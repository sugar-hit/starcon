package com.starcon.trade;

import static com.starcon.trade.WaybillModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
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
 * 10.2.1.2 运单申请入口。
 *
 * <p>命名空间 {@code /trade/logistics/waybill-applications}。<b>刻意不挂在
 * {@code fulfillment/pending-shipment} 之下</b>：待发货是 9.4.1.1 的履约看板，
 * 本模块是 10 物流的运单文档 —— 蓝图卷 10 明写「trade 拥有运单与报关单据」。
 * 挂成一个子路由会让人以为运单号是待发货单元的属性，而实际它是承运商签发的凭据。
 *
 * <p>十个形状，刻意逐个列出、不做通配：
 *
 * <ul>
 *   <li>{@code capabilities} 能力与上游可用性（含 10.1 未交付、10.2.1.3 / 10.3 未交付）
 *   <li>{@code GET /carriers} 承运商与渠道登记清单（真分页 + 真筛选）
 *   <li>{@code POST /carriers} 登记承运商（登记 ≠ 接通，默认 connected=false）
 *   <li>{@code POST /requests} 提交一条运单申请（**逐包裹** + 幂等键）
 *   <li>{@code POST /batches} 批量申请（**一次请求不许跨承运商**）
 *   <li>{@code GET /requests} 申请清单（真分页 + 真筛选 + 排序白名单）
 *   <li>{@code GET /requests/{id}} 申请详情（头 + 申报明细 + 队列 + 尝试 + 事件）
 *   <li>{@code POST /requests/{id}/dispatch} 按渠道窗口逐条放行
 *   <li>{@code POST /requests/{id}/retry} 失败重试（仍受幂等键约束）
 *   <li>{@code POST /requests/{id}/cancel} 作废（只允许还没出号的）
 * </ul>
 *
 * <p>刻意<b>没有</b> PUT / DELETE：运单申请是带凭据的单据
 * （V128 的 REVOKE 与状态机触发器已经这么钉了），要更正就作废重开一张。
 *
 * <p>权限码<b>复用</b> {@code trade.fulfillment.read} / {@code trade.fulfillment.update}，
 * 不新起 {@code trade.waybill.*}：identity 只注册了既有的 fulfillment 页面资源权限码，
 * 用一个<b>没有被注册</b>的码会让连 OWNER 都 403（8.1.2.1 车道踩过这个坑）。
 */
@RestController
@RequestMapping("/trade/logistics/waybill-applications")
public class WaybillApplicationController {

  private final AccessService access;
  private final WaybillApplicationService waybills;

  public WaybillApplicationController(
      AccessService access, WaybillApplicationService waybills) {
    this.access = access;
    this.waybills = waybills;
  }

  /** 统一入口：先确认身份与权限（租户<b>不</b>来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "trade." + permission);
    return access.run(actor, "trade." + permission, () -> work.apply(actor));
  }

  /**
   * 能力声明：<b>逐条</b>说清哪个维度真能用、哪个没有上游。
   *
   * <p>本端点存在的理由是 10.1 整卷未交付：一个「什么都能做」的运单申请页面
   * 会让运营在大促期点下去才发现拿不到单号，而那正是承运商接口最不该被打的时候。
   */
  @GetMapping("/capabilities")
  public WaybillCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "fulfillment.read", a -> waybills.capabilities());
  }

  /** 承运商与渠道登记清单。真分页 + 真筛选，不是全量返回让前端自己筛。 */
  @GetMapping("/carriers")
  public WaybillCarrierPage carriers(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String carrierCode,
      @RequestParam(required = false) Boolean connected,
      @RequestParam(required = false) String sort,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth, "fulfillment.read", a -> waybills.carriers(carrierCode, connected, sort, page, size));
  }

  /**
   * 登记承运商 / 渠道 / 限流口径。
   *
   * <p><b>登记 ≠ 接通</b>：默认一律 {@code connected=false}，且未给原因时
   * 自动写明「10.1 物流渠道未交付」。一个「登记即接通」的默认值会让界面显示
   * 「已接通」而实际提交必失败 —— 那是最不该出现的显示与事实不一致。
   */
  @PostMapping("/carriers")
  public WaybillCarrierRow registerCarrier(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody WaybillCarrierInput input) {
    return call(
        auth,
        "fulfillment.update",
        a -> waybills.registerCarrier(input, a.membershipId()));
  }

  /**
   * 提交一条运单申请（逐包裹）。
   *
   * <p>重复提交同一次申请返回<b>原来那一张</b>并标 {@code idempotentReplay}，
   * 不会产生第二张运单 —— 这是蓝图输入句第四项 {@code idempotency_key} 的兑现，
   * DB 层由 {@code UNIQUE(tenant_id, idempotency_key)} 钉死。
   */
  @PostMapping("/requests")
  public WaybillRequestRow request(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody WaybillRequestInput input) {
    return call(
        auth,
        "fulfillment.update",
        a -> waybills.request(input, a.membershipId(), auth));
  }

  /**
   * 批量申请（**一次请求不许跨承运商**）。
   *
   * <p>逐条入队、逐条返回结果：批量里一条失败不能把整批吞掉。
   * 服务层在混进两个承运商时当场拒，并把冲突的承运商都列出来。
   */
  @PostMapping("/batches")
  public WaybillBatchResult batch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody WaybillBatchRequestInput input) {
    return call(auth, "fulfillment.update", a -> waybills.batch(input, a.membershipId(), auth));
  }

  /** 申请清单（真分页 + 真筛选 + 排序白名单）。 */
  @GetMapping("/requests")
  public WaybillRequestPage requests(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID carrierId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth, "fulfillment.read", a -> waybills.requests(carrierId, status, keyword, sort, page, size));
  }

  /** 申请详情：头 + 申报明细 + 队列 + 逐次尝试 + 事件流 + 完成判据。 */
  @GetMapping("/requests/{id}")
  public WaybillRequestDetail request(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "fulfillment.read", a -> waybills.request(id));
  }

  /**
   * 按渠道限流窗口<b>逐条</b>放行。
   *
   * <p>不是同步阻塞循环：一次取一批到期条目，逐条提交，每条独立落尝试与状态。
   * 一条被限流不拖住后面所有条 —— 这正是蓝图那句「批量申请要按渠道限流排队」
   * 要防的后果（触发接口限流影响整批）。
   */
  @PostMapping("/requests/dispatch")
  public WaybillDispatchResult dispatch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody WaybillDispatchInput input) {
    return call(auth, "fulfillment.update", a -> waybills.dispatch(input, auth));
  }

  /** 失败重试：沿用同一条申请（同一条幂等键），**不会**产生第二张运单。 */
  @PostMapping("/requests/{id}/retry")
  public WaybillRetryResult retry(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody WaybillRetryInput input) {
    return call(auth, "fulfillment.update", a -> waybills.retry(id, input, a.membershipId()));
  }

  /** 作废：只允许作废**还没出号**的申请；已出号的取消属 10.2.1.3（未交付）。 */
  @PostMapping("/requests/{id}/cancel")
  public WaybillRequestRow cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody WaybillCancelInput input) {
    return call(auth, "fulfillment.update", a -> waybills.cancel(id, input, a.membershipId()));
  }

  /** 某个承运商的队列视图：看得见「谁在排第几位」，排队必须是可见事实。 */
  @GetMapping("/queue")
  public List<WaybillQueueRow> queue(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String carrierCode) {
    return call(
        auth,
        "fulfillment.read",
        a -> waybills.queue(carrierCode == null ? "" : carrierCode.trim().toUpperCase(Locale.ROOT)));
  }
}
