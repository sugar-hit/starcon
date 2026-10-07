package com.starcon.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 3.8.1.1 导入向导的传输对象。
 *
 * <p>五个环节各自对应的形状都在这里，各环节的**返回物**刻意不同：
 *
 * <ul>
 *   <li>下载模板 → 模板字典（{@link ImportTemplateRow} / {@link ImportTemplateDetail}）+ CSV 字节；
 *   <li>上传文件 → {@link ImportJobRow}（任务已受理，**不是**「导入完成」）；
 *   <li>字段映射 → {@link ImportJobRow}（映射快照换了，任务还没动）；
 *   <li>预校验 → {@link ImportJobRow}（带四段计数）+ {@link ImportRowErrorPage}（逐行逐列原因）；
 *   <li>执行 → {@link ImportJobRow}（带 applied/failed/skipped）。
 * </ul>
 *
 * <p><b>预校验与执行返回的都是任务行而不是「成功/失败」一句话</b>：
 * 蓝图把「结果统计」列为输出，而 3.8.1.2 要在这份统计上做校验报告。
 * 统计是库里可查的事实，页面只读它，不在前端重算。
 */
final class ImportWizardModels {
  private ImportWizardModels() {}

  // ================================================================ 模板

  /** 模板字典的一行（不含列，列表页用）。 */
  public record ImportTemplateRow(
      UUID id,
      String code,
      String name,
      String note,
      String targetEntity,
      boolean enabled,
      int columnCount,
      long version,
      Instant createdAt,
      Instant updatedAt) {}

  /** 模板详情：字典行 + 列定义。下载模板与字段映射都从这一份形状出。 */
  public record ImportTemplateDetail(
      ImportTemplateRow template, List<ImportTemplateColumn> columns) {}

  /** 模板列表。 */
  public record ImportTemplatePage(List<ImportTemplateRow> items, int total) {}


  /**
   * 模板的一列。
   *
   * @param fieldKey 程序内稳定标识，映射与校验只认它（改列头不影响已存映射）
   * @param header 给运营看的列头文字，可改
   * @param matchKey 是否是「按编码匹配」的匹配键，同一模板至多一个
   */
  public record ImportTemplateColumn(
      String fieldKey,
      String header,
      String dataType,
      boolean required,
      boolean matchKey,
      String sampleValue,
      String help,
      int ordinal) {}

  /** 新建/更新模板。 */
  public record ImportTemplateInput(
      @NotBlank @Size(max = 64) String code,
      @NotBlank @Size(max = 100) String name,
      @Size(max = 2000) String note,
      @NotBlank String targetEntity,
      Boolean enabled,
      @NotNull @Valid List<ImportTemplateColumnInput> columns) {}

  /** 模板列输入。ordinal 为空时按数组顺序补齐。 */
  public record ImportTemplateColumnInput(
      @NotBlank @Size(max = 64) String fieldKey,
      @NotBlank @Size(max = 100) String header,
      @NotBlank String dataType,
      Boolean required,
      Boolean matchKey,
      @Size(max = 200) String sampleValue,
      @Size(max = 500) String help,
      Integer ordinal) {}

  // ================================================================ 字段映射

  /** 可复用的字段映射配置。 */
  public record ImportFieldMappingRow(
      UUID id,
      UUID templateId,
      String name,
      Map<String, String> mapping,
      String note,
      long version,
      Instant createdAt,
      Instant updatedAt) {}

  /** 字段映射列表。 */
  public record ImportFieldMappingPage(List<ImportFieldMappingRow> items, int total) {}

  public record ImportFieldMappingInput(
      @NotNull UUID templateId,
      @NotBlank @Size(max = 100) String name,
      @NotNull Map<String, String> mapping,
      @Size(max = 2000) String note) {}

  // ================================================================ 导入任务

  /**
   * 导入任务行 = 「导入任务 + 结果统计」。
   *
   * <p>四段计数（valid/invalid/applied/failed/skipped）是蓝图点名的输出，页面上的进度条、
   * 成功失败数字全部读它。任务还在跑时它们是**已推进的进度**，不是最终结论 ——
   * {@link #status} 才是结论。
   */
  public record ImportJobRow(
      UUID id,
      UUID templateId,
      String templateName,
      String targetEntity,
      String importMode,
      String fileName,
      String fileFormat,
      BigDecimal byteSize,
      String contentSha256,
      int rowCount,
      Map<String, String> mapping,
      String status,
      int validCount,
      int invalidCount,
      int appliedCount,
      int failedCount,
      int skippedCount,
      String error,
      UUID actorId,
      Instant prevalidatedAt,
      Instant startedAt,
      Instant finishedAt,
      Instant createdAt,
      Instant updatedAt) {}

  /** 任务列表（分页）。 */
  public record ImportJobPage(List<ImportJobRow> items, int page, int size, long total) {}

  /**
   * 逐行结果。
   *
   * @param rowNo 文件里的物理行号（表头=1），运营在 Excel 里数到的就是它
   * @param values 归一化后的值 {field_key: 原始文本}
   * @param spuId / skuId 执行成功后回填，让「这行变成了哪条记录」可追溯
   */
  public record ImportJobRowLine(
      UUID id,
      int rowNo,
      String outcome,
      int errorCount,
      Map<String, String> values,
      UUID spuId,
      UUID skuId) {}

  public record ImportJobRowPage(List<ImportJobRowLine> items, int page, int size, long total) {}

  /**
   * 逐行错误明细 = 本模块的**预校验交付物**。
   *
   * <p>{@link #severity} 是 ERROR 还是 WARNING 直接决定该行落不落库，
   * 所以它是数据而不是界面文案。
   */
  public record ImportRowErrorLine(
      UUID id, int rowNo, String fieldKey, String code, String message, String severity) {}

  public record ImportRowErrorPage(
      List<ImportRowErrorLine> items, int page, int size, long total, int errorCount, int warningCount) {}

  /** 任务详情：任务行 + 当前筛选下的逐行结果。 */
  public record ImportJobDetail(ImportJobRow job, ImportJobRowPage rows) {}

  /** 预校验提交体：可选把这次的映射存成可复用配置。 */
  public record ImportMappingInput(
      @NotNull Map<String, String> mapping, @Size(max = 100) String saveAs) {}

  /** 上传文件的统计回执（解析结果摘要）。 */
  public record ImportUploadSummary(
      UUID jobId, String fileName, BigDecimal byteSize, String contentSha256, int rowCount, int columnCount, List<String> headers) {}

  /** 模板 CSV 下载的元信息（前端用同一个函数拼下载链接）。 */
  public record ImportTemplateFile(String fileName, String contentType, byte[] content) {}
}
