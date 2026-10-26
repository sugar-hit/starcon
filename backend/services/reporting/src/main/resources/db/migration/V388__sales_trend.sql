-- ===========================================================================
-- V388 · sales_trend —— 13.2.1.1 销量趋势
-- ===========================================================================
--
-- 蓝图原文（docs/product/capability-blueprint/13-profit-and-analytics.md:114）：
--
--   ### 13.2.1.1 销量趋势
--   - **职责**：销量与销售额趋势：按日/周/月、小时维度查看，支持多维对比
--     （同比/环比/店铺间/产品间）、异常日期标注（大促、缺货、断货）。
--   - **输入**：时间范围；维度与对比方式；异常事件标注。
--   - **输出**：趋势图 + 对比视图 + 异常标注。
--   - **关系**：上游 = 9 订单数据；下游 = 13.2.2、6.2.1 补货（销量预测输入）。
--     **销量异常必须能关联事件**（大促、断货、价格调整），否则图表只是曲线没有洞察。
--
-- ---------------------------------------------------------------------------
-- 0. 这三张表是什么 / 不是什么
-- ---------------------------------------------------------------------------
--
--   reporting_sales_metric_definition —— 销量趋势的**指标口径唯一表**（只登记**新增**口径）
--   reporting_sales_trend_fact      —— 销量趋势的**投影事实**（时间序列）
--   reporting_sales_event           —— **异常事件标注**（大促 / 秒杀 / 缺货 / 断货）
--
-- ⚠️ **本文件仍然只是投影**：成交金额与销量真值在 trade（销售订单 + 订单明细），
--   店铺归属在 trade 的平台单（platform_order.sales_order_id 回挂），
--   大促与秒杀在 channel（14.4.1.1 / 14.4.2.1），断货与缺货在 trade（6.2.1.1 缺货与断货风险）。
--   本服务只**读**它们的授权只读 API，把结果落成读模型。任何数字与业务系统冲突，
--   **以业务系统为准**（卷 13 卷首原文）。
--
-- ---------------------------------------------------------------------------
-- 1. 口径唯一：公司维度的口径**不在这里重定义**
-- ---------------------------------------------------------------------------
--
--   🔴 **公司维度（TOTAL）的三个基础口径已经登记在 `reporting_metric_definition`**
--   （13.1.1.1 经营总览的口径表，内容唯一来源是 `ReportingMetricCatalog.STANDARD`）：
--
--     SALES_AMOUNT   成交订单的含税成交金额
--     UNIT_SOLD      成交订单行的数量合计
--     ORDER_COUNT    成交订单笔数
--
--   ⇒ 本模块**直接引用**它们（事实行的 metric_code 就是这三个码），
--     **不另起一份定义** —— 否则同一个数会有两个口径真相。
--
--   本表只登记**13.1.1.1 没有的**两类口径：
--     ① **维度专属口径**：店铺维度（STORE）与商品维度（PRODUCT）
--        —— 13.1.1.1 的口径表**没有维度列**，且既有表**不允许 ALTER**
--           （与 13.1.1.2 另起 `reporting_store_metric_definition` 同一纪律）；
--     ② **对比口径**：环比（PERIOD_OVER_PERIOD）与同比（YEAR_OVER_YEAR）
--        —— 13.1.1.1 的 aggregation 枚举里**没有**这两档，
--           而本模块的核心输出就是趋势点上的**环比与同比**。
--
--   ⚠️ 对比口径**只写被比较指标的引用**（base_metric_code），不写公式 SQL
--     ⇒ 口径改一处，引用它的地方自动跟着改。
--   ⚠️ 对比口径的基准是**同一个指标的另一个桶 / 另一个窗口**：
--     基准缺失或为 0 ⇒ **算不出**，不是 0%。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. reporting_sales_metric_definition —— 销量趋势新增的指标口径
-- ---------------------------------------------------------------------------
--
-- ⚠️ **不预置任何一行**：口径是**租户自己**的（13.1.1.1 / 13.1.1.2 已经是这个纪律）。
--   服务层在第一次投影时按标准口径播种一份（播种内容是服务层的常量，**不是数据源**）。
--
-- ⚠️ **被比较指标必须已登记**（本表 **或** 13.1.1.1 的 `reporting_metric_definition`）：
--   DB 触发器钉死 ⇒ 口径不成立时写不进来。
CREATE TABLE reporting_sales_metric_definition (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  metric_code varchar(48) NOT NULL,
  display_name varchar(80) NOT NULL,
  -- 单位：CURRENCY 金额 / COUNT 笔数 / QUANTITY 数量 / PERCENT 百分比。
  unit text NOT NULL CHECK(unit IN ('CURRENCY','COUNT','QUANTITY','PERCENT')),
  aggregation text NOT NULL
    CHECK(aggregation IN ('SUM','PERIOD_OVER_PERIOD','YEAR_OVER_YEAR')),
  -- 本指标属于哪个维度：TOTAL 公司 / STORE 店铺 / PRODUCT 商品。
  -- ⚠️ 这是**口径的一部分**（公司口径的成交金额与商品口径的行金额不是一个数），
  --   所以它写在口径表里，而不是靠查询方记住。
  dimension_type text NOT NULL
    CHECK(dimension_type IN ('TOTAL','STORE','PRODUCT')),
  -- **被比较的指标**（PERIOD_OVER_PERIOD / YEAR_OVER_YEAR 型必填；SUM 型恒为空）。
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
    (aggregation IN ('PERIOD_OVER_PERIOD','YEAR_OVER_YEAR')
       AND base_metric_code IS NOT NULL)
    OR (aggregation = 'SUM'
       AND base_metric_code IS NULL)
  )
);

-- ---------------------------------------------------------------------------
-- 2. reporting_sales_trend_fact —— 销量趋势投影事实
-- ---------------------------------------------------------------------------
--
-- 一行 = 一条**事实贡献**（订单或订单行）在某个时刻、某个指标、某个币种、
-- 某个维度上的取值。
--
-- ⚠️ **存的是事实发生的精确时刻（timestamptz），不是分桶后的日期**：
--   日 / 周 / 月 / 小时的**分桶是查询期的事**（按调用方指定的时区做
--   `date_trunc(bucket_start AT TIME ZONE tz)`）——
--   ① 这样半小时偏移的时区（+05:30 / +05:45）也能**精确**落到本地日；
--   ② 换时区不需要重新投影（重投影是写事实的代价，不是查一个数的代价）。
--
-- ⚠️ **dimension_type 三种，各有各的口径**：
--   TOTAL   公司维度：trade 销售订单（总额 / 笔数）与订单明细行（销量）
--   STORE   店铺维度：同一批销售订单，按平台单回挂的 shop_code 分组
--   PRODUCT 商品维度：订单明细行（SKU 维度的数量与行金额）
--   ⇒ 三个维度**不可相加**（口径不同，见上面的指标码拆分）。
--
-- ⚠️ **金额一律 numeric，禁浮点**：value numeric(24,4)、quantity numeric(19,6)。
--   COUNT 型指标（订单量）落在 value 列（与 13.1.1.2 同一纪律），QUANTITY 型落在 quantity 列。
--
-- ⚠️ **口径码必须已登记**（本表 **或** 13.1.1.1 的 `reporting_metric_definition`）：
--   DB 触发器钉死 ⇒ 事实不能自己发明指标码（口径唯一）。
CREATE TABLE reporting_sales_trend_fact (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  metric_code varchar(48) NOT NULL,
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  dimension_type varchar(16) NOT NULL
    CHECK(dimension_type IN ('TOTAL','STORE','PRODUCT')),
  -- 维度键：TOTAL 恒为空串；STORE 是 shop_code；PRODUCT 是 sku_code。
  dimension_key varchar(64) NOT NULL DEFAULT '',
  -- 事实发生的时刻（**精确时刻**，不是分桶结果；分桶在查询期做）。
  bucket_start timestamptz NOT NULL,
  -- 金额型取值（金额型与笔数型指标用这列；数量型指标恒为 0）。
  value numeric(24,4) NOT NULL DEFAULT 0,
  -- 数量型取值（销量）。金额型指标恒为 0。
  quantity numeric(19,6) NOT NULL DEFAULT 0,
  -- 来源服务与上游行标识：幂等 upsert 的另一半键。
  source_service varchar(32) NOT NULL,
  source_reference varchar(160) NOT NULL DEFAULT '',
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id)
);

-- 幂等键：同一 (租户, 指标, 币种, 维度, 维度键, 时刻, 来源服务, 上游行) 只有一条事实。
CREATE UNIQUE INDEX reporting_sales_trend_fact_identity
  ON reporting_sales_trend_fact(
    tenant_id, metric_code, currency, dimension_type, dimension_key,
    bucket_start, source_service, source_reference);

-- 趋势查询用：租户 + 指标 + 时刻范围。
CREATE INDEX reporting_sales_trend_fact_by_metric
  ON reporting_sales_trend_fact(tenant_id, metric_code, bucket_start);
-- 维度查询用：租户 + 维度 + 维度键 + 时刻范围。
CREATE INDEX reporting_sales_trend_fact_by_dimension
  ON reporting_sales_trend_fact(tenant_id, dimension_type, dimension_key, bucket_start);

-- ---------------------------------------------------------------------------
-- 3. reporting_sales_event —— 异常事件标注
-- ---------------------------------------------------------------------------
--
-- 蓝图输入句里明写「异常事件标注」，关系句加粗写「**销量异常必须能关联事件**
-- （大促、断货、价格调整），否则图表只是曲线没有洞察」。
-- ⇒ 事件本身是**投影事实**（来自 channel 的促销 / 秒杀、trade 的缺货断货风险），
--   而「哪些桶是异常」是**查询期判定**（拿桶值与基线比）——
--   判定结果不落库（会过期），事件落库（是事实）。
--
-- ⚠️ **事件类型**：PROMOTION 大促 / FLASH_SALE 秒杀 / STOCKOUT 断货 / SHORTAGE 缺货。
--   「价格调整」目前没有上游事件源（价格变更在 5 定价服务）⇒
--   能力声明里如实列为拿不到，不在这里造一条。
--
-- ⚠️ **shop_code / sku_code 允许为空串**：空串 = 该事件**不针对具体店铺 / 商品**
--   （例如全局大促）。查询期按「空串 = 命中所有维度」处理。
CREATE TABLE reporting_sales_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 事件码（租户内唯一，与类型一起构成幂等键）。
  event_code varchar(80) NOT NULL,
  event_type varchar(24) NOT NULL
    CHECK(event_type IN ('PROMOTION','FLASH_SALE','STOCKOUT','SHORTAGE')),
  -- 事件标题（页面上原样显示）。
  title varchar(200) NOT NULL,
  -- 事件说明（大促的折扣、缺货的缺口量…）。
  detail varchar(500) NOT NULL DEFAULT '',
  -- 事件覆盖的日期区间（含首尾）。**按事件的日历日落**（时区无关的那一份）。
  event_date date NOT NULL,
  end_date date NOT NULL,
  -- 🔴 **有起止时刻的事件**（大促 / 秒杀）额外落**精确时刻**：
  --   查询期按**调用方指定的时区**把它换算成日历日，
  --   这样「北京时间 10-01 00:30 开促」在 UTC 口径下不会被算到前一天。
  --   **纯日期事件**（断货日 / 缺货日，上游只给日期）这两列为空 ⇒ 按日历日匹配。
  start_at timestamptz,
  end_at timestamptz,
  -- 作用范围（空串 = 全局）。
  shop_code varchar(64) NOT NULL DEFAULT '',
  sku_code varchar(64) NOT NULL DEFAULT '',
  -- 严重级别：INFO / WARNING / CRITICAL。
  severity varchar(16) NOT NULL DEFAULT 'INFO'
    CHECK(severity IN ('INFO','WARNING','CRITICAL')),
  -- 来源服务与上游行标识：幂等 upsert 的另一半键。
  source_service varchar(32) NOT NULL,
  source_reference varchar(160) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一个类型下的同一个事件码只有一条（重复刷新是覆盖，不是翻倍）。
  UNIQUE(tenant_id, event_type, event_code),
  CHECK(end_date >= event_date),
  -- 有精确时刻就必须两端都有，且结束不早于开始。
  CHECK((start_at IS NULL) = (end_at IS NULL)),
  CHECK(start_at IS NULL OR end_at >= start_at)
);

-- 按日期查事件（趋势点标注用）：租户 + 日期区间。
CREATE INDEX reporting_sales_event_by_date
  ON reporting_sales_event(tenant_id, event_date, end_date);
-- 按作用范围查事件（店铺 / 商品维度用）：租户 + 店铺码 / 商品码。
CREATE INDEX reporting_sales_event_by_scope
  ON reporting_sales_event(tenant_id, shop_code, sku_code, event_date);

-- ===========================================================================
-- 第二节：数据库不变量（把蓝图的话变成数据层做不到的事）
-- ===========================================================================

-- (a) 对比口径的**被比较指标必须真的存在**
--     （本表里，或者 13.1.1.1 的 `reporting_metric_definition` 里 —— 公司维度的三个基础口径在那里）。
CREATE OR REPLACE FUNCTION guard_reporting_sales_metric_definition_ref() RETURNS trigger AS $$
BEGIN
  IF NEW.aggregation IN ('PERIOD_OVER_PERIOD','YEAR_OVER_YEAR') THEN
    IF NOT EXISTS (
      SELECT 1 FROM reporting_sales_metric_definition
       WHERE tenant_id = NEW.tenant_id AND metric_code = NEW.base_metric_code)
       AND NOT EXISTS (
      SELECT 1 FROM reporting_metric_definition
       WHERE tenant_id = NEW.tenant_id AND metric_code = NEW.base_metric_code) THEN
      RAISE EXCEPTION '指标 % 的被比较指标 % 尚未定义，口径不成立', NEW.metric_code, NEW.base_metric_code
        USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER reporting_sales_metric_definition_ref
  BEFORE INSERT OR UPDATE ON reporting_sales_metric_definition
  FOR EACH ROW EXECUTE FUNCTION guard_reporting_sales_metric_definition_ref();

-- (b) 事实行的**指标码必须已登记**（本表 **或** 13.1.1.1 的口径表）；
--     而且事实的维度必须与**该指标口径登记的维度一致**
--     （公司口径的成交金额不能落到店铺维度上 —— 那是把一个口径挂到另一个维度）。
CREATE OR REPLACE FUNCTION guard_reporting_sales_trend_fact_known() RETURNS trigger AS $$
DECLARE expected_dimension text;
BEGIN
  SELECT dimension_type INTO expected_dimension
    FROM reporting_sales_metric_definition
   WHERE tenant_id = NEW.tenant_id AND metric_code = NEW.metric_code;
  IF expected_dimension IS NULL THEN
    -- 不在本表 ⇒ 必须是 13.1.1.1 口径表里的**公司维度**口径。
    IF NOT EXISTS (
      SELECT 1 FROM reporting_metric_definition
       WHERE tenant_id = NEW.tenant_id AND metric_code = NEW.metric_code) THEN
      RAISE EXCEPTION '指标码 % 尚未在口径表中登记，不能落事实', NEW.metric_code
        USING ERRCODE = 'check_violation';
    END IF;
    expected_dimension := 'TOTAL';
  END IF;
  IF expected_dimension <> NEW.dimension_type THEN
    RAISE EXCEPTION '指标 % 的口径维度是 %，不能以 % 维度落事实', NEW.metric_code,
      expected_dimension, NEW.dimension_type
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER reporting_sales_trend_fact_known
  BEFORE INSERT OR UPDATE ON reporting_sales_trend_fact
  FOR EACH ROW EXECUTE FUNCTION guard_reporting_sales_trend_fact_known();

-- (c) 维度键的形状：TOTAL 维度**不许**带维度键（否则「公司维度」会有好几个口径）。
CREATE OR REPLACE FUNCTION guard_reporting_sales_trend_fact_dimension() RETURNS trigger AS $$
BEGIN
  IF NEW.dimension_type = 'TOTAL' AND btrim(NEW.dimension_key) <> '' THEN
    RAISE EXCEPTION '公司维度（TOTAL）不允许带维度键：%', NEW.dimension_key
      USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.dimension_type IN ('STORE','PRODUCT') AND btrim(NEW.dimension_key) = '' THEN
    RAISE EXCEPTION '% 维度必须带维度键（店铺码 / 商品码）', NEW.dimension_type
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER reporting_sales_trend_fact_dimension
  BEFORE INSERT OR UPDATE ON reporting_sales_trend_fact
  FOR EACH ROW EXECUTE FUNCTION guard_reporting_sales_trend_fact_dimension();

-- ===========================================================================
-- 第三节：多租户（RLS isolation）+ 授权
-- ===========================================================================

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'reporting_sales_metric_definition',
    'reporting_sales_trend_fact',
    'reporting_sales_event'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_sales_metric_definition,
  reporting_sales_trend_fact,
  reporting_sales_event TO reporting_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_sales_metric_definition,
  reporting_sales_trend_fact,
  reporting_sales_event TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM reporting_app;

-- ===========================================================================
-- 第四节：留给下游的口子（不是本模块的缺口）
-- ===========================================================================
--
--   * **13.2.1.2 分时与分周模式**：本模块已经按**精确时刻**存事实（bucket_start），
--     分时热力图直接在本表上按小时分桶即可，**不需要**改结构。
--   * **13.2.2.1 店铺与站点 / 13.2.2.2 SKU·SPU·Listing**：本表的 PRODUCT 维度是
--     **SKU 口径**（订单明细行的 skuCode），SPU 合并需要商品主数据（3）⇒ 上游补齐后
--     同一张表就能承接，**不需要**改结构。
--   * **6.2.1 补货（销量预测输入）**：本模块的销量时间序列就是销量预测的输入，
--     下游按 bucket_start 读即可。
