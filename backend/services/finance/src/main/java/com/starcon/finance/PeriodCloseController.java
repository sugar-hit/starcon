package com.starcon.finance;

import static com.starcon.finance.PeriodCloseModels.*;

import jakarta.validation.Valid;
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
 * 12.5.1.3 关账的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /finance/period-close}</b>：首 token 与既有 finance 端点
 * （{@code receivables} / {@code settlement} / {@code vouchers} / {@code accounting-periods} …）
 * 全不相同，免得「关账」被别的前缀吃掉。
 *
 * <p>租户<b>不</b>来自任何请求头：先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码 {@code finance.period_close.read / .create / .update}：
 * 跑清单、建关账、下调例外是三件不同的事。
 *
 * <p>🔴 <b>没有 DELETE 端点</b>：检查清单与关账完成记录都是**举证凭据**，错了只能重跑一份清单。
 */
@RestController
@RequestMapping("/finance/period-close")
public class PeriodCloseController {

  private final AccessService access;
  private final PeriodCloseService closes;

  public PeriodCloseController(AccessService access, PeriodCloseService closes) {
    this.access = access;
    this.closes = closes;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "finance.period_close." + permission);
    return access.run(actor, "finance.period_close." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /** 能力声明：哪些真接通、哪些还没交付、归谁。 */
  @GetMapping("/capabilities")
  public FinancePeriodCloseCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> closes.capabilities());
  }

  /** 筛选项下拉（全部来自真实数据）。 */
  @GetMapping("/options")
  public FinancePeriodCloseOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> closes.options());
  }

  // ================================================================ 检查清单

  /** 检查清单列表：真分页 + 真筛选。 */
  @GetMapping("/checklists")
  public FinancePeriodCloseChecklistPage checklists(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID periodId,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(auth, "read", actor -> closes.pageChecklists(periodId, keyword, page, size));
  }

  /**
   * **跑一次关账检查清单**（真查数据：四类未处理事项各自查各自的真实数据源）。
   *
   * <p>⚠️ 清单**只追加**：重跑一次多一份证据（run_no 递增），已跑过的那份一个字都不改。
   */
  @PostMapping("/checklists")
  public FinancePeriodCloseChecklistDetail runChecklist(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinancePeriodCloseChecklistInput input) {
    return call(auth, "create", actor -> closes.runChecklist(input, actor, auth));
  }

  /** 清单详情：清单 + 四类检查项 + 按币种的金额分组（**不跨币种求和**）。 */
  @GetMapping("/checklists/{id}")
  public FinancePeriodCloseChecklistDetail checklist(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> closes.checklistDetail(id, auth));
  }

  /** 检查明细（可钻取）：真分页。 */
  @GetMapping("/checklists/{id}/items")
  public FinancePeriodCloseCheckItemPage items(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) UUID checkId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(auth, "read", actor -> closes.items(id, checkId, page, size));
  }

  /**
   * 把一个**阻断项**下调为**可结转未清项**（例外）。
   *
   * <p>🔴 **必须写例外依据** —— 蓝图加粗句「例外与结转须保留依据」。
   */
  @PostMapping("/checklists/{id}/checks/{checkId}/carry-forward")
  public FinancePeriodCloseCheckRow carryForward(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID checkId,
      @Valid @RequestBody FinancePeriodCloseCarryForwardInput input) {
    return call(auth, "update", actor -> closes.carryForward(id, checkId, input, actor));
  }

  // ================================================================ 关账执行

  /** 关账执行记录列表：真分页 + 真筛选。 */
  @GetMapping("/executions")
  public FinancePeriodCloseExecutionPage executions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID periodId,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(auth, "read", actor -> closes.pageExecutions(periodId, status, page, size));
  }

  /**
   * **提交关账**（走 17 审批）。
   *
   * <p>🔴 **阻断项未解决 ⇒ 拒绝关账**：返回 HTTP 409 + **明确的阻断原因清单**
   * （每一条都带检查项、条数与分类依据），期间**仍然开放**。
   */
  @PostMapping("/executions")
  public ResponseEntity<FinancePeriodCloseSubmitResult> submit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinancePeriodCloseExecuteInput input) {
    var result = call(auth, "create", actor -> closes.submit(input, actor, auth));
    if ("BLOCKED".equals(result.outcome())) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(result);
    }
    return ResponseEntity.ok(result);
  }

  /** 关账执行详情：执行记录 + 审批链路 + 据以关账的那份清单。 */
  @GetMapping("/executions/{id}")
  public FinancePeriodCloseExecutionDetail execution(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> closes.executionDetail(id, auth));
  }

  /**
   * **同步审批结果**；审批已批准 ⇒ 真的执行关账（期间随即锁定、禁止过账）。
   *
   * <p>🔴 审批不可达 ⇒ 整个调用失败、**期间保持开放** —— 绝不「读不到就当批过了」。
   */
  @PostMapping("/executions/{id}/sync-approval")
  public FinancePeriodCloseExecutionDetail syncApproval(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "update", actor -> closes.syncApproval(id, actor, auth));
  }
}
