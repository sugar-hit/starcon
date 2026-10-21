package com.starcon.trade;

import static com.starcon.trade.DevelopmentProjectModels.DevelopmentStatus;
import static com.starcon.trade.DevelopmentProjectRules.*;
import static org.assertj.core.api.Assertions.*;
// 显式单条静态导入压过 AssertJ 的通配导入：assertThrows 在 JUnit 与 AssertJ 里各有一份。
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.6.1.1 开发立项的纯算式与状态机单测。
 *
 * <p><b>不碰数据库</b>：状态机的合法边、毛利率的算式与舍入、豁免的必要性，
 * 这三件事一旦错了，集成测试只会告诉你「400 了」，不会告诉你「为什么错」。
 *
 * <p>🔴 本测试同时是「口径写死」的凭据：目标毛利率的刻度、舍入方式、成本取不到时的行为，
 * 都在这里被钉住 —— 口径漂了先红在这里。
 */
class DevelopmentProjectRulesTest {

  // ================================================================ 毛利率

  @Test
  void 目标毛利按售价减成本除以售价算() {
    assertThat(targetMarginRate(new BigDecimal("100.0000"), new BigDecimal("60.0000")))
        .isEqualByComparingTo(new BigDecimal("0.400000"));
  }

  @Test
  void 目标毛利单次halflup舍入到六位而不是先舍入再除() {
    // 售价 3、成本 1 ⇒ 2/3 = 0.666666...66
    // 「先按 10 位除再舍到 6 位」与「直接按 6 位除」是同一个数（单次舍入）。
    assertThat(targetMarginRate(new BigDecimal("3.0000"), new BigDecimal("1.0000")))
        .isEqualByComparingTo(new BigDecimal("0.666667"));
    // 售价 7、成本 2 ⇒ 5/7 = 0.714285714... ⇒ HALF_UP 到 6 位 = 0.714286
    assertThat(targetMarginRate(new BigDecimal("7.0000"), new BigDecimal("2.0000")))
        .isEqualByComparingTo(new BigDecimal("0.714286"));
  }

  @Test
  void 目标毛利刻度固定为六位小数() {
    assertThat(MARGIN_RATE_SCALE).isEqualTo(6);
    assertThat(targetMarginRate(new BigDecimal("100.0000"), new BigDecimal("60.0000")).scale())
        .isEqualTo(6);
  }

  @Test
  void 成本为零不等于成本取不到() {
    // 成本真的是 0 ⇒ 毛利率真的是 100%（这不是假数据，是「这个品白送」的结论）。
    // 但**取不到**的成本绝不能用 0 顶替 —— 那是另一回事，由下面的用例守住。
    assertThat(targetMarginRate(new BigDecimal("100.0000"), BigDecimal.ZERO))
        .isEqualByComparingTo(new BigDecimal("1.000000"));
  }

  @Test
  void 成本取不到时不允许算毛利率() {
    ResponseStatusException e =
        assertThrows(
            ResponseStatusException.class,
            () -> targetMarginRate(new BigDecimal("100.0000"), null));
    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(e.getReason()).contains("100%");
  }

  @Test
  void 售价为零或负数时拒绝算毛利率() {
    assertThat(
            assertThrows(
                    ResponseStatusException.class,
                    () -> targetMarginRate(BigDecimal.ZERO, new BigDecimal("1")))
                .getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(
            assertThrows(
                    ResponseStatusException.class,
                    () -> targetMarginRate(new BigDecimal("-1"), new BigDecimal("1")))
                .getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void 成本为负数时拒绝() {
    assertThat(
            assertThrows(
                    ResponseStatusException.class,
                    () -> targetMarginRate(new BigDecimal("10"), new BigDecimal("-1")))
                .getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void 毛利算式原文把公式和舍入口径都写出来() {
    String basis = marginBasis(new BigDecimal("100.0000"), new BigDecimal("60.0000"), "USD");
    assertThat(basis).contains("(目标售价 − 目标成本) ÷ 目标售价");
    assertThat(basis).contains("0.400000");
    assertThat(basis).contains("HALF_UP");
    assertThat(basis).contains("6");
  }

  @Test
  void 成本取不到时毛利口径原文点名归谁管() {
    String basis = unavailableMarginBasis("5.3.1.1 利润测算器", "catalog 不可达");
    assertThat(basis).contains("5.3.1.1 利润测算器");
    assertThat(basis).contains("不输出目标毛利率");
  }

  // ================================================================ 状态机

  @Test
  void 正常路径逐级推进() {
    assertThat(allowedTargets(DevelopmentStatus.EVALUATION))
        .containsExactly(DevelopmentStatus.APPROVED, DevelopmentStatus.TERMINATED);
    assertThat(allowedTargets(DevelopmentStatus.APPROVED))
        .containsExactly(DevelopmentStatus.IN_DEVELOPMENT, DevelopmentStatus.TERMINATED);
    assertThat(allowedTargets(DevelopmentStatus.IN_DEVELOPMENT))
        .containsExactly(
            DevelopmentStatus.SAMPLING,
            DevelopmentStatus.PILOT,
            DevelopmentStatus.MASS_PRODUCTION,
            DevelopmentStatus.TERMINATED);
    assertThat(allowedTargets(DevelopmentStatus.SAMPLING))
        .containsExactly(
            DevelopmentStatus.PILOT, DevelopmentStatus.MASS_PRODUCTION, DevelopmentStatus.TERMINATED);
    assertThat(allowedTargets(DevelopmentStatus.PILOT))
        .containsExactly(DevelopmentStatus.MASS_PRODUCTION, DevelopmentStatus.TERMINATED);
    assertThat(allowedTargets(DevelopmentStatus.MASS_PRODUCTION))
        .containsExactly(DevelopmentStatus.TERMINATED);
  }

  @Test
  void 终止是终态没有出边() {
    assertThat(allowedTargets(DevelopmentStatus.TERMINATED)).isEmpty();
    assertThat(isTerminal(DevelopmentStatus.TERMINATED)).isTrue();
    assertThat(isTerminal(DevelopmentStatus.MASS_PRODUCTION)).isFalse();
  }

  @Test
  void 不允许跳级() {
    assertThat(
            assertThrows(
                    ResponseStatusException.class,
                    () ->
                        requireTransition(
                            DevelopmentStatus.EVALUATION,
                            DevelopmentStatus.MASS_PRODUCTION,
                            null,
                            null))
                .getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(
            assertThrows(
                    ResponseStatusException.class,
                    () ->
                        requireTransition(
                            DevelopmentStatus.APPROVED,
                            DevelopmentStatus.MASS_PRODUCTION,
                            null,
                            null))
                .getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void 终态之后不提供恢复() {
    ResponseStatusException e =
        assertThrows(
            ResponseStatusException.class,
            () ->
                requireTransition(
                    DevelopmentStatus.TERMINATED, DevelopmentStatus.APPROVED, null, null));
    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(e.getReason()).contains("终止是终态");
  }

  @Test
  void 迁到自己身上不是一次迁移() {
    assertThat(
            assertThrows(
                    ResponseStatusException.class,
                    () ->
                        requireTransition(
                            DevelopmentStatus.APPROVED, DevelopmentStatus.APPROVED, null, null))
                .getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void 跳过打样与试产必须给出豁免记录() {
    // 跳过打样
    assertThat(requiresExemption(DevelopmentStatus.IN_DEVELOPMENT, DevelopmentStatus.PILOT)).isTrue();
    // 跳过打样与试产
    assertThat(
            requiresExemption(
                DevelopmentStatus.IN_DEVELOPMENT, DevelopmentStatus.MASS_PRODUCTION))
        .isTrue();
    // 跳过试产
    assertThat(
            requiresExemption(DevelopmentStatus.SAMPLING, DevelopmentStatus.MASS_PRODUCTION))
        .isTrue();
    // 正常路径不需要豁免
    assertThat(
            requiresExemption(DevelopmentStatus.IN_DEVELOPMENT, DevelopmentStatus.SAMPLING))
        .isFalse();
    assertThat(requiresExemption(DevelopmentStatus.SAMPLING, DevelopmentStatus.PILOT)).isFalse();
    assertThat(requiresExemption(DevelopmentStatus.PILOT, DevelopmentStatus.MASS_PRODUCTION))
        .isFalse();
    // 终止永远不需要豁免
    assertThat(
            requiresExemption(DevelopmentStatus.IN_DEVELOPMENT, DevelopmentStatus.TERMINATED))
        .isFalse();
  }

  @Test
  void 没有豁免码时跳过被拒() {
    ResponseStatusException e =
        assertThrows(
            ResponseStatusException.class,
            () ->
                requireTransition(
                    DevelopmentStatus.IN_DEVELOPMENT,
                    DevelopmentStatus.MASS_PRODUCTION,
                    null,
                    "这次省略了打样"));
    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(e.getReason()).contains("豁免");
  }

  @Test
  void 只有豁免码没有豁免说明也被拒() {
    ResponseStatusException e =
        assertThrows(
            ResponseStatusException.class,
            () ->
                requireTransition(
                    DevelopmentStatus.IN_DEVELOPMENT,
                    DevelopmentStatus.PILOT,
                    "SKIP-SAMPLING",
                    "  "));
    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(e.getReason()).contains("豁免说明");
  }

  @Test
  void 豁免码与说明齐全时放行() {
    requireTransition(
        DevelopmentStatus.IN_DEVELOPMENT,
        DevelopmentStatus.MASS_PRODUCTION,
        "SKIP-SAMPLING-PILOT",
        "客户要求直供成品，本次不做打样与试产");
  }

  // ================================================================ 杂项

  @Test
  void 币种归一为三位大写() {
    assertThat(normalizeCurrency("usd")).isEqualTo("USD");
    assertThat(normalizeCurrency(" eur ")).isEqualTo("EUR");
  }

  @Test
  void 币种非法时拒绝() {
    assertThat(
            assertThrows(
                    ResponseStatusException.class, () -> normalizeCurrency("US"))
                .getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(
            assertThrows(
                    ResponseStatusException.class, () -> normalizeCurrency("USDD"))
                .getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }
}
