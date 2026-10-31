package com.starcon.platform;

import com.starcon.platform.PaymentFundsModels.PaymentAttemptOutcome;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * **测试替身：站在支付服务商那一侧。**
 *
 * <p>🔴 <b>它替的是外部边界，不是本模块的实现</b>：被测的服务层、幂等键判定、
 * 查证兜底状态机、jOOQ 显式 SQL、RLS 全部是**真代码**；只有「服务商那一次网络往返」
 * 被替换掉了 —— 本仓没有任何真实的服务商凭据与网络通道（见
 * {@link AbstractUnavailablePaymentProviderAdapter}），而「超时 ⇒ 先查证再重试」
 * 这条纪律**只有在服务商会超时的情况下才测得到**。
 *
 * <p>⚠️ <b>它一次网络请求都不发</b>：所有结论都来自脚本里排好的队列。
 * 脚本排空后返回**最后一次**排的结果（而不是抛异常），
 * 这样「只排了一次却调用了两次」会表现为「第二次拿到同样的结果」——
 * 而调用次数由 {@link #sendCalls()} / {@link #queryCalls()} **单独**计数断言。
 */
class StubPaymentProviderPort implements PaymentProviderPort {

  /** 本替身顶替的服务商代码（与内置「未接入」实现同一个代码位）。 */
  static final String PROVIDER_CODE = "PAYPAL";

  private final Deque<PaymentResult> sends = new ArrayDeque<>();
  private final Deque<PaymentResult> queries = new ArrayDeque<>();
  private final Deque<ReceiptResult> receipts = new ArrayDeque<>();
  private final Deque<BalanceResult> balances = new ArrayDeque<>();
  private final Deque<RateResult> rates = new ArrayDeque<>();
  private final Deque<FeeResult> fees = new ArrayDeque<>();

  private final AtomicInteger sendCalls = new AtomicInteger();
  private final AtomicInteger queryCalls = new AtomicInteger();
  private final AtomicInteger receiptCalls = new AtomicInteger();

  /** 记录每一次调用的幂等键（用来断言「重发用的是同一个键」）。 */
  private final List<String> sentKeys = new ArrayList<>();
  private final List<String> queriedKeys = new ArrayList<>();

  // ---------------------------------------------------------------- 脚本

  /** 排一次付款回执（按调用顺序消费）。 */
  StubPaymentProviderPort scriptSend(PaymentResult result) {
    sends.add(result);
    return this;
  }

  /** 排一次查证回执（按调用顺序消费）。 */
  StubPaymentProviderPort scriptQuery(PaymentResult result) {
    queries.add(result);
    return this;
  }

  /** 排一次收款登记回执。 */
  StubPaymentProviderPort scriptReceipt(ReceiptResult result) {
    receipts.add(result);
    return this;
  }

  /** 排一次余额查询回执。 */
  StubPaymentProviderPort scriptBalance(BalanceResult result) {
    balances.add(result);
    return this;
  }

  /** 排一次汇率报价回执。 */
  StubPaymentProviderPort scriptRate(RateResult result) {
    rates.add(result);
    return this;
  }

  /** 排一次手续费明细回执。 */
  StubPaymentProviderPort scriptFees(FeeResult result) {
    fees.add(result);
    return this;
  }

  // ---------------------------------------------------------------- 断言用

  int sendCalls() {
    return sendCalls.get();
  }

  int queryCalls() {
    return queryCalls.get();
  }

  int receiptCalls() {
    return receiptCalls.get();
  }

  List<String> sentKeys() {
    return List.copyOf(sentKeys);
  }

  List<String> queriedKeys() {
    return List.copyOf(queriedKeys);
  }

  /** 清空脚本与计数（每个测试前复位）。 */
  void reset() {
    sends.clear();
    queries.clear();
    receipts.clear();
    balances.clear();
    rates.clear();
    fees.clear();
    sendCalls.set(0);
    queryCalls.set(0);
    receiptCalls.set(0);
    sentKeys.clear();
    queriedKeys.clear();
  }

  // ---------------------------------------------------------------- 端口实现

  @Override
  public String providerCode() {
    return PROVIDER_CODE;
  }

  @Override
  public String providerLabel() {
    return "PayPal（测试替身）";
  }

  @Override
  public boolean connected() {
    return true;
  }

  @Override
  public String unavailableReason() {
    return "";
  }

  @Override
  public BalanceResult queryBalance(ProviderContext context, BalanceQuery query) {
    return balances.isEmpty()
        ? new BalanceResult(
            PaymentAttemptOutcome.NOT_CONNECTED, null, null, query.currency(), "", "未接入")
        : balances.poll();
  }

  @Override
  public ReceiptResult registerReceipt(ProviderContext context, ReceiptCommand command) {
    receiptCalls.incrementAndGet();
    return receipts.isEmpty()
        ? new ReceiptResult(
            PaymentAttemptOutcome.NOT_CONNECTED, "", null, command.currency(), "未接入")
        : receipts.poll();
  }

  @Override
  public PaymentResult executePayment(ProviderContext context, PaymentCommand command) {
    sendCalls.incrementAndGet();
    sentKeys.add(command.idempotencyKey());
    return sends.isEmpty()
        ? new PaymentResult(
            PaymentAttemptOutcome.SUCCESS, "STUB-SERIAL-" + sendCalls.get(), null, null, "")
        : sends.poll();
  }

  @Override
  public RateResult quoteRate(ProviderContext context, RateCommand command) {
    return rates.isEmpty()
        ? new RateResult(
            PaymentAttemptOutcome.NOT_CONNECTED, null, false, null, null, null, "", "未接入")
        : rates.poll();
  }

  @Override
  public FeeResult fetchFeeDetails(ProviderContext context, FeeQuery query) {
    return fees.isEmpty()
        ? new FeeResult(PaymentAttemptOutcome.NOT_CONNECTED, List.of(), "未接入")
        : fees.poll();
  }

  @Override
  public PaymentResult queryPayment(ProviderContext context, String idempotencyKey) {
    queryCalls.incrementAndGet();
    queriedKeys.add(idempotencyKey);
    return queries.isEmpty()
        ? new PaymentResult(PaymentAttemptOutcome.UNKNOWN, "", null, null, "查证脚本排空")
        : queries.poll();
  }

  // ---------------------------------------------------------------- 便捷构造

  static PaymentResult success(String serial, String amount) {
    return new PaymentResult(
        PaymentAttemptOutcome.SUCCESS, serial, new BigDecimal(amount), null, "");
  }

  static PaymentResult timeout() {
    return new PaymentResult(
        PaymentAttemptOutcome.TIMEOUT, "", null, null, "渠道调用超时：结果未知，必须先查证再重发");
  }

  static PaymentResult unknown() {
    return new PaymentResult(
        PaymentAttemptOutcome.UNKNOWN, "", null, null, "渠道回单无法解析：结果未知");
  }

  static PaymentResult rejected(String reason) {
    return new PaymentResult(PaymentAttemptOutcome.REJECTED, "", null, null, reason);
  }

  static PaymentResult notFound() {
    return new PaymentResult(
        PaymentAttemptOutcome.NOT_FOUND, "", null, null, "渠道侧没有这笔付款（HTTP 404）");
  }

  static PaymentResult notConnected() {
    return new PaymentResult(PaymentAttemptOutcome.NOT_CONNECTED, "", null, null, "服务商未接入");
  }

  static ReceiptResult receiptNotConnected() {
    return new ReceiptResult(
        PaymentAttemptOutcome.NOT_CONNECTED, "", null, null, "服务商未接入");
  }

  static ReceiptResult receiptSuccess(String serial, String amount) {
    return new ReceiptResult(
        PaymentAttemptOutcome.SUCCESS, serial, new BigDecimal(amount), null, "");
  }

  static BalanceResult balanceSuccess(String currency, String amount) {
    return new BalanceResult(
        PaymentAttemptOutcome.SUCCESS,
        new BigDecimal(amount),
        new BigDecimal(amount),
        currency,
        "STUB-BAL",
        "");
  }

  static RateResult rateSuccess(String base, String quote, String rate) {
    return new RateResult(
        PaymentAttemptOutcome.SUCCESS,
        new BigDecimal(rate),
        false,
        base,
        quote,
        null,
        "stub",
        "");
  }

  static FeeResult feeSuccess(String feeType, String amount, String currency) {
    return new FeeResult(
        PaymentAttemptOutcome.SUCCESS,
        List.of(
            new FeeDetail(
                feeType,
                new BigDecimal(amount),
                currency,
                new BigDecimal("100.00"),
                "STUB-SERIAL",
                "STUB-REF",
                OffsetDateTime.parse("2026-10-31T00:00:00Z"),
                "测试手续费")),
        "");
  }
}
