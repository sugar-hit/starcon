-- ===========================================================================
-- V413 · webhook_delivery_record —— 18.1.2.2「投递记录」
-- ===========================================================================
--
-- 蓝图 18-integration-and-automation.md 18.1.2.2 原文：
--
--   「**投递记录**：记录事件投递明细：事件 ID、订阅方、投递状态、响应码、重试次数；
--     提供手动重投与死信重放。
--     输入：投递记录；重放请求。
--     输出：投递日志 + 重放任务。
--     关系：上游 = 18.1.2.1；下游 = 外部端点。
--     **接收方应按 event_id 幂等去重**——投递保证是"至少一次"，不是"恰好一次"。」
--
-- 落点：**platform 服务**（卷 18 的后端归属服务，端口 8089，库 starcon_platform，
-- 运行角色 platform_app）。紧跟 18.1.2.1「事件订阅」的 V412__event_subscription.sql。
--
-- ---------------------------------------------------------------------------
-- 0. 本模块**补上**了 18.1.2.1 刻意留空的那一格
-- ---------------------------------------------------------------------------
--
-- V412 的第 0 节写得很清楚：
--
--   「**不做投递记录 / 投递日志**。蓝图里「投递明细（事件 ID、订阅方、投递状态、响应码、
--     重试次数、死信重放）」属于 **18.1.2.2「投递记录」**。
--     ……「事件投递（带签名）」这一段做成一个**显式的未接入端口**，
--     端点返回 **409**，**一次投递都不发生**。」
--
-- 而 `EventSubscriptionDeliveryPort` 的类注释写得更死：
--
--   「⚠️ **投递记录 / 投递日志不在本端口的职责里**：那属于 18.1.2.2「投递记录」。
--     本端口只负责「发一次」，**不落任何投递明细**。」
--
-- `UnavailableEventSubscriptionDeliveryPort` 里还钉着一行：
--
--   `static final String OWNER_MODULE = "18.1.2.2";`
--   「投递执行器与投递日志同属那一格」
--
-- ⇒ **本迁移就是那一格**：投递执行器（真实 HTTP POST，带签名头）+ 投递明细落库
--   + 重试与死信归这里。18.1.2.1 只管订阅配置侧。
--
-- 🔴 **本迁移的纪律与 V412 一致**：
--   * 四张表全部 tenant_id NOT NULL + UNIQUE(tenant_id, id) 复合键
--     + 跨表 (tenant_id, …) 复合外键 + **ENABLE 与 FORCE** RLS isolation
--     + GRANT … TO platform_app（DO 循环逐表，与 V412 / V377 / V380 / V382 同写法）。
--   * 时间列一律 **timestamptz**；服务层用 OffsetDateTime 绑定并在 values 位置写
--     `?::timestamptz`（用 Timestamp.valueOf(LocalDateTime…) 会静默偏 −28800s）。
--   * 落库时间戳一律取 **DB 时钟**（clock_timestamp()），不取 JVM 墙钟。
--
-- ---------------------------------------------------------------------------
-- 1. 表清单
-- ---------------------------------------------------------------------------
--
--   platform_delivery_record          —— **投递记录**（蓝图点名的六项事实都在这里）
--   platform_delivery_attempt  —— **投递日志**（每一次尝试一行，含响应码）
--   platform_delivery_dead_letter       —— **死信**（重试耗尽的投递，供人工重放）
--   platform_delivery_replay_task       —— **重放任务**（手动重投 / 死信重放的留痕）
--
-- 四张表的关系：
--
--   delivery ──1:N──▶ attempt        （投递日志：每次尝试一行，attempt_no 从 1 开始）
--        │
--        ├──1:1──▶ dead_letter       （重试耗尽才产生；一个投递最多一条）
--        │
--        └──1:N──▶ replay_task       （每一次人工重放一条）
--
-- ---------------------------------------------------------------------------
-- 2. 「至少一次」不是口号：它落在**唯一键**上
-- ---------------------------------------------------------------------------
--
-- 蓝图那句「**接收方应按 event_id 幂等去重**——投递保证是"至少一次"，不是"恰好一次"」
-- 反过来读就是：**平台自己就允许同一个事件对同一个订阅投多次**（失败重试、人工重放、
-- 死信重放）。所以：
--
--   * 🔴 **UNIQUE(tenant_id, event_id, subscription_id)**：
--     同一个事件对同一个订阅**只有一条投递记录**；重试与重放都在**这一行**上累加
--     attempt_count，而不是每次都插一行新的投递记录。
--     ⇒ 「投递记录」是**事件 × 订阅方**的粒度（蓝图说的「订阅方」就在这一列），
--       「投递日志」才是**每一次尝试**的粒度。两者的粒度不同是刻意的：
--       把两者混成一张表，就没法回答「这个事件一共投了几次」。
--
--   * ⚠️ 这条唯一键**不是**「恰好一次」的保证：它只保证「同一事件不会被登记成两条投递记录」，
--     **不**保证只发一个包。接收方**必须**按 event_id 幂等去重。
--
-- ---------------------------------------------------------------------------
-- 3. 落库时间戳：DB 时钟，且与断言同钟域
-- ---------------------------------------------------------------------------
--
-- ⚠️ 本表**没有**任何「现在」由应用侧填的列：created_at / updated_at 用 DEFAULT now()，
--   其余时间点由服务层用 `clock_timestamp()` 读出后用 OffsetDateTime 绑定。
--   ⇒ 「投递发生的时间」与「落库的时间」在**同一个时钟域**里。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. platform_delivery_record —— 投递记录
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个「事件 → 某个订阅方」的一次投递（**含它全部的尝试**）。
--
-- 🔴 subscription_id 建**真 FK**（tenant_id, subscription_id) → platform_webhook_subscription：
--   投递记录挂在真实的订阅上 ⇒ 「谁在收我们的事件」永远答得上来；
--   订阅被删 ⇒ 它的投递记录随之级联删除（订阅都没了，留着投递记录没有意义）。
--
-- 🔴 app_id 同样建真 FK（→ platform_app）：投递必须有归属应用，
--   否则「哪个第三方接入方没收到」这个问题答不了。
CREATE TABLE platform_delivery_record (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- === 事件身份 ===
  -- 事件 ID（业务事件实例的全局标识）。
  -- 🔴 **接收方按它幂等去重** —— 所以它在整个投递生命周期里**不变**：
  --   重试、人工重放、死信重放全都复用同一个 event_id。
  event_id uuid NOT NULL,
  -- 事件类型字面量（与 18.1.2.1 注册表里的那个字符串逐字相同）。
  event_type varchar(64) NOT NULL,
  -- schema 版本（同一 event_type 的第 N 版结构）。
  schema_version int NOT NULL DEFAULT 1
    CHECK(schema_version >= 1),

  -- === 订阅方 ===
  -- 订阅（软删时连带删除投递记录）。
  subscription_id uuid NOT NULL,
  -- 归属应用（订阅挂着的那个 platform_app）。
  app_id uuid NOT NULL,
  -- 本次投递的目标地址（**快照**：订阅后来改了地址，历史投递记录仍指向当时那个地址）。
  target_url varchar(500) NOT NULL,

  -- === 投递状态 ===
  -- PENDING      未成功投递，且**还会自动重试**（含刚建好还没发出去的）
  -- DELIVERED    投递成功（2xx）
  -- FAILED       失败且**不会自动重试**（永久性错误：订阅停用 / 没有生效中的签名密钥 / 地址非法）
  -- DEAD_LETTER  重试次数用尽，转入死信箱（终态，可人工重放）
  status varchar(16) NOT NULL DEFAULT 'PENDING'
    CHECK(status IN ('PENDING','DELIVERED','FAILED','DEAD_LETTER')),

  -- === 响应码与次数 ===
  -- 最近一次尝试拿到的 HTTP 响应码；**0 = 根本没拿到响应**（连接失败 / 超时 / 没发出去）。
  status_code int NOT NULL DEFAULT 0,
  -- 已尝试次数（含首次）。每次尝试在 platform_delivery_attempt 里留一行。
  attempt_count int NOT NULL DEFAULT 0
    CHECK(attempt_count >= 0),
  -- 人工重放次数（自动重试**不**计入 —— 它不是「人工介入」）。
  replay_count int NOT NULL DEFAULT 0
    CHECK(replay_count >= 0),

  -- === 载荷 ===
  -- 事件数据（jsonb 对象）：给人看的、也是筛选用的。
  event_payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  -- 🔴 **实际发出去的报文字节**（Base64 编码）。
  --   为什么不用 event_payload 重新序列化：签名盖的是**原始字节**，
  --   重放时若重新序列化，字节序/空格可能变 ⇒ 验签对不上。
  --   存原始字节 ⇒ 重放发出去的**就是那一份**，验签与对端去重都成立。
  body_bytes text NOT NULL,

  -- 最近一次失败的原因（空 = 没失败过）。
  last_error varchar(1000) NOT NULL DEFAULT '',

  -- === 时间线 ===
  -- 下次自动重试的时间（NULL = 不排下次）。
  next_retry_at timestamptz,
  -- 首次尝试 / 最近一次尝试 / 成功 / 转死信的时间点。
  first_attempt_at timestamptz,
  last_attempt_at timestamptz,
  delivered_at timestamptz,
  dead_lettered_at timestamptz,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 🔴 「至少一次」的唯一键：同一事件对同一订阅**只有一条**投递记录。
  UNIQUE(tenant_id, event_id, subscription_id),

  FOREIGN KEY(tenant_id, subscription_id)
    REFERENCES platform_webhook_subscription(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, app_id)
    REFERENCES platform_app(tenant_id, id) ON DELETE CASCADE,

  CHECK(btrim(event_type) <> ''),
  CHECK(btrim(target_url) <> ''),
  CHECK(btrim(body_bytes) <> '')
);

-- 列表按「时间倒序」翻页（最常用的读路径）。
CREATE INDEX platform_delivery_record_by_time
  ON platform_delivery_record(tenant_id, created_at DESC, id DESC);

-- 按订阅筛（「这个订阅投过什么」）。
CREATE INDEX platform_delivery_record_by_subscription
  ON platform_delivery_record(tenant_id, subscription_id, created_at DESC);

-- 按事件筛（「这个事件投给了谁」）。
CREATE INDEX platform_delivery_record_by_event
  ON platform_delivery_record(tenant_id, event_id);

-- 🔴 重试到期扫描的**专用**索引：只关心「PENDING 且到期」的那几行，
--   没有它，每一轮扫描都要全表扫。
CREATE INDEX platform_delivery_record_retry_due
  ON platform_delivery_record(tenant_id, next_retry_at)
  WHERE status = 'PENDING';

-- ---------------------------------------------------------------------------
-- 2. platform_delivery_attempt —— 投递日志
-- ---------------------------------------------------------------------------
--
-- 一行 = **一次**投递尝试。这是「投递日志」的本体：响应码、耗时、错误都在这里。
--
-- 🔴 UNIQUE(tenant_id, delivery_id, attempt_no)：
--   同一个投递的第 N 次尝试只可能有一行 ⇒ 重试与重放**不会**把日志写成两行同号。
--
-- 🔴 status_code = 0 的行**是合法的**：连接被拒 / 超时 / 根本没发出去时，
--   **没有**响应码。把它记成 200 或记成 -1 都是在编造事实。
CREATE TABLE platform_delivery_attempt (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  delivery_id uuid NOT NULL,

  -- 第几次尝试（从 1 开始，含首次）。
  attempt_no int NOT NULL
    CHECK(attempt_no >= 1),

  -- 本次尝试的 HTTP 响应码；**0 = 没有拿到响应**。
  status_code int NOT NULL DEFAULT 0,
  -- 本次尝试是否成功（2xx）。
  success boolean NOT NULL DEFAULT false,
  -- 本次尝试的来源：
  --   INITIAL           首次投递
  --   RETRY             策略驱动的自动重试
  --   MANUAL            人工手动重投
  --   DEAD_LETTER_REPLAY 死信重放
  attempt_source varchar(24) NOT NULL DEFAULT 'INITIAL'
    CHECK(attempt_source IN ('INITIAL','RETRY','MANUAL','DEAD_LETTER_REPLAY')),

  -- 本次尝试的耗时（**毫秒计数**，整数；不是数量也不是金额 ⇒ bigint，零浮点）。
  duration_ms bigint NOT NULL DEFAULT 0
    CHECK(duration_ms >= 0),
  -- 失败原因（空 = 没失败）。
  error varchar(1000) NOT NULL DEFAULT '',
  -- 响应体片段（截断到 1000 字符；只用于让人看清对方回了什么）。
  response_snippet text NOT NULL DEFAULT '',
  -- 若是人工重放，指向那条重放任务。
  replay_task_id uuid,

  occurred_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, delivery_id, attempt_no),

  FOREIGN KEY(tenant_id, delivery_id)
    REFERENCES platform_delivery_record(tenant_id, id) ON DELETE CASCADE,

  CHECK(btrim(attempt_source) <> '')
);

CREATE INDEX platform_delivery_attempt_by_delivery
  ON platform_delivery_attempt(tenant_id, delivery_id, attempt_no);

-- ---------------------------------------------------------------------------
-- 3. platform_delivery_dead_letter —— 死信
-- ---------------------------------------------------------------------------
--
-- 一行 = 一条死信（= 一条重试耗尽的投递）。
--
-- 🔴 UNIQUE(tenant_id, delivery_id)：**一个投递最多一条死信**。
--   死信重放之后若再次耗尽，**不新增行** —— 把老行改回 PENDING 重放，
--   否则死信箱里会出现同一个投递的多条死信，「还剩几条要重放」就答不对了。
--
-- ⚠️ expires_at 来自 18.1.2.1 策略里的 dead_letter_retention_days：
--   死信保留 N 天后过期。**过期清理**的动作归本模块（策略是 18.1.2.1 配的）。
CREATE TABLE platform_delivery_dead_letter (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  delivery_id uuid NOT NULL,

  -- 转入死信的原因（写清「最后一次为什么失败」）。
  reason varchar(1000) NOT NULL DEFAULT '',
  -- 转入死信时，那次失败尝试是第几次。
  failed_attempt_no int NOT NULL DEFAULT 0
    CHECK(failed_attempt_no >= 0),
  -- 状态：
  --   PENDING  还没人处理（可重放）
  --   REPLAYED 已经重放过（终态）
  --   EXPIRED  超过保留期（终态）
  status varchar(16) NOT NULL DEFAULT 'PENDING'
    CHECK(status IN ('PENDING','REPLAYED','EXPIRED')),
  -- 保留期到期时间（= 转入死信的时间 + 策略里的 dead_letter_retention_days）。
  expires_at timestamptz NOT NULL,
  -- 重放的时间与那条重放任务。
  replayed_at timestamptz,
  replay_task_id uuid,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, delivery_id),

  FOREIGN KEY(tenant_id, delivery_id)
    REFERENCES platform_delivery_record(tenant_id, id) ON DELETE CASCADE,

  CHECK(btrim(reason) <> '')
);

CREATE INDEX platform_delivery_dead_letter_by_status
  ON platform_delivery_dead_letter(tenant_id, status, created_at DESC);

CREATE INDEX platform_delivery_dead_letter_by_expiry
  ON platform_delivery_dead_letter(tenant_id, expires_at)
  WHERE status = 'PENDING';

-- ---------------------------------------------------------------------------
-- 4. platform_delivery_replay_task —— 重放任务
-- ---------------------------------------------------------------------------
--
-- 一行 = 一次**人工**重放（手动重投或死信重放）。蓝图说「输出：投递日志 + 重放任务」——
-- 重放任务就是这里。
--
-- ⚠️ 只有**人工**重放才建任务：自动重试是策略行为，它的每一次尝试都在 attempt 表里，
--   不需要再造一个「任务」。
CREATE TABLE platform_delivery_replay_task (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  delivery_id uuid NOT NULL,

  -- 触发方式：MANUAL（手动重投）/ DEAD_LETTER（死信重放）。
  trigger_type varchar(24) NOT NULL
    CHECK(trigger_type IN ('MANUAL','DEAD_LETTER')),
  -- 任务状态：PENDING（已受理）/ SUCCEEDED（重放成功）/ FAILED（重放失败）。
  status varchar(16) NOT NULL DEFAULT 'PENDING'
    CHECK(status IN ('PENDING','SUCCEEDED','FAILED')),
  -- 重放结果：拿到的响应码（0 = 没拿到响应）与一句话结论。
  result_status_code int NOT NULL DEFAULT 0,
  result_message varchar(1000) NOT NULL DEFAULT '',
  -- 人给的备注（为什么重放）。
  reason varchar(500) NOT NULL DEFAULT '',
  -- 触发人（**来自已认证会话**，不接受请求参数传入）。
  requested_by uuid,

  created_at timestamptz NOT NULL DEFAULT now(),
  finished_at timestamptz,

  UNIQUE(tenant_id, id),

  FOREIGN KEY(tenant_id, delivery_id)
    REFERENCES platform_delivery_record(tenant_id, id) ON DELETE CASCADE,

  CHECK(btrim(trigger_type) <> '')
);

CREATE INDEX platform_delivery_replay_task_by_delivery
  ON platform_delivery_replay_task(tenant_id, delivery_id, created_at DESC);

CREATE INDEX platform_delivery_replay_task_by_time
  ON platform_delivery_replay_task(tenant_id, created_at DESC);

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
-- 🔴 **投递记录是租户隔离的重点对象**：目标地址、事件载荷、响应体片段
--   全是租户私有事实 —— 跨租户可见等于把别人的业务数据与外部出口交出去。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'platform_delivery_record','platform_delivery_attempt',
    'platform_delivery_dead_letter','platform_delivery_replay_task'
  ] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

-- ===========================================================================
-- 6. 授权
-- ===========================================================================
--
-- 运行角色是 platform_app：它要能读自己的投递记录 / 投递日志 / 死信 / 重放任务，
-- 能写（建投递记录、记尝试、转死信、受理重放任务）。
--
-- 🔴 投递记录**不给 DELETE**：投递明细是「这件事发生过」的证据，
--   删掉它就等于让「投没投出去」变成一个答不出来的问题。
--   （与 18.1.2.1 的审计表「触发器直接拒绝 DELETE」同一纪律，
--   这里用**授权**直接不给，理由写在这里。）
GRANT SELECT,INSERT,UPDATE ON platform_delivery_record TO platform_app;
GRANT SELECT,INSERT,UPDATE ON platform_delivery_attempt TO platform_app;
GRANT SELECT,INSERT,UPDATE ON platform_delivery_dead_letter TO platform_app;
GRANT SELECT,INSERT,UPDATE ON platform_delivery_replay_task TO platform_app;
