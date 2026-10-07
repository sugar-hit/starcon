-- 3.8.1.2 校验报告：按任务读预校验结果、结构化落库、分「可跳过 / 必须修正」、导出带标注的错误文件。
--
-- 蓝图 3.8.1.2 的原话：
--   「展示逐行校验结果：必填缺失、格式错误、重复编码、引用不存在（分类/品牌/属性）、唯一冲突。
--     区分"可跳过"与"必须修正"，允许导出带标注的错误文件。」
--   「输入：导入预校验结果。」
--   「输出：错误明细表 + 可下载的修正版模板。」
--   「关系：上游 = 3.8.1.1；下游 = 无（修正后重新上传）。
--     校验失败的行不落库，不允许"尽力导入"产生半截数据。」
--
-- ============================================================ 本模块不重新校验，只做报告
--
-- 校验规则是 3.8.1.1 的 `ImportRules`（纯函数，ERROR-WARNING 分界即 `blocking()`）。
-- 本模块**不写第二套规则**，也不改 `import_row_error`：
--   * 规则的唯一写入所有者仍然是 3.8.1.1 的 `ImportJobExecutor`；
--   * 本文件只把「同一时刻的校验结论」**固化成一份报告**（快照），
--     再在这份快照上做分类统计、逐行定位与导出标注。
--
-- 为什么要快照，而不是每次查询现算 `import_row_error`：
--   `import_row_error` 的唯一键是 `UNIQUE(tenant_id,job_id,row_no,field_key,code)`，
--   而预校验**可重跑**（改了映射、或补了规则之后再跑一次）。
--   重跑是 `delete from import_row_error where job_id=?` 后整批重写（V29 上游做法）——
--   也就是说**上一轮的结论会被就地覆盖**。
--   于是「运营昨天导出并发出去的那份错误清单，今天打开页面已经变了」，
--   而报告页恰恰是用来跟人对账的：拿着昨天那份清单改完的文件重传，
--   今天页面显示的是另一轮结论，两边对不上却谁也说不清差在哪。
--   快照把「这一轮结论」钉死在一张报告上（`source_prevalidated_at`），
--   报告页再拿它和任务当前的 `prevalidated_at` 比，**不一致就明确报「报告已过期」**。
--
-- 快照不做成历史流水：一个任务保留一份当前报告（`UNIQUE(tenant_id,job_id)`），
-- 重跑预校验后重新生成即覆盖。审计流水是 17.5.3.1 的职责（api-proxy 里 identity 的 audit），
-- 本模块不造第二个审计表 —— 一个任务几十行、几轮重跑，逐轮留档的收益远小于它的歧义成本。
--
-- ============================================================ 1. 报告快照（一个任务一份）
--
-- 三段计数直接对应蓝图那句「区分可跳过与必须修正」，而分界来自
-- `import_row_error.severity`：有 ERROR 的行**永不落库**（blocking），
-- 只有 WARNING 的行**照常落库**（skippable）。这不是界面文案，是数据里可查的事实。
-- 为什么存的是预校验时刻，判定过期却用别的列：
--   报告要回答「这份结论出自哪一轮」，所以存 `source_prevalidated_at`（触发器在进入 PREVALIDATED 时写的）。
--   但它**不能**用来判过期：V29 的触发器是 `prevalidated_at IS NULL` 才写，
--   重跑预校验不会把它清空，于是两轮预校验的 prevalidated_at 是同一个值 —— 拿它比会得出「没变过」。
--   真正可靠的判据是 `generated_at` 与 `import_job.updated_at` 的先后：
--   触发器在**每次** update 时把 updated_at 刷成 now()，于是「报告生成之后任务又被写过」
--   就是「结论可能已经变了」的充分条件。宁可误报过期（用户点一下重新生成），
--   也不能拿着过期结论让用户去改文件。
CREATE TABLE import_validation_report (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, job_id uuid NOT NULL,
  -- 生成时读到的任务预校验时刻。报告一旦生成就属于**这一轮**。
  -- 过期判定用 generated_at 与 import_job.updated_at 比（理由见文件头），不用这一列。
  source_prevalidated_at timestamptz NOT NULL,
  -- 生成时任务的状态。落一份是为了让报告自己能说清「我是基于哪个状态生成的」，
  -- 而不是让读报告的人再回任务行里猜。
  source_status text NOT NULL
    CHECK(source_status IN ('PENDING','PREVALIDATING','PREVALIDATED','RUNNING','SUCCEEDED','PARTIAL','FAILED','CANCELLED')),
  total_row_count int NOT NULL CHECK(total_row_count>=0),
  -- 必须修正的行：有 ERROR，这些行执行阶段永不落库。
  blocking_row_count int NOT NULL CHECK(blocking_row_count>=0),
  -- 可跳过的行：只有 WARNING，行照常落库。
  skippable_row_count int NOT NULL CHECK(skippable_row_count>=0),
  -- 没有任何问题的行。
  clean_row_count int NOT NULL CHECK(clean_row_count>=0),
  -- 有问题的行去重后的总数（blocking + skippable，行级去重不是条级）。
  issue_row_count int NOT NULL CHECK(issue_row_count>=0),
  error_count int NOT NULL CHECK(error_count>=0),
  warning_count int NOT NULL CHECK(warning_count>=0),
  generated_at timestamptz NOT NULL DEFAULT now(),
  actor_id uuid NOT NULL,
  UNIQUE(tenant_id,id),
  -- 一个任务一份当前报告：重跑预校验后重新生成即覆盖（文件头「快照不做成历史流水」）。
  UNIQUE(tenant_id,job_id),
  FOREIGN KEY(tenant_id,job_id) REFERENCES import_job(tenant_id,id) ON DELETE CASCADE,
  -- 三段行数必须与总行数对得上：报告页顶部的四个数字如果能互相矛盾，
  -- 「多少行真的能导进去」就答不出来，而那正是运营唯一要问的问题。
  CHECK(blocking_row_count + skippable_row_count + clean_row_count = total_row_count),
  CHECK(issue_row_count = blocking_row_count + skippable_row_count)
);

-- ============================================================ 2. 错误明细表（蓝图的「输出」之一）
--
-- 蓝图把「错误明细表」列为本模块的输出，而 V29 的 `import_row_error` 只有
-- 「哪一行哪个字段出了什么问题」——报告要用它做的事多三件：
--   1. **归到蓝图点名的五类**（必填缺失 / 格式错误 / 重复编码 / 引用不存在 / 唯一冲突），
--      因为运营是按类批量改的，不是按 14 个机器码一个个认；
--   2. **带上原始单元格文本**，否则「第 47 行 spu_code 这一格原来是什么」还得回原文件里找；
--   3. **带上行结论**（这一行到底落不落库），因为落不落库是**行级**事实，
--      而 `import_row_error` 是**条级**的：同一行可能 2 条 WARNING + 1 条 ERROR，
--      只有把条级归到行才能说清「这一行能不能进」。
--
-- 因此这里是**派生明细**，不是校验结果的第二份事实：它的每一行都能指回
-- `import_row_error` 里唯一一条 (row_no, field_key, code)。上游那条仍然是唯一写入所有者。
CREATE TABLE import_validation_issue (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, report_id uuid NOT NULL, job_id uuid NOT NULL,
  row_no int NOT NULL CHECK(row_no>1),
  -- 问题类别：蓝图点名的五类 + 执行阶段写库失败一类。
  --
  -- 词表**故意封闭**（6 类，没有 OTHER 兜底）：`ImportValidationRules.categoryOf`
  -- 必须把上游每一个可能的 code 显式归类，映射不全时单测直接变红。
  -- 留一个 OTHER 桶看起来更「健壮」，实际后果是新增一个 code 时悄悄落进 OTHER，
  -- 运营在报告里看到「其他问题」却不知道该改什么 —— 那正是分类这件事要消除的。
  -- 六个取值与上游 code 的对应关系（V29 上游全部 14 个 code 都在表内，无遗漏）：
  --   REQUIRED_MISSING   REQUIRED / MATCH_KEY_REQUIRED / UNMAPPED_COLUMN
  --   FORMAT_INVALID     INVALID_TYPE / INVALID_ENUM / TOO_LONG / NEGATIVE / GROSS_LT_NET
  --   DUPLICATE_CODE     DUPLICATE_IN_FILE
  --   REFERENCE_MISSING  REFERENCE_NOT_FOUND
  --   UNIQUE_CONFLICT    EXISTS_IN_CREATE_MODE / NOT_FOUND_IN_UPDATE_MODE / BARCODE_TAKEN
  --   WRITE_FAILED       WRITE_FAILED
  category text NOT NULL
    CHECK(category IN ('REQUIRED_MISSING','FORMAT_INVALID','DUPLICATE_CODE',
                       'REFERENCE_MISSING','UNIQUE_CONFLICT','WRITE_FAILED')),
  -- 出错的字段键；空串表示「整行级」问题（编码重复落在整行上等）。
  field_key varchar(64) NOT NULL DEFAULT '',
  -- **文件里那一列的列头文字**：运营在自己的表里看到的就是这个名字，
  -- 而模板列头可能已经被改过。映射缺失时退回模板列头，整行级问题为空串。
  field_label varchar(100) NOT NULL DEFAULT '',
  code varchar(32) NOT NULL CHECK(btrim(code)<>''),
  severity text NOT NULL CHECK(severity IN ('ERROR','WARNING')),
  message varchar(300) NOT NULL CHECK(btrim(message)<>''),
  -- 该格在文件里的**原始文本**。落库而不是现取，是因为报告是快照：
  -- 重跑预校验后 `import_job_row.cells` 可能已按新映射重新解释，
  -- 那时再取就拿不到「生成这份报告时它到底是什么」了。
  raw_value varchar(300) NOT NULL DEFAULT '',
  -- 生成报告时这一行的 outcome 快照（VALID / INVALID / IMPORTED / SKIPPED / FAILED）。
  row_outcome text NOT NULL CHECK(row_outcome IN ('PENDING','VALID','INVALID','IMPORTED','SKIPPED','FAILED')),
  -- 这一行是否因存在 ERROR 而**永不落库**。行级事实，导出时逐行写进「本行是否落库」。
  row_blocking boolean NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  -- 同一份报告里一条问题只出现一次。重新生成走「先删本报告明细再整批写入」，
  -- 所以这个唯一键与 (tenant_id, job_id, report_id) 三者配合，
  -- 让重跑生成收敛到同一批 (行,字段,码) 而不是越跑越多。
  UNIQUE(tenant_id,report_id,row_no,field_key,code),
  FOREIGN KEY(tenant_id,report_id) REFERENCES import_validation_report(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,job_id) REFERENCES import_job(tenant_id,id) ON DELETE CASCADE,
  -- 非空 field_key 一定是模板声明过的列（上游只在模板列上产出 field_key），
  -- 所以 field_label 必须有值 —— 空 label 的明细在导出时无法标注到具体列。
  CHECK(field_key='' OR btrim(field_label)<>'')
);

-- ============================================================ 3. 导出登记（可下载的修正版模板）
--
-- 导出内容**不存字节**：它由 (报告, 导出种类, 严重级别过滤) 唯一确定，
-- 现算即可，而且现算能保证「今天下载到的和昨天下载到的字节一致」——
-- 本表把 content_sha256 记下来正是为了让这一点可验证（集成测试连续导两次比对哈希）。
--
-- 那这张表为什么还要存在：它回答「这份错误清单是什么时候、基于哪一轮结论、谁导出的」，
-- 而报告页要靠它告诉运营「你手上那份是上一轮的，现结论已经变了」。
-- 没有它，「基于哪一轮导出的」这句话无处可查，而这恰恰是最容易出错的一句话。
--
-- 存登记而不是存字节还有一条工程理由：导入文件本体按 V29 第 33 节的纪律就不落库
-- （执行只读库，不依赖本地磁盘），导出同理 —— 库里不留 CSV blob。
CREATE TABLE import_validation_export (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, job_id uuid NOT NULL, report_id uuid NOT NULL,
  -- ANNOTATED_ERRORS = 带标注的错误文件（全部数据行 + 标注列，按 severity 可过滤）
  -- FIXED_TEMPLATE   = 修正版模板（**只含有问题的行** + 标注列，改完这一份即可重传）
  export_kind text NOT NULL CHECK(export_kind IN ('ANNOTATED_ERRORS','FIXED_TEMPLATE')),
  -- 导出时只看哪一档问题：ALL / ERROR / WARNING。
  severity_filter text NOT NULL DEFAULT 'ALL' CHECK(severity_filter IN ('ALL','ERROR','WARNING')),
  row_count int NOT NULL CHECK(row_count>=0),
  issue_count int NOT NULL CHECK(issue_count>=0),
  -- 字节数走 NUMERIC(19,6)，与 V29 的 byte_size 同一口径；不存 bytea。
  byte_size numeric(19,6) NOT NULL CHECK(byte_size>=0),
  content_sha256 varchar(64) NOT NULL CHECK(length(content_sha256)=64),
  file_name varchar(255) NOT NULL CHECK(btrim(file_name)<>''),
  actor_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,report_id) REFERENCES import_validation_report(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY(tenant_id,job_id) REFERENCES import_job(tenant_id,id) ON DELETE CASCADE
);

-- ============================================================ 4. 索引
--
-- 报告页首屏：拿任务的当前报告（一个任务一份，UNIQUE 已经够快，但按 job 查是主路径）。
CREATE INDEX import_validation_report_by_job ON import_validation_report(tenant_id, job_id);
-- 逐行明细（默认按行号升序翻页）+ 按严重级别/类别筛选：报告页三种筛选共用这条索引。
CREATE INDEX import_validation_issue_by_report ON import_validation_issue(tenant_id, report_id, row_no, severity, category);
-- 「这一类问题涉及哪些行」与「这一列错了多少次」：分类/按列聚合走它。
CREATE INDEX import_validation_issue_by_category ON import_validation_issue(tenant_id, report_id, category, row_no);
-- 导出记录列表（按时间倒序）。
CREATE INDEX import_validation_export_by_report ON import_validation_export(tenant_id, report_id, created_at DESC, id DESC);

-- ============================================================ 5. RLS 与授权
--
-- 三张表全部租户级数据，与 V29 第 8 节同一口径：一次导入跨成百上千条商品，
-- 逐行判部门归属既不可能也没有意义。判定的对象是「这批数据能不能写进本租户」，
-- 由 tenant_id 隔离与页面权限两层把关。
--
-- 报告本身比它描述的行**更敏感**：错误清单里含的是运营还没导进去的原始数据
-- （比如「这个条码不存在」会顺带把这一批的条码值都留在导出文件里），
-- 所以租户隔离是强制的，**FORCE RLS** 让表 owner 也绕不过去。
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['import_validation_report','import_validation_issue','import_validation_export'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tbl);
    EXECUTE format('CREATE POLICY isolation ON %I USING (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.current_tenant'',true),'''')::uuid)',tbl);
  END LOOP;
END $$;

-- V2 之后运行时角色是 catalog_app（nologin 组角色 starcon_app 仍保留 V1 的授权语义）。
GRANT SELECT,INSERT,UPDATE,DELETE ON
  import_validation_report, import_validation_issue, import_validation_export TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON
  import_validation_report, import_validation_issue, import_validation_export TO starcon_app;

-- ============================================================ 6. 留给下游 / 本模块的边界
--
--   * **3.8.2.1 导出**：本表的导出登记与内容生成是同一份代码，
--     全库导出（不只是导入错误清单）走那里，不在本文件里扩范围。
--   * **审计**：多轮报告的历史不落库，理由见文件头；
--     需要「谁在什么时候看过哪一轮结论」时由 17.5.3.1 的 access 审计承担。
--   * **不新增 xlsx 导出**：与 V29 第 1 节同一条边界，仓库无 Excel 写依赖，
--     导出只出 CSV（带 UTF-8 BOM，Excel 直接打开不乱码）。