package com.starcon.trade;

import static com.starcon.trade.AnomalyOrderModels.*;

import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 1.1.1.2 异常订单队列 —— 工作台（卷 01）的**只读**入口。
 *
 * <p>命名空间 {@code /trade/anomaly-orders}：与 09 自己的
 * {@code /trade/orders/pending-review}（待审队列）、{@code /trade/order-intercept}（异常拦截）、
 * {@code /trade/orders/risk-validation}（风控校验）分属不同命名空间 ——
 * 「这一单卡住了吗、卡了多久、该谁处理」与「这一单审得过吗、能不能发」是三件事，
 * 权限与日志都该分得开。
 *
 * <p>🔴 **本命名空间没有 POST / PUT / DELETE**：工作台只读 + 跳转。
 * 蓝图的「批量标记忽略 / 转人工 / 转售后 / 生成处理工单」全部落在**归属系统**，
 * 本模块只负责给出建议动作码并把归属模块点名（见 {@code /capabilities} 的 {@code actions}）。
 *
 * <p>权限码 {@code trade.orders.read}：本模块只读，与 09 其它读端点同一权限面。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/trade/anomaly-orders")
public class AnomalyOrderController {

  private final AccessService access;
  private final AnomalyOrderService anomalyOrders;

  public AnomalyOrderController(AccessService access, AnomalyOrderService anomalyOrders) {
    this.access = access;
    this.anomalyOrders = anomalyOrders;
  }

  /** 统一入口：先确认身份与权限（租户<b>不</b>来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "trade.orders.read");
    return access.run(actor, "trade.orders.read", () -> work.apply(actor));
  }

  /**
   * 能力声明：七类异常哪几类真接通、跨服务两类归谁、哪些动作没有归属入口。
   *
   * <p>存在的理由：工作台是**聚合层**，它读不到的东西必须**自己说**，
   * 而不是让页面从空列表或 404 里猜哪一环没通。
   */
  @GetMapping("/capabilities")
  public AnomalyOrderCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, a -> anomalyOrders.capabilities());
  }

  /** 筛选项：异常类型、店铺、订单来源、停留时长档位。 */
  @GetMapping("/options")
  public AnomalyOrderOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, a -> anomalyOrders.options());
  }

  /**
   * 异常订单清单：真分页 + 真筛选 + 真排序 + 汇总 + 数据截至时间。
   *
   * <p>⚠️ {@code anomalyType} 筛选**只对本服务侧五类有效**（跨服务两类的事实不在本服务，
   * 由前端合并层过滤）。前端对跨服务类型走「取全量 + 本地过滤」，口径写在
   * {@code capabilities.crossServiceSources} 里。
   */
  @GetMapping
  public AnomalyOrderPage page(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String anomalyType,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String orderSource,
      @RequestParam(required = false) Integer minDwellHours,
      @RequestParam(required = false) String sort,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        a ->
            anomalyOrders.page(
                anomalyType, shopCode, keyword, orderSource, minDwellHours, sort, page, size));
  }

  /** 单条明细：订单行 + 费用 + 状态留痕 + 逐条异常凭据 + 建议动作。跨租户 → 404。 */
  @GetMapping("/{orderId}")
  public AnomalyOrderDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId) {
    return call(auth, a -> anomalyOrders.detail(orderId));
  }
}
