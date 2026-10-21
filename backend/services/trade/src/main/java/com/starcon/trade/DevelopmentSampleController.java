package com.starcon.trade;

import static com.starcon.trade.DevelopmentSampleModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * 6.6.1.2 打样与样品管理。
 *
 * <p>命名空间 {@code /trade/development-samples}：后端命名空间<b>按服务走</b>（与 6.6.1.1 的
 * {@code /trade/development-projects}、6.2.1.3 的 {@code /trade/purchase-plans} 同一纪律）。
 * 前端路由是 {@code /purchase/development/samples} —— 那是<b>前端</b>路由，与后端命名空间是两件事。
 *
 * <p>权限码沿用 {@code trade.<page>.<action>}：{@code trade.development-sample.read} /
 * {@code trade.development-sample.update}。本车道<b>不改 identity 的权限真相源</b>（那是另一个服务的
 * 迁移版本号），而是沿用 trade 服务既有的权限码体系。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p><b>🔴 状态不是「前端传什么就存什么」</b>：
 *
 * <ul>
 *   <li>打样单状态只能通过 {@code POST /{id}/transitions} 推进；</li>
 *   <li>样品的<b>制作进度</b>走 {@code POST /{id}/samples/{sampleId}/transitions}；</li>
 *   <li>样品的<b>确认结果</b>（通过 / 修改 / 放弃）走
 *       {@code POST /{id}/samples/{sampleId}/confirmations} ——
 *       它必须留一条<b>不可改写</b>的确认记录，不能当作进度直接推进。</li>
 * </ul>
 *
 * <p>服务层与数据库层各挡一次。
 */
@RestController
@RequestMapping("/trade/development-samples")
public class DevelopmentSampleController {

  private final AccessService access;
  private final DevelopmentSampleService service;

  public DevelopmentSampleController(AccessService access, DevelopmentSampleService service) {
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
   * <p>存在的理由：**进费用归集（12 财务）**、**试产衔接（6.6.2.1）**、
   * **真实物流轨迹（10 物流）**三件事做不到，而**达人寄样（14.5.1.2）不归本模块管** ——
   * 哪一个接通了必须由接口自己报出。
   */
  @GetMapping("/capabilities")
  public DevelopmentSampleCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "development-sample.read", a -> service.capabilities());
  }

  // ================================================================ 参照下拉

  /** 状态下拉（含每个状态允许迁到哪些状态）、来源、用途、确认结果、成本类型、物流状态。 */
  @GetMapping("/options")
  public DevelopmentSampleOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "development-sample.read", a -> service.options());
  }

  // ================================================================ 概览

  /** 按状态分组的概览。 */
  @GetMapping("/summary")
  public DevelopmentSampleSummary summary(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "development-sample.read", a -> service.summary());
  }

  // ================================================================ 打样单清单

  /** 打样单清单（真分页 + 真筛选）。 */
  @GetMapping
  public DevelopmentSampleOrderPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String sourceType,
      @RequestParam(required = false) UUID developmentProjectId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "development-sample.read",
        a -> service.listOrders(status, sourceType, developmentProjectId, keyword, page, size));
  }

  // ================================================================ 样品台账（跨单）

  /** 样品台账清单（**一个样品一件**，跨打样单）。 */
  @GetMapping("/samples")
  public DevelopmentSamplePage listSamples(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String sourceType,
      @RequestParam(required = false) UUID developmentProjectId,
      @RequestParam(required = false) UUID orderId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "development-sample.read",
        a ->
            service.listSamples(
                status, sourceType, developmentProjectId, orderId, keyword, page, size));
  }

  // ================================================================ 打样单

  /** 建打样单。初始状态恒为「已申请」。 */
  @PostMapping
  public DevelopmentSampleOrderDetail create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody DevelopmentSampleOrderCreateInput input) {
    return call(
        auth,
        "development-sample.update",
        a -> service.createOrder(input, a.userId(), auth));
  }

  /** 打样单详情：打样单 + 样品台账 + 迁移历史 + 成本。 */
  @GetMapping("/{id}")
  public DevelopmentSampleOrderDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "development-sample.read", a -> service.orderDetail(id));
  }

  /**
   * 改打样单（<b>整体替换</b>语义：请求体就是期望的最终状态）。
   *
   * <p>🔴 币种变了 ⇒ 样品与成本明细行的币种跟着走（**一张单一个币种**）。
   */
  @PutMapping("/{id}")
  public DevelopmentSampleOrderDetail update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody DevelopmentSampleOrderUpdateInput input) {
    return call(auth, "development-sample.update", a -> service.updateOrder(id, input));
  }

  /**
   * 推进打样单状态。
   *
   * <p>🔴 只能沿状态机上存在的边走；已关闭 / 已取消是终态；
   * 标记为「已完成」前样品必须全部落到终态。每次迁移都会落一条不可改写的事件。
   */
  @PostMapping("/{id}/transitions")
  public DevelopmentSampleOrderDetail transition(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody DevelopmentSampleOrderTransitionInput input) {
    return call(
        auth,
        "development-sample.update",
        a -> service.transitionOrder(id, input, a.userId()));
  }

  // ================================================================ 样品台账

  /** 某个打样单下的样品台账。 */
  @GetMapping("/{id}/samples")
  public List<DevelopmentSampleRow> samples(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "development-sample.read", a -> service.listSamplesOfOrder(id));
  }

  /** 登记样品（一个样品一件）。 */
  @PostMapping("/{id}/samples")
  public DevelopmentSampleDetail createSample(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody DevelopmentSampleCreateInput input) {
    return call(
        auth,
        "development-sample.update",
        a -> service.createSample(id, input, a.userId()));
  }

  /** 样品详情：样品 + 进度事件 + 确认记录 + 物流快照 + 图片。 */
  @GetMapping("/{id}/samples/{sampleId}")
  public DevelopmentSampleDetail sampleDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID sampleId) {
    return call(auth, "development-sample.read", a -> service.sampleDetail(id, sampleId));
  }

  /** 改样品（规格 / 数量 / 备注）。 */
  @PutMapping("/{id}/samples/{sampleId}")
  public DevelopmentSampleDetail updateSample(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID sampleId,
      @Valid @RequestBody DevelopmentSampleUpdateInput input) {
    return call(
        auth,
        "development-sample.update",
        a -> service.updateSample(id, sampleId, input));
  }

  /**
   * 推进样品<b>制作进度</b>（已申请 → 打样中 → 已发出 → 已到样）。
   *
   * <p>🔴 **确认类状态不走这个端点** —— 它们必须走确认端点留一条不可改写的确认记录。
   */
  @PostMapping("/{id}/samples/{sampleId}/transitions")
  public DevelopmentSampleDetail transitionSample(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID sampleId,
      @Valid @RequestBody DevelopmentSampleTransitionInput input) {
    return call(
        auth,
        "development-sample.update",
        a -> service.transitionSample(id, sampleId, input, a.userId()));
  }

  // ================================================================ 确认记录

  /** 确认记录清单。 */
  @GetMapping("/{id}/samples/{sampleId}/confirmations")
  public List<DevelopmentSampleConfirmationRow> confirmations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID sampleId) {
    return call(auth, "development-sample.read", a -> service.listConfirmations(sampleId));
  }

  /**
   * 记一条确认结果（通过 / 修改 / 放弃）并推进样品状态。
   *
   * <p>🔴 **记录先落、状态后改**：数据库守卫会检查「有没有对应的确认记录」，
   * 没有就直接拒绝这次状态变更。
   */
  @PostMapping("/{id}/samples/{sampleId}/confirmations")
  public DevelopmentSampleDetail confirm(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID sampleId,
      @Valid @RequestBody DevelopmentSampleConfirmationInput input) {
    return call(
        auth,
        "development-sample.update",
        a -> service.confirmSample(id, sampleId, input, a.userId()));
  }

  // ================================================================ 物流快照

  /** 物流快照清单。 */
  @GetMapping("/{id}/samples/{sampleId}/shipments")
  public List<DevelopmentSampleShipmentRow> shipments(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID sampleId) {
    return call(auth, "development-sample.read", a -> service.listShipments(sampleId));
  }

  /** 登记一条物流快照（**人工登记**，不是平台轨迹）。 */
  @PostMapping("/{id}/samples/{sampleId}/shipments")
  public List<DevelopmentSampleShipmentRow> createShipment(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID sampleId,
      @Valid @RequestBody DevelopmentSampleShipmentInput input) {
    return call(
        auth,
        "development-sample.update",
        a -> service.createShipment(id, sampleId, input, a.userId()));
  }

  // ================================================================ 样品图片

  /** 样品图片清单（catalog 媒体库逻辑引用 + 快照元数据）。 */
  @GetMapping("/{id}/samples/{sampleId}/media")
  public List<DevelopmentSampleMediaRow> media(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID sampleId) {
    return call(auth, "development-sample.read", a -> service.listMedia(sampleId));
  }

  /** 挂一张样品图片（引用 catalog 媒体库的对象，快照取自 catalog）。 */
  @PostMapping("/{id}/samples/{sampleId}/media")
  public List<DevelopmentSampleMediaRow> createMedia(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID sampleId,
      @Valid @RequestBody DevelopmentSampleMediaInput input) {
    return call(
        auth,
        "development-sample.update",
        a -> service.createMedia(id, sampleId, input, a.userId(), auth));
  }

  /** catalog 媒体库的候选（跨服务只读）。 */
  @GetMapping("/media-candidates")
  public List<DevelopmentSampleMediaCandidateRow> mediaCandidates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword) {
    return call(auth, "development-sample.read", a -> service.mediaCandidates(keyword, auth));
  }

  // ================================================================ 样品成本

  /** 样品成本（成本头 + 明细行）。 */
  @GetMapping("/{id}/cost")
  public DevelopmentSampleCostView cost(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "development-sample.read", a -> service.costView(id));
  }

  /** 追加一条成本明细行。 */
  @PostMapping("/{id}/cost/lines")
  public DevelopmentSampleCostView addCostLine(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody DevelopmentSampleCostLineInput input) {
    return call(
        auth,
        "development-sample.update",
        a -> service.addCostLine(id, input, a.userId()));
  }

  /** 删一条成本明细行（合计金额由数据库触发器自动重算）。 */
  @DeleteMapping("/{id}/cost/lines/{lineId}")
  public DevelopmentSampleCostView deleteCostLine(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID lineId) {
    return call(auth, "development-sample.update", a -> service.deleteCostLine(id, lineId, a.userId()));
  }
}
