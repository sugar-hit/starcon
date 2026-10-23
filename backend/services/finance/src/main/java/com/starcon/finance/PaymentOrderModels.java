package com.starcon.finance;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 12.2.2.1 付款单与审批的传输模型。
 *
 * <p>蓝图 12-finance.md 原文：
 *
 * <blockquote>
 *
 * 职责：发起付款申请：选择应付明细、付款账户、币种与汇率、付款方式、分批安排，走分级审批。 输入：付款申请；审批规则（金额阈值）；汇率快照。 输出：付款单（草稿/待审/已批/执行中/已付/失败）+ 审批记录。 关系：上游 = 12.1.2.1、12.1.2.2；下游 = 12.2.2.2、17 审批。
 * <b>付款审批是资金安全的第一道控制</b>，大额付款必须双人复核或多级审批。
 *
 * </blockquote>
 *
 * <p><b>命名。</b>统一用 {@code FinancePaymentOrder*} 前缀 —— 与既有 {@code FinancePaymentPlan*}
 * （12.1.2.2）、{@code FinancePayable*}（12.1.2.1）零重叠：契约被 codegen 合并进一个扁平文件，
 * 同名后者覆盖前者且不报冲突。
 *
 * <p><b>金额一律 BigDecimal + NUMERIC</b>：本文件里没有一个 {@code double}。金额 {@code numeric(19,4)}、
 * 汇率 {@code numeric(19,8)}、数量 {@code numeric(19,6)}。
 *
 * <p><b>币种分开</b>：任何汇总都按币种分组返回 —— USD 与 EUR 加起来不是任何一个金额。
 */
public final class PaymentOrderModels {

  private PaymentOrderModels() {}

  /** 金额小数位（与既有 finance 模块一致）。 */
  public static final int MONEY_SCALE = 4;

  /** 汇率小数位。 */
  public static final int RATE_SCALE = 8;

  // ================================================================ 枚举

  /**
   * 付款单状态 —— **蓝图原文的六个词，一个不多一个不少**。
   *
   * <p>草稿 / 待审 / 已批 / 执行中 / 已付 / 失败。
   *
   * <p>⚠️ <b>「已驳回」不在这里</b>：被驳回的付款单<b>退回草稿</b>（可改可再提交），
   * 驳回这件事记在审批记录与事件流里。单据状态只回答「这笔钱现在走到哪一步」。
   *
   * <p>🔴 <b>EXECUTING / PAID / FAILED 本模块写不出来</b>：它们的写入权属 12.2.2.2「付款执行」
   * （未交付），由数据层的 CHECK + 触发器钉死（见 V346）。
   */
  public enum PaymentOrderStatus {
    /** 草稿：还在改，还没有提交审批。 */
    DRAFT("草稿"),
    /** 待审：已提交到 17 审批（identity），等人审。 */
    PENDING_APPROVAL("待审"),
    /** 已批：审批通过，等 12.2.2.2 执行。 */
    APPROVED("已批"),
    /** 执行中：12.2.2.2 正在付款（本模块写不出这一态）。 */
    EXECUTING("执行中"),
    /** 已付：钱已经付出去了（本模块写不出这一态）。 */
    PAID("已付"),
    /** 失败：付款失败（本模块写不出这一态）。 */
    FAILED("失败");

    private final String label;

    PaymentOrderStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 终态：不可再改。 */
    public boolean terminal() {
      return this == PAID || this == FAILED;
    }

    /** 执行态：写入权属 12.2.2.2（未交付）。 */
    public boolean executionOwned() {
      return this == EXECUTING || this == PAID || this == FAILED;
    }
  }

  /**
   * 付款方式。
   *
   * <p>词表取自蓝图 12.2.1.1 点名的跨境收款通道（银行到账 / PayPal / Stripe / PingPong）
   * 与 trade 侧已有的 {@code BANK_TRANSFER}，**不新造**与既有词表冲突的写法。
   */
  public enum PaymentMethod {
    /** 银行电汇。 */
    BANK_TRANSFER("银行电汇"),
    /** PayPal。 */
    PAYPAL("PayPal"),
    /** Stripe。 */
    STRIPE("Stripe"),
    /** PingPong。 */
    PINGPONG("PingPong"),
    /** 其他。 */
    OTHER("其他");

    private final String label;

    PaymentMethod(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 审批状态（**镜像** identity 侧的实例状态）。 */
  public enum ApprovalStatus {
    /** 未提交。 */
    NOT_SUBMITTED("未提交"),
    /** 待审。 */
    PENDING("待审"),
    /** 已批。 */
    APPROVED("已批"),
    /** 已驳回。 */
    REJECTED("已驳回");

    private final String label;

    ApprovalStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 付款对象类型（与 12.1.2.1 同一词表）。 */
  public enum PaymentCounterpartyType {
    SUPPLIER("供应商"),
    CARRIER("承运商"),
    PLATFORM("平台"),
    OTHER("其他");

    private final String label;

    PaymentCounterpartyType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 分批状态。 */
  public enum InstallmentStatus {
    /** 已排期。 */
    PLANNED("已排期"),
    /** 执行中（12.2.2.2 写）。 */
    EXECUTING("执行中"),
    /** 已付（12.2.2.2 写）。 */
    PAID("已付"),
    /** 失败（12.2.2.2 写）。 */
    FAILED("失败");

    private final String label;

    InstallmentStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 能力声明

  /** 能力条目：每一维说清「有没有、依据是什么、缺了会怎样」。 */
  public record FinancePaymentOrderCapability(
      String module, String name, boolean available, String basis, String ownerModule, String detail) {}

  /** 下游缺口：谁没交付、归谁。 */
  public record FinancePaymentOrderDownstreamGap(
      String module, String name, String ownerService, boolean available, String group, String detail) {}

  /** 12.2.2.1 的能力声明。 */
  public record FinancePaymentOrderCapabilities(
      List<FinancePaymentOrderCapability> capabilities,
      List<FinancePaymentOrderDownstreamGap> downstreamGaps) {}

  // ================================================================ 付款单

  /** 付款单头。 */
  public record FinancePaymentOrderRow(
      UUID id,
      String orderNo,
      PaymentCounterpartyType counterpartyType,
      @Schema(nullable = true) UUID counterpartyRef,
      String counterpartyName,
      String currency,
      BigDecimal amount,
      @Schema(nullable = true) String baseCurrency,
      @Schema(nullable = true) BigDecimal exchangeRate,
      @Schema(nullable = true) BigDecimal baseAmount,
      PaymentMethod paymentMethod,
      @Schema(nullable = true) UUID paymentAccountId,
      String paymentAccountName,
      @Schema(nullable = true) String paymentAccountCurrency,
      /** 账户真相的归属模块（恒为 12.7.1.1 —— 它未交付）。 */
      String paymentAccountOwnerModule,
      @Schema(nullable = true) UUID paymentPlanId,
      String paymentPlanNo,
      PaymentOrderStatus status,
      /** 付款执行归属模块（恒为 12.2.2.2 —— 它未交付）。 */
      String executionOwnerModule,
      /** 付款执行是否可用（**恒为 false** —— 12.2.2.2 未交付）。 */
      boolean executionAvailable,
      ApprovalStatus approvalStatus,
      @Schema(nullable = true) UUID approvalFlowId,
      String approvalFlowName,
      @Schema(nullable = true) UUID approvalInstanceId,
      String approvalInstanceNo,
      String approvalCurrentNodeKey,
      String approvalCurrentNodeName,
      /** 起始节点的路由依据：AMOUNT_BRANCH（命中金额阈值）/ FLOW_ORDER（流程顺序默认）。 */
      String approvalRoutedBy,
      @Schema(nullable = true) UUID submittedBy,
      @Schema(nullable = true) OffsetDateTime submittedAt,
      @Schema(nullable = true) UUID approvedBy,
      @Schema(nullable = true) OffsetDateTime approvedAt,
      String failureReason,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 付款明细行（**精确到应付明细行**）。 */
  public record FinancePaymentOrderLineRow(
      UUID id,
      int lineNo,
      UUID payableLineId,
      UUID payableId,
      String payableNo,
      String description,
      String currency,
      BigDecimal amount,
      /** 建单那一刻该应付明细行的金额快照。 */
      BigDecimal payableAmountSnapshot,
      /** 建单那一刻该应付明细行的已付金额快照。 */
      BigDecimal payablePaidSnapshot,
      /** 这条明细是否仍占用该应付明细行（付款单终态时释放）。 */
      boolean active,
      OffsetDateTime createdAt) {}

  /** 分批安排。 */
  public record FinancePaymentOrderInstallmentRow(
      UUID id,
      int seq,
      LocalDate plannedPaidDate,
      String currency,
      BigDecimal amount,
      InstallmentStatus status,
      String executionOwnerModule,
      boolean executionAvailable,
      String failureReason,
      OffsetDateTime createdAt) {}

  /** 付款单事件（append-only 举证凭据）。 */
  public record FinancePaymentOrderEventRow(
      UUID id,
      String eventType,
      String fromStatus,
      String toStatus,
      String reason,
      @Schema(nullable = true) UUID actorId,
      OffsetDateTime occurredAt) {}

  /** 审批记录（**镜像** identity 侧的审批链路）。 */
  public record FinancePaymentApprovalRecordRow(
      UUID id,
      UUID approvalInstanceId,
      String approvalInstanceNo,
      @Schema(nullable = true) UUID actionId,
      String action,
      String nodeKey,
      String nodeName,
      int nodeOrder,
      @Schema(nullable = true) UUID actorId,
      String actorName,
      String comment,
      OffsetDateTime decidedAt,
      long dwellSeconds,
      String sourceSystem,
      OffsetDateTime syncedAt) {}

  /** 付款单详情：单头 + 明细 + 分批 + 事件流 + 审批记录。 */
  public record FinancePaymentOrderDetail(
      FinancePaymentOrderRow order,
      List<FinancePaymentOrderLineRow> lines,
      List<FinancePaymentOrderInstallmentRow> installments,
      List<FinancePaymentOrderEventRow> events,
      List<FinancePaymentApprovalRecordRow> approvalRecords) {}

  /** 按币种的付款单汇总。 */
  public record FinancePaymentOrderCurrencySummary(
      String currency,
      BigDecimal amount,
      long count,
      long pendingApprovalCount,
      long approvedCount,
      long draftCount) {}

  /** 付款单列表页。 */
  public record FinancePaymentOrderPage(
      List<FinancePaymentOrderRow> items,
      int page,
      int size,
      long total,
      List<FinancePaymentOrderCurrencySummary> summaryByCurrency,
      long pendingApprovalCount) {}

  // ================================================================ 写入入参

  /** 一行付款明细的入参。 */
  public record FinancePaymentOrderLineInput(
      UUID payableLineId,
      @Schema(nullable = true) String description,
      String currency,
      BigDecimal amount) {}

  /** 一笔分批的入参。 */
  public record FinancePaymentOrderInstallmentInput(
      @Schema(nullable = true) Integer seq,
      LocalDate plannedPaidDate,
      String currency,
      BigDecimal amount) {}

  /**
   * 付款单入参（建单 / 改单共用）。
   *
   * <p>⚠️ <b>「提交审批」不是这个入参的一部分</b>：提交是**另一个动作**
   * （{@code POST /finance/payment-orders/{id}/submit}），因为它要调 17 审批。
   */
  public record FinancePaymentOrderInput(
      PaymentCounterpartyType counterpartyType,
      @Schema(nullable = true) UUID counterpartyRef,
      @Schema(nullable = true) String counterpartyName,
      String currency,
      BigDecimal amount,
      @Schema(nullable = true) String baseCurrency,
      @Schema(nullable = true) BigDecimal exchangeRate,
      PaymentMethod paymentMethod,
      @Schema(nullable = true) UUID paymentAccountId,
      @Schema(nullable = true) String paymentAccountName,
      @Schema(nullable = true) String paymentAccountCurrency,
      @Schema(nullable = true) UUID paymentPlanId,
      @Schema(nullable = true) String note,
      List<FinancePaymentOrderLineInput> lines,
      @Schema(nullable = true) List<FinancePaymentOrderInstallmentInput> installments) {}

  // ================================================================ 其它

  /** 列表查询条件。 */
  public record PaymentOrderQuery(
      @Schema(nullable = true) PaymentOrderStatus status,
      @Schema(nullable = true) ApprovalStatus approvalStatus,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) PaymentCounterpartyType counterpartyType,
      @Schema(nullable = true) UUID counterpartyRef,
      @Schema(nullable = true) PaymentMethod paymentMethod,
      @Schema(nullable = true) String keyword,
      int page,
      int size) {}

  /** 筛选项里的一个引用（id + 名称）。 */
  public record FinancePaymentOrderOptionRef(String id, String name) {}

  /**
   * 筛选项下拉：全部来自**真实数据**（distinct 查询），不是写死的词表。
   *
   * @param counterparties 付款对象（来自付款单与应付单的真实取值）
   */
  public record FinancePaymentOrderOptions(
      List<String> currencies,
      List<String> statuses,
      List<String> approvalStatuses,
      List<String> paymentMethods,
      List<FinancePaymentOrderOptionRef> counterparties,
      List<FinancePaymentOrderOptionRef> paymentPlans) {}

  /**
   * 可选的应付明细行（**只读** 12.1.2.1 的 finance_payable_line）。
   *
   * @param unpaidAmount 建单那一刻的未付余额（金额 − 已付）
   * @param lockedByOrderId 该明细行正被哪张付款单占用（**非空 = 不能选**）
   */
  public record FinancePaymentPayableLineOption(
      UUID payableLineId,
      UUID payableId,
      String payableNo,
      int lineNo,
      String description,
      String skuCode,
      String currency,
      BigDecimal amount,
      BigDecimal paidAmount,
      BigDecimal unpaidAmount,
      LocalDate dueDate,
      String counterpartyName,
      @Schema(nullable = true) UUID lockedByOrderId,
      @Schema(nullable = true) String lockedByOrderNo) {}

  /**
   * 汇率快照（**读** 5.2.2.1，catalog 服务）。
   *
   * @param baseCurrency 基准币种（本单币种）
   * @param quoteCurrency 报价币种（本位币）
   * @param rate 1 基准币种 = rate 报价币种
   * @param inverted 是否由反向汇率推导而来（catalog 只登记了反方向时）
   */
  public record FinancePaymentExchangeRateView(
      String baseCurrency,
      String quoteCurrency,
      BigDecimal rate,
      LocalDate effectiveDate,
      String sourceCode,
      String sourceName,
      boolean inverted,
      String display) {}

  /** 提交审批的结果。 */
  public record FinancePaymentOrderSubmitResult(
      FinancePaymentOrderRow order,
      @Schema(nullable = true) UUID approvalInstanceId,
      String approvalInstanceNo,
      String approvalCurrentNodeKey,
      String approvalCurrentNodeName,
      String approvalRoutedBy,
      String message) {}

  /** 同步审批结果的结果。 */
  public record FinancePaymentOrderSyncResult(
      FinancePaymentOrderRow order,
      int newRecordCount,
      ApprovalStatus approvalStatus,
      String message) {}
}
