package com.starcon.finance;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 12.3.2.1 对账任务的传输模型。
 *
 * <p>蓝图 12-finance.md 原文：
 *
 * <blockquote>
 *
 * 职责：发起与执行对账任务：选择对账范围（店铺 + 周期 + 账户）、运行自动匹配、输出匹配结果与差异。 输入：对账范围；我方账数据（结算、应收、应付、银行流水）；平台账单。 输出：匹配结果（一对一、一对多、多对一）+ 差异清单。 关系：上游 = 12.3.1.1、12.7.1.2；下游 = 12.3.2.2。 <b>不允许「金额相同即匹配」</b>——金额相同只是候选，必须结合订单号、日期、类型。
 *
 * </blockquote>
 *
 * <p><b>命名。</b>统一用 {@code FinanceReconTask*} 前缀 —— 与 12.7.1.2 的
 * {@code FinanceBankRecon*}、12.3.1.1 的 {@code FinanceSettlement*}、12.1.2.3 的
 * {@code FinanceSupplierReconciliation*} 零重叠。
 *
 * <p><b>本模块的五条口径纪律</b>（每一条都有对应实现，不是口号）：
 *
 * <ol>
 *   <li><b>不允许「金额相同即匹配」（卷 12 硬约束）。</b>匹配必须**同时**满足
 *       订单号 / 日期 / 类型中的**至少两个独立维度**；DB 层用 CHECK 钉死
 *       （{@code matched_dimensions} 至少含两个 {REFERENCE, DATE, TYPE}）。</li>
 *   <li><b>匹配结果带上匹配依据。</b>每一行都记着「比对了哪几个维度」（机器可读）
 *       + 人话说明（{@code matchBasis}）⇒ 页面能说清「为什么这两行配成一对」。</li>
 *   <li><b>一对多 / 多对一落成多行。</b>一行 = 一个 (平台账单行, 我方账行) 对；
 *       同组共享 {@code groupKey} / {@code groupNo} / {@code shape}
 *       ⇒ 多张单据对一张是**多行**，不是把多张单据揉成一条。</li>
 *   <li><b>跨币种绝不相加。</b>账户单一币种 ⇒ 差异清单**按币种分组**；
 *       汇率不可得就如实报「汇率不可得」（差额恒为 {@code null}），**不按 1:1 兜底**。</li>
 *   <li><b>执行结果是一次性事实。</b>任务推进到 MATCHED 之后范围冻结、
 *       匹配结果 append-only ⇒ <b>重跑 = 新建一个任务</b>，不覆盖历史。</li>
 * </ol>
 */
public final class ReconciliationTaskModels {

  private ReconciliationTaskModels() {}

  /** 金额小数位（与 12.1.x / 12.2.x / 12.7.x 一致）。 */
  public static final int MONEY_SCALE = 4;

  // ================================================================ 枚举

  /** 对账任务的状态机（**只能向前**：DRAFT → MATCHED）。 */
  public enum FinanceReconTaskStatus {
    /** 已建任务，还没跑匹配（范围还可以改）。 */
    DRAFT("待执行匹配"),
    /** 已完成匹配（**终态**：本模块不再改写结果）。 */
    MATCHED("已匹配");

    private final String label;

    FinanceReconTaskStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 我方账来源 —— **受控词表**。
   *
   * <p>蓝图输入句「我方账数据（结算、应收、应付、银行流水）」里的**我方账**那一侧：
   *
   * <ul>
   *   <li>{@code RECEIVABLE} —— 12.1.1.1 应收明细行；</li>
   *   <li>{@code PAYMENT_ORDER} —— 12.2.2.1 付款单明细行；</li>
   *   <li>{@code BANK_STATEMENT} —— 12.7.1.2 银行/支付平台流水行（**经授权只读 API**）。</li>
   * </ul>
   *
   * <p>⚠️ 「结算」不在这个词表里：12.3.1.1 的结算单 / 12.3.1.2 的结算明细行就是
   * **平台账单**（平台结算报告与平台交易报告的落地），在本模块里属于**平台侧**，
   * 拿它当「我方账」会让同一份平台文件的两份投影互相对撞（假匹配 + 假差异）。
   */
  public enum FinanceReconBookSource {
    /** 12.1.1.1 应收明细行。 */
    RECEIVABLE("应收明细"),
    /** 12.2.2.1 付款单明细行。 */
    PAYMENT_ORDER("付款单明细"),
    /** 12.7.1.2 银行/支付平台流水行（经授权只读 API）。 */
    BANK_STATEMENT("银行流水");

    private final String label;

    FinanceReconBookSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 资金方向：**受控词表**（金额恒正，方向单独一列 ⇒ 没有「负数代表支出」的二义性）。 */
  public enum FinanceReconDirection {
    /** 流入（收入）。 */
    IN("收入"),
    /** 流出（支出）。 */
    OUT("支出");

    private final String label;

    FinanceReconDirection(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 匹配形态 —— **受控词表**。
   *
   * <p>蓝图输出句「匹配结果（一对一、一对多、多对一）」在这里是三个**明确区分**的值，
   * 外加 MANY_TO_MANY（双方都拆）兜住真实世界里的第四种形态。
   */
  public enum FinanceReconMatchShape {
    /** 1 平台账单行 ⇄ 1 我方账行。 */
    ONE_TO_ONE("一对一"),
    /** 1 平台账单行 ⇄ N 我方账行（平台一笔、我方拆成几笔）。 */
    ONE_TO_MANY("一对多"),
    /** N 平台账单行 ⇄ 1 我方账行（我方一笔、平台拆成几笔）。 */
    MANY_TO_ONE("多对一"),
    /** N 平台账单行 ⇄ N 我方账行（双方都拆）。 */
    MANY_TO_MANY("多对多");

    private final String label;

    FinanceReconMatchShape(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 差异类型 —— **受控词表**。 */
  public enum FinanceReconDifferenceType {
    /** 平台账单有、我方账没有（平台多记）。 */
    EXTERNAL_ONLY("平台账单多出"),
    /** 我方账有、平台账单没有（我方多记）。 */
    BOOK_ONLY("我方账多出"),
    /** 两边按维度配上了，但组内合计不等。 */
    AMOUNT_MISMATCH("金额对不齐"),
    /** 币种不一致 ⇒ **汇率不可得**，差额恒为 null（不按 1:1 兜底）。 */
    CURRENCY_MISMATCH("币种不一致"),
    /** 只对上 1 个维度 ⇒ **不足以构成匹配**，落成差异并说明差在哪。 */
    DIMENSION_MISMATCH("维度对不上");

    private final String label;

    FinanceReconDifferenceType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 能力声明

  /** 能力声明的一条。 */
  public record FinanceReconCapability(
      String moduleId,
      String name,
      boolean delivered,
      String source,
      String ownerModule,
      String note) {}

  /** 下游 / 上游缺口的一条。 */
  public record FinanceReconGap(String moduleId, String name, String domain, boolean delivered, String owner, String note) {}

  /** 能力声明（页面上固定显示在顶部）。 */
  public record FinanceReconCapabilities(
      List<FinanceReconCapability> capabilities, List<FinanceReconGap> gaps) {}

  // ================================================================ 筛选项

  /** 通用下拉项。 */
  public record FinanceReconOption(String value, String label) {}

  /** 可选的资金账户（12.7.1.1 的真实账户，不是常量）。 */
  public record FinanceReconAccount(
      UUID id,
      String accountNo,
      String accountName,
      String accountType,
      String currency,
      String legalEntityName) {}

  /** 筛选项。 */
  public record FinanceReconOptions(
      List<FinanceReconOption> statuses,
      List<FinanceReconOption> bookSources,
      List<FinanceReconOption> shapes,
      List<FinanceReconOption> differenceTypes,
      List<FinanceReconOption> directions,
      List<FinanceReconAccount> accounts,
      List<String> currencies,
      String note) {}

  // ================================================================ 任务

  /** 一行对账任务。 */
  public record FinanceReconTaskRow(
      UUID id,
      String taskNo,
      UUID accountId,
      String accountNo,
      String accountName,
      String currency,
      String legalEntityName,
      /**
       * 店铺 id。
       *
       * <p>⚠️ <b>可空</b>：建任务时允许不指定店铺（= 该账户下全部授权店铺）⇒ DB 列可空。
       * 这里必须标 {@code nullable = true}，否则生成的契约说「非空」而实际可能给 {@code null}
       * ⇒ 前端把它当字符串用就会在页面上显示出 {@code null}。
       */
      @Schema(nullable = true) UUID shopId,
      String shopName,
      String platformCode,
      LocalDate periodStart,
      LocalDate periodEnd,
      List<String> bookSources,
      int dateWindowDays,
      BigDecimal amountTolerance,
      String status,
      String statusLabel,
      int externalLineCount,
      int matchedGroupCount,
      int matchedLineCount,
      int differenceCount,
      @Schema(nullable = true) OffsetDateTime matchedAt,
      String note,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 对账任务页（真分页）。 */
  public record FinanceReconTaskPage(List<FinanceReconTaskRow> items, int page, int size, long total) {}

  /** 建任务入参。 */
  public record FinanceReconTaskCreateInput(
      @NotNull UUID accountId,
      @Schema(nullable = true) UUID shopId,
      @NotNull LocalDate periodStart,
      @NotNull LocalDate periodEnd,
      @NotEmpty List<String> bookSources,
      @Min(1) @Max(31) Integer dateWindowDays,
      @DecimalMin("0") BigDecimal amountTolerance,
      @Schema(nullable = true) String note) {}

  /** 改范围入参（**只限 DRAFT 态**）。 */
  public record FinanceReconTaskUpdateInput(
      @NotNull UUID accountId,
      @Schema(nullable = true) UUID shopId,
      @NotNull LocalDate periodStart,
      @NotNull LocalDate periodEnd,
      @NotEmpty List<String> bookSources,
      @Min(1) @Max(31) Integer dateWindowDays,
      @DecimalMin("0") BigDecimal amountTolerance,
      @Schema(nullable = true) String note) {}

  // ================================================================ 匹配结果

  /** 一条匹配结果（一行 = 一个 (平台账单行, 我方账行) 对）。 */
  public record FinanceReconMatchRow(
      UUID id,
      UUID taskId,
      UUID groupKey,
      int groupNo,
      String shape,
      String shapeLabel,
      String bookSource,
      String bookSourceLabel,
      String externalKind,
      String externalKindLabel,
      UUID externalLineId,
      String externalNo,
      String externalReference,
      String externalType,
      @Schema(nullable = true) LocalDate externalOccurredOn,
      BigDecimal externalAmount,
      UUID bookLineId,
      String bookNo,
      String bookReference,
      String bookType,
      @Schema(nullable = true) LocalDate bookOccurredOn,
      BigDecimal bookAmount,
      String currency,
      String direction,
      BigDecimal groupExternalAmount,
      BigDecimal groupBookAmount,
      BigDecimal groupAmountDifference,
      List<String> matchedDimensions,
      int dimensionCount,
      String matchBasis,
      int dayDifference,
      OffsetDateTime matchedAt) {}

  /** 匹配结果页（真分页）。 */
  public record FinanceReconMatchPage(List<FinanceReconMatchRow> items, int page, int size, long total) {}

  // ================================================================ 差异清单

  /** 一条差异。 */
  public record FinanceReconDifferenceRow(
      UUID id,
      UUID taskId,
      String differenceType,
      String differenceTypeLabel,
      String side,
      String sideLabel,
      @Schema(nullable = true) String bookSource,
      @Schema(nullable = true) UUID groupKey,
      @Schema(nullable = true) String externalKind,
      @Schema(nullable = true) UUID externalLineId,
      @Schema(nullable = true) UUID bookLineId,
      @Schema(nullable = true) LocalDate occurredOn,
      String reference,
      String transactionType,
      /** 平台账单侧金额（可空）。 */
      @Schema(nullable = true) BigDecimal externalAmount,
      /** 我方账侧金额（可空）。 */
      @Schema(nullable = true) BigDecimal bookAmount,
      String currency,
      String direction,
      /** 差额（我方账合计 − 平台账单合计）。**跨币种时为 null**（汇率不可得）。 */
      @Schema(nullable = true) BigDecimal differenceAmount,
      boolean exchangeRateAvailable,
      String attribution,
      String reason,
      String status,
      String statusLabel) {}

  /** 差异清单页（真分页）。 */
  public record FinanceReconDifferencePage(
      List<FinanceReconDifferenceRow> items, int page, int size, long total) {}

  // ================================================================ 汇总（按币种分组）

  /**
   * 一个币种的汇总。
   *
   * <p>🔴 **一次对账只呈现一个币种的合计**（账户单一币种）。跨币种的行
   * **永远不并进同一合计** —— 那是账务事故的制造机。
   */
  public record FinanceReconCurrencySummary(
      String currency,
      boolean exchangeRateAvailable,
      BigDecimal externalAmount,
      BigDecimal bookAmount,
      @Schema(nullable = true) BigDecimal differenceAmount,
      long matchedGroupCount,
      long differenceCount,
      String note) {}

  /** 任务的执行结果摘要。 */
  public record FinanceReconTaskSummary(
      int externalLineCount,
      int matchedGroupCount,
      int matchedLineCount,
      int differenceCount,
      long oneToOneCount,
      long oneToManyCount,
      long manyToOneCount,
      long manyToManyCount,
      List<FinanceReconCurrencySummary> currencies,
      List<FinanceReconDifferenceRow> topDifferences,
      String note) {}

  // ================================================================ 执行结果 / 事件

  /** 跑匹配的返回（真计数 + 真分组形态分布）。 */
  public record FinanceReconMatchRunResult(
      UUID taskId,
      String taskNo,
      int externalLineCount,
      int matchedGroupCount,
      int matchedLineCount,
      int differenceCount,
      long oneToOneCount,
      long oneToManyCount,
      long manyToOneCount,
      long manyToManyCount,
      int bookLineCount,
      long differenceLineCount,
      String matchedAt,
      String note) {}

  /** 一条事件（append-only 举证）。 */
  public record FinanceReconEventRow(
      UUID id,
      UUID taskId,
      String eventType,
      String eventTypeLabel,
      String fromStatus,
      String toStatus,
      String summary,
      UUID actorId,
      OffsetDateTime occurredAt) {}

  /** 事件流页（真分页）。 */
  public record FinanceReconEventPage(List<FinanceReconEventRow> items, int page, int size, long total) {}

  /** 任务详情（任务 + 汇总）。 */
  public record FinanceReconTaskDetail(FinanceReconTaskRow task, FinanceReconTaskSummary summary) {}
}
