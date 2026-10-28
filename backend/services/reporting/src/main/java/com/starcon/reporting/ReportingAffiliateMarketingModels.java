package com.starcon.reporting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 13.3.2.2 关联营销效果的传输模型。
 *
 * <p>蓝图原文：「分析站外/关联营销（关联视频、A+、Store 导流、社交媒体）的引流效果：
 * 曝光、点击、加购、成交归因。输入：营销位配置与曝光点击数据；订单归因数据。
 * 输出：营销位效果报表 + ROI。关系：上游 = 4 刊登（营销模块）、14 广告；下游 = 13.3.1.1。
 * <b>归因需要明确规则</b>（末次点击 vs 首次点击），否则各人看到的数不一样。」
 *
 * <p>🔴 这个模型层钉死本模块最容易做丢的四件事：
 *
 * <ol>
 *   <li><b>归因规则随行</b>：每一行归因数据都带<b>规则快照</b>（归因模型 / 分摊基准 /
 *       归因窗口 / 退款口径 / 佣金口径），报表按 (营销位, 归因规则) 分列 ⇒
 *       <b>ROI 只在同一条规则内部相除</b>。
 *   <li><b>口径标签随行</b>：达人归因口径与落地商品口径<b>不是同一个数</b>，
 *       每个营销位行都带自己的口径标签与说明。
 *   <li><b>「没录」不是 0</b>：{@code attributedGmv} / {@code commissionAmount} 为
 *       {@code null} 表示<b>不可比</b>（上游没给币种 / 没录），与「录了，而且真的是零」相反。
 *   <li><b>「本轮不可用」不是 0</b>：{@code availability = NOT_AVAILABLE} 表示
 *       <b>这个口径上游没有</b>，页面显示「本轮不可用 + 归谁管」，<b>不显示 0</b>。
 * </ol>
 *
 * <p>🔴 金额与数量一律 {@link BigDecimal}，<b>没有 double</b>。
 */
public final class ReportingAffiliateMarketingModels {
  private ReportingAffiliateMarketingModels() {}

  // ================================================================ 能力声明

  public record AffiliateMarketingCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本轮是否可用") boolean available,
      @Schema(description = "数据来源（上游模块 / 表）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响与当前替代口径") String impact) {}

  public record AffiliateMarketingSlotTypeView(
      @Schema(description = "营销位类型：RELATED_VIDEO / A_PLUS / STORE_TRAFFIC / SOCIAL_MEDIA")
          String slotType,
      @Schema(description = "营销位类型显示名") String displayName,
      @Schema(description = "🔴 本轮这一类营销位**有没有**可用数据") boolean available,
      @Schema(description = "🔴 数据口径：INFLUENCER_ATTRIBUTION / LANDING_LISTING（未交付时为空串）")
          String caliber,
      @Schema(description = "🔴 上游 / 归属模块") String owner,
      @Schema(description = "🔴 这一类营销位的口径说明（页面上常驻显示）") String note) {}

  public record AffiliateMarketingRuleNote(
      @Schema(description = "规则码") String code, @Schema(description = "规则说明") String note) {}

  public record AffiliateMarketingCapabilities(
      @Schema(description = "营销位效果报表是否可用") boolean reportAvailable,
      @Schema(description = "投影刷新是否可用") boolean refreshAvailable,
      @Schema(description = "成交归因是否可用（达人归因口径）") boolean attributionAvailable,
      @Schema(description = "落地商品口径（曝光 / 点击）是否可用") boolean landingListingAvailable,
      @Schema(description = "本服务**真的读过**的上游模块") List<String> upstreamModules,
      @Schema(description = "🔴 **确实拿不到**的部分（每条都写清原因与替代口径）")
          List<AffiliateMarketingCapabilityDimension> notDelivered,
      @Schema(description = "判定规则与口径说明") List<AffiliateMarketingRuleNote> rules,
      @Schema(description = "四类营销位逐类状态") List<AffiliateMarketingSlotTypeView> slotTypes,
      @Schema(description = "指标码清单") List<String> metricCodes,
      @Schema(description = "🔴 归因模型取值域") List<String> attributionModels,
      @Schema(description = "🔴 分摊权重基准取值域") List<String> poolWeightBases,
      @Schema(description = "🔴 退款口径取值域") List<String> refundTreatments,
      @Schema(description = "🔴 佣金计提口径取值域") List<String> commissionBases,
      @Schema(description = "🔴 归因规则口径总说明") String attributionRuleNote,
      @Schema(description = "🔴 币种口径说明") String currencyNote,
      @Schema(description = "🔴 口径边界说明") String caliberNote) {}

  // ================================================================ 指标口径目录

  public record AffiliateMarketingMetricDefinitionView(
      @Schema(description = "指标码") String metricCode,
      @Schema(description = "显示名") String displayName,
      @Schema(description = "单位：CURRENCY / COUNT / QUANTITY / PERCENT") String unit,
      @Schema(description = "聚合方式：SUM / DERIVED_RATIO") String aggregation,
      @Schema(description = "派生型的分子指标码", nullable = true) String numeratorMetricCode,
      @Schema(description = "派生型的分母指标码", nullable = true) String denominatorMetricCode,
      @Schema(description = "是否涉币（涉币 ⇒ 绝不跨币种相加）") boolean currencyAware,
      @Schema(description = "是否依赖归因规则") boolean attributionBound,
      @Schema(description = "🔴 本轮是否可用（false ⇒ 本轮不可用 + 归谁管）") boolean available,
      @Schema(description = "上游归属模块 / 未交付段的责任模块") String ownerModule,
      @Schema(description = "口径说明（页面上照着显示）") String description) {}

  // ================================================================ 主报表

  /**
   * 一个营销位（× 一条归因规则）的效果行。
   *
   * <p>🔴 <b>归因规则是行的一部分</b>：同一对达人 × 活动如果历史上用过两条不同的归因规则，
   * 这里就是<b>两行</b>，各带自己的规则快照 —— 这样 ROI 不会把两条规则的钱加到一起再除。
   */
  public record AffiliateMarketingSlotRow(
      @Schema(description = "营销位类型") String slotType,
      @Schema(description = "营销位类型显示名") String slotTypeName,
      @Schema(description = "营销位键（社交媒体 = 达人 id : 活动 id；A+ = listing id）")
          String slotKey,
      @Schema(description = "营销位可读标签") String slotLabel,
      @Schema(description = "🔴 数据口径：INFLUENCER_ATTRIBUTION / LANDING_LISTING") String caliber,
      @Schema(description = "🔴 数据口径显示名") String caliberName,
      @Schema(description = "店铺编码（上游没给就是空串）") String shopCode,
      @Schema(description = "平台编码（上游没给就是空串）") String platformCode,
      @Schema(description = "🔴 归因规则码（落地商品口径没有归因 ⇒ 空串）") String attributionRuleCode,
      @Schema(description = "🔴 归因模型（规则快照）") String attributionModel,
      @Schema(description = "🔴 分摊权重基准（规则快照）") String poolWeightBasis,
      @Schema(description = "🔴 归因窗口（天，规则快照）", nullable = true) Integer attributionWindowDays,
      @Schema(description = "🔴 退款口径（规则快照）") String refundTreatment,
      @Schema(description = "🔴 佣金计提口径（规则快照）") String commissionBasis,
      @Schema(description = "这个营销位在这个窗口里的归因记录条数") int attributionRecordCount,
      @Schema(description = "逐指标取值（每格都带可用性与原因）")
          List<AffiliateMarketingMetricValue> metrics) {}

  /**
   * 一个指标的取值。
   *
   * <p>🔴 {@code availability} 的取值：
   * {@code AVAILABLE} 可用 / {@code NO_DATA} 可算但窗口内没有事实 /
   * {@code NOT_AVAILABLE} <b>本轮不可用</b>（该营销位类型没有这个口径，归谁管见能力声明）/
   * {@code NOT_COMPARABLE} <b>算不出来</b>（分母缺失或为 0，<b>不是 0</b>）。
   */
  public record AffiliateMarketingMetricValue(
      @Schema(description = "指标码") String metricCode,
      @Schema(description = "显示名") String displayName,
      @Schema(description = "单位") String unit,
      @Schema(description = "🔴 币种（空串 = 与币种无关，不是「币种未知」）") String currency,
      @Schema(description = "金额型取值（数量型指标恒为 null）", nullable = true) BigDecimal value,
      @Schema(description = "数量型取值（金额型指标恒为 null）", nullable = true) BigDecimal quantity,
      @Schema(description = "🔴 可用性：AVAILABLE / NO_DATA / NOT_AVAILABLE / NOT_COMPARABLE")
          String availability,
      @Schema(description = "🔴 可用性说明（页面上照着显示）") String availabilityNote) {}

  /** 本窗口实际用到的归因规则（**规则快照** + 记录条数）。 */
  public record AffiliateMarketingRuleUsage(
      @Schema(description = "归因规则码") String ruleCode,
      @Schema(description = "🔴 归因模型（规则快照）") String attributionModel,
      @Schema(description = "🔴 分摊权重基准（规则快照）") String poolWeightBasis,
      @Schema(description = "🔴 归因窗口（天，规则快照）", nullable = true) Integer attributionWindowDays,
      @Schema(description = "🔴 退款口径（规则快照）") String refundTreatment,
      @Schema(description = "🔴 佣金计提口径（规则快照）") String commissionBasis,
      @Schema(description = "本窗口用这条规则的归因记录条数") int recordCount,
      @Schema(description = "🔴 这条规则是怎么来的（快照，不是「当前规则」）") String note) {}

  public record AffiliateMarketingResponse(
      @Schema(description = "窗口起始日") LocalDate from,
      @Schema(description = "窗口结束日") LocalDate to,
      @Schema(description = "🔴 营销位效果报表（按 (营销位, 归因规则) 分列）")
          List<AffiliateMarketingSlotRow> slots,
      @Schema(description = "🔴 本窗口用到的归因规则（规则快照）") List<AffiliateMarketingRuleUsage> attributionRules,
      @Schema(description = "四类营销位逐类状态") List<AffiliateMarketingSlotTypeView> slotTypes,
      @Schema(description = "指标口径目录") List<AffiliateMarketingMetricDefinitionView> metricDefinitions,
      @Schema(description = "数据更新时间（最近一次投影落库时刻）", nullable = true) OffsetDateTime dataUpdatedAt,
      @Schema(description = "数据更新时间的说明") String dataUpdatedNote,
      @Schema(description = "状态：OK / EMPTY") String status,
      @Schema(description = "状态说明") String statusNote,
      @Schema(description = "空态原因（空 = 不空）", nullable = true) String emptyReason,
      @Schema(description = "空态说明") String emptyNote,
      @Schema(description = "⚠️ 警告（缺数据 / 口径提醒）") List<String> warnings,
      @Schema(description = "🔴 口径说明（页面上常驻显示）") List<String> caliberNotes) {}

  // ================================================================ 归因下钻

  /** 一条归因事实（**带规则快照**）。 */
  public record AffiliateMarketingAttributionRow(
      @Schema(description = "上游归因记录 id") UUID attributionId,
      @Schema(description = "营销位类型") String slotType,
      @Schema(description = "营销位键") String slotKey,
      @Schema(description = "营销位可读标签") String slotLabel,
      @Schema(description = "达人 id") UUID influencerId,
      @Schema(description = "达人名称") String influencerName,
      @Schema(description = "活动 id") UUID campaignId,
      @Schema(description = "活动名称") String campaignName,
      @Schema(description = "合作记录 id") UUID cooperationId,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "🔴 币种（空串 = 上游没给 ⇒ 金额不可比）") String currency,
      @Schema(description = "🔴 归因规则码（规则快照）") String ruleCode,
      @Schema(description = "🔴 规则快照：归因模型") String attributionModel,
      @Schema(description = "🔴 规则快照：分摊权重基准") String poolWeightBasis,
      @Schema(description = "🔴 规则快照：归因窗口（天）") Integer attributionWindowDays,
      @Schema(description = "🔴 规则快照：退款口径") String refundTreatment,
      @Schema(description = "🔴 规则快照：佣金计提口径") String commissionBasis,
      @Schema(description = "🔴 规则快照：佣金比例", nullable = true) BigDecimal commissionRate,
      @Schema(description = "🔴 归因权重（0 ~ 1）", nullable = true) BigDecimal attributionWeight,
      @Schema(description = "🔴 归因成交金额（null = 上游没给币种或没录）", nullable = true)
          BigDecimal attributedGmv,
      @Schema(description = "🔴 归因订单数", nullable = true) BigDecimal attributedOrderCount,
      @Schema(description = "🔴 有效订单数（扣退款后）", nullable = true) BigDecimal effectiveOrderCount,
      @Schema(description = "🔴 佣金成本（null = 上游没给币种或没录）", nullable = true)
          BigDecimal commissionAmount,
      @Schema(description = "🔴 归因发生时刻（**归因发生日口径**，上游不带订单日期）", nullable = true)
          OffsetDateTime attributedAt) {}

  public record AffiliateMarketingAttributionPage(
      @Schema(description = "归因明细列表") List<AffiliateMarketingAttributionRow> items,
      @Schema(description = "当前页（0 基）") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "总条数") long total,
      @Schema(description = "🔴 下钻口径说明") String note) {}

  // ================================================================ 刷新

  public record AffiliateMarketingRefreshResult(
      @Schema(description = "上游来源标识") String source,
      @Schema(description = "拉到的行数") int fetched,
      @Schema(description = "落库的行数") int projected,
      @Schema(description = "状态：SUCCEEDED / TRUNCATED / FAILED") String status,
      @Schema(description = "🔴 说明（截断 / 缺币种 / 不可用的口径都写在这里）") String note) {}
}
