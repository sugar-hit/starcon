package com.starcon.finance;

import static com.starcon.finance.BankReconciliationModels.*;

import jakarta.validation.Valid;
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
 * 12.7.1.2 银行对账的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /finance/bank-reconciliation}</b>：与同 L3 的 12.7.1.1 账户管理
 * （{@code /finance/fund-accounts}）、12.7.2.1 资金分析（{@code /finance/fund-analysis}）、
 * 12.2.1.1 收款单（{@code /finance/receipts}）**刻意不共用一个前缀** ——
 * 那些是别的模块的命名空间（端点、契约、页面都归它们所有）。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p><b>权限码沿用同 L3 兄弟模块 12.7.1.1 的 {@code finance.receivable.read / .update}</b>：
 * 12.7 资金家族共用同一套词汇表。刻意<b>不新造权限词表</b> ——
 * 权限码在 identity 的 {@code permission(code)} 目录里，新造一档要改另一个服务的迁移
 * （那是它的写入所有权，且迁移版本号由调度者统一分配）。
 */
@RestController
@RequestMapping("/finance/bank-reconciliation")
public class BankReconciliationController {

  private final AccessService access;
  private final BankReconciliationService recon;

  public BankReconciliationController(AccessService access, BankReconciliationService recon) {
    this.access = access;
    this.recon = recon;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "finance.receivable." + permission);
    return access.run(actor, "finance.receivable." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：哪些能力真接通、哪些还没交付、归谁。
   *
   * <p>页面把它固定显示在顶部 —— 「卷 12 硬约束：不允许金额相同即匹配」
   * 「12.2 的受管账户引用尚未接线」这些事必须让人在页面上看到。
   */
  @GetMapping("/capabilities")
  public FinanceBankReconCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> recon.capabilities());
  }

  // ================================================================ 筛选项

  /** 筛选项下拉（受控词表 + 12.7.1.1 的真实账户 + 本租户的真实规则）。 */
  @GetMapping("/options")
  public FinanceBankReconOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> recon.options());
  }

  // ================================================================ 对账批次

  /** 对账批次列表（真分页 + 真筛选 + 真搜索）。 */
  @GetMapping("/sessions")
  public FinanceBankReconSessionPage sessions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID accountId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "read",
        actor -> recon.listSessions(accountId, status, keyword, page, size));
  }

  /**
   * 建对账批次。
   *
   * <p>⚠️ 账户必须存在且启用中；币种**继承账户**（单账户单币种）。
   */
  @PostMapping("/sessions")
  public FinanceBankReconSessionRow createSession(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceBankReconSessionCreateInput input) {
    return call(auth, "update", actor -> recon.createSession(input, actor));
  }

  /** 对账批次详情。 */
  @GetMapping("/sessions/{id}")
  public FinanceBankReconSessionRow session(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> recon.getSession(id));
  }

  // ================================================================ 流水导入

  /**
   * 导入银行 / 支付平台流水（CSV / JSON 真解析）。
   *
   * <p>⚠️ 逐行解析：不合法的行**不静默丢弃** —— 它们进响应里的 {@code warnings}（带行号与原因）。
   */
  @PostMapping("/sessions/{id}/import")
  public FinanceBankReconImportResult importStatement(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody FinanceBankReconImportInput input) {
    return call(auth, "update", actor -> recon.importStatement(id, input, actor));
  }

  // ================================================================ 自动匹配

  /**
   * 跑自动匹配。
   *
   * <p>⚠️ **不允许「金额相同即匹配」**（卷 12 硬约束）：三种规则类型都至少组合两个独立维度
   * + 方向 + 金额；日期窗口上限 31 天。
   */
  @PostMapping("/sessions/{id}/match")
  public FinanceBankReconMatchResult runMatch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "update", actor -> recon.runMatch(id, actor));
  }

  // ================================================================ 流水 / 系统记录 / 匹配结果

  /** 银行流水行（真分页）。 */
  @GetMapping("/sessions/{id}/statement-lines")
  public FinanceBankReconStatementLinePage statementLines(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "read", actor -> recon.statementLines(id, page, size));
  }

  /** 系统记录快照（真分页）。 */
  @GetMapping("/sessions/{id}/system-lines")
  public FinanceBankReconSystemLinePage systemLines(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "read", actor -> recon.systemLines(id, page, size));
  }

  /** 匹配结果（真分页）。 */
  @GetMapping("/sessions/{id}/matches")
  public FinanceBankReconMatchPage matches(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "read", actor -> recon.matches(id, page, size));
  }

  // ================================================================ 未匹配清单

  /** 未匹配清单（真分页 + 真筛选）。 */
  @GetMapping("/sessions/{id}/unmatched")
  public FinanceBankReconUnmatchedPage unmatched(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String side,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "read", actor -> recon.unmatched(id, status, side, page, size));
  }

  /**
   * 人工认领到具体单据。
   *
   * <p>⚠️ 认领目标必须**真的存在于本租户**（DB 触发器再挡一次）；
   * 认领**只写本模块的表**，不改被认领单据的状态。
   */
  @PostMapping("/sessions/{id}/unmatched/{unmatchedId}/claim")
  public FinanceBankReconUnmatchedRow claim(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID unmatchedId,
      @Valid @RequestBody FinanceBankReconClaimInput input) {
    return call(auth, "update", actor -> recon.claim(id, unmatchedId, input, actor));
  }

  /** 排除一条未匹配项（必须写理由）。 */
  @PostMapping("/sessions/{id}/unmatched/{unmatchedId}/exclude")
  public FinanceBankReconUnmatchedRow exclude(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID unmatchedId,
      @Valid @RequestBody FinanceBankReconExcludeInput input) {
    return call(auth, "update", actor -> recon.exclude(id, unmatchedId, input, actor));
  }

  /** 认领 / 说明记录（真分页；append-only 举证）。 */
  @GetMapping("/sessions/{id}/claims")
  public FinanceBankReconClaimPage claims(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) UUID unmatchedId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "read", actor -> recon.claims(id, unmatchedId, page, size));
  }

  // ================================================================ 对账报告

  /** 生成对账报告并确认对账（确认是**终态**）。 */
  @PostMapping("/sessions/{id}/reconcile")
  public FinanceBankReconReportRow reconcile(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "update", actor -> recon.reconcile(id, actor));
  }

  /** 读已生成的对账报告（还没生成 ⇒ 404）。 */
  @GetMapping("/sessions/{id}/report")
  public FinanceBankReconReportRow report(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> recon.report(id));
  }

  // ================================================================ 事件流

  /** 事件流（真分页；append-only 举证）。 */
  @GetMapping("/sessions/{id}/events")
  public FinanceBankReconEventPage events(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "read", actor -> recon.events(id, page, size));
  }

  // ================================================================ 匹配规则

  /** 匹配规则列表（真分页）。 */
  @GetMapping("/rules")
  public FinanceBankReconRulePage rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "read", actor -> recon.rules(page, size));
  }

  /** 新建一条匹配规则（规则表**不预置任何一行**，租户自己维护）。 */
  @PostMapping("/rules")
  public FinanceBankReconRuleRow createRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceBankReconRuleInput input) {
    return call(auth, "update", actor -> recon.createRule(input, actor));
  }

  /** 改一条匹配规则（**规则编码不可改** —— 编码是历史匹配的追溯依据）。 */
  @PutMapping("/rules/{id}")
  public FinanceBankReconRuleRow updateRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody FinanceBankReconRuleInput input) {
    return call(auth, "update", actor -> recon.updateRule(id, input, actor));
  }
}
