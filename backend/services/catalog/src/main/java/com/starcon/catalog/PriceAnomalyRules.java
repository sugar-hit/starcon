package com.starcon.catalog;

import com.starcon.catalog.PriceAnomalyModels.AnomalyType;
import com.starcon.catalog.PriceAnomalyModels.PriceChangeSource;
import com.starcon.catalog.PriceAnomalyModels.Severity;
import com.starcon.catalog.PriceAnomalyModels.SubjectKind;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * 5.5.2.1 异常告警的**判定口径**：阈值默认值、价差比例、指纹。
 *
 * <p><b>为什么这些常量住在规则类里而不是数据库</b>：它们是<b>配置默认值</b>，不是数据。
 * 租户要改，走 {@code price_anomaly_rule} 表（那是「站点价差规则」这个输入的落点）；
 * 表里没有对应行时用这里的默认值。默认值的含义是「没人配过时的合理起点」，
 * 而不是「假装租户已经同意了这个阈值」—— 规则行里的 {@code overridden=false}
 * 会在页面上把这件事说出来。
 *
 * <p><b>🔴 禁浮点</b>：全部 BigDecimal，比例统一保留 6 位（{@code numeric(9,6)}）。
 */
public final class PriceAnomalyRules {

  private PriceAnomalyRules() {}

  /** 比例的标度：{@code numeric(9,6)} 的 6 位小数。 */
  public static final int RATIO_SCALE = 6;

  /** 金额的标度：{@code numeric(19,4)} 的 4 位小数。 */
  public static final int AMOUNT_SCALE = 4;

  /** ① 平台价与内部价不一致：默认差 5% 才算异常（低于它多半是舍入或促销噪声）。 */
  public static final BigDecimal DEFAULT_DIVERGENCE_RATIO = new BigDecimal("0.05");

  /** ① 「长期」的默认门槛：差 7 天以上才报 —— 一次就对不上不报。 */
  public static final int DEFAULT_DIVERGENCE_MIN_DURATION_DAYS = 7;

  /** ② 售价低于成本线：默认容忍 0 —— 低于成本线本身就是异常。 */
  public static final BigDecimal DEFAULT_BELOW_COST_RATIO = BigDecimal.ZERO;

  /** ③ 平台自动降价：默认降 2% 以上才算「自动降价」（更小的多半是舍入）。 */
  public static final BigDecimal DEFAULT_AUTO_DISCOUNT_RATIO = new BigDecimal("0.02");

  /** ④ 站点价差倒挂：默认差 10% 以上才算倒挂。 */
  public static final BigDecimal DEFAULT_CROSS_SITE_RATIO = new BigDecimal("0.10");

  /** ① 的默认严重度。 */
  public static final Severity DEFAULT_DIVERGENCE_SEVERITY = Severity.MEDIUM;

  /** ② 的默认严重度：**高** —— 低于成本线是直接的亏损。 */
  public static final Severity DEFAULT_BELOW_COST_SEVERITY = Severity.HIGH;

  /** ③ 的默认严重度。 */
  public static final Severity DEFAULT_AUTO_DISCOUNT_SEVERITY = Severity.MEDIUM;

  /** ④ 的默认严重度：**高** —— 跨区窜货与合规风险。 */
  public static final Severity DEFAULT_CROSS_SITE_SEVERITY = Severity.HIGH;

  /**
   * 平台价相对内部价的偏离比例。
   *
   * @return {@code |平台价 − 内部价| ÷ 内部价}；内部价不是正数时返回 {@code null}
   *     （**不给一个假的 0** —— 内部价为 0 时「偏离比例」没有定义）
   */
  public static BigDecimal divergenceRatio(BigDecimal internalPrice, BigDecimal platformPrice) {
    if (internalPrice == null || platformPrice == null) {
      return null;
    }
    if (internalPrice.signum() <= 0) {
      return null;
    }
    return platformPrice.subtract(internalPrice).abs().divide(internalPrice, RATIO_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * 降价比例：{@code (原价 − 现价) ÷ 原价}。
   *
   * @return 现价不低于原价时返回 {@code null}（那不是降价）
   */
  public static BigDecimal dropRatio(BigDecimal previousPrice, BigDecimal currentPrice) {
    if (previousPrice == null || currentPrice == null) {
      return null;
    }
    if (previousPrice.signum() <= 0) {
      return null;
    }
    BigDecimal drop = previousPrice.subtract(currentPrice);
    if (drop.signum() <= 0) {
      return null;
    }
    return drop.divide(previousPrice, RATIO_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * 两站点之间的价差比例（相对**贵的那一边**）。
   *
   * <p>🔴 站点倒挂的判定必须是<b>两两比对</b>：这是「德国站定价低于美国站」的落点。
   * 用「相对全平台平均价」的算法会把「德国比美国便宜、但比法国贵」这种真实倒挂洗掉。
   *
   * @return 低价站相对高价站低出多少；两者不可比（不是同一比价币种）时返回 {@code null}
   */
  public static BigDecimal siteGapRatio(BigDecimal lowerPrice, BigDecimal higherPrice) {
    if (lowerPrice == null || higherPrice == null) {
      return null;
    }
    if (higherPrice.signum() <= 0) {
      return null;
    }
    return higherPrice.subtract(lowerPrice).divide(higherPrice, RATIO_SCALE, RoundingMode.HALF_UP);
  }

  /** 某类异常的内置默认比例阈值。 */
  public static BigDecimal defaultRatio(AnomalyType type) {
    return switch (type) {
      case PLATFORM_INTERNAL_DIVERGENCE -> DEFAULT_DIVERGENCE_RATIO;
      case BELOW_COST -> DEFAULT_BELOW_COST_RATIO;
      case PLATFORM_AUTO_DISCOUNT -> DEFAULT_AUTO_DISCOUNT_RATIO;
      case CROSS_SITE_INVERSION -> DEFAULT_CROSS_SITE_RATIO;
    };
  }

  /** 某类异常的内置默认「持续天数」门槛。 */
  public static int defaultMinDurationDays(AnomalyType type) {
    return switch (type) {
      case PLATFORM_INTERNAL_DIVERGENCE -> DEFAULT_DIVERGENCE_MIN_DURATION_DAYS;
      // ②③④ 都不是「持续」型判定：低于成本线、站点倒挂、发生过的自动降价，
      // 命中一次就是一次 —— 给它们套「持续 7 天」会让真正在流血的问题被放过。
      case BELOW_COST, PLATFORM_AUTO_DISCOUNT, CROSS_SITE_INVERSION -> 0;
    };
  }

  /** 某类异常的内置默认严重度。 */
  public static Severity defaultSeverity(AnomalyType type) {
    return switch (type) {
      case PLATFORM_INTERNAL_DIVERGENCE -> DEFAULT_DIVERGENCE_SEVERITY;
      case BELOW_COST -> DEFAULT_BELOW_COST_SEVERITY;
      case PLATFORM_AUTO_DISCOUNT -> DEFAULT_AUTO_DISCOUNT_SEVERITY;
      case CROSS_SITE_INVERSION -> DEFAULT_CROSS_SITE_SEVERITY;
    };
  }

  /** 某类异常的默认名（租户没起名时显示用）。 */
  public static String defaultRuleName(AnomalyType type) {
    return type.label() + "（默认阈值）";
  }

  /**
   * 事件的**幂等指纹**。
   *
   * <p>🔴 指纹必须是「异常的稳定身份」，**不能**含时间桶 ——
   * 含了时间桶，重复跑一次检测就会堆出一堆重复事件；不含它，同一个异常才会被
   * 更新成同一条（{@code last_seen_at} / {@code duration_days} 往前走），事件才是**状态**而不是快照。
   *
   * <p>③ 平台自动降价是**发生过的事**而不是当前状态，所以它的指纹**带**降价时刻 ——
   * 同一个站点两次不同的自动降价是两件事，不该合并成一条。
   */
  public static String fingerprint(
      AnomalyType type,
      UUID skuId,
      String platformCode,
      String siteCode,
      String relatedPlatformCode,
      String relatedSiteCode,
      String currency,
      SubjectKind subjectKind,
      String occurrenceAt) {
    return String.join(
        "|",
        type.name(),
        skuId == null ? "" : skuId.toString(),
        nz(platformCode),
        nz(siteCode),
        nz(relatedPlatformCode),
        nz(relatedSiteCode),
        nz(currency),
        subjectKind == null ? "" : subjectKind.name(),
        nz(occurrenceAt));
  }

  /** ③ 平台自动降价专用指纹（带降价时刻）。 */
  public static String discountFingerprint(
      UUID skuId,
      String platformCode,
      String siteCode,
      String currency,
      String occurredAt) {
    return String.join(
        "|",
        AnomalyType.PLATFORM_AUTO_DISCOUNT.name(),
        skuId == null ? "" : skuId.toString(),
        nz(platformCode),
        nz(siteCode),
        "",
        "",
        nz(currency),
        "",
        nz(occurredAt));
  }

  /**
   * 「这次降价是平台降的还是我们改的」—— ③ 的判据。
   *
   * <p>🔴 只有 {@link PriceChangeSource#AUTOMATIC_PLATFORM} 算「平台自动降价」。
   * {@link PriceChangeSource#MANUAL_INTERNAL} 是**我们自己改的**，
   * 把它算成平台自动降价，就等于把自己的调价说成平台干的 ——
   * 那会让「平台在乱降价」这个结论最后查无实据。
   */
  public static boolean isPlatformAutomaticDiscount(PriceChangeSource source) {
    return source == PriceChangeSource.AUTOMATIC_PLATFORM;
  }

  /** 空值兜底：null → 空串（拼指纹用，**不**把 null 写进 key 的中间段）。 */
  private static String nz(String v) {
    return v == null ? "" : v;
  }
}
