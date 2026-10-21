package com.starcon.trade;

import static com.starcon.trade.MassProductionModels.MassProductionAttributeState;
import static com.starcon.trade.MassProductionModels.MassProductionDecision;
import static com.starcon.trade.MassProductionModels.MassProductionDecisionSource;
import static com.starcon.trade.MassProductionModels.MassProductionFilingStatus;
import static com.starcon.trade.MassProductionModels.MassProductionPlanStatus;
import static com.starcon.trade.MassProductionRules.*;
import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.6.2.2 转量产与建档的纯规则单测。
 *
 * <p><b>本测试盯住三件最容易出错的事</b>：状态迁移合不合法、量产决策有没有举证、
 * 建档请求能不能重试。第三件尤其关键 —— catalog 的建档端点<b>不接受幂等键</b>，
 * 「结果未知时盲目重试」会建出重复 SKU。
 */
class MassProductionRulesTest {

  /** 断言某个动作抛的是 {@link ResponseStatusException}，并把它交回给断言继续用。 */
  static ResponseStatusException thrownBy(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
    Throwable thrown = catchThrowable(callable);
    assertThat(thrown).isInstanceOf(ResponseStatusException.class);
    return (ResponseStatusException) thrown;
  }

  // ================================================================ 状态机

  @Test
  void 状态机的合法边逐条对齐() {
    assertThat(allowedTargets(MassProductionPlanStatus.DRAFT))
        .containsExactlyInAnyOrder(
            MassProductionPlanStatus.DECIDED, MassProductionPlanStatus.CANCELLED);
    assertThat(allowedTargets(MassProductionPlanStatus.DECIDED))
        .containsExactlyInAnyOrder(
            MassProductionPlanStatus.BOM_FROZEN, MassProductionPlanStatus.CANCELLED);
    assertThat(allowedTargets(MassProductionPlanStatus.BOM_FROZEN))
        .containsExactlyInAnyOrder(
            MassProductionPlanStatus.SKU_FILED, MassProductionPlanStatus.CANCELLED);
    assertThat(allowedTargets(MassProductionPlanStatus.SKU_FILED))
        .containsExactlyInAnyOrder(
            MassProductionPlanStatus.RELEASED, MassProductionPlanStatus.CANCELLED);
    assertThat(allowedTargets(MassProductionPlanStatus.RELEASED))
        .containsExactly(MassProductionPlanStatus.CLOSED);
  }

  @Test
  void 已关闭与已取消是终态没有出边() {
    assertThat(isTerminalStatus(MassProductionPlanStatus.CLOSED)).isTrue();
    assertThat(isTerminalStatus(MassProductionPlanStatus.CANCELLED)).isTrue();
    assertThat(allowedTargets(MassProductionPlanStatus.CLOSED)).isEmpty();
    assertThat(allowedTargets(MassProductionPlanStatus.CANCELLED)).isEmpty();
    assertThat(isTerminalStatus(MassProductionPlanStatus.RELEASED)).isFalse();
  }

  @Test
  void 跳级被拒() {
    ResponseStatusException e =
        thrownBy(
            () ->
                requireTransition(MassProductionPlanStatus.DRAFT, MassProductionPlanStatus.RELEASED));
    assertThat(e).isNotNull();
    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(e.getMessage()).contains("不能从");
  }

  @Test
  void 目标状态与当前状态相同不是一次迁移() {
    ResponseStatusException e =
        thrownBy(
            () ->
                requireTransition(
                    MassProductionPlanStatus.DRAFT, MassProductionPlanStatus.DRAFT));
    assertThat(e).isNotNull();
    assertThat(e.getMessage()).contains("不是一次状态迁移");
  }

  @Test
  void 合法边不报错() {
    assertThatCode(() -> requireTransition(MassProductionPlanStatus.DRAFT, MassProductionPlanStatus.DECIDED))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                requireTransition(
                    MassProductionPlanStatus.RELEASED, MassProductionPlanStatus.CLOSED))
        .doesNotThrowAnyException();
  }

  // ================================================================ 量产决策的举证

  @Test
  void 决策理由必填() {
    ResponseStatusException e =
        thrownBy(
            () ->
                requireDecisionEvidence(
                    MassProductionDecision.GO,
                    MassProductionDecisionSource.TRIAL_RECOMMENDATION,
                    "   ",
                    "",
                    "GO"));
    assertThat(e).isNotNull();
    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(e.getMessage()).contains("决策理由必填");
  }

  @Test
  void 上游建议否掉而本计划要转量产时必须写覆盖理由() {
    ResponseStatusException e =
        thrownBy(
            () ->
                requireDecisionEvidence(
                    MassProductionDecision.GO,
                    MassProductionDecisionSource.MANUAL_OVERRIDE,
                    "客户坚持先小批量试水",
                    "",
                    "NO_GO"));
    assertThat(e).isNotNull();
    assertThat(e.getMessage()).contains("必须写明覆盖理由");
  }

  @Test
  void 覆盖上游建议时决策来源必须是人工覆盖() {
    ResponseStatusException e =
        thrownBy(
            () ->
                requireDecisionEvidence(
                    MassProductionDecision.GO,
                    MassProductionDecisionSource.TRIAL_RECOMMENDATION,
                    "客户坚持先小批量试水",
                    "客户已确认先做 500 件",
                    "NO_GO"));
    assertThat(e).isNotNull();
    assertThat(e.getMessage()).contains("人工覆盖上游建议");
  }

  @Test
  void 有覆盖理由的人工覆盖可以通过() {
    assertThatCode(
            () ->
                requireDecisionEvidence(
                    MassProductionDecision.GO,
                    MassProductionDecisionSource.MANUAL_OVERRIDE,
                    "客户坚持先小批量试水",
                    "客户已确认先做 500 件",
                    "NO_GO"))
        .doesNotThrowAnyException();
  }

  @Test
  void 上游建议说否而本计划暂缓时不需要覆盖理由() {
    assertThatCode(
            () ->
                requireDecisionEvidence(
                    MassProductionDecision.HOLD,
                    MassProductionDecisionSource.TRIAL_RECOMMENDATION,
                    "良率未达标，等下一轮试产",
                    "",
                    "NO_GO"))
        .doesNotThrowAnyException();
  }

  @Test
  void 上游说转量产时跟随即可不需要覆盖理由() {
    assertThatCode(
            () ->
                requireDecisionEvidence(
                    MassProductionDecision.GO,
                    MassProductionDecisionSource.TRIAL_RECOMMENDATION,
                    "良率与成本都达标",
                    "",
                    "GO"))
        .doesNotThrowAnyException();
  }

  // ================================================================ 建档请求的重试纪律

  @Test
  void 已建成的建档请求不许再提交() {
    ResponseStatusException e =
        thrownBy(() -> requireFilingResubmittable(MassProductionFilingStatus.CONFIRMED));
    assertThat(e).isNotNull();
    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(e.getMessage()).contains("已经建成了");
  }

  @Test
  void 结果未知的建档请求不许盲目重试() {
    ResponseStatusException e =
        thrownBy(() -> requireFilingResubmittable(MassProductionFilingStatus.UNKNOWN));
    assertThat(e).isNotNull();
    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(e.getMessage()).contains("结果未知");
    assertThat(e.getMessage()).contains("重复 SKU");
  }

  @Test
  void 提交中的建档请求不许再提交() {
    ResponseStatusException e =
        thrownBy(() -> requireFilingResubmittable(MassProductionFilingStatus.SUBMITTED));
    assertThat(e).isNotNull();
    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void 草稿失败与部分完成的建档请求可以重试() {
    assertThatCode(() -> requireFilingResubmittable(MassProductionFilingStatus.PENDING))
        .doesNotThrowAnyException();
    assertThatCode(() -> requireFilingResubmittable(MassProductionFilingStatus.FAILED))
        .doesNotThrowAnyException();
    assertThatCode(() -> requireFilingResubmittable(MassProductionFilingStatus.PARTIAL))
        .doesNotThrowAnyException();
  }

  @Test
  void 只有已确认才算建成了() {
    assertThat(isFilingConfirmed(MassProductionFilingStatus.CONFIRMED)).isTrue();
    assertThat(isFilingConfirmed(MassProductionFilingStatus.PARTIAL)).isFalse();
    assertThat(isFilingConfirmed(MassProductionFilingStatus.UNKNOWN)).isFalse();
  }

  // ================================================================ 属性值的形状

  @Test
  void 设置状态必须带取值() {
    ResponseStatusException e =
        thrownBy(() -> requireAttributeValues("SET", List.of("  ")));
    assertThat(e).isNotNull();
    assertThat(e.getMessage()).contains("必须给出取值");
  }

  @Test
  void 清空与继承状态不能附带取值() {
    assertThatCode(() -> requireAttributeValues("CLEAR", List.of())).doesNotThrowAnyException();
    assertThatCode(() -> requireAttributeValues("INHERIT", List.of())).doesNotThrowAnyException();
    ResponseStatusException e =
        thrownBy(() -> requireAttributeValues("CLEAR", List.of("红色")));
    assertThat(e).isNotNull();
    assertThat(e.getMessage()).contains("不能附带取值");
  }

  @Test
  void 设置状态带取值可以通过() {
    assertThatCode(() -> requireAttributeValues("SET", List.of("红色", "蓝色")))
        .doesNotThrowAnyException();
  }

  @Test
  void 属性状态枚举与规则里的字符串一致() {
    assertThat(MassProductionAttributeState.SET.name()).isEqualTo("SET");
    assertThat(MassProductionAttributeState.CLEAR.name()).isEqualTo("CLEAR");
    assertThat(MassProductionAttributeState.INHERIT.name()).isEqualTo("INHERIT");
  }

  // ================================================================ 金额 / 数量 / 币种

  @Test
  void 币种归一为三位大写字母() {
    assertThat(normalizeCurrency("usd")).isEqualTo("USD");
    assertThat(normalizeCurrency(" CNY ")).isEqualTo("CNY");
    assertThat(catchThrowable(() -> normalizeCurrency("US"))).isNotNull();
    assertThat(catchThrowable(() -> normalizeCurrency("USDD"))).isNotNull();
    assertThat(catchThrowable(() -> normalizeCurrency(""))).isNotNull();
  }

  @Test
  void 数量与金额必须为正() {
    assertThat(catchThrowable(() -> requirePositiveQuantity(BigDecimal.ZERO))).isNotNull();
    assertThat(catchThrowable(() -> requirePositiveQuantity(null))).isNotNull();
    assertThatCode(() -> requirePositiveQuantity(new BigDecimal("1.000000"))).doesNotThrowAnyException();
    assertThat(catchThrowable(() -> requirePositiveAmount(BigDecimal.ZERO, "量产目标成本")))
        .isNotNull();
    assertThat(
            catchThrowable(
                () -> requirePositiveAmount(new BigDecimal("-1"), "量产目标成本")))
        .isNotNull();
  }

  // ================================================================ 成本偏差

  @Test
  void 目标成本取不到时偏差额与偏差率都取不到而不是零() {
    assertThat(targetCostVarianceAmount(new BigDecimal("10"), null)).isNull();
    assertThat(targetCostVarianceRate(new BigDecimal("10"), null)).isNull();
  }

  @Test
  void 偏差率是比例不是百分数() {
    // 试产实测 110、目标 100 ⇒ 差额 10、偏差率 0.1（**不是 10**）
    assertThat(targetCostVarianceAmount(new BigDecimal("110.0000"), new BigDecimal("100.0000")))
        .isEqualByComparingTo("10.0000");
    assertThat(targetCostVarianceRate(new BigDecimal("110.0000"), new BigDecimal("100.0000")))
        .isEqualByComparingTo("0.100000");
  }

  @Test
  void 偏差率单次到六位小数() {
    assertThat(targetCostVarianceRate(new BigDecimal("1.000000"), new BigDecimal("3.000000")))
        .isEqualByComparingTo("-0.666667");
  }

  @Test
  void 目标成本为零时偏差率无意义() {
    assertThat(catchThrowable(() -> targetCostVarianceRate(new BigDecimal("10"), BigDecimal.ZERO)))
        .isNotNull();
  }
}
