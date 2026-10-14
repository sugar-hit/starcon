package com.starcon.trade;

import static com.starcon.trade.DropshipOrderModels.*;

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
 * 9.6.1.2 代发单与供应商下单。
 *
 * <p>命名空间 {@code /trade/fulfillment/dropship-orders}：与 9.6.1.1 的
 * {@code /trade/fulfillment/dropship} <b>并列</b>而不是嵌套 ——
 * 配置说「这个 SKU 走不走代发、供应商是谁」，代发单说「这一票具体怎么下的、
 * 供应商回了多少钱、差价还正不正」。混在一个命名空间里会让「改一个配置」
 * 与「下了一单」在日志和权限上分不开。
 *
 * <p>权限码沿用 {@code trade.fulfillment.read} / {@code trade.fulfillment.update}：
 * 向供应商下单是要花钱的动作，不是读权限能带过去的。
 *
 * <p>租户**不**来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/trade/fulfillment/dropship-orders")
public class DropshipOrderController {

  private final AccessService access;
  private final DropshipOrderService dropshipOrders;

  public DropshipOrderController(AccessService access, DropshipOrderService dropshipOrders) {
    this.access = access;
    this.dropshipOrders = dropshipOrders;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "trade." + permission);
    return access.run(actor, "trade." + permission, () -> work.apply(actor));
  }

  // ============================================================ 能力声明

  /**
   * 本模块的能力与上游可用性。
   *
   * <p>本模块只有一处「拿不到却必须说」：<b>支付状态</b>（9.1 无支付状态列，
   * 12 财务未交付）。它在这里显式 {@code available=false} 并点名 ownerModule，
   * 而不是被画成一个假的「已付款」勾。
   */
  @GetMapping("/capabilities")
  public DropshipOrderCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "fulfillment.read", a -> dropshipOrders.capabilities());
  }

  // ============================================================ 供应商代发接口登记

  /** 供应商代发接口登记列表（只存 credential_ref，不存明文凭证）。 */
  @GetMapping("/endpoints")
  public DropshipEndpointPage endpoints(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String vendorCode,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "fulfillment.read",
        a -> dropshipOrders.pageEndpoints(new DropshipEndpointQuery(vendorCode, page, size)));
  }

  /** 登记 / 更新一个供应商代发接口。标记已接线时 baseUrl 必填。 */
  @PostMapping("/endpoints")
  public DropshipEndpointSaved saveEndpoint(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody DropshipEndpointInput input) {
    return call(auth, "fulfillment.update", a -> dropshipOrders.saveEndpoint(input));
  }

  // ============================================================ 代发单

  /** 代发单列表（真分页）。{@code negativeSpreadOnly=true} 只回差价转负的那些。 */
  @GetMapping("/orders")
  public DropshipOrderPage orders(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String orderNo,
      @RequestParam(required = false) String salesOrderNo,
      @RequestParam(required = false) String supplierCode,
      @RequestParam(required = false) String vendorCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "false") Boolean negativeSpreadOnly,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "fulfillment.read",
        a ->
            dropshipOrders.pageOrders(
                new DropshipOrderQuery(
                    orderNo, salesOrderNo, supplierCode, vendorCode, status, negativeSpreadOnly,
                    keyword, page, size)));
  }

  /** 从一张已提交的销售订单 + 一份生效的 9.6.1.1 配置建代发单（采购价在此刻冻结）。 */
  @PostMapping("/orders")
  public DropshipOrderSaved createOrder(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody DropshipOrderInput input) {
    return call(
        auth, "fulfillment.update", a -> dropshipOrders.createOrder(input, a.membershipId()));
  }

  /** 代发单详情：头 + 明细行 + 告警 + 事件流。跨租户在 RLS 下查不到 → 404。 */
  @GetMapping("/orders/{orderId}")
  public DropshipOrderService.DropshipOrderDetail order(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId) {
    return call(auth, "fulfillment.read", a -> dropshipOrders.orderDetail(orderId));
  }

  /**
   * 确认收款。
   *
   * <p>9.1 的 sales_order 没有支付状态列（status 只有 DRAFT/SUBMITTED，payment_method
   * 是付款<b>方式</b>不是实收），因此这一步记录的是<b>一次由有权限成员作出的书面确认</b>，
   * 而不是设置一个系统支付状态。未确认收款时 DB 触发器会挡住提交给供应商。
   */
  @PostMapping("/orders/{orderId}/confirm-payment")
  public DropshipOrderSaved confirmPayment(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId,
      @Valid @RequestBody DropshipPaymentConfirmInput input) {
    return call(
        auth,
        "fulfillment.update",
        a -> dropshipOrders.confirmPayment(orderId, input, a.membershipId()));
  }

  /**
   * 向供应商提交代发单（人工路径转「待录回执」，自动路径走 vendor 网关）。
   *
   * <p><b>供应商侧失败刻意返回 200</b>，而不是抛 5xx：调用带事务，抛异常会把刚写下的
   * FAILED 状态与 ORDER_FAILED / NO_STOCK 告警一起回滚 —— 而那正是蓝图要求
   * 「必须显式」的东西。失败以 {@code status=FAILED} + 告警的形式返回，
   * 调用方从单据与告警读到事实（与 9.6.1.1 的 evaluate 同一纪律：
   * 「无法履约也是业务结论而不是请求失败」）。
   */
  @PostMapping("/orders/{orderId}/dispatch")
  public DropshipOrderSaved dispatch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId) {
    return call(auth, "fulfillment.update", a -> dropshipOrders.dispatch(orderId, a.membershipId()));
  }

  /**
   * 人工录入供应商回执：单号与**实际**代发成本。
   *
   * <p>与自动路径同一套告警逻辑 —— 人工录入涨价同样落 PRICE_INCREASE，
   * 差价转负同样落 NEGATIVE_SPREAD。
   */
  @PostMapping("/orders/{orderId}/supplier-ack")
  public DropshipOrderSaved supplierAck(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId,
      @Valid @RequestBody DropshipSupplierAckInput input) {
    return call(
        auth,
        "fulfillment.update",
        a -> dropshipOrders.recordSupplierAck(orderId, input, a.membershipId()));
  }

  /** 记录供应商发货与运单号（跟踪与妥投判定属下游 9.6.1.3 / 10 物流）。 */
  @PostMapping("/orders/{orderId}/shipment")
  public DropshipOrderSaved shipment(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId,
      @Valid @RequestBody DropshipShipmentInput input) {
    return call(
        auth,
        "fulfillment.update",
        a -> dropshipOrders.recordShipment(orderId, input, a.membershipId()));
  }

  /** 人工置失败（供应商口头拒单、平台返回失败等）；必落 ORDER_FAILED 告警。 */
  @PostMapping("/orders/{orderId}/fail")
  public DropshipOrderSaved fail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId,
      @Valid @RequestBody DropshipFailureInput input) {
    return call(auth, "fulfillment.update", a -> dropshipOrders.markFailed(orderId, input, a.membershipId()));
  }

  // ============================================================ 差异告警

  /**
   * 差异告警工作台。
   *
   * <p>蓝图原话「供应商涨价或无货<b>必须显式告警</b>，否则差价转负的订单会静默亏损」
   * —— 告警是本模块的硬输出，因此它有独立的列表端点与筛选维度，
   * 而不是只能从代发单详情里翻到。
   */
  @GetMapping("/alerts")
  public DropshipAlertPage alerts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID orderId,
      @RequestParam(required = false) String alertKind,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String severity,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "fulfillment.read",
        a ->
            dropshipOrders.pageAlerts(
                new DropshipAlertQuery(
                    orderId, alertKind, status, severity, keyword, page, size)));
  }

  /**
   * 处置一条告警。
   *
   * <p>处置<b>不删除</b>告警历史行（DB 触发器 + REVOKE 双层挡住）：
   * 告警是「已经贵过 3 块」这件事的凭据，删掉它等于把这段历史抹掉。
   */
  @PostMapping("/alerts/{alertId}/acknowledge")
  public DropshipAlertRow acknowledge(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID alertId,
      @Valid @RequestBody DropshipAlertAckInput input) {
    return call(auth, "fulfillment.update", a -> dropshipOrders.acknowledgeAlert(alertId, input));
  }

  // ============================================================ 事件流

  /** 事件流（只读；append-only，没有任何写形状）。 */
  @GetMapping("/events")
  public DropshipEventPage events(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID orderId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth, "fulfillment.read", a -> dropshipOrders.pageEvents(new DropshipEventQuery(orderId, page, size)));
  }
}