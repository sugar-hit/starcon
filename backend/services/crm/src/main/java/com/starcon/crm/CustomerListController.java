package com.starcon.crm;

import static com.starcon.crm.CustomerListModels.*;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 15.3.1.1 客户列表。
 *
 * <p>命名空间 {@code /crm/customers}：前端入口 {@code /crm/customers}，
 * 浏览器只经 {@code /api/crm} 代理访问；后端路径按<b>服务</b>命名空间走，
 * 与 catalog（/catalog/...）、trade（/trade/...）同一纪律。
 *
 * <p>⚠️ 与同卷兄弟模块（{@code /crm/customer-tags} 15.3.2.1 客户标签、
 * {@code /crm/customer-contacts} 15.3.2.2 接触记录、{@code /crm/knowledge} 15.4.2.1 知识库、
 * {@code /crm/review-requests} 15.2.2.1 索评、{@code /crm/review-rules} 15.2.2.2 索评规则、
 * {@code /crm/buyer-emails} 15.1.2.1 买家邮件）**刻意不共用命名空间**：
 * 那些回答的是「这位客户属于哪一类人」「我跟这位客户接触过没有」「这句话怎么答」，
 * 本模块回答的是「租户有哪些客户、各自的情况怎么样」。四者不共用任何一张表。
 *
 * <p>租户<b>不</b>来自任何请求头：先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 FORCE RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>🔴 权限码用 {@code crm.customers.*}：
 *
 * <ul>
 *   <li>{@code crm.customers.read} —— 读列表与统计（联系方式**脱敏**）；
 *   <li>{@code crm.customers.update} —— 刷新客户档案、登记联系方式；
 *   <li>{@code crm.customers.pii.read} —— **明文联系方式**：
 *       蓝图加粗句「列表默认脱敏显示联系方式，需要显式授权才完整展示」在权限码这一层的落点。
 *       能看客户列表 ≠ 能看手机号 ⇒ 与 {@code read} **刻意分开**。</li>
 * </ul>
 */
@RestController
@RequestMapping("/crm/customers")
public class CustomerListController {

  private final AccessService access;
  private final CustomerListService customers;

  public CustomerListController(AccessService access, CustomerListService customers) {
    this.access = access;
    this.customers = customers;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "crm.customers." + permission);
    return access.run(actor, "crm.customers." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：前端在渲染前就知道哪些维度有、哪些没有。
   *
   * <p>🔴 里面显式说明「客户是**按来源店铺**的、逻辑客户由 15.3.1.3 拥有」，
   * 以及「联系方式默认脱敏，明文要额外的权限码」。
   */
  @GetMapping("/capabilities")
  public CrmCustomerListCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", a -> customers.capabilities());
  }

  // ================================================================ 列表

  /**
   * 客户列表：真分页 + 多维筛选 + 与分页无关的分层统计。
   *
   * @param revealContact 是否显式要求明文联系方式（还要**另有**
   *     {@code crm.customers.pii.read} 权限才给；没有就是 403，不是悄悄退回脱敏）
   */
  @GetMapping
  public CrmCustomerListPage customers(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String sourceShop,
      @RequestParam(required = false) String tagCode,
      @RequestParam(required = false) String valueTier,
      @RequestParam(required = false) String riskFlag,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Integer minOrderCount,
      @RequestParam(required = false) BigDecimal minTotalAmount,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "false") boolean revealContact,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        a ->
            customers.customerPage(
                sourceShop,
                tagCode,
                valueTier,
                riskFlag,
                keyword,
                minOrderCount,
                minTotalAmount,
                currency,
                sort,
                page,
                size,
                revealContact,
                a));
  }

  // ================================================================ 刷新

  /**
   * 从上游刷新客户档案（上游 = trade 的订单与售后事实，经授权 HTTP API 只读）。
   *
   * <p>上游读不到 ⇒ <b>503</b>，一条客户档案都不落；绝不返回空集合冒充「上游没有客户」。
   */
  @PostMapping("/sync")
  public CrmCustomerListSyncResult sync(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody(required = false) CrmCustomerListSyncInput input) {
    return call(auth, "update", a -> customers.sync(input, a.userId(), auth));
  }

  /** 刷新运行记录列表（真分页）：这次扫了多少、截断没有、联系方式补齐了几个。 */
  @GetMapping("/runs")
  public CrmCustomerListSyncRunPage runs(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "read", a -> customers.runPage(page, size));
  }

  // ================================================================ 联系方式

  /**
   * 登记 / 修正联系方式（人工路径）。
   *
   * <p>客户必须**已经存在**于本租户的客户主档里（按来源店铺 + 客户标识定位）。
   *
   * <p>返回的行仍然是**脱敏**形态：明文要走列表端点的显式授权，
   * 写入端点**不**顺带把明文吐出来。
   */
  @PutMapping("/contact")
  public CrmCustomerListRow registerContact(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmCustomerListContactInput input) {
    return call(auth, "update", a -> customers.registerContact(input, a.userId(), false));
  }
}
