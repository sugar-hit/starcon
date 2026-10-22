package com.starcon.crm;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 15.1.1.2 的**平台消息发送端口**：把「哪个平台的买家站内信能发出去」这件事**显式化**。
 *
 * <p>蓝图 15.1.1.2 的输出句写着「发送记录 + 平台回执 + 失败原因」，
 * 而**这条下游在本车道基线里不存在**：channel 服务的 {@code vendor} 包下只有 13 个
 * <b>OAuth</b> 适配器，<b>没有</b>买家站内信适配器（{@link PlatformMessagePort}
 * 已经把同一件事的**同步**侧标成未接入）。
 *
 * <p>所以本仓的处置是：<b>端口先立起来，实现如实答「未接入」</b>。
 * 平台适配器交付之后，只要换掉实现类，本模块的投递入口立刻变成真的 ——
 * 而在此之前，服务层据此**拒绝** {@code transport='PLATFORM_API'} 的写入，
 * 一次数据都不写。
 *
 * <p>⚠️ 为什么**不能**在这里放一个「假 vendor」：一个返回成功回执的假通道
 * 会让「消息已经发给买家了」在数据上看起来发生过，而现场一条真消息都还没发出去。
 * 蓝图加粗句「<b>发送失败要保留原消息状态</b>，不能让客服以为已回复」防的就是这件事：
 * 一条假回执会让客服以为已经回过，而买家那边一个字都没收到。
 *
 * <p>同理，本端口**不返回任何硬编码的「已发送」**：没有平台通道与
 * 「已经发出去了」是**两件不同的事**，必须是两件不同的事。
 */
public interface PlatformMessageSendPort {

  /**
   * 逐平台回答「买家站内信发送适配器接上了没有」。
   *
   * <p>{@code platformCodes} 来自**真实店铺目录**（channel 的 {@code GET /channel/shops}），
   * 不是本仓写死的平台清单 —— 写死的清单会漏掉新接的平台，或者报出一个根本没在用的平台。
   */
  List<PlatformSendAvailability> availability(List<String> platformCodes);

  /**
   * 把一条回复投到平台侧。
   *
   * @param request 投递请求（平台 / 店铺 / 买家 / 订单 / 正文 / 附件）
   * @param bearer 调用方的 bearer（转发给平台适配器用；本实现用不到）
   * @return 投递结论；未接入时 {@code connected=false} 且 {@code sent=false}，
   *     {@code failureReason} 说明原因
   */
  PlatformSendOutcome send(PlatformSendRequest request, String bearer);

  /**
   * 一个平台的接入状态。
   *
   * @param ownerModule 该能力交付之后才会变成 true 的归属模块（2 = 渠道与店铺）
   */
  record PlatformSendAvailability(
      String platformCode, boolean connected, String reason, String ownerModule) {}

  /** 一次投递的结论。 */
  record PlatformSendOutcome(
      boolean connected,
      boolean sent,
      String platformMessageId,
      String receipt,
      String failureReason,
      String ownerModule) {}

  /** 投递请求。字段刻意与 {@code crm_message_send} 一一对应，适配器交付时直接映射。 */
  record PlatformSendRequest(
      String platformCode,
      UUID shopId,
      String buyerReference,
      String orderReference,
      String subject,
      String body,
      List<PlatformSendAttachment> attachments) {}

  /** 平台侧投递的一个附件。 */
  record PlatformSendAttachment(
      String fileName, String fileUrl, String contentType, BigDecimal sizeBytes) {}
}
