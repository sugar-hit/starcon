package com.starcon.finance;

import static com.starcon.finance.PaymentExecutionModels.*;

import com.starcon.finance.PaymentOrderModels.FinancePaymentExchangeRateView;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 12.2.2.2 付款执行的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /finance/payment-executions}</b>：与 12.2.2.1 的
 * {@code /finance/payment-orders}、12.1.2.2 的 {@code /finance/payment-plans}
 * 各自独立前缀 —— 「哪个模块的哪个端点」必须说得清。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>🔴 <b>渠道调用被刻意拆成三个事务</b>（准备 → 调渠道 → 落结论）：
 * 渠道调用**绝不在数据库事务里**。详见 {@link PaymentExecutionService} 的类注释。
 *
 * <p>🔴 <b>重发端点先查证再重发</b>：{@code POST /{id}/retry} 的第一步永远是
 * 状态查证；查证发现渠道侧已成功 ⇒ <b>不重发</b>，直接收敛成「已付」。
 */
@RestController
@RequestMapping("/finance/payment-executions")
public class PaymentExecutionController {

  private final AccessService access;
  private final PaymentExecutionService executions;

  public PaymentExecutionController(AccessService access, PaymentExecutionService executions) {
    this.access = access;
    this.executions = executions;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String scope, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, scope + "." + permission);
    return access.run(actor, scope + "." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明与筛选项

  /** 能力声明：哪些真接通、哪些还没交付、归谁。 */
  @GetMapping("/capabilities")
  public FinancePaymentExecutionCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "finance.paymentexecution", "read", actor -> executions.capabilities());
  }

  /** 筛选项下拉（全部来自真实数据）。 */
  @GetMapping("/options")
  public FinancePaymentExecutionOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "finance.paymentexecution", "read", actor -> executions.options());
  }

  // ================================================================ 执行单

  /** 执行单列表：真分页 + 真筛选 + 真排序 + 汇总（汇总覆盖整个筛选结果集，不是当前页）。 */
  @GetMapping
  public FinancePaymentExecutionPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String failureClass,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String channelCode,
      @RequestParam(required = false) UUID orderId,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "finance.paymentexecution",
        "read",
        actor ->
            executions.page(
                new PaymentExecutionQuery(
                    enumOf(status, PaymentExecutionStatus.class, "执行状态"),
                    enumOf(failureClass, PaymentFailureClass.class, "失败分类"),
                    currency,
                    channelCode,
                    orderId,
                    keyword,
                    page,
                    size)));
  }

  /** 执行单详情：执行单 + 尝试记录 + 事件流 + 分摊 + 锁汇。 */
  @GetMapping("/{id}")
  public FinancePaymentExecutionDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "finance.paymentexecution", "read", actor -> executions.detail(id));
  }

  /**
   * 可发起付款执行的付款单（已批 / 执行中）。
   *
   * <p>页面据此列出「哪些钱现在可以付」；已经建过执行单的那一笔会带上执行单号与状态。
   */
  @GetMapping("/pending-orders")
  public List<FinancePaymentExecutionPendingOrder> pendingOrders(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth, "finance.paymentexecution", "read", actor -> executions.pendingOrders());
  }

  // ================================================================ 渠道配置

  /** 支付渠道列表（**只含密钥引用**，没有明文）。 */
  @GetMapping("/channels")
  public List<FinancePaymentChannelRow> channels(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "finance.paymentexecution", "read", actor -> executions.channels());
  }

  /** 登记一个支付渠道（凭据只存**引用**）。 */
  @PostMapping("/channels")
  public FinancePaymentChannelRow registerChannel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinancePaymentChannelInput input) {
    return call(
        auth, "finance.paymentexecution", "create", actor -> executions.registerChannel(input, actor));
  }

  /** 改一个支付渠道的配置。 */
  @PutMapping("/channels/{id}")
  public FinancePaymentChannelRow updateChannel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody FinancePaymentChannelInput input) {
    return call(
        auth, "finance.paymentexecution", "update", actor -> executions.updateChannel(id, input, actor));
  }

  // ================================================================ 付款执行

  /**
   * 发起一次付款执行（对一张**已批**付款单）。
   *
   * <p>🔴 **三个阶段、三个事务**：渠道调用在事务外（详见服务类注释）。
   */
  @PostMapping
  public FinancePaymentExecutionResult execute(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinancePaymentExecutionInput input) {
    var actor = access.require(auth, "finance.paymentexecution.create");
    var permission = "finance.paymentexecution.create";
    var prepared = access.run(actor, permission, () -> executions.prepare(input, actor));
    var outcome = executions.dispatch(prepared);
    return access.run(actor, permission, () -> executions.settle(prepared, outcome, actor));
  }

  /**
   * 状态查证（**不重发**）。
   *
   * <p>查证结论会如实落库：渠道侧成功 ⇒ 收敛成「已付」；渠道侧没有 ⇒ 可以重发；
   * 查不出来 ⇒ 结果仍未知，**拒绝重发**。
   */
  @PostMapping("/{id}/verify")
  public FinancePaymentExecutionResult verify(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    var actor = access.require(auth, "finance.paymentexecution.update");
    var permission = "finance.paymentexecution.update";
    var prepared = access.run(actor, permission, () -> executions.prepareVerify(id, actor));
    var outcome = executions.dispatch(prepared);
    return access.run(actor, permission, () -> executions.settle(prepared, outcome, actor));
  }

  /**
   * 重发。
   *
   * <p>🔴 <b>第一步永远是查证</b>：查证发现渠道侧已成功 ⇒ <b>不重发</b>；
   * 查证仍查不出来 ⇒ **拒绝重发**（重复付款是严重资损事故）；
   * 只有确认渠道侧确实没有成功时才重发，且**用同一个幂等键**。
   */
  @PostMapping("/{id}/retry")
  public FinancePaymentExecutionResult retry(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    var actor = access.require(auth, "finance.paymentexecution.update");
    var permission = "finance.paymentexecution.update";
    // ① 查证
    var verifyAction = access.run(actor, permission, () -> executions.prepareVerify(id, actor));
    var verifyOutcome = executions.dispatch(verifyAction);
    var verified = access.run(actor, permission, () -> executions.settle(verifyAction, verifyOutcome, actor));
    if (!verified.retryAllowed()) {
      return verified;
    }
    // ② 查证确认「确实没有成功」⇒ 才允许重发
    var resendAction = access.run(actor, permission, () -> executions.prepareResend(id, actor));
    var resendOutcome = executions.dispatch(resendAction);
    return access.run(actor, permission, () -> executions.settle(resendAction, resendOutcome, actor));
  }

  // ================================================================ 汇率

  /**
   * 当前市场汇率（**只读** catalog 5.2.2.1）。
   *
   * <p>用来对照单据**锁定**的汇率：页面要让人看见「锁定时是这个价，现在市价是那个价」，
   * 而不是把两者混成一个数。
   */
  @GetMapping("/market-rate")
  public FinancePaymentExchangeRateView marketRate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String baseCurrency,
      @RequestParam String quoteCurrency) {
    return call(
        auth,
        "finance.paymentexecution",
        "read",
        actor -> executions.marketRate(baseCurrency, quoteCurrency, auth));
  }

  // ================================================================ 工具

  /** 词表解析：非法取值给一句能直接展示的话，而不是一个枚举名。 */
  private static <T extends Enum<T>> T enumOf(String raw, Class<T> type, String label) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return Enum.valueOf(type, raw.trim());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "取值不合法：" + raw);
    }
  }
}
