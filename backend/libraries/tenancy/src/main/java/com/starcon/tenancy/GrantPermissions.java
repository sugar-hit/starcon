package com.starcon.tenancy;

import java.util.List;

/**
 * 角色授权判定（1.2.1.1 全局搜索引入）。
 *
 * <p><b>判定规则与各服务 {@code AccessService.require} 逐字同构</b>：命中某条授权时， 同一条授权里必须**同时**有「动作权限」与「该动作所属页面的
 * {@code .read} 权限」， 且数据范围（ALL / SELF / DEPARTMENT）成立。否则「能写不能读」这种组合会被静默放行。
 *
 * <p><b>为什么抽到这里而不是各服务各写一份</b>：授权判定一旦出现第二个实现， 「哪些端点信任什么」就变成需要逐个审计的问题 —— 这正是各服务 {@code AccessService}
 * 类注释里写死的纪律（「刻意不在本服务重写一份简化版守卫」）。本类只提供**纯判定**， 不解析租户、不发起任何调用：租户永远来自已认证的成员关系，不来自请求头。
 *
 * <p><b>本类不是租户隔离</b>：租户隔离由各服务的 RLS（{@code app.current_tenant}）在数据库层强制， 本类只回答「这个会话有没有某个页面的读权限」。
 */
public final class GrantPermissions {

  private GrantPermissions() {}

  /** 一次角色授权。与各服务 {@code AccessService.Grant} 同形，便于从那边直接映射过来。 */
  public record Grant(String roleCode, String scopeMode, List<String> permissions) {}

  /**
   * 单条授权是否覆盖 {@code needed} 这个权限码。
   *
   * @param needed 形如 {@code catalog.sku.read} 的权限码
   * @param hasDepartment 会话是否带部门（DEPARTMENT 范围需要它；没有部门时 DEPARTMENT 不成立）
   */
  public static boolean permits(Grant grant, String needed, boolean hasDepartment) {
    if (grant == null) return false;
    List<String> permissions = grant.permissions();
    if (permissions == null || permissions.isEmpty()) return false;
    int dot = needed.lastIndexOf('.');
    String siblingRead = (dot < 0 ? needed : needed.substring(0, dot)) + ".read";
    if (!permissions.contains(needed) || !permissions.contains(siblingRead)) return false;
    String scope = grant.scopeMode();
    return "ALL".equals(scope)
        || "SELF".equals(scope)
        || ("DEPARTMENT".equals(scope) && hasDepartment);
  }

  /** 会话的全部授权里是否有任意一条覆盖 {@code needed}。 */
  public static boolean permits(List<Grant> grants, String needed, boolean hasDepartment) {
    if (grants == null) return false;
    for (Grant grant : grants) {
      if (permits(grant, needed, hasDepartment)) return true;
    }
    return false;
  }

  // ================================================================ 数据范围

  /** 数据范围：全租户可见。 */
  public static final String SCOPE_ALL = "ALL";

  /** 数据范围：只可见本部门负责的。 */
  public static final String SCOPE_DEPARTMENT = "DEPARTMENT";

  /** 数据范围：只可见本人相关（或本人经手过）的。 */
  public static final String SCOPE_SELF = "SELF";

  /**
   * 数据范围：租户级数据 —— 授权之外**不再**按个人 / 部门收窄。
   *
   * <p>这不是一种授权范围模式，而是「该域的表没有归属维度」时的事实： 往来单位（party）、仓库档案、物流单据这类表上只有 {@code tenant_id}，
   * 任何人的授权都看不到比「本租户」更小的范围。
   */
  public static final String SCOPE_TENANT = "TENANT";

  /**
   * 某个权限码上**实际生效**的数据范围。
   *
   * <p>取「最宽」的一条授权（ALL &gt; DEPARTMENT &gt; SELF），与 RLS 的判定同语义： {@code catalog_scope} / {@code
   * channel_scope} 是「任一条授权成立即成立」， 所以多条授权里只要有一条 ALL，返回的行就是全租户可见 —— 报成 SELF
   * 会与实际可见范围不符（界面就会说「只看到本人的」，实际看到的是全租户的）。
   *
   * <p>DEPARTMENT 需要会话确实带部门（{@code hasDepartment}），否则退化为 SELF —— 往更严的一侧退化，与各服务既有口径一致。
   *
   * @param grants 会话的全部授权
   * @param permission 形如 {@code catalog.sku.read} 的权限码
   * @param hasDepartment 会话是否带部门
   */
  public static String scopeMode(List<Grant> grants, String permission, boolean hasDepartment) {
    if (grants == null || grants.isEmpty()) return SCOPE_SELF;
    String siblingRead = permission.substring(0, permission.lastIndexOf('.')) + ".read";
    boolean all = false;
    boolean department = false;
    for (Grant grant : grants) {
      if (grant == null) continue;
      List<String> permissions = grant.permissions();
      if (permissions == null || permissions.isEmpty()) continue;
      if (!permissions.contains(permission) || !permissions.contains(siblingRead)) continue;
      if (SCOPE_ALL.equals(grant.scopeMode())) all = true;
      else if (SCOPE_DEPARTMENT.equals(grant.scopeMode())) department = true;
    }
    if (all) return SCOPE_ALL;
    if (department && hasDepartment) return SCOPE_DEPARTMENT;
    return SCOPE_SELF;
  }
}
