-- 15.2.1.3 差评处理：差评专项处理（标记原因 / 判定是否申诉 / 联系买家解决后请求修改 /
-- 跟踪解决率）。
--
-- 蓝图 15 卷 15.2.1.3 的原话：
--   「职责：差评专项处理：标记原因（产品/物流/服务/误判）、判定是否申诉（部分平台支持）、
--     联系买家解决后请求修改、跟踪解决率。
--     输入：差评内容；处理动作；买家沟通记录。
--     输出：处理记录 + 申诉任务 + 解决状态。
--     关系：上游 = 15.2.1.1；下游 = 15.1.1.2 联系买家、11 售后（解决问题）。
--     **解决差评的正确路径是先解决买家问题再请求修改，直接申诉容易被驳回且消耗申诉配额。**」
--
-- ============================================================ 零、本文件与既有模块的边界
--
-- 本文件是 **crm 数据库的第九个迁移**（前八个：15.4.2.1 知识库 V99 / 15.2.2.1 索评 V220 /
-- 15.2.2.2 索评规则 V228 / 15.1.2.1 买家邮件 V292 / 15.1.1.1 消息收件箱 V293 /
-- 15.2.1.1 评价列表 V294 / 15.3.2.2 客户标签 V295 / 15.3.3.1 购物车挽回 V296 /
-- 15.1.1.2 消息发送 V297 / 15.2.1.2 评价回复 V298 / 15.3.2.1 客户接触 V299）。
--
-- ⇒ **本文件与它们不共用任何一张表，也不 ALTER / DROP 任何既有表**：
--   * 15.2.1.1 评价列表（V294）的 `crm_product_review` 是**评价本体**，
--     本模块**只读引用**它（差评的来源），**不改它的表结构、端点与权限**；
--   * 15.2.1.2 评价回复（V298）的 `crm_review_reply*` 是「**公开回复**」，
--     本模块的「**请求修改**」是**私下联系买家** —— 两者受众与目的都不同，
--     **刻意不共用表名**（公开回复不会让买家改评价，私下沟通才会）；
--   * 15.3.2.1 客户接触（V299）的 `crm_customer_contact` 是「**联系记录**」，
--     本模块的「联系买家」是**差评处理流程内的一步**（带前置条件与后续状态），
--     **刻意不共用表名**。
--
-- ============================================================ 一、🔴 加粗句必须落成系统行为
--
-- 「**解决差评的正确路径是先解决买家问题再请求修改，直接申诉容易被驳回且消耗申诉配额**」
--
-- 这句话的落点**不是**一句页面提示，而是一条**服务端强制的状态机**：
--
--   1. **没有成功联系过买家 ⇒ 不允许请求修改、不允许建申诉任务**。
--      服务层拒绝并给出**明确原因**（不是静默忽略，也不是"先创建再说"）。
--      触发条件是「处理单下存在一条 outcome=SUCCEEDED 的 CONTACT_BUYER 动作」。
--   2. **申诉配额是有限资源** ⇒ 配额按（租户 × 平台 × 周期）计数，
--      **建申诉任务即占用**（撤回归还），配额用尽时明确拒绝。
--   3. **判定是否可申诉要真的可配** ⇒ `crm_negative_review_rule` 落
--      （平台 × 原因）的规则；平台不支持申诉的平台**如实区分**，不一律说能申诉。
--
-- ============================================================ 二、🔴 平台侧接口未接入 —— 绝不写假回执
--
-- 「请求修改」与「提交申诉」都要落到**平台侧**。而 `channel/vendor` 目前**只有 OAuth 适配器**
-- （amazon / ebay / lazada / shopee / shopify / temu / tiktok / walmart），
-- **没有任何平台的买家评价申诉 / 修改申请适配器**。
--
-- ⇒ 本文件**不假装**这条通道存在：
--   * 平台提交端点由 `UnavailablePlatformNegativeReviewPort` 明确答「未接入」，
--     服务层据此返回 **409** 且**一行都不写**（连「尝试记录」都不写 ——
--     写一条「试过了」的行本身没错，但那样会让「已提交」的语义被稀释）；
--   * 🔴 **绝不写 `APPROVED` / `REJECTED` 之外的假结果，绝不编造 `platform_receipt`**：
--     一条返回「申诉已提交」的假回执会让「差评已经处理掉了」看起来发生过，
--     而现场一条真申诉都没提交。
--   * 运营在**平台侧人工提交**之后，用本模块的**人工交接登记**端点回填
--     真实的提交时刻与平台判定 —— 那是一条**人观察到的真事实**，不是编的。
--
-- ============================================================ 三、🔴 11 售后未交付 ⇒ 只落转交意图
--
-- 蓝图关系句写着「下游 = … 11 售后（解决问题）」，但**11 售后在本仓未交付**。
-- ⇒ 本模块只落**转交意图**：
--   * `crm_negative_review_action.after_sales_reason` 记录**为什么转**（必填）；
--   * 🔴 **本表刻意没有「售后单据 id」这一列** —— 下游没有交付就没有 id，
--     编一个会让「已转售后」看起来发生过，而现场一张售后单都没建。
--     （同 15.1.1.1 收件箱对「处置意图」的处理手法。）
--
-- ============================================================ 四、四张表
--
--   crm_negative_review_rule     差评处理规则（平台策略 + 原因规则，**真的可配**）
--   crm_negative_review_case     处理记录主体（一条差评的处理单）
--   crm_negative_review_action   处理动作流水（每一步动作 + 操作者 + 时刻 + 结果）
--   crm_negative_review_appeal   申诉任务（申诉类型 / 配额计数 / 提交跟踪 / 结果）
--
-- 为什么「规则」也要落库：原因分类与申诉判定若是代码里的常量，
-- 平台政策一变就只能改代码发版 —— 而**平台是否支持申诉本来就不是技术问题**。
--
-- ============================================================ 五、零外键（既有约定）
--
-- 本迁移**零 FOREIGN KEY**：跨表引用一律用**触发器**校验。
-- 原因：本仓既有约定 —— 外键会打破同服务既有测试的 TRUNCATE
-- （PG：`cannot truncate a table referenced in a foreign key constraint`）。
--
-- ============================================================ 六、时区、金额与数量
--
--   * 时间一律 **timestamptz** + OffsetDateTime，绑定写 `?::timestamptz`
--     （用 `Timestamp.valueOf(LocalDateTime)` 会在东八区静默偏 −28800 秒）。
--   * 本模块**没有金额**：差评处理里不出现金额。
--   * 数量一律 integer；**解决率**是比例，由 SQL 的 numeric 聚合给出，
--     服务层用 BigDecimal 承载，**禁浮点**。
--
-- ============================================================================
-- 零之一、角色
-- ============================================================================
-- 与 V99 / V220 / V228 / V292 / V294 / V298 同一口径：角色可能已由运维预置，因此只在缺失时创建。
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='crm_app') THEN CREATE ROLE crm_app; END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN CREATE ROLE starcon_app; END IF;
END $$;

GRANT USAGE ON SCHEMA public TO crm_app;
GRANT USAGE ON SCHEMA public TO starcon_app;

-- ============================================================================
-- 1. 表
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1.1 crm_negative_review_rule · 差评处理规则（真的可配）
-- ---------------------------------------------------------------------------
-- 两种作用域：
--   * **PLATFORM**（逐平台一行）：差评判定阈值、平台是否支持申诉、
--     是否必须先联系买家、申诉配额与周期、申诉时限。
--   * **CAUSE**（逐「平台 × 原因」一行）：原因的显示名与说明、
--     该原因默认是否可申诉、判定依据模板、是否要求证据。
--
-- 🔴 为什么阈值（negative_rating_threshold）落在**规则**里而不是代码常量里：
--   业务口径是「rating <= 2 视为差评」，但那是**口径**不是**宇宙真理** ——
--   不同平台的星级语义不同（有的平台 1 星是「差」，有的平台 2 星还算中性），
--   阈值必须能按平台调，而且**改了要能查清"这条单当时按哪个阈值判的"**
--   ⇒ 处理单上落阈值快照。
CREATE TABLE crm_negative_review_rule (
  id                        uuid PRIMARY KEY,
  tenant_id                 uuid NOT NULL,
  rule_no                   varchar(32)  NOT NULL,
  rule_scope                varchar(16)  NOT NULL,
  platform_code             varchar(32)  NOT NULL,
  -- CAUSE 作用域必填；PLATFORM 作用域必须为空（见下方 CHECK）。
  cause_code                varchar(24),
  label                     varchar(200) NOT NULL,
  description               varchar(1000),

  -- ---- PLATFORM 作用域 ----
  -- 🔴 差评判定阈值：rating <= 该阈值即视为差评（业务口径默认 2）。
  negative_rating_threshold smallint     NOT NULL DEFAULT 2,
  -- 平台是否**支持**申诉（部分平台不支持 ⇒ 如实区分，不一律说能申诉）。
  appeal_supported          boolean      NOT NULL DEFAULT false,
  -- 是否必须**先联系买家**（蓝图加粗句的落点之一，可按平台放宽）。
  contact_buyer_required    boolean      NOT NULL DEFAULT true,
  -- 申诉配额（每周期）与周期。0 = 该平台不开放申诉。
  appeal_quota_limit        integer      NOT NULL DEFAULT 0,
  quota_period              varchar(16)  NOT NULL DEFAULT 'MONTHLY',
  -- 申诉时限（天）：超过这个天数就不该再申诉了（平台侧通常也有时限）。
  appeal_window_days        integer      NOT NULL DEFAULT 30,

  -- ---- CAUSE 作用域 ----
  -- 该原因默认是否可申诉（还要与平台的 appeal_supported 取交集）。
  appeal_eligible           boolean      NOT NULL DEFAULT false,
  -- 判定依据模板（把「哪些条件导致可 / 不可申诉」写清楚、可按原因改）。
  appeal_basis_template     varchar(1000),
  -- 该原因是否要求留证据（没有证据的分类会让解决率统计失真）。
  evidence_required         boolean      NOT NULL DEFAULT false,

  -- 展示优先级（数字小 = 靠前）。
  priority                  integer      NOT NULL DEFAULT 100,
  -- 规则版本：原地改一次 = 版本 +1（处理单上落版本快照）。
  version                   integer      NOT NULL DEFAULT 1,
  status                    varchar(16)  NOT NULL DEFAULT 'ACTIVE',
  effective_from            timestamptz  NOT NULL,
  effective_to              timestamptz,
  note                      varchar(500),
  created_by                uuid,
  created_at                timestamptz  NOT NULL DEFAULT now(),
  updated_at                timestamptz  NOT NULL DEFAULT now(),

  CONSTRAINT crm_negative_review_rule_scope_ck
    CHECK (rule_scope IN ('PLATFORM','CAUSE')),
  -- 🔴 作用域与原因列必须自洽：PLATFORM 行不挂原因，CAUSE 行必须挂原因。
  CONSTRAINT crm_negative_review_rule_scope_cause_ck
    CHECK ( (rule_scope = 'CAUSE'    AND cause_code IS NOT NULL)
         OR (rule_scope = 'PLATFORM' AND cause_code IS NULL) ),
  CONSTRAINT crm_negative_review_rule_cause_ck
    CHECK (cause_code IS NULL OR cause_code IN ('PRODUCT','LOGISTICS','SERVICE','MISJUDGMENT')),
  CONSTRAINT crm_negative_review_rule_threshold_ck
    CHECK (negative_rating_threshold BETWEEN 1 AND 5),
  CONSTRAINT crm_negative_review_rule_quota_ck
    CHECK (appeal_quota_limit >= 0),
  CONSTRAINT crm_negative_review_rule_quota_period_ck
    CHECK (quota_period IN ('MONTHLY','QUARTERLY','YEARLY','NONE')),
  CONSTRAINT crm_negative_review_rule_window_days_ck
    CHECK (appeal_window_days >= 0 AND appeal_window_days <= 365),
  CONSTRAINT crm_negative_review_rule_version_ck
    CHECK (version >= 1),
  CONSTRAINT crm_negative_review_rule_status_ck
    CHECK (status IN ('ACTIVE','ARCHIVED')),
  CONSTRAINT crm_negative_review_rule_effective_ck
    CHECK (effective_to IS NULL OR effective_to > effective_from)
);

-- 规则号在租户内唯一。
CREATE UNIQUE INDEX crm_negative_review_rule_no_uniq
  ON crm_negative_review_rule(tenant_id, rule_no);
-- 🔴 一个平台**只有一条**策略规则（原地改 = 版本 +1）：
--   两条并存时「这条差评按哪条阈值判的」就答不了。
CREATE UNIQUE INDEX crm_negative_review_rule_platform_uniq
  ON crm_negative_review_rule(tenant_id, platform_code)
  WHERE rule_scope = 'PLATFORM';
-- 一个（平台 × 原因）**只有一条**原因规则。
CREATE UNIQUE INDEX crm_negative_review_rule_cause_uniq
  ON crm_negative_review_rule(tenant_id, platform_code, cause_code)
  WHERE rule_scope = 'CAUSE';
-- 取「当前生效的规则」用。
CREATE INDEX crm_negative_review_rule_effective_idx
  ON crm_negative_review_rule(tenant_id, platform_code, rule_scope, effective_from DESC);

-- ---------------------------------------------------------------------------
-- 1.2 crm_negative_review_case · 处理记录主体
-- ---------------------------------------------------------------------------
CREATE TABLE crm_negative_review_case (
  id                    uuid PRIMARY KEY,
  tenant_id             uuid NOT NULL,
  case_no               varchar(32)  NOT NULL,
  -- 上游评价（逻辑引用 15.2.1.1 的 crm_product_review，**零外键**，触发器校验）。
  review_id             uuid        NOT NULL,
  review_no             varchar(32)  NOT NULL,
  -- 评价快照：处理单要留住「当时面对的是哪一条差评」。
  review_rating         smallint    NOT NULL,
  review_title          varchar(300),
  review_content        text,
  review_external_id    varchar(128),
  shop_id               uuid        NOT NULL,
  shop_name             varchar(200) NOT NULL,
  platform_code         varchar(32)  NOT NULL,
  buyer_ref             varchar(128),
  buyer_name            varchar(200),
  sales_order_no        varchar(64),

  -- ---- 平台策略快照（建单时从规则取；之后规则改了也不影响本单口径） ----
  policy_rule_id        uuid,
  policy_rule_version   integer,
  negative_rating_threshold smallint  NOT NULL,
  appeal_supported      boolean      NOT NULL DEFAULT false,
  contact_buyer_required boolean     NOT NULL DEFAULT true,
  appeal_quota_limit    integer      NOT NULL DEFAULT 0,
  quota_period          varchar(16)  NOT NULL DEFAULT 'MONTHLY',
  appeal_window_days    integer      NOT NULL DEFAULT 30,

  -- ---- 原因分类与申诉判定（分类时落） ----
  cause_code            varchar(24),
  cause_label           varchar(200),
  cause_note            varchar(1000),
  cause_rule_id         uuid,
  cause_rule_version    integer,
  -- 判定结果 + 判定依据（**依据必填**：只给结论不给依据 = 无法复核）。
  appeal_eligible       boolean      NOT NULL DEFAULT false,
  appeal_basis          varchar(2000),
  appeal_assessed_at    timestamptz,

  -- ---- 处理状态与解决状态 ----
  -- 处理状态：OPEN（待处理）/ IN_PROGRESS（处理中）/ RESOLVED（已解决）/ CLOSED（已关闭）。
  case_status           varchar(24)  NOT NULL DEFAULT 'OPEN',
  -- 解决状态：UNRESOLVED（未解决）/ RESOLVED（已解决）/ CLOSED_UNRESOLVED（已关闭未解决）。
  resolution_status     varchar(24)  NOT NULL DEFAULT 'UNRESOLVED',
  resolution_note       varchar(1000),
  resolution_at         timestamptz,
  assigned_to           uuid,
  note                  varchar(500),
  created_by            uuid,
  created_at            timestamptz  NOT NULL DEFAULT now(),
  updated_at            timestamptz  NOT NULL DEFAULT now(),

  CONSTRAINT crm_negative_review_case_rating_ck CHECK (review_rating BETWEEN 1 AND 5),
  CONSTRAINT crm_negative_review_case_threshold_ck
    CHECK (negative_rating_threshold BETWEEN 1 AND 5),
  CONSTRAINT crm_negative_review_case_cause_ck
    CHECK (cause_code IS NULL OR cause_code IN ('PRODUCT','LOGISTICS','SERVICE','MISJUDGMENT')),
  CONSTRAINT crm_negative_review_case_status_ck
    CHECK (case_status IN ('OPEN','IN_PROGRESS','RESOLVED','CLOSED')),
  CONSTRAINT crm_negative_review_case_resolution_ck
    CHECK (resolution_status IN ('UNRESOLVED','RESOLVED','CLOSED_UNRESOLVED')),
  -- 🔴 「已解决 / 已关闭」必须带结论时刻与说明：
  --   否则「已解决」是一个查无实据的说法，解决率分子也会跟着虚高。
  CONSTRAINT crm_negative_review_case_resolution_ck2
    CHECK (resolution_status = 'UNRESOLVED'
        OR (resolution_at IS NOT NULL AND resolution_note IS NOT NULL
            AND length(btrim(resolution_note)) > 0)),
  -- 🔴 处理状态与解决状态必须自洽（"已解决"不等于"已关闭未解决"）。
  CONSTRAINT crm_negative_review_case_status_resolution_ck
    CHECK ( (case_status = 'RESOLVED' AND resolution_status = 'RESOLVED')
         OR (case_status = 'CLOSED'  AND resolution_status IN ('RESOLVED','CLOSED_UNRESOLVED'))
         OR (case_status IN ('OPEN','IN_PROGRESS') AND resolution_status = 'UNRESOLVED') ),
  -- 🔴 一旦分类，**判定依据与判定时刻必须同时存在**：
  --   只给「可申诉 / 不可申诉」不给依据，这条判定永远无法复核。
  CONSTRAINT crm_negative_review_case_assessed_ck
    CHECK (cause_code IS NULL
        OR (appeal_basis IS NOT NULL AND length(btrim(appeal_basis)) > 0
            AND appeal_assessed_at IS NOT NULL))
);

CREATE UNIQUE INDEX crm_negative_review_case_no_uniq
  ON crm_negative_review_case(tenant_id, case_no);
-- 🔴 同一条评价**只允许一个处理单**：两条处理单会让「解决率」的分母翻倍，
--   也让「这条差评到底谁在跟」说不清。
CREATE UNIQUE INDEX crm_negative_review_case_review_uniq
  ON crm_negative_review_case(tenant_id, review_id);
CREATE INDEX crm_negative_review_case_list_idx
  ON crm_negative_review_case(tenant_id, created_at DESC, id);
CREATE INDEX crm_negative_review_case_status_idx
  ON crm_negative_review_case(tenant_id, case_status, created_at DESC);
CREATE INDEX crm_negative_review_case_resolution_idx
  ON crm_negative_review_case(tenant_id, resolution_status, created_at DESC);
CREATE INDEX crm_negative_review_case_cause_idx
  ON crm_negative_review_case(tenant_id, cause_code, created_at DESC);
CREATE INDEX crm_negative_review_case_platform_idx
  ON crm_negative_review_case(tenant_id, platform_code, created_at DESC);
CREATE INDEX crm_negative_review_case_shop_idx
  ON crm_negative_review_case(tenant_id, shop_id, created_at DESC);
CREATE INDEX crm_negative_review_case_appeal_idx
  ON crm_negative_review_case(tenant_id, appeal_eligible, created_at DESC);

-- ---------------------------------------------------------------------------
-- 1.3 crm_negative_review_action · 处理动作流水
-- ---------------------------------------------------------------------------
CREATE TABLE crm_negative_review_action (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  action_no         varchar(32) NOT NULL,
  case_id           uuid        NOT NULL,
  case_no           varchar(32) NOT NULL,
  -- CONTACT_BUYER（联系买家）/ REQUEST_MODIFICATION（请求修改）/
  -- APPEAL_SUBMISSION（申诉提交）/ APPEAL_RESULT（申诉结果）/
  -- APPEAL_CANCEL（撤回申诉）/ AFTER_SALES_HANDOFF（售后转交）/
  -- RESOLVE（标记已解决）/ CLOSE（关闭）/ NOTE（备注）。
  action_type       varchar(24) NOT NULL,
  -- PLATFORM_API（走平台接口）/ MANUAL_HANDOFF（人工交接）/ INTERNAL（内部动作）。
  transport         varchar(16) NOT NULL DEFAULT 'INTERNAL',
  -- SUCCEEDED / FAILED / PENDING。
  -- 🔴 「已联系买家」= 存在一条 outcome=SUCCEEDED 的 CONTACT_BUYER 动作；
  --   一条 FAILED 的联系**不**算「走完了联系买家这一步」。
  outcome           varchar(16) NOT NULL,
  outcome_note      varchar(500),
  summary           varchar(500),
  detail            text,
  -- 🔴 售后转交意图：**只落原因，不落下游单据 id**（11 售后未交付，见文件头第三节）。
  after_sales_reason varchar(1000),
  appeal_id         uuid,
  appeal_no         varchar(32),
  operator_id       uuid,
  operator_name     varchar(200),
  action_at         timestamptz NOT NULL,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),

  CONSTRAINT crm_negative_review_action_type_ck
    CHECK (action_type IN ('CONTACT_BUYER','REQUEST_MODIFICATION','APPEAL_SUBMISSION',
      'APPEAL_RESULT','APPEAL_CANCEL','AFTER_SALES_HANDOFF','RESOLVE','CLOSE','NOTE')),
  CONSTRAINT crm_negative_review_action_transport_ck
    CHECK (transport IN ('PLATFORM_API','MANUAL_HANDOFF','INTERNAL')),
  CONSTRAINT crm_negative_review_action_outcome_ck
    CHECK (outcome IN ('SUCCEEDED','FAILED','PENDING')),
  -- 🔴 售后转交必须写原因：没有原因的转交等于没转。
  CONSTRAINT crm_negative_review_action_handoff_ck
    CHECK (action_type <> 'AFTER_SALES_HANDOFF'
        OR (after_sales_reason IS NOT NULL AND length(btrim(after_sales_reason)) > 0)),
  -- 🔴 转交原因只允许出现在转交动作上（避免别处的文本混进这个字段）。
  CONSTRAINT crm_negative_review_action_handoff_only_ck
    CHECK (after_sales_reason IS NULL OR action_type = 'AFTER_SALES_HANDOFF'),
  -- 🔴 申诉相关动作必须挂在具体的申诉任务上。
  CONSTRAINT crm_negative_review_action_appeal_ck
    CHECK (action_type NOT IN ('APPEAL_SUBMISSION','APPEAL_RESULT','APPEAL_CANCEL')
        OR appeal_id IS NOT NULL),
  -- 🔴 解决 / 关闭必须写说明：否则「已解决」是一个查无实据的说法。
  CONSTRAINT crm_negative_review_action_summary_ck
    CHECK (action_type NOT IN ('RESOLVE','CLOSE')
        OR (summary IS NOT NULL AND length(btrim(summary)) > 0))
);

CREATE UNIQUE INDEX crm_negative_review_action_no_uniq
  ON crm_negative_review_action(tenant_id, action_no);
CREATE INDEX crm_negative_review_action_by_case
  ON crm_negative_review_action(tenant_id, case_id, action_at DESC, id);
CREATE INDEX crm_negative_review_action_by_appeal
  ON crm_negative_review_action(tenant_id, appeal_id);
CREATE INDEX crm_negative_review_action_list_idx
  ON crm_negative_review_action(tenant_id, created_at DESC, id);

-- ---------------------------------------------------------------------------
-- 1.4 crm_negative_review_appeal · 申诉任务
-- ---------------------------------------------------------------------------
CREATE TABLE crm_negative_review_appeal (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  appeal_no         varchar(32) NOT NULL,
  case_id           uuid        NOT NULL,
  case_no           varchar(32) NOT NULL,
  review_id         uuid        NOT NULL,
  review_no         varchar(32) NOT NULL,
  shop_id           uuid        NOT NULL,
  platform_code     varchar(32) NOT NULL,
  -- REMOVAL（申请移除差评）/ MODIFICATION（申请修改差评内容）/
  -- POLICY_VIOLATION（平台违规申诉）。
  appeal_type       varchar(24) NOT NULL,
  cause_code        varchar(24) NOT NULL,
  reason            varchar(2000) NOT NULL,
  content           text,
  -- DRAFT（待提交）/ SUBMITTED（已提交）/ APPROVED（通过）/ REJECTED（驳回）/
  -- CANCELLED（已撤回）。
  appeal_status     varchar(16) NOT NULL DEFAULT 'DRAFT',
  -- MANUAL_HANDOFF（人工在平台侧提交后回填）/ PLATFORM_API（平台接口，当前未接入）。
  transport         varchar(16) NOT NULL DEFAULT 'MANUAL_HANDOFF',
  submitted_at      timestamptz,
  platform_reference varchar(128),
  platform_receipt  text,
  decided_at        timestamptz,
  decided_result    varchar(16),
  decided_reason    varchar(1000),
  cancelled_at      timestamptz,
  cancel_reason     varchar(500),
  -- 🔴 申诉配额计数：建申诉任务即占用（撤回归还）。
  --   quota_period_key 例：MONTHLY → '2026-10'；QUARTERLY → '2026-Q4'；YEARLY → '2026'。
  quota_period_key  varchar(16) NOT NULL,
  quota_index       integer     NOT NULL,
  quota_limit       integer     NOT NULL,
  quota_used_before integer     NOT NULL,
  created_by        uuid,
  created_at        timestamptz  NOT NULL DEFAULT now(),
  updated_at        timestamptz  NOT NULL DEFAULT now(),

  CONSTRAINT crm_negative_review_appeal_type_ck
    CHECK (appeal_type IN ('REMOVAL','MODIFICATION','POLICY_VIOLATION')),
  CONSTRAINT crm_negative_review_appeal_cause_ck
    CHECK (cause_code IN ('PRODUCT','LOGISTICS','SERVICE','MISJUDGMENT')),
  CONSTRAINT crm_negative_review_appeal_status_ck
    CHECK (appeal_status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','CANCELLED')),
  CONSTRAINT crm_negative_review_appeal_transport_ck
    CHECK (transport IN ('PLATFORM_API','MANUAL_HANDOFF')),
  CONSTRAINT crm_negative_review_appeal_reason_ck
    CHECK (length(btrim(reason)) > 0),
  -- 🔴 「已提交 / 通过 / 驳回」必须有提交时刻。
  CONSTRAINT crm_negative_review_appeal_submitted_ck
    CHECK (appeal_status NOT IN ('SUBMITTED','APPROVED','REJECTED') OR submitted_at IS NOT NULL),
  -- 🔴 「通过 / 驳回」必须有判定时刻与判定结果。
  CONSTRAINT crm_negative_review_appeal_decided_ck
    CHECK (appeal_status NOT IN ('APPROVED','REJECTED')
        OR (decided_at IS NOT NULL AND decided_result IS NOT NULL)),
  CONSTRAINT crm_negative_review_appeal_decided_result_ck
    CHECK (decided_result IS NULL OR decided_result IN ('APPROVED','REJECTED')),
  -- 🔴 「已撤回」必须带撤回时刻与原因。
  CONSTRAINT crm_negative_review_appeal_cancelled_ck
    CHECK (appeal_status <> 'CANCELLED'
        OR (cancelled_at IS NOT NULL AND cancel_reason IS NOT NULL
            AND length(btrim(cancel_reason)) > 0)),
  CONSTRAINT crm_negative_review_appeal_quota_ck
    CHECK (quota_index >= 1 AND quota_limit >= 0 AND quota_used_before >= 0)
);

CREATE UNIQUE INDEX crm_negative_review_appeal_no_uniq
  ON crm_negative_review_appeal(tenant_id, appeal_no);
CREATE INDEX crm_negative_review_appeal_by_case
  ON crm_negative_review_appeal(tenant_id, case_id, created_at DESC, id);
-- 配额计数用（按平台 + 周期统计非撤回的申诉任务）。
CREATE INDEX crm_negative_review_appeal_quota_idx
  ON crm_negative_review_appeal(tenant_id, platform_code, quota_period_key, appeal_status);
CREATE INDEX crm_negative_review_appeal_status_idx
  ON crm_negative_review_appeal(tenant_id, appeal_status, created_at DESC);

-- ============================================================================
-- 2. 序列（单独授权，见第五节）
-- ============================================================================
CREATE SEQUENCE crm_negative_review_rule_no_seq   START 1;
CREATE SEQUENCE crm_negative_review_case_no_seq   START 1;
CREATE SEQUENCE crm_negative_review_action_no_seq START 1;
CREATE SEQUENCE crm_negative_review_appeal_no_seq START 1;

-- ============================================================================
-- 3. 触发器
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 3.1 guard_crm_negative_review_case_target · 处理单必须挂在存在的评价上
-- ---------------------------------------------------------------------------
-- 为什么：处理单是「**针对哪一条差评**的处理记录」。挂在一个不存在的评价上，
--   「这条差评有没有被处理过」就永远查不清，而且解决率的分母会混进幽灵单。
-- ⚠️ 触发器里**不用外键**：外键会打破同服务既有测试的 TRUNCATE。
CREATE FUNCTION guard_crm_negative_review_case_target() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_no varchar(32);
  v_shop uuid;
  v_platform varchar(32);
  v_rating smallint;
BEGIN
  SELECT review_no, shop_id, platform_code, rating
    INTO v_no, v_shop, v_platform, v_rating
    FROM crm_product_review
   WHERE tenant_id = NEW.tenant_id AND id = NEW.review_id;
  IF v_no IS NULL THEN
    RAISE EXCEPTION '被处理的评价 % 不存在（tenant=%），不能建这张处理单',
      NEW.review_id, NEW.tenant_id USING ERRCODE='foreign_key_violation';
  END IF;
  -- 🔴 快照必须与评价一致：否则「这条处理单说的是哪一家店的哪条差评」会出现两个答案。
  IF v_shop IS DISTINCT FROM NEW.shop_id OR v_platform IS DISTINCT FROM NEW.platform_code THEN
    RAISE EXCEPTION '处理单的店铺/平台与评价不一致（评价属 % / %），不能建这张处理单',
      v_platform, v_shop USING ERRCODE='foreign_key_violation';
  END IF;
  IF v_rating IS DISTINCT FROM NEW.review_rating THEN
    RAISE EXCEPTION '处理单记录的星级（%）与评价实际星级（%）不一致', NEW.review_rating, v_rating
      USING ERRCODE='foreign_key_violation';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_negative_review_case_target_trg
  BEFORE INSERT ON crm_negative_review_case
  FOR EACH ROW EXECUTE FUNCTION guard_crm_negative_review_case_target();

-- ---------------------------------------------------------------------------
-- 3.2 guard_crm_negative_review_case_identity · 处理单身份与状态机
-- ---------------------------------------------------------------------------
-- 冻结的是「**这张单针对的是哪条评价、来自哪家店、哪个平台、星级多少**」——
--   改了它，「这条差评的处理记录」在历史里就有两个答案。
-- 放行的是**处理过程中的结论**（原因分类 / 申诉判定 / 状态 / 解决说明）。
CREATE FUNCTION guard_crm_negative_review_case_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id THEN
    RAISE EXCEPTION '处理单的租户归属建后不可改' USING ERRCODE='23514';
  END IF;
  IF NEW.review_id IS DISTINCT FROM OLD.review_id THEN
    RAISE EXCEPTION '处理单针对的评价建后不可改：改它会让「这条差评的处理记录」在历史里出现两个答案' USING ERRCODE='23514';
  END IF;
  IF NEW.shop_id IS DISTINCT FROM OLD.shop_id OR NEW.platform_code IS DISTINCT FROM OLD.platform_code THEN
    RAISE EXCEPTION '处理单的店铺/平台建后不可改' USING ERRCODE='23514';
  END IF;
  IF NEW.review_rating IS DISTINCT FROM OLD.review_rating THEN
    RAISE EXCEPTION '处理单记录的星级建后不可改' USING ERRCODE='23514';
  END IF;
  IF NEW.negative_rating_threshold IS DISTINCT FROM OLD.negative_rating_threshold THEN
    RAISE EXCEPTION '处理单按哪个阈值判为差评建后不可改：改它会让「当时凭什么说这是差评」无从查证' USING ERRCODE='23514';
  END IF;
  IF NEW.policy_rule_id IS DISTINCT FROM OLD.policy_rule_id
     OR NEW.policy_rule_version IS DISTINCT FROM OLD.policy_rule_version THEN
    RAISE EXCEPTION '处理单引用的平台策略版本建后不可改' USING ERRCODE='23514';
  END IF;
  -- 状态流转：OPEN → IN_PROGRESS → RESOLVED / CLOSED。
  IF NEW.case_status IS DISTINCT FROM OLD.case_status THEN
    IF NOT ( (OLD.case_status = 'OPEN'        AND NEW.case_status IN ('IN_PROGRESS','RESOLVED','CLOSED'))
           OR (OLD.case_status = 'IN_PROGRESS' AND NEW.case_status IN ('RESOLVED','CLOSED')) ) THEN
      RAISE EXCEPTION '处理单状态只能 OPEN → IN_PROGRESS → RESOLVED / CLOSED 单向流转，不能从 % 回到 %',
        OLD.case_status, NEW.case_status USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_negative_review_case_identity_trg
  BEFORE UPDATE ON crm_negative_review_case
  FOR EACH ROW EXECUTE FUNCTION guard_crm_negative_review_case_identity();

-- ---------------------------------------------------------------------------
-- 3.3 guard_crm_negative_review_action_target · 动作必须挂在存在的处理单上
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_crm_negative_review_action_target() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_no varchar(32);
  v_appeal_case uuid;
BEGIN
  SELECT case_no INTO v_no FROM crm_negative_review_case
   WHERE tenant_id = NEW.tenant_id AND id = NEW.case_id;
  IF v_no IS NULL THEN
    RAISE EXCEPTION '被记录动作的处理单 % 不存在（tenant=%），不能登记这个动作',
      NEW.case_id, NEW.tenant_id USING ERRCODE='foreign_key_violation';
  END IF;
  IF v_no IS DISTINCT FROM NEW.case_no THEN
    RAISE EXCEPTION '动作记录的处理单号（%）与实际处理单号（%）不一致', NEW.case_no, v_no
      USING ERRCODE='foreign_key_violation';
  END IF;
  -- 申诉相关动作必须挂在**该处理单自己的**申诉任务上。
  IF NEW.appeal_id IS NOT NULL THEN
    SELECT case_id INTO v_appeal_case FROM crm_negative_review_appeal
     WHERE tenant_id = NEW.tenant_id AND id = NEW.appeal_id;
    IF v_appeal_case IS NULL THEN
      RAISE EXCEPTION '引用的申诉任务 % 不存在（tenant=%），不能登记这个动作',
        NEW.appeal_id, NEW.tenant_id USING ERRCODE='foreign_key_violation';
    END IF;
    IF v_appeal_case IS DISTINCT FROM NEW.case_id THEN
      RAISE EXCEPTION '申诉任务不属于这张处理单（申诉属于 %，动作属于 %），不能登记这个动作',
        v_appeal_case, NEW.case_id USING ERRCODE='foreign_key_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_negative_review_action_target_trg
  BEFORE INSERT ON crm_negative_review_action
  FOR EACH ROW EXECUTE FUNCTION guard_crm_negative_review_action_target();

-- ---------------------------------------------------------------------------
-- 3.4 guard_crm_negative_review_action_identity · 动作流水不可改写
-- ---------------------------------------------------------------------------
-- 动作流水是**事实**：当时做了什么、谁做的、结果如何。
--   改写它 = 伪造处理过程。撤回/更正的办法是追加新动作。
CREATE FUNCTION guard_crm_negative_review_action_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id THEN
    RAISE EXCEPTION '动作的租户归属建后不可改' USING ERRCODE='23514';
  END IF;
  IF NEW.case_id IS DISTINCT FROM OLD.case_id THEN
    RAISE EXCEPTION '动作所属的处理单建后不可改' USING ERRCODE='23514';
  END IF;
  IF NEW.action_type IS DISTINCT FROM OLD.action_type THEN
    RAISE EXCEPTION '动作类型建后不可改：改它会让「这一步到底是什么」在历史里说不清' USING ERRCODE='23514';
  END IF;
  IF NEW.action_at IS DISTINCT FROM OLD.action_at THEN
    RAISE EXCEPTION '动作发生时刻建后不可改' USING ERRCODE='23514';
  END IF;
  IF NEW.after_sales_reason IS DISTINCT FROM OLD.after_sales_reason THEN
    RAISE EXCEPTION '售后转交原因建后不可改' USING ERRCODE='23514';
  END IF;
  IF NEW.operator_id IS DISTINCT FROM OLD.operator_id THEN
    RAISE EXCEPTION '动作的操作者建后不可改' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_negative_review_action_identity_trg
  BEFORE UPDATE ON crm_negative_review_action
  FOR EACH ROW EXECUTE FUNCTION guard_crm_negative_review_action_identity();

-- ---------------------------------------------------------------------------
-- 3.5 guard_crm_negative_review_appeal_target · 申诉任务必须挂在存在的处理单上
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_crm_negative_review_appeal_target() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_no varchar(32);
  v_review uuid;
  v_platform varchar(32);
BEGIN
  SELECT case_no, review_id, platform_code INTO v_no, v_review, v_platform
    FROM crm_negative_review_case
   WHERE tenant_id = NEW.tenant_id AND id = NEW.case_id;
  IF v_no IS NULL THEN
    RAISE EXCEPTION '被申诉的处理单 % 不存在（tenant=%），不能建这个申诉任务',
      NEW.case_id, NEW.tenant_id USING ERRCODE='foreign_key_violation';
  END IF;
  IF v_review IS DISTINCT FROM NEW.review_id OR v_platform IS DISTINCT FROM NEW.platform_code THEN
    RAISE EXCEPTION '申诉任务的评价/平台与处理单不一致（处理单属 % / %），不能建这个申诉任务',
      v_platform, v_review USING ERRCODE='foreign_key_violation';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_negative_review_appeal_target_trg
  BEFORE INSERT ON crm_negative_review_appeal
  FOR EACH ROW EXECUTE FUNCTION guard_crm_negative_review_appeal_target();

-- ---------------------------------------------------------------------------
-- 3.6 guard_crm_negative_review_appeal_identity · 申诉任务状态机
-- ---------------------------------------------------------------------------
-- DRAFT → SUBMITTED → APPROVED / REJECTED；DRAFT → CANCELLED。
-- 🔴 终态（APPROVED / REJECTED / CANCELLED）不可再流转 ——
--   平台判过一次就翻供会让「这条差评到底有没有被处理掉」永远答不了。
CREATE FUNCTION guard_crm_negative_review_appeal_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id THEN
    RAISE EXCEPTION '申诉任务的租户归属建后不可改' USING ERRCODE='23514';
  END IF;
  IF NEW.case_id IS DISTINCT FROM OLD.case_id THEN
    RAISE EXCEPTION '申诉任务所属的处理单建后不可改' USING ERRCODE='23514';
  END IF;
  IF NEW.appeal_type IS DISTINCT FROM OLD.appeal_type THEN
    RAISE EXCEPTION '申诉类型建后不可改：改它会让「我们到底申诉了什么」无从查证' USING ERRCODE='23514';
  END IF;
  IF NEW.cause_code IS DISTINCT FROM OLD.cause_code THEN
    RAISE EXCEPTION '申诉对应的原因分类建后不可改' USING ERRCODE='23514';
  END IF;
  IF NEW.reason IS DISTINCT FROM OLD.reason THEN
    RAISE EXCEPTION '申诉理由建后不可改：申诉理由是提交给平台的事实' USING ERRCODE='23514';
  END IF;
  IF NEW.quota_period_key IS DISTINCT FROM OLD.quota_period_key
     OR NEW.quota_index IS DISTINCT FROM OLD.quota_index THEN
    RAISE EXCEPTION '申诉的配额计数建后不可改：改它会让「配额用了多少」答不了' USING ERRCODE='23514';
  END IF;
  IF NEW.appeal_status IS DISTINCT FROM OLD.appeal_status THEN
    IF NOT ( (OLD.appeal_status = 'DRAFT' AND NEW.appeal_status IN ('SUBMITTED','CANCELLED'))
           OR (OLD.appeal_status = 'SUBMITTED' AND NEW.appeal_status IN ('APPROVED','REJECTED')) ) THEN
      RAISE EXCEPTION '申诉任务状态只能 DRAFT → SUBMITTED → APPROVED/REJECTED 或 DRAFT → CANCELLED，不能从 % 回到 %',
        OLD.appeal_status, NEW.appeal_status USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_negative_review_appeal_identity_trg
  BEFORE UPDATE ON crm_negative_review_appeal
  FOR EACH ROW EXECUTE FUNCTION guard_crm_negative_review_appeal_identity();

-- ============================================================================
-- 4. RLS（ENABLE + FORCE）与授权
-- ============================================================================
-- 与 V99 / V220 / V228 / V292 / V294 / V298 同一口径：策略名固定 isolation，
-- USING 与 WITH CHECK 两侧同表达式。
-- 表达式里的 nullif 不是洁癖：事务局部的 set_config 提交之后，PostgreSQL 会把自定义 GUC
-- **复位成空串**而不是「回到未设置」，直接 cast 会报 uuid 语法错，
-- 而 nullif 把它归一成 NULL —— 条件不成立 → 一行都看不到。
-- 也就是说「忘记绑定租户」当场失败，而不是静默返回全表。
ALTER TABLE crm_negative_review_rule   ENABLE ROW LEVEL SECURITY;
ALTER TABLE crm_negative_review_rule   FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON crm_negative_review_rule
  USING (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid)
  WITH CHECK (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid);

ALTER TABLE crm_negative_review_case   ENABLE ROW LEVEL SECURITY;
ALTER TABLE crm_negative_review_case   FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON crm_negative_review_case
  USING (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid)
  WITH CHECK (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid);

ALTER TABLE crm_negative_review_action ENABLE ROW LEVEL SECURITY;
ALTER TABLE crm_negative_review_action FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON crm_negative_review_action
  USING (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid)
  WITH CHECK (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid);

ALTER TABLE crm_negative_review_appeal ENABLE ROW LEVEL SECURITY;
ALTER TABLE crm_negative_review_appeal FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON crm_negative_review_appeal
  USING (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid)
  WITH CHECK (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid);

GRANT SELECT,INSERT,UPDATE,DELETE ON
  crm_negative_review_rule, crm_negative_review_case,
  crm_negative_review_action, crm_negative_review_appeal TO crm_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  crm_negative_review_rule, crm_negative_review_case,
  crm_negative_review_action, crm_negative_review_appeal TO starcon_app;

-- 🔴 序列单独授权：GRANT ... ON ALL TABLES **不覆盖序列**，漏授权会变成
-- 「第一条处理单就报权限不足」（10.3.2.2 踩过这个坑，这里不重复）。
GRANT USAGE, SELECT ON SEQUENCE crm_negative_review_rule_no_seq   TO crm_app;
GRANT USAGE, SELECT ON SEQUENCE crm_negative_review_case_no_seq   TO crm_app;
GRANT USAGE, SELECT ON SEQUENCE crm_negative_review_action_no_seq TO crm_app;
GRANT USAGE, SELECT ON SEQUENCE crm_negative_review_appeal_no_seq TO crm_app;
GRANT USAGE, SELECT ON SEQUENCE crm_negative_review_rule_no_seq   TO starcon_app;
GRANT USAGE, SELECT ON SEQUENCE crm_negative_review_case_no_seq   TO starcon_app;
GRANT USAGE, SELECT ON SEQUENCE crm_negative_review_action_no_seq TO starcon_app;
GRANT USAGE, SELECT ON SEQUENCE crm_negative_review_appeal_no_seq TO starcon_app;

REVOKE ALL ON flyway_schema_history FROM crm_app;

-- ============================================================================
-- 5. 留给上游 / 下游的口子（不是本模块的缺口）
-- ============================================================================
--   * **上游 15.2.1.1 评价列表（已交付）**：本模块**只读引用** `crm_product_review`
--     （差评的来源），**不改**它的表结构、端点与权限。
--   * **下游 15.1.1.2 联系买家（已交付）**：本模块的「联系买家」是**差评处理流程内的一步**，
--     经 `PlatformMessageSendPort` 走平台站内信；该适配器**当前未接入**，
--     所以平台侧动作由服务层返回 409 且**一行都不写**，运营走**人工交接**登记。
--   * **下游 11 售后（未交付）**：本模块**只落转交意图**（原因必填），
--     **不写下游单据 id、不编下游 id**（见文件头第三节）。
--   * **平台申诉 / 修改申请接口（未接入）**：见文件头第二节 ——
--     端点如实答「未接入」（409）且**一条假记录都不写**。
