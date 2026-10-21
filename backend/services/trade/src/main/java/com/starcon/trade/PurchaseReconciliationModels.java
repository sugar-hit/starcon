package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 6.5.1.1 采购对账的传输模型。
 *
 * <p>蓝图 06-purchase-and-supply.md §6.5.1.1 原文：
 *
 * <blockquote>
 * 职责：采购侧发起与供应商的对账：按期间汇总应收数量、单价、金额、代扣代缴费用、索赔，
 * 生成对账单发给供应商确认，差异挂起。
 * 输入：期间内的收货记录、质检扣量、索赔；供应商确认回执。
 * 输出：对账单（明细 + 汇总）；差异清单；确认状态。
 * 关系：上游 = 8 收货、6.4.2 质检；下游 = 12 财务（应付核对与付款）。
 * <b>对账由采购发起、12 财务执行付款</b>，两侧数据以对账单为交接点，避免采购擅自确认应付。
 * </blockquote>
 *
 * <p><b>本文件最重要的四件事</b>：
 *
 * <ol>
 *   <li><b>没有「应付已确认 / 已付款」这种东西</b>。{@link ReconciliationRow} 上只有
 *       {@code suggestedPayableAmount}（采购侧**建议**数，名字里的 Suggested 是刻意的），
 *       状态机里也没有「已付款」。采购侧确认应付 = 替财务签了字。</li>
 *   <li><b>差异是「行」，不是一个差额总数</b>。{@link ReconciliationDiscrepancyRow} 记清
 *       差在哪个字段（{@code discrepancyType} + {@code fieldName}）、我方值、供应商值、
 *       差额；差异可以 {@code HELD}（挂起）后再处理。</li>
 *   <li><b>质检扣量不是 0，是「未接线」</b>。{@link ReconciliationLineRow#qualityDeductionStatus}
 *       为 {@code UNAVAILABLE} 时，{@code qualityDeductionQuantity} 恒为 {@code null}
 *       —— 写 0 会被读成「这批没有扣量」。</li>
 *   <li><b>币种 + 汇率快照 + 单位换算逐行带</b>：{@code currency} / {@code fxRate} /
 *       {@code fxRateSource} / {@code quantity} / {@code unitCode} / {@code unitConversion}。
 *       跨币种绝不按 1:1 折算。</li>
 * </ol>
 *
 * <p><b>命名。</b>统一用 {@code Reconciliation*} / {@code PurchaseReconciliation*} 前缀，与
 * {@code SupplierClaim*} / {@code PurchaseArrival*} / {@code PurchasePlan*} 零重叠 ——
 * 契约被 codegen 合并进一个扁平文件、同名后者覆盖前者且不报冲突。
 */
public final class PurchaseReconciliationModels {

  private PurchaseReconciliationModels() {}

  // ================================================================ 枚举

  /**
   * 对账单状态机。
   *
   * <p>⚠️ <b>刻意没有「已付款」「应付已确认」</b>：付款是 12 财务的事，
   * 采购侧替财务确认应付正是蓝图加粗句要防的事。
   *
   * <p>⚠️ {@link #CLOSED} 是终态：关闭的对账单不再重开（重开只对未关闭的单开放）。
   */
  public enum ReconciliationStatus {
    DRAFT("草稿"),
    SENT("已发送待确认"),
    DISPUTED("有差异待处理"),
    CONFIRMED("已确认"),
    CLOSED("已关闭");

    private final String label;

    ReconciliationStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 终态：关闭后不再重开、不再改。 */
    public boolean terminal() {
      return this == CLOSED;
    }
  }

  /** 对账期间类型（月度 / 季度）。期间边界由服务端裁决，DB 用函数兜底。 */
  public enum ReconciliationPeriodType {
    MONTHLY("月度"),
    QUARTERLY("季度");

    private final String label;

    ReconciliationPeriodType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 对账明细行类型。 */
  public enum ReconciliationLineType {
    /** 已到货应收（来自 6.3.2.2 到货流水）。 */
    RECEIPT("已到货应收"),
    /** 索赔冲减（来自 11.2.3.3 供应商索赔）。 */
    CLAIM("索赔冲减"),
    /** 代扣代缴费用（采购登记）。 */
    DEDUCTION("代扣代缴费用");

    private final String label;

    ReconciliationLineType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 明细行的来源事实类型。 */
  public enum ReconciliationSourceType {
    /** 6.3.2.2 到货流水（trade 本库只读）。 */
    PURCHASE_ARRIVAL("到货流水"),
    /** 11.2.3.3 供应商索赔（trade 本库只读）。 */
    SUPPLIER_CLAIM("供应商索赔"),
    /** 采购手工登记（代扣代缴费用）。 */
    MANUAL("采购登记");

    private final String label;

    ReconciliationSourceType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 代扣代缴费用类型。 */
  public enum DeductionType {
    TAX("代扣代缴税费"),
    WITHHOLDING("代扣"),
    FREIGHT("代付运费"),
    REBATE("折让"),
    OTHER("其它");

    private final String label;

    DeductionType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 差异类型。
   *
   * <p>⚠️ {@link #MISSING_LINE}（我方有、供应商没列）与 {@link #EXTRA_LINE}
   * （供应商有、我方没列）是**两个方向**的差异 —— 合成一个「数量对不上」，
   * 就答不出「到底谁多算了」。
   */
  public enum DiscrepancyType {
    QUANTITY("数量不符"),
    UNIT_PRICE("单价不符"),
    AMOUNT("金额不符"),
    MISSING_LINE("我方有供应商未列"),
    EXTRA_LINE("供应商有我方未列"),
    CURRENCY("币种不符"),
    OTHER("其它差异");

    private final String label;

    DiscrepancyType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 差异处理状态（差异**可以挂起**，不是只能解决或不管）。 */
  public enum DiscrepancyStatus {
    OPEN("待处理"),
    HELD("已挂起"),
    RESOLVED("已解决");

    private final String label;

    DiscrepancyStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 质检扣量来源状态。
   *
   * <p>⚠️ {@link #UNAVAILABLE} 是本仓当前**真实**的状态：6.4.2.1 质检单同批并行开发、
   * 此刻在集成头**不存在** ⇒ 本模块显式报「未接线」，**不按 0 计入**。
   */
  public enum QualityDeductionStatus {
    NOT_APPLICABLE("不适用"),
    UNAVAILABLE("上游未接线"),
    APPLIED("已取到扣量");

    private final String label;

    QualityDeductionStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 供应商答复口径。 */
  public enum SupplierConfirmationResult {
    CONFIRMED("供应商确认无误"),
    DIFFERENCES("供应商有差异");

    private final String label;

    SupplierConfirmationResult(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 入参

  /**
   * 建对账单。
   *
   * <p>⚠️ <b>期间只给类型 + 起始日</b>：结束日由服务端裁决（月度 = 某月 1 号到月末，
   * 季度 = 季首月 1 号到季末月末），调用方给了也对不上 ⇒ 400，不悄悄改写。
   *
   * <p>⚠️ <b>没有「应付金额」字段</b>：汇总是 DB 从明细行算出来的。
   */
  public record PurchaseReconciliationCreate(
      UUID supplierRefId,
      String periodType,
      LocalDate periodStart,
      @Schema(nullable = true) LocalDate periodEnd,
      String statementCurrency,
      @Schema(nullable = true) List<ReconciliationFxRateInput> fxRates,
      @Schema(nullable = true) String note) {}

  /**
   * 一条汇率快照（原币 → 对账单币种）。
   *
   * <p>⚠️ <b>跨币种不给汇率就 400</b>：两种货币按 1:1 加进同一张对账单，
   * 是本模块要挡住的最贵的一种错账。汇率来源必填（快照要能追到出处）。
   */
  public record ReconciliationFxRateInput(
      String currency, BigDecimal rate, String source) {}

  /**
   * 登记一笔代扣代缴费用。
   *
   * <p>⚠️ <b>必须带币种与汇率快照来源</b>：代扣代缴的费用与货款可能不同币种，
   * 不记汇率就会被按 1:1 加进对账单。
   */
  public record PurchaseReconciliationDeductionCreate(
      String deductionType,
      BigDecimal amount,
      String currency,
      @Schema(nullable = true) BigDecimal fxRate,
      @Schema(nullable = true) String fxRateSource,
      @Schema(nullable = true) String note) {}

  /**
   * 录入供应商确认回执。
   *
   * <p>⚠️ {@link #supplierLines} 是**逐行**供应商值：差异要成行可查，
   * 只给一个总额就答不出「差在哪一行」。
   */
  public record PurchaseReconciliationConfirmationCreate(
      OffsetDateTime supplierRepliedAt,
      @Schema(nullable = true) String supplierRepliedBy,
      @Schema(nullable = true) String supplierReference,
      String supplierResult,
      @Schema(nullable = true) BigDecimal supplierConfirmedAmount,
      @Schema(nullable = true) String supplierConfirmedCurrency,
      @Schema(nullable = true) List<SupplierLineValue> supplierLines,
      @Schema(nullable = true) String note) {}

  /**
   * 供应商对某一行给出的值（数量 / 单价 / 金额，缺哪个就不比哪个）。
   *
   * <p>⚠️ {@code lineId} 为空时 {@code supplierLineRef} 必填：那表示
   * 「供应商列了这么一行，我们这边没有」⇒ 生成 EXTRA_LINE 差异
   * （**两个方向**的差异不能合成一个）。
   */
  public record SupplierLineValue(
      @Schema(nullable = true) UUID lineId,
      @Schema(nullable = true) String supplierLineRef,
      @Schema(nullable = true) BigDecimal quantity,
      @Schema(nullable = true) BigDecimal unitPrice,
      @Schema(nullable = true) BigDecimal amount) {}

  /** 追加一条差异（采购主动记的差异，不等供应商回执）。 */
  public record PurchaseReconciliationDiscrepancyCreate(
      @Schema(nullable = true) UUID lineId,
      String discrepancyType,
      @Schema(nullable = true) String fieldName,
      @Schema(nullable = true) BigDecimal ourValue,
      @Schema(nullable = true) BigDecimal supplierValue,
      String currency,
      @Schema(nullable = true) String note) {}

  /**
   * 处理一条差异。
   *
   * <p>⚠️ {@code status} 只能是 {@code HELD}（挂起）或 {@code RESOLVED}（解决）：
   * 「挂起」是蓝图输出句「差异挂起」的落点，必须能真的挂起而不必立刻解决。
   */
  public record PurchaseReconciliationDiscrepancyResolve(
      String status, String resolution, String resolutionNote) {}

  /** 重开对账单（追加差异）。 */
  public record PurchaseReconciliationReopen(String reason) {}

  /** 列表查询。真分页 + 多维筛选 + 排序白名单。 */
  public record PurchaseReconciliationListQuery(
      @Schema(nullable = true) Integer page,
      @Schema(nullable = true) Integer size,
      @Schema(nullable = true) String status,
      @Schema(nullable = true) UUID supplierRefId,
      @Schema(nullable = true) String periodType,
      @Schema(nullable = true) LocalDate periodStart,
      @Schema(nullable = true) LocalDate periodEnd,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) String keyword,
      @Schema(nullable = true) String sort) {}

  // ================================================================ 出参

  /** 对账单行（列表用）。 */
  public record ReconciliationRow(
      UUID id,
      String reconNo,
      UUID supplierRefId,
      String supplierCode,
      String supplierName,
      String periodType,
      String periodTypeLabel,
      LocalDate periodStart,
      LocalDate periodEnd,
      String statementCurrency,
      String status,
      String statusLabel,
      BigDecimal receivedAmount,
      BigDecimal deductionAmount,
      BigDecimal claimAmount,
      BigDecimal suggestedPayableAmount,
      BigDecimal totalQuantity,
      long lineCount,
      long discrepancyCount,
      long openDiscrepancyCount,
      boolean qualityBasisAvailable,
      String financeHandoffStatus,
      OffsetDateTime supplierConfirmedAt,
      String supplierReference,
      OffsetDateTime createdAt,
      long version) {}

  /** 对账单列表页。 */
  public record ReconciliationPage(
      List<ReconciliationRow> rows,
      int page,
      int size,
      long total,
      ReconciliationSummary summary,
      OffsetDateTime evaluatedAt,
      String paginationNote,
      String basisNote,
      String currencyNote,
      String sortNote) {}

  /** 对账单页汇总（跨全表算，不随分页变小）。 */
  public record ReconciliationSummary(
      long total,
      long draft,
      long sent,
      long disputed,
      long confirmed,
      long closed,
      BigDecimal totalReceivedAmount,
      BigDecimal totalDeductionAmount,
      BigDecimal totalClaimAmount,
      BigDecimal totalSuggestedPayableAmount,
      long totalOpenDiscrepancies) {}

  /** 对账明细行。 */
  public record ReconciliationLineRow(
      UUID id,
      int lineNo,
      String lineType,
      String lineTypeLabel,
      String sourceType,
      String sourceTypeLabel,
      @Schema(nullable = true) UUID sourceRefId,
      String sourceRefCode,
      @Schema(nullable = true) UUID sourceLineRefId,
      String sourceLineRefCode,
      OffsetDateTime occurredAt,
      @Schema(nullable = true) BigDecimal quantity,
      @Schema(nullable = true) String unitCode,
      @Schema(nullable = true) BigDecimal unitConversion,
      @Schema(nullable = true) BigDecimal baseQuantity,
      @Schema(nullable = true) BigDecimal unitPrice,
      String currency,
      BigDecimal fxRate,
      String fxRateSource,
      OffsetDateTime fxRateAt,
      BigDecimal amount,
      BigDecimal statementAmount,
      @Schema(nullable = true) String deductionType,
      String deductionTypeLabel,
      @Schema(nullable = true) UUID claimId,
      String qualityDeductionStatus,
      String qualityDeductionStatusLabel,
      @Schema(nullable = true) BigDecimal qualityDeductionQuantity,
      String qualityDeductionNote,
      String note) {}

  /** 明细行分页。 */
  public record ReconciliationLinePage(
      List<ReconciliationLineRow> rows,
      int page,
      int size,
      long total,
      ReconciliationLineSummary summary,
      OffsetDateTime evaluatedAt,
      String note) {}

  /** 明细页汇总（按行类型分桶；**跨币种不相加**）。 */
  public record ReconciliationLineSummary(
      long receiptLines,
      long claimLines,
      long deductionLines,
      BigDecimal receiptAmount,
      BigDecimal claimAmount,
      BigDecimal deductionAmount,
      BigDecimal totalQuantity,
      List<CurrencyBucket> byCurrency) {}

  /** 按币种分桶的汇总（**跨币种绝不相加**）。 */
  public record CurrencyBucket(String currency, long lineCount, BigDecimal amount) {}

  /** 差异行。 */
  public record ReconciliationDiscrepancyRow(
      UUID id,
      @Schema(nullable = true) UUID lineId,
      @Schema(nullable = true) Integer lineNo,
      String discrepancyType,
      String discrepancyTypeLabel,
      String fieldName,
      @Schema(nullable = true) BigDecimal ourValue,
      @Schema(nullable = true) BigDecimal supplierValue,
      BigDecimal difference,
      String currency,
      String status,
      String statusLabel,
      String resolution,
      String resolutionNote,
      @Schema(nullable = true) String note,
      @Schema(nullable = true) UUID resolvedBy,
      @Schema(nullable = true) OffsetDateTime resolvedAt,
      OffsetDateTime createdAt) {}

  /** 差异清单页。 */
  public record ReconciliationDiscrepancyPage(
      List<ReconciliationDiscrepancyRow> rows,
      int page,
      int size,
      long total,
      long openCount,
      long heldCount,
      long resolvedCount,
      OffsetDateTime evaluatedAt,
      String note) {}

  /** 供应商确认回执行。 */
  public record ReconciliationConfirmationRow(
      UUID id,
      String confirmationNo,
      OffsetDateTime supplierRepliedAt,
      String supplierRepliedBy,
      String supplierReference,
      String supplierResult,
      String supplierResultLabel,
      @Schema(nullable = true) BigDecimal supplierConfirmedAmount,
      @Schema(nullable = true) String supplierConfirmedCurrency,
      int discrepancyCount,
      String note,
      OffsetDateTime createdAt) {}

  /** 留痕行。 */
  public record ReconciliationEventRow(
      UUID id,
      String eventType,
      String eventTypeLabel,
      String fromStatus,
      String toStatus,
      @Schema(nullable = true) BigDecimal amount,
      String reasonNote,
      OffsetDateTime occurredAt) {}

  /** 对账单全景（头 + 明细 + 差异 + 回执 + 留痕 + 当前可执行动作）。 */
  public record ReconciliationDetail(
      ReconciliationRow head,
      List<ReconciliationLineRow> lines,
      List<ReconciliationDiscrepancyRow> discrepancies,
      List<ReconciliationConfirmationRow> confirmations,
      List<ReconciliationEventRow> events,
      List<ReconciliationAction> actions,
      ReconciliationBasis basis,
      OffsetDateTime evaluatedAt,
      String note) {}

  /**
   * 对账基数说明。
   *
   * <p>⚠️ 这不是装饰性文案：{@link #sources} 逐条报出「基数取自哪个已交付事实、
   * 那个事实现在接通了没有」，{@link #unavailableSources} 报出**未接线**的事实及其影响。
   */
  public record ReconciliationBasis(
      List<ReconciliationBasisSource> sources,
      List<ReconciliationUnavailableSource> unavailableSources,
      String receiptBasisNote,
      String qualityBasisNote,
      String financeHandoffNote,
      String periodNote,
      String fxNote,
      String unitNote) {}

  /** 一条基数来源。 */
  public record ReconciliationBasisSource(
      String key,
      String label,
      boolean available,
      @Schema(nullable = true) String ownerModule,
      String detail,
      @Schema(nullable = true) String href) {}

  /** 一条未接线的事实及其影响。 */
  public record ReconciliationUnavailableSource(
      String key,
      String label,
      boolean available,
      @Schema(nullable = true) String ownerModule,
      String reason,
      String impact) {}

  /** 可执行动作（每个动作都带 blockedReason，界面要能说清为什么不能点）。 */
  public record ReconciliationAction(
      String code,
      String label,
      boolean allowed,
      @Schema(nullable = true) String method,
      @Schema(nullable = true) String path,
      boolean required,
      @Schema(nullable = true) String precondition,
      @Schema(nullable = true) String blockedReason) {}

  // ================================================================ 能力声明

  /** 能力与边界声明（一个块 = 职责句的一个动词）。 */
  public record ReconciliationCapabilitySection(
      String section,
      String label,
      boolean available,
      @Schema(nullable = true) String ownerModule,
      String reason,
      @Schema(nullable = true) String landsIn) {}

  /** 上下游模块的交付状态。 */
  public record ReconciliationRelatedModule(
      String id,
      String label,
      boolean delivered,
      @Schema(nullable = true) String ownerModule,
      String reason,
      @Schema(nullable = true) String href) {}

  /** 动作声明（与 {@link ReconciliationAction} 同形，便于能力声明与实际动作对齐）。 */
  public record ReconciliationCapabilityAction(
      String code,
      String label,
      boolean allowed,
      @Schema(nullable = true) String method,
      @Schema(nullable = true) String path,
      boolean required,
      @Schema(nullable = true) String precondition,
      @Schema(nullable = true) String blockingReason,
      @Schema(nullable = true) String ownerModule,
      @Schema(nullable = true) String unavailableReason) {}

  /** 边界声明：本模块刻意不做的事。 */
  public record ReconciliationBoundary(
      String boundary, @Schema(nullable = true) String ownerModule, String reason) {}

  /** 能力声明整体。 */
  public record ReconciliationCapabilities(
      List<String> dataSources,
      List<ReconciliationCapabilitySection> sections,
      List<ReconciliationRelatedModule> related,
      List<ReconciliationCapabilityAction> actions,
      List<ReconciliationBoundary> boundaries,
      String stateMachine,
      String hardRuleBasis,
      String hardRuleNoPayable,
      String hardRuleDiscrepancy,
      String hardRuleCurrency,
      String hardRuleQuality) {}

  /** 建单时可选的供应商（取自已交付事实的真实供应商，不是造出来的）。 */
  public record ReconciliationSupplierOption(
      UUID supplierRefId, String supplierCode, String supplierName, int statementCount) {}

  /** 下拉项集合。 */
  public record ReconciliationOptions(
      List<ReconciliationSupplierOption> suppliers,
      List<ReconciliationPeriodOption> periods,
      List<String> currencies,
      List<ReconciliationDeductionOption> deductionTypes,
      String supplierNote,
      String periodNote) {}

  /** 期间预设（月度 / 季度的服务端裁决口径）。 */
  public record ReconciliationPeriodOption(
      String periodType, String periodTypeLabel, LocalDate periodStart, LocalDate periodEnd, String label) {}

  /** 代扣代缴费用类型下拉。 */
  public record ReconciliationDeductionOption(String code, String label) {}
}
