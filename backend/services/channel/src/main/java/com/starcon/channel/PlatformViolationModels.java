package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 16.1.2.1 违规与处罚的传输模型。
 *
 * <p>蓝图：「记录与管理平台违规与处罚：违规类型、涉及商品/订单、处罚措施（警告/下架/限制销售/扣款/停号）、
 * 处理状态与整改要求。输入：平台违规通知同步；整改记录。输出：违规记录 + 整改跟踪 + 扣款金额（进 12 财务）。
 * 关系：上游 = 2 同步；下游 = 12 财务（罚扣款）、4 刊登（问题商品下架）、16.1.2.2 申诉。
 * <b>违规常涉及多个商品</b>，需批量定位受影响 listing 与库存。」
 *
 * <p>🔴🔴 本模块对外最要紧的三条事实，全部**在 API 与页面上如实表达**：
 *
 * <ol>
 *   <li><b>扣款金额带币种，且不跨币种合计</b>：{@code penaltyAmount} + {@code penaltyCurrency}
 *       永远成对出现；合计按币种分组（{@code totalsByCurrency}），<b>没有一个跨币种的总数</b>。
 *       「不同币种直接相加」在这个模块里是<b>被禁止</b>的，不是「暂时没做」。</li>
 *   <li><b>影响档位是派生的</b>：{@code impactLevel} 由处罚措施集合算出，
 *       <b>没有</b>一个可以被单独改掉的「严重度」列。</li>
 *   <li><b>下游缺口是可查的事实</b>：12 财务（罚扣款）当前
 *       <b>未交付</b> ⇒ 能力声明里 {@code available=false} + {@code ownerModule} + 影响说明，
 *       <b>不编造</b>指向不存在入口的放行形状；
 *       16.1.2.2 申诉管理<b>已交付</b> ⇒ {@code available=true}，
 *       本模块只记「申诉中」这个状态，申诉数据由它记录、<b>不复制</b>一份。</li>
 * </ol>
 */
public final class PlatformViolationModels {

  private PlatformViolationModels() {}

  // ---------------------------------------------------------------- 能力声明

  /**
   * 一个下游（或维度）的交付状态。
   *
   * @param moduleId 模块号（如 12 / 04 / 16.1.2.2）
   * @param moduleName 模块名
   * @param ownerModule 归谁（available=false 时必填）
   * @param available 当前是否真的可用
   * @param impact 未交付 / 已交付的影响说明（界面原样显示）
   */
  public record PlatformViolationCapabilityGap(
      String moduleId,
      String moduleName,
      String ownerModule,
      boolean available,
      String impact) {}

  /**
   * 能力声明。
   *
   * @param gaps 下游交付状态清单（**可查的事实**，不是文档里的一句话）
   * @param violationTypes 违规类型值域
   * @param violationTypeLabels 违规类型人话
   * @param measures 处罚措施值域
   * @param measureLabels 处罚措施人话
   * @param statuses 处理状态值域
   * @param statusLabels 处理状态人话
   * @param statusTransitions 处理状态的合法转换
   * @param remediationStatuses 整改状态值域
   * @param remediationStatusLabels 整改状态人话
   * @param remediationStatusTransitions 整改状态的合法转换
   * @param subjectTypes 涉及主体类型值域
   * @param sources 来源值域
   * @param impactLevels 影响档位值域
   * @param typeDomainNote 违规类型值域的口径说明
   * @param moneySemantics 金额口径说明（**不同币种不得直接相加**）
   * @param impactSemantics 影响档位的口径说明
   * @param stateMachineSemantics 状态机的口径说明
   * @param idempotencySemantics 幂等口径说明
   * @param upstreamNote 上游说明
   * @param downstreamNote 下游说明
   */
  public record PlatformViolationCapabilities(
      List<PlatformViolationCapabilityGap> gaps,
      List<String> violationTypes,
      Map<String, String> violationTypeLabels,
      List<String> measures,
      Map<String, String> measureLabels,
      List<String> statuses,
      Map<String, String> statusLabels,
      Map<String, List<String>> statusTransitions,
      List<String> remediationStatuses,
      Map<String, String> remediationStatusLabels,
      Map<String, List<String>> remediationStatusTransitions,
      List<String> subjectTypes,
      List<String> sources,
      List<String> impactLevels,
      String typeDomainNote,
      String moneySemantics,
      String impactSemantics,
      String stateMachineSemantics,
      String idempotencySemantics,
      String upstreamNote,
      String downstreamNote) {}

  // ---------------------------------------------------------------- 店铺范围

  /** 店铺在本模块里的覆盖情况。 */
  public record PlatformViolationShop(
      String shopId,
      String shopCode,
      String shopName,
      String platformCode,
      String platformName,
      int violationCount,
      int openCount,
      int pendingDeductionCount) {}

  /** 店铺范围清单（**真分页**，数据来自本服务自己的店铺目录）。 */
  public record PlatformViolationShopPage(
      List<PlatformViolationShop> items, long total, int page, int size) {}

  // ---------------------------------------------------------------- 违规记录

  /**
   * 一条违规记录。
   *
   * @param violationId 违规 id
   * @param shopId 店铺 id
   * @param shopCode 店铺编码
   * @param shopName 店铺名
   * @param platformCode 平台编码
   * @param externalNoticeId 平台侧通知 id（**幂等锚点**）
   * @param source PLATFORM_SYNC / MANUAL
   * @param sourceLabel 来源的人话
   * @param violationType 违规类型
   * @param violationTypeLabel 违规类型的人话
   * @param title 标题
   * @param description 说明
   * @param occurredAt 平台判定 / 通知时刻
   * @param notifiedAt 平台下发通知时刻（可空）
   * @param status 处理状态
   * @param statusLabel 处理状态的人话
   * @param nextStatuses 合法可转的下一状态
   * @param deadline 整改期限（可空）
   * @param impactLevel **派生**的影响档位
   * @param impactLabel 影响档位的人话
   * @param measures 该违规上的处罚措施编码集合
   * @param measureLabels 处罚措施的人话
   * @param subjectCount 涉及主体数
   * @param penaltyCount 处罚条数
   * @param remediationCount 整改要求条数
   * @param openRemediationCount 未完结的整改要求数
   * @param createdAt 登记时刻
   */
  public record PlatformViolation(
      String violationId,
      String shopId,
      String shopCode,
      String shopName,
      String platformCode,
      String externalNoticeId,
      String source,
      String sourceLabel,
      String violationType,
      String violationTypeLabel,
      String title,
      String description,
      OffsetDateTime occurredAt,
      OffsetDateTime notifiedAt,
      String status,
      String statusLabel,
      List<String> nextStatuses,
      OffsetDateTime deadline,
      String impactLevel,
      String impactLabel,
      List<String> measures,
      List<String> measureLabels,
      int subjectCount,
      int penaltyCount,
      int remediationCount,
      int openRemediationCount,
      OffsetDateTime createdAt) {}

  /** 违规记录清单（**真分页**）。 */
  public record PlatformViolationPage(
      List<PlatformViolation> items,
      long total,
      int page,
      int size,
      long pendingCount,
      long inProgressCount,
      long rectifiedCount,
      long appealingCount,
      long closedCount,
      String note) {}

  /** 违规记录详情（违规 + 处罚 + 涉及主体 + 整改要求 + 整改凭证）。 */
  public record PlatformViolationDetail(
      PlatformViolation violation,
      List<PlatformPenalty> penalties,
      List<PlatformViolationSubject> subjects,
      List<PlatformViolationRemediation> remediations,
      List<PlatformViolationEvidence> evidence,
      String note) {}

  // ---------------------------------------------------------------- 处罚措施

  /**
   * 一条处罚措施。
   *
   * @param penaltyId 处罚 id
   * @param violationId 所属违规 id
   * @param measure 处罚措施（受控值域）
   * @param measureLabel 处罚措施的人话
   * @param penaltyAmount 扣款金额（**只有扣款才有**，其它措施为 null）
   * @param penaltyCurrency 币种（**与金额成对出现**）
   * @param moneyText 金额展示串（没有金额时是空串，**不是 0**）
   * @param effectiveAt 生效时刻
   * @param endsAt 结束时刻（可空）
   * @param status ACTIVE / RELEASED / EXPIRED
   * @param note 备注
   */
  public record PlatformPenalty(
      String penaltyId,
      String violationId,
      String measure,
      String measureLabel,
      BigDecimal penaltyAmount,
      String penaltyCurrency,
      String moneyText,
      OffsetDateTime effectiveAt,
      OffsetDateTime endsAt,
      String status,
      String note) {}

  /** 某个币种的扣款合计。 */
  public record PlatformPenaltyTotal(String currency, BigDecimal amount, long penaltyCount) {}

  /** 处罚清单（**真分页** + **按币种分组的合计**）。 */
  public record PlatformPenaltyPage(
      List<PlatformPenalty> items,
      long total,
      int page,
      int size,
      List<PlatformPenaltyTotal> totalsByCurrency,
      String totalNote,
      String note) {}

  /** 记一条处罚的入参。 */
  public record PlatformPenaltyInput(
      String measure,
      BigDecimal penaltyAmount,
      String penaltyCurrency,
      OffsetDateTime effectiveAt,
      OffsetDateTime endsAt,
      String note) {}

  // ---------------------------------------------------------------- 涉及主体

  /**
   * 违规涉及的一个主体（商品 / 平台商品 / 订单）。
   *
   * @param subjectType SKU / LISTING / ORDER
   * @param subjectTypeLabel 主体类型的人话
   * @param subjectRef 展示引用
   * @param skuId 商品 id（跨服务逻辑 id）
   * @param listingId 平台商品 id
   * @param orderRef 订单号
   * @param affectedQuantity 受影响数量（可空 = 平台没给）
   * @param note 备注
   */
  public record PlatformViolationSubject(
      String subjectId,
      String violationId,
      String subjectType,
      String subjectTypeLabel,
      String subjectRef,
      String skuId,
      String listingId,
      String orderRef,
      BigDecimal affectedQuantity,
      String note) {}

  /** 批量挂涉及主体的入参。 */
  public record PlatformViolationSubjectInput(
      String subjectType,
      String subjectRef,
      String skuId,
      String listingId,
      String orderRef,
      BigDecimal affectedQuantity,
      String note) {}

  /** 批量挂载的结果（**真的批量**，不是一条条循环）。 */
  public record PlatformViolationSubjectBatchResult(
      String violationId,
      int requested,
      int inserted,
      int skipped,
      List<String> skippedRefs,
      String note) {}

  // ---------------------------------------------------------------- 整改跟踪

  /**
   * 一条整改要求。
   *
   * @param remediationId 整改要求 id
   * @param violationId 所属违规 id
   * @param requirement 要求内容
   * @param dueAt 期限（可空）
   * @param overdue **派生**：已过期限且尚未完结
   * @param status 整改状态
   * @param statusLabel 整改状态的人话
   * @param nextStatuses 合法可转的下一状态
   * @param ownerRef 责任人
   * @param completedAt 提交时刻
   * @param verifiedAt 验收时刻
   * @param evidenceCount 整改凭证数
   * @param createdAt 创建时刻
   */
  public record PlatformViolationRemediation(
      String remediationId,
      String violationId,
      String requirement,
      OffsetDateTime dueAt,
      boolean overdue,
      String status,
      String statusLabel,
      List<String> nextStatuses,
      String ownerRef,
      OffsetDateTime completedAt,
      OffsetDateTime verifiedAt,
      int evidenceCount,
      OffsetDateTime createdAt) {}

  /** 整改要求清单（**真分页**）。 */
  public record PlatformViolationRemediationPage(
      List<PlatformViolationRemediation> items,
      long total,
      int page,
      int size,
      long overdueCount,
      String note) {}

  /** 记一条整改要求的入参。 */
  public record PlatformViolationRemediationInput(
      String requirement, OffsetDateTime dueAt, String ownerRef) {}

  /** 整改状态流转的入参。 */
  public record PlatformViolationRemediationStatusInput(String status, String note) {}

  /**
   * 一条整改凭证（**append-only**）。
   *
   * @param evidenceId 凭证 id
   * @param remediationId 所属整改要求 id
   * @param violationId 所属违规 id
   * @param evidenceType 凭证类型
   * @param evidenceRef 凭证引用
   * @param summary 说明
   * @param submittedAt 提交时刻
   */
  public record PlatformViolationEvidence(
      String evidenceId,
      String remediationId,
      String violationId,
      String evidenceType,
      String evidenceRef,
      String summary,
      OffsetDateTime submittedAt) {}

  /** 追加整改凭证的入参。 */
  public record PlatformViolationEvidenceInput(
      String evidenceType, String evidenceRef, String summary, OffsetDateTime submittedAt) {}

  // ---------------------------------------------------------------- 受影响定位

  /** 受影响的一个平台商品（**批量**定位的结果行）。 */
  public record PlatformViolationAffectedListing(
      String listingId,
      String externalItemId,
      String title,
      String status,
      String statusLabel,
      String platformStockState,
      String sourceSubjectType,
      String sourceSubjectRef) {}

  /** 受影响商品的可售量（来自 07 库存，**一次批量调用**）。 */
  public record PlatformViolationAffectedInventory(
      String skuId,
      String skuCode,
      BigDecimal sellable,
      boolean found,
      String note) {}

  /**
   * 「批量定位受影响 listing 与库存」的结果。
   *
   * @param violationId 违规 id
   * @param subjectCount 涉及主体数
   * @param listingCount 受影响 listing 数
   * @param listings 受影响的 listing（批量）
   * @param inventoryAvailable **上游读到了没有**（false ⇒ 不许当成「没有库存」）
   * @param inventory 受影响的库存行（批量）
   * @param inventoryNote 库存口径说明（读不到时说明为什么）
   * @param note 整体口径说明
   */
  public record PlatformViolationAffected(
      String violationId,
      int subjectCount,
      int listingCount,
      List<PlatformViolationAffectedListing> listings,
      boolean inventoryAvailable,
      List<PlatformViolationAffectedInventory> inventory,
      String inventoryNote,
      String note) {}

  // ---------------------------------------------------------------- 批量下架

  /** 批量下架的结果（下游 4 刊登 · 4.1.1.1 批量下架）。 */
  public record PlatformViolationDelistResult(
      String violationId,
      int candidateCount,
      int affectedCount,
      int skipCount,
      List<String> skippedListingIds,
      String outcome,
      String reason,
      String ownerModule,
      String taskId) {}

  // ---------------------------------------------------------------- 同步

  /**
   * 平台违规通知（同步入口的入参项）。
   *
   * @param externalNoticeId 平台侧通知 id（**幂等锚点**，必填）
   * @param violationType 违规类型
   * @param title 标题
   * @param description 说明
   * @param occurredAt 平台判定 / 通知时刻
   * @param notifiedAt 平台下发通知时刻
   * @param deadline 整改期限
   * @param subjects 涉及主体（可空）
   * @param penalties 处罚措施（可空）
   */
  public record PlatformViolationNotice(
      String externalNoticeId,
      String violationType,
      String title,
      String description,
      OffsetDateTime occurredAt,
      OffsetDateTime notifiedAt,
      OffsetDateTime deadline,
      List<PlatformViolationSubjectInput> subjects,
      List<PlatformPenaltyInput> penalties) {}

  /** 同步入口的入参。 */
  public record PlatformViolationSyncInput(List<PlatformViolationNotice> notices) {}

  /**
   * 一次同步的结果。
   *
   * @param runId 同步运行记录 id
   * @param shopId 店铺 id
   * @param fetched 取到的通知条数
   * @param written 新写入的违规条数
   * @param skipped **幂等跳过**的条数（同一条平台违规重复同步）
   * @param failed 失败条数
   * @param failures 失败明细（哪一条、为什么）
   * @param note 口径说明
   */
  public record PlatformViolationSyncResult(
      String runId,
      String shopId,
      int fetched,
      int written,
      int skipped,
      int failed,
      List<String> failures,
      String note) {}

  /** 同步运行记录（**真分页**）。 */
  public record PlatformViolationSyncRun(
      String runId,
      String shopId,
      String shopCode,
      String status,
      OffsetDateTime startedAt,
      OffsetDateTime finishedAt,
      int fetchedCount,
      int writtenCount,
      int skippedCount,
      int failedCount,
      String reason) {}

  /** 同步运行记录清单（**真分页**）。 */
  public record PlatformViolationSyncRunPage(
      List<PlatformViolationSyncRun> items, long total, int page, int size, String note) {}

  // ---------------------------------------------------------------- 人工登记

  /**
   * 人工登记违规的入参。
   *
   * @param shopId 店铺 id
   * @param externalNoticeId 平台侧通知 id（**幂等锚点**，必填）
   * @param violationType 违规类型
   * @param title 标题
   * @param description 说明
   * @param occurredAt 平台判定 / 通知时刻
   * @param notifiedAt 平台下发通知时刻
   * @param deadline 整改期限
   */
  public record PlatformViolationRegisterInput(
      String shopId,
      String externalNoticeId,
      String violationType,
      String title,
      String description,
      OffsetDateTime occurredAt,
      OffsetDateTime notifiedAt,
      OffsetDateTime deadline) {}

  /**
   * 登记结果（**幂等**）。
   *
   * @param violation 违规详情
   * @param created 本次是否真的新建（false ⇒ 命中幂等键，已有记录被原样返回）
   * @param note 口径说明
   */
  public record PlatformViolationRegistration(
      PlatformViolationDetail violation, boolean created, String note) {}

  // ---------------------------------------------------------------- 状态流转

  /** 违规处理状态流转的入参。 */
  public record PlatformViolationStatusInput(String status, String note) {}
}
