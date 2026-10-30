package com.starcon.platform;

import org.springframework.stereotype.Service;

/**
 * {@link EventSubscriptionDeliveryPort} 的**当前实现**：如实答「未接入」。
 *
 * <p>与 platform 的 {@link UnavailableFinanceSoftwarePushPort}、
 * {@link UnavailableAiTextGenerationPort} <b>完全同构</b>：
 * 端口先立起来，实现逐能力答「未接入 + 缺什么 + 归谁」，<b>不返回任何成功回执</b>。
 *
 * <p>🔴 <b>本实现一次投递都不发生</b>：不落投递记录、不落投递日志、不向任何外部地址发包。
 * 服务层拿到 {@code sent=false} 之后直接翻成 <b>409</b> 并结束。
 *
 * <p>⚠️ <b>这不代表本模块是半成品</b>：需要投递执行器的只有「把事件发出去」这一段。
 * 订阅配置侧的五件事（可订阅事件列表、订阅端点、签名密钥、事件过滤、
 * 重试与死信策略）今天就是真实生效的。
 */
@Service
public class UnavailableEventSubscriptionDeliveryPort implements EventSubscriptionDeliveryPort {

  /**
   * 未接入部分的**归属模块**：<b>18.1.2.2</b>（投递记录）。
   *
   * <p>蓝图 18.1.2 下分两格：18.1.2.1「事件订阅」管**订阅配置侧**，
   * 18.1.2.2「投递记录」管**投递执行与投递明细**（事件 ID、订阅方、投递状态、响应码、
   * 重试次数、死信重放）。投递执行器与投递日志同属那一格。
   */
  static final String OWNER_MODULE = "18.1.2.2";

  private static final String REASON =
      "「事件实际投递」**未接入**："
          + "平台服务里没有从业务服务 outbox 取事件并按订阅投递出去的 relay"
              + "（需要跨服务读 outbox + 一个后台调度器），"
              + "所以「把事件真的发到外部端点」这一段没有通道。"
              + "该能力归 **18.1.2.2 投递记录**（投递执行器与投递明细同属那一格）。"
              + "在此之前可以用的路径是："
              + "登记事件定义（带出处）→ 配订阅端点 → 配事件过滤 → 生成签名密钥 → "
              + "用「签名试算」当场核对平台会发的签名头（真算，但不投递、不写投递记录）。";

  @Override
  public EventSubscriptionDeliveryAvailability availability() {
    return new EventSubscriptionDeliveryAvailability(false, REASON, OWNER_MODULE);
  }

  @Override
  public EventSubscriptionDeliveryOutcome deliver(EventSubscriptionDeliveryRequest request) {
    // ⚠️ 这里**不**返回一条「已投递」了事：一条假的成功回执会让「对方系统已经收到并处理了这个事件」
    // 在数据上看起来发生过，而现场一个包都没发出去。
    return new EventSubscriptionDeliveryOutcome(false, false, 0, REASON, OWNER_MODULE);
  }
}
