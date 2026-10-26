-- 17.7.2.2 平台运维视图（阶段 D7 · L3 锚点 17.7.2）。
--
-- 蓝图原文（docs/product/capability-blueprint/17-org-permission-and-settings.md 第 292-297 行）：
--   ### 17.7.2.2 平台运维视图
--   - 职责：平台管理员视角的跨租户运维视图：租户在线状态、错误率、任务积压、容量与成本，
--           **严格隔离租户业务数据**，不展示单租户的客户/订单/PII 明细。
--   - 输入：系统指标与运维事件。
--   - 输出：平台健康看板；告警。
--   - 关系：上游 = 全部服务运维指标；下游 = 平台告警。
--           **跨租户视图只看技术指标不看业务数据**，是 SaaS 多租户隔离的红线。
--
-- ===========================================================================
-- 一、🔴 本模块的红线怎么在**表结构层面**落地
-- ===========================================================================
-- 「跨租户视图只看技术指标不看业务数据」这句话，靠「代码里记得别查」是守不住的 ——
-- 今天没人查，明天就有人加一个 name / description 列。本模块把它落成**三条结构性事实**：
--
--   1. **指标表里没有任何自由文本列。**
--      platform_ops_metric 的每一个字符型列（service_code / metric_code / unit /
--      severity / source）都被 CHECK **钉死在一个有限枚举**上。枚举里只有
--      「identity / ERROR_RATE / RATIO / OK / PUSH」这类**技术词**，
--      没有一个位置能塞进「ACME 有限公司」「订单号 A123」「张三」这种业务文本。
--      ⇒ 业务数据在结构上**塞不进来**（集成测试逐列断言：每个字符列都必须挂着一个
--        取值域 CHECK，且列名不得是 name/description/note/message 这类自由文本列名）。
--
--   2. **量纲与指标码是绑定的。**
--      metric_code 与 unit 必须成对出现（ERROR_RATE 只能是 RATIO、TASK_BACKLOG 只能是
--      COUNT……），RATIO 类的取值必须落在 0~1，ONLINE_STATUS 只允许 0/1。
--      ⇒ 「把某个业务金额当成一个 COUNT 塞进来」这一类污染在写入时就被 CHECK 挡下。
--
--   3. **表里根本没有业务对象。**
--      本模块的四张表里没有订单、没有商品、没有客户、没有收付款单据。
--      指标是**技术量**（次数、比率、毫秒、字节），告警是**技术量与阈值的比较结果**，
--      成本是**容量 / 调用量口径的估算数量**（不是金额，见第三节）。
--
-- 另外：**跨租户的读出口只有 SECURITY DEFINER 函数**（第七节），应用角色在
-- 普通事务里读这两张租户级表只能看见自己租户的行（RLS FORCE isolation）。
--
-- ===========================================================================
-- 二、表清单与分层
-- ===========================================================================
--   租户级（tenant_id NOT NULL + ENABLE/FORCE RLS isolation）
--     platform_ops_metric    技术指标采样（事实；只追加 / 同窗口可覆盖，不给 DELETE）
--     platform_ops_alert     由阈值比较产生的告警（事实 + 状态流转；不给 DELETE）
--
--   平台级（**不带 tenant_id**、不参与租户 RLS、只 GRANT SELECT 给 identity_app）
--     platform_ops_threshold 告警阈值（出厂口径；写入走 SECURITY DEFINER 函数）
--     platform_ops_cost_model 成本估算口径的权重（出厂口径；只读）
--
-- 为什么阈值与成本权重是平台级：它们回答的是「平台用什么口径判断告警 / 估算成本」，
-- 与具体租户无关 —— 和 V53 的 dict_catalog、V323 的 saas_plan 同一类全局码表。
-- 真要按租户存一份，平台改一次就要改 N 行。
--
-- ===========================================================================
-- 三、🔴 「成本」是数量，不是金额
-- ===========================================================================
-- 蓝图职责里写的是「容量与成本」。本模块的「成本」是**容量 / 调用量口径的估算**：
--   cost_estimate = Σ(最近一次采样值 × 口径权重)
-- 全部是**数量**（NUMERIC(19,6)），没有发票 / 应收 / 付款 / 结算 / 收入确认 ——
-- 那些是 12 财务域的事，本仓还没有那个服务。
-- 集成测试会用 information_schema 断言：本模块四张表**没有任何金额语义列**
-- （amount / price / fee / balance / tax / currency 之类一律不许出现）。
--
-- ===========================================================================
-- 四、平台运营闸门
-- ===========================================================================
-- 与 17.7.1.1 同一套三道闸门（照抄 saas_platform_gate）：
--   ① 会话租户里存在一条 saas_platform_role 登记；
--   ② 被登记的 roleId 正是会话持有的角色且 scope_mode='ALL'；
--   ③ 该角色分配带有所需权限码。
-- 少一条都拒绝。权限码刻意**不授 OWNER**（17.7 平台运营不对租户开放）。
--
-- ===========================================================================
-- 五、边界
-- ===========================================================================
-- 上游「全部服务运维指标」的**采集器**在本仓尚未接入（backend/libraries 下没有
-- observability / 事件信封库），本模块只**提供接入面**（POST 指标接入，内部密钥路径）
-- 与**消费面**（看板 + 告警）。采集接入是后续待办，在 capabilities 里如实标注。

-- ===========================================================================
-- 六、platform_ops_metric · 技术指标采样（租户数据）
-- ===========================================================================
CREATE TABLE platform_ops_metric (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id    uuid NOT NULL REFERENCES tenant(id),
  -- 采集方服务。取值域是**平台自己的服务清单**，不是业务对象。
  service_code varchar(32) NOT NULL
               CHECK(service_code IN ('identity','catalog','channel','trade','warehouse','finance','web')),
  -- 技术指标码。取值域里**没有任何业务概念**（没有 order / sku / customer / amount）。
  metric_code  varchar(40) NOT NULL
               CHECK(metric_code IN (
                 'ONLINE_STATUS',       -- 在线状态（0/1）
                 'ERROR_RATE',          -- 请求错误率（0~1）
                 'ERROR_COUNT',         -- 错误请求数
                 'REQUEST_COUNT',       -- 请求数
                 'REQUEST_LATENCY_MS',  -- 响应延迟（毫秒）
                 'AVAILABILITY',        -- 可用性（0~1）
                 'TASK_BACKLOG',        -- 任务积压
                 'QUEUE_DEPTH',         -- 队列深度
                 'CPU_UTILIZATION',     -- CPU 占用（0~1）
                 'MEMORY_UTILIZATION',  -- 内存占用（0~1）
                 'STORAGE_UTILIZATION',  -- 存储占用（0~1）
                 'STORAGE_USED_BYTES',  -- 存储用量（字节）
                 'API_CALL_COUNT',      -- API 调用量（成本口径）
                 'AI_CALL_COUNT'        -- AI 调用量（成本口径）
               )),
  -- 采样值。**数量**，禁浮点。
  sample_value numeric(19,6) NOT NULL CHECK(sample_value >= 0),
  unit         varchar(16) NOT NULL
               CHECK(unit IN ('RATIO','COUNT','MILLISECOND','BYTE','STATE')),
  severity     varchar(16) NOT NULL DEFAULT 'OK'
               CHECK(severity IN ('OK','WARNING','CRITICAL')),
  window_start timestamptz NOT NULL,
  window_end   timestamptz NOT NULL,
  source       varchar(16) NOT NULL DEFAULT 'PUSH'
               CHECK(source IN ('PUSH','SCHEDULER')),
  created_at   timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 同一租户 / 服务 / 指标 / 同一采集窗口只留一行：采集器重试不会把曲线打乱。
  UNIQUE(tenant_id, service_code, metric_code, window_start, window_end),
  CHECK(window_end > window_start),
  -- 🔴 量纲与指标码绑定：指标是什么，单位就必须是什么。
  CHECK(
    (metric_code IN ('ERROR_RATE','CPU_UTILIZATION','MEMORY_UTILIZATION','STORAGE_UTILIZATION','AVAILABILITY')
      AND unit='RATIO')
    OR (metric_code IN ('ERROR_COUNT','REQUEST_COUNT','TASK_BACKLOG','QUEUE_DEPTH','API_CALL_COUNT','AI_CALL_COUNT')
      AND unit='COUNT')
    OR (metric_code='REQUEST_LATENCY_MS' AND unit='MILLISECOND')
    OR (metric_code='STORAGE_USED_BYTES' AND unit='BYTE')
    OR (metric_code='ONLINE_STATUS' AND unit='STATE')
  ),
  -- 比率类的取值必须落在 0~1：把「错误率」写成 500 是不可能的。
  CHECK(unit <> 'RATIO' OR sample_value <= 1),
  -- 在线状态只允许 0 / 1。
  CHECK(metric_code <> 'ONLINE_STATUS' OR sample_value IN (0,1))
);

COMMENT ON TABLE platform_ops_metric IS
  '17.7.2.2 · 技术指标采样（租户数据：tenant_id NOT NULL + RLS FORCE isolation）。'
  '🔴 全部字符型列都被 CHECK 钉死在技术枚举上，表里没有任何自由文本列 —— '
  '业务数据在结构上塞不进来（这是「跨租户视图只看技术指标」的结构性保证）。';

CREATE INDEX platform_ops_metric_list ON platform_ops_metric(window_end DESC, id DESC);
CREATE INDEX platform_ops_metric_tenant ON platform_ops_metric(tenant_id, metric_code, window_end DESC);

-- ===========================================================================
-- 七、platform_ops_alert · 告警（租户数据）
-- ===========================================================================
-- 告警是**技术量与阈值的比较结果**，不是一段描述文本：库里没有 message / description
-- 列，告警的人话由接口层用枚举码拼出来（前端也有一份同样的映射）。
CREATE TABLE platform_ops_alert (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       uuid NOT NULL REFERENCES tenant(id),
  alert_code      varchar(32) NOT NULL
                  CHECK(alert_code IN (
                    'ERROR_RATE_HIGH','TASK_BACKLOG_HIGH','QUEUE_DEPTH_HIGH',
                    'CPU_PRESSURE','MEMORY_PRESSURE','STORAGE_PRESSURE',
                    'LATENCY_HIGH','AVAILABILITY_LOW','TENANT_OFFLINE')),
  metric_code     varchar(40) NOT NULL,
  service_code    varchar(32) NOT NULL
                  CHECK(service_code IN ('identity','catalog','channel','trade','warehouse','finance','web')),
  severity        varchar(16) NOT NULL CHECK(severity IN ('WARNING','CRITICAL')),
  -- 观测值与阈值都是**数量**。
  observed_value  numeric(19,6) NOT NULL CHECK(observed_value >= 0),
  threshold_value numeric(19,6) NOT NULL CHECK(threshold_value >= 0),
  status          varchar(16) NOT NULL DEFAULT 'OPEN'
                  CHECK(status IN ('OPEN','ACKNOWLEDGED','RESOLVED')),
  raised_at       timestamptz NOT NULL,
  acknowledged_at timestamptz,
  resolved_at     timestamptz,
  created_at      timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, id),
  -- 告警码与指标码必须成对，杜绝「错误率告警挂到队列深度上」这种脏数据。
  CHECK(
    (metric_code='ERROR_RATE'        AND alert_code='ERROR_RATE_HIGH')
    OR (metric_code='TASK_BACKLOG'   AND alert_code='TASK_BACKLOG_HIGH')
    OR (metric_code='QUEUE_DEPTH'    AND alert_code='QUEUE_DEPTH_HIGH')
    OR (metric_code='CPU_UTILIZATION' AND alert_code='CPU_PRESSURE')
    OR (metric_code='MEMORY_UTILIZATION' AND alert_code='MEMORY_PRESSURE')
    OR (metric_code='STORAGE_UTILIZATION' AND alert_code='STORAGE_PRESSURE')
    OR (metric_code='REQUEST_LATENCY_MS' AND alert_code='LATENCY_HIGH')
    OR (metric_code='AVAILABILITY'    AND alert_code='AVAILABILITY_LOW')
    OR (metric_code='ONLINE_STATUS'   AND alert_code='TENANT_OFFLINE')
  ),
  -- 状态与时间戳必须自洽：已确认的告警必须有确认时刻，已解决的必须有解决时刻。
  CHECK(status<>'ACKNOWLEDGED' OR acknowledged_at IS NOT NULL),
  CHECK(status<>'RESOLVED' OR resolved_at IS NOT NULL)
);

COMMENT ON TABLE platform_ops_alert IS
  '17.7.2.2 · 平台告警（租户数据：tenant_id NOT NULL + RLS FORCE isolation）。'
  '告警是技术量与阈值的比较结果：没有自由文本列，人话由接口层按枚举码拼。';

CREATE INDEX platform_ops_alert_list ON platform_ops_alert(tenant_id, status, raised_at DESC);
CREATE INDEX platform_ops_alert_time ON platform_ops_alert(raised_at DESC);
-- 同一租户 / 服务 / 指标至多一条未闭环告警（部分唯一索引）。
CREATE UNIQUE INDEX platform_ops_alert_unresolved
  ON platform_ops_alert(tenant_id, service_code, metric_code) WHERE status <> 'RESOLVED';

-- ===========================================================================
-- 八、platform_ops_threshold · 告警阈值（平台级，不带 tenant_id）
-- ===========================================================================
CREATE TABLE platform_ops_threshold (
  metric_code       varchar(40) PRIMARY KEY
                    CHECK(metric_code IN (
                      'ERROR_RATE','TASK_BACKLOG','QUEUE_DEPTH','CPU_UTILIZATION',
                      'MEMORY_UTILIZATION','STORAGE_UTILIZATION','REQUEST_LATENCY_MS',
                      'AVAILABILITY','ONLINE_STATUS')),
  warn_threshold    numeric(19,6) NOT NULL CHECK(warn_threshold >= 0),
  critical_threshold numeric(19,6) NOT NULL CHECK(critical_threshold >= 0),
  -- GT：越大越糟（超过阈值即告警）；LT：越小越糟（低于阈值即告警）。
  comparison        varchar(8) NOT NULL CHECK(comparison IN ('GT','LT')),
  enabled           boolean NOT NULL DEFAULT true,
  updated_at        timestamptz NOT NULL DEFAULT now(),
  -- 两级阈值必须与比较方向自洽：GT 时 warning < critical；LT 时 critical < warning。
  CHECK((comparison='GT' AND warn_threshold < critical_threshold)
     OR (comparison='LT' AND critical_threshold < warn_threshold))
);

COMMENT ON TABLE platform_ops_threshold IS
  '17.7.2.2 · 告警阈值（平台级：全局唯一一份，不带 tenant_id，不参与租户 RLS，'
  '只 GRANT SELECT 给 identity_app；写入走 SECURITY DEFINER 函数）。';

-- ===========================================================================
-- 九、platform_ops_cost_model · 成本估算口径的权重（平台级，不带 tenant_id）
-- ===========================================================================
-- 「成本」= Σ(最近一次采样值 × 权重)，是**容量 / 调用量口径的数量估算**，不是金额。
-- 权重是出厂口径（随代码发布，只读），与应用角色能否改无关 —— 改口径要走迁移。
CREATE TABLE platform_ops_cost_model (
  metric_code varchar(40) PRIMARY KEY
               CHECK(metric_code IN (
                 'CPU_UTILIZATION','MEMORY_UTILIZATION','STORAGE_UTILIZATION',
                 'REQUEST_COUNT','API_CALL_COUNT','AI_CALL_COUNT')),
  weight      numeric(19,6) NOT NULL CHECK(weight >= 0),
  enabled     boolean NOT NULL DEFAULT true,
  updated_at  timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE platform_ops_cost_model IS
  '17.7.2.2 · 成本估算口径（平台级：容量 / 调用量 → 估算单位数量的权重表；'
  '不是金额，不落发票 / 应收 / 付款 / 结算语义）。';

-- ===========================================================================
-- 十、租户隔离（RLS）
-- ===========================================================================
-- 指标与告警是**租户数据**：按 AGENTS.md 必须 tenant_id NOT NULL + DB 层 RLS，
-- 并参与多租户三断言（两租户互不可见 / 伪造租户头无效 / 连接池归还后不串租户）。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['platform_ops_metric','platform_ops_alert'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- ===========================================================================
-- 十一、授权
-- ===========================================================================
-- 租户级表：正常读写，但**不给 DELETE** —— 指标与告警是事实，不提供物理删除。
-- 平台级表：只读。写入走下面的 SECURITY DEFINER 函数。
GRANT SELECT,INSERT,UPDATE ON platform_ops_metric TO identity_app;
GRANT SELECT,INSERT,UPDATE ON platform_ops_alert TO identity_app;
GRANT SELECT ON platform_ops_threshold TO identity_app;
GRANT SELECT ON platform_ops_cost_model TO identity_app;

-- ===========================================================================
-- 十二、平台运营闸门与角色登记
-- ===========================================================================
-- 与 V323 的 saas_platform_gate 同一套三道条件，只是错误信息与本模块对齐。
-- SECURITY DEFINER：saas_platform_role 受 RLS 保护，而判定要在**平台租户自己的上下文**
-- 里读它，函数的 owner 不受行级策略影响，判定口径不会随调用方上下文漂移。
CREATE FUNCTION platform_ops_gate(hash text, needed_permission text) RETURNS uuid
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=public,pg_temp AS $$
DECLARE a record; matched int; BEGIN
  SELECT * INTO a FROM session_access(hash);
  IF a IS NULL THEN RAISE EXCEPTION 'Session unavailable' USING ERRCODE='42501'; END IF;
  SELECT count(*) INTO matched
  FROM saas_platform_role pr
  WHERE pr.tenant_id=a.tenant_id
    AND needed_permission=ANY(a.permissions)
    AND EXISTS (
      SELECT 1 FROM jsonb_array_elements(a.grants) g
      WHERE g->>'roleId'=pr.role_id::text AND g->>'scopeMode'='ALL');
  IF matched=0 THEN
    RAISE EXCEPTION '平台运维视图是平台运营域，当前角色没有此操作权限' USING ERRCODE='42501';
  END IF;
  RETURN a.tenant_id;
END $$;

COMMENT ON FUNCTION platform_ops_gate(text,text) IS
  '17.7.2.2 · 平台运营三道闸门（与 17.7.1.1 的 saas_platform_gate 同构）：'
  '① 会话租户有 saas_platform_role 登记；② 登记的 roleId 正是会话持有的角色且 scope_mode=ALL；'
  '③ 该角色带有所需权限码。权限码刻意不授 OWNER。';

-- 登记平台运营角色（平台运营侧专用路径，浏览器打不到）。
--
-- 与 V323 的 saas_platform_register_role 同一套形状，区别只在于**授哪一组权限码**：
-- 本模块授 identity.platform_ops.*，不碰 identity.plan.* / identity.subscription.*。
-- 登记的是 roleId 而不是角色编码，所以别的租户建一个同名角色也不算数。
CREATE FUNCTION platform_ops_register_role(hash text, requested_code text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,pg_temp AS $$
DECLARE a record; rid uuid; tid uuid; BEGIN
  SELECT * INTO a FROM session_access(hash);
  IF a IS NULL THEN RAISE EXCEPTION 'Session unavailable' USING ERRCODE='42501'; END IF;
  IF requested_code !~ '^[A-Z][A-Z0-9_]{1,63}$' THEN
    RAISE EXCEPTION 'Malformed platform role code' USING ERRCODE='23514';
  END IF;
  tid := a.tenant_id;
  SELECT id INTO rid FROM role WHERE tenant_id=tid AND code=requested_code;
  IF rid IS NULL THEN
    rid := gen_random_uuid();
    INSERT INTO role(id,tenant_id,code,name,status)
      VALUES(rid,tid,requested_code,'平台运维','ACTIVE');
  END IF;
  INSERT INTO role_permission(tenant_id,role_id,permission_code)
    SELECT tid,rid,p.code FROM permission p WHERE p.code LIKE 'identity.platform_ops.%'
    ON CONFLICT DO NOTHING;
  INSERT INTO saas_platform_role(tenant_id,role_id,role_code,registered_by)
    VALUES(tid,rid,requested_code,a.user_id)
    ON CONFLICT (tenant_id,role_id) DO NOTHING;
  INSERT INTO role_assignment(tenant_id,membership_id,role_id,scope_mode,assigned_by)
    VALUES(tid,a.membership_id,rid,'ALL',a.user_id)
    ON CONFLICT DO NOTHING;
END $$;

COMMENT ON FUNCTION platform_ops_register_role(text,text) IS
  '17.7.2.2 · 登记平台运维角色（服务端到服务端专用路径，要内部密钥 X-Internal-Key）。'
  '全程不接收租户 id：租户一律来自 session_access 推导出的成员关系。';

-- 目标租户是否存在。
--
-- identity_app 对 tenant 表**没有任何权限**（租户集合只能由已认证成员关系推导），
-- 所以「这个租户存在吗」必须放在 SECURITY DEFINER 里判。
CREATE FUNCTION platform_ops_tenant_exists(target_tenant uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path=public,pg_temp AS $$
  SELECT EXISTS (SELECT 1 FROM tenant WHERE id=target_tenant);
$$;

-- ===========================================================================
-- 十三、跨租户读出口（平台运营看板的唯一读路径）
-- ===========================================================================
-- ADR-0005「每次业务事务选定一个明确目标租户」：跨租户读**不**把 RLS 放大成
-- 「可见全部后代」，而是用一条 SECURITY DEFINER 函数承担这次跨租户读取，
-- 并且函数内部**自己再判一次闸门**（platform_ops_gate）—— 应用层漏判不会让数据漏出去。
--
-- ⚠️ 返回列的类型必须与查询结果**逐字一致**（varchar(32) 与 text 不相等），
--    且必须显式起别名（PL/pgSQL 的 RETURN QUERY 会把结果集的列名换成查询里的列名）。
--
-- 🔴 这里返回的每一列都是**技术量**或**租户身份**，没有任何业务数据：
--    租户编码 / 名称是平台自己的客户档案（与 17.7.1.1 的 saas_platform_subscription_list
--    同一口径），指标列全是数量。
CREATE FUNCTION platform_ops_metric_list(
  hash text, p_tenant uuid, p_service text, p_metric text, p_limit int, p_offset int)
RETURNS TABLE(
  id uuid, tenant_id uuid, service_code varchar(32), metric_code varchar(40),
  sample_value numeric(19,6), unit varchar(16), severity varchar(16),
  window_start timestamptz, window_end timestamptz, source varchar(16), created_at timestamptz)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=public,pg_temp AS $$
BEGIN
  PERFORM platform_ops_gate(hash,'identity.platform_ops.read');
  RETURN QUERY
  SELECT m.id AS id, m.tenant_id AS tenant_id, m.service_code AS service_code,
    m.metric_code AS metric_code, m.sample_value AS sample_value, m.unit AS unit,
    m.severity AS severity, m.window_start AS window_start, m.window_end AS window_end,
    m.source AS source, m.created_at AS created_at
  FROM platform_ops_metric m
  WHERE (p_tenant IS NULL OR m.tenant_id=p_tenant)
    AND (p_service IS NULL OR p_service='' OR m.service_code=p_service)
    AND (p_metric IS NULL OR p_metric='' OR m.metric_code=p_metric)
  ORDER BY m.window_end DESC, m.id DESC
  LIMIT greatest(coalesce(p_limit,50),1) OFFSET greatest(coalesce(p_offset,0),0);
END $$;

-- 同一批过滤条件下的总条数（真分页要用的总数，不是编出来的）。
CREATE FUNCTION platform_ops_metric_total(
  hash text, p_tenant uuid, p_service text, p_metric text)
RETURNS bigint
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=public,pg_temp AS $$
BEGIN
  PERFORM platform_ops_gate(hash,'identity.platform_ops.read');
  RETURN (
    SELECT count(*) FROM platform_ops_metric m
    WHERE (p_tenant IS NULL OR m.tenant_id=p_tenant)
      AND (p_service IS NULL OR p_service='' OR m.service_code=p_service)
      AND (p_metric IS NULL OR p_metric='' OR m.metric_code=p_metric));
END $$;

-- 告警列表（跨租户）。
CREATE FUNCTION platform_ops_alert_list(
  hash text, p_status text, p_severity text, p_tenant uuid, p_limit int, p_offset int)
RETURNS TABLE(
  id uuid, tenant_id uuid, alert_code varchar(32), metric_code varchar(40),
  service_code varchar(32), severity varchar(16), observed_value numeric(19,6),
  threshold_value numeric(19,6), status varchar(16),
  raised_at timestamptz, acknowledged_at timestamptz, resolved_at timestamptz)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=public,pg_temp AS $$
BEGIN
  PERFORM platform_ops_gate(hash,'identity.platform_ops.read');
  RETURN QUERY
  SELECT a.id AS id, a.tenant_id AS tenant_id, a.alert_code AS alert_code,
    a.metric_code AS metric_code, a.service_code AS service_code, a.severity AS severity,
    a.observed_value AS observed_value, a.threshold_value AS threshold_value,
    a.status AS status, a.raised_at AS raised_at, a.acknowledged_at AS acknowledged_at,
    a.resolved_at AS resolved_at
  FROM platform_ops_alert a
  WHERE (p_status IS NULL OR p_status='' OR a.status=p_status)
    AND (p_severity IS NULL OR p_severity='' OR a.severity=p_severity)
    AND (p_tenant IS NULL OR a.tenant_id=p_tenant)
  ORDER BY a.raised_at DESC, a.id DESC
  LIMIT greatest(coalesce(p_limit,50),1) OFFSET greatest(coalesce(p_offset,0),0);
END $$;

CREATE FUNCTION platform_ops_alert_total(
  hash text, p_status text, p_severity text, p_tenant uuid)
RETURNS bigint
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=public,pg_temp AS $$
BEGIN
  PERFORM platform_ops_gate(hash,'identity.platform_ops.read');
  RETURN (
    SELECT count(*) FROM platform_ops_alert a
    WHERE (p_status IS NULL OR p_status='' OR a.status=p_status)
      AND (p_severity IS NULL OR p_severity='' OR a.severity=p_severity)
      AND (p_tenant IS NULL OR a.tenant_id=p_tenant));
END $$;

-- 告警属于哪个租户：跨租户写（确认 / 解决）之前必须先确定「本次事务的目标租户」。
-- 找不到时返回 NULL 而不是抛错：「不存在」是 404，「没有权限」是 403。
CREATE FUNCTION platform_ops_alert_tenant(hash text, p_alert uuid, p_permission text)
RETURNS uuid
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=public,pg_temp AS $$
DECLARE tid uuid; BEGIN
  PERFORM platform_ops_gate(hash,p_permission);
  SELECT tenant_id INTO tid FROM platform_ops_alert WHERE id=p_alert;
  RETURN tid;
END $$;

-- ---------------------------------------------------------------------------
-- 13.1 平台健康看板：每个租户一行的技术健康摘要
-- ---------------------------------------------------------------------------
-- 口径说明（写在这里是为了让读的人不必猜）：
--   * 「最近一次采样」= 该租户 / 服务 / 指标中 window_end 最大的一条（并列时取
--     created_at、id 最大的一条）。**没有采样就是没有值**（NULL），绝不补 0 ——
--     补 0 会被读成「指标是 0」，那是把「没数据」说成了事实。
--   * 跨服务聚合取**最差值**：错误率 / 积压 / 延迟 / 占用取 max，可用性与在线状态取 min。
--     只要有一个服务报离线，租户整体就判为不在线。
--   * 成本估算按**租户级最近一次采样**（每个指标码取全局最近的一条）加权求和，
--     避免同一个指标被多个服务重复计入。
CREATE FUNCTION platform_ops_tenant_health(hash text)
RETURNS TABLE(
  tenant_id uuid, tenant_code text, tenant_name text, tenant_status text,
  online boolean, error_rate numeric(19,6), task_backlog numeric(19,6),
  queue_depth numeric(19,6), storage_utilization numeric(19,6),
  availability numeric(19,6), latency_ms numeric(19,6),
  cost_estimate numeric(19,6), open_alert_count bigint, critical_alert_count bigint,
  last_sample_at timestamptz)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=public,pg_temp AS $$
BEGIN
  PERFORM platform_ops_gate(hash,'identity.platform_ops.read');
  RETURN QUERY
  WITH per_service AS (
    SELECT DISTINCT ON (m.tenant_id, m.service_code, m.metric_code)
      m.tenant_id AS tenant_id, m.service_code AS service_code, m.metric_code AS metric_code,
      m.sample_value AS sample_value, m.window_end AS window_end
    FROM platform_ops_metric m
    ORDER BY m.tenant_id, m.service_code, m.metric_code, m.window_end DESC, m.created_at DESC, m.id DESC
  ),
  per_tenant AS (
    SELECT DISTINCT ON (p.tenant_id, p.metric_code)
      p.tenant_id AS tenant_id, p.metric_code AS metric_code,
      p.sample_value AS sample_value, p.window_end AS window_end
    FROM per_service p
    ORDER BY p.tenant_id, p.metric_code, p.window_end DESC
  ),
  health AS (
    SELECT s.tenant_id AS tenant_id,
      max(s.sample_value) FILTER (WHERE s.metric_code='ERROR_RATE') AS error_rate,
      min(s.sample_value) FILTER (WHERE s.metric_code='ONLINE_STATUS') AS online_status,
      min(s.sample_value) FILTER (WHERE s.metric_code='AVAILABILITY') AS availability,
      max(s.sample_value) FILTER (WHERE s.metric_code='TASK_BACKLOG') AS task_backlog,
      max(s.sample_value) FILTER (WHERE s.metric_code='QUEUE_DEPTH') AS queue_depth,
      max(s.sample_value) FILTER (WHERE s.metric_code='STORAGE_UTILIZATION') AS storage_utilization,
      max(s.sample_value) FILTER (WHERE s.metric_code='REQUEST_LATENCY_MS') AS latency_ms,
      max(s.window_end) AS last_sample_at
    FROM per_service s GROUP BY s.tenant_id
  ),
  cost AS (
    SELECT p.tenant_id AS tenant_id, sum(p.sample_value * cm.weight) AS cost_estimate
    FROM per_tenant p
    JOIN platform_ops_cost_model cm ON cm.metric_code=p.metric_code AND cm.enabled
    GROUP BY p.tenant_id
  ),
  alerts AS (
    SELECT a.tenant_id AS tenant_id,
      count(*) FILTER (WHERE a.status<>'RESOLVED') AS open_alert_count,
      count(*) FILTER (WHERE a.status<>'RESOLVED' AND a.severity='CRITICAL') AS critical_alert_count
    FROM platform_ops_alert a GROUP BY a.tenant_id
  )
  SELECT t.id AS tenant_id, t.code AS tenant_code, t.name AS tenant_name,
    t.status AS tenant_status,
    CASE WHEN h.online_status IS NULL THEN NULL ELSE h.online_status >= 1 END AS online,
    h.error_rate AS error_rate, h.task_backlog AS task_backlog, h.queue_depth AS queue_depth,
    h.storage_utilization AS storage_utilization, h.availability AS availability,
    h.latency_ms AS latency_ms, c.cost_estimate AS cost_estimate,
    coalesce(al.open_alert_count,0) AS open_alert_count,
    coalesce(al.critical_alert_count,0) AS critical_alert_count,
    h.last_sample_at AS last_sample_at
  FROM tenant t
  LEFT JOIN health h ON h.tenant_id=t.id
  LEFT JOIN cost c ON c.tenant_id=t.id
  LEFT JOIN alerts al ON al.tenant_id=t.id
  ORDER BY t.code;
END $$;

COMMENT ON FUNCTION platform_ops_tenant_health(text) IS
  '17.7.2.2 · 平台健康看板（每个租户一行的技术健康摘要：在线状态 / 错误率 / 任务积压 / '
  '队列深度 / 容量占用 / 可用性 / 响应延迟 / 成本估算 / 告警数）。'
  '🔴 只含技术量与租户身份，不含任何租户业务数据。';

-- ===========================================================================
-- 十四、阈值写入（平台级专用路径）
-- ===========================================================================
-- 应用角色对 platform_ops_threshold 只有 SELECT，唯一写入口是这条函数。
CREATE FUNCTION platform_ops_apply_threshold(
  hash text, p_metric text, p_warn numeric, p_critical numeric,
  p_comparison text, p_enabled boolean)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=public,pg_temp AS $$
BEGIN
  PERFORM platform_ops_gate(hash,'identity.platform_ops.update');
  IF p_comparison NOT IN ('GT','LT') THEN
    RAISE EXCEPTION '比较方向只能是 GT 或 LT' USING ERRCODE='23514';
  END IF;
  IF (p_comparison='GT' AND p_warn >= p_critical) OR (p_comparison='LT' AND p_critical >= p_warn) THEN
    RAISE EXCEPTION '两级阈值与比较方向不自洽' USING ERRCODE='23514';
  END IF;
  INSERT INTO platform_ops_threshold
    (metric_code,warn_threshold,critical_threshold,comparison,enabled,updated_at)
  VALUES (p_metric,p_warn,p_critical,p_comparison,coalesce(p_enabled,true),now())
  ON CONFLICT (metric_code) DO UPDATE
    SET warn_threshold=EXCLUDED.warn_threshold, critical_threshold=EXCLUDED.critical_threshold,
        comparison=EXCLUDED.comparison, enabled=EXCLUDED.enabled, updated_at=now();
END $$;

-- ===========================================================================
-- 十五、函数授权
-- ===========================================================================
REVOKE EXECUTE ON FUNCTION platform_ops_gate(text,text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION platform_ops_register_role(text,text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION platform_ops_tenant_exists(uuid) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION platform_ops_metric_list(text,uuid,text,text,int,int) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION platform_ops_metric_total(text,uuid,text,text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION platform_ops_alert_list(text,text,text,uuid,int,int) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION platform_ops_alert_total(text,text,text,uuid) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION platform_ops_alert_tenant(text,uuid,text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION platform_ops_tenant_health(text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION platform_ops_apply_threshold(text,text,numeric,numeric,text,boolean) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION platform_ops_gate(text,text) TO identity_app;
GRANT EXECUTE ON FUNCTION platform_ops_register_role(text,text) TO identity_app;
GRANT EXECUTE ON FUNCTION platform_ops_tenant_exists(uuid) TO identity_app;
GRANT EXECUTE ON FUNCTION platform_ops_metric_list(text,uuid,text,text,int,int) TO identity_app;
GRANT EXECUTE ON FUNCTION platform_ops_metric_total(text,uuid,text,text) TO identity_app;
GRANT EXECUTE ON FUNCTION platform_ops_alert_list(text,text,text,uuid,int,int) TO identity_app;
GRANT EXECUTE ON FUNCTION platform_ops_alert_total(text,text,text,uuid) TO identity_app;
GRANT EXECUTE ON FUNCTION platform_ops_alert_tenant(text,uuid,text) TO identity_app;
GRANT EXECUTE ON FUNCTION platform_ops_tenant_health(text) TO identity_app;
GRANT EXECUTE ON FUNCTION platform_ops_apply_threshold(text,text,numeric,numeric,text,boolean) TO identity_app;

-- ===========================================================================
-- 十六、页面授权
-- ===========================================================================
-- 🔴 平台运维权限码**刻意不授给 OWNER**（与 17.7.1.1 同一先例，与 V53 相反）：
--    授给 OWNER 等于每个租户都能看跨租户运维看板，蓝图「17.7 平台运营不对租户开放」
--    当场失效。权限只能通过 platform_ops_register_role() 授给**被登记的平台运营角色**。
INSERT INTO page_resource(code,name) VALUES ('identity.platform_ops','平台运维视图')
ON CONFLICT (code) DO NOTHING;
INSERT INTO permission SELECT 'identity.platform_ops.'||action,'identity.platform_ops',action
FROM unnest(ARRAY['read','alert','update']::text[]) action
ON CONFLICT (code) DO NOTHING;

-- ===========================================================================
-- 十七、出厂阈值与成本口径
-- ===========================================================================
-- 阈值是出厂口径：告警要能真的响，但也不能动不动就响。
-- GT 类（越大越糟）：warning < critical。
-- LT 类（越小越糟）：critical < warning。
INSERT INTO platform_ops_threshold
  (metric_code,warn_threshold,critical_threshold,comparison,enabled) VALUES
  ('ERROR_RATE',0.010000,0.050000,'GT',true),
  ('TASK_BACKLOG',100.000000,1000.000000,'GT',true),
  ('QUEUE_DEPTH',500.000000,5000.000000,'GT',true),
  ('CPU_UTILIZATION',0.800000,0.950000,'GT',true),
  ('MEMORY_UTILIZATION',0.800000,0.950000,'GT',true),
  ('STORAGE_UTILIZATION',0.800000,0.950000,'GT',true),
  ('REQUEST_LATENCY_MS',1000.000000,3000.000000,'GT',true),
  ('AVAILABILITY',0.990000,0.950000,'LT',true),
  -- 在线状态：值为 1 才算在线；报 0 即离线（critical）。
  ('ONLINE_STATUS',1.000000,0.000000,'LT',true);

-- 成本估算权重（**单位是估算单位数量，不是金额**）。
-- 口径：容量类按占用率折算，调用量类按次折算。
INSERT INTO platform_ops_cost_model (metric_code,weight,enabled) VALUES
  ('CPU_UTILIZATION',10.000000,true),
  ('MEMORY_UTILIZATION',6.000000,true),
  ('STORAGE_UTILIZATION',20.000000,true),
  ('REQUEST_COUNT',0.001000,true),
  ('API_CALL_COUNT',0.010000,true),
  ('AI_CALL_COUNT',0.050000,true);
