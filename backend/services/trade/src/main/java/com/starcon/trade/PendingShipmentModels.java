package com.starcon.trade;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 9.4.1.1 待发货的契约形状。
 *
 * <p>风格照抄 9.6.1.2（{@code DropshipOrderModels}）：枚举与记录同文件， 输入用 {@code @Valid} record，响应是不可变 record。
 */
final class PendingShipmentModels {
  private PendingShipmentModels() {}

  // ================================================================ 枚举

  /** 三个批量操作，逐字对应蓝图点名的三句话。 */
  enum ShipmentBatchTaskType {
    WAYBILL_REQUEST("批量申请运单号"),
    LABEL_PRINT("批量打单"),
    HANDOVER("批量交接");

    public final String displayName;

    ShipmentBatchTaskType(String displayName) {
      this.displayName = displayName;
    }
  }

  /**
   * 批量任务状态机。
   *
   * <p>{@code PARTIAL} 不是可选的：部分失败在大促期是常态， 把它并进 SUCCEEDED 就等于让「交接漏发」在界面上显示成「交接完成」。
   */
  enum ShipmentBatchStatus {
    PENDING("待执行"),
    RUNNING("执行中"),
    SUCCEEDED("全部成功"),
    PARTIAL("部分成功"),
    FAILED("全部失败"),
    CANCELLED("已取消");

    public final String displayName;

    ShipmentBatchStatus(String displayName) {
      this.displayName = displayName;
    }

    /** 终态不可再动（DB 触发器 {@code guard_shipment_batch_task_state} 同口径）。 */
    public boolean terminal() {
      return this == SUCCEEDED || this == PARTIAL || this == FAILED || this == CANCELLED;
    }
  }

  /** 逐条执行结果。SKIPPED 必须带原因，否则「跳过」与「漏了」事后无法区分。 */
  enum ShipmentTaskItemStatus {
    PENDING("待处理"),
    SUCCEEDED("成功"),
    FAILED("失败"),
    SKIPPED("跳过");

    public final String displayName;

    ShipmentTaskItemStatus(String displayName) {
      this.displayName = displayName;
    }
  }

  /** 待发货单元状态机，最远只到 HANDED_OVER（**本模块不产生已发货**）。 */
  enum PendingShipmentStatus {
    PENDING("待发货"),
    WAYBILL_REQUESTED("已申请运单号"),
    LABEL_PRINTED("已打单"),
    HANDED_OVER("已交接"),
    CANCELLED("已作废");

    public final String displayName;

    PendingShipmentStatus(String displayName) {
      this.displayName = displayName;
    }
  }

  /** 承运渠道（分组维度之一）。 */
  enum ShipmentCarrierChannel {
    PLATFORM_INTEGRATED("平台面单"),
    THIRD_PARTY("第三方物流"),
    SELF_PICKUP("买家自提"),
    POSTAL("邮政"),
    FREIGHT_FORWARDER("货代");

    public final String displayName;

    ShipmentCarrierChannel(String displayName) {
      this.displayName = displayName;
    }
  }

  /**
   * 发货时限分组桶。
   *
   * <p>{@code UNSET} 是**刻意的一桶**，不是兜底：上游 2.5.1.1 回传未交付， trade 的 sales_order 也没有平台 SLA 列（见 V124
   * 文件头第二节）， 所以「还不知道时限」是常态。把它混进 DUE_TODAY 等于把最该盯的货藏起来。
   */
  enum ShipmentSlaBucket {
    OVERDUE("已逾期"),
    DUE_TODAY("今天到期"),
    DUE_48H("48 小时内"),
    LATER("更晚"),
    UNSET("未登记时限");

    public final String displayName;

    ShipmentSlaBucket(String displayName) {
      this.displayName = displayName;
    }
  }

  /** 看板分组维度（蓝图点名的四个：仓库 / 承运渠道 / 承运商 / 发货时限）。 */
  enum PendingShipmentGroupBy {
    WAREHOUSE("仓库"),
    CARRIER_CHANNEL("承运渠道"),
    CARRIER("承运商"),
    SLA_DEADLINE("发货时限");

    public final String displayName;

    PendingShipmentGroupBy(String displayName) {
      this.displayName = displayName;
    }
  }

  /** 运单申请结果。 */
  enum ShipmentWaybillStatus {
    REQUESTED("已提交承运商"),
    ISSUED("已签发"),
    FAILED("被拒");

    public final String displayName;

    ShipmentWaybillStatus(String displayName) {
      this.displayName = displayName;
    }
  }

  /** 时限登记来源。两者互斥，不允许「说不清是谁登记的」。 */
  enum ShipmentSlaSource {
    MANUAL_ATTESTATION("人工登记"),
    PLATFORM_FEED("平台回传");

    public final String displayName;

    ShipmentSlaSource(String displayName) {
      this.displayName = displayName;
    }
  }

  // ================================================================ 能力声明

  /** 能力维度：哪些上游真读得到，哪些读不到（读不到就**显式报出**）。 */
  public record PendingShipmentCapabilityDimension(
      String dimension,
      String description,
      boolean available,
      String source,
      String ownerModule,
      String impact) {}

  public record PendingShipmentCapabilities(
      List<PendingShipmentCapabilityDimension> dimensions,
      List<String> statuses,
      List<String> batchTaskTypes,
      List<String> batchStatuses,
      List<String> groupByOptions,
      List<String> slaBuckets,
      String carrierSourceNote,
      String downstreamNote) {}

  // ================================================================ 只读投影与看板

  /** 看板一行 = 一个分组桶。计数与重量是**真聚合**，不是页内统计。 */
  public record PendingShipmentGroup(
      String groupKey,
      String groupLabel,
      long unitCount,
      int parcelCount,
      BigDecimal totalWeightKg,
      long overdueCount,
      long dueTodayCount,
      long unsetSlaCount) {}

  public record PendingShipmentBoard(
      String groupBy,
      List<PendingShipmentGroup> groups,
      PendingShipmentSummary summary,
      List<String> boundaryNotes) {}

  /** 汇总**不受分页影响**（与 9.6.1.2 / 9.3.2.1 同一纪律）。 */
  public record PendingShipmentSummary(
      long totalUnits,
      long pendingUnits,
      long waybillRequestedUnits,
      long labelPrintedUnits,
      long handedOverUnits,
      long cancelledUnits,
      int totalParcels,
      BigDecimal totalWeightKg,
      long overdueUnits,
      long dueTodayUnits,
      long unsetSlaUnits,
      long openBatchTasks) {}

  /** 同步结果：从 warehouse 授权 API 读到多少、落了多少。 */
  public record PendingShipmentSyncResult(
      int fetchedUnits,
      int insertedUnits,
      int updatedUnits,
      int skippedUnits,
      int parcelRowsUpserted,
      int leftBehindUnits,
      OffsetDateTime syncedAt,
      List<String> notes) {}

  // ================================================================ 待发货单元

  public record PendingShipmentUnitRow(
      UUID id,
      UUID sourcePackingOrderId,
      UUID sourceOutboundOrderId,
      String warehouseRef,
      UUID warehouseId,
      String outboundOrderNo,
      UUID salesOrderId,
      String salesOrderNo,
      String shopRef,
      String buyerName,
      String destinationCountry,
      ShipmentCarrierChannel carrierChannel,
      String carrierCode,
      ShipmentSlaBucket slaBucket,
      String slaSource,
      int parcelCount,
      BigDecimal totalWeightKg,
      PendingShipmentStatus status,
      String waybillNo,
      boolean labelPrinted,
      OffsetDateTime packedAt,
      OffsetDateTime slaDeadline,
      OffsetDateTime handedOverAt,
      OffsetDateTime syncedAt,
      String nextActions) {}

  public record PendingShipmentUnitDetail(
      PendingShipmentUnitRow unit,
      List<PendingShipmentParcelRow> parcels,
      List<PendingShipmentWaybillRow> waybills,
      List<PendingShipmentEventRow> timeline,
      List<String> completionCriteria,
      List<String> boundaryNotes) {}

  public record PendingShipmentUnitPage(
      List<PendingShipmentUnitRow> items,
      int page,
      int size,
      long totalElements,
      int totalPages,
      PendingShipmentSummary summary) {}

  public record PendingShipmentParcelRow(
      UUID id,
      UUID unitId,
      int parcelNo,
      UUID sourceParcelId,
      BigDecimal grossWeightKg,
      BigDecimal lengthMm,
      BigDecimal widthMm,
      BigDecimal heightMm,
      boolean labelPrinted,
      OffsetDateTime labelPrintedAt,
      String labelJobRef) {}

  public record PendingShipmentWaybillRow(
      UUID id,
      UUID unitId,
      UUID taskId,
      String carrierCode,
      ShipmentCarrierChannel carrierChannel,
      ShipmentWaybillStatus status,
      String waybillNo,
      String requestRef,
      String errorMessage,
      OffsetDateTime requestedAt,
      OffsetDateTime issuedAt) {}

  public record PendingShipmentEventRow(
      UUID id,
      UUID unitId,
      UUID taskId,
      String eventKind,
      String fromStatus,
      String toStatus,
      String detail,
      OffsetDateTime occurredAt) {}

  // ================================================================ 批量任务

  public record ShipmentBatchTaskRow(
      UUID id,
      String taskNo,
      ShipmentBatchTaskType taskType,
      String taskTypeLabel,
      UUID warehouseId,
      String warehouseRef,
      ShipmentCarrierChannel carrierChannel,
      String carrierCode,
      ShipmentBatchStatus status,
      String statusLabel,
      int unitCount,
      int succeededCount,
      int failedCount,
      int skippedCount,
      String failureReason,
      String note,
      UUID createdByMembershipId,
      OffsetDateTime createdAt,
      OffsetDateTime startedAt,
      OffsetDateTime finishedAt) {}

  public record ShipmentBatchTaskItemRow(
      UUID id,
      UUID unitId,
      String outboundOrderNo,
      String salesOrderNo,
      String warehouseRef,
      String carrierCode,
      ShipmentCarrierChannel carrierChannel,
      UUID parcelId,
      int parcelNo,
      ShipmentTaskItemStatus status,
      String statusLabel,
      String detail,
      OffsetDateTime processedAt) {}

  public record ShipmentBatchTaskDetail(
      ShipmentBatchTaskRow task,
      List<ShipmentBatchTaskItemRow> items,
      List<PendingShipmentEventRow> timeline,
      List<String> boundaryNotes) {}

  public record ShipmentBatchTaskPage(
      List<ShipmentBatchTaskRow> items,
      int page,
      int size,
      long totalElements,
      int totalPages,
      PendingShipmentBatchSummary summary) {}

  public record PendingShipmentBatchSummary(
      long totalTasks,
      long pendingTasks,
      long runningTasks,
      long succeededTasks,
      long partialTasks,
      long failedTasks,
      long cancelledTasks,
      long affectedUnits) {}

  // ================================================================ 输入

  /**
   * 从 warehouse 授权 API 拉取已完成打包的出库单。
   *
   * <p>{@code carrierAssignments} 是本模块**必须由调用方给出**的一段事实： 承运商来自 9.3.x 路由决策，而路由读的是我方的运费时效估算，不是仓库的打包单。
   * 没给承运商的单元照样投影进来（看板必须看见「还没选路由」这件事）， 但它**建不了批量任务** —— 交接必须知道交给谁。
   */
  public record PendingShipmentSyncInput(
      UUID warehouseId,
      @Pattern(regexp = "^[A-Z0-9_\\-]{2,32}$", message = "承运渠道代码只能是大写字母、数字、下划线或中划线")
          String carrierChannel,
      @Pattern(regexp = "^$|^[A-Z0-9_\\-]{2,64}$", message = "承运商代码只能是大写字母、数字、下划线或中划线")
          String carrierCode,
      @NotNull List<CarrierAssignment> carrierAssignments,
      @Positive int pageSize) {
    public PendingShipmentSyncInput {
      carrierAssignments = carrierAssignments == null ? List.of() : List.copyOf(carrierAssignments);
    }
  }

  public record CarrierAssignment(
      @NotNull UUID outboundOrderId, @NotBlank String outboundOrderNo) {}

  /** 登记发货时限（平台 SLA）—— 见 V124 文件头第二节「登记而不伪造」。 */
  public record ShipmentSlaInput(
      @NotNull OffsetDateTime deadlineAt,
      @NotNull ShipmentSlaSource source,
      @NotBlank @Pattern(regexp = ".{4,400}", message = "登记依据至少 4 个字符") String note) {}

  /** 建批量任务：unitIds 为空表示按分组键（仓 / 渠道 / 承运商）取全部候选。 */
  public record ShipmentBatchTaskInput(
      @NotNull ShipmentBatchTaskType taskType,
      List<UUID> unitIds,
      UUID warehouseId,
      ShipmentCarrierChannel carrierChannel,
      @Pattern(regexp = "^[A-Z0-9_\\-]{2,64}$", message = "承运商代码只能是大写字母、数字、下划线或中划线")
          String carrierCode,
      @NotBlank @Pattern(regexp = ".{4,400}", message = "任务说明至少 4 个字符") String note) {
    public ShipmentBatchTaskInput {
      unitIds = unitIds == null ? List.of() : List.copyOf(unitIds);
    }
  }

  /** 批量任务里的运单回执（承运商签发结果）。 */
  public record ShipmentWaybillAckInput(
      @NotNull ShipmentWaybillStatus status,
      @Pattern(regexp = "^$|^[A-Za-z0-9\\-]{6,128}$", message = "运单号 6–128 位字母数字或中划线")
          String waybillNo,
      @Pattern(regexp = "^$|^[A-Za-z0-9\\-_]{1,128}$", message = "请求凭据 1–128 位") String requestRef,
      @Pattern(regexp = "^$|^.{4,600}$", message = "失败原因至少 4 个字符") String errorMessage) {}

  /**
   * 面单打印凭据回填（8.3.4.2 渲染出来的真实 jobRef，本模块不渲染 ZPL）。
   *
   * <p>刻意**带模块前缀**：warehouse 服务已经有一个 {@code ParcelLabelInput} （8.3.4.2 面单打印的入参，字段是 labelPrintJobId
   * / printedAt / note）。 两边同名但结构不同，codegen 会在写盘前直接中止并点名「trade 消费端会凭空多出 哪两个字段、独有的 jobRef 拿不到」——
   * 撞名的后果是另一条车道的事故。
   */
  public record PendingShipmentLabelInput(
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9\\-_]{3,128}$", message = "打印任务凭据 3–128 位字母数字或中划线")
          String jobRef) {}

  public record ShipmentCancelInput(
      @NotBlank @Pattern(regexp = ".{4,400}", message = "作废原因至少 4 个字符") String reason) {}
}
