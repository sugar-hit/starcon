package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 16.5.1.2 风险评分的传输模型。
 *
 * <p>蓝图原文：「计算买家风险分：历史退款率、纠纷率、欺诈特征、地址模式、下单行为、关联账号，
 * 输出评分与建议动作。输入：买家行为数据；风险特征规则；权重配置。输出：风险评分 + 特征解释。
 * 关系：上游 = 15 客户、11 售后、9 订单；下游 = 16.5.1.1、9.2.2.2。
 * <b>评分要能解释</b>（为什么这个买家 80 分），黑盒分数客服无法向平台申诉。」
 *
 * <p>🔴 <b>本文件最重要的一条纪律：null 与 0 的区别在每个字段上都是实质的。</b>
 *
 * <ul>
 *   <li>{@code riskScore == null} ⇒ <b>信号不足，不出分</b>（租户选了 BLOCK_EVALUATION 口径），
 *       <b>不是</b>「这个买家 0 分风险」。
 *   <li>{@code normalizedValue == null} ⇒ <b>该特征量不到</b>，<b>不是</b>「该特征为 0」。
 *       量不到的特征<b>不进分母</b>、<b>不算通过</b>。
 *   <li>{@code contribution == null} ⇒ 这一条没参与计分（特征量不到，或整次评分没出分）。
 * </ul>
 *
 * <p>把这两种 null 折成 0，是评分系统最致命的一种撒谎：界面会显示「退款率 0%」，
 * 而真相是「我们没查到退款数据」—— 后者意味着「这买家干净」。
 */
public final class BuyerRiskScoreModels {

  private BuyerRiskScoreModels() {}

  // ================================================================ 能力声明

  /**
   * 逐维能力声明。
   *
   * @param available 该维度<b>现在量得到</b>吗（false ⇒ 评分里它不参与加权）
   * @param ownerModule 量不到时归谁（模块 ID）；量到了时是空串
   */
  public record BuyerRiskDimensionCapability(
      String dimensionCode,
      String dimensionLabel,
      String featureCode,
      boolean available,
      String source,
      String ownerModule,
      String impact) {}

  /** 逐个上游读面的可用性。读不到就**显式说读不到**，不返回空集合冒充「上游一个数据都没有」。 */
  public record BuyerRiskSourceState(
      String sourceCode, String sourceLabel, boolean available, String note) {}

  /** 能力声明。 */
  public record BuyerRiskCapabilities(
      List<BuyerRiskDimensionCapability> dimensions,
      List<BuyerRiskSourceState> sources,
      String subjectKeySemantics,
      String identityChoiceNote,
      String scoreSemantics,
      String missingSignalSemantics,
      String explainSemantics,
      String writebackNote,
      String downstreamNote,
      String boundaryNote) {}

  // ================================================================ 规则

  /**
   * 评分规则（一租户一条）。
   *
   * <p>🔴 <b>权重合计必须恰好 1.0000</b>（DB 层 CHECK 钉死，服务层也查一次）。
   * 🔴 <b>缺信号口径不给默认值</b>：{@code missingSignalPolicy} 必填，由租户显式选。
   */
  public record BuyerRiskRuleView(
      UUID ruleId,
      String ruleName,
      BigDecimal weightRefundHistory,
      BigDecimal weightDisputeHistory,
      BigDecimal weightFraudProfile,
      BigDecimal weightAddressPattern,
      BigDecimal weightOrderBehavior,
      String missingSignalPolicy,
      BigDecimal manualReviewThreshold,
      BigDecimal holdThreshold,
      BigDecimal refundRateFullRisk,
      BigDecimal fraudBlacklistedScore,
      BigDecimal fraudRiskLevelScore,
      BigDecimal fraudCaseFullRisk,
      BigDecimal addressSingleRatioThreshold,
      Integer orderBurstWindowHours,
      Integer orderBurstThreshold,
      BigDecimal orderFrequencyFullRisk,
      Integer orderLookbackDays,
      long version,
      boolean configured,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 规则登记 / 修改（upsert）。**缺信号口径必填**，不给默认。 */
  public record BuyerRiskRuleInput(
      @NotBlank @Size(max = 120) String ruleName,
      @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal weightRefundHistory,
      @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal weightDisputeHistory,
      @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal weightFraudProfile,
      @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal weightAddressPattern,
      @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal weightOrderBehavior,
      @NotNull @Pattern(regexp = "SCORE_KNOWN_ONLY|BLOCK_EVALUATION") String missingSignalPolicy,
      @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal manualReviewThreshold,
      @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal holdThreshold,
      @NotNull @DecimalMin("0.0001") @DecimalMax("1") BigDecimal refundRateFullRisk,
      @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal fraudBlacklistedScore,
      @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal fraudRiskLevelScore,
      @NotNull @DecimalMin("0.0001") @DecimalMax("1") BigDecimal fraudCaseFullRisk,
      @NotNull @DecimalMin("0") @DecimalMax("0.9999") BigDecimal addressSingleRatioThreshold,
      @NotNull @Min(1) @Max(720) Integer orderBurstWindowHours,
      @NotNull @Min(2) @Max(100) Integer orderBurstThreshold,
      @NotNull @DecimalMin("0.0001") BigDecimal orderFrequencyFullRisk,
      @NotNull @Min(1) @Max(3650) Integer orderLookbackDays,
      @Schema(nullable = true) Long version) {}

  // ================================================================ 特征明细

  /**
   * 一条特征的贡献明细。
   *
   * @param rawValue 原始值摘要（机器可读），例如 {@code orders=5;refunded=2;rate=0.400000}
   * @param normalizedValue 标准化到 0..1（1 = 风险最高）；量不到时是 null
   * @param weight 该维度的权重<b>快照</b>（从规则那一版抄下来的）
   * @param contribution 对总分的贡献分；量不到 / 整次没出分时是 null
   * @param reason 人话理由（必填 —— 没有理由的贡献分等于没有解释）
   * @param ownerModule 量不到时归谁
   */
  public record BuyerRiskFeatureView(
      UUID featureId,
      String dimensionCode,
      String dimensionLabel,
      String featureCode,
      String featureLabel,
      boolean available,
      String rawValue,
      @Schema(nullable = true) BigDecimal normalizedValue,
      BigDecimal weight,
      @Schema(nullable = true) BigDecimal contribution,
      String reason,
      String ownerModule,
      String note) {}

  /** 回写 9.2.2.2 风控信号的结果。 */
  public record BuyerRiskWritebackView(
      String featureCode,
      String signalCode,
      String subjectKey,
      BigDecimal measuredValue,
      String writebackStatus,
      String writebackNote,
      @Schema(nullable = true) OffsetDateTime attemptedAt) {}

  // ================================================================ 评分

  /** 一次评分的完整视图（头 + 逐特征明细 + 回写凭据）。 */
  public record BuyerRiskScoreView(
      UUID scoreId,
      String buyerKey,
      String buyerName,
      String buyerPhone,
      String buyerEmail,
      String buyerLevel,
      boolean blacklisted,
      String blacklistReason,
      @Schema(nullable = true) BigDecimal riskScore,
      String evaluationStatus,
      String recommendedAction,
      int scoredDimensionCount,
      int missingDimensionCount,
      UUID ruleId,
      long ruleVersion,
      String ruleName,
      String missingSignalPolicy,
      OffsetDateTime evaluatedReferenceAt,
      int ordersScanned,
      int orderCount,
      int refundedOrderCount,
      int lookbackDays,
      String conclusion,
      String note,
      OffsetDateTime createdAt,
      List<BuyerRiskFeatureView> features,
      List<BuyerRiskWritebackView> writebacks) {}

  /** 评分列表行（不带明细 —— 明细要单独点开）。 */
  public record BuyerRiskScoreSummary(
      UUID scoreId,
      String buyerKey,
      String buyerName,
      String buyerEmail,
      String buyerLevel,
      @Schema(nullable = true) BigDecimal riskScore,
      String evaluationStatus,
      String recommendedAction,
      int scoredDimensionCount,
      int missingDimensionCount,
      int orderCount,
      int refundedOrderCount,
      OffsetDateTime evaluatedReferenceAt,
      OffsetDateTime createdAt) {}

  /** 评分列表（真分页 + 与分页无关的汇总）。 */
  public record BuyerRiskScorePage(
      List<BuyerRiskScoreSummary> items,
      int page,
      int size,
      long total,
      BuyerRiskScoreSummaryCounts counts) {}

  /** 汇总计数：出分的 / 信号不足的 / 按建议动作分。 */
  public record BuyerRiskScoreSummaryCounts(
      long total, long scored, long insufficientSignals, long release, long manualReview, long hold) {}

  // ================================================================ 评估（评分运行）

  /**
   * 发起一次评分。
   *
   * @param buyerKey 只评一个买家时填（32 位十六进制）；留空 ⇒ 评「画像买家 ∪ 订单里发现的买家」
   */
  public record BuyerRiskEvaluateInput(@Schema(nullable = true) String buyerKey) {}

  /** 一次评分运行的结果。 */
  public record BuyerRiskEvaluateResult(
      OffsetDateTime evaluatedAt,
      String ruleName,
      long ruleVersion,
      String missingSignalPolicy,
      List<BuyerRiskSourceState> sources,
      int ordersScanned,
      int ordersUnresolved,
      int buyerCount,
      int scoredCount,
      int insufficientCount,
      List<BuyerRiskScoreView> scores) {}

  // ================================================================ 买家名册

  /**
   * 买家名册（来自 trade 的租户自维护买家画像）。
   *
   * @param blacklisted 是否在黑名单里（trade 侧登记的事实，**本模块不改它** —— 拉黑是 16.5.1.1 的职责）
   * @param latestScoreId 该买家最近一次评分（没有则 null）
   */
  public record BuyerRiskBuyerRow(
      String buyerKey,
      String buyerName,
      String buyerPhone,
      String buyerEmail,
      String buyerLevel,
      boolean blacklisted,
      String blacklistReason,
      @Schema(nullable = true) UUID latestScoreId,
      @Schema(nullable = true) BigDecimal latestRiskScore,
      @Schema(nullable = true) String latestEvaluationStatus,
      @Schema(nullable = true) String latestRecommendedAction) {}

  /** 买家名册（真分页）+ 读面可用性。 */
  public record BuyerRiskBuyerPage(
      boolean available,
      String note,
      List<BuyerRiskBuyerRow> items,
      int page,
      int size,
      long total) {}
}
