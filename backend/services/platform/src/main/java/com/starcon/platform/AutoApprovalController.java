package com.starcon.platform;

import static com.starcon.platform.AutoApprovalModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
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
 * 18.3.3.1「自动审批」的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /platform/auto-approvals}</b>：与同服务的 {@code /platform/apps}
 * （谁可以接入我们）、{@code /platform/text}（文案怎么翻）、{@code /platform/external-integrations}
 * （外部系统怎么连）同服务、不同前缀，四者管的是完全不同的东西。
 *
 * <p>租户<b>不</b>来自任何请求头：先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p><b>权限码 {@code platform.auto_approval.read / .create / .update / .decide}</b>：
 * 「配置」与「判定」是**两个独立的码** —— 能改规则的人不一定有权让规则替他放行单据，
 * 这两个码必须分开，否则「改规则」就等于「批量放行」。
 *
 * <p>🔴 <b>本模块不代替上游改审批状态</b>：自动决策只写本模块自己的决策台账；
 * 必须走人工的单据经**授权端口**提交上游，由上游的人工审批接手。
 */
@RestController
@RequestMapping("/platform/auto-approvals")
public class AutoApprovalController {

  private final AccessService access;
  private final AutoApprovalService configs;
  private final AutoApprovalDecisionService decisions;

  public AutoApprovalController(
      AccessService access,
      AutoApprovalService configs,
      AutoApprovalDecisionService decisions) {
    this.access = access;
    this.configs = configs;
    this.decisions = decisions;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "platform.auto_approval." + permission);
    return access.run(actor, "platform.auto_approval." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明与概览

  /** 能力声明：这条模块能做什么、**不能**做什么、边界归谁。 */
  @GetMapping("/capabilities")
  public AutoApprovalCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> configs.capabilities());
  }

  /** 概览：几个数。 */
  @GetMapping("/overview")
  public AutoApprovalOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> configs.overview());
  }

  // ================================================================ 规则配置

  /** 自动审批规则配置列表（真筛选 + 真关键词）。 */
  @GetMapping("/policies")
  public List<AutoApprovalPolicyRow> policies(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String documentTypeCode,
      @RequestParam(required = false) String currencyCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword) {
    return call(
        auth, "read", actor -> configs.policies(documentTypeCode, currencyCode, status, keyword));
  }

  /** 一条规则配置。 */
  @GetMapping("/policies/{id}")
  public AutoApprovalPolicyRow policy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> configs.policy(id));
  }

  /** 登记一条自动审批规则。 */
  @PostMapping("/policies")
  public AutoApprovalPolicyRow createPolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AutoApprovalPolicyInput input) {
    return call(auth, "create", actor -> configs.createPolicy(actor, input));
  }

  /** 改一条自动审批规则。 */
  @PutMapping("/policies/{id}")
  public AutoApprovalPolicyRow updatePolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody AutoApprovalPolicyInput input) {
    return call(auth, "update", actor -> configs.updatePolicy(actor, id, input));
  }

  /** 停用一条规则（**不删**：历史决策要能复现）。 */
  @PostMapping("/policies/{id}/disable")
  public AutoApprovalPolicyRow disablePolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "update", actor -> configs.disablePolicy(id));
  }

  // ================================================================ 风险信号码表

  /** 风险信号码表列表。 */
  @GetMapping("/signals")
  public List<AutoApprovalSignalRow> signals(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String signalKind,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword) {
    return call(auth, "read", actor -> configs.signals(signalKind, status, keyword));
  }

  /** 登记一个风险信号码。 */
  @PostMapping("/signals")
  public AutoApprovalSignalRow createSignal(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AutoApprovalSignalInput input) {
    return call(auth, "create", actor -> configs.createSignal(actor, input));
  }

  /** 停用一个风险信号码（**不删**）。 */
  @PostMapping("/signals/{id}/disable")
  public AutoApprovalSignalRow disableSignal(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "update", actor -> configs.disableSignal(id));
  }

  // ================================================================ 判定

  /**
   * **发起一次自动审批判定**（判定 + 记录）。
   *
   * <p>🔴 自动通过 / 自动驳回**不建上游实例**（没有单子需要人审，也就没有待办）；
   * 必须走人工的单据经授权端口**真的**提交上游人工审批。
   */
  @PostMapping("/decisions")
  public AutoApprovalDecisionView evaluate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AutoApprovalEvaluateInput input) {
    return call(auth, "decide", actor -> decisions.evaluate(auth, actor, input));
  }

  /** 决策台账列表（真筛选 + 真关键词）。 */
  @GetMapping("/decisions")
  public List<AutoApprovalDecisionRow> decisions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String documentTypeCode,
      @RequestParam(required = false) String documentRef,
      @RequestParam(required = false) String decision,
      @RequestParam(required = false) String executionState,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "50") int limit) {
    return call(
        auth,
        "read",
        actor ->
            decisions.decisions(
                documentTypeCode, documentRef, decision, executionState, keyword, limit));
  }

  /** 一条决策详情（含**决策依据快照**）。 */
  @GetMapping("/decisions/{id}")
  public AutoApprovalDecisionView decision(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> decisions.decision(id));
  }

  // ================================================================ 超时自动升级

  /**
   * 扫描超时：找出压过时限的单据并留下升级判定。
   *
   * <p>🔴 这是上游**只定义规则、不实现定时器**的那件事：上游的注释逐字写着
   * 「本模块只定义它，不实现任何会去看它并放行的定时器」。
   */
  @PostMapping("/escalations/scan")
  public AutoApprovalEscalationScanView scan(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "decide", actor -> decisions.scan(auth));
  }

  /** 超时升级台账列表。 */
  @GetMapping("/escalations")
  public List<AutoApprovalEscalationRow> escalations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String nodeKey,
      @RequestParam(defaultValue = "50") int limit) {
    return call(auth, "read", actor -> decisions.escalations(status, nodeKey, limit));
  }

  /** 一条超时升级台账。 */
  @GetMapping("/escalations/{id}")
  public AutoApprovalEscalationRow escalation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> decisions.escalation(id));
  }

  /**
   * 执行一次超时升级。
   *
   * <p>🔴 以**发起人自己**的身份在上游执行（上游的审批动作只认当前审批人自己的会话）——
   * 发起人不是当前审批人时，上游会如实拒绝，那个拒绝会被原样记进台账。
   */
  @PostMapping("/escalations/{id}/execute")
  public AutoApprovalEscalationRow execute(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "decide", actor -> decisions.executeEscalation(auth, actor, id));
  }
}
