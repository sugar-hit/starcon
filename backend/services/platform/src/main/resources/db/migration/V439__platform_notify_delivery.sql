-- ===========================================================================
-- V439 · platform_notify_delivery —— 18.5.2.1「投递记录」
-- ===========================================================================
--
-- 蓝图 18-integration-and-automation.md 18.5.2.1 原文：
--
--   「**投递记录**：记录通知投递结果：模板、收件范围、渠道、状态、失败原因、重试；
--     统计触达率与退订情况。
--     输入：投递记录；重试请求。
--     输出：投递日志 + 统计。
--     关系：上游 = 18.5.1；下游 = 1.3.2.2 外部推送、15.5.2 触达。
--     **退订必须被尊重并生效**，反复触达退订用户是投诉与合规风险的主要来源。」
--
-- 落点：**platform 服务**（卷 18 的后端归属服务，端口 8089，库 starcon_platform，
-- 运行角色 platform_app）。紧跟 18.5.1.1「模板管理」的 V427__notification_template.sql。
--
-- ---------------------------------------------------------------------------
-- 0. 命名空间纪律（本模块最容易踩的坑）
-- ---------------------------------------------------------------------------
--
-- platform 服务里**已经有**一张 18.1.2.2 的 `platform_delivery_record`
-- （V413__webhook_delivery_record.sql）—— 那是 **webhook 投递**的事实表
-- （事件 → 订阅方），路由在 `/platform/deliveries`。
--
-- 而本模块是 **通知投递**（模板 → 收件人），对象、粒度、上游全都不一样：
--
--   platform_delivery_record        —— 事件 × 订阅方（webhook），V413，本模块**一个字不改**
--   platform_delivery_attempt       —— webhook 的逐次尝试，V413
--   platform_delivery_dead_letter   —— webhook 死信，V413
--   platform_delivery_replay_task   —— webhook 重放任务，V413
--
--   platform_notify_delivery_record          —— 通知投递事实表，**本迁移**
--   platform_notify_delivery_record_attempt  —— 通知的逐次投递尝试，**本迁移**
--   platform_notify_unsubscribe              —— 退订登记，**本迁移**
--   platform_notify_unsubscribe_block        —— 退订拦截留痕，**本迁移**
--
-- ⇒ 本迁移**只建 platform_notify_* 表**，绝不建 / 绝不动 platform_delivery_* 表。
--
-- ---------------------------------------------------------------------------
-- 1. 表清单（四张表，一条职责链）
-- ---------------------------------------------------------------------------
--
--   platform_notify_delivery_record         —— **投递记录**（模板 / 收件范围 / 渠道 / 状态 / 失败原因）
--   platform_notify_delivery_record_attempt —— **投递日志**（每一次尝试一行，含响应码与失败原因）
--   platform_notify_unsubscribe             —— **退订登记**（谁、在哪个范围、什么时候退的）
--   platform_notify_unsubscribe_block       —— **拦截留痕**（命中退订被拦下的每一次）
--
-- 关系：
--
--   delivery ──1:N──▶ attempt        （投递日志：每次尝试一行，attempt_no 在该投递内全局递增）
--        │
--        └──1:N──▶ unsubscribe_block ──▶ unsubscribe
--
-- ---------------------------------------------------------------------------
-- 2. 蓝图加粗句「退订必须被尊重并生效」的落点
-- ---------------------------------------------------------------------------
--
-- 「尊重」= **先查后登记**：登记投递时，逐个收件人先查退订表；命中退订的收件人
--   **不进投递尝试**，而是写一行 `platform_notify_unsubscribe_block`。
--
-- 「不许静默丢弃」= 被拦下的收件人**进统计**：blocked_count 落在投递记录上，
--   拦截数也进租户级统计 ⇒ 「我们因为退订少发了几百条」是**看得见**的数字，
--   而不是「反正没人知道」。
--
-- ⚠️ 拦截行是**证据**：它能回答「为什么这个收件人没收到」。
--   UNIQUE(tenant_id, delivery_id, recipient_ref) ⇒ 同一个收件人在同一次投递里
--   最多被记一次拦截（重试不会重复计）。
--
-- ---------------------------------------------------------------------------
-- 3. 数量与比率：全部 NUMERIC，一个浮点都没有
-- ---------------------------------------------------------------------------
--
--   数量（收件人数 / 触达数 / 失败数 / 拦截数）⇒ NUMERIC(19,6)，服务层用 BigDecimal。
--   比率（触达率 / 退订率）                        ⇒ NUMERIC(7,4)，**不是** double / float。
--
-- 🔴 CHECK(blocked_count + targeted_count = recipient_count)：
--   收件人 = 被拦下的 + 实际投的。少了它，「触达率」就可能拿一个对不上的分母去算。
--
-- ---------------------------------------------------------------------------
-- 4. 时间：一律 timestamptz
-- ---------------------------------------------------------------------------
--
-- 服务层用 **OffsetDateTime** 绑定并在 values 位置写 `?::timestamptz`
-- （用 `Timestamp.valueOf(LocalDateTime…)` 会静默偏 −28800s）。
--
-- 落库时间戳一律取 **DB 时钟**（clock_timestamp()），不取 JVM 墙钟 ——
-- 「投递发生的时间」与「落库的时间」在同一个钟域里。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. platform_notify_delivery_record —— 投递记录
-- ---------------------------------------------------------------------------
--
-- 一行 = 一次「模板 → 某个收件范围 → 某个渠道」的投递（**含它全部的尝试**）。
--
-- 🔴 template_id 建**真 FK** → platform_notify_template：
--   投递记录挂在真实存在的通知模板上 ⇒ 「这批通知是哪个模板发的」永远答得上来；
--   模板被物理删 ⇒ 投递记录被挡住（ON DELETE RESTRICT）——
--   历史通知的模板依据不能随模板一起消失。
CREATE TABLE platform_notify_delivery_record (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- === 模板（上游 18.5.1.1）===
  template_id uuid NOT NULL,
  -- 实际使用的模板版本（**快照**：模板后来改版也不影响这行记录的事实）。
  template_version_id uuid,
  -- 模板 key 快照（模板改名 / 归档后仍可读）。
  template_key varchar(64) NOT NULL,

  -- === 收件范围 ===
  -- SINGLE     单个收件人（scope_ref = 收件人标识）
  -- SEGMENT    某个分群（scope_ref = 分群标识）
  -- SUBSCRIBERS 该模板 / 场景下的订阅者全集（scope_ref = 分群标识或空）
  scope_type varchar(24) NOT NULL
    CHECK(scope_type IN ('SINGLE','SEGMENT','SUBSCRIBERS')),
  scope_ref varchar(200) NOT NULL DEFAULT '',

  -- === 渠道 ===
  -- EMAIL / SMS / PUSH / IN_APP / WEBHOOK
  channel varchar(24) NOT NULL
    CHECK(channel IN ('EMAIL','SMS','PUSH','IN_APP','WEBHOOK')),

  -- === 投递状态 ===
  -- PENDING   已受理，**还有尝试没跑完**（含刚建好还没发出去的）
  -- DELIVERED 全部收件人触达成功
  -- PARTIAL   一部分触达成功、一部分失败
  -- FAILED    投了但全部失败
  -- BLOCKED   全部收件人命中退订 ⇒ **一次都没发出去**
  status varchar(16) NOT NULL DEFAULT 'PENDING'
    CHECK(status IN ('PENDING','DELIVERED','PARTIAL','FAILED','BLOCKED')),

  -- === 数量（NUMERIC(19,6)，禁浮点）===
  -- 收件人总数 = 被拦下的 + 实际投的。
  recipient_count numeric(19,6) NOT NULL DEFAULT 0
    CHECK(recipient_count >= 0),
  -- 因退订被拦下的人数（**进统计**，不许静默丢弃）。
  blocked_count numeric(19,6) NOT NULL DEFAULT 0
    CHECK(blocked_count >= 0),
  -- 实际投递的人数 = recipient_count - blocked_count。
  targeted_count numeric(19,6) NOT NULL DEFAULT 0
    CHECK(targeted_count >= 0),
  -- 最近一次结果为成功的收件人数。
  delivered_count numeric(19,6) NOT NULL DEFAULT 0
    CHECK(delivered_count >= 0),
  -- 最近一次结果为失败的收件人数。
  failed_count numeric(19,6) NOT NULL DEFAULT 0
    CHECK(failed_count >= 0),
  -- 已尝试次数（与 attempt 表的行数一致）。
  attempt_count int NOT NULL DEFAULT 0
    CHECK(attempt_count >= 0),

  -- === 失败原因（最近一次失败的归纳，空 = 没失败过）===
  failure_reason varchar(1000) NOT NULL DEFAULT '',

  -- === 触达率 = delivered_count / targeted_count（分母为 0 时记 0）===
  -- 🔴 NUMERIC(7,4)，**不是**浮点。
  delivery_rate numeric(7,4) NOT NULL DEFAULT 0
    CHECK(delivery_rate >= 0 AND delivery_rate <= 1),

  -- === 时间线 ===
  next_retry_at timestamptz,
  first_attempt_at timestamptz,
  last_attempt_at timestamptz,
  delivered_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),

  FOREIGN KEY(tenant_id, template_id)
    REFERENCES platform_notify_template(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY(tenant_id, template_version_id)
    REFERENCES platform_notify_template_version(tenant_id, id) ON DELETE RESTRICT,

  CHECK(btrim(template_key) <> '' AND length(template_key) <= 64),
  CHECK(scope_type <> 'SINGLE' OR btrim(scope_ref) <> ''),
  -- 🔴 收件人 = 被拦下的 + 实际投的（分母自洽 ⇒ 触达率不会算错）。
  CHECK(blocked_count + targeted_count = recipient_count)
);

-- 列表按「时间倒序」翻页（最常用的读路径）。
CREATE INDEX platform_notify_delivery_record_by_time
  ON platform_notify_delivery_record(tenant_id, created_at DESC, id DESC);

-- 按模板筛（「这个模板发过什么」）。
CREATE INDEX platform_notify_delivery_record_by_template
  ON platform_notify_delivery_record(tenant_id, template_id, created_at DESC);

-- 按状态筛（「哪些还没发出去」）。
CREATE INDEX platform_notify_delivery_record_by_status
  ON platform_notify_delivery_record(tenant_id, status, created_at DESC);

-- 按渠道筛（「短信发了多少」）。
CREATE INDEX platform_notify_delivery_record_by_channel
  ON platform_notify_delivery_record(tenant_id, channel, created_at DESC);

-- 🔴 重试到期扫描的**专用**索引：只关心「PENDING 且到期」的那几行。
CREATE INDEX platform_notify_delivery_record_retry_due
  ON platform_notify_delivery_record(tenant_id, next_retry_at)
  WHERE status = 'PENDING';

-- ---------------------------------------------------------------------------
-- 2. platform_notify_delivery_record_attempt —— 投递日志
-- ---------------------------------------------------------------------------
--
-- 一行 = **一次**投递尝试（针对一个收件人）。这是「投递日志」的本体：
-- 响应码、耗时、失败原因都在这里。
--
-- 🔴 UNIQUE(tenant_id, delivery_id, attempt_no)：
--   attempt_no 在**该投递内全局递增**（不是每个收件人各自从 1 开始）——
--   否则「这个投递一共投了几次」这个问题答不出来。
--
-- 🔴 status_code = 0 的行**是合法的**：连接被拒 / 超时 / 根本没发出去时，
--   **没有**响应码。把它记成 200 或记成 -1 都是在编造事实。
CREATE TABLE platform_notify_delivery_record_attempt (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  delivery_id uuid NOT NULL,

  -- 第几次尝试（在本次投递内全局递增，从 1 开始，含首次）。
  attempt_no int NOT NULL
    CHECK(attempt_no >= 1),

  -- 收件人标识（与投递记录里的收件人列表逐字对应）。
  recipient_ref varchar(200) NOT NULL,

  -- 本次尝试走的渠道（与投递记录一致；留一份是为了日志自洽）。
  channel varchar(24) NOT NULL
    CHECK(channel IN ('EMAIL','SMS','PUSH','IN_APP','WEBHOOK')),

  -- 本次尝试拿到的响应码；**0 = 没有拿到响应**（站内信这类没有 HTTP 码的渠道也记 0）。
  status_code int NOT NULL DEFAULT 0,
  -- 🔴 本次尝试的结果：**PENDING 表示这次尝试还没出结果**（已受理、结果未回）。
  --   为什么不能只靠 success 布尔位：「已受理未出结果」与「尝试过但没拿到响应」
  --   在 success 上都是 false ⇒ 两者会被混成一个数 ⇒ 「触达率」的分母就不可信了。
  outcome varchar(16) NOT NULL DEFAULT 'PENDING'
    CHECK(outcome IN ('DELIVERED','FAILED','PENDING')),
  -- 本次尝试的来源：
  --   INITIAL 首次投递
  --   RETRY   重试请求触发的重试
  --   MANUAL  人工手动重投
  attempt_source varchar(24) NOT NULL DEFAULT 'INITIAL'
    CHECK(attempt_source IN ('INITIAL','RETRY','MANUAL')),

  -- 本次尝试的耗时（**毫秒计数**，整数；不是数量也不是金额 ⇒ bigint，零浮点）。
  duration_ms bigint NOT NULL DEFAULT 0
    CHECK(duration_ms >= 0),
  -- 失败原因（空 = 没失败）。
  failure_reason varchar(1000) NOT NULL DEFAULT '',

  occurred_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, delivery_id, attempt_no),

  FOREIGN KEY(tenant_id, delivery_id)
    REFERENCES platform_notify_delivery_record(tenant_id, id) ON DELETE CASCADE,

  CHECK(btrim(recipient_ref) <> '' AND length(recipient_ref) <= 200),
  -- 🔴 结果与响应码不许自相矛盾：不能记下「已送达 + 500」这种编造出来的事实。
  --   （status_code = 0 是合法的：站内信没有 HTTP 码，或尝试后没拿到任何响应。）
  CHECK(outcome <> 'DELIVERED' OR status_code = 0 OR (status_code >= 200 AND status_code < 300)),
  CHECK(outcome <> 'FAILED' OR status_code = 0 OR status_code < 200 OR status_code > 299)
);

CREATE INDEX platform_notify_delivery_record_attempt_by_delivery
  ON platform_notify_delivery_record_attempt(tenant_id, delivery_id, attempt_no);

-- 按收件人筛（「这个地址最近收到过什么」）。
CREATE INDEX platform_notify_delivery_record_attempt_by_recipient
  ON platform_notify_delivery_record_attempt(tenant_id, recipient_ref, occurred_at DESC);

-- ---------------------------------------------------------------------------
-- 3. platform_notify_unsubscribe —— 退订登记
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个收件人的一条**生效中**的退订。
--
-- 🔴 UNIQUE(tenant_id, recipient_ref, channel, scope_type, scope_key)：
--   同一个收件人在同一个（渠道 × 范围）下**只可能有一条**退订登记。
--   ⇒ 服务层的「**先查后登记**」由这条唯一键兜底：并发两个请求同时登记，
--   一个成功、另一个撞唯一键 ⇒ 退订**不会**被登记两遍。
--
-- ⚠️ 撤销退订用 `revoked_at`（软撤销），**不物理删**：
--   「曾经退订过、后来又订阅了」本身是一条事实。
CREATE TABLE platform_notify_unsubscribe (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 收件人标识（邮箱 / 手机号 / 设备号……）。
  recipient_ref varchar(200) NOT NULL,

  -- 渠道；'ALL' = 所有渠道都不再触达。
  channel varchar(24) NOT NULL
    CHECK(channel IN ('EMAIL','SMS','PUSH','IN_APP','WEBHOOK','ALL')),

  -- 退订范围：
  --   TEMPLATE 只退订这一个模板（scope_key = template_key）
  --   SCENE    退订这个场景的全部模板（scope_key = 场景名）
  --   GLOBAL   这个租户在该渠道上不再接任何通知（scope_key = '*'）
  scope_type varchar(24) NOT NULL
    CHECK(scope_type IN ('TEMPLATE','SCENE','GLOBAL')),

  -- 范围键：TEMPLATE = 模板 key，SCENE = 场景名，GLOBAL = '*'。
  scope_key varchar(64) NOT NULL DEFAULT '*',

  -- TEMPLATE / SCENE 范围绑定的模板（TEMPLATE 时必填；SCENE 时可空）。
  template_id uuid,

  -- 退订原因（人话，留痕）。
  reason varchar(500) NOT NULL DEFAULT '',
  -- 退订来源：
  --   USER_REQUEST 收件人自己要求
  --   COMPLAINT    投诉导致的退订
  --   ADMIN        运营代为登记
  source varchar(24) NOT NULL DEFAULT 'USER_REQUEST'
    CHECK(source IN ('USER_REQUEST','COMPLAINT','ADMIN')),

  unsubscribed_at timestamptz NOT NULL,
  -- 撤销时刻（NULL = 仍然生效）。
  revoked_at timestamptz,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 🔴 「先查后登记」的唯一键兜底。
  UNIQUE(tenant_id, recipient_ref, channel, scope_type, scope_key),

  FOREIGN KEY(tenant_id, template_id)
    REFERENCES platform_notify_template(tenant_id, id) ON DELETE RESTRICT,

  CHECK(btrim(recipient_ref) <> '' AND length(recipient_ref) <= 200),
  CHECK(scope_type <> 'GLOBAL' OR scope_key = '*'),
  CHECK(scope_type <> 'TEMPLATE' OR btrim(scope_key) <> ''),
  CHECK(revoked_at IS NULL OR revoked_at >= unsubscribed_at)
);

-- 按收件人查（「这个人退订过什么」）。
CREATE INDEX platform_notify_unsubscribe_by_recipient
  ON platform_notify_unsubscribe(tenant_id, recipient_ref);

-- 🔴 **生效中**退订的专用索引：投递登记时的退订判定只关心这批行。
CREATE INDEX platform_notify_unsubscribe_active
  ON platform_notify_unsubscribe(tenant_id, channel, scope_type, scope_key)
  WHERE revoked_at IS NULL;

-- ---------------------------------------------------------------------------
-- 4. platform_notify_unsubscribe_block —— 拦截留痕
-- ---------------------------------------------------------------------------
--
-- 一行 = 一次「因为命中退订而被拦下」的记录。
--
-- 🔴 它是**证据**：回答「为什么这个收件人没收到这条通知」。
--   UNIQUE(tenant_id, delivery_id, recipient_ref) ⇒ 同一个收件人在同一次投递里
--   最多被记一次（重试不会重复计数）。
--
-- ⚠️ 被拦下的收件人**不进 attempt 表**：没有发出去的东西不能出现在投递日志里。
CREATE TABLE platform_notify_unsubscribe_block (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 被拦下的那一次投递。
  delivery_id uuid NOT NULL,
  -- 命中的那条退订登记。
  unsubscribe_id uuid NOT NULL,

  -- 收件人标识（与投递记录里的收件人逐字对应）。
  recipient_ref varchar(200) NOT NULL,
  -- 当时走的渠道。
  channel varchar(24) NOT NULL
    CHECK(channel IN ('EMAIL','SMS','PUSH','IN_APP','WEBHOOK')),
  -- 命中的退订范围（抄一份下来，退订后来被撤销也不改这一行的历史）。
  unsubscribe_scope_type varchar(24) NOT NULL
    CHECK(unsubscribe_scope_type IN ('TEMPLATE','SCENE','GLOBAL')),
  unsubscribe_scope_key varchar(64) NOT NULL,

  blocked_at timestamptz NOT NULL,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, delivery_id, recipient_ref),

  FOREIGN KEY(tenant_id, delivery_id)
    REFERENCES platform_notify_delivery_record(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, unsubscribe_id)
    REFERENCES platform_notify_unsubscribe(tenant_id, id) ON DELETE CASCADE,

  CHECK(btrim(recipient_ref) <> '' AND length(recipient_ref) <= 200)
);

CREATE INDEX platform_notify_unsubscribe_block_by_delivery
  ON platform_notify_unsubscribe_block(tenant_id, delivery_id);

-- 拦截数统计（按时间倒序翻页）。
CREATE INDEX platform_notify_unsubscribe_block_by_time
  ON platform_notify_unsubscribe_block(tenant_id, blocked_at DESC);

-- ===========================================================================
-- 5. 多租户：RLS + 授权
-- ===========================================================================
--
-- 四张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
--
-- ⚠️ policy 只认 `app.current_tenant` 这个**事务局部** GUC，它由
--    TenantTransactions.set_config(..., true) 绑定 —— 事务结束即失效，
--    连接归还池中时**不留残留**（这正是 AGENTS.md 第三条隔离断言要验的）。
--
-- 🔴 **退订表与拦截表是合规敏感对象**：它们记着「谁明确表示不要再联系」。
--   跨租户可见等于把甲租户的客户退订名单交给乙租户。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'platform_notify_delivery_record',
    'platform_notify_delivery_record_attempt',
    'platform_notify_unsubscribe',
    'platform_notify_unsubscribe_block'
  ] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

-- 🔴 租户触发器兜底（直连 SQL 也写不出跨租户的行）：
--   RLS 已经在读 / 写两侧挡住跨租户；触发器再挡一道，是因为
--   「owner 身份连进来」这类场景（后台任务、运维脚本）容易让人以为 RLS 也会跟着生效。
CREATE FUNCTION platform_notify_delivery_tenant_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  current_tenant uuid;
BEGIN
  current_tenant := nullif(current_setting('app.current_tenant', true), '')::uuid;
  IF current_tenant IS NULL THEN
    RAISE EXCEPTION '缺少租户上下文（app.current_tenant），不能写入通知投递相关表（%）', TG_TABLE_NAME
      USING ERRCODE = '28000';
  END IF;
  IF NEW.tenant_id IS DISTINCT FROM current_tenant THEN
    RAISE EXCEPTION '通知投递相关表的 tenant_id 必须等于当前租户上下文（% / %）', TG_TABLE_NAME, current_tenant
      USING ERRCODE = '42501';
  END IF;
  -- 投递记录的时间戳由 DB 时钟统一维护。
  IF TG_TABLE_NAME = 'platform_notify_delivery_record' THEN
    NEW.updated_at := now();
  END IF;
  RETURN NEW;
END $$;

REVOKE EXECUTE ON FUNCTION platform_notify_delivery_tenant_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_notify_delivery_tenant_guard() TO platform_app;

DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'platform_notify_delivery_record',
    'platform_notify_delivery_record_attempt',
    'platform_notify_unsubscribe',
    'platform_notify_unsubscribe_block'
  ] LOOP
  EXECUTE format(
    'CREATE TRIGGER platform_notify_delivery_tenant_guard_trigger BEFORE INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION platform_notify_delivery_tenant_guard()',tbl);
 END LOOP;
END $$;

-- ===========================================================================
-- 6. 授权
-- ===========================================================================
--
-- 运行角色是 platform_app：它要能读自己的投递记录 / 投递日志 / 退订 / 拦截，
-- 能写（登记投递、记尝试、登记退订、记拦截）。
--
-- 🔴 四张表**一律不给 DELETE**：投递明细与退订登记是「这件事发生过」的证据，
--   删掉它就等于让「投没投出去」「谁退订过」变成一个答不出来的问题。
--   （撤销退订用 revoked_at 软撤销，不删行。）
GRANT SELECT,INSERT,UPDATE ON platform_notify_delivery_record TO platform_app;
GRANT SELECT,INSERT,UPDATE ON platform_notify_delivery_record_attempt TO platform_app;
GRANT SELECT,INSERT,UPDATE ON platform_notify_unsubscribe TO platform_app;
GRANT SELECT,INSERT,UPDATE ON platform_notify_unsubscribe_block TO platform_app;
