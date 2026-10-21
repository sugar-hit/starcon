package com.starcon.trade;

import static com.starcon.trade.PurchaseDemandMergeModels.*;

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
 * 6.2.2.2 需求合并与批量。
 *
 * <p>命名空间 {@code /trade/demand-merges}：后端命名空间<b>按服务走</b>（与 6.2.2.1 的
 * {@code /trade/purchase-requests}、6.2.1.3 的 {@code /trade/purchase-plans} 同一纪律）。
 * 前端路由是 {@code /purchase/demand/merge} —— 那是<b>前端</b>路由，与后端命名空间是两件事。
 *
 * <p>权限码沿用 {@code trade.<page>.<action>}：{@code trade.demand-merge.read} /
 * {@code trade.demand-merge.update}。本车道<b>不改 identity 的权限真相源</b>
 * （那是另一个服务的迁移版本号），而是沿用 trade 服务既有的权限码体系。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p><b>🔴 本控制器没有「直接下单 / 提交采购单」端点</b>：6.3.1.2 采购单<b>未交付</b>，
 * 本模块只产出<b>采购单草稿</b>。本地造一张采购单等于凭空多一个对供应商的承诺。
 */
@RestController
@RequestMapping("/trade/demand-merges")
public class PurchaseDemandMergeController {

  private final AccessService access;
  private final PurchaseDemandMergeService service;

  public PurchaseDemandMergeController(AccessService access, PurchaseDemandMergeService service) {
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
   * <p>🔴 采购单（6.3.1.2）**未交付** ⇒ 本模块只产出**采购单草稿**，不产出采购单。
   */
  @GetMapping("/capabilities")
  public PurchaseDemandMergeCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "demand-merge.read", a -> service.capabilities());
  }

  // ================================================================ 参照下拉

  /** 合并要用的供应商 / 目标仓 / 法人下拉项。 */
  @GetMapping("/options")
  public PurchaseDemandMergeOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "demand-merge.read", a -> service.options(auth));
  }

  // ================================================================ 合并批次

  /** 合并批次列表（真分页 + 真筛选 + 真排序）。 */
  @GetMapping
  public PurchaseDemandMergeBatchPage batches(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "NEWEST") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth, "demand-merge.read", a -> service.pageBatches(status, keyword, sort, page, size));
  }

  /**
   * 批量生成：把多行需求按供应商合并成采购单**草稿**。
   *
   * <p>🔴 合并只在**同一供应商内进行**；跨供应商的行进「未合并行」并写明原因。
   */
  @PostMapping
  public PurchaseDemandMergeDetail merge(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PurchaseDemandMergeCreateInput input) {
    return call(auth, "demand-merge.update", a -> service.merge(input, a.userId(), auth));
  }

  /** 合并批次详情：批次 + 草稿 + 行 + 来源 + 未合并行 + 批量修改留痕。 */
  @GetMapping("/{id}")
  public PurchaseDemandMergeDetail batch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "demand-merge.read", a -> service.batchDetail(id));
  }

  // ================================================================ 草稿与行

  /** 草稿清单（真分页 + 真筛选）。 */
  @GetMapping("/{id}/drafts")
  public PurchaseDemandMergeDraftPage drafts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) UUID supplierId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth, "demand-merge.read", a -> service.pageDrafts(id, supplierId, page, size));
  }

  /** 草稿详情：草稿 + 行 + 行来源。 */
  @GetMapping("/{id}/drafts/{draftId}")
  public PurchaseDemandMergeDraftDetail draft(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID draftId) {
    return call(auth, "demand-merge.read", a -> service.draftDetail(id, draftId));
  }

  /** 草稿行清单（真分页 + 真筛选）。 */
  @GetMapping("/{id}/lines")
  public PurchaseDemandMergeLinePage lines(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) UUID draftId,
      @RequestParam(required = false) UUID supplierId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "demand-merge.read",
        a -> service.pageLines(id, draftId, supplierId, keyword, page, size));
  }

  /** 未合并行清单（真分页 + 真筛选）—— 每一行都带**明确原因**。 */
  @GetMapping("/{id}/unmerged")
  public PurchaseDemandMergeUnmergedPage unmerged(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) String reasonCode,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth, "demand-merge.read", a -> service.pageUnmerged(id, reasonCode, page, size));
  }

  // ================================================================ 批量动作

  /**
   * 批量改数量。
   *
   * <p>🔴 <b>整体生效或整体拒绝</b>：任一行不合法 ⇒ 整批回滚，不留「改了一半」的中间态。
   */
  @PostMapping("/{id}/quantity")
  public PurchaseDemandMergeDetail adjustQuantity(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody PurchaseDemandBatchQuantityInput input) {
    return call(
        auth, "demand-merge.update", a -> service.adjustQuantity(id, input, a.userId()));
  }

  /**
   * 批量改供应商。
   *
   * <p>🔴 改完之后<b>重新校验分组</b>：每张草稿的供应商必须唯一，不满足 ⇒ 整批拒绝。
   */
  @PostMapping("/{id}/supplier")
  public PurchaseDemandMergeDetail adjustSupplier(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody PurchaseDemandBatchSupplierInput input) {
    return call(
        auth, "demand-merge.update", a -> service.adjustSupplier(id, input, a.userId(), auth));
  }

  /** 定稿（OPEN → FINALIZED）：内容冻结，6.3.1.2 才能读这份草稿去建真正的采购单。 */
  @PostMapping("/{id}/finalize")
  public PurchaseDemandMergeBatchRow finalizeBatch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "demand-merge.update", a -> service.finalizeBatch(id, a.userId()));
  }

  /** 批量打印：把选中的采购单草稿渲染成可打印的文本。 */
  @PostMapping("/print")
  public PurchaseDemandPrintResult print(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PurchaseDemandPrintInput input) {
    return call(auth, "demand-merge.read", a -> service.print(input.draftIds()));
  }
}
