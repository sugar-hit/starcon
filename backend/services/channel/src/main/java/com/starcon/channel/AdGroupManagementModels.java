package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 14.1.2.1 广告组管理 的传输模型。
 *
 * <p>蓝图：「管理广告组：关联投放商品、匹配类型、定向设置、默认竞价，查看组级表现。
 * 输入：广告组设置；关联商品选择（从 4 listing 中选）。
 * 输出：广告组记录 + 组级报表入口。
 * 关系：上游 = 14.1.1.2；下游 = 14.1.3.1 关键词、14.2.1.1 竞价。
 * <b>广告组与 SKU 的映射是利润归因的基础</b>——投放的是 listing，归因要落到内部 SKU。」
 *
 * <p>🔴 这个模型层钉死本模块最容易做丢的五件事：
 *
 * <ol>
 *   <li><b>「组级表现」没有事实来源</b>。现有事实表里没有组级指标表
 *       （{@code ad_campaign_daily_metric} 是 campaign 维度，没有 group_id）。
 *       {@link AdGroupPerformanceAvailability} 把 {@code available=false} + {@code ownerModule}
 *       + {@code impact} 做成**结构里的字段** —— 拿不到就写「拿不到」，
 *       <b>不把活动级指标冒充组级指标</b>，也**不建一张没人写的空表**。
 *   <li><b>「组级报表入口」是入口，不是报表</b>。{@link AdGroupReportEntry} 同时给出
 *       {@code ownerModule}（14.3.1.2）与路由标识 —— 本模块**不 import 它的类、
 *       不建它的表、不猜它的 URL 形状**。
 *   <li><b>定向与匹配类型只存平台原文</b>（14.1.3.2 未交付 ⇒ 不解读）。
 *       {@code matchType} / {@code targetingType} / {@code targetingSettings} 三者
 *       在字段名与文档上分得开，界面才知道哪个是「匹配类型」、哪个是「定向设置」。
 *   <li><b>「本地已改」与「平台侧已改」分得开</b>。{@code pendingPlatformWriteback}
 *       是**结构事实**：平台回写队列未交付 ⇒ 本地写只落本地。
 *   <li><b>默认竞价不在本模块的写路径里</b>。改竞价是**钱**，
 *       14.1.1.2 已有带护栏与审计的 {@code POST /bids} —— 本模块**复用**它，
 *       <b>不另起同义端点</b>（两个端点改同一笔钱，审计就会分叉）。
 * </ol>
 *
 * <p>🔴 金额与数量一律 {@link BigDecimal}，**没有 double**：组级预算与默认竞价
 * 直接进 14.2.1.1 竞价管理与 14.3.1.1 报表。
 */
public final class AdGroupManagementModels {
  private AdGroupManagementModels() {}

  // ================================================================ 能力声明

  public record AdGroupCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  /**
   * 组级写操作的护栏上限。
   *
   * <p>🔴 组级预算是**钱**：一次手滑把组预算写成 100000 就是把活动预算烧光。
   * 上限**一起返回给界面**，让运营在点下去之前就知道天花板在哪。
   */
  public record AdGroupGuardrails(
      @Schema(description = "单个广告组的组级日预算上限（绝对值）", type = "string")
          BigDecimal maxGroupDailyBudget,
      @Schema(description = "单个广告组的默认竞价上限（绝对值）", type = "string")
          BigDecimal maxDefaultBid,
      @Schema(description = "一次设置投放商品最多允许关联的 listing 条数") int maxProductsPerGroup,
      @Schema(description = "护栏说明") String note) {}

  /**
   * 组级报表入口。
   *
   * <p>🔴 {@code available=true} 指的是**入口已交付**，不是「报表数据在这里」。
   * 报表本体归 14.3.1.2（同批并行车道，下一批并入集成头）；
   * 本模块**不 import 它的类、不建它的表、不猜它的 URL 形状** ——
   * 只给出路由标识（蓝图的 L2 前端入口 {@code /ads/reports}）与责任模块号。
   */
  public record AdGroupReportEntry(
      @Schema(description = "🔴 报表入口是否已交付（本模块提供入口与路由标识）") boolean available,
      @Schema(description = "报表本体的责任模块") String ownerModule,
      @Schema(description = "🔴 报表本体的前端路由标识（蓝图的 14.3 广告分析入口）")
          String reportRoute,
      @Schema(description = "报表模块的稳定标识（模块号）") String routeIdentifier,
      @Schema(description = "不交付的影响") String impact,
      @Schema(description = "说明") String note) {}

  /**
   * 组级表现指标的可得性。
   *
   * <p>🔴 {@code available=false} 是**结构事实**：现有事实表里**没有组级指标表**
   * （{@code ad_campaign_daily_metric} 是 campaign 维度，没有 group_id）
   * ⇒ 组级表现**没有可算的真实指标**。
   * 本模块**不把活动级指标冒充组级指标**，也**不建一张没人写的组级指标表** ——
   * 空表会被界面渲染成「没有数据」，那比明说「拿不到」严重得多。
   */
  public record AdGroupPerformanceAvailability(
      @Schema(description = "🔴 组级指标是否可得（当前恒为 false：没有组级指标事实表）")
          boolean available,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact,
      @Schema(description = "🔴 不可用的原因与替代口径") String note) {}

  public record AdGroupManagementCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false")
          List<AdGroupCapabilityDimension> dimensions,
      @Schema(description = "广告组状态取值") List<String> statuses,
      @Schema(description = "🔴 组级写操作的护栏上限") AdGroupGuardrails guardrails,
      @Schema(description = "🔴 组级报表入口（指向 14.3.1.2）") AdGroupReportEntry reportEntry,
      @Schema(description = "🔴 组级表现指标的可得性（当前不可得）")
          AdGroupPerformanceAvailability performance,
      @Schema(description = "🔴 定向与匹配类型只存原文的口径说明") String targetingNote,
      @Schema(description = "🔴 平台回写未交付的影响说明") String platformWritebackNote,
      @Schema(description = "上游依赖说明") String upstreamNote,
      @Schema(description = "下游与边界说明") String boundaryNote) {}

  // ================================================================ 选项

  public record AdGroupOption(
      @Schema(description = "取值") String value,
      @Schema(description = "展示名") String label) {}

  public record AdGroupOptions(
      @Schema(description = "广告组状态下拉项") List<AdGroupOption> statuses,
      @Schema(description = "排序方式下拉项") List<AdGroupOption> sorts,
      @Schema(description = "🔴 组级写操作的护栏上限") AdGroupGuardrails guardrails,
      @Schema(description = "🔴 组级报表入口（指向 14.3.1.2）") AdGroupReportEntry reportEntry,
      @Schema(description = "🔴 定向与匹配类型只存原文的口径说明") String targetingNote,
      @Schema(description = "🔴 平台回写未交付的影响说明") String platformWritebackNote) {}

  // ================================================================ 广告组记录

  /**
   * 一个广告组。
   *
   * <p>🔴 {@code defaultBid} 为空 = **平台侧没有给默认竞价**，不是 0；
   * {@code dailyBudget} 为空 = **继承活动预算**，不是 0。两者含义相反，不能混。
   */
  public record AdGroupRow(
      @Schema(description = "广告组 id") UUID id,
      @Schema(description = "所属活动 id") UUID campaignId,
      @Schema(description = "活动名") String campaignName,
      @Schema(description = "活动所在店铺编码") String shopCode,
      @Schema(description = "活动币种") String currency,
      @Schema(description = "平台侧广告组 id（本地新建的组是 LOCAL- 前缀的本地标记）")
          String externalGroupId,
      @Schema(description = "广告组名") String groupName,
      @Schema(description = "状态") String status,
      @Schema(description = "🔴 默认竞价（null = 平台侧没给，不是 0）", type = "string")
          BigDecimal defaultBid,
      @Schema(description = "竞价币种") String bidCurrency,
      @Schema(description = "🔴 组级日预算（null = 继承活动预算，不是 0）", type = "string")
          BigDecimal dailyBudget,
      @Schema(description = "🔴 定向类型（平台原文，不解读）") String targetingType,
      @Schema(description = "🔴 匹配类型（平台原文，不解读）") String matchType,
      @Schema(description = "🔴 定向设置原文（平台原文串，不拆解不归一）") String targetingSettings,
      @Schema(description = "组内投放商品条数") long productCount,
      @Schema(description = "🔴 本地已改、尚未回写平台") boolean pendingPlatformWriteback,
      @Schema(description = "🔴 本地最后一次改动时刻（null = 本地没动过这个组）")
          OffsetDateTime localChangedAt,
      @Schema(description = "🔴 这组结构的数据截至时刻") OffsetDateTime dataAsOf,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  public record AdGroupPage(
      @Schema(description = "当前页数据") List<AdGroupRow> items,
      @Schema(description = "总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  /** 组级列表汇总（**不做跨币种金额相加**）。 */
  public record AdGroupSummary(
      @Schema(description = "广告组总条数") long total,
      @Schema(description = "覆盖的活动数") long campaignCount,
      @Schema(description = "🔴 标着「本地已改、尚未回写平台」的组数") long pendingWritebackCount,
      @Schema(description = "🔴 各状态条数（状态 → 条数）") java.util.Map<String, Long> statusCounts,
      @Schema(description = "🔴 汇总口径说明") String note) {}

  // ================================================================ 投放商品与 SKU

  /**
   * 组内投放商品。
   *
   * <p>🔴 {@code internalSkuIds} 是**只读带出**的映射现状
   * （{@code listing_variant.internal_sku_id}，跨服务逻辑 ID），
   * 它**不是归因结果** —— 花费归因到 SKU 归 14.3.1.3 商品维度报表，未交付。
   */
  public record AdGroupProductRow(
      @Schema(description = "投放商品 id") UUID id,
      @Schema(description = "所属广告组 id") UUID groupId,
      @Schema(description = "listing id（4.1.1.1）") UUID listingId,
      @Schema(description = "平台侧商品 id 原文") String externalItemId,
      @Schema(description = "商品标题快照") String title,
      @Schema(description = "listing 状态（4.1.1.1）") String listingStatus,
      @Schema(description = "listing 审核状态（4.1.1.1）") String listingReviewStatus,
      @Schema(description = "🔴 该 listing 已映射的内部 SKU 逻辑 id（只读；可能为空）")
          List<UUID> internalSkuIds,
      @Schema(description = "🔴 未映射到内部 SKU 的变体条数") long unmappedVariantCount,
      @Schema(description = "🔴 归因口径说明") String attributionNote) {}

  /**
   * 可选投放商品候选（该活动所在店铺的 listing）。
   *
   * <p>蓝图的输入写着「关联商品选择（从 4 listing 中选）」⇒ 候选必须是**真的 listing**，
   * 且必须**属于活动所在店铺**（投放的商品不能跨店铺挂）。
   */
  public record AdGroupProductCandidateRow(
      @Schema(description = "listing id") UUID listingId,
      @Schema(description = "平台侧商品 id 原文") String externalItemId,
      @Schema(description = "商品标题") String title,
      @Schema(description = "listing 状态") String listingStatus,
      @Schema(description = "listing 审核状态") String listingReviewStatus,
      @Schema(description = "🔴 是否已挂在这个广告组上") boolean selected,
      @Schema(description = "🔴 已映射的内部 SKU 逻辑 id（只读）") List<UUID> internalSkuIds) {}

  public record AdGroupProductCandidatePage(
      @Schema(description = "当前页数据") List<AdGroupProductCandidateRow> items,
      @Schema(description = "总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  // ================================================================ 组详情

  /** 组详情里的一个下游入口。 */
  public record AdGroupEntry(
      @Schema(description = "入口键") String entry,
      @Schema(description = "展示名") String label,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "实现方") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact,
      @Schema(description = "说明") String note) {}

  /** 广告组详情。 */
  public record AdGroupDetail(
      @Schema(description = "🔴 广告组记录（组设置）") AdGroupRow group,
      @Schema(description = "🔴 组内投放商品（含只读带出的内部 SKU 映射现状）")
          List<AdGroupProductRow> products,
      @Schema(description = "组内创意条数（读上游 14.1.1.2 的结构表）") long creativeCount,
      @Schema(description = "🔴 组级报表入口（指向 14.3.1.2）") AdGroupReportEntry reportEntry,
      @Schema(description = "🔴 组级表现指标的可得性（当前不可得）")
          AdGroupPerformanceAvailability performance,
      @Schema(description = "🔴 下游入口（关键词 / 竞价 / 报表）") List<AdGroupEntry> entries,
      @Schema(description = "🔴 最近的组级操作审计（按时刻倒序）")
          List<AdGroupOperationRow> recentOperations,
      @Schema(description = "说明") String note) {}

  // ================================================================ 操作审计

  public record AdGroupOperationRow(
      @Schema(description = "审计 id") UUID id,
      @Schema(description = "所属活动 id") UUID campaignId,
      @Schema(description = "目标广告组 id") UUID groupId,
      @Schema(description = "写动作：CREATE_GROUP / UPDATE_SETTINGS / SET_PRODUCTS")
          String operation,
      @Schema(description = "变更前设置快照（紧凑 JSON 原文）") String beforeValue,
      @Schema(description = "变更后设置快照（紧凑 JSON 原文）") String afterValue,
      @Schema(description = "执行结果：APPLIED / REJECTED / SKIPPED") String result,
      @Schema(description = "逐条结果说明") String message,
      @Schema(description = "操作人（已认证成员）") UUID requestedBy,
      @Schema(description = "操作理由") String reason,
      @Schema(description = "留痕时刻") OffsetDateTime createdAt) {}

  public record AdGroupOperationPage(
      @Schema(description = "当前页数据") List<AdGroupOperationRow> items,
      @Schema(description = "总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  // ================================================================ 写请求

  /**
   * 新建广告组。
   *
   * <p>🔴 新建的组**一律落 PAUSED**：平台侧还没有这个组，谈不上「投放中」。
   * 平台侧 id 落成 {@code LOCAL-{uuid}} —— 那是**本地生成的标记，不是平台 id**；
   * 编一个像平台 id 的字符串会让「同步回读」把这条本地草稿认成平台广告组。
   */
  public record AdGroupCreateRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "所属活动 id") @NotNull
          UUID campaignId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "广告组名") @NotBlank
          @Size(max = 200)
          String groupName,
      @Schema(description = "🔴 初始默认竞价（留空 = 平台侧没给；金额，只受绝对上限保护）")
          @PositiveOrZero
          BigDecimal defaultBid,
      @Schema(description = "竞价币种（留空 = 取活动币种；与活动币种不一致会被拒）")
          String bidCurrency,
      @Schema(description = "🔴 组级日预算（留空 = 继承活动预算，不是 0）") @PositiveOrZero
          BigDecimal dailyBudget,
      @Schema(description = "🔴 定向类型（平台原文，不解读）") @Size(max = 64) String targetingType,
      @Schema(description = "🔴 匹配类型（平台原文，不解读）") @Size(max = 64) String matchType,
      @Schema(description = "🔴 定向设置原文（平台原文串，不拆解不归一）") String targetingSettings,
      @Schema(description = "操作理由（写进审计）") String reason) {}

  /**
   * 改组设置。
   *
   * <p>🔴 语义是**部分更新**：没给的字段**不改**；
   * 想把组级预算恢复成「继承活动预算」要显式给 {@code clearDailyBudget=true}
   * （{@code null} 与「不改」在 JSON 里分不开，所以给了一个显式的清空开关）。
   *
   * <p>🔴 {@code defaultBid} 是一个**只会把人拒掉**的字段：本端点**不接受**默认竞价 ——
   * 改竞价是钱，走上游 14.1.1.2 的改竞价入口（带单次上限与绝对上限、逐条留审计）。
   * 给了它会被明确拒绝（400），**不是静默忽略** —— 静默忽略会让调用方以为改成功了。
   */
  public record AdGroupSettingsUpdateRequest(
      @Schema(description = "广告组名（不给 = 不改；给空串 = 清空）") @Size(max = 200) String groupName,
      @Schema(description = "状态（不给 = 不改）") String status,
      @Schema(description = "🔴 组级日预算（不给 = 不改；给数值 = 设置）") @PositiveOrZero
          BigDecimal dailyBudget,
      @Schema(description = "🔴 显式把组级预算清成「继承活动预算」（与 dailyBudget 同时给会被拒）")
          Boolean clearDailyBudget,
      @Schema(description = "🔴 定向类型（不给 = 不改；给空串 = 清空）") @Size(max = 64)
          String targetingType,
      @Schema(description = "🔴 匹配类型（不给 = 不改；给空串 = 清空）") @Size(max = 64) String matchType,
      @Schema(description = "🔴 定向设置原文（不给 = 不改；给空串 = 清空）") String targetingSettings,
      @Schema(
              description =
                  "🔴 **不接受**：默认竞价不在本端点里，给了会被明确拒绝（400）；"
                      + "改竞价走 14.1.1.2 的 POST /channel/ad-campaign-details/campaigns/{id}/bids")
          BigDecimal defaultBid,
      @Schema(description = "操作理由（写进审计）") String reason) {}

  /** 设投放商品（**替换**语义）。 */
  public record AdGroupProductUpdateRequest(
      @Schema(description = "要挂上的 listing id 集合（替换语义：没列出的会被解除关联）")
          @NotNull
          List<UUID> listingIds,
      @Schema(description = "操作理由（写进审计）") String reason) {}

  // ================================================================ 写结果

  public record AdGroupCreateResult(
      @Schema(description = "🔴 新建的广告组 id（真的落库了）") UUID groupId,
      @Schema(description = "所属活动 id") UUID campaignId,
      @Schema(description = "广告组名") String groupName,
      @Schema(description = "🔴 状态（新建一律 PAUSED：平台侧还没有这个组）") String status,
      @Schema(description = "🔴 平台侧广告组 id（本地生成的 LOCAL- 标记，不是平台 id）")
          String externalGroupId,
      @Schema(description = "🔴 默认竞价（null = 没设）", type = "string") BigDecimal defaultBid,
      @Schema(description = "竞价币种") String bidCurrency,
      @Schema(description = "🔴 组级日预算（null = 继承活动预算）", type = "string")
          BigDecimal dailyBudget,
      @Schema(description = "🔴 定向类型（平台原文）") String targetingType,
      @Schema(description = "🔴 匹配类型（平台原文）") String matchType,
      @Schema(description = "🔴 定向设置原文") String targetingSettings,
      @Schema(description = "🔴 本地已建、平台侧尚未创建（平台回写队列未交付）")
          boolean pendingPlatformWriteback,
      @Schema(description = "说明") String note) {}

  public record AdGroupSettingsUpdateResult(
      @Schema(description = "广告组 id") UUID groupId,
      @Schema(description = "变更后的广告组记录") AdGroupRow group,
      @Schema(description = "🔴 真正变更了的字段名（没给或值相同的字段不算变更）")
          List<String> changedFields,
      @Schema(description = "🔴 本地已改、尚未回写平台") boolean pendingPlatformWriteback,
      @Schema(description = "说明") String note) {}

  public record AdGroupProductUpdateResult(
      @Schema(description = "广告组 id") UUID groupId,
      @Schema(description = "🔴 新增关联的 listing id") List<UUID> addedListingIds,
      @Schema(description = "🔴 解除关联的 listing id") List<UUID> removedListingIds,
      @Schema(description = "🔴 关联后的投放商品条数") long productCount,
      @Schema(description = "🔴 本地已改、尚未回写平台") boolean pendingPlatformWriteback,
      @Schema(description = "说明") String note) {}
}
