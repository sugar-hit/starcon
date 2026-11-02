package com.starcon.crm;

import static com.starcon.crm.CustomerDetailModels.*;

import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 15.3.1.2 客户详情。
 *
 * <p>命名空间 {@code /crm/customers/{id}}：与 15.3.1.1 客户列表（{@code /crm/customers}）
 * **同一业务对象（客户）的两个面**，子路径**互不重叠**：
 *
 * <ul>
 *   <li>15.3.1.1 拥有：{@code GET /crm/customers}（列表）、{@code /capabilities}、
 *       {@code /runs}、{@code /sync}、{@code PUT /contact}；</li>
 *   <li>本模块拥有：{@code GET /crm/customers/{id}}（详情视图）、
 *       {@code GET /crm/customers/{id}/timeline}（统一时间线）、
 *       {@code POST /crm/customers/{id}/timeline/rebuild}（重建时间线）。</li>
 * </ul>
 *
 * <p>🔴 路径变量用**UUID 正则**约束：这样 {@code /crm/customers/capabilities}
 * 之类**根本不会**落到本控制器上 —— 两个控制器各管一段路径，不需要靠「谁更具体」
 * 的隐式规则去仲裁。
 *
 * <p>租户<b>不</b>来自任何请求头：先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 FORCE RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>🔴 权限码沿用 15.3.1.1 的 {@code crm.customers.*}（同一个业务对象）：
 *
 * <ul>
 *   <li>{@code crm.customers.read} —— 读详情与时间线（联系方式**脱敏**）；
 *   <li>{@code crm.customers.update} —— 重建统一时间线（它要落库）；
 *   <li>{@code crm.customers.pii.read} —— **明文联系方式**：
 *       蓝图加粗句「列表默认脱敏显示联系方式，需要显式授权才完整展示」在详情页同一口径。</li>
 * </ul>
 */
@RestController
@RequestMapping("/crm/customers")
public class CustomerDetailController {

  /** UUID 路径变量的正则：非 UUID 一律不落到本控制器（404）。 */
  static final String UUID_PATTERN =
      "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

  private final AccessService access;
  private final CustomerDetailService details;

  public CustomerDetailController(AccessService access, CustomerDetailService details) {
    this.access = access;
    this.details = details;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "crm.customers." + permission);
    return access.run(actor, "crm.customers." + permission, () -> work.apply(actor));
  }

  // ================================================================ 详情视图

  /**
   * 客户详情视图：基本信息、金额、标签、跨店铺身份线索、逐段接入状态、时间线概要。
   *
   * @param revealContact 是否显式要求明文联系方式（还要**另有**
   *     {@code crm.customers.pii.read} 权限才给；没有就是 403，不是悄悄退回脱敏）
   */
  @GetMapping("/{id:" + UUID_PATTERN + "}")
  public CrmCustomerDetailView detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestParam(required = false, defaultValue = "false") boolean revealContact) {
    return call(auth, "read", a -> details.detail(UUID.fromString(id), revealContact, a));
  }

  // ================================================================ 统一时间线

  /**
   * 统一时间线（订单 / 售后 / 消息 / 评价 / 接触记录合并）：真分页 + 按来源筛选。
   *
   * @param sourceKind 只看某一个来源（空 = 全部）
   */
  @GetMapping("/{id:" + UUID_PATTERN + "}/timeline")
  public CrmCustomerDetailTimelinePage timeline(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestParam(required = false) String sourceKind,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth, "read", a -> details.timeline(UUID.fromString(id), sourceKind, page, size, a));
  }

  // ================================================================ 重建时间线

  /**
   * 重建这位客户的统一时间线（幂等）。
   *
   * <p>上游读不到 ⇒ 该来源标为「未接入 / 读不到」并写明原因与归属模块，
   * **绝不**编一条时间线条目；整次运行的状态是 {@code PARTIAL}（部分来源读不到）
   * 或 {@code FAILED}（一条都没落）。
   */
  @PostMapping("/{id:" + UUID_PATTERN + "}/timeline/rebuild")
  public CrmCustomerDetailTimelineRun rebuild(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(
        auth, "update", a -> details.rebuild(UUID.fromString(id), a, auth));
  }
}
