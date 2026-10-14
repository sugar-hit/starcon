package com.starcon.catalog;

import static com.starcon.catalog.UnitGroupRules.*;
import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * 3.3.3.1 计量单位的纯规则单测：编码词表、名称校验、比例校验、生效区间、换算算式、 量纲一致性与拒绝原因码词表。
 *
 * <p>这些规则是"能在保存前判定的"那一批，所以穷举它们比集成测试更便宜也更快。 换算算式尤其要离线钉死：算错一次就是一批按错比例记完的库存账。
 */
class UnitGroupRulesTest {

  @Test
  void groupCodeAcceptsChineseAndRejectsBlankAndIllegalChars() {
    // 中文是业务词：运营就是这么叫「重量组」的，套 ASCII 词表会逼所有人用拼音。
    assertThat(codeProblem("重量组")).isNull();
    assertThat(codeProblem("WEIGHT_GROUP")).isNull();
    assertThat(codeProblem("重量组-1")).isNull();
    assertThat(codeProblem("")).contains("不能为空");
    assertThat(codeProblem("   ")).contains("不能为空");
    assertThat(codeProblem("重量 组")).contains("只能使用");
    assertThat(codeProblem("重量组!")).contains("只能使用");
    assertThat(codeProblem("重".repeat(33))).contains("最多 32");
  }

  @Test
  void groupNameAndNoteAreBounded() {
    assertThat(nameProblem("重量")).isNull();
    assertThat(nameProblem("  ")).contains("不能为空");
    assertThat(nameProblem("重".repeat(101))).contains("最多 100");
    assertThat(normalizeNote("  备注  ")).isEqualTo("备注");
  }

  @Test
  void ratioRejectsZeroNegativeAndOverPrecision() {
    assertThat(ratioProblem(new BigDecimal("1000"))).isNull();
    assertThat(ratioProblem(new BigDecimal("0.5"))).isNull();
    // 0 会让换算结果恒为 0，负数会让出库变成入库——两种都是真实打错账的错误。
    assertThat(ratioProblem(BigDecimal.ZERO)).contains("必须大于 0");
    assertThat(ratioProblem(new BigDecimal("-1"))).contains("必须大于 0");
    assertThat(ratioProblem(null)).contains("不能为空");
    // numeric(19,6)：6 位小数、整数部分 ≤13 位。超了会在插入时被 DB 截断或报错。
    assertThat(ratioProblem(new BigDecimal("1.1234567"))).contains("最多 6 位小数");
    assertThat(ratioProblem(new BigDecimal("12345678901234"))).contains("整数部分最多 13 位");
  }

  @Test
  void windowRejectsReversedRangeAndAcceptsOpenRange() {
    LocalDate from = LocalDate.of(2026, 1, 1);
    assertThat(windowProblem(from, null)).isNull(); // 开放区间：当前仍生效
    assertThat(windowProblem(from, from)).isNull();
    assertThat(windowProblem(from, from.plusDays(30))).isNull();
    assertThat(windowProblem(from, from.minusDays(1))).contains("不能早于");
    assertThat(windowProblem(null, null)).contains("不能为空");
  }

  @Test
  void convertUsesRatioChainFromAndToGroupBase() {
    // 组基础单位是克：1 千克 = 1000 克、1 磅 = 453.59237 克。
    // 2 磅 → 千克 = 2 × 453.59237 ÷ 1000 = 0.90718474 → 6 位小数 0.907185。
    BigDecimal pound = new BigDecimal("453.59237");
    BigDecimal kilo = new BigDecimal("1000");
    assertThat(UnitGroupRules.convert(new BigDecimal("2"), pound, kilo))
        .isEqualByComparingTo(new BigDecimal("0.907185"));
    // 反向：2 千克 → 磅 = 2 × 1000 ÷ 453.59237 = 4.409245… → 6 位小数。
    assertThat(UnitGroupRules.convert(new BigDecimal("2"), kilo, pound))
        .isEqualByComparingTo(new BigDecimal("4.409245"));
    // 同单位：比例恒为 1。
    assertThat(UnitGroupRules.convert(new BigDecimal("3"), kilo, kilo))
        .isEqualByComparingTo(new BigDecimal("3.000000"));
    // 基准单位侧比例恒为 1：500 克 → 千克 = 500 × 1 ÷ 1000 = 0.5。
    assertThat(UnitGroupRules.convert(new BigDecimal("500"), BigDecimal.ONE, kilo))
        .isEqualByComparingTo(new BigDecimal("0.500000"));
  }

  @Test
  void effectiveWindowIsHalfOpenInclusiveOnBothEnds() {
    LocalDate from = LocalDate.of(2026, 1, 1);
    LocalDate to = LocalDate.of(2026, 3, 31);
    assertThat(effectiveOn(from, to, from)).isTrue(); // 起始日当天即生效
    assertThat(effectiveOn(from, to, to)).isTrue(); // 截止日当天仍生效
    assertThat(effectiveOn(from, to, from.minusDays(1))).isFalse();
    assertThat(effectiveOn(from, to, to.plusDays(1))).isFalse();
    // 开放区间：从起始日往后的每一天都命中。
    assertThat(effectiveOn(from, null, from.plusYears(5))).isTrue();
    assertThat(effectiveOn(from, null, from.minusDays(1))).isFalse();
  }

  @Test
  void dimensionMismatchMessageNamesBothSidesAndTheirDimensions() {
    assertThat(sameDimension("WEIGHT", "WEIGHT")).isTrue();
    assertThat(sameDimension("WEIGHT", "COUNT")).isFalse();
    // null 量纲不算同量纲：判成「相同」会让未登记的单位悄悄进入组。
    assertThat(sameDimension(null, null)).isFalse();
    assertThat(sameDimension("WEIGHT", null)).isFalse();

    String message = dimensionMismatchMessage("件", "COUNT", "千克", "WEIGHT");
    assertThat(message).contains("件").contains("数量").contains("千克").contains("重量");
    assertThat(message).contains("不允许换算");
  }

  @Test
  void dimensionLabelsCoverEveryCodeInTheVocabulary() {
    assertThat(DIMENSIONS.keySet())
        .containsExactlyInAnyOrder("WEIGHT", "LENGTH", "VOLUME", "COUNT", "PACKAGE");
    assertThat(dimensionLabel("WEIGHT")).isEqualTo("重量");
    assertThat(dimensionLabel("UNKNOWN")).isEqualTo("UNKNOWN");
    // 量纲词表必须与 V8 的 unit_of_measure CHECK 完全一致：
    // 单位能在 SKU 侧换算，就一定要能进同量纲的单位组，反之亦然。
    assertThat(DIMENSIONS.keySet())
        .containsExactlyInAnyOrder(UnitRules.DIMENSIONS.keySet().toArray(new String[0]));
  }

  @Test
  void denyCodesAreDistinctAndAllLabelled() {
    // 原因码是对外契约：调用方按码走不同处理，所以每个码都要有且只有一个人类标签。
    assertThat(DENY_CODES)
        .containsExactlyInAnyOrder(
            "UNIT_UNREGISTERED",
            "UNIT_DISABLED",
            "GROUP_DISABLED",
            "DIMENSION_MISMATCH",
            "NOT_SAME_GROUP",
            "NO_EFFECTIVE_VERSION",
            "SELF_CONVERSION");
    for (String code : DENY_CODES) assertThat(denyLabel(code)).isNotEqualTo(code);
    assertThat(DENY_CODES).hasSameSizeAs(DENY_LABELS.keySet());
  }

  @Test
  void displayFollowsUnitScaleAndNeverRendersMissingAsZero() {
    // 「没有」不能画成「是 0」：把治理缺口画成 0，运营会去改本来正确的比例。
    assertThat(display(null, 2)).isEqualTo("—");
    assertThat(display(new BigDecimal("12.3456"), 2)).isEqualTo("12.35");
    assertThat(display(new BigDecimal("12.3456"), 0)).isEqualTo("12");
    // scale 越界不炸：调用方传进来的 scale 可能来自历史数据，一律钳到 0~6。
    assertThat(display(new BigDecimal("12.3456"), 99)).isEqualTo("12.345600");
    assertThat(display(new BigDecimal("12.3456"), -3)).isEqualTo("12");
  }

  @Test
  void normalizationIsStableAndSafeOnNull() {
    assertThat(normalizeCode(null)).isEmpty();
    assertThat(normalizeNote(null)).isEmpty();
    assertThat(normalizeUnitCode(null)).isEmpty();
    assertThat(normalizeDimension(null)).isEmpty();
    assertThat(normalizeDimension(" weight ")).isEqualTo("WEIGHT");
    assertThat(normalizeUnitCode(" 千克 ")).isEqualTo("千克");
  }
}
