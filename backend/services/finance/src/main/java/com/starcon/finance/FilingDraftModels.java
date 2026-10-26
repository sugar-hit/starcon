package com.starcon.finance;

import com.starcon.finance.TaxRateModels.FinanceTaxJurisdictionOptions;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 12.6.1.2 VAT/IOSS 申报数据的传输模型。
 *
 * <p>蓝图 12-finance.md 原文：
 *
 * <blockquote>
 *
 * 职责：按辖区与周期汇总申报所需数据： taxable 销售额、已收 VAT、IOSS 订单与代 collected 金额、可抵扣进项，导出申报文件或对接申报接口。 输入：订单税务数据；结算与收付款数据；汇率快照；辖区规则。 输出：申报底稿 + 汇总报表 + 可导出文件。 关系：上游 = 12.6.1.1、9 订单、12.2.1.1；下游 = 16 合规（申报日历提醒）。<b>本系统提供申报底稿，不替代税务申报动作</b>——申报主体与责任在用户，系统保证数据完整可核。
 *
 * </blockquote>
 *
 * <p><b>命名。</b>统一用 {@code FinanceFiling*} 前缀，与既有服务的类型名零重叠 —— 契约被
 * codegen 合并进一个扁平文件，同名后者覆盖前者且不报冲突。
 *
 * <p><b>🔴 金额一律 BigDecimal + NUMERIC</b>：本文件里没有一个 {@code double}。
 * 金额列是 {@code numeric(19,4)}，税率是 {@code numeric(9,6)}，汇率是 {@code numeric(19,8)}。
 *
 * <p><b>🔴 多币种绝不相加</b>：{@link FinanceFilingDraftLine} 是**按币种分组**的，
 * 底稿头（{@link FinanceFilingDraftRow}）**没有任何金额列** —— 页面上不存在一个
 * 「跨币种总额」这种没有意义的数。
 *
 * <p><b>🔴 读不到 ≠ 是零</b>：IOSS 两列、可抵扣进项、汇率、折算值、实际到账金额
 * 在「读不到」时是 {@code null}（配一个 {@code *Available} 标记与一句写明原因的
 * note），**不是 0**。
 */
public final class FilingDraftModels {

  private FilingDraftModels() {}

  /** 金额小数位（金额列是 numeric(19,4)）。 */
  public static final int AMOUNT_SCALE = 4;

  /** 汇率小数位（汇率列是 numeric(19,8)）。 */
  public static final int RATE_SCALE = 8;

  // ================================================================ 枚举

  /**
   * 本模块的申报线：增值税申报 与 IOSS 申报。
   *
   * <p>🔴 <b>这里没有美国州销售税</b>：州级销售税归 channel 16.3.2.3 维护，
   * 本模块不重复建表、也不出它的申报底稿。
   */
  public enum FilingTaxType {
    /** 增值税申报（常规 VAT）。 */
    VAT("增值税申报", "按辖区与周期汇总应税销售额、已收 VAT 与可抵扣进项，产出申报底稿。"),
    /** IOSS 申报（欧盟进口一站式商店）。 */
    IOSS("IOSS 申报", "汇总 IOSS 订单与平台代收金额，产出申报底稿；订单清单事实归 channel 16.3.2.1。");

    private final String label;
    private final String basis;

    FilingTaxType(String label, String basis) {
      this.label = label;
      this.basis = basis;
    }

    public String label() {
      return label;
    }

    public String basis() {
      return basis;
    }

    /** 解析取值；非法取值返回 {@code null}（由服务层给一句能直接展示的话）。 */
    public static FilingTaxType parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var t : values()) {
        if (t.name().equalsIgnoreCase(raw.trim())) return t;
      }
      return null;
    }
  }

  /** 申报周期。 */
  public enum FilingCycle {
    /** 月度申报。 */
    MONTHLY("月度"),
    /** 季度申报。 */
    QUARTERLY("季度");

    private final String label;

    FilingCycle(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析取值；非法取值返回 {@code null}。 */
    public static FilingCycle parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var c : values()) {
        if (c.name().equalsIgnoreCase(raw.trim())) return c;
      }
      return null;
    }
  }

  /** 底稿状态。 */
  public enum DraftStatus {
    /** 生效中（同一 辖区 × 税种 × 期间 × 周期 同时只有一份）。 */
    GENERATED("生效中"),
    /** 已被新一轮取数取代（历史快照，保留供举证与导出）。 */
    SUPERSEDED("已被取代");

    private final String label;

    DraftStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析状态取值；非法取值返回 {@code null}（页面原样显示，不显示 undefined）。 */
    public static DraftStatus parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw.trim())) return s;
      }
      return null;
    }
  }

  /** 导出格式。 */
  public enum ExportFormat {
    /** CSV（给税务系统上传 / 人工核对）。 */
    CSV("CSV", "text/csv; charset=utf-8"),
    /** JSON（给系统对接）。 */
    JSON("JSON", "application/json; charset=utf-8");

    private final String label;
    private final String contentType;

    ExportFormat(String label, String contentType) {
      this.label = label;
      this.contentType = contentType;
    }

    public String label() {
      return label;
    }

    public String contentType() {
      return contentType;
    }

    /** 解析取值；非法取值返回 {@code null}。 */
    public static ExportFormat parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var f : values()) {
        if (f.name().equalsIgnoreCase(raw.trim())) return f;
      }
      return null;
    }
  }

  // ================================================================ 能力声明

  /** 能力条目：每一维说清「有没有、依据是什么、缺了会怎样」。 */
  public record FinanceFilingCapability(
      String module, String name, boolean available, String basis, String ownerModule, String detail) {}

  /** 下游 / 上游缺口：谁没交付、归谁。 */
  public record FinanceFilingGap(
      String module, String name, String ownerService, boolean available, String group, String detail) {}

  /** 12.6.1.2 的能力声明。 */
  public record FinanceFilingCapabilities(
      List<FinanceFilingCapability> capabilities, List<FinanceFilingGap> gaps, String productBoundary) {}

  // ================================================================ 选项

  /** 一个申报线选项（值 + 名称 + 口径说明）。 */
  public record FinanceFilingTypeOption(String value, String label, String basis) {}

  /** 一个申报周期选项。 */
  public record FinanceFilingCycleOption(String value, String label) {}

  /**
   * 筛选项下拉。
   *
   * @param jurisdictions 辖区选项（**实时**读 channel 16.3.1.1 的受控辖区码表；
   *     形状与 12.6.1.1 的辖区选项完全一致 —— 同一个码表，**不复制**）
   * @param currencies 底稿里真实出现过的币种（来自真实数据，不是硬编码）
   */
  public record FinanceFilingOptions(
      List<FinanceFilingTypeOption> taxTypes,
      List<FinanceFilingCycleOption> filingCycles,
      List<String> statuses,
      List<String> currencies,
      FinanceTaxJurisdictionOptions jurisdictions) {}

  // ================================================================ 数据来源状态

  /**
   * 一个数据来源的可用性。
   *
   * <p>🔴 <b>「读不到」必须能被界面原样说出来</b>：{@code available = false} 时
   * {@code reason} 里写明读不到的是什么、归谁、影响哪几个数。
   */
  public record FinanceFilingSource(
      String key,
      String module,
      String name,
      boolean available,
      String ownerService,
      String detail) {}

  // ================================================================ 底稿

  /** 一份申报底稿的头（**没有任何金额列** —— 金额在按币种分组的汇总行上）。 */
  public record FinanceFilingDraftRow(
      UUID id,
      String jurisdictionCode,
      String jurisdictionName,
      String countryCode,
      String taxType,
      String taxTypeLabel,
      LocalDate periodStart,
      LocalDate periodEnd,
      String filingCycle,
      String filingCycleLabel,
      @Schema(nullable = true) String baseCurrency,
      String orderSource,
      String status,
      String statusLabel,
      int orderCount,
      int includedCount,
      int outOfScopeCount,
      int unmappedCount,
      boolean orderTruncated,
      String generationBasis,
      OffsetDateTime generatedAt,
      @Schema(nullable = true) UUID generatedBy,
      String note,
      OffsetDateTime createdAt) {}

  /**
   * 一条汇总行（**一个币种一行**）。
   *
   * <p>🔴 {@code iossOrderCount} / {@code iossCollected} / {@code deductibleInput} /
   * {@code netPayable} / {@code exchangeRate} / {@code convertedNetPayable} /
   * {@code receivedAmount} 在「读不到」时是 {@code null}，**不是 0**。
   */
  public record FinanceFilingDraftLine(
      UUID id,
      UUID draftId,
      String currency,
      BigDecimal taxableSales,
      BigDecimal vatCollected,
      @Schema(nullable = true) Integer iossOrderCount,
      @Schema(nullable = true) BigDecimal iossCollected,
      @Schema(nullable = true) BigDecimal deductibleInput,
      boolean deductibleAvailable,
      String deductibleNote,
      @Schema(nullable = true) BigDecimal netPayable,
      @Schema(nullable = true) UUID taxRateId,
      String taxCode,
      @Schema(nullable = true) BigDecimal taxRate,
      @Schema(nullable = true) OffsetDateTime taxRateEffectiveFrom,
      @Schema(nullable = true) OffsetDateTime taxRateEffectiveTo,
      @Schema(nullable = true) BigDecimal exchangeRate,
      @Schema(nullable = true) LocalDate exchangeRateAsOf,
      String exchangeRateSource,
      boolean exchangeRateAvailable,
      @Schema(nullable = true) BigDecimal convertedNetPayable,
      @Schema(nullable = true) BigDecimal receivedAmount,
      int orderCount,
      String note) {}

  /** 底稿详情：头 + 汇总行 + 数据来源可用性 + 口径。 */
  public record FinanceFilingDraftDetail(
      FinanceFilingDraftRow draft,
      List<FinanceFilingDraftLine> lines,
      List<FinanceFilingSource> sources,
      String currencyNote,
      String productBoundary) {}

  /** 底稿列表页（真分页 + 真筛选）。 */
  public record FinanceFilingDraftPage(
      List<FinanceFilingDraftRow> items, int page, int size, long total, long liveCount) {}

  /**
   * 生成底稿的入参。
   *
   * <p>⚠️ {@code taxCode} 是<b>必填</b>：12.6.1.1 的「按生效时间取税率」在按辖区取数时
   * 必须给税码（不同税档是不同税率），拿不到就**报错**而不是猜一个。
   */
  public record FinanceFilingDraftInput(
      String jurisdictionCode,
      String taxType,
      String taxCode,
      LocalDate periodStart,
      LocalDate periodEnd,
      String filingCycle,
      @Schema(nullable = true) String baseCurrency,
      @Schema(nullable = true) String orderSource,
      @Schema(nullable = true) String note) {}

  // ================================================================ 汇总报表

  /**
   * 汇总报表（**按币种分组**）。
   *
   * <p>🔴 汇总行与底稿行是**同一份数据**的两种呈现，不建第二张表 —— 两个真值源
   * 会漂移。
   */
  public record FinanceFilingSummary(
      UUID draftId,
      String jurisdictionCode,
      String jurisdictionName,
      String taxType,
      LocalDate periodStart,
      LocalDate periodEnd,
      String filingCycle,
      @Schema(nullable = true) String baseCurrency,
      OffsetDateTime generatedAt,
      List<FinanceFilingDraftLine> lines,
      List<FinanceFilingSource> sources,
      String currencyNote,
      String productBoundary) {}

  // ================================================================ 导出

  /** 一次导出的留痕。 */
  public record FinanceFilingExportRow(
      UUID id,
      UUID draftId,
      String draftJurisdictionCode,
      String draftTaxType,
      LocalDate draftPeriodStart,
      LocalDate draftPeriodEnd,
      String format,
      String fileName,
      String contentType,
      int rowCount,
      int byteSize,
      String contentHash,
      OffsetDateTime exportedAt,
      @Schema(nullable = true) UUID exportedBy,
      String note) {}

  /** 导出留痕列表页（真分页 + 真筛选）。 */
  public record FinanceFilingExportPage(
      List<FinanceFilingExportRow> items, int page, int size, long total) {}
}
