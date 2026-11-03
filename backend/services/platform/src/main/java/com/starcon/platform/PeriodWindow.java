package com.starcon.platform;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * 相对时间窗口（上周 / 本月 / 最近 N 天）的**唯一口径**。
 *
 * <p>18.4.1.1 助手与 18.4.1.2 智能查询都要把问句里的「上周」解析成同一个窗口，
 * 而两边的窗口一旦算出不同的起止日，问出来的数就不可比 —— ⇒ 口径收敛到这一个类里，
 * 两边共用同一个方法，不各写一份。
 *
 * <p>🔴 **上一自然周 ≠ 最近的周一**：今天不是周一时，「最近的周一」落在**本周**，
 * 于是窗口的终点（周日）会在**未来**，问「上周」却得到本周（含还没发生的日子）。
 * 这不是理论风险 —— 2026-11-03（周二）就因此让
 * {@code AssistantIntegrationTest.追问换成上周只替换被显式修改的时间维度} 变红：
 * 期望上周（10-26 ~ 11-01），实得本周（11-02 ~ 11-08）。
 *
 * <p>⇒ 口径固定为：**先退一周，再对齐到那一周的周一**。这样无论今天星期几，
 * 得到的都是**上一个完整自然周**（周一至周日），且必然整段落在过去。
 */
final class PeriodWindow {

  private PeriodWindow() {}

  /**
   * 上一自然周（周一至周日）的**窗口起点**：上一个自然周的周一。
   *
   * <p>⚠️ 不能用 {@code TemporalAdjusters.previous(DayOfWeek.MONDAY)}：它的语义是
   * 「**最近的一个已经过去的**那个星期几」（今天不是周一时 = 本周的周一），
   * 不是「上一周的那个星期几」。
   *
   * @param today 口径日（调用方从 **DB 时钟**取，避免跨时钟域）
   * @return 上一个自然周的周一
   */
  static LocalDate lastWeekMonday(LocalDate today) {
    return today.minusWeeks(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
  }
}
