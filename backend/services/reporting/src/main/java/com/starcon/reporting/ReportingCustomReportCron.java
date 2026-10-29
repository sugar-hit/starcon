package com.starcon.reporting;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 13.6.1.1 订阅计划的 **cron 解析器**（5 段式：分 时 日 月 周）。
 *
 * <p>🔴 **为什么自己写而不是引一个库**：本模块要支持的是一个**明确写死的子集**，
 * 而不是「什么 cron 都能跑」。引一个通用库意味着用户可以写
 * {@code 0 0 1 1 *} 之外的任何东西，而**通用库的语义差异**（夏令时、秒级字段、
 * 非法值的宽容处理）会变成「同一串 cron 在不同机器上跑出不同的时刻」——
 * 那正是定时订阅最不能出的错。
 *
 * <p>支持的写法：
 *
 * <ul>
 *   <li>{@code *} —— 全匹配；</li>
 *   <li>{@code 5} —— 单值；</li>
 *   <li>{@code 1-5} —— 闭区间；</li>
 *   <li>{@code 0-30/5} 与「分钟字段全量步长」（斜杠前留空）—— 带步长；</li>
 *   <li>{@code 1,3,5} —— 列表（每一项都可以是上面四种）。</li>
 * </ul>
 *
 * <p>另外支持 {@code SUN..SAT}（星期）与 {@code JAN..DEC}（月份）的英文缩写。
 *
 * <p>⚠️ **不支持的写法一律报错**（不静默回落成「每天一次」）：
 * 用户以为每天收、实际收的频率完全不同，比直接报错糟得多。
 *
 * <p>⚠️ **时刻一律按 UTC 计算**：订阅计划是「几点发」，不是「哪个时区的几点发」。
 * 跨时区团队的差异由**报表自己的时间窗口**表达，不藏在 cron 里。
 */
public final class ReportingCustomReportCron {

  private ReportingCustomReportCron() {}

  /** 一年里的最大月数（cron 的月份字段）。 */
  private static final int MAX_MONTH = 12;

  /** 星期字段：0 与 7 都是周日（cron 的历史包袱）。 */
  private static final Set<String> DAY_NAMES =
      Set.of("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT");
  private static final Set<String> MONTH_NAMES =
      Set.of("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC");

  /**
   * 解析后的计划。
   *
   * @param minutes 命中���分钟
   * @param hours 命中的小时
   * @param daysOfMonth 命中的日
   * @param months 命中的月
   * @param daysOfWeek 命中的星期（0 = 周日）
   * @param dayOfMonthRestricted 「日」字段是不是**被限定过**（{@code *} 之外的写法）
   * @param dayOfWeekRestricted 「周」字段是不是**被限定过**
   */
  public record Schedule(
      Set<Integer> minutes,
      Set<Integer> hours,
      Set<Integer> daysOfMonth,
      Set<Integer> months,
      Set<Integer> daysOfWeek,
      boolean dayOfMonthRestricted,
      boolean dayOfWeekRestricted) {}

  /**
   * 解析 cron 表达式。
   *
   * @throws IllegalArgumentException 表达式不合法时抛出（消息写清差在哪）
   */
  public static Schedule parse(String expression) {
    if (expression == null || expression.isBlank()) {
      throw new IllegalArgumentException("cron 表达式不能为空");
    }
    var fields = expression.trim().split("\\s+");
    if (fields.length != 5) {
      throw new IllegalArgumentException(
          "cron 表达式必须是 5 段（分 时 日 月 周），收到的是 " + fields.length + " 段：「" + expression + "」");
    }
    var minutes = field(fields[0], 0, 59, "分钟");
    var hours = field(fields[1], 0, 23, "小时");
    var daysOfMonth = field(fields[2], 1, 31, "日");
    var months = field(fields[3], 1, MAX_MONTH, "月");
    var daysOfWeek = field(fields[4], 0, 7, "星期");
    // 7 与 0 都是周日 ⇒ 归一成 0，避免同一份计划算出两个时刻。
    var normalizedWeek = new LinkedHashSet<Integer>();
    for (var day : daysOfWeek) {
      normalizedWeek.add(day % 7);
    }
    return new Schedule(
        minutes,
        hours,
        daysOfMonth,
        months,
        normalizedWeek,
        !"*".equals(fields[2]),
        !"*".equals(fields[4]));
  }

  /**
   * 下一次命中的时刻（**严格晚于** {@code after}）。
   *
   * @param after 起点（会按 UTC 归一）
   * @return UTC 时刻
   * @throws IllegalArgumentException 接下来 366 天内没有任何命中时刻时抛出
   *     （例如 {@code 0 0 30 2 *} —— 2 月 30 日不存在）
   */
  public static OffsetDateTime nextAfter(Schedule schedule, OffsetDateTime after) {
    var lower = after.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
    var lowerLocal = lower.toLocalDateTime();
    var day = lower.toLocalDate();
    for (int offset = 0; offset <= 366; offset++) {
      if (matchesDay(schedule, day)) {
        for (var hour : schedule.hours()) {
          for (var minute : schedule.minutes()) {
            var candidate = day.atStartOfDay().plusHours(hour).plusMinutes(minute);
            if (candidate.isAfter(lowerLocal)) return candidate.atOffset(ZoneOffset.UTC);
          }
        }
      }
      day = day.plusDays(1);
    }
    throw new IllegalArgumentException(
        "该 cron 表达式在接下来 366 天内没有任何命中时刻（例如「2 月 30 日」这种不存在的日期）。");
  }

  /** 某一天是否命中（日 / 月 / 周三者的组合规则）。 */
  private static boolean matchesDay(Schedule schedule, LocalDate date) {
    if (!schedule.months().contains(date.getMonthValue())) return false;
    var monthHit = schedule.daysOfMonth().contains(date.getDayOfMonth());
    var weekHit = schedule.daysOfWeek().contains(date.getDayOfWeek().getValue() % 7);
    // cron 的历史语义：「日」与「周」**都被限定**时是「或」（任一命中即可）。
    if (schedule.dayOfMonthRestricted() && schedule.dayOfWeekRestricted()) {
      return monthHit || weekHit;
    }
    if (schedule.dayOfMonthRestricted()) return monthHit;
    if (schedule.dayOfWeekRestricted()) return weekHit;
    return true;
  }

  // ---------------------------------------------------------------------------
  // 字段解析
  // ---------------------------------------------------------------------------

  /** 解析一个 cron 字段。 */
  private static Set<Integer> field(String raw, int min, int max, String label) {
    var text = raw.trim().toUpperCase(Locale.ROOT);
    if (text.isEmpty()) {
      throw new IllegalArgumentException("cron 的「" + label + "」字段为空");
    }
    var out = new LinkedHashSet<Integer>();
    for (var part : text.split(",")) {
      var item = part.trim();
      if (item.isEmpty()) {
        throw new IllegalArgumentException("cron 的「" + label + "」字段里有空项：「" + raw + "」");
      }
      out.addAll(item(item, min, max, label, raw));
    }
    if (out.isEmpty()) {
      throw new IllegalArgumentException("cron 的「" + label + "」字段没有任何有效取值：「" + raw + "」");
    }
    return out;
  }

  /** 解析一个列表项（{@code *} / {@code n} / {@code a-b} / 带步长的写法）。 */
  private static Set<Integer> item(String item, int min, int max, String label, String raw) {
    var out = new LinkedHashSet<Integer>();
    var body = item;
    var step = 1;
    var slash = item.indexOf('/');
    if (slash >= 0) {
      body = item.substring(0, slash).trim();
      var stepText = item.substring(slash + 1).trim();
      try {
        step = Integer.parseInt(stepText);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(
            "cron 的「" + label + "」字段步长不是数字：「" + item + "」");
      }
      if (step <= 0) {
        throw new IllegalArgumentException(
            "cron 的「" + label + "」字段步长必须大于 0：「" + item + "」");
      }
    }
    if ("*".equals(body)) {
      for (var value = min; value <= max; value += step) out.add(value);
      return out;
    }
    var dash = body.indexOf('-');
    if (dash > 0) {
      var from = number(body.substring(0, dash), min, max, label, raw);
      var to = number(body.substring(dash + 1), min, max, label, raw);
      if (from > to) {
        throw new IllegalArgumentException(
            "cron 的「" + label + "」字段区间起点大于终点：「" + item + "」");
      }
      for (var value = from; value <= to; value += step) out.add(value);
      return out;
    }
    out.add(number(body, min, max, label, raw));
    return out;
  }

  /** 解析一个取值（支持英文缩写）。 */
  private static int number(String raw, int min, int max, String label, String original) {
    var text = raw.trim().toUpperCase(Locale.ROOT);
    if (text.isEmpty()) {
      throw new IllegalArgumentException("cron 的「" + label + "」字段里有空值：「" + original + "」");
    }
    if (DAY_NAMES.contains(text) || MONTH_NAMES.contains(text)) {
      // 英文缩写只在它该出现的字段里合法：星期名不能写进「月」字段，反之亦然。
      if (DAY_NAMES.contains(text) && max != 7) {
        throw new IllegalArgumentException(
            "cron 的「" + label + "」字段不接受星期缩写：「" + raw + "」");
      }
      if (MONTH_NAMES.contains(text) && !(min == 1 && max == MAX_MONTH)) {
        throw new IllegalArgumentException(
            "cron 的「" + label + "」字段不接受月份缩写：「" + raw + "」");
      }
      var names = DAY_NAMES.contains(text) ? List.of("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")
          : List.of("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC");
      var index = names.indexOf(text);
      if (DAY_NAMES.contains(text)) return index;
      return index + 1;
    }
    int value;
    try {
      value = Integer.parseInt(text);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          "cron 的「" + label + "」字段取值不是数字：「" + raw + "」");
    }
    if (value < min || value > max) {
      throw new IllegalArgumentException(
          "cron 的「" + label + "」字段取值 " + value + " 超出范围（" + min + "-" + max + "）。");
    }
    return value;
  }

  /** 星期字段的取值范围说明（错误消息里用）。 */
  public static String weekdayRange() {
    return "0-7（0 与 7 都是周日）或 " + String.join("/", DAY_NAMES);
  }

  /** 供测试断言用：某一天对应的 cron 星期值。 */
  public static int cronWeekday(DayOfWeek day) {
    return day.getValue() % 7;
  }
}
