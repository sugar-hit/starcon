-- 9.3.2.1 分仓规则。
--
-- 蓝图 09-order-and-fulfillment.md 9.3.2.1 原文：
--   职责：配置并执行仓库分配：按库存可用性、运费成本、时效承诺、货权归属、买家地区
--         匹配度选择发货仓；支持优先级与兜底策略。
--   输入：分仓规则配置；订单收货信息；各仓库存与运费估算。
--   输出：分配结果（发货仓 + 分配数量）；无法分配的异常挂起。
--   关系：上游 = 7.1.1.3 可用量、10 运费试算；下游 = 9.3.3.1、8 出库单。
--         **货权与成本要参与分仓决策**：从 A 法人的仓发货意味着收入记在 A 法人，
--         不能只看运费便宜。
--
-- 归属：**trade 服务**（蓝图 09 卷「后端归属：trade 服务拥有订单与售后单据」，
-- L2 表「9.3 履约编排 → 主责服务 trade」）。与 9.3.2.2 同一条履约链上**更靠前的一环**：
-- 9.3.2.2 选的是「走平台仓还是自发货」，本模块选的是「从哪个仓发」。
--
-- ===========================================================================
-- 零、与 9.3.2.2（V92）的边界：不碰它的表，只并列建自己的命名空间
-- ===========================================================================
-- V92 的七张表（fulfillment_mcf_authorization / fulfillment_path_cost_profile /
-- fulfillment_platform_stock_snapshot / fulfillment_routing_decision /
-- fulfillment_routing_candidate / fulfillment_routing_switch / fulfillment_routing_event）
-- 是 9.3.2.2 的**唯一写入所有者**。本迁移：
--   * **一条 ALTER 都不发**到那七张表（下面第 9 节会显式声明 ALTER 面 = 0）；
--   * 八张新表全部落在 fulfillment_warehouse_allocation_* 命名空间下，与
--     fulfillment_routing_* 并列而不混用；
--   * **不 JOIN、不读** V92 的任何一张表。特别是不复用它的
--     fulfillment_path_cost_profile：那本模块的「路径成本」是 FBA vs 自发货的
--     费率口径，本模块要的是「某仓发往某目的地」的**每单运费估算**，
--     两者维度不同（前者 (平台,路径,目的国)，后者 (仓,SKU,目的国)）。
--     复用它等于跨模块读别人的事实表，将来那条车道改一列就静默改掉本模块的判定。
--     所以这里建自己的口径表 `..._shipping_estimate`，两者可以由人核对，但不共享。
--
-- ===========================================================================
-- 一、上游依赖实查结论（不 mock、不建假上游）
-- ===========================================================================
-- 蓝图输入写「各仓库存与运费估算」「订单收货信息」。逐项实查：
--
--   * 7.1.1.3 可用量（**warehouse 服务**，独立数据库）
--     → 跨服务 SQL / FK / 视图被 AGENTS.md 明令禁止，故本模块把各仓可用量登记为
--       租户可维护的真实表 fulfillment_warehouse_allocation_stock_estimate，
--       每行强制带 stock_source（MODULE_7_1_1_3 / TENANT_MAINTAINED）与
--       collected_at（观测值的发生时点，由采集方给出，不许用写入时刻冒充）。
--   * 10 运费试算（**未开发**）
--     → 运费与时效估算落 fulfillment_warehouse_allocation_shipping_estimate，
--       带 cost_source（MODULE_10_1_2_1 / TENANT_MAINTAINED）。
--       本模块**不从代码常量取任何金额**（照抄 V92 第 1 节同一纪律）。
--   * 订单收货信息（9.1.1.2 订单详情，**未开发**）
--     → 只存 order_ref / order_line_ref 逻辑引用 + 收货快照列
--       （destination_country_code / destination_region_code / order_legal_entity_code），
--       **不校验订单存在性**，并在 capabilities 里显式报出该维度 available=false。
--   * 下游 9.3.3.1 履约请求、8 出库单（**均未开发**）
--     → 本模块只产出分配结果，**不预占 warehouse 库存**：预占要调 warehouse 的
--       授权 API，那是下游车道的职责。现在假装预占，等那条车道落地时
--       会出现「两边都以为占了同一批货」。
--
-- ===========================================================================
-- 二、货权（legal_entity）如何参与判定 —— 本模块的验收重点
-- ===========================================================================
-- 蓝图加粗句：「**货权与成本要参与分仓决策**：从 A 法人的仓发货意味着收入记在 A 法人，
-- 不能只看运费便宜。」把它拆成**两层**落在 DB 上，而不是做成输出里的一个附注字段：
--
--   第 1 层：货权归属是**仓库的属性**，所以建档在
--     fulfillment_warehouse_allocation_warehouse.legal_entity_code
--     （该仓的货记在哪个法人名下）。它不是挂在分配结果上的备注 ——
--     判定时每个候选仓都要先把自己的法人报出来。
--
--   第 2 层：两条不同的作用路径，**同时**可用：
--     (a) **硬闸**：rule.require_legal_entity_match = true 时，法人不符的仓**直接不可行**，
--         排除原因 LEGAL_ENTITY_MISMATCH。这条由 BEFORE 触发器
--         guard_allocation_candidate_legal_entity 钉在**写入路径**上：
--         绕过服务层直接写 SQL 也无法让一个货权不符的仓成为选中仓。
--         「收入记在哪个法人」是记账正确性，不是可权衡项 —— 所以它必须是闸，不是分。
--     (b) **权重**：rule.legal_entity_weight 参与总分，legal_entity_policy 决定法人维度
--         的得分（订单货权主体优先 / 指定法人优先 / 不偏好）。
--         即便开了硬闸，同属订单法人的多个仓之间仍靠这一维排序 ——
--         「都合法」不等于「一样好」。
--
-- 兜底策略 CROSS_LEGAL_ENTITY 是**唯一**能放宽硬闸的开关，且必须显式配：
-- 开了之后分配结果里 cross_legal_entity=true 会被如实报出，让调用方无法忽略
-- 「这单收入记在了别的法人名下」。
--
-- ===========================================================================
-- 三、判定顺序：与 9.3.2.2 的「资格 → 库存 → 成本」自洽
-- ===========================================================================
-- V92 把 MCF 资质闸门排在成本比较**之前**，理由是「有货有便宜但没资质」在平台上非法。
-- 本模块沿用同一条纪律，并把五个维度按同样的先后装进去：
--
--   1. 资格（qualification）：仓库是否启用、是否被规则允许/禁止、
--      **货权硬闸**。货权排在最前，因为它和资质一样是「能不能发」的前提，
--      不是「发哪个更好」。
--   2. 库存（stock）：有库存记录（不是 0，也不是 null）且数量够。
--      0 与「没测过」不等价 —— 前者有确定结论，后者是缺口。
--   3. 成本（cost）：有运费估算与时效估算。
--   4. 打分排序（scoring）：库存 / 成本 / 时效 / **货权** / 地区 五个维度加权，
--      再按 selection_policy 的主排序键 + 确定性 tiebreak 定序。
--
-- 一句话说明自洽性：**货权进第 1 层做闸、进第 4 层做权重** ——
-- 与「资质进第 1 层、库存进第 2 层、成本进第 3 层」是同一种纪律：
-- 不合规的先出局，合规的再比好。
--
-- ===========================================================================
-- 四、无法分配 = 真正的挂起终态，绝不静默兜底
-- ===========================================================================
-- 蓝图输出句把「分配结果」与「无法分配的异常挂起」并列，且明确是「异常挂起」。
-- 因此：
--   * fulfillment_warehouse_allocation_decision.status 的 SUSPENDED 是**终态之一**，
--     此时 chosen_warehouse_ref 与 allocated_qty 必须为空；
--   * 每个被排除的候选仓在 ..._candidate 里**逐条**记
--     infeasible_reason_code + infeasible_reason（为什么没选它永远答得出来）；
--   * ..._hold 记挂起的聚合原因与**全量枚举**的排除清单 —— 这是「异常挂起」的载体；
--   * 延迟约束触发器（提交时回表读当前行）双向钉死：
--       ALLOCATED ⇒ 必须存在一条 selected 候选，且仓号与数量与决策行一致；
--       SUSPENDED ⇒ 必须存在一条 hold，且**一条 selected 候选都没有**。
--     绕过服务层直接写 SQL 同样被挡，所以「先写一条已分配，以后再补依据」做不到。
--
-- 部分分配（fallback_policy = ALLOW_PARTIAL）是**唯一**允许 allocated_qty < requested_qty
-- 的情形，且差额必须由一条 hold 记录挂起，界面上不会显示成「已全部分配」。
--
-- ===========================================================================
-- 五、候选落库而不是查询时联算
-- ===========================================================================
-- 候选行（..._candidate）抄下决策那一刻每个仓的：可用量、运费、时效、法人、地区、
-- 五维得分与总分、可行与否、排除原因。与 V92 同一理由：库存会被补上、
-- 运费估算会被改、规则权重会被调，查询时联算会让「当时为什么分了 A 仓」
-- 永远答不出来。决策/候选/挂起/事件一律 **append-only**。
--
-- ⚠ 延迟约束触发器里**不使用 NEW 的可变列**、一律回表读当前行：
-- 服务层写入顺序是「头 → 候选 → 挂起 → 事件」，延迟到提交时 INSERT 那一次
-- 拿着的仍是半成品 NEW，用它每次必报。与 V92 guard_routing_decision_switch 同一解法。
--
-- ===========================================================================
-- 六、金额与数量
-- ===========================================================================
-- 金额 numeric(19,4)、数量 numeric(19,6)、得分与权重 numeric(9,4)、
-- 时效天数 numeric(9,2)，全程 BigDecimal 禁浮点。
-- 结算口径统一 HALF_UP 到分：Java 侧 setScale(2,HALF_UP)，DB 侧 round(x,2)，
-- 延迟约束在提交时复核两者一致（照抄 10.5.1.3 V90 的做法）。
-- 时间一律 timestamptz，服务层用 OffsetDateTime 绑定并在 values 位置写 ?::timestamptz。

-- ---------------------------------------------------------------------------
-- 1. 分仓规则配置（租户口径落库存档，不是代码常量）
-- ---------------------------------------------------------------------------
-- 蓝图职责句：「配置并执行仓库分配……支持优先级与兜底策略」。
-- priority 决定多条规则命中同一条订单时谁生效（数字小 = 优先级高），
-- UNIQUE(tenant_id, rule_code) 与 UNIQUE(tenant_id, priority) 一起保证
-- 「哪条规则生效」永远唯一 —— 两条并存时「取哪条」取决于查询顺序，
-- 同样输入在不同时候算出不同结果，这是配置表最典型的坑。
--
-- scope_platform_code / scope_destination_country_code 是规则的适用范围，
-- '*' 表示不限（与 V92 的 destination_country_code 同一套分档约定）。
CREATE TABLE fulfillment_warehouse_allocation_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  rule_code varchar(64) NOT NULL,
  rule_name varchar(150) NOT NULL,
  -- 规则优先级：数字小 = 优先级高。同租户内唯一。
  priority int NOT NULL CHECK(priority >= 0),
  scope_platform_code varchar(32) NOT NULL DEFAULT '*',
  scope_destination_country_code varchar(2) NOT NULL DEFAULT '*',
  -- 主排序口径：先按它定第一关键字，分数作为次关键字。
  selection_policy varchar(24) NOT NULL CHECK(selection_policy IN (
    'HIGHEST_SCORE','LOWEST_COST','CLOSEST_LEAD_TIME')),
  -- 兜底策略（蓝图「支持优先级与兜底策略」的兜底那一半）：
  --   SUSPEND           首选找不到可行仓就挂起（**缺省值**：不静默兜底）
  --   ALLOW_PARTIAL     允许部分分配：首选仓能供多少供多少，差额挂起
  --   CROSS_LEGAL_ENTITY 允许跨法人发货（放宽货权硬闸；会在结果里显式标记）
  fallback_policy varchar(24) NOT NULL DEFAULT 'SUSPEND' CHECK(fallback_policy IN (
    'SUSPEND','ALLOW_PARTIAL','CROSS_LEGAL_ENTITY')),
  -- 五个维度的权重，合计必须为 1.0000：权重不是「随便给的大数」，
  -- 合计不为 1 时同样的输入在不同租户口径下不可比，也说不清谁更重要。
  weight_stock numeric(9,4) NOT NULL CHECK(weight_stock >= 0 AND weight_stock <= 1),
  weight_cost numeric(9,4) NOT NULL CHECK(weight_cost >= 0 AND weight_cost <= 1),
  weight_lead_time numeric(9,4) NOT NULL CHECK(weight_lead_time >= 0 AND weight_lead_time <= 1),
  weight_legal_entity numeric(9,4) NOT NULL DEFAULT 0 CHECK(weight_legal_entity >= 0 AND weight_legal_entity <= 1),
  weight_region numeric(9,4) NOT NULL CHECK(weight_region >= 0 AND weight_region <= 1),
  -- 货权硬闸：开启后，法人不符的仓直接不可行（见文件头第二节）。
  require_legal_entity_match boolean NOT NULL DEFAULT true,
  -- 货权维度的打分口径：
  --   NEUTRAL            不偏好（所有候选该维度同分，法人只当闸门用）
  --   ORDER_OWNER_FIRST  订单货权主体优先
  --   PREFERRED_FIRST    指定法人优先
  legal_entity_policy varchar(24) NOT NULL DEFAULT 'NEUTRAL' CHECK(legal_entity_policy IN (
    'NEUTRAL','ORDER_OWNER_FIRST','PREFERRED_FIRST')),
  preferred_legal_entity_code varchar(64) NOT NULL DEFAULT '',
  -- 时效承诺：期望时效（天）。留空 = 不承诺，**不当 0** ——
  -- 0 天意味着「秒发」，参与比较时会把这个仓永远排到第一。
  max_lead_time_days numeric(9,2),
  -- 币种固定 USD：本模块只在**同币种**下比较各仓，不做跨币种换算
  -- （跨币种属 10 运费试算职责，那条线未开发，见文件头第一节）。
  currency_code varchar(3) NOT NULL DEFAULT 'USD' CHECK(currency_code=upper(currency_code)),
  -- 规则级排除清单：命中的仓一律不可行（运营临时封锁某仓时的正规入口，
  -- 不用去改权重 —— 改权重会同时影响其它维度，那是另一个问题）。
  excluded_warehouse_refs text NOT NULL DEFAULT '',
  effective_from timestamptz NOT NULL DEFAULT now(),
  effective_to timestamptz,
  notes varchar(400) NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_by_membership_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,rule_code),
  -- 优先级唯一：两条规则同优先级时「谁生效」取决于查询顺序。
  UNIQUE(tenant_id,priority),
  CHECK(btrim(rule_code)<>''),
  CHECK(btrim(rule_name)<>''),
  -- 权重合计 = 1（numeric 是精确十进制，等号判断可靠）。
  CHECK(weight_stock+weight_cost+weight_lead_time+weight_legal_entity+weight_region = 1.0000),
  -- 指定法人优先策略必须给出目标法人，否则该维度无从打分。
  CHECK(legal_entity_policy<>'PREFERRED_FIRST' OR btrim(preferred_legal_entity_code)<>''),
  CHECK(max_lead_time_days IS NULL OR max_lead_time_days>=0),
  CHECK(effective_to IS NULL OR effective_to>effective_from),
  CHECK(scope_destination_country_code='*' OR length(scope_destination_country_code)=2),
  CHECK(updated_at>=created_at)
);
CREATE INDEX fulfillment_warehouse_allocation_rule_resolve
  ON fulfillment_warehouse_allocation_rule(tenant_id,scope_platform_code,scope_destination_country_code,priority);

-- ---------------------------------------------------------------------------
-- 2. 仓库档案（**货权归属在这里**：它是仓库的属性，不是分配结果的备注）
-- ---------------------------------------------------------------------------
-- 仓库主数据归 warehouse 服务，**跨库不可读**，所以这里存逻辑 ID + 本模块需要的
-- 两个属性（法人、区域）的本地登记快照，不建外键不联查。
--
-- legal_entity_code NOT NULL CHECK(btrim<>'')：**一个仓的货权不允许留空**。
-- 留空意味着「不知道货记在谁名下」，而这正是加粗句要防的那件事 ——
-- 不知道归属时按「随便选一个便宜的」执行，等于替财务做了一个错误决定。
CREATE TABLE fulfillment_warehouse_allocation_warehouse (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  warehouse_ref varchar(64) NOT NULL,
  warehouse_name varchar(150) NOT NULL DEFAULT '',
  -- 货权归属：该仓的货记在哪个法人名下。收入按此法人记账。
  legal_entity_code varchar(64) NOT NULL CHECK(btrim(legal_entity_code)<>''),
  -- 区域码：用于「买家地区匹配度」维度。与 shipping_estimate 的目的国分档协同。
  region_code varchar(32) NOT NULL DEFAULT '*',
  -- 仓优先级：数字小 = 优先级高。分数相同时的确定性 tiebreak 之一。
  default_priority int NOT NULL DEFAULT 100 CHECK(default_priority >= 0),
  active boolean NOT NULL DEFAULT true,
  notes varchar(300) NOT NULL DEFAULT '',
  created_by_membership_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,warehouse_ref),
  CHECK(btrim(warehouse_ref)<>''),
  CHECK(updated_at>=created_at)
);
CREATE INDEX fulfillment_warehouse_allocation_warehouse_lookup
  ON fulfillment_warehouse_allocation_warehouse(tenant_id,legal_entity_code,active);

-- ---------------------------------------------------------------------------
-- 3. 各仓库存估算（租户可维护的真实表，7.1.1.3 的跨服务快照）
-- ---------------------------------------------------------------------------
-- 蓝图输入第一项「各仓库存…估算」。每行强制带来源与采集时间：
-- 没有来源的库存等于凭空捏一个数去决定从哪个仓发货，比没有数据更糟。
CREATE TABLE fulfillment_warehouse_allocation_stock_estimate (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  warehouse_ref varchar(64) NOT NULL,
  -- SKU 是 catalog 服务的逻辑 ID，跨服务不建外键（与 V92 sku_ref 同一纪律）。
  sku_ref varchar(64) NOT NULL,
  available_qty numeric(19,6) NOT NULL CHECK(available_qty>=0),
  -- 观测值的发生时点，由采集方给出，不由写入时刻冒充：
  -- 两者混同会让「数据太旧了」这种判断永远做不出来。
  collected_at timestamptz NOT NULL,
  stock_source varchar(24) NOT NULL DEFAULT 'TENANT_MAINTAINED'
    CHECK(stock_source IN ('MODULE_7_1_1_3','TENANT_MAINTAINED')),
  source_reference varchar(128) NOT NULL DEFAULT '',
  notes varchar(300) NOT NULL DEFAULT '',
  created_by_membership_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一仓同一 SKU 只能有一条**当前**估算（UPSERT 语义）：
  -- 两条并存时「取哪条」取决于查询顺序。
  UNIQUE(tenant_id,warehouse_ref,sku_ref),
  FOREIGN KEY(tenant_id,warehouse_ref)
    REFERENCES fulfillment_warehouse_allocation_warehouse(tenant_id,warehouse_ref),
  CHECK(btrim(sku_ref)<>''),
  CHECK(btrim(warehouse_ref)<>''),
  CHECK(stock_source<>'MODULE_7_1_1_3' OR btrim(source_reference)<>'')
);
CREATE INDEX fulfillment_warehouse_allocation_stock_estimate_lookup
  ON fulfillment_warehouse_allocation_stock_estimate(tenant_id,sku_ref,warehouse_ref);

-- ---------------------------------------------------------------------------
-- 4. 各仓运费与时效估算（10 运费试算未开发 → 租户维护）
-- ---------------------------------------------------------------------------
-- 蓝图输入第二项「…与运费估算」，也是加粗句里「不能只看运费便宜」的那个运费。
-- 维度是 (仓, SKU, 目的国)：与 V92 的 (平台, 路径, 目的国) **不同构**，故不复用其表。
-- currency_code 固定 USD：本模块只在同币种下比较各仓。
CREATE TABLE fulfillment_warehouse_allocation_shipping_estimate (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  warehouse_ref varchar(64) NOT NULL,
  sku_ref varchar(64) NOT NULL,
  destination_country_code varchar(2) NOT NULL,
  -- 单票运费（不是单件费率）：分仓决策比的是「这一单发这个仓多少钱」。
  shipping_cost numeric(19,4) NOT NULL CHECK(shipping_cost>=0),
  -- 预计时效（天）。留空 = 未知，不当 0。
  lead_time_days numeric(9,2),
  currency_code varchar(3) NOT NULL DEFAULT 'USD' CHECK(currency_code=upper(currency_code)),
  cost_source varchar(24) NOT NULL DEFAULT 'TENANT_MAINTAINED'
    CHECK(cost_source IN ('MODULE_10_1_2_1','TENANT_MAINTAINED')),
  source_reference varchar(128) NOT NULL DEFAULT '',
  effective_from timestamptz NOT NULL DEFAULT now(),
  effective_to timestamptz,
  notes varchar(300) NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_by_membership_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,warehouse_ref,sku_ref,destination_country_code),
  FOREIGN KEY(tenant_id,warehouse_ref)
    REFERENCES fulfillment_warehouse_allocation_warehouse(tenant_id,warehouse_ref),
  CHECK(btrim(warehouse_ref)<>''),
  CHECK(btrim(sku_ref)<>''),
  CHECK(length(destination_country_code)=2),
  CHECK(lead_time_days IS NULL OR lead_time_days>=0),
  CHECK(effective_to IS NULL OR effective_to>effective_from),
  CHECK(updated_at>=created_at)
);
CREATE INDEX fulfillment_warehouse_allocation_shipping_estimate_resolve
  ON fulfillment_warehouse_allocation_shipping_estimate(tenant_id,sku_ref,destination_country_code,warehouse_ref);

-- ---------------------------------------------------------------------------
-- 5. 分仓分配决策（append-only 的一次分配）
-- ---------------------------------------------------------------------------
-- **一条分配一行，不做「分配实体 + 反复改」**：本表回答的是
-- 「这一次为什么从 A 仓发、发了多少」，是举证凭据；改主意要落新的一条，
-- 否则「上次是怎么分的」会被覆盖掉，而复盘缺的正是这一问。
--
-- 收货与货权快照刻意抄进决策行（order_legal_entity_code /
-- destination_country_code / destination_region_code）：
-- 订单与仓库档案事后都会变，只存 order_ref 就再也答不出
-- 「当时按哪个收货地、按哪个法人判的」。
CREATE TABLE fulfillment_warehouse_allocation_decision (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  order_ref varchar(64) NOT NULL,
  order_line_ref varchar(64) NOT NULL DEFAULT '',
  sku_ref varchar(64) NOT NULL,
  requested_qty numeric(19,6) NOT NULL CHECK(requested_qty>0),
  -- 命中的规则。规则被改权重后，决策仍能说清「当时用的是哪一版口径」。
  rule_id uuid,
  -- 决策时规则的关键口径快照（规则事后可改，这几项决定复现当时结论所必需）。
  selection_policy varchar(24) NOT NULL CHECK(selection_policy IN (
    'HIGHEST_SCORE','LOWEST_COST','CLOSEST_LEAD_TIME')),
  fallback_policy varchar(24) NOT NULL CHECK(fallback_policy IN (
    'SUSPEND','ALLOW_PARTIAL','CROSS_LEGAL_ENTITY')),
  legal_entity_policy varchar(24) NOT NULL CHECK(legal_entity_policy IN (
    'NEUTRAL','ORDER_OWNER_FIRST','PREFERRED_FIRST')),
  require_legal_entity_match boolean NOT NULL,
  -- 收货快照
  destination_country_code varchar(2) NOT NULL,
  destination_region_code varchar(32) NOT NULL DEFAULT '*',
  -- **订单货权主体**：加粗句里「收入记在哪个法人」的那个「应该」。
  order_legal_entity_code varchar(64) NOT NULL CHECK(btrim(order_legal_entity_code)<>''),
  -- 结果：ALLOCATED 必须有发货仓与数量；SUSPENDED 两者必须为空。
  status varchar(16) NOT NULL CHECK(status IN (
    'ALLOCATED','PARTIAL','SUSPENDED','CONFIRMED','RELEASED')),
  chosen_warehouse_ref varchar(64),
  chosen_legal_entity_code varchar(64),
  -- 是否跨法人发货。只有兜底策略显式放开货权硬闸时才可能为 true，
  -- 调用方据此知道「这单收入记在了别的法人名下」。
  cross_legal_entity boolean NOT NULL DEFAULT false,
  allocated_qty numeric(19,6),
  unit_shipping_cost numeric(19,4),
  lead_time_days numeric(9,2),
  currency_code varchar(3) NOT NULL DEFAULT 'USD' CHECK(currency_code=upper(currency_code)),
  chosen_total_score numeric(12,4),
  reason_code varchar(32) NOT NULL CHECK(reason_code IN (
    'SCORE_BASED','LOWEST_COST','CLOSEST_LEAD_TIME','PARTIAL_ALLOCATION','NO_ELIGIBLE_WAREHOUSE')),
  -- 「为什么选了 A 仓」：含货权维度的贡献，界面上直接展示这句话。
  reason varchar(500) NOT NULL DEFAULT '',
  decided_at timestamptz NOT NULL,
  confirmed_at timestamptz,
  confirmed_by_membership_id uuid,
  released_at timestamptz,
  released_to_module varchar(32) NOT NULL DEFAULT ''
    CHECK(released_to_module IN ('','MODULE_9_3_3_1','MODULE_8_OUTBOUND')),
  created_by_membership_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,rule_id) REFERENCES fulfillment_warehouse_allocation_rule(tenant_id,id),
  CHECK(btrim(order_ref)<>''),
  CHECK(btrim(sku_ref)<>''),
  CHECK(length(destination_country_code)=2),
  CHECK(btrim(order_legal_entity_code)<>''),
  -- 已分配（含部分分配）：必须有发货仓、法人与正数数量。
  CHECK(status NOT IN ('ALLOCATED','PARTIAL','CONFIRMED','RELEASED')
     OR (btrim(chosen_warehouse_ref)<>'' AND btrim(chosen_legal_entity_code)<>''
         AND allocated_qty IS NOT NULL AND allocated_qty>0)),
  -- 已挂起：不允许有发货仓与数量（挂起不是「还没定」，是「定了发不出去」）。
  CHECK(status<>'SUSPENDED'
     OR (chosen_warehouse_ref IS NULL AND chosen_legal_entity_code IS NULL AND allocated_qty IS NULL)),
  -- 分配数量不得超过需求量。
  CHECK(allocated_qty IS NULL OR allocated_qty<=requested_qty),
  -- 部分分配与 reason_code 必须自洽：**与 status 无关**地要求
  -- 「有差额」⟺「reason_code=PARTIAL_ALLOCATION」。
  -- 刻意不写成 (status='PARTIAL') = (...)：状态推进到 CONFIRMED / RELEASED 之后
  -- status 不再是 PARTIAL，而它**仍然**是一笔部分分配 —— 按 status 写会让
  -- 「确认一笔部分分配」直接被 CHECK 拒掉。
  CHECK((reason_code='PARTIAL_ALLOCATION')
     = (allocated_qty IS NOT NULL AND allocated_qty < requested_qty)),
  -- 跨法人标记必须与规则口径自洽：只有**明确**允许跨法人时才可能为 true。
-- 「允许」有两种写法 —— 要么把 require_legal_entity_match 关掉（这本身就是一种显式选择），
-- 要么把兜底策略设为 CROSS_LEGAL_ENTITY。两者都没有时标 cross_legal_entity=true
-- 是凭空来的。
  CHECK(NOT cross_legal_entity
     OR (NOT require_legal_entity_match OR fallback_policy='CROSS_LEGAL_ENTITY')),
  -- 跨法人标记必须与两个法人真的不同一致（防「标记了但其实同法人」的自相矛盾）。
  CHECK(NOT cross_legal_entity OR chosen_legal_entity_code IS DISTINCT FROM order_legal_entity_code),
  CHECK(status<>'CONFIRMED' OR (confirmed_at IS NOT NULL AND confirmed_by_membership_id IS NOT NULL)),
  CHECK(status<>'RELEASED' OR (released_at IS NOT NULL AND btrim(released_to_module)<>'')),
  CHECK(status<>'CONFIRMED' OR confirmed_at IS NULL OR confirmed_at>=decided_at)
);
CREATE INDEX fulfillment_warehouse_allocation_decision_order
  ON fulfillment_warehouse_allocation_decision(tenant_id,order_ref,order_line_ref,decided_at DESC);
CREATE INDEX fulfillment_warehouse_allocation_decision_warehouse
  ON fulfillment_warehouse_allocation_decision(tenant_id,chosen_warehouse_ref,decided_at DESC)
  WHERE chosen_warehouse_ref IS NOT NULL;
CREATE INDEX fulfillment_warehouse_allocation_decision_status
  ON fulfillment_warehouse_allocation_decision(tenant_id,status,decided_at DESC);
CREATE INDEX fulfillment_warehouse_allocation_decision_suspended
  ON fulfillment_warehouse_allocation_decision(tenant_id,decided_at DESC) WHERE status='SUSPENDED';

-- ---------------------------------------------------------------------------
-- 6. 候选仓对比行（一次分配下每仓一行；**append-only**）
-- ---------------------------------------------------------------------------
-- 为什么必须落库而不只在响应里算：候选对比是**决策的输入**。
-- 库存会被补上、运费估算会被改、规则权重会被调，历史候选就再也算不出来了，
-- 而「当时每个仓分别是多少、为什么被排除」正是「为什么这单没走 A 仓」的完整答案。
--
-- 五个维度得分各占一列 + 一个 total_score：只存一个总分的话，
-- 「总分 0.82 是怎么来的」永远答不出来。
CREATE TABLE fulfillment_warehouse_allocation_candidate (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  decision_id uuid NOT NULL,
  warehouse_ref varchar(64) NOT NULL,
  -- 候选仓的法人与区域（决策时刻的档案快照）。
  legal_entity_code varchar(64) NOT NULL CHECK(btrim(legal_entity_code)<>''),
  region_code varchar(32) NOT NULL DEFAULT '*',
  warehouse_priority int NOT NULL DEFAULT 100,
  available_qty numeric(19,6),
  unit_shipping_cost numeric(19,4),
  lead_time_days numeric(9,2),
  stock_estimate_id uuid,
  shipping_estimate_id uuid,
  -- 五维得分，各自 0~1。缺的那一维给 0（而不是 null）：
  -- 「没测过」在打分里就是拿不到分，不能让它在求和里消失。
  score_stock numeric(9,4) NOT NULL DEFAULT 0 CHECK(score_stock >= 0 AND score_stock <= 1),
  score_cost numeric(9,4) NOT NULL DEFAULT 0 CHECK(score_cost >= 0 AND score_cost <= 1),
  score_lead_time numeric(9,4) NOT NULL DEFAULT 0 CHECK(score_lead_time >= 0 AND score_lead_time <= 1),
  score_legal_entity numeric(9,4) NOT NULL DEFAULT 0 CHECK(score_legal_entity >= 0 AND score_legal_entity <= 1),
  score_region numeric(9,4) NOT NULL DEFAULT 0 CHECK(score_region >= 0 AND score_region <= 1),
  total_score numeric(12,4) NOT NULL CHECK(total_score >= 0),
  -- 货权是否与订单主体一致（加粗句的判定维度，单独一列可直接筛）。
  legal_entity_match boolean NOT NULL DEFAULT false,
  feasible boolean NOT NULL,
  infeasible_reason_code varchar(32) NOT NULL DEFAULT '' CHECK(infeasible_reason_code IN (
    '','WAREHOUSE_INACTIVE','WAREHOUSE_EXCLUDED','LEGAL_ENTITY_MISMATCH',
    'NO_STOCK_RECORD','STOCK_ZERO','INSUFFICIENT_STOCK',
    'NO_SHIPPING_ESTIMATE','LEAD_TIME_EXCEEDED')),
  infeasible_reason varchar(400) NOT NULL DEFAULT '',
  -- 选中与否：至多一条 selected（由 guard_allocation_candidate_unique_selected 钉死）。
  selected boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 一次分配下每个仓只有一行候选：同一仓出现两个候选时「取哪个」无解。
  UNIQUE(tenant_id,decision_id,warehouse_ref),
  FOREIGN KEY(tenant_id,decision_id)
    REFERENCES fulfillment_warehouse_allocation_decision(tenant_id,id),
  FOREIGN KEY(tenant_id,stock_estimate_id)
    REFERENCES fulfillment_warehouse_allocation_stock_estimate(tenant_id,id),
  FOREIGN KEY(tenant_id,shipping_estimate_id)
    REFERENCES fulfillment_warehouse_allocation_shipping_estimate(tenant_id,id),
  CHECK(btrim(warehouse_ref)<>''),
  CHECK(available_qty IS NULL OR available_qty>=0),
  CHECK(unit_shipping_cost IS NULL OR unit_shipping_cost>=0),
  CHECK(lead_time_days IS NULL OR lead_time_days>=0),
  -- 不可用的候选必须说明原因；可用候选不得带原因（否则「可用」与「有例外」混在一起）。
  CHECK(feasible OR btrim(infeasible_reason_code)<>''),
  CHECK(feasible AND btrim(infeasible_reason_code)='' OR NOT feasible),
  -- 被选中的候选必然可行：选一个被排除的仓是自相矛盾。
  -- 刻意**不**在这里要求 legal_entity_match：兜底策略 CROSS_LEGAL_ENTITY 显式放开
  -- 货权硬闸时，选中的仓法人可以与订单主体不同 —— 那条路径由
  -- guard_warehouse_allocation_candidate_legal_entity 单独把守（它认这个例外），
  -- 在这里硬要求会把「允许跨法人」这个显式配置变成配了也用不了的死路。
  CHECK(NOT selected OR feasible)
);
CREATE INDEX fulfillment_warehouse_allocation_candidate_decision
  ON fulfillment_warehouse_allocation_candidate(tenant_id,decision_id);
CREATE INDEX fulfillment_warehouse_allocation_candidate_blocked
  ON fulfillment_warehouse_allocation_candidate(tenant_id,infeasible_reason_code)
  WHERE feasible=false;

-- ---------------------------------------------------------------------------
-- 7. 无法分配的挂起记录（append-only；蓝图输出的第二项）
-- ---------------------------------------------------------------------------
-- 「异常挂起」必须有载体，否则挂起之后就没人知道为什么挂起、该去补什么。
-- exclusion_detail 存**全量枚举**的排除清单（每个仓一句），
-- 与 candidate 行的逐条原因互为印证：候选行回答「每个仓为什么」，
-- 本行回答「这次分配整体因为什么挂起」。
CREATE TABLE fulfillment_warehouse_allocation_hold (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  decision_id uuid NOT NULL,
  hold_reason varchar(32) NOT NULL CHECK(hold_reason IN (
    'NO_ELIGIBLE_WAREHOUSE','NO_STOCK_RECORD','INSUFFICIENT_STOCK',
    'NO_SHIPPING_ESTIMATE','BLOCKED_BY_LEGAL_ENTITY','BLOCKED_BY_RULE',
    'PARTIAL_SHORTFALL')),
  -- 整体原因的一句话说明。
  reason varchar(500) NOT NULL,
  -- 逐仓排除清单：「仓A=无库存记录；仓B=法人不符；仓C=运费未配置」。
  exclusion_detail varchar(1000) NOT NULL DEFAULT '',
  excluded_warehouse_count int NOT NULL DEFAULT 0 CHECK(excluded_warehouse_count >= 0),
  -- 被排除的候选行（可点进去看每一维的数）。
  hold_candidate_id uuid,
  -- 部分分配时的未分配数量：requested - allocated。
  shortfall_qty numeric(19,6),
  held_at timestamptz NOT NULL,
  hold_source varchar(24) NOT NULL DEFAULT 'SYSTEM'
    CHECK(hold_source IN ('SYSTEM','MANUAL','FULFILLMENT_REQUEST')),
  created_by_membership_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,decision_id)
    REFERENCES fulfillment_warehouse_allocation_decision(tenant_id,id),
  FOREIGN KEY(tenant_id,hold_candidate_id)
    REFERENCES fulfillment_warehouse_allocation_candidate(tenant_id,id),
  CHECK(btrim(reason)<>''),
  -- 至少要排除过一个仓：没有任何排除项的挂起说明判定本身有问题。
  CHECK(excluded_warehouse_count>0),
  -- shortfall_qty 只在部分分配挂起时有意义。
  CHECK((hold_reason='PARTIAL_SHORTFALL') = (shortfall_qty IS NOT NULL))
);
CREATE INDEX fulfillment_warehouse_allocation_hold_decision
  ON fulfillment_warehouse_allocation_hold(tenant_id,decision_id);
CREATE INDEX fulfillment_warehouse_allocation_hold_feed
  ON fulfillment_warehouse_allocation_hold(tenant_id,held_at DESC);
CREATE INDEX fulfillment_warehouse_allocation_hold_reason
  ON fulfillment_warehouse_allocation_hold(tenant_id,hold_reason,held_at DESC);

-- ---------------------------------------------------------------------------
-- 8. 事件时间线（append-only）
-- ---------------------------------------------------------------------------
-- 分配 / 挂起 / 确认 / 下发四类事实的共同承载，用 event_kind 区分来源，
-- 而不是造四张形状雷同的表。事后复盘要回答的是
-- 「谁在什么时候把这次分配确认下去的」，只留当前状态答不出来。
CREATE TABLE fulfillment_warehouse_allocation_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  decision_id uuid NOT NULL,
  event_kind varchar(24) NOT NULL CHECK(event_kind IN (
    'ALLOCATED','PARTIAL','SUSPENDED','CONFIRMED','RELEASED','NOTE')),
  from_status varchar(16) NOT NULL DEFAULT '',
  to_status varchar(16) NOT NULL DEFAULT '',
  detail text NOT NULL DEFAULT '',
  actor_id uuid,
  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,decision_id)
    REFERENCES fulfillment_warehouse_allocation_decision(tenant_id,id),
  CHECK(btrim(detail)<>'' OR event_kind<>'NOTE')
);
CREATE INDEX fulfillment_warehouse_allocation_event_feed
  ON fulfillment_warehouse_allocation_event(tenant_id,decision_id,occurred_at DESC);

-- ---------------------------------------------------------------------------
-- 9. DB 级不变量
-- ---------------------------------------------------------------------------
-- 9.1 分仓决策状态机：确认 / 下发必须自洽，终态不许离开。
--     与 V92 guard_routing_decision_state、warehouse V84 同一纪律。
--     状态机：ALLOCATED|PARTIAL → CONFIRMED → RELEASED；SUSPENDED 是终态。
CREATE FUNCTION guard_warehouse_allocation_state() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.status = 'CONFIRMED') <> (NEW.confirmed_at IS NOT NULL) THEN
  RAISE EXCEPTION '分配状态「%」与确认时间不自洽：只有已确认才可能带确认时间', NEW.status
   USING ERRCODE='23514';
 END IF;
 IF NEW.status <> 'CONFIRMED'
    AND (NEW.confirmed_at IS NOT NULL OR NEW.confirmed_by_membership_id IS NOT NULL) THEN
  RAISE EXCEPTION '分配状态「%」不是已确认，不允许带确认痕迹', NEW.status USING ERRCODE='23514';
 END IF;
 IF NEW.status <> 'RELEASED' AND NEW.released_at IS NOT NULL THEN
  RAISE EXCEPTION '分配状态「%」不是已下发，不允许带下发时间', NEW.status USING ERRCODE='23514';
 END IF;
 -- 终态不许离开（只拦离开，不拦进入）。
 IF TG_OP = 'UPDATE' AND OLD.status IN ('SUSPENDED','RELEASED') AND NEW.status <> OLD.status THEN
  RAISE EXCEPTION '分配当前状态「%」是终态，不允许改到「%」', OLD.status, NEW.status
   USING ERRCODE='23514';
 END IF;
 -- 挂起是终态：不能把一个 SUSPENDED 直接改成分配 —— 那等于把
 -- 「发不出去」改写成「已决策发 A 仓」，正是本模块要防的那件事。
 IF TG_OP = 'UPDATE' AND OLD.status = 'SUSPENDED' THEN
  RAISE EXCEPTION '挂起的分配（%）是终态，不允许再改状态', OLD.id USING ERRCODE='23514';
 END IF;
 -- 已下发是终态：8 出库单已经拿走了这批货，再改状态会让两边不一致。
 IF TG_OP = 'UPDATE' AND OLD.status = 'RELEASED' THEN
  RAISE EXCEPTION '已下发的分配（%）是终态，不允许再改状态', OLD.id USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER warehouse_allocation_state BEFORE INSERT OR UPDATE
 ON fulfillment_warehouse_allocation_decision
 FOR EACH ROW EXECUTE FUNCTION guard_warehouse_allocation_state();

-- 9.2 已分配 ⇒ 必须存在一条 selected 候选，且仓号与数量一致
--     （**延迟约束触发器，回表读当前行**）。
--     蓝图输出的第一项在这里被钉成 DB 不变量：没有候选依据的「已分配」提交不了。
--     为什么不能用 NEW：见文件头第五节，服务层写入顺序是
--     「插头（候选还没写）→ 写候选 → 写挂起 → 写事件」，延迟到提交时
--     INSERT 那一次拿着的仍是半成品 NEW。
CREATE FUNCTION guard_warehouse_allocation_candidate() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE head record; picked record;
BEGIN
 SELECT id, status, chosen_warehouse_ref, chosen_legal_entity_code, allocated_qty,
        requested_qty, cross_legal_entity, order_legal_entity_code
   INTO head
   FROM fulfillment_warehouse_allocation_decision WHERE id = NEW.id;
 IF head IS NULL THEN RETURN NULL; END IF;
 -- 只在「已分配」这一族状态上要求有候选依据。
 IF head.status NOT IN ('ALLOCATED','PARTIAL','CONFIRMED','RELEASED') THEN
  RETURN NULL;
 END IF;
 SELECT warehouse_ref, legal_entity_code, feasible, available_qty
   INTO picked
   FROM fulfillment_warehouse_allocation_candidate
  WHERE tenant_id = NEW.tenant_id AND decision_id = head.id AND selected;
 IF picked IS NULL THEN
  RAISE EXCEPTION
    '分配 % 状态是「%」但没有任何选中的候选仓：分配结果必须有候选依据，否则答不出「为什么从这个仓发」',
    head.id, head.status USING ERRCODE='23514',
    HINT='先写 fulfillment_warehouse_allocation_candidate（selected=true）再提交';
 END IF;
 IF picked.warehouse_ref <> head.chosen_warehouse_ref THEN
  RAISE EXCEPTION
    '分配 % 记录的发货仓是「%」，选中的候选仓却是「%」：决策行与候选行互相矛盾',
    head.id, head.chosen_warehouse_ref, picked.warehouse_ref USING ERRCODE='23514';
 END IF;
 IF picked.legal_entity_code <> head.chosen_legal_entity_code THEN
  RAISE EXCEPTION
    '分配 % 记录的货权法人是「%」，选中候选仓的法人却是「%」：收入归属与候选依据矛盾',
    head.id, head.chosen_legal_entity_code, picked.legal_entity_code USING ERRCODE='23514';
 END IF;
 IF NOT picked.feasible THEN
  RAISE EXCEPTION '分配 % 选中的候选仓「%」被标记为不可行：选了不可行的仓',
    head.id, picked.warehouse_ref USING ERRCODE='23514';
 END IF;
 IF head.allocated_qty IS NULL OR head.allocated_qty > picked.available_qty THEN
  RAISE EXCEPTION
    '分配 % 的分配数量 % 超出选中仓的可用量 %：分出去的比能给的还多',
    head.id, head.allocated_qty, picked.available_qty USING ERRCODE='23514';
 END IF;
 -- 全部分配必须发满；部分分配的差额由一条 PARTIAL_SHORTFALL 挂起承载。
 IF head.status = 'ALLOCATED' AND head.allocated_qty < head.requested_qty THEN
  RAISE EXCEPTION
    '分配 % 状态是「已全部分配」但分配数量 % 小于需求量 %：差额要么改成 PARTIAL 并挂起，要么改数量',
    head.id, head.allocated_qty, head.requested_qty USING ERRCODE='23514';
 END IF;
 -- 加粗句的落点：选了货权不符的仓又被标成「不跨法人」，是记账口径上的硬矛盾。
 IF head.cross_legal_entity = (picked.legal_entity_code = head.order_legal_entity_code) THEN
  RAISE EXCEPTION
    '分配 % 的 cross_legal_entity=% 与选中仓法人「%」/订单法人「%」不自洽',
    head.id, head.cross_legal_entity, picked.legal_entity_code, head.order_legal_entity_code
   USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER warehouse_allocation_candidate
 AFTER INSERT OR UPDATE ON fulfillment_warehouse_allocation_decision
 DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION guard_warehouse_allocation_candidate();

-- 9.3 挂起 ⇒ 必须存在一条 hold 记录，且**一条选中候选都没有**
--     （延迟约束触发器，回表读当前行）。
--     这是「绝不悄悄回落」的 DB 钉子：挂起之后如果有人偷偷塞一条 selected 候选，
--     提交时报 23514 —— 「这单没货」不可能显示成「已决策走 A 仓」。
CREATE FUNCTION guard_warehouse_allocation_hold() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE head record; hold_count bigint; picked_count bigint;
BEGIN
 SELECT id, status, allocated_qty, requested_qty INTO head
   FROM fulfillment_warehouse_allocation_decision WHERE id = NEW.id;
 IF head IS NULL THEN RETURN NULL; END IF;
 IF head.status <> 'SUSPENDED' THEN
  -- 非挂起状态不得有 hold 记录（由 9.4 反向钉）。
  RETURN NULL;
 END IF;
 SELECT count(*) INTO hold_count FROM fulfillment_warehouse_allocation_hold
  WHERE tenant_id = NEW.tenant_id AND decision_id = head.id;
 IF hold_count = 0 THEN
  RAISE EXCEPTION
    '分配 % 处于挂起状态但没有任何挂起记录：无法分配必须挂起并列出每个仓被排除的原因，否则等于静默丢弃这单',
    head.id USING ERRCODE='23514',
    HINT='先写 fulfillment_warehouse_allocation_hold（带全量排除清单）再提交';
 END IF;
 SELECT count(*) INTO picked_count FROM fulfillment_warehouse_allocation_candidate
  WHERE tenant_id = NEW.tenant_id AND decision_id = head.id AND selected;
 IF picked_count > 0 THEN
  RAISE EXCEPTION
    '分配 % 已挂起，却有 % 条被选中的候选仓：挂起与已分配互相矛盾',
    head.id, picked_count USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER warehouse_allocation_hold
 AFTER INSERT OR UPDATE ON fulfillment_warehouse_allocation_decision
 DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION guard_warehouse_allocation_hold();

-- 9.4 部分分配 ⇒ 必须有一条 PARTIAL_SHORTFALL 挂起承载差额（回表读当前行）。
CREATE FUNCTION guard_warehouse_allocation_partial() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE head record; shortfall bigint;
BEGIN
 SELECT id, status, allocated_qty, requested_qty INTO head
   FROM fulfillment_warehouse_allocation_decision WHERE id = NEW.id;
 IF head IS NULL OR head.status <> 'PARTIAL' THEN RETURN NULL; END IF;
 IF head.allocated_qty IS NULL OR head.allocated_qty >= head.requested_qty THEN
  RAISE EXCEPTION
    '分配 % 状态是「部分分配」但分配数量 % 不小于需求量 %：没有差额就不叫部分分配',
    head.id, head.allocated_qty, head.requested_qty USING ERRCODE='23514';
 END IF;
 SELECT count(*) INTO shortfall FROM fulfillment_warehouse_allocation_hold
  WHERE tenant_id = NEW.tenant_id AND decision_id = head.id AND hold_reason = 'PARTIAL_SHORTFALL';
 IF shortfall = 0 THEN
  RAISE EXCEPTION
    '分配 % 是部分分配（已分 % / 需求 %）但没有差额挂起记录：差额会显示成「已全部分配」',
    head.id, head.allocated_qty, head.requested_qty USING ERRCODE='23514',
    HINT='先写一条 hold_reason=PARTIAL_SHORTFALL 的 fulfillment_warehouse_allocation_hold 再提交';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER warehouse_allocation_partial
 AFTER INSERT OR UPDATE ON fulfillment_warehouse_allocation_decision
 DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION guard_warehouse_allocation_partial();

-- 9.5 挂起记录必须与决策状态一致（回表读当前行，避开 NEW）。
--     「决策已挂起却留下 PARTIAL_SHORTFALL 之外的矛盾留痕」会让复盘两说并存。
CREATE FUNCTION guard_warehouse_allocation_hold_consistency() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE head_status varchar(16);
BEGIN
 SELECT status INTO head_status FROM fulfillment_warehouse_allocation_decision
  WHERE id = NEW.decision_id AND tenant_id = NEW.tenant_id;
 IF head_status IS NULL THEN
  RAISE EXCEPTION '挂起记录指向的分配 % 不存在或不属于本租户', NEW.decision_id USING ERRCODE='23503';
 END IF;
 IF NEW.hold_reason = 'PARTIAL_SHORTFALL' THEN
  IF head_status <> 'PARTIAL' THEN
   RAISE EXCEPTION '差额挂起记录挂在状态为「%」的分配上：只有部分分配才可能有差额',
     head_status USING ERRCODE='23514';
  END IF;
 ELSE
  IF head_status <> 'SUSPENDED' THEN
   RAISE EXCEPTION
     '无法分配的挂起记录挂在状态为「%」的分配上：只有挂起的分配才允许整单挂起',
     head_status USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER warehouse_allocation_hold_consistency
 AFTER INSERT OR UPDATE ON fulfillment_warehouse_allocation_hold
 DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION guard_warehouse_allocation_hold_consistency();

-- 9.6 每次分配至多一条选中候选（**即时 BEFORE 触发器**，候选表 append-only，用 NEW 安全）。
--     「两个仓同时被选中」在业务上无解：取哪个取决于查询顺序。
CREATE FUNCTION guard_warehouse_allocation_unique_selected() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE dup bigint;
BEGIN
 IF NOT NEW.selected THEN RETURN NEW; END IF;
 SELECT count(*) INTO dup FROM fulfillment_warehouse_allocation_candidate
  WHERE tenant_id = NEW.tenant_id AND decision_id = NEW.decision_id AND selected;
 IF dup > 0 THEN
  RAISE EXCEPTION '分配 % 已经有选中的候选仓，不允许再选「%」：一次分配只能有一个发货仓',
    NEW.decision_id, NEW.warehouse_ref USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER warehouse_allocation_unique_selected BEFORE INSERT
 ON fulfillment_warehouse_allocation_candidate
 FOR EACH ROW EXECUTE FUNCTION guard_warehouse_allocation_unique_selected();

-- 9.7 **加粗句的写入路径闸门**：货权不符的仓不许成为选中仓
--     （即时 BEFORE 触发器，候选表 append-only，用 NEW 是安全的）。
--
--     蓝图：「从 A 法人的仓发货意味着收入记在 A 法人，不能只看运费便宜。」
--     把它钉在**候选写入**上而不是「服务层先查一次货权」：
--     后者会被并发下的规则修改绕过 —— 读规则时 require_legal_entity_match=true，
--     写候选时规则已经被改成 false，本模块就会记下一条货权不符的分配，
--     而那笔收入的归属已经错了。
--
--     唯一能放宽的是 fallback_policy='CROSS_LEGAL_ENTITY'（运营显式承担跨法人后果），
--     且必须在 decision.cross_legal_entity 里如实标记。
CREATE FUNCTION guard_warehouse_allocation_candidate_legal_entity() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE head record;
BEGIN
 IF NOT NEW.selected THEN RETURN NEW; END IF;
 SELECT id, require_legal_entity_match, fallback_policy, order_legal_entity_code INTO head
   FROM fulfillment_warehouse_allocation_decision
  WHERE id = NEW.decision_id AND tenant_id = NEW.tenant_id;
 IF head IS NULL THEN
  RAISE EXCEPTION '候选行指向的分配 % 不存在或不属于本租户', NEW.decision_id USING ERRCODE='23503';
 END IF;
 IF head.fallback_policy = 'CROSS_LEGAL_ENTITY' THEN RETURN NEW; END IF;
 IF head.require_legal_entity_match AND NEW.legal_entity_code <> head.order_legal_entity_code THEN
  RAISE EXCEPTION
    '候选仓「%」的货权法人是「%」，与订单货权主体「%」不符，而本规则要求货权一致：'
    '从别的法人的仓发货意味着收入记在那个法人名下，不能只看运费便宜',
    NEW.warehouse_ref, NEW.legal_entity_code, head.order_legal_entity_code
   USING ERRCODE='23514',
   HINT='确实需要跨法人发货时，请把规则的兜底策略显式设为 CROSS_LEGAL_ENTITY';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER warehouse_allocation_candidate_legal_entity BEFORE INSERT
 ON fulfillment_warehouse_allocation_candidate
 FOR EACH ROW EXECUTE FUNCTION guard_warehouse_allocation_candidate_legal_entity();

-- 9.8 分配与候选的法人口径必须自洽：候选记的法人要与仓库档案当前一致。
--     档案被改过之后，历史候选行的法人仍是**当时**的值（决策要复现当时那一版），
--     所以这条只在**写入路径**上校验（候选表 append-only，不会被改写）。
CREATE FUNCTION guard_warehouse_allocation_candidate_profile() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE profile_entity varchar(64); profile_region varchar(32);
BEGIN
 SELECT legal_entity_code, region_code INTO profile_entity, profile_region
   FROM fulfillment_warehouse_allocation_warehouse
  WHERE tenant_id = NEW.tenant_id AND warehouse_ref = NEW.warehouse_ref;
 IF profile_entity IS NULL THEN
  RAISE EXCEPTION '候选仓「%」不在本租户的仓库档案里：没有档案就没有货权归属可依据',
    NEW.warehouse_ref USING ERRCODE='23503';
 END IF;
 IF profile_entity <> NEW.legal_entity_code THEN
  RAISE EXCEPTION
    '候选仓「%」在档案里的货权法人是「%」，候选行却写「%」：货权口径必须来自仓库档案，不能在候选行里另填',
    NEW.warehouse_ref, profile_entity, NEW.legal_entity_code USING ERRCODE='23514';
 END IF;
 IF profile_region <> NEW.region_code THEN
  RAISE EXCEPTION
    '候选仓「%」在档案里的区域码是「%」，候选行却写「%」：区域必须来自仓库档案',
    NEW.warehouse_ref, profile_region, NEW.region_code USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER warehouse_allocation_candidate_profile BEFORE INSERT
 ON fulfillment_warehouse_allocation_candidate
 FOR EACH ROW EXECUTE FUNCTION guard_warehouse_allocation_candidate_profile();

-- 9.9 append-only：决策、候选、挂起与事件都是举证凭据
--     （照抄 V92 第 8.5 节的 REVOKE 纪律）。
--     状态推进（ALLOCATED|PARTIAL → CONFIRMED → RELEASED）是**唯一**被允许的 UPDATE，
--     且只经 advance_warehouse_allocation_status 这一个窄函数发起。
--     判据刻意用 transaction-local GUC 而不是「看 NEW.status 放行」：
--   * 若按 NEW.status 放行，那「把 reason 改得更好看」也会被一起放行 ——
--     append-only 就只剩下「状态字段可写」这一条，实现上等于没锁；
--   * GUC 由 SECURITY DEFINER 函数在事务内 set_config(..., true) 设下，
--     事务结束自动撤销，运行角色自己伪造不了。
CREATE FUNCTION guard_warehouse_allocation_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF current_setting('app.allocation_status_advance', true) = 'on' THEN
  RETURN NEW;
 END IF;
 RAISE EXCEPTION '分仓分配是举证凭据，不允许改写或删除（要改主意请落一条新分配）'
  USING ERRCODE='23514',
        HINT='分配行只允许追加；状态流转请用 advance_warehouse_allocation_status，且挂起与已下发是终态';
END $$;
CREATE TRIGGER warehouse_allocation_decision_append_only BEFORE UPDATE OR DELETE
 ON fulfillment_warehouse_allocation_decision
 FOR EACH ROW EXECUTE FUNCTION guard_warehouse_allocation_append_only();

-- 状态推进经 SECURITY DEFINER 窄函数：刻意不做「整表可改」，
-- 那样 append-only 就只剩注释了。
CREATE FUNCTION advance_warehouse_allocation_status(
  p_decision_id uuid, p_status varchar, p_actor uuid, p_module varchar)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE head record;
BEGIN
 SELECT id, status, decided_at INTO head FROM fulfillment_warehouse_allocation_decision
  WHERE id = p_decision_id;
 IF head IS NULL THEN
  RAISE EXCEPTION '分配 % 不存在或不属于本租户', p_decision_id USING ERRCODE='23503';
 END IF;
 IF head.status IN ('SUSPENDED','RELEASED') THEN
  RAISE EXCEPTION '分配当前状态「%」是终态，不允许改到「%」', head.status, p_status
   USING ERRCODE='23514';
 END IF;
 IF p_status NOT IN ('CONFIRMED','RELEASED') THEN
  RAISE EXCEPTION '状态只能推进到 CONFIRMED 或 RELEASED，收到「%」', p_status USING ERRCODE='23514';
 END IF;
 IF p_status = 'RELEASED' AND head.status <> 'CONFIRMED' THEN
  RAISE EXCEPTION '分配当前状态是「%」，必须先确认才能下发', head.status USING ERRCODE='23514';
 END IF;
 IF p_status = 'RELEASED'
    AND p_module NOT IN ('MODULE_9_3_3_1','MODULE_8_OUTBOUND') THEN
  RAISE EXCEPTION '下发给哪个模块必须显式说明（MODULE_9_3_3_1 / MODULE_8_OUTBOUND），收到「%」', p_module
   USING ERRCODE='23514';
 END IF;
 -- 置标记后 UPDATE：本函数是 SECURITY DEFINER，但触发器照样对所有 UPDATE 生效，
 -- 所以由这个函数自己开窗，且窗口只活在当前事务内。
 PERFORM set_config('app.allocation_status_advance', 'on', true);
 UPDATE fulfillment_warehouse_allocation_decision
    SET status = p_status,
        confirmed_at = CASE WHEN p_status = 'CONFIRMED' THEN now() ELSE NULL END,
        confirmed_by_membership_id = CASE WHEN p_status = 'CONFIRMED' THEN p_actor ELSE NULL END,
        released_at = CASE WHEN p_status = 'RELEASED' THEN now() ELSE NULL END,
        released_to_module = CASE WHEN p_status = 'RELEASED' THEN p_module ELSE '' END
  WHERE id = p_decision_id;
END $$;

-- 候选、挂起与事件是纯 append-only，没有任何状态可推进。
CREATE FUNCTION guard_warehouse_allocation_trail_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION '% 是不可改写的留痕表（%）：已落库的候选与挂起是复盘「为什么这样分」的证据',
  TG_TABLE_NAME, TG_OP USING ERRCODE='23514';
END $$;
CREATE TRIGGER warehouse_allocation_candidate_append_only BEFORE UPDATE OR DELETE
 ON fulfillment_warehouse_allocation_candidate
 FOR EACH ROW EXECUTE FUNCTION guard_warehouse_allocation_trail_append_only();
CREATE TRIGGER warehouse_allocation_hold_append_only BEFORE UPDATE OR DELETE
 ON fulfillment_warehouse_allocation_hold
 FOR EACH ROW EXECUTE FUNCTION guard_warehouse_allocation_trail_append_only();
CREATE TRIGGER warehouse_allocation_event_append_only BEFORE UPDATE OR DELETE
 ON fulfillment_warehouse_allocation_event
 FOR EACH ROW EXECUTE FUNCTION guard_warehouse_allocation_trail_append_only();

-- ---------------------------------------------------------------------------
-- 10. 多租户：RLS + 授权（与 trade V1 第 2 节、warehouse V85 第 9 节同一套写法）
-- ---------------------------------------------------------------------------
-- 八张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY[
   'fulfillment_warehouse_allocation_rule',
   'fulfillment_warehouse_allocation_warehouse',
   'fulfillment_warehouse_allocation_stock_estimate',
   'fulfillment_warehouse_allocation_shipping_estimate',
   'fulfillment_warehouse_allocation_decision',
   'fulfillment_warehouse_allocation_candidate',
   'fulfillment_warehouse_allocation_hold',
   'fulfillment_warehouse_allocation_event'
  ] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  fulfillment_warehouse_allocation_rule,
  fulfillment_warehouse_allocation_warehouse,
  fulfillment_warehouse_allocation_stock_estimate,
  fulfillment_warehouse_allocation_shipping_estimate,
  fulfillment_warehouse_allocation_decision,
  fulfillment_warehouse_allocation_candidate,
  fulfillment_warehouse_allocation_hold,
  fulfillment_warehouse_allocation_event TO trade_app;
-- 授权在前、收回在后：把 REVOKE 写在 GRANT **之前**会被上面这条 GRANT 全量覆盖回去，
-- append-only 于是只剩注释（V92 第 9 节踩过同一个坑）。
REVOKE UPDATE,DELETE ON
  fulfillment_warehouse_allocation_decision,
  fulfillment_warehouse_allocation_candidate,
  fulfillment_warehouse_allocation_hold,
  fulfillment_warehouse_allocation_event FROM trade_app;
-- 状态推进函数是 SECURITY DEFINER，必须显式授权。
GRANT EXECUTE ON FUNCTION advance_warehouse_allocation_status(uuid,varchar,uuid,varchar) TO trade_app;
-- 本迁移**零 ALTER** 任何既有表（含 V92 的七张表与 V1 的 trade_tenancy_probe），
-- 被本迁移约束到的既有模块 = 无，跨车道 DDL 回归面 = 0。