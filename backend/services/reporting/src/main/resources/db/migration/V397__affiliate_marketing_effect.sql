-- ===========================================================================
-- V397 · affiliate_marketing_effect —— 13.3.2.2 关联营销效果
-- ===========================================================================
--
-- 蓝图原文（docs/product/capability-blueprint/13-profit-and-analytics.md:163）：
--
--   ### 13.3.2.2 关联营销效果
--   - **职责**：分析站外/关联营销（关联视频、A+、Store 导流、社交媒体）的引流效果：
--     曝光、点击、加购、成交归因。
--   - **输入**：营销位配置与曝光点击数据；订单归因数据。
--   - **输出**：营销位效果报表 + ROI。
--   - **关系**：上游 = 4 刊登（营销模块）、14 广告；下游 = 13.3.1.1。
--     **归因需要明确规则**（末次点击 vs 首次点击），否则各人看到的数不一样。
--
-- ---------------------------------------------------------------------------
-- 0. 🔴🔴 本文件最重要的一件事：**归因口径必须钉在数据上**
-- ---------------------------------------------------------------------------
--
--   蓝图那句加粗句是硬要求，不是提示语。本迁移把它落成**数据结构**：
--
--   * 归因事实表 `reporting_affiliate_marketing_attribution_fact` **逐字保存上游归因记录
--     自带的规则快照**（归因模型 / 分摊权重基准 / 归因窗口 / 退款口径 / 佣金计提口径）——
--     **不用「当前规则」回填历史**：规则改了，历史必须还是历史那一条规则。
--
--   * 事实表 `reporting_affiliate_marketing_fact` 的每一行都带 `attribution_rule_code`
--     ⇒ **报表按 (营销位, 归因规则) 分列**，ROI 只在**同一条规则内部**相除。
--     两个规则的钱绝不会被加到一起再除。
--
--   * 派生指标（ROI / 点击率 / 归因订单率）**只写引用**（分子分母的指标码），
--     **DB 触发器拒绝给派生指标落事实行** ⇒ 派生值只能在查询期算，落库就成了第二个真相。
--
-- ---------------------------------------------------------------------------
-- 1. 这四张表是什么 / 不是什么
-- ---------------------------------------------------------------------------
--
--   reporting_affiliate_marketing_metric_definition —— 指标口径唯一表（一个指标只允许一处定义）
--   reporting_affiliate_marketing_slot                —— 营销位台账（营销位「配置」维表）
--   reporting_affiliate_marketing_fact                —— 每日营销位指标事实（曝光/点击/归因/投入）
--   reporting_affiliate_marketing_attribution_fact    —— 🔴 本模块**另起**的归因事实（带规则快照）
--
-- ⚠️ **为什么必须另起归因事实**：13.3.2.1 流量转化（V395）在第四节明写「
--   13.3.2.2 关联营销效果（未交付）：它要营销位的曝光点击与成交归因，
--   归因规则（末次点击 vs 首次点击）本模块**没有**（本模块不做归因）⇒ 它要另起归因事实。」
--   ⇒ 本表是**本模块自己的归因真相**，不指望兄弟模块的表里有归因。
--
-- ⚠️ **本文件仍然只是投影**：营销位与归因数据在 channel（14.5.2.1 带货归因 / 4 刊登内容回写 /
--   4.5.1.1 平台表现），内部订单在 trade（9 订单）。本服务只**读**它们的授权只读 API，
--   把结果落成读模型。任何数字与业务系统冲突，**以业务系统为准**（卷 13 卷首原文）。
--
-- ⚠️ **零 ALTER 既有表**：reporting 自己的纪律是「另起一组表」（与 13.1.1.1 / 13.2.2.1 /
--   13.3.2.1 同纪律）⇒ 本模块的表与兄弟模块互不干扰，也不动 V376 / V395 的表。
--
-- ===========================================================================
-- 2. 营销位类型与它们各自的**数据口径**
-- ===========================================================================
--
--   蓝图点名了四类营销位。它们**不是同一个口径**，本表逐类钉死：
--
--     RELATED_VIDEO 关联视频   —— 上游**没有**该营销位的配置与数据 ⇒ 本轮不可用
--     A_PLUS        A+         —— 落地商品口径（listing 表现是**落地页流量**，不是素材自身曝光）
--     STORE_TRAFFIC Store 导流 —— 上游**没有**该营销位的配置与数据 ⇒ 本轮不可用
--     SOCIAL_MEDIA  社交媒体   —— 达人归因口径（channel 归因记录，**带规则快照**）
--
--   ⚠️ **口径不同的两个数绝不混算**：达人归因口径的「成交归因」与落地商品口径的
--   「平台成交」是**两回事**，报表里分列，各带自己的口径标签。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. reporting_affiliate_marketing_metric_definition —— 指标口径唯一表
-- ---------------------------------------------------------------------------
--
-- ⚠️ **不预置任何一行**：口径是**租户自己**的（13.1.1.1 / 13.2.1.1 / 13.2.2.1 / 13.3.2.1
--   都是这个纪律）。服务层在第一次投影时按标准口径播种一份（播种内容是服务层的常量，
--   **不是数据源**）。
CREATE TABLE reporting_affiliate_marketing_metric_definition (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  metric_code varchar(48) NOT NULL,
  display_name varchar(80) NOT NULL,
  -- 单位：CURRENCY 金额 / COUNT 笔数 / QUANTITY 数量 / PERCENT 百分比。
  unit text NOT NULL CHECK(unit IN ('CURRENCY','COUNT','QUANTITY','PERCENT')),
  aggregation text NOT NULL CHECK(aggregation IN ('SUM','DERIVED_RATIO')),
  -- 派生型的分子 / 分母（SUM 型恒为空）。
  numerator_metric_code varchar(48),
  denominator_metric_code varchar(48),
  -- 是否涉币：涉币指标**不跨币种相加**，只按币种分别报。
  currency_aware boolean NOT NULL DEFAULT true,
  -- 🔴 **该指标是否依赖归因规则**：true ⇒ 事实行**必须**带 attribution_rule_code
  --   （否则报表里说不清这条数是用哪条归因规则算出来的）。
  attribution_bound boolean NOT NULL DEFAULT false,
  -- 🔴 **本轮是否可用**：false ⇒ 页面显示「本轮不可用 + 归谁管 + 当前替代口径」，
  --   **不是 0**，也不是不出现。
  available boolean NOT NULL DEFAULT true,
  -- 未交付段的责任模块（**空串 = 上游已在本服务内闭环**）。
  owner_module varchar(16) NOT NULL DEFAULT '',
  description varchar(500) NOT NULL DEFAULT '',
  enabled boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一个指标码只能有一份定义：口径唯一。
  UNIQUE(tenant_id, metric_code),
  -- SUM 型不许写分子分母；派生型必须写全。
  CHECK(
    (aggregation = 'SUM'
       AND numerator_metric_code IS NULL AND denominator_metric_code IS NULL)
    OR (aggregation = 'DERIVED_RATIO'
       AND numerator_metric_code IS NOT NULL AND denominator_metric_code IS NOT NULL)
  ),
  -- 🔴 不可用的指标**必须**写明归谁管 + 说明（不许悄悄留空）。
  CHECK(available OR (owner_module <> '' AND description <> ''))
);

-- ---------------------------------------------------------------------------
-- 2. reporting_affiliate_marketing_slot —— 营销位台账（营销位「配置」维表）
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个**营销位**（营销位配置维）。营销位只有**上游有留痕**才登记：
--
--   SOCIAL_MEDIA  —— channel 归因记录（达人 × 活动）⇒ 达人归因口径
--   A_PLUS        —— channel 内容回写**确实写过 A+ 字段**的 listing ⇒ 落地商品口径
--
-- ⚠️ **没有上游留痕的营销位不登记**：凭空造一个「关联视频营销位」就是编造。
--   RELATED_VIDEO / STORE_TRAFFIC 本轮**没有**任何上游留痕 ⇒ 台账里没有它们，
--   能力声明里显式登记「本轮不可用 + 归谁管」。
--
-- ⚠️ **slot_key 的形状**：
--   SOCIAL_MEDIA ⇒ 达人 id : 活动 id（**不含时间** ⇒ 同一对达人 × 活动只有一个营销位）
--   A_PLUS        ⇒ listing id
CREATE TABLE reporting_affiliate_marketing_slot (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 🔴 营销位类型（蓝图点名的四类，**封闭词表**）。
  slot_type varchar(24) NOT NULL
    CHECK(slot_type IN ('RELATED_VIDEO','A_PLUS','STORE_TRAFFIC','SOCIAL_MEDIA')),
  slot_key varchar(200) NOT NULL CHECK(btrim(slot_key) <> ''),
  slot_label varchar(200) NOT NULL DEFAULT '',
  -- 🔴 **数据口径**：INFLUENCER_ATTRIBUTION 达人归因口径（带规则快照）
  --   / LANDING_LISTING 落地商品口径（listing 表现）。两者**绝不混算**。
  caliber varchar(24) NOT NULL
    CHECK(caliber IN ('INFLUENCER_ATTRIBUTION','LANDING_LISTING')),
  -- 归属的店铺 / 平台（上游没给就是空串，**绝不猜一个**）。
  shop_code varchar(64) NOT NULL DEFAULT '',
  platform_code varchar(64) NOT NULL DEFAULT '',
  listing_id uuid,
  influencer_id uuid,
  campaign_id uuid,
  cooperation_id uuid,
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','PAUSED')),
  -- 来源服务与上游行标识（营销位「存在」的证据）。
  source_service varchar(32) NOT NULL,
  source_reference varchar(160) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一个营销位只登记一次（幂等 upsert 的另一半键）。
  UNIQUE(tenant_id, slot_type, slot_key)
);

-- 按类型取营销位用：租户 + 类型 + 标签。
CREATE INDEX reporting_affiliate_marketing_slot_by_type
  ON reporting_affiliate_marketing_slot(tenant_id, slot_type, slot_label);

-- ---------------------------------------------------------------------------
-- 3. reporting_affiliate_marketing_fact —— 每日营销位指标事实
-- ---------------------------------------------------------------------------
--
-- 一行 = **一天**（某个营销位、某个指标、某个币种）的一组量。
--
-- ⚠️ **事实按「日」落，不按「上游行」落**：上游归因记录的逐条明细放在
--   `reporting_affiliate_marketing_attribution_fact` 里。事实表只放**已经汇总过的日** ——
--   这样「窗口内求和」永远是一条聚合查询，而明细仍然可下钻。
--
-- ⚠️ **🔴 归因规则码是事实行的一部分**（`attribution_rule_code`）：
--   报表按 (营销位, 归因规则) 分列 ⇒ **ROI 只在同一条规则内部相除**，
--   两个规则的钱绝不会被加到一起再除。落地商品口径的行没有归因 ⇒ 规则码是空串。
--
-- ⚠️ **金额一律 numeric，禁浮点**：value numeric(24,4)、quantity numeric(19,6)。
CREATE TABLE reporting_affiliate_marketing_fact (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  slot_type varchar(24) NOT NULL
    CHECK(slot_type IN ('RELATED_VIDEO','A_PLUS','STORE_TRAFFIC','SOCIAL_MEDIA')),
  slot_key varchar(200) NOT NULL CHECK(btrim(slot_key) <> ''),
  metric_code varchar(48) NOT NULL,
  -- 统计日。
  stat_date date NOT NULL,
  -- 币种：**空串 = 与货币无关**（曝光 / 点击 / 加购 / 笔数）。
  --   🔴 空串不是「币种未知」——未知币种的金额**根本不落库**（触发器钉死）。
  -- ⚠️ **用 varchar(3) 而不是 char(3)**：char(3) 会把空串补成三个空格（bpchar），
  --   「与货币无关」这个形状就表达不出来了。
  currency varchar(3) NOT NULL DEFAULT '' CHECK(currency ~ '^([A-Z]{3})?$'),
  -- 金额型取值（金额型指标用这列；数量型指标恒为 0）。
  value numeric(24,4) NOT NULL DEFAULT 0,
  -- 数量型取值（曝光 / 点击 / 加购 / 归因订单数）。金额型指标恒为 0。
  quantity numeric(19,6) NOT NULL DEFAULT 0,
  -- 🔴 **归因规则码**：走归因的指标**必须**带（DB 触发器钉死）；落地商品口径的行是空串。
  attribution_rule_code varchar(64) NOT NULL DEFAULT '',
  -- 来源服务与上游行标识（幂等 upsert 的另一半键）。
  source_service varchar(32) NOT NULL,
  source_reference varchar(160) NOT NULL DEFAULT '',
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id)
);

-- 幂等键：同一 (租户, 营销位, 类型, 指标, 日, 币种, 归因规则) 只有一条事实。
CREATE UNIQUE INDEX reporting_affiliate_marketing_fact_identity
  ON reporting_affiliate_marketing_fact(
    tenant_id, slot_type, slot_key, metric_code, stat_date, currency, attribution_rule_code);

-- 按窗口聚合用：租户 + 日 + 营销位 + 指标。
CREATE INDEX reporting_affiliate_marketing_fact_by_window
  ON reporting_affiliate_marketing_fact(tenant_id, stat_date, slot_type, metric_code);

-- ---------------------------------------------------------------------------
-- 4. reporting_affiliate_marketing_attribution_fact —— 🔴 本模块另起的归因事实
-- ---------------------------------------------------------------------------
--
-- 一行 = **一条上游归因记录**（channel 14.5.2.1 带货归因）。
--
-- 🔴 **规则快照逐字保留**：归因模型 / 分摊权重基准 / 归因窗口 / 退款口径 / 佣金计提口径
--   都取自**上游那条归因记录自带的快照**，**不是**「当前生效规则」——
--   规则改了以后历史归因仍然能被解释清楚（这正是蓝图那句加粗句要的效果）。
--
-- ⚠️ **币种缺失 ⇒ 金额为 NULL**：上游没给币种的归因金额**不落库**
--   （算进某个币种是编造），触发器钉死这条。
CREATE TABLE reporting_affiliate_marketing_attribution_fact (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  slot_type varchar(24) NOT NULL
    CHECK(slot_type IN ('RELATED_VIDEO','A_PLUS','STORE_TRAFFIC','SOCIAL_MEDIA')),
  slot_key varchar(200) NOT NULL CHECK(btrim(slot_key) <> ''),
  -- 上游归因记录 id（幂等键的另一半）。
  attribution_id uuid NOT NULL,
  influencer_id uuid,
  influencer_name varchar(120) NOT NULL DEFAULT '',
  campaign_id uuid,
  campaign_name varchar(120) NOT NULL DEFAULT '',
  cooperation_id uuid,
  shop_code varchar(64) NOT NULL DEFAULT '',
  -- 🔴 币种：空串 = 上游没给 ⇒ 下面的金额**必须**是 NULL。
  currency varchar(3) NOT NULL DEFAULT '' CHECK(currency ~ '^([A-Z]{3})?$'),
  -- 🔴 规则快照（**逐字取自上游那条归因记录**）。
  rule_code varchar(64) NOT NULL DEFAULT '',
  attribution_model varchar(32) NOT NULL DEFAULT '',
  pool_weight_basis varchar(32) NOT NULL DEFAULT '',
  attribution_window_days integer,
  refund_treatment varchar(16) NOT NULL DEFAULT '',
  commission_basis varchar(32) NOT NULL DEFAULT '',
  commission_rate numeric(19,6),
  attribution_weight numeric(19,6),
  -- 归因成交金额与佣金成本（**币种缺失时必须是 NULL**）。
  attributed_gmv numeric(24,4),
  commission_amount numeric(24,4),
  -- 归因订单量 / 有效订单量（与货币无关 ⇒ 币种缺失时照样落）。
  attributed_order_count numeric(19,6),
  effective_order_count numeric(19,6),
  -- 🔴 **统计日**：归因发生日（**UTC 自然日**）。上游归因记录**不带订单日期** ⇒
  --   这个口径写在页面上（口径表里也写着）。上游没给归因时刻时为 NULL
  --   （那一行**进不了任何时间窗口**，刷新结果里如实报出条数）。
  stat_date date,
  -- 🔴 **归因发生时刻（timestamptz，精确到瞬间）**。
  --   ⚠️ 必须用 {@code OffsetDateTime} 绑定并在 values 位置写 {@code ?::timestamptz} ——
  --   用 {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800s（东八区的时间会被记成 UTC）。
  --   ⚠️ 上游归因记录**不带订单日期** ⇒ 统计日按**归因发生日（UTC 自然日）**切桶，
  --   这个口径写在页面上（口径表里也写着）。
  attributed_at timestamptz,
  source_service varchar(32) NOT NULL,
  source_reference varchar(160) NOT NULL DEFAULT '',
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一条上游归因记录只留一条（重投同一批数据不会把数加两遍）。
  UNIQUE(tenant_id, source_service, attribution_id)
);

-- 下钻用：租户 + 营销位 + 归因时刻倒序。
CREATE INDEX reporting_affiliate_marketing_attribution_fact_by_slot
  ON reporting_affiliate_marketing_attribution_fact(
    tenant_id, slot_type, slot_key, attributed_at DESC);

-- 规则口径清单用：租户 + 规则码。
CREATE INDEX reporting_affiliate_marketing_attribution_fact_by_rule
  ON reporting_affiliate_marketing_attribution_fact(
    tenant_id, attribution_model, rule_code);

-- ===========================================================================
-- 第二节：数据库不变量（把蓝图的话变成数据层做不到的事）
-- ===========================================================================

-- (a) 派生型的分子分母必须**真的存在**（否则口径是一句空话）。
CREATE OR REPLACE FUNCTION guard_reporting_affiliate_marketing_metric_definition_ref() RETURNS trigger AS $$
BEGIN
  IF NEW.aggregation <> 'DERIVED_RATIO' THEN
    RETURN NEW;
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM reporting_affiliate_marketing_metric_definition
     WHERE tenant_id = NEW.tenant_id AND metric_code = NEW.numerator_metric_code) THEN
    RAISE EXCEPTION '指标 % 的分子指标 % 尚未定义，口径不成立', NEW.metric_code, NEW.numerator_metric_code
      USING ERRCODE = 'check_violation';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM reporting_affiliate_marketing_metric_definition
     WHERE tenant_id = NEW.tenant_id AND metric_code = NEW.denominator_metric_code) THEN
    RAISE EXCEPTION '指标 % 的分母指标 % 尚未定义，口径不成立', NEW.metric_code, NEW.denominator_metric_code
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER reporting_affiliate_marketing_metric_definition_ref
  BEFORE INSERT OR UPDATE ON reporting_affiliate_marketing_metric_definition
  FOR EACH ROW EXECUTE FUNCTION guard_reporting_affiliate_marketing_metric_definition_ref();

-- (b) 🔴 事实行的**指标码必须已登记**，且**币种形状与规则码必须与口径一致**：
--
--     * 派生型指标**不许有事实行**（派生值是查询期算出来的，落库就成了第二个真相）；
--     * 涉币指标的事实行**必须**带 3 位大写币种码（空串 = 与货币无关，不是「币种未知」）；
--     * 依赖归因规则的指标**必须**带 attribution_rule_code
--       （否则报表里说不清这条数是用哪条归因规则算出来的）。
CREATE OR REPLACE FUNCTION guard_reporting_affiliate_marketing_fact_known() RETURNS trigger AS $$
DECLARE
  def reporting_affiliate_marketing_metric_definition%ROWTYPE;
BEGIN
  SELECT * INTO def FROM reporting_affiliate_marketing_metric_definition
   WHERE tenant_id = NEW.tenant_id AND metric_code = NEW.metric_code;
  IF NOT FOUND THEN
    RAISE EXCEPTION '指标码 % 尚未在关联营销口径表中登记，不能落事实', NEW.metric_code
      USING ERRCODE = 'check_violation';
  END IF;
  IF def.aggregation <> 'SUM' THEN
    RAISE EXCEPTION '指标 % 是派生指标（%），只能在查询期算，不能落事实行', NEW.metric_code, def.aggregation
      USING ERRCODE = 'check_violation';
  END IF;
  IF NOT def.available THEN
    RAISE EXCEPTION '指标 % 本轮不可用（归 % 管），不能落事实行', NEW.metric_code, def.owner_module
      USING ERRCODE = 'check_violation';
  END IF;
  IF def.currency_aware AND NEW.currency !~ '^[A-Z]{3}$' THEN
    RAISE EXCEPTION '涉币指标 % 的事实行必须带 3 位大写币种码（空串 = 与货币无关，不是「币种未知」）', NEW.metric_code
      USING ERRCODE = 'check_violation';
  END IF;
  IF def.attribution_bound AND btrim(NEW.attribution_rule_code) = '' THEN
    RAISE EXCEPTION '指标 % 依赖归因规则，事实行必须带 attribution_rule_code（否则说不清用的是哪条规则）', NEW.metric_code
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER reporting_affiliate_marketing_fact_known
  BEFORE INSERT OR UPDATE ON reporting_affiliate_marketing_fact
  FOR EACH ROW EXECUTE FUNCTION guard_reporting_affiliate_marketing_fact_known();

-- (c) 🔴 **币种缺失 ⇒ 金额不落库**：归因事实里没币种时，归因金额与佣金金额**必须**是 NULL
--   （算进某个币种是编造）。订单量与货币无关 ⇒ 照样落。
CREATE OR REPLACE FUNCTION guard_reporting_affiliate_marketing_attribution_fact_currency() RETURNS trigger AS $$
BEGIN
  IF NEW.currency !~ '^[A-Z]{3}$' THEN
    IF NEW.attributed_gmv IS NOT NULL THEN
      RAISE EXCEPTION '归因事实缺币种时归因成交金额必须为 NULL（算进某个币种是编造）'
        USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.commission_amount IS NOT NULL THEN
      RAISE EXCEPTION '归因事实缺币种时佣金金额必须为 NULL（算进某个币种是编造）'
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER reporting_affiliate_marketing_attribution_fact_currency
  BEFORE INSERT OR UPDATE ON reporting_affiliate_marketing_attribution_fact
  FOR EACH ROW EXECUTE FUNCTION guard_reporting_affiliate_marketing_attribution_fact_currency();

-- ===========================================================================
-- 第三节：多租户（RLS isolation）+ 授权
-- ===========================================================================

DO $$
DECLARE tbl text;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'reporting_affiliate_marketing_metric_definition',
    'reporting_affiliate_marketing_slot',
    'reporting_affiliate_marketing_fact',
    'reporting_affiliate_marketing_attribution_fact'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_affiliate_marketing_metric_definition,
  reporting_affiliate_marketing_slot,
  reporting_affiliate_marketing_fact,
  reporting_affiliate_marketing_attribution_fact TO reporting_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_affiliate_marketing_metric_definition,
  reporting_affiliate_marketing_slot,
  reporting_affiliate_marketing_fact,
  reporting_affiliate_marketing_attribution_fact TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM reporting_app;

-- ===========================================================================
-- 第四节：留给下游的口子（不是本模块的缺口）
-- ===========================================================================
--
--   * **13.3.1.1 产品表现分析**：它要「单产品 360 度」，其中关联营销这一段直接读本模块的
--     `reporting_affiliate_marketing_fact`（A+ 落地商品口径的曝光 / 点击行就是
--     「哪个 listing 的引流」）—— **不需要**改结构。
--   * **13.4 利润**：归因成交金额与佣金成本是**真实成本**，但利润侧由 12.4 成本分摊与
--     13.4 报表负责，本模块**不订阅、不推送**（事实仍以业务系统为准）。
--   * **14 广告**：广告归因与预算调优归 14 所有，本模块**不抢**；
--     本模块的「归因」是**达人带货归因**（channel 14.5.2.1），与广告点击归因不是一回事。
