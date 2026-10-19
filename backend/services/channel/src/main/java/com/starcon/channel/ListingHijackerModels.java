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
import java.util.Map;
import java.util.UUID;

/**
 * 4.5.2.1 跟卖监控 的传输模型。
 *
 * <p>蓝图：「监控他人/己方其他店铺对本 ASIN 的跟卖行为，记录跟卖者、价格、店铺，发现异常跟卖或恶意压价时告警。输入：定时抓取或平台接口返回的跟卖信息。输出：跟卖记录 + 预警通知 + 对比视图。关系：上游 = 抓取任务；下游 = 16 合规（品牌保护）、1 工作台预警。<b>跟卖数据只能作为参考</b>，处置动作（投诉/举报）在平台侧完成，系统只负责发现与提醒。」
 *
 * <p>🔴 模型层把五个不变量钉死（每个都有集成测试逐条钉住）：
 *
 * <ol>
 *   <li><b>🔴🔴 数据仅供参考，不构成处置结论</b>：每个响应都带
 *       {@code dataSource='PLATFORM_SCRAPED'} 与 {@code sourceNote}（明确写明
 *       「抓取数据、仅供参考、投诉/举报在平台侧完成」）。蓝图加粗句因此是
 *       <b>契约级</b>的硬要求，不是页面上的装饰。
 *   <li><b>🔴🔴 系统不提供任何处置动作</b>：本模块**没有**投诉 / 举报 / 申诉端点，
 *       预警记录上的 {@code status} 只有 OPEN / ACKNOWLEDGED（内部「已阅」），
 *       <b>不代表已在平台侧投诉</b>。
 *   <li><b>🔴🔴 比率不落表、由服务端现算</b>：价格差比例（{@code priceGapRatio}）由 SQL 现算，
 *       <b>分母（自家参考价）为 0 时是 null</b> —— 报 0 会被读成「跟卖价与自家价一样」，
 *       那是结论不是缺口。
 *   <li><b>🔴 己方其他店铺跟卖是算出来的</b>：{@code hijackerKind} 由服务端按跟卖者的
 *       平台侧店铺标识匹配本租户自有店铺得出（OWN_SHOP / EXTERNAL），
 *       调用方**不能**直接声明这个性质。
 *   <li><b>🔴 监控对象可以暂停但历史不删</b>：暂停只停「接收与判定」，
 *       已有跟卖记录与预警**原样留着**（配置变了不等于历史变了）。
 * </ol>
 *
 * <p>载荷一律用具名 {@code record}：Map 的值类型会被 springdoc 推成 object，codegen 出来是空对象
 * {@code {}}（9.6.1.3 已踩过）。
 */
public final class ListingHijackerModels {
  private ListingHijackerModels() {}

  /** 🔴 数据来源标识 —— 平台抓取 / 平台接口观测值。 */
  public static final String DATA_SOURCE = "PLATFORM_SCRAPED";

  /**
   * 🔴 数据来源的固定文案（前端必须显示在页面顶部）。
   *
   * <p>蓝图加粗句「跟卖数据只能作为参考，处置动作（投诉/举报）在平台侧完成，系统只负责发现与提醒」。
   */
  public static final String SOURCE_NOTE =
      "🔴 平台抓取 / 平台接口返回的观测值，**仅供参考**，不构成处置结论：抓取可能滞后、可能漏抓，"
          + "也可能抓到已经下架的报价。系统只负责**发现与提醒** —— 投诉 / 举报 / 申诉等处置动作"
          + "在平台侧完成，本系统不提供、也不代为发起。";

  /**
   * 🔴 处置动作的固定声明（前端必须显示，能力声明里也带同一句）。
   *
   * <p>这一条存在的意义是**堵住一个具体的误用**：运营看到这个页面时，
   * 最自然的动作是「点一下投诉跟卖者」—— 本系统**没有**这个入口，
   * 而且**不应该**有。把这句话写进契约与页面，比留白更安全。
   */
  public static final String ACTIONS_NOTE =
      "🔴 本系统**不提供**投诉 / 举报 / 申诉等处置动作：跟卖处置在**平台侧**完成，"
          + "本模块只负责发现与提醒。预警记录上的「已阅」只是内部状态，"
          + "**不代表已在平台侧发起任何处置**。";

  /** 预警类型：MALICIOUS_PRICE_CUT 恶意压价 / ANOMALOUS_HIJACK 异常跟卖。 */
  public static final List<String> ALERT_TYPES =
      List.of("MALICIOUS_PRICE_CUT", "ANOMALOUS_HIJACK");

  /** 预警严重度。 */
  public static final List<String> SEVERITIES = List.of("HIGH", "MEDIUM");

  /** 来源：PLATFORM 平台接口 / SCRAPE 定时抓取 / API 人工补录。 */
  public static final List<String> SOURCES = List.of("PLATFORM", "SCRAPE", "API");

  /** 进来的通道：WEBHOOK 平台推送 / POLL 轮询补捞 / API 显式录入。 */
  public static final List<String> INGEST_CHANNELS = List.of("WEBHOOK", "POLL", "API");

  /** 监控对象状态：ACTIVE 监控中 / PAUSED 暂停。 */
  public static final List<String> WATCH_STATUSES = List.of("ACTIVE", "PAUSED");

  /** 跟卖者性质：OWN_SHOP 己方其他店铺 / EXTERNAL 外部跟卖者。 */
  public static final List<String> HIJACKER_KINDS = List.of("OWN_SHOP", "EXTERNAL");

  /** 预警记录状态：OPEN 未处理 / ACKNOWLEDGED 已阅（**内部状态，非平台侧处置**）。 */
  public static final List<String> ALERT_STATUSES = List.of("OPEN", "ACKNOWLEDGED");

  // ================================================================ 能力声明

  public record ListingHijackerCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  public record ListingHijackerCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false")
          List<ListingHijackerCapabilityDimension> dimensions,
      @Schema(description = "预警类型取值域") List<String> alertTypes,
      @Schema(description = "预警严重度取值域") List<String> severities,
      @Schema(description = "来源取值域") List<String> sources,
      @Schema(description = "进来的通道") List<String> ingestChannels,
      @Schema(description = "监控对象状态取值域") List<String> watchStatuses,
      @Schema(description = "跟卖者性质取值域") List<String> hijackerKinds,
      @Schema(description = "预警记录状态取值域") List<String> alertStatuses,
      @Schema(description = "🔴 判定规则的口径（服务端算，前端原样显示）") String ruleDefinitions,
      @Schema(description = "🔴 数据来源标识（PLATFORM_SCRAPED = 平台抓取观测值）") String dataSource,
      @Schema(description = "🔴 数据来源标注（仅供参考，不构成处置结论）") String sourceNote,
      @Schema(description = "🔴 处置动作声明（投诉/举报在平台侧完成）") String actionsNote,
      @Schema(description = "上游 / 下游说明") String relationNote) {}

  // ================================================================ 筛选项词表

  public record ListingHijackerPlatformOption(
      @Schema(description = "平台编码") String code, @Schema(description = "平台名称") String name) {}

  public record ListingHijackerShopOption(
      @Schema(description = "店铺 id") UUID id,
      @Schema(description = "店铺编码") String code,
      @Schema(description = "店铺名称") String name,
      @Schema(description = "平台编码") String platformCode) {}

  public record ListingHijackerFilterOptions(
      @Schema(description = "平台词表（全局只读码表）")
          List<ListingHijackerPlatformOption> platforms,
      @Schema(description = "本租户的店铺词表") List<ListingHijackerShopOption> shops,
      @Schema(description = "预警类型取值域") List<String> alertTypes,
      @Schema(description = "预警严重度取值域") List<String> severities,
      @Schema(description = "来源取值域") List<String> sources,
      @Schema(description = "监控对象状态取值域") List<String> watchStatuses,
      @Schema(description = "跟卖者性质取值域") List<String> hijackerKinds,
      @Schema(description = "预警记录状态取值域") List<String> alertStatuses) {}

  // ================================================================ 监控对象

  /**
   * 一条跟卖记录（列表行）。
   *
   * <p>🔴 {@code platformCode} 是**派生**的（listing → shop → platform），
   * 不是独立存下来的第二份答案 —— 见迁移第六节的说明。
   */
  public record ListingHijackerRecordRow(
      @Schema(description = "跟卖记录 id") UUID recordId,
      @Schema(description = "监控对象 id") UUID watchId,
      @Schema(description = "被跟卖的商品 id") UUID listingId,
      @Schema(description = "平台商品 ID 原文（ASIN）") String externalItemId,
      @Schema(description = "商品标题") String title,
      @Schema(description = "自家店铺编码") String shopCode,
      @Schema(description = "🔴 所在平台编码（由 listing → shop → platform 派生）") String platformCode,
      @Schema(description = "跟卖者（卖家标识）") String hijackerSellerId,
      @Schema(description = "跟卖者店铺名（平台侧原文）") String hijackerShopName,
      @Schema(description = "跟卖者店铺的平台侧标识") String hijackerShopIdentifier,
      @Schema(description = "🔴 跟卖者性质：OWN_SHOP 己方其他店铺 / EXTERNAL 外部跟卖者")
          String hijackerKind,
      @Schema(description = "🔴 跟卖价格（平台侧观测值）") BigDecimal hijackerPrice,
      @Schema(description = "币种") String currency,
      @Schema(description = "🔴 自家参考售价（人工维护，非平台权威价）") BigDecimal referencePrice,
      @Schema(description = "🔴 价格差 = 自家参考价 − 跟卖价（**现算**，不落表）")
          BigDecimal priceGap,
      @Schema(description = "🔴 价格差比例 = 价格差 ÷ 自家参考价（**现算**；分母为 0 时为 null）")
          BigDecimal priceGapRatio,
      @Schema(description = "🔴 发现时间") OffsetDateTime observedAt,
      @Schema(description = "来源：PLATFORM / SCRAPE / API") String source,
      @Schema(description = "进来的通道：WEBHOOK / POLL / API") String ingestChannel,
      @Schema(description = "批次幂等键") String batchIdempotencyKey,
      @Schema(description = "入库时刻") OffsetDateTime receivedAt,
      @Schema(description = "🔴 数据来源标识（PLATFORM_SCRAPED = 平台抓取观测值）") String dataSource,
      @Schema(description = "🔴 数据来源标注（仅供参考，不构成处置结论）") String sourceNote) {}

  /** 一页跟卖记录（真分页）。 */
  public record ListingHijackerRecordPage(
      @Schema(description = "本页数据") List<ListingHijackerRecordRow> items,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "符合条件的总条数") long total,
      @Schema(description = "🔴 本筛选范围内的预警条数") long alertCount,
      @Schema(description = "🔴 本筛选范围内的监控对象数（0 = 还没配监控对象）") long watchCount,
      @Schema(description = "🔴 数据来源标识") String dataSource,
      @Schema(description = "🔴 数据来源标注（仅供参考，不构成处置结论）") String sourceNote) {}

  /** 一个监控对象（列表行）。 */
  public record ListingHijackerWatchRow(
      @Schema(description = "监控对象 id") UUID watchId,
      @Schema(description = "被监控的商品 id") UUID listingId,
      @Schema(description = "平台商品 ID 原文（ASIN）") String externalItemId,
      @Schema(description = "商品标题") String title,
      @Schema(description = "自家店铺编码") String shopCode,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "🔴 自家参考售价（人工维护，非平台权威价）") BigDecimal referencePrice,
      @Schema(description = "币种") String currency,
      @Schema(description = "🔴 恶意压价阈值（不给 = 不判定这一条）") BigDecimal priceCutThreshold,
      @Schema(description = "🔴 异常跟卖的价格差比例阈值（配置项；不给 = 不判定这一条）")
          BigDecimal priceGapRatioThreshold,
      @Schema(description = "状态：ACTIVE / PAUSED") String status,
      @Schema(description = "备注") String note,
      @Schema(description = "跟卖记录条数") long recordCount,
      @Schema(description = "未处理预警条数") long openAlertCount,
      @Schema(description = "最近一次发现跟卖的时刻；从未发现过为 null") OffsetDateTime lastObservedAt,
      @Schema(description = "创建时刻") OffsetDateTime createdAt) {}

  /** 一页监控对象（真分页）。 */
  public record ListingHijackerWatchPage(
      @Schema(description = "本页数据") List<ListingHijackerWatchRow> items,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "符合条件的总条数") long total,
      @Schema(description = "🔴 数据来源标识") String dataSource,
      @Schema(description = "🔴 数据来源标注（仅供参考，不构成处置结论）") String sourceNote) {}

  /**
   * 删除监控对象的回执。
   *
   * <p>🔴 连同多少历史一起被删掉要**写明** —— 删掉的是事实（跟卖记录 / 预警），
   * 调用方必须知道自己销毁了多少东西，不能只拿到一个「成功」。
   */
  public record ListingHijackerWatchDeleteResult(
      @Schema(description = "被删除的监控对象 id") UUID watchId,
      @Schema(description = "🔴 一并删除的跟卖记录条数") long deletedRecords,
      @Schema(description = "🔴 一并删除的预警条数") long deletedAlerts) {}

  /**
   * 🔴 新建 / 更新监控对象的请求。
   *
   * <p>🔴 {@code listingId} 与 ({@code shopCode} + {@code externalItemId}) **二选一**：
   * 后者是抓取适配器手上最常有的东西（店铺编码 + ASIN），不必先查 listing。
   */
  public record SaveHijackerWatchRequest(
      @Schema(description = "被监控的商品 id（与 shopCode+externalItemId 二选一）") UUID listingId,
      @Schema(description = "自家店铺编码（与 externalItemId 配对定位商品）") @Size(max = 64)
          String shopCode,
      @Schema(description = "平台商品 ID 原文（与 shopCode 配对定位商品）") @Size(max = 128)
          String externalItemId,
      @Schema(description = "🔴 自家参考售价（人工维护，非平台权威价；05 定价未交付）")
          @DecimalMin("0") BigDecimal referencePrice,
      @Schema(description = "币种（ISO 4217 三字母）") @Size(max = 3) String currency,
      @Schema(description = "🔴 恶意压价阈值：跟卖价 ≤ 该值即判为恶意压价（不给 = 不判定）")
          @DecimalMin("0") BigDecimal priceCutThreshold,
      @Schema(description = "🔴 异常跟卖的价格差比例阈值 ∈ [0,1)（不给 = 不判定）")
          @DecimalMin("0") BigDecimal priceGapRatioThreshold,
      @Schema(description = "状态：ACTIVE / PAUSED（不给 = ACTIVE）") @Size(max = 16) String status,
      @Schema(description = "备注") @Size(max = 500) String note) {}

  // ================================================================ 回流入库

  /** 一条跟卖信息（输入项）。 */
  public record HijackerRecordInput(
      @Schema(description = "跟卖者（卖家标识，平台侧原文）") @NotBlank @Size(max = 128)
          String hijackerSellerId,
      @Schema(description = "🔴 跟卖者店铺的平台侧标识（如 Amazon Seller ID）—— 判定「是不是我们自己人」的依据")
          @NotBlank @Size(max = 128)
          String hijackerShopIdentifier,
      @Schema(description = "跟卖者店铺名（平台侧原文，仅供展示）") @Size(max = 200)
          String hijackerShopName,
      @Schema(description = "🔴 跟卖价格（平台侧观测值）") @NotNull @DecimalMin("0")
          BigDecimal hijackerPrice,
      @Schema(description = "币种（不给 = 监控对象的币种）") @Size(max = 3) String currency,
      @Schema(description = "平台给的原文结构（原样存）") Map<String, String> detail) {}

  /**
   * 🔴 接收一次抓取 / 平台接口返回的跟卖信息。
   *
   * <p>🔴 {@code idempotencyKey} **必填**：批次级幂等靠它。服务端**不替调用方编** ——
   * 平台报表 id + 抓取时刻之类的稳定值由适配器给；同一个 key 再次投递 ⇒
   * <b>整批不再落库</b>（回执 {@code replayed=true}）。
   */
  public record IngestHijackerRecordsRequest(
      @Schema(description = "监控对象 id") @NotNull UUID watchId,
      @Schema(description = "🔴 这一批的发现时间（抓取时刻；timestamptz）") @NotNull
          OffsetDateTime observedAt,
      @Schema(description = "来源：PLATFORM / SCRAPE / API（不给 = PLATFORM）") @Size(max = 32)
          String source,
      @Schema(description = "进来的通道：WEBHOOK / POLL / API（不给 = API）") @Size(max = 16)
          String ingestChannel,
      @Schema(description = "🔴 幂等键（租户内唯一；重复投递整批不再落库）") @NotBlank @Size(max = 160)
          String idempotencyKey,
      @Schema(description = "备注（进留痕）") @Size(max = 500) String note,
      @Schema(description = "🔴 本批的跟卖信息") @NotEmpty @Size(max = 500)
          List<@Valid HijackerRecordInput> records) {}

  /**
   * 🔴 回流入库的**回执**：逐条写明真的落了什么、什么没落。
   *
   * <p>🔴 {@code replayed=true} 表示这个批次**之前已经落过**，本次**没有**再写任何记录 ——
   * 页面必须显示出来，否则运营会以为平台又同步了一遍。
   */
  public record ListingHijackerIngestResult(
      @Schema(description = "监控对象 id") UUID watchId,
      @Schema(description = "🔴 发现时间") OffsetDateTime observedAt,
      @Schema(description = "同步状态：RECEIVED / APPLIED / PARTIAL / REPLAYED") String status,
      @Schema(description = "本批收到的跟卖信息条数") int receivedCount,
      @Schema(description = "实际落库的条数") int appliedCount,
      @Schema(description = "被跳过的条数") int skippedCount,
      @Schema(description = "🔴 是否为重复投递（本次未落库）") boolean replayed,
      @Schema(description = "幂等键") String idempotencyKey,
      @Schema(description = "🔴 逐条跳过原因（不静默丢弃）") List<String> skipped,
      @Schema(description = "🔴 本批产生的预警（一条跟卖记录最多一条预警）")
          List<ListingHijackerAlertRow> alerts,
      @Schema(description = "🔴 数据来源标识") String dataSource,
      @Schema(description = "🔴 数据来源标注（仅供参考，不构成处置结论）") String sourceNote) {}

  // ================================================================ 预警记录

  /** 一条预警记录。 */
  public record ListingHijackerAlertRow(
      @Schema(description = "预警 id") UUID alertId,
      @Schema(description = "监控对象 id") UUID watchId,
      @Schema(description = "触发预警的跟卖记录 id") UUID recordId,
      @Schema(description = "被跟卖的商品 id") UUID listingId,
      @Schema(description = "平台商品 ID 原文（ASIN）") String externalItemId,
      @Schema(description = "商品标题") String title,
      @Schema(description = "预警类型：MALICIOUS_PRICE_CUT 恶意压价 / ANOMALOUS_HIJACK 异常跟卖")
          String alertType,
      @Schema(description = "严重度：HIGH / MEDIUM") String severity,
      @Schema(description = "🔴 跟卖者（卖家标识）") String hijackerSellerId,
      @Schema(description = "跟卖者店铺名") String hijackerShopName,
      @Schema(description = "🔴 跟卖者性质：OWN_SHOP / EXTERNAL") String hijackerKind,
      @Schema(description = "🔴 触发时刻的跟卖价快照") BigDecimal observedPrice,
      @Schema(description = "币种") String currency,
      @Schema(description = "🔴 判定时刻的自家参考售价快照（**不是当前值**；05 定价未交付，人工维护）")
          BigDecimal referencePrice,
      @Schema(description = "🔴 价格差比例（**现算**；分母为 0 时为 null）") BigDecimal priceGapRatio,
      @Schema(description = "🔴 状态：OPEN 未处理 / ACKNOWLEDGED 已阅（**内部状态，非平台侧处置**）")
          String status,
      @Schema(description = "🔴 命中了哪条规则（写明依据）") String reason,
      @Schema(description = "发现时间") OffsetDateTime observedAt,
      @Schema(description = "预警生成时刻") OffsetDateTime detectedAt,
      @Schema(description = "已阅时刻；未已阅为 null") OffsetDateTime acknowledgedAt,
      @Schema(description = "🔴 数据来源标识") String dataSource,
      @Schema(description = "🔴 数据来源标注（仅供参考，不构成处置结论）") String sourceNote) {}

  /** 一页预警记录（真分页）。 */
  public record ListingHijackerAlertPage(
      @Schema(description = "本页数据") List<ListingHijackerAlertRow> items,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "符合条件的总条数") long total,
      @Schema(description = "🔴 本筛选范围内的未处理预警条数") long openCount,
      @Schema(description = "🔴 数据来源标识") String dataSource,
      @Schema(description = "🔴 数据来源标注（仅供参考，不构成处置结论）") String sourceNote) {}

  /** 标记已阅的请求。 */
  public record AcknowledgeHijackerAlertRequest(
      @Schema(description = "备注（进留痕）") @Size(max = 500) String note) {}

  // ================================================================ 对比视图

  /**
   * 🔴 对比视图的一行：**一个跟卖者**对自家 listing 的价格对比。
   *
   * <p>🔴 这一行是**服务端聚合出来的**（GROUP BY 跟卖者 + 现算价格差），
   * 前端**不拼**、不自己算价格差比例。
   */
  public record ListingHijackerComparisonRow(
      @Schema(description = "跟卖者（卖家标识）") String hijackerSellerId,
      @Schema(description = "跟卖者店铺名") String hijackerShopName,
      @Schema(description = "跟卖者店铺的平台侧标识") String hijackerShopIdentifier,
      @Schema(description = "🔴 跟卖者性质：OWN_SHOP / EXTERNAL") String hijackerKind,
      @Schema(description = "🔴 所在平台编码（派生）") String platformCode,
      @Schema(description = "观察次数（区间内）") long observationCount,
      @Schema(description = "🔴 最近一次观察到的价格") BigDecimal latestPrice,
      @Schema(description = "区间内最低价") BigDecimal minPrice,
      @Schema(description = "区间内最高价") BigDecimal maxPrice,
      @Schema(description = "区间内平均价") BigDecimal avgPrice,
      @Schema(description = "🔴 自家参考售价（人工维护，非平台权威价）") BigDecimal referencePrice,
      @Schema(description = "币种") String currency,
      @Schema(description = "🔴 价格差 = 自家参考价 − 最近跟卖价（**现算**）") BigDecimal priceGap,
      @Schema(description = "🔴 价格差比例 = 价格差 ÷ 自家参考价（**现算**；分母为 0 时为 null）")
          BigDecimal priceGapRatio,
      @Schema(description = "最近一次发现时间") OffsetDateTime lastObservedAt) {}

  /**
   * 🔴 对比视图（一页跟卖者 × 自家参考价）。
   *
   * <p>🔴 {@code maxPriceGapRatio} 是**跟卖最狠的那一个**的价格差比例 ——
   * 它是「有没有人在恶性压价」最直接的读数。分母为 0 时是 null。
   */
  public record ListingHijackerComparisonPage(
      @Schema(description = "本页数据") List<ListingHijackerComparisonRow> items,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "符合条件的跟卖者总数") long total,
      @Schema(description = "监控对象 id") UUID watchId,
      @Schema(description = "被监控的商品 id") UUID listingId,
      @Schema(description = "平台商品 ID 原文（ASIN）") String externalItemId,
      @Schema(description = "商品标题") String title,
      @Schema(description = "🔴 自家参考售价（人工维护，非平台权威价）") BigDecimal referencePrice,
      @Schema(description = "币种") String currency,
      @Schema(description = "区间内跟卖者数量") long hijackerCount,
      @Schema(description = "区间内观察总次数") long observationCount,
      @Schema(description = "区间内最低跟卖价") BigDecimal lowestHijackerPrice,
      @Schema(description = "区间内平均跟卖价") BigDecimal averageHijackerPrice,
      @Schema(description = "🔴 跟卖最狠的那一个的价格差比例（区间内**最大**的那个；分母为 0 时为 null）")
          BigDecimal maxPriceGapRatio,
      @Schema(description = "区间内未处理预警条数") long openAlertCount,
      @Schema(description = "区间起（含，UTC 自然日）") String from,
      @Schema(description = "区间止（含，UTC 自然日）") String to,
      @Schema(description = "区间内是否有跟卖数据") boolean hasData,
      @Schema(description = "空态原因（没有数据时写明）") String emptyReason,
      @Schema(description = "🔴 数据来源标识") String dataSource,
      @Schema(description = "🔴 数据来源标注（仅供参考，不构成处置结论）") String sourceNote) {}
}
