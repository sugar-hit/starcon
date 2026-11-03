-- ===========================================================================
-- V415 · import_export_task —— 18.1.3.1「导入导出任务」
-- ===========================================================================
--
-- 蓝图 18-integration-and-automation.md 18.1.3.1 原文：
--
--   「**职责**：统一的数据导入导出通道：任务创建（选择数据域与范围）、异步执行、
--     进度跟踪、结果下载、失败行报告、模板下载。
--     **输入**：导出/导入请求；文件上传。
--     **输出**：任务记录 + 文件（带有效期）+ 错误报告。
--     **关系**：上游 = 全部业务域；下游 = 用户本地文件。
--     **大文件一律异步**，并在下载链接上设置有效期，避免存储无限增长。」
--
-- 落点：**platform 服务**（卷 18 的后端归属服务，端口 8089，库 starcon_platform，
-- 运行角色 platform_app）。本迁移是它的第四批表。
--
-- ---------------------------------------------------------------------------
-- 0. 「上游 = 全部业务域」在本迁移里的落点（本车道最重要的一条设计决定）
-- ---------------------------------------------------------------------------
--
-- 蓝图写「上游 = 全部业务域」，但根 AGENTS.md 明令：
--   「跨 backend/services/<name>/ 必须走授权 API / event，禁止跨服务 SQL / FK / 共享视图」
-- ⇒ **本迁移里没有一行跨库 SQL、没有跨服务外键、没有共享视图**。
--
-- 「全部业务域」按**三条通道**落地，而不是按「一张大表 join 七个服务」落地：
--
--   通道 A · platform 自己的表（真实事实，本库读）
--       应用与凭证 / 事件订阅 / 投递记录 / 调用埋点 —— 这些事实本来就住在 platform。
--
--   通道 B · 属主服务的**授权只读端口**（跨服务 HTTP，只转发调用方自己的 bearer）
--       商品与 SKU（catalog）/ 销售订单（trade）/ 库存头寸（warehouse）/
--       平台商品（channel）/ 会计凭证（finance）。
--       ⇒ 这些域的导出**不抄一份到本库**：抄一份就会漂移。
--
--   通道 C · 属主服务的**授权导入作业**（导入要写业务域，必须由属主写）
--       商品域（catalog 的文件型导入作业）。
--
-- 通道没接通的域 ⇒ 能力声明里**如实标 available=false** 并写明缺什么、归谁。
-- 本仓的纪律是「绝不为让测试绿而写成假的 true」（照抄 platform 的
-- UnavailableFinanceSoftwarePushPort / UnavailableAiTextGenerationPort）。
--
-- ---------------------------------------------------------------------------
-- 1. 表清单
-- ---------------------------------------------------------------------------
--
--   platform_io_task       —— 统一任务记录（导出与导入**同一张表**，同一套状态机）
--   platform_io_file       —— 文件本体（导出结果 / 失败行报告 / 上传源文件 / 模板）
--   platform_io_row_error  —— 失败行报告（**逐行**，不是一行一个字符串）
--   platform_io_template   —— 导入模板（列定义 + 示例行）
--
-- 四张表全部 tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation + GRANT platform_app。
--
-- ---------------------------------------------------------------------------
-- 2. 为什么导出与导入共用一张任务表
-- ---------------------------------------------------------------------------
--
-- 蓝图的输出是「**任务记录 + 文件（带有效期）+ 错误报告**」三件套，
-- 而「任务创建 → 异步执行 → 进度跟踪 → 结果下载 → 失败行报告」这条链
-- 对导出与导入**完全同构**（方向不同、文件角色不同而已）。
-- ⇒ 拆两张表会让「进度跟踪」「下载有效期」「取消」这些逻辑写两遍，
--   而两遍实现必然漂移（这正是 catalog 把 export_job / import_job 分开之后
--   各写了一套状态机的原因）。
--
--   direction 字段区分方向；**状态机是同一套**（下面的触发器只认一套迁移）。
--
-- ---------------------------------------------------------------------------
-- 3. 状态机（钉在触发器上，不是只写在应用层）
-- ---------------------------------------------------------------------------
--
--   PENDING ──> RUNNING ──> SUCCEEDED ──> EXPIRED
--     │            │            │
--     │            └──> FAILED  └──> CANCELLED（作废：导错范围时第一件事是让它不能再下载）
--     └──> CANCELLED
--
-- 🔴 **CHECK 约束不够**：PostgreSQL 里 CHECK 可以被 NOT VALID 绕过，
--    而「SUCCEEDED 的任务其实没有文件」「EXPIRED 的任务还能下载」
--    都是**并发下最容易出错**的地方 ⇒ 用触发器钉死。
--
-- 🔴 **同一状态重复更新不算非法迁移**（执行器每推进一批就会 update 一次 status='RUNNING'）。
--
-- ---------------------------------------------------------------------------
-- 4. 「下载链接带有效期」（蓝图加粗句的直接落地）
-- ---------------------------------------------------------------------------
--
--   platform_io_task.expires_at   —— 任务级：结果文件的下载截止时刻
--   platform_io_file.expires_at   —— 文件级：每个文件各自的截止时刻
--
-- 两者都**可空**：文件还没生成时没有截止时刻（不是「永不过期」）。
-- 服务层在任务成功那一刻写入，**不写死成 DB 常量**（是业务参数，不是数据库常量）。
--
-- ⚠️ 过期不是「把行删掉」：任务记录是**审计事实**（谁在什么时候导出了什么），
--    删了就查不到了。过期只让**下载**返回 410 Gone，并让文件本体从存储里清掉。
--
-- ---------------------------------------------------------------------------
-- 5. 时间与金额
-- ---------------------------------------------------------------------------
--
--   * 时间列一律 **timestamptz**；服务层用 **OffsetDateTime** 绑定并在 values 位置写
--     `?::timestamptz`（用 Timestamp.valueOf(LocalDateTime) 会静默偏 −28800s）。
--   * 文件字节数用 **numeric(19,6)**（AGENTS.md：数量一律 NUMERIC(19,6)）。
--   * 行数用 **int**（计数不是金额）。
--   * sha256 一律 varchar(64) + CHECK(length = 64)。
--
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. platform_io_task —— 统一导入导出任务
-- ---------------------------------------------------------------------------
--
-- 一行 = 一次「导什么 / 导多少 / 导到哪里」的**真实请求**。
--
-- 🔴 **scope 是冻结的快照**（不是引用）：任务创建那一刻把「导的是哪一段范围」
--   冻在 jsonb 里。事后再读「用户当下的筛选条件」是答非所问 ——
--   审计要回答的是「**当时**导的是什么」。
--
-- 🔴 **counts 是已推进的进度，不是最终结论**：任务还在跑时它们会变，
--   真正的结论是 status。
CREATE TABLE platform_io_task (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 人可读单号（序列是全局的，跨租户取号会互相看见对方的号段 ⇒ 单号里拼租户短码）。
  task_no varchar(48) NOT NULL,

  -- 方向：导出 / 导入。
  direction varchar(16) NOT NULL
    CHECK(direction IN ('EXPORT','IMPORT')),

  -- 数据域（程序内稳定标识，见 ImportExportDomains）。
  domain_code varchar(32) NOT NULL,
  -- 数据域的中文名（**创建那一刻**的快照：数据域改名不该改掉历史任务的标题）。
  domain_label varchar(64) NOT NULL,

  -- 文件格式。本轮只落 CSV —— 见文件头「为什么只有 CSV」。
  format varchar(8) NOT NULL DEFAULT 'CSV'
    CHECK(format IN ('CSV')),

  -- 状态（迁移规则见下面的触发器）。
  status varchar(16) NOT NULL DEFAULT 'PENDING'
    CHECK(status IN ('PENDING','RUNNING','SUCCEEDED','FAILED','CANCELLED','EXPIRED')),

  -- 🔴 **范围快照**：冻结的筛选条件（时间窗 / 关键字 / 状态 …）。
  scope jsonb NOT NULL DEFAULT '{}'::jsonb
    CHECK(jsonb_typeof(scope) = 'object'),

  -- 进度与计数（真数，不是估算）。
  total_rows int NOT NULL DEFAULT 0,
  processed_rows int NOT NULL DEFAULT 0,
  succeeded_rows int NOT NULL DEFAULT 0,
  failed_rows int NOT NULL DEFAULT 0,

  -- 结果文件（导出产物 / 导入成功后的回执）与失败行报告文件。
  result_file_id uuid,
  error_file_id uuid,

  -- 委托给属主服务的上游作业（导入委托时）：渠道 C 的留痕。
  -- 🔴 这是**上游的** id，不是本表的 id；本表的 id 才是唯一真相。
  upstream_service varchar(32) NOT NULL DEFAULT '',
  upstream_job_id uuid,
  upstream_job_ref varchar(160) NOT NULL DEFAULT '',

  -- 执行失败的原因（成功 / 作废 / 过期时保留 —— 它是「当时发生了什么」的审计事实）。
  error text NOT NULL DEFAULT '',

  -- 操作人：来自**已认证会话**，不在入参里。
  actor_id uuid NOT NULL,

  requested_at timestamptz NOT NULL DEFAULT now(),
  started_at timestamptz,
  finished_at timestamptz,
  -- 🔴 **下载有效期**：任务成功那一刻由服务层写入（业务参数，不是 DB 常量）。
  expires_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, task_no),

  CHECK(btrim(task_no) <> ''),
  CHECK(btrim(domain_code) <> ''),
  CHECK(total_rows >= 0 AND processed_rows >= 0 AND succeeded_rows >= 0 AND failed_rows >= 0),
  -- 🔴 **成功必须有结果文件**：SUCCEEDED 却没有文件 ⇒ 这条记录在骗人。
  --   （EXPIRED 可以没有 —— 文件已被清理。）
  CHECK(status <> 'SUCCEEDED' OR result_file_id IS NOT NULL),
  -- 🔴 **finished_at 与终态同步**：非终态不许有 finished_at，终态必须有。
  CHECK((status IN ('PENDING','RUNNING')) = (finished_at IS NULL))
);

CREATE INDEX platform_io_task_by_listing
  ON platform_io_task(tenant_id, direction, domain_code, status, created_at DESC);

CREATE INDEX platform_io_task_by_expiry
  ON platform_io_task(tenant_id, expires_at)
  WHERE expires_at IS NOT NULL;

-- 发号序列（人可读单号）。照抄 platform_finance_voucher_file_no_seq 的纪律：
-- 序列是全局的，跨租户取号会互相看见对方的号段 ⇒ 单号里拼租户短码。
CREATE SEQUENCE platform_io_task_no_seq;

-- 🔴 **同一租户同一数据域同时只允许一个导入任务在跑**。
--   理由与 catalog 的导入向导同构：两个同类导入同时跑会互相覆盖对方的行结论，
--   页面上不能同时挂着两轮互相矛盾的结论。
--   ⚠️ 只约束 **IMPORT**：两个并行的**导出**是合法的（导的是不同的范围）。
CREATE UNIQUE INDEX platform_io_task_one_active_import
  ON platform_io_task(tenant_id, domain_code)
  WHERE direction = 'IMPORT' AND status IN ('PENDING','RUNNING');

-- ---------------------------------------------------------------------------
-- 2. platform_io_file —— 文件本体
-- ---------------------------------------------------------------------------
--
-- 一行 = 一份**真的存在过**的文件（导出结果 / 失败行报告 / 上传的源文件 / 模板）。
--
-- 🔴 **文件内容不落库**（与 18.2.1.1 的凭证文件不同）：导入导出的可能是几十 MB 的
--   业务数据，落进 PostgreSQL 的 TOAST 会把库撑大、备份变慢、查询变慢。
--   ⇒ 内容落**文件存储**（storage_key），库里只留**元信息 + sha256 + 字节数**。
--   sha256 让「当时导出的那一份」可被指认（对方系统里的单据能对上这个哈希）。
--
-- 🔴 **storage_key 里带租户段**：一个租户算不出另一个租户的键
--   （实际隔离仍由 RLS 保证，这里是第二道）。
CREATE TABLE platform_io_file (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 所属任务（模板文件没有任务 ⇒ 可空）。
  task_id uuid,

  -- 文件角色：
  --   RESULT       导出结果
  --   ERROR_REPORT 失败行报告
  --   SOURCE       用户上传的源文件（导入）
  --   TEMPLATE     导入模板
  role varchar(16) NOT NULL
    CHECK(role IN ('RESULT','ERROR_REPORT','SOURCE','TEMPLATE')),

  filename varchar(255) NOT NULL,
  -- 存储键（相对存储根目录；实现方必须做**路径穿越防护**）。
  storage_key text NOT NULL,
  content_type varchar(120) NOT NULL DEFAULT 'text/csv; charset=UTF-8',
  -- 内容指纹（sha256，十六进制 64 位）。
  content_sha256 varchar(64) NOT NULL,
  -- 内容字节数（真实长度，不是估算）。
  byte_size numeric(19,6) NOT NULL,
  -- 文件里有多少行数据（不含表头）。
  row_count int NOT NULL DEFAULT 0,

  -- 🔴 **下载有效期**：文件级截止时刻（与任务级 expires_at 通常一致，
  --   但模板文件是长期有效的 —— 模板不是一次性的导出产物）。
  expires_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, storage_key),

  CHECK(btrim(filename) <> ''),
  CHECK(btrim(storage_key) <> ''),
  CHECK(length(content_sha256) = 64),
  CHECK(byte_size >= 0),
  CHECK(row_count >= 0),

  FOREIGN KEY(tenant_id, task_id)
    REFERENCES platform_io_task(tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX platform_io_file_by_task
  ON platform_io_file(tenant_id, task_id, role);

CREATE INDEX platform_io_file_by_expiry
  ON platform_io_file(tenant_id, expires_at)
  WHERE expires_at IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 3. platform_io_row_error —— 失败行报告（逐行）
-- ---------------------------------------------------------------------------
--
-- 一行 = 一处失败。**不是**「一个任务一个错误字符串」——
-- 一次导入失败 300 行里有 12 行有问题，运营要看到的是**哪 12 行、哪一列、为什么**，
-- 否则就得把文件重新导一遍才知道哪里错。
--
-- 🔴 **row_no 是文件里的物理行号（表头 = 1）**：运营在 Excel 里数到的就是它。
--
-- 🔴 **column_name 可为空**：行级失败（整行不合法）与列级失败（某列不合法）
--   是两回事 —— 列级失败要指出是哪一列，行级失败没有列。
CREATE TABLE platform_io_row_error (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  task_id uuid NOT NULL,

  -- 文件里的物理行号（表头 = 1）。
  row_no int NOT NULL,
  -- 列名（列级失败时给出；行级失败留空）。
  column_name varchar(64) NOT NULL DEFAULT '',
  -- 原始值（截断存，避免把整个文件塞进库里）。
  raw_value varchar(500) NOT NULL DEFAULT '',
  -- 失败原因码（程序内稳定标识，页面据此显示口径）。
  error_code varchar(48) NOT NULL,
  -- 失败原因的中文说明。
  message varchar(500) NOT NULL,
  -- 严重级别：ERROR 阻断该行落库；WARNING 只提示。
  severity varchar(16) NOT NULL DEFAULT 'ERROR'
    CHECK(severity IN ('ERROR','WARNING')),

  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 幂等键：同一任务同一行同一列同一原因码只记一次
  -- （重跑预校验时先清掉上一轮的行结论，不靠这个唯一键兜底）。
  UNIQUE(tenant_id, task_id, row_no, column_name, error_code),

  CHECK(row_no >= 1),
  CHECK(btrim(error_code) <> ''),

  FOREIGN KEY(tenant_id, task_id)
    REFERENCES platform_io_task(tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX platform_io_row_error_by_task
  ON platform_io_row_error(tenant_id, task_id, row_no);

-- ---------------------------------------------------------------------------
-- 4. platform_io_template —— 导入模板
-- ---------------------------------------------------------------------------
--
-- 一行 = 一个数据域的导入模板（列定义 + 示例行）。
--
-- 🔴 **模板是契约，不是建议**：运营按这个列头填表，通道按这个列头解析。
--   所以列定义**冻结**在库里（改了列头就是改了契约，要新建一个模板而不是原地改）。
--
-- ⚠️ **只给 platform 自己拥有导入格式的域建模板**：
--   商品域的导入格式归 catalog 的导入向导拥有（fieldKey / matchKey 是它的词汇），
--   在 platform 抄一份 ⇒ 两边必然漂移。
--   ⇒ 商品域的模板由通道**代理 catalog 的模板文件**下载，不在本表里复制。
CREATE TABLE platform_io_template (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 数据域。
  domain_code varchar(32) NOT NULL,
  -- 模板编码（同一数据域下唯一）。
  code varchar(64) NOT NULL,
  name varchar(120) NOT NULL,

  -- 列定义：[{"fieldKey":"name","header":"订阅名称","required":true,
  --            "dataType":"STRING","sampleValue":"...","help":"..."}]
  columns jsonb NOT NULL
    CHECK(jsonb_typeof(columns) = 'array'),
  -- 示例行（{"name":"...","target_url":"..."}）。
  sample_row jsonb NOT NULL DEFAULT '{}'::jsonb
    CHECK(jsonb_typeof(sample_row) = 'object'),

  -- 内置模板（随产品发布）vs 租户自建模板。
  builtin boolean NOT NULL DEFAULT true,
  enabled boolean NOT NULL DEFAULT true,
  remark varchar(500) NOT NULL DEFAULT '',

  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, domain_code, code),

  CHECK(btrim(code) <> ''),
  CHECK(btrim(name) <> '')
);

CREATE INDEX platform_io_template_by_domain
  ON platform_io_template(tenant_id, domain_code, enabled);

-- 内置模板：**每个租户各一份**。
--
-- 🔴 **为什么按租户各插一份而不是一张全局模板表**：
--   业务表一律 tenant_id NOT NULL（AGENTS.md 多租户纪律），
--   全局表要么没有 tenant_id（破例 ⇒ 得单独论证），要么租户一多就 N 份重复数据。
--   ⇒ 模板在**租户首次用到该数据域时按需落地**（服务层的 ensureBuiltinTemplates），
--     不在本迁移里给「不存在的租户」插数据。
--
-- ⚠️ 本迁移**不预置任何模板行**：模板的列定义在服务层（ImportExportTemplates），
--   与解析器共用一份代码 —— 两处各写一份列名，模板与解析必然对不上。

-- ---------------------------------------------------------------------------
-- 5. 数据库不变量（把蓝图的话变成数据层做不到的事）
-- ---------------------------------------------------------------------------

-- 5.1 状态机：只允许合法的迁移。
CREATE OR REPLACE FUNCTION guard_platform_io_task_status() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE allowed boolean;
BEGIN
  IF NEW.status = OLD.status THEN
    RETURN NEW;
  END IF;
  allowed :=
    CASE OLD.status
      WHEN 'PENDING'   THEN NEW.status IN ('RUNNING','CANCELLED','FAILED')
      WHEN 'RUNNING'   THEN NEW.status IN ('SUCCEEDED','FAILED','CANCELLED')
      WHEN 'SUCCEEDED' THEN NEW.status = 'EXPIRED'
      WHEN 'FAILED'    THEN false
      WHEN 'CANCELLED' THEN false
      WHEN 'EXPIRED'   THEN false
      ELSE false
    END;
  IF NOT allowed THEN
    RAISE EXCEPTION '导入导出任务状态不能从 % 迁移到 %（合法迁移：PENDING→RUNNING→SUCCEEDED→EXPIRED，任意非终态可 CANCELLED/FAILED）',
      OLD.status, NEW.status
      USING ERRCODE = 'restrict_violation';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER guard_platform_io_task_status
  BEFORE UPDATE ON platform_io_task
  FOR EACH ROW EXECUTE FUNCTION guard_platform_io_task_status();

-- 5.2 冻结字段：方向 / 数据域 / 范围快照 / 操作人**创建后不可改**。
--
-- 🔴 「当时导的是什么」是审计事实。事后再改 scope 等于让审计结论失去意义。
CREATE OR REPLACE FUNCTION guard_platform_io_task_frozen() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.direction IS DISTINCT FROM OLD.direction
     OR NEW.domain_code IS DISTINCT FROM OLD.domain_code
     OR NEW.scope IS DISTINCT FROM OLD.scope
     OR NEW.actor_id IS DISTINCT FROM OLD.actor_id
     OR NEW.format IS DISTINCT FROM OLD.format THEN
    RAISE EXCEPTION '导入导出任务的方向 / 数据域 / 格式 / 范围快照 / 操作人在创建后不可改（它们是「当时导的是什么」的审计事实）'
      USING ERRCODE = 'restrict_violation';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER guard_platform_io_task_frozen
  BEFORE UPDATE ON platform_io_task
  FOR EACH ROW EXECUTE FUNCTION guard_platform_io_task_frozen();

-- 5.3 成功必须有结束时刻与有效期。
CREATE OR REPLACE FUNCTION guard_platform_io_task_terminal() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.status IN ('SUCCEEDED','FAILED','CANCELLED') AND NEW.finished_at IS NULL THEN
    NEW.finished_at := now();
  END IF;
  IF NEW.status = 'SUCCEEDED' AND NEW.expires_at IS NULL THEN
    -- ⚠️ **兜底 7 天**：正常路径由服务层写入业务参数值；
    --   这里只是防止「成功却没有有效期」这种状态出现（那样文件就永远下不掉了）。
    NEW.expires_at := now() + interval '7 days';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER guard_platform_io_task_terminal
  BEFORE UPDATE ON platform_io_task
  FOR EACH ROW EXECUTE FUNCTION guard_platform_io_task_terminal();

-- ---------------------------------------------------------------------------
-- 6. 租户隔离（RLS）
-- ---------------------------------------------------------------------------
--
-- 四张表全部：tenant_id NOT NULL + ENABLE **与** FORCE RLS + policy isolation。
--
-- ⚠️ policy 只认 `app.current_tenant` 这个**事务局部** GUC，它由
--    TenantTransactions.set_config(..., true) 绑定 —— 事务结束即失效，
--    连接归还池中时**不留残留**（这正是 AGENTS.md 第三条隔离断言要验的）。
--
ALTER TABLE platform_io_task ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_io_task FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_io_task
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_io_file ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_io_file FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_io_file
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_io_row_error ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_io_row_error FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_io_row_error
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_io_template ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_io_template FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_io_template
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

-- ---------------------------------------------------------------------------
-- 7. 授权
-- ---------------------------------------------------------------------------
--
-- 运行角色是 platform_app：读自己的任务/文件/行错误/模板，写自己的任务/文件/行错误/模板。
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_io_task TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_io_file TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_io_row_error TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_io_template TO platform_app;

GRANT USAGE, SELECT ON SEQUENCE platform_io_task_no_seq TO platform_app;
