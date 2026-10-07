package com.starcon.catalog;

import static com.starcon.catalog.PhysicalSafetyModels.*;
import static com.starcon.catalog.PhysicalSafetyRules.*;
import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.*;

/**
 * 3.7.2.1 纯规则单测：体积、体积重、计费重、特殊申报、渠道判定、装箱核算。
 *
 * <p>这些判定决定的是「这个 SKU 能不能走这个渠道、要不要申报」，代价出现在
 * <b>真实的面单与报关单</b>上，而判定发生在录入那一刻 —— 所以必须能不碰数据库
 * 逐条穷举。集成测试（{@code PhysicalSafetyIntegrationTest}）只证明这些规则
 * 真的被用上了，证明不了规则本身对不对。
 */
class PhysicalSafetyRulesTest {
  static PhysicalSafetyProfile profile(
      BigDecimal l, BigDecimal w, BigDecimal h, BigDecimal net, BigDecimal gross) {
    boolean collected = true;
    return new PhysicalSafetyProfile(
        java.util.UUID.randomUUID(), "SKU-1", "测试商品", "SPU-1", l, w, h, net, gross, "纸箱",
        collected, false, false, false, false, "", "", 0L, 0L,
        null, null, null, null, List.of(), List.of(), false);
  }

  static PhysicalSafetyProfile withFlags(boolean battery, boolean liquid, boolean dg, boolean magnetic) {
    return new PhysicalSafetyProfile(
        java.util.UUID.randomUUID(), "SKU-1", "测试商品", "SPU-1",
        null, null, null, null, null, "", true, battery, liquid, dg, magnetic,
        // 危险品必须带类别（规则 B3）：规则函数的入参必须永远是「合法的档案」，
        // 否则测试就在断言一个 DB 层已经拒绝掉的形状。
        dg ? "UN1266" : "", "", 0L, 0L,
        null, null, null, null, List.of(), List.of(), false);
  }

  static PhysicalSafetyProfile notCollected() {
    return new PhysicalSafetyProfile(
        java.util.UUID.randomUUID(), "SKU-1", "测试商品", "SPU-1",
        null, null, null, null, null, "", false, false, false, false, false, "", "", null, null,
        null, null, null, null, List.of(), List.of(), false);
  }

  // ============================================================ 体积（规则 R1）

  @Test
  void 体积按长宽高相乘并换算成立方厘米() {
    // 200×150×100 mm = 3,000,000 mm³ = 3000 cm³
    assertThat(volumeCm3(decimal("200"), decimal("150"), decimal("100")))
        .isEqualByComparingTo(decimal("3000"));
  }

  @Test
  void 缺任一维都不给体积而不是给零() {
    assertThat(volumeCm3(decimal("200"), null, decimal("100"))).isNull();
    assertThat(volumeCm3(null, decimal("150"), decimal("100"))).isNull();
    assertThat(volumeCm3(decimal("200"), decimal("150"), null)).isNull();
    assertThat(volumeCm3(null, null, null)).isNull();
  }

  @Test
  void 某一维为零视为没量到而不是体积为零() {
    // 0 体积会让渠道按「小件」给运费，少收的钱收不回来。
    assertThat(volumeCm3(decimal("0"), decimal("150"), decimal("100"))).isNull();
    assertThat(volumeCm3(decimal("200"), decimal("150"), decimal("0"))).isNull();
  }

  // ============================================================ 体积重（规则 R3）

  @Test
  void 体积重等于体积除以调用方给的除数() {
    BigDecimal volume = volumeCm3(decimal("200"), decimal("150"), decimal("100"));
    // 空运口径 6000 cm³/kg：3000 / 6000 = 0.5 kg
    assertThat(volumetricWeightKg(volume, decimal("6000"))).isEqualByComparingTo(decimal("0.5"));
    // 快递口径 5000：3000 / 5000 = 0.6 kg
    assertThat(volumetricWeightKg(volume, decimal("5000"))).isEqualByComparingTo(decimal("0.6"));
  }

  @Test
  void 除数不同给出不同体积重() {
    BigDecimal volume = volumeCm3(decimal("400"), decimal("400"), decimal("400"));
    BigDecimal air = volumetricWeightKg(volume, decimal("6000"));
    BigDecimal express = volumetricWeightKg(volume, decimal("5000"));
    assertThat(air).isNotEqualByComparingTo(express);
    assertThat(air).isLessThan(express);
  }

  @Test
  void 没给除数就不给体积重而不是套默认值() {
    BigDecimal volume = volumeCm3(decimal("200"), decimal("150"), decimal("100"));
    assertThat(volumetricWeightKg(volume, null)).isNull();
    // 除数非正数同样不给：除数是渠道口径，错的除数比没有除数更危险。
    assertThat(volumetricWeightKg(volume, BigDecimal.ZERO)).isNull();
    assertThat(volumetricWeightKg(volume, decimal("-1"))).isNull();
  }

  @Test
  void 体积算不出来时体积重也给不出来() {
    assertThat(volumetricWeightKg(null, decimal("6000"))).isNull();
  }

  // ============================================================ 计费重

  @Test
  void 计费重取实重与体积重的较大者() {
    // 毛重 5kg、体积重 0.5kg → 实重更大
    assertThat(chargeableWeightKg(decimal("5000"), decimal("0.5")))
        .isEqualByComparingTo(decimal("5"));
    // 毛重 0.2kg、体积重 0.6kg → 体积重更大（泡货的典型情形）
    assertThat(chargeableWeightKg(decimal("200"), decimal("0.6")))
        .isEqualByComparingTo(decimal("0.6"));
  }

  @Test
  void 只有实重或只有体积重时取那一个() {
    assertThat(chargeableWeightKg(decimal("1500"), null)).isEqualByComparingTo(decimal("1.5"));
    assertThat(chargeableWeightKg(null, decimal("0.75"))).isEqualByComparingTo(decimal("0.75"));
  }

  @Test
  void 两个都算不出来时不给零() {
    assertThat(chargeableWeightKg(null, null)).isNull();
  }

  @Test
  void 计费重用毛重而不是净重() {
    // 包装本身也有重量，净重不含它。
    assertThat(chargeableWeightKg(decimal("1200"), null)).isEqualByComparingTo(decimal("1.2"));
  }

  // ============================================================ 安全标记与申报（规则 B2 / R2）

  @Test
  void 四个安全标记各自独立成项() {
    var onlyBattery = activeFlags(withFlags(true, false, false, false));
    assertThat(onlyBattery).containsExactly(CONTAINS_BATTERY);
    var batteryAndLiquid = activeFlags(withFlags(true, true, false, false));
    assertThat(batteryAndLiquid).containsExactly(CONTAINS_BATTERY, CONTAINS_LIQUID);
    assertThat(activeFlags(withFlags(false, false, false, false))).isEmpty();
  }

  @Test
  void 申报项与安全标记一一对应() {
    assertThat(requiredDeclarations(withFlags(true, false, false, false))).containsExactly("BATTERY");
    assertThat(requiredDeclarations(withFlags(false, true, false, false))).containsExactly("LIQUID");
    assertThat(requiredDeclarations(withFlags(false, false, true, false)))
        .containsExactly("DANGEROUS_GOODS");
    assertThat(requiredDeclarations(withFlags(false, false, false, true))).containsExactly("MAGNETIC");
    assertThat(requiredDeclarations(withFlags(false, false, false, false))).isEmpty();
    // 带电且危险是真实存在的组合，不能被压成一项。
    assertThat(requiredDeclarations(withFlags(true, false, true, false)))
        .containsExactly("BATTERY", "DANGEROUS_GOODS");
  }

  @Test
  void 未采集与都不带在展示上必须不同() {
    assertThat(flagsText(notCollected())).isEqualTo("未采集");
    assertThat(flagsText(withFlags(false, false, false, false))).isEqualTo("无特殊属性");
    assertThat(flagsText(withFlags(true, false, true, false))).isEqualTo("带电（含电池） / 危险品");
  }

  // ============================================================ 渠道判定（规则 R2 / R3）

  static ChannelPolicy policy(
      String code, boolean battery, boolean liquid, boolean dg, boolean magnetic, boolean requireParams) {
    return new ChannelPolicy(code, battery, liquid, dg, magnetic, requireParams);
  }

  @Test
  void 安全属性未采集一律判不可发() {
    var result = verdict(notCollected(), policy("AIR", true, true, true, true, false));
    assertThat(result.shippable()).isFalse();
    assertThat(result.blockers()).contains("SAFETY_NOT_COLLECTED");
  }

  @Test
  void 未采集时即使渠道什么都不限制也不放行() {
    // 这是规则 R2 的核心：默认当成「普通货」放行 = 一条绕过申报的路。
    var result = verdict(notCollected(), policy("ANY", true, true, true, true, true));
    assertThat(result.shippable()).isFalse();
    assertThat(result.blockers()).contains("SAFETY_NOT_COLLECTED");
  }

  @Test
  void 已采集且渠道都接受时放行() {
    var result = verdict(withFlags(false, false, false, false), policy("EXPRESS", true, true, true, true, false));
    assertThat(result.shippable()).isTrue();
    assertThat(result.blockers()).isEmpty();
    assertThat(result.declarations()).isEmpty();
  }

  @Test
  void 带电货物遇到不收带电的渠道被拦下() {
    var result = verdict(withFlags(true, false, false, false), policy("ROAD", false, true, true, true, false));
    assertThat(result.shippable()).isFalse();
    assertThat(result.blockers()).containsExactly("BATTERY_NOT_ALLOWED");
  }

  @Test
  void 液体危险品磁性各自独立拦截() {
    assertThat(verdict(withFlags(false, true, false, false), policy("C", true, false, true, true, false))
        .blockers()).containsExactly("LIQUID_NOT_ALLOWED");
    assertThat(verdict(withFlags(false, false, true, false), policy("C", true, true, false, true, false))
        .blockers()).containsExactly("DANGEROUS_GOODS_NOT_ALLOWED");
    assertThat(verdict(withFlags(false, false, false, true), policy("C", true, true, true, false, false))
        .blockers()).containsExactly("MAGNETIC_NOT_ALLOWED");
  }

  @Test
  void 可发时也要给出该办的申报() {
    // 渠道接受危险品，但仍需危险品申报 —— 拦截与申报是两件事。
    var result = verdict(withFlags(false, false, true, false), policy("DG-FREIGHT", true, true, true, true, false));
    assertThat(result.shippable()).isTrue();
    assertThat(result.declarations()).containsExactly("DANGEROUS_GOODS");
  }

  @Test
  void 渠道要求物理参数时缺一维被拦下() {
    var noDims = withFlags(false, false, false, false);
    var result = verdict(noDims, policy("AIR", true, true, true, true, true));
    assertThat(result.shippable()).isFalse();
    assertThat(result.blockers()).contains("PHYSICAL_PARAMETERS_MISSING");
  }

  @Test
  void 安全未采集与缺尺寸两个阻断原因同时出现() {
    var result = verdict(notCollected(), policy("AIR", true, true, true, true, true));
    assertThat(result.blockers())
        .containsExactlyInAnyOrder("SAFETY_NOT_COLLECTED", "PHYSICAL_PARAMETERS_MISSING");
  }

  @Test
  void 拦截原因都有可读的中文说法() {
    for (String code :
        List.of("SAFETY_NOT_COLLECTED", "BATTERY_NOT_ALLOWED", "LIQUID_NOT_ALLOWED",
            "DANGEROUS_GOODS_NOT_ALLOWED", "MAGNETIC_NOT_ALLOWED", "PHYSICAL_PARAMETERS_MISSING")) {
      assertThat(blockerLabel(code)).isNotBlank().isNotEqualTo(code);
    }
  }

  // ============================================================ 装箱核算（8 拣货）

  @Test
  void 每箱件数按体积除出来并向下取整() {
    // 单件 3000 cm³、每箱 10000 cm³ → 3 件（3.33 向下取整）
    var line = carton("SKU-1", decimal("3000"), decimal("500"), decimal("600"),
        decimal("10000"), decimal("100000"));
    assertThat(line.unitsPerCartonByVolume()).isEqualTo(3);
    assertThat(line.unitsPerCarton()).isEqualTo(3);
    assertThat(line.limitedByWeight()).isFalse();
  }

  @Test
  void 限重更紧时按限重取件数并标明是重量受限() {
    // 单件 1000 cm³（体积上能装 10 件），但毛重 3000g、限重 8000g → 只能装 2 件
    var line = carton("SKU-1", decimal("1000"), decimal("2800"), decimal("3000"),
        decimal("10000"), decimal("8000"));
    assertThat(line.unitsPerCartonByVolume()).isEqualTo(10);
    assertThat(line.unitsPerCartonByWeight()).isEqualTo(2);
    assertThat(line.unitsPerCarton()).isEqualTo(2);
    assertThat(line.limitedByWeight()).isTrue();
  }

  @Test
  void 体积约束更紧时标明不是重量受限() {
    var line = carton("SKU-1", decimal("6000"), decimal("100"), decimal("200"),
        decimal("10000"), decimal("100000"));
    assertThat(line.unitsPerCarton()).isEqualTo(1);
    assertThat(line.limitedByWeight()).isFalse();
  }

  @Test
  void 缺体积时件数只按限重算并在附注里说明() {
    var line = carton("SKU-1", null, decimal("1000"), decimal("2000"),
        decimal("10000"), decimal("10000"));
    assertThat(line.unitsPerCartonByVolume()).isZero();
    assertThat(line.unitsPerCartonByWeight()).isEqualTo(5);
    assertThat(line.unitsPerCarton()).isEqualTo(5);
    assertThat(line.note()).contains("缺长宽高");
  }

  @Test
  void 既没体积也没重量时件数为零且附注说明() {
    var line = carton("SKU-1", null, null, null, decimal("10000"), decimal("10000"));
    assertThat(line.unitsPerCarton()).isZero();
    assertThat(line.note()).contains("算不出");
  }

  // ============================================================ 筛选归一化

  @Test
  void 筛选关键词去掉首尾空格并转小写() {
    assertThat(normalizeKeyword("  AbC ")).isEqualTo("abc");
    assertThat(normalizeKeyword("   ")).isNull();
    assertThat(normalizeKeyword(null)).isNull();
  }

  @Test
  void 包装方式去掉首尾空格后全空当作不筛() {
    assertThat(normalizePackagingType(" 纸箱 ")).isEqualTo("纸箱");
    assertThat(normalizePackagingType("   ")).isNull();
    assertThat(normalizePackagingType(null)).isNull();
  }

  @Test
  void 超长搜索词被拒() {
    assertThatThrownBy(() -> normalizeKeyword("x".repeat(65)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("64");
  }

  static BigDecimal decimal(String value) {
    return new BigDecimal(value);
  }
}
