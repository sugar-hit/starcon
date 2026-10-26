package com.starcon.finance;

import static com.starcon.finance.ReconciliationMatchRuleModels.*;

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
 * 12.3.2.3 自动匹配规则的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /finance/reconciliation-match-rules}</b>：与 12.3.2.1 对账任务
 * （{@code /finance/reconciliation-tasks}）、12.7.1.2 银行对账
 * （{@code /finance/bank-reconciliation}）、12.1.2.3 供应商对账
 * （{@code /finance/supplier-reconciliation}）**刻意不共用一个前缀** ——
 * 那些是别的模块的命名空间（端点、契约、页面都归它们所有）。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p><b>权限码沿用 12 财务核算家族同一套词汇表</b>（{@code finance.receivable.read /
 * .update}）：本模块是 12.3.2.1 的**下游**（规则驱动它的匹配引擎），用同一套词表
 * 跨模块读写才走得通；**刻意不新造权限词表**（新造一档要改 identity 的迁移 ——
 * 那是另一个服务的写入所有权，且迁移版本号由调度者统一分配）。
 */
@RestController
@RequestMapping("/finance/reconciliation-match-rules")
public class ReconciliationMatchRuleController {

  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(ReconciliationMatchRuleController.class);

  private final AccessService access;
  private final ReconciliationMatchRuleService rules;

  public ReconciliationMatchRuleController(AccessService access, ReconciliationMatchRuleService rules) {
    this.access = access;
    this.rules = rules;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "finance.receivable." + permission);
    return access.run(actor, "finance.receivable." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /** 能力声明：哪些能力真接通、哪些还没交付、归谁。 */
  @GetMapping("/capabilities")
  public FinanceMatchRuleCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> rules.capabilities());
  }

  // ================================================================ 筛选项

  /** 筛选项下拉（受控词表 + 12.7.1.1 的真实账户）。 */
  @GetMapping("/options")
  public FinanceMatchRuleOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> rules.options());
  }

  // ================================================================ 规则

  /** 规则列表（真分页 + 真筛选 + 真搜索）。 */
  @GetMapping
  public FinanceMatchRulePage rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "read", actor -> rules.listRules(status, keyword, page, size));
  }

  /** 建规则（匹配键 / 容差 / 优先级 / 模糊兜底 / 匹配率目标 / 适用范围）。 */
  @PostMapping
  public FinanceMatchRuleRow createRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceMatchRuleInput input) {
    return call(auth, "update", actor -> rules.createRule(input, actor));
  }

  /**
   * 匹配率监控视图（匹配率统计 + 未匹配原因分布）。
   *
   * @param taskId 对账任务（12.3.2.1）。**空 = 租户级汇总**。
   */
  @GetMapping("/monitoring")
  public FinanceMatchRateMonitoring monitoring(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID taskId) {
    return call(auth, "read", actor -> rules.monitoring(taskId));
  }

  /** 规则详情。 */
  @GetMapping("/{id}")
  public FinanceMatchRuleRow rule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> rules.detail(id));
  }

  /** 改规则（口径可改；激活中的规则改动立即对新匹配生效）。 */
  @PutMapping("/{id}")
  public FinanceMatchRuleRow updateRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody FinanceMatchRuleInput input) {
    return call(auth, "update", actor -> rules.updateRule(id, input, actor));
  }

  /** 启用规则（同一租户内激活态的优先级必须唯一）。 */
  @PostMapping("/{id}/activate")
  public FinanceMatchRuleRow activateRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "update", actor -> rules.activateRule(id, actor));
  }

  /** 停用规则。 */
  @PostMapping("/{id}/deactivate")
  public FinanceMatchRuleRow deactivateRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "update", actor -> rules.deactivateRule(id, actor));
  }

  /** 规则事件流（真分页；append-only 举证）。 */
  @GetMapping("/{id}/events")
  public FinanceMatchRuleEventPage events(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "read", actor -> rules.events(id, page, size));
  }

  /** 算一次匹配率快照并落库（append-only 举证）。 */
  @PostMapping("/{id}/rate-snapshots")
  public List<FinanceMatchRateSnapshotRow> createRateSnapshot(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody(required = false) FinanceMatchRateSnapshotInput input) {
    var taskId = input == null ? null : input.taskId();
    return call(auth, "update", actor -> rules.createRateSnapshot(id, taskId, actor));
  }

  /** 匹配率快照历史（真分页；append-only）。 */
  @GetMapping("/{id}/rate-snapshots")
  public FinanceMatchRateSnapshotPage rateSnapshots(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "read", actor -> rules.rateSnapshots(id, page, size));
  }
}
