-- ===========================================================================
-- V376 · reporting_projection —— 13 利润与经营分析服务的第一批表 + 13.1.1.1 经营总览
-- ===========================================================================
--
-- **本文件同时承担两件事**（新服务只有一次「服务引导」的机会，合在一起最省事）：
--
--   1. **服务引导**：reporting 是蓝图 13 卷「后端归属」写死的服务
--      （唯一拥有经营看板、销量/产品/利润/库存分析、自定义报表与预警订阅）。
--      本仓此前**不存在** backend/services/reporting/，卷 13 的 23 个模块全被卡住。
--
--   2. **13.1.1.1 经营总览**（蓝图 13-profit-and-analytics.md）：
--      「展示公司/法人/店铺维度的核心经营指标：销售额、订单量、销量、毛利额、毛利率、
--        广告花费、净利、退货率、库存总额、应收余额，支持时间对比与环比同比，
--        显示数据更新时间。」
--
-- ---------------------------------------------------------------------------
-- 0. 这个服务**刻意不做**什么（卷 13 卷首「不做」原文）
-- ---------------------------------------------------------------------------
--
--   * **不产生业务事实、不做业务写入**。
--     ⇒ 本文件里**没有任何一张表**能存业务事实：只有**投影**（可从事件重建的读模型）。
--       唯一允许写的是 `reporting_metric_fact` / `reporting_drilldown_fact` /
--       `reporting_projection_event` / `reporting_projection_cursor` 四张**投影与账本**表，
--       写入者永远是本服务的投影器，**没有任何端点能往里塞业务事实**。
--
--   * **口径唯一** ⇒ 指标定义（分子 / 分母 / 聚合方式 / 单位）**只存在一份**：
--     `reporting_metric_definition`。查询 SQL **不重算口径**，只按定义聚合。
--     比率型指标（毛利率 / 退货率）在这里写成**分子分母的引用**，不写公式 SQL。
--
--   * **可下钻** ⇒ 每个汇总数字都能钻到构成明细 ⇒ `reporting_drilldown_fact`
--     记着「哪个订单 / 哪个 SKU / 哪个活动」贡献了这笔数字。
--
--   * **时区与币种明确** ⇒ 事实行带 `timezone` 与 `currency`；跨币种折算用
--     `reporting_currency_rate_snapshot`（**汇率快照 + 时点**，事后可复盘）。
--
--   * **数据延迟要显示** ⇒ 事实行带 `updated_at`，查询返回 `dataUpdatedAt`（最近投影时刻）。
--
-- ---------------------------------------------------------------------------
-- 1. 表清单
-- ---------------------------------------------------------------------------
--
--   reporting_metric_definition   —— 指标口径唯一表（每个租户一份标准口径）
--   reporting_projection_event    —— 已消费事件账本（**幂等键**在这张表上）
--   reporting_projection_cursor    —— 每条上游流的消费游标（**可重建**从这里回放）
--   reporting_projection_epoch     —— 重建纪元（账本只追加 ⇒ 清空重建靠换纪元而不是删账本）
--   reporting_metric_fact         —— 投影事实（流量型按贡献行存；存量型按日快照存）
--   reporting_drilldown_fact       —— 构成明细（可下钻到订单 / SKU / 活动 / 应收 / 库存）
--   reporting_currency_rate_snapshot —— 折算汇率快照（币种 → 本位币，含时点）
--
--   七张表全部 tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation + GRANT reporting_app。
--
-- ---------------------------------------------------------------------------
-- 2. 关键约束怎么落到数据层
-- ---------------------------------------------------------------------------
--
--   * 「金额一律 BigDecimal + NUMERIC，**禁止浮点**」
--     ⇒ value numeric(24,4)，quantity numeric(19,6)，rate numeric(19,8)。本仓没有一处 double。
--
--   * 「**一个指标只允许一处定义**」
--     ⇒ reporting_metric_definition 上 UNIQUE(tenant_id, metric_code)；
--       查询侧只读这张表，拿不到定义就没有那个指标（**不猜、不重算**）。
--
--   * 「**同一个事件不重复投影**」
--     ⇒ reporting_projection_event 上 UNIQUE(tenant_id, source_service, source_event_id)：
--       同一事件重投只会被跳过，投影不会翻倍。
--
--   * 「**投影可重建**」
--     ⇒ 清掉事实表 + 构成明细 + 游标并把**纪元 +1**后，按同一套规范化 envelope 重放即可回到
--       同一状态。游标记的是「读到哪了」，不是「数据在哪」—— 它只是加速重建的检查点。
--       ⚠️ **账本行不删**（它是举证凭据，DB 层 REVOKE 了 UPDATE/DELETE）：换纪元就等于
--       「同一批事件在下一轮重新投一遍」，旧账本原样留着作为上一轮投过什么的证据。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. reporting_metric_definition —— 指标口径唯一表
-- ---------------------------------------------------------------------------
--
-- **这张表是「口径唯一」这条红线的物化形态。**
--
-- 一行 = 一个指标。四个字段决定它怎么被算出来：
--   aggregation = 'SUM'            流量型：窗口内把贡献行**相加**
--   aggregation = 'LAST'            存量型：取窗口内**最近一天**的快照值
--   aggregation = 'DERIVED_DIFF'    派生差：numerator_metric_code − denominator_metric_code
--   aggregation = 'DERIVED_RATIO'   派生比：numerator_metric_code ÷ denominator_metric_code
--
-- 派生型指标**只写引用、不写公式**：分子分母必须是本表里已有的指标码，
-- 由 CHECK + 触发器钉住 ⇒ 口径改一处，所有引用它的地方自动跟着改。
--
-- ⚠️ **不预置任何一行**：指标定义是**租户自己**的口径（同一个指标，跨币种怎么折算、
-- 毛利率按含税还是不含税算，都是租户的事）。服务层在第一次投影时按标准口径
-- 播种一份（播种内容写死在服务层的常量里，**不是数据源**—— 数据仍然全部来自上游）。
CREATE TABLE reporting_metric_definition (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 指标码：全大写下划线，本表内唯一。
  metric_code varchar(48) NOT NULL,
  display_name varchar(80) NOT NULL,
  -- 单位：CURRENCY 金额 / COUNT 笔数 / QUANTITY 数量 / PERCENT 百分比。
  unit text NOT NULL CHECK(unit IN ('CURRENCY','COUNT','QUANTITY','PERCENT')),
  aggregation text NOT NULL
    CHECK(aggregation IN ('SUM','LAST','DERIVED_DIFF','DERIVED_RATIO')),
  -- 派生型的分子 / 分母（SUM / LAST 型恒为空）。
  numerator_metric_code varchar(48),
  denominator_metric_code varchar(48),
  -- 是否涉币：涉币指标**不跨币种相加**，只按币种分别报。
  currency_aware boolean NOT NULL DEFAULT true,
  -- 口径说明：页面上原样显示，让用户知道这个数字是怎么来的。
  description varchar(500) NOT NULL DEFAULT '',
  -- 上游归属模块（如 '12.5.1.1'）。**空串 = 上游已在本服务内闭环**。
  owner_module varchar(16) NOT NULL DEFAULT '',
  enabled boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一个指标码只能有一份定义：口径唯一。
  UNIQUE(tenant_id, metric_code),
  -- 派生型必须写全分子分母；非派生型不许写（否则「口径」就有两处）。
  CHECK(
    (aggregation IN ('DERIVED_DIFF','DERIVED_RATIO')
       AND numerator_metric_code IS NOT NULL AND denominator_metric_code IS NOT NULL)
    OR (aggregation IN ('SUM','LAST')
       AND numerator_metric_code IS NULL AND denominator_metric_code IS NULL)
  )
);

-- ---------------------------------------------------------------------------
-- 2. reporting_projection_event —— 已消费事件账本（幂等键在这张表上）
-- ---------------------------------------------------------------------------
--
-- 与 finance 的 finance_receivable_event / trade 的 b2b_order_event 同一手法：
-- 事件流是「当时发生了什么」的**唯一**出处，只能追加。
--
-- ⚠️ **source_event_id 的语义**：它标识「上游那一条事实的某个版本」。
--   拉取适配器用「内容寻址」生成它（上游行主键 + 内容摘要）⇒
--   * 同一行内容没变 ⇒ 事件 id 不变 ⇒ **跳过**（投影不会翻倍）；
--   * 同一行内容变了 ⇒ 事件 id 变 ⇒ 重新投影 ⇒ 事实行被**覆盖**（不是追加）。
--   这样「重复拉取」与「上游改了」两种情况都用同一个幂等键表达，不需要两套机制。
CREATE TABLE reporting_projection_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 上游服务名：trade / channel / finance / warehouse / catalog / external。
  source_service varchar(32) NOT NULL,
  -- 上游事件 id（幂等键的另一半）。
  source_event_id varchar(160) NOT NULL,
  -- 规范化后的事件类型（口径由本服务定义，见 ReportingProjectionModels）。
  event_type varchar(48) NOT NULL,
  aggregate_id uuid,
  aggregate_version bigint NOT NULL DEFAULT 0,
  -- 事实发生的时刻（上游事实的时点，不是投影时刻）。
  occurred_at timestamptz NOT NULL,
  -- 投影落库时刻：**「数据更新时间」就取这里**（卷 13 卷首「数据延迟要显示」）。
  applied_at timestamptz NOT NULL DEFAULT now(),
  -- 本次投影写了几条事实。
  fact_count int NOT NULL DEFAULT 0 CHECK(fact_count >= 0),
  note varchar(300) NOT NULL DEFAULT '',
  -- **重建纪元**：同一个事件在纪元 N 与纪元 N+1 是两条不同的账本行
  -- （清空重建靠这个把「已经投过」这件事从唯一键里摘出去，账本本身仍然只追加）。
  epoch int NOT NULL DEFAULT 0 CHECK(epoch >= 0),
  UNIQUE(tenant_id, id),
  -- **幂等键**：同一个上游事件只会被投影一次（同一纪元内）。
  UNIQUE(tenant_id, source_service, source_event_id, epoch)
);

-- ---------------------------------------------------------------------------
-- 3. reporting_projection_cursor —— 消费游标
-- ---------------------------------------------------------------------------
--
-- 一行 = 「某个租户在某个上游服务的某条流上读到哪了」。
--
-- ⚠️ **游标是检查点，不是数据来源**：投影数据在事实表里，删掉游标不会丢数据，
-- 删掉事实表 + 游标 + 账本才是「清空重建」。游标的作用是让「重建」可以增量推进，
-- 也是给运维看的「上次读到哪」。
CREATE TABLE reporting_projection_cursor (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  source_service varchar(32) NOT NULL,
  -- 流名：同一个上游服务可以有多条流（如 trade 的订单流 / 售后流）。
  stream varchar(64) NOT NULL DEFAULT 'default',
  last_source_event_id varchar(160) NOT NULL DEFAULT '',
  last_occurred_at timestamptz,
  processed_count bigint NOT NULL DEFAULT 0 CHECK(processed_count >= 0),
  skipped_count bigint NOT NULL DEFAULT 0 CHECK(skipped_count >= 0),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, source_service, stream)
);

-- ---------------------------------------------------------------------------
-- 3b. reporting_projection_epoch —— 重建纪元
-- ---------------------------------------------------------------------------
--
-- **为什么需要它**：事件账本 `reporting_projection_event` 在下面被 **REVOKE 了
-- UPDATE/DELETE**（它是「举证凭据」，只能追加）。可是「报表是投影，**可从事件重建**」
-- 意味着清空读模型之后要把同一批事件**再投一遍** —— 而账本里明明记着「这批事件投过了」。
--
-- ⇒ 抹不掉就不能重投 ⇒ **换个纪元**：同一个 `source_event_id` 在纪元 N 与纪元 N+1
-- 是**两条不同的账本行**（唯一键含 epoch）。清空 = 清事实 + 清构成明细 + 清游标 +
-- **纪元 +1**（账本原样留着，作为「上一轮投过什么」的证据）。
--
-- 一行 = 一个租户当前所处的纪元。
CREATE TABLE reporting_projection_epoch (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 纪元号：单调递增，每清空一次 +1。0 = 首轮。
  epoch int NOT NULL DEFAULT 0 CHECK(epoch >= 0),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, epoch)
);

-- ---------------------------------------------------------------------------
-- 4. reporting_metric_fact —— 投影事实
-- ---------------------------------------------------------------------------
--
-- 两种粒度共存，靠 source_reference 区分：
--
--   * **流量型指标**（销售额 / 订单量 / 销量 / 广告花费 / 成本 / 退货额 …）
--     一行 = 一条上游事实的贡献（一个订单、一条订单行、一个广告活动行）。
--     查询时按窗口 SUM ⇒ 「销售额」是这些贡献行之和。
--
--   * **存量型指标**（应收余额 / 库存总额）
--     一行 = **一天一个快照**，source_reference 恒为 'SNAPSHOT'。
--     查询时取窗口内**最近一天**的值（aggregation='LAST'）——
--     余额不能累加：把十天的应收余额加起来没有任何含义。
--
-- ⚠️ **UNIQUE 里 legal_entity_id / shop_id 用 coalesce**：Postgres 的 UNIQUE 把 NULL 视为互不相等，
--   「公司级」行（两个维度都为空）会因为 NULL != NULL 而插出第二条同样的行。
--   用表达式唯一索引把 NULL 归一成零 uuid，幂等键才真的成立。
CREATE TABLE reporting_metric_fact (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 维度：公司 / 法人 / 店铺。
  scope_type text NOT NULL CHECK(scope_type IN ('COMPANY','LEGAL_ENTITY','SHOP')),
  legal_entity_id uuid,
  shop_id uuid,
  -- 指标码：与 reporting_metric_definition.metric_code 对应（**逻辑引用**，不建外键——
  -- 口径表可能被重建，事实表不该被口径变更卡住）。
  metric_code varchar(48) NOT NULL,
  -- 事实归属的日历日（按记录的 timezone 分桶，不是按 UTC 截断）。
  bucket_date date NOT NULL,
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  value numeric(24,4) NOT NULL,
  -- 分桶所用时区（跨时区店铺汇总必须显示所用时区）。
  timezone varchar(64) NOT NULL DEFAULT 'UTC',
  -- 来源服务与上游行标识：幂等 upsert 的另一半键。
  source_service varchar(32) NOT NULL,
  source_reference varchar(160) NOT NULL DEFAULT '',
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id)
);

-- 幂等键：同一 (租户, 维度, 指标, 日, 币种, 来源服务, 上游行) 只有一条事实。
CREATE UNIQUE INDEX reporting_metric_fact_identity
  ON reporting_metric_fact(
    tenant_id, scope_type,
    coalesce(legal_entity_id, '00000000-0000-0000-0000-000000000000'::uuid),
    coalesce(shop_id, '00000000-0000-0000-0000-000000000000'::uuid),
    metric_code, bucket_date, currency, source_service, source_reference);

-- 按窗口聚合用：租户 + 指标 + 日 + 币种。
CREATE INDEX reporting_metric_fact_by_metric
  ON reporting_metric_fact(tenant_id, metric_code, bucket_date, currency);
-- 按维度钻选用：租户 + 维度 + 日。
CREATE INDEX reporting_metric_fact_by_scope
  ON reporting_metric_fact(tenant_id, scope_type, legal_entity_id, shop_id, bucket_date);

-- ---------------------------------------------------------------------------
-- 5. reporting_drilldown_fact —— 构成明细（可下钻）
-- ---------------------------------------------------------------------------
--
-- 卷 13 卷首加粗句：「**可下钻**：每个汇总数字必须能钻到构成明细（哪个订单、哪个 SKU）。」
--
-- 一行 = 一条构成明细：它对某个 (维度, 指标, 日, 币种) 贡献了多少。
-- source_kind 决定它是哪一类构成：
--   ORDER 订单 / SKU 商品 / CAMPAIGN 广告活动 / RECEIVABLE 应收单 / STOCK 库存行。
CREATE TABLE reporting_drilldown_fact (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  scope_type text NOT NULL CHECK(scope_type IN ('COMPANY','LEGAL_ENTITY','SHOP')),
  legal_entity_id uuid,
  shop_id uuid,
  metric_code varchar(48) NOT NULL,
  bucket_date date NOT NULL,
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  source_kind varchar(32) NOT NULL
    CHECK(source_kind IN ('ORDER','SKU','CAMPAIGN','RECEIVABLE','STOCK')),
  -- 构成对象的标识与可读标签（哪个订单号 / 哪个 SKU 码 / 哪个活动）。
  reference_id varchar(160) NOT NULL,
  reference_label varchar(200) NOT NULL DEFAULT '',
  -- 数量型构成（销量、退货件数）。
  quantity numeric(19,6) NOT NULL DEFAULT 0,
  -- 金额型贡献。
  value numeric(24,4) NOT NULL DEFAULT 0,
  source_service varchar(32) NOT NULL,
  source_reference varchar(160) NOT NULL DEFAULT '',
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id)
);

-- 幂等键：同一构成明细只留一条（重投同一事件不会把金额加两遍）。
CREATE UNIQUE INDEX reporting_drilldown_fact_identity
  ON reporting_drilldown_fact(
    tenant_id, scope_type,
    coalesce(legal_entity_id, '00000000-0000-0000-0000-000000000000'::uuid),
    coalesce(shop_id, '00000000-0000-0000-0000-000000000000'::uuid),
    metric_code, bucket_date, currency, source_kind, reference_id, source_service, source_reference);

-- 下钻列表用：租户 + 指标 + 日 + 币种 + 贡献倒序。
CREATE INDEX reporting_drilldown_fact_by_metric
  ON reporting_drilldown_fact(tenant_id, metric_code, bucket_date, currency, value DESC);

-- ---------------------------------------------------------------------------
-- 6. reporting_currency_rate_snapshot —— 折算汇率快照
-- ---------------------------------------------------------------------------
--
-- 卷 13 卷首加粗句：「**时区与币种明确**：跨时区店铺汇总需显示所用时区，跨币种需显示折算汇率与时点。」
--
-- ⇒ 汇率**必须带时点**：今天汇率变了不能让上月的报表变。
--   这里存的是「某个时点、某个币种、某个本位币」的汇率；
--   投影事实**不存折算后的金额**（折算是**查询期**的事），只存原币金额 + 币种。
--   查询要给出跨币种合计时，按 as_of 取**最近的那条**汇率，并把它连同时点一起返回。
CREATE TABLE reporting_currency_rate_snapshot (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  base_currency char(3) NOT NULL CHECK(base_currency ~ '^[A-Z]{3}$'),
  rate numeric(19,8) NOT NULL CHECK(rate > 0),
  -- 汇率的时点（**必须显示**）。
  as_of timestamptz NOT NULL,
  source_service varchar(32) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, currency, base_currency, as_of),
  -- 不能拿自己折算自己（那会让「本位币合计」变成原币合计冒充）。
  CHECK(currency <> base_currency)
);

-- 取最近汇率用：租户 + 币种 + 本位币 + 时点倒序。
CREATE INDEX reporting_currency_rate_snapshot_lookup
  ON reporting_currency_rate_snapshot(tenant_id, currency, base_currency, as_of DESC);

-- ===========================================================================
-- 第二节：数据库不变量（把蓝图的话变成数据层做不到的事）
-- ===========================================================================

-- (a) 派生型指标的分子分母必须**真的存在**（否则口径是一句空话）。
--
--     ⚠️ 触发器要能在「定义行已被级联删除」时安静退出。
CREATE OR REPLACE FUNCTION guard_reporting_metric_definition_ref() RETURNS trigger AS $$
BEGIN
  IF NEW.aggregation NOT IN ('DERIVED_DIFF','DERIVED_RATIO') THEN
    RETURN NEW;
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM reporting_metric_definition
     WHERE tenant_id = NEW.tenant_id AND metric_code = NEW.numerator_metric_code) THEN
    RAISE EXCEPTION '指标 % 的分子指标 % 尚未定义，口径不成立', NEW.metric_code, NEW.numerator_metric_code
      USING ERRCODE = 'check_violation';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM reporting_metric_definition
     WHERE tenant_id = NEW.tenant_id AND metric_code = NEW.denominator_metric_code) THEN
    RAISE EXCEPTION '指标 % 的分母指标 % 尚未定义，口径不成立', NEW.metric_code, NEW.denominator_metric_code
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER reporting_metric_definition_ref
  BEFORE INSERT OR UPDATE ON reporting_metric_definition
  FOR EACH ROW EXECUTE FUNCTION guard_reporting_metric_definition_ref();

-- (b) 事实行的**指标码必须已登记**：口径表里没有的指标码写不进来。
--
--     「口径唯一」的另一面：事实不能自己发明指标码。
CREATE OR REPLACE FUNCTION guard_reporting_metric_fact_known() RETURNS trigger AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM reporting_metric_definition
     WHERE tenant_id = NEW.tenant_id AND metric_code = NEW.metric_code) THEN
    RAISE EXCEPTION '指标码 % 尚未在口径表中登记，不能落事实', NEW.metric_code
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER reporting_metric_fact_known
  BEFORE INSERT OR UPDATE ON reporting_metric_fact
  FOR EACH ROW EXECUTE FUNCTION guard_reporting_metric_fact_known();

CREATE OR REPLACE FUNCTION guard_reporting_drilldown_fact_known() RETURNS trigger AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM reporting_metric_definition
     WHERE tenant_id = NEW.tenant_id AND metric_code = NEW.metric_code) THEN
    RAISE EXCEPTION '指标码 % 尚未在口径表中登记，不能落构成明细', NEW.metric_code
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER reporting_drilldown_fact_known
  BEFORE INSERT OR UPDATE ON reporting_drilldown_fact
  FOR EACH ROW EXECUTE FUNCTION guard_reporting_drilldown_fact_known();

-- ===========================================================================
-- 第三节：多租户（RLS isolation）+ 授权
-- ===========================================================================

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'reporting_metric_definition','reporting_projection_event','reporting_projection_cursor',
    'reporting_projection_epoch',
    'reporting_metric_fact','reporting_drilldown_fact','reporting_currency_rate_snapshot'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_metric_definition, reporting_projection_event, reporting_projection_cursor,
  reporting_projection_epoch,
  reporting_metric_fact, reporting_drilldown_fact, reporting_currency_rate_snapshot TO reporting_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_metric_definition, reporting_projection_event, reporting_projection_cursor,
  reporting_projection_epoch,
  reporting_metric_fact, reporting_drilldown_fact, reporting_currency_rate_snapshot TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM reporting_app;

-- 「授权在前、收回在后」：事件账本与汇率快照是**举证凭据**，只能追加。
-- 收回之后，即使服务层写错（误 UPDATE / 误 DELETE）也改不动已经记下的历史。
REVOKE UPDATE,DELETE ON reporting_projection_event FROM reporting_app;
REVOKE UPDATE,DELETE ON reporting_currency_rate_snapshot FROM reporting_app;

-- ===========================================================================
-- 第四节：留给下游的口子（不是本模块的缺口）
-- ===========================================================================
--
--   * **13.1.1.2 多店对比（未开发）**：它读的是同一张 reporting_metric_fact
--     （按 shop_id 分组），**不需要**改结构。跨平台口径差异（亚马逊 GMV 与
--     TikTok GMV 不同）在**指标口径表**里加一行说明即可，不改事实表。
--
--   * **13.2.x 销量分析（未开发）**：需要更细的桶（按小时 / 按周）与异常标注。
--     reporting_metric_fact 的 bucket_date 是 date ⇒ 按周 / 按小时要另加时间粒度列，
--     本期**不加**（卷 13 卷首「一个指标只允许一处定义」优先于「一次把所有粒度都建出来」）。
--
--   * **13.4.x 利润分析（未开发）**：SKU 级利润需要 SKU 粒度的成本与收入，
--     已有 drilldown_fact（source_kind='SKU'）承载，无需改结构。
--
--   * **13.6.x 自定义分析（未开发）**：自助报表要的是「受控语义层」，
--     本表的 metric_definition 就是那份语义层（受控指标码 + 单位 + 口径），
--     13.6 只需要在它之上做查询编排。
