package com.starcon.platform;

import static com.starcon.platform.EventSubscriptionModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 18.1.2.1 事件订阅的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /platform/webhooks}</b>：与 18.1.1.1 的 {@code /platform/apps} 刻意不共用前缀；
 * 首 token 用 {@code webhooks} —— 与 channel 服务那一族「电商平台回传」的 {@code webhook-*}
 * 不是一回事（那边归 channel，本模块归 platform）。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码 {@code platform.webhook.read / .create / .update / .revoke}：
 * 吊销是<b>独立</b>的一个码 —— 能改订阅的人不该顺手把别人的订阅掐了。
 *
 * <p>🔴 {@code POST /platform/webhooks/subscriptions/{id}/deliver} 是**投递执行**的入口，
 * 但投递执行器<b>未接入</b>（归 18.1.2.2）⇒ 端点如实答 <b>409</b>，<b>一次包都不发</b>。
 */
@RestController
@RequestMapping("/platform")
public class EventSubscriptionController {

  private final AccessService access;
  private final EventSubscriptionService webhooks;

  public EventSubscriptionController(AccessService access, EventSubscriptionService webhooks) {
    this.access = access;
    this.webhooks = webhooks;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "platform.webhook." + permission);
    return access.run(actor, "platform.webhook." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明与概览

  /** 能力声明：哪些真接通、职责怎么切、哪些还没交付、归谁。 */
  @GetMapping("/webhooks/capabilities")
  public EventSubscriptionCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> webhooks.capabilities());
  }

  /** 概览：几个数。 */
  @GetMapping("/webhooks/overview")
  public EventSubscriptionOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> webhooks.overview());
  }

  // ================================================================ 事件定义（可订阅事件列表）

  /** 可订阅事件列表（真分页 + 真筛选；数据来自注册表，不是写死的清单）。 */
  @GetMapping("/webhooks/event-types")
  public EventSubscriptionEventTypePage eventTypes(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "read", actor -> webhooks.eventTypes(keyword, status, page, size));
  }

  /** 登记一个事件定义（= 一个事件类型的一个 schema 版本）。 */
  @PostMapping("/webhooks/event-types")
  public EventSubscriptionEventTypeRow registerEventType(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody EventSubscriptionEventTypeInput input) {
    return call(auth, "create", actor -> webhooks.registerEventType(input, actor));
  }

  /**
   * 改事件定义的<b>状态</b>（ACTIVE ↔ DEPRECATED）。
   *
   * <p>🔴 **只能改状态**：结构（event_type / schema_version / payload_schema）不可原地修改 ——
   * 数据库触发器会直接拒掉。要改结构请登记一个更高的 schema 版本。
   */
  @PostMapping("/webhooks/event-types/{eventTypeId}/status")
  public EventSubscriptionEventTypeRow changeEventTypeStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID eventTypeId,
      @Valid @RequestBody EventSubscriptionEventTypeStatusInput input) {
    return call(auth, "update", actor -> webhooks.changeEventTypeStatus(eventTypeId, input, actor));
  }

  // ================================================================ 订阅

  /** 订阅列表（真分页 + 真筛选）。 */
  @GetMapping("/webhooks/subscriptions")
  public EventSubscriptionPage subscriptions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID appId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "read", actor -> webhooks.subscriptions(keyword, status, appId, page, size));
  }

  /** 创建订阅。 */
  @PostMapping("/webhooks/subscriptions")
  public EventSubscriptionRow createSubscription(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody EventSubscriptionInput input) {
    return call(auth, "create", actor -> webhooks.createSubscription(input, actor));
  }

  /** 订阅详情（订阅 + 事件过滤 + 签名密钥元数据 + 生效策略）。 */
  @GetMapping("/webhooks/subscriptions/{subscriptionId}")
  public EventSubscriptionDetail subscriptionDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID subscriptionId) {
    return call(auth, "read", actor -> webhooks.subscriptionDetail(subscriptionId));
  }

  /**
   * 改订阅（端点 / 名称 / 状态 / 说明）。
   *
   * <p>⚠️ **刻意用 POST 而不是 PUT**：platform 的 catch-all 路由（{@code /api/platform/[...path]}）
   * 只导出 GET / POST / DELETE —— 与同服务的 18.1.1.1「应用与凭证」、18.2.1.1「财务软件」
   * 保持同一口径（后者改档案也是 POST）。改成 PUT 就得去动那条**多车道共用**的路由文件。
   */
  @PostMapping("/webhooks/subscriptions/{subscriptionId}")
  public EventSubscriptionRow updateSubscription(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID subscriptionId,
      @Valid @RequestBody EventSubscriptionInput input) {
    return call(auth, "update", actor -> webhooks.updateSubscription(subscriptionId, input, actor));
  }

  /** 吊销订阅（**不删**：订阅与其审计痕迹都留着）。 */
  @PostMapping("/webhooks/subscriptions/{subscriptionId}/revoke")
  public EventSubscriptionRow revokeSubscription(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID subscriptionId,
      @RequestParam(required = false) String reason) {
    return call(auth, "revoke", actor -> webhooks.revokeSubscription(subscriptionId, reason, actor));
  }

  // ================================================================ 事件过滤

  /** 事件过滤列表。 */
  @GetMapping("/webhooks/subscriptions/{subscriptionId}/events")
  public List<EventSubscriptionFilterRow> filters(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID subscriptionId) {
    return call(auth, "read", actor -> webhooks.filters(subscriptionId));
  }

  /** 加一条事件过滤（只能订阅**已登记**的 schema）。 */
  @PostMapping("/webhooks/subscriptions/{subscriptionId}/events")
  public EventSubscriptionFilterRow addFilter(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID subscriptionId,
      @Valid @RequestBody EventSubscriptionFilterInput input) {
    return call(auth, "update", actor -> webhooks.addFilter(subscriptionId, input, actor));
  }

  /** 删一条事件过滤。 */
  @DeleteMapping("/webhooks/subscriptions/{subscriptionId}/events/{filterId}")
  public void removeFilter(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID subscriptionId,
      @PathVariable UUID filterId) {
    call(auth, "update", actor -> {
      webhooks.removeFilter(subscriptionId, filterId, actor);
      return null;
    });
  }

  // ================================================================ 签名密钥

  /** 签名密钥列表（**只有元数据，没有明文**）。 */
  @GetMapping("/webhooks/subscriptions/{subscriptionId}/signing-secrets")
  public List<EventSubscriptionSecretRow> signingSecrets(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID subscriptionId) {
    return call(auth, "read", actor -> webhooks.signingSecrets(subscriptionId));
  }

  /**
   * 生成签名密钥 —— **明文只在这一次的响应里出现**。
   *
   * <p>之后任何读路径都取不回明文（库里只有 AES-256-GCM 密文）。
   */
  @PostMapping("/webhooks/subscriptions/{subscriptionId}/signing-secrets")
  public EventSubscriptionSecretIssued issueSecret(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID subscriptionId) {
    return call(auth, "update", actor -> webhooks.issueSecret(subscriptionId, actor));
  }

  /** 轮换签名密钥：生成新明文 + 旧密钥立即失效。 */
  @PostMapping("/webhooks/subscriptions/{subscriptionId}/signing-secrets/rotate")
  public EventSubscriptionSecretIssued rotateSecret(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID subscriptionId) {
    return call(auth, "update", actor -> webhooks.rotateSecret(subscriptionId, actor));
  }

  /** 吊销生效中的签名密钥（泄露时的一键止血）。 */
  @PostMapping("/webhooks/subscriptions/{subscriptionId}/signing-secrets/revoke")
  public EventSubscriptionSecretRow revokeSecret(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID subscriptionId) {
    return call(auth, "revoke", actor -> webhooks.revokeSecret(subscriptionId, actor));
  }

  // ================================================================ 签名试算

  /**
   * 签名试算：给定样例载荷，返回平台**会发**的签名头。
   *
   * <p>🔴 **不投递、不写任何投递记录** —— 只把「签名怎么算」变成对接方能当场核对的事实。
   */
  @PostMapping("/webhooks/subscriptions/{subscriptionId}/signature-preview")
  public EventSubscriptionSignaturePreview signaturePreview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID subscriptionId,
      @Valid @RequestBody EventSubscriptionSignaturePreviewInput input) {
    return call(auth, "update", actor -> webhooks.signaturePreview(subscriptionId, input, actor));
  }

  // ================================================================ 策略

  /** 策略列表（租户级默认 + 各订阅的专属策略）。 */
  @GetMapping("/webhooks/policies")
  public List<EventSubscriptionPolicyRow> policies(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> webhooks.policies());
  }

  /** 新建一份策略（subscriptionId 为空 = 租户级默认策略）。 */
  @PostMapping("/webhooks/policies")
  public EventSubscriptionPolicyRow createPolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody EventSubscriptionPolicyInput input) {
    return call(auth, "create", actor -> webhooks.createPolicy(input, actor));
  }

  /** 改策略（同上：刻意用 POST，与 platform 的 catch-all 路由导出口径一致）。 */
  @PostMapping("/webhooks/policies/{policyId}")
  public EventSubscriptionPolicyRow updatePolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID policyId,
      @Valid @RequestBody EventSubscriptionPolicyInput input) {
    return call(auth, "update", actor -> webhooks.updatePolicy(policyId, input, actor));
  }

  // ================================================================ 审计

  /** 审计事件列表（真分页，新到旧；可按订阅过滤）。 */
  @GetMapping("/webhooks/audit-events")
  public EventSubscriptionAuditEventPage auditEvents(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID subscriptionId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "read", actor -> webhooks.auditEvents(subscriptionId, page, size));
  }

  // ================================================================ 投递（当前未接入）

  /**
   * 投递一次事件 —— 🔴 **适配器未接入时答 409，一次包都不发**。
   *
   * <p>投递执行与投递明细归 18.1.2.2；本端点只把「缺什么、归谁」变成一条可查的事实。
   */
  @PostMapping("/webhooks/subscriptions/{subscriptionId}/deliver")
  public EventSubscriptionDeliveryPort.EventSubscriptionDeliveryOutcome deliver(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID subscriptionId,
      @Valid @RequestBody EventSubscriptionDeliveryInput input) {
    return call(auth, "update", actor -> webhooks.deliver(subscriptionId, input, actor));
  }
}
