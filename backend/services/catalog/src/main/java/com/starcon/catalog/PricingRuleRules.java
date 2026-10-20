package com.starcon.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;

/**
 * 5.3.2.2 价格规则：**纯算术**部分（无 IO、无租户上下文，可单测）。
 *
 * <p>本类只回答两个问题：
 *
 * <ol>
 *   <li><b>「命中」怎么算</b>：阈值比较与涨跌幅；
 *   <li><b>「建议价」怎么算、要不要人工确认</b>。
 * </ol>
 *
 * <p>「数据从哪来」<b>不在本类</b> —— 库存在 warehouse、库龄在 warehouse、成本版本在
 * 5.2.1.1、价格观测在 5.5.2.1、毛利在 5.3.1.1，由 {@link PricingRuleService} 取数后传进来。
 *
 * <h2>🔴 纪律一：全程 BigDecimal，零浮点</h2>
 *
 * <p>金额 4 位小数、费率 6 位小数，统一 HALF_UP（与 5.3.1.1 / 5.3.2.1 同一精度）。
 *
 * <h2>🔴 纪律二：取不到数就不判定</h2>
 *
 * <p>每个判定函数在「输入缺失」时返回 {@code null}（而不是 0、不是 false）——
 * 调用方据此记一条**未命中**的留痕并写明原因。把「取不到」当成「没超阈值」，
 * 等于让规则的沉默看起来像「判定过且没问题」。
 */
public final class PricingRuleRules {

  private PricingRuleRules() {}

  /** 金额精度：4 位小数（与 5.3.1.1 一致）。 */
  public static final int MONEY_SCALE = 4;

  /** 费率精度：6 位小数。 */
  public static final int RATE_SCALE = 6;

  /** 舍入方式：四舍五入（与 5.3.1.1 一致）。 */
  public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

  private static final BigDecimal ONE = BigDecimal.ONE;

  // ================================================================ 阈值比较

  /**
   * 库存低于阈值：可售量 < 阈值 ⇒ 命中。
   *
   * @param available 可售量（来自 7 库存）。null = 取不到 ⇒ 不判定。
   * @param threshold 阈值。null = 规则没配阈值 ⇒ 不判定。
   */
  public static Boolean inventoryBelow(BigDecimal available, BigDecimal threshold) {
    if (available == null || threshold == null) return null;
    return available.compareTo(threshold) < 0;
  }

  /**
   * 成本上涨：单位成本涨幅 ≥ 阈值 ⇒ 命中。
   *
   * @param latest 最新一版的单位成本合计。null = 取不到 ⇒ 不判定。
   * @param previous 上一版的单位成本合计。null = 没有历史版本 ⇒ 不判定。
   * @param threshold 涨幅阈值（小数口径）。null ⇒ 不判定。
   */
  public static Boolean costIncreased(BigDecimal latest, BigDecimal previous, BigDecimal threshold) {
    var rate = costIncreaseRate(latest, previous);
    if (rate == null || threshold == null) return null;
    return rate.compareTo(threshold) >= 0;
  }

  /**
   * 成本涨幅 = (最新 − 上一版) ÷ 上一版。
   *
   * @return null = 上一版缺失或 ≤ 0（**不判定**，不是 0%）
   */
  public static BigDecimal costIncreaseRate(BigDecimal latest, BigDecimal previous) {
    if (latest == null || previous == null) return null;
    if (previous.signum() <= 0) return null;
    return rate(latest.subtract(previous).divide(previous, RATE_SCALE + 6, RoundingMode.HALF_UP));
  }

  /**
   * 市场价（平台在售价）下降幅度 = (上一条 − 最新一条) ÷ 上一条。
   *
   * @return null = 只有一条观测或上一条 ≤ 0（**不判定**：一条观测说明不了「降价」）
   */
  public static BigDecimal priceDropRate(BigDecimal previous, BigDecimal latest) {
    if (previous == null || latest == null) return null;
    if (previous.signum() <= 0) return null;
    return rate(previous.subtract(latest).divide(previous, RATE_SCALE + 6, RoundingMode.HALF_UP));
  }

  /**
   * 市场价降价：降幅 ≥ 阈值 ⇒ 命中。
   *
   * @return null = 取不到两次观测 ⇒ 不判定
   */
  public static Boolean priceDropped(BigDecimal previous, BigDecimal latest, BigDecimal threshold) {
    var drop = priceDropRate(previous, latest);
    if (drop == null || threshold == null) return null;
    return drop.compareTo(threshold) >= 0;
  }

  /**
   * 毛利低于下限：当前价下的毛利率 < 下限 ⇒ 命中。
   *
   * @param marginRate 当前价下的毛利率。null = 复核不了（成本不完整）⇒ 不判定。
   * @param floor 毛利下限（小数口径）。null ⇒ 不判定。
   */
  public static Boolean marginBelowFloor(BigDecimal marginRate, BigDecimal floor) {
    if (marginRate == null || floor == null) return null;
    return marginRate.compareTo(floor) < 0;
  }

  /**
   * 库龄超期：最老一批的库龄 > 阈值 ⇒ 命中。
   *
   * @param agingDays 库龄天数。null = 取不到 ⇒ 不判定。
   * @param threshold 阈值（天）。null ⇒ 不判定。
   */
  public static Boolean agingOver(Integer agingDays, Integer threshold) {
    if (agingDays == null || threshold == null) return null;
    return agingDays > threshold;
  }

  // ================================================================ 建议价

  /**
   * 建议价 = 当前价 × (1 ∓ 幅度)。
   *
   * @param currentPrice 当前价（来自 5.5.2.1 价格观测台账）。null ⇒ **不给价**。
   * @param action 动作。只有降价 / 涨价产出价格。
   * @param actionRate 调价幅度（小数口径）。null ⇒ 不给价。
   * @return null = 非价格动作、或当前价 / 幅度缺失、或算出来不是正数
   */
  public static BigDecimal suggestedPrice(
      BigDecimal currentPrice, PricingRuleModels.ActionType action, BigDecimal actionRate) {
    if (action == null || !action.priceAction()) return null;
    if (currentPrice == null || currentPrice.signum() <= 0) return null;
    if (actionRate == null) return null;
    if (actionRate.compareTo(ONE) >= 0) return null;
    var factor =
        action == PricingRuleModels.ActionType.LOWER_PRICE
            ? ONE.subtract(actionRate)
            : ONE.add(actionRate);
    var price = currentPrice.multiply(factor);
    if (price.signum() <= 0) return null;
    return money(price);
  }

  /**
   * 相对当前价的变动幅度 = (建议价 − 当前价) ÷ 当前价（**带符号**：涨价为正，降价为负）。
   *
   * @return null = 当前价或建议价缺失
   */
  public static BigDecimal priceChangeRate(BigDecimal currentPrice, BigDecimal suggestedPrice) {
    if (currentPrice == null || suggestedPrice == null) return null;
    if (currentPrice.signum() <= 0) return null;
    return rate(
        suggestedPrice
            .subtract(currentPrice)
            .divide(currentPrice, RATE_SCALE + 6, RoundingMode.HALF_UP));
  }

  /**
   * 降价幅度（**正数**）：变动幅度为负时取绝对值，否则为 0。
   *
   * <p>高风险阈值比的是「降了多少」，不是「变得了多少」—— 涨价不会因为幅度大而触发人工确认。
   */
  public static BigDecimal dropRate(BigDecimal priceChangeRate) {
    if (priceChangeRate == null) return null;
    if (priceChangeRate.signum() >= 0) return rate(BigDecimal.ZERO);
    return rate(priceChangeRate.abs());
  }

  // ================================================================ 风险闸

  /**
   * 要不要人工确认（蓝图生死线：「高风险规则（降价超过 X%）需人工确认」）。
   *
   * <p>三个条件任一成立就<b>不自动放行</b>：
   *
   * <ol>
   *   <li>降价幅度超过规则上的 {@code maxAutoPriceDropRate}；
   *   <li>成本不完整（{@code decisionReady = false}）—— 复核不了毛利，就不该让它自动流下去；
   *   <li>非价格动作（暂停促销 / 下架）—— 规则写错一次就是一批商品被误伤。
   * </ol>
   *
   * @param action 动作
   * @param priceChangeRate 建议价相对当前价的变动幅度（带符号）。null = 没有价格动作
   * @param maxAutoPriceDropRate 规则上的自动放行上限（小数口径）。null ⇒ 按 0 处理（不放行任何降价）
   * @param decisionReady 5.3.1.1 对建议价的复核是否就绪
   */
  public static boolean requiresConfirmation(
      PricingRuleModels.ActionType action,
      BigDecimal priceChangeRate,
      BigDecimal maxAutoPriceDropRate,
      boolean decisionReady) {
    if (action == null) return true;
    if (!action.priceAction()) return true;
    if (!decisionReady) return true;
    var drop = dropRate(priceChangeRate);
    if (drop == null) return true;
    var limit = maxAutoPriceDropRate == null ? BigDecimal.ZERO : maxAutoPriceDropRate;
    return drop.compareTo(limit) > 0;
  }

  // ================================================================ 生效时段

  /**
   * 规则此刻是否生效：enabled + 落在生效时段内。
   *
   * @param effectiveFrom 生效起点（含）
   * @param effectiveTo 生效终点（不含，空 = 长期有效）
   * @param now 当前时刻
   */
  public static boolean effectiveNow(
      boolean enabled, OffsetDateTime effectiveFrom, OffsetDateTime effectiveTo, OffsetDateTime now) {
    if (!enabled) return false;
    if (effectiveFrom == null || now == null) return false;
    if (now.isBefore(effectiveFrom)) return false;
    return effectiveTo == null || now.isBefore(effectiveTo);
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
