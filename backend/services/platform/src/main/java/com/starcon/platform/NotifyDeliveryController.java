package com.starcon.platform;

import static com.starcon.platform.NotifyDeliveryModels.*;

import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 18.5.2.1「投递记录」的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /platform/notifications}</b>：同服务里已有
 * {@code /platform/ai/templates}（18.5.1.1 的<b>模板</b>，只读不改）、
 * {@code /platform/automation-rules}（18.3.1.1）、
 * {@code /platform/deliveries}（18.1.2.2 的<b>webhook 投递</b>）——
 * 四个前缀管的是完全不同的东西，本模块**不抢**它们的路由。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p><b>权限码 {@code platform.notify_delivery.read / .create / .retry / .unsubscribe}</b>：
 * 「看投递记录」「登记一次投递」「发起重试」「登记退订」是**四个独立的码** ——
 * 能看的人不该顺手就能替客户退订。
 *
 * <p>🔴 <b>本模块不执行投递</b>：真正把消息发到渠道的是下游 1.3.2.2 与 15.5.2。
 * 这里的端点是<b>登记</b>入口：调用方拿着渠道执行器的真实结果来登记。
 */
@RestController
@RequestMapping("/platform/notifications")
public class NotifyDeliveryController {

  private final AccessService access;
  private final NotifyDeliveryService deliveries;

  public NotifyDeliveryController(AccessService access, NotifyDeliveryService deliveries) {
    this.access = access;
    this.deliveries = deliveries;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "platform.notify_delivery." + permission);
    return access.run(actor, "platform.notify_delivery." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /** 能力声明：哪些真交付、哪些还没交付、归谁。 */
  @GetMapping("/capabilities")
  public NotifyDeliveryCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> deliveries.capabilities());
  }

  // ================================================================ 投递记录

  /** 投递记录列表（真筛选 + 真关键词 + **全序**排序）。 */
  @GetMapping("/deliveries")
  public List<NotifyDeliveryRow> deliveries(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID templateId,
      @RequestParam(required = false) String channel,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String scopeType,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false, defaultValue = "50") Integer limit,
      @RequestParam(required = false, defaultValue = "0") Integer offset) {
    return call(
        auth,
        "read",
        actor ->
            deliveries.deliveryRows(
                templateId,
                channel,
                status,
                scopeType,
                keyword,
                parseTime(from, "起始时间"),
                parseTime(to, "截止时间"),
                limit == null ? 50 : limit,
                offset == null ? 0 : offset));
  }

  /** 🔴 投递统计（触达率 / 退订率 / 拦截数）。 */
  @GetMapping("/deliveries/statistics")
  public NotifyDeliveryStatistics statistics(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> deliveries.statistics());
  }

  /** 一条投递记录（含逐次尝试 + 拦截留痕）。 */
  @GetMapping("/deliveries/{id}")
  public NotifyDeliveryDetail delivery(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> deliveries.deliveryDetail(id));
  }

  /**
   * 🔴 登记一次投递（唯一写入口）。
   *
   * <p>逐个收件人**先查退订**：命中退订的收件人不进投递尝试，改写一行拦截留痕并计入统计。
   */
  @PostMapping("/deliveries")
  public ResponseEntity<NotifyDeliveryDetail> record(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody NotifyDeliveryInput input) {
    return call(
        auth,
        "create",
        actor -> ResponseEntity.status(HttpStatus.CREATED).body(deliveries.recordDelivery(input)));
  }

  /**
   * 🔴 发起一次重试。
   *
   * <p>没点名收件人时，重试全部「失败 / 待出结果」的收件人。
   * <b>重试会重查退订</b>：第一次投递之后才退订的，重试必须被拦下。
   */
  @PostMapping("/deliveries/{id}/retry")
  public NotifyDeliveryDetail retry(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody NotifyRetryInput input) {
    return call(auth, "retry", actor -> deliveries.retry(id, input));
  }

  // ================================================================ 退订

  /** 退订列表（真筛选）。 */
  @GetMapping("/unsubscribes")
  public List<NotifyUnsubscribeRow> unsubscribes(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String recipientRef,
      @RequestParam(required = false) String channel,
      @RequestParam(required = false) String scopeType,
      @RequestParam(required = false) Boolean active,
      @RequestParam(required = false, defaultValue = "50") Integer limit,
      @RequestParam(required = false, defaultValue = "0") Integer offset) {
    return call(
        auth,
        "read",
        actor ->
            deliveries.unsubscribeRows(
                recipientRef,
                channel,
                scopeType,
                active,
                limit == null ? 50 : limit,
                offset == null ? 0 : offset));
  }

  /** 🔴 退订拦截留痕列表（「为什么这个收件人没收到」）。 */
  @GetMapping("/unsubscribes/blocks")
  public List<NotifyBlockRow> blocks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String recipientRef,
      @RequestParam(required = false) UUID deliveryId,
      @RequestParam(required = false, defaultValue = "50") Integer limit,
      @RequestParam(required = false, defaultValue = "0") Integer offset) {
    return call(
        auth,
        "read",
        actor ->
            deliveries.blockRows(
                recipientRef, deliveryId, limit == null ? 50 : limit, offset == null ? 0 : offset));
  }

  /**
   * 🔴 登记一条退订（**先查后登记**）。
   *
   * <p>已生效的同一条退订 ⇒ 直接返回原记录，**不产生第二行**。
   */
  @PostMapping("/unsubscribes")
  public ResponseEntity<NotifyUnsubscribeRow> unsubscribe(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody NotifyUnsubscribeInput input) {
    return call(
        auth,
        "unsubscribe",
        actor -> ResponseEntity.status(HttpStatus.CREATED).body(deliveries.registerUnsubscribe(input)));
  }

  /** 撤销退订（软撤销：历史区间留着，只是不再命中拦截）。 */
  @PostMapping("/unsubscribes/{id}/revoke")
  public NotifyUnsubscribeRow revoke(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "unsubscribe", actor -> deliveries.revokeUnsubscribe(id));
  }

  // ================================================================ 工具

  /** ISO-8601 带时区的时间（如 {@code 2026-10-30T00:00:00Z}）。 */
  private static OffsetDateTime parseTime(String raw, String label) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return OffsetDateTime.parse(raw.trim());
    } catch (RuntimeException e) {
      throw new org.springframework.web.server.ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          label + "必须是 ISO-8601 带时区的时间（如 2026-10-30T00:00:00Z）");
    }
  }
}
