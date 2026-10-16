package com.starcon.trade;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 10.2.1.3 改址与取消的契约形状。
 *
 * <p>风格照抄 10.2.1.2（{@code WaybillModels}）与 10.2.1.1（{@code WaybillListingModels}）：
 * 枚举与 record 同文件，输入用 {@code @Valid} record，响应是不可变 record。
 *
 * <p>⚠️ 载荷一律用<b>具名 record</b> 而不是 {@code Map<String,Object>}：Map 的值类型
 * 会被 springdoc 推成 {@code object}，codegen 出空对象 {@code {}}（9.6.1.3 已踩过）。
 */
final class WaybillRedirectModels {
  private WaybillRedirectModels() {}

  // ================================================================ 枚举

  /**
   * 承运商那一跳的结果。
   *
   * <p>⚠️ 刻意只有「受理 / 拒收 / 不可达 / 渠道未接通」四种，
   * <b>没有</b> {@code PENDING} 之类的中间态：本模块<b>同步</b>提交并记录结果，
   * 「还在等承运商异步回执」这件事本模块**不假装知道** ——
   * 10.3 物流跟踪未交付，没有可信的异步回执数据源（照抄 10.2.1.1 对
   * 「已揽收 / 在途 / 已签收 / 异常」四个态的处理：<b>没有数据源就不产出</b>）。
   */
  enum CarrierOutcome {
    /** 承运商<b>真实受理</b>。这是唯一能让界面显示「改址成功」的值。 */
    ACCEPTED("已受理"),
    /** 承运商明确拒绝（地址不可达 / 票已揽收 / 票号错误）。 */
    REJECTED("已拒收"),
    /** 承运商不可达（超时 / 5xx）。与「拒收」是**两回事**，重试有意义。 */
    CARRIER_UNAVAILABLE("承运商不可达"),
    /** 渠道未接通（10.1 未交付，系统里没有任何 vendor 实现）。 */
    ADAPTER_MISSING("渠道未接通");

    public final String displayName;

    CarrierOutcome(String displayName) {
      this.displayName = displayName;
    }
  }

  /**
   * 改址 / 取消的<b>时限与规则判定</b>（维度一）。
   *
   * <p>这正是蓝图加粗句「超时的改址请求要明确告知能否成功而不是让客服猜」的落点：
   * 四个值互斥且穷尽，每一个都有<b>可解释</b>的语义，客服照着就能回答买家。
   */
  enum DeadlineVerdict {
    /** 在时限内 —— 还有机会，但成不成还要看渠道通不通（{@code can_succeed} 说了算）。 */
    WITHIN_WINDOW("在改址时限内"),
    /** 已超时限 —— <b>再也不会成功</b>，不是重试一下就能过。这是客服最需要一句准话的场景。 */
    DEADLINE_PASSED("已超过渠道时限"),
    /** 该渠道不支持改址 / 取消（登记在规则表里）。 */
    CHANNEL_UNSUPPORTED("该渠道不支持此操作"),
    /** 该渠道**没有登记规则** —— 系统不知道它支持不支持，如实报「不知道」而不是猜。 */
    RULE_UNREGISTERED("未登记该渠道的规则");

    public final String displayName;

    DeadlineVerdict(String displayName) {
      this.displayName = displayName;
    }

    /** 这个判定是不是「已经没戏了」（客服话术要分的第一类）。 */
    public boolean hopeless() {
      return this == DEADLINE_PASSED || this == CHANNEL_UNSUPPORTED;
    }
  }

  /**
   * **落库**的渠道操作结果（与 V135 的 {@code outcome} 列 CHECK 逐字对齐）。
   *
   * <p>刻意与 {@link CarrierOutcome} <b>分成两个枚举</b>：端口那一跳的返回值有四种
   * （含两个<b>瞬时</b>态：承运商不可达、渠道未接通），而<b>落库</b>只有三种 ——
   * 不可达 / 未接通都<b>不是</b>「已受理」，如实落成 {@link #NOT_ATTEMPTED}
   * （没有受理凭据），具体原因写进 {@code outcome_reason} 原文。
   * 把瞬时态也存进 {@code outcome} 列会让「界面显示已受理」与「渠道没接通」混成一样。
   */
  enum StoredOutcome {
    /** 没有受理凭据：未派发、渠道未接通、承运商不可达。原因见 outcome_reason。 */
    NOT_ATTEMPTED("未受理"),
    /** 承运商<b>真实受理</b>。这是唯一能让界面显示「改址成功」的值。 */
    ACCEPTED("已受理"),
    /** 承运商明确拒绝（地址不可达 / 票已揽收 / 票号错误）。 */
    REJECTED("已拒收");

    public final String displayName;

    StoredOutcome(String displayName) {
      this.displayName = displayName;
    }
  }

  /**
   * 买家通知任务的通知类型（**不是** 15 的模板 ID —— 模板归 15 所有）。
   */
  enum NotificationKind {
    REDIRECT_ACCEPTED("改址已受理"),
    REDIRECT_REJECTED("改址被拒"),
    REDIRECT_EXPIRED("改址已超时限"),
    REDIRECT_UNSUPPORTED("该渠道不支持改址"),
    CANCEL_ACCEPTED("取消已受理"),
    CANCEL_REJECTED("取消被拒"),
    CANCEL_EXPIRED("取消已超时限"),
    CANCEL_UNSUPPORTED("该渠道不支持取消");

    public final String displayName;

    NotificationKind(String displayName) {
      this.displayName = displayName;
    }
  }

  // ================================================================ 能力声明

  /** 能力维度。{@code available=false} 必须点名 ownerModule。 */
  public record RedirectCapabilityDimension(
      String dimension, String description, boolean available, String source,
      String ownerModule, String impact) {}

  public record WaybillRedirectCapabilities(
      List<RedirectCapabilityDimension> dimensions,
      List<String> verdicts,
      List<String> carrierOutcomes,
      List<String> notificationKinds,
      String verdictRule,
      String deadlineRule,
      String boundaryNote) {}

  // ================================================================ 渠道规则

  public record CarrierRedirectRuleRow(
      UUID id,
      String carrierCode,
      String carrierChannel,
      boolean redirectSupported,
      Integer redirectDeadlineHours,
      boolean cancelSupported,
      Integer cancelDeadlineHours,
      String unsupportedReason,
      String remark,
      UUID registeredByMembershipId,
      OffsetDateTime registeredAt) {}

  public record CarrierRedirectRulePage(
      List<CarrierRedirectRuleRow> items, long totalElements) {}

  // ================================================================ 改址单

  /**
   * 一条改址单。
   *
   * <p>{@code canSucceed} + {@code canSucceedReason} 是本模块<b>最要紧</b>的两个字段：
   * 它们把「到底能不能改」变成一句客服可以直接念给买家的准话，
   * 而不是让客服从一个异常堆栈里猜（蓝图加粗句点名要避免的就是这个）。
   */
  public record WaybillRedirectRow(
      UUID id,
      String redirectNo,
      UUID requestId,
      String requestNo,
      String waybillNo,
      String carrierCode,
      String carrierChannel,
      OffsetDateTime issuedAtSnapshot,
      OffsetDateTime deadlineAt,
      Long minutesToDeadline,
      String recipientName,
      String recipientCountry,
      String addressLine,
      String city,
      String postalCode,
      String reason,
      DeadlineVerdict verdict,
      String verdictLabel,
      boolean canSucceed,
      String canSucceedReason,
      StoredOutcome outcome,
      String outcomeLabel,
      String carrierRef,
      String outcomeReason,
      OffsetDateTime dispatchedAt,
      UUID orderChangeRequestId,
      String salesOrderNo,
      String orderChangeStatus,
      String orderChangeNote,
      UUID notificationTaskId,
      String notificationNo,
      OffsetDateTime requestedAt,
      OffsetDateTime updatedAt,
      String nextAction) {}

  public record WaybillRedirectPage(
      List<WaybillRedirectRow> items, long total, long withinWindow, long deadlinePassed,
      long channelUnsupported, long ruleUnregistered, long canSucceedCount,
      long acceptedCount, long rejectedCount, long notAttemptedCount,
      long pendingNotificationCount, List<String> notes) {}

  /** 单条改址的详情：单据 + 联动的订单变更 + 通知任务 + 可执行动作。 */
  public record WaybillRedirectDetail(
      WaybillRedirectRow redirect,
      WaybillRedirectCarrierState carrierState,
      List<String> nextActions,
      List<String> boundaryNotes) {}

  /**
   * 承运商侧对本单的口径（改址 / 取消各一列）。
   *
   * <p>刻意把「规则登记」与「适配器是否接通」<b>分成两列</b>：
   * 登记说支持、但适配器没接通时，这一票是「规则允许但现在发不出去」，
   * 画成同一个「可用」会把两种完全不同的处境混成一个绿。
   */
  public record WaybillRedirectCarrierState(
      String carrierCode,
      boolean registeredInCarrierMaster,
      boolean redirectSupportedByRule,
      boolean cancelSupportedByRule,
      Integer redirectDeadlineHours,
      Integer cancelDeadlineHours,
      boolean redirectAdapterConnected,
      boolean cancelAdapterConnected,
      String unavailableReason,
      String note) {}

  // ================================================================ 取消单

  public record WaybillCancelRow(
      UUID id,
      String cancelNo,
      UUID requestId,
      String requestNo,
      String waybillNo,
      String carrierCode,
      String carrierChannel,
      OffsetDateTime issuedAtSnapshot,
      OffsetDateTime deadlineAt,
      Long minutesToDeadline,
      String reason,
      DeadlineVerdict verdict,
      String verdictLabel,
      boolean canSucceed,
      String canSucceedReason,
      StoredOutcome outcome,
      String outcomeLabel,
      String carrierRef,
      String outcomeReason,
      OffsetDateTime dispatchedAt,
      UUID orderCancellationRequestId,
      String salesOrderNo,
      String orderCancellationStatus,
      String orderCancellationNote,
      UUID notificationTaskId,
      String notificationNo,
      OffsetDateTime requestedAt,
      OffsetDateTime updatedAt,
      String nextAction) {}

  public record WaybillCancelPage(
      List<WaybillCancelRow> items, long total, long withinWindow, long deadlinePassed,
      long channelUnsupported, long ruleUnregistered, long canSucceedCount,
      long acceptedCount, long rejectedCount, long notAttemptedCount,
      long pendingNotificationCount, List<String> notes) {}

  public record WaybillCancelDetail(
      WaybillCancelRow cancel, WaybillRedirectCarrierState carrierState,
      List<String> nextActions, List<String> boundaryNotes) {}

  // ================================================================ 买家通知任务

  public record BuyerNotificationTaskRow(
      UUID id,
      String notificationNo,
      UUID redirectId,
      UUID cancelId,
      String salesOrderNo,
      String waybillNo,
      String carrierCode,
      NotificationKind notificationKind,
      String notificationKindLabel,
      String subjectLine,
      String bodySummary,
      String status,
      String ownerModule,
      OffsetDateTime createdAt) {}

  public record BuyerNotificationTaskPage(
      List<BuyerNotificationTaskRow> items, long total, long queued, long pendingForModule15,
      List<String> notes) {}

  // ================================================================ 输入

  /**
   * 登记 / 更新一个承运商的改址与取消规则。
   *
   * <p>⚠️ {@code redirectSupported = true} 时 {@code redirectDeadlineHours} <b>必填</b>，
   * 反之亦然（DB 层 CHECK 已钉）。「支持改址但没写时限」= 改址可以无限期成功，
   * 现实里没有承运商是这样，DB 层会直接拒。
   */
  public record CarrierRedirectRuleInput(
      @NotBlank(message = "承运商代码不能为空")
          @Pattern(regexp = "^[A-Z0-9_\\-]{2,64}$", message = "承运商代码只能是大写字母、数字、下划线或中划线")
          String carrierCode,
      Boolean redirectSupported,
      @Positive(message = "改址时限小时数必须大于 0") Integer redirectDeadlineHours,
      Boolean cancelSupported,
      @Positive(message = "取消时限小时数必须大于 0") Integer cancelDeadlineHours,
      @Size(max = 400) String unsupportedReason,
      @Size(max = 400) String remark) {}

  /**
   * 发起一次改址。
   *
   * <p>{@code requestedAt} 是<b>请求发起时刻</b>，也是<b>时限判定的基准</b> ——
   * 运营补录一条昨天发起的改址时，判据必须是「昨天发起」，
   * 否则一条本该成功的改址会被误判成超时，而这一票从此再也不能改。
   *
   * <p>金额与数量：地址改址本身没有金额维度；若将来渠道收取改址费，
   * 必须用 {@code BigDecimal} + {@code NUMERIC}，禁浮点（AGENTS.md）。
   */
  public record WaybillRedirectInput(
      @NotBlank(message = "新收件人不能为空") @Size(max = 200) String recipientName,
      @NotBlank(message = "新收件国不能为空")
          @Pattern(regexp = "^[A-Z]{2}$", message = "收件国必须是两位大写国家代码")
          String recipientCountry,
      @Size(max = 400) String addressLine,
      @Size(max = 120) String city,
      @Size(max = 32) String postalCode,
      @NotBlank(message = "改址原因不能为空") @Size(max = 500) String reason,
      /** 发起时刻 = 时限判定基准。不传则用服务端当前时刻。 */
      OffsetDateTime requestedAt,
      /** 联动 9.1.1.3 的订单地址变更单（可选；传了会校验它是 ADDRESS_MODIFY 且同订单）。 */
      UUID orderChangeRequestId) {}

  /** 发起一次已出号运单的取消。 */
  public record WaybillCancelInput(
      @NotBlank(message = "取消原因不能为空") @Size(max = 500) String reason,
      OffsetDateTime requestedAt,
      UUID orderCancellationRequestId) {}

  /** 批量判定：一次问清一批运单「现在能不能改 / 能不能撤」。 */
  public record WaybillRedirectPrecheckInput(
      @NotBlank(message = "必须指定要判定的运单")
          @Size(min = 1, max = 200, message = "一次最多判定 200 张运单")
          List<String> requestIds) {}

  public record WaybillRedirectPrecheckEntry(
      UUID requestId,
      String requestNo,
      String waybillNo,
      String carrierCode,
      boolean waybillIssued,
      String waybillStatusNote,
      DeadlineVerdict verdict,
      String verdictLabel,
      boolean canSucceed,
      String canSucceedReason,
      OffsetDateTime deadlineAt,
      Long minutesToDeadline,
      boolean adapterConnected,
      Integer redirectDeadlineHours,
      Integer cancelDeadlineHours) {}

  public record WaybillRedirectPrecheckResult(
      int selected, int issued, int notIssued, int canSucceed, int cannotSucceed,
      List<WaybillRedirectPrecheckEntry> entries, List<String> notes) {}

  /** 金额占位：本模块当前不产生金额；若将来渠道收改址费，用 BigDecimal + NUMERIC(19,4)。 */
  @SuppressWarnings("unused")
  record RedirectFeeNote(BigDecimal amount, String currency) {}
}
