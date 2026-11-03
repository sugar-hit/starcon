package com.starcon.platform;

import static com.starcon.platform.ExternalIntegrationModels.*;

import jakarta.validation.Valid;
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
 * 18.2.3.1「其他对接」的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /platform/external-integrations}</b>：与同服务的
 * {@code /platform/apps}（谁可以接入我们）、{@code /platform/text}（文案怎么翻）、
 * {@code /platform/finance-software}（凭证怎么交给外部财务软件）、
 * {@code /platform/payment-funds}（钱怎么通过支付服务商出去）同服务、不同前缀，
 * 四者管的是完全不同的东西。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p><b>权限码 {@code platform.external.read / .create / .update / .sync}</b>：
 * 「配置」与「同步」是**两个独立的码** —— 能登记连接配置的人不该顺手就能对外部系统发请求。
 *
 * <p>🔴 <b>「外部通道未接入」一律 409</b>，而**不是** 200：
 * 一个 200 会被前端当成「同步成功了」。响应体里带着那条**如实标注**未接入的同步记录
 * （status = NOT_CONNECTED、provider_notified = false、写明缺什么归谁）。
 */
@RestController
@RequestMapping("/platform/external-integrations")
public class ExternalIntegrationController {

  private final AccessService access;
  private final ExternalIntegrationService integrations;

  public ExternalIntegrationController(
      AccessService access, ExternalIntegrationService integrations) {
    this.access = access;
    this.integrations = integrations;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "platform.external." + permission);
    return access.run(actor, "platform.external." + permission, () -> work.apply(actor));
  }

  /**
   * 「外部通道未接入」的统一出口：<b>409</b> + 如实标注的同步记录。
   *
   * <p>🔴 刻意<b>不抛异常</b>：抛了会让事务回滚，那条「谁在什么时候对哪个对接发起了一次同步、
   * 外部侧结论是什么」的留痕就丢了 —— 而留痕正是这个模块**自己那一层**的价值。
   */
  private static ResponseEntity<ExternalSyncRecordRow> settled(ExternalSyncRecordRow row) {
    if ("NOT_CONNECTED".equals(row.status())) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(row);
    }
    return ResponseEntity.ok(row);
  }

  // ================================================================ 能力声明与概览

  /** 能力声明：哪些真接通、哪些还没交付、归谁。 */
  @GetMapping("/capabilities")
  public ExternalIntegrationCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> integrations.capabilities());
  }

  /** 概览：几个数。 */
  @GetMapping("/overview")
  public ExternalIntegrationOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> integrations.overview());
  }

  /** 逐个外部系统的通道可用性（**逐个如实答**）。 */
  @GetMapping("/adapters/availability")
  public List<ExternalAdapterAvailability> availability(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> integrations.availability());
  }

  // ================================================================ 连接配置

  /** 对接连接配置列表（真筛选 + 真关键词）。 */
  @GetMapping("/connections")
  public List<ExternalConnectionRow> connections(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String adapterCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String healthStatus,
      @RequestParam(required = false) String keyword) {
    return call(
        auth,
        "read",
        actor ->
            integrations.connectionRows(category, adapterCode, status, healthStatus, keyword));
  }

  /** 一条对接连接配置。 */
  @GetMapping("/connections/{id}")
  public ExternalConnectionRow connection(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> integrations.connectionRow(id));
  }

  /** 新增或修改一个对接连接配置（**密钥只存引用**）。 */
  @PostMapping("/connections")
  public ExternalConnectionRow saveConnection(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ExternalConnectionInput input) {
    return call(auth, "create", actor -> integrations.saveConnection(input, actor));
  }

  /** 停用一个对接连接（**不删**）。 */
  @PostMapping("/connections/{id}/disable")
  public ExternalConnectionRow disableConnection(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "update", actor -> integrations.disableConnection(id, actor));
  }

  // ================================================================ 健康状态

  /**
   * 🔴 **一个对接自己**的健康状态。
   *
   * <p>响应里出现的每一个数都来自**同一个 connection_id** ——
   * 别的对接挂了几个**不在这里**。
   */
  @GetMapping("/connections/{id}/health")
  public ExternalConnectionHealth health(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> integrations.healthOf(id));
  }

  /** 🔴 **重算某一个连接**的健康状态（只改这一行）。 */
  @PostMapping("/connections/{id}/health/refresh")
  public ExternalConnectionHealth refreshHealth(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "update", actor -> integrations.refreshHealth(id, actor));
  }

  // ================================================================ 同步任务

  /** 同步任务列表（真筛选 + 真关键词）。 */
  @GetMapping("/sync-tasks")
  public List<ExternalSyncTaskRow> syncTasks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID connectionId,
      @RequestParam(required = false) String taskType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword) {
    return call(
        auth, "read", actor -> integrations.taskRows(connectionId, taskType, status, keyword));
  }

  /** 一条同步任务。 */
  @GetMapping("/sync-tasks/{id}")
  public ExternalSyncTaskRow syncTask(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> integrations.taskRow(id));
  }

  /** 新增或修改一个同步任务配置。 */
  @PostMapping("/sync-tasks")
  public ExternalSyncTaskRow saveSyncTask(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ExternalSyncTaskInput input) {
    return call(auth, "create", actor -> integrations.saveTask(input, actor));
  }

  /** 停用一个同步任务（**不删**）。 */
  @PostMapping("/sync-tasks/{id}/disable")
  public ExternalSyncTaskRow disableSyncTask(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "update", actor -> integrations.disableTask(id, actor));
  }

  // ================================================================ 同步记录

  /** 同步记录列表（真筛选 + 真关键词）。 */
  @GetMapping("/sync-records")
  public List<ExternalSyncRecordRow> syncRecords(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID connectionId,
      @RequestParam(required = false) UUID taskId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String source,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Integer limit) {
    return call(
        auth,
        "read",
        actor ->
            integrations.recordRows(connectionId, taskId, status, source, keyword, limit));
  }

  /** 一条同步记录。 */
  @GetMapping("/sync-records/{id}")
  public ExternalSyncRecordRow syncRecord(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> integrations.recordRow(id));
  }

  /**
   * 🔴 **经由外部通道执行一次同步**。
   *
   * <p>外部通道未接入 ⇒ <b>409</b> + 如实标注的同步记录（<b>不写成功记录</b>）。
   */
  @PostMapping("/sync-tasks/{id}/run")
  public ResponseEntity<ExternalSyncRecordRow> runSync(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "sync", actor -> settled(integrations.runSync(id, actor, auth)));
  }

  /**
   * 🔴 **登记一条「外部侧已完成」的同步**（人工登记；**没有**经过本系统的外部通道）。
   *
   * <p>记进去的行 {@code source=EXTERNAL} 且 {@code provider_notified=false} ——
   * 它记的是「本系统没有与外部系统核对过」，不能被读成「已登记」。
   */
  @PostMapping("/sync-records")
  public ExternalSyncRecordRow recordExternal(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ExternalSyncRecordInput input) {
    return call(auth, "create", actor -> integrations.recordExternal(input, actor));
  }

  // ================================================================ 上游（只读）

  /** 上游 2.1 平台目录（**只读**；读不到 ⇒ 503）。 */
  @GetMapping("/upstream/platforms")
  public List<UpstreamChannelPlatformRow> upstreamPlatforms(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> integrations.upstreamPlatforms(auth));
  }

  /** 上游 2.3 店铺（**只读**；读不到 ⇒ 503）。 */
  @GetMapping("/upstream/shops")
  public List<UpstreamChannelShopRow> upstreamShops(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> integrations.upstreamShops(auth));
  }
}
