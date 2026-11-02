-- 15.6.2.1 生命周期与流失预警：客户生命周期分层（RFM / 新客-活跃-沉睡-流失-召回）、
-- 流失预警（按复购间隔识别「本应复购未复购」）、召回建议。
--
-- 蓝图 15 卷 15.6.2.1 的原话：
--   「职责：客户生命周期分层（RFM/新客-活跃-沉睡-流失-召回）与流失预警：按复购间隔识别
--     本应复购未复购的客户，给出召回建议与流失风险分；支持品牌化客户的核心留存看板。
--     输入：订单历史（9）；复购周期基线；RFM 计算配置。
--     输出：生命周期分层结果；流失预警清单；召回建议。
--     关系：上游 = 9 订单、15.3.1.1；下游 = 15.5.2 触达、15.6.2.2 挽回、13 分析。
--     **复购周期因品类差异极大**（快消周级、家具月级），预警阈值需可按类目配置，不能一刀切。」
--
-- ============================================================================
-- 零、本文件与既有模块的边界（写入所有者各自独立）
-- ============================================================================
--
-- 本文件是 **crm 数据库的第二十个迁移**（V99 知识库 … V432 客户列表，见目录）。
--
-- 与兄弟模块的边界 —— **零共用表、零 ALTER 既有表**：
--   * V432（15.3.1.1 客户列表）：回答「租户有哪些客户、各自的情况怎么样」。
--     本文件**只读**它的 `crm_customer_profile`（客户身份 / 来源店铺 / 买家名快照），
--     **一个字都不写**它的表 —— 本模块的写入所有者是下面这四张新表。
--   * V296（15.6.2.2 购物车与弃单挽回）：回答「独立站这位买家加购没走完，要不要挽回」。
--     本文件与它**不共用任何一张表**、不共用端点、不共用页面。
--   * V299（15.3.2.2 接触记录）：本文件**不读**它的退订表 —— 召回只是**建议**，
--     触达（15.5.2）才需要退订名单，那道闸归触达模块，不归本模块。
--
-- ============================================================================
-- 一、🔴 复购周期阈值是**租户数据**，不是代码常量
-- ============================================================================
--
-- 蓝图加粗句「复购周期因品类差异极大（快消周级、家具月级），预警阈值需可按类目配置，
-- 不能一刀切」在本文件里的落点是 `crm_lifecycle_policy`：
--
--   * 阈值**落库**（`expected_repurchase_days` / `tolerance_days`），
--     **有生效区间**（`effective_from` / `effective_to`）——
--     改阈值不改历史：上一次判定用哪套阈值，跑完就写在那一行上。
--   * 阈值**可按类目**（`scope='CATEGORY'` + `category_code`），
--     也可设一条**全局兜底**（`scope='GLOBAL'`）。
--   * 🔴 **代码里没有任何「默认 30 天」**。没有命中任何策略时，
--     判定基线改用**租户真实订单历史算出来的中位复购间隔**，
--     并且必须把这件事**如实写进** `threshold_source` 与 `segment_reason` ——
--     硬编码一个周数然后声称「按类目生效」，那是本文件要防的头号事故。
--
-- ============================================================================
-- 二、跨服务纪律（零跨库 SQL / FK / 视图）
-- ============================================================================
--
-- 上游 **9 订单**在 **trade** 服务（端口 8085，`GET /trade/sales-orders/list`）；
-- **类目**在 **catalog** 服务（端口 8082，`GET /catalog/skus` 的 `category` 列，
-- 由 SKU 编码解析）。两者与本模块**不同服务、不同数据库** ⇒ 只走**授权 HTTP API**，
-- 禁止跨库 SQL / 跨库 FK / 共享视图。转发的是**调用方自己的** `Authorization` bearer，
-- 上游侧仍按已认证成员关系解析租户并强制 RLS ⇒ 租户隔离是**两道**。
--
-- 🔴 `category_code` 存的是**快照**（解析当时的 catalog 类目编码），
--   **不建**指向 catalog 的外键，也不在本库复制一张类目表。
--
-- ============================================================================
-- 三、四张表
-- ============================================================================
--
--   crm_lifecycle_policy     复购预警阈值配置（按类目 / 全局，带生效区间）—— 租户数据
--   crm_customer_lifecycle   生命周期分层结果（一行 = 一个来源店铺下的一个客户）
--   crm_churn_alert          流失预警清单（本应复购未复购 + 风险分 + 召回建议）
--   crm_lifecycle_run        一次「重算分层与预警」的运行记录（可审计）
--
-- ============================================================================
-- 四、🔴 禁浮点与时间纪律
-- ============================================================================
--
--   * 金额：numeric(19,4) + BigDecimal；风险分：numeric(5,2) + BigDecimal；禁浮点。
--   * 时间：一律 timestamptz + OffsetDateTime，绑定写 `?::timestamptz`。
--     用 `Timestamp.valueOf(LocalDateTime…)` 会在东八区**静默偏 −28800 秒** ——
--     而「距上次购买多少天」正是本模块的核心计算，偏差一天就是错判一天。
--
-- ============================================================================
-- 五、🔴 三处「必须能解释」由 DB 层钉死
-- ============================================================================
--
--   1. 分层行必须带**分层理由**（`segment_reason`）—— 凭什么落这一层。
--   2. 预警行必须带**召回建议**与**归属模块**（`recall_suggestion` / `suggestion_owner`）
--      —— 本模块只**产出建议**，触达归 15.5.2、弃单挽回归 15.6.2.2。
--   3. 预警行必须带**风险因子明细**（`risk_factors` 非空数组）——
--      一个不透明的分数没法复核。
--
-- ============================================================================
-- 六、发布后不可变的强制位置
-- ============================================================================
--
--   1. 客户身份列（source_shop + customer_ref）建后不可改（与 V432 同一手法）。
--   2. 预警的「已处理」状态只能**向前**推进（OPEN → ACKNOWLEDGED → RESOLVED），
--      不能倒退 —— 倒退会让「已处理」的记录凭空变回待办。
--
-- ⚠️ 为什么用**触发器**而不是**数据库外键**：
--   同服务既有测试的 TRUNCATE（PG：cannot truncate a table referenced in a foreign key
--   constraint）会被外键打破。本文件**不给任何既有表建外键**，本文件自己新建的表之间
--   也**一律用触发器做引用校验**，与 V99 / V220 / V228 / V292 / V295 / V299 / V432 同一手法。
--
-- ============================================================================
-- 零之一、角色
-- ============================================================================
-- 与 V99 / V220 / V228 / V292 / V295 / V299 / V432 同一口径：角色可能已由运维预置，
-- 因此只在缺失时创建，保证迁移可重复执行。
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='crm_app') THEN CREATE ROLE crm_app; END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN CREATE ROLE starcon_app; END IF;
END $$;
GRANT USAGE ON SCHEMA public TO crm_app;
GRANT USAGE ON SCHEMA public TO starcon_app;

-- 运行号序列单独建、单独授权：GRANT ... ON ALL TABLES 不覆盖序列，
-- 漏授权会变成「第一次重算分层就报权限不足」。
CREATE SEQUENCE crm_lifecycle_run_no_seq START 1;

-- ============================================================================
-- 1. crm_lifecycle_policy —— 复购预警阈值配置（**按类目可配**，带生效区间）
-- ============================================================================
CREATE TABLE crm_lifecycle_policy (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- ===== 作用域 =====
  -- CATEGORY 按类目生效（快消周级 / 家具月级各配各的）
  -- GLOBAL   全局兜底（该类目没配时用这一条；一个租户最多一条同时生效）
  scope varchar(16) NOT NULL CHECK (scope IN ('CATEGORY','GLOBAL')),
  -- 类目编码（catalog 的类目编码，只读来源）。scope='GLOBAL' 时**必须**为空串。
  category_code varchar(64) NOT NULL DEFAULT '',

  -- ===== 预警阈值（本模块的核心可配置项）=====
  -- 该类目的**预期复购间隔**（天）：超过它 + 容差 ⇒ 本应复购未复购。
  -- 🔴 这里没有任何「默认 30 天」：不配就没有阈值，判定改用订单历史基线并如实标注。
  expected_repurchase_days integer NOT NULL CHECK (expected_repurchase_days > 0),
  -- 容差（天）：预期复购间隔之外再宽容几天。0 = 不宽容。
  tolerance_days integer NOT NULL DEFAULT 0 CHECK (tolerance_days >= 0),

  -- ===== RFM 计算配置（蓝图输入里的「RFM 计算配置」）=====
  -- RFM 的计算窗口（天）：窗口外的订单不进 R/F/M。
  rfm_window_days integer NOT NULL DEFAULT 365 CHECK (rfm_window_days > 0),
  -- R 分档的「最近」线（天）：距上次购买 ≤ 该值 ⇒ R=5。
  recency_score_days integer NOT NULL DEFAULT 30 CHECK (recency_score_days > 0),
  -- F 分档的基准（单）：几单算「高频」。
  frequency_min_orders integer NOT NULL DEFAULT 3 CHECK (frequency_min_orders >= 1),
  -- M 分档的基准（金额 + 币种）。**可空** ⇒ 改用该币种下租户真实订单历史的分位数。
  monetary_min_amount numeric(19,4),
  monetary_currency char(3),

  enabled boolean NOT NULL DEFAULT true,

  -- ===== 生效区间（改阈值不改历史）=====
  effective_from timestamptz NOT NULL,
  effective_to timestamptz,

  -- 备注：写清「这个类目为什么是这个复购周期」（例：快消按周补货）。
  note varchar(600) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  -- 同一作用域 + 同一类目 + 同一生效起点 ⇒ 只有一条。
  UNIQUE (tenant_id, scope, category_code, effective_from),

  -- GLOBAL 必须不带类目码；CATEGORY 必须带类目码。
  CHECK ((scope = 'GLOBAL' AND category_code = '')
      OR (scope = 'CATEGORY' AND btrim(category_code) <> '')),
  -- 生效区间必须自洽。
  CHECK (effective_to IS NULL OR effective_to > effective_from),
  -- 金额阈值与币种**同生共死**：有金额就必须说币种（跨币种的金额不能比）。
  CHECK ((monetary_min_amount IS NULL) = (monetary_currency IS NULL))
);

-- ============================================================================
-- 2. crm_customer_lifecycle —— 生命周期分层结果
-- ============================================================================
CREATE TABLE crm_customer_lifecycle (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- ===== 客户身份（**按来源店铺**，与 15.3.1.1 同一口径）=====
  customer_ref varchar(128) NOT NULL CHECK (btrim(customer_ref) <> ''),
  source_shop varchar(64) NOT NULL CHECK (btrim(source_shop) <> ''),
  customer_party_id uuid,
  customer_name varchar(200) NOT NULL DEFAULT '',

  -- ===== 事实（R/F/M 的原料，全部来自上游订单事实）=====
  first_order_at timestamptz,
  last_order_at timestamptz,
  order_count integer NOT NULL DEFAULT 0 CHECK (order_count >= 0),
  -- 距上次购买多少天（**整数天**，服务端按 UTC 日历天算，不做前端算术）。
  recency_days integer CHECK (recency_days >= 0),
  -- 窗口内订单数（F 的原料）。
  frequency integer NOT NULL DEFAULT 0 CHECK (frequency >= 0),
  -- 主币种下的累计消费（M 的原料）。**金额按币种分开**，这里只存主币种。
  monetary numeric(19,4) CHECK (monetary >= 0),
  currency char(3),

  -- ===== RFM 分档（0 = 不可判定，不是 0 分）=====
  r_score integer NOT NULL DEFAULT 0 CHECK (r_score BETWEEN 0 AND 5),
  f_score integer NOT NULL DEFAULT 0 CHECK (f_score BETWEEN 0 AND 5),
  m_score integer NOT NULL DEFAULT 0 CHECK (m_score BETWEEN 0 AND 5),
  -- 分档依据明细（JSON）：每个分档的阈值、实际值、怎么算出来的。
  score_basis jsonb NOT NULL DEFAULT '{}'::jsonb,

  -- ===== 生命周期分层 =====
  segment varchar(16) NOT NULL
    CHECK (segment IN ('NEW','ACTIVE','DORMANT','CHURNED','RECALL')),
  -- 🔴 分层理由**必填**：凭什么落这一层。
  segment_reason varchar(600) NOT NULL DEFAULT '',

  -- ===== 类目维度（catalog 只读快照）=====
  category_code varchar(64),
  -- CATALOG_SKU 解析自 catalog 的 SKU 类目 / UNRESOLVED 没解析出来
  category_source varchar(24) NOT NULL DEFAULT 'UNRESOLVED'
    CHECK (category_source IN ('CATALOG_SKU','UNRESOLVED')),

  -- ===== 判定阈值（本次判定用哪套阈值）=====
  expected_repurchase_days integer CHECK (expected_repurchase_days > 0),
  tolerance_days integer CHECK (tolerance_days >= 0),
  -- CATEGORY_POLICY 命中类目策略 / GLOBAL_POLICY 命中全局策略
  -- ORDER_HISTORY_BASELINE 两者都没有 ⇒ 用租户真实订单历史的中位复购间隔
  -- NONE 连订单历史基线都算不出来 ⇒ 不可判定（不产生预警）
  threshold_source varchar(32) NOT NULL
    CHECK (threshold_source IN ('CATEGORY_POLICY','GLOBAL_POLICY','ORDER_HISTORY_BASELINE','NONE')),
  policy_id uuid,

  -- ===== 客户自己的复购间隔基线（**证据**，来自本人真实订单历史）=====
  -- 本人历史相邻两次购买的间隔中位数（天）。
  customer_baseline_days integer CHECK (customer_baseline_days >= 0),
  -- 本人的间隔样本数（几段间隔）。
  customer_baseline_samples integer NOT NULL DEFAULT 0 CHECK (customer_baseline_samples >= 0),
  -- ORDER_HISTORY_MEDIAN 算得出来 / INSUFFICIENT_SAMPLE 样本不足（只有一单）
  baseline_source varchar(32) NOT NULL
    CHECK (baseline_source IN ('ORDER_HISTORY_MEDIAN','INSUFFICIENT_SAMPLE')),

  -- 超期天数（未超期为 0）。
  overdue_days integer NOT NULL DEFAULT 0 CHECK (overdue_days >= 0),

  -- ===== 统计窗口与计算时刻 =====
  window_from timestamptz,
  window_to timestamptz,
  computed_at timestamptz NOT NULL,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  -- 🔴 客户身份：**（来源店铺, 客户标识）** 在租户内唯一（与 15.3.1.1 同一口径）。
  UNIQUE (tenant_id, customer_ref, source_shop)
);

-- ============================================================================
-- 3. crm_churn_alert —— 流失预警清单（本应复购未复购）
-- ============================================================================
CREATE TABLE crm_churn_alert (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  customer_ref varchar(128) NOT NULL CHECK (btrim(customer_ref) <> ''),
  source_shop varchar(64) NOT NULL CHECK (btrim(source_shop) <> ''),
  customer_name varchar(200) NOT NULL DEFAULT '',

  -- ===== 判定依据（全部可复核）=====
  last_order_at timestamptz,
  -- 距上次购买多少天。
  days_since_last_order integer NOT NULL CHECK (days_since_last_order >= 0),
  -- 本次判定用的预期复购间隔（天）与容差（天）。
  expected_repurchase_days integer NOT NULL CHECK (expected_repurchase_days > 0),
  tolerance_days integer NOT NULL CHECK (tolerance_days >= 0),
  -- 超期天数 = 距上次购买 − (预期复购间隔 + 容差)。
  overdue_days integer NOT NULL CHECK (overdue_days >= 0),
  threshold_source varchar(32) NOT NULL
    CHECK (threshold_source IN ('CATEGORY_POLICY','GLOBAL_POLICY','ORDER_HISTORY_BASELINE')),
  category_code varchar(64),
  policy_id uuid,
  -- 客户本人的历史复购间隔中位数与样本数（**证据**）。
  customer_baseline_days integer CHECK (customer_baseline_days >= 0),
  customer_baseline_samples integer NOT NULL DEFAULT 0 CHECK (customer_baseline_samples >= 0),

  -- ===== 流失风险分（0..100，**可解释**：因子明细在 risk_factors 里）=====
  risk_score numeric(5,2) NOT NULL CHECK (risk_score >= 0 AND risk_score <= 100),
  -- LOW / MEDIUM / HIGH / CRITICAL
  risk_level varchar(16) NOT NULL CHECK (risk_level IN ('LOW','MEDIUM','HIGH','CRITICAL')),
  -- 🔴 因子明细：每个因子的代码、权重、原始值、得分与说明。**不允许为空数组**。
  risk_factors jsonb NOT NULL DEFAULT '[]'::jsonb,

  -- ===== 召回建议（🔴 只产出建议，不触达）=====
  recall_suggestion varchar(600) NOT NULL DEFAULT '',
  -- 建议的归属模块：15.5.2 触达 / 15.6.2.2 弃单挽回。
  suggestion_owner varchar(16) NOT NULL DEFAULT '15.5.2'
    CHECK (suggestion_owner IN ('15.5.2','15.6.2.2')),

  -- ===== 处理状态（只能向前）=====
  -- OPEN 待处理 / ACKNOWLEDGED 已知悉 / RESOLVED 已处理
  status varchar(16) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','ACKNOWLEDGED','RESOLVED')),
  status_note varchar(600) NOT NULL DEFAULT '',

  detected_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, customer_ref, source_shop)
);

-- ============================================================================
-- 4. crm_lifecycle_run —— 重算运行记录
-- ============================================================================
CREATE TABLE crm_lifecycle_run (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- LC-yyyyMMdd-000001
  run_no varchar(32) NOT NULL DEFAULT '',
  -- SUCCEEDED 成功 / FAILED 上游读不到（一条分层结果都不落）
  status varchar(16) NOT NULL CHECK (status IN ('SUCCEEDED','FAILED')),

  window_from timestamptz NOT NULL,
  window_to timestamptz NOT NULL,

  -- 计数（纯计数）
  customer_count integer NOT NULL DEFAULT 0 CHECK (customer_count >= 0),
  alert_count integer NOT NULL DEFAULT 0 CHECK (alert_count >= 0),
  policy_count integer NOT NULL DEFAULT 0 CHECK (policy_count >= 0),
  -- 命中类目策略的客户数（vs 走全局 / 走订单历史基线）。
  category_policy_hit_count integer NOT NULL DEFAULT 0 CHECK (category_policy_hit_count >= 0),
  -- 类目没解析出来的客户数（catalog 里查不到这个 SKU 的类目）。
  category_unresolved_count integer NOT NULL DEFAULT 0 CHECK (category_unresolved_count >= 0),
  -- 阈值判定不可判定的客户数（既无策略也无订单历史基线）。
  threshold_unavailable_count integer NOT NULL DEFAULT 0 CHECK (threshold_unavailable_count >= 0),
  -- 类目解析的扫描被截断（SKU 太多，查不完）。
  category_lookup_truncated boolean NOT NULL DEFAULT false,
  -- 上游扫描被截断（订单太多，翻页到上限）。
  truncated boolean NOT NULL DEFAULT false,

  failure_reason varchar(600) NOT NULL DEFAULT '',
  facts jsonb NOT NULL DEFAULT '{}'::jsonb,

  operator_id uuid,
  started_at timestamptz NOT NULL,
  finished_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, run_no)
);

-- ============================================================================
-- 5. 索引
-- ============================================================================
-- 分层列表默认排序：风险优先 / 最近购买升序（稳定全序）。
CREATE INDEX crm_customer_lifecycle_by_segment
  ON crm_customer_lifecycle(tenant_id, segment, customer_ref);

CREATE INDEX crm_customer_lifecycle_by_last_order
  ON crm_customer_lifecycle(tenant_id, last_order_at DESC NULLS LAST, customer_ref);

CREATE INDEX crm_customer_lifecycle_by_category
  ON crm_customer_lifecycle(tenant_id, category_code, customer_ref);

CREATE INDEX crm_customer_lifecycle_by_ref
  ON crm_customer_lifecycle(tenant_id, customer_ref);

-- 预警清单默认排序：超期天数降序（最该先召回的排最前）。
CREATE INDEX crm_churn_alert_by_overdue
  ON crm_churn_alert(tenant_id, status, overdue_days DESC, customer_ref);

CREATE INDEX crm_churn_alert_by_level
  ON crm_churn_alert(tenant_id, risk_level, customer_ref);

CREATE INDEX crm_churn_alert_by_category
  ON crm_churn_alert(tenant_id, category_code, customer_ref);

-- 策略查询：按类目 / 全局 + 生效区间。
CREATE INDEX crm_lifecycle_policy_by_scope
  ON crm_lifecycle_policy(tenant_id, scope, category_code, effective_from DESC);

CREATE INDEX crm_lifecycle_run_by_created
  ON crm_lifecycle_run(tenant_id, created_at DESC, id DESC);

-- ============================================================================
-- 6. 触发器（**一律不用外键** —— 理由见文件头第六节）
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 6.1 guard_crm_customer_lifecycle_identity · 客户身份列建后不可改
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_crm_customer_lifecycle_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.customer_ref IS DISTINCT FROM OLD.customer_ref THEN
    RAISE EXCEPTION '客户标识建后不可改：改了它会让「这位客户是谁」在历史上出现两个答案' USING ERRCODE='23514';
  END IF;
  IF NEW.source_shop IS DISTINCT FROM OLD.source_shop THEN
    RAISE EXCEPTION '来源店铺建后不可改：它是「按店铺的客户标识」的另一半' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_customer_lifecycle_identity_trg
  BEFORE UPDATE ON crm_customer_lifecycle
  FOR EACH ROW EXECUTE FUNCTION guard_crm_customer_lifecycle_identity();

-- ---------------------------------------------------------------------------
-- 6.2 guard_crm_customer_lifecycle_reason · 分层行必须带分层理由
-- ---------------------------------------------------------------------------
-- 凭什么落这一层必须能回答 —— 没有理由的分层没法复核。
CREATE FUNCTION guard_crm_customer_lifecycle_reason() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF btrim(NEW.segment_reason) = '' THEN
    RAISE EXCEPTION '生命周期分层行必须写明分层理由：凭什么落 % 这一层必须能回答', NEW.segment USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_customer_lifecycle_reason_trg
  BEFORE INSERT OR UPDATE ON crm_customer_lifecycle
  FOR EACH ROW EXECUTE FUNCTION guard_crm_customer_lifecycle_reason();

-- ---------------------------------------------------------------------------
-- 6.3 guard_crm_customer_lifecycle_threshold · 阈值与阈值来源必须自洽
-- ---------------------------------------------------------------------------
-- threshold_source='NONE' 表示「不可判定」⇒ **不许**同时写着一个预期复购天数。
-- 反过来，写了阈值就必须说清它从哪来 —— 不许「有个天数但说不出来源」。
CREATE FUNCTION guard_crm_customer_lifecycle_threshold() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.threshold_source = 'NONE' AND NEW.expected_repurchase_days IS NOT NULL THEN
    RAISE EXCEPTION '阈值不可判定（threshold_source=NONE）时不得写预期复购天数' USING ERRCODE='23514';
  END IF;
  IF NEW.threshold_source <> 'NONE' AND NEW.expected_repurchase_days IS NULL THEN
    RAISE EXCEPTION '阈值来源 % 必须配一个预期复购天数', NEW.threshold_source USING ERRCODE='23514';
  END IF;
  IF NEW.threshold_source IN ('CATEGORY_POLICY','GLOBAL_POLICY') AND NEW.policy_id IS NULL THEN
    RAISE EXCEPTION '阈值来源 % 必须指向具体策略行（policy_id）', NEW.threshold_source USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_customer_lifecycle_threshold_trg
  BEFORE INSERT OR UPDATE ON crm_customer_lifecycle
  FOR EACH ROW EXECUTE FUNCTION guard_crm_customer_lifecycle_threshold();

-- ---------------------------------------------------------------------------
-- 6.4 guard_crm_churn_alert_suggestion · 预警必须带召回建议与归属模块
-- ---------------------------------------------------------------------------
-- 召回建议**只产出建议**，触达归 15.5.2、弃单挽回归 15.6.2.2 ——
-- 所以「归属模块」是必填的，不许出现「不知道该谁去触达」。
CREATE FUNCTION guard_crm_churn_alert_suggestion() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF btrim(NEW.recall_suggestion) = '' THEN
    RAISE EXCEPTION '流失预警必须写明召回建议：没有建议的预警清单没法用' USING ERRCODE='23514';
  END IF;
  IF btrim(NEW.suggestion_owner) = '' THEN
    RAISE EXCEPTION '流失预警必须写明建议归属模块（15.5.2 触达 / 15.6.2.2 弃单挽回）' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_churn_alert_suggestion_trg
  BEFORE INSERT OR UPDATE ON crm_churn_alert
  FOR EACH ROW EXECUTE FUNCTION guard_crm_churn_alert_suggestion();

-- ---------------------------------------------------------------------------
-- 6.5 guard_crm_churn_alert_factors · 风险分必须带因子明细
-- ---------------------------------------------------------------------------
-- 一个不透明的分数没法复核：必须能回答「这个分是怎么来的」。
CREATE FUNCTION guard_crm_churn_alert_factors() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.risk_factors IS NULL OR jsonb_typeof(NEW.risk_factors) <> 'array' THEN
    RAISE EXCEPTION '风险因子明细必须是 JSON 数组' USING ERRCODE='23514';
  END IF;
  IF jsonb_array_length(NEW.risk_factors) = 0 THEN
    RAISE EXCEPTION '风险分必须带因子明细：没有因子明细的分数没法复核' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_churn_alert_factors_trg
  BEFORE INSERT OR UPDATE ON crm_churn_alert
  FOR EACH ROW EXECUTE FUNCTION guard_crm_churn_alert_factors();

-- ---------------------------------------------------------------------------
-- 6.6 guard_crm_churn_alert_status · 处理状态只能向前
-- ---------------------------------------------------------------------------
-- OPEN → ACKNOWLEDGED → RESOLVED。倒退会让「已处理」的记录凭空变回待办。
CREATE FUNCTION guard_crm_churn_alert_status() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_old int; v_new int;
BEGIN
  v_old := CASE OLD.status WHEN 'OPEN' THEN 0 WHEN 'ACKNOWLEDGED' THEN 1 ELSE 2 END;
  v_new := CASE NEW.status WHEN 'OPEN' THEN 0 WHEN 'ACKNOWLEDGED' THEN 1 ELSE 2 END;
  IF v_new < v_old THEN
    RAISE EXCEPTION '预警处理状态只能向前推进（% → %），不能倒退', OLD.status, NEW.status USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_churn_alert_status_trg
  BEFORE UPDATE ON crm_churn_alert
  FOR EACH ROW EXECUTE FUNCTION guard_crm_churn_alert_status();

-- ============================================================================
-- 7. RLS（ENABLE + FORCE）与授权
-- ============================================================================
-- 与 V99 / V220 / V228 / V292 / V295 / V299 / V432 同一口径：策略名固定 isolation，
-- USING 与 WITH CHECK 两侧同表达式。
-- 表达式里的 nullif 不是洁癖：事务局部的 set_config 提交之后，PostgreSQL 会把自定义 GUC
-- **复位成空串**而不是「回到未设置」，直接 cast 会报 uuid 语法错，
-- 而 nullif 把它归一成 NULL —— 条件不成立 → 一行都看不到。
-- 也就是说「忘记绑定租户」当场失败，而不是静默返回全表。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['crm_lifecycle_policy','crm_customer_lifecycle','crm_churn_alert','crm_lifecycle_run'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON crm_lifecycle_policy, crm_customer_lifecycle, crm_churn_alert, crm_lifecycle_run TO crm_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON crm_lifecycle_policy, crm_customer_lifecycle, crm_churn_alert, crm_lifecycle_run TO starcon_app;
-- 🔴 序列单独授权：GRANT ... ON ALL TABLES **不覆盖序列**，漏授权会变成
-- 「第一次重算分层就报权限不足」。
GRANT USAGE, SELECT ON SEQUENCE crm_lifecycle_run_no_seq TO crm_app;
GRANT USAGE, SELECT ON SEQUENCE crm_lifecycle_run_no_seq TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM crm_app;

-- ============================================================================
-- 8. 留给下游 / 上游的口子（不是本模块的缺口）
-- ============================================================================
--
--   * **15.5.2 触达**（未交付）：本模块**只产出召回建议**，不发送任何消息。
--     建议的归属写进 `suggestion_owner`（15.5.2 触达 / 15.6.2.2 弃单挽回）。
--   * **15.6.2.2 购物车与弃单挽回**（已交付，同在 crm 服务）：管的是**独立站加购没走完**
--     的那部分；本模块管的是**买过、该回购却没回购**的那部分。两者不共用表、不共用端点。
--   * **13 分析**：本模块的分层结果与预警清单是 13 的上游事实；
--     13 交付后由它消费，**不是**本模块开工的前置条件。
--   * **15.3.1.1 客户列表**（已交付）：本模块**只读**它的客户主档，不写它的表；
--     客户身份口径与它保持一致（来源店铺 + 客户标识）。
