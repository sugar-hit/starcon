package com.starcon.identity;

import static com.starcon.identity.UserPreferenceModels.UserPreferenceInput;
import static com.starcon.identity.UserPreferenceModels.UserPreferenceList;
import static com.starcon.identity.UserPreferenceModels.UserPreferenceView;

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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 1.4.1.2 列表与表格偏好 —— 「个人视图状态」端点（identity 服务）。
 *
 * <p><b>为什么在 identity</b>：列表偏好与全局搜索的最近记录一样，是<b>纯个人偏好</b>，
 * 与 1.4 个人设置同域；它不属于任何业务服务（catalog / trade / channel / warehouse
 * 都不拥有个人偏好数据），也不属于前端 localStorage（localStorage 只能当缓存层，
 * 权威副本必须在后端 —— 否则换台设备列表视图就丢了）。
 *
 * <p><b>租户与 membership_id 都来自已认证会话</b>：{@code X-Tenant-Id} 头在这里从头到尾
 * 没有出现过，伪造它改不动 {@code app.current_tenant}，也拿不到别人的 membership_id；
 * 请求体里也**不接受** membershipId / userId / tenantId。
 *
 * <p><b>为什么没有独立权限码</b>：本端点只能碰到「我自己」的偏好 ——
 * 边界是<b>数据归属</b>（tenant_id + membership_id + RLS），不是功能开关。
 * 另立一个 {@code identity.user_preference.*} 权限码只会带来一个坏处：
 * 管理员随时会新建角色，而新角色默认没有这个码，于是普通成员的列表页偏好静默失效。
 * 凡是会暴露**别人**数据的入口才需要独立权限码（全局搜索、操作日志、审批记录……），
 * 那些入口各有各的权限码。
 */
@RestController
@RequestMapping("/identity/user-preferences")
public class UserPreferenceController {

  private final IdentityController identity;
  private final UserPreferenceService preferences;
  private final TenantTransactions tenants;

  public UserPreferenceController(
      IdentityController identity,
      UserPreferenceService preferences,
      DSLContext sql,
      PlatformTransactionManager manager) {
    this.identity = identity;
    this.preferences = preferences;
    this.tenants = new TenantTransactions(sql, new TransactionTemplate(manager));
  }

  /**
   * 统一入口：先确认会话（租户与 membership_id 都从这里来），再在租户事务里执行。
   *
   * <p>刻意**不**接收任何身份参数：请求里带 membershipId 就是把「只能改自己的」这条边界
   * 交给客户端了。
   */
  private <T> T call(String auth, Function<IdentityController.Access, T> work) {
    IdentityController.Access actor = identity.resolve(auth);
    UUID membershipId = actor.membershipId();
    if (membershipId == null) {
      throw IdentityController.error(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
    }
    return tenants.run(actor.tenantId(), () -> work.apply(actor));
  }

  /** 读一个页面 key 的偏好。没存过时返回 {@code stored=false}（不是 404）。 */
  @GetMapping("/{pageKey}")
  public UserPreferenceView read(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable("pageKey") String rawPageKey) {
    String pageKey = UserPreferencePayloadRules.normalizePageKey(rawPageKey);
    return call(
        auth,
        actor -> preferences.read(actor.tenantId(), actor.membershipId(), pageKey));
  }

  /**
   * 保存一个页面 key 的偏好（**upsert**：同一 page_key 覆盖，不新增行）。
   *
   * <p>载荷里的敏感字段、非法键名、超深嵌套、超大体积都在服务端被拒（400）。
   */
  @PutMapping("/{pageKey}")
  public UserPreferenceView save(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable("pageKey") String rawPageKey,
      @RequestBody UserPreferenceInput input) {
    String pageKey = UserPreferencePayloadRules.normalizePageKey(rawPageKey);
    return call(
        auth,
        actor -> preferences.save(actor.tenantId(), actor.membershipId(), pageKey, input));
  }

  /** 重置一个页面 key 的偏好（删掉那一行 = 回到默认值）。幂等：本来没存过也算成功。 */
  @DeleteMapping("/{pageKey}")
  public void reset(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable("pageKey") String rawPageKey) {
    String pageKey = UserPreferencePayloadRules.normalizePageKey(rawPageKey);
    call(
        auth,
        actor -> {
          preferences.reset(actor.tenantId(), actor.membershipId(), pageKey);
          return null;
        });
  }

  /** 列出我全部页面的偏好（设置页用）。 */
  @GetMapping
  public UserPreferenceList list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        actor -> new UserPreferenceList(preferences.list(actor.tenantId(), actor.membershipId())));
  }

  /** 重置我全部页面的偏好（设置页的「全部恢复默认」）。 */
  @DeleteMapping
  public void resetAll(@RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    call(
        auth,
        actor -> {
          preferences.resetAll(actor.tenantId(), actor.membershipId());
          return null;
        });
  }
}
