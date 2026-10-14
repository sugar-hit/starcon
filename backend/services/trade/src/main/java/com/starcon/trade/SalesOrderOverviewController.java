package com.starcon.trade;

import static com.starcon.trade.SalesOrderOverviewModels.*;

import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 9.1.1.2 订单详情（控制塔）。
 *
 * <p>蓝图加粗句：「<b>详情页是订单的『控制塔』</b>：客服、采购、财务都从这一个页面 理解这笔订单为什么长这样。」端点 3 个，前缀 {@code
 * /trade/sales-orders/detail}。
 *
 * <p><b>为什么前缀是 {@code /detail} 而不是裸 {@code /{id}}</b> （这是本控制器最要紧的一条命名空间决策）：
 *
 * <ul>
 *   <li><b>{@code GET /trade/sales-orders/{id}} 已被 9.1.2.2 占用</b> （{@code
 *       ManualOrderController.detail} → {@code SalesOrderDetail}， 是手工订单详情，已交付、有测试、有前端页面在用）。在同一个
 *       {@code @RequestMapping("/trade/sales-orders")} 下再加一个 {@code @GetMapping("/{id}")}，Spring
 *       会在<b>启动时</b>抛 ambiguous mapping 让整个 trade 服务起不来。
 *   <li>{@code /trade/orders} 被 {@code TradeTenancyIntegrationTest} 断言 2 <b>显式预留</b>（GET 与 POST 都必须
 *       404），占了它会让一条已通过的 测试变红，而那个测试文件属于别的模块。
 *   <li>故取 {@code /trade/sales-orders/detail} —— 与 9.1.1.1 的 {@code /trade/sales-orders/list}
 *       <b>同一纪律</b>：同一服务命名空间下的 并列子资源，谁都不覆盖谁。
 * </ul>
 *
 * <p>权限码用 {@code trade.orders.read}：本模块<b>只有读</b>。 蓝图输出句里的「操作入口」四条全部 {@code available=false} （见
 * {@code SalesOrderOverviewService#capabilities} 的逐条理由）， 本控制器<b>刻意不提供任何写端点</b> —— sales_order
 * 簇的写入所有者是 9.1.2.2 与将来的 9.1.2.1。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限， 再在一个事务里绑定 {@code
 * app.current_tenant}，由 RLS 在数据库层强制隔离。 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p><b>跨租户与不存在返回同一个 404</b> —— 让 A 拿 B 的 id 探测存在性 本身就是泄露面（8.3.1.2 已确立的约定）。
 */
@RestController
@RequestMapping("/trade/sales-orders/detail")
public class SalesOrderOverviewController {

  private static final String READ = "orders.read";

  private final AccessService access;
  private final SalesOrderOverviewService overview;

  public SalesOrderOverviewController(AccessService access, SalesOrderOverviewService overview) {
    this.access = access;
    this.overview = overview;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, "trade." + READ);
    return access.run(actor, "trade." + READ, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /**
   * 蓝图十一个分区里哪些有数据、哪些还没接上（连同四条操作入口）。
   *
   * <p>与 9.1.1.1 的 {@code /trade/sales-orders/list/capabilities} 分开：
   * 那个报<b>列表页</b>的筛选与排序边界，本个报<b>详情页</b>的分区边界。
   */
  @GetMapping("/capabilities")
  public SalesOrderOverviewCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> overview.capabilities());
  }

  // ================================================================ 详情

  /**
   * 订单全景详情：一次调用返回整屏。
   *
   * <p>「只看我经手过的订单」（{@code SELF} 数据范围）由服务端从角色授权推导， <b>不接受请求参数</b>传入 —— 否则它会变成一个能越权的开关。
   */
  @GetMapping("/{id}")
  public SalesOrderOverview detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(
        auth,
        actor ->
            overview.overview(
                id,
                // 下面两个来自已认证身份与角色授权，**不是请求参数**。
                actor.userId(),
                selfOnly(actor)));
  }

  // ================================================================ 履约引用（给 warehouse）

  /**
   * 给 warehouse（8.3.1.2 出库单详情）调用的授权 API：关联销售订单 + 买家地址。
   *
   * <p><b>边界纪律：warehouse 必须调这个端点，不得直连 trade 的表， 也不建跨库 FK 或视图</b>（AGENTS.md 模块边界纪律）。
   *
   * <p>查询键是<b>订单号 + 来源</b>：warehouse 的 {@code outbound_order.source_external_ref} 存的就是销售订单号字符串 （V72
   * 第 114 行），warehouse 手上没有 trade 的 uuid。
   *
   * <p>权限码同为 {@code trade.orders.read}：读销售订单的权限， 与读自己出库单的权限是<b>两回事</b>，warehouse 侧自己判断是否已获得。
   */
  @GetMapping("/fulfillment-reference")
  public SalesOrderOverviewFulfillmentReference fulfillmentReference(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String orderNo,
      @RequestParam String orderSource) {
    return call(auth, actor -> overview.fulfillmentReference(orderNo, orderSource));
  }

  /**
   * 数据范围是否为「仅本人」。
   *
   * <p>取值规则与 {@code AccessService.require} 同一套授权：命中的授权里只要有一条 ALL 就按全租户，否则按 SELF 收窄到本人经手过的订单。
   * 订单没有部门维度（V99 的 sales_order 簇里没有 department 列）， 所以 DEPARTMENT 退化到 SELF —— 退化方向刻意选<b>更严</b>的一侧。
   */
  static boolean selfOnly(AccessService.Access a) {
    String read = "trade." + READ;
    var grants = a.grants() == null ? java.util.List.<AccessService.Grant>of() : a.grants();
    boolean anyAll =
        grants.stream()
            .anyMatch(g -> g.scopeMode().equals("ALL") && g.permissions().contains(read));
    return !anyAll;
  }
}
