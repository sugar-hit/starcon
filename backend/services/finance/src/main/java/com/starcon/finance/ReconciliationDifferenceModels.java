package com.starcon.finance;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 12.3.2.2 差异处理的传输模型。
 *
 * <p>蓝图 12-finance.md 原文：
 *
 * <blockquote>
 *
 * 职责：处理对账差异：分类（我方多记/对方多记/时间差/汇率差/手续费遗漏）、指定责任人、人工判定与调整、闭环跟踪。 输入：差异明细；处理结论；调整凭证或说明。 输出：差异处理记录 + 调整分录 + 闭环状态。 关系：上游 = 12.3.2.1；下游 = 12.5.2 凭证、1.3 预警。<b>差异分类是改进闭环的输入</b>：某类差异反复出现说明上游流程有系统性问题。
 *
 * </blockquote>
 *
 * <p><b>命名。</b>统一用 {@code ReconciliationDiff*} 前缀 —— 与 12.3.2.1 的
 * {@code FinanceRecon*}、12.7.1.2 的 {@code FinanceBankRecon*}、
 * 12.1.2.3 的 {@code FinanceSupplierReconciliation*} 零重叠（契约是 codegen 产物，
 * 类型名重了会撞车）。
 *
 * <p><b>本模块的五条口径纪律</b>（每一条都有对应实现，不是口号）：
 *
 * <ol>
 *   <li><b>差异是执行时的事实，处理记录另起一张表。</b>上游
 *       {@code finance_reconciliation_difference} 的触发器只放行 {@code status} 向前推进，
 *       其余字段（类型 / 侧 / 两侧金额 / 币种 / 差额 / 归因 / 原因）**冻结**
 *       ⇒ 分类、责任人、结论、调整分录全部落在**本模块自己的三张表**里，
 *       上游只被推进 {@code status}，**零 ALTER**。</li>
 *   <li><b>处理状态与上游差异状态同相。</b>同一个词表（OPEN / CLAIMED / ADJUSTED /
 *       WRITTEN_OFF），由 DB 触发器逐次核对 ⇒ 直连 SQL 也写不出「处理记录说已核销、
 *       上游差异还挂在待处理」。</li>
 *   <li><b>闭环必须有责任人、必须有分类、必须有结论。</b>表级 CHECK 钉死
 *       ⇒ 没有主人的差异进不了流程，没有理由的分类写不进去，没有结论的闭环开不了。</li>
 *   <li><b>调整凭证或说明，至少有一个。</b>蓝图输入句写的是「调整凭证<b>或</b>说明」
 *       ⇒ 表级 CHECK：两者都没有 = 这条分录什么都不是。</li>
 *   <li><b>跨币种绝不相加。</b>每条调整分录<b>自带币种</b>，一切汇总<b>按币种分组</b>
 *       ⇒ 结构上不存在「把 USD 和 EUR 加起来」这一格；上游跨币种差异的差额恒为
 *       {@code null}（汇率不可得，<b>不按 1:1 兜底</b>）。</li>
 * </ol>
 */
public final class ReconciliationDifferenceModels {

  private ReconciliationDifferenceModels() {}

  /** 金额小数位（与 12.1.x / 12.2.x / 12.7.x 一致）。 */
  public static final int MONEY_SCALE = 4;

  // ================================================================ 枚举

  /**
   * 差异分类 —— **受控词表**（蓝图点名的五类）。
   *
   * <p>⚠️ 这是**归因**，不是上游的差异<b>类型</b>：上游的 {@code difference_type}
   * 说的是「差在哪儿」（平台多出 / 我方多出 / 金额对不齐 / 币种不一致 / 维度对不上），
   * 本词表说的是「<b>为什么</b>差」（谁多记了 / 只是时间差 / 汇率差 / 手续费漏记）
   * —— 后者才是「改进闭环的输入」：某类反复出现说明上游流程有系统性问题。
   */
  public enum ReconciliationDiffCategory {
    /** 我方多记（我方账多了一笔，平台账单没有）。 */
    BOOK_OVER_RECORDED("我方多记"),
    /** 对方多记（平台账单多了一笔，我方账没有）。 */
    COUNTERPARTY_OVER_RECORDED("对方多记"),
    /** 时间差（两笔其实是一笔，只是跨了结算周期）。 */
    TIMING_DIFFERENCE("时间差"),
    /** 汇率差（折算口径不同造成的差额）。 */
    FX_DIFFERENCE("汇率差"),
    /** 手续费遗漏（平台扣了手续费，我方账没记）。 */
    FEE_OMISSION("手续费遗漏");

    private final String label;

    ReconciliationDiffCategory(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 差异处理状态 —— **受控词表**（与上游 {@code finance_reconciliation_difference.status}
   * <b>逐字同词表、同相位</b>）。
   */
  public enum ReconciliationDiffStatus {
    /** 待处理（还没人接手）。 */
    OPEN("待处理"),
    /** 已认领并指定责任人。 */
    CLAIMED("已认领"),
    /** 已调整（终态）。 */
    ADJUSTED("已调整"),
    /** 已核销（终态）。 */
    WRITTEN_OFF("已核销");

    private final String label;

    ReconciliationDiffStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 资金方向 —— **受控词表**（金额恒正，方向单独一列）。 */
  public enum ReconciliationDiffDirection {
    /** 流入（收入）。 */
    IN("收入"),
    /** 流出（支出）。 */
    OUT("支出");

    private final String label;

    ReconciliationDiffDirection(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 能力声明

  /** 能力声明的一条。 */
  public record ReconciliationDiffCapability(
      String moduleId,
      String name,
      boolean delivered,
      String source,
      String ownerModule,
      String note) {}

  /** 下游 / 上游缺口的一条。 */
  public record ReconciliationDiffGap(
      String moduleId, String name, String domain, boolean delivered, String owner, String note) {}

  /** 能力声明（页面上固定显示在顶部）。 */
  public record ReconciliationDiffCapabilities(
      List<ReconciliationDiffCapability> capabilities, List<ReconciliationDiffGap> gaps) {}

  // ================================================================ 筛选项

  /** 通用下拉项。 */
  public record ReconciliationDiffOption(String value, String label) {}

  /** 可选的对账任务（**真实存在且已产出差异**的任务，不是常量）。 */
  public record ReconciliationDiffTask(
      UUID id, String taskNo, String accountNo, String currency, String periodStart, String periodEnd) {}

  /** 筛选项。 */
  public record ReconciliationDiffOptions(
      List<ReconciliationDiffOption> statuses,
      List<ReconciliationDiffOption> categories,
      List<ReconciliationDiffOption> differenceTypes,
      List<ReconciliationDiffOption> directions,
      List<ReconciliationDiffTask> tasks,
      List<String> currencies,
      List<UUID> assignees,
      String note) {}

  // ================================================================ 差异处理队列

  /**
   * 一条差异（队列里的一行）。
   *
   * <p>🔴 {@code differenceAmount} 是<b>上游原样读出</b>的差额：跨币种时恒为
   * {@code null}（汇率不可得，<b>不按 1:1 兜底</b>）—— 页面显示「—」而**不是 0**。
   */
  public record ReconciliationDiffRow(
      UUID differenceId,
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
      /** 上游差额（我方账合计 − 平台账单合计）。**跨币种时为 null**。 */
      @Schema(nullable = true) BigDecimal differenceAmount,
      boolean exchangeRateAvailable,
      String attribution,
      String reason,
      String status,
      String statusLabel,
      /** 处理记录 id（还没人碰过这条差异时为 null）。 */
      @Schema(nullable = true) UUID handlingId,
      /** 归因分类（还没分类时为 null）。 */
      @Schema(nullable = true) String category,
      @Schema(nullable = true) String categoryLabel,
      /** 归因理由（还没分类时为 null）。 */
      @Schema(nullable = true) String categoryReason,
      /** 责任人（还没指定时为 null）。 */
      @Schema(nullable = true) UUID assigneeId,
      @Schema(nullable = true) OffsetDateTime claimedAt,
      /** 闭环结论（还没闭环时为空串）。 */
      String conclusion,
      @Schema(nullable = true) OffsetDateTime closedAt,
      /** 已登记的调整分录条数（**金额不进这一行** —— 分录可能跨币种，行上相加会算错）。 */
      int adjustmentCount) {}

  /**
   * 一个币种的汇总。
   *
   * <p>🔴 <b>按币种分组，绝不跨币种合计。</b>上游差额与调整金额只在<b>同一币种内</b>相加。
   */
  public record ReconciliationDiffCurrencySummary(
      String currency,
      long openCount,
      long claimedCount,
      long adjustedCount,
      long writtenOffCount,
      long totalCount,
      /** 上游差额合计（同币种；跨币种差异的差额是 null，不进合计）。 */
      @Schema(nullable = true) BigDecimal differenceAmount,
      /** 调整分录合计（同币种）。 */
      @Schema(nullable = true) BigDecimal adjustedAmount,
      long adjustmentCount,
      /** 该币种里是否存在「汇率不可得」的差异（有 ⇒ 该币种不做任何折算）。 */
      boolean exchangeRateAvailable,
      String note) {}

  /**
   * 分类分布 —— **改进闭环的输入**。
   *
   * <p>蓝图原话：「差异分类是改进闭环的输入：某类差异反复出现说明上游流程有系统性问题。」
   */
  public record ReconciliationDiffCategoryStat(
      String category,
      String categoryLabel,
      String currency,
      long count,
      @Schema(nullable = true) BigDecimal differenceAmount,
      @Schema(nullable = true) BigDecimal adjustedAmount) {}

  /** 责任人工作量（**只计数不记金额** ⇒ 不涉及币种相加）。 */
  public record ReconciliationDiffAssigneeStat(
      @Schema(nullable = true) UUID assigneeId, long claimedCount, long adjustedCount, long writtenOffCount) {}

  /** 队列页（真分页 + 真筛选 + 真汇总）。 */
  public record ReconciliationDiffQueuePage(
      List<ReconciliationDiffRow> items,
      int page,
      int size,
      long total,
      List<ReconciliationDiffCurrencySummary> currencies,
      List<ReconciliationDiffCategoryStat> categories,
      List<ReconciliationDiffAssigneeStat> assignees,
      String note) {}

  // ================================================================ 处理记录

  /** 差异处理记录（分类 + 责任人 + 闭环状态）。 */
  public record ReconciliationDiffHandling(
      UUID id,
      UUID differenceId,
      UUID taskId,
      String status,
      String statusLabel,
      @Schema(nullable = true) String category,
      @Schema(nullable = true) String categoryLabel,
      @Schema(nullable = true) String categoryReason,
      @Schema(nullable = true) UUID categorySetBy,
      @Schema(nullable = true) OffsetDateTime categorySetAt,
      @Schema(nullable = true) UUID assigneeId,
      @Schema(nullable = true) UUID claimedBy,
      @Schema(nullable = true) OffsetDateTime claimedAt,
      String conclusion,
      @Schema(nullable = true) UUID closedBy,
      @Schema(nullable = true) OffsetDateTime closedAt,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 一条调整分录（**凭证或说明**，append-only）。 */
  public record ReconciliationDiffAdjustmentRow(
      UUID id,
      UUID handlingId,
      int entryNo,
      /** 调整凭证号（**本模块不生成凭证**，只登记号；下游 = 12.5.2 凭证）。 */
      @Schema(nullable = true) String voucherNo,
      BigDecimal adjustmentAmount,
      String currency,
      String direction,
      String directionLabel,
      String explanation,
      @Schema(nullable = true) LocalDate occurredOn,
      UUID recordedBy,
      OffsetDateTime recordedAt) {}

  /** 处理时间线上的一条（append-only 举证）。 */
  public record ReconciliationDiffEventRow(
      UUID id,
      UUID handlingId,
      UUID differenceId,
      String eventType,
      String eventTypeLabel,
      String fromStatus,
      String toStatus,
      String summary,
      UUID actorId,
      OffsetDateTime occurredAt) {}

  /** 时间线页（真分页）。 */
  public record ReconciliationDiffEventPage(
      List<ReconciliationDiffEventRow> items, int page, int size, long total) {}

  /**
   * 当前可执行的动作 + 每一条的阻断理由。
   *
   * <p>⚠️ 「为什么不能点」必须说清楚 —— 页面据此禁用按钮并把理由写在按钮旁边。
   */
  public record ReconciliationDiffActions(
      boolean canClaim,
      String claimBlockReason,
      boolean canAssign,
      String assignBlockReason,
      boolean canClassify,
      String classifyBlockReason,
      boolean canRecordAdjustment,
      String recordAdjustmentBlockReason,
      boolean canAdjust,
      String adjustBlockReason,
      boolean canWriteOff,
      String writeOffBlockReason) {}

  /**
   * 一条差异的处理详情。
   *
   * <p>⚠️ <b>这里刻意不挂 {@code handling} 对象</b>：处理记录**可能还不存在**（没人碰过
   * 这条差异），而 springdoc 对 {@code $ref} 类型的属性**不保留 {@code nullable}** ⇒
   * 契约里会变成「这个对象一定在」⇒ 前端按可空处理就是错的。
   * ⇒ 处理记录的字段一律**摊平成可空的简单类型**（{@code claimedBy} /
   * {@code categorySetBy} / {@code categorySetAt}），分类 / 责任人 / 结论等其余字段
   * 已经在 {@link ReconciliationDiffRow} 上。
   */
  public record ReconciliationDiffDetail(
      ReconciliationDiffRow difference,
      @Schema(nullable = true) UUID claimedBy,
      @Schema(nullable = true) UUID categorySetBy,
      @Schema(nullable = true) OffsetDateTime categorySetAt,
      List<ReconciliationDiffAdjustmentRow> adjustments,
      List<ReconciliationDiffEventRow> events,
      ReconciliationDiffActions actions,
      String note) {}

  // ================================================================ 入参

  /** 认领入参（**不传 assigneeId = 认领人就是操作者自己**）。 */
  public record ReconciliationDiffClaimInput(
      @Schema(nullable = true) UUID assigneeId, @Schema(nullable = true) String note) {}

  /** 改派责任人入参。 */
  public record ReconciliationDiffAssignInput(
      @NotNull UUID assigneeId, @Schema(nullable = true) String reason) {}

  /** 分类归因入参（**分类 + 理由都必填**）。 */
  public record ReconciliationDiffClassifyInput(@NotBlank String category, @NotBlank String reason) {}

  /** 登记调整分录入参（**凭证或说明**至少有一个）。 */
  public record ReconciliationDiffAdjustmentInput(
      @Schema(nullable = true) String voucherNo,
      @NotNull @DecimalMin(value = "0.0001") BigDecimal adjustmentAmount,
      /** 不传 = 用差异本身的币种（**每条分录只有一个币种** ⇒ 汇总绝不跨币种相加）。 */
      @Schema(nullable = true) String currency,
      @NotBlank String direction,
      @Schema(nullable = true) String explanation,
      @Schema(nullable = true) LocalDate occurredOn) {}

  /** 人工判定入参（调整 / 核销共用；**结论必填**）。 */
  public record ReconciliationDiffDecideInput(@NotBlank String conclusion) {}
}
