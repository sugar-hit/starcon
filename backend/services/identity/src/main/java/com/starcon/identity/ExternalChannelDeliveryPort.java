package com.starcon.identity;

import java.util.List;

/**
 * 外部渠道**投递**端口（企业微信 / 钉钉 / 飞书 / 邮件）。
 *
 * <p>蓝图原文：「把选定的通知推送到企业 IM（企业微信/钉钉/飞书）或邮件，
 * 支持卡片化消息与点击跳转。」「**下游 = 外部 IM**。」
 *
 * <p>本接口是本模块与**外部世界**之间唯一的边界。所有对外的 HTTP 调用
 * （Webhook POST / 邮件网关）都必须经它，不允许在服务层直接建连接 ——
 * 理由与 AGENTS.md「外部适配器放在各服务自己的 vendor 包下，只暴露统一接口」同源。
 *
 * <p>🔴 <b>返回结果必须是如实的</b>：{@code delivered=false} 时必须带失败码与原因，
 * 绝不允许「假装投出去了」。
 */
public interface ExternalChannelDeliveryPort {

  /**
   * 逐渠道的通道可用性（给能力声明用）。
   *
   * @param channelCode 渠道码
   * @param connected 这个渠道**有没有已接通的投递通道**
   * @param reason 没接通时的原因
   * @param ownerModule 缺的那块归谁
   */
  record Availability(String channelCode, boolean connected, String reason, String ownerModule) {}

  /**
   * 一次投递请求。
   *
   * @param channelCode 渠道码
   * @param secretRef secret manager 引用串（**不是**凭据本体）
   * @param cardStyle CARD / TEXT
   * @param title 标题
   * @param body 正文（可空）
   * @param linkUrl 站内跳转目标（可空）
   * @param notificationType 通知类型
   * @param notificationRef 上游主键
   * @param recipientMembershipId 收件人
   */
  record DeliveryRequest(
      String channelCode,
      String secretRef,
      String cardStyle,
      String title,
      String body,
      String linkUrl,
      String notificationType,
      String notificationRef,
      String recipientMembershipId) {}

  /**
   * 一次投递的结果。
   *
   * @param delivered 真的投出去了吗
   * @param failureCode 失败码（成功时为 null）
   * @param failureMessage 失败说明（成功时为 null）
   */
  record DeliveryOutcome(boolean delivered, String failureCode, String failureMessage) {}

  /** 逐渠道的通道可用性。 */
  List<Availability> availability();

  /** 投递一次。 */
  DeliveryOutcome deliver(DeliveryRequest request);
}
