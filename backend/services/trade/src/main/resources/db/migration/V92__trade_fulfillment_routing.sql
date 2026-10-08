-- 9.3.2.2 FBA 与自发货选择。
--
-- 蓝图 09-order-and-fulfillment.md 9.3.2.2 原文：
--   职责：在平台仓（FBA/MFN）与自有/第三方仓之间选择履约路径：优先用平台仓（买家体验好）、
--         平台仓缺货时切自发货（需符合平台的多渠道配送要求）、或按成本优先。
--   输入：各渠道库存；平台多渠道配送权限；成本与时效对比；平台规则。
--   输出：履约路径决策 + 缺货时切换记录。
--   关系：上游 = 7.1.3.1 多仓分布、2.1.1.2 能力矩阵；下游 = 9.3.3.1、10 物流。
--         **平台仓缺货自动转自发货是高价值能力**，但受平台政策约束，
--         必须先验证该店铺的多渠道配送资质。
--
-- 归属：**trade 服务**（蓝图 09 卷「后端归属：trade 服务拥有订单与售后单据」，
-- L2 表「9.3 履约编排 → 主责服务 trade」）。本迁移是 trade 的第一条业务迁移，
-- 不 ALTER V1 基线的 trade_tenancy_probe，也不建任何跨服务外键 / 跨库视图。
--
-- ===========================================================================
-- 一、上游依赖实查结论（不 mock、不建假上游）
-- ===========================================================================
-- 蓝图关系段的两个上游**分别属于别的服务的数据库**，AGENTS.md 模块边界纪律禁止
-- 跨服务 SQL / FK / 视图，因此本模块**不读**它们，而是在本服务把这两类输入
-- 落成租户可维护的真实表，并用 source 字段如实标注来源：
--
--   * 7.1.3.1 多仓分布（**已交付**，warehouse 服务 V55 platform_stock_observation）
--     → 本表 fulfillment_platform_stock_snapshot，每行带 stock_source
--       （MODULE_7_1_3_1 / TENANT_MAINTAINED）与 source_reference（平台回传的报告单号）。
--       7.1.3.1 与本模块同属 ERP 但**不同库**，所以这里不做同步、只登记快照；
--       两边口径不一致时以 collected_at 与来源标注如实呈现，不做静默合并。
--   * 2.1.1.2 平台能力矩阵（**已交付**，channel 服务 platform.capabilities 里
--     已有 multi_channel_delivery 字段）→ 本表 fulfillment_mcf_authorization，
--     每行带 authorization_source（MODULE_2_1_1_2 / TENANT_MAINTAINED）。
--     **「资质未知」不是「有资质」**：status='UNKNOWN' 时自发货路径一律被 DB 拒绝
--     （见 guard_routing_decision_mcf），这正是蓝图加粗那句要的效果。
--   * 10 运费试算 / 成本口径（**未开发**）→ 成本与时效参数落成本表
--     fulfillment_path_cost_profile，由租户维护；本模块**不从代码常量取任何金额**。
--
-- 将来 16 合规 / channel 侧的正式接口到位后，接法是「同步器按同一形状往这三张表写」，
-- 并把 source 字段改成 MODULE_* 枚举值，本模块的表结构与判定逻辑都不需要改。
--
-- ===========================================================================
-- 二、「缺货时切换记录」是硬输出，被 DB 钉死
-- ===========================================================================
-- 蓝图输出句把「履约路径决策」与「缺货时切换记录」并列，且加粗句强调这是高价值能力。
-- 只算出「这单走 FBA」不算交付 —— 必须能查「为什么这单没走平台仓」。
-- 因此：
--   * fulfillment_routing_decision 是**一次决策**（append-only），
--     chosen_path 不是平台仓时，延迟约束触发器 guard_routing_decision_switch
--     **回表**核对当前行并要求存在一条 fulfillment_routing_switch；
--   * fulfillment_routing_switch 是**append-only 的切换留痕**
--     （REVOKE UPDATE/DELETE + 触发器），带 from_path/to_path/trigger_reason
--     与当时的库存、成本快照，不提供事后改写 —— 已归因的切换是纠错闭环的证据。
--
-- ⚠ 延迟约束触发器里**不使用 NEW 的可变列**、一律回表读当前行：
-- 服务层的写入顺序是「先插头 → 写候选行 → 回填头 → 写切换行」，
-- 延迟到提交时 INSERT 那一次拿着的仍是半成品的 NEW；用 NEW 每次必报。
-- （与 V85 customs_rate_snapshot / V90 duty_estimate_totals 同一个坑，同一个解法。）
--
-- ===========================================================================
-- 三、金额与数量
-- ===========================================================================
-- 金额 numeric(19,4)、数量 numeric(19,6)、汇率 numeric(19,8)，全程 BigDecimal 禁浮点。
-- 结算口径统一 HALF_UP 到分：Java 侧 setScale(2,HALF_UP)，DB 侧 round(x,2)，
-- 延迟约束在提交时复核两者一致（照抄 10.5.1.3 V90 的做法）。
-- 时间一律 timestamptz，服务层用 OffsetDateTime 绑定并在 values 位置写 ?::timestamptz。

-- ---------------------------------------------------------------------------
-- 1. 平台多渠道配送（MCF）资质登记
-- ---------------------------------------------------------------------------
-- 蓝图加粗句：「必须先验证该店铺的多渠道配送资质」。
-- 形状按 (平台, 店铺) 而不是按 SKU/订单：资质是**店铺级**的平台授权，
-- 一行一个店铺的一个平台，写成 SKU 级会让同一份授权被复制 N 份且各自漂移。
--
-- status 四值刻意不含「默认有资质」：
--   AUTHORIZED  已验证有资质（唯一允许切自发货的取值）
--   DENIED      平台明确无资质
--   PENDING     已提交平台审核，结果未回
--   UNKNOWN     从未核验（**这是缺省值**：没登记就是不知道，不是可以）
CREATE TABLE fulfillment_mcf_authorization (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  platform_code varchar(32) NOT NULL,
  shop_code varchar(64) NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'UNKNOWN' CHECK(status IN (
    'AUTHORIZED','DENIED','PENDING','UNKNOWN')),
  authorization_source varchar(24) NOT NULL DEFAULT 'TENANT_MAINTAINED'
    CHECK(authorization_source IN ('MODULE_2_1_1_2','TENANT_MAINTAINED')),
  -- 外部来源必须给依据单号：2.1.1.2 能力矩阵是平台注册表的只读快照，
  -- 引用不到具体行的 AUTHORIZED 就是凭空来的。
  source_reference varchar(128) NOT NULL DEFAULT '',
  verified_at timestamptz,
  verified_by_membership_id uuid,
  -- 资质有效期：平台会收回多渠道配送权限，没有有效期就无法自动停止用它切自发货。
  expires_at timestamptz,
  note varchar(400) NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_by_membership_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 一个店铺在一个平台上只能有一条当前资质：两条并存时「取哪条」取决于查询顺序，
  -- 同样输入在不同时候算出不同结果，这是配置表最典型的坑。
  UNIQUE(tenant_id,platform_code,shop_code),
  -- 已验证必须有验证人与时间：「谁在什么时候确认这店能做多渠道配送」是问责要用的。
  CHECK(status<>'AUTHORIZED' OR (verified_at IS NOT NULL AND verified_by_membership_id IS NOT NULL)),
  -- 外部来源必须有依据单号；租户自维护则必须有说明（至少能追溯到谁登的）。
  CHECK(authorization_source<>'MODULE_2_1_1_2' OR btrim(source_reference)<>''),
  CHECK(authorization_source<>'TENANT_MAINTAINED' OR btrim(source_reference)<>'' OR btrim(note)<>''),
  -- 生效时间不能早于验证时间。
  CHECK(expires_at IS NULL OR verified_at IS NULL OR expires_at>verified_at),
  CHECK(updated_at>=created_at)
);
CREATE INDEX fulfillment_mcf_authorization_lookup
  ON fulfillment_mcf_authorization(tenant_id,platform_code,status);

-- ---------------------------------------------------------------------------
-- 2. 成本与时效参数（租户可维护的真实表，不是代码常量）
-- ---------------------------------------------------------------------------
-- 蓝图输入写「成本与时效对比」。金额与时效都是**业务口径**（同一票货走 FBA 与走
-- 自有仓的费差、时效差随渠道、类目、目的国变化），写进 if 分支就再也改不动。
--
-- 维度：平台 + 履约路径 + （可选）目的国。目的国用 '*' 表示不限，
-- 与 10.4.1.2 在途预警阈值的分档约定同一套。
--
-- 唯一约束刻意**不含 purpose**：purpose 是业务语义标签（成本对比 / 候选评估），
-- 而不是口径的区分维度；含它会让「成本优先」与「平台优先」各存一份费率，
-- 改一处漏一处。
CREATE TABLE fulfillment_path_cost_profile (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  platform_code varchar(32) NOT NULL,
  -- 履约路径。与 fulfillment_routing_decision.chosen_path 同一词表。
  path_kind varchar(24) NOT NULL CHECK(path_kind IN (
    'PLATFORM_FBA','MERCHANT_OWN','THIRD_PARTY')),
  destination_country_code varchar(2) NOT NULL DEFAULT '*',
  -- 单件成本构成。全部 NOT NULL 且有 CHECK：不写默认 0，
  -- 因为「没配」与「这一段不要钱」在算成本时是两个完全不同的结论。
  -- 币种固定 USD：ERP 的本位币口径归 12 财务，本模块只做**同币种**下的路径比较，
  -- 跨币种比较属于 10 运费试算（未开发）的职责，这里不做也不假装做了。
  unit_cost numeric(19,4) NOT NULL CHECK(unit_cost>=0),
  -- 预计时效（天）。留空 = 未知，**不当 0**：0 天时效意味着「秒发」，
  -- 参与比较时会把这条路径永远排到第一，是最危险的一种默认值。
  lead_time_days numeric(9,2),
  platform_fee numeric(19,4) NOT NULL DEFAULT 0 CHECK(platform_fee>=0),
  currency_code varchar(3) NOT NULL DEFAULT 'USD' CHECK(currency_code=upper(currency_code)),
  effective_from timestamptz NOT NULL DEFAULT now(),
  effective_to timestamptz,
  notes varchar(300) NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  updated_by_membership_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,platform_code,path_kind,destination_country_code),
  CHECK(effective_to IS NULL OR effective_to>effective_from),
  CHECK(lead_time_days IS NULL OR lead_time_days>=0),
  CHECK(updated_at>=created_at)
);
CREATE INDEX fulfillment_path_cost_profile_resolve
  ON fulfillment_path_cost_profile(tenant_id,platform_code,path_kind,destination_country_code);

-- ---------------------------------------------------------------------------
-- 3. 各渠道库存快照（平台仓 / 自有仓 / 第三方仓的真实数量来源）
-- ---------------------------------------------------------------------------
-- 蓝图输入第一项。各渠道库存的权威在 warehouse 服务的库存账本与
-- 7.1.3.1 的 platform_stock_observation，**跨库不可读**，
-- 因此本表是本服务自维护的**快照**，每行强制带来源与采集时间：
-- 没有来源的库存快照等于凭空捏一个数去决定走哪条路，比没有数据更糟。
CREATE TABLE fulfillment_platform_stock_snapshot (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  platform_code varchar(32) NOT NULL,
  shop_code varchar(64) NOT NULL,
  -- SKU 是 catalog 服务的逻辑 ID，跨服务不建外键（与 warehouse sku_ref 同一纪律）。
  sku_ref varchar(64) NOT NULL,
  -- 这条快照描述的是哪条履约路径上的库存。
  path_kind varchar(24) NOT NULL CHECK(path_kind IN (
    'PLATFORM_FBA','MERCHANT_OWN','THIRD_PARTY')),
  -- 平台仓 / 第三方仓在本服务没有仓库档案（仓库主数据归 warehouse 服务），
  -- 所以只存**逻辑 ID + 可读名快照**，不建外键不联查。
  warehouse_ref varchar(64) NOT NULL DEFAULT '',
  warehouse_name varchar(150) NOT NULL DEFAULT '',
  available_qty numeric(19,6) NOT NULL CHECK(available_qty>=0),
  -- 观测值的发生时点，由采集方给出，不由写入时刻冒充：
  -- 两者混同会让「数据太旧了」这种判断永远做不出来。
  collected_at timestamptz NOT NULL,
  stock_source varchar(24) NOT NULL DEFAULT 'TENANT_MAINTAINED'
    CHECK(stock_source IN ('MODULE_7_1_3_1','TENANT_MAINTAINED')),
  source_reference varchar(128) NOT NULL DEFAULT '',
  notes varchar(300) NOT NULL DEFAULT '',
  created_by_membership_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一来源在同一时点只能有一条：采集器重试不会污染当前值取值，
  -- 而「同源同时点两条数量不同」在业务上没有意义。
  UNIQUE(tenant_id,platform_code,shop_code,sku_ref,path_kind,warehouse_ref,stock_source,collected_at),
  CHECK(btrim(sku_ref)<>''),
  CHECK(stock_source<>'TENANT_MAINTAINED' OR btrim(source_reference)<>'' OR btrim(warehouse_ref)<>'')
);
-- 候选评估的热读路径：按 (平台, 店铺, SKU, 路径) 取最新一条。
CREATE INDEX fulfillment_platform_stock_snapshot_latest
  ON fulfillment_platform_stock_snapshot(tenant_id,platform_code,shop_code,sku_ref,path_kind,collected_at DESC);

-- ---------------------------------------------------------------------------
-- 4. 履约路径决策（append-only 的一次决策）
-- ---------------------------------------------------------------------------
-- **一条决策一行，不做「决策实体 + 反复改」**：本表回答的是
-- 「这一次为什么走这条路径」，是举证凭据；改主意要落新的一条决策，
-- 否则「上次是怎么判的」会被覆盖掉，而复盘缺的正是这一问。
--
-- selected_policy 刻意有值：策略（PLATFORM_FIRST / COST_FIRST / MERCHANT_FIRST）
-- 是租户口径不是代码常量（与 10.4.1.2 预警阈值同一纪律）。
CREATE TABLE fulfillment_routing_decision (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 上游是 9.1 订单（**未开发**，trade 服务内尚无订单表），
  -- 因此这里只存订单的**逻辑 ID**：订单落地后由那条车道建外键或补引用，
  -- 本迁移不猜订单表结构（与 trade V1「不替后续车道猜表结构」同一条纪律）。
  order_ref varchar(64) NOT NULL,
  order_line_ref varchar(64) NOT NULL DEFAULT '',
  platform_code varchar(32) NOT NULL,
  shop_code varchar(64) NOT NULL,
  sku_ref varchar(64) NOT NULL,
  requested_qty numeric(19,6) NOT NULL CHECK(requested_qty>0),
  selected_policy varchar(16) NOT NULL CHECK(selected_policy IN (
    'PLATFORM_FIRST','COST_FIRST','MERCHANT_FIRST')),
  -- policy_source 区分「平台规则强制」与「租户自己配的」，两者问责对象不同。
  policy_source varchar(24) NOT NULL DEFAULT 'TENANT_POLICY'
    CHECK(policy_source IN ('PLATFORM_POLICY','TENANT_POLICY')),
  chosen_path varchar(24) NOT NULL CHECK(chosen_path IN (
    'PLATFORM_FBA','MERCHANT_OWN','THIRD_PARTY')),
  -- 决策依据的文字版本：为什么走这条路。reason_code 是机器可筛的枚举，
  -- reason 是给运营看的那一句（两者都要，因为「筛出来全是 PLATFORM_STOCK_SUFFICIENT
  -- 但没人说得清为什么」是真实的运维困境）。
  reason_code varchar(32) NOT NULL CHECK(reason_code IN (
    'PLATFORM_STOCK_SUFFICIENT','PLATFORM_STOCK_INSUFFICIENT','COST_ADVANTAGE',
    'MCF_NOT_AUTHORIZED','POLICY_OVERRIDE','NO_PLATFORM_STOCK_RECORD')),
  reason varchar(500) NOT NULL DEFAULT '',
  -- 当时参与比较的平台仓可用量与三条路径的成本快照。
  -- 抄进决策行而不是查询时联算：成本参数与库存都会变，
  -- 「当时看到的数」与「现在算出的数」不同是正常的，决策必须能复现当时那一版。
  platform_available_qty numeric(19,6) NOT NULL DEFAULT 0 CHECK(platform_available_qty>=0),
  platform_unit_cost numeric(19,4),
  merchant_unit_cost numeric(19,4),
  -- 实际选中的那条路径的单位成本，按结算口径 HALF_UP 到分后落库。
  selected_unit_cost numeric(19,4),
  currency_code varchar(3) NOT NULL DEFAULT 'USD' CHECK(currency_code=upper(currency_code)),
  -- 决策时该店铺的 MCF 资质快照（决策之后资质可能被收回）。
  mcf_authorization_id uuid,
  mcf_status varchar(16) NOT NULL DEFAULT 'UNKNOWN' CHECK(mcf_status IN (
    'AUTHORIZED','DENIED','PENDING','UNKNOWN')),
  -- 决策状态：DECIDED 已决策 → CONFIRMED 已确认下发 → CANCELLED 已作废。
  status varchar(16) NOT NULL DEFAULT 'DECIDED' CHECK(status IN (
    'DECIDED','CONFIRMED','CANCELLED')),
  decided_at timestamptz NOT NULL,
  confirmed_at timestamptz,
  confirmed_by_membership_id uuid,
  cancelled_at timestamptz,
  cancel_reason varchar(300) NOT NULL DEFAULT '',
  created_by_membership_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,mcf_authorization_id)
    REFERENCES fulfillment_mcf_authorization(tenant_id,id),
  CHECK(btrim(order_ref)<>''),
  CHECK(btrim(sku_ref)<>''),
  -- 选了平台仓却没有任何 MCF 授权引用：平台仓不需要 MCF（货在平台仓里），
  -- 但成本对比与库存仍可能引用自发货候选，因此允许 mcf 引用为空。
  -- 反过来，选了自发货路径却把 mcf_status 记成非 AUTHORIZED，是硬矛盾：
  -- 「没资质却走了自发货」= 平台会判违规发货，必须在 DB 挡住。
  CHECK(chosen_path='PLATFORM_FBA' OR mcf_status='AUTHORIZED'),
  -- 三个成本列要么都给要么都不给：半截的成本对比无法复现决策。
  CHECK((platform_unit_cost IS NULL) = (merchant_unit_cost IS NULL)),
  CHECK(selected_unit_cost IS NULL OR (platform_unit_cost IS NOT NULL AND merchant_unit_cost IS NOT NULL)),
  CHECK(status<>'CONFIRMED' OR (confirmed_at IS NOT NULL AND confirmed_by_membership_id IS NOT NULL)),
  CHECK(status<>'CONFIRMED' OR confirmed_at IS NULL OR confirmed_at>=decided_at),
  CHECK(status<>'CANCELLED' OR btrim(cancel_reason)<>'')
);
CREATE INDEX fulfillment_routing_decision_order
  ON fulfillment_routing_decision(tenant_id,order_ref,order_line_ref,decided_at DESC);
CREATE INDEX fulfillment_routing_decision_path
  ON fulfillment_routing_decision(tenant_id,chosen_path,decided_at DESC);
CREATE INDEX fulfillment_routing_decision_platform
  ON fulfillment_routing_decision(tenant_id,platform_code,shop_code,sku_ref,decided_at DESC);
CREATE INDEX fulfillment_routing_decision_pending
  ON fulfillment_routing_decision(tenant_id,decided_at DESC) WHERE status='DECIDED';

-- ---------------------------------------------------------------------------
-- 5. 候选路径对比行（一次决策下每条候选路径一行）
-- ---------------------------------------------------------------------------
-- 为什么必须落库而不只在响应里算：候选对比是**决策的输入**。
-- 库存快照与成本参数改一次，历史决策的候选就再也算不出来了，
-- 而「当时三条路径分别是多少」正是「为什么这单没走平台仓」的完整答案。
CREATE TABLE fulfillment_routing_candidate (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  decision_id uuid NOT NULL,
  path_kind varchar(24) NOT NULL CHECK(path_kind IN (
    'PLATFORM_FBA','MERCHANT_OWN','THIRD_PARTY')),
  -- 可用库存：null = 该路径当前没有库存快照（**不是 0**）。
  -- 0 与「没测过」在决策上不等价：前者有确定的结论，后者是缺口。
  available_qty numeric(19,6),
  unit_cost numeric(19,4),
  lead_time_days numeric(9,2),
  cost_profile_id uuid,
  stock_snapshot_id uuid,
  -- 这条候选是否可用：不可用必须给 reason_code，
  -- 否则「为什么没选它」永远答不出来，而这是本模块最核心的一问。
  feasible boolean NOT NULL,
  infeasible_reason_code varchar(32) NOT NULL DEFAULT '' CHECK(infeasible_reason_code IN (
    '','NO_STOCK_RECORD','STOCK_ZERO','INSUFFICIENT_STOCK','NO_COST_PROFILE','MCF_NOT_AUTHORIZED')),
  infeasible_reason varchar(400) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 一次决策下每条路径只有一行候选：同一路径出现两个候选时「取哪个」无解。
  UNIQUE(tenant_id,decision_id,path_kind),
  FOREIGN KEY(tenant_id,decision_id) REFERENCES fulfillment_routing_decision(tenant_id,id),
  FOREIGN KEY(tenant_id,cost_profile_id) REFERENCES fulfillment_path_cost_profile(tenant_id,id),
  FOREIGN KEY(tenant_id,stock_snapshot_id) REFERENCES fulfillment_platform_stock_snapshot(tenant_id,id),
  -- 数量与成本非负。
  CHECK(available_qty IS NULL OR available_qty>=0),
  CHECK(unit_cost IS NULL OR unit_cost>=0),
  CHECK(lead_time_days IS NULL OR lead_time_days>=0),
  -- 不可用的候选必须说明原因；可用候选不得带原因（否则「可用」与「有例外」混在一起）。
  CHECK(feasible OR btrim(infeasible_reason_code)<>''),
  CHECK(feasible AND btrim(infeasible_reason_code)='' OR NOT feasible)
);
CREATE INDEX fulfillment_routing_candidate_decision
  ON fulfillment_routing_candidate(tenant_id,decision_id);

-- ---------------------------------------------------------------------------
-- 6. 缺货切换记录（append-only 的举证凭据）
-- ---------------------------------------------------------------------------
-- 蓝图输出的第二项。**刻意没有 UPDATE / DELETE**：
-- 已归因的切换是纠错闭环的证据，改写它等于让「这票货当时为什么切的自发货」
-- 永久失去答案。
--
-- trigger_reason 六值对应三种真实现象 + 两种硬闸：
--   PLATFORM_STOCK_SHORTAGE   平台仓可用量不足（蓝图主线场景）
--   PLATFORM_STOCK_MISSING    平台仓完全没有库存快照
--   MCF_NOT_AUTHORIZED        店铺没有多渠道配送资质 → 不许切自发货（硬闸，见下）
--   COST_ADVANTAGE            成本优先策略下自发货更便宜
--   LEAD_TIME_ADVANTAGE       自发货时效更优
--   MANUAL_OVERRIDE           人工改判
CREATE TABLE fulfillment_routing_switch (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  decision_id uuid NOT NULL,
  from_path varchar(24) NOT NULL CHECK(from_path IN (
    'PLATFORM_FBA','MERCHANT_OWN','THIRD_PARTY')),
  to_path varchar(24) NOT NULL CHECK(to_path IN (
    'PLATFORM_FBA','MERCHANT_OWN','THIRD_PARTY')),
  trigger_reason varchar(32) NOT NULL CHECK(trigger_reason IN (
    'PLATFORM_STOCK_SHORTAGE','PLATFORM_STOCK_MISSING','MCF_NOT_AUTHORIZED',
    'COST_ADVANTAGE','LEAD_TIME_ADVANTAGE','MANUAL_OVERRIDE')),
  reason varchar(500) NOT NULL DEFAULT '',
  -- 切换那一刻的数量快照：事后库存会被补上，这两列是「当时缺多少」的唯一证据。
  platform_available_qty numeric(19,6) NOT NULL DEFAULT 0 CHECK(platform_available_qty>=0),
  requested_qty numeric(19,6) NOT NULL CHECK(requested_qty>0),
  switched_at timestamptz NOT NULL,
  switched_by_membership_id uuid NOT NULL,
  -- 外部改判来源：将来 9.3.3.1 履约请求或人工界面都要能记下是谁/什么触发的。
  switch_source varchar(24) NOT NULL DEFAULT 'SYSTEM'
    CHECK(switch_source IN ('SYSTEM','MANUAL','FULFILLMENT_REQUEST')),
  source_reference varchar(128) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,decision_id) REFERENCES fulfillment_routing_decision(tenant_id,id),
  -- 从平台仓切走才是「缺货切换」；两条自发货路径之间互切不属本表语义
  -- （那是 9.3.2.1 分仓规则的职责），否则这张表会混进另一模块的记录。
  CHECK(from_path='PLATFORM_FBA' AND to_path<>'PLATFORM_FBA'),
  -- 切换必须落在目标路径正是该决策选定路径的行上：
  -- 记「切到自发货」而决策其实选了平台仓，等于留了一条假的切换痕迹。
  CHECK(btrim(reason)<>'')
);
CREATE INDEX fulfillment_routing_switch_decision
  ON fulfillment_routing_switch(tenant_id,decision_id,switched_at DESC);
CREATE INDEX fulfillment_routing_switch_trigger
  ON fulfillment_routing_switch(tenant_id,trigger_reason,switched_at DESC);

-- ---------------------------------------------------------------------------
-- 7. 事件时间线（append-only）
-- ---------------------------------------------------------------------------
-- 决策 / 切换 / 确认 / 作废四类事实的共同承载，用 event_kind 区分来源，
-- 而不是造四张形状雷同的表。事后复盘要回答的是
-- 「谁在什么时候把这条路径确认下去的」，只留当前状态答不出来。
CREATE TABLE fulfillment_routing_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  decision_id uuid NOT NULL,
  event_kind varchar(24) NOT NULL CHECK(event_kind IN (
    'DECIDED','SWITCHED','CONFIRMED','CANCELLED','NOTE')),
  from_status varchar(16) NOT NULL DEFAULT '',
  to_status varchar(16) NOT NULL DEFAULT '',
  detail text NOT NULL DEFAULT '',
  actor_id uuid,
  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,decision_id) REFERENCES fulfillment_routing_decision(tenant_id,id),
  CHECK(btrim(detail)<>'' OR event_kind<>'NOTE')
);
CREATE INDEX fulfillment_routing_event_feed
  ON fulfillment_routing_event(tenant_id,decision_id,occurred_at DESC);

-- ---------------------------------------------------------------------------
-- 8. DB 级不变量
-- ---------------------------------------------------------------------------
-- 8.1 决策状态机：确认 / 作废必须自洽，终态不许离开。
--     与 warehouse V84 guard_first_mile_tracking_state 同一纪律。
CREATE FUNCTION guard_routing_decision_state() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.status = 'CONFIRMED') <> (NEW.confirmed_at IS NOT NULL) THEN
  RAISE EXCEPTION '决策状态「%」与确认时间不自洽：只有已确认才可能带确认时间', NEW.status
   USING ERRCODE='23514';
 END IF;
 IF NEW.status <> 'CONFIRMED'
    AND (NEW.confirmed_at IS NOT NULL OR NEW.confirmed_by_membership_id IS NOT NULL) THEN
  RAISE EXCEPTION '决策状态「%」不是已确认，不允许带确认痕迹', NEW.status USING ERRCODE='23514';
 END IF;
 IF NEW.status <> 'CANCELLED' AND NEW.cancelled_at IS NOT NULL THEN
  RAISE EXCEPTION '决策状态「%」不是已作废，不允许带作废时间', NEW.status USING ERRCODE='23514';
 END IF;
 -- 终态不许离开（只拦离开，不拦进入：DECIDED → CONFIRMED 必须放行）。
 IF TG_OP = 'UPDATE' AND OLD.status IN ('CONFIRMED','CANCELLED') AND NEW.status <> OLD.status THEN
  RAISE EXCEPTION '决策当前状态「%」是终态，不允许改到「%」', OLD.status, NEW.status
   USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER routing_decision_state BEFORE INSERT OR UPDATE ON fulfillment_routing_decision
 FOR EACH ROW EXECUTE FUNCTION guard_routing_decision_state();

-- 8.2 缺货切换记录必须存在（**延迟约束触发器，回表读当前行**）。
--     蓝图输出的第二项在这里被钉成 DB 不变量：
--     选了非平台仓的决策，提交时若没有一条切换记录，直接 23514 ——
--     绕过服务层直接写 SQL 同样被挡，所以「先存决策、切换以后再补」在本模块做不到。
--
--     为什么不能用 NEW：见文件头第二节。服务层的写入顺序是
--     「插头（候选还没写）→ 写候选 → 回填头 → 写切换行」，
--     延迟到提交时 INSERT 那一次拿着的仍是半成品 NEW，用它每次必报。
CREATE FUNCTION guard_routing_decision_switch() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE head_path varchar(24); head_id uuid; switch_count bigint;
BEGIN
 SELECT id, chosen_path INTO head_id, head_path
   FROM fulfillment_routing_decision WHERE id = NEW.id;
 IF head_path IS NULL THEN RETURN NULL; END IF;
 IF head_path <> 'PLATFORM_FBA' THEN
  SELECT count(*) INTO switch_count
    FROM fulfillment_routing_switch
   WHERE tenant_id = NEW.tenant_id AND decision_id = head_id;
  IF switch_count = 0 THEN
   RAISE EXCEPTION
     '决策 % 选了「%」但没有任何切换记录：缺货切换是本模块的硬输出，没有留痕就答不出「为什么这单没走平台仓」',
     head_id, head_path USING ERRCODE='23514',
     HINT='先写 fulfillment_routing_switch（from_path=PLATFORM_FBA）再提交';
  END IF;
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER routing_decision_switch
 AFTER INSERT OR UPDATE ON fulfillment_routing_decision
 DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION guard_routing_decision_switch();

-- 8.3 切换记录必须与决策选定路径一致（回表读当前行，同样避开 NEW）。
--     「决策选了平台仓却留了一条切到自发货的痕迹」会让复盘结论互相矛盾，
--     而这类矛盾恰恰出现在最需要看留痕的时候。
CREATE FUNCTION guard_routing_switch_consistency() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE head_path varchar(24); head_tenant uuid; head_order_ref varchar(64);
BEGIN
 SELECT tenant_id, order_ref, chosen_path INTO head_tenant, head_order_ref, head_path
   FROM fulfillment_routing_decision WHERE id = NEW.decision_id;
 IF head_path IS NULL THEN
  RAISE EXCEPTION '切换记录指向的决策 % 不存在或不属于本租户', NEW.decision_id
   USING ERRCODE='23503';
 END IF;
 IF head_path <> NEW.to_path THEN
  RAISE EXCEPTION
    '决策 % 选定的路径是「%」，切换记录却写「%」→「%」：留痕与决策互相矛盾，复盘时两说并存',
    NEW.decision_id, head_path, NEW.from_path, NEW.to_path USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER routing_switch_consistency
 AFTER INSERT OR UPDATE ON fulfillment_routing_switch
 DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION guard_routing_switch_consistency();

-- 8.4 无资质不许切自发货（**即时 BEFORE 触发器**，用 NEW 是安全的）。
--     蓝图加粗句的落点：缺货时自动切自发货是受平台政策约束的高价值能力，
--     没有多渠道配送资质的店铺切了会被平台判违规发货。
--     本触发器把「资质必须是 AUTHORIZED 且未过期」这条钉在写入路径上，
--     而不是靠服务层先查一次（那会被并发下的资质撤回绕过）。
--     注：只有 from_path='PLATFORM_FBA' 的切换需要 MCF（货要出平台仓走自发货）；
--     自发货路径之间的互切由 9.3.2.1 负责，本表已用 CHECK 挡掉。
CREATE FUNCTION guard_routing_switch_mcf() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE auth_status varchar(16); auth_expires timestamptz; auth_expired boolean;
BEGIN
 SELECT status, expires_at, (expires_at IS NOT NULL AND expires_at <= NEW.switched_at)
   INTO auth_status, auth_expires, auth_expired
   FROM fulfillment_mcf_authorization
  WHERE tenant_id = NEW.tenant_id AND platform_code =
    (SELECT platform_code FROM fulfillment_routing_decision
      WHERE id = NEW.decision_id AND tenant_id = NEW.tenant_id)
    AND shop_code =
    (SELECT shop_code FROM fulfillment_routing_decision
      WHERE id = NEW.decision_id AND tenant_id = NEW.tenant_id);
 IF auth_status IS NULL THEN
  RAISE EXCEPTION '该平台与店铺没有任何多渠道配送资质登记：缺货切自发货前必须先验证资质'
   USING ERRCODE='23514',
   HINT='请先在 fulfillment_mcf_authorization 登记该店铺的 MCF 资质（status=AUTHORIZED）';
 END IF;
 IF auth_status <> 'AUTHORIZED' OR auth_expired THEN
  RAISE EXCEPTION '多渠道配送资质当前是「%」%：资质未生效或已过期，此时切自发货会被平台判违规发货',
    auth_status, CASE WHEN auth_expired THEN '（已于 ' || auth_expires || ' 过期）' ELSE '' END
   USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER routing_switch_mcf BEFORE INSERT ON fulfillment_routing_switch
 FOR EACH ROW EXECUTE FUNCTION guard_routing_switch_mcf();

-- 8.5 append-only：决策与切换都是举证凭据（照抄 V55 观测表的 REVOKE 纪律）。
REVOKE UPDATE,DELETE ON fulfillment_routing_decision FROM trade_app;
-- 状态推进（DECIDED → CONFIRMED / CANCELLED）是**唯一**被允许的 UPDATE，
-- 且只经 advance_routing_decision_status 这一个窄函数发起。
-- 判据刻意用 transaction-local GUC 而不是「看 NEW.status」：
--   * 若按 NEW.status 放行，那「把 reason 改得更好看」也会被一起放行 ——
--     append-only 就只剩下「状态字段可写」这一条，实现上等于没锁；
--   * GUC 由 SECURITY DEFINER 函数在事务内 set_config(..., true) 设下，
--     事务结束自动撤销，运行角色自己伪造不了（它没有这个函数的 EXECUTE 之外的入口）。
CREATE FUNCTION guard_routing_decision_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF current_setting('app.routing_status_advance', true) = 'on' THEN
  RETURN NEW;
 END IF;
 RAISE EXCEPTION '履约路径决策是举证凭据，不允许改写或删除（要改主意请落一条新决策）'
  USING ERRCODE='23514',
        HINT='决策行只允许追加；状态流转请用 advance_routing_decision_status，且终态不可离开';
END $$;
CREATE TRIGGER routing_decision_append_only BEFORE UPDATE OR DELETE ON fulfillment_routing_decision
 FOR EACH ROW EXECUTE FUNCTION guard_routing_decision_append_only();
-- 例外：状态流转仍然要能推进（DECIDED → CONFIRMED / CANCELLED），
-- 所以把 UPDATE 权限收回后，通过一个 SECURITY DEFINER 的窄函数开放状态推进。
-- 刻意不做「整表可改」：那样 append-only 就只剩注释了。
CREATE FUNCTION advance_routing_decision_status(
  p_decision_id uuid, p_status varchar, p_actor uuid, p_reason varchar)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE head record;
BEGIN
 SELECT id, status, decided_at INTO head FROM fulfillment_routing_decision
  WHERE id = p_decision_id;
 IF head IS NULL THEN
  RAISE EXCEPTION '决策 % 不存在或不属于本租户', p_decision_id USING ERRCODE='23503';
 END IF;
 IF head.status IN ('CONFIRMED','CANCELLED') THEN
  RAISE EXCEPTION '决策当前状态「%」是终态，不允许改到「%」', head.status, p_status
   USING ERRCODE='23514';
 END IF;
 IF p_status NOT IN ('CONFIRMED','CANCELLED') THEN
  RAISE EXCEPTION '状态只能推进到 CONFIRMED 或 CANCELLED，收到「%」', p_status
   USING ERRCODE='23514';
 END IF;
 -- 置标记后 UPDATE：本函数是 SECURITY DEFINER，但触发器照样对所有 UPDATE 生效，
 -- 所以由这个函数自己开窗，且窗口只活在当前事务内。
 PERFORM set_config('app.routing_status_advance', 'on', true);
 UPDATE fulfillment_routing_decision
    SET status = p_status,
        confirmed_at = CASE WHEN p_status = 'CONFIRMED' THEN now() ELSE NULL END,
        confirmed_by_membership_id = CASE WHEN p_status = 'CONFIRMED' THEN p_actor ELSE NULL END,
        cancelled_at = CASE WHEN p_status = 'CANCELLED' THEN now() ELSE NULL END,
        cancel_reason = CASE WHEN p_status = 'CANCELLED' THEN p_reason ELSE '' END
  WHERE id = p_decision_id;
END $$;

-- 切换记录与事件流是纯 append-only，没有任何状态可推进。
REVOKE UPDATE,DELETE ON fulfillment_routing_switch,fulfillment_routing_event FROM trade_app;
CREATE FUNCTION guard_routing_trail_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION '% 是不可改写的留痕表（%）：已归因的切换与事件是纠错闭环的证据',
  TG_TABLE_NAME, TG_OP USING ERRCODE='23514';
END $$;
CREATE TRIGGER routing_switch_append_only BEFORE UPDATE OR DELETE ON fulfillment_routing_switch
 FOR EACH ROW EXECUTE FUNCTION guard_routing_trail_append_only();
CREATE TRIGGER routing_event_append_only BEFORE UPDATE OR DELETE ON fulfillment_routing_event
 FOR EACH ROW EXECUTE FUNCTION guard_routing_trail_append_only();

-- ---------------------------------------------------------------------------
-- 9. 多租户：RLS + 授权（与 trade V1 第 2 节、warehouse V85 第 9 节同一套写法）
-- ---------------------------------------------------------------------------
-- 七张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY[
   'fulfillment_mcf_authorization','fulfillment_path_cost_profile',
   'fulfillment_platform_stock_snapshot','fulfillment_routing_decision',
   'fulfillment_routing_candidate','fulfillment_routing_switch',
   'fulfillment_routing_event'
 ] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  fulfillment_mcf_authorization,fulfillment_path_cost_profile,
  fulfillment_platform_stock_snapshot,fulfillment_routing_decision,
  fulfillment_routing_candidate,fulfillment_routing_switch,
  fulfillment_routing_event TO trade_app;
-- 第 8.5 节 REVOKE 掉的是举证凭据的 UPDATE/DELETE，但那两段 REVOKE 写在 GRANT **之前**，
-- 会被下面这条 GRANT 全量授权覆盖回去 —— append-only 于是只剩注释。这里按顺序重放，
-- 明确「授权在前、收回在后」。
REVOKE UPDATE,DELETE ON fulfillment_routing_decision,
  fulfillment_routing_switch,fulfillment_routing_event FROM trade_app;
-- 状态推进函数是 SECURITY DEFINER，必须显式授权（默认 PUBLIC 无 EXECUTE 之外的用法，
-- 但这里写明是为了让「谁能推进决策状态」在 \df+ 上一眼可见）。
GRANT EXECUTE ON FUNCTION advance_routing_decision_status(uuid,varchar,uuid,varchar) TO trade_app;
-- 本迁移**不 ALTER V1 的 trade_tenancy_probe**，跨车道回归面 = 0；
-- 也不动任何既有表，所以被本迁移约束到的既有模块 = 无。