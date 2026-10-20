package com.starcon.trade;

import static com.starcon.trade.OrderReviewRuleModels.HitCondition;
import static com.starcon.trade.OrderReviewRuleModels.ReviewDecision;
import static com.starcon.trade.OrderReviewRuleModels.RuleHit;
import static com.starcon.trade.OrderRiskModels.RiskAssessment;
import static com.starcon.trade.OrderRiskModels.RiskComponent;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 9.2.1.1 待审队列的传输模型。
 *
 * <p>蓝图原话：「展示待审订单，按风险与时效排序，支持批量通过/驳回/挂起，
 * 显示每单命中的规则与风险信号。」
 *
 * <p><b>六条建模纪律</b>：
 *
 * <ol>
 *   <li><b>「批量通过必须基于明确的规则，而不是默认全过」在传输层就分得清。</b>
 *       {@link BatchDecisionInput#ruleCode()} 与 {@link BatchDecisionInput#ruleVersion()}
 *       是「通过」动作的必填项；{@link BatchReceipt} 把它们原样回显，
 *       回执上还带着每一单的实际结论 —— 客服能答出「这一批是按哪条规则的哪一版通过的」。</li>
 *   <li><b>风险分与时效分是两个字段，不是同一个「优先级」。</b>
 *       {@link PendingReviewRow#riskScore()} 可能为 {@code null}（压根没评估过），
 *       {@link PendingReviewRow#timelinessScore()} 恒有值（提交时刻是必填列）。
 *       「没量过」渲染成 0 会被读成「量过了，安全」。</li>
 *   <li><b>命中链是引用 9.2.1.2 的形状</b>（{@link RuleHit} / {@link HitCondition}），
 *       不另起一套：命中链要能一路点到那条判定记录上，两套形状会让「回溯到规则 ID」断链。</li>
 *   <li><b>风险明细也是引用 9.2.2.2 的形状</b>（{@link RiskComponent}），
 *       同样不另起一套：五个维度恒为五行，缺信号的那行带着「为什么缺」。</li>
 *   <li><b>金额与分数全程 BigDecimal</b>：金额对齐 DB 的 numeric(19,4)，
 *       分数对齐 numeric(9,4)，时长对齐 numeric（秒 → 小时的整数除法，不用浮点）。</li>
 *   <li><b>「跳过」必须带理由</b>：{@link BatchItemResult#reason()} 在 outcome=SKIPPED 时非空，
 *       否则「这一单没通过」会被读成「这一单不存在」。</li>
 * </ol>
 */
public final class OrderPendingReviewModels {

  private OrderPendingReviewModels() {}

  // ------------------------------------------------------------ 枚举

  /** 队列状态。PENDING = 还没有审单结论（队列里的一行不存在结论）。 */
  public enum QueueStatus {
    PENDING("待审"),
    SUSPENDED("已挂起"),
    APPROVED("已通过"),
    REJECTED("已驳回"),
    ALL("全部");

    private final String label;

    QueueStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 批量动作。只有三种，没有第四种 —— 没有「无脑全过」。 */
  public enum BatchAction {
    APPROVE("通过"),
    REJECT("驳回"),
    SUSPEND("挂起");

    private final String label;

    BatchAction(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 逐条结果。SKIPPED = 这一条没被处理。 */
  public enum BatchOutcome {
    APPROVED("已通过"),
    REJECTED("已驳回"),
    SUSPENDED("已挂起"),
    SKIPPED("已跳过");

    private final String label;

    BatchOutcome(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 时效状态。判定真身在 DB 函数 order_review_timeliness_state 里。 */
  public enum TimelinessState {
    FRESH("未超时"),
    DUE_SOON("临近超时"),
    OVERDUE("已超时"),
    NO_DEADLINE("无时限");

    private final String label;

    TimelinessState(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ------------------------------------------------------------ 能力与概览

  /** 一项能力（上游数据源）是否接通。 */
  @Schema(description = "待审队列的一项能力/上游数据源")
  public record PendingReviewDimension(
      @Schema(description = "能力码") String code,
      @Schema(description = "中文名") String label,
      @Schema(description = "是否已接通") boolean delivered,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "缺口说明；已接通时为空串") String note) {}

  /** 一个下游模块的交付状态。 */
  @Schema(description = "下游模块的交付状态")
  public record PendingReviewDownstream(
      @Schema(description = "模块号") String code,
      @Schema(description = "中文名") String label,
      @Schema(description = "归属服务") String ownerModule,
      @Schema(description = "是否已交付") boolean delivered,
      @Schema(description = "交接说明") String note) {}

  @Schema(description = "9.2.1.1 待审队列的能力与上下游可用性")
  public record PendingReviewCapabilities(
      @Schema(description = "上游数据源逐项可用性") List<PendingReviewDimension> dimensions,
      @Schema(description = "下游模块逐项交付状态") List<PendingReviewDownstream> downstream,
      @Schema(description = "本租户此刻生效中的审单规则条数") int activeRuleCount,
      @Schema(description = "本租户此刻生效中的「自动通过」规则条数") int autoApproveRuleCount,
      @Schema(description = "本租户此刻生效中的「强制转人工」规则条数") int forcedManualRuleCount,
      @Schema(description = "**可作为批量通过依据**的规则清单（编号 + 版本）。"
          + "批量通过必须从这里挑一条 —— 没有规则就没有「明确的规则」")
          List<ApprovableRule> approvableRules,
      @Schema(description = "读这一页必须知道的事") String notes) {}

  /**
   * 一条可以作为「批量通过」依据的规则。
   *
   * <p>把规则清单放进能力声明里，是为了让界面**只能从生效中的自动通过规则里挑** ——
   * 挑别的（转人工的、没生效的）后端会显式拒绝，界面上提前一步就少一次往返。
   */
  @Schema(description = "可作为批量通过依据的规则")
  public record ApprovableRule(
      @Schema(description = "规则编号") String ruleCode,
      @Schema(description = "规则名称") String ruleName,
      @Schema(description = "规则版本（批量通过要连版本一起带）") long version,
      @Schema(description = "生效起") OffsetDateTime effectiveFrom,
      @Schema(nullable = true, description = "生效止；null = 长期有效") OffsetDateTime effectiveTo,
      @Schema(description = "为什么这条能当依据") String note) {}

  @Schema(description = "待审队列概览（不受列表筛选与分页影响）")
  public record PendingReviewSummary(
      @Schema(description = "队列里的订单总数（已提交且未终结）") long total,
      @Schema(description = "待审（没有任何结论）") long pending,
      @Schema(description = "已挂起（仍在队列里等人）") long suspended,
      @Schema(description = "已通过") long approved,
      @Schema(description = "已驳回") long rejected,
      @Schema(description = "已超时（等得最久的一批）") long overdue,
      @Schema(description = "还没有风险评估的订单数") long unevaluated,
      @Schema(description = "风险分 ≥ 60 的订单数") long highRisk,
      @Schema(description = "概览基准时刻") OffsetDateTime at) {}

  // ------------------------------------------------------------ 列表

  @Schema(description = "待审队列的一行")
  public record PendingReviewRow(
      @Schema(description = "订单 id") UUID orderId,
      @Schema(description = "我方单号") String orderNo,
      @Schema(description = "平台/外部单号") String externalOrderNo,
      @Schema(description = "订单来源") String orderSource,
      @Schema(description = "币种") String currencyCode,
      @Schema(description = "订单总额（numeric(19,4)）") BigDecimal grandTotal,
      @Schema(description = "提交时刻（进队列的时刻）") OffsetDateTime submittedAt,
      @Schema(description = "已等候小时数（numeric，秒→小时的整数除法）") BigDecimal waitingHours,
      @Schema(description = "时效分 0..100（DB 函数现算）") BigDecimal timelinessScore,
      @Schema(description = "时效状态") String timelinessState,
      @Schema(description = "时效状态中文") String timelinessStateLabel,
      @Schema(description = "队列状态") String queueStatus,
      @Schema(description = "队列状态中文") String queueStatusLabel,
      @Schema(nullable = true, description = "结论时刻") OffsetDateTime decidedAt,
      @Schema(nullable = true, description = "结论理由") String decisionReason,
      @Schema(nullable = true, description = "结论依据的规则编号") String ruleCode,
      @Schema(nullable = true, description = "结论依据的规则版本") Long ruleVersion,
      @Schema(nullable = true, description = "产生该结论的批次 id") UUID batchId,
      @Schema(nullable = true, description = "风险分 0..100；null = 没评估过，不是 0")
          BigDecimal riskScore,
      @Schema(nullable = true, description = "风控建议动作（RELEASE / MANUAL_REVIEW / HOLD_SHIPMENT）")
          String riskRecommendedAction,
      @Schema(nullable = true, description = "风控是否要求人工复核") Boolean riskManualReviewRequired,
      @Schema(nullable = true, description = "风控评估状态（SCORED / INSUFFICIENT_SIGNALS）")
          String riskEvaluationStatus,
      @Schema(nullable = true, description = "审单判定结论（AUTO_APPROVE / MANUAL_REVIEW）；null = 没判定过")
          String reviewDecision,
      @Schema(nullable = true, description = "判定的模式（RECORDED 生效判定 / DRY_RUN 试跑）；null = 没判定过")
          String reviewMode,
      @Schema(nullable = true, description = "审单判定的一句话结论") String reviewDecisionReason,
      @Schema(nullable = true, description = "决定结论的规则编号") String winningRuleCode,
      @Schema(nullable = true, description = "决定结论的规则名称") String winningRuleName,
      @Schema(description = "命中的规则条数") int matchedRuleCount,
      @Schema(description = "强制转人工的规则条数") int forcedRuleCount,
      @Schema(description = "命中的规则编号（可回溯到具体规则）") List<String> hitRuleCodes,
      @Schema(description = "命中的地址异常码") List<String> addressAnomalyCodes,
      @Schema(nullable = true, description = "收件人姓名") String recipientName,
      @Schema(nullable = true, description = "收货国家") String shippingCountry) {}

  @Schema(description = "待审队列分页")
  public record PendingReviewPage(
      @Schema(description = "当前页的行") List<PendingReviewRow> items,
      @Schema(description = "筛选后的总行数（不受分页影响）") long total,
      @Schema(description = "页码（从 0 开始）") int page,
      @Schema(description = "每页行数") int size,
      @Schema(description = "本批的排序键说明（风险分 → 时效分）") String sortNote) {}

  @Schema(description = "待审队列的筛选条件")
  public record QueueQuery(
      @Schema(description = "订单号 / 平台单号模糊匹配") String keyword,
      @Schema(description = "订单来源精确匹配") String orderSource,
      @Schema(description = "队列状态筛选；不给 = 未终结（待审 + 已挂起）") String queueStatus,
      @Schema(description = "审单判定结论筛选（AUTO_APPROVE / MANUAL_REVIEW）") String reviewDecision,
      @Schema(description = "只看有 / 没有风险评估的订单") Boolean riskEvaluated,
      @Schema(description = "页码（从 0 开始）") int page,
      @Schema(description = "每页行数") int size) {}

  // ------------------------------------------------------------ 详情

  @Schema(description = "订单快照（审单要看的那些事实）")
  public record OrderSnapshot(
      @Schema(description = "订单 id") UUID orderId,
      @Schema(description = "我方单号") String orderNo,
      @Schema(description = "平台/外部单号") String externalOrderNo,
      @Schema(description = "订单来源") String orderSource,
      @Schema(description = "手工单子类；平台单为空串") String manualKind,
      @Schema(description = "币种") String currencyCode,
      @Schema(description = "小计") BigDecimal subtotalAmount,
      @Schema(description = "折扣额") BigDecimal discountAmount,
      @Schema(description = "税费") BigDecimal taxAmount,
      @Schema(description = "运费") BigDecimal shippingAmount,
      @Schema(description = "其他费用") BigDecimal otherAmount,
      @Schema(description = "总额") BigDecimal grandTotal,
      @Schema(description = "提交时刻") OffsetDateTime submittedAt,
      @Schema(description = "单据状态（DRAFT / SUBMITTED）") String documentStatus,
      @Schema(description = "收件人姓名") String recipientName,
      @Schema(description = "收件人电话") String recipientPhone,
      @Schema(description = "收件人邮箱") String recipientEmail,
      @Schema(description = "收货国家") String shippingCountry,
      @Schema(description = "收货城市") String shippingCity,
      @Schema(description = "收货邮编") String shippingPostcode,
      @Schema(description = "收货地址行") String shippingAddressLine1,
      @Schema(description = "订单行数") int lineCount,
      @Schema(description = "订单件数（numeric(19,6)）") BigDecimal totalQuantity) {}

  @Schema(description = "这一单当前的审单结论")
  public record QueueCaseInfo(
      @Schema(description = "结论状态") String status,
      @Schema(description = "结论中文") String statusLabel,
      @Schema(description = "入队时刻") OffsetDateTime enqueuedAt,
      @Schema(nullable = true, description = "结论时刻") OffsetDateTime decidedAt,
      @Schema(nullable = true, description = "结论人（已认证成员）") UUID decidedByMembershipId,
      @Schema(description = "结论理由") String decisionReason,
      @Schema(nullable = true, description = "依据的规则编号") String ruleCode,
      @Schema(nullable = true, description = "依据的规则版本") Long ruleVersion,
      @Schema(nullable = true, description = "依据的判定留痕 id") UUID evaluationId,
      @Schema(nullable = true, description = "产生该结论的批次 id") UUID batchId) {}

  @Schema(description = "这一单最近几次批量审单的结果")
  public record PendingReviewBatchRef(
      @Schema(description = "批次 id") UUID batchId,
      @Schema(description = "批次动作") String action,
      @Schema(description = "批次创建时刻") OffsetDateTime createdAt,
      @Schema(description = "这一单在该批次里的结论") String outcome,
      @Schema(description = "这一单在该批次里的理由") String reason) {}

  @Schema(description = "待审队列的单条详情")
  public record PendingReviewDetail(
      @Schema(description = "队列行（含风险分、时效分、命中规则）") PendingReviewRow row,
      @Schema(description = "订单快照") OrderSnapshot order,
      @Schema(nullable = true, description = "当前审单结论；未审过为 null") QueueCaseInfo queueCase,
      @Schema(nullable = true, description = "最近一次风控评估；没评估过为 null")
          RiskAssessment riskAssessment,
      @Schema(description = "风控五个维度的明细（恒 5 行；没评估过时为空表）")
          List<RiskComponent> riskComponents,
      @Schema(nullable = true, description = "最近一次审单判定；没判定过为 null") ReviewDecision reviewDecision,
      @Schema(description = "命中链（逐条件到订单字段与实际值）；没判定过时为空表") List<RuleHit> hits,
      @Schema(description = "这一单最近几次批量审单的结果") List<PendingReviewBatchRef> recentBatches,
      @Schema(description = "读这一页必须知道的事") String notes) {}

  // ------------------------------------------------------------ 批量

  @Schema(description = "批量审单请求")
  public record BatchDecisionInput(
      @NotBlank
          @Size(max = 128)
          @Schema(description = "幂等键：同一租户内唯一；重复提交同一个键只生效一次")
          String idempotencyKey,
      @NotNull @Schema(description = "批量动作：APPROVE 通过 / REJECT 驳回 / SUSPEND 挂起")
          BatchAction action,
      @NotEmpty
          @Size(max = 100)
          @Schema(description = "订单 id 列表（1..100 条，同一批内不许重复）")
          List<UUID> orderIds,
      @Schema(nullable = true, description = "依据的规则编号；action=APPROVE 时必填")
          String ruleCode,
      @Schema(nullable = true, description = "依据的规则版本；action=APPROVE 时必填")
          Long ruleVersion,
      @Schema(nullable = true, description = "理由；action=REJECT / SUSPEND 时必填") String reason) {}

  @Schema(description = "批量审单的逐条结果")
  public record BatchItemResult(
      @Schema(description = "请求内的序号（从 1 开始）") int seqNo,
      @Schema(description = "订单 id") UUID orderId,
      @Schema(nullable = true, description = "我方单号；查不到时为 null") String orderNo,
      @Schema(description = "这一条的结论") BatchOutcome outcome,
      @Schema(description = "这一条的结论中文") String outcomeLabel,
      @Schema(description = "为什么是这个结论") String reason) {}

  @Schema(description = "批量审单回执")
  public record BatchReceipt(
      @Schema(description = "批次 id") UUID batchId,
      @Schema(description = "幂等键") String idempotencyKey,
      @Schema(description = "批次动作") BatchAction action,
      @Schema(description = "批次动作中文") String actionLabel,
      @Schema(nullable = true, description = "依据的规则编号") String ruleCode,
      @Schema(nullable = true, description = "依据的规则版本") Long ruleVersion,
      @Schema(description = "请求了几条") int requestedCount,
      @Schema(description = "本批实际通过了几条") int approvedCount,
      @Schema(description = "本批实际驳回了几条") int rejectedCount,
      @Schema(description = "本批实际挂起了几条") int suspendedCount,
      @Schema(description = "本批跳过了几条") int skippedCount,
      @Schema(description = "批次理由") String reason,
      @Schema(description = "是否已完成") boolean completed,
      @Schema(nullable = true, description = "完成时刻") OffsetDateTime completedAt,
      @Schema(description = "操作人（已认证成员）") UUID createdByMembershipId,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "逐条结果（按请求下标）") List<BatchItemResult> items,
      @Schema(description = "本次是否为幂等回放（true = 之前已经处理过，没有再次改状态）")
          boolean replayed,
      @Schema(description = "读这一页必须知道的事") String notes) {}

  @Schema(description = "批量审单回执列表的一行")
  public record BatchRow(
      @Schema(description = "批次 id") UUID batchId,
      @Schema(description = "幂等键") String idempotencyKey,
      @Schema(description = "批次动作") BatchAction action,
      @Schema(description = "批次动作中文") String actionLabel,
      @Schema(nullable = true, description = "依据的规则编号") String ruleCode,
      @Schema(nullable = true, description = "依据的规则版本") Long ruleVersion,
      @Schema(description = "请求了几条") int requestedCount,
      @Schema(description = "实际通过几条") int approvedCount,
      @Schema(description = "实际驳回几条") int rejectedCount,
      @Schema(description = "实际挂起几条") int suspendedCount,
      @Schema(description = "跳过几条") int skippedCount,
      @Schema(description = "批次理由") String reason,
      @Schema(description = "是否已完成") boolean completed,
      @Schema(description = "创建时刻") OffsetDateTime createdAt) {}

  @Schema(description = "批量审单回执分页")
  public record BatchPage(
      @Schema(description = "当前页的行") List<BatchRow> items,
      @Schema(description = "总行数（不受分页影响）") long total,
      @Schema(description = "页码（从 0 开始）") int page,
      @Schema(description = "每页行数") int size) {}

  @Schema(description = "批量审单回执的筛选条件")
  public record BatchQuery(
      @Schema(description = "批次动作筛选（APPROVE / REJECT / SUSPEND）") String action,
      @Schema(description = "幂等键 / 规则编号模糊匹配") String keyword,
      @Schema(description = "页码（从 0 开始）") int page,
      @Schema(description = "每页行数") int size) {}

  @Schema(description = "试算请求")
  public record EvaluateInput(
      @Schema(nullable = true, description = "判定时刻（timestamptz）；不给 = 服务器当前时刻")
          OffsetDateTime evaluatedAt) {}
}
