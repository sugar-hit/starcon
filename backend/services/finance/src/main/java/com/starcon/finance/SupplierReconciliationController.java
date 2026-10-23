package com.starcon.finance;

import static com.starcon.finance.SupplierReconciliationModels.*;

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
 * 12.1.2.3 供应商对账（财务侧）的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /finance/supplier-reconciliations}</b>：与 12.1.2.1 的
 * {@code /finance/payables}、12.1.2.2 的 {@code /finance/payment-plans} 各自独立前缀 ——
 * 「哪个模块的哪个端点」必须说得清。
 *
 * <p><b>🔴 三个权限码就是本模块的职责分离</b>：
 *
 * <ul>
 *   <li>{@code finance.suppliereconciliation.read} —— 看（列表 / 详情 / 明细 / 差异 / 能力声明）。
 *   <li>{@code finance.suppliereconciliation.update} —— <b>采购侧也能做</b>的动作：
 *       导入采购侧对账单、核对收货与质检、登记差异说明。
 *   <li>{@code finance.suppliereconciliation.confirm} —— <b>财务侧专属</b>的动作：
 *       裁决差异、<b>最终确认并生成应付</b>、驳回。
 * </ul>
 *
 * <p>蓝图 12.1.2.3 加粗句「<b>财务是最终确认方，采购不能自行确认应付</b>——这是职责分离」
 * 在这里的落点就是：<b>只有 {@code confirm} 权限能走到「生成应付」那一步</b>。
 * 采购侧拿着 {@code update} 权限能导入、能核对、能登记说明，但**裁决与确认都做不了**。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/finance/supplier-reconciliation")
public class SupplierReconciliationController {

  private final AccessService access;
  private final SupplierReconciliationService reconciliations;

  public SupplierReconciliationController(
      AccessService access, SupplierReconciliationService reconciliations) {
    this.access = access;
    this.reconciliations = reconciliations;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "finance.suppliereconciliation." + permission);
    return access.run(actor, "finance.suppliereconciliation." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明 / 下拉

  /** 能力声明：哪些上游真接通、职责分离怎么落、哪些还没交付、归谁。 */
  @GetMapping("/capabilities")
  public FinanceSupplierReconciliationCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> reconciliations.capabilities());
  }

  /** 筛选项下拉（全部来自真实数据）。 */
  @GetMapping("/options")
  public FinanceSupplierReconciliationOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> reconciliations.options());
  }

  // ================================================================ 列表 / 详情

  /** 对账单列表：真分页 + 真筛选 + 真排序 + 按币种分开的汇总。 */
  @GetMapping
  public FinanceSupplierReconciliationPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String upstreamStatus,
      @RequestParam(required = false) UUID supplierRefId,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        actor ->
            reconciliations.page(
                status, upstreamStatus, supplierRefId, currency, keyword, page, size));
  }

  /** 对账单详情：头 + 明细行 + 质检证据 + 差异 + 说明 + 事件流 + 应付依据。 */
  @GetMapping("/{id}")
  public FinanceSupplierReconciliationDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> reconciliations.detail(id));
  }

  /** 明细行列表（真分页 + 行类型筛选）。 */
  @GetMapping("/{id}/lines")
  public FinanceSupplierReconciliationLinePage lines(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) String lineType,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(auth, "read", actor -> reconciliations.lines(id, lineType, page, size));
  }

  /** 差异清单（真分页 + 状态筛选）。 */
  @GetMapping("/{id}/differences")
  public FinanceSupplierReconciliationDifferencePage differences(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "read", actor -> reconciliations.differences(id, status, page, size));
  }

  // ================================================================ 采购发起侧的动作（update 权限）

  /**
   * 从上游 **6.5.1.1 采购对账**导入对账单（幂等）。
   *
   * <p>⚠️ 上游读不到时**整体失败**（503），绝不返回「导入 0 条」冒充「上游没有对账单」。
   */
  @PostMapping("/import")
  public FinanceSupplierReconciliationImportResult importStatements(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "update", actor -> reconciliations.importFromPurchase(auth, actor));
  }

  /**
   * 核对收货与质检，产出差异清单。
   *
   * <p>⚠️ 它**只核对，不确认**：核对完的状态是「已核对 / 有差异待裁决」，
   * 还不是应付。
   */
  @PostMapping("/{id}/match")
  public FinanceSupplierReconciliationMatchResult match(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "update", actor -> reconciliations.match(id, auth, actor));
  }

  /** 登记一条差异说明（采购侧 / 供应商给出）。**不裁决金额** —— 裁决是财务侧动作。 */
  @PostMapping("/{id}/differences/{differenceId}/explanations")
  public FinanceSupplierReconciliationDetail addExplanation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID differenceId,
      @RequestBody FinanceSupplierReconciliationExplanationInput input) {
    return call(
        auth, "update", actor -> reconciliations.addExplanation(id, differenceId, input, actor));
  }

  // ================================================================ 财务侧的动作（confirm 权限）

  /**
   * 裁决一条差异（**财务侧专属**）。
   *
   * <p>APPLIED = 按财务口径调整应付；KEPT = 沿用采购侧口径。
   */
  @PostMapping("/{id}/differences/{differenceId}/resolve")
  public FinanceSupplierReconciliationDetail resolveDifference(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID differenceId,
      @RequestBody FinanceSupplierReconciliationResolveInput input) {
    return call(
        auth, "confirm", actor -> reconciliations.resolveDifference(id, differenceId, input, actor));
  }

  /**
   * **财务最终确认**：裁决完差异 ⇒ 生成应付依据 ⇒ 落一张应付单（12.1.2.1）。
   *
   * <p>⚠️ 这是本模块<b>唯一</b>能产生应付的入口 —— 采购侧不能自行确认应付。
   */
  @PostMapping("/{id}/confirm")
  public FinanceSupplierReconciliationDetail confirm(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "confirm", actor -> reconciliations.confirm(id, actor));
  }

  /** 财务驳回（终态，整单冻结）。理由必填。 */
  @PostMapping("/{id}/reject")
  public FinanceSupplierReconciliationDetail reject(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody FinanceSupplierReconciliationRejectInput input) {
    return call(auth, "confirm", actor -> reconciliations.reject(id, input, actor));
  }
}
