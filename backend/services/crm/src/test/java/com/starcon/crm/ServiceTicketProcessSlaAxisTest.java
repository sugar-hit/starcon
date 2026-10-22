package com.starcon.crm;

import static org.assertj.core.api.Assertions.*;

import com.starcon.crm.ServiceTicketProcessSlaAxis.Pause;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 15.4.1.2 的<b>暂停感知 SLA 轴</b>的纯逻辑守卫。
 *
 * <p>本类<b>不碰数据库、不起容器</b> —— 它守的是「SLA 计时沿营业时间轴、
 * 且暂停扣的是营业时间」这条口径本身。
 *
 * <p>🔴 蓝图 15.4.1.1 的加粗句「SLA 计时需按营业时间而非自然时间」在本模块继续成立，
 * 而且多了一条：**暂停必须作用在同一条轴上**。
 */
class ServiceTicketProcessSlaAxisTest {

  /** 周一 09:00（+08:00）—— 营业时间轴的测试基准。 */
  private static final OffsetDateTime MON_09 =
      OffsetDateTime.parse("2026-06-01T09:00:00+08:00");

  /** 一条「周一到周五 09:00–12:00、13:00–18:00」的轴（午休要能表达出来）。 */
  private static BusinessHoursCalendar workingDays() {
    List<BusinessHoursCalendar.Window> windows = new java.util.ArrayList<>();
    for (int weekday = 1; weekday <= 5; weekday++) {
      windows.add(new BusinessHoursCalendar.Window(weekday, LocalTime.of(9, 0), LocalTime.of(12, 0)));
      windows.add(
          new BusinessHoursCalendar.Window(weekday, LocalTime.of(13, 0), LocalTime.of(18, 0)));
    }
    return BusinessHoursCalendar.of("DEFAULT", "Asia/Shanghai", windows, Set.of());
  }

  /** 只有周一 09:00–18:00 的一天的轴（跨夜测试用）。 */
  private static BusinessHoursCalendar mondayOnly() {
    return BusinessHoursCalendar.of(
        "DEFAULT",
        "Asia/Shanghai",
        List.of(new BusinessHoursCalendar.Window(1, LocalTime.of(9, 0), LocalTime.of(18, 0))),
        Set.of());
  }

  @Test
  void 没配营业时间就算不出来_不退回自然时间() {
    BusinessHoursCalendar empty =
        BusinessHoursCalendar.of("DEFAULT", "Asia/Shanghai", List.of(), Set.of());
    ServiceTicketProcessSlaAxis axis = new ServiceTicketProcessSlaAxis(empty, List.of());

    assertThat(axis.configured()).isFalse();
    // 🔴 **刻意不退回自然时间**：4 小时后到期那种「看起来很正常的数字」正是要避免的误判。
    assertThat(axis.dueAt(MON_09, 240L, ServiceTicketProcessSlaAxis.REPLY)).isNull();
    assertThat(axis.consumedMinutes(MON_09, MON_09.plusHours(4), ServiceTicketProcessSlaAxis.REPLY))
        .isZero();
  }

  @Test
  void 到期时刻在营业时间轴上累加_不是_createdAt加N小时() {
    ServiceTicketProcessSlaAxis axis = new ServiceTicketProcessSlaAxis(workingDays(), List.of());

    // 09:00 起 4 小时营业时间 = 09:00-12:00（3h）+ 13:00-14:00（1h）⇒ 14:00。
    assertThat(axis.dueAt(MON_09, 240L, ServiceTicketProcessSlaAxis.REPLY))
        .isEqualTo(OffsetDateTime.parse("2026-06-01T14:00:00+08:00"));
    // 自然时间口径（只作对照）会是 13:00 —— 午休那一小时被算进去了。
    assertThat(MON_09.plusHours(4)).isBefore(axis.dueAt(MON_09, 240L, ServiceTicketProcessSlaAxis.REPLY));
  }

  @Test
  void 夜间积压不计时_跨夜后第二天上班才继续() {
    ServiceTicketProcessSlaAxis axis = new ServiceTicketProcessSlaAxis(mondayOnly(), List.of());
    // 周一 09:00 + 4 小时营业时间 = 周一 13:00（同一天内）。
    assertThat(axis.dueAt(MON_09, 240L, ServiceTicketProcessSlaAxis.REPLY))
        .isEqualTo(OffsetDateTime.parse("2026-06-01T13:00:00+08:00"));
    // 周一 23:00 进来的单：那天营业时段已经过了 ⇒ 沿轴走到**下周一** 09:00 再累加 4 小时。
    // 🔴 关键：不是「周一 23:00 + 4 小时 = 周二 03:00」（自然时间口径）——
    //   那样夜间积压会在第二天上班前就被判逾期。
    OffsetDateTime nightDue =
        axis.dueAt(
            OffsetDateTime.parse("2026-06-01T23:00:00+08:00"),
            240L,
            ServiceTicketProcessSlaAxis.REPLY);
    assertThat(nightDue).isEqualTo(OffsetDateTime.parse("2026-06-08T13:00:00+08:00"));
    assertThat(nightDue).isAfter(OffsetDateTime.parse("2026-06-02T03:00:00+08:00"));
  }

  @Test
  void 节假日整日不计时() {
    BusinessHoursCalendar calendar =
        BusinessHoursCalendar.of(
            "DEFAULT",
            "Asia/Shanghai",
            List.of(new BusinessHoursCalendar.Window(1, LocalTime.of(9, 0), LocalTime.of(18, 0))),
            Set.of(LocalDate.parse("2026-06-08")));
    ServiceTicketProcessSlaAxis axis = new ServiceTicketProcessSlaAxis(calendar, List.of());

    // 2026-06-08 是周一（假日）：那天整日不计时 ⇒ 到期时刻落在下周一。
    assertThat(
            axis.dueAt(
                OffsetDateTime.parse("2026-06-08T09:00:00+08:00"),
                60L,
                ServiceTicketProcessSlaAxis.REPLY))
        .isEqualTo(OffsetDateTime.parse("2026-06-15T10:00:00+08:00"));
  }

  @Test
  void 暂停扣的是营业时间_到期时刻因此推后() {
    ServiceTicketProcessSlaAxis axis =
        new ServiceTicketProcessSlaAxis(
            workingDays(),
            List.of(
                new Pause(
                    "BOTH",
                    OffsetDateTime.parse("2026-06-01T10:00:00+08:00"),
                    OffsetDateTime.parse("2026-06-01T11:00:00+08:00"))));

    // 不暂停 ⇒ 14:00 到期；暂停了 10:00-11:00 这 **1 个营业小时** ⇒ 15:00 到期。
    assertThat(axis.dueAt(MON_09, 240L, ServiceTicketProcessSlaAxis.REPLY))
        .isEqualTo(OffsetDateTime.parse("2026-06-01T15:00:00+08:00"));
  }

  @Test
  void 暂停期间不消耗营业时间_剩余量被冻住() {
    OffsetDateTime at = OffsetDateTime.parse("2026-06-01T11:00:00+08:00");
    ServiceTicketProcessSlaAxis axis =
        new ServiceTicketProcessSlaAxis(
            workingDays(),
            List.of(
                new Pause(
                    "BOTH",
                    OffsetDateTime.parse("2026-06-01T10:00:00+08:00"),
                    OffsetDateTime.parse("2026-06-01T11:00:00+08:00"))));

    // 09:00 → 11:00 之间有 2 个营业小时（09:00-10:00、10:00-11:00），
    // 但其中 10:00-11:00 被暂停扣掉 ⇒ 只消耗 1 个营业小时。
    assertThat(axis.consumedMinutes(MON_09, at, ServiceTicketProcessSlaAxis.REPLY)).isEqualTo(60L);
    // 4 小时预算 ⇒ 还剩 3 个营业小时（不是 2 个）。
    assertThat(axis.remainingSeconds(MON_09, at, 240L, ServiceTicketProcessSlaAxis.REPLY))
        .isEqualTo(3 * 3600L);
  }

  @Test
  void 暂停尚未恢复_到期时刻还没发生() {
    ServiceTicketProcessSlaAxis axis =
        new ServiceTicketProcessSlaAxis(
            workingDays(),
            List.of(
                new Pause(
                    "BOTH", OffsetDateTime.parse("2026-06-01T10:00:00+08:00"), null)));

    assertThat(axis.paused(ServiceTicketProcessSlaAxis.REPLY)).isTrue();
    assertThat(axis.paused(ServiceTicketProcessSlaAxis.RESOLVE)).isTrue();
    // ⚠️ 未恢复 ⇒ 剩下的预算永远花不掉 ⇒ 到期时刻**还没发生**（不是「已经逾期」）。
    assertThat(axis.dueAt(MON_09, 240L, ServiceTicketProcessSlaAxis.REPLY)).isNull();
  }

  @Test
  void 暂停只作用在被指定的那条时限上() {
    OffsetDateTime pauseStart = OffsetDateTime.parse("2026-06-01T10:00:00+08:00");
    OffsetDateTime pauseEnd = OffsetDateTime.parse("2026-06-01T11:00:00+08:00");
    // 只暂停回复时限（且**还没恢复** ⇒ 当前处于暂停中）。
    ServiceTicketProcessSlaAxis open =
        new ServiceTicketProcessSlaAxis(
            workingDays(), List.of(new Pause("REPLY", pauseStart, null)));
    assertThat(open.paused(ServiceTicketProcessSlaAxis.REPLY)).isTrue();
    assertThat(open.paused(ServiceTicketProcessSlaAxis.RESOLVE)).isFalse();

    // 已恢复的暂停：不再处于「暂停中」，但**消耗**照样被扣掉。
    ServiceTicketProcessSlaAxis closed =
        new ServiceTicketProcessSlaAxis(
            workingDays(), List.of(new Pause("REPLY", pauseStart, pauseEnd)));
    assertThat(closed.paused(ServiceTicketProcessSlaAxis.REPLY)).isFalse();
    assertThat(closed.paused(ServiceTicketProcessSlaAxis.RESOLVE)).isFalse();
    // 只暂停回复时限 ⇒ 回复时限推后 1 个营业小时，处理时限不动。
    assertThat(closed.dueAt(MON_09, 240L, ServiceTicketProcessSlaAxis.REPLY))
        .isEqualTo(OffsetDateTime.parse("2026-06-01T15:00:00+08:00"));
    assertThat(closed.dueAt(MON_09, 240L, ServiceTicketProcessSlaAxis.RESOLVE))
        .isEqualTo(OffsetDateTime.parse("2026-06-01T14:00:00+08:00"));
  }

  @Test
  void 自然时间口径只作对照_与营业时间口径不是一回事() {
    ServiceTicketProcessSlaAxis axis = new ServiceTicketProcessSlaAxis(workingDays(), List.of());
    OffsetDateTime at = OffsetDateTime.parse("2026-06-01T12:30:00+08:00");

    // 营业时间口径：09:00 → 12:30 消耗 3 个营业小时（午休 12:00-13:00 不算）。
    assertThat(axis.consumedMinutes(MON_09, at, ServiceTicketProcessSlaAxis.REPLY)).isEqualTo(180L);
    // 自然时间口径：3.5 小时（对照用，页面不得拿它当考核口径）。
    assertThat(axis.wallClockRemainingSeconds(MON_09, at, 240L))
        .isEqualTo((240L * 60L) - 3 * 3600L - 1800L);
  }
}
