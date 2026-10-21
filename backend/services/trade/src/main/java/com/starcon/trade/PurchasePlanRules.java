package com.starcon.trade;

import static com.starcon.trade.PurchasePlanModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.2.1.3 采购计划的纯算式（不碰数据库，可单测）。
 *
 * <p><b>为什么单独一个类</b>：本模块最容易出错的地方是「周期边界」与「大促 / 日常两条不同的算式」。
 * 这两件事一旦混进 jOOQ 的 SQL 里，读代码的人就看不出「为什么大促的到货日不是建议给的到货日」，
 * 而这正是蓝图加粗句「大促备货与日常补货要分开」要防的分叉。
 *
 * <p><b>四条算式纪律</b>：
 *
 * <ol>
 *   <li><b>周期边界由服务端裁决</b>：月度计划的周期必须是某个自然月的 1 号到月末；
 *       季度计划必须是某个自然季度的首日到末日。调用方给的边界对不上 ⇒ 400，
 *       而不是悄悄改写成「这个月」—— 悄悄改写会让「这份计划覆盖哪段时间」永远查不回来。</li>
 *   <li><b>大促计划量 = 建议量 × 销量系数 × 备货系数</b>，结果按 6 位 HALF_UP；
 *       日常计划量 = 建议量本身（系数恒为 1）。</li>
 *   <li><b>大促期望到货日 = 大促开始日 − 提前天数</b>（备货量大、周期长，必须更早到）；
 *       日常期望到货日 = 补货建议给出的预计到货日，取不到时退回周期起点。</li>
 *   <li><b>算式原文随结论一起返回</b>（{@code quantityBasis}）：「这个数是怎么来的」不靠复算。</li>
 * </ol>
 */
public final class PurchasePlanRules {

  private PurchasePlanRules() {}

  /** 数量刻度：与 DB 的 numeric(19,6) 对齐。 */
  public static final int QUANTITY_SCALE = 6;

  /** 系数刻度：与 DB 的 numeric(19,6) 对齐（费率 / 系数 6 位 HALF_UP）。 */
  public static final int RATE_SCALE = 6;

  /** 一个系数。null 视作 1（「没配 ⇒ 不放大」）。 */
  public static BigDecimal factor(BigDecimal value) {
    if (value == null) {
      return BigDecimal.ONE.setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }
    return value.setScale(RATE_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * 校验计划周期边界与周期类型自洽。
   *
   * <p>月度：{@code periodStart} 必须是某月 1 号，{@code periodEnd} 必须是该月最后一天。
   * 季度：{@code periodStart} 必须是某季首月（1/4/7/10）的 1 号，
   * {@code periodEnd} 必须是该季末月（3/6/9/12）的最后一天。
   */
  public static void validatePeriod(
      PurchasePlanPeriodType periodType, LocalDate periodStart, LocalDate periodEnd) {
    if (periodEnd.isBefore(periodStart)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "计划周期的终点不能早于起点");
    }
    // 周期终点：月度 = 本月末；季度 = 本季末月末（起点月 + 2）。
    LocalDate expectedEnd =
        switch (periodType) {
          case MONTHLY -> periodStart.withDayOfMonth(periodStart.lengthOfMonth());
          case QUARTERLY ->
              periodStart.plusMonths(2).withDayOfMonth(periodStart.plusMonths(2).lengthOfMonth());
        };
    boolean startOk =
        switch (periodType) {
          case MONTHLY -> periodStart.getDayOfMonth() == 1;
          case QUARTERLY ->
              periodStart.getDayOfMonth() == 1
                  && (periodStart.getMonthValue() % 3 == 1);
        };
    if (!startOk) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          periodType == PurchasePlanPeriodType.MONTHLY
              ? "月度计划的周期起点必须是某月的 1 号，收到的是 " + periodStart
              : "季度计划的周期起点必须是某季首月（1/4/7/10）的 1 号，收到的是 " + periodStart);
    }
    if (!periodEnd.equals(expectedEnd)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          periodType == PurchasePlanPeriodType.MONTHLY
              ? "月度计划的周期终点必须是该月的最后一天（" + expectedEnd + "），收到的是 " + periodEnd
              : "季度计划的周期终点必须是该季末月的最后一天（"
                  + expectedEnd
                  + "），收到的是 "
                  + periodEnd);
    }
  }

  /** 周期起点之前 / 之后是否与大促档期相容（大促计划的周期必须**覆盖**整个大促档期）。 */
  public static void validateCampaignCovered(
      LocalDate periodStart, LocalDate periodEnd, LocalDate campaignStart, LocalDate campaignEnd) {
    if (periodStart.isAfter(campaignStart) || periodEnd.isBefore(campaignEnd)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "大促计划的周期必须覆盖整个大促档期（"
              + campaignStart
              + " ~ "
              + campaignEnd
              + "），当前周期是 "
              + periodStart
              + " ~ "
              + periodEnd
              + " —— 周期比大促短，备货就会在档期结束之后才到");
    }
  }

  /**
   * 计划量。
   *
   * @param suggestedQuantity 6.2.1.2 给出的建议量（基础单位）
   * @param planType 计划类型
   * @param salesMultiplier 大促销量系数（日常传 null）
   * @param stockFactor 大促备货系数（日常传 null）
   * @return 计划量（基础单位，6 位）
   */
  public static BigDecimal planQuantity(
      BigDecimal suggestedQuantity,
      PurchasePlanType planType,
      BigDecimal salesMultiplier,
      BigDecimal stockFactor) {
    BigDecimal base = suggestedQuantity.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
    if (!planType.isCampaign()) {
      return base;
    }
    BigDecimal multiplier = factor(salesMultiplier);
    BigDecimal stock = factor(stockFactor);
    return base.multiply(multiplier).multiply(stock).setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * 计划量的算式原文（随结论一起返回）。
   *
   * @param sourceText 汇总基数的说明原文（如「建议量 120（上游处置后调整为 130）（6.2.1.2 批次 RS-…）」）
   * @param planQuantity 算出来的计划量
   * @param planType 计划类型
   * @param salesMultiplier 大促销量系数（日常传 null）
   * @param stockFactor 大促备货系数（日常传 null）
   * @param campaignCode 大促档期编码（日常传 null）
   */
  public static String quantityBasis(
      String sourceText,
      BigDecimal planQuantity,
      PurchasePlanType planType,
      BigDecimal salesMultiplier,
      BigDecimal stockFactor,
      String campaignCode) {
    String source = sourceText == null ? "建议量 无数据" : sourceText;
    if (!planType.isCampaign()) {
      return source + " = 计划量 " + planQuantity.stripTrailingZeros().toPlainString()
          + "（日常补货不放大）";
    }
    BigDecimal multiplier = factor(salesMultiplier);
    BigDecimal stock = factor(stockFactor);
    return source
        + " × 销量系数 "
        + multiplier.stripTrailingZeros().toPlainString()
        + " × 备货系数 "
        + stock.stripTrailingZeros().toPlainString()
        + " = 计划量 "
        + planQuantity.stripTrailingZeros().toPlainString()
        + (campaignCode == null || campaignCode.isBlank() ? "" : "（大促 " + campaignCode + "）");
  }

  /**
   * 期望到货日。
   *
   * @param suggestedArrivalDate 6.2.1.2 给出的预计到货日（可空）
   * @param periodStart 周期起点（日常计划的兜底到货日）
   * @param planType 计划类型
   * @param campaignStart 大促开始日（planType=CAMPAIGN 时必填）
   * @param arrivalLeadDays 大促期望到货提前天数
   */
  public static LocalDate expectedArrivalDate(
      LocalDate suggestedArrivalDate,
      LocalDate periodStart,
      PurchasePlanType planType,
      LocalDate campaignStart,
      int arrivalLeadDays) {
    if (planType.isCampaign()) {
      return campaignStart.minusDays(arrivalLeadDays);
    }
    return suggestedArrivalDate == null ? periodStart : suggestedArrivalDate;
  }

  /** 期望到货日的推导依据原文。 */
  public static String arrivalBasis(
      LocalDate expectedArrivalDate,
      PurchasePlanType planType,
      LocalDate suggestedArrivalDate,
      LocalDate periodStart,
      LocalDate campaignStart,
      int arrivalLeadDays) {
    if (planType.isCampaign()) {
      return "大促开始日 "
          + campaignStart
          + " − 提前 "
          + arrivalLeadDays
          + " 天 = 期望到货日 "
          + expectedArrivalDate
          + "（大促备货量大、周期长，必须早于档期开始到货）";
    }
    if (suggestedArrivalDate == null) {
      return "补货建议没有给出预计到货日 ⇒ 退回计划周期起点 "
          + periodStart
          + " 作为期望到货日（不是「今天到」）";
    }
    return "取 6.2.1.2 补货建议给出的预计到货日 " + suggestedArrivalDate + "（日常补货不另加提前量）";
  }

  /**
   * 计划整体状态由行状态派生（不单独存一份「计划状态」出来自相矛盾）。
   *
   * <p>规则：全是已锁定 ⇒ LOCKED；有锁定成功也有失败 ⇒ PARTIALLY_LOCKED；
   * 有成功但还有没锁的（且没有失败）⇒ LOCKING（还在进行中）；
   * 有失败且一个都没成功 ⇒ LOCK_FAILED；否则（都还没锁）⇒ 保留原状态。
   */
  public static PurchasePlanStatus deriveStatus(
      PurchasePlanStatus current, int locked, int failed, int total) {
    if (total > 0 && locked == total) {
      return PurchasePlanStatus.LOCKED;
    }
    if (locked > 0 && failed > 0) {
      return PurchasePlanStatus.PARTIALLY_LOCKED;
    }
    if (locked > 0) {
      return PurchasePlanStatus.LOCKING;
    }
    if (failed > 0) {
      return PurchasePlanStatus.LOCK_FAILED;
    }
    return current;
  }
}
