package com.starcon.identity;

import static com.starcon.identity.GlobalSearchRecentModels.*;

import com.starcon.tenancy.TenantTransactions;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 1.2.1.1 全局搜索框 —— 「最近搜索记录」端点（identity 服务）。
 *
 * <p><b>为什么在 identity</b>：最近搜索是<b>纯个人偏好</b>，与 1.4 个人设置同域；
 * 它不属于任何业务服务（catalog / trade / channel / warehouse 都不拥有个人偏好数据），
 * 也不属于前端 localStorage（localStorage 只能当缓存层，权威副本必须在后端 ——
 * 否则换台设备最近搜索就空了，而最近搜索本来就是跨设备才有意义的东西）。
 *
 * <p><b>租户与 user_id 都来自已认证会话</b>：{@code X-Tenant-Id} 头在这里从头到尾没有出现过，
 * 伪造它改不动 {@code app.current_tenant}，也拿不到别人的搜索记录。
 *
 * <p><b>权限</b>：{@code identity.global_search.read}（与四个业务服务的检索端点同一个入口权限码）。
 * 「谁能用全局搜索」是一个独立决定 —— 它不是某个域的读权限的副产品。
 */
@RestController
@RequestMapping("/identity/global-search")
public class GlobalSearchRecentController {

  /** 入口权限码：全局搜索是一个独立能力，单独授权（V330 注册）。 */
  static final String SEARCH = "identity.global_search.read";

  private final IdentityController identity;
  private final GlobalSearchRecentService recent;
  private final TenantTransactions tenants;

  public GlobalSearchRecentController(
      IdentityController identity,
      GlobalSearchRecentService recent,
      DSLContext sql,
      PlatformTransactionManager manager) {
    this.identity = identity;
    this.recent = recent;
    this.tenants = new TenantTransactions(sql, new TransactionTemplate(manager));
  }

  /**
   * 统一入口：先确认会话与权限（租户与 user_id 都来自这里），再在租户事务里执行。
   *
   * <p>权限判定与各服务 {@code AccessService.require} 同构：命中某条授权时，
   * 同一条授权里必须同时有「动作权限」与「该动作所属页面的 {@code .read} 权限」。
   */
  private <T> T call(String auth, Function<IdentityController.Access, T> work) {
    var actor = identity.resolve(auth);
    return tenants.run(
        actor.tenantId(),
        () -> {
          boolean allowed =
              actor.grants().stream()
                  .anyMatch(
                      g ->
                          g.permissions().contains(SEARCH)
                              && g.permissions()
                                  .contains(
                                      SEARCH.substring(0, SEARCH.lastIndexOf('.')) + ".read")
                              && (g.scopeMode().equals("ALL")
                                  || g.scopeMode().equals("SELF")
                                  || (g.scopeMode().equals("DEPARTMENT")
                                      && actor.departmentId() != null)));
          if (!allowed) {
            throw IdentityController.error(HttpStatus.FORBIDDEN, "当前角色没有全局搜索权限");
          }
          return work.apply(actor);
        });
  }

  /** 这个人的最近搜索（新的在前）。 */
  @GetMapping("/recent")
  public GlobalSearchRecentList list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(defaultValue = "10") int limit) {
    return call(
        auth,
        actor -> recent.list(actor.tenantId(), actor.userId(), limit));
  }

  /**
   * 登记一条最近搜索。
   *
   * <p>前端在用户<b>选中</b>一条结果时调用（不是每次键入都调用）——
   * 否则「最近搜索」会变成击键历史，而不是「最近找过的东西」。
   */
  @PostMapping("/recent")
  public GlobalSearchRecentEntry record(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody GlobalSearchRecentInput input) {
    return call(
        auth,
        actor -> recent.record(actor.tenantId(), actor.userId(), input));
  }

  /** 清空这个人的最近搜索。 */
  @DeleteMapping("/recent")
  public void clear(@RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    call(
        auth,
        actor -> {
          recent.clear(actor.tenantId(), actor.userId());
          return null;
        });
  }
}
