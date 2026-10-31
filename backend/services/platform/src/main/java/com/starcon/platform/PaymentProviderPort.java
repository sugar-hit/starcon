package com.starcon.platform;

import com.starcon.platform.PaymentFundsModels.PaymentAttemptOutcome;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * **支付与资金服务商适配器**（PayPal / Stripe / PingPong / 连连 / 万里汇）的**唯一边界**。
 *
 * <p>蓝图 18.2.2.1 的职责写明「对接支付与资金服务（PayPal、Stripe、PingPong、连连、万里汇）：
 * 余额查询、收款登记、付款执行、汇率查询、手续费明细同步」—— 这五件事**全部**要出去跟服务商说话，
 * 所以它们统一收敛到本端口后面：服务层只认本接口，**不认具体服务商**。
 *
 * <p>🔴 **本接口刻意没有「默认成功」的实现**：
 * 通道侧没有凭据 / 主机不在白名单 / 适配器未交付 ⇒ 适配器**必须**返回
 * 「服务商未接入」或「结果未知」，由服务层**如实记进操作台账**，
 * 绝不假装成功 —— 假装成功会让系统以为钱付出去了，而钱根本没动。
 *
 * <p>🔴 **凭据只有引用**：{@link ProviderContext#credentialRef()} 里是密钥管理器的
 * 引用 ID，**不是**明文。明文只在适配器解析出来的那一刻活在内存里，
 * 绝不入库、绝不入日志、绝不出现在请求摘要里。
 *
 * <p>🔴 <b>查证（{@link #queryPayment}）与发起（{@link #executePayment}）是<b>两个方法</b>，
 * 刻意不合并</b>：蓝图加粗句「所有第三方资金操作必须有幂等键与查询兜底，超时先查证再重试」
 * 要求「先查证」在代码里是**一次显式的、单独留痕的动作**，
 * 合并成一个方法就看不出到底有没有查过。
 */
public interface PaymentProviderPort {

  // ================================================================ 通道元信息

  /** 服务商代码（与 {@link PaymentFundsModels.PaymentProviderCode} 同名）。 */
  String providerCode();

  /** 服务商中文名。 */
  String providerLabel();

  /**
   * 这个通道**现在**通不通。
   *
   * <p>🔴 当前实现**恒为 {@code false}** —— 本仓没有任何真实的支付服务商凭据与网络通道。
   */
  boolean connected();

  /**
   * 不通的理由（写清「缺什么、归谁、现在怎么办」）。
   *
   * <p>🔴 **不返回空串**：空理由会让界面上出现一个没有解释的灰色开关，
   * 运营既不知道为什么不通，也不知道该找谁。
   */
  String unavailableReason();

  // ================================================================ 五件职责

  /** 余额查询：服务商侧账户余额。 */
  BalanceResult queryBalance(ProviderContext context, BalanceQuery query);

  /** 收款登记：把一笔收到的钱登记到服务商侧。 */
  ReceiptResult registerReceipt(ProviderContext context, ReceiptCommand command);

  /** 付款执行：发起一笔付款。 */
  PaymentResult executePayment(ProviderContext context, PaymentCommand command);

  /** 汇率查询：服务商侧的报价（锁汇）。 */
  RateResult quoteRate(ProviderContext context, RateCommand command);

  /** 手续费明细同步：拉服务商侧的收费明细。 */
  FeeResult fetchFeeDetails(ProviderContext context, FeeQuery query);

  /**
   * **查证**：服务商侧到底有没有这一笔。
   *
   * <p>🔴 <b>这是「超时 ≠ 失败」那条纪律的落点</b>：查证返回「成功」时，
   * 服务层**不会重发**（重复付款是严重资损事故）。
   */
  PaymentResult queryPayment(ProviderContext context, String idempotencyKey);

  // ================================================================ 传输形状

  /**
   * 一次调用的上下文（**只含引用，不含任何凭据明文**）。
   *
   * @param bearer 调用方自己的会话（跨上游调用时只转发它，不传租户号）
   */
  record ProviderContext(
      String providerCode,
      String baseUrl,
      String credentialRef,
      int timeoutSeconds,
      int maxAttempts,
      String bearer) {}

  /** 余额查询参数。 */
  record BalanceQuery(String currency) {}

  /** 收款登记指令。 */
  record ReceiptCommand(
      String idempotencyKey,
      String reference,
      String currency,
      BigDecimal amount,
      String baseCurrency,
      BigDecimal exchangeRate,
      BigDecimal baseAmount,
      String counterpartyName,
      String counterpartyRef,
      String counterpartyType,
      String note) {}

  /** 付款指令。 */
  record PaymentCommand(
      String idempotencyKey,
      String reference,
      String currency,
      BigDecimal amount,
      String baseCurrency,
      BigDecimal exchangeRate,
      BigDecimal baseAmount,
      String counterpartyName,
      String counterpartyRef,
      String counterpartyType,
      String note) {}

  /** 汇率报价参数。 */
  record RateCommand(String baseCurrency, String quoteCurrency, LocalDate asOf) {}

  /** 手续费明细查询参数。 */
  record FeeQuery(String currency, LocalDate dateFrom, LocalDate dateTo) {}

  // ================================================================ 结果

  /** 余额查询结果。 */
  record BalanceResult(
      PaymentAttemptOutcome outcome,
      BigDecimal balanceAmount,
      BigDecimal availableAmount,
      String currency,
      String providerSerialNo,
      String reason) {}

  /** 收款登记结果。 */
  record ReceiptResult(
      PaymentAttemptOutcome outcome,
      String providerSerialNo,
      BigDecimal creditedAmount,
      String currency,
      String reason) {}

  /** 付款结果。 */
  record PaymentResult(
      PaymentAttemptOutcome outcome,
      String providerSerialNo,
      BigDecimal paidAmount,
      String paidCurrency,
      String reason) {}

  /** 汇率报价结果。 */
  record RateResult(
      PaymentAttemptOutcome outcome,
      BigDecimal rate,
      boolean inverted,
      String baseCurrency,
      String quoteCurrency,
      LocalDate effectiveDate,
      String sourceRef,
      String reason) {}

  /** 手续费明细结果。 */
  record FeeResult(PaymentAttemptOutcome outcome, List<FeeDetail> fees, String reason) {}

  /** 一条手续费明细。 */
  record FeeDetail(
      String feeType,
      BigDecimal amount,
      String currency,
      BigDecimal chargedAmount,
      String providerSerialNo,
      String providerReference,
      OffsetDateTime occurredAt,
      String summary) {}
}
