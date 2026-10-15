package com.starcon.trade;

import static com.starcon.trade.ShipmentRecordModels.*;

import jakarta.validation.Valid;
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
 * 9.4.1.2 发货记录入口。
 *
 * <p>命名空间 {@code /trade/fulfillment/shipment-records}。<b>刻意不挂在
 * {@code pending-shipment} 之下</b>：那是 9.4.1.1 待发货的命名空间，
 * 而待发货最远只到 HANDED_OVER、<b>从不产生已发货</b>（V124 文件头第四节）。
 * 把已发货挂到它下面，会让人以为「待发货推进一下就成了发货记录」——
 * 而那正是 9.4.1.1 刻意拒绝做、会让迟发率算在从未发生的时刻上的那一步。
 * 本模块的每一条都从 8.3.4.1 已确认交接的交接单同步而来。
 *
 * <p>十个形状，刻意逐个列出、不做通配：
 *
 * <ul>
 *   <li>{@code capabilities} 能力与上下游可用性（轨迹 / 买家可见 / 平台回传三个维度
 *       available=false 并点名 ownerModule）</li>
 *   <li>{@code POST /sync} 从 8.3.4.1 授权只读接口同步已确认交接（幂等投影，**唯一的写入口之一**）</li>
 *   <li>{@code GET /board} 分组看板（承运商 / 轨迹状态 / 平台回传状态 四选一，真 GROUP BY）</li>
 *   <li>{@code GET /records} 发货记录清单（真分页 + 真筛选 + 排序白名单 + 汇总）</li>
 *   <li>{@code GET /records/{id}} 记录明细（单头 + 包裹 + 轨迹 + 可见状态 + 回传 + 时间线）</li>
 *   <li>{@code POST /records/{id}/tracking} 登记轨迹观测（10.3 未交付，登记而不伪造）</li>
 *   <li>{@code POST /records/{id}/visibility} 登记买家可见状态（无可读上游）</li>
 *   <li>{@code POST /records/{id}/callbacks} 登记平台回传状态（9.4.2.1 / 2.5.1.1 未交付）</li>
 *   <li>{@code POST /records/{id}/status} 推进 / 更正记录状态（终态不可再动）</li>
 *   <li>{@code GET /performance} 迟发率与有效追踪率（口径在服务端一处定义）</li>
 *   <li>{@code GET /events} 事件流（append-only，真分页）</li>
 * </ul>
 *
 * <p>刻意<b>没有</b> PUT / DELETE：凭据行是 append-only 的举证凭据
 * （V127 的 REVOKE 与触发器已经这么钉了），要更正就追加一条新的登记，
 * 而不是改掉上一条 —— 改掉之后，「这一票当时到底报的是什么」就再也答不出了。
 *
 * <p>权限码<b>复用</b> {@code trade.fulfillment.read} / {@code trade.fulfillment.update}，
 * 不新起 {@code trade.shipment.*}：identity 只注册了既有的 fulfillment 页面资源
 * 权限码，用一个<b>没有被注册</b>的码会让连 OWNER 都 403（8.1.2.1 车道踩过这个坑）。
 */
@RestController
@RequestMapping("/trade/fulfillment/shipment-records")
public class ShipmentRecordController {

  private final AccessService access;
  private final ShipmentRecordService shipmentRecords;

  public ShipmentRecordController(AccessService access, ShipmentRecordService shipmentRecords) {
    this.access = access;
    this.shipmentRecords = shipmentRecords;
  }

  /** 统一入口：先确认身份与权限（租户<b>不</b>来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "trade." + permission);
    return access.run(actor, "trade." + permission, () -> work.apply(actor));
  }

  /**
   * 能力声明：已发货事实来自 8.3.4.1 已确认交接、订单快照来自同服务 9.4.1.1 只读 join、
   * 轨迹（10.3）/ 买家可见（无可读上游）/ 平台回传（9.4.2.1 / 2.5.1.1）三个维度未交付。
   */
  @GetMapping("/capabilities")
  public ShipmentRecordCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "fulfillment.read", a -> shipmentRecords.capabilities());
  }

  /**
   * 从 8.3.4.1 的授权只读接口同步已确认交接的交接单，落成发货记录（幂等）。
   *
   * <p><b>本服务不直连 warehouse 库</b>：V127 文件头第一节写明了为什么
   * （跨库视图、跨服务 SQL、跨服务 FK 三条都被 AGENTS.md 禁止），
   * 上游不可达时这里返回 503 而不是空清单。
   */
  @PostMapping("/sync")
  public ShipmentRecordSyncResult sync(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ShipmentRecordSyncInput input) {
    return call(auth, "fulfillment.update", a -> shipmentRecords.sync(input, auth));
  }

  /** 分组看板：承运商 / 轨迹状态 / 平台回传状态 四选一，真 GROUP BY。 */
  @GetMapping("/board")
  public ShipmentRecordBoard board(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String carrierCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String trackingStatus,
      @RequestParam(required = false) String platformCallbackStatus,
      @RequestParam(required = false, defaultValue = "CARRIER") String groupBy) {
    return call(
        auth,
        "fulfillment.read",
        a ->
            shipmentRecords.board(
                carrierCode,
                status,
                trackingStatus,
                platformCallbackStatus,
                groupBy == null
                    ? null
                    : ShipmentRecordGroupBy.valueOf(groupBy.toUpperCase(Locale.ROOT))));
  }

  /** 发货记录清单（真分页 + 真筛选 + 排序白名单；汇总不受分页影响）。 */
  @GetMapping("/records")
  public ShipmentRecordPage records(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String carrierCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String trackingStatus,
      @RequestParam(required = false) String platformCallbackStatus,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "SHIPPED_AT_DESC") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "fulfillment.read",
        a ->
            shipmentRecords.records(
                carrierCode,
                status,
                trackingStatus,
                platformCallbackStatus,
                keyword,
                sort,
                page,
                size));
  }

  /** 记录明细：单头 + 包裹 + 轨迹 + 可见状态 + 回传 + 时间线 + 边界声明。 */
  @GetMapping("/records/{id}")
  public ShipmentRecordDetail record(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "fulfillment.read", a -> shipmentRecords.record(id));
  }

  /** 登记轨迹观测 —— 10.3 未交付，本模块只登记不抓取。 */
  @PostMapping("/records/{id}/tracking")
  public ShipmentRecordDetail registerTracking(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody ShipmentTrackingObservationInput input) {
    return call(
        auth,
        "fulfillment.update",
        a -> shipmentRecords.registerTracking(id, input, a.membershipId()));
  }

  /** 登记买家可见状态 —— 没有可读上游，所以登记（有主体、有时刻、有依据）。 */
  @PostMapping("/records/{id}/visibility")
  public ShipmentRecordDetail registerVisibility(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody ShipmentVisibilityInput input) {
    return call(
        auth,
        "fulfillment.update",
        a -> shipmentRecords.registerVisibility(id, input, a.membershipId()));
  }

  /**
   * 登记平台回传状态 —— 9.4.2.1 / 2.5.1.1 未交付，本模块登记进展与结果，
   * <b>不假装已经回传出去</b>。SKIPPED（决定不回传）必须带原因。
   */
  @PostMapping("/records/{id}/callbacks")
  public ShipmentRecordDetail registerCallback(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody ShipmentCallbackInput input) {
    return call(
        auth,
        "fulfillment.update",
        a -> shipmentRecords.registerCallback(id, input, a.membershipId()));
  }

  /** 推进 / 更正记录状态。终态（DELIVERED / RETURNED / VOID）不可再动。 */
  @PostMapping("/records/{id}/status")
  public ShipmentRecordDetail changeStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody ShipmentStatusInput input) {
    return call(
        auth,
        "fulfillment.update",
        a -> shipmentRecords.changeStatus(id, input, a.membershipId()));
  }

  /** 绩效口径：迟发率与有效追踪率。口径定义在服务端一处，界面不自算。 */
  @GetMapping("/performance")
  public ShipmentPerformanceReport performance(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String carrierCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String trackingStatus,
      @RequestParam(required = false) String platformCallbackStatus,
      @RequestParam(required = false) String keyword) {
    return call(
        auth,
        "fulfillment.read",
        a ->
            shipmentRecords.performance(
                carrierCode, status, trackingStatus, platformCallbackStatus, keyword));
  }

  /** 事件流（append-only，真分页；**刻意没有**任何写形状与 DELETE）。 */
  @GetMapping("/events")
  public ShipmentRecordEventPage events(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String eventKind,
      @RequestParam(required = false) UUID recordId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "fulfillment.read",
        a -> shipmentRecords.events(eventKind, recordId, page, size));
  }
}
