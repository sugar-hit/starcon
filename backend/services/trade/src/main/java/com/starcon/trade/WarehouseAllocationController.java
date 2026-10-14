package com.starcon.trade;

import static com.starcon.trade.WarehouseAllocationModels.*;

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
 * 9.3.2.1 分仓规则。
 *
 * <p>命名空间 {@code /trade/fulfillment/allocation}：与 9.3.2.2 的
 * {@code /trade/fulfillment/routing} **并列而不是嵌套** —— 两者是同一条履约链上
 * 相邻但不同的两环（选履约路径 vs 选发货仓），混在一个命名空间里会让
 * 「路由决策」和「分仓决策」在日志与权限上分不开。api-proxy 白名单里也是新起一组，
 * 不并进已存在的 routing 组。
 *
 * <p>权限码沿用 {@code trade.fulfillment.read} / {@code trade.fulfillment.update}：
 * 执行分配直接决定这票货从哪个仓发、收入记在哪个法人名下，不是读权限能带过去的动作。
 *
 * <p>租户**不**来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/trade/fulfillment/allocation")
public class WarehouseAllocationController {

  private final AccessService access;
  private final WarehouseAllocationService allocation;

  public WarehouseAllocationController(AccessService access, WarehouseAllocationService allocation) {
    this.access = access;
    this.allocation = allocation;
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
   * <p>存在的理由：把「哪些输入真有依据」显式报出来 —— 各仓可用量在 warehouse 的库、
   * 运费试算未交付、订单与下游履约均未落地，这些都必须由接口自己说，
   * 而不是让调用方从 404 或空数组里猜。
   */
  @GetMapping("/capabilities")
  public WarehouseAllocationCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "fulfillment.read", a -> allocation.capabilities());
  }

  // ============================================================ 分仓规则配置

  /** 分仓规则列表。 */
  @GetMapping("/rules")
  public WarehouseAllocationRulePage rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String ruleCode,
      @RequestParam(required = false) String selectionPolicy,
      @RequestParam(required = false) String fallbackPolicy,
      @RequestParam(required = false, defaultValue = "false") Boolean effectiveOnly,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "fulfillment.read",
        a ->
            allocation.pageRules(
                new WarehouseAllocationRuleQuery(
                    ruleCode, selectionPolicy, fallbackPolicy, effectiveOnly, keyword, page, size)));
  }

  /**
   * 维护一条分仓规则（优先级、兜底策略与五维权重）。
   *
   * <p>五个权重合计必须为 1.0000；{@code requireLegalEntityMatch} 是货权硬闸，
   * 开启后法人不符的仓直接不可行。
   */
  @PostMapping("/rules")
  public WarehouseAllocationRuleSaved saveRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody WarehouseAllocationRuleInput input) {
    return call(auth, "fulfillment.update", a -> allocation.saveRule(input));
  }

  // ============================================================ 仓库档案（货权在这里）

  /** 仓库档案列表（含每个仓的货权法人）。 */
  @GetMapping("/warehouses")
  public WarehouseAllocationWarehousePage warehouses(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String legalEntityCode,
      @RequestParam(required = false) String regionCode,
      @RequestParam(required = false, defaultValue = "false") Boolean activeOnly,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "fulfillment.read",
        a ->
            allocation.pageWarehouses(
                new WarehouseAllocationWarehouseQuery(
                    legalEntityCode, regionCode, activeOnly, keyword, page, size)));
  }

  /**
   * 维护一个发货仓的档案。
   *
   * <p>{@code legalEntityCode} 必填：不知道货记在哪个法人名下时不允许建档 ——
   * 按「随便选个便宜的」发货，等于替财务做了一个错误决定。
   */
  @PostMapping("/warehouses")
  public WarehouseAllocationWarehouseSaved saveWarehouse(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody WarehouseAllocationWarehouseInput input) {
    return call(auth, "fulfillment.update", a -> allocation.saveWarehouse(input));
  }

  // ============================================================ 库存与运费估算

  /** 各仓可用量估算列表。 */
  @GetMapping("/stock-estimates")
  public WarehouseAllocationStockPage stockEstimates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String warehouseRef,
      @RequestParam(required = false) String skuRef,
      @RequestParam(required = false) String stockSource,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "fulfillment.read",
        a ->
            allocation.pageStockEstimates(
                new WarehouseAllocationStockQuery(
                    warehouseRef, skuRef, stockSource, keyword, page, size)));
  }

  /** 登记 / 更新一个仓的可用量估算（强制带来源与采集时间）。 */
  @PostMapping("/stock-estimates")
  public WarehouseAllocationStockSaved saveStockEstimate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody WarehouseAllocationStockInput input) {
    return call(auth, "fulfillment.update", a -> allocation.saveStockEstimate(input, a.membershipId()));
  }

  /** 运费与时效估算列表。 */
  @GetMapping("/shipping-estimates")
  public WarehouseAllocationShippingPage shippingEstimates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String warehouseRef,
      @RequestParam(required = false) String skuRef,
      @RequestParam(required = false) String destinationCountryCode,
      @RequestParam(required = false) String costSource,
      @RequestParam(required = false, defaultValue = "false") Boolean effectiveOnly,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "fulfillment.read",
        a ->
            allocation.pageShippingEstimates(
                new WarehouseAllocationShippingQuery(
                    warehouseRef, skuRef, destinationCountryCode, costSource, effectiveOnly,
                    page, size)));
  }

  /** 维护一条运费与时效估算。 */
  @PostMapping("/shipping-estimates")
  public WarehouseAllocationShippingSaved saveShippingEstimate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody WarehouseAllocationShippingInput input) {
    return call(
        auth, "fulfillment.update", a -> allocation.saveShippingEstimate(input, a.membershipId()));
  }

  // ============================================================ 试算与执行分配

  /**
   * 分仓试算（只读，**不落库**）：按当前档案、可用量、运费估算与规则算出
   * 「应该从哪个仓发、为什么、每个仓分别如何、哪些仓被排除」。
   */
  @PostMapping("/simulations")
  public WarehouseAllocationSimulation simulate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody WarehouseAllocationSimulationRequest request) {
    return call(auth, "fulfillment.read", a -> allocation.simulate(request));
  }

  /**
   * 执行一次分仓分配（落决策 + 逐仓候选 + 挂起或差额留痕 + 事件）。
   *
   * <p>无法分配时返回 200 与一条 {@code SUSPENDED} 分配：挂起是业务结论不是请求失败，
   * 调用方据此转人工而不是重试。
   */
  @PostMapping("/allocations")
  public WarehouseAllocationDecisionSaved allocate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody WarehouseAllocationDecisionRequest request) {
    return call(auth, "fulfillment.update", a -> allocation.allocate(request, a.membershipId()));
  }

  /** 分配列表（真分页；四组汇总额不受分页影响）。 */
  @GetMapping("/allocations")
  public WarehouseAllocationDecisionPage allocations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String orderRef,
      @RequestParam(required = false) String skuRef,
      @RequestParam(required = false) String chosenWarehouseRef,
      @RequestParam(required = false) String chosenLegalEntityCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String reasonCode,
      @RequestParam(required = false) Boolean crossLegalEntity,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "fulfillment.read",
        a ->
            allocation.pageDecisions(
                new WarehouseAllocationDecisionQuery(
                    orderRef, skuRef, chosenWarehouseRef, chosenLegalEntityCode, status, reasonCode,
                    crossLegalEntity, keyword, page, size)));
  }

  /** 分配详情：决策 + 逐仓候选 + 挂起记录 + 事件流。跨租户在 RLS 下查不到 → 404。 */
  @GetMapping("/allocations/{allocationId}")
  public WarehouseAllocationDecisionDetail allocation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID allocationId) {
    return call(auth, "fulfillment.read", a -> allocation.decision(allocationId));
  }

  /** 推进分配状态（ALLOCATED|PARTIAL → CONFIRMED → RELEASED）。挂起与已下发是终态。 */
  @PostMapping("/allocations/{allocationId}/status")
  public WarehouseAllocationDecisionSaved advanceStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID allocationId,
      @Valid @RequestBody WarehouseAllocationStatusInput input) {
    return call(auth, "fulfillment.update",
        a -> allocation.advanceStatus(allocationId, input, a.membershipId()));
  }

  // ============================================================ 挂起清单

  /**
   * 挂起清单：这是「哪几单发不出去」与「该去补什么」的查询入口。
   *
   * <p>每一行都带整体挂起原因与全量的逐仓排除清单 ——
   * 事后库存会被补上、运费估算会被改，只有这份快照能回答「当时缺什么」。
   */
  @GetMapping("/holds")
  public WarehouseAllocationHoldPage holds(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String orderRef,
      @RequestParam(required = false) UUID decisionId,
      @RequestParam(required = false) String holdReason,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "fulfillment.read",
        a ->
            allocation.pageHolds(
                new WarehouseAllocationHoldQuery(orderRef, decisionId, holdReason, keyword, page, size)));
  }
}