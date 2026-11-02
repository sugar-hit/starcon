package com.starcon.crm;

import static com.starcon.crm.PointsModels.*;

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
 * 15.6.1.2 积分与权益。
 *
 * <p>命名空间 {@code /crm/points}：前端入口 {@code /crm/customer-points}
 * （积分与权益看板），浏览器只经 {@code /api/crm} 代理访问；后端路径按<b>服务</b>命名空间走，
 * 与 catalog（/catalog/...）、trade（/trade/...）同一纪律。
 *
 * <p>⚠️ 与同服务的 {@code /crm/customer-membership}（15.6.1.1 会员与等级）、
 * {@code /crm/customers}（15.3.1.1 客户列表）**刻意不共用命名空间**：
 * 会员与等级回答「这位客户是什么等级、能享什么权益」，
 * 本模块回答「这位客户有多少积分、积分什么时候过期、能换什么权益、还欠财务多少负债」。
 *
 * <p>租户<b>不</b>来自任何请求头：先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 FORCE RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>🔴 权限码用 {@code crm.points.*}：
 *
 * <ul>
 *   <li>{@code crm.points.read} —— 读积分账户、流水、规则、权益目录、核销记录与负债汇总；</li>
 *   <li>{@code crm.points.update} —— 配置规则与权益、发放 / 消耗积分、兑换与核销权益、结算到期。</li>
 * </ul>
 */
@RestController
@RequestMapping("/crm/points")
public class PointsController {

  private final AccessService access;
  private final PointsService points;

  public PointsController(AccessService access, PointsService points) {
    this.access = access;
    this.points = points;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "crm.points." + permission);
    return access.run(actor, "crm.points." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：前端在渲染前就知道哪些维度有、哪些没有。
   *
   * <p>🔴 里面显式说明「12 财务侧未交付积分负债科目」「5 定价侧未交付积分抵扣写入」
   * 「积分冲正未交付」。
   */
  @GetMapping("/capabilities")
  public CrmPointsCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", a -> points.capabilities());
  }

  // ================================================================ 积分账户

  /** 积分账户列表（真分页 + 真筛选 + 与分页无关的统计）。 */
  @GetMapping("/accounts")
  public CrmPointsAccountPage accounts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String memberKey,
      @RequestParam(required = false) String sourceShop,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "false") boolean onlyWithBalance,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        a ->
            points.accountPage(
                memberKey, sourceShop, keyword, onlyWithBalance, page, size, a));
  }

  /** 单个积分账户详情（账户 + 积分批次 + 最近流水）。 */
  @GetMapping("/accounts/{accountId}")
  public CrmPointsAccountDetail account(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID accountId) {
    return call(auth, "read", a -> points.accountDetail(accountId));
  }

  // ================================================================ 积分规则

  /** 积分规则列表（真分页）：拿多少分 / 抵多少比例 / 单积分值多少 —— **全是租户数据**。 */
  @GetMapping("/rules")
  public CrmPointsRulePage rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String ruleType,
      @RequestParam(required = false) String ruleCode,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "read", a -> points.rulePage(ruleType, ruleCode, page, size));
  }

  /** 单条积分规则。 */
  @GetMapping("/rules/{ruleId}")
  public CrmPointsRuleRow rule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId) {
    return call(auth, "read", a -> points.rule(ruleId));
  }

  /**
   * 新建一条积分规则。
   *
   * <p>🔴 请求体**不接受** tenantId / userId：租户来自已认证成员关系。
   */
  @PostMapping("/rules")
  public CrmPointsRuleRow createRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmPointsRuleInput input) {
    return call(auth, "update", a -> points.createRule(input));
  }

  /**
   * 改一条积分规则。
   *
   * <p>🔴 <b>口径列不可就地修改</b>（不可变优先）：改了它，之前按它发过的积分就解释不通了。
   * 要改口径请新建一条（新的生效起点）。
   */
  @PutMapping("/rules/{ruleId}")
  public CrmPointsRuleRow updateRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId,
      @Valid @RequestBody CrmPointsRuleInput input) {
    return call(auth, "update", a -> points.updateRule(ruleId, input));
  }

  // ================================================================ 权益目录

  /** 权益目录列表（真分页）：可兑换的权益与兑换所需积分 —— **租户数据**。 */
  @GetMapping("/entitlements")
  public CrmPointsEntitlementPage entitlements(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String entitlementCode,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "read", a -> points.entitlementPage(entitlementCode, page, size));
  }

  /** 单项权益。 */
  @GetMapping("/entitlements/{entitlementId}")
  public CrmPointsEntitlementRow entitlement(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID entitlementId) {
    return call(auth, "read", a -> points.entitlement(entitlementId));
  }

  /** 新建一项权益。 */
  @PostMapping("/entitlements")
  public CrmPointsEntitlementRow createEntitlement(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmPointsEntitlementInput input) {
    return call(auth, "update", a -> points.createEntitlement(input));
  }

  // ================================================================ 积分流水

  /** 积分流水列表（真分页 + 真筛选）：每一行都带「扣前余额 → 扣后余额」。 */
  @GetMapping("/ledger")
  public CrmPointsLedgerPage ledger(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String memberKey,
      @RequestParam(required = false) String direction,
      @RequestParam(required = false) String reasonCode,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "read", a -> points.ledgerPage(memberKey, direction, reasonCode, page, size));
  }

  // ================================================================ 积分获取与消耗

  /**
   * 积分获取（消费 / 评价 / 签到 / 人工）。
   *
   * <p>🔴 上游读不到 ⇒ <b>503</b>，一条积分都不发；
   * 绝不返回空集合冒充「这个租户没有订单」。
   */
  @PostMapping("/earn")
  public CrmPointsEarnResult earn(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmPointsEarnInput input) {
    return call(auth, "update", a -> points.earn(input, a.userId(), auth));
  }

  /**
   * 积分消耗：订单抵扣。
   *
   * <p>🔴 抵扣上限 = 订单金额 × 抵扣率上限 ÷ 单积分价值；
   * 订单币种与积分估值币种不一致时<b>直接拒绝</b>（不跨币种折算）。
   */
  @PostMapping("/spend")
  public CrmPointsSpendResult spend(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmPointsSpendInput input) {
    return call(auth, "update", a -> points.spend(input, a.userId(), auth));
  }

  // ================================================================ 权益兑换与核销

  /** 权益兑换：用积分换一份权益（落一条核销记录）。 */
  @PostMapping("/redeem")
  public CrmPointsRedeemResult redeem(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmPointsRedeemInput input) {
    return call(auth, "update", a -> points.redeem(input, a.userId()));
  }

  /** 权益核销记录列表（真分页 + 真筛选）。 */
  @GetMapping("/redemptions")
  public CrmPointsRedemptionPage redemptions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String memberKey,
      @RequestParam(required = false) String entitlementCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "read", a -> points.redemptionPage(memberKey, entitlementCode, status, page, size));
  }

  /** 权益核销：把已兑换的权益标成已核销（**单向**：REDEEMED → USED）。 */
  @PostMapping("/redemptions/{redemptionId}/use")
  public CrmPointsRedemptionRow use(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID redemptionId,
      @Valid @RequestBody(required = false) CrmPointsUseInput input) {
    return call(auth, "update", a -> points.use(redemptionId, input, a.userId()));
  }

  // ================================================================ 到期结算与负债汇总

  /**
   * 到期结算：把**已过期**批次的剩余积分清零，并留一条 EXPIRE 流水。
   *
   * <p>🔴 有效期按**绝对时刻**判定（{@code expires_at <= now()}，两者都是 timestamptz）。
   */
  @PostMapping("/expire")
  public CrmPointsExpireResult expire(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "update", a -> points.expire(a.userId()));
  }

  /**
   * 积分负债汇总 —— **这是交给 12 财务的东西**。
   *
   * <p>🔴 12 财务侧未交付积分负债科目：本模块只<b>输出</b>汇总，
   * <b>不代劳</b>在财务侧建科目，<b>不假装</b>已经入账
   * （出参 {@code bookedInFinance} 恒为 {@code false}）。
   */
  @GetMapping("/liability")
  public CrmPointsLiability liability(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", a -> points.liability());
  }
}
