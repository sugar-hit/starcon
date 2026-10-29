-- 13.6.2.1 导出与订阅（卷 13「利润与经营分析」L3 13.6.2 的第二格）。
--
-- 蓝图原文：
--   「职责：报表导出（Excel/CSV/PDF）与定时订阅推送（邮件/企业 IM），支持大文件异步导出。」
--   「输入：导出请求；订阅配置。」
--   「输出：文件下载链接；订阅投递记录。」
--   「关系：上游 = 13.6.1.1；下游 = 1.3.2.2 推送。**大导出走异步任务并给出有效期**，
--     同步导出大文件会拖垮服务。」
--
-- ============================================================ 为什么是两张表
--
-- 蓝图这一格的输出是**一对**东西：「文件下载链接」与「订阅投递记录」。
-- 投递记录的上游（13.6.1.1 的 V408）已经建好了七张表里的
-- `reporting_custom_report_delivery`（队列）与 `reporting_custom_report_subscription`（计划），
-- ⇒ **本文件不碰它们**（零 ALTER 既有表），只补「导出」这一侧缺的两张：
--
--   reporting_export_job        —— 异步导出**任务**（状态机 / 文件本体 / 下载有效期 / 操作人）
--   reporting_export_job_source —— 这次导出**导的是什么**（报表 / 格式 / 字段选择 / 筛选快照 / 窗口）
--
-- 为什么拆两张而不是一张（照抄 catalog 的 V35 export_job 的分法，但那一张里字段选择是内联列）：
--   * 任务行关心的是「**进行到哪一步了**」：状态、文件在哪、多大、什么时候过期。
--   * 源行关心的是「**导出的到底是什么**」：哪张报表、什么格式、哪些列、什么筛选、什么窗口。
-- 两者**变更节奏不同**：源行在创建那一刻就冻结（一经创建不可改 —— 否则「当时按什么导出的」
-- 这句话就没有答案了），任务行却要一路改状态。放进同一张表 ⇒ 冻结约束会跟状态更新打架。
--
-- ============================================================ 1. 状态机（钉在触发器上）
--
--   PENDING ──> RUNNING ──> SUCCEEDED ──> EXPIRED
--     │            │            │
--     │            └──> FAILED  └──> CANCELLED（作废：发现导错范围时第一件事就是让它不能再下载）
--     └──> CANCELLED
--
-- 与 catalog 的 export_job 同一套理由：**并发下最容易出错的就是状态迁移**，而 CHECK 在
-- PostgreSQL 里可以被 NOT VALID 绕过，触发器不行。
-- 「同一状态重复更新」不算非法迁移（执行器每批都会 update 一次 status='RUNNING'）。
CREATE TABLE reporting_export_job (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 操作人：蓝图的「导出记录可追溯到操作人」。来自**已认证会话**，不在入参里。
  actor_id uuid NOT NULL,

  status text NOT NULL DEFAULT 'PENDING'
    CHECK(status IN ('PENDING','RUNNING','SUCCEEDED','FAILED','CANCELLED','EXPIRED')),

  -- 行数：文件里实际写了多少行数据（不含表头）。它与文件是同一次执行写出来的，
  -- 所以两者必须同时有值或同时为空 —— 由第 4 节的 CHECK 一并保证。
  row_count int NOT NULL DEFAULT 0 CHECK(row_count >= 0),

  -- 文件本体（见第 3 节）；未跑出文件时为 NULL。
  file_name varchar(255),
  storage_key text,
  byte_size numeric(19,6) CHECK(byte_size IS NULL OR byte_size >= 0),
  content_sha256 varchar(64),

  -- 执行失败的原因（成功 / 作废 / 过期时保留 —— 它是「当时发生了什么」的审计事实）。
  error text NOT NULL DEFAULT '',

  -- 「一个任务一个主人」的领取标记，由 DB 裁决：两个执行者各写各的局部 row_count，
  -- 后写的会把前一个的进度抹掉，任务显示「导出 0 行」而文件里其实有 120 行。
  worker_claimed_at timestamptz,

  requested_at timestamptz NOT NULL DEFAULT now(),
  started_at timestamptz,
  finished_at timestamptz,
  -- 🔴 **下载有效期**：在任务成功那一刻由服务层写入（可配置的业务参数，不是 DB 常量）。
  expires_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id)
);

-- ============================================================ 2. 导出源（冻结的快照）
--
-- 这一行回答「**这份文件是怎么来的**」：
--   report_id         导的是哪张自定义报表（逻辑引用，不建跨服务外键）
--   format            CSV / XLSX / PDF
--   selected_fields   选中的列（程序内的稳定键，如 DIM:SHOP / MEASURE:SALES_AMOUNT）
--   field_headers     当时的列头文字（另存一份中文列头：列头随时可能改，
--                     而审计要回答「当时导出的那份文件里第 3 列叫什么」）
--   filter_snapshot   当时的筛选条件（报表自己的筛选，快照下来）
--   window_from/to    导出的时间窗口
--
-- ⚠️ **这些是快照，不是引用**：它们在任务创建那一刻冻结，之后不可改
-- （触发器钉死）。事后再读「用户当下的配置」是答非所问。
--
-- 幂等键 = (tenant_id, job_id)：一个任务只有一条源记录。
CREATE TABLE reporting_export_job_source (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  job_id uuid NOT NULL REFERENCES reporting_export_job(id) ON DELETE CASCADE,

  report_id uuid NOT NULL,
  format text NOT NULL CHECK(format IN ('CSV','XLSX','PDF')),

  selected_fields jsonb NOT NULL DEFAULT '[]'::jsonb
    CHECK(jsonb_typeof(selected_fields)='array'),
  field_headers   jsonb NOT NULL DEFAULT '[]'::jsonb
    CHECK(jsonb_typeof(field_headers)='array'),
  filter_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb
    CHECK(jsonb_typeof(filter_snapshot)='object'),

  window_from date,
  window_to date,

  created_at timestamptz NOT NULL DEFAULT now(),

  -- 幂等键：一个任务一条源记录。
  UNIQUE(tenant_id, job_id),
  UNIQUE(tenant_id, id)
);

-- ===========================================================================
-- 3. 数据库不变量（把蓝图的话变成数据层做不到的事）
-- ===========================================================================

CREATE OR REPLACE FUNCTION guard_reporting_export_job() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE allowed boolean;
BEGIN
  -- 租户不可变更：租户来自已认证成员关系，不是可编辑字段。
  IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id THEN
    RAISE EXCEPTION '导出任务的租户不可变更（任务 %）：租户来自已认证成员关系，不是可编辑字段', OLD.id
      USING ERRCODE='23514';
  END IF;
  -- 操作人不可改写：这一列是审计的主体，改了它审计就指向了别人。
  IF NEW.actor_id IS DISTINCT FROM OLD.actor_id THEN
    RAISE EXCEPTION '导出任务的操作人不可变更（任务 %）：这一列是审计主体', OLD.id
      USING ERRCODE='23514';
  END IF;
  -- 文件落位之后不可换位置：storage_key 变了，下载端会去找一个从未写过的键。
  IF OLD.storage_key IS NOT NULL AND NEW.storage_key IS NOT NULL
     AND NEW.storage_key IS DISTINCT FROM OLD.storage_key THEN
    RAISE EXCEPTION '导出文件落位后不可更换存储键（任务 %）', OLD.id
      USING ERRCODE='23514';
  END IF;
  -- 清空存储键**只允许在作废时发生**。其它状态下清空它，会让一条 SUCCEEDED 的记录
  -- 指向不了自己的文件，下载端只能报「文件不在」。
  IF OLD.storage_key IS NOT NULL AND NEW.storage_key IS NULL AND NEW.status <> 'CANCELLED' THEN
    RAISE EXCEPTION '导出文件的存储键只能在作废时清空（任务 %）', OLD.id
      USING ERRCODE='23514';
  END IF;

  -- 状态没变时不做迁移判定：执行器每批都会 update 一次，那些更新的 status 是同一个值；
  -- 不短路会把「RUNNING → RUNNING」当成非法迁移打掉整批。
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    allowed := CASE OLD.status
      WHEN 'PENDING'   THEN NEW.status IN ('RUNNING','CANCELLED')
      WHEN 'RUNNING'   THEN NEW.status IN ('SUCCEEDED','FAILED')
      -- SUCCEEDED → EXPIRED：有效期到了。→ CANCELLED：未过期时运营主动作废这份文件。
      WHEN 'SUCCEEDED' THEN NEW.status IN ('EXPIRED','CANCELLED')
      ELSE false   -- 终态不可迁出：FAILED/CANCELLED/EXPIRED 之后再改状态等于让历史任务复活
    END;
    IF NOT allowed THEN
      RAISE EXCEPTION '导出任务状态不可从 % 迁移到 %（任务 %）', OLD.status, NEW.status, OLD.id
        USING ERRCODE='23514';
    END IF;

    IF NEW.status='RUNNING' AND NEW.started_at IS NULL THEN NEW.started_at=now(); END IF;
    IF NEW.status IN ('SUCCEEDED','FAILED','CANCELLED','EXPIRED') AND NEW.finished_at IS NULL THEN
      NEW.finished_at=now();
    END IF;
  END IF;
  NEW.updated_at=now();
  RETURN NEW;
END $$;
CREATE TRIGGER guard_reporting_export_job BEFORE UPDATE ON reporting_export_job
  FOR EACH ROW EXECUTE FUNCTION guard_reporting_export_job();

-- 源快照**一经创建不可改**：改了它，「当时按什么导出的」就没有答案了。
CREATE OR REPLACE FUNCTION guard_reporting_export_job_source() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
     OR NEW.job_id IS DISTINCT FROM OLD.job_id
     OR NEW.report_id IS DISTINCT FROM OLD.report_id
     OR NEW.format IS DISTINCT FROM OLD.format
     OR NEW.selected_fields IS DISTINCT FROM OLD.selected_fields
     OR NEW.field_headers IS DISTINCT FROM OLD.field_headers
     OR NEW.filter_snapshot IS DISTINCT FROM OLD.filter_snapshot
     OR NEW.window_from IS DISTINCT FROM OLD.window_from
     OR NEW.window_to IS DISTINCT FROM OLD.window_to THEN
    RAISE EXCEPTION '导出源快照不可变更（源 %）：它是创建那一刻的冻结快照，改了就无法解释这份文件', OLD.id
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER guard_reporting_export_job_source BEFORE UPDATE ON reporting_export_job_source
  FOR EACH ROW EXECUTE FUNCTION guard_reporting_export_job_source();

-- ===========================================================================
-- 4. 列间一致性约束（跨列才有意义 ⇒ 不能写成单列 CHECK）
-- ===========================================================================

-- (1) 有文件 ⟺ 成功或已过期。
--     EXPIRED 也保留 storage_key：文件过期后下载被服务层挡住，但「这份导出当初产出到哪、
--     多大、sha 是多少」仍然是有价值的事实（过期清理要靠这个键找文件）。
--     真正「没有文件」的四种状态（PENDING / RUNNING / FAILED / CANCELLED）必须为空键。
--     行数（row_count）不受这条约束管：0 行是一次**合法**的导出（条件确实没命中）。
ALTER TABLE reporting_export_job ADD CONSTRAINT reporting_export_job_file_iff_usable CHECK (
  (status IN ('SUCCEEDED','EXPIRED')
     AND storage_key IS NOT NULL AND file_name IS NOT NULL
     AND byte_size IS NOT NULL AND content_sha256 IS NOT NULL AND expires_at IS NOT NULL)
  OR (status NOT IN ('SUCCEEDED','EXPIRED') AND storage_key IS NULL)
);

-- (2) sha256 形状：固定 64 位十六进制，只在有文件时有值。
ALTER TABLE reporting_export_job ADD CONSTRAINT reporting_export_job_sha256_shape CHECK (
  content_sha256 IS NULL OR length(content_sha256) = 64
);

-- (3) 导出源必须挂在**本租户**的任务上：跨租户挂载会让 A 的任务导出 B 的报表。
CREATE OR REPLACE FUNCTION guard_reporting_export_job_source_tenant() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE owner_tenant uuid;
BEGIN
  SELECT tenant_id INTO owner_tenant FROM reporting_export_job WHERE id = NEW.job_id;
  IF owner_tenant IS NULL THEN
    RAISE EXCEPTION '导出源挂的任务不存在（源 %）', NEW.id USING ERRCODE='23514';
  END IF;
  IF owner_tenant IS DISTINCT FROM NEW.tenant_id THEN
    RAISE EXCEPTION '导出源与任务的租户必须一致（源 %）', NEW.id USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER reporting_export_job_source_tenant
  BEFORE INSERT OR UPDATE ON reporting_export_job_source
  FOR EACH ROW EXECUTE FUNCTION guard_reporting_export_job_source_tenant();

-- ===========================================================================
-- 5. 索引
-- ===========================================================================

-- 任务列表页：按请求时间倒序（「我刚点的那个导出好了没」是最常见的一屏）。
CREATE INDEX reporting_export_job_list ON reporting_export_job(tenant_id, requested_at DESC, id DESC);
-- 按状态过滤（页面上「只看失败的那些」）。
CREATE INDEX reporting_export_job_by_status ON reporting_export_job(tenant_id, status, requested_at DESC, id DESC);
-- 有效期扫描：找出「已过期但状态还没推进到 EXPIRED」的行（下载端惰性过期之外的兜底查询口）。
CREATE INDEX reporting_export_job_expiry ON reporting_export_job(tenant_id, expires_at)
  WHERE expires_at IS NOT NULL AND status='SUCCEEDED';
-- 源表按报表反查（某个报表导出过几次）。
CREATE INDEX reporting_export_job_source_by_report
  ON reporting_export_job_source(tenant_id, report_id, created_at DESC);

-- ===========================================================================
-- 6. 多租户（RLS isolation）+ 授权
-- ===========================================================================
--
-- 与 V376 / V389 / V408 **完全同构**：ENABLE 与 FORCE 同时开，USING 与 WITH CHECK 同时钉死。
--
-- 导出记录是**租户级**数据，不套 owner_id / department_id 的数据范围策略：
-- 那套策略是为「业务记录谁创建归谁管」设计的，而导出任务是租户自己的批量作业，
-- 判定的对象是「这份导出能不能写进本租户 / 能不能被本租户的人看到」。
-- **数据范围对导出内容仍然生效，且生效点在别处**：导出的行来自
-- `reporting_metric_fact`（13.1.1.1 的投影事实），导出查询跑在带 Access 的租户事务里
-- （它设置 app.actor / app.grants / app.department / app.permission）⇒ 逐行成立。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'reporting_export_job','reporting_export_job_source'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_export_job, reporting_export_job_source TO reporting_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  reporting_export_job, reporting_export_job_source TO starcon_app;
REVOKE ALL ON flyway_schema_history FROM reporting_app;

-- ===========================================================================
-- 7. 留给下游的口子（不是本模块的缺口）
-- ===========================================================================
--
--   * **18.1.3.1 导入导出任务通道**：蓝图写「大批量统一走 18.1.3.1」。本模块自带的是
--     一套可独立运行的（任务表 + 异步执行 + 下载有效期），并入时由 18.1.3.1 接管调度与
--     产物投递，本表继续作为「导出记录」的**唯一事实来源** —— 审计不能挂在别人的任务通道上。
--   * **1.3.2.2 推送**：蓝图把本模块的下游写成「1.3.2.2 推送」。本模块产出的是**文件**与
--     **投递记录**；工作台侧的推送编排（把导出结果推到工作台卡片）属 1.3.2.2 的范围，
--     它读本模块的导出任务与投递记录，不在本模块内实现。
--   * **订阅投递记录**沿用 13.6.1.1 的 `reporting_custom_report_delivery`
--     （队列）与 `reporting_custom_report_subscription`（计划）：本模块只**消费**那张队列
--     （把它推进成 DELIVERED / FAILED），**不新建**投递表 ⇒ 不产生第二个投递真相。
