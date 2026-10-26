package com.starcon.platform;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 18.2.1.1「财务软件」的传输模型。
 *
 * <p>蓝图 18-integration-and-automation.md 原文：
 *
 * <blockquote>
 *
 * 职责：对接第三方财务软件（金蝶、用友、SAP、Odoo）：凭证推送、科目映射、主数据同步、余额核对；
 * 导出标准凭证文件（Excel/XML）。 输入：凭证数据；科目映射配置；对账需求。
 * 输出：推送结果；凭证文件；核对差异。 关系：上游 = 12.5.2.1 凭证；下游 = 外部财务软件。
 * <b>业财一体化的常见做法是本系统管业务账、外部软件管法定账</b>，
 * 两边通过凭证文件与科目映射衔接。
 *
 * </blockquote>
 *
 * <p><b>🔴 前缀刻意是 {@code FinanceSoftware} 而不是 {@code Finance}</b>：
 * finance 服务（12 财务核算）已经有一整族 {@code FinanceVoucher*} / {@code FinanceReport*}
 * 类型，而契约被 codegen 合并进<b>同一个扁平命名空间</b> —— 前缀撞车会让读代码的人
 * 以为是一件事，codegen 的同名合并也会让「谁覆盖谁」变成需要逐个审计的问题。
 *
 * <p><b>🔴 本模块一个 {@code double} 都没有</b>：金额、余额、差额一律 {@link BigDecimal}
 * （库里 {@code NUMERIC(20,6)}）；计数一律 {@code int}。本模块**落金额**（凭证借贷合计、
 * 科目余额、核对差额），但**不落任何浮点**。
 *
 * <p><b>🔴 「推送结果」在本仓当前拿不到</b>（外部财务软件适配器未接入，见
 * {@link FinanceSoftwarePushPort}）：{@link FinanceSoftwarePushOutcome} 因此带
 * {@code connected=false / pushed=false} 与一条写明「缺什么、归谁」的 {@code reason}，
 * <b>它不是一个「已经推过去了」的结果</b>。
 */
public final class FinanceSoftwareModels {

  private FinanceSoftwareModels() {}

  // ================================================================ 能力声明

  /**
   * 一项能力（或一项「还没接通」的能力）。
   *
   * @param available 该能力**现在**能不能用（不是「将来会有」）
   * @param evidence 凭什么这么判（端点 / 表 / 规则的具体名字）
   * @param ownerModule 还没接通的能力，**缺在哪个模块**
   */
  public record FinanceSoftwareCapability(
      String moduleId,
      String name,
      boolean available,
      String evidence,
      String ownerModule,
      String note) {}

  /** 一个「还没交付」的下游 / 兄弟能力。 */
  public record FinanceSoftwareGap(
      String moduleId, String name, String service, boolean available, String relation, String note) {}

  /** 能力声明总览。 */
  public record FinanceSoftwareCapabilities(
      List<FinanceSoftwareCapability> capabilities, List<FinanceSoftwareGap> gaps) {}

  /** 概览：几个数。 */
  public record FinanceSoftwareOverview(
      long accounts,
      long activeAccounts,
      long subjectMaps,
      long masterMaps,
      long voucherFiles,
      long reconcileTasks,
      long computedReconcileTasks,
      long reconcileDiffs,
      OffsetDateTime generatedAt) {}

  // ================================================================ 连接档案

  /** 外部财务软件厂商。 */
  public enum FinanceSoftwareVendor {
    /** 金蝶。 */
    KINGDEE("金蝶"),
    /** 用友。 */
    YONYOU("用友"),
    /** SAP。 */
    SAP("SAP"),
    /** Odoo。 */
    ODOO("Odoo");

    private final String label;

    FinanceSoftwareVendor(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}（服务层给一句能直接展示的话）。 */
    public static FinanceSoftwareVendor parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var v : values()) {
        if (v.name().equalsIgnoreCase(raw.trim())) return v;
      }
      return null;
    }
  }

  /** 连接档案一行。 */
  public record FinanceSoftwareAccountRow(
      UUID id,
      String name,
      String vendor,
      String vendorLabel,
      String bookCode,
      String companyName,
      String currency,
      int fiscalStartMonth,
      String status,
      String statusLabel,
      long subjectMapCount,
      long masterMapCount,
      long voucherFileCount,
      String remark,
      @Schema(nullable = true) UUID createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 登记 / 修改一个连接档案。 */
  public record FinanceSoftwareAccountInput(
      String name,
      String vendor,
      String bookCode,
      String companyName,
      String currency,
      Integer fiscalStartMonth,
      String status,
      String remark) {}

  // ================================================================ 科目映射

  /** 方向：留空表示跟随本地科目余额方向。 */
  public enum FinanceSoftwareDirection {
    DEBIT("借方"),
    CREDIT("贷方");

    private final String label;

    FinanceSoftwareDirection(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}。 */
    public static FinanceSoftwareDirection parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var d : values()) {
        if (d.name().equalsIgnoreCase(raw.trim())) return d;
      }
      return null;
    }
  }

  /** 科目映射一行。 */
  public record FinanceSoftwareSubjectMapRow(
      UUID id,
      UUID accountId,
      String accountName,
      String localSubjectCode,
      String localSubjectName,
      String externalSubjectCode,
      String externalSubjectName,
      String taxCode,
      String direction,
      String directionLabel,
      boolean enabled,
      String remark,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 新增或修改一条科目映射。 */
  public record FinanceSoftwareSubjectMapInput(
      UUID accountId,
      String localSubjectCode,
      String localSubjectName,
      String externalSubjectCode,
      String externalSubjectName,
      String taxCode,
      String direction,
      Boolean enabled,
      String remark) {}

  // ================================================================ 主数据映射

  /**
   * 主数据类型。
   *
   * <p>🔴 与 12.5.2.1 凭证行上的**辅助核算类型**对齐 —— 主数据映射的作用就是给
   * 辅助核算项换外部编码，所以类型词表必须与辅助核算类型一致，
   * 否则导出凭证文件时对不上。
   */
  public enum FinanceSoftwareMasterType {
    /** 客户（辅助核算 CUSTOMER）。 */
    CUSTOMER("客户"),
    /** 供应商（辅助核算 SUPPLIER）。 */
    SUPPLIER("供应商"),
    /** 平台 / 店铺（辅助核算 PLATFORM）。 */
    PLATFORM("平台/店铺"),
    /** 资金账户（辅助核算 ACCOUNT）。 */
    ACCOUNT("资金账户"),
    /** 商品。 */
    PRODUCT("商品"),
    /** 部门。 */
    DEPARTMENT("部门");

    private final String label;

    FinanceSoftwareMasterType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}。 */
    public static FinanceSoftwareMasterType parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var t : values()) {
        if (t.name().equalsIgnoreCase(raw.trim())) return t;
      }
      return null;
    }
  }

  /** 主数据映射一行。 */
  public record FinanceSoftwareMasterMapRow(
      UUID id,
      UUID accountId,
      String accountName,
      String masterType,
      String masterTypeLabel,
      String localCode,
      String localName,
      String externalCode,
      String externalName,
      boolean enabled,
      String remark,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 新增或修改一条主数据映射。 */
  public record FinanceSoftwareMasterMapInput(
      UUID accountId,
      String masterType,
      String localCode,
      String localName,
      String externalCode,
      String externalName,
      Boolean enabled,
      String remark) {}

  // ================================================================ 凭证文件

  /** 凭证文件格式。 */
  public enum FinanceSoftwareFileFormat {
    /** 带 UTF-8 BOM 的 CSV（Excel 直接双击能开）。 */
    CSV("CSV"),
    /** SpreadsheetML 2003（XML 工作簿）。 */
    EXCEL("Excel"),
    /** 通用凭证 XML。 */
    XML("XML");

    private final String label;

    FinanceSoftwareFileFormat(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}。 */
    public static FinanceSoftwareFileFormat parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var f : values()) {
        if (f.name().equalsIgnoreCase(raw.trim())) return f;
      }
      return null;
    }
  }

  /** 一次凭证文件导出的留痕。 */
  public record FinanceSoftwareVoucherFileRow(
      UUID id,
      UUID accountId,
      String accountName,
      String fileNo,
      String format,
      String filename,
      String contentSha256,
      int contentBytes,
      long voucherCount,
      long lineCount,
      long unmappedLineCount,
      BigDecimal totalDebit,
      BigDecimal totalCredit,
      String currency,
      LocalDate dateFrom,
      LocalDate dateTo,
      @Schema(nullable = true) UUID createdBy,
      OffsetDateTime createdAt) {}

  /** 凭证文件详情（**含真内容** —— 事后要能原样取回当初导出的那一份）。 */
  public record FinanceSoftwareVoucherFileDetail(
      FinanceSoftwareVoucherFileRow file, String contentType, String content) {}

  /** 导出入参。 */
  public record FinanceSoftwareVoucherExportInput(
      UUID accountId,
      String format,
      LocalDate dateFrom,
      LocalDate dateTo,
      String remark) {}

  /** 导出的凭证文件（**真内容**，不是占位字节）。 */
  public record FinanceSoftwareVoucherExportFile(
      String filename, String contentType, String content, String fileNo, String format) {}

  /**
   * 导出结果。
   *
   * @param fileId 导出记录 id（已落库）
   * @param fileNo 导出单号
   */
  public record FinanceSoftwareVoucherExportResult(
      UUID fileId,
      String fileNo,
      String format,
      String filename,
      String contentType,
      String content,
      String contentSha256,
      int contentBytes,
      long voucherCount,
      long lineCount,
      long unmappedLineCount,
      BigDecimal totalDebit,
      BigDecimal totalCredit,
      String currency,
      LocalDate dateFrom,
      LocalDate dateTo) {}

  // ================================================================ 余额核对

  /** 核对任务状态。 */
  public enum FinanceSoftwareTaskStatus {
    /** 已建任务，还没算。 */
    DRAFT("待计算"),
    /** 已算出差异。 */
    COMPUTED("已计算");

    private final String label;

    FinanceSoftwareTaskStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}。 */
    public static FinanceSoftwareTaskStatus parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw.trim())) return s;
      }
      return null;
    }
  }

  /** 核对差异原因。 */
  public enum FinanceSoftwareReconcileReason {
    /** 两边都有，金额不等。 */
    BALANCE_DIFF("金额不等"),
    /** 本地有、外部没有。 */
    ONLY_LOCAL("仅本地有"),
    /** 外部有、本地没有。 */
    ONLY_EXTERNAL("仅外部有"),
    /** 两边币种不是同一个（不能相减）。 */
    CURRENCY_MISMATCH("币种不一致");

    private final String label;

    FinanceSoftwareReconcileReason(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 核对任务一行。 */
  public record FinanceSoftwareReconcileTaskRow(
      UUID id,
      UUID accountId,
      String accountName,
      String reconcileNo,
      UUID periodFromId,
      UUID periodToId,
      String currency,
      String status,
      String statusLabel,
      long localSubjectCount,
      long externalSubjectCount,
      long matchedCount,
      long differenceCount,
      BigDecimal totalDifference,
      @Schema(nullable = true) OffsetDateTime computedAt,
      String remark,
      @Schema(nullable = true) UUID createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 建一个核对任务。 */
  public record FinanceSoftwareReconcileTaskInput(
      UUID accountId, UUID periodFromId, UUID periodToId, String currency, String remark) {}

  /** 导入一条外部余额（一个外部科目一行）。 */
  public record FinanceSoftwareReconcileBalanceInput(
      String externalSubjectCode,
      String externalSubjectName,
      String currency,
      String direction,
      BigDecimal externalBalance) {}

  /** 外部余额快照一行。 */
  public record FinanceSoftwareReconcileBalanceRow(
      UUID id,
      UUID taskId,
      String externalSubjectCode,
      String externalSubjectName,
      String currency,
      String direction,
      String directionLabel,
      BigDecimal externalBalance,
      OffsetDateTime createdAt) {}

  /** 核对差异一行。 */
  public record FinanceSoftwareReconcileDiffRow(
      UUID id,
      UUID taskId,
      String externalSubjectCode,
      String externalSubjectName,
      String localSubjectCode,
      String localSubjectName,
      String currency,
      BigDecimal externalBalance,
      BigDecimal localBalance,
      BigDecimal difference,
      String reason,
      String reasonLabel,
      String detail,
      OffsetDateTime createdAt) {}

  /** 核对详情：任务 + 导入的外部余额 + 算出来的差异。 */
  public record FinanceSoftwareReconcileDetail(
      FinanceSoftwareReconcileTaskRow task,
      List<FinanceSoftwareReconcileBalanceRow> balances,
      List<FinanceSoftwareReconcileDiffRow> diffs) {}

  /** 批量导入外部余额的入参（一次导入一批科目）。 */
  public record FinanceSoftwareBalanceImportInput(
      List<FinanceSoftwareReconcileBalanceInput> balances) {}

  /** 直连推送的入参（**当前恒 409** —— 外部适配器未接入）。 */
  public record FinanceSoftwarePushInput(UUID accountId, LocalDate dateFrom, LocalDate dateTo) {}

  // ================================================================ 外部推送（未接入）

  /**
   * 直连推送的结果。
   *
   * 🔴 **本仓当前恒为 {@code connected=false / pushed=false}**：外部财务软件适配器未接入。
   * 这个类型存在的意义是让「这一段缺什么、缺在谁身上」变成一条**可查的事实**，
   * 而不是一句写在文档里的说明。
   */
  public record FinanceSoftwarePushOutcome(
      boolean connected,
      boolean pushed,
      String accountId,
      String vendor,
      String externalReference,
      String reason,
      String ownerModule) {}

  // ================================================================ 上游（跨服务只读）

  /** 一个会计期间（来自 12.5.2.1 凭证管理的期间表）。 */
  public record FinanceSoftwarePeriodOption(
      String id, String periodCode, String periodName, String status, String startDate, String endDate) {}
}
