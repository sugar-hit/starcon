package com.starcon.channel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 14.5.1.1 达人库的<b>纯计算规则</b>（不碰数据库，可单独单测）。
 *
 * <p>两件在这里算的事：
 *
 * <ol>
 *   <li><b>评分</b>：按租户配置的评分口径（权重 + 归一化上限）把达人效果数据折成一个
 *       0 ~ 1 的分数，再落档位。<b>缺任何一项必需数据 ⇒ 没有评分</b>（不是 0 分）。
 *   <li><b>粉丝层级</b>：粉丝数的分段（DB 触发器也做同一件事，见 V288；
 *       这里的一份是给服务层回显与单测用的口径说明）。
 * </ol>
 *
 * <p>🔴 <b>为什么缺数据就不给分</b>：0 分与「没数据」在运营决策上完全相反 ——
 * 0 分会说「这个达人很差，别投」，「没数据」说的是「你还没录这个达人的效果」。
 * 折算成 0 分会把「还没录」渲染成「录了，而且很差」。
 */
public final class InfluencerRules {
  private InfluencerRules() {}

  /** 粉丝层级分段（与 V288 `stamp_influencer_follower_tier` 的 CASE 逐档一致）。 */
  public static final List<String> FOLLOWER_TIERS =
      List.of("NANO", "MICRO", "MID", "MACRO", "MEGA", "TOP");

  /** 拉黑原因封闭词表（与 V288 `influencer_blacklist.reason_code` 的 CHECK 一致）。 */
  public static final List<String> BLACKLIST_REASON_CODES =
      List.of(
          "FRAUD",
          "INFRINGEMENT",
          "POOR_QUALITY",
          "NON_COMPLIANCE",
          "BRAND_SAFETY",
          "DATA_QUALITY");

  /** 合作状态封闭词表（与 V288 `influencer_cooperation.status` 的 CHECK 一致）。 */
  public static final List<String> COOPERATION_STATUSES =
      List.of("PLANNED", "ACTIVE", "COMPLETED", "CANCELLED");

  /** 达人本地状态（与 V288 `influencer.status` 的 CHECK 一致）。 */
  public static final List<String> INFLUENCER_STATUSES = List.of("ACTIVE", "INACTIVE");

  /** 数据来源（与 V288 `influencer.sync_source` 的 CHECK 一致）。 */
  public static final List<String> SYNC_SOURCES = List.of("MANUAL", "PLATFORM_API");

  /** 平台同步状态（与 V288 `influencer.platform_sync_status` 的 CHECK 一致）。 */
  public static final List<String> PLATFORM_SYNC_STATUSES =
      List.of("LOCAL_ONLY", "PENDING_SYNC", "SYNCED", "FAILED");

  /** 评分保留的小数位（与列的 NUMERIC(19,6) 对齐）。 */
  static final int SCORE_SCALE = 6;

  /**
   * 评分口径（服务层从 {@code influencer_rating_rule} 读出来传进来）。
   *
   * @param weightGmv GMV 权重
   * @param weightEngagement 互动率权重
   * @param weightRefund 退款率权重
   * @param gmvCap GMV 归一化上限（超过按满分算）
   * @param engagementCap 互动率归一化上限
   * @param refundCap 退款率归一化上限
   */
  public record RatingRule(
      BigDecimal weightGmv,
      BigDecimal weightEngagement,
      BigDecimal weightRefund,
      BigDecimal gmvCap,
      BigDecimal engagementCap,
      BigDecimal refundCap) {

    /** 这个口径要求哪些效果数据必须入库（**权重为 0 的那一项不要求**）。 */
    public boolean needsGmv() {
      return weightGmv.signum() > 0;
    }

    public boolean needsEngagement() {
      return weightEngagement.signum() > 0;
    }

    public boolean needsRefund() {
      return weightRefund.signum() > 0;
    }
  }

  /**
   * 按口径算一个达人的评分。
   *
   * <p>🔴 返回 {@code null} 表示<b>算不出来</b>（必需的效果数据还没录），
   * **不是 0 分**。调用方据此把 {@code rating_score} 与 {@code rating_tier_id} 都留空。
   *
   * @param rule 生效的评分口径
   * @param gmv 带货 GMV（null = 还没录）
   * @param engagementRate 互动率（null = 还没录）
   * @param refundRate 退款率（null = 还没录）
   * @return 0 ~ 1 的评分，或 null（算不出来）
   */
  public static BigDecimal score(
      RatingRule rule, BigDecimal gmv, BigDecimal engagementRate, BigDecimal refundRate) {
    if (rule == null) {
      return null;
    }
    // 🔴 必需项缺一个就没有分数 —— 「还没录」不是「录了而且是 0」。
    if (rule.needsGmv() && gmv == null) {
      return null;
    }
    if (rule.needsEngagement() && engagementRate == null) {
      return null;
    }
    if (rule.needsRefund() && refundRate == null) {
      return null;
    }

    BigDecimal total = BigDecimal.ZERO;
    if (rule.needsGmv()) {
      total = total.add(rule.weightGmv().multiply(normalizeUp(gmv, rule.gmvCap())));
    }
    if (rule.needsEngagement()) {
      total =
          total.add(rule.weightEngagement().multiply(normalizeUp(engagementRate, rule.engagementCap())));
    }
    if (rule.needsRefund()) {
      // 🔴 退款率是**负向**指标：退款率 0 拿满分，退款率超过上限拿 0 分。
      total =
          total.add(
              rule.weightRefund().multiply(BigDecimal.ONE.subtract(normalizeUp(refundRate, rule.refundCap()))));
    }
    return total.setScale(SCORE_SCALE, RoundingMode.HALF_UP);
  }

  /** 归一化到 0 ~ 1：**超过上限按满分算**（不封顶的话一个超级达人能把所有人压成 0 分）。 */
  static BigDecimal normalizeUp(BigDecimal value, BigDecimal cap) {
    if (value == null || cap == null || cap.signum() <= 0) {
      return BigDecimal.ZERO;
    }
    BigDecimal ratio = value.divide(cap, SCORE_SCALE, RoundingMode.HALF_UP);
    if (ratio.compareTo(BigDecimal.ONE) > 0) {
      return BigDecimal.ONE;
    }
    if (ratio.signum() < 0) {
      return BigDecimal.ZERO;
    }
    return ratio;
  }

  /**
   * 粉丝层级（与 DB 触发器同一口径）。
   *
   * <p>DB 触发器才是写入时的唯一权威（服务层写什么都不作数），这一份给
   * 单测与「层级分段说明」用 —— 两边必须一致，改一处要改两处。
   */
  public static String followerTier(long followerCount) {
    if (followerCount < 10_000L) {
      return "NANO";
    }
    if (followerCount < 100_000L) {
      return "MICRO";
    }
    if (followerCount < 500_000L) {
      return "MID";
    }
    if (followerCount < 1_000_000L) {
      return "MACRO";
    }
    if (followerCount < 5_000_000L) {
      return "MEGA";
    }
    return "TOP";
  }

  /** 档位是否覆盖这个分数（区间**闭区间**：[min, max]）。 */
  public static boolean tierCovers(BigDecimal minScore, BigDecimal maxScore, BigDecimal score) {
    if (minScore == null || maxScore == null || score == null) {
      return false;
    }
    return score.compareTo(minScore) >= 0 && score.compareTo(maxScore) <= 0;
  }
}
