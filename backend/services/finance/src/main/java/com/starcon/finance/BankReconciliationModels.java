package com.starcon.finance;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 12.7.1.2 银行对账的传输模型。
 *
 * <p>蓝图 12-finance.md 原文：
 *
 * <blockquote>
 *
 * 职责：导入银行/支付平台流水与系统记录自动匹配，输出未匹配项，支持人工认领与说明，生成对账报告。 输入：流水文件（导入）；系统收支记录；匹配规则。 输出：匹配结果 + 未匹配清单 + 对账报告。 关系：上游 = 12.7.1.1、12.2；下游 = 12.3.2.1 对账。 <b>未匹配项要能认领到具体单据</b>，否则余额永远平不了——这是财务日常最高频的工作。
 *
 * </blockquote>
 *
 * <p><b>命名。</b>统一用 {@code FinanceBankRecon*} 前缀 —— 与 12.7.1.1 的
 * {@code FinanceFundAccount*}、12.7.2.1 的 {@code FinanceFundAnalysis*} 零重叠。
 *
 * <p><b>本模块的五条口径纪律</b>（每一条都有对应实现，不是口号）：
 *
 * <ol>
 *   <li><b>不允许「金额相同即匹配」（卷 12 硬约束）。</b>匹配规则是**受控词表**，
 *       三种类型都**至少组合两个独立维度**（单号 / 对手方 / 日期窗口）+
 *       方向 + 金额；DB 层用 CHECK 钉死规则类型与日期窗口上限（≤ 31 天）。</li>
 *   <li><b>未匹配项要能认领到具体单据。</b>认领目标是**受控词表 + 单据 id + 单号**，
 *       DB 层校验该单据**真的存在于本租户**（同服务同库 ⇒ 可校验），
 *       并把每次认领/说明**追加**进 {@code finance_bank_recon_claim}（append-only）。</li>
 *   <li><b>银行流水是外部事实 ⇒ append-only。</b>导入后不许改写（DB 触发器 +
 *       收回运行角色权限）；要改就换一个对账批次重新导入。</li>
 *   <li><b>一次对账只有一个币种。</b>币种继承 12.7.1.1 的账户（单账户单币种是硬约束）
 *       ⇒ 全部金额可以相加，**不需要**任何跨币种折算（本模块不折算）。</li>
 *   <li><b>读不到 ≠ 没有。</b>我方账面余额读不到时，报告里的调节后余额与差额**恒为 null**，
 *       并带 {@code bookBalanceAvailable=false} —— 写 0 会被读成「账是平的」。</li>
 * </ol>
 */
public final class BankReconciliationModels {

  private BankReconciliationModels() {}

  /** 金额小数位（与 12.1.x / 12.2.x / 12.7.x 一致）。 */
  public static final int MONEY_SCALE = 4;

  // ================================================================ 枚举

  /** 对账批次的状态机（**只能向前**：DRAFT → IMPORTED → MATCHED → RECONCILED）。 */
  public enum FinanceBankReconStatus {
    /** 已建批次，还没导入流水。 */
    DRAFT("待导入流水"),
    /** 已导入银行流水。 */
    IMPORTED("已导入流水"),
    /** 已跑过自动匹配（匹配结果 + 未匹配清单已产出）。 */
    MATCHED("已匹配"),
    /** 已生成对账报告并确认（**终态**：批次冻结）。 */
    RECONCILED("已对账确认");

    private final String label;

    FinanceBankReconStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 资金方向：**受控词表**（金额恒正，方向单独一列 ⇒ 没有「负数代表支出」的二义性）。 */
  public enum FinanceBankReconDirection {
    /** 流入（银行账户收入）。 */
    IN("收入"),
    /** 流出（银行账户支出）。 */
    OUT("支出");

    private final String label;

    FinanceBankReconDirection(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 匹配规则类型 —— **受控词表**。
   *
   * <p>🔴 三种类型都**至少组合两个独立维度**（单号 / 对手方 / 日期窗口）+ 方向 + 金额：
   * **没有任何一种只看金额**（卷 12 硬约束「不允许金额相同即匹配」）。
   */
  public enum FinanceBankReconRuleType {
    /** 单号精确相等 + 金额相等 + 方向相同 + 日期在窗口内。 */
    REFERENCE_EXACT("单号精确匹配"),
    /** 对手方规范化后相等 + 金额相等 + 方向相同 + 日期在窗口内。 */
    COUNTERPARTY_AMOUNT("对手方与金额匹配"),
    /** 金额相等 + 方向相同 + 日期在窗口内 + 对手方**不矛盾**。 */
    AMOUNT_DATE_WINDOW("金额与日期窗口匹配");

    private final String label;

    FinanceBankReconRuleType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 系统记录的来源类型（12.2 / 12.3 的哪张表）。 */
  public enum FinanceBankReconSourceType {
    /** 12.2.1.1 收款单。 */
    RECEIPT("收款单"),
    /** 12.2.2.1 付款单。 */
    PAYMENT_ORDER("付款单"),
    /** 12.3.1.2 结算明细行。 */
    SETTLEMENT_LINE("结算明细行");

    private final String label;

    FinanceBankReconSourceType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 未匹配项来自哪一侧。 */
  public enum FinanceBankReconSide {
    /** 银行有、我方账上没有（银行已收未入账 / 银行已付未入账）。 */
    BANK("银行侧"),
    /** 我方账上有、银行账单上没有（我方已收银行未收 / 我方已付银行未付）。 */
    SYSTEM("系统侧");

    private final String label;

    FinanceBankReconSide(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 未匹配项的处理状态。 */
  public enum FinanceBankReconUnmatchedStatus {
    /** 待处理（**进入余额调节**）。 */
    OPEN("待处理"),
    /** 已认领到具体单据（**终态**：不再进入余额调节）。 */
    CLAIMED("已认领"),
    /** 已说明并排除（**终态**：不再进入余额调节）。 */
    EXCLUDED("已排除");

    private final String label;

    FinanceBankReconUnmatchedStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 认领记录的动作。 */
  public enum FinanceBankReconClaimAction {
    /** 认领到具体单据。 */
    CLAIM("认领"),
    /** 说明后排除。 */
    EXCLUDE("排除");

    private final String label;

    FinanceBankReconClaimAction(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 认领到的单据类型（**受控词表**）。 */
  public enum FinanceBankReconDocumentType {
    /** 12.2.1.1 收款单。 */
    RECEIPT("收款单"),
    /** 12.2.2.1 付款单。 */
    PAYMENT_ORDER("付款单"),
    /** 12.3.1.1 结算单。 */
    SETTLEMENT("结算单"),
    /** 其它单据（外部凭据，本模块不校验它的存在性 —— 说清它是什么就行）。 */
    OTHER("其它单据");

    private final String label;

    FinanceBankReconDocumentType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 流水导入文件的格式。 */
  public enum FinanceBankReconImportFormat {
    /** 带表头的 CSV（列名见服务层常量）。 */
    CSV("CSV"),
    /** JSON 数组（对象字段名与 CSV 列名一致）。 */
    JSON("JSON");

    private final String label;

    FinanceBankReconImportFormat(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 能力声明

  /** 能力条目：每一维说清「有没有、依据是什么、缺了会怎样」。 */
  public record FinanceBankReconCapability(
      String module, String name, boolean available, String basis, String ownerModule, String detail) {}

  /** 下游缺口：谁没交付、归谁。 */
  public record FinanceBankReconDownstreamGap(
      String module, String name, String ownerService, boolean available, String group, String detail) {}

  /** 12.7.1.2 的能力声明。 */
  public record FinanceBankReconCapabilities(
      List<FinanceBankReconCapability> capabilities,
      List<FinanceBankReconDownstreamGap> downstreamGaps) {}

  // ================================================================ 筛选项

  /** 受控词表里的一项。 */
  public record FinanceBankReconOption(String code, String label) {}

  /**
   * 可对账的资金账户（读自 12.7.1.1 的 {@code finance_fund_account}，**真数据**）。
   *
   * @param currentBalance 当前余额（12.7.1.1 的余额历史推导出的当前值）。
   */
  public record FinanceBankReconAccount(
      UUID id,
      String accountNo,
      String accountName,
      String accountType,
      String accountTypeLabel,
      String currency,
      String legalEntityName,
      BigDecimal currentBalance,
      OffsetDateTime currentBalanceAt) {}

  /** 筛选项下拉。 */
  public record FinanceBankReconOptions(
      List<FinanceBankReconOption> directions,
      List<FinanceBankReconOption> ruleTypes,
      List<FinanceBankReconOption> sourceTypes,
      List<FinanceBankReconOption> unmatchedStatuses,
      List<FinanceBankReconOption> documentTypes,
      List<FinanceBankReconAccount> accounts,
      List<String> currencies,
      String note) {}

  // ================================================================ 对账批次

  /** 一个对账批次。 */
  public record FinanceBankReconSessionRow(
      UUID id,
      String reconNo,
      UUID accountId,
      String accountNo,
      String accountName,
      String currency,
      LocalDate periodStart,
      LocalDate periodEnd,
      /** 银行对账单期初 / 期末余额（来自银行账单）。 */
      BigDecimal statementOpeningBalance,
      BigDecimal statementClosingBalance,
      /** 我方账面期初 / 期末余额（读自 12.7.1.1 的余额历史；**读不到就是 null**）。 */
      @Schema(nullable = true) BigDecimal bookOpeningBalance,
      @Schema(nullable = true) BigDecimal bookClosingBalance,
      String status,
      String statusLabel,
      String sourceSystem,
      int statementLineCount,
      int systemLineCount,
      int matchedCount,
      /** 待处理的银行侧未匹配笔数（**进入余额调节**）。 */
      int unmatchedBankCount,
      /** 待处理的系统侧未匹配笔数（**进入余额调节**）。 */
      int unmatchedSystemCount,
      @Schema(nullable = true) UUID reconciledBy,
      @Schema(nullable = true) OffsetDateTime reconciledAt,
      String note,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 对账批次页（真分页）。 */
  public record FinanceBankReconSessionPage(
      List<FinanceBankReconSessionRow> items, int page, int size, long total) {}

  // ================================================================ 流水行 / 系统记录

  /** 一行银行 / 支付平台流水（**外部事实**：导入后不可改写）。 */
  public record FinanceBankReconStatementLineRow(
      UUID id,
      UUID sessionId,
      OffsetDateTime occurredAt,
      String direction,
      String directionLabel,
      BigDecimal amount,
      String currency,
      String bankReference,
      String externalReference,
      String counterparty,
      String memo,
      String importBatch,
      OffsetDateTime createdAt) {}

  /** 银行流水行页（真分页）。 */
  public record FinanceBankReconStatementLinePage(
      List<FinanceBankReconStatementLineRow> items, int page, int size, long total) {}

  /** 一条系统收支记录的快照（读模型：上游状态变了，重跑匹配时重建）。 */
  public record FinanceBankReconSystemLineRow(
      UUID id,
      UUID sessionId,
      String sourceType,
      String sourceTypeLabel,
      UUID sourceId,
      String sourceNo,
      String sourceLabel,
      OffsetDateTime occurredAt,
      /** 事实精度：DAY（只有日历日）/ SECOND（精确到秒）。 */
      String occurredPrecision,
      String direction,
      String directionLabel,
      BigDecimal amount,
      BigDecimal rawAmount,
      String currency,
      String externalReference,
      String counterparty,
      String memo,
      OffsetDateTime createdAt) {}

  /** 系统记录快照页（真分页）。 */
  public record FinanceBankReconSystemLinePage(
      List<FinanceBankReconSystemLineRow> items, int page, int size, long total) {}

  // ================================================================ 匹配结果

  /** 一条匹配结果（银行流水行 ↔ 系统记录）。 */
  public record FinanceBankReconMatchRow(
      UUID id,
      UUID sessionId,
      UUID statementLineId,
      UUID systemLineId,
      String ruleCode,
      String ruleType,
      String ruleTypeLabel,
      String matchBasis,
      int dayDifference,
      BigDecimal amount,
      String currency,
      String direction,
      OffsetDateTime occurredAt,
      String externalReference,
      String counterparty,
      UUID matchedBy,
      OffsetDateTime matchedAt) {}

  /** 匹配结果页（真分页）。 */
  public record FinanceBankReconMatchPage(
      List<FinanceBankReconMatchRow> items, int page, int size, long total) {}

  // ================================================================ 未匹配清单

  /** 一条未匹配项。 */
  public record FinanceBankReconUnmatchedRow(
      UUID id,
      UUID sessionId,
      String side,
      String sideLabel,
      @Schema(nullable = true) UUID statementLineId,
      @Schema(nullable = true) UUID systemLineId,
      OffsetDateTime occurredAt,
      String direction,
      String directionLabel,
      BigDecimal amount,
      String currency,
      String counterparty,
      String externalReference,
      String memo,
      String status,
      String statusLabel,
      /** 认领到的单据类型 / id / 单号（**认领到具体单据**）。 */
      @Schema(nullable = true) String claimedDocumentType,
      @Schema(nullable = true) String claimedDocumentTypeLabel,
      @Schema(nullable = true) UUID claimedDocumentId,
      @Schema(nullable = true) String claimedDocumentNo,
      @Schema(nullable = true) UUID claimedBy,
      @Schema(nullable = true) OffsetDateTime claimedAt,
      @Schema(nullable = true) String claimReason,
      @Schema(nullable = true) String excludeReason,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 未匹配清单页（真分页）。 */
  public record FinanceBankReconUnmatchedPage(
      List<FinanceBankReconUnmatchedRow> items, int page, int size, long total) {}

  /** 一条认领 / 说明记录（**append-only**）。 */
  public record FinanceBankReconClaimRow(
      UUID id,
      UUID sessionId,
      UUID unmatchedId,
      String action,
      String actionLabel,
      @Schema(nullable = true) String claimedDocumentType,
      @Schema(nullable = true) String claimedDocumentTypeLabel,
      @Schema(nullable = true) UUID claimedDocumentId,
      @Schema(nullable = true) String claimedDocumentNo,
      String reason,
      UUID claimedBy,
      OffsetDateTime claimedAt,
      OffsetDateTime createdAt) {}

  /** 认领记录页（真分页）。 */
  public record FinanceBankReconClaimPage(
      List<FinanceBankReconClaimRow> items, int page, int size, long total) {}

  // ================================================================ 匹配规则

  /** 一条匹配规则。 */
  public record FinanceBankReconRuleRow(
      UUID id,
      String ruleCode,
      String ruleName,
      String ruleType,
      String ruleTypeLabel,
      int priority,
      boolean enabled,
      int dateWindowDays,
      BigDecimal amountTolerance,
      boolean counterpartyRequired,
      boolean builtin,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 匹配规则页（真分页）。 */
  public record FinanceBankReconRulePage(
      List<FinanceBankReconRuleRow> items, int page, int size, long total) {}

  // ================================================================ 对账报告

  /**
   * 对账报告（生成那一刻的快照）。
   *
   * @param unmatchedBankCount 待处理的银行侧未匹配笔数（**进入余额调节**）。
   * @param unmatchedSystemCount 待处理的系统侧未匹配笔数（**进入余额调节**）。
   * @param claimedCount 已认领的笔数（已解决，不再进入余额调节）。
   * @param excludedCount 已排除的笔数（已解决，不再进入余额调节）。
   * @param bookBalanceAvailable 我方账面余额**读到了没有**（读不到 ⇒ 差额恒为 null）。
   * @param adjustedBalance 调节后余额（账面余额读不到时恒为 null）。
   * @param difference 差额（调节后余额 − 我方账面余额；读不到时恒为 null）。
   */
  public record FinanceBankReconReportRow(
      UUID id,
      UUID sessionId,
      String reconNo,
      String currency,
      LocalDate periodStart,
      LocalDate periodEnd,
      int statementLineCount,
      int systemLineCount,
      int matchedCount,
      int unmatchedBankCount,
      int unmatchedSystemCount,
      int claimedCount,
      int excludedCount,
      BigDecimal matchedAmount,
      BigDecimal statementInAmount,
      BigDecimal statementOutAmount,
      BigDecimal systemInAmount,
      BigDecimal systemOutAmount,
      BigDecimal unmatchedBankInAmount,
      BigDecimal unmatchedBankOutAmount,
      BigDecimal unmatchedSystemInAmount,
      BigDecimal unmatchedSystemOutAmount,
      BigDecimal statementOpeningBalance,
      BigDecimal statementClosingBalance,
      @Schema(nullable = true) BigDecimal bookClosingBalance,
      boolean bookBalanceAvailable,
      @Schema(nullable = true) BigDecimal adjustedBalance,
      @Schema(nullable = true) BigDecimal difference,
      UUID generatedBy,
      OffsetDateTime generatedAt) {}

  // ================================================================ 导入 / 匹配结果

  /**
   * 一次流水导入的结果。
   *
   * @param importedCount 真正入账的行数。
   * @param skippedCount 被跳过的行数（**不是丢弃**：每一条都进 {@code warnings}）。
   * @param warnings 逐条告警（行号 + 原因），让人知道**哪一行没进来、为什么**。
   */
  public record FinanceBankReconImportResult(
      UUID sessionId,
      String reconNo,
      int parsedCount,
      int importedCount,
      int skippedCount,
      int statementLineCount,
      List<String> warnings) {}

  /**
   * 一次自动匹配的结果。
   *
   * @param rulesApplied 实际参与本次匹配���规则（按优先级顺序）。
   */
  public record FinanceBankReconMatchResult(
      UUID sessionId,
      String reconNo,
      int statementLineCount,
      int systemLineCount,
      int matchedCount,
      int unmatchedBankCount,
      int unmatchedSystemCount,
      List<String> rulesApplied) {}

  // ================================================================ 事件流
  /** 一条对账事件（**append-only**）。 */
  public record FinanceBankReconEventRow(
      UUID id,
      UUID sessionId,
      String eventType,
      String summary,
      String payload,
      UUID operatorUserId,
      OffsetDateTime occurredAt,
      OffsetDateTime createdAt) {}

  /** 事件流页（真分页）。 */
  public record FinanceBankReconEventPage(
      List<FinanceBankReconEventRow> items, int page, int size, long total) {}

  // ================================================================ 输入

  /** 建对账批次。 */
  public record FinanceBankReconSessionCreateInput(
      @NotNull(message = "必须选择对账账户")
          @Schema(description = "12.7.1.1 的资金账户 id", requiredMode = Schema.RequiredMode.REQUIRED)
          UUID accountId,
      @NotNull(message = "必须填写对账期间起始日")
          @Schema(description = "期间起始日", requiredMode = Schema.RequiredMode.REQUIRED)
          LocalDate periodStart,
      @NotNull(message = "必须填写对账期间截止日")
          @Schema(description = "期间截止日", requiredMode = Schema.RequiredMode.REQUIRED)
          LocalDate periodEnd,
      @Schema(nullable = true, description = "银行对账单期初余额")
          BigDecimal statementOpeningBalance,
      @Schema(nullable = true, description = "银行对账单期末余额")
          BigDecimal statementClosingBalance,
      @Schema(nullable = true, description = "流水来源系统（bank / paypal / stripe …）")
          String sourceSystem,
      @Schema(nullable = true, description = "备注") String note) {}

  /**
   * 导入银行 / 支付平台流水。
   *
   * @param content 流水文件原文（CSV 文本或 JSON 数组）。
   * @param statementOpeningBalance 本次导入带来的对账单期初余额（可空 = 不改）。
   * @param statementClosingBalance 本次导入带来的对账单期末余额（可空 = 不改）。
   */
  public record FinanceBankReconImportInput(
      @NotNull(message = "必须选择文件格式")
          @Schema(description = "CSV 或 JSON", requiredMode = Schema.RequiredMode.REQUIRED)
          FinanceBankReconImportFormat format,
      @NotBlank(message = "必须提供流水文件内容")
          @Size(max = 2_000_000, message = "单次导入的文件内容过大")
          @Schema(description = "流水文件原文", requiredMode = Schema.RequiredMode.REQUIRED)
          String content,
      @Schema(nullable = true, description = "对账单期初余额（可空 = 不改）")
          BigDecimal statementOpeningBalance,
      @Schema(nullable = true, description = "对账单期末余额（可空 = 不改）")
          BigDecimal statementClosingBalance) {}

  /** 人工认领到具体单据。 */
  public record FinanceBankReconClaimInput(
      @NotNull(message = "必须选择认领到的单据类型")
          @Schema(description = "收款单 / 付款单 / 结算单 / 其它", requiredMode = Schema.RequiredMode.REQUIRED)
          FinanceBankReconDocumentType documentType,
      @Schema(nullable = true, description = "单据 id（收款单 / 付款单 / 结算单必填）")
          UUID documentId,
      @Schema(nullable = true, description = "单据号（认领到具体单据时必填）") String documentNo,
      @NotBlank(message = "必须写认领理由")
          @Size(max = 500, message = "认领理由过长")
          @Schema(description = "认领理由", requiredMode = Schema.RequiredMode.REQUIRED)
          String reason) {}

  /** 排除一条未匹配项（必须写理由）。 */
  public record FinanceBankReconExcludeInput(
      @NotBlank(message = "必须写排除理由")
          @Size(max = 500, message = "排除理由过长")
          @Schema(description = "排除理由", requiredMode = Schema.RequiredMode.REQUIRED)
          String reason) {}

  /** 匹配规则（新建 / 修改共用；{@code ruleCode} 建后不可改）。 */
  public record FinanceBankReconRuleInput(
      @NotBlank(message = "必须填写规则编码")
          @Pattern(regexp = "^[A-Z0-9_-]{1,48}$", message = "规则编码只允许大写字母、数字、下划线与连字符")
          @Schema(description = "规则编码（租户内唯一）", requiredMode = Schema.RequiredMode.REQUIRED)
          String ruleCode,
      @NotBlank(message = "必须填写规则名称")
          @Size(max = 120, message = "规则名称过长")
          @Schema(description = "规则名称", requiredMode = Schema.RequiredMode.REQUIRED)
          String ruleName,
      @NotNull(message = "必须选择规则类型")
          @Schema(description = "受控词表：单号 / 对手方 / 金额日期窗口", requiredMode = Schema.RequiredMode.REQUIRED)
          FinanceBankReconRuleType ruleType,
      @Schema(nullable = true, description = "优先级（数字小的先试）") Integer priority,
      @Schema(nullable = true, description = "是否启用") Boolean enabled,
      @Schema(nullable = true, description = "日期窗口（天，1–31）") Integer dateWindowDays,
      @Schema(nullable = true, description = "金额容差") BigDecimal amountTolerance,
      @Schema(nullable = true, description = "对手方是否必须完全一致")
          Boolean counterpartyRequired,
      @Schema(nullable = true, description = "备注") String note) {}
}
