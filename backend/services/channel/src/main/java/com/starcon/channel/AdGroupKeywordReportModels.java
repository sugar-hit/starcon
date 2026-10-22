package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 14.3.1.2 广告组与词报表 的传输模型。
 *
 * <p>蓝图：「广告组与关键词维度报表：单组/单词效果、搜索词质量、否定效果评估，支持批量选中导出。
 * 输入：维度选择（组/词/搜索词）；时间范围。输出：明细报表 + 汇总。
 * 关系：上游 = 14.1.3.1；下游 = 14.1.3.1 否定决策。
 * <b>关键词级数据量极大</b>，查询必须分页与缓存，不能实时全表扫。」
 *
 * <p>🔴 这个模型层钉死本模块最容易做丢的五件事：
 *
 * <ol>
 *   <li><b>组级指标的「没有」是一个被显式声明的事实</b>。{@code AdGroupReportCapabilityDimension}
 *       里 {@code group_metrics} 是 {@code available=false} + {@code ownerModule} + {@code impact}：
 *       现有事实表里<b>没有组级指标表</b>，而且 {@code ad_keyword} / {@code ad_search_term}
 *       <b>根本没有组的引用列</b> ⇒ 词指标在物理上无法归到组。
 *       本模型<b>不</b>给组级指标留数值字段（留一个恒为 null 的字段只会让调用方误以为
 *       「取到了，只是恰好是空」），而是让能力声明把这件事说清楚。
 *       <b>组的结构事实</b>（组名 / 状态 / 默认竞价 / 投放商品数 / 所属活动）是<b>真的</b>，
 *       它们来自 {@code ad_campaign_group} / {@code ad_campaign_group_product}。
 *   <li><b>口径写在返回值里，不写在注释里</b>。{@code metricDefinitions} 逐指标给出
 *       {@code formula}（口径定义）、{@code available}（有没有分母）、{@code unavailableReason}。
 *       界面的表头直接用它渲染 —— 跨部门沟通时「ACOS 到底是哪个除哪个」必须是同一份定义。
 *   <li><b>否定效果评估的口径要自己定并公示</b>。{@code negativeEffectNote} 写清
 *       「否定生效日」怎么算、「否定后仍有数据」怎么判定 ——
 *       规则变了历史报表不可比，这是广告与财务对账最容易吵架的点。
 *   <li><b>汇总是「先汇总再相除」的那一份</b>。{@code acos} / {@code roas} 是
 *       Σ花费 / Σ销售额，<b>不是</b>各行比率的平均 —— 后者是数学错误。
 *   <li><b>汇总按币种拆开</b>。本仓没有汇率源，把不同币种加在一起等于造一个假总数；
 *       所以 {@code summaries} 是一个列表，每个币种一份。
 * </ol>
 *
 * <p>🔴 金额与数量一律 {@link BigDecimal}，**没有 double**：花费 / 销售额直接进财务与归因。
 *
 * <p>🔴 比率的 {@code null} 与 {@code 0} 含义相反：{@code null} = 不可比（分母为 0），
 * {@code 0} = 可比但真的是零（照抄 14.1.1.1 的 {@code AdRatio} 约定）。
 */
public final class AdGroupKeywordReportModels {
  private AdGroupKeywordReportModels() {}

  // ================================================================ 能力声明

  public record AdGroupReportCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  /**
   * 单个指标的口径定义 —— **表头就是从这里渲染的**。
   *
   * <p>🔴 {@code available=false} 的含义是「<b>没有分母</b>」，不是「这一列是 0」。
   */
  public record AdGroupReportMetricDefinition(
      @Schema(description = "指标键") String metric,
      @Schema(description = "指标显示名") String label,
      @Schema(description = "🔴 是否有分母可算（false = 当前不出数值，不是 0）") boolean available,
      @Schema(description = "🔴 口径定义（写进表头的那句话）") String formula,
      @Schema(description = "数据来源") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "🔴 不可用的原因（available=false 时说明为什么没有数值）")
          String unavailableReason) {}

  public record AdGroupKeywordReportCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false")
          List<AdGroupReportCapabilityDimension> dimensions,
      @Schema(description = "🔴 逐指标口径定义（ACOS / ROAS / CPC 的定义写在这里）")
          List<AdGroupReportMetricDefinition> metricDefinitions,
      @Schema(description = "报表维度取值") List<String> dimensions_,
      @Schema(description = "时间粒度取值") List<String> granularities,
      @Schema(description = "🔴 每个维度各自的排序方式（维度不同，白名单也不同）")
          Map<String, List<String>> sortOptionsByDimension,
      @Schema(description = "空态原因取值") List<String> emptyReasons,
      @Schema(description = "🔴 否定效果评估的口径说明（随报表一起返回）") String negativeEffectNote,
      @Schema(description = "🔴 ACOS / ROAS / CPC 口径说明（随报表一起返回）")
          String metricDefinitionNote,
      @Schema(description = "🔴 「数据截至 X 时间」的含义") String dataAsOfNote,
      @Schema(description = "🔴 缓存的失效依据与过期口径") String cacheNote,
      @Schema(description = "上游依赖说明") String upstreamNote,
      @Schema(description = "下游与边界说明") String boundaryNote) {}

  // ================================================================ 选项

  public record AdGroupKeywordReportOption(
      @Schema(description = "取值") String value, @Schema(description = "显示名") String label) {}

  public record AdGroupKeywordReportOptions(
      @Schema(description = "报表维度下拉项") List<AdGroupKeywordReportOption> dimensions,
      @Schema(description = "时间粒度下拉项") List<AdGroupKeywordReportOption> granularities,
      @Schema(description = "平台下拉项") List<AdGroupKeywordReportOption> platforms,
      @Schema(description = "店铺下拉项") List<AdGroupKeywordReportOption> shops,
      @Schema(description = "归一后类型下拉项") List<AdGroupKeywordReportOption> internalAdTypes,
      @Schema(description = "状态下拉项（含义随维度而异，见 note）")
          List<AdGroupKeywordReportOption> statuses,
      @Schema(description = "🔴 每个维度各自的排序方式（维度不同，白名单也不同）")
          Map<String, List<AdGroupKeywordReportOption>> sortsByDimension,
      @Schema(description = "🔴 逐指标口径定义") List<AdGroupReportMetricDefinition> metricDefinitions,
      @Schema(description = "🔴 ACOS / ROAS / CPC 口径说明") String metricDefinitionNote,
      @Schema(description = "🔴 否定效果评估口径说明") String negativeEffectNote,
      @Schema(description = "🔴 时间范围与粒度的上限说明") String rangeLimitNote,
      @Schema(description = "🔴 缓存说明") String cacheNote,
      @Schema(description = "🔴 状态下拉项的含义说明") String statusNote) {}

  // ================================================================ 报表

  /**
   * 报表的一行。
   *
   * <p>🔴 这一行<b>横跨四个维度</b>（组 / 词 / 搜索词 / 否定评估），但**每个维度只填自己
   * 那一段**；不适用的一段全是 null。之所以做成<b>一条</b>记录而不是四条：
   * 缓存载荷、CSV 表头与分页语义在四个维度上是同一套，拆成四条会让「同一份缓存键
   * 到底是哪一种行」变成隐式约定。
   *
   * <p>🔴 比率列的 {@code null} = 不可比（分母为 0），与 {@code 0}（可比但真的是零）含义相反。
   * <p>🔴 组维度的指标列（{@code spend} / {@code acos} / …）<b>全部为 null</b>：
   * 组级指标<b>没有事实源</b>，给 0 会被读成「这个组一分钱没花」—— 那是假事实。
   */
  public record AdGroupKeywordReportRow(
      @Schema(description = "报表维度：GROUP / KEYWORD / SEARCH_TERM / NEGATIVE_EFFECT")
          String dimension,
      @Schema(description = "维度主键（组 id / 词 id / 搜索词原文 / 否定记录 id）")
          String dimensionKey,
      @Schema(description = "维度显示名") String dimensionLabel,
      @Schema(description = "🔴 币种（本仓无汇率源，不同币种不合并）") String currency,
      @Schema(description = "平台名") String platformName,
      @Schema(description = "店铺名") String shopName,
      @Schema(description = "平台侧活动 id") String externalCampaignId,
      @Schema(description = "曝光合计", type = "string") BigDecimal impressions,
      @Schema(description = "点击合计", type = "string") BigDecimal clicks,
      @Schema(description = "转化合计", type = "string") BigDecimal conversions,
      @Schema(description = "花费合计", type = "string") BigDecimal spend,
      @Schema(description = "销售额合计", type = "string") BigDecimal sales,
      @Schema(description = "🔴 CTR = Σ点击 / Σ曝光（分母为 0 时为 null=不可比）", type = "string")
          BigDecimal ctr,
      @Schema(description = "🔴 CPC = Σ花费 / Σ点击（分母为 0 时为 null=不可比）", type = "string")
          BigDecimal cpc,
      @Schema(description = "🔴 转化率 = Σ转化 / Σ点击（分母为 0 时为 null=不可比）", type = "string")
          BigDecimal cvr,
      @Schema(description = "🔴 ACOS = Σ花费 / Σ销售额（分母为 0 时为 null=不可比）", type = "string")
          BigDecimal acos,
      @Schema(description = "🔴 ROAS = Σ销售额 / Σ花费（分母为 0 时为 null=不可比）", type = "string")
          BigDecimal roas,
      @Schema(description = "🔴 有数据的天数（同一 (keyword_id, bucket_date) 唯一 ⇒ 不会重复计数）")
          Long activeDays,
      @Schema(description = "🔴 时间桶起点（TOTAL 粒度为 null）") LocalDate periodStart,
      @Schema(description = "🔴 时间桶终点（TOTAL 粒度为 null）") LocalDate periodEnd,
      @Schema(description = "该行实际统计到的最早日期") LocalDate metricFrom,
      @Schema(description = "该行实际统计到的最晚日期") LocalDate metricTo,
      // ---------- 组维度（结构事实）----------
      @Schema(description = "组状态（GROUP 维度）") String groupStatus,
      @Schema(description = "平台侧组 id（GROUP 维度）") String externalGroupId,
      @Schema(description = "所属活动名（GROUP 维度）") String campaignName,
      @Schema(description = "所属活动状态（GROUP 维度）") String campaignStatus,
      @Schema(description = "组默认竞价（GROUP 维度；null = 平台侧没给）", type = "string")
          BigDecimal defaultBid,
      @Schema(description = "组日预算（GROUP 维度；null = 继承活动预算）", type = "string")
          BigDecimal dailyBudget,
      @Schema(description = "竞价币种（GROUP 维度）") String bidCurrency,
      @Schema(description = "定向类型（平台原文，GROUP 维度）") String targetingType,
      @Schema(description = "该组投放的商品数（GROUP 维度）") Long productCount,
      // ---------- 词维度 ----------
      @Schema(description = "关键词原文（KEYWORD 维度）") String keywordText,
      @Schema(description = "平台侧关键词 id（KEYWORD 维度）") String externalKeywordId,
      @Schema(description = "归一后广告类型（KEYWORD 维度）") String internalAdType,
      @Schema(description = "🔴 原始平台类型标记（与归一类型并存，KEYWORD 维度）")
          String platformAdType,
      @Schema(description = "词状态（KEYWORD 维度）") String keywordStatus,
      @Schema(description = "词当前竞价（KEYWORD 维度）", type = "string") BigDecimal bid,
      // ---------- 搜索词维度 ----------
      @Schema(description = "搜索词原文（SEARCH_TERM 维度）") String searchTerm,
      @Schema(description = "该搜索词出现在多少个投放词下（SEARCH_TERM 维度）")
          Long sourceKeywordCount,
      @Schema(description = "该搜索词出现在多少个活动下（SEARCH_TERM 维度）")
          Long sourceCampaignCount,
      // ---------- 否定评估维度 ----------
      @Schema(description = "被否定的词原文（NEGATIVE_EFFECT 维度）") String negationText,
      @Schema(description = "否定类型：NEGATIVE_EXACT / NEGATIVE_PHRASE")
          String negationType,
      @Schema(description = "否定状态：DECIDED / PUSHED / REJECTED") String negationStatus,
      @Schema(description = "🔴 否定生效日（applied_at 的 UTC 日期；空 ⇒ 无法评估）")
          LocalDate effectiveDate,
      @Schema(description = "依据区间起（UTC 日）") LocalDate evidenceFrom,
      @Schema(description = "依据区间止（UTC 日）") LocalDate evidenceTo,
      @Schema(description = "平台回执时间") OffsetDateTime appliedAt,
      @Schema(
              description =
                  "🔴 评估结论：NO_DATA_AFTER / STILL_REPORTING / ZERO_SPEND_AFTER / NOT_EVALUABLE")
          String evaluationOutcome,
      @Schema(description = "🔴 评估结论的说明（写清为什么）") String evaluationNote,
      @Schema(description = "否定前曝光（生效日及之前）", type = "string") BigDecimal preImpressions,
      @Schema(description = "否定前点击（生效日及之前）", type = "string") BigDecimal preClicks,
      @Schema(description = "否定前转化（生效日及之前）", type = "string") BigDecimal preConversions,
      @Schema(description = "否定前花费（生效日及之前）", type = "string") BigDecimal preSpend,
      @Schema(description = "否定前销售额（生效日及之前）", type = "string") BigDecimal preSales,
      @Schema(description = "否定后曝光（生效日之后）", type = "string") BigDecimal postImpressions,
      @Schema(description = "否定后点击（生效日之后）", type = "string") BigDecimal postClicks,
      @Schema(description = "否定后转化（生效日之后）", type = "string") BigDecimal postConversions,
      @Schema(description = "否定后花费（生效日之后）", type = "string") BigDecimal postSpend,
      @Schema(description = "否定后销售额（生效日之后）", type = "string") BigDecimal postSales,
      @Schema(
              description =
                  "🔴 否定后是否仍有数据（生效日之后仍有花费或点击）；null = 无法评估")
          Boolean postStillReporting,
      // ---------- 质量标记 ----------
      @Schema(description = "🔴 是否「高曝光零转化」（有曝光且零转化）—— 浪费的入口")
          Boolean zeroConversionWaste,
      @Schema(description = "🔴 数据截至时刻（该行数据截至什么时候）") OffsetDateTime dataAsOf,
      @Schema(description = "🔴 该行的补充说明") String note) {}

  /**
   * 汇总（**不受分页影响**：统计的是筛选后的全集，不是当前页）。
   *
   * <p>🔴 按币种拆开：没有汇率源 ⇒ 不同币种的花费 / 销售额加在一起就是假总数。
   * <p>🔴 ACOS / ROAS 是「先汇总再相除」：总花费 / 总销售额，
   * <b>不是各行比率的平均</b> —— 后者是数学错误。
   * <p>🔴 GROUP 维度是结构事实，金额列全为 null（组级指标没有事实源，见能力声明）。
   */
  public record AdGroupKeywordReportSummary(
      @Schema(description = "币种（GROUP 维度为空串）") String currency,
      @Schema(description = "该币种下的分组行数") long rowCount,
      @Schema(description = "实体数（组数 / 词数 / 搜索词数 / 否定记录数）") long entityCount,
      @Schema(description = "曝光合计", type = "string") BigDecimal totalImpressions,
      @Schema(description = "点击合计", type = "string") BigDecimal totalClicks,
      @Schema(description = "转化合计", type = "string") BigDecimal totalConversions,
      @Schema(description = "花费合计", type = "string") BigDecimal totalSpend,
      @Schema(description = "销售额合计", type = "string") BigDecimal totalSales,
      @Schema(description = "🔴 CTR = Σ点击 / Σ曝光（分母为 0 时为 null=不可比）", type = "string")
          BigDecimal ctr,
      @Schema(description = "🔴 CPC = Σ花费 / Σ点击（分母为 0 时为 null=不可比）", type = "string")
          BigDecimal cpc,
      @Schema(description = "🔴 转化率 = Σ转化 / Σ点击（分母为 0 时为 null=不可比）", type = "string")
          BigDecimal cvr,
      @Schema(description = "🔴 ACOS = Σ花费 / Σ销售额（分母为 0 时为 null=不可比）", type = "string")
          BigDecimal acos,
      @Schema(description = "🔴 ROAS = Σ销售额 / Σ花费（分母为 0 时为 null=不可比）", type = "string")
          BigDecimal roas,
      @Schema(description = "实际统计到的最早日期") LocalDate metricFrom,
      @Schema(description = "实际统计到的最晚日期") LocalDate metricTo,
      @Schema(description = "🔴 口径说明（ACOS / ROAS / CPC 的分子分母）") String metricDefinitionNote) {}

  /**
   * 缓存信息 —— 🔴 「缓存了但不知道什么时候过期」是最不可接受的一种结果，
   * 所以命中状态、失效依据、过期时刻**一起**返回。
   */
  public record AdGroupKeywordReportCacheInfo(
      @Schema(description = "🔴 缓存状态：HIT / MISS / STALE / BYPASS") String status,
      @Schema(description = "缓存键（查询指纹）") String cacheKey,
      @Schema(description = "🔴 失效依据 1：算这份结果时的数据截至时刻") OffsetDateTime dataAsOf,
      @Schema(description = "🔴 失效依据 2：水位那一行来自哪一次同步批次") String sourceBatchKey,
      @Schema(description = "🔴 失效依据 3：失效令牌（数据变了就变）") String sourceWatermark,
      @Schema(description = "🔴 明确的过期时刻") OffsetDateTime expiresAt,
      @Schema(description = "🔴 缓存口径说明") String note) {}

  public record AdGroupKeywordReportPage(
      @Schema(description = "当前页数据") List<AdGroupKeywordReportRow> items,
      @Schema(description = "符合筛选条件的分组总条数（不受分页影响）") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "报表维度") String dimension,
      @Schema(description = "时间粒度：TOTAL / DAY / WEEK / MONTH") String granularity,
      @Schema(description = "🔴 筛选后全集的汇总（按币种拆开，不受分页影响）")
          List<AdGroupKeywordReportSummary> summaries,
      @Schema(description = "🔴 数据截至时刻（该维度的水位）") OffsetDateTime dataAsOf,
      @Schema(description = "🔴 「数据截至 X 时间」的口径说明") String dataAsOfNote,
      @Schema(description = "🔴 逐指标口径定义（表头渲染用）")
          List<AdGroupReportMetricDefinition> metricDefinitions,
      @Schema(description = "🔴 ACOS / ROAS / CPC 口径说明") String metricDefinitionNote,
      @Schema(description = "🔴 否定效果评估口径说明") String negativeEffectNote,
      @Schema(description = "🔴 空态原因：NO_SYNCED_DATA / NO_METRICS_IN_RANGE / FILTERED_EMPTY / null")
          String emptyReason,
      @Schema(description = "🔴 空态说明文案（三种空的解释各不相同）") String emptyNote,
      @Schema(description = "🔴 缓存信息（命中状态 + 失效依据 + 过期时刻）")
          AdGroupKeywordReportCacheInfo cache,
      @Schema(description = "🔴 本维度的补充说明") String note) {}

  // ================================================================ 导出

  /** 批量选中导出的请求体。 */
  public record AdGroupKeywordReportExportRequest(
      @Schema(description = "报表维度：GROUP / KEYWORD / SEARCH_TERM / NEGATIVE_EFFECT")
          String dimension,
      @Schema(description = "时间粒度：TOTAL / DAY / WEEK / MONTH") String granularity,
      @Schema(description = "排序方式") String sort,
      @Schema(description = "平台 id 筛选（null = 没筛）") UUID platformId,
      @Schema(description = "店铺筛选（null = 没筛）") String shopCode,
      @Schema(description = "归一后类型筛选（null = 没筛）") String internalAdType,
      @Schema(description = "状态筛选（null = 没筛；含义随维度而异）") String status,
      @Schema(description = "外部活动 id 筛选（null = 没筛）") String externalCampaignId,
      @Schema(description = "词文本筛选（null = 没筛）") String keywordText,
      @Schema(description = "统计起始日") LocalDate dateFrom,
      @Schema(description = "统计截止日") LocalDate dateTo,
      @Schema(
              description =
                  "🔴 批量选中的维度主键（空数组 = 导出当前筛选的全部；维度主键的含义随维度而异）")
          List<String> selectedKeys) {}

  public record AdGroupKeywordReportExportRow(
      @Schema(description = "导出留痕 id") UUID id,
      @Schema(description = "发起人（已认证成员）") UUID requestedBy,
      @Schema(description = "报表维度") String dimension,
      @Schema(description = "导出时用的时间粒度") String granularity,
      @Schema(description = "平台 id 筛选（null = 没筛）") UUID platformId,
      @Schema(description = "店铺筛选（null = 没筛）") String shopCode,
      @Schema(description = "归一后类型筛选（null = 没筛）") String internalAdType,
      @Schema(description = "状态筛选（null = 没筛）") String status,
      @Schema(description = "外部活动 id 筛选（null = 没筛）") String externalCampaignId,
      @Schema(description = "词文本筛选（null = 没筛）") String keywordText,
      @Schema(description = "统计起始日") LocalDate dateFrom,
      @Schema(description = "统计截止日") LocalDate dateTo,
      @Schema(description = "导出的分组行数") int rowCount,
      @Schema(description = "🔴 批量选中的维度主键（空 = 当前筛选的全部）") List<String> selection,
      @Schema(description = "🔴 这次导出对着哪个水位算出来的") OffsetDateTime dataAsOf,
      @Schema(description = "🔴 导出当时的口径定义") String metricDefinitionNote,
      @Schema(description = "发生时刻") OffsetDateTime createdAt) {}

  public record AdGroupKeywordReportExportPage(
      @Schema(description = "当前页数据") List<AdGroupKeywordReportExportRow> items,
      @Schema(description = "总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}
}
