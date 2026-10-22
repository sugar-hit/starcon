package com.starcon.channel;

import static com.starcon.channel.EprRegistrationRules.*;
import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 16.3.2.2 EPR/包装法 的判定规则单测（纯函数，**不碰数据库**）。
 *
 * <p>🔴 这组用例钉的是「算得出来」的那部分：申报期窗口、币种 / 国家码形状、
 * 判定原因是不是会阻断、人话口径。
 */
class EprRegistrationRulesTest {

  // ==================================================================
  // 申报期窗口
  // ==================================================================

  @Test
  @DisplayName("按月申报：起止月份相同 ⇒ 整月都是申报期")
  void monthlyWindow() {
    var window = filingWindow(CYCLE_MONTHLY, 3, 3, LocalDate.of(2026, 3, 10));
    assertThat(window.status()).isEqualTo(FILING_IN_PERIOD);
    assertThat(window.periodStart()).isEqualTo(LocalDate.of(2026, 3, 1));
    assertThat(window.periodEnd()).isEqualTo(LocalDate.of(2026, 3, 31));
    assertThat(window.daysUntilNext()).isZero();
  }

  @Test
  @DisplayName("按季申报：一季度内算在申报期内")
  void quarterlyWindowInside() {
    var window = filingWindow(CYCLE_QUARTERLY, 1, 3, LocalDate.of(2026, 2, 15));
    assertThat(window.status()).isEqualTo(FILING_IN_PERIOD);
    assertThat(window.periodStart()).isEqualTo(LocalDate.of(2026, 1, 1));
    assertThat(window.periodEnd()).isEqualTo(LocalDate.of(2026, 3, 31));
  }

  @Test
  @DisplayName("按季申报：季度之外 ⇒ 不在申报期内，并算出距下一期还有几天")
  void quarterlyWindowOutside() {
    var window = filingWindow(CYCLE_QUARTERLY, 1, 3, LocalDate.of(2026, 4, 10));
    assertThat(window.status()).isEqualTo(FILING_OUT_OF_PERIOD);
    assertThat(window.periodEnd()).isEqualTo(LocalDate.of(2026, 3, 31));
    // 2026-04-10 → 2027-01-01
    assertThat(window.daysUntilNext()).isEqualTo(266);
  }

  @Test
  @DisplayName("跨年申报期（11..2）算得对")
  void crossYearWindow() {
    var window = filingWindow(CYCLE_ANNUAL, 11, 2, LocalDate.of(2026, 12, 20));
    assertThat(window.status()).isEqualTo(FILING_IN_PERIOD);
    assertThat(window.periodStart()).isEqualTo(LocalDate.of(2026, 11, 1));
    assertThat(window.periodEnd()).isEqualTo(LocalDate.of(2027, 2, 28));
  }

  @Test
  @DisplayName("一次性申报没有周期 ⇒ NOT_RECURRING（不是「不在申报期内」）")
  void oneTimeHasNoPeriod() {
    var window = filingWindow(CYCLE_ONE_TIME, null, null, LocalDate.of(2026, 5, 5));
    assertThat(window.status()).isEqualTo(FILING_NOT_RECURRING);
    assertThat(window.periodStart()).isNull();
    assertThat(window.daysUntilNext()).isNull();
  }

  @Test
  @DisplayName("没记起止月份 ⇒ 判不了（UNKNOWN），不是「不在申报期内」")
  void unknownIsNotOutOfPeriod() {
    var window = filingWindow(CYCLE_QUARTERLY, null, null, LocalDate.of(2026, 5, 5));
    assertThat(window.status()).isEqualTo(FILING_UNKNOWN);
    assertThat(window.status()).isNotEqualTo(FILING_OUT_OF_PERIOD);
    assertThat(window.daysUntilNext()).isNull();
  }

  @Test
  @DisplayName("申报期窗口的人话串")
  void periodLabel() {
    var window = filingWindow(CYCLE_QUARTERLY, 1, 3, LocalDate.of(2026, 2, 15));
    assertThat(filingPeriodLabel(window)).isEqualTo("2026-01-01 ~ 2026-03-31");
  }

  // ==================================================================
  // 值域形状
  // ==================================================================

  @Test
  @DisplayName("币种必须是三个大写字母")
  void currencyShape() {
    assertThat(validCurrency("EUR")).isTrue();
    assertThat(validCurrency("USD")).isTrue();
    assertThat(validCurrency("eur")).isFalse();
    assertThat(validCurrency("EU")).isFalse();
    assertThat(validCurrency("EURO")).isFalse();
    assertThat(validCurrency("")).isFalse();
    assertThat(validCurrency(null)).isFalse();
  }

  @Test
  @DisplayName("国家码必须是两位大写字母")
  void countryShape() {
    assertThat(validCountryCode("DE")).isTrue();
    assertThat(validCountryCode("GB")).isTrue();
    assertThat(validCountryCode("de")).isFalse();
    assertThat(validCountryCode("DEU")).isFalse();
    assertThat(validCountryCode("")).isFalse();
    assertThat(validCountryCode(null)).isFalse();
  }

  // ==================================================================
  // 判定原因
  // ==================================================================

  @Test
  @DisplayName("🔴 会阻断刊登的原因与不会阻断的原因必须分清")
  void blockingReasons() {
    assertThat(blocking(REASON_NOT_REGISTERED)).isTrue();
    assertThat(blocking(REASON_EXPIRED)).isTrue();
    assertThat(blocking(REASON_VALIDITY_UNKNOWN)).isTrue();
    assertThat(blocking(REASON_NOT_EFFECTIVE_YET)).isTrue();
    assertThat(blocking(REASON_DISABLED)).isTrue();
    assertThat(blocking(REASON_CATEGORY_NOT_COVERED)).isTrue();

    // 🔴 「不适用」与「已覆盖」**不阻断** —— 把 NOT_APPLICABLE 当成未注册会把不该拦的全拦了
    assertThat(blocking(REASON_OK)).isFalse();
    assertThat(blocking(REASON_NOT_APPLICABLE)).isFalse();
    assertThat(blocking(REASON_EXEMPTED)).isFalse();
  }

  @Test
  @DisplayName("🔴 「判不了」也是阻断原因（不许默认放行）")
  void indeterminateBlocks() {
    assertThat(BLOCKING_REASONS).contains(REASON_VALIDITY_UNKNOWN, REASON_NOT_EFFECTIVE_YET);
  }

  @Test
  @DisplayName("制度词表就是四种，且与 DB 的 CHECK 一致")
  void regimeVocabulary() {
    assertThat(REGIME_TYPES)
        .containsExactlyInAnyOrder(
            "LUCID", "PACKAGING_REGISTRATION", "BATTERY", "WEEE");
  }

  @Test
  @DisplayName("申报周期词表：月度 / 季度 / 年度 / 一次性")
  void filingCycleVocabulary() {
    assertThat(FILING_CYCLES).containsExactlyInAnyOrder("MONTHLY", "QUARTERLY", "ANNUAL", "ONE_TIME");
  }

  @Test
  @DisplayName("有效期形态词表含第三态 UNKNOWN")
  void validityVocabulary() {
    assertThat(VALIDITY_KINDS).containsExactlyInAnyOrder("FIXED", "OPEN_ENDED", "UNKNOWN");
  }

  @Test
  @DisplayName("人话：判定原因与结论")
  void labels() {
    assertThat(reasonLabel(REASON_NOT_REGISTERED)).isEqualTo("未注册");
    assertThat(reasonLabel(REASON_VALIDITY_UNKNOWN)).isEqualTo("有效期判不了");
    assertThat(reasonLabel(REASON_EXEMPTED)).isEqualTo("不适用（已声明）");
    assertThat(conclusionLabel(CONCLUSION_INDETERMINATE)).isEqualTo("判不了（仍不放行）");
    assertThat(filingStatusLabel(FILING_UNKNOWN)).isEqualTo("没记申报月份（判不了）");
    assertThat(paidByLabel(PAID_BY_SERVICE_PROVIDER)).isEqualTo("服务商承担");
    assertThat(feeTypeLabel(FEE_PENALTY)).isEqualTo("罚款");
  }
}
