package com.starcon.crm;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 15.4.1.1 工单列表的<b>营业时间轴</b>。
 *
 * <p>蓝图 15-crm-and-service.md 的加粗句：
 *
 * <blockquote>
 * <b>SLA 计时需按营业时间而非自然时间</b>，否则夜间积压会造成误判。
 * </blockquote>
 *
 * <p>夜里 23:00 进来的工单，如果按自然时间算「48 小时回复」，它会在第二天下午
 * 就被判逾期 —— 而客服**根本还没上班**。于是「夜间积压」在报表上变成
 * 「一批客服集体超时」，绩效口径直接被带偏。
 *
 * <p>本类把「营业时间轴」显式化成一条可计算的轴：
 *
 * <ul>
 *   <li>轴的形状来自<b>租户自己的配置</b>（工作日区间 + 时区 + 节假日例外），
 *       读本服务自己的 {@code crm_service_business_hours} /
 *       {@code crm_service_business_holiday}，<b>不写死常量</b>；</li>
 *   <li>到期时刻 = <b>在轴上累加</b>（{@link #addBusinessMinutes}），
 *       <b>不是</b> {@code createdAt + N 小时}；</li>
 *   <li>剩余量 = <b>在轴上量</b>（{@link #businessSecondsBetween}）。</li>
 * </ul>
 *
 * <p>⚠️ <b>没有配置就没有轴</b>：{@link #configured()} 为 false 时
 * {@link #addBusinessMinutes} 返回 {@code null}，由服务层如实报
 * {@code NO_CALENDAR}（未配营业时间）——
 * <b>刻意不退回自然时间</b>：悄悄退回自然时间等于把加粗句要避免的误判又请回来，
 * 而且现场**看不出来**（数字照常有，只是全错了）。
 *
 * <p>⚠️ 工作日编号用 <b>ISO-8601</b>：1 = 周一 … 7 = 周日（与 {@code DayOfWeek.getValue()} 一致）。
 *
 * <p>本类是<b>纯计算</b>的：没有 Spring、没有数据库、没有时钟注入 ——
 * 「现在几点」由调用方以参数传进来，所以同一个轴的判定在任何时刻都可复现。
 */
public final class BusinessHoursCalendar {

  /**
   * 营业时间轴能向前看的最大天数。
   *
   * <p>⚠️ 必须有上限：一条「营业时间极窄 + 时限极长」的组合会让循环无界。
   * 触到上限时返回 {@code null}（而不是无限循环，也不是悄悄按自然时间算）。
   */
  static final int MAX_HORIZON_DAYS = 730;

  /** 轴上的一段营业区间（ISO-8601 工作日 + 左闭右开区间）。 */
  public record Window(int weekday, LocalTime start, LocalTime end) {
    public Window {
      if (weekday < 1 || weekday > 7) {
        throw new IllegalArgumentException("工作日必须是 ISO-8601 的 1..7（1=周一），收到 " + weekday);
      }
      if (start == null || end == null || !start.isBefore(end)) {
        throw new IllegalArgumentException("营业区间的起点必须早于终点");
      }
    }
  }

  private final String scopeKey;
  private final String timezone;
  private final ZoneId zone;
  private final List<Window> windows;
  private final Set<LocalDate> closedDates;
  private final boolean configured;

  private BusinessHoursCalendar(
      String scopeKey, String timezone, ZoneId zone, List<Window> windows, Set<LocalDate> closedDates) {
    this.scopeKey = scopeKey;
    this.timezone = timezone;
    this.zone = zone;
    this.windows = List.copyOf(windows);
    this.closedDates = Set.copyOf(closedDates);
    this.configured = !windows.isEmpty();
  }

  /**
   * 造一条轴。
   *
   * <p>⚠️ 时区非法时<b>不抛</b>，而是返回一条<b>未配置</b>的轴 ——
   * 库里存了一个坏时区不该让整个列表接口 500；它应该表现为
   * 「这个作用域的 SLA 算不出来」并带原因。
   */
  public static BusinessHoursCalendar of(
      String scopeKey, String timezone, List<Window> windows, Set<LocalDate> closedDates) {
    ZoneId zone;
    try {
      zone = ZoneId.of(timezone == null ? "UTC" : timezone.trim());
    } catch (RuntimeException e) {
      return new BusinessHoursCalendar(scopeKey, timezone, ZoneOffset.UTC, List.of(), Set.of());
    }
    List<Window> sorted = new ArrayList<>(windows == null ? List.of() : windows);
    sorted.sort(Comparator.comparingInt(Window::weekday).thenComparing(Window::start));
    return new BusinessHoursCalendar(
        scopeKey, timezone, zone, sorted, closedDates == null ? Set.of() : new HashSet<>(closedDates));
  }

  /** 这条轴有没有被配置过（没有 ⇒ SLA 算不出来）。 */
  public boolean configured() {
    return configured;
  }

  public String scopeKey() {
    return scopeKey;
  }

  public String timezone() {
    return timezone;
  }

  public List<Window> windows() {
    return windows;
  }

  public Set<LocalDate> closedDates() {
    return closedDates;
  }

  /**
   * 在营业时间轴上累加 {@code minutes} 分钟，返回到期时刻。
   *
   * <p>⚠️ 返回 {@code null} 的三种情形（都<b>不是</b>「按自然时间算」的借口）：
   *
   * <ol>
   *   <li>这条轴没配置过营业时段 ⇒ 算不出来；</li>
   *   <li>时长为负 ⇒ 口径非法；</li>
   *   <li>累加时长超出 {@link #MAX_HORIZON_DAYS} 天的轴长 ⇒ 触到上限。</li>
   * </ol>
   *
   * <p>⚠️ <b>跨天与跨时区</b>：起点按 {@code zone} 折算成当地时刻后逐段推进；
   * 夏令时切换当天用 {@code LocalDateTime.atZone} 落到实际时刻
   * （春季跳过的时刻前移、秋季重复的时刻取第一次）—— 两次判定可能相差一小时，
   * 但<b>同一口径下可复现</b>，这比「每次都差一点」好得多。
   */
  public OffsetDateTime addBusinessMinutes(OffsetDateTime start, long minutes) {
    if (!configured || start == null || minutes < 0) {
      return null;
    }
    LocalDateTime cursor = start.atZoneSameInstant(zone).toLocalDateTime();
    long remaining = minutes;
    for (int dayOffset = 0; dayOffset <= MAX_HORIZON_DAYS; dayOffset++) {
      LocalDate day = cursor.toLocalDate().plusDays(dayOffset);
      if (closedDates.contains(day)) {
        continue;
      }
      for (Window window : windowsOf(day)) {
        LocalDateTime from = LocalDateTime.of(day, window.start());
        LocalDateTime to = LocalDateTime.of(day, window.end());
        LocalDateTime segmentStart = from.isAfter(cursor) ? from : cursor;
        if (!to.isAfter(segmentStart)) {
          continue;
        }
        long segmentMinutes = Duration.between(segmentStart, to).toMinutes();
        if (remaining <= segmentMinutes) {
          return segmentStart.plusMinutes(remaining).atZone(zone).toOffsetDateTime();
        }
        remaining -= segmentMinutes;
      }
    }
    return null;
  }

  /**
   * 两个时刻之间<b>落在营业时间内</b>的秒数（不含非营业时间）。
   *
   * <p>⚠️ 负数区间按 0 处理（from 不晚于 to 才有意义）。
   */
  public long businessSecondsBetween(OffsetDateTime from, OffsetDateTime to) {
    if (!configured || from == null || to == null || !to.isAfter(from)) {
      return 0L;
    }
    LocalDateTime cursor = from.atZoneSameInstant(zone).toLocalDateTime();
    LocalDateTime end = to.atZoneSameInstant(zone).toLocalDateTime();
    long seconds = 0L;
    for (int dayOffset = 0; dayOffset <= MAX_HORIZON_DAYS && cursor.isBefore(end); dayOffset++) {
      LocalDate day = cursor.toLocalDate().plusDays(dayOffset);
      if (closedDates.contains(day)) {
        continue;
      }
      for (Window window : windowsOf(day)) {
        LocalDateTime windowStart = LocalDateTime.of(day, window.start());
        LocalDateTime windowEnd = LocalDateTime.of(day, window.end());
        LocalDateTime segmentStart = windowStart.isAfter(cursor) ? windowStart : cursor;
        LocalDateTime segmentEnd = windowEnd.isAfter(end) ? end : windowEnd;
        if (!segmentEnd.isAfter(segmentStart)) {
          continue;
        }
        seconds += Duration.between(segmentStart, segmentEnd).getSeconds();
      }
    }
    return seconds;
  }

  /** 自然时间秒数（<b>只作对照</b>：营业时间口径之外的剩余量）。 */
  public static long wallClockSecondsBetween(OffsetDateTime from, OffsetDateTime to) {
    if (from == null || to == null) {
      return 0L;
    }
    return Duration.between(from, to).getSeconds();
  }

  /** 某一天落在轴上的区间（按起点排序）。 */
  private List<Window> windowsOf(LocalDate day) {
    int weekday = day.getDayOfWeek().getValue();
    List<Window> result = new ArrayList<>(2);
    for (Window window : windows) {
      if (window.weekday() == weekday) {
        result.add(window);
      }
    }
    return result;
  }

  /** 按工作日分组的视图（供服务层读库时组装用）。 */
  public static Map<Integer, List<Window>> groupByWeekday(List<Window> windows) {
    Map<Integer, List<Window>> grouped = new HashMap<>();
    for (Window window : windows) {
      grouped.computeIfAbsent(window.weekday(), key -> new ArrayList<>()).add(window);
    }
    return grouped;
  }
}
