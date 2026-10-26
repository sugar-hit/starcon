package com.starcon.finance;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 12.5.1.3 关账的传输模型。
 *
 * <p>蓝图 12-finance.md 原文：
 *
 * <blockquote>
 *
 * 职责：期末关账流程：检查未处理事项（未对账差异、未结转成本、未核销款项、未过账凭证），按已批准清单区分阻断项与可结转未清项后关闭期间；禁止向已关闭期间录入业务凭证。
 * 输入：关账检查清单结果；关账确认。 输出：关账完成记录；期间锁定。 关系：上游 = 12.5.1.2；下游 = 12.5.2.2
 * 报表。**关账检查清单是防错最后一道关**：阻断项未解决不允许关账；正常未到期应收应付不要求清零，例外与结转须保留依据。
 *
 * </blockquote>
 *
 * <p><b>命名。</b>统一用 {@code FinancePeriodClose*} 前缀，与既有服务的类型名零重叠 ——
 * 契约被 codegen 合并进一个扁平文件，同名后者覆盖前者且不报冲突。
 *
 * <p><b>金额按币种分组。</b>关账检查里涉及的金额**绝不跨币种求和** ⇒ 金额一律
 * {@link BigDecimal} + 数据库 NUMERIC，列表与详情都给「按币种分组」的形态
 * （{@link FinancePeriodCloseCurrencyGroup}），不出现「合计」这种跨币种数字。
 *
 * <p><b>期间是日期概念。</b>{@code periodStart / periodEnd / dueDate} 一律 {@link LocalDate}
 * （date 列），检查完成 / 关账 / 审批这些**时刻**才用 {@link OffsetDateTime}（timestamptz 列）。
 */
public final class PeriodCloseModels {

  private PeriodCloseModels() {}

  // ================================================================ 枚举

  /** 检查项编码：与蓝图职责里点名的四类「未处理事项」一一对应。 */
  public enum PeriodCloseCheckCode {
    /**
     * 未对账差异。
     *
     * <p>🔴 上游 12.3.2.1 对账任务 / 12.3.2.2 差异处理**未交付** ⇒ 本项如实报「不可判定」。
     */
    UNRECONCILED_DIFFERENCE("未对账差异"),
    /** 未结转成本（数据源：12.4.1.2 的 finance_cost_calculation_run）。 */
    UNCARRIED_COST("未结转成本"),
    /** 未核销款项（12.1.1.1 应收 / 12.2.1.1 收款 / 12.3.1.1 结算）。 */
    UNSETTLED_PAYMENT("未核销款项"),
    /** 未过账凭证（12.5.2.1 的 finance_voucher，status='DRAFT'）。 */
    UNPOSTED_VOUCHER("未过账凭证");

    private final String label;

    PeriodCloseCheckCode(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 分类：阻断项 / 可结转未清项。 */
  public enum PeriodCloseClassification {
    /** 阻断项：**未解决就不允许关账**（蓝图加粗句）。 */
    BLOCKING("阻断项"),
    /** 可结转未清项：可以带着它关账，但**必须保留依据**。 */
    CARRYWARD("可结转未清项");

    private final String label;

    PeriodCloseClassification(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 检查结果。 */
  public enum PeriodCloseCheckResult {
    /** 通过：这一类没有未处理事项。 */
    PASS("通过"),
    /** 未通过：查出了未处理事项。 */
    FAIL("未通过"),
    /**
     * 不可判定。
     *
     * <p>🔴 **只用于「上游未交付 / 读不到」** —— 绝不用它冒充 PASS，
     * 也绝不用它悄悄放过一个真问题。
     */
    NOT_DETERMINABLE("不可判定");

    private final String label;

    PeriodCloseCheckResult(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 关账执行状态。 */
  public enum PeriodCloseExecutionStatus {
    /** 已提交审批，**期间仍然开放**。 */
    PENDING_APPROVAL("审批中"),
    /** 审批通过、关账已执行 —— 期间**已锁定、禁止过账**。 */
    APPROVED("审批通过"),
    /** 审批被驳回 —— 期间**仍然开放**。 */
    REJECTED("审批驳回"),
    /** 已关账：关账完成记录已落库，期间**已锁定、禁止过账**。 */
    CLOSED("已关账");

    private final String label;

    PeriodCloseExecutionStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 清单

  /** 关账检查清单（一行 = 某期间的一次检查运行）。 */
  public record FinancePeriodCloseChecklistRow(
      UUID id,
      UUID periodId,
      /** 期间编码快照（12.5.1.2 的 finance_voucher_period）。 */
      String periodCode,
      int runNo,
      String checklistNo,
      LocalDate periodStart,
      LocalDate periodEnd,
      /** 期间本位币快照。 */
      String baseCurrency,
      OffsetDateTime startedAt,
      OffsetDateTime completedAt,
      @Schema(nullable = true) UUID createdBy,
      String note,
      OffsetDateTime createdAt) {}

  /** 逐项检查结果（一行 = 一类检查项）。 */
  public record FinancePeriodCloseCheckRow(
      UUID id,
      UUID checklistId,
      UUID periodId,
      PeriodCloseCheckCode checkCode,
      String checkCodeLabel,
      String checkName,
      /** 规则判定（不可改）。 */
      PeriodCloseClassification ruleClassification,
      String ruleClassificationLabel,
      /** 最终分类（人工可下调为可结转未清项，但必须写例外依据）。 */
      PeriodCloseClassification classification,
      String classificationLabel,
      PeriodCloseCheckResult result,
      String resultLabel,
      /** 分类依据（落库）。 */
      String classificationBasis,
      /** 例外依据（人工下调分类时必填）。 */
      String exceptionBasis,
      @Schema(nullable = true) UUID exceptionBy,
      @Schema(nullable = true) OffsetDateTime exceptionAt,
      int itemCount,
      /** 数据源说明（真的查了哪张表 / 哪个上游模块）。 */
      String dataSource,
      /** 这一项**按币种分组**的金额（**永不跨币种相加**）。 */
      List<FinancePeriodCloseCheckAmountRow> amounts,
      OffsetDateTime createdAt) {}

  /** 可钻取明细（一行 = 一个未处理事项）。 */
  public record FinancePeriodCloseCheckItemRow(
      UUID id,
      UUID checklistId,
      UUID checkId,
      UUID periodId,
      String sourceModule,
      String sourceType,
      UUID sourceId,
      String sourceNo,
      String currency,
      /** 未处理金额（该币种内）。 */
      BigDecimal amount,
      @Schema(nullable = true) BigDecimal quantity,
      String reason,
      PeriodCloseClassification classification,
      String classificationLabel,
      @Schema(nullable = true) LocalDate dueDate,
      OffsetDateTime createdAt) {}

  /** 按币种分组的金额（**永不跨币种相加**）。 */
  public record FinancePeriodCloseCheckAmountRow(
      UUID id,
      UUID checklistId,
      UUID checkId,
      String currency,
      BigDecimal amount,
      int itemCount,
      OffsetDateTime createdAt) {}

  /**
   * 一个币种分组 + 可选的本位币折算。
   *
   * <p>🔴 汇率不可得就<b>只给分组、不折算</b>（{@code baseAmount} 为空，
   * {@code conversionNote} 写明原因）—— 绝不拿一个猜的汇率折算出一个看起来像真的总数。
   */
  public record FinancePeriodCloseCurrencyGroup(
      String currency,
      BigDecimal amount,
      int itemCount,
      @Schema(nullable = true) BigDecimal baseAmount,
      @Schema(nullable = true) BigDecimal exchangeRate,
      @Schema(nullable = true) LocalDate rateEffectiveDate,
      String conversionNote) {}

  // ================================================================ 执行

  /** 关账执行记录（关账完成记录）。 */
  public record FinancePeriodCloseExecutionRow(
      UUID id,
      UUID periodId,
      String periodCode,
      UUID checklistId,
      String checklistNo,
      String closeNo,
      PeriodCloseExecutionStatus status,
      String statusLabel,
      int blockingCount,
      int carryforwardCount,
      int notDeterminableCount,
      /** 结转依据（落库）。 */
      String carryforwardBasis,
      String reason,
      @Schema(nullable = true) UUID approvalInstanceId,
      String approvalInstanceNo,
      String approvalStatus,
      /** 期间是否已锁定（禁止过账）。 */
      boolean periodLocked,
      String lockNote,
      @Schema(nullable = true) OffsetDateTime closedAt,
      @Schema(nullable = true) UUID closedBy,
      @Schema(nullable = true) UUID createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 审批链路镜像的一步（identity 侧链路的本地镜像）。 */
  public record FinancePeriodCloseApprovalRow(
      UUID id,
      UUID executionId,
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

  // ================================================================ 聚合

  /** 关账清单详情：清单 + 四类检查项 + 金额分组（按币种）。 */
  public record FinancePeriodCloseChecklistDetail(
      FinancePeriodCloseChecklistRow checklist,
      List<FinancePeriodCloseCheckRow> checks,
      List<FinancePeriodCloseCheckAmountRow> amounts,
      /**
       * 整份清单**按币种分组**的金额 + 可选的本位币折算。
       *
       * <p>🔴 这里**刻意没有「合计」这一个数字** —— 多币种绝不相加：
       * 想知道一共多少钱，答案永远是「按币种分开是这些」。
       */
      List<FinancePeriodCloseCurrencyGroup> currencyGroups,
      // ---- 汇总一律**现算**（唯一真相来源 = 那四张检查项行）----
      /** 检查项总数（恒为 4：蓝图点名的四类未处理事项）。 */
      int checkCount,
      /** **当前**阻断项数（人工下调例外后立刻变）。 */
      int blockingCount,
      /** **当前**可结转未清项数（result=FAIL 且已下调为可结转）。 */
      int carryforwardCount,
      /** **当前**不可判定项数（上游未交付 ⇒ 读不到）。 */
      int notDeterminableCount,
      /** 通过项数。 */
      int passedCount,
      /** 期间当前状态（12.5.1.2 的 finance_voucher_period）。 */
      String periodStatus,
      String periodStatusLabel,
      /** 期间锁定说明（如实反映 V357 的过账守卫）。 */
      String periodLockNote) {}

  /** 关账执行详情：执行记录 + 审批链路。 */
  public record FinancePeriodCloseExecutionDetail(
      FinancePeriodCloseExecutionRow execution,
      List<FinancePeriodCloseApprovalRow> approvals,
      FinancePeriodCloseChecklistDetail checklist) {}

  /** 明细钻取分页。 */
  public record FinancePeriodCloseCheckItemPage(
      List<FinancePeriodCloseCheckItemRow> items, int page, int size, long total) {}

  /** 清单分页。 */
  public record FinancePeriodCloseChecklistPage(
      List<FinancePeriodCloseChecklistRow> items, int page, int size, long total) {}

  /** 执行记录分页。 */
  public record FinancePeriodCloseExecutionPage(
      List<FinancePeriodCloseExecutionRow> items, int page, int size, long total) {}

  /**
   * 阻断原因清单（阻断项未解决 ⇒ 拒绝关账，返回**明确的**阻断原因清单）。
   *
   * @param checkCode 检查项编码
   * @param checkName 检查项名称
   * @param itemCount 未处理事项条数
   * @param basis 分类依据（为什么判成阻断项）
   */
  public record FinancePeriodCloseBlockingReason(
      PeriodCloseCheckCode checkCode, String checkName, int itemCount, String basis) {}

  /**
   * 提交关账的结果。
   *
   * @param outcome 关账结局：BLOCKED（被阻断）/ PENDING_APPROVAL（已提交审批）/ CLOSED（已关账）
   * @param message 如实说明这一局的结局
   * @param blockingReasons 阻断原因清单（outcome=BLOCKED 时非空）
   * @param executionId 刚创建 / 已存在的关账执行记录 id（outcome=BLOCKED 时为空）
   *     <p>⚠️ **刻意只给 id、不给整行**：一个「可空的对象引用」会把行类型本身变成可空
   *     （契约 codegen 的既有行为），那样整页代码都要到处判空。给 id 让调用方自己去取详情。
   */
  public record FinancePeriodCloseSubmitResult(
      String outcome,
      String message,
      List<FinancePeriodCloseBlockingReason> blockingReasons,
      @Schema(nullable = true) String executionId) {}

  // ================================================================ 输入

  /** 运行一次检查清单的入参（目标期间）。 */
  public record FinancePeriodCloseChecklistInput(UUID periodId, String note) {}

  /**
   * 把阻断项下调为可结转未清项的入参。
   *
   * <p>🔴 {@code exceptionBasis} **必填** —— 蓝图加粗句「例外与结转须保留依据」，
   * 服务层与 DB 守卫各拒一次。
   */
  public record FinancePeriodCloseCarryForwardInput(String exceptionBasis) {}

  /** 提交关账的入参。 */
  public record FinancePeriodCloseExecuteInput(
      UUID checklistId,
      /** 关账原因（写给审计看的一句话）。 */
      String reason,
      /** 结转依据（有结转项时**必填**）。 */
      String carryforwardBasis) {}

  // ================================================================ 能力声明与筛选项

  /** 能力声明的一行。 */
  public record FinancePeriodCloseCapability(
      String module,
      String name,
      boolean available,
      String basis,
      String ownerModule,
      String detail) {}

  /** 下游缺口（谁还没交付）。 */
  public record FinancePeriodCloseDownstreamGap(
      String module, String name, String ownerService, boolean available, String group, String detail) {}

  /** 12.5.1.3 的能力声明。 */
  public record FinancePeriodCloseCapabilities(
      List<FinancePeriodCloseCapability> capabilities,
      List<FinancePeriodCloseDownstreamGap> downstreamGaps) {}

  /** 筛选项下拉（全部来自真实数据）。 */
  public record FinancePeriodCloseOptions(
      List<String> statuses,
      List<String> checkCodes,
      List<String> currencies,
      List<String> periodStatuses,
      List<FinancePeriodCloseOptionRef> periods) {}

  /** 期间下拉项。 */
  public record FinancePeriodCloseOptionRef(String id, String code, String name, String status) {}
}
