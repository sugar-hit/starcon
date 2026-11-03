package com.starcon.platform;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 18.2.2.1「支付与资金」的传输模型。
 *
 * <p>蓝图 18-integration-and-automation.md 原文：
 *
 * <blockquote>
 *
 * 职责：对接支付与资金服务（PayPal、Stripe、PingPong、连连、万里汇）：余额查询、收款登记、付款执行、
 * 汇率查询、手续费明细同步。 输入：资金操作请求；接口配置（**密钥引用**）。 输出：操作结果 + 资金流水同步。
 * 关系：上游 = 12.2 收付款、12.7 资金账户；下游 = 支付服务商。
 * <b>所有第三方资金操作必须有幂等键与查询兜底</b>，超时先查证再重试（实体目录 §6）。
 *
 * </blockquote>
 *
 * <p><b>🔴 前缀是 {@code PaymentFunds}**：契约被 codegen 合并进<b>同一个扁平命名空间</b>，
 * 而 finance 服务（12 财务核算）已经有一整族 {@code FinancePayment*} 类型
 * （付款单 / 付款执行 / 结算…）。前缀撞车会让读代码的人以为是一件事。
 *
 * <p><b>🔴 本模块一个 {@code double} 都没有</b>：金额、余额、费率、手续费一律
 * {@link BigDecimal}（库里 {@code NUMERIC(20,6)}，汇率 {@code NUMERIC(24,12)}）。
 * 汇率尤其不能拿 double 算 —— 7.1234 用 double 存一遍再取出来就不是那个数了。
 *
 * <p><b>🔴 三个取值域刻意分开</b>，它们混在一起就是资损事故的配方：
 * {@link PaymentOperationStatus} 的 {@code UNKNOWN}（**结果未知**）、
 * {@link PaymentAttemptOutcome} 的 {@code REJECTED}（服务商**明确拒绝**）、
 * 与「服务商未接入」。超时**不是**失败。
 */
public final class PaymentFundsModels {

  private PaymentFundsModels() {}

  /** 金额小数位（与本服务 18.2.1.1 / finance 12.x 同一口径）。 */
  public static final int MONEY_SCALE = 6;

  // ================================================================ 能力声明

  /**
   * 一项能力（或一项「还没接通」的能力）。
   *
   * @param available 该能力**现在**能不能用（不是「将来会有」）
   * @param evidence 凭什么这么判（表 / 端点 / 规则的具体名字）
   * @param ownerModule 还没接通的能力，**缺在哪个模块**
   * @param note 缺了会怎样 / 缺了归谁
   */
  public record PaymentFundsCapability(
      String moduleId,
      String name,
      boolean available,
      String evidence,
      String ownerModule,
      String note) {}

  /** 一个「还没交付」的下游 / 兄弟能力。 */
  public record PaymentFundsGap(
      String moduleId, String name, String service, boolean available, String relation, String note) {}

  /** 能力声明总览。 */
  public record PaymentFundsCapabilities(
      List<PaymentFundsCapability> capabilities, List<PaymentFundsGap> gaps) {}

  /** 概览：几个数。 */
  public record PaymentFundsOverview(
      long providers,
      long activeProviders,
      long operations,
      long unknownOperations,
      long balances,
      long fundFlows,
      long fees,
      long rates,
      OffsetDateTime generatedAt) {}

  // ================================================================ 服务商

  /** 支付与资金服务商（蓝图点名的五个）。 */
  public enum PaymentProviderCode {
    /** PayPal。 */
    PAYPAL("PayPal"),
    /** Stripe。 */
    STRIPE("Stripe"),
    /** PingPong。 */
    PINGPONG("PingPong"),
    /** 连连国际。 */
    LIANLIAN("连连国际"),
    /** 万里汇（WorldFirst）。 */
    WORLDFIRST("万里汇");

    private final String label;

    PaymentProviderCode(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}（服务层给一句能直接展示的话）。 */
    public static PaymentProviderCode parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var v : values()) {
        if (v.name().equalsIgnoreCase(raw.trim())) return v;
      }
      return null;
    }
  }

  /** 某个服务商的通道可用性。 */
  public record PaymentProviderAvailability(
      String providerCode,
      String providerLabel,
      boolean connected,
      String reason,
      String ownerModule) {}

  /** 服务商配置一行（**密钥引用**，不是密钥）。 */
  public record PaymentProviderRow(
      UUID id,
      String providerCode,
      String providerLabel,
      String name,
      String baseUrl,
      /** 密钥管理器里的**引用 ID**（不是密钥本体）。 */
      String credentialRef,
      /** 末四位 / 人眼分辨用（非机密）。 */
      String credentialHint,
      String settlementCurrency,
      String status,
      String statusLabel,
      boolean credentialConfigured,
      int timeoutSeconds,
      int maxAttempts,
      long operationCount,
      long fundFlowCount,
      String remark,
      UUID createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 服务商配置（新增 / 修改）。 */
  public record PaymentProviderInput(
      @Schema(nullable = true) UUID id,
      @Schema(nullable = true) String providerCode,
      @Schema(nullable = true) String name,
      @Schema(nullable = true) String baseUrl,
      @Schema(nullable = true) String credentialRef,
      @Schema(nullable = true) String credentialHint,
      @Schema(nullable = true) String settlementCurrency,
      @Schema(nullable = true) String status,
      @Schema(nullable = true) Integer timeoutSeconds,
      @Schema(nullable = true) Integer maxAttempts,
      @Schema(nullable = true) String remark) {}

  // ================================================================ 资金操作

  /** 资金操作类型（蓝图五件职责 + 导入路径）。 */
  public enum PaymentOperationType {
    /** 余额查询。 */
    BALANCE_QUERY("余额查询"),
    /** 收款登记。 */
    RECEIPT_REGISTER("收款登记"),
    /** 付款执行。 */
    PAYMENT_EXECUTE("付款执行"),
    /** 汇率查询。 */
    RATE_QUERY("汇率查询"),
    /** 手续费明细同步。 */
    FEE_SYNC("手续费明细同步");

    private final String label;

    PaymentOperationType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 资金操作状态。
   *
   * <p>🔴 {@code UNKNOWN} 与 {@code FAILED} 是**两个不同的值**：
   * 超时 / 5xx / 不可达 ⇒ 结果**未知**（必须先查证）；服务商**明确拒绝** ⇒ 失败。
   * 把超时记成失败，重试逻辑就会跳过查证直接二次付款 —— 那就是资损。
   */
  public enum PaymentOperationStatus {
    /** 已受理，还没发出。 */
    PENDING("已受理"),
    /** 已发出，等服务商回单。 */
    SENDING("执行中"),
    /** 确定性成功。 */
    SUCCEEDED("成功"),
    /** 确定性失败（服务商明确拒绝）。 */
    FAILED("失败"),
    /** 结果未知（超时 / 不可达 / 查证查不出来）—— **必须先查证再重试**。 */
    UNKNOWN("结果未知"),
    /** 服务商未接入（**根本没发出去**）。 */
    NOT_CONNECTED("服务商未接入");

    private final String label;

    PaymentOperationStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 尝试种类。 */
  public enum PaymentAttemptKind {
    /** 发起。 */
    SEND("发起"),
    /** **查证**（超时或未知之后，向服务商确认这笔到底有没有发生）。 */
    QUERY("查证");

    private final String label;

    PaymentAttemptKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 尝试结论。 */
  public enum PaymentAttemptOutcome {
    /** 服务商明确给成功。 */
    SUCCESS("成功"),
    /** 服务商明确拒绝（确定性失败）。 */
    REJECTED("拒绝"),
    /** **查证**结果：服务商侧确实没有这一笔。 */
    NOT_FOUND("服务商侧没有这笔"),
    /** 超时（**结果未知**）。 */
    TIMEOUT("超时"),
    /** 回单无法解析 / 状态无法识别（**结果未知**）。 */
    UNKNOWN("结果未知"),
    /** 服务商未接入（**根本没发出去**）。 */
    NOT_CONNECTED("未接入");

    private final String label;

    PaymentAttemptOutcome(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 资金方向。 */
  public enum PaymentDirection {
    /** 进（收款）。 */
    INBOUND("进"),
    /** 出（付款）。 */
    OUTBOUND("出");

    private final String label;

    PaymentDirection(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 资金操作一行。 */
  public record PaymentOperationRow(
      UUID id,
      String idempotencyKey,
      String operationType,
      String operationTypeLabel,
      UUID providerId,
      String providerCode,
      String providerLabel,
      String direction,
      String status,
      String statusLabel,
      @Schema(nullable = true) BigDecimal amount,
      String currency,
      @Schema(nullable = true) BigDecimal baseAmount,
      String baseCurrency,
      @Schema(nullable = true) BigDecimal exchangeRate,
      String reference,
      String counterpartyName,
      String counterpartyRef,
      String counterpartyType,
      String providerSerialNo,
      String failureReason,
      String failureClass,
      /** 本次操作**是否已与服务商通道核对过**（服务商侧返回了明确结论）。 */
      boolean providerNotified,
      /** 服务商侧没被告知 / 没核对时的说明。 */
      String providerNotice,
      /** 操作来源：DIRECT（走服务商通道）/ IMPORT（账单导入，没调用服务商）。 */
      String source,
      int attemptCount,
      /** 是否允许重试（UNKNOWN 时只有「查证明确说服务商侧没有」才为真）。 */
      boolean retryAllowed,
      /** 请求内容摘要（SHA-256）：钉住「同一个幂等键必须对应同一个请求」。 */
      String requestDigest,
      /** 本次响应是否来自**幂等重放**（同键重复提交 ⇒ 不再调用服务商）。 */
      boolean replayed,
      UUID createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 一次尝试的留痕。 */
  public record PaymentAttemptRow(
      UUID id,
      UUID operationId,
      int attemptNo,
      String attemptKind,
      String attemptKindLabel,
      String idempotencyKey,
      String outcome,
      String outcomeLabel,
      String providerSerialNo,
      String failureReason,
      String requestSummary,
      String responseSummary,
      OffsetDateTime startedAt,
      OffsetDateTime finishedAt) {}

  /** 付款执行请求。 */
  public record PaymentExecutionInput(
      @Schema(nullable = true) UUID providerId,
      @Schema(nullable = true) String idempotencyKey,
      @Schema(nullable = true) String reference,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) BigDecimal amount,
      @Schema(nullable = true) String baseCurrency,
      @Schema(nullable = true) BigDecimal exchangeRate,
      @Schema(nullable = true) BigDecimal baseAmount,
      @Schema(nullable = true) String counterpartyName,
      @Schema(nullable = true) String counterpartyRef,
      @Schema(nullable = true) String counterpartyType,
      @Schema(nullable = true) String note) {}

  /** 收款登记请求。 */
  public record ReceiptRegistrationInput(
      @Schema(nullable = true) UUID providerId,
      @Schema(nullable = true) String idempotencyKey,
      @Schema(nullable = true) String reference,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) BigDecimal amount,
      @Schema(nullable = true) String baseCurrency,
      @Schema(nullable = true) BigDecimal exchangeRate,
      @Schema(nullable = true) BigDecimal baseAmount,
      @Schema(nullable = true) String counterpartyName,
      @Schema(nullable = true) String counterpartyRef,
      @Schema(nullable = true) String counterpartyType,
      @Schema(nullable = true) String providerSerialNo,
      @Schema(nullable = true) String occurredAt,
      @Schema(nullable = true) String note) {}

  /** 余额查询请求。 */
  public record BalanceQueryInput(
      @Schema(nullable = true) UUID providerId,
      @Schema(nullable = true) String idempotencyKey,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) String note) {}

  /** 余额导入（从服务商账单 / 对账单导入）。 */
  public record BalanceImportInput(
      @Schema(nullable = true) UUID providerId,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) BigDecimal balanceAmount,
      @Schema(nullable = true) BigDecimal availableAmount,
      @Schema(nullable = true) String observedAt,
      @Schema(nullable = true) String sourceRef,
      @Schema(nullable = true) String note) {}

  /** 手续费明细导入。 */
  public record FeeImportInput(
      @Schema(nullable = true) UUID providerId,
      @Schema(nullable = true) String feeType,
      @Schema(nullable = true) BigDecimal amount,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) BigDecimal chargedAmount,
      @Schema(nullable = true) String providerSerialNo,
      @Schema(nullable = true) String providerReference,
      @Schema(nullable = true) String occurredAt,
      @Schema(nullable = true) String summary) {}

  /** 汇率查询请求。 */
  public record RateQueryInput(
      @Schema(nullable = true) UUID providerId,
      @Schema(nullable = true) String idempotencyKey,
      @Schema(nullable = true) String baseCurrency,
      @Schema(nullable = true) String quoteCurrency,
      @Schema(nullable = true) String asOf) {}

  // ================================================================ 台账

  /** 余额观测一行。 */
  public record PaymentBalanceRow(
      UUID id,
      UUID providerId,
      String providerCode,
      String providerName,
      String currency,
      @Schema(nullable = true) BigDecimal availableAmount,
      BigDecimal balanceAmount,
      String source,
      OffsetDateTime observedAt,
      String sourceRef,
      OffsetDateTime createdAt) {}

  /** 资金流水一行（**只追加**）。 */
  public record FundFlowRow(
      UUID id,
      String flowNo,
      UUID providerId,
      String providerCode,
      String providerName,
      @Schema(nullable = true) UUID operationId,
      String flowType,
      String flowTypeLabel,
      String direction,
      BigDecimal amount,
      String currency,
      @Schema(nullable = true) BigDecimal baseAmount,
      String baseCurrency,
      @Schema(nullable = true) BigDecimal exchangeRate,
      String providerSerialNo,
      String reference,
      String counterpartyName,
      OffsetDateTime occurredAt,
      String source,
      String remark,
      OffsetDateTime createdAt) {}

  /** 手续费明细一行（**只追加**）。 */
  public record PaymentFeeRow(
      UUID id,
      UUID providerId,
      String providerCode,
      String providerName,
      @Schema(nullable = true) UUID operationId,
      String feeType,
      BigDecimal amount,
      String currency,
      @Schema(nullable = true) BigDecimal chargedAmount,
      String providerSerialNo,
      String providerReference,
      OffsetDateTime occurredAt,
      String source,
      String summary,
      OffsetDateTime createdAt) {}

  /** 汇率观测一行（**只追加**）。 */
  public record PaymentRateRow(
      UUID id,
      UUID providerId,
      String providerCode,
      String providerName,
      String baseCurrency,
      String quoteCurrency,
      BigDecimal rate,
      String source,
      /** 该汇率是否由反方向**推导**而来（true = 推导值，不是直接登记的事实）。 */
      boolean inverted,
      @Schema(nullable = true) LocalDate effectiveDate,
      OffsetDateTime observedAt,
      String sourceRef,
      OffsetDateTime createdAt) {}

  // ================================================================ 上游（finance / catalog）

  /** 上游 12.7 资金账户（**只读**，来自 finance 服务的授权端点）。 */
  public record UpstreamFundAccountRow(
      UUID id,
      String accountNo,
      String accountName,
      String accountType,
      String currency,
      @Schema(nullable = true) BigDecimal currentBalance,
      @Schema(nullable = true) OffsetDateTime currentBalanceAt,
      String status,
      String note) {}

  /** 上游 12.2 收付款（**只读**，来自 finance 服务的授权端点）。 */
  public record UpstreamPaymentOrderRow(
      UUID id,
      String orderNo,
      String counterpartyName,
      String currency,
      @Schema(nullable = true) BigDecimal amount,
      @Schema(nullable = true) BigDecimal baseAmount,
      String baseCurrency,
      @Schema(nullable = true) BigDecimal exchangeRate,
      String status,
      String paymentMethod,
      @Schema(nullable = true) String paymentAccountName) {}

  /** 上游汇率（**只读**，来自 catalog 5.2.2.1 汇率事实）。 */
  public record UpstreamRateRow(
      UUID id,
      String baseCurrency,
      String quoteCurrency,
      BigDecimal rate,
      @Schema(nullable = true) LocalDate effectiveDate,
      @Schema(nullable = true) String sourceCode,
      @Schema(nullable = true) String sourceName,
      @Schema(nullable = true) BigDecimal inverseRate,
      String display) {}

  /** 上游只读端口的一次调用结果（读到了什么 / 读不到的原因）。 */
  public record UpstreamReadResult<T>(boolean readable, @Schema(nullable = true) T data, String reason) {}
}
