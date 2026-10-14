package com.starcon.catalog;

import static com.starcon.catalog.CatalogService.check;
import static com.starcon.catalog.ImportWizardModels.*;

import com.starcon.tenancy.Ids;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 3.8.1.1 的导入任务：上传、字段映射、任务与结果查询（五环节里的第 2、3 步与「结果统计」）。
 *
 * <p><b>上传只做「解析 + 落行 + 建任务」，不写任何商品表。</b> 这是蓝图把预校验与执行拆成两个独立环节的直接结果：上传完的任务是 {@code PENDING}，
 * 它的逐行结果还没算出来，此刻页面上还不能说「这一批能不能导」。 真正的写库在 {@link ImportJobExecutor} 的执行阶段。
 *
 * <p><b>为什么解析结果逐行落库而不是把文件留在磁盘上</b>：执行发生在请求之外 （另一个线程、可能另一个进程）。把行落库之后，执行只读库，
 * 于是「队列不丢」「换进程不用重新传文件」两件事都自动成立， 而且库里不会出现任何指向本地路径的悬空引用。代价是库里存了一份解析后的副本 —— 它是文本，不是文件，体积与行数同阶。
 *
 * <p><b>互斥在 DB 层</b>：{@code import_job_one_active_per_entity} 部分唯一索引保证
 * 「同一租户同一目标对象同时只有一个活跃任务」。这里把它撞出来的 23505 翻译成 409 并点名当前在跑的任务 —— <b>不是</b>「先查有没有再插」：那种写法在两个并发请求之间有窗口期。
 */
@Service
public class ImportJobService {
  private final DSLContext sql;
  private final ImportJobExecutor executor;

  public ImportJobService(DSLContext sql, ImportJobExecutor executor) {
    this.sql = sql;
    this.executor = executor;
  }

  // ================================================================ 上传（第 2 步）

  /**
   * 解析上传的 CSV，并把「文件有问题」翻译成 400。
   *
   * <p>{@link ImportCsvParser} 用 {@link IllegalArgumentException} 表达 「编码认不出 / 表头缺列名 /
   * 超上限」——那是**上传者**的问题（换个文件重传）， 不是服务端的 500。直接放行的话页面上会弹一个没有信息量的「服务器错误」， 而真实的理由（"表头第 3
   * 列没有列名"）才是运营该看到的。
   */
  private static ImportCsvParser.Parsed parseCsv(byte[] content) {
    try {
      return ImportCsvParser.parse(content);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  /**
   * 上传文件：解析 → 建任务 → 逐行落库。
   *
   * <p>返回的是「已受理」，不是「导入完成」。任务此刻是 PENDING，预校验要等用户点下一步。
   *
   * @param mapping 可选的字段映射；为空时按列名自动猜一套（见 {@link #autoMapping}）
   */
  public ImportUploadSummary upload(
      UUID tenantId,
      UUID templateId,
      String importMode,
      Map<String, String> mapping,
      String fileName,
      byte[] content) {
    var templateRow = sql.fetchOne("select * from import_template where id=?", templateId);
    if (templateRow == null) throw notFound("模板不存在或已删除");
    check(
        templateRow.get("enabled", Boolean.class),
        "模板「" + templateRow.get("name", String.class) + "」已停用，请换一个模板");
    ImportRules.Entity entity =
        ImportRules.entityOf(templateRow.get("target_entity", String.class));
    check(entity != null, "模板的目标对象不合法");
    ImportRules.Mode mode = ImportRules.modeOf(importMode);
    check(mode != null, "导入模式不合法：" + importMode + "（可用：CREATE / UPDATE / MATCH_BY_CODE）");

    var parsed = parseCsv(content);
    var columns = columns(tenantId, templateId);
    check(!columns.isEmpty(), "模板还没有任何列定义，无法解析文件");

    // 初始映射：显式传入的优先，否则按列名自动猜一套。
    // 自动猜不是「假装映射已完成」：猜不中的列在预校验里会明确报 UNMAPPED_COLUMN。
    Map<String, String> effective =
        mapping == null || mapping.isEmpty()
            ? autoMapping(columns, parsed.headers())
            : new LinkedHashMap<>(mapping);
    checkTemplateColumnsDeclared(effective, columns);

    // 先看一眼有没有同类任务在跑：只是为了让报错**指名道姓**（哪个文件、什么状态），
    // 便于运营直接去处理。它**不是**互斥的裁决者 —— 两个并发请求可能都读到「没有」，
    // 所以真正的裁决在下面那次 insert 上，由 DB 的部分唯一索引做。
    var running = activeJob(tenantId, entity.name());
    if (running != null)
      throw conflict(
          "已有同类的「"
              + entity.name()
              + "」导入任务在进行中（"
              + running.get("file_name", String.class)
              + "，"
              + statusLabel(running.get("status", String.class))
              + "）。为避免并发覆盖，同一时间只允许一个同类任务，请等它结束或先取消它");

    UUID jobId = Ids.next();
    try {
      sql.execute(
          "insert into import_job(id,tenant_id,template_id,target_entity,import_mode,file_name,"
              + "file_format,byte_size,content_sha256,row_count,mapping,status,actor_id)"
              + " values(?,?,?,?,?,?,'CSV',?,?,?,?::jsonb,'PENDING',?)",
          jobId,
          tenantId,
          templateId,
          entity.name(),
          mode.name(),
          fileName == null || fileName.isBlank() ? "import.csv" : fileName.trim(),
          java.math.BigDecimal.valueOf(content.length),
          ImportCsvParser.sha256(content),
          parsed.rows().size(),
          ImportTemplateService.json(effective),
          Ids.next());
    } catch (DataAccessException e) {
      // 撞的是 import_job_one_active_per_entity —— 并发窗口里由 DB 裁决的那一次。
      // 这里**不能**再查库找是哪个任务：insert 失败已经把当前事务置为 aborted，
      // 任何后续查询都会报 "current transaction is aborted"。
      // 所以消息只能指向「任务列表」让运营自己看，这比让整条事务死掉划算。
      throw conflict(
          "已有同类的「"
              + entity.name()
              + "」导入任务在进行中。为避免并发覆盖，同一时间只允许一个同类任务，"
              + "请在导入任务列表里查看当前任务、等它结束或取消后重试");
    }

    if (!parsed.rows().isEmpty()) {
      List<Object[]> batch = new ArrayList<>(parsed.rows().size());
      for (int i = 0; i < parsed.rows().size(); i++) {
        // row_no 从 2 开始：第 1 行是表头，运营在 Excel 里数到的行号就是这个数。
        Map<String, String> cells = new LinkedHashMap<>();
        List<String> row = parsed.rows().get(i);
        for (int c = 0; c < parsed.headers().size(); c++)
          cells.put(parsed.headers().get(c), c < row.size() ? row.get(c) : "");
        batch.add(
            new Object[] {Ids.next(), tenantId, jobId, i + 2, ImportTemplateService.json(cells)});
      }
      // 逐行 execute 而不是 DSLContext.batch(字符串)：本仓库的写法一律是
      // sql.execute + 显式 ?::jsonb，batch(String, ...) 对 `?::jsonb` 这种
      // 「占位符后接 cast」的解析并不可靠（曾经整批静默插 0 行）。
      // 行数上限（MAX_ROWS）与文件上限（MAX_BYTES）在这里兜住单次上传的代价。
      for (Object[] bindings : batch)
        sql.execute(
            "insert into import_job_row(id,tenant_id,job_id,row_no,cells) values(?,?,?,?,?::jsonb)",
            bindings);
    }

    return new ImportUploadSummary(
        jobId,
        fileName == null ? "import.csv" : fileName.trim(),
        java.math.BigDecimal.valueOf(content.length),
        ImportCsvParser.sha256(content),
        parsed.rows().size(),
        parsed.headers().size(),
        parsed.headers());
  }

  // ================================================================ 字段映射（第 3 步）

  /**
   * 改本次任务的字段映射。
   *
   * <p>行里存的是<b>文件原样的表头→单元格</b>，所以映射可以在预校验之前随意改 —— 改完直接重跑预校验即可，不需要重新上传文件。
   *
   * <p><b>已经预校验过的任务改映射会把行结论作废</b>（退回 PENDING）： 上一轮的 valid/invalid 是按旧映射算的，留着它就等于让任务行上的统计
   * 与当前列的含义对不上。重跑预校验会重算，不作废的话用户会拿着旧结论去放行。
   */
  public ImportJobRow setMapping(UUID tenantId, UUID jobId, ImportMappingInput input) {
    var job = job(tenantId, jobId);
    check(
        job.status().equals("PENDING") || job.status().equals("PREVALIDATED"),
        "当前状态「" + statusLabel(job.status()) + "」不能改映射。映射只能在「已上传」与「预校验完成」两步之间调整");
    var columns = columns(tenantId, job.templateId());
    checkTemplateColumnsDeclared(input.mapping(), columns);
    sql.execute(
        "update import_job set mapping=?::jsonb,status='PENDING' where id=?",
        ImportTemplateService.json(input.mapping()),
        jobId);
    if (input.saveAs() != null && !input.saveAs().isBlank()) {
      new ImportTemplateService(sql)
          .saveMapping(
              tenantId,
              new ImportFieldMappingInput(
                  job.templateId(),
                  input.saveAs().trim(),
                  input.mapping(),
                  "由导入任务 " + jobId + " 保存"));
    }
    return job(tenantId, jobId);
  }

  /**
   * 按列名猜一套映射。
   *
   * <p>三级匹配：字段键精确 → 列头精确 → 列头忽略大小写。 猜中就把文件表头写进映射，猜不中就**不写**（于是那一列在预校验里报 「模板列 X
   * 在文件里找不到对应列」，而不是静默按空值处理）。
   */
  static Map<String, String> autoMapping(List<ImportTemplateColumn> columns, List<String> headers) {
    Map<String, String> byKey = new LinkedHashMap<>();
    for (String h : headers) byKey.putIfAbsent(h.trim(), h);
    Map<String, String> lower = new LinkedHashMap<>();
    for (String h : headers) lower.putIfAbsent(h.trim().toLowerCase(Locale.ROOT), h);
    Map<String, String> mapping = new LinkedHashMap<>();
    for (var c : columns) {
      String hit = byKey.get(c.fieldKey());
      if (hit == null) hit = byKey.get(c.header());
      if (hit == null) hit = lower.get(c.fieldKey().toLowerCase(Locale.ROOT));
      if (hit == null) hit = lower.get(c.header().trim().toLowerCase(Locale.ROOT));
      if (hit != null) mapping.put(c.fieldKey(), hit);
    }
    return mapping;
  }

  // ================================================================ 触发预校验 / 执行（第 4、5 步）

  /**
   * 请求预校验：立刻返回「已受理」，结果由 {@link ImportJobExecutor} 落库后轮询。
   *
   * <p>已预校验的任务可以**重跑**（映射没变也允许，比如 3.8.1.2 那边补了规则之后想再看一次）。 重跑前会清掉上一轮的行结论与错误明细 ——
   * 否则页面上会同时挂着两轮互相矛盾的结论。
   */
  public ImportJobRow requestPrevalidate(AccessService.Access actor, UUID jobId) {
    UUID tenantId = actor.tenantId();
    var job = job(tenantId, jobId);
    if (job.status().equals("PENDING") || job.status().equals("PREVALIDATED")) {
      // 互斥索引仍占位（PENDING / PREVALIDATING / PREVALIDATED 都在索引条件里），
      // 所以重跑预校验不会让另一个同类任务插进来。
      // 清空 worker_claimed_at：重跑时任务必须重新变成「可被领取」。
      sql.execute(
          "update import_job set status='PREVALIDATING',error='',worker_claimed_at=null"
              + " where id=? and status in ('PENDING','PREVALIDATED')",
          jobId);
    } else {
      throw conflict("当前状态「" + statusLabel(job.status()) + "」不能预校验：只有已上传或已预校验的任务可以（重）跑预校验");
    }
    executor.enqueuePrevalidate(actor, jobId);
    return job(tenantId, jobId);
  }

  /** 请求执行：立刻返回「已受理」。只有预校验通过（有可落库的行）才允许执行。 */
  public ImportJobRow requestExecute(AccessService.Access actor, UUID jobId) {
    UUID tenantId = actor.tenantId();
    var job = job(tenantId, jobId);
    check(
        job.status().equals("PREVALIDATED"),
        "执行前必须先完成预校验（当前状态「" + statusLabel(job.status()) + "」）。预校验会把有错误的行挑出来，避免半截数据入库");
    sql.execute(
        "update import_job set status='RUNNING',worker_claimed_at=null where id=? and status='PREVALIDATED'",
        jobId);
    executor.enqueueExecute(actor, jobId);
    return job(tenantId, jobId);
  }

  /**
   * 取消任务，释放「同类任务互斥」的槽位。
   *
   * <p><b>取消不是删除已写的数据。</b>已经落库的行由 {@code spu / sku / ...} 自己拥有， 不在本模块的删除权限里（删商品是 3.1.1
   * 的职责，且要过引用检查）。 所以取消只停掉「还没跑的部分」：互斥槽位立刻释放，被取消的行保持原状。
   *
   * <p>正在执行（{@code RUNNING}）的任务取消：执行器每批重新读一次行， 下一批读到的仍是 {@code outcome='VALID'} 的行，所以它不会自动停 ——
   * 因此这里只允许取消 <b>PENDING / PREVALIDATED</b> 两个「还没开跑」的状态， RUNNING 必须等它自己到终态。这样「取消」是一个可预期的动作，而不是一个
   * 取决于线程调度时机的竞态。
   */
  public ImportJobRow cancel(UUID tenantId, UUID jobId) {
    var job = job(tenantId, jobId);
    check(
        job.status().equals("PENDING") || job.status().equals("PREVALIDATED"),
        "当前状态「"
            + statusLabel(job.status())
            + "」不能取消。只有「已上传」或「预校验完成」"
            + "（还没开始执行）的任务可以取消；正在导入的任务请等它跑完");
    sql.execute(
        "update import_job set status='CANCELLED' where id=? and status in ('PENDING','PREVALIDATED')",
        jobId);
    return job(tenantId, jobId);
  }

  // ================================================================ 查询（结果统计）

  /** 任务详情。 */
  public ImportJobRow job(UUID tenantId, UUID jobId) {
    var r =
        sql.fetchOne(
            "select j.*,t.name as template_name from import_job j"
                + " join import_template t on t.tenant_id=j.tenant_id and t.id=j.template_id where j.id=?",
            jobId);
    if (r == null) throw notFound("导入任务不存在或已删除");
    return jobRow(r);
  }

  /** 任务列表（分页 + 按目标对象/状态过滤）。 */
  public ImportJobPage jobs(UUID tenantId, String targetEntity, String status, int page, int size) {
    StringBuilder where = new StringBuilder(" where 1=1");
    List<Object> args = new ArrayList<>();
    if (targetEntity != null && !targetEntity.isBlank()) {
      where.append(" and j.target_entity=?");
      args.add(targetEntity.trim().toUpperCase(Locale.ROOT));
    }
    if (status != null && !status.isBlank()) {
      where.append(" and j.status=?");
      args.add(status.trim().toUpperCase(Locale.ROOT));
    }
    long total =
        sql.fetchOne("select count(*) from import_job j" + where, args.toArray())
            .get(0, Long.class);
    int limit = Math.min(Math.max(size, 1), 100);
    int offset = Math.max(page, 0) * limit;
    args.add(limit);
    args.add(offset);
    var rows =
        sql.fetch(
            "select j.*,t.name as template_name from import_job j"
                + " join import_template t on t.tenant_id=j.tenant_id and t.id=j.template_id"
                + where
                + " order by j.created_at desc,j.id desc limit ? offset ?",
            args.toArray());
    return new ImportJobPage(rows.map(ImportJobService::jobRow), Math.max(page, 0), limit, total);
  }

  /** 逐行结果（分页，可按 outcome 过滤）。UI 上的「第几行错了」走这里。 */
  public ImportJobRowPage rows(UUID tenantId, UUID jobId, String outcome, int page, int size) {
    job(tenantId, jobId);
    StringBuilder where = new StringBuilder(" where job_id=?");
    List<Object> args = new ArrayList<>();
    args.add(jobId);
    if (outcome != null && !outcome.isBlank()) {
      where.append(" and outcome=?");
      args.add(outcome.trim().toUpperCase(Locale.ROOT));
    }
    long total =
        sql.fetchOne("select count(*) from import_job_row" + where, args.toArray())
            .get(0, Long.class);
    int limit = Math.min(Math.max(size, 1), 200);
    int offset = Math.max(page, 0) * limit;
    args.add(limit);
    args.add(offset);
    var fetched =
        sql.fetch(
            "select * from import_job_row" + where + " order by row_no limit ? offset ?",
            args.toArray());
    return new ImportJobRowPage(
        fetched.map(
            r ->
                new ImportJobRowLine(
                    r.get("id", UUID.class),
                    r.get("row_no", Integer.class),
                    r.get("outcome", String.class),
                    r.get("error_count", Integer.class),
                    ImportTemplateService.readJson(r.get("cells")),
                    r.get("spu_id", UUID.class),
                    r.get("sku_id", UUID.class))),
        Math.max(page, 0),
        limit,
        total);
  }

  /**
   * 逐行错误明细（本模块的预校验交付物）。
   *
   * <p>3.8.1.2 的「校验报告」页面读的就是这份数据：它要展示逐行校验结果、 区分「可跳过」与「必须修正」，并在此基础上导出带标注的错误文件。 所以这里的过滤维度里有
   * severity，而不只是行号。
   */
  public ImportRowErrorPage errors(UUID tenantId, UUID jobId, String severity, int page, int size) {
    job(tenantId, jobId);
    StringBuilder where = new StringBuilder(" where job_id=?");
    List<Object> args = new ArrayList<>();
    args.add(jobId);
    if (severity != null && !severity.isBlank()) {
      where.append(" and severity=?");
      args.add(severity.trim().toUpperCase(Locale.ROOT));
    }
    long total =
        sql.fetchOne("select count(*) from import_row_error" + where, args.toArray())
            .get(0, Long.class);
    var counts =
        sql.fetchOne(
            "select count(*) filter (where severity='ERROR') as errors,"
                + "count(*) filter (where severity='WARNING') as warnings from import_row_error where job_id=?",
            jobId);
    int limit = Math.min(Math.max(size, 1), 200);
    int offset = Math.max(page, 0) * limit;
    args.add(limit);
    args.add(offset);
    var fetched =
        sql.fetch(
            "select * from import_row_error"
                + where
                + " order by row_no,severity desc,field_key limit ? offset ?",
            args.toArray());
    return new ImportRowErrorPage(
        fetched.map(
            r ->
                new ImportRowErrorLine(
                    r.get("id", UUID.class),
                    r.get("row_no", Integer.class),
                    r.get("field_key", String.class),
                    r.get("code", String.class),
                    r.get("message", String.class),
                    r.get("severity", String.class))),
        Math.max(page, 0),
        limit,
        total,
        counts.get("errors", Integer.class),
        counts.get("warnings", Integer.class));
  }

  // ================================================================ 供执行器复用的读

  /** 模板列（规则视图）。 */
  List<ImportRules.Column> ruleColumns(UUID tenantId, UUID templateId) {
    return sql.fetch(
            "select * from import_template_column where template_id=? order by ordinal,field_key",
            templateId)
        .map(
            r ->
                new ImportRules.Column(
                    r.get("field_key", String.class), r.get("data_type", String.class),
                    r.get("required", Boolean.class), r.get("match_key", Boolean.class)));
  }

  /** 传输视图的模板列（下载模板、UI 渲染列用）。 */
  List<ImportTemplateColumn> columns(UUID tenantId, UUID templateId) {
    return sql.fetch(
            "select * from import_template_column where template_id=? order by ordinal,field_key",
            templateId)
        .map(
            r ->
                new ImportTemplateColumn(
                    r.get("field_key", String.class),
                    r.get("header", String.class),
                    r.get("data_type", String.class),
                    r.get("required", Boolean.class),
                    r.get("match_key", Boolean.class),
                    r.get("sample_value", String.class),
                    r.get("help", String.class),
                    r.get("ordinal", Integer.class)));
  }

  /**
   * 把「文件原样的表头→单元格」按映射解析成「模板字段键→值」。
   *
   * <p>映射里没有的字段键解析成空串 —— 之后由预校验报「必填缺失」或「这一列在文件里没有」， 而不是在这里静默丢列。
   */
  static Map<String, String> resolveValues(
      List<ImportRules.Column> columns, Map<String, String> mapping, Map<String, String> cells) {
    Map<String, String> out = new LinkedHashMap<>();
    for (var column : columns) {
      String header = mapping.get(column.fieldKey());
      String value = header == null ? null : cells.get(header);
      out.put(column.fieldKey(), value == null ? "" : value.trim());
    }
    return out;
  }

  /** 映射里出现了模板没有的字段键就点名报错（存进去就永远不会生效的那种映射）。 */
  private static void checkTemplateColumnsDeclared(
      Map<String, String> mapping, List<ImportTemplateColumn> columns) {
    var declared =
        columns.stream()
            .map(ImportTemplateColumn::fieldKey)
            .collect(java.util.stream.Collectors.toSet());
    List<String> unknown = mapping.keySet().stream().filter(k -> !declared.contains(k)).toList();
    check(unknown.isEmpty(), "映射里有该模板不存在的字段：" + String.join("、", unknown));
  }

  private Record activeJob(UUID tenantId, String entity) {
    return sql.fetchOne(
        "select * from import_job where target_entity=? and status in"
            + " ('PENDING','PREVALIDATING','PREVALIDATED','RUNNING') order by created_at desc limit 1",
        entity);
  }

  static ImportJobRow jobRow(Record r) {
    return new ImportJobRow(
        r.get("id", UUID.class),
        r.get("template_id", UUID.class),
        r.get("template_name", String.class),
        r.get("target_entity", String.class),
        r.get("import_mode", String.class),
        r.get("file_name", String.class),
        r.get("file_format", String.class),
        r.get("byte_size", java.math.BigDecimal.class),
        r.get("content_sha256", String.class),
        r.get("row_count", Integer.class),
        ImportTemplateService.readJson(r.get("mapping")),
        r.get("status", String.class),
        r.get("valid_count", Integer.class),
        r.get("invalid_count", Integer.class),
        r.get("applied_count", Integer.class),
        r.get("failed_count", Integer.class),
        r.get("skipped_count", Integer.class),
        r.get("error", String.class),
        r.get("actor_id", UUID.class),
        ImportTemplateService.instant(r, "prevalidated_at"),
        ImportTemplateService.instant(r, "started_at"),
        ImportTemplateService.instant(r, "finished_at"),
        ImportTemplateService.instant(r, "created_at"),
        ImportTemplateService.instant(r, "updated_at"));
  }

  static String statusLabel(String status) {
    return switch (status) {
      case "PENDING" -> "已上传";
      case "PREVALIDATING" -> "预校验中";
      case "PREVALIDATED" -> "预校验完成";
      case "RUNNING" -> "导入中";
      case "SUCCEEDED" -> "全部成功";
      case "PARTIAL" -> "部分成功";
      case "FAILED" -> "导入失败";
      case "CANCELLED" -> "已取消";
      default -> status;
    };
  }

  static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}
