-- ===========================================================================
-- V417 · data_migration —— 18.1.3.2「数据迁移」
-- ===========================================================================
--
-- 蓝图 18-integration-and-automation.md 18.1.3.2 原文：
--
--   「**职责**：支持从其他 ERP 迁移历史数据：映射配置（外部字段 → 内部模型）、
--     试运行预览、正式迁移、失败回滚与核对报告。
--     **输入**：源数据文件；字段映射配置。
--     **输出**：迁移任务 + 迁移核对报告（成功/失败/跳过数量与原因）。
--     **关系**：上游 = 18.1.3.1；下游 = 各业务域。
--     **历史数据迁移必须支持试运行**，直接正式跑会产生大量脏数据难以回退。」
--
-- 落点：**platform 服务**（卷 18 的后端归属服务，端口 8089，库 starcon_platform，
-- 运行角色 platform_app）。本迁移是它的第五批表。
--
-- ---------------------------------------------------------------------------
-- 0. 本模块与 18.1.3.1 的关系（**复用，不另起体系**）
-- ---------------------------------------------------------------------------
--
-- 蓝图写「上游 = 18.1.3.1」。18.1.3.1 已经交付了**统一的数据导入导出通道**：
-- 文件存储抽象（ImportExportStorage）、CSV 读写（ImportExportCsv）、
-- 进程内执行器（ImportExportExecutor）、AccessService 守卫。
-- ⇒ 本模块**复用**这四样，只新增「迁移」自己的语义：
--   映射配置 / 试运行 / 正式迁移 / 回滚 / 核对报告。
-- ⇒ **本迁移不复制 18.1.3.1 的四张表**（platform_io_task / _file / _row_error / _template）：
--   迁移任务的状态机、文件表形状与它们**不同构**（迁移有「回滚」这一维，
--   逐行结论要记「上游 id」才能回滚），硬塞进同一张表会把两套状态机揉在一起。
--   本模块的「上游是 18.1.3.1」体现在**代码层复用**（存储 / CSV / 执行器 / 守卫），
--   不体现在「共用一张表」。
--
-- ---------------------------------------------------------------------------
-- 1. 表清单
-- ---------------------------------------------------------------------------
--
--   platform_migration_mapping —— 映射配置（外部字段 → 内部模型）
--   platform_migration_run     —— 迁移任务（试运行 / 正式迁移 / 回滚）
--   platform_migration_file    —— 源文件与核对报告
--   platform_migration_row     —— 逐行结论（成功 / 失败 / 跳过 + 原因 + 上游 id）
--
-- 四张表全部 tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation + GRANT platform_app。
--
-- ---------------------------------------------------------------------------
-- 2. 目标业务域：catalog（商品 SPU / SKU）
-- ---------------------------------------------------------------------------
--
-- 蓝图写「下游 = 各业务域」。本轮**接通的域是 catalog 的商品域**，理由是
-- **它有真实的写入 / 删除对**（只有这样「失败回滚」才可能是真的）：
--
--   POST   /catalog/products                 → 建 SPU（catalog 新建强制 DRAFT）
--   DELETE /catalog/products/{id}?version=    → 删 SPU（要求 DRAFT 且名下无 SKU）
--   POST   /catalog/products/{id}/skus       → 建 SKU（version 是 **SPU 的**版本号）
--   DELETE /catalog/products/{id}/skus/{sid}?version= → 删 SKU（version 也是 **SPU 的**版本号）
--
-- ⚠️ **没有 create/delete 对的域不做迁移**：回滚只能删掉「自己建出来的东西」，
--   删不掉就等于「回滚」是假的（页面上写着「已回滚」，数据其实还在）。
--   其余域的迁移能力在**能力声明里如实标 available=false** 并写明缺什么、归谁。
--
-- ⚠️ **本迁移里没有一行跨库 SQL、没有跨服务外键、没有共享视图**：
--   迁移要写商品主数据 ⇒ 必须由 catalog 自己写（经它的授权 API）。
--
-- ---------------------------------------------------------------------------
-- 3. 状态机（钉在触发器上，不是只写在应用层）
-- ---------------------------------------------------------------------------
--
--   status（PENDING → RUNNING → SUCCEEDED / FAILED / CANCELLED）
--
--   rollback_status（NONE → RUNNING → SUCCEEDED / FAILED）
--
-- 🔴 **CHECK 约束不够**：PostgreSQL 里 CHECK 可以被 NOT VALID 绕过，
--    而「回滚了一个从没跑完的任务」「回滚了两次」都是并发下最容易出错的地方
--    ⇒ 用触发器钉死。
--
-- 🔴 **回滚与迁移是两条独立的状态机**：一次迁移「成功」之后仍可以被回滚，
--    那时 status 仍是 SUCCEEDED、rollback_status 变成 SUCCEEDED ——
--    把两者揉进一个字段就会丢掉「迁移本身成功过」这个事实。
--
-- ---------------------------------------------------------------------------
-- 4. 时间与金额
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
-- 1. platform_migration_mapping —— 映射配置（外部字段 → 内部模型）
-- ---------------------------------------------------------------------------
--
-- 一行 = 「外部系统的哪个列，对应我们内部模型的哪个字段」。
--
-- 🔴 **fields 是冻结的契约**（不是引用）：外部系统的表头经常变（「商品编码」改成「款号」）。
--   映射是**契约** —— 运营按它填表、服务按它解析。
--   改了就是改了契约 ⇒ 迁移任务会把**创建那一刻的映射快照**冻进 mapping_snapshot，
--   事后再读「用户当下的映射」是答非所问 —— 审计要回答的是「**当时**按什么映射迁的」。
--
-- ⚠️ **只给 platform 自己拥有导入格式的域建映射**：商品域的字段名归 catalog 所有，
--   在 platform 抄一份字段清单 ⇒ 两边必然漂移（catalog 加了字段这边还按老的迁）。
--   ⇒ 目标模型与字段由服务层的一份代码（DataMigrationTargets）定义，
--     迁移任务与映射校验共用它，不在库里再存一份字段清单。
CREATE TABLE platform_migration_mapping (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 映射编码（同一租户下唯一）：如 JST_SPU_V1。
  code varchar(64) NOT NULL,
  name varchar(120) NOT NULL,
  -- 外部系统名（聚水潭 / 旺店通 / 自研 ERP …）：让人知道这份映射是给谁用的。
  source_system varchar(64) NOT NULL DEFAULT '',

  -- 目标内部模型：SPU / SKU（程序内稳定标识，见 DataMigrationTargets）。
  target_model varchar(16) NOT NULL
    CHECK(target_model IN ('SPU','SKU')),

  -- 字段映射：[{"source":"商品编码","target":"code","required":true,
  --            "transform":"TRIM","defaultValue":""}]
  --   source       外部列名（文件表头，逐字匹配）
  --   target       内部字段键（必须是目标模型的合法字段）
  --   required     源文件缺这一列 / 值为空 ⇒ 该行失败
  --   transform    NONE / TRIM / UPPER / LOWER
  --   defaultValue 源值为空时的兜底值（只对非 required 字段有意义）
  fields jsonb NOT NULL
    CHECK(jsonb_typeof(fields) = 'array'),

  enabled boolean NOT NULL DEFAULT true,
  remark varchar(500) NOT NULL DEFAULT '',

  -- 操作人：来自**已认证会话**，不在入参里。
  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, code),

  CHECK(btrim(code) <> ''),
  CHECK(btrim(name) <> '')
);

CREATE INDEX platform_migration_mapping_by_listing
  ON platform_migration_mapping(tenant_id, target_model, enabled, created_at DESC);

-- ---------------------------------------------------------------------------
-- 2. platform_migration_run —— 迁移任务
-- ---------------------------------------------------------------------------
--
-- 一行 = 一次「迁什么、迁多少、迁到哪」的**真实请求**。
--
-- 🔴 **mode 只有两档：DRY_RUN（试运行）与 APPLY（正式迁移）**。
--   蓝图加粗句「历史数据迁移必须支持试运行」在这里落地：
--   DRY_RUN **不调用任何写端点**（只读目标库做去重判断），APPLY 才真正写。
--
-- 🔴 **mapping_snapshot 是冻结的快照**（不是引用）：与 18.1.3.1 的 scope 快照同一纪律 ——
--   审计要回答的是「**当时**按什么映射迁的」。
--
-- 🔴 **回滚是**第二次**动作，不是状态机上的一个终态**：
--   rollback_status 单独一列，迁移本身成不成功由 status 说了算。
CREATE TABLE platform_migration_run (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 人可读单号（序列是全局的，跨租户取号会互相看见对方的号段 ⇒ 单号里拼租户短码）。
  run_no varchar(48) NOT NULL,

  -- 模式：DRY_RUN 试运行 / APPLY 正式迁移。
  mode varchar(16) NOT NULL
    CHECK(mode IN ('DRY_RUN','APPLY')),

  -- 目标内部模型（SPU / SKU）。从映射配置带过来，**创建后不可改**。
  target_model varchar(16) NOT NULL
    CHECK(target_model IN ('SPU','SKU')),

  -- 用的哪份映射（**引用**）+ **冻结的映射快照**（审计事实）。
  mapping_id uuid,
  mapping_code varchar(64) NOT NULL DEFAULT '',
  mapping_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb
    CHECK(jsonb_typeof(mapping_snapshot) = 'object'),

  -- 源文件指纹（「迁的是哪一份文件」的凭据）。
  source_filename varchar(255) NOT NULL DEFAULT '',
  source_sha256 varchar(64) NOT NULL DEFAULT '',
  CHECK(source_sha256 = '' OR length(source_sha256) = 64),

  -- 状态（迁移本身）。
  status varchar(16) NOT NULL DEFAULT 'PENDING'
    CHECK(status IN ('PENDING','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
  -- 回滚状态（**独立的状态机**，见文件头第 3 节）。
  rollback_status varchar(16) NOT NULL DEFAULT 'NONE'
    CHECK(rollback_status IN ('NONE','RUNNING','SUCCEEDED','FAILED')),

  -- 计数（真数，不是估算）：核对报告的三个口径。
  total_rows int NOT NULL DEFAULT 0,
  processed_rows int NOT NULL DEFAULT 0,
  succeeded_rows int NOT NULL DEFAULT 0,
  failed_rows int NOT NULL DEFAULT 0,
  skipped_rows int NOT NULL DEFAULT 0,
  -- 回滚真的删掉的记录数（**不是**「提交了多少次删除请求」）。
  rolled_back_rows int NOT NULL DEFAULT 0,

  -- 核对报告文件（platform_migration_file.role='REPORT' 的 id）。
  report_file_id uuid,
  -- 回滚报告文件（**另存一份**，不覆盖核对报告 ——
  -- 「迁成了什么」与「回滚删掉了什么」是两份不同的报告，揉成一份就丢了一边）。
  rollback_report_file_id uuid,

  -- 执行失败的原因（成功 / 作废时也保留 —— 它是「当时发生了什么」的审计事实）。
  error text NOT NULL DEFAULT '',

  -- 操作人：来自**已认证会话**，不在入参里。
  actor_id uuid NOT NULL,

  requested_at timestamptz NOT NULL DEFAULT now(),
  started_at timestamptz,
  finished_at timestamptz,
  rolled_back_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, run_no),

  CHECK(btrim(run_no) <> ''),
  CHECK(total_rows >= 0 AND processed_rows >= 0 AND succeeded_rows >= 0
        AND failed_rows >= 0 AND skipped_rows >= 0 AND rolled_back_rows >= 0),
  -- 🔴 **三个口径加起来必须等于处理过的行数**：
  --   否则核对报告里的「成功 / 失败 / 跳过」加不到总数上，
  --   那份报告在账上就对不平 —— 而对账报告的全部意义就是「加起来对得上」。
  CHECK(processed_rows = succeeded_rows + failed_rows + skipped_rows)
);

CREATE INDEX platform_migration_run_by_listing
  ON platform_migration_run(tenant_id, mode, target_model, status, created_at DESC);

CREATE INDEX platform_migration_run_by_rollback
  ON platform_migration_run(tenant_id, rollback_status, created_at DESC);

-- 发号序列（人可读单号）。照抄 platform_io_task_no_seq 的纪律：
-- 序列是全局的，跨租户取号会互相看见对方的号段 ⇒ 单号里拼租户短码。
CREATE SEQUENCE platform_migration_run_no_seq;

-- 🔴 **同一租户同时只允许一个迁移任务在跑**。
--   理由与 18.1.3.1 的「一个数据域一个导入任务」同构：两个迁移同时跑会互相覆盖
--   对方的行结论，页面上不能同时挂着两轮互相矛盾的结论。
--   ⚠️ 只约束**非终态**：回滚是迁移任务的后续动作，不另开一轮。
CREATE UNIQUE INDEX platform_migration_run_one_active
  ON platform_migration_run(tenant_id)
  WHERE status IN ('PENDING','RUNNING');

-- ---------------------------------------------------------------------------
-- 3. platform_migration_file —— 源文件与核对报告
-- ---------------------------------------------------------------------------
--
-- 一行 = 一份**真的存在过**的文件（源文件 / 核对报告）。
--
-- 🔴 **文件内容不落库**（与 18.1.3.1 的 platform_io_file 同一纪律）：
--   历史数据文件可能是几十 MB，落进 PostgreSQL 的 TOAST 会把库撑大、
--   备份变慢、查询变慢。库里只留**元信息 + sha256 + 字节数**。
--
-- 🔴 **storage_key 里带租户段**：一个租户算不出另一个租户的键
--   （实际隔离仍由 RLS 保证，这里是第二道）。
CREATE TABLE platform_migration_file (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 所属迁移任务。
  run_id uuid NOT NULL,

  -- 文件角色：SOURCE 外部系统导出的源文件 / REPORT 核对报告。
  role varchar(16) NOT NULL
    CHECK(role IN ('SOURCE','REPORT')),

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

  -- 🔴 **下载有效期**：文件级截止时刻。核对报告是**一次性产物**，
  --   到期即删（迁移任务记录本身保留 —— 它是审计事实）。
  expires_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  UNIQUE(tenant_id, storage_key),

  CHECK(btrim(filename) <> ''),
  CHECK(btrim(storage_key) <> ''),
  CHECK(length(content_sha256) = 64),
  CHECK(byte_size >= 0),
  CHECK(row_count >= 0),

  FOREIGN KEY(tenant_id, run_id)
    REFERENCES platform_migration_run(tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX platform_migration_file_by_run
  ON platform_migration_file(tenant_id, run_id, role);

CREATE INDEX platform_migration_file_by_expiry
  ON platform_migration_file(tenant_id, expires_at)
  WHERE expires_at IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 4. platform_migration_row —— 逐行结论（**回滚的依据**）
-- ---------------------------------------------------------------------------
--
-- 一行 = 源文件里的**一行数据**的迁移结论。
--
-- 🔴 **不是「一个任务一个错误字符串」**：一次迁移 3000 行里有 12 行有问题，
--   运营要看到的是**哪 12 行、哪一列、为什么**。
--
-- 🔴 **upstream_id 是「我们在目标库里建出来的那条记录」的 id** ——
--   **回滚就是靠它**（去目标库把它删掉）。没有它，回滚只能是假的。
--
-- 🔴 **upstream_parent_id 是 SKU 的父 SPU id**：catalog 删 SPU 前要求「名下无 SKU」，
--   ⇒ 回滚 SKU 行与 SPU 行时要知道它挂在谁下面。
--
-- 🔴 **rollback_* 两列与迁移结论**分开放**：回滚不改写「这一行当时迁成了没有」
--   这个事实 —— 迁移结论与回滚结论是**两件事**，揉在一起就丢了一边。
CREATE TABLE platform_migration_row (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  run_id uuid NOT NULL,

  -- 源文件里的物理行号（表头 = 1）：运营在 Excel 里数到的就是它。
  row_no int NOT NULL,
  -- 结论：SUCCEEDED 迁成了 / FAILED 迁不成 / SKIPPED 有意不迁。
  outcome varchar(16) NOT NULL
    CHECK(outcome IN ('SUCCEEDED','FAILED','SKIPPED')),

  -- 这一行迁的是 SPU 还是 SKU（SKU 行要指明它的父 SPU）。
  target_kind varchar(16) NOT NULL DEFAULT ''
    CHECK(target_kind IN ('','SPU','SKU')),
  -- 目标编码（catalog 的 code）：迁移过去之后这就是它在目标库里的身份。
  target_code varchar(64) NOT NULL DEFAULT '',

  -- 🔴 **上游 id**：我们在目标库里建出来的那条记录（回滚的依据）。
  --   试运行（DRY_RUN）**永远为空** —— 试运行不写业务数据。
  upstream_id uuid,
  -- SKU 的父 SPU id。
  upstream_parent_id uuid,

  -- 原因码（程序内稳定标识，页面据此显示口径）+ 人话说明。
  reason_code varchar(48) NOT NULL DEFAULT '',
  message varchar(500) NOT NULL DEFAULT '',
  -- 原始值（截断存，避免把整个文件塞进库里）。
  raw_value varchar(500) NOT NULL DEFAULT '',

  -- 回滚结论（与迁移结论**分开**）：NONE 没回滚 / DELETED 已删掉 / FAILED 没删掉。
  rollback_status varchar(16) NOT NULL DEFAULT 'NONE'
    CHECK(rollback_status IN ('NONE','DELETED','FAILED')),
  rollback_message varchar(500) NOT NULL DEFAULT '',

  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id, id),
  -- 一行源数据一行结论（重跑迁移是**新的一次任务**，不是覆盖上一轮）。
  UNIQUE(tenant_id, run_id, row_no),

  CHECK(row_no >= 1),
  CHECK(btrim(target_code) <> '' OR outcome = 'FAILED'),
  -- 🔴 **「迁成了」必须真的有上游 id**：没有 id 就没法回滚，
  --   那这条「成功」是骗人的（试运行的预判成功不算迁成）。
  --   ⚠️ DRY_RUN 的成功行没有上游 id —— 它们是「预判」，
  --   所以这条约束只对 APPLY 生效（用「有没有 mode」区分不了，
  --   改由触发器 guard_platform_migration_row_success 按 run.mode 裁决）。
  FOREIGN KEY(tenant_id, run_id)
    REFERENCES platform_migration_run(tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX platform_migration_row_by_run
  ON platform_migration_row(tenant_id, run_id, row_no);

CREATE INDEX platform_migration_row_by_outcome
  ON platform_migration_row(tenant_id, run_id, outcome);

-- 🔴 **回滚要按上游 id 找记录**：这一条索引让「本次迁移建出来的所有记录」
--   一次查得出来，不用扫全表。
CREATE INDEX platform_migration_row_by_upstream
  ON platform_migration_row(tenant_id, run_id, upstream_id)
  WHERE upstream_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 5. 数据库不变量（把蓝图的话变成数据层做不到的事）
-- ---------------------------------------------------------------------------

-- 5.1 迁移状态机：只允许合法的迁移。
CREATE OR REPLACE FUNCTION guard_platform_migration_run_status() RETURNS trigger
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
      WHEN 'SUCCEEDED' THEN false
      WHEN 'FAILED'    THEN false
      WHEN 'CANCELLED' THEN false
      ELSE false
    END;
  IF NOT allowed THEN
    RAISE EXCEPTION '迁移任务状态不能从 % 迁移到 %（合法迁移：PENDING→RUNNING→SUCCEEDED/FAILED，任意非终态可 CANCELLED）',
      OLD.status, NEW.status
      USING ERRCODE = 'restrict_violation';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER guard_platform_migration_run_status
  BEFORE UPDATE ON platform_migration_run
  FOR EACH ROW EXECUTE FUNCTION guard_platform_migration_run_status();

-- 5.2 回滚状态机：**独立于迁移状态机**。
--
-- 🔴 **回滚的前提**：迁移必须**已经跑完**（SUCCEEDED 或 FAILED）且还没回滚过。
--   「回滚一个还在跑的任务」会让回滚与迁移同时改同一批记录，
--   结果是「删了一半、又建了一半」—— 比不回滚更糟。
--   「回滚两次」则会把第二次当成新的删除（虽然幂等，但报告里的数字会翻倍）。
--
-- ⚠️ **DRY_RUN 不许回滚**：试运行没写任何业务数据，回滚它是无中生有 ——
--   而报告里出现「已回滚 N 条」会让人以为真删掉了东西。
--
-- ⚠️ **前提只在 NONE → RUNNING 这一步校验**（RUNNING → SUCCEEDED/FAILED 是收尾，
--   那时再查一遍「迁移跑完了没」是多余的，而且会把「跑完了但状态被并发改掉」的
--   情况误判成非法迁移）。
CREATE OR REPLACE FUNCTION guard_platform_migration_run_rollback() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE allowed boolean;
BEGIN
  IF NEW.rollback_status = OLD.rollback_status THEN
    RETURN NEW;
  END IF;
  IF NEW.rollback_status = 'NONE' THEN
    RAISE EXCEPTION '回滚状态不能回到 NONE（回滚是一次性的动作）'
      USING ERRCODE = 'restrict_violation';
  END IF;
  allowed :=
    CASE OLD.rollback_status
      WHEN 'NONE'   THEN NEW.rollback_status = 'RUNNING'
      WHEN 'RUNNING' THEN NEW.rollback_status IN ('SUCCEEDED','FAILED')
      ELSE false
    END;
  IF NOT allowed THEN
    RAISE EXCEPTION '迁移任务的回滚状态不能从 % 迁移到 %（合法迁移：NONE→RUNNING→SUCCEEDED/FAILED）',
      OLD.rollback_status, NEW.rollback_status
      USING ERRCODE = 'restrict_violation';
  END IF;
  IF OLD.rollback_status = 'NONE' THEN
    IF OLD.status NOT IN ('SUCCEEDED','FAILED') THEN
      RAISE EXCEPTION '迁移还没跑完（当前 %），不能回滚', OLD.status
        USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.mode = 'DRY_RUN' THEN
      RAISE EXCEPTION '试运行没有写任何业务数据，没有可回滚的东西'
        USING ERRCODE = 'restrict_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER guard_platform_migration_run_rollback
  BEFORE UPDATE ON platform_migration_run
  FOR EACH ROW EXECUTE FUNCTION guard_platform_migration_run_rollback();

-- 5.3 冻结字段：模式 / 目标模型 / 映射快照 / 源文件指纹 / 操作人**创建后不可改**。
--
-- 🔴 「当时按什么迁的」是审计事实。事后再改就是让审计结论失去意义。
CREATE OR REPLACE FUNCTION guard_platform_migration_run_frozen() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.mode IS DISTINCT FROM OLD.mode
     OR NEW.target_model IS DISTINCT FROM OLD.target_model
     OR NEW.mapping_snapshot IS DISTINCT FROM OLD.mapping_snapshot
     OR NEW.source_sha256 IS DISTINCT FROM OLD.source_sha256
     OR NEW.actor_id IS DISTINCT FROM OLD.actor_id THEN
    RAISE EXCEPTION '迁移任务的模式 / 目标模型 / 映射快照 / 源文件指纹 / 操作人在创建后不可改（它们是「当时按什么迁的」的审计事实）'
      USING ERRCODE = 'restrict_violation';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER guard_platform_migration_run_frozen
  BEFORE UPDATE ON platform_migration_run
  FOR EACH ROW EXECUTE FUNCTION guard_platform_migration_run_frozen();

-- 5.4 终态补齐：到了终态就必须有结束时刻。
CREATE OR REPLACE FUNCTION guard_platform_migration_run_terminal() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.status IN ('SUCCEEDED','FAILED','CANCELLED') AND NEW.finished_at IS NULL THEN
    NEW.finished_at := now();
  END IF;
  IF NEW.rollback_status = 'SUCCEEDED' AND NEW.rolled_back_at IS NULL THEN
    NEW.rolled_back_at := now();
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER guard_platform_migration_run_terminal
  BEFORE UPDATE ON platform_migration_run
  FOR EACH ROW EXECUTE FUNCTION guard_platform_migration_run_terminal();

-- 5.5 逐行结论与运行模式的一致性（**试运行不许有上游 id**）。
--
-- 🔴 这是蓝图那句加粗的**数据层兜底**：
--   「历史数据迁移必须支持试运行，直接正式跑会产生大量脏数据难以回退」——
--   反过来说，试运行**绝不能**留下「已经写进业务库」的痕迹。
--   一条 DRY_RUN 的行带着 upstream_id ⇒ 目标库里真的多了一条记录 ⇒ 试运行不是试运行。
CREATE OR REPLACE FUNCTION guard_platform_migration_row_success() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE run_mode varchar(16);
BEGIN
  SELECT mode INTO run_mode FROM platform_migration_run WHERE id = NEW.run_id;
  IF run_mode = 'DRY_RUN' AND NEW.upstream_id IS NOT NULL THEN
    RAISE EXCEPTION '试运行不允许产生上游记录 id（它没有写任何业务数据）'
      USING ERRCODE = 'restrict_violation';
  END IF;
  IF run_mode = 'APPLY' AND NEW.outcome = 'SUCCEEDED' AND NEW.upstream_id IS NULL THEN
    RAISE EXCEPTION '正式迁移里「迁成」的行必须有上游记录 id，否则没法回滚'
      USING ERRCODE = 'restrict_violation';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER guard_platform_migration_row_success
  BEFORE INSERT OR UPDATE ON platform_migration_row
  FOR EACH ROW EXECUTE FUNCTION guard_platform_migration_row_success();

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
ALTER TABLE platform_migration_mapping ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_migration_mapping FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_migration_mapping
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_migration_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_migration_run FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_migration_run
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_migration_file ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_migration_file FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_migration_file
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE platform_migration_row ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_migration_row FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation ON platform_migration_row
  USING (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

-- ---------------------------------------------------------------------------
-- 7. 授权
-- ---------------------------------------------------------------------------
--
-- 运行角色是 platform_app：读自己的映射 / 任务 / 文件 / 逐行结论，写自己的。
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_migration_mapping TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_migration_run TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_migration_file TO platform_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON platform_migration_row TO platform_app;

GRANT USAGE, SELECT ON SEQUENCE platform_migration_run_no_seq TO platform_app;
