package com.starcon.catalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 3.8.1.2 校验报告的传输对象。
 *
 * <p>蓝图的输出是「<b>错误明细表 + 可下载的修正版模板</b>」，所以这里的形状围绕两件事：
 *
 * <ul>
 *   <li>{@link ImportValidationSummary} —— 报告总览：四段行数（三段分「必须修正 / 可跳过 / 校验通过」 +
 *       任务总行数）、按蓝图五类分的统计、按字段列分的统计。 「有多少行真能落库」在这里被分成两个数而不是一个数，因为运营要决定的是 「先改哪一类」和「改完还剩多少」，不是「成没成功」。
 *   <li>{@link ImportValidationIssueLine} —— 错误明细表的一行，带原始单元格文本与行结论。
 * </ul>
 *
 * <p><b>报告带 {@code stale} 标记</b>：报告是一轮预校验结论的快照（见 V34 文件头）， 任务重跑预校验后结论会变，而页面上挂着的那份报告不会自己变。 {@code
 * stale=true} 就是「你看到的结论已经不是这一批数据当前的结论了」， 页面必须把它显示出来而不是默默展示旧数字。
 */
final class ImportValidationModels {
  private ImportValidationModels() {}

  // ================================================================ 报告

  /**
   * 报告快照行。
   *
   * @param totalRowCount 任务的总数据行数（表头不计）
   * @param blockingRowCount 必须修正的行：有 ERROR，<b>执行阶段永不落库</b>
   * @param skippableRowCount 可跳过的行：只有 WARNING，行照常落库
   * @param cleanRowCount 没有任何问题的行
   * @param stale 报告基于的预校验时刻与任务当前的不一致 → 这份报告已过期
   */
  public record ImportValidationReportRow(
      UUID id,
      UUID jobId,
      Instant sourcePrevalidatedAt,
      String sourceStatus,
      String sourceStatusLabel,
      int totalRowCount,
      int blockingRowCount,
      int skippableRowCount,
      int cleanRowCount,
      int issueRowCount,
      int errorCount,
      int warningCount,
      boolean stale,
      Instant generatedAt,
      UUID actorId) {}

  /**
   * 一类问题的统计。
   *
   * @param rowCount 命中这一类的**去重行数**（同一行两个格式错误只算一行）
   * @param errorCount / warningCount 条数。同样的问题 100 行 × 2 个字段 = 200 条但只有 100 行，
   *     两个数都要给：运营按条数估工作量，按行数估要改多少行。
   */
  public record ImportValidationCategoryStat(
      String category, String label, int rowCount, int errorCount, int warningCount) {}

  /**
   * 一列的统计：哪一列错得最多。
   *
   * <p>字段标签给的是**文件里的列头文字**（{@code fieldLabel}）而不是模板列头 —— 运营在自己的文件里看到的是前者，两者可能已经不一致（模板列头可改，文件不会跟着改）。
   */
  public record ImportValidationColumnStat(
      String fieldKey, String fieldLabel, int rowCount, int errorCount, int warningCount) {}

  /** 报告总览：报告行 + 两组分组统计。 */
  public record ImportValidationSummary(
      ImportValidationReportRow report,
      List<ImportValidationCategoryStat> categories,
      List<ImportValidationColumnStat> columns) {}

  // ================================================================ 错误明细

  /**
   * 错误明细表的一行。
   *
   * @param fieldKey 出错字段键；空串表示整行级问题
   * @param rawValue 该格在文件里的**原始文本** —— 没有它，第 47 行原来填了什么只能回原文件里找
   * @param rowOutcome 生成报告时这一行的逐行结论（VALID / INVALID / IMPORTED / SKIPPED / FAILED， 与 V29 的 {@code
   *     import_job_row.outcome} 同一词表）
   * @param rowBlocking 这一行是否**永不落库**。这是行级事实（条级 severity 归并上来的结果）， 报告页与导出文件里的「必须修正 / 是否落库」都读它
   */
  public record ImportValidationIssueLine(
      UUID id,
      UUID reportId,
      int rowNo,
      String fieldKey,
      String fieldLabel,
      String code,
      String category,
      String categoryLabel,
      String severity,
      String message,
      String rawValue,
      String rowOutcome,
      String rowOutcomeLabel,
      boolean rowBlocking) {}

  /**
   * 错误明细分页。
   *
   * @param errorCount / warningCount 是**当前报告全量**的条数（不受本页分页与筛选影响）， 页面顶部的「必须修正 N 条 / 可跳过 M 条」读的是这两个数。
   */
  public record ImportValidationIssuePage(
      List<ImportValidationIssueLine> items,
      int page,
      int size,
      long total,
      int errorCount,
      int warningCount) {}

  // ================================================================ 导出

  /**
   * 一次导出的登记。
   *
   * @param exportKind ANNOTATED_ERRORS=带标注的错误文件 / FIXED_TEMPLATE=修正版模板
   * @param byteSize 字节数（BigDecimal + NUMERIC(19,6)，不存 bytea）
   * @param contentSha256 内容哈希：现算导出，所以同一个报告导两次的哈希必须一样， 这个数让「下载到的和登记的是同一份」可验证
   * @param stale 登记时的报告已过期 —— 拿它导出的文件对应的是上一轮结论
   */
  public record ImportValidationExportRow(
      UUID id,
      UUID jobId,
      UUID reportId,
      String exportKind,
      String exportKindLabel,
      String severityFilter,
      int rowCount,
      int issueCount,
      BigDecimal byteSize,
      String contentSha256,
      String fileName,
      boolean stale,
      UUID actorId,
      Instant createdAt) {}

  public record ImportValidationExportPage(List<ImportValidationExportRow> items, int total) {}

  /** 导出的提交体：导出哪一份、按哪一档问题筛。 */
  public record ImportValidationExportInput(
      @NotBlank String exportKind, @Size(max = 16) String severityFilter) {}

  /** 下载用的字节 + 元信息（与 3.8.1.1 的 {@code ImportTemplateFile} 同一形状）。 */
  public record ImportValidationExportFile(String fileName, String contentType, byte[] content) {}
}
