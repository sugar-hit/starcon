package com.starcon.channel;

import static com.starcon.channel.CarrierModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 10.1.1.1 承运商列表。
 *
 * <p>端点按蓝图模块号标注，方便对照 10-logistics-and-freight.md。
 *
 * <p>🔴 本控制器<b>刻意没有「建承运商」端点</b>：蓝图加粗句写死了
 * **「承运商是往来单位的一种角色，不在此处重复建档」**。主体与 CARRIER 角色
 * 在 catalog 的 /partners 建（3.6.1.1 / 3.6.1.2），本模块只提供：
 *
 * <ul>
 *   <li>{@code POST /sync} —— 把上游的承运商身份投影成本服务的物流侧行
 *       （<b>唯一</b>会写「上游快照」的入口）
 *   <li>{@code PUT /{id}} —— 登记 / 改<b>物流侧</b>档案（类别 / 时效 / 覆盖 /
 *       业务类型 / 凭据引用）。请求体里<b>没有</b>主体身份字段。
 *   <li>{@code DELETE /{id}} —— 撤下物流侧档案，<b>不动</b>上游的承运商身份
 * </ul>
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}
 * （与 10.3.2.1 交运批次同族），因此不需要改动 identity 服务，跨车道回归面为 0。
 */
@RestController
@RequestMapping("/channel/carriers")
public class CarrierController {
  private final AccessService access;
  private final CarrierService service;

  public CarrierController(AccessService access, CarrierService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  private void callVoid(String bearer, String permission, Consumer<AccessService.Access> work) {
    var actor = access.require(bearer, permission);
    access.run(
        actor,
        permission,
        () -> {
          work.accept(actor);
          return null;
        });
  }

  // ---- 能力声明 ----

  @GetMapping("/capabilities")
  public CarrierCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  /** 概览：全量口径的分类 / 时效分布，**不受列表筛选与分页影响**。 */
  @GetMapping("/overview")
  public CarrierOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.overview());
  }

  // ---- 承运商列表 ----

  /**
   * 承运商列表：真分页 + 真筛选。
   *
   * <p>筛选维度都是 SQL 侧的：关键词（编码 / 名称，LIKE 通配符已转义）、
   * 承运商类别、时效等级、可用性、覆盖国家、支持业务类型。
   * 返回的 {@code summary} 走独立查询，**不受 limit/offset 影响**。
   */
  @GetMapping
  public CarrierPage carriers(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String carrierCategory,
      @RequestParam(required = false) String serviceTier,
      @RequestParam(required = false) String availability,
      @RequestParam(required = false) String countryCode,
      @RequestParam(required = false) String businessType,
      @RequestParam(required = false, defaultValue = "NAME_ASC") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.carriers(
                keyword, carrierCategory, serviceTier, availability, countryCode, businessType,
                sort, page, size));
  }

  /** 同步承运商身份：唯一写「上游快照」的入口，转发的是调用方自己的 bearer。 */
  @PostMapping("/sync")
  public SyncCarriersResult sync(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "100") int size) {
    return call(auth, "channel.operation.create", a -> service.syncCarriers(page, size, auth));
  }

  @GetMapping("/{id}")
  public CarrierRow detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.detail(UUID.fromString(id)));
  }

  /** 登记 / 改物流侧档案。请求体<b>不接受</b>主体身份、接口类型与账号。 */
  @PutMapping("/{id}")
  public CarrierRow save(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody SaveCarrierRequest request) {
    return call(
        auth, "channel.operation.update", a -> service.save(UUID.fromString(id), request));
  }

  /**
   * 撤下物流侧档案（<b>不是</b>删除承运商）：主体记录在 catalog，本服务无权删。
   *
   * <p>撤掉之后该承运商重新变回 {@code NOT_REGISTERED}，重新同步即可再看到 ——
   * 身份一次都没有丢过，这在数据上就是「物流侧没配过」这一条真实状态。
   */
  @DeleteMapping("/{id}")
  public org.springframework.http.ResponseEntity<Void> deleteLogisticsProfile(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    callVoid(
        auth,
        "channel.operation.delete",
        a -> service.deleteLogisticsProfile(UUID.fromString(id)));
    return org.springframework.http.ResponseEntity.status(HttpStatus.NO_CONTENT).build();
  }
}
