package com.starcon.finance;

import static com.starcon.finance.ReceivableModels.*;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
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
 * 12.1.1.1 应收单管理的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /finance/receivables}</b>：与卷 12 的其余模块（12.2 收付款 / 12.3 结算 / 12.5 总账 …）刻意不共用一个前缀
 * —— 那些模块还没交付，等它们落地时各自有自己的前缀， 挤在同一个前缀下会让「哪个模块的哪个端点」变得说不清。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限， 再在一个事务里绑定 {@code
 * app.current_tenant}，由 RLS 在数据库层强制隔离。 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码 {@code finance.receivable.read / .create / .update}： 确认应收、改核销状态、改确认政策都不是读权限能带过去的动作。
 */
@RestController
@RequestMapping("/finance/receivables")
public class ReceivableController {

  private final AccessService access;
  private final ReceivableService receivables;

  public ReceivableController(AccessService access, ReceivableService receivables) {
    this.access = access;
    this.receivables = receivables;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "finance.receivable." + permission);
    return access.run(actor, "finance.receivable." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /** 能力声明：哪些上游真接通、哪些还没交付、归谁。 */
  @GetMapping("/capabilities")
  public FinanceReceivableCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> receivables.capabilities());
  }

  // ================================================================ 筛选项

  /** 筛选项下拉（全部来自真实数据）。 */
  @GetMapping("/options")
  public FinanceReceivableOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> receivables.options());
  }

  // ================================================================ 列表 / 详情

  /** 应收单列表：真分页 + 真筛选 + 真排序 + 汇总（汇总覆盖整个筛选结果集，不是当前页）。 */
  @GetMapping
  public FinanceReceivablePage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String source,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) UUID counterpartyRef,
      @RequestParam(required = false) UUID shopId,
      @RequestParam(required = false) Boolean disputeOnly,
      @RequestParam(required = false) Boolean overdueOnly,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate dueFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate dueTo,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        actor ->
            receivables.page(
                new ReceivableQuery(
                    enumOf(status, ReceivableStatus.class, "应收状态"),
                    enumOf(source, ReceivableSource.class, "应收来源"),
                    currency,
                    counterpartyRef,
                    shopId,
                    disputeOnly,
                    overdueOnly,
                    dueFrom,
                    dueTo,
                    keyword,
                    page,
                    size)));
  }

  /** 应收单详情：单头 + 明细行 + 事件流 + 催收任务。 */
  @GetMapping("/{id}")
  public FinanceReceivableDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> receivables.detail(id));
  }

  // ================================================================ 建单

  /**
   * 人工录入一张应收单（建单 + 确认在同一个事务里完成）。
   *
   * <p>⚠️ 只有 MANUAL / OTHER 能手工建单；B2B 账期应收只能由发货确认导入。
   */
  @PostMapping
  public FinanceReceivableRow create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceReceivableInput input) {
    return call(auth, "create", actor -> receivables.create(input, actor));
  }

  /**
   * 从上游发货确认导入账期应收（幂等）。
   *
   * <p>⚠️ 上游读不到时**整体失败**（503），绝不返回「导入 0 条」冒充「上游没有数据」。
   */
  @PostMapping("/import-b2b")
  public FinanceReceivableImportResult importFromShipmentConfirmations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "create", actor -> receivables.importFromShipmentConfirmations(auth, actor));
  }

  // ================================================================ 作废 / 争议

  /** 作废（红冲的等价物）：终态，整单冻结。已核销的不可作废。 */
  @PostMapping("/{id}/void")
  public FinanceReceivableRow voidReceivable(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody FinanceReceivableVoidInput input) {
    return call(auth, "update", actor -> receivables.voidReceivable(id, input, actor));
  }

  /** 标注 / 取消「争议中」（争议事实来自 11.4.1.1，当前只支持人工标注）。 */
  @PostMapping("/{id}/dispute")
  public FinanceReceivableRow flagDispute(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody FinanceReceivableDisputeInput input) {
    return call(auth, "update", actor -> receivables.flagDispute(id, input, actor));
  }

  // ================================================================ 账龄

  /** 账龄分布：五档 × 币种，争议中金额单列。 */
  @GetMapping("/aging")
  public FinanceReceivableAging aging(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> receivables.aging());
  }

  // ================================================================ 催收任务

  /** 催收任务列表：真分页 + 真筛选。 */
  @GetMapping("/collection-tasks")
  public FinanceCollectionTaskPage collectionTasks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String level,
      @RequestParam(required = false) UUID receivableId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        actor ->
            receivables.collectionTasks(
                new CollectionTaskQuery(
                    enumOf(status, CollectionTaskStatus.class, "催收任务状态"),
                    enumOf(level, CollectionTaskLevel.class, "催收级别"),
                    receivableId,
                    page,
                    size)));
  }

  /** 给一张应收单建催收任务。 */
  @PostMapping("/{id}/collection-tasks")
  public FinanceCollectionTaskRow createCollectionTask(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody FinanceCollectionTaskInput input) {
    return call(auth, "update", actor -> receivables.createCollectionTask(id, input, actor));
  }

  /** 批量生成逾期催收任务（幂等：已有同级别任务的不重复建）。 */
  @PostMapping("/collection-tasks/generate")
  public java.util.List<FinanceCollectionTaskRow> generateOverdueTasks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "update", actor -> receivables.generateOverdueTasks(actor));
  }

  /** 关闭催收任务。 */
  @PostMapping("/collection-tasks/{taskId}/close")
  public FinanceCollectionTaskRow closeCollectionTask(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID taskId,
      @RequestBody(required = false) FinanceCollectionTaskCloseInput input) {
    return call(auth, "update", actor -> receivables.closeCollectionTask(taskId, input, actor));
  }

  // ================================================================ 应收确认政策

  /** 应收确认政策：五个来源全列（含未配置的）。 */
  @GetMapping("/policies")
  public java.util.List<FinanceReceivablePolicyView> policies(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> receivables.policies());
  }

  /**
   * 配置一个来源的确认政策（幂等 upsert）。
   *
   * <p>这是「什么业务确认为应收由租户决定」的操作入口： 一个来源没有启用的政策 ⇒ 这个来源一张应收都产生不了。
   */
  @PutMapping("/policies/{source}")
  public FinanceReceivablePolicyRow updatePolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String source,
      @RequestBody FinanceReceivablePolicyInput input) {
    var parsed = enumOf(source, ReceivableSource.class, "应收来源");
    return call(auth, "update", actor -> receivables.updatePolicy(parsed, input, actor));
  }

  /** 词表解析：非法取值给一句能直接展示的话，而不是一个枚举名。 */
  private static <T extends Enum<T>> T enumOf(String raw, Class<T> type, String label) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return Enum.valueOf(type, raw.trim());
    } catch (IllegalArgumentException e) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, label + "取值不合法：" + raw);
    }
  }
}
