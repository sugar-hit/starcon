package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 4.4.2.2 批量搬迁 的传输模型。
 *
 * <p>蓝图：「批量把一批 listing 从一个店铺搬到多个店铺，支持按规则筛选（类目、销量区间、价格区间），并对重复铺货风险给出提示。输入：
 * 筛选规则；目标店铺集合；重复度控制参数。输出：批量搬迁任务 + 结果明细；重复度风险提示。关系：上游 = 4.4.2.1；下游 = 4.3.1.2。
 * <b>搬迁后必须做标题差异化</b>，否则平台可能判定重复铺货并抑制 listing。」
 *
 * <p>🔴 模型层把本模块最容易做丢的三件事钉死：
 *
 * <ol>
 *   <li><b>标题差异化是流程的一部分</b>：请求里<b>没有</b>「原样照搬标题」这个形状。
 *       {@link MigrationExecuteRequest} 只能给出筛选规则、目标店铺集合与重复度控制参数 ——
 *       差异化后的标题由服务层算出来，并逐条留痕在 {@link ListingMigrationContentDiffRow}。
 *   <li><b>「不知道」是一个状态</b>：销量与价格都可能取不到值。模型用 {@code null} 表达
 *       「没有取到」，**不用 0 冒充**；重复度等级里的 {@code UNKNOWN} 是与
 *       {@code LOW/MEDIUM/HIGH/CRITICAL} 并列的一等取值。
 *   <li><b>载荷一律用具名 record</b>，不用 {@code Map<String,Object>}：Map 的值类型会被 springdoc
 *       推成 object，codegen 出来是空对象 {@code {}}（9.6.1.3 已踩过）。
 * </ol>
 */
public final class ListingMigrationModels {
  private ListingMigrationModels() {}

  /** 未知值策略：数值维度取不到值时怎么办。 */
  public static final List<String> UNKNOWN_POLICIES = List.of("EXCLUDE", "INCLUDE", "ONLY");

  /** 排序维度。 */
  public static final List<String> SORT_FIELDS =
      List.of("UPDATED_AT", "CREATED_AT", "TITLE", "PRICE", "SALES");

  /** 重复度等级。UNKNOWN = 无法判定（**不是 0 分**）。 */
  public static final List<String> RISK_LEVELS =
      List.of("LOW", "MEDIUM", "HIGH", "CRITICAL", "UNKNOWN");

  /** 重复度控制参数里「风险达到这一级就跳过搬迁」的取值。NONE = 不按风险跳过。 */
  public static final List<String> MAX_RISK_LEVELS = List.of("NONE", "LOW", "MEDIUM", "HIGH", "CRITICAL");

  /** 目标店铺无任何可比商品（风险 UNKNOWN）时的处置。 */
  public static final List<String> UNKNOWN_RISK_ACTIONS = List.of("PROCEED", "SKIP");

  // ================================================================ 能力声明

  public record ListingMigrationCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  public record ListingMigrationCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false") List<ListingMigrationCapabilityDimension> dimensions,
      @Schema(description = "可筛选的类目维度") List<String> filterDimensions,
      @Schema(description = "未知值策略取值域") List<String> unknownPolicies,
      @Schema(description = "排序维度取值域") List<String> sortFields,
      @Schema(description = "重复度等级取值域") List<String> riskLevels,
      @Schema(description = "🔴 是否保证搬迁结果带差异化标题") boolean titleDifferentiationEnforced,
      @Schema(description = "🔴 差异化保证的说明（前端原样展示）") String titleDifferentiationNote,
      @Schema(description = "🔴 标题差异化规则（每条都留痕）") List<String> titleRules,
      @Schema(description = "🔴 重复度分值的权重（标题 / 类目 / 属性）") String riskWeights,
      @Schema(description = "🔴 重复度等级阈值") String riskThresholds,
      @Schema(description = "🔴 重复度分值是否是算出来的") boolean riskScoreComputed,
      @Schema(description = "🔴 重复度分值的说明") String riskNote,
      @Schema(description = "🔴 幂等保证的说明") String idempotencyNote,
      @Schema(description = "上游依赖说明") String upstreamNote) {}

  // ================================================================ 请求

  /**
   * 筛选规则。
   *
   * @param categoryPaths 类目路径（平台侧原文，精确匹配）
   * @param platformCategoryIds 平台类目 ID（精确匹配）
   * @param priceMin 价格区间下界（含）；价格来源 = V169 writeback_target.current_price
   * @param priceMax 价格区间上界（含）
   * @param salesMin 销量区间下界（含）；**来源未接入**，命中数按未知值策略如实报
   * @param salesMax 销量区间上界（含）
   * @param unknownPolicy 数值维度取不到值时的处置：EXCLUDE 排除 / INCLUDE 保留 / ONLY 只要未知的
   */
  public record MigrationFilterRequest(
      @Schema(description = "源店铺 id（批量搬迁 = 从一个店铺搬到多个店铺）") @NotNull UUID sourceShopId,
      @Schema(description = "类目路径筛选（平台侧原文，精确匹配）") List<String> categoryPaths,
      @Schema(description = "平台类目 ID 筛选（精确匹配）") List<String> platformCategoryIds,
      @Schema(description = "价格区间下界（含；NUMERIC，不是浮点）", nullable = true) @DecimalMin("0") BigDecimal priceMin,
      @Schema(description = "价格区间上界（含；NUMERIC，不是浮点）", nullable = true) @DecimalMin("0") BigDecimal priceMax,
      @Schema(description = "销量区间下界（含；来源未接入时按未知值策略处置）", nullable = true) @DecimalMin("0") BigDecimal salesMin,
      @Schema(description = "销量区间上界（含；来源未接入时按未知值策略处置）", nullable = true) @DecimalMin("0") BigDecimal salesMax,
      @Schema(description = "未知值策略：EXCLUDE / INCLUDE / ONLY", nullable = true) String unknownPolicy,
      @Schema(description = "排序维度：UPDATED_AT / CREATED_AT / TITLE / PRICE / SALES", nullable = true)
          String sortBy,
      @Schema(description = "排序方向：ASC / DESC", nullable = true) String sortOrder,
      @Schema(description = "页码，从 0 开始", nullable = true) Integer page,
      @Schema(description = "每页条数", nullable = true) Integer size) {}

  /** 目标店铺与站点。 */
  public record MigrationTarget(
      @Schema(description = "目标店铺 id（必须与源店铺不同、必须与源店铺同平台）") @NotNull UUID shopId,
      @Schema(description = "目标站点 id（必须已被目标店铺启用）", nullable = true) UUID marketplaceId) {}

  /**
   * 重复度控制参数。
   *
   * @param maxRiskLevel 风险达到该等级及以上的搬迁项会被跳过（NONE = 不按风险跳过）
   * @param onUnknown 目标店铺无任何可比商品（风险 UNKNOWN）时的处置：PROCEED / SKIP
   * @param maxComparables 比对时最多取多少条目标店铺的既有 listing
   * @param titleTemplate 标题模板（可选）；可用占位符 {title} {storeName} {shopCode} {marketplaceCode} {categoryLeaf}
   */
  public record MigrationDuplicatePolicy(
      @Schema(description = "风险达到该等级及以上的搬迁项被跳过：NONE / LOW / MEDIUM / HIGH / CRITICAL", nullable = true)
          String maxRiskLevel,
      @Schema(description = "风险 UNKNOWN 时的处置：PROCEED / SKIP", nullable = true) String onUnknown,
      @Schema(description = "比对上限（默认 200）", nullable = true) Integer maxComparables,
      @Schema(description = "标题模板；可用 {title} {storeName} {shopCode} {marketplaceCode} {categoryLeaf}", nullable = true)
          String titleTemplate) {}

  /**
   * 搬迁请求。
   *
   * <p>🔴 请求体里<b>没有</b>「标题原样照搬」这个形状 —— 差异化是流程的一部分，
   * 由服务层按规则算出并逐条留痕。
   */
  public record MigrationExecuteRequest(
      @Schema(description = "筛选规则") @NotNull @Valid MigrationFilterRequest filter,
      @Schema(description = "目标店铺集合（至少一个）") @NotEmpty List<@Valid MigrationTarget> targets,
      @Schema(description = "重复度控制参数", nullable = true) @Valid MigrationDuplicatePolicy duplicatePolicy,
      @Schema(description = "幂等键；不给时按请求内容派生（同一个请求重复提交天然幂等）", nullable = true)
          @Size(max = 200)
          String idempotencyKey,
      @Schema(description = "备注", nullable = true) @Size(max = 500) String note) {}

  /** 预览请求（**不写任何数据**）。 */
  public record MigrationPreviewRequest(
      @Schema(description = "筛选规则") @NotNull @Valid MigrationFilterRequest filter,
      @Schema(description = "目标店铺集合（至少一个）") @NotEmpty List<@Valid MigrationTarget> targets) {}

  // ================================================================ 筛选说明

  /** 单个筛选维度的命中说明（过滤命中要能解释）。 */
  public record MigrationFilterDimensionReport(
      @Schema(description = "维度键：CATEGORY / PRICE / SALES") String dimension,
      @Schema(description = "该维度是否参与了本次过滤") boolean applied,
      @Schema(description = "该维度的数据源是否已交付") boolean sourceAvailable,
      @Schema(description = "数据来源（表.列）") String source,
      @Schema(description = "未交付时的归属模块") String ownerModule,
      @Schema(description = "未知值策略：EXCLUDE / INCLUDE / ONLY") String unknownPolicy,
      @Schema(description = "该维度上命中的条数") long matchedCount,
      @Schema(description = "该维度上取值为未知（不是 0）的条数") long unknownCount,
      @Schema(description = "人话说明（前端原样展示）") String note) {}

  /** 一次筛选的完整说明。 */
  public record MigrationFilterExplanation(
      @Schema(description = "源店铺内的全部 listing 条数（过滤前的基线）") long baseCount,
      @Schema(description = "过滤后命中的条数") long matchedCount,
      @Schema(description = "逐维度的命中说明") List<MigrationFilterDimensionReport> dimensions,
      @Schema(description = "整条筛选的人话说明（前端原样展示）") String note) {}

  // ================================================================ 预览

  /**
   * 预览里的一个目标店铺（源商品 × 目标店铺）。
   *
   * @param plannedTargetTitle 搬迁后的标题（差异化之后）；差异化失败时为空串
   * @param plannedRules 标题差异化用到的规则（逐条留痕在结果明细里）
   * @param alreadyMigrated 同一对（源商品 × 目标店铺）已经搬迁过
   * @param alreadyClaimed 4.4.2.1 已经把这个商品认领到该店铺
   */
  public record ListingMigrationPreviewTarget(
      @Schema(description = "目标店铺 id") UUID targetShopId,
      @Schema(description = "目标店铺编码") String targetShopCode,
      @Schema(description = "目标店铺名") String targetShopName,
      @Schema(description = "目标站点 id") UUID targetMarketplaceId,
      @Schema(description = "目标站点编码") String targetMarketplaceCode,
      @Schema(description = "🔴 搬迁后的标题（差异化之后）") String plannedTargetTitle,
      @Schema(description = "🔴 标题差异化用到的规则") List<String> plannedRules,
      @Schema(description = "🔴 是否已搬迁过") boolean alreadyMigrated,
      @Schema(description = "🔴 是否已被 4.4.2.1 认领到该店铺") boolean alreadyClaimed,
      @Schema(description = "重复度等级：LOW / MEDIUM / HIGH / CRITICAL / UNKNOWN") String duplicateLevel,
      @Schema(description = "重复度分值（算出来的；UNKNOWN 时为 null，不是 0）") BigDecimal duplicateScore,
      @Schema(description = "重复度判定依据（人话）") String duplicateReason) {}

  /**
   * 预览里的一个候选商品。
   *
   * <p>🔴 分页分的是**候选商品**（源店铺里的 listing），每个候选商品下面挂着它到各个目标店铺的
   * 风险与差异化计划 —— 与「结果明细」一页 N 行的口径一致。
   */
  public record ListingMigrationPreviewCandidate(
      @Schema(description = "源 listing id") UUID sourceListingId,
      @Schema(description = "源 listing 标题") String sourceTitle,
      @Schema(description = "源 listing 类目路径") String sourceCategoryPath,
      @Schema(description = "源 listing 平台类目 ID") String sourcePlatformCategoryId,
      @Schema(description = "源 listing 状态") String sourceStatus,
      @Schema(description = "🔴 价格来源与取值状态（未知不是 0）") String priceNote,
      @Schema(description = "逐个目标店铺的风险与差异化计划") List<ListingMigrationPreviewTarget> targets) {}

  /** 预览结果。 */
  public record ListingMigrationPreview(
      @Schema(description = "筛选说明") MigrationFilterExplanation explanation,
      @Schema(description = "命中的候选商品") List<ListingMigrationPreviewCandidate> items,
      @Schema(description = "本页条数") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "命中的候选商品总数") long total) {}

  // ================================================================ 结果

  /** 一次批量搬迁的批次行。 */
  public record ListingMigrationBatchRow(
      @Schema(description = "搬迁批次 id") UUID batchId,
      @Schema(description = "幂等键") String idempotencyKey,
      @Schema(description = "源店铺 id") UUID sourceShopId,
      @Schema(description = "源店铺编码") String sourceShopCode,
      @Schema(description = "源店铺名") String sourceShopName,
      @Schema(description = "目标店铺 id 列表") List<UUID> targetShopIds,
      @Schema(description = "目标店铺与站点的明细") List<String> targetDetail,
      @Schema(description = "筛选规则快照") String filterRule,
      @Schema(description = "重复度控制参数快照") String duplicatePolicy,
      @Schema(description = "批次状态：EXECUTED / PARTIAL / FAILED") String state,
      @Schema(description = "命中候选数") int candidateCount,
      @Schema(description = "结果条数") int totalCount,
      @Schema(description = "已搬迁条数") int migratedCount,
      @Schema(description = "已跳过条数") int skippedCount,
      @Schema(description = "失败条数") int failedCount,
      @Schema(description = "待发布批次 id（复用 4.3.1.2 的 V183 批次）", nullable = true) UUID publishBatchId,
      @Schema(description = "操作人", nullable = true) UUID actorId,
      @Schema(description = "备注", nullable = true) String note,
      @Schema(description = "创建时间") OffsetDateTime createdAt,
      @Schema(description = "更新时间") OffsetDateTime updatedAt) {}

  /** 差异化留痕的一行。 */
  public record ListingMigrationContentDiffRow(
      @Schema(description = "字段：TITLE / BULLETS / DESCRIPTION") String field,
      @Schema(description = "原值原文") String sourceValue,
      @Schema(description = "差异化后的值") String targetValue,
      @Schema(description = "依据的规则") String ruleId,
      @Schema(description = "规则参数") MigrationRuleParams ruleParams,
      @Schema(description = "谁改的", nullable = true) UUID actorId,
      @Schema(description = "留痕时间") OffsetDateTime createdAt) {}

  /** 规则参数（具名 record，避免 codegen 出空对象）。 */
  public record MigrationRuleParams(
      @Schema(description = "目标店铺名") String storeName,
      @Schema(description = "目标店铺编码") String shopCode,
      @Schema(description = "目标站点编码") String marketplaceCode,
      @Schema(description = "用到的模板（仅 TEMPLATE 规则）") String template,
      @Schema(description = "用于消歧的序号（仅 COLLISION_RESOLVE 规则）", nullable = true) Integer sequence,
      @Schema(description = "原因说明", nullable = true) String reason) {}

  /** 重复度判定依据（具名 record）。 */
  public record MigrationDuplicateBasis(
      @Schema(description = "参与比对的源标题") String evaluatedTitle,
      @Schema(description = "标题权重") double titleWeight,
      @Schema(description = "类目权重") double categoryWeight,
      @Schema(description = "属性权重") double attributeWeight,
      @Schema(description = "CRITICAL 阈值") double criticalThreshold,
      @Schema(description = "HIGH 阈值") double highThreshold,
      @Schema(description = "MEDIUM 阈值") double mediumThreshold,
      @Schema(description = "目标店铺里参与比对的既有 listing 条数") int comparableCount,
      @Schema(description = "最像的那条既有 listing id", nullable = true) UUID bestListingId,
      @Schema(description = "最像的那条既有 listing 标题") String bestTitle,
      @Schema(description = "标题相似度", nullable = true) BigDecimal titleSimilarity,
      @Schema(description = "类目相似度", nullable = true) BigDecimal categorySimilarity,
      @Schema(description = "属性相似度", nullable = true) BigDecimal attributeSimilarity,
      @Schema(description = "标题命中的字符二元组数", nullable = true) Integer sharedBigrams,
      @Schema(description = "标题字符二元组总数", nullable = true) Integer unionBigrams,
      @Schema(description = "属性命中的键") List<String> matchedAttributes,
      @Schema(description = "属性冲突的键") List<String> conflictingAttributes,
      @Schema(description = "人话结论（前端原样展示）") String explanation) {}

  /** 一条搬迁结果明细。 */
  public record ListingMigrationItemRow(
      @Schema(description = "搬迁结果 id") UUID itemId,
      @Schema(description = "批次 id") UUID batchId,
      @Schema(description = "源 listing id") UUID sourceListingId,
      @Schema(description = "源 listing 标题") String sourceTitle,
      @Schema(description = "源 listing 的平台商品 ID 原文") String sourceExternalItemId,
      @Schema(description = "源店铺 id") UUID sourceShopId,
      @Schema(description = "源店铺编码") String sourceShopCode,
      @Schema(description = "目标店铺 id") UUID targetShopId,
      @Schema(description = "目标店铺编码") String targetShopCode,
      @Schema(description = "目标店铺名") String targetShopName,
      @Schema(description = "目标站点 id") UUID targetMarketplaceId,
      @Schema(description = "目标站点编码") String targetMarketplaceCode,
      @Schema(description = "目标 listing id（本次搬迁新建的草稿）", nullable = true) UUID targetListingId,
      @Schema(description = "🔴 目标 listing 的平台商品 ID 保留位（草稿未发布，平台尚未分配）", nullable = true)
          String targetExternalItemId,
      @Schema(description = "🔴 源标题") String originalTitle,
      @Schema(description = "🔴 差异化后的标题") String differentiatedTitle,
      @Schema(description = "🔴 标题是否真的差异化（与原标题归一化后不同）") boolean titleDifferentiated,
      @Schema(description = "结果状态：MIGRATED / SKIPPED / FAILED") String state,
      @Schema(description = "跳过原因（SKIPPED）", nullable = true) String skipReason,
      @Schema(description = "失败原因（FAILED）", nullable = true) String failureReason,
      @Schema(description = "重复度等级：LOW / MEDIUM / HIGH / CRITICAL / UNKNOWN") String duplicateLevel,
      @Schema(description = "重复度分值（算出来的；UNKNOWN 时为 null，不是 0）", nullable = true) BigDecimal duplicateScore,
      @Schema(description = "重复度判定依据", nullable = true) MigrationDuplicateBasis duplicateBasis,
      @Schema(description = "待发布任务 id（复用 4.3.1.2 的 V183 任务）", nullable = true) UUID publishTaskId,
      @Schema(description = "操作人", nullable = true) UUID actorId,
      @Schema(description = "创建时间") OffsetDateTime createdAt,
      @Schema(description = "逐字段的差异化留痕") List<ListingMigrationContentDiffRow> diffs) {}

  /** 「已搬迁」查询的一行。 */
  public record ListingMigrationMigratedRow(
      @Schema(description = "搬迁结果 id") UUID itemId,
      @Schema(description = "批次 id") UUID batchId,
      @Schema(description = "源 listing id") UUID sourceListingId,
      @Schema(description = "源标题") String sourceTitle,
      @Schema(description = "目标店铺 id") UUID targetShopId,
      @Schema(description = "目标店铺编码") String targetShopCode,
      @Schema(description = "目标 listing id", nullable = true) UUID targetListingId,
      @Schema(description = "🔴 差异化后的标题") String differentiatedTitle,
      @Schema(description = "重复度等级") String duplicateLevel,
      @Schema(description = "重复度分值（UNKNOWN 时为 null）", nullable = true) BigDecimal duplicateScore,
      @Schema(description = "搬迁时间") OffsetDateTime createdAt) {}

  /** 「重复铺货风险」查询的一行。 */
  public record ListingMigrationDuplicateRow(
      @Schema(description = "评估 id") UUID assessmentId,
      @Schema(description = "搬迁结果 id") UUID itemId,
      @Schema(description = "批次 id") UUID batchId,
      @Schema(description = "源 listing id") UUID sourceListingId,
      @Schema(description = "源标题") String sourceTitle,
      @Schema(description = "目标店铺 id") UUID targetShopId,
      @Schema(description = "目标店铺编码") String targetShopCode,
      @Schema(description = "综合分（算出来的；UNKNOWN 时为 null，不是 0）", nullable = true) BigDecimal score,
      @Schema(description = "风险等级：LOW / MEDIUM / HIGH / CRITICAL / UNKNOWN") String level,
      @Schema(description = "最像的那条既有 listing id", nullable = true) UUID comparableListingId,
      @Schema(description = "最像的那条既有 listing 标题") String comparableTitle,
      @Schema(description = "参与比对的既有 listing 条数") int comparableCount,
      @Schema(description = "标题相似度", nullable = true) BigDecimal titleSimilarity,
      @Schema(description = "类目相似度", nullable = true) BigDecimal categorySimilarity,
      @Schema(description = "属性相似度", nullable = true) BigDecimal attributeSimilarity,
      @Schema(description = "判定依据", nullable = true) MigrationDuplicateBasis basis,
      @Schema(description = "评估时刻") OffsetDateTime evaluatedAt) {}

  // ================================================================ 筛选选项

  /** 类目筛选项（来自源店铺的真实数据）。 */
  public record MigrationCategoryOption(
      @Schema(description = "类目路径") String categoryPath,
      @Schema(description = "平台类目 ID") String platformCategoryId,
      @Schema(description = "该类目下的 listing 条数") long listingCount) {}

  /** 筛选面板需要的全部选项。 */
  public record MigrationFilterOptions(
      @Schema(description = "可作为源店铺的店铺列表") List<String> shops,
      @Schema(description = "源店铺下的真实类目（按 listing 条数倒序）") List<MigrationCategoryOption> categories) {}
}
