package com.starcon.platform;

import static com.starcon.platform.DeliveryRecordModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 18.1.2.2「投递记录」的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /platform/deliveries}</b>：与 18.1.2.1 的 {@code /platform/webhooks}
 * 刻意不共用前缀 —— 18.1.2.1 管**订阅配置侧**（可订阅事件列表、订阅端点、签名密钥、事件过滤、
 * 重试与死信策略），本模块管**投递执行与明细侧**（真的发出去、投递明细、投递日志、重试、死信、
 * 重放）。两件事、两页、两组权限码。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 租户由成员关系解析，伪造的 {@code X-Tenant-Id} 头改不动它。
 *
 * <p>🔴 <b>为什么这里用的是 {@code access.require(...)} 而不是同服务的 {@code access.run(...)}</b>：
 * 投递要**真的**把包发出去，而一次 HTTP 调用可能长达策略允许的 120 秒
 * （{@code request_timeout_seconds} 上限 120）。
 * {@code access.run(...)} 会把整个调用包在一个数据库事务里 ⇒ 一次慢投递会占住一条连接
 * 整整 120 秒。
 *
 * <p>⇒ 本模块**自己**控制事务边界：
 *
 * <ul>
 *   <li>阶段一（事务）：读订阅、解析策略、解密签名密钥、<b>把投递记录落库并提交</b>；
 *   <li>阶段二（<b>无事务</b>）：真的发 HTTP；
 *   <li>阶段三（事务）：把响应码写进投递日志、更新投递状态、必要时转死信。
 * </ul>
 *
 * <p>这个顺序就是「至少一次」的实现：<b>记录先落库，包后发</b>。
 * 发包前崩了 ⇒ 留下一条 PENDING 的记录，重试扫描会把它捡起来；
 * 反过来（先发包后落库）崩在中途 ⇒ 对方收到了、我们没有记录，「投没投出去」答不出来。
 *
 * <p>权限码 {@code platform.webhook.delivery.read / .publish / .replay}：
 * 「看投递记录」「发布事件」「让人工重投」是三件事 —— 能看的人不该顺手把一条死信重放出去。
 */
@RestController
@RequestMapping("/platform")
public class DeliveryRecordController {

  private final AccessService access;
  private final DeliveryRecordService deliveries;

  public DeliveryRecordController(AccessService access, DeliveryRecordService deliveries) {
    this.access = access;
    this.deliveries = deliveries;
  }

  /**
   * 统一入口：先确认身份与权限（租户**不**来自客户端头），再把工作交给服务层。
   *
   * <p>🔴 刻意**不**用 {@code access.run(...)}：见类注释「为什么这里用的是 require 而不是 run」——
   * 投递的真实 HTTP 调用不能被包在一个数据库事务里。
   */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "platform.webhook.delivery." + permission);
    return work.apply(actor);
  }

  // ================================================================ 能力声明与概览

  /** 能力声明：哪些真接通、职责怎么切、哪些还没交付、归谁。 */
  @GetMapping("/deliveries/capabilities")
  public DeliveryRecordCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> deliveries.capabilities());
  }

  /** 概览：几个数（真查库）。 */
  @GetMapping("/deliveries/overview")
  public DeliveryRecordOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> deliveries.overview(actor.tenantId()));
  }

  // ================================================================ 投递记录

  /** 投递记录列表（真分页 + 真筛选）。 */
  @GetMapping("/deliveries")
  public DeliveryRecordPage deliveries(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID subscriptionId,
      @RequestParam(required = false) String eventType,
      @RequestParam(required = false) UUID eventId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "read",
        actor ->
            deliveries.deliveries(
                actor.tenantId(), keyword, status, subscriptionId, eventType, eventId, page, size));
  }

  /** 投递详情：记录 + 投递日志 + 死信 + 重放任务。 */
  @GetMapping("/deliveries/{deliveryId}")
  public DeliveryRecordDetail deliveryDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID deliveryId) {
    return call(auth, "read", actor -> deliveries.deliveryDetail(actor.tenantId(), deliveryId));
  }

  /** 投递日志（每一次尝试一行：响应码、耗时、错误）。 */
  @GetMapping("/deliveries/{deliveryId}/attempts")
  public List<DeliveryRecordAttemptRow> attempts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID deliveryId) {
    return call(auth, "read", actor -> deliveries.attempts(actor.tenantId(), deliveryId));
  }

  // ================================================================ 发布（真实投递）

  /**
   * 发布一个事件 —— **真的**投给每一个匹配的订阅，并把明细落库。
   *
   * <p>🔴 同一个 {@code eventId} 再次发布是**幂等**的：不会重复发包。
   */
  @PostMapping("/deliveries/publish")
  public DeliveryRecordPublishResult publish(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody DeliveryRecordPublishInput input) {
    return call(auth, "publish", actor -> deliveries.publish(actor.tenantId(), input, actor));
  }

  // ================================================================ 重投与重放

  /** 手动重投：重发**同一份报文字节**，事件 ID 不变（接收方按 event_id 幂等去重即可）。 */
  @PostMapping("/deliveries/{deliveryId}/replay")
  public DeliveryRecordReplayResult replay(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID deliveryId,
      @Valid @RequestBody DeliveryRecordReplayInput input) {
    return call(auth, "replay", actor -> deliveries.replay(actor.tenantId(), deliveryId, input, actor));
  }

  /** 死信列表（真分页 + 真筛选）。 */
  @GetMapping("/deliveries/dead-letters")
  public DeliveryRecordDeadLetterPage deadLetters(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "read", actor -> deliveries.deadLetters(actor.tenantId(), status, page, size));
  }

  /** 死信重放：把一条死信重新投一次。 */
  @PostMapping("/deliveries/dead-letters/{deadLetterId}/replay")
  public DeliveryRecordReplayResult replayDeadLetter(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID deadLetterId,
      @Valid @RequestBody DeliveryRecordReplayInput input) {
    return call(
        auth, "replay", actor -> deliveries.replayDeadLetter(actor.tenantId(), deadLetterId, input, actor));
  }

  /** 重放任务列表（真分页）。 */
  @GetMapping("/deliveries/replay-tasks")
  public DeliveryRecordReplayTaskPage replayTasks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "read", actor -> deliveries.replayTasks(actor.tenantId(), page, size));
  }

  // ================================================================ 到期重试扫描（外部调度触发）

  /**
   * 🔴 **到期待重试的扫描**（自动重试的执行入口）。
   *
   * <p>本服务**不装 {@code @Scheduled}**（多副本下自带定时器要做主节点选举与分布式锁，
   * 那是平台能力不是领域能力；与 channel V212 / V317 / identity 外部推送同一纪律）
   * ⇒ 由**外部调度**（K8s CronJob）按间隔调一次本端点。
   *
   * <p>⚠️ 逐租户：租户来自已认证会话（不是客户端头）⇒ RLS 照常生效。
   */
  @PostMapping("/deliveries/retry-due")
  public DeliveryRecordRetrySweep retryDue(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(defaultValue = "50") int limit) {
    return call(auth, "replay", actor -> deliveries.retryDue(actor.tenantId(), limit));
  }
}
