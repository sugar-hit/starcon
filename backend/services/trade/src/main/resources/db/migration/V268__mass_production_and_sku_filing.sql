-- 6.6.2.2 转量产与建档：试产通过后转正式量产 —— 确定量产 BOM、批量采购/生产计划、
-- 目标成本、正式 SKU 建档（含编码/条码/属性/报关属性），并把量产参数带入采购与价格模型。
--
-- 蓝图 06-purchase-and-supply.md 6.6.2.2 原文：
--   职责：试产通过后转正式量产：确定量产 BOM、批量采购/生产计划、目标成本、
--         正式 SKU 建档（含编码/条码/属性/报关属性），并把量产参数带入采购与价格模型。
--   输入：试产结果；量产决策；BOM 定版。
--   输出：量产计划；正式 SKU（3.1.2.1）；量产 BOM 版本；采购需求（6.2.1.2）。
--   关系：上游 = 6.6.2.1；下游 = 3 商品主数据、6 采购、5 定价。
--         **转量产是开发与供应链的交接口**，SKU 编码/条码在此时生成，后续全链路引用此编码。
--
-- 归属：**trade 服务**（卷 06 后端主责服务 = trade + catalog（party），取列表第一项 = trade）。
-- 后端命名空间 /trade/mass-productions；前端路由 /purchase/development/mass-production。
--
-- ===========================================================================
-- 一、模块边界：本文件**不碰任何既有表**，也不碰 catalog / warehouse 的库
-- ===========================================================================
--   * **6.6.2.1 试产单（trade 服务，同一个库，已交付 V264）**：
--     `production_trial_order` / `production_trial_mass_production_recommendation` 是**它**的事实
--     ⇒ 本模块**只 SELECT 引用 + 展示快照**，**零外键、零 ALTER、零写入**。
--     本模块消费的是它的「转量产建议」（建议事实），**不是**它的结果表。
--   * **3.5.2.1 BOM 版本（catalog 独立库，已交付 V25）**：
--     `bom_version` / `bom_component` 在 **catalog 服务的库**里 ⇒ 本模块**零跨库 FOREIGN KEY、
--     零跨库视图、零跨服务直连查询**。量产 BOM 版本一律 **逻辑引用（uuid）+ 展示快照**。
--     组件需求量**原样搬运 catalog 的 R6/R7**，本服务**不重算**。
--   * **3.1.2.1 SKU 列表 / 商品档案（catalog 独立库，已交付 V5 + CatalogController）**：
--     🔴🔴 正式 SKU 建档的**写入所有权在 catalog**（`POST /catalog/products/{id}/skus`，
--     权限 `catalog.sku.create`；`PUT /catalog/customs-attributes/skus/{skuId}` 写报关属性）。
--     ⇒ 本模块**不建 SKU 表、不跨库写 SKU**。本模块落的是
--     **本服务侧的「建档请求 / 待办事实」**（目标系统 + 目标键 + 载荷 + 幂等键 + 状态机），
--     再**经 catalog 的授权 HTTP 接口**把 SKU 建出来。
--     建档结果（catalog 返回的 SKU id / 编码）是**读回来的事实**，不是本模块编的。
--   * **6.2.1.2 补货建议（trade 服务，同一个库，已交付 V238）**：
--     🔴 `replenishment_suggestion*` / `replenishment_order_policy` 是**它**的表与不变量
--     ⇒ 本模块**绝不 INSERT / UPDATE 它的表**。本模块只做**重算触发**
--     （调用它自己的 `ReplenishmentSuggestionService.compute(...)`），
--     并把「量产参数**没有**进到它里面去」如实记在交接留痕里
--     （`mass_production_input_applied=false`）—— 与 6.3.2.1 跟单交期的诚实口径同构。
--   * **5 定价（5.3.1.1 目标定价，未开发；仓里没有 pricing 服务）** ⇒ 如实标缺口，不 mock 算价。
--   * **8.4.1 组装单（warehouse 独立库）** ⇒ 量产「生产计划」的下达由它承接，如实标缺口。
--
-- ===========================================================================
-- 二、八张业务表的职责划分
-- ===========================================================================
--   1. `mass_production_plan` —— **量产计划（头）**：编号、上游试产单引用与快照、
--      量产 BOM 版本引用与快照、计划批量、目标成本与币种、产线/工厂、状态、决策留痕。
--   2. `mass_production_plan_transition` —— 量产计划**状态迁移事件**（不可改写的留痕）。
--   3. `mass_production_bom_line` —— **量产 BOM 组件行快照**（定版那一刻从 catalog 原样搬过来的）。
--   4. `mass_production_sku_filing` —— **正式 SKU 建档请求 / 待办事实**：
--      目标系统 + 目标键（SPU）+ 载荷（编码/条码/基础单位）+ **幂等键** + 状态机 + 结果。
--   5. `mass_production_sku_filing_attribute` —— 建档载荷里的**属性行**（catalog 的 ValueInput）。
--   6. `mass_production_sku_filing_attribute_value` —— 属性行的**取值**（一行一个值）。
--   7. `mass_production_customs_element` —— 建档载荷里的**报关申报要素行**。
--   8. `mass_production_downstream_handoff` —— **下游交接留痕**（6.2.1.2 采购需求 / 5.3.1.1 价格模型）：
--      成功/失败/跳过都留一行，失败行带原因 —— **绝不假装已经交接**。
--
-- ===========================================================================
-- 三、🔴 状态机（服务层 + 数据库层**两处**都钉死）
-- ===========================================================================
--   量产计划：DRAFT 已建计划 → DECIDED 已决策 → BOM_FROZEN BOM已定版
--            → SKU_FILED SKU已建档 → RELEASED 已下达 → CLOSED 已关闭；
--            任意非终态可 CANCELLED 已取消。**CLOSED / CANCELLED 是终态**（不提供「恢复」）。
--
--   🔴 **每次状态迁移都要落一条不可改写的事件**（谁、什么时候、从哪到哪、为什么），
--      否则 DB 直接拒绝 ⇒ 「改了状态却没有事件」在数据库层就做不到。
--   🔴 **进入「BOM 已定版」前必须真的定版**（组件行快照非空）——
--      定了版却没把组件行搬过来，等于定了一个空版本。
--   🔴 **进入「SKU 已建档」前必须有一条**已确认**（CONFIRMED）的建档请求** ——
--      建档请求只是「交出去」，确认才是「建成了」。
--   🔴 **进入「已下达」前必须已经定版**（BOM 组件行快照非空）——
--      没有定版就下达，等于按一份会变的清单去采购。
--
-- ===========================================================================
-- 四、🔴 幂等键纪律
-- ===========================================================================
--   catalog 的 `POST /catalog/products/{id}/skus` **不接受幂等键** ⇒ 本模块必须自己扛住
--   「重复提交会建出两个 SKU」这件事：
--     * 建档请求生成**幂等键**（租户内唯一），UNIQUE(tenant_id, idempotency_key)；
--     * 一张量产计划**只允许一条**建档请求（UNIQUE(tenant_id, plan_id)）；
--     * 状态为 `CONFIRMED`（已建成）时**拒绝**再次提交（409）；
--     * 状态为 `UNKNOWN`（**结果未知**：调用超时 / 连接中断，无法判断 catalog 到底建没建）
--       时**也拒绝**再次提交（409）—— 盲目重试可能建出重复 SKU，
--       必须先人工去 catalog 核对，再决定怎么收口。
--
-- ===========================================================================
-- 五、单位与时间纪律
-- ===========================================================================
--   * 金额一律 `numeric(19,4)`（**禁浮点**：Java 侧 BigDecimal）。
--   * 数量与比例一律 `numeric(19,6)`。
--   * 时间列一律 `timestamptz`；Java 侧用 `OffsetDateTime` 绑定，
--     SQL 的 values 位置写 `?::timestamptz`（用 `Timestamp.valueOf(LocalDateTime...)`
--     会静默偏 −28800s）。
--
-- ===========================================================================
-- 1. 量产计划（头）
-- ===========================================================================
CREATE TABLE mass_production_plan (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- **量产计划号**：租户内唯一、可读（MP-20261021-a1b2c3d4）。
  plan_no varchar(64) NOT NULL CHECK(btrim(plan_no) <> ''),

  -- ---------------------------------------------------------------------
  -- 上游 6.6.2.1 试产单（**同一个 trade 库**，V264）：只读引用 + 快照，零外键
  -- ---------------------------------------------------------------------
  trial_order_id uuid NOT NULL,
  trial_no varchar(64) NOT NULL CHECK(btrim(trial_no) <> ''),
  -- 上游转量产建议的结论快照（GO / NO_GO）；**取不到就是空串**，不是编一个。
  trial_recommendation_decision varchar(16) NOT NULL DEFAULT ''
    CHECK(trial_recommendation_decision IN ('','GO','NO_GO')),

  -- ---------------------------------------------------------------------
  -- 上游 3.5.2.1 BOM 版本（**catalog 独立库**）：逻辑引用 + 展示快照，零跨库 FK
  -- ---------------------------------------------------------------------
  bom_version_id uuid NOT NULL,
  bom_version_no varchar(32) NOT NULL CHECK(btrim(bom_version_no) <> ''),
  bom_code varchar(64) NOT NULL CHECK(btrim(bom_code) <> ''),
  output_sku_code varchar(64) NOT NULL DEFAULT '',
  output_sku_name varchar(200) NOT NULL DEFAULT '',

  -- ---------------------------------------------------------------------
  -- 量产规模与目标
  -- ---------------------------------------------------------------------
  -- **计划批量**（蓝图职责句点名的「批量采购/生产计划」）。
  plan_quantity numeric(19,6) NOT NULL CHECK(plan_quantity > 0),
  -- **目标成本**（蓝图职责句点名的「目标成本」）。
  --   🔴 **可空 = 取不到**（不是 0）：取不到时成本对比标 comparable=false。
  target_cost numeric(19,4),
  -- 一个计划一个币种；跨币种**绝不相加**（5.2.2.1 汇率查询未交付 ⇒ 不做固定汇率折算）。
  currency varchar(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  production_line_code varchar(64) NOT NULL DEFAULT '',
  production_line_name varchar(200) NOT NULL DEFAULT '',
  factory_code varchar(64) NOT NULL DEFAULT '',
  factory_name varchar(200) NOT NULL DEFAULT '',
  planned_start_at timestamptz,
  planned_end_at timestamptz,

  -- ---------------------------------------------------------------------
  -- 状态：七个，单向推进（服务层 + DB 守卫两处钉死）
  -- ---------------------------------------------------------------------
  status varchar(24) NOT NULL DEFAULT 'DRAFT'
    CHECK(status IN ('DRAFT','DECIDED','BOM_FROZEN','SKU_FILED','RELEASED','CLOSED','CANCELLED')),

  -- ---------------------------------------------------------------------
  -- 量产决策（蓝图输入点名的「量产决策」）
  -- ---------------------------------------------------------------------
  --   decision：GO 转量产 / HOLD 暂缓。
  --   decision_source：TRIAL_RECOMMENDATION 跟随上游建议 / MANUAL_OVERRIDE 人工覆盖。
  --   🔴 上游建议是 NO_GO 而本计划决策是 GO 时，**必须**写 override_reason，
  --      否则就是「没证据地放行一次量产」。
  decision varchar(8) NOT NULL DEFAULT '' CHECK(decision IN ('','GO','HOLD')),
  decision_source varchar(24) NOT NULL DEFAULT ''
    CHECK(decision_source IN ('','TRIAL_RECOMMENDATION','MANUAL_OVERRIDE')),
  decision_rationale text NOT NULL DEFAULT '',
  override_reason text NOT NULL DEFAULT '',
  decided_by uuid,
  decided_at timestamptz,

  note text NOT NULL DEFAULT '',
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, plan_no),

  -- 🔴 挂了试产单就得有试产单编号的快照（只挂一个裸 id，界面上显示什么？）。
  CONSTRAINT mass_production_plan_trial_snapshot CHECK(btrim(trial_no) <> ''),
  -- 🔴 BOM 版本也必须带版本号与 BOM 编码快照。
  CONSTRAINT mass_production_plan_bom_snapshot CHECK(
    btrim(bom_version_no) <> '' AND btrim(bom_code) <> ''
  ),
  -- 🔴 目标成本要么没有（取不到），要么是正数 —— 0 元的目标成本不是目标。
  CONSTRAINT mass_production_plan_target_cost CHECK(target_cost IS NULL OR target_cost > 0),
  -- 🔴 决策信息与决策状态必须同时在/不在：没决策就不许有决策字段。
  CONSTRAINT mass_production_plan_decision_pair CHECK(
    (decision = '' AND decision_source = '' AND decided_by IS NULL)
    OR (decision IN ('GO','HOLD') AND decision_source IN ('TRIAL_RECOMMENDATION','MANUAL_OVERRIDE')
        AND decided_by IS NOT NULL AND decided_at IS NOT NULL)
  )
);

COMMENT ON TABLE mass_production_plan IS
  '6.6.2.2 量产计划（头）：试产通过后转正式量产的决策、BOM 定版、批量与目标成本';

-- ===========================================================================
-- 2. 状态迁移事件（append-only）
-- ===========================================================================
CREATE TABLE mass_production_plan_transition (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  plan_id uuid NOT NULL
      REFERENCES mass_production_plan(id) ON DELETE CASCADE,
  from_status varchar(24) NOT NULL
    CHECK(from_status IN ('DRAFT','DECIDED','BOM_FROZEN','SKU_FILED','RELEASED','CLOSED','CANCELLED')),
  to_status varchar(24) NOT NULL
    CHECK(to_status IN ('DRAFT','DECIDED','BOM_FROZEN','SKU_FILED','RELEASED','CLOSED','CANCELLED')),
  reason text NOT NULL DEFAULT '',
  actor_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL,
  -- applied_at：这条事件**是否**已经被对应的状态变更消费掉（防重复套用同一条事件）。
  applied_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, plan_id, from_status, to_status, occurred_at)
);

COMMENT ON TABLE mass_production_plan_transition IS
  '6.6.2.2 量产计划状态迁移事件（append-only 留痕：谁、何时、从哪到哪、为什么）';

-- ===========================================================================
-- 3. 量产 BOM 组件行快照（定版那一刻从 catalog 原样搬过来）
-- ===========================================================================
CREATE TABLE mass_production_bom_line (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  plan_id uuid NOT NULL
      REFERENCES mass_production_plan(id) ON DELETE CASCADE,
  line_no integer NOT NULL CHECK(line_no > 0),
  -- 🔴 跨服务只存**逻辑引用 + 快照**，零跨库 FK。
  sku_id uuid NOT NULL,
  sku_code varchar(64) NOT NULL CHECK(btrim(sku_code) <> ''),
  sku_name varchar(200) NOT NULL DEFAULT '',
  -- 🔴 三元组原样搬运 catalog 的 R6/R7，本服务**不重算、不取整**。
  unit_quantity numeric(19,6) NOT NULL CHECK(unit_quantity > 0),
  scrap_rate numeric(19,6) NOT NULL DEFAULT 0 CHECK(scrap_rate >= 0),
  required_quantity numeric(19,6) NOT NULL CHECK(required_quantity > 0),
  unit varchar(32) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, plan_id, line_no)
);

COMMENT ON TABLE mass_production_bom_line IS
  '6.6.2.2 量产 BOM 组件行快照：定版时从 catalog 的 R6/R7 原样搬运，本服务不重算';

-- ===========================================================================
-- 4. 正式 SKU 建档请求 / 待办事实
-- ===========================================================================
CREATE TABLE mass_production_sku_filing (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  filing_no varchar(64) NOT NULL CHECK(btrim(filing_no) <> ''),
  plan_id uuid NOT NULL
      REFERENCES mass_production_plan(id) ON DELETE CASCADE,

  -- ---------------------------------------------------------------------
  -- 目标系统与目标键
  -- ---------------------------------------------------------------------
  -- 🔴 建档的**写入所有权在 catalog**（3.1.2.1 / CatalogController）。
  --   本模块只记「要让它建什么、在它那里建在谁下面」。
  target_system varchar(32) NOT NULL CHECK(target_system = 'catalog'),
  target_endpoint varchar(128) NOT NULL,
  -- 目标键：SKU 建在哪个 SPU 下面（catalog 独立库 ⇒ 逻辑引用 + 编码快照）。
  target_spu_id uuid NOT NULL,
  target_spu_code varchar(64) NOT NULL DEFAULT '',
  target_spu_name varchar(200) NOT NULL DEFAULT '',

  -- ---------------------------------------------------------------------
  -- 载荷（编码 / 条码 / 基础单位 / 名称 / 状态）
  -- ---------------------------------------------------------------------
  sku_code varchar(64) NOT NULL CHECK(btrim(sku_code) <> ''),
  sku_name varchar(200) NOT NULL CHECK(btrim(sku_name) <> ''),
  barcode varchar(128) NOT NULL CHECK(btrim(barcode) <> ''),
  base_unit varchar(32) NOT NULL CHECK(btrim(base_unit) <> ''),
  sku_status varchar(16) NOT NULL DEFAULT 'DRAFT'
    CHECK(sku_status IN ('DRAFT','ACTIVE','INACTIVE')),

  -- ---------------------------------------------------------------------
  -- 报关属性（catalog 的 CustomsInput 标量部分）
  --   🔴 **可以没有**：没有报关属性时建档照样成立（catalog 那边报关属性可后补）。
  --   🔴 有 hs_code 就必须把其余报关标量也带齐（下面的 CHECK 钉死）。
  -- ---------------------------------------------------------------------
  customs_hs_code varchar(16) NOT NULL DEFAULT '',
  customs_origin_country_code varchar(2) NOT NULL DEFAULT '',
  customs_declared_name varchar(200) NOT NULL DEFAULT '',
  customs_declared_value_reference numeric(19,4),
  customs_statutory_unit_code varchar(8) NOT NULL DEFAULT '',
  customs_preferential_origin_eligible boolean NOT NULL DEFAULT false,
  customs_preference_agreement varchar(32) NOT NULL DEFAULT '',
  customs_note varchar(1000) NOT NULL DEFAULT '',

  -- ---------------------------------------------------------------------
  -- 幂等键 + 状态机
  -- ---------------------------------------------------------------------
  -- 🔴 catalog 的建档端点**不接受幂等键** ⇒ 本模块自己扛「重复提交建出两个 SKU」。
  --   租户内唯一；重复提交同一条请求只会命中同一条建档事实。
  idempotency_key varchar(64) NOT NULL CHECK(btrim(idempotency_key) <> ''),
  --   PENDING   待提交
  --   SUBMITTED 已提交（catalog 已受理，结果未知）
  --   CONFIRMED 已建成（catalog 返回了 SKU id）
  --   PARTIAL   部分完成（SKU 建成了，但报关属性没写进去）
  --   FAILED    失败（SKU 没建成；可重试）
  --   UNKNOWN   **结果未知**（超时/断连，无法判断建没建 ⇒ 禁止盲目重试）
  status varchar(16) NOT NULL DEFAULT 'PENDING'
    CHECK(status IN ('PENDING','SUBMITTED','CONFIRMED','PARTIAL','FAILED','UNKNOWN')),
  target_sku_id uuid,
  target_sku_code varchar(64) NOT NULL DEFAULT '',
  error_message varchar(1000) NOT NULL DEFAULT '',
  submitted_at timestamptz,
  confirmed_at timestamptz,
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, filing_no),
  -- 🔴 一张量产计划**只允许一条**建档请求：两条建档请求 = 两个 SKU。
  UNIQUE(tenant_id, plan_id),
  UNIQUE(tenant_id, idempotency_key),
  -- 🔴 已建成 ⇒ 必须有 catalog 返回的 SKU id（不能凭空说建成了）。
  CONSTRAINT mass_production_sku_filing_confirmed CHECK(
    status <> 'CONFIRMED' OR target_sku_id IS NOT NULL
  ),
  -- 🔴 没有报关属性时，HS 编码等标量必须全空（避免半截报关属性）。
  CONSTRAINT mass_production_sku_filing_customs_shape CHECK(
    (customs_hs_code = '' AND customs_origin_country_code = '' AND customs_declared_name = ''
      AND customs_statutory_unit_code = '' AND customs_declared_value_reference IS NULL
      AND customs_preference_agreement = '' AND NOT customs_preferential_origin_eligible)
    OR (btrim(customs_hs_code) <> '' AND btrim(customs_origin_country_code) <> ''
        AND btrim(customs_declared_name) <> '' AND btrim(customs_statutory_unit_code) <> '')
  )
);

COMMENT ON TABLE mass_production_sku_filing IS
  '6.6.2.2 正式 SKU 建档请求/待办事实：目标系统 + 目标键 + 载荷 + 幂等键 + 状态机；'
  '写入所有权在 catalog，本模块不跨库建 SKU';

-- ===========================================================================
-- 5. 建档载荷的属性行（catalog 的 ValueInput）
-- ===========================================================================
CREATE TABLE mass_production_sku_filing_attribute (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  filing_id uuid NOT NULL
      REFERENCES mass_production_sku_filing(id) ON DELETE CASCADE,
  line_no integer NOT NULL CHECK(line_no > 0),
  -- catalog 的属性 id（**逻辑引用**：catalog 独立库，零跨库 FK）。
  attribute_id uuid NOT NULL,
  attribute_code varchar(64) NOT NULL DEFAULT '',
  attribute_name varchar(200) NOT NULL DEFAULT '',
  -- 覆写状态：SET 设置 / CLEAR 清空 / INHERIT 继承（与 catalog 的三种取值逐字对齐）。
  value_state varchar(16) NOT NULL CHECK(value_state IN ('SET','CLEAR','INHERIT')),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, filing_id, line_no),
  UNIQUE(tenant_id, filing_id, attribute_id)
);

COMMENT ON TABLE mass_production_sku_filing_attribute IS
  '6.6.2.2 建档载荷的属性行：编码/条码/属性里的「属性」部分，值在 attribute_value 行上';

-- ===========================================================================
-- 6. 属性取值行（一行一个值，与 catalog 的 attribute_value_item 同构）
-- ===========================================================================
CREATE TABLE mass_production_sku_filing_attribute_value (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  attribute_row_id uuid NOT NULL
      REFERENCES mass_production_sku_filing_attribute(id) ON DELETE CASCADE,
  value_ordinal integer NOT NULL CHECK(value_ordinal > 0),
  value_text varchar(2000) NOT NULL CHECK(btrim(value_text) <> ''),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, attribute_row_id, value_ordinal)
);

COMMENT ON TABLE mass_production_sku_filing_attribute_value IS
  '6.6.2.2 建档属性取值行（多值属性一行一个值）';

-- ===========================================================================
-- 7. 报关申报要素行（catalog 的 DeclarationElementInput）
-- ===========================================================================
CREATE TABLE mass_production_customs_element (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  filing_id uuid NOT NULL
      REFERENCES mass_production_sku_filing(id) ON DELETE CASCADE,
  ordinal integer NOT NULL CHECK(ordinal > 0),
  element_code varchar(32) NOT NULL CHECK(btrim(element_code) <> ''),
  element_name varchar(200) NOT NULL CHECK(btrim(element_name) <> ''),
  element_value varchar(2000) NOT NULL DEFAULT '',
  required boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, filing_id, ordinal)
);

COMMENT ON TABLE mass_production_customs_element IS
  '6.6.2.2 建档载荷的报关申报要素行（位次从 1 起，required 为空与「已填」是两件事）';

-- ===========================================================================
-- 8. 下游交接留痕（6.2.1.2 采购需求 / 5.3.1.1 价格模型）
-- ===========================================================================
CREATE TABLE mass_production_downstream_handoff (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  plan_id uuid NOT NULL
      REFERENCES mass_production_plan(id) ON DELETE CASCADE,
  -- ---- 目标模块与它自己的写入入口 ----
  -- 6.2.1.2 = POST /trade/replenishment-suggestions/batches（重算触发）
  -- 5.3.1.1 = 目标定价（**未开发**，无入口）
  target_module varchar(16) NOT NULL CHECK(target_module IN ('6.2.1.2','5.3.1.1')),
  target_endpoint varchar(128) NOT NULL,
  owner_module varchar(32) NOT NULL,
  -- 🔴 available=false ⇒ 本模块**没有**把量产参数写进去（写入口的语义对不上），如实标出来。
  available boolean NOT NULL,
  -- 🔴 mass_production_input_applied：量产参数**是否**真的作为参数进了那次重算。
  --   6.2.1.2 = false（它的起订额/交期是人工策略，本模块不顶掉它）
  --   5.3.1.1 = false（模块未开发）
  mass_production_input_applied boolean NOT NULL DEFAULT false,
  status varchar(16) NOT NULL CHECK(status IN ('SUCCEEDED','FAILED','SKIPPED')),
  result_ref varchar(64) NOT NULL DEFAULT '',
  result_summary varchar(500) NOT NULL DEFAULT '',
  error_message varchar(1000) NOT NULL DEFAULT '',
  impact varchar(500) NOT NULL DEFAULT '',
  attempted_at timestamptz NOT NULL DEFAULT now(),
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id)
);

COMMENT ON TABLE mass_production_downstream_handoff IS
  '6.6.2.2 下游交接留痕：量产参数交给 6.2.1.2 采购需求与 5.3.1.1 价格模型的真实结果';

-- ===========================================================================
-- 9. 数据库层守卫（与 Java 侧 MassProductionRules 逐条同构）
-- ===========================================================================
-- 9.1 🔴 **状态机守卫**：合法边 + 「必须有对应迁移事件」+ 三条前置条件。
CREATE FUNCTION guard_mass_production_plan_status() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_allowed boolean;
  v_event_id uuid;
  v_lines int;
  v_confirmed int;
BEGIN
  IF NEW.status IS NOT DISTINCT FROM OLD.status THEN
    RETURN NEW;
  END IF;

  v_allowed := CASE OLD.status
    WHEN 'DRAFT'      THEN NEW.status IN ('DECIDED','CANCELLED')
    WHEN 'DECIDED'    THEN NEW.status IN ('BOM_FROZEN','CANCELLED')
    WHEN 'BOM_FROZEN' THEN NEW.status IN ('SKU_FILED','CANCELLED')
    WHEN 'SKU_FILED'  THEN NEW.status IN ('RELEASED','CANCELLED')
    WHEN 'RELEASED'   THEN NEW.status = 'CLOSED'
    ELSE false
  END;
  IF NOT v_allowed THEN
    RAISE EXCEPTION '量产计划状态不允许从 % 迁移到 %（已关闭 / 已取消是终态，不提供恢复）', OLD.status, NEW.status
      USING ERRCODE='23514';
  END IF;

  SELECT t.id INTO v_event_id
    FROM mass_production_plan_transition t
   WHERE t.tenant_id = NEW.tenant_id
     AND t.plan_id = NEW.id
     AND t.from_status = OLD.status
     AND t.to_status = NEW.status
     AND t.applied_at IS NULL
   ORDER BY t.created_at DESC, t.id DESC
   LIMIT 1;
  IF NOT FOUND THEN
    RAISE EXCEPTION '量产计划状态变更缺少对应迁移事件（% → %）：每次状态迁移都必须落一条不可改写的记录', OLD.status, NEW.status
      USING ERRCODE='23514';
  END IF;

  -- 进入「BOM 已定版」：组件行快照必须非空（定了一个空版本等于没定）。
  IF NEW.status = 'BOM_FROZEN' THEN
    SELECT count(*) INTO v_lines
      FROM mass_production_bom_line l
     WHERE l.tenant_id = NEW.tenant_id
       AND l.plan_id = NEW.id;
    IF v_lines = 0 THEN
      RAISE EXCEPTION '量产 BOM 还没有定版（组件行快照为空），不能标记为「BOM 已定版」'
        USING ERRCODE='23514';
    END IF;
  END IF;

  -- 进入「SKU 已建档」：必须有一条**已确认**的建档请求。
  IF NEW.status = 'SKU_FILED' THEN
    SELECT count(*) INTO v_confirmed
      FROM mass_production_sku_filing f
     WHERE f.tenant_id = NEW.tenant_id
       AND f.plan_id = NEW.id
       AND f.status = 'CONFIRMED';
    IF v_confirmed = 0 THEN
      RAISE EXCEPTION '还没有已确认的 SKU 建档请求，不能标记为「SKU 已建档」：建档请求只是交出去，确认才是建成了'
        USING ERRCODE='23514';
    END IF;
  END IF;

  -- 进入「已下达」：必须已经定版。
  IF NEW.status = 'RELEASED' THEN
    SELECT count(*) INTO v_lines
      FROM mass_production_bom_line l
     WHERE l.tenant_id = NEW.tenant_id
       AND l.plan_id = NEW.id;
    IF v_lines = 0 THEN
      RAISE EXCEPTION '量产 BOM 还没定版，不能下达：没有定版就下达，等于按一份会变的清单去采购'
        USING ERRCODE='23514';
    END IF;
  END IF;

  UPDATE mass_production_plan_transition SET applied_at = now() WHERE id = v_event_id;
  RETURN NEW;
END $$;
CREATE TRIGGER mass_production_plan_status_guard BEFORE UPDATE ON mass_production_plan
  FOR EACH ROW EXECUTE FUNCTION guard_mass_production_plan_status();

-- 9.2 🔴 **决策留痕不可改写**：决策（谁、何时、结论、理由、覆盖理由）一旦落库就不可改。
CREATE FUNCTION guard_mass_production_plan_decision_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.decision <> '' AND (
       NEW.decision IS DISTINCT FROM OLD.decision
    OR NEW.decision_source IS DISTINCT FROM OLD.decision_source
    OR NEW.decision_rationale IS DISTINCT FROM OLD.decision_rationale
    OR NEW.override_reason IS DISTINCT FROM OLD.override_reason
    OR NEW.decided_by IS DISTINCT FROM OLD.decided_by
    OR NEW.decided_at IS DISTINCT FROM OLD.decided_at) THEN
    RAISE EXCEPTION '量产决策不可改写（%）：「当时凭什么决定量产」是举证凭据', OLD.id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER mass_production_plan_decision_immutable BEFORE UPDATE ON mass_production_plan
  FOR EACH ROW EXECUTE FUNCTION guard_mass_production_plan_decision_immutable();

-- 9.3 🔴 **建档请求不可删**：删掉它就等于抹掉「当时要把这个 SKU 建到 catalog 去」这件事。
CREATE FUNCTION guard_mass_production_sku_filing_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'SKU 建档请求不可删除（%）：建档事实是「已交给商品中心建」的举证', OLD.id
      USING ERRCODE='23514';
  END IF;
  IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
     OR NEW.filing_no IS DISTINCT FROM OLD.filing_no
     OR NEW.plan_id IS DISTINCT FROM OLD.plan_id
     OR NEW.target_system IS DISTINCT FROM OLD.target_system
     OR NEW.target_endpoint IS DISTINCT FROM OLD.target_endpoint
     OR NEW.target_spu_id IS DISTINCT FROM OLD.target_spu_id
     OR NEW.sku_code IS DISTINCT FROM OLD.sku_code
     OR NEW.sku_name IS DISTINCT FROM OLD.sku_name
     OR NEW.barcode IS DISTINCT FROM OLD.barcode
     OR NEW.base_unit IS DISTINCT FROM OLD.base_unit
     OR NEW.sku_status IS DISTINCT FROM OLD.sku_status
     OR NEW.customs_hs_code IS DISTINCT FROM OLD.customs_hs_code
     OR NEW.customs_origin_country_code IS DISTINCT FROM OLD.customs_origin_country_code
     OR NEW.customs_declared_name IS DISTINCT FROM OLD.customs_declared_name
     OR NEW.customs_declared_value_reference IS DISTINCT FROM OLD.customs_declared_value_reference
     OR NEW.customs_statutory_unit_code IS DISTINCT FROM OLD.customs_statutory_unit_code
     OR NEW.customs_preferential_origin_eligible IS DISTINCT FROM OLD.customs_preferential_origin_eligible
     OR NEW.customs_preference_agreement IS DISTINCT FROM OLD.customs_preference_agreement
     OR NEW.customs_note IS DISTINCT FROM OLD.customs_note
     OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
     OR NEW.created_by IS DISTINCT FROM OLD.created_by
     OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION 'SKU 建档请求不可改写（%）：载荷与幂等键一经提交就不能改', OLD.id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER mass_production_sku_filing_immutable
  BEFORE UPDATE OR DELETE ON mass_production_sku_filing
  FOR EACH ROW EXECUTE FUNCTION guard_mass_production_sku_filing_immutable();

-- 9.4 🔴 **迁移事件不可改写**。
CREATE FUNCTION guard_mass_production_transition_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION '量产计划状态迁移事件不可删除（%）', OLD.id USING ERRCODE='23514';
  END IF;
  IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
     OR NEW.plan_id IS DISTINCT FROM OLD.plan_id
     OR NEW.from_status IS DISTINCT FROM OLD.from_status
     OR NEW.to_status IS DISTINCT FROM OLD.to_status
     OR NEW.reason IS DISTINCT FROM OLD.reason
     OR NEW.actor_id IS DISTINCT FROM OLD.actor_id
     OR NEW.occurred_at IS DISTINCT FROM OLD.occurred_at
     OR NEW.created_at IS DISTINCT FROM OLD.created_at
     OR (OLD.applied_at IS NOT NULL AND NEW.applied_at IS DISTINCT FROM OLD.applied_at) THEN
    RAISE EXCEPTION '量产计划状态迁移事件不可改写（%）', OLD.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER mass_production_transition_immutable
  BEFORE UPDATE OR DELETE ON mass_production_plan_transition
  FOR EACH ROW EXECUTE FUNCTION guard_mass_production_transition_immutable();

-- ===========================================================================
-- 10. 索引（按真实查询形状建，不多建）
-- ===========================================================================
-- 量产计划清单页：按状态 + 创建时刻读（清单页主查询形状）。
CREATE INDEX mass_production_plan_by_status
  ON mass_production_plan(tenant_id, status, created_at desc, id);
-- 量产计划清单页：按上游试产单反查。
CREATE INDEX mass_production_plan_by_trial
  ON mass_production_plan(tenant_id, trial_order_id, created_at desc, id);
-- 迁移事件：按计划 + 发生时刻读（详情页时间线）。
CREATE INDEX mass_production_transition_by_plan
  ON mass_production_plan_transition(tenant_id, plan_id, occurred_at desc, id);
-- BOM 组件行：按计划 + 行号读。
CREATE INDEX mass_production_bom_line_by_plan
  ON mass_production_bom_line(tenant_id, plan_id, line_no, id);
-- 建档请求：按状态读（「还有多少没建档」）。
CREATE INDEX mass_production_sku_filing_by_status
  ON mass_production_sku_filing(tenant_id, status, created_at desc, id);
-- 建档属性行：按建档请求 + 行号读。
CREATE INDEX mass_production_filing_attribute_by_filing
  ON mass_production_sku_filing_attribute(tenant_id, filing_id, line_no, id);
-- 报关要素：按建档请求 + 位次读。
CREATE INDEX mass_production_customs_element_by_filing
  ON mass_production_customs_element(tenant_id, filing_id, ordinal, id);
-- 下游交接留痕：按计划 + 目标模块读。
CREATE INDEX mass_production_handoff_by_plan
  ON mass_production_downstream_handoff(tenant_id, plan_id, target_module, attempted_at desc, id);

-- ===========================================================================
-- 11. 多租户：RLS + 授权（与 trade V1 / V92 / V257 / V261 / V264 同一套写法）
-- ===========================================================================
-- 八张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'mass_production_plan','mass_production_plan_transition','mass_production_bom_line',
    'mass_production_sku_filing','mass_production_sku_filing_attribute',
    'mass_production_sku_filing_attribute_value','mass_production_customs_element',
    'mass_production_downstream_handoff'
  ] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  mass_production_plan, mass_production_plan_transition, mass_production_bom_line,
  mass_production_sku_filing, mass_production_sku_filing_attribute,
  mass_production_sku_filing_attribute_value, mass_production_customs_element,
  mass_production_downstream_handoff TO trade_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  mass_production_plan, mass_production_plan_transition, mass_production_bom_line,
  mass_production_sku_filing, mass_production_sku_filing_attribute,
  mass_production_sku_filing_attribute_value, mass_production_customs_element,
  mass_production_downstream_handoff TO starcon_app;
-- 留痕表**不可删**：删掉它就等于抹掉「当时为什么这么转」与「当时把什么交给了下游」的证据。
REVOKE DELETE ON mass_production_plan_transition FROM trade_app;
REVOKE DELETE ON mass_production_sku_filing FROM trade_app;
REVOKE DELETE ON mass_production_downstream_handoff FROM trade_app;
-- 量产计划**不提供 DELETE**：它挂着 BOM 快照、建档请求与全部留痕，
-- 删除会抹掉「这个量产经历过什么」的证据。
REVOKE DELETE ON mass_production_plan FROM trade_app;

-- ---------------------------------------------------------------------------
-- 12. 本迁移的回归面（跨车道纪律）
-- ---------------------------------------------------------------------------
--   * **不 ALTER 任何既有表**：V1 / V92 / V233 / V257 / V261 / V264 的表一个字节都不碰，
--     因此既没有 ADD COLUMN / SET NOT NULL，也没有既有表触发器被新约束覆盖 ——
--     跨车道 DDL 回归面 = 0，不牵动任何已交付模块的既有集成测试。
--   * **不写别的模块的表**：本文件只建自己的八张表；
--     6.6.2.1（production_trial_*）只被**只读引用 + 快照**，一个字节都不碰；
--     6.2.1.2（replenishment_*）只被**触发重算**，一个字节都不碰；
--     catalog 的 bom_version / sku / spu / attribute **不碰也不建跨库外键**（那是它们的写入所有权）。
--   * **不建 SKU 表**：正式 SKU 由 catalog 建（3.1.2.1 / CatalogController），
--     本模块只落**建档请求事实**并在结果里如实读回 catalog 返回的 SKU id。
