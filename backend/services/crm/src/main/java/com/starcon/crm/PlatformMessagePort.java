package com.starcon.crm;

import java.util.List;
import java.util.UUID;

/**
 * 15.1.1.1 的**平台消息同步端口**：把「哪些平台的买家站内信接上了没有」这件事**显式化**。
 *
 * <p>蓝图 15.1.1.1 的输入句写着「平台消息同步（webhook + 拉取）」，
 * 而**这条上游在本车道基线里不存在**：channel 服务的 {@code vendor} 包下只有 13 个
 * <b>OAuth</b> 适配器，<b>没有</b>买家站内信适配器。
 *
 * <p>所以本仓的处置是：<b>端口先立起来，实现如实答「未接入」</b>。
 * 平台适配器交付之后，只要换掉实现类，本模块的同步入口立刻变成真的 ——
 * 而在此之前，入口**明确报 409 且一条数据都不写**。
 *
 * <p>⚠️ 为什么**不能**在这里放一个「假 vendor」：一个返回成功回执的假同步通道
 * 会让「平台消息已经同步进来了」在数据上看起来发生过，而现场一条真实消息都还没有。
 * 那比明确报「未接入」糟得多 —— 前者让运营以为收件箱是全的。
 *
 * <p>同理，本端口**不返回任何硬编码消息列表**：「没有消息」与「读不到消息」
 * 必须是两件不同的事。
 */
public interface PlatformMessagePort {

  /**
   * 逐平台回答「买家站内信适配器接上了没有」。
   *
   * <p>{@code platformCodes} 来自**真实店铺目录**（channel 的 {@code GET /channel/shops}），
   * 不是本仓写死的平台清单 —— 写死的清单会漏掉新接的平台，或者报出一个根本没在用的平台。
   */
  List<PlatformMessageAvailability> availability(List<String> platformCodes);

  /**
   * 拉取某个平台某个店铺的买家站内信。
   *
   * @param since 起点时刻（ISO-8601，可空表示从头拉）
   * @return 拉取结论；未接入时 {@code connected=false} 且 {@code reason} 说明原因，
   *     {@code messages} 必为空列表
   */
  PlatformMessageFetch fetch(String platformCode, UUID shopId, String since, String bearer);

  /**
   * 一个平台的接入状态。
   *
   * @param ownerModule 该能力交付之后才会变成 true 的归属模块（2 = 渠道与店铺）
   */
  record PlatformMessageAvailability(
      String platformCode, boolean connected, String reason, String ownerModule) {}

  /**
   * 一次拉取的结论。
   *
   * @param messages 平台侧消息；未接入时**必为空**（不是「没有消息」，是「读不到」）
   */
  record PlatformMessageFetch(
      boolean connected,
      int fetchedCount,
      List<PlatformMessage> messages,
      String reason,
      String ownerModule) {}

  /**
   * 平台侧一条买家消息（**当前没有任何实现会产出它**）。
   *
   * <p>字段刻意与本服务的 {@code crm_message} 一一对应：适配器交付时直接映射，
   * 不需要在本服务里做二次翻译 —— 翻译层是「平台差异」漏进业务层的入口。
   *
   * @param buyerReference 买家标识（必填）
   * @param orderReference 订单标识（必填）—— 蓝图加粗句：消息必须关联到买家与订单
   */
  record PlatformMessage(
      String externalMessageId,
      String shopCode,
      String buyerReference,
      String orderReference,
      String subject,
      String body,
      String direction,
      String messageType,
      String platformTime,
      List<PlatformMessageAttachment> attachments) {}

  /** 平台侧消息的一个附件。 */
  record PlatformMessageAttachment(
      String fileName, String fileUrl, String contentType, java.math.BigDecimal sizeBytes) {}
}
