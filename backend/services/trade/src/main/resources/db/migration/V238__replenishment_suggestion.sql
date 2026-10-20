-- 6.2.1.2 补货建议：计算建议补货量（目标库存覆盖天数 × 日均销量 −（可用 + 在途）），
-- 并结合 MOQ、箱规、采购起订额做取整修正，给出建议供应商与到货时间。
--
-- 蓝图 06-purchase-and-supply.md 6.2.1.2 原文：
--   职责：计算建议补货量：目标库存覆盖天数 × 日均销量 −（可用 + 在途），并结合 MOQ、箱规、
--         采购起订额做取整修正，给出建议供应商与到货时间。
--   输入：销量预测（含近期增长率）；覆盖天数目标（可按 SKU/分类配置）；MOQ 与箱规（3.6.2）；在途量。
--   输出：补货建议（建议量、取整说明、预计到货日、预计费用）；支持接受/调整/忽略。
--   关系：上游 = 6.2.1.1、3.6.2 报价、10 头程时效；下游 = 6.2.1.2 → 6.2.2.1 采购申请。
--         **建议必须展示计算过程**（销量怎么来的、扣了哪些在途），用户不信任的预测不会被采纳。
--
-- 归属：**trade 服务**（蓝图 README 系统地图 06 行「后端主责服务：trade + catalog（party）」）。
-- 前端路由 /purchase/demand/replenishment 是**前端**路由；后端命名空间按服务走
-- （与 6.2.1.1 的 /trade/stockout-risk、9.3.2.2 的 /trade/fulfillment/routing 同一纪律）。
--
-- ===========================================================================
-- 一、模块边界：本文件**不碰 6.2.1.1 的任何一张表**
-- ===========================================================================
--   * **日均销量**读 6.2.1.1 的 `stockout_sales_velocity`（销售速度登记）。那张表是
--     6.2.1.1 的**写入所有者**：本文件只 SELECT，一个字节都不改。
--   * **可用量与在途量**经 trade 的 `StockoutInventoryPort` 读 warehouse 的授权只读接口
--     （7.1.1.3 可售量 / 7.5.1.2 在途腿）。warehouse 的库存账是它的写入所有者，
--     本文件不建任何跨库外键、不建跨库视图、不跨服务直查对方的表。
--   * **MOQ / 采购单位 / 交期 / 阶梯价**读 catalog 的 3.6.2.1 报价（经
--     `ReplenishmentCatalogPort`）。**箱规**（1 个采购单位含多少基础单位）读 catalog
--     3.1.2.4 的 `sku_unit_conversion`（role='PURCHASE' 的 quantity_per_base），
--     同样只经授权只读接口。
--
-- ===========================================================================
-- 二、四张业务表的职责划分
-- ===========================================================================
--   1. `replenishment_coverage_policy` —— **覆盖天数目标**（可按 SKU / 分类 / 租户默认配置）。
--      蓝图输入句明写「覆盖天数目标（可按 SKU/分类配置）」，所以它是一张**带生效期的
--      配置表**而不是计算时的参数：配置本身要能追溯（3 月 1 日那天用的是 30 天还是 45 天）。
--      取值优先级固定为 **SKU > 分类 > 租户默认**；租户默认允许在计算时由调用方显式传入
--      （`ReplenishmentComputeInput.defaultCoverageDays`），与 6.2.1.1 的
--      `leadTimeDays` 同一纪律：调用方给的是「兜底」，配置表给的是「覆盖」。
--   2. `replenishment_order_policy` —— **采购起订额 + 生产周期**。
--      「采购起订额」在 catalog 的 3.6.2 报价里**不存在**（报价只有 MOQ（数量）、
--      交期、币种、阶梯价），而它本质上是**采购侧的门槛规则**，不是商品主数据 ⇒ 归本服务。
--      「生产周期」同理：报价的 `lead_time_days` 是「下单到到货的承诺天数」（供应商交期），
--      生产周期是它之外的额外时间，两者在 6.2.1.1 的输入句里是**并列**的两项。
--   3. `replenishment_suggestion_batch` —— **一次计算 = 一份批次**。与 6.2.1.1 的快照
--      同一纪律：结论要能被回溯复算，就不能只在内存里算一遍。
--   4. `replenishment_suggestion` —— **一条建议（逐 SKU 的结论）**。
--   5. `replenishment_suggestion_step` —— **计算过程逐项拆解**（蓝图加粗句「建议必须展示
--      计算过程」）。没有它，用户就只能看到一个数字，而「用户不信任的预测不会被采纳」。
--   6. `replenishment_suggestion_decision` —— **处置留痕**（接受 / 调整 / 忽略）。
--
-- ===========================================================================
-- 三、为什么「无数据」与「不需要补」是两个状态（不折叠成一个 0）
-- ===========================================================================
--   * `NO_SALES_DATA` —— 没有销售速度登记 ⇒ 算不出日均销量 ⇒ **答不出来**。
--     这不是「不紧急」，是一个必须有人去补数据的洞。
--   * `HOLD` —— 算得出来，且库存（含在途）已经覆盖目标库存 ⇒ **不需要补货**。
--   * `NO_QUOTE` —— 算得出净需求量，但没有有效报价 ⇒ 取不到 MOQ / 箱规 / 交期 / 价格，
--     **给不出可下单的数量**。此时净需求量照实给（运营知道要补多少），
--     但建议量是 null（不是 0，也不是猜一个数）。
--   * `REPLENISH` —— 算得出来、有有效报价、净需求量 > 0 ⇒ 真正的建议。
-- 把 NO_SALES_DATA 折叠成 HOLD（0），会让「没登记数据」被读成「库存充足」——
-- 那正是 6.2.1.1 反复强调要防的那类误判。
--
-- ===========================================================================
-- 四、单位纪律（本模块最容易出错的地方）
-- ===========================================================================
--   * **基础单位（base unit）**：SKU 自身的单位。日均销量、可用量、在途量、目标库存量、
--     净需求量都**在基础单位**里（它们都来自 warehouse 的 SKU 级口径）。
--   * **采购单位（purchase unit）**：报价的 `purchase_unit_code`（工厂按箱报、我们按件收）。
--     **MOQ 与阶梯起订量在采购单位里**（catalog 的 `applicableTier` 就是拿数量与
--     `minQuantity` 同单位比较的 ⇒ 两者必然同单位）。
--   * **箱规** = 「1 个采购单位含多少基础单位」= catalog `sku_unit_conversion`
--     (role='PURCHASE') 的 `quantity_per_base`（1 箱 = 12 件 ⇒ 12）。
--     采购单位就是基础单位时箱规 = 1（catalog 里没有换算行）。
--   * **金额**：`estimated_cost = 建议采购单位数 × 适用阶梯单价`，币种取报价的币种。
--     **绝不跨币种比价**（12 财务才有汇率表），所以供应商择优不拿价格当排序键。
--
-- ===========================================================================
-- 1. 覆盖天数目标配置（SKU / 分类 / 租户默认）
-- ===========================================================================
CREATE TABLE replenishment_coverage_policy (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- 作用范围：DEFAULT 租户默认 / SKU 单个 SKU / CATEGORY 某个分类。
  scope text NOT NULL CHECK(scope IN ('DEFAULT','SKU','CATEGORY')),
  -- SKU（scope='SKU' 时必填）。catalog 拥有 SKU，跨服务不建外键。
  sku_id uuid,
  -- 分类编码（scope='CATEGORY' 时必填）。catalog 的分类字典，同样不建外键。
  category_code varchar(64),
  -- 覆盖天数（天）。上限 365 挡住「把月填成天」。
  coverage_days int NOT NULL CHECK(coverage_days > 0 AND coverage_days <= 365),
  -- **生效期**（区间语义 [effective_from, effective_to)）。effective_to 为 NULL = 当前生效。
  effective_from date NOT NULL,
  effective_to date,
  note text NOT NULL DEFAULT '',
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 范围与它的两个定位列必须自洽：写了 sku_id 就必须是 SKU 范围，
  -- 否则「这条到底是给哪个 SKU 的」永远答不回来。
  CONSTRAINT replenishment_coverage_scope_consistent CHECK(
    (scope = 'SKU' AND sku_id IS NOT NULL AND category_code IS NULL)
    OR (scope = 'CATEGORY' AND sku_id IS NULL AND category_code IS NOT NULL)
    OR (scope = 'DEFAULT' AND sku_id IS NULL AND category_code IS NULL)
  ),
  CONSTRAINT replenishment_coverage_effective_ordered
    CHECK(effective_to IS NULL OR effective_to >= effective_from)
);
-- **同一作用范围同时只允许一条未收口的配置**：否则「这个 SKU 现在的覆盖天数」有两个答案，
-- 而它是整个模块的地基（与 6.2.1.1 的 `stockout_sales_velocity_open_uq` 同一纪律）。
CREATE UNIQUE INDEX replenishment_coverage_sku_open_uq
  ON replenishment_coverage_policy(tenant_id, sku_id) WHERE scope = 'SKU' AND effective_to IS NULL;
CREATE UNIQUE INDEX replenishment_coverage_category_open_uq
  ON replenishment_coverage_policy(tenant_id, category_code) WHERE scope = 'CATEGORY' AND effective_to IS NULL;
CREATE UNIQUE INDEX replenishment_coverage_default_open_uq
  ON replenishment_coverage_policy(tenant_id) WHERE scope = 'DEFAULT' AND effective_to IS NULL;

-- ===========================================================================
-- 2. 采购起订额 + 生产周期（采购侧门槛规则，catalog 没有这两个事实）
-- ===========================================================================
CREATE TABLE replenishment_order_policy (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- 供应商（catalog 的 party / 角色 id）。两者都为空 = 租户级默认。
  supplier_id uuid,
  supplier_role_id uuid,
  -- **采购起订额**（金额）。为空 = 不参与取整（不是 0 —— 0 会让每一单都「达标」）。
  min_order_amount numeric(19,4) CHECK(min_order_amount IS NULL OR min_order_amount > 0),
  -- 起订额的币种（ISO 4217 三位码）。为空 = 不校验（跨币种不得比较）。
  currency varchar(3) CHECK(currency IS NULL OR currency ~ '^[A-Z]{3}$'),
  -- **生产周期**（天）：供应商交期之外的额外生产时间。0 = 与报价交期同义（当日可发）。
  production_lead_time_days int
    CHECK(production_lead_time_days IS NULL OR (production_lead_time_days >= 0 AND production_lead_time_days <= 365)),
  effective_from date NOT NULL,
  effective_to date,
  note text NOT NULL DEFAULT '',
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 供应商两个 id 成对：只给一个就分不清说的是哪个供应商。
  CONSTRAINT replenishment_order_supplier_consistent CHECK(
    (supplier_id IS NULL) = (supplier_role_id IS NULL)
  ),
  -- 起订额与币种成对：只有金额没有币种的门槛是没法比对的。
  CONSTRAINT replenishment_order_amount_currency CHECK(
    (min_order_amount IS NULL) = (currency IS NULL)
  ),
  CONSTRAINT replenishment_order_effective_ordered
    CHECK(effective_to IS NULL OR effective_to >= effective_from)
);
CREATE UNIQUE INDEX replenishment_order_supplier_open_uq
  ON replenishment_order_policy(tenant_id, supplier_id, supplier_role_id)
  WHERE supplier_id IS NOT NULL AND effective_to IS NULL;
CREATE UNIQUE INDEX replenishment_order_default_open_uq
  ON replenishment_order_policy(tenant_id)
  WHERE supplier_id IS NULL AND effective_to IS NULL;

-- ===========================================================================
-- 3. 计算批次（一次计算 = 一份批次）
-- ===========================================================================
CREATE TABLE replenishment_suggestion_batch (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  -- 批次号：租户内唯一、单调可读（RS-20261021-0001）。
  batch_no varchar(64) NOT NULL CHECK(btrim(batch_no) <> ''),
  -- **评估基准日**：本次计算「站在哪一天看」。可显式指定以便回溯复算。
  as_of_date date NOT NULL,
  -- **计算时刻**：这一次计算真正跑完的时刻。
  computed_at timestamptz NOT NULL,
  -- **落库时刻**：与 computed_at 分开 —— 回溯算出来的历史结论不该看起来像是当时算的。
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  -- 本次用的**兜底覆盖天数**（调用方给的租户默认）。
  default_coverage_days int NOT NULL CHECK(default_coverage_days > 0),
  item_count int NOT NULL DEFAULT 0 CHECK(item_count >= 0),
  replenish_count int NOT NULL DEFAULT 0 CHECK(replenish_count >= 0),
  hold_count int NOT NULL DEFAULT 0 CHECK(hold_count >= 0),
  no_sales_data_count int NOT NULL DEFAULT 0 CHECK(no_sales_data_count >= 0),
  no_quote_count int NOT NULL DEFAULT 0 CHECK(no_quote_count >= 0),
  -- ⚠️ **刻意不存「已处置条数」这一列**：批次是 append-only 的留痕（见 7.2），
  -- 任何 UPDATE 都会被触发器挡下 —— 而「处置计数」恰恰要随处置变化。
  -- 所以它是**读时派生**的（见服务层的 count 子查询）：存一份就要同步一次，
  -- 漏同步一次就会出现「批次说处置了 2 条、点开只有 1 条」。
  -- 上游分页 / 目录查询被上限截断 ⇒ 本批次**不完整**，界面必须如实报出。
  truncated boolean NOT NULL DEFAULT false,
  created_by uuid NOT NULL,
  note text NOT NULL DEFAULT '',
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,batch_no)
);

-- ===========================================================================
-- 4. 补货建议（逐 SKU 的结论）
-- ===========================================================================
CREATE TABLE replenishment_suggestion (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  batch_id uuid NOT NULL,
  sku_id uuid NOT NULL,
  sku_code varchar(64) NOT NULL CHECK(btrim(sku_code) <> ''),
  sku_name varchar(200) NOT NULL DEFAULT '',
  -- ---- 销量侧（来自 6.2.1.1 的销售速度登记）----
  -- **日均销量**：没有登记时是 NULL（「无数据」），**绝不写 0**。
  daily_sales_quantity numeric(19,6),
  -- **近期增长率**：(当前日均 − 上一版本日均) ÷ 上一版本日均。只有一个版本时是 NULL。
  growth_rate numeric(19,6),
  previous_daily_sales_quantity numeric(19,6),
  velocity_window_start date,
  velocity_window_end date,
  velocity_source_kind varchar(24),
  -- ---- 覆盖天数 ----
  coverage_days int NOT NULL CHECK(coverage_days > 0),
  -- 覆盖天数**来自哪一层**：SKU / CATEGORY / DEFAULT（调用方给的兜底）。
  coverage_source text NOT NULL CHECK(coverage_source IN ('SKU','CATEGORY','DEFAULT')),
  coverage_category_code varchar(64),
  -- ---- 目标库存与缺口（数量一律在基础单位）----
  -- **目标库存量可为空**：没有销售速度登记时它**算不出来**（不是 0 —— 0 会被读成
  -- 「库存充足」，那正是 6.2.1.1 反复强调要防的误判）。
  target_stock_quantity numeric(19,6) CHECK(target_stock_quantity IS NULL OR target_stock_quantity >= 0),
  available_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(available_quantity >= 0),
  in_transit_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(in_transit_quantity >= 0),
  -- **覆盖窗口内可到达的在途**：ETA 落在 [评估日, 评估日+覆盖天数] 内的量才计入。
  in_transit_arriving_quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(in_transit_arriving_quantity >= 0),
  -- **净需求量**（未取整）：max(0, 目标库存 − 可用 − 覆盖窗口内可到达在途)。
  -- 算不出（无销售速度）时是 NULL。
  raw_required_quantity numeric(19,6),
  -- ---- 取整（MOQ / 箱规 / 采购起订额）----
  -- MOQ（采购单位）。来自 3.6.2 报价；没有报价时是 NULL。
  moq_quantity numeric(19,6) CHECK(moq_quantity IS NULL OR moq_quantity > 0),
  -- 采购单位编码（报价的 purchase_unit_code）。
  purchase_unit_code varchar(32),
  -- **箱规**：1 个采购单位含多少基础单位。catalog 没登记换算比例时是 NULL
  -- （是「量不到」，不是「等于 1」—— 猜成 1 会把订量算少一箱）。
  box_spec_quantity numeric(19,6) CHECK(box_spec_quantity IS NULL OR box_spec_quantity > 0),
  -- **建议采购单位数**（取整后）。取整不了（无报价 / 箱规未知）时是 NULL。
  suggested_purchase_quantity numeric(19,6),
  -- **建议量**（基础单位）。这是运营真正会填进采购申请的那个数。
  suggested_quantity numeric(19,6),
  -- 取整带来的增量（建议量 − 净需求量）。
  rounding_adjustment_quantity numeric(19,6),
  -- **取整说明**：每一步为什么这么取，写成人能读的一句话。
  rounding_note text NOT NULL DEFAULT '',
  -- ---- 金额（报价口径，不跨币种）----
  min_order_amount numeric(19,4),
  currency varchar(3),
  -- 适用阶梯单价（每采购单位）。
  unit_price numeric(19,4),
  -- **预计费用** = 建议采购单位数 × 适用阶梯单价。
  estimated_cost numeric(19,4),
  -- ---- 供应商与到货 ----
  supplier_id uuid,
  supplier_name varchar(200) NOT NULL DEFAULT '',
  supplier_role_id uuid,
  supplier_priority int,
  -- **供应商择优的原文依据**（选了谁、为什么）。
  supplier_selection_basis text NOT NULL DEFAULT '',
  -- 供应商交期（报价的 lead_time_days）。
  lead_time_days int CHECK(lead_time_days IS NULL OR lead_time_days >= 0),
  -- 生产周期（采购侧配置）。未配置时是 NULL（=「没维护」，不是 0）。
  production_lead_time_days int CHECK(production_lead_time_days IS NULL OR production_lead_time_days >= 0),
  -- **预计到货日**：评估日 + 供应商交期 + 生产周期。
  expected_arrival_date date,
  -- 到货日的**推导依据原文**（含「头程时效未交付」这类必须说清的话）。
  arrival_basis text NOT NULL DEFAULT '',
  -- ---- 结论 ----
  -- 建议类别：REPLENISH 真建议 / HOLD 不需要补 / NO_SALES_DATA 缺日均销量 /
  -- NO_QUOTE 缺有效报价 / NO_BOX_SPEC 箱规量不到（换算不成可下单件数）。
  advice text NOT NULL CHECK(advice IN ('REPLENISH','HOLD','NO_SALES_DATA','NO_QUOTE','NO_BOX_SPEC')),
  -- 判定依据原文（清单页直接显示这行就能复现判定）。
  advice_basis text NOT NULL CHECK(btrim(advice_basis) <> ''),
  -- 处置状态：SUGGESTED 待处置 / ACCEPTED 已接受 / ADJUSTED 已调整 / IGNORED 已忽略。
  status text NOT NULL DEFAULT 'SUGGESTED'
    CHECK(status IN ('SUGGESTED','ACCEPTED','ADJUSTED','IGNORED')),
  -- 处置后真正下单的数量（基础单位）。ACCEPTED = 建议量；ADJUSTED = 人工改的量；IGNORED = NULL。
  decided_quantity numeric(19,6),
  decision_by uuid,
  decision_at timestamptz,
  decision_note text NOT NULL DEFAULT '',
  -- 排序位次：落库后可回溯「当时排第几」。
  sort_rank int NOT NULL CHECK(sort_rank >= 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一批次里一个 SKU 只有一行。
  UNIQUE(tenant_id,batch_id,sku_id),
  FOREIGN KEY(tenant_id,batch_id) REFERENCES replenishment_suggestion_batch(tenant_id,id) ON DELETE CASCADE,
  -- 处置状态与处置信息必须自洽：没处置过就没有处置人 / 处置时间。
  CONSTRAINT replenishment_suggestion_decision_consistent CHECK(
    (status = 'SUGGESTED' AND decision_by IS NULL AND decision_at IS NULL AND decided_quantity IS NULL)
    OR (status = 'ACCEPTED' AND decision_by IS NOT NULL AND decision_at IS NOT NULL
        AND decided_quantity IS NOT NULL)
    OR (status = 'ADJUSTED' AND decision_by IS NOT NULL AND decision_at IS NOT NULL
        AND decided_quantity IS NOT NULL AND decided_quantity > 0)
    OR (status = 'IGNORED' AND decision_by IS NOT NULL AND decision_at IS NOT NULL
        AND decided_quantity IS NULL)
  ),
  -- 建议量与建议类别必须自洽：只有 REPLENISH 才有建议量。
  CONSTRAINT replenishment_suggestion_advice_quantity CHECK(
    (advice = 'REPLENISH' AND suggested_quantity IS NOT NULL AND suggested_quantity > 0)
    OR (advice <> 'REPLENISH' AND suggested_quantity IS NULL AND suggested_purchase_quantity IS NULL)
  ),
  -- 覆盖窗口内可到达的在途不可能超过在途合计。
  CONSTRAINT replenishment_suggestion_in_transit_consistent
    CHECK(in_transit_arriving_quantity <= in_transit_quantity)
);

-- ===========================================================================
-- 5. 计算过程拆解（一行 = 算式里的一步）
-- ===========================================================================
CREATE TABLE replenishment_suggestion_step (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, suggestion_id uuid NOT NULL,
  step_order int NOT NULL CHECK(step_order > 0),
  step_kind text NOT NULL CHECK(step_kind IN (
    'DAILY_SALES','GROWTH_RATE','COVERAGE_DAYS','TARGET_STOCK','AVAILABLE',
    'IN_TRANSIT_TOTAL','IN_TRANSIT_ARRIVING','RAW_REQUIRED','BOX_SPEC',
    'MOQ','MIN_ORDER_AMOUNT','ROUNDED_QUANTITY','UNIT_PRICE','ESTIMATED_COST',
    'SUPPLIER_LEAD_TIME','PRODUCTION_LEAD_TIME','ARRIVAL_DATE')),
  -- 该步骤的量。**可为空**：「日均销量」在无登记时就是空的（NULL），不是 0。
  quantity numeric(19,6),
  -- 该步骤的口径说明（公式原文 / 来源端点）。
  basis text NOT NULL DEFAULT '',
  -- 数据来源（如「6.2.1.1 销售速度登记」「catalog 3.6.2.1 报价」「warehouse 7.1.1.3 可售量」）。
  source_ref text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,suggestion_id,step_order),
  FOREIGN KEY(tenant_id,suggestion_id) REFERENCES replenishment_suggestion(tenant_id,id) ON DELETE CASCADE
);

-- ===========================================================================
-- 6. 处置留痕（接受 / 调整 / 忽略，append-only）
-- ===========================================================================
CREATE TABLE replenishment_suggestion_decision (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, suggestion_id uuid NOT NULL,
  -- 处置动作：ACCEPT 接受 / ADJUST 调整 / IGNORE 忽略。
  action text NOT NULL CHECK(action IN ('ACCEPT','ADJUST','IGNORE')),
  -- 处置后的数量（基础单位）。ACCEPT = 建议量；ADJUST = 人工改的量；IGNORE = NULL。
  quantity numeric(19,6),
  -- 处置说明。**接受与调整都必填**：一句「按建议下单」不是留痕。
  note text NOT NULL CHECK(btrim(note) <> ''),
  decided_by uuid NOT NULL,
  decided_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,suggestion_id) REFERENCES replenishment_suggestion(tenant_id,id) ON DELETE CASCADE,
  -- IGNORED 不带数量；ACCEPT / ADJUST 必须带正数。
  CONSTRAINT replenishment_decision_quantity_consistent CHECK(
    (action = 'IGNORE' AND quantity IS NULL)
    OR (action IN ('ACCEPT','ADJUST') AND quantity IS NOT NULL AND quantity > 0)
  )
);

-- ===========================================================================
-- 7. 触发器（纵深防线：CHECK 在 PostgreSQL 里可被迁移脚本绕过，触发器不行）
-- ===========================================================================
-- 7.1 建议的**计算结论不可改写**：一次计算出来的建议量、供应商、到货日、费用都是
--     「那一刻算出来的那份结论」的凭据。处置只能动处置相关的列。
CREATE FUNCTION guard_replenishment_suggestion_frozen() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.batch_id IS DISTINCT FROM OLD.batch_id
    OR NEW.sku_id IS DISTINCT FROM OLD.sku_id
    OR NEW.sku_code IS DISTINCT FROM OLD.sku_code
    OR NEW.daily_sales_quantity IS DISTINCT FROM OLD.daily_sales_quantity
    OR NEW.growth_rate IS DISTINCT FROM OLD.growth_rate
    OR NEW.coverage_days IS DISTINCT FROM OLD.coverage_days
    OR NEW.coverage_source IS DISTINCT FROM OLD.coverage_source
    OR NEW.target_stock_quantity IS DISTINCT FROM OLD.target_stock_quantity
    OR NEW.available_quantity IS DISTINCT FROM OLD.available_quantity
    OR NEW.in_transit_quantity IS DISTINCT FROM OLD.in_transit_quantity
    OR NEW.in_transit_arriving_quantity IS DISTINCT FROM OLD.in_transit_arriving_quantity
    OR NEW.raw_required_quantity IS DISTINCT FROM OLD.raw_required_quantity
    OR NEW.moq_quantity IS DISTINCT FROM OLD.moq_quantity
    OR NEW.purchase_unit_code IS DISTINCT FROM OLD.purchase_unit_code
    OR NEW.box_spec_quantity IS DISTINCT FROM OLD.box_spec_quantity
    OR NEW.suggested_purchase_quantity IS DISTINCT FROM OLD.suggested_purchase_quantity
    OR NEW.suggested_quantity IS DISTINCT FROM OLD.suggested_quantity
    OR NEW.rounding_adjustment_quantity IS DISTINCT FROM OLD.rounding_adjustment_quantity
    OR NEW.rounding_note IS DISTINCT FROM OLD.rounding_note
    OR NEW.min_order_amount IS DISTINCT FROM OLD.min_order_amount
    OR NEW.currency IS DISTINCT FROM OLD.currency
    OR NEW.unit_price IS DISTINCT FROM OLD.unit_price
    OR NEW.estimated_cost IS DISTINCT FROM OLD.estimated_cost
    OR NEW.supplier_id IS DISTINCT FROM OLD.supplier_id
    OR NEW.supplier_name IS DISTINCT FROM OLD.supplier_name
    OR NEW.supplier_role_id IS DISTINCT FROM OLD.supplier_role_id
    OR NEW.supplier_priority IS DISTINCT FROM OLD.supplier_priority
    OR NEW.supplier_selection_basis IS DISTINCT FROM OLD.supplier_selection_basis
    OR NEW.lead_time_days IS DISTINCT FROM OLD.lead_time_days
    OR NEW.production_lead_time_days IS DISTINCT FROM OLD.production_lead_time_days
    OR NEW.expected_arrival_date IS DISTINCT FROM OLD.expected_arrival_date
    OR NEW.arrival_basis IS DISTINCT FROM OLD.arrival_basis
    OR NEW.advice IS DISTINCT FROM OLD.advice
    OR NEW.advice_basis IS DISTINCT FROM OLD.advice_basis
    OR NEW.sort_rank IS DISTINCT FROM OLD.sort_rank THEN
  RAISE EXCEPTION '补货建议「%」的计算结论不可改写：它是「那一刻算出来的那份结论」的举证凭据。要重算请发起一次新的计算', OLD.sku_code
   USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER replenishment_suggestion_frozen BEFORE UPDATE ON replenishment_suggestion
  FOR EACH ROW EXECUTE FUNCTION guard_replenishment_suggestion_frozen();

-- 7.2 处置留痕与计算拆解是**append-only**：不可改写、不可删。
CREATE FUNCTION guard_replenishment_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION '% 是不可改写的留痕表（%）：补货建议的批次、计算拆解与处置记录是「那一刻算出来的结论」的举证凭据',
   TG_TABLE_NAME, TG_OP
   USING ERRCODE='23514';
END $$;
CREATE TRIGGER replenishment_batch_append_only BEFORE UPDATE OR DELETE ON replenishment_suggestion_batch
  FOR EACH ROW EXECUTE FUNCTION guard_replenishment_append_only();
CREATE TRIGGER replenishment_step_append_only BEFORE UPDATE OR DELETE ON replenishment_suggestion_step
  FOR EACH ROW EXECUTE FUNCTION guard_replenishment_append_only();
CREATE TRIGGER replenishment_decision_append_only BEFORE UPDATE OR DELETE ON replenishment_suggestion_decision
  FOR EACH ROW EXECUTE FUNCTION guard_replenishment_append_only();

-- 7.3 配置表的**统计口径冻结**：覆盖天数 / 起订额 / 生产周期不可改，
--     唯一可动的是 effective_to，且**只能设定一次**。改了等于让「当时按哪个配置算的」查不回来。
--     ⚠️ 两张配置表的列**不相同**（覆盖天数表没有起订额，采购配置表没有覆盖天数），
--     所以冻结检查必须**各写一个触发器函数**：共用一个函数时，函数体里引用到
--     另一张表不存在的列会在运行时炸（`record "new" has no field ...`），
--     而那种炸只发生在真正改配置的那一刻 —— 平时看起来一切正常。
CREATE FUNCTION guard_replenishment_coverage_policy_frozen() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.scope IS DISTINCT FROM OLD.scope
    OR NEW.sku_id IS DISTINCT FROM OLD.sku_id
    OR NEW.category_code IS DISTINCT FROM OLD.category_code
    OR NEW.coverage_days IS DISTINCT FROM OLD.coverage_days
    OR NEW.effective_from IS DISTINCT FROM OLD.effective_from THEN
  RAISE EXCEPTION '覆盖天数配置（%）的口径不可改：要改请新增一条并收口旧的一条', OLD.id
   USING ERRCODE='23514';
 END IF;
 IF NEW.effective_to IS DISTINCT FROM OLD.effective_to THEN
  IF OLD.effective_to IS NOT NULL THEN
   RAISE EXCEPTION '覆盖天数配置的失效日已经定过（%），不能再改', OLD.effective_to
    USING ERRCODE='23514';
  END IF;
  IF NEW.effective_to IS NULL THEN
   RAISE EXCEPTION '覆盖天数配置的失效日只能设定一次，不能再置空'
    USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER replenishment_coverage_policy_frozen BEFORE UPDATE ON replenishment_coverage_policy
  FOR EACH ROW EXECUTE FUNCTION guard_replenishment_coverage_policy_frozen();

CREATE FUNCTION guard_replenishment_order_policy_frozen() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.supplier_id IS DISTINCT FROM OLD.supplier_id
    OR NEW.supplier_role_id IS DISTINCT FROM OLD.supplier_role_id
    OR NEW.min_order_amount IS DISTINCT FROM OLD.min_order_amount
    OR NEW.currency IS DISTINCT FROM OLD.currency
    OR NEW.production_lead_time_days IS DISTINCT FROM OLD.production_lead_time_days
    OR NEW.effective_from IS DISTINCT FROM OLD.effective_from THEN
  RAISE EXCEPTION '采购起订额配置（%）的口径不可改：要改请新增一条并收口旧的一条', OLD.id
   USING ERRCODE='23514';
 END IF;
 IF NEW.effective_to IS DISTINCT FROM OLD.effective_to THEN
  IF OLD.effective_to IS NOT NULL THEN
   RAISE EXCEPTION '采购起订额配置的失效日已经定过（%），不能再改', OLD.effective_to
    USING ERRCODE='23514';
  END IF;
  IF NEW.effective_to IS NULL THEN
   RAISE EXCEPTION '采购起订额配置的失效日只能设定一次，不能再置空'
    USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER replenishment_order_policy_frozen BEFORE UPDATE ON replenishment_order_policy
  FOR EACH ROW EXECUTE FUNCTION guard_replenishment_order_policy_frozen();

-- ===========================================================================
-- 8. 索引（按真实查询形状建，不多建）
-- ===========================================================================
-- 清单页：按批次 + 建议类别 + 处置状态 + 位次读（清单页主查询形状）。
CREATE INDEX replenishment_suggestion_by_batch
  ON replenishment_suggestion(tenant_id, batch_id, advice, status, sort_rank);
-- 清单页：按 SKU 过滤 / 详情页按 SKU 反查。
CREATE INDEX replenishment_suggestion_by_sku ON replenishment_suggestion(tenant_id, sku_id, batch_id);
-- 批次列表页：按计算时刻倒序。
CREATE INDEX replenishment_batch_by_time ON replenishment_suggestion_batch(tenant_id, computed_at desc, id);
-- 覆盖天数配置：按 SKU / 分类 / 生效期读（计算时的三种取法）。
CREATE INDEX replenishment_coverage_by_sku ON replenishment_coverage_policy(tenant_id, sku_id, effective_from, effective_to);
CREATE INDEX replenishment_coverage_by_category ON replenishment_coverage_policy(tenant_id, category_code, effective_from, effective_to);
-- 采购起订额配置：按供应商 / 生效期读。
CREATE INDEX replenishment_order_by_supplier ON replenishment_order_policy(tenant_id, supplier_id, effective_from, effective_to);
-- 拆解：按建议读（详情页第二段）。
CREATE INDEX replenishment_step_by_suggestion ON replenishment_suggestion_step(tenant_id, suggestion_id, step_order);
-- 处置留痕：按建议读（详情页第三段）。
CREATE INDEX replenishment_decision_by_suggestion ON replenishment_suggestion_decision(tenant_id, suggestion_id, decided_at);

-- ===========================================================================
-- 9. 多租户：RLS + 授权（与 trade V1 / V92 / V234 同一套写法）
-- ===========================================================================
-- 六张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY[
   'replenishment_coverage_policy','replenishment_order_policy',
   'replenishment_suggestion_batch','replenishment_suggestion',
   'replenishment_suggestion_step','replenishment_suggestion_decision'
 ] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  replenishment_coverage_policy,replenishment_order_policy,
  replenishment_suggestion_batch,replenishment_suggestion,
  replenishment_suggestion_step,replenishment_suggestion_decision TO trade_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  replenishment_coverage_policy,replenishment_order_policy,
  replenishment_suggestion_batch,replenishment_suggestion,
  replenishment_suggestion_step,replenishment_suggestion_decision TO starcon_app;
-- 收回批次、拆解与处置留痕的改写权（"授权在前、收回在后"，顺序不能颠倒，
-- 否则下面这条 REVOKE 会被上面的 GRANT 覆盖回去）。
REVOKE UPDATE,DELETE ON
  replenishment_suggestion_batch,replenishment_suggestion_step,replenishment_suggestion_decision FROM trade_app;
-- 建议行允许 UPDATE —— 但**只允许动处置相关的列**（由 7.1 的触发器限死），
-- 那是「接受 / 调整 / 忽略」的唯一合法动作。DELETE 一律收回。
REVOKE DELETE ON replenishment_suggestion FROM trade_app;
-- 两张配置表只收回 DELETE（收口失效日要用 UPDATE，由 7.3 的触发器限死只动那一列）。
REVOKE DELETE ON replenishment_coverage_policy,replenishment_order_policy FROM trade_app;

-- ---------------------------------------------------------------------------
-- 10. 本迁移的回归面（跨车道纪律）
-- ---------------------------------------------------------------------------
--   * **不 ALTER 任何既有表**：V1 / V92 / V97 / V234 的表一个字节都不碰，
--     因此既没有 ADD COLUMN / SET NOT NULL，也没有既有表触发器被新约束覆盖 ——
--     跨车道 DDL 回归面 = 0，不牵动任何已交付模块的既有集成测试。
--   * **不读 6.2.1.1 的表做写入**：`stockout_sales_velocity` 只被 SELECT，
--     本文件不 ALTER 它、不加约束它、不建跨表外键到它（那张表的写入所有权是 6.2.1.1）。
--   * 对 warehouse / catalog / channel 的数据**只经授权 HTTP API 读**：
--     跨库 FK 本来就禁止，所以本文件既没有约束到对方库的任何一张表。
--   * 新增函数全部带 replenishment_ 前缀，与 V1 / V25 / V92 / V97 / V234 的既有函数名不冲突。
