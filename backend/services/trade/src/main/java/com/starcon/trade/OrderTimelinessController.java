package com.starcon.trade;

import static com.starcon.trade.OrderTimelinessModels.*;

import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 9.5.1.1 时效分析入口。
 *
 * <p>命名空间 {@code /trade/fulfillment/timeliness}，与 routing / allocation /
 * pending-shipment / shipment-records / dropship-* <b>并列</b>而非嵌套：
 * 前五个组回答「怎么发」，本组回答「发得够不够快」，在权限与日志上该分得开。
 *
 * <p>刻意<b>不挂</b>在 {@code shipment-records} 之下：那是 9.4.1.2 的
 * 「已发货事实」命名空间，本模块只读它的投影做统计，一个只读度量模块
 * 挂在被度量对象的路径下会让人以为改这里能改已发货事实。
 *
 * <p>十一个形状，刻意逐个列出、不做通配：
 *
 * <ul>
 *   <li>{@code capabilities} 能力与上下游可用性（轨迹实际时间 / 实际到货
 *       / 承诺时效配置三个维度 available=false 并点名 ownerModule；
 *       下游 13 / 16 的缺口同样写明）</li>
 *   <li>{@code POST /sync} 从 9.4.1.2 刷新时效事实（幂等投影，唯一的批量写入口）</li>
 *   <li>{@code GET /report} 时效报表（四段逐段统计 + 发货超时三桶 + 承诺四桶）</li>
 *   <li>{@code GET /dimensions} 分维度对比（店铺 / 仓库 / 承运商 / 商品 四选一）</li>
 *   <li>{@code GET /overdue} 超时明细（发货超时 + 承诺超时两个来源，分列报数）</li>
 *   <li>{@code GET /facts} 事实清单（真分页 + 真筛选 + 排序白名单 + 汇总）</li>
 *   <li>{@code GET /facts/{id}} 事实明细（单头 + 订单行 + 两类凭据 + 时间线）</li>
 *   <li>{@code POST /facts/{id}/promise} 登记承诺到货（10.3 未交付，登记而不伪造）</li>
 *   <li>{@code POST /facts/{id}/delivery} 登记实际到货（同上）</li>
 *   <li>{@code GET /events} 事件流（append-only，真分页）</li>
 * </ul>
 *
 * <p>刻意<b>没有</b> PUT / DELETE：凭据行是 append-only 的举证凭据
 * （V131 的 REVOKE 与触发器已经这么钉了），要更正就追加一条新的登记，
 * 而不是改掉上一条 —— 改掉之后，「这一票当时承诺的是哪天」就再也答不出了。
 *
 * <p>权限码<b>复用</b> {@code trade.fulfillment.read} / {@code trade.fulfillment.update}，
 * 不新起 {@code trade.timeliness.*}：identity 只注册了既有的 fulfillment 页面
 * 资源权限码，用一个<b>没有被注册</b>的码会让连 OWNER 都 403。
 */
@RestController
@RequestMapping("/trade/fulfillment/timeliness")
public class OrderTimelinessController {

  private final AccessService access;
  private final OrderTimelinessService timeliness;

  public OrderTimelinessController(AccessService access, OrderTimelinessService timeliness) {
    this.access = access;
    this.timeliness = timeliness;
  }

  /** 统一入口：先确认身份与权限（租户<b>不</b>来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "trade." + permission);
    return access.run(actor, "trade." + permission, () -> work.apply(actor));
  }

  /** 能力声明：三个可算维度 + 轨迹实际时间 / 承诺配置 available=false + 下游 13 / 16 缺口。 */
  @GetMapping("/capabilities")
  public OrderTimelinessCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "fulfillment.read", a -> timeliness.capabilities());
  }

  /**
   * 从 9.4.1.2 的发货记录刷新时效事实（幂等）。
   *
   * <p><b>本服务不直连 warehouse 库</b>：出库时刻取同服务 9.4.1.1 的投影，
   * V131 第六节写明了为什么（跨库视图、跨服务 SQL、跨服务 FK 三条都被禁止）。
   */
  @PostMapping("/sync")
  public TimelinessSyncResult sync(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody(required = false) TimelinessSyncInput input) {
    return call(auth, "fulfillment.update", a -> timeliness.sync(input == null ? new TimelinessSyncInput(null) : input));
  }

  /** 时效报表：四段逐段统计；每段都同时给已测数与未测数。 */
  @GetMapping("/report")
  public OrderTimelinessReport report(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String shopRef,
      @RequestParam(required = false) String warehouseRef,
      @RequestParam(required = false) String carrierCode,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime to) {
    return call(
        auth,
        "fulfillment.read",
        a -> timeliness.report(shopRef, warehouseRef, carrierCode, from, to));
  }

  /** 分维度对比：店铺 / 仓库 / 承运商 / 商品 四选一，全部真 GROUP BY。 */
  @GetMapping("/dimensions")
  public TimelinessDimensionPage dimensions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "SHOP") String dimensionBy,
      @RequestParam(required = false) String shopRef,
      @RequestParam(required = false) String warehouseRef,
      @RequestParam(required = false) String carrierCode,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime to,
      @RequestParam(required = false, defaultValue = "50") int limit) {
    TimelinessDimension dimension;
    try {
      dimension = TimelinessDimension.valueOf(dimensionBy.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "dimensionBy 只能是 SHOP / WAREHOUSE / CARRIER / SKU");
    }
    return call(
        auth,
        "fulfillment.read",
        a ->
            timeliness.dimensions(
                dimension, shopRef, warehouseRef, carrierCode, from, to, limit));
  }

  /** 超时明细：发货超时与承诺超时两个来源，分列报数而不是混成一个「超时数」。 */
  @GetMapping("/overdue")
  public TimelinessOverduePage overdue(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String shopRef,
      @RequestParam(required = false) String warehouseRef,
      @RequestParam(required = false) String carrierCode,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "fulfillment.read",
        a -> timeliness.overdue(shopRef, warehouseRef, carrierCode, page, size));
  }

  /** 事实清单（真分页 + 真筛选 + 排序白名单；汇总不受分页影响）。 */
  @GetMapping("/facts")
  public OrderTimelinessFactPage facts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String shopRef,
      @RequestParam(required = false) String warehouseRef,
      @RequestParam(required = false) String carrierCode,
      @RequestParam(required = false) String shipSlaState,
      @RequestParam(required = false) String promiseState,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "HANDOVER_AT_DESC") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    TimelinessSort srt;
    try {
      srt = TimelinessSort.valueOf(sort.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "sort 不在白名单内");
    }
    return call(
        auth,
        "fulfillment.read",
        a ->
            timeliness.facts(
                shopRef,
                warehouseRef,
                carrierCode,
                shipSlaState,
                promiseState,
                keyword,
                srt,
                page,
                size));
  }

  /** 事实明细：单头 + 订单行 + 承诺凭据 + 交付凭据 + 时间线 + 边界声明。 */
  @GetMapping("/facts/{id}")
  public OrderTimelinessDetail fact(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "fulfillment.read", a -> timeliness.fact(id));
  }

  /** 登记承诺到货 —— 2.5.1.1 未交付，本模块逐票登记，不假装读到了平台 SLA。 */
  @PostMapping("/facts/{id}/promise")
  public OrderTimelinessDetail registerPromise(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody TimelinessPromiseInput input) {
    return call(
        auth,
        "fulfillment.update",
        a -> timeliness.registerPromise(id, input, a.membershipId()));
  }

  /** 登记实际到货 —— 10.3 未交付，本模块登记人工上报的签收，不假装抓到了轨迹。 */
  @PostMapping("/facts/{id}/delivery")
  public OrderTimelinessDetail registerDelivery(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody TimelinessDeliveryInput input) {
    return call(
        auth,
        "fulfillment.update",
        a -> timeliness.registerDelivery(id, input, a.membershipId()));
  }

  /** 事件流（append-only，真分页；**刻意没有**任何写形状与 DELETE）。 */
  @GetMapping("/events")
  public OrderTimelinessEventPage events(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String kind,
      @RequestParam(required = false) String factId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "fulfillment.read", a -> timeliness.events(kind, factId, page, size));
  }
}
