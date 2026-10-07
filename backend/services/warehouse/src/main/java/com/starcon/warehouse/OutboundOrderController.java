package com.starcon.warehouse;

import static com.starcon.warehouse.OutboundOrderModels.*;

import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.List;
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
 * 8.3.1.1 出库单列表。
 *
 * <p>蓝图：「出库作业主列表：来源（销售/采购退货/调拨/加工出库）、SKU 行数、件数、状态
 * （待拣/拣货中/待复核/待打包/待发货/已发货/取消）、发货仓、承运渠道。」输入：「筛选条件
 * （仓库、来源、状态、时间）；用户数据范围。」输出：「分页列表 + 批量操作（生成拣货任务、
 * 打印、取消）。」
 *
 * <p>命名空间与 7.x 同一套：{@code /warehouse/inventory/...}，权限码
 * {@code warehouse.inventory.read} / {@code warehouse.inventory.update}。
 * 端点刻意独立成 {@code OutboundOrderController} 而不是并进 {@code InventoryController}：
 * 那是 7.1.x 的文件，8 条车道并行时各改各的才不会撞。
 *
 * <p><b>刻意没有 GET /outbound-orders/{id}</b>：单据详情是 8.3.1.2「出库单详情」的职责
 * （明细行与已拣/已发量、关联销售订单与买家地址、预占引用、包裹列表、作业时间线）。
 * 在这里放一个详情端点会让下游模块交付时不知道该改哪里；而列表要显示的六列
 * （来源、行数、件数、状态、发货仓、承运渠道）本来就都在列表行里。
 *
 * <p><b>没有 DELETE</b>：出库单是履约动作的凭证，只能取消（留痕），不能删掉。
 */
@RestController
@RequestMapping("/warehouse")
public class OutboundOrderController {

  private final AccessService access;
  private final OutboundOrderService orders;

  public OutboundOrderController(AccessService access, OutboundOrderService orders) {
    this.access = access;
    this.orders = orders;
  }

  /**
   * 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），
   * 再在一个事务里绑定 RLS 租户上下文执行查询（与 7.x 同一个 call）。
   */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  // ------------------------------------------------------------ 能力

  /**
   * 能力声明：四类来源、七态与转换矩阵、筛选维度、两种打印文档，以及四条如实说明
   * （拣货任务归 8.3.2.1、9 订单未交付故只存外部单号、数据范围实际口径、打印为何不含面单）。
   *
   * <p>前端据此渲染选项与提示，而不是把状态机硬编码在界面上。
   */
  @GetMapping("/inventory/outbound-orders/capabilities")
  public OutboundOrderCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> orders.capabilities(a));
  }

  // ------------------------------------------------------------ 列表

  /**
   * 出库单分页列表（真分页；四个筛选维度全部落在 SQL WHERE 上）。
   *
   * <p>仓库、来源、状态三者是**多选**（逗号分隔或重复 query 均可）；时间按 ISO-8601 带偏移量
   * 解析，格式不对直接 400，不按服务器本地时区猜。
   */
  @GetMapping("/inventory/outbound-orders")
  public OutboundOrderPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) List<UUID> warehouseIds,
      @RequestParam(required = false) List<String> sourceTypes,
      @RequestParam(required = false) List<String> statuses,
      @RequestParam(required = false) String createdFrom,
      @RequestParam(required = false) String createdTo,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    OffsetDateTime from = InventoryController.moment(createdFrom);
    OffsetDateTime to = InventoryController.moment(createdTo);
    if (from != null && to != null && !to.isAfter(from)) {
      throw new IllegalArgumentException("创建时间区间的起必须早于止。");
    }
    return call(
        auth,
        "inventory.read",
        a -> orders.list(warehouseIds, sourceTypes, statuses, from, to, keyword, page, size, a));
  }

  // ------------------------------------------------------------ 建单

  /**
   * 建立出库单（待拣）。
   *
   * <p>建单不在蓝图给 8.3.1.1 的职责清单里，但它是这张列表唯一诚实的数据来源 ——
   * 上游 9 订单履约尚未交付，不建单列表就永远是空的。它只落地「履约动作的载体」这件事，
   * 不代表 9 订单已交付：销售 / 采购退货 / 加工三类来源仍只存外部单号字符串。
   *
   * <p>调拨来源走真外键到 7.5.1.1 的 {@code transfer_order}，并校验发货仓与明细 SKU 对得上。
   */
  @PostMapping("/inventory/outbound-orders")
  public OutboundOrderSummary create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody OutboundOrderCreateInput input) {
    return call(auth, "inventory.update", a -> orders.create(input, a.userId()));
  }

  // ------------------------------------------------------------ 批量操作

  /**
   * 批量生成拣货任务：待拣 → 拣货中，并落一行 {@code outbound_pick_request} 交接请求。
   *
   * <p><b>只做到交接为止</b>：拣货任务表、拣货明细（实际库位/批次/序列号）、路径排序与
   * 异常上报都归 8.3.2.1。本模块不建那张表，也不假装它存在。
   *
   * <p>逐单独立处理，失败单带着可读原因回来 —— 批量操作里最危险的就是把「部分成功」
   * 当成「全部成功」。
   */
  @PostMapping("/inventory/outbound-orders/pick-requests")
  public OutboundPickRequestResult requestPicking(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody OutboundPickRequestInput input) {
    return call(
        auth,
        "inventory.update",
        a -> orders.requestPicking(input == null ? null : input.orderIds(), input == null ? null : input.note(), a.userId()));
  }

  /**
   * 批量打印：返回真正要印的内容（单头 + 明细行），并逐单留 PRINTED 审计。
   *
   * <p>只支持出库单与拣货单两种，都只用本模块自己持有的数据；面单/箱唛/标签属 8.3.4.2。
   */
  @PostMapping("/inventory/outbound-orders/print")
  public OutboundPrintResult print(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody OutboundPrintInput input) {
    return call(
        auth,
        "inventory.read",
        a -> orders.print(input == null ? null : input.orderIds(), input == null ? null : input.document(), a.userId()));
  }

  /**
   * 批量取消：原因必填（DB 级 CHECK 也要求非空），逐单独立处理。
   *
   * <p>已发货的单取消不了 —— 货已经交出去了，这不是能靠改状态挽回的操作。
   */
  @PostMapping("/inventory/outbound-orders/cancel")
  public OutboundCancelResult cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody OutboundCancelInput input) {
    return call(
        auth,
        "inventory.update",
        a -> orders.cancel(input == null ? null : input.orderIds(), input == null ? null : input.reason(), a.userId()));
  }

  /**
   * 状态推进（单张）：合法跃迁由 {@link OutboundOrderStatus} 的转换矩阵定义，
   * 非法跃迁 409，DB 触发器在库层再挡一次。
   *
   * <p>本模块是 {@code outbound_order.status} 的所有者，所以这张主表的状态机由本端点驱动。
   * 它<b>不等于</b>下游模块的工作：推到「待打包」不代表打过包。8.3.2.1 / 8.3.3.x / 8.3.4.1
   * 交付后由它们的端点在完成各自实体的同时调用同一套状态机。
   *
   * <p>「已发货」要求已有承运渠道；「取消」不走这里，要用取消端点单独提交原因。
   */
  @PostMapping("/inventory/outbound-orders/{orderId}/transition")
  public OutboundOrderSummary transition(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId,
      @RequestBody OutboundTransitionInput input) {
    return call(auth, "inventory.update", a -> orders.transition(orderId, input, a.userId()));
  }
}