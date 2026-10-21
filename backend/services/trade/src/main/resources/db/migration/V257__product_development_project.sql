-- 6.6.1.1 开发立项：管理新品开发立项 —— 立项编号、品类、目标市场与平台、目标售价与毛利、
-- 开发负责人、打样与量产时间计划、状态（评估/立项/开发中/打样/试产/量产/终止）。
--
-- 蓝图 06-purchase-and-supply.md 6.6.1.1 原文：
--   职责：管理新品开发立项：立项编号、品类、目标市场与平台、目标售价与毛利、开发负责人、
--         打样与量产时间计划、状态（评估/立项/开发中/打样/试产/量产/终止）。
--         立项通过后可派生打样单与试产单，并作为 SKU 建档的前置。
--   输入：立项申请与评审结论；目标定价与成本估算（5.3.1.1）。
--   输出：开发单（立项记录 + 里程碑计划）；可转 SKU 建档的候选参数。
--   关系：上游 = 5 定价（目标毛利）、14 广告（市场数据）；下游 = 6.6.1.2、6.6.2.1、3.1.1.1 建档。
--         **立项不是 ERP 传统模块，但是品牌化客户的源头** —— 把"开发-打样-试产-量产"纳入同一编号
--         体系，才能让开发成本进入 SKU 生命周期。
--
-- 归属：**trade 服务**（卷 06 后端主责服务 = trade + catalog，取列表第一项 = trade）。
-- 前端路由 /purchase/development/projects 是**前端**路由；后端命名空间按服务走
-- （与 6.2.1.3 的 /trade/purchase-plans、6.2.1.2 的 /trade/replenishment-suggestions 同一纪律）。
--
-- ===========================================================================
-- 一、模块边界：本文件**不碰任何既有表**，也不碰 catalog / channel / warehouse 的库
-- ===========================================================================
--   * **5.3.1.1 利润测算器（catalog 服务，已交付，V227）**：目标成本与目标毛利的**取数来源**。
--     本库**只经 catalog 的授权只读端点**读它（trade 的 DevelopmentCostEstimatePort），
--     不直连对方库、不建跨库视图、不建跨库外键。取到的成本在本库**落一份快照**，
--     快照里同时记来源（cost_source）与口径（cost_basis）。
--   * **14 广告（市场数据）**：卷 14 的主责服务是 channel + reporting，而 **reporting 服务在本仓不存在**
--     （backend/services/ 下只有 catalog / channel / crm / identity / trade / warehouse）。
--     ⇒ 系统侧市场数据**取不到**：本模块**不建任何市场规模 / 竞品价位 / 搜索热度表**
--     （那等于编一个不存在的上游），能力声明里如实标 available=false + ownerModule='14 广告'。
--     租户**手工录入**的市场数据允许存在，但**必须标明来源与采集时间**
--     （development_project_market_observation.source / .collected_at 两列都 NOT NULL 且非空）。
--   * **3.1.1.1 SKU 建档（catalog 服务，已交付）**：本模块**只产出候选参数**
--     （development_project_handoff 的 SKU_ONBOARDING 交接单），
--     **不建 SKU、不写 catalog 的表** —— SKU 的唯一写入所有者是 catalog。
--   * **6.6.1.2 打样 / 6.6.2.1 试产**：都**未交付** ⇒ 本模块**不代它们建单**，
--     只产出**交接单**（SAMPLING_ORDER / PILOT_ORDER），交接单自己记 target_module，
--     下游交付后由下游消费。**本模块的运行不依赖任何未交付模块。**
--   * **14 广告 / 6.6.1.2 / 6.6.2.1 / 3.1.1.1 都不在本文件建表** ——
--     跨服务只经授权 HTTP API，跨库 FK / 跨库视图本仓禁止。
--
-- ===========================================================================
-- 二、七张业务表的职责划分
-- ===========================================================================
--   1. `development_project` —— **立项记录（开发单头）**：编号、名称、品类、状态、负责人、
--      目标售价、目标成本、目标毛利、评审结论、参考 SKU、上市时间目标。
--   2. `development_project_market` —— **目标市场与平台**（一张立项可以有多个「市场 × 平台」组合，
--      每个组合有自己的目标售价 —— 同一款产品在美国亚马逊和德国亚马逊不是一个价）。
--   3. `development_project_milestone` —— **里程碑计划**（打样 / 试产 / 量产 / 上市的计划时间）。
--   4. `development_project_transition` —— **状态迁移事件**（不可改写的留痕）。
--   5. `development_project_spec_candidate` —— **规格候选**（可转 SKU 建档的候选参数之一）。
--   6. `development_project_market_observation` —— **手工录入的市场数据**（必带来源 + 采集时间）。
--   7. `development_project_handoff` —— **下游交接单**（打样单 / 试产单 / SKU 建档）。
--
-- ===========================================================================
-- 三、状态机（服务层 + 数据库层**两处**都钉死）
-- ===========================================================================
--   评估 EVALUATION → 立项 APPROVED → 开发中 IN_DEVELOPMENT → 打样 SAMPLING
--     → 试产 PILOT → 量产 MASS_PRODUCTION；终止 TERMINATED 是**终态**（不提供「恢复」）。
--
--   🔴 **「终止」是终态**：TERMINATED 之后**没有**任何出边。
--      「恢复」会让「这个立项为什么停了」永远查不回来 —— 而那正是终止事件存在的理由。
--
--   🔴 **「量产之前不许跳过打样 / 试产」**：正常路径必须逐级走
--      IN_DEVELOPMENT → SAMPLING → PILOT → MASS_PRODUCTION。
--      要跳，必须有**显式的豁免记录**（transition.exemption_code + exemption_note 都非空），
--      数据库守卫触发器会挡住没有豁免记录的跳跃 —— 「悄悄跳过去」与「明确豁免」是两件事。
--      允许豁免的跳跃只有三条（都是「跳过打样 / 试产」）：
--        IN_DEVELOPMENT → PILOT              （跳过打样）
--        IN_DEVELOPMENT → MASS_PRODUCTION   （跳过打样与试产）
--        SAMPLING       → MASS_PRODUCTION   （跳过试产）
--
--   🔴 **每次状态迁移都要落一条不可改写的事件**（谁、何时、从哪到哪、为什么）：
--      development_project_transition 是 append-only 留痕，
--      **状态守卫触发器要求迁移事件先存在**，否则状态变更直接被数据库拒绝 ——
--      也就是说「改了状态却没有事件」在数据库层就做不到，不是靠应用层自觉。
--
-- ===========================================================================
-- 四、单位与时间纪律
-- ===========================================================================
--   * 金额一律 `numeric(19,4)`，费率（目标毛利率）`numeric(19,6)`。
--     **禁浮点**：Java 侧 BigDecimal，毛利率单次 HALF_UP 舍入到 6 位
--     （与 5.3.1.1 利润测算器的毛利率口径**一致**，同一个立项在定价页与立项页不会显示两个毛利）。
--   * 数量 `numeric(19,6)`（本模块没有实物数量列 —— 数量属打样 / 试产 / 量产单，不属立项）。
--   * 时间列一律 `timestamptz`；Java 侧用 `OffsetDateTime` 绑定，
--     SQL 的 values 位置写 `?::timestamptz`（用 `Timestamp.valueOf(LocalDateTime...)` 会静默偏 −28800s）。
--   * **打样 / 量产时间计划是计划日期（timestamptz），不许用 `LocalDate.now()` 当默认基准** ——
--     应用机与数据库机差半天时「今天」会算错一天。计划时间必须由人显式填。
--
-- ===========================================================================
-- 1. 立项记录（开发单头）
-- ===========================================================================
CREATE TABLE development_project (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- **立项编号**：租户内唯一、可读（DP-20260101-a1b2c3d4）。
  -- 蓝图加粗句「把开发-打样-试产-量产纳入同一编号体系」的落点：这个编号就是那条线的锚。
  project_no varchar(64) NOT NULL CHECK(btrim(project_no) <> ''),
  -- 立项名称（新品名 / 款式名）。
  name varchar(200) NOT NULL CHECK(btrim(name) <> ''),
  -- **品类**（蓝图职责句点名的字段）。自由文本，但非空 —— 没有品类的立项没法归口分析。
  category varchar(64) NOT NULL CHECK(btrim(category) <> ''),
  -- 状态：七个，取值见蓝图职责句。
  status varchar(24) NOT NULL DEFAULT 'EVALUATION'
    CHECK(status IN ('EVALUATION','APPROVED','IN_DEVELOPMENT','SAMPLING','PILOT','MASS_PRODUCTION','TERMINATED')),
  -- **开发负责人**（蓝图职责句点名的字段）。
  owner_id uuid NOT NULL,
  owner_name varchar(100) NOT NULL DEFAULT '',
  -- **目标售价**与**币种**（ISO 4217 三位大写码）。
  target_price numeric(19,4) NOT NULL CHECK(target_price > 0),
  currency varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- **目标成本**。取不到时为 **NULL**（**绝不用 0 顶替**：成本 0 的毛利率是 100%，
  -- 是彻头彻尾的假数据 —— 宁可显示「取不到」，也不显示一个漂亮但假的毛利）。
  target_cost numeric(19,4) CHECK(target_cost IS NULL OR target_cost >= 0),
  -- 成本币种。与售价币种**必须相同**才有资格算毛利率（跨币种绝不相加，
  -- 5.2.2.1 汇率查询未交付 ⇒ 本模块不做折算）。
  cost_currency varchar(3) CHECK(cost_currency IS NULL OR cost_currency ~ '^[A-Z]{3}$'),
  -- 成本**取到了**吗。false ≠ 0，是「答不出来」这件事本身。
  cost_available boolean NOT NULL DEFAULT false,
  -- 成本来源：CATALOG_ESTIMATE（5.3.1.1 利润测算器经授权只读端点取到）
  --         / MANUAL_ESTIMATE（租户按供应商报价 / BOM 手工估算）
  --         / UNAVAILABLE（取不到）。
  cost_source varchar(32) NOT NULL DEFAULT 'UNAVAILABLE'
    CHECK(cost_source IN ('CATALOG_ESTIMATE','MANUAL_ESTIMATE','UNAVAILABLE')),
  -- 取不到时点名**归谁管**（5.3.1.1 利润测算器 / 5.2.2.1 汇率）。
  cost_owner_module varchar(32) NOT NULL DEFAULT '',
  -- 成本口径原文（成本法、窗口、来源单据……）。
  cost_basis text NOT NULL DEFAULT '',
  -- 成本估算/取数的时刻（手工估算必填；系统取数填上游口径的生效时刻）。
  cost_estimated_at timestamptz,
  -- **目标毛利率**：**一律由服务端算**（毛利 = (售价 − 成本) ÷ 售价，单次 HALF_UP 到 6 位）。
  -- 调用方**不许**自己报一个毛利率 —— 客户端报上来的毛利率是「愿望」，不是结论。
  -- 成本不可用时为 NULL（连带 margin_basis 说明为什么没有）。
  target_margin_rate numeric(19,6),
  -- 毛利率算式口径原文（公式 + 舍入口径 + 币种），随结论一起返回。
  margin_basis text NOT NULL DEFAULT '',
  -- **评审结论**（蓝图输入句「立项申请与评审结论」）。
  decision_note text NOT NULL DEFAULT '',
  -- **参考 SKU**：用来从 5.3.1.1 取成本估算的既有 SKU（成本基准）。
  -- 新品还没建档时通常没有；有了它，目标成本才有系统来源而不是只能手工估。
  reference_sku_id uuid,
  reference_sku_code varchar(64) NOT NULL DEFAULT '',
  -- **上市时间目标**（可转 SKU 建档的候选参数之一）。
  launch_target_at timestamptz,
  note text NOT NULL DEFAULT '',
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, project_no),
  -- 🔴 成本与「可用」标记必须自洽：
  --   可用 ⇒ 有成本数字 + 有成本币种 + 币种与售价一致 + 来源不是 UNAVAILABLE；
  --   不可用 ⇒ **没有**成本数字（NULL，不是 0）+ 来源是 UNAVAILABLE + 点名归谁管。
  CONSTRAINT development_project_cost_consistent CHECK(
    (cost_available
        AND target_cost IS NOT NULL
        AND cost_currency = currency
        AND cost_source <> 'UNAVAILABLE')
    OR (NOT cost_available
        AND target_cost IS NULL
        AND cost_currency IS NULL
        AND cost_source = 'UNAVAILABLE'
        AND btrim(cost_owner_module) <> '')
  ),
  -- 🔴 目标毛利率**必须**与成本可用性同进同退：
  --   成本可用 ⇒ 有毛利率；成本不可用 ⇒ **没有**毛利率（不是 0，也不是 100%）。
  CONSTRAINT development_project_margin_consistent CHECK(
    (cost_available AND target_margin_rate IS NOT NULL)
    OR (NOT cost_available AND target_margin_rate IS NULL)
  ),
  -- 成本非负 ⇒ 毛利 = (价 − 成本) ÷ 价 < 1。
  CONSTRAINT development_project_margin_range CHECK(
    target_margin_rate IS NULL OR target_margin_rate < 1
  )
);

-- ===========================================================================
-- 2. 目标市场与平台
-- ===========================================================================
CREATE TABLE development_project_market (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  project_id uuid NOT NULL,
  -- **目标市场**（US / UK / DE / JP …）：自由 code，但非空。
  market_code varchar(32) NOT NULL CHECK(btrim(market_code) <> ''),
  -- **目标平台**（AMAZON / SHOPIFY / …）：自由 code，但非空。
  platform_code varchar(32) NOT NULL CHECK(btrim(platform_code) <> ''),
  -- 该「市场 × 平台」组合的目标售价（同一个立项在不同市场可以不是一个价）。
  target_price numeric(19,4) NOT NULL CHECK(target_price > 0),
  currency varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 优先级：1 = 首发市场。
  priority int NOT NULL DEFAULT 1 CHECK(priority BETWEEN 1 AND 9),
  sort_rank int NOT NULL DEFAULT 0 CHECK(sort_rank >= 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一立项里一个「市场 × 平台」只有一行（重复登记会让目标售价出现两个值）。
  UNIQUE(tenant_id, project_id, market_code, platform_code),
  FOREIGN KEY(tenant_id, project_id) REFERENCES development_project(tenant_id, id) ON DELETE CASCADE
);

-- ===========================================================================
-- 3. 里程碑计划（打样 / 试产 / 量产 / 上市）
-- ===========================================================================
CREATE TABLE development_project_milestone (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  project_id uuid NOT NULL,
  -- 里程碑类型。
  milestone_type varchar(24) NOT NULL
    CHECK(milestone_type IN ('SAMPLING','PILOT','MASS_PRODUCTION','LAUNCH')),
  -- **计划时间**（timestamptz）。必填：**没有默认的「今天」** ——
  -- 应用机与数据库机差半天时，用当天算出来的计划日会整整错一天。
  planned_at timestamptz NOT NULL,
  -- 实际发生时刻（未发生为 NULL）。
  actual_at timestamptz,
  -- PLANNED 计划中 / REACHED 已达成 / MISSED 错过。
  status varchar(16) NOT NULL DEFAULT 'PLANNED'
    CHECK(status IN ('PLANNED','REACHED','MISSED')),
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一立项里一种里程碑只有一行。
  UNIQUE(tenant_id, project_id, milestone_type),
  FOREIGN KEY(tenant_id, project_id) REFERENCES development_project(tenant_id, id) ON DELETE CASCADE,
  -- 状态与实际时刻必须自洽：
  --   PLANNED ⇒ 没有实际时刻；
  --   REACHED ⇒ **必须**有实际时刻（"达成了但不知道什么时候"等于没达成）；
  --   MISSED  ⇒ 可有可无（可能补记了实际发生时刻）。
  CONSTRAINT development_project_milestone_actual_consistent CHECK(
    (status = 'PLANNED' AND actual_at IS NULL)
    OR (status = 'REACHED' AND actual_at IS NOT NULL)
    OR (status = 'MISSED')
  )
);

-- ===========================================================================
-- 4. 状态迁移事件（不可改写的留痕）
-- ===========================================================================
CREATE TABLE development_project_transition (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  project_id uuid NOT NULL,
  -- 从哪到哪（谁、何时、从哪到哪、为什么 —— 蓝图要求的四要素）。
  from_status varchar(24) NOT NULL
    CHECK(from_status IN ('EVALUATION','APPROVED','IN_DEVELOPMENT','SAMPLING','PILOT','MASS_PRODUCTION','TERMINATED')),
  to_status varchar(24) NOT NULL
    CHECK(to_status IN ('EVALUATION','APPROVED','IN_DEVELOPMENT','SAMPLING','PILOT','MASS_PRODUCTION','TERMINATED')),
  -- **为什么**（必填）：一句「推进一下」不是留痕。
  reason text NOT NULL CHECK(btrim(reason) <> ''),
  -- **豁免记录**：跳过打样 / 试产时必填（码 + 说明都非空）。
  exemption_code varchar(32),
  exemption_note text NOT NULL DEFAULT '',
  actor_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL,
  -- 由状态守卫触发器写入：这条事件已经被哪次状态变更用掉了。
  -- 插入时恒为 NULL（见 guard_development_project_transition_insert）。
  applied_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, project_id) REFERENCES development_project(tenant_id, id) ON DELETE RESTRICT,
  -- 豁免码与豁免说明必须**同时**有或同时没有 —— 半条豁免记录等于没有豁免记录。
  CONSTRAINT development_project_transition_exemption_consistent CHECK(
    (exemption_code IS NULL AND exemption_note = '')
    OR (exemption_code IS NOT NULL AND btrim(exemption_code) <> '' AND btrim(exemption_note) <> '')
  ),
  -- 终止事件必须带原因（reason 已 NOT NULL 且非空，这里再钉一层「不能自己转自己」）。
  CONSTRAINT development_project_transition_distinct CHECK(from_status <> to_status)
);

-- ===========================================================================
-- 5. 规格候选（可转 SKU 建档的候选参数之一）
-- ===========================================================================
CREATE TABLE development_project_spec_candidate (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  project_id uuid NOT NULL,
  -- 规格名（颜色 / 尺寸 / 材质 …）与规格值。
  spec_name varchar(120) NOT NULL CHECK(btrim(spec_name) <> ''),
  spec_value varchar(200) NOT NULL CHECK(btrim(spec_value) <> ''),
  sort_rank int NOT NULL DEFAULT 0 CHECK(sort_rank >= 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, project_id, spec_name, spec_value),
  FOREIGN KEY(tenant_id, project_id) REFERENCES development_project(tenant_id, id) ON DELETE CASCADE
);

-- ===========================================================================
-- 6. 手工录入的市场数据（**必须**标明来源与采集时间）
-- ===========================================================================
--   🔴 系统侧市场数据（类目规模 / 竞品价位 / 搜索热度）取不到：14 广告的主责服务
--      channel + reporting 中 **reporting 服务在本仓不存在**。
--      本表**只**存租户自己采集并**标明来源与采集时间**的观察值 ——
--      它是「人的观察」，不是「系统算出来的市场规模」。source / collected_at 两列
--      都 NOT NULL 且非空：没有来源的市场数据比没有市场数据更危险。
CREATE TABLE development_project_market_observation (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  project_id uuid NOT NULL,
  market_code varchar(32) NOT NULL CHECK(btrim(market_code) <> ''),
  platform_code varchar(32) NOT NULL DEFAULT '',
  -- 指标：CATEGORY_SIZE 类目规模 / COMPETITOR_PRICE 竞品价位 /
  --       SEARCH_VOLUME 搜索热度 / OTHER 其它。
  metric_code varchar(32) NOT NULL
    CHECK(metric_code IN ('CATEGORY_SIZE','COMPETITOR_PRICE','SEARCH_VOLUME','OTHER')),
  -- 指标值（数量 / 金额口径由 value_unit 说明）。
  metric_value numeric(19,6),
  value_unit varchar(32) NOT NULL DEFAULT '',
  -- **来源**：非空。「亚马逊美国站类目页 2026-10」这种能被人复核的来源。
  source varchar(200) NOT NULL CHECK(btrim(source) <> ''),
  -- **采集时间**：非空。数据有多旧，必须能被回答。
  collected_at timestamptz NOT NULL,
  note text NOT NULL DEFAULT '',
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  FOREIGN KEY(tenant_id, project_id) REFERENCES development_project(tenant_id, id) ON DELETE CASCADE
);

-- ===========================================================================
-- 7. 下游交接单（打样单 / 试产单 / SKU 建档）
-- ===========================================================================
--   🔴 6.6.1.2 打样、6.6.2.1 试产、3.1.1.1 建档**都不在本模块建单**：
--      前两者未交付，第三者归 catalog 服务。本表是**交接单** —— 本模块产出候选参数，
--      下游交付后由下游消费；交接单自己记 target_module 与 status，
--      界面据此显示「已发出、等待 6.6.1.2 消费」，而不是假装打样单已经存在。
CREATE TABLE development_project_handoff (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  project_id uuid NOT NULL,
  handoff_type varchar(24) NOT NULL
    CHECK(handoff_type IN ('SAMPLING_ORDER','PILOT_ORDER','SKU_ONBOARDING')),
  -- 目标模块（与 handoff_type 一一对应，由 CHECK 钉死）。
  target_module varchar(16) NOT NULL,
  -- PENDING 已发出待消费 / ACCEPTED 下游已接收 / CANCELLED 已撤销。
  status varchar(16) NOT NULL DEFAULT 'PENDING'
    CHECK(status IN ('PENDING','ACCEPTED','CANCELLED')),
  -- 候选参数载荷（品类 / 目标市场 / 目标售价区间 / 规格候选 / 上市时间目标 …）。
  payload jsonb NOT NULL,
  note text NOT NULL DEFAULT '',
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, project_id, handoff_type),
  FOREIGN KEY(tenant_id, project_id) REFERENCES development_project(tenant_id, id) ON DELETE CASCADE,
  CONSTRAINT development_project_handoff_target_consistent CHECK(
    (handoff_type = 'SAMPLING_ORDER' AND target_module = '6.6.1.2')
    OR (handoff_type = 'PILOT_ORDER' AND target_module = '6.6.2.1')
    OR (handoff_type = 'SKU_ONBOARDING' AND target_module = '3.1.1.1')
  )
);

-- ===========================================================================
-- 8. 触发器（纵深防线：CHECK 在 PostgreSQL 里可被迁移脚本绕过，触发器不行）
-- ===========================================================================
-- 8.1 迁移事件**插入时 applied_at 恒为 NULL** —— 它只能被状态守卫触发器写上去。
--     不这样的话，应用可以在插入时就伪造「这条事件已经被用掉了」，
--     状态守卫随后会认为「没有可用的迁移事件」而拒绝变更 —— 或者更糟，
--     一条伪造事件顶掉一次真实迁移。
CREATE FUNCTION guard_development_project_transition_insert() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  NEW.applied_at := NULL;
  RETURN NEW;
END $$;
CREATE TRIGGER development_project_transition_insert BEFORE INSERT ON development_project_transition
  FOR EACH ROW EXECUTE FUNCTION guard_development_project_transition_insert();

-- 8.2 迁移事件**不可改写、不可删**（只允许把 applied_at 从 NULL 标成一次时刻）。
CREATE FUNCTION guard_development_project_transition_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION '开发立项状态迁移事件不可删除（%）：删掉它就等于抹掉「当时为什么这么转」的证据', OLD.id
      USING ERRCODE='23514';
  END IF;
  IF NEW.id IS DISTINCT FROM OLD.id
     OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
     OR NEW.project_id IS DISTINCT FROM OLD.project_id
     OR NEW.from_status IS DISTINCT FROM OLD.from_status
     OR NEW.to_status IS DISTINCT FROM OLD.to_status
     OR NEW.reason IS DISTINCT FROM OLD.reason
     OR NEW.exemption_code IS DISTINCT FROM OLD.exemption_code
     OR NEW.exemption_note IS DISTINCT FROM OLD.exemption_note
     OR NEW.actor_id IS DISTINCT FROM OLD.actor_id
     OR NEW.occurred_at IS DISTINCT FROM OLD.occurred_at
     OR NEW.created_at IS DISTINCT FROM OLD.created_at
     OR (OLD.applied_at IS NOT NULL AND NEW.applied_at IS DISTINCT FROM OLD.applied_at) THEN
    RAISE EXCEPTION '开发立项状态迁移事件不可改写（%）', OLD.id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER development_project_transition_immutable BEFORE UPDATE OR DELETE ON development_project_transition
  FOR EACH ROW EXECUTE FUNCTION guard_development_project_transition_immutable();

-- 8.3 🔴 **状态守卫**：数据库层钉死状态机。
--     * 只放行蓝图那七个状态之间的**单向**推进；
--     * TERMINATED 是终态（没有出边，不提供「恢复」）；
--     * 跳过打样 / 试产必须有**显式豁免记录**；
--     * **每次状态变更都必须有一条对应的迁移事件**（谁 / 何时 / 从哪到哪 / 为什么），
--       没有事件 ⇒ 直接拒绝 —— 「改了状态却没有事件」在数据库层就做不到。
CREATE FUNCTION guard_development_project_status() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_allowed boolean := false;
  v_needs_exemption boolean := false;
  v_event_id uuid;
  v_exemption varchar(32);
BEGIN
  IF NEW.status IS NOT DISTINCT FROM OLD.status THEN
    RETURN NEW;
  END IF;

  -- 允许的迁移（与 Java 侧 DevelopmentProjectRules.allowedTargets 逐条同构）。
  v_allowed := CASE OLD.status
    WHEN 'EVALUATION'      THEN NEW.status IN ('APPROVED','TERMINATED')
    WHEN 'APPROVED'        THEN NEW.status IN ('IN_DEVELOPMENT','TERMINATED')
    WHEN 'IN_DEVELOPMENT'  THEN NEW.status IN ('SAMPLING','PILOT','MASS_PRODUCTION','TERMINATED')
    WHEN 'SAMPLING'        THEN NEW.status IN ('PILOT','MASS_PRODUCTION','TERMINATED')
    WHEN 'PILOT'           THEN NEW.status IN ('MASS_PRODUCTION','TERMINATED')
    WHEN 'MASS_PRODUCTION' THEN NEW.status IN ('TERMINATED')
    ELSE false
  END;
  IF NOT v_allowed THEN
    RAISE EXCEPTION '开发立项状态不允许从 % 迁移到 %（终止是终态，不提供恢复）', OLD.status, NEW.status
      USING ERRCODE='23514';
  END IF;

  -- 跳过打样 / 试产 ⇒ 必须有显式豁免记录。
  v_needs_exemption :=
    (OLD.status = 'IN_DEVELOPMENT' AND NEW.status IN ('PILOT','MASS_PRODUCTION'))
    OR (OLD.status = 'SAMPLING' AND NEW.status = 'MASS_PRODUCTION');

  SELECT t.id, t.exemption_code INTO v_event_id, v_exemption
    FROM development_project_transition t
   WHERE t.tenant_id = NEW.tenant_id
     AND t.project_id = NEW.id
     AND t.from_status = OLD.status
     AND t.to_status = NEW.status
     AND t.applied_at IS NULL
   ORDER BY t.created_at DESC, t.id DESC
   LIMIT 1;
  IF NOT FOUND THEN
    RAISE EXCEPTION '开发立项状态变更缺少对应迁移事件（% → %）：每次状态迁移都必须落一条不可改写的记录', OLD.status, NEW.status
      USING ERRCODE='23514';
  END IF;
  IF v_needs_exemption AND (v_exemption IS NULL OR btrim(v_exemption) = '') THEN
    RAISE EXCEPTION '开发立项从 % 跳到 % 跳过了打样 / 试产，必须有显式的豁免记录（豁免码 + 豁免说明）', OLD.status, NEW.status
      USING ERRCODE='23514';
  END IF;

  UPDATE development_project_transition SET applied_at = now() WHERE id = v_event_id;
  RETURN NEW;
END $$;
CREATE TRIGGER development_project_status_guard BEFORE UPDATE ON development_project
  FOR EACH ROW EXECUTE FUNCTION guard_development_project_status();

-- 8.4 立项记录的**身份列冻结**：编号、创建人、创建时刻是「这条立项记录是谁、什么时候立的」
--     的举证凭据，改编号会让历史迁移事件与交接单指向一个不存在的东西。
CREATE FUNCTION guard_development_project_identity_frozen() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.id IS DISTINCT FROM OLD.id
     OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
     OR NEW.project_no IS DISTINCT FROM OLD.project_no
     OR NEW.created_by IS DISTINCT FROM OLD.created_by
     OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION '开发立项「%」的身份列不可改写（编号 / 创建人 / 创建时刻）：要换编号请新建一条立项', OLD.project_no
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER development_project_identity_frozen BEFORE UPDATE ON development_project
  FOR EACH ROW EXECUTE FUNCTION guard_development_project_identity_frozen();

-- ===========================================================================
-- 9. 索引（按真实查询形状建，不多建）
-- ===========================================================================
-- 立项清单页：按状态 + 品类读（清单页主查询形状）。
CREATE INDEX development_project_by_status ON development_project(tenant_id, status, created_at desc, id);
CREATE INDEX development_project_by_category ON development_project(tenant_id, category, created_at desc, id);
-- 立项清单页：按负责人反查（「我负责的开发有哪些」）。
CREATE INDEX development_project_by_owner ON development_project(tenant_id, owner_id, created_at desc, id);
-- 目标市场：按立项 + 优先级读（详情页第二段）。
CREATE INDEX development_project_market_by_project ON development_project_market(tenant_id, project_id, priority, sort_rank);
-- 里程碑：按立项 + 计划时间读（详情页第三段）。
CREATE INDEX development_project_milestone_by_project ON development_project_milestone(tenant_id, project_id, planned_at);
-- 迁移事件：按立项 + 发生时刻读（详情页第四段，时间线）。
CREATE INDEX development_project_transition_by_project ON development_project_transition(tenant_id, project_id, occurred_at desc, id);
-- 规格候选：按立项 + 排序位次读。
CREATE INDEX development_project_spec_candidate_by_project ON development_project_spec_candidate(tenant_id, project_id, sort_rank, id);
-- 市场观察：按立项 + 采集时刻读。
CREATE INDEX development_project_market_observation_by_project ON development_project_market_observation(tenant_id, project_id, collected_at desc, id);
-- 交接单：按立项 + 状态读。
CREATE INDEX development_project_handoff_by_project ON development_project_handoff(tenant_id, project_id, status);

-- ===========================================================================
-- 10. 多租户：RLS + 授权（与 trade V1 / V92 / V234 / V242 / V251 同一套写法）
-- ===========================================================================
-- 七张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'development_project','development_project_market','development_project_milestone',
    'development_project_transition','development_project_spec_candidate',
    'development_project_market_observation','development_project_handoff'
  ] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  development_project,development_project_market,development_project_milestone,
  development_project_transition,development_project_spec_candidate,
  development_project_market_observation,development_project_handoff TO trade_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  development_project,development_project_market,development_project_milestone,
  development_project_transition,development_project_spec_candidate,
  development_project_market_observation,development_project_handoff TO starcon_app;
-- 迁移事件**不可删**：删掉它就等于抹掉「当时为什么这么转」的证据。
REVOKE DELETE ON development_project_transition FROM trade_app;
-- 立项记录**不提供 DELETE**：立项是业务凭据（迁移事件与交接单都指向它），
-- 删除会抹掉「这个立项经历过什么」的证据。
REVOKE DELETE ON development_project FROM trade_app;

-- ---------------------------------------------------------------------------
-- 11. 本迁移的回归面（跨车道纪律）
-- ---------------------------------------------------------------------------
--   * **不 ALTER 任何既有表**：V1 / V92 / V234 / V238 / V242 / V251 的表一个字节都不碰，
--     因此既没有 ADD COLUMN / SET NOT NULL，也没有既有表触发器被新约束覆盖 ——
--     跨车道 DDL 回归面 = 0，不牵动任何已交付模块的既有集成测试。
--   * **不写别的模块的表**：本文件只建自己的七张表；
--     5.3.1.1（catalog）、3.1.1.1（catalog）、14 广告（reporting，**服务不存在**）的表
--     一个都不碰、也不建跨库外键到它们（那不是本模块的写入所有权）。
--   * 对 catalog 的数据**只经授权 HTTP API 读**：跨库 FK 本来就禁止，
--     所以本文件既没有约束到对方库的任何一张表。
--   * 新增函数全部带 development_project_ 前缀，与 V1 / V25 / V92 / V234 / V238 / V242
--     的既有函数名不冲突。
