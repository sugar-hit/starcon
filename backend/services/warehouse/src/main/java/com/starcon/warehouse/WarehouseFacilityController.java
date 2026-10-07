package com.starcon.warehouse;

import static com.starcon.warehouse.WarehouseFacilityModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 8.1.1.1 仓库列表。
 *
 * <p>蓝图：「展示租户下所有仓库：类型（自营/三方/海外仓/平台仓/监管仓）、所属法人、国家地区、
 * 时区、启用状态、库存量与作业量，支持筛选与新增。」以及关系里的红线：
 * 「**仓库绑定法人是财务前提**：不同法人的库存货权与收入归属不同。」
 *
 * <p>命名空间是 {@code /warehouse/facilities}（蓝图 08 的 L3 锚点路由），
 * 与 {@code /warehouse/warehouses} 刻意分开：后者是 7.1.x 各页共用的**下拉参照项**
 * （只有 id/code/name/country/status），本端点是**列表本体**（带库存量、作业量、
 * 法人归属、当地时间）。两个路径名字相近但形状与权限不同，混用会让筛选下拉
 * 悄悄退化成整个列表的全量传输。
 *
 * <p>权限码是 {@code warehouse.facilities.read} / {@code warehouse.facilities.create}
 * （identity V69 注册），**刻意不与 warehouse.inventory 共用**：新增仓库是主数据写入，
 * 与库存聚合只读的风险面不同（见 V69 文件头）。
 *
 * <p>端点独立成 {@code WarehouseFacilityController} 而不是并进 {@code InventoryController}：
 * 那是 7.1.x 的文件，8.1.1.2 / 8.1.1.3 两条车道并行时各改各的才不会撞。
 */
@RestController
@RequestMapping("/warehouse")
public class WarehouseFacilityController {

  private final AccessService access;
  private final WarehouseFacilityService facilities;

  public WarehouseFacilityController(AccessService access, WarehouseFacilityService facilities) {
    this.access = access;
    this.facilities = facilities;
  }

  /**
   * 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），
   * 再在一个事务里绑定 RLS 租户上下文执行查询（与 7.4.2.2 同一个 call）。
   */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  /**
   * 仓库列表：筛选（类型 / 法人 / 国家 / 状态）+ 关键字 + 排序 + 真分页。
   *
   * <p>响应里的 {@code summary} 是**不受分页与筛选影响**的全量合计，
   * {@code detailUnavailable} 如实报出 8.1.1.2 详情页尚未交付（前端据此禁用进详情）。
   */
  @GetMapping("/facilities")
  public WarehouseFacilityPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String kind,
      @RequestParam(required = false) UUID legalEntityId,
      @RequestParam(required = false) String countryCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "facilities.read",
        a ->
            facilities.list(
                new FacilityQuery(
                    kind, legalEntityId, countryCode, status, keyword, sort, page, size)));
  }

  /**
   * 筛选与录入下拉项：仓库类型词表、本租户真实在用的国家、法人引用、状态词表。
   *
   * <p>作业量维度接入情况在 {@code workCapabilities()}（见下），两件事分开返回：
   * 下拉项是「填什么」，能力声明是「哪些维度有数」。
   */
  @GetMapping("/facilities/options")
  public FacilityOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "facilities.read", a -> facilities.options());
  }

  /**
   * 作业量维度接入情况：盘点 / 调拨 / 库存变动已接入，
   * 收货（8.2）/ 拣货出库（8.3）/ 组装（8.4）显式报 {@code available=false} 并写明归哪个模块。
   *
   * <p>前端据此禁用不可用的维度，而不是显示一个永远为 0 的数字 ——
   * 「0 条收货作业」与「收货作业还没做」是两件事，混起来会让人以为系统坏了。
   */
  @GetMapping("/facilities/capabilities")
  public List<WorkVolumeCapability> capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "facilities.read", a -> facilities.workCapabilities());
  }

  /**
   * 新增仓库。
   *
   * <p>{@code defaultLegalEntityId} **必填**：仓库绑定法人是财务前提，
   * 不绑定法人的仓会让「这批货算哪个法人的收入」无从回答。存量行的该列可空是历史事实，
   * 但新建一律要填。
   *
   * <p>{@code isDefault=true} 时若该租户已有启用中的默认仓会被拒 ——
   * 「每个租户至多一个默认发货仓」由 {@code uq_warehouse_default} 部分唯一索引在 DB 层裁决，
   * 这里先把话说清楚。
   */
  @PostMapping("/facilities")
  public WarehouseCreated create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody WarehouseCreateInput input) {
    return call(auth, "facilities.create", a -> facilities.create(input));
  }
}