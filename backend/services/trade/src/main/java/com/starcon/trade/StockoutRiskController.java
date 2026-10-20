package com.starcon.trade;

import static com.starcon.trade.StockoutRiskModels.*;

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
 * 6.2.1.1 缺货与断货风险。
 *
 * <p>命名空间 {@code /trade/stockout-risk}：后端命名空间**按服务走**（与 9.3.3.2 的
 * {@code /trade/fulfillment/monitor}、6.4.1.1 的 {@code /trade/processing} 同一纪律）。
 * 前端路由是 {@code /purchase/demand} —— 那是**前端**路由，与后端命名空间是两件事，
 * 与 9.3.2.2 前端 {@code /orders} ↔ 后端 {@code /trade/fulfillment/routing} 同款。
 *
 * <p>权限码沿用 {@code trade.<page>.<action>}：{@code trade.stockout.read} /
 * {@code trade.stockout.update}，与 trade 服务既有的 {@code trade.processing.*}、
 * {@code trade.fulfillment.*} 同一套命名。本车道**不改 identity 的权限真相源**
 * （那是另一个服务的迁移版本号），而是沿用 trade 服务既有的权限码体系。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/trade/stockout-risk")
public class StockoutRiskController {

  private final AccessService access;
  private final StockoutRiskService risk;

  public StockoutRiskController(AccessService access, StockoutRiskService risk) {
    this.access = access;
    this.risk = risk;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "trade." + permission);
    return access.run(actor, "trade." + permission, () -> work.apply(actor));
  }

  // ============================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>存在的理由：四个输入里有两个来自 warehouse、一个来自 channel，一个是本服务自建的登记。
   * 哪一个接通了必须由接口自己报出，而不是让调用方从空数组里猜。
   */
  @GetMapping("/capabilities")
  public StockoutCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "stockout.read", a -> risk.capabilities());
  }

  // ============================================================ 销售速度登记（输入事实）

  /** 销售速度登记列表（真分页 + 真筛选）。 */
  @GetMapping("/velocities")
  public StockoutVelocityPage velocities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String skuCode,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "false") Boolean effectiveOnly,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "stockout.read",
        a -> risk.pageVelocities(skuCode, keyword, Boolean.TRUE.equals(effectiveOnly), page, size));
  }

  /**
   * 登记一条销售速度（版本化：同一 SKU 的旧登记会被**收口**，不是被覆盖）。
   *
   * <p>覆盖会让「当时按哪个日均销算的断货日」永远查不回来，而那正是本模块的可追溯性承诺。
   */
  @PostMapping("/velocities")
  public StockoutVelocityRow registerVelocity(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody StockoutVelocityInput input) {
    return call(auth, "stockout.update", a -> risk.registerVelocity(input, a.userId()));
  }

  // ============================================================ 计算

  /**
   * 计算一次断货风险清单（落成一份快照）。
   *
   * <p>这是一次<b>读上游 + 写本地</b>的动作：可用量与在途来自 warehouse 的授权只读接口，
   * 涉及店铺来自 channel 的授权只读接口，销售速度来自本服务的登记事实表。
   * 跨服务一律走端口，禁跨库 SQL / FK / 视图。
   */
  @PostMapping("/snapshots")
  public StockoutComputeResult compute(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody StockoutComputeInput input) {
    return call(auth, "stockout.update", a -> risk.compute(input, a.userId(), auth));
  }

  /** 快照列表（真分页）。 */
  @GetMapping("/snapshots")
  public StockoutSnapshotPage snapshots(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "stockout.read", a -> risk.pageSnapshots(page, size));
  }

  // ============================================================ 风险清单

  /**
   * 断货风险清单（真分页 + 真筛选 + 按紧急度排序）。
   *
   * <p>不传 {@code snapshotId} 时取最近一次快照 —— 界面默认看到的是「最新的那份结论」，
   * 而不是一堆需要自己挑的历史快照。
   */
  @GetMapping("/items")
  public StockoutRiskItemPage items(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID snapshotId,
      @RequestParam(required = false) String urgency,
      @RequestParam(required = false) String skuCode,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "false") Boolean onlyShortage,
      @RequestParam(required = false, defaultValue = "false") Boolean onlyWithoutVelocity,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "stockout.read",
        a ->
            risk.pageItems(
                new StockoutRiskItemQuery(
                    snapshotId, urgency, skuCode, keyword, onlyShortage, onlyWithoutVelocity,
                    page, size)));
  }

  /** 风险行详情：结论 + 涉及店铺 + 计算过程（逐项拆解）。 */
  @GetMapping("/items/{id}")
  public StockoutRiskItemDetail item(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "stockout.read", a -> risk.itemDetail(id));
  }
}
