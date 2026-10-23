package com.starcon.identity;

import static com.starcon.identity.ShortcutTemplateModels.*;

import com.starcon.tenancy.TenantTransactions;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
 * 1.2.2.2 快捷入口配置 —— 端点（identity 服务）。
 *
 * <p><b>为什么在 identity</b>：岗位模板的适用范围指向 {@code role.id} / {@code department.id}，
 * 这两张表都在 identity 的库里；个人覆盖项又是纯个人偏好（同 1.4 个人设置）。
 * 两者都属组织与个人配置域，业务服务一个都不拥有。
 *
 * <p><b>租户、成员、岗位、部门全部来自已认证会话</b>：{@code X-Tenant-Id} 头在这里从头到尾
 * 没有出现过 —— 伪造它改不动 {@code app.current_tenant}，也拿不到别人的覆盖项。
 *
 * <p><b>权限</b>：
 *
 * <ul>
 *   <li>{@code identity.shortcut.read} —— 看模板、看「最终生效的快捷入口集合」、
 *       读写自己的覆盖项。
 *   <li>{@code identity.shortcut.manage} —— 建/改/删模板（岗位模板是租户级组织配置）。
 * </ul>
 *
 * <p>与各服务 {@code AccessService.require} 同构：命中某条授权时，同一条授权里必须同时有
 * 「动作权限」与「该动作所属页面的 {@code .read} 权限」。
 */
@RestController
@RequestMapping("/identity/shortcut-templates")
public class ShortcutTemplateController {

  /** 读侧权限码。 */
  static final String READ = "identity.shortcut.read";

  /** 管理侧权限码（模板是租户级组织配置）。 */
  static final String MANAGE = "identity.shortcut.manage";

  private final IdentityController identity;
  private final ShortcutTemplateService templates;
  private final TenantTransactions tenants;

  public ShortcutTemplateController(
      IdentityController identity,
      ShortcutTemplateService templates,
      DSLContext sql,
      PlatformTransactionManager manager) {
    this.identity = identity;
    this.templates = templates;
    this.tenants = new TenantTransactions(sql, new TransactionTemplate(manager));
  }

  /**
   * 统一入口：先确认会话与权限（租户、成员、岗位、部门都来自这里），再在租户事务里执行。
   *
   * @param manage 是否要求模板管理权限（true 时还要 scope=ALL：模板是租户级配置）
   */
  private <T> T call(String auth, boolean manage, Function<IdentityController.Access, T> work) {
    var actor = identity.resolve(auth);
    return tenants.run(
        actor.tenantId(),
        () -> {
          String action = manage ? MANAGE : READ;
          String pageRead = READ;
          boolean allowed =
              actor.grants().stream()
                  .anyMatch(
                      g ->
                          g.permissions().contains(action)
                              && g.permissions().contains(pageRead)
                              && (!manage
                                  || g.scopeMode().equals("ALL")
                                  || g.scopeMode().equals("DEPARTMENT")
                                          && actor.departmentId() != null));
          if (!allowed) {
            throw IdentityController.error(
                HttpStatus.FORBIDDEN,
                manage ? "当前角色没有快捷入口模板管理权限" : "当前角色没有快捷入口查看权限");
          }
          return work.apply(actor);
        });
  }

  /** 能力声明：本模块兑现了什么、边界在哪。 */
  @GetMapping("/capabilities")
  public ShortcutCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, false, actor -> templates.capabilities());
  }

  /** 模板列表（真分页 + 真筛选）。 */
  @GetMapping
  public ShortcutTemplateList list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String scopeType,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return call(
        auth,
        false,
        actor -> templates.listTemplates(actor.tenantId(), scopeType, status, limit, offset));
  }

  /**
   * **当前登录用户最终生效的快捷入口集合**（= 模板 + 个人覆盖的合并结果）。
   *
   * <p>这才是蓝图 1.2.2.2 的「输出」。收藏列表的渲染与拖拽排序属于 1.2.2.1，本模块不提供。
   */
  @GetMapping("/effective")
  public EffectiveShortcutSet effective(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        false,
        actor ->
            templates.effective(
                actor.tenantId(),
                actor.membershipId(),
                roleIds(actor),
                actor.departmentId(),
                permissions(actor)));
  }

  /** 当前这个人的个人覆盖项。 */
  @GetMapping("/overrides")
  public List<ShortcutOverride> overrides(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        false,
        actor -> templates.listOverrides(actor.tenantId(), actor.membershipId()));
  }

  /**
   * 写一条个人覆盖项（同一个 targetKey 重复提交是覆盖，不是叠加）。
   *
   * <p>目标必须命中「我当前生效集合」里真实存在的入口 —— 覆盖项不能凭空新增入口
   * （新增是 1.2.2.1 常用页面收藏的职责）。
   */
  @PostMapping("/overrides")
  public ShortcutOverride upsertOverride(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody ShortcutOverrideInput input) {
    return call(
        auth,
        false,
        actor ->
            templates.upsertOverride(
                actor.tenantId(),
                actor.membershipId(),
                roleIds(actor),
                actor.departmentId(),
                permissions(actor),
                input));
  }

  /** 删掉自己的覆盖项。 */
  @DeleteMapping("/overrides/{id}")
  public void deleteOverride(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    call(
        auth,
        false,
        actor -> {
          templates.deleteOverride(actor.tenantId(), actor.membershipId(), id);
          return null;
        });
  }

  /** 单张模板。 */
  @GetMapping("/{id}")
  public ShortcutTemplate get(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, false, actor -> templates.getTemplate(actor.tenantId(), id));
  }

  /** 新建模板。 */
  @PostMapping
  public ShortcutTemplate create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody ShortcutTemplateInput input) {
    return call(
        auth,
        true,
        actor -> templates.createTemplate(actor.tenantId(), actor.membershipId(), input));
  }

  /** 改模板。 */
  @PutMapping("/{id}")
  public ShortcutTemplate update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody ShortcutTemplateInput input) {
    return call(
        auth, true, actor -> templates.updateTemplate(actor.tenantId(), actor.membershipId(), id, input));
  }

  /** 删模板。 */
  @DeleteMapping("/{id}")
  public void delete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth, @PathVariable UUID id) {
    call(
        auth,
        true,
        actor -> {
          templates.deleteTemplate(actor.tenantId(), id);
          return null;
        });
  }

  /** 我持有的岗位（来自已认证会话的授权，不是请求参数）。 */
  private static List<UUID> roleIds(IdentityController.Access actor) {
    return actor.grants().stream().map(IdentityController.Grant::roleId).distinct().toList();
  }

  /** 我实际持有的权限码（来自已认证会话）。 */
  private static Set<String> permissions(IdentityController.Access actor) {
    return new LinkedHashSet<>(actor.permissions());
  }
}
