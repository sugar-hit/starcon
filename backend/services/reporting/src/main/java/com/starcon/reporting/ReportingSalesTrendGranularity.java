package com.starcon.reporting;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 13.2.1.1 销量趋势的**分桶粒度**（桶的定义本身就是一种口径）。
 *
 * <p>蓝图职责句：「按<b>日/周/月、小时</b>维度查看」⇒ 四档粒度。
 *
 * <p>⚠️ **桶的对齐规则写在这里，不散落在各处**：
 *
 * <ul>
 *   <li><b>周</b>按 ISO 周（周一为一周之始）；
 *   <li><b>月</b>按自然月；
 *   <li><b>小时</b>按本地时区的整点。
 * </ul>
 *
 * <p>⚠️ **桶在查询期算，不在投影期算**：事实表存的是**精确时刻**，
 * 分桶用 {@code date_trunc(bucket_start AT TIME ZONE tz)} —— 这样半小时偏移的时区
 * （+05:30 / +05:45）也能精确落到本地日，换时区也不必重新投影。
 */
public enum ReportingSalesTrendGranularity {

  /** 日。 */
  DAY("day", "日"),
  /** 周（ISO 周，周一为一周之始）。 */
  WEEK("week", "周"),
  /** 月（自然月）。 */
  MONTH("month", "月"),
  /** 小时（本地时区的整点）。 */
  HOUR("hour", "小时");

  private final String sqlUnit;
  private final String label;

  ReportingSalesTrendGranularity(String sqlUnit, String label) {
    this.sqlUnit = sqlUnit;
    this.label = label;
  }

  /** PostgreSQL {@code date_trunc} 的单位名（第一参数）。 */
  public String sqlUnit() {
    return sqlUnit;
  }

  /** 中文标签。 */
  public String label() {
    return label;
  }

  /** 解析调用方传入的粒度（大小写不敏感；取不到返回 null，由调用方报 400）。 */
  public static ReportingSalesTrendGranularity of(String value) {
    if (value == null || value.isBlank()) return null;
    var normalized = value.trim().toUpperCase();
    for (var granularity : values()) {
      if (granularity.name().equals(normalized)) return granularity;
    }
    return null;
  }

  /** 把日期对齐到**桶起点**。 */
  public LocalDate alignStart(LocalDate date) {
    return switch (this) {
      case DAY, HOUR -> date;
      case WEEK -> date.minusDays(date.getDayOfWeek().getValue() - 1L);
      case MONTH -> date.withDayOfMonth(1);
    };
  }

  /** 把日期对齐到**桶终点**（含）。 */
  public LocalDate alignEnd(LocalDate date) {
    return switch (this) {
      case DAY, HOUR -> date;
      case WEEK -> alignStart(date).plusDays(6);
      case MONTH -> date.withDayOfMonth(date.lengthOfMonth());
    };
  }

  /**
   * 把一个本地时刻**截断到桶键**（与 SQL 的 {@code date_trunc} 同构）。
   *
   * <p>⚠️ 必须与 SQL 侧**逐字同构** —— 两边不一致会让「桶的对齐」出现一天 / 一周的偏移。
   */
  public LocalDateTime truncate(LocalDateTime local) {
    return switch (this) {
      case DAY -> local.toLocalDate().atStartOfDay();
      case WEEK -> local.toLocalDate().minusDays(local.getDayOfWeek().getValue() - 1L).atStartOfDay();
      case MONTH -> local.toLocalDate().withDayOfMonth(1).atStartOfDay();
      case HOUR -> local.truncatedTo(ChronoUnit.HOURS);
    };
  }

  /** 桶键往前挪一个桶（环比的基准桶）。 */
  public LocalDateTime previous(LocalDateTime bucketKey) {
    return shift(bucketKey, -1);
  }

  /** 桶键挪动 {@code steps} 个桶（正数向后，负数向前）。 */
  public LocalDateTime shift(LocalDateTime bucketKey, int steps) {
    return switch (this) {
      case DAY -> bucketKey.plusDays(steps);
      case WEEK -> bucketKey.plusWeeks(steps);
      case MONTH -> bucketKey.plusMonths(steps);
      case HOUR -> bucketKey.plusHours(steps);
    };
  }

  /** 一个窗口里有几个桶（含首尾）。 */
  public int bucketCount(LocalDate from, LocalDate to) {
    var start = truncate(from.atStartOfDay());
    var end = truncate(to.atStartOfDay());
    return switch (this) {
      case DAY -> (int) ChronoUnit.DAYS.between(start, end) + 1;
      case WEEK -> (int) ChronoUnit.WEEKS.between(start, end) + 1;
      case MONTH -> (int) ChronoUnit.MONTHS.between(start, end) + 1;
      case HOUR -> (int) ChronoUnit.HOURS.between(start, end) + 1;
    };
  }

  /** 桶键所在桶的**结束日**（含）。 */
  public LocalDate bucketEndDate(LocalDateTime bucketKey) {
    var start = bucketKey.toLocalDate();
    return switch (this) {
      case DAY, HOUR -> start;
      case WEEK -> start.plusDays(6);
      case MONTH -> start.withDayOfMonth(start.lengthOfMonth());
    };
  }

  /** 桶键往前挪一年（同比的基准桶，按同一套截断规则重新对齐）。 */
  public LocalDateTime yearAgo(LocalDateTime bucketKey) {
    return truncate(bucketKey.minusYears(1));
  }

  /** 桶的展示标签。 */
  public String bucketLabel(LocalDate start, LocalDate end, Integer hour) {
    if (this == HOUR) {
      return start + " " + String.format("%02d:00", hour == null ? 0 : hour);
    }
    if (this == WEEK) return start + " ~ " + end;
    if (this == MONTH) return start.toString().substring(0, 7);
    return start.toString();
  }
}
