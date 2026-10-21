package com.starcon.catalog;

import static org.assertj.core.api.Assertions.*;

import com.starcon.catalog.PlatformPriceSyncModels.PlatformPriceRuleOutcome;
import com.starcon.catalog.PlatformPriceSyncModels.PlatformPriceRuleType;
import com.starcon.catalog.PlatformPriceSyncRules.ComparisonPrice;
import com.starcon.catalog.PlatformPriceSyncRules.Rule;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 5.4.2.1 平台价格同步的**纯规则**单测（无 IO、无租户上下文）。
 *
 * <p>规则引擎是本模块最容易被改坏的地方 —— 它决定「这个价推上去平台会怎么处置」。
 * 所以把三条规则各自的判定与边界单独钉死：
 *
 * <ul>
 *   <li>最低价：低于门槛 ⇒ 拒收；等于门槛 ⇒ 通过（门槛是「不低于」）；
 *   <li>自动比价：超出容差 ⇒ 压到对比价；容差之内 ⇒ 通过；
 *   <li>多站点联动：越出比例带 ⇒ 拉到带边界；带内 ⇒ 通过；
 *   <li>🔴 判不出来 ≠ 判成通过：对比价缺失 / 币种不同 ⇒ 该规则**不参与**并留下说明；
 *   <li>🔴 拒收优先于改价：最低价是硬门槛，命中它就不再谈「改成多少」。
 * </ul>
 */
class PlatformPriceSyncRulesTest {

  private static final UUID MIN_PRICE = UUID.randomUUID();
  private static final UUID AUTO_COMPARE = UUID.randomUUID();
  private static final UUID MULTI_SITE = UUID.randomUUID();

  private static Rule minPrice(String platform, String site, String minPrice, String currency) {
    return new Rule(
        MIN_PRICE,
        PlatformPriceRuleType.MIN_PRICE,
        platform,
        site,
        null,
        new BigDecimal(minPrice),
        currency,
        "",
        "",
        null,
        "",
        null,
        null,
        100);
  }

  private static Rule autoCompare(String platform, String site, String compare, String gap) {
    return new Rule(
        AUTO_COMPARE,
        PlatformPriceRuleType.AUTO_COMPARE,
        platform,
        site,
        null,
        null,
        null,
        compare,
        "US",
        new BigDecimal(gap),
        "",
        null,
        null,
        100);
  }

  private static Rule multiSite(String platform, String site, String reference, String low, String high) {
    return new Rule(
        MULTI_SITE,
        PlatformPriceRuleType.MULTI_SITE_LINK,
        platform,
        site,
        null,
        null,
        null,
        "",
        "",
        null,
        reference,
        new BigDecimal(low),
        new BigDecimal(high),
        100);
  }

  // ================================================================ 作用域

  @Test
  void 规则只作用于自己的平台站点与SKU() {
    var rule = minPrice("AMAZON", "US", "100", "USD");
    assertThat(PlatformPriceSyncRules.applies(rule, "AMAZON", "US", UUID.randomUUID())).isTrue();
    // 站点级规则只作用于该站点。
    assertThat(PlatformPriceSyncRules.applies(rule, "AMAZON", "DE", UUID.randomUUID())).isFalse();
    // 平台不同不适用。
    assertThat(PlatformPriceSyncRules.applies(rule, "SHOPIFY", "US", UUID.randomUUID())).isFalse();

    // 平台级规则（site_code 为空）作用于该平台全部站点。
    var platformWide = minPrice("AMAZON", "", "100", "USD");
    assertThat(PlatformPriceSyncRules.applies(platformWide, "AMAZON", "DE", UUID.randomUUID())).isTrue();

    // SKU 级规则只作用于该 SKU。
    UUID sku = UUID.randomUUID();
    var skuScoped =
        new Rule(
            MIN_PRICE,
            PlatformPriceRuleType.MIN_PRICE,
            "AMAZON",
            "US",
            sku,
            new BigDecimal("100"),
            "USD",
            "",
            "",
            null,
            "",
            null,
            null,
            100);
    assertThat(PlatformPriceSyncRules.applies(skuScoped, "AMAZON", "US", sku)).isTrue();
    assertThat(PlatformPriceSyncRules.applies(skuScoped, "AMAZON", "US", UUID.randomUUID())).isFalse();
  }

  // ================================================================ 最低价

  @Test
  void 最低价低于门槛判拒收且等于门槛判通过() {
    var below =
        PlatformPriceSyncRules.evaluate(
            new BigDecimal("80.0000"),
            "USD",
            "AMAZON",
            "US",
            UUID.randomUUID(),
            List.of(minPrice("AMAZON", "US", "100.0000", "USD")),
            Map.of());
    assertThat(below.outcome()).isEqualTo(PlatformPriceRuleOutcome.REJECTED);
    assertThat(below.ruleId()).isEqualTo(MIN_PRICE);
    assertThat(below.predictedPrice()).isNull();
    assertThat(below.reasonCode()).isEqualTo("BELOW_MIN_PRICE");
    assertThat(below.reasonMessage()).contains("100.0000");

    // 门槛是「不低于」：正好等于门槛 ⇒ 通过。
    var atLimit =
        PlatformPriceSyncRules.evaluate(
            new BigDecimal("100.0000"),
            "USD",
            "AMAZON",
            "US",
            UUID.randomUUID(),
            List.of(minPrice("AMAZON", "US", "100.0000", "USD")),
            Map.of());
    assertThat(atLimit.outcome()).isEqualTo(PlatformPriceRuleOutcome.ACCEPTED);
    assertThat(atLimit.predictedPrice()).isEqualByComparingTo("100.0000");
  }

  @Test
  void 最低价币种不同不参与判定也不折算() {
    var decision =
        PlatformPriceSyncRules.evaluate(
            new BigDecimal("80.0000"),
            "USD",
            "AMAZON",
            "US",
            UUID.randomUUID(),
            List.of(minPrice("AMAZON", "US", "100.0000", "EUR")),
            Map.of());
    assertThat(decision.outcome()).isEqualTo(PlatformPriceRuleOutcome.ACCEPTED);
    // 🔴 判不出来 ≠ 判成通过：必须留下「为什么没参与」。
    assertThat(decision.notes()).isNotEmpty();
    assertThat(String.join("；", decision.notes())).contains("币种");
  }

  // ================================================================ 自动比价

  @Test
  void 自动比价超出容差判平台压价() {
    var decision =
        PlatformPriceSyncRules.evaluate(
            new BigDecimal("120.0000"),
            "USD",
            "AMAZON",
            "US",
            UUID.randomUUID(),
            List.of(autoCompare("AMAZON", "US", "SHOPIFY", "0.05")),
            Map.of(
                PlatformPriceSyncRules.key("SHOPIFY", "US"),
                new ComparisonPrice("USD", new BigDecimal("100.0000"))));
    assertThat(decision.outcome()).isEqualTo(PlatformPriceRuleOutcome.ADJUSTED);
    assertThat(decision.predictedPrice()).isEqualByComparingTo("100.0000");
    assertThat(decision.ruleId()).isEqualTo(AUTO_COMPARE);
  }

  @Test
  void 自动比价在容差之内判通过() {
    // 100 * 1.05 = 105 ⇒ 104 在容差之内。
    var decision =
        PlatformPriceSyncRules.evaluate(
            new BigDecimal("104.0000"),
            "USD",
            "AMAZON",
            "US",
            UUID.randomUUID(),
            List.of(autoCompare("AMAZON", "US", "SHOPIFY", "0.05")),
            Map.of(
                PlatformPriceSyncRules.key("SHOPIFY", "US"),
                new ComparisonPrice("USD", new BigDecimal("100.0000"))));
    assertThat(decision.outcome()).isEqualTo(PlatformPriceRuleOutcome.ACCEPTED);
    assertThat(decision.predictedPrice()).isEqualByComparingTo("104.0000");
  }

  @Test
  void 自动比价取不到对比价时不参与判定() {
    var decision =
        PlatformPriceSyncRules.evaluate(
            new BigDecimal("120.0000"),
            "USD",
            "AMAZON",
            "US",
            UUID.randomUUID(),
            List.of(autoCompare("AMAZON", "US", "SHOPIFY", "0.05")),
            Map.of());
    assertThat(decision.outcome()).isEqualTo(PlatformPriceRuleOutcome.ACCEPTED);
    assertThat(String.join("；", decision.notes())).contains("对比价");
  }

  // ================================================================ 多站点联动

  @Test
  void 多站点联动越界判拉回带边界() {
    var prices =
        Map.of(
            PlatformPriceSyncRules.key("AMAZON", "DE"),
            new ComparisonPrice("USD", new BigDecimal("100.0000")));
    // 高于上限 110 ⇒ 拉到 110。
    var high =
        PlatformPriceSyncRules.evaluate(
            new BigDecimal("120.0000"),
            "USD",
            "AMAZON",
            "US",
            UUID.randomUUID(),
            List.of(multiSite("AMAZON", "US", "DE", "0.9", "1.1")),
            prices);
    assertThat(high.outcome()).isEqualTo(PlatformPriceRuleOutcome.ADJUSTED);
    assertThat(high.predictedPrice()).isEqualByComparingTo("110.0000");

    // 低于下限 90 ⇒ 拉到 90。
    var low =
        PlatformPriceSyncRules.evaluate(
            new BigDecimal("80.0000"),
            "USD",
            "AMAZON",
            "US",
            UUID.randomUUID(),
            List.of(multiSite("AMAZON", "US", "DE", "0.9", "1.1")),
            prices);
    assertThat(low.outcome()).isEqualTo(PlatformPriceRuleOutcome.ADJUSTED);
    assertThat(low.predictedPrice()).isEqualByComparingTo("90.0000");

    // 带内 ⇒ 通过。
    var inside =
        PlatformPriceSyncRules.evaluate(
            new BigDecimal("105.0000"),
            "USD",
            "AMAZON",
            "US",
            UUID.randomUUID(),
            List.of(multiSite("AMAZON", "US", "DE", "0.9", "1.1")),
            prices);
    assertThat(inside.outcome()).isEqualTo(PlatformPriceRuleOutcome.ACCEPTED);
  }

  @Test
  void 多站点联动参照价币种不同不参与判定() {
    var decision =
        PlatformPriceSyncRules.evaluate(
            new BigDecimal("120.0000"),
            "USD",
            "AMAZON",
            "US",
            UUID.randomUUID(),
            List.of(multiSite("AMAZON", "US", "DE", "0.9", "1.1")),
            Map.of(
                PlatformPriceSyncRules.key("AMAZON", "DE"),
                new ComparisonPrice("EUR", new BigDecimal("100.0000"))));
    assertThat(decision.outcome()).isEqualTo(PlatformPriceRuleOutcome.ACCEPTED);
    assertThat(String.join("；", decision.notes())).contains("币种");
  }

  // ================================================================ 组合

  @Test
  void 拒收优先于改价() {
    var decision =
        PlatformPriceSyncRules.evaluate(
            new BigDecimal("80.0000"),
            "USD",
            "AMAZON",
            "US",
            UUID.randomUUID(),
            List.of(
                minPrice("AMAZON", "US", "100.0000", "USD"),
                autoCompare("AMAZON", "US", "SHOPIFY", "0.05")),
            Map.of(
                PlatformPriceSyncRules.key("SHOPIFY", "US"),
                new ComparisonPrice("USD", new BigDecimal("100.0000"))));
    // 最低价是硬门槛：平台根本不收这个价，谈不上「改成多少」。
    assertThat(decision.outcome()).isEqualTo(PlatformPriceRuleOutcome.REJECTED);
    assertThat(decision.predictedPrice()).isNull();
  }

  @Test
  void 没有规则时按期望价原样推送() {
    var decision =
        PlatformPriceSyncRules.evaluate(
            new BigDecimal("120.0000"),
            "USD",
            "AMAZON",
            "US",
            UUID.randomUUID(),
            List.of(),
            Map.of());
    assertThat(decision.outcome()).isEqualTo(PlatformPriceRuleOutcome.NO_RULE);
    assertThat(decision.predictedPrice()).isEqualByComparingTo("120.0000");
    assertThat(decision.ruleId()).isNull();
  }

  @Test
  void 规则参数不齐备一律被拒() {
    assertThatThrownBy(
            () ->
                PlatformPriceSyncRules.validate(
                    PlatformPriceRuleType.MIN_PRICE, null, "USD", null, null, null, null, null))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThatThrownBy(
            () ->
                PlatformPriceSyncRules.validate(
                    PlatformPriceRuleType.MIN_PRICE, new BigDecimal("10"), "usd", null, null, null, null, null))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThatThrownBy(
            () ->
                PlatformPriceSyncRules.validate(
                    PlatformPriceRuleType.AUTO_COMPARE, null, null, "", new BigDecimal("0.05"), null, null, null))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThatThrownBy(
            () ->
                PlatformPriceSyncRules.validate(
                    PlatformPriceRuleType.MULTI_SITE_LINK, null, null, null, null, "", null, null))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    // 上限比例不能小于下限比例。
    assertThatThrownBy(
            () ->
                PlatformPriceSyncRules.validate(
                    PlatformPriceRuleType.MULTI_SITE_LINK,
                    null,
                    null,
                    null,
                    null,
                    "DE",
                    new BigDecimal("1.1"),
                    new BigDecimal("0.9")))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }
}
