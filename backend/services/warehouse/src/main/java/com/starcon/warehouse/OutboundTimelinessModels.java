package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 8.5.3.1 出库时效的传输模型。
 *
 * <p>蓝图原话：「<b>统计出库各环节时效：订单到出库时长、拣货时长、打包时长、交接时长，识别超时环节与瓶颈。</b>」输入：「订单时间戳；各作业环节时间戳。」输出：「<b>时效分布 + 瓶颈环节 + 超时明细</b>。」以及本模块被点名的红线：「<b>时效数据直接对应平台迟发率指标</b>，超时会导致绩效违规与搜索降权。」
 *
 * <p><b>本模块只有两个「自己的」表，其余四环节全部在读既有事实：</b>
 *
 * <ul>
 *   <li>{@code outbound_order_time_snapshot}（V148 表①）—— 订单下单时刻的<b>本服务投影</b>。下单时刻属 9 订单（trade 服务），本服务<b>读不到</b>（禁止跨服务 SQL），所以由 trade 投递到本服务的写端点。本车道是该表的<b>唯一写入所有者</b>。与 8.5.1.1（V74）处理上游未交付时的路线 A 完全同构。
 *   <li>{@code outbound_timeliness_threshold}（V148 表②）—— 四环节阈值，<b>租户默认 + 仓库覆盖</b>两级。蓝图把时效挂在平台迟发率上，而迟发率按平台/店铺/仓库都不同，所以阈值必须是可配数据而不是写死常量。
 *   <li>拣货 / 打包 / 交接三个环节的<b>时间戳已在 V110 / V121 / V123 里</b>，本模块<b>只读不投影</b>：再抄一份只会让它比它描述的作业更先过期（同 8.5.2.1 的判断）。
 * </ul>
 *
 * <p><b>缺投影 = 不可计算，不是 0。</b>没拿到下单时刻时，「订单到出库时长」是 {@code null}，并计入 {@code missingSampleCounts} 单独报出。理由：这个模块的数字直接决定绩效申诉与平台降权判断，<b>编一个看起来能用的数比没有这个数危险得多</b> —— 编出来的「及时效」会让人以为迟发率被管住了。
 *
 * <p><b>负时长不可计算（null），不夹成 0。</b>交接时刻早于建单时刻是数据错误，夹成 0 会让「时间戳错了」显示成「这一单超快」。
 *
 * <p><b>瓶颈只看内部三环节。</b>订单到出库是总时长，恒大于其任一环节；把它算进瓶颈排名，「瓶颈永远是订单到出库」这条信息恒为零。报告里总时长单列。
 *
 * <p><b>四样输出一个不少</b>（蓝图点了三样，分布/瓶颈/明细之外还给出样本完整度）：时效分布（{@link OutboundTimelinessReport#distribution()}）、瓶颈环节（{@link OutboundTimelinessReport#bottleneck()}）、超时明细（{@link OutboundTimelinessReport#overdue()} 真分页）。
 */
public final class OutboundTimelinessModels {

  private OutboundTimelinessModels() {}

  /** 时效统计的时区口径。固定 Asia/Shanghai，不跟服务器本地时区走（跨境电商报表的可复算性前提）。 */
  public static final String TIMEZONE = "Asia/Shanghai";

  // ------------------------------------------------------------ 枚举

  /**
   * 出库时效的四个环节 —— 蓝图逐字点名的四个，不是四个「我顺手加的」。
   *
   * <p>枚举顺序即报告里的默认展示顺序：订单到出库（总时长）在前，三个内部环节在后。 {@link #isInternal()} 决定谁进瓶颈排名。
   */
  public enum OutboundTimelinessStage {
    ORDER_TO_OUTBOUND(
        "订单到出库时长",
        "从订单下单到出库单发货的墙钟时长。它是**总时长**，不是某个环节的用时。",
        false),
    PICKING(
        "拣货时长", "拣货任务从开工到完成的墙钟时长（pick_task.started_at → completed_at）。", true),
    PACKING(
        "打包时长", "打包单从开工到完成的墙钟时长（packing_order.started_at → completed_at）。", true),
    HANDOVER(
        "交接时长",
        "发货交接单从建单到实际交接给承运商的墙钟时长（shipment_handover.created_at → handover_at）。",
        true);

    private final String label;
    private final String description;
    private final boolean internal;

    OutboundTimelinessStage(String label, String description, boolean internal) {
      this.label = label;
      this.description = description;
      this.internal = internal;
    }

    public String label() {
      return label;
    }

    public String description() {
      return description;
    }

    /**
     * 是否是「内部环节」。
     *
     * <p>只有内部三环节参与瓶颈排名：订单到出库是它们之和的近似，恒为最大， 参与排名等于把「瓶颈永远是订单到出库」这个无信息量的结论固定下来。
     */
    public boolean isInternal() {
      return internal;
    }
  }

  /** 阈值来源：回答「这个阈值是谁定的」。 */
  public enum ThresholdSource {
    WAREHOUSE("仓库覆盖", "该仓库自己配的，优先级最高"),
    TENANT("租户默认", "该租户所有仓库共用的默认档"),
    BUILTIN("内置兜底", "该租户与该仓库都还没有配置，用的是本模块声明的兜底值");

    private final String label;
    private final String description;

    ThresholdSource(String label, String description) {
      this.label = label;
      this.description = description;
    }

    public String label() {
      return label;
    }

    public String description() {
      return description;
    }
  }

  // ------------------------------------------------------------ 输入

  /**
   * 投影订单下单时刻。
   *
   * <p>{@code orderPlacedAt} 必填且<b>不接受 null</b>：本服务读不到 trade 的订单表， 这个时刻只能由上游投过来 —— 兜底成 now() 等于给每一单编一个「及时效」。
   *
   * <p>{@code outboundOrderId} 是出库单 id 而不是订单号：分仓/分批会让<b>一张订单有多张出库单</b>，而时效必须逐出库单算（同一个订单从 A 仓发和从 B 仓发是两条不同的履约承诺）。
   */
  public record OutboundOrderTimeInput(
      UUID outboundOrderId,
      String sourceType,
      OffsetDateTime orderPlacedAt,
      String sourceModule) {}

  /**
   * 配置阈值。
   *
   * <p>{@code warehouseId} 为空 = 写租户默认档；不为空 = 写该仓覆盖档（优先级更高）。 两者都走幂等 upsert —— 同一个 (租户, 仓库, 环节) 只有一行，改阈值是改它而不是插第二条。
   *
   * <p>{@code stageCode} 单条配置：一次只配一个环节。四个环节一起提交会让「哪条配错了」 无法定位，而阈值出错的后果是绩效申诉。
   */
  public record OutboundThresholdInput(
      @Schema(nullable = true) UUID warehouseId,
      String stageCode,
      BigDecimal thresholdMinutes,
      @Schema(nullable = true) Boolean enabled,
      @Schema(nullable = true) String note) {}

  // ------------------------------------------------------------ 输出

  /**
   * 一个出库单的四环节时效（一行 = 一张出库单）。
   *
   * @param orderToOutboundMinutes 订单到出库墙钟分钟数；缺下单时刻投影或尚未发货时为 null
   * @param pickingMinutes 拣货墙钟分钟数；未完成或无拣货任务时为 null
   * @param packingMinutes 打包墙钟分钟数；未完成或无打包单时为 null
   * @param handoverMinutes 交接墙钟分钟数；尚未交接时为 null
   * @param overdueStages 超时的环节代号列表（已按环节顺序去重）；超时判定用**生效阈值**，不是内置兜底
   * @param pickCompletedAt 拣货完成时刻：超时明细要能定位到「哪一单、哪一环、什么时候」， 只给分钟数答不出「这次迟发发生在几点」
   * @param packCompletedAt 打包完成时刻
   * @param handoverAt 实际交接给承运商的时刻
   */
  public record OutboundTimelinessRow(
      UUID outboundOrderId,
      String orderCode,
      String sourceType,
      @Schema(nullable = true) String externalOrderRef,
      UUID warehouseId,
      String warehouseCode,
      @Schema(nullable = true) BigDecimal orderToOutboundMinutes,
      @Schema(nullable = true) BigDecimal pickingMinutes,
      @Schema(nullable = true) BigDecimal packingMinutes,
      @Schema(nullable = true) BigDecimal handoverMinutes,
      List<String> overdueStages,
      long overdueStageCount,
      @Schema(nullable = true) OffsetDateTime shippedAt,
      @Schema(nullable = true) OffsetDateTime orderPlacedAt,
      @Schema(nullable = true) OffsetDateTime pickCompletedAt,
      @Schema(nullable = true) OffsetDateTime packCompletedAt,
      @Schema(nullable = true) OffsetDateTime handoverAt) {}

  /** 超时明细的一行 —— 必须能定位到<b>具体订单 + 具体环节</b>（蓝图：时效直接对应平台迟发率）。 */
  public record OutboundOverdueDetail(
      UUID outboundOrderId,
      String orderCode,
      String stageCode,
      String stageLabel,
      UUID warehouseId,
      String warehouseCode,
      @Schema(nullable = true) BigDecimal actualMinutes,
      BigDecimal thresholdMinutes,
      String thresholdSource,
      @Schema(nullable = true) BigDecimal excessMinutes,
      @Schema(nullable = true) OffsetDateTime occurredAt) {}

  /** 阈值明细：数值 + 来源 + 口径说明。 */
  public record OutboundThresholdView(
      String stageCode,
      String stageLabel,
      BigDecimal thresholdMinutes,
      String source,
      String sourceLabel,
      @Schema(nullable = true) Boolean enabled,
      @Schema(nullable = true) String note) {}

  /** 时效分布里的一个分位桶。 */
  public record OutboundDistributionBucket(String bucket, long orderCount, BigDecimal share) {}

  /**
   * 单个环节的分布与超时统计（分布是蓝图的明文输出之一，不能只给平均时长）。
   *
   * @param sampledCount 走到该环节终点的样本数（分母）。<b>没有样本时 {@code avgMinutes} 为 null</b>，不是 0
   * @param overdueCount 超时单数；{@code overdueRate} 分母为 0 时为 null
   * @param missingOrderCount 该环节终点未发生的单数（还没发货 / 还没打包完）—— 单独报，不混进分母
   */
  public record OutboundStageDistribution(
      String stageCode,
      String stageLabel,
      String unit,
      long sampledCount,
      long missingOrderCount,
      BigDecimal coverageRate,
      @Schema(nullable = true) BigDecimal avgMinutes,
      @Schema(nullable = true) BigDecimal p50Minutes,
      @Schema(nullable = true) BigDecimal p90Minutes,
      @Schema(nullable = true) BigDecimal maxMinutes,
      BigDecimal thresholdMinutes,
      String thresholdSource,
      long overdueCount,
      @Schema(nullable = true) BigDecimal overdueRate,
      List<OutboundDistributionBucket> buckets) {}

  /**
   * 瓶颈环节。
   *
   * @param stageCode 瓶颈环节；候选为空时为 null（区间内没有任何环节有样本）
   * @param basis 判定依据的逐字说明 —— 瓶颈是<b>由阈值算出来的</b>，不是拍脑袋的，必须能被复核
   */
  public record OutboundBottleneck(
      @Schema(nullable = true) String stageCode,
      @Schema(nullable = true) String stageLabel,
      @Schema(nullable = true) BigDecimal avgMinutes,
      BigDecimal thresholdMinutes,
      String thresholdSource,
      @Schema(nullable = true) BigDecimal overdueRate,
      long overdueCount,
      long sampledCount,
      String basis) {}

  /** 超时明细分页（真分页；total 是该筛选下的全量行数）。 */
  public record OutboundOverduePage(List<OutboundOverdueDetail> items, long total, int page, int size) {}

  /**
   * 出库时效报表（分布 + 瓶颈 + 超时明细三件齐全）。
   *
   * @param rows 逐单明细（本区间内已发货的出库单）
   * @param distribution 四环节分布
   * @param bottleneck 瓶颈环节
   * @param overdue 超时明细分页
   * @param thresholds 本次生效的阈值（数值 + 来源），与分布/超时判定同源
   * @param missingSampleCounts 各环节「终点未发生」的单数，{@code key} 为 stageCode
   * @param overdueOrderCount 至少有一个环节超时的单数（超时单率的分母是已发货单数）
   */
  public record OutboundTimelinessReport(
      LocalDate from,
      LocalDate to,
      String timezone,
      String dayBasis,
      @Schema(nullable = true) String warehouseFilter,
      @Schema(nullable = true) String warehouseFilterLabel,
      long shippedOrderCount,
      long overdueOrderCount,
      @Schema(nullable = true) BigDecimal overdueOrderRate,
      List<OutboundThresholdView> thresholds,
      List<OutboundStageDistribution> distribution,
      OutboundBottleneck bottleneck,
      OutboundOverduePage overdue,
      List<OutboundTimelinessRow> rows,
      long total,
      int page,
      int size,
      java.util.Map<String, Long> missingSampleCounts) {}

  /** 能力与口径声明。 */
  public record OutboundTimelinessCapabilities(
      List<OutboundStageOption> stages,
      List<OutboundThresholdView> builtinThresholds,
      List<OutboundThresholdView> effectiveThresholds,
      @Schema(nullable = true) UUID warehouseId,
      String timezone,
      String stageBasisNote,
      String wallClockBasisNote,
      String missingSampleBasisNote,
      String bottleneckBasisNote,
      String overdueBasisNote,
      String orderTimestampSourceNote,
      String upstreamNote,
      String ownershipNote) {}

  /** 环节选项（前端据此渲染，不把枚举硬编码在界面上）。 */
  public record OutboundStageOption(String value, String label, String description, boolean internal) {}
}
