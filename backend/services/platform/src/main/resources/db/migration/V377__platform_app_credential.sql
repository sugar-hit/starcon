-- ===========================================================================
-- V377 · platform_app_credential —— 18 集成开放与智能自动化服务的第一批表
-- ===========================================================================
--
-- **服务引导 + 18.1.1.1 应用与凭证**（蓝图 18-integration-and-automation.md）：
--
--   「管理第三方接入的应用：应用注册、API Key/Secret 生成与轮换、授权范围（可访问的资源与操作）、
--     IP 白名单、调用配额。
--     输入：应用注册；凭证轮换；授权范围配置。
--     输出：应用档案 + 凭证（**明文仅在生成时展示一次**，之后只存摘要）。
--     关系：上游 = 17 权限；下游 = 18.1.1.2 接口目录。
--     **对外凭证与平台凭据同等对待**：不落库明文、访问有审计、泄露可一键吊销。」
--
-- ---------------------------------------------------------------------------
-- 0. 这个服务**刻意不做**什么（卷 18 卷首「不做」原文）
-- ---------------------------------------------------------------------------
--
--   * **不提供任何绕过平台官方 API 的模拟登录能力**（合规红线）。
--     ⇒ 本文件里**没有任何一张表**存「账号 / 密码 / Cookie / 模拟登录态」。
--       18.2 的外部系统对接只能走对方官方 API 与 OAuth，本服务不提供绕路的凭据存储。
--
--   * **不代替用户做会计与经营决策**、**不把 AI 结果直接写为业务事实**（本期不涉及 AI）。
--
--   * **对外 API 严格按租户隔离 + 权限校验 + 限流 + 审计**（卷 18 卷首红线）。
--     ⇒ 本文件是这条红线的**第一道实现**：
--         租户隔离 —— 七张表全部 tenant_id NOT NULL + ENABLE **与** FORCE RLS isolation；
--         权限校验 —— 服务层用 platform.app.* 权限码（见 AccessService）；
--         限流     —— platform_app_quota（配额配置）+ platform_app_quota_usage（窗口用量）；
--         审计     —— platform_app_audit_event（追加不可改）。
--
--   * **对外凭证与平台凭据同等对待**：不落库明文、访问有审计、泄露可一键吊销。
--     ⇒ 明文 API Key / Secret **只在生成时返回一次**，库里只存摘要；
--       吊销是**一键**的（吊销应用 ⇒ 该应用全部凭证同时失效）。
--
-- ---------------------------------------------------------------------------
-- 1. 表清单
-- ---------------------------------------------------------------------------
--
--   platform_app               —— 应用档案（注册；租户内 app_code 唯一）
--   platform_app_credential    —— 凭证（**只存摘要**；轮换链；吊销）
--   platform_app_scope         —— 授权范围（可访问的**资源** × **操作**）
--   platform_app_ip_allowlist  —— IP 白名单（CIDR）
--   platform_app_quota         —— 调用配额**配置**（窗口类型 × 上限）
--   platform_app_quota_usage   —— 调用配额**窗口用量**（窗口起点 × 已用量）
--   platform_app_audit_event   —— 审计留痕（**追加不可改不可删**）
--
--   七张表全部 tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation + GRANT platform_app。
--
-- ---------------------------------------------------------------------------
-- 2. 「不落库明文」怎么落到数据层
-- ---------------------------------------------------------------------------
--
--   * `platform_app_credential` 里**没有一个列**存 API Key / Secret 的明文：
--       api_key_digest  —— **BCrypt** 摘要（与 identity 的密码摘要同一形状）；
--       api_key_lookup  —— SHA-256 十六进制摘要，**只用于按值定位这一行**；
--       secret_digest   —— **BCrypt** 摘要；
--       api_key_prefix  —— 展示用前缀（如 sk_live_9f2a），**非机密**，只给人眼分辨用；
--       secret_hint     —— 末四位，只给人眼分辨用。
--
--   * 为什么 API Key 要多一个 `api_key_lookup`（SHA-256）而 Secret 不要：
--       **BCrypt 加盐且慢，无法按值定位**——只有 Secret 的话，网关拿到一个 API Key
--       根本不知道该去比哪一行。API Key 是 **32 字节 CSPRNG 随机串（256 bit）**，
--       它不是「低熵口令」，SHA-256 摘要**不可暴力枚举**，拿来做定位索引是安全的。
--       ⚠️ 若将来允许运营**手工指定** API Key（熵降下来），必须同时把这一列改成
--       HMAC-SHA256 —— 那时 SHA-256 就变成可离线枚举的口令哈希了。
--       Secret 是**唯一真正的机密**，它只走 BCrypt，**永远不落任何可定位的摘要**。
--
--   * 明文只在**服务层的响应里**出现一次（生成 / 轮换那一次），之后再也读不出来 ——
--     这是「明文仅在生成时展示一次」的物理形态，不是靠前端自觉。
--
-- ---------------------------------------------------------------------------
-- 3. 「泄露可一键吊销」怎么落到数据层
-- ---------------------------------------------------------------------------
--
--   * 凭证状态机：ACTIVE → ROTATED（被轮换取代）/ REVOKED（被吊销）。
--     轮换不是改写旧行，而是**追加新版本**并把旧行收口 —— 旧摘要留在库里，
--     争议时能查清「哪一版在什么时间点有效过」。
--   * `platform_app.status = 'REVOKED'` ⇒ 该应用下**全部**凭证立即失效（服务层一次 UPDATE）。
--     吊销是**幂等**的：重复吊销不报错，状态与时间戳不漂移。
--
-- ---------------------------------------------------------------------------
-- 4. 金额与数量
-- ---------------------------------------------------------------------------
--
--   * 本模块**不落任何金额**：没有单价、费用、账单、结算 —— 对外 API 的计费属 18.1/18.2 的
--     商业条款，不在本模块。测试用 information_schema 断言本批表**无金额语义列**。
--   * 数量一律 `numeric(19,6)`（配额上限、窗口用量），**禁浮点**：本文件没有一处 double。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. platform_app —— 应用档案
-- ---------------------------------------------------------------------------
--
-- **一个应用 = 一个第三方接入方**（ERP、金蝶、WMS、ISV、自研对接…）。
-- 应用是「档案」，凭证、授权范围、IP 白名单、配额都挂在它下面。
--
-- 🔴 **app_code 是租户内的稳定标识**：对外 API 的调用方要拿它来标识自己，
--   改了会让对端的配置全部失效 ⇒ 一旦创建**不建议改**（本模块没有改 app_code 的端点）。
--
-- 🔴 **系统零预置行**：应用是租户自己的接入方，系统不替用户登记一个「默认应用」。
CREATE TABLE platform_app (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 应用标识：租户内唯一。给对端配置用，一经创建不改。
  app_code varchar(64) NOT NULL,
  app_name varchar(120) NOT NULL,

  -- 应用类型：
  --   INTERNAL       平台自研对接（我们自己写的那一套客户端）
  --   THIRD_PARTY   第三方系统（对方提供 SDK）
  --   PARTNER       生态伙伴（ISV / 服务商）
  app_type text NOT NULL DEFAULT 'THIRD_PARTY'
    CHECK(app_type IN ('INTERNAL','THIRD_PARTY','PARTNER')),

  description varchar(500) NOT NULL DEFAULT '',

  -- 负责人与联系信息：来自已认证会话的 user_id，**不接受请求参数传入**。
  owner_user_id uuid,
  contact_name varchar(80) NOT NULL DEFAULT '',
  contact_email varchar(160) NOT NULL DEFAULT '',

  -- 应用状态：
  --   ACTIVE      正常
  --   SUSPENDED   暂停（凭证仍然存在，但网关应当拒绝 —— 判定由网关侧读本服务的状态）
  --   REVOKED     已吊销（**一键吊销**：该应用全部凭证同时失效）
  status text NOT NULL DEFAULT 'ACTIVE'
    CHECK(status IN ('ACTIVE','SUSPENDED','REVOKED')),

  revoked_at timestamptz,
  revoke_reason varchar(500) NOT NULL DEFAULT '',

  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 租户内应用标识唯一：两个应用不能同名同码，否则对端无法区分调用方。
  UNIQUE(tenant_id, app_code),

  CHECK(btrim(app_code) <> '' AND length(app_code) <= 64),
  CHECK(btrim(app_name) <> '' AND length(app_name) <= 120),
  -- 「已吊销」与「吊销时刻」必须同时成立：只改一个就是状态与事实不一致。
  CHECK((status = 'REVOKED') = (revoked_at IS NOT NULL))
);

CREATE INDEX platform_app_by_name ON platform_app(tenant_id, app_name);

-- ---------------------------------------------------------------------------
-- 2. platform_app_credential —— 凭证（**只存摘要**）
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个应用的一个**凭证版本**。轮换 = 追加新版本 + 旧版本收口，不是改写。
--
-- 🔴 **库里没有任何明文**：见本文件第 2 节的说明。
-- 🔴 `api_key_lookup` 是**唯一**能按值定位的列，Secret 侧**没有**这样的列 ——
--   Secret 永远只能「拿到候选行之后逐行 BCrypt 比对」。
CREATE TABLE platform_app_credential (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  app_id uuid NOT NULL,

  -- 同一应用内的凭证版本号：从 1 递增。轮换链靠它排序。
  version int NOT NULL DEFAULT 1,

  -- 凭证状态：
  --   ACTIVE    当前生效
  --   ROTATED   已被更新的版本取代（仍留痕，便于争议时回溯哪一版有效过）
  --   REVOKED   已吊销（泄露时的一键止血）
  status text NOT NULL DEFAULT 'ACTIVE'
    CHECK(status IN ('ACTIVE','ROTATED','REVOKED')),

  -- 展示用前缀（非机密）：如 sk_live_9f2a。人眼分辨用，不参与任何校验。
  api_key_prefix varchar(24) NOT NULL,
  -- BCrypt 摘要（与 identity 的密码摘要同一形状）。
  api_key_digest varchar(100) NOT NULL,
  -- SHA-256 十六进制摘要：**只**用于按值定位这一行（BCrypt 加盐，无法定位）。
  api_key_lookup varchar(64) NOT NULL,
  -- Secret 的展示提示（末四位，非机密）。
  secret_hint varchar(16) NOT NULL,
  -- BCrypt 摘要：Secret 是唯一真正的机密，只走 BCrypt。
  secret_digest varchar(100) NOT NULL,

  -- 生效与失效时刻。expires_at 为空 = 长期有效（由运营显式吊销来止血）。
  issued_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz,

  -- 轮换链：本行取代了哪一行（旧凭证 id）。
  rotated_from_id uuid,

  revoked_at timestamptz,
  revoke_reason varchar(500) NOT NULL DEFAULT '',
  -- 最近一次被网关验签通过的时刻（null = 从未被使用过）。
  last_used_at timestamptz,

  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一应用内版本号唯一。
  UNIQUE(tenant_id, app_id, version),
  -- 同一租户内 API Key 不可重复（定位列的唯一索引）。
  UNIQUE(tenant_id, api_key_lookup),

  FOREIGN KEY(tenant_id, app_id) REFERENCES platform_app(tenant_id, id) ON DELETE CASCADE,

  CHECK(version >= 1),
  CHECK(btrim(api_key_prefix) <> ''),
  CHECK(btrim(secret_hint) <> ''),
  -- 「已吊销」与「吊销时刻」必须同时成立。
  CHECK((status = 'REVOKED') = (revoked_at IS NOT NULL))
);

-- 轮换链：查某一行是被哪一行取代的。
CREATE INDEX platform_app_credential_by_app
  ON platform_app_credential(tenant_id, app_id, version DESC);

-- ---------------------------------------------------------------------------
-- 3. platform_app_scope —— 授权范围（可访问的**资源** × **操作**）
-- ---------------------------------------------------------------------------
--
-- 🔴 **这是「对外应用能访问什么」，不是平台内部的 RBAC 权限码**：
--   `platform.app.read` 那一套是**平台内部**谁能管理应用；
--   这里的 `resource_code × action_code` 是**外部调用方**能调哪些业务资源。
--   两套码**刻意不共用命名空间**，混在一起就会出现「内部管理员权限 = 对外开放范围」的错。
--
-- 一行 = 一个 (应用, 资源, 操作)。同一组合只能有一行（重复授权是幂等的，不是叠加）。
CREATE TABLE platform_app_scope (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  app_id uuid NOT NULL,

  -- 资源码：如 order / product / inventory / shipment。
  resource_code varchar(64) NOT NULL,
  -- 操作：READ 读 / WRITE 写。
  action_code text NOT NULL CHECK(action_code IN ('READ','WRITE')),

  granted_at timestamptz NOT NULL DEFAULT now(),
  granted_by uuid,

  UNIQUE(tenant_id, id),
  -- 同一 (应用, 资源, 操作) 只有一行：重复授权幂等，不叠加。
  UNIQUE(tenant_id, app_id, resource_code, action_code),

  FOREIGN KEY(tenant_id, app_id) REFERENCES platform_app(tenant_id, id) ON DELETE CASCADE,

  CHECK(btrim(resource_code) <> '')
);

CREATE INDEX platform_app_scope_by_app ON platform_app_scope(tenant_id, app_id);

-- ---------------------------------------------------------------------------
-- 4. platform_app_ip_allowlist —— IP 白名单
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个允许来源的 CIDR。
--
-- 🔴 **空列表 = 不限制**（而不是「全部拒绝」）：这是本模块与「白名单为空即拒绝」的
--   常见做法**刻意相反**的选择 —— 因为「限制来源」是收紧动作，
--   默认收紧会让一个刚注册、还没配白名单的应用**完全不可用**，而运营的第一反应
--   是「配了白名单也不通」，很难排查。白名单里**有**条目时，才按条目匹配。
--   这个口径写在服务层与前端提示里，不靠人记。
CREATE TABLE platform_app_ip_allowlist (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  app_id uuid NOT NULL,

  -- CIDR：IPv4（1.2.3.4/32）或 IPv6（2001:db8::/32）。
  cidr varchar(64) NOT NULL,
  note varchar(200) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),
  created_by uuid,

  UNIQUE(tenant_id, id),
  -- 同一应用内同一段 CIDR 只有一行。
  UNIQUE(tenant_id, app_id, cidr),

  FOREIGN KEY(tenant_id, app_id) REFERENCES platform_app(tenant_id, id) ON DELETE CASCADE,

  CHECK(btrim(cidr) <> '' AND length(cidr) <= 64)
);

CREATE INDEX platform_app_ip_allowlist_by_app ON platform_app_ip_allowlist(tenant_id, app_id);

-- ---------------------------------------------------------------------------
-- 5. platform_app_quota —— 调用配额**配置**
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个 (应用, 窗口类型) 的上限。
--
-- 🔴 **窗口类型是「滚动窗口」的近似**：窗口起点由服务层按 UTC 截断计算
--   （SECOND→整秒 / MINUTE→整分 / HOUR→整点 / DAY→零点 / MONTH→月初）。
--   滚动窗口与固定窗口的差别对「限流」这一用途不敏感，但**口径必须写明**，
--   否则运营看到「配额已用 100%」会以为是精确到秒的滚动窗口。
--
-- 🔴 **系统零预置行**：不替用户默认一个「1000 次/天」。
--   没有配额的窗口 = 该窗口**不限**（不是「限 0 次」）。
CREATE TABLE platform_app_quota (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  app_id uuid NOT NULL,

  window_type text NOT NULL
    CHECK(window_type IN ('SECOND','MINUTE','HOUR','DAY','MONTH')),
  -- 上限：数量，NUMERIC(19,6)，禁浮点。
  limit_count numeric(19,6) NOT NULL,
  enabled boolean NOT NULL DEFAULT true,

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一应用同一窗口类型只有一行。
  UNIQUE(tenant_id, app_id, window_type),

  FOREIGN KEY(tenant_id, app_id) REFERENCES platform_app(tenant_id, id) ON DELETE CASCADE,

  CHECK(limit_count >= 0)
);

-- ---------------------------------------------------------------------------
-- 6. platform_app_quota_usage —— 调用配额**窗口用量**
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个 (应用, 窗口类型, 窗口起点) 的已用量。
--
-- 这是**计数账本**，由网关在调用后回报（服务层的内部密钥端点写入）。
-- 写入是**累加**（UPSERT + 加法），不是覆盖 —— 覆盖会把并发回报丢掉。
CREATE TABLE platform_app_quota_usage (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  app_id uuid NOT NULL,

  window_type text NOT NULL
    CHECK(window_type IN ('SECOND','MINUTE','HOUR','DAY','MONTH')),
  -- 窗口起点（UTC 截断）。与 window_type 一起唯一确定一个窗口。
  window_started_at timestamptz NOT NULL,
  -- 已用量：数量，NUMERIC(19,6)，禁浮点。
  used_count numeric(19,6) NOT NULL DEFAULT 0,

  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 同一 (应用, 窗口, 窗口起点) 只有一行：回报是累加，不是追加新行。
  UNIQUE(tenant_id, app_id, window_type, window_started_at),

  FOREIGN KEY(tenant_id, app_id) REFERENCES platform_app(tenant_id, id) ON DELETE CASCADE,

  CHECK(used_count >= 0)
);

CREATE INDEX platform_app_quota_usage_by_app
  ON platform_app_quota_usage(tenant_id, app_id, window_started_at DESC);

-- ---------------------------------------------------------------------------
-- 7. platform_app_audit_event —— 审计留痕（**追加不可改不可删**）
-- ---------------------------------------------------------------------------
--
-- 「访问有审计」落成这张表：应用注册、凭证生成 / 轮换 / 吊销、授权范围与白名单与配额的变更、
-- 凭证被读取（访问审计），全部追加一行。
--
-- 🔴 **只追加**：UPDATE / DELETE 一律被触发器拒绝。理由与 audit_log 同源 ——
--   能改的审计记录等于没有审计记录。
--
-- actor_kind：
--   USER     平台内部用户（走 AccessService，actor_user_id 来自已认证会话）
--   GATEWAY  网关回报的用量（服务端到服务端，actor_user_id 为空）
CREATE TABLE platform_app_audit_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  app_id uuid NOT NULL,
  -- 关联的凭证（没有凭证的审计事件，如应用注册，为空）。
  credential_id uuid,

  event_type text NOT NULL CHECK(event_type IN (
    'APP_REGISTERED','APP_UPDATED','APP_REVOKED',
    'CREDENTIAL_GENERATED','CREDENTIAL_ROTATED','CREDENTIAL_REVOKED','CREDENTIAL_ACCESSED',
    'SCOPE_CHANGED','IP_ALLOWLIST_CHANGED','QUOTA_CHANGED','QUOTA_USAGE_RECORDED'
  )),

  -- 触发者：来自已认证会话的 user_id（**不接受请求参数传入**）。
  actor_user_id uuid,
  actor_kind text NOT NULL DEFAULT 'USER' CHECK(actor_kind IN ('USER','GATEWAY')),

  -- 人可读的补充说明（不存明文密钥、不存 PII）。
  detail varchar(500) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),

  -- ⚠️ **这一条 FK 刻意是 RESTRICT（默认 NO ACTION），不是 CASCADE**：
  --   审计账本**不能**跟着应用一起被删掉 —— 应用一旦登记就写过一行 APP_REGISTERED，
  --   所以这个 FK 等价于「**应用只能吊销，不能删除**」。
  --   别的子表（凭证 / 授权范围 / 白名单 / 配额）用 CASCADE：它们是应用的附属物，
  --   随应用一起消失是自然的。
  FOREIGN KEY(tenant_id, app_id) REFERENCES platform_app(tenant_id, id)
);

CREATE INDEX platform_app_audit_event_by_app
  ON platform_app_audit_event(tenant_id, app_id, created_at DESC);

-- 审计只追加：拒绝 UPDATE。
CREATE FUNCTION refuse_platform_app_audit_event_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'platform_app_audit_event 是只追加的审计账本，不允许修改';
END $$;

CREATE TRIGGER guard_platform_app_audit_event_update
  BEFORE UPDATE ON platform_app_audit_event
  FOR EACH ROW EXECUTE FUNCTION refuse_platform_app_audit_event_update();

-- 审计只追加：拒绝 DELETE。
CREATE FUNCTION refuse_platform_app_audit_event_delete() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'platform_app_audit_event 是只追加的审计账本，不允许删除';
END $$;

CREATE TRIGGER guard_platform_app_audit_event_delete
  BEFORE DELETE ON platform_app_audit_event
  FOR EACH ROW EXECUTE FUNCTION refuse_platform_app_audit_event_delete();

-- ===========================================================================
-- 8. 租户隔离（RLS）
-- ===========================================================================
--
-- 七张表全部：tenant_id NOT NULL + ENABLE **与** FORCE RLS + policy isolation。
--
-- FORCE 是必须的：表 owner 在 FORCE 下也受策略约束，否则迁移脚本或后台任务用 owner
-- 身份一连就绕过了隔离（与 identity V330 / finance V361 同纪律）。
--
-- ⚠️ policy 只认 `app.current_tenant` 这个**事务局部** GUC，它由
--    TenantTransactions.set_config(..., true) 绑定 —— 事务结束即失效，
--    连接归还池中时**不留残留**（这正是 AGENTS.md 第三条隔离断言要验的）。
ALTER TABLE platform_app ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_app FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_app
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_app_credential ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_app_credential FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_app_credential
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_app_scope ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_app_scope FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_app_scope
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_app_ip_allowlist ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_app_ip_allowlist FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_app_ip_allowlist
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_app_quota ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_app_quota FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_app_quota
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_app_quota_usage ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_app_quota_usage FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_app_quota_usage
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_app_audit_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_app_audit_event FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_app_audit_event
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

-- ===========================================================================
-- 9. 授权
-- ===========================================================================
--
-- 运行角色是 platform_app：它需要能读自己的应用与凭证、能写（注册 / 轮换 / 授权 / 配额）、
-- 能删（撤销授权范围与白名单条目）。
--
-- ⚠️ 审计表也给了 DELETE 授权，但**触发器**会拒绝它 —— 应用角色的授权面统一，
--    「不可删」这件事由数据层的触发器钉死，而不是靠少授一个权限码（少授会被
--    「迁移脚本用 owner 身份跑」绕开，触发器不会）。
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_app TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_app_credential TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_app_scope TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_app_ip_allowlist TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_app_quota TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_app_quota_usage TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_app_audit_event TO platform_app;
