package com.starcon.platform;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 18.1.3.1「导入导出任务」的模型层。
 *
 * <p>蓝图原文：「<b>职责</b>：统一的数据导入导出通道：任务创建（选择数据域与范围）、异步执行、
 * 进度跟踪、结果下载、失败行报告、模板下载。<b>输入</b>：导出/导入请求；文件上传。
 * <b>输出</b>：任务记录 + 文件（带有效期）+ 错误报告。<b>关系</b>：上游 = 全部业务域；
 * 下游 = 用户本地文件。<b>大文件一律异步</b>，并在下载链接上设置有效期，避免存储无限增长。」
 *
 * <p><b>五条纪律：</b>
 *
 * <ol>
 *   <li><b>导出与导入共用一张任务表</b>：两条链（任务创建 → 异步执行 → 进度跟踪 →
 *       结果下载 → 失败行报告）对两个方向<b>完全同构</b>，拆两张表会让状态机写两遍，
 *       而两遍实现必然漂移。方向由 {@code direction} 区分。
 *   <li><b>能力声明如实答</b>：没接通的数据域标 {@code available=false} 并写明缺什么、归谁 ——
 *       绝不为让测试绿而写成假的 {@code true}（照抄 {@code UnavailableFinanceSoftwarePushPort}）。
 *   <li><b>跨服务只走授权端口</b>：本模块<b>一条跨库 SQL 都没有</b>；属主服务的数据
 *       只经它自己的授权 API 读，且<b>只转发调用方自己的 bearer</b>（不替对方决定租户）。
 *   <li><b>文件内容落存储、不落库</b>：几十 MB 的业务数据落进 TOAST 会撑大库、
 *       拖慢备份与查询；库里只留元信息 + sha256 + 字节数。
 *   <li><b>时间一律 timestamptz</b>：服务层用 {@link OffsetDateTime} 绑定，
 *       values 位置写 {@code ?::timestamptz}（用 {@code Timestamp.valueOf(LocalDateTime)}
 *       会静默偏 −28800s）。
 * </ol>
 */
public final class ImportExportModels {

  private ImportExportModels() {}

  // ================================================================ 词表

  /** 方向：导出 / 导入。 */
  public enum Direction {
    EXPORT("导出"),
    IMPORT("导入");

    private final String label;

    Direction(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析（大小写不敏感）；不认识的值返回 {@code null}（由调用方翻成 400）。 */
    public static Direction parse(String raw) {
      if (raw == null) return null;
      for (var d : values()) {
        if (d.name().equalsIgnoreCase(raw.trim())) return d;
      }
      return null;
    }
  }

  /**
   * 任务状态。
   *
   * <p>状态机（PENDING → RUNNING → SUCCEEDED → EXPIRED，任意非终态可 CANCELLED / FAILED）
   * 由**数据库触发器**钉死（V415），不是只写在应用层 —— PostgreSQL 里 CHECK 可以被
   * {@code NOT VALID} 绕过，触发器不会。
   */
  public enum TaskStatus {
    PENDING("待执行"),
    RUNNING("执行中"),
    SUCCEEDED("已完成"),
    FAILED("已失败"),
    CANCELLED("已作废"),
    EXPIRED("已过期");

    private final String label;

    TaskStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 文件角色。 */
  public enum FileRole {
    RESULT("导出结果"),
    ERROR_REPORT("失败行报告"),
    SOURCE("上传源文件"),
    TEMPLATE("导入模板");

    private final String label;

    FileRole(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 失败行严重级别。 */
  public enum Severity {
    ERROR("错误"),
    WARNING("警告");

    private final String label;

    Severity(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 列定义

  /**
   * 一列。
   *
   * @param fieldKey 程序内稳定标识（解析与映射只认它，改列头不影响已存映射）
   * @param header 给运营看的列头文字
   * @param dataType 数据类型（STRING / NUMBER / DATE / DATETIME / BOOLEAN）
   * @param required 是否必填（导入模板用；导出列恒为 false）
   * @param sampleValue 示例值（导入模板的示例行用它）
   * @param help 填写说明
   */
  public record ImportExportColumn(
      String fieldKey,
      String header,
      String dataType,
      boolean required,
      String sampleValue,
      String help) {}

  // ================================================================ 任务

  /** 任务行 = 「任务记录 + 进度 + 结果文件指针」。 */
  public record ImportExportTaskRow(
      UUID id,
      String taskNo,
      String direction,
      String domainCode,
      String domainLabel,
      String format,
      String status,
      Map<String, Object> scope,
      int totalRows,
      int processedRows,
      int succeededRows,
      int failedRows,
      UUID resultFileId,
      UUID errorFileId,
      String upstreamService,
      String upstreamJobId,
      String upstreamJobRef,
      String error,
      UUID actorId,
      OffsetDateTime requestedAt,
      OffsetDateTime startedAt,
      OffsetDateTime finishedAt,
      OffsetDateTime expiresAt,
      OffsetDateTime createdAt) {}

  /** 任务列表（真分页）。 */
  public record ImportExportTaskPage(
      List<ImportExportTaskRow> items, int page, int size, long total) {}

  // ================================================================ 文件

  /** 文件元信息（**不含内容**：内容在存储里，按 storage_key 取）。 */
  public record ImportExportFileRow(
      UUID id,
      UUID taskId,
      String role,
      String filename,
      String contentType,
      String contentSha256,
      BigDecimal byteSize,
      int rowCount,
      OffsetDateTime expiresAt,
      OffsetDateTime createdAt) {}

  /** 下载用的一次性载荷。 */
  public record ImportExportFileContent(
      String filename, String contentType, byte[] content) {}

  // ================================================================ 失败行

  /** 一处失败（**逐行逐列**，不是一个错误字符串）。 */
  public record ImportExportRowErrorRow(
      UUID id,
      UUID taskId,
      int rowNo,
      String columnName,
      String rawValue,
      String errorCode,
      String message,
      String severity,
      OffsetDateTime createdAt) {}

  /** 失败行分页（附两类计数）。 */
  public record ImportExportRowErrorPage(
      List<ImportExportRowErrorRow> items,
      int page,
      int size,
      long total,
      int errorCount,
      int warningCount) {}

  // ================================================================ 模板

  /** 模板行（列表页用，不含列）。 */
  public record ImportExportTemplateRow(
      UUID id,
      String domainCode,
      String code,
      String name,
      boolean builtin,
      boolean enabled,
      String remark,
      OffsetDateTime createdAt) {}

  /** 模板详情 = 模板行 + 列定义 + 示例行。 */
  public record ImportExportTemplateDetail(
      ImportExportTemplateRow template,
      List<ImportExportColumn> columns,
      Map<String, String> sampleRow) {}

  /** 模板列表。 */
  public record ImportExportTemplatePage(
      List<ImportExportTemplateRow> items, int page, int size, long total) {}

  // ================================================================ 数据域目录

  /**
   * 一个数据域在某个方向上的可用性。
   *
   * @param code 数据域稳定标识
   * @param label 中文名
   * @param direction 方向（EXPORT / IMPORT）
   * @param available **是否真的接通**
   * @param source 数据来源（LOCAL_TABLE / AUTHORIZED_PORT / DELEGATED_JOB / NONE）
   * @param ownerService 属主服务
   * @param ownerModule 归属模块（不可用时写明「缺在谁身上」）
   * @param columns 这一格的列（导出列 / 导入列）
   * @param reason 不可用时的原因（接通时为空）
   * @param limitation 接通时的边界（上限、口径限制等）
   */
  public record ImportExportDomainRow(
      String code,
      String label,
      String direction,
      boolean available,
      String source,
      String ownerService,
      String ownerModule,
      List<ImportExportColumn> columns,
      String reason,
      String limitation) {}

  // ================================================================ 能力声明

  /** 能力声明的一格。 */
  public record ImportExportCapability(
      String module,
      String name,
      boolean available,
      String basis,
      String ownerModule,
      String limitation) {}

  /** 兄弟模块的接线状态。 */
  public record ImportExportGap(
      String module,
      String name,
      String ownerService,
      boolean available,
      String relation,
      String note) {}

  /** 能力声明整体。 */
  public record ImportExportCapabilities(
      List<ImportExportCapability> capabilities, List<ImportExportGap> gaps) {}

  // ================================================================ 概览

  /** 概览：几个数。 */
  public record ImportExportOverview(
      long totalTasks,
      long exportTasks,
      long importTasks,
      long pendingTasks,
      long runningTasks,
      long succeededTasks,
      long failedTasks,
      long rowErrorCount,
      long fileCount,
      OffsetDateTime now) {}

  // ================================================================ 入参

  /** 发起导出任务。 */
  public record ImportExportCreateInput(
      String domainCode, Map<String, Object> scope) {}

  /**
   * 发起导入任务（<b>base64 JSON</b> 形态）。
   *
   * <p>🔴 <b>为什么是 base64 而不是 multipart</b>：浏览器侧的同源代理只转发 JSON
   * （{@code request.text()} + 固定 {@code Content-Type: application/json}），
   * multipart 过不去 —— 走 multipart 的话请求体不是 multipart，服务端解析不出文件部分。
   * ⇒ 浏览器走这一支；**直连 API 的客户端**仍可用 multipart 端点。
   *
   * @param domainCode 数据域
   * @param filename 原始文件名（「导进去的到底是哪份文件」的凭据）
   * @param contentBase64 文件内容的 Base64（**不是**明文，也不是 URL）
   * @param scope 范围（导入方向的范围字段：目标对象 / 导入模式）
   */
  public record ImportExportImportInput(
      String domainCode, String filename, String contentBase64, Map<String, Object> scope) {}
}
