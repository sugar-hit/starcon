package com.starcon.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 5.2.1.2 到岸成本的**纯计算**规则（无 IO，可单测）。
 *
 * <p>本类只回答一个问题：**批次级的一笔钱，怎么落到这一批的各个 SKU 行上。**
 *
 * <h2>🔴 分摊必须逐字守恒</h2>
 *
 * <p>批次级的三笔钱（国际头程、目的地操作费、批次清关费）要摊到该批的各个 SKU 行上。
 * 摊完之后，各行金额之和**必须严格等于**来源总额：
 *
 * <ul>
 *   <li>比来源大 ⇒ 多算了成本（到岸成本被夸大，定价会偏高）；
 *   <li>比来源小 ⇒ 漏算了成本（到岸成本被低估，定价会偏低 —— <b>这正是本模块最危险的失败模式</b>）。
 * </ul>
 *
 * <p>⇒ 本类用**最大余数法**：先按比例取整到 {@link #MONEY_SCALE}，
 * 余下的分（precision of 1e-4）按小数部分从大到小逐个补给份额最大的行。
 * 于是「份额之和 == 来源总额」在**构造上**成立，不依赖舍入恰好凑巧。
 * 这与 10.6.1.1 成本分析的「分摊逐行合计严格等于来源总额」是同一条纪律。
 *
 * <h2>🔴 数量比例的适用范围</h2>
 *
 * <p>目前只有**按数量比例**一种口径（{@code allocation_method = QUANTITY_PROPORTIONAL}）。
 * 完整的分摊规则（按重量 / 体积 / 金额 + 人工微调 + 规则版本历史）归
 * <b>5.2.1.3 成本分摊</b>（当前未交付）—— 本模块不代它开形状，
 * 也不把「只有一个规则」说成「规则体系已交付」。
 *
 * <h2>🔴 金额与数量一律 BigDecimal，禁浮点</h2>
 *
 * <p>AGENTS.md：禁止浮点记账。本类的每一次除法都带 scale 与舍入模式
 * （{@link #MONEY_SCALE} 位 + {@link RoundingMode#HALF_UP}），
 * 与 V80 / V90 / V137 / V215 的 {@code numeric(19,4)} 同一精度。
 */
public final class LandedCostRules {

  /** 对外金额精度：4 位小数（0.0001 货币单位）。与 V80 / V90 / V137 同精度。 */
  public static final int MONEY_SCALE = 4;

  /** 中间比例精度：分摊份额的中间结果保留 10 位，避免二次除法把精度吃掉。 */
  public static final int RATIO_SCALE = 10;

  /** 舍入模式：四舍五入（HALF_UP）。与本仓其它金额计算保持一致。 */
  public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

  /** 唯一的分摊口径。 */
  public static final String ALLOCATION_QUANTITY_PROPORTIONAL = "QUANTITY_PROPORTIONAL";

  private LandedCostRules() {}

  /**
   * 按数量比例把批次级金额摊到各行（**最大余数法，逐字守恒**）。
   *
   * @param total 批次级金额；{@code null} 表示**这笔钱没有数**（缺口）
   * @param quantities 各行的数量（基础单位）
   * @return 与 {@code quantities} 等长的分摊金额；{@code total} 为 null 时全部为 null
   *     （**不是 0**：0 会被读成「这笔钱是 0」，而真相是「没查到」）
   * @throws IllegalArgumentException 数量之和为 0（无法按比例分摊）
   */
  public static List<BigDecimal> allocateByQuantity(BigDecimal total, List<BigDecimal> quantities) {
    if (quantities == null || quantities.isEmpty()) {
      return List.of();
    }
    if (total == null) {
      // 没有数 ⇒ 每一行都没有数。返回 null 而不是 0。
      var unknown = new ArrayList<BigDecimal>();
      for (int i = 0; i < quantities.size(); i++) {
        unknown.add(null);
      }
      return unknown;
    }
    BigDecimal weightSum = BigDecimal.ZERO;
    for (var quantity : quantities) {
      weightSum = weightSum.add(nz(quantity));
    }
    if (weightSum.signum() <= 0) {
      throw new IllegalArgumentException("数量之和为 0，无法按比例分摊");
    }

    int n = quantities.size();
    var amounts = new ArrayList<BigDecimal>(n);
    var remainders = new ArrayList<BigDecimal>(n);
    BigDecimal allocated = BigDecimal.ZERO;
    for (var quantity : quantities) {
      BigDecimal weight = nz(quantity);
      BigDecimal share =
          total.multiply(weight).divide(weightSum, MONEY_SCALE, ROUNDING);
      amounts.add(share);
      remainders.add(
          total.multiply(weight).divide(weightSum, RATIO_SCALE, ROUNDING)
              .subtract(share));
      allocated = allocated.add(share);
    }

    // 余数（可能是负的，因为 ROUNDING 是 HALF_UP 而不是截断）按绝对值从大到小补齐。
    // 🔴 补的是**最小货币单位**（1e-4）：来源总额与各行金额同为 4 位小数，
    // 余数必然是 1e-4 的整数倍 ⇒ 补完之后「各行之和 == 来源总额」逐字成立。
    BigDecimal remainder = total.subtract(allocated);
    if (remainder.signum() != 0) {
      var order = new ArrayList<Integer>(n);
      for (int i = 0; i < n; i++) {
        order.add(i);
      }
      order.sort((a, b) -> remainders.get(b).abs().compareTo(remainders.get(a).abs()));
      BigDecimal unit = BigDecimal.ONE.movePointLeft(MONEY_SCALE);
      long units = remainder.divide(unit, 0, RoundingMode.HALF_UP).longValueExact();
      int step = units > 0 ? 1 : -1;
      int remaining = (int) Math.abs(units);
      for (int i = 0; i < n && remaining > 0; i++) {
        int index = order.get(i % n);
        amounts.set(index, amounts.get(index).add(unit.multiply(BigDecimal.valueOf(step))));
        remaining--;
      }
    }
    return amounts;
  }

  /**
   * 单位金额（每基础单位）。
   *
   * @param amount 金额；{@code null} 表示没有数
   * @param baseQuantity 基础数量
   * @return 单位金额；{@code amount} 为 null 或数量为 0 时返回 null
   */
  public static BigDecimal unitAmount(BigDecimal amount, BigDecimal baseQuantity) {
    if (amount == null || baseQuantity == null || baseQuantity.signum() == 0) {
      return null;
    }
    return amount.divide(baseQuantity, MONEY_SCALE, ROUNDING);
  }

  /**
   * 合计（只加**本币种**的可用金额）。
   *
   * <p>🔴 跨币种绝不相加：调用方只把同币种的金额传进来。
   * 全部为 null 时返回 null（**算不出来**），不是 0。
   */
  public static BigDecimal total(List<BigDecimal> amounts) {
    if (amounts == null || amounts.isEmpty()) {
      return null;
    }
    BigDecimal sum = BigDecimal.ZERO;
    boolean any = false;
    for (var amount : amounts) {
      if (amount == null) continue;
      any = true;
      sum = sum.add(amount);
    }
    if (!any) {
      return null;
    }
    return sum.setScale(MONEY_SCALE, ROUNDING);
  }

  /** null 安全取零。 */
  public static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
