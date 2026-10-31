-- ===========================================================================
-- V414 · platform_api_call_monitor —— 18.1.1.3「调用监控」
-- ===========================================================================
--
-- **卷 18「集成开放与智能自动化」L3 18.1.1 API 管理 → 18.1.1.3 调用监控**
-- （蓝图 18-integration-and-automation.md 18.1.1.3 原文）：
--
--   「**职责**：监控 API 调用：QPS、错误率、限流命中、慢调用、按应用与接口维度分析，异常告警。
--     **输入**：调用埋点；配额配置。
--     **输出**：调用统计 + 告警。
--     **关系**：上游 = API 网关；下游 = 1 工作台告警、18.1.1.1 配额调整。
--     **外部系统调用错误要能被快速定位**，否则对接方只会说"你们接口有问题"。」
--
-- ---------------------------------------------------------------------------
-- 0. 这个模块**刻意**做什么 / 不做什么
-- ---------------------------------------------------------------------------
--
--   * **上游 = API 网关，而本仓今天没有网关** ⇒ 本迁移同时落两件事：
--       ① **埋点事实表**（调用真的被记下来）；
--       ② **网关入口的**跨租户凭证定位出口（SECURITY DEFINER 函数，见第三节）。
--     网关入口本身（校验凭证 / 限流判定 / 记埋点）在 Java 侧
--     `ApiGatewayService` / `ApiGatewayController`，对应 `/open/v1/**`。
--
--   * **🔴 网关入口不是开放代理**：它只放行**本模块真实实现的、可枚举的**端点形状
--     （ping / quota / scopes / credential / echo / calls / call-detail）。
--     其余一律 404。
--     与 18.1.1.2 刻意不放行 spec 代理是同一判断（放行后端不存在的映射比 404 更糟）。
--
--   * **🔴 跨租户读只走一个 SECURITY DEFINER 函数**（`platform_api_credential_lookup`）：
--     网关拿到一个 API Key 后必须能定位它属于哪个租户 —— 这是 18.1.1.1 的能力声明里
--     明确写「尚未提供、归 18.1.1.3」的那一格（见 AppCredentialService.capabilities()）。
--     本函数是**唯一**的跨租户出口，且只返回**一条**凭证的元信息（不含明文）。
--
--   * **不落任何金额**：调用监控没有单价 / 费用 / 账单 —— 对外 API 的计费属 18.1/18.2 的
--     商业条款，不在本模块。测试用 information_schema 断言本批表**无金额语义列**。
--
-- ---------------------------------------------------------------------------
-- 1. 表清单
-- ---------------------------------------------------------------------------
--
--   platform_api_call_log    —— 调用埋点（一次调用一行；追加）
--   platform_api_call_alert  —— 异常告警（OPEN / ACKNOWLEDGED / RESOLVED）
--
-- 两张表全部 tenant_id NOT NULL + ENABLE **与** FORCE RLS isolation + GRANT platform_app。
--
-- ---------------------------------------------------------------------------
-- 2. 「落库时间戳一律取 DB 时钟」
-- ---------------------------------------------------------------------------
--
--   `called_at` / `created_at` 的默认值是 `clock_timestamp()`（**不是** `now()`）：
--   `now()` 在一个事务里是**冻结**的（事务开始时刻），而一次网关调用会在同一个事务里
--   写多行；用 now() 会让「调用时刻」与「窗口切分」偏到事务开始那一刻。
--   `clock_timestamp()` 取的是**真实当前时刻**，同一事务内每行都不同。
--
--   ⚠️ 服务层仍然是「先 `select clock_timestamp()` 读出来、再用 `?::timestamptz` 绑定」：
--     读出后用 OffsetDateTime 绑定，避免 `Timestamp.valueOf(LocalDateTime)` 那种
--     静默偏 −28800s 的写法（跨时区偏移在夏令时切换那天会差 1 小时）。
--
-- ---------------------------------------------------------------------------
-- 3. 跨租户凭证定位（网关的唯一跨租户出口）
-- ---------------------------------------------------------------------------
--
--   网关的调用方**只有 API Key / Secret**，没有会话 ⇒ 租户必须由密钥反查。
--   `platform_app_credential.api_key_lookup` 是 API Key 的 **SHA-256 十六进制**摘要
--   （V377 明确说明：它是唯一能按值定位的列；Secret 侧刻意没有这样的列）。
--   于是定位路径是：调用方给的 Key → SHA-256 → `api_key_lookup` → 候选行 → BCrypt 比 Secret。
--
--   ⚠️ **为什么必须走 SECURITY DEFINER 函数**：`platform_app_credential` 与 `platform_app`
--     都是 FORCE RLS 表，裸 SQL 查询（不带租户上下文）读不到任何行。
--     与 identity V42 / V325 同一套纪律：跨租户读一律走 SECURITY DEFINER 函数，
--     函数内部自己再判一次闸门（这里判的是「密钥是否真的对得上」）。
--
--   ⚠️ **这个函数为什么能跨租户读到行**：函数是 SECURITY DEFINER，**以函数属主身份执行**。
--     迁移用的 `starcon_migrator` 是**超级用户**，超级用户**永远绕过 RLS（即使 FORCE）**
--     —— 这正是跨租户出口能工作的原因（identity 的 `session_access` 是同一套机制）。
--     运行角色 `platform_app` 是 NOBYPASSRLS：它自己写的裸 SQL 读不到任何行，
--     只能调这个函数，且函数只按 `api_key_lookup` 定位，不接受调用方传租户。
--
--   ⚠️ **可能返回多行**：V377 的唯一约束是 `UNIQUE(tenant_id, api_key_lookup)` ——
--     **同一个 Key 值可以在两个租户下各登记一次**。此时网关不能「随便挑一个」，
--     必须把候选行全部拿去比 Secret；比中了唯一一行才放行，比中多行 ⇒ 判为歧义并拒绝。
--     函数因此**不**做 LIMIT 1。
--
--   ⚠️ **不返回明文**：函数只回 BCrypt 摘要（`api_key_digest` / `secret_digest`）与状态，
--     明文密钥在库里根本不存在（V377 的「不落库明文」纪律）。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. platform_api_call_log —— 调用埋点
-- ---------------------------------------------------------------------------
--
-- 一行 = 一次**已归属租户**的对外 API 调用。
--
-- 🔴 **归属口径**：只有「调用方给的 API Key 能定位到一个租户」的调用才落这一行。
--    密钥完全对不上（Key 根本不存在）时**没有租户可归属** ⇒ 不落行 ——
--    硬塞一个租户等于把 A 租户的调用量算到 B 头上，比不记更坏。
--    但「Key 存在、Secret 错」/「应用被吊销」/「IP 不在白名单」/「授权范围不足」
--    /「超出配额」这几种**都能归属** ⇒ 全部落行，且 status_code / outcome 如实记。
--    这几种恰恰是对接方最需要被定位的失败。
--
-- 🔴 **追加不改**：调用埋点是事实表，不许 UPDATE / DELETE 改写历史
--     （与 platform_app_audit_event 同纪律：能改的记录等于没有记录）。
--
-- 🔴 **外键用默认的 NO ACTION（不 CASCADE）**：有调用记录的应用**不能被删除**，只能被吊销
--     （与 V377 的 platform_app_audit_event 同一口径）。
--     若这里写 ON DELETE CASCADE，删应用会连带删埋点 —— 而下面的「只追加」触发器
--     会把这次级联删除也拦下来（触发器对级联删除同样生效），结果是
--     「删应用」报一句看不懂的触发器错误。写成 NO ACTION 时报的是干净的 FK 违例。
--
CREATE TABLE platform_api_call_log (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 应用维度：这次调用是哪个应用发出来的。
  app_id uuid NOT NULL,
  -- 关联的凭证（轮换后仍能看出是哪一版发出的）。**不设外键**：
  -- 凭证可能被吊销或轮换，埋点必须留住当时那一版的痕迹。
  credential_id uuid,

  -- 接口维度：端点码（枚举，见 Java 侧 ApiGatewayEndpoints）+ 方法 + 路径。
  -- endpoint_code 是**分析维度**（按接口聚合），request_path 是**原始事实**（排查用）。
  endpoint_code varchar(64) NOT NULL,
  http_method varchar(8) NOT NULL,
  request_path varchar(200) NOT NULL,

  -- 结果：状态码 + 结论码。两者都留着 —— 状态码给外部看，结论码给内部聚合。
  status_code int NOT NULL,
  outcome varchar(24) NOT NULL
    CHECK(outcome IN (
      'SUCCESS','AUTH_FAILED','FORBIDDEN','RATE_LIMITED','NOT_FOUND','ERROR'
    )),
  -- 细分的失败原因（如 SECRET_MISMATCH / IP_NOT_ALLOWED / SCOPE_DENIED / QUOTA_EXCEEDED）。
  error_code varchar(48) NOT NULL DEFAULT '',

  -- 限流命中：🔴 与 status_code 分开记 ——
  --   429 既是「限流命中」也是「一次错误」，但分析「限流命中」时问的是
  --   「有多少次调用是被配额挡下来的」，而不是「有多少个 4xx」。
  rate_limited boolean NOT NULL DEFAULT false,

  -- 耗时：数量，NUMERIC(19,6)，禁浮点。
  duration_ms numeric(19,6) NOT NULL DEFAULT 0,

  -- 来源：客户端 IP（IP 白名单判定与排查都要它）与请求号（对端报障时能对上）。
  client_ip varchar(64) NOT NULL DEFAULT '',
  request_id varchar(64) NOT NULL DEFAULT '',

  -- 调用时刻：取 DB 时钟（见本文件第二节）。
  called_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  UNIQUE(tenant_id, id),

  FOREIGN KEY(tenant_id, app_id) REFERENCES platform_app(tenant_id, id),

  CHECK(status_code >= 100 AND status_code <= 599),
  CHECK(duration_ms >= 0),
  CHECK(btrim(endpoint_code) <> ''),
  CHECK(btrim(http_method) <> ''),
  -- 限流命中必须同时是 429：这两列口径分开放就是为了能这么互相钉死。
  CHECK(NOT rate_limited OR status_code = 429),
  -- 结论码与状态码必须**逐字对应**，否则「错误率」与「429 次数」两套口径会互相打架：
  --   错误率 = outcome <> 'SUCCESS' 的占比，429 次数 = rate_limited 的行数，
  --   两列一旦可以各说各话，页面上两个数字就对不上，排查时反而更迷路。
  CHECK(
    (outcome = 'SUCCESS' AND status_code BETWEEN 200 AND 399)
    OR (outcome = 'AUTH_FAILED' AND status_code = 401)
    OR (outcome = 'FORBIDDEN' AND status_code = 403)
    OR (outcome = 'RATE_LIMITED' AND status_code = 429)
    OR (outcome = 'NOT_FOUND' AND status_code = 404)
    OR (outcome = 'ERROR' AND status_code BETWEEN 500 AND 599)
  )
);

-- 按应用维度分析（QPS / 错误率 / 限流命中 / 慢调用 的主查询路径）。
CREATE INDEX platform_api_call_log_by_app
  ON platform_api_call_log(tenant_id, app_id, called_at DESC);
-- 按接口维度分析。
CREATE INDEX platform_api_call_log_by_endpoint
  ON platform_api_call_log(tenant_id, endpoint_code, called_at DESC);
-- 告警扫描的主查询路径（按应用 × 接口 × 时间取窗口内的事实）。
CREATE INDEX platform_api_call_log_by_app_endpoint
  ON platform_api_call_log(tenant_id, app_id, endpoint_code, called_at DESC);
-- 时间线（趋势图 / 调用明细列表）。
CREATE INDEX platform_api_call_log_by_time
  ON platform_api_call_log(tenant_id, called_at DESC);

-- ---------------------------------------------------------------------------
-- 2. platform_api_call_alert —— 异常告警
-- ---------------------------------------------------------------------------
--
-- 一行 = 一次「异常判定」的结果。
--
-- 🔴 **同一异常不刷屏**：部分唯一索引保证 (租户, 应用, 告警类型, 接口) 在**未闭环**期间
--   最多一行。重复触发时走 ON CONFLICT DO UPDATE 只刷新指标值与文案，
--   **不**改 created_at —— 否则告警列表会被同一件事刷成几十行。
--   闭环（RESOLVED）之后再次越线才会重新开一条。
--
-- 🔴 **告警指向下游两件事**：
--   · 卷 01 工作台告警 —— 消费面是 `GET /platform/call-monitor/alerts`（真分页 + 真筛选）；
--     ⚠️ 卷 01 的工作台告警服务**至今未交付**，所以本模块**只**把告警落在自己这里
--     并如实对外声明该下游不可用（见 ApiCallMonitorService.capabilities()）。
--   · 18.1.1.1 配额调整 —— 限流类告警带 `suggested_quota_window` / `suggested_quota_limit` /
--     `quota_adjust_target`（指向 18.1.1.1 的配额调整端点）。
--     本模块**不**替 18.1.1.1 改配额策略，只把「建议」算出来并指路。
--
CREATE TABLE platform_api_call_alert (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 应用维度：告警挂在哪个应用上（没有应用级告警 —— 判定一律按应用做）。
  app_id uuid NOT NULL,

  -- 告警类型：
  --   ERROR_RATE_HIGH   错误率越线
  --   SLOW_CALL         慢调用越线（p95 耗时）
  --   RATE_LIMIT_SPIKE  限流命中次数越线
  alert_type varchar(32) NOT NULL
    CHECK(alert_type IN ('ERROR_RATE_HIGH','SLOW_CALL','RATE_LIMIT_SPIKE')),
  severity varchar(16) NOT NULL CHECK(severity IN ('WARNING','CRITICAL')),
  status varchar(16) NOT NULL DEFAULT 'OPEN'
    CHECK(status IN ('OPEN','ACKNOWLEDGED','RESOLVED')),

  -- 接口维度：空串 = 应用级告警（限流命中是应用级；错误率与慢调用是接口级）。
  endpoint_code varchar(64) NOT NULL DEFAULT '',

  -- 指标值与阈值：⚠️ 数量一律 NUMERIC(19,6)，比率也用 numeric（禁浮点）。
  metric_value numeric(19,6) NOT NULL,
  threshold_value numeric(19,6) NOT NULL,
  -- 判定窗口（可复现：同一窗口重扫得到同一结论）。
  window_started_at timestamptz NOT NULL,
  window_ended_at timestamptz NOT NULL,
  window_seconds int NOT NULL,

  -- 人可读说明（不含 PII / 不含密钥）。
  message varchar(500) NOT NULL DEFAULT '',
  suggested_action varchar(500) NOT NULL DEFAULT '',

  -- 🔴 指向 18.1.1.1 配额调整的三个字段（限流类告警才有值）：
  --   suggested_quota_window  建议调整哪个窗口
  --   suggested_quota_limit   建议调成多少
  --   quota_adjust_target     到哪里去调（18.1.1.1 的端点形状）
  suggested_quota_window varchar(16) NOT NULL DEFAULT ''
    CHECK(suggested_quota_window IN ('','SECOND','MINUTE','HOUR','DAY','MONTH')),
  suggested_quota_limit numeric(19,6),
  quota_adjust_target varchar(200) NOT NULL DEFAULT '',

  -- 处置痕迹。
  acknowledged_at timestamptz,
  acknowledged_by uuid,
  resolved_at timestamptz,

  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),

  UNIQUE(tenant_id, id),

  FOREIGN KEY(tenant_id, app_id) REFERENCES platform_app(tenant_id, id),

  CHECK(metric_value >= 0),
  CHECK(threshold_value >= 0),
  CHECK(window_seconds > 0),
  CHECK(window_ended_at > window_started_at),
  -- 处置痕迹的三条一致性：
  --   · 确认过就不能退回 OPEN（「取消确认」不是一个动作）；
  --   · resolved_at 只有 RESOLVED 才有；
  --   · RESOLVED 必须带 resolved_at（否则分不清「已闭环」与「忘了记时间」）。
  CHECK(acknowledged_at IS NULL OR status <> 'OPEN'),
  CHECK(resolved_at IS NULL OR status = 'RESOLVED'),
  CHECK(status <> 'RESOLVED' OR resolved_at IS NOT NULL),
  -- 确认时间与确认人必须同时有或同时没有。
  CHECK((acknowledged_at IS NULL) = (acknowledged_by IS NULL))
);

-- 未闭环期间，同一异常最多一行。
CREATE UNIQUE INDEX platform_api_call_alert_open_unique
  ON platform_api_call_alert(tenant_id, app_id, alert_type, endpoint_code)
  WHERE status <> 'RESOLVED';

-- 告警列表（真分页 + 真筛选）。
CREATE INDEX platform_api_call_alert_by_status
  ON platform_api_call_alert(tenant_id, status, created_at DESC);
CREATE INDEX platform_api_call_alert_by_app
  ON platform_api_call_alert(tenant_id, app_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- 3. 跨租户凭证定位（网关的唯一跨租户出口）
-- ---------------------------------------------------------------------------
--
-- 见本文件第 3 节的说明。返回类型与查询结果**逐字一致**（PL/pgSQL 的 RETURN QUERY
-- 是严格类型比较，varchar(64) 与 text 不相等，不一致会在**第一次调用**时才炸）。
--
-- ⚠️ 不做 LIMIT 1：同一个 Key 值可能在两个租户下各登记一次（V377 的唯一约束是
--   UNIQUE(tenant_id, api_key_lookup)），必须把候选行全部交给调用方去比 Secret。
--
CREATE FUNCTION platform_api_credential_lookup(p_lookup text)
RETURNS TABLE(
  tenant_id uuid,
  app_id uuid,
  credential_id uuid,
  api_key_digest text,
  secret_digest text,
  api_key_prefix text,
  secret_hint text,
  credential_status text,
  credential_version int,
  app_status text,
  app_code text,
  app_name text)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path=public,pg_temp AS $$
  SELECT c.tenant_id,
         c.app_id,
         c.id,
         c.api_key_digest::text,
         c.secret_digest::text,
         c.api_key_prefix::text,
         c.secret_hint::text,
         c.status::text,
         c.version,
         a.status::text,
         a.app_code::text,
         a.app_name::text
  FROM platform_app_credential c
  JOIN platform_app a ON a.tenant_id = c.tenant_id AND a.id = c.app_id
  WHERE c.api_key_lookup = p_lookup;
$$;

GRANT EXECUTE ON FUNCTION platform_api_credential_lookup(text) TO platform_app;
REVOKE EXECUTE ON FUNCTION platform_api_credential_lookup(text) FROM PUBLIC;

-- ---------------------------------------------------------------------------
-- 4. 租户隔离（RLS）
-- ---------------------------------------------------------------------------
--
-- 两张表全部：tenant_id NOT NULL + ENABLE **与** FORCE RLS + policy isolation。
--
-- ⚠️ policy 只认 `app.current_tenant` 这个**事务局部** GUC，它由
--    TenantTransactions.set_config(..., true) 绑定 —— 事务结束即失效，
--    连接归还池中时**不留残留**（这正是 AGENTS.md 第三条隔离断言要验的）。
--
ALTER TABLE platform_api_call_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_api_call_log FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_api_call_log
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_api_call_alert ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_api_call_alert FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_api_call_alert
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

-- ---------------------------------------------------------------------------
-- 5. 授权
-- ---------------------------------------------------------------------------
--
-- 运行角色是 platform_app：读自己的埋点、写自己的埋点、读/写自己的告警。
--
-- ⚠️ 埋点表**只授 SELECT + INSERT**（不给 UPDATE / DELETE）。
--    「不可改、不可删」这件事由**数据层的触发器**钉死，而不是只靠少授一个权限码：
--    少授会被「迁移脚本用属主身份跑」绕开，触发器不会（属主也会被 FORCE RLS + 触发器管住）。
--    两个闸门都留着，权限码是「正常路径用不到」，触发器是「异常路径也拦得住」。
GRANT SELECT,INSERT ON platform_api_call_log TO platform_app;
GRANT SELECT,INSERT,UPDATE ON platform_api_call_alert TO platform_app;

-- 埋点只追加：拒绝 UPDATE（改写历史等于伪造历史）。
CREATE FUNCTION refuse_platform_api_call_log_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'platform_api_call_log 是只追加的调用埋点，不允许修改';
END $$;

CREATE TRIGGER guard_platform_api_call_log_update
  BEFORE UPDATE ON platform_api_call_log
  FOR EACH ROW EXECUTE FUNCTION refuse_platform_api_call_log_update();

-- 埋点只追加：拒绝 DELETE。
CREATE FUNCTION refuse_platform_api_call_log_delete() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'platform_api_call_log 是只追加的调用埋点，不允许删除';
END $$;

CREATE TRIGGER guard_platform_api_call_log_delete
  BEFORE DELETE ON platform_api_call_log
  FOR EACH ROW EXECUTE FUNCTION refuse_platform_api_call_log_delete();
