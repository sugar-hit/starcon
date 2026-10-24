package com.starcon.finance;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 12.5.2.2 财务报表的传输模型。
 *
 * <p>蓝图 12-finance.md 原文：
 *
 * <blockquote>
 *
 * 职责：生成资产负债表、利润表、现金流量表、科目余额表、明细账；支持期间对比、导出、钻取到凭证。 输入：已过账凭证；期间范围；报表模板。 输出：<b>报表 + 钻取路径 + 导出文件</b>。关系：上游 = 12.5.2.1 凭证管理；下游 = 17 审计、对外报送。<b>报表数字必须能钻到凭证</b>，"报表与明细对不上"是财务系统的致命问题。
 *
 * </blockquote>
 *
 * <p><b>命名。</b>统一用 {@code FinanceReport*} 前缀 —— 与既有 {@code FinanceVoucher*}、
 * {@code FinanceFund*}、{@code FinancePaymentExecution*} 零重叠：契约被 codegen 合并进一个扁平文件，
 * 同名后者覆盖前者且不报冲突。
 *
 * <p><b>金额一律 BigDecimal + NUMERIC</b>：本文件里没有一个 {@code double}。金额 {@code numeric(19,4)}。
 *
 * <p><b>币种分开</b>：任何汇总都按币种分组返回（{@link FinanceReportCurrencyGroup}）——
 * USD 与 EUR 加起来不是任何一个金额。
 */
public final class FinancialReportModels {

  private FinancialReportModels() {}

  /** 金额小数位（与既有 finance 模块一致）。 */
  public static final int MONEY_SCALE = 4;

  // ================================================================ 枚举

  /** 报表类型（五张报表，蓝图无豁免）。 */
  public enum FinancialReportType {
    /** 资产负债表。 */
    BALANCE_SHEET("资产负债表"),
    /** 利润表。 */
    INCOME_STATEMENT("利润表"),
    /** 现金流量表。 */
    CASH_FLOW("现金流量表"),
    /** 科目余额表。 */
    TRIAL_BALANCE("科目余额表"),
    /** 明细账。 */
    GENERAL_LEDGER("明细账");

    private final String label;

    FinancialReportType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 报表段落。
   *
   * <p>🔴 <b>模板可以自定义段落</b>（{@code section} 是自由文本）⇒ 这个枚举只是**默认段落**：
   * 没有模板时按科目类别归到这些段落；有模板时按模板里的段落名分组。
   */
  public enum FinancialReportSection {
    /** 资产。 */
    ASSET("资产"),
    /** 负债。 */
    LIABILITY("负债"),
    /** 所有者权益。 */
    EQUITY("所有者权益"),
    /** 损益结转（累计未结转的损益）。 */
    RETAINED("损益结转"),
    /** 未分类（科目没在科目表登记）。 */
    UNCLASSIFIED("未分类"),
    /** 收入。 */
    REVENUE("收入"),
    /** 成本与费用。 */
    EXPENSE("成本与费用"),
    /** 经营活动。 */
    OPERATING("经营活动"),
    /** 投资活动。 */
    INVESTING("投资活动"),
    /** 筹资活动。 */
    FINANCING("筹资活动"),
    /** 未分类活动（对方科目没配活动类别）。 */
    CASH_UNCLASSIFIED("未分类活动"),
    /** 现金余额。 */
    CASH_BALANCE("现金余额"),
    /** 科目（未做段落归并的明细科目行）。 */
    SUBJECT("科目"),
    /** 合计行。 */
    TOTAL("合计");

    private final String label;

    FinancialReportSection(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 现金流活动类别（只对现金流量表有意义）。 */
  public enum FinancialReportCashFlowGroup {
    /** 经营活动。 */
    OPERATING("经营活动"),
    /** 投资活动。 */
    INVESTING("投资活动"),
    /** 筹资活动。 */
    FINANCING("筹资活动"),
    /** 未分类（对方科目没配活动类别）。 */
    UNCLASSIFIED("未分类活动");

    private final String label;

    FinancialReportCashFlowGroup(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 现金角色（只对现金流量表有意义）。 */
  public enum FinancialReportCashRole {
    /** 现金科目（银行存款 / 库存现金…）。 */
    CASH("现金科目"),
    /** 非现金科目（对方科目）。 */
    NON_CASH("对方科目");

    private final String label;

    FinancialReportCashRole(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 能力声明

  /** 能力条目：每一维说清「有没有、依据是什么、缺了会怎样」。 */
  public record FinanceReportCapability(
      String module, String name, boolean available, String basis, String ownerModule, String detail) {}

  /** 下游缺口：谁没交付、归谁。 */
  public record FinanceReportDownstreamGap(
      String module, String name, String ownerService, boolean available, String group, String detail) {}

  /**
   * 五张报表**各自的口径**。
   *
   * <p>🔴 这一条是本模块的命门：「报表与明细对不上」是财务系统的致命问题 ⇒
   * 口径不能只写在注释里，必须**逐张报表**说清「草稿怎么办 / 红冲怎么办 / 多币种怎么办」，
   * 并且**随报表一起返回**（页面原样显示）。
   */
  public record FinanceReportBasis(
      String reportType,
      String reportTypeLabel,
      /** 草稿口径。 */
      String draftPolicy,
      /** 红冲口径。 */
      String reversalPolicy,
      /** 多币种口径。 */
      String currencyPolicy,
      /** 该报表特有的口径说明。 */
      String detail) {}

  /** 12.5.2.2 的能力声明（含五张报表的口径）。 */
  public record FinanceReportCapabilities(
      List<FinanceReportCapability> capabilities,
      List<FinanceReportDownstreamGap> downstreamGaps,
      List<FinanceReportBasis> bases) {}

  // ================================================================ 报表模板

  /** 报表模板行。 */
  public record FinanceReportTemplateRow(
      UUID id,
      String reportType,
      String reportTypeLabel,
      String subjectCode,
      String subjectName,
      String lineCode,
      String lineName,
      String section,
      String sectionLabel,
      String cashFlowGroup,
      String cashFlowGroupLabel,
      String cashRole,
      String cashRoleLabel,
      int sortOrder,
      boolean enabled,
      String note,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 登记 / 修改报表模板的入参（乐观并发）。 */
  public record FinanceReportTemplateInput(
      String reportType,
      String subjectCode,
      @Schema(nullable = true) String subjectName,
      String lineCode,
      @Schema(nullable = true) String lineName,
      @Schema(nullable = true) String section,
      @Schema(nullable = true) String cashFlowGroup,
      @Schema(nullable = true) String cashRole,
      @Schema(nullable = true) Integer sortOrder,
      @Schema(nullable = true) Boolean enabled,
      @Schema(nullable = true) String note,
      /** 乐观并发：修改模板时必须带上当前版本号，不匹配 ⇒ 冲突。 */
      @Schema(nullable = true) Integer expectedVersion) {}

  // ================================================================ 筛选项

  /** 筛选项里的一个会计期间（来自 12.5.2.1 的期间登记）。 */
  public record FinanceReportPeriodRef(
      UUID id,
      String periodCode,
      LocalDate periodStart,
      LocalDate periodEnd,
      String status,
      String baseCurrency) {}

  /**
   * 筛选项里的一个科目。
   *
   * @param category 科目类别（来自 12.5.1.1 科目表；未登记 ⇒ 空串）
   * @param balanceDirection 余额方向（未登记 ⇒ 空串）
   */
  public record FinanceReportSubjectRef(
      String subjectCode, String subjectName, String category, String balanceDirection) {}

  /** 报表筛选项（全部来自**真实数据**，不是写死的词表）。 */
  public record FinanceReportOptions(
      List<FinanceReportPeriodRef> periods,
      List<String> currencies,
      List<FinanceReportSubjectRef> subjects,
      List<FinanceReportTemplateRow> templates,
      List<String> reportTypes) {}

  // ================================================================ 报表主体

  /**
   * 报表的一行。
   *
   * <p>🔴 <b>每一行都必须能钻到凭证</b>：{@link #drillPath} 是**真实可走的路径**
   * （不是文案），指向本模块的钻取端点，由它给出构成这一行的凭证 + 分录行。
   */
  public record FinanceReportRow(
      /** 行标识（同一张报表内唯一）。 */
      String rowKey,
      /** 段落码。 */
      String section,
      /** 段落名。 */
      String sectionLabel,
      /** 报表行编码（模板里定义的行码；没有模板时就是科目编码）。 */
      String lineCode,
      /** 报表行名称。 */
      String lineName,
      /** 科目编码（这一行只由一个科目构成时才有值；归并了多个科目时为空）。 */
      @Schema(nullable = true) String subjectCode,
      /** 科目名称。 */
      @Schema(nullable = true) String subjectName,
      /** 币种。 */
      String currency,
      /** 科目类别（未在科目表登记 ⇒ 空串）。 */
      String category,
      /** 余额方向（未在科目表登记 ⇒ 空串）。 */
      String balanceDirection,
      /** 期初借方余额。 */
      BigDecimal openingDebit,
      /** 期初贷方余额。 */
      BigDecimal openingCredit,
      /** 期初余额（按科目余额方向取正负）。 */
      BigDecimal openingBalance,
      /** 本期借方发生额。 */
      BigDecimal periodDebit,
      /** 本期贷方发生额。 */
      BigDecimal periodCredit,
      /** 本期净额（按科目余额方向取正负；收入为正、支出为正）。 */
      BigDecimal periodNet,
      /** 期末借方余额。 */
      BigDecimal closingDebit,
      /** 期末贷方余额。 */
      BigDecimal closingCredit,
      /** 期末余额（按科目余额方向取正负）。 */
      BigDecimal closingBalance,
      /** 对比期本期净额。 */
      BigDecimal comparisonPeriodNet,
      /** 对比期期末余额。 */
      BigDecimal comparisonClosingBalance,
      /** 本期净额 − 对比期本期净额。 */
      BigDecimal deltaPeriodNet,
      /** 本期期末余额 − 对比期期末余额。 */
      BigDecimal deltaClosingBalance,
      /** 构成这一行的凭证数（本期）。 */
      long voucherCount,
      /** 构成这一行的分录行数（本期）。 */
      long lineCount,
      /** 是不是合计行。 */
      boolean totalRow,
      /** 钻取路径（真实端点）。 */
      String drillPath) {}

  /**
   * 明细账的一笔（**逐条**分录行）。
   *
   * <p>明细账本身就是「钻取到凭证」的最终形态：每一行都带着真实的凭证号与分录行号。
   */
  public record FinanceReportLedgerEntry(
      String rowKey,
      String subjectCode,
      String subjectName,
      String currency,
      LocalDate entryDate,
      String periodCode,
      UUID voucherId,
      String voucherNo,
      String voucherStatus,
      UUID lineId,
      int lineNo,
      String lineType,
      String lineTypeLabel,
      String summary,
      BigDecimal amount,
      BigDecimal debitAmount,
      BigDecimal creditAmount,
      /** 逐笔余额（按科目余额方向取正负）。 */
      BigDecimal runningBalance,
      /** 对方科目（同一张凭证的其它分录行）。 */
      String counterpartSummary,
      /** 钻取路径（真实端点，指向这张凭证）。 */
      String drillPath) {}

  /** 一组合计。 */
  public record FinanceReportTotals(
      BigDecimal openingDebit,
      BigDecimal openingCredit,
      BigDecimal openingBalance,
      BigDecimal periodDebit,
      BigDecimal periodCredit,
      BigDecimal periodNet,
      BigDecimal closingDebit,
      BigDecimal closingCredit,
      BigDecimal closingBalance,
      long voucherCount,
      long lineCount) {}

  /**
   * 报表的**自检**。
   *
   * <p>🔴 这是「报表与明细对得上」的机器证明：
   * <ul>
   *   <li>借贷平衡：本期借方合计 = 本期贷方合计 ⇒ 差额必须为 0；</li>
   *   <li>会计恒等式：资产 = 负债 + 所有者权益 + 累计未结转损益 ⇒ 差额必须为 0。</li>
   * </ul>
   * 差额非 0 时**如实报出差额并说明可能原因**，绝不悄悄抹平。
   */
  public record FinanceReportCheck(
      String checkName,
      boolean balanced,
      BigDecimal leftValue,
      BigDecimal rightValue,
      BigDecimal difference,
      String detail) {}

  /** 一个币种的报表分组（**不同币种绝不合并**）。 */
  public record FinanceReportCurrencyGroup(
      String currency,
      List<FinanceReportRow> rows,
      List<FinanceReportLedgerEntry> ledgerEntries,
      FinanceReportTotals totals,
      FinanceReportTotals comparisonTotals,
      List<FinanceReportCheck> checks) {}

  /** 一张报表的完整结果。 */
  public record FinanceReportResult(
      String reportType,
      String reportTypeLabel,
      /** 本期期间范围。 */
      String periodFromCode,
      String periodToCode,
      String periodLabel,
      /** 对比期期间范围（没填对比期 ⇒ 空串）。 */
      String comparePeriodFromCode,
      String comparePeriodToCode,
      String comparePeriodLabel,
      /** 币种范围：ALL / 具体币种。 */
      String currencyScope,
      List<FinanceReportCurrencyGroup> currencyGroups,
      /** 口径与缺口说明（页面原样显示）。 */
      String notice,
      /** 本期涉及的凭证数。 */
      long voucherCount,
      /** 本期涉及的分录行数。 */
      long lineCount,
      /** 本期**被排除**的草稿凭证数（草稿不是账，明确说出来）。 */
      long draftExcludedCount,
      /** 本期已红冲的原凭证数（红冲按净额处理，明确说出来）。 */
      long reversedVoucherCount) {}

  // ================================================================ 钻取

  /** 钻取到凭证的一条（凭证 + 分录行）。 */
  public record FinanceReportDrillEntry(
      UUID voucherId,
      String voucherNo,
      LocalDate voucherDate,
      String periodCode,
      String voucherStatus,
      UUID lineId,
      int lineNo,
      String lineType,
      String lineTypeLabel,
      String subjectCode,
      String subjectName,
      String summary,
      BigDecimal amount,
      String currency,
      /** 钻取路径（真实端点，指向这张凭证）。 */
      String drillPath) {}

  /** 钻取结果：构成某个报表行的全部凭证与分录行。 */
  public record FinanceReportDrill(
      String reportType,
      String reportTypeLabel,
      String lineCode,
      String lineName,
      String section,
      String sectionLabel,
      @Schema(nullable = true) String subjectCode,
      @Schema(nullable = true) String subjectName,
      String currency,
      String periodLabel,
      BigDecimal debitTotal,
      BigDecimal creditTotal,
      BigDecimal netTotal,
      long voucherCount,
      long lineCount,
      List<FinanceReportDrillEntry> entries) {}

  // ================================================================ 导出

  /** 导出的报表文件（**真内容**，不是占位字节）。 */
  public record FinanceReportExportFile(
      String filename,
      String contentType,
      String content,
      int rowCount,
      int currencyCount,
      /** 导出内容的 SHA-256（十六进制）—— 与留痕表里写的一致。 */
      String contentHash) {}
}
