package com.starcon.finance;

import static com.starcon.finance.ReconciliationDifferenceModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 12.3.2.2 差异处理的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /finance/reconciliation-differences}</b>：与 12.3.2.1 对账任务
 * （{@code /finance/reconciliation-tasks}）、12.7.1.2 银行对账
 * （{@code /finance/bank-reconciliation}）、12.1.2.3 供应商对账
 * （{@code /finance/supplier-reconciliation}）**刻意不共用一个前缀** ——
 * 那些是别的模块的命名空间（端点、契约、页面都归它们所有）。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p><b>权限码沿用 12.3.2.1 / 12.7.1.2 的 {@code finance.receivable.read / .update}</b>：
 * 12 财务核算家族共用同一套词汇表，**刻意不新造权限词表**（新造一档要改 identity 的
 * 迁移 —— 那是另一个服务的写入所有权，且迁移版本号由调度者统一分配）。
 */
@RestController
@RequestMapping("/finance/reconciliation-differences")
public class ReconciliationDifferenceController {

  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(ReconciliationDifferenceController.class);

  private final AccessService access;
  private final ReconciliationDifferenceService differences;

  public ReconciliationDifferenceController(
      AccessService access, ReconciliationDifferenceService differences) {
    this.access = access;
    this.differences = differences;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "finance.receivable." + permission);
    return access.run(actor, "finance.receivable." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /** 能力声明：哪些能力真接通、哪些还没交付、归谁。 */
  @GetMapping("/capabilities")
  public ReconciliationDiffCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> differences.capabilities());
  }

  // ================================================================ 筛选项

  /** 筛选项下拉（受控词表 + 真实任务 / 币种 / 责任人）。 */
  @GetMapping("/options")
  public ReconciliationDiffOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> differences.options());
  }

  // ================================================================ 队列

  /**
   * 差异处理队列（真分页 + 真筛选 + 真搜索 + **按币种分组的汇总**）。
   *
   * <p>🔴 汇总**绝不跨币种合计**；跨币种差异的差额恒为 null（汇率不可得）。
   */
  @GetMapping
  public ReconciliationDiffQueuePage queue(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID taskId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) UUID assigneeId,
      @RequestParam(required = false) String differenceType,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "read",
        actor ->
            differences.queue(
                taskId, status, category, currency, assigneeId, differenceType, keyword, page, size));
  }

  // ================================================================ 单条差异

  /** 一条差异的处理详情（差异 + 处理记录 + 调整分录 + 时间线 + 可执行动作）。 */
  @GetMapping("/{id}")
  public ReconciliationDiffDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> differences.detail(id));
  }

  /** 处理时间线（真分页；append-only 举证）。 */
  @GetMapping("/{id}/events")
  public ReconciliationDiffEventPage events(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "read", actor -> differences.events(id, page, size));
  }

  // ================================================================ 处理动作

  /**
   * 认领这条差异（并指定责任人）。
   *
   * <p>⚠️ 不传 {@code assigneeId} = 认领人就是**操作者自己**（取自已认证会话）。
   */
  @PostMapping("/{id}/claim")
  public ReconciliationDiffHandling claim(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody ReconciliationDiffClaimInput input) {
    return call(auth, "update", actor -> differences.claim(id, input, actor));
  }

  /** 改派责任人（**只限已认领、且未闭环**）。 */
  @PostMapping("/{id}/assign")
  public ReconciliationDiffHandling assign(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody ReconciliationDiffAssignInput input) {
    return call(auth, "update", actor -> differences.assign(id, input, actor));
  }

  /** 分类归因（人工分类 + 理由；**理由必填**）。 */
  @PostMapping("/{id}/classify")
  public ReconciliationDiffHandling classify(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody ReconciliationDiffClassifyInput input) {
    return call(auth, "update", actor -> differences.classify(id, input, actor));
  }

  /** 登记调整分录（**凭证或说明**至少有一个）。 */
  @PostMapping("/{id}/adjustments")
  public ReconciliationDiffAdjustmentRow recordAdjustment(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody ReconciliationDiffAdjustmentInput input) {
    return call(auth, "update", actor -> differences.recordAdjustment(id, input, actor));
  }

  /** 人工判定：**已调整**（终态；至少要有一条调整分录）。 */
  @PostMapping("/{id}/adjust")
  public ReconciliationDiffHandling adjust(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody ReconciliationDiffDecideInput input) {
    return call(auth, "update", actor -> differences.adjust(id, input, actor));
  }

  /** 人工判定：**已核销**（终态；= 判定不需要调整）。 */
  @PostMapping("/{id}/write-off")
  public ReconciliationDiffHandling writeOff(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody ReconciliationDiffDecideInput input) {
    return call(auth, "update", actor -> differences.writeOff(id, input, actor));
  }
}
