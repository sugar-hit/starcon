package com.starcon.trade;

import static com.starcon.trade.FulfillmentRoutingModels.*;

import jakarta.validation.Valid;
import java.time.OffsetDateTime;
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
 * 9.3.2.2 FBA 与自发货选择。
 *
 * <p>命名空间 {@code /trade/fulfillment/routing}：卷 09 的前端入口是 {@code /orders}，
 * 浏览器只经 /api/trade 代理访问（proxy 白名单里 trade/ 这一支由本车道首次放行）。
 *
 * <p>权限码用 {@code trade.fulfillment.read} / {@code trade.fulfillment.update}：
 * 落决策与登记资质直接改变履约路径与合规判断，不是读权限能带过去的动作。
 *
 * <p>租户**不**来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/trade/fulfillment/routing")
public class FulfillmentRoutingController {

  private final AccessService access;
  private final FulfillmentRoutingService routing;

  public FulfillmentRoutingController(AccessService access, FulfillmentRoutingService routing) {
    this.access = access;
    this.routing = routing;
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
   * <p>存在的理由：把「哪些输入真有依据」显式报出来 —— 渠道库存与 MCF 资质是
   * 本服务自维护（上游在别的库，不可跨库读），订单行与履约请求尚未交付，
   * 这些都必须由接口自己说，而不是让调用方从 404 或空数组里猜。
   */
  @GetMapping("/capabilities")
  public FulfillmentRoutingCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "fulfillment.read", a -> routing.capabilities());
  }

  // ============================================================ MCF 资质

  /** 多渠道配送资质列表。 */
  @GetMapping("/mcf-authorizations")
  public McfPage mcfList(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "fulfillment.read",
        a -> routing.pageMcf(new McfQuery(platformCode, shopCode, status, keyword, page, size)));
  }

  /**
   * 登记 / 更新一个店铺在一个平台上的多渠道配送资质。
   *
   * <p>{@code status=AUTHORIZED} 必须给核验时间与来源出处 ——
   * 缺货自动切自发货受平台政策约束，这个出处就是合规依据。
   */
  @PostMapping("/mcf-authorizations")
  public McfSaved saveMcf(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody McfAuthorizationInput input) {
    return call(auth, "fulfillment.update", a -> routing.saveMcf(input, a.membershipId()));
  }

  // ============================================================ 成本与时效参数

  /** 成本与时效参数列表（真分页）。 */
  @GetMapping("/cost-profiles")
  public CostProfilePage costProfiles(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String pathKind,
      @RequestParam(required = false) String destinationCountryCode,
      @RequestParam(required = false, defaultValue = "false") Boolean effectiveOnly,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "fulfillment.read",
        a ->
            routing.pageCostProfiles(
                new CostProfileQuery(
                    platformCode, pathKind, destinationCountryCode, effectiveOnly, page, size)));
  }

  /** 维护一条成本与时效参数。 */
  @PostMapping("/cost-profiles")
  public CostProfileSaved saveCostProfile(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CostProfileInput input) {
    return call(auth, "fulfillment.update", a -> routing.saveCostProfile(input));
  }

  // ============================================================ 渠道库存快照

  /** 各渠道库存快照列表。 */
  @GetMapping("/stock-snapshots")
  public StockSnapshotPage stockSnapshots(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String skuRef,
      @RequestParam(required = false) String pathKind,
      @RequestParam(required = false) String stockSource,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "fulfillment.read",
        a ->
            routing.pageStockSnapshots(
                new StockSnapshotQuery(
                    platformCode, shopCode, skuRef, pathKind, stockSource, page, size)));
  }

  /** 登记一条渠道库存快照（带来源与采集时间）。 */
  @PostMapping("/stock-snapshots")
  public StockSnapshotSaved saveStockSnapshot(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody StockSnapshotInput input) {
    return call(auth, "fulfillment.update", a -> routing.saveStockSnapshot(input, a.membershipId()));
  }

  // ============================================================ 路径评估与决策

  /**
   * 路径评估（只读，不落库）：按当前库存、资质与成本参数算出「应该走哪条路」。
   *
   * <p>{@code platformAvailableQty} 可手工指定，用于「如果平台仓只有 N 件会怎么判」的预演；
   * 不传则读该 SKU 最新的平台仓库存快照。
   */
  @PostMapping("/evaluations")
  public RoutingEvaluation evaluate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody RoutingEvaluationRequest request) {
    return call(auth, "fulfillment.read", a -> routing.evaluate(request));
  }

  /**
   * 落一条履约路径决策（含候选快照；选了非平台仓时**同时**写缺货切换记录）。
   *
   * <p>没有切换记录的非平台仓决策在提交时会被 DB 拒绝（延迟约束触发器 23514）。
   */
  @PostMapping("/decisions")
  public RoutingDecisionSaved decide(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody RoutingDecisionRequest request) {
    return call(auth, "fulfillment.update", a -> routing.decide(request, a.membershipId()));
  }

  /** 决策列表（真分页；四组汇总额不受分页影响）。 */
  @GetMapping("/decisions")
  public DecisionPage decisions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String orderRef,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String skuRef,
      @RequestParam(required = false) String chosenPath,
      @RequestParam(required = false) String reasonCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "fulfillment.read",
        a ->
            routing.pageDecisions(
                new DecisionQuery(
                    orderRef, platformCode, shopCode, skuRef, chosenPath, reasonCode, status,
                    keyword, page, size)));
  }

  /** 决策详情：决策 + 候选快照 + 切换记录 + 事件流。跨租户在 RLS 下查不到 → 404。 */
  @GetMapping("/decisions/{decisionId}")
  public RoutingDecisionDetail decision(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID decisionId) {
    return call(auth, "fulfillment.read", a -> routing.decision(decisionId));
  }

  /** 推进决策状态（DECIDED → CONFIRMED / CANCELLED）。终态不可离开，作废必须给原因。 */
  @PostMapping("/decisions/{decisionId}/status")
  public RoutingDecisionSaved advanceStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID decisionId,
      @Valid @RequestBody DecisionStatusInput input) {
    return call(auth, "fulfillment.update", a -> routing.advanceStatus(decisionId, input, a.membershipId()));
  }

  // ============================================================ 缺货切换记录

  /**
   * 缺货切换记录列表：这是「为什么这单没走平台仓」的查询入口。
   *
   * <p>返回的每一行都带触发原因、当时的平台仓可用量与需求量 ——
   * 事后库存会被补上，只有这份快照能回答「当时缺多少」。
   */
  @GetMapping("/switches")
  public SwitchPage switches(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String orderRef,
      @RequestParam(required = false) UUID decisionId,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String triggerReason,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "fulfillment.read",
        a -> routing.pageSwitches(new SwitchQuery(orderRef, decisionId, platformCode, triggerReason, page, size)));
  }
}