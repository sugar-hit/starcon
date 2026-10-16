package com.starcon.channel;

import static com.starcon.channel.FreightRatingEngine.*;
import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 10.1.2.1 运费计算器 · 计费引擎边界。
 *
 * <p>本类<b>不起容器</b>：计费引擎是纯函数，所以「重量段边界 / 首重进位 / 体积重除数 /
 * 舍入位数与模式 / 旺季区间两端」这些最贵的边界可以在毫秒级被钉死，且不会因为
 * 起了 PG 容器就变成一个慢测试。
 *
 * <p>🔴 这里刻意<b>不只测一个「正常值」</b>。计费事故几乎都发生在边界上：
 * 1.0kg 落在哪一段、0.5 件续重算不算、半分怎么舍、旺季区间交界那一刻算不算 ——
 * 任何一个算错，账单上都表现为「少收了一点」，而没有人会去查。
 */
class FreightRatingEngineTest {

  /** 固定时刻：旺季区间的两端用它来测，不用 {@code now()}（否则断言会飘）。 */
  static final OffsetDateTime AT = OffsetDateTime.parse("2026-10-15T09:00:00+08:00");

  static BigDecimal bd(String value) {
    return new BigDecimal(value);
  }

  static PackageSpec pkg(String weight) {
    return new PackageSpec(bd(weight), null, null, null, 1);
  }

  static ChannelTerms terms(String mode, String base, String divisor) {
    return new ChannelTerms(mode, "USD", bd(base), bd(divisor), bd("0"), "NONE", bd("0"), bd("0"));
  }

  /**
   * 三段计分档位：0–1 / 1–5 / 5 以上，单价 10 / 12 / 20。
   *
   * <p>🔴 段区间是<b>半开</b>的（[0,1) / [1,5) / [5,∞)）：这不只是为了好看，而是
   * DB 层 {@code guard_freight_band_overlap} 会拒绝 [1,5] 闭 + [5,∞) 这种配置
   * （5.0 同时落在两段里）。测试夹具必须与生产能落库的形态一致，否则单测就在
   * 测一种线上根本不存在的配置。
   */
  static RateCard weightBands() {
    return new RateCard(
        "FLAT_RATE", "WEIGHT", "HALF_UP", 2, null, null, null, null, null,
        List.of(
            new Band(1, bd("0"), bd("1"), false, bd("10")),
            new Band(2, bd("1"), bd("5"), false, bd("12")),
            new Band(3, bd("5"), null, false, bd("20"))));
  }

  Rating rate(PackageSpec p, ChannelTerms t, RateCard card, List<Surcharge> fees) {
    return INSTANCE.rate(p, t, card, false, fees, null, AT);
  }

  @Nested
  @DisplayName("计分档位：段边界闭开")
  class Bands {

    @Test
    @DisplayName("正好 1kg：金额与 1kg 差一丁点一致，且归属记在第一段（第二段命中 0 宽）")
    void boundaryValueIsAttributedToLowerBand() {
      var rating = rate(pkg("1"), terms("BY_WEIGHT", "28.50", "1"), weightBands(), List.of());

      // 1 × 10 + 0 × 12 = 10
      assertThat(rating.totalCharge()).isEqualByComparingTo("10.00");
      assertThat(rating.chargeableQty()).isEqualByComparingTo("1.000000");
      // 归属可见：第二段一条 trace 都没有
      assertThat(rating.trace()).anyMatch(s -> s.contains("第 1 段") && s.contains("命中 1"));
      assertThat(rating.trace()).noneMatch(s -> s.contains("第 2 段"));
    }

    @Test
    @DisplayName("比 1kg 多一丁点就进第二段（0.000001kg 也要按第二段单价计）")
    void justOverBoundaryEntersNextBand() {
      var rating =
          rate(pkg("1.000001"), terms("BY_WEIGHT", "28.50", "1"), weightBands(), List.of());

      // 1×10 + 0.000001×12 = 10.000012 → 两位 HALF_UP = 10.00
      assertThat(rating.totalCharge()).isEqualByComparingTo("10.00");
      assertThat(rating.trace()).anyMatch(s -> s.contains("第 2 段") && s.contains("命中 0.000001"));
    }

    @Test
    @DisplayName("正好 5kg：第二段收满 4kg，第三段命中 0 宽（半开区间的端点归属）")
    void boundaryAtThirdBandStart() {
      var rating = rate(pkg("5"), terms("BY_WEIGHT", "28.50", "1"), weightBands(), List.of());

      // 1×10 + 4×12 = 58
      assertThat(rating.totalCharge()).isEqualByComparingTo("58.00");
      assertThat(rating.trace()).noneMatch(s -> s.contains("第 3 段"));
    }

    @Test
    @DisplayName("超过最高一档上界 ⇒ 报错，绝不按「已覆盖的部分」少算一段")
    void beyondHighestBandIsAnError() {
      // 费率卡最高一档封顶 10kg
      RateCard capped =
          new RateCard(
              "FLAT_RATE", "WEIGHT", "HALF_UP", 2, null, null, null, null, null,
              List.of(
                  new Band(1, bd("0"), bd("1"), false, bd("10")),
                  new Band(2, bd("1"), bd("10"), true, bd("12"))));

      assertThatThrownBy(() -> rate(pkg("40"), terms("BY_WEIGHT", "28.50", "1"), capped, List.of()))
          .isInstanceOf(RatingException.class)
          .hasMessageContaining("超出了费率卡的最高一档");
    }
  }

  @Nested
  @DisplayName("首重 + 续重：进位方向")
  class FirstPlusAdditional {

    RateCard card() {
      return new RateCard(
          "FIRST_PLUS_ADDITIONAL", "WEIGHT", "HALF_UP", 2, null,
          bd("1"), bd("12"), bd("0.5"), bd("3"), List.of());
    }

    @Test
    @DisplayName("不超过首重只收首重费")
    void withinFirstWeight() {
      var rating = rate(pkg("1"), terms("BY_WEIGHT", "28.50", "1"), card(), List.of());
      assertThat(rating.totalCharge()).isEqualByComparingTo("12.00");
    }

    @Test
    @DisplayName("正好一个续重单位 ⇒ 一段续重")
    void exactlyOneAdditionalStep() {
      var rating = rate(pkg("1.5"), terms("BY_WEIGHT", "28.50", "1"), card(), List.of());
      assertThat(rating.totalCharge()).isEqualByComparingTo("15.00");
    }

    @Test
    @DisplayName("比一个续重单位多一丁点 ⇒ 进位成两段（少算半段就是白送运费）")
    void stepAlwaysRoundsUp() {
      var rating = rate(pkg("1.500001"), terms("BY_WEIGHT", "28.50", "1"), card(), List.of());
      assertThat(rating.totalCharge()).isEqualByComparingTo("18.00");
      assertThat(rating.trace()).anyMatch(s -> s.contains("进位得 2 段"));
    }

    @Test
    @DisplayName("余量不足一个续重单位也按整段收（向上进位，不是四舍五入）")
    void remainderStillChargesFullStep() {
      var rating = rate(pkg("1.4"), terms("BY_WEIGHT", "28.50", "1"), card(), List.of());
      assertThat(rating.totalCharge()).isEqualByComparingTo("15.00");
    }
  }

  @Nested
  @DisplayName("体积重：除数与取大")
  class Volumetric {

    @Test
    @DisplayName("体积重大于实重时按体积重计费（轻泡货不折算就是系统性少收）")
    void volumetricWinsWhenLarger() {
      RateCard card =
          new RateCard("FLAT_RATE", "WEIGHT", "HALF_UP", 2, bd("6000"), null, null, null, null,
              weightBands().bands());
      PackageSpec p = new PackageSpec(bd("1"), bd("30"), bd("20"), bd("20"), 1);

      var rating = rate(p, terms("BY_WEIGHT", "28.50", "1"), card, List.of());

      // 12000cm³ ÷ 6000 = 2kg > 实重 1kg ⇒ 按 2kg 走第二段 = 1×10 + 1×12 = 22
      assertThat(rating.chargeableQty()).isEqualByComparingTo("2.000000");
      assertThat(rating.volumetricQty()).isEqualByComparingTo("2.000000");
      assertThat(rating.totalCharge()).isEqualByComparingTo("22.00");
      assertThat(rating.trace()).anyMatch(s -> s.contains("按体积重计费"));
    }

    @Test
    @DisplayName("实重更大时按实重，体积重仍然报出来（要能看出这票折算过）")
    void actualWeightWinsWhenLarger() {
      RateCard card =
          new RateCard("FLAT_RATE", "WEIGHT", "HALF_UP", 2, bd("6000"), null, null, null, null,
              weightBands().bands());
      PackageSpec p = new PackageSpec(bd("3"), bd("30"), bd("20"), bd("20"), 1);

      var rating = rate(p, terms("BY_WEIGHT", "28.50", "1"), card, List.of());

      assertThat(rating.chargeableQty()).isEqualByComparingTo("3.000000");
      assertThat(rating.volumetricQty()).isEqualByComparingTo("2.000000");
      assertThat(rating.totalCharge()).isEqualByComparingTo("34.00");
    }

    @Test
    @DisplayName("按体积计费的渠道走渠道自己的折算基数，缺尺寸则算不了")
    void volumeBasisUsesChannelDivisor() {
      PackageSpec p = new PackageSpec(bd("2"), bd("30"), bd("20"), bd("20"), 1);
      RateCard card =
          new RateCard("FLAT_RATE", "VOLUME", "HALF_UP", 2, null, null, null, null, null,
              List.of(new Band(1, bd("0"), null, false, bd("30"))));

      // 12000 ÷ 6000 = 2 折 kg × 30 = 60
      var rating = rate(p, terms("BY_VOLUME", "30", "6000"), card, List.of());
      assertThat(rating.chargeableQty()).isEqualByComparingTo("2.000000");
      assertThat(rating.totalCharge()).isEqualByComparingTo("60.00");

      PackageSpec noDims = new PackageSpec(bd("2"), null, null, null, 1);
      assertThatThrownBy(() -> rate(noDims, terms("BY_VOLUME", "30", "6000"), card, List.of()))
          .isInstanceOf(RatingException.class)
          .hasMessageContaining("必须给长宽高");
    }
  }

  @Nested
  @DisplayName("附加费：燃油 / 偏远 / 旺季")
  class Surcharges {

    @Test
    @DisplayName("燃油按百分比随基础运费浮动")
    void fuelPercentTracksBase() {
      var rating =
          rate(
              pkg("1"),
              terms("BY_WEIGHT", "28.50", "1"),
              weightBands(),
              List.of(new Surcharge("FUEL", "PERCENT", bd("18.5"), null, null)));

      // 基础 10.00 + 10×18.5% = 1.85
      assertThat(rating.baseCharge()).isEqualByComparingTo("10.00");
      assertThat(rating.fuelFee()).isEqualByComparingTo("1.85");
      assertThat(rating.totalCharge()).isEqualByComparingTo("11.85");
    }

    @Test
    @DisplayName("偏远费只在该国确实偏远时收")
    void remoteAreaOnlyWhenRemote() {
      List<Surcharge> fees = List.of(new Surcharge("REMOTE_AREA", "FIXED", bd("12"), null, null));

      var near = INSTANCE.rate(pkg("1"), terms("BY_WEIGHT", "28.50", "1"), weightBands(), false,
          fees, null, AT);
      assertThat(near.remoteFee()).isEqualByComparingTo("0.00");

      var far = INSTANCE.rate(pkg("1"), terms("BY_WEIGHT", "28.50", "1"), weightBands(), true,
          fees, null, AT);
      assertThat(far.remoteFee()).isEqualByComparingTo("12.00");
      assertThat(far.totalCharge()).isEqualByComparingTo("22.00");
    }

    @Test
    @DisplayName("旺季区间左闭右开：起点那一刻收、终点那一刻不收")
    void peakWindowIsHalfOpen() {
      OffsetDateTime from = OffsetDateTime.parse("2026-10-01T00:00:00+08:00");
      OffsetDateTime to = OffsetDateTime.parse("2026-11-01T00:00:00+08:00");
      List<Surcharge> fees =
          List.of(new Surcharge("PEAK_SEASON", "FIXED", bd("5"), from, to));

      var atStart =
          INSTANCE.rate(pkg("1"), terms("BY_WEIGHT", "28.50", "1"), weightBands(), false, fees,
              null, from);
      assertThat(atStart.peakFee()).isEqualByComparingTo("5.00");

      var atEnd =
          INSTANCE.rate(pkg("1"), terms("BY_WEIGHT", "28.50", "1"), weightBands(), false, fees,
              null, to);
      assertThat(atEnd.peakFee()).isEqualByComparingTo("0.00");

      // AT（2026-10-15）落在旺季区间**内** —— 这正是要钉住的那种「今天算不算旺季」
      OffsetDateTime insideWindow = OffsetDateTime.parse("2026-10-15T09:00:00+08:00");
      var inside =
          INSTANCE.rate(pkg("1"), terms("BY_WEIGHT", "28.50", "1"), weightBands(), false, fees,
              null, insideWindow);
      assertThat(inside.peakFee()).isEqualByComparingTo("5.00");

      var outside =
          INSTANCE.rate(pkg("1"), terms("BY_WEIGHT", "28.50", "1"), weightBands(), false, fees,
              null, OffsetDateTime.parse("2026-12-01T09:00:00+08:00"));
      assertThat(outside.peakFee()).isEqualByComparingTo("0.00");
      assertThat(outside.trace()).anyMatch(s -> s.contains("不在生效区间内"));
    }
  }

  @Nested
  @DisplayName("舍入：位数与模式来自数据")
  class Rounding {

    /** 基础运费 10.00 + 燃油 0.05% = 0.005 —— 半分，模式不同结果不同。 */
    List<Surcharge> halfCentFuel() {
      return List.of(new Surcharge("FUEL", "PERCENT", bd("0.05"), null, null));
    }

    Rating withMode(String mode, int scale) {
      RateCard card =
          new RateCard("FLAT_RATE", "WEIGHT", mode, scale, null, null, null, null, null,
              weightBands().bands());
      return rate(pkg("1"), terms("BY_WEIGHT", "28.50", "1"), card, halfCentFuel());
    }

    @Test
    @DisplayName("HALF_UP 把半分进位，FLOOR 舍去，HALF_EVEN 取偶")
    void roundingModeChangesTheCent() {
      assertThat(withMode("HALF_UP", 2).fuelFee()).isEqualByComparingTo("0.01");
      assertThat(withMode("FLOOR", 2).fuelFee()).isEqualByComparingTo("0.00");
      // 0.005 的半分正好落在偶数 0 上 ⇒ HALF_EVEN 不动
      assertThat(withMode("HALF_EVEN", 2).fuelFee()).isEqualByComparingTo("0.00");
      assertThat(withMode("CEILING", 2).fuelFee()).isEqualByComparingTo("0.01");
    }

    @Test
    @DisplayName("舍入位数也是数据：4 位与 0 位给出不同的合计")
    void scaleIsAlsoData() {
      assertThat(withMode("HALF_UP", 4).totalCharge()).isEqualByComparingTo("10.0050");
      assertThat(withMode("HALF_UP", 0).totalCharge()).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("合计恒等于各分项之和（界面上分项与合计必须对得上）")
    void totalIsTheSumOfRoundedParts() {
      Rating rating =
          INSTANCE.rate(pkg("1"), terms("BY_WEIGHT", "28.50", "1"), weightBands(), true,
              List.of(
                  new Surcharge("FUEL", "PERCENT", bd("18.5"), null, null),
                  new Surcharge("REMOTE_AREA", "FIXED", bd("12"), null, null)),
              null, AT);
      assertThat(rating.totalCharge())
          .isEqualByComparingTo(
              rating.baseCharge()
                  .add(rating.fuelFee())
                  .add(rating.remoteFee())
                  .add(rating.peakFee())
                  .add(rating.insuranceFee()));
    }
  }

  @Nested
  @DisplayName("最低收费 / 保费 / 口径一致性")
  class Guards {

    @Test
    @DisplayName("低于最低收费按最低收费收（否则 1 件小件算出几分钱运费）")
    void minChargeFloor() {
      ChannelTerms terms =
          new ChannelTerms("BY_WEIGHT", "USD", bd("28.50"), bd("1"), bd("15"), "NONE", bd("0"), bd("0"));

      var rating = rate(pkg("1"), terms, weightBands(), List.of());

      assertThat(rating.baseCharge()).isEqualByComparingTo("15.00");
      assertThat(rating.trace()).anyMatch(s -> s.contains("低于渠道最低收费"));
    }

    @Test
    @DisplayName("按申报价值投保：费率与最低保费取大")
    void insuranceByDeclaredValue() {
      ChannelTerms terms =
          new ChannelTerms("BY_WEIGHT", "USD", bd("28.50"), bd("1"), bd("0"),
              "DECLARED_VALUE", bd("1.5"), bd("2"));

      var rating = INSTANCE.rate(pkg("1"), terms, weightBands(), false, List.of(), bd("100"), AT);
      assertThat(rating.insuranceFee()).isEqualByComparingTo("2.00"); // 100×1.5% = 1.50 → 取最低 2.00

      var bigger = INSTANCE.rate(pkg("1"), terms, weightBands(), false, List.of(), bd("1000"), AT);
      assertThat(bigger.insuranceFee()).isEqualByComparingTo("15.00");
    }

    @Test
    @DisplayName("按申报价值投保但没给申报价值 ⇒ 保费 0 且必须留一条说明（不是静默算成 0）")
    void insuranceWithoutDeclaredValueIsFlagged() {
      ChannelTerms terms =
          new ChannelTerms("BY_WEIGHT", "USD", bd("28.50"), bd("1"), bd("0"),
              "DECLARED_VALUE", bd("1.5"), bd("2"));

      var rating = INSTANCE.rate(pkg("1"), terms, weightBands(), false, List.of(), null, AT);

      assertThat(rating.insuranceFee()).isEqualByComparingTo("0.00");
      assertThat(rating.notes()).anyMatch(s -> s.contains("未提供申报价值"));
    }

    @Test
    @DisplayName("费率卡量纲与渠道计费方式不一致 ⇒ 报错，不猜")
    void basisMismatchIsRejected() {
      RateCard volumeCard =
          new RateCard("FLAT_RATE", "VOLUME", "HALF_UP", 2, null, null, null, null, null,
              List.of(new Band(1, bd("0"), null, false, bd("30"))));

      assertThatThrownBy(
              () -> rate(
                  new PackageSpec(bd("1"), bd("10"), bd("10"), bd("10"), 1),
                  terms("BY_WEIGHT", "28.50", "1"), volumeCard, List.of()))
          .isInstanceOf(RatingException.class)
          .hasMessageContaining("不一致");
    }

    @Test
    @DisplayName("没有生效费率卡 ⇒ 回退渠道单值，并在轨迹里说清用的是回退口径")
    void fallsBackToChannelFlatRate() {
      var rating = rate(pkg("4"), terms("BY_WEIGHT", "28.50", "2"), null, List.of());

      // 4 ÷ 2 × 28.50 = 57.00
      assertThat(rating.rateSource()).isEqualTo("CHANNEL_FLAT");
      assertThat(rating.totalCharge()).isEqualByComparingTo("57.00");
      assertThat(rating.trace()).anyMatch(s -> s.contains("渠道单值回退"));
      assertThat(rating.trace()).anyMatch(s -> s.contains("HALF_UP@2"));
    }
  }
}
