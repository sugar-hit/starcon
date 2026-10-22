package com.starcon.channel;

import static com.starcon.channel.PlatformViolationRules.*;
import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 16.1.2.1 违规与处罚的<b>值域 / 状态机 / 金额</b>纯逻辑单测（无 IO）。
 *
 * <p>🔴 这组用例守的是本模块最容易做丢的四件事：
 *
 * <ol>
 *   <li><b>处罚措施是受控值域</b>：警告 / 下架 / 限制销售 / 扣款 / 停号，**恰好五种**，不多不少；</li>
 *   <li><b>处理状态与整改状态都是显式状态机</b>：非法转换在应用层就被识别出来；</li>
 *   <li><b>影响档位是派生的</b>：由处罚措施集合算出，停号 &gt; 下架 &gt; 限制销售/扣款 &gt; 警告；</li>
 *   <li><b>不同币种不得直接相加</b>：{@link Money#plus(Money)} 必须抛异常，而不是给出一个
 *       看起来合理的和。</li>
 * </ol>
 */
class PlatformViolationRulesTest {

  // ==================================================================
  // 值域
  // ==================================================================

  @Test
  @DisplayName("16.1.2.1 处罚措施值域 = 警告 / 下架 / 限制销售 / 扣款 / 停号（恰好五种）")
  void measuresAreTheFiveBlueprintOnes() {
    assertThat(MEASURES)
        .containsExactly("WARNING", "DELIST", "RESTRICT_SALES", "DEDUCTION", "SUSPEND_ACCOUNT");
    assertThat(MEASURE_LABELS.get("WARNING")).isEqualTo("警告");
    assertThat(MEASURE_LABELS.get("DELIST")).isEqualTo("下架");
    assertThat(MEASURE_LABELS.get("RESTRICT_SALES")).isEqualTo("限制销售");
    assertThat(MEASURE_LABELS.get("DEDUCTION")).isEqualTo("扣款");
    assertThat(MEASURE_LABELS.get("SUSPEND_ACCOUNT")).isEqualTo("停号");
    assertThat(MEASURES).hasSize(5);
  }

  @Test
  @DisplayName("16.1.2.1 违规类型值域非空且与 DB 的 CHECK 一致（受控值域）")
  void violationTypesAreControlled() {
    assertThat(VIOLATION_TYPES).isNotEmpty();
    assertThat(VIOLATION_TYPES).doesNotHaveDuplicates();
    for (String code : VIOLATION_TYPES) {
      assertThat(knownViolationType(code)).as("%s 必须在自己的值域里", code).isTrue();
      assertThat(VIOLATION_TYPE_LABELS.get(code)).as("%s 必须有人话名", code).isNotBlank();
    }
    assertThat(knownViolationType("NOT_A_TYPE")).isFalse();
    assertThat(knownViolationType(null)).isFalse();
  }

  @Test
  @DisplayName("16.1.2.1 处理状态值域 = 未处理 / 处理中 / 已整改 / 申诉中 / 已关闭")
  void statusesAreControlled() {
    assertThat(STATUSES)
        .containsExactly("PENDING", "IN_PROGRESS", "RECTIFIED", "APPEALING", "CLOSED");
    assertThat(STATUS_LABELS.get("PENDING")).isEqualTo("未处理");
    assertThat(STATUS_LABELS.get("IN_PROGRESS")).isEqualTo("处理中");
    assertThat(STATUS_LABELS.get("RECTIFIED")).isEqualTo("已整改");
    assertThat(STATUS_LABELS.get("APPEALING")).isEqualTo("申诉中");
    assertThat(STATUS_LABELS.get("CLOSED")).isEqualTo("已关闭");
  }

  // ==================================================================
  // 状态机
  // ==================================================================

  @Test
  @DisplayName("16.1.2.1 处理状态机：未处理 → 处理中 → 已整改 / 申诉中 / 已关闭")
  void violationStatusMachine() {
    assertThat(canTransition("PENDING", "IN_PROGRESS")).isTrue();
    assertThat(canTransition("IN_PROGRESS", "RECTIFIED")).isTrue();
    assertThat(canTransition("IN_PROGRESS", "APPEALING")).isTrue();
    assertThat(canTransition("IN_PROGRESS", "CLOSED")).isTrue();
    // 申诉被驳回 ⇒ 回到处理中；已整改后仍可申诉 / 关闭。
    assertThat(canTransition("APPEALING", "IN_PROGRESS")).isTrue();
    assertThat(canTransition("APPEALING", "RECTIFIED")).isTrue();
    assertThat(canTransition("RECTIFIED", "IN_PROGRESS")).isTrue();
  }

  @Test
  @DisplayName("16.1.2.1 非法状态转换一律被拒（未处理不能直接整改、已关闭是终态）")
  void illegalTransitionsAreRejected() {
    assertThat(canTransition("PENDING", "RECTIFIED")).isFalse();
    assertThat(canTransition("PENDING", "CLOSED")).isFalse();
    assertThat(canTransition("PENDING", "APPEALING")).isFalse();
    assertThat(canTransition("CLOSED", "IN_PROGRESS")).isFalse();
    assertThat(canTransition("CLOSED", "RECTIFIED")).isFalse();
    assertThat(canTransition("CLOSED", "APPEALING")).isFalse();
    assertThat(nextStatuses("CLOSED")).isEmpty();
    assertThat(canTransition(null, "CLOSED")).isFalse();
    assertThat(canTransition("PENDING", null)).isFalse();
  }

  @Test
  @DisplayName("16.1.2.1 整改状态机：待整改 → 整改中 → 已提交 → 已验收 / 已驳回")
  void remediationStatusMachine() {
    assertThat(canTransitionRemediation("OPEN", "IN_PROGRESS")).isTrue();
    assertThat(canTransitionRemediation("OPEN", "SUBMITTED")).isTrue();
    assertThat(canTransitionRemediation("IN_PROGRESS", "SUBMITTED")).isTrue();
    assertThat(canTransitionRemediation("SUBMITTED", "VERIFIED")).isTrue();
    assertThat(canTransitionRemediation("SUBMITTED", "REJECTED")).isTrue();
    assertThat(canTransitionRemediation("REJECTED", "IN_PROGRESS")).isTrue();
    // 已验收是终态。
    assertThat(canTransitionRemediation("VERIFIED", "IN_PROGRESS")).isFalse();
    assertThat(canTransitionRemediation("VERIFIED", "SUBMITTED")).isFalse();
    // 不能跳过「已提交」直接验收。
    assertThat(canTransitionRemediation("OPEN", "VERIFIED")).isFalse();
    assertThat(canTransitionRemediation("IN_PROGRESS", "VERIFIED")).isFalse();
  }

  // ==================================================================
  // 影响档位（派生）
  // ==================================================================

  @Test
  @DisplayName("16.1.2.1 影响档位是派生的：停号 > 下架 > 限制销售/扣款 > 警告")
  void impactLevelIsDerivedFromMeasures() {
    assertThat(impactLevel(List.of("WARNING"))).isEqualTo("LOW");
    assertThat(impactLevel(List.of("DEDUCTION"))).isEqualTo("MEDIUM");
    assertThat(impactLevel(List.of("RESTRICT_SALES"))).isEqualTo("MEDIUM");
    assertThat(impactLevel(List.of("DELIST"))).isEqualTo("HIGH");
    assertThat(impactLevel(List.of("SUSPEND_ACCOUNT"))).isEqualTo("CRITICAL");
    // 多措施取最高档。
    assertThat(impactLevel(List.of("WARNING", "DELIST"))).isEqualTo("HIGH");
    assertThat(impactLevel(List.of("WARNING", "SUSPEND_ACCOUNT", "DELIST"))).isEqualTo("CRITICAL");
  }

  @Test
  @DisplayName("16.1.2.1 「尚无处罚措施」是它自己的档位，**不等于没问题**")
  void noMeasuresIsItsOwnLevel() {
    assertThat(impactLevel(List.of())).isEqualTo("NONE");
    assertThat(impactLevel(null)).isEqualTo("NONE");
    assertThat(impactLabel("NONE")).contains("尚无处罚措施");
    assertThat(impactLabel("NONE")).doesNotContain("正常");
  }

  // ==================================================================
  // 金额（Money）
  // ==================================================================

  @Test
  @DisplayName("16.1.2.1 Money：币种规范、金额非负、最多 6 位小数")
  void moneyValidation() {
    assertThat(new Money(new BigDecimal("12.50"), "usd").currency()).isEqualTo("USD");
    assertThatThrownBy(() -> new Money(new BigDecimal("1"), "US"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("三位");
    assertThatThrownBy(() -> new Money(new BigDecimal("-1"), "USD"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不能为负");
    assertThatThrownBy(() -> new Money(new BigDecimal("1.0000001"), "USD"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("小数");
  }

  @Test
  @DisplayName("16.1.2.1 🔴 不同币种直接相加必须抛异常 —— 不许给出一个看起来合理的和")
  void differentCurrenciesCannotBeAdded() {
    Money usd = new Money(new BigDecimal("100.00"), "USD");
    Money eur = new Money(new BigDecimal("100.00"), "EUR");
    assertThatThrownBy(() -> usd.plus(eur))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不同币种");
    // 同币种可以加。
    assertThat(usd.plus(new Money(new BigDecimal("50.25"), "USD")).amount())
        .isEqualByComparingTo("150.25");
  }

  @Test
  @DisplayName("16.1.2.1 Money 展示串带币种（页面上一眼看得出是什么钱）")
  void moneyDisplay() {
    assertThat(new Money(new BigDecimal("100.00"), "USD").display()).isEqualTo("100 USD");
    assertThat(new Money(new BigDecimal("12.5"), "EUR").display()).isEqualTo("12.5 EUR");
  }
}
