-- 15.1.1.3 消息分类：对消息自动分类（售前咨询、物流查询、退货退款、投诉、平台合规、垃圾信息），
-- 驱动自动回复与优先级，支持人工纠正以改进分类。
--
-- 蓝图 15 卷 15.1.1.3 的原话：
--   「职责：对消息自动分类（售前咨询、物流查询、退货退款、投诉、平台合规、垃圾信息），
--     驱动自动回复与优先级，支持人工纠正以改进分类。
--     输入：消息内容；分类规则（关键词 + 平台类型 + 历史纠正数据）。
--     输出：分类标签 + 优先级 + 是否可自动回复。
--     关系：上游 = 15.1.1.1；下游 = 15.5.1 自动回复、1.3 预警。
--     **人工纠正要回流为训练样本**，否则分类准确率永远上不去。」
--
-- ============================================================ 零、与既有模块的边界
--
-- 本文件是 **crm 数据库的第十一个迁移**（前十个：V99 知识库 / V220 索评 / V228 索评规则 /
-- V292 邮件收发 / V293 消息收件箱 / V294 评价 / V295 客户标签 / V296 弃单挽回 /
-- V297 消息发送 / V298 评价回复 / V299 接触记录）。
--
-- ⚠️ 本模块**不 ALTER 任何既有表**：
--   * `crm_message`（15.1.1.1 消息收件箱）是**上游**，本服务**同库同服务**，
--     所以直接 jOOQ 查它就行 —— 但**只读**，本模块**一个字都不改**它的列；
--   * 分类结论**不写回** `crm_message.priority`（见第四节）：那是人工设过的值，
--     偷偷用建议值盖掉它 = 客服设的优先级无声消失。
--
-- 本模块新建**三张表**（各一个写入所有者）：
--   crm_message_classification_rule      · 分类规则（关键词 + 适用平台 + 适用类型 +
--                                          目标标签 + 建议优先级 + 是否可自动回复 + 顺序 + 启用）
--   crm_message_classification            · 分类结论（一条消息一条：标签 / 建议优先级 /
--                                          人工值 / 冲突标记 / 是否可自动回复 / 命中依据）
--   crm_message_classification_feedback   · 人工纠正（**只追加**）= 蓝图说的**训练样本**
--
-- ============================================================ 一、🔴 加粗句的落地：
-- 「人工纠正要回流为训练样本，否则分类准确率永远上不去」
--
-- 这句话在本文件里落成**三件事**，而不是一句注释：
--
--   1. **纠正与结论分开两张表**：
--      `crm_message_classification` 是**机器的结论**（可被重新分类覆盖）；
--      `crm_message_classification_feedback` 是**人的纠正**（**只追加、不可改、不可删**）。
--      纠正**不回写**结论里的标签/优先级列 —— 结论永远是「机器当时说了什么」，
--      人工的判断另存一条，事后能逐条比对。
--   2. **反馈行自带机器预测快照**：
--      predicted_label / predicted_priority / predicted_rule_code 是**纠正当时**的机器输出，
--      哪怕之后重新分类覆盖了结论，这一行仍然记着「当时判成什么、被改成了什么」——
--      没有这份快照，训练样本就只剩「正确答案」，学不出错在哪。
--   3. **DB 层强制只追加**：
--      触发器 `guard_crm_message_classification_feedback_append_only` 在 UPDATE / DELETE 时
--      直接报错。训练样本被改掉 = 准确率指标可以随时被改漂亮 —— 那这条指标就没有意义了。
--
-- ============================================================ 二、分类规则是真的能配的
--
-- 蓝图输入句：「分类规则（关键词 + 平台类型 + 历史纠正数据）」。
-- 规则表把前两项**真的做成列**：
--   * keywords       —— 关键词（换行分隔；命中方式 ANY / ALL）
--   * platform_codes —— 适用平台（逗号分隔；空 = 全部平台）
--   * message_types  —— 适用消息类型（逗号分隔；空 = 全部类型）
--   * priority_order —— **显式顺序**：数字小���先命中（并列时按 rule_code）
--   * target_label / suggested_priority / auto_reply_eligible —— 命中后给出的结论
--   * enabled        —— 停用规则不参与命中，但**不删**：删了历史命中依据就查不到了
--
-- ⚠️ 顺序必须**显式**：关键词规则的天然歧义是「一条消息同时命中多条规则」。
-- 不给顺序的话，「垃圾信息」和「退货退款」撞上时结果取决于插入顺序 ——
-- 那是不可复现的分类，运营改一条规则就会让历史结论集体漂移。
--
-- ============================================================ 三、标签词表
--
-- 蓝图点名的六类：售前咨询 / 物流查询 / 退货退款 / 投诉 / 平台合规 / 垃圾信息。
-- 本文件另加一档 **UNCLASSIFIED 未分类**：
--   * 它是**兜底**（没有规则命中时的结论），**不是**第七个业务类别 ——
--     所以规则的 target_label **不许**指向它（一条「未分类」的规则没有意义）。
--   * 未分类的消息**一律不可自动回复**（auto_reply_eligible = false）：
--     说不清是什么的消息就自动回，风险由买家承担。
--
-- ============================================================ 四、🔴 不许覆写人工设过的优先级
--
-- `crm_message.priority` 是**人工**设的（15.1.1.1 的批量动作 SET_PRIORITY）。
-- 本模块**只读**它，**一个字都不改**。
--
-- 分类结论里同时记三件事，如实表达分歧：
--   suggested_priority —— 规则给出的**建议**优先级
--   manual_priority    —— 分类时刻 crm_message.priority 的**人工值**快照
--   effective_priority —— **实际生效**的优先级 = **人工值**（人工值优先）
--   priority_conflict  —— 人工值 ≠ 建议值（true = 有分歧，页面必须把两个值都显示出来）
--
-- 用建议值盖掉人工值 = 客服设的「紧急」被悄悄改回「普通」，
-- 而页面上看不出发生过 —— 所以这里**只记录、不改写**。
--
-- ============================================================ 五、跨服务纪律
--
-- 上游 `crm_message` 在**本服务本库**（不是跨服务），所以直接查；
-- 下游 15.5.1 自动回复与 1.3 预警**都未交付** ⇒
-- 本模块**只判定**「是否可自动回复」，**不触发**任何自动回复，也**不写**任何预警。
-- 「是否可自动回复」是**本模块自己的结论字段**，不代表自动回复功能已存在。
--
-- ============================================================ 六、时点、��量与金额
--
--   * 时刻一律 **timestamptz** + OffsetDateTime，绑定写 `?::timestamptz`。
--     （用 Timestamp.valueOf(LocalDateTime) 会在东八区静默偏 −28800 秒。）
--   * 本表**没有金额**（分类结论不涉及钱）；关键词数量等计数用 integer / bigint。
--
-- ============================================================================
-- 零之一、角色
-- ============================================================================
-- 与 V99 / V220 / V293 同一口径：角色可能已由运维预置，因此只在缺失时创建。
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='crm_app') THEN CREATE ROLE crm_app; END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN CREATE ROLE starcon_app; END IF;
END $$;
GRANT USAGE ON SCHEMA public TO crm_app;

-- ============================================================================
-- 1. crm_message_classification_rule · 分类规则
-- ============================================================================
CREATE TABLE crm_message_classification_rule (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 规则编码：运营在工单/文档里引用它的唯一名字（同租户内唯一）。
  rule_code varchar(64) NOT NULL CHECK (btrim(rule_code) <> ''),
  rule_name varchar(200) NOT NULL CHECK (btrim(rule_name) <> ''),

  -- 🔴 显式顺序：数字小的先命中；并列时按 rule_code 升序（确定性）。
  priority_order integer NOT NULL CHECK (priority_order >= 0),

  -- 命中后给出的结论。target_label **不含** UNCLASSIFIED（未分类是兜底，不是类别）。
  target_label varchar(32) NOT NULL
    CHECK (target_label IN ('PRE_SALE','LOGISTICS','RETURN_REFUND','COMPLAINT','PLATFORM_COMPLIANCE','SPAM')),
  suggested_priority varchar(16) NOT NULL
    CHECK (suggested_priority IN ('LOW','NORMAL','HIGH','URGENT')),
  -- 是否可自动回复（**本模块的判定结果**，不代表 15.5.1 自动回复已交付）。
  auto_reply_eligible boolean NOT NULL DEFAULT false,

  -- 关键词：换行分隔。命中方式 ANY（任一命中）/ ALL（全部命中）。
  keywords text NOT NULL CHECK (btrim(keywords) <> ''),
  match_mode varchar(16) NOT NULL DEFAULT 'ANY' CHECK (match_mode IN ('ANY','ALL')),

  -- 适用平台：逗号分隔的平台码（形状同 crm_message.platform_code）；空 = 全部平台。
  platform_codes text NOT NULL DEFAULT ''
    CHECK (platform_codes = '' OR platform_codes ~ '^([A-Z][A-Z0-9_]*)(,[A-Z][A-Z0-9_]*)*$'),
  -- 适用消息类型：逗号分隔；空 = 全部类型。
  message_types text NOT NULL DEFAULT ''
    CHECK (message_types = ''
      OR message_types ~ '^(PLATFORM_INBOX|BUYER_MESSAGE|SYSTEM_NOTICE|COMPLIANCE)(,(PLATFORM_INBOX|BUYER_MESSAGE|SYSTEM_NOTICE|COMPLIANCE))*$'),

  -- 停用而不是删除：历史命中依据里带着规则编码，删了就查不到了。
  enabled boolean NOT NULL DEFAULT true,

  note text NOT NULL DEFAULT '',
  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, rule_code)
);

-- 命中顺序：按 (order, code) 走 —— 分类时的候选集就是这条索引。
CREATE INDEX crm_message_classification_rule_by_order
  ON crm_message_classification_rule(tenant_id, priority_order, rule_code);
-- 只看启用中的规则。
CREATE INDEX crm_message_classification_rule_by_enabled
  ON crm_message_classification_rule(tenant_id, enabled, priority_order);

-- ============================================================================
-- 2. crm_message_classification · 分类结论
-- ============================================================================
-- 分类号序列单独建、单独授权：GRANT ... ON ALL TABLES 不覆盖序列，
-- 漏授权会变成「第一条分类就报权限不足」（V293 已踩过这个坑）。
CREATE SEQUENCE crm_message_classification_no_seq START 1;

CREATE TABLE crm_message_classification (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- CLS-yyyyMMdd-000001（人可引用的短号；页面上比裸 UUID 好念）。
  classification_no varchar(32) NOT NULL DEFAULT '',

  -- 挂在哪条消息上（15.1.1.1 的 crm_message.id）。**逻辑引用**，
  -- 引用校验用触发器做，不建数据库外键（理由见文件头第五节）。
  message_id uuid NOT NULL,

  -- ===== 结论 =====
  -- 标签：六类业务类别 + UNCLASSIFIED 兜底。
  label varchar(32) NOT NULL
    CHECK (label IN ('PRE_SALE','LOGISTICS','RETURN_REFUND','COMPLAINT','PLATFORM_COMPLIANCE','SPAM','UNCLASSIFIED')),
  -- 规则给出的建议优先级。
  suggested_priority varchar(16) NOT NULL
    CHECK (suggested_priority IN ('LOW','NORMAL','HIGH','URGENT')),
  -- 分类时刻 crm_message.priority 的人工值快照（本模块**只读不改**它）。
  manual_priority varchar(16) NOT NULL
    CHECK (manual_priority IN ('LOW','NORMAL','HIGH','URGENT')),
  -- 实际生效优先级 = **人工值**（人工值优先；建议值只作为参考展示）。
  effective_priority varchar(16) NOT NULL
    CHECK (effective_priority IN ('LOW','NORMAL','HIGH','URGENT')),
  -- 人工值与建议值是否冲突（true 时页面必须把两个值都显示出来）。
  priority_conflict boolean NOT NULL DEFAULT false,

  -- 是否可自动回复（**本模块的判定结果字段**；15.5.1 自动回复未交付 ⇒ 不触发任何回复）。
  auto_reply_eligible boolean NOT NULL DEFAULT false,

  -- ===== 命中依据 =====
  -- 命中了哪条规则（未命中任何规则时为 NULL）。
  matched_rule_id uuid,
  matched_rule_code varchar(64) NOT NULL DEFAULT '',
  matched_rule_name varchar(200) NOT NULL DEFAULT '',
  -- 完整命中依据（含所有命中规则与各自命中的关键词）：
  --   {"winningRuleId":"...","winningRuleCode":"...","matches":[{"ruleId":"...","ruleCode":"...",
  --     "ruleName":"...","ruleOrder":10,"won":true,"keywords":[{"keyword":"refund","field":"BODY"}]}]}
  -- 用 jsonb 存整份依据（多规则多关键词），查询侧按 jsonb_array_elements 统计规则命中数。
  match_basis jsonb NOT NULL DEFAULT '{}'::jsonb,

  -- 分类器口径版本（当前 RULE_MATCH_V1）：规则改了要能看出「当时用的是哪一版口径」。
  classifier_version varchar(32) NOT NULL,
  classified_at timestamptz NOT NULL,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, message_id),
  UNIQUE (tenant_id, classification_no)
);

-- 按消息读结论。
CREATE INDEX crm_message_classification_by_message
  ON crm_message_classification(tenant_id, message_id);
-- 按标签筛（运营最常用的一句：「今天有多少条被当成垃圾信息」）。
CREATE INDEX crm_message_classification_by_label
  ON crm_message_classification(tenant_id, label, classified_at DESC);
-- 按建议优先级筛。
CREATE INDEX crm_message_classification_by_priority
  ON crm_message_classification(tenant_id, suggested_priority, classified_at DESC);
-- 人工值与建议值冲突的行（需要复核的）。
CREATE INDEX crm_message_classification_by_conflict
  ON crm_message_classification(tenant_id, priority_conflict, classified_at DESC);

-- ============================================================================
-- 3. crm_message_classification_feedback · 人工纠正（训练样本，只追加）
-- ============================================================================
CREATE TABLE crm_message_classification_feedback (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  message_id uuid NOT NULL,
  classification_id uuid NOT NULL,

  -- ===== 机器当时的预测（快照，不随之后的重新分类改变）=====
  predicted_label varchar(32) NOT NULL
    CHECK (predicted_label IN ('PRE_SALE','LOGISTICS','RETURN_REFUND','COMPLAINT','PLATFORM_COMPLIANCE','SPAM','UNCLASSIFIED')),
  predicted_priority varchar(16) NOT NULL
    CHECK (predicted_priority IN ('LOW','NORMAL','HIGH','URGENT')),
  predicted_rule_id uuid,
  predicted_rule_code varchar(64) NOT NULL DEFAULT '',

  -- ===== 人工纠正后的结论 =====
  corrected_label varchar(32) NOT NULL
    CHECK (corrected_label IN ('PRE_SALE','LOGISTICS','RETURN_REFUND','COMPLAINT','PLATFORM_COMPLIANCE','SPAM','UNCLASSIFIED')),
  corrected_priority varchar(16) NOT NULL
    CHECK (corrected_priority IN ('LOW','NORMAL','HIGH','URGENT')),
  corrected_auto_reply_eligible boolean NOT NULL,

  -- 为什么改：**必填**。没有理由的纠正没法复核，也没法当训练样本用。
  reason varchar(1000) NOT NULL CHECK (btrim(reason) <> ''),

  corrected_by uuid NOT NULL,
  corrected_at timestamptz NOT NULL,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id)
);

-- 按消息读纠正历史。
CREATE INDEX crm_message_classification_feedback_by_message
  ON crm_message_classification_feedback(tenant_id, message_id, corrected_at DESC);
-- 按被纠正的规则读（规则命中统计里「这条规则错了多少次」用得上）。
CREATE INDEX crm_message_classification_feedback_by_rule
  ON crm_message_classification_feedback(tenant_id, predicted_rule_id, corrected_at DESC);
-- 按纠正时间读（训练样本列表按时间倒序）。
CREATE INDEX crm_message_classification_feedback_by_time
  ON crm_message_classification_feedback(tenant_id, corrected_at DESC);

-- ============================================================================
-- 4. 触发器
-- ============================================================================
-- ⚠️ 为什么用**同库回表**而不是**数据库外键**：
--   本仓同服务的既有迁移（V220 / V293）一律用触发器做引用校验 ——
--   数据库外键会打破既有测试的 TRUNCATE（PG：cannot truncate a table referenced in
--   a foreign key constraint）。本文件**不给任何既有表建外键**，
--   自己新建的三张表之间也**一律用触发器**。

-- 4.1 规则编码建后不可改：命中依据里存的是规则编码，改了它历史依据就对不上。
CREATE FUNCTION guard_crm_message_classification_rule_code() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.rule_code IS DISTINCT FROM OLD.rule_code THEN
    RAISE EXCEPTION '规则编码建后不可改：历史命中依据里存的是编码，改了就对不上';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_message_classification_rule_code_trg
  BEFORE UPDATE ON crm_message_classification_rule
  FOR EACH ROW EXECUTE FUNCTION guard_crm_message_classification_rule_code();

-- 4.2 分类结论必须挂在同一租户下真实存在的消息上。
CREATE FUNCTION guard_crm_message_classification_message_target() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
      SELECT 1 FROM crm_message m
      WHERE m.id = NEW.message_id AND m.tenant_id = NEW.tenant_id) THEN
    RAISE EXCEPTION '分类结论必须挂在同一租户下真实存在的消息上（message_id=%）', NEW.message_id;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_message_classification_message_target_trg
  BEFORE INSERT ON crm_message_classification
  FOR EACH ROW EXECUTE FUNCTION guard_crm_message_classification_message_target();

-- 4.3 纠正必须挂在同一租户下真实存在的分类结论上，且消息一致。
CREATE FUNCTION guard_crm_message_classification_feedback_target() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
      SELECT 1 FROM crm_message_classification c
      WHERE c.id = NEW.classification_id
        AND c.tenant_id = NEW.tenant_id
        AND c.message_id = NEW.message_id) THEN
    RAISE EXCEPTION '纠正必须挂在同一租户下真实存在的分类结论上（classification_id=% / message_id=%）',
      NEW.classification_id, NEW.message_id;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_message_classification_feedback_target_trg
  BEFORE INSERT ON crm_message_classification_feedback
  FOR EACH ROW EXECUTE FUNCTION guard_crm_message_classification_feedback_target();

-- 4.4 🔴 训练样本只追加：UPDATE / DELETE 一律拒绝。
--     改得掉的训练样本 = 随时可以改漂亮的准确率 —— 那这条指标就没有意义了。
CREATE FUNCTION guard_crm_message_classification_feedback_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '人工纠正记录只追加，不改不删：训练样本被改掉，准确率指标就随时可以被改漂亮';
END $$;

CREATE TRIGGER crm_message_classification_feedback_append_only_trg
  BEFORE UPDATE OR DELETE ON crm_message_classification_feedback
  FOR EACH ROW EXECUTE FUNCTION guard_crm_message_classification_feedback_append_only();

-- ============================================================================
-- 5. RLS（ENABLE + FORCE）与授权
-- ============================================================================
-- 与 V99 / V220 / V293 同一口径：策略名固定 isolation，USING 与 WITH CHECK 两侧同表达式。
-- 表达式里的 nullif 不是洁癖：事务局部的 set_config 提交之后，PostgreSQL 会把自定义 GUC
-- **复位成空串**而不是「回到未设置」，直接 cast 会报 uuid 语法错，
-- 而 nullif 把它归一成 NULL —— 条件不成立 → 一行都看不到。
-- 也就是说「忘记绑定租户」当场失败，而不是静默返回全表。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['crm_message_classification_rule','crm_message_classification','crm_message_classification_feedback'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON crm_message_classification_rule, crm_message_classification,
  crm_message_classification_feedback TO crm_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON crm_message_classification_rule, crm_message_classification,
  crm_message_classification_feedback TO starcon_app;
-- 🔴 序列单独授权：GRANT ... ON ALL TABLES **不覆盖序列**，漏授权会变成
-- 「第一条分类就报权限不足」。
GRANT USAGE, SELECT ON SEQUENCE crm_message_classification_no_seq TO crm_app;
GRANT USAGE, SELECT ON SEQUENCE crm_message_classification_no_seq TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM crm_app;

-- ============================================================================
-- 6. 留给下游 / 上游的口子（不是本模块的缺口）
-- ============================================================================
--   * **15.5.1 自动回复**：未交付。本模块只**判定** `auto_reply_eligible`
--     （是否可自动回复），**不触发**任何自动回复，也**不写**任何回复记录。
--   * **1.3 预警**（工作台预警）：未交付。本模块**不写**任何预警。
--   * **15.1.1.1 消息收件箱**（上游）：同库同服务，本模块**只读** `crm_message`，
--     **不改**它的 `priority`（人工值优先，见文件头第四节）。
--   * **人工纠正回流**：纠正行是**训练样本**（本服务内可查、可统计），
--     但**没有**训练任务调度器 —— 那是模型训练侧的事，本模块不编。
