package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 16.1.2.2 申诉管理的传输模型。
 *
 * <p>🔴🔴 <b>为什么类型前缀是 {@code ComplianceAppeal*} 而不是 {@code PlatformAppeal*}</b>：
 * {@code PlatformAppeal*} 这个契约命名空间<b>已经被 trade 服务的 11.4.1.1 平台申诉占用</b>
 * （{@code PlatformDisputeAppealModels}）。契约是<b>扁平命名空间</b>（所有服务的类型倒进同一份
 * {@code packages/api-contracts}），撞名会让 codegen 直接报冲突、消费端拿到错误的那一份。
 * 所以本模块用<b>合规域</b>前缀 {@code ComplianceAppeal*}（与同服务 16.2.x 的
 * {@code CompliancePrecheck*} 同一套约定）。
 * 🔴 <b>但 DB 表前缀仍是 {@code platform_appeal_*}</b> —— 那是任务书钉死的表命名空间，
 *   四个命名空间（{@code platform_violation_*} / {@code platform_penalty_*} /
 *   {@code account_health_*} / {@code platform_appeal_*}）必须彼此隔离，不因契约改名而变。
 *
 * <p>蓝图：「管理平台申诉：申诉类型、举证材料收集、申诉文本、提交跟踪、结果记录与申诉次数统计。
 * 输入：违规记录；申诉材料；提交动作。输出：申诉单 + 提交记录 + 结果（通过/驳回）。
 * 关系：上游 = 16.1.2.1；下游 = 平台申诉接口。<b>申诉配额有限</b>，需记录已用次数并优先申诉高影响违规。」
 *
 * <p>🔴🔴 本模块对外最要紧的四条事实，全部**在 API 与页面上如实表达**：
 *
 * <ol>
 *   <li><b>配额是真的有限</b>：{@code quotaLimit} / {@code usedCount} / {@code remaining}
 *       都落在 {@code platform_appeal_quota} 上，<b>超配额提交在 DB 层就被拒</b>，
 *       页面上永远显示得出「还剩几次」。</li>
 *   <li><b>影响档位是派生的</b>：{@code impactLevel} 由上游违规的处罚措施集合现算，
 *       <b>没有</b>一个可以被单独改掉的「严重度」列。</li>
 *   <li><b>举证材料 / 提交记录 / 结果记录三样都是追加式</b>：不可改写、不可删；
 *       驳回后再次申诉<b>不会</b>抹掉上一次的结果。</li>
 *   <li><b>下游缺口是可查的事实</b>：平台申诉接口在本仓<b>不存在</b> ⇒ 能力声明里
 *       {@code available=false} + {@code ownerModule} + 影响说明，
 *       <b>不编造</b>指向不存在入口的放行形状。</li>
 * </ol>
 */
public final class ComplianceAppealModels {

  private ComplianceAppealModels() {}

  // ---------------------------------------------------------------- 能力声明

  /**
   * 一个下游（或维度）的交付状态。
   *
   * @param moduleId 模块号（如 04 / 16.1.2.1）
   * @param moduleName 模块名
   * @param ownerModule 归谁（available=false 时必填）
   * @param available 当前是否真的可用
   * @param impact 未交付 / 已交付的影响说明（界面原样显示）
   */
  public record ComplianceAppealCapabilityGap(
      String moduleId,
      String moduleName,
      String ownerModule,
      boolean available,
      String impact) {}

  /**
   * 能力声明。
   *
   * @param gaps 下游交付状态清单（**可查的事实**，不是文档里的一句话）
   * @param appealTypes 申诉类型值域
   * @param appealTypeLabels 申诉类型人话
   * @param statuses 申诉状态值域
   * @param statusLabels 申诉状态人话
   * @param statusTransitions 申诉状态的合法转换
   * @param evidenceTypes 举证材料类型值域
   * @param evidenceTypeLabels 举证材料类型人话
   * @param outcomes 申诉结果值域（通过 / 驳回）
   * @param outcomeLabels 申诉结果人话
   * @param impactLevels 影响档位值域（**派生**，不落库）
   * @param typeDomainNote 申诉类型值域的口径说明
   * @param quotaNote 配额口径说明
   * @param priorityNote 优先申诉口径说明
   * @param stateMachineSemantics 状态机的口径说明
   * @param appendOnlySemantics 追加式口径说明
   * @param upstreamNote 上游说明
   * @param downstreamNote 下游说明
   */
  public record ComplianceAppealCapabilities(
      List<ComplianceAppealCapabilityGap> gaps,
      List<String> appealTypes,
      Map<String, String> appealTypeLabels,
      List<String> statuses,
      Map<String, String> statusLabels,
      Map<String, List<String>> statusTransitions,
      List<String> evidenceTypes,
      Map<String, String> evidenceTypeLabels,
      List<String> outcomes,
      Map<String, String> outcomeLabels,
      List<String> impactLevels,
      String typeDomainNote,
      String quotaNote,
      String priorityNote,
      String stateMachineSemantics,
      String appendOnlySemantics,
      String upstreamNote,
      String downstreamNote) {}

  // ---------------------------------------------------------------- 店铺范围

  /** 店铺在本模块里的覆盖情况。 */
  public record ComplianceAppealShop(
      String shopId,
      String shopCode,
      String shopName,
      String platformCode,
      String platformName,
      int appealCount,
      int openAppealCount,
      int quotaLimit,
      int quotaUsed,
      int quotaRemaining) {}

  /** 店铺范围清单（**真分页**，数据来自本服务自己的店铺目录）。 */
  public record ComplianceAppealShopPage(
      List<ComplianceAppealShop> items, long total, int page, int size) {}

  // ---------------------------------------------------------------- 申诉单

  /**
   * 一条申诉单。
   *
   * @param appealId 申诉单 id
   * @param shopId 店铺 id
   * @param shopCode 店铺编码
   * @param shopName 店铺名
   * @param platformCode 平台编码
   * @param violationId 关联的上游违规 id
   * @param violationTitle 上游违规标题
   * @param violationType 上游违规类型
   * @param violationTypeLabel 上游违规类型人话
   * @param impactLevel **派生**的影响档位
   * @param impactLabel 影响档位的人话
   * @param appealType 申诉类型
   * @param appealTypeLabel 申诉类型人话
   * @param status 申诉状态
   * @param statusLabel 申诉状态人话
   * @param nextStatuses 合法可转的下一状态
   * @param appealText 申诉文本
   * @param submittedAt 最近一次提交时刻（可空 = 还没提交过）
   * @param withdrawnAt 撤回时刻（可空）
   * @param submissionCount 提交次数（**申诉次数**）
   * @param evidenceCount 举证材料条数
   * @param decisionCount 结果记录条数
   * @param lastOutcome 最近一次结果（可空 = 还没裁定过）
   * @param lastOutcomeLabel 最近一次结果人话
   * @param lastOutcomeReason 最近一次裁定的理由
   * @param lastDecidedAt 最近一次裁定时刻
   * @param createdAt 创建时刻
   */
  public record ComplianceAppeal(
      String appealId,
      String shopId,
      String shopCode,
      String shopName,
      String platformCode,
      String violationId,
      String violationTitle,
      String violationType,
      String violationTypeLabel,
      String impactLevel,
      String impactLabel,
      String appealType,
      String appealTypeLabel,
      String status,
      String statusLabel,
      List<String> nextStatuses,
      String appealText,
      OffsetDateTime submittedAt,
      OffsetDateTime withdrawnAt,
      int submissionCount,
      int evidenceCount,
      int decisionCount,
      String lastOutcome,
      String lastOutcomeLabel,
      String lastOutcomeReason,
      OffsetDateTime lastDecidedAt,
      OffsetDateTime createdAt) {}

  /** 申诉单清单（**真分页**）。 */
  public record ComplianceAppealPage(
      List<ComplianceAppeal> items,
      long total,
      int page,
      int size,
      long draftCount,
      long submittedCount,
      long approvedCount,
      long rejectedCount,
      long withdrawnCount,
      long submissionCount,
      String note) {}

  // ---------------------------------------------------------------- 举证材料

  /**
   * 一条申诉举证材料（**append-only**）。
   *
   * @param evidenceId 举证材料 id
   * @param appealId 所属申诉单 id
   * @param evidenceType 举证材料类型
   * @param evidenceRef 举证材料引用（文件名 / 链接 / 附件 id）
   * @param summary 说明
   * @param submittedAt 提交时刻
   */
  public record ComplianceAppealEvidence(
      String evidenceId,
      String appealId,
      String evidenceType,
      String evidenceRef,
      String summary,
      OffsetDateTime submittedAt) {}

  /** 追加举证材料的入参。 */
  public record ComplianceAppealEvidenceInput(
      String evidenceType, String evidenceRef, String summary) {}

  // ---------------------------------------------------------------- 提交记录

  /**
   * 一条申诉提交记录（**append-only**）。
   *
   * @param submissionId 提交记录 id
   * @param appealId 所属申诉单 id
   * @param attemptNo 第几次提交（驳回后补材料再提 ⇒ 第 2 次、第 3 次…）
   * @param submittedAt 提交时刻
   * @param platformReference 平台侧受理号 / 回执号
   * @param note 备注
   */
  public record ComplianceAppealSubmission(
      String submissionId,
      String appealId,
      int attemptNo,
      OffsetDateTime submittedAt,
      String platformReference,
      String note) {}

  /** 提交申诉的入参（提交时刻由服务端取 DB 时钟，不接受客户端自报）。 */
  public record ComplianceAppealSubmitInput(String platformReference, String note) {}

  /**
   * 提交的结果。
   *
   * @param appeal 提交后的申诉单
   * @param submission 本次写下的提交记录
   * @param attemptNo 本次是第几次提交
   * @param quota 提交后的配额快照（**剩余次数**）
   * @param note 口径说明
   */
  public record ComplianceAppealSubmissionResult(
      ComplianceAppeal appeal,
      ComplianceAppealSubmission submission,
      int attemptNo,
      ComplianceAppealQuota quota,
      String note) {}

  // ---------------------------------------------------------------- 结果记录

  /**
   * 一条申诉结果记录（**append-only**）。
   *
   * @param decisionId 结果记录 id
   * @param appealId 所属申诉单 id
   * @param outcome APPROVED（通过）/ REJECTED（驳回）
   * @param outcomeLabel 结果人话
   * @param reason 平台给的裁定理由
   * @param decidedAt 裁定时刻
   * @param platformReference 平台侧回执号
   */
  public record ComplianceAppealDecision(
      String decisionId,
      String appealId,
      String outcome,
      String outcomeLabel,
      String reason,
      OffsetDateTime decidedAt,
      String platformReference) {}

  /** 记录平台裁定的入参。 */
  public record ComplianceAppealDecisionInput(
      String outcome, String reason, String platformReference, OffsetDateTime decidedAt) {}

  /**
   * 裁定的结果。
   *
   * @param appeal 裁定后的申诉单
   * @param decision 本次写下的结果记录
   * @param note 口径说明
   */
  public record ComplianceAppealDecisionResult(
      ComplianceAppeal appeal, ComplianceAppealDecision decision, String note) {}

  // ---------------------------------------------------------------- 配额

  /**
   * 一个店铺在某个周期的申诉配额。
   *
   * @param shopId 店铺 id
   * @param shopCode 店铺编码
   * @param shopName 店铺名
   * @param periodStart 周期起
   * @param periodEnd 周期止
   * @param quotaLimit 配额上限（**本周期最多可提交多少次**）
   * @param usedCount 已用次数（= 已提交次数）
   * @param remaining 剩余次数
   * @param configured 该店铺本周期**是否配了配额**（false ⇒ 提交会被拒）
   */
  public record ComplianceAppealQuota(
      String shopId,
      String shopCode,
      String shopName,
      OffsetDateTime periodStart,
      OffsetDateTime periodEnd,
      int quotaLimit,
      int usedCount,
      int remaining,
      boolean configured,
      String note) {}

  /** 配额清单（**真分页**；**没有配额的店铺也会出现**，configured=false）。 */
  public record ComplianceAppealQuotaPage(
      List<ComplianceAppealQuota> items, long total, int page, int size, String note) {}

  /** 配置配额的入参（周期不填 ⇒ 默认当前自然季度 UTC）。 */
  public record ComplianceAppealQuotaInput(
      String shopId, Integer quotaLimit, OffsetDateTime periodStart, OffsetDateTime periodEnd) {}

  /**
   * 配置配额的结果。
   *
   * @param quota 配额快照
   * @param created 本次是否新建（false ⇒ 改了上限）
   * @param note 口径说明
   */
  public record ComplianceAppealQuotaUpdate(ComplianceAppealQuota quota, boolean created, String note) {}

  // ---------------------------------------------------------------- 优先申诉队列

  /**
   * 优先申诉队列里的一条候选违规。
   *
   * @param violationId 上游违规 id
   * @param shopId 店铺 id
   * @param shopCode 店铺编码
   * @param shopName 店铺名
   * @param platformCode 平台编码
   * @param title 违规标题
   * @param violationType 违规类型
   * @param violationTypeLabel 违规类型人话
   * @param impactLevel **派生**的影响档位
   * @param impactLabel 影响档位人话
   * @param occurredAt 违规发生时刻
   * @param deadline 整改期限（可空）
   * @param violationStatus 上游违规的处理状态
   * @param appealable 现在能不能提（false ⇒ 看 reason）
   * @param reason 不能提的原因 / 能提的提示
   * @param openAppealId 已有在办申诉的 id（可空）
   * @param quotaConfigured 该店铺本周期是否配了配额
   * @param quotaRemaining 该店铺本周期剩余次数（-1 = 没配配额）
   */
  public record ComplianceAppealPriorityCandidate(
      String violationId,
      String shopId,
      String shopCode,
      String shopName,
      String platformCode,
      String title,
      String violationType,
      String violationTypeLabel,
      String impactLevel,
      String impactLabel,
      OffsetDateTime occurredAt,
      OffsetDateTime deadline,
      String violationStatus,
      boolean appealable,
      String reason,
      String openAppealId,
      boolean quotaConfigured,
      int quotaRemaining) {}

  /** 优先申诉队列（**真分页**，默认按影响档位排序）。 */
  public record ComplianceAppealPriorityPage(
      List<ComplianceAppealPriorityCandidate> items,
      long total,
      int page,
      int size,
      String note) {}

  // ---------------------------------------------------------------- 详情 / 统计

  /** 申诉单详情（申诉单 + 举证材料 + 提交记录 + 结果记录 + 配额）。 */
  public record ComplianceAppealDetail(
      ComplianceAppeal appeal,
      List<ComplianceAppealEvidence> evidence,
      List<ComplianceAppealSubmission> submissions,
      List<ComplianceAppealDecision> decisions,
      ComplianceAppealQuota quota,
      String note) {}

  /**
   * 申诉次数统计。
   *
   * @param shopId 统计口径的店铺（可空 = 全租户）
   * @param totalAppeals 申诉单总数
   * @param byStatus 按申诉状态计数
   * @param byType 按申诉类型计数
   * @param totalSubmissions 提交总次数（= **申诉次数**）
   * @param approvedCount 裁定通过次数
   * @param rejectedCount 裁定驳回次数
   * @param quota 该店铺的配额（可空 = 没按店铺统计）
   * @param note 口径说明
   */
  public record ComplianceAppealStats(
      String shopId,
      long totalAppeals,
      Map<String, Long> byStatus,
      Map<String, Long> byType,
      long totalSubmissions,
      long approvedCount,
      long rejectedCount,
      ComplianceAppealQuota quota,
      String note) {}

  // ---------------------------------------------------------------- 入参

  /**
   * 新建申诉单的入参。
   *
   * @param violationId 关联的上游违规 id（**店铺由它派生**，不接受客户端自报）
   * @param appealType 申诉类型
   * @param appealText 申诉文本
   */
  public record ComplianceAppealCreateInput(
      String violationId, String appealType, String appealText) {}

  /** 改申诉文本的入参（只有草稿态能改）。 */
  public record ComplianceAppealTextInput(String appealText) {}

  /** 撤回申诉的入参。 */
  public record ComplianceAppealWithdrawInput(String note) {}
}
