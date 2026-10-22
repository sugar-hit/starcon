-- 15.5.1.1 自动回复：按触发条件（收到消息的分类结果 / 关键词 / 无人工介入超时）自动发送回复，
-- 支持节假日无人值守、分级自动回复（先自动引导再转人工），并留出人工接管。
--
-- 蓝图 15 卷 15.5.1.1 的原话：
--   「职责：配置消息自动回复：按触发条件（收到消息分类、关键词、无人工介入超时）自动发送回复，
--     支持节假日无人值守、分级自动回复（先自动引导再转人工）。
--     输入：自动回复规则；模板内容；触发条件。
--     输出：自动回复记录 + 转人工标记。
--     关系：上游 = 15.1.1.3、15.4.2；下游 = 15.1.1.2 发送。
--     **自动回复必须可一键关闭并留出人工接管**，很多平台对纯自动回复有明确限制。」
--
-- ============================================================ 零、与既有模块的边界
--
-- 本文件是 **crm 数据库的第十四个迁移**（前十三个：V99 知识库 / V220 索评 / V228 索评规则 /
-- V292 邮件收发 / V293 消息收件箱 / V294 评价 / V295 客户标签 / V296 弃单挽回 /
-- V297 消息发送 / V298 评价回复 / V299 接触记录 / V301 消息分类 / V302 差评 / V303 关怀催办）。
--
-- ⚠️ 本模块**不 ALTER 任何既有表**（零 ALTER / 零 DROP / 零改列）：
--   * `crm_message`（15.1.1.1 收件箱）是**上游**，同库同服务，直接 jOOQ 查，**只读**；
--   * `crm_message_classification`（15.1.1.3 分类）是**上游**，同库同服务，直接 jOOQ 查，**只读** ——
--     **绝不另造一张平行分类表**：两套分类结论必然打架，而打架的方向往往是
--     「自动回复按自己的分类发，而分类页显示的是另一套」；
--   * `crm_message_send` / `crm_message_send_template`（15.1.1.2 发送）是**下游**，同库同服务：
--     本模块**只调它的服务层**登记发送，**一个字都不写它的表**
--     （那张表的写入所有者是 15.1.1.2）；
--   * `crm_knowledge_entry` / `crm_knowledge_version`（15.4.2.1 知识库）是**上游**，同库同服务，**只读**。
--
-- 本模块新建**六张表**（各一个写入所有者）：
--   crm_auto_reply_setting        · **全局开关**（每租户一行）：一键关闭自动回复 + 关闭原因与操作人
--   crm_auto_reply_rule           · 自动回复规则（= 阶梯的一级）：触发条件 + 话术 + 阶梯位置
--   crm_auto_reply_duty_calendar  · **值班日历**（租户可配，不是写死常量）：节假日 / 非营业时段是否自动回复
--   crm_auto_reply_record         · **自动回复记录**（**只追加**）：触发时间 / 命中规则 / 模板版本快照 / 发送结果 / 未发送原因
--   crm_auto_reply_escalation     · **转人工标记**（**只追加**）
--   crm_auto_reply_takeover       · **人工接管标记**（**只追加**，按会话）
--
-- ============================================================ 一、🔴 加粗句的落地：
-- 「自动回复必须可一键关闭并留出人工接管，很多平台对纯自动回复有明确限制」
--
-- 这句话在本文件里落成**四件事**，而不是一句注释：
--
--   1. **全局开关是表，不是常量**（`crm_auto_reply_setting`）：
--      一行一个租户，`auto_reply_enabled=false` 即「本租户现在一条自动回复都不发」。
--      关闭必须带**原因**与**操作人**（`closed_reason` NOT NULL CHECK + `closed_at` + `closed_by`）——
--      没有原因的一键关闭，事后没人知道当时为什么关的、该什么时候开回来。
--   2. **规则级开关**（`crm_auto_reply_rule.enabled`）：全局开着也不代表每条规则都该发。
--   3. **关闭是即时生效**（服务层 + DB 层）：
--      ① 触发时在**同一个事务里**读开关 —— 改开关之后的新触发不再自动发；
--      ② 关闭时把**已经排队待发**的记录逐条追加一条 `outcome='CANCELLED'` 的撤销行
--         （「已撤销，不得再发」），**不得**在关闭后继续发；
--      ③ 撤销信号落在**本模块自己的表**上，**不 UPDATE `crm_message_send`**
--         （那张表的写入所有者是 15.1.1.2，跨模块改它的状态会变成两处事实源）。
--   4. **人工接管是留痕的事实**（`crm_auto_reply_takeover`）：
--      接管以**会话**（店铺 + 买家标识）为粒度 —— 上游**没有会话实体**
--      （15.1.1.1 只到「消息」这一层），所以本模块把会话定义为
--      (shop_id, buyer_reference)，并在文件头第五节写明这是本模块自己的定义。
--      接管后**同一会话不再自动回复**：这是硬闸，服务层每次触发都回查。
--
-- ⚠️ 平台限制（"很多平台对纯自动回复有明确限制"）在本仓**无法由本模块判定**：
--   channel/vendor 下只有 OAuth 适配器，**没有买家站内信适配器**，也没有任何平台的
--   「自动回复准入」清单 ⇒ 本模块**不编**一份平台白名单，
--   而是在**能力声明**里如实写 `available=false` + `ownerModule=2`（渠道与店铺）。
--   编一份假名单 = 让人以为「这个平台可以放心全自动」。
--
-- ============================================================ 二、触发条件三选一
--
-- 蓝图输入句：「自动回复规则；模板内容；触发条件」，职责句点名三种触发条件：
--
--   (a) **收到消息分类命中** —— 复用 15.1.1.3 的 `crm_message_classification.label`；
--       规则上的 `classification_labels`（逗号分隔）与结论的 label 比对。
--       🔴 上游的 `auto_reply_eligible=false` 是**安全闸**：那条消息**不许**自动回
--       （说不清是什么的消息就自动回，风险由买家承担）。
--   (b) **关键词命中** —— 规则上的 `keywords`（换行分隔），命中方式 ANY / ALL，
--       与 15.1.1.3 的关键词口径**逐字一致**（subject / body 的大小写不敏感 contains）。
--   (c) **无人工介入超时** —— 规则上的 `human_timeout_minutes`；
--       「人工介入」= 15.1.1.2 里一条 **不是本模块产生**的 SENT 发送记录
--       （15.4.1.2 工单处理动作**未交付** ⇒ 人工介入的**唯一**可判定信号是发送记录，
--       这一点在能力声明里如实写明）。
--
-- ⚠️ 三种触发条件**互斥**（`trigger_type` 单值 + 表级 CHECK）：
--   一条规则同时写「分类命中」和「关键词命中」，命中口径就没法复现了。
--
-- ============================================================ 三、分级自动回复（先自动引导再转人工）
--
-- 阶梯语义落成**规则上的三个列**，而不是藏在代码里的隐式约定：
--   `ladder_code`  —— 阶梯标识（同租户内 (ladder_code, level) 唯一）
--   `level`        —— 第几级（1 起）
--   `escalate_to_human` —— 本级处理完**是否转人工**（阶梯的终点由它标记）
--
-- 推进口径（服务端唯一判定）：
--   * 第 1 级：触发条件命中即发（分类 / 关键词）；
--     `HUMAN_TIMEOUT` 触发时还要满足「距消息平台时间已过 human_timeout_minutes
--     且期间无人工介入」。
--   * 第 N 级（N>1）：触发条件命中 **且** 距**上一级自动回复**至少过 `level_wait_minutes`
--     **且** 期间无人工介入 ⇒ 「先自动引导 → 仍无人工介入 → 再发一级 → 转人工」。
--   * 走完一级且该级 `escalate_to_human=true` ⇒ 追加一条**转人工标记**。
--
-- ⚠️ `level > 1 ⇒ level_wait_minutes > 0`（表级 CHECK）：
--   间隔为 0 会让一次触发把整条阶梯一次走完 —— 那不是「分级自动回复」，
--   那是「一次把三句话全甩给买家」。
--
-- ============================================================ 四、节假日无人值守：值班日历（可配置，不是常量）
--
-- 蓝图职责句点名「支持节假日无人值守」。**节假日不是写死的常量** ——
-- 每个租户的节假日、值班安排都不一样，写死一份「1 月 1 日春节」在运营第一天就错了。
-- 所以：
--   `crm_auto_reply_duty_calendar` —— 租户可配的**值班日历**：
--     scope        GLOBAL（整租户）/ PLATFORM（按平台）/ RULE（按规则）
--     duty_mode    UNATTENDED（无人值守）/ ATTENDED（有人值班）/ CLOSED（休息）
--     duty_start / duty_end  该条安排的时段（都为空 = 全天）
--     auto_reply_allowed  🔴 **此时段是否允许自动回复**（这就是「无人值守要不要自动回复」
--                    的可配置点；默认 true = 无人值守时自动引导）
--     timezone     用来算「当地日期与当地时刻」（默认 UTC）
--   生效口径：同一天里命中的条目按**范围从窄到宽**取一条
--   （RULE > PLATFORM > GLOBAL）；时段没覆盖当前时刻 ⇒ 该条不适用。
--   一条都命中不到 ⇒ 回落规则的默认口径（规则上的 `unattended_auto_reply_allowed`）。
--
-- 🔴 值班闸挡下自动回复时**必须留出口**：落一条 `outcome='SUPPRESSED'` 的记录
--   **并追加一条转人工标记**（reason_code='DUTY_GATE'）——
--   无人值守时段仍要保留「转人工」出口，否则买家等的是空气。
--
-- ============================================================ 五、跨服务纪律
--
-- 上游 `crm_message` / `crm_message_classification` / `crm_knowledge_*` 都在**本服务本库**
-- （不是跨服务），所以直接 jOOQ 查，**只读**；
-- 下游 15.1.1.2 消息发送在**本服务本库**，本模块**调它的服务层**登记发送，
-- **不自己再造一条发送通道**，也**不写**它的表。
--
-- 跨服务（trade 订单上下文等）由 15.1.1.2 自己的端口负责，本模块不碰。
--
-- ============================================================ 六、时点、数量与金额
--
--   * 时刻一律 **timestamptz** + OffsetDateTime，绑定写 `?::timestamptz`。
--     （用 Timestamp.valueOf(LocalDateTime) 会在东八区静默偏 −28800 秒。）
--   * 日期用 **date** + LocalDate（绑定 `?::date`），时刻用 **time** + LocalTime（绑定 `?::time`）。
--   * 本表**没有金额**；数量用 integer / bigint。
--
-- ============================================================================
-- 零之一、角色
-- ============================================================================
-- 与 V99 / V220 / V293 / V303 同一口径：角色可能已由运维预置，因此只在缺失时创建。
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='crm_app') THEN CREATE ROLE crm_app; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN CREATE ROLE starcon_app; END IF;
END $$;
GRANT USAGE ON SCHEMA public TO crm_app;

-- ============================================================================
-- 1. crm_auto_reply_setting · 全局开关（每租户一行）
-- ============================================================================
CREATE TABLE crm_auto_reply_setting (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 🔴 一键关闭：false = 本租户现在一条自动回复都不发。
  auto_reply_enabled boolean NOT NULL DEFAULT true,

  -- 关闭原因：关闭时**必填**（服务层 + 触发器各拦一次）——
  -- 没有原因的一键关闭，事后没人知道当时为什么关、该什么时候开回来。
  closed_reason varchar(500) NOT NULL DEFAULT '',
  closed_at timestamptz,
  closed_by uuid,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id)
);

-- ============================================================================
-- 2. crm_auto_reply_rule · 自动回复规则（= 阶梯的一级）
-- ============================================================================
CREATE TABLE crm_auto_reply_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  rule_code varchar(64) NOT NULL CHECK (btrim(rule_code) <> ''),
  rule_name varchar(200) NOT NULL CHECK (btrim(rule_name) <> ''),

  -- ===== 阶梯位置 =====
  -- 阶梯标识：同一阶梯的多级规则共用它。
  ladder_code varchar(64) NOT NULL CHECK (btrim(ladder_code) <> ''),
  -- 第几级（1 起）。
  level integer NOT NULL CHECK (level >= 1),
  -- 本级处理完是否转人工（阶梯终点由它标记）。
  escalate_to_human boolean NOT NULL DEFAULT false,

  -- 规则级开关：全局开着也不代表每条规则都该发。
  enabled boolean NOT NULL DEFAULT true,

  -- ===== 触发条件（三选一，互斥）=====
  trigger_type varchar(24) NOT NULL
    CHECK (trigger_type IN ('CLASSIFICATION','KEYWORD','HUMAN_TIMEOUT')),

  -- (a) 分类命中：逗号分隔的 15.1.1.3 标签（PRE_SALE / LOGISTICS / RETURN_REFUND /
  --     COMPLAINT / PLATFORM_COMPLIANCE / SPAM）。
  classification_labels text NOT NULL DEFAULT '',
  -- (b) 关键词命中：换行分隔。
  keywords text NOT NULL DEFAULT '',
  match_mode varchar(16) NOT NULL DEFAULT 'ANY' CHECK (match_mode IN ('ANY','ALL')),

  -- 适用平台：逗号分隔；空 = 全部平台（与 15.1.1.3 的 platform_codes 同一形状）。
  platform_codes text NOT NULL DEFAULT '',

  -- ===== 超时口径（可配置、可审阅）=====
  -- 「无人工介入超时」口径（分钟）：trigger_type='HUMAN_TIMEOUT' 时必填 > 0。
  human_timeout_minutes integer NOT NULL DEFAULT 0 CHECK (human_timeout_minutes >= 0),
  -- 本级最短间隔（分钟）：level>1 时必填 > 0（距上一级自动回复）。
  level_wait_minutes integer NOT NULL DEFAULT 0 CHECK (level_wait_minutes >= 0),

  -- 🔴 没有任何排班记录时的默认口径：允许自动回复（true）/ 不允许（false）。
  --    「节假日无人值守要不要自动回复」是**可配置**的，不是写死的常量。
  unattended_auto_reply_allowed boolean NOT NULL DEFAULT true,

  -- ===== 话术（模板内容）=====
  -- 15.1.1.2 的回复模板（下游服务，本模块只引用 id，不复制它的表）。
  template_id uuid,
  template_code varchar(64) NOT NULL DEFAULT '',
  -- 15.4.2.1 的知识版本（上游）。
  knowledge_version_id uuid,
  subject varchar(500) NOT NULL DEFAULT '',
  body text NOT NULL DEFAULT '',

  note text NOT NULL DEFAULT '',
  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, rule_code),
  -- 一个阶梯里同一级只能有一条规则。
  UNIQUE (tenant_id, ladder_code, level),

  -- 三种触发条件互斥，且各自的必填项不能为空。
  CHECK (trigger_type <> 'CLASSIFICATION' OR btrim(classification_labels) <> ''),
  CHECK (trigger_type <> 'KEYWORD' OR btrim(keywords) <> ''),
  CHECK (trigger_type <> 'HUMAN_TIMEOUT' OR human_timeout_minutes > 0),
  -- 间隔为 0 会让一次触发把整条阶梯走完 —— 那不是分级自动回复。
  CHECK (level = 1 OR level_wait_minutes > 0)
);

-- 命中时的候选集：按 (enabled, trigger_type) 取规则。
CREATE INDEX crm_auto_reply_rule_by_enabled
  ON crm_auto_reply_rule(tenant_id, enabled, trigger_type);
-- 阶梯推进时读「某个阶梯已发出的最高一级」。
CREATE INDEX crm_auto_reply_rule_by_ladder
  ON crm_auto_reply_rule(tenant_id, ladder_code, level);

-- ============================================================================
-- 3. crm_auto_reply_duty_calendar · 值班日历（租户可配，不是常量）
-- ============================================================================
CREATE TABLE crm_auto_reply_duty_calendar (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 生效范围：整租户 / 按平台 / 按规则。
  scope varchar(16) NOT NULL CHECK (scope IN ('GLOBAL','PLATFORM','RULE')),
  platform_code varchar(32) CHECK (platform_code IS NULL OR platform_code ~ '^[A-Z][A-Z0-9_]*$'),
  rule_id uuid,

  -- 生效日期（当地日期）。
  calendar_date date NOT NULL,

  -- 值班安排：无人值守 / 有人值班 / 休息。
  duty_mode varchar(16) NOT NULL CHECK (duty_mode IN ('UNATTENDED','ATTENDED','CLOSED')),
  -- 该条安排的时段；两个都为空 = 全天。
  duty_start time,
  duty_end time,

  -- 🔴 此时段是否允许自动回复（无人值守要不要自动回复 = 这一列，可配置）。
  auto_reply_allowed boolean NOT NULL DEFAULT true,

  -- 用来算「当地日期 / 当地时刻」。
  timezone varchar(64) NOT NULL DEFAULT 'UTC',
  -- 节日名（"春节" / "黑色星期五" ...），运营看得懂就行。
  holiday_name varchar(100) NOT NULL DEFAULT '',

  note text NOT NULL DEFAULT '',
  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, scope, platform_code, rule_id, calendar_date, duty_start),

  CHECK (scope <> 'PLATFORM' OR platform_code IS NOT NULL),
  CHECK (scope <> 'RULE' OR rule_id IS NOT NULL),
  CHECK ((duty_start IS NULL) = (duty_end IS NULL)),
  -- 起止相同 = 一个空的时段，写下来的人以为它生效了，实际它一次都不会命中。
  CHECK (duty_start IS NULL OR duty_start <> duty_end),
  -- 「休息」与「允许自动回复」自相矛盾：休息就是没人值班，不该自动回。
  CHECK (duty_mode <> 'CLOSED' OR auto_reply_allowed = false)
);

-- 生效判定时按日期取候选。
CREATE INDEX crm_auto_reply_duty_calendar_by_date
  ON crm_auto_reply_duty_calendar(tenant_id, calendar_date, scope);

-- ============================================================================
-- 4. crm_auto_reply_record · 自动回复记录（只追加）
-- ============================================================================
-- 记录号序列单独建、单独授权：GRANT ... ON ALL TABLES 不覆盖序列，
-- 漏授权会变成「第一条自动回复记录就报权限不足」（V293 已踩过这个坑）。
CREATE SEQUENCE crm_auto_reply_record_no_seq START 1;

CREATE TABLE crm_auto_reply_record (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- AR-yyyyMMdd-000001（人可引用的短号）。
  record_no varchar(32) NOT NULL DEFAULT '',

  -- ===== 命中依据 =====
  rule_id uuid,
  rule_code varchar(64) NOT NULL DEFAULT '',
  rule_name varchar(200) NOT NULL DEFAULT '',
  ladder_code varchar(64) NOT NULL DEFAULT '',
  level integer NOT NULL DEFAULT 0,
  trigger_type varchar(24) NOT NULL DEFAULT '',
  -- 命中依据（人话，可审阅）：命中了哪个分类 / 哪些关键词 / 超时了多久。
  trigger_reason varchar(1000) NOT NULL DEFAULT '',

  -- ===== 消息上下文（快照）=====
  message_id uuid NOT NULL,
  message_no varchar(32) NOT NULL DEFAULT '',
  shop_id uuid NOT NULL,
  shop_name varchar(200) NOT NULL DEFAULT '',
  platform_code varchar(32) NOT NULL DEFAULT '',
  buyer_reference varchar(128) NOT NULL DEFAULT '',
  order_reference varchar(64) NOT NULL DEFAULT '',

  -- 15.1.1.3 的分类结论（快照；上游只读）。
  classification_id uuid,
  classification_label varchar(32) NOT NULL DEFAULT '',

  -- ===== 模板版本快照（改规则不追溯改变历史记录）=====
  template_id uuid,
  template_code varchar(64) NOT NULL DEFAULT '',
  template_body_snapshot text NOT NULL DEFAULT '',
  knowledge_version_id uuid,
  knowledge_version_no integer NOT NULL DEFAULT 0,
  -- 实际发出去的正文快照（渲染后的）。
  body_snapshot text NOT NULL DEFAULT '',

  -- ===== 结果 =====
  -- SEND_REGISTERED 已通过 15.1.1.2 登记发送 · SUPPRESSED 被闸门挡下（未发送）
  -- CANCELLED 已撤销（全局关闭后不得再发）
  outcome varchar(24) NOT NULL
    CHECK (outcome IN ('SEND_REGISTERED','SUPPRESSED','CANCELLED')),
  -- 未发送原因 / 撤销原因（人话）。
  reason varchar(1000) NOT NULL DEFAULT '',

  -- 关联 15.1.1.2 的发送记录（本模块**不写**那张表，只是引用它的 id）。
  send_id uuid,
  send_no varchar(32) NOT NULL DEFAULT '',
  -- 撤销行指向被撤销的那一条。
  origin_record_id uuid,

  -- 本条是否同时落了转人工标记。
  escalated boolean NOT NULL DEFAULT false,

  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, record_no)
);

-- 按消息读记录（复核「这条消息被自动回过几次」）。
CREATE INDEX crm_auto_reply_record_by_message
  ON crm_auto_reply_record(tenant_id, message_id, occurred_at DESC);
-- 按阶梯读（推进下一级时用）。
CREATE INDEX crm_auto_reply_record_by_ladder
  ON crm_auto_reply_record(tenant_id, message_id, ladder_code, level);
-- 按结果筛（「有多少条被闸门挡下」）。
CREATE INDEX crm_auto_reply_record_by_outcome
  ON crm_auto_reply_record(tenant_id, outcome, occurred_at DESC);
-- 按时间倒序（记录列表）。
CREATE INDEX crm_auto_reply_record_by_time
  ON crm_auto_reply_record(tenant_id, occurred_at DESC);

-- ============================================================================
-- 5. crm_auto_reply_escalation · 转人工标记（只追加）
-- ============================================================================
CREATE TABLE crm_auto_reply_escalation (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  message_id uuid NOT NULL,
  message_no varchar(32) NOT NULL DEFAULT '',
  shop_id uuid NOT NULL,
  shop_name varchar(200) NOT NULL DEFAULT '',
  platform_code varchar(32) NOT NULL DEFAULT '',
  buyer_reference varchar(128) NOT NULL DEFAULT '',
  order_reference varchar(64) NOT NULL DEFAULT '',

  -- 触发这次转人工的自动回复记录（人工手动标记时可能为空）。
  record_id uuid,
  rule_id uuid,
  rule_code varchar(64) NOT NULL DEFAULT '',
  level integer NOT NULL DEFAULT 0,

  -- LADDER_EXHAUSTED 阶梯走完 · DUTY_GATE 值班闸挡下（仍要留人工出口）
  -- SEND_FAILED 发送登记失败（话术不可用等）· MANUAL_ESCALATION 人工手动标记
  reason_code varchar(24) NOT NULL
    CHECK (reason_code IN ('LADDER_EXHAUSTED','DUTY_GATE','SEND_FAILED','MANUAL_ESCALATION')),
  reason varchar(1000) NOT NULL CHECK (btrim(reason) <> ''),

  -- 标记人：null = 系统自动标记。
  escalated_by uuid,
  escalated_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  -- 一条消息只转人工一次：重复标记返回既有行，不制造两条「待处理」。
  UNIQUE (tenant_id, message_id)
);

-- 「待人工接管的队列」按时间倒序读。
CREATE INDEX crm_auto_reply_escalation_by_time
  ON crm_auto_reply_escalation(tenant_id, escalated_at DESC);
-- 按店铺筛。
CREATE INDEX crm_auto_reply_escalation_by_shop
  ON crm_auto_reply_escalation(tenant_id, shop_id, escalated_at DESC);

-- ============================================================================
-- 6. crm_auto_reply_takeover · 人工接管标记（只追加，按会话）
-- ============================================================================
CREATE TABLE crm_auto_reply_takeover (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- ===== 会话标识 =====
  -- ⚠️ 上游**没有会话实体**（15.1.1.1 只到「消息」这一层），
  --    所以本模块把「同一会话」定义为 (shop_id, buyer_reference)：
  --    同一店铺里的同一位买家 = 同一个会话线程。
  --    这是**本模块自己的定义**，在能力声明里如实写明，避免下游误以为有会话主数据。
  shop_id uuid NOT NULL,
  shop_name varchar(200) NOT NULL DEFAULT '',
  platform_code varchar(32) NOT NULL DEFAULT '',
  buyer_reference varchar(128) NOT NULL CHECK (btrim(buyer_reference) <> ''),

  -- 接管的源消息 / 源记录 / 源转人工标记（可空：接管可以先于任何自动回复发生）。
  message_id uuid,
  record_id uuid,
  escalation_id uuid,

  taken_over_by uuid NOT NULL,
  taken_over_at timestamptz NOT NULL,
  -- 接管原因**必填**：没有理由的接管，事后没人知道客服为什么不再让系统回。
  reason varchar(1000) NOT NULL CHECK (btrim(reason) <> ''),

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  -- 一个会话只接管一次；重复接管返回既有行（幂等）。
  UNIQUE (tenant_id, shop_id, buyer_reference)
);

-- 触发时按 (shop, buyer) 回查「这个会话是否已被接管」。
CREATE INDEX crm_auto_reply_takeover_by_conversation
  ON crm_auto_reply_takeover(tenant_id, shop_id, buyer_reference);
-- 按时间倒序（接管历史）。
CREATE INDEX crm_auto_reply_takeover_by_time
  ON crm_auto_reply_takeover(tenant_id, taken_over_at DESC);

-- ============================================================================
-- 7. 触发器
-- ============================================================================
-- ⚠️ 为什么用**同库回表**而不是**数据库外键**：
--   本仓同服务的既有迁移（V220 / V293 / V301 / V303）一律用触发器做引用校验 ——
--   数据库外键会打破既有测试的 TRUNCATE（PG：cannot truncate a table referenced in
--   a foreign key constraint）。本文件**不给任何既有表建外键**，
--   自己新建的表之间也**一律用触发器**。

-- 7.1 规则编码建后不可改：记录里存的是规则编码，改了它历史依据就对不上。
CREATE FUNCTION guard_crm_auto_reply_rule_code() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.rule_code IS DISTINCT FROM OLD.rule_code THEN
    RAISE EXCEPTION '规则编码建后不可改：历史自动回复记录里存的是编码，改了就对不上';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_auto_reply_rule_code_trg
  BEFORE UPDATE ON crm_auto_reply_rule
  FOR EACH ROW EXECUTE FUNCTION guard_crm_auto_reply_rule_code();

-- 7.2 阶梯的级别必须连号：第 N 级存在时，第 N-1 级必须存在。
--     缺一级 ⇒ 阶梯会跳过一级，「先引导再引导」变成「直接发第二级」。
CREATE FUNCTION guard_crm_auto_reply_rule_ladder() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.level > 1 AND NOT EXISTS (
      SELECT 1 FROM crm_auto_reply_rule r
      WHERE r.tenant_id = NEW.tenant_id
        AND r.ladder_code = NEW.ladder_code
        AND r.level = NEW.level - 1) THEN
    RAISE EXCEPTION '阶梯「%」的第 % 级不能先于第 % 级存在：阶梯必须连号',
      NEW.ladder_code, NEW.level, NEW.level - 1;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_auto_reply_rule_ladder_trg
  BEFORE INSERT ON crm_auto_reply_rule
  FOR EACH ROW EXECUTE FUNCTION guard_crm_auto_reply_rule_ladder();

-- 7.3 记录必须挂在同一租户下真实存在的消息上；命中规则（若给了）也必须真实存在。
CREATE FUNCTION guard_crm_auto_reply_record_target() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
      SELECT 1 FROM crm_message m
      WHERE m.id = NEW.message_id AND m.tenant_id = NEW.tenant_id) THEN
    RAISE EXCEPTION '自动回复记录必须挂在同一租户下真实存在的消息上（message_id=%）', NEW.message_id;
  END IF;
  IF NEW.rule_id IS NOT NULL AND NOT EXISTS (
      SELECT 1 FROM crm_auto_reply_rule r
      WHERE r.id = NEW.rule_id AND r.tenant_id = NEW.tenant_id) THEN
    RAISE EXCEPTION '自动回复记录引用的规则必须真实存在（rule_id=%）', NEW.rule_id;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_auto_reply_record_target_trg
  BEFORE INSERT ON crm_auto_reply_record
  FOR EACH ROW EXECUTE FUNCTION guard_crm_auto_reply_record_target();

-- 7.4 🔴 自动回复记录**只追加**：UPDATE / DELETE 一律拒绝。
--     改规则不追溯改变历史记录 —— 记录被改掉，就再也说不清「当时到底回了什么」。
CREATE FUNCTION guard_crm_auto_reply_record_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '自动回复记录**只追加**，不改不删：改规则不追溯改变历史记录'
    USING ERRCODE='23514';
END $$;

CREATE TRIGGER crm_auto_reply_record_append_only_trg
  BEFORE UPDATE ON crm_auto_reply_record
  FOR EACH ROW EXECUTE FUNCTION guard_crm_auto_reply_record_append_only();

CREATE TRIGGER crm_auto_reply_record_delete_trg
  BEFORE DELETE ON crm_auto_reply_record
  FOR EACH ROW EXECUTE FUNCTION guard_crm_auto_reply_record_append_only();

-- 7.5 转人工标记必须挂在真实存在的消息上。
CREATE FUNCTION guard_crm_auto_reply_escalation_target() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
      SELECT 1 FROM crm_message m
      WHERE m.id = NEW.message_id AND m.tenant_id = NEW.tenant_id) THEN
    RAISE EXCEPTION '转人工标记必须挂在同一租户下真实存在的消息上（message_id=%）', NEW.message_id;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_auto_reply_escalation_target_trg
  BEFORE INSERT ON crm_auto_reply_escalation
  FOR EACH ROW EXECUTE FUNCTION guard_crm_auto_reply_escalation_target();

-- 7.6 🔴 转人工标记只追加：它是「这件事需要人」的事实，改掉它就等于让待办消失。
CREATE FUNCTION guard_crm_auto_reply_escalation_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '转人工标记**只追加**，不改不删：它是「这件事需要人」的事实'
    USING ERRCODE='23514';
END $$;

CREATE TRIGGER crm_auto_reply_escalation_append_only_trg
  BEFORE UPDATE ON crm_auto_reply_escalation
  FOR EACH ROW EXECUTE FUNCTION guard_crm_auto_reply_escalation_append_only();

CREATE TRIGGER crm_auto_reply_escalation_delete_trg
  BEFORE DELETE ON crm_auto_reply_escalation
  FOR EACH ROW EXECUTE FUNCTION guard_crm_auto_reply_escalation_append_only();

-- 7.7 人工接管必须挂在真实存在的消息上（若给了消息）。
CREATE FUNCTION guard_crm_auto_reply_takeover_target() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.message_id IS NOT NULL AND NOT EXISTS (
      SELECT 1 FROM crm_message m
      WHERE m.id = NEW.message_id AND m.tenant_id = NEW.tenant_id) THEN
    RAISE EXCEPTION '人工接管必须挂在同一租户下真实存在的消息上（message_id=%）', NEW.message_id;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_auto_reply_takeover_target_trg
  BEFORE INSERT ON crm_auto_reply_takeover
  FOR EACH ROW EXECUTE FUNCTION guard_crm_auto_reply_takeover_target();

-- 7.8 🔴 人工接管只追加：接管是「这个人接手了」的事实。
CREATE FUNCTION guard_crm_auto_reply_takeover_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '人工接管标记**只追加**，不改不删：接管是「这个人接手了」的事实'
    USING ERRCODE='23514';
END $$;

CREATE TRIGGER crm_auto_reply_takeover_append_only_trg
  BEFORE UPDATE ON crm_auto_reply_takeover
  FOR EACH ROW EXECUTE FUNCTION guard_crm_auto_reply_takeover_append_only();

CREATE TRIGGER crm_auto_reply_takeover_delete_trg
  BEFORE DELETE ON crm_auto_reply_takeover
  FOR EACH ROW EXECUTE FUNCTION guard_crm_auto_reply_takeover_append_only();

-- 7.9 关闭全局开关时必须写明原因与操作人。
CREATE FUNCTION guard_crm_auto_reply_setting_closed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.auto_reply_enabled = false THEN
    IF btrim(NEW.closed_reason) = '' THEN
      RAISE EXCEPTION '关闭自动回复必须写明关闭原因：事后没人知道当时为什么关、该什么时候开回来';
    END IF;
    IF NEW.closed_at IS NULL OR NEW.closed_by IS NULL THEN
      RAISE EXCEPTION '关闭自动回复必须记录关闭时刻与操作人';
    END IF;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_auto_reply_setting_closed_trg
  BEFORE INSERT OR UPDATE ON crm_auto_reply_setting
  FOR EACH ROW EXECUTE FUNCTION guard_crm_auto_reply_setting_closed();

-- ============================================================================
-- 8. RLS（ENABLE + FORCE）与授权
-- ============================================================================
-- 与 V99 / V220 / V293 / V303 同一口径：策略名固定 isolation，USING 与 WITH CHECK 两侧同表达式。
-- 表达式里的 nullif 不是洁癖：事务局部的 set_config 提交之后，PostgreSQL 会把自定义 GUC
-- **复位成空串**而不是「回到未设置」，直接 cast 会报 uuid 语法错，
-- 而 nullif 把它归一成 NULL —— 条件不成立 → 一行都看不到。
-- 也就是说「忘记绑定租户」当场失败，而不是静默返回全表。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'crm_auto_reply_setting',
    'crm_auto_reply_rule',
    'crm_auto_reply_duty_calendar',
    'crm_auto_reply_record',
    'crm_auto_reply_escalation',
    'crm_auto_reply_takeover'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  crm_auto_reply_setting, crm_auto_reply_rule, crm_auto_reply_duty_calendar,
  crm_auto_reply_record, crm_auto_reply_escalation, crm_auto_reply_takeover TO crm_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  crm_auto_reply_setting, crm_auto_reply_rule, crm_auto_reply_duty_calendar,
  crm_auto_reply_record, crm_auto_reply_escalation, crm_auto_reply_takeover TO starcon_app;
-- 🔴 序列单独授权：GRANT ... ON ALL TABLES **不覆盖序列**，漏授权会变成
-- 「第一条自动回复记录就报权限不足」。
GRANT USAGE, SELECT ON SEQUENCE crm_auto_reply_record_no_seq TO crm_app;
GRANT USAGE, SELECT ON SEQUENCE crm_auto_reply_record_no_seq TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM crm_app;

-- ============================================================================
-- 9. 留给下游 / 上游的口子（不是本模块的缺口）
-- ============================================================================
--   * **15.1.1.2 消息发送**（V297）：本模块**不重造发送**，只调它的服务层登记发送
--     （模板 / 知识库话术 / 幂等键全部走它）。平台侧发送接口未接入 ⇒
--     transport 恒为 MANUAL_HANDOFF，运营在平台侧实际发出后回本服务登记结果。
--   * **15.4.1.2 工单处理**：**未交付** ⇒ 「人工介入」的**唯一**可判定信号是
--     15.1.1.2 的 SENT 发送记录（工单侧的介入动作本模块读不到）。
--   * **平台自动回复准入限制**：**未交付**（channel/vendor 下没有买家站内信适配器，
--     也没有任何平台的自动回复政策清单）⇒ 本模块**不编**平台白名单，
--     能力声明里如实标 available=false + ownerModule=2。
