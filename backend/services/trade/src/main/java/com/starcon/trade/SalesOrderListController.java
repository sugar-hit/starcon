package com.starcon.trade;

import static com.starcon.trade.SalesOrderListModels.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 9.1.1.1 订单列表（全渠道统一订单列表）。
 *
 * <p><b>命名空间是 {@code /trade/sales-orders/list}，与 9.1.2.2 并列而不是覆盖。</b> 9.1.2.2 已经占了 {@code GET
 * /trade/sales-orders}（手工单列表）与 {@code GET /trade/sales-orders/capabilities}（手工单能力声明）。
 * 本模块是<b>全渠道统一</b>列表，读的是<b>同一批表、但维度与语义都不同</b> （蓝图 9.1.1 订单查询 与 9.1.2 订单来源 是并列的两个 L4），所以：
 *
 * <ul>
 *   <li><b>不覆盖</b> 9.1.2.2 的两个端点 —— 它们已交付、有测试、有前端页面在用；
 *   <li><b>不新开</b> {@code /trade/orders} —— 该路径被 {@code TradeTenancyIntegrationTest} 断言 2
 *       <b>显式预留</b>（GET 与 POST 都必须 404）， 占了它会让一条<b>已通过的测试</b>变红，而那个测试文件属于别的模块；
 *   <li>故取 {@code /trade/sales-orders/list}：同一服务命名空间下的并列子资源。
 * </ul>
 *
 * <p>权限码用 {@code trade.orders.read}：本模块<b>只有读</b>。蓝图职责句里的 「审单 / 备注 / 取消 / 发催付」四条批量操作全部 {@code
 * available=false} （见 {@code SalesOrderListService#capabilities} 的逐条理由）， 本控制器<b>刻意不提供任何写端点</b> ——
 * 本模块是 sales_order 的读投影， 它的写入所有者是 9.1.2.2 与将来的 9.1.2.1。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限， 再在一个事务里绑定 {@code
 * app.current_tenant}，由 RLS 在数据库层强制隔离。 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>「只看我自己的订单」（{@code SELF} 数据范围）由服务端从角色授权推导， <b>不接受请求参数</b>传入 —— 否则它会变成一个能越权的开关。
 */
@RestController
@RequestMapping("/trade/sales-orders/list")
public class SalesOrderListController {

  private static final String READ = "orders.read";

  private final AccessService access;
  private final SalesOrderListService orders;

  public SalesOrderListController(AccessService access, SalesOrderListService orders) {
    this.access = access;
    this.orders = orders;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, "trade." + READ);
    return access.run(actor, "trade." + READ, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /**
   * 蓝图要而本服务拿不到的六个维度，以及四条不可用的批量操作。
   *
   * <p>让界面能说清「为什么没有支付状态 / 履约状态 / 发货仓 / 平台状态 / 售后状态」， 而不是让它们显示成空白让人以为「这单没有这些值」。
   */
  @GetMapping("/capabilities")
  public SalesOrderListCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> orders.capabilities());
  }

  /** 筛选项下拉用的租户级词表（币种来自真实数据，不是硬编码）。 */
  @GetMapping("/filters")
  public SalesOrderListFilterOptions filters(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> orders.filterOptions());
  }

  // ================================================================ 列表

  /**
   * 全渠道统一订单列表：真分页 + 与分页无关的全量汇总。
   *
   * <p>每个筛选参数都对应一个<b>真实存在</b>的列或聚合。蓝图点名的平台、店铺、 支付状态、履约状态、发货仓、售后状态<b>刻意不作为参数存在</b> ——
   * 提供一个永远筛不出东西的参数比不提供更糟。
   */
  @GetMapping
  public SalesOrderListPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String orderSource,
      @RequestParam(required = false) String documentStatus,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) UUID customerPartyId,
      @RequestParam(required = false) String buyerKeyword,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) OffsetDateTime orderedFrom,
      @RequestParam(required = false) OffsetDateTime orderedTo,
      @RequestParam(required = false) BigDecimal minAmount,
      @RequestParam(required = false) BigDecimal maxAmount,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        actor ->
            orders.list(
                new SalesOrderListQuery(
                    orderSource,
                    documentStatus,
                    currency,
                    customerPartyId,
                    buyerKeyword,
                    skuId,
                    orderedFrom,
                    orderedTo,
                    minAmount,
                    maxAmount,
                    keyword,
                    sort,
                    page,
                    size,
                    // 下面两个来自已认证身份与角色授权，**不是请求参数**。
                    actor.userId(),
                    selfOnly(actor))));
  }

  /**
   * 数据范围是否为「仅本人」。
   *
   * <p>蓝图把「用户数据范围」列为本模块的输入之一，所以它必须<b>真的生效</b>， 而不是只挂在页面上。取值规则与 {@code AccessService.require} 同一套授权：
   * 命中的授权里只要有一条 ALL 就按 ALL，否则按 SELF 收窄到本人经手过的订单。
   *
   * <p>订单没有部门维度（V99 的 sales_order 簇里没有 department 列）， 所以 DEPARTMENT 退化到 SELF 而不是本租户全量 ——
   * 退化方向刻意选<b>更严</b>的一侧。
   */
  static boolean selfOnly(AccessService.Access a) {
    String read = "trade." + READ;
    var grants = a.grants() == null ? java.util.List.<AccessService.Grant>of() : a.grants();
    boolean anyAll =
        grants.stream()
            .anyMatch(
                g ->
                    g.scopeMode().equals("ALL")
                        && g.permissions().contains(read)
                        && g.permissions().contains("trade.orders.read"));
    return !anyAll;
  }
}
