package com.starcon.finance;

import com.starcon.finance.PaymentExecutionModels.PaymentAttemptOutcome;
import java.math.BigDecimal;

/**
 * 支付渠道适配器（**port**）—— 12.2.2.2 付款执行对外的**唯一**边界。
 *
 * <p>蓝图 12-finance.md：「<b>职责：执行付款：对接支付渠道/银企直连，发送付款指令，跟踪结果，
 * 失败重试与异常处理；处理汇率锁汇。</b>输入：已批付款单；<b>支付渠道配置（密钥引用）</b>；汇率锁定。
 * 输出：<b>付款结果 + 渠道流水号 + 失败原因</b>。」
 *
 * <p>🔴 <b>本接口刻意没有「默认成功」的实现</b>：
 * 通道侧没有凭据 / 主机不在白名单 / 渠道未启用 ⇒ 适配器**必须**返回
 * 「渠道不可用 / 未配置」，并由服务层**如实记成失败**，绝不假装成功 ——
 * 假装成功会让系统以为钱付出去了，而钱根本没动。
 *
 * <p>🔴 <b>凭据只有引用</b>：{@link PaymentChannelConfig#secretRef()} 里是密钥管理器的
 * 引用 ID，**不是**明文。明文只在适配器解析出来的那一刻活在内存里，
 * 绝不入库、绝不入日志、绝不出现在请求摘要里。
 *
 * <p><b>渠道协议（HTTP 实现遵循的契约）</b>：
 *
 * <pre>
 *   POST {baseUrl}/payments
 *     Headers: Authorization: Bearer {secret}
 *              Idempotency-Key: {idempotencyKey}
 *     Body:    { reference, amount, currency, baseAmount?, baseCurrency?, exchangeRate?,
 *                beneficiary: { type, name, ref? }, note? }
 *     200 { status: "SUCCESS", serialNo, paidAmount?, currency? }   → 成功
 *     200 { status: "FAILED", reason }                             → 渠道明确拒绝
 *     409 { status: "SUCCESS", serialNo }                          → 幂等重放（同一键已成功）
 *     4xx                                                          → 明确拒绝（确定性失败）
 *     5xx / 超时 / 网络不可达                                       → **结果未知**（必须先查证）
 *
 *   GET {baseUrl}/payments/{idempotencyKey}
 *     200 { status: "SUCCESS", serialNo, paidAmount?, currency? }   → 渠道侧确实成功
 *     200 { status: "FAILED", reason }                             → 渠道侧确实失败
 *     404                                                         → 渠道侧**没有**这笔
 *     其它                                                         → **查不出来**（结果仍未知）
 * </pre>
 */
public interface PaymentChannelPort {

  /** 渠道配置（**只含引用**，不含任何凭据明文）。 */
  record PaymentChannelConfig(
      String code,
      String name,
      String paymentMethod,
      String baseUrl,
      String secretRef,
      int timeoutSeconds,
      int maxAttempts) {}

  /** 付款指令：钱要付给谁、付多少、按哪个汇率。 */
  record PaymentInstruction(
      String idempotencyKey,
      String reference,
      String currency,
      BigDecimal amount,
      String baseCurrency,
      BigDecimal exchangeRate,
      BigDecimal baseAmount,
      String beneficiaryType,
      String beneficiaryName,
      String beneficiaryRef,
      String note) {}

  /**
   * 渠道回执。
   *
   * @param paidAmount 渠道确认**实付**的金额（可空：渠道没回金额时不做金额比对）
   * @param paidCurrency 渠道确认的币种（可空）
   */
  record ChannelOutcome(
      PaymentAttemptOutcome outcome,
      String channelSerialNo,
      String failureReason,
      String responseSummary,
      BigDecimal paidAmount,
      String paidCurrency) {}

  /**
   * 预检：这条渠道**现在**能不能用？
   *
   * @return {@code null} = 可用；否则返回一句能直接展示给用户的原因
   *     （例如「渠道凭据解析不到」）。预检**不发任何请求**。
   */
  String unavailableReason(PaymentChannelConfig config);

  /** 发送付款指令。 */
  ChannelOutcome send(PaymentChannelConfig config, PaymentInstruction instruction);

  /**
   * 状态查证：渠道侧到底有没有这笔。
   *
   * <p>🔴 <b>这是「超时 ≠ 失败」那条纪律的落点</b>：查证返回「成功」时，
   * 服务层**不会重发**（重复付款是严重资损事故）。
   */
  ChannelOutcome query(PaymentChannelConfig config, String idempotencyKey);
}
