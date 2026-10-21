package com.starcon.channel;

import static com.starcon.channel.AdCampaignModels.AdCampaignDailyMetricRow;
import static com.starcon.channel.AdCampaignModels.AdCampaignOperationLogRow;
import static com.starcon.channel.AdCampaignModels.AdCampaignRow;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 14.1.1.2 活动详情 的传输模型。
 *
 * <p>蓝图：「单活动全景：基本设置、预算与消耗曲线、广告组列表、投放商品、关键词、创意（视频/图片）、
 * 每日表现明细、平台侧修改历史。输入：campaign_id；聚合子资源。
 * 输出：详情视图 + 操作入口（调预算、改竞价、暂停、复制结构）。
 * 关系：上游 = 14.1.1.1；下游 = 14.1.2.1 广告组、14.3.1.1 报表。
 * <b>"复制结构"是高价值操作</b>：把跑得好的活动结构复制到新店铺/新 ASIN，是广告提效的核心动作。」
 *
 * <p>🔴 这个模型层钉死本模块最容易做丢的四件事：
 *
 * <ol>
 *   <li><b>「平台侧修改历史」必须显式不可用</b>。上游没有平台广告 API 同步器 ⇒ 没有同步源。
 *       {@link AdCampaignChangeHistory} 把 {@code available=false} + {@code ownerModule} +
 *       {@code impact} 做成**结构里的字段**，并把「当前展示的是本地操作审计」写在
 *       {@code localAuditNote} 里 —— 编一张假的「平台改历史」表比不提供更糟。
 *   <li><b>「复制结构」的结构副本是真的，平台侧还不是</b>。{@link AdCampaignCopyResult} 同时给
 *       {@code targetCampaignId}（真的落库了）与 {@code pendingPlatformWriteback}（平台侧还没有），
 *       少任何一半都是在骗人。
 *   <li><b>关键词是读上游的</b>（14.1.3.1 的 {@code ad_keyword}），本模块不建第二张关键词表 ——
 *       同一个词在两个地方各有一份，改价就会改到不同的地方去。
 *   <li><b>比率的 {@code null} 与 {@code 0} 含义相反</b>：{@code null} = 不可比（分母为 0），
 *       {@code 0} = 可比但真的是零。
 * </ol>
 *
 * <p>🔴 金额与数量一律 {@link BigDecimal}，**没有 double**：竞价 / 预算 / 消耗 / 销售额直接进
 * 财务与归因，曝光 / 点击 / 订单是跨平台可比的计数。
 */
public final class AdCampaignDetailModels {
  private AdCampaignDetailModels() {}

  // ================================================================ 能力声明

  public record AdCampaignDetailCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  /**
   * 改竞价的护栏上限。
   *
   * <p>🔴 蓝图 14 章关键约束：「AI/规则自动调整必须有上限保护」——
   * 一次手滑把默认竞价写成 1000 就是把预算烧光。上下限**一起返回给界面**，
   * 让运营在点下去之前就知道天花板在哪。
   */
  public record AdBidGuardrails(
      @Schema(description = "单个广告组允许的竞价上限（绝对值）", type = "string")
          BigDecimal maxBid,
      @Schema(description = "单个广告组一次允许调整的竞价上限（绝对值）", type = "string")
          BigDecimal maxSingleBidDelta,
      @Schema(description = "一次请求最多允许调整的广告组条数") int maxGroupsPerRequest,
      @Schema(description = "护栏说明") String note) {}

  public record AdCampaignDetailCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false")
          List<AdCampaignDetailCapabilityDimension> dimensions,
      @Schema(description = "创意类型取值") List<String> creativeTypes,
      @Schema(description = "复制结构时投放商品的处理方式取值") List<String> productModes,
      @Schema(description = "🔴 改竞价护栏上限") AdBidGuardrails bidGuardrails,
      @Schema(description = "🔴 ACOS / ROAS 口径定义") String metricDefinitionNote,
      @Schema(description = "🔴 平台侧修改历史为什么不可用") String platformChangeHistoryNote,
      @Schema(description = "🔴 复制结构的语义说明（结构副本是真的，平台侧还不是）")
          String copyStructureNote,
      @Schema(description = "上游依赖说明") String upstreamNote,
      @Schema(description = "下游与边界说明") String boundaryNote) {}

  // ================================================================ 详情全景

  /** 预算与消耗曲线上的一个点（自然日）。 */
  public record AdCampaignBudgetCurvePoint(
      @Schema(description = "自然日（平台当地日）") LocalDate date,
      @Schema(description = "🔴 当日生效的日预算（null = 平台侧没有预算，不是 0）", type = "string")
          BigDecimal dailyBudget,
      @Schema(description = "当日消耗", type = "string") BigDecimal spend,
      @Schema(description = "当日销售额", type = "string") BigDecimal sales,
      @Schema(description = "🔴 ACOS = 消耗 / 销售额（分母为 0 时为 null=不可比）", type = "string")
          BigDecimal acos,
      @Schema(description = "🔴 ROAS = 销售额 / 消耗（分母为 0 时为 null=不可比）", type = "string")
          BigDecimal roas,
      @Schema(description = "🔴 当日预算使用率 = 消耗 / 日预算（无预算或消耗为 0 时为 null=不可比）", type = "string")
          BigDecimal budgetUsageRate) {}

  /**
   * 预算与消耗曲线。
   *
   * <p>🔴 「日预算」为空 = **平台侧没有预算**（如 DSP 固定价），不是 0。
   * 曲线上的 {@code budgetUsageRate} 因此也是空的 —— 「算不出来」与「真的是零」是两件事。
   */
  public record AdCampaignBudgetCurve(
      @Schema(description = "🔴 活动日预算（null = 平台侧没有预算）", type = "string")
          BigDecimal dailyBudget,
      @Schema(description = "币种") String currency,
      @Schema(description = "曲线起点") LocalDate from,
      @Schema(description = "曲线终点") LocalDate to,
      @Schema(description = "区间天数（含首尾）") int windowDays,
      @Schema(description = "🔴 区间预算 = 日预算 × 区间天数（无日预算时为 null）", type = "string")
          BigDecimal windowBudget,
      @Schema(description = "区间消耗合计", type = "string") BigDecimal totalSpend,
      @Schema(description = "区间销售额合计", type = "string") BigDecimal totalSales,
      @Schema(description = "🔴 区间 ACOS = 总消耗 / 总销售额（分母为 0 时为 null）", type = "string")
          BigDecimal acos,
      @Schema(description = "🔴 区间 ROAS = 总销售额 / 总消耗（分母为 0 时为 null）", type = "string")
          BigDecimal roas,
      @Schema(description = "逐日曲线点（按日期升序）") List<AdCampaignBudgetCurvePoint> points,
      @Schema(description = "口径说明") String note) {}

  /** 结构资源的条数与新鲜度。 */
  public record AdCampaignStructureSummary(
      @Schema(description = "广告组条数") long groupCount,
      @Schema(description = "投放商品条数") long productCount,
      @Schema(description = "创意条数") long creativeCount,
      @Schema(description = "关键词条数（读上游 14.1.3.1）") long keywordCount,
      @Schema(description = "🔴 结构数据截至时刻（null = 从未登记过结构）")
          OffsetDateTime structureAsOf,
      @Schema(description = "说明") String structureNote) {}

  /** 一个操作入口。 */
  public record AdCampaignOperationEntry(
      @Schema(description = "操作键") String operation,
      @Schema(description = "展示名") String label,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "实现方（14.1.1.1 / 本模块 / —）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact,
      @Schema(description = "说明") String note) {}

  public record AdCampaignDetailOperations(
      @Schema(description = "逐个操作入口") List<AdCampaignOperationEntry> entries) {}

  /**
   * 平台侧修改历史。
   *
   * <p>🔴 {@code available=false} 是**结构事实**，不是文案：上游没有平台广告 API 同步器，
   * 没有同步源 ⇒ 平台侧改历史**无法获得**。当前展示的是**本地**操作审计
   * （{@code ad_campaign_operation_log}），两者在字段名与文案上必须分得开。
   */
  public record AdCampaignChangeHistory(
      @Schema(description = "🔴 平台侧修改历史是否可得（当前恒为 false：没有同步源）")
          boolean available,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact,
      @Schema(description = "🔴 不可用的原因与替代口径") String note,
      @Schema(description = "🔴 当前展示的是什么：本地操作审计（不是平台侧改历史）")
          String localAuditNote,
      @Schema(description = "本地操作审计（按时刻倒序）") List<AdCampaignOperationLogRow> localAudit) {}

  /**
   * 单活动全景视图。
   *
   * <p>🔴 {@code campaign} 直接复用上游 14.1.1.1 的 {@link AdCampaignRow} ——
   * 活动本体的形状只有一个来源，本模块**不重写**它。
   */
  public record AdCampaignDetailPanorama(
      @Schema(description = "🔴 活动本体与基本设置（复用上游 14.1.1.1 的形状）")
          AdCampaignRow campaign,
      @Schema(description = "🔴 预算与消耗曲线") AdCampaignBudgetCurve budgetCurve,
      @Schema(description = "每日表现明细（按日期倒序）") List<AdCampaignDailyMetricRow> dailyMetrics,
      @Schema(description = "🔴 结构资源条数与新鲜度") AdCampaignStructureSummary structure,
      @Schema(description = "🔴 操作入口（调预算 / 改竞价 / 暂停 / 复制结构）")
          AdCampaignDetailOperations operations,
      @Schema(description = "🔴 平台侧修改历史（显式不可用 + 本地操作审计）")
          AdCampaignChangeHistory changeHistory,
      @Schema(description = "说明") String note) {}

  // ================================================================ 子资源

  public record AdCampaignGroupRow(
      @Schema(description = "广告组 id") UUID id,
      @Schema(description = "活动 id") UUID campaignId,
      @Schema(description = "平台侧广告组 id") String externalGroupId,
      @Schema(description = "广告组名") String groupName,
      @Schema(description = "状态") String status,
      @Schema(description = "🔴 默认竞价（null = 平台侧没给，不是 0）", type = "string")
          BigDecimal defaultBid,
      @Schema(description = "竞价币种") String bidCurrency,
      @Schema(description = "🔴 组级日预算（null = 继承活动预算，不是 0）", type = "string")
          BigDecimal dailyBudget,
      @Schema(description = "定向 / 匹配类型（平台原文）") String targetingType,
      @Schema(description = "组内投放商品条数") long productCount,
      @Schema(description = "组内创意条数") long creativeCount,
      @Schema(description = "🔴 这组结构的数据截至时刻") OffsetDateTime dataAsOf,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  public record AdCampaignProductRow(
      @Schema(description = "投放商品 id") UUID id,
      @Schema(description = "所属广告组 id") UUID groupId,
      @Schema(description = "所属广告组名") String groupName,
      @Schema(description = "listing id（4.1.1.1）") UUID listingId,
      @Schema(description = "平台侧商品 id 原文") String externalItemId,
      @Schema(description = "商品标题快照") String title,
      @Schema(description = "listing 状态（4.1.1.1）") String listingStatus,
      @Schema(description = "listing 审核状态（4.1.1.1）") String listingReviewStatus,
      @Schema(description = "登记时刻") OffsetDateTime createdAt) {}

  public record AdCampaignCreativeRow(
      @Schema(description = "创意 id") UUID id,
      @Schema(description = "活动 id") UUID campaignId,
      @Schema(description = "所属广告组 id（null = 活动级创意）") UUID groupId,
      @Schema(description = "所属广告组名（null = 活动级创意）") String groupName,
      @Schema(description = "平台侧创意 id") String externalCreativeId,
      @Schema(description = "创意名") String creativeName,
      @Schema(description = "🔴 创意类型：IMAGE / VIDEO / CAROUSEL / TEXT") String creativeType,
      @Schema(description = "素材地址") String assetUrl,
      @Schema(description = "视频时长（秒；非视频为 null）") Integer durationSeconds,
      @Schema(description = "状态") String status,
      @Schema(description = "🔴 这条创意的数据截至时刻") OffsetDateTime dataAsOf) {}

  public record AdCampaignKeywordRow(
      @Schema(description = "关键词 id（上游 14.1.3.1）") UUID id,
      @Schema(description = "平台侧关键词 id") String externalKeywordId,
      @Schema(description = "关键词原文") String keyword,
      @Schema(description = "🔴 归一后内部类型") String internalAdType,
      @Schema(description = "🔴 原始平台类型标记") String platformAdType,
      @Schema(description = "当前竞价", type = "string") BigDecimal bid,
      @Schema(description = "竞价币种") String bidCurrency,
      @Schema(description = "状态：ACTIVE / PAUSED / ARCHIVED") String status,
      @Schema(description = "关联的 listing_keyword id（可空 = 还没关联）") UUID listingKeywordId,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  public record AdCampaignGroupPage(
      @Schema(description = "当前页数据") List<AdCampaignGroupRow> items,
      @Schema(description = "总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  public record AdCampaignProductPage(
      @Schema(description = "当前页数据") List<AdCampaignProductRow> items,
      @Schema(description = "总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  public record AdCampaignCreativePage(
      @Schema(description = "当前页数据") List<AdCampaignCreativeRow> items,
      @Schema(description = "总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  public record AdCampaignKeywordPage(
      @Schema(description = "当前页数据") List<AdCampaignKeywordRow> items,
      @Schema(description = "总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  // ================================================================ 改竞价

  public record AdCampaignBidUpdateItem(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "广告组 id") @NotNull
          UUID groupId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "目标竞价") @NotNull
          @PositiveOrZero
          BigDecimal bid,
      @Schema(description = "操作理由（写进审计）") String reason) {}

  public record AdCampaignBidUpdateRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "要改竞价的广告组")
          @NotEmpty
          @Size(max = 100)
          List<@Valid AdCampaignBidUpdateItem> items) {}

  public record AdCampaignBidUpdateItemResult(
      @Schema(description = "广告组 id") UUID groupId,
      @Schema(description = "广告组名") String groupName,
      @Schema(description = "变更前竞价", type = "string") BigDecimal beforeBid,
      @Schema(description = "变更后竞价", type = "string") BigDecimal afterBid,
      @Schema(description = "执行结果：APPLIED / REJECTED / SKIPPED") String result,
      @Schema(description = "逐条结果说明（被拒时给出原因）") String message) {}

  public record AdCampaignBidUpdateResult(
      @Schema(description = "提交的广告组条数") int total,
      @Schema(description = "已生效条数") int applied,
      @Schema(description = "被拒条数") int rejected,
      @Schema(description = "跳过条数（本来就已经是目标竞价）") int skipped,
      @Schema(description = "🔴 本次生效的护栏上限") AdBidGuardrails guardrails,
      @Schema(description = "🔴 逐条执行结果") List<AdCampaignBidUpdateItemResult> items,
      @Schema(description = "说明") String note) {}

  // ================================================================ 复制结构

  public record AdCampaignCopyRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "复制到的目标店铺编码")
          @NotBlank
          String targetShopCode,
      @Schema(description = "复制到的新 ASIN（listing id）；productMode=TARGET_LISTING 时必填")
          UUID targetListingId,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              description = "投放商品处理方式：KEEP_PRODUCTS（沿用源商品，只允许同店铺）/ TARGET_LISTING（改投指定 ASIN）")
          @NotBlank
          String productMode,
      @Schema(description = "新活动名（留空则自动生成）") String newCampaignName,
      @Schema(description = "操作理由（写进审计）") String reason) {}

  public record AdCampaignCopyResult(
      @Schema(description = "源活动 id") UUID sourceCampaignId,
      @Schema(description = "🔴 目标活动 id（结构副本真的落库了）") UUID targetCampaignId,
      @Schema(description = "目标活动名") String targetCampaignName,
      @Schema(description = "目标活动状态（复制出来不投放）") String targetStatus,
      @Schema(description = "目标店铺编码") String targetShopCode,
      @Schema(description = "投放商品处理方式") String productMode,
      @Schema(description = "目标 ASIN 的 listing id（TARGET_LISTING 时有值）") UUID targetListingId,
      @Schema(description = "复制的广告组条数") int groupCount,
      @Schema(description = "复制的投放商品条数") int productCount,
      @Schema(description = "复制的创意条数") int creativeCount,
      @Schema(description = "🔴 复制的关键词条数（恒为 0：平台侧还没建这个活动，关键词 id 无从分配）")
          int keywordCount,
      @Schema(description = "🔴 本地已建、平台侧尚未创建（平台回写队列未交付）")
          boolean pendingPlatformWriteback,
      @Schema(description = "说明") String note) {}

  // ================================================================ 结构登记（真实写入口）

  public record AdCampaignProductImport(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "投放的 listing id") @NotNull
          UUID listingId,
      @Schema(description = "平台侧商品 id 原文（留空则从 listing 读）") String externalItemId,
      @Schema(description = "标题快照（留空则从 listing 读）") String title) {}

  public record AdCampaignCreativeImport(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "平台侧创意 id") @NotBlank
          String externalCreativeId,
      @Schema(description = "创意名") String creativeName,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "创意类型：IMAGE / VIDEO / CAROUSEL / TEXT")
          @NotBlank
          String creativeType,
      @Schema(description = "素材地址") String assetUrl,
      @Schema(description = "视频时长（秒）") Integer durationSeconds,
      @Schema(description = "状态") String status) {}

  public record AdCampaignGroupImport(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "平台侧广告组 id") @NotBlank
          String externalGroupId,
      @Schema(description = "广告组名") String groupName,
      @Schema(description = "状态") String status,
      @Schema(description = "默认竞价") BigDecimal defaultBid,
      @Schema(description = "竞价币种") String bidCurrency,
      @Schema(description = "组级日预算（null = 继承活动预算）") BigDecimal dailyBudget,
      @Schema(description = "定向 / 匹配类型") String targetingType,
      @Schema(description = "组内投放商品") List<@Valid AdCampaignProductImport> products,
      @Schema(description = "组内创意") List<@Valid AdCampaignCreativeImport> creatives) {}

  /**
   * 登记一个活动的结构（广告组 / 投放商品 / 创意）。
   *
   * <p>🔴 平台广告 API 同步器**尚未交付**，但结构必须有真的落点 ——
   * 这个端点就是那个落点，**绝不用空数组或硬编码常量假装有广告组**。
   *
   * <p>🔴 语义是**替换**：一次登记代表「这是平台返回的该活动当前完整结构」，
   * 因此载荷里没出现的组内商品 / 创意会被删掉，组本身没出现的也会被删掉。
   * 增量追加会让「平台已经下架了这个商品」永远删不掉。
   */
  public record AdCampaignStructureImportRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "结构数据截至时刻") @NotNull
          OffsetDateTime dataAsOf,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "广告组列表") @NotEmpty
          List<@Valid AdCampaignGroupImport> groups,
      @Schema(description = "活动级创意（不挂在任何广告组下的）")
          List<@Valid AdCampaignCreativeImport> creatives) {}

  public record AdCampaignStructureImportResult(
      @Schema(description = "登记的广告组条数") int groupCount,
      @Schema(description = "登记的投放商品条数") int productCount,
      @Schema(description = "登记的创意条数") int creativeCount,
      @Schema(description = "被替换语义删掉的广告组条数") int removedGroupCount,
      @Schema(description = "🔴 结构数据截至时刻") OffsetDateTime dataAsOf,
      @Schema(description = "说明") String note) {}
}
