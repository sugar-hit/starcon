-- 15.1.1.2 消息发送：回复买家消息 —— 模板调用、手工编辑、附件、发送记录、失败重试、批量相同回复。
--
-- 蓝图 15 卷 15.1.1.2 的原话：
--   「职责：回复买家消息：调用模板、手工编辑、附件支持、发送记录、失败重试；支持批量相同回复（同类问题）。
--     输入：回复内容（模板或手工）；目标消息；附件；idempotency_key。
--     输出：发送记录 + 平台回执 + 失败原因。
--     关系：上游 = 15.1.1.1、15.4.2 知识库；下游 = 平台消息接口。
--     **发送失败要保留原消息状态**，不能让客服以为已回复。」
--
-- ============================================================ 零、与既有模块的边界
--
-- 本文件是 **crm 数据库的第五个迁移**（前四个：V99 知识库 / V220 索评 / V228 索评规则 /
-- V292 邮件收发 / V293 消息收件箱）。本模块与它们**不共用任何一张表**：
--   * V99  知识库管**内容**（这个格子此刻该给买家答哪一条）；
--   * V292 邮件管**邮件**（邮件有它自己的发送记录 crm_buyer_email_send_record）；
--   * V293 收件箱管**收到的消息**（买家/平台说了什么）；
--   * 本模块管**发出去的那一条**（回复内容 / 投递过程 / 平台回执 / 失败原因）。
--
-- ⚠️ 刻意**不**复用 `crm_buyer_email_template` / `crm_buyer_email_send_record`：
--   那是**邮件**的话术与邮件的发送记录。站内信与邮件在部分平台上是同一会话，
--   但两者的投递通道、平台回执格式与合规口径不同；把邮件的发送记录拿来当站内信的
--   发送记录，会让「这封邮件发出去了」与「这条站内信发出去了」在数据上混成一件
--   说不清的事。本模块另起 `crm_message_send_*` 系列表名。
--
-- ============================================================ 一、🔴 加粗句的落地：发送失败要保留原消息状态
--
-- 「**发送失败要保留原消息状态**，不能让客服以为已回复」在本文件里落成
-- **「已回复」这个事实只由发送记录推导，不在源消息上开一列**：
--
--   一条消息「是否已回复」 ⇔  存在一条 status='SENT' 的 crm_message_send 指向它。
--
-- 于是失败的那一次发送**根本没有能力**把源消息改成「已回复」——
-- 没有那一列可以被改，也就没有「忘了改回来」这种事故。
-- 相比之下，若在 crm_message 上加 replied 标记列，那么每一次发送成功/失败
-- 都必须精确地改/不改这一列，而「忘了不改」正是客服看到「已回复」而实际没发出去
-- 的**唯一**成因。所以本模块**刻意不给既有表加列**（也避免跨车道 ALTER 既有表）：
-- 已回复状态由发送记录推导，失败时它天然不变。
--
-- 目标消息与其它表的引用校验**一律用触发器**（见第四节），**零外键**：
-- 外键会打破同服务既有测试的 TRUNCATE（PG：cannot truncate a table referenced in
-- a foreign key constraint）—— 本仓既有约定，本文件零外键。
--
-- ============================================================ 二、平台投递：未接入，按未接入处理
--
-- 蓝图输出句里的「平台回执」依赖 **平台侧买家站内信发送接口**，而**这条下游在本仓
-- 不存在**：channel 服务的 vendor 包下只有 13 个 OAuth 适配器，**没有**买家站内信
-- 适配器（同服务的 PlatformMessagePort 已经把「站内信**同步**」标成未接入，
-- 发送侧同样没有通道）。
--
-- ⇒ 本文件的 transport 词表留了两档：
--   MANUAL_HANDOFF 人工交接（**当前唯一可用**）—— 运营在平台侧实际发出后，
--                   回本服务登记结果（已发出 / 失败原因 / 平台回执）。
--   PLATFORM_API    平台侧发送接口（**未接入**，服务端拒绝写这一档）。
--
-- 一个「返回成功回执」的假通道会让「消息已经发给买家了」在数据上看起来发生过，
-- 而现场一条真消息都没发出去 —— 那比明确报「未接入」糟得多。
--
-- ============================================================ 三、四张表
--
--   crm_message_send_template   · 站内信回复模板（调用模板 / 手工编辑成模板）
--   crm_message_send            · 发送记录（= 蓝图输出句里的「发送记录 + 平台回执 + 失败原因」）
--   crm_message_send_attachment · 发送附件（附件支持）
--   crm_message_send_attempt    · 发送尝试（失败重试的每一次尝试都留痕）
--
-- 为什么模板独立成表而不是一列 text：模板是**跨消息复用**的东西（同类问题批量回复），
-- 而且必须**版本化可查**（蓝图关键约束：消息模板与合规话术需版本化，平台政策变化时能定位）。
-- 本表的 template_code 在租户内唯一，历史发送记录**快照**当时的 template_code 与正文，
-- 所以模板改掉之后，历史「用的哪套话术」仍可读。
--
-- 为什么尝试独立成表：蓝图点名「失败重试」。若只在发送记录上放一个 attempt_count，
-- 那么「第 1 次为什么失败、第 2 次为什么失败」就没有落点 —— 而失败原因恰恰是
-- 蓝图输出句里点名要输出的东西。每一次尝试一行，失败原因与平台回执都在那一行里。
--
-- ⚠️ 知识库（15.4.2，V99）的引用只是**逻辑引用**（knowledge_entry_id /
--   knowledge_version_id），不建外键：本服务与知识库是**同一张库**同一服务，
--   但知识库表的写入所有者是 15.4.2 车道，跨表引用校验用触发器（同上）。
--   引用知识版本时，本服务会向知识库登记一条**引用记录**（consumer='MANUAL_REPLY'，
--   与知识库既有的词表一致），使「买家当时收到了哪一版」可查。
--
-- ============================================================ 四、时点、数量与金额
--
--   * 时刻一律 **timestamptz** + OffsetDateTime，绑定写 `?::timestamptz`。
--     （用 Timestamp.valueOf(LocalDateTime) 会在东八区静默偏 −28800 秒，本仓反复踩过。）
--   * 数量 / 字节数一律 **numeric(19,6)** + BigDecimal，**禁浮点**
--     （与 V292 / V293 的 size_bytes 同一形状）。
--   * 本模块**不涉及金额**：订单金额来自 trade 侧 numeric，发送记录只存订单标识。
--
-- ============================================================ 五、发布后不可变的强制位置
--
--   1. NOT NULL + CHECK：idempotency_key 不得为空串（幂等键必须有）；
--      买家标识 / 订单标识不得为空串（沿用 V293 的加粗句口径）；
--      FAILED 必须带失败原因；SENT 必须带发出时刻。
--   2. UNIQUE (tenant_id, idempotency_key)：同一条幂等键重放**只会有一条**记录。
--   3. 触发器：
--      guard_crm_message_send_identity        （BEFORE UPDATE：身份与内容列建后不可改）
--      guard_crm_message_send_target          （BEFORE INSERT：目标消息必须存在且是收到的）
--      guard_crm_message_send_template_target （BEFORE INSERT：引用的模板必须存在）
--      guard_crm_message_send_knowledge_target（BEFORE INSERT：引用的知识版本必须存在）
--      guard_crm_message_send_attachment_target（BEFORE INSERT：附件必须挂在存在的发送记录上）
--      guard_crm_message_send_attempt_target  （BEFORE INSERT：尝试必须挂在存在的发送记录上）
--      sync_crm_message_send_attachment_count  （AFTER INSERT/DELETE，维护附件计数）
--
-- ============================================================================
-- 零之一、角色
-- ============================================================================
-- 与 V99 / V220 / V293 同一口径：角色可能已由运维预置，因此只在缺失时创建，保证迁移可重复执行。
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='crm_app') THEN CREATE ROLE crm_app; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN CREATE ROLE starcon_app; END IF;
END $$;
GRANT USAGE ON SCHEMA public TO crm_app;

-- ============================================================================
-- 1. crm_message_send_template · 站内信回复模板
-- ============================================================================
CREATE TABLE crm_message_send_template (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 业务键（同租户内唯一，便于运营按编码引用）。
  template_code varchar(64) NOT NULL CHECK (btrim(template_code) <> ''),
  name varchar(200) NOT NULL CHECK (btrim(name) <> ''),
  -- BCP-47 简写（如 zh-CN / en-US），与 V99 知识库 / V292 邮件模板的 locale CHECK 同一形状。
  locale varchar(16) NOT NULL DEFAULT 'en-US' CHECK (locale ~ '^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$'),

  subject varchar(500) NOT NULL DEFAULT '',
  body text NOT NULL DEFAULT '',

  -- 适用店铺 / 平台：NULL = 本租户全部。
  shop_id uuid,
  platform_code varchar(32) CHECK (platform_code IS NULL OR platform_code ~ '^[A-Z][A-Z0-9_]*$'),

  -- ACTIVE 可用 · DISABLED 停用（停用后不能新建发送，但历史记录仍可读）。
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),

  created_by uuid,
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, template_code)
);

-- 模板下拉框的真数据源（按店铺 / 语言 / 状态筛）。
CREATE INDEX crm_message_send_template_by_shop
  ON crm_message_send_template(tenant_id, shop_id, status, template_code);
CREATE INDEX crm_message_send_template_by_locale
  ON crm_message_send_template(tenant_id, locale, status, template_code);

-- ============================================================================
-- 2. crm_message_send · 发送记录
-- ============================================================================
-- 发送号序列单独建、单独授权：GRANT ... ON ALL TABLES 不覆盖序列，
-- 漏授权会变成「第一条发送记录就报权限不足」。
CREATE SEQUENCE crm_message_send_no_seq START 1;

CREATE TABLE crm_message_send (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- MSGS-yyyyMMdd-000001
  send_no varchar(32) NOT NULL DEFAULT '',

  -- ===== 目标消息（回复的是哪一条）=====
  -- **逻辑引用**，指向 15.1.1.1 的 crm_message.id；引用校验用触发器，不建外键。
  target_message_id uuid NOT NULL,
  -- 目标消息当时的快照（源消息不可变，这里也就不再回源）。
  shop_id uuid NOT NULL,
  shop_name varchar(200) NOT NULL DEFAULT '',
  platform_code varchar(32) NOT NULL CHECK (platform_code ~ '^[A-Z][A-Z0-9_]*$'),
  buyer_reference varchar(128) NOT NULL CHECK (btrim(buyer_reference) <> ''),
  order_reference varchar(64) NOT NULL CHECK (btrim(order_reference) <> ''),

  -- ===== 模板与知识来源 =====
  -- 引用的站内信模板（逻辑引用）。历史记录快照 template_code，模板改掉后仍可读。
  template_id uuid,
  template_code varchar(64) NOT NULL DEFAULT '',
  -- 引用的知识库条目与版本（15.4.2 上游，逻辑引用）。
  knowledge_entry_id uuid,
  knowledge_version_id uuid,
  knowledge_version_no integer NOT NULL DEFAULT 0 CHECK (knowledge_version_no >= 0),

  -- ===== 发送内容（渲染后的快照）=====
  subject varchar(500) NOT NULL DEFAULT '',
  body text NOT NULL DEFAULT '',
  -- 正文来源：MANUAL 纯手工 · TEMPLATE 直接用模板 · TEMPLATE_EDITED 模板起手工改过 ·
  -- KNOWLEDGE 取自知识库条目 · KNOWLEDGE_EDITED 知识起稿后手工改过。
  -- 留这一列是为了事后能回答「这句到底是模板原话还是人改过的」。
  body_source varchar(16) NOT NULL DEFAULT 'MANUAL'
    CHECK (body_source IN ('MANUAL','TEMPLATE','TEMPLATE_EDITED','KNOWLEDGE','KNOWLEDGE_EDITED')),

  -- ===== 触达通道 =====
  -- MANUAL_HANDOFF 人工交接（**当前唯一可用**）；
  -- PLATFORM_API    平台侧发送接口（**未接入**，服务端拒绝写这一档）。
  transport varchar(24) NOT NULL CHECK (transport IN ('PLATFORM_API','MANUAL_HANDOFF')),
  -- QUEUED 已登记待发 · SENT 已发出 · FAILED 失败（必须带原因）· CANCELLED 已作废。
  status varchar(16) NOT NULL DEFAULT 'QUEUED'
    CHECK (status IN ('QUEUED','SENT','FAILED','CANCELLED')),
  -- 失败原因：FAILED 时**必填**（只写「失败」而不写为什么，事后无法判断该不该重试）。
  failure_reason text NOT NULL DEFAULT '',
  CHECK (status <> 'FAILED' OR btrim(failure_reason) <> ''),

  -- 平台回执与平台侧消息号（人工交接时由运营登记平台侧拿到的东西）。
  platform_receipt text NOT NULL DEFAULT '',
  platform_message_id varchar(128) NOT NULL DEFAULT '',

  -- 附件计数：与 crm_message_send_attachment 一致，由触发器维护。
  attachment_count integer NOT NULL DEFAULT 0 CHECK (attachment_count >= 0),

  -- ===== 重试 =====
  -- 已经尝试过几次（含本次登记）；重试会让它 +1。
  attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
  last_attempt_at timestamptz,
  -- 已发出时刻：SENT 时**必填**。
  sent_at timestamptz,
  CHECK (status <> 'SENT' OR sent_at IS NOT NULL),

  -- ===== 幂等 =====
  -- 幂等键：同租户内唯一。重放同一条键**不会**产生第二条发送记录。
  idempotency_key varchar(200) NOT NULL CHECK (btrim(idempotency_key) <> ''),

  -- 批量相同回复的批次号：同一次批量里的所有发送记录共享它。
  batch_id uuid,

  created_by uuid,
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, send_no),
  UNIQUE (tenant_id, idempotency_key)
);

-- 按目标消息读发送记录（客服最常用的一句：「这条消息我回过没有」）。
CREATE INDEX crm_message_send_by_target
  ON crm_message_send(tenant_id, target_message_id, created_at DESC);
-- 按状态读（失败重试队列）。
CREATE INDEX crm_message_send_by_status
  ON crm_message_send(tenant_id, status, created_at DESC);
-- 按店铺读。
CREATE INDEX crm_message_send_by_shop
  ON crm_message_send(tenant_id, shop_id, created_at DESC);
-- 按批次读（批量相同回复的逐条结论）。
CREATE INDEX crm_message_send_by_batch
  ON crm_message_send(tenant_id, batch_id, created_at);
-- 按幂等键点查（重放判定）。
CREATE UNIQUE INDEX crm_message_send_one_per_idempotency
  ON crm_message_send(tenant_id, idempotency_key);

-- ============================================================================
-- 3. crm_message_send_attachment · 发送附件
-- ============================================================================
CREATE TABLE crm_message_send_attachment (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 挂在哪条发送记录上。**用触发器做引用校验**，不建数据库外键。
  send_id uuid NOT NULL,

  file_name varchar(300) NOT NULL CHECK (btrim(file_name) <> ''),
  -- 附件链接（对象存储地址等）。空串表示「只登记了文件名，文件还没落地」。
  file_url varchar(1000) NOT NULL DEFAULT '',
  content_type varchar(120) NOT NULL DEFAULT '',
  -- 字节数：0 表示未知（不是「0 字节的文件」—— 两者在这里分不开）。
  size_bytes numeric(19,6) NOT NULL DEFAULT 0 CHECK (size_bytes >= 0),

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id)
);

CREATE INDEX crm_message_send_attachment_by_send
  ON crm_message_send_attachment(tenant_id, send_id, created_at);

-- ============================================================================
-- 4. crm_message_send_attempt · 发送尝试（失败重试留痕）
-- ============================================================================
CREATE TABLE crm_message_send_attempt (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 挂在哪条发送记录上。**用触发器做引用校验**，不建数据库外键。
  send_id uuid NOT NULL,

  -- 第几次尝试（从 1 开始，同一条发送记录内不重复）。
  attempt_no integer NOT NULL CHECK (attempt_no >= 1),
  -- QUEUED 已登记待发 · SENT 已发出 · FAILED 失败（必须带原因）。
  status varchar(16) NOT NULL DEFAULT 'QUEUED'
    CHECK (status IN ('QUEUED','SENT','FAILED')),
  failure_reason text NOT NULL DEFAULT '',
  CHECK (status <> 'FAILED' OR btrim(failure_reason) <> ''),
  platform_receipt text NOT NULL DEFAULT '',
  platform_message_id varchar(128) NOT NULL DEFAULT '',
  attempted_at timestamptz NOT NULL DEFAULT now(),
  operator_note text NOT NULL DEFAULT '',

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, send_id, attempt_no)
);

CREATE INDEX crm_message_send_attempt_by_send
  ON crm_message_send_attempt(tenant_id, send_id, attempt_no);

-- ============================================================================
-- 5. 触发器
-- ============================================================================
-- ⚠️ 为什么用**同库回表**而不是**数据库外键**：
--   外键会打破同服务既有测试的 TRUNCATE（PG：cannot truncate a table referenced in a
--   foreign key constraint）。本文件**不给任何既有表建外键**，自己新建的四张表之间
--   也一律用触发器做引用校验。

-- 5.1 身份与内容列建后不可改（目标消息 / 店铺 / 买家 / 订单 / 模板 / 知识来源 /
--     正文 / 通道 / 幂等键 / 批次）。可改的只有**处理态**列（状态 / 失败原因 /
--     平台回执 / 尝试次数 / 尝试时刻），那正是客服每次重试要动的东西。
CREATE FUNCTION guard_crm_message_send_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.target_message_id IS DISTINCT FROM OLD.target_message_id
     OR NEW.shop_id IS DISTINCT FROM OLD.shop_id
     OR NEW.platform_code IS DISTINCT FROM OLD.platform_code
     OR NEW.buyer_reference IS DISTINCT FROM OLD.buyer_reference
     OR NEW.order_reference IS DISTINCT FROM OLD.order_reference
     OR NEW.template_id IS DISTINCT FROM OLD.template_id
     OR NEW.template_code IS DISTINCT FROM OLD.template_code
     OR NEW.knowledge_entry_id IS DISTINCT FROM OLD.knowledge_entry_id
     OR NEW.knowledge_version_id IS DISTINCT FROM OLD.knowledge_version_id
     OR NEW.subject IS DISTINCT FROM OLD.subject
     OR NEW.body IS DISTINCT FROM OLD.body
     OR NEW.body_source IS DISTINCT FROM OLD.body_source
     OR NEW.transport IS DISTINCT FROM OLD.transport
     OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
     OR NEW.batch_id IS DISTINCT FROM OLD.batch_id
     OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION '发送记录的身份与内容列（目标消息/店铺/买家/订单/模板/知识来源/正文/通道/幂等键/批次）建后不可改：改了就出现「同一次发送有两套话术」的历史';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_message_send_identity_trg
  BEFORE UPDATE ON crm_message_send
  FOR EACH ROW EXECUTE FUNCTION guard_crm_message_send_identity();

-- 5.2 目标消息必须存在、属于同一租户，且是**收到的**消息。
--     只能回复收到的消息：回复我们自己发出去的那条没有意义。
CREATE FUNCTION guard_crm_message_send_target() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  target_direction varchar(16);
BEGIN
  SELECT direction INTO target_direction
    FROM crm_message m
   WHERE m.id = NEW.target_message_id AND m.tenant_id = NEW.tenant_id;
  IF target_direction IS NULL THEN
    RAISE EXCEPTION '发送目标必须是同一租户下真实存在的消息（message_id=%）', NEW.target_message_id;
  END IF;
  IF target_direction <> 'INBOUND' THEN
    RAISE EXCEPTION '只能回复收到的消息（direction=%）：回复自己发出去的那条没有意义', target_direction;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_message_send_target_trg
  BEFORE INSERT ON crm_message_send
  FOR EACH ROW EXECUTE FUNCTION guard_crm_message_send_target();

-- 5.3 引用的模板必须存在（同一租户）。
CREATE FUNCTION guard_crm_message_send_template_target() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.template_id IS NOT NULL AND NOT EXISTS (
      SELECT 1 FROM crm_message_send_template t
      WHERE t.id = NEW.template_id AND t.tenant_id = NEW.tenant_id) THEN
    RAISE EXCEPTION '引用的回复模板必须存在（template_id=%）', NEW.template_id;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_message_send_template_target_trg
  BEFORE INSERT ON crm_message_send
  FOR EACH ROW EXECUTE FUNCTION guard_crm_message_send_template_target();

-- 5.4 引用的知识版本必须存在（同一租户，15.4.2 知识库）。
CREATE FUNCTION guard_crm_message_send_knowledge_target() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.knowledge_version_id IS NOT NULL AND NOT EXISTS (
      SELECT 1 FROM crm_knowledge_version v
      WHERE v.id = NEW.knowledge_version_id AND v.tenant_id = NEW.tenant_id) THEN
    RAISE EXCEPTION '引用的知识版本必须存在（knowledge_version_id=%）', NEW.knowledge_version_id;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_message_send_knowledge_target_trg
  BEFORE INSERT ON crm_message_send
  FOR EACH ROW EXECUTE FUNCTION guard_crm_message_send_knowledge_target();

-- 5.5 附件必须挂在同一租户下真实存在的发送记录上。
CREATE FUNCTION guard_crm_message_send_attachment_target() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
      SELECT 1 FROM crm_message_send s
      WHERE s.id = NEW.send_id AND s.tenant_id = NEW.tenant_id) THEN
    RAISE EXCEPTION '附件必须挂在同一租户下真实存在的发送记录上（send_id=%）', NEW.send_id;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_message_send_attachment_target_trg
  BEFORE INSERT ON crm_message_send_attachment
  FOR EACH ROW EXECUTE FUNCTION guard_crm_message_send_attachment_target();

-- 5.6 尝试必须挂在同一租户下真实存在的发送记录上。
CREATE FUNCTION guard_crm_message_send_attempt_target() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
      SELECT 1 FROM crm_message_send s
      WHERE s.id = NEW.send_id AND s.tenant_id = NEW.tenant_id) THEN
    RAISE EXCEPTION '发送尝试必须挂在同一租户下真实存在的发送记录上（send_id=%）', NEW.send_id;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_message_send_attempt_target_trg
  BEFORE INSERT ON crm_message_send_attempt
  FOR EACH ROW EXECUTE FUNCTION guard_crm_message_send_attempt_target();

-- 5.7 附件计数由 DB 维护（发送记录行上的冗余计数只此一处来源）。
CREATE FUNCTION sync_crm_message_send_attachment_count() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    UPDATE crm_message_send
       SET attachment_count = attachment_count + 1, updated_at = now()
     WHERE id = NEW.send_id AND tenant_id = NEW.tenant_id;
    RETURN NEW;
  END IF;
  UPDATE crm_message_send
     SET attachment_count = GREATEST(attachment_count - 1, 0), updated_at = now()
   WHERE id = OLD.send_id AND tenant_id = OLD.tenant_id;
  RETURN OLD;
END $$;

CREATE TRIGGER crm_message_send_attachment_count_trg
  AFTER INSERT OR DELETE ON crm_message_send_attachment
  FOR EACH ROW EXECUTE FUNCTION sync_crm_message_send_attachment_count();

-- ============================================================================
-- 6. RLS（ENABLE + FORCE）与授权
-- ============================================================================
-- 与 V99 / V220 / V293 同一口径：策略名固定 isolation，USING 与 WITH CHECK 两侧同表达式。
-- 表达式里的 nullif 不是洁癖：事务局部的 set_config 提交之后，PostgreSQL 会把自定义 GUC
-- **复位成空串**而不是「回到未设置」，直接 cast 会报 uuid 语法错，
-- 而 nullif 把它归一成 NULL —— 条件不成立 → 一行都看不到。
-- 也就是说「忘记绑定租户」当场失败，而不是静默返回全表。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['crm_message_send_template','crm_message_send',
                             'crm_message_send_attachment','crm_message_send_attempt'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON crm_message_send_template, crm_message_send,
  crm_message_send_attachment, crm_message_send_attempt TO crm_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON crm_message_send_template, crm_message_send,
  crm_message_send_attachment, crm_message_send_attempt TO starcon_app;
-- 🔴 序列单独授权：GRANT ... ON ALL TABLES **不覆盖序列**，漏授权会变成
-- 「第一条发送记录就报权限不足」（10.3.2.2 踩过这个坑，这里不重复）。
GRANT USAGE, SELECT ON SEQUENCE crm_message_send_no_seq TO crm_app;
GRANT USAGE, SELECT ON SEQUENCE crm_message_send_no_seq TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM crm_app;

-- ============================================================================
-- 7. 留给上游 / 下游的口子（不是本模块的缺口）
-- ============================================================================
--   * **平台买家站内信发送适配器**（channel/vendor 下**只有 OAuth 适配器**，没有站内信）：
--     本模块的 transport 只允许 MANUAL_HANDOFF（人工交接）。
--     PLATFORM_API 词表**先钉死**，将来适配器交付时不需要改这里的语义。
--   * **15.1.1.3 消息分类**：未交付。本模块不依赖分类结果做自动回复。
--   * **15.5.1 自动回复**：未交付。本模块的发送全部由**人工**发起。
--   * **15.1.2.1 邮件收发**：已交付但**刻意不共用表** —— 邮件有它自己的发送记录。
--   * **11 售后 / 15.4 工单**：未交付。本模块不写任何下游单据、不编下游单号。
