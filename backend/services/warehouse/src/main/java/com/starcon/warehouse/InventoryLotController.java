package com.starcon.warehouse;

import static com.starcon.warehouse.InventoryLedgerModels.*;
import static com.starcon.warehouse.InventoryLotModels.*;

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
 * 7.3.1.1 批次管理。
 *
 * <p>命名空间 {@code /warehouse/inventory/lots}：与总览、明细、台账、核对同一前缀，
 * 浏览器只经 /api/warehouse 代理访问。刻意**不挂在 {@code /warehouse/lots}** —— 那是 7.1.1.2
 * 明细页的批次下拉（{@code InventoryController.lots}），一个只读精简列表，
 * 与本模块的完整档案不是同一个东西。
 *
 * <p>权限码沿用 {@code warehouse.}：读用 {@code warehouse.inventory.read}（与总览/明细/台账同源同权），
 * 建立与修改用 {@code warehouse.inventory.update} —— 建立批次是收货/建档流程的一环，
 * 不是读权限能带过去的动作。
 *
 * <p>租户**不**来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 */
@RestController
@RequestMapping("/warehouse/inventory/lots")
public class InventoryLotController {

  private final AccessService access;
  private final InventoryLotService lots;

  public InventoryLotController(AccessService access, InventoryLotService lots) {
    this.access = access;
    this.lots = lots;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  /**
   * 批次档案列表 + 全量汇总（**合计不受分页影响**）。
   *
   * <p>{@code expiringWithinDays} 是**读侧**筛选（取数），临期预警与 FEFO 出库推荐属 7.3.1.2，
   * 本模块不产生任何预警。
   */
  @GetMapping
  public LotArchivePage lots(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) List<String> status,
      @RequestParam(required = false) List<String> origin,
      @RequestParam(required = false) List<String> sourceSystem,
      @RequestParam(required = false) UUID supplierRefId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Integer expiringWithinDays,
      @RequestParam(required = false) Boolean inStock,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            lots.page(
                new LotArchiveQuery(
                    skuId,
                    warehouseId,
                    status,
                    origin,
                    sourceSystem,
                    supplierRefId,
                    keyword,
                    expiringWithinDays,
                    inStock,
                    sort,
                    page,
                    size)));
  }

  /**
   * 单个批次的档案详情。跨租户在 RLS 下查不到 → 404（403 会泄露「这个批次存在」）。
   */
  @GetMapping("/{lotId}")
  public LotArchiveRow archive(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID lotId) {
    return call(auth, "inventory.read", a -> lots.archive(lotId));
  }

  /** 批次库存分布：仓库级 + 库位级，真从 stock_bucket 聚合。 */
  @GetMapping("/{lotId}/distribution")
  public LotDistribution distribution(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID lotId) {
    return call(auth, "inventory.read", a -> lots.distribution(lotId));
  }

  /**
   * 批次出入库历史 —— 复用 7.1.2.1 库存流水台账的分录查询，**不另写一套流水查询**。
   *
   * <p>时间参数与台账端点同一套规则：必须带偏移量的 ISO-8601，不按服务器本地时区猜。
   *
   * <p>数据范围（SELF）由角色授权推导，与台账列表完全一致：actorId 取自已认证身份，
   * **不是**客户端参数 —— 否则「只看我自己的流水」会变成一个能越权的开关。
   */
  @GetMapping("/{lotId}/movements")
  public LotMovements movements(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID lotId,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) List<String> kinds,
      @RequestParam(required = false) List<String> refTypes,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            lots.movements(
                lotId,
                new InventoryLedgerQuery(
                    InventoryController.moment(from),
                    InventoryController.moment(to),
                    null,
                    warehouseId,
                    kinds,
                    refTypes,
                    keyword,
                    sort,
                    page,
                    size,
                    a.userId(),
                    InventoryController.selfOnly(a, "warehouse.inventory.read"))));
  }

  /**
   * 建立批次：收货生成（8 收货调用，origin=RECEIPT）与手工建立（origin=MANUAL）共用这一条。
   *
   * <p>用 POST：它真的落库。收货侧调同一端点建立批次，来源单据 id 走
   * {@code sourceSystem + sourceRefId} 跨服务定位键 —— 采购(6)/收货(8) 不在本服务，
   * 本模块既不建外键也不联查它们的表。
   */
  @PostMapping
  public LotArchiveSaved create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody LotArchiveCreateRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> lots.create(a.tenantId(), request == null ? emptyCreate() : request));
  }

  /**
   * 修改批次属性。
   *
   * <p>用 PUT：资源整体替换语义，但**批次身份三件套（批号 / SKU / origin）不可改** ——
   * 改了等于换了一个批次，而库位上的 lot_id 还指着旧身份。要换批号请新建批次。
   * 可改属性中 null 表示不修改（见 {@code LotArchiveUpdateRequest}）。
   */
  @PutMapping("/{lotId}")
  public LotArchiveSaved update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID lotId,
      @Valid @RequestBody LotArchiveUpdateRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> lots.update(lotId, request == null ? emptyUpdate() : request));
  }

  /** 空请求体落到这里：{@code @Valid} 对 null 不生效，必须由服务层报出「批号不能为空」这类可读错误。 */
  private static LotArchiveCreateRequest emptyCreate() {
    return new LotArchiveCreateRequest(
        null, null, null, null, null, null, null, null, null, null, null, null);
  }

  private static LotArchiveUpdateRequest emptyUpdate() {
    return new LotArchiveUpdateRequest(null, null, null, null, null, null);
  }
}
