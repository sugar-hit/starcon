package com.starcon.warehouse;

import static com.starcon.warehouse.OutboundOrderDetailModels.*;

import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 8.3.1.2 出库单详情。
 *
 * <p>蓝图：「单出库单作业视图：明细行与已拣/已发量、关联销售订单与买家地址、预占引用、
 * 包裹列表、作业时间线。」输入：「outbound_order_id；关联对象。」输出：「详情 + 作业操作入口。」
 * 以及本模块被点名的那条关系：「**详情页串联整条出库链路**，作业人员不需要在多个页面跳。」
 *
 * <p>命名空间与 7.x / 8.3.1.1 同一套：{@code /warehouse/inventory/...}，权限码
 * {@code warehouse.inventory.read}（详情是纯读，不需要 update）。
 *
 * <p>端点刻意独立成 {@code OutboundOrderDetailController} 而不是并进
 * {@code OutboundOrderController}：那是 8.3.1.1 的文件，多条车道并行时各改各的才不会撞。
 * 8.3.1.1 的类注释里写明「刻意没有 GET /{id} 详情（属 8.3.1.2）」—— 这里就是那个端点。
 *
 * <p><b>没有写端点</b>：作业操作入口（合法下一步）由 8.3.1.1 的 transition / cancel / print /
 * pick-requests 四个端点执行。本模块只负责把「现在能做什么、去哪个模块做、前置条件是什么」
 * 讲清楚；在这里再开一套写端点，就是让同一个状态跃迁有两个入口。
 */
@RestController
@RequestMapping("/warehouse/inventory/outbound-orders")
public class OutboundOrderDetailController {

  private final AccessService access;
  private final OutboundOrderDetailService details;

  public OutboundOrderDetailController(AccessService access, OutboundOrderDetailService details) {
    this.access = access;
    this.details = details;
  }

  /**
   * 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），
   * 再在一个事务里绑定 RLS 租户上下文执行查询（与 7.x / 8.3.1.1 同一个 call）。
   */
  private <T> T call(String bearer, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse.inventory.read");
    return access.run(actor, "warehouse.inventory.read", () -> work.apply(actor));
  }

  /**
   * 详情页能力声明：六段内容的可用性、已拣/已发两个数量的口径，以及四条如实说明。
   *
   * <p>刻意与 8.3.1.1 的 {@code GET /outbound-orders/capabilities} **分开**：那个报的是
   * 列表与批量操作的能力，这个报的是详情页的形状与边界。两者并排读，才不会把
   * 「列表能做什么」当成「详情能做什么」。
   */
  @GetMapping("/detail-capabilities")
  public OutboundOrderDetailCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, a -> details.capabilities());
  }

  /**
   * 出库单详情。
   *
   * <p>一次调用返回整屏：单头 + 明细行 + 已拣/已发口径 + 拣货交接 + 作业时间线 +
   * 四段不可得能力 + 作业操作入口。
   *
   * <p>跨租户与不存在**返回同一个 404**：让 A 租户拿 B 租户的 id 探测存在性，
   * 本身就是一个信息泄露面。
   */
  @GetMapping("/{orderId}")
  public OutboundOrderDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId) {
    return call(auth, a -> details.detail(orderId));
  }
}