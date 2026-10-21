package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;

import com.starcon.trade.SupplierRatingModels.SupplierRatingDimension;
import com.starcon.trade.SupplierRatingModels.SupplierRatingGrade;
import com.starcon.trade.SupplierRatingModels.SupplierRatingPeriodType;
import com.starcon.trade.SupplierRatingRules.QuoteTier;
import com.starcon.trade.SupplierRatingRules.Weights;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 6.1.1.3 供应商评级的<b>纯规则单测</b>。
 *
 * <p>测的是「怎么算」，不碰数据库：口径一旦散在 SQL 里，改一次分母就要同时改三四个地方，
 * 而**没有任何东西会报错** —— 只会让运营对着两个数字做决策。
 *
 * <p>覆盖的六条纪律：
 *
 * <ol>
 *   <li>不可用 ≠ 0 分（分母为 0 ⇒ null ⇒ 不参与合成）；</li>
 *   <li>合成按可用维度归一（只算出三个维度时仍是 0~100 的可比值）；</li>
 *   <li>人工调整不改客观分（只改最终分，且夹逼在 0~100）；</li>
 *   <li>权重和必须精确等于 1；</li>
 *   <li>阶梯价在「参考数量低于全部起订量」时<b>不适用</b>（返回 null，不进比价样本）；</li>
 *   <li>价格贡献度 = 同币种最优价 / 本供应商价（上限 1）。</li>
 * </ol>
 */
class SupplierRatingRulesTest {

  private static BigDecimal bd(String v) {
    return new BigDecimal(v);
  }

  // ================================================================ 周期

  @Test
  void 月度周期是自然月闭区间() {
    var period = SupplierRatingRules.periodOf(SupplierRatingPeriodType.MONTHLY, LocalDate.of(2026, 3, 17));
    assertThat(period.start()).isEqualTo(LocalDate.of(2026, 3, 1));
    assertThat(period.end()).isEqualTo(LocalDate.of(2026, 3, 31));
  }

  @Test
  void 二月按真实天数收口() {
    var period = SupplierRatingRules.periodOf(SupplierRatingPeriodType.MONTHLY, LocalDate.of(2026, 2, 10));
    assertThat(period.start()).isEqualTo(LocalDate.of(2026, 2, 1));
    assertThat(period.end()).isEqualTo(LocalDate.of(2026, 2, 28));
  }

  @Test
  void 季度周期是自然季度闭区间() {
    var q1 = SupplierRatingRules.periodOf(SupplierRatingPeriodType.QUARTERLY, LocalDate.of(2026, 2, 10));
    assertThat(q1.start()).isEqualTo(LocalDate.of(2026, 1, 1));
    assertThat(q1.end()).isEqualTo(LocalDate.of(2026, 3, 31));
    var q3 = SupplierRatingRules.periodOf(SupplierRatingPeriodType.QUARTERLY, LocalDate.of(2026, 9, 30));
    assertThat(q3.start()).isEqualTo(LocalDate.of(2026, 7, 1));
    assertThat(q3.end()).isEqualTo(LocalDate.of(2026, 9, 30));
  }

  @Test
  void 周期类型缺省是月度() {
    var period = SupplierRatingRules.periodOf(null, LocalDate.of(2026, 5, 5));
    assertThat(period.start()).isEqualTo(LocalDate.of(2026, 5, 1));
  }

  // ================================================================ 权重

  @Test
  void 权重和必须精确等于一() {
    var problems = SupplierRatingRules.validateWeights(new Weights(
        bd("0.30"), bd("0.30"), bd("0.15"), bd("0.15"), bd("0.10")));
    assertThat(problems).isEmpty();
  }

  @Test
  void 权重和小于一会被拒() {
    var problems = SupplierRatingRules.validateWeights(new Weights(
        bd("0.30"), bd("0.30"), bd("0.15"), bd("0.15"), bd("0.05")));
    assertThat(problems).hasSize(1);
    assertThat(problems.get(0)).contains("精确等于 1");
  }

  @Test
  void 权重和大于一会被拒() {
    var problems = SupplierRatingRules.validateWeights(new Weights(
        bd("0.40"), bd("0.30"), bd("0.15"), bd("0.15"), bd("0.10")));
    assertThat(problems).hasSize(1);
  }

  @Test
  void 某维度权重为零是合法表达() {
    var problems = SupplierRatingRules.validateWeights(new Weights(
        bd("0.40"), bd("0.40"), bd("0.20"), bd("0.00"), bd("0.00")));
    assertThat(problems).isEmpty();
  }

  @Test
  void 负权重被拒() {
    var problems = SupplierRatingRules.validateWeights(new Weights(
        bd("1.10"), bd("0.00"), bd("0.00"), bd("0.00"), bd("-0.10")));
    assertThat(problems).isNotEmpty();
    assertThat(problems.get(0)).contains("0 ~ 1");
  }

  @Test
  void 系统默认权重之和精确等于一() {
    assertThat(SupplierRatingRules.defaultWeights().sum()).isEqualByComparingTo("1");
  }

  // ================================================================ 比率型维度

  @Test
  void 比率型维度得分是比率乘一百() {
    assertThat(SupplierRatingRules.rateScore(bd("0.92"))).isEqualByComparingTo("92.0000");
  }

  @Test
  void 比率型维度在比率为null时不可用而不是零分() {
    assertThat(SupplierRatingRules.rateScore(null)).isNull();
  }

  @Test
  void 比率零分与不可用是两件事() {
    assertThat(SupplierRatingRules.rateScore(BigDecimal.ZERO)).isEqualByComparingTo("0.0000");
    assertThat(SupplierRatingRules.rateScore(null)).isNull();
  }

  // ================================================================ 响应时长

  @Test
  void 响应时长等于目标时得五十分() {
    assertThat(SupplierRatingRules.responseScore(bd("72"), 72)).isEqualByComparingTo("50.0000");
  }

  @Test
  void 响应时长为零得满分() {
    assertThat(SupplierRatingRules.responseScore(BigDecimal.ZERO, 72)).isEqualByComparingTo("100.0000");
  }

  @Test
  void 响应时长超过两倍目标得零分且不为负() {
    assertThat(SupplierRatingRules.responseScore(bd("144"), 72)).isEqualByComparingTo("0.0000");
    assertThat(SupplierRatingRules.responseScore(bd("500"), 72)).isEqualByComparingTo("0.0000");
  }

  @Test
  void 响应时长在目标与两倍目标之间线性递减() {
    // 100 小时、目标 72 ⇒ 100 × (1 − 100/144) = 30.5556
    assertThat(SupplierRatingRules.responseScore(bd("100"), 72)).isEqualByComparingTo("30.5556");
  }

  @Test
  void 响应时长样本为空时该维度不可用() {
    assertThat(SupplierRatingRules.responseScore(null, 72)).isNull();
  }

  // ================================================================ 价格竞争力

  @Test
  void 本供应商就是最优价时贡献度为一() {
    assertThat(SupplierRatingRules.priceContribution(bd("10"), bd("10")))
        .isEqualByComparingTo("1.000000");
    assertThat(SupplierRatingRules.priceContribution(bd("8"), bd("10")))
        .isEqualByComparingTo("1.000000");
  }

  @Test
  void 比最优价贵一倍时贡献度是一半() {
    assertThat(SupplierRatingRules.priceContribution(bd("20"), bd("10")))
        .isEqualByComparingTo("0.500000");
  }

  @Test
  void 价格缺一端时贡献度为空() {
    assertThat(SupplierRatingRules.priceContribution(null, bd("10"))).isNull();
    assertThat(SupplierRatingRules.priceContribution(bd("10"), null)).isNull();
  }

  @Test
  void 阶梯价按参考数量命中起订量不高于它的最高一档() {
    var tiers = List.of(new QuoteTier(bd("1"), bd("12")), new QuoteTier(bd("100"), bd("10")));
    assertThat(SupplierRatingRules.tierPrice(tiers, bd("50"))).isEqualByComparingTo("12");
    assertThat(SupplierRatingRules.tierPrice(tiers, bd("100"))).isEqualByComparingTo("10");
    assertThat(SupplierRatingRules.tierPrice(tiers, bd("500"))).isEqualByComparingTo("10");
  }

  @Test
  void 参考数量低于全部起订量时报价不适用() {
    var tiers = List.of(new QuoteTier(bd("1000"), bd("9")));
    assertThat(SupplierRatingRules.tierPrice(tiers, bd("10"))).isNull();
  }

  @Test
  void 没有阶梯时单价为空() {
    assertThat(SupplierRatingRules.tierPrice(List.of(), bd("10"))).isNull();
    assertThat(SupplierRatingRules.tierPrice(null, bd("10"))).isNull();
  }

  // ================================================================ 合成

  private Map<SupplierRatingDimension, BigDecimal> scores(
      BigDecimal delivery, BigDecimal quality, BigDecimal response, BigDecimal price,
      BigDecimal service) {
    var m = new EnumMap<SupplierRatingDimension, BigDecimal>(SupplierRatingDimension.class);
    m.put(SupplierRatingDimension.DELIVERY, delivery);
    m.put(SupplierRatingDimension.QUALITY, quality);
    m.put(SupplierRatingDimension.RESPONSE, response);
    m.put(SupplierRatingDimension.PRICE, price);
    m.put(SupplierRatingDimension.SERVICE, service);
    return m;
  }

  @Test
  void 五个维度齐全时综合分是加权平均() {
    var w = new Weights(bd("0.20"), bd("0.20"), bd("0.20"), bd("0.20"), bd("0.20"));
    var composite = SupplierRatingRules.composite(
        w, scores(bd("100"), bd("80"), bd("60"), bd("40"), bd("20")));
    assertThat(composite.score()).isEqualByComparingTo("60.0000");
    assertThat(composite.availableWeight()).isEqualByComparingTo("1.000000");
    assertThat(composite.unavailable()).isEmpty();
  }

  @Test
  void 不可用维度被排除且综合分按可用维度归一() {
    var w = new Weights(bd("0.50"), bd("0.50"), bd("0.00"), bd("0.00"), bd("0.00"));
    var composite = SupplierRatingRules.composite(
        w, scores(bd("100"), bd("80"), null, null, null));
    assertThat(composite.score()).isEqualByComparingTo("90.0000");
    assertThat(composite.availableWeight()).isEqualByComparingTo("1.000000");
    assertThat(composite.unavailable())
        .containsExactlyInAnyOrder(
            SupplierRatingDimension.RESPONSE, SupplierRatingDimension.PRICE,
            SupplierRatingDimension.SERVICE);
  }

  @Test
  void 全部维度不可用时综合分为空而不是零() {
    var w = new Weights(bd("0.50"), bd("0.50"), bd("0.00"), bd("0.00"), bd("0.00"));
    var composite = SupplierRatingRules.composite(w, scores(null, null, null, null, null));
    assertThat(composite.score()).isNull();
    assertThat(composite.availableWeight()).isEqualByComparingTo("0.000000");
  }

  @Test
  void 只算出部分维度时覆盖权重被如实报出() {
    var w = new Weights(bd("0.30"), bd("0.30"), bd("0.15"), bd("0.15"), bd("0.10"));
    var composite = SupplierRatingRules.composite(
        w, scores(bd("100"), bd("100"), null, null, null));
    assertThat(composite.availableWeight()).isEqualByComparingTo("0.600000");
  }

  // ================================================================ 等级

  @Test
  void 等级按分档判定且边界含等号() {
    assertThat(SupplierRatingRules.grade(bd("85"), bd("0.85"), bd("0.70"), bd("0.55")))
        .isEqualTo(SupplierRatingGrade.A);
    assertThat(SupplierRatingRules.grade(bd("70"), bd("0.85"), bd("0.70"), bd("0.55")))
        .isEqualTo(SupplierRatingGrade.B);
    assertThat(SupplierRatingRules.grade(bd("55"), bd("0.85"), bd("0.70"), bd("0.55")))
        .isEqualTo(SupplierRatingGrade.C);
    assertThat(SupplierRatingRules.grade(bd("54.99"), bd("0.85"), bd("0.70"), bd("0.55")))
        .isEqualTo(SupplierRatingGrade.D);
  }

  @Test
  void 没有分数就没有等级() {
    assertThat(SupplierRatingRules.grade(null, bd("0.85"), bd("0.70"), bd("0.55"))).isNull();
  }

  // ================================================================ 人工调整

  @Test
  void 人工调整只改最终分不动客观分() {
    assertThat(SupplierRatingRules.adjustedFinalScore(bd("80.0000"), bd("10")))
        .isEqualByComparingTo("90.0000");
  }

  @Test
  void 人工调整把最终分夹逼在一百分以内() {
    assertThat(SupplierRatingRules.adjustedFinalScore(bd("95"), bd("20")))
        .isEqualByComparingTo("100.0000");
    assertThat(SupplierRatingRules.adjustedFinalScore(bd("5"), bd("-20")))
        .isEqualByComparingTo("0.0000");
  }

  @Test
  void 没有人工调整时最终分等于客观分() {
    assertThat(SupplierRatingRules.adjustedFinalScore(bd("73.5"), null))
        .isEqualByComparingTo("73.5000");
  }

  // ================================================================ 维度键

  @Test
  void 维度键清单可往返解析() {
    var keys = SupplierRatingRules.dimensionKeys(
        List.of(SupplierRatingDimension.PRICE, SupplierRatingDimension.SERVICE));
    assertThat(keys).isEqualTo("PRICE,SERVICE");
    assertThat(SupplierRatingRules.parseDimensions(keys))
        .containsExactly(SupplierRatingDimension.PRICE, SupplierRatingDimension.SERVICE);
  }

  @Test
  void 读到一个不认识的维度键时跳过而不是整页失败() {
    assertThat(SupplierRatingRules.parseDimensions("PRICE,NOT_A_DIMENSION"))
        .containsExactly(SupplierRatingDimension.PRICE);
  }
}
