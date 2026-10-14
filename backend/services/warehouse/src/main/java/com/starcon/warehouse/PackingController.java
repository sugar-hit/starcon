package com.starcon.warehouse;

import static com.starcon.warehouse.PackingModels.*;

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
 * 8.3.3.2 打包与称重入口。
 *
 * <p>命名空间 {@code /warehouse/packing-orders}。<b>刻意不挂在 {@code outbound-orders} 之下</b>：
 * 出库单是履约单据（8.3.1.x），打包是现场作业工单 —— 一张出库单可以先打包失败退回复核、 再重打包，每次是一张独立的打包单，挂���出库单底下会让契约看起来像「一张单只装一次」 （与
 * 8.3.3.1 复核单同一理由）。也不挂在 {@code recount-scans} 之下：打包单的上游是 一张<b>已通过</b>的复核单，而复核可以有多轮。
 *
 * <p>十四个形状，刻意逐个列出、不做通配：
 *
 * <ul>
 *   <li>{@code capabilities} 能力与边界声明（含「体积重归 10 物流」「3.7.2 属 catalog 未接入」两条口径）
 *   <li>{@code POST /materials}、{@code GET /materials}、{@code GET /materials/{id}}、{@code POST
 *       /materials/{id}/retire}
 *   <li>{@code POST /} 开打包单（接 <b>已通过</b>的复核单 + 冻结基线）
 *   <li>{@code GET /} 列表（真分页 / 真筛选 / 排序白名单 / 汇总）
 *   <li>{@code GET /{id}} 详情（单头 + 包裹 + 明细 + 基线对齐 + 时间线 + 完成判据）
 *   <li>{@code GET /{id}/completion} 能不能完成（页面的唯一判据来源）
 *   <li>{@code POST /{id}/parcels} 建包裹（选包材与填充物）
 *   <li>{@code POST /{id}/parcels/{parcelId}/items} 装明细（<b>必须指回复核基线行</b>）
 *   <li>{@code POST /{id}/parcels/{parcelId}/measure} 称重与量尺寸（实测重量体积）
 *   <li>{@code POST /{id}/parcels/{parcelId}/label} 记面单打印（引用 8.3.4.2 的打印任务）
 *   <li>{@code POST /{id}/complete} 完成（→ 出库单<b>待发货</b>，<b>不是已发货</b>）
 *   <li>{@code POST /{id}/fail} 判失败（→ 出库单退回待复核）
 *   <li>{@code POST /{id}/cancel} 取消（<b>不动库存</b>）
 * </ul>
 *
 * <p>刻意<b>没有</b> PUT / DELETE：包裹与明细项是事实（V121 的守卫会直接拒已结束的打包单 再改），打包流水是 append-only。要更正就取消重开一张。
 *
 * <p>权限码<b>复用</b> {@code warehouse.inventory.read} / {@code warehouse.inventory.update}， 不新起 {@code
 * warehouse.packing.*}：identity 只注册了 {@code warehouse.inventory} 与 {@code warehouse.facilities}
 * 两个页面资源的权限码，用一个<b>没有被注册</b>的码会让 连 OWNER 都 403（8.1.2.1 车道踩过这个坑并在台账里记了原因）。
 */
@RestController
@RequestMapping("/warehouse/packing-orders")
public class PackingController {

  private final AccessService access;
  private final PackingService packing;

  public PackingController(AccessService access, PackingService packing) {
    this.access = access;
    this.packing = packing;
  }

  /** 统一入口：先确认身份与权限（租户<b>不</b>来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  /** 能力声明：实测重量体积的产出范围、包裹必须含明细项、包材目录的三条守卫、 体积重归 10 物流、3.7.2 物理属性属 catalog 未接入，以及未交付的 8.3.4.1。 */
  @GetMapping("/capabilities")
  public PackingCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> packing.capabilities());
  }

  /** 建包材 / 填充物（真实目录：自重与外形是净重口径的一部分）。 */
  @PostMapping("/materials")
  public PackingMaterialRow createMaterial(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PackingMaterialInput input) {
    return call(auth, "inventory.update", a -> packing.createMaterial(input, a.userId()));
  }

  /** 包材列表（真分页 + 真筛选）。 */
  @GetMapping("/materials")
  public PackingMaterialPage materials(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) String role,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a -> packing.materials(warehouseId, role, type, status, keyword, page, size));
  }

  /** 包材详情（包材 + 被用作包材/填充物各多少次）。 */
  @GetMapping("/materials/{id}")
  public PackingMaterialDetail material(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "inventory.read", a -> packing.material(id));
  }

  /** 停用包材。停用后不能复活（包材口径的历史必须可追）。 */
  @PostMapping("/materials/{id}/retire")
  public PackingMaterialRow retireMaterial(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody PackingMaterialRetireInput input) {
    return call(auth, "inventory.update", a -> packing.retireMaterial(id, input, a.userId()));
  }

  /**
   * 开一张打包单：接一张<b>复核通过</b>的复核单，把复核基线冻结成本单的基线。
   *
   * <p>基线由触发器从复核单读数覆盖，调用方写的两个数一律不作数 —— 「按哪一版基线装的」 是这份凭据的一半。
   */
  @PostMapping
  public PackingCreated open(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PackingOpenInput input) {
    return call(auth, "inventory.update", a -> packing.open(input, a.userId()));
  }

  /** 打包单列表（真分页 + 真筛选 + 排序白名单 + 汇总，汇总不受筛选影响）。 */
  @GetMapping
  public PackingPage page(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) UUID outboundOrderId,
      @RequestParam(required = false) UUID recountSessionId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID packerUserId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "STARTED_AT") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            packing.page(
                warehouseId,
                outboundOrderId,
                recountSessionId,
                status,
                packerUserId,
                keyword,
                sort,
                page,
                size));
  }

  /** 详情：包裹 + 明细项 + 基线对齐 + 时间线 + 完成判据 + 边界声明。 */
  @GetMapping("/{id}")
  public PackingDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "inventory.read", a -> packing.detail(id));
  }

  /** 能不能完成打包 —— 页面的唯一判据来源（前端不自己数行、不自己算重量）。 */
  @GetMapping("/{id}/completion")
  public PackingCompletionReport completion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "inventory.read", a -> packing.completionOf(id));
  }

  /** 建包裹：选包材（角色必须是包材）与填充物（可选，角色必须是填充物）。 */
  @PostMapping("/{id}/parcels")
  public ParcelRow addParcel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody ParcelAddInput input) {
    return call(auth, "inventory.update", a -> packing.addParcel(id, input, a.userId()));
  }

  /**
   * 装一件明细：<b>必须指回一条复核基线行</b>。
   *
   * <p>蓝图那句「包裹必须含明细项（哪些 SKU、多少件），只有重量和运单号无法追溯与对账」 在这里落地：没有来源行的明细就是凭空装进去的东西。
   */
  @PostMapping("/{id}/parcels/{parcelId}/items")
  public ParcelItemRow addItem(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @PathVariable String parcelId,
      @Valid @RequestBody ParcelItemInput input) {
    return call(auth, "inventory.update", a -> packing.addItem(id, parcelId, input, a.userId()));
  }

  /**
   * 称重与量尺寸 —— 实测重量体积，本模块的核心产出。
   *
   * <p>净重与体积由 DB 触发器派生；超重、超外形、毛重低于自重三条当场拒。
   */
  @PostMapping("/{id}/parcels/{parcelId}/measure")
  public ParcelRow measure(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @PathVariable String parcelId,
      @Valid @RequestBody ParcelMeasureInput input) {
    return call(auth, "inventory.update", a -> packing.measure(id, parcelId, input, a.userId()));
  }

  /** 记面单打印（只引用 8.3.4.2 的打印任务，不在本模块渲染 ZPL）。 */
  @PostMapping("/{id}/parcels/{parcelId}/label")
  public ParcelRow recordLabel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @PathVariable String parcelId,
      @Valid @RequestBody ParcelLabelInput input) {
    return call(
        auth, "inventory.update", a -> packing.recordLabel(id, parcelId, input, a.userId()));
  }

  /**
   * 打包完成：五个前提（至少一个包裹、每箱都有明细、都称过重量过尺寸、 每行基线实装恰好等于应发、有打包人）在 DB 层同样拒一遍。
   *
   * <p>通过时把出库单推到「待发货」交给 8.3.4.1，<b>从不碰已发货</b>。
   */
  @PostMapping("/{id}/complete")
  public PackingCreated complete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody(required = false) PackingCompleteInput input) {
    return call(
        auth,
        "inventory.update",
        a ->
            packing.complete(
                id, input == null ? new PackingCompleteInput(null, null) : input, a.userId()));
  }

  /** 打包失败（打包时发现缺件/破损）：出库单退回「待复核」。 */
  @PostMapping("/{id}/fail")
  public PackingCreated fail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody PackingFailInput input) {
    return call(auth, "inventory.update", a -> packing.fail(id, input, a.userId()));
  }

  /** 取消打包（<b>不动库存</b>）。原因必填。 */
  @PostMapping("/{id}/cancel")
  public PackingCreated cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody PackingCancelInput input) {
    return call(auth, "inventory.update", a -> packing.cancel(id, input, a.userId()));
  }
}
