package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 4.5.1.1 Listing 表现 的传输模型。
 *
 * <p>蓝图：「单个 listing 的表现明细：曝光、点击、转化率、销量、销售额、Session、退货率、评分变化，支持时间范围对比。输入：{@code listing_id}；平台表现数据同步结果；时间范围。输出：表现指标 + 趋势图；下钻到 13 产品表现。关系：上游 = 02 同步的平台流量数据；下游 = 13 产品表现、14 广告（关联投放）。<b>表现数据来自平台而非内部订单</b>，与内部订单口径不同，展示时需标注来源。」
 *
 * <p>🔴 模型层把四个不变量钉死（每个都有集成测试逐条钉住）：
 *
 * <ol>
 *   <li><b>🔴🔴 每一个数都是平台口径</b>：{@link ListingPerformanceMetrics} 上的
 *       {@code unitsOrdered} / {@code salesAmount} / {@code returnsCount} 都是
 *       <b>平台回报</b>的口径，<b>非内部订单</b>。模型层用字段名与注释把这件事钉在
 *       契约里，前端拿到的就是平台口径，<b>不会</b>与 09 的内部订单行数混算。
 *   <li><b>🔴🔴 比率不落表、由服务端现算</b>：{@code ctr} / {@code conversionRate} /
 *       {@code returnRate} 三个字段是服务端按固定分子分母算好后下发的结论，
 *       <b>分母为 0 时是 null</b>（不是 0、也不是 100）。前端<b>原样显示</b>，不重算。
 *   <li><b>🔴 数据来源必须能被展示</b>：每个响应都带 {@code dataSource} / {@code sourceNote}，
 *       蓝图加粗句「展示时需标注来源」因此是**契约级**的硬要求，不是页面上的装饰。
 *   <li><b>🔴 没有内部订单的入口</b>：{@link IngestPerformanceRequest} 里没有任何能改
 *       内部订单或内部库存的字段 —— 本模块只接收平台回报，不参与内部账。
 * </ol>
 *
 * <p>载荷一律用具名 {@code record}：Map 的值类型会被 springdoc 推成 object，codegen 出来是空对象
 * {@code {}}（9.6.1.3 已踩过）。
 */
public final class ListingPerformanceModels {
  private ListingPerformanceModels() {}

  /** 🔴 数据来源标识 —— 平台回报口径，与内部订单口径**不是一回事**。 */
  public static final String DATA_SOURCE = "PLATFORM_REPORTED";

  /**
   * 🔴 数据来源的固定文案（前端必须显示在指标区顶部）。
   *
   * <p>蓝图加粗句「表现数据来自平台而非内部订单，与内部订单口径不同，展示时需标注来源」。
   */
  public static final String SOURCE_NOTE =
      "🔴 平台口径（平台回报数据，非内部订单）：曝光 / 点击 / Session / 销量 / 销售额 / 退货 / 评分 "
          + "全部来自平台报表，与本系统内部订单口径不同（平台按平台自然日归集、含取消与部分退款），"
          + "两者不可互相替代，也不可相加。";

  /** 来源：PLATFORM 平台回报 / API 人工补录。 */
  public static final List<String> SOURCES = List.of("PLATFORM", "API");

  /** 进来的通道：WEBHOOK 平台推送 / POLL 轮询补捞 / API 显式录入。 */
  public static final List<String> INGEST_CHANNELS = List.of("WEBHOOK", "POLL", "API");

  /** 趋势粒度：日 / 周 / 月。 */
  public static final List<String> GRANULARITIES = List.of("DAY", "WEEK", "MONTH");

  // ================================================================ 能力声明

  public record ListingPerformanceCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  public record ListingPerformanceCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false") List<ListingPerformanceCapabilityDimension> dimensions,
      @Schema(description = "来源取值域") List<String> sources,
      @Schema(description = "进来的通道") List<String> ingestChannels,
      @Schema(description = "趋势粒度") List<String> granularities,
      @Schema(description = "🔴 每个指标与比率的分子/分母口径（服务端算，前端原样显示）")
          String metricDefinitions,
      @Schema(description = "🔴 数据来源标注（平台口径 vs 内部订单）") String dataSourceNote,
      @Schema(description = "上游 / 下游说明") String relationNote) {}

  // ================================================================ 回流入库

  /**
   * 一条平台表现快照（一个统计日）。
   *
   * <p>🔴 比率（转化率 / 退货率 / 点击率）**不在这里** —— 它们由服务端按分子分母现算。
   * 平台若自报了这些比率，原文落在 {@code detail} 里，**不另开一列**
   * （一个指标两个答案比少一个答案更糟）。
   */
  public record PerformanceSnapshotInput(
      @Schema(description = "🔴 平台统计日（平台自然日口径，不是内部下单日）") @NotNull LocalDate statDate,
      @Schema(description = "曝光（平台口径）") @DecimalMin("0") BigDecimal impressions,
      @Schema(description = "点击（平台口径）") @DecimalMin("0") BigDecimal clicks,
      @Schema(description = "Session（平台口径）") @DecimalMin("0") BigDecimal sessions,
      @Schema(description = "🔴 销量（**平台口径，非内部订单**）") @DecimalMin("0") BigDecimal unitsOrdered,
      @Schema(description = "🔴 销售额（**平台口径，非内部订单**）") @DecimalMin("0") BigDecimal salesAmount,
      @Schema(description = "币种（ISO 4217 三字母）") @Size(max = 3) String currency,
      @Schema(description = "退货件数（平台口径）") @DecimalMin("0") BigDecimal returnsCount,
      @Schema(description = "退货金额（平台口径）") @DecimalMin("0") BigDecimal returnAmount,
      @Schema(description = "平台评分（当天快照值）") BigDecimal rating,
      @Schema(description = "平台评价数（当天快照值）") BigDecimal ratingCount,
      @Schema(description = "平台给的原文结构（原样存，不解释不加工）") Map<String, String> detail) {}

  /**
   * 🔴 接收一次平台表现数据同步结果。
   *
   * <p>🔴 {@code idempotencyKey} **必填**：批次级幂等靠它。服务端**不替调用方编** ——
   * 平台报表 id + 区间之类的稳定值由适配器给；同一个 key 再次投递 ⇒ 整批不再落库。
   */
  public record IngestPerformanceRequest(
      @Schema(description = "listing id（给 listingId 或 externalItemId+shopCode 其一）") UUID listingId,
      @Schema(description = "店铺编码（与 externalItemId 配对定位商品）") @Size(max = 64) String shopCode,
      @Schema(description = "平台商品 ID 原文（与 shopCode 配对定位商品）") @Size(max = 128) String externalItemId,
      @Schema(description = "来源：PLATFORM / API（不给 = PLATFORM）") @Size(max = 32) String source,
      @Schema(description = "进来的通道：WEBHOOK / POLL / API（不给 = API）") @Size(max = 16)
          String ingestChannel,
      @Schema(description = "🔴 平台给的口径区间起点") @NotNull OffsetDateTime periodStart,
      @Schema(description = "🔴 平台给的口径区间终点") @NotNull OffsetDateTime periodEnd,
      @Schema(description = "🔴 覆盖的统计日起（含）") @NotNull LocalDate statFrom,
      @Schema(description = "🔴 覆盖的统计日止（含）") @NotNull LocalDate statTo,
      @Schema(description = "🔴 幂等键（租户内唯一；重复投递整批不再落库）") @NotBlank @Size(max = 160)
          String idempotencyKey,
      @Schema(description = "备注（进留痕）") @Size(max = 500) String note,
      @Schema(description = "平台给的原文结构（原样存）") Map<String, String> detail,
      @Schema(description = "🔴 本批的平台表现快照（按统计日）") @NotEmpty @Size(max = 400)
          List<@Valid PerformanceSnapshotInput> snapshots) {}

  /**
   * 🔴 回流入库的**回执**：逐条写明真的落了什么、什么没落。
   *
   * <p>🔴 {@code replayed=true} 表示这个批次**之前已经落过**，本次**没有**再写任何快照 ——
   * 页面必须显示出来，否则运营会以为平台又同步了一遍。
   */
  public record ListingPerformanceIngestResult(
      @Schema(description = "同步批次 id") UUID syncId,
      @Schema(description = "listing id") UUID listingId,
      @Schema(description = "同步状态：RECEIVED / APPLIED / PARTIAL") String status,
      @Schema(description = "本批收到的快照条数") int receivedCount,
      @Schema(description = "实际落库的条数") int appliedCount,
      @Schema(description = "被跳过的条数（落在声明区间外）") int skippedCount,
      @Schema(description = "🔴 是否为重复投递（本次未落库）") boolean replayed,
      @Schema(description = "幂等键") String idempotencyKey,
      @Schema(description = "🔴 逐条跳过原因（不静默丢弃）") List<String> skipped,
      @Schema(description = "入库时刻") OffsetDateTime receivedAt) {}

  // ================================================================ 表现指标

  /**
   * 🔴 一段区间的表现指标（**平台口径**）。
   *
   * <p>🔴 三个比率字段（{@code ctr} / {@code conversionRate} / {@code returnRate}）是
   * <b>服务端现算</b>的结论，<b>分母为 0 时是 null</b>（不是 0、也不是 100）——
   * 报 0 会被读成「转化率 0%」，那是结论不是缺口。前端<b>原样显示</b>，null 显示成「—」。
   */
  public record ListingPerformanceMetrics(
      @Schema(description = "曝光（平台口径）") BigDecimal impressions,
      @Schema(description = "点击（平台口径）") BigDecimal clicks,
      @Schema(description = "🔴 点击率 = 点击 ÷ 曝光（分母为 0 时为 null）") BigDecimal ctr,
      @Schema(description = "Session（平台口径）") BigDecimal sessions,
      @Schema(description = "🔴 销量（**平台口径，非内部订单**）") BigDecimal unitsOrdered,
      @Schema(description = "🔴 销售额（**平台口径，非内部订单**）") BigDecimal salesAmount,
      @Schema(description = "🔴 转化率 = 销量 ÷ Session（分母为 0 时为 null）") BigDecimal conversionRate,
      @Schema(description = "退货件数（平台口径）") BigDecimal returnsCount,
      @Schema(description = "退货金额（平台口径）") BigDecimal returnAmount,
      @Schema(description = "🔴 退货率 = 退货件数 ÷ 销量（分母为 0 时为 null）") BigDecimal returnRate,
      @Schema(description = "平台评分（期末值）") BigDecimal rating,
      @Schema(description = "平台评价数（期末值）") BigDecimal ratingCount,
      @Schema(description = "🔴 评分变化 = 期末评分 − 期初评分（区间内）") BigDecimal ratingChange,
      @Schema(description = "参与聚合的快照条数") int snapshotCount,
      @Schema(description = "币种") String currency) {}

  /**
   * 一个指标的本期 vs 上期对比。
   *
   * <p>🔴 {@code changeRate} 在上期为 0 时是 <b>null</b>（不是 0、不是 +∞）：
   * 「上期是 0」与「本期没变」是两件事。
   */
  public record ListingPerformanceComparisonEntry(
      @Schema(description = "指标键") String metric,
      @Schema(description = "本期值") BigDecimal current,
      @Schema(description = "上期值") BigDecimal previous,
      @Schema(description = "变化量 = 本期 − 上期") BigDecimal change,
      @Schema(description = "🔴 变化率 = 变化量 ÷ |上期|（上期为 0 时为 null）") BigDecimal changeRate,
      @Schema(description = "单位：COUNT 数量 / AMOUNT 金额 / RATE 比率 / SCORE 评分") String unit) {}

  public record ListingPerformanceComparison(
      @Schema(description = "逐指标对比") List<ListingPerformanceComparisonEntry> entries) {}

  /**
   * 🔴 单个 listing 的表现明细（含时间范围对比）。
   *
   * <p>🔴 {@code hasData=false} 时 {@code current} / {@code previous} 的所有数都是 0、
   * 比率都是 null，{@code emptyReason} 写明是「没有同步结果」还是「区间内没有数据」。
   */
  public record ListingPerformanceDetail(
      @Schema(description = "listing id") UUID listingId,
      @Schema(description = "平台商品 ID 原文") String externalItemId,
      @Schema(description = "商品标题") String title,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "🔴 数据来源标识（PLATFORM_REPORTED = 平台口径）") String dataSource,
      @Schema(description = "🔴 数据来源标注（展示层必须显示）") String sourceNote,
      @Schema(description = "本期统计日起（含）") String periodFrom,
      @Schema(description = "本期统计日止（含）") String periodTo,
      @Schema(description = "上期统计日起（含）") String comparePeriodFrom,
      @Schema(description = "上期统计日止（含）") String comparePeriodTo,
      @Schema(description = "🔴 本期指标（平台口径）") ListingPerformanceMetrics current,
      @Schema(description = "🔴 上期指标（平台口径）") ListingPerformanceMetrics previous,
      @Schema(description = "🔴 本期 vs 上期逐指标对比") ListingPerformanceComparison comparison,
      @Schema(description = "区间内是否有平台表现数据") boolean hasData,
      @Schema(description = "空态原因（没有数据时写明）") String emptyReason,
      @Schema(description = "该商品的同步结果条数") long syncCount,
      @Schema(description = "最近一次同步时刻；从未同步过为 null") OffsetDateTime lastSyncAt) {}

  // ================================================================ 表现快照列表

  /** 一条平台表现快照（列表行）。 */
  public record ListingPerformanceSnapshotRow(
      @Schema(description = "快照 id") UUID snapshotId,
      @Schema(description = "listing id") UUID listingId,
      @Schema(description = "平台商品 ID 原文") String externalItemId,
      @Schema(description = "商品标题") String title,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "🔴 平台统计日") String statDate,
      @Schema(description = "曝光（平台口径）") BigDecimal impressions,
      @Schema(description = "点击（平台口径）") BigDecimal clicks,
      @Schema(description = "点击率 = 点击 ÷ 曝光") BigDecimal ctr,
      @Schema(description = "Session（平台口径）") BigDecimal sessions,
      @Schema(description = "🔴 销量（**平台口径，非内部订单**）") BigDecimal unitsOrdered,
      @Schema(description = "🔴 销售额（**平台口径，非内部订单**）") BigDecimal salesAmount,
      @Schema(description = "转化率 = 销量 ÷ Session") BigDecimal conversionRate,
      @Schema(description = "退货件数（平台口径）") BigDecimal returnsCount,
      @Schema(description = "退货金额（平台口径）") BigDecimal returnAmount,
      @Schema(description = "退货率 = 退货件数 ÷ 销量") BigDecimal returnRate,
      @Schema(description = "平台评分") BigDecimal rating,
      @Schema(description = "平台评价数") BigDecimal ratingCount,
      @Schema(description = "来源：PLATFORM / API") String source,
      @Schema(description = "进来的通道：WEBHOOK / POLL / API") String ingestChannel,
      @Schema(description = "币种") String currency,
      @Schema(description = "🔴 覆盖次数（平台修正同一天报表时递增）") int revision,
      @Schema(description = "入库时刻") OffsetDateTime receivedAt,
      @Schema(description = "最近更新时刻") OffsetDateTime updatedAt) {}

  /**
   * 一页表现快照（真分页）。
   *
   * <p>🔴 {@code syncCount} 是本筛选范围内的**同步结果条数** ——
   * 有了它，前端才能把「还没同步过」与「同步过但这个条件下没数据」分成两种空态。
   */
  public record ListingPerformanceSnapshotPage(
      @Schema(description = "本页数据") List<ListingPerformanceSnapshotRow> items,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "符合条件的总条数") long total,
      @Schema(description = "🔴 本筛选范围内的同步结果条数") long syncCount) {}

  // ================================================================ 趋势

  /** 趋势序列上的一个点（日 / 周 / 月）。 */
  public record ListingPerformanceTrendPoint(
      @Schema(description = "时间桶（DAY=2026-10-01 / WEEK=2026-10-05 / MONTH=2026-10-01）")
          String bucket,
      @Schema(description = "曝光（平台口径）") BigDecimal impressions,
      @Schema(description = "点击（平台口径）") BigDecimal clicks,
      @Schema(description = "点击率 = 点击 ÷ 曝光") BigDecimal ctr,
      @Schema(description = "Session（平台口径）") BigDecimal sessions,
      @Schema(description = "🔴 销量（**平台口径，非内部订单**）") BigDecimal unitsOrdered,
      @Schema(description = "🔴 销售额（**平台口径，非内部订单**）") BigDecimal salesAmount,
      @Schema(description = "转化率 = 销量 ÷ Session") BigDecimal conversionRate,
      @Schema(description = "退货件数（平台口径）") BigDecimal returnsCount,
      @Schema(description = "退货金额（平台口径）") BigDecimal returnAmount,
      @Schema(description = "退货率 = 退货件数 ÷ 销量") BigDecimal returnRate,
      @Schema(description = "平台评分（该桶期末值）") BigDecimal rating,
      @Schema(description = "平台评价数（该桶期末值）") BigDecimal ratingCount) {}

  public record ListingPerformanceTrend(
      @Schema(description = "listing id") UUID listingId,
      @Schema(description = "粒度：DAY / WEEK / MONTH") String granularity,
      @Schema(description = "统计日起（含）") String from,
      @Schema(description = "统计日止（含）") String to,
      @Schema(description = "🔴 数据来源标识（PLATFORM_REPORTED = 平台口径）") String dataSource,
      @Schema(description = "🔴 数据来源标注") String sourceNote,
      @Schema(description = "🔴 趋势点（**没有数据时是空数组，不是假数据**）")
          List<ListingPerformanceTrendPoint> points,
      @Schema(description = "区间内是否有平台表现数据") boolean hasData) {}
}
