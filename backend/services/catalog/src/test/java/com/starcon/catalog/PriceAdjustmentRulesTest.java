package com.starcon.catalog;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.starcon.catalog.PriceAdjustmentModels.Status;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * 5.4.1.1 调价单的**纯规则**测试（无 IO、无数据库）。
 *
 * <p>这里钉死三件事：新价的舍入口径、幅度/价差的算法、以及**状态机的合法转换白名单**。
 */
class PriceAdjustmentRulesTest {

  // ================================================================ 舍入

  @Test
  void 新价按两位小数HALF_UP落定() {
    assertThat(PriceAdjustmentRules.newPrice(new BigDecimal("12.345")))
        .isEqualTo(new BigDecimal("12.35"));
    assertThat(PriceAdjustmentRules.newPrice(new BigDecimal("12.344")))
        .isEqualTo(new BigDecimal("12.34"));
    // 🔴 HALF_UP：.5 往上，不靠 IEEE 754 的银行家舍入。
    assertThat(PriceAdjustmentRules.newPrice(new BigDecimal("0.005"))).isEqualTo(new BigDecimal("0.01"));
  }

  @Test
  void 新价不是正价时返回null而不是零() {
    assertThat(PriceAdjustmentRules.newPrice(BigDecimal.ZERO)).isNull();
    assertThat(PriceAdjustmentRules.newPrice(new BigDecimal("-1"))).isNull();
    assertThat(PriceAdjustmentRules.newPrice(null)).isNull();
  }

  @Test
  void 价差与幅度口径() {
    var old = new BigDecimal("100.00");
    var now = new BigDecimal("120.00");
    assertThat(PriceAdjustmentRules.delta(old, now)).isEqualTo(new BigDecimal("20.0000"));
    assertThat(PriceAdjustmentRules.rate(old, now)).isEqualTo(new BigDecimal("0.200000"));
    assertThat(PriceAdjustmentRules.amount(old, now)).isEqualTo(new BigDecimal("20.0000"));
    // 降价时金额取绝对值（审批看的是「改了多少」，不是方向）。
    assertThat(PriceAdjustmentRules.amount(now, old)).isEqualTo(new BigDecimal("20.0000"));
  }

  @Test
  void 旧价为零时幅度不可计算而不是零() {
    var rate = PriceAdjustmentRules.rate(BigDecimal.ZERO, new BigDecimal("10"));
    assertThat(rate).isNull();
  }

  @Test
  void 亏损标记在毛利额缺失时是null而不是false() {
    assertThat(PriceAdjustmentRules.lossFlag(null)).isNull();
    assertThat(PriceAdjustmentRules.lossFlag(new BigDecimal("-1"))).isTrue();
    assertThat(PriceAdjustmentRules.lossFlag(new BigDecimal("0"))).isFalse();
  }

  @Test
  void 金额四位费率六位HALF_UP() {
    assertThat(PriceAdjustmentRules.money(new BigDecimal("1.23456")))
        .isEqualTo(new BigDecimal("1.2346"));
    assertThat(PriceAdjustmentRules.rate(new BigDecimal("0.1234565")))
        .isEqualTo(new BigDecimal("0.123457"));
  }

  // ================================================================ 状态机

  @Test
  void 状态机的合法路径() {
    assertThat(PriceAdjustmentRules.canTransition(Status.DRAFT, Status.PENDING_APPROVAL)).isTrue();
    assertThat(PriceAdjustmentRules.canTransition(Status.DRAFT, Status.APPROVED)).isTrue();
    assertThat(PriceAdjustmentRules.canTransition(Status.DRAFT, Status.REJECTED)).isTrue();
    assertThat(PriceAdjustmentRules.canTransition(Status.PENDING_APPROVAL, Status.APPROVED)).isTrue();
    assertThat(PriceAdjustmentRules.canTransition(Status.PENDING_APPROVAL, Status.REJECTED)).isTrue();
    assertThat(PriceAdjustmentRules.canTransition(Status.APPROVED, Status.EXECUTED)).isTrue();
  }

  @Test
  void 已执行与已驳回是终态() {
    assertThat(PriceAdjustmentRules.canTransition(Status.EXECUTED, Status.APPROVED)).isFalse();
    assertThat(PriceAdjustmentRules.canTransition(Status.EXECUTED, Status.EXECUTED)).isFalse();
    assertThat(PriceAdjustmentRules.canTransition(Status.REJECTED, Status.APPROVED)).isFalse();
    assertThat(PriceAdjustmentRules.canTransition(Status.REJECTED, Status.DRAFT)).isFalse();
    // 草稿不能直接执行（必须先提交 → 已批）。
    assertThat(PriceAdjustmentRules.canTransition(Status.DRAFT, Status.EXECUTED)).isFalse();
  }

  @Test
  void 非法转换明确报错而不是静默忽略() {
    var error =
        assertThrows(
            IllegalStateException.class,
            () -> PriceAdjustmentRules.transition(Status.DRAFT, Status.EXECUTED));
    assertThat(error.getMessage()).contains("草稿");
    assertThat(error.getMessage()).contains("已执行");
  }

  @Test
  void 归一化平台码与站点码() {
    assertThat(PriceAdjustmentRules.code(" amazon ")).isEqualTo("AMAZON");
    assertThat(PriceAdjustmentRules.code("")).isNull();
    assertThat(PriceAdjustmentRules.code(null)).isNull();
    assertThat(PriceAdjustmentRules.trimToNull("  ")).isNull();
    assertThat(PriceAdjustmentRules.trimToEmpty(null)).isEmpty();
  }
}
