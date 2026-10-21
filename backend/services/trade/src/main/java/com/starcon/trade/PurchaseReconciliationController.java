package com.starcon.trade;

import static com.starcon.trade.PurchaseReconciliationModels.*;

import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpHeaders;
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
 * 6.5.1.1 采购对账。
 *
 * <p>命名空间 {@code /trade/purchase-reconciliations}：后端命名空间<b>按服务走</b>（与 6.3.2.2 的
 * {@code /trade/purchase-arrivals}、6.2.1.3 的 {@code /trade/purchase-plans} 同一纪律）。
 * 前端路由是 {@code /purchase/reconciliation} —— 那是<b>前端</b>路由，与后端命名空间是两件事。
 *
 * <p>权限码沿用 {@code trade.<page>.<action>}：{@code trade.purchase-reconciliation.read} /
 * {@code trade.purchase-reconciliation.update}。本车道<b>不改 identity 的权限真相源</b>
 * （那是另一个服务的迁移版本号），而是沿用 trade 服务既有的权限码体系。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>⚠️ <b>本控制器刻意没有「确认应付 / 付款」端点</b>：蓝图加粗句「对账由采购发起、
 * 12 财务执行付款，两侧数据以对账单为交接点，避免采购擅自确认应付」。
 */
@RestController
@RequestMapping("/trade/purchase-reconciliations")
public class PurchaseReconciliationController {

  private final AccessService access;
  private final PurchaseReconciliationService service;

  public PurchaseReconciliationController(AccessService access, PurchaseReconciliationService service) {
    this.access = access;
    this.service = service;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "trade." + permission);
    return access.run(actor, "trade." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>存在的理由：对账基数在 6.3.2.2 与 11.2.3.3、质检扣量在 6.4.2.1（未交付）、
   * 应付付款在 12 财务（服务不存在）。哪一个接通了必须由接口自己报出，
   * 而不是让界面显示成空白让人以为「这些能力没做」。
   */
  @GetMapping("/capabilities")
  public ReconciliationCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "purchase-reconciliation.read", a -> service.capabilities());
  }

  /** 下拉项：供应商（取自已交付事实）、期间预设、币种、代扣代缴类型。 */
  @GetMapping("/options")
  public ReconciliationOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "purchase-reconciliation.read", a -> service.options());
  }

  // ================================================================ 列表

  /** 对账单列表（真分页 + 真筛选 + 真排序）。 */
  @GetMapping
  public ReconciliationPage reconciliations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID supplierRefId,
      @RequestParam(required = false) String periodType,
      @RequestParam(required = false) LocalDate periodStart,
      @RequestParam(required = false) LocalDate periodEnd,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "created_desc") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "purchase-reconciliation.read",
        a ->
            service.list(
                new PurchaseReconciliationListQuery(
                    page,
                    size,
                    status,
                    supplierRefId,
                    periodType,
                    periodStart,
                    periodEnd,
                    currency,
                    keyword,
                    sort)));
  }

  /**
   * 发起对账（按期间 + 供应商采集基数）。
   *
   * <p>⚠️ 跨币种必须给汇率快照：不给 ⇒ 400（绝不按 1:1 折算）。
   */
  @PostMapping
  public ReconciliationDetail create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PurchaseReconciliationCreate input) {
    return call(
        auth,
        "purchase-reconciliation.update",
        a -> service.create(input, a.userId()));
  }

  /** 对账单详情：头 + 明细 + 差异 + 回执 + 留痕 + 基数说明 + 可执行动作。 */
  @GetMapping("/{id}")
  public ReconciliationDetail reconciliation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "purchase-reconciliation.read", a -> service.detail(id));
  }

  /** 明细行列表（真分页 + 行类型筛选 + 按币种分桶的汇总）。 */
  @GetMapping("/{id}/lines")
  public ReconciliationLinePage lines(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) String lineType,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "purchase-reconciliation.read", a -> service.lines(id, lineType, page, size));
  }

  /** 登记一笔代扣代缴费用（仅草稿）。 */
  @PostMapping("/{id}/deductions")
  public ReconciliationDetail addDeduction(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody PurchaseReconciliationDeductionCreate input) {
    return call(
        auth,
        "purchase-reconciliation.update",
        a -> service.addDeduction(id, input, a.userId()));
  }

  /** 发出对账单给供应商确认（草稿 → 已发送）。 */
  @PostMapping("/{id}/send")
  public ReconciliationDetail send(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "purchase-reconciliation.update", a -> service.send(id, a.userId()));
  }

  /**
   * 录入供应商确认回执：逐行比对 → 逐条落差异 → 推进状态。
   *
   * <p>⚠️ 回执带**逐行**供应商值：只给一个总额就答不出「差在哪一行」。
   */
  @PostMapping("/{id}/confirmations")
  public ReconciliationDetail recordConfirmation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody PurchaseReconciliationConfirmationCreate input) {
    return call(
        auth,
        "purchase-reconciliation.update",
        a -> service.recordConfirmation(id, input, a.userId()));
  }

  /** 差异清单（真分页 + 状态筛选）。 */
  @GetMapping("/{id}/discrepancies")
  public ReconciliationDiscrepancyPage discrepancies(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "purchase-reconciliation.read",
        a -> service.discrepancies(id, status, page, size));
  }

  /** 追加一条差异（采购主动记的，不等供应商回执）。 */
  @PostMapping("/{id}/discrepancies")
  public ReconciliationDetail addDiscrepancy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody PurchaseReconciliationDiscrepancyCreate input) {
    return call(
        auth,
        "purchase-reconciliation.update",
        a -> service.addDiscrepancy(id, input, a.userId()));
  }

  /** 处理一条差异：挂起（HELD）或解决（RESOLVED）。 */
  @PostMapping("/{id}/discrepancies/{discrepancyId}/resolve")
  public ReconciliationDetail resolveDiscrepancy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID discrepancyId,
      @Valid @RequestBody PurchaseReconciliationDiscrepancyResolve input) {
    return call(
        auth,
        "purchase-reconciliation.update",
        a -> service.resolveDiscrepancy(id, discrepancyId, input, a.userId()));
  }

  /** 重开对账（追加差异）。 */
  @PostMapping("/{id}/reopen")
  public ReconciliationDetail reopen(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody PurchaseReconciliationReopen input) {
    return call(auth, "purchase-reconciliation.update", a -> service.reopen(id, input, a.userId()));
  }

  /** 关闭对账单（已确认 → 已关闭）。 */
  @PostMapping("/{id}/close")
  public ReconciliationDetail close(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "purchase-reconciliation.update", a -> service.close(id, a.userId()));
  }

  /**
   * 导出 CSV（发给供应商）。
   *
   * <p>真导出：内容就是这张对账单的**全部明细行** + 汇总 + 差异清单。
   * 质检扣量未接入的行在文件里也写明「未接入」—— 让供应商看到的和采购看到的是同一份口径。
   */
  @GetMapping(value = "/{id}/export", produces = "text/csv")
  public ResponseEntity<byte[]> export(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    byte[] bytes = call(auth, "purchase-reconciliation.read", a -> service.exportCsv(id));
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"purchase-reconciliation-" + id + ".csv\"")
        .body(bytes);
  }
}
