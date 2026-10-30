package com.starcon.platform;

import java.util.UUID;

/**
 * **把事件投递到外部端点**的端口（18.1.2.1「事件订阅」的输出侧）。
 *
 * <p>蓝图 18.1.2.1 的输出写明「事件投递（带签名）」，上游写明「业务事件（outbox）」。
 * 本端口把这一段**显式立起来**，让「缺什么、缺在谁身上」变成一条**可查的事实**
 * （能力声明里的那一维 + 投递端点的拒绝），而不是一句写在注释里的说明。
 *
 * <p>⚠️ **本仓当前没有事件投递执行器**：平台服务里没有从业务服务 outbox 取事件、
 * 再按订阅投递出去的 relay（那需要跨服务读 outbox 与一个后台调度器）。
 * 所以当前实现是 {@link UnavailableEventSubscriptionDeliveryPort}：
 * **如实答「未接入」，一次投递都不发生**。
 *
 * <p>🔴 **端口先立起来，不代表这一段是半成品**：与「有没有投递执行器」无关，
 * 下面四件**本系统内部能力**是真做的 ——
 * 可订阅事件列表（注册表，带出处）、订阅端点配置、签名密钥、事件过滤，
 * 以及重试与死信**策略配置**。
 *
 * <p>⚠️ **投递记录 / 投递日志不在本端口的职责里**：那属于 18.1.2.2「投递记录」。
 * 本端口只负责「发一次」，**不落任何投递明细**。
 */
public interface EventSubscriptionDeliveryPort {

  /** 一次投递请求。 */
  record EventSubscriptionDeliveryRequest(
      UUID subscriptionId,
      String eventType,
      int schemaVersion,
      String targetUrl,
      byte[] payload,
      String signatureHeader,
      String signature) {}

  /**
   * 一次投递的结果。
   *
   * 🔴 **当前实现的 {@code delivered} 与 {@code sent} 恒为 {@code false}** ——
   * 它**不**返回一条「已投递」的回执。一条假的成功回执会让「对方系统已经收到并处理了这个事件」
   * 在数据上看起来发生过，而现场一个包都没发出去。
   */
  record EventSubscriptionDeliveryOutcome(
      boolean connected,
      boolean sent,
      int statusCode,
      String reason,
      String ownerModule) {}

  /** 通道可用性。 */
  record EventSubscriptionDeliveryAvailability(boolean connected, String reason, String ownerModule) {}

  /** 答「通道通不通」。 */
  EventSubscriptionDeliveryAvailability availability();

  /**
   * 投一次事件。
   *
   * <p>⚠️ 当前实现在**任何情况下都不投递、也不写任何投递记录**。
   */
  EventSubscriptionDeliveryOutcome deliver(EventSubscriptionDeliveryRequest request);
}
