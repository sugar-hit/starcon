package com.starcon.catalog;

import static com.starcon.catalog.PriceListPriorityModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 5.1.2.1 优先级与冲突检测。
 *
 * <p>命名空间 {@code /catalog/price-lists}（与 5.1.1.1 同一前缀下的**不同**子路径）。
 * 页面路由 {@code /pricing/lists/priority}。
 *
 * <p>权限沿用商品主数据域：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}。
 * 不新造权限词表 —— 新词表要改 identity 服务的迁移，那是另一个服务的写入所有权。
 *
 * <p><b>租户不来自任何请求头</b>：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 客户端伪造的 {@code X-Tenant-Id} 进不来也改不动它。
 *
 * <h2>🔴 与 5.1.1.1 的路径分工</h2>
 *
 * <ul>
 *   <li>{@code /capabilities} {@code /scope-directory} {@code /coverage} {@code /lists} {@code /lists/{uuid}}
 *       —— **5.1.1.1** 的，不动。
 *   <li>{@code /priority/*} {@code /lists/{uuid}/priority} {@code /lists/{uuid}/conflicts}
 *       {@code /conflicts} {@code /resolve} —— **本模块**的。
 * </ul>
 *
 * <p>🔴 请求体**不接受** tenantId / userId / membershipId：租户只来自已认证成员关系。
 */
@RestController
@RequestMapping("/catalog/price-lists")
public class PriceListPriorityController {

  private final AccessService access;
  private final PriceListPriorityService priorities;

  public PriceListPriorityController(AccessService access, PriceListPriorityService priorities) {
    this.access = access;
    this.priorities = priorities;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  /**
   * 能力声明：优先级语义 + 六个维度的覆盖情况（含「同 SKU 未覆盖」的原因）。
   */
  @GetMapping("/priority/capabilities")
  public PriceListPriorityCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> priorities.capabilities());
  }

  /** 本租户全部优先级规则。 */
  @GetMapping("/priority/rules")
  public List<PriorityRuleView> rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> priorities.allRules());
  }

  /** 某张价目表的优先级规则。 */
  @GetMapping("/lists/{priceListId}/priority")
  public List<PriorityRuleView> listRules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID priceListId) {
    return call(auth, "spu.read", a -> priorities.rules(priceListId));
  }

  /**
   * 保存某张价目表的优先级规则（整体替换）。
   *
   * <p>🔴 检出阻断级冲突 ⇒ 409 + 冲突明细 ⇒ 事务回滚 ⇒ 什么都没保存。
   */
  @PostMapping("/lists/{priceListId}/priority")
  public PriorityRulesSaved saveRules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID priceListId,
      @Valid @RequestBody PriorityRulesInput input) {
    return call(
        auth,
        "spu.update",
        a -> priorities.saveRules(a, priceListId, input, auth));
  }

  /**
   * 某张价目表的冲突明细（蓝图输出句：「冲突检测结果」）。
   *
   * <p>🔴 上游读不到 ⇒ <b>503</b>（理由写「读不到上游」），不用空列表冒充「没有冲突」。
   */
  @GetMapping("/lists/{priceListId}/conflicts")
  public ConflictReport conflicts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID priceListId) {
    return call(auth, "spu.read", a -> guard(() -> priorities.conflicts(priceListId, auth)));
  }

  /** 全租户冲突概览。 */
  @GetMapping("/conflicts")
  public TenantConflictOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> guard(() -> priorities.overview(auth)));
  }

  /**
   * 解析「这个 SKU 在这个店铺此刻该卖多少」。
   *
   * <p>🔴 同优先级并列 ⇒ 409 + 并列候选清单（**不随机取一条**）。
   */
  @PostMapping("/resolve")
  public Resolution resolve(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ResolveRequest request) {
    return call(auth, "spu.read", a -> guard(() -> priorities.resolve(request, auth)));
  }

  /** 上游读不到 ⇒ 503（而不是空结果冒充「没有冲突」）。 */
  private <T> T guard(java.util.function.Supplier<T> work) {
    try {
      return work.get();
    } catch (PriceListScopePort.ScopeUnavailableException
        | ChannelPlatformPort.ChannelPlatformUnavailableException e) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }
  }
}
