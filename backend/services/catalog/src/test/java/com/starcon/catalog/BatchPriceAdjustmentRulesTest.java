package com.starcon.catalog;

import static com.starcon.catalog.BatchPriceAdjustmentModels.AdjustMode.*;
import static org.assertj.core.api.Assertions.*;

import com.starcon.catalog.BatchPriceAdjustmentModels.AdjustMode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 5.4.1.2 批量调价的**纯规则**断言（无 IO、无数据库）。
 *
 * <p>这些是页面上写着、导出里写着、数据库里存着的那几条口径 ——
 * 舍入精度、亏损判定、预览令牌的稳定性。
 * 它们必须是**纯函数**：没有租户上下文、没有数据库、没有时间依赖，
 * 所以每一条都能被单测钉死。
 */
class BatchPriceAdjustmentRulesTest {

  // ================================================================ 新价换算

  /** 固定金额：加与减都按同一个金额走，新价 2 位小数 HALF_UP。 */
  @Test
  void 固定金额按同一个金额加减且落两位小数() {
    assertThat(
            BatchPriceAdjustmentRules.newPrice(
                AdjustMode.FIXED_AMOUNT, new BigDecimal("100"), new BigDecimal("5"), null))
        .isEqualByComparingTo("105.00");
    assertThat(
            BatchPriceAdjustmentRules.newPrice(
                AdjustMode.FIXED_AMOUNT, new BigDecimal("100"), new BigDecimal("-20"), null))
        .isEqualByComparingTo("80.00");
    // 99.99 + 0.01 = 100.00（不是 99.999）。
    assertThat(
            BatchPriceAdjustmentRules.newPrice(
                AdjustMode.FIXED_AMOUNT, new BigDecimal("99.99"), new BigDecimal("0.01"), null))
        .isEqualByComparingTo("100.00");
    // 99.99 + 0.005 = 99.995 ⇒ HALF_UP 到 100.00（不是 99.99）。
    assertThat(
            BatchPriceAdjustmentRules.newPrice(
                AdjustMode.FIXED_AMOUNT, new BigDecimal("99.99"), new BigDecimal("0.005"), null))
        .isEqualByComparingTo("100.00");
  }

  /** 百分比：小数口径（0.1 = +10%），新价 2 位小数 HALF_UP。 */
  @Test
  void 百分比按小数口径换算且落两位小数() {
    assertThat(
            BatchPriceAdjustmentRules.newPrice(
                AdjustMode.PERCENT, new BigDecimal("100"), null, new BigDecimal("0.1")))
        .isEqualByComparingTo("110.00");
    assertThat(
            BatchPriceAdjustmentRules.newPrice(
                AdjustMode.PERCENT, new BigDecimal("100"), null, new BigDecimal("-0.2")))
        .isEqualByComparingTo("80.00");
    // 99.99 × 1.1 = 109.989 ⇒ 109.99。
    assertThat(
            BatchPriceAdjustmentRules.newPrice(
                AdjustMode.PERCENT, new BigDecimal("99.99"), null, new BigDecimal("0.1")))
        .isEqualByComparingTo("109.99");
    // 降 100% ⇒ 0，不是正价 ⇒ null（这一行调不了，由调用方跳过）。
    assertThat(
            BatchPriceAdjustmentRules.newPrice(
                AdjustMode.PERCENT, new BigDecimal("100"), null, BigDecimal.ONE.negate()))
        .isNull();
  }

  /** 没有旧价就没有新价（不是 0）。 */
  @Test
  void 没有旧价就没有新价() {
    assertThat(
            BatchPriceAdjustmentRules.newPrice(
                AdjustMode.FIXED_AMOUNT, null, new BigDecimal("5"), null))
        .isNull();
    assertThat(
            BatchPriceAdjustmentRules.newPrice(
                AdjustMode.PERCENT, null, null, new BigDecimal("0.1")))
        .isNull();
  }

  /** 按模板方式的新价由 5.3.2.1 给，本方法不参与（也不二次舍入）。 */
  @Test
  void 按模板方式本方法不参与换算() {
    assertThat(
            BatchPriceAdjustmentRules.newPrice(
                AdjustMode.TEMPLATE, new BigDecimal("100"), new BigDecimal("5"), null))
        .isNull();
  }

  /** 价差：新价 − 旧价，正 = 涨价，负 = 降价。 */
  @Test
  void 价差是新价减旧价() {
    assertThat(
            BatchPriceAdjustmentRules.delta(
                new BigDecimal("100"), new BigDecimal("110.00")))
        .isEqualByComparingTo("10.0000");
    assertThat(
            BatchPriceAdjustmentRules.delta(
                new BigDecimal("100"), new BigDecimal("80.00")))
        .isEqualByComparingTo("-20.0000");
    assertThat(BatchPriceAdjustmentRules.delta(new BigDecimal("100"), null)).isNull();
  }

  // ================================================================ 亏损判定

  /** 毛利额 &lt; 0 ⇒ 亏损；= 0 不是；null ⇒ 不可信（不是 false）。 */
  @Test
  void 亏损判定区分负数零与不可信() {
    assertThat(BatchPriceAdjustmentRules.lossFlag(new BigDecimal("-1"))).isTrue();
    assertThat(BatchPriceAdjustmentRules.lossFlag(BigDecimal.ZERO)).isFalse();
    assertThat(BatchPriceAdjustmentRules.lossFlag(new BigDecimal("1"))).isFalse();
    assertThat(BatchPriceAdjustmentRules.lossFlag(null)).isNull();
  }

  // ================================================================ 预览令牌

  /** 同一份内容 ⇒ 同一个令牌（不含随机量与时间戳）。 */
  @Test
  void 预览令牌对同一份内容稳定() {
    var canonical = "mode=PERCENT|amount=-|rate=0.1|sku=1";
    assertThat(BatchPriceAdjustmentRules.previewToken(canonical))
        .isEqualTo(BatchPriceAdjustmentRules.previewToken(canonical));
    assertThat(BatchPriceAdjustmentRules.previewToken(canonical))
        .isNotEqualTo(BatchPriceAdjustmentRules.previewToken(canonical + "|x"));
    assertThat(BatchPriceAdjustmentRules.previewToken(canonical)).hasSize(64);
  }

  /** 规范化：10.00 与 10 必须是同一个串（否则同一批目标会算出两个令牌）。 */
  @Test
  void 规范化让等值的金额算出同一个串() {
    assertThat(BatchPriceAdjustmentRules.canonicalNumber(new BigDecimal("10.00")))
        .isEqualTo(BatchPriceAdjustmentRules.canonicalNumber(new BigDecimal("10")));
    assertThat(BatchPriceAdjustmentRules.canonicalNumber(new BigDecimal("10.0000")))
        .isEqualTo("10");
    assertThat(BatchPriceAdjustmentRules.canonicalNumber(null)).isEqualTo("-");
  }

  // ================================================================ 口径常量

  /** 售价 2 位、内部金额 4 位、费率 6 位，统一 HALF_UP。 */
  @Test
  void 精度常量与舍入方式固定() {
    assertThat(BatchPriceAdjustmentRules.PRICE_SCALE).isEqualTo(2);
    assertThat(BatchPriceAdjustmentRules.MONEY_SCALE).isEqualTo(4);
    assertThat(BatchPriceAdjustmentRules.RATE_SCALE).isEqualTo(6);
    assertThat(BatchPriceAdjustmentRules.ROUNDING.name()).isEqualTo("HALF_UP");
    assertThat(BatchPriceAdjustmentRules.price(new BigDecimal("1.005")))
        .isEqualByComparingTo("1.01");
    assertThat(BatchPriceAdjustmentRules.money(new BigDecimal("1.00005")))
        .isEqualByComparingTo("1.0001");
    assertThat(BatchPriceAdjustmentRules.rate(new BigDecimal("0.1234565")))
        .isEqualByComparingTo("0.123457");
  }

  /** 店铺清单的规范编码：排序 + 去重 ⇒ 同一批目标两次算出同一个串。 */
  @Test
  void 店铺清单排序去重后稳定() {
    var a = List.of(new String[] {"AMAZON", "US"}, new String[] {"AMAZON", "CA"});
    var b = List.of(new String[] {"AMAZON", "CA"}, new String[] {"AMAZON", "US"});
    assertThat(BatchPriceAdjustmentRules.shopCodes(a))
        .isEqualTo(BatchPriceAdjustmentRules.shopCodes(b))
        .isEqualTo("AMAZON:CA,AMAZON:US");
    assertThat(BatchPriceAdjustmentRules.shopCode("AMAZON", "US")).isEqualTo("AMAZON:US");
  }

  /** 平台码 / 站点码归一化：去空白 + 大写。 */
  @Test
  void 码归一化去空白并大写() {
    assertThat(BatchPriceAdjustmentRules.code(" amazon ")).isEqualTo("AMAZON");
    assertThat(BatchPriceAdjustmentRules.code("  ")).isNull();
    assertThat(BatchPriceAdjustmentRules.code(null)).isNull();
    assertThat(BatchPriceAdjustmentRules.scope("  Home ")).isEqualTo("Home");
    assertThat(BatchPriceAdjustmentRules.scope(null)).isEmpty();
  }
}
