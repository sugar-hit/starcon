package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 9.3.3.1 履约请求的传输模型。
 *
 * <p>蓝图原话：「编排一次履约：锁定预占 → 生成出库单 → 编排作业 → 跟踪完成；持久化流程状态，支持断点恢复与超时重试。」输出是「履约请求 + 出库单 + 流程状态记录 + 事件」。
 *
 * <p><b>命名纪律（硬要求，不是风格偏好）</b>：springdoc 用 record 的 <b>简单类名</b>做 {@code components.schemas} 的键，
 * 而本仓已有 {@code FulfillmentRoutingCapabilities}（9.3.2.2）、{@code OrderConsolidationCapabilities}（9.3.1.2）
 * 等同名 record，同名会互相覆盖。因此本文件所有 record / enum 一律以 {@code FulfillmentRequest} 开头，
 * 嵌套声明同样起名 {@link FulfillmentRequestDimension} 而不是 {@code Dimension}
 * —— 9.3.2.2 的嵌套 {@code Dimension} 就已经占了这个键。
 *
 * <p><b>四条建模纪律</b>：
 *
 * <ul>
 *   <li><b>词表只有一个来源</b>：各枚举一字不改地对应 DB 的 CHECK 词表，Java 侧不维护第二份可写别的取值的枚举。</li>
 *   <li><b>状态机的合法转移写在枚举里</b>：{@link FulfillmentRequestStatus#allowedTargets()} 与 V152 第 8.1 节的
 *       转移表逐条对应，由集成测试比对，不靠两处各写一遍然后各自漂移。</li>
 *   <li><b>下游未接通要显式说，不能让调用方猜</b>：{@link FulfillmentRequestCapabilities} 里
 *       {@code reservesUpstreamStock} / {@code outboundOrderDispatched} / {@code logisticsOrderPlaced}
 *       全是 false —— 本模块只<b>登记</b>下游指令，没真的锁 warehouse 的库存。</li>
 *   <li><b>金额全程 BigDecimal</b>，数量 numeric(19,6)、金额 numeric(19,4)，结算口径 HALF_UP 到分。</li>
 * </ul>
 */
public final class FulfillmentRequestModels {

  private FulfillmentRequestModels() {}

  // ================================================================ 枚举

  /**
   * 履约流程状态。COMPLETED 与 CANCELLED 是终态。
   *
   * <p>{@link #allowedTargets()} 是本枚举唯一的状态机定义，必须与 V152 第 8.1 节
   * {@code guard_fulfillment_request_state} 里的白名单逐条一致。
   */
  public enum FulfillmentRequestStatus {
    DRAFT("待编排"),
    RESERVING("预占中"),
    RESERVED("已锁定预占"),
    OUTBOUND_PENDING("出库单待确认"),
    OUTBOUND_CONFIRMED("出库单已确认"),
    WORKING("作业编排中"),
    COMPLETED("已完成"),
    BLOCKED("已挂起"),
    CANCELLED("已取消");

    private final String label;

    FulfillmentRequestStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 终态不许离开：重发要新建一条履约请求，不是把老的改回来。 */
    public boolean terminal() {
      return this == COMPLETED || this == CANCELLED;
    }

    /**
     * DB 第 8.1 节白名单的 Java 镜像。
     *
     * <p>刻意用 switch 而不是构造器里的 {@code List.of(...)}：枚举常量之间互相引用
     * 是 Java 的 forward reference，构造器里引用不到后面才声明的常量（拿到的是 null），
     * 于是 {@code allowedTargets} 会静默变成一堆 null —— 比报错更糟。
     *
     * <p>BLOCKED 的四个目标里落到哪一个，由 {@link FulfillmentStepKind#resumeStatus()} 决定，
     * 也就是「从断点继续」而不是从头再来。
     */
    public List<FulfillmentRequestStatus> allowedTargets() {
      return switch (this) {
        // BLOCKED 也在列：建了单没人推进、阈值又过了，同样算「卡住」。
        case DRAFT -> List.of(RESERVING, BLOCKED, CANCELLED);
        case RESERVING -> List.of(RESERVED, BLOCKED, CANCELLED);
        case RESERVED -> List.of(OUTBOUND_PENDING, BLOCKED, CANCELLED);
        case OUTBOUND_PENDING -> List.of(OUTBOUND_CONFIRMED, BLOCKED, CANCELLED);
        case OUTBOUND_CONFIRMED -> List.of(WORKING, BLOCKED, CANCELLED);
        case WORKING -> List.of(COMPLETED, BLOCKED, CANCELLED);
        case BLOCKED ->
            List.of(RESERVING, OUTBOUND_PENDING, WORKING, CANCELLED);
        // 终态：空集合。
        case COMPLETED, CANCELLED -> List.of();
      };
    }
  }

  /** 流程步：断点恢复就是「回到 current_step 继续」。阶梯顺序即枚举顺序。 */
  public enum FulfillmentStepKind {
    RESERVE("锁定预占"),
    OUTBOUND("生成出库单"),
    WORK("编排作业"),
    TRACK("跟踪完成");

    private final String label;

    FulfillmentStepKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 阶梯序号：TRACK 之前每一步都必须完成过（V152 第 8.2 节）。 */
    public int rung() {
      return ordinal();
    }

    /** 从挂起恢复时该落回的状态：TRACK 复用 WORKING（跟踪与作业同一段进行中状态）。 */
    public FulfillmentRequestStatus resumeStatus() {
      return switch (this) {
        case RESERVE -> FulfillmentRequestStatus.RESERVING;
        case OUTBOUND -> FulfillmentRequestStatus.OUTBOUND_PENDING;
        case WORK, TRACK -> FulfillmentRequestStatus.WORKING;
      };
    }

    /** 该步的默认超时阈值（秒）。建单时可按租户口径覆盖。 */
    public int defaultTimeoutSeconds() {
      return switch (this) {
        case RESERVE -> 3600;
        case OUTBOUND -> 7200;
        case WORK -> 14400;
        case TRACK -> 86400;
      };
    }
  }

  /** 单步执行结果。append-only 表 _step 的状态词表。 */
  public enum FulfillmentStepStatus {
    PENDING("待执行"),
    RUNNING("执行中"),
    COMPLETED("已完成"),
    FAILED("已失败"),
    SKIPPED("已跳过");

    private final String label;

    FulfillmentStepStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public boolean terminal() {
      return this == COMPLETED || this == FAILED || this == SKIPPED;
    }
  }

  /**
   * 预占指令状态。缺省 {@code REQUESTED} 是**本模块最重要的一个默认值**：
   * 它表示「指令已登记，待 7.2 回执」，而不是「货已经锁住了」。
   */
  public enum FulfillmentReservationStatus {
    REQUESTED("待回执"),
    LOCKED("已锁定"),
    REJECTED("已被拒"),
    RELEASED("已释放");

    private final String label;

    FulfillmentReservationStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public boolean terminal() {
      return this != REQUESTED;
    }
  }

  /** 出库单指令状态（下游 8.3.1）。 */
  public enum FulfillmentOutboundStatus {
    REQUESTED("待回执"),
    CONFIRMED("已确认"),
    REJECTED("已被拒"),
    CANCELLED("已取消");

    private final String label;

    FulfillmentOutboundStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public boolean terminal() {
      return this != REQUESTED;
    }
  }

  /** 编排作业状态：ASSIGNED → RUNNING → DONE / FAILED / SKIPPED，不许跳过 RUNNING。 */
  public enum FulfillmentWorkStatus {
    ASSIGNED("已派工"),
    RUNNING("作业中"),
    DONE("已完成"),
    FAILED("已失败"),
    SKIPPED("已跳过");

    private final String label;

    FulfillmentWorkStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public boolean terminal() {
      return this == DONE || this == FAILED || this == SKIPPED;
    }
  }

  /** 作业类型：与 8.3.3 打包、8.3.4 交接对齐的分档（不建跨服务外键）。 */
  public enum FulfillmentWorkTaskType {
    PICK("拣货"),
    RECHECK("复核"),
    PACK("打包"),
    HANDOVER("交接");

    private final String label;

    FulfillmentWorkTaskType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 卡住的检出方式。三种都是**本模块在自己编排过程中能自己检出**的事实；
   * 跨请求的异常监控与人工处置入口判给 9.3.3.2。
   */
  public enum FulfillmentDetectionKind {
    TIMEOUT("超时未推进"),
    STEP_FAILED("步骤报失败"),
    STALE_ACK("回执陈旧");

    private final String label;

    FulfillmentDetectionKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 挂起原因码（与 DB 的 CHECK 词表一字对应）。 */
  public enum FulfillmentBlockReason {
    RESERVE_TIMEOUT("锁定预占超时"),
    RESERVE_REJECTED("预占被拒"),
    OUTBOUND_TIMEOUT("出库单超时"),
    OUTBOUND_REJECTED("出库单被拒"),
    WORK_TIMEOUT("作业编排超时"),
    TRACK_TIMEOUT("跟踪完成超时"),
    STALE_ACK("下游回执陈旧"),
    UPSTREAM_MISMATCH("与上游分仓 / 合单结果不符");

    private final String label;

    FulfillmentBlockReason(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 事件类型（下游消费用，append-only）。 */
  public enum FulfillmentEventKind {
    CREATED("已建单"),
    RESERVATION_REQUESTED("已发预占指令"),
    RESERVATION_LOCKED("预占已锁定"),
    RESERVATION_REJECTED("预占被拒"),
    OUTBOUND_REQUESTED("已发出库单指令"),
    OUTBOUND_CONFIRMED("出库单已确认"),
    OUTBOUND_REJECTED("出库单被拒"),
    WORK_ASSIGNED("已编排作业"),
    WORK_DONE("作业完成"),
    WORK_FAILED("作业失败"),
    BLOCKED("已挂起"),
    RETRY_REQUESTED("已请求重试"),
    RESUMED("已恢复"),
    COMPLETED("已完成"),
    CANCELLED("已取消"),
    NOTE("备注");

    private final String label;

    FulfillmentEventKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 能力声明

  /**
   * 单个上游 / 下游维度的可用性。
   *
   * @param key 维度键
   * @param label 中文名
   * @param available 是否真的接通了（false 时调用方必须自己读 reason，不许猜）
   * @param ownerModule 真实归属模块
   * @param reason 未接通的原因（available=false 时必填）
   */
  public record FulfillmentRequestDimension(
      @Schema(example = "WAREHOUSE_ALLOCATION") String key,
      @Schema(example = "分仓结果（发货仓 + 分配数量）") String label,
      boolean available,
      @Schema(example = "9.3.2.1 分仓规则") String ownerModule,
      String reason) {}

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>存在的理由：<b>下游三个全部未接通</b>，而这恰恰是最容易被误读的地方。
   * 界面若看到「已锁定预占」就以为货锁住了，那 warehouse 那边其实什么都没锁 ——
   * 这正是 9.3.2.1（V116）踩过并写进证据的坑。所以 {@code reservesUpstreamStock=false}、
   * {@code outboundOrderDispatched=false}、{@code logisticsOrderPlaced=false} 必须由接口自己说。
   */
  public record FulfillmentRequestCapabilities(
      List<FulfillmentRequestDimension> dimensions,
      @Schema(description = "本模块是否真的锁了 warehouse 的上游库存（恒为 false：只登记指令）")
          boolean reservesUpstreamStock,
      @Schema(description = "本模块是否真的把出库单派给了 8.3.1（恒为 false：只登记指令）")
          boolean outboundOrderDispatched,
      @Schema(description = "本模块是否真的下了物流单（恒为 false：运单主权在 9.4.1.1 / 8.3.4.2）")
          boolean logisticsOrderPlaced,
      @Schema(description = "流程状态是否持久化在 PostgreSQL（恒为 true：蓝图加粗句）")
          boolean processStatePersisted,
      @Schema(description = "9.3.3.2 履约流程监控是否交付（跨请求异常看板与人工处置入口）")
          boolean monitoringDelivered,
      List<FulfillmentRequestStatus> statuses,
      List<FulfillmentStepKind> steps,
      List<FulfillmentBlockReason> blockReasons,
      List<FulfillmentDetectionKind> detectionKinds,
      @Schema(description = "断点恢复与乐观并发怎么用") String processNote,
      @Schema(description = "下游边界为什么是「登记指令」而不是「直接调下游」") String downstreamNote,
      @Schema(description = "本模块与 9.3.3.2 的分工") String scopeNote) {}

  // ================================================================ 查询 / 分页

  /** 履约请求列表查询条件（全部落到真实 SQL 筛选，不是前端过滤）。 */
  public record FulfillmentRequestQuery(
      String requestCode,
      String orderRef,
      String warehouseRef,
      String skuRef,
      String platformCode,
      String splitUnitRef,
      String consolidationRef,
      String allocationDecisionRef,
      String status,
      String currentStep,
      @Schema(description = "只看已挂起的") Boolean blockedOnly,
      @Schema(description = "只看已超过当前步 deadline 的") Boolean overdueOnly,
      String keyword,
      Integer page,
      Integer size) {}

  /** 真分页一页（total 与分页无关，单条 count 得出）。 */
  public record FulfillmentRequestPage(
      List<FulfillmentRequestRow> items, long total, int page, int size) {}

  /** 履约请求列表行。 */
  public record FulfillmentRequestRow(
      UUID id,
      String requestCode,
      String orderRef,
      String orderLineRef,
      String splitUnitRef,
      String consolidationRef,
      String allocationDecisionRef,
      String routingDecisionRef,
      String skuRef,
      String platformCode,
      String warehouseRef,
      String legalEntityCode,
      String destinationCountryCode,
      String destinationRegionCode,
      BigDecimal requestedQty,
      BigDecimal estimatedHandlingAmount,
      String currencyCode,
      FulfillmentRequestStatus status,
      String statusLabel,
      FulfillmentStepKind currentStep,
      String currentStepLabel,
      long processVersion,
      int attempt,
      OffsetDateTime requestedAt,
      OffsetDateTime deadlineAt,
      OffsetDateTime lastStepAt,
      @Schema(description = "是否已超过当前步 deadline（按服务器时钟实时算，不落库）") boolean overdue,
      OffsetDateTime blockedAt,
      FulfillmentBlockReason blockedReasonCode,
      String blockedReasonCodeLabel,
      String blockedReason,
      OffsetDateTime completedAt,
      OffsetDateTime cancelledAt,
      String cancelReason,
      String notes) {}

  // ================================================================ 建单

  /** 建一张履约请求（一次履约单元）。 */
  public record FulfillmentRequestInput(
      @NotBlank String requestCode,
      @NotBlank String orderRef,
      String orderLineRef,
      @Schema(description = "9.3.1.1 拆单单元引用（该模块尚未交付，未接通）") String splitUnitRef,
      @Schema(description = "9.3.1.2 合单引用") String consolidationRef,
      @Schema(description = "9.3.2.1 分仓决策引用") String allocationDecisionRef,
      @Schema(description = "9.3.2.2 履约路径决策引用") String routingDecisionRef,
      @NotBlank String skuRef,
      String platformCode,
      @NotBlank String warehouseRef,
      @NotBlank String legalEntityCode,
      @NotBlank String destinationCountryCode,
      String destinationRegionCode,
      @NotNull BigDecimal requestedQty,
      BigDecimal estimatedHandlingAmount,
      String currencyCode,
      @Schema(description = "幂等键：重复建单只产生一张履约请求") String idempotencyKey,
      @Schema(description = "各步超时阈值（秒）；留空用租户缺省值") Integer reserveTimeoutSeconds,
      Integer outboundTimeoutSeconds,
      Integer workTimeoutSeconds,
      Integer trackTimeoutSeconds,
      String notes) {}

  /** 建单结果：回显 processVersion，调用方下一次推进必须带它。 */
  public record FulfillmentRequestSaved(
      UUID requestId,
      String requestCode,
      FulfillmentRequestStatus status,
      FulfillmentStepKind currentStep,
      long processVersion,
      int attempt,
      OffsetDateTime requestedAt,
      OffsetDateTime deadlineAt) {}

  // ================================================================ 详情

  /** 履约请求详情：头 + 四个下游凭据集合 + 超时记录 + 事件流（蓝图四项输出一次给全）。 */
  public record FulfillmentRequestDetail(
      FulfillmentRequestRow request,
      List<FulfillmentRequestStepRow> steps,
      List<FulfillmentRequestReservationRow> reservations,
      List<FulfillmentRequestOutboundRow> outboundOrders,
      List<FulfillmentRequestWorkOrderRow> workOrders,
      List<FulfillmentRequestTimeoutRow> timeouts,
      List<FulfillmentRequestEventRow> events) {}

  /** 流程状态记录（append-only）。 */
  public record FulfillmentRequestStepRow(
      UUID id,
      FulfillmentStepKind stepKind,
      String stepKindLabel,
      int attempt,
      FulfillmentStepStatus status,
      String statusLabel,
      UUID reservationId,
      UUID outboundOrderId,
      int workOrderCount,
      OffsetDateTime startedAt,
      OffsetDateTime finishedAt,
      BigDecimal elapsedSeconds,
      String failureCode,
      String failureReason,
      UUID operatedByMembershipId) {}

  /** 锁定预占指令（下游 7.2；本服务内登记）。 */
  public record FulfillmentRequestReservationRow(
      UUID id,
      String warehouseRef,
      String skuRef,
      BigDecimal requestedQty,
      FulfillmentReservationStatus status,
      String statusLabel,
      String reservationRef,
      BigDecimal lockedQty,
      String rejectReason,
      OffsetDateTime requestedAt,
      OffsetDateTime acknowledgedAt,
      OffsetDateTime releasedAt,
      String idempotencyKey,
      int attempt) {}

  /** 出库单指令（下游 8.3.1；本服务内登记）。 */
  public record FulfillmentRequestOutboundRow(
      UUID id,
      String warehouseRef,
      BigDecimal outboundQty,
      String outboundRef,
      String packageRef,
      FulfillmentOutboundStatus status,
      String statusLabel,
      String rejectReason,
      OffsetDateTime requestedAt,
      OffsetDateTime acknowledgedAt,
      OffsetDateTime cancelledAt,
      String idempotencyKey,
      int attempt) {}

  /** 编排作业。 */
  public record FulfillmentRequestWorkOrderRow(
      UUID id,
      String workOrderCode,
      FulfillmentWorkTaskType taskType,
      String taskTypeLabel,
      String warehouseRef,
      String skuRef,
      BigDecimal qty,
      FulfillmentWorkStatus status,
      String statusLabel,
      String assigneeRef,
      OffsetDateTime startedAt,
      OffsetDateTime finishedAt,
      String failureReason,
      int attempt) {}

  /** 超时 / 失败检出记录（append-only）。 */
  public record FulfillmentRequestTimeoutRow(
      UUID id,
      @Schema(description = "所属履约请求") UUID requestId,
      FulfillmentStepKind stepKind,
      String stepKindLabel,
      int attempt,
      FulfillmentDetectionKind detectionKind,
      String detectionKindLabel,
      OffsetDateTime dueAt,
      OffsetDateTime detectedAt,
      BigDecimal overdueSeconds,
      int thresholdSeconds,
      FulfillmentBlockReason reasonCode,
      String reasonCodeLabel,
      String reason,
      OffsetDateTime blockedAt) {}

  /** 事件流（append-only；下游消费）。 */
  public record FulfillmentRequestEventRow(
      UUID id,
      FulfillmentEventKind eventKind,
      String eventKindLabel,
      String fromStatus,
      String toStatus,
      long processVersion,
      String detail,
      UUID actorId,
      OffsetDateTime occurredAt) {}

  // ================================================================ 推进 / 重试 / 取消

  /**
   * 推进状态。
   *
   * <p>{@code expectedVersion} 是蓝图输入里点名的字段，<b>必填</b>：没有它，
   * 两个操作员同时点「确认」时后点的会把先点的结果覆盖掉，而流程会显示成只推进过一次。
   */
  public record FulfillmentRequestStatusInput(
      @NotNull FulfillmentRequestStatus status,
      @NotNull FulfillmentStepKind currentStep,
      @Schema(description = "蓝图输入里点名的乐观并发版本") @NotNull Long expectedVersion,
      @Schema(description = "该步的超时阈值（秒）") Integer stepTimeoutSeconds) {}

  /** 挂起（超时 / 步骤失败 / 回执陈旧）。 */
  public record FulfillmentRequestBlockInput(
      @NotNull FulfillmentStepKind stepKind,
      @NotNull FulfillmentDetectionKind detectionKind,
      @NotNull FulfillmentBlockReason reasonCode,
      @NotBlank String reason,
      @NotNull Long expectedVersion) {}

  /**
   * 重试 / 断点恢复。
   *
   * <p>{@code resumeReason} 必填且不可为空串：蓝图说「超时不是失败」，无理由的重试会变成重复发货。
   */
  public record FulfillmentRequestRetryInput(@NotNull Long expectedVersion, @NotBlank String resumeReason) {}

  /** 取消。 */
  public record FulfillmentRequestCancelInput(@NotNull Long expectedVersion, @NotBlank String reason) {}

  /** 状态推进 / 挂起 / 重试 / 取消的共同回显。 */
  public record FulfillmentRequestTransitionResult(
      UUID requestId,
      FulfillmentRequestStatus status,
      FulfillmentStepKind currentStep,
      long processVersion,
      int attempt,
      OffsetDateTime deadlineAt,
      OffsetDateTime blockedAt,
      String blockedReason) {}

  // ================================================================ 下游指令与回执

  /** 发出锁定预占指令。 */
  public record FulfillmentRequestReservationInput(
      @Schema(description = "留空则用本次尝试的默认键") String idempotencyKey) {}

  /** 登记 7.2 的预占回执。 */
  public record FulfillmentRequestReservationAckInput(
      @NotNull FulfillmentReservationStatus status,
      @Schema(description = "已锁定时必填：warehouse 回的凭据") String reservationRef,
      @Schema(description = "已锁定时必填") BigDecimal lockedQty,
      @Schema(description = "被拒 / 释放时必填") String rejectReason) {}

  /** 发出库单指令。 */
  public record FulfillmentRequestOutboundInput(
      @Schema(description = "留空则用本次尝试的默认键") String idempotencyKey,
      String packageRef) {}

  /** 登记 8.3.1 的出库单回执。 */
  public record FulfillmentRequestOutboundAckInput(
      @NotNull FulfillmentOutboundStatus status,
      @Schema(description = "已确认时必填：8.3.1 开的单号") String outboundRef,
      String packageRef,
      @Schema(description = "被拒 / 取消时必填") String rejectReason) {}

  /** 单条编排作业。 */
  public record FulfillmentRequestWorkTask(
      @NotBlank String workOrderCode,
      @NotNull FulfillmentWorkTaskType taskType,
      @NotBlank String warehouseRef,
      @NotBlank String skuRef,
      @NotNull BigDecimal qty,
      @Schema(description = "留空则按 requestId+workOrderCode 生成") String idempotencyKey) {}

  /** 批量编排作业。 */
  public record FulfillmentRequestWorkOrderInput(@NotNull List<FulfillmentRequestWorkTask> tasks) {}

  /** 登记作业状态。 */
  public record FulfillmentRequestWorkOrderStatusInput(
      @NotNull FulfillmentWorkStatus status,
      String assigneeRef,
      String failureReason) {}

  // ================================================================ 超时扫描

  /** 扫描超时请求的入参。 */
  public record FulfillmentRequestScanInput(
      @Schema(description = "只扫这一步（留空 = 当前步）") FulfillmentStepKind stepKind,
      @Schema(description = "最多处理多少条，防止一次扫描把库锁住") Integer limit) {}

  /** 扫描结果：逐条报出挂起与跳过，不给一个含糊的总数。 */
  public record FulfillmentRequestScanResult(
      int scanned,
      List<FulfillmentRequestBlocked> blocked,
      List<FulfillmentRequestSkipped> skipped,
      OffsetDateTime scannedAt) {

    /** 一条被挂起的请求。 */
    public record FulfillmentRequestBlocked(
            UUID requestId,
            String requestCode,
            FulfillmentStepKind stepKind,
            FulfillmentBlockReason reasonCode,
            BigDecimal overdueSeconds,
            long processVersion) {}

    /** 一条**没有**被挂起的请求，以及为什么没挂（已终态 / 未到阈值 / 版本被并发改过）。 */
    public record FulfillmentRequestSkipped(UUID requestId, String reason) {}
  }

  /** 超时记录一页（9.3.3.2 的数据来源，与履约请求分页形状不同）。 */
  public record FulfillmentRequestTimeoutPage(
      List<FulfillmentRequestTimeoutRow> items, long total, int page, int size) {}

  /** 可恢复清单（断点恢复入口）：全部 BLOCKED，按挂起时间倒序。 */
  public record FulfillmentRequestResumablePage(
      List<FulfillmentRequestRow> items, long total, int page, int size) {}
}
