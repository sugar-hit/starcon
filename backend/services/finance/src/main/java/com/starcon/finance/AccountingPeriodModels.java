package com.starcon.finance;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 12.5.1.2 会计期间的传输模型。
 *
 * <p>蓝图 12-finance.md 原文：
 *
 * <blockquote>
 *
 * 职责：管理会计期间（自然月或 4-4-5 fiscal 周期）：期间状态（开放/关闭）、关账与重开、期间内凭证限制。
 * 输入：会计日历配置；关账/重开操作；审批。 输出：期间状态；关账记录。 关系：上游 = 会计日历；下游 =
 * 12.5.2.1 凭证（已关闭期间禁止过账）。**重开已关期间是高风险操作**，必须审批留痕。
 *
 * </blockquote>
 *
 * <p><b>命名。</b>统一用 {@code FinanceAccountingPeriod*} / {@code FinanceAccountingCalendar*}
 * 前缀，与既有服务的类型名零重叠 —— 契约被 codegen 合并进一个扁平文件，同名后者覆盖前者且不报冲突。
 *
 * <p><b>没有金额。</b>会计期间是**账期配置**，金额的载体是凭证（12.5.2.1）⇒ 本文件里**没有一个
 * BigDecimal**：唯一与数字有关的字段是财年 / 周期序号 / 周期长度，都是整数。
 *
 * <p><b>期间是日期概念。</b>{@code periodStart / periodEnd / anchorDate} 一律 {@link LocalDate}
 * （date 列），关账 / 重开 / 留痕这些**时刻**才用 {@link OffsetDateTime}（timestamptz 列）。
 */
public final class AccountingPeriodModels {

  private AccountingPeriodModels() {}

  // ================================================================ 枚举

  /** 期间状态：开放 / 关闭。 */
  public enum AccountingPeriodStatus {
    /** 开放：可以过账。 */
    OPEN("开放"),
    /** 关闭：**禁止过账**（下游 12.5.2.1 的触发器读这个状态）。 */
    CLOSED("关闭");

    private final String label;

    AccountingPeriodStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 期间类型。
   *
   * <p>🔴 自然月与 4-4-5 是**两种账期体系**，周期边界与取数口径都不一样 ⇒ 期间自带类型，
   * 报表按财年取数时不必回头猜「这期到底是自然月还是 4-4-5」。
   */
  public enum AccountingPeriodType {
    /** 自然月（12 个日历月）。 */
    CALENDAR_MONTH("自然月"),
    /** 4-4-5 fiscal 周期（12 个 13 周周期）。 */
    FISCAL_445("4-4-5 周期"),
    /** 自定义：完全由用户登记，不绑定日历。 */
    CUSTOM("自定义");

    private final String label;

    AccountingPeriodType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 会计日历类型。 */
  public enum AccountingCalendarType {
    /** 自然月日历：财年从起始月起算 12 个日历月。 */
    CALENDAR_MONTH("自然月"),
    /** 4-4-5 日历：锚点日起算 4-4-5 循环 × 4 轮 = 52 周。 */
    FISCAL_445("4-4-5 周期"),
    /** 自定义日历：不生成期间。 */
    CUSTOM("自定义");

    private final String label;

    AccountingCalendarType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 会计日历状态。 */
  public enum AccountingCalendarStatus {
    /** 在用。 */
    ACTIVE("在用"),
    /** 停用（**不是删除**：已生成的期间仍要能引用到它）。 */
    DISABLED("停用");

    private final String label;

    AccountingCalendarStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 期间事件类型。 */
  public enum AccountingPeriodEventType {
    /** 建期。 */
    CREATED("建期"),
    /** 关账。 */
    CLOSED("关账"),
    /** 提交重开申请（已进审批，**尚未**重开）。 */
    REOPEN_REQUESTED("提交重开申请"),
    /** 重开完成（审批已批准）。 */
    REOPENED("重开"),
    /** 改了非状态字段（备注等）。 */
    UPDATED("变更");

    private final String label;

    AccountingPeriodEventType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 重开申请在审批里的状态。 */
  public enum AccountingApprovalStatus {
    /** 审批中。 */
    PENDING("审批中"),
    /** 已批准 ⇒ 允许重开。 */
    APPROVED("已批准"),
    /** 已驳回 ⇒ 不允许重开。 */
    REJECTED("已驳回");

    private final String label;

    AccountingApprovalStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 能力声明

  /** 能力条目：每一维说清「有没有、依据是什么、缺了会怎样」。 */
  public record FinanceAccountingPeriodCapability(
      String module,
      String name,
      boolean available,
      String basis,
      String ownerModule,
      String detail) {}

  /** 下游缺口：谁没交付、归谁。 */
  public record FinanceAccountingPeriodDownstreamGap(
      String module,
      String name,
      String ownerService,
      boolean available,
      String group,
      String detail) {}

  /** 12.5.1.2 的能力声明。 */
  public record FinanceAccountingPeriodCapabilities(
      List<FinanceAccountingPeriodCapability> capabilities,
      List<FinanceAccountingPeriodDownstreamGap> downstreamGaps) {}

  // ================================================================ 期间

  /** 会计期间行（一行 = 一个账期）。 */
  public record FinanceAccountingPeriodRow(
      UUID id,
      String periodCode,
      /** 期间开始日（date）：账期是一段日历，不是一瞬间。 */
      LocalDate periodStart,
      LocalDate periodEnd,
      /** 周期长度（天）：由日期区间派生，不会与日期漂移。 */
      int periodLengthDays,
      AccountingPeriodStatus status,
      String statusLabel,
      /** 本位币（账簿本位币）：过账时凭证币种必须与它一致。 */
      String baseCurrency,
      AccountingPeriodType periodType,
      String periodTypeLabel,
      /** 所属会计日历（自定义期间为空）。 */
      @Schema(nullable = true) UUID calendarId,
      @Schema(nullable = true) String calendarCode,
      @Schema(nullable = true) String calendarName,
      /** 会计年度（自定义期间为空）。 */
      @Schema(nullable = true) Integer fiscalYear,
      /** 财年内周期序号 1..12（自定义期间为空）。 */
      @Schema(nullable = true) Integer fiscalPeriodNo,
      /** 关账时刻（timestamptz）。 */
      @Schema(nullable = true) OffsetDateTime closedAt,
      @Schema(nullable = true) UUID closedBy,
      /** 关账原因。 */
      String closedReason,
      /** 重开时刻（timestamptz）：重开**过**才有值。 */
      @Schema(nullable = true) OffsetDateTime reopenedAt,
      @Schema(nullable = true) UUID reopenedBy,
      /** 重开原因。 */
      String reopenReason,
      /** 重开所依据的审批实例（identity 侧，跨服务无 FK）。 */
      @Schema(nullable = true) UUID reopenApprovalInstanceId,
      /** 累计重开次数。 */
      int reopenCount,
      String note,
      int version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 期间状态汇总（本模块没有金额，汇总是**期间笔数**）。 */
  public record FinanceAccountingPeriodSummary(
      AccountingPeriodStatus status, String statusLabel, long count) {}

  /** 期间列表页（汇总覆盖**整个筛选结果集**，不是当前页）。 */
  public record FinanceAccountingPeriodPage(
      List<FinanceAccountingPeriodRow> items,
      int page,
      int size,
      long total,
      List<FinanceAccountingPeriodSummary> summaryByStatus) {}

  /** 期间列表查询条件。 */
  public record AccountingPeriodQuery(
      @Schema(nullable = true) AccountingPeriodStatus status,
      @Schema(nullable = true) AccountingPeriodType periodType,
      @Schema(nullable = true) UUID calendarId,
      @Schema(nullable = true) Integer fiscalYear,
      @Schema(nullable = true) String keyword,
      int page,
      int size) {}

  /** 筛选项下拉：全部来自**真实数据**（distinct 查询），不是写死的词表。 */
  public record FinanceAccountingPeriodOptions(
      List<String> statuses,
      List<String> periodTypes,
      List<String> baseCurrencies,
      /** 真实出现过的日历 ID（去重）。 */
      List<UUID> calendarIds,
      /** 真实出现过的财年（去重，倒序）。 */
      List<Integer> fiscalYears) {}

  /**
   * 手工登记一个自定义期间。
   *
   * <p>⚠️ 自定义期间**不绑定日历**：它没有 fiscal 周期（{@code fiscalYear / fiscalPeriodNo} 为空）。
   * 要有 fiscal 周期请用「会计日历生成期间」。
   */
  public record FinanceAccountingPeriodInput(
      String periodCode, LocalDate periodStart, LocalDate periodEnd, String baseCurrency, String note) {}

  /** 关账入参：必须写明原因。 */
  public record FinanceAccountingPeriodCloseInput(String reason, Integer expectedVersion) {}

  /**
   * 改期间入参。
   *
   * <p>⚠️ **只有备注可改**：期间编码 / 起止日 / 本位币 / 会计日历 / fiscal 周期**一经入账不可改**
   * （凭证按 {@code period_id} 引用期间，改了等于改账期）—— 与 12.5.1.1 科目「编码不可改」同一条纪律。
   */
  public record FinanceAccountingPeriodUpdateInput(String note) {}

  /**
   * 重开入参。
   *
   * <p>⚠️ **只带原因与版本**：审批实例由本模块自己去 17 审批（identity）申请与读取 ——
   * 调用方**不能**自己指定一个审批实例 id（否则等于「随便报一个已批准的 id 就重开了」）。
   */
  public record FinanceAccountingPeriodReopenInput(String reason, Integer expectedVersion) {}

  /**
   * 重开结果。
   *
   * <p>🔴 **四种结局都要如实报出来**：
   *
   * <ul>
   *   <li>{@code REOPEN_REQUESTED} —— 刚提交了审批申请，期间**仍然关闭**；
   *   <li>{@code PENDING_APPROVAL} —— 审批中，期间**仍然关闭**；
   *   <li>{@code REJECTED} —— 审批被驳回，期间**仍然关闭**；
   *   <li>{@code REOPENED} —— 审批已批准，期间**已重开**。
   * </ul>
   */
  public record FinanceAccountingPeriodReopenResult(
      String outcome,
      String message,
      @Schema(nullable = true) UUID approvalInstanceId,
      @Schema(nullable = true) String approvalInstanceNo,
      @Schema(nullable = true) String approvalStatus,
      @Schema(nullable = true) FinanceAccountingPeriodRow period) {}

  // ================================================================ 会计日历

  /** 会计日历行（一行 = 一套账期体系）。 */
  public record FinanceAccountingCalendarRow(
      UUID id,
      String calendarCode,
      String calendarName,
      AccountingCalendarType calendarType,
      String calendarTypeLabel,
      /** 财年起始月（自然月日历用）：1 = 财年 = 自然年。 */
      int fiscalYearStartMonth,
      /** 4-4-5 锚点日（财年第一天）。 */
      @Schema(nullable = true) LocalDate anchorDate,
      String baseCurrency,
      AccountingCalendarStatus status,
      String statusLabel,
      String note,
      int version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 建日历入参。 */
  public record FinanceAccountingCalendarInput(
      String calendarCode,
      String calendarName,
      AccountingCalendarType calendarType,
      Integer fiscalYearStartMonth,
      @Schema(nullable = true) LocalDate anchorDate,
      String baseCurrency,
      String note) {}

  /**
   * 改日历入参。
   *
   * <p>⚠️ **没有 calendarType / fiscalYearStartMonth / anchorDate / baseCurrency**：
   * 一旦有期间引用它，这四个字段就不可再改（DB 触发器钉死）—— 改了等于改已生成的账期。
   */
  public record FinanceAccountingCalendarUpdateInput(
      String calendarName, AccountingCalendarStatus status, String note) {}

  /** 日历详情：日历 + 它生成的期间。 */
  public record FinanceAccountingCalendarDetail(
      FinanceAccountingCalendarRow calendar, List<FinanceAccountingPeriodRow> periods) {}

  /** 从日历生成期间的入参：要生成哪一个财年。 */
  public record FinanceAccountingPeriodGenerateInput(Integer fiscalYear) {}

  /**
   * 从日历生成期间的结果。
   *
   * <p>⚠️ **已存在的期间编码被跳过而不是覆盖** —— 用户手改过的期间不能被重跑冲掉；
   * 被跳过的编码逐个报出来。
   */
  public record FinanceAccountingPeriodGenerateResult(
      UUID calendarId,
      int fiscalYear,
      int created,
      int skipped,
      /** 被跳过的期间编码（已存在），逐个列出。 */
      List<String> skippedCodes,
      List<FinanceAccountingPeriodRow> periods) {}

  // ================================================================ 留痕与审批

  /** 期间事件留痕（append-only）＝ 蓝图输出里的「**关账记录**」。 */
  public record FinanceAccountingPeriodEventRow(
      UUID id,
      UUID periodId,
      AccountingPeriodEventType eventType,
      String eventTypeLabel,
      String fromStatus,
      String toStatus,
      String reason,
      @Schema(nullable = true) UUID actorId,
      /** 重开申请关联的审批实例。 */
      @Schema(nullable = true) UUID approvalInstanceId,
      /** 审批状态（与审批无关的事件为空）。 */
      String approvalStatus,
      String detail,
      OffsetDateTime occurredAt) {}

  /** 重开审批链路的一步（identity 侧链路的本地镜像）。 */
  public record FinanceAccountingPeriodApprovalRow(
      UUID id,
      UUID periodId,
      UUID approvalInstanceId,
      String approvalInstanceNo,
      @Schema(nullable = true) UUID actionId,
      /** SUBMIT / APPROVE / REJECT / TRANSFER。 */
      String action,
      String nodeKey,
      String nodeName,
      int nodeOrder,
      @Schema(nullable = true) UUID actorId,
      String actorName,
      String comment,
      OffsetDateTime decidedAt,
      long dwellSeconds,
      OffsetDateTime syncedAt) {}

  /** 期间详情：期间 + 事件留痕 + 审批链路。 */
  public record FinanceAccountingPeriodDetail(
      FinanceAccountingPeriodRow period,
      List<FinanceAccountingPeriodEventRow> events,
      List<FinanceAccountingPeriodApprovalRow> approvals) {}
}
