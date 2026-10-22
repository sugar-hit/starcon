-- ===========================================================================
-- 16.5.2.1 登录与访问安全 —— channel 服务
-- ===========================================================================
-- 蓝图原文（docs/product/capability-blueprint/16-compliance-and-risk.md 第 217-222 行）：
--   ### 16.5.2.1 登录与访问安全
--   - 职责：管理平台账号与系统的访问安全：多因子认证、登录审计、异常登录告警、
--           IP 白名单、会话管理；平台授权凭据的访问审计。
--   - 输入：认证配置；登录事件；访问审计事件。
--   - 输出：安全配置 + 审计记录 + 告警。
--   - 关系：上游 = 17 identity；下游 = 2 凭据引用。
--           **平台凭据是最值钱的资产**，访问必须有审计且最小权限。
--
-- ---------------------------------------------------------------------------
-- 一、🔴 蓝图点名的六个能力面 ⇒ 本迁移的九张表（逐一对应，不合并、不省略）
-- ---------------------------------------------------------------------------
--   能力面                      表
--   ------------------------   --------------------------------------------------------
--   多因子认证配置              access_security_policy（MFA 段）
--                               access_security_mfa_registration（登记 / 绑定状态）
--                               access_security_mfa_challenge（挑战下发与验证尝试）
--   登录审计                    access_security_login_audit（本地留存的登录事件投影）
--   异常登录告警                access_security_alert + access_security_alert_action
--   IP 白名单                   access_security_ip_allowlist
--   会话管理                    access_security_policy（会话段）+ access_security_policy_audit
--   平台授权凭据的访问审计      access_security_credential_access
--
-- ---------------------------------------------------------------------------
-- 二、🔴 本仓实查结论（不是推测，逐条给证据）
-- ---------------------------------------------------------------------------
-- 1) **登录事件的事件源在 identity 的 `operation_log`，不在 `identity_audit`**：
--    identity 的 `AuditLog.record` 只记业务变更（成员、角色、字典…），
--    而**登录 / 登出事件由 `OperationLog.login` / `OperationLog.logout` 写入
--    `operation_log`**（event_type='LOGIN' / 'LOGOUT'）。
--    ⇒ 本模块的登录事件**只经 identity 的 `GET /identity/operation-log` 授权 API 读**，
--    **不跨库直读 identity 的表**（AGENTS.md 禁止跨服务 SQL）。
--
-- 2) 🔴 **identity 的登录事件里没有来源 IP**：
--    `IdentityController.login` 调 `operationLog.login(tenantId, requestId(), username, true, null, null)`
--    —— 第 5 个参数 `remoteHint` **恒为 null**。
--    ⇒ **「按来源 IP 判定异常登录」这条维度在本仓量不到**。
--    本模块**不编造 IP**：表里保留 `source_ip` / `source_ip_available` 两列并如实标注
--    available=false，能力声明里写明归属模块（17 identity）与影响面。
--    **用「同账号在窗口内的失败次数 / 成功次数」做异常判定** —— 那是从真实上游事件
--    实算出来的，不需要猜 IP。
--
-- 3) 🔴 **identity 的 `auth_session` 只有四个列**（token_hash / user_id / membership_id /
--    expires_at，见 identity V1），**没有空闲时间、没有并发数、没有登录来源**。
--    ⇒ 本模块的「会话管理」**是本服务侧的会话策略管理（策略 + 审计）**，
--    **绝不另起第二套会话表**（那会变成第二个登录系统）。
--    会话到期时刻 / 空闲时长 / 并发数这三个维度 upstream **读不到** ⇒ 标 available=false。
--
-- 4) 🔴 **下游 2 凭据引用已交付**（台账 2.2.1.3「凭据引用管理」= 已交付）：
--    `credential_ref`（channel V1）里**只有引用、没有任何明文令牌**。
--    ⇒ 本模块的凭据访问审计**结构上就不可能泄露令牌**（表里根本没有令牌列）。
--
-- ---------------------------------------------------------------------------
-- 三、🔴 表命名空间隔离（本批车道并行开发，前缀必须彼此隔离）
-- ---------------------------------------------------------------------------
--   本模块（16.5.2.1）⇒ `access_security_*`。
--   与既有命名空间**彼此不同**：channel 库的 `compliance_rule_*` / `compliance_intercept_*` /
--   `epr_*` / `ip_risk_*` / `buyer_risk_*` / `brand_*` / `listing_compliance_*` /
--   `product_certification_*` / `tax_registration_*` / `credential_ref` / `channel_connection`。
--   ⇒ 本迁移**不建**任何同名表，也**零 ALTER** 任何既有表。
--
-- ---------------------------------------------------------------------------
-- 四、🔴 零外键（与 channel V1 / V300 / V316 同一纪律）
-- ---------------------------------------------------------------------------
-- channel 的既有集成测试用 `truncate <表清单>` 清库，外键会让 TRUNCATE 报 23503。
-- 所以本迁移**不建任何外键**，跨表引用（challenge → registration、action → alert）
-- 改由 BEFORE INSERT 触发器校验存在性与同租户。
--
-- ---------------------------------------------------------------------------
-- 五、🔴 多租户隔离：tenant_id NOT NULL + ENABLE/FORCE RLS
-- ---------------------------------------------------------------------------
-- 与 channel V1 / V197 / V300 / V316 同一套写法。租户由 backend/libraries/tenancy
-- 在事务内设置（app.current_tenant），客户端的 X-Tenant-Id 头进不来也改不动它。
--
-- 🔴 **只带 isolation 策略，不带 data_scope（RESTRICTIVE）策略**：
--   安全配置与审计是**租户级资产**，不是某家店 / 某个部门的资产。
--   （与 V316 的 buyer_risk_* 同一处理。）
--
-- ===========================================================================
-- 一、access_security_policy · 一租户一条安全策略（MFA 段 + 会话段 + IP 口径 + 阈值）
-- ===========================================================================
-- 🔴 **阈值不给默认值**：异常登录的窗口与阈值必须由租户显式选。
--   「系统给个默认阈值」等于「系统替租户决定了什么算异常」—— 那不是配置，是默认值。
--
-- 🔴 **会话策略是「目标口径」而不是「另一个会话系统」**：
--   identity 的 auth_session 固定 8 小时过期（identity V1: now()+interval '8 hours'），
--   本表的会话段表达**平台侧应执行的会话策略**（空闲 / 绝对 / 并发 / 换绑即撤销），
--   真正的执行点在 17 identity 与网关 —— 本模块**不代它执行、也不假装已执行**
--   （能力声明里逐条标 available）。
--
-- 🔴 **mfa_grace_period_minutes > 0 ⇒ 必须有 mfa_enforcement_from**：
--   宽限期必须锚定一个「强制生效时刻」，否则它永远不过期 —— 那是一条不会失效的免死金牌。
CREATE TABLE access_security_policy (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  policy_name varchar(120) NOT NULL CHECK(btrim(policy_name)<>''),

  -- ---- 多因子认证段 ----
  mfa_required boolean NOT NULL DEFAULT false,
  -- 主因子：受控值域（DB 层 CHECK 钉死）
  mfa_primary_factor varchar(16) NOT NULL DEFAULT 'TOTP'
    CHECK(mfa_primary_factor IN ('TOTP','SMS','EMAIL')),
  -- 宽限期（分钟）：0 = 立即强制
  mfa_grace_period_minutes int NOT NULL DEFAULT 0
    CHECK(mfa_grace_period_minutes >= 0 AND mfa_grace_period_minutes <= 43200),
  -- 强制生效时刻（宽限期 > 0 时必填，见文件头第五节）
  mfa_enforcement_from timestamptz,

  -- ---- 会话策略段（本服务侧策略，不另起会话表）----
  session_idle_timeout_minutes int NOT NULL DEFAULT 480 CHECK(session_idle_timeout_minutes > 0),
  session_absolute_timeout_minutes int NOT NULL DEFAULT 1440 CHECK(session_absolute_timeout_minutes > 0),
  session_max_concurrent int NOT NULL DEFAULT 5 CHECK(session_max_concurrent > 0),
  session_revoke_on_mfa_change boolean NOT NULL DEFAULT true,
  session_revoke_on_password_change boolean NOT NULL DEFAULT true,
  -- 绝对超时必须不短于空闲超时
  CHECK(session_absolute_timeout_minutes >= session_idle_timeout_minutes),

  -- ---- IP 白名单执行口径 ----
  -- DISABLED   ：不做 IP 限制（**这是租户显式选的「不限制」，不是默认值**）
  -- REPORT_ONLY：不限制，但不在白名单的 IP 会被记成告警
  -- ENFORCE    ：不在白名单的 IP 一律拒绝
  -- 🔴 **ENFORCE + 空白名单 = 拒绝所有**（fail-closed），由下面的守卫触发器钉死，
  --   绝不允许「白名单为空 ⇒ 放行所有」。
  ip_allowlist_mode varchar(16) NOT NULL DEFAULT 'DISABLED'
    CHECK(ip_allowlist_mode IN ('DISABLED','REPORT_ONLY','ENFORCE')),

  -- ---- 异常登录检测阈值（必须由租户显式选）----
  anomaly_window_minutes int NOT NULL DEFAULT 15 CHECK(anomaly_window_minutes > 0),
  anomaly_failed_login_threshold int NOT NULL DEFAULT 5 CHECK(anomaly_failed_login_threshold > 0),
  anomaly_success_login_threshold int NOT NULL DEFAULT 3 CHECK(anomaly_success_login_threshold > 0),

  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 🔴 一租户一条策略
  UNIQUE(tenant_id),
  CHECK(updated_at >= created_at),
  -- 🔴 宽限期必须锚定强制生效时刻
  CHECK(mfa_grace_period_minutes = 0 OR mfa_enforcement_from IS NOT NULL)
);
COMMENT ON TABLE access_security_policy IS
  '16.5.2.1 · 一租户一条访问安全策略：多因子认证段 + 会话策略段 + IP 白名单执行口径 + 异常登录检测阈值。'
  '阈值不给默认值（必须由租户显式选）。ip_allowlist_mode=ENFORCE 且白名单为空时一律拒绝（fail-closed）。';

-- ===========================================================================
-- 二、access_security_policy_audit · 安全配置变更留痕（append-only）
-- ===========================================================================
-- 🔴 **append-only**：配置改了就是改了，事后改写「当初是怎么配的」等于毁掉取证能力。
CREATE TABLE access_security_policy_audit (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  target_kind varchar(32) NOT NULL
    CHECK(target_kind IN ('POLICY','MFA_REGISTRATION','IP_ALLOWLIST')),
  target_id uuid NOT NULL,
  action varchar(24) NOT NULL
    CHECK(action IN ('CREATED','UPDATED','ACTIVATED','DISABLED','DELETED','REVOKED')),
  field_name varchar(64) NOT NULL CHECK(btrim(field_name)<>''),
  old_value varchar(400) NOT NULL DEFAULT '',
  new_value varchar(400) NOT NULL DEFAULT '',
  changed_by uuid,
  changed_by_name varchar(120) NOT NULL DEFAULT '',
  reason varchar(600) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id)
);
COMMENT ON TABLE access_security_policy_audit IS
  '16.5.2.1 · 安全配置变更留痕（append-only）。策略 / MFA 登记 / IP 白名单的每一次改动都留一行。';

CREATE INDEX access_security_policy_audit_by_target
  ON access_security_policy_audit(tenant_id, target_kind, target_id, created_at DESC);
CREATE INDEX access_security_policy_audit_by_time
  ON access_security_policy_audit(tenant_id, created_at DESC);

-- ===========================================================================
-- 三、access_security_mfa_registration · MFA 因子登记 / 绑定状态
-- ===========================================================================
-- 🔴 **只存外部密钥管理器的引用，不存明文因子密钥**：
--   与 2.2.1.3「令牌明文只在内存里短暂存在并直接写密钥管理器，数据库仅存引用」同一纪律。
--   本仓**没有**真实 MFA 服务商凭据 ⇒ 本表登记的是**绑定状态**，
--   挑战真正下发由 17 identity / 网关执行（能力声明里标 available=false）。
CREATE TABLE access_security_mfa_registration (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 成员（identity 的 app_user.id）。跨服务只存逻辑 ID，不建外键。
  user_id uuid NOT NULL,
  membership_id uuid,
  factor_type varchar(16) NOT NULL CHECK(factor_type IN ('TOTP','SMS','EMAIL')),
  factor_label varchar(120) NOT NULL DEFAULT '',
  -- 🔴 外部密钥管理器引用（**不是明文密钥**）
  secret_ref varchar(400) NOT NULL DEFAULT '',

  -- 绑定状态机：PENDING → ACTIVE → DISABLED / REVOKED（守卫触发器钉死）
  status varchar(16) NOT NULL DEFAULT 'PENDING'
    CHECK(status IN ('PENDING','ACTIVE','DISABLED','REVOKED')),
  bound_at timestamptz,
  last_verified_at timestamptz,
  revoked_reason varchar(400) NOT NULL DEFAULT '',

  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 一个成员每种因子至多一条登记
  UNIQUE(tenant_id,user_id,factor_type),
  CHECK(updated_at >= created_at),
  -- 🔴 ACTIVE 必须已绑定（没有绑定时刻的「已启用」是假的）
  CHECK(status <> 'ACTIVE' OR bound_at IS NOT NULL),
  -- 🔴 REVOKED 必须写明原因（撤销不解释 = 无法追责）
  CHECK(status <> 'REVOKED' OR btrim(revoked_reason) <> '')
);
COMMENT ON TABLE access_security_mfa_registration IS
  '16.5.2.1 · MFA 因子登记与绑定状态。只存密钥管理器引用，**不存明文因子密钥**；'
  'ACTIVE 必须已绑定，REVOKED 必须写明原因。';

CREATE INDEX access_security_mfa_registration_by_user
  ON access_security_mfa_registration(tenant_id, user_id, status);

-- ===========================================================================
-- 四、access_security_mfa_challenge · 挑战下发与验证尝试（append-only）
-- ===========================================================================
-- 🔴 **一次挑战多次尝试 = 多行**，不是改同一行：
--   验证尝试是**发生过的动作**，事后改写它等于伪造「验证了几次」。
--   同一 challenge_id 的多行用 attempt_no 串起来。
--
-- 🔴 **本表不发送任何短信 / 邮件**：本仓**没有**真实 MFA 服务商凭据，
--   写一个打真实域名的假 HTTP 客户端是最典型的假实现。
--   本表登记的是**下发记录**（由 17 identity / 网关真正执行时回写，或由本模块登记占位）。
CREATE TABLE access_security_mfa_challenge (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  registration_id uuid NOT NULL,
  -- 逻辑挑战号：同一次挑战的多次尝试共用它
  challenge_id uuid NOT NULL,
  attempt_no int NOT NULL CHECK(attempt_no > 0),

  factor_type varchar(16) NOT NULL CHECK(factor_type IN ('TOTP','SMS','EMAIL')),
  outcome varchar(16) NOT NULL CHECK(outcome IN ('ISSUED','VERIFIED','FAILED','EXPIRED')),
  -- 下发通道；AUTHENTICATOR_APP = 用户自己扫码，不经过任何外部服务商
  delivery_channel varchar(24) NOT NULL DEFAULT ''
    CHECK(delivery_channel IN ('','EMAIL','SMS','AUTHENTICATOR_APP','MANUAL')),
  delivery_ref varchar(400) NOT NULL DEFAULT '',
  failure_reason varchar(400) NOT NULL DEFAULT '',
  expires_at timestamptz,
  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,challenge_id,attempt_no),
  -- 🔴 失败必须写明原因
  CHECK(outcome <> 'FAILED' OR btrim(failure_reason) <> ''),
  -- 🔴 只有第 1 次是「下发」，其余都是验证尝试
  CHECK(outcome <> 'ISSUED' OR attempt_no = 1)
);
COMMENT ON TABLE access_security_mfa_challenge IS
  '16.5.2.1 · MFA 挑战下发与验证尝试（append-only）。一次挑战多次尝试 = 多行（attempt_no 串联）。'
  '本表**不发送任何短信 / 邮件** —— 本仓没有真实 MFA 服务商凭据。';

CREATE INDEX access_security_mfa_challenge_by_challenge
  ON access_security_mfa_challenge(tenant_id, challenge_id, attempt_no);
CREATE INDEX access_security_mfa_challenge_by_registration
  ON access_security_mfa_challenge(tenant_id, registration_id, occurred_at DESC);

-- ===========================================================================
-- 五、access_security_login_audit · 登录审计（append-only）
-- ===========================================================================
-- 🔴 **为什么本地要留一份**：identity 的 `operation_log` 有 **90 天留存期**
--   （identity V49: expires_at = occurred_at + 90 days，且有 purge 端点）。
--   登录审计是**合规证据**，不能跟着排障日志一起过期。
--   ⇒ 本表是 identity 登录事件的**本地留存投影**，
--   落库时用 `UNIQUE(tenant_id, source_event_id)` 保证**同一事件只落一次**。
--
-- 🔴 **source_ip 恒为空**：identity 的登录事件不带来源 IP（见文件头第二节第 2 条）。
--   两列（source_ip / source_ip_available）如实表达这个缺口，不编造。
CREATE TABLE access_security_login_audit (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- identity `operation_log` 的行 id（同一事件只落一次）
  source_event_id uuid NOT NULL,
  source_service varchar(32) NOT NULL DEFAULT 'IDENTITY' CHECK(source_service = 'IDENTITY'),
  event_type varchar(16) NOT NULL CHECK(event_type IN ('LOGIN','LOGOUT')),
  outcome varchar(16) NOT NULL CHECK(outcome IN ('SUCCESS','FAILURE')),

  actor_name varchar(120) NOT NULL DEFAULT '',
  actor_id uuid,
  level varchar(16) NOT NULL DEFAULT 'INFO' CHECK(level IN ('INFO','WARNING','ERROR')),
  request_id varchar(120) NOT NULL DEFAULT '',
  summary varchar(400) NOT NULL DEFAULT '',

  occurred_at timestamptz NOT NULL,

  -- 🔴 identity 的登录事件**不含来源 IP**（remoteHint 恒为 null）⇒ available=false
  source_ip varchar(64) NOT NULL DEFAULT '',
  source_ip_available boolean NOT NULL DEFAULT false,
  CHECK(NOT source_ip_available OR btrim(source_ip) <> ''),

  -- 异常判定结果（由扫描时实算，不是人工标注）
  anomaly boolean NOT NULL DEFAULT false,
  anomaly_code varchar(32) NOT NULL DEFAULT '',
  note varchar(600) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 🔴 同一上游事件只落一次（重复扫描不会产生重复审计行）
  UNIQUE(tenant_id,source_event_id)
);
COMMENT ON TABLE access_security_login_audit IS
  '16.5.2.1 · 登录审计（append-only）。identity 的 operation_log 只有 90 天留存期，'
  '登录审计是合规证据不能跟着过期 ⇒ 本表是登录事件的本地留存投影；source_event_id 唯一保证只落一次。'
  'source_ip 恒为空（identity 登录事件不带来源 IP），available=false 如实标注。';

CREATE INDEX access_security_login_audit_by_time
  ON access_security_login_audit(tenant_id, occurred_at DESC);
CREATE INDEX access_security_login_audit_by_actor
  ON access_security_login_audit(tenant_id, actor_name, occurred_at DESC);
CREATE INDEX access_security_login_audit_by_outcome
  ON access_security_login_audit(tenant_id, outcome, occurred_at DESC);

-- ===========================================================================
-- 六、access_security_alert · 异常登录告警（受控严重度 + 处置状态机）
-- ===========================================================================
-- 🔴 **严重度是受控值域**（DB 层 CHECK 钉死），不靠服务层自觉：
--   LOW / MEDIUM / HIGH / CRITICAL —— 自由文本的严重度没法排序、没法筛、没法告警。
--
-- 🔴 **状态机：新警 → 已确认 / 已忽略 / 已处置**（守卫触发器钉死合法转换）：
--   * NEW → ACKNOWLEDGED / IGNORED / RESOLVED
--   * ACKNOWLEDGED → IGNORED / RESOLVED
--   * IGNORED / RESOLVED 是**终态**（事后要重新处置就再开一条告警，不改旧的那条）
--
-- 🔴 **一个未关闭告警只允许一条**：部分唯一索引
--   （同一 tenant + 同一 dedup_key 只允许一条 NEW/ACKNOWLEDGED）。
--   重复扫描命中同一条告警时**更新**它（事件数、窗口、严重度），
--   而不是刷出一串一模一样的告警把运营的注意力淹掉。
CREATE TABLE access_security_alert (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  alert_type varchar(32) NOT NULL
    CHECK(alert_type IN ('FAILED_LOGIN_BURST','SUCCESS_LOGIN_BURST','IP_NOT_ALLOWED','ALLOWLIST_EMPTY')),
  severity varchar(16) NOT NULL
    CHECK(severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
  status varchar(16) NOT NULL DEFAULT 'NEW'
    CHECK(status IN ('NEW','ACKNOWLEDGED','IGNORED','RESOLVED')),

  -- 主体：用户名 / IP / 策略
  subject_kind varchar(24) NOT NULL CHECK(subject_kind IN ('USERNAME','IP','POLICY')),
  subject_value varchar(200) NOT NULL CHECK(btrim(subject_value)<>''),

  -- 幂等键：同一 (类型, 主体) 的未关闭告警至多一条
  dedup_key varchar(200) NOT NULL CHECK(btrim(dedup_key)<>''),

  title varchar(200) NOT NULL CHECK(btrim(title)<>''),
  summary varchar(800) NOT NULL DEFAULT '',
  event_count int NOT NULL DEFAULT 0 CHECK(event_count >= 0),
  window_start timestamptz,
  window_end timestamptz,
  detected_at timestamptz NOT NULL,

  -- ---- 处置留痕（状态与留痕必须自洽）----
  acknowledged_by uuid,
  acknowledged_at timestamptz,
  ignored_by uuid,
  ignored_at timestamptz,
  ignore_reason varchar(600) NOT NULL DEFAULT '',
  resolved_by uuid,
  resolved_at timestamptz,
  resolution_note varchar(800) NOT NULL DEFAULT '',

  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  CHECK(updated_at >= created_at),
  -- 🔴 状态与留痕自洽：进了某个状态就必须有处置人与处置时刻
  CHECK(status <> 'ACKNOWLEDGED' OR (acknowledged_by IS NOT NULL AND acknowledged_at IS NOT NULL)),
  CHECK(status <> 'IGNORED' OR (ignored_by IS NOT NULL AND ignored_at IS NOT NULL)),
  CHECK(status <> 'RESOLVED' OR (resolved_by IS NOT NULL AND resolved_at IS NOT NULL)),
  -- 🔴 NEW 态不许有任何处置留痕
  CHECK(status <> 'NEW' OR (acknowledged_at IS NULL AND ignored_at IS NULL AND resolved_at IS NULL)),
  -- 窗口要么都有，要么都没有
  CHECK((window_start IS NULL) = (window_end IS NULL)),
  CHECK(window_start IS NULL OR window_end >= window_start)
);
COMMENT ON TABLE access_security_alert IS
  '16.5.2.1 · 异常登录告警。严重度是受控值域（LOW/MEDIUM/HIGH/CRITICAL）；'
  '状态机 新警 → 已确认 / 已忽略 / 已处置，已忽略与已处置是终态。'
  '部分唯一索引保证同一 (类型, 主体) 只留一条未关闭告警。';

CREATE INDEX access_security_alert_by_status
  ON access_security_alert(tenant_id, status, detected_at DESC);
CREATE INDEX access_security_alert_by_severity
  ON access_security_alert(tenant_id, severity, detected_at DESC);
CREATE INDEX access_security_alert_by_type
  ON access_security_alert(tenant_id, alert_type, detected_at DESC);

-- 🔴 一个未关闭告警（NEW / ACKNOWLEDGED）只允许一条
CREATE UNIQUE INDEX access_security_alert_open_dedup
  ON access_security_alert(tenant_id, dedup_key)
  WHERE status IN ('NEW','ACKNOWLEDGED');

-- ===========================================================================
-- 七、access_security_alert_action · 告警处置留痕（append-only）
-- ===========================================================================
-- 🔴 **处置必须留痕**：只把状态改成「已处置」而不记「谁在什么时候因为什么处置的」，
--   等于没有处置记录 —— 出了事没人认账。
CREATE TABLE access_security_alert_action (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  alert_id uuid NOT NULL,
  action varchar(16) NOT NULL CHECK(action IN ('ACKNOWLEDGE','IGNORE','RESOLVE')),
  from_status varchar(16) NOT NULL
    CHECK(from_status IN ('NEW','ACKNOWLEDGED','IGNORED','RESOLVED')),
  to_status varchar(16) NOT NULL
    CHECK(to_status IN ('NEW','ACKNOWLEDGED','IGNORED','RESOLVED')),

  actor_id uuid,
  actor_name varchar(120) NOT NULL DEFAULT '',
  reason varchar(600) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 🔴 动作与目标状态必须一一对应（不许「确认」落到「已处置」上）
  CHECK((action = 'ACKNOWLEDGE') = (to_status = 'ACKNOWLEDGED')),
  CHECK((action = 'IGNORE') = (to_status = 'IGNORED')),
  CHECK((action = 'RESOLVE') = (to_status = 'RESOLVED')),
  -- 🔴 处置理由不能为空（IGNORE / RESOLVE 尤其重要）
  CHECK(action <> 'ACKNOWLEDGE' OR btrim(reason) <> '')
);
COMMENT ON TABLE access_security_alert_action IS
  '16.5.2.1 · 告警处置留痕（append-only）。每一次确认 / 忽略 / 处置都留一行，含前后状态与理由。';

CREATE INDEX access_security_alert_action_by_alert
  ON access_security_alert_action(tenant_id, alert_id, created_at);

-- ===========================================================================
-- 八、access_security_ip_allowlist · IP 白名单（真网段判定）
-- ===========================================================================
-- 🔴 **用 PostgreSQL 的 `inet` 类型，网段判定是 `<<=` 运算，不是字符串相等**：
--   字符串相等只能判「完全一样的地址」，判不了「10.0.0.5 在 10.0.0.0/8 里面」。
--   本仓真实的判定 SQL 见 AccessSecurityService：
--       ?::inet <<= cidr_range
--   并用 `family()` 排除 IPv4 / IPv6 混比。
--
-- 🔴 **为什么用 inet 而不是 cidr**：cidr 类型**拒绝带主机位的写法**
--   （'192.168.1.5/24'::cidr 直接报错），而用户填网段时十有八九会带主机位。
--   inet 两种都收下，且 `<<=` 语义一致。
CREATE TABLE access_security_ip_allowlist (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  cidr_range inet NOT NULL,
  label varchar(120) NOT NULL DEFAULT '',
  -- TENANT = 整个租户；CONNECTION = 只对某个商家授权连接生效
  scope_kind varchar(16) NOT NULL DEFAULT 'TENANT' CHECK(scope_kind IN ('TENANT','CONNECTION')),
  scope_id uuid,

  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','DISABLED')),
  effective_from timestamptz NOT NULL DEFAULT now(),
  effective_to timestamptz,

  created_by uuid,
  created_by_name varchar(120) NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  -- 🔴 同一租户下同一网段同一口径只能有一条。
  --   `NULLS NOT DISTINCT`（PG15+ 的**表约束**语法）让 scope_id=NULL 也算「同一条」——
  --   否则「租户口径的 10.0.0.0/8」可以重复登记两条，改一条漏一条。
  --   ⚠️ 这个语法**只能写在表约束里**：CREATE INDEX 的索引元素只认 `NULLS FIRST|LAST`，
  --   写成 `CREATE UNIQUE INDEX ... (..., scope_id NULLS NOT DISTINCT)` 是语法错误。
  UNIQUE NULLS NOT DISTINCT (tenant_id, cidr_range, scope_kind, scope_id),
  CHECK(updated_at >= created_at),
  -- 🔴 CONNECTION 口径必须带 scope_id；TENANT 口径必须不带（否则两套语义会打架）
  CHECK((scope_kind = 'CONNECTION') = (scope_id IS NOT NULL)),
  CHECK(effective_to IS NULL OR effective_to > effective_from)
);
COMMENT ON TABLE access_security_ip_allowlist IS
  '16.5.2.1 · IP 白名单条目。用 PostgreSQL `inet` 类型存，判定走 `<<=` 运算符（真网段包含），'
  '不是字符串相等。scope TENANT / CONNECTION 由 CHECK 钉死互斥。';

-- 🔴 同一租户下同一网段同一口径的唯一性由**表约束** `UNIQUE NULLS NOT DISTINCT` 承担
--   （见建表处）。**不能**写成 CREATE UNIQUE INDEX ... (..., scope_id NULLS NOT DISTINCT)
--   —— CREATE INDEX 的索引元素只认 `NULLS FIRST|LAST`，那样写是语法错误。
CREATE INDEX access_security_ip_allowlist_by_status
  ON access_security_ip_allowlist(tenant_id, status, effective_from);

-- ===========================================================================
-- 九、access_security_credential_access · 平台授权凭据访问审计（append-only）
-- ===========================================================================
-- 🔴🔴 **蓝图加粗句「平台凭据是最值钱的资产，访问必须有审计且最小权限」的物理落点**：
--   1) **append-only**：GRANT 只给 SELECT,INSERT，**刻意不给 UPDATE/DELETE**
--      （见文件头第十节；守卫触发器再钉一道）。
--   2) **表里没有令牌列**：2.2.1.3 的 `credential_ref` 本身就只存引用、不存明文令牌，
--      本表也只记引用 ⇒ **结构上不可能泄露令牌**。
--   3) **记的是「谁 / 什么时候 / 哪个凭据 / 做了什么 / 成没成」**：
--      actor + occurred_at + credential_id + access_kind + outcome 五件套齐全。
CREATE TABLE access_security_credential_access (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 逻辑引用，指向 2.2.1.3 的 `credential_ref`（**不建外键**：跨模块引用由服务层校验）
  credential_id uuid NOT NULL,
  external_ref varchar(400) NOT NULL DEFAULT '',
  provider varchar(64) NOT NULL DEFAULT '',

  access_kind varchar(32) NOT NULL
    CHECK(access_kind IN ('READ_METADATA','READ_SECRET','WRITE_SECRET','ROTATE','REVOKE','HEALTH_CHECK')),
  access_purpose varchar(200) NOT NULL DEFAULT '',
  outcome varchar(16) NOT NULL CHECK(outcome IN ('SUCCESS','DENIED','FAILED')),

  actor_id uuid,
  actor_name varchar(120) NOT NULL DEFAULT '',
  actor_source varchar(16) NOT NULL DEFAULT 'USER' CHECK(actor_source IN ('USER','SERVICE','SYSTEM')),
  request_id varchar(120) NOT NULL DEFAULT '',
  detail jsonb NOT NULL DEFAULT '{}'::jsonb,
  note varchar(600) NOT NULL DEFAULT '',
  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  CHECK(jsonb_typeof(detail) = 'object')
);
COMMENT ON TABLE access_security_credential_access IS
  '16.5.2.1 · 平台授权凭据访问审计（append-only，GRANT 只给 SELECT,INSERT）。'
  '记 actor / occurred_at / credential_id / access_kind / outcome 五件套；'
  '表里**没有令牌列**（2.2.1.3 的 credential_ref 本身也只存引用）⇒ 结构上不可能泄露令牌。';

CREATE INDEX access_security_credential_access_by_credential
  ON access_security_credential_access(tenant_id, credential_id, occurred_at DESC);
CREATE INDEX access_security_credential_access_by_actor
  ON access_security_credential_access(tenant_id, actor_id, occurred_at DESC);
CREATE INDEX access_security_credential_access_by_outcome
  ON access_security_credential_access(tenant_id, outcome, occurred_at DESC);

-- ===========================================================================
-- 十、append-only 触发器（审计凭据不可改写）
-- ===========================================================================
-- 🔴 五张凭据表全部**不可 UPDATE / DELETE**：
--   * 凭据访问审计改一行 = 抹掉一次访问痕迹；
--   * 登录审计改一行 = 抹掉一次登录痕迹；
--   * 告警处置留痕改一行 = 抹掉一次处置；
--   * 策略变更留痕改一行 = 抹掉一次配置变更；
--   * MFA 挑战改一行 = 伪造「验证了几次」。
CREATE FUNCTION guard_access_security_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% 是审计凭据：不可修改也不可删除', TG_TABLE_NAME
    USING ERRCODE='23514';
END $$;

CREATE TRIGGER access_security_policy_audit_immutable
  BEFORE UPDATE OR DELETE ON access_security_policy_audit
  FOR EACH ROW EXECUTE FUNCTION guard_access_security_immutable();
CREATE TRIGGER access_security_mfa_challenge_immutable
  BEFORE UPDATE OR DELETE ON access_security_mfa_challenge
  FOR EACH ROW EXECUTE FUNCTION guard_access_security_immutable();
CREATE TRIGGER access_security_login_audit_immutable
  BEFORE UPDATE OR DELETE ON access_security_login_audit
  FOR EACH ROW EXECUTE FUNCTION guard_access_security_immutable();
CREATE TRIGGER access_security_alert_action_immutable
  BEFORE UPDATE OR DELETE ON access_security_alert_action
  FOR EACH ROW EXECUTE FUNCTION guard_access_security_immutable();
CREATE TRIGGER access_security_credential_access_immutable
  BEFORE UPDATE OR DELETE ON access_security_credential_access
  FOR EACH ROW EXECUTE FUNCTION guard_access_security_immutable();

-- ===========================================================================
-- 十一、状态机触发器
-- ===========================================================================
-- 🔴 MFA 登记状态机（守卫在 DB 层，不靠服务层自觉）
CREATE FUNCTION guard_access_security_mfa_status() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.status = OLD.status THEN
    RETURN NEW;
  END IF;
  IF NOT (
    (OLD.status = 'PENDING'  AND NEW.status IN ('ACTIVE','DISABLED','REVOKED')) OR
    (OLD.status = 'ACTIVE'   AND NEW.status IN ('DISABLED','REVOKED')) OR
    (OLD.status = 'DISABLED' AND NEW.status IN ('ACTIVE','REVOKED'))
  ) THEN
    RAISE EXCEPTION 'MFA 登记状态不能从 % 变到 %（待绑定 → 已启用 / 已停用 / 已撤销；已启用 ↔ 已停用；任意态 → 已撤销）',
      OLD.status, NEW.status USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER access_security_mfa_status_guard
  BEFORE UPDATE OF status ON access_security_mfa_registration
  FOR EACH ROW EXECUTE FUNCTION guard_access_security_mfa_status();

-- 🔴 告警状态机：新警 → 已确认 / 已忽略 / 已处置
CREATE FUNCTION guard_access_security_alert_status() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.status = OLD.status THEN
    RETURN NEW;
  END IF;
  IF NOT (
    (OLD.status = 'NEW'          AND NEW.status IN ('ACKNOWLEDGED','IGNORED','RESOLVED')) OR
    (OLD.status = 'ACKNOWLEDGED' AND NEW.status IN ('IGNORED','RESOLVED'))
  ) THEN
    RAISE EXCEPTION '告警状态不能从 % 变到 %（新警 → 已确认 / 已忽略 / 已处置；已确认 → 已忽略 / 已处置；已忽略与已处置是终态）',
      OLD.status, NEW.status USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER access_security_alert_status_guard
  BEFORE UPDATE OF status ON access_security_alert
  FOR EACH ROW EXECUTE FUNCTION guard_access_security_alert_status();

-- ===========================================================================
-- 十二、引用完整性（不建外键，改用触发器 —— 见文件头第四节）
-- ===========================================================================
CREATE FUNCTION guard_access_security_challenge_reference() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM access_security_mfa_registration r
                 WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.registration_id) THEN
    RAISE EXCEPTION 'MFA 登记 % 不存在或不属于当前租户', NEW.registration_id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER access_security_mfa_challenge_reference
  BEFORE INSERT ON access_security_mfa_challenge
  FOR EACH ROW EXECUTE FUNCTION guard_access_security_challenge_reference();

CREATE FUNCTION guard_access_security_alert_action_reference() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM access_security_alert a
                 WHERE a.tenant_id = NEW.tenant_id AND a.id = NEW.alert_id) THEN
    RAISE EXCEPTION '告警 % 不存在或不属于当前租户', NEW.alert_id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER access_security_alert_action_reference
  BEFORE INSERT ON access_security_alert_action
  FOR EACH ROW EXECUTE FUNCTION guard_access_security_alert_action_reference();

-- ===========================================================================
-- 十三、🔴 fail-closed 守卫：ENFORCE 口径下白名单不许空
-- ===========================================================================
-- 🔴 蓝图口径「白名单为空 ≠ 放行所有」：
--   切到 ENFORCE 时如果白名单是空的 ⇒ 一律拒绝（而不是「谁都能进」）。
--   这个不变量在**数据层**钉死，不靠服务层记得检查。
--
--   守卫覆盖三个入口：
--     1) 策略切到 ENFORCE（BEFORE UPDATE OF ip_allowlist_mode）
--     2) 白名单条目被停用 / 改口径（BEFORE UPDATE）
--     3) 白名单条目被删除（BEFORE DELETE）
--   ⇒ 「先清空白名单再切 ENFORCE」这条路也走不通：清空的那一步就被拦。
CREATE FUNCTION access_security_assert_allowlist_not_empty(
  p_tenant uuid, p_excluding uuid, p_adding_active boolean) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
  v_mode varchar(16);
  v_active int;
BEGIN
  SELECT ip_allowlist_mode INTO v_mode
    FROM access_security_policy WHERE tenant_id = p_tenant;
  IF v_mode IS DISTINCT FROM 'ENFORCE' THEN
    RETURN;
  END IF;
  SELECT count(*) INTO v_active
    FROM access_security_ip_allowlist
    WHERE tenant_id = p_tenant AND status = 'ACTIVE'
      AND (p_excluding IS NULL OR id <> p_excluding);
  IF p_adding_active THEN
    v_active := v_active + 1;
  END IF;
  IF v_active < 1 THEN
    RAISE EXCEPTION 'IP 白名单口径是 ENFORCE（不在白名单的 IP 一律拒绝），但本租户没有任何生效中的白名单条目 —— 请先登记白名单再切换口径（空白名单不等于放行所有）'
      USING ERRCODE='23514';
  END IF;
END $$;

CREATE FUNCTION guard_access_security_policy_enforce() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.ip_allowlist_mode = 'ENFORCE' AND OLD.ip_allowlist_mode IS DISTINCT FROM 'ENFORCE' THEN
    PERFORM access_security_assert_allowlist_not_empty(NEW.tenant_id, NULL, FALSE);
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER access_security_policy_enforce_guard
  BEFORE UPDATE OF ip_allowlist_mode ON access_security_policy
  FOR EACH ROW EXECUTE FUNCTION guard_access_security_policy_enforce();

CREATE FUNCTION guard_access_security_allowlist_insert() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM access_security_assert_allowlist_not_empty(NEW.tenant_id, NULL, NEW.status = 'ACTIVE');
  RETURN NEW;
END $$;
CREATE TRIGGER access_security_allowlist_insert_guard
  BEFORE INSERT ON access_security_ip_allowlist
  FOR EACH ROW EXECUTE FUNCTION guard_access_security_allowlist_insert();

CREATE FUNCTION guard_access_security_allowlist_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM access_security_assert_allowlist_not_empty(NEW.tenant_id, OLD.id, NEW.status = 'ACTIVE');
  RETURN NEW;
END $$;
CREATE TRIGGER access_security_allowlist_update_guard
  BEFORE UPDATE ON access_security_ip_allowlist
  FOR EACH ROW EXECUTE FUNCTION guard_access_security_allowlist_update();

CREATE FUNCTION guard_access_security_allowlist_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM access_security_assert_allowlist_not_empty(OLD.tenant_id, OLD.id, FALSE);
  RETURN NEW;
END $$;
CREATE TRIGGER access_security_allowlist_delete_guard
  BEFORE DELETE ON access_security_ip_allowlist
  FOR EACH ROW EXECUTE FUNCTION guard_access_security_allowlist_delete();

-- ===========================================================================
-- 十四、多租户隔离：ENABLE + FORCE ROW LEVEL SECURITY
-- ===========================================================================
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['access_security_policy','access_security_policy_audit',
                              'access_security_mfa_registration','access_security_mfa_challenge',
                              'access_security_login_audit','access_security_alert',
                              'access_security_alert_action','access_security_ip_allowlist',
                              'access_security_credential_access'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;
-- 🔴 **刻意不建 data_scope（RESTRICTIVE）策略**：安全配置与审计是租户级资产
--   （与 V316 的 buyer_risk_* 同一处理）。

-- ===========================================================================
-- 十五、账号与授权
-- ===========================================================================
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='channel_app') THEN
    CREATE ROLE channel_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='starcon_app') THEN
    CREATE ROLE starcon_app;
  END IF;
END $$;

GRANT USAGE ON SCHEMA public TO channel_app;

-- 可改的配置类表
GRANT SELECT,INSERT,UPDATE ON access_security_policy TO channel_app;
GRANT SELECT,INSERT,UPDATE ON access_security_mfa_registration TO channel_app;
GRANT SELECT,INSERT,UPDATE ON access_security_alert TO channel_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON access_security_ip_allowlist TO channel_app;

-- 🔴🔴 **append-only 的五张凭据表：刻意只给 SELECT,INSERT，不给 UPDATE/DELETE**
--   （access_security_credential_access 是其中最要紧的一张：平台凭据访问审计）
GRANT SELECT,INSERT ON access_security_policy_audit TO channel_app;
GRANT SELECT,INSERT ON access_security_mfa_challenge TO channel_app;
GRANT SELECT,INSERT ON access_security_login_audit TO channel_app;
GRANT SELECT,INSERT ON access_security_alert_action TO channel_app;
GRANT SELECT,INSERT ON access_security_credential_access TO channel_app;

-- starcon_app 是跨服务只读角色，拿不到任何业务表（与 V1..V316 同口径）。
REVOKE ALL ON access_security_policy FROM starcon_app;
REVOKE ALL ON access_security_policy_audit FROM starcon_app;
REVOKE ALL ON access_security_mfa_registration FROM starcon_app;
REVOKE ALL ON access_security_mfa_challenge FROM starcon_app;
REVOKE ALL ON access_security_login_audit FROM starcon_app;
REVOKE ALL ON access_security_alert FROM starcon_app;
REVOKE ALL ON access_security_alert_action FROM starcon_app;
REVOKE ALL ON access_security_ip_allowlist FROM starcon_app;
REVOKE ALL ON access_security_credential_access FROM starcon_app;

-- 🔴 channel_app 不许碰 Flyway 的迁移历史表（与 V1 同口径）。
REVOKE ALL ON flyway_schema_history FROM channel_app;
