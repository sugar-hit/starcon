package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 9.6.2.1 代发差价与对账的传输模型。
 *
 * <p>蓝图原话：「管理代发业务的成本与结算：代发采购成本（供应商结算价 + 供应商运费）、
 * 我方售价、差价毛利；按周期与供应商对账，识别亏损代发单（售价低于代发成本+平台费）
 * 并预警。输出：代发差价与毛利报表（按店铺/供应商/SKU）；供应商对账单（→ 12 应付）；
 * 亏损单预警。」
 *
 * <p><b>命名纪律（硬要求）</b>：springdoc 用 record 的<b>简单类名</b>做
 * {@code components.schemas} 的键。本文件所有 record 一律以
 * {@code DropshipMargin} / {@code DropshipFeeRate} / {@code DropshipFxRate} /
 * {@code DropshipSettlementCycle} / {@code DropshipLossAlert} /
 * {@code DropshipReconciliation} / {@code DropshipPayableTask} 开头，
 * <b>不复用</b> 9.6.1.1 / 9.6.1.2 / 9.6.1.3 的 {@code Dropship*} 类型名 ——
 * 契约文件里撞名会让两个模块的 schema 互相覆盖。
 *
 * <p><b>五条建模纪律</b>：
 *
 * <ul>
 *   <li><b>「无法判定」不是「不亏」。</b>{@code lossVerdict} 是三值枚举
 *       （{@link DropshipLossVerdict}），{@code spreadAfterFee} 在平台费未登记时是
 *       {@code null} 而不是 0。界面上「差价 0」与「差价算不出来」必须长得不一样。</li>
 *   <li><b>构成项随差价一起返回。</b>{@code sellAmount / purchaseAmount /
 *       supplierShippingAmount / platformFeeAmount} 与差价同在一条 row 上 ——
 *       拿不到构成项的差价无法复核。</li>
 *   <li><b>报表按经营模式分组。</b>{@code businessModel} / {@code costBasis} 恒为
 *       {@code DROPSHIP} / {@code PURCHASE_SPREAD}，前端**不**把它并进自营成本口径。</li>
 *   <li><b>币种跟着金额走。</b>每个金额字段都带它所属的币种列，
 *       跨币种折算状态由 {@code fxStatus} 如实标出（{@code UNSET} = 没有折算数）。</li>
 *   <li><b>任务载荷用具名 record</b>，不用 {@code Map<String,Object>} ——
 *       Map 的值类型会被 springdoc 推成 object，codegen 出空对象 {@code {}}
 *       （9.6.1.3 已踩过并修正）。</li>
 * </ul>
 */
public final class DropshipMarginModels {

  private DropshipMarginModels() {}

  // ------------------------------------------------------------ 枚举

  /**
   * 亏损判定三值结论（DB 生成列 {@code loss_verdict} 的同一词表）。
   *
   * <p>刻意<b>不是</b>布尔：平台费规则来自 5.2.3.1，**未交付**。把它当 0 算，
   * 真实亏损会被放过去；把它当「不亏」处理，「平台费一直没登记」会彻底消失。
   * 所以第三种可能必须自己占一个值。
   */
  public enum DropshipLossVerdict {
    /** 售价低于代发成本（+ 已登记平台费）。已确认亏损。 */
    LOSS_CONFIRMED("已确认亏损"),
    /** 平台费已登记，且售价 ≥ 代发成本 + 平台费。 */
    NOT_LOSS("已含平台费，不亏"),
    /** 售价不低于代发成本，但平台费<b>未登记</b> —— 无法判定，不等于不亏。 */
    UNDETERMINED_NO_FEE("平台费未登记，无法判定");

    private final String label;

    DropshipLossVerdict(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 平台费计价形态（与 DB 的 charge_basis 同一词表）。 */
  public enum DropshipFeeChargeBasis {
    PERCENT_OF_SELL("按售价比例"),
    FIXED_PER_ORDER("按单固定额");

    private final String label;

    DropshipFeeChargeBasis(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 折算状态（与 DB 的 fx_status 同一词表）。UNSET 不是一个可以忽略的默认值。 */
  public enum DropshipFxStatus {
    /** 原币即结算币种，直接用。 */
    SAME_CURRENCY("原币即结算币"),
    /** 按登记汇率折算，汇率值随行留证。 */
    APPLIED("已按登记汇率折算"),
    /** 汇率<b>未登记</b> —— 折算列整组为 NULL，该行不计入合计。 */
    UNSET("汇率未登记，无法折算");

    private final String label;

    DropshipFxStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 报表分组维度（蓝图：按店铺/供应商/SKU）。 */
  public enum DropshipReportGroupBy {
    SHOP("按店铺"),
    SUPPLIER("按供应商"),
    SKU("按 SKU"),
    ORDER("按代发单");

    private final String label;

    DropshipReportGroupBy(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 对账单状态机。 */
  public enum DropshipStatementStatus {
    DRAFT("草稿"),
    FINALIZED("已定稿"),
    DISPUTED("有争议"),
    CLOSED("已关闭");

    private final String label;

    DropshipStatementStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 应付待办状态机。SENT（已发出）与 ACKNOWLEDGED（下游已受理）是两件事。 */
  public enum DropshipPayableTaskStatus {
    OPEN("待领取"),
    CLAIMED("已领取"),
    SENT("已交给下游"),
    ACKNOWLEDGED("下游已受理"),
    FAILED("失败");

    private final String label;

    DropshipPayableTaskStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ------------------------------------------------------------ 能力声明

  public record DropshipMarginCapabilities(
      List<String> lossVerdicts,
      List<String> feeChargeBases,
      List<String> fxStatuses,
      List<String> reportGroupBys,
      List<String> statementStatuses,
      List<String> payableTaskStatuses,
      List<DropshipMarginCapabilityRule> rules,
      List<DropshipMarginCapabilityDimension> dimensions,
      boolean platformFeeRegistered,
      String summary,
      String basisNote) {}

  /** 一条判定规则的自述（前端把「这个结论是怎么来的」原样显示）。 */
  public record DropshipMarginCapabilityRule(
      String code,
      String label,
      String ruleSummary,
      String formula,
      String suggestedActions) {}

  /** 上游 / 下游维度可用性。available=false 时 ownerModule 必填。 */
  public record DropshipMarginCapabilityDimension(
      String code,
      String name,
      boolean available,
      String source,
      @Schema(nullable = true) String ownerModule,
      @Schema(nullable = true) String note) {}

  // ------------------------------------------------------------ 平台费率

  public record DropshipFeeRateRow(
      UUID id,
      String rateCode,
      String rateName,
      String platformCode,
      String feeType,
      String chargeBasis,
      @Schema(nullable = true) BigDecimal ratePercent,
      @Schema(nullable = true) BigDecimal fixedAmount,
      @Schema(nullable = true) String fixedCurrencyCode,
      String settlementCurrencyCode,
      OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      String source,
      OffsetDateTime observedAt,
      String note,
      int version) {}

  public record DropshipFeeRateInput(
      @NotBlank @Size(max = 32) String rateCode,
      @NotBlank @Size(max = 200) String rateName,
      @NotBlank @Size(max = 32) String platformCode,
      @NotBlank @Size(max = 24) String feeType,
      @NotNull DropshipFeeChargeBasis chargeBasis,
      @DecimalMin("0.0") @DecimalMax("0.999999") BigDecimal ratePercent,
      @DecimalMin("0.0") BigDecimal fixedAmount,
      @Pattern(regexp = "^[A-Z]{3}$") String fixedCurrencyCode,
      @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String settlementCurrencyCode,
      @NotNull OffsetDateTime effectiveFrom,
      OffsetDateTime effectiveTo,
      @NotNull OffsetDateTime observedAt,
      @Size(max = 400) String note) {}

  public record DropshipFeeRatePage(List<DropshipFeeRateRow> items, long total, int page, int size) {}

  // ------------------------------------------------------------ 汇率

  public record DropshipFxRateRow(
      UUID id,
      String baseCurrency,
      String quoteCurrency,
      BigDecimal rateValue,
      OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      String source,
      OffsetDateTime observedAt,
      String note,
      int version) {}

  public record DropshipFxRateInput(
      @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String baseCurrency,
      @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String quoteCurrency,
      @NotNull @DecimalMin(value = "0.00000001") BigDecimal rateValue,
      @NotNull OffsetDateTime effectiveFrom,
      OffsetDateTime effectiveTo,
      @NotBlank @Size(max = 24) String source,
      @NotNull OffsetDateTime observedAt,
      @Size(max = 400) String note) {}

  public record DropshipFxRatePage(List<DropshipFxRateRow> items, long total, int page, int size) {}

  // ------------------------------------------------------------ 结算周期

  public record DropshipSettlementCycleRow(
      UUID id,
      String cycleCode,
      String supplierCode,
      String currencyCode,
      String cycleKind,
      @Schema(nullable = true) Integer settleDay,
      OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      String status,
      String note,
      int version) {}

  public record DropshipSettlementCycleInput(
      @NotBlank @Size(max = 32) String cycleCode,
      @NotBlank @Size(max = 64) String supplierCode,
      @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currencyCode,
      @NotBlank @Size(max = 12) String cycleKind,
      @Min(1) @Max(31) Integer settleDay,
      @NotNull OffsetDateTime effectiveFrom,
      OffsetDateTime effectiveTo,
      @Size(max = 400) String note) {}

  public record DropshipSettlementCyclePage(
      List<DropshipSettlementCycleRow> items, long total, int page, int size) {}

  // ------------------------------------------------------------ 差价毛利事实

  public record DropshipMarginFactRow(
      UUID id,
      UUID orderId,
      String orderNo,
      String salesOrderNo,
      String shopCode,
      String supplierCode,
      String currencyCode,
      String businessModel,
      String costBasis,
      BigDecimal sellAmount,
      BigDecimal purchaseAmount,
      BigDecimal supplierShippingAmount,
      BigDecimal dropshipCost,
      BigDecimal spreadBeforeFee,
      @Schema(nullable = true) BigDecimal platformFeeAmount,
      @Schema(nullable = true) BigDecimal spreadAfterFee,
      @Schema(nullable = true) BigDecimal marginRate,
      String feeRegistrationStatus,
      String lossVerdict,
      String lossVerdictLabel,
      String lossEvidence,
      OffsetDateTime computedAt) {}

  public record DropshipMarginFactQuery(
      String supplierCode,
      String shopCode,
      String orderNo,
      String lossVerdict,
      String keyword,
      int page,
      int size) {}

  public record DropshipMarginFactPage(
      List<DropshipMarginFactRow> items, long total, int page, int size) {}

  /**
   * 跑一轮差价计算的结果。三个计数分开报：把「算了几张」与「其中几张有问题」
   * 混成一个数字，正是「看不出来到底有没有漏算」的来源。
   */
  public record DropshipMarginEvaluateResult(
      long scannedOrders, int computed, int skipped, int lossConfirmed, int undetermined) {}

  // ------------------------------------------------------------ 差价毛利报表

  /**
   * 报表的一行分组。
   *
   * <p>⚠️ {@code businessModel} / {@code costBasis} 随每一行返回，不是只在表头写一次：
   * 下游 13 利润报表按经营模式分组时，光看金额会把代发店铺并进自营口径。
   */
  public record DropshipMarginReportGroup(
      String groupKey,
      String groupLabel,
      String groupBy,
      String businessModel,
      String costBasis,
      String currencyCode,
      long orderCount,
      BigDecimal sellAmount,
      BigDecimal purchaseAmount,
      BigDecimal supplierShippingAmount,
      BigDecimal platformFeeAmount,
      long feeUnregisteredOrderCount,
      BigDecimal dropshipCost,
      BigDecimal spreadBeforeFee,
      @Schema(nullable = true) BigDecimal spreadAfterFee,
      BigDecimal spreadBeforeFeeSum,
      long undeterminedOrderCount,
      long lossConfirmedOrderCount,
      long notLossOrderCount,
      @Schema(nullable = true) BigDecimal marginRate,
      String note) {}

  public record DropshipMarginReport(
      String groupBy,
      String businessModel,
      String costBasis,
      OffsetDateTime periodStart,
      OffsetDateTime periodEnd,
      String currencyCode,
      long totalOrderCount,
      int groupCount,
      List<DropshipMarginReportGroup> groups,
      String basisNote,
      String feeNote,
      String currencyNote) {}

  // ------------------------------------------------------------ 亏损预警

  public record DropshipLossAlertRow(
      UUID id,
      String alertNo,
      UUID orderId,
      String orderNo,
      String shopCode,
      String supplierCode,
      String currencyCode,
      String alertKind,
      String alertKindLabel,
      String severity,
      String ruleCode,
      String ruleEvidence,
      String feeRegistrationStatus,
      @Schema(nullable = true) BigDecimal platformFeeAmount,
      BigDecimal sellAmount,
      BigDecimal dropshipCost,
      BigDecimal spreadBeforeFee,
      @Schema(nullable = true) BigDecimal spreadAfterFee,
      OffsetDateTime periodStart,
      OffsetDateTime periodEnd,
      String status,
      String statusLabel,
      OffsetDateTime detectedAt,
      @Schema(nullable = true) OffsetDateTime acknowledgedAt,
      String resolutionNote) {}

  public record DropshipLossAlertQuery(
      String supplierCode, String alertKind, String status, String severity, int page, int size) {}

  public record DropshipLossAlertPage(
      List<DropshipLossAlertRow> items,
      long total,
      long lossConfirmedTotal,
      long undeterminedTotal,
      int page,
      int size) {}

  /** 处置（acknowledge / resolve）：必须写明依据。 */
  public record DropshipLossAlertAckInput(
      @NotBlank @Size(max = 400) String resolutionNote, boolean resolved) {}

  /** 判为误报：必须写明理由。 */
  public record DropshipLossAlertDismissInput(@NotBlank @Size(max = 400) String reason) {}

  // ------------------------------------------------------------ 对账单

  public record DropshipReconciliationRow(
      UUID id,
      String statementNo,
      String supplierCode,
      OffsetDateTime periodStart,
      OffsetDateTime periodEnd,
      String cycleKind,
      String currencyCode,
      String businessModel,
      String costBasis,
      String status,
      int orderCount,
      int lossOrderCount,
      BigDecimal totalSellAmount,
      BigDecimal totalPurchaseAmount,
      BigDecimal totalSupplierShipping,
      BigDecimal totalPlatformFee,
      BigDecimal totalDropshipCost,
      BigDecimal totalSpread,
      BigDecimal totalPayable,
      int undeterminedOrderCount,
      BigDecimal undeterminedPayable,
      int feeUnregisteredOrderCount,
      String fxStatus,
      @Schema(nullable = true) BigDecimal fxRateValue,
      String ruleEvidence,
      String basisNote,
      String disputeNote,
      @Schema(nullable = true) OffsetDateTime finalizedAt,
      @Schema(nullable = true) OffsetDateTime closedAt,
      OffsetDateTime createdAt,
      int version) {}

  /**
   * 建对账单。
   *
   * <p>⚠️ {@code periodStart} / {@code periodEnd} 由<b>调用方显式给出</b>：
   * 「上个月」这种键在月初与月末会圈出不同范围，既没法复算也没法断言。
   */
  public record DropshipReconciliationInput(
      @NotBlank @Size(max = 64) String supplierCode,
      @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currencyCode,
      @NotNull OffsetDateTime periodStart,
      @NotNull OffsetDateTime periodEnd,
      @Size(max = 32) String cycleKind,
      @Size(max = 400) String note) {}

  public record DropshipReconciliationStatusInput(
      @NotBlank @Size(max = 16) String status,
      @Size(max = 400) String disputeNote) {}

  public record DropshipReconciliationLineRow(
      UUID id,
      String orderNo,
      String salesOrderNo,
      String shopCode,
      String originalCurrencyCode,
      String statementCurrencyCode,
      BigDecimal sellAmount,
      BigDecimal purchaseAmount,
      BigDecimal supplierShippingAmount,
      String feeRegistrationStatus,
      @Schema(nullable = true) BigDecimal platformFeeAmount,
      String lossVerdict,
      String fxStatus,
      @Schema(nullable = true) BigDecimal fxRateValue,
      @Schema(nullable = true) BigDecimal sellAmountInStatement,
      @Schema(nullable = true) BigDecimal purchaseAmountInStatement,
      @Schema(nullable = true) BigDecimal supplierShippingAmountInStatement,
      @Schema(nullable = true) BigDecimal platformFeeInStatement,
      @Schema(nullable = true) BigDecimal dropshipCostInStatement,
      @Schema(nullable = true) BigDecimal spreadInStatement,
      @Schema(nullable = true) BigDecimal payableInStatement,
      boolean inTotals,
      String ruleEvidence) {}

  public record DropshipReconciliationDetail(
      DropshipReconciliationRow statement,
      List<DropshipReconciliationLineRow> lines,
      int lineCount,
      String fxNote,
      String taskNote) {}

  public record DropshipReconciliationQuery(
      String supplierCode, String status, String currencyCode, int page, int size) {}

  public record DropshipReconciliationPage(
      List<DropshipReconciliationRow> items, long total, int page, int size) {}

  // ------------------------------------------------------------ 应付待办

  /** 待办载荷：**具名 record**，不用 Map（见类注释第五条）。 */
  public record DropshipPayablePayload(
      String statementNo,
      String supplierCode,
      String businessModel,
      String costBasis,
      String currencyCode,
      OffsetDateTime periodStart,
      OffsetDateTime periodEnd,
      BigDecimal payableAmount,
      BigDecimal totalSellAmount,
      BigDecimal totalSpread,
      int orderCount,
      int undeterminedOrderCount,
      String basisNote) {}

  public record DropshipPayableTaskRow(
      UUID id,
      String taskNo,
      String taskKind,
      String targetSystem,
      String taskStatus,
      String taskStatusLabel,
      UUID statementId,
      String statementNo,
      String supplierCode,
      BigDecimal payableAmount,
      String payableCurrencyCode,
      DropshipPayablePayload payload,
      String idempotencyKey,
      int attemptCount,
      @Schema(nullable = true) OffsetDateTime lastAttemptAt,
      String lastError,
      @Schema(nullable = true) OffsetDateTime claimedAt,
      @Schema(nullable = true) OffsetDateTime sentAt,
      @Schema(nullable = true) OffsetDateTime acknowledgedAt,
      OffsetDateTime createdAt) {}

  public record DropshipPayableTaskQuery(String taskStatus, String supplierCode, int page, int size) {}

  public record DropshipPayableTaskPage(
      List<DropshipPayableTaskRow> items, long total, int page, int size) {}

  /** 下游回执：只有走到这里，对账单才允许显示「下游已受理」。 */
  public record DropshipPayableTaskResultInput(
      @NotBlank @Size(max = 16) String result,
      @Size(max = 400) String note) {}

  // ------------------------------------------------------------ 事件流

  public record DropshipMarginEventRow(
      UUID id,
      String eventKind,
      @Schema(nullable = true) UUID factId,
      @Schema(nullable = true) UUID statementId,
      @Schema(nullable = true) UUID alertId,
      @Schema(nullable = true) UUID taskId,
      String supplierCode,
      String detail,
      @Schema(nullable = true) OffsetDateTime occurredAt) {}

  public record DropshipMarginEventPage(
      List<DropshipMarginEventRow> items, long total, int page, int size) {}
}
