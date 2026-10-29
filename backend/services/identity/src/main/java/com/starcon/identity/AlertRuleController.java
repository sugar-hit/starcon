package com.starcon.identity;

import static com.starcon.identity.AlertRuleModels.AlertRuleCapabilities;
import static com.starcon.identity.AlertRuleModels.AlertRuleEffective;
import static com.starcon.identity.AlertRuleModels.AlertRuleHitHistory;
import static com.starcon.identity.AlertRuleModels.AlertRuleList;
import static com.starcon.identity.AlertRuleModels.AlertRuleSaveRequest;

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
 * 1.3.2.1 预警规则 —— 端点（identity 服务）。
 *
 * <p><b>为什么在 identity</b>：卷 01 §1.3「消息与预警」整域都在 identity ——
 * 1.3.1.1 通知列表（{@code /identity/notification-inbox}）、
 * 1.3.1.2 通知偏好（{@code /identity/notification-preferences}）都在这里。
 * 预警规则是同一域的第三块：它存的是**租户级共享的运营配置**（预警线），
 * 不是任何业务服务的事实 —— warehouse / trade / channel / finance 都不拥有
 * 「本租户想按什么线预警」这个概念。
 *
 * <p><b>租户与成员都来自已认证会话</b>：{@code X-Tenant-Id} 头在这里从头到尾
 * 没有出现过，伪造它改不动 {@code app.current_tenant}。
 *
 * <p><b>请求体不接受 tenantId / userId / membershipId</b>：
 * 请求里带 membershipId 就是把「这条规则属于哪个租户」这条边界交给客户端了。
 * （identity 的 Jackson 配了 {@code fail-on-unknown-properties: true}，
 * 所以这些字段会被**直接 400 拒掉**，而不是被静默忽略。）
 *
 * <p><b>权限</b>：
 *
 * <ul>
 *   <li>{@code identity.alert_rule.read} —— 看预置规则集、词表、生效规则清单、命中历史；</li>
 *   <li>{@code identity.alert_rule.write} —— 建 / 改 / 关 / 开 / 删 / 恢复默认。
 *       写动作还要求授权范围是 {@code ALL}：规则是**租户级共享**的配置，
 *       一个「只对本部门生效」的授权不该能改全租户的告警线。</li>
 * </ul>
 *
 * <p>与各服务 {@code AccessService.require} 同构：命中某条授权时，同一条授权里
 * 必须同时有「动作权限」与「该动作所属页面的 {@code .read} 权限」。
 *
 * <p><b>本端点不做规则求值</b>：蓝图明写「规则引擎本体在 18 系统，此处只做配置界面」。
 * 命中历史（{@code /hits}）只读，引擎未接通时返回<b>空集合 + 明确的未接通说明</b> ——
 * 绝不伪造命中记录。
 */
@RestController
@RequestMapping("/identity/alert-rules")
public class AlertRuleController {

  /** 读侧权限码。 */
  static final String READ = "identity.alert_rule.read";

  /** 写侧权限码。 */
  static final String WRITE = "identity.alert_rule.write";

  private final IdentityController identity;
  private final AlertRuleService rules;
  private final TenantTransactions tenants;

  public AlertRuleController(
      IdentityController identity,
      AlertRuleService rules,
      DSLContext sql,
      PlatformTransactionManager manager) {
    this.identity = identity;
    this.rules = rules;
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
                    ? "当前角色没有预警规则修改权限（预警规则是租户级共享配置，需要租户范围的授权）"
                    : "当前角色没有预警规则查看权限");
          }
          return work.apply(actor);
        });
  }

  /**
   * 能力声明：预置规则集 + 受控词表 + 引擎是否接通 + 逐条边界。
   *
   * <p>前端按这里下发的词表渲染，不自己写一份清单 ——
   * 两份词表迟早会漂移，而漂移的表现是「建了一条规则，引擎说度量不认识」。
   */
  @GetMapping("/capabilities")
  public AlertRuleCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, false, actor -> rules.capabilities());
  }

  /**
   * 生效规则清单（= 预置默认值 ⨯ 租户覆盖 合并后的**生效值**）。
   *
   * <p>每条带**来源标记**：预置 / 自定义 / 已被本租户改过 / 已关闭。
   */
  @GetMapping
  public AlertRuleList list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, false, actor -> rules.list(actor.tenantId()));
  }

  /** 新建自定义规则（五项定义 + 名称 + 对象范围 + 时区，全部必填）。 */
  @PostMapping
  public AlertRuleEffective create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody AlertRuleSaveRequest input) {
    return call(
        auth,
        true,
        actor -> rules.create(actor.tenantId(), actor.membershipId(), input));
  }

  /**
   * 改一条规则（**逐字段覆盖**：给了的覆盖，没给的保持）。
   *
   * <p>对预置规则：写的是覆盖行；把某一项改回预置默认值 = 清掉那一项的覆盖。
   */
  @PutMapping("/{id}")
  public AlertRuleEffective update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody AlertRuleSaveRequest input) {
    return call(auth, true, actor -> rules.update(actor.tenantId(), actor.membershipId(), id, input));
  }

  /** 关闭一条规则（蓝图明说「用户可改参数或关闭」）。 */
  @PostMapping("/{id}/disable")
  public AlertRuleEffective disable(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(
        auth, true, actor -> rules.setEnabled(actor.tenantId(), actor.membershipId(), id, false));
  }

  /** 重新启用一条规则。 */
  @PostMapping("/{id}/enable")
  public AlertRuleEffective enable(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(
        auth, true, actor -> rules.setEnabled(actor.tenantId(), actor.membershipId(), id, true));
  }

  /** 恢复预置规则的默认值（= 删掉那行覆盖）。只对预置规则有意义。 */
  @PostMapping("/{id}/reset")
  public void reset(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    call(
        auth,
        true,
        actor -> {
          rules.reset(actor.tenantId(), id);
          return null;
        });
  }

  /**
   * 删除一条**自定义**规则。
   *
   * <p>🔴 预置规则**不允许删除**（409）—— 它属于平台的跨境常用规则集，
   * 删掉等于让「这条规则存在过」说不清。要让它别生效请用「关闭」。
   */
  @DeleteMapping("/{id}")
  public void delete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    call(
        auth,
        true,
        actor -> {
          rules.delete(actor.tenantId(), id);
          return null;
        });
  }

  /**
   * 命中历史（**只读**）。
   *
   * <p>🔴 引擎（18 系统）未接通 ⇒ 列表恒为空，并在响应里写明「引擎未接通 + 归谁」，
   * 绝不返回假命中记录。空列表 ≠ 「0 次命中」。
   */
  @GetMapping("/hits")
  public AlertRuleHitHistory hits(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return call(auth, false, actor -> rules.hits(actor.tenantId(), limit, offset));
  }
}
