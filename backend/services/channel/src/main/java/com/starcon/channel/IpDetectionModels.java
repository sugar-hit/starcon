package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 16.4.1.2 检测与预警的传输模型。
 *
 * <p>蓝图：「对商品内容做侵权检测：标题/描述/图片/品牌字段比对侵权库，输出风险等级与命中项；
 * 接入外部商标/专利查询；已上架商品的定期巡检。输入：商品内容与图片；侵权库；外部查询结果。
 * 输出：检测报告 + 预警 + 整改建议。关系：上游 = 16.4.1.1、4.2.1.1；下游 = 16.2.1.2 拦截、16.4.2 授权核验。
 * <b>图片侵权检测难度高于文本</b>，误判与漏判都要留人工复核环节。」
 *
 * <p>🔴🔴 本模块对外最要紧的五条事实，全部<b>在 API 与页面上如实表达</b>：
 *
 * <ol>
 *   <li><b>风险等级沿用上游口径</b>（HIGH / MEDIUM / LOW，另有 NONE = 无命中），
 *       <b>不是</b> 0..100 的裸数字。</li>
 *   <li><b>命中项必须可解释</b>：每条命中都点名<b>命中了哪一条目</b>（编码快照）、
 *       <b>命中了哪些匹配特征</b>、<b>在哪个字段</b>、<b>命中处原文</b>。</li>
 *   <li><b>图片命中一律要人工复核</b>（{@code needsManualReview}），
 *       <b>不能</b>直接定性为「高风险」。</li>
 *   <li><b>结论三态</b>：CLEAR / REVIEW_REQUIRED / ACTION_REQUIRED ——
 *       「还没判」与「判了没问题」是<b>两件事</b>。</li>
 *   <li><b>本模块不拦截</b>：{@code recommendedAction} 只是<b>建议</b>，
 *       拦截归 16.2.1.2，授权核验归 16.4.2。</li>
 * </ol>
 */
public final class IpDetectionModels {

  private IpDetectionModels() {}

  // ---------------------------------------------------------------- 能力声明

  /**
   * 一个下游（或维度）的交付状态。
   *
   * @param moduleId 模块号
   * @param moduleName 模块名
   * @param ownerModule 归谁（available=false 时必填）
   * @param available 当前是否真的可用
   * @param impact 影响说明（界面原样显示）
   */
  public record IpDetectionCapabilityGap(
      String moduleId,
      String moduleName,
      String ownerModule,
      boolean available,
      String impact) {}

  /**
   * 能力声明。
   *
   * @param gaps 下游 / 外部边界的交付状态清单
   * @param riskLevels 风险等级值域
   * @param riskLevelLabels 风险等级人话
   * @param conclusions 结论值域
   * @param conclusionLabels 结论人话
   * @param recommendedActions 建议动作值域
   * @param recommendedActionLabels 建议动作人话
   * @param subjectTypes 检测对象类型值域
   * @param subjectTypeLabels 检测对象类型人话
   * @param fields 命中字段值域
   * @param fieldLabels 命中字段人话
   * @param featureKinds 命中特征分类值域
   * @param featureKindLabels 命中特征分类人话
   * @param alertTypes 预警类型值域
   * @param alertTypeLabels 预警类型人话
   * @param alertStatuses 预警状态值域
   * @param alertStatusLabels 预警状态人话
   * @param remediationActions 整改动作值域
   * @param remediationActionLabels 整改动作人话
   * @param remediationStatuses 整改状态值域
   * @param remediationStatusLabels 整改状态人话
   * @param reviewReasons 复核原因值域
   * @param reviewReasonLabels 复核原因人话
   * @param reviewStatuses 复核状态值域
   * @param reviewStatusLabels 复核状态人话
   * @param queryStatuses 外部查询状态值域
   * @param queryStatusLabels 外部查询状态人话
   * @param planScopes 巡检范围值域
   * @param planScopeLabels 巡检范围人话
   * @param riskLevelNote 风险等级口径说明
   * @param matchNote 比对口径说明
   * @param imageNote 图片检测口径说明（**最要紧的一条**）
   * @param reviewNote 人工复核口径说明
   * @param externalNote 外部查询口径说明
   * @param inspectionNote 巡检口径说明
   * @param upstreamNote 上游说明
   * @param downstreamNote 下游说明
   */
  public record IpDetectionCapabilities(
      List<IpDetectionCapabilityGap> gaps,
      List<String> riskLevels,
      Map<String, String> riskLevelLabels,
      List<String> conclusions,
      Map<String, String> conclusionLabels,
      List<String> recommendedActions,
      Map<String, String> recommendedActionLabels,
      List<String> subjectTypes,
      Map<String, String> subjectTypeLabels,
      List<String> fields,
      Map<String, String> fieldLabels,
      List<String> featureKinds,
      Map<String, String> featureKindLabels,
      List<String> alertTypes,
      Map<String, String> alertTypeLabels,
      List<String> alertStatuses,
      Map<String, String> alertStatusLabels,
      List<String> remediationActions,
      Map<String, String> remediationActionLabels,
      List<String> remediationStatuses,
      Map<String, String> remediationStatusLabels,
      List<String> reviewReasons,
      Map<String, String> reviewReasonLabels,
      List<String> reviewStatuses,
      Map<String, String> reviewStatusLabels,
      List<String> queryStatuses,
      Map<String, String> queryStatusLabels,
      List<String> planScopes,
      Map<String, String> planScopeLabels,
      String riskLevelNote,
      String matchNote,
      String imageNote,
      String reviewNote,
      String externalNote,
      String inspectionNote,
      String upstreamNote,
      String downstreamNote) {}

  // ---------------------------------------------------------------- 入参

  /**
   * 发起一次检测的入参。
   *
   * @param subjectType 检测对象类型（LISTING / DRAFT / MANUAL）
   * @param listingId 已上架商品 id（subjectType=LISTING 时必填）
   * @param draftId 刊登编辑器草稿 id（subjectType=DRAFT 时必填）
   * @param title 标题（subjectType=MANUAL 时必填）
   * @param description 描述（MANUAL）
   * @param bulletPoints 卖点（MANUAL）
   * @param imageUrls 图片（MANUAL）
   * @param brandName 品牌名（MANUAL；LISTING / DRAFT 时从上游解析，读不到会显式说明）
   * @param includeImage 是否包含图片检测（默认含；关掉它等于承认「图片不用查」）
   */
  public record IpDetectionSubjectInput(
      @Schema(description = "🔴 检测对象类型：LISTING / DRAFT / MANUAL")
          String subjectType,
      @Schema(description = "已上架商品 id（subjectType=LISTING 时必填）") String listingId,
      @Schema(description = "刊登编辑器草稿 id（subjectType=DRAFT 时必填）") String draftId,
      @Schema(description = "标题（subjectType=MANUAL 时必填）") String title,
      @Schema(description = "描述（subjectType=MANUAL）") String description,
      @Schema(description = "卖点（subjectType=MANUAL）") List<String> bulletPoints,
      @Schema(description = "图片（subjectType=MANUAL）") List<String> imageUrls,
      @Schema(description = "品牌名（subjectType=MANUAL 时由调用方声明）") String brandName,
      @Schema(description = "是否包含图片检测（默认含）") Boolean includeImage) {}

  /**
   * 一次检测的<b>被比对内容</b>（服务层解析后的真实内容）。
   *
   * @param subjectType 对象类型
   * @param subjectId 对象 id
   * @param label 对象的人话名
   * @param shopId 目标店铺
   * @param platformCode 平台编码
   * @param fields 实际拿到的可比对字段（**读不到就是空**，不是占位符）
   * @param brandAvailable 品牌是否解析到了（false ⇒ 品牌维度**没查**，不是「没有品牌」）
   * @param brandNote 品牌解析说明
   * @param sourceNote 内容来源说明
   */
  public record IpDetectionResolvedSubject(
      String subjectType,
      String subjectId,
      String label,
      String shopId,
      String platformCode,
      List<IpDetectionField> fields,
      boolean brandAvailable,
      String brandNote,
      String sourceNote) {}

  /**
   * 一段可比对的内容。
   *
   * @param field 字段（TITLE / DESCRIPTION / BULLET / IMAGE / BRAND）
   * @param text 原文
   * @param reference 该段内容的出处（图片 URL / 字段名）
   */
  public record IpDetectionField(String field, String text, String reference) {}

  // ---------------------------------------------------------------- 报告

  /**
   * 一份检测报告。
   *
   * @param reportId 报告 id
   * @param subjectType 检测对象类型
   * @param subjectTypeLabel 检测对象类型人话
   * @param subjectId 检测对象 id
   * @param subjectLabel 检测对象的人话名
   * @param shopId 目标店铺
   * @param platformCode 平台编码
   * @param triggerType 触发方式（MANUAL / INSPECTION）
   * @param inspectionRunId 巡检运行 id（巡检产生的报告才有）
   * @param riskLevel 风险等级（HIGH / MEDIUM / LOW / NONE）
   * @param riskLevelLabel 风险等级人话
   * @param conclusion 结论
   * @param conclusionLabel 结论人话
   * @param recommendedAction 建议的下游动作
   * @param recommendedActionLabel 建议动作人话
   * @param textHitCount 文本 / 品牌字段命中数
   * @param imageHitCount 图片命中数
   * @param entryHitCount 命中的不同条目 / 外部命中数
   * @param pendingReviewCount 待人工复核的命中数
   * @param scannedFieldCount 实际参与比对的字段数
   * @param sourceNote 内容来源说明
   * @param createdAt 检测时刻
   */
  public record IpDetectionReport(
      String reportId,
      String subjectType,
      String subjectTypeLabel,
      String subjectId,
      String subjectLabel,
      String shopId,
      String platformCode,
      String triggerType,
      String inspectionRunId,
      String riskLevel,
      String riskLevelLabel,
      String conclusion,
      String conclusionLabel,
      String recommendedAction,
      String recommendedActionLabel,
      int textHitCount,
      int imageHitCount,
      int entryHitCount,
      int pendingReviewCount,
      int scannedFieldCount,
      String sourceNote,
      OffsetDateTime createdAt) {}

  /**
   * 检测报告清单（**真分页**）。
   *
   * @param items 报告行
   * @param total 总条数
   * @param page 当前页
   * @param size 每页条数
   * @param riskLevelCounts 按风险等级的计数（覆盖整个筛选结果集）
   * @param conclusionCounts 按结论的计数（覆盖整个筛选结果集）
   * @param pendingReviewTotal 待人工复核的命中总数
   * @param note 口径说明
   */
  public record IpDetectionReportPage(
      List<IpDetectionReport> items,
      long total,
      int page,
      int size,
      Map<String, Long> riskLevelCounts,
      Map<String, Long> conclusionCounts,
      long pendingReviewTotal,
      String note) {}

  /**
   * 报告详情（报告 + 命中项 + 预警 + 整改建议 + 复核任务）。
   *
   * @param report 报告
   * @param hits 命中项
   * @param alerts 预警
   * @param remediations 整改建议
   * @param reviewTasks 复核任务
   * @param note 口径说明
   */
  public record IpDetectionReportDetail(
      IpDetectionReport report,
      List<IpDetectionHit> hits,
      List<IpDetectionAlert> alerts,
      List<IpDetectionRemediation> remediations,
      List<IpDetectionReviewTask> reviewTasks,
      String note) {}

  // ---------------------------------------------------------------- 命中项

  /**
   * 一条命中项。
   *
   * @param hitId 命中 id
   * @param reportId 所属报告 id
   * @param entryId 命中的侵权库条目 id（外部命中时为空）
   * @param externalHitId 命中的外部查询命中 id（本地条目命中时为空）
   * @param entryCode 条目编码**快照**
   * @param rightType 权利类型**快照**
   * @param riskLevel 风险等级**快照**
   * @param field 命中字段
   * @param fieldLabel 命中字段人话
   * @param featureKind 命中特征分类
   * @param featureKindLabel 命中特征分类人话
   * @param matchedFeature 命中的匹配特征
   * @param matchedText 命中处原文片段
   * @param needsManualReview 是否要人工复核（**图片命中恒为 true**）
   * @param reviewStatus 该命中的复核状态（还没进复核队列时为空）
   * @param createdAt 命中时刻
   */
  public record IpDetectionHit(
      String hitId,
      String reportId,
      String entryId,
      String externalHitId,
      String entryCode,
      String rightType,
      String riskLevel,
      String field,
      String fieldLabel,
      String featureKind,
      String featureKindLabel,
      String matchedFeature,
      String matchedText,
      boolean needsManualReview,
      String reviewStatus,
      OffsetDateTime createdAt) {}

  // ---------------------------------------------------------------- 预警

  /**
   * 一条预警。
   *
   * @param alertId 预警 id
   * @param reportId 所属报告 id
   * @param alertLevel 预警等级
   * @param alertLevelLabel 预警等级人话
   * @param alertType 预警类型
   * @param alertTypeLabel 预警类型人话
   * @param title 标题
   * @param detail 详情
   * @param status 状态
   * @param statusLabel 状态人话
   * @param acknowledgedBy 确认人
   * @param acknowledgedAt 确认时刻
   * @param resolutionNote 处置说明
   * @param createdAt 创建时刻
   */
  public record IpDetectionAlert(
      String alertId,
      String reportId,
      String alertLevel,
      String alertLevelLabel,
      String alertType,
      String alertTypeLabel,
      String title,
      String detail,
      String status,
      String statusLabel,
      String acknowledgedBy,
      OffsetDateTime acknowledgedAt,
      String resolutionNote,
      OffsetDateTime createdAt) {}

  /** 改预警状态的入参。 */
  public record IpDetectionAlertStatusInput(String status, String resolutionNote) {}

  // ---------------------------------------------------------------- 整改建议

  /**
   * 一条整改建议。
   *
   * @param remediationId 建议 id
   * @param reportId 所属报告 id
   * @param hitId 针对的命中 id（可空）
   * @param actionCode 整改动作类型
   * @param actionLabel 整改动作人话
   * @param suggestion 建议正文
   * @param priority 优先级
   * @param status 状态
   * @param statusLabel 状态人话
   * @param assigneeId 负责人
   * @param dueAt 截止时刻
   * @param completedAt 完成时刻
   * @param createdAt 创建时刻
   */
  public record IpDetectionRemediation(
      String remediationId,
      String reportId,
      String hitId,
      String actionCode,
      String actionLabel,
      String suggestion,
      String priority,
      String status,
      String statusLabel,
      String assigneeId,
      OffsetDateTime dueAt,
      OffsetDateTime completedAt,
      OffsetDateTime createdAt) {}

  /**
   * 新增整改建议的入参。
   *
   * @param reportId 报告 id
   * @param hitId 针对的命中 id（可空）
   * @param actionCode 整改动作类型
   * @param suggestion 建议正文
   * @param priority 优先级
   * @param assigneeId 负责人
   * @param dueAt 截止时刻
   */
  public record IpDetectionRemediationInput(
      String reportId,
      String hitId,
      String actionCode,
      String suggestion,
      String priority,
      String assigneeId,
      OffsetDateTime dueAt) {}

  /** 改整改建议状态的入参。 */
  public record IpDetectionRemediationStatusInput(String status) {}

  // ---------------------------------------------------------------- 人工复核

  /**
   * 一条复核任务。
   *
   * @param reviewTaskId 复核任务 id
   * @param reportId 所属报告 id
   * @param hitId 针对的命中 id
   * @param reason 复核原因
   * @param reasonLabel 复核原因人话
   * @param status 状态
   * @param statusLabel 状态人话
   * @param reviewerId 复核人
   * @param reviewedAt 复核时刻
   * @param reviewComment 复核意见
   * @param createdAt 创建时刻
   */
  public record IpDetectionReviewTask(
      String reviewTaskId,
      String reportId,
      String hitId,
      String reason,
      String reasonLabel,
      String status,
      String statusLabel,
      String reviewerId,
      OffsetDateTime reviewedAt,
      String reviewComment,
      OffsetDateTime createdAt) {}

  /**
   * 提一条复核（文本命中被判误判时的申诉）。
   *
   * @param reportId 报告 id
   * @param hitId 针对的命中 id
   * @param reason 复核原因（TEXT_MISMATCH / APPEAL）
   * @param comment 申诉说明
   */
  public record IpDetectionReviewInput(String reportId, String hitId, String reason, String comment) {}

  /**
   * 复核结论的入参。
   *
   * @param status CONFIRMED / DISMISSED
   * @param comment 复核意见（**必填**：没意见的复核等于没复核）
   */
  public record IpDetectionReviewDecisionInput(String status, String comment) {}

  /** 预警清单（**真分页**）。 */
  public record IpDetectionAlertPage(
      List<IpDetectionAlert> items, long total, int page, int size, String note) {}

  /** 整改建议清单（**真分页**）。 */
  public record IpDetectionRemediationPage(
      List<IpDetectionRemediation> items, long total, int page, int size, String note) {}

  /** 人工复核队列清单（**真分页**）。 */
  public record IpDetectionReviewTaskPage(
      List<IpDetectionReviewTask> items, long total, int page, int size, String note) {}

  // ---------------------------------------------------------------- 外部查询

  /**
   * 一条外部商标 / 专利查询登记。
   *
   * @param queryId 查询 id
   * @param queryCode 查询编码（租户内幂等锚点）
   * @param rightType 权利类型
   * @param rightTypeLabel 权利类型人话
   * @param keyword 查询词
   * @param platformCode 适用平台
   * @param sourceName 查询来源
   * @param queryStatus 查询状态
   * @param queryStatusLabel 查询状态人话
   * @param resultCount 结果条数
   * @param errorMessage 失败原因
   * @param queriedAt 查询时刻
   * @param note 备注
   * @param createdAt 创建时刻
   */
  public record IpDetectionExternalQuery(
      String queryId,
      String queryCode,
      String rightType,
      String rightTypeLabel,
      String keyword,
      String platformCode,
      String sourceName,
      String queryStatus,
      String queryStatusLabel,
      int resultCount,
      String errorMessage,
      OffsetDateTime queriedAt,
      String note,
      OffsetDateTime createdAt) {}

  /**
   * 登记一条外部查询的入参。
   *
   * @param queryCode 查询编码（幂等锚点）
   * @param rightType 权利类型
   * @param keyword 查询词
   * @param platformCode 适用平台
   * @param sourceName 查询来源
   * @param note 备注
   */
  public record IpDetectionExternalQueryInput(
      String queryCode,
      String rightType,
      String keyword,
      String platformCode,
      String sourceName,
      String note) {}

  /**
   * 登记外部查询结果的入参。
   *
   * @param queryId 查询 id
   * @param matchedKey 外部库返回的关键词 / 品牌名
   * @param rightType 权利类型
   * @param riskLevel 风险等级
   * @param registrationNo 注册号
   * @param rightsHolder 权利人
   * @param sourceName 来源
   */
  public record IpDetectionExternalHitInput(
      String queryId,
      String matchedKey,
      String rightType,
      String riskLevel,
      String registrationNo,
      String rightsHolder,
      String sourceName) {}

  /**
   * 一条外部查询命中。
   *
   * @param externalHitId 命中 id
   * @param queryId 所属查询 id
   * @param matchedKey 关键词 / 品牌名
   * @param rightType 权利类型
   * @param riskLevel 风险等级
   * @param registrationNo 注册号
   * @param rightsHolder 权利人
   * @param sourceName 来源
   * @param createdAt 登记时刻
   */
  public record IpDetectionExternalHit(
      String externalHitId,
      String queryId,
      String matchedKey,
      String rightType,
      String riskLevel,
      String registrationNo,
      String rightsHolder,
      String sourceName,
      OffsetDateTime createdAt) {}

  /** 外部查询清单。 */
  public record IpDetectionExternalQueryPage(
      List<IpDetectionExternalQuery> items, long total, int page, int size, String note) {}

  /** 外部查询详情（查询 + 命中的外部结果）。 */
  public record IpDetectionExternalQueryDetail(
      IpDetectionExternalQuery query, List<IpDetectionExternalHit> hits, String note) {}

  // ---------------------------------------------------------------- 巡检

  /**
   * 一条巡检计划。
   *
   * @param planId 计划 id
   * @param planCode 计划编码（租户内幂等锚点）
   * @param scope 巡检范围
   * @param scopeLabel 范围人话
   * @param shopId 目标店铺
   * @param intervalDays 巡检间隔（天）
   * @param includeImage 是否包含图片检测
   * @param enabled 是否启用
   * @param lastRunAt 最近一次运行时刻
   * @param nextRunAt 下一次应运行时刻
   * @param note 备注
   * @param createdAt 创建时刻
   */
  public record IpDetectionInspectionPlan(
      String planId,
      String planCode,
      String scope,
      String scopeLabel,
      String shopId,
      int intervalDays,
      boolean includeImage,
      boolean enabled,
      OffsetDateTime lastRunAt,
      OffsetDateTime nextRunAt,
      String note,
      OffsetDateTime createdAt) {}

  /**
   * 新建 / 改巡检计划的入参。
   *
   * @param planCode 计划编码（幂等锚点）
   * @param scope 巡检范围（ALL / SHOP）
   * @param shopId 目标店铺（scope=SHOP 时必填）
   * @param intervalDays 巡检间隔（天）
   * @param includeImage 是否包含图片检测
   * @param enabled 是否启用
   * @param note 备注
   */
  public record IpDetectionInspectionPlanInput(
      String planCode, String scope, String shopId, Integer intervalDays, Boolean includeImage,
      Boolean enabled, String note) {}

  /**
   * 一次巡检运行。
   *
   * @param runId 运行 id
   * @param planId 所属计划 id
   * @param planCode 所属计划编码
   * @param status 运行状态
   * @param statusLabel 状态人话
   * @param listingScanned 扫了多少个商品
   * @param reportCount 产出了多少份报告
   * @param hitListingCount 有多少个商品查出了命中
   * @param errorMessage 失败原因
   * @param startedAt 开始时刻
   * @param finishedAt 结束时刻
   */
  public record IpDetectionInspectionRun(
      String runId,
      String planId,
      String planCode,
      String status,
      String statusLabel,
      int listingScanned,
      int reportCount,
      int hitListingCount,
      String errorMessage,
      OffsetDateTime startedAt,
      OffsetDateTime finishedAt) {}

  /** 巡检运行清单。 */
  public record IpDetectionInspectionRunPage(
      List<IpDetectionInspectionRun> items, long total, int page, int size, String note) {}

  /**
   * 巡检运行详情（运行 + 这一轮产出的报告）。
   *
   * @param run 运行
   * @param reports 这一轮的检测报告
   * @param note 口径说明
   */
  public record IpDetectionInspectionRunDetail(
      IpDetectionInspectionRun run, List<IpDetectionReport> reports, String note) {}
}
