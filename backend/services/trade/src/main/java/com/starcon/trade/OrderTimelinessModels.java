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
 * 9.5.1.1 时效分析的契约形状。
 *
 * <p>风格照抄同服务已交付的 9.4.1.2（{@code ShipmentRecordModels}）：枚举与记录同文件，
 * 输入用 {@code @Valid} record，响应是不可变 record。
 *
 * <p><b>时长口径</b>：本文件里所有「时长」都是 <b>整数秒</b>（{@code Long}），
 * 唯独均值是 {@link BigDecimal}（DB 的 {@code avg(bigint)} 返回 numeric）。
 * 刻意没有任何浮点类型 —— 时效是平台算法权重项，浮点累加的误差会一路带到
 * 阈值判定上，而那时没人说得清差在哪。
 */
final class OrderTimelinessModels {
  private OrderTimelinessModels() {}

  // ================================================================ 枚举

  /** 时效段。报表按这四段逐段出统计，每段独立成桶。 */
  enum TimelinessSegment {
    ORDER_TO_PACK("下单到出库", "order_to_pack_seconds"),
    PACK_TO_HANDOVER("出库到交接", "pack_to_handover_seconds"),
    ORDER_TO_HANDOVER("下单到交接", "order_to_handover_seconds"),
    PROMISE_VS_DELIVERY("承诺到货 vs 实际到货", "promise_vs_delivery_seconds");

    public final String displayName;

    /** 事实表上承载这一段的<b>整数秒列名</b>（白名单，不接受调用方指定列）。 */
    public final String column;

    TimelinessSegment(String displayName, String column) {
      this.displayName = displayName;
      this.column = column;
    }
  }

  /** 拆解维度。商品维度是唯一走订单行只读 join 的一维（V131 第五节）。 */
  enum TimelinessDimension {
    SHOP("店铺"),
    WAREHOUSE("仓库"),
    CARRIER("承运商"),
    SKU("商品");

    public final String displayName;

    TimelinessDimension(String displayName) {
      this.displayName = displayName;
    }
  }

  /**
   * 「承诺到货 vs 实际到货」的四值结论。
   *
   * <p>{@code NO_PROMISE} 与 {@code NO_ACTUAL} <b>刻意不合并</b>（V131 第三节）：
   * 合并之后「压根没登记承诺」会伪装成「货还在路上」，而这二者在平台申诉里
   * 需要的举证材料完全不同 —— 前者要平台 SLA，后者要轨迹签收。
   */
  enum PromiseVsDeliveryState {
    MET("达成承诺"),
    LATE("超出承诺"),
    NO_PROMISE("未登记承诺到货"),
    NO_ACTUAL("未登记实际到货");

    public final String displayName;

    PromiseVsDeliveryState(String displayName) {
      this.displayName = displayName;
    }
  }

  /** 发货时限的三值判定：没登记时限既不算准时也不算超时。 */
  enum ShipSlaState {
    ON_TIME("按时发货"),
    OVERDUE("超出发货时限"),
    UNSET("未登记发货时限");

    public final String displayName;

    ShipSlaState(String displayName) {
      this.displayName = displayName;
    }
  }

  /** 承诺到货的登记来源。四件套互斥由 DB CHECK 钉死。 */
  enum TimelinessPromiseSource {
    MANUAL_ATTESTATION("人工登记"),
    PLATFORM_FEED("平台回传");

    public final String displayName;

    TimelinessPromiseSource(String displayName) {
      this.displayName = displayName;
    }
  }

  /** 实际到货的登记结果。货还在路上时唯一诚实的表达是没登记（NO_ACTUAL）。 */
  enum TimelinessDeliveryState {
    DELIVERED("已送达"),
    NOT_DELIVERED("未送达");

    public final String displayName;

    TimelinessDeliveryState(String displayName) {
      this.displayName = displayName;
    }
  }

  /** 清单排序白名单。刻意不开放「任意列名」。 */
  enum TimelinessSort {
    HANDOVER_AT_DESC("交接时刻（新→旧）"),
    HANDOVER_AT_ASC("交接时刻（旧→新）"),
    OVERDUE_DESC("超时秒数（大→小）"),
    OVERDUE_ASC("超时秒数（小→大）"),
    ORDER_TO_HANDOVER_DESC("下单到交接（大→小）"),
    FACT_NO_ASC("事实号（升序）");

    public final String displayName;

    TimelinessSort(String displayName) {
      this.displayName = displayName;
    }
  }

  // ================================================================ 能力声明

  /**
   * 一个能力维度。
   *
   * @param key 维度键
   * @param label 人可读名
   * @param available 是否有可读上游
   * @param reason {@code available=false} 时说明**为什么**拿不到
   * @param ownerModule 缺口归属模块（{@code available=false} 时点名）
   * @param handling 本模块的处理方式（登记 / 独立桶 / 只读 join …）
   */
  record OrderTimelinessCapabilityDimension(
      @Schema(example = "ORDER_TO_PACK") String key,
      String label,
      boolean available,
      String reason,
      String ownerModule,
      String handling) {}

  record OrderTimelinessCapabilities(
      List<OrderTimelinessCapabilityDimension> dimensions,
      List<String> segments,
      List<String> dimensionsBy,
      List<String> promiseVsDeliveryStates,
      List<String> shipSlaStates,
      List<String> sorts,
      String durationUnit,
      String definition,
      String caveat) {}

  // ================================================================ 同步

  /** 同步入参：只接分页游标，**不接受**调用方指定任何时刻。 */
  record TimelinessSyncInput(
      @Schema(description = "每页条数（上限 500）") @Positive Integer pageSize) {}

  record TimelinessSyncResult(
      boolean sourcesAvailable,
      int scanned,
      int inserted,
      int updated,
      int unchanged,
      int withOrderedAt,
      int withoutOrderedAt,
      int withPackedAt,
      int withoutPackedAt,
      int withoutOrderSnapshot,
      int skippedTimeInversion,
      String note) {}

  // ================================================================ 事实清单

  /** 一行时效事实。三个时刻 + 五个整数秒时长 + 四个维度。 */
  record OrderTimelinessFactRow(
      UUID id,
      String factNo,
      UUID sourceRecordId,
      String sourceRecordNo,
      UUID salesOrderId,
      String salesOrderNo,
      String shopRef,
      String warehouseRef,
      String carrierCode,
      String carrierName,
      OffsetDateTime orderedAt,
      OffsetDateTime packedAt,
      OffsetDateTime handedOverAt,
      Long orderToPackSeconds,
      Long packToHandoverSeconds,
      Long orderToHandoverSeconds,
      OffsetDateTime shipSlaDeadlineAt,
      String shipSlaSource,
      Long shipOverdueSeconds,
      String shipSlaState,
      OffsetDateTime promisedDeliveryAt,
      String promiseSource,
      OffsetDateTime actualDeliveredAt,
      String deliveryState,
      Long promiseVsDeliverySeconds,
      String promiseVsDeliveryState,
      OffsetDateTime syncedAt) {}

  record OrderTimelinessFactPage(
      List<OrderTimelinessFactRow> items,
      int page,
      int size,
      long total,
      int totalPages,
      TimelinessFactSummary summary) {}

  /**
   * 汇总条。<b>不受分页影响</b>（同一套 WHERE 另跑一次聚合），
   * 且每一项都带分母：没有分母的比率没法判断「没登记」还是「真准时」。
   */
  record TimelinessFactSummary(
      long totalFacts,
      long withOrderedAt,
      long withoutOrderedAt,
      long withPackedAt,
      long withoutPackedAt,
      long withOrderSnapshot,
      long withoutOrderSnapshot,
      long shipSlaUnset,
      long onTime,
      long overdue,
      long promiseNoPromise,
      long promiseNoActual,
      long promiseMet,
      long promiseLate,
      long unmeasuredTotalToPack,
      long unmeasuredPackToHandover,
      long unmeasuredPromiseVsDelivery) {}

  // ================================================================ 报表

  /**
   * 单段时效统计。
   *
   * @param avgSeconds 均值（DB 的 {@code avg(bigint)}，numeric，**不是**浮点）
   * @param p50Seconds 中位数（{@code percentile_disc}，离散精确值）
   * @param p90Seconds 90 分位（{@code percentile_disc}）
   * @param negativeCount 时间倒流的行数（时长为负，数据问题，正常恒为 0）
   */
  record TimelinessSegmentStats(
      String segment,
      String segmentLabel,
      long measuredCount,
      long unsetCount,
      BigDecimal avgSeconds,
      Long p50Seconds,
      Long p90Seconds,
      Long minSeconds,
      Long maxSeconds,
      long negativeCount) {}

  /** 时效报表。 */
  record OrderTimelinessReport(
      List<TimelinessSegmentStats> segments,
      long totalFacts,
      String windowFrom,
      String windowTo,
      TimelinessSlaBreakdown sla,
      TimelinessPromiseBreakdown promise,
      String caveat) {}

  record TimelinessSlaBreakdown(
      long unset, long onTime, long overdue, BigDecimal overdueRate, String rateBasis) {}

  record TimelinessPromiseBreakdown(
      long met,
      long late,
      long noPromise,
      long noActual,
      BigDecimal metRate,
      BigDecimal lateSecondsAvg,
      String rateBasis) {}

  // ================================================================ 分维度对比

  /** 一个维度分组的统计行。四个维度共用这一个形状，避免四套几乎一样的 DTO。 */
  record TimelinessDimensionRow(
      String dimension,
      String dimensionKey,
      String dimensionLabel,
      long factCount,
      long measuredCount,
      long unsetCount,
      BigDecimal avgOrderToPackSeconds,
      BigDecimal avgPackToHandoverSeconds,
      BigDecimal avgOrderToHandoverSeconds,
      Long worstOrderToHandoverSeconds,
      Long worstOverdueSeconds,
      long overdueCount,
      long withoutOrderSnapshot) {}

  record TimelinessDimensionPage(
      TimelinessDimension dimension,
      String dimensionLabel,
      List<TimelinessDimensionRow> items,
      int total,
      String caveat) {}

  // ================================================================ 超时明细

  /** 超时明细行：发货超时 + 承诺超时两个来源，合成一张可排序的清单。 */
  record TimelinessOverdueRow(
      UUID factId,
      String factNo,
      String salesOrderNo,
      String shopRef,
      String warehouseRef,
      String carrierCode,
      String carrierName,
      OffsetDateTime handedOverAt,
      Long orderToHandoverSeconds,
      String overdueKind,
      Long overdueSeconds,
      OffsetDateTime deadlineAt,
      String promiseVsDeliveryState) {}

  record TimelinessOverduePage(
      List<TimelinessOverdueRow> items,
      int page,
      int size,
      long total,
      int totalPages,
      long shipOverdueCount,
      long promiseLateCount,
      long unmeasuredPromiseCount) {}

  // ================================================================ 明细 / 凭据

  record TimelinessPromiseCredential(
      UUID id,
      OffsetDateTime promisedDeliveryAt,
      String source,
      String note,
      UUID registeredByMembershipId,
      OffsetDateTime registeredAt) {}

  record TimelinessDeliveryCredential(
      UUID id,
      OffsetDateTime deliveredAt,
      String state,
      String source,
      String note,
      UUID registeredByMembershipId,
      OffsetDateTime registeredAt) {}

  record OrderTimelinessEventRow(
      long seq,
      UUID factId,
      String kind,
      String summary,
      UUID actorMembershipId,
      OffsetDateTime occurredAt) {}

  record OrderTimelinessEventPage(
      List<OrderTimelinessEventRow> items, int page, int size, long total, int totalPages) {}

  /** 明细：事实 + 订单行（商品维度）+ 全部凭据 + 时间线。 */
  record OrderTimelinessDetail(
      OrderTimelinessFactRow fact,
      List<OrderTimelinessOrderLine> orderLines,
      List<TimelinessPromiseCredential> promises,
      List<TimelinessDeliveryCredential> deliveries,
      List<OrderTimelinessEventRow> timeline,
      List<String> boundaries) {}

  /** 订单行（只读 join 同服务 sales_order_line，V131 第五节）。 */
  record OrderTimelinessOrderLine(
      UUID skuId, String skuCode, String skuName, String specSnapshot, BigDecimal quantity) {}

  // ================================================================ 登记入参

  /**
   * 登记承诺到货。
   *
   * @param promisedDeliveryAt 承诺到货时刻（必填：不登记就没有「未登记承诺」这个桶）
   * @param note 更正上一条时的依据
   */
  record TimelinessPromiseInput(
      @NotNull OffsetDateTime promisedDeliveryAt,
      @NotBlank
          @Pattern(
              regexp = "MANUAL_ATTESTATION|PLATFORM_FEED",
              message = "来源只能是 MANUAL_ATTESTATION 或 PLATFORM_FEED")
          String source,
      @Schema(description = "更正上一条时的依据（可空）") String note) {}

  /**
   * 登记实际到货。
   *
   * @param deliveredAt 签收时刻（NOT_DELIVERED 时表示「已确认未送达」的时刻）
   */
  record TimelinessDeliveryInput(
      @NotNull OffsetDateTime deliveredAt,
      @NotBlank
          @Pattern(
              regexp = "DELIVERED|NOT_DELIVERED",
              message = "状态只能是 DELIVERED 或 NOT_DELIVERED")
          String state,
      @NotBlank
          @Pattern(
              regexp = "MANUAL_ATTESTATION|PLATFORM_FEED",
              message = "来源只能是 MANUAL_ATTESTATION 或 PLATFORM_FEED")
          String source,
      @Schema(description = "未送达时必填：拒收 / 退回 / 丢失是三件不同的事")
          String note) {}
}
