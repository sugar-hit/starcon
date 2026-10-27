-- ===========================================================================
-- V391 · store_site_breakdown —— 13.2.2.1 店铺与站点
-- ===========================================================================
--
-- 蓝图原文（docs/product/capability-blueprint/13-profit-and-analytics.md:128）：
--
--   ### 13.2.2.1 店铺与站点
--   - **职责**：按店铺、站点、平台维度拆解销量与销售额，含店铺增长贡献、新店/老店区分、渠道占比。
--   - **输入**：维度选择；时间范围。
--   - **输出**：分组报表 + 占比与贡献度。
--   - **关系**：上游 = 13.2.1.1；下游 = 13.1.1.2。**增长贡献度比绝对值有用**：
--     告诉老板"哪个店/哪个品带来了增量"。
--
-- ---------------------------------------------------------------------------
-- 0. 这四张表是什么 / 不是什么
-- ---------------------------------------------------------------------------
--
--   reporting_store_site_metric_definition —— 13.2.2.1 的**指标口径唯一表**
--   reporting_store_site_profile          —— 店铺 / 站点 / 平台**档案**（维度树的一环）
--   reporting_store_site_metric_fact      —— 店铺维度**投影事实**（金额 / 销量 / 订单数）
--   reporting_store_site_drilldown_fact   —— 店铺维度**构成明细**（可下钻到订单行）
--
-- ⚠️ **本文件仍然只是投影**：成交金额与销量真值在 trade（9 订单），
--   店铺 / 站点 / 平台归属在 channel（2 渠道与店铺）。本服务只**读**它们的授权只读 API，
--   把结果落成读模型。任何数字与业务系统冲突，**以业务系统为准**（卷 13 卷首原文）。
--
-- ---------------------------------------------------------------------------
-- 1. 🔴 为什么事实只落在**店铺**一层，站点 / 平台是查询期重分组
-- ---------------------------------------------------------------------------
--
--   维度树是 **平台 → 站点 → 店铺**（channel 的 `platform` / `marketplace` / `shop` 三张表）。
--   本模块要的三个维度（店铺 / 站点 / 平台）**互为上下级**，不是三套独立事实：
--
--     PLATFORM  = 该平台下所有店铺的合计
--     SITE      = 该站点下所有店铺的合计
--     SHOP      = 该店铺自己的合计
--
--   ⇒ 若把同一批订单**投影三遍**（三个维度各一份事实），刷新一次要写三倍行，
--     而三份行之间可能出现**不一致**（一次刷新写了一半）—— 那是自找的「一个数两个真相」。
--   ⇒ 本表**只落店铺层事实**；站点 / 平台维度在**查询期**按 `reporting_store_site_profile`
--     重分组（一次 SQL join，口径唯一）。
--
--   ⚠️ 与 13.2.1.1（V388 `reporting_sales_trend_fact`）的分工：
--     13.2.1.1 是**趋势**视图（按日/周/月/小时的曲线 + 环比同比 + 异常标注），
--     它的维度是 STORE / PRODUCT / TOTAL；
--     本模块是**维度拆解**视图（按店铺/站点/平台分组 + 占比 + 增长贡献 + 新老店 + 渠道占比）。
--     两边读的是**同一批上游订单**（9 订单），但**口径码不重名**（本表一律 `STORE_SITE_` 前缀），
--     所以同一个数不会有两个真相；本表的成交金额与 13.2.1.1 的店铺成交金额**同源同口径**
--     （9 订单的订单总额 + 平台单回挂店铺），差别只在**维度树**与**增长贡献**。
--
-- ---------------------------------------------------------------------------
-- 2. 🔴 站点维度有一条硬边界：**订单不带站点**
-- ---------------------------------------------------------------------------
--
--   trade 的销售订单与平台单都**不带站点（marketplace）**，只带 `shop_code` 与 `platform_code`；
--   站点归属在 channel 的 `shop_marketplace`（一个店铺启用哪些站点）。
--   ⇒ 店铺的销量**能不能**唯一落到某个站点，取决于**该店铺启用了几个站点**：
--
--     启用了 1 个站点  ⇒ UNIQUE    销量可唯一归属到那个站点
--     启用了 ≥2 个站点 ⇒ AMBIGUOUS 销量归不了任何站点（摊给谁是造假）
--     启用了 0 个站点  ⇒ NONE      同上
--
--   ⇒ 本表用 `attribution_status` 把这件事**钉进数据层**，
--     查询期据此把「无法唯一归属」的销量**单列**（不是摊给某个站点，也不是悄悄丢掉）。
--
-- ---------------------------------------------------------------------------
-- 3. 口径唯一：指标定义只有一处
-- ---------------------------------------------------------------------------
--
--   aggregation 取值：
--     SUM               流量型：窗口内把贡献行相加
--     GROUP_SHARE       组内占比：该维度取值 ÷ 同层级合计
--     GROUP_CONTRIBUTION 增长贡献度：该维度的增量 ÷ 全部维度的增量之和
--     WINDOW_GROWTH     窗口环比：(当前窗口值 − 上一等长窗口值) ÷ 上一等长窗口值
--
--   派生型只写**被比较指标的引用**（base_metric_code），不写公式 SQL
--   ⇒ 口径改一处，引用它的地方自动跟着改。
--
--   ⚠️ GROUP_SHARE / GROUP_CONTRIBUTION 的分母是**「同一层级合计」**：
--     选店铺维度 ⇒ 分母是全部店铺的合计；选平台维度 ⇒ 分母是全部平台的合计。
--     层级是**调用方选的**，所以分母只能在查询期算 —— 这就是为什么它们不是事实表里的行。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. reporting_store_site_metric_definition —— 13.2.2.1 的指标口径唯一表
-- ---------------------------------------------------------------------------
--
-- ⚠️ **不预置任何一行**：口径是**租户自己**的（13.1.1.1 / 13.1.1.2 / 13.2.1.1 / 13.2.2.2
--   已经是这个纪律）。服务层在第一次投影时按标准口径播种一份（播种内容是服务层的常量，
--   **不是数据源**）。
--
-- ⚠️ **派生型的被比较指标必须同在本表内已登记**：DB 触发器钉死 ⇒ 口径不成立时写不进来。
CREATE TABLE reporting_store_site_metric_definition (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  metric_code varchar(48) NOT NULL,
  display_name varchar(80) NOT NULL,
  -- 单位：CURRENCY 金额 / COUNT 笔数 / QUANTITY 数量 / PERCENT 百分比。
  unit text NOT NULL CHECK(unit IN ('CURRENCY','COUNT','QUANTITY','PERCENT')),
  aggregation text NOT NULL
    CHECK(aggregation IN ('SUM','GROUP_SHARE','GROUP_CONTRIBUTION','WINDOW_GROWTH')),
  -- 派生型的分子 / 分母（SUM 型恒为空）。
  numerator_metric_code varchar(48),
  denominator_metric_code varchar(48),
  -- **被比较 / 被分享的指标**：
  --   GROUP_SHARE         —— 「谁被分成多少份」（分母 = 同层级合计）
  --   GROUP_CONTRIBUTION  —— 「谁的增量算在总增量里」（分子 = 该维度的增量）
  --   WINDOW_GROWTH       —— 「跟上一个等长窗口比」
  -- SUM 型恒为空。
  base_metric_code varchar(48),
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
    (aggregation = 'SUM'
       AND numerator_metric_code IS NULL AND denominator_metric_code IS NULL
       AND base_metric_code IS NULL)
    OR (aggregation IN ('GROUP_SHARE','GROUP_CONTRIBUTION','WINDOW_GROWTH')
       AND numerator_metric_code IS NULL AND denominator_metric_code IS NULL
       AND base_metric_code IS NOT NULL)
  )
);

-- ---------------------------------------------------------------------------
-- 2. reporting_store_site_profile —— 店铺 / 站点 / 平台档案（维度树）
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个（店铺, 站点）绑定。
--
-- ⚠️ **站点码只在平台内唯一**（channel 的 `marketplace` 表：UNIQUE(platform_id, code)，
--   注释写明「每个平台都有 US/DE 这类同名站点，平台才是它们的父级」）
--   ⇒ 站点维度键必须是 **平台码:站点码**，不能只用站点码。
--
-- ⚠️ **一个店铺可以启用多个站点** ⇒ 一行一个绑定；该店铺的销量能不能唯一落到某个站点，
--   取决于它启用了几个站点 ⇒ 用 `attribution_status` 说清楚（见上面第 2 节）。
--
-- ⚠️ **「没有启用站点」的店铺也要有一行**（site_code = ''，attribution_status = 'NONE'）：
--   否则这家店铺在档案里查不到，查询侧只能报「不在档案里」—— 那是对的，但会让人以为
--   是数据缺失。有一行 NONE 就把「它确实没启用站点」这件事说出来了。
CREATE TABLE reporting_store_site_profile (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 店铺业务编码（channel `shop.code`）。
  shop_code varchar(64) NOT NULL CHECK(btrim(shop_code) <> ''),
  shop_name varchar(150) NOT NULL DEFAULT '',
  -- 所属平台（channel `platform.code` / `platform.name`）。**空串 = 尚未确认归属**。
  platform_code varchar(64) NOT NULL DEFAULT '',
  platform_name varchar(120) NOT NULL DEFAULT '',
  -- 站点（channel `marketplace.code` / `marketplace.name`）。
  -- ⚠️ **空串只在 attribution_status = 'NONE' 时出现**（DB 触发器钉死）。
  site_code varchar(64) NOT NULL DEFAULT '',
  site_name varchar(120) NOT NULL DEFAULT '',
  -- 站点国家 / 默认币种 / 默认时区（channel `marketplace` 的全局码表参数）。
  -- 🔴 **空串 = 上游没给**（不是「币种未知」的托词：这两个字段上游确实有值，
  --   拿不到说明是映射缺失，查询侧会把它如实显示出来）。
  site_country_code varchar(8) NOT NULL DEFAULT '',
  site_currency varchar(3) NOT NULL DEFAULT '',
  site_timezone varchar(64) NOT NULL DEFAULT '',
  -- 站点启用状态（channel `shop_marketplace.status`：ACTIVE / SUSPENDED）。
  site_status varchar(16) NOT NULL DEFAULT '',
  -- 🔴 **站点启用时刻（timestamptz，精确到瞬间）**：新店 / 老店判定的**旁证**
  --   （真正的判据是事实表里的成交先后，见 13.2.2.1 的口径说明）。
  site_enabled_at timestamptz,
  -- 归属状态：UNIQUE / AMBIGUOUS / NONE（见上面第 2 节）。
  attribution_status varchar(16) NOT NULL
    CHECK(attribution_status IN ('UNIQUE','AMBIGUOUS','NONE')),
  -- 来源服务与上游标识：幂等 upsert 的另一半键。
  source_service varchar(32) NOT NULL DEFAULT '',
  source_reference varchar(160) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一个店铺的同一个站点只有一行档案。
  UNIQUE(tenant_id, shop_code, site_code)
);

-- 按平台分组用：租户 + 平台 + 店铺。
CREATE INDEX reporting_store_site_profile_by_platform
  ON reporting_store_site_profile(tenant_id, platform_code, shop_code);
-- 按站点分组用：租户 + 站点键（平台码:站点码）。
CREATE INDEX reporting_store_site_profile_by_site
  ON reporting_store_site_profile(tenant_id, platform_code, site_code);

-- ---------------------------------------------------------------------------
-- 3. reporting_store_site_metric_fact —— 店铺维度投影事实
-- ---------------------------------------------------------------------------
--
-- 一行 = 一条**事实贡献**（一张订单 / 一张订单的一批明细行）在某个时刻、
--   某个指标、某个币种、某个**店铺**上的取值。
--
-- ⚠️ **存的是事实发生的精确时刻（timestamptz），不是分桶后的日期**：
--   窗口的切分是**查询期**的事（按调用方指定的时区做
--   `(bucket_start at time zone tz)::date`）——
--   ① 这样半小时偏移的时区（+05:30 / +05:45）也能**精确**落到本地日；
--   ② 换时区不需要重新投影（重投影是写事实的代价，不是查一个数的代价）。
--   这与 13.2.1.1（V388）**逐字同一纪律** ⇒ 两边的时间口径天然对齐。
--
-- ⚠️ **金额一律 numeric，禁浮点**：value numeric(24,4)、quantity numeric(19,6)。
--
-- ⚠️ **按订单落行，不按天落行**：订单数要**按订单去重**（一张订单里同一款 SKU 出现两行
--   也只算一单），逐单落行才能既去重又保留明细。
--
-- ⚠️ **口径码必须已登记**：DB 触发器钉死 ⇒ 事实不能自己发明指标码（口径唯一）。
CREATE TABLE reporting_store_site_metric_fact (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  shop_code varchar(64) NOT NULL CHECK(btrim(shop_code) <> ''),
  metric_code varchar(48) NOT NULL,
  -- 事实发生的精确时刻（**精确时刻**，不是分桶结果；分桶在查询期做）。
  bucket_start timestamptz NOT NULL,
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 金额型 / 笔数型取值（金额型与笔数型指标用这列；数量型指标恒为 0）。
  value numeric(24,4) NOT NULL DEFAULT 0,
  -- 数量型取值（销量件数）。金额型指标恒为 0。
  quantity numeric(19,6) NOT NULL DEFAULT 0,
  -- 来源服务与上游行标识：幂等 upsert 的另一半键。
  source_service varchar(32) NOT NULL,
  source_reference varchar(160) NOT NULL DEFAULT '',
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id)
);

-- 幂等键：同一 (租户, 店铺, 指标, 币种, 时刻, 来源服务, 上游行) 只有一条事实。
CREATE UNIQUE INDEX reporting_store_site_metric_fact_identity
  ON reporting_store_site_metric_fact(
    tenant_id, shop_code, metric_code, bucket_start, currency, source_service, source_reference);

-- 按窗口聚合用：租户 + 店铺 + 指标 + 时刻。
CREATE INDEX reporting_store_site_metric_fact_by_shop
  ON reporting_store_site_metric_fact(tenant_id, shop_code, metric_code, bucket_start);

-- ---------------------------------------------------------------------------
-- 4. reporting_store_site_drilldown_fact —— 构成明细（可下钻）
-- ---------------------------------------------------------------------------
--
-- 卷 13 卷首加粗句：「**可下钻**：每个汇总数字必须能钻到构成明细（哪个订单、哪个 SKU）。」
--
-- 一行 = 一条构成明细：某个订单 / 某条订单行对某个（店铺, 指标, 时刻, 币种）贡献了多少。
--
-- 🔴 **同一行同时带 order_no 与 sku_code**：从店铺往下钻看到的是「哪几笔订单」，
--   从「哪张订单的哪一行」看到的是行级构成 —— 两者用同一张表。
CREATE TABLE reporting_store_site_drilldown_fact (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  shop_code varchar(64) NOT NULL CHECK(btrim(shop_code) <> ''),
  metric_code varchar(48) NOT NULL,
  -- 事实发生的精确时刻。
  bucket_start timestamptz NOT NULL,
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  -- 构成类型：ORDER 订单级 / ORDER_LINE 订单行级。
  source_kind varchar(16) NOT NULL CHECK(source_kind IN ('ORDER','ORDER_LINE')),
  -- 构成对象的标识与可读标签（订单号 / 订单号 + 行号）。
  reference_id varchar(160) NOT NULL CHECK(btrim(reference_id) <> ''),
  reference_label varchar(200) NOT NULL DEFAULT '',
  -- 构成所在的订单号：下钻到「哪张订单」用。
  order_no varchar(128) NOT NULL DEFAULT '',
  -- 🔴 **下单时刻（timestamptz，精确到瞬间）**：下钻时能看到「这笔销量是哪一刻产生的」。
  --   ⚠️ 必须用 {@code OffsetDateTime} 绑定并在 values 位置写 {@code ?::timestamptz} ——
  --   用 {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800s（东八区的时间会被记成 UTC）。
  occurred_at timestamptz,
  -- 构成所在的 SKU 码与名称（下钻到「哪个 SKU」用）。
  sku_code varchar(64) NOT NULL DEFAULT '',
  sku_name varchar(200) NOT NULL DEFAULT '',
  -- 数量型构成（销量件数）与金额型贡献。
  quantity numeric(19,6) NOT NULL DEFAULT 0,
  value numeric(24,4) NOT NULL DEFAULT 0,
  -- 来源服务与上游行标识：幂等 upsert 的另一半键。
  source_service varchar(32) NOT NULL,
  source_reference varchar(160) NOT NULL DEFAULT '',
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id)
);

-- 幂等键：同一条构成明细只留一条（重投同一批订单不会把金额加两遍）。
CREATE UNIQUE INDEX reporting_store_site_drilldown_fact_identity
  ON reporting_store_site_drilldown_fact(
    tenant_id, shop_code, metric_code, bucket_start, currency,
    source_kind, reference_id, source_service, source_reference);

-- 下钻列表用：租户 + 店铺 + 指标 + 时刻 + 贡献倒序。
CREATE INDEX reporting_store_site_drilldown_fact_by_shop
  ON reporting_store_site_drilldown_fact(
    tenant_id, shop_code, metric_code, bucket_start, value DESC);

-- ===========================================================================
-- 第二节：数据库不变量（把蓝图的话变成数据层做不到的事）
-- ===========================================================================

-- (a) 派生型的**被比较指标必须真的存在**（否则口径是一句空话）。
CREATE OR REPLACE FUNCTION guard_reporting_store_site_metric_definition_ref() RETURNS trigger AS $$
BEGIN
  IF NEW.aggregation NOT IN ('GROUP_SHARE','GROUP_CONTRIBUTION','WINDOW_GROWTH') THEN
    RETURN NEW;
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM reporting_store_site_metric_definition
     WHERE tenant_id = NEW.tenant_id AND metric_code = NEW.base_metric_code) THEN
    RAISE EXCEPTION '指标 % 的被比较指标 % 尚未定义，口径不成立', NEW.metric_code, NEW.base_metric_code
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER reporting_store_site_metric_definition_ref
  BEFORE INSERT OR UPDATE ON reporting_store_site_metric_definition
  FOR EACH ROW EXECUTE FUNCTION guard_reporting_store_site_metric_definition_ref();

-- (b) 事实行的**指标码必须已登记**：口径表里没有的指标码写不进来。
CREATE OR REPLACE FUNCTION guard_reporting_store_site_metric_fact_known() RETURNS trigger AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM reporting_store_site_metric_definition
     WHERE tenant_id = NEW.tenant_id AND metric_code = NEW.metric_code) THEN
    RAISE EXCEPTION '指标码 % 尚未在店铺/站点口径表中登记，不能落事实', NEW.metric_code
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER reporting_store_site_metric_fact_known
  BEFORE INSERT OR UPDATE ON reporting_store_site_metric_fact
  FOR EACH ROW EXECUTE FUNCTION guard_reporting_store_site_metric_fact_known();

-- (c) 构成明细的**指标码必须已登记**：与事实行同一条红线。
CREATE OR REPLACE FUNCTION guard_reporting_store_site_drilldown_fact_known() RETURNS trigger AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM reporting_store_site_metric_definition
     WHERE tenant_id = NEW.tenant_id AND metric_code = NEW.metric_code) THEN
    RAISE EXCEPTION '指标码 % 尚未在店铺/站点口径表中登记，不能落构成明细', NEW.metric_code
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER reporting_store_site_drilldown_fact_known
  BEFORE INSERT OR UPDATE ON reporting_store_site_drilldown_fact
  FOR EACH ROW EXECUTE FUNCTION guard_reporting_store_site_drilldown_fact_known();

-- (d) 🔴 **归属状态与站点码的形状必须一致**：
--     「没有启用站点」⇒ 站点码必须为空；「启用了一个 / 多个站点」⇒ 站点码必须非空。
--     这条把「销量能不能唯一归属到站点」钉进数据层 —— 写错形状的档案写不进来。
CREATE OR REPLACE FUNCTION guard_reporting_store_site_profile_attribution() RETURNS trigger AS $$
BEGIN
  IF NEW.attribution_status = 'NONE' AND btrim(NEW.site_code) <> '' THEN
    RAISE EXCEPTION '归属状态 NONE（未启用站点）的店铺档案不许带站点码：%', NEW.site_code
      USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.attribution_status IN ('UNIQUE','AMBIGUOUS') AND btrim(NEW.site_code) = '' THEN
    RAISE EXCEPTION '归属状态 % 的店铺档案必须带站点码', NEW.attribution_status
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER reporting_store_site_profile_attribution
  BEFORE INSERT OR UPDATE ON reporting_store_site_profile
  FOR EACH ROW EXECUTE FUNCTION guard_reporting_store_site_profile_attribution();

-- ===========================================================================
-- 第三节：多租户（RLS isolation）+ 授权
-- ===========================================================================

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'reporting_store_site_metric_definition',
    'reporting_store_site_profile',
    'reporting_store_site_metric_fact',
    'reporting_store_site_drilldown_fact'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_store_site_metric_definition,
  reporting_store_site_profile,
  reporting_store_site_metric_fact,
  reporting_store_site_drilldown_fact TO reporting_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_store_site_metric_definition,
  reporting_store_site_profile,
  reporting_store_site_metric_fact,
  reporting_store_site_drilldown_fact TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM reporting_app;

-- ===========================================================================
-- 第四节：留给下游的口子（不是本模块的缺口）
-- ===========================================================================
--
--   * **13.1.1.2 多店对比（已交付）**：它读的是自己的 `reporting_store_metric_fact`
--     （店铺对比口径）。本模块的 `reporting_store_site_profile` 是**店铺 / 站点 / 平台**
--     的维度树，多店对比要按站点分组时可以直接引用，**不需要**改它的表。
--   * **13.2.2.2 SKU/SPU/Listing（已交付）**：它按商品拆，本模块按店铺/站点/平台拆 ——
--     两种**并列**的拆解维度，事实表互不干扰（它的表是 `reporting_product_*`）。
--   * **13.2.1.2 分时与分周模式**：本模块与 13.2.1.1 一样把**精确时刻**存事实
--     （bucket_start），分时热力图直接在本表上按小时分桶即可，**不需要**改结构。
