package com.starcon.warehouse;

import static com.starcon.warehouse.WarehouseDetailModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 8.1.1.2 仓库详情 —— 仓库的控制塔。
 *
 * <p>蓝图：「单仓库全景：基础信息与地址、库位结构、库存分布、作业统计、关联的自发货/中转/ FBA 备货策略、作业人员与设备。」「详情页是仓库的控制塔，作业入口与库存入口都在这里。」
 *
 * <p>四个端点分工：
 *
 * <ul>
 *   <li>{@code GET /warehouse/facilities/{id}} —— 全景（基础信息 + 地址 + 库存分布 + 库位结构 + 作业统计 + 三条备货策略 +
 *       人员与设备 + 入口清单），一次取全，页面不用打七次请求
 *   <li>{@code GET /warehouse/facilities/{id}/locations} —— 库位清单（真分页 + 排序白名单），
 *       独立成端点是因为它是**唯一会增长的列表**：库位可以上百上千，塞进全景里会让控制塔首屏变慢
 *   <li>{@code GET /warehouse/facilities/{id}/entrypoints} —— 入口清单单独可取，
 *       因为入口的接入状态与仓库数据无关（它只取决于哪些模块已交付）
 *   <li>{@code PUT /warehouse/facilities/{id}} —— 改基础信息与地址（8.1.1.1 明确把「改仓库属性」 留给本模块，见 identity V69
 *       文件头）
 * </ul>
 *
 * <p>权限码 {@code warehouse.facilities.read} / {@code warehouse.facilities.update} （identity V69 +
 * V77）。<b>刻意不注册 delete</b>：仓库不是可物理删除的主数据， 停用与归档才是出口，且本服务没有对应的 DELETE 端点 —— 没有端点的权限码不注册。
 *
 * <p>端点独立成 {@code WarehouseDetailController} 而不是并进 {@code WarehouseFacilityController}： 8.1.1.1 与
 * 8.1.1.3 是并行车道，各改各的才不会撞（与 8.1.1.1 文件头同一理由）。
 */
@RestController
public class WarehouseDetailController {

  private final AccessService access;
  private final WarehouseDetailService details;

  public WarehouseDetailController(AccessService access, WarehouseDetailService details) {
    this.access = access;
    this.details = details;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头）， 再在一个事务里绑定 RLS 租户上下文执行查询。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  /**
   * 单仓库全景。
   *
   * <p>仓库不存在与不属于本租户都返回 404 —— 两者可观测结果必须相同， 否则「猜 id」就成了探测别的租户有没有仓库的侧信道。
   */
  @GetMapping("/warehouse/facilities/{id}")
  public WarehouseDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "facilities.read", a -> details.detail(id));
  }

  /**
   * 库位清单：筛选（类型 / 状态）+ 关键字 + 排序 + 真分页，合计不受分页与筛选影响。
   *
   * <p>排序键走白名单：非法排序键报 400，而不是静默按默认序返回一份「看起来能排序」的数据。
   */
  @GetMapping("/warehouse/facilities/{id}/locations")
  public LocationPage locations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "facilities.read",
        a -> details.locations(id, new LocationQuery(type, status, keyword, sort, page, size)));
  }

  /**
   * 各子模块入口。
   *
   * <p>蓝图把「各子模块入口」写进本模块的输出，所以它是端点而不是页内常量： 其它模块（库存页、作业页）可以直接取这份清单来渲染自己那一段的入口， 而不必各写一份自己的「未交付」列表。
   */
  @GetMapping("/warehouse/facilities/{id}/entrypoints")
  public List<DetailEntryPoint> entryPoints(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    // id 必须落在本租户：入口清单虽然与仓库数据无关，但一个「不存在的仓库也返回 200」
    // 的端点会让拼错的链接看起来是有效的。与详情同一套 404。
    return call(
        auth,
        "facilities.read",
        a -> {
          details.detail(id);
          return details.entryPoints();
        });
  }

  /**
   * 改仓库基础信息与地址。
   *
   * <p>入参**不含**编码、仓库类型、货权法人与默认发货仓标记：它们分别是仓库标识、 记账口径与财务前提，改动会让已落库数据失去指向（逐条理由见 {@link
   * WarehouseUpdateInput} 的注释）。
   *
   * <p>{@code status} 可改到 ARCHIVED，但归档是终态：归档后不能再改任何字段。
   */
  @PutMapping("/warehouse/facilities/{id}")
  public WarehouseUpdated update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody WarehouseUpdateInput input) {
    return call(auth, "facilities.update", a -> details.update(id, input));
  }
}
