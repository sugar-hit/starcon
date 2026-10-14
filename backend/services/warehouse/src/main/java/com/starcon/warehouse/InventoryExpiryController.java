package com.starcon.warehouse;

import static com.starcon.warehouse.InventoryExpiryModels.*;

import jakarta.validation.Valid;
import java.util.List;
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
 * 7.3.1.2 效期与先进先出。
 *
 * <p>命名空间 {@code /warehouse/inventory/expiry}：与总览、明细、台账、批次同一前缀， 浏览器只经 /api/warehouse 代理访问。
 *
 * <p>刻意**不挂在 {@code /warehouse/inventory/lots} 下面**：那是 7.3.1.1 的批次档案。 本模块消费批次但不拥有批次 ——
 * 效期档案、生产日期、批次备注全部由批次模块维护， 挂在它的路径下会让人以为效期页可以改批次属性。
 *
 * <p>权限码沿用 {@code warehouse.}：读用 {@code warehouse.inventory.read}， 写策略用 {@code
 * warehouse.inventory.update} —— 出库分配策略是防错发的配置， 不是读权限能带过去的动作。
 *
 * <p>租户**不**来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限， 再在一个事务里绑定 {@code app.current_tenant}，由
 * RLS 在数据库层强制隔离。
 */
@RestController
@RequestMapping("/warehouse/inventory/expiry")
public class InventoryExpiryController {

  private final AccessService access;
  private final InventoryExpiryService expiry;

  public InventoryExpiryController(AccessService access, InventoryExpiryService expiry) {
    this.access = access;
    this.expiry = expiry;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  // ------------------------------------------------------------ 效期预警

  /**
   * 效期预警清单（临期 / 过期 / 非效期品）。
   *
   * <p>不传 {@code expiryStatus} 时**只看需要处理的两档**（EXPIRED + EXPIRING）： 预警清单的用途是「找出待办的货」。
   *
   * <p>{@code leadDays} 是临时覆盖，不传则逐 SKU 用它自己策略里的阈值。
   */
  @GetMapping("/alerts")
  public ExpiryAlertPage alerts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) List<String> expiryStatus,
      @RequestParam(required = false) Integer leadDays,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Boolean inStock,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            expiry.alerts(
                new ExpiryAlertQuery(
                    skuId,
                    warehouseId,
                    expiryStatus,
                    leadDays,
                    keyword,
                    inStock,
                    sort,
                    page,
                    size)));
  }

  // ------------------------------------------------------------ 分配策略

  /** 策略列表 + 全量汇总 + 策略下拉词表。 */
  @GetMapping("/policies")
  public ExpiryPolicyPage policies(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) List<String> strategy,
      @RequestParam(required = false) Boolean enabled,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            expiry.policies(
                new ExpiryPolicyQuery(skuId, strategy, enabled, keyword, sort, page, size)));
  }

  /**
   * 某个 SKU 的**生效策略**。
   *
   * <p>没配过也返回 200 且 {@code configured=false}：界面要能区分「配成 30 天」与「没人配」。 SKU 不存在或跨租户才是 404。
   */
  @GetMapping("/policy/{skuId}")
  public ExpiryPolicy policy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID skuId) {
    return call(auth, "inventory.read", a -> expiry.policy(skuId));
  }

  /**
   * 建立或修改某 SKU 的出库分配策略（按 SKU 幂等 upsert）。
   *
   * <p>用 POST：它真的落库。同一个 SKU 重复提交是**改**而不是报冲突 —— 策略是「一个 SKU 一份当前配置」，重复提交等于改配置。
   *
   * <p>刻意**没有** DELETE 端点：停用走 {@code enabled=false}，策略行永久保留 （DB 侧也 REVOKE 了 DELETE），否则「这个 SKU 曾经按
   * FEFO 出过货」就再也说不清了。
   */
  @PostMapping("/policy")
  public ExpiryPolicySaved upsertPolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ExpiryPolicyUpsertRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> expiry.upsertPolicy(a.tenantId(), request == null ? emptyUpsert() : request));
  }

  // ------------------------------------------------------------ 分配推荐

  /**
   * 出库分配推荐（FEFO / FIFO / 指定批次）。
   *
   * <p>用 POST：它要带 {@code quantity} 这类实打实的分配请求体，且语义是「算一次方案」而不是取资源。
   *
   * <p>本端点**只读不写**：它出推荐，扣减由 8 拣货的业务单据负责。
   */
  @PostMapping("/allocation")
  public FefoAllocationPlan recommend(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FefoAllocationRequest request) {
    return call(
        auth,
        "inventory.read",
        a -> expiry.recommend(request == null ? emptyAllocation() : request));
  }

  // ------------------------------------------------------------ 下拉参照

  /** 仓库清单。分配推荐限定单仓。 */
  @GetMapping("/warehouses")
  public List<ExpiryWarehouseOption> warehouses(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> expiry.warehouses());
  }

  /** 某 SKU 的批次下拉（指定批次策略用），按失效日期升序。 */
  @GetMapping("/lots/{skuId}")
  public List<ExpiryLotOption> lots(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID skuId) {
    return call(auth, "inventory.read", a -> expiry.lots(skuId));
  }

  /** 空请求体落到这里：{@code @Valid} 对 null 不生效，必须由服务层报出可读错误。 */
  private static ExpiryPolicyUpsertRequest emptyUpsert() {
    return new ExpiryPolicyUpsertRequest(null, null, null, null, null, null);
  }

  private static FefoAllocationRequest emptyAllocation() {
    return new FefoAllocationRequest(null, null, null, null, null);
  }
}
