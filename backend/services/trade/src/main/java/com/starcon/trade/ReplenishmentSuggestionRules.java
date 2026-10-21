package com.starcon.trade;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 6.2.1.2 补货建议的<b>纯计算规则</b>：取整修正与阶梯取价。
 *
 * <p>本类<b>不碰数据库、不碰网络</b>，所以可以在离线单测里穷举边界（数量 0、箱规缺失、
 * 起订额刚好卡在档位边界、阶梯断档……）。服务层只负责把上游事实喂进来、把结果落库。
 *
 * <p><b>单位纪律（本模块最容易出错的地方）</b>：
 *
 * <ul>
 *   <li><b>基础单位</b>：日均销量、可用量、在途量、目标库存量、净需求量、建议量都在基础单位里。</li>
 *   <li><b>采购单位</b>：MOQ 与阶梯起订量都在采购单位里 —— catalog 的 {@code applicableTier}
 *       就是拿数量与 {@code minQuantity} 同单位比较的 ⇒ 两者必然同单位。</li>
 *   <li><b>箱规</b>（{@code boxSpec}）：1 个采购单位含多少基础单位（1 箱 = 12 件 ⇒ 12）。</li>
 * </ul>
 *
 * <p><b>取整三步（顺序固定，每一步都可解释）</b>：
 *
 * <ol>
 *   <li><b>整箱 / 整件</b>：需要的采购单位数 = 净需求量 ÷ 箱规，<b>向上取整</b>
 *       （向下取整会让订量不够，那是超卖的方向）。</li>
 *   <li><b>MOQ</b>：采购单位数不足 MOQ 时抬到 MOQ。</li>
 *   <li><b>采购起订额</b>：预计费用低于起订额时，按单价把采购单位数抬到刚好达标
 *       （<b>只在币种相同且取得到适用阶梯价时</b>；跨币种不比价 —— 12 财务才有汇率表）。</li>
 * </ol>
 */
public final class ReplenishmentSuggestionRules {

  private ReplenishmentSuggestionRules() {}

  /** 数量刻度：与 DB 的 numeric(19,6) 对齐。 */
  public static final int QUANTITY_SCALE = 6;

  /** 金额刻度：与 DB 的 numeric(19,4) 对齐。 */
  public static final int PRICE_SCALE = 4;

  /** 中间除法保留的位数（远高于 6 位，取整前不丢精度）。 */
  private static final int DIVIDE_SCALE = 12;

  /**
   * 取整的结果。
   *
   * @param purchaseUnits 建议采购单位数（取整后）
   * @param suggestedQuantity 建议量（基础单位）
   * @param adjustment 取整带来的增量（基础单位）= 建议量 − 净需求量
   * @param unitPrice 适用阶梯单价（每采购单位）。null = 没有适用档
   * @param estimatedCost 预计费用 = 采购单位数 × 适用阶梯单价。null = 取不到价
   * @param moqQuantity 生效的 MOQ（采购单位）
   * @param minOrderAmount 生效的采购起订额
   * @param minOrderApplied 起订额是否真的抬了量
   * @param minOrderSkipReason 起订额为什么没参与（null = 参与了或本来就没有起订额）
   * @param boxRaised 箱规取整抬了多少采购单位
   * @param moqRaised MOQ 抬了多少采购单位
   */
  public record Rounding(
      BigDecimal purchaseUnits,
      BigDecimal suggestedQuantity,
      BigDecimal adjustment,
      BigDecimal unitPrice,
      BigDecimal estimatedCost,
      BigDecimal moqQuantity,
      BigDecimal minOrderAmount,
      boolean minOrderApplied,
      String minOrderSkipReason,
      BigDecimal boxRaised,
      BigDecimal moqRaised) {}

  /**
   * 取整修正。
   *
   * @param rawRequired 净需求量（基础单位，非负）
   * @param boxSpec 箱规（1 采购单位 = 多少基础单位），必填且 &gt; 0
   * @param moq MOQ（采购单位），可空
   * @param minOrderAmount 采购起订额，可空
   * @param quoteCurrency 报价币种
   * @param minOrderCurrency 起订额币种
   * @param tiers 阶梯价（minQuantity / unitPrice），可空
   */
  public static Rounding round(
      BigDecimal rawRequired,
      BigDecimal boxSpec,
      BigDecimal moq,
      BigDecimal minOrderAmount,
      String quoteCurrency,
      String minOrderCurrency,
      List<ReplenishmentCatalogPort.TierView> tiers) {

    BigDecimal required = rawRequired == null ? BigDecimal.ZERO : rawRequired.max(BigDecimal.ZERO);
    BigDecimal spec = boxSpec;

    // ---- 第一步：整箱 / 整件（向上取整） ----
    BigDecimal exactUnits = required.divide(spec, DIVIDE_SCALE, RoundingMode.HALF_UP);
    BigDecimal units = exactUnits.setScale(0, RoundingMode.CEILING);
    if (units.signum() == 0 && required.signum() > 0) {
      // 净需求量是 0.000001 这种极小值时 ceil 仍是 0 —— 至少订 1 个采购单位，
      // 否则建议量会是 0，而 0 会被读成「不需要补」。
      units = BigDecimal.ONE;
    }
    BigDecimal boxRaised = units.subtract(exactUnits).max(BigDecimal.ZERO);

    // ---- 第二步：MOQ ----
    BigDecimal moqRaised = BigDecimal.ZERO;
    if (moq != null && moq.signum() > 0 && units.compareTo(moq) < 0) {
      moqRaised = moq.subtract(units);
      units = moq;
    }

    // ---- 第三步：采购起订额 ----
    BigDecimal unitPrice = applicableTierPrice(tiers, units);
    BigDecimal estimatedCost =
        unitPrice == null ? null : money(units.multiply(unitPrice));
    boolean minOrderApplied = false;
    String minOrderSkipReason = null;
    if (minOrderAmount != null && minOrderAmount.signum() > 0) {
      if (quoteCurrency == null
          || minOrderCurrency == null
          || !quoteCurrency.equalsIgnoreCase(minOrderCurrency)) {
        minOrderSkipReason =
            "采购起订额币种（"
                + (minOrderCurrency == null ? "未填" : minOrderCurrency)
                + "）与报价币种（"
                + (quoteCurrency == null ? "未填" : quoteCurrency)
                + "）不同，跨币种不比较金额，未做起订额取整";
      } else if (unitPrice == null) {
        minOrderSkipReason =
            "该数量在报价的阶梯里没有适用档（取不到单价），算不出预计费用，未做起订额取整";
      } else {
        // 抬量后可能进入更高一档（单价更低），所以循环到「费用达标」或「抬不动了」为止。
        for (int i = 0; i < 8; i++) {
          BigDecimal cost = money(units.multiply(unitPrice));
          if (cost == null || cost.compareTo(minOrderAmount) >= 0) {
            break;
          }
          BigDecimal needed =
              minOrderAmount
                  .divide(unitPrice, DIVIDE_SCALE, RoundingMode.HALF_UP)
                  .setScale(0, RoundingMode.CEILING);
          if (needed.compareTo(units) <= 0) {
            break;
          }
          units = needed;
          minOrderApplied = true;
          unitPrice = applicableTierPrice(tiers, units);
          if (unitPrice == null) {
            // 抬到没有适用档了 —— 退回上一次还有价的数量，并如实说明。
            units = needed.subtract(BigDecimal.ONE).max(BigDecimal.ZERO);
            break;
          }
        }
        estimatedCost = unitPrice == null ? null : money(units.multiply(unitPrice));
      }
    }

    BigDecimal suggested = money6(units.multiply(spec));
    BigDecimal adjustment = money6(suggested.subtract(required));
    return new Rounding(
        money6(units),
        suggested,
        adjustment,
        unitPrice,
        estimatedCost,
        moq,
        minOrderAmount,
        minOrderApplied,
        minOrderSkipReason,
        money6(boxRaised),
        money6(moqRaised));
  }

  /**
   * 取适用阶梯的单价（开口阶梯：取 {@code minQuantity <= quantity} 里最大的一档）。
   *
   * <p>与 catalog 3.6.2.1 的 {@code applicableTier} 同一套口径（照抄，不重新发明）：
   * 取 {@code minQuantity <= 数量} 里最大的一档。数量低于第一档时返回 <b>null</b> ——
   * 刻意不退回高档价，那会让采购单带出一个供应商没报过的价。
   */
  public static BigDecimal applicableTierPrice(
      List<ReplenishmentCatalogPort.TierView> tiers, BigDecimal quantity) {
    if (tiers == null || tiers.isEmpty() || quantity == null) {
      return null;
    }
    ReplenishmentCatalogPort.TierView chosen = null;
    BigDecimal chosenFrom = null;
    for (var tier : tiers) {
      if (tier == null || tier.minQuantity() == null) {
        continue;
      }
      if (quantity.compareTo(tier.minQuantity()) < 0) {
        continue;
      }
      if (chosenFrom == null || tier.minQuantity().compareTo(chosenFrom) > 0) {
        chosenFrom = tier.minQuantity();
        chosen = tier;
      }
    }
    return chosen == null ? null : chosen.unitPrice();
  }

  /** 数量口径：刻度对齐 numeric(19,6)。 */
  public static BigDecimal money6(BigDecimal value) {
    return value == null ? null : value.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
  }

  /** 金额口径：刻度对齐 numeric(19,4)。 */
  public static BigDecimal money(BigDecimal value) {
    return value == null ? null : value.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
  }

  /** 展示口径：去掉尾零。 */
  public static String plain(BigDecimal value) {
    return value == null ? "—" : value.stripTrailingZeros().toPlainString();
  }
}
