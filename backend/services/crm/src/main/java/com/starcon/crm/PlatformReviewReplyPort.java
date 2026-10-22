package com.starcon.crm;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 15.2.1.2 评价回复的<b>平台投递端口</b>：把「回复评价」里的
 * <b>「把这条回复真的发到平台上」</b>那一半表达成一个可插拔的接缝。
 *
 * <p>🔴🔴 <b>当前没有任何平台实现这个端口</b> —— channel 的适配层
 * （{@code com.starcon.channel.vendor}）目前<b>只有 OAuth 适配器</b>，
 * <b>没有买家评价回复接口</b>。所以本端口的唯一实现
 * （{@link UnavailablePlatformReviewReplyPort}）对所有平台都答「未接入」。
 *
 * <p>为什么不能顺手编一个：蓝图写的是「输出：回复记录 + <b>平台回执</b>」——
 * 一条返回「已回复」的假回执会让「评价已经回复给买家了」看起来发生过，
 * 而现场一条真回复都没发出去。那比明确答「没发出去」糟得多：
 * 前者会让运营以为不用再跟进，后者会让他们继续跟进。
 *
 * <p>⚠️ 端口<b>不传租户号</b>：调用方转发的是<b>自己的</b> {@code Authorization} bearer，
 * 平台侧仍按已认证成员关系解析租户并强制 RLS。租户隔离因此是<b>两道</b>
 * （crm 侧 RLS + 平台侧），不是本服务替对方决定租户。
 *
 * <p>⚠️ 端口<b>只负责投递，不负责落库</b>：落库是 {@link ReviewReplyService} 的事。
 * 端口返回投递结论，由服务层决定怎么记（记成哪一版投递记录、回复状态怎么流转）。
 */
public interface PlatformReviewReplyPort {

  /**
   * 某个平台的评价回复能力是否已接入。
   *
   * @param available 是否已接入（false ⇒ 投递不可用，<b>不是「已回复」</b>）
   * @param status 状态码：{@code NOT_INTEGRATED} 未接入 / {@code AVAILABLE} 已接入
   * @param reason 人能读的原因（未接入时必填，界面上要显示出来）
   */
  record Availability(boolean available, String status, String reason) {}

  /**
   * 一次投递的请求。
   *
   * @param platformCode 平台
   * @param replyNo 本系统的回复号（平台侧不认识它，只是排查用的凭据）
   * @param reviewId 目标评价在本系统的 id
   * @param externalReviewId 平台侧的评价 id（人工登记的评价可能为空）
   * @param body 回复正文
   * @param locale 回复语言
   * @param replyType 回复形态（公开回复 / 卖家评论追加）
   */
  record DeliveryRequest(
      String platformCode,
      String replyNo,
      UUID reviewId,
      String externalReviewId,
      String body,
      String locale,
      String replyType) {}

  /**
   * 一次投递的结论。
   *
   * @param delivered 平台是否确认收到（false ⇒ <b>绝不</b>写 DELIVERED 状态）
   * @param outcome 投递结果（{@code DELIVERED} / {@code REJECTED} / {@code FAILED} / {@code NOT_INTEGRATED}）
   * @param reason 人能读的原因（未投递成功时必填）
   * @param platformReplyId 平台侧给出的回复 id（平台确认时才有）
   * @param platformReceipt 平台回执原文（平台确认时才有）
   * @param deliveredAt 平台确认送达的时刻（平台确认时才有）
   */
  record DeliveryOutcome(
      boolean delivered,
      String outcome,
      String reason,
      String platformReplyId,
      String platformReceipt,
      OffsetDateTime deliveredAt) {}

  /** 该平台的评价回复能力是否已接入。 */
  Availability availability(String platformCode);

  /**
   * 把一条回复投递到平台。
   *
   * <p>取不到 / 投不了时返回 {@code delivered=false} + 原因，<b>不抛异常</b> ——
   * 未接入是业务状态，不是故障。
   */
  DeliveryOutcome deliver(DeliveryRequest request, String bearer);
}
