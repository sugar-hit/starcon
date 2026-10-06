package com.starcon.catalog;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 3.1.2.3 外部条码 scheme 与校验位的纯函数单测。
 *
 * <p>条码写错的代价出现在**拣货现场**（08 扫不出来、扫错货），而校验发生在导入那一刻。
 * 所以校验必须便宜、可穷举、可单测——这里把 GS1 mod-10 的每种 scheme 都钉一遍。
 */
class BarcodeSchemeTest {

  /** 三条手工核对过校验位的真实格式样例：EAN-8 / EAN-13 / UPC-A。 */
  @Test
  void acceptsRealGtinSamples() {
    assertThat(BarcodeScheme.problem("EAN8", "96385074")).isNull();
    assertThat(BarcodeScheme.problem("EAN13", "4006381333931")).isNull();
    assertThat(BarcodeScheme.problem("UPCA", "036000291452")).isNull();
  }

  @Test
  void acceptsAnyCasingAndTrimsTheValue() {
    assertThat(BarcodeScheme.normalize("ean13")).isEqualTo("EAN13");
    assertThat(BarcodeScheme.problem("ean13", "  4006381333931 ")).isNull();
  }

  @Test
  void rejectsWrongLengthWrongCharsetAndWrongCheckDigit() {
    // 位数不对
    assertThat(BarcodeScheme.problem("EAN13", "400638133393"))
        .contains("13 位").contains("12 位");
    assertThat(BarcodeScheme.problem("UPCA", "0360002914")).contains("12 位");
    // 含非数字
    assertThat(BarcodeScheme.problem("EAN13", "40063813339X1")).contains("只能由数字组成");
    // 校验位错：末位改掉一位
    assertThat(BarcodeScheme.problem("EAN13", "4006381333930")).contains("校验位不正确");
    assertThat(BarcodeScheme.problem("EAN8", "96385075")).contains("校验位不正确");
  }

  @Test
  void tellsUserWhichCheckDigitWasExpected() {
    // 入参是数据位（不含校验位）：400638133393 应填 1，而不是 0。
    assertThat(BarcodeScheme.expectedCheckDigit("400638133393")).isEqualTo('1');
    assertThat(BarcodeScheme.problem("EAN13", "4006381333930")).contains("末位应为 1");
  }

  @Test
  void rejectsUnknownSchemeAndBlankValue() {
    assertThat(BarcodeScheme.problem("GS1-128", "ABC")).contains("不支持的条码 scheme").contains("EAN13");
    assertThat(BarcodeScheme.problem("", "123")).contains("scheme 不能为空");
    assertThat(BarcodeScheme.problem("EAN13", "")).contains("条码值不能为空");
    assertThat(BarcodeScheme.problem("EAN13", "   ")).contains("条码值不能为空");
    assertThat(BarcodeScheme.problem("CUSTOM", "")).contains("条码值不能为空");
  }

  /** 自定义 scheme 不套 GTIN 规则：它没有固定位数与校验位，套了会把合法条码误拒。 */
  @Test
  void customSchemeAcceptsAnyNonBlankValue() {
    assertThat(BarcodeScheme.problem("CUSTOM", "SUPPLIER-A-9931")).isNull();
    assertThat(BarcodeScheme.problem("CUSTOM", "123")).isNull();
    assertThat(BarcodeScheme.problem("CUSTOM", "A1-2_3")).isNull();
    // 仍然要挡住长度越界，否则会把超长串塞进 varchar(128)。
    assertThat(BarcodeScheme.problem("CUSTOM", "X".repeat(129))).contains("超过 128 位上限");
    assertThat(BarcodeScheme.problem("CUSTOM", "X".repeat(128))).isNull();
  }

  @Test
  void supportsEveryDeclaredSchemeAndLabelsThem() {
    for (String scheme : BarcodeScheme.SCHEMES) {
      assertThat(BarcodeScheme.label(scheme)).isNotBlank().isNotEqualTo(scheme);
    }
    assertThat(BarcodeScheme.label("EAN13")).isEqualTo("EAN-13");
    assertThat(BarcodeScheme.label("UPCE")).isEqualTo("UPC-E");
    assertThat(BarcodeScheme.label("GTIN14")).isEqualTo("GTIN-14");
    assertThat(BarcodeScheme.label("CUSTOM")).isEqualTo("自定义");
    // 未声明的 scheme 原样返回，不抛异常（列表回显历史数据时要能显示）。
    assertThat(BarcodeScheme.label("UNKNOWN")).isEqualTo("UNKNOWN");
  }

  @Test
  void everyGtinSchemeRoundTripsThroughTheCheckDigit() {
    // 用算法自己生成合法样例，确保每个 scheme 的位数与权重都对得上。
    for (String scheme : List.of("EAN8", "EAN13", "UPCA", "UPCE", "GTIN14")) {
      int width =
          switch (scheme) {
            case "EAN8", "UPCE" -> 7;
            case "UPCA" -> 11;
            case "EAN13" -> 12;
            default -> 13;
          };
      String body = "1234567890".repeat(2).substring(0, width);
      String full = body + BarcodeScheme.expectedCheckDigit(body);
      assertThat(BarcodeScheme.problem(scheme, full)).as(scheme).isNull();
      assertThat(BarcodeScheme.validCheckDigit(full)).as(scheme).isTrue();
    }
  }

  /** seller_sku 不是外部商品条码，scheme 词表里不该有它的位置。 */
  @Test
  void sellerSkuIsNotASupportedScheme() {
    assertThat(BarcodeScheme.SCHEMES).doesNotContain("SELLER_SKU", "PLATFORM", "ASIN");
    assertThat(BarcodeScheme.problem("SELLER_SKU", "ABC-1")).contains("不支持的条码 scheme");
  }
}