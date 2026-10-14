package com.starcon.warehouse;

import static com.starcon.warehouse.LocationUtilizationModels.*;

import jakarta.validation.Valid;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 8.5.2.1 库位利用率。
 *
 * <p>蓝图：「分析库位占用：容量利用率、周转率、空置库位、爆满库位、低效库位，给出调整建议。
 * 输入：库位与库存占用；出库频次。输出：利用率报表 + 热力图 + 建议。」关系段：「上游 = 8.1.2.1；
 * 下游 = 8.1.2.2 自动分配优化。库位热力数据同时优化拣货路径（高频商品放近出口），一举两得。」
 *
 * <p><b>命名空间新起 {@code /warehouse/location-utilization}，与兄弟模块的三条刻意分开：</b>
 *
 * <ul>
 *   <li>不挂 {@code /warehouse/location-trees}（8.1.2.1 库位树）—— 那是<b>维护</b>档案的
 *       端点，语义是「改」；本模块是<b>只读分析</b>，没有任何写入口。把分析挂在维护的命名空间下
 *       会让「这个页面能改库位吗」变得答不清；
 *   <li>不挂 {@code /warehouse/facilities/{id}/locations}（8.1.1.2 库位清单）—— 那是分页
 *       扁平清单，本模块是按利用率排序的全量热力图，形状不同；
 *   <li>不挂 {@code /warehouse/locations} —— 那是 7.1.x 的只读下拉参照项。
 * </ul>
 *
 * <p><b>字面量子路径 {@code /capabilities} 排在 {@code {warehouseId}} 之前</b>：
 * 与 8.1.2.1 的 controller 同一纪律（uuid 形状不能被当成通配吃掉）。
 *
 * <p><b>权限码复用 {@code warehouse.facilities.read}，且本模块没有任何写端点。</b>
 * ADR-0005：8.5.2.1 是 8.1「仓库与库位」下的分析子功能，不是新的页面资源；
 * 分析是只读的，要 create/update 权限才看得到利用率报表是错的。本模块<b>刻意不注册</b>
 * 任何新的权限码 —— identity 里没有对应码的话，用一个不存在的码会让连 OWNER 都 403。
 *
 * <p>租户**不**来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 */
@RestController
@RequestMapping("/warehouse/location-utilization")
public class LocationUtilizationController {

  private final AccessService access;
  private final LocationUtilizationService utilization;

  public LocationUtilizationController(AccessService access, LocationUtilizationService utilization) {
    this.access = access;
    this.utilization = utilization;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, java.util.function.Supplier<T> work) {
    var actor = access.require(bearer, "warehouse.facilities.read");
    return access.run(actor, "warehouse.facilities.read", work);
  }

  /**
   * 能力声明。
   *
   * <p>如实报出库频次的归集粒度与周转率口径（字符串随响应返回，不只写在文档里）；
   * 8.1.2.2 自动分配与 8.3.2.1 拣货路径本车道不交付，恒为 {@code false} + 归属模块。
   */
  @GetMapping("/capabilities")
  public LocationUtilizationCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, () -> utilization.capabilities());
  }

  /**
   * 利用率报表 + 热力图 + 建议。
   *
   * <p>仓库不存在与不属于本租户都返回 404 —— 两者可观测结果必须相同，
   * 否则「猜 id」就成了探测别的租户有没有仓库的侧信道。
   *
   * <p>筛选（档位 / 类型 / 可拣 / 利用率下限 / 关键字）只影响 {@code locations}，
   * {@code totals} 始终是全仓真相 —— 筛完只看得到一部分行时，顶部合计不该跟着变。
   */
  @GetMapping("/{warehouseId}")
  public LocationUtilizationReport report(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID warehouseId,
      @Valid LocationUtilizationQuery query) {
    return call(auth, () -> utilization.report(warehouseId, query));
  }

  /**
   * 单库位详情：逐 SKU 拆分 + 该库位的建议。
   *
   * <p>独立于报表端点，因为它是唯一会随 SKU 数量增长的形状 ——
   * 报表是「每个库位一行」，详情是「这个库位上每个 SKU 一行」。
   */
  @GetMapping("/{warehouseId}/locations/{locationId}")
  public LocationUtilizationDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID warehouseId,
      @PathVariable UUID locationId,
      @Valid LocationUtilizationQuery query) {
    return call(auth, () -> utilization.detail(warehouseId, locationId, query));
  }
}
