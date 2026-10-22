package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 14.5.2.1 带货归因 的传输模型。
 *
 * <p>蓝图：「达人带货归因：达人带来的订单量、GMV、退款率、佣金成本、有效粉丝转化，
 * 对比达人投入产出，沉淀优质达人库。输入：达人带货数据同步；订单归因；佣金数据。
 * 输出：达人效果报表 + ROI + 评级更新。关系：上游 = 14.5.1.1、平台数据；
 * 下游 = 14.5.1.1 评级、13.4 利润（佣金成本）。
 * <b>达人佣金是真实成本，必须进利润表</b>，否则会高估达人带货的净收益。」
 *
 * <p>🔴 这个模型层钉死本模块最容易做丢的四件事：
 *
 * <ol>
 *   <li><b>三个 GMV 口径分列三栏</b>：档案 GMV（14.5.1.1 入库值）、
 *       合作历史合计（本地按合作行求和）、归因口径（本模块按规则分摊后的净额）。
 *       三个字段**各归各的**，谁也不覆盖谁 —— 报表里必须能分别看到。
 *   <li><b>佣金成本是真实成本，但没进利润表</b>。{@code commissionAmount} /
 *       {@code commissionBasisAmount} / {@code commissionBasis} / {@code accrualStatus}
 *       是**本服务内的真实留痕**；而利润侧（13.4）<b>没有接收方</b>，
 *       所以能力声明里 {@code profitIntegration.available = false}，
 *       <b>不许写「已同步到利润分析」</b>。
 *   <li><b>「没录」不是 0</b>：{@code sourceGmv} / {@code sourceCommission} /
 *       {@code commissionAmount} 为 {@code null} 表示<b>不可比</b>（还没录），
 *       与「录了，而且真的是零」含义相反。
 *   <li><b>平台带货数据同步未接入</b>：{@code availability} 逐平台答「未接入」，
 *       同步入口明确 409 且<b>不写任何数据</b>。
 * </ol>
 *
 * <p>🔴 金额与数量一律 {@link BigDecimal}，**没有 double**：GMV / 佣金 / ROI /
 * 退款率 / 转化率都是钱或比率，它们直接决定「这个达人值不值得投」。
 */
public final class InfluencerAttributionModels {
  private InfluencerAttributionModels() {}

  // ================================================================ 能力声明

  public record InfluencerAttributionCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  /**
   * 🔴 利润侧对接状态。
   *
   * <p><b>{@code available} 恒为 false</b>：13.4 利润（reporting 服务）**未交付**，
   * 集成头里根本没有 reporting 服务。佣金成本目前<b>只在本服务内留痕</b>
   * （归因行上的金额 / 口径 / 计提状态），<b>没有进任何利润报表</b>。
   *
   * <p>这个字段存在的意义就是<b>不让界面与文档宣称一件没发生的事</b>。
   */
  public record InfluencerAttributionProfitIntegration(
      @Schema(description = "佣金成本是否已进利润表（本模块恒为 false：13.4 未交付）")
          boolean available,
      @Schema(description = "责任模块") String ownerModule,
      @Schema(description = "未对接的影响与当前替代口径") String note) {}

  public record InfluencerAttributionCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false")
          List<InfluencerAttributionCapabilityDimension> dimensions,
      @Schema(description = "🔴 利润侧对接状态（available 恒为 false：13.4 未交付）")
          InfluencerAttributionProfitIntegration profitIntegration,
      @Schema(description = "归因模型取值") List<String> attributionModels,
      @Schema(description = "分摊权重基准取值") List<String> poolWeightBases,
      @Schema(description = "退款口径取值") List<String> refundTreatments,
      @Schema(description = "佣金计提口径取值") List<String> commissionBases,
      @Schema(description = "计提状态取值") List<String> accrualStatuses,
      @Schema(description = "🔴 三口径说明") String caliberNote,
      @Schema(description = "🔴 归因规则口径说明") String ruleNote,
      @Schema(description = "🔴 佣金成本口径说明") String commissionNote,
      @Schema(description = "🔴 平台同步能力说明") String syncNote,
      @Schema(description = "上游与边界说明") String upstreamNote,
      @Schema(description = "下游与边界说明") String boundaryNote) {}

  // ================================================================ 下拉项

  public record InfluencerAttributionOption(
      @Schema(description = "取值") String code, @Schema(description = "中文说明") String label) {}

  public record InfluencerAttributionPlatformOption(
      @Schema(description = "平台 id") UUID id,
      @Schema(description = "平台编码") String code,
      @Schema(description = "平台名称") String name) {}

  public record InfluencerAttributionOptions(
      @Schema(description = "归因模型选项") List<InfluencerAttributionOption> attributionModels,
      @Schema(description = "分摊权重基准选项") List<InfluencerAttributionOption> poolWeightBases,
      @Schema(description = "退款口径选项") List<InfluencerAttributionOption> refundTreatments,
      @Schema(description = "佣金计提口径选项") List<InfluencerAttributionOption> commissionBases,
      @Schema(description = "计提状态选项") List<InfluencerAttributionOption> accrualStatuses,
      @Schema(description = "平台下拉项（带 id）") List<InfluencerAttributionPlatformOption> platforms,
      @Schema(description = "平台编码（租户内可用的）") List<String> platformCodes,
      @Schema(description = "店铺编码（租户内可用的）") List<String> shopCodes,
      @Schema(description = "币种（归因里出现过的）") List<String> currencies,
      @Schema(description = "🔴 三口径说明") String caliberNote,
      @Schema(description = "🔴 佣金成本口径说明") String commissionNote) {}

  // ================================================================ 平台同步

  public record InfluencerAttributionPlatformSyncAvailability(
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "平台名称") String platformName,
      @Schema(description = "带货数据同步是否已接入") boolean available,
      @Schema(description = "状态码：NOT_INTEGRATED / AVAILABLE") String status,
      @Schema(description = "人能读的原因") String reason,
      @Schema(description = "本租户在该平台已有的归因记录数") int registeredCount) {}

  public record InfluencerAttributionSyncStatus(
      @Schema(description = "逐平台接入情况") List<InfluencerAttributionPlatformSyncAvailability> items,
      @Schema(description = "🔴 平台同步能力说明") String note) {}

  /** 触发一次平台带货数据同步（**未接入 ⇒ 409 且不写任何数据**）。 */
  public record InfluencerAttributionSyncRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "平台编码") @NotBlank
          String platformCode,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "店铺编码") @NotBlank
          String shopCode) {}

  public record InfluencerAttributionSyncResult(
      @Schema(description = "是否成功") boolean success,
      @Schema(description = "结论") String message,
      @Schema(description = "同步条数") int synced,
      @Schema(description = "跳过条数") int skipped) {}

  // ================================================================ 归因规则

  public record InfluencerAttributionRuleRow(
      @Schema(description = "规则 id") UUID id,
      @Schema(description = "规则编码") String ruleCode,
      @Schema(description = "归因模型") String attributionModel,
      @Schema(description = "分摊权重基准") String poolWeightBasis,
      @Schema(description = "归因窗口（天）") int attributionWindowDays,
      @Schema(description = "退款口径") String refundTreatment,
      @Schema(description = "佣金计提口径") String commissionBasis,
      @Schema(description = "佣金比例（0 ~ 1；null = 用合作记录里的佣金）", type = "string")
          BigDecimal commissionRate,
      @Schema(description = "状态：ACTIVE / PAUSED") String status,
      @Schema(description = "备注") String note,
      @Schema(description = "版本") long version,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  public record InfluencerAttributionRulePage(
      @Schema(description = "规则列表") List<InfluencerAttributionRuleRow> items,
      @Schema(description = "当前页（0 基）") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "总条数") long total,
      @Schema(description = "🔴 归因规则口径说明") String ruleNote) {}

  /** 配置归因规则（保存新规则时旧的自动停用）。 */
  public record SaveInfluencerAttributionRuleRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "规则编码") @NotBlank
          @Size(max = 64)
          String ruleCode,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "归因模型")
          @NotBlank
          String attributionModel,
      @Schema(description = "分摊权重基准") String poolWeightBasis,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "归因窗口（天）")
          @Min(0)
          @Max(365)
          int attributionWindowDays,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "退款口径") @NotBlank
          String refundTreatment,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "佣金计提口径") @NotBlank
          String commissionBasis,
      @Schema(description = "佣金比例（0 ~ 1）", type = "string")
          @DecimalMin("0")
          @DecimalMax("1")
          BigDecimal commissionRate,
      @Schema(description = "状态：ACTIVE / PAUSED") String status,
      @Schema(description = "备注") String note) {}

  // ================================================================ 归因记录

  /**
   * 一条归因记录。
   *
   * <p>🔴 每一行都自带<b>规则快照</b>（归因模型 / 分摊基准 / 归因窗口 / 退款口径 /
   * 佣金口径与比例）与<b>源快照</b>（合作记录当时的 GMV / 订单量 / 佣金 / 退款率）——
   * 口径改了以后历史归因仍然能被解释清楚。
   */
  public record InfluencerAttributionRow(
      @Schema(description = "归因记录 id") UUID id,
      @Schema(description = "达人 id") UUID influencerId,
      @Schema(description = "达人名称") String influencerName,
      @Schema(description = "达人账号名") String influencerHandle,
      @Schema(description = "达人类目") String influencerCategory,
      @Schema(description = "粉丝数") long followerCount,
      @Schema(description = "粉丝层级") String followerTier,
      @Schema(description = "合作记录 id") UUID cooperationId,
      @Schema(description = "活动 id") UUID campaignId,
      @Schema(description = "活动名称") String campaignName,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "归因规则编码") String ruleCode,
      @Schema(description = "🔴 规则快照：归因模型") String attributionModel,
      @Schema(description = "🔴 规则快照：分摊权重基准") String poolWeightBasis,
      @Schema(description = "🔴 规则快照：归因窗口（天）") int attributionWindowDays,
      @Schema(description = "🔴 规则快照：退款口径") String refundTreatment,
      @Schema(description = "🔴 规则快照：佣金计提口径") String commissionBasis,
      @Schema(description = "🔴 规则快照：佣金比例", type = "string") BigDecimal commissionRate,
      @Schema(description = "🔴 归因权重（0 ~ 1）", type = "string") BigDecimal attributionWeight,
      @Schema(description = "🔴 源快照：合作 GMV（null = 还没录）", type = "string")
          BigDecimal sourceGmv,
      @Schema(description = "🔴 源快照：合作订单量（null = 还没录）", type = "string")
          BigDecimal sourceOrderCount,
      @Schema(description = "🔴 源快照：合作佣金（null = 还没录）", type = "string")
          BigDecimal sourceCommission,
      @Schema(description = "🔴 源快照：合作退款率（null = 还没录）", type = "string")
          BigDecimal sourceRefundRate,
      @Schema(description = "🔴 源快照：币种") String sourceCurrency,
      @Schema(description = "🔴 归因基数：GMV（合作全额归因 = 自身值；活动池分摊 = 池子合计）", type = "string")
          BigDecimal attributionBaseGmv,
      @Schema(description = "🔴 归因基数：订单量", type = "string")
          BigDecimal attributionBaseOrderCount,
      @Schema(description = "🔴 归因基数：退款率（0 ~ 1）", type = "string")
          BigDecimal attributionBaseRefundRate,
      @Schema(description = "🔴 归因 GMV（第三口径；null = 还没录）", type = "string")
          BigDecimal attributedGmv,
      @Schema(description = "🔴 归因订单量（null = 还没录）", type = "string")
          BigDecimal attributedOrderCount,
      @Schema(description = "🔴 归因退款率（null = 还没录）", type = "string")
          BigDecimal attributedRefundRate,
      @Schema(description = "🔴 归因退款额（null = 还没录）", type = "string")
          BigDecimal attributedRefundAmount,
      @Schema(description = "🔴 有效订单量（扣退款后）", type = "string")
          BigDecimal effectiveOrderCount,
      @Schema(description = "🔴 有效粉丝转化率（0 ~ 1；null = 粉丝数未录）", type = "string")
          BigDecimal followerConversionRate,
      @Schema(description = "粉丝数快照（归因时刻）") long followerCountSnapshot,
      @Schema(description = "币种") String currency,
      @Schema(description = "🔴 佣金成本金额（null = 还没录；**本服务内留痕，未进利润表**）", type = "string")
          BigDecimal commissionAmount,
      @Schema(description = "🔴 佣金计提基数", type = "string") BigDecimal commissionBasisAmount,
      @Schema(description = "🔴 佣金差额（重算值 − 已计提值）", type = "string")
          BigDecimal commissionVariance,
      @Schema(description = "🔴 计提状态：PENDING / ACCRUED / REVERSED") String accrualStatus,
      @Schema(description = "🔴 计提时刻") OffsetDateTime accruedAt,
      @Schema(description = "🔴 计提备注") String accrualNote,
      @Schema(description = "归因时刻") OffsetDateTime attributedAt,
      @Schema(description = "备注") String note,
      @Schema(description = "版本") long version,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  public record InfluencerAttributionPage(
      @Schema(description = "归因记录列表") List<InfluencerAttributionRow> items,
      @Schema(description = "当前页（0 基）") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "总条数") long total,
      @Schema(description = "🔴 三口径说明") String caliberNote,
      @Schema(description = "🔴 佣金成本口径说明") String commissionNote) {}

  /** 🔴 达人三个 GMV 口径并排（详情与报表都用它）。 */
  public record InfluencerAttributionInfluencerSummary(
      @Schema(description = "达人 id") UUID influencerId,
      @Schema(description = "达人名称") String displayName,
      @Schema(description = "达人账号名") String handle,
      @Schema(description = "类目") String category,
      @Schema(description = "粉丝数") long followerCount,
      @Schema(description = "粉丝层级") String followerTier,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "🔴 口径一：档案 GMV（14.5.1.1 入库值；null = 还没录）", type = "string")
          BigDecimal profileGmv,
      @Schema(description = "🔴 档案 GMV 币种") String profileGmvCurrency,
      @Schema(description = "🔴 口径二：合作历史合计 GMV（本地按合作行求和；null = 还没录）", type = "string")
          BigDecimal cooperationGmvTotal,
      @Schema(description = "🔴 合作历史合计订单量", type = "string") BigDecimal cooperationOrderTotal,
      @Schema(description = "🔴 合作历史合计佣金", type = "string") BigDecimal cooperationCommissionTotal,
      @Schema(description = "🔴 口径三：归因 GMV（本模块；null = 还没归因）", type = "string")
          BigDecimal attributedGmvTotal,
      @Schema(description = "🔴 归因订单量合计", type = "string") BigDecimal attributedOrderTotal,
      @Schema(description = "🔴 归因佣金成本合计", type = "string") BigDecimal commissionTotal,
      @Schema(description = "🔴 有效订单量合计", type = "string") BigDecimal effectiveOrderTotal,
      @Schema(description = "🔴 归因 ROI（归因 GMV ÷ 佣金成本；null = 佣金未录或为 0）", type = "string")
          BigDecimal roi,
      @Schema(description = "🔴 有效粉丝转化率（0 ~ 1）", type = "string")
          BigDecimal followerConversionRate,
      @Schema(description = "评级分数（14.5.1.1 口径；null = 暂无评分）", type = "string")
          BigDecimal ratingScore,
      @Schema(description = "评级档位编码") String ratingTierCode,
      @Schema(description = "评级档位名称") String ratingTierName) {}

  /** 归因的源：一条真实存在的合作记录。 */
  public record InfluencerAttributionCooperationSource(
      @Schema(description = "合作记录 id") UUID cooperationId,
      @Schema(description = "活动 id") UUID campaignId,
      @Schema(description = "活动名称") String campaignName,
      @Schema(description = "合作状态") String status,
      @Schema(description = "合作开始时刻") OffsetDateTime startedAt,
      @Schema(description = "合作结束时刻") OffsetDateTime endedAt,
      @Schema(description = "合作 GMV（null = 还没录）", type = "string") BigDecimal gmv,
      @Schema(description = "合作订单量（null = 还没录）", type = "string") BigDecimal orderCount,
      @Schema(description = "合作佣金（null = 还没录）", type = "string") BigDecimal commission,
      @Schema(description = "合作退款率（null = 还没录）", type = "string") BigDecimal refundRate,
      @Schema(description = "币种") String currency) {}

  /** 归因用的规则快照。 */
  public record InfluencerAttributionRuleSnapshot(
      @Schema(description = "规则 id") UUID id,
      @Schema(description = "规则编码") String ruleCode,
      @Schema(description = "归因模型") String attributionModel,
      @Schema(description = "分摊权重基准") String poolWeightBasis,
      @Schema(description = "归因窗口（天）") int attributionWindowDays,
      @Schema(description = "退款口径") String refundTreatment,
      @Schema(description = "佣金计提口径") String commissionBasis,
      @Schema(description = "佣金比例", type = "string") BigDecimal commissionRate,
      @Schema(description = "状态") String status,
      @Schema(description = "备注") String note) {}

  public record InfluencerAttributionDetail(
      @Schema(description = "归因记录") InfluencerAttributionRow row,
      @Schema(description = "达人与三个口径") InfluencerAttributionInfluencerSummary influencer,
      @Schema(description = "源：合作记录") InfluencerAttributionCooperationSource source,
      @Schema(description = "归因用的规则快照") InfluencerAttributionRuleSnapshot rule,
      @Schema(description = "🔴 三口径说明") String caliberNote,
      @Schema(description = "🔴 佣金成本口径说明") String commissionNote,
      @Schema(description = "🔴 利润侧对接状态") InfluencerAttributionProfitIntegration profitIntegration) {}

  // ================================================================ 归因批次

  /** 触发一次归因批次（按生效规则把合作记录归因到达人）。 */
  public record InfluencerAttributionRunRequest(
      @Schema(description = "只归因某个达人（留空 = 全部）") UUID influencerId,
      @Schema(description = "只归因某个活动（留空 = 全部）") UUID campaignId,
      @Schema(description = "只归因某个店铺（留空 = 全部）") String shopCode,
      @Schema(description = "合作开始时刻下界（留空 = 不限）") OffsetDateTime from,
      @Schema(description = "合作开始时刻上界（留空 = 不限）") OffsetDateTime to) {}

  public record InfluencerAttributionRunResult(
      @Schema(description = "是否成功") boolean success,
      @Schema(description = "本批次使用的规则编码") String ruleCode,
      @Schema(description = "本批次使用的归因模型") String attributionModel,
      @Schema(description = "处理的合作记录数") int processed,
      @Schema(description = "新建的归因记录数") int created,
      @Schema(description = "刷新的归因记录数") int refreshed,
      @Schema(description = "🔴 跳过：已出归因窗口（不允许新增归因）") int skippedOutOfWindow,
      @Schema(description = "🔴 跳过：合作记录没有效果数据（GMV / 订单量都没录）") int skippedNoEffectData,
      @Schema(description = "🔴 跳过：分摊基准不可用（如粉丝数合计为 0）") int skippedNoSplitBasis,
      @Schema(description = "触发的评级重算次数（评级口径归 14.5.1.1）") int ratingRecomputed,
      @Schema(description = "结论说明") String message) {}

  // ================================================================ 佣金计提

  /** 变更佣金计提状态（计提 / 冲销 / 退回待计提）。 */
  public record InfluencerAttributionAccrualRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "目标状态：ACCRUED / REVERSED / PENDING")
          @NotBlank
          String status,
      @Schema(description = "备注（会写进留痕）") String note) {}

  // ================================================================ 达人效果报表

  /**
   * 达人效果报表的一行。
   *
   * <p>🔴 <b>三个 GMV 口径分列三栏</b>：{@code profileGmv}（档案口径）、
   * {@code cooperationGmvTotal}（合作口径）、{@code attributedGmvTotal}（归因口径）。
   */
  public record InfluencerAttributionReportRow(
      @Schema(description = "达人 id") UUID influencerId,
      @Schema(description = "达人名称") String displayName,
      @Schema(description = "达人账号名") String handle,
      @Schema(description = "类目") String category,
      @Schema(description = "粉丝数") long followerCount,
      @Schema(description = "粉丝层级") String followerTier,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "🔴 口径一：档案 GMV（null = 还没录）", type = "string")
          BigDecimal profileGmv,
      @Schema(description = "档案 GMV 币种") String profileGmvCurrency,
      @Schema(description = "🔴 口径二：合作历史合计 GMV（null = 还没录）", type = "string")
          BigDecimal cooperationGmvTotal,
      @Schema(description = "🔴 合作历史合计订单量", type = "string") BigDecimal cooperationOrderTotal,
      @Schema(description = "🔴 合作历史合计佣金", type = "string") BigDecimal cooperationCommissionTotal,
      @Schema(description = "🔴 口径三：归因 GMV（null = 还没归因）", type = "string")
          BigDecimal attributedGmvTotal,
      @Schema(description = "🔴 归因订单量", type = "string") BigDecimal attributedOrderTotal,
      @Schema(description = "🔴 归因退款率（加权）", type = "string") BigDecimal attributedRefundRate,
      @Schema(description = "🔴 佣金成本合计（null = 还没录）", type = "string")
          BigDecimal commissionTotal,
      @Schema(description = "🔴 已计提佣金合计", type = "string") BigDecimal commissionAccruedTotal,
      @Schema(description = "🔴 待计提佣金合计", type = "string") BigDecimal commissionPendingTotal,
      @Schema(description = "🔴 有效订单量（扣退款）", type = "string") BigDecimal effectiveOrderTotal,
      @Schema(description = "🔴 有效粉丝转化率（0 ~ 1）", type = "string")
          BigDecimal followerConversionRate,
      @Schema(description = "🔴 归因 ROI（归因 GMV ÷ 佣金成本；null = 佣金未录或为 0）", type = "string")
          BigDecimal roi,
      @Schema(description = "🔴 扣佣金后净额（**只扣佣金，不含样品 / 履约 / 平台费**）", type = "string")
          BigDecimal netGmvAfterCommission,
      @Schema(description = "归因记录数") long attributionCount,
      @Schema(description = "评级分数（14.5.1.1 口径；null = 暂无评分）", type = "string")
          BigDecimal ratingScore,
      @Schema(description = "评级档位编码") String ratingTierCode,
      @Schema(description = "评级档位名称") String ratingTierName) {}

  public record InfluencerAttributionReportSummary(
      @Schema(description = "有归因记录的达人数") int influencerCount,
      @Schema(description = "归因记录数") long attributionCount,
      @Schema(description = "🔴 归因 GMV 合计", type = "string") BigDecimal attributedGmvTotal,
      @Schema(description = "🔴 佣金成本合计（null = 还没录）", type = "string")
          BigDecimal commissionTotal,
      @Schema(description = "🔴 已计提佣金合计", type = "string") BigDecimal commissionAccruedTotal,
      @Schema(description = "🔴 待计提佣金合计", type = "string") BigDecimal commissionPendingTotal,
      @Schema(description = "🔴 整体 ROI（归因 GMV ÷ 佣金成本；null = 佣金未录或为 0）", type = "string")
          BigDecimal roi,
      @Schema(description = "🔴 有效订单量合计", type = "string") BigDecimal effectiveOrderTotal,
      @Schema(description = "🔴 整体有效粉丝转化率", type = "string")
          BigDecimal followerConversionRate,
      @Schema(description = "本报表使用的归因规则编码") String ruleCode,
      @Schema(description = "本报表使用的归因模型") String attributionModel,
      @Schema(description = "🔴 利润侧对接状态") InfluencerAttributionProfitIntegration profitIntegration) {}

  public record InfluencerAttributionReportPage(
      @Schema(description = "达人效果报表（真分页）") List<InfluencerAttributionReportRow> items,
      @Schema(description = "当前页（0 基）") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "总条数") long total,
      @Schema(description = "汇总") InfluencerAttributionReportSummary summary,
      @Schema(description = "🔴 三口径说明") String caliberNote,
      @Schema(description = "🔴 归因规则口径说明") String ruleNote,
      @Schema(description = "🔴 佣金成本口径说明") String commissionNote,
      @Schema(description = "🔴 利润侧对接状态") InfluencerAttributionProfitIntegration profitIntegration) {}
}
