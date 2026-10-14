package com.starcon.warehouse;

import static com.starcon.warehouse.ParcelDetailModels.*;

import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 10.2.2.1 包裹详情入口。
 *
 * <p>命名空间 {@code /warehouse/parcels}。<b>刻意不挂在 {@code packing-orders} 之下</b>：
 * 打包作业单是 8.3.3.2 的<b>作业工单</b>（一张出库单可以打包失败退回复核、再重打包，
 * 每次一张独立的作业单），而包裹详情是<b>凭据视图</b> —— 10 物流查轨迹、12 财务做运费归集
 * 时手里拿的是 {@code parcel_id}，不是作业单 id。挂在 packing-orders 之下会让这两个下游
 * 必须先找到作业单才能看包裹（与 8.3.3.2 把打包单挂在出库单之外的同一理由）。
 *
 * <p>两个形状，刻意逐个列出：
 *
 * <ul>
 *   <li>{@code capabilities} 能力与边界声明（含「体积重与运费不在本模块算」「轨迹与交运归 10/8.3.4.1」
 *       「归集到行的基础是 parcel_item → 销售订单行」三条口径）
 *   <li>{@code GET /{id}} 包裹详情（单头 + 实测重量体积 + 明细项 + 订单行关联 + 面单 + 成本归集 + 操作入口）
 * </ul>
 *
 * <p>刻意<b>没有</b> POST / PUT / DELETE：本模块是 8.3.3.2 包裹凭据的<b>只读视图</b>，
 * 一个字节都不改写那些表。包裹与明细项是事实（V121 的守卫会直接拒已结束的打包单再改），
 * 业务动作一律回到 8.3.3.2 的作业单上按它的状态机走。
 *
 * <p>权限码<b>复用</b> {@code warehouse.inventory.read}，不新起 {@code warehouse.parcel.*}：
 * identity 只注册了 {@code warehouse.inventory} 与 {@code warehouse.facilities} 两个页面资源的
 * 权限码，用一个<b>没有被注册</b>的码会让连 OWNER 都 403（8.1.2.1 车道踩过这个坑并在台账里记了原因）。
 */
@RestController
@RequestMapping("/warehouse/parcels")
public class ParcelDetailController {

  private final AccessService access;
  private final ParcelDetailService parcels;

  public ParcelDetailController(AccessService access, ParcelDetailService parcels) {
    this.access = access;
    this.parcels = parcels;
  }

  /** 统一入口：先确认身份与权限（租户<b>不</b>来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  /**
   * 能力声明：六段内容的可用性、归集口径，以及必须写在界面上的说明。
   *
   * <p>前端据此渲染「未接入」的灰块与提示，而不是把六段都画成空的详情表。
   */
  @GetMapping("/capabilities")
  public ParcelDetailCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> parcels.capabilities());
  }

  /**
   * 包裹详情（蓝图输入 parcel_id；输出：包裹详情 + 操作入口）。
   *
   * <p>一次调用返回整屏：单头（含实测重量体积）、包裹内明细项与订单行对应关系、
   * 面单、成本归集基线、操作入口、三段不可得能力的逐段说明。
   *
   * <p>包裹不存在<b>或</b>不属于当前租户时都返回 404 —— 与「不存在」同一个出口，
   * 不给跨租户探测留信息泄露面。
   */
  @GetMapping("/{id}")
  public ParcelDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "inventory.read", a -> parcels.detail(UUID.fromString(id)));
  }
}
