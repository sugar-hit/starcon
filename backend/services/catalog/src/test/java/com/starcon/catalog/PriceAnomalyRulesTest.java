package com.starcon.catalog;

import static org.assertj.core.api.Assertions.*;

import com.starcon.catalog.PriceAnomalyModels.AnomalyType;
import com.starcon.catalog.PriceAnomalyModels.PriceChangeSource;
import com.starcon.catalog.PriceAnomalyModels.Severity;
import com.starcon.catalog.PriceAnomalyModels.SubjectKind;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 5.5.2.1 异常告警的**判定口径**单元测试（不碰数据库）。
 *
 * <p>这些是纯函数：价差比例、降价比例、站点价差、指纹、默认阈值。
 * 之所以单独测：它们是判定口径的**唯一实现** —— 列表、明细、检测三处若各算一次，
 * 迟早出现「清单说差 5%、详情说差 4%」这种对不上的数。
 */
class PriceAnomalyRulesTest {

  @Test
  void 平台价相对内部价的偏离比例取绝对值并保留六位() {
    assertThat(PriceAnomalyRules.divergenceRatio(new BigDecimal("10.00"), new BigDecimal("12.00")))
        .isEqualByComparingTo("0.200000");
    assertThat(PriceAnomalyRules.divergenceRatio(new BigDecimal("10.00"), new BigDecimal("8.00")))
        .isEqualByComparingTo("0.200000");
  }

  @Test
  void 内部价不是正数时不给一个假的零() {
    // 🔴 内部价为 0 时「偏离比例」没有定义 —— 给 0 会把「比不出来」说成「完全一致」。
    assertThat(PriceAnomalyRules.divergenceRatio(new BigDecimal("0"), new BigDecimal("12.00"))).isNull();
    assertThat(PriceAnomalyRules.divergenceRatio(null, new BigDecimal("12.00"))).isNull();
    assertThat(PriceAnomalyRules.divergenceRatio(new BigDecimal("10.00"), null)).isNull();
  }

  @Test
  void 降价比例只在真的降了的时候才给数() {
    assertThat(PriceAnomalyRules.dropRatio(new BigDecimal("10.00"), new BigDecimal("8.00")))
        .isEqualByComparingTo("0.200000");
    // 没降（涨价或持平）⇒ 不是降价事件。
    assertThat(PriceAnomalyRules.dropRatio(new BigDecimal("10.00"), new BigDecimal("10.00"))).isNull();
    assertThat(PriceAnomalyRules.dropRatio(new BigDecimal("10.00"), new BigDecimal("12.00"))).isNull();
    assertThat(PriceAnomalyRules.dropRatio(new BigDecimal("0"), new BigDecimal("8.00"))).isNull();
  }

  @Test
  void 站点价差比例相对贵的那一边算() {
    // 德国站 19.00 vs 美国站 25.00 ⇒ 低 24%。
    assertThat(PriceAnomalyRules.siteGapRatio(new BigDecimal("19.00"), new BigDecimal("25.00")))
        .isEqualByComparingTo("0.240000");
    assertThat(PriceAnomalyRules.siteGapRatio(new BigDecimal("19.00"), new BigDecimal("19.00")))
        .isEqualByComparingTo("0.000000");
    assertThat(PriceAnomalyRules.siteGapRatio(new BigDecimal("19.00"), null)).isNull();
  }

  @Test
  void 指纹是异常的稳定身份不含时间桶() {
    var skuId = UUID.randomUUID();
    var first =
        PriceAnomalyRules.fingerprint(
            AnomalyType.PLATFORM_INTERNAL_DIVERGENCE,
            skuId,
            "AMZN",
            "US",
            "",
            "",
            "USD",
            null,
            "");
    var second =
        PriceAnomalyRules.fingerprint(
            AnomalyType.PLATFORM_INTERNAL_DIVERGENCE,
            skuId,
            "AMZN",
            "US",
            "",
            "",
            "USD",
            null,
            "");
    // 🔴 同一异常重复检测必须落到同一条事件上 —— 指纹相同是这件事的技术落点。
    assertThat(first).isEqualTo(second);

    // 站点不同 ⇒ 不同身份。
    var other =
        PriceAnomalyRules.fingerprint(
            AnomalyType.PLATFORM_INTERNAL_DIVERGENCE,
            skuId,
            "AMZN",
            "DE",
            "",
            "",
            "USD",
            null,
            "");
    assertThat(other).isNotEqualTo(first);

    // ④ 站点倒挂的指纹必须**分得清是哪一对站点**。
    var pairOne =
        PriceAnomalyRules.fingerprint(
            AnomalyType.CROSS_SITE_INVERSION, skuId, "AMZN", "DE", "AMZN", "US", "EUR", null, "");
    var pairTwo =
        PriceAnomalyRules.fingerprint(
            AnomalyType.CROSS_SITE_INVERSION, skuId, "AMZN", "DE", "AMZN", "FR", "EUR", null, "");
    assertThat(pairOne).isNotEqualTo(pairTwo);
  }

  @Test
  void 平台自动降价的指纹带降价时刻因为它是发生过的事() {
    var skuId = UUID.randomUUID();
    var at = "2026-10-20T00:00:00Z";
    var later = "2026-10-21T00:00:00Z";
    var first = PriceAnomalyRules.discountFingerprint(skuId, "AMZN", "US", "USD", at);
    var second = PriceAnomalyRules.discountFingerprint(skuId, "AMZN", "US", "USD", later);
    // 🔴 同一个站点两次不同的自动降价是**两件事**，不能合并成一条。
    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void 只有平台自动降的才算平台自动降价() {
    assertThat(PriceAnomalyRules.isPlatformAutomaticDiscount(PriceChangeSource.AUTOMATIC_PLATFORM)).isTrue();
    // 🔴 我们自己改的不算 —— 把自己的调价说成平台干的，结论最后查无实据。
    assertThat(PriceAnomalyRules.isPlatformAutomaticDiscount(PriceChangeSource.MANUAL_INTERNAL)).isFalse();
    assertThat(PriceAnomalyRules.isPlatformAutomaticDiscount(PriceChangeSource.PLATFORM_FEEDBACK)).isFalse();
    assertThat(PriceAnomalyRules.isPlatformAutomaticDiscount(PriceChangeSource.UNKNOWN)).isFalse();
  }

  @Test
  void 每类异常都有内置默认阈值且比例都在合法区间() {
    for (var type : AnomalyType.values()) {
      var ratio = PriceAnomalyRules.defaultRatio(type);
      assertThat(ratio).isNotNull();
      assertThat(ratio.signum()).isGreaterThanOrEqualTo(0);
      assertThat(ratio.compareTo(BigDecimal.ONE)).isLessThan(0);
      assertThat(PriceAnomalyRules.defaultMinDurationDays(type)).isGreaterThanOrEqualTo(0);
      assertThat(PriceAnomalyRules.defaultSeverity(type)).isNotNull();
    }
    // 「长期」是 ① 的专属门槛：其它三类命中一次就是一次。
    assertThat(PriceAnomalyRules.defaultMinDurationDays(AnomalyType.PLATFORM_INTERNAL_DIVERGENCE)).isEqualTo(7);
    assertThat(PriceAnomalyRules.defaultMinDurationDays(AnomalyType.BELOW_COST)).isZero();
    assertThat(PriceAnomalyRules.defaultMinDurationDays(AnomalyType.CROSS_SITE_INVERSION)).isZero();
    // ② 低于成本线与 ④ 站点倒挂是直接亏损 / 窜货风险，默认严重度为高。
    assertThat(PriceAnomalyRules.defaultSeverity(AnomalyType.BELOW_COST)).isEqualTo(Severity.HIGH);
    assertThat(PriceAnomalyRules.defaultSeverity(AnomalyType.CROSS_SITE_INVERSION)).isEqualTo(Severity.HIGH);
  }

  @Test
  void 低于成本线的主体分平台价与内部价两条() {
    var skuId = UUID.randomUUID();
    var platform =
        PriceAnomalyRules.fingerprint(
            AnomalyType.BELOW_COST, skuId, "AMZN", "US", "", "", "USD", SubjectKind.PLATFORM_PRICE, "");
    var internal =
        PriceAnomalyRules.fingerprint(
            AnomalyType.BELOW_COST, skuId, "AMZN", "US", "", "", "USD", SubjectKind.INTERNAL_PRICE, "");
    // 🔴 平台价与内部价各自成一条事件 —— 两个不同的价，各自低于成本线就是两件事。
    assertThat(platform).isNotEqualTo(internal);
  }
}
