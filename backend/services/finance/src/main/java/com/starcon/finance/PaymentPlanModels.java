package com.starcon.finance;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 12.1.2.2 付款计划的传输模型。
 *
 * <p>蓝图 12-finance.md 原文：
 *
 * <blockquote>
 *
 * 职责：按付款条件（账期、里程碑、到期日）生成付款计划与到期提醒，支持按供应商与到期窗口批量安排付款。 输入：应付到期日；账期规则；资金计划。 输出：付款计划表 + 到期提醒 + 资金需求预测。 关系：上游 = 12.1.2.1；下游 = 12.2.2.1、1.3 提醒。
 * <b>付款计划与资金预测联动</b>：跨境资金常有回款延迟与付款刚性，错配会导致现金流断裂。
 *
 * </blockquote>
 *
 * <p><b>命名。</b>统一用 {@code FinancePayment*} 前缀，与既有七个服务的类型名零重叠 ——
 * 契约被 codegen 合并进一个扁平文件，同名后者覆盖前者且不报冲突。
 *
 * <p><b>金额一律 BigDecimal + NUMERIC</b>：本文件里没有一个 {@code double}。金额 {@code numeric(19,4)}、
 * 比例 {@code numeric(19,6)}。
 *
 * <p><b>币种分开</b>：任何汇总都按币种分组返回，USD 与 EUR 加起来不是任何一个金额 ——
 * 这是全仓一致的纪律。资金需求预测尤其如此：流入与流出**都**按币种分开比。
 */
public final class PaymentPlanModels {

  private PaymentPlanModels() {}

  /** 金额小数位（与 trade / crm 一致）。 */
  public static final int MONEY_SCALE = 4;

  /** 比例小数位。 */
  public static final int RATIO_SCALE = 6;

  // ================================================================ 枚举

  /**
   * 付款条件 —— 蓝图加粗句「按付款条件（<b>账期、里程碑、到期日</b>）生成付款计划」。
   *
   * <p>⚠️ <b>三者不是同一个 type 字段上的三行取值</b>，而是三种**不同的算法**：
   *
   * <ul>
   *   <li>{@link #DUE_DATE}（到期日）—— 条件日 = 应付单自带的到期日（12.1.2.1 的事实）。
   *   <li>{@link #TERM}（账期）—— 条件日 = 起算基准日 + <b>账期规则</b>里的账期天数。
   *       账期规则由租户自己维护，**系统不预置任何一行**。
   *   <li>{@link #MILESTONE}（里程碑）—— 一张应付可拆成**多条计划**，每个里程碑一条。
   * </ul>
   */
  public enum PaymentCondition {
    /** 账期：起算基准日 + 账期规则的天数。 */
    TERM("账期"),
    /** 里程碑：每个里程碑一条计划，按比例拆分金额。 */
    MILESTONE("里程碑"),
    /** 到期日：直接取应付单的到期日。 */
    DUE_DATE("到期日");

    private final String label;

    PaymentCondition(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 付款计划状态。 */
  public enum PaymentPlanStatus {
    /** 已生成：计划存在，但还没排进任何付款批次。 */
    PLANNED("已生成"),
    /** 已安排：已排进某个付款批次（**尚未付款** —— 付款属 12.2.2.1，未交付）。 */
    SCHEDULED("已安排"),
    /** 已取消：终态，整条冻结。 */
    CANCELLED("已取消");

    private final String label;

    PaymentPlanStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 终态：不可再改。 */
    public boolean terminal() {
      return this == CANCELLED;
    }
  }

  /** 付款批次状态。 */
  public enum PaymentBatchStatus {
    /** 已安排。 */
    SCHEDULED("已安排"),
    /** 已取消：终态。 */
    CANCELLED("已取消");

    private final String label;

    PaymentBatchStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 提醒类型 —— **真实的到期判定**，不是文案。 */
  public enum ReminderType {
    /** 即将到期（计划付款日在提醒窗口内、尚未到期）。 */
    UPCOMING("即将到期"),
    /** 今日到期。 */
    DUE("今日到期"),
    /** 已逾期。 */
    OVERDUE("已逾期");

    private final String label;

    ReminderType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 提醒处理状态 —— **真实的状态机**，不是假计数。 */
  public enum ReminderStatus {
    /** 待处理。 */
    PENDING("待处理"),
    /** 已确认（谁、什么时候确认的都记下来）。 */
    ACKNOWLEDGED("已确认"),
    /** 已忽略。 */
    DISMISSED("已忽略");

    private final String label;

    ReminderStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 账期起算基准。 */
  public enum TermBaseKind {
    /** 应付确认日。 */
    CONFIRMED_DATE("确认日"),
    /** 应付到期日。 */
    DUE_DATE("到期日");

    private final String label;

    TermBaseKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 应付对象类型（与 12.1.2.1 同一词表）。 */
  public enum PaymentCounterpartyType {
    SUPPLIER("供应商"),
    CARRIER("承运商"),
    PLATFORM("平台"),
    OTHER("其他");

    private final String label;

    PaymentCounterpartyType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 能力声明

  /** 能力条目：每一维说清「有没有、依据是什么、缺了会怎样」。 */
  public record FinancePaymentPlanCapability(
      String module,
      String name,
      boolean available,
      String basis,
      String ownerModule,
      String detail) {}

  /** 下游缺口：谁没交付、归谁。 */
  public record FinancePaymentPlanDownstreamGap(
      String module,
      String name,
      String ownerService,
      boolean available,
      String group,
      String detail) {}

  /** 12.1.2.2 的能力声明。 */
  public record FinancePaymentPlanCapabilities(
      List<FinancePaymentPlanCapability> capabilities,
      List<FinancePaymentPlanDownstreamGap> downstreamGaps) {}

  // ================================================================ 规则

  /** 账期规则（**租户自己维护**，系统不预置任何一行）。 */
  public record FinancePaymentTermRuleRow(
      UUID id,
      String name,
      @Schema(nullable = true) UUID counterpartyRef,
      @Schema(nullable = true) String currency,
      TermBaseKind baseKind,
      int termDays,
      boolean enabled,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 配置一条账期规则。 */
  public record FinancePaymentTermRuleInput(
      String name,
      @Schema(nullable = true) UUID counterpartyRef,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) TermBaseKind baseKind,
      @Schema(nullable = true) Integer termDays,
      @Schema(nullable = true) Boolean enabled,
      @Schema(nullable = true) String note) {}

  /** 里程碑明细。 */
  public record FinancePaymentMilestoneRow(
      UUID id,
      int seq,
      String name,
      @Schema(nullable = true) Integer offsetDays,
      @Schema(nullable = true) LocalDate triggerDate,
      BigDecimal ratio) {}

  /** 里程碑规则（含它的里程碑明细）。 */
  public record FinancePaymentMilestoneRuleRow(
      UUID id,
      String name,
      @Schema(nullable = true) UUID counterpartyRef,
      @Schema(nullable = true) String currency,
      boolean enabled,
      String note,
      List<FinancePaymentMilestoneRow> milestones,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 配置一个里程碑（触发日两种写法二选一）。 */
  public record FinancePaymentMilestoneInput(
      @Schema(nullable = true) Integer seq,
      String name,
      @Schema(nullable = true) Integer offsetDays,
      @Schema(nullable = true) LocalDate triggerDate,
      BigDecimal ratio) {}

  /** 配置一套里程碑规则（**整套一起落**：比例之和必须为 1）。 */
  public record FinancePaymentMilestoneRuleInput(
      String name,
      @Schema(nullable = true) UUID counterpartyRef,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) Boolean enabled,
      @Schema(nullable = true) String note,
      List<FinancePaymentMilestoneInput> milestones) {}

  // ================================================================ 付款计划

  /** 付款计划。 */
  public record FinancePaymentPlanRow(
      UUID id,
      String planNo,
      UUID payableId,
      String payableNo,
      @Schema(nullable = true) UUID batchId,
      @Schema(nullable = true) String batchNo,
      PaymentCondition paymentCondition,
      @Schema(nullable = true) UUID termRuleId,
      @Schema(nullable = true) UUID milestoneId,
      @Schema(nullable = true) String milestoneName,
      /** 条件日：按付款条件算出来的日子。 */
      LocalDate conditionDate,
      /** 计划付款日：排进资金计划的那一天。 */
      LocalDate plannedPaidDate,
      /** 是否被付款窗口压过（生成列 ⇒ 与两个日期不可能对不上）。 */
      boolean clamped,
      String currency,
      BigDecimal plannedAmount,
      /** 应付未付余额快照（生成计划那一刻）。 */
      BigDecimal unpaidSnapshot,
      /** 应付**当前**未付余额（实时读上游，用来发现计划已偏离）。 */
      BigDecimal currentUnpaid,
      PaymentPlanStatus status,
      /** 付款执行归属模块（恒为 12.2.2.1 —— 它未交付）。 */
      String executionOwnerModule,
      /** 付款执行是否可用（**恒为 false** —— 12.2.2.1 未交付）。 */
      boolean executionAvailable,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 计划事件（append-only 举证凭据）。 */
  public record FinancePaymentPlanEventRow(
      UUID id,
      String eventType,
      String fromStatus,
      String toStatus,
      String reason,
      @Schema(nullable = true) UUID actorId,
      OffsetDateTime occurredAt) {}

  /** 按币种的计划汇总。 */
  public record FinancePaymentPlanCurrencySummary(
      String currency, BigDecimal plannedAmount, long count, long scheduledCount, long overdueCount) {}

  /** 付款计划列表页。 */
  public record FinancePaymentPlanPage(
      List<FinancePaymentPlanRow> items,
      int page,
      int size,
      long total,
      List<FinancePaymentPlanCurrencySummary> summaryByCurrency,
      long overdueCount) {}

  /** 付款计划详情：计划 + 事件流 + 当前提醒。 */
  public record FinancePaymentPlanDetail(
      FinancePaymentPlanRow plan,
      List<FinancePaymentPlanEventRow> events,
      @Schema(nullable = true) FinancePaymentReminderRow reminder) {}

  // ================================================================ 付款批次

  /** 付款批次（=「按供应商与到期窗口批量安排」的安排结果）。 */
  public record FinancePaymentBatchRow(
      UUID id,
      String batchNo,
      PaymentCounterpartyType counterpartyType,
      @Schema(nullable = true) UUID counterpartyRef,
      String counterpartyName,
      LocalDate dueWindowStart,
      LocalDate dueWindowEnd,
      LocalDate paymentWindowStart,
      LocalDate paymentWindowEnd,
      String currency,
      BigDecimal plannedAmount,
      int planCount,
      PaymentBatchStatus status,
      String executionOwnerModule,
      boolean executionAvailable,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 付款批次列表页。 */
  public record FinancePaymentBatchPage(
      List<FinancePaymentBatchRow> items, int page, int size, long total) {}

  // ================================================================ 生成 / 安排

  /** 列表查询条件。 */
  public record PaymentPlanQuery(
      @Schema(nullable = true) PaymentPlanStatus status,
      @Schema(nullable = true) PaymentCondition paymentCondition,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) UUID counterpartyRef,
      @Schema(nullable = true) UUID batchId,
      @Schema(nullable = true) Boolean overdueOnly,
      @Schema(nullable = true) LocalDate paidFrom,
      @Schema(nullable = true) LocalDate paidTo,
      @Schema(nullable = true) String keyword,
      int page,
      int size) {}

  /**
   * 生成付款计划（**只生成，不排批次**）。
   *
   * <p>⚠️ 生成 ≠ 付款：本端点只产生计划与到期判定，**不产生任何付款动作**。
   */
  public record FinancePaymentPlanGenerateInput(
      PaymentCondition paymentCondition,
      @Schema(nullable = true) UUID termRuleId,
      @Schema(nullable = true) UUID milestoneRuleId,
      @Schema(nullable = true) UUID counterpartyRef,
      @Schema(nullable = true) LocalDate dueFrom,
      @Schema(nullable = true) LocalDate dueTo,
      @Schema(nullable = true) String note) {}

  /**
   * 按供应商与到期窗口**批量安排付款**。
   *
   * <p>⚠️ 安排 = 把应付排进某个付款窗口 / 批次，**不是执行付款**。
   */
  public record FinancePaymentPlanScheduleInput(
      UUID counterpartyRef,
      LocalDate dueFrom,
      LocalDate dueTo,
      LocalDate paymentWindowStart,
      LocalDate paymentWindowEnd,
      PaymentCondition paymentCondition,
      @Schema(nullable = true) UUID termRuleId,
      @Schema(nullable = true) UUID milestoneRuleId,
      @Schema(nullable = true) String note) {}

  /**
   * 批量安排的结果。
   *
   * @param scanned 这次扫了多少张应付
   * @param planned 新建了多少条计划
   * @param rearranged 已有计划被改期（重新排进批次）多少条
   * @param skipped 跳过多少条（已排过、已付清、没有可用规则 …）
   * @param batchCount 建了多少个批次（**按币种分批**）
   */
  public record FinancePaymentPlanScheduleResult(
      int scanned,
      int planned,
      int rearranged,
      int skipped,
      int batchCount,
      List<FinancePaymentPlanRow> plans,
      List<FinancePaymentBatchRow> batches,
      List<String> warnings) {}

  // ================================================================ 资金需求预测

  /**
   * 预测的一个窗口（**按币种一行**）。
   *
   * @param outflowAmount 流出：本窗口内计划要付的钱（来自付款计划）
   * @param inflowAmount 流入：本窗口内预计回款的钱（来自 12.1.1.1 应收单的未核销余额）
   * @param netAmount 净额 = 流入 − 流出（负数 ⇒ 现金流断裂风险）
   */
  public record FinancePaymentForecastWindow(
      String currency,
      LocalDate windowStart,
      LocalDate windowEnd,
      long planCount,
      BigDecimal outflowAmount,
      BigDecimal inflowAmount,
      BigDecimal netAmount,
      boolean gapFlag) {}

  /** 一个币种的预测合计。 */
  public record FinancePaymentForecastCurrencyTotal(
      String currency,
      BigDecimal outflowAmount,
      BigDecimal inflowAmount,
      BigDecimal netAmount,
      long windowCount,
      long gapWindowCount) {}

  /**
   * 资金需求预测。
   *
   * <p>⚠️ **它是实时聚合出来的，不是另一张静态表** ⇒ 计划改了，预测当场跟着变。
   */
  public record FinancePaymentForecast(
      LocalDate asOfDate,
      int windowDays,
      LocalDate fromDate,
      LocalDate toDate,
      List<FinancePaymentForecastWindow> windows,
      List<FinancePaymentForecastCurrencyTotal> totalsByCurrency,
      long planCount) {}

  // ================================================================ 到期提醒

  /** 到期提醒。 */
  public record FinancePaymentReminderRow(
      UUID id,
      UUID planId,
      String planNo,
      UUID payableId,
      String payableNo,
      String counterpartyName,
      ReminderType reminderType,
      LocalDate remindDate,
      LocalDate dueDate,
      String currency,
      BigDecimal amount,
      ReminderStatus status,
      @Schema(nullable = true) OffsetDateTime acknowledgedAt,
      @Schema(nullable = true) UUID acknowledgedBy,
      String dismissReason,
      /** 推送通道归属模块（恒为 1.3 —— 它未交付）。 */
      String pushOwnerModule,
      /** 推送状态（**恒为 NOT_PUSHED** —— 推送通道不可用）。 */
      String pushStatus,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 按币种的提醒汇总。 */
  public record FinancePaymentReminderCurrencySummary(
      String currency, BigDecimal amount, long count, long pendingCount, long overdueCount) {}

  /** 到期提醒清单。 */
  public record FinancePaymentReminderPage(
      List<FinancePaymentReminderRow> items,
      int page,
      int size,
      long total,
      List<FinancePaymentReminderCurrencySummary> summaryByCurrency,
      long pendingCount,
      long overdueCount) {}

  /** 提醒查询条件。 */
  public record PaymentReminderQuery(
      @Schema(nullable = true) ReminderStatus status,
      @Schema(nullable = true) ReminderType reminderType,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) UUID counterpartyRef,
      @Schema(nullable = true) String keyword,
      int page,
      int size) {}

  /**
   * 到期提醒扫描的结果。
   *
   * @param created 新建了多少条提醒
   * @param updated 更新了多少条（计划变了 ⇒ 提醒跟着改，并重置为「待处理」）
   * @param dismissed 让多少条提醒失效（计划取消 / 移出提醒窗口）
   * @param unchanged 有多少条提醒状态没变（**已确认的不会被重置**）
   */
  public record FinancePaymentReminderScanResult(
      LocalDate asOfDate,
      int horizonDays,
      int scanned,
      int created,
      int updated,
      int dismissed,
      int unchanged,
      List<String> warnings) {}

  /** 确认提醒。理由可选。 */
  public record FinancePaymentReminderAcknowledgeInput(@Schema(nullable = true) String reason) {}

  /** 忽略提醒。理由必填。 */
  public record FinancePaymentReminderDismissInput(String reason) {}

  // ================================================================ 筛选项

  /** 筛选项里的一个引用（id + 名称）。 */
  public record FinancePaymentPlanOptionRef(String id, String name) {}

  /**
   * 筛选项下拉：全部来自**真实数据**（distinct 查询），不是写死的词表。
   *
   * @param counterparties 供应商（来自付款计划与应付单的真实取值）
   * @param termRules 账期规则
   * @param milestoneRules 里程碑规则
   */
  public record FinancePaymentPlanOptions(
      List<String> currencies,
      List<String> statuses,
      List<String> conditions,
      List<FinancePaymentPlanOptionRef> counterparties,
      List<FinancePaymentPlanOptionRef> termRules,
      List<FinancePaymentPlanOptionRef> milestoneRules) {}

  // ================================================================ 动作

  /** 取消计划。理由必填。 */
  public record FinancePaymentPlanCancelInput(String reason) {}
}
