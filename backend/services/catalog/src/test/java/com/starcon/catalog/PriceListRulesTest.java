package com.starcon.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.starcon.catalog.PriceListModels.ScopeInput;
import com.starcon.catalog.PriceListModels.ScopeType;
import com.starcon.catalog.PriceListModels.ScopeView;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 5.1.1.1 价目表的**纯规则**断言。
 *
 * <p>这些用例守的是蓝图那条加粗句的**技术落点**：
 * 「**一价目一币种**是硬约束——混币种的价目表会让利润计算失效」
 * 以及紧挨着它的半开区间约定。
 */
class PriceListRulesTest {

  private static final OffsetDateTime T0 =
      OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC);

  // ================================================================ 编码

  @Test
  void 编码归一化成大写并去空白() {
    assertThat(PriceListRules.normalizeCode("  na-retail  ")).isEqualTo("NA-RETAIL");
    assertThat(PriceListRules.normalizeCode("NA_RETAIL_01")).isEqualTo("NA_RETAIL_01");
  }

  @Test
  void 空编码与非法字符被拒() {
    assertThatThrownBy(() -> PriceListRules.normalizeCode("  "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("编码必填");
    assertThatThrownBy(() -> PriceListRules.normalizeCode("北美 零售价"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("只能是大写字母");
    // 首位不能是分隔符。
    assertThatThrownBy(() -> PriceListRules.normalizeCode("-NA"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ================================================================ 币种（一价目一币种）

  @Test
  void 币种归一化成三位大写字母() {
    assertThat(PriceListRules.normalizeCurrency(" usd ")).isEqualTo("USD");
    assertThat(PriceListRules.normalizeCurrency("cny")).isEqualTo("CNY");
  }

  @Test
  void 混币种写法被拒() {
    // 「USD,CNY」正是混币种价目表最可能的写法 —— 归一化阶段就挡掉。
    assertThatThrownBy(() -> PriceListRules.normalizeCurrency("USD,CNY"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("一张价目表只能有一个币种");
    assertThatThrownBy(() -> PriceListRules.normalizeCurrency("USDCNY"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 空币种与两位字母被拒() {
    assertThatThrownBy(() -> PriceListRules.normalizeCurrency(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("必须指定价目表币种");
    assertThatThrownBy(() -> PriceListRules.normalizeCurrency("US"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ================================================================ 生效区间（半开区间）

  @Test
  void 区间必须非空且终点晚于起点() {
    assertThat(PriceListRules.validInterval(T0, T0.plusDays(1))).isTrue();
    assertThat(PriceListRules.validInterval(T0, null)).isTrue();
    assertThat(PriceListRules.validInterval(T0, T0)).isFalse();
    assertThat(PriceListRules.validInterval(T0, T0.minusSeconds(1))).isFalse();
    assertThat(PriceListRules.validInterval(null, T0)).isFalse();
  }

  @Test
  void 生效判定是半开区间() {
    // 起点含：now == from ⇒ 生效。
    assertThat(PriceListRules.effectiveNow(T0, T0.plusDays(1), T0)).isTrue();
    // 终点不含：now == to ⇒ 已失效。
    assertThat(PriceListRules.effectiveNow(T0, T0.plusDays(1), T0.plusDays(1))).isFalse();
    // 起点之前 ⇒ 未生效。
    assertThat(PriceListRules.effectiveNow(T0, T0.plusDays(1), T0.minusSeconds(1))).isFalse();
    // 终点之前一毫秒 ⇒ 仍生效。
    assertThat(PriceListRules.effectiveNow(T0, T0.plusDays(1), T0.plusDays(1).minusNanos(1_000_000)))
        .isTrue();
    // 终点为空 = 长期有效。
    assertThat(PriceListRules.effectiveNow(T0, null, T0.plusYears(5))).isTrue();
  }

  // ================================================================ 适用范围

  @Test
  void 适用范围去重且按法人店铺站点排序() {
    var scopes =
        PriceListRules.normalizeScopes(
            List.of(
                new ScopeInput(ScopeType.SITE, "US", "AMAZON"),
                new ScopeInput(ScopeType.SHOP, "AMZ-US", "AMAZON"),
                new ScopeInput(ScopeType.LEGAL_ENTITY, "US-HQ", null)));
    assertThat(scopes).hasSize(3);
    assertThat(scopes.get(0).type()).isEqualTo(ScopeType.LEGAL_ENTITY);
    assertThat(scopes.get(1).type()).isEqualTo(ScopeType.SHOP);
    assertThat(scopes.get(2).type()).isEqualTo(ScopeType.SITE);
  }

  @Test
  void 重复的适用对象被拒() {
    assertThatThrownBy(
            () ->
                PriceListRules.normalizeScopes(
                    List.of(
                        new ScopeInput(ScopeType.SHOP, "AMZ-US", null),
                        new ScopeInput(ScopeType.SHOP, "AMZ-US", null))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("重复");
  }

  @Test
  void 法人不能带平台码而站点必须带平台码() {
    assertThatThrownBy(
            () ->
                PriceListRules.normalizeScopes(
                    List.of(new ScopeInput(ScopeType.LEGAL_ENTITY, "US-HQ", "AMAZON"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("法人没有平台");
    assertThatThrownBy(
            () -> PriceListRules.normalizeScopes(List.of(new ScopeInput(ScopeType.SITE, "US", null))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("站点必须给平台码");
  }

  @Test
  void 空编码的适用对象被拒() {
    assertThatThrownBy(
            () -> PriceListRules.normalizeScopes(List.of(new ScopeInput(ScopeType.SHOP, "  ", null))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("必须给编码");
  }

  // ================================================================ 覆盖范围摘要

  @Test
  void 覆盖摘要按类型计数() {
    var scopes =
        List.of(
            view(ScopeType.LEGAL_ENTITY, "US-HQ"),
            view(ScopeType.SHOP, "AMZ-US"),
            view(ScopeType.SHOP, "AMZ-CA"),
            view(ScopeType.SITE, "US"));
    var coverage = PriceListRules.coverageOf(scopes);
    assertThat(coverage.scopeTotal()).isEqualTo(4);
    assertThat(coverage.legalEntityCount()).isEqualTo(1);
    assertThat(coverage.shopCount()).isEqualTo(2);
    assertThat(coverage.siteCount()).isEqualTo(1);
    assertThat(coverage.appliesToWholeTenant()).isFalse();
    assertThat(coverage.text()).isEqualTo("1 法人 / 2 店铺 / 1 站点");
  }

  @Test
  void 没有适用范围时摘要写明全租户() {
    var coverage = PriceListRules.coverageOf(List.of());
    assertThat(coverage.scopeTotal()).isZero();
    assertThat(coverage.appliesToWholeTenant()).isTrue();
    assertThat(coverage.text()).contains("全租户");
  }

  private static ScopeView view(ScopeType type, String ref) {
    return new ScopeView(UUID.randomUUID(), type, type.label(), ref, ref, null);
  }
}
