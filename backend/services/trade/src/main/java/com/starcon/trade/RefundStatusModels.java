package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 11.3.1.2 退款状态的传输模型与**状态机**。
 *
 * <p>蓝图 11-aftersales-and-returns.md 原文：
 *
 * <blockquote>
 * 职责：跟踪退款执行状态与平台同步状态，处理退款失败、外部超时（先查证交易再重试，禁止重复支付）、平台退款与系统退款不一致的差异。
 * 输入：12 财务的执行结果回报；平台侧退款查询。
 * 输出：退款状态 + 失败原因 + 重试/人工处理建议 + 差异清单。
 * 关系：上游 = 12 财务；下游 = 15 客服（通知买家）、9 订单（订单退款状态）。
 * <b>外部退款超时不等于失败</b>，重复发起退款会造成实质资损。
 * </blockquote>
 *
 * <p><b>本文件是本模块最重要的一份文件</b>：蓝图那句加粗不是文案要求，是<b>正确性要求</b>，
 * 而它的全部表达就是 {@link RefundExecutionState} + {@link RefundExecutionState#allowedNext()}
 * + {@link RefundRetryAdvice#advise} 三者。任何绕过这三处直接改状态的代码都是资损缺陷，
 * 而 DB 层（V159 的 {@code guard_refund_attempt_retry} 触发器 + 两条 CHECK + 幂等唯一约束）
 * 会把绕过它的尝试挡下来。
 *
 * <p><b>「超时不等于失败」在类型层面的表达</b>：{@link RefundExecutionState#UNKNOWN} 是独立状态，
 * 它的建议恒为 {@link RefundAction#VERIFY} —— <b>不是</b> RETRY。
 * 而 {@link RefundExecutionState#FAILED} 只接受 {@link FailureClass#DEFINITIVE}（V159 的
 * {@code refund_execution_failure_is_definitive} CHECK 钉死），也就是「钱确定没有出去」。
 * 于是「超时」在类型上<b>装不进</b> FAILED：想表达拿不准，只能用 UNKNOWN，
 * 而 UNKNOWN 的建议就是去查证，不是去重发。
 *
 * <p><b>命名。</b>统一用 {@code Refund*} 前缀，全仓 grep 确认与既有类型零重叠 ——
 * 契约被 codegen 合并进一个扁平文件、同名后者覆盖前者且不报冲突。
 * （注意：V119 的 {@code order_change_cancellation} 上有 {@code refund_amount} **列名**，
 * 与本文件的类型名不冲突。）
 */
public final class RefundStatusModels {

  private RefundStatusModels() {}

  // ================================================================ 状态机

  /**
   * 退款执行状态（六态）。
   *
   * <p>{@link #UNKNOWN} 是本模块存在的理由。刻意把它与 {@link #FAILED} 分成两个状态而不是合并：
   * 合并后「超时」就只能落进 FAILED，而 FAILED 的建议是可重试 —— 那就是重复退款。
   */
  public enum RefundExecutionState {
    /** 退款尚未发起：只有退款额与幂等键，还没有对外派发过。 */
    NOT_INITIATED("未发起"),
    /** 已派发、等待外部结果（正常在途）。 */
    IN_FLIGHT("已发起待结果"),
    /** 已发起但<b>结果未知</b>（外部超时）。不是失败，不可直接重试。 */
    UNKNOWN("结果未知"),
    /** 外部/平台确认成功。 */
    SUCCEEDED("已退款"),
    /** 外部<b>明确</b>失败（钱确定没有出去）。 */
    FAILED("已明确失败"),
    /** 已对账差异（需人工裁定）。 */
    DISCREPANCY("对账差异");

    private final String label;

    RefundExecutionState(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /**
     * 合法目标状态集合。<b>与 V159 的 {@code refund_transition_allowed()} 逐条对齐</b>，
     * 集成测试 {@code 两侧可转换集合完全一致} 同时比对 DB 函数与本方法。
     *
     * <p>两处实现刻意并存：Java 侧挡住 API 误用并给出人话错误，DB 侧挡住绕过应用的写入。
     * 若只留一处，绕过应用的那条路就裸奔；若两处漂移，测试会红。
     */
    public Set<RefundExecutionState> allowedNext() {
      return switch (this) {
        case NOT_INITIATED -> Set.of(IN_FLIGHT);
        // IN_FLIGHT 也能进差异：平台侧对账可能在我们还在等回执时就查出一笔不一致的退款。
        case IN_FLIGHT -> Set.of(SUCCEEDED, FAILED, UNKNOWN, DISCREPANCY);
        // ⚠️ UNKNOWN → FAILED 存在，但**必须**经由一条 CONFIRMED_NOT_SETTLED 的查证
        //   （V159 的触发器 + 本文件 {@link RefundAction#VERIFY} 的建议强制它）。
        //   没有「超时自动变失败」这条路。
        case UNKNOWN -> Set.of(SUCCEEDED, FAILED, IN_FLIGHT, DISCREPANCY);
        case FAILED -> Set.of(IN_FLIGHT, DISCREPANCY);
        case SUCCEEDED -> Set.of(DISCREPANCY);
        case DISCREPANCY -> Set.of(SUCCEEDED, FAILED);
      };
    }

    /** 三个「已出结论」的状态：有 resolved_at（V159 的等价 CHECK 钉死）。 */
    public boolean resolved() {
      return this == SUCCEEDED || this == FAILED || this == DISCREPANCY;
    }

    /** 需要人看一眼的三种状态（UNKNOWN / FAILED / DISCREPANCY）——列表的默认视图。 */
    public boolean needsAttention() {
      return this == UNKNOWN || this == FAILED || this == DISCREPANCY;
    }

    public static RefundExecutionState of(String code) {
      for (RefundExecutionState s : values()) {
        if (s.name().equals(code)) return s;
      }
      throw new IllegalArgumentException("未知的退款执行状态：" + code);
    }
  }

  /** 失败分类：把「失败」再分成确定性与不确定性两半（V159 的 CHECK 钉死 FAILED ⇒ DEFINITIVE）。 */
  public enum FailureClass {
    /** 外部明确拒绝，钱确定没有出去 ⇒ 允许直接重试。 */
    DEFINITIVE("明确失败"),
    /** 超时 / 无响应 / 渠道未回执 ⇒ <b>不许</b>重试，必须先查证。 */
    INDETERMINATE("不确定失败");

    private final String label;

    FailureClass(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 查证结论。<b>三值，没有第四种。</b>
   *
   * <p>刻意不提供「默认当作没付出去」这个便捷取值：查不出来就是查不出来。
   * 把 INCONCLUSIVE 当成 CONFIRMED_NOT_SETTLED，正是重复退款的起点。
   */
  public enum VerificationVerdict {
    /** 确认钱已经出去了 ⇒ 只能推进到 SUCCEEDED，重试就是重复退款。 */
    CONFIRMED_SETTLED("查证：已付出"),
    /** 确认钱没有出去 ⇒ 才允许重试。 */
    CONFIRMED_NOT_SETTLED("查证：未付出"),
    /** 还是不知道（查不到 / 渠道仍未回执）⇒ 必须保持 UNKNOWN。 */
    INCONCLUSIVE("查证：无法确认");

    private final String label;

    VerificationVerdict(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 查证来源（蓝图输入句点名的第二个输入是「平台侧退款查询」）。 */
  public enum VerifySource {
    /** 平台侧退款查询。 */
    PLATFORM_QUERY("平台侧退款查询"),
    /** 12 财务侧执行结果查询。<b>12 财务未交付</b>，当前不可用，见 capabilities。 */
    FINANCE_QUERY("12 财务执行结果查询"),
    /** 人工查证（运营拿支付渠道后台登记）。 */
    MANUAL("人工查证");

    private final String label;

    VerifySource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 外部执行结果回报的结论。
   *
   * <p>{@link #TIMEOUT} 与 {@link #UNKNOWN} <b>都只允许把执行推进到 UNKNOWN</b>，
   * 不允许推进到 FAILED —— 那是蓝图加粗句的直接表达。
   */
  public enum ReportOutcome {
    SUCCEEDED("成功"),
    FAILED("明确失败"),
    TIMEOUT("外部超时"),
    UNKNOWN("结果未知");

    private final String label;

    ReportOutcome(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 回报结论映射到执行状态：<b>超时时落到 UNKNOWN 而不是 FAILED</b>。 */
    public RefundExecutionState toState() {
      return switch (this) {
        case SUCCEEDED -> RefundExecutionState.SUCCEEDED;
        case FAILED -> RefundExecutionState.FAILED;
        case TIMEOUT, UNKNOWN -> RefundExecutionState.UNKNOWN;
      };
    }
  }

  /** 回报来源。FINANCE_MODULE_12 在 12 财务交付前<b>不可用</b>，服务层直接拒（见 capabilities）。 */
  public enum ReportSource {
    /** 12 财务的执行结果回报（蓝图输入句）。<b>未交付</b>。 */
    FINANCE_MODULE_12("12 财务执行回报"),
    /** 平台侧退款查询（蓝图输入句）。 */
    PLATFORM_SYNC("平台侧退款查询"),
    /** 人工登记（运营在支付渠道后台查过之后登记）。 */
    MANUAL_OPERATOR("人工登记");

    private final String label;

    ReportSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 平台同步状态（与执行状态<b>正交</b>的第二条轴）。 */
  public enum PlatformSyncState {
    /** 本笔退款不经过平台（原路退回 / 退回余额）—— 与「还没同步」是两件事。 */
    NOT_REQUESTED("不经平台"),
    /** 已请求平台同步，结果未回。 */
    PENDING("待平台同步"),
    /** 平台已同步且与系统一致。 */
    SYNCED("平台已同步"),
    /** 平台与系统不一致（见差异清单）。 */
    DISCREPANCY("平台不一致");

    private final String label;

    PlatformSyncState(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 平台退款方式（决定要不要走平台同步这条轴）。 */
  public enum PlatformRefundMethod {
    /** 原路退回：走支付渠道，不经平台。 */
    ORIGINAL("原路退回"),
    /** 平台侧退款。 */
    PLATFORM("平台侧退款"),
    /** 退回余额。 */
    BALANCE("退回余额");

    private final String label;

    PlatformRefundMethod(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 重试 / 人工建议

  /**
   * 可执行动作。
   *
   * <p>这是蓝图输出句「重试/人工处理建议」的<b>可枚举</b>形态：建议由状态机推导，
   * <b>不是自由文本</b>。自由文本的致命缺陷是它可以被写成「可重试」而状态是 UNKNOWN ——
   * 运营照着做就是重复退款。
   */
  public enum RefundAction {
    /** 可发起首次派发。 */
    DISPATCH("发起退款"),
    /** 等待外部结果，不要做任何动作。 */
    AWAIT("等待结果"),
    /** <b>先查证交易</b>：结果未知，必须查证后才能决定（绝不可直接重试）。 */
    VERIFY("先查证交易"),
    /** 可重试：钱确定没有出去。 */
    RETRY("可重试"),
    /** 需人工处理（差异裁定 / 平台不一致）。 */
    MANUAL("需人工处理"),
    /** 已是终态，无需任何动作。 */
    NONE("已是终态");

    private final String label;

    RefundAction(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 重试 / 人工处理建议。<b>由状态机推导，不可由调用方传入。</b>
   *
   * @param action 可执行动作（枚举，不是文本）
   * @param label 人话说明
   * @param requiredVerdict 若重试，**必须**先拿到的查证结论；不需要查证时为 null
   * @param blocking 当前状态下<b>被禁止</b>的动作（界面上要显式说明为什么不能重试）
   * @param reason 推导依据的人话说明
   */
  public record RefundRetryAdvice(
      RefundAction action,
      String label,
      VerificationVerdict requiredVerdict,
      String blocking,
      String reason) {}

  /**
   * 建议推导。<b>本模块唯一的建议真身。</b>
   *
   * <p>推导规则逐条对应蓝图：
   *
   * <ul>
   *   <li>{@code NOT_INITIATED} → {@link RefundAction#DISPATCH}；
   *   <li>{@code IN_FLIGHT} → {@link RefundAction#AWAIT}（结果未到就重发 = 重复支付）；
   *   <li>{@code UNKNOWN} → {@link RefundAction#VERIFY}，<b>blocking 明写「禁止直接重试」</b>；
   *   <li>{@code FAILED} → {@link RefundAction#RETRY}（确定性失败，钱确定没动）；
   *   <li>{@code DISCREPANCY} → {@link RefundAction#MANUAL}；
   *   <li>{@code SUCCEEDED} → {@link RefundAction#NONE}。
   * </ul>
   *
   * <p>{@link RefundAction#VERIFY} 要求的查证结论是 {@link VerificationVerdict#CONFIRMED_NOT_SETTLED}：
   * 查证确认钱**没有**出去才谈得上重发。查证结果是 {@code CONFIRMED_SETTLED} 时，
   * 正确动作是推进到 SUCCEEDED（服务端在推进时校验，见 RefundStatusService）。
   */
  public static RefundRetryAdvice advise(RefundExecutionState state) {
    return switch (state) {
      case NOT_INITIATED -> new RefundRetryAdvice(
          RefundAction.DISPATCH, "尚未发起退款，可发起一次带幂等键的派发",
          null, null, "退款只有登记记录，还没有对外派发过");
      case IN_FLIGHT -> new RefundRetryAdvice(
          RefundAction.AWAIT, "已派发、等待外部结果，不要重发",
          null, "重发会造成重复支付",
          "结果还没回来；此时任何重发都会被 DB 触发器拒绝");
      case UNKNOWN -> new RefundRetryAdvice(
          RefundAction.VERIFY, "结果未知：必须先查证交易，查证确认未付出后才谈得上重试",
          VerificationVerdict.CONFIRMED_NOT_SETTLED,
          "超时不等于失败，未查证前禁止重试",
          "外部超时只说明「没拿到回执」，不说明「钱没出去」；"
              + "此时重发就是对同一笔退款发起第二次支付");
      case FAILED -> new RefundRetryAdvice(
          RefundAction.RETRY, "外部明确失败（钱确定没有出去），可重试",
          null, null, "失败分类为 DEFINITIVE：外部明确拒绝，钱确定没动");
      case DISCREPANCY -> new RefundRetryAdvice(
          RefundAction.MANUAL, "平台与系统退款不一致，需人工裁定后再决定",
          null, "差异未裁定前不允许重试或改状态",
          "差异只能由人裁定，系统不自动抹平");
      case SUCCEEDED -> new RefundRetryAdvice(
          RefundAction.NONE, "退款已完成，无需任何动作",
          null, "已是终态，重试会造成重复退款",
          "平台已确认退款成功");
    };
  }

  // ================================================================ 差异清单

  /** 差异类型。EXISTENCE（平台有 / 系统没有，或反过来）是最严重的一类。 */
  public enum DiscrepancyKind {
    AMOUNT("金额不一致"),
    CURRENCY("币种不一致"),
    TIMING("时间不一致"),
    EXISTENCE("存在性不一致");

    private final String label;

    DiscrepancyKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 差异裁定状态。<b>只能由人的动作推进</b>（系统不自动抹平）。 */
  public enum DiscrepancyResolution {
    OPEN("待裁定"),
    ACKNOWLEDGED("已确认待处理"),
    RESOLVED("已裁定");

    private final String label;

    DiscrepancyResolution(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 请求

  /**
   * 登记一笔退款执行（NOT_INITIATED 态）。
   *
   * @param idempotencyKey <b>必填</b>且租户内唯一：重复提交同一键返回既有记录，不创建第二笔
   * @param orderId 关联订单（V99，服务层真查存在性）
   * @param caseId 关联售后单（11.1.1.1，<b>可空</b>：不假设每笔退款都来自售后申请单）
   */
  public record RefundExecutionCreate(
      String idempotencyKey,
      UUID orderId,
      UUID caseId,
      BigDecimal refundAmount,
      String currency,
      PlatformRefundMethod platformRefundMethod,
      String note) {}

  /**
   * 派发（首次或重试）。<b>每次派发都必须带幂等键</b>。
   *
   * @param idempotencyKey 派发幂等键：与退款业务键<b>不同</b>，标识「这是第几次派发」
   * @param verificationId 重试时<b>必填</b>的查证记录；首次派发必须为 null
   * @param dispatchedAt 派发时刻（{@code ?::timestamptz} + OffsetDateTime）
   */
  public record RefundDispatchRequest(
      String idempotencyKey,
      UUID verificationId,
      OffsetDateTime dispatchedAt,
      String dispatchChannel,
      String note) {}

  /**
   * 登记一次查证（「先查证交易」的那一步）。
   *
   * @param transactionRef 被查证的交易号：必须具体，「大概是哪一笔」不算查证
   * @param verifiedAt 查证时刻（{@code ?::timestamptz} + OffsetDateTime）
   */
  public record RefundVerificationRequest(
      VerifySource verifySource,
      VerificationVerdict verdict,
      String transactionRef,
      String note,
      OffsetDateTime verifiedAt) {}

  /**
   * 登记一条外部执行结果回报。
   *
   * @param reportedAmount 声称成功时<b>必填</b>（没有金额的成功无法与差异清单对账）
   * @param reportedAt 外部<b>说的</b>时刻（与 received_at 不同：网络延迟会让两者不同）
   */
  public record RefundReportRequest(
      ReportSource reportSource,
      ReportOutcome reportOutcome,
      FailureClass failureClass,
      String externalTransactionRef,
      BigDecimal reportedAmount,
      String reportedCurrency,
      String failureCode,
      String failureReason,
      OffsetDateTime reportedAt) {}

  /**
   * 登记一条对账差异。<b>如实报，不自动抹平。</b>
   *
   * <p>按 {@link RefundDiscrepancyCreate#discrepancyKind()} 钉死该有的证据（V159 的
   * {@code refund_discrepancy_kind_has_evidence} CHECK 同时在 DB 层钉死）。
   */
  public record RefundDiscrepancyCreate(
      DiscrepancyKind discrepancyKind,
      BigDecimal systemAmount,
      String systemCurrency,
      RefundExecutionState systemState,
      OffsetDateTime systemRefundedAt,
      BigDecimal platformAmount,
      String platformCurrency,
      RefundExecutionState platformState,
      OffsetDateTime platformRefundedAt,
      String detail,
      OffsetDateTime detectedAt) {}

  /** 人工裁定差异。只有人的动作能调用它。 */
  public record RefundDiscrepancyResolutionRequest(
      DiscrepancyResolution resolutionState, String resolutionNote, OffsetDateTime resolvedAt) {}

  /** 平台同步结果回报（蓝图「平台退款与系统退款不一致」的检测输入）。 */
  public record RefundPlatformSyncRequest(
      PlatformSyncState platformSyncState,
      String platformRefundNo,
      BigDecimal platformAmount,
      String platformCurrency,
      OffsetDateTime platformRefundedAt,
      String note) {}

  /** 列表查询条件。每个条件都对应一个<b>真实存在</b>的列 / 索引 / DB 函数。 */
  public record RefundExecutionQuery(
      String executionState,
      String platformSyncState,
      String platformRefundMethod,
      String currency,
      UUID orderId,
      UUID caseId,
      String orderNo,
      String refundNo,
      String failureCode,
      String minAmount,
      String maxAmount,
      String onlyNeedsAttention,
      String sort,
      Integer page,
      Integer size) {}

  // ================================================================ 响应

  /** 列表行。 */
  public record RefundExecutionRow(
      UUID id,
      String refundNo,
      String executionState,
      String executionStateLabel,
      String failureClass,
      String failureCode,
      String failureReason,
      BigDecimal refundAmount,
      String currency,
      String platformSource,
      String orderNo,
      UUID orderId,
      UUID caseId,
      String platformRefundMethod,
      String platformSyncState,
      String platformRefundNo,
      String externalTransactionRef,
      String action,
      String actionLabel,
      String actionBlocking,
      String actionReason,
      String requiredVerdict,
      boolean needsAttention,
      int attemptCount,
      int openDiscrepancyCount,
      OffsetDateTime createdAt,
      OffsetDateTime initiatedAt,
      OffsetDateTime lastVerifiedAt,
      OffsetDateTime resolvedAt) {}

  /** 派发流水行。 */
  public record RefundAttemptRow(
      UUID id,
      String attemptNo,
      String attemptKind,
      String idempotencyKey,
      UUID verificationId,
      String stateAtDispatch,
      String dispatchChannel,
      OffsetDateTime dispatchedAt) {}

  /** 查证记录行。 */
  public record RefundVerificationRow(
      UUID id,
      String verifySource,
      String verdict,
      String verdictLabel,
      String transactionRef,
      String note,
      OffsetDateTime verifiedAt) {}

  /** 外部回报行。 */
  public record RefundReportRow(
      UUID id,
      String reportSource,
      String reportOutcome,
      String failureClass,
      String externalTransactionRef,
      BigDecimal reportedAmount,
      String reportedCurrency,
      String failureCode,
      String failureReason,
      OffsetDateTime reportedAt,
      OffsetDateTime receivedAt) {}

  /** 差异清单行（**两边原值都保留** —— 只报「不一致」而不报两边是什么，运营无法判断该改哪边）。 */
  public record RefundDiscrepancyRow(
      UUID id,
      UUID executionId,
      String refundNo,
      String discrepancyKind,
      String discrepancyKindLabel,
      BigDecimal systemAmount,
      String systemCurrency,
      String systemState,
      OffsetDateTime systemRefundedAt,
      BigDecimal platformAmount,
      String platformCurrency,
      String platformState,
      OffsetDateTime platformRefundedAt,
      String detail,
      String resolutionState,
      String resolutionNote,
      OffsetDateTime resolvedAt,
      UUID resolvedBy,
      OffsetDateTime detectedAt) {}

  /** 状态事件行。 */
  public record RefundStatusEventRow(
      UUID id,
      String eventType,
      String fromState,
      String toState,
      String detail,
      UUID verificationId,
      UUID reportId,
      UUID discrepancyId,
      OffsetDateTime occurredAt) {}

  /**
   * 退款详情：头 + 派发历史 + 查证历史 + 外部回报 + 差异 + 状态事件 + <b>当前建议</b>。
   *
   * @param allowedNextStates 当前状态的合法目标状态（前端据此决定显示哪些动作按钮）
   * @param advice 当前的重试 / 人工处理建议
   */
  public record RefundExecutionDetail(
      RefundExecutionRow header,
      List<RefundAttemptRow> attempts,
      List<RefundVerificationRow> verifications,
      List<RefundReportRow> reports,
      List<RefundDiscrepancyRow> discrepancies,
      List<RefundStatusEventRow> events,
      List<String> allowedNextStates,
      RefundRetryAdvice advice,
      String platformNote,
      String idempotencyNote) {}

  /** 差异清单分页页。与分页无关的全量汇总单列。 */
  public record RefundDiscrepancyPage(
      List<RefundDiscrepancyRow> rows,
      int page,
      int size,
      long total,
      long openCount,
      long acknowledgedCount,
      long resolvedCount,
      Map<String, Long> byKind,
      Map<String, BigDecimal> systemAmountByKind,
      Map<String, BigDecimal> platformAmountByKind) {}

  /** 列表分页页。汇总按币种分开（跨币种相加是财务事故的标准起点）。 */
  public record RefundExecutionPage(
      List<RefundExecutionRow> rows,
      int page,
      int size,
      long total,
      long needsAttentionCount,
      long unknownCount,
      long inFlightCount,
      long failedCount,
      long discrepancyCount,
      Map<String, Long> countByState,
      Map<String, BigDecimal> amountByCurrency,
      String amountRangeSemantics,
      String sortApplied) {}

  /** 筛选项下拉（**来自真实数据的词表**，不是硬编码常量列表）。 */
  public record RefundFilterOptions(
      List<String> executionStates,
      List<String> platformSyncStates,
      List<String> platformRefundMethods,
      List<String> currencies,
      List<String> failureCodes,
      List<String> orderSources,
      long totalCount) {}

  // ================================================================ capabilities

  /**
   * 能力声明：状态机真身 + 上下游交付情况 + 每个未交付对接口的归属模块号。
   *
   * <p>蓝图说上游是 12 财务、下游是 15 客服与 9 订单。<b>这三个全部未交付</b>
   * （依赖实查结论见 V159 文件头第零节）⇒ 这里逐条报 {@code delivered=false} 与
   * {@code ownerModule}，<b>不假装已对接</b>、不建恒空的影子表、不返回空集冒充成功。
   */
  public record RefundStatusCapabilities(
      List<RefundStateTransitionSpec> stateMachine,
      List<String> failureClasses,
      List<String> verificationVerdicts,
      List<String> discrepancyKinds,
      List<String> platformSyncStates,
      RefundUpstream upstream,
      RefundDownstream downstream,
      List<RefundActionSpec> actions,
      List<String> idempotencyGuarantees,
      String unknownStateNote,
      String retryGuardNote,
      String discrepancyNote,
      String shopGranularityNote) {}

  /**
   * 状态机的一条转换规则（供前端渲染「能做什么 / 不能做什么」）。
   *
   * @param requiresVerification 该转换是否<b>必须</b>先有一条查证记录
   * @param allowedVerdict 该转换要求的查证结论（不需要查证时为 null）
   */
  public record RefundStateTransitionSpec(
      String from,
      String fromLabel,
      String to,
      String toLabel,
      boolean requiresVerification,
      String allowedVerdict,
      String note) {}

  /** 上游 12 财务：全部未交付。 */
  public record RefundUpstream(
      boolean financeDelivered,
      String financeOwner,
      String financeNote,
      boolean refundRequestDelivered,
      String refundRequestOwner,
      String refundRequestNote,
      String reportLandingNote) {}

  /** 下游 15 客服 / 9 订单：两者都未交付本模块所需的对接。 */
  public record RefundDownstream(
      boolean customerNotifyDelivered,
      String customerNotifyOwner,
      String customerNotifyNote,
      boolean orderStatusWritebackDelivered,
      String orderStatusWritebackOwner,
      String orderStatusWritebackNote,
      boolean platformSyncDelivered,
      String platformSyncOwner,
      String platformSyncNote) {}

  /**
   * 一个动作的可用性。
   *
   * @param available 当前是否可用（不可用时 {@code reason} 说明为什么）
   * @param guard 挡在它前面的护栏（V159 的触发器 / CHECK / 唯一约束）
   */
  public record RefundActionSpec(
      String action, String label, boolean available, String guard, String reason, String ownerModule) {}

  /** 平台维度只有平台级的说明（照抄 V156 的同一条纪律：不建恒为空的 shop 列）。 */
  public record RefundShopGranularity(String note, String ownerModule) {}
}
