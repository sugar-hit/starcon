package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 11.3.1.1 退款申请的传输模型。
 *
 * <p>蓝图 11-aftersales-and-returns.md 原文：
 *
 * <blockquote>
 * 职责：发起退款：选择退款方式（原路/平台/余额）、金额（可部分）、原因，校验累计退款不超过可退余额，提交审批后交财务执行。
 * 输入：退款申请；可退余额校验（已退 + 在途）；审批规则。
 * 输出：退款申请单（待审/已批/执行中/成功/失败）+ 可退余额更新。
 * 关系：上游 = 11.1.1.2、11.2.1.1；下游 = 12.2.2.2 付款执行（退款专用业务类型，保留原收款关联）。
 * <b>可退余额校验必须在服务端做</b>——重复提交退款是资损事故的高发点。
 * </blockquote>
 *
 * <p><b>本文件最重要的五件事</b>：
 *
 * <ol>
 *   <li><b>可退余额是一个被算出来的事实，不是请求参数</b>（{@link RefundBalance}）。
 *       发起退款时前端**不能**告诉后端「可退多少」—— 服务端自己查（{@link RefundApplicationService#balance}），
 *       五个占用桶的定义见 V166 第二节，逐桶单列而不是给一个总数：
 *       「可退 0」与「可退 -50」是完全不同的两种问题。</li>
 *   <li><b>审批规则是可配置的真数据</b>（{@link RefundPolicyView}），而且
 *       <b>没有配置时 fail-closed</b>（{@code requiresDualApproval=true}）——
 *       默认值必须是拦住而不是放过（资损模块的默认值方向）。</li>
 *   <li><b>申请单状态与退款执行状态是两条轴</b>：本模块的 EXECUTING 只说明「已交出去」，
 *       渠道没回执时**停在这里**，不许单方面宣布失败 ——
 *       11.3.1.2 已在 V159 用 UNKNOWN 态与查证闸实现这条，本模块继承它
 *       （{@link RefundDetail#executionState()} 与 {@link RefundDetail#executionStateNote()}）。</li>
 *   <li><b>未交付的下游一律 delivered=false + 归属模块号</b>（{@link RefundRelatedModule}）：
 *       12.2.2.2 付款执行当前未交付，<b>不建影子表</b>。</li>
 *   <li><b>排序是白名单</b>（{@link RefundSort#parse}）：排序键进 SQL 文本，
 *       放任用户传表达式就是一条注入。</li>
 * </ol>
 *
 * <p><b>命名。</b>统一用 {@code RefundApplication*} 前缀，全仓 grep 确认与既有类型零重叠 ——
 * 契约被 codegen 合并进一个扁平文件、同名后者覆盖前者且不报冲突。
 * 与 11.3.1.2 的 {@code RefundStatus*} 是两组不同的类型，**刻意不共用**：
 * 那组跟踪的是「钱退没退出去」，这组跟踪的是「这笔退款申请走到哪一步了」。
 */
public final class RefundApplicationModels {

  private RefundApplicationModels() {}

  /**
   * 可枚举项的展示名。
   *
   * <p>⚠️ 抽成接口只有一个理由：服务层的 {@code parseEnum} 与 {@code distinctCounts}
   * 是泛型方法，{@code <E extends Enum<E>>} 上<b>没有</b> {@code label()} ——
   * 不把这个能力放进类型边界，筛选项词表就只能写字典把 label 再抄一遍，
   * 而抄一遍的那份迟早与枚举本身不同步。
   */
  public interface Labelled {
    String label();
  }

  // ================================================================ 枚举

  /**
   * 退款申请单状态机（V166 第四节）。
   *
   * <p>蓝图输出句点名的五个状态逐字对应：待审 = {@link #PENDING_APPROVAL}、
   * 已批 = {@link #APPROVED} 与 {@link #SECOND_APPROVED}、执行中 = {@link #EXECUTING}、
   * 成功 = {@link #SUCCEEDED}、失败 = {@link #FAILED}。另加 DRAFT / SECOND_APPROVAL_PENDING /
   * REJECTED / CANCELLED 四态补齐两端（详见 V166 第四节）。
   */
  public enum RefundApplicationStatus implements Labelled {
    DRAFT("草稿"),
    PENDING_APPROVAL("待审"),
    APPROVED("已批（单签）"),
    SECOND_APPROVAL_PENDING("待复审"),
    SECOND_APPROVED("已批（双签）"),
    EXECUTING("执行中"),
    SUCCEEDED("成功"),
    FAILED("失败"),
    REJECTED("审批驳回"),
    CANCELLED("已撤销");

    private final String label;

    RefundApplicationStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /**
     * 占用可退余额。
     *
     * <p>⚠️ <b>DRAFT 不占用，REJECTED / FAILED / CANCELLED 也不占用</b> ——
     * 审批驳回说明<b>根本没退</b>，额度要立刻还给售后单，
     * 否则一张被拒的申请会永久占死一块可退余额，运营只能去改库才能再退一次。
     */
    public boolean consumesBalance() {
      return this == PENDING_APPROVAL
          || this == APPROVED
          || this == SECOND_APPROVAL_PENDING
          || this == SECOND_APPROVED
          || this == EXECUTING
          || this == SUCCEEDED;
    }

    /** 终态：不能再流转（DB 触发器同一条纪律）。 */
    public boolean terminal() {
      return this == SUCCEEDED || this == FAILED || this == REJECTED || this == CANCELLED;
    }

    /** 在审批队列里（运营每天的待办）。 */
    public boolean pendingReview() {
      return this == PENDING_APPROVAL || this == SECOND_APPROVAL_PENDING;
    }

    /** 已批、可以交财务执行。 */
    public boolean approved() {
      return this == APPROVED || this == SECOND_APPROVED;
    }
  }

  /**
   * 退款方式：原路 / 平台 / 余额。
   *
   * <p>⚠️ 与 V159 的 {@code refund_execution.platform_refund_method} <b>逐字同构</b>：
   * 「平台退款」这一条会让这笔钱根本不经支付渠道，因此下游要不要做平台同步由它决定。
   * 用两个词表描述同一件事，会出现「申请单说原路、执行记录说平台」这种对不上的行。
   */
  public enum RefundMethod implements Labelled {
    ORIGINAL("原路退回"),
    PLATFORM("平台侧退款"),
    BALANCE("退回余额");

    private final String label;

    RefundMethod(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 退款原因（五类内联词表）。
   *
   * <p>⚠️ 与售后单的 {@code reason_code}（七类）是<b>两个不同的轴</b>：
   * 售后单记的是「货为什么有问题」，这里记的是「这笔钱为什么退出去」。
   */
  public enum RefundReason implements Labelled {
    RETURN_REFUND("退货退款"),
    CANCELLATION("取消订单退款"),
    WRONG_AMOUNT("退款金额有误"),
    GOODWILL("赔付"),
    OTHER("其他");

    private final String label;

    RefundReason(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 执行结果来源。
   *
   * <p>⚠️ {@link #FINANCE_MODULE_12} 在 12 财务交付前<b>没有任何路径能写入</b>（服务层拒绝该取值，
   * 与 V159 对 {@code report_source} 的同一处理）。当前真正能写的是
   * {@link #PLATFORM_SYNC}（平台侧回报）与 {@link #MANUAL_OPERATOR}（人工登记）。
   */
  public enum RefundOutcomeSource implements Labelled {
    FINANCE_MODULE_12("12 财务回报"),
    PLATFORM_SYNC("平台侧回报"),
    MANUAL_OPERATOR("人工登记");

    private final String label;

    RefundOutcomeSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 排序白名单（照抄 RmaSort 的同一纪律）。
   *
   * <p>⚠️ 每一条都必须带表别名 {@code r.}：列表查询里 {@code refund_application} 被别名成 {@code r}，
   * 而 PostgreSQL <b>不允许在 ORDER BY 里用原表名</b>，报出来的是一句
   * 「invalid reference to FROM-clause entry」而不是「别名写错了」。
   */
  public enum RefundSort {
    CREATED_DESC("r.created_at desc, r.id desc"),
    AMOUNT_DESC("r.refund_amount desc, r.created_at desc"),
    STATUS_ASC("r.application_status asc, r.created_at desc"),
    SUBMITTED_ASC("r.submitted_at asc nulls last, r.created_at desc");

    private final String sql;

    RefundSort(String sql) {
      this.sql = sql;
    }

    public String sql() {
      return sql;
    }

    public static RefundSort parse(String raw) {
      if (raw == null) {
        return CREATED_DESC;
      }
      return switch (raw) {
        case "created_desc" -> CREATED_DESC;
        case "amount_desc" -> AMOUNT_DESC;
        case "status_asc" -> STATUS_ASC;
        case "submitted_asc" -> SUBMITTED_ASC;
        default -> CREATED_DESC;
      };
    }
  }

  // ================================================================ 可退余额（蓝图加粗句的落点）

  /**
   * 可退余额校验结果 —— 蓝图加粗句「<b>可退余额校验必须在服务端做</b>」的落点。
   *
   * <p>⚠️ <b>五个占用桶逐个单列，而不是只给一个合计</b>：运营看到「可退 0」时必须能立刻答出
   * 「是已经退掉了、还是有一笔在途、还是被对账争议卡住了」——
   * 三种情况的补救动作完全不同，合成一个数就等于把排查成本推给财务去翻流水。
   *
   * <p>⚠️ {@link #availableAmount()} <b>允许为负</b>且不截断成 0：
   * 截断成 0 之后「超了 50 块」与「正好退光了」在界面上完全一样，
   * 而前者要查账、后者只要再退一笔。
   */
  public record RefundBalance(
      UUID caseId,
      String caseNo,
      String caseType,
      String caseStatus,
      UUID orderId,
      @Schema(nullable = true) String orderNo,
      String currency,
      BigDecimal baseAmount,
      BigDecimal settledAmount,
      BigDecimal dispatchedAmount,
      BigDecimal registeredAmount,
      BigDecimal pendingAmount,
      BigDecimal disputedAmount,
      BigDecimal occupiedAmount,
      BigDecimal availableAmount,
      boolean refundable,
      List<String> blockingReasons,
      String balanceSource,
      OffsetDateTime evaluatedAt) {}

  // ================================================================ 审批规则

  /**
   * 审批规则的对外形状。
   *
   * <p>⚠️ {@link #policySource()} = {@code DEFAULT_NO_POLICY} 时，
   * {@link #requiresDualApproval()} <b>恒为 true</b>（fail-closed）——
   * 界面必须能把这一点显示出来，而不是让人以为「系统用的是 0 阈值」。
   */
  public record RefundPolicyView(
      String currency,
      String policyStatus,
      BigDecimal dualApprovalThreshold,
      boolean requiresDualApproval,
      String policySource,
      @Schema(nullable = true) String note,
      @Schema(nullable = true) OffsetDateTime effectiveFrom,
      @Schema(nullable = true) UUID updatedBy,
      @Schema(nullable = true) OffsetDateTime updatedAt) {}

  // ================================================================ 入参

  /** 发起一张退款申请（草稿）。金额会在**建单这一刻**就过一次可退余额校验。 */
  public record RefundCreate(
      UUID caseId,
      @Schema(nullable = true) UUID rmaId,
      String refundMethod,
      BigDecimal refundAmount,
      String currency,
      String refundReason,
      @Schema(nullable = true) String refundReasonNote,
      @Schema(nullable = true) String originalPaymentRef,
      @Schema(nullable = true) String platformRefundRef) {}

  /**
   * 改草稿要素。
   *
   * <p><b>只有 DRAFT 可改</b>：已提交的申请是一份待审的凭据，
   * 审批人看到的金额必须与他当时点「同意」的那个数字逐字相同（留痕里冻了金额快照）。
   */
  public record RefundUpdate(
      String refundMethod,
      BigDecimal refundAmount,
      String currency,
      String refundReason,
      @Schema(nullable = true) String refundReasonNote,
      @Schema(nullable = true) String originalPaymentRef,
      @Schema(nullable = true) String platformRefundRef,
      Long expectedVersion) {}

  /** 提交审批：把草稿推入待审，并在**这一刻重算一次**可退余额。 */
  public record RefundSubmit(Long expectedVersion) {}

  /**
   * 审批决定：同意 / 驳回。
   *
   * <p>同意时落到 {@code APPROVED} 还是 {@code SECOND_APPROVAL_PENDING} 由<b>审批规则</b>决定，
   * 调用方不能指定；驳回必须写理由（DB CHECK 兜底）。
   */
  public record RefundDecision(
      boolean approve, @Schema(nullable = true) String note, Long expectedVersion) {}

  /**
   * 交财务执行（蓝图「提交审批后交财务执行」）。
   *
   * @param executionId **必填**：必须指向一条已存在的退款执行记录（11.3.1.2 / V159）。
   *     本模块<b>不创建</b>它（每张事实表只有一个写入所有者），只引用。
   */
  public record RefundDispatch(UUID executionId, Long expectedVersion) {}

  /**
   * 登记执行结果：成功 / 失败。
   *
   * @param externalTransactionRef 外部交易号：成功时必填（「确认付了」却给不出交易号，
   *     这条结果就没有可复核的凭据）
   * @param failureReason 失败时必填（DB CHECK 兜底）
   */
  public record RefundExecutionOutcome(
      String result,
      String source,
      @Schema(nullable = true) String externalTransactionRef,
      @Schema(nullable = true) String failureReason,
      Long expectedVersion) {}

  /** 人工撤销（撤回 / 作废）。终态不可撤销 —— 已经退出去的钱撤不回来。 */
  public record RefundCancel(String reason, Long expectedVersion) {}

  /** 维护审批规则（租户可维护的真表，V166 第六节）。 */
  public record RefundPolicyUpsert(
      String currency,
      BigDecimal dualApprovalThreshold,
      @Schema(nullable = true) String note,
      @Schema(nullable = true) OffsetDateTime effectiveFrom) {}

  /** 列表查询。 */
  public record RefundListQuery(
      @Schema(nullable = true) Integer page,
      @Schema(nullable = true) Integer size,
      @Schema(nullable = true) String status,
      @Schema(nullable = true) String method,
      @Schema(nullable = true) String reason,
      @Schema(nullable = true) UUID caseId,
      @Schema(nullable = true) Boolean pendingReviewOnly,
      @Schema(nullable = true) String keyword,
      @Schema(nullable = true) String sort) {}

  // ================================================================ 出参

  /** 退款申请单头（列表与详情共用同一形状）。 */
  public record RefundHead(
      UUID id,
      String applicationNo,
      UUID caseId,
      @Schema(nullable = true) String caseNo,
      @Schema(nullable = true) UUID rmaId,
      @Schema(nullable = true) String rmaNo,
      UUID orderId,
      @Schema(nullable = true) String orderNo,
      String applicationStatus,
      String applicationStatusLabel,
      String refundMethod,
      String refundMethodLabel,
      BigDecimal refundAmount,
      String currency,
      String refundReason,
      String refundReasonLabel,
      @Schema(nullable = true) String refundReasonNote,
      @Schema(nullable = true) String originalPaymentRef,
      @Schema(nullable = true) String platformRefundRef,
      @Schema(nullable = true) UUID submittedBy,
      @Schema(nullable = true) OffsetDateTime submittedAt,
      @Schema(nullable = true) UUID firstApprovedBy,
      @Schema(nullable = true) OffsetDateTime firstApprovedAt,
      @Schema(nullable = true) UUID secondApprovedBy,
      @Schema(nullable = true) OffsetDateTime secondApprovedAt,
      @Schema(nullable = true) String approvalNote,
      @Schema(nullable = true) UUID dispatchedBy,
      @Schema(nullable = true) OffsetDateTime dispatchedAt,
      @Schema(nullable = true) UUID executionId,
      @Schema(nullable = true) String executionNo,
      @Schema(nullable = true) String outcomeSource,
      @Schema(nullable = true) UUID outcomeRecordedBy,
      @Schema(nullable = true) OffsetDateTime outcomeRecordedAt,
      @Schema(nullable = true) String failureReason,
      @Schema(nullable = true) String cancelReason,
      UUID createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      long version) {}

  /** 列表行。金额是<b>这一笔</b>的金额，可退余额不在行里（那是逐单实时算的，见 {@link RefundBalance}）。 */
  public record RefundRow(
      RefundHead head,
      String timelinessState,
      @Schema(nullable = true) Long remainingSeconds,
      BigDecimal refundableAmount) {}

  /** 列表汇总：<b>不受分页影响</b>（独立 COUNT）。 */
  public record RefundSummary(
      long total,
      long draft,
      long pendingApproval,
      long secondApprovalPending,
      long approved,
      long executing,
      long succeeded,
      long failed,
      long rejected,
      long cancelled,
      BigDecimal pendingAmount,
      BigDecimal executingAmount) {}

  public record RefundPage(
      List<RefundRow> rows,
      int page,
      int size,
      long total,
      RefundSummary summary,
      OffsetDateTime evaluatedAt,
      String paginationNote,
      String pendingNote,
      String balanceNote,
      String sortNote) {}

  /** 审批与状态留痕（追加式，DB 触发器按状态变更自动追加，运行时角色无权改删）。 */
  public record RefundEvent(
      UUID id,
      String eventType,
      String eventTypeLabel,
      @Schema(nullable = true) String fromStatus,
      String toStatus,
      BigDecimal amountSnapshot,
      String currencySnapshot,
      @Schema(nullable = true) String reasonNote,
      UUID occurredBy,
      OffsetDateTime occurredAt) {}

  /** 筛选项词表（来自真实数据 DISTINCT，不是写死的常量数组）。 */
  public record RefundApplicationFilterOptions(
      List<String> statuses,
      List<String> methods,
      List<String> reasons,
      List<FilterOption> currencies,
      long distinctCount) {}

  /** 单个可选项（值 + 出现次数），供界面显示「还有多少单是这个值」。 */
  public record FilterOption(String value, @Schema(nullable = true) String label, long count) {}

  /**
   * 单张退款申请单全景。
   *
   * @param executionState 关联执行记录的状态（11.3.1.2 / V159，同库只读）。<b>本申请没有派发时为 null</b>。
   * @param executionStateNote 为什么申请状态与执行状态可能不一致 ——
   *     最典型的是执行记录停在 {@code UNKNOWN} 而申请单也停在「执行中」：
   *     渠道没回执时<b>不许</b>把它当成失败（蓝图加粗句，V159 已实现，本模块继承）。
   */
  public record RefundDetail(
      RefundHead head,
      RefundBalance balance,
      RefundPolicyView policy,
      @Schema(nullable = true) String executionState,
      @Schema(nullable = true) String externalTransactionRef,
      String executionStateNote,
      List<RefundEvent> events,
      List<RefundActionEntry> actions,
      RefundSectionCapability refundableCheck,
      RefundSectionCapability financeExecution) {}

  /** 职责句的某个区块是否可用；不可用时必须带归属模块号与原因。 */
  public record RefundSectionCapability(
      String section,
      String label,
      boolean available,
      @Schema(nullable = true) String ownerModule,
      @Schema(nullable = true) String reason,
      @Schema(nullable = true) String landsIn) {}

  /** 关联模块可用性。 */
  public record RefundRelatedModule(
      String key,
      String label,
      boolean delivered,
      @Schema(nullable = true) String ownerModule,
      @Schema(nullable = true) String reason,
      @Schema(nullable = true) String note) {}

  /** 本模块**刻意不做**的事。 */
  public record RefundBoundary(
      String boundary, String ownerModule, String reason) {}

  /** 操作入口。不可用时把理由原样带出来（按钮变灰而不给理由会让人以为系统坏了）。 */
  public record RefundActionEntry(
      String action,
      String label,
      boolean allowed,
      @Schema(nullable = true) String method,
      @Schema(nullable = true) String path,
      boolean requiresReason,
      @Schema(nullable = true) String blockingReason,
      @Schema(nullable = true) String ownerModule,
      @Schema(nullable = true) String unavailableReason) {}

  /** 能力声明。 */
  public record RefundCapabilities(
      List<String> inputs,
      List<RefundSectionCapability> sections,
      List<RefundRelatedModule> relatedModules,
      List<RefundBoundary> boundaries,
      String stateMachine,
      String balanceRule,
      String approvalRule,
      String executionNote) {}
}
