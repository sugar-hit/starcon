package com.starcon.channel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 14.5.2.1 带货归因的<b>纯计算规则</b>（不碰数据库，可单独单测）。
 *
 * <p>这里算的是蓝图职责句里的每一个数：
 *
 * <ol>
 *   <li><b>归因权重</b>：一条合作的效果有多少落到某个达人身上
 *       （合作全额归因 / 同活动内按粉丝数或合作 GMV 占比分摊）。
 *   <li><b>归因结果</b>：归因 GMV / 订单量 / 退款额 / 退款率 / 有效订单量
 *       / 有效粉丝转化率（**第三口径**，与档案 GMV、合作历史合计都不同）。
 *   <li><b>佣金成本</b>：按计提口径算佣金金额、ROI 与扣佣金后的净额。
 *   <li><b>归因窗口</b>：合作结束多久之内还允许新增归因。
 * </ol>
 *
 * <p>🔴 <b>为什么「缺数据就不给数」</b>：0 与「没数据」在运营决策上完全相反 ——
 * 0 佣金会说「这个达人不用付佣金」（于是 ROI 算成无穷大），
 * 「没数据」说的是「你还没录这个达人的佣金」。
 * 所以缺项一律给 {@code null}，由服务层原样落库（不可比），**绝不折成 0**。
 */
public final class InfluencerAttributionRules {
  private InfluencerAttributionRules() {}

  // ---- 封闭词表（与 V290 的 CHECK 逐字一致）----

  /** 归因模型。 */
  public static final List<String> ATTRIBUTION_MODELS =
      List.of("COOPERATION_FULL", "CAMPAIGN_POOL_SPLIT");

  /** 分摊权重基准。 */
  public static final List<String> POOL_WEIGHT_BASES =
      List.of("FOLLOWER_SHARE", "COOPERATION_GMV_SHARE");

  /** 退款口径。 */
  public static final List<String> REFUND_TREATMENTS = List.of("GROSS", "NET");

  /** 佣金计提口径。 */
  public static final List<String> COMMISSION_BASES =
      List.of("COOPERATION_COMMISSION", "RATE_ON_ATTRIBUTED_GMV");

  /** 佣金计提状态。 */
  public static final List<String> ACCRUAL_STATUSES = List.of("PENDING", "ACCRUED", "REVERSED");

  /** 金额小数位（与列的 NUMERIC(19,4) 对齐）。 */
  public static final int MONEY_SCALE = 4;

  /** 比率 / 数量小数位（与列的 NUMERIC(19,6) 对齐）。 */
  public static final int RATIO_SCALE = 6;

  // ==================================================================
  // 归因权重
  // ==================================================================

  /**
   * 归因权重：一条合作的效果有多少落到某个达人身上。
   *
   * <p>🔴 返回 {@code null} 表示<b>分摊基准不可用</b>（例如按粉丝数分摊但
   * 活动内粉丝数合计为 0）—— 这种情况下**不能**悄悄退回「全额归因」，
   * 那会把「按触达分摊」的规则变成「谁合作多谁拿走」，口径说一套做一套。
   * 服务层据此跳过这条合作并在批次结果里计数。
   *
   * @param model 归因模型
   * @param basis 分摊权重基准（只在 CAMPAIGN_POOL_SPLIT 下有意义）
   * @param ownFollowers 该达人在活动内的粉丝数
   * @param poolFollowers 活动内各位达人粉丝数合计
   * @param ownGmv 该达人在活动内的合作 GMV
   * @param poolGmv 活动内合作 GMV 合计
   * @return 0 ~ 1 的权重，或 null（分摊基准不可用）
   */
  public static BigDecimal weight(
      String model,
      String basis,
      long ownFollowers,
      long poolFollowers,
      BigDecimal ownGmv,
      BigDecimal poolGmv) {
    if ("COOPERATION_FULL".equals(model)) {
      return BigDecimal.ONE.setScale(RATIO_SCALE, RoundingMode.HALF_UP);
    }
    if (!"CAMPAIGN_POOL_SPLIT".equals(model)) {
      return null;
    }
    if ("FOLLOWER_SHARE".equals(basis)) {
      if (poolFollowers <= 0L) {
        return null;
      }
      return BigDecimal.valueOf(ownFollowers)
          .divide(BigDecimal.valueOf(poolFollowers), RATIO_SCALE, RoundingMode.HALF_UP);
    }
    if ("COOPERATION_GMV_SHARE".equals(basis)) {
      if (poolGmv == null || poolGmv.signum() <= 0) {
        return null;
      }
      if (ownGmv == null) {
        return null;
      }
      return ownGmv.divide(poolGmv, RATIO_SCALE, RoundingMode.HALF_UP);
    }
    return null;
  }

  // ==================================================================
  // 归因结果
  // ==================================================================

  /** 归因毛额 = 归因基数 × 权重。基数没录 ⇒ null（不可比，不是 0）。 */
  public static BigDecimal grossGmv(BigDecimal baseGmv, BigDecimal weight) {
    if (baseGmv == null || weight == null) {
      return null;
    }
    return baseGmv.multiply(weight).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  /** 归因退款额 = 归因毛额 × 源退款率。退款率没录 ⇒ null。 */
  public static BigDecimal refundAmount(BigDecimal grossGmv, BigDecimal sourceRefundRate) {
    if (grossGmv == null || sourceRefundRate == null) {
      return null;
    }
    return grossGmv.multiply(sourceRefundRate).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * 归因 GMV（第三口径）。
   *
   * <p>GROSS 毛额 / NET 净额（扣掉退款额，**不为负**）。
   *
   * <p>🔴 恒等式：<b>归因毛额 = 归因基数 × 权重</b>（两种退款口径下都成立）——
   * 报表据此算加权退款率（不需要另存一个毛额列）。
   * GROSS 下「归因 GMV = 毛额」，NET 下「归因 GMV + 退款额 = 毛额」。
   */
  public static BigDecimal attributedGmv(
      BigDecimal grossGmv, BigDecimal refundAmount, String refundTreatment) {
    if (grossGmv == null) {
      return null;
    }
    if ("NET".equals(refundTreatment)) {
      BigDecimal refund = refundAmount == null ? BigDecimal.ZERO : refundAmount;
      BigDecimal net = grossGmv.subtract(refund);
      return net.signum() < 0 ? BigDecimal.ZERO.setScale(MONEY_SCALE) : net;
    }
    return grossGmv;
  }

  /** 归因订单量 = 源订单量 × 权重。 */
  public static BigDecimal attributedOrderCount(BigDecimal sourceOrderCount, BigDecimal weight) {
    if (sourceOrderCount == null || weight == null) {
      return null;
    }
    return sourceOrderCount.multiply(weight).setScale(RATIO_SCALE, RoundingMode.HALF_UP);
  }

  /** 有效订单量 = 归因订单量 × (1 − 退款率)（扣掉退款那部分）。 */
  public static BigDecimal effectiveOrderCount(
      BigDecimal attributedOrderCount, BigDecimal sourceRefundRate) {
    if (attributedOrderCount == null) {
      return null;
    }
    if (sourceRefundRate == null) {
      return attributedOrderCount;
    }
    return attributedOrderCount
        .multiply(BigDecimal.ONE.subtract(sourceRefundRate))
        .setScale(RATIO_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * 有效粉丝转化率 = 有效订单量 ÷ 粉丝数。
   *
   * <p>🔴 粉丝数是 0 ⇒ {@code null}（不是 0，也不是无穷大）：没录粉丝数就无从谈转化。
   */
  public static BigDecimal followerConversionRate(
      BigDecimal effectiveOrderCount, long followerCount) {
    if (effectiveOrderCount == null || followerCount <= 0L) {
      return null;
    }
    return effectiveOrderCount
        .divide(BigDecimal.valueOf(followerCount), RATIO_SCALE, RoundingMode.HALF_UP);
  }

  // ==================================================================
  // 佣金成本与投产比
  // ==================================================================

  /**
   * 佣金金额。
   *
   * <p>🔴 <b>佣金不按归因权重缩放</b>：佣金是**已经发生的成本**，
   * 成本不会因为归因口径变了就变。按权重缩放会让「换了口径 ⇒ 成本变小」，
   * 那是在用口径粉饰成本 —— 正好是蓝图加粗句要防的事。
   *
   * @param basis 佣金计提口径
   * @param sourceCommission 合作记录里已录的佣金（null = 还没录）
   * @param attributedGmv 归因 GMV
   * @param rate 佣金比例（只在 RATE_ON_ATTRIBUTED_GMV 下有意义）
   * @return 佣金金额，或 null（还没录 / 口径要的比例没配）
   */
  public static BigDecimal commissionAmount(
      String basis, BigDecimal sourceCommission, BigDecimal attributedGmv, BigDecimal rate) {
    if ("COOPERATION_COMMISSION".equals(basis)) {
      return sourceCommission == null ? null : sourceCommission.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
    if ("RATE_ON_ATTRIBUTED_GMV".equals(basis)) {
      if (rate == null || attributedGmv == null) {
        return null;
      }
      return attributedGmv.multiply(rate).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
    return null;
  }

  /** 计提基数（比例乘在谁身上）。 */
  public static BigDecimal commissionBasisAmount(
      String basis, BigDecimal sourceCommission, BigDecimal attributedGmv) {
    if ("COOPERATION_COMMISSION".equals(basis)) {
      return sourceCommission == null ? null : sourceCommission.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
    if ("RATE_ON_ATTRIBUTED_GMV".equals(basis)) {
      return attributedGmv;
    }
    return null;
  }

  /**
   * 归因 ROI = 归因 GMV ÷ 佣金成本。
   *
   * <p>🔴 佣金没录（null）或佣金为 0 ⇒ {@code null}：
   * 「佣金还没录」不是「佣金是 0，ROI 无穷大」。
   */
  public static BigDecimal roi(BigDecimal attributedGmv, BigDecimal commissionAmount) {
    if (attributedGmv == null || commissionAmount == null) {
      return null;
    }
    if (commissionAmount.signum() <= 0) {
      return null;
    }
    return attributedGmv
        .divide(commissionAmount, RATIO_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * 扣佣金后的净额。
   *
   * <p>🔴 <b>只扣佣金</b> —— 样品 / 履约 / 平台费都不在里面（那些在本服务外），
   * 所以这个数**不是净收益**，界面上必须写明「仅扣佣金」。
   *
   * <p>🔴 佣金**没录** ⇒ {@code null}：成本都不知道，凭什么报净额。
   */
  public static BigDecimal netAfterCommission(
      BigDecimal attributedGmv, BigDecimal commissionAmount) {
    if (attributedGmv == null || commissionAmount == null) {
      return null;
    }
    return attributedGmv.subtract(commissionAmount).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  // ==================================================================
  // 归因窗口
  // ==================================================================

  /**
   * 这条合作还在不在归因窗口内。
   *
   * <p>窗口从**合作结束时刻**起算：{@code endedAt + windowDays >= now}。
   * 合作**还没结束**（{@code endedAt == null}）⇒ 永远在窗口内。
   *
   * <p>🔴 窗口只决定**能不能新增归因**（账期过了不能补记），
   * <b>已经归因过的记录不会被抹掉</b> —— 已归因是事实（佣金按它计提过）。
   */
  public static boolean withinWindow(OffsetDateTime endedAt, int windowDays, OffsetDateTime now) {
    if (endedAt == null) {
      return true;
    }
    return !endedAt.plus(windowDays, ChronoUnit.DAYS).isBefore(now);
  }
}
