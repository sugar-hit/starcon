package com.starcon.channel;

import static com.starcon.channel.FilingCalendarRules.*;
import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 16.3.1.2 申报日历的<b>纯函数</b>单测。
 *
 * <p>🔴 这一组守的是本模块最贵的错误 —— <b>期算错 ⇒ 截止日算错 ⇒ 提醒算错 ⇒ 漏报</b>：
 *
 * <ol>
 *   <li><b>状态单调</b>：只许往前，不许倒退；</li>
 *   <li><b>申报期按「生效起始日 + 期起始日 + 周期」切</b>（不是拍脑袋的日历月 / 日历季）；</li>
 *   <li><b>截止日是当地时刻</b>（Berlin ≠ UTC —— 差几小时就足以错过一个截止日）；</li>
 *   <li><b>逾期判定按服务端时刻</b>。</li>
 * </ol>
 */
class FilingCalendarRulesTest {

  // ==================================================================
  // 状态：受控词表 + 单调
  // ==================================================================

  @Test
  @DisplayName("16.3.1.2 状态 rank：未开始 < 数据就绪 < 已提交 < 已确认")
  void statusRanks() {
    assertThat(statusRank("NOT_STARTED")).isEqualTo(0);
    assertThat(statusRank("DATA_READY")).isEqualTo(1);
    assertThat(statusRank("SUBMITTED")).isEqualTo(2);
    assertThat(statusRank("CONFIRMED")).isEqualTo(3);
    assertThat(statusRank("UNKNOWN")).isZero();
    assertThat(statusRank(null)).isZero();
  }

  @Test
  @DisplayName("16.3.1.2 状态只许前进：倒退一律不允许，同状态是幂等")
  void statusOnlyMovesForward() {
    assertThat(canAdvance("NOT_STARTED", "DATA_READY")).isTrue();
    assertThat(canAdvance("NOT_STARTED", "SUBMITTED")).isTrue();
    assertThat(canAdvance("DATA_READY", "SUBMITTED")).isTrue();
    assertThat(canAdvance("SUBMITTED", "CONFIRMED")).isTrue();

    assertThat(canAdvance("SUBMITTED", "DATA_READY")).as("已提交退回数据就绪 = 把漏报抹掉").isFalse();
    assertThat(canAdvance("CONFIRMED", "SUBMITTED")).as("已确认退回已提交 = 把漏报抹掉").isFalse();
    assertThat(canAdvance("CONFIRMED", "NOT_STARTED")).isFalse();
    assertThat(canAdvance("DATA_READY", "NOT_STARTED")).isFalse();

    assertThat(canAdvance("SUBMITTED", "SUBMITTED")).as("同状态是幂等，不算倒退").isTrue();
    assertThat(canAdvance("NOT_STARTED", "NOT_A_STATUS")).as("词表外的目标状态一律拒绝").isFalse();
  }

  @Test
  @DisplayName("16.3.1.2 人话标签：未知值回落成原值，不吞掉")
  void labels() {
    assertThat(statusLabel("NOT_STARTED")).isEqualTo("未开始");
    assertThat(statusLabel("DATA_READY")).isEqualTo("数据就绪");
    assertThat(statusLabel("SUBMITTED")).isEqualTo("已提交");
    assertThat(statusLabel("CONFIRMED")).isEqualTo("已确认");
    assertThat(statusLabel("SOMETHING")).isEqualTo("SOMETHING");
    assertThat(filingCycleLabel("MONTHLY")).isEqualTo("按月");
    assertThat(taxTypeLabel("VAT")).isEqualTo("增值税（VAT）");
    assertThat(reminderKindLabel("DUE_SOON")).isEqualTo("即将到期");
  }

  // ==================================================================
  // 申报期
  // ==================================================================

  @Test
  @DisplayName("16.3.1.2 申报期：按月 + 期起始日 1 号 ⇒ 日历月")
  void monthlyPeriod() {
    FilingCalendarRules.Period period =
        periodFor(LocalDate.of(2026, 3, 15), LocalDate.of(2026, 1, 1), 1, "MONTHLY");
    assertThat(period.start()).isEqualTo(LocalDate.of(2026, 3, 1));
    assertThat(period.end()).isEqualTo(LocalDate.of(2026, 3, 31));
  }

  @Test
  @DisplayName("16.3.1.2 申报期：按月 + 期起始日 15 号 ⇒ 15 号到次月 14 号")
  void monthlyPeriodWithMidMonthAnchor() {
    FilingCalendarRules.Period period =
        periodFor(LocalDate.of(2026, 3, 20), LocalDate.of(2026, 1, 1), 15, "MONTHLY");
    assertThat(period.start()).isEqualTo(LocalDate.of(2026, 3, 15));
    assertThat(period.end()).isEqualTo(LocalDate.of(2026, 4, 14));
  }

  @Test
  @DisplayName("16.3.1.2 申报期：参考日早于起始日 ⇒ 落在上一格")
  void monthlyPeriodBeforeAnchor() {
    FilingCalendarRules.Period period =
        periodFor(LocalDate.of(2026, 3, 10), LocalDate.of(2026, 1, 1), 15, "MONTHLY");
    assertThat(period.start()).isEqualTo(LocalDate.of(2026, 2, 15));
    assertThat(period.end()).isEqualTo(LocalDate.of(2026, 3, 14));
  }

  @Test
  @DisplayName("16.3.1.2 申报期：按季 + 起始日 1 号 ⇒ 日历季度（格子从生效起始日排）")
  void quarterlyPeriod() {
    FilingCalendarRules.Period period =
        periodFor(LocalDate.of(2026, 5, 15), LocalDate.of(2026, 1, 1), 1, "QUARTERLY");
    assertThat(period.start()).isEqualTo(LocalDate.of(2026, 4, 1));
    assertThat(period.end()).isEqualTo(LocalDate.of(2026, 6, 30));
  }

  @Test
  @DisplayName("16.3.1.2 申报期：按年 + 起始日 1 号 ⇒ 日历年")
  void annualPeriod() {
    FilingCalendarRules.Period period =
        periodFor(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 1, 1), 1, "ANNUAL");
    assertThat(period.start()).isEqualTo(LocalDate.of(2026, 1, 1));
    assertThat(period.end()).isEqualTo(LocalDate.of(2026, 12, 31));
  }

  @Test
  @DisplayName("16.3.1.2 申报期：配置生效日之前的期间不产出（那时还没有这项义务）")
  void periodBeforeEffectiveFromIsClamped() {
    FilingCalendarRules.Period period =
        periodFor(LocalDate.of(2026, 2, 10), LocalDate.of(2026, 2, 1), 15, "MONTHLY");
    assertThat(period.start()).isEqualTo(LocalDate.of(2026, 2, 1));
    assertThat(period.end()).isEqualTo(LocalDate.of(2026, 2, 28));
  }

  @Test
  @DisplayName("16.3.1.2 申报期：二月不因 29/30/31 号崩掉（期起始日上限 28）")
  void anchorDayNeverBreaksShortMonths() {
    for (int day : new int[] {1, 15, 28}) {
      FilingCalendarRules.Period period =
          periodFor(LocalDate.of(2026, 2, 28), LocalDate.of(2026, 1, 1), day, "MONTHLY");
      assertThat(period.start().getDayOfMonth()).isEqualTo(day);
      assertThat(period.end()).isAfterOrEqualTo(period.start());
    }
  }

  @Test
  @DisplayName("16.3.1.2 周期跨月数：月 1 / 季 3 / 半年 6 / 年 12")
  void cycleMonthCounts() {
    assertThat(cycleMonths("MONTHLY")).isEqualTo(1);
    assertThat(cycleMonths("QUARTERLY")).isEqualTo(3);
    assertThat(cycleMonths("SEMI_ANNUAL")).isEqualTo(6);
    assertThat(cycleMonths("ANNUAL")).isEqualTo(12);
    assertThat(cycleMonths(null)).isEqualTo(1);
  }

  // ==================================================================
  // 截止日
  // ==================================================================

  @Test
  @DisplayName("16.3.1.2 截止日是**当地时刻**：Berlin 冬季比 UTC 快一小时")
  void dueAtUsesTheConfiguredTimezone() {
    OffsetDateTime berlin =
        dueAt(LocalDate.of(2026, 1, 31), 10, 23, ZoneId.of("Europe/Berlin"));
    assertThat(berlin.getOffset()).isEqualTo(ZoneOffset.ofHours(1));
    assertThat(berlin.toLocalDate()).isEqualTo(LocalDate.of(2026, 2, 10));
    assertThat(berlin.getHour()).isEqualTo(23);

    OffsetDateTime utc = dueAt(LocalDate.of(2026, 1, 31), 10, 23, ZoneId.of("UTC"));
    assertThat(utc.getOffset()).isEqualTo(ZoneOffset.UTC);
    assertThat(utc.getHour()).isEqualTo(23);
    assertThat(berlin).isNotEqualTo(utc);
  }

  @Test
  @DisplayName("16.3.1.2 截止偏移 0 天 ⇒ 期末当天截止")
  void dueAtZeroOffset() {
    OffsetDateTime moment =
        dueAt(LocalDate.of(2026, 3, 31), 0, 23, ZoneId.of("Europe/Berlin"));
    assertThat(moment.toLocalDate()).isEqualTo(LocalDate.of(2026, 3, 31));
  }

  @Test
  @DisplayName("16.3.1.2 非法时区名被拒（不静默兜底成 UTC —— 那会让截止日差几小时）")
  void invalidTimezoneIsRejected() {
    assertThatThrownBy(() -> zoneOf("Mars/Olympus"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Mars/Olympus");
    assertThat(zoneOf("Europe/Berlin")).isEqualTo(ZoneId.of("Europe/Berlin"));
    assertThat(zoneOf(null)).isEqualTo(ZoneId.of("UTC"));
  }

  // ==================================================================
  // 到期
  // ==================================================================

  @Test
  @DisplayName("16.3.1.2 逾期判定按服务端时刻：负数天数 = 已逾期")
  void overdueUsesServerClock() {
    OffsetDateTime now = OffsetDateTime.of(2026, 3, 10, 12, 0, 0, 0, ZoneOffset.UTC);
    assertThat(overdue(now.plusDays(1), now)).isFalse();
    assertThat(daysToDue(now.plusDays(5), now)).isEqualTo(5);
    assertThat(overdue(now.minusHours(1), now)).isTrue();
    assertThat(daysToDue(now.minusDays(2), now)).isEqualTo(-2);
  }
}
