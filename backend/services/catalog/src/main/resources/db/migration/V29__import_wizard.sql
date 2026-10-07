-- 3.8.1.1 导入向导：模板字典、字段映射配置、异步导入任务、逐行结果、逐行错误。
--
-- 蓝图 3.8.1.1 的原话：
--   「批量导入商品（SPU/SKU/属性/包装/条码/报价），四步引导：下载模板 → 上传文件 → 字段映射
--     → 预校验 → 执行。」
--   「输入：Excel/CSV 文件；字段映射配置；导入模式（新增/更新/按编码匹配）。」
--   「输出：导入任务 + 结果统计。」
--   「关系：上游 = —；下游 = 3.8.1.2 校验报告。**导入是异步任务**，大文件不阻塞页面；
--     同一租户同时只允许一个同类导入任务，避免并发覆盖。」
--
-- 标题写「四步」而后面列了五个环节（下载模板 / 上传文件 / 字段映射 / 预校验 / 执行）。
-- 本文件按**后面列的五个环节**建落点，因为那是原文的实质内容：
-- 少了任何一个环节，向导就有一段路走不通，而不是少一步少一层皮。
--
-- ============================================================ 本模块写哪些表
--
-- 导入对象 = 蓝图括号里列的六类。**这里只落 catalog 自有的五类**：
--
--     SPU / SKU / ATTRIBUTE / PACKAGE / BARCODE
--
-- **「报价」不在本文件里，理由与 V5 / V6 开头写的是同一条**：
-- 售价与报价的**唯一写入所有者是 05 定价服务**（V5 开头：「售价归 05 定价（阶段 D5）」，
-- V6 开头把「成本与采购价归 06 采购」也钉在同一处）。catalog 现在把报价列存成本地表，
-- 等于凭空造出第二个价格事实来源：两份数字必然漂移，而漂移之后
-- 「平台上架价到底是多少」没有任何一张表能作 answer。
-- 05 定价尚未落地，所以本轮也没有授权 API 可以把导入的报价**转发**过去。
-- 因此本文件既不建报价表，也不接受 `target_entity='QUOTE'`：
-- 在 05 定价服务交付、且有授权写入端点之前，报价导入没有正确的归属。
-- 这不是「后续再补」的空话，是一次有边界的取舍：宁可少一类对象，
-- 也不要让价格有两个主人。等 05 定价就位，报价导入作为独立实体加进
-- import_job.target_entity 的 CHECK 与模板字典即可，本文件的其余结构不需要动。
--
-- ============================================================ 上传的文件本体不落库
--
-- 与 V19「二进制仍然不落库」同一纪律，但这里的理由更进一步：
-- **任务要在提交请求之外、被另一个工作线程、甚至另一个进程读回来执行**，
-- 而执行需要的是「这一行归一化后的值」而不是「原始字节」。
-- 所以上传时就把 CSV 解析掉，结果逐行落进 `import_job_row.values`（jsonb，存原始文本）。
-- 于是：
--   * 执行阶段只读库，不依赖任何本地磁盘路径 → 换进程、重启都不丢队列；
--   * 库里没有 bytea，任务表的体积与行数同阶而不是与文件体积同阶；
--   * 任务行仍记 `file_name` / `byte_size` / `content_sha256`，
--     用来回答「导进去的到底是哪份文件」（同一份文件重复导两次能看出来）。
--
-- ============================================================ 1. 模板字典（下载模板 + 字段映射的共同词表）
--
-- 模板是**租户级字典**，不是每次上传临时拼的列名。原因有两条：
--   * 「下载模板」要给运营一份带列头与示例行的文件，列头必须有个稳定出处；
--   * 「字段映射」要把上传文件的列头对上模板的字段，映射的目标侧同样来自这份字典。
-- 两者若各写一份，运营改了模板列而映射表没跟上，预校验就会按旧列名报一堆「必填缺失」。
CREATE TABLE import_template (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  code varchar(64) NOT NULL CHECK(btrim(code)<>''),
  name varchar(100) NOT NULL CHECK(btrim(name)<>''),
  note text NOT NULL DEFAULT '',
  -- 目标对象：决定执行阶段往哪张表写。与文件头的取舍一致，**故意不含 QUOTE**。
  target_entity text NOT NULL
    CHECK(target_entity IN ('SPU','SKU','ATTRIBUTE','PACKAGE','BARCODE')),
  enabled boolean NOT NULL DEFAULT true,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id), UNIQUE(tenant_id,code)
);

-- 模板列：一行 = 一个可导入字段。ordinal 决定下载模板里的列顺序。
CREATE TABLE import_template_column (
  tenant_id uuid NOT NULL, template_id uuid NOT NULL,
  -- 字段键是**程序内的稳定标识**（如 spu_code、net_weight_g），不是给运营看的列头文字。
  -- 列头文字（header）运营随时可改，映射与校验都只认 field_key —— 否则改一次列头
  -- 就等于让所有已保存的映射配置集体失效。
  field_key varchar(64) NOT NULL CHECK(btrim(field_key)<>''),
  header varchar(100) NOT NULL CHECK(btrim(header)<>''),
  data_type text NOT NULL CHECK(data_type IN ('TEXT','DECIMAL','INTEGER','BOOLEAN','DATE','TIMESTAMPTZ')),
  required boolean NOT NULL DEFAULT false,
  -- 匹配键：「按编码匹配」模式靠它定位行（导入到哪条已有记录）。
  -- 同一模板至多一个匹配键，部分唯一索引在下面第 5 节。
  match_key boolean NOT NULL DEFAULT false,
  -- 示例值：下载模板时写在表头下面那一行。空模板对运营没有指引意义。
  sample_value varchar(200) NOT NULL DEFAULT '',
  help text NOT NULL DEFAULT '',
  ordinal int NOT NULL CHECK(ordinal>=0),
  PRIMARY KEY(tenant_id,template_id,field_key),
  UNIQUE(tenant_id,template_id,ordinal),
  FOREIGN KEY(tenant_id,template_id) REFERENCES import_template(tenant_id,id) ON DELETE CASCADE
);

-- ============================================================ 2. 字段映射配置（存库、可复用）
--
-- 「字段映射配置」是蓝图的输入之一，且要求可复用：不同供应商的列名不一样
-- （Amazon 的 `item_sku`、1688 的 `商家编码`），映射一次之后每批文件都能直接套。
-- mapping 是 jsonb：{"<field_key>": "<上传文件的列头>"}。
-- 用 jsonb 而不是子表，是因为映射只在「读文件 + 预校验」这一条路径上整体读出，
-- 从不按单列查询；子表只会让写映射变成 N 次往返。
CREATE TABLE import_field_mapping (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, template_id uuid NOT NULL,
  name varchar(100) NOT NULL CHECK(btrim(name)<>''),
  mapping jsonb NOT NULL DEFAULT '{}'::jsonb,
  note text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 映射按名字复用，所以名字在租户内唯一：同名两条会让「选一个映射」这个问题有两个答案。
  UNIQUE(tenant_id,name),
  FOREIGN KEY(tenant_id,template_id) REFERENCES import_template(tenant_id,id) ON DELETE CASCADE,
  CHECK(jsonb_typeof(mapping)='object')
);

-- ============================================================ 3. 导入任务（异步）
--
-- 状态机（执行阶段只允许合法迁移，见文件头第 6 节的触发器）：
--
--   PENDING ──prevalidate──> PREVALIDATING ──> PREVALIDATED ──execute──> RUNNING
--                                                                              │
--                                        ┌─────────────────────────────────────┤
--                                        v                                     v
--                                  SUCCEEDED / PARTIAL                      FAILED
--
-- PENDING = 已上传、已解析、还没开始预校验。PREVALIDATED = 预校验做完、等运营看过结果再决定执行。
-- 「预校验」与「执行」分成两个显式阶段，是因为蓝图把预校验单列为一个环节，
-- 且 3.8.1.2 要在两者之间插入「校验报告」让人看过再放行。
CREATE TABLE import_job (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, template_id uuid NOT NULL,
  -- 冗余存一份目标对象：互斥约束（下面第 4 节）要按它分组，
  -- 每次开事务回查模板表只为读这一列是不必要的往返，而这一列本身不会与模板打架
  -- （服务层写入时从同一处取值，且有触发器守卫）。
  target_entity text NOT NULL
    CHECK(target_entity IN ('SPU','SKU','ATTRIBUTE','PACKAGE','BARCODE')),
  -- 导入模式：CREATE=只新建（编码已存在即该行失败）；UPDATE=只更新（编码不存在即该行失败）；
  -- MATCH_BY_CODE=先按匹配键找，找不到就新建、找到就更新。
  import_mode text NOT NULL CHECK(import_mode IN ('CREATE','UPDATE','MATCH_BY_CODE')),
  file_name varchar(255) NOT NULL,
  file_format text NOT NULL CHECK(file_format IN ('CSV')),
  byte_size numeric(19,6) NOT NULL CHECK(byte_size>0),
  content_sha256 varchar(64) NOT NULL CHECK(length(content_sha256)=64),
  -- 数据行数（不含表头）。用它当进度分母。
  row_count int NOT NULL CHECK(row_count>=0),
  -- 本次任务实际使用的映射快照：存副本而不是只存 import_field_mapping 的 id，
  -- 因为映射配置随时会被改，而「这批数据是按哪套映射进的」必须冻结。
  mapping jsonb NOT NULL DEFAULT '{}'::jsonb,
  status text NOT NULL DEFAULT 'PENDING'
    CHECK(status IN ('PENDING','PREVALIDATING','PREVALIDATED','RUNNING','SUCCEEDED','PARTIAL','FAILED','CANCELLED')),
  -- 结果统计：四段计数。页面上「多少行成功、多少行失败、多少行跳过」直接读这四列。
  valid_count int NOT NULL DEFAULT 0 CHECK(valid_count>=0),
  invalid_count int NOT NULL DEFAULT 0 CHECK(invalid_count>=0),
  applied_count int NOT NULL DEFAULT 0 CHECK(applied_count>=0),
  failed_count int NOT NULL DEFAULT 0 CHECK(failed_count>=0),
  skipped_count int NOT NULL DEFAULT 0 CHECK(skipped_count>=0),
  -- 整体失败原因（不是逐行的那种，逐行在 import_row_error）。
  error text NOT NULL DEFAULT '',
  actor_id uuid NOT NULL,
  -- 「一个任务一个主人」的领取标记。
  --
  -- 领取 = `update import_job set worker_claimed_at=now() where id=? and worker_claimed_at is null`，
  -- 命中 0 行就说明别人已经在跑。**由 DB 裁决**，所以两个工作线程（线程池并行度 2，
  -- 加上同一任务被重复入队）里只有一个能真正干活，另一个直接退出。
  --
  -- 这条不是可有可无的：3.4.1.2 的 ImageProcessExecutor 在同一个坑上死锁过 ——
  -- 两个线程各自推进同一个任务并互相等待对方交还连接，在单连接池下整个测试挂死。
  -- 更糟的是不互斥时的**计数覆盖**：两个执行者各自把 applied_count 写成自己的局部计数，
  -- 后写的那次会抹掉前一次的进度，任务显示「成功 0 条」而库里其实写了 5 条。
  --
  -- 重跑预校验/重跑执行时由服务层清空它（把它重置成可领取）。
  worker_claimed_at timestamptz,
  prevalidated_at timestamptz, started_at timestamptz, finished_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,template_id) REFERENCES import_template(tenant_id,id) ON DELETE RESTRICT,
  CHECK(jsonb_typeof(mapping)='object')
);

-- ============================================================ 4. 同一租户同类任务互斥（DB 层）
--
-- 蓝图：「同一租户同时只允许一个同类导入任务，避免并发覆盖」。
-- 关键在**DB 层**：只在前端禁用按钮，两个标签页同时点、或者运营直接调 API，
-- 就会出现两批数据同时往同一批 SKU 上写 —— 结果是后写的赢，而前一批的修改无声消失。
-- 所以这里用**部分唯一索引**（不是约束、不是触发器）：
--
--   UNIQUE(tenant_id, target_entity) WHERE status IN (活跃态)
--
-- 语义：同一个租户、同一个目标对象，活跃任务至多一条。终态（SUCCEEDED/PARTIAL/FAILED/
-- CANCELLED）不进索引，所以历史任务可以无限多条。
-- 插入第二个活跃任务会撞 23505，服务层把它翻译成 409 并点名「当前已有同类任务在跑」。
--
-- 为什么用部分索引而不是「先查再插」：先查再插在两个并发事务之间有窗口期，
-- 两者都读到「没有活跃任务」然后都插入。唯一索引由数据库在写入时裁决，没有窗口期。
CREATE UNIQUE INDEX import_job_one_active_per_entity
  ON import_job(tenant_id, target_entity)
  WHERE status IN ('PENDING','PREVALIDATING','PREVALIDATED','RUNNING');

-- 一个模板至多一个匹配键：两个匹配键时「按编码匹配」不知道按哪个找。
CREATE UNIQUE INDEX import_template_one_match_key
  ON import_template_column(tenant_id, template_id) WHERE match_key;

-- ============================================================ 5. 逐行结果 + 逐行错误
--
-- 任务级只有四个计数（「导入是异步任务」的进度条要的就是这个），
-- 但运营要的是「**第 47 行为什么没进来**」——所以每行一条、每个错误一条，都带 row_no。
CREATE TABLE import_job_row (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, job_id uuid NOT NULL,
  -- 行号：**文件里的物理行号，表头是第 1 行，第一行数据是第 2 行**。
  -- 用物理行号而不是「第几条数据」，运营在 Excel 里数到的就是物理行号 ——
  -- 报「第 3 条数据」他还得先自己减掉表头。
  row_no int NOT NULL CHECK(row_no>1),
  -- **文件原样的「表头 → 单元格」**：{"<上传文件的列头文字>": "<原始文本>"}。
  --
  -- 刻意**不**存「已按模板映射好的 field_key → 值」：字段映射是五环节里**上传之后**才有的
  -- 那一步（运营看到真实列名才决定怎么对），而上传时映射可能还没定、也一定会被改。
  -- 存成已映射的形状，改一次映射就得重新上传文件 —— 「下载模板 → 上传 → 映射」这条
  -- 引导链会断在第三步。存原样则映射可以在预校验之前随意改，改完再跑预校验。
  --
  -- 存**原始文本**而不是转好的类型：预校验要按类型逐行报错（"第 12 行 net_weight_g
  -- 不是数字：abc"），提前转型会把「哪一列错了」这条信息丢掉。
  -- 列名不用 values：那是 PostgreSQL 的保留字，拿它当列名依赖的是
  -- 解析器在特定位置的宽容度，而不是语法保证。这里叫 cells。
  cells jsonb NOT NULL,
  outcome text NOT NULL DEFAULT 'PENDING'
    CHECK(outcome IN ('PENDING','VALID','INVALID','IMPORTED','SKIPPED','FAILED')),
  error_count int NOT NULL DEFAULT 0 CHECK(error_count>=0),
  -- 命中/新建的业务对象 id：执行成功后回填，让「这行变成了哪条记录」可追溯。
  spu_id uuid, sku_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,job_id,row_no),
  FOREIGN KEY(tenant_id,job_id) REFERENCES import_job(tenant_id,id) ON DELETE CASCADE,
  CHECK(jsonb_typeof(cells)='object')
);

-- 逐行错误明细：预校验的**交付物**。
-- severity 区分「必须修正」（ERROR，行不落库）与「可跳过」（WARNING，行仍然进）。
-- 这条分界直接决定行落不落库，所以它在数据里是可查的事实，不是界面上的一句说明。
CREATE TABLE import_row_error (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, job_id uuid NOT NULL, row_no int NOT NULL CHECK(row_no>1),
  -- 出错的字段键；空串表示「整行级」错误（列数不对、编码重复等）。
  field_key varchar(64) NOT NULL DEFAULT '',
  -- 机器码：前端可以据此做本地化与分组，不解析中文。
  code varchar(32) NOT NULL CHECK(btrim(code)<>''),
  message varchar(300) NOT NULL CHECK(btrim(message)<>''),
  severity text NOT NULL CHECK(severity IN ('ERROR','WARNING')),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一条错误在重跑预校验时不重复堆积：预校验可重跑（改了映射再跑一次），
  -- 唯一键让「重跑」变成「覆盖同一批 (行,字段,码)」而不是「越跑越多」。
  UNIQUE(tenant_id,job_id,row_no,field_key,code),
  FOREIGN KEY (tenant_id,job_id) REFERENCES import_job(tenant_id,id) ON DELETE CASCADE
);

-- ============================================================ 6. 触发器：状态机 + 时间戳 + 目标对象不可漂移
--
-- CHECK 约束在 PostgreSQL 里可以被 NOT VALID 绕过，触发器不行；
-- 而「任务只能沿合法状态迁移」是并发下最容易出错的一条（两个工作线程同时推进同一任务），
-- 所以钉在触发器上。
CREATE FUNCTION guard_import_job_status() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE allowed boolean;
BEGIN
  IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id THEN
    RAISE EXCEPTION '导入任务的租户不可变更（任务 %）：租户来自已认证成员关系，不是可编辑字段', OLD.id
      USING ERRCODE='23514';
  END IF;
  IF NEW.template_id IS DISTINCT FROM OLD.template_id THEN
    RAISE EXCEPTION '导入任务的模板不可更换（任务 %）：换模板后列的含义全变，已解析的行无法按新列解释', OLD.id
      USING ERRCODE='23514';
  END IF;
  -- 目标对象必须跟着模板走：它同时是互斥索引的分组键，与模板不一致会让互斥判错对象。
  IF NEW.target_entity IS DISTINCT FROM OLD.target_entity THEN
    RAISE EXCEPTION '导入任务的目标对象不可变更（任务 %）：它与模板不一致会让「同类任务互斥」判错对象', OLD.id
      USING ERRCODE='23514';
  END IF;

  -- 状态没变时**不**做迁移判定：执行器每批都会 update 一次计数
  -- （valid_count / applied_count / error …），那些更新的 status 是同一个值。
  -- 如果不短路，「PREVALIDATING → PREVALIDATING」会被当成一次非法迁移而把整批计数更新全打掉。
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    allowed := CASE OLD.status
      WHEN 'PENDING'        THEN NEW.status IN ('PREVALIDATING','CANCELLED')
      -- PREVALIDATED 允许退回 PENDING：改了字段映射之后，行结论是按旧映射算的，
      -- 留着它们就是让「valid/invalid 计数」与「当前列的含义」对不上。
      -- 所以换映射即作废结论（由服务层在改映射时顺手退回，重跑预校验再算一遍）。
      WHEN 'PREVALIDATED'   THEN NEW.status IN ('PREVALIDATING','PENDING','RUNNING','CANCELLED')
      -- PREVALIDATING → PREVALIDATED 是「重跑」：映射没变也要能重算一遍
      -- （比如 3.8.1.2 那边补了规则、或运营就是想再看一次结论）。
      WHEN 'PREVALIDATING'  THEN NEW.status IN ('PREVALIDATED','FAILED','CANCELLED')
      WHEN 'RUNNING'        THEN NEW.status IN ('SUCCEEDED','PARTIAL','FAILED','CANCELLED')
      ELSE false   -- 终态不可再迁出：跑完的任务被改回 PENDING 会让互斥索引重新占位并被重跑
    END;
    IF NOT allowed THEN
      RAISE EXCEPTION '导入任务状态不可从 % 迁移到 %（任务 %）', OLD.status, NEW.status, OLD.id
        USING ERRCODE='23514';
    END IF;

    -- 顺带维护时间戳，省掉服务层每次都记得 set 三列的负担。
    IF NEW.status='PREVALIDATED' AND NEW.prevalidated_at IS NULL THEN NEW.prevalidated_at=now(); END IF;
    IF NEW.status='RUNNING' AND NEW.started_at IS NULL THEN NEW.started_at=now(); END IF;
    IF NEW.status IN ('SUCCEEDED','PARTIAL','FAILED','CANCELLED') AND NEW.finished_at IS NULL THEN
      NEW.finished_at=now();
    END IF;
  END IF;
  NEW.updated_at=now();
  RETURN NEW;
END $$;
CREATE TRIGGER guard_import_job BEFORE UPDATE ON import_job
  FOR EACH ROW EXECUTE FUNCTION guard_import_job_status();

-- 模板被任务引用后不可删除：任务行里的 template_id 是 RESTRICT，删模板会直接被 FK 拒。
-- 但**停用**是允许的（enabled=false）：历史任务照常可读，新的导入不再选它。
-- 模板列被任务引用后也不可删（否则「这批数据当初是按哪几列进的」就答不出来了）。
CREATE FUNCTION refuse_import_template_column_delete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE jobs bigint;
BEGIN
 SELECT count(*) INTO jobs FROM import_job j
  WHERE j.tenant_id=OLD.tenant_id AND j.template_id=OLD.template_id;
 IF jobs>0 THEN
  RAISE EXCEPTION '模板列「%」已被 % 个导入任务引用，不可删除：任务记录里存的是这批数据按哪几列解析的，删掉列就无法解释这批数据。要停用请改模板的 enabled',
   OLD.field_key, jobs USING ERRCODE='23503';
 END IF;
 RETURN OLD;
END $$;
CREATE TRIGGER refuse_import_template_column_delete BEFORE DELETE ON import_template_column
  FOR EACH ROW EXECUTE FUNCTION refuse_import_template_column_delete();

-- 引用了任务的映射配置删除时，任务的 mapping 快照仍然是完整的一份 —— 快照就是快照。
-- 但映射配置本身可以删：它只是「下次可以再套一套」，不是历史事实。

-- ============================================================ 7. 索引
--
-- 任务列表页：按创建时间倒序（页面最常见是「我刚点的那个跑到哪了」）。
CREATE INDEX import_job_list ON import_job(tenant_id, created_at DESC, id DESC);
-- 「当前有没有同类任务在跑」：互斥判定与占用提示都走它。部分索引因为活跃行远少于历史行。
CREATE INDEX import_job_active ON import_job(tenant_id, target_entity, created_at DESC)
  WHERE status IN ('PENDING','PREVALIDATING','PREVALIDATED','RUNNING');
-- 执行器扫「这个任务里还有哪些行没处理」，以及错误列表按行号翻页。
CREATE INDEX import_job_row_by_job ON import_job_row(tenant_id, job_id, outcome, row_no);
CREATE INDEX import_row_error_by_job ON import_row_error(tenant_id, job_id, row_no, severity);
-- 模板字典与映射配置列表。
CREATE INDEX import_template_list ON import_template(tenant_id, enabled, code);
CREATE INDEX import_template_column_by_template ON import_template_column(tenant_id, template_id, ordinal, field_key);
CREATE INDEX import_field_mapping_by_template ON import_field_mapping(tenant_id, template_id, name);

-- ============================================================ 8. RLS 与授权
--
-- 五张表全部租户级数据（导入是租户自己的批量作业，不套 V3 的 owner_id/department_id
-- 数据范围 —— 一次导入跨成百上千条商品，逐行判部门归属既不可能也没有意义：
-- 判定的对象是「这批数据能不能写进本租户」，由 tenant_id 隔离与页面权限两层把关）。
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['import_template','import_template_column','import_field_mapping','import_job','import_job_row','import_row_error'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
  EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
 END LOOP;
END $$;

-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
GRANT SELECT,INSERT,UPDATE,DELETE ON
  import_template, import_template_column, import_field_mapping,
  import_job, import_job_row, import_row_error TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  import_template, import_template_column, import_field_mapping,
  import_job, import_job_row, import_row_error TO starcon_app;

-- ============================================================ 9. 留给下游的口子（不是本模块的缺口）
--
--   * **3.8.1.2 校验报告**：读 import_row_error（逐行逐列的 ERROR/WARNING）即可，
--     不需要在本服务里另建一套错误表。「可跳过的行不阻断导入、必须修正的行不落库」
--     这条分界在 severity 列上，是可查的事实。
--   * **3.8.2.1 导出**：导入任务列表与结果统计已经有分页端点，导出走同一份数据。
--   * **05 定价的报价导入**：见文件头第一节的取舍说明。
