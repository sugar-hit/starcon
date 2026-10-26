-- ===========================================================================
-- V381 · store_comparison —— 13.1.1.2 多店对比
-- ===========================================================================
--
-- 蓝图原文（docs/product/capability-blueprint/13-profit-and-analytics.md:93）：
--
--   ### 13.1.1.2 多店对比
--   - **职责**：多店铺横向对比：规模、增长、利润率、广告效率、退货率、库存周转，
--     找出掉队店铺与增长店铺，支持按平台分组。
--   - **输入**：店铺维度聚合；对比指标选择。
--   - **输出**：对比矩阵 + 排名 + 差异归因提示。
--   - **关系**：上游 = 13.1.1.1；下游 = 各店铺的专题分析。
--     **跨平台对比要处理口径差异**（亚马逊的 GMV 与 TikTok 的口径不同），对比时标注口径说明。
--
-- ---------------------------------------------------------------------------
-- 0. 这三张表是什么 / 不是什么
-- ---------------------------------------------------------------------------
--
--   reporting_store_profile          —— 店铺档案（店铺码 / 店铺名 / 所属平台）
--   reporting_store_metric_definition —— 店铺对比的**指标口径唯一表**
--   reporting_store_metric_fact      —— 店铺维度**投影事实**（可从上游重建）
--
-- ⚠️ **本文件仍然只是投影**：三张表里没有任何业务事实的业务真相 —— 店铺归属在
--   channel（V1 `shop` / `platform`）、成交金额在 trade（V207 `platform_order`）、
--   广告花费在 channel（V273 `ad_campaign_daily_metric`）、退货在 trade（售后单）。
--   本服务只**读**它们的授权只读 API，把结果落成读模型。任何数字与业务系统冲突，
--   **以业务系统为准**（卷 13 卷首原文）。
--
-- ⚠️ **为什么不复用 13.1.1.1 的 reporting_metric_fact**：
--   ① 它的维度键是 uuid（shop_id），而本模块的店铺维度来源是 channel / trade 的
--      **shop_code（业务编码）**，跨服务的授权只读 API 暴露的是编码而不是 uuid；
--   ② 它的 aggregation 枚举里**没有**「窗口环比」这一档，而本模块的「增长」就是
--      窗口环比（当前窗口 vs 上一等长窗口）；
--   ③ reporting 自己的纪律是**零 ALTER 既有表**（V376 的七张表纯 CREATE TABLE）。
--   ⇒ 本模块**另起一组表**，不 ALTER 13.1.1.1 的任何一张表，两边互不干扰。
--
-- ---------------------------------------------------------------------------
-- 1. 口径唯一：指标定义只有一处
-- ---------------------------------------------------------------------------
--
--   aggregation 取值：
--     SUM             流量型：窗口内把贡献行相加
--     LAST            存量型：取窗口内最近一天的快照值
--     DERIVED_DIFF    派生差：分子 − 分母
--     DERIVED_RATIO   派生比：分子 ÷ 分母
--     WINDOW_GROWTH   窗口环比：(当前窗口值 − 上一等长窗口值) ÷ 上一等长窗口值
--
--   派生型只写**分子 / 分母引用**，不写公式 SQL ⇒ 口径改一处，引用它的地方自动跟着改。
--   ⚠️ WINDOW_GROWTH 的分母是**同一个指标的上一等长窗口值**（上一窗口为 0 或缺数据 ⇒ 算不出，
--     不是 0%）。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. reporting_store_profile —— 店铺档案
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个店铺。**它是「按平台分组」这件事的唯一依据**（蓝图：支持按平台分组）。
--
-- ⚠️ **shop_code 是业务编码而不是 uuid**：channel（V1 `shop.code`）、trade（V207
--   `platform_order.shop_code`）、13.4.2.2 费用差异（V379）三处都用它，且都是跨服务
--   授权只读 API 里真实暴露的字段。跨服务**不建外键**（AGENTS.md 模块边界纪律）。
--
-- ⚠️ **platform_code / platform_name 允许为空串**：店铺归属的平台来自 channel 的
--   广告活动（每个活动带 shop_code + platform_code）。**没有任何广告活动的店铺**
--   拿不到平台归属 —— 那时这里留空串，查询侧把它归到「未标注平台」组并**如实说明**，
--   绝不猜一个平台。
CREATE TABLE reporting_store_profile (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 店铺业务编码（channel `shop.code`）。
  shop_code varchar(64) NOT NULL CHECK(btrim(shop_code) <> ''),
  shop_name varchar(150) NOT NULL DEFAULT '',
  -- 所属平台（channel `platform.code` / `platform.name`）。**空串 = 尚未确认归属**。
  platform_code varchar(64) NOT NULL DEFAULT '',
  platform_name varchar(120) NOT NULL DEFAULT '',
  -- 数据来源与上游标识（幂等 upsert 的另一半键）。
  source_service varchar(32) NOT NULL DEFAULT '',
  source_reference varchar(160) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一个店铺编码只能有一份档案（跨来源合并时以最后一次投影为准）。
  UNIQUE(tenant_id, shop_code)
);

-- 按平台分组用：租户 + 平台 + 店铺。
CREATE INDEX reporting_store_profile_by_platform
  ON reporting_store_profile(tenant_id, platform_code, shop_code);

-- ---------------------------------------------------------------------------
-- 2. reporting_store_metric_definition —— 店铺对比的指标口径唯一表
-- ---------------------------------------------------------------------------
--
-- ⚠️ **不预置任何一行**：口径是**租户自己**的（13.1.1.1 已经是这个纪律）。
--   服务层在第一次投影时按标准口径播种一份（播种内容是服务层的常量，**不是数据源**）。
--
-- ⚠️ **派生型的分子分母必须同在本表内已登记**：DB 触发器钉死 ⇒ 口径不成立时写不进来。
CREATE TABLE reporting_store_metric_definition (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  metric_code varchar(48) NOT NULL,
  display_name varchar(80) NOT NULL,
  -- 单位：CURRENCY 金额 / COUNT 笔数 / QUANTITY 数量 / PERCENT 百分比 / RATIO 倍数。
  unit text NOT NULL CHECK(unit IN ('CURRENCY','COUNT','QUANTITY','PERCENT','RATIO')),
  aggregation text NOT NULL
    CHECK(aggregation IN ('SUM','LAST','DERIVED_DIFF','DERIVED_RATIO','WINDOW_GROWTH')),
  -- 派生型的分子 / 分母（SUM / LAST / WINDOW_GROWTH 型恒为空）。
  numerator_metric_code varchar(48),
  denominator_metric_code varchar(48),
  -- **窗口环比的被比较指标**：WINDOW_GROWTH 型必填（它比的是哪个指标的两个窗口），
  -- 其它聚合方式恒为空。
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
    (aggregation IN ('DERIVED_DIFF','DERIVED_RATIO')
       AND numerator_metric_code IS NOT NULL AND denominator_metric_code IS NOT NULL
       AND base_metric_code IS NULL)
    OR (aggregation = 'WINDOW_GROWTH'
       AND numerator_metric_code IS NULL AND denominator_metric_code IS NULL
       AND base_metric_code IS NOT NULL)
    OR (aggregation IN ('SUM','LAST')
       AND numerator_metric_code IS NULL AND denominator_metric_code IS NULL
       AND base_metric_code IS NULL)
  )
);

-- ---------------------------------------------------------------------------
-- 3. reporting_store_metric_fact —— 店铺维度投影事实
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个（店铺, 指标, 日, 币种）的取值。
--
-- ⚠️ **流量型指标（成交金额 / 订单量 / 退货额 / 广告花费）按日分行**：
--   窗口内的值是这些行的 SUM ⇒ 任意窗口都能算，不需要「按窗口重新投影一次」。
--
-- ⚠️ **金额一律 numeric，禁浮点**：value numeric(24,4)、quantity numeric(19,6)。
--
-- ⚠️ **口径码必须已登记**：DB 触发器钉死 ⇒ 事实不能自己发明指标码（口径唯一）。
CREATE TABLE reporting_store_metric_fact (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  shop_code varchar(64) NOT NULL CHECK(btrim(shop_code) <> ''),
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

-- 幂等键：同一 (租户, 店铺, 指标, 日, 币种, 来源服务, 上游行) 只有一条事实。
CREATE UNIQUE INDEX reporting_store_metric_fact_identity
  ON reporting_store_metric_fact(
    tenant_id, shop_code, metric_code, bucket_date, currency, source_service, source_reference);

-- 按窗口聚合用：租户 + 指标 + 日 + 币种。
CREATE INDEX reporting_store_metric_fact_by_metric
  ON reporting_store_metric_fact(tenant_id, metric_code, bucket_date, currency);
-- 按店铺钻选用：租户 + 店铺 + 日。
CREATE INDEX reporting_store_metric_fact_by_store
  ON reporting_store_metric_fact(tenant_id, shop_code, bucket_date);

-- ===========================================================================
-- 第二节：数据库不变量（把蓝图的话变成数据层做不到的事）
-- ===========================================================================

-- (a) 派生型指标的分子分母必须**真的存在**（否则口径是一句空话）；
--     WINDOW_GROWTH 型则要求它**被比较的指标**（base_metric_code）已登记。
CREATE OR REPLACE FUNCTION guard_reporting_store_metric_definition_ref() RETURNS trigger AS $$
BEGIN
  IF NEW.aggregation = 'WINDOW_GROWTH' THEN
    IF NOT EXISTS (
      SELECT 1 FROM reporting_store_metric_definition
       WHERE tenant_id = NEW.tenant_id AND metric_code = NEW.base_metric_code) THEN
      RAISE EXCEPTION '指标 % 的被比较指标 % 尚未定义，口径不成立', NEW.metric_code, NEW.base_metric_code
        USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
  END IF;
  IF NEW.aggregation NOT IN ('DERIVED_DIFF','DERIVED_RATIO') THEN
    RETURN NEW;
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM reporting_store_metric_definition
     WHERE tenant_id = NEW.tenant_id AND metric_code = NEW.numerator_metric_code) THEN
    RAISE EXCEPTION '指标 % 的分子指标 % 尚未定义，口径不成立', NEW.metric_code, NEW.numerator_metric_code
      USING ERRCODE = 'check_violation';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM reporting_store_metric_definition
     WHERE tenant_id = NEW.tenant_id AND metric_code = NEW.denominator_metric_code) THEN
    RAISE EXCEPTION '指标 % 的分母指标 % 尚未定义，口径不成立', NEW.metric_code, NEW.denominator_metric_code
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER reporting_store_metric_definition_ref
  BEFORE INSERT OR UPDATE ON reporting_store_metric_definition
  FOR EACH ROW EXECUTE FUNCTION guard_reporting_store_metric_definition_ref();

-- (b) 事实行的**指标码必须已登记**：口径表里没有的指标码写不进来。
CREATE OR REPLACE FUNCTION guard_reporting_store_metric_fact_known() RETURNS trigger AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM reporting_store_metric_definition
     WHERE tenant_id = NEW.tenant_id AND metric_code = NEW.metric_code) THEN
    RAISE EXCEPTION '指标码 % 尚未在店铺对比口径表中登记，不能落事实', NEW.metric_code
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER reporting_store_metric_fact_known
  BEFORE INSERT OR UPDATE ON reporting_store_metric_fact
  FOR EACH ROW EXECUTE FUNCTION guard_reporting_store_metric_fact_known();

-- ===========================================================================
-- 第三节：多租户（RLS isolation）+ 授权
-- ===========================================================================

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'reporting_store_profile',
    'reporting_store_metric_definition',
    'reporting_store_metric_fact'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_store_profile,
  reporting_store_metric_definition,
  reporting_store_metric_fact TO reporting_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_store_profile,
  reporting_store_metric_definition,
  reporting_store_metric_fact TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM reporting_app;

-- ===========================================================================
-- 第四节：留给下游的口子（不是本模块的缺口）
-- ===========================================================================
--
--   * **13.1.2.1 目标设定 / 13.1.2.2 达成追踪（未开发）**：目标要挂在店铺上，
--     届时读本表的 shop_code 维度事实即可，**不需要**改结构。
--   * **13.2.2.1 店铺与站点（未开发）**：它管「店铺与站点」的明细维度；
--     本表只存**对比用的店铺维度聚合**，两边不重叠。
--   * **13.5.1.1 库存分析（未开发）**：库存周转的店铺维度依赖 warehouse 的
--     `inventory_balance` 带 shop 维度 —— **当前上游没有**（只有 legal_entity_id），
--     所以店铺维度的库存周转在 13.1.1.2 的 capabilities 里**如实报为不可得**，
--     等上游补齐维度后同一张事实表就能直接承接，**不需要**改结构。
