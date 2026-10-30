-- ===========================================================================
-- V412 · event_subscription —— 18.1.2.1「事件订阅」
-- ===========================================================================
--
-- 蓝图 18-integration-and-automation.md 18.1.2.1 原文：
--
--   「**事件订阅**：对外提供事件订阅（webhook）：可订阅事件列表、订阅端点配置、签名密钥、
--   事件过滤、重试与死信策略。
--   输入：订阅配置；事件定义（schema 版本）。
--   输出：订阅记录；事件投递（带签名）。
--   关系：上游 = 业务事件（outbox）；下游 = 外部端点。
--   **事件 schema 必须版本化，破坏性变更要新增版本而非原地修改。**」
--
-- 落点：**platform 服务**（18 卷的后端归属服务，端口 8089，库 starcon_platform，
-- 运行角色 platform_app）。18.1.1.1「应用与凭证」V377、18.4.2.1「文本与翻译」V380、
-- 18.2.1.1「财务软件」V382 已经把服务骨架建好，本迁移是它的第四批表。
--
-- ---------------------------------------------------------------------------
-- 0. 这个模块**刻意不做**什么
-- ---------------------------------------------------------------------------
--
--   * **不做投递记录 / 投递日志**。
--     蓝图里「投递明细（事件 ID、订阅方、投递状态、响应码、重试次数、死信重放）」属于
--     **18.1.2.2「投递记录」**。本迁移只落**订阅配置侧**的六张表；
--     「事件投递（带签名）」这一段做成一个**显式的未接入端口**
--     （EventSubscriptionDeliveryPort + UnavailableEventSubscriptionDeliveryPort）：
--     端点返回 **409**，**一次投递都不发生**。
--     理由（照抄 platform 的 UnavailableFinanceSoftwarePushPort 的判断）：
--     一条返回「投递成功」的假通道，会让「对方系统已经收到并处理了这个事件」
--     在数据上看起来发生过 —— 而现场一个包都没发出去。
--     本迁移**只提供订阅配置侧**，投递执行与投递日志**留出接口即可**。
--
--   * **不把「可订阅事件列表」写死成一份常量清单**。
--     真值落在 platform_webhook_event_type 这张**注册表**里，每行都带
--     source_service + source_artifact（出处文件:行）。
--     ⚠️ `backend/libraries/` 下**没有** event-envelope 库（只有 tenancy），
--     所以事件定义的**真相源只能是本表**；业务服务真正会发出的事件类型字面量
--     记录在 `source_artifact` 里（例如 catalog 的
--     `SkuProfileSaved` 出自 backend/services/catalog/src/main/java/.../SkuDetailService.java）。
--     ⇒ 「可订阅事件列表」端点**真查这张表**，不是返回一份假数组。
--
--   * **不预置任何一行事件类型**。
--     同一个租户订阅的事件集合由租户自己登记（登记时写清出处）；
--     系统零预置行 ⇒ 不会有一份「看起来很全、其实没人保证会发」的清单。
--
-- ---------------------------------------------------------------------------
-- 1. 表清单
-- ---------------------------------------------------------------------------
--
--   platform_webhook_event_type        —— 事件定义注册表（= 可订阅事件列表）
--   platform_webhook_subscription      —— 订阅端点（挂在某个 platform_app 上）
--   platform_webhook_subscription_event —— 事件过滤（订阅 ↔ (事件类型, schema 版本) + 过滤条件）
--   platform_webhook_signing_secret    —— 签名密钥（密文落库，明文只在生成那一次返回）
--   platform_webhook_policy            —— 重试与死信**策略配置**
--   platform_webhook_audit_event       —— 订阅配置变更审计（追加式）
--
--   六张表全部 tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation + GRANT platform_app。
--
-- ---------------------------------------------------------------------------
-- 2. 「破坏性变更要新增版本而非原地修改」落到**物理约束**
-- ---------------------------------------------------------------------------
--
--   蓝图这句加粗的话如果不落到数据层，就只是一句口号：有人在后台把
--   payload_schema 改掉一个字段，已经按老 schema 对接好的订阅方当场就炸。
--
--   ⇒ platform_webhook_event_type 上挂一个 BEFORE UPDATE 触发器：
--     **event_type / schema_version / payload_schema 三者一律不许改**
--     （触发器直接 RAISE restrict_violation）。
--     要改结构就**登记新版本**（同 event_type、新 schema_version）；
--     老版本**保留**，只是可以标 DEPRECATED（不再可被新订阅选中，
--     但已经订阅它的订阅方仍按老 schema 收 —— 这是版本化的全部意义）。
--
-- ---------------------------------------------------------------------------
-- 3. 签名密钥：真随机、密文落库、明文只返回一次
-- ---------------------------------------------------------------------------
--
--   * 密钥用 SecureRandom 生成（256 bit 熵），**不是**可推导的值。
--   * 落库的是 **AES-256-GCM 密文**（Base64(nonce|ciphertext||tag)），
--     主密钥由 platform 已有的 INTERNAL_KEY 经 **HKDF-SHA256 派生**
--     （不新增必填 env —— 新增一个必填变量会让没配该变量的部署直接启动失败）。
--   * **明文只在「生成 / 轮换」那一次响应里出现**；之后任何读路径都取不回原文
--     （读接口只给 secret_hint 末四位）。
--   * 内部解密**只用于算签名**（给 18.1.2.2 的投递执行器用），不是给外部读的。
--
-- ---------------------------------------------------------------------------
-- 4. 时间与金额
-- ---------------------------------------------------------------------------
--
--   * 时间列一律 **timestamptz**；服务层用 **OffsetDateTime** 绑定并在 values 位置写
--     `?::timestamptz`（用 Timestamp.valueOf(LocalDateTime) 会静默偏 −28800s）。
--   * 本模块**不落任何金额**。
--   * 数量（重试次数、超时秒数、保留天数）是**计数**用 int；
--     退避**倍数**是比率，用 numeric(8,2)（禁浮点）。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. platform_webhook_event_type —— 事件定义注册表（可订阅事件列表）
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个「事件类型 + schema 版本」的**已登记定义**。
--
-- 🔴 **UNIQUE(tenant_id, event_type, schema_version)**：同一个事件类型可以有多个版本，
--   但同一版本只能登记一次（否则订阅过滤会指向两个不同的 schema）。
--
-- 🔴 **本表零预置行**：登记是租户的动作，出处（source_service / source_artifact）
--   是登记时必须写的事实 —— 「这个事件真的由哪个服务发出、代码在哪」不可编造，
--   所以它是**必填**列而不是可选项。
CREATE TABLE platform_webhook_event_type (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 事件类型字面量（与业务服务 outbox 里写的那个字符串**逐字相同**）。
  event_type varchar(64) NOT NULL,
  -- schema 版本（同一 event_type 的第 N 版结构）。
  schema_version int NOT NULL DEFAULT 1
    CHECK(schema_version >= 1),
  -- 状态：
  --   ACTIVE       当前可被新订阅选中
  --   DEPRECATED   已被新版本取代，**不再可被新订阅选中**，但已订阅的仍按老版本收
  status varchar(16) NOT NULL DEFAULT 'ACTIVE'
    CHECK(status IN ('ACTIVE','DEPRECATED')),

  -- 事件载荷的 JSON Schema（版本化契约本体）。
  payload_schema jsonb NOT NULL DEFAULT '{}'::jsonb,

  -- 出处：这个事件**真由哪个服务发出**。
  source_service varchar(32) NOT NULL
    CHECK(btrim(source_service) <> ''),
  -- 出处：代码位置（文件:行），供人核对「这个字面量确实在那儿」。
  source_artifact varchar(240) NOT NULL
    CHECK(btrim(source_artifact) <> ''),

  registered_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, event_type, schema_version),

  CHECK(btrim(event_type) <> '')
);

CREATE INDEX platform_webhook_event_type_by_status
  ON platform_webhook_event_type(tenant_id, status, event_type);

-- 🔴 「破坏性变更要新增版本而非原地修改」的**物理约束**：
--   event_type / schema_version / payload_schema 三者一旦登记就不许改。
--   要改结构 ⇒ 登记新版本；老版本保留（可标 DEPRECATED）。
CREATE OR REPLACE FUNCTION platform_webhook_event_type_immutable() RETURNS trigger AS $$
BEGIN
  IF NEW.event_type IS DISTINCT FROM OLD.event_type
     OR NEW.schema_version IS DISTINCT FROM OLD.schema_version
     OR NEW.payload_schema IS DISTINCT FROM OLD.payload_schema THEN
    RAISE EXCEPTION
      '事件 schema 是版本化契约：event_type/schema_version/payload_schema 登记后不可原地修改（% v%）；破坏性变更请登记新版本',
      OLD.event_type, OLD.schema_version
      USING ERRCODE = 'restrict_violation';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER platform_webhook_event_type_immutable_trg
  BEFORE UPDATE ON platform_webhook_event_type
  FOR EACH ROW EXECUTE FUNCTION platform_webhook_event_type_immutable();

-- ---------------------------------------------------------------------------
-- 2. platform_webhook_subscription —— 订阅端点
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个「把某类事件推到某个外部地址」的订阅配置。
--
-- 🔴 **app_id 必填且是真 FK**（platform_app）：webhook 凭据必须归属一个已注册应用，
--   否则「谁在收我们的事件」无法回答。跨服务不建 FK，但 platform_app 在**本服务**内 ⇒ 建真 FK。
CREATE TABLE platform_webhook_subscription (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  app_id uuid NOT NULL,

  -- 订阅名（同一应用内唯一）。
  name varchar(120) NOT NULL,
  -- 外部端点地址（下游）。
  target_url varchar(500) NOT NULL,
  -- 状态：
  --   ACTIVE    生效
  --   PAUSED   暂停（配置保留，暂时不投递）
  --   DISABLED 停用（终态）
  status varchar(16) NOT NULL DEFAULT 'ACTIVE'
    CHECK(status IN ('ACTIVE','PAUSED','DISABLED')),

  description varchar(500) NOT NULL DEFAULT '',

  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, app_id, name),

  FOREIGN KEY(tenant_id, app_id)
    REFERENCES platform_app(tenant_id, id),

  CHECK(btrim(name) <> ''),
  CHECK(btrim(target_url) <> ''),
  -- 🔴 只允许 http / https：写别的协议进去，签名密钥与事件载荷可能被送到非预期的地方。
  CHECK(target_url ~ '^https?://')
);

CREATE INDEX platform_webhook_subscription_by_app
  ON platform_webhook_subscription(tenant_id, app_id, status);

-- ---------------------------------------------------------------------------
-- 3. platform_webhook_subscription_event —— 事件过滤
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个订阅对某个 (event_type, schema_version) 的订阅关系 + 过滤条件。
--
-- 🔴 **对注册表建真 FK**：(tenant_id, event_type, schema_version) 指向
--   platform_webhook_event_type ⇒ **不能订阅一个没登记的事件**
--   （订阅一个不存在的 schema，等于让对接方按一份不存在的契约写代码）。
--
-- 🔴 filter 是 **JSON Schema 风格的过滤条件**（jsonb）：订阅方只收符合条件的事件。
--   语义约定由服务层写死在契约里（见 EventSubscriptionModels），
--   数据库只负责「它必须是合法 JSON 对象」。
CREATE TABLE platform_webhook_subscription_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  subscription_id uuid NOT NULL,

  -- 被订阅的事件（软引用业务服务的 outbox，指向注册表）。
  event_type varchar(64) NOT NULL,
  schema_version int NOT NULL DEFAULT 1
    CHECK(schema_version >= 1),

  -- 过滤条件（jsonb 对象）。空对象 = 不额外过滤（该事件的全部实例都收）。
  filter jsonb NOT NULL DEFAULT '{}'::jsonb,
  enabled boolean NOT NULL DEFAULT true,

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, subscription_id, event_type, schema_version),

  FOREIGN KEY(tenant_id, subscription_id)
    REFERENCES platform_webhook_subscription(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id, event_type, schema_version)
    REFERENCES platform_webhook_event_type(tenant_id, event_type, schema_version),

  CHECK(btrim(event_type) <> ''),
  -- 过滤条件必须是 **JSON 对象**（不是数组、不是标量）。
  CHECK(jsonb_typeof(filter) = 'object')
);

CREATE INDEX platform_webhook_subscription_event_by_subscription
  ON platform_webhook_subscription_event(tenant_id, subscription_id, enabled);

-- ---------------------------------------------------------------------------
-- 4. platform_webhook_signing_secret —— 签名密钥
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个订阅的某一版签名密钥。
--
-- 🔴 **库���只有密文**：secret_ciphertext = Base64(nonce(12B) || ciphertext || tag(16B))，
--   AES-256-GCM。主密钥由 INTERNAL_KEY 经 HKDF-SHA256 派生。
--   **明文只在「生成 / 轮换」那一次响应里出现一次**，之后任何读路径都取不回原文。
--
-- 🔴 **一个订阅同时只允许一个 ACTIVE 密钥**（部分唯一索引）：
--   两把同时生效的密钥会让「用哪把验签」变成不确定的事。
--   轮换时旧密钥转 ROTATED（保留痕迹，不删）。
CREATE TABLE platform_webhook_signing_secret (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  subscription_id uuid NOT NULL,

  -- 版本号（同一个订阅内递增）。
  version int NOT NULL DEFAULT 1
    CHECK(version >= 1),
  -- 状态：
  --   ACTIVE   当前生效（验签用这一把）
  --   ROTATED  已轮换（历史痕迹）
  --   REVOKED  已吊销（泄露止血）
  status varchar(16) NOT NULL DEFAULT 'ACTIVE'
    CHECK(status IN ('ACTIVE','ROTATED','REVOKED')),

  -- 密文（Base64(nonce|ciphertext||tag)）—— **库里没有明文**。
  secret_ciphertext text NOT NULL,
  -- 末四位提示（只用于让人认出「这是哪一把」，不可用于验签）。
  secret_hint varchar(16) NOT NULL,

  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  rotated_at timestamptz,

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, subscription_id, version),

  FOREIGN KEY(tenant_id, subscription_id)
    REFERENCES platform_webhook_subscription(tenant_id, id) ON DELETE CASCADE,

  CHECK(btrim(secret_ciphertext) <> ''),
  CHECK(length(secret_hint) >= 4)
);

-- 🔴 一个订阅同时只允许一把 ACTIVE 密钥。
CREATE UNIQUE INDEX platform_webhook_signing_secret_one_active
  ON platform_webhook_signing_secret(tenant_id, subscription_id)
  WHERE status = 'ACTIVE';

-- ---------------------------------------------------------------------------
-- 5. platform_webhook_policy —— 重试与死信**策略配置**
-- ---------------------------------------------------------------------------
--
-- 一行 = 一份投递策略。**本表只是配置**，执行归 18.1.2.2。
--
-- 🔴 **subscription_id 可空**：
--   · 为空 = **租户级默认策略**（本租户所有订阅的兜底）
--   · 非空 = 该订阅的**专属策略**（覆盖默认）
--   两张部分唯一索引分别保证「每个订阅最多一份专属策略」「每租户最多一份默认策略」。
--
-- ⚠️ 这里面**没有**「当前重试次数」「死信条数」这类**运行态**字段 ——
--   那是 18.1.2.2「投递记录」的表。本表只记**策略参数**。
CREATE TABLE platform_webhook_policy (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 为空 = 租户级默认策略；非空 = 该订阅的专属策略。
  subscription_id uuid,

  -- 一次投递最多尝试几次（含首次）。
  max_attempts int NOT NULL DEFAULT 5
    CHECK(max_attempts BETWEEN 1 AND 20),
  -- 首次退避秒数。
  initial_backoff_seconds int NOT NULL DEFAULT 30
    CHECK(initial_backoff_seconds BETWEEN 1 AND 86400),
  -- 退避倍数（numeric，禁浮点）：第 n 次退避 = initial * multiplier^(n-1)。
  backoff_multiplier numeric(8,2) NOT NULL DEFAULT 2.00
    CHECK(backoff_multiplier >= 1.00 AND backoff_multiplier <= 10.00),
  -- 单次退避上限（秒）。
  max_backoff_seconds int NOT NULL DEFAULT 3600
    CHECK(max_backoff_seconds BETWEEN 1 AND 86400),
  -- 单次 HTTP 请求超时（秒）。
  request_timeout_seconds int NOT NULL DEFAULT 10
    CHECK(request_timeout_seconds BETWEEN 1 AND 120),
  -- 死信保留天数（**策略**：超过就清；真清的动作在 18.1.2.2）。
  dead_letter_retention_days int NOT NULL DEFAULT 30
    CHECK(dead_letter_retention_days BETWEEN 1 AND 365),
  -- 签名算法（**只有** HMAC-SHA256 一档；写死是为了让「验签方」只有一种算法要实现）。
  signature_algorithm varchar(16) NOT NULL DEFAULT 'HMAC_SHA256'
    CHECK(signature_algorithm IN ('HMAC_SHA256')),
  -- 签名头名称（对方按这个名字取签名）。
  signature_header varchar(64) NOT NULL DEFAULT 'X-Starcon-Signature'
    CHECK(btrim(signature_header) <> ''),

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),

  -- subscription_id 为空时 MATCH SIMPLE 判定该 FK 不生效 ⇒ 租户默认策略不受影响。
  FOREIGN KEY(tenant_id, subscription_id)
    REFERENCES platform_webhook_subscription(tenant_id, id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX platform_webhook_policy_per_subscription
  ON platform_webhook_policy(tenant_id, subscription_id)
  WHERE subscription_id IS NOT NULL;

CREATE UNIQUE INDEX platform_webhook_policy_tenant_default
  ON platform_webhook_policy(tenant_id)
  WHERE subscription_id IS NULL;

-- ---------------------------------------------------------------------------
-- 6. platform_webhook_audit_event —— 订阅配置变更审计（追加式）
-- ---------------------------------------------------------------------------
--
-- 一行 = 一次订阅配置变更的留痕。
--
-- 🔴 **只追加**：触发器直接挡掉 UPDATE / DELETE。
--   审计一旦可改就没有意义 —— 能改审计的人也能改掉自己越权的痕迹。
--
-- ⚠️ subscription_id 是**软引用**（不建 FK）：订阅被删之后审计行必须**留着**
--   （「它存在过、被谁建过、被谁删过」是审计的核心价值）。
CREATE TABLE platform_webhook_audit_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  -- 软引用：订阅被删后审计行仍在。
  subscription_id uuid,

  -- 动作（SUBSCRIPTION_CREATED / SIGNING_SECRET_ROTATED / POLICY_UPDATED / ...）。
  action varchar(48) NOT NULL
    CHECK(btrim(action) <> ''),
  -- 触发人（来自已认证会话，不接受请求参数传入）。
  actor_user_id uuid,
  -- 目标对象类型与 id（订阅 / 事件类型 / 策略）。
  target_type varchar(32) NOT NULL DEFAULT '',
  target_id uuid,
  -- 细节（jsonb 对象）。
  detail jsonb NOT NULL DEFAULT '{}'::jsonb,

  occurred_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),

  CHECK(btrim(action) <> '')
);

CREATE INDEX platform_webhook_audit_event_by_tenant
  ON platform_webhook_audit_event(tenant_id, occurred_at DESC);

CREATE INDEX platform_webhook_audit_event_by_subscription
  ON platform_webhook_audit_event(tenant_id, subscription_id, occurred_at DESC);

CREATE OR REPLACE FUNCTION platform_webhook_audit_event_append_only() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION '订阅配置审计是追加式的：不允许 UPDATE / DELETE'
    USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER platform_webhook_audit_event_no_update
  BEFORE UPDATE ON platform_webhook_audit_event
  FOR EACH ROW EXECUTE FUNCTION platform_webhook_audit_event_append_only();

CREATE TRIGGER platform_webhook_audit_event_no_delete
  BEFORE DELETE ON platform_webhook_audit_event
  FOR EACH ROW EXECUTE FUNCTION platform_webhook_audit_event_append_only();

-- ===========================================================================
-- 7. 多租户：RLS + 授权
-- ===========================================================================
--
-- 六张业务表一视同仁 ENABLE + FORCE。租户由 backend/libraries/tenancy 在事务内设置，
-- 客户端的 X-Tenant-Id 头进不来也改不动它。
--
-- FORCE 是必须的：表 owner 在 FORCE 下也受策略约束，否则迁移脚本或后台任务用 owner
-- 身份一连就绕过了隔离（与 identity V330 / finance V361 / platform V377 / V380 / V382 同纪律）。
--
-- ⚠️ policy 只认 `app.current_tenant` 这个**事务局部** GUC，它由
--    TenantTransactions.set_config(..., true) 绑定 —— 事务结束即失效，
--    连接归还池中时**不留残留**（这正是 AGENTS.md 第三条隔离断言要验的）。
--
-- ⚠️ **订阅端点与签名密钥是租户隔离的重点对象**：外部端点 URL 与签名密钥
--    都是租户私有事实，跨租户可见等于把别人的数据出口与验签密钥交出去。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY[
   'platform_webhook_event_type','platform_webhook_subscription',
   'platform_webhook_subscription_event','platform_webhook_signing_secret',
   'platform_webhook_policy','platform_webhook_audit_event'
 ] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

-- ===========================================================================
-- 8. 授权
-- ===========================================================================
--
-- 运行角色是 platform_app：它需要能读自己的事件定义注册表 / 订阅 / 事件过滤 /
-- 签名密钥 / 策略 / 审计，能写（登记事件定义、建订阅、配过滤、发轮换密钥、配策略），
-- 能删（删订阅 —— 其下的过滤与密钥随之级联）。
--
-- 🔴 审计表也给了 DELETE 权限，但**表上的触发器会直接拒绝** ——
--   授权与约束各管一半：要能写的表都得授权，要写不动的行为由触发器兜住。
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_webhook_event_type TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_webhook_subscription TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_webhook_subscription_event TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_webhook_signing_secret TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_webhook_policy TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_webhook_audit_event TO platform_app;
