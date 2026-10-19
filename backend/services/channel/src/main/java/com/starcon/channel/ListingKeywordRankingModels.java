package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 4.5.1.2 关键词排名 的传输模型。
 *
 * <p>蓝图：「监控商品在核心关键词下的自然排名与广告排名，记录历史变化，低于阈值时预警。输入：关键词集合（按商品维护）；排名抓取任务；阈值设置。输出：排名历史表 + 变化预警 + 关键词维度报表。关系：上游 = 抓取任务（02 通道）；下游 = 1 工作台预警、14 广告。<b>排名数据依赖抓取频率与平台限制，必须明确标注抓取时间与可能的不准确窗口</b>。」
 *
 * <p>🔴 模型层把三个不变量钉死（每个都有集成测试逐条钉住）：
 *
 * <ol>
 *   <li><b>🔴🔴 抓取时间与不准确窗口是契约的一部分</b>：凡���返回排名数据的响应，
 *       都带 {@code crawledAt}（抓取时间）与 {@code accuracyWindowMinutes}
 *       （可能的不准确窗口，分钟），并附 {@code accuracyNote} 说明。
 *       蓝图加粗句「必须明确标注抓取时间与可能的不准确窗口」因此是
 *       <b>接口契约级</b>的硬要求，不是页面上的装饰 —— 导出的报表里也得带着它。
 *   <li><b>🔴🔴 排名没有就写 null</b>：{@code naturalRank} / {@code adRank} 为 null 表示
 *       「这次抓取平台没报这个维度的名次」，<b>不是 0</b>。写 0 会被下游随便读成
 *       「排第一」或「完全没排名」—— 这两件事都不是事实。
 *   <li><b>🔴 变化量不落表、服务端现算</b>：{@code naturalRankChange} /
 *       {@code adRankChange} 是「本次名次 − 上次名次」，由 SQL 现算。
 *       「上一次」是按抓取时间定的，落列天然会错。
 * </ol>
 *
 * <p>载荷一律用具名 {@code record}：Map 的值类型会被 springdoc 推成 object，codegen 出来是空对象
 * {@code {}}（9.6.1.3 已踩过）。
 */
public final class ListingKeywordRankingModels {
  private ListingKeywordRankingModels() {}

  /**
   * 🔴 排名数据的不准确窗口说明（前端必须显示在排名区域顶部）。
   *
   * <p>蓝图加粗句「排名数据依赖抓取频率与平台限制，必须明确标注抓取时间与可能的不准确窗口」。
   */
  public static final String ACCURACY_NOTE =
      "🔴 排名依赖抓取频率与平台限制：每个数据点都带**抓取时间**与**可能的不准确窗口**（分钟）。"
          + "名次是抓取那一刻平台给出的位置 —— 平台侧仍可能因个性化、库存、地域与 A/B 实验而变动，"
          + "超过窗口后该名次不再代表当前排名。窗口大小由抓取方按平台限制与抓取频率给出。";

  /** 来源：PLATFORM 平台回报 / API 人工补录。 */
  public static final List<String> SOURCES = List.of("PLATFORM", "API");

  /** 进来的通道：WEBHOOK 平台推送 / POLL 轮询补捞 / API 显式录入。 */
  public static final List<String> INGEST_CHANNELS = List.of("WEBHOOK", "POLL", "API");

  /** 预警类型。 */
  public static final List<String> ALERT_TYPES =
      List.of(
          "NATURAL_BELOW_THRESHOLD",
          "AD_BELOW_THRESHOLD",
          "NATURAL_RANK_DROPPED",
          "AD_RANK_DROPPED");

  /** 趋势粒度：日 / 周 / 月。 */
  public static final List<String> GRANULARITIES = List.of("DAY", "WEEK", "MONTH");

  // ================================================================ 能力声明

  public record ListingKeywordRankingCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  public record ListingKeywordRankingCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false")
          List<ListingKeywordRankingCapabilityDimension> dimensions,
      @Schema(description = "来源取值域") List<String> sources,
      @Schema(description = "进来的通道") List<String> ingestChannels,
      @Schema(description = "预警类型") List<String> alertTypes,
      @Schema(description = "趋势粒度") List<String> granularities,
      @Schema(description = "🔴 抓取时间与不准确窗口的说明（展示层必须显示）") String accuracyNote,
      @Schema(description = "🔴 排名字段的口径（名次 / null 的含义 / 变化量怎么算）")
          String rankDefinitions,
      @Schema(description = "上游 / 下游说明") String relationNote) {}

  // ================================================================ 筛选词表

  public record ListingKeywordRankingFilterOption(
      @Schema(description = "编码") String code,
      @Schema(description = "名称") String name,
      @Schema(description = "平台编码（站点码只在平台内唯一，平台才是父级）") String platformCode) {}

  public record ListingKeywordRankingFilterOptions(
      @Schema(description = "平台列表") List<ListingKeywordRankingFilterOption> platforms,
      @Schema(description = "店铺列表") List<ListingKeywordRankingFilterOption> shops,
      @Schema(description = "站点列表") List<ListingKeywordRankingFilterOption> sites) {}

  // ================================================================ 关键词集合

  /**
   * 新建 / 改一个关键词。
   *
   * <p>🔴 平台与站点**不在这份输入里**：它们由商品所属店铺/站点推导（商品是唯一事实来源）。
   * 独立维护一份就可能与商品矛盾，而矛盾的平台归属会让排名报表按错误的维度分组。
   *
   * <p>🔴 三个阈值都可空 = 该维度不产生预警。**「没配阈值」与「阈值是 0」是两件事**：
   * 0 意味着「掉出第 0 名就预警」（等于永不触发），那是配置，不是「没配」。
   */
  public record ListingKeywordRankingKeywordInput(
      @Schema(description = "listing id（关键词挂在这个商品上）") @NotNull UUID listingId,
      @Schema(description = "关键词原文（照抄平台侧的词，不做大小写折叠）") @NotBlank @Size(max = 200)
          String keyword,
      @Schema(description = "🔴 自然排名阈值（名次数字大于它算预警；不给 = 该维度不预警）")
          @Positive Integer naturalRankThreshold,
      @Schema(description = "🔴 广告排名阈值（不给 = 该维度不预警）") @Positive Integer adRankThreshold,
      @Schema(description = "🔴 排名退步阈值（相对上次抓取退步超过它算一次变化预警；不给 = 不预警）")
          @Positive Integer rankDropThreshold,
      @Schema(description = "备注（进留痕）") @Size(max = 500) String note) {}

  /**
   * 一条关键词（列表行 / 明细共用）。
   *
   * <p>🔴 {@code latestCrawledAt} 与 {@code latestAccuracyWindowMinutes} 是
   * <b>契约级字段</b>：不给出它们，界面上那个名次就看不出是什么时候的、可能有多不准。
   */
  public record ListingKeywordRankingKeywordRow(
      @Schema(description = "关键词 id") UUID keywordId,
      @Schema(description = "listing id") UUID listingId,
      @Schema(description = "平台商品 ID 原文") String externalItemId,
      @Schema(description = "商品标题") String title,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "站点编码（无站点时为空串）") String siteCode,
      @Schema(description = "关键词") String keyword,
      @Schema(description = "自然排名阈值（null = 该维度不预警）") Integer naturalRankThreshold,
      @Schema(description = "广告排名阈值（null = 该维度不预警）") Integer adRankThreshold,
      @Schema(description = "排名退步阈值（null = 不产生变化预警）") Integer rankDropThreshold,
      @Schema(description = "是否启用（停用是软删除，历史保留）") boolean active,
      @Schema(description = "备注") String note,
      @Schema(description = "🔴 最近一次自然排名（null = 平台没报，不是 0）") Integer latestNaturalRank,
      @Schema(description = "🔴 最近一次广告排名（null = 平台没报，不是 0）") Integer latestAdRank,
      @Schema(description = "🔴 最近一次自然排名的变化（本次 − 上次；无上次时为 null）")
          Integer naturalRankChange,
      @Schema(description = "🔴 最近一次广告排名的变化（本次 − 上次；无上次时为 null）")
          Integer adRankChange,
      @Schema(description = "🔴 最近一次抓取时间（该名次是什么时候看到的）") OffsetDateTime latestCrawledAt,
      @Schema(description = "🔴 最近一次可能的不准确窗口（分钟）") Integer latestAccuracyWindowMinutes,
      @Schema(description = "该关键词的抓取批次数") long crawlCount,
      @Schema(description = "该关键词的排名历史条数") long snapshotCount,
      @Schema(description = "未确认的预警条数") long openAlertCount,
      @Schema(description = "预警总条数") long totalAlertCount) {}

  /** 一页关键词（真分页）。 */
  public record ListingKeywordRankingKeywordPage(
      @Schema(description = "本页数据") List<ListingKeywordRankingKeywordRow> items,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "符合条件的总条数") long total) {}

  /**
   * 单个关键词的排名明细。
   *
   * <p>🔴 {@code hasData=false} 时所有名次都是 null，{@code emptyReason} 写明是
   * 「还没抓过」还是「抓过但这个关键词没有数据」—— 那是两回事。
   */
  public record ListingKeywordRankingDetail(
      @Schema(description = "关键词 id") UUID keywordId,
      @Schema(description = "listing id") UUID listingId,
      @Schema(description = "平台商品 ID 原文") String externalItemId,
      @Schema(description = "商品标题") String title,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "站点编码（无站点时为空串）") String siteCode,
      @Schema(description = "关键词") String keyword,
      @Schema(description = "自然排名阈值（null = 该维度不预警）") Integer naturalRankThreshold,
      @Schema(description = "广告排名阈值（null = 该维度不预警）") Integer adRankThreshold,
      @Schema(description = "排名退步阈值（null = 不产生变化预警）") Integer rankDropThreshold,
      @Schema(description = "是否启用（停用是软删除，历史保留）") boolean active,
      @Schema(description = "备注") String note,
      @Schema(description = "🔴 最近一次自然排名（null = 平台没报，不是 0）") Integer latestNaturalRank,
      @Schema(description = "🔴 最近一次广告排名（null = 平台没报，不是 0）") Integer latestAdRank,
      @Schema(description = "🔴 自然排名变化（本次 − 上次；无上次时为 null）") Integer naturalRankChange,
      @Schema(description = "🔴 广告排名变化（本次 − 上次；无上次时为 null）") Integer adRankChange,
      @Schema(description = "🔴 最近一次抓取时间（该名次是什么时候看到的）") OffsetDateTime latestCrawledAt,
      @Schema(description = "🔴 最近一次可能的不准确窗口（分钟）") Integer latestAccuracyWindowMinutes,
      @Schema(description = "🔴 抓取时间与不准确窗口的说明（展示层必须显示）") String accuracyNote,
      @Schema(description = "覆盖该关键词的抓取批次数") long crawlCount,
      @Schema(description = "该关键词的排名历史条数") long snapshotCount,
      @Schema(description = "未确认的预警条数") long openAlertCount,
      @Schema(description = "预警总条数") long totalAlertCount,
      @Schema(description = "该关键词是否已有排名数据") boolean hasData,
      @Schema(description = "空态原因（没有数据时写明）") String emptyReason,
      @Schema(description = "最近一次抓取时刻；从未抓过为 null") OffsetDateTime lastCrawledAt) {}

  // ================================================================ 排名抓取任务

  /**
   * 一个关键词的一次排名结果。
   *
   * <p>🔴 {@code naturalRank} / {@code adRank} 可空 —— 空表示「这次平台没报这个维度的名次」，
   * <b>不是 0</b>。
   */
  public record ListingKeywordRankingRankInput(
      @Schema(description = "关键词 id（必须属于本次抓取的商品）") @NotNull UUID keywordId,
      @Schema(description = "🔴 自然排名（名次；null = 平台没报，不是 0）") @Positive Integer naturalRank,
      @Schema(description = "🔴 广告排名（名次；null = 平台没报，不是 0）") @Positive Integer adRank,
      @Schema(description = "抓取方给的原文结构（原样存，不解释不加工）") Map<String, String> detail) {}

  /**
   * 🔴 接收一次排名抓取任务。
   *
   * <p>🔴 {@code idempotencyKey} **必填**：批次级幂等靠它。服务端**不替调用方编** ——
   * 平台 + 任务批次 id 之类的稳定值由抓取方给；同一个 key 再次投递 ⇒ 整批不再落库。
   *
   * <p>🔴 {@code crawledAt} 与 {@code accuracyWindowMinutes} **必填**：
   * 没有抓取时间的排名是不合格的排名数据（蓝图加粗句）。
   */
  public record ListingKeywordRankingCrawlRequest(
      @Schema(description = "listing id（这次抓取针对哪个商品）") @NotNull UUID listingId,
      @Schema(description = "来源：PLATFORM / API（不给 = PLATFORM）") @Size(max = 32) String source,
      @Schema(description = "进来的通道：WEBHOOK / POLL / API（不给 = API）") @Size(max = 16)
          String ingestChannel,
      @Schema(description = "🔴 抓取时间（这次名次是什么时候看到的）") @NotNull OffsetDateTime crawledAt,
      @Schema(description = "🔴 可能存在的不准确窗口（分钟）") @NotNull @Min(0) @Max(525600)
          Integer accuracyWindowMinutes,
      @Schema(description = "🔴 幂等键（租户内唯一；重复投递整批不再落库）") @NotBlank @Size(max = 160)
          String idempotencyKey,
      @Schema(description = "备注（进留痕）") @Size(max = 500) String note,
      @Schema(description = "抓取方给的原文结构（原样存）") Map<String, String> detail,
      @Schema(description = "🔴 本批的排名结果（按关键词）") @NotEmpty @Size(max = 400)
          List<@Valid ListingKeywordRankingRankInput> ranks) {}

  /**
   * 🔴 抓取任务的**回执**：逐条写明真的落了什么、什么没落。
   *
   * <p>🔴 {@code replayed=true} 表示这个批次**之前已经落过**，本次**没有**再写任何排名 ——
   * 页面必须显示出来，否则运营会以为平台又抓了一遍。
   */
  public record ListingKeywordRankingCrawlResult(
      @Schema(description = "抓取批次 id") UUID crawlId,
      @Schema(description = "listing id") UUID listingId,
      @Schema(description = "抓取状态：RECEIVED / APPLIED / PARTIAL") String status,
      @Schema(description = "本批收到的排名条数") int receivedCount,
      @Schema(description = "实际落库的条数") int appliedCount,
      @Schema(description = "被跳过的条数") int skippedCount,
      @Schema(description = "🔴 是否为重复投递（本次未落库）") boolean replayed,
      @Schema(description = "幂等键") String idempotencyKey,
      @Schema(description = "🔴 逐条跳过原因（不静默丢弃）") List<String> skipped,
      @Schema(description = "🔴 抓取时间") OffsetDateTime crawledAt,
      @Schema(description = "🔴 可能存在的不准确窗口（分钟）") int accuracyWindowMinutes,
      @Schema(description = "本批产生的预警条数") int alertCount) {}

  // ================================================================ 排名历史

  /** 一条排名历史（列表行）。 */
  public record ListingKeywordRankingHistoryRow(
      @Schema(description = "快照 id") UUID snapshotId,
      @Schema(description = "关键词 id") UUID keywordId,
      @Schema(description = "listing id") UUID listingId,
      @Schema(description = "关键词") String keyword,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "站点编码") String siteCode,
      @Schema(description = "🔴 自然排名（名次；null = 平台没报，不是 0）") Integer naturalRank,
      @Schema(description = "🔴 广告排名（名次；null = 平台没报，不是 0）") Integer adRank,
      @Schema(description = "🔴 自然排名变化（本次 − 上次；无上次时为 null）") Integer naturalRankChange,
      @Schema(description = "🔴 广告排名变化（本次 − 上次；无上次时为 null）") Integer adRankChange,
      @Schema(description = "🔴 抓取时间（该名次是什么时候看到的）") OffsetDateTime crawledAt,
      @Schema(description = "🔴 可能存在的不准确窗口（分钟）") int accuracyWindowMinutes,
      @Schema(description = "🔴 抓取时间与不准确窗口的说明") String accuracyNote,
      @Schema(description = "来源：PLATFORM / API") String source,
      @Schema(description = "进来的通道：WEBHOOK / POLL / API") String ingestChannel,
      @Schema(description = "覆盖次数（抓取方修正同一次抓取时递增）") int revision,
      @Schema(description = "入库时刻") OffsetDateTime receivedAt) {}

  /** 一页排名历史（真分页）。 */
  public record ListingKeywordRankingHistoryPage(
      @Schema(description = "本页数据") List<ListingKeywordRankingHistoryRow> items,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "符合条件的总条数") long total) {}

  // ================================================================ 趋势

  /**
   * 趋势序列上的一个点（日 / 周 / 月）。
   *
   * <p>🔴 名次是**位置**：数字越小越好，所以 {@code best} 是区间内最小的名次，
   * {@code worst} 是最大的那个。
   */
  public record ListingKeywordRankingTrendPoint(
      @Schema(description = "时间桶（DAY=2026-10-01 / WEEK=2026-10-05 / MONTH=2026-10-01）")
          String bucket,
      @Schema(description = "🔴 该桶内最后一次的自然排名（null = 平台没报）") Integer lastNaturalRank,
      @Schema(description = "🔴 该桶内最后一次的广告排名（null = 平台没报）") Integer lastAdRank,
      @Schema(description = "🔴 该桶内最好的自然排名（名次最小；null = 没有样本）") Integer bestNaturalRank,
      @Schema(description = "🔴 该桶内最差的自然排名（名次最大；null = 没有样本）") Integer worstNaturalRank,
      @Schema(description = "🔴 该桶内最好的广告排名（名次最小；null = 没有样本）") Integer bestAdRank,
      @Schema(description = "🔴 该桶内最差的广告排名（名次最大；null = 没有样本）") Integer worstAdRank,
      @Schema(description = "该桶内的样本数") int sampleCount,
      @Schema(description = "🔴 该桶内最后一次抓取时间") OffsetDateTime lastCrawledAt) {}

  public record ListingKeywordRankingTrend(
      @Schema(description = "关键词 id") UUID keywordId,
      @Schema(description = "关键词") String keyword,
      @Schema(description = "粒度：DAY / WEEK / MONTH") String granularity,
      @Schema(description = "区间起（含）") String from,
      @Schema(description = "区间止（含）") String to,
      @Schema(description = "🔴 抓取时间与不准确窗口的说明") String accuracyNote,
      @Schema(description = "🔴 趋势点（**没有数据时是空数组，不是假数据**）")
          List<ListingKeywordRankingTrendPoint> points,
      @Schema(description = "区间内是否有排名数据") boolean hasData) {}

  // ================================================================ 变化预警

  /** 一条变化预警。 */
  public record ListingKeywordRankingAlertRow(
      @Schema(description = "预警 id") UUID alertId,
      @Schema(description = "关键词 id") UUID keywordId,
      @Schema(description = "listing id") UUID listingId,
      @Schema(description = "关键词") String keyword,
      @Schema(description = "商品标题") String title,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "站点编码") String siteCode,
      @Schema(description = "预警类型") String alertType,
      @Schema(description = "🔴 判定时看到的名次（null = 这次平台没报该维度）") Integer rankValue,
      @Schema(description = "🔴 上一次抓取的名次（无上一次时为 null）") Integer previousRank,
      @Schema(description = "🔴 触发时用的阈值（null = 该维度没配阈值）") Integer thresholdValue,
      @Schema(description = "🔴 判定时刻") OffsetDateTime detectedAt,
      @Schema(description = "人工确认时刻（未确认为 null）") OffsetDateTime acknowledgedAt,
      @Schema(description = "人工确认人（未确认为 null）") UUID acknowledgedBy,
      @Schema(description = "来源抓取批次 id") UUID crawlId,
      @Schema(description = "来源排名快照 id") UUID snapshotId) {}

  /** 一页变化预警（真分页）。 */
  public record ListingKeywordRankingAlertPage(
      @Schema(description = "本页数据") List<ListingKeywordRankingAlertRow> items,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "符合条件的总条数") long total,
      @Schema(description = "本筛选范围内未确认的预警条数") long openCount) {}

  // ================================================================ 关键词维度报表

  /**
   * 关键词维度报表的一行（<b>真 SQL 聚合</b>，不是前端拼）。
   *
   * <p>🔴 报表**不建表** —— 它是排名历史的聚合视图，落表就会与事实漂移。
   *
   * <p>🔴 聚合键是（平台, 站点, 关键词）：站点码只在平台内唯一，平台才是父级。
   */
  public record ListingKeywordRankingReportRow(
      @Schema(description = "关键词") String keyword,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "站点编码") String siteCode,
      @Schema(description = "监控这个关键词的商品数") long listingCount,
      @Schema(description = "排名样本数") long sampleCount,
      @Schema(description = "🔴 平均自然排名（null = 没有样本，不是 0）") BigDecimal averageNaturalRank,
      @Schema(description = "🔴 最好自然排名（名次最小；null = 没有样本）") Integer bestNaturalRank,
      @Schema(description = "🔴 最差自然排名（名次最大；null = 没有样本）") Integer worstNaturalRank,
      @Schema(description = "🔴 平均广告排名（null = 没有样本，不是 0）") BigDecimal averageAdRank,
      @Schema(description = "🔴 最好广告排名（名次最小；null = 没有样本）") Integer bestAdRank,
      @Schema(description = "🔴 最差广告排名（名次最大；null = 没有样本）") Integer worstAdRank,
      @Schema(description = "低于阈值的次数") long belowThresholdCount,
      @Schema(description = "未确认的预警条数") long openAlertCount,
      @Schema(description = "🔴 最近一次抓取时间（这些名次是什么时候看到的）") OffsetDateTime lastCrawledAt,
      @Schema(description = "🔴 最近一次可能的不准确窗口（分钟）") Integer accuracyWindowMinutes) {}

  /** 关键词维度报表。 */
  public record ListingKeywordRankingReport(
      @Schema(description = "🔴 抓取时间与不准确窗口的说明") String accuracyNote,
      @Schema(description = "🔴 排名字段的口径") String rankDefinitions,
      @Schema(description = "🔴 报表行（按关键词聚合；没有数据时是空数组）")
          List<ListingKeywordRankingReportRow> rows,
      @Schema(description = "报表里有多少个不同的关键词") long keywordCount) {}
}
