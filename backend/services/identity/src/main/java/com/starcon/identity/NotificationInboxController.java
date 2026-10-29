package com.starcon.identity;

import static com.starcon.identity.NotificationInboxModels.NotificationInboxCapabilities;
import static com.starcon.identity.NotificationInboxModels.NotificationReadRequest;
import static com.starcon.identity.NotificationInboxModels.NotificationReadResult;
import static com.starcon.identity.NotificationInboxModels.NotificationReadState;
import static com.starcon.identity.NotificationInboxModels.NotificationSubscriptionRequest;
import static com.starcon.identity.NotificationInboxModels.NotificationSubscriptions;

import com.starcon.tenancy.TenantTransactions;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 1.3.1.1 通知列表 —— 端点（identity 服务）。
 *
 * <p><b>为什么在 identity</b>：本模块落库的是**个人维度**的状态（已读 / 未读、按类型订阅），
 * 与 1.4 个人设置、1.4.1.2 的 {@code user_preference} 同域；catalog / trade / channel /
 * warehouse / crm / finance 都不拥有「个人已读状态」这个概念。
 *
 * <p><b>通知内容不在这里</b>：内容是各域既有事件的只读投影，前端按各域的**授权只读端点**
 * 聚合成一条通知流。本端点只负责「我读过哪些」「我订阅哪些类型」这两件个人事实。
 *
 * <p><b>租户与 membership_id 都来自已认证会话</b>：{@code X-Tenant-Id} 头在这里从头到尾
 * 没有出现过，伪造它改不动 {@code app.current_tenant}，也拿不到别人的 membership_id；
 * 请求体里也**不接受** membershipId / userId / tenantId。
 *
 * <p><b>为什么没有独立权限码</b>：本端点只能碰到「我自己」的状态 ——
 * 边界是**数据归属**（tenant_id + membership_id + RLS），不是功能开关。
 * 与 {@code user_preference} / {@code page_favorite} 同一套论证：另立一个
 * {@code identity.notification.*} 权限码只会让管理员新建角色时忘了勾，
 * 于是普通成员的已读状态静默失效（界面上看不出任何异常）。
 */
@RestController
@RequestMapping("/identity/notification-inbox")
public class NotificationInboxController {

  private final IdentityController identity;
  private final NotificationInboxService inbox;
  private final TenantTransactions tenants;

  public NotificationInboxController(
      IdentityController identity,
      NotificationInboxService inbox,
      DSLContext sql,
      PlatformTransactionManager manager) {
    this.identity = identity;
    this.inbox = inbox;
    this.tenants = new TenantTransactions(sql, new TransactionTemplate(manager));
  }

  /**
   * 统一入口：先确认会话（租户与 membership_id 都从这里来），再在租户事务里执行。
   *
   * <p>刻意**不**接收任何身份参数：请求里带 membershipId 就是把「只能改自己的」
   * 这条边界交给客户端了。
   */
  private <T> T call(String auth, Function<IdentityController.Access, T> work) {
    IdentityController.Access actor = identity.resolve(auth);
    UUID membershipId = actor.membershipId();
    if (membershipId == null) {
      throw IdentityController.error(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
    }
    return tenants.run(actor.tenantId(), () -> work.apply(actor));
  }

  /**
   * 能力声明：受控类型词表 + 批量上限 + **未交付**的相邻模块。
   *
   * <p>前端按这里的词表渲染订阅项，不自己写一份类型清单 ——
   * 两份词表迟早会漂移，而漂移的表现是「订阅了某类型但收件箱里看不到」。
   */
  @GetMapping("/capabilities")
  public NotificationInboxCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        actor ->
            new NotificationInboxCapabilities(
                NotificationInboxRules.TYPES,
                NotificationInboxRules.MAX_KEYS,
                List.of(
                    "1.3.1.2 通知偏好（接收渠道 / 免打扰时段）已交付（过滤与免打扰口径归它，本接口只给站内收件箱读模型）",
                    "1.3.2.1 预警规则已交付（命中产出归它，本接口只消费已入箱的站内通知）",
                    "1.3.2.2 外部渠道推送已交付（外部渠道投递归它，本接口只管站内收件箱）")));
  }

  /**
   * 查一批通知的已读状态。
   *
   * <p><b>用 POST 是因为键集合可能几百条</b>：GET 的 URL 扛不住这个长度
   * （几十个键就会顶到代理的 URL 上限，几百个键根本放不下）。
   * 这是一次**读**操作，但走 POST —— 因为被读的是请求体，不在 URL 上。
   */
  @PostMapping("/read-state")
  public NotificationReadState readState(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody NotificationReadRequest input) {
    List<NotificationInboxRules.NotificationKey> keys =
        NotificationInboxRules.normalizeKeys(input == null ? null : input.keys());
    return call(
        auth,
        actor -> inbox.readState(actor.tenantId(), actor.membershipId(), keys));
  }

  /**
   * 批量标记已读（**只对点名的那几条生效**）。
   *
   * <p>刻意**没有**「把本租户全部通知标已读」这种端点：调用方负责把要改的
   * 集合限定在**当前筛选出的那批**里，再把键显式传进来。
   */
  @PostMapping("/read")
  public NotificationReadResult markRead(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody NotificationReadRequest input) {
    List<NotificationInboxRules.NotificationKey> keys =
        NotificationInboxRules.normalizeKeys(input == null ? null : input.keys());
    return call(
        auth,
        actor -> inbox.markRead(actor.tenantId(), actor.membershipId(), keys));
  }

  /** 批量取消已读（幂等；同样只对点名的那几条生效）。 */
  @PostMapping("/unread")
  public NotificationReadResult markUnread(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody NotificationReadRequest input) {
    List<NotificationInboxRules.NotificationKey> keys =
        NotificationInboxRules.normalizeKeys(input == null ? null : input.keys());
    return call(
        auth,
        actor -> inbox.markUnread(actor.tenantId(), actor.membershipId(), keys));
  }

  /** 我的订阅类型集合（**生效值**：没自定义过时是全部类型）。 */
  @GetMapping("/subscriptions")
  public NotificationSubscriptions subscriptions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        actor -> inbox.subscriptions(actor.tenantId(), actor.membershipId()));
  }

  /** 设置我的订阅类型（整体替换；词表外的类型码一律 400）。 */
  @PutMapping("/subscriptions")
  public NotificationSubscriptions setSubscriptions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody NotificationSubscriptionRequest input) {
    List<String> types = input == null ? null : input.types();
    return call(
        auth,
        actor -> inbox.setSubscriptions(actor.tenantId(), actor.membershipId(), types));
  }
}
