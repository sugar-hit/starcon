package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 14.1.3.1 关键词管理 的传输模型。
 *
 * <p>蓝图：「关键词管理与分析：搜索词报告、关键词表现（展示、点击、转化、CPC、ACOS）、否定词建议、词频与排名、批量调价与否定。输入：搜索词与关键词数据同步；批量操作参数。输出：关键词报表 + 否定建议 + 批量调整任务。关系：上游 = 平台搜索词报告；下游 = 14.2.1.1 竞价、4.5.1.2 排名。<b>搜索词报告是发现浪费的入口</b>：高曝光零转化词应尽快否定，否则预算持续流失。」
 *
 * <p>🔴 模型层把六个不变量钉死（每个都有集成测试逐条钉住）：
 *
 * <ol>
 *   <li><b>🔴🔴 归一类型与原始类型并存</b>：{@code internalAdType}（归一后内部类型）与
 *       {@code platformAdType}（原始平台类型标记）**同时**出现在每一个返回行里。
 *       {@code UNMAPPED} 是**显式合法值**（2.1.2.1 两层都没查到），不是兜底 ——
 *       「没有可比口径」与「没有这种广告」是相反的两件事。
 *   <li><b>🔴🔴 ACOS 的分母口径写死</b>：ACOS = 花费 / 销售额；**销售额为 0 时是 null**，
 *       不是 0、不是异常。{@code acos} / {@code cpc} / {@code ctr} / {@code cvr} 全部同此纪律
 *       （null = 分母为 0，不可比；0 = 可比但真的是零）。
 *   <li><b>🔴🔴 数据截至时间是契约的一部分</b>：凡返回指标的响应都带 {@code dataAsOf}
 *       （这批数据截至何时）与 {@code syncStatus}（{@code NEVER_SYNCED} / {@code SYNCED}）——
 *       「没跑这个词」与「还没同步到数据」必须分得开。
 *   <li><b>🔴🔴 否定必须带依据</b>：{@code evidence} 里是**做决定那一刻的真实数值**
 *       （曝光 / 点击 / 转化 / 花费 / 销售额 / ACOS / 截至时间），不是一个词表。
 *   <li><b>🔴 排名引用 4.5.1.2，不造第二套</b>：{@code naturalRank} / {@code adRank} 读的是
 *       4.5.1.2 的 {@code listing_keyword_rank_snapshot}（同一张表、同一套 null 语义）；
 *       {@code frequency}（词频）是同一个词被多少个商品监控着。
 *   <li><b>🔴 金额与计数不混</b>：金额一律 {@link BigDecimal}（numeric(19,4)），
 *       计数（曝光 / 点击 / 转化）一律**整数**（long / int），**没有浮点**。
 * </ol>
 *
 * <p>载荷一律用具名 {@code record}：Map 的值类型会被 springdoc 推成 object，codegen 出来是空对象
 * {@code {}}（9.6.1.3 已踩过）。
 */
public final class AdKeywordModels {
  private AdKeywordModels() {}

  // ================================================================ 口径与常量

  /**
   * 🔴 指标口径说明（前端与报表都显示，导出的报表里也得带着）。
   *
   * <p>蓝图 14.3.1.1 提醒过「ACOS / ROAS / TACOS 三个口径要在表头写明定义」，
   * 否则跨部门沟通时各说各话。本模块只产 ACOS 一个口径，写清它。
   */
  public static final String METRIC_DEFINITIONS =
      "🔴 **ACOS = 花费 / 销售额**（分母是**广告带来的销售额**，不是总销售额，也不是订单总额）。"
          + "🔴 销售额为 0 时 ACOS 是 **null（不可比）**，不是 0 —— 「可比但真的是零」与「压根算不出来」"
          + "是两件完全不同的事，写 0 会被下游读成「效率完美」。"
          + "🔴 **CPC = 花费 / 点击**（点击为 0 时 null）；**CTR = 点击 / 展示**（展示为 0 时 null）；"
          + "**CVR = 转化 / 点击**（点击为 0 时 null）。"
          + "🔴 展示 / 点击 / 转化是**计数**（整数），花费 / 销售额是**金额**（numeric(19,4)）—— "
          + "本模块**没有浮点**。跨币种**不可相加**：每行带自己的 currency，比率只在同币种内成立。"
          + "🔴 前端原样显示，不要自己重算。";

  /**
   * 🔴 词频与排名的口径说明。
   *
   * <p>口径**引用 4.5.1.2**，不另起一套：名次读的是 4.5.1.2 的
   * {@code listing_keyword_rank_snapshot}（自然排名 / 广告排名），
   * 词频是「同一个词被多少个商品监控着」（4.5.1.2 的关键词集合 {@code listing_keyword}）。
   */
  public static final String FREQUENCY_DEFINITIONS =
      "🔴 **名次口径与 4.5.1.2 完全一致**：naturalRank / adRank 是**名次**（第几名，数字越小越好），"
          + "读的是 4.5.1.2 的 ranking 快照；平台没报这个维度时是 **null，不是 0**。"
          + "🔴 **词频 = 同一个词被多少个商品监控着**（4.5.1.2 的关键词集合里同平台同词的条目数）。"
          + "🔴 排名依赖抓取频率与平台限制：名次带抓取时间与可能的不准确窗口"
          + "（rankingCrawledAt / rankingAccuracyWindowMinutes），超过窗口后该名次不再代表当前排名。"
          + "🔴 没关联到 4.5.1.2 关键词集合的投放关键词（listingKeywordId 为空）名次就是 null，"
          + "不是 0 —— 「没关联」与「排名第 0」是两件事。";

  /**
   * 🔴 否定依据的口径说明。
   *
   * <p>蓝图加粗句「否定要能追溯到依据」的落地：建议行与否定记录都带**实际数值**。
   */
  public static final String NEGATIVE_EVIDENCE_NOTE =
      "🔴 每条否定建议与每条否定记录都带**依据数值**（曝光 / 点击 / 转化 / 花费 / 销售额 / ACOS / "
          + "数据截至时间），不是一个光秃秃的词表 —— 运营要能回答「凭什么否定它」。"
          + "🔴 建议的判定规则有三条，都写在服务端（前端不自己判）："
          + "① **高曝光零转化**：曝光 ≥ minImpressions 且转化 = 0；"
          + "② **花了钱没转化**：点击 ≥ minClicks 且转化 = 0 且花费 > 0；"
          + "③ **ACOS 超标**：给了 targetAcos 时，ACOS > targetAcos 且点击 ≥ minClicks。"
          + "🔴 采纳建议时**依据会被快照进否定记录**（evidence 字段）—— "
          + "事后平台数据变了，也改不了当时为什么否定。";

  /**
   * 🔴 批量调价与否定的护栏说明（前端必须显示在批量操作区顶部）。
   *
   * <p>蓝图 14.1.1.3 的加粗句「批量调价要有单次上限与总消耗上限保护，误操作一次可能烧掉整月预算」
   * 在本模块同样成立。
   */
  public static final String GUARDRAIL_NOTE =
      "🔴 批量调价有四道护栏，**整批越线直接拒**（不是部分执行）："
          + "① **单次条目上限** 200 条；"
          + "② **单条调整幅度上限** ±50%（按当前竞价）；"
          + "③ **单条竞价绝对上限** 1000.00；"
          + "④ **单次总上调上限** 5000.00（按本次声明的币种计价，只管上调、不限下调 —— "
          + "下调不烧钱，上调会）。"
          + "🔴 **币种必须一致**：请求里声明的币种与关键词的竞价的币种不一致的条目**逐条失败**并写明原因，"
          + "不会按同一个数把不同币种一起调。"
          + "🔴 逐条返回执行结果：成了 / 没成 / 为什么没成。整批被拒时逐条标**跳过**并写清是哪条上限。";

  // ================================================================ 能力声明

  public record AdKeywordCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  public record AdKeywordCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false")
          List<AdKeywordCapabilityDimension> dimensions,
      @Schema(description = "关键词状态") List<String> keywordStatuses,
      @Schema(description = "否定类型") List<String> negationTypes,
      @Schema(description = "否定状态") List<String> negationStatuses,
      @Schema(description = "批量动作类型") List<String> bulkActionTypes,
      @Schema(description = "批量任务状态") List<String> bulkActionStatuses,
      @Schema(description = "数据来源（指标与搜索词）") List<String> metricSources,
      @Schema(description = "🔴 指标口径说明（ACOS 分母 / null 的含义）") String metricDefinitions,
      @Schema(description = "🔴 词频与排名的口径说明（引用 4.5.1.2）") String frequencyDefinitions,
      @Schema(description = "🔴 否定依据的口径说明") String negativeEvidenceNote,
      @Schema(description = "🔴 批量调价的护栏说明") String guardrailNote,
      @Schema(description = "🔴 护栏的具体数值（前端按这个渲染提示）") Map<String, Object> guardrailLimits,
      @Schema(description = "上游 / 下游说明") String relationNote) {}

  // ================================================================ 筛选词表

  public record AdKeywordFilterOption(
      @Schema(description = "编码") String code,
      @Schema(description = "名称") String name,
      @Schema(description = "平台编码（站点码只在平台内唯一，平台才是父级）") String platformCode) {}

  public record AdKeywordFilterOptions(
      @Schema(description = "平台列表") List<AdKeywordFilterOption> platforms,
      @Schema(description = "店铺列表") List<AdKeywordFilterOption> shops,
      @Schema(description = "外部活动 id 列表（14.1.1.1 未交付 ⇒ 用外部引用表达）")
          List<AdKeywordFilterOption> campaigns,
      @Schema(description = "归一后内部类型列表（UNMAPPED 是显式值，不是兜底）")
          List<AdKeywordFilterOption> adTypes,
      @Schema(description = "币种列表") List<AdKeywordFilterOption> currencies) {}

  // ================================================================ 关键词

  /**
   * 新建 / 改一个投放关键词。
   *
   * <p>🔴 归一类型**不在这份输入的必填里**：{@code internalAdType} 可以不填（服务端按 14.1.2.2
   * 的两层解析带出），填了则**必须与解析结果一致**，不一致直接拒 —— 归一口径的真相在 2.1.2.1，
   * 不在请求里（照抄 14.1.2.2 登记档案的同一纪律）。
   *
   * <p>🔴 竞价与竞价币种**成对出现**：给了竞价就必须给币种（否则批量调价无从计价）。
   */
  public record AdKeywordInput(
      @Schema(description = "平台 id（2.1.1.1 平台目录）") @NotNull UUID platformId,
      @Schema(description = "店铺 id（2.3.1.1 店铺）") @NotNull UUID shopId,
      @Schema(description = "🔴 外部活动 id（14.1.1.1 未交付 ⇒ 只存外部引用，不跨车道建 FK）")
          @NotBlank @Size(max = 128) String externalCampaignId,
      @Schema(description = "🔴 外部关键词 id（平台侧投放对象 id）") @NotBlank @Size(max = 128)
          String externalKeywordId,
      @Schema(description = "🔴 关键词原文（照抄平台侧的词，不做大小写折叠）") @NotBlank @Size(max = 200)
          String keyword,
      @Schema(description = "🔴 原始平台类型标记（原样存，不折叠大小写与别名）") @NotBlank @Size(max = 64)
          String platformAdType,
      @Schema(description = "🔴 归一后内部类型（不给 = 由 14.1.2.2 解析带出；给了必须与解析结果一致）")
          @Size(max = 64) String internalAdType,
      @Schema(description = "🔴 关键词当前竞价（不给 = 0）") @DecimalMin("0.0") BigDecimal bid,
      @Schema(description = "🔴 竞价币种（给了竞价就必须给币种）") @Size(max = 8) String bidCurrency,
      @Schema(description = "状态：ACTIVE / PAUSED / ARCHIVED（不给 = ACTIVE）") @Size(max = 16)
          String status,
      @Schema(description = "🔴 关联到 4.5.1.2 的关键词集合（可空；名次口径直接引用它）") UUID listingKeywordId,
      @Schema(description = "备注（进留痕）") @Size(max = 500) String note) {}

  /**
   * 一条投放关键词（列表行 / 明细共用）。
   *
   * <p>🔴 {@code internalAdType} 与 {@code platformAdType} **同时返回**；{@code dataAsOf} 与
   * {@code syncStatus} 让「没跑这个词」与「还没同步到数据」分得开。
   */
  public record AdKeywordRow(
      @Schema(description = "关键词 id") UUID keywordId,
      @Schema(description = "平台 id") UUID platformId,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "店铺 id") UUID shopId,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "🔴 外部活动 id（外部引用，不跨车道建 FK）") String externalCampaignId,
      @Schema(description = "🔴 外部关键词 id") String externalKeywordId,
      @Schema(description = "🔴 关键词原文") String keyword,
      @Schema(description = "🔴 归一后内部类型（UNMAPPED 是显式值，不是兜底）") String internalAdType,
      @Schema(description = "🔴 原始平台类型标记（与归一类型并存）") String platformAdType,
      @Schema(description = "🔴 关键词当前竞价（14.2.1.1 未交付 ⇒ 本模块只提供读取形状与批量调价动作）")
          BigDecimal bid,
      @Schema(description = "🔴 竞价币种") String bidCurrency,
      @Schema(description = "状态：ACTIVE / PAUSED / ARCHIVED") String status,
      @Schema(description = "🔴 关联到的 4.5.1.2 关键词 id（可空）") UUID listingKeywordId,
      @Schema(description = "备注") String note,
      @Schema(description = "展示次数（计数）") long impressions,
      @Schema(description = "点击次数（计数）") long clicks,
      @Schema(description = "转化次数（计数）") int conversions,
      @Schema(description = "花费（金额 numeric(19,4)）") BigDecimal spend,
      @Schema(description = "销售额（金额 numeric(19,4)）") BigDecimal sales,
      @Schema(description = "指标币种（区间内无数据时为空串）") String currency,
      @Schema(description = "🔴 ACOS = 花费/销售额；销售额为 0 时为 null（不可比），不是 0")
          BigDecimal acos,
      @Schema(description = "🔴 CPC = 花费/点击；点击为 0 时为 null") BigDecimal cpc,
      @Schema(description = "🔴 CTR = 点击/展示；展示为 0 时为 null") BigDecimal ctr,
      @Schema(description = "🔴 CVR = 转化/点击；点击为 0 时为 null") BigDecimal cvr,
      @Schema(description = "区间内有指标的天数") int activeDays,
      @Schema(description = "🔴 该关键词的指标数据截至时间（区间内无数据时为 null）") OffsetDateTime metricAsOf,
      @Schema(description = "🔴 该关键词是否已同步过任何指标（false = 还没跑这个词）") boolean hasMetrics,
      @Schema(description = "🔴 自然排名（4.5.1.2 口径；null = 平台没报或没关联，不是 0）")
          Integer naturalRank,
      @Schema(description = "🔴 广告排名（4.5.1.2 口径；null = 平台没报或没关联，不是 0）") Integer adRank,
      @Schema(description = "🔴 名次的抓取时间（4.5.1.2 口径）") OffsetDateTime rankingCrawledAt,
      @Schema(description = "🔴 名次可能的不准确窗口（分钟，4.5.1.2 口径）")
          Integer rankingAccuracyWindowMinutes,
      @Schema(description = "🔴 词频：同一个词被多少个商品监控着（4.5.1.2 的关键词集合）") long frequency,
      @Schema(description = "🔴 词频里覆盖的商品数") long frequencyListingCount,
      @Schema(description = "🔴 是否已被否定（已存在否定记录）") boolean negated,
      @Schema(description = "🔴 最近的否定记录 id（可空）") UUID latestNegativeId,
      @Schema(description = "创建时间") OffsetDateTime createdAt,
      @Schema(description = "更新时间") OffsetDateTime updatedAt) {}

  /** 一页关键词（真分页）。 */
  public record AdKeywordPage(
      @Schema(description = "本页数据") List<AdKeywordRow> items,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "符合条件的总条数") long total,
      @Schema(description = "🔴 本租户指标数据的截至时间（null = 从未同步过）") OffsetDateTime dataAsOf,
      @Schema(description = "🔴 同步状态：NEVER_SYNCED / SYNCED") String syncStatus,
      @Schema(description = "🔴 同步状态说明（区分「没跑这个词」与「还没同步到数据」）")
          String syncNote) {}

  /**
   * 单个关键词的明细（含搜索词浪费摘要与否定历史）。
   *
   * <p>🔴 {@code hasMetrics=false} 时所有指标都是 0，{@code emptyReason} 写明是
   * 「还没同步过任何指标」还是「同步过但这个区间内没有数据」—— 那是两回事。
   */
  public record AdKeywordDetail(
      @Schema(description = "关键词 id") UUID keywordId,
      @Schema(description = "平台 id") UUID platformId,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "店铺 id") UUID shopId,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "🔴 外部活动 id") String externalCampaignId,
      @Schema(description = "🔴 外部关键词 id") String externalKeywordId,
      @Schema(description = "🔴 关键词原文") String keyword,
      @Schema(description = "🔴 归一后内部类型") String internalAdType,
      @Schema(description = "🔴 原始平台类型标记") String platformAdType,
      @Schema(description = "🔴 关键词当前竞价") BigDecimal bid,
      @Schema(description = "🔴 竞价币种") String bidCurrency,
      @Schema(description = "状态") String status,
      @Schema(description = "🔴 关联到的 4.5.1.2 关键词 id") UUID listingKeywordId,
      @Schema(description = "备注") String note,
      @Schema(description = "展示次数（计数）") long impressions,
      @Schema(description = "点击次数（计数）") long clicks,
      @Schema(description = "转化次数（计数）") int conversions,
      @Schema(description = "花费（金额 numeric(19,4)）") BigDecimal spend,
      @Schema(description = "销售额（金额 numeric(19,4)）") BigDecimal sales,
      @Schema(description = "指标币种") String currency,
      @Schema(description = "🔴 ACOS（销售额为 0 时为 null）") BigDecimal acos,
      @Schema(description = "🔴 CPC（点击为 0 时为 null）") BigDecimal cpc,
      @Schema(description = "🔴 CTR（展示为 0 时为 null）") BigDecimal ctr,
      @Schema(description = "🔴 CVR（点击为 0 时为 null）") BigDecimal cvr,
      @Schema(description = "区间内有指标的天数") int activeDays,
      @Schema(description = "🔴 指标数据截至时间") OffsetDateTime metricAsOf,
      @Schema(description = "🔴 是否已同步过任何指标") boolean hasMetrics,
      @Schema(description = "🔴 自然排名（4.5.1.2 口径）") Integer naturalRank,
      @Schema(description = "🔴 广告排名（4.5.1.2 口径）") Integer adRank,
      @Schema(description = "🔴 名次的抓取时间（4.5.1.2 口径）") OffsetDateTime rankingCrawledAt,
      @Schema(description = "🔴 名次可能的不准确窗口（分钟）") Integer rankingAccuracyWindowMinutes,
      @Schema(description = "🔴 词频：同一个词被多少个商品监控着") long frequency,
      @Schema(description = "🔴 词频里覆盖的商品数") long frequencyListingCount,
      @Schema(description = "🔴 该关键词下的搜索词条数（发现浪费的入口）") long searchTermCount,
      @Schema(description = "🔴 其中高曝光零转化的搜索词条数（浪费）") long wastedSearchTermCount,
      @Schema(description = "🔴 否定记录条数") long negativeCount,
      @Schema(description = "🔴 是否已有指标数据") boolean hasData,
      @Schema(description = "空态原因（没有数据时写明）") String emptyReason,
      @Schema(description = "🔴 本租户指标数据截至时间") OffsetDateTime dataAsOf,
      @Schema(description = "🔴 同步状态：NEVER_SYNCED / SYNCED") String syncStatus,
      @Schema(description = "🔴 同步状态说明") String syncNote) {}

  // ================================================================ 关键词表现同步（写入口）

  /**
   * 🔴 一次关键词表现同步里的一行。
   *
   * <p>🔴 {@code syncedAt}（数据截至时间）**必填** —— 没有它，报表上的数看不出是什么时候的。
   */
  public record AdKeywordMetricInput(
      @Schema(description = "关键词 id（必须存在且属于本租户）") @NotNull UUID keywordId,
      @Schema(description = "指标日（UTC 日，yyyy-MM-dd）") @NotNull LocalDate bucketDate,
      @Schema(description = "展示次数（计数）") @PositiveOrZero Long impressions,
      @Schema(description = "点击次数（计数）") @PositiveOrZero Long clicks,
      @Schema(description = "转化次数（计数）") @PositiveOrZero Integer conversions,
      @Schema(description = "花费（金额 numeric(19,4)）") @DecimalMin("0.0") BigDecimal spend,
      @Schema(description = "销售额（金额 numeric(19,4)）") @DecimalMin("0.0") BigDecimal sales,
      @Schema(description = "🔴 指标币种（必须与关键词竞价的币种一致，否则 DB 拒）") @NotBlank @Size(max = 8)
          String currency,
      @Schema(description = "🔴 数据截至时间（这批指标是截至什么时候的）") @NotNull OffsetDateTime syncedAt) {}

  /**
   * 🔴 接收一批关键词表现（**真实写入口**）。
   *
   * <p>🔴 {@code idempotencyKey} **必填**：重投同一批不重复累加（指标重复落两遍会让
   * ACOS 与 CPC 变成两倍）。同一个 key 再次投递 ⇒ 整批不再落库，返回既有批次并把
   * {@code replayed} 置 true。
   */
  public record AdKeywordMetricBatchRequest(
      @Schema(description = "来源：PLATFORM / API / IMPORT（不给 = PLATFORM）") @Size(max = 16)
          String source,
      @Schema(description = "来源引用（平台侧批次 id 之类）") @Size(max = 160) String sourceRef,
      @Schema(description = "🔴 幂等键（租户内唯一；重复投递整批不再落库）") @NotBlank @Size(max = 160)
          String idempotencyKey,
      @Schema(description = "备注（进留痕）") @Size(max = 500) String note,
      @Schema(description = "本批的指标行") @NotEmpty @Size(max = 2000)
          List<@Valid AdKeywordMetricInput> metrics) {}

  /**
   * 🔴 一次同步的**回执**：逐条写明真的落了什么、什么没落。
   *
   * <p>🔴 {@code replayed=true} 表示这个批次**之前已经落过**，本次**没有**再写任何指标 ——
   * 页面必须显示出来，否则运营会以为平台又同步了一遍。
   */
  public record AdKeywordMetricBatchResult(
      @Schema(description = "🔴 是否为重复投递（本次未落库）") boolean replayed,
      @Schema(description = "幂等键") String idempotencyKey,
      @Schema(description = "本批收到的行数") int receivedCount,
      @Schema(description = "实际落库的条数") int appliedCount,
      @Schema(description = "被跳过的条数") int skippedCount,
      @Schema(description = "🔴 逐条跳过原因（不静默丢弃）") List<String> skipped,
      @Schema(description = "🔴 数据截至时间（本批最新的）") OffsetDateTime dataAsOf) {}

  // ================================================================ 搜索词报告

  /**
   * 🔴 一次搜索词报告同步里的一行。
   *
   * <p>🔴 与关键词表现一样带 {@code syncedAt}（数据截至时间）。
   */
  public record AdSearchTermInput(
      @Schema(description = "关键词 id（这条搜索词是在哪个投放词下跑出来的）") @NotNull UUID keywordId,
      @Schema(description = "指标日（UTC 日，yyyy-MM-dd）") @NotNull LocalDate bucketDate,
      @Schema(description = "🔴 搜索词原文（用户实际搜出来的词）") @NotBlank @Size(max = 200)
          String searchTerm,
      @Schema(description = "🔴 外部活动 id（冗余一份外部引用）") @NotBlank @Size(max = 128)
          String externalCampaignId,
      @Schema(description = "展示次数（计数）") @PositiveOrZero Long impressions,
      @Schema(description = "点击次数（计数）") @PositiveOrZero Long clicks,
      @Schema(description = "转化次数（计数）") @PositiveOrZero Integer conversions,
      @Schema(description = "花费（金额 numeric(19,4)）") @DecimalMin("0.0") BigDecimal spend,
      @Schema(description = "销售额（金额 numeric(19,4)）") @DecimalMin("0.0") BigDecimal sales,
      @Schema(description = "🔴 币种") @NotBlank @Size(max = 8) String currency,
      @Schema(description = "🔴 数据截至时间") @NotNull OffsetDateTime syncedAt) {}

  /** 🔴 接收一批搜索词报告（**真实写入口**）。 */
  public record AdSearchTermBatchRequest(
      @Schema(description = "来源：PLATFORM / API / IMPORT（不给 = PLATFORM）") @Size(max = 16)
          String source,
      @Schema(description = "来源引用") @Size(max = 160) String sourceRef,
      @Schema(description = "🔴 幂等键（租户内唯一）") @NotBlank @Size(max = 160) String idempotencyKey,
      @Schema(description = "备注（进留痕）") @Size(max = 500) String note,
      @Schema(description = "本批的搜索词行") @NotEmpty @Size(max = 2000)
          List<@Valid AdSearchTermInput> terms) {}

  /** 🔴 搜索词同步的回执（逐条写明落了什么、什么没落）。 */
  public record AdSearchTermBatchResult(
      @Schema(description = "🔴 是否为重复投递（本次未落库）") boolean replayed,
      @Schema(description = "幂等键") String idempotencyKey,
      @Schema(description = "本批收到的行数") int receivedCount,
      @Schema(description = "实际落库的条数") int appliedCount,
      @Schema(description = "被跳过的条数") int skippedCount,
      @Schema(description = "🔴 逐条跳过原因") List<String> skipped,
      @Schema(description = "🔴 数据截至时间（本批最新的）") OffsetDateTime dataAsOf) {}

  /** 一条搜索词（报告行）。 */
  public record AdSearchTermRow(
      @Schema(description = "搜索词 id") UUID searchTermId,
      @Schema(description = "关键词 id") UUID keywordId,
      @Schema(description = "🔴 搜索词原文") String searchTerm,
      @Schema(description = "🔴 投放关键词原文（对照看：搜出来的词 ≠ 投的词）") String keyword,
      @Schema(description = "🔴 外部活动 id") String externalCampaignId,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "展示次数（计数；区间内按日累加）") long impressions,
      @Schema(description = "点击次数（计数）") long clicks,
      @Schema(description = "转化次数（计数）") int conversions,
      @Schema(description = "花费（金额 numeric(19,4)）") BigDecimal spend,
      @Schema(description = "销售额（金额 numeric(19,4)）") BigDecimal sales,
      @Schema(description = "币种") String currency,
      @Schema(description = "🔴 ACOS（销售额为 0 时为 null）") BigDecimal acos,
      @Schema(description = "🔴 CPC（点击为 0 时为 null）") BigDecimal cpc,
      @Schema(description = "🔴 CTR（展示为 0 时为 null）") BigDecimal ctr,
      @Schema(description = "🔴 CVR（点击为 0 时为 null）") BigDecimal cvr,
      @Schema(description = "🔴 是否高曝光零转化（浪费；服务端判定，前端不自己判）") boolean wasted,
      @Schema(description = "🔴 是否已有否定记录") boolean negated,
      @Schema(description = "🔴 数据截至时间") OffsetDateTime syncedAt,
      @Schema(description = "来源：PLATFORM / API / IMPORT") String source) {}

  /** 一页搜索词（真分页）。 */
  public record AdSearchTermPage(
      @Schema(description = "本页数据") List<AdSearchTermRow> items,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "符合条件的总条数") long total,
      @Schema(description = "🔴 区间内高曝光零转化的搜索词条数（浪费）") long wastedCount,
      @Schema(description = "🔴 本租户搜索词数据的截至时间") OffsetDateTime dataAsOf,
      @Schema(description = "🔴 同步状态：NEVER_SYNCED / SYNCED") String syncStatus,
      @Schema(description = "🔴 同步状态说明") String syncNote) {}

  // ================================================================ 否定词建议

  /**
   * 🔴 一条否定词建议 —— **带依据数值**，不是一个光秃秃的词表。
   *
   * <p>🔴 蓝图加粗句「搜索词报告是发现浪费的入口：高曝光零转化词应尽快否定，
   * 否则预算持续流失」：建议行里必须能一眼看出「它有多浪费」。
   */
  public record AdNegativeSuggestion(
      @Schema(description = "关键词 id（这条搜索词在哪个投放词下）") UUID keywordId,
      @Schema(description = "🔴 搜索词原文（建议否定的就是它）") String searchTerm,
      @Schema(description = "🔴 投放关键词原文") String keyword,
      @Schema(description = "🔴 外部活动 id") String externalCampaignId,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "店铺 id") UUID shopId,
      @Schema(description = "🔴 命中的建议规则") String rule,
      @Schema(description = "🔴 建议理由（含实际数值）") String reason,
      @Schema(description = "展示次数（计数）") long impressions,
      @Schema(description = "点击次数（计数）") long clicks,
      @Schema(description = "转化次数（计数）") int conversions,
      @Schema(description = "花费（金额 numeric(19,4)）") BigDecimal spend,
      @Schema(description = "销售额（金额 numeric(19,4)）") BigDecimal sales,
      @Schema(description = "币种") String currency,
      @Schema(description = "🔴 ACOS（销售额为 0 时为 null）") BigDecimal acos,
      @Schema(description = "🔴 CPC（点击为 0 时为 null）") BigDecimal cpc,
      @Schema(description = "🔴 CTR（展示为 0 时为 null）") BigDecimal ctr,
      @Schema(description = "🔴 区间起点（UTC 日）") LocalDate from,
      @Schema(description = "🔴 区间止（UTC 日）") LocalDate to,
      @Schema(description = "🔴 依据的数据截至时间") OffsetDateTime dataAsOf,
      @Schema(description = "🔴 建议的否定类型：NEGATIVE_EXACT / NEGATIVE_PHRASE")
          String suggestedNegationType,
      @Schema(description = "🔴 是否已有否定记录") boolean negated) {}

  /** 一页否定词建议（真分页）。 */
  public record AdNegativeSuggestionPage(
      @Schema(description = "本页数据") List<AdNegativeSuggestion> items,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "符合条件的总条数") long total,
      @Schema(description = "🔴 建议命中的规则说明") String ruleNote,
      @Schema(description = "🔴 依据的统计区间起点") LocalDate from,
      @Schema(description = "🔴 依据的统计区间止") LocalDate to,
      @Schema(description = "🔴 依据的数据截至时间") OffsetDateTime dataAsOf) {}

  // ================================================================ 否定记录

  /**
   * 🔴 登记一条否定。
   *
   * <p>🔴 **权威依据由服务端现算**，不采信调用方给的数字：服务端按与「否定建议」**同一套口径**
   * （搜索词报告优先，落到关键词表现）在决定的那一刻现算曝光 / 点击 / 转化 / 花费 / 销售额 /
   * ACOS，写进 {@code evidence}。这样「凭什么否定它」永远是系统看到的那份数据，
   * 而不是操作员敲进去的一串数。
   *
   * <p>🔴 {@code claimedEvidence}（可空）只作**审计留痕**：调用方自述的依据原样存下来，
   * 事后能对比「系统看到的」与「人以为的」差在哪。
   */
  public record AdNegativeInput(
      @Schema(description = "关键词 id（否定的是投放词时必填；否定搜索词时也带上，便于回溯）")
          UUID keywordId,
      @Schema(description = "🔴 被否定的词原文") @NotBlank @Size(max = 200) String negationText,
      @Schema(description = "🔴 否定类型：NEGATIVE_EXACT / NEGATIVE_PHRASE（不给 = NEGATIVE_EXACT）")
          @Size(max = 24) String negationType,
      @Schema(description = "🔴 外部活动 id（否定词是活动级的）") @NotBlank @Size(max = 128)
          String externalCampaignId,
      @Schema(description = "🔴 原因（必填，进留痕：否定要能追溯到依据）") @NotBlank @Size(max = 500)
          String reason,
      @Schema(description = "🔴 调用方自述的依据（可空，只作审计留痕；**权威依据由服务端现算**）")
          Map<String, Object> claimedEvidence,
      @Schema(description = "🔴 依据的统计区间起（UTC 日；不给 = 近 30 天）") LocalDate evidenceFrom,
      @Schema(description = "🔴 依据的统计区间止（UTC 日；不给 = 今天）") LocalDate evidenceTo,
      @Schema(description = "🔴 是否采纳系统建议（MANUAL / SYSTEM_SUGGESTION；不给 = MANUAL）")
          @Size(max = 24) String suggestedBy) {}

  /** 一条否定记录。 */
  public record AdNegativeRow(
      @Schema(description = "否定记录 id") UUID negativeId,
      @Schema(description = "关键词 id（可空）") UUID keywordId,
      @Schema(description = "🔴 被否定的词原文") String negationText,
      @Schema(description = "🔴 否定类型") String negationType,
      @Schema(description = "状态：DECIDED / PUSHED / REJECTED") String status,
      @Schema(description = "🔴 外部活动 id") String externalCampaignId,
      @Schema(description = "店铺 id") UUID shopId,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "原因") String reason,
      @Schema(description = "🔴 依据快照（曝光/点击/转化/花费/销售额/ACOS 的实际数值，服务端现算）")
          Map<String, Object> evidence,
      @Schema(description = "🔴 调用方自述的依据（只作审计留痕）") Map<String, Object> claimedEvidence,
      @Schema(description = "🔴 依据的统计区间起") LocalDate evidenceFrom,
      @Schema(description = "🔴 依据的统计区间止") LocalDate evidenceTo,
      @Schema(description = "🔴 依据来源：MANUAL / SYSTEM_SUGGESTION") String suggestedBy,
      @Schema(description = "🔴 平台回执时间") OffsetDateTime appliedAt,
      @Schema(description = "🔴 平台回执原文") String platformNote,
      @Schema(description = "创建时间") OffsetDateTime createdAt) {}

  /** 一页否定记录（真分页）。 */
  public record AdNegativePage(
      @Schema(description = "本页数据") List<AdNegativeRow> items,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "符合条件的总条数") long total) {}

  /**
   * 🔴 登记平台对一条否定的回执。
   *
   * <p>🔴 本模块**不推平台**（推给平台广告 API 归 02 通道 / 平台适配器），
   * 但**回执必须能进来** —— 否则这条否定永远停在「已决定」，运营不知道它到底生效没有。
   */
  public record AdNegativeResultInput(
      @Schema(description = "🔴 平台是否接受这条否定") @NotNull Boolean applied,
      @Schema(description = "🔴 平台回执原文（拒绝原因等）") @Size(max = 500) String platformNote) {}

  // ================================================================ 批量调整

  /**
   * 🔴 批量调价的一条目标。
   *
   * <p>🔴 {@code keywordId} 是必填：批量操作的对象就是**投放关键词**。
   * 否定的目标文本由服务端从该关键词的搜索词报告里取（否定的对象是搜索词时，
   * 走 {@code AdNegativeInput} 那条路）。
   */
  public record AdBulkBidTarget(
      @Schema(description = "关键词 id") @NotNull UUID keywordId,
      @Schema(description = "🔴 期望的调价结果（PERCENT 时是百分比，如 20 = +20%；ABSOLUTE 时是目标竞价）")
          BigDecimal bidValue) {}

  /**
   * 🔴 一次批量调价与否定。
   *
   * <p>🔴 {@code idempotencyKey} **必填**：调价是**花钱**的动作，重投一次就是多花一次。
   */
  public record AdBulkActionRequest(
      @Schema(description = "🔴 动作类型：BID_ADJUST 批量调价 / NEGATE 批量否定") @NotBlank @Size(max = 16)
          String actionType,
      @Schema(description = "🔴 店铺 id（一次批量操作限定在同一店铺内）") @NotNull UUID shopId,
      @Schema(description = "🔴 调价方式：PERCENT 百分比 / ABSOLUTE 直接设成某个竞价（BID_ADJUST 时必填）")
          @Size(max = 16) String bidMode,
      @Schema(description = "🔴 调价币种（BID_ADJUST 时必填；逐条按币种核对）") @Size(max = 8)
          String currency,
      @Schema(description = "🔴 调价目标（BID_ADJUST 时必填，逐条给期望值；NEGATE 时可空）")
          List<@Valid AdBulkBidTarget> targets,
      @Schema(description = "🔴 否定的类型（NEGATE 时可空；不给 = NEGATIVE_EXACT）") @Size(max = 24)
          String negationType,
      @Schema(description = "🔴 幂等键（租户内唯一）") @NotBlank @Size(max = 160) String idempotencyKey,
      @Schema(description = "备注（进留痕）") @Size(max = 500) String note) {}

  /** 批量任务里的一条逐条结果。 */
  public record AdBulkActionItemRow(
      @Schema(description = "结果 id") UUID itemId,
      @Schema(description = "关键词 id") UUID keywordId,
      @Schema(description = "🔴 目标原文（关键词文本 / 否定文本）") String targetRef,
      @Schema(description = "🔴 调价前的竞价") BigDecimal beforeBid,
      @Schema(description = "🔴 调价后的竞价") BigDecimal afterBid,
      @Schema(description = "逐条状态：APPLIED / FAILED / SKIPPED") String status,
      @Schema(description = "🔴 逐条原因（没成必须写清为什么）") String reason,
      @Schema(description = "🔴 逐条附加信息（否定时是依据快照）") Map<String, Object> result) {}

  /**
   * 🔴 一次批量调整的**回执**：逐条写明成了没成、为什么没成。
   *
   * <p>🔴 {@code status=REJECTED} 表示**整批被护栏拒绝**（一条都没动）——
   * 「整批越线就整批不动」比「部分执行」安全：批量调价越线意味着可能烧掉整月预算。
   */
  public record AdBulkActionResult(
      @Schema(description = "批量任务 id") UUID actionId,
      @Schema(description = "🔴 动作类型：BID_ADJUST / NEGATE") String actionType,
      @Schema(description = "🔴 任务状态：RECEIVED / APPLIED / PARTIAL / REJECTED") String status,
      @Schema(description = "🔴 幂等键") String idempotencyKey,
      @Schema(description = "🔴 是否为重复投递（本次未执行）") boolean replayed,
      @Schema(description = "请求的条目数") int requestedCount,
      @Schema(description = "🔴 成功的条目数（**任务本身**的计数；重投时返回既有任务的计数，"
              + "replayed=true 说明本次没有执行）")
          int appliedCount,
      @Schema(description = "🔴 失败的条目数") int failedCount,
      @Schema(description = "🔴 整批被拒的原因（REJECTED 时写清是哪条上限）") String reason,
      @Schema(description = "🔴 护栏快照（这次用的上限与实际总上调）") Map<String, Object> guardrail,
      @Schema(description = "🔴 逐条执行结果") List<AdBulkActionItemRow> items) {}

  /** 批量任务列表行。 */
  public record AdBulkActionRow(
      @Schema(description = "批量任务 id") UUID actionId,
      @Schema(description = "🔴 动作类型") String actionType,
      @Schema(description = "🔴 任务状态") String status,
      @Schema(description = "🔴 店铺 id") UUID shopId,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "🔴 调价币种") String currency,
      @Schema(description = "请求的条目数") int requestedCount,
      @Schema(description = "🔴 成功的条目数") int appliedCount,
      @Schema(description = "🔴 失败的条目数") int failedCount,
      @Schema(description = "🔴 整批被拒的原因") String reason,
      @Schema(description = "🔴 护栏快照") Map<String, Object> guardrail,
      @Schema(description = "幂等键") String idempotencyKey,
      @Schema(description = "备注") String note,
      @Schema(description = "创建时间") OffsetDateTime createdAt) {}

  /** 一页批量任务（真分页）。 */
  public record AdBulkActionPage(
      @Schema(description = "本页数据") List<AdBulkActionRow> items,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "符合条件的总条数") long total) {}

  // ================================================================ 报表

  /** 🔴 关键词报表的一行（按维度聚合）。 */
  public record AdKeywordReportRow(
      @Schema(description = "聚合维度键") String dimensionKey,
      @Schema(description = "聚合维度值") String dimensionValue,
      @Schema(description = "🔴 币种（跨币种**不可相加** ⇒ 报表按币种分行）") String currency,
      @Schema(description = "🔴 归一后内部类型（UNMAPPED 是显式值，不参与可比聚合）")
          String internalAdType,
      @Schema(description = "🔴 原始平台类型标记（可比聚合里也带，不丢信息）") String platformAdType,
      @Schema(description = "关键词条数") long keywordCount,
      @Schema(description = "展示次数（计数）") long impressions,
      @Schema(description = "点击次数（计数）") long clicks,
      @Schema(description = "转化次数（计数）") int conversions,
      @Schema(description = "花费（金额 numeric(19,4)）") BigDecimal spend,
      @Schema(description = "销售额（金额 numeric(19,4)）") BigDecimal sales,
      @Schema(description = "🔴 ACOS（销售额为 0 时为 null）") BigDecimal acos,
      @Schema(description = "🔴 CPC（点击为 0 时为 null）") BigDecimal cpc,
      @Schema(description = "🔴 CTR（展示为 0 时为 null）") BigDecimal ctr,
      @Schema(description = "🔴 CVR（点击为 0 时为 null）") BigDecimal cvr,
      @Schema(description = "🔴 已否定的关键词条数") long negatedCount) {}

  /** 🔴 关键词报表。 */
  public record AdKeywordReport(
      @Schema(description = "🔴 聚合维度：PLATFORM / SHOP / CAMPAIGN / AD_TYPE / ALL") String dimension,
      @Schema(description = "🔴 报表行") List<AdKeywordReportRow> rows,
      @Schema(description = "🔴 指标口径说明") String metricDefinitions,
      @Schema(description = "🔴 数据截至时间") OffsetDateTime dataAsOf,
      @Schema(description = "🔴 区间起点（UTC 日）") LocalDate from,
      @Schema(description = "🔴 区间止（UTC 日）") LocalDate to,
      @Schema(description = "🔴 同步状态：NEVER_SYNCED / SYNCED") String syncStatus,
      @Schema(description = "🔴 同步状态说明") String syncNote,
      @Schema(description = "🔴 统计口径说明（哪些关键词进了报表、为什么按币种分行）")
          String scopeNote) {}

  /**
   * 🔴 词频与排名的一行。
   *
   * <p>🔴 口径**引用 4.5.1.2**（同一张排名快照、同一套 null 语义）。
   */
  public record AdKeywordFrequencyRow(
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "🔴 关键词原文") String keyword,
      @Schema(description = "🔴 币种（跨币种**不可相加** ⇒ 按币种分行）") String currency,
      @Schema(description = "🔴 归一后内部类型") String internalAdType,
      @Schema(description = "🔴 原始平台类型标记") String platformAdType,
      @Schema(description = "🔴 用这个词的投放关键词条数") long keywordCount,
      @Schema(description = "🔴 用这个词的活动数") long campaignCount,
      @Schema(description = "🔴 词频：这个词被多少个商品监控着（4.5.1.2 口径）") long frequency,
      @Schema(description = "🔴 词频里覆盖的商品数") long frequencyListingCount,
      @Schema(description = "🔴 自然排名（4.5.1.2 口径；null = 没关联或平台没报，不是 0）")
          Integer naturalRank,
      @Schema(description = "🔴 广告排名（4.5.1.2 口径；null = 没关联或平台没报，不是 0）") Integer adRank,
      @Schema(description = "🔴 名次的抓取时间（4.5.1.2 口径）") OffsetDateTime rankingCrawledAt,
      @Schema(description = "🔴 名次可能的不准确窗口（分钟）") Integer rankingAccuracyWindowMinutes,
      @Schema(description = "展示次数（计数）") long impressions,
      @Schema(description = "点击次数（计数）") long clicks,
      @Schema(description = "转化次数（计数）") int conversions,
      @Schema(description = "花费（金额 numeric(19,4)）") BigDecimal spend,
      @Schema(description = "销售额（金额 numeric(19,4)）") BigDecimal sales,
      @Schema(description = "🔴 ACOS（销售额为 0 时为 null）") BigDecimal acos,
      @Schema(description = "🔴 词频与排名的口径说明") String frequencyDefinitions) {}

  /** 🔴 词频与排名报表。 */
  public record AdKeywordFrequencyPage(
      @Schema(description = "🔴 报表行") List<AdKeywordFrequencyRow> items,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "符合条件的总条数") long total,
      @Schema(description = "🔴 词频与排名的口径说明") String frequencyDefinitions,
      @Schema(description = "🔴 数据截至时间") OffsetDateTime dataAsOf) {}
}
