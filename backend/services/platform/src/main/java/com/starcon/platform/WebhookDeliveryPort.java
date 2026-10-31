package com.starcon.platform;

import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * 18.1.2.2「投递记录」为 {@link EventSubscriptionDeliveryPort} 提供的**真实实现**。
 *
 * <p>🔴 <b>本类存在的全部理由</b>：18.1.2.1 的 {@code UnavailableEventSubscriptionDeliveryPort}
 * 如实答「未接入」、端点恒 409、一次包都不发 —— 理由写得很清楚：
 * 「投递执行器与投递日志同属那一格（18.1.2.2）」。本类就是那一格的执行侧。
 *
 * <p><b>为什么标 {@code @Primary}</b>：18.1.2.1 的 {@code UnavailableEventSubscriptionDeliveryPort}
 * 仍然留在仓里（本车道不删别人的文件），于是同一个接口有**两个** Spring bean。
 * {@code @Primary} 让注入 {@link EventSubscriptionDeliveryPort} 的地方（本仓只有
 * {@link EventSubscriptionService} 一处）拿到的是**本类**这个真实现，
 * 那一个自然退化为历史实现 —— 不删它、不改它，也不必改 18.1.2.1 的注入代码。
 *
 * <p><b>本类只做三件事</b>：
 *
 * <ol>
 *   <li><b>取可信租户</b>：从事务局部的 {@code app.current_tenant} 读 ——
 *       租户**不**来自任何请求头（伪造 {@code X-Tenant-Id} 头改不动它）。
 *   <li><b>把「投一次」交给 {@link DeliveryRecordService}</b>：落投递记录 → 真的发 HTTP →
 *       把响应码写进投递日志 → 按策略决定重试还是转死信。
 *   <li><b>如实回执</b>：{@code sent=false} 就是「**一个包都没发出去**」，
 *       {@code statusCode=0} 就是「**根本没拿到响应**」。绝不返回一条假的成功回执 ——
 *       一条假的成功回执会让「对方系统已经收到并处理了这个事件」在数据上看起来发生过，
 *       而现场一个包都没发出去。
 * </ol>
 *
 * <p>⚠️ <b>签名不在这里算</b>：18.1.2.1 的 {@code deliver} 端点传过来的签名值是**空串**
 * （它只负责「发一次」的形状，签名归 18.1.2.2）。本模块用**订阅生效中的那把密钥**
 * 自己算 HMAC-SHA256 —— 与 {@code POST /platform/deliveries/publish} 走的是**同一段代码**
 * （{@link DeliveryRecordService#attemptDelivery}），对接方只需要实现一种验签。
 */
@Service
@Primary
public class WebhookDeliveryPort implements EventSubscriptionDeliveryPort {

  /** 本实现的归属模块：18.1.2.2（投递执行与投递明细）。 */
  static final String OWNER_MODULE = "18.1.2.2";

  private static final String REASON =
      "「事件实际投递」**已接入**：投递执行器（真实 HTTP POST，带 HMAC-SHA256 签名头）"
          + "与投递明细都在 18.1.2.2。投递记录、投递日志、响应码、重试与死信、"
          + "手动重投与死信重放都可在「投递记录」页面逐条查。";

  private final DSLContext sql;
  private final DeliveryRecordService deliveries;

  public WebhookDeliveryPort(DSLContext sql, DeliveryRecordService deliveries) {
    this.sql = sql;
    this.deliveries = deliveries;
  }

  @Override
  public EventSubscriptionDeliveryAvailability availability() {
    return new EventSubscriptionDeliveryAvailability(true, REASON, OWNER_MODULE);
  }

  @Override
  public EventSubscriptionDeliveryOutcome deliver(EventSubscriptionDeliveryRequest request) {
    var tenantId = currentTenant();
    var result =
        deliveries.deliverToSubscription(
            tenantId,
            request.subscriptionId(),
            request.eventType(),
            request.schemaVersion(),
            request.payload());
    return new EventSubscriptionDeliveryOutcome(
        result.connected(),
        result.sent(),
        result.statusCode(),
        result.reason(),
        OWNER_MODULE);
  }

  /**
   * 事务局部的租户上下文。
   *
   * <p>🔴 **不读任何请求头**：租户由 {@code AccessService} 依据已认证成员关系解析后
   * 用 {@code set_config('app.current_tenant', …, true)} 绑在**当前事务**上。
   * 没有它 ⇒ 没有可信租户 ⇒ 一行都读不到（RLS 会把全部行挡掉）。
   */
  private UUID currentTenant() {
    var raw =
        sql.fetchOne("select current_setting('app.current_tenant', true) as t").get(0, String.class);
    if (raw == null || raw.isBlank()) {
      throw new IllegalStateException("缺少可信租户上下文：投递必须发生在已认证会话的事务里");
    }
    return UUID.fromString(raw);
  }
}
