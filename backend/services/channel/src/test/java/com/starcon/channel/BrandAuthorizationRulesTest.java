package com.starcon.channel;

import static com.starcon.channel.BrandAuthorizationModels.BrandAuthorizationReason;
import static com.starcon.channel.BrandAuthorizationRules.*;
import static org.assertj.core.api.Assertions.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 16.4.2.1 品牌授权的判定规则单测（纯函数，不碰数据库）。
 *
 * <p>🔴 这组用例守的是三件最容易做丢的事：
 *
 * <ol>
 *   <li><b>没有原因的 false 是不允许出现的</b> —— 每个「不覆盖」都必须落到一条原因上；
 *   <li><b>「判不了」不等于「合格」</b> —— 上下文缺维度时结论是 UNKNOWN，不是 IN_SCOPE；
 *   <li><b>同一维度上没有登记范围 = 该维度不限制</b> —— 不是「不许卖」。
 * </ol>
 */
class BrandAuthorizationRulesTest {

  static final OffsetDateTime NOW = OffsetDateTime.of(2026, 6, 1, 12, 0, 0, 0, ZoneOffset.UTC);

  static BrandAuthorizationRules.AuthorizationFact fact(
      String id,
      boolean enabled,
      OffsetDateTime from,
      OffsetDateTime to,
      Map<String, Set<String>> scopes) {
    return new BrandAuthorizationRules.AuthorizationFact(
        id, "品牌方 → 被授权主体", enabled, from, to, scopes);
  }

  static Map<String, Set<String>> scopes(String kind, String... values) {
    Map<String, Set<String>> map = new LinkedHashMap<>();
    map.put(kind, new LinkedHashSet<>(List.of(values)));
    return map;
  }

  static BrandAuthorizationRules.CheckContext context(
      String shopId, String countryCode, String categoryId, String... shopCountries) {
    return new BrandAuthorizationRules.CheckContext(
        "brand-1", shopId, countryCode, categoryId, new LinkedHashSet<>(List.of(shopCountries)));
  }

  // ==================================================================
  // 授权档案为空
  // ==================================================================

  @Test
  @DisplayName("没有授权档案 ⇒ NO_AUTHORIZATION + 一条原因")
  void noAuthorizationAtAll() {
    Evaluation evaluation = evaluate(List.of(), context("shop-1", "DE", "cat-1"), NOW);
    assertThat(evaluation.inScope()).isFalse();
    assertThat(evaluation.conclusion()).isEqualTo("NO_AUTHORIZATION");
    assertThat(evaluation.severity()).isEqualTo("BLOCKING");
    assertThat(evaluation.reasons()).hasSize(1);
    assertThat(evaluation.reasons().get(0).code()).isEqualTo(REASON_NO_AUTHORIZATION);
    assertThat(evaluation.reasons().get(0).message()).isNotBlank();
  }

  // ==================================================================
  // 命中
  // ==================================================================

  @Test
  @DisplayName("范围与期限都覆盖 ⇒ IN_SCOPE + 命中那条授权")
  void inScope() {
    var fact =
        fact(
            "auth-1",
            true,
            NOW.minusDays(30),
            NOW.plusDays(30),
            scopes("COUNTRY", "DE", "US"));
    Evaluation evaluation = evaluate(List.of(fact), context("shop-1", "DE", "cat-1"), NOW);
    assertThat(evaluation.inScope()).isTrue();
    assertThat(evaluation.conclusion()).isEqualTo("IN_SCOPE");
    assertThat(evaluation.severity()).isEqualTo("NONE");
    assertThat(evaluation.matchedAuthorizationId()).isEqualTo("auth-1");
    assertThat(evaluation.reasons()).isEmpty();
  }

  @Test
  @DisplayName("同一维度没有登记范围 ⇒ 该维度不限制（不是「不许卖」）")
  void missingScopeKindMeansUnrestricted() {
    var fact = fact("auth-1", true, NOW.minusDays(1), NOW.plusDays(1), scopes("COUNTRY", "DE"));
    // 没给店铺、没给类目，但授权只限定了国家 ⇒ 店铺与类目两个维度不限制，仍判 IN_SCOPE
    Evaluation evaluation = evaluate(List.of(fact), context(null, "DE", null), NOW);
    assertThat(evaluation.inScope()).isTrue();
    assertThat(evaluation.conclusion()).isEqualTo("IN_SCOPE");
  }

  @Test
  @DisplayName("两条授权：一条失败、一条成功 ⇒ IN_SCOPE（命中成功的那条）")
  void anyMatchWins() {
    var failed = fact("auth-1", true, NOW.minusDays(30), NOW.plusDays(30), scopes("COUNTRY", "US"));
    var matched = fact("auth-2", true, NOW.minusDays(30), NOW.plusDays(30), scopes("COUNTRY", "DE"));
    Evaluation evaluation = evaluate(List.of(failed, matched), context("shop-1", "DE", "cat-1"), NOW);
    assertThat(evaluation.inScope()).isTrue();
    assertThat(evaluation.matchedAuthorizationId()).isEqualTo("auth-2");
  }

  // ==================================================================
  // 期限
  // ==================================================================

  @Test
  @DisplayName("授权已过期 ⇒ EXPIRED + 过期原因")
  void expired() {
    var fact = fact("auth-1", true, NOW.minusDays(90), NOW.minusDays(30), scopes("COUNTRY", "DE"));
    Evaluation evaluation = evaluate(List.of(fact), context("shop-1", "DE", "cat-1"), NOW);
    assertThat(evaluation.inScope()).isFalse();
    assertThat(evaluation.conclusion()).isEqualTo("EXPIRED");
    assertThat(evaluation.severity()).isEqualTo("BLOCKING");
    assertThat(evaluation.reasons()).extracting(BrandAuthorizationReason::code)
        .contains(REASON_EXPIRED);
  }

  @Test
  @DisplayName("授权尚未生效 ⇒ 明确不覆盖，并给出「尚未生效」的原因")
  void notYetEffective() {
    var fact = fact("auth-1", true, NOW.plusDays(10), NOW.plusDays(40), scopes("COUNTRY", "DE"));
    Evaluation evaluation = evaluate(List.of(fact), context("shop-1", "DE", "cat-1"), NOW);
    assertThat(evaluation.inScope()).isFalse();
    assertThat(evaluation.severity()).isEqualTo("BLOCKING");
    assertThat(evaluation.reasons()).extracting(BrandAuthorizationReason::code)
        .contains(REASON_NOT_YET_EFFECTIVE);
  }

  @Test
  @DisplayName("授权已停用 ⇒ 明确不覆盖，并给出「已停用」的原因")
  void disabled() {
    var fact = fact("auth-1", false, NOW.minusDays(30), NOW.plusDays(30), scopes("COUNTRY", "DE"));
    Evaluation evaluation = evaluate(List.of(fact), context("shop-1", "DE", "cat-1"), NOW);
    assertThat(evaluation.inScope()).isFalse();
    assertThat(evaluation.severity()).isEqualTo("BLOCKING");
    assertThat(evaluation.reasons()).extracting(BrandAuthorizationReason::code)
        .contains(REASON_DISABLED);
  }

  // ==================================================================
  // 范围
  // ==================================================================

  @Test
  @DisplayName("不覆盖店铺 ⇒ OUT_OF_SCOPE + 逐条原因（写明是哪条授权、哪个店铺）")
  void shopOutOfScope() {
    var fact = fact("auth-1", true, NOW.minusDays(1), NOW.plusDays(1), scopes("SHOP", "shop-9"));
    Evaluation evaluation = evaluate(List.of(fact), context("shop-1", "DE", "cat-1"), NOW);
    assertThat(evaluation.inScope()).isFalse();
    assertThat(evaluation.conclusion()).isEqualTo("OUT_OF_SCOPE");
    BrandAuthorizationReason reason = evaluation.reasons().get(0);
    assertThat(reason.code()).isEqualTo(REASON_SHOP_NOT_IN_SCOPE);
    assertThat(reason.message()).contains("shop-1");
    assertThat(reason.authorizationId()).isEqualTo("auth-1");
  }

  @Test
  @DisplayName("不覆盖国家 ⇒ OUT_OF_SCOPE + 逐条原因（写明缺哪个国家）")
  void countryOutOfScope() {
    var fact = fact("auth-1", true, NOW.minusDays(1), NOW.plusDays(1), scopes("COUNTRY", "US"));
    Evaluation evaluation = evaluate(List.of(fact), context("shop-1", "DE", "cat-1"), NOW);
    assertThat(evaluation.inScope()).isFalse();
    BrandAuthorizationReason reason = evaluation.reasons().get(0);
    assertThat(reason.code()).isEqualTo(REASON_COUNTRY_NOT_IN_SCOPE);
    assertThat(reason.message()).contains("DE");
  }

  @Test
  @DisplayName("🔴 多国店铺：授权只覆盖其中一个国家 ⇒ 仍然不覆盖（缺一个就是越界）")
  void multiCountryShopNeedsFullCoverage() {
    var fact = fact("auth-1", true, NOW.minusDays(1), NOW.plusDays(1), scopes("COUNTRY", "DE"));
    Evaluation evaluation = evaluate(List.of(fact), context("shop-1", null, "cat-1", "DE", "FR"), NOW);
    assertThat(evaluation.inScope()).isFalse();
    BrandAuthorizationReason reason = evaluation.reasons().get(0);
    assertThat(reason.code()).isEqualTo(REASON_COUNTRY_NOT_IN_SCOPE);
    assertThat(reason.message()).contains("FR");
  }

  @Test
  @DisplayName("多国店铺且授权覆盖全部国家 ⇒ IN_SCOPE")
  void multiCountryShopFullyCovered() {
    var fact =
        fact("auth-1", true, NOW.minusDays(1), NOW.plusDays(1), scopes("COUNTRY", "DE", "FR"));
    Evaluation evaluation = evaluate(List.of(fact), context("shop-1", null, "cat-1", "DE", "FR"), NOW);
    assertThat(evaluation.inScope()).isTrue();
  }

  @Test
  @DisplayName("不覆盖类目 ⇒ OUT_OF_SCOPE + 逐条原因")
  void categoryOutOfScope() {
    var fact = fact("auth-1", true, NOW.minusDays(1), NOW.plusDays(1), scopes("CATEGORY", "cat-9"));
    Evaluation evaluation = evaluate(List.of(fact), context("shop-1", "DE", "cat-1"), NOW);
    assertThat(evaluation.inScope()).isFalse();
    assertThat(evaluation.reasons().get(0).code()).isEqualTo(REASON_CATEGORY_NOT_IN_SCOPE);
  }

  // ==================================================================
  // 判不了（UNKNOWN）
  // ==================================================================

  @Test
  @DisplayName("🔴 缺店铺维度 ⇒ UNKNOWN（不是 IN_SCOPE，也不谎称越权）")
  void shopDimensionUnresolved() {
    var fact = fact("auth-1", true, NOW.minusDays(1), NOW.plusDays(1), scopes("SHOP", "shop-9"));
    Evaluation evaluation = evaluate(List.of(fact), context(null, "DE", "cat-1"), NOW);
    assertThat(evaluation.inScope()).isFalse();
    assertThat(evaluation.conclusion()).isEqualTo("UNKNOWN");
    assertThat(evaluation.severity()).isEqualTo("REVIEW");
    assertThat(evaluation.reasons()).extracting(BrandAuthorizationReason::code)
        .contains(REASON_SHOP_UNRESOLVED);
  }

  @Test
  @DisplayName("🔴 缺国家维度（没给国家且店铺没有可解析的国家）⇒ UNKNOWN")
  void countryDimensionUnresolved() {
    var fact = fact("auth-1", true, NOW.minusDays(1), NOW.plusDays(1), scopes("COUNTRY", "DE"));
    Evaluation evaluation = evaluate(List.of(fact), context("shop-1", null, "cat-1"), NOW);
    assertThat(evaluation.conclusion()).isEqualTo("UNKNOWN");
    assertThat(evaluation.reasons()).extracting(BrandAuthorizationReason::code)
        .contains(REASON_COUNTRY_UNRESOLVED);
  }

  @Test
  @DisplayName("🔴 缺类目维度 ⇒ UNKNOWN")
  void categoryDimensionUnresolved() {
    var fact = fact("auth-1", true, NOW.minusDays(1), NOW.plusDays(1), scopes("CATEGORY", "cat-9"));
    Evaluation evaluation = evaluate(List.of(fact), context("shop-1", "DE", null), NOW);
    assertThat(evaluation.conclusion()).isEqualTo("UNKNOWN");
    assertThat(evaluation.reasons()).extracting(BrandAuthorizationReason::code)
        .contains(REASON_CATEGORY_UNRESOLVED);
  }

  @Test
  @DisplayName("🔴 判不了时原因必须非空（没有原因的 false 不允许出现）")
  void unknownStillCarriesReasons() {
    var fact = fact("auth-1", true, NOW.minusDays(1), NOW.plusDays(1), scopes("CATEGORY", "cat-9"));
    Evaluation evaluation = evaluate(List.of(fact), context("shop-1", "DE", null), NOW);
    assertThat(evaluation.conclusion()).isEqualTo("UNKNOWN");
    assertThat(evaluation.reasons()).isNotEmpty();
    assertThat(evaluation.reasons()).allSatisfy(reason -> assertThat(reason.message()).isNotBlank());
  }

  // ==================================================================
  // 到期状态
  // ==================================================================

  @Test
  @DisplayName("到期状态五态")
  void expiryStates() {
    assertThat(expiryState(false, NOW.minusDays(1), NOW.plusDays(1), 1, NOW, 30))
        .isEqualTo("DISABLED");
    assertThat(expiryState(true, NOW.plusDays(1), NOW.plusDays(10), 10, NOW, 30))
        .isEqualTo("NOT_YET_EFFECTIVE");
    assertThat(expiryState(true, NOW.minusDays(10), NOW.minusDays(1), -1, NOW, 30))
        .isEqualTo("EXPIRED");
    assertThat(expiryState(true, NOW.minusDays(1), NOW.plusDays(5), 5, NOW, 30))
        .isEqualTo("EXPIRING");
    assertThat(expiryState(true, NOW.minusDays(1), NOW.plusDays(90), 90, NOW, 30))
        .isEqualTo("ACTIVE");
  }

  @Test
  @DisplayName("目标国家归一化：显式国家优先，小写也会被抬成大写")
  void targetCountriesNormalized() {
    assertThat(BrandAuthorizationRules.targetCountries(context("s", "de", null)))
        .containsExactly("DE");
    assertThat(BrandAuthorizationRules.targetCountries(context("s", null, null, "de", "FR")))
        .containsExactly("DE", "FR");
    assertThat(BrandAuthorizationRules.targetCountries(context("s", null, null))).isEmpty();
  }
}
