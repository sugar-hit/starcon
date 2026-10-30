package com.starcon.reporting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 13.6.2.1 导出与订阅的 **DTO**（蓝图 13.6.2.1）。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 * **职责**：报表导出（Excel/CSV/PDF）与定时订阅推送（邮件/企业 IM），支持大文件异步导出。<br>
 * **输入**：导出请求；订阅配置。<br>
 * **输出**：文件下载链接；订阅投递记录。<br>
 * **关系**：上游 = 13.6.1.1；下游 = 1.3.2.2 推送。<b>大导出走异步任务并给出有效期</b>，
 * 同步导出大文件会拖垮服务。
 * </blockquote>
 *
 * <p>🔴 **命名纪律**：DTO 名统一带 {@code Export} / {@code Delivery} 前缀。
 * SpringDoc 的 schema 名在重名时会追加序号消歧（{@code CustomReportFoo} 与 {@code CustomReportFoo1}），
 * 契约生成物就会漂 ⇒ 前缀是契约稳定性的一部分，不是装饰。
 *
 * <p>🔴 **本文件不出现 {@code byte[]}**：二进制文件走
 * {@code ResponseEntity<byte[]> + Content-Disposition}（照抄 catalog 的下载端点），
 * 走 DTO 会被 JSON 编码成 base64 字符串 —— 那是「下载下来是一串乱码」而不是「一个文件」。
 */
public final class ReportingExportModels {

  private ReportingExportModels() {}

  // ==================================================================
  // 能力声明：可导出的东西
  // ==================================================================

  /** 一种导出格式。 */
  public record ExportSubscriptionFormatInfo(
      String code,
      String label,
      /** 文件扩展名（含点，如 {@code .xlsx}）。 */
      String extension,
      /** 下载时的 Content-Type。 */
      String contentType,
      boolean available,
      /** 不可用时写清原因（available=true 时为空串）。 */
      String note) {}

  /** 一个可导出的列（**白名单**：只能是报表自己的维度与度量）。 */
  public record ExportSubscriptionColumnInfo(
      /** 程序内的稳定键（{@code DIM:SHOP} / {@code MEASURE:SALES_AMOUNT}）。 */
      String key,
      /** 当时的列头文字。 */
      String label,
      /** 列的种类：DIMENSION / MEASURE。 */
      String kind,
      /** 这一列出的是什么（口径说明，照着显示）。 */
      String note) {}

  /** 一个可用的排序键。 */
  public record ExportSubscriptionSortInfo(String key, String label) {}

  /** 一个可用的筛选运算符。 */
  public record ExportSubscriptionFilterOperatorInfo(String code, String label) {}

  /**
   * 导出能力声明（**可导出字段 / 排序 / 筛选 / 格式 / 阈值**）。
   *
   * @param reportId 传了就返回这份报表自己的可导出列；没传就只返回通用能力
   * @param syncRowThreshold 同步导出的行数上限：**超过它必须走异步任务**
   * @param maxRows 单次导出的行数上限（超过 ⇒ 任务失败并写明原因，不静默截断）
   * @param downloadTtlHours 下载有效期（小时）—— 可配置的业务参数
   */
  public record ExportSubscriptionSourcesResponse(
      List<ExportSubscriptionFormatInfo> formats,
      List<ExportSubscriptionColumnInfo> columns,
      List<ExportSubscriptionSortInfo> sorts,
      List<ExportSubscriptionFilterOperatorInfo> filterOperators,
      int syncRowThreshold,
      int maxRows,
      int downloadTtlHours,
      @Schema(nullable = true) String reportId,
      @Schema(nullable = true) String reportCode,
      @Schema(nullable = true) String reportName,
      List<String> notes) {}

  // ==================================================================
  // 导出任务
  // ==================================================================

  /** 一个导出任务（异步任务的当前状态）。 */
  public record ExportSubscriptionJobView(
      String jobId,
      String reportId,
      String reportCode,
      String reportName,
      /** CSV / XLSX / PDF。 */
      String format,
      String status,
      String statusLabel,
      /** 选中的列（稳定键）。 */
      List<String> selectedFields,
      /** 当时的列头文字。 */
      List<String> fieldHeaders,
      /** 文件里实际写了多少行数据（不含表头）。 */
      int rowCount,
      @Schema(nullable = true) String fileName,
      @Schema(nullable = true) BigDecimal byteSize,
      @Schema(nullable = true) String contentSha256,
      /** 操作人（已认证会话的 user_id）。 */
      String actorId,
      @Schema(nullable = true) OffsetDateTime requestedAt,
      @Schema(nullable = true) OffsetDateTime startedAt,
      @Schema(nullable = true) OffsetDateTime finishedAt,
      /** 🔴 下载有效期（UTC）；过期后下载端点回 410 Gone。 */
      @Schema(nullable = true) OffsetDateTime expiresAt,
      /** 现在能不能下载（**服务端算出来的**，不是前端自己判断的）。 */
      boolean downloadable,
      /** 现在的下载状态说明（过期 / 未完成 / 已作废 各说各的）。 */
      String downloadNote,
      /** 执行失败的原因。 */
      String error) {}

  /** 导出任务清单（真分页 + 真计数）。 */
  public record ExportSubscriptionJobPage(
      List<ExportSubscriptionJobView> rows,
      long total,
      int page,
      int size,
      String statusFilter,
      String formatFilter,
      String keyword) {}

  /**
   * 发起一次导出（写入用）。
   *
   * @param format CSV / XLSX / PDF
   * @param fields 选中的列（稳定键）；空 = 全部列
   */
  public record ExportSubscriptionJobCreateRequest(
      String reportId,
      String format,
      List<String> fields,
      @Schema(nullable = true) LocalDate from,
      @Schema(nullable = true) LocalDate to) {}

  // ==================================================================
  // 投递
  // ==================================================================

  /**
   * 一条投递记录（**队列的一格**）。
   *
   * 🔴 {@code status} 是**真的投递结果**：只有传输层回报成功才会是 {@code DELIVERED}。
   * 没有配置传输（缺 SMTP / Webhook）时它会是 {@code FAILED} 并写明原因 ——
   * **绝不允许在没发出去的情况下标成 DELIVERED**。
   */
  public record ExportSubscriptionDeliveryView(
      String deliveryId,
      String reportId,
      String reportCode,
      String reportName,
      String subscriptionId,
      /** 投递通道：EMAIL / IM。 */
      String channel,
      /** 收件目标（邮箱 / IM 群 id）。 */
      String target,
      /** 计划投递的时刻（UTC）。 */
      OffsetDateTime scheduledFor,
      String status,
      String statusLabel,
      int attemptCount,
      /** 最近一次失败的原因（成功时为空串）。 */
      String lastError,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      /** 这一格的说明（「待投递」不等于「已发送」）。 */
      String note) {}

  /**
   * 跑一轮投递的结果。
   *
   * @param attempted 本轮尝试了几条
   * @param delivered 真的投递成功了几条（**传输层回报成功**才计数）
   * @param failed 失败了几条（没配置传输也算失败，且写明原因）
   */
  public record ExportSubscriptionDispatchResult(
      int attempted,
      int delivered,
      int failed,
      List<ExportSubscriptionDeliveryView> rows,
      String note) {}

  /** 跑一轮投递的入参（空体 ⇒ 用默认批量）。 */
  public record ExportSubscriptionDispatchRequest(
      /** 本轮最多处理几条（空 ⇒ 服务端默认 50）。 */
      @Schema(nullable = true) Integer limit) {}
}
