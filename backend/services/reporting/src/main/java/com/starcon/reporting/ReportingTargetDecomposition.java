package com.starcon.reporting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 13.1.2.1 目标**分解计划**的计算（纯计算，不碰数据库、不碰网络）。
 *
 * <p>蓝图要求：「设定…指标与周期，<b>支持分解到月/周</b>。」本类把一个「周期内总额」
 * 拆成一串「月 / 周」分解项，并保证：
 *
 * <p>🔴 **分解项合计精确等于目标值**（不是「约等于」）——
 * 「均分后合计 ≠ 目标值」是浮点加法最常见的坑；本仓禁浮点，
 * 金额一律 {@link BigDecimal} + {@code numeric(24,4)}，所以这里能做到**逐分不差**。
 *
 * <p>🔴 **余数归属写清楚**（两种方式都写进响应，不静默）：
 *
 * <ul>
 *   <li><b>EVEN（均分）</b>：每项先按 HALF_UP 取到 4 位小数，
 *       <b>差额（余数）归最后一个分解项</b> ⇒ 合计精确等于目标值；</li>
 *   <li><b>WEIGHTED（按权重）</b>：每项先按权重算出精确份额并向下取整到 4 位小数，
 *       余下的「最小单位」（0.0001）按<b>小数部分从大到小</b>逐个补齐（最大余额法 / Hare quota）
 *       ⇒ 合计同样精确等于目标值，且每项与「精确份额」的偏差不超过 0.0001。</li>
 * </ul>
 *
 * <p>⚠️ **权重不决定金额**：权重只是「怎么分」的相对比例，金额由本类算出来。
 * 两个分解项权重相同时，金额也可能因余数补齐而差 0.0001 —— 这是**有意的**：
 * 宁可让最后一项承担余数，也不让合计对不上。
 */
final class ReportingTargetDecomposition {

  /** 金额刻度：与服务端 {@code numeric(24,4)} 对齐。 */
  static final int VALUE_SCALE = 4;

  /** 权重刻度：与服务端 {@code numeric(19,6)} 对齐。 */
  static final int WEIGHT_SCALE = 6;

  /** 分解项个数的上限（防呆：一次分解出上千项没有意义）。 */
  static final int MAX_BUCKETS = 200;

  /** 精确份额的计算刻度（比金额刻度细，避免取整误差累积）。 */
  private static final int EXACT_SCALE = 20;

  private ReportingTargetDecomposition() {}

  /** 一个分解项。 */
  record Bucket(LocalDate start, LocalDate end, BigDecimal weight, BigDecimal value) {}

  /** 分解算不出来 / 口径不成立时抛出（服务层翻成 400）。 */
  static class InvalidDecomposition extends RuntimeException {
    InvalidDecomposition(String message) {
      super(message);
    }
  }

  // ==========================================================================
  // 分解项边界
  // ==========================================================================

  /**
   * 按周期与粒度算出分解项边界。
   *
   * <p>⚠️ **周期必须与粒度对齐**（服务层与 DB 层都钉死）：
   * MONTHLY ⇒ 起点是某月 1 日、终点是某月末日；WEEKLY ⇒ 起点是 ISO 周周一、终点是 ISO 周周日。
   * 不对齐的周期切不干净，分解项会半截落在周期外。
   */
  static List<Bucket> buckets(LocalDate periodStart, LocalDate periodEnd, String granularity) {
    if (periodStart == null || periodEnd == null) {
      throw new InvalidDecomposition("周期起止必须同时给出。");
    }
    if (periodStart.isAfter(periodEnd)) {
      throw new InvalidDecomposition(
          "周期起始日期不能晚于结束日期（from=" + periodStart + "、to=" + periodEnd + "）。");
    }
    var out = new ArrayList<Bucket>();
    if ("MONTHLY".equals(granularity)) {
      if (periodStart.getDayOfMonth() != 1) {
        throw new InvalidDecomposition(
            "按月分解时周期起点必须是某月 1 日（收到 " + periodStart + "）。");
      }
      if (!periodEnd.equals(periodEnd.withDayOfMonth(periodEnd.lengthOfMonth()))) {
        throw new InvalidDecomposition(
            "按月分解时周期终点必须是某月的最后一天（收到 " + periodEnd + "）。");
      }
      var cursor = periodStart;
      while (!cursor.isAfter(periodEnd)) {
        var end = cursor.withDayOfMonth(cursor.lengthOfMonth());
        out.add(new Bucket(cursor, end, null, null));
        cursor = cursor.plusMonths(1);
      }
    } else if ("WEEKLY".equals(granularity)) {
      if (periodStart.getDayOfWeek() != DayOfWeek.MONDAY) {
        throw new InvalidDecomposition(
            "按周分解时周期起点必须是 ISO 周的周一（收到 " + periodStart + "）。");
      }
      if (periodEnd.getDayOfWeek() != DayOfWeek.SUNDAY) {
        throw new InvalidDecomposition(
            "按周分解时周期终点必须是 ISO 周的周日（收到 " + periodEnd + "）。");
      }
      var cursor = periodStart;
      while (!cursor.isAfter(periodEnd)) {
        out.add(new Bucket(cursor, cursor.plusDays(6), null, null));
        cursor = cursor.plusDays(7);
      }
    } else {
      throw new InvalidDecomposition("分解粒度只能是 MONTHLY 或 WEEKLY，收到：" + granularity + "。");
    }
    if (out.isEmpty()) {
      throw new InvalidDecomposition("周期里一个分解项都没有（请检查周期起止）。");
    }
    if (out.size() > MAX_BUCKETS) {
      throw new InvalidDecomposition(
          "一次最多分解 " + MAX_BUCKETS + " 项（当前周期会切出 " + out.size() + " 项）—— "
              + "请缩短周期或改用更粗的粒度。");
    }
    return out;
  }

  // ==========================================================================
  // 分解
  // ==========================================================================

  /**
   * 分解一个周期内的目标总额。
   *
   * @param periodStart 周期起
   * @param periodEnd 周期止
   * @param granularity 分解粒度（MONTHLY / WEEKLY）
   * @param method 分解方式（EVEN / WEIGHTED）
   * @param targetValue 周期内目标总额
   * @param weights WEIGHTED 时的相对权重（长度必须等于分解项个数；EVEN 时传 null / 空）
   * @return 分解项（每项带金额与权重）
   */
  static List<Bucket> decompose(
      LocalDate periodStart,
      LocalDate periodEnd,
      String granularity,
      String method,
      BigDecimal targetValue,
      List<BigDecimal> weights) {
    if (targetValue == null) {
      throw new InvalidDecomposition("目标值不能为空。");
    }
    var boundaries = buckets(periodStart, periodEnd, granularity);
    var total = targetValue.setScale(VALUE_SCALE, RoundingMode.HALF_UP);
    if ("EVEN".equals(method)) {
      if (weights != null && !weights.isEmpty()) {
        throw new InvalidDecomposition("均分（EVEN）不接受权重：权重只属于按权重（WEIGHTED）分解。");
      }
      return even(boundaries, total);
    }
    if ("WEIGHTED".equals(method)) {
      if (weights == null || weights.isEmpty()) {
        throw new InvalidDecomposition("按权重（WEIGHTED）分解必须给出每个分解项的权重。");
      }
      if (weights.size() != boundaries.size()) {
        throw new InvalidDecomposition(
            "权重个数（"
                + weights.size()
                + "）必须等于分解项个数（"
                + boundaries.size()
                + "）—— 这个周期会切出 "
                + boundaries.size()
                + " 个分解项。");
      }
      return weighted(boundaries, total, weights);
    }
    throw new InvalidDecomposition("分解方式只能是 EVEN 或 WEIGHTED，收到：" + method + "。");
  }

  /**
   * 均分：每项 HALF_UP 取到 4 位小数，<b>差额归最后一个分解项</b>。
   *
   * <p>⚠️ 「每项四舍五入后直接相加」会差几分钱 —— 所以差额由最后一项承担，
   * 合计**精确等于**目标值（{@link BigDecimal} 的加减是精确的）。
   */
  private static List<Bucket> even(List<Bucket> boundaries, BigDecimal total) {
    var n = BigDecimal.valueOf(boundaries.size());
    var base = total.divide(n, VALUE_SCALE, RoundingMode.HALF_UP);
    var remainder = total.subtract(base.multiply(n));
    var out = new ArrayList<Bucket>(boundaries.size());
    for (var index = 0; index < boundaries.size(); index++) {
      var boundary = boundaries.get(index);
      var value = index == boundaries.size() - 1 ? base.add(remainder) : base;
      out.add(new Bucket(boundary.start(), boundary.end(), BigDecimal.ONE, value));
    }
    return out;
  }

  /**
   * 按权重分配（<b>最大余额法 / Hare quota</b>）：先向下取整，再把余下的最小单位
   * 按小数部分从大到小逐个补齐 ⇒ 合计精确等于目标值。
   */
  private static List<Bucket> weighted(
      List<Bucket> boundaries, BigDecimal total, List<BigDecimal> weights) {
    var normalized = new ArrayList<BigDecimal>(weights.size());
    var totalWeight = BigDecimal.ZERO;
    for (var weight : weights) {
      if (weight == null) {
        throw new InvalidDecomposition("权重不能为空。");
      }
      var scaled = weight.setScale(WEIGHT_SCALE, RoundingMode.HALF_UP);
      if (scaled.signum() < 0) {
        throw new InvalidDecomposition("权重不能为负数（收到 " + scaled + "）。");
      }
      normalized.add(scaled);
      totalWeight = totalWeight.add(scaled);
    }
    if (totalWeight.signum() == 0) {
      throw new InvalidDecomposition("权重合计不能为 0 —— 全 0 的权重分不出任何金额。");
    }

    var exact = new ArrayList<BigDecimal>(boundaries.size());
    var floored = new ArrayList<BigDecimal>(boundaries.size());
    var assigned = BigDecimal.ZERO;
    for (var index = 0; index < boundaries.size(); index++) {
      var share =
          total.multiply(normalized.get(index)).divide(totalWeight, EXACT_SCALE, RoundingMode.HALF_UP);
      exact.add(share);
      var floor = share.setScale(VALUE_SCALE, RoundingMode.FLOOR);
      floored.add(floor);
      assigned = assigned.add(floor);
    }

    // 余下的最小单位（0.0001）个数。差额恒 ≥ 0（向下取整只会少给），
    // 且恒 < 分解项个数（每项的小数部分都 < 1 个最小单位）。
    var diff = total.subtract(assigned);
    var units = diff.movePointRight(VALUE_SCALE).setScale(0, RoundingMode.FLOOR).intValueExact();
    if (units < 0 || units >= boundaries.size()) {
      throw new IllegalStateException(
          "分解余数超出预期（units=" + units + "、项数=" + boundaries.size() + "），分解口径被改坏了。");
    }

    // 按小数部分从大到小排序；**小数部分相同时归后面的分解项**
    // （与均分的「差额归最后一个分解项」一致 —— 同一套余数归属口径）。
    var order = new ArrayList<Integer>(boundaries.size());
    for (var index = 0; index < boundaries.size(); index++) {
      order.add(index);
    }
    order.sort(
        Comparator.comparing((Integer index) -> exact.get(index).subtract(floored.get(index)))
            .reversed()
            .thenComparing((a, b) -> Integer.compare(b, a)));

    var values = new ArrayList<>(floored);
    var unit = BigDecimal.ONE.movePointLeft(VALUE_SCALE);
    for (var position = 0; position < units; position++) {
      var index = order.get(position);
      values.set(index, values.get(index).add(unit));
    }

    var out = new ArrayList<Bucket>(boundaries.size());
    for (var index = 0; index < boundaries.size(); index++) {
      var boundary = boundaries.get(index);
      out.add(new Bucket(boundary.start(), boundary.end(), normalized.get(index), values.get(index)));
    }
    return out;
  }

  // ==========================================================================
  // 说明
  // ==========================================================================

  /** 余数归属说明（写进响应，让「为什么这一项多 0.0001」有据可查）。 */
  static String remainderNote(String method, List<Bucket> buckets, BigDecimal total) {
    var sum = BigDecimal.ZERO;
    for (var bucket : buckets) {
      sum = sum.add(bucket.value());
    }
    var diff = total.subtract(sum);
    if ("EVEN".equals(method)) {
      return "均分：每个分解项先按 HALF_UP 取到 "
          + VALUE_SCALE
          + " 位小数，"
          + "差额（"
          + diff.toPlainString()
          + "）归最后一个分解项 —— 合计精确等于目标值（"
          + sum.toPlainString()
          + " = "
          + total.toPlainString()
          + "），不存在「差 0.01」的静默不一致。";
    }
    return "按权重分配：每个分解项先按权重算出精确份额并向下取整到 "
        + VALUE_SCALE
        + " 位小数，"
        + "余下的最小单位（0.0001）按小数部分从大到小逐个补齐"
        + "（小数部分相同时归后面的分解项，与均分的「差额归最后一项」一致）—— "
        + "合计精确等于目标值（"
        + sum.toPlainString()
        + " = "
        + total.toPlainString()
        + "），每项与精确份额的偏差不超过 0.0001。";
  }
}
