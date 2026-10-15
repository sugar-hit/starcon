package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 9.4.1.2 发货记录的契约形状。
 *
 * <p>风格照抄同 L3 兄弟 9.4.1.1（{@code PendingShipmentModels}）：枚举与记录同文件，
 * 输入用 {@code @Valid} record，响应是不可变 record。
 */
final class ShipmentRecordModels {
  private ShipmentRecordModels() {}

  // ================================================================ 枚举

  /**
   * 发货记录状态机。
   *
   * <p>{@code SHIPPED} <b>不是</b>本模块拍脑袋置的：它由 8.3.4.1 已确认的交接单
   * 同步而来（见 {@code ShipmentHandoverQueryPort}）。9.4.1.1 刻意最远只到
   * HANDED_OVER，把这个事实留给本模块，正是为了让迟发率算在一个真的发生过
   * 的时刻上。
   */
  enum ShipmentRecordStatus {
    SHIPPED("已发货"),
    IN_TRANSIT("在途"),
    DELIVERED("已妥投"),
    RETURNED("已退回"),
    VOID("已作废");

    public final String displayName;

    ShipmentRecordStatus(String displayName) {
      this.displayName = displayName;
    }

    /** 终态不可再动（DB 触发器 {@code guard_shipment_record_state} 同口径）。 */
    public boolean terminal() {
      return this == DELIVERED || this == RETURNED || this == VOID;
    }
  }

  /**
   * 轨迹状态。
   *
   * <p>{@code UNSET} 与 {@code NO_TRACE} 是<b>两件事</b>，绝不合并：
   * UNSET = 还没登记过任何轨迹观测（10.3 未交付，这是当前常态）；
   * NO_TRACE = 登记过了，而承运商明确报「这一票没有轨迹」。
   * 把两者并成一个桶，就再也答不出「是没登记，还是登记了但承运商说没有」——
   * 而这正是有效追踪率申诉时要提交的那一句话。
   */
  enum ShipmentTrackingStatus {
    UNSET("未登记轨迹"),
    NO_TRACE("承运商无轨迹"),
    PICKED_UP("已揽收"),
    IN_TRANSIT("运输中"),
    CUSTOMS("清关中"),
    OUT_FOR_DELIVERY("派送中"),
    DELIVERED("已送达"),
    EXCEPTION("轨迹异常"),
    RETURNED("已退回");

    public final String displayName;

    ShipmentTrackingStatus(String displayName) {
      this.displayName = displayName;
    }

    /** {@code UNSET} 不是一种轨迹观测，只能作快照，不能被登记（登记的是 NO_TRACE 起）。 */
    public boolean observable() {
      return this != UNSET;
    }
  }

  /** 买家可见状态。没有上游可读，一律登记。 */
  enum BuyerVisibleStatus {
    UNSET("未登记"),
    VISIBLE("买家可见"),
    HIDDEN("对买家隐藏"),
    PUBLISHED("已向买家发布");

    public final String displayName;

    BuyerVisibleStatus(String displayName) {
      this.displayName = displayName;
    }
  }

  /**
   * 平台回传状态。
   *
   * <p>{@code UNSET} 与 {@code SKIPPED} 的区别是本枚举存在的唯一理由：
   * UNSET = 压根还没登记（<b>漏了</b>），SKIPPED = 我们<b>决定</b>不回传（<b>跳过</b>，
   * 必须写明原因）。两者在平台处罚上完全不同，混成一个桶就等于让「漏回传」
   * 伪装成「按策略不回传」。
   */
  enum PlatformCallbackStatus {
    UNSET("未登记"),
    PENDING("待回传"),
    SUBMITTED("已提交平台"),
    ACKNOWLEDGED("平台已受理"),
    REJECTED("平台拒绝"),
    SKIPPED("决定不回传");

    public final String displayName;

    PlatformCallbackStatus(String displayName) {
      this.displayName = displayName;
    }
  }

  /**
   * 观测 / 登记来源。
   *
   * <p>{@code CARRIER_FEED} 与 {@code CHANNEL_SERVICE} 是为 10 轨迹与
   * 2.5.1.1 落地后预留的值：它们在 DB CHECK 里已存在，但当前集成头里
   * 没有任何写入路径会用到 —— capabilities 里如实说明「等谁上线」。
   */
  enum ShipmentObservationSource {
    MANUAL_ATTESTATION("人工登记"),
    PLATFORM_FEED("平台回传"),
    CARRIER_FEED("承运商回调"),
    CHANNEL_SERVICE("平台对接服务");

    public final String displayName;

    ShipmentObservationSource(String displayName) {
      this.displayName = displayName;
    }
  }

  /** 买家可见状态的来源只允许前两个（不接承运商回调 —— 承运商不管买家可见性）。 */
  enum BuyerVisibilitySource {
    MANUAL_ATTESTATION("人工登记"),
    PLATFORM_FEED("平台回传");

    public final String displayName;

    BuyerVisibilitySource(String displayName) {
      this.displayName = displayName;
    }
  }

  /** 清单分组维度（三个维度都是真实的 GROUP BY，不是把一页在 Java 里数）。 */
  enum ShipmentRecordGroupBy {
    CARRIER("承运商"),
    TRACKING("轨迹状态"),
    PLATFORM_CALLBACK("平台回传状态");

    public final String displayName;

    ShipmentRecordGroupBy(String displayName) {
      this.displayName = displayName;
    }
  }

  // ================================================================ 能力声明

  /** 能力维度：哪些上游真读得到，哪些读不到（读不到就**显式报出**）。 */
  public record ShipmentRecordCapabilityDimension(
      String dimension,
      String description,
      boolean available,
      String source,
      String ownerModule,
      String impact) {}

  public record ShipmentRecordCapabilities(
      List<ShipmentRecordCapabilityDimension> dimensions,
      List<String> statuses,
      List<String> trackingStatuses,
      List<String> platformCallbackStatuses,
      List<String> buyerVisibleStatuses,
      List<String> groupByOptions,
      String shipmentFactBasis,
      String performanceBasis,
      String downstreamNote) {}

  // ================================================================ 同步

  /**
   * 从 8.3.4.1 授权只读接口同步已确认的交接单。
   *
   * <p>刻意只有 {@code pageSize} 一个参数：承运商 / 运单号 / 发货时间全部来自
   * 上游确认过的交接单，本模块**不接受调用方指定** —— 让调用方传发货时间等于
   * 允许把发货记录的时间改成任意时刻，迟发率会当场失去意义。
   */
  public record ShipmentRecordSyncInput(@Positive int pageSize) {}

  /** 同步结果：从 warehouse 授权 API 读到多少、落了多少。 */
  public record ShipmentRecordSyncResult(
      int fetchedHandovers,
      int insertedRecords,
      int updatedRecords,
      int skippedHandovers,
      int parcelRowsUpserted,
      int recordsWithOrderSnapshot,
      int recordsWithoutOrderSnapshot,
      int recordsWithoutSla,
      OffsetDateTime syncedAt,
      List<String> notes) {}

  // ================================================================ 清单与明细

  /** 看板一行 = 一个分组桶。计数是**真聚合**，不是页内统计。 */
  public record ShipmentRecordGroup(
      String groupKey, String groupLabel, long recordCount, int parcelCount, long exceptionCount) {}

  public record ShipmentRecordBoard(
      String groupBy, List<ShipmentRecordGroup> groups, ShipmentRecordSummary summary,
      List<String> boundaryNotes) {}

  /** 汇总**不受分页影响**（与 9.4.1.1 / 9.6.1.2 同一纪律）。 */
  public record ShipmentRecordSummary(
      long totalRecords,
      long shippedRecords,
      long inTransitRecords,
      long deliveredRecords,
      long returnedRecords,
      long voidRecords,
      int totalParcels,
      long unsetTrackingRecords,
      long exceptionTrackingRecords,
      long unsetCallbackRecords,
      long rejectedCallbackRecords,
      long skippedCallbackRecords,
      long unsetVisibilityRecords,
      long noSlaRecords) {}

  /**
   * 绩效口径（本模块是「平台绩效指标的计算依据」，迟发率与有效追踪率的定义写在这里，
   * 而不是散在前端或报表里 —— 同一个词两处定义，事后没人说得清哪个是真的）。
   */
  public record ShipmentPerformanceReport(
      long evaluatedRecords,
      long lateRecords,
      long onTimeRecords,
      long unsetSlaRecords,
      BigDecimal lateRate,
      BigDecimal onTimeRate,
      long trackableRecords,
      long untrackableRecords,
      BigDecimal validTrackingRate,
      String lateRateBasis,
      String validTrackingRateBasis,
      List<String> boundaryNotes) {}

  public record ShipmentRecordRow(
      UUID id,
      String recordNo,
      UUID sourceHandoverId,
      String sourceHandoverCode,
      String outboundOrderNo,
      UUID salesOrderId,
      String salesOrderNo,
      String shopRef,
      String buyerName,
      String destinationCountry,
      String warehouseRef,
      String carrierCode,
      String carrierName,
      String masterWaybillNo,
      OffsetDateTime shippedAt,
      OffsetDateTime slaDeadlineAt,
      int parcelCount,
      BigDecimal totalQuantity,
      BigDecimal totalGrossWeightG,
      ShipmentRecordStatus status,
      String statusLabel,
      ShipmentTrackingStatus trackingStatus,
      String trackingStatusLabel,
      BuyerVisibleStatus buyerVisibleStatus,
      String buyerVisibleStatusLabel,
      PlatformCallbackStatus platformCallbackStatus,
      String platformCallbackStatusLabel,
      boolean trackingObservationsPresent,
      boolean lateAgainstSla,
      OffsetDateTime syncedAt) {}

  public record ShipmentRecordDetail(
      ShipmentRecordRow record,
      List<ShipmentRecordParcelRow> parcels,
      List<ShipmentTrackingObservationRow> tracking,
      List<ShipmentVisibilityRow> visibility,
      List<ShipmentCallbackRow> callbacks,
      List<ShipmentRecordEventRow> timeline,
      List<String> boundaryNotes) {}

  public record ShipmentRecordPage(
      List<ShipmentRecordRow> items,
      int page,
      int size,
      long totalElements,
      int totalPages,
      ShipmentRecordSummary summary) {}

  public record ShipmentRecordParcelRow(
      UUID id,
      UUID recordId,
      int parcelNo,
      UUID sourceParcelId,
      String parcelCode,
      String waybillNo,
      BigDecimal grossWeightG,
      BigDecimal volumeCbm,
      int itemCount,
      BigDecimal totalQuantity,
      ShipmentTrackingStatus currentTrackingStatus,
      String currentTrackingStatusLabel,
      int observationCount) {}

  public record ShipmentTrackingObservationRow(
      UUID id,
      UUID recordId,
      UUID parcelId,
      int parcelNo,
      ShipmentTrackingStatus status,
      String statusLabel,
      String source,
      String observationRef,
      String detail,
      OffsetDateTime observedAt,
      OffsetDateTime createdAt) {}

  public record ShipmentVisibilityRow(
      UUID id,
      UUID recordId,
      BuyerVisibleStatus status,
      String statusLabel,
      String source,
      String note,
      OffsetDateTime registeredAt,
      UUID registeredByMembershipId) {}

  public record ShipmentCallbackRow(
      UUID id,
      UUID recordId,
      PlatformCallbackStatus status,
      String statusLabel,
      String source,
      String requestRef,
      String responseRef,
      String note,
      OffsetDateTime registeredAt,
      UUID registeredByMembershipId) {}

  public record ShipmentRecordEventRow(
      UUID id,
      UUID recordId,
      String eventKind,
      String fromStatus,
      String toStatus,
      String detail,
      OffsetDateTime occurredAt) {}

  // ================================================================ 输入

  /** 登记轨迹观测 —— 10.3 未交付，本模块只登记，不抓取。 */
  public record ShipmentTrackingObservationInput(
      @NotNull ShipmentTrackingStatus status,
      @NotNull ShipmentObservationSource source,
      // null = 整票级观测；给了就是逐箱观测。刻意标 nullable：
      // 不标的话 springdoc 会把它推成必填 string，前端就得为了传「整票」
      // 编一个假 UUID 上去。
      @Schema(nullable = true) UUID parcelId,
      @NotNull
          @Pattern(regexp = ".{4,200}", message = "观测依据至少 4 个字符")
          String observationRef,
      @Pattern(regexp = "^.{0,600}$", message = "备注最多 600 个字符")
          String detail,
      @NotNull OffsetDateTime observedAt) {}

  /** 登记买家可见状态。人工登记必须写依据（V127 第 4 节的 CHECK）。 */
  public record ShipmentVisibilityInput(
      @NotNull BuyerVisibleStatus status,
      @NotNull BuyerVisibilitySource source,
      @Pattern(regexp = "^.{0,400}$", message = "依据最多 400 个字符")
          String note,
      @NotNull OffsetDateTime registeredAt) {}

  /**
   * 登记平台回传状态 —— 9.4.2.1 / 2.5.1.1 未交付，本模块登记而非假装已回传。
   *
   * <p>{@code requestRef} 与 {@code responseRef} 分别对应 SUBMITTED 与
   * ACKNOWLEDGED 的举证凭据（DB CHECK 强制）。
   */
  public record ShipmentCallbackInput(
      @NotNull PlatformCallbackStatus status,
      @NotNull ShipmentObservationSource source,
      @Pattern(regexp = "^.{0,128}$", message = "请求凭据最多 128 个字符")
          String requestRef,
      @Pattern(regexp = "^.{0,128}$", message = "平台回执凭据最多 128 个字符")
          String responseRef,
      @Pattern(regexp = "^.{0,400}$", message = "说明最多 400 个字符")
          String note,
      @NotNull OffsetDateTime registeredAt) {}

  /** 推进 / 更正发货记录状态。终态不可再动，VOID 必须写明原因。 */
  public record ShipmentStatusInput(
      @NotNull ShipmentRecordStatus status,
      @NotBlank @Pattern(regexp = "^.{4,400}$", message = "状态变更依据至少 4 个字符")
          String reason) {}

  /** 事件流读取：真分页 + 真筛选。 */
  public record ShipmentRecordEventPage(
      List<ShipmentRecordEventRow> items, int page, int size, long totalElements, int totalPages) {}
}
