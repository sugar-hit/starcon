package com.starcon.finance;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 12.2.2.2 付款执行的传输模型。
 *
 * <p>蓝图 12-finance.md 原文：
 *
 * <blockquote>
 *
 * 职责：执行付款：对接支付渠道/银企直连，发送付款指令，跟踪结果，失败重试与异常处理；处理汇率锁汇。 输入：已批付款单；支付渠道配置（密钥引用）；汇率锁定。 输出：<b>付款结果 + 渠道流水号 + 失败原因</b>。 关系：上游 = 12.2.2.1；下游 = 12.7.1 资金账户、12.5.2 凭证。
 * <b>外部支付超时不等于失败</b>，必须先查证再重发——重复付款是严重资损事故。
 *
 * </blockquote>
 *
 * <p><b>命名。</b>统一用 {@code FinancePaymentExecution*} 前缀 —— 与既有 {@code FinancePaymentOrder*}
 * （12.2.2.1）、{@code FinancePaymentPlan*}（12.1.2.2）、{@code FinancePayable*}（12.1.2.1）零重叠：
 * 契约被 codegen 合并进一个扁平文件，同名后者覆盖前者且不报冲突。
 *
 * <p><b>金额一律 BigDecimal + NUMERIC</b>：本文件里没有一个 {@code double}。金额 {@code numeric(19,4)}、
 * 汇率 {@code numeric(19,8)}、数量 {@code numeric(19,6)}。
 *
 * <p><b>币种分开</b>：任何汇总都按币种分组返回 —— USD 与 EUR 加起来不是任何一个金额。
 */
public final class PaymentExecutionModels {

  private PaymentExecutionModels() {}

  /** 金额小数位（与既有 finance 模块一致）。 */
  public static final int MONEY_SCALE = 4;

  /** 汇率小数位。 */
  public static final int RATE_SCALE = 8;

  // ================================================================ 枚举

  /**
   * 付款执行状态。
   *
   * <p>🔴 <b>UNKNOWN 是本模块的关键状态</b>：蓝图关系行写死「<b>外部支付超时不等于失败</b>」。
   * 渠道超时 / 5xx / 网络不可达一律进 UNKNOWN，**不进 FAILED** ——
   * 只有渠道**明确**拒绝（4xx / 回单说失败）才是 FAILED。
   *
   * <p>UNKNOWN 的执行单**不允许**把付款单打成「失败」：DB 触发器要求
   * 「写 FAILED 必须有一条 status='FAILED' 的执行记录」，而 UNKNOWN 的执行单不是 FAILED。
   */
  public enum PaymentExecutionStatus {
    /** 已建单，还没发出去。 */
    PENDING("待发送"),
    /** 在途（已发出，等渠道回单）。 */
    SENDING("发送中"),
    /** 渠道确认成功（钱付出去了）。 */
    SUCCEEDED("已付款"),
    /** 确定性失败（渠道明确拒绝）。 */
    FAILED("失败"),
    /** 结果未知（超时 / 渠道不可达）：**必须先查证再重发**。 */
    UNKNOWN("结果待查证");

    private final String label;

    PaymentExecutionStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 终态：不再重发。 */
    public boolean terminal() {
      return this == SUCCEEDED || this == FAILED;
    }

    /** 结果未定：重发前必须先查证。 */
    public boolean uncertain() {
      return this == UNKNOWN;
    }
  }

  /** 失败分类（失败原因的可读归类）。 */
  public enum PaymentFailureClass {
    /** 无失败。 */
    NONE("无"),
    /** 渠道明确拒绝。 */
    CHANNEL_REJECTED("渠道拒绝"),
    /** 渠道不可达（5xx / 网络）。 */
    CHANNEL_UNAVAILABLE("渠道不可用"),
    /** 渠道未配置（缺凭据 / 未启用 / 主机不在白名单）。 */
    NOT_CONFIGURED("渠道未配置"),
    /** 超时。 */
    TIMEOUT("渠道超时"),
    /** 渠道回单金额与指令金额对不上。 */
    AMOUNT_MISMATCH("金额不符"),
    /** 本模块内部错误。 */
    INTERNAL("内部错误");

    private final String label;

    PaymentFailureClass(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 尝试类型：发送 / 查证。 */
  public enum PaymentAttemptKind {
    /** 发出付款指令（**只有它计入重发次数**）。 */
    SEND("发送"),
    /** 状态查证（**不计入**重发次数 —— 查证不是重发）。 */
    QUERY("查证");

    private final String label;

    PaymentAttemptKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 一次尝试的结论。 */
  public enum PaymentAttemptOutcome {
    /** 渠道确认成功。 */
    SUCCESS("成功"),
    /** 渠道明确拒绝。 */
    REJECTED("渠道拒绝"),
    /** 查证结果：渠道侧**没有**这笔（确定性「没付出去」）。 */
    NOT_FOUND("渠道无此笔"),
    /** 超时。 */
    TIMEOUT("超时"),
    /** 渠道不可达。 */
    CHANNEL_UNAVAILABLE("渠道不可用"),
    /** 渠道未配置（没发出去）。 */
    NOT_CONFIGURED("渠道未配置"),
    /** 回单金额与指令金额对不上。 */
    AMOUNT_MISMATCH("金额不符");

    private final String label;

    PaymentAttemptOutcome(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 是否为「确定性失败」：可以进入 FAILED，可以重发。 */
    public boolean definitive() {
      return this == REJECTED || this == NOT_FOUND || this == NOT_CONFIGURED || this == AMOUNT_MISMATCH;
    }

    /** 是否为「结果未知」：**不能**进 FAILED，重发前必须先查证。 */
    public boolean uncertain() {
      return this == TIMEOUT || this == CHANNEL_UNAVAILABLE;
    }
  }

  /** 锁汇状态。 */
  public enum RateLockStatus {
    /** 生效中。 */
    LOCKED("生效中"),
    /** 已释放（付款完成）。 */
    RELEASED("已释放"),
    /** 已过期。 */
    EXPIRED("已过期");

    private final String label;

    RateLockStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 能力声明

  /** 能力条目：每一维说清「有没有、依据是什么、缺了会怎样」。 */
  public record FinancePaymentExecutionCapability(
      String module, String name, boolean available, String basis, String ownerModule, String detail) {}

  /** 下游缺口：谁没交付、归谁。 */
  public record FinancePaymentExecutionDownstreamGap(
      String module, String name, String ownerService, boolean available, String group, String detail) {}

  /** 12.2.2.2 的能力声明。 */
  public record FinancePaymentExecutionCapabilities(
      List<FinancePaymentExecutionCapability> capabilities,
      List<FinancePaymentExecutionDownstreamGap> downstreamGaps) {}

  // ================================================================ 渠道

  /** 支付渠道配置（**只存密钥引用**）。 */
  public record FinancePaymentChannelRow(
      UUID id,
      String code,
      String name,
      String paymentMethod,
      String baseUrl,
      /** 密钥管理器引用 ID（**不是**明文）。 */
      String secretRef,
      boolean enabled,
      int timeoutSeconds,
      int maxAttempts,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 渠道入参（登记 / 改配置）。 */
  public record FinancePaymentChannelInput(
      String code,
      String name,
      String paymentMethod,
      String baseUrl,
      String secretRef,
      @Schema(nullable = true) Boolean enabled,
      @Schema(nullable = true) Integer timeoutSeconds,
      @Schema(nullable = true) Integer maxAttempts,
      @Schema(nullable = true) String note) {}

  // ================================================================ 执行单

  /** 付款执行单。 */
  public record FinancePaymentExecutionRow(
      UUID id,
      String executionNo,
      UUID orderId,
      String orderNo,
      @Schema(nullable = true) UUID installmentId,
      @Schema(nullable = true) Integer installmentSeq,
      UUID channelId,
      String channelCode,
      String channelName,
      String paymentMethod,
      String currency,
      BigDecimal amount,
      @Schema(nullable = true) String baseCurrency,
      @Schema(nullable = true) BigDecimal exchangeRate,
      @Schema(nullable = true) BigDecimal baseAmount,
      /** 幂等键：同一笔逻辑付款的每次重发都用它。 */
      String idempotencyKey,
      PaymentExecutionStatus status,
      /** 已发送次数（查证不计入）。 */
      int attemptCount,
      /** 渠道流水号（蓝图输出之一）。 */
      String channelSerialNo,
      /** 失败原因（蓝图输出之一）。 */
      String failureReason,
      PaymentFailureClass failureClass,
      @Schema(nullable = true) OffsetDateTime sentAt,
      @Schema(nullable = true) OffsetDateTime respondedAt,
      @Schema(nullable = true) OffsetDateTime settledAt,
      @Schema(nullable = true) OffsetDateTime lastVerifiedAt,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 一次发送 / 查证尝试。 */
  public record FinancePaymentExecutionAttemptRow(
      UUID id,
      int attemptNo,
      PaymentAttemptKind attemptKind,
      String idempotencyKey,
      PaymentAttemptOutcome outcome,
      String channelSerialNo,
      String failureReason,
      String requestSummary,
      String responseSummary,
      OffsetDateTime startedAt,
      OffsetDateTime finishedAt) {}

  /** 执行事件（append-only 举证凭据）。 */
  public record FinancePaymentExecutionEventRow(
      UUID id,
      String eventType,
      String fromStatus,
      String toStatus,
      String reason,
      @Schema(nullable = true) UUID actorId,
      OffsetDateTime occurredAt) {}

  /** 汇率锁定。 */
  public record FinanceRateLockRow(
      UUID id,
      UUID orderId,
      @Schema(nullable = true) UUID executionId,
      String quoteCurrency,
      String baseCurrency,
      BigDecimal rate,
      BigDecimal baseAmount,
      String sourceCode,
      String sourceName,
      @Schema(nullable = true) LocalDate effectiveDate,
      OffsetDateTime lockedAt,
      OffsetDateTime expiresAt,
      RateLockStatus status,
      @Schema(nullable = true) OffsetDateTime releasedAt) {}

  /** 本次付款分摊到某条应付明细行上的金额。 */
  public record FinancePaymentExecutionAllocationRow(
      UUID id,
      UUID payableLineId,
      UUID orderLineId,
      BigDecimal amount,
      OffsetDateTime createdAt) {}

  /** 执行单详情：执行单 + 尝试记录 + 事件流 + 分摊 + 锁汇。 */
  public record FinancePaymentExecutionDetail(
      FinancePaymentExecutionRow execution,
      List<FinancePaymentExecutionAttemptRow> attempts,
      List<FinancePaymentExecutionEventRow> events,
      List<FinancePaymentExecutionAllocationRow> allocations,
      List<FinanceRateLockRow> rateLocks) {}

  /** 按币种的执行汇总。 */
  public record FinancePaymentExecutionCurrencySummary(
      String currency,
      BigDecimal amount,
      long count,
      long succeededCount,
      long failedCount,
      long unknownCount) {}

  /** 执行单列表页。 */
  public record FinancePaymentExecutionPage(
      List<FinancePaymentExecutionRow> items,
      int page,
      int size,
      long total,
      List<FinancePaymentExecutionCurrencySummary> summaryByCurrency,
      long unknownCount) {}

  /** 筛选项里的一个引用（id + 名称）。 */
  public record FinancePaymentExecutionOptionRef(String id, String name) {}

  /**
   * 筛选项下拉：全部来自**真实数据**（distinct 查询），不是写死的词表。
   *
   * @param channels 支付渠道（来自渠道配置表的真实取值）
   */
  public record FinancePaymentExecutionOptions(
      List<String> currencies,
      List<String> statuses,
      List<String> failureClasses,
      List<String> paymentMethods,
      List<FinancePaymentExecutionOptionRef> channels,
      List<FinancePaymentExecutionOptionRef> orders) {}

  // ================================================================ 其它

  /** 列表查询条件。 */
  public record PaymentExecutionQuery(
      @Schema(nullable = true) PaymentExecutionStatus status,
      @Schema(nullable = true) PaymentFailureClass failureClass,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) String channelCode,
      @Schema(nullable = true) UUID orderId,
      @Schema(nullable = true) String keyword,
      int page,
      int size) {}

  /**
   * **可发起付款执行**的付款单（已批 / 执行中）。
   *
   * @param installmentId 分批 id（这张单有分批安排时逐批出现）
   * @param hasExecution 这一笔（付款单 + 分批）**已经有**执行单了
   */
  public record FinancePaymentExecutionPendingOrder(
      UUID id,
      String orderNo,
      String counterpartyName,
      String currency,
      BigDecimal amount,
      String paymentMethod,
      String status,
      @Schema(nullable = true) UUID installmentId,
      @Schema(nullable = true) Integer installmentSeq,
      @Schema(nullable = true) LocalDate plannedPaidDate,
      boolean hasExecution,
      @Schema(nullable = true) String executionNo,
      @Schema(nullable = true) String executionStatus) {}

  // ================================================================ 写入入参

  /** 发起付款执行的入参。 */
  public record FinancePaymentExecutionInput(
      UUID orderId,
      @Schema(nullable = true) UUID installmentId,
      String channelCode,
      @Schema(nullable = true) String note) {}

  // ================================================================ 结果

  /**
   * 发起执行 / 重发 / 查证的结果。
   *
   * @param resent 本次是否**真的重发了**（查证发现已成功时为 false —— 那是本模块最重要的一条不变量）
   * @param verified 查证结论（本次调用是否做了状态查证）
   * @param retryAllowed 现在**是否允许**再重发（查证仍未知时为 false —— 结果未知时重发就是重复付款）
   */
  public record FinancePaymentExecutionResult(
      FinancePaymentExecutionRow execution,
      boolean resent,
      boolean verified,
      boolean retryAllowed,
      String message) {}
}
