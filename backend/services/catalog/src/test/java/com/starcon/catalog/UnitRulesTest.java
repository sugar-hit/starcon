package com.starcon.catalog;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * 3.1.2.4 纯规则单测：单位码字符集、量纲词表、比例校验、换算精度与生效区间判定。
 *
 * <p>这些是不碰数据库就能判定的规则，穷举它们比等集成测试便宜得多——
 * 量纲不匹配要等到真正建换算行才拦，代价是错误信息离操作人十万八千里。
 */
class UnitRulesTest {

  @Test
  void unitCodeAcceptsChineseAndLatin() {
    // 单位码不是内部编码：'件' / '箱' / '千克' 都是真实数据，套 [A-Z0-9] 会把它们判成非法。
    assertThat(UnitRules.codeProblem("件")).isNull();
    assertThat(UnitRules.codeProblem("箱")).isNull();
    assertThat(UnitRules.codeProblem("千克")).isNull();
    assertThat(UnitRules.codeProblem("PCS")).isNull();
    assertThat(UnitRules.codeProblem("carton_12")).isNull();
    assertThat(UnitRules.codeProblem("a-b")).isNull();
  }

  @Test
  void unitCodeRejectsEmptySpacesAndOverlong() {
    assertThat(UnitRules.codeProblem("")).contains("不能为空");
    assertThat(UnitRules.codeProblem("   ")).contains("不能为空");
    assertThat(UnitRules.codeProblem(null)).contains("不能为空");
    assertThat(UnitRules.codeProblem("件 件")).contains("只能使用");
    // 33 位：超过 sku.base_unit varchar(32) 的上限。
    assertThat(UnitRules.codeProblem("a".repeat(33))).contains("1~32");
    assertThat(UnitRules.codeProblem("a".repeat(32))).isNull();
  }

  @Test
  void unitCodeRejectsLeadingPunctuation() {
    assertThat(UnitRules.codeProblem("-件")).contains("只能使用");
    assertThat(UnitRules.codeProblem("_a")).contains("只能使用");
    // 数字/中文/字母开头都合法，首字符只排除分隔符。
    assertThat(UnitRules.codeProblem("1件")).isNull();
  }

  @Test
  void ratioMustBeStrictlyPositive() {
    assertThat(UnitRules.ratioProblem(new BigDecimal("12"))).isNull();
    assertThat(UnitRules.ratioProblem(new BigDecimal("0.5"))).isNull();
    // 0 会让基础数量恒为 0，负数会让出库变成入库——两种都会真实打错账。
    assertThat(UnitRules.ratioProblem(BigDecimal.ZERO)).contains("大于 0");
    assertThat(UnitRules.ratioProblem(new BigDecimal("-1"))).contains("大于 0");
    assertThat(UnitRules.ratioProblem(null)).contains("不能为空");
  }

  @Test
  void ratioRejectsExcessPrecision() {
    // numeric(19,6)：整数 13 位 + 小数 6 位。
    assertThat(UnitRules.ratioProblem(new BigDecimal("1.123456"))).isNull();
    assertThat(UnitRules.ratioProblem(new BigDecimal("1.1234567"))).contains("6 位小数");
    assertThat(UnitRules.ratioProblem(new BigDecimal("12345678901234"))).contains("13 位");
  }

  @Test
  void baseQuantityUsesHalfUpNotTruncation() {
    // 1 箱 = 3 件，2.5 箱 = 7.5 件。截断会写成 7 件，账面少 0.5 件。
    assertThat(UnitRules.toBaseQuantity(new BigDecimal("2.5"), new BigDecimal("3")))
        .isEqualByComparingTo("7.500000");
    assertThat(UnitRules.toBaseQuantity(new BigDecimal("2.5"), new BigDecimal("3")).stripTrailingZeros())
        .isEqualByComparingTo("7.5");
    // 1 箱 = 12 件：最典型的整箱换算。
    assertThat(UnitRules.toBaseQuantity(new BigDecimal("3"), new BigDecimal("12")))
        .isEqualByComparingTo("36.000000");
  }

  @Test
  void effectiveWindowIsInclusiveOnBothEnds() {
    LocalDate from = LocalDate.parse("2024-01-01");
    LocalDate to = LocalDate.parse("2025-12-31");
    assertThat(effective(from, to, "2024-01-01")).isTrue();
    assertThat(effective(from, to, "2025-12-31")).isTrue();
    assertThat(effective(from, to, "2023-12-31")).isFalse();
    assertThat(effective(from, to, "2026-01-01")).isFalse();
    // 开放区间：effective_to 为空表示"至今仍生效"。
    assertThat(effective(from, null, "2099-01-01")).isTrue();
    assertThat(effective(from, null, "2023-12-31")).isFalse();
  }

  private static boolean effective(LocalDate from, LocalDate to, String at) {
    return UnitRules.effectiveOn(from, to, LocalDate.parse(at));
  }

  @Test
  void dimensionsAndRolesHaveChineseLabels() {
    assertThat(UnitRules.DIMENSIONS.keySet())
        .containsExactlyInAnyOrder("WEIGHT", "LENGTH", "VOLUME", "COUNT", "PACKAGE");
    assertThat(UnitRules.dimensionLabel("WEIGHT")).isEqualTo("重量");
    assertThat(UnitRules.dimensionLabel("COUNT")).isEqualTo("数量");
    // 未登记的枚举回显原值，不抛异常：界面上要能看到"这个值不在词表里"而不是崩掉。
    assertThat(UnitRules.dimensionLabel("MASS")).isEqualTo("MASS");
    assertThat(UnitRules.roleLabel("PURCHASE")).isEqualTo("采购单位");
    assertThat(UnitRules.roleLabel("BASE")).isEqualTo("基础单位");
  }

  @Test
  void baseRoleIsNotAConversionRow() {
    // 基础单位是 sku.base_unit 的属性，比例恒为 1，不占换算行。
    assertThat(UnitRules.ROLES).contains("BASE");
    assertThat(UnitRules.CONVERSION_ROLES).doesNotContain("BASE");
    assertThat(UnitRules.CONVERSION_ROLES)
        .containsExactlyInAnyOrder("PURCHASE", "SALE", "PACKAGING");
  }

  @Test
  void documentTypesCoverTheThreeDownstreamSystems() {
    // 06 采购 / 07 库存 / 08 拣货 是蓝图点名的三个下游。
    assertThat(UnitRules.DOCUMENT_TYPES).containsKeys("PURCHASE", "INBOUND", "OUTBOUND", "PICKING");
    assertThat(UnitRules.documentTypeLabel("PURCHASE")).isEqualTo("06 采购");
    assertThat(UnitRules.documentTypeLabel("PICKING")).isEqualTo("08 拣货");
    assertThat(UnitRules.documentTypeLabel("UNKNOWN")).isEqualTo("UNKNOWN");
  }

  @Test
  void displayHonoursUnitScaleAndClampsIt() {
    assertThat(UnitRules.display(new BigDecimal("12.5"), 2)).isEqualTo("12.50");
    assertThat(UnitRules.display(new BigDecimal("12.5"), 0)).isEqualTo("13");
    // 空值不画成 0。
    assertThat(UnitRules.display(null, 2)).isEqualTo("—");
    // 越界的 scale 夹到 0~6，不抛异常。
    assertThat(UnitRules.display(new BigDecimal("1"), -3)).isEqualTo("1");
    assertThat(UnitRules.display(new BigDecimal("1"), 99)).isEqualTo("1.000000");
  }

  @Test
  void normalizersAreNullSafeAndUpperCase() {
    assertThat(UnitRules.normalizeCode(null)).isEmpty();
    assertThat(UnitRules.normalizeCode("  件  ")).isEqualTo("件");
    assertThat(UnitRules.normalizeDimension("weight")).isEqualTo("WEIGHT");
    assertThat(UnitRules.normalizeRole(" purchase ")).isEqualTo("PURCHASE");
    assertThat(UnitRules.normalizeDocumentType("inbound")).isEqualTo("INBOUND");
    assertThat(UnitRules.normalizeDimension(null)).isEmpty();
  }
}
