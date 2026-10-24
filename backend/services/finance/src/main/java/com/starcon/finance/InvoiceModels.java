package com.starcon.finance;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 12.6.2.1 发票管理的传输模型。
 *
 * <p>蓝图 12-finance.md 原文：
 *
 * <blockquote>
 *
 * 职责：管理销售发票与贷项通知单：按平台/国家模板生成、维护税码税率税额、编号与状态（草稿/开具/作废/红冲），支持批量与平台开票对接。 输入：订单与结算数据；发票模板；编号规则；作废申请。 输出：发票与贷项单 + 编号序列 + 状态记录。 关系：上游 = 9 订单、12.3.1；下游 = 12.5.2.1 凭证、11.4 售后（退货开票调整）。<b>各国发票要求差异大</b>，模板必须按国家配置；本系统不承诺覆盖所有国家税务规则。
 *
 * </blockquote>
 *
 * <p><b>命名。</b>统一用 {@code FinanceInvoice*} 前缀，与既有服务的类型名零重叠 —— 契约被 codegen
 * 合并进一个扁平文件，同名后者覆盖前者且不报冲突。
 *
 * <p><b>🔴 金额一律 BigDecimal + NUMERIC</b>：本文件里没有一个 {@code double}。
 * 发票金额 {@code numeric(19,4)}，数量 {@code numeric(19,6)}，税率 {@code numeric(9,6)}。
 *
 * <p><b>🔴 一张发票只有一个币种</b>：跨币种的合计一律<b>按币种分组</b>呈现
 * （见 {@link FinanceInvoiceCurrencySummary}），本模块<b>不做本位币折算</b>，
 * 汇率不可得时如实报「汇率不可得」，<b>绝不静默按 1:1 相加</b>。
 */
public final class InvoiceModels {

  private InvoiceModels() {}

  // ================================================================ 枚举

  /** 单据类型：销售发票 / 贷项通知单。 */
  public enum DocumentType {
    /** 销售发票。 */
    INVOICE("销售发票"),
    /** 贷项通知单（红冲用，必须指向原发票）。 */
    CREDIT_NOTE("贷项通知单");

    private final String label;

    DocumentType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析单据类型；非法取值返回 {@code null}（由服务层给一句能直接展示的话）。 */
    public static DocumentType parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var t : values()) {
        if (t.name().equalsIgnoreCase(raw.trim())) return t;
      }
      return null;
    }
  }

  /** 发票状态（蓝图点名的四个）。 */
  public enum InvoiceStatus {
    /** 草稿：还没编号、没对外。 */
    DRAFT("草稿"),
    /** 已开具：已分配正式编号，是对外凭据。 */
    ISSUED("已开具"),
    /** 已作废（终态）：作废必须留痕（谁、何时、为什么）。 */
    VOID("已作废"),
    /** 已被红冲（终态）：必须有一张成对的贷项通知单。 */
    REVERSED("已红冲");

    private final String label;

    InvoiceStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析状态取值；非法取值返回 {@code null}（页面原样显示，不显示 undefined）。 */
    public static InvoiceStatus parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw.trim())) return s;
      }
      return null;
    }
  }

  /** 平台开票对接状态。 */
  public enum PlatformSyncStatus {
    /** 还没提交平台。 */
    NOT_SUBMITTED("未提交"),
    /** 已提交，等待平台回执。 */
    SUBMITTED("已提交"),
    /** 平台已确认（回执号已回填）。 */
    ACKNOWLEDGED("平台已确认"),
    /** 提交失败（失败原因已留痕）。 */
    FAILED("提交失败");

    private final String label;

    PlatformSyncStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析状态取值；非法取值返回 {@code null}。 */
    public static PlatformSyncStatus parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw.trim())) return s;
      }
      return null;
    }
  }

  // ================================================================ 能力声明

  /** 能力条目：每一维说清「有没有、依据是什么、缺了会怎样」。 */
  public record FinanceInvoiceCapability(
      String module,
      String name,
      boolean available,
      String basis,
      String ownerModule,
      String detail) {}

  /** 下游缺口：谁没交付、归谁。 */
  public record FinanceInvoiceGap(
      String module,
      String name,
      String ownerService,
      boolean available,
      String group,
      String detail) {}

  /**
   * 12.6.2.1 的能力声明。
   *
   * @param countryCoverage 各国覆盖情况（🔴 **不承诺覆盖所有国家税务规则**）
   */
  public record FinanceInvoiceCapabilities(
      List<FinanceInvoiceCapability> capabilities,
      List<FinanceInvoiceGap> downstreamGaps,
      List<FinanceInvoiceCountryCoverage> countryCoverage) {}

  /**
   * 一个国家的发票支持情况。
   *
   * <p>🔴 「各国发票要求差异大，模板必须按国家配置；本系统不承诺覆盖所有国家税务规则」——
   * 这一格把话说清楚：**支持哪些、缺口归谁**，不假装全支持。
   */
  public record FinanceInvoiceCountryCoverage(
      String countryCode,
      String jurisdictionCode,
      String name,
      boolean templateConfigured,
      boolean taxRateResolvable,
      String note) {}

  // ================================================================ 模板

  /** 一张发票模板。 */
  public record FinanceInvoiceTemplateRow(
      UUID id,
      String templateCode,
      String templateName,
      String countryCode,
      String jurisdictionCode,
      String jurisdictionName,
      String platformCode,
      String documentType,
      String documentTypeLabel,
      String taxType,
      String taxCode,
      String currency,
      String title,
      String sequencePrefix,
      String sequenceScope,
      String sequencePeriod,
      OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      String status,
      String note,
      @Schema(nullable = true) UUID createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * 登记一张发票模板。
   *
   * <p>⚠️ {@code effectiveFrom} 是<b>必填</b>：没有生效时间的模板等于没有 ——
   * 改模板会把历史发票的抬头变成另一个口径，而那张发票没人能解释。
   */
  public record FinanceInvoiceTemplateInput(
      String templateCode,
      String templateName,
      String countryCode,
      String jurisdictionCode,
      String platformCode,
      String documentType,
      String taxType,
      String taxCode,
      String currency,
      String title,
      String sequencePrefix,
      String sequenceScope,
      String sequencePeriod,
      OffsetDateTime effectiveFrom,
      String note) {}

  /** 模板列表页（真分页 + 真筛选）。 */
  public record FinanceInvoiceTemplatePage(
      List<FinanceInvoiceTemplateRow> items, int page, int size, long total) {}

  // ================================================================ 发票

  /** 一张发票（或贷项通知单）的头。 */
  public record FinanceInvoiceRow(
      UUID id,
      @Schema(nullable = true) String invoiceNumber,
      String documentType,
      String documentTypeLabel,
      String status,
      String statusLabel,
      String templateCode,
      String title,
      String countryCode,
      String jurisdictionCode,
      String jurisdictionName,
      String platformCode,
      @Schema(nullable = true) UUID shopId,
      String shopCode,
      @Schema(nullable = true) UUID sourceReceivableId,
      @Schema(nullable = true) UUID sourceSettlementId,
      String sourceOrderNo,
      String customerName,
      String currency,
      LocalDate issueDate,
      BigDecimal subtotalAmount,
      BigDecimal taxAmount,
      BigDecimal totalAmount,
      @Schema(nullable = true) UUID originalInvoiceId,
      String originalInvoiceNumber,
      @Schema(nullable = true) UUID reversalInvoiceId,
      String reason,
      @Schema(nullable = true) UUID issuedBy,
      @Schema(nullable = true) OffsetDateTime issuedAt,
      @Schema(nullable = true) UUID voidedBy,
      @Schema(nullable = true) OffsetDateTime voidedAt,
      @Schema(nullable = true) UUID reversedBy,
      @Schema(nullable = true) OffsetDateTime reversedAt,
      String platformSyncStatus,
      String platformSyncStatusLabel,
      String platformInvoiceRef,
      @Schema(nullable = true) UUID createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 一行明细（金额是生成列，服务层写不进去）。 */
  public record FinanceInvoiceLineRow(
      UUID id,
      UUID invoiceId,
      int lineNo,
      String skuCode,
      String productName,
      BigDecimal quantity,
      BigDecimal unitPrice,
      BigDecimal lineAmount,
      String taxType,
      String taxCode,
      BigDecimal taxRate,
      UUID taxRateId,
      BigDecimal taxAmount,
      @Schema(nullable = true) UUID originalLineId,
      @Schema(nullable = true) BigDecimal originalLineAmount,
      String note) {}

  /** 发票详情（头 + 明细）。 */
  public record FinanceInvoiceDetail(FinanceInvoiceRow invoice, List<FinanceInvoiceLineRow> lines) {}

  /** 发票列表页（真分页 + 真筛选）。 */
  public record FinanceInvoicePage(
      List<FinanceInvoiceRow> items, int page, int size, long total, long draftCount, long issuedCount) {}

  /**
   * 一行待建发票的明细（落库前**必须**实时校验税率）。
   *
   * <p>⚠️ {@code taxCode} 与 {@code taxType} 留空时用模板的默认值；填了就以行为准，
   * 但**照样**要过实时校验 —— 填了也校验不过的，照样写不进去。
   */
  public record FinanceInvoiceLineInput(
      String skuCode,
      String productName,
      BigDecimal quantity,
      BigDecimal unitPrice,
      String taxType,
      String taxCode,
      String note) {}

  /**
   * 建一张发票草稿。
   *
   * <p>⚠️ 建单**不**指定税率与税额：税率由服务层按（发票日 × 辖区 × 税码）实时取，
   * 税额由生成列算 —— 调用方**填不出**一个假的税额。
   */
  public record FinanceInvoiceCreateInput(
      UUID templateId,
      LocalDate issueDate,
      @Schema(nullable = true) UUID shopId,
      String shopCode,
      @Schema(nullable = true) UUID sourceSettlementId,
      String sourceOrderNo,
      String customerName,
      List<FinanceInvoiceLineInput> lines,
      String note) {}

  /** 作废申请（🔴 原因必填：作废必须留痕「谁、何时、为什么」）。 */
  public record FinanceInvoiceVoidInput(String reason) {}

  /**
   * 红冲申请。
   *
   * @param lineNos 要红冲的原发票行号（留空 = 整张红冲）
   * @param reason 红冲原因（必填）
   */
  public record FinanceInvoiceReverseInput(List<Integer> lineNos, String reason) {}

  // ================================================================ 汇总（按币种分组）

  /**
   * 一个币种的合计。
   *
   * <p>🔴 币种是汇总的第一维：{@code subtotal} / {@code tax} / {@code total} 三者
   * **只在同一个币种内**相加，跨币种**绝不**求和。
   *
   * @param baseAmount 本位币折算后的合计（**汇率不可得时为 null**，绝不按 1:1 兜底）
   * @param exchangeRateNote 汇率口径说明（不可得时写明「汇率不可得」）
   */
  public record FinanceInvoiceCurrencySummary(
      String currency,
      BigDecimal subtotal,
      BigDecimal tax,
      BigDecimal total,
      long invoiceCount,
      long creditNoteCount,
      @Schema(nullable = true) BigDecimal baseAmount,
      @Schema(nullable = true) BigDecimal exchangeRate,
      String exchangeRateNote) {}

  /**
   * 发票汇总（**按币种分组**）。
   *
   * @param byCurrency 分币种合计（🔴 **这里没有跨币种的合计数字**）
   * @param baseCurrency 请求折算到的本位币（空 = 不折算）
   * @param exchangeRateAvailable 本位币折算是否**全部**可得（false ⇒ 缺汇率的分组没有折算值）
   * @param note 口径说明（把「为什么没有总数」写清）
   */
  public record FinanceInvoiceSummary(
      List<FinanceInvoiceCurrencySummary> byCurrency,
      long invoiceCount,
      long creditNoteCount,
      long draftCount,
      long voidCount,
      long reversedCount,
      @Schema(nullable = true) String baseCurrency,
      boolean exchangeRateAvailable,
      String note) {}

  // ================================================================ 待开票需求（上游 9 订单）

  /** 一条待开票需求（上游 trade 的 b2b_receivable，**只读**）。 */
  public record FinanceInvoiceRequestRow(
      UUID id,
      String receivableNo,
      UUID orderId,
      String orderNo,
      String customerName,
      String currency,
      BigDecimal amount,
      OffsetDateTime invoiceRequestedAt,
      String invoiceState,
      List<FinanceInvoiceRequestLine> lines) {}

  /** 待开票需求的一行明细。 */
  public record FinanceInvoiceRequestLine(
      String skuCode, BigDecimal quantity, BigDecimal unitPrice, BigDecimal lineAmount) {}

  /**
   * 待开票需求清单。
   *
   * @param available 上游读到了吗（false ⇒ 页面显示「读不到」，**不是**「没有待开票需求」）
   */
  public record FinanceInvoiceRequestPage(
      List<FinanceInvoiceRequestRow> items,
      int page,
      int size,
      long total,
      boolean available,
      String sourceModule,
      String note) {}

  /** 批量建单的入参（从待开票需求批量生成草稿）。 */
  public record FinanceInvoiceBatchInput(
      UUID templateId, List<UUID> requestIds, LocalDate issueDate, String note) {}

  /**
   * 批量建单的结果。
   *
   * @param failed 逐条失败原因（🔴 批量里失败的那几条**必须**说清为什么，不能静默吞掉）
   */
  public record FinanceInvoiceBatchResult(
      List<FinanceInvoiceRow> created, List<FinanceInvoiceBatchFailure> failed) {}

  /** 批量建单里的一条失败。 */
  public record FinanceInvoiceBatchFailure(UUID requestId, String reason) {}

  // ================================================================ 状态记录

  /** 一条状态记录（append-only 留痕）。 */
  public record FinanceInvoiceEventRow(
      UUID id,
      UUID invoiceId,
      String eventType,
      String eventTypeLabel,
      String fromStatus,
      String toStatus,
      String reason,
      @Schema(nullable = true) UUID actorId,
      OffsetDateTime occurredAt,
      Map<String, Object> detail) {}

  // ================================================================ 平台开票对接

  /**
   * 平台开票报文。
   *
   * <p>🔴 金额在报文里是**字符串**（BigDecimal 原样）：报文是给平台与人工核对的凭据，
   * 走一遍 JSON 浮点解析就可能变成 23.799999999999997。
   */
  public record FinanceInvoicePlatformPayload(
      String platformCode,
      String documentType,
      @Schema(nullable = true) String invoiceNumber,
      LocalDate issueDate,
      String currency,
      String title,
      String jurisdictionCode,
      String jurisdictionName,
      String countryCode,
      String customerName,
      String sourceOrderNo,
      List<FinanceInvoicePayloadLine> lines,
      FinanceInvoicePayloadTotals totals,
      String digest) {}

  /** 报文的一行。 */
  public record FinanceInvoicePayloadLine(
      int lineNo,
      String skuCode,
      String productName,
      String quantity,
      String unitPrice,
      String lineAmount,
      String taxType,
      String taxCode,
      String taxRate,
      String taxAmount) {}

  /** 报文的合计。 */
  public record FinanceInvoicePayloadTotals(
      String subtotal, String tax, String total) {}

  /** 一次平台提交记录。 */
  public record FinanceInvoicePlatformSubmissionRow(
      UUID id,
      UUID invoiceId,
      String invoiceNumber,
      String platformCode,
      String status,
      String statusLabel,
      String payloadDigest,
      int attempts,
      String platformRef,
      String errorMessage,
      @Schema(nullable = true) OffsetDateTime submittedAt,
      @Schema(nullable = true) OffsetDateTime acknowledgedAt,
      OffsetDateTime createdAt) {}

  /** 平台提交记录清单。 */
  public record FinanceInvoicePlatformSubmissionPage(
      List<FinanceInvoicePlatformSubmissionRow> items, int page, int size, long total) {}

  /**
   * 批量提交平台开票的入参。
   *
   * @param platformCode 目标平台（空 = 用发票自己的平台）
   */
  public record FinanceInvoicePlatformSubmitInput(
      List<UUID> invoiceIds, String platformCode) {}

  /**
   * 批量提交的结果。
   *
   * @param failed 逐条失败原因（🔴 上游提交失败**必须**说清原话，不能只给一个「失败」）
   */
  public record FinanceInvoicePlatformSubmitResult(
      List<FinanceInvoicePlatformSubmissionRow> submitted,
      List<FinanceInvoicePlatformSubmitFailure> failed) {}

  /** 批量提交里的一条失败。 */
  public record FinanceInvoicePlatformSubmitFailure(UUID invoiceId, String reason) {}

  /** 登记平台回执的入参。 */
  public record FinanceInvoicePlatformAcknowledgeInput(
      String platformRef, String result, String message) {}
}
