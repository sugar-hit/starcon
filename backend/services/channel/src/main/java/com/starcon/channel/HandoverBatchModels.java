package com.starcon.channel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 10.3.2.1 交运批次 的传输模型。
 *
 * <p>载荷一律用具名 {@code record} 而不是 {@code Map<String,Object>}：Map 的值类型会被
 * springdoc 推成 object，OpenAPI codegen 出来是空对象 {@code {}}（已踩过）。
 *
 * <p>枚举值在迁移 V129 里由 CHECK 钉死，这里用字符串常量表达以免前端拿到
 * 一堆看不懂的数字；每个取值集合在 {@link HandoverCapabilities} 里如实列出。
 */
public final class HandoverBatchModels {
  private HandoverBatchModels() {}

  // ================================================================ 能力声明

  /**
   * 一个能力维度。
   *
   * <p>{@code available=false} 时必须同时给出 {@code ownerModule}（缺口归属模块）与
   * {@code impact}（缺了会怎样）。**不用空列表或 0 冒充「这一项没有数据」** ——
   * 空列表在界面上与「确实没有记录」无法区分，那是把缺口说成了事实。
   */
  public record HandoverCapabilityDimension(
      String dimension,
      String description,
      boolean available,
      String source,
      String ownerModule,
      String impact) {}

  public record HandoverCapabilities(
      List<HandoverCapabilityDimension> dimensions,
      List<String> batchStatuses,
      List<String> carrierChannels,
      List<String> waybillGapCodes,
      List<String> receiptStatuses,
      List<String> manifestActions,
      String carrierSourceNote,
      String upstreamNote,
      String boundaryNote) {}

  // ================================================================ 候选池（上游 9.4.1.1 的只读投影）

  /** 候选行 = 一个已由 9.4.1.1 推到 HANDED_OVER 的出库单元。 */
  public record HandoverCandidateRow(
      UUID id,
      UUID sourceUnitId,
      UUID sourcePackingOrderId,
      String warehouseRef,
      String outboundOrderNo,
      String salesOrderNo,
      String destinationCountry,
      String carrierCode,
      String carrierChannel,
      String waybillNo,
      String waybillGapReason,
      String waybillGapCode,
      int parcelCount,
      BigDecimal totalWeightKg,
      String status,
      UUID batchId,
      OffsetDateTime packedAt,
      OffsetDateTime handedOverAt,
      OffsetDateTime syncedAt) {}

  /** 候选池分页。汇总是真聚合，不受分页影响。 */
  public record HandoverCandidatePage(
      List<HandoverCandidateRow> items,
      int page,
      int size,
      long totalElements,
      int totalPages,
      HandoverCandidateSummary summary) {}

  /**
   * 候选池汇总：全部按**承运商**真聚合。
   *
   * <p>蓝图输入句点名「承运商」与「交运时间」两个维度，所以汇总也按这两个分组，
   * 而不是拉全量在 Java 里 filter（那会让「这一屏看到的」与「实际总量」不一致）。
   */
  public record HandoverCandidateSummary(
      long pendingCount,
      long batchedCount,
      int pendingParcels,
      BigDecimal pendingWeightKg,
      long missingWaybillCount,
      List<HandoverGroupBucket> byCarrier,
      List<String> handoverDateOptions) {}

  /** 一个分组桶 = 承运商 × 交运日期。计数与重量是 SQL 里的真 GROUP BY。 */
  public record HandoverGroupBucket(
      String carrierCode,
      String carrierChannel,
      LocalDate handoverBucket,
      String handoverBucketLabel,
      long unitCount,
      int parcelCount,
      BigDecimal totalWeightKg,
      long missingWaybillCount) {}

  // ================================================================ 交运批次

  public record HandoverBatchRow(
      UUID id,
      String batchNo,
      String carrierCode,
      String carrierChannel,
      OffsetDateTime handoverAt,
      LocalDate handoverBucket,
      int unitCount,
      int parcelCount,
      BigDecimal totalWeightKg,
      String status,
      String statusDetail,
      String statusLabel,
      UUID dispatchedByMembershipId,
      OffsetDateTime dispatchedAt,
      String receiptRef,
      String receiptStatus,
      String receiptStatusLabel,
      String receiptDetail,
      OffsetDateTime receiptRecordedAt,
      int manifestCount,
      int version,
      OffsetDateTime createdAt) {}

  public record HandoverBatchPage(
      List<HandoverBatchRow> items,
      int page,
      int size,
      long totalElements,
      int totalPages,
      HandoverBatchSummary summary) {}

  public record HandoverBatchSummary(
      long draftCount,
      long dispatchPendingCount,
      long handedOverCount,
      long cancelledCount,
      int handedOverParcels,
      BigDecimal handedOverWeightKg,
      long receiptMismatchCount,
      List<HandoverStatusBucket> byStatus,
      List<HandoverGroupBucket> byCarrier) {}

  /** 批次详情 = 单头 + 逐件交接清单 + 时间线 + 边界声明。 */
  public record HandoverBatchDetail(
      HandoverBatchRow batch,
      List<HandoverManifestItemRow> manifest,
      List<HandoverBatchEventRow> timeline,
      List<String> completionCriteria,
      List<String> boundaryNotes) {}

  /** 状态分桶（真 GROUP BY）。 */
  public record HandoverStatusBucket(
      String status, String statusLabel, long batchCount, int parcelCount, BigDecimal totalWeightKg) {}

  // ================================================================ 交接清单（法律凭据）

  /**
   * 交接清单逐件行。
   *
   * <p>{@code waybillNo} 为空时 {@code waybillGapReason} / {@code waybillGapCode}
   * **必须**说明原因（DB CHECK 已钉）：10.2.1.2 运单申请未交付，运单号可能还没有来源，
   * 此时如实留空并说明，**绝不拿出库单号冒充运单号**（9.4.1.1 已踩过并明确禁止）。
   */
  public record HandoverManifestItemRow(
      UUID id,
      UUID batchId,
      UUID candidateId,
      int parcelNo,
      String parcelRef,
      String warehouseRef,
      String outboundOrderNo,
      String carrierCode,
      String waybillNo,
      String waybillGapReason,
      String waybillGapCode,
      BigDecimal grossWeightKg,
      BigDecimal lengthMm,
      BigDecimal widthMm,
      BigDecimal heightMm,
      String remark,
      OffsetDateTime handoverAt,
      UUID handedByMembershipId,
      OffsetDateTime createdAt) {}

  /** 交接清单导出用：一张批次的逐件凭据（追责时直接给对方的那一份）。 */
  public record HandoverManifestExport(
      UUID batchId,
      String batchNo,
      String carrierCode,
      String carrierChannel,
      OffsetDateTime handoverAt,
      String status,
      String statusLabel,
      int itemCount,
      BigDecimal totalWeightKg,
      List<HandoverManifestItemRow> items,
      List<String> boundaryNotes) {}

  /** 事件时间线（append-only）。作废一个批次时，「曾登记过交接」仍留在时间线上。 */
  public record HandoverBatchEventRow(
      UUID id,
      UUID batchId,
      UUID candidateId,
      String eventKind,
      String fromStatus,
      String toStatus,
      String detail,
      UUID actorMembershipId,
      OffsetDateTime occurredAt) {}

  // ================================================================ 输入

  /** 同步请求：按承运商拉上游 HANDED_OVER 单元进候选池。 */
  public record SyncCandidatesRequest(
      UUID warehouseId, String carrierCode, String carrierChannel, int page, int size) {}

  public record SyncCandidatesResult(
      int fetched, int created, int updated, String carrierCode, OffsetDateTime syncedAt, String note) {}

  /** 组批请求：承运商 + 交运时间（蓝图输入句的两个维度）+ 逐件交接清单。 */
  public record CreateBatchRequest(
      String carrierCode,
      OffsetDateTime handoverAt,
      UUID warehouseId,
      String warehouseRef,
      List<ManifestItemInput> items) {}

  /** 交接清单逐件输入。运单号有则记，无则必须给缺口原因。 */
  public record ManifestItemInput(
      UUID candidateId,
      int parcelNo,
      String parcelRef,
      String waybillNo,
      String waybillGapCode,
      String waybillGapReason,
      BigDecimal grossWeightKg,
      BigDecimal lengthMm,
      BigDecimal widthMm,
      BigDecimal heightMm,
      String remark) {}

  /** 登记交接：交给承运商的那一刻由谁做（凭据必须有主体与时刻）。 */
  public record DispatchRequest(OffsetDateTime handoverAt, String note) {}

  /** 承运商回执登记（10.1 渠道未交付 ⇒ 由人工带回执登记，不是系统对接取回）。 */
  public record ReceiptRequest(
      String receiptRef,
      String receiptStatus,
      String receiptDetail,
      OffsetDateTime receiptRecordedAt) {}
}
