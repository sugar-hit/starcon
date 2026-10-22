package com.starcon.crm;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 15.4.1.2 的<b>暂停感知 SLA 轴</b>。
 *
 * <p>蓝图 15.4.1.1 的加粗句「SLA 计时需按营业时间而非自然时间」在本模块继续成立，
 * 而本模块多了一条：**SLA 暂停 / 恢复必须作用在<b>同一条轴</b>上**。
 *
 * <blockquote>
 * 夜里 23:00 进来的工单，如果按自然时间算「48 小时回复」，它会在第二天下午
 * 就被判逾期 —— 而客服<b>根本还没上班</b>。
 * </blockquote>
 *
 * <p>本类做的事：把「到期的营业时间预算」沿**营业时间轴**消耗，
 * 并把**暂停区间里的营业时间**从消耗中<b>扣掉</b>。
 *
 * <ul>
 *   <li><b>到期时刻</b> = 在轴上累加（{@link BusinessHoursCalendar#addBusinessMinutes}），
 *       <b>不是</b> {@code createdAt + N 小时}；</li>
 *   <li><b>暂停</b>是一段墙钟区间 [paused_at, resumed_at)；
 *       落在区间里的**营业时间不计入消耗**（区间之外的自然时间本来就不计入），
 *       所以「暂停」= 「冻结 SLA 计时器」；</li>
 *   <li><b>没配营业时间</b> ⇒ 一律 {@code null} / NO_CALENDAR，
 *       <b>刻意不退回自然时间</b> —— 悄悄退回等于把误判请回来，而且现场看不出来。</li>
 * </ul>
 *
 * <p>本类是<b>纯计算</b>的：没有 Spring、没有数据库、没有时钟注入 ——
 * 「现在几点」由调用方以参数传进来，所以同一条轴的判定在任何时刻都可复现。
 */
public final class ServiceTicketProcessSlaAxis {

  /** 一段暂停区间（{@code resumedAt} 为 null ⇒ 还没恢复）。 */
  public record Pause(
      String slaTarget, OffsetDateTime pausedAt, OffsetDateTime resumedAt) {}

  /** 一条时限的目标（REPLY / RESOLVE）。 */
  public static final String REPLY = "REPLY";

  /** 一条时限的目标（RESOLVE）。 */
  public static final String RESOLVE = "RESOLVE";

  private final BusinessHoursCalendar calendar;
  private final List<Pause> pauses;

  public ServiceTicketProcessSlaAxis(BusinessHoursCalendar calendar, List<Pause> pauses) {
    this.calendar = calendar;
    List<Pause> sorted = new ArrayList<>(pauses == null ? List.of() : pauses);
    sorted.sort(Comparator.comparing(Pause::pausedAt, Comparator.nullsLast(Comparator.naturalOrder())));
    this.pauses = List.copyOf(sorted);
  }

  /**
   * 换一组暂停区间，<b>共用同一条营业时间轴</b>。
   *
   * <p>⚠️ 存在的理由：轴的形状（营业时段 + 时区 + 节假日）读一次就够，
   * 暂停区间是<b>逐张工单</b>的 —— 不共用轴的话，处理队列一页 50 张单
   * 就要把配置表读 50 次。
   */
  public ServiceTicketProcessSlaAxis withPauses(List<Pause> newPauses) {
    return new ServiceTicketProcessSlaAxis(calendar, newPauses);
  }

  /** 这条轴配过营业时间没有（没配 ⇒ 一切时限都算不出来）。 */
  public boolean configured() {
    return calendar.configured();
  }

  /** 作用域与时区（响应里如实写明用的是哪条口径）。 */
  public String scopeKey() {
    return calendar.scopeKey();
  }

  public String timezone() {
    return calendar.timezone();
  }

  /**
   * 这条时限当前是不是<b>暂停中</b>。
   *
   * <p>⚠️ 「暂停中」≠「已到期」：暂停期间 SLA **不消耗**，
   * 但到期时刻也**还没发生**（因为还有预算没花完）。
   */
  public boolean paused(String target) {
    for (Pause pause : pauses) {
      if (!covers(pause, target)) {
        continue;
      }
      if (pause.pausedAt() == null) {
        continue;
      }
      if (pause.resumedAt() == null) {
        return true;
      }
    }
    return false;
  }

  /** 暂停区间的条数（含已恢复的）。 */
  public long pauseCount() {
    return pauses.size();
  }

  /**
   * 到期时刻：在轴上累加 {@code budgetMinutes} 分钟，<b>跳过暂停区间</b>。
   *
   * <p>⚠️ 返回 {@code null} 的三种情形（都<b>不是</b>「按自然时间算」的借口）：
   *
   * <ol>
   *   <li>这条轴没配置过营业时段 ⇒ 算不出来；</li>
   *   <li>目前<b>暂停中</b> ⇒ 到期时刻**还没发生</b>（不是「已经逾期」）；</li>
   *   <li>累加时长超出轴长 ⇒ 触到上限。</li>
   * </ol>
   */
  public OffsetDateTime dueAt(OffsetDateTime start, long budgetMinutes, String target) {
    return dueAt(start, budgetMinutes, target, false);
  }

  /**
   * 到期时刻：<b>假装没有未恢复的暂停</b>（把它整条跳过）。
   *
   * <p>⚠️ 存在的理由：**「暂停中」不等于「没超期」**。
   * 一张在暂停之前就已经超期的工单，暂停**不能抹掉**已经发生的超期 ——
   * 否则「先拖到超期、再点一下暂停」就成了洗白超时的手法。
   * 所以判断「暂停中」还是「已超期」时，要拿「假如没有这次暂停」的到期时刻来比。
   *
   * <p>被跳过的未恢复暂停**既不消耗预算、也不推进游标** ——
   * 「当作它没发生过」意味着那段时间的营业时间照常计入。
   */
  public OffsetDateTime dueAtIgnoringOpenPauses(
      OffsetDateTime start, long budgetMinutes, String target) {
    return dueAt(start, budgetMinutes, target, true);
  }

  private OffsetDateTime dueAt(
      OffsetDateTime start, long budgetMinutes, String target, boolean ignoreOpenPauses) {
    if (!calendar.configured() || start == null || budgetMinutes < 0) {
      return null;
    }
    long remaining = budgetMinutes;
    OffsetDateTime cursor = start;
    for (Pause pause : pauses) {
      if (!covers(pause, target) || pause.pausedAt() == null) {
        continue;
      }
      if (pause.resumedAt() == null && ignoreOpenPauses) {
        // 当作没有这条暂停：它不消耗预算，也不推进游标。
        continue;
      }
      OffsetDateTime pauseStart = pause.pausedAt();
      if (!pauseStart.isAfter(cursor)) {
        // 这段暂停的起点已经在 cursor 之前。
        if (pause.resumedAt() == null) {
          // 还没恢复 ⇒ 计时器冻着 ⇒ 到期时刻尚未发生。
          return null;
        }
        if (!pause.resumedAt().isAfter(cursor)) {
          // 完全落在 cursor 之前 ⇒ 跳过。
          continue;
        }
        // cursor 落在暂停区间里 ⇒ 同样冻着。
        return null;
      }
      long available = calendar.businessSecondsBetween(cursor, pauseStart) / 60L;
      if (remaining <= available) {
        return calendar.addBusinessMinutes(cursor, remaining);
      }
      remaining -= available;
      if (pause.resumedAt() == null) {
        // 暂停至今未恢复 ⇒ 剩下的预算永远花不掉 ⇒ 尚未到期。
        return null;
      }
      cursor = pause.resumedAt();
    }
    return calendar.addBusinessMinutes(cursor, remaining);
  }

  /**
   * 已经消耗掉的<b>营业分钟数</b>（跳过暂停区间）。
   *
   * <p>⚠️ 自然时间落在营业时间之外的**本来就不计入**（这是轴的口径），
   * 暂停区间扣掉的是「营业时间里被冻住的那一段」。
   */
  public long consumedMinutes(OffsetDateTime start, OffsetDateTime at, String target) {
    if (!calendar.configured() || start == null || at == null || !at.isAfter(start)) {
      return 0L;
    }
    long total = calendar.businessSecondsBetween(start, at) / 60L;
    for (Pause pause : pauses) {
      if (!covers(pause, target) || pause.pausedAt() == null) {
        continue;
      }
      if (!pause.pausedAt().isBefore(at)) {
        // 暂停在 at 之后开始 ⇒ 还没影响到这一段。
        continue;
      }
      OffsetDateTime segmentStart =
          pause.pausedAt().isBefore(start) ? start : pause.pausedAt();
      OffsetDateTime segmentEnd =
          pause.resumedAt() == null ? at : (pause.resumedAt().isAfter(at) ? at : pause.resumedAt());
      if (segmentEnd.isAfter(segmentStart)) {
        total -= calendar.businessSecondsBetween(segmentStart, segmentEnd) / 60L;
      }
    }
    return Math.max(total, 0L);
  }

  /**
   * 剩余营业秒数（已超期为负数）。
   *
   * <p>⚠️ 与 {@link BusinessHoursCalendar#businessSecondsBetween} 同一口径：
   * 只有落在营业时间里的秒数才算数。
   */
  public long remainingSeconds(
      OffsetDateTime start, OffsetDateTime at, long budgetMinutes, String target) {
    if (!calendar.configured() || start == null || at == null) {
      return 0L;
    }
    long consumed = consumedMinutes(start, at, target);
    long remaining = budgetMinutes - consumed;
    return remaining * 60L;
  }

  /** 自然时间口径的剩余秒数（<b>只作对照</b>：页面不得拿它当考核口径）。 */
  public long wallClockRemainingSeconds(
      OffsetDateTime start, OffsetDateTime at, long budgetMinutes) {
    if (start == null || at == null) {
      return 0L;
    }
    return (budgetMinutes * 60L) - BusinessHoursCalendar.wallClockSecondsBetween(start, at);
  }

  /** 某段暂停是否作用在这条时限上。 */
  private static boolean covers(Pause pause, String target) {
    String pauseTarget = pause.slaTarget();
    if (pauseTarget == null) {
      return false;
    }
    return "BOTH".equals(pauseTarget) || pauseTarget.equals(target);
  }
}
