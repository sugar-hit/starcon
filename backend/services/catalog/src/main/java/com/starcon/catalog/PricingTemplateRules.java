package com.starcon.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 5.3.2.1 定价模板：**纯算术**部分（无 IO、无租户上下文，可单测）。
 *
 * <p>本类只回答一个问题：<b>「基准 + 调整项」怎么变成一个建议价</b>。
 * 「成本是多少」这件事**不在本类**——它来自 5.3.1.1 的测算结果（{@code ProfitCalculation}），
 * 由 {@link PricingTemplateService} 传进来。
 *
 * <h2>🔴 纪律一：调整项加在「基准率」上，不乘在价格上</h2>
 *
 * <p>三个基准方法的基准率含义不同（加成率 / 目标毛利率 / 跟随率），
 * 但「站点差异化」在三种基准下的语义是同一个：<b>这个站点的定价比基准松/紧多少</b>。
 * 所以它是**加法**（{@code 有效基准率 = 基准率 + 站点增量}），
 * 而不是乘法（{@code 价格 × (1 + x)}）——乘法会让「+2 个百分点」在加成率与毛利率两种语义下
 * 变成完全不同的东西，而用户在页面上看到的只有一个数字。
 *
 * <h2>🔴 纪律二：库存周转是**乘在建议价上**的折扣</h2>
 *
 * <p>可售天数超过阈值判定为滞销，折扣率作用在**最终建议价**上
 * （{@code 建议价 × (1 − 折扣率)}），而不是加在基准率上：
 * 滞销处理的是「这个价太高卖不动」，它必须作用在价上；
 * 加在基准率上会让固定毛利模板在折扣很小时反而**涨价**（基准率 −10% 在毛利率语义下是降价，
 * 在加成率语义下也是降价，但在跟随率语义下就变成了「更贴近竞品」= 涨价）。
 *
 * <h2>🔴 纪律三：全程 BigDecimal，零浮点</h2>
 *
 * <p>金额 4 位小数、费率 6 位小数，统一 HALF_UP（与 5.3.1.1 的 {@code ProfitCalculatorRules} 同一精度）。
 */
public final class PricingTemplateRules {

  private PricingTemplateRules() {}

  /** 金额精度：4 位小数（与 5.3.1.1 一致）。 */
  public static final int MONEY_SCALE = 4;

  /** 费率精度：6 位小数。 */
  public static final int RATE_SCALE = 6;

  /** 舍入方式：四舍五入（与 5.3.1.1 一致）。 */
  public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

  private static final BigDecimal ONE = BigDecimal.ONE;

  /**
   * 一个调整项的算术形态（服务层把数据库行映射成它）。
   *
   * @param type 调整项类型
   * @param siteCode 站点码（仅按站点差异化）
   * @param deltaRate 站点增量（仅按站点差异化）
   * @param daysCoverThreshold 可售天数阈值（仅按库存周转调整）
   * @param discountRate 滞销折扣率（仅按库存周转调整）
   */
  public record AdjustmentSpec(
      PricingTemplateModels.AdjustmentType type,
      String siteCode,
      BigDecimal deltaRate,
      BigDecimal daysCoverThreshold,
      BigDecimal discountRate) {}

  /**
   * 一条调整项在本次应用里的效果。
   *
   * @param applied 命中了吗
   * @param reason 为什么命中 / 为什么没命中（页面上逐条显示）
   * @param rateDelta 站点增量（仅按站点差异化命中时非空）
   * @param discountRate 滞销折扣率（仅按库存周转命中时非空）
   */
  public record AdjustmentEffect(
      boolean applied, String reason, BigDecimal rateDelta, BigDecimal discountRate) {}

  // ================================================================ 调整项

  /**
   * 按站点差异化：取目标站点的增量。
   *
   * <p>没配这个站点 ⇒ 增量为 0（不是「拒绝应用」：模板可以只给部分站点做差异化）。
   *
   * @param adjustments 模板上的全部调整项
   * @param siteCode 目标店铺（站点码）；空 = 没有站点上下文
   */
  public static AdjustmentEffect siteDifferential(
      List<AdjustmentSpec> adjustments, String siteCode) {
    if (adjustments == null || adjustments.isEmpty()) {
      return new AdjustmentEffect(false, "模板没有配按站点差异化的调整项", null, null);
    }
    if (siteCode == null || siteCode.isBlank()) {
      return new AdjustmentEffect(false, "本次应用没有目标店铺，无法命中站点差异化规则", null, null);
    }
    for (var adjustment : adjustments) {
      if (adjustment.type() != PricingTemplateModels.AdjustmentType.SITE_DIFFERENTIAL) continue;
      if (!siteCode.equalsIgnoreCase(adjustment.siteCode())) continue;
      var delta = adjustment.deltaRate() == null ? BigDecimal.ZERO : adjustment.deltaRate();
      return new AdjustmentEffect(
          true,
          "命中站点 " + siteCode + " 的差异化规则：基准率 "
              + percentText(delta)
              + "（加在基准率上，不是乘在价格上）",
          delta,
          null);
    }
    return new AdjustmentEffect(
        false, "模板没有为站点 " + siteCode + " 配差异化规则 ⇒ 基准率不变", null, null);
  }

  /**
   * 按库存周转调整：可售天数超过阈值 ⇒ 打折。
   *
   * <p>🔴 {@code daysOfCover} 为空时**不猜**：返回 {@code applied=false} 并把原因写明
   * 「当前可售天数没有填」——上游 7 库存 / 09 订单没有给本服务可读端点，
   * 拿一个默认值去打折等于凭空编一个滞销判定。
   *
   * <p>调用方（服务层）会把「模板配了库存周转调整但没有可售天数」当成**拒绝应用**，
   * 而不是「按不打折处理」——静默跳过一条策略等于让模板的语义失效。
   */
  public static AdjustmentEffect inventoryTurnover(
      List<AdjustmentSpec> adjustments, BigDecimal daysOfCover) {
    AdjustmentSpec rule = null;
    if (adjustments != null) {
      for (var adjustment : adjustments) {
        if (adjustment.type() == PricingTemplateModels.AdjustmentType.INVENTORY_TURNOVER) {
          rule = adjustment;
          break;
        }
      }
    }
    if (rule == null) {
      return new AdjustmentEffect(false, "模板没有配按库存周转调整的规则", null, null);
    }
    if (daysOfCover == null) {
      return new AdjustmentEffect(
          false,
          "🔴 模板配了按库存周转调整的规则，但当前可售天数没有填："
              + "上游 7 库存 / 09 订单没有给本服务可读端点，可售天数只能由人填（显式的手工假设）",
          null,
          null);
    }
    var threshold = rule.daysCoverThreshold() == null ? BigDecimal.ZERO : rule.daysCoverThreshold();
    if (daysOfCover.compareTo(threshold) <= 0) {
      return new AdjustmentEffect(
          false,
          "可售天数 " + daysOfCover.stripTrailingZeros().toPlainString()
              + " 天未超过阈值 " + threshold.stripTrailingZeros().toPlainString() + " 天 ⇒ 不打折",
          null,
          null);
    }
    var discount = rule.discountRate() == null ? BigDecimal.ZERO : rule.discountRate();
    return new AdjustmentEffect(
        true,
        "可售天数 " + daysOfCover.stripTrailingZeros().toPlainString()
            + " 天超过阈值 " + threshold.stripTrailingZeros().toPlainString()
            + " 天 ⇒ 判定滞销，建议价打 " + percentText(discount) + " 折（即降 "
            + percentText(discount) + "）",
        null,
        discount);
  }

  // ================================================================ 基准率与建议价

  /**
   * 有效基准率 = 基准率 + 站点增量。
   *
   * <p>四舍五入到 6 位小数（费率精度）。
   */
  public static BigDecimal effectiveRate(BigDecimal baseRate, BigDecimal siteDelta) {
    var base = baseRate == null ? BigDecimal.ZERO : baseRate;
    var delta = siteDelta == null ? BigDecimal.ZERO : siteDelta;
    return rate(base.add(delta));
  }

  /**
   * 成本加成：建议价 = 加成基数 × (1 + 有效加成率)。
   *
   * @param costBase 加成基数（单位成本合计：5 项成本分项 + 物流每单位，来自 5.3.1.1 的瀑布图）
   * @param effectiveMarkup 有效加成率
   * @return null = 加成基数缺失或有效加成率 ≤ −1（价格会变成非正数）⇒ **不给价**
   */
  public static BigDecimal costPlusPrice(BigDecimal costBase, BigDecimal effectiveMarkup) {
    if (costBase == null || effectiveMarkup == null) return null;
    if (costBase.signum() <= 0) return null;
    if (effectiveMarkup.compareTo(BigDecimal.ONE.negate()) <= 0) return null;
    return money(costBase.multiply(ONE.add(effectiveMarkup)));
  }

  /**
   * 竞品跟随：建议价 = 竞品基准价 × (1 + 有效跟随率)。
   *
   * @param referencePrice 竞品基准价（人工登记）
   * @param effectiveFollowRate 有效跟随率（可为负 = 比竞品便宜）
   * @return null = 竞品价缺失或跟随率 ≤ −1（价格会变成非正数）⇒ **不给价**
   */
  public static BigDecimal competitorPrice(BigDecimal referencePrice, BigDecimal effectiveFollowRate) {
    if (referencePrice == null || effectiveFollowRate == null) return null;
    if (referencePrice.signum() <= 0) return null;
    if (effectiveFollowRate.compareTo(BigDecimal.ONE.negate()) <= 0) return null;
    return money(referencePrice.multiply(ONE.add(effectiveFollowRate)));
  }

  /**
   * 滞销折扣：建议价 × (1 − 折扣率)。
   *
   * <p>折扣率为空或 0 ⇒ 原价返回（不是 null）。
   */
  public static BigDecimal applyDiscount(BigDecimal price, BigDecimal discountRate) {
    if (price == null) return null;
    if (discountRate == null || discountRate.signum() == 0) return money(price);
    if (discountRate.compareTo(BigDecimal.ONE) > 0) return null;
    return money(price.multiply(ONE.subtract(discountRate)));
  }

  // ================================================================ 小工具

  /** 金额：4 位小数，HALF_UP。 */
  public static BigDecimal money(BigDecimal value) {
    return value == null ? null : value.setScale(MONEY_SCALE, ROUNDING);
  }

  /** 费率：6 位小数，HALF_UP。 */
  public static BigDecimal rate(BigDecimal value) {
    return value == null ? null : value.setScale(RATE_SCALE, ROUNDING);
  }

  /** 百分比展示：0.25 → 「25%」。 */
  public static String percentText(BigDecimal value) {
    if (value == null) return "—";
    return value
        .multiply(BigDecimal.valueOf(100))
        .setScale(2, ROUNDING)
        .stripTrailingZeros()
        .toPlainString()
        + "%";
  }
}
