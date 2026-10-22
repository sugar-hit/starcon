package com.starcon.channel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 16.5.1.2 风险评分的<b>纯计算</b>（不含 IO、不含 Spring）。
 *
 * <p>🔴 这个类只做一件事：把「原始事实」变成「逐特征的贡献分 + 一个总分」。
 * 之所以把它单独拎出来（而不是塞进 Service 里），是因为**评分算法必须能被单独测**：
 * 权重归一化、缺特征不进分母、贡献分之和恰好等于总分 —— 这三条是本模块的命门，
 * 混在数据库访问里就没人能一眼看出它对不对。
 *
 * <p>🔴 <b>三条不可让步的口径</b>：
 *
 * <ol>
 *   <li><b>量不到 ≠ 0</b>：量不到的特征<b>不进分母</b>、<b>不算通过</b>。
 *       把「读不到」折成 0 分，等于把「没数据」说成「这买家很干净」。
 *   <li><b>贡献分之和 = 总分</b>：贡献分按「标准化值 × 权重 ÷ 已知维度权重合计 × 100」算，
 *       <b>逐条四舍五入到 4 位小数后求和</b>，这个和<b>就是</b>总分。
 *       界面上「各特征贡献分加起来」必须正好等于「总分」—— 否则客服解释不清。
 *   <li><b>全为 0 时不许出分</b>：已知维度的权重合计为 0 ⇒ 无法归一化 ⇒
 *       判「信号不足」，不给一个 0 分。
 * </ol>
 */
public final class BuyerRiskScoreRules {

  private BuyerRiskScoreRules() {}

  /** 分数精度（与 DB 的 numeric(9,4) 一致）。 */
  public static final int SCORE_SCALE = 4;

  /** 标准化值精度（与 DB 的 numeric(9,6) 一致）。 */
  public static final int NORMALIZED_SCALE = 6;

  /** 缺信号口径：按已有维度归一化后打分（缺失维度不贡献，权重按比例摊回）。 */
  public static final String POLICY_SCORE_KNOWN_ONLY = "SCORE_KNOWN_ONLY";

  /** 缺信号口径：任一维度缺信号就不出分，只给「建议人工」。 */
  public static final String POLICY_BLOCK_EVALUATION = "BLOCK_EVALUATION";

  /** 建议动作：放行。 */
  public static final String ACTION_RELEASE = "RELEASE";

  /** 建议动作：转人工。 */
  public static final String ACTION_MANUAL_REVIEW = "MANUAL_REVIEW";

  /** 建议动作：暂缓（本模块<b>只出建议</b>，不改订单状态 —— 改订单是 9.2 的事）。 */
  public static final String ACTION_HOLD = "HOLD";

  /** 判定状态：出了分。 */
  public static final String STATUS_SCORED = "SCORED";

  /** 判定状态：信号不足，没出分。 */
  public static final String STATUS_INSUFFICIENT = "INSUFFICIENT_SIGNALS";

  /**
   * 五个**有权重**的维度 + 一个**没有权重**的维度。
   *
   * <p>🔴 {@link #RELATED_ACCOUNTS} 刻意<b>不在权重里</b>：16.5.2.2 关联风险未交付，
   * 本模块不编造一张「关联账号」表（那是别人的职责）。
   * 一个<b>永远量不到</b>的维度占着权重会把分数稀释 —— 量不到的维度不进分母。
   */
  public enum Dimension {
    REFUND_HISTORY("历史退款", "REFUND_RATE", true),
    DISPUTE_HISTORY("纠纷历史", "DISPUTE_RATE", true),
    FRAUD_PROFILE("欺诈特征", "FRAUD_PROFILE", true),
    ADDRESS_PATTERN("地址模式", "ADDRESS_PATTERN", true),
    ORDER_BEHAVIOR("下单行为", "ORDER_BEHAVIOR", true),
    RELATED_ACCOUNTS("关联账号", "RELATED_ACCOUNTS", false);

    private final String label;
    private final String featureCode;
    private final boolean weighted;

    Dimension(String label, String featureCode, boolean weighted) {
      this.label = label;
      this.featureCode = featureCode;
      this.weighted = weighted;
    }

    public String label() {
      return label;
    }

    public String featureCode() {
      return featureCode;
    }

    /** 该维度是否参与权重（{@link #RELATED_ACCOUNTS} 永远不参与）。 */
    public boolean weighted() {
      return weighted;
    }
  }

  /** 一条特征算完之后的中间结果（服务层拿它落 feature 行）。 */
  public record Feature(
      Dimension dimension,
      boolean available,
      String rawValue,
      BigDecimal normalizedValue,
      String reason,
      String ownerModule,
      String note) {}

  /** 汇总结果。 */
  public record Outcome(
      BigDecimal score,
      String evaluationStatus,
      String recommendedAction,
      int scoredDimensionCount,
      int missingDimensionCount,
      BigDecimal knownWeightSum,
      String conclusion,
      /** 逐维度的贡献分（量不到的维度不在表里）；**逐条之和 = score**。 */
      java.util.Map<Dimension, BigDecimal> contributions) {}

  // ================================================================ 归一化

  /** 夹到 0..1。 */
  public static BigDecimal clamp01(BigDecimal value) {
    if (value == null) return BigDecimal.ZERO;
    if (value.signum() < 0) return BigDecimal.ZERO;
    if (value.compareTo(BigDecimal.ONE) > 0) return BigDecimal.ONE;
    return value;
  }

  /**
   * 按「满档阈值」折算：{@code value / threshold}，夹到 0..1。
   *
   * <p>用于「退款率 30% 即满档」「短时多单 3 笔即满档」「每 30 天 8 笔即满档」这类口径。
   * 阈值本身是<b>租户可配</b>的（{@code buyer_risk_rule} 的几列阈值）。
   */
  public static BigDecimal byThreshold(BigDecimal value, BigDecimal threshold) {
    if (value == null || threshold == null || threshold.signum() <= 0) return BigDecimal.ZERO;
    return clamp01(value.divide(threshold, NORMALIZED_SCALE, RoundingMode.HALF_UP));
  }

  /**
   * 按「阈值以上的区间」折算：{@code (ratio - threshold) / (1 - threshold)}，夹到 0..1。
   *
   * <p>用于「地址分散度」：分散度低于阈值不产生风险，高于阈值按超出幅度线性上升。
   */
  public static BigDecimal aboveThreshold(BigDecimal ratio, BigDecimal threshold) {
    if (ratio == null || threshold == null) return BigDecimal.ZERO;
    if (ratio.compareTo(threshold) <= 0) return BigDecimal.ZERO;
    BigDecimal span = BigDecimal.ONE.subtract(threshold);
    if (span.signum() <= 0) return BigDecimal.ONE;
    return clamp01(
        ratio.subtract(threshold).divide(span, NORMALIZED_SCALE, RoundingMode.HALF_UP));
  }

  /** 百分比展示（保留两位小数，纯展示用）。 */
  public static String percentText(BigDecimal ratio) {
    if (ratio == null) return "—";
    return ratio.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP).toPlainString()
        + "%";
  }

  /** 分数展示（保留两位小数，纯展示用）。 */
  public static String scoreText(BigDecimal score) {
    if (score == null) return "—";
    return score.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  // ================================================================ 汇总

  /**
   * 把逐特征结果汇总成「总分 + 建议动作 + 结论」。
   *
   * @param features 全部特征（含量不到的）
   * @param weightOf 该维度的权重（量不到的维度权重也照样给，但**不进分母**）
   * @param missingSignalPolicy 租户选的缺信号口径
   * @param manualReviewThreshold 建议人工的分数阈值
   * @param holdThreshold 建议暂缓的分数阈值
   */
  public static Outcome aggregate(
      List<Feature> features,
      java.util.function.Function<Dimension, BigDecimal> weightOf,
      String missingSignalPolicy,
      BigDecimal manualReviewThreshold,
      BigDecimal holdThreshold) {

    BigDecimal knownWeight = BigDecimal.ZERO;
    int known = 0;
    int missing = 0;
    List<Feature> available = new java.util.ArrayList<>();
    for (Feature feature : features) {
      if (feature == null) continue;
      if (feature.available()) {
        known++;
        knownWeight = knownWeight.add(zero(weightOf.apply(feature.dimension())));
        available.add(feature);
      } else {
        missing++;
      }
    }

    // 🔴 BLOCK_EVALUATION：任一维度缺信号就**不出分**，只给「建议人工」。
    if (POLICY_BLOCK_EVALUATION.equals(missingSignalPolicy) && missing > 0) {
      return new Outcome(
          null,
          STATUS_INSUFFICIENT,
          ACTION_MANUAL_REVIEW,
          known,
          missing,
          knownWeight,
          "信号不足，无法评分："
              + missing
              + " 个维度量不到（缺信号口径为 BLOCK_EVALUATION，缺一个维度就不出分）。"
              + "已量到的维度不产生贡献分 —— 界面显示的分项加起来不会等于任何分数，因为压根没有分数。",
          java.util.Map.of());
    }

    // 🔴 全量不到 / 已知维度权重合计为 0 ⇒ 无法归一化 ⇒ 不出分（不给 0 分）。
    if (known == 0 || knownWeight.signum() <= 0) {
      return new Outcome(
          null,
          STATUS_INSUFFICIENT,
          ACTION_MANUAL_REVIEW,
          known,
          missing,
          knownWeight,
          known == 0
              ? "信号不足，无法评分：五个维度全部量不到，没有任何依据可以算分。"
              : "信号不足，无法评分：量到的维度权重合计为 0，无法归一化。",
          java.util.Map.of());
    }

    // 🔴 贡献分逐条四舍五入到 4 位小数，**求和就是总分**（界面上必须对得上）。
    java.util.Map<Dimension, BigDecimal> contributions = new java.util.LinkedHashMap<>();
    BigDecimal total = BigDecimal.ZERO;
    for (Feature feature : available) {
      BigDecimal weight = zero(weightOf.apply(feature.dimension()));
      BigDecimal contribution =
          feature
              .normalizedValue()
              .multiply(weight)
              .multiply(BigDecimal.valueOf(100))
              .divide(knownWeight, SCORE_SCALE, RoundingMode.HALF_UP);
      contributions.put(feature.dimension(), contribution);
      total = total.add(contribution);
    }
    BigDecimal score = total.setScale(SCORE_SCALE, RoundingMode.HALF_UP);

    String action;
    if (score.compareTo(zero(holdThreshold)) >= 0) {
      action = ACTION_HOLD;
    } else if (score.compareTo(zero(manualReviewThreshold)) >= 0) {
      action = ACTION_MANUAL_REVIEW;
    } else {
      action = ACTION_RELEASE;
    }

    return new Outcome(
        score,
        STATUS_SCORED,
        action,
        known,
        missing,
        knownWeight,
        "总分 "
            + scoreText(score)
            + "（"
            + known
            + " 个维度参与计分、"
            + missing
            + " 个维度量不到未计入）；建议动作："
            + actionLabel(action)
            + "。各特征贡献分之和恰好等于总分。",
        contributions);
  }

  /** 建议动作的中文标签（后端算好，界面原样显示）。 */
  public static String actionLabel(String action) {
    if (ACTION_HOLD.equals(action)) return "建议暂缓";
    if (ACTION_MANUAL_REVIEW.equals(action)) return "建议人工复核";
    if (ACTION_RELEASE.equals(action)) return "建议放行";
    return "未知";
  }

  private static BigDecimal zero(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
