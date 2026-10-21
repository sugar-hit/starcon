package com.starcon.catalog;

import com.starcon.catalog.PlatformPriceSyncModels.PlatformPriceRuleOutcome;
import com.starcon.catalog.PlatformPriceSyncModels.PlatformPriceRuleType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 5.4.2.1 平台价格同步的**纯规则引擎**（无 IO、无租户上下文，可单测）。
 *
 * <p>本类只回答一件事：**按租户登记的平台定价规则，这个价推上去平台会怎么处置？**
 *
 * <ul>
 *   <li>{@link #evaluate} —— 判定（接受 / 改价 / 拒收），产出<b>平台承接价</b>与原因
 *   <li>{@link #applies} —— 规则作用域匹配（平台 × 站点 × SKU）
 *   <li>{@link #validate} —— 建规则时的参数齐备性校验
 * </ul>
 *
 * <p><b>🔴 纪律一：全程 BigDecimal，零浮点。</b>
 * 价与比例的精度与 5.4.1.1 / 5.3.1.1 逐字相同：价 4 位小数（{@link #MONEY_SCALE}），
 * 比例 6 位小数（{@link #RATE_SCALE}），舍入一律 {@link #ROUNDING}（HALF_UP）。
 *
 * <p><b>🔴 纪律二：判不出来 ≠ 判成通过。</b>
 * 对比价缺观测、规则币种与本次不同币种，这些情形一律**不参与判定**并留下
 * {@code notes} 说明为什么没参与 —— 绝不悄悄当成「没发现问题」。
 *
 * <p><b>🔴 纪律三：拒收优先于改价。</b>
 * 最低价是平台的硬门槛：低于它平台根本不收，谈不上「改成多少」。
 * 所以只要命中最低价规则，判定结果就是 REJECTED，哪怕另一条规则算出了改价。
 *
 * <p><b>🔴 纪律四：币种不同不折算。</b>
 * 最低价规则带币种；本次回写的币种与之不同 ⇒ 该规则不参与判定（而不是按 1:1 比）。
 */
public final class PlatformPriceSyncRules {

  private PlatformPriceSyncRules() {}

  /** 价精度：4 位小数（与 5.3.1.1 / 5.4.1.1 一致）。 */
  public static final int MONEY_SCALE = 4;

  /** 比例精度：6 位小数（0.05 = 5%）。 */
  public static final int RATE_SCALE = 6;

  /** 舍入方式：四舍五入。 */
  public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

  /** 台账页大小上限：与相邻模块一致。 */
  public static final int MAX_PAGE_SIZE = 200;

  // ================================================================ 内部形状

  /**
   * 判定用的规则形状（从库里读出来的行；不含 status —— 停用的规则在读的时候就被滤掉了）。
   */
  public record Rule(
      UUID id,
      PlatformPriceRuleType type,
      String platformCode,
      String siteCode,
      UUID skuId,
      BigDecimal minPrice,
      String currency,
      String comparePlatformCode,
      String compareSiteCode,
      BigDecimal maxGapRatio,
      String referenceSiteCode,
      BigDecimal minRatio,
      BigDecimal maxRatio,
      int priority) {}

  /**
   * 对比价观测：某个平台 / 站点上该 SKU 的**平台在售价**。
   *
   * @param currency 该观测的币种（🔴 与本次回写币种不同 ⇒ 该规则不参与判定，
   *     绝不按 1:1 折算 —— 与 5.4.1.1「币种不同 ⇒ 整单拒绝」同一条纪律）
   * @param price 平台在售价
   */
  public record ComparisonPrice(String currency, BigDecimal price) {}

  /**
   * 判定结果。
   *
   * @param outcome 判定结论
   * @param predictedPrice 平台承接价（判出来改价时是它；判不出来时为 null）
   * @param ruleId 命中的规则（没有规则命中时为 null）
   * @param reasonCode 原因码（机器可读：页面按它决定徽标）
   * @param reasonMessage 原因说明（人可读）
   * @param notes 没有参与判定的规则及原因（**不静默**）
   */
  public record Decision(
      PlatformPriceRuleOutcome outcome,
      BigDecimal predictedPrice,
      UUID ruleId,
      String reasonCode,
      String reasonMessage,
      List<String> notes) {}

  // ================================================================ 判定

  /**
   * 判定一次回写。
   *
   * @param expectedPrice 期望价（我们要求平台收的价）
   * @param currency 本次回写的币种
   * @param platformCode 平台码
   * @param siteCode 站点码
   * @param skuId 本次改价的 SKU
   * @param rules 该租户的全部生效规则（未过滤作用域）
   * @param comparisonPrices 对比价：key = {@code 平台码|站点码}，value = 该 SKU 在那儿的平台在售价观测
   *        （来自 5.5.2.1 的价格观测台账 —— 本仓唯一的真实平台价来源）
   */
  public static Decision evaluate(
      BigDecimal expectedPrice,
      String currency,
      String platformCode,
      String siteCode,
      UUID skuId,
      List<Rule> rules,
      Map<String, ComparisonPrice> comparisonPrices) {

    var notes = new ArrayList<String>();
    var applicable = new ArrayList<Rule>();
    for (var rule : rules == null ? List.<Rule>of() : rules) {
      if (applies(rule, platformCode, siteCode, skuId)) applicable.add(rule);
    }
    if (applicable.isEmpty()) {
      return new Decision(
          PlatformPriceRuleOutcome.NO_RULE,
          expectedPrice,
          null,
          "NO_RULE",
          "该平台 / 站点没有登记生效的定价规则，本次按期望价原样推送",
          notes);
    }
    // 判定顺序：越具体的规则越先判（SKU 级 > 站点级 > 平台级），同 specificity 按优先级。
    applicable.sort(
        Comparator.comparingInt((Rule r) -> -specificity(r, siteCode, skuId))
            .thenComparingInt(r -> r.priority())
            .thenComparing(r -> r.id().toString()));

    Rule rejected = null;
    String rejectedReason = null;
    Rule adjusted = null;
    BigDecimal adjustedPrice = null;
    String adjustedReason = null;

    for (var rule : applicable) {
      switch (rule.type()) {
        case MIN_PRICE -> {
          if (rule.currency() == null
              || rule.currency().isBlank()
              || !rule.currency().equalsIgnoreCase(currency == null ? "" : currency)) {
            notes.add(
                "最低价规则（"
                    + rule.id()
                    + "）币种为 "
                    + rule.currency()
                    + "，与本次回写币种 "
                    + currency
                    + " 不同，未参与判定（不按 1:1 折算）");
            break;
          }
          if (expectedPrice.compareTo(rule.minPrice()) < 0) {
            rejected = rule;
            rejectedReason =
                "期望价 "
                    + plain(expectedPrice)
                    + " 低于平台最低价 "
                    + plain(rule.minPrice())
                    + " "
                    + rule.currency()
                    + "，平台拒收";
            break;
          }
        }
        case AUTO_COMPARE -> {
          String key = key(rule.comparePlatformCode(), rule.compareSiteCode());
          var raw = comparisonPrices == null ? null : comparisonPrices.get(key);
          var compare = comparable(comparisonPrices, key, currency);
          if (compare == null) {
            notes.add(
                "自动比价规则（"
                    + rule.id()
                    + "）"
                    + (raw == null
                        ? "没有取到对比价观测（"
                            + rule.comparePlatformCode()
                            + " "
                            + (rule.compareSiteCode() == null || rule.compareSiteCode().isBlank()
                                ? "全部站点"
                                : rule.compareSiteCode())
                            + "）"
                        : "对比价币种为 "
                            + raw.currency()
                            + "，与本次回写币种 "
                            + currency
                            + " 不同，未参与判定（不按 1:1 折算）")
                    + "，未参与判定");
            break;
          }
          var allowedMax =
              compare.multiply(BigDecimal.ONE.add(nz(rule.maxGapRatio()))).setScale(MONEY_SCALE, ROUNDING);
          if (expectedPrice.compareTo(allowedMax) > 0) {
            if (adjusted == null) {
              adjusted = rule;
              adjustedPrice = compare.setScale(MONEY_SCALE, ROUNDING);
              adjustedReason =
                  "期望价 "
                      + plain(expectedPrice)
                      + " 高于对比价 "
                      + plain(compare)
                      + " 允许的 "
                      + plain(rule.maxGapRatio())
                      + " 价差上限（"
                      + plain(allowedMax)
                      + "），平台自动比价会压到 "
                      + plain(adjustedPrice);
            }
          }
        }
        case MULTI_SITE_LINK -> {
          String key = key(platformCode, rule.referenceSiteCode());
          var raw = comparisonPrices == null ? null : comparisonPrices.get(key);
          var reference = comparable(comparisonPrices, key, currency);
          if (reference == null) {
            notes.add(
                "多站点联动规则（"
                    + rule.id()
                    + "）"
                    + (raw == null
                        ? "没有取到参照站点 " + rule.referenceSiteCode() + " 的价格观测"
                        : "参照站点 "
                            + rule.referenceSiteCode()
                            + " 的价格币种为 "
                            + raw.currency()
                            + "，与本次回写币种 "
                            + currency
                            + " 不同，未参与判定（不按 1:1 折算）")
                    + "，未参与判定");
            break;
          }
          var low =
              reference.multiply(nz(rule.minRatio())).setScale(MONEY_SCALE, ROUNDING);
          var high =
              reference.multiply(nz(rule.maxRatio())).setScale(MONEY_SCALE, ROUNDING);
          if (expectedPrice.compareTo(low) < 0) {
            if (adjusted == null) {
              adjusted = rule;
              adjustedPrice = low;
              adjustedReason =
                  "期望价 "
                      + plain(expectedPrice)
                      + " 低于参照站点 "
                      + rule.referenceSiteCode()
                      + " 价格 "
                      + plain(reference)
                      + " 的联动下限 "
                      + plain(low)
                      + "，平台联动会拉到 "
                      + plain(low);
            }
          } else if (expectedPrice.compareTo(high) > 0) {
            if (adjusted == null) {
              adjusted = rule;
              adjustedPrice = high;
              adjustedReason =
                  "期望价 "
                      + plain(expectedPrice)
                      + " 高于参照站点 "
                      + rule.referenceSiteCode()
                      + " 价格 "
                      + plain(reference)
                      + " 的联动上限 "
                      + plain(high)
                      + "，平台联动会拉到 "
                      + plain(high);
            }
          }
        }
      }
      // 拒收是硬结论：一旦命中就不再看后面的规则。
      if (rejected != null) break;
    }

    if (rejected != null) {
      return new Decision(
          PlatformPriceRuleOutcome.REJECTED,
          null,
          rejected.id(),
          "BELOW_MIN_PRICE",
          rejectedReason,
          notes);
    }
    if (adjusted != null) {
      return new Decision(
          PlatformPriceRuleOutcome.ADJUSTED,
          adjustedPrice,
          adjusted.id(),
          "PLATFORM_WILL_ADJUST",
          adjustedReason,
          notes);
    }
    return new Decision(
        PlatformPriceRuleOutcome.ACCEPTED,
        expectedPrice.setScale(MONEY_SCALE, ROUNDING),
        applicable.get(0).id(),
        "RULES_OK",
        "生效规则均通过，本次按期望价原样推送",
        notes);
  }

  /** 规则是否作用于本次（平台 × 站点 × SKU）。 */
  public static boolean applies(Rule rule, String platformCode, String siteCode, UUID skuId) {
    if (rule.platformCode() == null || !rule.platformCode().equalsIgnoreCase(platformCode)) return false;
    if (rule.siteCode() != null
        && !rule.siteCode().isBlank()
        && !rule.siteCode().equalsIgnoreCase(siteCode == null ? "" : siteCode)) {
      return false;
    }
    return rule.skuId() == null || rule.skuId().equals(skuId);
  }

  /**
   * 规则作用域的具体程度：SKU 级 3 > 站点级 2 > 平台级 1。
   *
   * <p>越具体越先判 —— 平台级最低价是兜底，SKU 级规则才是这个商品自己的约定。
   */
  private static int specificity(Rule rule, String siteCode, UUID skuId) {
    int score = 1;
    if (rule.siteCode() != null && !rule.siteCode().isBlank() && rule.siteCode().equalsIgnoreCase(siteCode)) {
      score = 2;
    }
    if (rule.skuId() != null && rule.skuId().equals(skuId)) score = 3;
    return score;
  }

  // ================================================================ 校验

  /**
   * 建 / 改规则时的参数齐备性校验。
   *
   * <p>🔴 缺参数**直接拒**（400），不给「先存下来以后再补」的口子 ——
   * 一条没配最低价的最低价规则在库里静默存在，判定时又拿不出结果，
   * 那是最难排查的一种数据质量问题。
   */
  public static void validate(
      PlatformPriceRuleType type,
      BigDecimal minPrice,
      String currency,
      String comparePlatformCode,
      BigDecimal maxGapRatio,
      String referenceSiteCode,
      BigDecimal minRatio,
      BigDecimal maxRatio) {
    if (type == null) throw ProfitCalculatorService.badRequest("必须指定规则类型");
    switch (type) {
      case MIN_PRICE -> {
        if (minPrice == null || minPrice.signum() <= 0) {
          throw ProfitCalculatorService.badRequest("最低价规则必须给一个大于 0 的最低价");
        }
        if (currency == null || currency.isBlank() || !currency.matches("^[A-Z]{3}$")) {
          throw ProfitCalculatorService.badRequest("最低价规则必须给三位大写币种代码");
        }
      }
      case AUTO_COMPARE -> {
        if (comparePlatformCode == null || comparePlatformCode.isBlank()) {
          throw ProfitCalculatorService.badRequest("自动比价规则必须指定对比平台");
        }
        if (maxGapRatio == null || maxGapRatio.signum() < 0) {
          throw ProfitCalculatorService.badRequest("自动比价规则必须给一个不小于 0 的最大价差比例");
        }
      }
      case MULTI_SITE_LINK -> {
        if (referenceSiteCode == null || referenceSiteCode.isBlank()) {
          throw ProfitCalculatorService.badRequest("多站点联动规则必须指定参照站点");
        }
        if (minRatio == null || minRatio.signum() <= 0) {
          throw ProfitCalculatorService.badRequest("多站点联动规则必须给一个大于 0 的联动下限比例");
        }
        if (maxRatio == null || maxRatio.signum() < 0) {
          throw ProfitCalculatorService.badRequest("多站点联动规则必须给一个不小于 0 的联动上限比例");
        }
        if (maxRatio.compareTo(minRatio) < 0) {
          throw ProfitCalculatorService.badRequest("多站点联动规则的联动上限比例不能小于下限比例");
        }
      }
    }
  }

  // ================================================================ 小工具

  /**
   * 取**同币种**的对比价。
   *
   * <p>🔴 币种不同 ⇒ 返回 null（该规则不参与判定），**绝不按 1:1 折算** ——
   * 与 5.4.1.1「币种不同 ⇒ 整单拒绝」同一条纪律。
   * 一个 EUR 的参照价与一个 USD 的期望价放在同一个比值里算，算出来的是数字不是结论。
   */
  public static BigDecimal comparable(
      Map<String, ComparisonPrice> comparisonPrices, String key, String currency) {
    if (comparisonPrices == null) return null;
    var found = comparisonPrices.get(key);
    if (found == null || found.price() == null) return null;
    if (found.currency() == null || !found.currency().equalsIgnoreCase(currency == null ? "" : currency)) {
      return null;
    }
    return found.price();
  }

  /** 对比价的 key：平台码 | 站点码。 */
  public static String key(String platformCode, String siteCode) {
    return norm(platformCode) + "|" + norm(siteCode);
  }

  /** 归一化码（去空白 + 大写）。 */
  public static String norm(String code) {
    return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  /** 展示用的价格文本（4 位小数）。 */
  public static String plain(BigDecimal value) {
    return value == null ? "—" : value.setScale(MONEY_SCALE, ROUNDING).toPlainString();
  }
}
