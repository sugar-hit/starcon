package com.starcon.platform;

import static com.starcon.platform.PaymentFundsModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 18.2.2.1「支付与资金」的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /platform/payment-funds}</b>：与同服务的 {@code /platform/apps}
 * （谁可以接入我们）、{@code /platform/text}（文案怎么翻）、
 * {@code /platform/finance-software}（凭证怎么交给外部财务软件）同服务、不同前缀，
 * 三者管的是完全不同的东西。与 finance 服务自己的 {@code /finance/payment-orders}、
 * {@code /finance/fund-accounts} 也**不是一回事** —— 那一组是**业务账**
 * （本系统自己的付款单与资金账户），本组是**外部资金通道**与**本系统的资金流水台账**。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p><b>权限码 {@code platform.payment.read / .create / .update / .execute / .sync}</b>：
 * 「付款执行」与「直连同步」是**两个独立的码** —— 能配服务商档案的人不该顺手就能发起付款。
 *
 * <p>🔴 <b>「服务商未接入」一律 409</b>，而**不是** 200：
 * 一个 200 会被前端当成「这笔钱处理完了」。响应体里带着那条**如实标注**未接入的操作记录
 * （status = NOT_CONNECTED、provider_notified = false、写明缺什么归谁）。
 */
@RestController
@RequestMapping("/platform/payment-funds")
public class PaymentFundsController {

  private final AccessService access;
  private final PaymentFundsService funds;

  public PaymentFundsController(AccessService access, PaymentFundsService funds) {
    this.access = access;
    this.funds = funds;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "platform.payment." + permission);
    return access.run(actor, "platform.payment." + permission, () -> work.apply(actor));
  }

  /**
   * 「服务商未接入」的统一出口：<b>409</b> + 如实标注的操作记录。
   *
   * <p>🔴 刻意<b>不抛异常</b>：抛了会让事务回滚，那条「谁在什么时候发起了一次付款请求、
   * 服务商侧没接通」的留痕就丢了 —— 而留痕正是这个模块**自己那一层**的价值。
   */
  private static ResponseEntity<PaymentOperationRow> settled(PaymentOperationRow row) {
    if ("NOT_CONNECTED".equals(row.status())) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(row);
    }
    return ResponseEntity.ok(row);
  }

  // ================================================================ 能力声明与概览

  /** 能力声明：哪些真接通、哪些还没交付、归谁。 */
  @GetMapping("/capabilities")
  public PaymentFundsCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> funds.capabilities());
  }

  /** 概览：几个数。 */
  @GetMapping("/overview")
  public PaymentFundsOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> funds.overview());
  }

  /** 逐服务商的通道可用性（**逐家如实答**）。 */
  @GetMapping("/providers/availability")
  public List<PaymentProviderAvailability> availability(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> funds.availability());
  }

  // ================================================================ 服务商配置

  /** 服务商配置列表（真筛选）。 */
  @GetMapping("/providers")
  public List<PaymentProviderRow> providers(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String providerCode,
      @RequestParam(required = false) String status) {
    return call(auth, "read", actor -> funds.providerRows(providerCode, status));
  }

  /** 新增或修改一个服务商配置（**密钥只存引用**）。 */
  @PostMapping("/providers")
  public PaymentProviderRow saveProvider(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PaymentProviderInput input) {
    return call(auth, "create", actor -> funds.saveProvider(input, actor));
  }

  /** 停用一个服务商配置（**不删**：历史流水与操作记录要能复现）。 */
  @PostMapping("/providers/{id}/disable")
  public PaymentProviderRow disableProvider(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "update", actor -> funds.disableProvider(id, actor));
  }

  // ================================================================ 付款执行

  /**
   * **付款执行**。
   *
   * <p>🔴 同幂等键重复提交 ⇒ 原样返回既有结果（{@code replayed=true}），**绝不第二次调用服务商**。
   * 服务商未接入 ⇒ <b>409</b> + 如实标注的操作记录（<b>不写成功流水</b>）。
   */
  @PostMapping("/payments")
  public ResponseEntity<PaymentOperationRow> executePayment(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PaymentExecutionInput input) {
    return call(
        auth,
        "execute",
        actor -> settled(funds.executePayment(input, actor, auth)));
  }

  /**
   * **重发**一次付款（只在查证确认「服务商侧没有这笔」之后才允许）。
   *
   * <p>🔴 查证**没有**给出结论时一律 409 —— 「查不到」不等于「没付出去」。
   */
  @PostMapping("/operations/{id}/retry")
  public ResponseEntity<PaymentOperationRow> retryPayment(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "execute", actor -> settled(funds.retryPayment(id, actor, auth)));
  }

  // ================================================================ 收款登记

  /**
   * **收款登记**：记进本系统的资金流水台账，并尝试通知服务商侧。
   *
   * <p>⚠️ 与付款执行的**不同**：服务商未接入时**照样登记本地流水**，
   * 但 {@code provider_notified=false} 且 {@code provider_notice} 写明「服务商未接入」——
   * 一条 {@code status=SUCCEEDED 且 provider_notified=false} 的行
   * <b>不可能</b>被误读成「服务商侧已经登记过了」。
   */
  @PostMapping("/receipts")
  public PaymentOperationRow registerReceipt(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ReceiptRegistrationInput input) {
    return call(auth, "create", actor -> funds.registerReceipt(input, actor, auth));
  }

  // ================================================================ 操作台账

  /** 操作记录列表（真筛选 + 真关键词）。 */
  @GetMapping("/operations")
  public List<PaymentOperationRow> operations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String providerCode,
      @RequestParam(required = false) String operationType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword) {
    return call(
        auth,
        "read",
        actor -> funds.operationRows(providerCode, operationType, status, keyword));
  }

  /** 一条操作记录。 */
  @GetMapping("/operations/{id}")
  public PaymentOperationRow operation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> funds.operationRow(id));
  }

  /** 一次操作的尝试留痕（**查证兜底**的证据）。 */
  @GetMapping("/operations/{id}/attempts")
  public List<PaymentAttemptRow> attempts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> funds.attempts(id));
  }

  // ================================================================ 余额

  /** 余额观测列表（真筛选）。 */
  @GetMapping("/balances")
  public List<PaymentBalanceRow> balances(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID providerId,
      @RequestParam(required = false) String currency) {
    return call(auth, "read", actor -> funds.balanceRows(providerId, currency));
  }

  /** **余额查询**（服务商直查；未接入 ⇒ 409 且不写余额行）。 */
  @PostMapping("/balances/query")
  public ResponseEntity<PaymentOperationRow> queryBalance(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody BalanceQueryInput input) {
    return call(auth, "sync", actor -> settled(funds.queryBalance(input, actor, auth)));
  }

  /** **余额导入**（从服务商账单 / 对账单；今天就能用的路径）。 */
  @PostMapping("/balances/import")
  public PaymentBalanceRow importBalance(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody BalanceImportInput input) {
    return call(auth, "create", actor -> funds.importBalance(input, actor));
  }

  // ================================================================ 手续费明细

  /** 手续费明细列表（真筛选）。 */
  @GetMapping("/fees")
  public List<PaymentFeeRow> fees(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID providerId,
      @RequestParam(required = false) String currency) {
    return call(auth, "read", actor -> funds.feeRows(providerId, currency));
  }

  /** **手续费明细同步**（服务商直拉；未接入 ⇒ 409 且不写手续费行）。 */
  @PostMapping("/fees/sync")
  public ResponseEntity<PaymentOperationRow> syncFees(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID providerId,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String idempotencyKey) {
    return call(
        auth,
        "sync",
        actor -> settled(funds.syncFees(providerId, currency, idempotencyKey, actor, auth)));
  }

  /** **手续费明细导入**（从服务商账单 / 对账单；今天就能用的路径）。 */
  @PostMapping("/fees/import")
  public PaymentFeeRow importFee(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FeeImportInput input) {
    return call(auth, "create", actor -> funds.importFee(input, actor));
  }

  // ================================================================ 汇率

  /** 汇率观测列表（真筛选）。 */
  @GetMapping("/rates")
  public List<PaymentRateRow> rates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID providerId,
      @RequestParam(required = false) String baseCurrency,
      @RequestParam(required = false) String quoteCurrency) {
    return call(
        auth, "read", actor -> funds.rateRows(providerId, baseCurrency, quoteCurrency));
  }

  /**
   * **汇率查询**：真读 catalog 5.2.2.1 汇率事实并把观测值落库；
   * 服务商报价未接入时如实标注（但**不影响**参考汇率落地）。
   */
  @PostMapping("/rates/query")
  public PaymentOperationRow queryRate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody RateQueryInput input) {
    return call(auth, "create", actor -> funds.queryRate(input, actor, auth));
  }

  // ================================================================ 资金流水台账

  /** 资金流水列表（真筛选）。 */
  @GetMapping("/flows")
  public List<FundFlowRow> flows(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID providerId,
      @RequestParam(required = false) String flowType,
      @RequestParam(required = false) String currency) {
    return call(auth, "read", actor -> funds.fundFlowRows(providerId, flowType, currency));
  }

  // ================================================================ 上游（只读）

  /** 上游 12.7 资金账户（**只读**；读不到 ⇒ 503）。 */
  @GetMapping("/upstream/fund-accounts")
  public List<UpstreamFundAccountRow> upstreamFundAccounts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String keyword) {
    return call(auth, "read", actor -> funds.upstreamFundAccounts(currency, keyword, auth));
  }

  /** 上游 12.2 收付款（**只读**；读不到 ⇒ 503）。 */
  @GetMapping("/upstream/payment-orders")
  public List<UpstreamPaymentOrderRow> upstreamPaymentOrders(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String keyword) {
    return call(
        auth, "read", actor -> funds.upstreamPaymentOrders(status, currency, keyword, auth));
  }
}
