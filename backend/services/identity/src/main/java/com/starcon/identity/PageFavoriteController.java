package com.starcon.identity;

import static com.starcon.identity.PageFavoriteModels.PageFavoriteInput;
import static com.starcon.identity.PageFavoriteModels.PageFavoriteMove;
import static com.starcon.identity.PageFavoriteModels.PageFavoriteSet;

import com.starcon.tenancy.TenantTransactions;
import java.util.LinkedHashSet;
import java.util.Set;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 1.2.2.1 常用页面收藏 —— 端点（identity 服务）。
 *
 * <p><b>为什么在 identity</b>：常用页面收藏是<b>纯个人配置</b>（与 1.4 个人设置同域），
 * 不属于任何业务服务 —— catalog / trade / channel / warehouse 都不拥有「个人偏好」这个概念。
 *
 * <p><b>租户与 membership_id 都来自已认证会话</b>：{@code X-Tenant-Id} 头在这里从头到尾
 * 没有出现过，伪造它改不动 {@code app.current_tenant}，也拿不到别人的 membership_id；
 * 请求体里也<b>不接受</b> membershipId / userId / tenantId。
 *
 * <p><b>为什么没有独立权限码</b>：本端点只能碰到「我自己」的收藏 ——
 * 边界是<b>数据归属</b>（tenant_id + membership_id + RLS），不是功能开关。
 * 与 1.4.1.2 的 {@code user_preference} 同一套论证：另立一个
 * {@code identity.page_favorite.*} 权限码只会带来一个坏处 ——
 * 管理员随时会新建角色，而新角色默认没有这个码，于是普通成员的收藏静默失效。
 * 蓝图也只把<b>页面级</b>权限当作闸（无权限的收藏自动隐藏），那道闸在数据层，不在入口层。
 *
 * <p><b>与 1.2.2.2 快捷入口配置严格分工</b>：那条线做的是「按角色/团队<b>预置</b>的模板」，
 * 本模块做的是「用户<b>自己收藏</b>」。两个模块不共用表、不共用端点、不共用实现。
 */
@RestController
@RequestMapping("/identity/page-favorites")
public class PageFavoriteController {

  private final IdentityController identity;
  private final PageFavoriteService favorites;
  private final TenantTransactions tenants;

  public PageFavoriteController(
      IdentityController identity,
      PageFavoriteService favorites,
      DSLContext sql,
      PlatformTransactionManager manager) {
    this.identity = identity;
    this.favorites = favorites;
    this.tenants = new TenantTransactions(sql, new TransactionTemplate(manager));
  }

  /**
   * 统一入口：先确认会话（租户与 membership_id 都从这里来），再在租户事务里执行。
   *
   * <p>刻意<b>不</b>接收任何身份参数：请求里带 membershipId 就是把「只能改自己的」
   * 这条边界交给客户端了。
   */
  private <T> T call(String auth, Function<IdentityController.Access, T> work) {
    IdentityController.Access actor = identity.resolve(auth);
    UUID membershipId = actor.membershipId();
    if (membershipId == null) {
      throw IdentityController.error(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
    }
    return tenants.run(
        actor.tenantId(), () -> work.apply(actor));
  }

  /** 我实际持有的权限码（来自已认证会话）—— 权限闸要用。 */
  private static Set<String> permissions(IdentityController.Access actor) {
    return new LinkedHashSet<>(actor.permissions());
  }

  /**
   * 我的收藏清单（有序）。
   *
   * <p>没收藏过时返回空清单（不是 404）；无页面读权限的收藏被<b>自动隐藏</b>，
   * 并在 {@code hiddenByPermission} 里报数。
   */
  @GetMapping
  public PageFavoriteSet list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        actor ->
            favorites.read(actor.tenantId(), actor.membershipId(), permissions(actor)));
  }

  /**
   * 收藏一个页面（同一个路径重复收藏是覆盖，不新增行，也不打乱已有顺序）。
   *
   * <p>入参里的 {@code pageCode} 是目标页面的权限页码：必须真的登记过
   * {@code <pageCode>.read}，否则 400（与 1.2.2.2 的 {@code pageIsRegistered} 同纪律）。
   */
  @PostMapping
  public PageFavoriteSet add(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody PageFavoriteInput input) {
    return call(
        auth,
        actor ->
            favorites.add(
                actor.tenantId(), actor.membershipId(), permissions(actor), input));
  }

  /**
   * 排序：把一条收藏移到可见清单的第 {@code toIndex} 位。
   *
   * <p>拖拽排序与「上移/下移」按钮走的是**同一个端点**。
   */
  @PutMapping("/order")
  public PageFavoriteSet move(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody PageFavoriteMove input) {
    return call(
        auth,
        actor ->
            favorites.move(
                actor.tenantId(), actor.membershipId(), permissions(actor), input));
  }

  /**
   * 取消收藏（幂等：本来没收藏过也算成功）。
   *
   * <p>目标路径走<b>查询参数</b>而不是路径段：路径里带 {@code /} 要转义，
   * 查询参数更不容易出错，也不会让白名单多一个「路径段里含斜杠」的形状。
   */
  @DeleteMapping
  public PageFavoriteSet remove(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam("href") String href) {
    return call(
        auth,
        actor ->
            favorites.remove(actor.tenantId(), actor.membershipId(), permissions(actor), href));
  }

  /**
   * 清掉当前因权限不足而隐藏的收藏。
   *
   * <p>权限闸把无权访问的收藏藏起来，但用户看不见也就删不掉 ——
   * 这个动作让那些幽灵收藏能被一次性清干净。
   */
  @DeleteMapping("/hidden")
  public PageFavoriteSet removeHidden(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        actor ->
            favorites.removeHidden(
                actor.tenantId(), actor.membershipId(), permissions(actor)));
  }
}
