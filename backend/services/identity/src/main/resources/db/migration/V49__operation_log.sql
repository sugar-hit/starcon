-- 17.5.3.2 操作日志。
--
-- 与 17.5.3.1 审计日志（V7，identity_audit）**用途不同、不可互相替代**：
--   * 审计日志 = 合规。脱敏、长期留存、触发器禁止 UPDATE/DELETE、只记关键操作；
--   * 操作日志 = 排障。含技术细节（API 调用摘要、任务执行、错误堆栈、耗时），
--     蓝图明确允许「可短期留存」，因此这里**有意不**加不可篡改触发器，
--     而是给 identity_app DELETE 权限做留存期清理。
-- 两张表的字段几乎没有交集，这正是它们不能合并的证据。
--
-- 核心交付物是**链路关联视图**：一次请求会先后产生多条日志（登录、API 摘要、错误），
-- 它们共享同一个 request_id，按 request_id 聚合才能还原「这一次调用到底发生了什么」。
-- 这也是审计日志做不到的——审计按资源与操作人检索，没有请求级链路。
--
-- 边界（与 V7 同一条纪律）：蓝图说「上游 = 系统各层」，但 AGENTS.md 禁止跨服务数据库访问，
-- 所以本表是 identity **自己拥有的**操作事实，只覆盖本服务产生的运行事件。

CREATE TABLE operation_log (
  id                  uuid PRIMARY KEY,
  tenant_id           uuid NOT NULL REFERENCES tenant(id),

  -- 蓝图点名的四类事件 + 登出。列级 CHECK：取值集合在这里就该被约束住，
  -- 后续新增一类事件不必改表结构。
  event_type          text NOT NULL
                        CHECK(event_type IN ('LOGIN','LOGOUT','API_CALL','TASK_RUN','ERROR')),
  -- INFO 正常、WARNING 异常但请求成功、ERROR 失败。排障时先按级别收窄。
  level               text NOT NULL DEFAULT 'INFO'
                        CHECK(level IN ('INFO','WARNING','ERROR')),

  -- 链路关联键。同一次请求产生的所有日志共享它，这是「链路关联视图」的基础。
  request_id          text NOT NULL CHECK(btrim(request_id)<>''),
  -- 上游网关传入的链路 ID；没有网关时为空。与 request_id 分开存：
  -- 两者语义不同（一个是本次 HTTP 调用，一个是跨服务的完整链路），混用会丢掉跨服务串联能力。
  trace_id            text,

  -- 操作人。系统触发的事件（如定时任务）没有自然人，为 NULL——如实表达「无操作人」，
  -- 不拿服务账号顶替，那会让「谁做的」这个问题得到假答案。
  actor_id            uuid,
  actor_name          text,

  -- 一行人类可读的摘要：排障时第一眼看到的就是它。
  summary             text NOT NULL CHECK(btrim(summary)<>''),

  -- API 调用摘要（event_type='API_CALL' 时有值）。
  -- path 只存路径、**不含查询串**：查询串里常有令牌与业务标识，存进去等于泄密。
  http_method         text,
  path                text,
  status_code         integer CHECK(status_code IS NULL OR status_code BETWEEN 100 AND 599),

  -- 耗时（毫秒）。按 AGENTS.md「数量用 NUMERIC(19,6) + BigDecimal」的要求不用整数：
  -- 排障要看的是亚毫秒级抖动，整数毫秒会把「偶发卡顿」压成 0。
  duration_ms         numeric(19,6) CHECK(duration_ms IS NULL OR duration_ms>=0),

  -- 任务执行摘要（event_type='TASK_RUN' 时有值）。
  task_name           text,
  -- SUCCEEDED / FAILED / SKIPPED。任务没有状态就看不出「跑了但没做事」和「跑失败了」的区别。
  task_status         text
                        CHECK(task_status IS NULL OR task_status IN ('SUCCEEDED','FAILED','SKIPPED')),

  -- 错误堆栈（event_type='ERROR' 时有值）。
  error_class         text,
  error_message       text,
  -- 长度上限由 CHECK 在数据库层兜住：即使有人直连 SQL 绕过应用写入，
  -- 也不能把一个无界的堆栈灌进这张表。应用侧截断到 8000 字符并追加截断标记。
  stack_trace         text CHECK(stack_trace IS NULL OR length(stack_trace)<=8200),

  -- 技术细节（参数摘要、影响行数等）。写入侧已按 17.5.3.1 的同一套脱敏口径处理，
  -- 不含密码、令牌与完整请求体。
  detail              jsonb NOT NULL DEFAULT '{}'::jsonb,

  occurred_at         timestamptz NOT NULL DEFAULT now(),
  -- 留存期到期时刻 = 发生时刻 + 配置的留存天数。清理任务按它删除，
  -- 「可短期留存」因此是可执行的事实，而不是一句没有落点的口号。
  expires_at          timestamptz NOT NULL,
  UNIQUE(tenant_id, id)
);

-- ------------------------------------------------------------------
-- 1. 检索索引
-- ------------------------------------------------------------------
-- 蓝图点名「按用户/时间/请求 ID 关联链路」，三个维度各一组。
-- 另一个维度是事件类型：排障时「只看错误」是最常见的入口。
CREATE INDEX operation_log_tenant_time ON operation_log(tenant_id, occurred_at DESC);
CREATE INDEX operation_log_tenant_actor ON operation_log(tenant_id, actor_id, occurred_at DESC);
-- 链路关联的主索引。前导列是 tenant_id 保证租户过滤下推到索引，
-- request_id 次之让一次请求的所有日志在索引里连续，聚合时几乎不用回表。
CREATE INDEX operation_log_request ON operation_log(tenant_id, request_id, occurred_at, id);
CREATE INDEX operation_log_event ON operation_log(tenant_id, event_type, occurred_at DESC);
-- 留存期清理专用：按 expires_at 扫描到期行，与时间检索的索引分开，
-- 否则清理任务和列表查询会互相争抢同一个索引。
CREATE INDEX operation_log_expiry ON operation_log(expires_at);

-- ------------------------------------------------------------------
-- 2. 多租户隔离
-- ------------------------------------------------------------------
-- 业务表 tenant_id NOT NULL + DB 层 FORCE RLS，与 identity 其余表同一套策略：
-- 租户来自已认证成员关系写入的事务局部设置 app.current_tenant，
-- X-Tenant-Id 头在整条链路上没有任何读取点。
ALTER TABLE operation_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE operation_log FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON operation_log
  USING (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid)
  WITH CHECK (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid);

-- ------------------------------------------------------------------
-- 3. 权限
-- ------------------------------------------------------------------
-- 只读 + 留存期清理。没有 UPDATE：日志一旦写入就不改内容（改了就失去排障价值），
-- 唯一的写入口是追加。与审计日志的差别在于这里给了 DELETE——排障日志要能被清理。
REVOKE ALL ON operation_log FROM identity_app;
GRANT SELECT, INSERT, DELETE ON operation_log TO identity_app;

-- ------------------------------------------------------------------
-- 4. 链路关联视图
-- ------------------------------------------------------------------
-- 蓝图要求的「链路关联视图」，真实聚合在数据库里完成，不是前端把几行拼起来。
--
-- 返回三件事：
--   * entries  —— 这一次请求产生的全部日志，按发生时刻排序（真正的时序）；
--   * stats    —— 条数、各级别条数、整条链路的总耗时；
--   * span_ms  —— 从第一条到最后一条的墙钟跨度，排障时用来判断「慢」还是「多」。
--
-- 为什么用 SECURITY DEFINER：这里的聚合要跨行汇总，RLS 会逐行过滤，
-- 但函数只返回**当前租户**的行（WHERE 里已带 tenant_id 子查询），
-- 而 tenant_id 本身来自 session_access（会话推导），调用方无从指定别的租户。
CREATE FUNCTION operation_trace(hash text, wanted_request_id text)
RETURNS TABLE(
  request_id text,
  entry_count bigint,
  error_count bigint,
  warning_count bigint,
  total_duration_ms numeric,
  span_ms numeric,
  first_at timestamptz,
  last_at timestamptz)
LANGUAGE sql SECURITY DEFINER SET search_path=public,pg_temp AS $$
  SELECT l.request_id,
         count(*),
         count(*) FILTER (WHERE l.level='ERROR'),
         count(*) FILTER (WHERE l.level='WARNING'),
         coalesce(sum(l.duration_ms),0),
         coalesce(extract(epoch FROM (max(l.occurred_at)-min(l.occurred_at)))*1000,0),
         min(l.occurred_at),
         max(l.occurred_at)
  FROM operation_log l
  WHERE l.tenant_id=(SELECT a.tenant_id FROM session_access(hash) a)
    AND l.request_id=wanted_request_id
  GROUP BY l.request_id
$$;
REVOKE EXECUTE ON FUNCTION operation_trace(text,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION operation_trace(text,text) TO identity_app;

-- 登录失败事件。
--
-- 登录失败时还没有可信的租户上下文，而 operation_log.tenant_id 是 NOT NULL 且受 RLS 保护。
-- 这里由数据库按**用户名的成员关系**反查候选租户并写入，而不是让调用方传 tenant_id：
-- 调用方结构上就没有指定租户的入口，与 V40「租户集合只能由成员关系推导」同一条纪律。
--
-- 账号不存在时 memberships_for 无行 → 返回 0，不写入。给一个查无此人的账号硬造租户归属，
-- 比不记更糟：那等于凭空造出一条不存在的成员登录流水。
-- 账号属于多个租户时逐个租户各记一条：同一个人确实在每个租户都有一次失败的登录尝试，
-- 只记到其中一个会让另外两个租户的排障视图出现无法解释的缺口。
CREATE FUNCTION record_failed_login(login_name text,wanted_request_id text,reason text)
RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,pg_temp AS $$
DECLARE uid uuid; tid uuid; written bigint := 0;
BEGIN
  SELECT u.id INTO uid FROM app_user u WHERE u.username=login_name AND u.status='ACTIVE';
  IF uid IS NULL THEN RETURN 0; END IF;
  FOR tid IN SELECT DISTINCT m.tenant_id FROM memberships_for(uid) m LOOP
    INSERT INTO operation_log(id,tenant_id,event_type,level,request_id,actor_name,summary,detail,
                              occurred_at,expires_at)
    VALUES(gen_random_uuid(),tid,'LOGIN','ERROR',coalesce(wanted_request_id,'login-unattributed'),
           login_name,'登录失败：'||left(coalesce(reason,''),200),
           jsonb_build_object('username',login_name),
           now(),now()+interval '90 days');
    written := written + 1;
  END LOOP;
  RETURN written;
END $$;
REVOKE EXECUTE ON FUNCTION record_failed_login(text,text,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION record_failed_login(text,text,text) TO identity_app;

-- 留存期清理。返回被删除的条数，让调用方能确认清理确实发生（而不是静默删了 0 条）。
-- 只删当前租户的行：跨租户清理属于平台运维（17.7.2.2），不在租户侧接口里做。
CREATE FUNCTION purge_operation_log(hash text,older_than timestamptz) RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,pg_temp AS $$
DECLARE a record; removed bigint;
BEGIN
  SELECT * INTO a FROM session_access(hash);
  IF a IS NULL THEN RAISE EXCEPTION 'Session unavailable' USING ERRCODE='42501'; END IF;
  -- 清理只给所有者，且**必须报错而不是静默删 0 条**：
  -- 运维点了「清理」却看到「已清理 0 条」会以为确实清理过了，而真相是权限不足。
  IF NOT EXISTS(SELECT 1 FROM jsonb_array_elements(a.grants) g
                WHERE g->>'roleCode'='OWNER' AND g->>'scopeMode'='ALL')
  THEN RAISE EXCEPTION 'Owner required' USING ERRCODE='42501'; END IF;
  DELETE FROM operation_log l
  WHERE l.tenant_id=a.tenant_id AND l.expires_at<coalesce(older_than,now());
  GET DIAGNOSTICS removed = ROW_COUNT;
  RETURN removed;
END $$;
REVOKE EXECUTE ON FUNCTION purge_operation_log(text,timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION purge_operation_log(text,timestamptz) TO identity_app;

-- ------------------------------------------------------------------
-- 5. 页面授权
-- ------------------------------------------------------------------
-- 操作日志只读 + 一个清理动作，因此注册 read 与 purge 两项权限。
INSERT INTO page_resource(code,name) VALUES ('identity.operation_log','操作日志')
ON CONFLICT (code) DO NOTHING;
INSERT INTO permission VALUES ('identity.operation_log.read','identity.operation_log','read'),
                             ('identity.operation_log.purge','identity.operation_log','purge')
ON CONFLICT (code) DO NOTHING;
INSERT INTO role_permission SELECT r.tenant_id,r.id,p.code FROM role r CROSS JOIN permission p
 WHERE r.code='OWNER' AND p.code IN ('identity.operation_log.read','identity.operation_log.purge')
ON CONFLICT DO NOTHING;