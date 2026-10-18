package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 11.1.1.3 售后受理的传输模型。
 *
 * <p>蓝图 11-aftersales-and-returns.md 第 86-90 行原文：
 *
 * <blockquote>
 * 职责：受理平台或内部发起的售后请求：判定是否在售后期内、是否符合条件、指定责任人与处理方案、设置回复时限。
 * 输入：售后申请（平台消息/买家消息/内部录入）；售后政策配置（期限、条件、必退情形）。
 * 输出：售后单（待处理）；政策判定结果；责任人分配。
 * 关系：上游 = 15 客户与客服、2 平台消息；下游 = 11.2/11.3。
 * <b>必退情形（平台强制、收到即退）要自动判定</b>，人工不能为了业绩拖着不退导致平台判责。
 * </blockquote>
 *
 * <p><b>本文件最重要的三件事</b>：
 *
 * <ol>
 *   <li><b>必退是判定结论，不是备注。</b>{@link AftersalesIntakeVerdictView#mustRefund()} 与
 *       {@link AftersalesIntakeVerdictView#mustRefundBasis()} 是一等字段，
 *       且 {@code mustRefundBasis=PLATFORM_DECLARED} 的结论在数据库层<b>不许被降级</b>
 *       （V164 的 {@code guard_aftersales_verdict_must_refund} 触发器）——
 *       加粗句那句「不能为了业绩拖着不退」靠服务层自觉是守不住的。
 *   <li><b>政策是读来的，不是写死的。</b>{@link AftersalesIntakeVerdictView#policyReturnWindowDays()} 与
 *       {@link AftersalesIntakeVerdictView#policyMustRefundReasons()} 是从 17.5.1.1 业务参数读到的
 *       <b>生效值快照</b>，连同 {@code policySourceScope}（哪一档）一起冻结在判定凭据上 ——
 *       改了参数不影响历史单据「当初按哪份政策判的」这个回答。
 *   <li><b>四项判定逐项可解释。</b>每项判定都有自己的 basis 字段（为什么是这个结论），
 *       不是一个笼统的 boolean。运营要能回答「凭什么判它必退」「凭什么说它超窗」。
 * </ol>
 *
 * <p><b>命名。</b>统一用 {@code AftersalesIntake*} / {@code AftersalesReplySla*} /
 * {@code AftersalesOwnerRoster*} 前缀，全仓 grep 确认与既有类型零重叠 ——
 * 契约被 codegen 合并进一个扁平文件、同名后者覆盖前者且不报冲突。
 */
public final class AftersalesIntakeModels {

  private AftersalesIntakeModels() {}

  // ================================================================ 枚举

  /** 受理来源：蓝图输入句点名的三种（平台消息 / 买家消息 / 内部录入），一个都不能少。 */
  public enum AftersalesIntakeSource {
    PLATFORM_MESSAGE("平台消息"),
    BUYER_MESSAGE("买家消息"),
    INTERNAL_ENTRY("内部录入");

    private final String label;

    AftersalesIntakeSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 受理渠道。与 V157 {@code aftersales_message.channel} 同一词表，两处能对上。 */
  public enum AftersalesIntakeChannel {
    PLATFORM("平台"),
    EMAIL("邮件"),
    CHAT("在线聊天"),
    PHONE("电话"),
    INTERNAL_NOTE("内部登记");

    private final String label;

    AftersalesIntakeChannel(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 是否符合条件。判定结论，<b>不是</b>处理进展（后者是 11.1.1.1 的 {@code aftersales_case.status}）。 */
  public enum AftersalesEligibility {
    ACCEPTED("符合条件，正常受理"),
    ACCEPTED_MUST_REFUND("符合条件且必退（平台强制，收到即退）"),
    OUT_OF_WINDOW_PENDING("超出售后期，待人工裁定"),
    REJECTED_OUT_OF_WINDOW("超出售后期且非必退，不受理");

    private final String label;

    AftersalesEligibility(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 必退依据。
   *
   * <p>⚠️ 刻意<b>不</b>含「人工判定必退」：必退是<b>平台强制</b>的事实，
   * 让人工也能把它标成必退，等于给了「拖着不退」的合法外壳。
   * 需要给单子开路的正确做法是授权（11.1.1.1 的 AUTHORIZE），那是有决策留痕的。
   */
  public enum AftersalesMustRefundBasis {
    PLATFORM_DECLARED("平台声明必退"),
    REASON_MAPPED("原因分类命中必退情形"),
    NONE("不命中任何必退情形");

    private final String label;

    AftersalesMustRefundBasis(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 处理方案。与 {@code case_type}（买家要什么）独立：这里是「我们打算怎么办」。 */
  public enum AftersalesHandlingPlan {
    RETURN_REFUND("退货退款"),
    REFUND_ONLY("仅退款"),
    EXCHANGE("换货"),
    REISSUE("补发"),
    REPAIR("维修"),
    REJECT("不受理");

    private final String label;

    AftersalesHandlingPlan(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 回复时限从哪来。
   *
   * <p>⚠️ 刻意<b>不</b>含「受理人随手指定」：受理时限是平台判责的计时基准，
   * 「谁随手填的」不该成为它的合法来源。两档都取不到时受理**直接失败并说清没配置**
   * （见 {@code AftersalesIntakeService#resolveReplyDue}），而不是默默替租户选一个数字。
   */
  public enum AftersalesReplyDueBasis {
    PLATFORM_GIVEN("平台自己给了时限"),
    POLICY_SLA("按租户回复时限策略推算");

    private final String label;

    AftersalesReplyDueBasis(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 责任人怎么定的。 */
  public enum AftersalesOwnerAssignMode {
    OPERATOR_SPECIFIED("受理人指定"),
    ROSTER_ROUND_ROBIN("按责任人名册轮转自动派单"),
    UNASSIGNED("暂未分配（名册为空且受理人未指定）");

    private final String label;

    AftersalesOwnerAssignMode(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 售后期锚点用了订单的哪一列。如实报出，不用「都差不多」糊过去。 */
  public enum AftersalesOrderAnchorKind {
    SUBMITTED_AT("订单提交时刻"),
    CREATED_AT("订单建单时刻（该单尚无提交时刻）");

    private final String label;

    AftersalesOrderAnchorKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 请求

  /**
   * 受理一张售后请求。
   *
   * <p>四个判定输入里，<b>订单与原因分类必填</b>（没有它们判不了），
   * 而<b>时限与责任人都可以不填</b>——它们由本模块按政策推算或自动派单补齐，
   * 这正是蓝图把「设置回复时限」「指定责任人」列为本模块职责的意思。
   *
   * @param orderId 关联订单（必填；是否在售后期内以它的下单时刻为锚点）
   * @param caseType 售后类型（必填；处理方案的合法取值由它约束）
   * @param reasonCode 原因分类（必填；蓝图 11.1.2.1 的七类，**不允许自由文本**）
   * @param platformMustRefund 平台是否声明该单为强制必退。**只接受平台侧的真信号**，
   *     受理人自己勾这个等于给自己开「拖着不退」的合法外壳，所以它的依据是请求来源必须是平台消息。
   * @param replyDueAt 平台给的回复时限（给了就用平台的，优先级最高）
   * @param resolveDueAt 平台给的处理时限
   * @param ownerRef 指定责任人（不给则按名册轮转自动派单）
   */
  public record AftersalesIntakeRequest(
      UUID orderId,
      String caseType,
      String reasonCode,
      @Schema(nullable = true) String reasonNote,
      BigDecimal requestedAmount,
      String currency,
      BigDecimal requestedQuantity,
      String intakeSource,
      @Schema(nullable = true) String intakeReference,
      @Schema(nullable = true) String intakeChannel,
      @Schema(nullable = true) Boolean platformMustRefund,
      @Schema(nullable = true) OffsetDateTime platformReceivedAt,
      @Schema(nullable = true) String buyerRequest,
      @Schema(nullable = true) OffsetDateTime replyDueAt,
      @Schema(nullable = true) OffsetDateTime resolveDueAt,
      @Schema(nullable = true) OffsetDateTime receivedAt,
      @Schema(nullable = true) String handlingPlan,
      @Schema(nullable = true) String ownerRef,
      @Schema(nullable = true) String ownerDisplayName,
      @Schema(nullable = true) String ownerRoleLabel) {}

  // ================================================================ 响应

  /** 受理结果：判定凭据 + 建出来的售后单 + 责任人分配结果（三样都是蓝图的输出句点名的）。 */
  public record AftersalesIntakeResult(
      AftersalesIntakeVerdictView verdict,
      AftersalesCaseModels.AftersalesCaseRow caseRow,
      AftersalesOwnerAssignmentView ownerAssignment,
      String policyNote) {}

  /** 判定凭据。整份冻结在 {@code aftersales_intake_verdict} 上（V164，append-only）。 */
  public record AftersalesIntakeVerdictView(
      UUID verdictId,
      UUID caseId,
      String caseNo,
      String intakeSource,
      String intakeReference,
      String intakeChannel,
      OffsetDateTime requestReceivedAt,
      OffsetDateTime judgedAt,
      String orderAnchorKind,
      OffsetDateTime orderAnchorAt,
      int policyReturnWindowDays,
      OffsetDateTime returnWindowDeadlineAt,
      boolean inReturnWindow,
      @Schema(nullable = true) Long windowMarginHours,
      List<String> policyMustRefundReasons,
      boolean mustRefund,
      String mustRefundBasis,
      String mustRefundSituation,
      String eligibility,
      String handlingPlan,
      String handlingPlanSource,
      OffsetDateTime replyDueAt,
      String replyDueBasis,
      @Schema(nullable = true) Integer replySlaHours,
      @Schema(nullable = true) OffsetDateTime resolveDueAt,
      @Schema(nullable = true) Integer resolveSlaHours,
      String ownerAssignmentMode,
      String policySourceScope,
      List<String> policyParameterCodes,
      OffsetDateTime policyReadAt) {}

  /** 责任人分配结果。真实落在 11.1.1.1 的 {@code aftersales_owner_assignment}（追加式）。 */
  public record AftersalesOwnerAssignmentView(
      @Schema(nullable = true) UUID assignmentId,
      @Schema(nullable = true) String ownerRef,
      @Schema(nullable = true) String ownerDisplayName,
      @Schema(nullable = true) String ownerRoleLabel,
      @Schema(nullable = true) String ownerSource,
      String mode,
      String note) {}

  // ================================================================ 回复时限策略

  /** 新建一档回复时限策略。 */
  public record AftersalesReplySlaCreate(
      String platformScope,
      int replySlaHours,
      @Schema(nullable = true) Integer resolveSlaHours,
      OffsetDateTime effectiveFrom) {}

  public record AftersalesReplySlaView(
      UUID id,
      String platformScope,
      int replySlaHours,
      @Schema(nullable = true) Integer resolveSlaHours,
      OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      boolean effectiveNow,
      boolean created) {}

  /** 收口一档策略（换版或作废）。内容不可改，只能收口 —— 理由见 V164 的 immutable 触发器。 */
  public record AftersalesReplySlaRetire(@Schema(nullable = true) OffsetDateTime effectiveTo) {}

  // ================================================================ 责任人名册

  /** 登记一个可接单的责任人（租户可维护，卷 15 客服交付后由同步器按同一形状写入）。 */
  public record AftersalesOwnerRosterCreate(
      String ownerRef,
      @Schema(nullable = true) String ownerDisplayName,
      @Schema(nullable = true) String ownerRoleLabel,
      @Schema(nullable = true) String ownerSource,
      @Schema(nullable = true) String ownerSourceReference,
      @Schema(nullable = true) Integer weight) {}

  /** 调整名册行：只允许改「是否接新单」与权重，标识本身不可改（UNIQUE(tenant_id, owner_ref) 是它的身份）。 */
  public record AftersalesOwnerRosterUpdate(
      @Schema(nullable = true) Boolean acceptingNewCases,
      @Schema(nullable = true) Integer weight,
      @Schema(nullable = true) String ownerDisplayName,
      @Schema(nullable = true) String ownerRoleLabel) {}

  public record AftersalesOwnerRosterView(
      UUID id,
      String ownerRef,
      String ownerDisplayName,
      String ownerRoleLabel,
      String ownerSource,
      String sourceReference,
      boolean acceptingNewCases,
      int weight,
      OffsetDateTime createdAt) {}

  // ================================================================ 查询与能力声明

  /** 受理凭据列表的筛选条件。每一项都对应一个真实会被跑的查询形状。 */
  public record AftersalesIntakeQuery(
      @Schema(nullable = true) String eligibility,
      @Schema(nullable = true) Boolean mustRefund,
      @Schema(nullable = true) String intakeSource,
      @Schema(nullable = true) String ownerAssignmentMode,
      @Schema(nullable = true) UUID caseId,
      @Schema(nullable = true) String caseNo,
      @Schema(nullable = true) String keyword,
      @Schema(nullable = true) Boolean currentOnly,
      @Schema(nullable = true) String sort,
      @Schema(nullable = true) Integer page,
      @Schema(nullable = true) Integer size) {}

  public record AftersalesIntakePage(
      List<AftersalesIntakeVerdictView> items,
      long total,
      int page,
      int size,
      int totalPages,
      OffsetDateTime evaluatedAt,
      AftersalesIntakeSummary summary,
      String sortSemantics) {}

  /**
   * 与分页无关的全量汇总。
   *
   * <p>⚠️ {@code mustRefundCount} 与 {@code replyOverdueMustRefundCount} 是本模块最要紧的两个数：
   * 前者是「平台在等我们退的单」，后者是「**其中已经超时的**」——
   * 超时直接关系账号绩效与资金冻结，所以它们与分页无关，一进页面就能看到。
   */
  public record AftersalesIntakeSummary(
      long total,
      long mustRefundCount,
      long replyOverdueMustRefundCount,
      long outOfWindowPendingCount,
      long unassignedCount,
      long supersededCount) {}

  /** 能力声明：四项判定各读什么、必退词表当前长什么样、回复时限策略配没配。 */
  public record AftersalesIntakeCapabilities(
      List<String> judgmentResponsibilities,
      String policySource,
      String policySourceNote,
      String returnWindowParameter,
      String mustRefundParameter,
      @Schema(nullable = true) Integer effectiveReturnWindowDays,
      @Schema(nullable = true) List<String> effectiveMustRefundReasons,
      @Schema(nullable = true) String policyScopeType,
      String replyDeadlineSource,
      String replyDeadlineNote,
      boolean replySlaConfigured,
      int rosterSize,
      int assignableRosterSize,
      String ownerSourceNote,
      String mustRefundEnforcementNote,
      String platformMessageNote,
      List<String> intakeSources,
      List<AftersalesHandlingPlanOption> handlingPlans,
      String crossServiceNote) {}

  public record AftersalesHandlingPlanOption(String code, String label) {}
}
