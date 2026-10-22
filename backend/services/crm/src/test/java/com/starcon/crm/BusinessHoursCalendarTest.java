package com.starcon.crm;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 15.4.1.1 营业时间轴的单元测试（<b>纯计算</b>：不碰数据库、不起容器）。
 *
 * <p>蓝图加粗句「SLA 计时需按营业时间而非自然时间，否则夜间积压会造成误判」
 * 的判据全部落在这里：
 *
 * <ul>
 *   <li>到期时刻在<b>营业时间轴</b>上累加（不是 createdAt + N 小时）；</li>
 *   <li>夜间 / 周末 / 节假日<b>不计时</b>；</li>
 *   <li>没配置营业时间 ⇒ <b>算不出来</b>（返回 null），<b>不退回自然时间</b>。</li>
 * </ul>
 */
class BusinessHoursCalendarTest {

  /** 周一至周五 09:00-12:00、13:00-18:00（东八区）。 */
  private static BusinessHoursCalendar office() {
    List<BusinessHoursCalendar.Window> windows = new java.util.ArrayList<>();
    for (int weekday = 1; weekday <= 5; weekday++) {
      windows.add(new BusinessHoursCalendar.Window(weekday, LocalTime.of(9, 0), LocalTime.of(12, 0)));
      windows.add(new BusinessHoursCalendar.Window(weekday, LocalTime.of(13, 0), LocalTime.of(18, 0)));
    }
    return BusinessHoursCalendar.of("DEFAULT", "Asia/Shanghai", windows, Set.of());
  }

  @Test
  @DisplayName("周末不计时：周五 17:00 起 2 小时营业时间 ⇒ 下周一 10:00")
  void 周末不计时() {
    BusinessHoursCalendar calendar = office();
    OffsetDateTime friday = OffsetDateTime.parse("2026-06-05T17:00:00+08:00");
    // 自然时间口径会是周六 15:00；营业时间口径必须跨过整个周末。
    OffsetDateTime due = calendar.addBusinessMinutes(friday, 120);
    assertThat(due).isNotNull();
    assertThat(due.toLocalDate()).isEqualTo(LocalDate.parse("2026-06-08"));
    assertThat(due.toLocalTime()).isEqualTo(LocalTime.of(10, 0));
  }

  @Test
  @DisplayName("夜间积压不误判：周五 23:00 起 8 小时营业时间 ⇒ 下周一 11:00")
  void 夜间积压不误判() {
    BusinessHoursCalendar calendar = office();
    OffsetDateTime fridayNight = OffsetDateTime.parse("2026-06-05T23:00:00+08:00");
    OffsetDateTime due = calendar.addBusinessMinutes(fridayNight, 8 * 60L);
    assertThat(due).isNotNull();
    // 自然时间口径：周六 07:00（那时客服还没上班）。
    // 营业时间口径：周五 23:00 之后没有营业时段 ⇒ 周一 09:00 起算 8 小时
    //   09:00-12:00 = 3h，13:00-18:00 = 5h ⇒ 恰好在周一 18:00 用完。
    assertThat(due.toLocalDate()).isEqualTo(LocalDate.parse("2026-06-08"));
    assertThat(due.toLocalTime()).isEqualTo(LocalTime.of(18, 0));
    // 自然时间口径会落在周六 —— 营业时间口径必须**不在**周六。
    assertThat(due.getDayOfWeek().getValue()).isEqualTo(1);
  }

  @Test
  @DisplayName("午休不计时：11:30 起 1 小时营业时间 ⇒ 当天 13:30")
  void 午休不计时() {
    BusinessHoursCalendar calendar = office();
    OffsetDateTime noon = OffsetDateTime.parse("2026-06-01T11:30:00+08:00");
    OffsetDateTime due = calendar.addBusinessMinutes(noon, 60);
    assertThat(due).isNotNull();
    assertThat(due.toLocalDate()).isEqualTo(LocalDate.parse("2026-06-01"));
    assertThat(due.toLocalTime()).isEqualTo(LocalTime.of(13, 30));
  }

  @Test
  @DisplayName("节假日整日不计时：节前最后一个工作日下午起 2 小时 ⇒ 节后第一个工作日 10:00")
  void 节假日不计时() {
    List<BusinessHoursCalendar.Window> windows = new java.util.ArrayList<>();
    for (int weekday = 1; weekday <= 5; weekday++) {
      windows.add(new BusinessHoursCalendar.Window(weekday, LocalTime.of(9, 0), LocalTime.of(18, 0)));
    }
    // 2026-06-08（周一）是节假日。
    BusinessHoursCalendar calendar =
        BusinessHoursCalendar.of(
            "DEFAULT", "Asia/Shanghai", windows, Set.of(LocalDate.parse("2026-06-08")));
    OffsetDateTime before = OffsetDateTime.parse("2026-06-05T17:00:00+08:00");
    OffsetDateTime due = calendar.addBusinessMinutes(before, 120);
    assertThat(due).isNotNull();
    // 周五 17:00-18:00 记 1 小时，剩下 1 小时落到节后第一个工作日（周二）09:00-10:00。
    assertThat(due.toLocalDate()).isEqualTo(LocalDate.parse("2026-06-09"));
    assertThat(due.toLocalTime()).isEqualTo(LocalTime.of(10, 0));
  }

  @Test
  @DisplayName("没配置营业时间 ⇒ 算不出来（null），绝不退回自然时间")
  void 没配置就算不出来() {
    BusinessHoursCalendar empty =
        BusinessHoursCalendar.of("DEFAULT", "Asia/Shanghai", List.of(), Set.of());
    assertThat(empty.configured()).isFalse();
    OffsetDateTime start = OffsetDateTime.parse("2026-06-01T10:00:00+08:00");
    assertThat(empty.addBusinessMinutes(start, 60)).isNull();
    assertThat(empty.businessSecondsBetween(start, start.plusHours(8))).isZero();
  }

  @Test
  @DisplayName("剩余量只算营业时间：跨越周末的 48 自然小时里只有 10 小时是营业时间")
  void 剩余量只算营业时间() {
    BusinessHoursCalendar calendar = office();
    OffsetDateTime friday = OffsetDateTime.parse("2026-06-05T16:00:00+08:00");
    OffsetDateTime monday = OffsetDateTime.parse("2026-06-08T10:00:00+08:00");
    // 周五 16:00-18:00 = 2 小时；周六周日 0；周一 09:00-10:00 = 1 小时 ⇒ 共 3 小时。
    assertThat(calendar.businessSecondsBetween(friday, monday)).isEqualTo(3 * 3600L);
    // 自然时间对照：66 小时（周五 16:00 → 下周一 10:00，跨了整个周末）。
    assertThat(BusinessHoursCalendar.wallClockSecondsBetween(friday, monday)).isEqualTo(66 * 3600L);
  }

  @Test
  @DisplayName("时区不静默偏移：同一绝对时刻在不同作用域轴上的到期时刻按各自时区算")
  void 时区不静默偏移() {
    List<BusinessHoursCalendar.Window> windows =
        List.of(new BusinessHoursCalendar.Window(1, LocalTime.of(9, 0), LocalTime.of(18, 0)));
    BusinessHoursCalendar shanghai =
        BusinessHoursCalendar.of("DEFAULT", "Asia/Shanghai", windows, Set.of());
    BusinessHoursCalendar losAngeles =
        BusinessHoursCalendar.of("SHOP:1", "America/Los_Angeles", windows, Set.of());
    // 2026-06-01T00:00:00Z = 上海 08:00 = 洛杉矶 17:00（前一日）。
    OffsetDateTime start = OffsetDateTime.parse("2026-06-01T00:00:00Z");
    // 上海：08:00 还没到 09:00 ⇒ 1 小时后到 10:00 ⇒ UTC 02:00。
    assertThat(shanghai.addBusinessMinutes(start, 60).toInstant())
        .isEqualTo(OffsetDateTime.parse("2026-06-01T02:00:00Z").toInstant());
    // 洛杉矶：当地周日 17:00（周日不营业）⇒ 下一个营业日周一 09:00 起算 1 小时 ⇒ 当地 10:00。
    OffsetDateTime la = losAngeles.addBusinessMinutes(start, 60);
    assertThat(la).isNotNull();
    assertThat(la.toLocalDate()).isEqualTo(LocalDate.parse("2026-06-01"));
    assertThat(la.toLocalTime()).isEqualTo(LocalTime.of(10, 0));
  }

  @Test
  @DisplayName("非法时区不炸轴：算不出来，而不是让整个列表 500")
  void 非法时区不炸轴() {
    BusinessHoursCalendar broken =
        BusinessHoursCalendar.of(
            "DEFAULT",
            "Not/AZone",
            List.of(new BusinessHoursCalendar.Window(1, LocalTime.of(9, 0), LocalTime.of(18, 0))),
            Set.of());
    assertThat(broken.configured()).isFalse();
    assertThat(broken.addBusinessMinutes(OffsetDateTime.parse("2026-06-01T10:00:00+08:00"), 60))
        .isNull();
  }

  @Test
  @DisplayName("营业区间非法（起点不早于终点）当场拒绝")
  void 营业区间非法当场拒绝() {
    assertThatThrownBy(
            () ->
                new BusinessHoursCalendar.Window(
                    1, LocalTime.of(18, 0), LocalTime.of(9, 0)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new BusinessHoursCalendar.Window(8, LocalTime.of(9, 0), LocalTime.of(18, 0)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
