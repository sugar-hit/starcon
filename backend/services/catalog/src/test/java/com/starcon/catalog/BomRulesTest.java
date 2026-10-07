package com.starcon.catalog;

import static org.assertj.core.api.Assertions.*;

import com.starcon.catalog.BomRules;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * 3.5.2.1 BOM 版本管理的纯规则测试。
 *
 * <p>本文件把 BOM <b>自己</b>钉死的两条规则逐条钉死（生效区间 R1–R5 是转发 3.5.1.1 的，
 * 只做口径一致性的回归断言，不在这里重复证明 —— 那属于 BundleRulesTest）：
 *
 * <ul>
 *   <li><b>R6 损耗率是百分数</b>：{@code 5} = 5%，且上界 100% 有明确理由；
 *   <li><b>R7 单层需求量</b>：{@code unit × qty × (1 + rate/100)}，6 位小数 HALF_UP，不取整。
 * </ul>
 *
 * <p>另有几条是「BOM 相对套装多出来的约束」：单位用量必须 &gt; 0、成品数量必须为正整数。
 */
class BomRulesTest {

  private static BigDecimal bd(String value) {
    return new BigDecimal(value);
  }

  // ================================================================ R6 损耗率

  @Test
  void scrapRateIsAPercentageNotARatio() {
    // R6 的核心：5 是 5%，不是 500%。
    assertThat(BomRules.scrapRateProblem(bd("5"))).isNull();
    assertThat(BomRules.scrapRateProblem(bd("0"))).isNull();
    assertThat(BomRules.scrapRateProblem(bd("0.5"))).isNull();
    assertThat(BomRules.scrapRateProblem(bd("100"))).isNull();
    // 比率写法（0.05 = 5%）在本系统里是「0.05%」——不是被拒，而是就是 0.05% 这个意思。
    // 这一点必须由 R7 的算式自证：见 requiredQuantityUsesPercentageSemantics。
  }

  @Test
  void scrapRateRejectsNegativeAndAboveOneHundred() {
    assertThat(BomRules.scrapRateProblem(bd("-0.1"))).contains("不能是负数");
    assertThat(BomRules.scrapRateProblem(bd("100.0001"))).contains("不能超过 100%");
    // 上界文案要解释「为什么 100% 也不行」，否则运营会以为系统抽风。
    assertThat(BomRules.scrapRateProblem(bd("150")))
        .contains("100% 损耗等于这个组件一丁点都用不上")
        .contains("领料 0 件");
  }

  @Test
  void scrapRateRejectsNullAndTooManyDecimals() {
    assertThat(BomRules.scrapRateProblem(null)).contains("不能为空").contains("无损耗请填 0");
    assertThat(BomRules.scrapRateProblem(bd("1.23456"))).contains("最多 4 位小数");
  }

  @Test
  void scrapRateBoundsMatchTheDatabaseCheck() {
    // 与 V25 的 numeric(7,4) + CHECK(scrap_rate >= 0 AND scrap_rate <= 100) 同口径。
    assertThat(BomRules.SCRAP_MAX).isEqualByComparingTo("100");
    assertThat(BomRules.SCRAP_SCALE).isEqualTo(4);
  }

  // ================================================================ R7 需求量

  @Test
  void requiredQuantityUsesPercentageSemantics() {
    // 1 个成品需要 2 个组件，损耗 5%：需求量 = 2 × 1 × 1.05 = 2.1
    // 如果按比率读（0.05），这里会是 2.1 × …… 不对，是 2 × 1 × 0.05 = 0.1。
    // 两个答案差 21 倍，而错的那个不报错 —— 这就是 R6 必须钉死的原因。
    assertThat(
            BomRules.requiredQuantity(bd("2"), bd("5"), bd("1")))
        .isEqualByComparingTo("2.100000");
  }

  @Test
  void requiredQuantityScalesWithOutputQuantity() {
    // 产 100 个，每个要 0.5 米，损耗 3%：0.5 × 100 × 1.03 = 51.5
    assertThat(
            BomRules.requiredQuantity(bd("0.5"), bd("3"), bd("100")))
        .isEqualByComparingTo("51.500000");
  }

  @Test
  void requiredQuantityWithZeroScrapIsPlainMultiplication() {
    assertThat(
            BomRules.requiredQuantity(bd("2.5"), bd("0"), bd("4")))
        .isEqualByComparingTo("10.000000");
    // 损耗率缺省按 0 处理：委外加工里「不损耗」是绝大多数情况，必填只会让人填一堆 0。
    assertThat(
            BomRules.requiredQuantity(bd("2.5"), null, bd("4")))
        .isEqualByComparingTo("10.000000");
  }

  @Test
  void requiredQuantityKeepsSixDecimalsAndRoundsHalfUp() {
    // 3 × 1 × 1.12345 = 3.37035 → 6 位小数正好放下，不进位也不截断。
    assertThat(
            BomRules.requiredQuantity(bd("3"), bd("12.345"), bd("1")))
        .isEqualByComparingTo("3.370350");
    // 第 7 位是 5 → HALF_UP 进位。若换成 HALF_EVEN 或截断，
    // 组装单领出来的料会与采购建议算出的量差 0.000001 —— 数字对不上就会被质疑整套系统。
    assertThat(BomRules.requiredQuantity(bd("1"), bd("0.000001"), bd("0.000001")).scale())
        .isEqualTo(6);
  }

  @Test
  void requiredQuantityDoesNotRoundToWholeUnits() {
    // R7：结果是精确需求量，不取整。取整是 3.5.2.2 与 06 的决定。
    BigDecimal required = BomRules.requiredQuantity(bd("1"), bd("50"), bd("3"));
    assertThat(required).isEqualByComparingTo("4.500000");
    assertThat(required.stripTrailingZeros().scale()).isGreaterThan(0);
  }

  @Test
  void requiredQuantityReturnsNullWhenAnInputIsMissing() {
    // null 不是 0：把「没填单位用量」当成 0 会让需求量静默变成 0，
    // 而 0 在组装单上读起来就是「这个组件不用领」。
    assertThat(BomRules.requiredQuantity(null, bd("5"), bd("1"))).isNull();
    assertThat(BomRules.requiredQuantity(bd("1"), bd("5"), null)).isNull();
  }

  @Test
  void requiredQuantityNeverUsesFloatingPoint() {
    // 0.1 + 0.2 那一类 double 误差在这里是「领出来的料比算出来的多几件」。
    BigDecimal result = BomRules.requiredQuantity(bd("0.1"), bd("0"), bd("3"));
    assertThat(result).isEqualByComparingTo("0.300000");
    assertThat(result.toPlainString()).doesNotContain("E").doesNotContain("0.3000000000");
  }

  // ================================================================ 单位用量

  @Test
  void unitQuantityMustBePositive() {
    assertThat(BomRules.quantityProblem(null)).contains("不能为空");
    assertThat(BomRules.quantityProblem(bd("0"))).contains("必须大于 0");
    assertThat(BomRules.quantityProblem(bd("-1"))).contains("必须大于 0");
    assertThat(BomRules.quantityProblem(bd("0.000001"))).isNull();
  }

  @Test
  void unitQuantityAcceptsFractionalValuesAndComparesNumerically() {
    // 生产型卖家的组件是「0.5 米面料 + 2 颗螺丝」，int 会逼运营写成 1 件。
    assertThat(BomRules.quantityProblem(bd("0.5"))).isNull();
    assertThat(BomRules.quantityProblem(bd("1"))).isNull();
    // 用 compareTo 而不是 equals：1 与 1.0 是同一个数，不能误拒。
    assertThat(BomRules.quantityProblem(bd("1.0"))).isNull();
  }

  @Test
  void unitQuantityRejectsMorePrecisionThanTheColumn() {
    assertThat(BomRules.quantityProblem(bd("0.1234567"))).contains("最多 6 位小数");
    assertThat(BomRules.quantityProblem(bd("12345678901234"))).contains("整数部分最多 13 位");
  }

  // ================================================================ 成品数量

  @Test
  void outputQuantityMustBeAPositiveInteger() {
    assertThat(BomRules.outputQuantityProblem(null)).contains("请填写要生产的成品数量");
    assertThat(BomRules.outputQuantityProblem(bd("0"))).contains("必须大于 0");
    assertThat(BomRules.outputQuantityProblem(bd("-5"))).contains("必须大于 0");
    // 生产计划按件下达，0.5 个成品在仓库里不存在。
    assertThat(BomRules.outputQuantityProblem(bd("0.5"))).contains("必须是整数");
    assertThat(BomRules.outputQuantityProblem(bd("100"))).isNull();
    // 校验通过后可以安全地 setScale(0, UNNECESSARY) —— 服务层正是这么做的。
    assertThat(bd("100").setScale(0, java.math.RoundingMode.UNNECESSARY)).isEqualTo(bd("100"));
  }

  @Test
  void outputQuantityZeroWouldProduceAnAllZeroBillOfMaterials() {
    // 产 0 个时每个组件的需求量都是 0，拿到的清单会被误读成「这个 BOM 什么料都不用」。
    assertThat(BomRules.outputQuantityProblem(bd("0")))
        .contains("什么料都不用");
  }

  // ================================================================ 区间口径与 3.5.1.1 一致

  @Test
  void effectiveIntervalIsHalfOpenAndDoesNotOverlapAtTheBoundary() {
    // R1 半开区间：[10:00,12:00) 与 [12:00,14:00) 在 12:00 处不重叠。
    Instant a = Instant.parse("2026-03-01T10:00:00Z");
    Instant b = Instant.parse("2026-03-01T12:00:00Z");
    Instant c = Instant.parse("2026-03-01T14:00:00Z");
    assertThat(BomRules.overlaps(a, b, b, c)).isFalse();
    assertThat(BomRules.overlaps(a, b, a, c)).isTrue();
    // 开放区间（to = null = +∞）与任何后继区间都重叠。
    assertThat(BomRules.overlaps(a, null, b, c)).isTrue();
  }

  @Test
  void coversUsesTheSameFunctionForEveryModule() {
    // R4：起点命中、终点不命中。转发 BundleRules 保证两个模块给出同一个答案。
    Instant from = Instant.parse("2026-03-01T10:00:00Z");
    Instant to = Instant.parse("2026-03-01T12:00:00Z");
    assertThat(BomRules.covers(from, to, from)).isTrue();
    assertThat(BomRules.covers(from, to, to)).isFalse();
    assertThat(BomRules.covers(from, to, Instant.parse("2026-03-01T09:59:59Z"))).isFalse();
    assertThat(BomRules.covers(from, null, Instant.parse("2099-01-01T00:00:00Z"))).isTrue();
    // 同一个输入，两个模块必须给同一个答案。
    assertThat(BomRules.covers(from, to, from)).isEqualTo(BundleRules.covers(from, to, from));
  }

  @Test
  void forwardCloseIsTheOnlyWayToRetireAPublishedVersion() {
    // R5：只能收紧、只能往前。
    Instant now = Instant.parse("2026-03-01T10:00:00Z");
    assertThat(
            BomRules.forwardCloseProblem(
                null, Instant.parse("2026-03-01T09:00:00Z"), now))
        .contains("必须落在当前时刻之后");
    assertThat(
            BomRules.forwardCloseProblem(
                Instant.parse("2026-03-02T00:00:00Z"), Instant.parse("2026-03-03T00:00:00Z"), now))
        .contains("只能收紧不能往后延");
    assertThat(BomRules.forwardCloseProblem(null, null, now)).contains("要指定一个具体时刻");
    // 合法：把开放区间收到未来某一刻。
    assertThat(
            BomRules.forwardCloseProblem(
                null, Instant.parse("2026-04-01T00:00:00Z"), now))
        .isNull();
  }

  // ================================================================ 其它字段

  @Test
  void componentCountBoundsMatchTheDatabaseAndTheColumn() {
    assertThat(BomRules.componentCountProblem(0)).contains("至少要有一个组件");
    assertThat(BomRules.componentCountProblem(1)).isNull();
    assertThat(BomRules.componentCountProblem(50)).isNull();
    assertThat(BomRules.componentCountProblem(51)).contains("最多 50 个组件");
    // numeric(19,6) 的精度拆分必须自洽，否则 Java 侧放过的数 DB 会拒。
    assertThat(BomRules.QUANTITY_PRECISION + BomRules.QUANTITY_SCALE).isEqualTo(19);
  }

  @Test
  void codeIsUppercasedButVersionNumberKeepsItsCase() {
    // 编码是 06 采购建议与 08 加工单里的稳定引用，必须唯一到一个写法。
    assertThat(BomRules.normalizeCode(" bom-1 ")).isEqualTo("BOM-1");
    // 版本号是人写的标签：V1 与 v1 是两个不同标签，折叠会让版本失去可读性。
    assertThat(BomRules.normalizeVersionNo(" V1 ")).isEqualTo("V1");
  }

  @Test
  void statusVocabulariesMatchTheDatabaseChecks() {
    assertThat(BomRules.statusProblem("DRAFT")).isNull();
    assertThat(BomRules.statusProblem("PUBLISHED")).isNull();
    assertThat(BomRules.statusProblem("ARCHIVED")).contains("只能是 DRAFT 或 PUBLISHED");
    // 空输入 = 不过滤，不是错误。
    assertThat(BomRules.statusProblem("")).isNull();
    assertThat(BomRules.bomStatusProblem("ACTIVE")).isNull();
    assertThat(BomRules.bomStatusProblem("DELETED")).contains("只能是 ACTIVE 或 ARCHIVED");
  }

  @Test
  void effectiveFromMustBeATimeZonedInstant() {
    // 不带时区的写法会按 JVM 默认时区解析，同一份数据在不同机器上得到不同的生效时刻。
    assertThat(BomRules.parseInstant("2026-03-01T00:00:00Z", "生效起点"))
        .isEqualTo(Instant.parse("2026-03-01T00:00:00Z"));
    assertThat(BomRules.parseInstant("", "生效起点")).isNull();
    assertThatThrownBy(() -> BomRules.parseInstant("2026-03-01T00:00:00", "生效起点"))
        .hasMessageContaining("不是合法的带时区时间");
  }
}
