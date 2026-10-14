package com.starcon.trade;

import static com.starcon.trade.PendingShipmentModels.*;

import jakarta.validation.Valid;
import java.util.Locale;
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
 * 9.4.1.1 待发货入口。
 *
 * <p>命名空间 {@code /trade/fulfillment/pending-shipment}。<b>刻意不挂在
 * {@code outbound-orders} 之下</b>：出库单是仓库侧履约单据（8.3.x），
 * 待发货是 trade 侧的发货看板 —— 两张表分属两个库，挂在一个命名空间下会让人
 * 以为能对对方库做 JOIN。同样<b>不并进</b> {@code fulfillment/routing} 与
 * {@code fulfillment/allocation}：那两个是「选路径」「选仓」，本模块是「选完之后怎么发」。
 *
 * <p>十三个形状，刻意逐个列出、不做通配：
 *
 * <ul>
 *   <li>{@code capabilities} 能力与上游可用性（含 SLA 无上游可读、8.3.4.2/8.3.4.1/10 未交付）
 *   <li>{@code POST /sync} 从 8.3.3.2 授权只读接口拉取已完成打包的出库单（幂等投影）
 *   <li>{@code GET /board} 分组看板（仓库 / 承运渠道 / 承运商 / 发货时限 四选一）
 *   <li>{@code GET /units} 待发货清单（真分页 + 真筛选 + 排序白名单 + 汇总）
 *   <li>{@code GET /units/{id}} 单元详情（单头 + 包裹 + 运单 + 时间线 + 完成判据）
 *   <li>{@code POST /units/{id}/sla} 登记发货时限（平台 SLA 无上游，登记而不伪造）
 *   <li>{@code POST /units/{id}/cancel} 作废单元（退回仓库，不产生发货记录）
 *   <li>{@code POST /batch-tasks} 建批量任务（**一次任务不许跨承运商**）
 *   <li>{@code GET /batch-tasks} 任务列表（真分页 + 真筛选 + 排序白名单）
 *   <li>{@code GET /batch-tasks/{id}} 任务详情（任务头 + 逐条结果 + 时间线）
 *   <li>{@code POST /batch-tasks/{id}/execute} 执行（逐条推进 + 状态由结果算出）
 *   <li>{@code POST /batch-tasks/{id}/waybill-acks} 登记承运商运单回执
 *   <li>{@code POST /batch-tasks/{id}/labels} 回填包裹面单打印凭据（8.3.4.2 未交付）
 * </ul>
 *
 * <p>刻意<b>没有</b> PUT / DELETE：待发货单元与批量任务都是带凭据的单据
 * （V124 的 REVOKE 与状态机触发器已经这么钉了），要更正就作废重开一张。
 *
 * <p>权限码<b>复用</b> {@code trade.fulfillment.read} / {@code trade.fulfillment.update}，
 * 不新起 {@code trade.shipment.*}：identity 只注册了既有的 fulfillment 页面资源权限码，
 * 用一个<b>没有被注册</b>的码会让连 OWNER 都 403（8.1.2.1 车道踩过这个坑）。
 */
@RestController
@RequestMapping("/trade/fulfillment/pending-shipment")
public class PendingShipmentController {

  private final AccessService access;
  private final PendingShipmentService pendingShipment;

  public PendingShipmentController(AccessService access, PendingShipmentService pendingShipment) {
    this.access = access;
    this.pendingShipment = pendingShipment;
  }

  /** 统一入口：先确认身份与权限（租户<b>不</b>来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "trade." + permission);
    return access.run(actor, "trade." + permission, () -> work.apply(actor));
  }

  /**
   * 能力声明：数据来源只有 8.3.3.2 授权只读 API、平台 SLA 无上游（登记而不伪造）、
   * 8.3.4.2 面单渲染与 8.3.4.1 交接 / 10 物流未交付、本模块最远只到 HANDED_OVER。
   */
  @GetMapping("/capabilities")
  public PendingShipmentCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "fulfillment.read", a -> pendingShipment.capabilities());
  }

  /**
   * 从 8.3.3.2 的授权只读接口拉取已完成打包的出库单，落成待发货投影（幂等）。
   *
   * <p><b>本服务不直连 warehouse 库</b>：V124 文件头第一节写明了为什么
   * （跨库视图、跨服务 SQL、跨服务 FK 三条都被 AGENTS.md 禁止），
   * 上游不可达时这里返回 503 而不是空清单。
   */
  @PostMapping("/sync")
  public PendingShipmentSyncResult sync(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PendingShipmentSyncInput input) {
    return call(
        auth,
        "fulfillment.update",
        a -> pendingShipment.sync(input, a.membershipId(), auth));
  }

  /** 分组看板：仓库 / 承运渠道 / 承运商 / 发货时限 四选一，真 GROUP BY。 */
  @GetMapping("/board")
  public PendingShipmentBoard board(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) String carrierChannel,
      @RequestParam(required = false) String carrierCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "CARRIER") String groupBy) {
    return call(
        auth,
        "fulfillment.read",
        a ->
            pendingShipment.board(
                warehouseId,
                carrierChannel,
                carrierCode,
                status,
                keyword,
                groupBy == null ? null : PendingShipmentGroupBy.valueOf(groupBy.toUpperCase(Locale.ROOT))));
  }

  /** 待发货清单（真分页 + 真筛选 + 排序白名单；汇总不受分页影响）。 */
  @GetMapping("/units")
  public PendingShipmentUnitPage units(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) String carrierChannel,
      @RequestParam(required = false) String carrierCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "PACKED_AT_DESC") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "fulfillment.read",
        a ->
            pendingShipment.units(
                warehouseId, carrierChannel, carrierCode, status, keyword, sort, page, size));
  }

  /** 单元详情：单头 + 包裹 + 运单 + 时间线 + 完成判据 + 边界声明。 */
  @GetMapping("/units/{id}")
  public PendingShipmentUnitDetail unit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "fulfillment.read", a -> pendingShipment.unit(id));
  }

  /** 登记发货时限 —— 上游没有平台 SLA 可读，所以登记（有主体、有时刻、有来源）而不是伪造。 */
  @PostMapping("/units/{id}/sla")
  public PendingShipmentUnitRow registerSla(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody ShipmentSlaInput input) {
    return call(
        auth,
        "fulfillment.update",
        a -> pendingShipment.registerSla(id, input, a.membershipId()));
  }

  /** 作废单元（退回仓库，**不**产生发货记录）。 */
  @PostMapping("/units/{id}/cancel")
  public PendingShipmentUnitRow cancelUnit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody ShipmentCancelInput input) {
    return call(
        auth,
        "fulfillment.update",
        a -> pendingShipment.cancelUnit(id, input, a.membershipId()));
  }

  /** 建批量任务：一次任务<b>不许跨承运商</b>（蓝图：混在一起会导致交接混乱）。 */
  @PostMapping("/batch-tasks")
  public ShipmentBatchTaskDetail createTask(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ShipmentBatchTaskInput input) {
    return call(
        auth,
        "fulfillment.update",
        a -> pendingShipment.createTask(input, a.membershipId()));
  }

  /** 批量任务列表（真分页 + 真筛选 + 排序白名单）。 */
  @GetMapping("/batch-tasks")
  public ShipmentBatchTaskPage tasks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String taskType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String carrierCode,
      @RequestParam(required = false, defaultValue = "CREATED_AT_DESC") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "fulfillment.read",
        a -> pendingShipment.tasks(taskType, status, carrierCode, sort, page, size));
  }

  /** 任务详情：任务头 + 逐条执行结果 + 时间线。 */
  @GetMapping("/batch-tasks/{id}")
  public ShipmentBatchTaskDetail task(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "fulfillment.read", a -> pendingShipment.task(id));
  }

  /** 执行：逐条推进单元状态，任务最终状态**由逐条结果算出**，不接受调用方直接指定。 */
  @PostMapping("/batch-tasks/{id}/execute")
  public ShipmentBatchTaskDetail execute(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(
        auth,
        "fulfillment.update",
        a -> pendingShipment.executeTask(id, a.membershipId()));
  }

  /** 登记承运商运单回执（逐条）：把 REQUESTED 推到 ISSUED 或 FAILED。 */
  @PostMapping("/batch-tasks/{id}/waybill-acks")
  public ShipmentBatchTaskDetail waybillAck(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestParam(required = false) UUID unitId,
      @Valid @RequestBody ShipmentWaybillAckInput input) {
    return call(
        auth,
        "fulfillment.update",
        a ->
            pendingShipment.recordWaybillAck(
                id, unitId == null ? null : unitId, input, a.membershipId()));
  }

  /** 取消批量任务（只在 PENDING / RUNNING 上可取消；终态是追责凭据，不可回退）。 */
  @PostMapping("/batch-tasks/{id}/cancel")
  public ShipmentBatchTaskDetail cancelTask(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody ShipmentCancelInput input) {
    return call(
        auth,
        "fulfillment.update",
        a -> pendingShipment.cancelTask(id, input, a.membershipId()));
  }

  /**
   * 回填包裹面单打印凭据（8.3.4.2 未交付，本模块不渲染 ZPL）。
   *
   * <p>没有真实 jobRef 的包裹不会被标成「已打单」—— 批量打单任务里那一条会显式
   * 记为<b>跳过</b>并写明原因，而不是伪装成成功。
   */
  @PostMapping("/batch-tasks/{id}/labels")
  public ShipmentBatchTaskDetail recordLabel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestParam(required = false) UUID unitId,
      @RequestParam(required = false) UUID parcelId,
      @Valid @RequestBody PendingShipmentLabelInput input) {
    return call(
        auth,
        "fulfillment.update",
        a ->
            pendingShipment.recordLabelPrinted(
                id, unitId, parcelId, input, a.membershipId()));
  }
}