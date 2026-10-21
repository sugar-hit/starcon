-- 15.1.2.1 邮件收发：管理买家邮件（Amazon Buyer-Seller Messaging、eBay 邮件等）：
-- 收发、附件、模板、与站内信的双向同步或标记为同源。
--
-- 蓝图 15 卷 15.1.2.1 的原话：
--   「职责：管理买家邮件（Amazon Buyer-Seller Messaging、eBay 邮件等）：收发、附件、模板、
--     与站内信的双向同步或标记为同源。
--     输入：邮件同步；发送请求。
--     输出：邮件列表与详情 + 发送记录。
--     关系：上游 = 2 同步；下游 = 15.1.1.2 发送。
--     **部分平台要求邮件与站内信在同一会话处理**，分开展示会导致重复回复买家。」
--
-- ============================================================ 零、本文件与既有模块的边界
--
-- 本文件是 **crm 数据库的第三个迁移**（前两个是 15.4.2.1 知识库 V99 / 15.2.2.1 索评 V220
-- / 15.2.2.2 索评规则 V228）。本模块与卷 15 的其它已交付模块**不共用任何一张表**：
--   * 15.4.2.1 知识库回答「这个格子此刻该给买家答哪一条」（按店铺 / 类目 / 语言组织）；
--   * 15.2.2.1 索评回答「这一位买家、这一张已签收的单子，要不要向他发起一次索评」；
--   * 15.2.2.2 索评规则回答「按店铺 / 物流方式该套哪套策略与话术」；
--   * **本模块回答「这位买家在平台上发来的这封邮件是什么，我们回过没有」**。
-- 四者一个管内容、一个管触达、一个管策略、一个管邮件线程，事实表与写入所有者都不同。
--
-- ============================================================ 一、🔴 加粗句「分开展示会导致重复回复买家」
--
-- 这条加粗句在本文件里落成**两件具体的东西**，而不是一句提示：
--
--   1. **会话键 conversation_key**：一封邮件所在的会话的自然键，
--      形如 `<shopId>|<buyerRef 小写>|<salesOrderNo 小写>`（无订单号时第三段写 `-`）。
--      同一买家、同一订单、同一店铺的邮件（无论收还是发）**必然**落在同一个会话键下。
--      客服在一个会话视图里就能看到「这封问的是这个单子，我已经回过了没有」。
--
--   2. **同源标记靠会话键相等，不靠外键**：
--      同批并行的 15.1.1.1「消息收件箱」正在开发，它的表**不在本车道的基线里**。
--      ⇒ 本表**不建任何指向它的外键**（`REFERENCES crm_message_inbox(...)` 在本基线上
--      会直接迁移失败，而且那正是 AGENTS.md 禁止的跨批次 FK 形状）。
--      站内信侧由 15.1.1.1 用**同一个会话键公式**算自己的键，两边靠 `conversation_key`
--      相等来对齐「同源」。**对齐是下一次迭代的事**，不影响本模块独立落地。
--      本表只留一个 **nullable 的 `source_message_id uuid`**（软引用，**不加外键**），
--      供将来回填站内信侧的真实主键。
--
-- ⚠️ 本文件**一律不建数据库外键**（含本文件自己新建的表之间）：
--   外键会打断同服务既有测试的 TRUNCATE（PG：cannot truncate a table referenced in a
--   foreign key constraint；batch-37 有车道踩过这个坑）。
--   同一手法：跨表引用用**触发器回表校验**（V219 / V220 / V228 同一纪律）。
--
-- ============================================================ 二、跨服务纪律（零跨库 SQL / FK / 视图）
--
-- 上游 2 同步与店铺目录在 **channel** 服务（端口 8083）：
--   * 店铺目录 —— `GET /channel/shops`（2.3.1.1）；
--   * 平台买家邮件 —— **未接入**：channel/vendor 下只有 13 个 OAuth 适配器，
--     **没有任何买家邮件适配器**。⇒ 本模块新增端口 `PlatformEmailPort` +
--     当前实现 `UnavailablePlatformEmailPort`（逐平台答「未接入」，**不返回任何邮件、
--     不编造平台接口、不返回硬编码列表**）；未接入时端点明确 **409** 且**不写任何数据**。
-- 本服务**一条 SQL 都不碰对方的库**，只经**授权 HTTP API** 读，本库只存逻辑引用 + 快照。
--
-- 下游 15.1.1.2「消息发送」**未交付** ⇒ 本模块**不实现**「从收件箱发送消息」这个动作。
-- 本模块**自己的**发送记录是它的职责（蓝图输出句：「邮件列表与详情 + 发送记录」），
-- 平台回执与失败原因必须留痕。
--
-- 🔴 与 15.1.1.2 蓝图里同一条红线在邮件侧同样适用：
--   **发送失败要保留原邮件状态**，不能让客服以为已回复 ——
--   所以 status='FAILED' 的发送记录**不改**邮件状态（邮件仍是 RECEIVED），
--   失败原因写在发送记录里；只有 status='SENT' 才把邮件置为 REPLIED。
--
-- ============================================================ 三、四张表
--
--   crm_buyer_email            · 邮件本体（收 / 发两个方向都在这里）
--   crm_buyer_email_attachment · 附件**元数据**（不含文件字节）
--   crm_buyer_email_template   · 本模块自己的邮件模板（CRUD）
--   crm_buyer_email_send_record· 发送记录（= 蓝图输出句里的「发送记录」）
--
-- 为什么「邮件」与「发送记录」分两张表：
--   一封**收到的**邮件可能一次都没回过，也可能回过三次（失败两次）。
--   合一就会让「这封邮件是什么」与「我们回过没有」两个问题互相污染 ——
--   而加粗句要的正是「有没有重复回复」这件事可靠。
--
-- 为什么收 / 发两个方向都在 `crm_buyer_email` 里：
--   加粗句要求「邮件与站内信在**同一会话**处理」。会话视图里如果只有收到的邮件、
--   没有发出的邮件，客服仍然会以为「我还没回过」⇒ 照样重复回复。
--   所以**每一条发送记录**都同时落一行 OUTBOUND 邮件（状态与发送记录一致：
--   QUEUED / SENT / FAILED），与收到的那封共用同一个 conversation_key。
--   ⚠️ 失败时**收到的那封**邮件状态**不变**（仍是 RECEIVED）——
--     「失败」由 OUTBOUND 那一行的 status='FAILED' 如实记着，两边不互相污染。
--
-- 为什么附件是**元数据**表：
--   蓝图只要求「附件」；本仓 crm 服务**不持有对象存储**，
--   附件字节归 8 仓储 / 平台侧，本表只记「文件名 / 类型 / 大小 / 存储键 / 校验和」，
--   让客服能看见「买家附了 3 个文件」而不假装本服务存了文件。
--
-- ============================================================ 四、时间与数量
--
--   时间：一律 **timestamptz** + OffsetDateTime，绑定写 `?::timestamptz`。
--     （用 `Timestamp.valueOf(LocalDateTime)` 会在东八区**静默偏 −28800 秒**。）
--   数量：一律 **numeric(19,6)** + BigDecimal，**禁浮点**
--     （附件字节数与 channel V247 / V255、trade V239 的 `size_bytes` 同一形状）。
--   字节数用 numeric 而不是 bigint：仓库近几条迁移（V247 / V255 / V239）统一用
--     numeric(19,6)，本文件保持同一口径，避免同一语义在两处类型不同。
--
-- ============================================================ 五、发布后不可变的强制位置
--
--   1. 服务层：邮件的身份列（店铺 / 买家 / 会话键 / 收件时刻 / 方向）建后不可改
--      （触发器 guard_crm_buyer_email_identity）——
--      改了会让「这封邮件属于哪个会话」在历史上出现两个答案。
--   2. CHECK 约束：方向 / 来源 / 状态词表；FAILED 必须带失败原因；
--      SENT 必须带尝试时刻；附件字节数非负。
--   3. 触发器（CHECK 在 PostgreSQL 里可被 NOT VALID 或迁移脚本绕过，触发器不行）：
--      guard_crm_buyer_email_identity              （邮件身份列不可改）
--      guard_crm_buyer_email_reply_target          （reply_to_email_id 必须指向存在的邮件）
--      guard_crm_buyer_email_attachment_target     （附件必须挂在存在的邮件上）
--      guard_crm_buyer_email_send_record_target    （发送记录必须挂在存在的邮件上）
--      guard_crm_buyer_email_send_template_target  （引用的模板必须存在）
--      guard_crm_buyer_email_template_delete        （已被发送记录引用的模板删不掉）
--
-- ============================================================================
-- 零之一、角色
-- ============================================================================
-- 与 V99 / V220 同一口径：角色可能已由运维预置，因此只在缺失时创建，保证迁移可重复执行。
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='crm_app') THEN CREATE ROLE crm_app; END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN CREATE ROLE starcon_app; END IF;
END $$;
GRANT USAGE ON SCHEMA public TO crm_app;

-- ============================================================================
-- 1. crm_buyer_email · 邮件本体
-- ============================================================================
-- 邮件号序列单独建、单独授权：GRANT ... ON ALL TABLES 不覆盖序列，
-- 漏授权会变成「第一条邮件就报权限不足」（10.3.2.2 踩过这个坑）。
CREATE SEQUENCE crm_buyer_email_no_seq START 1;

CREATE TABLE crm_buyer_email (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- BE-yyyyMMdd-000001
  email_no varchar(32) NOT NULL DEFAULT '',

  -- ===== 店铺（来自 channel 的真实店铺清单，只存逻辑 ID + 名称快照）=====
  shop_id uuid NOT NULL,
  shop_name varchar(200) NOT NULL DEFAULT '',
  platform_code varchar(32) NOT NULL CHECK (platform_code ~ '^[A-Z][A-Z0-9_]*$'),

  -- ===== 方向 =====
  -- INBOUND  买家发来的邮件（**本模块的主要对象**）
  -- OUTBOUND 我们发出的邮件（由 status='SENT' 的发送记录落成，与收到的那封同会话）
  direction varchar(16) NOT NULL CHECK (direction IN ('INBOUND','OUTBOUND')),

  -- 来源：MANUAL_ENTRY 人工登记（**当前唯一可用**）；
  -- PLATFORM_SYNC 平台同步（**未接入**，服务端拒绝写这一档）。
  source varchar(24) NOT NULL DEFAULT 'MANUAL_ENTRY'
    CHECK (source IN ('MANUAL_ENTRY','PLATFORM_SYNC')),

  -- ===== 买家 =====
  -- 买家标识（平台买家号等，由运营按店铺提供）。本仓**没有买家档案**
  -- （15.3.1.1 客户列表未交付），它**不是**买家主数据，只作会话键与筛选键。
  buyer_ref varchar(128) NOT NULL CHECK (btrim(buyer_ref) <> ''),
  buyer_name varchar(200) NOT NULL DEFAULT '',
  buyer_email varchar(320) NOT NULL DEFAULT '',

  -- ===== 正文 =====
  subject varchar(500) NOT NULL DEFAULT '',
  body text NOT NULL DEFAULT '',

  -- ===== 🔴 会话键（加粗句「同一会话」的物理落点）=====
  -- 形如 <shopId>|<buyerRef 小写>|<salesOrderNo 小写>（无订单号时第三段写 '-'）。
  -- 同一买家 + 同一订单 + 同一店铺的收/发两封邮件**必然**同键 ⇒ 会话视图天然完整。
  conversation_key varchar(300) NOT NULL CHECK (btrim(conversation_key) <> ''),

  -- ===== 站内信软引用（15.1.1.1，未交付）=====
  -- **不加外键**：它的表不在本基线上（跨批次 FK 会让迁移直接失败），
  -- 也违反 AGENTS.md 的模块边界纪律。站内信侧由 15.1.1.1 用**同一会话键公式**
  -- 算自己的键，两边靠 conversation_key 相等来对齐「同源」。
  source_message_id uuid,

  -- ===== 回复关系（本模块内的软引用，触发器校验）=====
  -- OUTBOUND 邮件指向它回复的那封 INBOUND 邮件。
  reply_to_email_id uuid,

  -- 关联订单（**软引用**：订单归 trade 服务，本服务不建跨库 FK）。
  sales_order_no varchar(64) NOT NULL DEFAULT '',
  -- 平台侧邮件 ID（人工登记时可有可无；用于将来平台同步的幂等去重）。
  external_message_id varchar(200) NOT NULL DEFAULT '',

  -- 买家在平台上的邮件时刻（不是本系统的登记时刻）。
  received_at timestamptz NOT NULL,

  -- ===== 状态机 =====
  -- 收到的邮件（INBOUND）：
  --   RECEIVED 已收到、尚未成功回复
  --   REPLIED   已成功回复过（**只有** status='SENT' 的发送记录才会把它置成这一档）
  -- 发出的邮件（OUTBOUND）：它**就是**那次发送的结果，状态与发送记录一致
  --   QUEUED 已登记待发 · SENT 已发出 · FAILED 发送失败
  -- 🔴 发送失败（发送记录 status='FAILED'）**不改收到的那封邮件的状态**：
  --   它仍然是 RECEIVED —— 否则客服会以为已经回过
  --   （15.1.1.2 蓝图里的同一条红线，邮件侧同样适用）。
  --   失败这件事由**发出去的那一行**（OUTBOUND + status='FAILED'）如实记着。
  status varchar(16) NOT NULL DEFAULT 'RECEIVED'
    CHECK (status IN ('RECEIVED','REPLIED','QUEUED','SENT','FAILED')),
  -- 方向与状态必须自洽：收到的邮件不可能是「已发出」，发出的邮件不可能是「已收到」。
  CHECK (
    (direction = 'INBOUND' AND status IN ('RECEIVED','REPLIED'))
    OR (direction = 'OUTBOUND' AND status IN ('QUEUED','SENT','FAILED'))
  ),

  created_by uuid,
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, email_no)
);

-- 会话视图（加粗句「同一会话」）的主查询形状：同会话键 + 时刻倒序。
CREATE INDEX crm_buyer_email_by_conversation
  ON crm_buyer_email(tenant_id, conversation_key, received_at DESC);
-- 店铺维度筛选。
CREATE INDEX crm_buyer_email_by_shop
  ON crm_buyer_email(tenant_id, shop_id, received_at DESC);
-- 买家维度筛选（客服按买家查历史）。
CREATE INDEX crm_buyer_email_by_buyer
  ON crm_buyer_email(tenant_id, buyer_ref, received_at DESC);
-- 方向 + 状态筛选（收件箱 / 已发）。
CREATE INDEX crm_buyer_email_by_direction
  ON crm_buyer_email(tenant_id, direction, received_at DESC);
CREATE INDEX crm_buyer_email_by_status
  ON crm_buyer_email(tenant_id, status, received_at DESC);
-- 订单维度筛选（这封邮件问的是哪一单）。
CREATE INDEX crm_buyer_email_by_order
  ON crm_buyer_email(tenant_id, sales_order_no, received_at DESC)
  WHERE sales_order_no <> '';
-- 站内信软引用回填时用。
CREATE INDEX crm_buyer_email_by_source_message
  ON crm_buyer_email(tenant_id, source_message_id)
  WHERE source_message_id IS NOT NULL;
-- 回复关系回查。
CREATE INDEX crm_buyer_email_by_reply_to
  ON crm_buyer_email(tenant_id, reply_to_email_id)
  WHERE reply_to_email_id IS NOT NULL;
-- 平台同步的幂等去重（人工登记时空串不参与唯一）。
CREATE UNIQUE INDEX crm_buyer_email_one_per_external
  ON crm_buyer_email(tenant_id, external_message_id)
  WHERE external_message_id <> '';

-- ============================================================================
-- 2. crm_buyer_email_attachment · 附件**元数据**
-- ============================================================================
-- ⚠️ 本表**不存文件字节**：crm 服务没有对象存储。
-- 附件字节归平台侧 / 8 仓储；本表只记「这个附件是什么」，让客服看得见买家附了什么。
CREATE TABLE crm_buyer_email_attachment (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 挂在哪封邮件上。**用触发器做引用校验**，不建数据库外键（理由见文件头第一节末）。
  email_id uuid NOT NULL,

  file_name varchar(300) NOT NULL CHECK (btrim(file_name) <> ''),
  content_type varchar(120) NOT NULL DEFAULT '',
  -- 字节数：numeric(19,6) + BigDecimal，**禁浮点**（与 V247 / V255 / V239 同一形状）。
  size_bytes numeric(19,6) NOT NULL DEFAULT 0 CHECK (size_bytes >= 0),
  -- 对象存储键（**元数据**，不是本服务持有的文件路径）。
  storage_key varchar(400) NOT NULL DEFAULT '',
  -- 内容校验和（十六进制 sha256，64 位）。
  checksum_sha256 varchar(64) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id)
);

-- 按邮件读附件。
CREATE INDEX crm_buyer_email_attachment_by_email
  ON crm_buyer_email_attachment(tenant_id, email_id, created_at);

-- ============================================================================
-- 3. crm_buyer_email_template · 本模块自己的邮件模板
-- ============================================================================
-- ⚠️ 边界：15.4 知识库（话术内容）与 15.1.1.2（消息发送）侧的具体模板模块归属**未交付**。
--   本表**只做本模块范围内的最小完整闭环**：邮件模板 CRUD + 发送时引用模板。
--   它**不是** 15.4 知识库（那是按店铺 / 类目 / 语言组织的知识档案），
--   也**不是** 15.1.1.2 的消息模板（那属于消息发送，本模块不代劳）。
CREATE TABLE crm_buyer_email_template (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 业务键（同租户内唯一，便于运营按编码引用）。
  template_code varchar(64) NOT NULL CHECK (btrim(template_code) <> ''),
  name varchar(200) NOT NULL CHECK (btrim(name) <> ''),
  -- BCP-47 简写（如 zh-CN / en-US），与 V99 知识库的 locale CHECK 同一形状。
  locale varchar(16) NOT NULL DEFAULT 'en-US' CHECK(locale ~ '^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$'),

  subject varchar(500) NOT NULL DEFAULT '',
  body text NOT NULL DEFAULT '',

  -- 适用店铺：NULL = 本租户全部店铺。
  shop_id uuid,
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),

  created_by uuid,
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, template_code)
);

-- 按店铺 / 语言 / 状态筛选（模板下拉框的真数据源）。
CREATE INDEX crm_buyer_email_template_by_shop
  ON crm_buyer_email_template(tenant_id, shop_id, status, template_code);

-- ============================================================================
-- 4. crm_buyer_email_send_record · 发送记录（= 蓝图输出句里的「发送记录」）
-- ============================================================================
CREATE TABLE crm_buyer_email_send_record (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 挂在哪封邮件上（**被回复的那封收到的邮件**）。触发器做引用校验，不建外键。
  email_id uuid NOT NULL,
  -- 引用的模板（触发器校验）。模板**被删**后这里的引用仍在（模板删不掉，见 5.6）。
  template_id uuid,
  -- 模板编码快照：模板行被改掉后，历史上「用的哪套话术」仍可读。
  template_code varchar(64) NOT NULL DEFAULT '',

  -- 触达通道。
  -- PLATFORM_API   平台侧邮件发送接口（**当前未接入**，服务端拒绝写这一档）；
  -- MANUAL_HANDOFF 人工交接（运营在平台侧发出后登记结果）。
  transport varchar(24) NOT NULL CHECK (transport IN ('PLATFORM_API','MANUAL_HANDOFF')),
  -- QUEUED 已登记待发 · SENT 已发出 · FAILED 失败（必须带原因）· CANCELLED 已作废。
  status varchar(16) NOT NULL DEFAULT 'QUEUED'
    CHECK (status IN ('QUEUED','SENT','FAILED','CANCELLED')),

  -- 实际发出的主题与正文快照（**不落正文的话，事后无从知道我们到底说了什么**）。
  subject varchar(500) NOT NULL DEFAULT '',
  body text NOT NULL DEFAULT '',
  -- 平台回执（人工交接时可把平台侧的回执号登记进来）。
  platform_receipt varchar(500) NOT NULL DEFAULT '',

  attempted_at timestamptz,
  failure_reason varchar(600) NOT NULL DEFAULT '',
  -- 登记人（已认证成员，服务端写入）。
  operator_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE (tenant_id, id),
  CHECK (status <> 'SENT' OR attempted_at IS NOT NULL),
  CHECK (status <> 'FAILED' OR btrim(failure_reason) <> '')
);

-- 按邮件读发送记录（时间线）。
CREATE INDEX crm_buyer_email_send_record_by_email
  ON crm_buyer_email_send_record(tenant_id, email_id, created_at DESC);
-- 模板删不掉的判定要用。
CREATE INDEX crm_buyer_email_send_record_by_template
  ON crm_buyer_email_send_record(tenant_id, template_id)
  WHERE template_id IS NOT NULL;

-- ============================================================================
-- 5. 触发器（**一律不用外键** —— 理由见文件头第一节末）
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 5.1 guard_crm_buyer_email_identity · 邮件身份列建后不可改
-- ---------------------------------------------------------------------------
-- 为什么：这行是「这封邮件属于哪个会话」的凭据。改了会话键 / 买家 / 店铺 /
-- 收件时刻之后，加粗句要的「有没有重复回复」在历史上会出现两个答案 ——
-- 而加粗句要的正是**这个判断**可靠。
-- status（是否已回复）/ note / updated_at 仍然可改（它们是流程状态）。
CREATE FUNCTION guard_crm_buyer_email_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.conversation_key IS DISTINCT FROM OLD.conversation_key THEN
    RAISE EXCEPTION '邮件的会话键建后不可改：改它会让「这封邮件和哪几封是同一会话」在历史上出现两个答案，而重复回复买家的代价正是平台违规与买家投诉' USING ERRCODE='23514';
  END IF;
  IF NEW.buyer_ref IS DISTINCT FROM OLD.buyer_ref THEN
    RAISE EXCEPTION '邮件的买家标识建后不可改：改它会让「这位买家来过几封邮件」在历史上出现两个答案' USING ERRCODE='23514';
  END IF;
  IF NEW.shop_id IS DISTINCT FROM OLD.shop_id THEN
    RAISE EXCEPTION '邮件的店铺建后不可改' USING ERRCODE='23514';
  END IF;
  IF NEW.direction IS DISTINCT FROM OLD.direction THEN
    RAISE EXCEPTION '邮件的收发方向建后不可改' USING ERRCODE='23514';
  END IF;
  IF NEW.received_at IS DISTINCT FROM OLD.received_at THEN
    RAISE EXCEPTION '邮件的平台时刻建后不可改：改它会让「这封邮件什么时候到的」无从查证' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_buyer_email_identity_trg
  BEFORE UPDATE ON crm_buyer_email
  FOR EACH ROW EXECUTE FUNCTION guard_crm_buyer_email_identity();

-- ---------------------------------------------------------------------------
-- 5.2 guard_crm_buyer_email_reply_target · 回复关系必须指向存在的邮件
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_crm_buyer_email_reply_target() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_no varchar(32);
BEGIN
  IF NEW.reply_to_email_id IS NULL THEN
    RETURN NEW;
  END IF;
  SELECT email_no INTO v_no FROM crm_buyer_email
   WHERE tenant_id = NEW.tenant_id AND id = NEW.reply_to_email_id;
  IF v_no IS NULL THEN
    RAISE EXCEPTION '被回复的邮件 % 不存在（tenant=%），不能登记这条回复',
      NEW.reply_to_email_id, NEW.tenant_id USING ERRCODE='foreign_key_violation';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_buyer_email_reply_target_trg
  BEFORE INSERT ON crm_buyer_email
  FOR EACH ROW EXECUTE FUNCTION guard_crm_buyer_email_reply_target();

-- ---------------------------------------------------------------------------
-- 5.3 guard_crm_buyer_email_attachment_target · 附件必须挂在存在的邮件上
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_crm_buyer_email_attachment_target() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_no varchar(32);
BEGIN
  SELECT email_no INTO v_no FROM crm_buyer_email
   WHERE tenant_id = NEW.tenant_id AND id = NEW.email_id;
  IF v_no IS NULL THEN
    RAISE EXCEPTION '邮件 % 不存在（tenant=%），不能给它登记附件',
      NEW.email_id, NEW.tenant_id USING ERRCODE='foreign_key_violation';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_buyer_email_attachment_target_trg
  BEFORE INSERT ON crm_buyer_email_attachment
  FOR EACH ROW EXECUTE FUNCTION guard_crm_buyer_email_attachment_target();

-- ---------------------------------------------------------------------------
-- 5.4 guard_crm_buyer_email_send_record_target · 发送记录必须挂在存在的邮件上
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_crm_buyer_email_send_record_target() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_no varchar(32);
BEGIN
  SELECT email_no INTO v_no FROM crm_buyer_email
   WHERE tenant_id = NEW.tenant_id AND id = NEW.email_id;
  IF v_no IS NULL THEN
    RAISE EXCEPTION '邮件 % 不存在（tenant=%），不能给它登记发送记录',
      NEW.email_id, NEW.tenant_id USING ERRCODE='foreign_key_violation';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_buyer_email_send_record_target_trg
  BEFORE INSERT ON crm_buyer_email_send_record
  FOR EACH ROW EXECUTE FUNCTION guard_crm_buyer_email_send_record_target();

-- ---------------------------------------------------------------------------
-- 5.5 guard_crm_buyer_email_send_template_target · 引用的模板必须存在
-- ---------------------------------------------------------------------------
CREATE FUNCTION guard_crm_buyer_email_send_template_target() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_code varchar(64);
BEGIN
  IF NEW.template_id IS NULL THEN
    RETURN NEW;
  END IF;
  SELECT template_code INTO v_code FROM crm_buyer_email_template
   WHERE tenant_id = NEW.tenant_id AND id = NEW.template_id;
  IF v_code IS NULL THEN
    RAISE EXCEPTION '邮件模板 % 不存在（tenant=%），不能引用它',
      NEW.template_id, NEW.tenant_id USING ERRCODE='foreign_key_violation';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER crm_buyer_email_send_template_target_trg
  BEFORE INSERT ON crm_buyer_email_send_record
  FOR EACH ROW EXECUTE FUNCTION guard_crm_buyer_email_send_template_target();

-- ---------------------------------------------------------------------------
-- 5.6 guard_crm_buyer_email_template_delete · 已被发送记录引用的模板删不掉
-- ---------------------------------------------------------------------------
-- 为什么：模板是**配置**（删了就是删了），但**已经被发送记录引用过的**模板一旦删掉，
-- 「这次回复用的是哪套话术」在历史里就只剩一个编码快照、没有正文可查 ——
-- 结论被改写，而不是少了几条。
CREATE FUNCTION guard_crm_buyer_email_template_delete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_count integer;
BEGIN
  SELECT count(*) INTO v_count FROM crm_buyer_email_send_record
   WHERE tenant_id = OLD.tenant_id AND template_id = OLD.id;
  IF v_count > 0 THEN
    RAISE EXCEPTION '邮件模板 % 已被 % 条发送记录引用，删不掉：删掉它会让「这次回复用的哪套话术」在历史里无从查证。可以改成 DISABLED 停用',
      OLD.template_code, v_count USING ERRCODE='23514';
  END IF;
  RETURN OLD;
END $$;

CREATE TRIGGER crm_buyer_email_template_delete_trg
  BEFORE DELETE ON crm_buyer_email_template
  FOR EACH ROW EXECUTE FUNCTION guard_crm_buyer_email_template_delete();

-- ============================================================================
-- 6. RLS（ENABLE + FORCE）与授权
-- ============================================================================
-- 与 V99 / V220 同一口径：策略名固定 isolation，USING 与 WITH CHECK 两侧同表达式。
-- 表达式里的 nullif 不是洁癖：事务局部的 set_config 提交之后，PostgreSQL 会把自定义 GUC
-- **复位成空串**而不是「回到未设置」，直接 cast 会报 uuid 语法错，
-- 而 nullif 把它归一成 NULL —— 条件不成立 → 一行都看不到。
-- 也就是说「忘记绑定租户」当场失败，而不是静默返回全表。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['crm_buyer_email','crm_buyer_email_attachment','crm_buyer_email_template','crm_buyer_email_send_record'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON crm_buyer_email, crm_buyer_email_attachment, crm_buyer_email_template, crm_buyer_email_send_record TO crm_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON crm_buyer_email, crm_buyer_email_attachment, crm_buyer_email_template, crm_buyer_email_send_record TO starcon_app;
-- 🔴 序列单独授权：GRANT ... ON ALL TABLES **不覆盖序列**，漏授权会变成
-- 「第一条邮件就报权限不足」（10.3.2.2 踩过这个坑，这里不重复）。
GRANT USAGE, SELECT ON SEQUENCE crm_buyer_email_no_seq TO crm_app;
GRANT USAGE, SELECT ON SEQUENCE crm_buyer_email_no_seq TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM crm_app;

-- ============================================================================
-- 7. 留给下游 / 上游的口子（不是本模块的缺口）
-- ============================================================================
--   * **15.1.1.1 消息收件箱**（**同批并行开发**）：本表**不建**指向它的外键，
--     只留 nullable 的 source_message_id（软引用）+ conversation_key（同源标记）。
--     两边靠**同一个会话键公式**对齐，那是它落地之后的下一次迭代。
--     本模块**不假装**能看到站内信原文（capabilities 里 inbox_thread 维度 available=false，
--     ownerModule=15.1.1.1）。
--   * **15.1.1.2 消息发送**（**未交付**）：本模块**不实现**「从收件箱发送消息」这个动作，
--     只做**邮件自己的发送记录**。发送失败**保留原邮件状态**（邮件仍是 RECEIVED），
--     失败原因留在发送记录里 —— 不让客服以为已回复。
--   * **平台买家邮件接口**（**未接入**）：本模块**刻意不放假 vendor 实现**。
--     发送记录的 transport='PLATFORM_API' 由**服务层拒绝**，只允许 MANUAL_HANDOFF
--     并如实登记状态与平台回执 —— 一个「返回成功回执」的假通道会让
--     「买家已经收到回复了」在数据上看起来发生过，而重复回复的代价正是平台违规。
--   * **15.4 知识库 / 15.1.1.2 的模板**：本表只做**本模块自己的邮件模板**，
--     不改动也不假装它们的模板模块已交付。
