package com.starcon.trade;

import static com.starcon.trade.ManualOrderModels.*;

import jakarta.validation.Valid;
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
 * 9.1.2.2 手工订单。
 *
 * <p><b>命名空间是 {@code /trade/sales-orders}，刻意不是 {@code /trade/orders}。</b> 蓝图 09 卷的前端入口是 {@code
 * /orders}，浏览器只经 /api/trade 代理访问；后端路径按 <b>服务</b>命名空间走，与 9.3.2.2（前端 /orders、后端
 * /trade/fulfillment/routing）、 6.4.1.1（前端 /purchase/processing、后端 /trade/processing）同一纪律。
 *
 * <p>为什么不是 {@code /trade/orders}：该路径已被 {@code TradeTenancyIntegrationTest} 的断言 2 <b>显式预留</b>给另一条车道
 * —— 它断言「GET 与 POST /trade/orders 都必须 404」，并注明第一条 9.x 端点车道落地后 那条断言要升级成「带合法会话 + 伪造 X-Tenant-Id →
 * 仍只看到会话租户的行」。 本车道占了它会让一条<b>已通过的测试</b>变红，而那个测试文件属于别的模块，不在本车道修改范围。
 *
 * <p>权限码用 {@code trade.orders.read} / {@code trade.orders.create} / {@code
 * trade.orders.update}：提交一张订单等于让审单与仓库按它工作， 不是读权限能带过去的动作。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限， 再在一个事务里绑定 {@code
 * app.current_tenant}，由 RLS 在数据库层强制隔离。 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/trade/sales-orders")
public class ManualOrderController {

  private final AccessService access;
  private final ManualOrderService orders;

  public ManualOrderController(AccessService access, ManualOrderService orders) {
    this.access = access;
    this.orders = orders;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "trade." + permission);
    return access.run(actor, "trade." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>把「价格来源有没有」「下游审单有没有接上」显式报出来，而不是让调用方 从 404 或空数组里去猜哪一环没通。
   */
  @GetMapping("/capabilities")
  public SalesOrderCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "orders.read", actor -> orders.capabilities());
  }

  // ================================================================ 读

  /** 订单列表：真分页 + 与分页无关的全量汇总（支持子类 / 状态 / 客户 / 支付方式 / 关键字）。 */
  @GetMapping
  public SalesOrderPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String manualKind,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID customerPartyId,
      @RequestParam(required = false) String paymentMethod,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "orders.read",
        actor ->
            orders.pageOrders(
                new SalesOrderQuery(
                    manualKind, status, customerPartyId, paymentMethod, keyword, page, size)));
  }

  /** 订单详情：一次调用返回整屏（头 + 客户 + 行 + 属性 + 地址 + 费用 + 事件流）。 */
  @GetMapping("/{id}")
  public SalesOrderDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "orders.read", actor -> orders.order(id));
  }

  // ================================================================ 写

  /**
   * 创建手工销售订单（来源固定 MANUAL，调用方不能选来源）。
   *
   * <p>刻意<b>不接受</b> orderSource 入参：让客户端能选来源等于开了一条「手工录入通道写平台单」 的后门，而外部订单号的唯一约束是<b>按来源维度</b>的（V99 的
   * {@code UNIQUE(tenant_id, order_source, external_order_no)}），来源被伪造就会撞掉平台单的 唯一性 ——
   * 更糟的是这张「平台单」根本没有平台履约链路可走。
   */
  @PostMapping
  public SalesOrderSaved create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SalesOrderCreateInput input) {
    return call(auth, "orders.create", actor -> orders.create(input, actor));
  }

  /** 更新草稿（整体替换客户 / 行 / 地址 / 费用）。提交后由 DB 触发器冻结。 */
  @PutMapping("/{id}")
  public SalesOrderSaved update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody SalesOrderUpdateInput input) {
    return call(auth, "orders.update", actor -> orders.update(id, input, actor));
  }

  /** 提交待审：DRAFT → SUBMITTED（9.2 审单的入口）。独立事务，提交后明细与金额冻结。 */
  @PostMapping("/{id}/submit")
  public SalesOrderSaved submit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "orders.update", actor -> orders.submit(id, actor));
  }
}
