package com.starcon.warehouse;

import static com.starcon.warehouse.InventoryBucketModels.*;
import static com.starcon.warehouse.WarehouseModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * 7.1.1.1 库存总览。
 *
 * <p>命名空间 {@code /warehouse}，与 catalog 的 {@code /catalog}、channel 的
 * {@code /channel} 同一套约定：每个服务暴露自己的前缀，浏览器只经 /api/warehouse 代理访问。
 *
 * <p>权限码前缀 {@code warehouse.}：总览读用 {@code warehouse.inventory.read}，
 * 阈值维护用 {@code warehouse.inventory.update}（写入超储线是经营决策，不是读权限）。
 */
@RestController
@RequestMapping("/warehouse")
public class InventoryController {
  private final AccessService access;
  private final InventoryOverviewService overview;
  private final InventoryBucketService buckets;
  private final WarehouseReferenceService references;
  private final InventoryAvailabilityService availability;

  public InventoryController(
      AccessService access,
      InventoryOverviewService overview,
      InventoryBucketService buckets,
      WarehouseReferenceService references,
      InventoryAvailabilityService availability) {
    this.access = access;
    this.overview = overview;
    this.buckets = buckets;
    this.references = references;
    this.availability = availability;
  }

  /**
   * 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），
   * 再在一个事务里绑定 RLS 租户上下文执行查询。
   */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  /**
   * 库存总览：汇总表 + 分组统计 + 合计。
   *
   * <p>纯聚合视图，不存独立数据 —— 响应里的每个数字都能在同一份余额数据上核对出来。
   */
  @GetMapping("/inventory/overview")
  public InventoryOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) UUID legalEntityId,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) List<UUID> categoryIds,
      @RequestParam(required = false) List<UUID> brandIds,
      @RequestParam(required = false) List<UUID> shopIds,
      @RequestParam(required = false) Boolean zeroStock,
      @RequestParam(required = false) Boolean overstock,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            overview.overview(
                new OverviewQuery(
                    warehouseId,
                    legalEntityId,
                    skuId,
                    keyword,
                    categoryIds,
                    brandIds,
                    shopIds,
                    zeroStock,
                    overstock,
                    sort,
                    page,
                    size)));
  }

  /**
   * 7.1.1.2 库存明细：bucket 级的最细粒度行 + 与余额的核对结果。
   *
   * <p>一行 = 法人货权 × SKU × 仓库 × 库位 × 批次 × 质量状态，正是蓝图定义的最小事实单位。
   * 读权限与总览同为 {@code warehouse.inventory.read}：明细不写任何数据，与总览同源同权。
   */
  @GetMapping("/inventory/buckets")
  public InventoryBucketDetail buckets(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) UUID legalEntityId,
      @RequestParam(required = false) UUID locationId,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) UUID lotId,
      @RequestParam(required = false) UUID qualityStatusId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Boolean onlyPickable,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            buckets.detail(
                new BucketDetailQuery(
                    legalEntityId,
                    warehouseId,
                    locationId,
                    skuId,
                    lotId,
                    qualityStatusId,
                    keyword,
                    onlyPickable,
                    sort,
                    page,
                    size)));
  }

  /** 行内操作入口（发起调整 / 发起调拨 / 查看流水）。目标模块未交付时 available=false。 */
  @GetMapping("/inventory/bucket-actions")
  public List<BucketAction> bucketActions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> buckets.actions());
  }

  /**
   * 批量可用量查询（3.5.2.2 BOM 展开消费）。
   *
   * <p>用 POST 而不是 GET：请求体是一组 SKU，展开树最坏 500 个 UUID，
   * 放 URL 里会撞长度限制，也会把一份清单塞进访问日志。
   *
   * <p>租户不来自请求体也不来自任何客户端头：{@link #call} 先向 identity 确认
   * 已认证成员关系与权限，再在一个事务里绑定 {@code app.current_tenant}，
   * 由 RLS 在数据库层强制隔离。catalog 侧必须透传调用者的 Authorization 头 ——
   * 丢了它这里就无从解析租户（这是跨服务调用丢掉租户上下文的直接后果）。
   */
  @PostMapping("/inventory/availability")
  public AvailabilityView availability(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody AvailabilityQuery query) {
    return call(auth, "inventory.read", a -> availability.availability(query));
  }

  /** 跨服务筛选维度接入情况：前端据此禁用不可用的筛选项，而不是让用户填完收 422。 */
  @GetMapping("/inventory/capabilities")
  public List<DimensionCapability> capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> overview.capabilities());
  }

  // ------------------------------------------------------------ 参照数据

  @GetMapping("/warehouses")
  public List<WarehouseRef> warehouses(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> references.warehouses());
  }

  @GetMapping("/legal-entities")
  public List<LegalEntityRef> legalEntities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> references.legalEntities());
  }

  @GetMapping("/skus")
  public List<SkuRef> skus(@RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> references.skus());
  }

  /**
   * 库位下拉（7.1.1.2 的逐级下钻第二级）。
   *
   * <p>按仓库过滤，让"先选仓、再选库位"这条下钻路径在 UI 上成立，而不是全租户库位混在一起。
   */
  @GetMapping("/locations")
  public List<LocationRef> locations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) String type) {
    return call(auth, "inventory.read", a -> buckets.locations(warehouseId, type));
  }

  /** 批次下拉（7.1.1.2 的逐级下钻最末一级）。 */
  @GetMapping("/lots")
  public List<LotRef> lots(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) String keyword) {
    return call(auth, "inventory.read", a -> buckets.lots(skuId, keyword));
  }

  @GetMapping("/quality-statuses")
  public List<QualityStatusRef> qualityStatuses(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> references.qualityStatuses());
  }

  // ------------------------------------------------------------ 超储阈值

  @GetMapping("/inventory/policies")
  public List<InventoryPolicy> policies(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String scopeType) {
    return call(auth, "inventory.read", a -> references.policies(scopeType));
  }

  @PostMapping("/inventory/policies")
  public InventoryPolicySaved createPolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody InventoryPolicyInput input) {
    return call(auth, "inventory.update", a -> references.savePolicy(null, input));
  }

  @PutMapping("/inventory/policies/{id}")
  public InventoryPolicySaved updatePolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody InventoryPolicyInput input) {
    return call(auth, "inventory.update", a -> references.savePolicy(id, input));
  }

  @DeleteMapping("/inventory/policies/{id}")
  public void deletePolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    call(
        auth,
        "inventory.update",
        a -> {
          references.deletePolicy(id);
          return null;
        });
  }
}
