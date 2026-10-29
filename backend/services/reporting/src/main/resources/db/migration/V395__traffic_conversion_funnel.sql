-- ===========================================================================
-- V395 · traffic_conversion_funnel —— 13.3.2.1 流量转化
-- ===========================================================================
--
-- 蓝图原文（docs/product/capability-blueprint/13-profit-and-analytics.md:156）：
--
--   ### 13.3.2.1 流量转化
--   - **职责**：分析流量与转化：曝光、点击、Session、转化率、客单价，区分自然与广告流量，
--     计算转化漏斗。
--   - **输入**：平台流量数据同步；订单数据。
--   - **输出**：漏斗视图 + 转化率趋势 + 异常诊断。
--   - **关系**：上游 = 2 同步、14 广告；下游 = 13.3.1.1。**平台流量数据与内部订单数据口径不同**，
--     展示时必须标注来源与统计范围（页面 vs 订单）。
--
-- ---------------------------------------------------------------------------
-- 0. 🔴🔴 本文件最重要的一件事：**两套口径不许混算**
-- ---------------------------------------------------------------------------
--
--   蓝图那句加粗句是硬要求，不是提示语。本迁移把它落成**数据结构**：
--
--   * 每一条事实行都带 `traffic_source`（LISTING / AD / ORDER）—— **它是谁家的口径**；
--   * 每一条事实行都带 `caliber`（在口径表里）—— PLATFORM 平台口径 / ORDER 订单口径；
--   * 每一条事实行都带 `bucket_basis`（PLATFORM_DAY / UTC_DAY）—— **统计日是按谁的日历切的**。
--
--   ⇒ 「曝光 / 点击 / Session / 平台成交量」是**平台口径**（页面口径，来自 2 同步的
--     listing 表现与 14 广告的广告报表）；
--     「内部订单笔数 / 内部订单金额」是**订单口径**（来自 9 订单）。
--     两个口径的统计范围不同（平台按平台自然日、按平台自己的退货/取消口径；
--     内部订单按本系统单据、按 UTC 自然日）⇒ **绝不相加、相除当同一个数**。
--
--   🔴 **客单价与转化率的口径因此是分开的**：
--     平台口径客单价 = 平台成交金额 ÷ 平台成交件数；
--     订单口径客单价 = 内部订单金额 ÷ 内部订单笔数。
--     两个数**都在报表里**，但每个都标着自己的口径 —— 不是二选一，也不是混在一起。
--
--   🔴 **订单口径没有 Session**：内部订单不带访问行为 ⇒ 订单口径的「转化率」**算不出来**
--     （分母缺失不是 0）。报表里那一格是「不可比」，不是 0%。
--
-- ---------------------------------------------------------------------------
-- 1. 这三张表是什么 / 不是什么
-- ---------------------------------------------------------------------------
--
--   reporting_traffic_conversion_metric_definition —— 口径唯一表（一个指标只允许一处定义）
--   reporting_traffic_conversion_fact                —— 每日流量与转化事实（按来源口径分行）
--   reporting_traffic_conversion_drilldown_fact      —— 构成明细（可下钻到 listing / 广告活动 / 订单）
--
-- ⚠️ **本文件仍然只是投影**：平台流量数据在 channel（2 同步的平台表现、14 广告的活动报表），
--   内部订单在 trade（9 订单）。本服务只**读**它们的授权只读 API，把结果落成读模型。
--   任何数字与业务系统冲突，**以业务系统为准**（卷 13 卷首原文）。
--
-- ⚠️ **零 ALTER 既有表**：reporting 自己的纪律是「另起一组表」（与 13.1.1.1 / 13.2.2.1 / 13.2.2.2
--   同纪律）⇒ 本模块的表与兄弟模块互不干扰，也不动 V376 的七张表。
--
-- ---------------------------------------------------------------------------
-- 2. 口径唯一：指标定义只有一处
-- ---------------------------------------------------------------------------
--
--   aggregation 取值：
--     SUM            流量型：窗口内把贡献行相加
--     DERIVED_RATIO  派生比：numerator_metric_code ÷ denominator_metric_code
--     DERIVED_DIFF   派生差：numerator_metric_code − denominator_metric_code
--
--   派生型只写**引用**，不写公式 SQL ⇒ 口径改一处，引用它的地方自动跟着改。
--
-- 🔴 **DERIVED_DIFF 有一处跨来源**：自然流量 = 平台商品口径 − 广告口径。
--   它的分子分母**来自两个不同的 traffic_source** ⇒ 这就是「区分自然与广告流量」的落点：
--   自然流量是**推导值**（两套平台口径相减的残余），不是平台直接给出的数。
--   DB 层保证不了「相减不为负」（那取决于两套口径的统计范围），所以**负数在服务层判为
--   「口径冲突」并如实报出来**，绝不悄悄截成 0。
--
-- 🔴 **currency_aware 与事实行的币种形状钉在一起**：
--   涉币指标（金额）的事实行**必须**带 3 位大写币种码 ⇒ **不同币种的钱永远不可能被加到一起**
--   （触发器钉死）。与货币无关的量（曝光 / 点击 / Session / 件数）币种可以是空串、
--   也可以是 3 位币种码 —— 事实仍按币种分行存储，查询期走**跨币种合计**。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. reporting_traffic_conversion_metric_definition —— 13.3.2.1 的指标口径唯一表
-- ---------------------------------------------------------------------------
--
-- ⚠️ **不预置任何一行**：口径是**租户自己**的（13.1.1.1 / 13.1.1.2 / 13.2.1.1 / 13.2.2.1 /
--   13.2.2.2 都是这个纪律）。服务层在第一次投影时按标准口径播种一份（播种内容是服务层的常量，
--   **不是数据源**）。
CREATE TABLE reporting_traffic_conversion_metric_definition (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  metric_code varchar(48) NOT NULL,
  display_name varchar(80) NOT NULL,
  -- 单位：CURRENCY 金额 / COUNT 笔数 / QUANTITY 数量 / PERCENT 百分比。
  unit text NOT NULL CHECK(unit IN ('CURRENCY','COUNT','QUANTITY','PERCENT')),
  aggregation text NOT NULL
    CHECK(aggregation IN ('SUM','DERIVED_RATIO','DERIVED_DIFF')),
  -- 派生型的分子 / 分母（SUM 型恒为空）。
  numerator_metric_code varchar(48),
  denominator_metric_code varchar(48),
  -- 🔴 **口径来源**：PLATFORM 平台口径（页面口径）/ ORDER 订单口径。
  --   两个口径的统计范围不同，报表里每个指标都要标出自己的口径。
  caliber text NOT NULL CHECK(caliber IN ('PLATFORM','ORDER')),
  -- 🔴 **流量来源**：LISTING 平台商品口径 / AD 广告口径 / ORDER 订单口径。
  --   SUM 型必有；派生型为空（它的来源由分子分母决定，可能跨来源）。
  traffic_source varchar(16)
    CHECK(traffic_source IS NULL OR traffic_source IN ('LISTING','AD','ORDER')),
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
  -- SUM 型必须写清流量来源；派生型不许写（来源由分子分母决定）。
  CHECK(
    (aggregation = 'SUM'
       AND numerator_metric_code IS NULL AND denominator_metric_code IS NULL
       AND traffic_source IS NOT NULL)
    OR (aggregation IN ('DERIVED_RATIO','DERIVED_DIFF')
       AND numerator_metric_code IS NOT NULL AND denominator_metric_code IS NOT NULL
       AND traffic_source IS NULL)
  )
);

-- ---------------------------------------------------------------------------
-- 2. reporting_traffic_conversion_fact —— 每日流量与转化事实
-- ---------------------------------------------------------------------------
--
-- 一行 = **一天**（某个来源口径、某个店铺/平台、某个币种）的一组流量/转化量。
--
-- ⚠️ **事实按「日」落，不按「listing」落**：listing / 广告活动级别的构成放在
--   `reporting_traffic_conversion_drilldown_fact` 里。事实表只放**已经汇总过的日**——
--   这样「窗口内求和」永远是一条聚合查询，而明细仍然可下钻。
--
-- ⚠️ **统计日口径写在行上**（bucket_basis）：
--   PLATFORM_DAY = 平台自然日（listing 表现 / 广告报表各自的平台日）；
--   UTC_DAY      = 内部订单按 UTC 自然日。
--   两者**不是同一个日**，跨口径比较时必须知道这件事。
--
-- ⚠️ **金额一律 numeric，禁浮点**：value numeric(24,4)、quantity numeric(19,6)。
CREATE TABLE reporting_traffic_conversion_fact (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 🔴 **流量来源口径**：LISTING 平台商品口径 / AD 广告口径 / ORDER 订单口径。
  traffic_source varchar(16) NOT NULL
    CHECK(traffic_source IN ('LISTING','AD','ORDER')),
  metric_code varchar(48) NOT NULL,
  -- 统计日（**按 bucket_basis 声明的日历**切的）。
  stat_date date NOT NULL,
  -- 🔴 **统计日的日历口径**：PLATFORM_DAY 平台自然日 / UTC_DAY 内部订单的 UTC 自然日。
  bucket_basis varchar(16) NOT NULL
    CHECK(bucket_basis IN ('PLATFORM_DAY','UTC_DAY')),
  -- 币种：**空串 = 与货币无关**（曝光 / 点击 / Session / 笔数）。
  --   🔴 空串不是「币种未知」——未知币种的金额**根本不落库**（触发器钉死）。
  -- ⚠️ **用 varchar(3) 而不是 char(3)**：char(3) 会把空串补成三个空格（bpchar），
  --   「与货币无关」这个形状就表达不出来了 ⇒ 这里必须能真的存下一个空串。
  currency varchar(3) NOT NULL DEFAULT '' CHECK(currency ~ '^([A-Z]{3})?$'),
  -- 金额型取值（金额型指标用这列；数量型指标恒为 0）。
  value numeric(24,4) NOT NULL DEFAULT 0,
  -- 数量型取值（曝光 / 点击 / Session / 件数 / 笔数）。金额型指标恒为 0。
  quantity numeric(19,6) NOT NULL DEFAULT 0,
  -- 店铺码 / 平台码（**可以为空**：广告报表按活动分组时拿不到店铺码，订单口径也没有店铺）。
  --   空串 = 上游没给，**绝不猜一个**。
  shop_code varchar(64) NOT NULL DEFAULT '',
  platform_code varchar(64) NOT NULL DEFAULT '',
  -- 来源服务与上游行标识：幂等 upsert 的另一半键。
  source_service varchar(32) NOT NULL,
  source_reference varchar(160) NOT NULL DEFAULT '',
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id)
);

-- 幂等键：同一 (租户, 来源, 指标, 日, 币种, 店铺, 平台, 来源服务, 上游行) 只有一条事实。
CREATE UNIQUE INDEX reporting_traffic_conversion_fact_identity
  ON reporting_traffic_conversion_fact(
    tenant_id, traffic_source, metric_code, stat_date, currency,
    shop_code, platform_code, source_service, source_reference);

-- 按窗口聚合用：租户 + 日 + 来源 + 指标。
CREATE INDEX reporting_traffic_conversion_fact_by_window
  ON reporting_traffic_conversion_fact(tenant_id, stat_date, traffic_source, metric_code);

-- ---------------------------------------------------------------------------
-- 3. reporting_traffic_conversion_drilldown_fact —— 构成明细（可下钻）
-- ---------------------------------------------------------------------------
--
-- 卷 13 卷首加粗句：「**可下钻**：每个汇总数字必须能钻到构成明细。」
--
-- 一行 = 一条构成明细：某个 listing / 某个广告活动 / 某张内部订单，对某个
-- （来源, 指标, 日, 币种）贡献了多少。
--
-- 🔴 **三行合一口径**：source_kind 决定它是哪一类构成：
--   LISTING     平台商品（平台口径的曝光 / 点击 / Session 来自哪个 listing）
--   AD_CAMPAIGN 广告活动（广告口径的曝光 / 点击 / 订单来自哪个活动）
--   ORDER       内部订单（订单口径的笔数 / 金额来自哪张单）
CREATE TABLE reporting_traffic_conversion_drilldown_fact (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  traffic_source varchar(16) NOT NULL
    CHECK(traffic_source IN ('LISTING','AD','ORDER')),
  metric_code varchar(48) NOT NULL,
  stat_date date NOT NULL,
  bucket_basis varchar(16) NOT NULL
    CHECK(bucket_basis IN ('PLATFORM_DAY','UTC_DAY')),
  currency varchar(3) NOT NULL DEFAULT '' CHECK(currency ~ '^([A-Z]{3})?$'),
  -- 构成类型：LISTING 平台商品 / AD_CAMPAIGN 广告活动 / ORDER 内部订单。
  source_kind varchar(16) NOT NULL CHECK(source_kind IN ('LISTING','AD_CAMPAIGN','ORDER')),
  -- 构成对象的标识与可读标签。
  reference_id varchar(160) NOT NULL CHECK(btrim(reference_id) <> ''),
  reference_label varchar(200) NOT NULL DEFAULT '',
  -- 归属的店铺 / 平台（上游没给就是空串，**绝不猜**）。
  shop_code varchar(64) NOT NULL DEFAULT '',
  platform_code varchar(64) NOT NULL DEFAULT '',
  -- 构成所在的订单号（订单口径的行有值）。
  order_no varchar(128) NOT NULL DEFAULT '',
  -- 🔴 **发生时刻（timestamptz，精确到瞬间）**：订单口径的行有值（下单时刻）。
  --   ⚠️ 必须用 {@code OffsetDateTime} 绑定并在 values 位置写 {@code ?::timestamptz} ——
  --   用 {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800s（东八区的时间会被记成 UTC）。
  occurred_at timestamptz,
  -- 数量型构成与金额型贡献。
  quantity numeric(19,6) NOT NULL DEFAULT 0,
  value numeric(24,4) NOT NULL DEFAULT 0,
  source_service varchar(32) NOT NULL,
  source_reference varchar(160) NOT NULL DEFAULT '',
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id)
);

-- 幂等键：同一条构成明细只留一条（重投同一批上游数据不会把数加两遍）。
CREATE UNIQUE INDEX reporting_traffic_conversion_drilldown_fact_identity
  ON reporting_traffic_conversion_drilldown_fact(
    tenant_id, traffic_source, metric_code, stat_date, currency,
    source_kind, reference_id, source_service, source_reference);

-- 下钻列表用：租户 + 来源 + 指标 + 日 + 贡献倒序。
CREATE INDEX reporting_traffic_conversion_drilldown_fact_by_metric
  ON reporting_traffic_conversion_drilldown_fact(
    tenant_id, traffic_source, metric_code, stat_date, value DESC, quantity DESC);

-- ===========================================================================
-- 第二节：数据库不变量（把蓝图的话变成数据层做不到的事）
-- ===========================================================================

-- (a) 派生型的分子分母必须**真的存在**（否则口径是一句空话）。
CREATE OR REPLACE FUNCTION guard_reporting_traffic_conversion_metric_definition_ref() RETURNS trigger AS $$
BEGIN
  IF NEW.aggregation NOT IN ('DERIVED_RATIO','DERIVED_DIFF') THEN
    RETURN NEW;
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM reporting_traffic_conversion_metric_definition
     WHERE tenant_id = NEW.tenant_id AND metric_code = NEW.numerator_metric_code) THEN
    RAISE EXCEPTION '指标 % 的分子指标 % 尚未定义，口径不成立', NEW.metric_code, NEW.numerator_metric_code
      USING ERRCODE = 'check_violation';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM reporting_traffic_conversion_metric_definition
     WHERE tenant_id = NEW.tenant_id AND metric_code = NEW.denominator_metric_code) THEN
    RAISE EXCEPTION '指标 % 的分母指标 % 尚未定义，口径不成立', NEW.metric_code, NEW.denominator_metric_code
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER reporting_traffic_conversion_metric_definition_ref
  BEFORE INSERT OR UPDATE ON reporting_traffic_conversion_metric_definition
  FOR EACH ROW EXECUTE FUNCTION guard_reporting_traffic_conversion_metric_definition_ref();

-- (b) 🔴 事实行的**指标码必须已登记**，且**来源口径与币种形状必须与口径一致**：
--
--     * 派生型指标**不许有事实行**（派生值是查询期算出来的，落库就成了第二个真相）；
--     * 事实行的 traffic_source 必须等于口径表里登记的来源（口径不能偷偷换来源）；
--     * 涉币指标的事实行**必须**带 3 位大写币种码（空串 = 与币种无关，不是「币种未知」）；
--     * 与货币无关的指标（曝光 / 点击 / Session / 件数）的事实行币种**可以**是空串，
--       **也可以**是 3 位币种码 —— 事实仍**按币种分行存储**（与 13.2.2.2 同纪律），
--       这样「客单价 = 金额 ÷ 件数」能按**同币种**取数；查询期件数走**跨币种合计**
--       （件数相加不会把两种货币的钱加起来）。
CREATE OR REPLACE FUNCTION guard_reporting_traffic_conversion_fact_known() RETURNS trigger AS $$
DECLARE
  def reporting_traffic_conversion_metric_definition%ROWTYPE;
BEGIN
  SELECT * INTO def FROM reporting_traffic_conversion_metric_definition
   WHERE tenant_id = NEW.tenant_id AND metric_code = NEW.metric_code;
  IF NOT FOUND THEN
    RAISE EXCEPTION '指标码 % 尚未在流量转化口径表中登记，不能落事实', NEW.metric_code
      USING ERRCODE = 'check_violation';
  END IF;
  IF def.aggregation <> 'SUM' THEN
    RAISE EXCEPTION '指标 % 是派生指标（%），只能在查询期算，不能落事实行', NEW.metric_code, def.aggregation
      USING ERRCODE = 'check_violation';
  END IF;
  IF def.traffic_source IS DISTINCT FROM NEW.traffic_source THEN
    RAISE EXCEPTION '事实行的来源口径 % 与指标 % 登记的来源口径 % 不一致', NEW.traffic_source, NEW.metric_code, def.traffic_source
      USING ERRCODE = 'check_violation';
  END IF;
  IF def.currency_aware AND NEW.currency !~ '^[A-Z]{3}$' THEN
    RAISE EXCEPTION '涉币指标 % 的事实行必须带 3 位大写币种码（空串 = 与币种无关，不是「币种未知」）', NEW.metric_code
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER reporting_traffic_conversion_fact_known
  BEFORE INSERT OR UPDATE ON reporting_traffic_conversion_fact
  FOR EACH ROW EXECUTE FUNCTION guard_reporting_traffic_conversion_fact_known();

-- (c) 构成明细与事实行同一条红线（指标码必须已登记）。
CREATE OR REPLACE FUNCTION guard_reporting_traffic_conversion_drilldown_fact_known() RETURNS trigger AS $$
DECLARE
  def reporting_traffic_conversion_metric_definition%ROWTYPE;
BEGIN
  SELECT * INTO def FROM reporting_traffic_conversion_metric_definition
   WHERE tenant_id = NEW.tenant_id AND metric_code = NEW.metric_code;
  IF NOT FOUND THEN
    RAISE EXCEPTION '指标码 % 尚未在流量转化口径表中登记，不能落构成明细', NEW.metric_code
      USING ERRCODE = 'check_violation';
  END IF;
  IF def.aggregation <> 'SUM' THEN
    RAISE EXCEPTION '指标 % 是派生指标（%），只能在查询期算，不能落构成明细', NEW.metric_code, def.aggregation
      USING ERRCODE = 'check_violation';
  END IF;
  IF def.traffic_source IS DISTINCT FROM NEW.traffic_source THEN
    RAISE EXCEPTION '构成明细的来源口径 % 与指标 % 登记的来源口径 % 不一致', NEW.traffic_source, NEW.metric_code, def.traffic_source
      USING ERRCODE = 'check_violation';
  END IF;
  IF def.currency_aware AND NEW.currency !~ '^[A-Z]{3}$' THEN
    RAISE EXCEPTION '涉币指标 % 的构成明细必须带 3 位大写币种码', NEW.metric_code
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER reporting_traffic_conversion_drilldown_fact_known
  BEFORE INSERT OR UPDATE ON reporting_traffic_conversion_drilldown_fact
  FOR EACH ROW EXECUTE FUNCTION guard_reporting_traffic_conversion_drilldown_fact_known();

-- ===========================================================================
-- 第三节：多租户（RLS isolation）+ 授权
-- ===========================================================================

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'reporting_traffic_conversion_metric_definition',
    'reporting_traffic_conversion_fact',
    'reporting_traffic_conversion_drilldown_fact'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_traffic_conversion_metric_definition,
  reporting_traffic_conversion_fact,
  reporting_traffic_conversion_drilldown_fact TO reporting_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_traffic_conversion_metric_definition,
  reporting_traffic_conversion_fact,
  reporting_traffic_conversion_drilldown_fact TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM reporting_app;

-- ===========================================================================
-- 第四节：留给下游的口子（不是本模块的缺口）
-- ===========================================================================
--
--   * **13.3.1.1 产品表现分析（未交付）**：它要「单产品 360 度」，其中流量与转化这一段
--     直接读本模块的 `reporting_traffic_conversion_drilldown_fact`（source_kind='LISTING'
--     的行就是「哪个 listing 的曝光/点击/Session」）—— **不需要**改结构。
--   * **13.3.2.2 关联营销效果（未交付）**：它要营销位的曝光点击与成交归因，
--     归因规则（末次点击 vs 首次点击）本模块**没有**（本模块不做归因）⇒ 它要另起归因事实。
--   * **14 广告（14.3.1.1 活动报表）**：本模块的广告口径流量来自它，
--     但**广告归因与预算调优不在本模块**（那是 14 的职责）。
