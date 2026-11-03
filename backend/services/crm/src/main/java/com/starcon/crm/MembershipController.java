package com.starcon.crm;

import static com.starcon.crm.MembershipModels.*;

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
 * 15.6.1.1 会员与等级。
 *
 * <p>命名空间 {@code /crm/customer-membership}：前端入口 {@code /crm/customer-membership}
 * （会员等级看板），浏览器只经 {@code /api/crm} 代理访问；后端路径按<b>服务</b>命名空间走，
 * 与 catalog（/catalog/...）、trade（/trade/...）同一纪律。
 *
 * <p>⚠️ 与同服务的 {@code /crm/customers}（15.3.1.1 客户列表）、
 * {@code /crm/customer-lifecycle}（15.6.2.1 生命周期）、
 * {@code /crm/customer-merge}（15.3.1.3 跨店合并）**刻意不共用命名空间**：
 * 客户列表回答「租户有哪些客户」，生命周期回答「这位客户该回购却没回购」，
 * 跨店合并回答「哪些来源店铺客户行其实是同一个人」，
 * 本模块回答「这位客户是什么等级、能享什么权益」。
 *
 * <p>租户<b>不</b>来自任何请求头：先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 FORCE RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>🔴 权限码用 {@code crm.membership.*}：
 *
 * <ul>
 *   <li>{@code crm.membership.read} —— 读会员档案、等级规则、等级变更记录与权益生效配置；</li>
 *   <li>{@code crm.membership.update} —— 配置等级规则与权益、重算会员等级。</li>
 * </ul>
 *
 * <p>🔴 <b>本控制器没有任何「设置等级」的端点</b>：蓝图加粗句「会员等级必须由真实消费数据驱动，
 * 手工挂等级会导致权益成本失控」。等级只由 {@code POST /compute} 从上游真实订单事实算出。
 */
@RestController
@RequestMapping("/crm/customer-membership")
public class MembershipController {

  private final AccessService access;
  private final MembershipService membership;

  public MembershipController(AccessService access, MembershipService membership) {
    this.access = access;
    this.membership = membership;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "crm.membership." + permission);
    return access.run(actor, "crm.membership." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：前端在渲染前就知道哪些维度有、哪些没有。
   *
   * <p>🔴 里面显式说明「人工覆盖等级本轮未交付」、
   * 「平台会员体系对接未交付」、「积分归 15.6.1.2」。
   */
  @GetMapping("/capabilities")
  public CrmMembershipCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", a -> membership.capabilities());
  }

  // ================================================================ 会员档案

  /** 会员档案列表（真分页 + 真筛选 + 与分页无关的统计）。 */
  @GetMapping("/members")
  public CrmMembershipMemberPage members(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String levelCode,
      @RequestParam(required = false) String sourceShop,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth, "read", a -> membership.memberPage(levelCode, sourceShop, keyword, page, size, a));
  }

  /** 单个会员详情（会员档案 + 该会员的等级变更记录）。 */
  @GetMapping("/members/{memberId}")
  public CrmMembershipMemberDetail member(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID memberId) {
    return call(auth, "read", a -> membership.memberDetail(memberId));
  }

  // ================================================================ 等级规则与权益

  /** 等级规则列表（真分页）：等级门槛是**租户数据**。 */
  @GetMapping("/levels")
  public CrmMembershipLevelRulePage levels(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String levelCode,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "read", a -> membership.levelRulePage(levelCode, page, size));
  }

  /** 单条等级规则。 */
  @GetMapping("/levels/{ruleId}")
  public CrmMembershipLevelRuleRow level(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId) {
    return call(auth, "read", a -> membership.levelRule(ruleId));
  }

  /**
   * 新建一条等级规则（+ 等级权益）。
   *
   * <p>🔴 请求体**不接受** tenantId / userId：租户来自已认证成员关系。
   * 🔴 请求体**也不接受** memberKey / levelCode 之外的身份字段 ——
   * 等级规则是**全租户**的配置，不是给某个人挂的。
   */
  @PostMapping("/levels")
  public CrmMembershipLevelRuleRow createLevel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmMembershipLevelRuleInput input) {
    return call(auth, "update", a -> membership.createLevelRule(input));
  }

  /**
   * 改一条等级规则。
   *
   * <p>🔴 <b>门槛与等级序不可就地修改</b>（不可变优先）：改了它，之前引用这条规则的
   * 会员判定就解释不通了。要改门槛请新建一条（新的生效起点）。
   */
  @PutMapping("/levels/{ruleId}")
  public CrmMembershipLevelRuleRow updateLevel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId,
      @Valid @RequestBody CrmMembershipLevelRuleInput input) {
    return call(auth, "update", a -> membership.updateLevelRule(ruleId, input));
  }

  /**
   * 权益生效配置 —— 这是交给 **5 定价 / 14 促销** 的东西。
   *
   * <p>🔴 本模块**只输出**这份配置，**不**去改价目表 / 促销表（跨服务禁止）。
   */
  @GetMapping("/benefits")
  public CrmMembershipBenefitConfig benefits(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", a -> membership.benefitConfig());
  }

  // ================================================================ 等级变更记录

  /** 等级变更记录列表（真分页 + 真筛选）：升级 / 降级 / 首次定级都留痕。 */
  @GetMapping("/level-changes")
  public CrmMembershipLevelChangePage levelChanges(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String memberKey,
      @RequestParam(required = false) String changeKind,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth, "read", a -> membership.levelChangePage(memberKey, changeKind, keyword, page, size));
  }

  // ================================================================ 重算与运行记录

  /**
   * 从上游订单事实重算会员等级。
   *
   * <p>🔴 上游读不到 ⇒ <b>503</b>，一条会员档案都不落；
   * 绝不返回空集合冒充「这个租户没有订单」。
   */
  @PostMapping("/compute")
  public CrmMembershipComputeResult compute(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody(required = false) CrmMembershipComputeInput input) {
    return call(auth, "update", a -> membership.compute(input, a.userId(), auth));
  }

  /** 重算运行记录列表（真分页）：这次扫了多少、变了多少级、有没有截断。 */
  @GetMapping("/runs")
  public CrmMembershipRunPage runs(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "read", a -> membership.runPage(page, size));
  }
}
