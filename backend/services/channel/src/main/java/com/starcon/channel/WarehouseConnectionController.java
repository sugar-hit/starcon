package com.starcon.channel;

import static com.starcon.channel.WarehouseConnectionModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 10.4.2.1 海外仓连接。
 *
 * <p>端点按蓝图模块号标注，方便对照 10-logistics-and-freight.md。
 *
 * <p>权限码**复用** identity 已有的 {@code channel.connection.*} 与 {@code channel.sync.*} （连接配置归
 * connection，库存同步归 sync），因此本模块不需要改动 identity 服务， 跨车道回归面为 0。
 */
@RestController
@RequestMapping("/channel/warehouse-providers")
public class WarehouseConnectionController {
  private final AccessService access;
  private final WarehouseConnectionService service;

  public WarehouseConnectionController(AccessService access, WarehouseConnectionService service) {
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

  // ---- 服务商目录 ----

  @GetMapping
  public List<WarehouseProviderView> providers(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.connection.read", a -> service.providers());
  }

  // ---- 连接 ----

  @GetMapping("/connections")
  public List<WarehouseConnectionView> connections(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.connection.read", a -> service.connections());
  }

  @GetMapping("/connections/{id}")
  public WarehouseConnectionView connection(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "channel.connection.read", a -> service.connection(id));
  }

  @PostMapping("/connections")
  public UUID connectionCreate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody WarehouseConnectionInput input) {
    return call(auth, "channel.connection.create", a -> service.createConnection(input));
  }

  /** 暂停/恢复同步。暂停只停同步，连接本身仍可见——否则运营会以为配置丢了。 */
  @PostMapping("/connections/{id}/sync-paused")
  public void connectionSetPaused(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam boolean paused) {
    callVoid(auth, "channel.connection.update", a -> service.setPaused(id, paused));
  }

  // ---- 仓账户 ----

  @GetMapping("/accounts")
  public List<WarehouseAccountView> accounts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID connectionId) {
    return call(auth, "channel.connection.read", a -> service.accounts(connectionId));
  }

  @GetMapping("/accounts/{id}")
  public WarehouseAccountView account(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "channel.connection.read", a -> service.account(id));
  }

  @PostMapping("/connections/{id}/accounts")
  public UUID accountCreate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody WarehouseAccountInput input) {
    return call(auth, "channel.connection.create", a -> service.createAccount(id, input));
  }

  // ---- 服务商报送（适配器的真实输入） ----

  /**
   * 登记一条服务商库存报送。
   *
   * <p>这是**输入**，服务商报错时能改（下方有 DELETE 撤销）；观测快照不能改。 两者分表正是为了让这个区别在数据上成立。
   */
  @PostMapping("/accounts/{id}/inbound-stock-lines")
  public UUID inboundLine(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody InboundStockLineInput input) {
    return call(
        auth,
        "channel.sync.create",
        a ->
            service.recordInboundStockLine(
                id,
                input.externalSku(),
                input.quantity(),
                input.unit(),
                input.source(),
                input.reportedAt()));
  }

  // ---- 库存同步 ----

  /**
   * 触发一次库存同步。
   *
   * <p>同步结果只写观测快照表，**不写自营库存**——这是蓝图那条加粗约束的运行时体现， DB 层另有触发器与权限各挡一层（迁移 V93）。
   */
  @PostMapping("/accounts/{id}/sync")
  public UUID syncTrigger(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "channel.sync.trigger", a -> service.triggerSync(id, "MANUAL"));
  }

  @GetMapping("/sync-runs")
  public List<WarehouseSyncRunView> syncRuns(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID connectionId,
      @RequestParam(defaultValue = "50") int limit) {
    return call(auth, "channel.sync.read", a -> service.syncRuns(connectionId, limit));
  }

  // ---- 外部库存观测（只读） ----

  /** 观测流水：按观测时点倒序，观测值的历史本身就是要留的证据。 */
  @GetMapping("/accounts/{id}/observations")
  public List<ExternalStockObservationView> observations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "100") int limit) {
    return call(auth, "channel.sync.read", a -> service.observations(id, limit));
  }

  /** 各 SKU 最新观测。「最新」是查出来的（DISTINCT ON），不是存出来等着被覆盖的。 */
  @GetMapping("/accounts/{id}/observations/latest")
  public List<ExternalStockLatestView> latestObservations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "channel.sync.read", a -> service.latestObservations(id));
  }
}
