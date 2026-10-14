package com.starcon.warehouse;

import static com.starcon.warehouse.ShipmentHandoverModels.*;

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
 * 8.3.4.1 发货交接入口。
 *
 * <p>命名空间 {@code /warehouse/shipment-handovers}。<b>刻意不挂在 {@code outbound-orders} 之下</b>：
 * 出库单是履约单据（8.3.1.x），交接是现场作业凭据 —— 交接单要自带冻结的包裹、实测重量体积、 交接人与交接时刻，挂在出库单底下会让契约看起来像「一张单只有一次交接数据」（与
 * 8.3.3.2 打包单同一理由）。也不挂在 {@code packing-orders} 之下：打包单可以失败重开多张， 而交接单是「货真的出去了」的那一张。
 *
 * <p>七个形状，刻意逐个列出、不做通配：
 *
 * <ul>
 *   <li>{@code capabilities} 能力与边界声明（含「轨迹归 10」「平台回写归 2」两条口径）
 *   <li>{@code POST /} 开交接单（接<b>已打包</b>的打包单，冻结包裹与明细；<b>不动库存</b>）
 *   <li>{@code GET /} 列表（真分页 / 真筛选 / 排序白名单 / 汇总）
 *   <li>{@code GET /{id}} 详情（单头 + 包裹 + 明细 + 预占消费 + 时间线 + 预占候选 + 完成判据）
 *   <li>{@code GET /{id}/completion} 能不能交接（页面的唯一判据来源）
 *   <li>{@code POST /{id}/waybills} 登记承运商与运单号（单头主运单号 + 每箱运单号）
 *   <li>{@code POST /{id}/confirm} <b>交接确认 = 实发扣减 + 预占消费（同一事务）</b>
 *   <li>{@code POST /{id}/cancel} 作废（<b>库存不动</b>，本来也没扣）
 * </ul>
 *
 * <p>刻意<b>没有</b> PUT / DELETE：交接是事实，流水是 append-only（V123 的守卫会拒）， 作废一次就不能复活。要更正就作废重开一张 ——
 * 货已经交出去的更不许改写。
 *
 * <p>权限码<b>复用</b> {@code warehouse.inventory.read} / {@code warehouse.inventory.update}， 不新起 {@code
 * warehouse.handover.*}：identity 只注册了 {@code warehouse.inventory} 与 {@code warehouse.facilities}
 * 两个页面资源的权限码，用一个<b>没有被注册</b>的码会让 连 OWNER 都 403（8.1.2.1 车道踩过这个坑并在台账里记了原因）。
 */
@RestController
@RequestMapping("/warehouse/shipment-handovers")
public class ShipmentHandoverController {

  private final AccessService access;
  private final ShipmentHandoverService handover;

  public ShipmentHandoverController(AccessService access, ShipmentHandoverService handover) {
    this.access = access;
    this.handover = handover;
  }

  /** 统一入口：先确认身份与权限（租户<b>不</b>来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  /** 能力声明：预占转实扣的同事务口径、承运渠道与运单号的归属、序列号出库口径， 以及尚未交付的 10 轨迹 / 2 平台回写 / 9 订单推进。 */
  @GetMapping("/capabilities")
  public ShipmentHandoverCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> handover.capabilities());
  }

  /**
   * 开一张交接单：接一张<b>已打包</b>的打包单，把包裹与明细冻结到本单。
   *
   * <p><b>此刻库存一动不动</b> —— 实发扣减发生在 {@code /confirm} 那一个事务里。
   */
  @PostMapping
  public HandoverRow open(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody HandoverOpenInput input) {
    return call(auth, "inventory.update", a -> handover.open(input, a.userId()));
  }

  /** 交接单列表（真分页 + 真筛选 + 排序白名单 + 汇总，汇总不受筛选影响）。 */
  @GetMapping
  public HandoverPage page(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) UUID outboundOrderId,
      @RequestParam(required = false) UUID packingOrderId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String carrierCode,
      @RequestParam(required = false) UUID handoverUserId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "CREATED_AT") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            handover.page(
                warehouseId,
                outboundOrderId,
                packingOrderId,
                status,
                carrierCode,
                handoverUserId,
                keyword,
                sort,
                page,
                size));
  }

  /** 详情：包裹 + 明细 + 预占消费 + 时间线 + 可消费预占候选 + 完成判据 + 边界声明。 */
  @GetMapping("/{id}")
  public HandoverDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "inventory.read", a -> handover.detail(id));
  }

  /** 能不能交接确认 —— 页面的唯一判据来源（前端不自己算可用量，那等于和库存账各算一次）。 */
  @GetMapping("/{id}/completion")
  public HandoverCompletionReport completion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "inventory.read", a -> handover.completionOf(id));
  }

  /** 登记承运商与运单号（蓝图输入「包裹与运单号；承运渠道」）。<b>不动库存。</b> */
  @PostMapping("/{id}/waybills")
  public HandoverRow registerWaybills(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody HandoverWaybillInput input) {
    return call(auth, "inventory.update", a -> handover.registerWaybills(id, input, a.userId()));
  }

  /**
   * 交接确认 —— <b>实发扣减与预占消费在这一个事务里完成</b>。
   *
   * <p>同事务里做四件事：写出库台账（幂等锚点）、扣 {@code on_hand} 与 {@code reserved} 并写带符号分录、消费 {@code
   * reservation_allocation} 并落预占消费行、 带序列号的件置 SOLD。任一步失败整体回滚。
   */
  @PostMapping("/{id}/confirm")
  public HandoverConfirmed confirm(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody(required = false) HandoverConfirmInput input) {
    return call(
        auth,
        "inventory.update",
        a ->
            handover.confirm(
                id,
                input == null ? new HandoverConfirmInput(null, null, null, null, null) : input,
                a.userId()));
  }

  /** 作废交接单（货没交出去，<b>库存一分不动</b>）。原因必填。 */
  @PostMapping("/{id}/cancel")
  public HandoverRow cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody HandoverCancelInput input) {
    return call(auth, "inventory.update", a -> handover.cancel(id, input, a.userId()));
  }
}
