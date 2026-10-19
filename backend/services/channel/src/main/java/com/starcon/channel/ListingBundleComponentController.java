package com.starcon.channel;

import static com.starcon.channel.ListingBundleComponentModels.*;

import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * 4.1.2.3 套装组件映射。
 *
 * <p>蓝图：「当平台商品对应内部虚拟套装时，维护组件 SKU 与数量的映射，支持平台变体与不同套装版本对应，并校验组件 SKU 在目标仓库可售。输入：组件映射维护；套装版本选择。输出：<b>组件映射记录 + 可售性校验结果</b>。关系：上游 = 3.5.1 套装版本、4.1.2.1；下游 = 07 库存（按组件预占）。<b>组件数量必须大于 0</b>，缺任一组件时整个套装不可售。」
 *
 * <p>端点按蓝图模块号标注，方便对照 04-listing-and-content.md。每个入口各自对应职责句里的一个词：
 *
 * <ul>
 *   <li>{@code GET /bindings} —— 「平台变体与不同套装版本对应」的读出口。跨映射的真分页列表，
 *       带生效区间状态筛选（当前生效 / 已退役）。</li>
 *   <li>{@code POST /bindings} —— 输入句的「<b>套装版本选择</b>」。换版生成新绑定并退役旧的，
 *       <b>不就地改</b>：已在售的组件集合不会因为换版而被改写。</li>
 *   <li>{@code POST /components} / {@code PUT /components/{id}} / {@code DELETE /components/{id}}
 *       —— 输入句的「<b>组件映射维护</b>」。数量恒 &gt; 0，服务层先给可读原因、DB 再兜一道。</li>
 *   <li>{@code GET /components} —— 输出句的「<b>组件映射记录</b>」。真分页 + 筛选。</li>
 *   <li>{@code GET /bindings/{id}/sellable-check} —— 输出句的「<b>可售性校验结果</b>」。
 *       🔴 <b>目标仓库是必填参数</b>：可售量按仓库计算，不指定仓库就没有唯一答案。</li>
 * </ul>
 *
 * <p>🔴 <b>本控制器不提供任何能就地改写历史组件的入口</b>：已退役绑定的组件由 V186 的触发器挡死
 * （{@code guard_listing_bundle_component_frozen}），换版是唯一的改法 —— 这正是
 * 「内部套装升版不影响已在售商品」的物理落点。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.read} / {@code channel.operation.create}
 * / {@code channel.operation.update} / {@code channel.operation.delete}（与 4.1.2.1、4.1.2.2 同族），
 * identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/bundle-components")
public class ListingBundleComponentController {

  private final AccessService access;
  private final ListingBundleComponentService service;

  public ListingBundleComponentController(
      AccessService access, ListingBundleComponentService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(
      String bearer,
      String permission,
      java.util.function.Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ==================================================================
  // 能力声明 / 筛选项
  // ==================================================================

  @GetMapping("/capabilities")
  public ListingBundleComponentCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  @GetMapping("/filter-options")
  public ListingBundleFilterOptions filterOptions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.filterOptions());
  }

  // ==================================================================
  // 套装版本选择
  // ==================================================================

  /** 绑定列表：平台变体的套装映射当前（或曾经）指向哪个内部套装版本。 */
  @GetMapping("/bindings")
  public ListingBundleBindingPage bindings(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID mappingId,
      @RequestParam(required = false) UUID listingId,
      @RequestParam(required = false) UUID listingVariantId,
      @RequestParam(required = false) UUID bundleVersionId,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String effectiveState,
      @RequestParam(required = false) String search,
      @RequestParam(required = false, defaultValue = "RECENT") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.listBindings(
                mappingId, listingId, listingVariantId, bundleVersionId, shopCode, effectiveState,
                search, sort, page, size));
  }

  /** 单个绑定详情：套装版本选择 + 逐组件明细。 */
  @GetMapping("/bindings/{id}")
  public BundleBindingDetail binding(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.detail(id));
  }

  /**
   * 套装版本选择（写）。
   *
   * <p>🔴 换版**不就地改**：请求带 {@code retireCurrentAt} 时把当前生效的那一版关闭到这个时刻，
   * 新绑定另起一行。同一映射至多一个当前生效由数据库的部分唯一索引挡，并发下也成立。
   */
  @PostMapping("/bindings")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  public BundleBindingDetail saveBinding(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody SaveBundleBindingRequest request) {
    return call(
        auth, "channel.operation.create", a -> service.saveBinding(request, bearerOf(auth)));
  }

  // ==================================================================
  // 组件映射维护 + 记录
  // ==================================================================

  /** 组件映射记录（输出句）。真分页 + 按绑定 / SKU 筛选。 */
  @GetMapping("/components")
  public ListingBundleComponentPage components(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID bindingId,
      @RequestParam(required = false) UUID componentSkuId,
      @RequestParam(required = false) String search,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.listComponents(bindingId, componentSkuId, search, page, size));
  }

  /**
   * 新增一条组件（输入句的「组件映射维护」）。
   *
   * <p>🔴 {@code quantity} 必须 &gt; 0；组件 SKU 必须**确实属于**所选套装版本 ——
   * 这一点经 catalog 的授权 API 核对（{@code componentSkuId} 是逻辑 ID，数据库表达不了这层归属）。
   */
  @PostMapping("/components")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  public ListingBundleComponentRow addComponent(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody SaveBundleComponentRequest request) {
    return call(
        auth,
        "channel.operation.create",
        a -> service.saveComponent(request, bearerOf(auth)));
  }

  /** 改一条已登记的组件（数量 / 角色 / 备注）。同样受 qty &gt; 0 与构成归属两道约束。 */
  @PutMapping("/components/{id}")
  public ListingBundleComponentRow updateComponent(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody SaveBundleComponentRequest request) {
    return call(
        auth,
        "channel.operation.update",
        a ->
            service.saveComponent(
                new SaveBundleComponentRequest(
                    parseOrNull(id),
                    request.bindingId(),
                    request.componentSkuId(),
                    request.quantity(),
                    request.role(),
                    request.note()),
                bearerOf(auth)));
  }

  /**
   * 删除一条组件登记。
   *
   * <p>🔴 只对**当前生效**的绑定开放：已退役绑定的组件是历史（由 V186 触发器兜住）。
   * 响应带删除前的组件清单，界面能立刻看出「删到哪一步了、还够不够一套」。
   */
  @DeleteMapping("/components/{id}")
  public ListingBundleComponentDetail deleteComponent(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.delete", a -> service.removeComponent(id));
  }

  // ==================================================================
  // 可售性校验结果
  // ==================================================================

  /**
   * 校验组件 SKU 在目标仓库可售（输出句的「可售性校验结果」）。
   *
   * <p>🔴 <b>{@code warehouseId} 必填</b>：可售量按仓库计算，不指定仓库就没有唯一答案
   * （而不是"给个全量"）。
   *
   * <p>🔴 <b>「缺任一组件时整个套装不可售」</b>落在响应的 {@code bundleSellable} 上：
   * 逐行看每个组件都还有货，但只要有一个不够整套用量，这一整套就卖不出去。
   *
   * <p>🔴 上游 warehouse 不可达时**返回 503**，不返回「全部可售」——
   * 「我们查不到」被读成「能卖」，就是超卖的第一步。
   */
  @GetMapping("/bindings/{id}/sellable-check")
  public ListingBundleSellableCheck sellableCheck(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestParam UUID warehouseId) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.sellableCheck(id, warehouseId, bearerOf(auth)));
  }

  // ==================================================================
  // 辅助
  // ==================================================================

  /** 原样转发给上游端口的 bearer：租户仍由上游自己按已认证成员关系解析。 */
  private static String bearerOf(String auth) {
    return auth == null || auth.isBlank() ? "" : auth;
  }

  private static UUID parseOrNull(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (RuntimeException e) {
      return null;
    }
  }
}
