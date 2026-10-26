package com.starcon.identity;

import static com.starcon.identity.NotificationPreferenceModels.NotificationPreferenceCapabilities;
import static com.starcon.identity.NotificationPreferenceModels.NotificationPreferenceInput;
import static com.starcon.identity.NotificationPreferenceModels.NotificationPreferenceResolution;
import static com.starcon.identity.NotificationPreferenceModels.NotificationPreferenceResolveRequest;
import static com.starcon.identity.NotificationPreferenceModels.NotificationPreferenceView;

import com.starcon.tenancy.TenantTransactions;
import java.util.UUID;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 1.3.1.2 通知偏好 —— 端点（identity 服务）。
 *
 * <p><b>为什么在 identity</b>：本模块落库的是**个人维度**的偏好（每类通知走哪些渠道、
 * 什么时段别打扰我），与 1.4 个人设置、1.4.1.2 的 {@code user_preference} 同域；
 * catalog / trade / channel / warehouse / finance 都不拥有「个人通知偏好」这个概念。
 *
 * <p>🔴 <b>存储复用 {@code user_preference}</b>（页面 key = {@code notifications.delivery}），
 * 与蓝图「共享数据：{@code user_preference}」逐字一致；本模块<b>不建表</b>。
 *
 * <p>🔴 <b>租户与 membership_id 都来自已认证会话</b>：{@code X-Tenant-Id} 头在这里从头到尾
 * 没有出现过，伪造它改不动 {@code app.current_tenant}，也拿不到别人的 membership_id；
 * 请求体里也**不接受** membershipId / userId / tenantId。
 *
 * <p>🔴 <b>关键业务通知不允许被完全关闭</b>：这条落在<b>服务端</b>，
 * 不靠前端把复选框置灰 —— 校验在 {@link NotificationPreferenceRules}，
 * 兜底在 {@link NotificationPreferenceService}（读的时候底线渠道仍然在）。
 *
 * <p><b>为什么没有独立权限码</b>：本端点只能碰到「我自己」的偏好 ——
 * 边界是<b>数据归属</b>（tenant_id + membership_id + RLS），不是功能开关。
 * 与 {@code user_preference} / {@code notification_read_state} 同一套论证：
 * 另立一个 {@code identity.notification.*} 权限码只会带来一个坏处 ——
 * 管理员随时会新建角色，而新角色默认没有这个码，于是普通成员的偏好静默失效
 * （而且界面上看不出任何异常）。
 */
@RestController
@RequestMapping("/identity/notification-preferences")
public class NotificationPreferenceController {

  private final IdentityController identity;
  private final NotificationPreferenceService preference;
  private final TenantTransactions tenants;

  public NotificationPreferenceController(
      IdentityController identity,
      NotificationPreferenceService preference,
      DSLContext sql,
      PlatformTransactionManager manager) {
    this.identity = identity;
    this.preference = preference;
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
   * 能力声明：渠道词表 + **不可关闭的底线清单** + 时段上限 + 边界。
   *
   * <p>前端按这里下发的词表渲染，不自己写一份渠道清单 ——
   * 两份词表迟早会漂移，而漂移的表现是「勾了一个服务端不认的渠道，保存时被拒」。
   */
  @GetMapping("/capabilities")
  public NotificationPreferenceCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        actor -> {
          // 能力声明不碰个人数据，但仍然要走一遍会话与租户事务：
          // 未登录时它必须同样返回 401，而不是泄露一份「这里有什么功能」的清单。
          NotificationPreferenceCapabilities capabilities = preference.capabilities();
          return capabilities;
        });
  }

  /** 读我的通知偏好（**生效值**：含强制保留的底线渠道）。 */
  @GetMapping
  public NotificationPreferenceView read(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        actor -> preference.read(actor.tenantId(), actor.membershipId()));
  }

  /**
   * 保存我的通知偏好（**upsert**：同一 page key 覆盖，不新增行）。
   *
   * <p>没给的字段保持原值；渠道集合为空、试图关掉关键业务通知的底线渠道、
   * 时段形状不对、时区不是 IANA 区域 id —— 都在服务端被拒（400）。
   */
  @PutMapping
  public NotificationPreferenceView save(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody NotificationPreferenceInput input) {
    return call(
        auth,
        actor -> preference.save(actor.tenantId(), actor.membershipId(), input));
  }

  /** 重置（删掉那一行 = 回到默认）。幂等：本来没存过也算成功。 */
  @DeleteMapping
  public NotificationPreferenceView reset(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        actor -> preference.reset(actor.tenantId(), actor.membershipId()));
  }

  /**
   * 解析某一类通知**现在**该走哪些渠道。
   *
   * <p>这是蓝图「输出：偏好配置；<b>供 1.3.2.2 推送时过滤</b>」的落点：
   * 判定全在服务端做完（渠道词表、底线项、免打扰时段），
   * 下游拿到的是结论而不是原始载荷。
   *
   * <p>用 POST 是因为要显式给出「解析哪一类」——它不是一个资源路径，
   * 而是一次基于当前偏好的判定（和当下时刻有关，不是幂等的资源读）。
   */
  @PostMapping("/resolve")
  public NotificationPreferenceResolution resolve(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody NotificationPreferenceResolveRequest input) {
    String type = input == null ? null : input.type();
    return call(
        auth,
        actor -> preference.resolve(actor.tenantId(), actor.membershipId(), type));
  }
}
