package com.starcon.trade;

import static com.starcon.trade.SupplierRatingModels.SupplierRatingDimension;
import static com.starcon.trade.SupplierRatingModels.SupplierRatingGrade;
import static com.starcon.trade.SupplierRatingModels.SupplierRatingPeriodType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 6.1.1.3 供应商评级的<b>纯规则层</b>（无 IO、无 Spring 依赖，可直接单测）。
 *
 * <p>把「怎么算」与「从哪算」分开，是这个模块最容易出错的地方：
 * 口径一旦散在 SQL 与服务层里，改一次分母就要同时改三四个地方，而**没有任何东西会报错** ——
 * 只会让运营对着两个数字做决策。
 *
 * <p><b>五条口径纪律</b>（与 V266 迁移文件的第二节逐字对应）：
 *
 * <ol>
 *   <li><b>不可用 ≠ 0 分</b>：维度的原始指标为 null（分母为 0 / 没有依据）时，
 *       该维度**不参与合成**（{@code available=false}），而不是给它 0 分。
 *       「没交货过」与「交货全部延期」是相反的两件事。</li>
 *   <li><b>合成按可用维度归一</b>：{@code 综合分 = Σ(权重 × 得分) / Σ(可用权重)}。
 *       五个维度里只算出三个时，综合分仍然是 0~100 的可比分数，
 *       同时把 {@code availableWeight} 报出来（页面上要显示「本次评级覆盖了 60% 的权重」）。</li>
 *   <li><b>人工调整不改客观分</b>：人工只改最终分，客观分永远是数据算出来的那一份。</li>
 *   <li><b>比率与分数一律 BigDecimal</b>：禁浮点（AGENTS.md）。</li>
 *   <li><b>分母为 0 返回 null</b>：比率类指标一律如此。</li>
 * </ol>
 */
public final class SupplierRatingRules {

  private SupplierRatingRules() {}

  /** 分数刻度：所有维度得分与综合分都落在 0~100。 */
  public static final BigDecimal HUNDRED = new BigDecimal("100");

  /** 系统默认权重（租户还没配时用；五项之和精确为 1）。 */
  public static final BigDecimal DEFAULT_DELIVERY_WEIGHT = new BigDecimal("0.30");
  public static final BigDecimal DEFAULT_QUALITY_WEIGHT = new BigDecimal("0.30");
  public static final BigDecimal DEFAULT_RESPONSE_WEIGHT = new BigDecimal("0.15");
  public static final BigDecimal DEFAULT_PRICE_WEIGHT = new BigDecimal("0.15");
  public static final BigDecimal DEFAULT_SERVICE_WEIGHT = new BigDecimal("0.10");

  /** 系统默认等级分档。 */
  public static final BigDecimal DEFAULT_GRADE_A_MIN = new BigDecimal("0.85");
  public static final BigDecimal DEFAULT_GRADE_B_MIN = new BigDecimal("0.70");
  public static final BigDecimal DEFAULT_GRADE_C_MIN = new BigDecimal("0.55");

  /** 系统默认响应目标小时数（3 天）。 */
  public static final int DEFAULT_RESPONSE_TARGET_HOURS = 72;

  /** 权重保留 6 位小数（与 DB 的 {@code numeric(9,6)} 对齐）。 */
  private static final int WEIGHT_SCALE = 6;

  /** 分数保留 4 位小数（与 DB 的 {@code numeric(9,4)} 对齐）。 */
  private static final int SCORE_SCALE = 4;

  /** 比率保留 6 位小数（与 DB 的 {@code numeric(9,6)} 对齐）。 */
  private static final int RATE_SCALE = 6;

  // ================================================================ 周期

  /**
   * 周期区间（闭区间，含首尾两天）。
   *
   * <p>月度 = 锚点所在自然月；季度 = 锚点所在自然季度。
   * 「默认月度」是蓝图原话，所以 {@code MONTHLY} 是默认周期类型。
   */
  public record Period(LocalDate start, LocalDate end) {}

  /** 锚点所在周期。 */
  public static Period periodOf(SupplierRatingPeriodType type, LocalDate anchor) {
    SupplierRatingPeriodType t = type == null ? SupplierRatingPeriodType.MONTHLY : type;
    if (t == SupplierRatingPeriodType.QUARTERLY) {
      int firstMonth = ((anchor.getMonthValue() - 1) / 3) * 3 + 1;
      LocalDate start = LocalDate.of(anchor.getYear(), firstMonth, 1);
      LocalDate end = start.plusMonths(3).minusDays(1);
      return new Period(start, end);
    }
    YearMonth ym = YearMonth.of(anchor.getYear(), anchor.getMonthValue());
    return new Period(ym.atDay(1), ym.atEndOfMonth());
  }

  // ================================================================ 权重

  /** 五个维度的权重。 */
  public record Weights(
      BigDecimal delivery,
      BigDecimal quality,
      BigDecimal response,
      BigDecimal price,
      BigDecimal service) {

    /** 取某个维度的权重。 */
    public BigDecimal of(SupplierRatingDimension dimension) {
      return switch (dimension) {
        case DELIVERY -> delivery;
        case QUALITY -> quality;
        case RESPONSE -> response;
        case PRICE -> price;
        case SERVICE -> service;
      };
    }

    /** 五项之和（精确十进制，不是浮点）。 */
    public BigDecimal sum() {
      return delivery.add(quality).add(response).add(price).add(service);
    }
  }

  /** 系统默认权重。 */
  public static Weights defaultWeights() {
    return new Weights(
        DEFAULT_DELIVERY_WEIGHT,
        DEFAULT_QUALITY_WEIGHT,
        DEFAULT_RESPONSE_WEIGHT,
        DEFAULT_PRICE_WEIGHT,
        DEFAULT_SERVICE_WEIGHT);
  }

  /**
   * 权重配置的校验（返回问题清单，空 = 通过）。
   *
   * <p>⚠️ 权重和必须<b>精确等于 1</b>：大于 1 综合分被放大、小于 1 被压缩，
   * 两种都会让「名次」与「分数」脱钩；等于 0 综合分恒为 0，评级退化成常量。
   * 「某个维度不参与」的正确表达是<b>该维度权重为 0</b>，不是「权重和不为 1」。
   */
  public static List<String> validateWeights(Weights w) {
    List<String> problems = new ArrayList<>();
    if (w == null) {
      problems.add("权重不能为空");
      return problems;
    }
    for (SupplierRatingDimension d : SupplierRatingDimension.values()) {
      BigDecimal v = w.of(d);
      if (v == null) {
        problems.add(d.label() + "的权重不能为空");
      } else if (v.signum() < 0 || v.compareTo(BigDecimal.ONE) > 0) {
        problems.add(d.label() + "的权重必须在 0 ~ 1 之间，当前是 " + v);
      }
    }
    if (!problems.isEmpty()) return problems;
    BigDecimal sum = w.sum();
    if (sum.compareTo(BigDecimal.ONE) != 0) {
      problems.add(
          "五个维度的权重之和必须精确等于 1（当前是 "
              + sum.stripTrailingZeros().toPlainString()
              + "）：权重和不为 1 时综合分会失真，而这种失真不会报错");
    }
    return problems;
  }

  // ================================================================ 维度得分

  /**
   * 比率型维度的得分（交期达成率 / 质量合格率 / 售后配合度）。
   *
   * @param rate 比率（0~1），{@code null} = 该维度不可用
   * @return 得分（0~100），{@code null} = 该维度不可用
   */
  public static BigDecimal rateScore(BigDecimal rate) {
    if (rate == null) return null;
    return rate.multiply(HUNDRED).setScale(SCORE_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * 响应时长的得分：越短越好。
   *
   * <p>线性映射：平均响应时长 = <b>目标小时数</b>时得 50 分，= 两倍目标时得 0 分，0 小时得满分。
   * 线性而不是曲线：曲线的拐点要另立一条规则，运营答不出「60 分对应几小时」，
   * 而线性可以直接把目标小时数写在界面上。
   *
   * <p>「目标」取一半分而不是零分，是因为目标本身是**可接受的上限**：
   * 刚好压线不该拿 0 分（那会让「按时答复」与「完全不应答」在分数上无法区分），
   * 但也不该拿满分（那会让「压线答复」与「秒回」无法区分）。
   *
   * @param avgHours 平均响应时长（小时），{@code null} = 该维度不可用
   * @param targetHours 目标小时数（> 0）
   */
  public static BigDecimal responseScore(BigDecimal avgHours, int targetHours) {
    if (avgHours == null) return null;
    if (targetHours <= 0) throw new IllegalArgumentException("响应目标小时数必须大于 0");
    BigDecimal ratio =
        avgHours.divide(BigDecimal.valueOf(targetHours * 2L), 6, RoundingMode.HALF_UP);
    BigDecimal score = BigDecimal.ONE.subtract(ratio).max(BigDecimal.ZERO).multiply(HUNDRED);
    return score.setScale(SCORE_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * 价格竞争力的单条贡献度。
   *
   * @param ourPrice 本供应商在同一币种、同一参考数量处的单价
   * @param bestOtherPrice 同币种其它供应商的最低单价
   * @return 贡献度（0~1）：本供应商就是最优价时是 1，贵一倍时是 0.5
   */
  public static BigDecimal priceContribution(BigDecimal ourPrice, BigDecimal bestOtherPrice) {
    if (ourPrice == null || bestOtherPrice == null) return null;
    if (ourPrice.signum() <= 0) return null;
    if (bestOtherPrice.signum() <= 0) return null;
    BigDecimal ratio = bestOtherPrice.divide(ourPrice, 6, RoundingMode.HALF_UP);
    return ratio.min(BigDecimal.ONE).max(BigDecimal.ZERO).setScale(RATE_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * 阶梯报价在某个参考数量处的单价。
   *
   * <p>取「起订量 ≤ 参考数量的最高那一档」。
   *
   * <p>⚠️ <b>参考数量低于全部档位的起订量时返回 null</b>（而不是退回最低档）：
   * 那样等于拿「人家 MOQ 1000 的入场价」去比「我们买 10 件的价格」——
   * 两者的条件根本不同，比出来的百分比是假的。<b>报价在该数量处不适用 ⇒ 该样本不参与比价。</b>
   *
   * @param tiers 阶梯（{@code minQuantity} + {@code unitPrice}）
   * @param quantity 参考数量
   * @return 单价；没有阶梯、或该数量低于所有档位的起订量时返回 null
   */
  public static BigDecimal tierPrice(List<QuoteTier> tiers, BigDecimal quantity) {
    if (tiers == null || tiers.isEmpty() || quantity == null) return null;
    QuoteTier best = null;
    for (QuoteTier t : tiers) {
      if (t == null || t.unitPrice() == null) continue;
      if (t.minQuantity() != null && quantity.compareTo(t.minQuantity()) < 0) continue;
      if (best == null
          || (t.minQuantity() != null
              && best.minQuantity() != null
              && t.minQuantity().compareTo(best.minQuantity()) > 0)) {
        best = t;
      }
    }
    return best == null ? null : best.unitPrice();
  }

  /** 阶梯价的一档（跨服务读回来的报价形状）。 */
  public record QuoteTier(BigDecimal minQuantity, BigDecimal unitPrice) {}

  // ================================================================ 合成

  /** 合成的结果。 */
  public record Composite(BigDecimal score, BigDecimal availableWeight, List<SupplierRatingDimension> unavailable) {}

  /**
   * 综合分：{@code Σ(权重 × 得分) / Σ(可用权重)}。
   *
   * <p>分母用<b>可用</b>权重和而不是 1：五个维度里只算出三个时，
   * 分数仍然是 0~100 的可比值（同时 {@code availableWeight} 报出覆盖率）。
   * 一个维度都算不出来时返回 {@code null}（而不是 0）——
   * 「没有任何依据」与「依据显示这家很差」是相反的两件事。
   */
  public static Composite composite(Weights weights, Map<SupplierRatingDimension, BigDecimal> scores) {
    BigDecimal weighted = BigDecimal.ZERO;
    BigDecimal available = BigDecimal.ZERO;
    List<SupplierRatingDimension> unavailable = new ArrayList<>();
    for (SupplierRatingDimension d : SupplierRatingDimension.values()) {
      BigDecimal score = scores == null ? null : scores.get(d);
      if (score == null) {
        unavailable.add(d);
        continue;
      }
      BigDecimal w = weights.of(d);
      weighted = weighted.add(w.multiply(score));
      available = available.add(w);
    }
    if (available.signum() == 0) {
      return new Composite(null, BigDecimal.ZERO.setScale(WEIGHT_SCALE, RoundingMode.HALF_UP), unavailable);
    }
    BigDecimal score = weighted.divide(available, SCORE_SCALE, RoundingMode.HALF_UP);
    return new Composite(score, available.setScale(WEIGHT_SCALE, RoundingMode.HALF_UP), unavailable);
  }

  // ================================================================ 等级

  /**
   * 等级：{@code A ≥ aMin > B ≥ bMin > C ≥ cMin > D}。
   *
   * @param score 最终分（0~100），{@code null} = 没有分数就没有等级
   */
  public static SupplierRatingGrade grade(
      BigDecimal score, BigDecimal aMin, BigDecimal bMin, BigDecimal cMin) {
    if (score == null) return null;
    BigDecimal a = aMin == null ? DEFAULT_GRADE_A_MIN : aMin;
    BigDecimal b = bMin == null ? DEFAULT_GRADE_B_MIN : bMin;
    BigDecimal c = cMin == null ? DEFAULT_GRADE_C_MIN : cMin;
    BigDecimal ratio = score.divide(HUNDRED, 6, RoundingMode.HALF_UP);
    if (ratio.compareTo(a) >= 0) return SupplierRatingGrade.A;
    if (ratio.compareTo(b) >= 0) return SupplierRatingGrade.B;
    if (ratio.compareTo(c) >= 0) return SupplierRatingGrade.C;
    return SupplierRatingGrade.D;
  }

  // ================================================================ 人工调整

  /**
   * 人工调整后的最终分（夹逼到 0~100）。
   *
   * <p>人工只改最终分，客观分不动 —— 客观分永远是数据算出来的那一份。
   */
  public static BigDecimal adjustedFinalScore(BigDecimal objectiveScore, BigDecimal adjustment) {
    if (objectiveScore == null) return null;
    if (adjustment == null) return objectiveScore.setScale(SCORE_SCALE, RoundingMode.HALF_UP);
    return objectiveScore
        .add(adjustment)
        .min(HUNDRED)
        .max(BigDecimal.ZERO)
        .setScale(SCORE_SCALE, RoundingMode.HALF_UP);
  }

  // ================================================================ 其它

  /** 比率的展示精度（6 位小数，与 DB 对齐）。 */
  public static BigDecimal rate(BigDecimal value) {
    if (value == null) return null;
    return value.setScale(RATE_SCALE, RoundingMode.HALF_UP);
  }

  /** 权重的展示精度（6 位小数，与 DB 对齐）。 */
  public static BigDecimal weight(BigDecimal value) {
    if (value == null) return null;
    return value.setScale(WEIGHT_SCALE, RoundingMode.HALF_UP);
  }

  /** 维度键的逗号串（不可用维度清单）。 */
  public static String dimensionKeys(List<SupplierRatingDimension> dimensions) {
    if (dimensions == null || dimensions.isEmpty()) return "";
    StringBuilder sb = new StringBuilder();
    for (SupplierRatingDimension d : dimensions) {
      if (sb.length() > 0) sb.append(',');
      sb.append(d.name());
    }
    return sb.toString();
  }

  /** 维度键 → 维度（用于读回时解析不可用维度清单）。 */
  public static List<SupplierRatingDimension> parseDimensions(String keys) {
    List<SupplierRatingDimension> out = new ArrayList<>();
    if (keys == null || keys.isBlank()) return out;
    for (String raw : keys.split(",")) {
      String k = raw.trim();
      if (k.isEmpty()) continue;
      try {
        out.add(SupplierRatingDimension.valueOf(k));
      } catch (IllegalArgumentException ignored) {
        // 维度键是闭集词表；读到一个不认识的值就跳过，绝不因此让整页 500。
      }
    }
    return out;
  }

  /** 空的维度得分表（五个维度都没算出来）。 */
  public static Map<SupplierRatingDimension, BigDecimal> emptyScores() {
    return new EnumMap<>(SupplierRatingDimension.class);
  }
}
