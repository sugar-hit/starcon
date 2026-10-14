package com.starcon.trade;

import static com.starcon.trade.DropshipConfigModels.*;

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
 * 9.6.1.1 代发模式配置。
 *
 * <p>命名空间 {@code /trade/fulfillment/dropship}：与 9.3.2.2 的
 * {@code /trade/fulfillment/routing}、9.3.2.1 的 {@code /trade/fulfillment/allocation}
 * **并列而不是嵌套** —— 三者是同一条履约链上相邻但不同的三环：
 * 路由选「FBA 还是自发货」、分仓选「从哪个仓发」、代发配置决定「这个 SKU 走不走我方仓」。
 * 混在一个命名空间里会让三种决策在日志与权限上分不开。
 *
 * <p>权限码沿用 {@code trade.fulfillment.read} / {@code trade.fulfillment.update}：
 * 代发配置直接决定这票货走不走我方仓、货权记在谁名下、成本怎么算，
 * 不是读权限能带过去的动作。
 *
 * <p>租户**不**来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/trade/fulfillment/dropship")
public class DropshipConfigController {

  private final AccessService access;
  private final DropshipConfigService dropship;

  public DropshipConfigController(AccessService access, DropshipConfigService dropship) {
    this.access = access;
    this.dropship = dropship;
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
   * <p>存在的理由：把「哪些输入真有依据」显式报出来 —— 我方自有库存这一维度
   * **刻意不可用**（蓝图明写「代发不查自有库存」），供应商库存观测、店铺与报价
   * 走本服务投影。这些必须由接口自己说，而不是让调用方从 404 或空数组里猜。
   */
  @GetMapping("/capabilities")
  public DropshipCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "fulfillment.read", a -> dropship.capabilities());
  }

  // ============================================================ 店铺投影（2.3.1）

  /** 店铺投影列表。 */
  @GetMapping("/shop-refs")
  public DropshipShopRefPage shopRefs(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false, defaultValue = "false") Boolean activeOnly,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "fulfillment.read",
        a ->
            dropship.pageShopRefs(
                new DropshipShopRefQuery(shopCode, platformCode, activeOnly, keyword, page, size)));
  }

  /** 登记 / 更新一个店铺投影（2.3.1 channel 服务的只读副本）。 */
  @PostMapping("/shop-refs")
  public DropshipShopRefSaved saveShopRef(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody DropshipShopRefInput input) {
    return call(auth, "fulfillment.update", a -> dropship.saveShopRef(input));
  }

  // ============================================================ 供应商报价投影（3.6.2）

  /** 供应商报价投影列表。 */
  @GetMapping("/supplier-refs")
  public DropshipSupplierRefPage supplierRefs(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String supplierCode,
      @RequestParam(required = false) String quoteSource,
      @RequestParam(required = false, defaultValue = "false") Boolean activeOnly,
      @RequestParam(required = false, defaultValue = "false") Boolean quoteValidOnly,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "fulfillment.read",
        a ->
            dropship.pageSupplierRefs(
                new DropshipSupplierRefQuery(
                    supplierCode, quoteSource, activeOnly, quoteValidOnly, keyword, page, size)));
  }

  /** 登记 / 更新一个供应商报价投影（3.6.2 catalog 服务的只读副本）。 */
  @PostMapping("/supplier-refs")
  public DropshipSupplierRefSaved saveSupplierRef(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody DropshipSupplierRefInput input) {
    return call(auth, "fulfillment.update", a -> dropship.saveSupplierRef(input));
  }

  // ============================================================ 代发配置

  /** 代发配置列表。{@code countsAgainstOwnInventory=true} 时只回「占我方库存」的那些。 */
  @GetMapping("/configs")
  public DropshipConfigPage configs(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String configCode,
      @RequestParam(required = false) String scopeKind,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String skuRef,
      @RequestParam(required = false) String fulfillmentPath,
      @RequestParam(required = false) String ownershipMode,
      @RequestParam(required = false) String settlementBasis,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "false") Boolean effectiveOnly,
      @RequestParam(required = false) Boolean countsAgainstOwnInventory,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "fulfillment.read",
        a ->
            dropship.pageConfigs(
                new DropshipConfigQuery(
                    configCode, scopeKind, shopCode, skuRef, fulfillmentPath, ownershipMode,
                    settlementBasis, status, effectiveOnly, countsAgainstOwnInventory, keyword,
                    page, size)));
  }

  /**
   * 维护一条代发配置。
   *
   * <p>物流（{@code fulfillmentPath}）与货权（{@code ownershipMode}）两个维度分别校验：
   * 供应商直发必须有代发供应商；自营发货不得挂供应商、也不得声明纯代理；
   * 货权在我方时必须给出确认时间、确认人与确认依据。
   */
  @PostMapping("/configs")
  public DropshipConfigSaved saveConfig(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody DropshipConfigInput input) {
    return call(
        auth, "fulfillment.update", a -> dropship.saveConfig(input, a.membershipId()));
  }

  /** 配置详情：配置头 + SKU 明细行 + 事件流。跨租户在 RLS 下查不到 → 404。 */
  @GetMapping("/configs/{configId}")
  public DropshipConfigService.DropshipConfigDetail config(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID configId) {
    return call(auth, "fulfillment.read", a -> dropship.configDetail(configId));
  }

  /** 推进配置状态（DRAFT → ACTIVE → SUSPENDED ↔ ACTIVE → CLOSED）。 */
  @PostMapping("/configs/{configId}/status")
  public DropshipConfigSaved advanceStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID configId,
      @Valid @RequestBody DropshipConfigStatusInput input) {
    return call(
        auth,
        "fulfillment.update",
        a -> dropship.advanceStatus(configId, input, a.membershipId()));
  }

  /** 维护一条 SKU 明细行（可覆盖供货价 / 参考售价）。 */
  @PostMapping("/configs/{configId}/lines")
  public DropshipConfigLineSaved saveLine(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID configId,
      @Valid @RequestBody DropshipConfigLineInput input) {
    return call(auth, "fulfillment.update", a -> dropship.saveConfigLine(configId, input));
  }

  // ============================================================ 供应商库存观测

  /** 供应商可供货量列表。 */
  @GetMapping("/supplier-stocks")
  public DropshipSupplierStockPage supplierStocks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String supplierCode,
      @RequestParam(required = false) String skuRef,
      @RequestParam(required = false) String stockSource,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "fulfillment.read",
        a ->
            dropship.pageSupplierStocks(
                new DropshipSupplierStockQuery(
                    supplierCode, skuRef, stockSource, keyword, page, size)));
  }

  /** 登记 / 更新一个供应商可供货量观测值。 */
  @PostMapping("/supplier-stocks")
  public DropshipSupplierStockSaved saveSupplierStock(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody DropshipSupplierStockInput input) {
    return call(auth, "fulfillment.update", a -> dropship.saveSupplierStock(input));
  }

  // ============================================================ 可售标记

  /**
   * 评估一次代发可售性并落一条**不可改写**的判定凭据。
   *
   * <p>{@code sellable} 与 {@code listable} 分开返回：不可售不必然要下架。
   * 无法履约也是业务结论而不是请求失败，因此返回 200。
   */
  @PostMapping("/evaluations")
  public DropshipEvaluationSaved evaluate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody DropshipEvaluationRequest request) {
    return call(auth, "fulfillment.update", a -> dropship.evaluate(request));
  }

  /** 可售标记列表：某 SKU 到底缺什么，这里逐条给出。 */
  @GetMapping("/evaluations")
  public DropshipEvaluationPage evaluations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID configId,
      @RequestParam(required = false) String skuRef,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String supplierCode,
      @RequestParam(required = false) Boolean sellable,
      @RequestParam(required = false) Boolean listable,
      @RequestParam(required = false) String reasonCode,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "fulfillment.read",
        a ->
            dropship.pageEvaluations(
                new DropshipEvaluationQuery(
                    configId, skuRef, shopCode, supplierCode, sellable, listable, reasonCode,
                    page, size)));
  }

  // ============================================================ 事件流

  /** 事件流（只读；append-only，没有任何写形状）。 */
  @GetMapping("/events")
  public DropshipEventPage events(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID configId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "fulfillment.read", a -> dropship.pageEvents(configId, page, size));
  }
}
