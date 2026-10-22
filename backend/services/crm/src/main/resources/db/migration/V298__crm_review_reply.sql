-- 15.2.1.2 评价回复：回复评价（公开回复 + 部分平台的卖家评论追加），支持模板、多语言、
-- 批量回复同类评价。
--
-- 蓝图 15 卷 15.2.1.2 的原话：
--   「职责：回复评价（公开回复 + 部分平台的卖家评论追加），支持模板、多语言、批量回复同类评价。
--     输入：回复内容；目标评价。输出：回复记录 + 平台回执。
--     关系：上游 = 15.2.1.1；下游 = 平台接口。
--     **差评回复是转化机会也是合规风险**，模板必须符合平台措辞规范。」
--
-- ============================================================ 零、本文件与既有模块的边界
--
-- 本文件是 **crm 数据库的第八个迁移**（前七个：15.4.2.1 知识库 V99 / 15.2.2.1 索评 V220 /
-- 15.2.2.2 索评规则 V228 / 15.1.2.1 买家邮件 V292 / 15.1.1.1 消息收件箱 V293 /
-- 15.2.1.1 评价列表 V294 / 15.3.2.2 客户标签 V295 / 15.3.3.1 购物车挽回 V296）。
--
-- ⇒ **本文件与它们不共用任何一张表，也不 ALTER / DROP 任何既有表**：
--   * 15.2.1.1 评价列表（V294）的 `crm_product_review` 是**评价本体**，
--     本模块只**只读引用**它（回复的目标评价），**不改它的表结构、端点与权限**；
--   * 15.2.2.1 索评（V220）的 `crm_review_send_record` 是「**索评请求**的发送记录」，
--     与本模块的「**评价回复**的投递记录」是**两件事**，**刻意不共用表名**；
--   * 15.2.2.2 索评规则（V228）的 `crm_review_message_template` /
--     `crm_review_message_variant` 是**索评话术**（催评），受众是**买家**；
--     本模块的 `crm_review_reply_template` 是**评价回复话术**，受众是**公开平台**，
--     措辞规范完全不同（公开回复里塞「快来下单」是平台违规）⇒ **刻意另起表**。
--
-- ============================================================ 一、🔴 加粗句「模板必须符合平台措辞规范」
--
-- 这句话的落点**不是**「在回复表单上写一句提示」，而是一条**可执行的校验链**：
--
--   1. **规范本身要能配置**：`crm_review_reply_policy`（租户级、逐平台、逐回复形态、
--      **带版本与生效时间**）。平台政策会变 ⇒ 规范也是版本化的。
--   2. **校验要真的拦得住**：服务层对模板的每条多语言正文与每条回复正文跑
--      **措辞校验**（长度 / 导流联系方式 / 促销措辞 / 外链 / 感叹问号上限 /
--      差评必致歉 / 租户自定义禁用词 / 质疑买家的措辞），**不合规直接 400 拒绝**，
--      **不合规的模板根本存不进来**。
--   3. **历史要能追**：回复记录里落 `template_id` + `template_version` + `template_key`
--      + `template_locale` + `policy_version` ⇒ 事后能回答
--      「这条回复当时用的哪一版模板、当时的规范是哪一版」。
--      没有这四个字段，「平台政策变了之后这条回复算不算违规」永远答不了。
--
-- ⇒ 模板**版本化**的实现方式：**一个模板 = 一行 = 一个版本**。
--   同一 `template_key` 下 `version` 递增；改内容 = **发新版本**，不是原地改。
--   （原地改会让「当时用的哪一版」当场失效。）
--
-- ============================================================ 二、🔴 平台投递未接入 —— 绝不返回假回执
--
-- `channel/vendor` 目前**只有 OAuth 适配器**（amazon / ebay / lazada / shopee / shopify /
-- temu / tiktok / walmart），**没有任何平台的买家评价回复接口**。
-- ⇒ 本文件**不假装**投递能力存在：
--   * 投递端点由 `UnavailablePlatformReviewReplyPort` 明确答「未接入」，
--     服务层据此返回 **409**；
--   * 但**投递尝试本身要留痕**：`crm_review_reply_delivery` 记一条
--     `outcome = 'NOT_INTEGRATED'` —— 一次都没发出去的尝试，也要能看到
--     「我们试过、没发出去、为什么」。
--   * 🔴 **绝不写 `DELIVERED` / 绝不编造 `platform_receipt`**：
--     一条返回「已回复」的假回执会让「评价已经回复给买家了」看起来发生过，
--     而现场一条真回复都没发出去。
--
-- ⚠️ 因此本模块**不修改** `crm_product_review.replied`：
--   15.2.1.1 的 `replied` 语义是「**平台侧**是否已有卖家公开回复」。
--   本模块的回复**尚未投递到平台** ⇒ 平台侧仍然没有回复 ⇒ 该列必须保持 false。
--   把没发出去的回复算成「平台已回复」，比不更新更糟。
--   本模块自己的「有没有回复记录」由**本模块的表**回答（回复列表 / 批次详情）。
--
-- ============================================================ 三、🔴 15.2.1.3 差评处理未交付
--
-- 差评**专项处理**（标记原因 / 判定申诉 / 联系买家解决 / 跟踪解决率）属 15.2.1.3，未交付。
-- ⇒ 本模块**不建**差评标记 / 申诉 / 解决率；
--   但**「回复差评」本身是本模块的职责** —— 差评回复正是「转化机会」的那一半，
--   所以对**任意评价（含差评）**都能回复，只是不做「差评的专门处理流程」。
--   能力声明里 `negative_review_handling` 维度 available=false + ownerModule=15.2.1.3。
--
-- ============================================================ 四、六张表
--
--   crm_review_reply_policy           平台措辞规范（租户配置，逐平台 + 逐形态，版本化）
--   crm_review_reply_template         回复模板（一行 = 一个版本，带生效时间）
--   crm_review_reply_template_variant 模板的多语言条目（每版本每语言一条）
--   crm_review_reply_batch            批量回复批次（一次批量 = 一组同类评价的回复）
--   crm_review_reply                  回复记录（= 蓝图输出句里的「回复记录」）
--   crm_review_reply_delivery         投递尝试与平台回执（= 蓝图输出句里的「平台回执」）
--
-- 为什么「规范」也要落库：规范若是代码里的常量，平台改了政策就只能改代码发版，
-- 而**运营改一句「这个词不许出现」要等一次发版** ⇒ 规范必须是可以配置的数据。
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
--   * 本模块**没有金额**：评价回复里不出现金额。
--   * 数量（长度上限 / 计数）一律 **integer**；本模块**没有**需要 NUMERIC(19,6) 的量。
--
-- ============================================================================
-- 零之一、角色
-- ============================================================================
-- 与 V99 / V220 / V228 / V292 / V294 同一口径：角色可能已由运维预置，因此只在缺失时创建。
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
-- 1.1 crm_review_reply_policy · 平台措辞规范
-- ---------------------------------------------------------------------------
-- 逐（平台 × 回复形态）一行；平台政策会变 ⇒ **版本化 + 生效时间**。
-- 一条回复必须落在**当时生效的那一版规范**下 —— 否则「平台政策变了之后这条回复
-- 还合不合规」永远答不了。
CREATE TABLE crm_review_reply_policy (
  id                    uuid PRIMARY KEY,
  tenant_id             uuid NOT NULL,
  policy_no             varchar(32)  NOT NULL,
  platform_code         varchar(32)  NOT NULL,
  reply_type            varchar(32)  NOT NULL,
  version               integer      NOT NULL,
  effective_from        timestamptz  NOT NULL,
  effective_to          timestamptz,

  -- 长度区间（按 Unicode 码点计，避免 emoji / 中文按 UTF-16 单元算多一倍）。
  min_length            integer      NOT NULL DEFAULT 10,
  max_length            integer      NOT NULL DEFAULT 500,

  -- 措辞开关：默认全部**收紧**（公开回复里塞促销 / 导流是平台违规的常见来源）。
  allow_contact_info    boolean      NOT NULL DEFAULT false,
  allow_promotion       boolean      NOT NULL DEFAULT false,
  allow_external_link   boolean      NOT NULL DEFAULT false,
  -- 感叹号 / 问号上限（过度标点=情绪化措辞，平台与买家都不买账）。
  max_exclamation       integer      NOT NULL DEFAULT 1,
  max_question_marks    integer      NOT NULL DEFAULT 2,
  -- 差评回复必须致歉（只对 NEGATIVE 档的评价生效）。
  require_apology       boolean      NOT NULL DEFAULT true,
  -- 租户自定义禁用词（jsonb 字符串数组）；与内置禁用词**叠加**。
  banned_terms          jsonb        NOT NULL DEFAULT '[]'::jsonb,

  status                varchar(16)  NOT NULL DEFAULT 'ACTIVE',
  note                  varchar(500),
  created_by            uuid,
  created_at            timestamptz  NOT NULL DEFAULT now(),
  updated_at            timestamptz  NOT NULL DEFAULT now(),

  CONSTRAINT crm_review_reply_policy_reply_type_ck
    CHECK (reply_type IN ('PUBLIC_REPLY','SELLER_COMMENT_APPEND')),
  CONSTRAINT crm_review_reply_policy_status_ck
    CHECK (status IN ('ACTIVE','ARCHIVED')),
  CONSTRAINT crm_review_reply_policy_version_ck
    CHECK (version >= 1),
  CONSTRAINT crm_review_reply_policy_length_ck
    CHECK (min_length >= 0 AND max_length >= min_length AND max_length <= 5000),
  CONSTRAINT crm_review_reply_policy_punctuation_ck
    CHECK (max_exclamation >= 0 AND max_exclamation <= 10
       AND max_question_marks >= 0 AND max_question_marks <= 10),
  CONSTRAINT crm_review_reply_policy_window_ck
    CHECK (effective_to IS NULL OR effective_to > effective_from)
);

-- 同一（平台 × 形态）的版本号唯一。
CREATE UNIQUE INDEX crm_review_reply_policy_version_uniq
  ON crm_review_reply_policy(tenant_id, platform_code, reply_type, version);
CREATE UNIQUE INDEX crm_review_reply_policy_no_uniq
  ON crm_review_reply_policy(tenant_id, policy_no);
-- 「取当时生效的那一版」用。
CREATE INDEX crm_review_reply_policy_effective_idx
  ON crm_review_reply_policy(tenant_id, platform_code, reply_type, effective_from DESC);

-- ---------------------------------------------------------------------------
-- 1.2 crm_review_reply_template · 回复模板（一行 = 一个版本）
-- ---------------------------------------------------------------------------
CREATE TABLE crm_review_reply_template (
  id              uuid PRIMARY KEY,
  tenant_id       uuid NOT NULL,
  template_no     varchar(32)  NOT NULL,
  -- 业务键：同一模板的各版本共用（改内容 = 同 key 的新版本）。
  template_key    varchar(64)  NOT NULL,
  version         integer      NOT NULL,
  name            varchar(200) NOT NULL,
  platform_code   varchar(32)  NOT NULL,
  reply_type      varchar(32)  NOT NULL,
  -- 适用档位：ALL（不限）/ POSITIVE（4-5 星）/ NEUTRAL（3 星）/ NEGATIVE（1-2 星）。
  rating_scope    varchar(16)  NOT NULL DEFAULT 'ALL',
  -- 生效时间（加粗句「平台政策会变」的落点之一）。
  effective_from  timestamptz  NOT NULL,
  effective_to    timestamptz,
  -- DRAFT（可编辑）→ ACTIVE（已生效，内容冻结）→ ARCHIVED（停用，冻结）。
  status          varchar(16)  NOT NULL DEFAULT 'DRAFT',
  note            varchar(500),
  created_by      uuid,
  created_at      timestamptz  NOT NULL DEFAULT now(),
  updated_at      timestamptz  NOT NULL DEFAULT now(),

  CONSTRAINT crm_review_reply_template_reply_type_ck
    CHECK (reply_type IN ('PUBLIC_REPLY','SELLER_COMMENT_APPEND')),
  CONSTRAINT crm_review_reply_template_rating_scope_ck
    CHECK (rating_scope IN ('ALL','POSITIVE','NEUTRAL','NEGATIVE')),
  CONSTRAINT crm_review_reply_template_status_ck
    CHECK (status IN ('DRAFT','ACTIVE','ARCHIVED')),
  CONSTRAINT crm_review_reply_template_version_ck
    CHECK (version >= 1),
  CONSTRAINT crm_review_reply_template_window_ck
    CHECK (effective_to IS NULL OR effective_to > effective_from)
);

CREATE UNIQUE INDEX crm_review_reply_template_key_version_uniq
  ON crm_review_reply_template(tenant_id, template_key, platform_code, reply_type, version);
CREATE UNIQUE INDEX crm_review_reply_template_no_uniq
  ON crm_review_reply_template(tenant_id, template_no);
-- 「挑一个当前生效的模板」用。
CREATE INDEX crm_review_reply_template_effective_idx
  ON crm_review_reply_template(tenant_id, platform_code, reply_type, rating_scope, effective_from DESC);

-- ---------------------------------------------------------------------------
-- 1.3 crm_review_reply_template_variant · 模板的多语言条目
-- ---------------------------------------------------------------------------
CREATE TABLE crm_review_reply_template_variant (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL,
  template_id uuid        NOT NULL,
  -- BCP-47 语言标签（en-US / zh-CN / de-DE …）。
  locale      varchar(16) NOT NULL,
  body        text        NOT NULL,
  -- 该语言是否是本模板的兜底语言（找不到请求语言时用它）。
  is_default  boolean     NOT NULL DEFAULT false,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),

  CONSTRAINT crm_review_reply_template_variant_locale_ck
    CHECK (locale ~ '^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$')
);

CREATE UNIQUE INDEX crm_review_reply_template_variant_uniq
  ON crm_review_reply_template_variant(tenant_id, template_id, locale);
CREATE INDEX crm_review_reply_template_variant_by_template
  ON crm_review_reply_template_variant(tenant_id, template_id);

-- ---------------------------------------------------------------------------
-- 1.4 crm_review_reply_batch · 批量回复批次
-- ---------------------------------------------------------------------------
CREATE TABLE crm_review_reply_batch (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  batch_no          varchar(32) NOT NULL,
  platform_code     varchar(32) NOT NULL,
  shop_id           uuid,
  reply_type        varchar(32) NOT NULL,
  locale            varchar(16) NOT NULL,
  -- 批次用的模板版本（快照）。
  template_id       uuid,
  template_version  integer,
  template_key      varchar(64),
  rating_scope      varchar(16) NOT NULL,
  -- 目标评价的筛选条件快照（口径要能事后复现「这批是按什么挑出来的」）。
  selection         jsonb NOT NULL DEFAULT '{}'::jsonb,
  target_count      integer NOT NULL DEFAULT 0,
  created_count     integer NOT NULL DEFAULT 0,
  skipped_count     integer NOT NULL DEFAULT 0,
  -- 跳过的评价与原因（jsonb 数组：[{reviewId, reviewNo, reason}]）。
  skipped           jsonb NOT NULL DEFAULT '[]'::jsonb,
  status            varchar(16) NOT NULL DEFAULT 'COMPOSED',
  note              varchar(500),
  created_by        uuid,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),

  CONSTRAINT crm_review_reply_batch_reply_type_ck
    CHECK (reply_type IN ('PUBLIC_REPLY','SELLER_COMMENT_APPEND')),
  CONSTRAINT crm_review_reply_batch_rating_scope_ck
    CHECK (rating_scope IN ('ALL','POSITIVE','NEUTRAL','NEGATIVE')),
  CONSTRAINT crm_review_reply_batch_status_ck
    CHECK (status IN ('COMPOSED','PARTIAL','EMPTY'))
);

CREATE UNIQUE INDEX crm_review_reply_batch_no_uniq
  ON crm_review_reply_batch(tenant_id, batch_no);
CREATE INDEX crm_review_reply_batch_by_platform
  ON crm_review_reply_batch(tenant_id, platform_code, reply_type, created_at DESC);

-- ---------------------------------------------------------------------------
-- 1.5 crm_review_reply · 回复记录
-- ---------------------------------------------------------------------------
CREATE TABLE crm_review_reply (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  reply_no          varchar(32) NOT NULL,
  -- 目标评价（逻辑引用 15.2.1.1 的 crm_product_review，**零外键**，触发器校验）。
  review_id         uuid        NOT NULL,
  review_no         varchar(32) NOT NULL,
  shop_id           uuid        NOT NULL,
  shop_name         varchar(200) NOT NULL,
  platform_code     varchar(32) NOT NULL,
  -- 评价快照：星级与档位。评价本身可以被纠正，但回复要留住「当时是对着哪条评价写的」。
  review_rating     smallint    NOT NULL,
  review_scope      varchar(16) NOT NULL,
  reply_type        varchar(32) NOT NULL,
  locale            varchar(16) NOT NULL,
  body              text        NOT NULL,
  -- 模板版本追溯（加粗句的落点）。
  template_id       uuid,
  template_version  integer,
  template_key      varchar(64),
  template_locale   varchar(16),
  -- 当时生效的措辞规范版本（平台政策会变 ⇒ 规范也版本化）。
  policy_version    integer,
  -- 批次（批量回复）。
  batch_id          uuid,
  batch_no          varchar(32),
  -- PENDING（已提交本系统，待平台投递）/ DELIVERED（平台已确认）/ REJECTED（平台拒绝）/
  -- CANCELLED（运营撤回）。
  status            varchar(16) NOT NULL DEFAULT 'PENDING',
  platform_reply_id varchar(128),
  platform_receipt  text,
  delivered_at      timestamptz,
  last_delivery_note varchar(500),
  note              varchar(500),
  created_by        uuid,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),

  CONSTRAINT crm_review_reply_review_rating_ck CHECK (review_rating BETWEEN 1 AND 5),
  CONSTRAINT crm_review_reply_review_scope_ck
    CHECK (review_scope IN ('POSITIVE','NEUTRAL','NEGATIVE')),
  CONSTRAINT crm_review_reply_reply_type_ck
    CHECK (reply_type IN ('PUBLIC_REPLY','SELLER_COMMENT_APPEND')),
  CONSTRAINT crm_review_reply_status_ck
    CHECK (status IN ('PENDING','DELIVERED','REJECTED','CANCELLED')),
  CONSTRAINT crm_review_reply_locale_ck
    CHECK (locale ~ '^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$'),
  CONSTRAINT crm_review_reply_body_ck CHECK (length(btrim(body)) > 0),
  -- 🔴 「已送达」必须真有回执与送达时刻：否则「已回复」会是个查无实据的说法。
  CONSTRAINT crm_review_reply_delivered_ck
    CHECK (status <> 'DELIVERED' OR (delivered_at IS NOT NULL AND platform_receipt IS NOT NULL))
);

CREATE UNIQUE INDEX crm_review_reply_no_uniq
  ON crm_review_reply(tenant_id, reply_no);
-- 🔴 同一条评价 + 同一种回复形态，只允许存在**一条在途或已送达**的回复 ——
--   重复回复买家正是平台违规与买家投诉的常见来源（重复回复的代价见 15.1.2.1 邮件模块）。
--   DRAFT / CANCELLED / REJECTED 不受此限：撤回了可以再回一次。
CREATE UNIQUE INDEX crm_review_reply_active_uniq
  ON crm_review_reply(tenant_id, review_id, reply_type)
  WHERE status IN ('PENDING','DELIVERED');
CREATE INDEX crm_review_reply_by_review
  ON crm_review_reply(tenant_id, review_id);
CREATE INDEX crm_review_reply_by_batch
  ON crm_review_reply(tenant_id, batch_id);
CREATE INDEX crm_review_reply_list_idx
  ON crm_review_reply(tenant_id, created_at DESC, id);
CREATE INDEX crm_review_reply_pending_idx
  ON crm_review_reply(tenant_id, status, created_at DESC);

-- ---------------------------------------------------------------------------
-- 1.6 crm_review_reply_delivery · 投递尝试与平台回执
-- ---------------------------------------------------------------------------
CREATE TABLE crm_review_reply_delivery (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  delivery_no       varchar(32) NOT NULL,
  reply_id          uuid        NOT NULL,
  reply_no          varchar(32) NOT NULL,
  attempt_no        integer     NOT NULL,
  platform_code     varchar(32) NOT NULL,
  -- NOT_INTEGRATED（平台适配器未接入）/ DELIVERED（平台已确认）/ REJECTED（平台拒绝）/
  -- FAILED（投递失败）。
  outcome           varchar(24) NOT NULL,
  platform_reply_id varchar(128),
  platform_receipt  text,
  attempted_at      timestamptz NOT NULL,
  note              varchar(500),

  CONSTRAINT crm_review_reply_delivery_outcome_ck
    CHECK (outcome IN ('NOT_INTEGRATED','DELIVERED','REJECTED','FAILED')),
  CONSTRAINT crm_review_reply_delivery_attempt_ck CHECK (attempt_no >= 1)
);

CREATE UNIQUE INDEX crm_review_reply_delivery_no_uniq
  ON crm_review_reply_delivery(tenant_id, delivery_no);
CREATE UNIQUE INDEX crm_review_reply_delivery_attempt_uniq
  ON crm_review_reply_delivery(tenant_id, reply_id, attempt_no);
CREATE INDEX crm_review_reply_delivery_by_reply
  ON crm_review_reply_delivery(tenant_id, reply_id, attempt_no DESC);

-- ============================================================================
-- 2. 序列（单独授权，见第五节）
-- ============================================================================
CREATE SEQUENCE crm_review_reply_policy_no_seq   START 1;
CREATE SEQUENCE crm_review_reply_template_no_seq START 1;
CREATE SEQUENCE crm_review_reply_batch_no_seq    START 1;
CREATE SEQUENCE crm_review_reply_no_seq          START 1;
CREATE SEQUENCE crm_review_reply_delivery_no_seq START 1;

-- ============================================================================
-- 3. 触发器
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 3.1 guard_crm_review_reply_template_identity · 已生效的模板内容冻结
-- ---------------------------------------------------------------------------
-- 为什么：模板一旦 ACTIVE，回复记录就会引用它的**版本**。
--   原地改正文会让「当时用的哪一版」当场失效 —— 加粗句要的就是可追溯。
--   ⇒ DRAFT 可编辑；ACTIVE / ARCHIVED 冻结（改内容 = 发新版本）。
-- ⚠️ 冻结的是**内容与身份**（键 / 版本 / 平台 / 形态 / 档位 / 生效起点），
--   放行的是**状态流转与收尾**（publish / archive / 失效时间 / 备注）。
CREATE FUNCTION guard_crm_review_reply_template_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id THEN
    RAISE EXCEPTION '模板的租户归属建后不可改' USING ERRCODE='23514';
  END IF;
  IF NEW.template_key IS DISTINCT FROM OLD.template_key THEN
    RAISE EXCEPTION '模板的业务键建后不可改：改键会让「同一模板的各版本」在历史里散架' USING ERRCODE='23514';
  END IF;
  IF NEW.version IS DISTINCT FROM OLD.version THEN
    RAISE EXCEPTION '模板的版本号建后不可改：版本号是「当时用的哪一版」的唯一定位' USING ERRCODE='23514';
  END IF;
  IF NEW.platform_code IS DISTINCT FROM OLD.platform_code THEN
    RAISE EXCEPTION '模板的平台建后不可改' USING ERRCODE='23514';
  END IF;
  IF NEW.reply_type IS DISTINCT FROM OLD.reply_type THEN
    RAISE EXCEPTION '模板的回复形态建后不可改' USING ERRCODE='23514';
  END IF;
  IF NEW.rating_scope IS DISTINCT FROM OLD.rating_scope THEN
    RAISE EXCEPTION '模板的适用档位建后不可改：改档位会让「这条模板本来是回差评的」在历史里说不清' USING ERRCODE='23514';
  END IF;
  IF NEW.effective_from IS DISTINCT FROM OLD.effective_from THEN
    RAISE EXCEPTION '模板的生效起点建后不可改：改它会让「当时生效的是哪一版」无从查证' USING ERRCODE='23514';
  END IF;
  -- 状态流转只允许 DRAFT → ACTIVE → ARCHIVED（DRAFT → ARCHIVED = 废弃草稿）。
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    IF NOT ( (OLD.status = 'DRAFT'   AND NEW.status IN ('ACTIVE','ARCHIVED'))
           OR (OLD.status = 'ACTIVE' AND NEW.status = 'ARCHIVED') ) THEN
      RAISE EXCEPTION '模板状态只能 DRAFT → ACTIVE → ARCHIVED 单向流转，不能从 % 回到 %',
        OLD.status, NEW.status USING ERRCODE='23514';
    END IF;
  END IF;
  -- 已生效 / 已停用的模板：**内容**冻结（状态、失效时间、备注仍然可动）。
  --   放行 effective_to 是因为「停用」本来就要给一个失效时间；
  --   但只允许在**归档**时一次性设定 —— 否则「当时生效到什么时候」会在事后被改写。
  IF OLD.status IN ('ACTIVE','ARCHIVED') THEN
    IF NEW.name IS DISTINCT FROM OLD.name THEN
      RAISE EXCEPTION '已生效的模板不可改名：要改内容请发新版本（平台政策会变，旧版本要留在历史里）' USING ERRCODE='23514';
    END IF;
    IF NEW.effective_to IS DISTINCT FROM OLD.effective_to
       AND NOT (OLD.status = 'ACTIVE' AND NEW.status = 'ARCHIVED' AND OLD.effective_to IS NULL) THEN
      RAISE EXCEPTION '已生效的模板的失效时间只能在下架（ACTIVE → ARCHIVED）时一次性设定：事后改它会让「当时生效到哪一天」无从查证' USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_review_reply_template_identity_trg
  BEFORE UPDATE ON crm_review_reply_template
  FOR EACH ROW EXECUTE FUNCTION guard_crm_review_reply_template_identity();

-- ---------------------------------------------------------------------------
-- 3.2 guard_crm_review_reply_template_variant_target · 多语言条目必须挂在存在的模板上
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_crm_review_reply_template_variant_target() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_no varchar(32);
BEGIN
  SELECT template_no INTO v_no FROM crm_review_reply_template
   WHERE tenant_id = NEW.tenant_id AND id = NEW.template_id;
  IF v_no IS NULL THEN
    RAISE EXCEPTION '引用的回复模板 % 不存在（tenant=%），不能挂这条多语言条目',
      NEW.template_id, NEW.tenant_id USING ERRCODE='foreign_key_violation';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_review_reply_template_variant_target_trg
  BEFORE INSERT ON crm_review_reply_template_variant
  FOR EACH ROW EXECUTE FUNCTION guard_crm_review_reply_template_variant_target();

-- ---------------------------------------------------------------------------
-- 3.3 guard_crm_review_reply_template_variant_content · 已生效模板的正文冻结
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_crm_review_reply_template_variant_content() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_old_status varchar(16);
  v_new_status varchar(16);
BEGIN
  SELECT status INTO v_old_status FROM crm_review_reply_template
   WHERE tenant_id = OLD.tenant_id AND id = OLD.template_id;
  SELECT status INTO v_new_status FROM crm_review_reply_template
   WHERE tenant_id = NEW.tenant_id AND id = NEW.template_id;
  IF v_old_status IS NULL THEN
    RETURN NEW;
  END IF;
  IF v_old_status <> 'DRAFT' OR (v_new_status IS NOT NULL AND v_new_status <> 'DRAFT') THEN
    RAISE EXCEPTION '已生效的模板的多语言正文不可改：改它会让「当时用的哪一版」当场失效。要改内容请发新版本' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_review_reply_template_variant_content_trg
  BEFORE UPDATE ON crm_review_reply_template_variant
  FOR EACH ROW EXECUTE FUNCTION guard_crm_review_reply_template_variant_content();

-- ---------------------------------------------------------------------------
-- 3.4 guard_crm_review_reply_target · 回复必须挂在存在的评价上，且店铺/平台一致
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_crm_review_reply_target() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_no varchar(32);
  v_shop uuid;
  v_platform varchar(32);
BEGIN
  SELECT review_no, shop_id, platform_code INTO v_no, v_shop, v_platform
    FROM crm_product_review
   WHERE tenant_id = NEW.tenant_id AND id = NEW.review_id;
  IF v_no IS NULL THEN
    RAISE EXCEPTION '被回复的评价 % 不存在（tenant=%），不能登记这条回复',
      NEW.review_id, NEW.tenant_id USING ERRCODE='foreign_key_violation';
  END IF;
  -- 🔴 回复必须挂在**同一家店、同一个平台**的评价上：
  --   否则「给 A 店的差评回了一句给 B 店的话」会被记成一条已回复，而买家根本没收到。
  IF v_shop IS DISTINCT FROM NEW.shop_id OR v_platform IS DISTINCT FROM NEW.platform_code THEN
    RAISE EXCEPTION '回复的店铺/平台与评价不一致（评价属 % / %），不能登记这条回复',
      v_platform, v_shop USING ERRCODE='foreign_key_violation';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_review_reply_target_trg
  BEFORE INSERT ON crm_review_reply
  FOR EACH ROW EXECUTE FUNCTION guard_crm_review_reply_target();

-- ---------------------------------------------------------------------------
-- 3.5 guard_crm_review_reply_template_ref · 引用的模板版本必须与模板本身一致
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_crm_review_reply_template_ref() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_version integer;
  v_status varchar(16);
BEGIN
  IF NEW.template_id IS NULL THEN
    RETURN NEW;
  END IF;
  SELECT version, status INTO v_version, v_status FROM crm_review_reply_template
   WHERE tenant_id = NEW.tenant_id AND id = NEW.template_id;
  IF v_version IS NULL THEN
    RAISE EXCEPTION '引用的回复模板 % 不存在（tenant=%），不能登记这条回复',
      NEW.template_id, NEW.tenant_id USING ERRCODE='foreign_key_violation';
  END IF;
  IF NEW.template_version IS DISTINCT FROM v_version THEN
    RAISE EXCEPTION '回复记录的模板版本（%）与模板实际版本（%）不一致：版本对不上，「当时用的哪一版」就答不了',
      NEW.template_version, v_version USING ERRCODE='foreign_key_violation';
  END IF;
  IF v_status <> 'ACTIVE' THEN
    RAISE EXCEPTION '只能引用已生效（ACTIVE）的模板，当前模板状态是 %', v_status USING ERRCODE='foreign_key_violation';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_review_reply_template_ref_trg
  BEFORE INSERT ON crm_review_reply
  FOR EACH ROW EXECUTE FUNCTION guard_crm_review_reply_template_ref();

-- ---------------------------------------------------------------------------
-- 3.6 guard_crm_review_reply_batch_target · 批次引用必须存在
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_crm_review_reply_batch_target() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_no varchar(32);
BEGIN
  IF NEW.batch_id IS NULL THEN
    RETURN NEW;
  END IF;
  SELECT batch_no INTO v_no FROM crm_review_reply_batch
   WHERE tenant_id = NEW.tenant_id AND id = NEW.batch_id;
  IF v_no IS NULL THEN
    RAISE EXCEPTION '引用的批量回复批次 % 不存在（tenant=%），不能登记这条回复',
      NEW.batch_id, NEW.tenant_id USING ERRCODE='foreign_key_violation';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_review_reply_batch_target_trg
  BEFORE INSERT ON crm_review_reply
  FOR EACH ROW EXECUTE FUNCTION guard_crm_review_reply_batch_target();

-- ---------------------------------------------------------------------------
-- 3.7 guard_crm_review_reply_identity · 回复正文与目标建后不可改
-- ---------------------------------------------------------------------------
-- 为什么：回复记录是**事实**。正文 / 目标 / 模板版本一旦落库就不该改 ——
--   改了它，「我们当时到底写了什么、用的哪一版模板」永远答不了。
--   要改内容 ⇒ 取消这条并新建一条。
--   放行的是**投递结果**（status / platform_reply_id / platform_receipt / delivered_at）
--   与备注 —— 那些是投递过程产生的事实。
CREATE FUNCTION guard_crm_review_reply_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id THEN
    RAISE EXCEPTION '回复的租户归属建后不可改' USING ERRCODE='23514';
  END IF;
  IF NEW.review_id IS DISTINCT FROM OLD.review_id THEN
    RAISE EXCEPTION '回复的目标评价建后不可改：改它会让「这条回复是回给谁的」在历史里出现两个答案' USING ERRCODE='23514';
  END IF;
  IF NEW.reply_type IS DISTINCT FROM OLD.reply_type THEN
    RAISE EXCEPTION '回复的形态建后不可改' USING ERRCODE='23514';
  END IF;
  IF NEW.body IS DISTINCT FROM OLD.body THEN
    RAISE EXCEPTION '回复正文建后不可改：回复记录是事实，改了它就查不清当时写了什么。要改内容请取消这条并新建一条' USING ERRCODE='23514';
  END IF;
  IF NEW.locale IS DISTINCT FROM OLD.locale THEN
    RAISE EXCEPTION '回复的语言建后不可改' USING ERRCODE='23514';
  END IF;
  IF NEW.template_id IS DISTINCT FROM OLD.template_id
     OR NEW.template_version IS DISTINCT FROM OLD.template_version
     OR NEW.template_key IS DISTINCT FROM OLD.template_key
     OR NEW.template_locale IS DISTINCT FROM OLD.template_locale THEN
    RAISE EXCEPTION '回复引用的模板版本建后不可改：版本追溯一旦可改，加粗句要的「追到当时用的模板版本」就失效了' USING ERRCODE='23514';
  END IF;
  IF NEW.policy_version IS DISTINCT FROM OLD.policy_version THEN
    RAISE EXCEPTION '回复当时生效的措辞规范版本建后不可改' USING ERRCODE='23514';
  END IF;
  IF NEW.batch_id IS DISTINCT FROM OLD.batch_id THEN
    RAISE EXCEPTION '回复所属的批次建后不可改' USING ERRCODE='23514';
  END IF;
  -- 状态流转：PENDING → DELIVERED / REJECTED / CANCELLED；DELIVERED / REJECTED 是终态。
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    IF NOT ( (OLD.status = 'PENDING' AND NEW.status IN ('DELIVERED','REJECTED','CANCELLED')) ) THEN
      RAISE EXCEPTION '回复状态只能从 PENDING 流转到 DELIVERED / REJECTED / CANCELLED，不能从 % 回到 %',
        OLD.status, NEW.status USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_review_reply_identity_trg
  BEFORE UPDATE ON crm_review_reply
  FOR EACH ROW EXECUTE FUNCTION guard_crm_review_reply_identity();

-- ---------------------------------------------------------------------------
-- 3.8 guard_crm_review_reply_delivery_target · 投递记录必须挂在存在的回复上
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_crm_review_reply_delivery_target() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_no varchar(32);
BEGIN
  SELECT reply_no INTO v_no FROM crm_review_reply
   WHERE tenant_id = NEW.tenant_id AND id = NEW.reply_id;
  IF v_no IS NULL THEN
    RAISE EXCEPTION '被回复的回复记录 % 不存在（tenant=%），不能登记这次投递',
      NEW.reply_id, NEW.tenant_id USING ERRCODE='foreign_key_violation';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_review_reply_delivery_target_trg
  BEFORE INSERT ON crm_review_reply_delivery
  FOR EACH ROW EXECUTE FUNCTION guard_crm_review_reply_delivery_target();

-- ---------------------------------------------------------------------------
-- 3.9 guard_crm_review_reply_policy_window · 同一（平台 × 形态）的生效区间不重叠
-- ---------------------------------------------------------------------------
-- 为什么：「当时生效的是哪一版规范」必须**唯一**，否则同一条回复可能被两版规范同时认领。
CREATE FUNCTION guard_crm_review_reply_policy_window() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_overlap integer;
BEGIN
  SELECT count(*) INTO v_overlap FROM crm_review_reply_policy p
   WHERE p.tenant_id = NEW.tenant_id
     AND p.platform_code = NEW.platform_code
     AND p.reply_type = NEW.reply_type
     AND p.id <> NEW.id
     AND tstzrange(p.effective_from, p.effective_to, '[)')
         && tstzrange(NEW.effective_from, NEW.effective_to, '[)');
  IF v_overlap > 0 THEN
    RAISE EXCEPTION '平台 % / 形态 % 的措辞规范生效区间与已有版本重叠：「当时生效的是哪一版」必须唯一',
      NEW.platform_code, NEW.reply_type USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_review_reply_policy_window_trg
  BEFORE INSERT OR UPDATE ON crm_review_reply_policy
  FOR EACH ROW EXECUTE FUNCTION guard_crm_review_reply_policy_window();

-- ============================================================================
-- 4. RLS（ENABLE + FORCE）与授权
-- ============================================================================
-- 与 V99 / V220 / V228 / V292 / V294 同一口径：策略名固定 isolation，USING 与 WITH CHECK 两侧同表达式。
-- 表达式里的 nullif 不是洁癖：事务局部的 set_config 提交之后，PostgreSQL 会把自定义 GUC
-- **复位成空串**而不是「回到未设置」，直接 cast 会报 uuid 语法错，
-- 而 nullif 把它归一成 NULL —— 条件不成立 → 一行都看不到。
-- 也就是说「忘记绑定租户」当场失败，而不是静默返回全表。
ALTER TABLE crm_review_reply_policy           ENABLE ROW LEVEL SECURITY;
ALTER TABLE crm_review_reply_policy           FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON crm_review_reply_policy
  USING (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid)
  WITH CHECK (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid);

ALTER TABLE crm_review_reply_template         ENABLE ROW LEVEL SECURITY;
ALTER TABLE crm_review_reply_template         FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON crm_review_reply_template
  USING (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid)
  WITH CHECK (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid);

ALTER TABLE crm_review_reply_template_variant ENABLE ROW LEVEL SECURITY;
ALTER TABLE crm_review_reply_template_variant FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON crm_review_reply_template_variant
  USING (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid)
  WITH CHECK (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid);

ALTER TABLE crm_review_reply_batch            ENABLE ROW LEVEL SECURITY;
ALTER TABLE crm_review_reply_batch            FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON crm_review_reply_batch
  USING (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid)
  WITH CHECK (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid);

ALTER TABLE crm_review_reply                  ENABLE ROW LEVEL SECURITY;
ALTER TABLE crm_review_reply                  FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON crm_review_reply
  USING (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid)
  WITH CHECK (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid);

ALTER TABLE crm_review_reply_delivery         ENABLE ROW LEVEL SECURITY;
ALTER TABLE crm_review_reply_delivery         FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON crm_review_reply_delivery
  USING (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid)
  WITH CHECK (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid);

GRANT SELECT,INSERT,UPDATE,DELETE ON
  crm_review_reply_policy, crm_review_reply_template, crm_review_reply_template_variant,
  crm_review_reply_batch, crm_review_reply, crm_review_reply_delivery TO crm_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  crm_review_reply_policy, crm_review_reply_template, crm_review_reply_template_variant,
  crm_review_reply_batch, crm_review_reply, crm_review_reply_delivery TO starcon_app;

-- 🔴 序列单独授权：GRANT ... ON ALL TABLES **不覆盖序列**，漏授权会变成
-- 「第一条记录就报权限不足」（10.3.2.2 踩过这个坑，这里不重复）。
GRANT USAGE, SELECT ON SEQUENCE crm_review_reply_policy_no_seq   TO crm_app;
GRANT USAGE, SELECT ON SEQUENCE crm_review_reply_template_no_seq TO crm_app;
GRANT USAGE, SELECT ON SEQUENCE crm_review_reply_batch_no_seq    TO crm_app;
GRANT USAGE, SELECT ON SEQUENCE crm_review_reply_no_seq          TO crm_app;
GRANT USAGE, SELECT ON SEQUENCE crm_review_reply_delivery_no_seq TO crm_app;
GRANT USAGE, SELECT ON SEQUENCE crm_review_reply_policy_no_seq   TO starcon_app;
GRANT USAGE, SELECT ON SEQUENCE crm_review_reply_template_no_seq TO starcon_app;
GRANT USAGE, SELECT ON SEQUENCE crm_review_reply_batch_no_seq    TO starcon_app;
GRANT USAGE, SELECT ON SEQUENCE crm_review_reply_no_seq          TO starcon_app;
GRANT USAGE, SELECT ON SEQUENCE crm_review_reply_delivery_no_seq TO starcon_app;

REVOKE ALL ON flyway_schema_history FROM crm_app;

-- ============================================================================
-- 5. 留给下游 / 上游的口子（不是本模块的缺口）
-- ============================================================================
--   * **上游 15.2.1.1 评价列表（已交付）**：本模块**只读引用** `crm_product_review`
--     （回复的目标评价），**不改它的表结构、端点与权限**。
--     ⚠️ 本模块**不修改**它的 `replied` 列 —— 那列的语义是「**平台侧**是否已有公开回复」，
--     而本模块的回复尚未投递到平台 ⇒ 平台侧仍然没有回复。
--   * **下游 15.2.1.3 差评处理（未交付）**：本模块**不建**差评标记 / 申诉 / 解决率；
--     但**对差评也能回复**（那正是本模块的职责）。
--   * **下游 15.2.2.1 索评 / 15.5.2 买家关怀（未交付或不在本模块范围）**：
--     本模块**不发起**索评、不做买家关怀，只做「已收到的评价怎么回」。
--   * **平台投递（未接入）**：见文件头第二节。
