package com.starcon.channel;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 10.1.2.2 渠道比价 的<b>评分引擎</b>。
 *
 * <p>蓝图：「在满足时效与限制的渠道中按成本/时效/成功率多目标排序，支持按店铺预设优先级自动选择。
 * 输入：比价条件；渠道评分（历史成功率、时效达成率、投诉率）。输出：排序后的推荐渠道 + 评分明细。」
 *
 * <p>本类是多目标排序真正发生的地方：把「一条渠道的成本 + 时效 + 可靠性」变成 0~100 的
 * 三维得分与综合分。
 *
 * <p>🔴 三条纪律，与 {@link FreightRatingEngine} 同一套：
 *
 * <ol>
 *   <li><b>本类不读数据库，也没有任何策略常量</b>。评分（成功率 / 时效达成率 / 投诉率）由
 *       调用方从 {@code channel_rating_score} 读出来传进来；<b>三个闸的阈值</b>
 *       （投诉率红线、最小样本量、评分最大年龄）同样由调用方传进来 —— 它们是
 *       <b>租户可维护的数据</b>（V143 的列），不是代码里的字面量。换一家承运商的经营状况
 *       变化时，调阈值是改数据不是发版。
 *   <li><b>没有 double / float</b>：得分全程 {@link BigDecimal}。得分直接决定下游
 *       9.3.2.1 分仓选谁，它是决策依据，浮点不可接受。
 *   <li><b>不可比返回 {@code null} 而不是 0</b>。0 分与「没有数据」在这一页是
 *       <b>完全相反</b>的含义：0 分是「比过，确实最差」，null 是「压根没资格比」。
 *       把 null 当 0 会让没登记时效的渠道凭空拿到 0 分，再乘一个权重就污染了综合分。
 * </ol>
 *
 * <p>本类无状态，可脱离 Spring 单独 new 出来测，所以归一化边界（全等、缺一、单值）
 * 在单测里毫秒级钉死，不需要起容器。
 */
public final class ChannelScoringEngine {

  /** 得分统一按 NUMERIC(9,4) 的精度折算（与列定义一致，不靠默认 MathContext）。 */
  private static final int SCORE_SCALE = 4;

  private static final BigDecimal HUNDRED = new BigDecimal("100");

  /** 中間精度比得分位数高得多，所以归一化不会引入二次舍入误差。 */
  private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

  /** 无状态：服务层与测试都拿这一个实例（它没有字段，也不需要依赖）。 */
  public static final ChannelScoringEngine INSTANCE = new ChannelScoringEngine();

  private ChannelScoringEngine() {}

  // ==================================================================
  // 输入
  // ==================================================================

  /**
   * 一条参与排序的候选。
   *
   * @param totalCharge 上游 10.1.2.1 的试算合计；null = 算不出钱（{@code NO_RULE}），不是 0
   * @param currency 该渠道的计费币种
   * @param transitBasisDays 排序用时效：优先取试算时效上界 {@code transitMaxDays}，
   *     缺失时退到下界 {@code transitMinDays}；两者皆 null = 该渠道未登记时效，不可比
   * @param successRate / onTimeRate / complaintRate 来自 {@code channel_rating_score} 的
   *     <b>生成列</b>（DB 层由计数派生），null 表示这份评分缺这一维
   * @param comparable false = 已被前置条件（硬拦截 / 超时效 / 币种不同）排除，压根不参与归一化
   */
  public record Candidate(
      UUID channelId,
      BigDecimal totalCharge,
      String currency,
      Integer transitBasisDays,
      BigDecimal successRate,
      BigDecimal onTimeRate,
      BigDecimal complaintRate,
      boolean comparable) {}

  /** 一条候选算出来的三维得分与综合分。 */
  public record Scores(
      BigDecimal costScore,
      BigDecimal transitScore,
      BigDecimal reliabilityScore,
      BigDecimal compositeScore,
      List<String> breakdown) {}

  /** 归一化后的多目标权重（三个主权重 + 可靠性维度的三个子权重）。 */
  public record Weights(
      BigDecimal cost,
      BigDecimal transit,
      BigDecimal reliability,
      BigDecimal successRate,
      BigDecimal onTimeRate,
      BigDecimal complaintRate) {}

  /**
   * 把一组权重归一化到和为 1。
   *
   * <p>🔴 全零时**抛异常而不是静默均分**：「三个目标都不重要」不是合法输入，
   * 而均分出一个 (1/3,1/3,1/3) 会让这次比价看起来算过、实际上与调用方的意图无关。
   */
  public Weights normalize(BigDecimal cost, BigDecimal transit, BigDecimal reliability) {
    BigDecimal c = nz(cost);
    BigDecimal t = nz(transit);
    BigDecimal r = nz(reliability);
    BigDecimal sum = c.add(t).add(r);
    if (sum.signum() <= 0) {
      throw new ScoringException(
          "成本 / 时效 / 可靠性三个权重不能同时为 0 —— 那不叫「都不重要」，那叫没选目标");
    }
    return new Weights(
        c.divide(sum, SCORE_SCALE, RoundingMode.HALF_UP),
        t.divide(sum, SCORE_SCALE, RoundingMode.HALF_UP),
        r.divide(sum, SCORE_SCALE, RoundingMode.HALF_UP),
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
  }

  /** 可靠性维度内部的三个子权重归一化（成功率 / 时效达成率 / 投诉率倒数）。 */
  public Weights withReliabilityWeights(Weights base, BigDecimal success, BigDecimal onTime, BigDecimal complaint) {
    BigDecimal s = nz(success);
    BigDecimal t = nz(onTime);
    BigDecimal c = nz(complaint);
    BigDecimal sum = s.add(t).add(c);
    if (sum.signum() <= 0) {
      throw new ScoringException(
          "可靠性维度的三个子权重（成功率 / 时效达成率 / 投诉率）不能同时为 0");
    }
    return new Weights(
        base.cost(),
        base.transit(),
        base.reliability(),
        s.divide(sum, SCORE_SCALE, RoundingMode.HALF_UP),
        t.divide(sum, SCORE_SCALE, RoundingMode.HALF_UP),
        c.divide(sum, SCORE_SCALE, RoundingMode.HALF_UP));
  }

  // ==================================================================
  // 归一化：把「越小越好」的两维换算成 0~100 的「越大越好」
  // ==================================================================

  /**
   * 成本归一化：最便宜 100 分，最贵 0 分。
   *
   * <p>🔴 **只在同一个币种内归一化**（{@code scope} 是调用方筛出的主币种候选集）：
   * 0.9 CNY 的运费比 0.13 USD「便宜」，但它们不是同一个东西，混排出来的名次没有意义。
   *
   * <p>全部同价时一律给 100 分：它们并列最好，给谁 0 分都是在编造一个不存在的差距。
   */
  public CostSpan costScores(List<Candidate> scope) {
    BigDecimal min = null;
    BigDecimal max = null;
    for (Candidate c : scope) {
      if (!c.comparable() || c.totalCharge() == null) {
        continue;
      }
      BigDecimal v = c.totalCharge();
      if (min == null || v.compareTo(min) < 0) {
        min = v;
      }
      if (max == null || v.compareTo(max) > 0) {
        max = v;
      }
    }
    if (min == null) {
      return new CostSpan(null, null);
    }
    if (min.compareTo(max) == 0) {
      return new CostSpan(min, max);
    }
    return new CostSpan(min, max);
  }

  /** 成本得分：{@code 100 × (max − cost) / (max − min)}。 */
  public BigDecimal costScore(Candidate candidate, BigDecimal min, BigDecimal max) {
    if (min == null || max == null || candidate.totalCharge() == null) {
      return null;
    }
    if (min.compareTo(max) == 0) {
      return HUNDRED;
    }
    return HUNDRED
        .multiply(max.subtract(candidate.totalCharge()))
        .divide(max.subtract(min), MC)
        .setScale(SCORE_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * 时效归一化：最快 100 分，最慢 0 分。同样只在 {@code scope} 这组可比候选内比较。
   *
   * <p>🔴 没登记时效的渠道**返回 null**（不可比）而不是 0 分：它没被比过，
   * 不是「比过了最慢」。把它按 0 分算，等于替一个没数据的渠道编了一个结论。
   */
  public Span transitSpan(List<Candidate> scope) {
    Integer min = null;
    Integer max = null;
    for (Candidate c : scope) {
      if (!c.comparable() || c.transitBasisDays() == null) {
        continue;
      }
      Integer v = c.transitBasisDays();
      if (min == null || v < min) {
        min = v;
      }
      if (max == null || v > max) {
        max = v;
      }
    }
    return new Span(min, max);
  }

  /** 时效得分：{@code 100 × (max − days) / (max − min)}。 */
  public BigDecimal transitScore(Candidate candidate, Span span) {
    if (span.min() == null || span.max() == null || candidate.transitBasisDays() == null) {
      return null;
    }
    if (span.min().equals(span.max())) {
      return HUNDRED;
    }
    BigDecimal spread = BigDecimal.valueOf(span.max()).subtract(BigDecimal.valueOf(span.min()));
    return HUNDRED
        .multiply(
            BigDecimal.valueOf(span.max())
                .subtract(BigDecimal.valueOf(candidate.transitBasisDays())))
        .divide(spread, MC)
        .setScale(SCORE_SCALE, RoundingMode.HALF_UP);
  }

  // ==================================================================
  // 可靠性分：成功率 / 时效达成率 / 投诉率倒数 的加权和
  // ==================================================================

  /**
   * 可靠性得分。
   *
   * <p>投诉率取<b>倒数</b>参与：投诉率越高越差，所以 {@code 1 − 投诉率} 才是「越大越好」
   * 的那一维。把它直接当成正权重加进去会得出「投诉越多越可靠」的反向结论。
   *
   * <p>🔴 缺任一维时**返回 null**（不可比）而不是当作 0：把缺的那维当 0 等于凭空
   * 断言「这条渠道的投诉率是 100%」。
   */
  public BigDecimal reliabilityScore(Candidate candidate, Weights weights) {
    if (candidate.successRate() == null
        || candidate.onTimeRate() == null
        || candidate.complaintRate() == null) {
      return null;
    }
    BigDecimal success = pct(candidate.successRate());
    BigDecimal onTime = pct(candidate.onTimeRate());
    BigDecimal noComplaint = HUNDRED.subtract(pct(candidate.complaintRate()));
    return success
        .multiply(weights.successRate())
        .add(onTime.multiply(weights.onTimeRate()))
        .add(noComplaint.multiply(weights.complaintRate()))
        .setScale(SCORE_SCALE, RoundingMode.HALF_UP);
  }

  /** 比率（0~1）→ 百分制（0~100）。 */
  private static BigDecimal pct(BigDecimal rate) {
    return rate.multiply(HUNDRED, MC).setScale(SCORE_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * 综合分 = 成本分 × 权重 + 时效分 × 权重 + 可靠性分 × 权重。
   *
   * <p>🔴 任一维为 null（不可比）时综合分为 null：**不把缺失的维度 renormalize 掉**。
   * 那样做等于把「这个渠道没登记时效」解读成「它其它两维格外重要」，而这是调用方
   * 没有表达过的意思。不可比的渠道由服务层直接排除并写明原因。
   *
   * <p>（最终落库的综合分由 V143 trigger d 在 DB 层按单头权重重算一遍 —— 本方法算的
   * 值用于**排序**，DB 算的值用于**存储**，两者同式。）
   */
  public BigDecimal composite(BigDecimal costScore, BigDecimal transitScore, BigDecimal reliabilityScore, Weights weights) {
    if (costScore == null || transitScore == null || reliabilityScore == null) {
      return null;
    }
    return costScore
        .multiply(weights.cost())
        .add(transitScore.multiply(weights.transit()))
        .add(reliabilityScore.multiply(weights.reliability()))
        .setScale(SCORE_SCALE, RoundingMode.HALF_UP);
  }

  // ==================================================================
  // 三个闸：只挡自动选择，不挡评分（蓝图加粗句的物理落点）
  // ==================================================================

  /** 闸 (1) 投诉率红线。返回 null 表示通过。 */
  public String riskGate(Candidate candidate, BigDecimal maxComplaintRatePct) {
    if (candidate.complaintRate() == null || maxComplaintRatePct == null) {
      return null;
    }
    BigDecimal actual = pct(candidate.complaintRate());
    if (actual.compareTo(maxComplaintRatePct) > 0) {
      return "投诉率 "
          + actual.stripTrailingZeros().toPlainString()
          + "% 超过这条渠道的红线 "
          + maxComplaintRatePct.stripTrailingZeros().toPlainString()
          + "% —— 便宜但高丢件的渠道会把售后与差评成本推高，不自动选中";
    }
    return null;
  }

  /** 闸 (2) 最小样本量。返回 null 表示通过。 */
  public String sampleGate(Integer dispatched, int minSampleThreshold) {
    if (dispatched == null) {
      return "没有渠道评分，无法判断样本量";
    }
    if (dispatched < minSampleThreshold) {
      return "样本量 "
          + dispatched
          + " 单低于门槛 "
          + minSampleThreshold
          + " 单 —— 只发过几单全到不代表这条路真的可靠，不自动选中";
    }
    return null;
  }

  /** 闸 (3) 评分新鲜度。返回 null 表示通过。 */
  public String stalenessGate(OffsetDateTime observedAt, OffsetDateTime comparedAt, int maxAgeDays) {
    if (observedAt == null || comparedAt == null) {
      return null;
    }
    long ageDays = ChronoUnit.DAYS.between(observedAt, comparedAt);
    if (ageDays > maxAgeDays) {
      return "评分观测于 "
          + observedAt
          + "，距本次比价已 "
          + ageDays
          + " 天（上限 "
          + maxAgeDays
          + " 天）—— 过期的评分不代表今天这条渠道，不自动选中";
    }
    return null;
  }

  // ==================================================================
  // 算式明细：界面上要能读出「这 87.4 分是怎么来的」
  // ==================================================================

  /** 拼一条渠道的评分明细（逐条，不是拼成一团）。 */
  public List<String> breakdown(
      Candidate candidate, Weights weights, BigDecimal costScore, BigDecimal transitScore,
      BigDecimal reliabilityScore, BigDecimal compositeScore, String riskNote,
      String sampleNote, String staleNote) {

    List<String> out = new ArrayList<>();
    if (costScore == null) {
      out.add("成本分：不可比（上游 10.1.2.1 算不出这条渠道的运费，或币种与主币种不同）");
    } else {
      out.add(
          "成本分 "
              + plain(costScore)
              + "（运费 "
              + (candidate.totalCharge() == null ? "—" : plain(candidate.totalCharge()))
              + " "
              + candidate.currency()
              + "，在同币种候选里归一化）× 权重 "
              + plain(weights.cost())
              + " = "
              + plain(scale(costScore.multiply(weights.cost()))));
    }
    if (transitScore == null) {
      out.add("时效分：不可比（该渠道未登记本次目的国的试算时效区间，不编天数）");
    } else {
      out.add(
          "时效分 "
              + plain(transitScore)
              + "（预计 "
              + candidate.transitBasisDays()
              + " 天，取时效上界，无上界时取下界）× 权重 "
              + plain(weights.transit())
              + " = "
              + plain(scale(transitScore.multiply(weights.transit()))));
    }
    if (reliabilityScore == null) {
      out.add("可靠性分：不可比（这份渠道评分缺了成功率 / 时效达成率 / 投诉率中的某一维，不拿缺的当 0）");
    } else {
      out.add(
          "可靠性分 "
              + plain(reliabilityScore)
              + " = 成功率 "
              + ratio(candidate.successRate())
              + "×"
              + plain(weights.successRate())
              + " + 时效达成率 "
              + ratio(candidate.onTimeRate())
              + "×"
              + plain(weights.onTimeRate())
              + " + 无投诉 "
              + ratio(one().subtract(candidate.complaintRate()))
              + "×"
              + plain(weights.complaintRate())
              + "；× 权重 "
              + plain(weights.reliability())
              + " = "
              + plain(scale(reliabilityScore.multiply(weights.reliability()))));
    }
    if (compositeScore != null) {
      out.add("综合分 " + plain(compositeScore) + "（落库时由 V143 的 DB 触发器按同一组权重重算复核）");
    }
    if (riskNote != null) {
      out.add("风险闸：" + riskNote);
    }
    if (sampleNote != null) {
      out.add("样本闸：" + sampleNote);
    }
    if (staleNote != null) {
      out.add("新鲜度闸：" + staleNote);
    }
    return out;
  }

  // ==================================================================
  // 小工具
  // ==================================================================

  /** 时效归一化的区间（null 表示这一维没有可比项）。 */
  public record Span(Integer min, Integer max) {}

  /** 成本归一化的区间（min/max 皆 null 表示没有可比项）。 */
  public record CostSpan(BigDecimal min, BigDecimal max) {}

  private static BigDecimal one() {
    return BigDecimal.ONE;
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static BigDecimal scale(BigDecimal value) {
    return value.setScale(SCORE_SCALE, RoundingMode.HALF_UP);
  }

  private static String plain(BigDecimal value) {
    return value == null ? "—" : value.stripTrailingZeros().toPlainString();
  }

  private static String ratio(BigDecimal value) {
    return value == null ? "—" : value.setScale(4, RoundingMode.HALF_UP).toPlainString();
  }

  /** 评分无法计算时的异常（输入不合法，不是「算不出钱」）。 */
  public static final class ScoringException extends RuntimeException {
    public ScoringException(String message) {
      super(message);
    }
  }

}
