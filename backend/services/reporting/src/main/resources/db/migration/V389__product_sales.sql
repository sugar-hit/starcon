-- ===========================================================================
-- V389 · product_sales —— 13.2.2.2 SKU/SPU/Listing
-- ===========================================================================
--
-- 蓝图原文（docs/product/capability-blueprint/13-profit-and-analytics.md:135）：
--
--   ### 13.2.2.2 SKU/SPU/Listing
--   - **职责**：按商品维度拆解销量：SKU/SPU/Listing 三层，支持 SPU 汇总（多平台多 SKU 合并看）、
--     变体销量分布。
--   - **输入**：商品维度；时间范围；是否合并 SPU。
--   - **输出**：商品销量报表 + 层级钻取。
--   - **关系**：上游 = 9 订单、3 商品主数据；下游 = 6 补货、5 定价。
--     **SPU 口径是跨平台经营的基本单位** —— 只按平台 SKU 看会误判商品整体表现。
--
-- ---------------------------------------------------------------------------
-- 0. 这五张表是什么 / 不是什么
-- ---------------------------------------------------------------------------
--
--   reporting_product_metric_definition —— 商品维度**指标口径唯一表**
--   reporting_product_dimension       —— 商品维度档案（SKU / SPU / LISTING 三层 + 父子关系）
--   reporting_product_listing_sku     —— Listing ↔ 内部 SKU 的绑定（平台商品挂到哪些 SKU）
--   reporting_product_metric_fact     —— 商品维度**投影事实**（维度 × 指标 × 日 × 币种）
--   reporting_product_drilldown_fact  —— 商品维度**构成明细**（可下钻到订单 / SKU）
--
-- ⚠️ **本文件仍然只是投影**：商品主数据在 catalog（3 商品）、平台商品在 channel（4 刊登）、
--   订单与订单明细在 trade（9 订单）。本服务只**读**它们的授权只读 API，把结果落成读模型。
--   任何数字与业务系统冲突，**以业务系统为准**（卷 13 卷首原文）。
--
-- ⚠️ **为什么不复用 13.1.1.1 的 reporting_metric_fact / reporting_drilldown_fact**：
--   ① 它们的维度键是 uuid（shop_id / legal_entity_id），**没有商品维度** ——
--      本模块的维度键是 SKU / SPU / Listing；
--   ② reporting_drilldown_fact 的 scope_type 枚举只有
--      (COMPANY, LEGAL_ENTITY, SHOP)，**没有商品这一档**；
--   ③ reporting 自己的纪律是**零 ALTER 既有表**（V376 的七张表纯 CREATE TABLE）。
--   ⇒ 本模块**另起一组表**，不 ALTER 13.1.1.1 的任何一张表，两边互不干扰。
--     （V376 的 reporting_drilldown_fact 仍是 13.1.1.1 自己的构成明细，两处各写各的。）
--
-- ---------------------------------------------------------------------------
-- 1. 口径唯一：指标定义只有一处
-- ---------------------------------------------------------------------------
--
--   aggregation 取值：
--     SUM            流量型：窗口内把贡献行相加
--     DERIVED_DIFF   派生差：分子 − 分母
--     DERIVED_RATIO  派生比：分子 ÷ 分母
--
--   派生型只写**分子 / 分母引用**，不写公式 SQL ⇒ 口径改一处，引用它的地方自动跟着改。
--
-- 🔴 **三列扩展**（本模块特有，用于表达「商品三层」的口径）：
--
--   numerator_dimension_type / denominator_dimension_type
--     ── 分子 / 分母取自**哪一层**（空 = 与本行同层）。
--     为什么需要：变体销量占比 = 「某个 SKU 的销量」÷「它所属 SPU 的销量」，
--     分子在 SKU 层、分母在 SPU 层 —— 跨层引用。若不显式声明，引擎会把
--     「SPU 销量 ÷ SPU 销量」算成 100%，那是个**看起来对、其实没意义**的数。
--     ⇒ 声明了分子层，引擎就只在 SKU 层用它，SPU 层不出现这个指标。
--
--   currency_scope
--     ── SAME = 分子分母必须同币种（SAME 是默认，也是金额类指标的**唯一**合法值）；
--        ANY  = 允许跨币种合计（**只给件数 / 单数这类与货币无关的量**）。
--     为什么需要：变体销量分布是「件数分布」，件数与币种无关 ⇒ 允许 ANY；
--     而「件均价」「客单价」是金额除以量，必须同币种 ⇒ SAME。
--     ⚠️ **绝不跨币种直接相加**这条红线只约束**金额**：件数相加不会把两个币种的金额加起来。
--
-- ---------------------------------------------------------------------------
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. reporting_product_metric_definition —— 商品维度指标口径唯一表
-- ---------------------------------------------------------------------------
--
-- ⚠️ **不预置任何一行**：口径是**租户自己**的（13.1.1.1 与 13.1.1.2 已经是这个纪律）。
--   服务层在第一次投影时按标准口径播种一份（播种内容是服务层的常量，**不是数据源**）。
--
-- ⚠️ **派生型的分子分母必须同在本表内已登记**：DB 触发器钉死 ⇒ 口径不成立时写不进来。
CREATE TABLE reporting_product_metric_definition (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  metric_code varchar(48) NOT NULL,
  display_name varchar(80) NOT NULL,
  -- 单位：CURRENCY 金额 / COUNT 笔数 / QUANTITY 数量 / PERCENT 百分比。
  unit text NOT NULL CHECK(unit IN ('CURRENCY','COUNT','QUANTITY','PERCENT')),
  aggregation text NOT NULL
    CHECK(aggregation IN ('SUM','DERIVED_DIFF','DERIVED_RATIO')),
  -- 派生型的分子 / 分母（SUM 型恒为空）。
  numerator_metric_code varchar(48),
  denominator_metric_code varchar(48),
  -- 分子 / 分母取自哪一层（空 = 与本行同层）。
  numerator_dimension_type varchar(16) CHECK(
    numerator_dimension_type IS NULL OR numerator_dimension_type IN ('SKU','SPU','LISTING')),
  denominator_dimension_type varchar(16) CHECK(
    denominator_dimension_type IS NULL OR denominator_dimension_type IN ('SKU','SPU','LISTING')),
  -- 币种口径：SAME 同币种（默认）/ ANY 允许跨币种合计（只给与货币无关的量）。
  currency_scope text NOT NULL DEFAULT 'SAME' CHECK(currency_scope IN ('SAME','ANY')),
  -- 是否涉币：涉币指标**不跨币种相加**，只按币种分别报。
  currency_aware boolean NOT NULL DEFAULT true,
  -- 口径说明：页面上原样显示，让用户知道这个数字是怎么来的。
  description varchar(500) NOT NULL DEFAULT '',
  -- 上游归属模块。**空串 = 上游已在本服务内闭环**。
  owner_module varchar(16) NOT NULL DEFAULT '',
  enabled boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一个指标码只能有一份定义：口径唯一。
  UNIQUE(tenant_id, metric_code),
  CHECK(
    (aggregation IN ('DERIVED_DIFF','DERIVED_RATIO')
       AND numerator_metric_code IS NOT NULL AND denominator_metric_code IS NOT NULL)
    OR (aggregation = 'SUM'
       AND numerator_metric_code IS NULL AND denominator_metric_code IS NULL)
  ),
  -- 🔴 跨层引用只在派生型里出现：SUM 型没有分子分母可跨层。
  CHECK(
    (aggregation = 'SUM'
       AND numerator_dimension_type IS NULL AND denominator_dimension_type IS NULL)
    OR (aggregation IN ('DERIVED_DIFF','DERIVED_RATIO'))
  ),
  -- 🔴 **金额类指标绝不允许跨币种合计**：currency_aware=true ⇒ currency_scope 必须是 SAME。
  --     （ANY 只给件数 / 单数这类与货币无关的量。）
  CHECK(currency_aware = false OR currency_scope = 'SAME')
);

-- ---------------------------------------------------------------------------
-- 2. reporting_product_dimension —— 商品维度档案（SKU / SPU / LISTING 三层）
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个商品维度对象（一个 SKU / 一个 SPU / 一个平台商品 Listing）。
--
-- ⚠️ **三层放在一张表里**（而不是三张表）：层级钻取要在这三层之间上下走，
--   三个父键字段（parent_*）就是那条路径。分成三张表会让「SPU 下有哪些 SKU」
--   变成一次三表 join，反而更难读。
--
-- ⚠️ **dimension_id 是上游的 uuid**（catalog 的 sku.id / spu.id，channel 的 listing.id），
--   **跨服务不建外键**（AGENTS.md 模块边界纪律）—— 上游删了它，这里的行也不会被级联删掉，
--   事实还在，但维度档案查不到 ⇒ 查询侧会说「该维度不在档案里」，不猜。
--
-- ⚠️ **platform_code / shop_code / external_item_id 只对 LISTING 层有意义**：
--   SKU 与 SPU 层恒为空串。SKU 属于哪些 Listing、属于哪个平台，
--   要从 reporting_product_listing_sku 那张绑定表走 —— **不把多值塞进一张表的列里**。
CREATE TABLE reporting_product_dimension (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 维度类型：SKU（内部 SKU）/ SPU（标准产品单元）/ LISTING（平台商品）。
  dimension_type varchar(16) NOT NULL CHECK(dimension_type IN ('SKU','SPU','LISTING')),
  -- 上游的 uuid（catalog / channel 拥有）。
  dimension_id uuid NOT NULL,
  -- 业务键（可读）：SKU 码 / SPU 码 / Listing 的 uuid 文本。
  dimension_key varchar(160) NOT NULL CHECK(btrim(dimension_key) <> ''),
  dimension_name varchar(200) NOT NULL DEFAULT '',
  -- 父层：SKU 的父是 SPU；SPU 与 LISTING 没有父（空 = 顶层）。
  parent_dimension_type varchar(16) CHECK(
    parent_dimension_type IS NULL OR parent_dimension_type IN ('SKU','SPU','LISTING')),
  parent_dimension_id uuid,
  parent_dimension_key varchar(160) NOT NULL DEFAULT '',
  -- 变体签名（catalog 的 variant_signature）：同一个 SPU 下区分变体的那串规格。
  -- ⚠️ **变体销量分布按它分组**：签名相同的行才是「同一个变体」。
  variant_signature varchar(200) NOT NULL DEFAULT '',
  -- LISTING 层的平台归属（SKU / SPU 层恒为空串）。
  platform_code varchar(64) NOT NULL DEFAULT '',
  shop_code varchar(64) NOT NULL DEFAULT '',
  -- 平台商品 id 原文（ASIN / item_id），不折叠大小写。
  external_item_id varchar(160) NOT NULL DEFAULT '',
  -- Listing 的映射状态（UNMAPPED / PARTIAL / MAPPED）；SKU / SPU 层恒为空串。
  mapping_status varchar(16) NOT NULL DEFAULT '',
  -- 来源服务与上游行标识：幂等 upsert 的另一半键。
  source_service varchar(32) NOT NULL DEFAULT '',
  source_reference varchar(160) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一个上游对象在同一租户内只有一行档案。
  UNIQUE(tenant_id, dimension_type, dimension_id)
);

-- SKU 查它的 SPU（层级钻取的入口）。
CREATE INDEX reporting_product_dimension_by_parent
  ON reporting_product_dimension(tenant_id, dimension_type, parent_dimension_id);
-- 按业务键查（SPU 汇总与变体分布都按 key 定位）。
CREATE INDEX reporting_product_dimension_by_key
  ON reporting_product_dimension(tenant_id, dimension_type, dimension_key);

-- ---------------------------------------------------------------------------
-- 3. reporting_product_listing_sku —— Listing ↔ 内部 SKU 的绑定
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个平台商品上的一个内部 SKU（**多对多**：一个 Listing 多个 SKU，
-- 一个 SKU 也能挂在多个 Listing 上 —— 同一款商品同时在亚马逊和 TikTok 上架）。
--
-- ⚠️ **「一个 SKU 挂在 N 个 Listing 上」这件事本身就是一条重要信息**：
--   销量归不到唯一的 Listing 时，**不摊给任何一个**（摊了就是造假），
--   而是在能力声明与报表里如实说「这部分销量无法唯一归属到 Listing」。
--
-- ⚠️ **映射状态**：UNMAPPED / PARTIAL / MAPPED（channel 的口径，本服务原样存）。
CREATE TABLE reporting_product_listing_sku (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  listing_id uuid NOT NULL,
  listing_key varchar(160) NOT NULL CHECK(btrim(listing_key) <> ''),
  platform_code varchar(64) NOT NULL DEFAULT '',
  shop_code varchar(64) NOT NULL DEFAULT '',
  external_item_id varchar(160) NOT NULL DEFAULT '',
  sku_id uuid NOT NULL,
  -- ⚠️ **允许空串**：catalog 的 SKU 列表里还没有这个 SKU 时（商品主数据没跟上），
  --   我们拿不到它的编码 —— 留空比编一个编码诚实（空 = 本服务没有它的档案）。
  sku_key varchar(160) NOT NULL DEFAULT '',
  mapping_status varchar(16) NOT NULL DEFAULT '',
  source_service varchar(32) NOT NULL DEFAULT '',
  source_reference varchar(160) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一个 Listing 上的同一个 SKU 只有一条绑定。
  UNIQUE(tenant_id, listing_id, sku_id)
);

-- 反查：「这个 SKU 挂在哪些 Listing 上」（判定能否唯一归属的唯一依据）。
CREATE INDEX reporting_product_listing_sku_by_sku
  ON reporting_product_listing_sku(tenant_id, sku_id);
-- 正查：「这个 Listing 挂了哪些 SKU」。
CREATE INDEX reporting_product_listing_sku_by_listing
  ON reporting_product_listing_sku(tenant_id, listing_id);

-- ---------------------------------------------------------------------------
-- 4. reporting_product_metric_fact —— 商品维度投影事实
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个（维度, 指标, 日, 币种）的取值。
--
-- ⚠️ **流量型指标（成交金额 / 销量 / 订单数）按日分行**：
--   窗口内的值是这些行的 SUM ⇒ 任意窗口都能算，不需要「按窗口重新投影一次」。
--
-- ⚠️ **金额一律 numeric，禁浮点**：value numeric(24,4)、quantity numeric(19,6)。
--
-- ⚠️ **口径码必须已登记**：DB 触发器钉死 ⇒ 事实不能自己发明指标码（口径唯一）。
--
-- ⚠️ **维度 id 与档案表对齐**：事实行记 dimension_id ⇒ 查询侧能直接回到档案拿名字。
--   档案被删的事实（上游删了 SKU）仍在事实表里，但查不到档案 ⇒ 查询侧报
--   「该维度不在档案里」，**不猜一个名字**。
CREATE TABLE reporting_product_metric_fact (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  dimension_type varchar(16) NOT NULL CHECK(dimension_type IN ('SKU','SPU','LISTING')),
  dimension_id uuid NOT NULL,
  dimension_key varchar(160) NOT NULL CHECK(btrim(dimension_key) <> ''),
  metric_code varchar(48) NOT NULL,
  -- 事实归属的日历日（按记录的 timezone 分桶，不是按 UTC 截断）。
  bucket_date date NOT NULL,
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  value numeric(24,4) NOT NULL,
  -- 数量型取值（销量、件数）。金额型指标恒为 0。
  quantity numeric(19,6) NOT NULL DEFAULT 0,
  -- 分桶所用时区（跨时区店铺汇总必须显示所用时区）。
  timezone varchar(64) NOT NULL DEFAULT 'UTC',
  -- 来源服务与上游行标识：幂等 upsert 的另一半键。
  source_service varchar(32) NOT NULL,
  source_reference varchar(160) NOT NULL DEFAULT '',
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id)
);

-- 幂等键：同一 (租户, 维度, 指标, 日, 币种, 来源服务, 上游行) 只有一条事实。
CREATE UNIQUE INDEX reporting_product_metric_fact_identity
  ON reporting_product_metric_fact(
    tenant_id, dimension_type, dimension_id, metric_code, bucket_date, currency,
    source_service, source_reference);

-- 按窗口聚合用：租户 + 维度 + 指标 + 日 + 币种。
CREATE INDEX reporting_product_metric_fact_by_dimension
  ON reporting_product_metric_fact(tenant_id, dimension_type, dimension_id, metric_code, bucket_date, currency);

-- ---------------------------------------------------------------------------
-- 5. reporting_product_drilldown_fact —— 商品维度构成明细（可下钻）
-- ---------------------------------------------------------------------------
--
-- 卷 13 卷首加粗句：「**可下钻**：每个汇总数字必须能钻到构成明细（哪个订单、哪个 SKU）。」
--
-- 一行 = 一条构成明细：某个订单行对某个（维度, 指标, 日, 币种）贡献了多少。
--
-- 🔴 **同一行同时带 order_no 与 sku_code**：从 SPU 往下钻看到的是「哪几笔订单」，
--   从 SKU 往下钻看到的是「哪几笔订单的哪一行」—— 两者用同一张表，
--   不用为每一层各写一张明细表。
--
-- ⚠️ **reference_id 用「订单号 + 行序号」**：同一张订单里同一款 SKU 可能出现两行
--   （不同规格 / 不同促销），只按订单号去重会把两行合并成一个数 —— 那就丢了明细。
CREATE TABLE reporting_product_drilldown_fact (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  dimension_type varchar(16) NOT NULL CHECK(dimension_type IN ('SKU','SPU','LISTING')),
  dimension_id uuid NOT NULL,
  dimension_key varchar(160) NOT NULL CHECK(btrim(dimension_key) <> ''),
  metric_code varchar(48) NOT NULL,
  bucket_date date NOT NULL,
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 构成类型：ORDER 订单行（订单与 SKU 两层共用同一行明细）。
  source_kind varchar(32) NOT NULL CHECK(source_kind IN ('ORDER','SKU')),
  -- 构成对象的标识与可读标签（订单号 + 行序号 / SKU 码）。
  reference_id varchar(160) NOT NULL CHECK(btrim(reference_id) <> ''),
  reference_label varchar(200) NOT NULL DEFAULT '',
  -- 构成所在的订单（uuid）与订单号：下钻到「哪张订单」用。
  order_id uuid,
  order_no varchar(128) NOT NULL DEFAULT '',
  -- 🔴 **下单时刻（timestamptz，精确到瞬间）**：下钻时能看到「这笔销量是哪一刻产生的」。
  --   ⚠️ 必须用 {@code OffsetDateTime} 绑定并在 values 位置写 {@code ?::timestamptz} ——
  --   用 {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800s（东八区的时间会被记成 UTC）。
  occurred_at timestamptz,
  -- 构成所在的 SKU 码：下钻到「哪个 SKU」用。
  sku_code varchar(64) NOT NULL DEFAULT '',
  sku_name varchar(200) NOT NULL DEFAULT '',
  -- 数量型构成（销量件数）与金额型贡献。
  quantity numeric(19,6) NOT NULL DEFAULT 0,
  value numeric(24,4) NOT NULL DEFAULT 0,
  -- 🔴 分桶所用时区（跨时区店铺汇总必须显示所用时区）：这一行的 bucket_date 是按它分桶出来的。
  timezone varchar(64) NOT NULL DEFAULT 'UTC',
  source_service varchar(32) NOT NULL,
  source_reference varchar(160) NOT NULL DEFAULT '',
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id)
);

-- 幂等键：同一条构成明细只留一条（重投同一批订单不会把金额加两遍）。
CREATE UNIQUE INDEX reporting_product_drilldown_fact_identity
  ON reporting_product_drilldown_fact(
    tenant_id, dimension_type, dimension_id, metric_code, bucket_date, currency,
    source_kind, reference_id, source_service, source_reference);

-- 下钻列表用：租户 + 维度 + 指标 + 日 + 币种 + 贡献倒序。
CREATE INDEX reporting_product_drilldown_fact_by_dimension
  ON reporting_product_drilldown_fact(
    tenant_id, dimension_type, dimension_id, metric_code, bucket_date, currency, value DESC);

-- ===========================================================================
-- 第二节：数据库不变量（把蓝图的话变成数据层做不到的事）
-- ===========================================================================

-- (a) 派生型指标的分子分母必须**真的存在**（否则口径是一句空话）。
CREATE OR REPLACE FUNCTION guard_reporting_product_metric_definition_ref() RETURNS trigger AS $$
BEGIN
  IF NEW.aggregation NOT IN ('DERIVED_DIFF','DERIVED_RATIO') THEN
    RETURN NEW;
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM reporting_product_metric_definition
     WHERE tenant_id = NEW.tenant_id AND metric_code = NEW.numerator_metric_code) THEN
    RAISE EXCEPTION '指标 % 的分子指标 % 尚未定义，口径不成立', NEW.metric_code, NEW.numerator_metric_code
      USING ERRCODE = 'check_violation';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM reporting_product_metric_definition
     WHERE tenant_id = NEW.tenant_id AND metric_code = NEW.denominator_metric_code) THEN
    RAISE EXCEPTION '指标 % 的分母指标 % 尚未定义，口径不成立', NEW.metric_code, NEW.denominator_metric_code
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER reporting_product_metric_definition_ref
  BEFORE INSERT OR UPDATE ON reporting_product_metric_definition
  FOR EACH ROW EXECUTE FUNCTION guard_reporting_product_metric_definition_ref();

-- (b) 事实行的**指标码必须已登记**：口径表里没有的指标码写不进来。
CREATE OR REPLACE FUNCTION guard_reporting_product_metric_fact_known() RETURNS trigger AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM reporting_product_metric_definition
     WHERE tenant_id = NEW.tenant_id AND metric_code = NEW.metric_code) THEN
    RAISE EXCEPTION '指标码 % 尚未在商品维度口径表中登记，不能落事实', NEW.metric_code
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER reporting_product_metric_fact_known
  BEFORE INSERT OR UPDATE ON reporting_product_metric_fact
  FOR EACH ROW EXECUTE FUNCTION guard_reporting_product_metric_fact_known();

-- (c) 构成明细的**指标码必须已登记**：与事实行同一条红线。
CREATE OR REPLACE FUNCTION guard_reporting_product_drilldown_fact_known() RETURNS trigger AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM reporting_product_metric_definition
     WHERE tenant_id = NEW.tenant_id AND metric_code = NEW.metric_code) THEN
    RAISE EXCEPTION '指标码 % 尚未在商品维度口径表中登记，不能落构成明细', NEW.metric_code
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER reporting_product_drilldown_fact_known
  BEFORE INSERT OR UPDATE ON reporting_product_drilldown_fact
  FOR EACH ROW EXECUTE FUNCTION guard_reporting_product_drilldown_fact_known();

-- ===========================================================================
-- 第三节：多租户（RLS isolation）+ 授权
-- ===========================================================================

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'reporting_product_metric_definition',
    'reporting_product_dimension',
    'reporting_product_listing_sku',
    'reporting_product_metric_fact',
    'reporting_product_drilldown_fact'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_product_metric_definition,
  reporting_product_dimension,
  reporting_product_listing_sku,
  reporting_product_metric_fact,
  reporting_product_drilldown_fact TO reporting_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_product_metric_definition,
  reporting_product_dimension,
  reporting_product_listing_sku,
  reporting_product_metric_fact,
  reporting_product_drilldown_fact TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM reporting_app;

-- ===========================================================================
-- 第四节：留给下游的口子（不是本模块的缺口）
-- ===========================================================================
--
--   * **13.3.1.1 产品表现分析 / 13.3.1.2 生命周期与分层（未开发）**：
--     它们要的是「单产品 360 度」。本表的三层维度（SKU / SPU / LISTING）
--     与档案表足够承接，**不需要**改结构。
--   * **14.2.1.x 商品维度广告效果（未开发）**：它要 SKU/SPU/Listing 三个维度的
--     广告花费与 ACOS。本表已经有三层维度键，广告事实落到同一批维度上即可。
--   * **6 补货 / 5 定价（下游）**：它们按 SPU 看整体表现 ——
--     本模块的 SPU 汇总正是为此存在（「SPU 口径是跨平台经营的基本单位」）。
