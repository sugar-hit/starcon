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
 * 4.5.2.2 上新与变更监控 的传输模型。
 *
 * <p>蓝图：「监控目标 ASIN/竞品的上下架、价格、排名、评论数变化，识别市场机会（如竞品断货涨价）。输入：监控对象列表；抓取频率。输出：变更事件流 + 历史对比。关系：上游 = 抓取任务；下游 = 13 分析、1 工作台。<b>监控对象与自有商品分离</b>，竞品数据不进自有商品模型，只做参考指标。」
 *
 * <p>🔴 模型层把四个不变量钉死（每个都有集成测试逐条钉住）：
 *
 * <ol>
 *   <li><b>🔴🔴 竞品数据不进自有商品模型</b>：本模块的传输对象里**没有任何**
 *       {@code listingId} / {@code skuId} / {@code internalSkuId}。
 *       目标身份只有「平台 + 平台商品 ID（ASIN）原文」这一对
 *       （{@code platformCode} + {@code externalItemId}）。
 *       蓝图加粗句「监控对象与自有商品分离，竞品数据不进自有商品模型，只做参考指标」
 *       因此是<b>契约级</b>的硬要求，不是页面上的装饰。
 *   <li><b>🔴🔴 变化量不落表、服务端现算</b>：{@code priceChangeRatio} /
 *       {@code priceChangeAmount} / {@code rankChange} 是「本次观测 − 上次已知观测」，
 *       由 SQL 现算，<b>分母（上次价格）为 0 时是 null</b>，不是 0。
 *   <li><b>🔴🔴 平台没报 ≠ 变成 0</b>：{@code price} / {@code rankPosition} /
 *       {@code reviewCount} 为 null 表示「这次平台没报这个维度」，
 *       <b>不是 0</b>。写 0 会被下游读成「价格 0 元」「排第一」。
 *   <li><b>🔴 变化方向只说「变没变、朝哪变」</b>：{@code direction} 一律指
 *       <b>该维度数值/状态本身</b>的变化，不含好坏判断 ——
 *       {@code RANK + INCREASED} 是「名次数字变大」＝竞品表现变差（对我方有利），
 *       但方向字段本身不替运营下这个结论；好坏由市场机会判定决定。
 * </ol>
 *
 * <p>载荷一律用具名 {@code record}：Map 的值类型会被 springdoc 推成 object，codegen 出来是空对象
 * {@code {}}（9.6.1.3 已踩过）。
 */
public final class ListingChangeModels {
  private ListingChangeModels() {}

  // ================================================================ 枚举取值域

  /** 目标性质：COMPETITOR 竞品 / OWN_ASIN 自有 ASIN。 */
  public static final List<String> TARGET_KINDS = List.of("COMPETITOR", "OWN_ASIN");

  /** 上下架状态：LISTED 在售 / OUT_OF_STOCK 断货 / DELISTED 已下架 / UNKNOWN 平台没报。 */
  public static final List<String> LISTING_STATUSES =
      List.of("LISTED", "OUT_OF_STOCK", "DELISTED", "UNKNOWN");

  /** 变化维度：上下架 / 价格 / 排名 / 评论数。 */
  public static final List<String> CHANGE_TYPES =
      List.of("LISTING_STATUS", "PRICE", "RANK", "REVIEW_COUNT");

  /** 变化方向（只说方向，不含好坏判断）。 */
  public static final List<String> DIRECTIONS = List.of("INCREASED", "DECREASED", "CHANGED");

  /** 市场机会类型。 */
  public static final List<String> OPPORTUNITY_TYPES =
      List.of(
          "COMPETITOR_OUT_OF_STOCK",
          "COMPETITOR_PRICE_INCREASE",
          "COMPETITOR_RANK_WORSENED",
          "COMPETITOR_LISTED");

  /** 严重度。 */
  public static final List<String> SEVERITIES = List.of("HIGH", "MEDIUM", "LOW");

  /** 来源：PLATFORM 平台接口 / SCRAPE 定时抓取 / API 人工补录。 */
  public static final List<String> SOURCES = List.of("PLATFORM", "SCRAPE", "API");

  /** 进来的通道：WEBHOOK 平台推送 / POLL 轮询补捞 / API 显式录入。 */
  public static final List<String> INGEST_CHANNELS = List.of("WEBHOOK", "POLL", "API");

  /** 监控对象状态：ACTIVE 监控中 / PAUSED 暂停。 */
  public static final List<String> WATCH_STATUSES = List.of("ACTIVE", "PAUSED");

  /** 市场机会状态：OPEN 未处理 / ACKNOWLEDGED 已阅。 */
  public static final List<String> OPPORTUNITY_STATUSES = List.of("OPEN", "ACKNOWLEDGED");

  /** 排名口径：BSR 平台销量榜 / CATEGORY 类目榜 / KEYWORD 关键词下。 */
  public static final List<String> RANK_BASES = List.of("BSR", "CATEGORY", "KEYWORD");

  /**
   * 🔴 数据来源标识（每个响应都带）。
   *
   * <p>蓝图加粗句「竞品数据不进自有商品模型，<b>只做参考指标</b>」落到契约上就是它：
   * 页面与下游一眼就能认出这批数字是**平台侧观测**，不是我方商品的事实。
   */
  public static final String DATA_SOURCE = "PLATFORM_SCRAPED";

  /**
   * 🔴 数据来源标注（每个响应都带，前端固定显示在页面顶部）。
   *
   * <p>竞品的价格 / 排名 / 评论数是抓回来的**观测**，不是市场事实 ——
   * 抓取会滞后、会漏抓、也会抓到已下架的商品。
   */
  public static final String SOURCE_NOTE =
      "🔴 平台侧抓取观测值，仅供参考，不构成市场结论：抓取会滞后、会漏抓，"
          + "也会抓到已下架 / 已断货的商品。所有价格、排名、评论数都是「某时刻平台看到的数」，"
          + "不是持续成立的事实。";

  /**
   * 🔴 动作声明（每个响应都带）。
   *
   * <p>本模块只负责**发现**变化与机会，不提供任何抢占 / 调价 / 铺货动作入口 ——
   * 竞品数据只做参考指标，动作由运营在平台侧或自有定价流程里完成。
   */
  public static final String ACTIONS_NOTE =
      "🔴 本系统不提供抢占 / 调价 / 铺货等动作：竞品数据只做参考指标，"
          + "机会需要人工判断后再在平台侧或自有定价流程里执行。";

  // ================================================================ 能力声明

  public record ListingChangeCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  public record ListingChangeCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false")
          List<ListingChangeCapabilityDimension> dimensions,
      @Schema(description = "目标性质取值域") List<String> targetKinds,
      @Schema(description = "上下架状态取值域") List<String> listingStatuses,
      @Schema(description = "变化维度取值域") List<String> changeTypes,
      @Schema(description = "变化方向取值域") List<String> directions,
      @Schema(description = "市场机会类型取值域") List<String> opportunityTypes,
      @Schema(description = "严重度取值域") List<String> severities,
      @Schema(description = "来源取值域") List<String> sources,
      @Schema(description = "进来的通道") List<String> ingestChannels,
      @Schema(description = "监控对象状态取值域") List<String> watchStatuses,
      @Schema(description = "市场机会状态取值域") List<String> opportunityStatuses,
      @Schema(description = "排名口径取值域") List<String> rankBases,
      @Schema(description = "🔴 判定规则口径（服务端算，前端原样显示）") String ruleDefinitions,
      @Schema(description = "🔴 数据来源标识（参考数据）") String dataSource,
      @Schema(description = "🔴 数据来源标注（仅供参考，不构成市场结论）") String sourceNote,
      @Schema(description = "🔴 动作声明（本模块不提供抢占/调价等动作）") String actionsNote,
      @Schema(description = "上游 / 下游说明") String relationNote) {}

  // ================================================================ 筛选词表

  public record ListingChangePlatformOption(
      @Schema(description = "平台编码") String code,
      @Schema(description = "平台名称") String name) {}

  public record ListingChangeMarketplaceOption(
      @Schema(description = "站点编码（只在平台内唯一）") String code,
      @Schema(description = "站点名称") String name,
      @Schema(description = "所属平台编码") String platformCode) {}

  public record ListingChangeFilterOptions(
      @Schema(description = "平台列表") List<ListingChangePlatformOption> platforms,
      @Schema(description = "站点列表") List<ListingChangeMarketplaceOption> marketplaces,
      @Schema(description = "目标性质取值域") List<String> targetKinds,
      @Schema(description = "上下架状态取值域") List<String> listingStatuses,
      @Schema(description = "变化维度取值域") List<String> changeTypes,
      @Schema(description = "变化方向取值域") List<String> directions,
      @Schema(description = "市场机会类型取值域") List<String> opportunityTypes,
      @Schema(description = "严重度取值域") List<String> severities,
      @Schema(description = "来源取值域") List<String> sources,
      @Schema(description = "监控对象状态取值域") List<String> watchStatuses,
      @Schema(description = "市场机会状态取值域") List<String> opportunityStatuses,
      @Schema(description = "排名口径取值域") List<String> rankBases) {}

  // ================================================================ 监控对象

  /**
   * 新建 / 改一个监控对象。
   *
   * <p>🔴 **目标身份只有平台 + 平台商品 ID（ASIN）**，没有 listingId ——
   * 蓝图加粗句「监控对象与自有商品分离」在**入参**这一层就已经生效。
   *
   * <p>🔴 四个机会判定开关都是**配置**（阈值不给 = 该维度**不判定**）：
   * 「没配阈值」与「阈值是 0」是两件事 ——
   * 0 意味着「涨 0% 就算涨价」，那是一个**永远命中**的配置，不是「没配」。
   */
  public record SaveListingChangeWatchRequest(
      @Schema(description = "🔴 平台编码（目标在哪个平台上被观察）") @NotBlank @Size(max = 32)
          String platformCode,
      @Schema(description = "站点编码（可空；站点码只在平台内唯一）") @Size(max = 32)
          String marketplaceCode,
      @Schema(description = "🔴 平台商品 ID 原文（ASIN）—— 目标身份的一半") @NotBlank @Size(max = 128)
          String externalItemId,
      @Schema(description = "🔴 目标性质：COMPETITOR 竞品 / OWN_ASIN 自有 ASIN（不给 = COMPETITOR）")
          @Size(max = 16) String targetKind,
      @Schema(description = "目标标题（平台侧原文，仅供展示）") @Size(max = 300) String title,
      @Schema(description = "目标品牌（平台侧原文，仅供展示）") @Size(max = 120) String brand,
      @Schema(description = "🔴 抓取频率（分钟；蓝图输入项。不给 = 720）") @Min(1) @Max(100000)
          Integer crawlIntervalMinutes,
      @Schema(description = "状态：ACTIVE / PAUSED（不给 = ACTIVE）") @Size(max = 16) String status,
      @Schema(description = "🔴 竞品断货 ⇒ 市场机会（不给 = 开启）") Boolean outOfStockOpportunity,
      @Schema(description = "🔴 上新 / 重新上架 ⇒ 市场机会（不给 = 关闭）") Boolean listedOpportunity,
      @Schema(description = "🔴 涨价幅度阈值（比例；**不给 = 不判定**这一条）") BigDecimal
          priceIncreaseRatioThreshold,
      @Schema(description = "🔴 排名名次数字变大超过该位数 ⇒ 市场机会（**不给 = 不判定**这一条）")
          @Positive Integer rankWorsenThreshold,
      @Schema(description = "备注（进留痕）") @Size(max = 500) String note) {}

  /**
   * 一个监控对象（列表行 / 明细共用）。
   *
   * <p>🔴 「最近一次」那几个值是**最近一次已知观测**（不是最近一次抓取）——
   * 平台这次没报价格时，最近价格仍显示上一次报过的那个，并标出它的观测时刻。
   */
  public record ListingChangeWatchRow(
      @Schema(description = "监控对象 id") UUID watchId,
      @Schema(description = "🔴 平台编码") String platformCode,
      @Schema(description = "站点编码（无站点时为空串）") String marketplaceCode,
      @Schema(description = "🔴 平台商品 ID 原文（ASIN）") String externalItemId,
      @Schema(description = "🔴 目标性质：COMPETITOR / OWN_ASIN") String targetKind,
      @Schema(description = "目标标题") String title,
      @Schema(description = "目标品牌") String brand,
      @Schema(description = "🔴 抓取频率（分钟）") int crawlIntervalMinutes,
      @Schema(description = "状态：ACTIVE / PAUSED") String status,
      @Schema(description = "断货 ⇒ 机会（配置）") boolean outOfStockOpportunity,
      @Schema(description = "上新 ⇒ 机会（配置）") boolean listedOpportunity,
      @Schema(description = "🔴 涨价幅度阈值（null = 该维度不判定）") BigDecimal
          priceIncreaseRatioThreshold,
      @Schema(description = "🔴 排名变差阈值（null = 该维度不判定）") Integer rankWorsenThreshold,
      @Schema(description = "备注") String note,
      @Schema(description = "观测快照条数") long snapshotCount,
      @Schema(description = "变更事件条数") long eventCount,
      @Schema(description = "市场机会条数") long opportunityCount,
      @Schema(description = "未处理的市场机会条数") long openOpportunityCount,
      @Schema(description = "🔴 最近一次**已知**上下架状态（null = 从未观测到）") String
          lastListingStatus,
      @Schema(description = "🔴 最近一次**已知**价格（null = 从未观测到；不是 0）") BigDecimal
          lastPrice,
      @Schema(description = "🔴 最近一次**已知**排名（null = 从未观测到；不是 0）") Integer
          lastRankPosition,
      @Schema(description = "🔴 最近一次**已知**评论数（null = 从未观测到）") BigDecimal
          lastReviewCount,
      @Schema(description = "币种") String currency,
      @Schema(description = "🔴 最近一次观测时刻（null = 从未观测到）") OffsetDateTime lastObservedAt,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  /** 一页监控对象（真分页）。 */
  public record ListingChangeWatchPage(
      @Schema(description = "本页数据") List<ListingChangeWatchRow> items,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "符合条件的总条数") long total,
      @Schema(description = "🔴 数据来源标识（参考数据）") String dataSource,
      @Schema(description = "🔴 数据来源标注") String sourceNote) {}

  /**
   * 删除监控对象的回执。
   *
   * <p>🔴 写明一并删掉了多少条 —— 删掉的是事实，调用方必须知道。
   */
  public record ListingChangeWatchDeleteResult(
      @Schema(description = "监控对象 id") UUID watchId,
      @Schema(description = "🔴 平台商品 ID 原文（ASIN）") String externalItemId,
      @Schema(description = "一并删掉的抓取批次数") int crawlCount,
      @Schema(description = "一并删掉的观测快照条数") int snapshotCount,
      @Schema(description = "一并删掉的变更事件条数") int eventCount,
      @Schema(description = "一并删掉的市场机会条数") int opportunityCount) {}

  // ================================================================ 抓取入库

  /**
   * 一条观测（某时刻看到的目标状态）。
   *
   * <p>🔴 每个维度都可空 —— 空表示「平台这次没报这个维度」，<b>不是 0</b>。
   * 上下架状态不给时按 {@code UNKNOWN} 处理，它**不产生**上下架事件。
   */
  public record ListingChangeObservationInput(
      @Schema(description = "🔴 观测时刻（这次看到这些值是什么时候）") @NotNull OffsetDateTime
          observedAt,
      @Schema(description = "🔴 上下架状态：LISTED / OUT_OF_STOCK / DELISTED / UNKNOWN（不给 = UNKNOWN）")
          @Size(max = 16) String listingStatus,
      @Schema(description = "🔴 观测价格（null = 平台没报，不是 0）") BigDecimal price,
      @Schema(description = "币种（ISO 4217 三字母）") @Size(max = 3) String currency,
      @Schema(description = "🔴 排名（名次；null = 平台没报，不是 0。名次数字越小越好）")
          @Positive Integer rankPosition,
      @Schema(description = "🔴 排名口径：BSR / CATEGORY / KEYWORD（不给 = BSR）") @Size(max = 16)
          String rankBasis,
      @Schema(description = "排名口径的键（关键词原文 / 类目路径；BSR 时可空）") @Size(max = 200)
          String rankBasisKey,
      @Schema(description = "🔴 评论数（null = 平台没报，不是 0）") BigDecimal reviewCount,
      @Schema(description = "抓取方给的原文结构（原样存，不解释不加工）") Map<String, String> detail) {}

  /**
   * 🔴 接收一次抓取任务的结果。
   *
   * <p>🔴 {@code idempotencyKey} **必填**：批次级幂等靠它。服务端**不替调用方编** ——
   * 平台 + 任务批次 id 之类的稳定值由抓取方给；同一个 key 再次投递 ⇒ 整批不再落库。
   *
   * <p>🔴 {@code crawledAt} 与 {@code accuracyWindowMinutes} **必填**：
   * 没有抓取时间与不准确窗口的竞品数据是不合格的数据。
   *
   * <p>🔴 一批可以带回**多条观测**（回补历史用），每条观测带自己的 {@code observedAt}。
   */
  public record IngestListingChangeCrawlRequest(
      @Schema(description = "🔴 监控对象 id（这一批盯的是哪个目标）") @NotNull UUID watchId,
      @Schema(description = "来源：PLATFORM / SCRAPE / API（不给 = PLATFORM）") @Size(max = 16)
          String source,
      @Schema(description = "进来的通道：WEBHOOK / POLL / API（不给 = API）") @Size(max = 16)
          String ingestChannel,
      @Schema(description = "🔴 抓取时刻（这一批是什么时候跑的）") @NotNull OffsetDateTime crawledAt,
      @Schema(description = "🔴 可能存在的不准确窗口（分钟）") @NotNull @Min(0) @Max(525600)
          Integer accuracyWindowMinutes,
      @Schema(description = "🔴 幂等键（租户内唯一；重复投递整批不再落库）") @NotBlank @Size(max = 160)
          String idempotencyKey,
      @Schema(description = "备注（进留痕）") @Size(max = 500) String note,
      @Schema(description = "抓取方给的原文结构（原样存）") Map<String, String> detail,
      @Schema(description = "🔴 本批的观测（每条带自己的观测时刻）") @NotEmpty @Size(max = 500)
          List<@Valid ListingChangeObservationInput> observations) {}

  /**
   * 🔴 抓取入库的**回执**：逐条写明真的落了什么、什么没落。
   *
   * <p>🔴 {@code replayed=true} 表示这个批次**之前已经落过**，本次**没有**再写任何数据 ——
   * 页面必须显示出来，否则运营会以为平台又抓了一遍。
   */
  public record ListingChangeCrawlResult(
      @Schema(description = "抓取批次 id") UUID crawlId,
      @Schema(description = "监控对象 id") UUID watchId,
      @Schema(description = "抓取状态：RECEIVED / APPLIED / PARTIAL") String status,
      @Schema(description = "本批收到的观测条数") int receivedCount,
      @Schema(description = "实际落库的条数（**重复投递时是原批次落库条数** —— 本次未落库，看 replayed）")
          int appliedCount,
      @Schema(description = "被跳过的条数") int skippedCount,
      @Schema(description = "🔴 是否为重复投递（本次未落库）") boolean replayed,
      @Schema(description = "幂等键") String idempotencyKey,
      @Schema(description = "🔴 逐条跳过原因（不静默丢弃）") List<String> skipped,
      @Schema(description = "🔴 抓取时刻") OffsetDateTime crawledAt,
      @Schema(description = "🔴 可能存在的不准确窗口（分钟）") int accuracyWindowMinutes,
      @Schema(description = "本批产生的变更事件条数") int eventCount,
      @Schema(description = "本批产生的市场机会条数") int opportunityCount) {}

  /** 一条抓取批次（列表行）。 */
  public record ListingChangeCrawlRow(
      @Schema(description = "抓取批次 id") UUID crawlId,
      @Schema(description = "监控对象 id") UUID watchId,
      @Schema(description = "🔴 平台商品 ID 原文（ASIN）") String externalItemId,
      @Schema(description = "来源：PLATFORM / SCRAPE / API") String source,
      @Schema(description = "进来的通道：WEBHOOK / POLL / API") String ingestChannel,
      @Schema(description = "🔴 抓取时刻") OffsetDateTime crawledAt,
      @Schema(description = "🔴 可能存在的不准确窗口（分钟）") int accuracyWindowMinutes,
      @Schema(description = "抓取状态：RECEIVED / APPLIED / PARTIAL") String status,
      @Schema(description = "收到的观测条数") int receivedCount,
      @Schema(description = "落库的观测条数") int appliedCount,
      @Schema(description = "跳过的观测条数") int skippedCount,
      @Schema(description = "产生的变更事件条数") int eventCount,
      @Schema(description = "产生的市场机会条数") int opportunityCount,
      @Schema(description = "幂等键") String idempotencyKey,
      @Schema(description = "备注") String note,
      @Schema(description = "入库时刻") OffsetDateTime receivedAt) {}

  /** 一页抓取批次（真分页）。 */
  public record ListingChangeCrawlPage(
      @Schema(description = "本页数据") List<ListingChangeCrawlRow> items,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "符合条件的总条数") long total,
      @Schema(description = "🔴 数据来源标识（参考数据）") String dataSource,
      @Schema(description = "🔴 数据来源标注") String sourceNote) {}

  // ================================================================ 观测快照（历史对比的事实源）

  /** 一条观测快照。 */
  public record ListingChangeSnapshotRow(
      @Schema(description = "快照 id") UUID snapshotId,
      @Schema(description = "监控对象 id") UUID watchId,
      @Schema(description = "🔴 平台商品 ID 原文（ASIN）") String externalItemId,
      @Schema(description = "🔴 观测时刻") OffsetDateTime observedAt,
      @Schema(description = "🔴 可能存在的不准确窗口（分钟）") int accuracyWindowMinutes,
      @Schema(description = "🔴 上下架状态：LISTED / OUT_OF_STOCK / DELISTED / UNKNOWN") String
          listingStatus,
      @Schema(description = "🔴 观测价格（null = 平台没报，不是 0）") BigDecimal price,
      @Schema(description = "币种") String currency,
      @Schema(description = "🔴 排名（名次；null = 平台没报，不是 0）") Integer rankPosition,
      @Schema(description = "🔴 排名口径：BSR / CATEGORY / KEYWORD") String rankBasis,
      @Schema(description = "排名口径的键") String rankBasisKey,
      @Schema(description = "🔴 评论数（null = 平台没报，不是 0）") BigDecimal reviewCount,
      @Schema(description = "来源") String source,
      @Schema(description = "进来的通道") String ingestChannel,
      @Schema(description = "覆盖次数") int revision,
      @Schema(description = "🔴 数据来源标识（参考数据）") String dataSource,
      @Schema(description = "🔴 数据来源标注") String sourceNote) {}

  /** 一页观测快照（真分页）。 */
  public record ListingChangeSnapshotPage(
      @Schema(description = "本页数据") List<ListingChangeSnapshotRow> items,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "符合条件的总条数") long total,
      @Schema(description = "🔴 数据来源标识（参考数据）") String dataSource,
      @Schema(description = "🔴 数据来源标注") String sourceNote) {}

  // ================================================================ 变更事件流

  /**
   * 一条变更事件。
   *
   * <p>🔴 {@code previousValue} / {@code currentValue} 是**当时那一对观测值的快照**，
   * 不是「变化量」—— 变化幅度与比例由 {@code priceChangeAmount} /
   * {@code priceChangeRatio} / {@code rankChange} / {@code reviewCountChange}
   * <b>现算</b>给出。
   */
  public record ListingChangeEventRow(
      @Schema(description = "事件 id") UUID eventId,
      @Schema(description = "监控对象 id") UUID watchId,
      @Schema(description = "触发这次变化的观测 id") UUID snapshotId,
      @Schema(description = "比较基准的观测 id（null = 首次观测，没有基准）") UUID
          previousSnapshotId,
      @Schema(description = "🔴 平台商品 ID 原文（ASIN）") String externalItemId,
      @Schema(description = "目标标题") String title,
      @Schema(description = "🔴 变化维度：LISTING_STATUS / PRICE / RANK / REVIEW_COUNT") String
          changeType,
      @Schema(description = "🔴 方向：INCREASED / DECREASED / CHANGED（只说方向，不含好坏）")
          String direction,
      @Schema(description = "🔴 旧值（数值维度；null = 平台没报 / 首次观测）") BigDecimal previousValue,
      @Schema(description = "🔴 新值（数值维度）") BigDecimal currentValue,
      @Schema(description = "🔴 旧状态（上下架维度）") String previousState,
      @Schema(description = "🔴 新状态（上下架维度）") String currentState,
      @Schema(description = "🔴 价格变化量（现算；无基准时 null）") BigDecimal priceChangeAmount,
      @Schema(description = "🔴 价格变化比例（现算；**分母为 0 时是 null**，不是 0）") BigDecimal
          priceChangeRatio,
      @Schema(description = "🔴 排名变化（现算：本次名次 − 上次名次；名次变大 = 竞品变差）") Integer
          rankChange,
      @Schema(description = "🔴 评论数变化（现算）") BigDecimal reviewCountChange,
      @Schema(description = "🔴 判定时刻的价格快照") BigDecimal observedPrice,
      @Schema(description = "币种") String currency,
      @Schema(description = "🔴 判定时刻") OffsetDateTime detectedAt,
      @Schema(description = "🔴 判定依据") String reason,
      @Schema(description = "🔴 数据来源标识（参考数据）") String dataSource,
      @Schema(description = "🔴 数据来源标注") String sourceNote) {}

  /** 一页变更事件（真分页）。 */
  public record ListingChangeEventPage(
      @Schema(description = "本页数据") List<ListingChangeEventRow> items,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "符合条件的总条数") long total,
      @Schema(description = "监控对象总数") long watchCount,
      @Schema(description = "🔴 数据来源标识（参考数据）") String dataSource,
      @Schema(description = "🔴 数据来源标注") String sourceNote) {}

  // ================================================================ 市场机会

  /** 一条市场机会。 */
  public record ListingChangeOpportunityRow(
      @Schema(description = "机会 id") UUID opportunityId,
      @Schema(description = "监控对象 id") UUID watchId,
      @Schema(description = "触发这次机会的变更事件 id") UUID eventId,
      @Schema(description = "🔴 平台商品 ID 原文（ASIN）") String externalItemId,
      @Schema(description = "目标标题") String title,
      @Schema(description = "🔴 机会类型") String opportunityType,
      @Schema(description = "严重度：HIGH / MEDIUM / LOW") String severity,
      @Schema(description = "🔴 判定时刻的价格快照") BigDecimal observedPrice,
      @Schema(description = "🔴 判定时刻的排名快照") Integer observedRank,
      @Schema(description = "🔴 判定时刻的评论数快照") BigDecimal observedReviewCount,
      @Schema(description = "币种") String currency,
      @Schema(description = "🔴 状态：OPEN 未处理 / ACKNOWLEDGED 已阅（**内部状态，不代表已采取动作**）")
          String status,
      @Schema(description = "🔴 判定依据") String reason,
      @Schema(description = "🔴 判定时刻") OffsetDateTime detectedAt,
      @Schema(description = "已阅时刻（null = 未阅）") OffsetDateTime acknowledgedAt,
      @Schema(description = "🔴 数据来源标识（参考数据）") String dataSource,
      @Schema(description = "🔴 数据来源标注") String sourceNote) {}

  /** 一页市场机会（真分页）。 */
  public record ListingChangeOpportunityPage(
      @Schema(description = "本页数据") List<ListingChangeOpportunityRow> items,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "符合条件的总条数") long total,
      @Schema(description = "未处理的机会条数") long openCount,
      @Schema(description = "🔴 数据来源标识（参考数据）") String dataSource,
      @Schema(description = "🔴 数据来源标注") String sourceNote) {}

  /** 标记机会为「已阅」的请求（备注进留痕）。 */
  public record AcknowledgeListingChangeOpportunityRequest(
      @Schema(description = "备注（进留痕）") @Size(max = 500) String note) {}

  // ================================================================ 历史对比

  /** 价格维度的一段历史对比（全部现算）。 */
  public record ListingChangePriceComparison(
      @Schema(description = "区间内第一个已知价格（null = 没有样本）") BigDecimal firstPrice,
      @Schema(description = "区间内最后一个已知价格（null = 没有样本）") BigDecimal lastPrice,
      @Schema(description = "区间内最低价") BigDecimal minPrice,
      @Schema(description = "区间内最高价") BigDecimal maxPrice,
      @Schema(description = "区间内平均价") BigDecimal avgPrice,
      @Schema(description = "🔴 价格变化量（末 − 首；无首值时 null）") BigDecimal changeAmount,
      @Schema(description = "🔴 价格变化比例（现算；**分母为 0 时是 null**，不是 0）") BigDecimal
          changeRatio,
      @Schema(description = "币种") String currency,
      @Schema(description = "有价格的样本数") long sampleCount) {}

  /** 排名维度的一段历史对比（全部现算）。 */
  public record ListingChangeRankComparison(
      @Schema(description = "区间内第一个已知排名（null = 没有样本）") Integer firstRank,
      @Schema(description = "区间内最后一个已知排名（null = 没有样本）") Integer lastRank,
      @Schema(description = "区间内最好排名（名次最小）") Integer bestRank,
      @Schema(description = "区间内最差排名（名次最大）") Integer worstRank,
      @Schema(description = "🔴 排名变化（末 − 首；名次变大 = 竞品变差）") Integer changeAmount,
      @Schema(description = "排名口径：BSR / CATEGORY / KEYWORD") String rankBasis,
      @Schema(description = "有排名的样本数") long sampleCount) {}

  /** 评论数维度的一段历史对比（全部现算）。 */
  public record ListingChangeReviewComparison(
      @Schema(description = "区间内第一个已知评论数（null = 没有样本）") BigDecimal firstCount,
      @Schema(description = "区间内最后一个已知评论数（null = 没有样本）") BigDecimal lastCount,
      @Schema(description = "🔴 评论数变化（末 − 首）") BigDecimal changeAmount,
      @Schema(description = "有评论数的样本数") long sampleCount) {}

  /** 上下架维度的一段历史对比（全部现算）。 */
  public record ListingChangeStatusComparison(
      @Schema(description = "区间内最后一个已知上下架状态（null = 没有样本）") String currentStatus,
      @Schema(description = "在售样本数") long listedCount,
      @Schema(description = "断货样本数") long outOfStockCount,
      @Schema(description = "已下架样本数") long delistedCount,
      @Schema(description = "🔴 上下架迁移次数（变更事件条数）") long transitionCount,
      @Schema(description = "有上下架状态的样本数") long sampleCount) {}

  /**
   * 🔴 单个监控对象的历史对比。
   *
   * <p>🔴 **对比视图不落表** —— 它是观测快照与变更事件的 SQL 聚合。
   * 落了表就会与事实漂移（改了阈值、修正了同一次抓取，旧的比例不会跟着变）。
   */
  public record ListingChangeComparison(
      @Schema(description = "监控对象 id") UUID watchId,
      @Schema(description = "🔴 平台编码") String platformCode,
      @Schema(description = "站点编码（无站点时为空串）") String marketplaceCode,
      @Schema(description = "🔴 平台商品 ID 原文（ASIN）") String externalItemId,
      @Schema(description = "🔴 目标性质：COMPETITOR / OWN_ASIN") String targetKind,
      @Schema(description = "目标标题") String title,
      @Schema(description = "区间起（含，yyyy-MM-dd）") String from,
      @Schema(description = "区间止（含，yyyy-MM-dd）") String to,
      @Schema(description = "区间内观测样本数") long sampleCount,
      @Schema(description = "区间内变更事件条数") long eventCount,
      @Schema(description = "区间内市场机会条数") long opportunityCount,
      @Schema(description = "价格维度对比") ListingChangePriceComparison price,
      @Schema(description = "排名维度对比") ListingChangeRankComparison rank,
      @Schema(description = "评论数维度对比") ListingChangeReviewComparison reviewCount,
      @Schema(description = "上下架维度对比") ListingChangeStatusComparison listingStatus,
      @Schema(description = "区间内是否有观测数据") boolean hasData,
      @Schema(description = "空态原因（没有数据时写明）") String emptyReason,
      @Schema(description = "🔴 数据来源标识（参考数据）") String dataSource,
      @Schema(description = "🔴 数据来源标注") String sourceNote) {}
}
