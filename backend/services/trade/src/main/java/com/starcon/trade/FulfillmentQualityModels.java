package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 9.5.2.1 履约质量的契约形状。
 *
 * <p>风格照抄同 L3 的 9.5.1.1（{@code OrderTimelinessModels}）：枚举与 record 同文件，
 * 输入用 {@code @Valid} record，响应是不可变 record。
 *
 * <p><b>比率契约（全文件最重要的一条）</b>：{@code rate} 是百分数
 * （{@code numeric(19,6)}，0~100 量纲），<b>分母为 0 时是 {@code null}</b>，
 * 绝不返回 0 或 1。分母为 0 的含义是「这个窗口里没有任何可判定样本」，
 * 报成 0 会被读成「及时率 0%」—— 那是结论，不是缺口。
 *
 * <p><b>六个指标的分子/分母写在枚举里</b>（{@link QualityMetric}）而不是散落在
 * 各处 SQL 注释：口径只有这一处定义，能力声明、指标卡、维度对比、下钻清单
 * 全部逐字引用同一段话。口径含糊的指标看板等于没有指标。
 */
final class FulfillmentQualityModels {
  private FulfillmentQualityModels() {}

  // ================================================================ 枚举

  /**
   * 六个质量指标 —— <b>分子/分母的口径只有这一处定义</b>。
   *
   * <p>{@code numerator} / {@code denominator} 是给人看的口径说明，
   * {@code clock} 是这一指标分子/分母各自圈窗口的时刻列。
   * 六个指标<b>刻意不共用一个时钟</b>：拿「提交时刻」去圈纠纷率，
   * 会把发货之后才发生的纠纷算进还没发货的单里。
   */
  enum QualityMetric {
    SHIP_ON_TIME_RATE(
        "发货及时率",
        "按时发出的货票数",
        "按时 + 超出发货时限的货票数（不含未登记时限）",
        "order_timeliness_fact.handed_over_at",
        Direction.HIGHER_IS_BETTER,
        "复用 9.5.1.1 已冻结的发货时限快照口径，不另起一套时限算法 —— 同一件事两个算法必然对不上"),

    COMPLETE_SHIPMENT_RATE(
        "完整发货率",
        "已发量 ≥ 下单量的订单行数",
        "有履约进度投影的订单行总数",
        "order_fulfillment_line_progress.shipped_at",
        Direction.HIGHER_IS_BETTER,
        "按**行**统计而不是按单：「10 行发全 9 行」与「10 行发全 10 行」压成同一个数会丢掉缺陷本身"),

    WRONG_OR_MISSING_RATE(
        "错发漏发率",
        "登记过错发或漏发的去重订单数",
        "窗口内有履约进度投影的去重订单数",
        "分子 exception.reported_at / 分母 progress.shipped_at",
        Direction.LOWER_IS_BETTER,
        "错发当前**无可读上游**：发货记录与运单只有件数与总量，没有 SKU 列，"
            + "「发出去的是不是订单行那个 SKU」在集成头里无法判定 ⇒ 错发只能登记；"
            + "漏发则可推导（已发量 < 下单量），两者分列报数不合并"),

    CANCELLATION_RATE(
        "取消率",
        "有已确认取消申请的去重订单数",
        "窗口内已提交订单的去重订单数",
        "分子 cancellation.decided_at / 分母 sales_order.submitted_at",
        Direction.LOWER_IS_BETTER,
        "只认 CONFIRMED：REQUESTED / PROCESSING / PENDING_VERIFICATION 都还可能被驳回，"
            + "把申请数当取消数会让取消率虚高一截"),

    DISPUTE_RATE(
        "纠纷率",
        "有纠纷信号的去重订单数",
        "窗口内有交接事实的去重订单数",
        "分子 signal.occurred_at / 分母 shipment_record.shipped_at",
        Direction.LOWER_IS_BETTER,
        "上游 11 售后在别的服务的独立库 ⇒ 当前读不到真实售后数据，只能读租户维护的信号表；"
            + "分母取「已发货订单」而不是全部订单：纠纷只可能发生在货已发出的单上"),

    RETURN_RATE(
        "退货率",
        "有退货信号的去重订单数",
        "与纠纷率完全相同的去重订单数",
        "分子 signal.occurred_at / 分母 shipment_record.shipped_at",
        Direction.LOWER_IS_BETTER,
        "与纠纷率**刻意分成两个指标**：两者是平台后台两个独立算法权重项，"
            + "合并之后答不出「压低账号安全权重的是纠纷还是退货」");

    public final String displayName;
    public final String numerator;
    public final String denominator;
    public final String clock;
    public final Direction direction;
    public final String caveat;

    QualityMetric(
        String displayName,
        String numerator,
        String denominator,
        String clock,
        Direction direction,
        String caveat) {
      this.displayName = displayName;
      this.numerator = numerator;
      this.denominator = denominator;
      this.clock = clock;
      this.direction = direction;
      this.caveat = caveat;
    }
  }

  /** 指标方向：及时/完整是越高越好，错发/取消/纠纷/退货是越低越好。 */
  enum Direction {
    HIGHER_IS_BETTER("越高越好"),
    LOWER_IS_BETTER("越低越好");

    public final String displayName;

    Direction(String displayName) {
      this.displayName = displayName;
    }
  }

  /**
   * 对比维度。
   *
   * <p>{@code PLATFORM} 就是蓝图说的「店铺」维度，但<b>只能按平台级
   * {@code order_source} 判</b>：{@code sales_order} 与 {@code shipment_record}
   * 都没有 {@code shop_code} 列（9.2.1.2 已核实）。
   *
   * <p>本模块<b>不为它 ALTER 既有表</b>（跨车道 DDL 回归面必须为 0），
   * 而是在能力声明里显式报 {@code shopCodeAvailable=false}。
   * D5 平台同步落地后由同步器补 {@code shop_code}，本枚举与统计逻辑都不用改。
   */
  enum QualityDimension {
    PLATFORM("店铺（平台级）"),
    PERSON("人员");

    public final String displayName;

    QualityDimension(String displayName) {
      this.displayName = displayName;
    }
  }

  /** 售后信号类型。纠纷与退货分列（见 {@link QualityMetric} 的 RETURN_RATE 说明）。 */
  enum AfterSaleSignalType {
    DISPUTE("纠纷"),
    RETURN("退货");

    public final String displayName;

    AfterSaleSignalType(String displayName) {
      this.displayName = displayName;
    }
  }

  /** 售后信号状态。纠纷与退货共用一个封闭词表（DB CHECK 钉死）。 */
  enum AfterSaleSignalState {
    OPEN("待处理"),
    UNDER_REVIEW("处理中"),
    RESOLVED("已解决"),
    REJECTED("已驳回"),
    CLOSED("已关闭");

    public final String displayName;

    AfterSaleSignalState(String displayName) {
      this.displayName = displayName;
    }
  }

  /**
   * 履约缺陷类型。错发与漏发分列：平台算法里是完全不同的权重项。
   *
   * <p>{@code WRONG_ITEM} 当前<b>只能租户登记</b>（无 SKU 级上游，见 V155 第二节）。
   */
  enum FulfillmentExceptionType {
    WRONG_ITEM("错发"),
    MISSING_ITEM("漏发");

    public final String displayName;

    FulfillmentExceptionType(String displayName) {
      this.displayName = displayName;
    }
  }

  /** 阈值比较方向。必须显式：方向反了阈值会反过来报警。 */
  enum AlertComparison {
    GT("大于"),
    GTE("大于等于"),
    LT("小于"),
    LTE("小于等于");

    public final String displayName;

    AlertComparison(String displayName) {
      this.displayName = displayName;
    }
  }

  /** 预警级别。CRITICAL 供 16 账号健康对接时消费（16 未交付，现阶段只落留痕）。 */
  enum AlertSeverity {
    INFO("提示"),
    WARN("警告"),
    CRITICAL("严重");

    public final String displayName;

    AlertSeverity(String displayName) {
      this.displayName = displayName;
    }
  }

  /** 明细下钻的排序白名单。刻意不开放「任意列名」。 */
  enum QualitySort {
    OCCURRED_AT_DESC("判定时刻（新→旧）"),
    OCCURRED_AT_ASC("判定时刻（旧→新）"),
    ORDER_NO_ASC("订单号升序"),
    QUANTITY_DESC("数量降序");

    public final String displayName;

    QualitySort(String displayName) {
      this.displayName = displayName;
    }
  }

  // ================================================================ 输入

  /** 登记一条纠纷 / 退货信号（11 售后未交付，逐笔登记而非伪造读取）。 */
  record AfterSaleSignalInput(
      @NotNull UUID orderId,
      @Schema(description = "发生时刻（不是登记时刻）") @NotNull OffsetDateTime occurredAt,
      @NotNull AfterSaleSignalType signalType,
      AfterSaleSignalState signalState,
      @Schema(description = "平台侧单号；平台同步到位后才有值")
          String platformOrderNo,
      @Schema(description = "非 TENANT_MAINTAINED 来源时必填") String sourceReference,
      String note) {}

  /** 登记一次错发 / 漏发（举证凭据，append-only）。 */
  record FulfillmentExceptionInput(
      @NotNull UUID orderId,
      UUID orderLineId,
      @Schema(description = "与 orderLineId 同时给或同时不给") Integer lineNo,
      String skuCode,
      @NotNull FulfillmentExceptionType exceptionType,
      @Schema(description = "错发时为实际发出量，漏发时为少发量；可空") BigDecimal affectedQty,
      @NotNull OffsetDateTime reportedAt,
      String sourceReference,
      String note) {}

  /** 预警阈值配置（幂等 upsert：一个指标一条生效规则）。 */
  record AlertRuleInput(
      @Schema(description = "百分数，0~100，与指标卡同量纲") @NotNull BigDecimal thresholdValue,
      @NotNull AlertComparison comparison,
      AlertSeverity severity,
      @Schema(description = "缺省 true") Boolean enabled,
      String note) {}

  /** 预警评估请求。窗口与指标卡用同一套归一化。 */
  record AlertEvaluateInput(
      @Schema(description = "评估窗口起；缺省取默认窗口") OffsetDateTime from,
      @Schema(description = "评估窗口止；缺省取默认窗口") OffsetDateTime to,
      @Schema(description = "逐维度评估；缺省只评估租户整体") Boolean perDimension) {}

  // ================================================================ 响应

  /** 能力与上下游可用性。 */
  record QualityCapabilities(
      List<QualityCapabilityDimension> dimensions,
      List<String> metrics,
      List<String> metricDefinitions,
      List<String> supportedDimensions,
      String rateScale,
      String rateNullRule,
      String shopDimensionStatus,
      String downstreamStatus) {}

  /** 单个能力维度（含 available=false 的缺口，点名 ownerModule）。 */
  record QualityCapabilityDimension(
      String code,
      String displayName,
      boolean available,
      String unavailableReason,
      String ownerModule,
      String handling) {}

  /** 归一化后的统计窗口。回显让调用方能核对「我传的到底是哪一段」。 */
  record QualityWindow(String from, String to, boolean defaulted) {}

  /**
   * 一张指标卡。
   *
   * <p>{@code numerator} 与 {@code denominator} <b>必须与 rate 同时出现</b>：
   * 只给一个百分数而不给分母，这个数字就没法用来做任何判断。
   *
   * <p>{@code excludedFromDenominator} 报「刻意不进分母」的样本数
   * （如未登记发货时限），避免把「没测过」读成「做得好」。
   */
  record QualityMetricCard(
      String metricCode,
      String displayName,
      String numerator,
      String denominator,
      BigDecimal rate,
      long numeratorCount,
      long denominatorCount,
      long excludedFromDenominator,
      boolean unavailable,
      String unavailableReason,
      String direction,
      String clock,
      String definition,
      String caveat,
      String sourceDetailRef) {}

  /** 指标卡总览。 */
  record QualityReport(
      List<QualityMetricCard> metrics,
      QualityWindow window,
      long shippedOrdersInWindow,
      long submittedOrdersInWindow,
      String scope,
      String caveat) {}

  /** 维度对比的一行：同一维度值下**六个指标全量**给出，便于横向比。 */
  record QualityDimensionRow(
      String dimensionCode,
      String dimensionKey,
      String dimensionLabel,
      long ordersInScope,
      List<QualityMetricCard> metrics,
      String caveat) {}

  /** 维度对比结果。 */
  record QualityDimensionPage(
      String dimensionCode,
      String dimensionDisplayName,
      List<QualityDimensionRow> items,
      int dimensionCount,
      String caveat) {}

  /** 下钻明细的一行。同一张订单可能同时出现在分子与分母里（role 区分）。 */
  record QualityDrilldownItem(
      UUID orderId,
      String orderNo,
      String orderSource,
      String shopRef,
      String assigneeRef,
      String metricRole,
      String classification,
      String classificationLabel,
      OffsetDateTime occurredAt,
      BigDecimal quantity,
      BigDecimal requiredQuantity,
      String detailKind,
      String detailRef,
      String detailNo) {}

  /** 下钻明细清单（真分页 + 汇总）。 */
  record QualityDrilldownPage(
      String metricCode,
      String metricDisplayName,
      String definition,
      List<QualityDrilldownItem> items,
      int page,
      int size,
      long total,
      int totalPages,
      long numeratorCount,
      long denominatorCount,
      BigDecimal rate,
      String caveat) {}

  /** 售后信号的一行。 */
  record AfterSaleSignalRow(
      UUID id,
      UUID orderId,
      String orderNo,
      String signalType,
      String signalState,
      String platformOrderNo,
      OffsetDateTime occurredAt,
      String signalSource,
      String sourceReference,
      OffsetDateTime capturedAt,
      String note) {}

  /** 售后信号清单（真分页 + 汇总）。 */
  record AfterSaleSignalPage(
      List<AfterSaleSignalRow> items, int page, int size, long total, int totalPages, long disputeCount,
      long returnCount) {}

  /** 履约缺陷登记的一行。 */
  record FulfillmentExceptionRow(
      UUID id,
      UUID orderId,
      String orderNo,
      String orderSource,
      UUID orderLineId,
      String skuCode,
      String exceptionType,
      BigDecimal affectedQty,
      OffsetDateTime reportedAt,
      String reportedSource,
      String sourceReference,
      String note) {}

  /** 履约缺陷清单（真分页 + 汇总）。 */
  record FulfillmentExceptionPage(
      List<FulfillmentExceptionRow> items,
      int page,
      int size,
      long total,
      int totalPages,
      long wrongItemCount,
      long missingItemCount) {}

  /** 阈值规则的一行。 */
  record AlertRuleRow(
      UUID id,
      String metricCode,
      String metricDisplayName,
      BigDecimal thresholdValue,
      String comparison,
      String severity,
      boolean enabled,
      String note,
      OffsetDateTime updatedAt) {}

  /** 预警记录的一行。 */
  record AlertRow(
      UUID id,
      String metricCode,
      String metricDisplayName,
      BigDecimal observedValue,
      BigDecimal thresholdValue,
      String comparison,
      String severity,
      long numerator,
      long denominator,
      String windowFrom,
      String windowTo,
      String dimensionCode,
      String dimensionValue,
      OffsetDateTime triggeredAt,
      boolean acknowledged) {}

  /** 预警清单（真分页）。 */
  record AlertPage(
      List<AlertRow> items, int page, int size, long total, int totalPages, long openCount) {}

  /** 事件流的一行。 */
  record QualityEventRow(
      long seq, String kind, String summary, UUID orderId, UUID alertId, OffsetDateTime occurredAt) {}

  /** 事件流清单（真分页）。 */
  record QualityEventPage(
      List<QualityEventRow> items, int page, int size, long total, int totalPages) {}

  /** 预警评估结果。 */
  record AlertEvaluateResult(
      int evaluated, int triggered, int skippedNoBasis, String windowFrom, String windowTo,
      boolean perDimension) {}
}
