package com.starcon.catalog;

import static com.starcon.catalog.PriceListPriorityModels.PriorityScopeType.*;
import static com.starcon.catalog.PriceListPriorityRules.*;
import static org.assertj.core.api.Assertions.*;

import com.starcon.catalog.PriceListPriorityModels.ConflictDimension;
import com.starcon.catalog.PriceListPriorityModels.PriorityRuleInput;
import com.starcon.catalog.PriceListPriorityRules.Rule;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 5.1.2.1 的**纯规则**单测（无 IO、无租户上下文）。
 *
 * <p>钉死的六件事：
 *
 * <ol>
 *   <li>规则归一化：GLOBAL 固定写 {@code *}、去重、排序、缺 GLOBAL 被拒。
 *   <li>specificity 顺序：站点 &gt; 店铺 &gt; 法人 &gt; 全局。
 *   <li>一个上下文里命中哪条规则（最具体的那条）。
 *   <li>同优先级 ⇒ 不可唯一解析；一边没排位 ⇒ 不算并列（它不参与解析）。
 *   <li>维度覆盖说明里「同 SKU」必须是**未覆盖**且带原因。
 * </ol>
 */
class PriceListPriorityRulesTest {

  @Test
  void 归一化把全局规则固定成星号并排序() {
    var normalized =
        normalizeRules(
            List.of(
                new PriorityRuleInput(SITE, "US", 3, "站点"),
                new PriorityRuleInput(GLOBAL, " 任意东西 ", 10, "全局"),
                new PriorityRuleInput(SHOP, "AMZ-US", 5, "店铺")));

    assertThat(normalized).hasSize(3);
    assertThat(normalized.get(0).scopeType()).isEqualTo(GLOBAL);
    assertThat(normalized.get(0).scopeRef()).isEqualTo("*");
    assertThat(normalized.get(1).scopeType()).isEqualTo(SHOP);
    assertThat(normalized.get(2).scopeType()).isEqualTo(SITE);
  }

  @Test
  void 归一化拒绝缺全局或重复上下文() {
    assertThatThrownBy(() -> normalizeRules(List.of(new PriorityRuleInput(SHOP, "AMZ-US", 1, ""))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("GLOBAL");
    assertThatThrownBy(
            () ->
                normalizeRules(
                    List.of(
                        new PriorityRuleInput(GLOBAL, "*", 1, ""),
                        new PriorityRuleInput(GLOBAL, "*", 2, ""))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("只能有一条 GLOBAL");
    assertThatThrownBy(
            () ->
                normalizeRules(
                    List.of(
                        new PriorityRuleInput(GLOBAL, "*", 1, ""),
                        new PriorityRuleInput(SHOP, "AMZ-US", 2, ""),
                        new PriorityRuleInput(SHOP, "amz-us", 3, ""))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("重复");
    assertThatThrownBy(() -> normalizeRules(List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("至少要有一条优先级规则");
  }

  @Test
  void specificity顺序是站点店铺法人全局() {
    assertThat(specificity(SITE)).isLessThan(specificity(SHOP));
    assertThat(specificity(SHOP)).isLessThan(specificity(LEGAL_ENTITY));
    assertThat(specificity(LEGAL_ENTITY)).isLessThan(specificity(GLOBAL));
  }

  @Test
  void 上下文命中最具体的那条规则() {
    var rules =
        List.of(
            new Rule(GLOBAL, "*", 10),
            new Rule(LEGAL_ENTITY, "US-HQ", 20),
            new Rule(SHOP, "AMZ-US", 30),
            new Rule(SITE, "US", 40));
    var ctx = new Context("AMZ-US", "US", "US-HQ");

    assertThat(effectiveRule(rules, ctx).scopeType()).isEqualTo(SITE);
    assertThat(effectiveRule(rules, ctx).priority()).isEqualTo(40);

    // 没有站点规则时退到店铺规则。
    var noSite =
        List.of(new Rule(GLOBAL, "*", 10), new Rule(SHOP, "AMZ-US", 30));
    assertThat(effectiveRule(noSite, ctx).scopeType()).isEqualTo(SHOP);

    // 什么都不匹配时退到全局。
    assertThat(effectiveRule(List.of(new Rule(GLOBAL, "*", 10)), ctx).scopeType()).isEqualTo(GLOBAL);
  }

  @Test
  void 上下文为空段时不参与匹配() {
    var rules = List.of(new Rule(SITE, "US", 40), new Rule(GLOBAL, "*", 10));
    var ctx = new Context("AMZ-US", null, null);
    assertThat(effectiveRule(rules, ctx).scopeType()).isEqualTo(GLOBAL);
  }

  @Test
  void 编码大小写不影响匹配() {
    var rules = List.of(new Rule(SHOP, "amz-us", 30));
    assertThat(effectiveRule(rules, new Context("AMZ-US", null, null)).scopeType()).isEqualTo(SHOP);
  }

  @Test
  void 同优先级才算并列一边没排位不算() {
    assertThat(tie(new Rule(GLOBAL, "*", 5), new Rule(SHOP, "AMZ-US", 5))).isTrue();
    assertThat(tie(new Rule(GLOBAL, "*", 5), new Rule(GLOBAL, "*", 6))).isFalse();
    // 一边没排位 ⇒ 不参与解析 ⇒ 不算并列。
    assertThat(tie(null, new Rule(GLOBAL, "*", 5))).isFalse();
    assertThat(tie(null, null)).isFalse();
  }

  @Test
  void 维度覆盖说明里同sku是未覆盖且带原因() {
    var coverage = dimensionCoverage();
    assertThat(coverage).hasSize(ConflictDimension.values().length);
    var sku =
        coverage.stream().filter(c -> c.dimension() == ConflictDimension.SKU).findFirst().orElseThrow();
    assertThat(sku.covered()).isFalse();
    assertThat(sku.detail()).contains("5.1.1.2").contains("price_list_item");
    for (var entry : coverage) {
      if (entry.dimension() == ConflictDimension.SKU) continue;
      assertThat(entry.covered()).as("%s 应当已覆盖", entry.dimension()).isTrue();
      assertThat(entry.detail()).isNotBlank();
    }
  }

  @Test
  void 同sku未覆盖说明讲清不是没有冲突() {
    assertThat(skuDimensionNote()).contains("未覆盖").contains("price_list_item");
    assertThat(skuDimensionNote()).contains("不是「没有冲突」");
  }
}
