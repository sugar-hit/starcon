package com.starcon.channel;

import static com.starcon.channel.ChannelModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 02 渠道与店铺的 HTTP 接口。
 *
 * <p>每个端点都声明它服务的蓝图模块号，方便对照 capability-blueprint/02-channel-and-shop.md。
 */
@RestController
@RequestMapping("/channel")
public class ChannelController {
  private final AccessService access;
  private final ChannelService service;

  public ChannelController(AccessService access, ChannelService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  private void callVoid(String bearer, String permission, java.util.function.Consumer<AccessService.Access> work) {
    var actor = access.require(bearer, permission);
    access.run(actor, permission, () -> {
      work.accept(actor);
      return null;
    });
  }

  // ---- 2.1 平台目录 ----

  /** 2.1.1.1 平台目录 / 2.1.1.2 能力矩阵 / 2.1.1.3 站点与国家参数。 */
  @GetMapping("/platforms")
  public List<PlatformView> platforms(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.registry.read", a -> service.platforms());
  }

  /** 2.1.2.1 生效枚举映射：平台默认 + 本租户覆盖。 */
  @GetMapping("/platforms/{platformCode}/enums")
  public List<EnumMappingView> enumMappings(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String platformCode,
      @RequestParam(defaultValue = "") String kind) {
    return call(auth, "channel.registry.read", a -> service.enumMappings(a.tenantId(), platformCode, kind));
  }

  /**
   * 2.1.2.1 登记本租户映射覆盖。
   *
   * <p>平台默认表 {@code platform_enum_mapping} 是全局只读码表，故意不给写端点：租户改它
   * 会污染别的租户的订单状态机。要改口径只能写自己的覆盖层。
   */
  @PostMapping("/platforms/{platformCode}/enums")
  public UUID enumOverrideCreate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String platformCode,
      @Valid @RequestBody EnumOverrideInput input) {
    return call(
        auth, "channel.registry.update", a -> service.putEnumOverride(a, platformCode, input));
  }

  /** 删除覆盖后回落平台默认。 */
  @DeleteMapping("/platforms/{platformCode}/enums/{id}")
  public void enumOverrideDelete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String platformCode,
      @PathVariable UUID id) {
    callVoid(
        auth,
        "channel.registry.delete",
        a -> service.deleteEnumOverride(a, platformCode, id));
  }

  // ---- 2.2 授权与连接 ----

  /** 2.2.1.1 连接列表。 */
  @GetMapping("/connections")
  public List<ConnectionView> connections(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(defaultValue = "") String q) {
    return call(auth, "channel.connection.read", a -> service.connections(q));
  }

  /** 2.2.1.2 授权向导：创建连接。 */
  @PostMapping("/connections")
  public UUID connectionCreate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ConnectionInput input) {
    return call(auth, "channel.connection.create", a -> service.createConnection(a, input));
  }

  /** 2.2.1.3 凭据轮换：只接收密钥管理器引用。 */
  @PutMapping("/connections/{id}/credential")
  public void credentialRotate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody CredentialRotateInput input) {
    callVoid(auth, "channel.connection.update", a -> service.rotateCredential(a, id, input));
  }

  /** 2.2.1.1 断开连接。 */
  @PostMapping("/connections/{id}/revoke")
  public void connectionRevoke(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam long version) {
    callVoid(auth, "channel.connection.revoke", a -> service.revokeConnection(a, id, version));
  }

  /** 2.2.2.1 健康诊断。 */
  @GetMapping("/connections/{id}/health")
  public ConnectionHealthView connectionHealth(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth, @PathVariable UUID id) {
    return call(auth, "channel.connection.read", a -> service.health(a, id));
  }

  /** 2.2.2.2 重新授权提醒。 */
  @GetMapping("/connections/reminders")
  public List<ReauthorizationReminderView> reminders(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(defaultValue = "7") int withinDays) {
    return call(auth, "channel.connection.read", a -> service.reauthorizationReminders(withinDays));
  }

  // ---- 2.3 店铺与站点 ----

  /** 2.3.1.1 店铺列表。 */
  @GetMapping("/shops")
  public List<ShopView> shops(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(defaultValue = "") String q) {
    return call(auth, "channel.shop.read", a -> service.shops(q));
  }

  /** 2.3.1.2 店铺详情。 */
  @GetMapping("/shops/{id}")
  public ShopView shop(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth, @PathVariable UUID id) {
    return call(auth, "channel.shop.read", a -> service.shop(a.tenantId(), id));
  }

  @PostMapping("/shops")
  public UUID shopCreate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ShopInput input) {
    return call(auth, "channel.shop.create", a -> service.createShop(a, input));
  }

  @PutMapping("/shops/{id}")
  public void shopUpdate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody ShopInput input) {
    callVoid(auth, "channel.shop.update", a -> service.updateShop(a, id, input));
  }

  @DeleteMapping("/shops/{id}")
  public void shopDelete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam long version) {
    callVoid(auth, "channel.shop.delete", a -> service.deleteShop(a, id, version));
  }

  /** 2.3.1.1 启用站点。 */
  @PostMapping("/shops/{id}/marketplaces")
  public void marketplaceEnable(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody ShopMarketplaceInput input) {
    callVoid(auth, "channel.shop.update", a -> service.enableMarketplace(a, id, input));
  }

  @DeleteMapping("/shops/{id}/marketplaces/{marketplaceId}")
  public void marketplaceDisable(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID marketplaceId) {
    callVoid(auth, "channel.shop.update", a -> service.disableMarketplace(a, id, marketplaceId));
  }

  /** 2.3.2.2 负责人与数据范围。 */
  @PostMapping("/shops/{id}/transfer")
  public void shopTransfer(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody ShopOwnerTransferInput input) {
    callVoid(auth, "channel.shop.update", a -> service.transferOwner(a, id, input));
  }
}
