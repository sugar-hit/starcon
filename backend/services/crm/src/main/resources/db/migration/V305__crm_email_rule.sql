-- 15.1.2.2 邮件规则：配置邮件自动处理规则（按发件人 / 关键词 / 工单标签），
-- 命中后自动分配 / 自动回复 / 静默；**关键沟通静默会造成平台违规**，规则需强制留痕。
--
-- 蓝图 15 卷 15.1.2.2 的原话：
--   「职责：配置邮件自动处理规则：按发件人/关键词/工单标签自动分配、自动回复、静默规则（重要沟通不可静默）。
--     输入：规则配置；15.1.1.3 分类结果。
--     输出：规则命中记录；自动分配结果。
--     关系：上游 = 15.1.1.3；下游 = 15.4.1.1 工单分配。**关键沟通静默会造成平台违规**，规则需强制留痕。」
--
-- ============================================================ 零、与既有模块的边界
--
-- 本文件是 **crm 数据库的第十四个迁移**（前十三个：V99 知识库 / V220 索评 / V228 索评规则 /
-- V292 邮件收发 / V293 消息收件箱 / V294 评价 / V295 客户标签 / V296 弃单挽回 /
-- V297 消息发送 / V298 评价回复 / V299 接触记录 / V301 消息分类 / V302 差评 / V303 关怀催办）。
--
-- ⚠️ 本模块**不 ALTER 任何既有表**：
--   * `crm_buyer_email`（15.1.2.1 邮件收发）是**上游**，本服务**同库同服务**，
--     所以直接 jOOQ 查它就行 —— 但**只读**，本模块**一个字都不改**它的列；
--   * `crm_message_classification`（15.1.1.3 消息分类）是**上游**，
--     同样**只读**：本模块**不造一份平行分类表**，重要沟通的判定直接读它的结论。
--
-- 本模块新建**五张表**（各一个写入所有者）：
--   crm_email_rule                  · 规则定义（条件维度 + 命中动作 + 顺序 + 启用 + 版本）
--   crm_email_ticket_tag            · 工单标签字典（规则条件引用的标签码必须真实存在）
--   crm_email_ticket_tag_assignment · 邮件 ↔ 工单标签（工单未交付 ⇒ 标签先落在邮件上）
--   crm_email_rule_hit              · **规则命中记录（只追加）** = 蓝图输出句的第一项
--   crm_email_rule_assignment       · **自动分配结果** = 蓝图输出句的第二项
--
-- ============================================================ 一、🔴 加粗句的落地：
-- 「关键沟通静默会造成平台违规，规则需强制留痕」
--
-- 这句话在本文件里落成**三件事**，而不是一句注释：
--
--   1. **命中记录只追加**：
--      触发器 `guard_crm_email_rule_hit_append_only` 在 UPDATE / DELETE 时直接报错。
--      命中记录是「当时发生了什么」的证据 —— 改得掉的记录等于「没发生过」。
--   2. **静默被强制降级**（不是靠前端约定）：
--      命中记录同时存 **action（规则声明的动作）** 与 **effective_action（实际生效的动作）**。
--      当命中的是**重要沟通**（判定见第三节）而规则要求静默时，
--      `effective_action` 只能是 `ESCALATE_MANUAL`（不静默 + 转人工），
--      并且 `silence_refused = true` + `refusal_reason` 写明理由 ——
--      **DB 层 CHECK 钉死**：SILENCE 生效时 silence_refused 必须为 false，
--      ESCALATE_MANUAL 时必须为 true，写反了直接插入失败。
--   3. **命中凭据冻结当时的规则**：
--      命中记录带 `rule_version`（规则版本号）与 `rule_snapshot`（当时的规则快照 jsonb），
--      以及 `match_basis`（命中条件明细 jsonb）。
--      ⇒ **改规则不追溯改变历史命中记录**：规则改了之后命中的是**新版本**，
--      历史那条仍然写着它当时用的规则内容与命中条件。
--
-- ============================================================ 二、三个条件维度 + 顺序 + 启用
--
-- 蓝图输入句：「规则配置」；职责句点名三种维度：**发件人 / 关键词 / 工单标签**。
-- 三者都做成**真的列**：
--   * sender_emails  —— 发件人邮箱（换行分隔；精确匹配，忽略大小写）
--   * sender_domains —— 发件人域名（逗号分隔；精确匹配，忽略大小写）
--   * keywords       —— 关键词（换行分隔；主题 / 正文，字面包含，忽略大小写）
--   * ticket_tag_codes —— 工单标签码（逗号分隔；与邮件当前挂的工单标签比对）
--   * match_mode     —— **同一维度内**多个值的命中方式：ANY（任一命中）/ ALL（全部命中）
--   * priority_order —— **显式顺序**：数字小的先命中（并列时按 rule_code）
--   * enabled        —— 停用规则不参与命中，但**不删**：删了历史命中依据就查不到了
--
-- ⚠️ 顺序必须**显式**：同一封邮件经常同时命中多条规则（「VIP 客户」和「投诉」）。
-- 不给顺序的话，命中结果取决于插入顺序 —— 那是不可复现的规则引擎，
-- 运营改一条规则就会让历史命中记录集体漂移。
--
-- ⚠️ **至少一个维度非空**（CHECK）：四个维度全空的规则**永远不会命中**，
-- 却会占掉一个顺序位 —— 那是一条「看起来配了、其实没生效」的规则。
--
-- ⚠️ **规则条件引用的工单标签码必须真实存在**（触发器回表校验）：
-- 引用一个字典里没有的标签码 = 一条永远不会命中的规则。
--
-- ============================================================ 三、「重要沟通」怎么判定（可判定，不靠前端）
--
-- 两个**可判定**的来源，任一命中即视为重要沟通：
--
--   1. **分类结果的关键类别**（上游 15.1.1.3，同库同服务，只读）：
--      `crm_message_classification.label` ∈ ('PLATFORM_COMPLIANCE','COMPLAINT')。
--      平台合规 = 静默它等于违规；投诉 = 静默它等于把纠纷吞掉。
--      邮件通过 `crm_buyer_email.source_message_id` 关联到站内信，
--      再关联到那条消息的分类结论（`crm_message_classification.message_id`）。
--   2. **规则标记**（`important_markers`：换行分隔的关键字 / 短语）：
--      例如「attorney」「chargeback」「legal」—— 这类词在分类结果出来之前就该被认出来。
--
-- 判定结果落在命中记录的 `important` + `important_source` 两列上，
-- **留痕**用：为什么这一条被判成重要沟通，事后查得到。
--
-- ============================================================ 四、跨服务纪律
--
-- 上游 `crm_buyer_email` 与 `crm_message_classification` 在**本服务本库**（同库同服务），
-- 所以直接查；下游 15.4.1.1 工单分配**未交付** ⇒
-- 本模块只产出**自动分配结果**（分配给谁、依据是哪条规则、命中了哪些条件），
-- **不创建工单**、**不写任何工单 id**（下游没交付就没有 id，编一个会让「已转工单」看起来发生过）。
--
-- ============================================================ 五、时间、数量与金额
--
--   * 时刻一律 **timestamptz** + OffsetDateTime，绑定写 `?::timestamptz`。
--     （用 Timestamp.valueOf(LocalDateTime) 会在东八区静默偏 −28800 秒。）
--   * 本模块**没有金额**（规则与命中记录都不涉及钱）；命中次数等计数用 integer / bigint。
--
-- ============================================================================
-- 零之一、角色
-- ============================================================================
-- 与 V99 / V220 / V301 / V303 同一口径：角色可能已由运维预置，因此只在缺失时创建。
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='crm_app') THEN CREATE ROLE crm_app; END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN CREATE ROLE starcon_app; END IF;
END $$;
GRANT USAGE ON SCHEMA public TO crm_app;

-- ============================================================================
-- 1. crm_email_rule · 邮件规则
-- ============================================================================
CREATE TABLE crm_email_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 规则编码：运营在文档 / 工单里引用它的唯一名字（同租户内唯一）。
  rule_code varchar(64) NOT NULL CHECK (btrim(rule_code) <> ''),
  rule_name varchar(200) NOT NULL CHECK (btrim(rule_name) <> ''),

  -- 🔴 显式顺序：数字小的先命中；并列时按 rule_code 升序（确定性）。
  priority_order integer NOT NULL CHECK (priority_order >= 0),

  -- 停用而不是删除：历史命中依据里带着规则编码与快照，删了就查不到了。
  enabled boolean NOT NULL DEFAULT true,

  -- ===== 命中动作 =====
  -- AUTO_ASSIGN 自动分配 · AUTO_REPLY 自动回复 · SILENCE 静默
  action varchar(24) NOT NULL CHECK (action IN ('AUTO_ASSIGN','AUTO_REPLY','SILENCE')),

  -- ===== 条件维度 =====
  -- 发件人邮箱（换行分隔；精确匹配，忽略大小写）。
  sender_emails text NOT NULL DEFAULT '',
  -- 发件人域名（逗号分隔；精确匹配，忽略大小写）。
  sender_domains text NOT NULL DEFAULT '',
  -- 关键词（换行分隔；主题 / 正文，字面包含，忽略大小写）。
  keywords text NOT NULL DEFAULT '',
  -- 工单标签码（逗号分隔；与邮件当前挂的工单标签比对）。
  ticket_tag_codes text NOT NULL DEFAULT '',
  -- 同一维度内多个值的命中方式。
  match_mode varchar(16) NOT NULL DEFAULT 'ANY' CHECK (match_mode IN ('ANY','ALL')),

  -- ===== 动作载荷 =====
  -- 自动分配给谁：TEAM（组）/ AGENT（人）。
  assign_target_type varchar(16) NOT NULL DEFAULT 'TEAM' CHECK (assign_target_type IN ('TEAM','AGENT')),
  assign_target_code varchar(128) NOT NULL DEFAULT '',
  -- 自动回复用哪套话术（模板码 + 标题 / 正文快照）。
  reply_template_code varchar(64) NOT NULL DEFAULT '',
  reply_subject varchar(500) NOT NULL DEFAULT '',
  reply_body text NOT NULL DEFAULT '',

  -- ===== 重要沟通标记 =====
  -- 规则自己声明的「关键沟通」关键词（换行分隔）；命中即视为重要沟通。
  important_markers text NOT NULL DEFAULT '',

  -- ===== 版本 =====
  -- 每次修改 +1：命中记录冻结当时的版本 ⇒ 改规则不追溯改变历史命中记录。
  version integer NOT NULL DEFAULT 1 CHECK (version >= 1),

  note text NOT NULL DEFAULT '',
  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, rule_code),

  -- 至少一个条件维度非空：全空的规则永远不会命中，只是一条看起来配了的空规则。
  CHECK (
    btrim(sender_emails) <> '' OR btrim(sender_domains) <> ''
    OR btrim(keywords) <> '' OR btrim(ticket_tag_codes) <> ''
  ),

  -- 动作与载荷必须配套：自动分配必须有分配目标，自动回复必须有话术。
  CHECK (action <> 'AUTO_ASSIGN' OR btrim(assign_target_code) <> ''),
  CHECK (
    action <> 'AUTO_REPLY'
    OR (btrim(reply_template_code) <> '' OR btrim(reply_body) <> '')
  )
);

-- 命中顺序：按 (order, code) 走 —— 评估时的候选集就是这条索引。
CREATE INDEX crm_email_rule_by_order
  ON crm_email_rule(tenant_id, priority_order, rule_code);
-- 只看启用中的规则。
CREATE INDEX crm_email_rule_by_enabled
  ON crm_email_rule(tenant_id, enabled, priority_order);
-- 按动作筛（页面上的「静默规则 / 自动分配规则」分组）。
CREATE INDEX crm_email_rule_by_action
  ON crm_email_rule(tenant_id, action);

-- ============================================================================
-- 2. crm_email_ticket_tag · 工单标签字典
-- ============================================================================
-- 为什么要有字典：规则条件引用的标签码**必须真实存在** ——
-- 引用一个不存在的标签码 = 一条永远不会命中的规则（看起来配了，其实没生效）。
CREATE TABLE crm_email_ticket_tag (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  tag_code varchar(64) NOT NULL CHECK (btrim(tag_code) <> ''),
  name varchar(120) NOT NULL CHECK (btrim(name) <> ''),
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
  description varchar(600) NOT NULL DEFAULT '',

  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, tag_code)
);

-- ============================================================================
-- 3. crm_email_ticket_tag_assignment · 邮件 ↔ 工单标签
-- ============================================================================
-- 15.4.1.1 工单**未交付** ⇒ 工单标签先落在**邮件**上：
-- 规则条件按这些标签判定，工单交付后由它来承接同一批标签。
CREATE TABLE crm_email_ticket_tag_assignment (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  email_id uuid NOT NULL,
  tag_id uuid NOT NULL,
  -- 标签码快照：标签改名后，历史打标记录仍说得清「当时打的是哪个标签」。
  tag_code varchar(64) NOT NULL CHECK (btrim(tag_code) <> ''),

  source varchar(16) NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('MANUAL','RULE')),
  assigned_by uuid,
  assigned_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  -- 同一封邮件上的同一标签只记一次（重复打标是幂等的，不重复记账）。
  UNIQUE (tenant_id, email_id, tag_code)
);

-- 按邮件读标签（评估时的候选集就是这条索引）。
CREATE INDEX crm_email_ticket_tag_assignment_by_email
  ON crm_email_ticket_tag_assignment(tenant_id, email_id);
-- 按标签读（规则条件命中统计、标签使用率）。
CREATE INDEX crm_email_ticket_tag_assignment_by_tag
  ON crm_email_ticket_tag_assignment(tenant_id, tag_code);

-- ============================================================================
-- 4. crm_email_rule_hit · 规则命中记录（只追加）
-- ============================================================================
-- 命中号序列单独建、单独授权：GRANT ... ON ALL TABLES 不覆盖序列，
-- 漏授权会变成「第一条命中就报权限不足」（V293 / V301 已踩过这个坑）。
CREATE SEQUENCE crm_email_rule_hit_no_seq START 1;

CREATE TABLE crm_email_rule_hit (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- ERT-yyyyMMdd-000001
  hit_no varchar(32) NOT NULL DEFAULT '',

  -- ===== 命中的规则（快照）=====
  -- ⚠️ 刻意**不建**指向 crm_email_rule 的外键，也**不**用触发器要求规则仍然存在：
  --   规则允许删除（配置），而命中记录必须留下（事实）。
  --   规则内容以 rule_snapshot 冻结，改规则 / 删规则都不追溯改变这一条。
  rule_id uuid NOT NULL,
  rule_code varchar(64) NOT NULL DEFAULT '',
  rule_name varchar(200) NOT NULL DEFAULT '',
  -- 当时的规则版本号。
  rule_version integer NOT NULL CHECK (rule_version >= 1),
  -- 当时的规则快照（jsonb）：条件 + 动作 + 载荷 + 重要标记，全量冻结。
  rule_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,

  -- ===== 邮件快照 =====
  email_id uuid NOT NULL,
  email_no varchar(32) NOT NULL DEFAULT '',
  buyer_email varchar(320) NOT NULL DEFAULT '',
  subject varchar(500) NOT NULL DEFAULT '',

  -- ===== 命中条件 =====
  -- {"winningRuleId":"...","winningRuleCode":"...","matches":[{"ruleId":"...","ruleCode":"...",
  --   "ruleName":"...","ruleOrder":10,"action":"AUTO_ASSIGN","won":true,
  --   "conditions":{"sender":{"matched":true,"emails":["a@b.com"],"domains":["b.com"],
  --     "matchedEmails":["a@b.com"],"matchedDomains":[]},
  --     "keywords":{"matched":true,"keywords":["refund"],"matchedKeywords":["refund"]},
  --     "ticketTags":{"matched":true,"codes":["VIP"],"matchedCodes":["VIP"]}}}]}
  match_basis jsonb NOT NULL DEFAULT '{}'::jsonb,

  -- ===== 动作结果 =====
  -- 规则**声明**的动作。
  action varchar(24) NOT NULL CHECK (action IN ('AUTO_ASSIGN','AUTO_REPLY','SILENCE')),
  -- **实际生效**的动作：静默被拒时只能是 ESCALATE_MANUAL（不静默 + 转人工）。
  effective_action varchar(24) NOT NULL
    CHECK (effective_action IN ('AUTO_ASSIGN','AUTO_REPLY','SILENCE','ESCALATE_MANUAL')),
  -- 🔴 静默是否被拒（重要沟通命中静默规则时为 true）。
  silence_refused boolean NOT NULL DEFAULT false,
  -- 被拒理由（可读）：为什么这一条没有静默。
  refusal_reason varchar(600) NOT NULL DEFAULT '',

  -- ===== 重要沟通判定（留痕）=====
  important boolean NOT NULL DEFAULT false,
  -- CLASSIFICATION（分类结果的关键类别）/ RULE_MARKER（规则自己标记的关键词）/ 空 = 不重要。
  important_source varchar(24) NOT NULL DEFAULT ''
    CHECK (important_source IN ('','CLASSIFICATION','RULE_MARKER')),
  -- 判定所依据的分类结论（上游 15.1.1.3，只读）。
  classification_id uuid,
  classification_label varchar(32) NOT NULL DEFAULT '',

  -- ⚠️ 这里**刻意没有** assignment_id 列：命中记录只追加（不可 UPDATE），
  --   事后再回填「这条命中产出了哪条分配结果」就必须 UPDATE 一行 —— 那会破坏只追加的纪律。
  --   改成**反查**：分配结果表持有 hit_id，读取命中记录时反查分配结果。

  -- 自动回复的投递状态：本模块**只判定并留痕意图**，真正的投递由 15.5.1 自动回复负责
  -- （未交付）⇒ 恒为 PENDING_DOWNSTREAM，**不谎称已发出**。
  -- ⚠️ 只有真正生效为自动回复时才有投递状态；其它动作为 NULL（不是「已投递」）。
  delivery_status varchar(24)
    CHECK (delivery_status IS NULL OR delivery_status IN ('PENDING_DOWNSTREAM')),

  matched_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, hit_no),
  -- 幂等：同一封邮件 + 同一条规则的**同一版本**只记一次（重复评估不重复记账）。
  UNIQUE (tenant_id, email_id, rule_id, rule_version),

  -- 🔴 静默与降级必须配套（DB 层钉死，不靠应用层自觉）：
  --   生效动作为 SILENCE 时不许出现「被拒」标记；
  --   生效动作为 ESCALATE_MANUAL 时必须是被拒的静默。
  CHECK (
    (effective_action = 'SILENCE' AND silence_refused = false)
    OR (effective_action = 'ESCALATE_MANUAL' AND silence_refused = true)
    OR (effective_action NOT IN ('SILENCE','ESCALATE_MANUAL') AND silence_refused = false)
  ),
  -- 投递状态只对自动回复有意义：生效动作为自动回复时必须是「等下游投递」，
  -- 其它动作**不许**带投递状态（不是「已投递」）。
  CHECK (
    (effective_action = 'AUTO_REPLY' AND delivery_status = 'PENDING_DOWNSTREAM')
    OR (effective_action <> 'AUTO_REPLY' AND delivery_status IS NULL)
  )
);

-- 按邮件读命中记录（详情页要按邮件列）。
CREATE INDEX crm_email_rule_hit_by_email
  ON crm_email_rule_hit(tenant_id, email_id, matched_at DESC);
-- 按规则读命中记录（规则命中统计）。
CREATE INDEX crm_email_rule_hit_by_rule
  ON crm_email_rule_hit(tenant_id, rule_id, matched_at DESC);
-- 按生效动作筛（页面最常用的一句：「静默被拒了多少条」）。
CREATE INDEX crm_email_rule_hit_by_action
  ON crm_email_rule_hit(tenant_id, effective_action, matched_at DESC);
-- 按时间读（命中流水倒序）。
CREATE INDEX crm_email_rule_hit_by_time
  ON crm_email_rule_hit(tenant_id, matched_at DESC);

-- ============================================================================
-- 5. crm_email_rule_assignment · 自动分配结果
-- ============================================================================
CREATE SEQUENCE crm_email_rule_assignment_no_seq START 1;

CREATE TABLE crm_email_rule_assignment (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- EAS-yyyyMMdd-000001
  assignment_no varchar(32) NOT NULL DEFAULT '',

  -- 挂在哪条命中记录上（同一租户下真实存在的命中记录）。
  hit_id uuid NOT NULL,
  rule_id uuid NOT NULL,
  rule_code varchar(64) NOT NULL DEFAULT '',
  rule_version integer NOT NULL CHECK (rule_version >= 1),

  -- ===== 邮件快照 =====
  email_id uuid NOT NULL,
  email_no varchar(32) NOT NULL DEFAULT '',
  buyer_email varchar(320) NOT NULL DEFAULT '',
  subject varchar(500) NOT NULL DEFAULT '',

  -- ===== 分配给谁 =====
  assign_target_type varchar(16) NOT NULL CHECK (assign_target_type IN ('TEAM','AGENT')),
  assign_target_code varchar(128) NOT NULL CHECK (btrim(assign_target_code) <> ''),

  -- ===== 状态 =====
  -- PENDING_DISPATCH：等下游 15.4.1.1 工单分配来取（**当前唯一常态** ——
  --   下游未交付，本模块**不创建工单、也不写工单 id**）。
  -- DISPATCHED / CANCELLED：向前单向的状态迁移（触发器钉死，身份列不可改）。
  status varchar(24) NOT NULL DEFAULT 'PENDING_DISPATCH'
    CHECK (status IN ('PENDING_DISPATCH','DISPATCHED','CANCELLED')),
  dispatch_note varchar(600) NOT NULL DEFAULT '',
  cancel_reason varchar(600) NOT NULL DEFAULT '',
  cancelled_by uuid,
  cancelled_at timestamptz,

  assigned_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, assignment_no),
  -- 一条命中记录最多产出一条分配结果（幂等）。
  UNIQUE (tenant_id, hit_id),

  -- 取消必须写理由。
  CHECK (status <> 'CANCELLED' OR btrim(cancel_reason) <> '')
);

-- 按邮件读分配结果。
CREATE INDEX crm_email_rule_assignment_by_email
  ON crm_email_rule_assignment(tenant_id, email_id, assigned_at DESC);
-- 按分配目标读（「VIP 组今天分到多少封」）。
CREATE INDEX crm_email_rule_assignment_by_target
  ON crm_email_rule_assignment(tenant_id, assign_target_type, assign_target_code, assigned_at DESC);
-- 按状态筛（待下游取走的）。
CREATE INDEX crm_email_rule_assignment_by_status
  ON crm_email_rule_assignment(tenant_id, status, assigned_at DESC);

-- ============================================================================
-- 6. 触发器
-- ============================================================================
-- ⚠️ 为什么用**同库回表**而不是**数据库外键**：
--   本仓同服务的既有迁移（V220 / V292 / V293 / V301）一律用触发器做引用校验 ——
--   数据库外键会打破既有测试的 TRUNCATE（PG：cannot truncate a table referenced in
--   a foreign key constraint）。本文件**不给任何既有表建外键**，
--   自己新建的表之间也**一律用触发器**。

-- 6.1 规则编码建后不可改：命中记录里存的是编码，改了它历史依据就对不上。
CREATE FUNCTION guard_crm_email_rule_code() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.rule_code IS DISTINCT FROM OLD.rule_code THEN
    RAISE EXCEPTION '规则编码建后不可改：历史命中记录里存的是编码，改了就对不上';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_email_rule_code_trg
  BEFORE UPDATE ON crm_email_rule
  FOR EACH ROW EXECUTE FUNCTION guard_crm_email_rule_code();

-- 6.2 规则条件引用的工单标签码必须真实存在（否则是一条永远不会命中的规则）。
CREATE FUNCTION guard_crm_email_rule_tag_codes() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  code text;
  missing text;
BEGIN
  FOR code IN SELECT unnest(string_to_array(NEW.ticket_tag_codes, ',')) LOOP
    code := btrim(code);
    IF code = '' THEN
      CONTINUE;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM crm_email_ticket_tag t
        WHERE t.tenant_id = NEW.tenant_id AND t.tag_code = code) THEN
      missing := missing || CASE WHEN missing IS NULL THEN '' ELSE ', ' END || code;
    END IF;
  END LOOP;
  IF missing IS NOT NULL THEN
    RAISE EXCEPTION '规则条件引用了不存在的工单标签码（%）：那是一条永远不会命中的规则', missing;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_email_rule_tag_codes_trg
  BEFORE INSERT OR UPDATE ON crm_email_rule
  FOR EACH ROW EXECUTE FUNCTION guard_crm_email_rule_tag_codes();

-- 6.3 打标必须挂在同一租户下真实存在的邮件上。
CREATE FUNCTION guard_crm_email_ticket_tag_assignment_email() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
      SELECT 1 FROM crm_buyer_email e
      WHERE e.id = NEW.email_id AND e.tenant_id = NEW.tenant_id) THEN
    RAISE EXCEPTION '工单标签必须挂在同一租户下真实存在的邮件上（email_id=%）', NEW.email_id;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_email_ticket_tag_assignment_email_trg
  BEFORE INSERT ON crm_email_ticket_tag_assignment
  FOR EACH ROW EXECUTE FUNCTION guard_crm_email_ticket_tag_assignment_email();

-- 6.4 打标引用的标签必须真实存在（同一租户下）。
CREATE FUNCTION guard_crm_email_ticket_tag_assignment_tag() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
      SELECT 1 FROM crm_email_ticket_tag t
      WHERE t.id = NEW.tag_id AND t.tenant_id = NEW.tenant_id
        AND t.tag_code = NEW.tag_code) THEN
    RAISE EXCEPTION '打标引用的工单标签不存在（tag_id=% / tag_code=%）', NEW.tag_id, NEW.tag_code;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_email_ticket_tag_assignment_tag_trg
  BEFORE INSERT ON crm_email_ticket_tag_assignment
  FOR EACH ROW EXECUTE FUNCTION guard_crm_email_ticket_tag_assignment_tag();

-- 6.5 命中记录必须挂在同一租户下真实存在的邮件上。
CREATE FUNCTION guard_crm_email_rule_hit_email() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
      SELECT 1 FROM crm_buyer_email e
      WHERE e.id = NEW.email_id AND e.tenant_id = NEW.tenant_id) THEN
    RAISE EXCEPTION '命中记录必须挂在同一租户下真实存在的邮件上（email_id=%）', NEW.email_id;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_email_rule_hit_email_trg
  BEFORE INSERT ON crm_email_rule_hit
  FOR EACH ROW EXECUTE FUNCTION guard_crm_email_rule_hit_email();

-- 6.6 🔴 命中记录只追加：UPDATE / DELETE 一律拒绝。
--     改得掉的命中记录 = 「当时静默过这封重要邮件」这句话可以随时被抹掉 ——
--     而那正是平台违规的证据。
CREATE FUNCTION guard_crm_email_rule_hit_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '规则命中记录只追加，不改不删：命中记录是「当时发生了什么」的证据，改得掉就等于没发生过';
END $$;

CREATE TRIGGER crm_email_rule_hit_append_only_trg
  BEFORE UPDATE OR DELETE ON crm_email_rule_hit
  FOR EACH ROW EXECUTE FUNCTION guard_crm_email_rule_hit_append_only();

-- 6.7 自动分配结果必须挂在同一租户下真实存在的命中记录上。
CREATE FUNCTION guard_crm_email_rule_assignment_target() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
      SELECT 1 FROM crm_email_rule_hit h
      WHERE h.id = NEW.hit_id AND h.tenant_id = NEW.tenant_id) THEN
    RAISE EXCEPTION '自动分配结果必须挂在同一租户下真实存在的命中记录上（hit_id=%）', NEW.hit_id;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_email_rule_assignment_target_trg
  BEFORE INSERT ON crm_email_rule_assignment
  FOR EACH ROW EXECUTE FUNCTION guard_crm_email_rule_assignment_target();

-- 6.8 自动分配结果：身份列不可改，状态只能向前单向迁移。
CREATE FUNCTION guard_crm_email_rule_assignment_state() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.hit_id IS DISTINCT FROM OLD.hit_id
     OR NEW.email_id IS DISTINCT FROM OLD.email_id
     OR NEW.assign_target_type IS DISTINCT FROM OLD.assign_target_type
     OR NEW.assign_target_code IS DISTINCT FROM OLD.assign_target_code
     OR NEW.rule_id IS DISTINCT FROM OLD.rule_id THEN
    RAISE EXCEPTION '自动分配结果的身份列不可改：改了会让「这一封邮件分给了谁」在历史上出现两个答案';
  END IF;
  IF OLD.status = 'DISPATCHED' AND NEW.status <> 'DISPATCHED' THEN
    RAISE EXCEPTION '已投递的分配结果不可回退：它已经进了下游工单';
  END IF;
  IF OLD.status = 'CANCELLED' AND NEW.status <> 'CANCELLED' THEN
    RAISE EXCEPTION '已取消的分配结果不可复活：取消就是取消';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_email_rule_assignment_state_trg
  BEFORE UPDATE ON crm_email_rule_assignment
  FOR EACH ROW EXECUTE FUNCTION guard_crm_email_rule_assignment_state();

-- 6.9 被引用过的工单标签删不掉（规则条件 / 打标记录里还指着它）。
CREATE FUNCTION guard_crm_email_ticket_tag_delete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_count integer;
BEGIN
  SELECT count(*) INTO v_count FROM crm_email_ticket_tag_assignment
   WHERE tenant_id = OLD.tenant_id AND tag_id = OLD.id;
  IF v_count > 0 THEN
    RAISE EXCEPTION '工单标签 % 已被 % 条打标记录引用，删不掉：可以改成 DISABLED 停用', OLD.tag_code, v_count;
  END IF;
  IF EXISTS (
      SELECT 1 FROM crm_email_rule r
       WHERE r.tenant_id = OLD.tenant_id
         AND OLD.tag_code = ANY(string_to_array(r.ticket_tag_codes, ','))) THEN
    RAISE EXCEPTION '工单标签 % 已被规则条件引用，删不掉：删了那条规则就永远不会命中', OLD.tag_code;
  END IF;
  RETURN OLD;
END $$;

CREATE TRIGGER crm_email_ticket_tag_delete_trg
  BEFORE DELETE ON crm_email_ticket_tag
  FOR EACH ROW EXECUTE FUNCTION guard_crm_email_ticket_tag_delete();

-- ============================================================================
-- 7. RLS（ENABLE + FORCE）与授权
-- ============================================================================
-- 与 V99 / V220 / V293 / V301 同一口径：策略名固定 isolation，USING 与 WITH CHECK 两侧同表达式。
-- 表达式里的 nullif 不是洁癖：事务局部的 set_config 提交之后，PostgreSQL 会把自定义 GUC
-- **复位成空串**而不是「回到未设置」，直接 cast 会报 uuid 语法错，
-- 而 nullif 把它归一成 NULL —— 条件不成立 → 一行都看不到。
-- 也就是说「忘记绑定租户」当场失败，而不是静默返回全表。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['crm_email_rule','crm_email_ticket_tag',
                             'crm_email_ticket_tag_assignment','crm_email_rule_hit',
                             'crm_email_rule_assignment'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON crm_email_rule, crm_email_ticket_tag,
  crm_email_ticket_tag_assignment, crm_email_rule_hit, crm_email_rule_assignment TO crm_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON crm_email_rule, crm_email_ticket_tag,
  crm_email_ticket_tag_assignment, crm_email_rule_hit, crm_email_rule_assignment TO starcon_app;
-- 🔴 序列单独授权：GRANT ... ON ALL TABLES **不覆盖序列**，漏授权会变成
-- 「第一条命中记录就报权限不足」。
GRANT USAGE, SELECT ON SEQUENCE crm_email_rule_hit_no_seq TO crm_app;
GRANT USAGE, SELECT ON SEQUENCE crm_email_rule_hit_no_seq TO starcon_app;
GRANT USAGE, SELECT ON SEQUENCE crm_email_rule_assignment_no_seq TO crm_app;
GRANT USAGE, SELECT ON SEQUENCE crm_email_rule_assignment_no_seq TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM crm_app;

-- ============================================================================
-- 8. 留给下游 / 上游的口子（不是本模块的缺口）
-- ============================================================================
--   * **15.4.1.1 工单分配**（下游）：**未交付**。本模块只产出**自动分配结果**
--     （分配给谁、依据哪条规则、命中了哪些条件），**不创建工单、不写工单 id**。
--   * **15.5.1 自动回复**（下游）：**未交付**。本模块只**判定并留痕**自动回复意图
--     （模板码 + 标题 / 正文快照），**不真的发出**；命中记录上的
--     `delivery_status` 恒为 `PENDING_DOWNSTREAM`，**不谎称已发出**。
--   * **15.1.2.1 邮件收发**（上游）：同库同服务，本模块**只读** `crm_buyer_email`，
--     **不改**它的任何列。
--   * **15.1.1.3 消息分类**（上游）：同库同服务，本模块**只读**
--     `crm_message_classification`，**不造一份平行分类表**。
