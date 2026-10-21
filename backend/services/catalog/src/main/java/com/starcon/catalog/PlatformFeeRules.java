package com.starcon.catalog;

import static com.starcon.catalog.PlatformFeeModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 5.2.3.1 平台费用：<b>纯算术与校验</b>（不碰数据库、不碰 HTTP）。
 *
 * <p>它单独成一个类有两个理由：
 *
 * <ol>
 *   <li><b>算式必须能被单独证明</b>。蓝图加粗句要的是「规则参数用于事前测算」，
 *       而「这条规则算出来的费用是多少」是一条**恒等式**（费用 = 销售额 × 费率），
 *       它必须有一个不依赖数据库的单元测试钉住 —— 集成测试只能证明「落库了」，
 *       证明不了「算对了」。
 *   <li><b>规则与实际费用共用同一个算式</b>。测算（事前）与差异对照（事后）
 *       用的是**同一个** {@link #apply}：差异 = 实际 − 按规则测算。
 *       两边各写一套算法的话，差异里的「按规则测算」与测算页的「测算」就会分叉，
 *       而差异恰恰是用来解释测算为什么不准的 —— 两套算法会让它解释不了任何东西。
 * </ol>
 *
 * <p><b>🔴 全程 BigDecimal，禁浮点</b>（AGENTS.md：费率/比例也必须是精确值）。
 * 费率的单位是<b>小数</b>（{@code 0.15} = 15%），见 {@code V226__platform_fee.sql} 第三节。
 */
public final class PlatformFeeRules {

  private PlatformFeeRules() {}

  /** 金额精度：与本仓其它金额列一致（4 位小数）。 */
  public static final int MONEY_SCALE = 4;

  /**
   * 比例费率的取值域上限。
   *
   * <p>费率是<b>小数</b>口径，所以上限是 1（= 100%）。把 15（当成 15% 的百分数）
   * 填进来的错误会在这里被当场拒掉，而不是变成 1500% 的扣项。
   */
  public static final BigDecimal MAX_RATE = BigDecimal.ONE;

  // ================================================================ 词表

  /** 费用类型 → 中文名。未知取值原样返回（不假装认识）。 */
  public static String feeTypeLabel(String value) {
    if (value == null) return null;
    for (FeeType type : FeeType.values()) {
      if (type.name().equals(value)) return type.label();
    }
    return value;
  }

  /** 费用类型 → 口径说明。 */
  public static String feeTypeBasis(String value) {
    if (value == null) return null;
    for (FeeType type : FeeType.values()) {
      if (type.name().equals(value)) return type.basis();
    }
    return null;
  }

  /** 计价形态 → 中文名。 */
  public static String chargeBasisLabel(String value) {
    if (value == null) return null;
    for (ChargeBasis basis : ChargeBasis.values()) {
      if (basis.name().equals(value)) return basis.label();
    }
    return value;
  }

  /** 计价形态 → 口径说明。 */
  public static String chargeBasisBasis(String value) {
    if (value == null) return null;
    for (ChargeBasis basis : ChargeBasis.values()) {
      if (basis.name().equals(value)) return basis.basis();
    }
    return null;
  }

  /** 分摊口径 → 中文名。 */
  public static String allocationBasisLabel(String value) {
    if (value == null) return null;
    for (AllocationBasis basis : AllocationBasis.values()) {
      if (basis.name().equals(value)) return basis.label();
    }
    return value;
  }

  /** 实际费用的来源 → 中文名。 */
  public static String actualSourceLabel(String value) {
    if ("MANUAL_OBSERVATION".equals(value)) return "人工登记的平台账单观察值";
    return value;
  }

  // ================================================================ 规则校验

  /**
   * 校验一条费用规则的入参。
   *
   * <p>🔴 这道校验是为了**早点告诉人**，不是安全边界 —— DB 层的 CHECK 才是。
   * 两边的口径必须一致：服务层少判一项，就有人能写出一条 DB 层会拒的规则，
   * 然后看到一条看不懂的 23514。
   *
   * @return 问题清单；空列表 = 通过
   */
  public static List<String> validateRule(FeeRuleInput input) {
    List<String> problems = new ArrayList<>();
    if (input == null) {
      problems.add("规则内容必填");
      return problems;
    }
    if (isBlank(input.platformCode())) {
      problems.add("平台必填：费用规则挂在「平台 / 站点 / 类目」三个维度上，平台是主维度");
    }
    if (isBlank(input.feeType())) {
      problems.add("费用类型必填");
    } else if (feeTypeLabel(input.feeType()).equals(input.feeType())) {
      problems.add("费用类型不认识：" + input.feeType());
    }
    if (isBlank(input.chargeBasis())) {
      problems.add("计价形态必填：这条费率到底怎么算必须有唯一答案");
    } else if (chargeBasisLabel(input.chargeBasis()).equals(input.chargeBasis())) {
      problems.add("计价形态不认识：" + input.chargeBasis());
    }
    if (input.effectiveFrom() == null) {
      problems.add("生效起点必填：这条费率从哪一刻开始算数");
    }
    if (input.effectiveTo() != null && input.effectiveFrom() != null
        && !input.effectiveTo().isAfter(input.effectiveFrom())) {
      problems.add("生效终点必须晚于生效起点");
    }

    boolean subscription = "SUBSCRIPTION".equals(input.feeType());
    String basis = input.chargeBasis();
    if (!subscription && "TIERED_PERCENTAGE".equals(basis)) {
      // 阶梯的档位在阶梯端点里维护，这里只校验费率本身不越界。
      if (input.rate() != null) {
        problems.add("阶梯形态不填费率（费率在阶梯的每一档里填）");
      }
    }
    if (!subscription && "PERCENTAGE_OF_SALE".equals(basis)) {
      if (input.rate() == null) problems.add("销售额比例形态必须填费率");
      else if (input.rate().signum() < 0 || input.rate().compareTo(MAX_RATE) > 0) {
        problems.add("费率必须在 0 到 1 之间（小数口径：0.15 = 15%）：" + input.rate());
      }
      if (input.fixedAmount() != null) problems.add("销售额比例形态不填固定额：两个都填时「怎么算」没有答案");
    }
    if (!subscription && ("FIXED_PER_ORDER".equals(basis) || "FIXED_PER_UNIT".equals(basis))) {
      if (input.fixedAmount() == null) problems.add("固定额形态必须填固定额");
      else if (input.fixedAmount().signum() < 0) problems.add("固定额不能为负");
      if (isBlank(input.currency())) problems.add("固定额必须填币种");
    }
    if (subscription) {
      if (!"FIXED_PER_ORDER".equals(basis) && !"FIXED_PER_UNIT".equals(basis)) {
        problems.add("订阅费的计价形态只能是「每单固定额」或「每件固定额」");
      }
      if (input.periodTotalAmount() == null) {
        problems.add("订阅费必须填周期总额：订阅费是一笔周期总额，不是每单的钱");
      } else if (input.periodTotalAmount().signum() <= 0) {
        problems.add("订阅费周期总额必须大于 0");
      }
      if (isBlank(input.periodCurrency())) problems.add("订阅费必须填周期总额的币种");
      if (isBlank(input.allocationBasis())) {
        problems.add("订阅费必须填分摊口径（按单分摊 / 按件分摊）");
      } else if (!"PER_ORDER".equals(input.allocationBasis())
          && !"PER_UNIT".equals(input.allocationBasis())) {
        problems.add("订阅费分摊口径不认识：" + input.allocationBasis());
      }
      if (input.allocationDivisor() == null) {
        problems.add("订阅费必须填分摊基数（期间单量 / 件量）：没有基数就摊不出来");
      } else if (input.allocationDivisor().signum() <= 0) {
        problems.add("订阅费分摊基数必须大于 0");
      }
      if (input.fixedAmount() != null) problems.add("订阅费不填固定额：它的金额来自周期总额分摊");
      if (input.rate() != null) problems.add("订阅费不填费率：它的金额来自周期总额分摊");
    }
    if (!subscription && input.periodTotalAmount() != null) {
      problems.add("只有订阅费才填周期总额");
    }
    return problems;
  }

  /**
   * 校验阶梯。
   *
   * <p>阶梯的档位必须**首尾相接且不重叠**：下界递增、上界等于下一档的下界。
   * 不相接的话，销售额落在两档之间的空隙里就没有费率可用 ——
   * 而服务层不会替用户挑一档，只会报「该金额不适用」。
   */
  public static List<String> validateTiers(List<FeeTierInput> tiers) {
    List<String> problems = new ArrayList<>();
    if (tiers == null || tiers.isEmpty()) return problems;
    List<FeeTierInput> sorted = new ArrayList<>(tiers);
    sorted.sort(Comparator.comparingInt(FeeTierInput::seq));
    for (int i = 0; i < sorted.size(); i++) {
      FeeTierInput tier = sorted.get(i);
      if (tier.seq() <= 0) problems.add("档位序号必须从 1 起");
      if (tier.minAmount() == null || tier.minAmount().signum() < 0) {
        problems.add("档位下界不能为负");
        continue;
      }
      if (tier.maxAmount() != null && tier.maxAmount().compareTo(tier.minAmount()) <= 0) {
        problems.add("档位上界必须严格大于下界（第 " + tier.seq() + " 档）");
      }
      if (tier.rate() == null
          || tier.rate().signum() < 0
          || tier.rate().compareTo(MAX_RATE) > 0) {
        problems.add("档位费率必须在 0 到 1 之间（小数口径，第 " + tier.seq() + " 档）");
      }
      if (i > 0) {
        FeeTierInput previous = sorted.get(i - 1);
        if (previous.maxAmount() != null
            && previous.maxAmount().compareTo(tier.minAmount()) != 0) {
          problems.add(
              "阶梯必须首尾相接：第 "
                  + previous.seq()
                  + " 档的上界（"
                  + previous.maxAmount()
                  + "）与第 "
                  + tier.seq()
                  + " 档的下界（"
                  + tier.minAmount()
                  + "）之间不能有缝，否则落在这个金额段的销售没有费率可用");
        }
        if (previous.maxAmount() == null) {
          problems.add("第 " + previous.seq() + " 档没有上界（无上限），后面不能再有档位");
        }
      }
    }
    return problems;
  }

  // ================================================================ 规则视图（算式的输入）

  /** 算式需要的规则内容（与数据库解耦，便于单元测试）。 */
  public record RuleView(
      UUID id,
      String feeType,
      ChargeBasis chargeBasis,
      BigDecimal rate,
      BigDecimal fixedAmount,
      String currency,
      BigDecimal periodTotalAmount,
      String periodCurrency,
      AllocationBasis allocationBasis,
      BigDecimal allocationDivisor,
      List<TierView> tiers) {}

  /** 阶梯的一档。 */
  public record TierView(BigDecimal minAmount, BigDecimal maxAmount, BigDecimal rate) {}

  /**
   * 规则套用到一次销售上的结果。
   *
   * @param amount 算出来的费用；{@code applicable=false} 时为 null（**不是 0**）
   * @param currency 费用币种；比例形态下是销售额的币种
   * @param rate 本次用到的费率（阶梯形态下是命中的那一档的费率）
   * @param basis 算式说明（人话，直接显示）
   * @param applicable 规则是否适用于本次金额；false = 规则存在但落不进任何档
   * @param note 补充说明
   */
  public record AppliedFee(
      BigDecimal amount,
      String currency,
      BigDecimal rate,
      String basis,
      boolean applicable,
      String note) {}

  // ================================================================ 算式

  /**
   * 把一条规则套到一次销售上，算出费用。
   *
   * <p>🔴 **算不出来就是 {@code applicable=false} + {@code amount=null}，绝不是 0。**
   * 「规则不适用」与「平台没收钱」是两件事：前者是「算不出来」，
   * 后者是「确实是 0」。把前者报成 0 会让费用偏低、毛利偏高，而页面上看不出异常。
   *
   * @param rule 规则
   * @param tiers 该规则的阶梯（可空）
   * @param saleAmount 销售额
   * @param quantity 数量
   * @param saleCurrency 销售额币种（比例形态下费用与它同币种）
   */
  public static AppliedFee apply(
      RuleView rule, List<TierView> tiers, BigDecimal saleAmount, BigDecimal quantity, String saleCurrency) {
    BigDecimal sale = nz(saleAmount);
    BigDecimal qty = nz(quantity);
    if (rule == null) {
      return new AppliedFee(null, null, null, "", false, "没有命中的规则");
    }
    // 订阅费走的是「周期总额 ÷ 分摊基数」，与固定额形态不是同一件事。
    if ("SUBSCRIPTION".equals(rule.feeType())) {
      if (rule.periodTotalAmount() == null || rule.allocationDivisor() == null) {
        return new AppliedFee(null, null, null, "", false, "订阅费缺少周期总额或分摊基数，算不出来");
      }
      BigDecimal share =
          rule.periodTotalAmount()
              .divide(rule.allocationDivisor(), 8, RoundingMode.HALF_UP);
      BigDecimal amount =
          rule.allocationBasis() == AllocationBasis.PER_UNIT
              ? share.multiply(qty)
              : share;
      return new AppliedFee(
          money(amount),
          rule.periodCurrency(),
          null,
          "订阅费分摊：周期总额 "
              + rule.periodTotalAmount()
              + " "
              + rule.periodCurrency()
              + " ÷ 分摊基数 "
              + rule.allocationDivisor()
              + (rule.allocationBasis() == AllocationBasis.PER_UNIT ? " × 数量 " + qty : "")
              + " = "
              + money(amount),
          true,
          "");
    }
    switch (rule.chargeBasis()) {
      case PERCENTAGE_OF_SALE: {
        if (rule.rate() == null) {
          return new AppliedFee(null, saleCurrency, null, "", false, "规则没有费率，算不出来");
        }
        BigDecimal amount = money(sale.multiply(rule.rate()));
        return new AppliedFee(
            amount,
            saleCurrency,
            rule.rate(),
            "销售额 " + sale + " × 费率 " + rule.rate() + " = " + amount,
            true,
            "");
      }
      case FIXED_PER_ORDER: {
        if (rule.fixedAmount() == null) {
          return new AppliedFee(null, rule.currency(), null, "", false, "规则没有固定额，算不出来");
        }
        return new AppliedFee(
            money(rule.fixedAmount()),
            rule.currency(),
            null,
            "每单固定额 " + rule.fixedAmount() + " " + rule.currency(),
            true,
            "");
      }
      case FIXED_PER_UNIT: {
        if (rule.fixedAmount() == null) {
          return new AppliedFee(null, rule.currency(), null, "", false, "规则没有固定额，算不出来");
        }
        BigDecimal amount = money(rule.fixedAmount().multiply(qty));
        return new AppliedFee(
            amount,
            rule.currency(),
            null,
            "每件固定额 " + rule.fixedAmount() + " × 数量 " + qty + " = " + amount,
            true,
            "");
      }
      case TIERED_PERCENTAGE: {
        TierView tier = selectTier(tiers, sale);
        if (tier == null) {
          return new AppliedFee(
              null,
              saleCurrency,
              null,
              "阶梯费率",
              false,
              "销售额 " + sale + " 没有落进任何一档 —— 该规则不适用于这个金额");
        }
        BigDecimal amount = money(sale.multiply(tier.rate()));
        return new AppliedFee(
            amount,
            saleCurrency,
            tier.rate(),
            "销售额 "
                + sale
                + " 落在档位 ["
                + tier.minAmount()
                + ", "
                + (tier.maxAmount() == null ? "∞" : tier.maxAmount())
                + ") × 费率 "
                + tier.rate()
                + " = "
                + amount,
            true,
            "");
      }
      default:
        return new AppliedFee(null, saleCurrency, null, "", false, "未知的计价形态");
    }
  }

  /**
   * 选档：下界闭、上界开。
   *
   * <p>{@code max_amount IS NULL} = 该档以上无上限。
   * 销售额低于最低档下界 ⇒ 没有档位（规则不适用），**不是**落进第一档。
   */
  public static TierView selectTier(List<TierView> tiers, BigDecimal saleAmount) {
    if (tiers == null || tiers.isEmpty()) return null;
    BigDecimal sale = nz(saleAmount);
    TierView best = null;
    for (TierView tier : tiers) {
      if (tier.minAmount() == null) continue;
      if (sale.compareTo(tier.minAmount()) < 0) continue;
      if (tier.maxAmount() != null && sale.compareTo(tier.maxAmount()) >= 0) continue;
      if (best == null
          || tier.minAmount().compareTo(best.minAmount()) > 0) best = tier;
    }
    return best;
  }

  // ================================================================ 作用域

  /**
   * 规则的作用域特异度：越大越具体，命中冲突时**特异度高的赢**。
   *
   * <p>口径（写死在��里，服务层与测试共用）：
   *
   * <ul>
   *   <li>限定站点 +8（站点比类目更能决定费率：同一个平台不同站点费率常常不同）
   *   <li>限定内部分类 +4
   *   <li>限定平台类目 +2
   * </ul>
   *
   * <p>没写的维度 = 该规则**平台级 / 全类目**通用，是兜底口径。
   * 同特异度的两条规则若同时命中，服务层按「生效起点更晚的赢、再按 id 定序」取一条
   * —— 确定性的，不靠数据库返回顺序。
   */
  public static int specificity(String siteCode, UUID internalCategoryId, UUID platformCategoryId) {
    int score = 0;
    if (!isBlank(siteCode)) score += 8;
    if (internalCategoryId != null) score += 4;
    if (platformCategoryId != null) score += 2;
    return score;
  }

  /** 作用域的人话说明（页面上要看得懂这条规则管到哪）。 */
  public static String scopeBasis(
      String platformCode, String siteCode, String internalCategoryName, String platformCategoryName) {
    StringBuilder text = new StringBuilder("平台 ").append(platformCode);
    text.append(isBlank(siteCode) ? "（全部站点）" : " / 站点 " + siteCode);
    if (internalCategoryName != null) text.append(" / 内部分类「").append(internalCategoryName).append("」及其子类");
    if (platformCategoryName != null) text.append(" / 平台类目「").append(platformCategoryName).append("」");
    return text.toString();
  }

  // ================================================================ 小工具

  /** 金额取到 4 位小数（HALF_UP）。 */
  public static BigDecimal money(BigDecimal value) {
    return nz(value).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  /** BigDecimal → 展示串（null 保持 null，**不**变成 "0"）。 */
  public static String text(BigDecimal value) {
    return value == null ? null : value.toPlainString();
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
