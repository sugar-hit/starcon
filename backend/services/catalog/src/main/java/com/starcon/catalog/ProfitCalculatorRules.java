package com.starcon.catalog;

import com.starcon.catalog.ProfitCalculatorModels.CalcMode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 5.3.1.1 利润测算器：利润拆解的**纯算术**实现（无 IO、无租户上下文，可单测）。
 *
 * <p>蓝图加粗句：「<b>这是定价决策的核心工具</b>，必须显式列出每一项扣减并允许展开查看来源，
 * 不能只给一个毛利率数字。」本类只负责「给定每一项扣减，怎么算」，
 * 「这一项扣减从哪来、能不能取到」由 {@link ProfitCalculatorService} 负责。
 *
 * <h2>🔴 纪律一：正向与反向走同一套结构</h2>
 *
 * <p>正向（给定售价算毛利）与反向（给定目标毛利率倒推售价）在本类里是**同一个方程**：
 *
 * <pre>
 *   毛利 = 售价 − 平台费 − 物流 − 采购 − 头程 − 包装 − 关税 − 其他直接成本
 *   平台费 = 售价 × 费率 + 固定费
 * </pre>
 *
 * <p>⇒ 保本价 = 解「毛利 = 0」，目标毛利价 = 解「毛利 = 目标毛利率 × 售价」，
 * 两个都是 {@link #solvePrice} 的同一次调用，<b>不存在第二套近似公式</b>。
 * 这正是蓝图「反向测算要走同一套扣减结构」的技术落点。
 *
 * <h2>🔴 纪律二：缺项就不给毛利率数字</h2>
 *
 * <p>{@link Input} 里每一项扣减都有明确的「有没有值」。<b>只要有一项缺失</b>，
 * {@link Output} 的 {@code grossProfit} / {@code grossMarginRate} / {@code breakEvenPrice} /
 * {@code targetMarginPrice} / {@code breakEvenVolume} 一律是 <b>null</b>，
 * 不是 0、不是估算值。此时只给 {@code revenueLeftForMissingDeductions}：
 * 「售价减掉已知扣减后还剩多少可用于覆盖缺失扣减」——<b>那不是毛利</b>，
 * 名字与语义都刻意不跟毛利混。
 *
 * <p>理由：上游 5.2.3.1（平台费用）与 5.2.3.2（物流费用）未交付，
 * 缺了它们的毛利率是**系统性虚高**的；而下游 5.3.2.1 模板与 5.4 调价会
 * **程序化**消费本模块的输出，页面上的一句提示拦不住一个自动改价的规则。
 *
 * <h2>🔴 纪律三：全程 BigDecimal，零浮点</h2>
 *
 * <p>金额 4 位小数（HALF_UP，与 V215 成本构成的精度一致），费率/毛利率 6 位小数。
 * 毛利率也是精确值 —— {@code double} 承载不了「毛利率 0.333333」这种要被拿去改价的数。
 */
public final class ProfitCalculatorRules {

  private ProfitCalculatorRules() {}

  /** 金额精度：4 位小数（与 V215 的成本分项同一精度，够分辨「差一分钱」）。 */
  public static final int MONEY_SCALE = 4;

  /** 费率/毛利率精度：6 位小数。 */
  public static final int RATE_SCALE = 6;

  /** 舍入方式：四舍五入。全部金额与费率统一用它，页面上写明。 */
  public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

  /** 毛利率的最大可行值：目标毛利率 + 平台费率必须小于 1，否则方程无解。 */
  private static final BigDecimal ONE = BigDecimal.ONE;

  // ================================================================ 输入

  /**
   * 一个成本分项（已换算到售价币种）。
   *
   * @param available 取到了吗。false 时 {@code amount} 必为 null
   * @param partial 取到了但**不完整**（例如部分到货记录折算不出基础单位被排除）。
   *     partial 的分项**有值**，但那个值只覆盖了一部分事实 ⇒ 合计只是「已知部分合计」。
   */
  public record CostInput(
      String code, boolean available, boolean partial, BigDecimal amount, String unavailableReason) {}

  /** 引擎输入：全部金额单位是「每单位」，币种是售价币种。 */
  public record Input(
      /** 售价。PRICE 模式由调用方给；TARGET_MARGIN 模式由 {@link #solvePrice} 解出。 */
      BigDecimal revenue,
      /** 目标毛利率（小数口径）。可空。 */
      BigDecimal targetMarginRate,
      /** 数量（用于阶梯与运费）。 */
      BigDecimal quantity,
      /** 平台费率（售价的小数比例）。可空 = 这一项取不到。 */
      BigDecimal platformFeeRate,
      /** 平台固定费（每单固定金额）。可空 = 按 0 计（口径在瀑布图里写明）。 */
      BigDecimal platformFeeFixed,
      /** 物流每单位运费（已按数量加权）。可空 = 这一项取不到。 */
      BigDecimal logisticsPerUnit,
      /** 月度固定成本（盈亏平衡销量的固定费用基数）。可空 = 不输出盈亏平衡销量。 */
      BigDecimal monthlyFixedCost,
      /** 物流阶梯（用于盈亏平衡销量）。可空。 */
      List<TierSpec> tiers,
      /** 成本分项（采购/头程/包装/关税/其他直接成本）。 */
      List<CostInput> costs) {}

  /** 物流阶梯的一段（引擎内部形态）。 */
  public record TierSpec(BigDecimal upToQuantity, BigDecimal perUnitCost) {}

  // ================================================================ 输出

  /** 引擎输出。{@code complete=false} 时所有「利润类」字段都是 null。 */
  public record Output(
      BigDecimal revenue,
      BigDecimal platformFeeAmount,
      BigDecimal logisticsAmount,
      BigDecimal knownCostTotal,
      BigDecimal totalDeductions,
      BigDecimal grossProfit,
      BigDecimal grossMarginRate,
      BigDecimal breakEvenPrice,
      BigDecimal targetMarginPrice,
      BigDecimal breakEvenVolume,
      BigDecimal unitContribution,
      boolean complete,
      String incompleteReason) {}

  // ================================================================ 主流程

  /**
   * 一次测算。
   *
   * @param mode 测算方向。{@code PRICE} 用给定售价；{@code TARGET_MARGIN} 先解售价再算。
   */
  public static Output evaluate(CalcMode mode, Input input) {
    var costs = input.costs() == null ? List.<CostInput>of() : input.costs();

    // ---- 完整性判定：每一项扣减都必须有值 ----
    boolean platformFeeKnown = input.platformFeeRate() != null;
    boolean logisticsKnown = input.logisticsPerUnit() != null;
    boolean costComplete = true;
    for (var cost : costs) {
      if (!cost.available() || cost.partial()) costComplete = false;
    }
    boolean complete = platformFeeKnown && logisticsKnown && costComplete;

    // ---- 已知成本合计（不含平台费与物流：那两项在下面单列）----
    BigDecimal knownCostTotal = BigDecimal.ZERO;
    for (var cost : costs) {
      if (cost.available() && cost.amount() != null) {
        knownCostTotal = knownCostTotal.add(cost.amount());
      }
    }
    knownCostTotal = money(knownCostTotal);

    // ---- 反向：目标毛利率 → 售价（与正向共用同一个方程）----
    BigDecimal revenue = input.revenue();
    if (mode == CalcMode.TARGET_MARGIN) {
      if (!complete) {
        return new Output(
            null, null, null, knownCostTotal, null, null, null, null, null, null, null, false,
            "成本不完整：目标毛利率倒推售价需要每一项扣减都取得到，否则解出来的售价是错的。"
                + "缺哪几项见 blockingGaps。");
      }
      BigDecimal margin = input.targetMarginRate() == null ? BigDecimal.ZERO : input.targetMarginRate();
      revenue = solvePrice(margin, input.platformFeeRate(), input.platformFeeFixed(),
          input.logisticsPerUnit(), knownCostTotal);
      if (revenue == null) {
        return new Output(
            null, null, null, knownCostTotal, null, null, null, null, null, null, null, false,
            "目标毛利率 " + percent(margin) + " 加平台费率之后不小于 100%，方程无解 —— "
                + "这个目标毛利率在当前费率下根本达不到。");
      }
    }

    // ---- 正向：按给定售价算拆解 ----
    if (revenue == null) {
      return new Output(
          null, null, null, knownCostTotal, null, null, null, null, null, null, null, false,
          "没有售价：按售价测算时必须给售价。");
    }
    revenue = money(revenue);
    if (revenue.signum() <= 0) {
      return new Output(
          null, null, null, knownCostTotal, null, null, null, null, null, null, null, false,
          "售价必须大于 0。");
    }

    BigDecimal platformFee = null;
    if (platformFeeKnown) {
      platformFee = money(revenue.multiply(input.platformFeeRate()));
      if (input.platformFeeFixed() != null) {
        platformFee = money(platformFee.add(input.platformFeeFixed()));
      }
    }
    BigDecimal logistics =
        logisticsKnown ? money(input.logisticsPerUnit()) : null;

    BigDecimal totalDeductions = null;
    BigDecimal grossProfit = null;
    BigDecimal grossMarginRate = null;
    if (complete) {
      totalDeductions = money(platformFee.add(logistics).add(knownCostTotal));
      grossProfit = money(revenue.subtract(totalDeductions));
      grossMarginRate = rate(grossProfit.divide(revenue, RATE_SCALE + 4, ROUNDING));
    }

    // ---- 保本价与目标毛利价：同一个方程，只换右端的毛利率 ----
    BigDecimal breakEvenPrice = null;
    BigDecimal targetMarginPrice = null;
    if (complete) {
      breakEvenPrice = solvePrice(BigDecimal.ZERO, input.platformFeeRate(), input.platformFeeFixed(),
          input.logisticsPerUnit(), knownCostTotal);
      if (input.targetMarginRate() != null) {
        targetMarginPrice =
            solvePrice(input.targetMarginRate(), input.platformFeeRate(), input.platformFeeFixed(),
                input.logisticsPerUnit(), knownCostTotal);
      }
    }

    // ---- 盈亏平衡销量：固定成本 ÷ 单位贡献毛利（阶梯分段求解）----
    BigDecimal breakEvenVolume = null;
    if (complete && input.monthlyFixedCost() != null) {
      breakEvenVolume =
          breakEvenVolume(
              revenue, input.platformFeeRate(), input.platformFeeFixed(), knownCostTotal,
              input.tiers(), input.logisticsPerUnit(), input.monthlyFixedCost());
    }

    BigDecimal unitContribution = grossProfit;

    return new Output(
        revenue,
        platformFee,
        logistics,
        knownCostTotal,
        totalDeductions,
        grossProfit,
        grossMarginRate,
        breakEvenPrice,
        targetMarginPrice,
        breakEvenVolume,
        unitContribution,
        complete,
        complete ? null : "成本不完整");
  }

  // ================================================================ 反向求解

  /**
   * 解「售价 = ?」，使毛利率等于 {@code margin}。
   *
   * <p>推导（与正向的毛利公式**逐字同构**）：
   *
   * <pre>
   *   毛利 = 售价 × (1 − 费率 − 毛利率) − 固定费 − 物流 − 已知成本 = 0
   *   ⇒ 售价 = (固定费 + 物流 + 已知成本) ÷ (1 − 费率 − 毛利率)
   * </pre>
   *
   * <p>分母 ≤ 0 ⇒ 无解（返回 null）：目标毛利率加上平台费率已经不小于 100%，
   * 这个目标在当前费率下达不到 —— 返回一个数会是在骗人。
   */
  public static BigDecimal solvePrice(
      BigDecimal margin,
      BigDecimal platformFeeRate,
      BigDecimal platformFeeFixed,
      BigDecimal logisticsPerUnit,
      BigDecimal knownCostTotal) {
    if (platformFeeRate == null || logisticsPerUnit == null) return null;
    BigDecimal denominator =
        ONE.subtract(platformFeeRate).subtract(margin == null ? BigDecimal.ZERO : margin);
    if (denominator.signum() <= 0) return null;
    BigDecimal numerator =
        (platformFeeFixed == null ? BigDecimal.ZERO : platformFeeFixed)
            .add(logisticsPerUnit)
            .add(knownCostTotal);
    return money(numerator.divide(denominator, MONEY_SCALE + 6, ROUNDING));
  }

  // ================================================================ 物流阶梯

  /**
   * 按数量把阶梯摊成段：每段多少单、每单位多少运费。
   *
   * <p>阶梯是「数量 → 每单位运费」的映射（蓝图输入句「数量（用于阶梯与运费）」）。
   * 段序升序表示数量从小到大；最后一段的 {@code upToQuantity} 为空 = 之后无限。
   */
  public static List<ProfitCalculatorModels.LogisticsBand> bands(
      List<TierSpec> tiers, BigDecimal quantity, BigDecimal flatPerUnit) {
    var out = new ArrayList<ProfitCalculatorModels.LogisticsBand>();
    BigDecimal qty = quantity == null ? BigDecimal.ONE : quantity;
    if (tiers == null || tiers.isEmpty()) {
      BigDecimal cost = flatPerUnit == null ? BigDecimal.ZERO : flatPerUnit;
      out.add(
          new ProfitCalculatorModels.LogisticsBand(
              1, BigDecimal.ONE, null, qty, money(cost), money(qty.multiply(cost))));
      return out;
    }
    BigDecimal consumed = BigDecimal.ZERO;
    int seq = 1;
    for (var tier : tiers) {
      BigDecimal from = consumed.add(BigDecimal.ONE);
      BigDecimal units = BigDecimal.ZERO;
      if (tier.upToQuantity() != null) {
        BigDecimal capped = tier.upToQuantity().min(qty);
        if (capped.compareTo(consumed) > 0) units = capped.subtract(consumed);
      } else {
        if (qty.compareTo(consumed) > 0) units = qty.subtract(consumed);
      }
      out.add(
          new ProfitCalculatorModels.LogisticsBand(
              seq++, from, tier.upToQuantity(), units, money(tier.perUnitCost()),
              money(units.multiply(tier.perUnitCost()))));
      consumed = consumed.add(units);
    }
    return out;
  }

  /**
   * 阶梯覆盖是否完整：最后一段必须是无上限的，否则超出阶梯的数量没有运费定义。
   *
   * <p>不完整时**不补一个默认运费** —— 那等于凭空编一笔成本。
   */
  public static boolean tierCoverageComplete(List<TierSpec> tiers, BigDecimal quantity) {
    if (tiers == null || tiers.isEmpty()) return true;
    var last = tiers.get(tiers.size() - 1);
    if (last.upToQuantity() == null) return true;
    BigDecimal qty = quantity == null ? BigDecimal.ONE : quantity;
    return qty.compareTo(last.upToQuantity()) <= 0;
  }

  /**
   * 给定数量下的**每单位**物流成本（按阶梯加权平均）。
   *
   * <p>阶梯不覆盖全部数量时返回 null —— 那一段没有运费定义，
   * 不能拿前面几段的平均值糊过去。
   */
  public static BigDecimal logisticsPerUnit(
      List<TierSpec> tiers, BigDecimal quantity, BigDecimal flatPerUnit) {
    BigDecimal qty = quantity == null ? BigDecimal.ONE : quantity;
    if (tiers == null || tiers.isEmpty()) {
      return flatPerUnit == null ? null : money(flatPerUnit);
    }
    if (!tierCoverageComplete(tiers, qty)) return null;
    var bandList = bands(tiers, qty, flatPerUnit);
    BigDecimal total = BigDecimal.ZERO;
    for (var band : bandList) total = total.add(band.bandTotal());
    if (qty.signum() <= 0) return null;
    return money(total.divide(qty, MONEY_SCALE + 6, ROUNDING));
  }

  /**
   * 盈亏平衡销量：固定成本 ÷ 单位贡献毛利，<b>按阶梯分段精确求解</b>。
   *
   * <p>为什么不能简单除：阶梯运费下，单位贡献毛利**随数量变化**（换一段，运费单价就变），
   * 所以盈亏平衡点必须分段解：
   *
   * <pre>
   *   逐段累加：段内单位贡献 c 是常数（售价与费率固定，只有运费单价随段变），
   *   累加到第 n 段末的利润 P；若 c &gt; 0，本段内还需 ceil(−P ÷ c) 单即可转正。
   * </pre>
   *
   * <p>返回 null 表示「永远无法盈亏平衡」（每段的单位贡献都不为正）。
   */
  public static BigDecimal breakEvenVolume(
      BigDecimal revenue,
      BigDecimal platformFeeRate,
      BigDecimal platformFeeFixed,
      BigDecimal knownCostTotal,
      List<TierSpec> tiers,
      BigDecimal flatPerUnit,
      BigDecimal monthlyFixedCost) {
    if (revenue == null || platformFeeRate == null || monthlyFixedCost == null) return null;
    if (monthlyFixedCost.signum() <= 0) {
      // 固定成本为 0 ⇒ 第一单就转正，盈亏平衡销量是 0。
      return BigDecimal.ZERO.setScale(0, RoundingMode.CEILING);
    }
    // 没有阶梯时，用「每单位运费 = flatPerUnit」的**一段**来算 ——
    // 漏掉这一段会让单位贡献里少掉运费，盈亏平衡销量会被算小。
    var specs =
        tiers == null || tiers.isEmpty()
            ? List.of(new TierSpec(null, flatPerUnit == null ? BigDecimal.ZERO : flatPerUnit))
            : tiers;
    BigDecimal platformFee =
        money(revenue.multiply(platformFeeRate))
            .add(platformFeeFixed == null ? BigDecimal.ZERO : platformFeeFixed);
    BigDecimal consumed = BigDecimal.ZERO;
    // 🔴 起始利润是 **−固定成本**，不是 0：卖 0 单时也要付固定成本。
    // 写成 0 会让盈亏平衡销量少算「卖多少单才够本」的那一段。
    BigDecimal profit = monthlyFixedCost.negate();
    for (var tier : specs) {
      BigDecimal capacity;
      if (tier.upToQuantity() == null) {
        capacity = null;
      } else {
        capacity = tier.upToQuantity().subtract(consumed);
        if (capacity.signum() <= 0) continue;
      }
      BigDecimal contribution =
          revenue.subtract(platformFee).subtract(knownCostTotal).subtract(tier.perUnitCost());
      if (contribution.signum() > 0) {
        BigDecimal needed = profit.negate().divide(contribution, 0, RoundingMode.CEILING);
        if (capacity == null || needed.compareTo(capacity) <= 0) {
          return consumed.add(needed).setScale(0, RoundingMode.CEILING);
        }
      }
      if (capacity == null) {
        // 无上限的段里单位贡献不为正 ⇒ 再多也赚不回来。
        return null;
      }
      profit = profit.add(capacity.multiply(contribution));
      consumed = consumed.add(capacity);
    }
    return null;
  }

  // ================================================================ 小工具

  /** 金额：4 位小数，HALF_UP。 */
  public static BigDecimal money(BigDecimal value) {
    return value == null ? null : value.setScale(MONEY_SCALE, ROUNDING);
  }

  /** 费率/毛利率：6 位小数，HALF_UP。 */
  public static BigDecimal rate(BigDecimal value) {
    return value == null ? null : value.setScale(RATE_SCALE, ROUNDING);
  }

  /** 百分比展示（口径串用）：0.25 → 「25%」。 */
  public static String percent(BigDecimal value) {
    if (value == null) return "—";
    return value.multiply(BigDecimal.valueOf(100)).setScale(2, ROUNDING).stripTrailingZeros().toPlainString()
        + "%";
  }
}
