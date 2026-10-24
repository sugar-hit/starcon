package com.starcon.finance;

import static com.starcon.finance.ReconciliationTaskModels.*;

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
 * 12.3.2.1 对账任务的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /finance/reconciliation-tasks}</b>：与 12.7.1.2 银行对账
 * （{@code /finance/bank-reconciliation}）、12.1.2.3 供应商对账
 * （{@code /finance/supplier-reconciliation}）、12.3.1.1 结算单
 * （{@code /finance/settlement}）**刻意不共用一个前缀** ——
 * 那些是别的模块的命名空间（端点、契约、页面都归它们所有）。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p><b>权限码沿用 12.7.1.2 银行对账的 {@code finance.receivable.read / .update}</b>：
 * ① 12 财务核算家族共用同一套词汇表，**刻意不新造权限词表**（新造一档要改 identity 的
 * 迁移 —— 那是另一个服务的写入所有权，且迁移版本号由调度者统一分配）；
 * ② 本模块要经授权只读 API 读 12.7.1.2 的银行流水，那条路径本来就要求
 * {@code finance.receivable.read} ⇒ 用同一套词表，跨模块读才走得通。
 */
@RestController
@RequestMapping("/finance/reconciliation-tasks")
public class ReconciliationTaskController {

  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(ReconciliationTaskController.class);

  private final AccessService access;
  private final ReconciliationTaskService tasks;
  private final BankReconciliationPort bankPort;

  public ReconciliationTaskController(
      AccessService access, ReconciliationTaskService tasks, BankReconciliationPort bankPort) {
    this.access = access;
    this.tasks = tasks;
    this.bankPort = bankPort;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "finance.receivable." + permission);
    return access.run(actor, "finance.receivable." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /** 能力声明：哪些能力真接通、哪些还没交付、归谁。 */
  @GetMapping("/capabilities")
  public FinanceReconCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> tasks.capabilities());
  }

  // ================================================================ 筛选项

  /** 筛选项下拉（受控词表 + 12.7.1.1 的真实账户）。 */
  @GetMapping("/options")
  public FinanceReconOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> tasks.options());
  }

  // ================================================================ 对账任务

  /** 对账任务列表（真分页 + 真筛选 + 真搜索）。 */
  @GetMapping
  public FinanceReconTaskPage tasks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID accountId,
      @RequestParam(required = false) UUID shopId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth, "read", actor -> tasks.listTasks(accountId, shopId, status, keyword, page, size));
  }

  /**
   * 建对账任务（选择对账范围：店铺 + 周期 + 账户）。
   *
   * <p>⚠️ 账户必须存在且启用中；币种与法人**继承账户**；店铺必须**在授权范围内**。
   */
  @PostMapping
  public FinanceReconTaskRow createTask(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceReconTaskCreateInput input) {
    return call(auth, "update", actor -> tasks.createTask(input, auth, actor));
  }

  /** 对账任务详情（任务 + 按币种分组的汇总）。 */
  @GetMapping("/{id}")
  public FinanceReconTaskDetail task(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> tasks.detail(id));
  }

  /** 改对账范围（**只限 DRAFT 态**）。 */
  @PutMapping("/{id}")
  public FinanceReconTaskRow updateTask(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody FinanceReconTaskUpdateInput input) {
    return call(auth, "update", actor -> tasks.updateTask(id, input, auth, actor));
  }

  /**
   * 跑自动匹配（输出匹配结果 + 差异清单）。
   *
   * <p>⚠️ <b>两步执行</b>：① 先在**短事务**里读出对账范围；
   * ② 再经**授权只读 API** 取 12.7.1.2 的银行流水 —— 这一步**刻意在本模块事务之外**做，
   * 否则「自己 HTTP 调自己」会在本模块事务里再占一条连接（连接池只有 1 条时直接死锁）；
   * ③ 最后在一个事务里落库。
   */
  @PostMapping("/{id}/match")
  public FinanceReconMatchRunResult runMatch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    try {
      return runMatchInternal(auth, id);
    } catch (RuntimeException e) {
      LOG.error("对账任务 {} 跑匹配失败：{}", id, e.getMessage(), e);
      throw e;
    }
  }

  private FinanceReconMatchRunResult runMatchInternal(String auth, UUID id) {
    var actor = access.require(auth, "finance.receivable.update");

    var scope = access.run(actor, "finance.receivable.update", () -> tasks.scopeOf(id));

    var bankLines =
        scope.bookSources().contains("BANK_STATEMENT")
            ? bankPort.statementLines(
                auth, scope.accountId(), scope.periodStart(), scope.periodEnd())
            : List.<BankReconciliationPort.BankStatementLine>of();

    return access.run(
        actor,
        "finance.receivable.update",
        () -> tasks.runMatch(id, scope, bankLines, actor));
  }

  /** 匹配结果（真分页 + 真筛选：形态 / 我方账来源）。 */
  @GetMapping("/{id}/matches")
  public FinanceReconMatchPage matches(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) String shape,
      @RequestParam(required = false) String bookSource,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "read", actor -> tasks.matches(id, shape, bookSource, page, size));
  }

  /** 差异清单（真分页 + 真筛选：类型 / 币种 / 侧）。 */
  @GetMapping("/{id}/differences")
  public FinanceReconDifferencePage differences(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) String differenceType,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String side,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth, "read", actor -> tasks.differences(id, differenceType, currency, side, page, size));
  }

  /** 事件流（真分页；append-only 举证）。 */
  @GetMapping("/{id}/events")
  public FinanceReconEventPage events(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "read", actor -> tasks.events(id, page, size));
  }
}
