-- 3.8.2.1 导出：按当前列表页的筛选条件导出结果数据，导出记录可追溯到操作人。
--
-- 蓝图 3.8.2.1 的原话：
--   「职责：按当前列表页的筛选条件导出结果数据（SPU/SKU/库存视图/报价等），
--     支持字段选择、排序还原与大文件异步导出，导出记录可追溯到操作人。」
--   「输入：列表筛选条件；字段选择；导出请求；用户数据范围。」
--   「输出：导出文件（带下载有效期）+ 导出记录（条件、字段、行数、时间、操作人）。」
--   「关系：上游 = 3.1.1.1 / 3.1.2.1 / 3.6.2 等列表页；下游 = 用户本地文件；
--     大批量统一走 18.1.3.1 导入导出任务通道。**导出必须服从数据范围与 PII 授权**：
--     含联系方式等敏感字段的导出需显式授权并留审计（见 16.6.1）。」
--
-- ============================================================ 一张表，不多不少
--
-- 蓝图的输出是**一对**东西：「导出文件（带下载有效期）」与「导出记录」。
-- 所以本文件只建一张 `export_job`：文件本体不进这张表（见第三节），
-- 而「条件 / 字段 / 行数 / 时间 / 操作人」这五项**全部**落在这一行上——
-- 拆成「任务表 + 字段明细表」会把「这次导出到底选了什么」这个最常被追问的问题
-- 变成一次 join，而拆开之后两表之间还多出一个能不一致的口子。
--
-- ============================================================ 1. 条件与字段：快照，不是引用
--
-- filter_snapshot / selected_fields / field_headers / sort_snapshot 四列都是 jsonb，
-- 且在**任务创建那一刻**冻结，不引用任何当时的行：
--
--   * 筛选条件（filter_snapshot）：用户点导出时列表页上的那套条件。冻结它，
--     「这批数据是按当时那套条件导出来的」才答得上来；事后再读用户当下的条件是答非所问。
--   * 字段清单（selected_fields）：程序内的稳定字段键列表（如 CODE、NAME）。
--     field_headers 另存一份**当时的中文列头**：列头文字随时可能改，
--     而审计要回答的是「当时导出的那份文件里第 3 列叫什么」，
--     指向今天的字典只会给出另一个答案。
--   * 排序还原（sort_snapshot）：{sort, order}。蓝图点名的「排序还原」——
--     导出的行序要与列表页当时看到的行序一致，否则用户按「价格从低到高」排完序
--     导出来一看顺序全变了，这份导出就没有用。
--
-- 刻意**不**存「已生成的行数据」：行数据是文件本体，存在导出存储里（见第三节），
-- 在库里再抄一份等于把大文件复制进业务库，而 V29 已经把「二进制不落业务库」
-- 当成本服务的纪律（那里针对的是上传件，这里针对的是生成件，同一条纪律）。
--
-- ============================================================ 2. PII 授权：显式，且留痕
--
-- 「含联系方式等敏感字段的导出需显式授权并留审计」在本表上是**四列事实**，
-- 不是界面上的一句话：
--
--   includes_pii  本次导出是否含敏感字段（由服务层按字段清单推导，不接受客户端自称）
--   pii_fields    具体是哪几个敏感字段（如 EMAIL、PHONE、MOBILE）——授权的是具体字段，不是"整个导出"
--   pii_purpose   授权理由（非空才允许 includes_pii=true）：谁、为什么要把联系方式导出去
--   actor_id      操作人：与上面三列同属一行，所以「谁在什么时候带什么理由导走了哪些联系方式」
--                 是**一次查询**能答出来的，不需要跨服务去翻 16.6.1 的审计日志
--
-- 由 DB 再钉一道（约束本身在表建好之后加，见第 8 节）：includes_pii=true 时
-- pii_fields 与 pii_purpose 都不得为空。服务层已经会拒绝，但 CHECK 是第二道——
-- 绕过服务层直连 SQL 写进来的脏数据同样会污染这条审计线，而审计线被污染是最难被发现的一种错。

-- ============================================================ 3. 文件本体：走存储端口，不进业务库
--
-- file_name / storage_key / byte_size / content_sha256 记的是**文件在哪、有多大、是哪一份**：
--   * storage_key 走 3.4.1.1 的 MediaStorage 端口（当前 LocalMediaStorage 本地实现，
--     生产换 S3/OSS 时只换实现类）。键里带租户段：一个租户算不出另一个租户的键。
--   * content_sha256 回答「两次导出得到的是不是同一份文件」——同一套条件重跑一次，
--     若 sha256 变了说明这期间数据变了，这个差异本身就是有用的信息。
--   * byte_size 用 NUMERIC(19,6) 而非 bigint：与本库其它字节/数量列同精度（AGENTS.md 禁浮点）。
--
-- 允许为空的语义：**任务还没跑出文件时这几列就是空的**（PENDING / RUNNING / FAILED / CANCELLED）。
-- 「有文件 ⟺ 成功或已过期」这条由第 6 节的 CHECK 保证，不会出现「状态是成功却没有文件」
-- 或「已过期却查不出当初的文件键」。
--
-- sha256 固定 64 位十六进制；只在有文件时有值——同样由第 8 节的 CHECK 保证。

-- ============================================================ 4. 下载有效期
--
-- expires_at 在任务成功那一刻由服务层写入（下载有效期是可配置的业务参数，不是 DB 常量）。
-- 过期后：下载端返回 410 Gone，并把状态推进到 EXPIRED、同时删掉存储对象——
-- 「有效期过了文件还在」等于有效期没生效，而文件里若有 PII，那就是一份无主的数据。
--
-- EXPIRED 是**终态**：不允许从 EXPIRED 再迁回 SUCCEEDED（那会让一条已判过期的导出重新可下载）。
-- 想要就重新发起一次导出，那会是一条新的任务行，审计线上多一条而不是覆盖旧的。
--
-- ============================================================ 5. 状态机
--
--   PENDING ──> RUNNING ──> SUCCEEDED ──> EXPIRED
--     │            │            │
--     │            └──> FAILED   └──> CANCELLED（仅未开跑时可取消）
--     └──> CANCELLED
--
-- 与 import_job 同样的理由钉在触发器上：并发下最容易出错的就是状态迁移，
-- 而 CHECK 在 PostgreSQL 里可以被 NOT VALID 绕过，触发器不行。
-- 本任务**没有**「同一租户同类任务互斥」约束——那是导入的要求（蓝图对导入写死了
-- 「同一租户同时只允许一个同类导入任务，避免并发覆盖」），导出是只读作业，
-- 多个人同时导不同筛选条件没有任何互相覆盖的风险，加互斥只会白白挡住合法的并发导出。
CREATE TABLE export_job (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,

  -- 数据源键：'SPU' / 'SKU' / 'CONTACT'。词表由代码侧的 ExportDataSource 注册表定义
  -- （每个数据源自带它的字段与排序白名单），这里存的是注册表里的键而不是表名——
  -- 存表名会让「加一个数据源」变成一次迁移，而数据源是代码资产。
  source_key text NOT NULL CHECK(btrim(source_key) <> ''),

  status text NOT NULL DEFAULT 'PENDING'
    CHECK(status IN ('PENDING','RUNNING','SUCCEEDED','FAILED','CANCELLED','EXPIRED')),

  -- 冻结的条件与字段（见第一节）。
  filter_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
  selected_fields jsonb NOT NULL CHECK(jsonb_typeof(selected_fields)='array'),
  field_headers   jsonb NOT NULL CHECK(jsonb_typeof(field_headers)='array'),
  sort_snapshot   jsonb NOT NULL CHECK(jsonb_typeof(sort_snapshot)='object'),

  -- 行数：文件里实际写了多少行数据（不含表头）。它与文件是同一次执行写出来的，
  -- 所以两者必须同时有值或同时为空——由第 3 节的 CHECK 一并保证。
  row_count int NOT NULL DEFAULT 0 CHECK(row_count >= 0),

  -- 文件本体（见第三节）；未跑出文件时为 NULL。
  file_name varchar(255),
  storage_key text,
  byte_size numeric(19,6) CHECK(byte_size IS NULL OR byte_size >= 0),
  content_sha256 varchar(64),

  -- PII 授权与审计（见第二节）。
  includes_pii boolean NOT NULL DEFAULT false,
  pii_fields jsonb NOT NULL DEFAULT '[]'::jsonb CHECK(jsonb_typeof(pii_fields)='array'),
  pii_purpose text NOT NULL DEFAULT '',

  -- 操作人：蓝图的「导出记录可追溯到操作人」。
  -- department_id 一并存下：导出留痕在合规复核时要能回答「当时是以什么数据范围点的导出」，
  -- 只存 user_id 就丢了这一维（同一个用户在不同部门身份下范围不同）。
  actor_id uuid NOT NULL,
  department_id uuid,

  error text NOT NULL DEFAULT '',

  -- 「一个任务一个主人」的领取标记，由 DB 裁决（与 V29 import_job 同一条经验：
  -- 两个执行者各写各的局部 row_count，后写的会抹掉前一个的进度）。
  worker_claimed_at timestamptz,

  requested_at timestamptz NOT NULL DEFAULT now(),
  started_at timestamptz,
  finished_at timestamptz,
  expires_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),

  UNIQUE(tenant_id,id),
  CHECK(jsonb_typeof(filter_snapshot)='object')
);

CREATE FUNCTION guard_export_job() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE allowed boolean;
BEGIN
  -- 租户不可变更：租户来自已认证成员关系，不是可编辑字段。
  IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id THEN
    RAISE EXCEPTION '导出任务的租户不可变更（任务 %）：租户来自已认证成员关系，不是可编辑字段', OLD.id
      USING ERRCODE='23514';
  END IF;
  -- 条件 / 字段 / 排序是**快照**，一经创建不可改：改了它，「当时按什么导出的」就没有答案了。
  IF NEW.filter_snapshot IS DISTINCT FROM OLD.filter_snapshot
     OR NEW.selected_fields IS DISTINCT FROM OLD.selected_fields
     OR NEW.field_headers   IS DISTINCT FROM OLD.field_headers
     OR NEW.sort_snapshot   IS DISTINCT FROM OLD.sort_snapshot THEN
    RAISE EXCEPTION '导出任务的筛选条件与字段清单不可变更（任务 %）：它们是创建那一刻的快照，改了就无法解释这份文件', OLD.id
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
  -- 清空存储键**只允许在作废时发生**：作废的语义是「这份文件不再可取」，
  -- 所以要把键摘掉（DB CHECK 也要求非成功态无键）。其它状态下清空它，
  -- 会让一条 SUCCEEDED 的记录指向不了自己的文件，下载端只能报「文件不在」。
  -- file_name / row_count / content_sha256 不清空 —— 它们是「当初导出了什么」的审计事实，
  -- 随作废一起删掉等于把审计也作废掉。
  IF OLD.storage_key IS NOT NULL AND NEW.storage_key IS NULL AND NEW.status <> 'CANCELLED' THEN
    RAISE EXCEPTION '导出文件的存储键只能在作废时清空（任务 %）', OLD.id
      USING ERRCODE='23514';
  END IF;

  -- 状态没变时不做迁移判定：执行器每批都会 update 一次（row_count 等），
  -- 那些更新的 status 是同一个值；不短路会把「RUNNING → RUNNING」当成非法迁移打掉整批。
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    allowed := CASE OLD.status
      WHEN 'PENDING'   THEN NEW.status IN ('RUNNING','CANCELLED')
      WHEN 'RUNNING'   THEN NEW.status IN ('SUCCEEDED','FAILED')
      -- SUCCEEDED → EXPIRED：有效期到了。→ CANCELLED：未过期时运营主动作废这份文件
      -- （含 PII 的导出发现导错范围时，第一件事就是让它不能再下载）。
      WHEN 'SUCCEEDED' THEN NEW.status IN ('EXPIRED','CANCELLED')
      ELSE false   -- 终态不可迁出：FAILED/CANCELLED/EXPIRED 之后再改状态等于让历史任务复活
    END;
    IF NOT allowed THEN
      RAISE EXCEPTION '导出任务状态不可从 % 迁移到 %（任务 %）', OLD.status, NEW.status, OLD.id
        USING ERRCODE='23514';
    END IF;

    IF NEW.status='RUNNING'   AND NEW.started_at  IS NULL THEN NEW.started_at=now();  END IF;
    IF NEW.status IN ('SUCCEEDED','FAILED','CANCELLED','EXPIRED') AND NEW.finished_at IS NULL THEN
      NEW.finished_at=now();
    END IF;
  END IF;
  NEW.updated_at=now();
  RETURN NEW;
END $$;
CREATE TRIGGER guard_export_job BEFORE UPDATE ON export_job
  FOR EACH ROW EXECUTE FUNCTION guard_export_job();

-- ============================================================ 6. 列间一致性约束（放在表与触发器之后）
--
-- 三条都只在**跨列**才有意义，所以不能写成某一列上的单列 CHECK，统一放这里。
--
-- (1) PII 授权可追溯：includes_pii=true 时，敏感字段清单与授权理由都必须非空。
ALTER TABLE export_job ADD CONSTRAINT export_job_pii_traceable CHECK (
  includes_pii = false OR (jsonb_array_length(pii_fields) > 0 AND btrim(pii_purpose) <> '')
);
--
-- (2) 有文件 ⟺ 成功或已过期。
--     **EXPIRED 也保留 storage_key**：文件过期后下载被服务层挡住，但「这份导出当初
--     产出到哪、多大、sha 是多少」仍然是有价值的事实（过期清理要靠这个键找文件，
--     审计要靠 sha 回答「导出去的那份是不是这一份」）。若在这里强制清空 storage_key，
--     过期之后就再也找不到那个文件，清理任务只能靠猜。
--     真正「没有文件」的四种状态（PENDING / RUNNING / FAILED / CANCELLED）必须为空键：
--     带一个指向未产出文件的键，会让下载端去读一个从来不存在的东西。
--     行数（row_count）不受这条约束管：0 行是一次**合法**的导出（条件确实没命中）。
ALTER TABLE export_job ADD CONSTRAINT export_job_file_iff_usable CHECK (
  (status IN ('SUCCEEDED','EXPIRED')
     AND storage_key IS NOT NULL AND file_name IS NOT NULL
     AND byte_size IS NOT NULL AND content_sha256 IS NOT NULL AND expires_at IS NOT NULL)
  OR (status NOT IN ('SUCCEEDED','EXPIRED') AND storage_key IS NULL)
);
--
-- (3) sha256 形状：固定 64 位十六进制，只在有文件时有值。
ALTER TABLE export_job ADD CONSTRAINT export_job_sha256_shape CHECK (
  content_sha256 IS NULL OR length(content_sha256) = 64
);

-- ============================================================ 7. 索引
--
-- 导出记录列表页：按请求时间倒序（「我刚点的那个导出好了没」是最常见的一屏）。
CREATE INDEX export_job_list ON export_job(tenant_id, requested_at DESC, id DESC);
-- 按数据源过滤 + 倒序：运营常问「昨天导了几次 SKU」。
CREATE INDEX export_job_by_source ON export_job(tenant_id, source_key, requested_at DESC, id DESC);
-- 有效期扫描：找出「已过期但状态还没推进到 EXPIRED」的行（下载端惰性过期之外的兜底查询口）。
CREATE INDEX export_job_expiry ON export_job(tenant_id, expires_at)
  WHERE expires_at IS NOT NULL AND status='SUCCEEDED';

-- ============================================================ 8. RLS 与授权
--
-- 导出记录是**租户级**数据，不套 V3 的 owner_id/department_id 数据范围：
-- 那套策略是为「业务记录谁创建归谁管」设计的，而导出任务是租户自己的批量作业，
-- 判定的对象是「这份导出能不能写进本租户 / 能不能被本租户的人看到」，
-- 由 tenant_id 隔离与页面权限两层把关。
--
-- **数据范围仍然对导出内容生效，且生效点在别处**：导出的**行**来自 spu / sku / party_contact，
-- 这三张表各自带 V3 的 data_scope RESTRICTIVE 策略；导出查询跑在
-- `access.run(actor, permission)` 开的租户事务里（它设置 app.actor / app.grants /
-- app.department / app.permission），所以 SELF / DEPARTMENT 数据范围的判定
-- 在导出 SQL 上逐行成立——**A 租户（或 SELF 范围的用户）导不出看不见的行**，
-- 这条由集成测试实跑断言，不是靠这里注释声明的。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['export_job'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

GRANT SELECT,INSERT,UPDATE,DELETE ON export_job TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON export_job TO starcon_app;

-- ============================================================ 9. 留给下游的口子（不是本模块的缺口）
--
--   * **3.6.2.1 报价列表**：报价的写入所有者是定价服务，本轮它尚未并入本分支，
--     所以注册表里没有 QUOTE 数据源。等它落地后，加一个 ExportDataSource 实现
--     即可（自带字段白名单、排序白名单与 PII 标记），**本表结构不需要动**——
--     这正是把数据源做成代码侧注册表、而不是在这里枚举表名的原因。
--   * **18.1.3.1 导入导出任务通道**：蓝图写「大批量统一走 18.1.3.1」。
--     本模块自带的是可独立运行的一套（任务表 + 异步执行 + 下载有效期），
--     并入时由 18.1.3.1 接管调度与产物投递，本表继续作为「导出记录与 PII 授权审计」的
--     唯一事实来源——那部分不该随通道一起搬走，审计不能挂在别人的任务通道上。
--   * **16.6.1 审计**：本表已经能回答「谁、何时、什么条件、哪些字段、多少行」，
--     且 PII 导出带授权理由；16.6.1 落地后应读这张表，而不是另建一套导出审计。