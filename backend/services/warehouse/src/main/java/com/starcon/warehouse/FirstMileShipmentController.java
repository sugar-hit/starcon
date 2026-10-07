package com.starcon.warehouse;

import static com.starcon.warehouse.FirstMileShipmentModels.*;

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
 * 10.4.1.1 头程发运单。
 *
 * <p>命名空间 {@code /warehouse/first-mile-shipments}：与收货单（8.2.1.1）同一 {@code
 * /warehouse} 前缀，浏览器只经 /api/warehouse 代理访问。刻意不挂在 {@code /logistics/}
 * 下面 —— 蓝图的 10 物流与头程目前只有 warehouse 服务一个可落地后端，
 * 而本模块的货物来源、在途腿与质量状态全在 warehouse 服务内，
 * 放进 {@code /inventory/} 也会让「8.2 现场作业」与「10 货权转移」两套语义混在一个前缀里。
 *
 * <p>权限码沿用 {@code warehouse.}：读用 {@code warehouse.inventory.read}，
 * 全部写动作（建单 / 改单 / 确认 / 发出 / 取消）挂 {@code warehouse.inventory.update} ——
 * 发出那一刻货权转移、在途腿登记落库，权限等级与调拨发货同档。
 *
 * <p>租户**不**来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 客户端伪造的 {@code X-Tenant-Id} 进不来也改不动它。
 *
 * <p>动作端点是 **POST**（{@code /confirm}、{@code /dispatch}、{@code /cancel}）：
 * 它们都是「发生一次业务事件」，用 PUT 会被 HTTP 语义读成「把单据设置成某个状态」，
 * 而实际发生的是货权转移 + 在途腿登记。
 */
@RestController
@RequestMapping("/warehouse/first-mile-shipments")
public class FirstMileShipmentController {

  private final AccessService access;
  private final FirstMileShipmentService shipments;

  public FirstMileShipmentController(AccessService access, FirstMileShipmentService shipments) {
    this.access = access;
    this.shipments = shipments;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  /**
   * 头程发运能力：货物来源接入情况、**未交付依赖逐条声明**、四态状态机、
   * 运输方式与贸易术语词表，以及四条口径说明。
   *
   * <p>前端据此渲染来源下拉、运输方式、货权/风险选择与提示 —— 未交付的来源与下游
   * **显式报不可用并给出归属模块**，而不是给一个点下去没反应的选项。
   */
  @GetMapping("/capabilities")
  public FirstMileCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> shipments.capabilities());
  }

  /**
   * 可发批次候选（8.2.1.1 逐次实收行）。
   *
   * <p>头程发运的输入是「选择货物与批次」，而批次挂在**逐次实收**上：
   * 同一 SKU 分三车到货可以是三个批次。所以这里精确到实收行而不是精确到 SKU，
   * 并如实给出「还能发走多少」（实收 − 拒收 − 已被其他发运单占用）。
   */
  @GetMapping("/source-candidates")
  public SourceCandidatePage sourceCandidates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID receiptId,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a -> shipments.sourceCandidates(receiptId, skuId, warehouseId, keyword, page, size));
  }

  /** 头程发运单列表 + 全量汇总（**合计不受分页影响**）+ 状态/运输方式/仓库下拉。 */
  @GetMapping
  public FirstMilePage page(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) List<String> status,
      @RequestParam(required = false) String transportMode,
      @RequestParam(required = false) String incoterm,
      @RequestParam(required = false) UUID originWarehouseId,
      @RequestParam(required = false) UUID destinationWarehouseId,
      @RequestParam(required = false) String destinationCountryCode,
      @RequestParam(required = false) Boolean inTransitOnly,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            shipments.page(
                new FirstMileQuery(
                    status,
                    transportMode,
                    incoterm,
                    originWarehouseId,
                    destinationWarehouseId,
                    destinationCountryCode,
                    inTransitOnly,
                    keyword,
                    sort,
                    page,
                    size)));
  }

  /**
   * 头程发运单详情：头 + 明细 + 发货分录（在途腿）+ 事件时间线 + 当前状态的合法下一步。
   *
   * <p>跨租户在 RLS 下查不到 → 404（403 会泄露「这张单存在」）。
   */
  @GetMapping("/{shipmentId}")
  public FirstMileDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID shipmentId) {
    return call(auth, "inventory.read", a -> shipments.detail(shipmentId));
  }

  /**
   * 本单的发货分录（**在途腿**，真在 7.5.1.2 名下）。
   *
   * <p>未达量与已了结量是 7.5.1.2 的权威值，本模块只如实读出来一起返回：
   * 「发运单发出 100、到货 60」这两个数必须并排可读，否则会被读成账丢了 40。
   */
  @GetMapping("/{shipmentId}/transit-legs")
  public List<FirstMileTransitLegRow> transitLegs(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID shipmentId) {
    return call(
        auth,
        "inventory.read",
        a -> {
          // 详情先行：单不存在时 404，而不是返回一个空腿数组冒充「没有在途」。
          shipments.detail(shipmentId);
          return shipments.transitLegs(shipmentId);
        });
  }

  /** 建单（草稿）。用 POST：它真的落库；草稿不动任何账。 */
  @PostMapping
  public FirstMileSaved create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FirstMileCreateRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> shipments.create(a.tenantId(), request == null ? emptyCreate() : request));
  }

  /** 改单：仅草稿与已确认可改，整份替换明细。已发出之后一行都不让改。 */
  @PutMapping("/{shipmentId}")
  public FirstMileSaved update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID shipmentId,
      @Valid @RequestBody FirstMileUpdateRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> shipments.update(shipmentId, request == null ? emptyUpdate() : request));
  }

  /**
   * 确认：申报信息齐备、已订舱。货还没走，所以不动任何账。
   *
   * <p>这是「可以发了」的最后一道闸：申报品名/原产国/货值/毛重/箱数缺一项都拒绝。
   */
  @PostMapping("/{shipmentId}/confirm")
  public FirstMileSaved confirm(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID shipmentId,
      @Valid @RequestBody FirstMileConfirmRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> shipments.confirm(shipmentId, request == null ? emptyConfirm() : request));
  }

  /**
   * 发出：**货权转移与转在途就在这一刻发生**。
   *
   * <p>逐行在 7.5.1.2 登记 FIRST_MILE 在途腿（sourceSystem=FIRST_MILE、
   * sourceReference=发运单号-行号），**不扣在手库存** —— 头程货在到货前从未进过我方库存账。
   * 重复发出撞 7.5.1.2 的幂等唯一索引，不会双记在途。
   */
  @PostMapping("/{shipmentId}/dispatch")
  public FirstMileSaved dispatch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID shipmentId,
      @Valid @RequestBody FirstMileDispatchRequest request) {
    return call(
        auth,
        "inventory.update",
        a ->
            shipments.dispatch(
                a.tenantId(), shipmentId, request == null ? emptyDispatch() : request));
  }

  /** 取消：必须给原因。已发出之后不能取消（货权已转，走 7.5.1.2 的差异登记）。 */
  @PostMapping("/{shipmentId}/cancel")
  public FirstMileSaved cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID shipmentId,
      @Valid @RequestBody FirstMileCancelRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> shipments.cancel(shipmentId, request == null ? emptyCancel() : request));
  }

  // 空请求体落到这里：{@code @Valid} 对 null 不生效，必须由服务层报出可读错误
  // （例如「缺少必填项：贸易术语（成本转嫁方式）」），而不是 NPE。

  // 每个 null 对应 record 的一个组件（顺序与 Models 里逐字一致）。组件一多就容易数错，
  // 所以这里按 Models 的字段顺序分组注释；改 Models 时这里必须同步改，否则编译期就报参数不匹配。
  private static FirstMileCreateRequest emptyCreate() {
    return new FirstMileCreateRequest(
        // sourceType, sourceRefId, sourceRefCode, legalEntityId,
        null, null, null, null,
        // originWarehouseId, destinationWarehouseId, destinationCountryCode, destinationName,
        null, null, null, null,
        // transportMode, carrierName, bookingNo, vesselOrFlightNo,
        null, null, null, null,
        // incoterm, riskBearer, currency, freightCost,
        null, null, null, null,
        // insuranceCost, dutyEstimate, handlingCost, customsBroker,
        null, null, null, null,
        // departAt, expectedArriveAt, notes, lines
        null, null, null, List.of());
  }

  private static FirstMileUpdateRequest emptyUpdate() {
    return new FirstMileUpdateRequest(
        // expectedVersion, sourceRefCode, destinationWarehouseId, destinationCountryCode,
        null, null, null, null,
        // destinationName, transportMode, carrierName, bookingNo,
        null, null, null, null,
        // vesselOrFlightNo, incoterm, riskBearer, currency,
        null, null, null, null,
        // freightCost, insuranceCost, dutyEstimate, handlingCost,
        null, null, null, null,
        // customsBroker, departAt, expectedArriveAt, notes, lines
        null, null, null, null, List.of());
  }

  private static FirstMileConfirmRequest emptyConfirm() {
    return new FirstMileConfirmRequest(null, null, null, null);
  }

  private static FirstMileDispatchRequest emptyDispatch() {
    return new FirstMileDispatchRequest(null, null, null, List.of());
  }

  private static FirstMileCancelRequest emptyCancel() {
    return new FirstMileCancelRequest(null, null, null);
  }
}
