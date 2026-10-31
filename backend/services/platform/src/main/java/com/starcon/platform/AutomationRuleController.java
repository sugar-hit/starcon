package com.starcon.platform;

import static com.starcon.platform.AutomationRuleModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 18.3.1.1「规则列表」的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /platform/automation-rules}</b>：与同服务的
 * {@code /platform/apps}（谁可以接入我们）、{@code /platform/webhooks}（对外提供事件订阅）、
 * {@code /platform/external-integrations}（对接外部系统）同服务、不同前缀，
 * 四者管的是完全不同的东西。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p><b>权限码 {@code platform.automation_rule.read / .create / .update / .record}</b>：
 * 「配规则」「改规则」「登记一次执行结果」是**三个独立的码** ——
 * 能登记规则的人不该顺手就能往执行台账里写结论。
 *
 * <p>🔴 <b>本模块不执行规则</b>：18.3.2「执行」是下游、尚未交付 ⇒
 * {@code /rules/{id}/preview} 只**求值**条件并给出动作计划，
 * {@code sideEffectsApplied} 恒为 false。
 */
@RestController
@RequestMapping("/platform/automation-rules")
public class AutomationRuleController {

  private final AccessService access;
  private final AutomationRuleService rules;

  public AutomationRuleController(AccessService access, AutomationRuleService rules) {
    this.access = access;
    this.rules = rules;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "platform.automation_rule." + permission);
    return access.run(actor, "platform.automation_rule." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明与概览

  /** 能力声明：哪些真交付、哪些还没交付、归谁。 */
  @GetMapping("/capabilities")
  public AutomationRuleCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> rules.capabilities());
  }

  /** 概览：几个数（含「连续失败」这一档）。 */
  @GetMapping("/overview")
  public AutomationRuleOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> rules.overview());
  }

  // ================================================================ 规则档案

  /** 规则列表（真筛选 + 真关键词 + **全序**排序）。 */
  @GetMapping("/rules")
  public List<AutomationRuleRow> list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String eventType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Boolean enabled,
      @RequestParam(required = false) Boolean dryRun,
      @RequestParam(required = false) Boolean failing,
      @RequestParam(required = false) String scopeType,
      @RequestParam(required = false) UUID scopeRef,
      @RequestParam(required = false) String keyword) {
    return call(
        auth,
        "read",
        actor ->
            rules.ruleRows(
                eventType, status, enabled, dryRun, failing, scopeType, scopeRef, keyword));
  }

  /** 一条规则档案（含条件 / 动作 / 启用范围 / 最近几次执行）。 */
  @GetMapping("/rules/{id}")
  public AutomationRuleDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> rules.ruleDetail(id));
  }

  /** 新增或修改一条规则（条件 / 动作 / 启用范围整体替换）。 */
  @PostMapping("/rules")
  public AutomationRuleDetail save(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AutomationRuleInput input) {
    return call(auth, "create", actor -> rules.saveRule(input, actor));
  }

  /** 启用一条规则。 */
  @PostMapping("/rules/{id}/enable")
  public AutomationRuleRow enable(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "update", actor -> rules.enableRule(id));
  }

  /** 停用一条规则（**不删**）。 */
  @PostMapping("/rules/{id}/disable")
  public AutomationRuleRow disable(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "update", actor -> rules.disableRule(id));
  }

  /**
   * 🔴 **开关试运行**（独立状态）。
   *
   * <p>试运行**不改变**启用 / 停用状态 —— 一条规则可以「启用 + 试运行」。
   */
  @PostMapping("/rules/{id}/dry-run")
  public AutomationRuleRow dryRun(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody AutomationRuleDryRunInput input) {
    return call(auth, "update", actor -> rules.setDryRun(id, input.dryRun()));
  }

  /** 🔴 **排序**（一次性给出若干条规则的优先级）。 */
  @PostMapping("/rules/reorder")
  public List<AutomationRuleRow> reorder(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AutomationRuleReorderInput input) {
    return call(auth, "update", actor -> rules.reorder(input));
  }

  // ================================================================ 启用范围（真约束）

  /**
   * 🔴 **范围内生效的规则**。
   *
   * <p>启用范围是**真约束**：这条查询真的 JOIN 范围表 ——
   * 一条没有「整个租户」范围行的规则，不会对任何指定店铺 / 仓库生效。
   */
  @GetMapping("/applicable")
  public List<AutomationRuleApplicableRow> applicable(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID shopId,
      @RequestParam(required = false) UUID warehouseId) {
    return call(auth, "read", actor -> rules.applicable(shopId, warehouseId));
  }

  // ================================================================ 预演（求值，不执行）

  /**
   * 🔴 **预演**：按样本载荷真的求值条件、真的判范围，给出动作计划。
   *
   * <p>本端点<b>不执行任何动作</b>（{@code sideEffectsApplied} 恒为 false）。
   */
  @PostMapping("/rules/{id}/preview")
  public AutomationRulePreview preview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody AutomationRulePreviewInput input) {
    return call(auth, "read", actor -> rules.preview(id, input));
  }

  // ================================================================ 执行统计

  /** 某条规则的执行历史（只追加台账）。 */
  @GetMapping("/rules/{id}/executions")
  public List<AutomationRuleExecutionRow> executions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false, defaultValue = "50") Integer limit) {
    return call(auth, "read", actor -> rules.executionRows(id, limit == null ? 50 : limit));
  }

  /**
   * 🔴 **登记一次执行结果** —— 执行统计的唯一写入口。
   *
   * <p>登记后规则档案上的「最后一次执行（时间 / 结果 / 失败原因）」、
   * 累计计数与「连续失败计数 / 坏规则标记」同步更新。
   *
   * <p>🔴 对**试运行规则**登记「产生了真实副作用」⇒ <b>409</b>。
   */
  @PostMapping("/rules/{id}/executions")
  public ResponseEntity<AutomationRuleExecutionRow> recordExecution(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody AutomationRuleExecutionInput input) {
    return call(
        auth,
        "record",
        actor -> ResponseEntity.ok(rules.recordExecution(id, input, actor)));
  }

  // ================================================================ 上游（只读）

  /** 可用触发事件（来自平台事件注册表，18.1.2.1 交付）。 */
  @GetMapping("/event-types")
  public List<AutomationRuleEventTypeRow> eventTypes(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> rules.eventTypes());
  }

  /** 上游 2.3 店铺（**只读**；读不到 ⇒ 503）。 */
  @GetMapping("/upstream/shops")
  public List<AutomationRuleUpstreamShopRow> upstreamShops(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> rules.upstreamShops(auth));
  }

  /** 上游仓库（**只读**；读不到 ⇒ 503）。 */
  @GetMapping("/upstream/warehouses")
  public List<AutomationRuleUpstreamWarehouseRow> upstreamWarehouses(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> rules.upstreamWarehouses(auth));
  }
}
