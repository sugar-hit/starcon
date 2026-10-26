package com.starcon.finance;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 12.5.2.1 凭证管理的传输模型。
 *
 * <p>蓝图 12-finance.md 原文：
 *
 * <blockquote>
 *
 * 职责：生成与管理会计凭证：由业务事件自动生成或手工录入，支持过账、校验（至少两行、金额有效、期间开放、借贷平衡）与红冲；已过账凭证不得反过账后改写，导出标准凭证文件。 输入：业务事件（发货、收货、收付款、费用计提）；手工凭证；{@code expected_version}。 输出：<b>凭证 + 分录行 + 过账状态；审计记录</b>。关系：上游 = 全部业务系统事件；下游 = 12.5.2.2 报表。<b>过账后不可改行，只能红冲</b>；过账事务要锁凭证头并在数据库层防护（实体目录 §9 明确触发器要求）。
 *
 * </blockquote>
 *
 * <p><b>命名。</b>统一用 {@code FinanceVoucher*} 前缀 —— 与既有 {@code FinancePaymentExecution*}、
 * {@code FinanceCostCollection*}、{@code FinanceBadDebt*} 零重叠：契约被 codegen 合并进一个扁平文件，
 * 同名后者覆盖前者且不报冲突。
 *
 * <p><b>金额一律 BigDecimal + NUMERIC</b>：本文件里没有一个 {@code double}。金额 {@code numeric(19,4)}、
 * 数量 {@code numeric(19,6)}、汇率 {@code numeric(19,8)}。
 *
 * <p><b>币种分开</b>：任何汇总都按币种分组返回 —— USD 与 EUR 加起来不是任何一个金额。
 */
public final class VoucherModels {

  private VoucherModels() {}

  /** 金额小数位（与既有 finance 模块一致）。 */
  public static final int MONEY_SCALE = 4;

  /** 数量小数位。 */
  public static final int QUANTITY_SCALE = 6;

  // ================================================================ 枚举

  /**
   * 凭证状态。
   *
   * <p>🔴 <b>没有「反过账」这条边</b>：DRAFT → POSTED → REVERSED，DRAFT → VOID。
   * 已过账的凭证**不可**回到 DRAFT（蓝图加粗句：「已过账凭证不得反过账后改写」）⇒
   * 要改写已过账的凭证，只能生成一张**红冲凭证**并把原凭证标成 REVERSED。
   */
  public enum VoucherStatus {
    /** 草稿：可以改行、可以作废。 */
    DRAFT("草稿"),
    /** 已过账：分录行冻结，事实冻结。 */
    POSTED("已过账"),
    /** 已红冲：已被一张红冲凭证冲掉。 */
    REVERSED("已红冲"),
    /** 已作废：草稿被作废（不占期间、不进报表口径）。 */
    VOID("已作废");

    private final String label;

    VoucherStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 终态：不可再推进。 */
    public boolean terminal() {
      return this == REVERSED || this == VOID;
    }

    /** 已过账（含已红冲）：分录行与事实都冻结。 */
    public boolean frozen() {
      return this == POSTED || this == REVERSED;
    }
  }

  /**
   * 凭证来源类型。
   *
   * <p>🔴 {@code sourceId} 是**逻辑来源引用**（db/schemas/v2/README.md：
   * 「凭证 source_type/source_id 是逻辑来源引用，不给多态 source_id 加指向 sales_order 的假 FK」）。
   */
  public enum VoucherSourceType {
    /** 手工录入。 */
    MANUAL("手工录入"),
    /** 发货（销售出库）业务事件。 */
    SHIPMENT("发货"),
    /** 收货（采购入库）业务事件。 */
    RECEIPT("收货"),
    /** 收付款业务事件。 */
    PAYMENT("收付款"),
    /** 费用计提业务事件。 */
    EXPENSE_ACCRUAL("费用计提"),
    /** 红冲凭证（反向凭证）。 */
    REVERSAL("红冲");

    private final String label;

    VoucherSourceType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 分录行的借贷方向。 */
  public enum VoucherLineType {
    /** 借方。 */
    DEBIT("借"),
    /** 贷方。 */
    CREDIT("贷");

    private final String label;

    VoucherLineType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 审计事件类型。 */
  public enum VoucherEventType {
    /** 创建。 */
    CREATED("创建"),
    /** 修改草稿。 */
    UPDATED("修改"),
    /** 自动生成。 */
    AUTO_GENERATED("自动生成"),
    /** 过账。 */
    POSTED("过账"),
    /** 生成红冲凭证。 */
    REVERSAL_CREATED("生成红冲凭证"),
    /** 标记已红冲。 */
    REVERSED("已红冲"),
    /** 作废。 */
    VOIDED("作废"),
    /** 导出。 */
    EXPORTED("导出"),
    /** 校验未通过。 */
    VALIDATION_FAILED("校验未通过");

    private final String label;

    VoucherEventType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 辅助核算维度（**逻辑引用，不建跨模块 FK**）。 */
  public enum VoucherAuxiliaryType {
    /** 无。 */
    NONE("无"),
    /** 订单。 */
    ORDER("订单"),
    /** 供应商。 */
    SUPPLIER("供应商"),
    /** 付款单。 */
    PAYMENT_ORDER("付款单"),
    /** 费用归集条目。 */
    COST_ENTRY("归集条目"),
    /** 客户。 */
    CUSTOMER("客户"),
    /** 平台 / 店铺。 */
    PLATFORM("平台"),
    /** 资金账户。 */
    ACCOUNT("资金账户");

    private final String label;

    VoucherAuxiliaryType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 能力声明

  /** 能力条目：每一维说清「有没有、依据是什么、缺了会怎样」。 */
  public record FinanceVoucherCapability(
      String module, String name, boolean available, String basis, String ownerModule, String detail) {}

  /** 下游缺口：谁没交付、归谁。 */
  public record FinanceVoucherDownstreamGap(
      String module, String name, String ownerService, boolean available, String group, String detail) {}

  /** 12.5.2.1 的能力声明。 */
  public record FinanceVoucherCapabilities(
      List<FinanceVoucherCapability> capabilities,
      List<FinanceVoucherDownstreamGap> downstreamGaps) {}

  // ================================================================ 会计期间

  /** 会计期间（**过渡期 bootstrap**，最终归属 12.5.1.2）。 */
  public record FinanceVoucherPeriodRow(
      UUID id,
      String periodCode,
      LocalDate periodStart,
      LocalDate periodEnd,
      String status,
      String baseCurrency,
      @Schema(nullable = true) OffsetDateTime closedAt,
      @Schema(nullable = true) UUID closedBy,
      String closedReason,
      String note,
      int version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 登记会计期间的入参。 */
  public record FinanceVoucherPeriodInput(
      String periodCode,
      LocalDate periodStart,
      LocalDate periodEnd,
      String baseCurrency,
      @Schema(nullable = true) String note) {}

  /** 关闭会计期间的入参（乐观并发）。 */
  public record FinanceVoucherPeriodCloseInput(
      String reason,
      @Schema(nullable = true) Integer expectedVersion) {}

  // ================================================================ 映射

  /** 业务事件 → 借贷科目映射（租户维护，系统不预置任何一行）。 */
  public record FinanceVoucherMappingRow(
      UUID id,
      String sourceType,
      String eventKind,
      String debitSubjectCode,
      String debitSubjectName,
      String creditSubjectCode,
      String creditSubjectName,
      boolean enabled,
      String note,
      int version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 登记 / 修改映射的入参。 */
  public record FinanceVoucherMappingInput(
      String sourceType,
      String eventKind,
      String debitSubjectCode,
      String debitSubjectName,
      String creditSubjectCode,
      String creditSubjectName,
      @Schema(nullable = true) Boolean enabled,
      @Schema(nullable = true) String note) {}

  // ================================================================ 凭证

  /** 凭证头。 */
  public record FinanceVoucherRow(
      UUID id,
      String voucherNo,
      String sourceType,
      String sourceTypeLabel,
      @Schema(nullable = true) UUID sourceId,
      String sourceNo,
      @Schema(nullable = true) UUID reversalOfId,
      @Schema(nullable = true) UUID reversalVoucherId,
      UUID periodId,
      String periodCode,
      LocalDate voucherDate,
      String description,
      String currency,
      String status,
      String statusLabel,
      BigDecimal totalDebit,
      BigDecimal totalCredit,
      int lineCount,
      @Schema(nullable = true) OffsetDateTime postedAt,
      @Schema(nullable = true) UUID postedBy,
      @Schema(nullable = true) OffsetDateTime reversedAt,
      String note,
      int version,
      @Schema(nullable = true) UUID createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 分录行。 */
  public record FinanceVoucherLineRow(
      UUID id,
      int lineNo,
      String lineType,
      String lineTypeLabel,
      /** 科目编码（**软引用**：12.5.1.1 会计科目未交付 ⇒ 不建 FK）。 */
      String subjectCode,
      /** 科目名称快照。 */
      String subjectName,
      String summary,
      BigDecimal amount,
      String currency,
      @Schema(nullable = true) BigDecimal quantity,
      String auxiliaryType,
      @Schema(nullable = true) UUID auxiliaryId,
      String auxiliaryName) {}

  /** 审计事件（append-only 举证凭据）。 */
  public record FinanceVoucherEventRow(
      UUID id,
      String eventType,
      String eventTypeLabel,
      String fromStatus,
      String toStatus,
      String reason,
      String detail,
      @Schema(nullable = true) UUID actorId,
      OffsetDateTime occurredAt) {}

  /** 凭证详情：凭证 + 分录行 + 审计留痕。 */
  public record FinanceVoucherDetail(
      FinanceVoucherRow voucher, List<FinanceVoucherLineRow> lines, List<FinanceVoucherEventRow> events) {}

  /** 按币种的凭证汇总。 */
  public record FinanceVoucherCurrencySummary(
      String currency,
      BigDecimal totalDebit,
      BigDecimal totalCredit,
      long count,
      long postedCount,
      long draftCount,
      long reversedCount) {}

  /** 凭证列表页。 */
  public record FinanceVoucherPage(
      List<FinanceVoucherRow> items,
      int page,
      int size,
      long total,
      List<FinanceVoucherCurrencySummary> summaryByCurrency,
      long draftCount,
      long postedCount) {}

  /** 筛选项里的一个引用（id + 名称）。 */
  public record FinanceVoucherOptionRef(String id, String name) {}

  /**
   * 筛选项下拉：全部来自**真实数据**（distinct 查询），不是写死的词表。
   *
   * @param periods 会计期间（来自期间表的真实取值）
   * @param subjectCodes 已在凭证上用过的科目编码（distinct）
   */
  public record FinanceVoucherOptions(
      List<String> currencies,
      List<String> statuses,
      List<String> sourceTypes,
      List<FinanceVoucherOptionRef> periods,
      List<String> subjectCodes) {}

  // ================================================================ 写入入参

  /** 列表查询条件。 */
  public record VoucherQuery(
      @Schema(nullable = true) VoucherStatus status,
      @Schema(nullable = true) VoucherSourceType sourceType,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) UUID periodId,
      @Schema(nullable = true) LocalDate dateFrom,
      @Schema(nullable = true) LocalDate dateTo,
      @Schema(nullable = true) String keyword,
      int page,
      int size) {}

  /** 一条分录行的入参。 */
  public record FinanceVoucherLineInput(
      String lineType,
      String subjectCode,
      String subjectName,
      @Schema(nullable = true) String summary,
      BigDecimal amount,
      String currency,
      @Schema(nullable = true) BigDecimal quantity,
      @Schema(nullable = true) String auxiliaryType,
      @Schema(nullable = true) UUID auxiliaryId,
      @Schema(nullable = true) String auxiliaryName) {}

  /** 手工录入 / 修改一张草稿凭证的入参。 */
  public record FinanceVoucherInput(
      String sourceType,
      @Schema(nullable = true) UUID sourceId,
      @Schema(nullable = true) String sourceNo,
      /** 会计期间（**必填**，但允许传 null ⇒ 服务层给一句「必须指定会计期间」，而不是解析失败）。 */
      @Schema(nullable = true) UUID periodId,
      /** 业务日期（不填 ⇒ 取今天）。 */
      @Schema(nullable = true) LocalDate voucherDate,
      String description,
      String currency,
      @Schema(nullable = true) String note,
      /** 乐观并发：修改草稿时必须带上当前版本号，不匹配 ⇒ 冲突（不静默覆盖）。 */
      @Schema(nullable = true) Integer expectedVersion,
      List<FinanceVoucherLineInput> lines) {}

  /** 过账的入参（乐观并发）。 */
  public record FinanceVoucherPostInput(
      @Schema(nullable = true) Integer expectedVersion, @Schema(nullable = true) String note) {}

  /** 红冲的入参（乐观并发 + 原因必填）。 */
  public record FinanceVoucherReverseInput(
      @Schema(nullable = true) Integer expectedVersion,
      String reason,
      @Schema(nullable = true) LocalDate voucherDate,
      @Schema(nullable = true) String note) {}

  /** 作废草稿的入参（乐观并发 + 原因必填）。 */
  public record FinanceVoucherVoidInput(
      @Schema(nullable = true) Integer expectedVersion, String reason) {}

  /**
   * 由业务事件自动生成凭证的入参。
   *
   * @param sourceType 业务事件类型（SHIPMENT / RECEIPT / PAYMENT / EXPENSE_ACCRUAL）
   * @param eventKind 事件细分（同一 source_type 下的不同业务口径）
   * @param sourceId 业务事实的 id（逻辑引用）
   */
  public record FinanceVoucherGenerateInput(
      String sourceType,
      String eventKind,
      /** 业务事实 id（**必填**，但允许传 null ⇒ 服务层给一句「必须指定业务事实」）。 */
      @Schema(nullable = true) UUID sourceId,
      /** 会计期间（**必填**，但允许传 null ⇒ 服务层给一句「必须指定会计期间」）。 */
      @Schema(nullable = true) UUID periodId,
      @Schema(nullable = true) LocalDate voucherDate,
      @Schema(nullable = true) String description,
      @Schema(nullable = true) String note) {}

  /**
   * 导出标准凭证文件的入参。
   *
   * @param format 文件格式（CSV / JSON）
   */
  public record FinanceVoucherExportInput(
      String format,
      @Schema(nullable = true) UUID periodId,
      @Schema(nullable = true) LocalDate dateFrom,
      @Schema(nullable = true) LocalDate dateTo,
      @Schema(nullable = true) String status) {}

  // ================================================================ 结果

  /**
   * 过账 / 红冲 / 作废 / 自动生成的结果。
   *
   * @param message 一句能直接展示给人看的话（说清做了什么、为什么）
   */
  public record FinanceVoucherResult(
      FinanceVoucherRow voucher,
      List<FinanceVoucherLineRow> lines,
      @Schema(nullable = true) FinanceVoucherRow reversalVoucher,
      String message) {}

  /** 导出的标准凭证文件（**真内容**，不是占位字节）。 */
  public record FinanceVoucherExportFile(
      String filename, String contentType, String content, int voucherCount, int lineCount) {}
}
