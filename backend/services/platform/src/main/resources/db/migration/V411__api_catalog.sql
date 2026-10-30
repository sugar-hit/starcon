-- ===========================================================================
-- V411 · platform_api_sandbox_run —— 18.1.1.2「接口目录」的沙箱测试留痕
-- ===========================================================================
--
-- 蓝图 18-integration-and-automation.md → 18.1 开放平台 → 18.1.1 API 管理 → 18.1.1.2 接口目录：
--
--   「职责：对外开放 API 的目录与文档：按业务域列出接口、参数、返回、限流说明、示例；
--     支持在线测试（沙箱环境）。
--     输入：接口定义（OpenAPI 生成物）；测试请求。
--     输出：接口文档 + 测试结果。
--     关系：上游 = 后端 OpenAPI spec（单一真相源）；下游 = 外部系统。
--     **接口契约由后端 spec 生成，文档不手写** —— 手写文档必然与实现漂移。」
--
-- ---------------------------------------------------------------------------
-- 0. 这个迁移刻意**只有一张表**（理由写在下面第 1 节，别急着加表）
-- ---------------------------------------------------------------------------
--
--   platform_api_sandbox_run —— 沙箱测试历史（谁、什么时候、对哪个接口发了什么请求、真实结果是什么）
--
-- ⚠️ **接口清单本身不落库**：它**必须**每次从各服务的 OpenAPI spec（`/v3/api-docs`）真解析出来。
--   把接口清单存成一张表，等于把 spec 抄一份进数据库 —— 那是「手写文档必然与实现漂移」
--   的另一种写法：spec 改了，库里那份不会跟着改，而运营看到的正是库里那份。
--   ⇒ 目录 = spec 的**投影**（不缓存、不入库、不手写）；只有「测试结果」是本模块自己产生的事实，
--     所以只有它落库。
--
-- ---------------------------------------------------------------------------
-- 1. 为什么测试结果要落库
-- ---------------------------------------------------------------------------
--
-- 蓝图把「测试结果」列为本模块的**输出**。落库有三个具体好处（不是「为了落库而落库」）：
--
--   * **可复现**：接入方报「你那个接口昨天还能调」时，能调出**那一版**的真实状态码与响应摘要。
--   * **可追责**：沙箱测试是**用真实租户会话**发的，出了问题要能查到是谁发的。
--   * **可核对**：响应摘要是**真响应**，落库后运营能拿它跟对端收到的比。
--
-- ⚠️ **只落「读」接口的测试结果**（沙箱拒发写请求，详见 ApiSandboxExecutor）：
--   写请求在沙箱里被 409 挡掉，但**连「被拒绝」这件事本身也要留痕** ——
--   否则「我明明点了测试，怎么什么都没发生」没有答案。
--
-- ---------------------------------------------------------------------------
-- 2. 金额与数量
-- ---------------------------------------------------------------------------
--
--   * 本表**不落任何金额**：对外 API 的计费属 18.1 / 18.2 的商业条款，不在本模块。
--     测试用 information_schema 断言本表**无金额语义列**。
--   * 数量只有两列，都是**计数**不是业务数量：
--       response_bytes —— 上游真实返回的字节数（bigint，计数）
--       duration_ms   —— 本次调用真实耗时（integer，毫秒，计数）
--     两者都不是金额，也不是「件 / 公斤」这类业务数量 ⇒ 用 bigint / integer 计数，
--     **不套 numeric(19,6)**（那会让「字节数」看起来像一个可带小数的业务数量）。
--
-- ---------------------------------------------------------------------------
-- 3. 租户隔离（RLS）
-- ---------------------------------------------------------------------------
--
-- tenant_id NOT NULL + ENABLE **与** FORCE RLS isolation + GRANT platform_app。
-- FORCE 是必须的：表 owner 在 FORCE 下也受策略约束，否则迁移脚本或后台任务用 owner
-- 身份一连就绕过了隔离（与 identity V330 / finance V361 / platform V377 同纪律）。
--
-- ⚠️ policy 只认 `app.current_tenant` 这个**事务局部** GUC，它由
--    TenantTransactions.set_config(..., true) 绑定 —— 事务结束即失效，
--    连接归还池中时**不留残留**（这正是 AGENTS.md 第三条隔离断言要验的）。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. platform_api_sandbox_run —— 沙箱测试历史
-- ---------------------------------------------------------------------------
--
-- 🔴 **一条行 = 一次真实的沙箱测试尝试**（成功、拒发、上游不可达都留痕）。
--
-- 🔴 **不落任何请求/响应里的机密**：本表不存 Authorization、不存 Cookie。
--   request_body 存的是**调用方自己填的测试载荷**，response_summary 存的是
--   **上游真实响应体的前若干字符** —— 两者都是调试现场要看的原话，
--   但都**有长度上限**（见下面的 CHECK），不是整包搬运。
CREATE TABLE platform_api_sandbox_run (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 目标服务名（取自平台的服务登记表，不是客户端随便填的字符串）。
  target_service varchar(64) NOT NULL,

  -- HTTP 方法（沙箱只放行读方法；写方法会被拒并以 REFUSED_WRITE 留痕）。
  http_method varchar(8) NOT NULL,

  -- 目标路径（**必须**是 spec 里声明过的形状，见 ApiSandboxExecutor 的路径白名单校验）。
  target_path varchar(512) NOT NULL,

  -- 查询串（原样存调用方填的那一份，键值以 & 分隔）。
  query_string varchar(1024) NOT NULL DEFAULT '',

  -- 请求体（调用方自己填的测试载荷；上限 8000 字符）。
  request_body text NOT NULL DEFAULT '',

  -- 上游真实返回的 HTTP 状态码；**没发出去 / 上游不可达时为 NULL**
  -- （NULL 与 0 不同：0 表示「我们拒发了」，NULL 表示「没有状态码这回事」）。
  status_code integer,

  -- 响应体摘要（上游真实响应体的前若干字符）。
  response_summary text NOT NULL DEFAULT '',

  -- 上游真实返回的字节数与本次调用真实耗时（毫秒）。
  response_bytes bigint NOT NULL DEFAULT 0,
  duration_ms integer NOT NULL DEFAULT 0,

  -- 这次尝试的结论：
  --   COMPLETED          —— 上游真的回了（status_code 有值）
  --   REFUSED_WRITE      —— 沙箱拒发写请求（保护真实业务表）
  --   INVALID_TARGET     —— 目标路径 / 查询参数不在 spec 里（400，什么都没发出去）
  --   UPSTREAM_UNREACHABLE —— 上游不可达（503，什么都没发出去）
  outcome varchar(32) NOT NULL
    CHECK(outcome IN ('COMPLETED','REFUSED_WRITE','INVALID_TARGET','UPSTREAM_UNREACHABLE')),

  -- 结论的人话说明（现场看得懂的那一句）。
  message varchar(500) NOT NULL DEFAULT '',

  -- 谁发起的：来自已认证会话的 user_id，**不接受请求参数传入**。
  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),

  CHECK(btrim(target_service) <> '' AND length(target_service) <= 64),
  CHECK(btrim(target_path) <> '' AND length(target_path) <= 512),
  CHECK(btrim(http_method) <> ''),
  -- 写方法只能以 REFUSED_WRITE 落痕；COMPLETED 只能配一个真实状态码。
  CHECK((outcome = 'COMPLETED') = (status_code IS NOT NULL)),
  CHECK(status_code IS NULL OR (status_code >= 100 AND status_code <= 599)),
  CHECK(length(request_body) <= 8000),
  CHECK(length(response_summary) <= 8000),
  CHECK(response_bytes >= 0),
  CHECK(duration_ms >= 0)
);

-- 最近优先的测试历史（页面默认就按这个顺序翻）。
CREATE INDEX platform_api_sandbox_run_by_time
  ON platform_api_sandbox_run(tenant_id, created_at DESC);

-- 按目标服务过滤（同一个接口连着测了 20 次，要能一眼挑出来）。
CREATE INDEX platform_api_sandbox_run_by_service
  ON platform_api_sandbox_run(tenant_id, target_service, created_at DESC);

-- ---------------------------------------------------------------------------
-- 2. 租户隔离（RLS）
-- ---------------------------------------------------------------------------
ALTER TABLE platform_api_sandbox_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_api_sandbox_run FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_api_sandbox_run
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

-- ---------------------------------------------------------------------------
-- 3. 授权
-- ---------------------------------------------------------------------------
-- 运行角色是 platform_app：它要能读自己的测试历史、写新的测试留痕。
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_api_sandbox_run TO platform_app;
