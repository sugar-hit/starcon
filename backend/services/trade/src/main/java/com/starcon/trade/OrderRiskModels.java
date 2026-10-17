package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
 * 9.2.2.2 风控校验的传输模型。
 *
 * <p>蓝图原话：「多信号风险打分：买家历史（退款率、纠纷率）、设备与 IP 关联（16）、下单频次、
 * 金额异常、地址集中度；输出风险分与建议动作。」
 *
 * <p><b>四条建模纪律</b>：
 *
 * <ul>
 *   <li><b>分数与动作是两个字段，而且建议动作不是最终动作。</b>
 *       {@link RiskAssessment#riskScore}（0..100，四位刻度）与
 *       {@link RiskAssessment#recommendedAction}（放行 / 人工 / 拒发）由 V150 的触发器从五个组件
 *       实算；{@link RiskAssessment#finalAction} 只能来自 {@link RiskReview} —— 蓝图加粗句
 *       「风控分数不能单独作为拒绝依据」在传输层同样分得清清楚楚。
 *   <li><b>「没测到」不是 0。</b>{@link RiskComponent#rawValue} 在该维不可用时是 {@code null}，
 *       {@link RiskComponent#normalizedScore} 是 0 且 {@link RiskComponent#status} 说明为什么。
 *   <li><b>缺失的维度也占一行。</b>{@link RiskDetail#components} 恒为 5 行：少一行的话
 *       「这单为什么没按预期打分」就只能从总分反推，而总分不保留是谁缺席的。
 *   <li><b>金额与分数全程 BigDecimal</b>，分别对齐 DB 的 numeric(19,4) 与 numeric(9,4)。
 * </ul>
 */
public final class OrderRiskModels {

  private OrderRiskModels() {}

  // ------------------------------------------------------------ 枚举

  /** 五个风险维度（DB 词表，一字不改）。 */
  public enum RiskDimension {
    BUYER_HISTORY("买家历史（退款率 / 纠纷率）"),
    DEVICE_IP_LINK("设备与 IP 关联"),
    ORDER_FREQUENCY("下单频次"),
    AMOUNT_ANOMALY("金额异常"),
    ADDRESS_CONCENTRATION("地址集中度");

    private final String label;

    RiskDimension(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 单个维度的可用状态。
   *
   * <p>把「不可用」拆成四种而不是一个 null，是为了让「这维为什么没分」在详情页上是四句不同的话：
   * 没登记、登记了但过期、历史单不够、这一单形态上不适用 —— 处置动作两两不同。
   */
  public enum RiskComponentStatus {
    SCORED("已计分"),
    MISSING_SIGNAL("缺信号（上游未接通或租户未登记）"),
    STALE_SIGNAL("信号已过期"),
    INSUFFICIENT_HISTORY("历史笔数不足"),
    NOT_APPLICABLE("该单形态上不适用");

    private final String label;

    RiskComponentStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * **建议动作**（DB 词表）。
   *
   * <p>三个值都只是「建议」：{@link #HOLD_SHIPMENT} 的语义是「建议拒发，需人工复核」，
   * 它不等于已经拒发 —— 真正的拒发要等 {@link RiskReview} 落一条人工结论。
   */
  public enum RecommendedAction {
    RELEASE("放行"),
    MANUAL_REVIEW("人工复核"),
    HOLD_SHIPMENT("建议拒发");

    private final String label;

    RecommendedAction(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 评估状态：出了分，还是因为信号不足没出分。 */
  public enum EvaluationStatus {
    SCORED("已出分"),
    INSUFFICIENT_SIGNALS("信号不足，未出分");

    private final String label;

    EvaluationStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 缺信号时怎么办（租户可维护口径）。 */
  public enum MissingSignalPolicy {
    /** 按已有维度归一化后打分。 */
    SCORE_KNOWN_ONLY("按已知维度打分"),
    /** 任一维度缺信号就不出分，只给「建议人工」。 */
    BLOCK_EVALUATION("缺任一维度即不出分");

    private final String label;

    MissingSignalPolicy(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 信号码（DB 词表）。前两个来自 15 客户，后三个来自 16 合规。 */
  public enum SignalCode {
    REFUND_RATE("退款率"),
    DISPUTE_RATE("纠纷率"),
    DEVICE_IP_LINK_COUNT("设备与 IP 关联数"),
    SHARED_DEVICE_COUNT("共用设备数"),
    SHARED_IP_COUNT("共用 IP 数");

    private final String label;

    SignalCode(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * **信号来源**（DB 词表，照抄 V116 stock_source / V92 authorization_source 的既有解法）。
   *
   * <p>名称刻意是「卷 + 关注点」而不是伪造的 L4 模块号：15 / 16 里目前没有任何一个 L4 模块
   * 是退款率画像或设备 IP 关联的交付方，编一个号会让后来的人去查一个不存在的模块。
   */
  public enum SignalSource {
    TENANT_MAINTAINED("租户自维护"),
    MODULE_15_CUSTOMER_PROFILE("15 客户行为画像（未交付）"),
    MODULE_16_RISK_LINKAGE("16 合规关联风险（未交付）"),
    /** 真 SQL 维度（频次 / 金额 / 地址）从本服务订单簇现算，不是登记来的。 */
    DERIVED_FROM_ORDERS("本服务订单簇现算");

    private final String label;

    SignalSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 信号挂在谁身上：catalog 客户档案的逻辑 ID，或本服务现算的买家指纹。 */
  public enum SubjectKind {
    CUSTOMER_PARTY("客户档案 ID"),
    BUYER_FINGERPRINT("买家指纹（收货地址快照 md5）");

    private final String label;

    SubjectKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 测量单位：比率还是计数。 */
  public enum MeasurementUnit {
    RATE("比率（0..1）"),
    COUNT("计数");

    private final String label;

    MeasurementUnit(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 最终动作：只可能来自人工复核。 */
  public enum FinalAction {
    SHIP("发货"),
    REJECT("拒发");

    private final String label;

    FinalAction(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 人工复核结论。 */
  public enum ReviewDecision {
    SHIP("复核后发货"),
    REJECT("复核后拒发"),
    DEFERRED("转延期，等材料"),
    ESCALATED("升级合规人工");

    private final String label;

    ReviewDecision(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 复核原因码。FALSE_POSITIVE 是「误伤正常复购客户」在数据里的正式名字。 */
  public enum ReviewReasonCode {
    CONFIRMED_RISK("复核确认有风险"),
    FALSE_POSITIVE("误判，疑似正常复购客户"),
    PENDING_EVIDENCE("仍需材料"),
    ESCALATED_COMPLIANCE("升级合规处理");

    private final String label;

    ReviewReasonCode(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ------------------------------------------------------------ 能力声明

  /**
   * 能力与上下游可用性。
   *
   * <p>存在的理由：蓝图的上游是 15 客户与 16 合规，而这两卷在集成头
   * <b>15 只有 15.4.1.1 已交付（在 crm 服务的独立库里）、16 一个都没交付</b>。
   * 「哪几维真的接通、哪几维是租户自己维护的」必须由接口自己说，
   * 而不是让界面从空数组里猜哪一环没通。
   */
  @Schema(description = "9.2.2.2 风控校验能力与上下游可用性")
  public record RiskCapabilities(
      @Schema(description = "五个风险维度的可用性") List<RiskCapabilityDimension> dimensions,
      @Schema(description = "下游 9.2.1.3") RiskCapabilityDownstream downstream,
      @Schema(description = "本租户是否已登记风控规则") boolean ruleConfigured,
      @Schema(description = "是否已接入 15 客户画像（跨服务读表一律禁止）") boolean crossServiceReadAvailable,
      @Schema(description = "五维权重合计恒为 1.0000，判定顺序见 scoringOrder") String weightCheck,
      @Schema(description = "打分的判定顺序原文") String scoringOrder,
      @Schema(description = "上游缺口原文") String gaps) {}

  /**
   * 单个风险维度的可用性。
   *
   * <p>⚠️ 刻意**不叫 {@code Dimension}**：warehouse 的 capabilities 也有一个
   * {@code Dimension}（code / label / available / source / unavailableReason），
   * 而 codegen 把各服务的 schema 合并进同一个桶文件 —— 直接生成会把 warehouse 的
   * 那份打掉、打红它的 workspace。加模块前缀是既有解法（照抄 15.4.1.1 把
   * KnowledgeEntry 改名成 CrmKnowledgeEntry 那次）。
   */
  @Schema(description = "单个风险维度的可用性")
  public record RiskCapabilityDimension(
      @Schema(description = "维度码") String code,
      @Schema(description = "中文名") String label,
      @Schema(description = "数据来源原文") String source,
      @Schema(description = "是否真接通（false = 租户维护或上游未交付）") boolean upstreamDelivered,
      @Schema(description = "权重取自规则表的哪一列") String weightSource,
      @Schema(description = "缺口原文；已接通为 null") String gap) {}

  @Schema(description = "下游可用性")
  public record RiskCapabilityDownstream(
      @Schema(description = "模块号") String moduleId,
      @Schema(description = "名称") String name,
      @Schema(description = "是否已交付") boolean available,
      @Schema(description = "交付前本模块做到哪一步") String handoff) {}

  // ------------------------------------------------------------ 规则

  /** 风控规则读取形态。 */
  @Schema(description = "风控规则（每租户一条）")
  public record RiskRule(
      UUID id,
      String ruleName,
      boolean enabled,
      BigDecimal weightBuyerHistory,
      BigDecimal weightDeviceIpLink,
      BigDecimal weightOrderFrequency,
      BigDecimal weightAmountAnomaly,
      BigDecimal weightAddressConcentration,
      BigDecimal manualReviewThreshold,
      BigDecimal holdShipmentThreshold,
      int frequencyWindowHours,
      int frequencyMaxOrders,
      int deviceLinkMaxLinks,
      BigDecimal amountAnomalyRatio,
      int amountAnomalyMinOrders,
      int addressConcentrationWindowHours,
      int addressConcentrationMaxOrders,
      int signalMaxAgeHours,
      MissingSignalPolicy missingSignalPolicy,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 登记 / 更新规则。权重合计必须 = 1.0000，否则被 DB 拒绝。 */
  @Schema(description = "登记风控规则（upsert；权重合计必须等于 1.0000）")
  public record RiskRuleInput(
      @NotBlank @Size(max = 120) String ruleName,
      @NotNull @DecimalMin("0.0000") @DecimalMax("1.0000") BigDecimal weightBuyerHistory,
      @NotNull @DecimalMin("0.0000") @DecimalMax("1.0000") BigDecimal weightDeviceIpLink,
      @NotNull @DecimalMin("0.0000") @DecimalMax("1.0000") BigDecimal weightOrderFrequency,
      @NotNull @DecimalMin("0.0000") @DecimalMax("1.0000") BigDecimal weightAmountAnomaly,
      @NotNull @DecimalMin("0.0000") @DecimalMax("1.0000") BigDecimal weightAddressConcentration,
      @NotNull @DecimalMin("0.0000") @DecimalMax("100.0000") BigDecimal manualReviewThreshold,
      @NotNull @DecimalMin("0.0001") @DecimalMax("100.0000") BigDecimal holdShipmentThreshold,
      @Min(1) @Max(24 * 365) int frequencyWindowHours,
      @Min(1) @Max(100000) int frequencyMaxOrders,
      @Min(1) @Max(1000000) int deviceLinkMaxLinks,
      @NotNull @DecimalMin("1.0001") @DecimalMax("100.0000") BigDecimal amountAnomalyRatio,
      @Min(2) @Max(100000) int amountAnomalyMinOrders,
      @Min(1) @Max(24 * 365) int addressConcentrationWindowHours,
      @Min(2) @Max(1000000) int addressConcentrationMaxOrders,
      @Min(1) @Max(24 * 365 * 3) int signalMaxAgeHours,
      @NotNull MissingSignalPolicy missingSignalPolicy) {}

  // ------------------------------------------------------------ 信号

  /** 风险信号读取形态。 */
  @Schema(description = "风险信号（租户可维护；每行带来源与采集时刻）")
  public record RiskSignal(
      UUID id,
      SignalCode signalCode,
      RiskDimension dimension,
      MeasurementUnit measurementUnit,
      SubjectKind subjectKind,
      String subjectKey,
      BigDecimal measuredValue,
      SignalSource signalSource,
      String sourceReference,
      OffsetDateTime capturedAt,
      String note,
      OffsetDateTime createdAt) {}

  /** 登记一条风险信号。 */
  @Schema(description = "登记一条风险信号（采集时刻必填：那是信号被测出来的时刻，不是登记时刻）")
  public record RiskSignalInput(
      @NotNull SignalCode signalCode,
      @NotNull SubjectKind subjectKind,
      @NotBlank
          @Size(max = 200)
          @Pattern(
              regexp = "^[0-9a-fA-F-]{32,36}$",
              message = "主体键必须是 UUID（客户档案 ID）或 32 位十六进制买家指纹")
          String subjectKey,
      @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal measuredValue,
      @NotNull SignalSource signalSource,
      @Size(max = 128) String sourceReference,
      @NotNull OffsetDateTime capturedAt,
      @Size(max = 2000) String note) {}

  /** 信号列表分页（真分页 + 真搜索）。 */
  @Schema(description = "风险信号分页")
  public record RiskSignalPage(
      List<RiskSignal> items, long total, int page, int size, int totalPages) {}

  // ------------------------------------------------------------ 评估

  /** 一行一个维度的计分明细。 */
  @Schema(description = "单维计分明细（恒 5 行；缺失维度也在）")
  public record RiskComponent(
      UUID id,
      RiskDimension dimension,
      RiskComponentStatus status,
      @Schema(description = "该维权重（恒等于规则里的权重，即使是缺失维）")
          BigDecimal weight,
      @Schema(nullable = true, description = "原始值；不可用时为 null（不是 0）") BigDecimal rawValue,
      @Schema(description = "归一化分 0..100；不可用时为 0") BigDecimal normalizedScore,
      @Schema(description = "贡献 = 权重 × 归一化分") BigDecimal contribution,
      SignalSource signalSource,
      @Schema(nullable = true) OffsetDateTime signalCapturedAt,
      @Schema(description = "为什么是现在这个结论") String explanation) {}

  /** 评估头。 */
  @Schema(description = "风控评估头")
  public record RiskAssessment(
      UUID id,
      UUID orderId,
      String orderNo,
      @Schema(nullable = true) String buyerFingerprint,
      String orderCurrency,
      BigDecimal orderAmount,
      OffsetDateTime orderSubmittedAt,
      UUID ruleId,
      String ruleName,
      long ruleVersion,
      OffsetDateTime evaluatedReferenceAt,
      @Schema(nullable = true, description = "总分 0..100（4 位刻度）；信号不足未出分时为 null")
          BigDecimal riskScore,
      RecommendedAction recommendedAction,
      @Schema(description = "是否需要人工复核（建议拒发恒为 true）") boolean manualReviewRequired,
      EvaluationStatus evaluationStatus,
      int scoredDimensionCount,
      int missingDimensionCount,
      @Schema(nullable = true, description = "最终动作；只能来自人工复核，未复核时为 null")
          FinalAction finalAction,
      @Schema(nullable = true) OffsetDateTime reviewedAt,
      @Schema(nullable = true) UUID reviewedBy,
      @Schema(nullable = true) OffsetDateTime totalsComputedAt,
      @Schema(nullable = true) UUID createdBy,
      OffsetDateTime createdAt) {}

  /** 评估详情：头 + 五行组件 + 人工复核留痕。 */
  @Schema(description = "风控评估详情（头 + 五维明细 + 人工复核）")
  public record RiskDetail(
      RiskAssessment assessment,
      List<RiskComponent> components,
      @Schema(nullable = true, description = "人工复核留痕；尚未复核时为 null") RiskReview review) {}

  /** 评估列表分页（真分页 + 真搜索）。 */
  @Schema(description = "风控评估分页")
  public record RiskAssessmentPage(
      List<RiskAssessment> items, long total, int page, int size, int totalPages, Summary summary) {}

  /** 列表页顶部的汇总数（不受筛选与分页影响）。 */
  @Schema(description = "列表汇总（不受分页影响）")
  public record Summary(
      long total,
      long scoredCount,
      long insufficientSignalCount,
      long pendingReviewCount,
      long releasedCount,
      long manualReviewCount,
      long holdShipmentCount,
      @Schema(description = "缺任一维的评估数") long incompleteCount) {}

  /** 触发一次评估的请求体。 */
  @Schema(description = "触发一次风控评估")
  public record AssessmentInput(
      @NotNull UUID orderId,
      @Size(max = 2000) String note) {}

  /** 评估落库结果。 */
  @Schema(description = "评估落库结果（分数与建议动作由 DB 触发器实算后回读）")
  public record AssessmentSaved(RiskAssessment assessment, List<RiskComponent> components, String summary) {}

  // ------------------------------------------------------------ 人工复核

  /** 人工复核记录。 */
  @Schema(description = "人工复核记录（风控分数唯一的出口）")
  public record RiskReview(
      UUID id,
      UUID assessmentId,
      ReviewDecision decision,
      UUID reviewedBy,
      OffsetDateTime reviewedAt,
      ReviewReasonCode reasonCode,
      String note) {}

  /** 提交人工复核。 */
  @Schema(description = "提交人工复核（拒发的唯一合法入口）")
  public record RiskReviewInput(
      @NotNull ReviewDecision decision,
      @NotNull ReviewReasonCode reasonCode,
      @Size(max = 2000) String note) {}

  /** 复核落库结果。 */
  @Schema(description = "复核落库结果（最终动作由 DB 触发器回填后回读）")
  public record ReviewSaved(RiskAssessment assessment, RiskReview review, String summary) {}

  // ------------------------------------------------------------ 查询参数

  /** 评估列表查询条件。 */
  public record AssessmentQuery(
      String keyword,
      String recommendedAction,
      String evaluationStatus,
      Boolean pendingReviewOnly,
      String buyerFingerprint,
      int page,
      int size) {}

  /** 信号列表查询条件。 */
  public record SignalQuery(
      String keyword,
      String signalCode,
      SignalSource signalSource,
      String subjectKey,
      int page,
      int size) {}
}
