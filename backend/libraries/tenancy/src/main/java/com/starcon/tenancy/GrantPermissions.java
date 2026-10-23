package com.starcon.tenancy;

import java.util.List;

/**
 * 角色授权判定（1.2.1.1 全局搜索引入）。
 *
 * <p><b>判定规则与各服务 {@code AccessService.require} 逐字同构</b>：命中某条授权时，
 * 同一条授权里必须**同时**有「动作权限」与「该动作所属页面的 {@code .read} 权限」，
 * 且数据范围（ALL / SELF / DEPARTMENT）成立。否则「能写不能读」这种组合会被静默放行。
 *
 * <p><b>为什么抽到这里而不是各服务各写一份</b>：授权判定一旦出现第二个实现，
 * 「哪些端点信任什么」就变成需要逐个审计的问题 —— 这正是各服务 {@code AccessService}
 * 类注释里写死的纪律（「刻意不在本服务重写一份简化版守卫」）。本类只提供**纯判定**，
 * 不解析租户、不发起任何调用：租户永远来自已认证的成员关系，不来自请求头。
 *
 * <p><b>本类不是租户隔离</b>：租户隔离由各服务的 RLS（{@code app.current_tenant}）在数据库层强制，
 * 本类只回答「这个会话有没有某个页面的读权限」。
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
}
