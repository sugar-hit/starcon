package com.starcon.trade;

import static com.starcon.trade.AddressPaymentCheckModels.*;

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
 * 9.2.2.1 地址与支付校验。
 *
 * <p>命名空间 {@code /trade/order-address-payment-check}：与 9.2.1.2 的审单规则（{@code /trade/order-review}）、
 * 9.2.1.3 的异常拦截（{@code /trade/order-intercept}）、9.2.2.2 的风控校验
 * （{@code /trade/orders/risk-validation}）分属不同命名空间 ——
 * 「判这单地址能不能送到」「判这单有没有异常」「算出风险分并建议拒发」是三件事，
 * 权限与日志都该分得开。
 *
 * <p>权限码用 {@code trade.orders.read} / {@code trade.orders.update}：登记禁运清单与国别规则、
 * 触发校验都直接改变一单发不发，不是读权限能带过去的动作。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p><b>刻意不提供的能力</b>：没有任何端点能改写一条已落库的校验结论 ——
 * 校验结果是凭据（V210 的明细表 append-only，合计与风险等级由触发器实算）。
 * 也没有「一键放行」「批量改判」端点。
 */
@RestController
@RequestMapping("/trade/order-address-payment-check")
public class AddressPaymentCheckController {

  private final AccessService access;
  private final AddressPaymentCheckService checks;

  public AddressPaymentCheckController(AccessService access, AddressPaymentCheckService checks) {
    this.access = access;
    this.checks = checks;
  }

  /** 统一入口：先确认身份与权限（租户<b>不</b>来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "trade." + permission);
    return access.run(actor, "trade." + permission, () -> work.apply(actor));
  }

  // ============================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>存在的理由：蓝图输入里的「支付状态」在本服务**没有这个事实**（12 财务未交付），
   * 加粗句里的「保险策略」在蓝图中**没有任何 L4 模块持有**。
   * <b>哪几项真接通、哪几项靠租户登记、哪几项现在根本判不了，必须由接口自己说</b>，
   * 而不是让界面从空数组或 404 里猜哪一环没通。
   */
  @GetMapping("/capabilities")
  public AddressPaymentCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "orders.read", a -> checks.capabilities());
  }

  /** 全量概览（不受筛选与分页影响）。 */
  @GetMapping("/overview")
  public AddressPaymentOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "orders.read", a -> checks.overview());
  }

  // ============================================================ 策略

  /** 校验策略读取。没登记过就是 404 —— 不是一份带默认值的策略。 */
  @GetMapping("/policy")
  public AddressPaymentPolicy policy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "orders.read", a -> checks.policy());
  }

  /**
   * 登记 / 更新校验策略（upsert，一租户一条）。
   *
   * <p>本模块<b>不提供</b>「未配置时的默认策略」：校验结论会被 9.2.1.3 异常拦截与 10 物流读，
   * 「没配 = 什么都不拦」与「清单位国家真的不在禁运清单里」长得一模一样。
   * 登记时顺带幂等装一份基线码表（国别地址格式规则 + 支付一致性规则）。
   */
  @PutMapping("/policy")
  public AddressPaymentPolicy savePolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AddressPaymentPolicyInput input) {
    return call(auth, "orders.update", a -> checks.savePolicy(input));
  }

  // ============================================================ 禁运清单

  /** 禁运国家清单列表（真分页 + 真搜索）。 */
  @GetMapping("/embargo-rules")
  public AddressPaymentEmbargoRulePage embargoRules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String embargoLevel,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth, "orders.read", a -> checks.pageEmbargoRules(keyword, embargoLevel, page, size));
  }

  /**
   * 登记 / 更新一条禁运国家。
   *
   * <p>这是本模块禁运清单的<b>唯一数据入口</b>：它不是占位，是真实参与判定的业务数据。
   * 16 合规的正式清单到位后由同步器按同一形状写入，只需把 source 改成
   * MODULE_16_COMPLIANCE，表结构与判定逻辑都不动。
   */
  @PostMapping("/embargo-rules")
  public AddressPaymentEmbargoRule saveEmbargoRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AddressPaymentEmbargoRuleInput input) {
    return call(auth, "orders.update", a -> checks.saveEmbargoRule(input));
  }

  // ============================================================ 国别规则

  /** 国别地址格式规则列表（真分页 + 真搜索）。 */
  @GetMapping("/country-rules")
  public AddressPaymentCountryRulePage countryRules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "orders.read", a -> checks.pageCountryRules(keyword, page, size));
  }

  /**
   * 登记 / 更新国别地址格式规则。
   *
   * <p>邮编正则在服务端<b>先编译一遍</b>：写进去一条编译不过的正则，
   * 会让该国的邮编项永远判不了，而界面上它显示为「已配置」。
   */
  @PostMapping("/country-rules")
  public AddressPaymentCountryRule saveCountryRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AddressPaymentCountryRuleInput input) {
    return call(auth, "orders.update", a -> checks.saveCountryRule(input));
  }

  // ============================================================ 支付规则

  /** 支付状态一致性规则列表（真分页 + 真筛选）。 */
  @GetMapping("/payment-rules")
  public AddressPaymentPaymentRulePage paymentRules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String paymentMethod,
      @RequestParam(required = false) String observedStatus,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "orders.read",
        a -> checks.pagePaymentRules(paymentMethod, observedStatus, page, size));
  }

  /** 登记 / 更新支付状态一致性规则。 */
  @PostMapping("/payment-rules")
  public AddressPaymentPaymentRule savePaymentRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AddressPaymentPaymentRuleInput input) {
    return call(auth, "orders.update", a -> checks.savePaymentRule(input));
  }

  // ============================================================ 校验

  /** 校验结果列表（真分页 + 真筛选）。 */
  @GetMapping("/checks")
  public AddressPaymentCheckPage checks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String riskLevel,
      @RequestParam(required = false) String deliveryStatus,
      @RequestParam(required = false) UUID orderId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "orders.read",
        a -> checks.pageChecks(keyword, riskLevel, deliveryStatus, orderId, page, size));
  }

  /**
   * 对一张订单跑一次地址与支付校验。
   *
   * <p>判定基准时刻取<b>订单的提交时刻</b>而不是当前时间：同一张单重复校验结果恒定。
   * 请求体<b>不接收</b>「要不要直接放行」之类的参数 —— 本模块只产出校验结果与风险等级，
   * 放不放行是 9.2.1.3 异常拦截与人的事。
   */
  @PostMapping("/checks")
  public AddressPaymentCheckSaved assess(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AddressPaymentCheckInput input) {
    return call(auth, "orders.update", a -> checks.assess(input, a.membershipId()));
  }

  /** 校验详情：头 + 七行明细。跨租户在 RLS 下查不到 → 404。 */
  @GetMapping("/checks/{checkId}")
  public AddressPaymentCheckDetail check(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID checkId) {
    return call(auth, "orders.read", a -> checks.check(checkId));
  }

  /**
   * 某订单<b>最近一次</b>校验结果 —— <b>9.2.1.3 异常拦截的读路径</b>。
   *
   * <p>本模块的结果要能被 9.2.1.3 读到。跨服务消费者（例如 10 物流的渠道可用性）
   * 走这个<b>授权 API</b>，而不是跨库读表（AGENTS.md 模块边界纪律：禁止跨服务数据库访问）。
   *
   * <p>返回 404 而不是空对象：该订单<b>从未校验过</b>与「校验结果是空」是两件事。
   */
  @GetMapping("/orders/{orderId}/latest")
  public AddressPaymentCheckDetail latest(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId) {
    return call(auth, "orders.read", a -> checks.latestForOrder(orderId));
  }
}
