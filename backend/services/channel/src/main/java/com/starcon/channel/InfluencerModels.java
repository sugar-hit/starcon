package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 14.5.1.1 达人库 的传输模型。
 *
 * <p>蓝图：「管理达人/联盟资源：达人档案（类目、粉丝层级、带货 GMV、互动率、合作历史）、
 * 标签分组、评级与黑名单。输入：达人数据同步（平台接口或手动录入）；标签与评级。
 * 输出：达人库 + 筛选检索。关系：上游 = 平台数据；下游 = 14.5.1.2 建联、15 客户与客服。
 * <b>达人效果数据（GMV/退款率）必须入库</b>，否则无法筛选优质达人。」
 *
 * <p>🔴 这个模型层钉死本模块最容易做丢的五件事：
 *
 * <ol>
 *   <li><b>「没录」不是 0</b>。{@code gmv} / {@code engagementRate} / {@code refundRate}
 *       为 {@code null} 表示**不可比**（还没录），与「可比但真的是零」含义相反。
 *       界面把 null 渲染成 0.00 就等于宣称「这个达人一单没带过货」。
 *   <li><b>粉丝层级不是手填的</b>。{@code followerTier} 由 DB 触发器从
 *       {@code followerCount} 推导（服务层写什么都不作数），所以它永远与粉丝数一致。
 *   <li><b>「平台侧还没有」要说出来</b>。{@code syncSource} + {@code platformSyncStatus}
 *       表达「本地手动录入、平台侧还没有这个达人」——平台达人适配器未交付，
 *       这两列不是装饰。
 *   <li><b>档案 GMV 与合作合计不是一个数</b>。{@code gmv}（档案口径）与
 *       {@code gmvFromCooperations}（本地按合作历史行求和）分列两个字段，
 *       归因口径归 14.5.2.1。
 *   <li><b>黑名单是独立事实</b>。{@code blacklisted} / {@code blacklistReason} /
 *       {@code blacklistEffectiveAt} 让「拉黑后从正常检索里排除」这件事在结果里可见，
 *       而不是让人以为达人凭空消失了。
 * </ol>
 *
 * <p>🔴 金额与数量一律 {@link BigDecimal}，**没有 double**：GMV / 佣金 / 互动率 / 退款率
 * / 评分都是钱或比率，它们直接决定「这个达人值不值得投」。
 */
public final class InfluencerModels {
  private InfluencerModels() {}

  // ================================================================ 能力声明

  public record InfluencerCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  /**
   * 能力声明：逐维度说清「哪些能算、哪些拿不到」。
   *
   * <p>🔴 不可用维度是<b>平台达人数据同步</b>（适配层未交付）与<b>带货归因</b>
   * （14.5.2.1 未交付）—— 前者让「同步」只能是手动录入，后者让 GMV / 佣金只有
   * 本地口径、没有归因口径。两个都必须显式说清，不能用一个假数字顶替。
   */
  public record InfluencerCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false")
          List<InfluencerCapabilityDimension> dimensions,
      @Schema(description = "粉丝层级取值") List<String> followerTiers,
      @Schema(description = "拉黑原因取值") List<String> blacklistReasonCodes,
      @Schema(description = "合作状态取值") List<String> cooperationStatuses,
      @Schema(description = "数据来源取值") List<String> syncSources,
      @Schema(description = "🔴 效果数据口径说明") String effectNote,
      @Schema(description = "🔴 评分口径说明") String ratingNote,
      @Schema(description = "🔴 平台同步能力说明") String syncNote,
      @Schema(description = "上游与边界说明") String upstreamNote,
      @Schema(description = "下游与边界说明") String boundaryNote) {}

  // ================================================================ 下拉项

  public record InfluencerOption(
      @Schema(description = "取值") String code, @Schema(description = "中文说明") String label) {}

  /** 平台下拉项（**带 id** —— 登记达人要的是平台 id，不只是编码）。 */
  public record InfluencerPlatformOption(
      @Schema(description = "平台 id") UUID id,
      @Schema(description = "平台编码") String code,
      @Schema(description = "平台名称") String name) {}

  public record InfluencerOptions(
      @Schema(description = "粉丝层级选项") List<InfluencerOption> followerTiers,
      @Schema(description = "拉黑原因选项") List<InfluencerOption> blacklistReasons,
      @Schema(description = "合作状态选项") List<InfluencerOption> cooperationStatuses,
      @Schema(description = "平台下拉项（带 id）") List<InfluencerPlatformOption> platforms,
      @Schema(description = "平台编码（租户内可用的）") List<String> platformCodes,
      @Schema(description = "店铺编码（租户内可用的）") List<String> shopCodes,
      @Schema(description = "币种（达人库里出现过的）") List<String> currencies,
      @Schema(description = "类目（达人库里出现过的）") List<String> categories,
      @Schema(description = "🔴 效果数据口径说明") String effectNote,
      @Schema(description = "🔴 评分口径说明") String ratingNote) {}

  // ================================================================ 达人档案

  /**
   * 达人档案的一行。
   *
   * <p>🔴 {@code gmv} 与 {@code gmvFromCooperations} 是<b>两个口径</b>：
   * 前者是档案入库值，后者是本地按合作历史行求和。归因口径归 14.5.2.1。
   */
  public record InfluencerRow(
      @Schema(description = "达人 id") UUID id,
      @Schema(description = "平台 id") UUID platformId,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "平台侧达人 id（空串 = 还没登记）") String externalInfluencerId,
      @Schema(description = "达人名称") String displayName,
      @Schema(description = "账号名") String handle,
      @Schema(description = "类目") String category,
      @Schema(description = "粉丝数") long followerCount,
      @Schema(description = "🔴 粉丝层级（由触发器从粉丝数推导）") String followerTier,
      @Schema(description = "互动率（null = 还没录，不可比）", type = "string")
          BigDecimal engagementRate,
      @Schema(description = "🔴 带货 GMV（null = 还没录，不可比）", type = "string") BigDecimal gmv,
      @Schema(description = "GMV 币种") String gmvCurrency,
      @Schema(description = "🔴 退款率（null = 还没录，不可比）", type = "string") BigDecimal refundRate,
      @Schema(description = "🔴 评分（null = 评分口径未配置）", type = "string") BigDecimal ratingScore,
      @Schema(description = "评级档位 id") UUID ratingTierId,
      @Schema(description = "评级档位编码") String ratingTierCode,
      @Schema(description = "评级档位名称") String ratingTierName,
      @Schema(description = "评级档位排序位") Integer ratingSortOrder,
      @Schema(description = "合作历史条数") int cooperationCount,
      @Schema(description = "🔴 合作历史 GMV 合计（合作口径，非归因口径）", type = "string")
          BigDecimal gmvFromCooperations,
      @Schema(description = "数据来源：MANUAL 手动录入 / PLATFORM_API 平台接口") String syncSource,
      @Schema(description = "平台同步状态：LOCAL_ONLY / PENDING_SYNC / SYNCED / FAILED")
          String platformSyncStatus,
      @Schema(description = "本地状态：ACTIVE / INACTIVE") String status,
      @Schema(description = "🔴 是否在黑名单里（生效中）") boolean blacklisted,
      @Schema(description = "黑名单记录 id") UUID blacklistId,
      @Schema(description = "黑名单原因码") String blacklistReasonCode,
      @Schema(description = "黑名单生效时刻") OffsetDateTime blacklistEffectiveAt,
      @Schema(description = "标签编码（逗号分隔，按标签编码升序）") String tagCodes,
      @Schema(description = "备注") String note,
      @Schema(description = "乐观锁版本") long version,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  public record InfluencerPage(
      @Schema(description = "当前页数据") List<InfluencerRow> items,
      @Schema(description = "符合筛选条件的总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  // ================================================================ 标签与分组

  /** 标签分组（租户级配置）。 */
  public record InfluencerTagGroupRow(
      @Schema(description = "分组 id") UUID id,
      @Schema(description = "分组编码（租户内唯一）") String groupCode,
      @Schema(description = "分组名称") String groupName,
      @Schema(description = "分组说明") String description,
      @Schema(description = "状态：ACTIVE / PAUSED") String status,
      @Schema(description = "该分组下的标签数") int tagCount,
      @Schema(description = "备注") String note,
      @Schema(description = "乐观锁版本") long version,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  public record InfluencerTagGroupPage(
      @Schema(description = "当前页数据") List<InfluencerTagGroupRow> items,
      @Schema(description = "符合筛选条件的总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  /** 标签（租户级配置，挂在分组下）。 */
  public record InfluencerTagRow(
      @Schema(description = "标签 id") UUID id,
      @Schema(description = "分组 id") UUID groupId,
      @Schema(description = "分组编码") String groupCode,
      @Schema(description = "分组名称") String groupName,
      @Schema(description = "标签编码（租户内唯一）") String tagCode,
      @Schema(description = "标签名称") String tagName,
      @Schema(description = "标签说明") String description,
      @Schema(description = "状态：ACTIVE / PAUSED") String status,
      @Schema(description = "打了这个标签的达人数") int influencerCount,
      @Schema(description = "备注") String note,
      @Schema(description = "乐观锁版本") long version,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  public record InfluencerTagPage(
      @Schema(description = "当前页数据") List<InfluencerTagRow> items,
      @Schema(description = "符合筛选条件的总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  // ================================================================ 评级

  /** 评级档位。 */
  public record InfluencerRatingTierRow(
      @Schema(description = "档位 id") UUID id,
      @Schema(description = "档位编码（租户内唯一）") String tierCode,
      @Schema(description = "档位名称") String tierName,
      @Schema(description = "分数下限（含）", type = "string") BigDecimal minScore,
      @Schema(description = "分数上限（含）", type = "string") BigDecimal maxScore,
      @Schema(description = "排序位（越大越靠前）") int sortOrder,
      @Schema(description = "状态：ACTIVE / PAUSED") String status,
      @Schema(description = "备注") String note,
      @Schema(description = "乐观锁版本") long version,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  public record InfluencerRatingTierPage(
      @Schema(description = "当前页数据") List<InfluencerRatingTierRow> items,
      @Schema(description = "符合筛选条件的总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  /** 评分口径（租户级配置，一个租户一套生效）。 */
  public record InfluencerRatingRuleRow(
      @Schema(description = "口径 id") UUID id,
      @Schema(description = "口径编码") String ruleCode,
      @Schema(description = "GMV 权重", type = "string") BigDecimal weightGmv,
      @Schema(description = "互动率权重", type = "string") BigDecimal weightEngagement,
      @Schema(description = "退款率权重", type = "string") BigDecimal weightRefund,
      @Schema(description = "GMV 归一化上限", type = "string") BigDecimal gmvCap,
      @Schema(description = "互动率归一化上限", type = "string") BigDecimal engagementCap,
      @Schema(description = "退款率归一化上限", type = "string") BigDecimal refundCap,
      @Schema(description = "状态：ACTIVE / PAUSED") String status,
      @Schema(description = "备注") String note,
      @Schema(description = "乐观锁版本") long version,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  // ================================================================ 黑名单

  /** 黑名单的一行（生效中的与已解除的都在这里查得到）。 */
  public record InfluencerBlacklistRow(
      @Schema(description = "黑名单记录 id") UUID id,
      @Schema(description = "达人 id") UUID influencerId,
      @Schema(description = "达人名称") String influencerName,
      @Schema(description = "类目") String category,
      @Schema(description = "粉丝层级") String followerTier,
      @Schema(description = "拉黑原因码") String reasonCode,
      @Schema(description = "拉黑理由") String reason,
      @Schema(description = "生效时刻") OffsetDateTime effectiveAt,
      @Schema(description = "解除时刻（未解除为空）") OffsetDateTime releasedAt,
      @Schema(description = "解除理由") String releasedReason,
      @Schema(description = "状态：ACTIVE 生效中 / RELEASED 已解除") String status,
      @Schema(description = "备注") String note,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  public record InfluencerBlacklistPage(
      @Schema(description = "当前页数据") List<InfluencerBlacklistRow> items,
      @Schema(description = "符合筛选条件的总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  // ================================================================ 合作历史

  /** 合作历史的一行（达人 × 活动）。 */
  public record InfluencerCooperationRow(
      @Schema(description = "合作记录 id") UUID id,
      @Schema(description = "达人 id") UUID influencerId,
      @Schema(description = "达人名称") String influencerName,
      @Schema(description = "活动 id") UUID campaignId,
      @Schema(description = "活动名称") String campaignName,
      @Schema(description = "平台侧活动 id") String externalCampaignId,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "合作状态：PLANNED / ACTIVE / COMPLETED / CANCELLED") String status,
      @Schema(description = "开始时刻") OffsetDateTime startedAt,
      @Schema(description = "结束时刻") OffsetDateTime endedAt,
      @Schema(description = "合作 GMV（null = 还没录）", type = "string") BigDecimal gmv,
      @Schema(description = "合作订单量（null = 还没录）", type = "string") BigDecimal orderCount,
      @Schema(description = "佣金（null = 还没录）", type = "string") BigDecimal commission,
      @Schema(description = "退款率（null = 还没录）", type = "string") BigDecimal refundRate,
      @Schema(description = "币种") String currency,
      @Schema(description = "备注") String note,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  public record InfluencerCooperationPage(
      @Schema(description = "当前页数据") List<InfluencerCooperationRow> items,
      @Schema(description = "符合筛选条件的总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  // ================================================================ 详情

  /**
   * 达人详情。
   *
   * <p>🔴 {@code syncNote} / {@code ratingNote} 必须在场：平台同步未接入、评分口径未配置
   * 这两件事要让界面看得见，而不是让界面显示一个假的 0。
   */
  public record InfluencerDetail(
      @Schema(description = "达人档案") InfluencerRow influencer,
      @Schema(description = "标签（按标签编码升序）") List<InfluencerTagRow> tags,
      @Schema(description = "合作历史（按开始时刻倒序）") List<InfluencerCooperationRow> cooperations,
      @Schema(description = "黑名单记录（生效中的或最近一条已解除的；没有则为空）")
          InfluencerBlacklistRow blacklist,
      @Schema(description = "🔴 效果数据口径说明") String effectNote,
      @Schema(description = "🔴 评分口径说明") String ratingNote,
      @Schema(description = "🔴 平台同步说明") String syncNote,
      @Schema(description = "说明") String note) {}

  // ================================================================ 同步能力

  /** 一个平台的达人数据同步是否已接入。 */
  public record InfluencerPlatformSyncAvailability(
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "平台名称") String platformName,
      @Schema(description = "是否已接入达人数据适配器") boolean available,
      @Schema(description = "同步状态：NOT_INTEGRATED 未接入 / AVAILABLE 已接入") String status,
      @Schema(description = "未接入的原因（人能读，不是异常类名）") String reason,
      @Schema(description = "该平台在库里已登记的达人数") int registeredCount) {}

  /** 同步能力总览（按平台逐个说清接入与否）。 */
  public record InfluencerSyncStatus(
      @Schema(description = "逐平台接入情况") List<InfluencerPlatformSyncAvailability> platforms,
      @Schema(description = "🔴 同步口径说明") String syncNote) {}

  /** 触发一次同步的结论。 */
  public record InfluencerSyncResult(
      @Schema(description = "是否真的同步了") boolean synced,
      @Schema(description = "结论说明") String message,
      @Schema(description = "写入的达人数") int syncedCount,
      @Schema(description = "失败条数") int failedCount) {}

  // ================================================================ 请求

  /** 手动录入 / 编辑一个达人档案（蓝图的「手动录入」）。 */
  public record SaveInfluencerRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "平台 id") @NotNull
          UUID platformId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "店铺编码") @NotBlank
          String shopCode,
      @Schema(description = "平台侧达人 id（留空 = 还没登记）") @Size(max = 128)
          String externalInfluencerId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "达人名称") @NotBlank
          @Size(max = 200)
          String displayName,
      @Schema(description = "账号名") @Size(max = 128) String handle,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "类目") @NotBlank
          @Size(max = 64)
          String category,
      @Schema(description = "粉丝数（粉丝层级由它推导）") Long followerCount,
      @Schema(description = "互动率（0 ~ 1；留空 = 还没录）", type = "string")
          @DecimalMin("0")
          @DecimalMax("1")
          BigDecimal engagementRate,
      @Schema(description = "带货 GMV（留空 = 还没录，不可比）", type = "string") @DecimalMin("0")
          BigDecimal gmv,
      @Schema(description = "GMV 币种（3 位字母）") @Size(max = 3) String gmvCurrency,
      @Schema(description = "退款率（0 ~ 1；留空 = 还没录）", type = "string")
          @DecimalMin("0")
          @DecimalMax("1")
          BigDecimal refundRate,
      @Schema(description = "状态：ACTIVE / INACTIVE") String status,
      @Schema(description = "备注") String note) {}

  /** 批量打标（打标 / 批量打标都走这一个入口）。 */
  public record TagInfluencersRequest(
      @Schema(description = "标签 id 列表") List<UUID> tagIds,
      @Schema(description = "标签编码列表（与 tagIds 合并后一起打）") List<String> tagCodes,
      @Schema(description = "备注") String note) {}

  /** 登记 / 修改标签。 */
  public record SaveInfluencerTagRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "分组 id") @NotNull
          UUID groupId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "标签编码（租户内唯一）")
          @NotBlank
          @Size(max = 64)
          String tagCode,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "标签名称") @NotBlank
          @Size(max = 200)
          String tagName,
      @Schema(description = "标签说明") String description,
      @Schema(description = "状态：ACTIVE / PAUSED") String status,
      @Schema(description = "备注") String note) {}

  /** 登记 / 修改标签分组。 */
  public record SaveInfluencerTagGroupRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "分组编码（租户内唯一）")
          @NotBlank
          @Size(max = 64)
          String groupCode,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "分组名称") @NotBlank
          @Size(max = 200)
          String groupName,
      @Schema(description = "分组说明") String description,
      @Schema(description = "状态：ACTIVE / PAUSED") String status,
      @Schema(description = "备注") String note) {}

  /** 登记 / 修改评级档位。 */
  public record SaveInfluencerRatingTierRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "档位编码（租户内唯一）")
          @NotBlank
          @Size(max = 32)
          String tierCode,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "档位名称") @NotBlank
          @Size(max = 100)
          String tierName,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "分数下限（含）", type = "string")
          @DecimalMin("0")
          @DecimalMax("1")
          BigDecimal minScore,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "分数上限（含）", type = "string")
          @DecimalMin("0")
          @DecimalMax("1")
          BigDecimal maxScore,
      @Schema(description = "排序位（越大越靠前）") Integer sortOrder,
      @Schema(description = "状态：ACTIVE / PAUSED") String status,
      @Schema(description = "备注") String note) {}

  /** 配置评分口径。 */
  public record SaveInfluencerRatingRuleRequest(
      @Schema(description = "口径编码（租户内唯一）") @Size(max = 64) String ruleCode,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "GMV 权重", type = "string")
          @DecimalMin("0")
          @DecimalMax("1")
          BigDecimal weightGmv,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "互动率权重", type = "string")
          @DecimalMin("0")
          @DecimalMax("1")
          BigDecimal weightEngagement,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "退款率权重", type = "string")
          @DecimalMin("0")
          @DecimalMax("1")
          BigDecimal weightRefund,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "GMV 归一化上限", type = "string")
          @DecimalMin(value = "0", inclusive = false)
          BigDecimal gmvCap,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "互动率归一化上限", type = "string")
          @DecimalMin(value = "0", inclusive = false)
          BigDecimal engagementCap,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "退款率归一化上限", type = "string")
          @DecimalMin(value = "0", inclusive = false)
          BigDecimal refundCap,
      @Schema(description = "状态：ACTIVE / PAUSED") String status,
      @Schema(description = "备注") String note) {}

  /** 拉黑一个达人。 */
  public record BlacklistInfluencerRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "达人 id") @NotNull
          UUID influencerId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "拉黑原因码")
          @NotBlank
          String reasonCode,
      @Schema(description = "拉黑理由（人话）") String reason,
      @Schema(description = "生效时刻（留空 = 现在生效）") OffsetDateTime effectiveAt,
      @Schema(description = "备注") String note) {}

  /** 解除黑名单。 */
  public record ReleaseBlacklistRequest(
      @Schema(description = "解除理由（会写进留痕）") String reason) {}

  /** 登记 / 修改一条合作记录（达人 × 活动）。 */
  public record SaveCooperationRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "达人 id") @NotNull
          UUID influencerId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "活动 id") @NotNull
          UUID campaignId,
      @Schema(description = "状态：PLANNED / ACTIVE / COMPLETED / CANCELLED") String status,
      @Schema(description = "开始时刻") OffsetDateTime startedAt,
      @Schema(description = "结束时刻") OffsetDateTime endedAt,
      @Schema(description = "合作 GMV（留空 = 还没录）", type = "string") @DecimalMin("0")
          BigDecimal gmv,
      @Schema(description = "合作订单量（留空 = 还没录）", type = "string") @DecimalMin("0")
          BigDecimal orderCount,
      @Schema(description = "佣金（留空 = 还没录）", type = "string") @DecimalMin("0") BigDecimal commission,
      @Schema(description = "退款率（0 ~ 1；留空 = 还没录）", type = "string")
          @DecimalMin("0")
          @DecimalMax("1")
          BigDecimal refundRate,
      @Schema(description = "币种（3 位字母）") @Size(max = 3) String currency,
      @Schema(description = "备注") String note) {}

  /** 触发一次平台达人数据同步。 */
  public record SyncInfluencersRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "平台编码") @NotBlank
          String platformCode,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "店铺编码") @NotBlank
          String shopCode) {}
}
