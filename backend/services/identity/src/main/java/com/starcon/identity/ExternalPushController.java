package com.starcon.identity;

import static com.starcon.identity.ExternalPushModels.ExternalChannel;
import static com.starcon.identity.ExternalPushModels.ExternalChannelInput;
import static com.starcon.identity.ExternalPushModels.ExternalChannelList;
import static com.starcon.identity.ExternalPushModels.ExternalDelivery;
import static com.starcon.identity.ExternalPushModels.ExternalDeliveryList;
import static com.starcon.identity.ExternalPushModels.ExternalPushCapabilities;
import static com.starcon.identity.ExternalPushModels.ExternalPushCandidates;
import static com.starcon.identity.ExternalPushModels.ExternalPushRequest;
import static com.starcon.identity.ExternalPushModels.ExternalPushResult;
import static com.starcon.identity.ExternalPushModels.ExternalRetrySweep;

import com.starcon.tenancy.TenantTransactions;
import java.util.UUID;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 1.3.2.2 外部渠道推送 —— 端点（identity 服务）。
 *
 * <p><b>为什么在 identity</b>：卷 01 §1.3「消息与预警」整域都在 identity ——
 * 1.3.1.1 通知列表、1.3.1.2 通知偏好、1.3.2.1 预警规则都在这里。
 * 外部渠道推送是同一域的第四块：它记的是「本租户把通知投到哪个外部通道、投了几次、
 * 成了没有」—— warehouse / trade / channel / finance 都不拥有这个概念。
 *
 * <p><b>租户与成员都来自已认证会话</b>：{@code X-Tenant-Id} 头在这里从头到尾
 * 没有出现过，伪造它改不动 {@code app.current_tenant}。
 *
 * <p><b>请求体不接受 tenantId / userId / membershipId</b>：
 * 请求里带 membershipId 就是把「这条投递属于哪个租户」这条边界交给客户端了。
 * （identity 的 Jackson 配了 {@code fail-on-unknown-properties: true}，
 * 所以这些字段会被**直接 400 拒掉**，而不是被静默忽略。）
 *
 * <p><b>权限</b>：
 *
 * <ul>
 *   <li>{@code identity.external_push.read} —— 看渠道清单、投递记录、候选清单、能力声明；</li>
 *   <li>{@code identity.external_push.write} —— 登记 / 改 / 删渠道、发起推送、重试、确认告警。
 *       写动作还要求授权范围是 {@code ALL}：渠道与投递记录是**租户级共享**的配置，
 *       一个「只对本部门生效」的授权不该能改全租户的外发通道。</li>
 * </ul>
 *
 * <p><b>本端点不假装消息送出去了</b>：外部 IM / 邮件通道在本仓**未接通**
 * （见 {@link UnavailableExternalChannelDeliveryPort}），所以每一次投递尝试都
 * **真的失败**，并落成一条带失败码、失败原因、重试排期与告警的投递记录。
 * 能力声明里如实报「未接通 + 归 18 系统负责」。
 */
@RestController
@RequestMapping("/identity/external-channel-push")
public class ExternalPushController {

  /** 读侧权限码。 */
  static final String READ = "identity.external_push.read";

  /** 写侧权限码。 */
  static final String WRITE = "identity.external_push.write";

  private final IdentityController identity;
  private final ExternalPushService push;
  private final TenantTransactions tenants;

  public ExternalPushController(
      IdentityController identity,
      ExternalPushService push,
      DSLContext sql,
      PlatformTransactionManager manager) {
    this.identity = identity;
    this.push = push;
    this.tenants = new TenantTransactions(sql, new TransactionTemplate(manager));
  }

  /**
   * 统一入口：先确认会话与权限（租户、成员都来自这里），再在租户事务里执行。
   *
   * @param write 是否要求写权限（写还要求授权范围是 ALL）
   */
  private <T> T call(String auth, boolean write, Function<IdentityController.Access, T> work) {
    var actor = identity.resolve(auth);
    return tenants.run(
        actor.tenantId(),
        () -> {
          boolean allowed =
              actor.grants().stream()
                  .anyMatch(
                      grant ->
                          grant.permissions().contains(write ? WRITE : READ)
                              && grant.permissions().contains(READ)
                              && (!write || "ALL".equals(grant.scopeMode())));
          if (!allowed) {
            throw IdentityController.error(
                HttpStatus.FORBIDDEN,
                write
                    ? "当前角色没有外部渠道推送修改权限（渠道与投递记录是租户级共享配置，需要租户范围的授权）"
                    : "当前角色没有外部渠道推送查看权限");
          }
          return work.apply(actor);
        });
  }

  /**
   * 能力声明：渠道词表 + secret manager / 投递通道 / 模板管理三个「通没接通」+ 逐条边界。
   *
   * <p>前端按这里下发的词表渲染，不自己写一份清单 ——
   * 两份词表迟早会漂移，而漂移的表现是「建了一个渠道，引擎说渠道不认识」。
   */
  @GetMapping("/capabilities")
  public ExternalPushCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, false, actor -> push.capabilities());
  }

  /**
   * 外部渠道清单。
   *
   * <p>🔴 返回的是 **secret manager 引用串**，不是凭据 —— 凭据不落库，
   * 所以这里**没有**任何一列能装下它。
   */
  @GetMapping("/channels")
  public ExternalChannelList channels(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, false, actor -> push.listChannels(actor.tenantId()));
  }

  /**
   * 登记一个外部渠道。
   *
   * <p>🔴 {@code secretRef} 必须是 secret manager 的**引用串**；
   * {@code https://…} 这类地址本体会被 400 拒掉（凭据不落库）。
   */
  @PostMapping("/channels")
  public ExternalChannel createChannel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody ExternalChannelInput input) {
    return call(
        auth, true, actor -> push.createChannel(actor.tenantId(), actor.membershipId(), input));
  }

  /** 改一个外部渠道（**逐字段覆盖**：给了的覆盖，没给的保持）。 */
  @PutMapping("/channels/{id}")
  public ExternalChannel updateChannel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody ExternalChannelInput input) {
    return call(
        auth, true, actor -> push.updateChannel(actor.tenantId(), actor.membershipId(), id, input));
  }

  /**
   * 删一个外部渠道（**投递历史留着**：历史里有渠道码与渠道名的快照）。
   */
  @DeleteMapping("/channels/{id}")
  public void deleteChannel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    call(
        auth,
        true,
        actor -> {
          push.deleteChannel(actor.tenantId(), id);
          return null;
        });
  }

  /**
   * 可推送的候选清单（**1.3.2.1 的真命中**）。
   *
   * <p>只列 {@code resolution='HIT'} 的真命中；UNDECIDABLE 是「算不出来」，不是命中。
   */
  @GetMapping("/candidates")
  public ExternalPushCandidates candidates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return call(auth, false, actor -> push.candidates(actor.tenantId(), limit, offset));
  }

  /**
   * 发起一次推送（**一个目标渠道一条投递记录**）。
   *
   * <p>🔴 结果**如实**：外部通道未接通 ⇒ 每条记录都是失败，并带失败码与重试排期。
   */
  @PostMapping("/deliveries")
  public ExternalPushResult deliver(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody ExternalPushRequest input) {
    return call(auth, true, actor -> push.push(actor.tenantId(), actor.membershipId(), input));
  }

  /** 投递记录清单（**真分页 + 真筛选**）。 */
  @GetMapping("/deliveries")
  public ExternalDeliveryList deliveries(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID channelId,
      @RequestParam(required = false) String sourceKind,
      @RequestParam(required = false) String notificationType,
      @RequestParam(required = false) UUID recipientMembershipId,
      @RequestParam(required = false) String alertState,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return call(
        auth,
        false,
        actor ->
            push.deliveries(
                actor.tenantId(),
                new ExternalPushService.DeliveryFilter(
                    status,
                    channelId,
                    sourceKind,
                    notificationType,
                    recipientMembershipId,
                    alertState),
                limit,
                offset));
  }

  /**
   * 🔴 **到期待重试的扫描**（蓝图「失败自动重试」的执行入口）。
   *
   * <p>本服务**不装 @Scheduled**（多副本下自带定时器要做主节点选举与分布式锁，
   * 那是平台能力不是领域能力）⇒ 由**外部调度**（K8s CronJob）按间隔调一次本端点。
   *
   * <p>失败重试与免打扰到期**共用这一个口径**（{@code next_attempt_at <= now()}）。
   */
  @PostMapping("/deliveries/retry-due")
  public ExternalRetrySweep retryDue(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(defaultValue = "50") int limit) {
    return call(auth, true, actor -> push.retryDue(actor.tenantId(), limit));
  }

  /** 手工重试一条投递记录（**人工再给一次机会**）。 */
  @PostMapping("/deliveries/{id}/retry")
  public ExternalDelivery retry(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, true, actor -> push.retry(actor.tenantId(), id));
  }

  /**
   * 确认一条告警（**只改告警状态，不改投递结果**）。
   *
   * <p>「我知道了」和「它投出去了」是两件事 —— 确认告警不许把失败改成成功。
   */
  @PostMapping("/deliveries/{id}/acknowledge-alert")
  public ExternalDelivery acknowledgeAlert(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, true, actor -> push.acknowledgeAlert(actor.tenantId(), id));
  }
}
