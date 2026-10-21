package com.starcon.trade;

import static com.starcon.trade.PurchaseRequestModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.2.2.1 采购申请。
 *
 * <p>命名空间 {@code /trade/purchase-requests}：后端命名空间<b>按服务走</b>（与 6.2.1.3 的
 * {@code /trade/purchase-plans}、6.2.1.2 的 {@code /trade/replenishment-suggestions} 同一纪律）。
 * 前端路由是 {@code /purchase/demand/requests} —— 那是<b>前端</b>路由，与后端命名空间是两件事。
 *
 * <p>权限码沿用 {@code trade.<page>.<action>}：{@code trade.purchase-request.read} /
 * {@code trade.purchase-request.update}。本车道<b>不改 identity 的权限真相源</b>
 * （那是另一个服务的迁移版本号），而是沿用 trade 服务既有的权限码体系。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p><b>🔴 本控制器没有「批准 / 驳回」端点</b>：审批结论由 17 组织（identity）裁定，
 * 本地批准会把审批流本地化 —— identity 那边什么都不知道，还留一条没人管的待办。
 */
@RestController
@RequestMapping("/trade/purchase-requests")
public class PurchaseRequestController {

  private final AccessService access;
  private final PurchaseRequestService service;

  public PurchaseRequestController(AccessService access, PurchaseRequestService service) {
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
   * <p>🔴 采购单（6.3.1.2）**未交付** ⇒ 本模块只产出**转单事实**，不产出采购单。
   */
  @GetMapping("/capabilities")
  public PurchaseRequestCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "purchase-request.read", a -> service.capabilities());
  }

  // ================================================================ 参照下拉

  /** 建申请单需要的供应商 / 目标仓 / 法人下拉项。 */
  @GetMapping("/options")
  public PurchaseRequestOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "purchase-request.read", a -> service.options(auth));
  }

  // ================================================================ 申请单

  /** 采购申请单列表（真分页 + 真筛选 + 真排序）。 */
  @GetMapping
  public PurchaseRequestPage requests(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String sourceType,
      @RequestParam(required = false) UUID sourcePlanId,
      @RequestParam(required = false) UUID targetWarehouseId,
      @RequestParam(required = false) UUID supplierId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "NEWEST") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "purchase-request.read",
        a ->
            service.pageRequests(
                status, sourceType, sourcePlanId, targetWarehouseId, supplierId, keyword, sort, page,
                size));
  }

  /**
   * 建一张采购申请单（草稿）。
   *
   * <p>来源三种：{@code PLAN}（6.2.1.3 计划行）、{@code SUGGESTION}（6.2.1.2 补货建议）、
   * {@code MANUAL}（手工建单）。
   */
  @PostMapping
  public PurchaseRequestRow create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PurchaseRequestCreateInput input) {
    return call(
        auth, "purchase-request.update", a -> service.create(input, a.userId(), auth));
  }

  /**
   * 申请单详情：头 + 行 + 调整留痕 + 转单事实。
   *
   * @param refreshApproval true 时顺带拉一次审批状态（拉取式同步）
   */
  @GetMapping("/{id}")
  public PurchaseRequestDetail request(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false, defaultValue = "false") boolean refreshApproval) {
    return call(
        auth, "purchase-request.read", a -> service.requestDetail(id, refreshApproval, auth));
  }

  /** 更新草稿（法人 / 币种 / 备注）。已提交的申请单不可改。 */
  @PutMapping("/{id}")
  public PurchaseRequestRow update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody PurchaseRequestUpdateInput input) {
    return call(
        auth, "purchase-request.update", a -> service.updateDraft(id, input, a.userId(), auth));
  }

  // ================================================================ 申请行

  /** 申请行列表（真分页 + 真筛选）。 */
  @GetMapping("/{id}/lines")
  public PurchaseRequestLinePage lines(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) String skuCode,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) UUID supplierId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "purchase-request.read",
        a -> service.pageLines(id, skuCode, keyword, supplierId, page, size));
  }

  /** 给草稿加一行。 */
  @PostMapping("/{id}/lines")
  public PurchaseRequestLineRow addLine(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody PurchaseRequestLineInput input) {
    return call(
        auth, "purchase-request.update", a -> service.addLine(id, input, a.userId(), auth));
  }

  /** 调整一行的数量 / 交期 / 单价 / 优先级，理由必填（每次改动都留痕）。 */
  @PostMapping("/{id}/lines/{lineId}/adjust")
  public PurchaseRequestLineRow adjust(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID lineId,
      @Valid @RequestBody PurchaseRequestLineAdjustInput input) {
    return call(
        auth,
        "purchase-request.update",
        a -> service.adjustLine(id, lineId, input, a.userId()));
  }

  /** 给一行选供应商（供应商必须真的存在于 catalog）。 */
  @PostMapping("/{id}/lines/{lineId}/supplier")
  public PurchaseRequestLineRow selectSupplier(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID lineId,
      @Valid @RequestBody PurchaseRequestSupplierInput input) {
    return call(
        auth,
        "purchase-request.update",
        a -> service.selectSupplier(id, lineId, input, a.userId(), auth));
  }

  // ================================================================ 审批

  /**
   * 提交审批（草稿 → 待审 / 已批）。
   *
   * <p>🔴 免审直通进已批；需审则停在待审；**审批服务不可达 ⇒ 停在待审并把失败原因写进
   * {@code approval_sync_error}**，绝不自动跳到已批。
   */
  @PostMapping("/{id}/submit")
  public PurchaseRequestRow submit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody(required = false) PurchaseRequestSubmitInput input) {
    var result = call(auth, "purchase-request.update", a -> service.submit(id, a.userId(), auth));
    // 🔴 翻状态码发生在**事务提交之后**：服务层先记下「审批同步失败」这条痕迹再返回，
    //    事务提交了痕迹才落库；这里再翻成 503/409，页面拿到的申请单仍是「停在待审」。
    if (result.failed()) {
      throw new ResponseStatusException(
          HttpStatusCode.valueOf(result.errorStatus()), result.message());
    }
    return result.request();
  }

  /**
   * 拉取审批结论（拉取式同步：本仓还没有事件总线）。
   *
   * <p>读详情时也可以顺带刷（{@code ?refreshApproval=true}）。
   */
  @PostMapping("/{id}/approval/refresh")
  public PurchaseRequestRow refreshApproval(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    var result =
        call(auth, "purchase-request.update", a -> service.refreshApproval(id, a.userId(), auth));
    if (result.failed()) {
      throw new ResponseStatusException(
          HttpStatusCode.valueOf(result.errorStatus()), result.message());
    }
    return result.request();
  }

  // ================================================================ 转单

  /**
   * 转单（已批 → 已转单）。
   *
   * <p>🔴 6.3.1.2 采购单**未交付** ⇒ 本端点**不建采购单**，只把转单参数
   * （供应商 + 数量 + 交期 + 目标仓）记成事实。每一行都必须先有供应商与交期。
   */
  @PostMapping("/{id}/convert")
  public PurchaseRequestDetail convert(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody(required = false) PurchaseRequestConvertInput input) {
    return call(
        auth,
        "purchase-request.update",
        a ->
            service.convert(
                id,
                input == null ? new PurchaseRequestConvertInput(null, null) : input,
                a.userId(),
                auth));
  }
}
