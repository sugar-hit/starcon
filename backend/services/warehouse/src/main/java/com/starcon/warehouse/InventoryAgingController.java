package com.starcon.warehouse;

import static com.starcon.warehouse.InventoryAgingModels.*;

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
 * 7.6.1.1 库龄与呆滞。
 *
 * <p>命名空间 {@code /warehouse/inventory/aging}：与总览、明细、台账、批次、效期同一前缀， 浏览器只经 {@code /api/warehouse}
 * 代理访问。
 *
 * <p>刻意**不挂在 {@code /warehouse/inventory/lots} 下面**：那是 7.3.1.1 的批次档案。 本模块消费批次（取 {@code received_on}
 * 算库龄）但不拥有批次 —— 挂在它路径下会让人以为库龄页能改批次属性。
 *
 * <p>也刻意**不挂在 {@code /expiry} 下面**：临期只是本模块的四个判定维度之一， 效期档案与预警清单由 7.3.1.2 拥有。
 *
 * <p>权限码沿用 {@code warehouse.}：读用 {@code warehouse.inventory.read}。 <b>本模块不提供任何写库存的端点</b> ——
 * 处置建议只是依据，调价属 5.4.1、 退仓报废属 6.4.2.2，都未交付。唯一可写的是阈值策略（{@code warehouse.inventory.update}），
 * 因为它改的是本模块自己的报表口径，不动任何库存数量。
 *
 * <p>租户**不**来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限， 再在一个事务里绑定 {@code app.current_tenant}，由
 * RLS 在数据库层强制隔离。
 */
@RestController
@RequestMapping("/warehouse/inventory/aging")
public class InventoryAgingController {

  private final AccessService access;
  private final InventoryAgingService aging;

  public InventoryAgingController(AccessService access, InventoryAgingService aging) {
    this.access = access;
    this.aging = aging;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  // ------------------------------------------------------------ 库龄分桶报表

  /**
   * 库龄分桶报表（0-30 / 31-60 / 61-90 / 90+ 天）。
   *
   * <p>四个桶恒定输出（命中 0 的桶也在），并返回 {@code totals} 与 {@code capitalTie}（资金占用估算的诚实回答）。
   */
  @GetMapping("/buckets")
  public AgingBucketReport buckets(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) UUID legalEntityId,
      @RequestParam(required = false) String agingBasis,
      @RequestParam(required = false) String keyword) {
    return call(
        auth,
        "inventory.read",
        a ->
            aging.bucketReport(
                new AgingBucketQuery(skuId, warehouseId, legalEntityId, agingBasis, keyword)));
  }

  // ------------------------------------------------------------ 呆滞清单

  /**
   * 呆滞清单（真分页 + 真筛选）。
   *
   * <p>每行带 {@code disposition}（处置建议）、{@code basis}（判定依据）与 {@code automatable=false} ——
   * 本模块只给依据，不触发动作。
   */
  @GetMapping("/slow-moving")
  public SlowMovingPage slowMoving(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) UUID legalEntityId,
      @RequestParam(required = false) List<String> slowMovingStatus,
      @RequestParam(required = false) String disposition,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            aging.slowMoving(
                new SlowMovingQuery(
                    skuId,
                    warehouseId,
                    legalEntityId,
                    slowMovingStatus,
                    disposition,
                    keyword,
                    sort,
                    page,
                    size)));
  }

  // ------------------------------------------------------------ 阈值策略

  /** 阈值策略列表 + 兜底默认值。 */
  @GetMapping("/policies")
  public AgingPolicyPage policies(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) Boolean enabled,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a -> aging.policies(new AgingPolicyQuery(skuId, enabled, keyword, sort, page, size)));
  }

  /** 单个 SKU 的生效策略：配了用配的，没配用默认值并标 {@code configured=false}。 */
  @GetMapping("/policy/{skuId}")
  public AgingPolicyEffective policy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID skuId) {
    return call(auth, "inventory.read", a -> aging.policy(skuId));
  }

  /** 建立或修改某 SKU 的库龄阈值策略（按 SKU 幂等 upsert）。 */
  @PostMapping("/policy")
  public AgingPolicyEffective savePolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AgingPolicyUpsertRequest request) {
    var actor = access.require(auth, "warehouse.inventory.update");
    return access.run(
        actor, "warehouse.inventory.update", () -> aging.upsertPolicy(actor.tenantId(), request));
  }
}
