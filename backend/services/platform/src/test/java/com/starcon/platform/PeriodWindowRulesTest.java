package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;

import java.time.DayOfWeek;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * 「上一自然周」窗口口径的**纯函数**单元测试（不碰数据库）。
 *
 * <p>🔴 这条口径必须**与今天星期几无关**：周一、周二、…、周日，「上周」都必须是
 * **上一个完整自然周**（周一至周日），且整段落在过去。
 *
 * <p>为什么值得单独钉：原实现用的是 {@code TemporalAdjusters.previous(DayOfWeek.MONDAY)} ——
 * 它的语义是「**最近的一个已经过去的**那个星期几」，在今天不是周一时等于**本周**的周一，
 * 于是窗口终点（周日）落在**未来**：问「上周」得到的是含未来几天的本周。
 * 这个缺陷 6/7 的日子都发作，但集成测试只在「今天不是周一」时才红
 * ⇒ 等于有一半时间是绿的、缺陷被时钟藏起来。
 *
 * <p>⇒ 这里用**固定的日期**覆盖一整周七天，把口径钉死，不让时钟参与判定。
 */
class PeriodWindowRulesTest {

  @Test
  void 上周是上一个完整自然周_与今天星期几无关() {
    // 2026-11-02 是周一、11-08 是周日 —— 一周七天各取一天。
    for (int day = 2; day <= 8; day++) {
      var today = LocalDate.of(2026, 11, day);
      var monday = PeriodWindow.lastWeekMonday(today);

      assertThat(monday.getDayOfWeek()).as("%s 的上周起点必须是周一", today).isEqualTo(DayOfWeek.MONDAY);
      assertThat(monday).as("%s 的上周起点必须早于今天", today).isBefore(today);
      // 整段（周一至周日）必须落在过去 ⇒ 终点早于今天（不能问到还没发生的日子）。
      assertThat(monday.plusDays(6)).as("%s 的上周终点（周日）必须早于今天", today).isBefore(today);
      // 一周七天里，任意一天算出来的「上周」都是同一段（2026-10-26 ~ 2026-11-01）。
      assertThat(monday).as("%s 的上周起点", today).isEqualTo(LocalDate.of(2026, 10, 26));
      assertThat(monday.plusDays(6)).as("%s 的上周终点", today).isEqualTo(LocalDate.of(2026, 11, 1));
    }
  }

  @Test
  void 周一那天也退到上一周而不是本周() {
    // 2026-11-09 是周一 —— 最容易踩坑的一天：
    // 「最近的周一」= 今天自己，「上一周的周一」= 11-02。
    var monday = PeriodWindow.lastWeekMonday(LocalDate.of(2026, 11, 9));
    assertThat(monday).isEqualTo(LocalDate.of(2026, 11, 2));
    assertThat(monday.plusDays(6)).isEqualTo(LocalDate.of(2026, 11, 8));
  }

  @Test
  void 跨月跨年也不串() {
    // 2027-01-01 是周五：它所在的那一周是 2026-12-28 ~ 2027-01-03，
    // 所以「上周」= 2026-12-21 ~ 2026-12-27（不是 12-28 那一周）。
    var monday = PeriodWindow.lastWeekMonday(LocalDate.of(2027, 1, 1));
    assertThat(monday).isEqualTo(LocalDate.of(2026, 12, 21));
    assertThat(monday.plusDays(6)).isEqualTo(LocalDate.of(2026, 12, 27));
  }
}
