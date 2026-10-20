package com.starcon.channel;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 10.3.2.2 揽收与签收确认 的传输模型。
 *
 * <p>载荷一律用具名 {@code record} 而不是 {@code Map<String,Object>}：Map 的值类型会被
 * springdoc 推成 object，OpenAPI codegen 出来是空对象 {@code {}}（已踩过）。
 *
 * <p>🔴 名字带 {@code PickupSignoff} 前缀不是啰嗦，是**必需**：本模块的
 * {@code PickupSignoffCaseRow} / {@code PickupSignoffSummary} 等类型在跨服务合并的
 * 前端契约里必须与别的服务**不同名** —— 同名不同结构会变成字段级覆盖，
 * 消费端拿到的是「凭空多出的字段」，编译期还不报错（与 V212 把
 * {@code TrackingEventRow} 改名成 {@code CarrierTrackingEventRow} 同款理由）。
 *
 * <p>枚举取值在迁移 V217 里由 CHECK 钉死，这里用字符串常量表达以免前端拿到
 * 一堆看不懂的数字；每个取值集合在 {@link LogisticsPickupSignoffCapabilities} 里如实列出。
 */
public final class LogisticsPickupSignoffModels {
  private LogisticsPickupSignoffModels() {}

  // ================================================================ 能力声明

  /**
   * 一个能力维度。
   *
   * <p>{@code available=false} 时必须同时给出 {@code ownerModule}（缺口归属模块）与
   * {@code impact}（缺了会怎样）。**不用空列表或 0 冒充「这一项没有数据」** ——
   * 空列表在界面上与「确实没有记录」无法区分，那是把缺口说成了事实。
   */
  public record LogisticsPickupSignoffCapabilityDimension(
      String dimension,
      String description,
      boolean available,
      String source,
      String ownerModule,
      String impact) {}

  public record LogisticsPickupSignoffCapabilities(
      List<LogisticsPickupSignoffCapabilityDimension> dimensions,
      List<String> caseStatuses,
      List<String> pickupStates,
      List<String> deliveryStates,
      List<String> reminderKinds,
      List<String> reminderStatuses,
      List<String> notificationKinds,
      List<String> notificationStatuses,
      List<String> gapCodes,
      String boundaryNote,
      String highlight) {}

  // ================================================================ 时限规则

  /**
   * 揽收 / 签收时限规则行。
   *
   * <p>{@code pickupSlaHours} 等时长一律 {@link BigDecimal}（小时），DB 落
   * numeric(19,6) —— **禁浮点**：0.1 小时的误差在跨 8 小时时区差上是 6 分钟，
   * 而「超时未揽收」直接决定平台会不会判我们未发货。
   */
  public record PickupSignoffSlaRuleRow(
      UUID id,
      String carrierCode,
      UUID channelId,
      String channelCode,
      String channelName,
      BigDecimal pickupSlaHours,
      BigDecimal reminderLeadHours,
      BigDecimal reviewInviteDelayHours,
      BigDecimal afterSalesWindowHours,
      String status,
      String statusLabel,
      String note,
      int version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 登记时限规则。 */
  public record PickupSignoffSlaRuleInput(
      @NotBlank @Pattern(regexp = "^.{1,64}$", message = "承运商代码最多 64 个字符")
          String carrierCode,
      UUID channelId,
      @NotNull @DecimalMin(value = "0.000001", message = "揽收时限必须大于 0")
          BigDecimal pickupSlaHours,
      @DecimalMin(value = "0", message = "提醒提前量不能为负") BigDecimal reminderLeadHours,
      @DecimalMin(value = "0", message = "评价邀请延迟不能为负")
          BigDecimal reviewInviteDelayHours,
      @DecimalMin(value = "0.000001", message = "售后窗口必须大于 0")
          BigDecimal afterSalesWindowHours,
      @Pattern(regexp = "ACTIVE|DISABLED", message = "规则状态只能是 ACTIVE 或 DISABLED")
          String status,
      @Pattern(regexp = "^.{0,600}$", message = "备注最多 600 个字符") String note) {}

  // ================================================================ 确认单

  /**
   * 确认单行 = 一个**交运件**（10.3.2.1 交接清单的一行）。
   *
   * <p>{@code pickupState} / {@code deliveryState} 是读时推进的确认状态；
   * {@code status} 是这两者合成的案件状态（DB 层 CHECK 钉死自洽）。
   */
  public record PickupSignoffCaseRow(
      UUID id,
      String caseNo,
      UUID sourceManifestItemId,
      UUID sourceBatchId,
      String sourceBatchNo,
      String sourceBatchStatus,
      UUID sourceSubjectId,
      int subjectMatchCount,
      String masterWaybillNo,
      int parcelNo,
      String parcelRef,
      String carrierCode,
      UUID channelId,
      String salesOrderNo,
      String destinationCountry,
      BigDecimal grossWeightKg,
      OffsetDateTime handoverAt,
      OffsetDateTime dispatchedAt,
      UUID slaRuleId,
      BigDecimal pickupSlaHours,
      BigDecimal reminderLeadHours,
      OffsetDateTime pickupDeadlineAt,
      String pickupState,
      String pickupStateLabel,
      OffsetDateTime pickupConfirmedAt,
      String pickupEvidenceSource,
      String pickupEvidenceRef,
      String deliveryState,
      String deliveryStateLabel,
      OffsetDateTime deliveryConfirmedAt,
      String deliveryEvidenceSource,
      String deliveryEvidenceRef,
      boolean exceptionFlag,
      String exceptionCode,
      String exceptionNote,
      OffsetDateTime exceptionAt,
      BigDecimal overdueHours,
      String status,
      String statusLabel,
      String gapCode,
      String gapLabel,
      String gapReason,
      OffsetDateTime lastReconciledAt,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 确认单分页。汇总是真聚合，不受分页影响。 */
  public record PickupSignoffCasePage(
      List<PickupSignoffCaseRow> items,
      int page,
      int size,
      long totalElements,
      int totalPages,
      PickupSignoffSummary summary) {}

  /**
   * 确认单汇总：全量口径（不受筛选与分页影响）。
   *
   * <p>缺口计数单列：{@code noWaybillCount} / {@code subjectMissingCount} /
   * {@code subjectAmbiguousCount} / {@code slaUnconfiguredCount} 是「这一件推进不了」
   * 的**原因分布**，不是「没有这一件」。
   */
  public record PickupSignoffSummary(
      long totalCount,
      long pendingPickupCount,
      long pickedUpCount,
      long overdueCount,
      long deliveredCount,
      long voidCount,
      long exceptionCount,
      long noWaybillCount,
      long subjectMissingCount,
      long subjectAmbiguousCount,
      long slaUnconfiguredCount,
      long reminderPendingCount,
      long notificationReadyCount,
      long notificationPendingCount,
      List<PickupSignoffCarrierBucket> byCarrier,
      List<PickupSignoffStatusBucket> byStatus) {}

  /** 按承运商分桶。 */
  public record PickupSignoffCarrierBucket(String carrierCode, long caseCount, long overdueCount) {}

  /** 按状态分桶。 */
  public record PickupSignoffStatusBucket(String status, long caseCount) {}

  /** 确认单详情 = 单 + 时间线 + 提醒 + 下游待通知 + 边界说明。 */
  public record PickupSignoffCaseDetail(
      PickupSignoffCaseRow signoffCase,
      List<PickupSignoffEventRow> timeline,
      List<PickupSignoffReminderRow> reminders,
      List<PickupSignoffNotificationRow> notifications,
      List<String> boundaryNotes) {}

  // ================================================================ 确认事件流

  public record PickupSignoffEventRow(
      UUID id,
      UUID caseId,
      String eventKind,
      String eventKindLabel,
      String fromStatus,
      String toStatus,
      String detail,
      String carrierEventId,
      UUID actorMembershipId,
      OffsetDateTime occurredAt) {}

  public record PickupSignoffEventPage(
      List<PickupSignoffEventRow> items,
      int page,
      int size,
      long totalElements,
      int totalPages) {}

  // ================================================================ 提醒任务

  public record PickupSignoffReminderRow(
      UUID id,
      UUID caseId,
      String caseNo,
      String masterWaybillNo,
      String carrierCode,
      String reminderKind,
      String reminderKindLabel,
      String status,
      String statusLabel,
      String title,
      String detail,
      OffsetDateTime dueAt,
      UUID assignedToMembershipId,
      OffsetDateTime acknowledgedAt,
      UUID acknowledgedByMembershipId,
      OffsetDateTime resolvedAt,
      String resolutionNote,
      OffsetDateTime createdAt) {}

  public record PickupSignoffReminderPage(
      List<PickupSignoffReminderRow> items,
      int page,
      int size,
      long totalElements,
      int totalPages) {}

  // ================================================================ 下游待通知

  /**
   * 下游待通知行。
   *
   * <p>{@code payload} 是 JSON **字符串**（不是 object）：契约里 object 会被 codegen
   * 推成空对象 {@code {}}，下游就拿不到字段了。要结构化内容就在前端
   * {@code JSON.parse} 一次。
   */
  public record PickupSignoffNotificationRow(
      UUID id,
      UUID caseId,
      String caseNo,
      String masterWaybillNo,
      String carrierCode,
      String notificationKind,
      String notificationKindLabel,
      String downstreamModule,
      String downstreamStatus,
      String downstreamStatusLabel,
      OffsetDateTime dueAt,
      String payload,
      int attemptCount,
      String lastError,
      OffsetDateTime consumedAt,
      UUID consumedByMembershipId,
      OffsetDateTime createdAt) {}

  public record PickupSignoffNotificationPage(
      List<PickupSignoffNotificationRow> items,
      int page,
      int size,
      long totalElements,
      int totalPages) {}

  // ================================================================ 输入

  /** 同步确认单（幂等投影）。 */
  public record PickupSignoffSyncInput(
      @Pattern(regexp = "^.{0,64}$", message = "承运商代码最多 64 个字符") String carrierCode,
      @Pattern(regexp = "^.{0,32}$", message = "批次号最多 32 个字符") String batchNo,
      Integer page,
      Integer size) {}

  public record PickupSignoffSyncResult(
      int scanned,
      int created,
      int updated,
      int skipped,
      long totalElements,
      List<String> notes) {}

  /** 巡检（主动监控）：推进揽收/签收状态、判定超时、生成提醒与待通知。 */
  public record PickupSignoffReconcileInput(
      @Pattern(regexp = "^.{0,64}$", message = "承运商代码最多 64 个字符") String carrierCode,
      @Pattern(regexp = "^.{0,32}$", message = "批次号最多 32 个字符") String batchNo,
      Integer page,
      Integer size) {}

  public record PickupSignoffReconcileResult(
      int scanned,
      int created,
      int pickupConfirmed,
      int deliveryConfirmed,
      int overdueDetected,
      int dueSoonDetected,
      int remindersCreated,
      int notificationsCreated,
      int exceptionsMarked,
      int voided,
      List<String> notes) {}

  /** 人工确认揽收 / 签收（承运商通道未接通时的凭据登记）。 */
  public record PickupSignoffAttestInput(
      @NotBlank
          @Pattern(
              regexp = "PICKUP|DELIVERY",
              message = "确认类型只能是 PICKUP（揽收）或 DELIVERY（签收）")
          String kind,
      @NotNull(message = "缺少确认发生时刻") OffsetDateTime occurredAt,
      @NotBlank
          @Pattern(regexp = "^.{1,128}$", message = "凭据号最多 128 个字符")
          String evidenceRef,
      @Pattern(regexp = "^.{0,400}$", message = "备注最多 400 个字符") String note) {}

  /** 提醒处置。 */
  public record PickupSignoffReminderActionInput(
      @Pattern(regexp = "^.{0,400}$", message = "备注最多 400 个字符") String note) {}

  /** 下游待通知消费结果登记。 */
  public record PickupSignoffNotificationConsumeInput(
      @NotBlank
          @Pattern(
              regexp = "CONSUMED|FAILED",
              message = "消费结果只能是 CONSUMED（已消费）或 FAILED（消费失败）")
          String result,
      @Pattern(regexp = "^.{0,400}$", message = "失败原因最多 400 个字符") String note) {}

  /** SLA 规则列表。 */
  public record PickupSignoffSlaRulePage(
      List<PickupSignoffSlaRuleRow> items,
      int page,
      int size,
      long totalElements,
      int totalPages) {}

  /** 确认单按状态分桶的辅助类型（列表页顶部用）。 */
  public record PickupSignoffStatusBucketRow(String status, String statusLabel, long caseCount) {}
}
