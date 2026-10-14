package com.starcon.warehouse;

import static com.starcon.warehouse.LocationAllocationModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 8.1.2.2 库位自动分配。
 *
 * <p>蓝图：「按规则为入库商品推荐库位：同品类聚类、最短上架路径、容量与承重匹配、 待检/残次专用库位强制规则。输入：入库 SKU 与数量；库位属性与占用；路径距离。
 * 输出：推荐库位列表（带推荐理由）。」红线：「**质量状态决定库位类型**： 残次品入残次库位是硬规则，不允许混放。」
 *
 * <p><b>命名空间新起 {@code /warehouse/location-allocations}</b>，刻意不复用：
 *
 * <ul>
 *   <li>{@code /warehouse/location-trees/{id}} —— 8.1.2.1 的库位**树**（维护档案）；
 *   <li>{@code /warehouse/facilities/{id}/locations} —— 8.1.1.2 的库位**清单**（扁平分页列表）；
 *   <li>{@code /warehouse/inventory/...allocation} —— 7.x 的库存**分配**（数量分配给订单）。
 * </ul>
 *
 * <p>三者的主语完全不同（库位本身 / 库位里的明细 / 订单要走的数量），混用会让「上架分配」 与「库存分配」在接口上分不清。字面量子路径（{@code
 * /capabilities}、{@code /recommend}、 {@code /list}）刻意排在 {@code /{allocationId}} 之前，避免被 uuid 形状吃掉。
 *
 * <p><b>权限码复用 {@code warehouse.facilities.read / .update}</b>，与 8.1.2.1 同一处置： 8.1「仓库与库位」这个 L3 的
 * page_resource 就是 {@code warehouse.facilities} （identity V69 注册 read + create，V77 补
 * update），本模块是它下面的二级子功能， 不另开页面资源。也不注册 delete —— 撤销走 {@code .update}，且撤销是本模块的一等动作 （带原因、可重新指派），不是删数据。
 *
 * <p>租户**不**来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限， 再在一个事务里绑定 {@code app.current_tenant}，由
 * RLS 在数据库层强制隔离。
 */
@RestController
@RequestMapping("/warehouse/location-allocations")
public class LocationAllocationController {

  private final AccessService access;
  private final LocationAllocationService allocations;

  public LocationAllocationController(AccessService access, LocationAllocationService allocations) {
    this.access = access;
    this.allocations = allocations;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  /**
   * 能力声明。
   *
   * <p>四条 {@code false} 都是本服务真的拿不到的东西（catalog 类目树、巷道路径图、 既有库存的体积口径、上架任务），逐条写明归属模块 —— 不给
   * 0、不给空数组冒充「这个仓没有」。
   */
  @GetMapping("/capabilities")
  public LocationAllocationCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "facilities.read", a -> allocations.capabilities());
  }

  /**
   * 为一张收货单的入库明细推荐库位（带推荐理由）。
   *
   * <p><b>纯计算，不落库。</b>落库的只有人真正选定的那个库位（见 confirm）。
   *
   * <p>{@code requiredCapacity} / {@code requiredWeight} 是调用方量出来的这批货要占的
   * 容量与承重；不传则容量与承重规则<b>无法判定</b>，返回里会给 evaluated=false 与说明 —— 而不是把它们当成「够」。
   */
  @PostMapping("/recommend")
  public List<LocationAllocationLinePlan> recommend(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody LocationAllocationRecommendInput input) {
    return call(auth, "facilities.read", a -> allocations.recommend(input));
  }

  /** 选定一个库位，落成一条上架决策。分数与理由由服务端重算后冻结成快照，不采信调用方。 */
  @PostMapping
  public LocationAllocationSaved confirm(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody LocationAllocationConfirmInput input) {
    return call(auth, "facilities.update", a -> allocations.confirm(input, a.userId()));
  }

  /** 决策清单（真分页 + 排序白名单；非法排序键 400 而非静默忽略）。合计不受分页与筛选影响。 */
  @GetMapping("/list")
  public LocationAllocationPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String qualityRole,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(
        auth,
        "facilities.read",
        a ->
            allocations.list(
                new LocationAllocationQuery(
                    warehouseId, status, qualityRole, keyword, sort, page, size)));
  }

  /** 某张收货单上的全部上架决策。 */
  @GetMapping("/by-receipt/{receiptId}")
  public List<LocationAllocationDecision> byReceipt(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID receiptId) {
    return call(auth, "facilities.read", a -> allocations.byReceipt(receiptId));
  }

  /** 释放已选定的决策（交班给 8.2.2.1）。 */
  @PostMapping("/{allocationId}/release")
  public LocationAllocationSaved release(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID allocationId) {
    return call(auth, "facilities.update", a -> allocations.release(allocationId, a.userId()));
  }

  /**
   * 撤销已选定的决策。
   *
   * <p><b>必须带原因</b>（V106 的 CHECK 也会拒）：「为什么撤掉这个决定」是现场复盘的必答题。 撤销不是删除 —— 决策行留着，行状态转
   * CANCELLED，该收货明细行可重新指派。 所以走 {@code .update} 而不是 delete。
   */
  @DeleteMapping("/{allocationId}")
  public LocationAllocationSaved cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID allocationId,
      @Valid @RequestBody LocationAllocationCancelInput input) {
    return call(auth, "facilities.update", a -> allocations.cancel(allocationId, input));
  }
}
