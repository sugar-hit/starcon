package com.starcon.crm;

import static com.starcon.crm.CustomerLifecycleModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 15.6.2.1 生命周期与流失预警。
 *
 * <p>命名空间 {@code /crm/customer-lifecycle}：前端入口 {@code /crm/customer-lifecycle}
 * （客户留存看板），浏览器只经 {@code /api/crm} 代理访问；后端路径按<b>服务</b>命名空间走，
 * 与 catalog（/catalog/...）、trade（/trade/...）同一纪律。
 *
 * <p>⚠️ 与同服务的 {@code /crm/customers}（15.3.1.1 客户列表）、
 * {@code /crm/cart-recovery}（15.6.2.2 购物车与弃单挽回）、
 * {@code /crm/customer-tags}（15.3.2.1 客户标签）**刻意不共用命名空间**：
 * 客户列表回答「租户有哪些客户」，购物车挽回回答「这位买家加购没走完」，
 * 本模块回答「这位客户买过、**该回购却没回购**」。
 *
 * <p>租户<b>不</b>来自任何请求头：先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 FORCE RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>🔴 权限码用 {@code crm.lifecycle.*}：
 *
 * <ul>
 *   <li>{@code crm.lifecycle.read} —— 读分层结果、预警清单与阈值策略；</li>
 *   <li>{@code crm.lifecycle.update} —— 重算分层与预警、改阈值策略、推进预警处理状态。</li>
 * </ul>
 */
@RestController
@RequestMapping("/crm/customer-lifecycle")
public class CustomerLifecycleController {

  private final AccessService access;
  private final CustomerLifecycleService lifecycle;

  public CustomerLifecycleController(AccessService access, CustomerLifecycleService lifecycle) {
    this.access = access;
    this.lifecycle = lifecycle;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "crm.lifecycle." + permission);
    return access.run(actor, "crm.lifecycle." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：前端在渲染前就知道哪些维度有、哪些没有。
   *
   * <p>🔴 里面显式说明「触达未交付（本模块只给建议）」、
   * 「客户是按来源店铺的」、「阈值来自租户按类目配的策略」。
   */
  @GetMapping("/capabilities")
  public CrmLifecycleCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", a -> lifecycle.capabilities());
  }

  // ================================================================ 分层结果

  /** 生命周期分层结果列表（真分页 + 真筛选 + 与分页无关的分层统计）。 */
  @GetMapping("/segments")
  public CrmLifecyclePage segments(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String segment,
      @RequestParam(required = false) String sourceShop,
      @RequestParam(required = false) String categoryCode,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "read", a -> lifecycle.segmentPage(segment, sourceShop, categoryCode, keyword, page, size, a));
  }

  // ================================================================ 预警清单

  /** 流失预警清单（真分页 + 真筛选）。 */
  @GetMapping("/alerts")
  public CrmLifecycleAlertPage alerts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String riskLevel,
      @RequestParam(required = false) String categoryCode,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "read", a -> lifecycle.alertPage(status, riskLevel, categoryCode, keyword, page, size));
  }

  /** 单条预警详情（含风险因子明细）。 */
  @GetMapping("/alerts/{alertId}")
  public CrmLifecycleAlertDetail alert(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID alertId) {
    return call(auth, "read", a -> lifecycle.alertDetail(alertId));
  }

  /** 标记已知悉（OPEN → ACKNOWLEDGED）。状态只能向前推进。 */
  @PostMapping("/alerts/{alertId}/acknowledge")
  public CrmLifecycleAlertRow acknowledge(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID alertId,
      @Valid @RequestBody(required = false) CrmLifecycleAlertStatusInput input) {
    return call(
        auth,
        "update",
        a -> lifecycle.acknowledgeAlert(alertId, input == null ? "" : input.note()));
  }

  /** 标记已处理（→ RESOLVED）。 */
  @PostMapping("/alerts/{alertId}/resolve")
  public CrmLifecycleAlertRow resolve(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID alertId,
      @Valid @RequestBody(required = false) CrmLifecycleAlertStatusInput input) {
    return call(auth, "update", a -> lifecycle.resolveAlert(alertId, input == null ? "" : input.note()));
  }

  // ================================================================ 阈值策略

  /** 阈值策略列表（真分页）：复购周期阈值是**租户数据**，可按类目配。 */
  @GetMapping("/policies")
  public CrmLifecyclePolicyPage policies(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String scope,
      @RequestParam(required = false) String categoryCode,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "read", a -> lifecycle.policyPage(scope, categoryCode, page, size));
  }

  /** 单条阈值策略。 */
  @GetMapping("/policies/{policyId}")
  public CrmLifecyclePolicyRow policy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID policyId) {
    return call(auth, "read", a -> lifecycle.policy(policyId));
  }

  /**
   * 新建一条阈值策略。
   *
   * <p>🔴 请求体**不接受** tenantId / userId / membershipId：租户来自已认证成员关系。
   */
  @PostMapping("/policies")
  public CrmLifecyclePolicyRow createPolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmLifecyclePolicyInput input) {
    return call(auth, "update", a -> lifecycle.createPolicy(input));
  }

  /**
   * 改一条阈值策略。
   *
   * <p>🔴 <b>阈值不可就地修改</b>（不可变优先）：改了它，之前引用这条策略的判定就解释不通了。
   * 要改阈值请新建一条（新的生效起点）。
   */
  @PutMapping("/policies/{policyId}")
  public CrmLifecyclePolicyRow updatePolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID policyId,
      @Valid @RequestBody CrmLifecyclePolicyInput input) {
    return call(auth, "update", a -> lifecycle.updatePolicy(policyId, input));
  }

  // ================================================================ 重算与运行记录

  /**
   * 从上游订单事实重算生命周期分层与流失预警。
   *
   * <p>🔴 上游读不到 ⇒ <b>503</b>，一条分层结果都不落；
   * 绝不返回空集合冒充「这个租户没有客户」。
   */
  @PostMapping("/compute")
  public CrmLifecycleComputeResult compute(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody(required = false) CrmLifecycleComputeInput input) {
    return call(auth, "update", a -> lifecycle.compute(input, a.userId(), auth));
  }

  /** 重算运行记录列表（真分页）：这次扫了多少、类目解析了几个、有没有截断。 */
  @GetMapping("/runs")
  public CrmLifecycleRunPage runs(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "read", a -> lifecycle.runPage(page, size));
  }
}
