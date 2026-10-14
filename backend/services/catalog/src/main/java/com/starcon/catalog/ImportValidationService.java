package com.starcon.catalog;

import static com.starcon.catalog.ImportValidationModels.*;

import com.starcon.tenancy.Ids;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;

/**
 * 3.8.1.2 校验报告：把一个导入任务当前那一轮预校验结论，固化成一份可查询、可导出的报告。
 *
 * <p><b>本服务不校验任何东西。</b>校验规则是 3.8.1.1 的 {@link ImportRules}， 唯一写入所有者是 {@code
 * ImportJobExecutor}。这里做的是报告层的四件事：
 *
 * <ol>
 *   <li><b>固化</b>：把 {@code import_row_error} 的<b>条级</b>问题归并成 「条 + 原始值 + 行结论」的明细，并统计出「必须修正 / 可跳过 /
 *       校验通过」三段行数。 归并的必要性在于：落不落库是<b>行级</b>事实（同一行 2 条 WARNING + 1 条 ERROR 仍然不落库）， 而 {@code
 *       import_row_error} 是条级的，单看一条答不出「这一行能不能进」。
 *   <li><b>分类</b>：按蓝图点名的五类（必填缺失 / 格式错误 / 重复编码 / 引用不存在 / 唯一冲突） 分组，让运营按类批量改。
 *   <li><b>过期可辨</b>：报告是快照，而上游预校验可重跑且重跑会覆盖结论。 所以每次读报告都比一次 {@code import_job.updated_at} 与报告的 {@code
 *       generated_at}， 报告晚于任务最后一次写入就标 {@code stale} —— 宁可提示重新生成，也不给过期数字。
 *   <li><b>导出</b>：带标注的错误文件与修正版模板（同一段代码、同一个字节生成器）。
 * </ol>
 *
 * <p><b>租户</b>：本服务所有 SQL 都带 {@code tenant_id} 显式条件，并依赖 V34 的 ENABLE + FORCE RLS 双保险；租户由 {@code
 * AccessService} 从已认证成员关系给出， 客户端的 {@code X-Tenant-Id} 头在这条链路上没有任何位置。
 */
@Service
public class ImportValidationService {
  private final DSLContext sql;
  private final ImportJobService jobs;

  public ImportValidationService(DSLContext sql, ImportJobService jobs) {
    this.sql = sql;
    this.jobs = jobs;
  }

  /** 报告快照 + 定位它所需的租户/任务 id。 */
  private record Snapshot(
      UUID tenantId, UUID jobId, UUID reportId, ImportValidationReportRow report) {}

  // ================================================================ 报告总览

  /**
   * 报告总览。
   *
   * <p><b>第一次读会生成，之后再读只返回快照</b>：没有报告时当场生成（客户端永远拿得到 200， 不用先吃一个 404）；已有报告时<b>原样返回</b>，过期只把 {@code
   * stale} 标出来， 不在这里悄悄重算 —— 理由见 {@link #materialize}。
   */
  public ImportValidationSummary summary(UUID tenantId, UUID jobId, UUID actorId) {
    var snapshot = materialize(tenantId, jobId, false, actorId);
    return new ImportValidationSummary(
        snapshot.report(), categoryStats(snapshot), columnStats(snapshot));
  }

  /** 重新生成报告（改了映射或补了规则之后主动刷新）。 */
  public ImportValidationReportRow regenerate(UUID tenantId, UUID jobId, UUID actorId) {
    return materialize(tenantId, jobId, true, actorId).report();
  }

  /**
   * 固化（或复用）一份报告。
   *
   * <p><b>「第一次读会生成，之后再读只返回快照 + 过期标记」</b>。这个分界是本方法的核心： 若每次读都顺手重算，{@code stale} 就永远是 false（重算完当然是新的），
   * 于是报告页没有机会告诉用户「你正在看的这份已经不是这批数据当前的结论了」—— 而那正是快照要解决的唯一问题。所以：
   *
   * <ul>
   *   <li>没有报告 → 当场生成（省掉一次 404 + 一次重试，客户端永远拿得到报告）；
   *   <li>有报告 → <b>原样返回</b>，只把 {@code stale} 标出来，是否重算交给显式的 {@link
   *       #regenerate}。报告正在被人逐条核对，中途换掉它比让它显示「已过期」危险得多。
   * </ul>
   *
   * @param force 为 true 时无条件重算（忽略已有报告）
   */
  private Snapshot materialize(UUID tenantId, UUID jobId, boolean force, UUID actorId) {
    // 先读任务：不属于本租户时这里就 404，报告不存在于此之前 —— 租户隔离的第一道门。
    var job = jobs.job(tenantId, jobId);
    check(
        job.prevalidatedAt() != null,
        "任务「" + job.fileName() + "」还没有预校验结论，暂时没有校验报告。" + "请先在导入向导里跑一次预校验；校验报告读的是预校验结果，不是上传的文件");

    var existing =
        sql.fetchOne(
            "select * from import_validation_report where tenant_id=? and job_id=?",
            tenantId,
            jobId);
    if (!force && existing != null)
      return new Snapshot(
          tenantId,
          jobId,
          existing.get("id", UUID.class),
          reportRow(
              existing,
              isStale(job.updatedAt(), ImportTemplateService.instant(existing, "generated_at"))));

    var columns = jobs.columns(tenantId, job.templateId());
    Map<String, String> mapping = job.mapping();

    // 条级错误按行号归组：行是报告的最小单位（导出也是逐行给标注）。
    var errorsByRow = new LinkedHashMap<Integer, List<Record>>();
    for (var e :
        sql.fetch(
            "select row_no,field_key,code,message,severity from import_row_error"
                + " where tenant_id=? and job_id=? order by row_no,field_key,code",
            tenantId,
            jobId))
      errorsByRow.computeIfAbsent(e.get("row_no", Integer.class), k -> new ArrayList<>()).add(e);

    var rows =
        sql.fetch(
            "select row_no,outcome,cells from import_job_row where tenant_id=? and job_id=? order by row_no",
            tenantId,
            jobId);
    int blocking = 0, skippable = 0, clean = 0, errorCount = 0, warningCount = 0;
    var issues = new ArrayList<Object[]>(rows.size());
    for (var row : rows) {
      int rowNo = row.get("row_no", Integer.class);
      var rowErrors = errorsByRow.get(rowNo);
      List<ImportValidationRules.Issue> rowIssues =
          rowErrors == null
              ? List.of()
              : toIssues(
                  rowErrors, mapping, columns, ImportTemplateService.readJson(row.get("cells")));
      var conclusion = ImportValidationRules.conclude(rowIssues);
      if (conclusion == ImportValidationRules.RowConclusion.CLEAN) clean++;
      else if (conclusion == ImportValidationRules.RowConclusion.BLOCKING) blocking++;
      else skippable++;
      boolean rowBlocking = conclusion == ImportValidationRules.RowConclusion.BLOCKING;
      for (var issue : rowIssues) {
        errorCount += ImportValidationRules.blocking(issue.severity()) ? 1 : 0;
        warningCount += ImportValidationRules.blocking(issue.severity()) ? 0 : 1;
        issues.add(
            new Object[] {
              Ids.next(),
              tenantId,
              null /*report_id 稍后回填*/,
              jobId,
              rowNo,
              issue.category(),
              issue.fieldKey(),
              issue.fieldLabel(),
              issue.code(),
              issue.severity(),
              issue.message(),
              issue.rawValue(),
              row.get("outcome", String.class),
              rowBlocking
            });
      }
    }
    // 指向不存在的行的错误不能被悄悄丢掉：那就意味着「有一行不落库」这件事
    // 在报告里查不到行号，而报告的全部意义就是定位到行。
    var rowNos = new java.util.HashSet<Integer>();
    for (var r : rows) rowNos.add(r.get("row_no", Integer.class));
    var orphans = errorsByRow.keySet().stream().filter(n -> !rowNos.contains(n)).sorted().toList();
    check(
        orphans.isEmpty(),
        "预校验结果里有 "
            + orphans.size()
            + " 行的错误指向不存在的行（行号 "
            + orphans
            + "）。这是预校验侧的数据不一致，请重跑一次预校验后再生成报告");

    UUID reportId = existing == null ? Ids.next() : existing.get("id", UUID.class);
    sql.execute(
        "insert into import_validation_report(id,tenant_id,job_id,source_prevalidated_at,source_status,"
            + "total_row_count,blocking_row_count,skippable_row_count,clean_row_count,issue_row_count,"
            + "error_count,warning_count,generated_at,actor_id)"
            + " values(?,?,?,?::timestamptz,?,?,?,?,?,?,?,?,now(),?)"
            + " on conflict (tenant_id,job_id) do update set"
            + " source_prevalidated_at=excluded.source_prevalidated_at,source_status=excluded.source_status,"
            + " total_row_count=excluded.total_row_count,blocking_row_count=excluded.blocking_row_count,"
            + " skippable_row_count=excluded.skippable_row_count,clean_row_count=excluded.clean_row_count,"
            + " issue_row_count=excluded.issue_row_count,error_count=excluded.error_count,"
            + " warning_count=excluded.warning_count,generated_at=now(),actor_id=excluded.actor_id",
        reportId,
        tenantId,
        jobId,
        job.prevalidatedAt(),
        job.status(),
        rows.size(),
        blocking,
        skippable,
        clean,
        blocking + skippable,
        errorCount,
        warningCount,
        actorId);

    // 明细整批重写：报告是一次快照，重算就是「换成这一轮的结论」而不是「往里追加」。
    sql.execute(
        "delete from import_validation_issue where tenant_id=? and report_id=?",
        tenantId,
        reportId);
    for (var binding : issues) {
      binding[2] = reportId;
      sql.execute(
          "insert into import_validation_issue(id,tenant_id,report_id,job_id,row_no,category,field_key,"
              + "field_label,code,severity,message,raw_value,row_outcome,row_blocking)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          binding);
    }

    var stored =
        sql.fetchOne(
            "select * from import_validation_report where tenant_id=? and job_id=?",
            tenantId,
            jobId);
    return new Snapshot(tenantId, jobId, reportId, reportRow(stored, false));
  }

  /**
   * 把一批条级错误翻译成带字段名与原始值的报告明细。
   *
   * <p><b>字段名优先用文件里的列头</b>而不是模板列头：模板列头随时可改， 而运营在自己的文件里看到的是上传时那份列头，标注必须指着他看得见的那个名字。 映射缺失时（{@code
   * UNMAPPED_COLUMN}）退回模板列头 —— 那正是运营要去字段映射里选的列。
   */
  private List<ImportValidationRules.Issue> toIssues(
      List<Record> rowErrors,
      Map<String, String> mapping,
      List<ImportWizardModels.ImportTemplateColumn> columns,
      Map<String, String> cells) {
    List<ImportValidationRules.Issue> out = new ArrayList<>(rowErrors.size());
    for (var e : rowErrors) {
      String fieldKey = e.get("field_key", String.class);
      String code = e.get("code", String.class);
      var category = ImportValidationRules.categoryOf(code);
      // 未知码不兜底：见 ImportValidationRules 类注释。
      // 归类缺口在这里必须炸出来 —— 悄悄落进某一类会让运营去改一个不该改的地方。
      if (category == null)
        throw new IllegalStateException(
            "预校验产生了尚未归类的错误码「"
                + code
                + "」（行 "
                + e.get("row_no", Integer.class)
                + "）。请在 ImportValidationRules 里为它指定分类，否则校验报告无法给出可信的分类统计");
      String header = fieldKey.isBlank() ? null : mapping.get(fieldKey);
      String label =
          fieldKey.isBlank()
              ? ""
              : header != null
                  ? header
                  : columns.stream()
                      .filter(c -> c.fieldKey().equals(fieldKey))
                      .findFirst()
                      .map(ImportWizardModels.ImportTemplateColumn::header)
                      .orElse("");
      check(!fieldKey.isBlank() || label.isEmpty(), "整行级问题不应带字段名：" + code);
      String raw = header == null ? "" : cells.getOrDefault(header, "");
      out.add(
          new ImportValidationRules.Issue(
              e.get("row_no", Integer.class),
              fieldKey,
              label,
              code,
              category.name(),
              e.get("severity", String.class),
              e.get("message", String.class),
              raw.length() > 300 ? raw.substring(0, 300) : raw));
    }
    return out;
  }

  /**
   * 报告是否过期：任务在报告生成之后又被写过。
   *
   * <p>用「谁更晚」而不是「两轮预校验的 id 或时刻」——V29 的触发器只在 {@code prevalidated_at IS NULL} 时写那个列，重跑预校验不会更新它，
   * 所以拿它比永远得出「没变过」。{@code updated_at} 每次 update 都刷（同一个触发器）， 于是「报告之后任务又变了」这个事实一定被看见。
   */
  private static boolean isStale(java.time.Instant jobUpdatedAt, java.time.Instant generatedAt) {
    return jobUpdatedAt != null && generatedAt != null && jobUpdatedAt.isAfter(generatedAt);
  }

  private static void check(boolean condition, String message) {
    CatalogService.check(condition, message);
  }

  // ================================================================ 错误明细

  /**
   * 错误明细表（蓝图的「输出」之一）。
   *
   * @param severity 可选：ERROR（只看必须修正）/ WARNING（只看可跳过）
   * @param category 可选：按蓝图五类之一收窄
   * @param rowNo 可选：定位到具体一行（「第 47 行到底错在哪」）
   */
  public ImportValidationIssuePage issues(
      UUID tenantId,
      UUID jobId,
      UUID actorId,
      String severity,
      String category,
      Integer rowNo,
      int page,
      int size) {
    var snapshot = materialize(tenantId, jobId, false, actorId);
    StringBuilder where = new StringBuilder(" where tenant_id=? and report_id=?");
    List<Object> args = new ArrayList<>();
    args.add(tenantId);
    args.add(snapshot.reportId());
    if (severity != null && !severity.isBlank()) {
      where.append(" and severity=?");
      args.add(ImportValidationRules.blocking(severity) ? "ERROR" : "WARNING");
    }
    if (category != null && !category.isBlank()) {
      check(
          knownCategory(category),
          "未知的错误类别「" + category + "」。可用：" + String.join(" / ", categoryLabels()));
      where.append(" and category=?");
      args.add(ImportValidationRules.categoryByName(category).name());
    }
    if (rowNo != null) {
      check(rowNo > 1, "行号从 2 开始（第 1 行是表头）");
      where.append(" and row_no=?");
      args.add(rowNo);
    }
    long total =
        sql.fetchOne("select count(*) from import_validation_issue" + where, args.toArray())
            .get(0, Long.class);
    var counts =
        sql.fetchOne(
            "select count(*) filter (where severity='ERROR') as errors,"
                + "count(*) filter (where severity='WARNING') as warnings"
                + " from import_validation_issue where tenant_id=? and report_id=?",
            tenantId,
            snapshot.reportId());
    int limit = Math.min(Math.max(size, 1), 200);
    int offset = Math.max(page, 0) * limit;
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(limit);
    pageArgs.add(offset);
    var fetched =
        sql.fetch(
            "select * from import_validation_issue"
                + where
                + " order by row_no,field_key,code limit ? offset ?",
            pageArgs.toArray());
    return new ImportValidationIssuePage(
        fetched.map(
            r ->
                new ImportValidationIssueLine(
                    r.get("id", UUID.class),
                    r.get("report_id", UUID.class),
                    r.get("row_no", Integer.class),
                    r.get("field_key", String.class),
                    r.get("field_label", String.class),
                    r.get("code", String.class),
                    r.get("category", String.class),
                    ImportValidationRules.categoryByName(r.get("category", String.class)).label(),
                    r.get("severity", String.class),
                    r.get("message", String.class),
                    r.get("raw_value", String.class),
                    r.get("row_outcome", String.class),
                    ImportValidationRules.outcomeLabel(r.get("row_outcome", String.class)),
                    r.get("row_blocking", Boolean.class))),
        Math.max(page, 0),
        limit,
        total,
        counts.get("errors", Integer.class),
        counts.get("warnings", Integer.class));
  }

  // ================================================================ 分类统计

  /**
   * 按蓝图点名的类别统计。
   *
   * <p><b>六类全给（含 0）</b>：报告页要能回答「唯一冲突这一类我有没有踩」， 只列非空的类会让「0」与「没检查过」看起来一模一样。
   */
  private List<ImportValidationCategoryStat> categoryStats(Snapshot snapshot) {
    var counts = new LinkedHashMap<String, Record>();
    for (var r :
        sql.fetch(
            "select category,count(distinct row_no) as rows_count,"
                + "count(*) filter (where severity='ERROR') as errors,"
                + "count(*) filter (where severity='WARNING') as warnings"
                + " from import_validation_issue where tenant_id=? and report_id=? group by category",
            snapshot.tenantId(),
            snapshot.reportId())) counts.put(r.get("category", String.class), r);
    List<ImportValidationCategoryStat> out = new ArrayList<>();
    for (var category : ImportValidationRules.Category.values()) {
      var r = counts.get(category.name());
      out.add(
          new ImportValidationCategoryStat(
              category.name(),
              category.label(),
              r == null ? 0 : r.get("rows_count", Integer.class),
              r == null ? 0 : r.get("errors", Integer.class),
              r == null ? 0 : r.get("warnings", Integer.class)));
    }
    return out;
  }

  /**
   * 按字段列统计：哪一列错得最多。
   *
   * <p>{@code field_key=''} 是整行级问题（编码重复落在整行上）， 归到「整行」一栏 —— 把它混进某个字段列会让那一列看起来错得比实际更多。
   */
  private List<ImportValidationColumnStat> columnStats(Snapshot snapshot) {
    return sql.fetch(
            "select field_key,max(field_label) as field_label,count(distinct row_no) as rows_count,"
                + "count(*) filter (where severity='ERROR') as errors,"
                + "count(*) filter (where severity='WARNING') as warnings"
                + " from import_validation_issue where tenant_id=? and report_id=? group by field_key"
                + " order by errors desc,warnings desc,field_key",
            snapshot.tenantId(),
            snapshot.reportId())
        .map(
            r ->
                new ImportValidationColumnStat(
                    r.get("field_key", String.class),
                    r.get("field_key", String.class).isBlank()
                        ? "整行"
                        : r.get("field_label", String.class),
                    r.get("rows_count", Integer.class),
                    r.get("errors", Integer.class),
                    r.get("warnings", Integer.class)));
  }

  private static boolean knownCategory(String category) {
    try {
      ImportValidationRules.categoryByName(category);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static List<String> categoryLabels() {
    List<String> out = new ArrayList<>();
    for (var c : ImportValidationRules.Category.values()) out.add(c.name() + "（" + c.label() + "）");
    return out;
  }

  // ================================================================ 导出

  /** 导出登记列表（按时间倒序）。 */
  public ImportValidationExportPage exports(UUID tenantId, UUID jobId, UUID actorId) {
    var snapshot = materialize(tenantId, jobId, false, actorId);
    var items =
        sql.fetch(
                "select * from import_validation_export where tenant_id=? and job_id=?"
                    + " order by created_at desc,id desc",
                tenantId,
                jobId)
            .map(r -> exportRow(r, snapshot.report().stale()));
    return new ImportValidationExportPage(items, items.size());
  }

  /**
   * 生成一次导出（登记，不返回字节）。
   *
   * <p><b>报告过期时拒绝导出（409），不静默重新生成。</b>报告页正在被人用来逐条核对， 导出发生在核对的中途，此时悄悄换一份新结论会让「我看到的」与「我拿到的」不再是同一批数据。
   * 所以这里明确拒绝并给出该做的动作，页面上点「重新生成报告」即可。
   */
  public ImportValidationExportRow createExport(
      UUID tenantId, UUID jobId, ImportValidationExportInput input, UUID actorId) {
    var snapshot = materialize(tenantId, jobId, false, actorId);
    check(!snapshot.report().stale(), "这份校验报告已经过期（生成之后任务又有过变动），导出的标注会对应上一轮结论。" + "请先重新生成报告再导出");
    String kind = input.exportKind().trim().toUpperCase(Locale.ROOT);
    check(
        ImportValidationRules.KIND_ANNOTATED.equals(kind)
            || ImportValidationRules.KIND_FIXED_TEMPLATE.equals(kind),
        "未知的导出种类「"
            + input.exportKind()
            + "」。可用："
            + ImportValidationRules.KIND_ANNOTATED
            + "（带标注的错误文件）/ "
            + ImportValidationRules.KIND_FIXED_TEMPLATE
            + "（修正版模板）");
    String filter =
        input.severityFilter() == null || input.severityFilter().isBlank()
            ? "ALL"
            : input.severityFilter().trim().toUpperCase(Locale.ROOT);
    check(
        ImportValidationRules.SEVERITY_FILTERS.contains(filter),
        "未知的严重级别筛选「" + input.severityFilter() + "」。可用：ALL / ERROR / WARNING");

    var content = buildContent(snapshot, kind, filter);
    check(
        ImportValidationRules.KIND_FIXED_TEMPLATE.equals(kind) ? content.rowCount() > 0 : true,
        "这份报告里没有需要修正的行（没有 ERROR 也没有 WARNING），不需要下载修正版模板。" + "若想看全部数据行，请导出「带标注的错误文件」");
    UUID id = Ids.next();
    sql.execute(
        "insert into import_validation_export(id,tenant_id,job_id,report_id,export_kind,severity_filter,"
            + "row_count,issue_count,byte_size,content_sha256,file_name,actor_id)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?)",
        id,
        tenantId,
        jobId,
        snapshot.reportId(),
        kind,
        filter,
        content.rowCount(),
        content.issueCount(),
        java.math.BigDecimal.valueOf(content.content().length),
        ImportCsvParser.sha256(content.content()),
        content.fileName(),
        actorId);
    return exportRow(sql.fetchOne("select * from import_validation_export where id=?", id), false);
  }

  /**
   * 下载导出文件。
   *
   * <p>字节**现算**：库里没有 CSV blob（与 V29「文件本体不落库」同一纪律）。 现算的代价是每次下载重跑一次装配，换来的好处是「同一个报告导两次字节一致」，
   * 所以下面那次哈希比对不是多余的装饰 —— 它是「内容确实只由报告决定」这条性质的运行时守卫。
   */
  public ImportValidationExportFile exportFile(
      UUID tenantId, UUID jobId, UUID exportId, UUID actorId) {
    var snapshot = materialize(tenantId, jobId, false, actorId);
    var export =
        sql.fetchOne(
            "select * from import_validation_export where tenant_id=? and job_id=? and id=?",
            tenantId,
            jobId,
            exportId);
    if (export == null) throw ImportJobService.notFound("导出记录不存在或已删除");
    var content =
        buildContent(
            snapshot,
            export.get("export_kind", String.class),
            export.get("severity_filter", String.class));
    String actual = ImportCsvParser.sha256(content.content());
    if (!actual.equals(export.get("content_sha256", String.class)))
      throw new IllegalStateException(
          "导出的内容与登记时的哈希不一致（登记 "
              + export.get("content_sha256", String.class)
              + "，现算 "
              + actual
              + "）。导出内容必须只由报告决定，否则同一份报告会导出两种文件");
    return new ImportValidationExportFile(
        export.get("file_name", String.class), "text/csv; charset=UTF-8", content.content());
  }

  /** 装配好的导出文件。 */
  private record ExportContent(byte[] content, int rowCount, int issueCount, String fileName) {}

  /**
   * 装配带标注的 CSV。
   *
   * <p><b>数据列按模板列序</b>而不是文件原始列序，原因见 {@link ImportValidationRules#exportHeaders}：文件原始列序在库里不可复原（jsonb
   * 不保留键序）， 模板列序反而与「下载模板」一致。模板声明了但这次没映射到的列也会给出一列空值 —— 那正是 {@code UNMAPPED_COLUMN}
   * 那一类问题在文件里的样子：空着，且标注说明了为什么空。
   *
   * <p><b>标注列追加在数据列之后，文件仍然可以原样重新上传</b>： 导入只按映射读取模板声明的列（{@code ImportJobService.resolveValues}），
   * 多出来的标注列会被忽略。所以运营可以直接在这份带标注的文件上改，改完原样传回。
   */
  private ExportContent buildContent(Snapshot snapshot, String kind, String filter) {
    var job = jobs.job(snapshot.tenantId(), snapshot.jobId());
    var columns = jobs.columns(snapshot.tenantId(), job.templateId());
    Map<String, String> mapping = job.mapping();
    var headers = new ArrayList<String>();
    for (var c : columns) headers.add(c.header());
    var issuesByRow = new LinkedHashMap<Integer, List<ImportValidationRules.Issue>>();
    for (var r :
        sql.fetch(
            "select row_no,field_key,field_label,code,category,severity,message,raw_value"
                + " from import_validation_issue where tenant_id=? and report_id=? order by row_no",
            snapshot.tenantId(),
            snapshot.reportId()))
      issuesByRow
          .computeIfAbsent(r.get("row_no", Integer.class), k -> new ArrayList<>())
          .add(
              new ImportValidationRules.Issue(
                  r.get("row_no", Integer.class),
                  r.get("field_key", String.class),
                  r.get("field_label", String.class),
                  r.get("code", String.class),
                  r.get("category", String.class),
                  r.get("severity", String.class),
                  r.get("message", String.class),
                  r.get("raw_value", String.class)));

    boolean problemOnly = ImportValidationRules.problemOnly(kind);
    List<List<String>> out = new ArrayList<>();
    int issueCount = 0;
    for (var row :
        sql.fetch(
            "select row_no,cells from import_job_row where tenant_id=? and job_id=? order by row_no",
            snapshot.tenantId(),
            snapshot.jobId())) {
      int rowNo = row.get("row_no", Integer.class);
      var rowIssues = issuesByRow.getOrDefault(rowNo, List.of());
      var conclusion = ImportValidationRules.conclude(rowIssues);
      if (!ImportValidationRules.rowIncluded(conclusion, filter, problemOnly)) continue;
      out.add(
          ImportValidationRules.annotatedRow(
                  rowNo,
                  values(columns, mapping, ImportTemplateService.readJson(row.get("cells"))),
                  rowIssues)
              .toCsvRow());
      issueCount += rowIssues.size();
    }
    var code =
        sql.fetchOne(
                "select code from import_template where tenant_id=? and id=?",
                snapshot.tenantId(),
                job.templateId())
            .get("code", String.class);
    String suffix =
        ImportValidationRules.KIND_FIXED_TEMPLATE.equals(kind)
            ? "fixed-template"
            : "annotated-errors";
    String fileName =
        code + "-" + snapshot.jobId().toString().substring(0, 8) + "-" + suffix + ".csv";
    return new ExportContent(
        ImportCsvParser.write(ImportValidationRules.exportHeaders(headers), out),
        out.size(),
        issueCount,
        fileName);
  }

  /** 一行按模板列序的**原始文本**值（不 trim：导出的文件要能原样传回）。 */
  private static List<String> values(
      List<ImportWizardModels.ImportTemplateColumn> columns,
      Map<String, String> mapping,
      Map<String, String> cells) {
    List<String> out = new ArrayList<>(columns.size());
    for (var c : columns) {
      String header = mapping.get(c.fieldKey());
      out.add(header == null ? "" : cells.getOrDefault(header, ""));
    }
    return out;
  }

  // ================================================================ 行映射

  private static ImportValidationReportRow reportRow(Record r, boolean stale) {
    return new ImportValidationReportRow(
        r.get("id", UUID.class),
        r.get("job_id", UUID.class),
        ImportTemplateService.instant(r, "source_prevalidated_at"),
        r.get("source_status", String.class),
        ImportJobService.statusLabel(r.get("source_status", String.class)),
        r.get("total_row_count", Integer.class),
        r.get("blocking_row_count", Integer.class),
        r.get("skippable_row_count", Integer.class),
        r.get("clean_row_count", Integer.class),
        r.get("issue_row_count", Integer.class),
        r.get("error_count", Integer.class),
        r.get("warning_count", Integer.class),
        stale,
        ImportTemplateService.instant(r, "generated_at"),
        r.get("actor_id", UUID.class));
  }

  private static ImportValidationExportRow exportRow(Record r, boolean stale) {
    return new ImportValidationExportRow(
        r.get("id", UUID.class),
        r.get("job_id", UUID.class),
        r.get("report_id", UUID.class),
        r.get("export_kind", String.class),
        ImportValidationRules.kindLabel(r.get("export_kind", String.class)),
        r.get("severity_filter", String.class),
        r.get("row_count", Integer.class),
        r.get("issue_count", Integer.class),
        r.get("byte_size", java.math.BigDecimal.class),
        r.get("content_sha256", String.class),
        r.get("file_name", String.class),
        stale,
        r.get("actor_id", UUID.class),
        ImportTemplateService.instant(r, "created_at"));
  }
}
