package com.starcon.channel;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 10.3.1.1 轨迹查询 的传输模型。
 *
 * <p>载荷一律用具名 {@code record} 而不是 {@code Map<String,Object>}：Map 的值类型会被
 * springdoc 推成 object，OpenAPI codegen 出来是空对象 {@code {}}（已踩过）。
 *
 * <p>枚举值在迁移 V212 里由 CHECK 钉死，这里用字符串常量表达以免前端拿到
 * 一堆看不懂的数字；每个取值集合在 {@link TrackingCapabilities} 里如实列出。
 */
public final class TrackingQueryModels {
  private TrackingQueryModels() {}

  // ================================================================ 能力声明

  /**
   * 一个能力维度。
   *
   * <p>{@code available=false} 时必须同时给出 {@code ownerModule}（缺口归属模块）与
   * {@code impact}（缺了会怎样）。**不用空列表或 0 冒充「这一项没有数据」** ——
   * 空列表在界面上与「确实没有记录」无法区分，那是把缺口说成了事实。
   */
  public record TrackingCapabilityDimension(
      String dimension,
      String description,
      boolean available,
      String source,
      String ownerModule,
      String impact) {}

  public record TrackingCapabilities(
      List<TrackingCapabilityDimension> dimensions,
      List<String> nodeTypes,
      List<String> etaStatuses,
      List<String> subjectStatuses,
      List<String> eventSources,
      List<String> pullTaskStatuses,
      List<String> pullItemStatuses,
      List<String> serviceLevels,
      String boundaryNote,
      String highlight) {}

  // ================================================================ 轨迹主体（运单级）

  /**
   * 轨迹主体行 = 一票已发货的轨迹汇总。
   *
   * <p>{@code etaStatus} 是**读时**算出来的（不是存下来的），因为「是否延误」
   * 取决于现在几点 —— 上午十点还在承诺时效内、下午六点就超了，
   * 存一个静态结论会让「现在延误了吗」这个问题答错。
   */
  public record TrackingSubjectRow(
      UUID id,
      UUID sourceShipmentRecordId,
      String sourceRecordNo,
      String salesOrderNo,
      String outboundOrderNo,
      String warehouseRef,
      String carrierCode,
      String masterWaybillNo,
      String destinationCountry,
      UUID channelId,
      String channelCode,
      String channelName,
      BigDecimal grossWeightKg,
      String serviceLevel,
      int parcelCount,
      OffsetDateTime shippedAt,
      String latestNode,
      String latestNodeLabel,
      String latestEventCode,
      String latestDescription,
      OffsetDateTime latestOccurredAt,
      OffsetDateTime latestReceivedAt,
      int eventCount,
      boolean exceptionFlag,
      int exceptionCount,
      String lastExceptionCode,
      String lastExceptionNote,
      OffsetDateTime lastExceptionAt,
      String status,
      String statusLabel,
      String etaStatus,
      String etaStatusLabel,
      OffsetDateTime promisedEtaEarliestAt,
      OffsetDateTime promisedEtaAt,
      Integer promisedTransitDaysMin,
      Integer promisedTransitDaysMax,
      OffsetDateTime carrierEtaAt,
      int matchedPromiseCount,
      OffsetDateTime syncedAt) {}

  /** 轨迹清单分页。汇总是真聚合，不受分页影响。 */
  public record TrackingSubjectPage(
      List<TrackingSubjectRow> items,
      int page,
      int size,
      long totalElements,
      int totalPages,
      TrackingSubjectSummary summary) {}

  /** 清单汇总：全量口径（不受筛选与分页影响）。 */
  public record TrackingSubjectSummary(
      long totalCount,
      long exceptionCount,
      long delayedCount,
      long atRiskCount,
      long noEtaCount,
      long noPromiseCount,
      long deliveredCount,
      long inTransitCount,
      long eventTotal,
      List<TrackingCarrierBucket> byCarrier,
      List<TrackingNodeBucket> byNode,
      List<TrackingEtaBucket> byEtaStatus) {}

  /** 按承运商分桶。 */
  public record TrackingCarrierBucket(String carrierCode, long subjectCount, long exceptionCount) {}

  /** 按节点分桶。 */
  public record TrackingNodeBucket(String nodeType, long subjectCount) {}

  /** 按时效状态分桶。 */
  public record TrackingEtaBucket(String etaStatus, long subjectCount) {}

  // ================================================================ 轨迹事件（时间线）

  /**
   * 承运商轨迹事件行。
   *
   * <p>⚠️ 名字带 {@code Carrier} 前缀不是啰嗦，是**必需**：warehouse 服务的
   * {@code InTransitTrackingModels.TrackingEventRow}（仓库作业在途事件）已经占了
   * {@code TrackingEventRow} 这个名字，而前端契约是**跨服务合并**的：同名不同结构
   * 会变成字段级覆盖，warehouse 独有的 {@code fromStage / toStage / etaBefore /
   * etaAfter} 会被本模块的 {@code carrierEventId / nodeType / exceptionCode}
   * 悄悄顶掉 —— 消费端拿到的是「凭空多出的字段」，编译期还不报错。
   * 契约 codegen（{@code tools/codegen/catalog-contracts.py}）会直接以
   * 「跨服务同名 DTO」中止，逼着这一层显式消歧。
   */
  public record CarrierTrackingEventRow(
      UUID id,
      UUID subjectId,
      String carrierCode,
      String carrierEventId,
      String nodeType,
      String nodeLabel,
      String eventCode,
      String description,
      String location,
      OffsetDateTime occurredAt,
      OffsetDateTime receivedAt,
      /**
       * 承运商在**这一条**事件里给的预计到达时刻（null = 这条没带）。
       *
       * <p>与主体行上的 {@code carrierEtaAt} 不是一回事：主体行上是**最新一条**带预计的
       * 事件的值，而这里保留每个节点当时的预计 —— 「预计到达」被改过几次本身就是信息。
       */
      OffsetDateTime estimatedDeliveryAt,
      String source,
      String exceptionCode,
      String exceptionNote,
      boolean exception,
      UUID registeredByMembershipId,
      OffsetDateTime createdAt) {}

  public record TrackingEventPage(
      List<CarrierTrackingEventRow> items,
      int page,
      int size,
      long totalElements,
      int totalPages) {}

  /**
   * 轨迹详情 = 主体 + 时间线 + 时效对比 + 边界说明。
   *
   * <p>时效对比单列成一个对象而不是摊平到主体行上：承诺（我方规则）与
   * 预计（承运商说的）是**两个来源**，摊平之后界面上分不清哪句话是谁说的。
   */
  public record TrackingTimelineDetail(
      TrackingSubjectRow subject,
      List<CarrierTrackingEventRow> timeline,
      TrackingEtaComparison eta,
      List<String> boundaryNotes) {}

  /** 预计时效对比。 */
  public record TrackingEtaComparison(
      String etaStatus,
      String etaStatusLabel,
      Integer promisedTransitDaysMin,
      Integer promisedTransitDaysMax,
      OffsetDateTime promisedEtaEarliestAt,
      OffsetDateTime promisedEtaAt,
      OffsetDateTime carrierEtaAt,
      OffsetDateTime latestOccurredAt,
      String note,
      int matchedPromiseCount) {}

  // ================================================================ 输入

  /** 从上游 9.4.1.2 同步轨迹主体（运单集合）。 */
  public record TrackingSyncInput(
      @Pattern(regexp = "^.{0,64}$", message = "承运商代码最多 64 个字符")
          String carrierCode,
      @Pattern(
              regexp = "ECONOMY|STANDARD|EXPRESS",
              message = "服务等级只能是 ECONOMY / STANDARD / EXPRESS")
          String serviceLevel,
      Integer page,
      Integer size) {}

  public record TrackingSyncResult(
      int scanned,
      int created,
      int updated,
      int skipped,
      long totalElements,
      List<String> notes) {}

  /** 承运商推送轨迹事件（批量）。 */
  public record TrackingEventPushInput(
      @NotBlank @Pattern(regexp = "^.{1,64}$", message = "承运商代码最多 64 个字符")
          String carrierCode,
      @NotEmpty @Size(max = 500, message = "一次最多 500 条事件") List<TrackingEventPushItem> events) {}

  public record TrackingEventPushItem(
      @NotBlank @Pattern(regexp = "^.{1,128}$", message = "承运商事件号最多 128 个字符")
          String carrierEventId,
      @NotBlank @Pattern(regexp = "^.{1,64}$", message = "运单号最多 64 个字符") String waybillNo,
      @NotBlank @Pattern(regexp = "^.{1,20}$", message = "节点类型最多 20 个字符") String nodeType,
      @Pattern(regexp = "^.{0,64}$", message = "事件码最多 64 个字符") String eventCode,
      @Pattern(regexp = "^.{0,400}$", message = "描述最多 400 个字符") String description,
      @Pattern(regexp = "^.{0,200}$", message = "地点最多 200 个字符") String location,
      @NotNull(message = "缺少承运商给的发生时刻") OffsetDateTime occurredAt,
      @Pattern(regexp = "^.{0,40}$", message = "异常码最多 40 个字符") String exceptionCode,
      @Pattern(regexp = "^.{0,400}$", message = "异常说明最多 400 个字符") String exceptionNote,
      OffsetDateTime estimatedDeliveryAt) {}

  /** 推送结果：逐条如实记账，**被拒的条目要看得见**。 */
  public record TrackingEventPushResult(
      String carrierCode,
      int received,
      int inserted,
      int duplicated,
      int rejected,
      List<TrackingEventPushRejection> rejections,
      TrackingSubjectRow subject) {}

  public record TrackingEventPushRejection(String carrierEventId, String waybillNo, String reason) {}

  /** 按承运商批量拉取（轮询任务）。 */
  public record TrackingPullTaskInput(
      @NotBlank @Pattern(regexp = "^.{1,64}$", message = "承运商代码最多 64 个字符")
          String carrierCode,
      @Size(max = 500, message = "一次最多 500 票") List<String> waybillNos,
      @Pattern(regexp = "MANUAL|SCHEDULED", message = "触发来源只能是 MANUAL 或 SCHEDULED")
          String triggerSource) {}

  // ================================================================ 拉取任务

  public record TrackingPullTaskRow(
      UUID id,
      String taskNo,
      String carrierCode,
      String status,
      String statusLabel,
      String triggerSource,
      int requestedCount,
      int succeededCount,
      int duplicateCount,
      int noEventCount,
      int failedCount,
      int insertedEventCount,
      OffsetDateTime startedAt,
      OffsetDateTime finishedAt,
      String failureReason,
      UUID createdByMembershipId,
      OffsetDateTime createdAt) {}

  public record TrackingPullTaskItemRow(
      UUID id,
      UUID subjectId,
      String waybillNo,
      String status,
      String statusLabel,
      int insertedEventCount,
      int duplicateEventCount,
      String detail) {}

  public record TrackingPullTaskDetail(
      TrackingPullTaskRow task, List<TrackingPullTaskItemRow> items) {}

  public record TrackingPullTaskPage(
      List<TrackingPullTaskRow> items,
      int page,
      int size,
      long totalElements,
      int totalPages) {}
}
