package com.starcon.channel;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * 16.3.1.2 申报日历的<b>纯函数</b>判定规则。
 *
 * <p>🔴 这一类里**没有 IO、没有 Spring、没有数据库** —— 申报期怎么切、截止日怎么算、
 * 状态能不能倒退，全是可单测的纯函数。把它从服务里剥出来，是因为「期算错了」是本模块
 * 最贵的错误：期算错 ⇒ 截止日算错 ⇒ 提醒算错 ⇒ <b>漏报</b>。
 *
 * <p>🔴 两条硬口径：
 *
 * <ol>
 *   <li><b>状态单调</b>：rank 只许上升（未开始 → 数据就绪 → 已提交 → 已确认）。
 *       日历是用来盯「有没有漏报」的，状态倒退 = 漏报被悄悄抹掉。</li>
 *   <li><b>截止日是当地时刻</b>：欧盟各国申报截止时间都是当地时间（Berlin 23:59 ≠
 *       UTC 23:59）。配置里存 IANA 时区名，按那个时区算出瞬时 —— 写死 UTC 会差几小时，
 *       而差几小时就足以错过一个截止日。</li>
 * </ol>
 */
public final class FilingCalendarRules {

  private FilingCalendarRules() {}

  // ---------------------------------------------------------------- 词表

  /** 申报线（税种）词表 —— 与 12.6.1.1 的税种词表同族，但**不含 WITHHOLDING**。 */
  public static final List<String> TAX_TYPES =
      List.of("VAT", "IOSS", "SALES_TAX", "GST", "CONSUMPTION_TAX");

  /** 申报周期词表。 */
  public static final List<String> FILING_CYCLES =
      List.of("MONTHLY", "QUARTERLY", "SEMI_ANNUAL", "ANNUAL");

  /** 状态词表（受控）。 */
  public static final List<String> STATUSES =
      List.of("NOT_STARTED", "DATA_READY", "SUBMITTED", "CONFIRMED");

  /** 提醒档位词表。 */
  public static final List<String> REMINDER_KINDS = List.of("DUE_SOON", "DUE");

  /** 申报线的人话。 */
  public static final Map<String, String> TAX_TYPE_LABELS =
      Map.of(
          "VAT", "增值税（VAT）",
          "IOSS", "IOSS（一站式商店）",
          "SALES_TAX", "销售税",
          "GST", "商品服务税（GST）",
          "CONSUMPTION_TAX", "消费税");

  /** 申报周期的人话。 */
  public static final Map<String, String> FILING_CYCLE_LABELS =
      Map.of(
          "MONTHLY", "按月",
          "QUARTERLY", "按季",
          "SEMI_ANNUAL", "按半年",
          "ANNUAL", "按年");

  /** 状态的人话。 */
  public static final Map<String, String> STATUS_LABELS =
      Map.of(
          "NOT_STARTED", "未开始",
          "DATA_READY", "数据就绪",
          "SUBMITTED", "已提交",
          "CONFIRMED", "已确认");

  /** 提醒档位的人话。 */
  public static final Map<String, String> REMINDER_KIND_LABELS =
      Map.of("DUE_SOON", "即将到期", "DUE", "截止日");

  /** 税种的人话（读不到键时回落成原值，不吞掉未知值）。 */
  public static String taxTypeLabel(String value) {
    return TAX_TYPE_LABELS.getOrDefault(value, value == null ? "" : value);
  }

  /** 周期的人话。 */
  public static String filingCycleLabel(String value) {
    return FILING_CYCLE_LABELS.getOrDefault(value, value == null ? "" : value);
  }

  /** 状态的人话。 */
  public static String statusLabel(String value) {
    return STATUS_LABELS.getOrDefault(value, value == null ? "" : value);
  }

  /** 提醒档位的人话。 */
  public static String reminderKindLabel(String value) {
    return REMINDER_KIND_LABELS.getOrDefault(value, value == null ? "" : value);
  }

  // ---------------------------------------------------------------- 状态

  /**
   * 状态的序号（越大越靠后）。
   *
   * @param status 状态
   * @return rank（未知状态按 0 处理，**不抛异常** —— 状态是数据，不是代码）
   */
  public static int statusRank(String status) {
    if (status == null) {
      return 0;
    }
    return switch (status) {
      case "NOT_STARTED" -> 0;
      case "DATA_READY" -> 1;
      case "SUBMITTED" -> 2;
      case "CONFIRMED" -> 3;
      default -> 0;
    };
  }

  /**
   * 状态能不能从 {@code from} 推到 {@code to}。
   *
   * @param from 当前状态
   * @param to 目标状态
   * @return true = 允许（**只许前进**；同状态是幂等，不算倒退）
   */
  public static boolean canAdvance(String from, String to) {
    if (!STATUSES.contains(to)) {
      return false;
    }
    return statusRank(to) >= statusRank(from);
  }

  // ---------------------------------------------------------------- 申报期

  /**
   * 一个申报期。
   *
   * @param start 期起
   * @param end 期止
   */
  public record Period(LocalDate start, LocalDate end) {}

  /**
   * 一个申报周期跨几个月。
   *
   * @param filingCycle 周期
   * @return 月数（未知周期按 1 处理）
   */
  public static int cycleMonths(String filingCycle) {
    if (filingCycle == null) {
      return 1;
    }
    return switch (filingCycle) {
      case "MONTHLY" -> 1;
      case "QUARTERLY" -> 3;
      case "SEMI_ANNUAL" -> 6;
      case "ANNUAL" -> 12;
      default -> 1;
    };
  }

  /**
   * 🔴 算「{@code reference} 落在哪一期」。
   *
   * <p>口径：**以配置的生效起始日为原点，按周期在月份上排格子；每格从「期起始日」那天开始，
   * 跨 N 个月**。
   *
   * <ul>
   *   <li>生效起始日 2026-01-01 + 期起始日 1 号 + 按季 ⇒ 格子是 1/4/7/10 月 ⇒
   *       2026-05-15 落在 [2026-04-01, 2026-06-30]（**日历季度**）。</li>
   *   <li>期起始日 15 号 ⇒ 同一张格子表上每格从 15 号开始，2026-05-15 落在
   *       [2026-04-15, 2026-07-14]。</li>
   *   <li>生效起始日之前的期间**不产出**（配置还没生效 ⇒ 那时没有这项申报义务）。</li>
   * </ul>
   *
   * @param reference 参考日（一般是「今天」）
   * @param effectiveFrom 配置的生效起始日（格子原点）
   * @param anchorDay 期起始日（1..28）
   * @param filingCycle 申报周期
   * @return 申报期
   */
  public static Period periodFor(
      LocalDate reference, LocalDate effectiveFrom, int anchorDay, String filingCycle) {
    int months = cycleMonths(filingCycle);
    int day = Math.min(Math.max(anchorDay, 1), 28);
    LocalDate origin = effectiveFrom.withDayOfMonth(1);
    long elapsed = ChronoUnit.MONTHS.between(origin, reference.withDayOfMonth(1));
    long index = Math.floorDiv(Math.max(elapsed, 0L), (long) months);
    LocalDate gridStart = origin.plusMonths(index * months);
    LocalDate start = gridStart.withDayOfMonth(day);
    if (start.isAfter(reference)) {
      gridStart = gridStart.minusMonths(months);
      start = gridStart.withDayOfMonth(day);
    }
    LocalDate end = gridStart.plusMonths(months).withDayOfMonth(day).minusDays(1);
    if (start.isBefore(effectiveFrom)) {
      start = effectiveFrom;
      end = start.plusMonths(months).minusDays(1);
    }
    return new Period(start, end);
  }

  /**
   * 🔴 算截止时刻。
   *
   * <p>截止日 = 期末 + 偏移天数，在<b>配置的当地时刻</b>（时区名 + 小时）触发。
   *
   * @param periodEnd 期末
   * @param offsetDays 偏移天数（期末 + N 天）
   * @param dueHour 当地小时（0..23）
   * @param zone 当地时区
   * @return 截止时刻（带偏移量）
   */
  public static OffsetDateTime dueAt(
      LocalDate periodEnd, int offsetDays, int dueHour, ZoneId zone) {
    LocalDate deadline = periodEnd.plusDays(offsetDays);
    ZonedDateTime moment =
        ZonedDateTime.of(deadline, LocalTime.of(Math.min(Math.max(dueHour, 0), 23), 0), zone);
    return moment.toOffsetDateTime();
  }

  /**
   * 解析时区名。
   *
   * @param raw 时区名（如 {@code Europe/Berlin}）
   * @return 时区
   * @throws IllegalArgumentException 时区名非法（调用方据此回 422，**不静默兜底成 UTC**）
   */
  public static ZoneId zoneOf(String raw) {
    String value = raw == null ? "" : raw.trim();
    if (value.isEmpty()) {
      return ZoneId.of("UTC");
    }
    try {
      return ZoneId.of(value);
    } catch (DateTimeException e) {
      throw new IllegalArgumentException("时区名不合法：" + value);
    }
  }

  // ---------------------------------------------------------------- 到期

  /**
   * 距截止还有几天。
   *
   * @param dueAt 截止时刻
   * @param now 当前时刻（**服务端时钟**，不信任客户端时间）
   * @return 天数（**负数 = 已逾期**）
   */
  public static int daysToDue(OffsetDateTime dueAt, OffsetDateTime now) {
    if (dueAt == null) {
      return 0;
    }
    return (int) ChronoUnit.DAYS.between(now.toLocalDate(), dueAt.toLocalDate());
  }

  /**
   * 是否已逾期（**服务端按当前时刻算**）。
   *
   * @param dueAt 截止时刻
   * @param now 当前时刻
   * @return 已逾期 = 当前时刻晚于截止时刻
   */
  public static boolean overdue(OffsetDateTime dueAt, OffsetDateTime now) {
    return dueAt != null && now.isAfter(dueAt);
  }
}
