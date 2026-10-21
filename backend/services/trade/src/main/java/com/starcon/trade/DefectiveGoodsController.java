package com.starcon.trade;

import static com.starcon.trade.DefectiveGoodsModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
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
 * 6.4.2.2 不良品处理。
 *
 * <p>命名空间 {@code /trade/defective-dispositions}：后端命名空间<b>按服务走</b>
 * （与 6.4.2.1 的 {@code /trade/quality-inspections}、6.2.1.3 的
 * {@code /trade/purchase-plans} 同一纪律）。前端路由是 {@code /purchase/quality/defectives} ——
 * 那是<b>前端</b>路由，与后端命名空间是两件事。
 *
 * <p>⚠️ 与 6.4.2.1 质检单、11.x 退货质检是**三个模块**：本模块处置的是「来料/成品质检判出的
 * 不合格品」，表、端点、权限码、DTO 前缀全部分开
 * （{@code defective_*} vs {@code quality_inspection_*} vs {@code return_quality_*}）。
 *
 * <p><b>权限码</b>：{@code trade.defective-disposition.read} /
 * {@code trade.defective-disposition.update}；<b>让步接收另需</b>
 * {@code trade.defective-disposition.concession} —— 这一档比「质检结论录入」
 * （{@code trade.quality-inspection.update}）<b>高</b>，质检员不能随手放行
 * （蓝图加粗句「让步接收需要权限与原因，不能由质检员随手放行」）。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/trade/defective-dispositions")
public class DefectiveGoodsController {

  private final AccessService access;
  private final DefectiveGoodsService service;

  public DefectiveGoodsController(AccessService access, DefectiveGoodsService service) {
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
   * <p>存在的理由：本模块横跨两个**没有交付**的下游（7 库存的写入口、12 财务），
   * 而这两件事恰恰是处置结论的落点 —— 哪一个接通了必须由接口自己报出，
   * 而不是让界面显示「已登记」而其实什么都没发生。
   */
  @GetMapping("/capabilities")
  public DefectiveGoodsCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "defective-disposition.read", a -> service.capabilities());
  }

  // ================================================================ 下拉

  /** 建单需要的下拉与词表（可处置的质检单 = 已完结且仍有不合格量）。 */
  @GetMapping("/options")
  public DefectiveOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "defective-disposition.read", a -> service.options());
  }

  // ================================================================ 处置单

  /** 处置单列表（真分页 + 真筛选 + 真排序）。 */
  @GetMapping
  public DefectiveOrderPage orders(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String dispositionType,
      @RequestParam(required = false) String inspectionNo,
      @RequestParam(required = false) String skuCode,
      @RequestParam(required = false) UUID supplierId,
      @RequestParam(required = false, defaultValue = "created") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "defective-disposition.read",
        a ->
            service.pageOrders(
                keyword, status, dispositionType, inspectionNo, skuCode, supplierId, sort, page, size));
  }

  /** 汇总视图。 */
  @GetMapping("/summary")
  public DefectiveSummary summary(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String currencyCode) {
    return call(auth, "defective-disposition.read", a -> service.summary(currencyCode));
  }

  /**
   * 建处置单。
   *
   * <p>入参里**没有处置总量**：所有数量都从 6.4.2.1 质检单回读，
   * 处置总量由明细行求和重算。让操作员再敲一遍数字等于让同一个结论有两份。
   */
  @PostMapping
  public DefectiveOrderRow createOrder(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody DefectiveOrderInput input) {
    return call(
        auth,
        "defective-disposition.update",
        a -> service.createOrder(input, a.userId(), a.permissions()));
  }

  /** 处置单详情：头 + 明细 + 库存指令 + 索赔 + 事件台账。 */
  @GetMapping("/{id}")
  public DefectiveOrderDetail order(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "defective-disposition.read", a -> service.orderDetail(id));
  }

  /** 改处置明细（只有草稿能改）。 */
  @PutMapping("/{id}")
  public DefectiveOrderRow updateOrder(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody DefectiveOrderInput input) {
    return call(
        auth,
        "defective-disposition.update",
        a -> service.updateOrder(id, input, a.userId(), a.permissions()));
  }

  /** 决策定稿：生成库存状态变更指令与供应商索赔记录。 */
  @PostMapping("/{id}/decide")
  public DefectiveOrderRow decide(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "defective-disposition.update", a -> service.decide(id, a.userId()));
  }

  /**
   * 把库存状态变更指令真正送到 warehouse。
   *
   * <p>对端没有写端点时，指令状态如实落 {@code UNAVAILABLE} 并写明缺哪个端点 ——
   * 绝不出现「已登记」却其实没动库存。
   */
  @PostMapping("/{id}/dispatch-inventory")
  public DefectiveOrderRow dispatchInventory(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(
        auth,
        "defective-disposition.update",
        a -> service.dispatchInventory(id, a.userId(), auth));
  }

  /** 完成处置单。 */
  @PostMapping("/{id}/complete")
  public DefectiveOrderRow complete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "defective-disposition.update", a -> service.complete(id, a.userId()));
  }

  /** 作废处置单。理由必填。 */
  @PostMapping("/{id}/cancel")
  public DefectiveOrderRow cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody DefectiveCancelInput input) {
    return call(auth, "defective-disposition.update", a -> service.cancel(id, input, a.userId()));
  }

  // ================================================================ 供应商索赔

  /** 提交索赔。 */
  @PostMapping("/{id}/claims/{claimId}/submit")
  public DefectiveClaimRow submitClaim(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID claimId) {
    return call(auth, "defective-disposition.update", a -> service.submitClaim(id, claimId, a.userId()));
  }

  /** 供应商认可赔付金额。 */
  @PostMapping("/{id}/claims/{claimId}/agree")
  public DefectiveClaimRow agreeClaim(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID claimId,
      @Valid @RequestBody DefectiveClaimAgreeInput input) {
    return call(
        auth, "defective-disposition.update", a -> service.agreeClaim(id, claimId, input, a.userId()));
  }

  /** 供应商拒赔。理由必填。 */
  @PostMapping("/{id}/claims/{claimId}/reject")
  public DefectiveClaimRow rejectClaim(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID claimId,
      @Valid @RequestBody DefectiveClaimReasonInput input) {
    return call(
        auth, "defective-disposition.update", a -> service.rejectClaim(id, claimId, input, a.userId()));
  }

  /** 撤回索赔。理由必填。 */
  @PostMapping("/{id}/claims/{claimId}/withdraw")
  public DefectiveClaimRow withdrawClaim(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID claimId,
      @Valid @RequestBody DefectiveClaimReasonInput input) {
    return call(
        auth, "defective-disposition.update", a -> service.withdrawClaim(id, claimId, input, a.userId()));
  }

  // ================================================================ 导出

  /**
   * 导出 CSV 给采购 / 供应商对账用。
   *
   * <p>真导出：处置单 + 明细 + 库存指令 + 索赔 + 事件台账，五段都在文件里 ——
   * 只导出处置量会把「凭什么让步、货到底动没动、供应商认不认」丢掉，
   * 而那正是争议时最需要的那几行。
   */
  @GetMapping(value = "/{id}/export", produces = "text/csv")
  public ResponseEntity<byte[]> export(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    byte[] bytes = call(auth, "defective-disposition.read", a -> service.exportCsv(id));
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"defective-disposition-" + id + ".csv\"")
        .body(bytes);
  }
}
