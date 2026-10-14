package com.starcon.warehouse;

import static com.starcon.warehouse.WarehouseLocationTreeModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 8.1.2.1 库位树。
 *
 * <p>蓝图：「维护库位层级与属性（区 → 巷 → 排 → 架 → 层 → 格），支持容量、承重、类型 （存储/暂存/待检/残次/冻结）、是否可拣。输入：库位增删改与层级调整；防环校验。
 * 输出：库位树 + 占用情况。」
 *
 * <p><b>命名空间刻意新起 {@code /warehouse/location-trees}，不复用 {@code
 * /warehouse/facilities/{id}/locations}。</b>后者是 8.1.1.2 已交付的 「详情页里的库位清单」（真分页 + 排序白名单的**扁平列表**，未交付时给
 * {@code available=false}）。本模块交付后那个布尔值应翻成 true，但<b>它的形状不能动</b> ——
 * 两条车道各改各的才不会在同一个方法签名上撞车，而扁平清单与树本来就是两种形状： 清单要能排序分页，树要保父子关系。
 *
 * <p>同理也**不**挂在 {@code /warehouse/locations} —— 那是 7.1.x 的库位下拉参照项 （只读精简列表），与本模块的可维护档案不是同一个东西（与
 * InventoryLotController 不挂 {@code /warehouse/lots} 同一理由）。
 *
 * <p><b>权限码复用 {@code warehouse.facilities.*}。</b>ADR-0005：L3 子功能 = 一张 page_resource 按角色配 CRUD，而
 * 8.1「仓库与库位」这个 L3 的 page_resource 就是 {@code warehouse.facilities}（identity V69 注册 read + create，V77
 * 补 update）， 库位树是它下面的一级子功能，不另开页面资源 —— 另开会在授权界面多出一条同义菜单项。 <b>删除刻意复用 {@code .update}</b>：identity 里没有
 * {@code warehouse.facilities.delete} 这个码（V69 的纪律是「没有端点的权限码不注册」，而本车道不去改 identity 的迁移）， 用一个不存在的码会让连
 * OWNER 都 403。用 update 也符合语义：删一个空叶子是「改这棵树」 的一种，而不是独立于维护的破坏性动作。
 *
 * <p>租户**不**来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限， 再在一个事务里绑定 {@code app.current_tenant}，由
 * RLS 在数据库层强制隔离。
 */
@RestController
@RequestMapping("/warehouse/location-trees")
public class WarehouseLocationTreeController {

  private final AccessService access;
  private final WarehouseLocationTreeService trees;

  public WarehouseLocationTreeController(AccessService access, WarehouseLocationTreeService trees) {
    this.access = access;
    this.trees = trees;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  /**
   * 能力声明。
   *
   * <p>自动分配（8.1.2.2）与拣货路径（8.3.2.1）未交付，如实报 {@code false} + 归属模块。 字面量路径排在 {@code {warehouseId}}
   * 之前，避免被 uuid 形状吃掉。
   */
  @GetMapping("/capabilities")
  public LocationTreeCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "facilities.read", a -> trees.capabilities());
  }

  /**
   * 单仓库的库位树 + 占用情况。
   *
   * <p>仓库不存在与不属于本租户都返回 404 —— 两者可观测结果必须相同， 否则「猜 id」就成了探测别的租户有没有仓库的侧信道。
   */
  @GetMapping("/{warehouseId}")
  public LocationTree tree(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID warehouseId) {
    return call(auth, "facilities.read", a -> trees.tree(warehouseId));
  }

  /**
   * 新建库位。
   *
   * <p>{@code parentId} 为 null 即顶层根节点（区）—— 不是漏填。 防环与跨仓库挂父子在 DB 层裁决（V105 触发器 + 复合外键），这里给可读的中文报错。
   */
  @PostMapping("/{warehouseId}/locations")
  public LocationSaved create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID warehouseId,
      @Valid @RequestBody LocationCreateInput input) {
    return call(auth, "facilities.create", a -> trees.create(warehouseId, input));
  }

  /**
   * 改库位属性与层级。
   *
   * <p>带 {@code parentId} 即层级调整（挂到别的节点下），且 {@code parentId=null} + 带 {@code parentProvided=true}
   * 表示「升为根节点」；{@code parentProvided} 缺省则层级不动。 成环与超六层由 V105 触发器拒绝。
   */
  @PutMapping("/{warehouseId}/locations/{id}")
  public LocationSaved update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID warehouseId,
      @PathVariable UUID id,
      @Valid @RequestBody LocationUpdateInput input) {
    return call(auth, "facilities.update", a -> trees.update(warehouseId, id, input));
  }

  /**
   * 删库位（只允许空叶子）。
   *
   * <p>有子节点或压着库存时 409 —— 让用户看到「先把货移走 / 先移走子节点」， 而不是撞一个裸 FK 违例。
   */
  @DeleteMapping("/{warehouseId}/locations/{id}")
  public void delete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID warehouseId,
      @PathVariable UUID id) {
    call(
        auth,
        "facilities.update",
        a -> {
          trees.delete(warehouseId, id);
          return null;
        });
  }
}
