package com.starcon.catalog;

import static com.starcon.catalog.PricingTemplateModels.AdjustmentType.INVENTORY_TURNOVER;
import static com.starcon.catalog.PricingTemplateModels.AdjustmentType.SITE_DIFFERENTIAL;
import static org.assertj.core.api.Assertions.*;

import com.starcon.catalog.PricingTemplateModels.AdjustmentType;
import com.starcon.catalog.PricingTemplateRules.AdjustmentSpec;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 5.3.2.1 定价模板：**纯算术**部分的单测（无 IO、无数据库）。
 *
 * <p>覆盖蓝图点名的三种基准方法与两类调整项的算术口径：
 *
 * <ul>
 *   <li>成本加成：建议价 = 加成基数 × (1 + 加成率)；
 *   <li>固定毛利：有效目标毛利率 = 基准率 + 站点增量（取值域检查）；
 *   <li>竞品跟随：建议价 = 竞品基准价 × (1 + 跟随率)（跟随率可为负）；
 *   <li>按站点差异化：只命中目标站点，且**加在基准率上**（不是乘在价格上）；
 *   <li>按库存周转调整：可售天数超过阈值才打折，折扣**乘在建议价上**；
 *   <li>缺项 ⇒ 返回 null（不给价），而不是给一个 0 或一个编出来的数。
 * </ul>
 */
class PricingTemplateRulesTest {

  private static AdjustmentSpec siteDelta(String siteCode, String delta) {
    return new AdjustmentSpec(
        SITE_DIFFERENTIAL, siteCode, new BigDecimal(delta), null, null);
  }

  private static AdjustmentSpec turnover(String threshold, String discount) {
    return new AdjustmentSpec(
        INVENTORY_TURNOVER, null, null, new BigDecimal(threshold), new BigDecimal(discount));
  }

  // ================================================================ 成本加成

  @Test
  void 成本加成的建议价等于基数乘以一加加成率() {
    var price = PricingTemplateRules.costPlusPrice(new BigDecimal("21"), new BigDecimal("0.30"));
    assertThat(price).isEqualByComparingTo("27.3000");
    // 精度：金额 4 位、费率 6 位，HALF_UP。
    assertThat(price.scale()).isEqualTo(4);
  }

  @Test
  void 成本加成在加成基数为空时不给价() {
    assertThat(PricingTemplateRules.costPlusPrice(null, new BigDecimal("0.3"))).isNull();
    assertThat(PricingTemplateRules.costPlusPrice(BigDecimal.ZERO, new BigDecimal("0.3"))).isNull();
  }

  @Test
  void 成本加成在加成率让价格变成非正数时不给价() {
    assertThat(PricingTemplateRules.costPlusPrice(new BigDecimal("21"), new BigDecimal("-1"))).isNull();
    assertThat(PricingTemplateRules.costPlusPrice(new BigDecimal("21"), new BigDecimal("-1.5"))).isNull();
  }

  // ================================================================ 竞品跟随

  @Test
  void 竞品跟随的建议价等于竞品价乘以一加跟随率() {
    assertThat(PricingTemplateRules.competitorPrice(new BigDecimal("100"), new BigDecimal("-0.05")))
        .isEqualByComparingTo("95.0000");
  }

  @Test
  void 竞品跟随在竞品价缺失时不给价() {
    assertThat(PricingTemplateRules.competitorPrice(null, new BigDecimal("0"))).isNull();
    assertThat(PricingTemplateRules.competitorPrice(BigDecimal.ZERO, new BigDecimal("0"))).isNull();
  }

  @Test
  void 竞品跟随在跟随率让价格变成非正数时不给价() {
    assertThat(PricingTemplateRules.competitorPrice(new BigDecimal("100"), new BigDecimal("-1")))
        .isNull();
  }

  // ================================================================ 站点差异化

  @Test
  void 站点差异化只命中目标站点() {
    var adjustments = List.of(siteDelta("US", "0.05"));
    var hit = PricingTemplateRules.siteDifferential(adjustments, "US");
    assertThat(hit.applied()).isTrue();
    assertThat(hit.rateDelta()).isEqualByComparingTo("0.050000");
    assertThat(hit.reason()).contains("US");

    var miss = PricingTemplateRules.siteDifferential(adjustments, "DE");
    assertThat(miss.applied()).isFalse();
    assertThat(miss.rateDelta()).isNull();
    assertThat(miss.reason()).contains("DE");
  }

  @Test
  void 站点差异化在目标站点大小写不同时仍然命中() {
    var hit = PricingTemplateRules.siteDifferential(List.of(siteDelta("US", "0.05")), "us");
    assertThat(hit.applied()).isTrue();
  }

  @Test
  void 站点差异化在没有目标店铺时不命中() {
    var effect = PricingTemplateRules.siteDifferential(List.of(siteDelta("US", "0.05")), null);
    assertThat(effect.applied()).isFalse();
    assertThat(effect.reason()).contains("目标店铺");
  }

  @Test
  void 站点增量加在基准率上而不是乘在价格上() {
    var effective = PricingTemplateRules.effectiveRate(new BigDecimal("0.25"), new BigDecimal("0.05"));
    assertThat(effective).isEqualByComparingTo("0.300000");
    // 加法：0.25 + 0.05 = 0.30（乘法会得到 0.2625）。
    assertThat(effective).isNotEqualByComparingTo("0.262500");
  }

  // ================================================================ 库存周转

  @Test
  void 库存周转在可售天数超过阈值时打折() {
    var effect = PricingTemplateRules.inventoryTurnover(List.of(turnover("90", "0.10")), new BigDecimal("120"));
    assertThat(effect.applied()).isTrue();
    assertThat(effect.discountRate()).isEqualByComparingTo("0.100000");
    assertThat(effect.reason()).contains("滞销");
  }

  @Test
  void 库存周转在可售天数未超过阈值时不打折() {
    var effect = PricingTemplateRules.inventoryTurnover(List.of(turnover("90", "0.10")), new BigDecimal("30"));
    assertThat(effect.applied()).isFalse();
    assertThat(effect.discountRate()).isNull();
    assertThat(effect.reason()).contains("未超过阈值");
  }

  @Test
  void 库存周转在可售天数缺失时不猜() {
    var effect = PricingTemplateRules.inventoryTurnover(List.of(turnover("90", "0.10")), null);
    assertThat(effect.applied()).isFalse();
    assertThat(effect.discountRate()).isNull();
    assertThat(effect.reason()).contains("可售天数");
  }

  @Test
  void 库存周转的折扣乘在建议价上() {
    var price = PricingTemplateRules.applyDiscount(new BigDecimal("35"), new BigDecimal("0.10"));
    assertThat(price).isEqualByComparingTo("31.5000");
    // 折扣率为 0 ⇒ 原价（不是 null）。
    assertThat(PricingTemplateRules.applyDiscount(new BigDecimal("35"), BigDecimal.ZERO))
        .isEqualByComparingTo("35.0000");
    assertThat(PricingTemplateRules.applyDiscount(new BigDecimal("35"), null))
        .isEqualByComparingTo("35.0000");
  }

  @Test
  void 折扣率超过百分之百时不给价() {
    assertThat(PricingTemplateRules.applyDiscount(new BigDecimal("35"), new BigDecimal("1.5"))).isNull();
  }

  // ================================================================ 其它

  @Test
  void 空的调整项列表不会炸() {
    assertThat(PricingTemplateRules.siteDifferential(null, "US").applied()).isFalse();
    assertThat(PricingTemplateRules.siteDifferential(List.of(), "US").applied()).isFalse();
    assertThat(PricingTemplateRules.inventoryTurnover(null, new BigDecimal("10")).applied()).isFalse();
    assertThat(PricingTemplateRules.inventoryTurnover(List.of(), new BigDecimal("10")).applied()).isFalse();
  }

  @Test
  void 百分比展示口径() {
    assertThat(PricingTemplateRules.percentText(new BigDecimal("0.25"))).isEqualTo("25%");
    assertThat(PricingTemplateRules.percentText(new BigDecimal("-0.05"))).isEqualTo("-5%");
    assertThat(PricingTemplateRules.percentText(null)).isEqualTo("—");
  }

  @Test
  void 调整项类型只有两种() {
    assertThat(AdjustmentType.values()).hasSize(2);
  }
}
