package com.starcon.trade;

import static com.starcon.trade.QualityInspectionModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 6.4.2.1 质检单的口径与算法。
 *
 * <p>三件事在这里算，别处只调用：
 *
 * <ol>
 *   <li><b>应抽数量</b>（{@link #samplePlan}）—— 按模板的抽样比例 + 本次批量 + 最小/最大夹逼 +
 *       舍入口径算出，并把<b>算式原文</b>一起返回（随单落库）。</li>
 *   <li><b>三量分配</b>（{@link #balance}）—— 扣量合计、让步接收量、最终不合格量、合格量。
 *       蓝图加粗句「质检不合格的货不能直接进可售库存」的算术落点。</li>
 *   <li><b>检验结论</b>（{@link #resultOf}）—— 由三量推出合格 / 整批不合格 / 部分合格 / 让步接收。</li>
 * </ol>
 *
 * <p><b>为什么单独成类</b>：这些口径一旦散进 service 的各个方法里，就会出现
 * 「列表页算一套、完结时算另一套」的情况 —— 页面显示的合格量与落库的合格量对不上，
 * 而这种不一致在测试里很难被发现（两个数都是合理的）。
 */
public final class QualityInspectionRules {

  private QualityInspectionRules() {}

  /** 数量刻度：与 DB 的 numeric(19,6) 对齐。 */
  public static final int QUANTITY_SCALE = 6;

  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);

  /** 应抽数量的计算结果（附算式原文）。 */
  public record SamplePlan(
      BigDecimal lotSize,
      BigDecimal ratio,
      BigDecimal minSample,
      BigDecimal maxSample,
      BigDecimal rawQuantity,
      BigDecimal clampedQuantity,
      BigDecimal sampleQuantity,
      String basis) {}

  /** 三量分配结果。 */
  public record QuantityBalance(
      BigDecimal lotSize,
      BigDecimal grossDeducted,
      BigDecimal concession,
      BigDecimal defective,
      BigDecimal qualified,
      BigDecimal sellable,
      BigDecimal nonSellable,
      boolean balanced) {}

  /**
   * 按模板的抽样规则算应抽数量。
   *
   * <p>算式（顺序固定，算式原文写进 {@code basis} 落库）：
   *
   * <ol>
   *   <li>{@code raw = 批量 × 比例}（比例是 0~1 的<b>比率</b>，不是百分数）；</li>
   *   <li>夹逼到 {@code [最小抽样数, 最大抽样数]}（最大为 0 表示不限）；</li>
   *   <li>再夹逼到不超过**批量本身**（抽不出比货还多的件）；</li>
   *   <li>按<b>舍入口径</b>取整。</li>
   * </ol>
   *
   * <p>⚠️ 比例的单位：{@code sampling_ratio} 是 {@code numeric(19,6)} 且 CHECK 落在 {@code (0, 1]}，
   * 即 10% 写 {@code 0.1}。把它当成百分数（写 10）会算出 1000 倍的抽样量。
   */
  public static SamplePlan samplePlan(
      BigDecimal lotSize,
      BigDecimal ratio,
      BigDecimal minSample,
      BigDecimal maxSample,
      IncomingRoundingMode roundingMode) {
    BigDecimal lot = scale(lotSize);
    BigDecimal raw = lot.multiply(ratio).setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
    BigDecimal min = minSample == null ? ZERO : scale(minSample);
    BigDecimal max = maxSample == null ? ZERO : scale(maxSample);

    BigDecimal clamped = raw;
    if (clamped.compareTo(min) < 0) {
      clamped = min;
    }
    if (max.signum() > 0 && clamped.compareTo(max) > 0) {
      clamped = max;
    }
    // 批量本身是硬上界：货只有这么多，抽不出更多。
    if (clamped.compareTo(lot) > 0) {
      clamped = lot;
    }
    BigDecimal sample = round(clamped, roundingMode);
    if (sample.compareTo(lot) > 0) {
      // 向上取整可能把 0.1 件取成 1 件，而批量只有 0.1 件 —— 夹回批量。
      sample = lot;
    }
    if (sample.signum() < 0) {
      sample = ZERO;
    }
    String basis =
        "批量 "
            + lot.toPlainString()
            + " × 比例 "
            + ratio.stripTrailingZeros().toPlainString()
            + " = "
            + raw.toPlainString()
            + "，夹逼 ["
            + min.toPlainString()
            + ", "
            + (max.signum() > 0 ? max.toPlainString() : "不限")
            + "]"
            + " → "
            + clamped.toPlainString()
            + "，"
            + roundingMode.label()
            + " → 应抽 "
            + sample.toPlainString();
    return new SamplePlan(lot, ratio, min, max, raw, clamped, sample, basis);
  }

  /**
   * 三量分配。
   *
   * <p>口径（蓝图输出句「合格量 / 不合格量 / 让步接收量」）：
   *
   * <ul>
   *   <li><b>扣量合计</b> = 各检验项的批次扣量之和（关键项整批拒收时 = 整批）；</li>
   *   <li><b>让步接收量</b> 从扣量合计**里面**出（让步接收是「不合格里被批准放行的那部分」，
   *       不是把不合格改成合格）⇒ 让步接收量 ≤ 扣量合计；</li>
   *   <li><b>不合格量</b> = 扣量合计 − 让步接收量；</li>
   *   <li><b>合格量</b> = 批量 − 扣量合计。</li>
   * </ul>
   *
   * <p>不变量：{@code 合格量 + 不合格量 + 让步接收量 = 批量}，恒成立（{@link QuantityBalance#balanced()}）。
   */
  public static QuantityBalance balance(
      BigDecimal lotSize, BigDecimal grossDeducted, BigDecimal concession) {
    BigDecimal lot = scale(lotSize);
    BigDecimal gross = scale(grossDeducted);
    BigDecimal conc = scale(concession);
    if (conc.compareTo(gross) > 0) {
      // 让步接收量超过扣量合计 —— 这在数学上意味着「放行了并不存在的不合格品」。
      // 这里夹到扣量合计，真正的拒绝在服务层（带明确文案），这里是最后一道算术兜底。
      conc = gross;
    }
    if (gross.compareTo(lot) > 0) {
      gross = lot;
    }
    BigDecimal defective = gross.subtract(conc).setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
    BigDecimal qualified = lot.subtract(gross).setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
    BigDecimal sellable = qualified.add(conc).setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
    BigDecimal nonSellable = defective.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
    boolean balanced =
        qualified.add(defective).add(conc).compareTo(lot) == 0
            && qualified.signum() >= 0
            && defective.signum() >= 0
            && conc.signum() >= 0;
    return new QuantityBalance(lot, gross, conc, defective, qualified, sellable, nonSellable, balanced);
  }

  /**
   * 由三量推出检验结论。
   *
   * <p>口径：
   *
   * <ul>
   *   <li>不合格量 = 批量 ⇒ <b>整批不合格</b>；</li>
   *   <li>不合格量 = 0 且有让步接收 ⇒ <b>让步接收</b>；</li>
   *   <li>不合格量 = 0 且无让步接收 ⇒ <b>合格</b>；</li>
   *   <li>其余 ⇒ <b>部分合格</b>。</li>
   * </ul>
   */
  public static IncomingInspectionResult resultOf(QuantityBalance balance) {
    if (balance.defective().compareTo(balance.lotSize()) >= 0) {
      return IncomingInspectionResult.FAILED;
    }
    if (balance.defective().signum() == 0 && balance.concession().signum() > 0) {
      return IncomingInspectionResult.CONCESSION_ACCEPTED;
    }
    if (balance.defective().signum() == 0) {
      return IncomingInspectionResult.PASSED;
    }
    return IncomingInspectionResult.PARTIAL;
  }

  /**
   * 分流目标。
   *
   * <p>不合格量 &gt; 0 ⇒ 一定不是 {@code SELLABLE}（蓝图加粗句）。
   * 合格量与让步接收量进可售；不合格量按模板的去向（待检 / 残次）走。
   */
  public static IncomingDisposition dispositionOf(
      QuantityBalance balance, IncomingDefectiveTarget defectiveTarget) {
    if (balance.nonSellable().signum() == 0) {
      return IncomingDisposition.SELLABLE;
    }
    return switch (defectiveTarget == null
        ? IncomingDefectiveTarget.PENDING_INSPECTION
        : defectiveTarget) {
      case PENDING_INSPECTION -> IncomingDisposition.PENDING_INSPECTION;
      case DEFECTIVE -> IncomingDisposition.DEFECTIVE;
    };
  }

  /** 扣量金额 = 扣量 × 单价 ×（汇率 / 单位换算，按录入口径）。 */
  public static BigDecimal deductedAmount(
      BigDecimal grossDeducted,
      BigDecimal unitPrice,
      BigDecimal exchangeRate,
      BigDecimal unitConversionFactor) {
    if (unitPrice == null) {
      return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    }
    BigDecimal amount = scale(grossDeducted).multiply(unitPrice);
    if (exchangeRate != null) {
      amount = amount.multiply(exchangeRate);
    }
    if (unitConversionFactor != null) {
      amount = amount.divide(unitConversionFactor, 10, RoundingMode.HALF_UP);
    }
    return amount.setScale(4, RoundingMode.HALF_UP);
  }

  /** 数量刻度对齐（6 位 HALF_UP）。 */
  public static BigDecimal scale(BigDecimal value) {
    return value == null ? ZERO : value.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
  }

  /** 按口径取整到整数件。 */
  public static BigDecimal round(BigDecimal value, IncomingRoundingMode mode) {
    RoundingMode rm =
        switch (mode == null ? IncomingRoundingMode.CEILING : mode) {
          case CEILING -> RoundingMode.CEILING;
          case ROUND_HALF_UP -> RoundingMode.HALF_UP;
          case FLOOR -> RoundingMode.FLOOR;
        };
    BigDecimal result = value.setScale(0, rm);
    // 件数不可能是负数；FLOOR 对 0.1 会得到 0 —— 那就意味着「这一项不用抽」。
    return result.signum() < 0 ? BigDecimal.ZERO.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP) : result;
  }
}
