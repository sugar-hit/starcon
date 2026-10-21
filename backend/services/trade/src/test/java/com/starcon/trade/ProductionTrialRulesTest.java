package com.starcon.trade;

import static com.starcon.trade.ProductionTrialModels.ProductionTrialJudgment;
import static com.starcon.trade.ProductionTrialModels.ProductionTrialMassProductionDecision;
import static com.starcon.trade.ProductionTrialModels.ProductionTrialStatus;
import static com.starcon.trade.ProductionTrialRules.*;
import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.6.2.1 试产单的纯规则单测（不碰数据库）。
 *
 * <p>🔴 这组用例守的是本模块最容易悄悄漂移的三件事：
 * <b>状态机的合法边</b>、<b>良率 / 成本偏差是比例而不是百分数</b>、
 * 以及<b>「建议转量产」必须举证</b>。
 */
class ProductionTrialRulesTest {

  private static final BigDecimal ZERO = new BigDecimal("0");
  private static final BigDecimal ONE = new BigDecimal("1");

  // ================================================================ 状态机

  @Test
  void 状态机的合法边与服务层一致() {
    assertThat(allowedTargets(ProductionTrialStatus.REQUESTED))
        .containsExactlyInAnyOrder(ProductionTrialStatus.SCHEDULED, ProductionTrialStatus.CANCELLED);
    assertThat(allowedTargets(ProductionTrialStatus.SCHEDULED))
        .containsExactlyInAnyOrder(ProductionTrialStatus.IN_PROGRESS, ProductionTrialStatus.CANCELLED);
    assertThat(allowedTargets(ProductionTrialStatus.IN_PROGRESS))
        .containsExactlyInAnyOrder(ProductionTrialStatus.COMPLETED, ProductionTrialStatus.CANCELLED);
    assertThat(allowedTargets(ProductionTrialStatus.COMPLETED))
        .containsExactly(ProductionTrialStatus.CLOSED);
  }

  @Test
  void 已关闭与已取消是终态_没有出边() {
    assertThat(isTerminalStatus(ProductionTrialStatus.CLOSED)).isTrue();
    assertThat(isTerminalStatus(ProductionTrialStatus.CANCELLED)).isTrue();
    assertThat(allowedTargets(ProductionTrialStatus.CLOSED)).isEmpty();
    assertThat(allowedTargets(ProductionTrialStatus.CANCELLED)).isEmpty();
    assertThat(isTerminalStatus(ProductionTrialStatus.COMPLETED)).isFalse();
  }

  @Test
  void 跳级被拒_不能从已申请直接进试产中() {
    ResponseStatusException e =
        catchThrowableOfType(
            () -> requireTransition(ProductionTrialStatus.REQUESTED, ProductionTrialStatus.IN_PROGRESS),
            ResponseStatusException.class);
    assertThat(e).isNotNull();
    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(e.getReason()).contains("已申请").contains("试产中");
  }

  @Test
  void 状态相同不是一次状态迁移() {
    ResponseStatusException e =
        catchThrowableOfType(
            () -> requireTransition(ProductionTrialStatus.REQUESTED, ProductionTrialStatus.REQUESTED),
            ResponseStatusException.class);
    assertThat(e).isNotNull();
    assertThat(e.getReason()).contains("不是一次状态迁移");
  }

  @Test
  void 终态不提供恢复() {
    ResponseStatusException e =
        catchThrowableOfType(
            () -> requireTransition(ProductionTrialStatus.CLOSED, ProductionTrialStatus.IN_PROGRESS),
            ResponseStatusException.class);
    assertThat(e).isNotNull();
    assertThat(e.getReason()).contains("终态");
  }

  @Test
  void 合法边放行() {
    assertThatCode(
            () -> requireTransition(ProductionTrialStatus.REQUESTED, ProductionTrialStatus.SCHEDULED))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                requireTransition(
                    ProductionTrialStatus.IN_PROGRESS, ProductionTrialStatus.COMPLETED))
        .doesNotThrowAnyException();
  }

  @Test
  void 没做完的试产不许出转量产建议() {
    ResponseStatusException e =
        catchThrowableOfType(
            () -> requireRecommendationStage(ProductionTrialStatus.IN_PROGRESS),
            ResponseStatusException.class);
    assertThat(e).isNotNull();
    assertThat(e.getReason()).contains("已完成的试产单");
    assertThatCode(() -> requireRecommendationStage(ProductionTrialStatus.COMPLETED))
        .doesNotThrowAnyException();
  }

  // ================================================================ 良率

  @Test
  void 良率是比例不是百分数() {
    assertThat(yieldRate(new BigDecimal("950"), new BigDecimal("1000")))
        .isEqualByComparingTo(new BigDecimal("0.950000"));
    assertThat(yieldRate(new BigDecimal("19"), new BigDecimal("20")))
        .isEqualByComparingTo(new BigDecimal("0.950000"));
  }

  @Test
  void 良率单次HALF_UP到六位小数() {
    // 1/3 = 0.333333... ⇒ 0.333333（不是 0.3333334 也不是 33.3333）
    assertThat(yieldRate(ONE, new BigDecimal("3")))
        .isEqualByComparingTo(new BigDecimal("0.333333"));
  }

  @Test
  void 全合格时良率是一() {
    assertThat(yieldRate(new BigDecimal("100"), new BigDecimal("100")))
        .isEqualByComparingTo(ONE);
  }

  @Test
  void 没有产出就没有良率() {
    ResponseStatusException e =
        catchThrowableOfType(
            () -> yieldRate(ZERO, ZERO), ResponseStatusException.class);
    assertThat(e).isNotNull();
    assertThat(e.getReason()).contains("产出数量必须大于 0");
  }

  @Test
  void 合格数量不能超过产出() {
    ResponseStatusException e =
        catchThrowableOfType(
            () -> yieldRate(new BigDecimal("11"), new BigDecimal("10")),
            ResponseStatusException.class);
    assertThat(e).isNotNull();
    assertThat(e.getReason()).contains("合格数量必须落在 0 与产出数量之间");
  }

  @Test
  void 合格数量不能为负() {
    ResponseStatusException e =
        catchThrowableOfType(
            () -> yieldRate(new BigDecimal("-1"), new BigDecimal("10")),
            ResponseStatusException.class);
    assertThat(e).isNotNull();
  }

  // ================================================================ 成本偏差

  @Test
  void 成本偏差率是比例() {
    assertThat(costVarianceRate(new BigDecimal("120.0000"), new BigDecimal("100.0000")))
        .isEqualByComparingTo(new BigDecimal("0.200000"));
    assertThat(costVarianceAmount(new BigDecimal("120.0000"), new BigDecimal("100.0000")))
        .isEqualByComparingTo(new BigDecimal("20.0000"));
  }

  @Test
  void 量产目标成本取不到时偏差率取不到_不折算也不顶零() {
    assertThat(costVarianceRate(new BigDecimal("120.0000"), null)).isNull();
    assertThat(costVarianceAmount(new BigDecimal("120.0000"), null)).isNull();
  }

  @Test
  void 量产目标成本为零时偏差率没有意义() {
    ResponseStatusException e =
        catchThrowableOfType(
            () -> costVarianceRate(new BigDecimal("120.0000"), ZERO),
            ResponseStatusException.class);
    assertThat(e).isNotNull();
    assertThat(e.getReason()).contains("必须大于 0");
  }

  // ================================================================ 转量产建议的举证

  @Test
  void 建议转量产但良率不达标_被拒() {
    ResponseStatusException e =
        catchThrowableOfType(
            () ->
                requireRecommendationEvidence(
                    ProductionTrialMassProductionDecision.GO,
                    new BigDecimal("0.90"),
                    new BigDecimal("0.95"),
                    new BigDecimal("0.01"),
                    new BigDecimal("0.10")),
            ResponseStatusException.class);
    assertThat(e).isNotNull();
    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(e.getReason()).contains("良率").contains("0.90").contains("0.95");
  }

  @Test
  void 建议转量产但成本偏差超限_被拒() {
    ResponseStatusException e =
        catchThrowableOfType(
            () ->
                requireRecommendationEvidence(
                    ProductionTrialMassProductionDecision.GO,
                    new BigDecimal("0.98"),
                    new BigDecimal("0.95"),
                    new BigDecimal("0.25"),
                    new BigDecimal("0.10")),
            ResponseStatusException.class);
    assertThat(e).isNotNull();
    assertThat(e.getReason()).contains("成本偏差率");
  }

  @Test
  void 设了成本门槛但量产目标成本不可得_不能给建议转量产() {
    ResponseStatusException e =
        catchThrowableOfType(
            () ->
                requireRecommendationEvidence(
                    ProductionTrialMassProductionDecision.GO,
                    new BigDecimal("0.98"),
                    new BigDecimal("0.95"),
                    null,
                    new BigDecimal("0.10")),
            ResponseStatusException.class);
    assertThat(e).isNotNull();
    assertThat(e.getReason()).contains("量产目标成本不可得");
  }

  @Test
  void 条件都达标时建议转量产放行() {
    assertThatCode(
            () ->
                requireRecommendationEvidence(
                    ProductionTrialMassProductionDecision.GO,
                    new BigDecimal("0.98"),
                    new BigDecimal("0.95"),
                    new BigDecimal("0.03"),
                    new BigDecimal("0.10")))
        .doesNotThrowAnyException();
  }

  @Test
  void 没设成本门槛时只看良率() {
    assertThatCode(
            () ->
                requireRecommendationEvidence(
                    ProductionTrialMassProductionDecision.GO,
                    new BigDecimal("0.98"),
                    new BigDecimal("0.95"),
                    null,
                    null))
        .doesNotThrowAnyException();
  }

  @Test
  void 暂不建议转量产不需要举证() {
    assertThatCode(
            () ->
                requireRecommendationEvidence(
                    ProductionTrialMassProductionDecision.NO_GO,
                    new BigDecimal("0.99"),
                    new BigDecimal("0.95"),
                    new BigDecimal("0.01"),
                    new BigDecimal("0.10")))
        .doesNotThrowAnyException();
  }

  // ================================================================ 金额 / 数量 / 币种

  @Test
  void 币种归一为三位大写字母() {
    assertThat(normalizeCurrency("usd")).isEqualTo("USD");
    assertThat(normalizeCurrency(" CNY ")).isEqualTo("CNY");
    assertThatThrownBy(() -> normalizeCurrency("US"))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> normalizeCurrency("USDD"))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> normalizeCurrency("  "))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void 金额必须大于零() {
    assertThatThrownBy(() -> requirePositiveAmount(ZERO))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> requirePositiveAmount(null))
        .isInstanceOf(ResponseStatusException.class);
    assertThatCode(() -> requirePositiveAmount(new BigDecimal("0.01")))
        .doesNotThrowAnyException();
  }

  @Test
  void 数量必须大于零() {
    assertThatThrownBy(() -> requirePositiveQuantity(ZERO))
        .isInstanceOf(ResponseStatusException.class);
    assertThatCode(() -> requirePositiveQuantity(new BigDecimal("1"))).doesNotThrowAnyException();
  }

  @Test
  void 比例必须落在零到一之间() {
    assertThat(requireRate(new BigDecimal("0.95"), "良率门槛"))
        .isEqualByComparingTo(new BigDecimal("0.950000"));
    assertThatThrownBy(() -> requireRate(new BigDecimal("95"), "良率门槛"))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> requireRate(new BigDecimal("-0.1"), "良率门槛"))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> requireRate(null, "良率门槛"))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void 一张单一个币种_明细行币种必须与成本头一致() {
    assertThatCode(() -> requireSameCurrency("USD", "USD")).doesNotThrowAnyException();
    ResponseStatusException e =
        catchThrowableOfType(
            () -> requireSameCurrency("USD", "CNY"), ResponseStatusException.class);
    assertThat(e).isNotNull();
    assertThat(e.getReason()).contains("一个币种");
  }

  @Test
  void 判定不可撤回成待判定() {
    ResponseStatusException e =
        catchThrowableOfType(
            () -> requireJudgment(ProductionTrialJudgment.PENDING),
            ResponseStatusException.class);
    assertThat(e).isNotNull();
    assertThat(e.getReason()).contains("待判定");
    assertThatCode(() -> requireJudgment(ProductionTrialJudgment.PASS)).doesNotThrowAnyException();
    assertThatCode(() -> requireJudgment(ProductionTrialJudgment.FAIL)).doesNotThrowAnyException();
  }
}
