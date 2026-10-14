package com.starcon.warehouse;

import static com.starcon.warehouse.InventoryInTransitModels.*;

import jakarta.validation.Valid;
import java.util.List;
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
 * 7.5.1.2 在途库存。
 *
 * <p>命名空间 {@code /warehouse/inventory/in-transit}：与总览、明细、台账、核对、调拨同一前缀， 浏览器只经 /api/warehouse 代理访问。
 *
 * <p>权限码沿用 {@code warehouse.}：读用 {@code warehouse.inventory.read}， 三个写动作（登记外部在途 / 到货 / 差异登记）挂
 * {@code warehouse.inventory.update} —— 到货会真的往目的仓入库并写 RECEIPT 分录，比维护一条阈值危险得多。
 *
 * <p>租户**不**来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限， 再在一个事务里绑定 {@code app.current_tenant}，由
 * RLS 在数据库层强制隔离。
 *
 * <p>写动作是 **POST**（{@code /arrive}、{@code /discrepancy}）：它们都是「发生一次业务事件」 （到货、短少登记），用 PUT 会被 HTTP
 * 语义读成「把记录设置成某个状态」， 而实际发生的是库存入账 + 在途余量结算。
 */
@RestController
@RequestMapping("/warehouse/inventory/in-transit")
public class InventoryInTransitController {

  private final AccessService access;
  private final InventoryInTransitService inTransit;

  public InventoryInTransitController(AccessService access, InventoryInTransitService inTransit) {
    this.access = access;
    this.inTransit = inTransit;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  /**
   * 在途清单：调拨/头程/采购/FBA 发货四类**同一个口径**，附全量汇总与下拉词表。
   *
   * <p>汇总不受分页影响。未达量、已了结量、原发量三个数逐行返回： 「这批货现在在哪、什么时候到、还差多少」必须在一行里同时可读。
   */
  @GetMapping
  public InTransitPage page(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String transitKind,
      @RequestParam(required = false) UUID fromWarehouseId,
      @RequestParam(required = false) UUID toWarehouseId,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) List<String> overdueLevel,
      @RequestParam(required = false) Boolean overdueOnly,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            inTransit.page(
                new InTransitQuery(
                    transitKind,
                    fromWarehouseId,
                    toWarehouseId,
                    skuId,
                    overdueLevel,
                    overdueOnly,
                    keyword,
                    sort,
                    page,
                    size)));
  }

  /**
   * 到达预警（逾期未达）：按 ETA 从早到晚排，最该先追的在前。
   *
   * <p>档位与清单用**同一套阈值**（都是「ETA + 此刻 + 仍在途」算出来的）， 所以同一条在途腿在两个页面上不会给出互相矛盾的逾期档。
   */
  @GetMapping("/alerts")
  public InTransitAlerts alerts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> inTransit.alerts());
  }

  /**
   * 补货可扣在途净额（按目的仓 × SKU）。
   *
   * <p>6.2.1.1 算缺口时的直接输入：蓝图关系段要求「缺口计算必须扣在途」， 而在途漏算正是重复补货的根因。这里只交数，不给补货建议 —— 需求预测与下单不属本模块。
   */
  @GetMapping("/netting")
  public InTransitNettingPage netting(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID toWarehouseId,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth, "inventory.read", a -> inTransit.netting(toWarehouseId, skuId, sort, page, size));
  }

  /**
   * 在途腿详情：行 + 该腿的完整到达/差异时间线。
   *
   * <p>跨租户在 RLS 下查不到 → 404（403 会泄露「这批货存在」）。
   */
  @GetMapping("/{legId}")
  public InTransitDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID legId) {
    return call(auth, "inventory.read", a -> inTransit.detail(legId));
  }

  /**
   * 登记外部在途腿（头程发运 / 采购发运 / FBA 发货运单事件）。
   *
   * <p>调拨在途不在这里登记 —— 它由 7.5.1.1 调拨单的发出动作产生。 {@code sourceSystem + sourceReference}
   * 是幂等锚点，上游重复投递不会双记在途。
   *
   * <p>FBA 发货登记会**真的从我方发货仓扣减在手**（货已离仓）， 头程/采购登记不动在手（货还没进我方账），到货时才入库。
   */
  @PostMapping
  public InTransitSaved register(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody InTransitRegisterRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> inTransit.register(a.tenantId(), request == null ? emptyRegister() : request));
  }

  /** 到货：可分批到货；入库类到货必须给库位，落目的仓在手并写 RECEIPT 分录。 */
  @PostMapping("/{legId}/arrive")
  public InTransitSaved arrive(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID legId,
      @Valid @RequestBody InTransitArriveRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> inTransit.arrive(legId, request == null ? emptyArrive() : request));
  }

  /** 差异登记：把到不了的部分如实记为短少，在途随之归零。差异原因必填，不自动抹平。 */
  @PostMapping("/{legId}/discrepancy")
  public InTransitSaved discrepancy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID legId,
      @Valid @RequestBody InTransitDiscrepancyRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> inTransit.discrepancy(legId, request == null ? emptyDiscrepancy() : request));
  }

  // 空请求体落到这里：{@code @Valid} 对 null 不生效，必须由服务层报出可读错误
  // （例如「来源系统必填」），而不是 NPE。

  private static InTransitRegisterRequest emptyRegister() {
    return new InTransitRegisterRequest(
        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null);
  }

  private static InTransitArriveRequest emptyArrive() {
    return new InTransitArriveRequest(null, null, null, null, null, null);
  }

  private static InTransitDiscrepancyRequest emptyDiscrepancy() {
    return new InTransitDiscrepancyRequest(null, null, null, null, null);
  }
}
