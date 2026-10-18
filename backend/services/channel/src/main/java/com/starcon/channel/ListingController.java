package com.starcon.channel;

import static com.starcon.channel.ListingModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 4.1.1.1 Listing 列表。
 *
 * <p>蓝图：「按店铺/站点/状态/负责人/类目/是否有映射/是否缺货筛选平台商品，展示内部 SKU、价格、库存、销量、评分、近 7
 * 日状态变化，支持批量改价改库存（跳 05/07）、批量下架、批量分配负责人。输出：分页列表（含内部 SKU 映射状态、缺货标记、审核状态）；批量操作任务；点击进入
 * 4.1.1.2。」端点按蓝图模块号标注，方便对照 04-listing-and-content.md。
 *
 * <p>🔴 三个入口刻意分开，各自对应职责句里的一个词：
 *
 * <ul>
 *   <li>{@code GET /} —— 「输出：分页列表」。七个筛选维度各自一个 query 参数，真分页真筛选。
 *   <li>{@code POST /batch} —— 「批量操作任务」。改价 / 改库存被显式拒绝（归 05 / 07），
 *       且拒绝这件事本身落 {@code listing_batch_task} 留痕并回显。
 *   <li>{@code POST /} —— 「输入：{@code listing} + {@code listing_variant}」，即 02 同步 / 4.4.1
 *       采集写入的平台商品与变体。这是列表的数据来源。
 * </ul>
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 10.1.1.2 渠道配置、
 * 10.1.2.1 运费计算器、2.1.2.1 平台枚举映射、14.1.2.2 广告类型差异同族），identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/listings")
public class ListingController {

  private final AccessService access;
  private final ListingService service;

  public ListingController(AccessService access, ListingService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /**
   * 能力声明：价格 / 销量 / 内部可售量三个维度 available=false + ownerModule + 影响。
   *
   * <p>前端据此显示「价格能力未接入」，<b>不回 0、不显示假价格</b>。
   */
  @GetMapping("/capabilities")
  public ListingCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  /** 筛选面板的可选值：全部来自本服务的事实，不硬编码在前端。 */
  @GetMapping("/filter-options")
  public ListingFilterOptions filterOptions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.filterOptions());
  }

  // ---- 分页列表（「输出：分页列表」）----

  /**
   * 分页列表。七个筛选维度对应职责句的七个词：店铺 / 站点 / 状态 / 负责人 / 类目 /
   * 是否有映射（{@code mappingStatus}）/ 是否缺货（{@code outOfStock}）。
   *
   * <p>🔴 {@code mappingStatus} 与 {@code outOfStock} 是<b>两条互不影响的筛选</b>：
   * 一个已映射但平台缺货的商品必须能被同时筛出来。
   */
  @GetMapping
  public ListingPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String marketplaceCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String ownerId,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String mappingStatus,
      @RequestParam(required = false) Boolean outOfStock,
      @RequestParam(required = false) String search,
      @RequestParam(required = false, defaultValue = "RECENT") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.list(
                shopCode, marketplaceCode, status, ownerId, category,
                mappingStatus, outOfStock, search, sort, page, size));
  }

  /** 单条读取：列表行点进去的最小载荷。4.1.1.2 的全景详情是另一个模块，不在这里做。 */
  @GetMapping("/{id}")
  public ListingRow one(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.load(id));
  }

  // ---- 登记（「输入：listing + listing_variant」）----

  /** 登记 / 更新一条平台商品及其变体（02 同步、4.4.1 采集的写入入口）。 */
  @PostMapping
  public ListingRow save(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SaveListingRequest request) {
    return call(auth, "channel.operation.create", a -> service.save(request));
  }

  // ---- 批量操作（「批量操作任务」）----

  /**
   * 批量分配负责人 / 批量下架。
   *
   * <p>🔴 批量改价（REPRICE）与批量改库存（RESTOCK）**在本模块被显式拒绝**，
   * 返回 {@code outcome=REJECTED} + 归属模块 05 / 07，并落 {@code listing_batch_task} 留痕 ——
   * 蓝图职责句本身就写明「（跳 05/07）」。返回一个「成功但什么都没做」的任务比报错更糟。
   */
  @PostMapping("/batch")
  public ListingBatchResult batch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ListingBatchRequest request) {
    return call(auth, "channel.operation.update", a -> service.batch(request));
  }

  /** 批量操作任务列表（可回溯「谁在什么时候对哪一批做了什么」）。 */
  @GetMapping("/batch-tasks")
  public List<ListingBatchResult> batchTasks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.batchTasks(page, size));
  }
}
