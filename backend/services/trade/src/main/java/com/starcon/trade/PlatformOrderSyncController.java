package com.starcon.trade;

import static com.starcon.trade.PlatformOrderSyncModels.*;

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
 * 9.1.2.1 平台订单同步。
 *
 * <p><b>命名空间是 {@code /trade/platform-orders}</b>：与 9.1.2.2 手工单的
 * {@code /trade/sales-orders} 并列而不是包含它 —— 两者是<b>并列</b>的订单来源
 * （蓝图 9.1.2 下的两个 L4），共用一张统一订单模型但入口必须分得开：
 * 让「平台事件受理」和「手工录单」共用一条路径，等于开了一条
 * 「用手动录单的表单伪造平台单」的后门。
 *
 * <p><b>两个写入口，一个落库路径</b>：{@code POST /webhook}（实时接入）与
 * {@code POST /poll}（轮询补拉）都调同一个服务层方法 —— 蓝图要求二者走同一条路径，
 * 两套逻辑各写一遍迟早分叉（这正是集成测试会盯住的那条）。
 *
 * <p><b>租户不来自任何请求头</b>：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/trade/platform-orders")
public class PlatformOrderSyncController {

  private final AccessService access;
  private final PlatformOrderSyncService sync;

  public PlatformOrderSyncController(AccessService access, PlatformOrderSyncService sync) {
    this.access = access;
    this.sync = sync;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "trade." + permission);
    return access.run(actor, "trade." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /** 本模块的能力与上下游可用性（含未交付的下游与各自的归属模块）。 */
  @GetMapping("/capabilities")
  public PlatformOrderSyncCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "orders.read", actor -> sync.capabilities());
  }

  // ================================================================ 受理

  /**
   * webhook 实时接入：一批平台订单事件 → 解析 → 归一 → 落库。
   *
   * <p>整批在一个事务里：批次的计数与结论因此永远是这一批的真实结果。
   */
  @PostMapping("/webhook")
  public PlatformOrderSyncResult webhook(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PlatformOrderSyncWebhookInput input) {
    return call(auth, "orders.create", actor -> sync.webhook(input, actor));
  }

  /** 轮询补拉：按来源 + 时间窗把平台接口返回的订单补进来（与 webhook 同一条落库路径）。 */
  @PostMapping("/poll")
  public PlatformOrderSyncResult poll(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PlatformOrderSyncPollInput input) {
    return call(auth, "orders.create", actor -> sync.poll(input, actor));
  }

  // ================================================================ 读

  /** 同步批次列表：真分页 + 与分页无关的全量汇总。 */
  @GetMapping("/batches")
  public PlatformOrderSyncBatchPage batches(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String triggerType,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "orders.read",
        actor ->
            sync.pageBatches(
                new PlatformOrderSyncBatchQuery(platformCode, shopCode, status, triggerType, page, size)));
  }

  /** 批次详情：批次 + 逐条受理结果 + 异常明细。 */
  @GetMapping("/batches/{id}")
  public PlatformOrderSyncBatchDetail batch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "orders.read", actor -> sync.batch(id));
  }

  /** 平台单列表：真分页 + 与分页无关的全量汇总。 */
  @GetMapping("/orders")
  public PlatformOrderSyncOrderPage orders(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String normalizedStatus,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "orders.read",
        actor ->
            sync.pageOrders(
                new PlatformOrderSyncOrderQuery(
                    platformCode, shopCode, normalizedStatus, keyword, page, size)));
  }

  /** 平台单详情：投影 + 组件展开（按映射版本）+ 逐条受理记录。 */
  @GetMapping("/orders/{id}")
  public PlatformOrderSyncOrderDetail order(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "orders.read", actor -> sync.order(id));
  }

  /** 异常明细列表：真分页 + 按错误码的汇总。 */
  @GetMapping("/exceptions")
  public PlatformOrderSyncExceptionPage exceptions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String errorCode,
      @RequestParam(required = false) String externalOrderNo,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "orders.read",
        actor ->
            sync.pageExceptions(
                new PlatformOrderSyncExceptionQuery(
                    platformCode, errorCode, externalOrderNo, page, size)));
  }

  /** 映射副本列表（2.1.2.1 的本模块副本 + 租户自维护行）。 */
  @GetMapping("/mappings")
  public PlatformOrderSyncMappingPage mappings(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String enumKind,
      @RequestParam(required = false) String mappingSource,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "orders.read",
        actor ->
            sync.pageMappings(
                new PlatformOrderSyncMappingQuery(
                    platformCode, enumKind, mappingSource, page, size)));
  }

  // ================================================================ 映射副本写入

  /** 租户自维护映射副本（平台默认码表尚未覆盖到的新平台状态词走这里）。 */
  @PostMapping("/mappings")
  public PlatformOrderSyncMappingRow registerMapping(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PlatformOrderSyncMappingInput input) {
    return call(auth, "orders.update", actor -> sync.registerMapping(input));
  }

  /**
   * 从 2.1.2.1（channel 服务）同步映射副本。
   *
   * <p>转发的是<b>调用方自己的</b> Authorization bearer —— 租户由 channel 侧按已认证成员关系
   * 解析并在 channel 侧强制 RLS，本模块不替 channel 传租户号。
   */
  @PostMapping("/mappings/sync")
  public PlatformOrderSyncMappingSyncResult syncMappings(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PlatformOrderSyncMappingSyncInput input) {
    return call(auth, "orders.update", actor -> sync.syncMappings(input, auth));
  }
}
