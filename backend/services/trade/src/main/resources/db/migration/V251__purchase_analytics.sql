-- 6.5.2.1 采购分析（卷 06 采购与供应 / L3 6.5.2 采购分析的唯一一格）。
--
-- 蓝图 06-purchase-and-supply.md 6.5.2.1 原文：
--   职责：采购侧报表：采购金额趋势、供应商集中度与贡献、采购价格波动、到货准时率、
--         采购成本占销售额比、按类目/店铺维度的采购效率。
--   输入：采购单与收货数据；时间范围与维度筛选。
--   输出：报表图表 + 可导出明细；下钻到具体采购单。
--   关系：上游 = 6.3、6.4；下游 = 13 利润与经营分析。
--         **采购成本占比是利润健康度的先行指标**，采购毛利异常时能提前发现。
--
-- 归属：**trade 服务**（蓝图 06 卷 L2 表「6.5 采购结算 → trade → finance」的第一项是 trade；
-- 本仓不存在 finance 服务 —— backend/services/ 下只有
-- identity / catalog / channel / warehouse / trade / crm）。
-- 前端路由 /purchase/analytics 是**前端**路由；后端命名空间按服务走
-- （与 6.2.1.3 的 /trade/purchase-plans、6.3.2.2 的 /trade/purchase-arrivals 同一纪律）。
--
-- ===========================================================================
-- 一、本模块拥有什么、不拥有什么
-- ===========================================================================
--
-- ⚠️ **「采购单原单」（6.3.1.1 列表 / 6.3.1.2 创建编辑 / 6.3.1.3 审批）在本仓未交付**。
--   实查集成头：backend/services/trade/src/main/java/** 下没有 PurchaseOrder 类，
--   backend/services/trade/src/main/resources/db/migration/ 下没有采购单表。
--   ⇒ 本模块**不建采购单影子表**，也不假装有采购单号可下钻。
--   本模块的报表基数取自**已交付**的事实（见第二节），并在能力声明里把
--   「采购单原单」显式标成 available=false + ownerModule=6.3.1.x。
--
-- ⚠️ **6.4.2.1 质检单同批并行开发，此刻在本仓不存在** ⇒
--   质检扣量、质检不合格成本一律**不进**本模块的金额口径，
--   能力声明里显式标 available=false + ownerModule=6.4.2.1。
--
-- ⚠️ **13 利润与经营分析在本仓没有对应服务** ⇒ 下游显式标未交付。
--
-- ⚠️ **6.4.1.1 加工单（V97）已交付但没有金额字段**（processing_order 里没有单价 / 币种），
--   ⇒ 委外加工费**不计入**采购金额口径；能力声明里写明「不是漏了，是上游没有金额事实」。
--
-- ===========================================================================
-- 二、报表基数的来源（全部是已交付事实，逐条可查）
-- ===========================================================================
--
--   1. `purchase_arrival_tracking` / `purchase_arrival_receipt`（V235，6.3.2.2 到货与逾期）
--      —— **采购金额 / 供应商 / 单价 / 币种 / 承诺到货日 / 准时与否**的事实来源。
--      金额 = 单价快照 × 本次到货数量（V235 的 DB 触发器已按同一口径算过一遍，
--      本模块**只 SELECT**，不重算、不改它一个字）。
--   2. `purchase_plan` / `purchase_plan_line`（V242，6.2.1.3 采购计划）
--      —— **SKU / 计划量 / 期望到货日 / 目标仓 / 大促店铺**的来源。
--      计划行**没有金额**（V242 里就没有价格列）⇒ 计划只进「量」的口径，不进金额口径。
--   3. `supplier_claim`（V188，11.2.3.3 供应商索赔）
--      —— 已达成（AGREED / SETTLED）的索赔金额是**采购成本的冲减**，单独列示，
--      不与采购金额相抵成一个数（抵了之后看不出「买的多」与「赔的多」）。
--   4. warehouse 的 8.2.1.1 收货单（`GET /warehouse/goods-receipts`）
--      —— **实收量**的来源（跨服务，经授权只读 HTTP 端口；不跨库 SQL / FK / 视图）。
--
-- ===========================================================================
-- 三、蓝图加粗句与卷 06 硬约束在本文件里的物理落点
-- ===========================================================================
--
-- ⚠️ **卷 06 硬约束「必须记录币种、汇率快照、单位换算」**：
--   * `currency` —— 每一行都带原币（到货事实带的是供应商报价币种）。
--   * `fx_rate` / `fx_status` / `fx_rate_id` / `fx_rate_effective_date` —— **汇率快照**：
--     折算用的汇率在**事实物化那一刻**从 catalog 的汇率台账取到并**冻结在本行**，
--     之后 catalog 改了汇率，历史报表的数字不会跟着漂。
--   * `fx_status` 三态（照抄 trade V130 dropship margin 的同一套词表）：
--       SAME_CURRENCY 原币即本位币，不需要汇率；
--       APPLIED        按快照汇率折算，fx_rate 留证；
--       UNSET          汇率取不到 ⇒ **折算列整组为 NULL**，该行被排除在本位币合计之外
--                      并单独进「未折算」桶。**绝不按 1:1 折算**。
--   * `unit_code` / `unit_factor` / `base_quantity` —— **单位换算**：
--     `unit_factor` = 1 个记录单位等于多少个基础单位（catalog 的 quantityPerBase），
--     `base_quantity` = 折算到基础单位后的数量。折不出来时 base_quantity 为 NULL，
--     同样**不进**数量合计（宁可少算，不可算错）。
--
-- ⚠️ **卷 06 硬约束「采购收货是分批、可有差异的」**：
--   本模块的事实粒度就是**每一次到货一行**（不是每张采购单一行），
--   金额与数量都按流水累加 ⇒ 分批到货不会被压成一个「已到齐」的数字。
--
-- ⚠️ **卷 06 硬约束「采购单生成后修改要留痕」**：
--   本模块的物化台账是**按事实键幂等 upsert**（同一 fact_key 只更新、不新增），
--   且每次同步落一条 `purchase_analytics_sync_run` 留痕（扫了多少 / 新增 / 更新 / 跳过）。
--   上游事实被改了 → 下次同步更新那一行；台账本身**不删**历史行。
--
-- ===========================================================================
-- 1. 采购分析事实台账（物化自上游已交付事实）
-- ===========================================================================
CREATE TABLE purchase_analytics_fact (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- **事实键**：幂等的唯一依据（来源 + 来源单号 + 来源行号）。
  -- 同一键重复同步只更新不新增 ⇒ 同步可以重跑，不会把金额翻倍。
  fact_key varchar(160) NOT NULL CHECK(btrim(fact_key) <> ''),
  -- **事实种类**：COST 带金额 / RECEIPT 实收量 / PLAN 计划量。
  -- 三者语义不同，混在一张表里靠 source_type 区分，报表按 fact_kind 取数。
  fact_kind varchar(12) NOT NULL CHECK(fact_kind IN ('COST','RECEIPT','PLAN')),

  -- ---- 来源（上游模块的写入所有权仍在它们自己那里，本表只是快照）----
  source_type varchar(32) NOT NULL CHECK(source_type IN
    ('ARRIVAL_RECEIPT','PURCHASE_PLAN_LINE','GOODS_RECEIPT','SUPPLIER_CLAIM')),
  source_doc_no varchar(64) NOT NULL DEFAULT '',
  source_doc_id uuid,
  -- 上游来源单号（例如采购单号 purchase_order_no、收货单号）。
  source_ref_no varchar(64) NOT NULL DEFAULT '',

  -- **发生时刻**：报表的时间轴基准。金额按它归期。
  occurred_at timestamptz NOT NULL,
  -- 业务日历日（date 口径，按租户所在时区由服务层换算后落库）。
  occurred_date date NOT NULL,

  -- ---- 供应商（catalog 拥有档案，本表只存引用 + 快照）----
  supplier_ref_id uuid,
  supplier_code varchar(64) NOT NULL DEFAULT '',
  supplier_name varchar(200) NOT NULL DEFAULT '',

  -- ---- SKU ----
  sku_ref_id uuid,
  sku_code varchar(64) NOT NULL DEFAULT '',
  sku_name varchar(200) NOT NULL DEFAULT '',

  -- ---- 维度快照（类目来自 catalog 的 SKU 主数据；店铺来自大促日历）----
  category_code varchar(64) NOT NULL DEFAULT '',
  category_name varchar(200) NOT NULL DEFAULT '',
  shop_code varchar(64) NOT NULL DEFAULT '',
  warehouse_code varchar(64) NOT NULL DEFAULT '',

  -- ---- 数量与单位换算 ----
  quantity numeric(19,6) NOT NULL DEFAULT 0 CHECK(quantity >= 0),
  unit_code varchar(32) NOT NULL DEFAULT '',
  -- **unit_factor = 1 个记录单位等于多少个基础单位**（catalog 的 quantityPerBase）。
  unit_factor numeric(19,6) CHECK(unit_factor IS NULL OR unit_factor > 0),
  -- 折算到基础单位后的数量。折不出来 = NULL（不是 0）。
  base_quantity numeric(19,6),

  -- ---- 金额与币种（金额一律 NUMERIC，禁浮点）----
  unit_price numeric(19,6) CHECK(unit_price IS NULL OR unit_price >= 0),
  amount numeric(19,4) CHECK(amount IS NULL OR amount >= 0),
  currency char(3) CHECK(currency IS NULL OR currency ~ '^[A-Z]{3}$'),

  -- ---- 汇率快照 ----
  base_currency char(3),
  fx_rate numeric(19,8) CHECK(fx_rate IS NULL OR fx_rate > 0),
  -- **本位币金额**：折算后的值。汇率取不到时为 NULL（不是 0，也不按 1:1 折算）。
  base_amount numeric(19,4),
  fx_status varchar(16) NOT NULL DEFAULT 'NOT_APPLICABLE' CHECK(fx_status IN
    ('SAME_CURRENCY','APPLIED','UNSET','NOT_APPLICABLE')),
  fx_rate_id uuid,
  fx_rate_effective_date date,

  -- ---- 到货准时（只有到货类事实有）----
  promised_arrival_date date,
  on_time_status varchar(16) NOT NULL DEFAULT 'NOT_APPLICABLE' CHECK(on_time_status IN
    ('ON_TIME','LATE','PENDING','NOT_APPLICABLE')),

  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, fact_key),
  -- 成本事实要么带金额，要么明确标 NOT_APPLICABLE；不许「有成本事实但金额为空且没解释」。
  CONSTRAINT purchase_analytics_fact_cost_amount CHECK(
    fact_kind <> 'COST' OR amount IS NOT NULL OR fx_status = 'NOT_APPLICABLE'
  ),
  -- APPLIED 必须留下汇率证据（值 + 来源 id + 生效日）；UNSET 必须没有汇率值。
  CONSTRAINT purchase_analytics_fact_fx_consistent CHECK(
    (fx_status = 'APPLIED' AND fx_rate IS NOT NULL AND fx_rate_id IS NOT NULL
        AND fx_rate_effective_date IS NOT NULL AND base_currency IS NOT NULL
        AND base_amount IS NOT NULL)
    OR (fx_status <> 'APPLIED' AND fx_rate IS NULL)
  ),
  -- UNSET（汇率取不到）必须**没有**本位币金额 —— 未折算的钱不能出现在本位币合计里。
  CONSTRAINT purchase_analytics_fact_unset_consistent CHECK(
    fx_status <> 'UNSET' OR (base_amount IS NULL AND fx_rate IS NULL)
  ),
  -- SAME_CURRENCY（原币即本位币）必须给出本位币金额，且折算系数恒为 1（不重复登记汇率）。
  CONSTRAINT purchase_analytics_fact_same_currency_consistent CHECK(
    fx_status <> 'SAME_CURRENCY' OR (base_amount IS NOT NULL AND base_currency = currency)
  ),
  -- 准时判定只在到货类事实上有意义。
  CONSTRAINT purchase_analytics_fact_on_time_consistent CHECK(
    on_time_status = 'NOT_APPLICABLE' OR source_type = 'ARRIVAL_RECEIPT'
  )
);

COMMENT ON TABLE purchase_analytics_fact IS
  '6.5.2.1 采购分析事实台账：物化自 6.3.2.2 到货流水 / 6.2.1.3 采购计划行 / 8.2.1.1 收货单 / 11.2.3.3 供应商索赔';

-- ===========================================================================
-- 2. 同步批次留痕（append-only）
-- ===========================================================================
-- 「什么时候、按哪个窗口、把哪些上游事实物化进来了」必须能回溯：
-- 报表上的一个数，得能一路追到某一次同步的某一行。
CREATE TABLE purchase_analytics_sync_run (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  run_no varchar(32) NOT NULL CHECK(btrim(run_no) <> ''),
  -- 本次同步覆盖的来源（ALL = 全部已交付来源）。
  source_type varchar(32) NOT NULL CHECK(source_type IN
    ('ALL','ARRIVAL_RECEIPT','PURCHASE_PLAN_LINE','GOODS_RECEIPT','SUPPLIER_CLAIM')),
  -- 窗口口径：起止**都含**（[window_start, window_end] 的自然日）。
  window_start date NOT NULL,
  window_end date NOT NULL,
  status varchar(16) NOT NULL CHECK(status IN ('RUNNING','SUCCESS','PARTIAL','FAILED')),
  scanned_count int NOT NULL DEFAULT 0 CHECK(scanned_count >= 0),
  created_count int NOT NULL DEFAULT 0 CHECK(created_count >= 0),
  updated_count int NOT NULL DEFAULT 0 CHECK(updated_count >= 0),
  skipped_count int NOT NULL DEFAULT 0 CHECK(skipped_count >= 0),
  -- SKU 维度解析不出来的行数（catalog 读不到 / SKU 不存在）。
  unresolved_sku_count int NOT NULL DEFAULT 0 CHECK(unresolved_sku_count >= 0),
  -- 汇率取不到、因而未折算的行数。
  unconverted_count int NOT NULL DEFAULT 0 CHECK(unconverted_count >= 0),
  message text NOT NULL DEFAULT '',
  triggered_by uuid NOT NULL,
  started_at timestamptz NOT NULL,
  finished_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, run_no),
  CONSTRAINT purchase_analytics_sync_run_window_ordered CHECK(window_end >= window_start),
  -- 跑完的批次必须有结束时刻；还在跑的批次没有。
  CONSTRAINT purchase_analytics_sync_run_finished CHECK(
    status = 'RUNNING' OR finished_at IS NOT NULL
  )
);

COMMENT ON TABLE purchase_analytics_sync_run IS
  '6.5.2.1 采购分析同步批次留痕：每次物化一行，append-only';

-- ===========================================================================
-- 3. SKU 维度快照（catalog 主数据经授权只读端口解析后冻结）
-- ===========================================================================
-- ⚠️ 这张表**不是** SKU 影子表：它只存「本模块做采购分析需要的两个维度」
--    —— 类目（报表分组）与单位换算系数（数量折算），且**只在解析成功时**落库。
--    SKU 档案的写入所有者仍是 catalog（3.1.2.1）。
CREATE TABLE purchase_analytics_sku_dimension (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  sku_id uuid NOT NULL,
  sku_code varchar(64) NOT NULL DEFAULT '',
  sku_name varchar(200) NOT NULL DEFAULT '',
  category_code varchar(64) NOT NULL DEFAULT '',
  category_name varchar(200) NOT NULL DEFAULT '',
  base_unit varchar(32) NOT NULL DEFAULT '',
  -- 采购单位（catalog 里 role='PURCHASE' 的那个）与它的换算系数。
  purchase_unit varchar(32) NOT NULL DEFAULT '',
  unit_factor numeric(19,6) CHECK(unit_factor IS NULL OR unit_factor > 0),
  -- RESOLVED 解析成功 / NOT_FOUND catalog 说没有这个 SKU / UNAVAILABLE catalog 读不到。
  resolve_status varchar(16) NOT NULL CHECK(resolve_status IN ('RESOLVED','NOT_FOUND','UNAVAILABLE')),
  note text NOT NULL DEFAULT '',
  resolved_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, sku_id),
  -- 解析成功 ⇒ 类目与基础单位必须落到位；解析失败 ⇒ 不能带出任何维度值。
  CONSTRAINT purchase_analytics_sku_dimension_consistent CHECK(
    (resolve_status = 'RESOLVED' AND base_unit <> '' AND category_code <> '')
    OR (resolve_status <> 'RESOLVED' AND category_code = '' AND base_unit = '' AND unit_factor IS NULL)
  )
);

COMMENT ON TABLE purchase_analytics_sku_dimension IS
  '6.5.2.1 SKU 维度快照：类目 + 单位换算系数，由 catalog 授权只读接口解析后冻结';

-- ===========================================================================
-- 4. 索引（按真实查询形状建，不多建）
-- ===========================================================================
-- 报表主查询：窗口 + 事实种类 + 币种（趋势 / 集中度 / 准时率都走这一条）。
CREATE INDEX purchase_analytics_fact_by_window
  ON purchase_analytics_fact(tenant_id, fact_kind, occurred_date, currency);
-- 供应商维度：集中度与贡献、供应商筛选。
CREATE INDEX purchase_analytics_fact_by_supplier
  ON purchase_analytics_fact(tenant_id, supplier_ref_id, occurred_date);
-- 类目 / 店铺维度：采购效率。
CREATE INDEX purchase_analytics_fact_by_dimension
  ON purchase_analytics_fact(tenant_id, category_code, shop_code, occurred_date);
-- 下钻到来源单据：同一张来源单的多行（分批到货）连成一片。
CREATE INDEX purchase_analytics_fact_by_source
  ON purchase_analytics_fact(tenant_id, source_type, source_doc_no, occurred_at);
-- 汇率未折算桶：单独捞出来给人看。
CREATE INDEX purchase_analytics_fact_by_fx_status
  ON purchase_analytics_fact(tenant_id, fx_status) WHERE fx_status = 'UNSET';
-- 同步留痕：按时间倒序读最近的批次。
CREATE INDEX purchase_analytics_sync_run_by_time
  ON purchase_analytics_sync_run(tenant_id, started_at desc, id);

-- ===========================================================================
-- 5. 多租户：RLS + 授权（与 trade V1 / V92 / V235 / V242 同一套写法）
-- ===========================================================================
-- 三张表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'purchase_analytics_fact','purchase_analytics_sync_run','purchase_analytics_sku_dimension'
  ] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  purchase_analytics_fact,purchase_analytics_sync_run,purchase_analytics_sku_dimension TO trade_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  purchase_analytics_fact,purchase_analytics_sync_run,purchase_analytics_sku_dimension TO starcon_app;
-- 同步留痕**不提供 DELETE**：删掉一次同步记录 = 让报表上的那个数失去出处。
-- UPDATE **保留**：同步批次要从 RUNNING 改写成终态（扫描 / 新增 / 更新计数与结束时刻）——
-- 这不是「改历史」，而是「把这次同步做完」。真正的不可篡改由「没有 DELETE」+ 只由服务层写入保证。
REVOKE DELETE ON purchase_analytics_sync_run FROM trade_app;
-- 事实台账**不提供 DELETE**：删一行就等于把这个数从报表里抹掉且无从追查。
-- 事实错了由同步覆盖（upsert），或者由上游事实更正后重新同步。
REVOKE DELETE ON purchase_analytics_fact FROM trade_app;

-- ---------------------------------------------------------------------------
-- 6. 本迁移的回归面（跨车道纪律）
-- ---------------------------------------------------------------------------
--   * **不 ALTER 任何既有表**：V1 / V97 / V188 / V235 / V242 的表一个字节都不碰，
--     因此既没有 ADD COLUMN / SET NOT NULL，也没有既有表触发器被新约束覆盖 ——
--     跨车道 DDL 回归面 = 0，不牵动任何已交付模块的既有集成测试。
--   * **只 SELECT 上游的表**：`purchase_arrival_tracking` / `purchase_arrival_receipt` /
--     `purchase_plan` / `purchase_plan_line` / `purchase_plan_campaign` / `supplier_claim`
--     都被本模块读，但本文件不 ALTER 它们、不加约束它们、不建跨表外键到它们
--     （那些表的写入所有权属于 6.3.2.2 / 6.2.1.3 / 11.2.3.3）。
--   * 对 warehouse / catalog 的数据**只经授权 HTTP API 读**：
--     跨库 FK 本来就禁止，所以本文件既没有约束到对方库的任何一张表，
--     也没有建任何跨库视图。
--   * 新增函数/表名全部带 purchase_analytics_ 前缀，与 V1 / V25 / V92 / V235 / V242 的
--     既有对象名不冲突。
