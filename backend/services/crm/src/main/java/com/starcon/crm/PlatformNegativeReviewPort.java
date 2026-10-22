package com.starcon.crm;

import java.util.List;
import java.util.UUID;

/**
 * 15.2.1.3 差评处理的<b>平台申诉端口</b>：把「这条差评能不能在平台侧提交申诉」这件事
 * <b>显式化</b>。
 *
 * <p>蓝图 15.2.1.3 的输出句写着「<b>申诉任务</b>」，而关系句写着「<b>直接申诉容易被驳回
 * 且消耗申诉配额</b>」—— 申诉是**要落到平台侧**的动作，所以它需要一个接缝。
 *
 * <p>⚠️ 本仓当前<b>没有</b>任何一个平台的买家评价申诉 / 修改申请适配器
 * （{@code channel/vendor} 下只有 OAuth 适配器）。所以本端口的当前实现
 * {@link UnavailablePlatformNegativeReviewPort} 对所有平台答「未接入」，
 * 服务层据此返回 <b>409</b> 且<b>一行都不写</b>。
 *
 * <p>🔴 <b>为什么不能在这里放一个「假 vendor」</b>：一个返回成功回执的假通道会让
 * 「差评已经申诉掉了」在数据上看起来发生过，而现场一条真申诉都没提交 ——
 * 那正是加粗句警告的「消耗申诉配额却没被处理」。所以本端口的当前实现
 * <b>不返回任何回执</b>，只返回「没接上 + 为什么」。
 *
 * <p>与同服务的 {@link PlatformMessageSendPort}（15.1.1.2 买家站内信发送）**同构** ——
 * 那条接缝处理「能不能发给买家」，本条处理「能不能向平台提交申诉」。
 */
public interface PlatformNegativeReviewPort {

  /**
   * 逐平台回答「该平台的买家评价申诉接口接上了没有」。
   *
   * <p>{@code platformCodes} 来自**真实店铺目录**（channel 的 {@code GET /channel/shops}），
   * 不是本仓写死的平台清单 —— 写死的清单会漏掉新接的平台，或者报出一个根本没在用的平台。
   */
  List<NegativeReviewAvailability> availability(List<String> platformCodes);

  /**
   * 向平台提交一条申诉。
   *
   * @param request 申诉请求（平台 / 店铺 / 评价 / 申诉类型 / 理由 / 正文）
   * @param bearer 调用方的 bearer（转发给平台适配器用；未接入的实现用不到）
   * @return 提交结论；未接入时 {@code connected=false} 且 {@code submitted=false}，
   *     {@code failureReason} 说明原因，{@code platformAppealId} 与 {@code receipt} 恒为 null
   */
  NegativeReviewAppealOutcome submitAppeal(NegativeReviewAppealRequest request, String bearer);

  /** 一个平台的申诉接口接入状态。 */
  record NegativeReviewAvailability(
      String platformCode,
      boolean connected,
      boolean appealSupported,
      String reason,
      String ownerModule) {}

  /** 一次申诉提交的结论。 */
  record NegativeReviewAppealOutcome(
      boolean connected,
      boolean submitted,
      String platformAppealId,
      String receipt,
      String failureReason,
      String ownerModule) {}

  /** 申诉请求。字段刻意与 {@code crm_negative_review_appeal} 一一对应，适配器交付时直接映射。 */
  record NegativeReviewAppealRequest(
      String platformCode,
      UUID shopId,
      String reviewNo,
      String externalReviewId,
      String appealType,
      String causeCode,
      String reason,
      String content) {}
}
