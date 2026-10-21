package com.starcon.trade;

import static com.starcon.trade.DevelopmentSampleModels.DevelopmentSampleConfirmationResult;
import static com.starcon.trade.DevelopmentSampleModels.DevelopmentSampleOrderStatus;
import static com.starcon.trade.DevelopmentSampleModels.DevelopmentSampleSourceType;
import static com.starcon.trade.DevelopmentSampleModels.DevelopmentSampleStatus;
import static com.starcon.trade.DevelopmentSampleModels.DevelopmentSampleUsageType;
import static com.starcon.trade.DevelopmentSampleRules.*;
import static org.assertj.core.api.Assertions.*;
// 显式单条静态导入压过 AssertJ 的通配导入：assertThrows 在 JUnit 与 AssertJ 里各有一份。
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.6.1.2 打样与样品管理的纯规则单测。
 *
 * <p><b>不碰数据库</b>：状态机的合法边、确认结果与目标状态的对应、来源与来源实体的自洽、
 * 「打样 vs 达人寄样」的边界 —— 这四件事一旦错了，集成测试只会告诉你「400 了」，
 * 不会告诉你「为什么错」。
 *
 * <p>🔴 本测试同时是「口径写死」的凭据：状态机的每一条边、用途词表的值域、
 * 成本类型的封闭词表，都在这里被钉住 —— 口径漂了先红在这里。
 */
class DevelopmentSampleRulesTest {

  // ================================================================ 打样单状态机

  @Test
  void 打样单按已申请进行中已完成已关闭单向推进() {
    assertThat(allowedOrderTargets(DevelopmentSampleOrderStatus.REQUESTED))
        .containsExactly(
            DevelopmentSampleOrderStatus.IN_PROGRESS, DevelopmentSampleOrderStatus.CANCELLED);
    assertThat(allowedOrderTargets(DevelopmentSampleOrderStatus.IN_PROGRESS))
        .containsExactly(
            DevelopmentSampleOrderStatus.COMPLETED, DevelopmentSampleOrderStatus.CANCELLED);
    assertThat(allowedOrderTargets(DevelopmentSampleOrderStatus.COMPLETED))
        .containsExactly(DevelopmentSampleOrderStatus.CLOSED);
  }

  @Test
  void 打样单已关闭与已取消是终态没有出边() {
    assertThat(allowedOrderTargets(DevelopmentSampleOrderStatus.CLOSED)).isEmpty();
    assertThat(allowedOrderTargets(DevelopmentSampleOrderStatus.CANCELLED)).isEmpty();
    assertThat(isTerminalOrderStatus(DevelopmentSampleOrderStatus.CLOSED)).isTrue();
    assertThat(isTerminalOrderStatus(DevelopmentSampleOrderStatus.CANCELLED)).isTrue();
    assertThat(isTerminalOrderStatus(DevelopmentSampleOrderStatus.REQUESTED)).isFalse();
  }

  @Test
  void 打样单不能跳过进行中直接完成() {
    assertThrows(
        ResponseStatusException.class,
        () ->
            requireOrderTransition(
                DevelopmentSampleOrderStatus.REQUESTED, DevelopmentSampleOrderStatus.COMPLETED));
  }

  @Test
  void 打样单非法迁移被拒且报出允许的去向() {
    var error =
        assertThrows(
            ResponseStatusException.class,
            () ->
                requireOrderTransition(
                    DevelopmentSampleOrderStatus.CLOSED, DevelopmentSampleOrderStatus.IN_PROGRESS));
    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(error.getReason()).contains("终态");
  }

  @Test
  void 打样单迁到自身不是一次状态迁移() {
    assertThrows(
        ResponseStatusException.class,
        () ->
            requireOrderTransition(
                DevelopmentSampleOrderStatus.REQUESTED, DevelopmentSampleOrderStatus.REQUESTED));
  }

  // ================================================================ 样品状态机

  @Test
  void 样品按已申请打样中已发出已到样推进() {
    assertThat(allowedSampleTargets(DevelopmentSampleStatus.REQUESTED))
        .containsExactly(DevelopmentSampleStatus.MAKING, DevelopmentSampleStatus.ABANDONED);
    assertThat(allowedSampleTargets(DevelopmentSampleStatus.MAKING))
        .containsExactly(DevelopmentSampleStatus.SHIPPED, DevelopmentSampleStatus.ABANDONED);
    assertThat(allowedSampleTargets(DevelopmentSampleStatus.SHIPPED))
        .containsExactly(DevelopmentSampleStatus.RECEIVED, DevelopmentSampleStatus.ABANDONED);
  }

  @Test
  void 样品到样后可以确认通过修改或放弃() {
    assertThat(allowedSampleTargets(DevelopmentSampleStatus.RECEIVED))
        .containsExactly(
            DevelopmentSampleStatus.CONFIRMED,
            DevelopmentSampleStatus.REVISING,
            DevelopmentSampleStatus.ABANDONED);
  }

  @Test
  void 样品修改中不是终态可以重新打样也可以改完直接通过() {
    assertThat(allowedSampleTargets(DevelopmentSampleStatus.REVISING))
        .containsExactly(
            DevelopmentSampleStatus.MAKING,
            DevelopmentSampleStatus.CONFIRMED,
            DevelopmentSampleStatus.ABANDONED);
    assertThat(isTerminalSampleStatus(DevelopmentSampleStatus.REVISING)).isFalse();
  }

  @Test
  void 样品已确认与已放弃是终态没有出边() {
    assertThat(allowedSampleTargets(DevelopmentSampleStatus.CONFIRMED)).isEmpty();
    assertThat(allowedSampleTargets(DevelopmentSampleStatus.ABANDONED)).isEmpty();
    assertThat(isTerminalSampleStatus(DevelopmentSampleStatus.CONFIRMED)).isTrue();
    assertThat(isTerminalSampleStatus(DevelopmentSampleStatus.ABANDONED)).isTrue();
  }

  @Test
  void 样品不能跳过打样中直接变成已发出() {
    assertThrows(
        ResponseStatusException.class,
        () ->
            requireSampleTransition(
                DevelopmentSampleStatus.REQUESTED, DevelopmentSampleStatus.SHIPPED));
  }

  @Test
  void 样品不能跳过已到样直接变成已确认() {
    assertThrows(
        ResponseStatusException.class,
        () ->
            requireSampleTransition(
                DevelopmentSampleStatus.MAKING, DevelopmentSampleStatus.CONFIRMED));
  }

  // ================================================================ 确认结果 ↔ 目标状态

  @Test
  void 确认结果与目标状态一一对应() {
    assertThat(targetStatusFor(DevelopmentSampleConfirmationResult.PASSED))
        .isEqualTo(DevelopmentSampleStatus.CONFIRMED);
    assertThat(targetStatusFor(DevelopmentSampleConfirmationResult.MODIFY))
        .isEqualTo(DevelopmentSampleStatus.REVISING);
    assertThat(targetStatusFor(DevelopmentSampleConfirmationResult.ABANDONED))
        .isEqualTo(DevelopmentSampleStatus.ABANDONED);
  }

  @Test
  void 目标状态反向映射回确认结果() {
    assertThat(confirmationResultFor(DevelopmentSampleStatus.CONFIRMED))
        .isEqualTo(DevelopmentSampleConfirmationResult.PASSED);
    assertThat(confirmationResultFor(DevelopmentSampleStatus.REVISING))
        .isEqualTo(DevelopmentSampleConfirmationResult.MODIFY);
    assertThat(confirmationResultFor(DevelopmentSampleStatus.ABANDONED))
        .isEqualTo(DevelopmentSampleConfirmationResult.ABANDONED);
  }

  @Test
  void 制作进度类目标状态不是确认边() {
    assertThat(isConfirmationEdge(DevelopmentSampleStatus.CONFIRMED)).isTrue();
    assertThat(isConfirmationEdge(DevelopmentSampleStatus.REVISING)).isTrue();
    assertThat(isConfirmationEdge(DevelopmentSampleStatus.ABANDONED)).isTrue();
    assertThat(isConfirmationEdge(DevelopmentSampleStatus.MAKING)).isFalse();
    assertThat(isConfirmationEdge(DevelopmentSampleStatus.SHIPPED)).isFalse();
    assertThat(isConfirmationEdge(DevelopmentSampleStatus.RECEIVED)).isFalse();
  }

  // ================================================================ 打样 vs 达人寄样

  @Test
  void 用途词表只有开发打样一个值() {
    assertThat(DevelopmentSampleUsageType.values())
        .containsExactly(DevelopmentSampleUsageType.DEVELOPMENT);
  }

  @Test
  void 非开发打样用途被拒并点名达人寄样归14卷管() {
    // 用途枚举只有 DEVELOPMENT 一个值 ⇒ 能走到校验里的「非开发打样」只有 null。
    // 这里守的是**校验本身**：词表一旦被人加了营销寄样的值，这条规则必须立刻拦住。
    var error = assertThrows(ResponseStatusException.class, () -> requireUsageType(null));
    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(error.getReason()).contains("达人寄样").contains("14.5.1.2");
  }

  @Test
  void 开发打样用途通过() {
    requireUsageType(DevelopmentSampleUsageType.DEVELOPMENT);
  }

  @Test
  void 来源词表是供应商自有产档口三个且没有其它桶() {
    assertThat(DevelopmentSampleSourceType.values())
        .containsExactly(
            DevelopmentSampleSourceType.SUPPLIER,
            DevelopmentSampleSourceType.OWN_FACTORY,
            DevelopmentSampleSourceType.STALL);
    // 🔴 留一个「其它」桶就等于把营销寄样塞进来 —— 词表里不许有它。
    assertThat(
            java.util.Arrays.stream(DevelopmentSampleSourceType.values())
                .map(Enum::name)
                .toList())
        .doesNotContain("OTHER", "MARKETING", "INFLUENCER");
  }

  // ================================================================ 来源 ↔ 来源实体

  @Test
  void 供应商打样必须挂供应商() {
    assertThrows(
        ResponseStatusException.class,
        () ->
            requireSourceConsistency(DevelopmentSampleSourceType.SUPPLIER, null, null));
    requireSourceConsistency(
        DevelopmentSampleSourceType.SUPPLIER, UUID.randomUUID(), null);
  }

  @Test
  void 档口打样必须挂档口() {
    assertThrows(
        ResponseStatusException.class,
        () -> requireSourceConsistency(DevelopmentSampleSourceType.STALL, null, null));
    requireSourceConsistency(DevelopmentSampleSourceType.STALL, null, UUID.randomUUID());
  }

  @Test
  void 自有产打样不许挂供应商或档口() {
    assertThrows(
        ResponseStatusException.class,
        () ->
            requireSourceConsistency(
                DevelopmentSampleSourceType.OWN_FACTORY, UUID.randomUUID(), null));
    assertThrows(
        ResponseStatusException.class,
        () ->
            requireSourceConsistency(
                DevelopmentSampleSourceType.OWN_FACTORY, null, UUID.randomUUID()));
    requireSourceConsistency(DevelopmentSampleSourceType.OWN_FACTORY, null, null);
  }

  // ================================================================ 确认记录

  @Test
  void 确认必须写明原因() {
    assertThrows(
        ResponseStatusException.class,
        () ->
            requireConfirmation(DevelopmentSampleConfirmationResult.PASSED, "  ", null));
  }

  @Test
  void 修改结果必须写明修改意见() {
    var error =
        assertThrows(
            ResponseStatusException.class,
            () ->
                requireConfirmation(DevelopmentSampleConfirmationResult.MODIFY, "颜色不对", ""));
    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(error.getReason()).contains("修改意见");
  }

  @Test
  void 通过与放弃不要求修改意见() {
    requireConfirmation(DevelopmentSampleConfirmationResult.PASSED, "与图纸一致", null);
    requireConfirmation(DevelopmentSampleConfirmationResult.ABANDONED, "供应商做不出来", null);
  }

  // ================================================================ 金额 / 数量 / 币种

  @Test
  void 成本金额必须大于零() {
    assertThrows(
        ResponseStatusException.class, () -> requirePositiveAmount(BigDecimal.ZERO));
    assertThrows(
        ResponseStatusException.class, () -> requirePositiveAmount(new BigDecimal("-1.00")));
    assertThrows(
        ResponseStatusException.class, () -> requirePositiveAmount(null));
    requirePositiveAmount(new BigDecimal("0.01"));
  }

  @Test
  void 样品数量必须大于零() {
    assertThrows(
        ResponseStatusException.class, () -> requirePositiveQuantity(BigDecimal.ZERO));
    requirePositiveQuantity(new BigDecimal("1"));
  }

  @Test
  void 币种归一为三位大写字母() {
    assertThat(normalizeCurrency("usd")).isEqualTo("USD");
    assertThat(normalizeCurrency(" cny ")).isEqualTo("CNY");
    assertThrows(ResponseStatusException.class, () -> normalizeCurrency("US"));
    assertThrows(ResponseStatusException.class, () -> normalizeCurrency("USDD"));
    assertThrows(ResponseStatusException.class, () -> normalizeCurrency(null));
  }

  @Test
  void 刻度与数据库列宽对齐() {
    assertThat(MONEY_SCALE).isEqualTo(4);
    assertThat(QUANTITY_SCALE).isEqualTo(6);
  }
}
