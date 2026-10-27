-- ===========================================================================
-- V394 · product_performance_analysis —— 13.3.1.1 产品表现分析
-- ===========================================================================
--
-- 蓝图原文（docs/product/capability-blueprint/13-profit-and-analytics.md:142-147）：
--
--   ### 13.3.1.1 产品表现分析
--   - **职责**：单产品的 360 度分析：销量、销售额、毛利、流量、转化、退货、库存、库龄、
--     广告表现、竞品对比，集成在同一页面滚动查看（避免页面来回跳）。
--   - **输入**：产品标识（SKU/SPU/Listing）；时间范围；对比基线。
--   - **输出**：多指标分析页 + 趋势 + 排名分位。
--   - **关系**：上游 = 全部业务域；下游 = 各业务域的处理页。**这是运营每天用得最多的页面**，
--     性能与信息密度优先，指标要可对比（环比/同层级排名）。
--
-- ---------------------------------------------------------------------------
-- 0. 这三张表是什么 / 不是什么
-- ---------------------------------------------------------------------------
--
--   reporting_product_performance_metric_definition
--       —— 本模块的**指标口径唯一表**（含「是否已交付」与 owner 模块）。
--   reporting_product_performance_fact
--       —— 本模块的**日粒度事实**（毛利分子 / 退货 / 流量 / 转化 / 广告花费）。
--   reporting_product_performance_snapshot
--       —— 本模块的**时点快照**（库存 / 库龄 / 跟卖价格 —— 这些没有「日粒度」语义）。
--
-- ⚠️ **本文件仍然只是投影**：业务事实仍然在各自的业务域（订单在 trade、成本在 finance、
--   库存在 warehouse、流量与广告在 channel）。本服务只**读**它们的授权只读 API，
--   把结果落成读模型。任何数字与业务系统冲突，**以业务系统为准**（卷 13 卷首原文）。
--
-- ---------------------------------------------------------------------------
-- 1. 🔴 为什么**不**复用 13.2.2.2 的 reporting_product_metric_fact
-- ---------------------------------------------------------------------------
--
--   13.2.2.2（V389）已经建了商品维度的三层档案与日事实表，本模块**只读**它们
--   （销量 / 销售额 / 订单数就来自那里）。但本模块**自己的事实必须另起一张表**：
--
--   ① **兄弟模块会按窗口删表**。13.2.2.2 的刷新里有
--      `delete from reporting_product_metric_fact where tenant_id = ? and bucket_date between ? and ?`
--      —— 它删的是**整张表在窗口内的全部行**。本模块的事实若写进那张表，
--      兄弟模块刷一次就把本模块的行抹掉 ⇒ 本模块的毛利 / 退货 / 流量会**静默变空**。
--   ② **口径归属不同**。销量 / 销售额 / 订单数的口径归属是 9.1.1.1（trade 订单明细）；
--      毛利 / 退货 / 流量 / 库龄的口径归属是 12.4 / 9 售后 / 4.5.1.1 / 7 库存。
--      两套口径各自登记在一张表里，谁也不覆盖谁。
--   ③ reporting 的纪律是**零 ALTER 既有表**（V376 / V389 都是纯 CREATE TABLE）。
--
--   ⇒ 本模块**另起一组表**，不 ALTER 13.2.2.2 的任何一张表，两边互不干扰。
--
-- ---------------------------------------------------------------------------
-- 2. 口径唯一：指标定义只有一处
-- ---------------------------------------------------------------------------
--
--   aggregation 取值：
--     SUM            流量型：窗口内把贡献行相加
--     DERIVED_DIFF   派生差：分子 − 分母
--     DERIVED_RATIO  派生比：分子 ÷ 分母
--
--   派生型只写**分子 / 分母引用**，不写公式 SQL ⇒ 口径改一处，引用它的地方自动跟着改。
--
-- 🔴 **派生指标可以跨口径表引用**：本模块的「毛利额 = 销售额 − 出库成本」里，
--   「销售额」的口径登记在 **13.2.2.2 的 reporting_product_metric_definition**（那是
--   商品维度口径的**唯一**登记处，重复登记就是第二个真相）。
--   ⇒ 本表上的触发器**两张口径表都查**：口径在服务内只定义一次。
--
-- 🔴 **available = false 的指标**：上游本轮未交付的能力（如 14.3.1.3 商品维度广告报表、
--   14.3.2.1 广告归因）在这里**显式登记 + 标注归属模块**，
--   页面上显示「本轮不可用 + 归谁管」，而不是显示 0 或者干脆不出现。
--
-- ---------------------------------------------------------------------------
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. reporting_product_performance_metric_definition —— 本模块指标口径唯一表
-- ---------------------------------------------------------------------------
--
-- ⚠️ **不预置任何一行**：口径是**租户自己**的（13.1.1.1 / 13.2.2.2 已经是这个纪律）。
--   服务层在第一次投影时按标准口径播种一份（播种内容是服务层的常量，**不是数据源**）。
CREATE TABLE reporting_product_performance_metric_definition (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  metric_code varchar(48) NOT NULL,
  display_name varchar(80) NOT NULL,
  -- 单位：CURRENCY 金额 / COUNT 次数 / QUANTITY 数量 / PERCENT 百分比。
  unit text NOT NULL CHECK(unit IN ('CURRENCY','COUNT','QUANTITY','PERCENT')),
  aggregation text NOT NULL
    CHECK(aggregation IN ('SUM','DERIVED_DIFF','DERIVED_RATIO')),
  -- 派生型的分子 / 分母（SUM 型恒为空）。
  numerator_metric_code varchar(48),
  denominator_metric_code varchar(48),
  -- 币种口径：SAME 同币种（默认）/ ANY 允许跨币种合计（只给与货币无关的量）。
  currency_scope text NOT NULL DEFAULT 'SAME' CHECK(currency_scope IN ('SAME','ANY')),
  -- 是否涉币：涉币指标**不跨币种相加**，只按币种分别报。
  currency_aware boolean NOT NULL DEFAULT true,
  -- 🔴 **本轮是否可用**：false ⇒ 上游本轮未交付，页面上显示「不可用 + 归谁管」，
  --   **不是 0**，也不是干脆不出现。
  available boolean NOT NULL DEFAULT true,
  -- 不可用时的归属模块（如 14.3.1.3 / 14.3.2.1）—— 页面据此显示「这归谁管」。
  owner_module varchar(16) NOT NULL DEFAULT '',
  -- 口径说明：页面上原样显示，让用户知道这个数字是怎么来的。
  description varchar(500) NOT NULL DEFAULT '',
  -- 不可用的原因（available = false 时必填）。
  availability_note varchar(500) NOT NULL DEFAULT '',
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
  -- 🔴 **金额类指标绝不允许跨币种合计**：currency_aware=true ⇒ currency_scope 必须是 SAME。
  CHECK(currency_aware = false OR currency_scope = 'SAME'),
  -- 🔴 **不可用就要说清为什么**：available=false ⇒ 必须写明原因与归属模块。
  CHECK(available = true OR (btrim(availability_note) <> '' AND btrim(owner_module) <> ''))
);

-- ---------------------------------------------------------------------------
-- 2. reporting_product_performance_fact —— 日粒度事实
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个（维度, 指标, 日, 币种）的取值。
--
-- ⚠️ **流量型指标按日分行**：窗口内的值是这些行的 SUM ⇒ 任意窗口都能算。
--
-- ⚠️ **currency 可空**：空 = **与币种无关**（曝光 / 点击 / Session / 件数），
--   非空 = 金额类指标按币种分别统计，**绝不跨币种相加**。
--   （13.2.2.2 那张表把币种定成 NOT NULL，是因为它的量全部来自订单行 ——
--   本模块的曝光 / 点击根本没有币种，硬塞一个币种反而是编造。）
--
-- ⚠️ **维度只存「上游原生粒度」**：流量 / 广告 / 跟卖价格是 **Listing 层**，
--   退货 / 成本是 **SKU 层**。SPU 与 Listing 的口径在**查询侧**按
--   「不重复计数」的原则算出来（同一 Listing 只算一次），
--   在落库时硬摊会把一个 Listing 的流量算两遍。
CREATE TABLE reporting_product_performance_fact (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  dimension_type varchar(16) NOT NULL CHECK(dimension_type IN ('SKU','SPU','LISTING')),
  dimension_id uuid NOT NULL,
  dimension_key varchar(160) NOT NULL CHECK(btrim(dimension_key) <> ''),
  metric_code varchar(48) NOT NULL,
  -- 事实归属的日历日（按记录的 timezone 分桶，不是按 UTC 截断）。
  bucket_date date NOT NULL,
  -- 空 = 与币种无关（曝光 / 点击 / Session / 件数）；非空 = 金额按币种分别统计。
  currency char(3) CHECK(currency IS NULL OR currency ~ '^[A-Z]{3}$'),
  value numeric(24,4) NOT NULL DEFAULT 0,
  -- 数量型取值（退货件数等）。金额型指标恒为 0。
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
-- ⚠️ currency 可空 ⇒ 唯一索引用 coalesce(currency,'')，
--   否则 NULL 行之间永远不冲突（PG 里 NULL 不等于 NULL）⇒ 重复刷新会翻倍。
CREATE UNIQUE INDEX reporting_product_performance_fact_identity
  ON reporting_product_performance_fact(
    tenant_id, dimension_type, dimension_id, metric_code, bucket_date, coalesce(currency,''),
    source_service, source_reference);

-- 按窗口聚合用：租户 + 维度 + 指标 + 日 + 币种。
CREATE INDEX reporting_product_performance_fact_by_dimension
  ON reporting_product_performance_fact(tenant_id, dimension_type, dimension_id, metric_code, bucket_date);

-- ---------------------------------------------------------------------------
-- 3. reporting_product_performance_snapshot —— 时点快照
-- ---------------------------------------------------------------------------
--
-- 库存（在手 / 预留 / 可用）、库龄（天数 / 档位）、跟卖价格（自家参考价 vs 跟卖价）
-- **没有「日粒度」语义**：它们是**某一时点的读数**。把它们塞进日事实表会让人
-- 以为「每天都有一个库存快照」，而真相是「只有刷新那一刻有」。
--
-- ⚠️ **text_value 存档位 / 卖家标识这类文字**：库龄档位（0-30 / 31-60 …）与
--   跟卖者身份是**分类信息**，不是数值 ⇒ 单独一列，不塞进 value。
CREATE TABLE reporting_product_performance_snapshot (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  dimension_type varchar(16) NOT NULL CHECK(dimension_type IN ('SKU','SPU','LISTING')),
  dimension_id uuid NOT NULL,
  dimension_key varchar(160) NOT NULL CHECK(btrim(dimension_key) <> ''),
  metric_code varchar(48) NOT NULL,
  -- 空 = 与币种无关（数量 / 天数 / 比例）；非空 = 金额按币种分别统计。
  currency char(3) CHECK(currency IS NULL OR currency ~ '^[A-Z]{3}$'),
  value numeric(24,4) NOT NULL DEFAULT 0,
  quantity numeric(19,6) NOT NULL DEFAULT 0,
  -- 分类信息（库龄档位 / 跟卖者标识 / 跟卖者性质）。
  text_value varchar(200) NOT NULL DEFAULT '',
  -- 🔴 **窗口区间**：广告活动报表是**窗口聚合**（上游按「活动 × 币种」聚合整个区间），
  --   不是日粒度 ⇒ 这一行属于哪个窗口必须记下来，否则当期窗口与基线窗口的行会互相覆盖。
  --   时点类指标（库存 / 库龄 / 跟卖）恒为空。
  window_from date,
  window_to date,
  -- 🔴 **投影时刻**：一律取 PG 的 now()（DB 钟域），不绑 JVM 墙钟。
  observed_at timestamptz NOT NULL DEFAULT now(),
  -- 上游观测时刻（如跟卖记录被发现的时间）。可空 ⇒ 没有观测就没有这一列。
  source_observed_at timestamptz,
  timezone varchar(64) NOT NULL DEFAULT 'UTC',
  -- 来源服务与上游行标识：幂等 upsert 的另一半键。
  source_service varchar(32) NOT NULL,
  source_reference varchar(160) NOT NULL DEFAULT '',
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id)
);

-- 幂等键：同一 (租户, 维度, 指标, 币种, 来源服务, 上游行, 窗口起日) 只有一条快照。
-- ⚠️ currency 可空 ⇒ 唯一索引用 coalesce(currency,'')；
-- ⚠️ window_from 可空 ⇒ 同样用 coalesce(...)，
--   否则时点类指标（库存 / 库龄 / 跟卖）的行永远不冲突、重复刷新会翻倍。
--   🔴 **不能用 coalesce(window_from::text,'')**：date → text 的转换函数是 STABLE
--   而不是 IMMUTABLE，PG 会拒绝这个索引（42P17）⇒ 这里用日期字面量兜底。
CREATE UNIQUE INDEX reporting_product_performance_snapshot_identity
  ON reporting_product_performance_snapshot(
    tenant_id, dimension_type, dimension_id, metric_code, coalesce(currency,''),
    coalesce(window_from, date '0001-01-01'), source_service, source_reference);

-- 按维度查快照用。
CREATE INDEX reporting_product_performance_snapshot_by_dimension
  ON reporting_product_performance_snapshot(tenant_id, dimension_type, dimension_id, metric_code);

-- ===========================================================================
-- 第二节：数据库不变量（把蓝图的话变成数据层做不到的事）
-- ===========================================================================

-- (a) 派生型指标的分子分母必须**真的存在**（否则口径是一句空话）。
--
-- 🔴 **两张口径表都查**：13.2.2.2 的商品维度口径（销售额 / 销量 / 订单数）登记在
--   reporting_product_metric_definition —— 本模块的「毛利额 = 销售额 − 出库成本」
--   直接引用它，**不重复登记**（重复登记就是第二个真相）。
CREATE OR REPLACE FUNCTION guard_reporting_product_performance_metric_ref() RETURNS trigger AS $$
DECLARE
  ref_found boolean;
BEGIN
  IF NEW.aggregation NOT IN ('DERIVED_DIFF','DERIVED_RATIO') THEN
    RETURN NEW;
  END IF;
  SELECT EXISTS (
    SELECT 1 FROM reporting_product_performance_metric_definition
     WHERE tenant_id = NEW.tenant_id AND metric_code = NEW.numerator_metric_code)
    OR EXISTS (
    SELECT 1 FROM reporting_product_metric_definition
     WHERE tenant_id = NEW.tenant_id AND metric_code = NEW.numerator_metric_code)
  INTO ref_found;
  IF NOT ref_found THEN
    RAISE EXCEPTION '指标 % 的分子指标 % 尚未定义，口径不成立', NEW.metric_code, NEW.numerator_metric_code
      USING ERRCODE = 'check_violation';
  END IF;
  SELECT EXISTS (
    SELECT 1 FROM reporting_product_performance_metric_definition
     WHERE tenant_id = NEW.tenant_id AND metric_code = NEW.denominator_metric_code)
    OR EXISTS (
    SELECT 1 FROM reporting_product_metric_definition
     WHERE tenant_id = NEW.tenant_id AND metric_code = NEW.denominator_metric_code)
  INTO ref_found;
  IF NOT ref_found THEN
    RAISE EXCEPTION '指标 % 的分母指标 % 尚未定义，口径不成立', NEW.metric_code, NEW.denominator_metric_code
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER reporting_product_performance_metric_ref
  BEFORE INSERT OR UPDATE ON reporting_product_performance_metric_definition
  FOR EACH ROW EXECUTE FUNCTION guard_reporting_product_performance_metric_ref();

-- (b) 事实行的**指标码必须已登记**，且**币种口径必须与口径一致**。
--
-- 🔴 「与币种无关」的指标（曝光 / 点击 / Session / 件数）**必须不带币种**，
--     涉币指标（金额 / 比例）**必须带币种** ⇒ 这条不变量写在 DB 层，
--     前端与查询侧都不可能再把「曝光」当成「USD 的曝光」。
CREATE OR REPLACE FUNCTION guard_reporting_product_performance_fact_known() RETURNS trigger AS $$
DECLARE
  definition reporting_product_performance_metric_definition%ROWTYPE;
BEGIN
  SELECT * INTO definition
    FROM reporting_product_performance_metric_definition
   WHERE tenant_id = NEW.tenant_id AND metric_code = NEW.metric_code;
  IF NOT FOUND THEN
    RAISE EXCEPTION '指标码 % 尚未在产品表现口径表中登记，不能落事实', NEW.metric_code
      USING ERRCODE = 'check_violation';
  END IF;
  IF definition.currency_aware AND NEW.currency IS NULL THEN
    RAISE EXCEPTION '指标 % 涉币，必须带币种，不能与币种无关', NEW.metric_code
      USING ERRCODE = 'check_violation';
  END IF;
  IF NOT definition.currency_aware AND NEW.currency IS NOT NULL THEN
    RAISE EXCEPTION '指标 % 与币种无关，不能带币种', NEW.metric_code
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER reporting_product_performance_fact_known
  BEFORE INSERT OR UPDATE ON reporting_product_performance_fact
  FOR EACH ROW EXECUTE FUNCTION guard_reporting_product_performance_fact_known();

-- (c) 快照行的**指标码必须已登记**（与事实行同一条红线）。
CREATE OR REPLACE FUNCTION guard_reporting_product_performance_snapshot_known() RETURNS trigger AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM reporting_product_performance_metric_definition
     WHERE tenant_id = NEW.tenant_id AND metric_code = NEW.metric_code) THEN
    RAISE EXCEPTION '指标码 % 尚未在产品表现口径表中登记，不能落快照', NEW.metric_code
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER reporting_product_performance_snapshot_known
  BEFORE INSERT OR UPDATE ON reporting_product_performance_snapshot
  FOR EACH ROW EXECUTE FUNCTION guard_reporting_product_performance_snapshot_known();

-- ===========================================================================
-- 第三节：多租户（RLS isolation）+ 授权
-- ===========================================================================

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'reporting_product_performance_metric_definition',
    'reporting_product_performance_fact',
    'reporting_product_performance_snapshot'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_product_performance_metric_definition,
  reporting_product_performance_fact,
  reporting_product_performance_snapshot TO reporting_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_product_performance_metric_definition,
  reporting_product_performance_fact,
  reporting_product_performance_snapshot TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM reporting_app;

-- ===========================================================================
-- 第四节：留给下游的口子（不是本模块的缺口）
-- ===========================================================================
--
--   * **13.3.1.2 生命周期与分层**：它要「按生命周期与表现分层商品」，输入是
--     **销量与毛利历史** —— 本模块的日事实（毛利按日、销量按日）正好是它的输入。
--   * **13.3.2.1 流量转化**：它要区分自然与广告流量 —— 本模块的广告活动关联
--     （活动 → Listing）落在快照的 text_value 里，可以被它引用。
--   * **14.2.1.x 商品维度广告效果**：SKU/SPU/Listing 三个维度的 ACOS / ROAS
--     归它所有 —— 本模块的日事实里已有 Listing 层的广告花费与曝光 / 点击，
--     它可以直接引用，不必再拉一遍上游。
