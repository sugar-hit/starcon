package com.starcon.warehouse;

import static com.starcon.warehouse.AvailableQuantityModels.*;

import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 7.1.1.3 可用量计算。
 *
 * <p>命名空间 {@code /warehouse/inventory/sellable}。
 *
 * <p><b>刻意不叫 {@code /inventory/availability}</b>：那个 POST 路径已经被 3.5.2.2 的
 * 「BOM 展开批量可用量查询」占着（{@code InventoryAvailabilityService}）。两个语义完全不同的
 * 端点挤在一个路径下，调用方迟早会把「按 SKU 批量查可用量」当成「算出可售量」，
 * 而后者带安全缓冲与排除口径 —— 那是一次防超卖口径的误用。本模块用自己的名字，
 * 也让 {@code /sellable} 与 7.2.2.1 的 {@code /sellable-policies} 在名字上就分得开。
 *
 * <p><b>三个端点</b>：
 *
 * <ul>
 *   <li>{@code GET /inventory/sellable} —— 口径报告：合计 + 构成拆解 + 能力声明 + 生效渠道口径。
 *       蓝图输出「可售量数值 + 构成拆解」就落在这里，一次取全，页面不用打三次请求
 *   <li>{@code GET /inventory/sellable/skus} —— 分 SKU 行（真分页 + 排序白名单）。
 *       独立成端点是因为它是**唯一会增长的列表**：可售量按 SKU 展开，行数可以上千
 *   <li>{@code GET /inventory/sellable/capabilities} —— 能力声明单独可取，
 *       因为它与库存数据无关（它只取决于哪些模块已交付）
 * </ul>
 *
 * <p><b>权限码统一 {@code warehouse.inventory.read}</b>：可售量是纯读，算式里没有任何写动作 ——
 * 它不写表（所以「算一次可售量」不构成一次库存业务，不需要走 {@code inventory.update}）。
 * 与 7.1.1.1 / 7.1.1.2 同源同权：同一个数从三个页面读出来必须是同一个数，
 * 而权限一分为二就会诱导出「总览看到的可售与明细看到的可售不一样」。
 *
 * <p><b>刻意没有 {@code at} / {@code asOf} 参数</b>：可售量不存历史快照
 * （见 {@link AvailableQuantityService} 类头），回看一个时刻没有定义。
 * 给了这个参数就是在承诺一件系统做不到的事。
 */
@RestController
@RequestMapping("/warehouse/inventory/sellable")
public class AvailableQuantityController {

  private final AccessService access;
  private final AvailableQuantityService quantities;

  public AvailableQuantityController(AccessService access, AvailableQuantityService quantities) {
    this.access = access;
    this.quantities = quantities;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），再绑定 RLS 上下文。 */
  private <T> T call(String bearer, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse.inventory.read");
    return access.run(actor, "warehouse.inventory.read", () -> work.apply(actor));
  }

  /**
   * 口径报告：可售量数值 + 构成拆解 + 能力声明 + 生效渠道口径。
   *
   * <p>{@code sliceBalanced=false} 是本端点最重要的一个布尔量：它为 false 意味着五片之和
   * 不等于 available，口径出了问题 —— 这种时候界面必须把它顶到第一屏，而不是安静地展示一个
   * 已经不自洽的可售量。
   */
  @GetMapping
  public AvailableQuantityReport report(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) UUID legalEntityId,
      @RequestParam(required = false) UUID locationId,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) UUID qualityStatusId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String channelScopeType,
      @RequestParam(required = false) UUID channelReferenceId) {
    return call(
        auth,
        a ->
            quantities.report(
                new AvailableQuantityQuery(
                    warehouseId,
                    legalEntityId,
                    locationId,
                    skuId,
                    qualityStatusId,
                    keyword,
                    channelScopeType,
                    channelReferenceId,
                    null,
                    null,
                    null)));
  }

  /**
   * 分 SKU 的可售量。真分页；排序键非法报 400 而不是静默按默认序返回。
   *
   * <p>{@code totals} 是<b>全范围</b>合计，不受分页与排序影响 —— 与 7.1.1.2 的
   * {@code summary} 同一纪律：合计要能用来核对，翻页时它不能变。
   */
  @GetMapping("/skus")
  public AvailableQuantityPage skus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) UUID legalEntityId,
      @RequestParam(required = false) UUID locationId,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) UUID qualityStatusId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String channelScopeType,
      @RequestParam(required = false) UUID channelReferenceId,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        a ->
            quantities.skuRows(
                new AvailableQuantityQuery(
                    warehouseId,
                    legalEntityId,
                    locationId,
                    skuId,
                    qualityStatusId,
                    keyword,
                    channelScopeType,
                    channelReferenceId,
                    sort,
                    page,
                    size)));
  }

  /**
   * 能力声明：哪些排除口径与缓冲口径已接入、哪些还没有及归属模块。
   *
   * <p>前端据此禁用不可用的筛选并在界面上讲清原因，而不是让用户填完再收一个 422。
   * 特别地 {@code expiryByDateAvailable} 与 {@code reservationReleaseAvailable} 恒为 false ——
   * 这两条如实报出来，比「悄悄按一条直觉剔货」或「悄悄把过期预占留着不解释」都要安全。
   */
  @GetMapping("/capabilities")
  public AvailableQuantityCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, a -> quantities.capabilities());
  }
}