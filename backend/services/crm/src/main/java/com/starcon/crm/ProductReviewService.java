package com.starcon.crm;

import static com.starcon.crm.ProductReviewModels.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.starcon.crm.ProductReviewModels.CrmProductReviewCapabilities;
import com.starcon.crm.ProductReviewModels.CrmProductReviewCapabilityDimension;
import com.starcon.crm.ProductReviewModels.CrmProductReviewCreateInput;
import com.starcon.crm.ProductReviewModels.CrmProductReviewDecline;
import com.starcon.crm.ProductReviewModels.CrmProductReviewDetail;
import com.starcon.crm.ProductReviewModels.CrmProductReviewDimensionBucket;
import com.starcon.crm.ProductReviewModels.CrmProductReviewOrderContext;
import com.starcon.crm.ProductReviewModels.CrmProductReviewPage;
import com.starcon.crm.ProductReviewModels.CrmProductReviewRatingBucket;
import com.starcon.crm.ProductReviewModels.CrmProductReviewRow;
import com.starcon.crm.ProductReviewModels.CrmProductReviewShopView;
import com.starcon.crm.ProductReviewModels.CrmProductReviewStats;
import com.starcon.crm.ProductReviewModels.CrmProductReviewSyncInput;
import com.starcon.crm.ProductReviewModels.CrmProductReviewSyncResult;
import com.starcon.crm.ProductReviewModels.CrmProductReviewTrendBucket;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 15.2.1.1 评价列表：汇总各店铺评价，并给出**店铺评分汇总**（趋势 + 分布 + 下滑维度）。
 *
 * <p>蓝图 15 卷 15.2.1.1 原话：
 *
 * <blockquote>
 *
 * 职责：汇总各店铺评价：星级、内容、变体、图片、平台时间、关联订单、是否已回复、店铺评分汇总，支持筛选（星级、含图/含视频、未回复、时间范围）与导出。 输入：评价同步；筛选条件。 输出：评价列表 + 评分统计。 关系：上游 = 2 同步；下游 = 15.2.1.2 回复、15.2.2.1 索评。 <b>评分趋势比单条评价更重要</b>：连续下滑要先查是产品问题还是履约问题。
 *
 * </blockquote>
 *
 * <p><b>🔴 加粗句「评分趋势比单条评价更重要」在本服务落成三件事</b>：
 *
 * <ol>
 *   <li><b>不建「店铺评分汇总表」</b>：汇总一律由明细<b>现算</b>（{@link #stats}）。
 *       落库的汇总会变成第二份事实 —— 明细改了汇总没改时，「平均分」与「趋势」互相矛盾，
 *       而没人说得清哪一份是真的。
 *   <li><b>趋势的时间轴是「平台评价时刻」</b>（{@code platform_reviewed_at}），
 *       不是「登记进本库的时刻」：用登记时刻分桶会把「今天集中补录了一批上个月的老评价」
 *       显示成「今天的评分突然崩了」—— 那是假下滑，会把客服引到错的方向。
 *   <li><b>下滑要能定位到维度</b>：{@link CrmProductReviewDecline} 把店铺 / 商品 / 变体
 *       <b>分别</b>比近窗与前窗，并给 {@code continuous}（三窗单调下滑）。
 *       只给「评分在跌」不给维度，客服无从下手 —— 那正是加粗句要解决的。
 * </ol>
 *
 * <p><b>跨服务纪律</b>：
 *
 * <ul>
 *   <li>店铺目录（2.3.1.1）在 <b>channel</b> 服务 ⇒ 经 {@link SignoffDeliveredOrderPort}
 *       走授权 HTTP API 读；上游读不到一律 <b>503</b>，绝不返回空集合冒充「没有店铺」。
 *   <li>关联订单上下文（订单事实在 <b>trade</b>、履约签收事实在 <b>channel</b>）
 *       ⇒ 经 {@link ReviewOrderContextPort} 走授权 HTTP API 读，
 *       <b>不直连对方库、不建跨库 FK、不建跨库视图</b>；读不到时明确说「不可用 + 原因」。
 *   <li>平台评价同步 ⇒ 经 {@link PlatformReviewPort} 接缝接，
 *       当前实现 {@link UnavailablePlatformReviewPort} 对所有平台答「未接入」，
 *       同步端点据此返回 <b>409</b> 且<b>一行都不写</b>。
 * </ul>
 */
@Service
public class ProductReviewService {

  // ================================================================ 常量

  /** 来源词表。PLATFORM_SYNC 先钉死（适配器接上时不需要改语义）。 */
  static final List<String> SOURCES = List.of("MANUAL_ENTRY", "PLATFORM_SYNC");

  /** 「是否已回复」的两种状态。 */
  static final List<String> REPLY_STATES = List.of("REPLIED", "UNREPLIED");

  /** 下滑定位的三个维度。 */
  static final List<String> DIMENSION_KEYS = List.of("SHOP", "PRODUCT", "VARIANT");

  /** 列表分页上限。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 导出上限（超过就截断，并在响应头里如实报出来）。 */
  static final int MAX_EXPORT_ROWS = 10_000;

  /** 统计默认作用域（天）。 */
  static final int DEFAULT_STATS_DAYS = 30;

  /** 统计作用域的最小 / 最大天数。 */
  static final int MIN_STATS_DAYS = 7;
  static final int MAX_STATS_DAYS = 365;

  /** 趋势分桶的目标桶数（实际桶宽由作用域天数算出，最少 1 天）。 */
  static final int TREND_BUCKETS = 10;

  /** 下滑清单最多列多少条。 */
  static final int MAX_DECLINES = 50;

  /** 每个维度的汇总最多列多少条。 */
  static final int MAX_DIMENSION_ROWS = 50;

  /** 解析 jsonb 图片地址用的 mapper。 */
  private static final ObjectMapper JSON = new ObjectMapper();

  private final DSLContext sql;
  private final SignoffDeliveredOrderPort shops;
  private final ReviewOrderContextPort orderContext;
  private final PlatformReviewPort platformReview;
  private final ObjectMapper json;

  public ProductReviewService(
      DSLContext sql,
      SignoffDeliveredOrderPort shops,
      ReviewOrderContextPort orderContext,
      PlatformReviewPort platformReview,
      ObjectMapper json) {
    this.sql = sql;
    this.shops = shops;
    this.orderContext = orderContext;
    this.platformReview = platformReview;
    this.json = json;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：每一维说清「有没有、依据是什么、缺了会怎样」。
   *
   * <p>🔴 三维刻意是 {@code available=false}：
   *
   * <ul>
   *   <li>{@code platform_review_sync} —— 平台评价适配器**未交付**（channel 的适配层只有 OAuth 适配器）。
   *   <li>{@code review_reply} —— 15.2.1.2 评价回复**未交付** ⇒ 本模块**不产生回复**。
   *   <li>{@code negative_review_handling} —— 15.2.1.3 差评处理**未交付**。
   * </ul>
   */
  public CrmProductReviewCapabilities capabilities() {
    List<CrmProductReviewCapabilityDimension> dimensions =
        List.of(
            new CrmProductReviewCapabilityDimension(
                "review_registration",
                "评价登记入口（人工登记：星级 / 内容 / 变体 / 图片 / 平台时间 / 关联订单）",
                true,
                "本服务 V294 crm_product_review",
                "",
                "评价真的落库、可筛选、可导出；平台同步没接通时，这是唯一能进评价的入口"),
            new CrmProductReviewCapabilityDimension(
                "shop_directory",
                "店铺目录（评价按店铺汇总的维度来源）",
                true,
                "上游 channel V1 shop，经授权 HTTP API 读（GET /channel/shops）",
                "2.3.1.1",
                "评价的店铺从**真实清单**里选，不是前端写死的常量；上游读不到一律 503"),
            new CrmProductReviewCapabilityDimension(
                "rating_trend",
                "🔴 评分趋势（按时间窗口的均分变化 + 星级分布）",
                true,
                "本服务 V294 crm_product_review（明细现算，**不建汇总表**）",
                "",
                "趋势按**平台评价时刻**分桶（不是登记时刻，否则补录会造成假下滑）；"
                    + "汇总一律现算，避免出现第二份与明细矛盾的事实"),
            new CrmProductReviewCapabilityDimension(
                "decline_attribution",
                "🔴 连续下滑的维度定位（店铺 / 商品 / 变体）",
                true,
                "本服务 V294（三个维度分别比近窗 / 中窗 / 远窗）",
                "",
                "加粗句「连续下滑要先查是产品问题还是履约问题」的落点："
                    + "光有「评分在跌」没有维度，客服无从下手"),
            new CrmProductReviewCapabilityDimension(
                "order_context",
                "关联订单上下文（订单状态 + 履约签收事实）",
                true,
                "上游 trade 9.x 销售订单 + channel 10.3.2.2 签收确认单，均经授权 HTTP API 读",
                "9.1.2.2 / 10.3.2.2",
                "本库只存订单号这个**逻辑引用**；读不到时页面明确显示「订单上下文不可用」并说明原因，"
                    + "绝不显示空白（空白会被读成「这条评价没有关联订单」）"),
            new CrmProductReviewCapabilityDimension(
                "platform_review_sync",
                "平台评价同步（自动把平台评价拉进来）",
                false,
                "channel 的平台适配层目前**只有 OAuth 授权适配器**，没有买家评价适配器",
                "2",
                "同步端点明确答「未接入」（409）并**一行都不写**；"
                    + "评价目前只能**人工登记**，平台适配器接上后本端点会自动放行"),
            new CrmProductReviewCapabilityDimension(
                "review_reply",
                "评价回复（公开回复 / 卖家评论追加）",
                false,
                "15.2.1.2 评价回复未交付",
                "15.2.1.2",
                "本模块**不产生回复**、不记录回复正文、不编造平台回执；"
                    + "列表里的「是否已回复」只反映**平台侧**是否已有公开回复（登记时由运营核对填写）"),
            new CrmProductReviewCapabilityDimension(
                "negative_review_handling",
                "差评处理（标记原因 / 判定申诉 / 解决率）",
                false,
                "15.2.1.3 差评处理未交付",
                "15.2.1.3",
                "低星评价在本模块里只是**星级分布里的一档**，不会被自动贴上「产品问题 / 物流问题」的标签 —— "
                    + "那些标签要有证据，而证据属于 15.2.1.3"),
            new CrmProductReviewCapabilityDimension(
                "review_request_readonly",
                "索评请求状态（15.2.2.1 已交付，只读引用）",
                true,
                "同库 crm_review_request / crm_review_recovery（V220）",
                "15.2.2.1",
                "本模块**只读**引用索评事实，**不改**它的表结构、规则表与端点"));
    return new CrmProductReviewCapabilities(
        dimensions,
        SOURCES,
        REPLY_STATES,
        DIMENSION_KEYS,
        "平台评价同步未接入（409 且不写任何数据）；评价回复 15.2.1.2 未交付；"
            + "关联订单上下文与店铺目录均经**授权 HTTP API** 读上游，不直连对方库。",
        "🔴 评分趋势比单条评价更重要：趋势按**平台评价时刻**分桶（补录不会造成假下滑），"
            + "下滑能定位到**店铺 / 商品 / 变体**三个维度，连续下滑（三窗单调）会被单独列出来。");
  }

  // ================================================================ 店铺目录

  /** 店铺目录（来自上游 channel 的真实清单）+ 每个平台的评价接入状态。 */
  public List<CrmProductReviewShopView> shops(String bearer) {
    List<CrmProductReviewShopView> out = new ArrayList<>();
    for (var shop : shops.shops(bearer)) {
      var availability = platformReview.availability(nz(shop.platformCode()));
      out.add(
          new CrmProductReviewShopView(
              shop.id(),
              nz(shop.code()),
              nz(shop.name()),
              nz(shop.platformCode()),
              nz(shop.platformName()),
              nz(shop.status()),
              availability.available(),
              availability.reason()));
    }
    return out;
  }

  // ================================================================ 评价列表

  /** 评价列表：真分页 + 真筛选（星级 / 含图 / 含视频 / 未回复 / 时间范围 / 店铺 / 维度 …）。 */
  public CrmProductReviewPage list(ReviewFilter filter, int page, int size) {
    List<Object> binds = new ArrayList<>();
    String where = reviewWhere(filter, binds);

    long total = sql.fetchOne("select count(*) from crm_product_review r" + where, binds.toArray())
        .get(0, Long.class);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);
    List<CrmProductReviewRow> items =
        sql.fetch(
                reviewColumns() + " from crm_product_review r" + where
                    + " order by r.platform_reviewed_at desc, r.id limit ? offset ?",
                pageBinds.toArray())
            .map(ProductReviewService::review);
    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmProductReviewPage(items, safePage, safeSize, total, totalPages);
  }

  // ================================================================ 评分统计（趋势 + 分布 + 下滑定位）

  /**
   * 评分统计：总数 / 已回复 / 未回复 / 含图 / 含视频 / 平均分 / 星级分布 /
   * 时间趋势 / 三个维度的汇总 / 在下滑的维度。
   *
   * <p>🔴 全部数值由 SQL 现算（{@code avg} 走 numeric），前端只原样展示。
   */
  public CrmProductReviewStats stats(ReviewFilter filter, int days) {
    int effectiveDays = Math.min(Math.max(days, MIN_STATS_DAYS), MAX_STATS_DAYS);
    OffsetDateTime end = now();
    OffsetDateTime start = end.minusDays(effectiveDays);
    // 显式给了时间范围就以它为准（否则以 days 为准），两种口径**只有一条**生效。
    if (filter.from() != null) start = filter.from();
    if (filter.to() != null) end = filter.to();
    if (!end.isAfter(start)) {
      throw bad("评分统计的时间范围不对：终点必须晚于起点。");
    }
    ReviewFilter scoped = filter.withWindow(start, end);

    // 作用域三等分：远窗 / 中窗 / 近窗（连续下滑靠这三窗的单调性判定）。
    long spanSeconds = Duration.between(start, end).getSeconds();
    long third = Math.max(1, spanSeconds / 3);
    OffsetDateTime olderEnd = start.plusSeconds(third);
    OffsetDateTime previousEnd = start.plusSeconds(third * 2);

    List<Object> binds = new ArrayList<>();
    String where = reviewWhere(scoped, binds);

    Record overall =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where r.replied) as replied,"
                + " count(*) filter (where not r.replied) as unreplied,"
                + " count(*) filter (where r.has_image) as with_image,"
                + " count(*) filter (where r.has_video) as with_video,"
                + " avg(r.rating) as avg_rating"
                + " from crm_product_review r" + where,
            binds.toArray());

    List<CrmProductReviewRatingBucket> distribution = ratingDistribution(scoped);
    List<CrmProductReviewTrendBucket> trend = trend(scoped, start, end);
    List<CrmProductReviewDimensionBucket> byShop =
        dimensionBuckets("SHOP", scoped, olderEnd, previousEnd);
    List<CrmProductReviewDimensionBucket> byProduct =
        dimensionBuckets("PRODUCT", scoped, olderEnd, previousEnd);
    List<CrmProductReviewDimensionBucket> byVariant =
        dimensionBuckets("VARIANT", scoped, olderEnd, previousEnd);

    List<CrmProductReviewDimensionBucket> allBuckets = new ArrayList<>();
    allBuckets.addAll(byShop);
    allBuckets.addAll(byProduct);
    allBuckets.addAll(byVariant);
    List<CrmProductReviewDecline> declining = new ArrayList<>();
    for (var bucket : allBuckets) {
      var decline = declineOf(bucket);
      if (decline != null) declining.add(decline);
    }
    declining.sort(Comparator.comparing(CrmProductReviewDecline::delta));
    if (declining.size() > MAX_DECLINES) {
      declining = new ArrayList<>(declining.subList(0, MAX_DECLINES));
    }

    return new CrmProductReviewStats(
        effectiveDays,
        start.toString(),
        end.toString(),
        overall.get("total", Long.class),
        overall.get("replied", Long.class),
        overall.get("unreplied", Long.class),
        overall.get("with_image", Long.class),
        overall.get("with_video", Long.class),
        overall.get("avg_rating", BigDecimal.class),
        distribution,
        trend,
        byShop,
        byProduct,
        byVariant,
        declining,
        "🔴 趋势按**平台评价时刻**分桶（不是登记时刻：集中补录老评价会造成假下滑）；"
            + "「下滑」= 近窗均分低于中窗均分，「连续下滑」= 近窗 < 中窗 < 远窗（三窗单调）；"
            + "三个维度分别列出，才能判断是产品问题还是履约问题。");
  }

  /** 星级分布（1..5 五档，没有评价的档位也列出来 —— 「0 条」本身是一条信息）。 */
  private List<CrmProductReviewRatingBucket> ratingDistribution(ReviewFilter filter) {
    List<Object> binds = new ArrayList<>();
    String where = reviewWhere(filter, binds);
    Map<Integer, Long> counts = new LinkedHashMap<>();
    for (int rating = 5; rating >= 1; rating--) counts.put(rating, 0L);
    for (Record row :
        sql.fetch(
            "select r.rating as rating, count(*) as cnt from crm_product_review r" + where
                + " group by r.rating",
            binds.toArray())) {
      counts.put(row.get("rating", Integer.class), row.get("cnt", Long.class));
    }
    List<CrmProductReviewRatingBucket> out = new ArrayList<>();
    counts.forEach((rating, count) -> out.add(new CrmProductReviewRatingBucket(rating, count)));
    return out;
  }

  /**
   * 趋势分桶。
   *
   * <p>🔴 空桶**补出来**（count=0、均分为 null）：只列有评价的桶会让「中间那段没评价」
   * 看起来像「数据丢了」，而那其实是一条信息。
   */
  private List<CrmProductReviewTrendBucket> trend(
      ReviewFilter filter, OffsetDateTime start, OffsetDateTime end) {
    long spanDays = Math.max(1, Duration.between(start, end).toDays());
    int bucketDays = (int) Math.max(1, (spanDays + TREND_BUCKETS - 1) / TREND_BUCKETS);

    List<Object> binds = new ArrayList<>();
    String where = reviewWhere(filter, binds);
    List<Object> queryBinds = new ArrayList<>();
    queryBinds.add(bucketDays);
    queryBinds.add(start);
    queryBinds.addAll(binds);
    Map<java.time.Instant, Record> byStart = new HashMap<>();
    for (Record row :
        sql.fetch(
            "select date_bin(make_interval(days => ?), r.platform_reviewed_at, ?::timestamptz)"
                + " as bucket_start, count(*) as cnt, avg(r.rating) as avg_rating"
                + " from crm_product_review r" + where
                + " group by 1 order by 1",
            queryBinds.toArray())) {
      byStart.put(row.get("bucket_start", OffsetDateTime.class).toInstant(), row);
    }

    List<CrmProductReviewTrendBucket> out = new ArrayList<>();
    OffsetDateTime cursor = start;
    while (cursor.isBefore(end)) {
      OffsetDateTime next = cursor.plusDays(bucketDays);
      Record row = byStart.get(cursor.toInstant());
      out.add(
          new CrmProductReviewTrendBucket(
              cursor.toString(),
              (next.isAfter(end) ? end : next).toString(),
              row == null ? 0L : row.get("cnt", Long.class),
              row == null ? null : row.get("avg_rating", BigDecimal.class)));
      cursor = next;
    }
    return out;
  }

  /**
   * 一个维度的评分汇总（店铺 / 商品 / 变体）。
   *
   * <p>🔴 三窗均分在**同一条 SELECT** 里用 {@code avg(...) filter (where ...)} 算出来 ——
   * 分三次查询会让「总数」与「均分」的口径漂移，而口径漂移的趋势比没有趋势更糟。
   */
  private List<CrmProductReviewDimensionBucket> dimensionBuckets(
      String dimension, ReviewFilter filter, OffsetDateTime olderEnd, OffsetDateTime previousEnd) {
    List<Object> binds = new ArrayList<>();
    String where = reviewWhere(filter, binds);

    // 每个维度的「键 / 标签 / 附带列」不同：店铺要带出 shop_id 与 platform_code，
    // 商品与变体则按**跨店铺**聚合（同一个变体在几家店都跌，才是变体本身的问题）。
    String keyExpr;
    String labelExpr;
    String extraSelect;
    String groupBy;
    switch (dimension) {
      case "SHOP" -> {
        keyExpr = "r.shop_id::text";
        labelExpr = "r.shop_name";
        extraSelect = ", r.shop_id as shop_id, r.shop_name as shop_name, r.platform_code as platform_code";
        groupBy = "r.shop_id, r.shop_name, r.platform_code";
      }
      case "PRODUCT" -> {
        keyExpr = "r.product_ref";
        labelExpr = "r.product_title";
        extraSelect = "";
        groupBy = "r.product_ref, r.product_title";
      }
      default -> {
        keyExpr = "r.variant_ref";
        labelExpr = "r.variant_label";
        extraSelect = "";
        groupBy = "r.variant_ref, r.variant_label";
      }
    }

    List<Object> ordered = new ArrayList<>();
    // SELECT 里的三个 filter 表达式（**先于 WHERE 出现**，绑定顺序必须照这个顺序）：
    //   avg_recent（近窗起点 = 中窗终点）· avg_older（远窗终点 = 中窗起点）·
    //   avg_prev（中窗起点、中窗终点）
    ordered.add(previousEnd);
    ordered.add(olderEnd);
    ordered.add(olderEnd);
    ordered.add(previousEnd);
    ordered.addAll(binds);
    ordered.add(MAX_DIMENSION_ROWS);

    String sqlText =
        "select "
            + keyExpr
            + " as dim_key, "
            + labelExpr
            + " as dim_label"
            + extraSelect
            + ", count(*) as cnt, count(*) filter (where not r.replied) as unreplied,"
            + " avg(r.rating) as avg_all,"
            + " avg(r.rating) filter (where r.platform_reviewed_at >= ?::timestamptz) as avg_recent,"
            + " avg(r.rating) filter (where r.platform_reviewed_at < ?::timestamptz) as avg_older,"
            + " avg(r.rating) filter (where r.platform_reviewed_at >= ?::timestamptz"
            + " and r.platform_reviewed_at < ?::timestamptz) as avg_prev"
            + " from crm_product_review r" + where
            + " group by "
            + groupBy
            + " order by avg_recent asc nulls last, cnt desc limit ?";

    List<CrmProductReviewDimensionBucket> out = new ArrayList<>();
    for (Record row : sql.fetch(sqlText, ordered.toArray())) {
      String key = nz(row.get("dim_key", String.class));
      if (key.isEmpty()) {
        // 没有该维度的评价（没填变体 / 没填商品）不占一档：空档不是「一个没有评价的变体」。
        continue;
      }
      BigDecimal recent = row.get("avg_recent", BigDecimal.class);
      BigDecimal previous = row.get("avg_prev", BigDecimal.class);
      BigDecimal delta = recent == null || previous == null ? null : recent.subtract(previous);
      out.add(
          new CrmProductReviewDimensionBucket(
              dimension,
              key,
              nz(row.get("dim_label", String.class)),
              "SHOP".equals(dimension) ? row.get("shop_id", UUID.class) : null,
              "SHOP".equals(dimension) ? nz(row.get("shop_name", String.class)) : null,
              "SHOP".equals(dimension) ? nz(row.get("platform_code", String.class)) : null,
              row.get("cnt", Long.class),
              row.get("unreplied", Long.class),
              row.get("avg_all", BigDecimal.class),
              recent,
              previous,
              row.get("avg_older", BigDecimal.class),
              delta));
    }
    return out;
  }

  /** 一个维度桶 → 一条「在下滑」的记录（不是下滑就返回 null）。 */
  private static CrmProductReviewDecline declineOf(CrmProductReviewDimensionBucket bucket) {
    if (bucket.delta() == null || bucket.delta().signum() >= 0) return null;
    BigDecimal recent = bucket.recentAverage();
    BigDecimal previous = bucket.previousAverage();
    BigDecimal older = bucket.olderAverage();
    // 「连续下滑」= 近窗 < 中窗 < 远窗（三窗单调），只有两窗可比时**不敢**说连续。
    boolean continuous =
        recent != null && previous != null && older != null && previous.compareTo(older) < 0;
    return new CrmProductReviewDecline(
        bucket.dimension(),
        bucket.key(),
        bucket.label(),
        bucket.count(),
        recent,
        previous,
        older,
        bucket.delta(),
        continuous);
  }

  // ================================================================ 单条详情

  /**
   * 单条评价详情 = 评价本体 + 关联订单上下文。
   *
   * <p>🔴 订单上下文**读不到时明确显示「不可用」并说明原因**，绝不显示空白 ——
   * 客服看到空白会以为这条评价没有关联订单，而真相可能是上游挂了。
   */
  public CrmProductReviewDetail detail(UUID reviewId, String bearer) {
    CrmProductReviewRow row = requireReview(reviewId);
    var context = orderContext.context(row.salesOrderNo(), bearer);
    return new CrmProductReviewDetail(row, orderContextView(context));
  }

  private static CrmProductReviewOrderContext orderContextView(
      ReviewOrderContextPort.OrderContext context) {
    return new CrmProductReviewOrderContext(
        context.available(),
        context.reason(),
        context.salesOrderNo(),
        context.orderNo(),
        context.orderStatus(),
        context.deliveredAt(),
        context.deliveryState(),
        context.carrierCode(),
        context.caseNo());
  }

  // ================================================================ 登记评价

  /**
   * 登记一条评价（人工登记）。
   *
   * <p>🔴 本端点是**平台同步没接通时唯一能进评价的入口**：它落的是真数据，
   * 可筛选、可导出、可参与评分统计。
   */
  @Transactional
  public CrmProductReviewRow create(
      CrmProductReviewCreateInput input, UUID actorId, String bearer) {
    ShopRef shop = requireShop(input.shopId(), bearer);
    OffsetDateTime platformReviewedAt = parseMoment(input.platformReviewedAt(), "平台评价时刻");
    String productRef = nz(input.productRef()).trim();
    String variantRef = nz(input.variantRef()).trim();
    if (productRef.isEmpty()) {
      throw bad("必须写明商品编码：没有商品维度，评分下滑就定位不到「是哪个商品出了问题」。");
    }
    if (variantRef.isEmpty()) {
      throw bad(
          "必须写明变体编码：蓝图里评价就带着变体，没有变体维度就定位不到「是哪个变体出了问题」。");
    }
    List<String> imageUrls = normalizeImages(input.imageUrls());
    String videoUrl = blankToNull(input.videoUrl());
    boolean replied = Boolean.TRUE.equals(input.replied());
    OffsetDateTime repliedAt = replied ? parseMoment(input.repliedAt(), "已回复时刻") : null;
    if (replied && repliedAt == null) {
      throw bad("标记为「已回复」时必须写明回复时刻：否则「已回复但不知道什么时候」永远查不清。");
    }
    String externalReviewId = blankToNull(input.externalReviewId());
    if (externalReviewId != null && existsByExternalId(shop, externalReviewId)) {
      throw conflict(
          "这条平台评价（"
              + externalReviewId
              + "）已经登记过了：同一店铺下的同一条平台评价只允许登记一次，"
              + "重复登记会让评分统计凭空多算一条。");
    }

    UUID id = UUID.randomUUID();
    OffsetDateTime moment = now();
    sql.execute(
        "insert into crm_product_review"
            + "(id,tenant_id,review_no,shop_id,shop_name,platform_code,source,external_review_id,"
            + " rating,title,content,product_ref,product_title,variant_ref,variant_label,"
            + " image_urls,video_url,has_image,has_video,buyer_ref,buyer_name,sales_order_no,"
            + " replied,replied_at,platform_reviewed_at,registered_at,note,created_by,"
            + " created_at,updated_at)"
            + " values (?,?,?,?,?,?,'MANUAL_ENTRY',?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?,"
            + "?,?::timestamptz,?::timestamptz,?::timestamptz,?,?,?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        nextReviewNo(),
        shop.id(),
        shop.name(),
        shop.platformCode(),
        externalReviewId,
        input.rating(),
        blankToNull(input.title()),
        blankToNull(input.content()),
        productRef,
        blankToNull(input.productTitle()),
        variantRef,
        blankToNull(input.variantLabel()),
        imagesJson(imageUrls),
        videoUrl,
        !imageUrls.isEmpty(),
        videoUrl != null,
        blankToNull(input.buyerRef()),
        blankToNull(input.buyerName()),
        blankToNull(input.salesOrderNo()),
        replied,
        repliedAt,
        platformReviewedAt,
        moment,
        blankToNull(input.note()),
        actorId,
        moment,
        moment);
    return requireReview(id);
  }

  // ================================================================ 平台评价同步（当前恒 409）

  /**
   * 平台评价同步。
   *
   * <p>🔴 当前**恒为 409**（平台评价适配器未交付）：同步请求**一行都不写** ——
   * 一个「点了同步就返回成功」的假通道会让运营以为评价已经进来了，
   * 而真相是「一条都没拉到」，那正是评价趋势失真的源头。
   */
  @Transactional
  public CrmProductReviewSyncResult sync(CrmProductReviewSyncInput input, String bearer) {
    ShopRef shop = requireShop(input.shopId(), bearer);
    var availability = platformReview.availability(shop.platformCode());
    if (!availability.available()) {
      throw conflict(availability.reason());
    }
    var fetched = platformReview.fetch(shop.platformCode(), shop.id(), bearer);
    if (!fetched.available()) {
      throw conflict(fetched.reason());
    }
    int synced = 0;
    for (var raw : fetched.reviews()) {
      if (insertSyncedReview(shop, raw)) synced++;
    }
    return new CrmProductReviewSyncResult(
        synced,
        true,
        "已从平台同步 " + synced + " 条评价。",
        "同步进来的评价与人工登记的评价落在**同一张表**里，"
            + "所以评分统计与筛选面对两者一视同仁。");
  }

  /**
   * 落一条同步来的评价。
   *
   * @return true 表示新落了一条；false 表示**已存在**（幂等跳过，不是失败）。
   */
  private boolean insertSyncedReview(ShopRef shop, PlatformReviewPort.RawReview raw) {
    String externalReviewId = blankToNull(raw.externalReviewId());
    if (externalReviewId != null && existsByExternalId(shop, externalReviewId)) {
      return false;
    }
    List<String> imageUrls = normalizeImages(raw.imageUrls());
    String videoUrl = blankToNull(raw.videoUrl());
    UUID id = UUID.randomUUID();
    OffsetDateTime moment = now();
    sql.execute(
        "insert into crm_product_review"
            + "(id,tenant_id,review_no,shop_id,shop_name,platform_code,source,external_review_id,"
            + " rating,title,content,product_ref,product_title,variant_ref,variant_label,"
            + " image_urls,video_url,has_image,has_video,buyer_ref,buyer_name,sales_order_no,"
            + " replied,replied_at,platform_reviewed_at,registered_at,note,created_by,"
            + " created_at,updated_at)"
            + " values (?,?,?,?,?,?,'PLATFORM_SYNC',?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?,"
            + "false,null,?::timestamptz,?::timestamptz,null,null,?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        nextReviewNo(),
        shop.id(),
        shop.name(),
        shop.platformCode(),
        externalReviewId,
        raw.rating(),
        blankToNull(raw.title()),
        blankToNull(raw.content()),
        blankToNull(raw.productRef()),
        blankToNull(raw.productTitle()),
        blankToNull(raw.variantRef()),
        blankToNull(raw.variantLabel()),
        imagesJson(imageUrls),
        videoUrl,
        !imageUrls.isEmpty(),
        videoUrl != null,
        blankToNull(raw.buyerRef()),
        blankToNull(raw.buyerName()),
        blankToNull(raw.salesOrderNo()),
        raw.platformReviewedAt() == null ? moment : raw.platformReviewedAt(),
        moment,
        moment,
        moment);
    return true;
  }

  // ================================================================ 导出

  /** 导出结果：CSV 文本 + 行数 + 是否被截断（与 trade 采购单导出同一套口径）。 */
  public record CrmProductReviewExport(String csv, int rowCount, boolean truncated) {}

  /**
   * 导出当前筛选结果 CSV。
   *
   * <p>与列表**同一套筛选条件** —— 导出的不是一份全量。
   *
   * <p>🔴 超过 {@link #MAX_EXPORT_ROWS} 行会**如实截断**，并在响应头
   * {@code X-Export-Truncated} 与 {@code X-Export-Row-Count} 上报出来：
   * 悄悄少导一部分、让运营以为拿到了全部，比报错更糟。
   */
  public CrmProductReviewExport exportCsv(ReviewFilter filter) {
    List<Object> binds = new ArrayList<>();
    String where = reviewWhere(filter, binds);
    List<Object> queryBinds = new ArrayList<>(binds);
    queryBinds.add(MAX_EXPORT_ROWS + 1);
    List<Record> records =
        sql.fetch(
            reviewColumns() + " from crm_product_review r" + where
                + " order by r.platform_reviewed_at desc, r.id limit ?",
            queryBinds.toArray());
    boolean truncated = records.size() > MAX_EXPORT_ROWS;
    List<Record> kept = truncated ? records.subList(0, MAX_EXPORT_ROWS) : records;

    StringBuilder csv = new StringBuilder();
    csv.append(
        "评价号,平台,店铺,星级,是否已回复,平台评价时刻,登记时刻,关联订单号,买家标识,"
            + "商品编码,商品名称,变体编码,变体名称,含图,含视频,标题,内容,来源,平台评价id,"
            + "图片地址,备注\n");
    for (Record row : kept) {
      List<String> images = parseImages(row.get("image_urls", String.class));
      List<String> cells =
          List.of(
              csvCell(row.get("review_no", String.class)),
              csvCell(row.get("platform_code", String.class)),
              csvCell(row.get("shop_name", String.class)),
              String.valueOf(row.get("rating", Integer.class)),
              Boolean.TRUE.equals(row.get("replied", Boolean.class)) ? "已回复" : "未回复",
              csvCell(momentText(row.get("platform_reviewed_at", OffsetDateTime.class))),
              csvCell(momentText(row.get("registered_at", OffsetDateTime.class))),
              csvCell(row.get("sales_order_no", String.class)),
              csvCell(row.get("buyer_ref", String.class)),
              csvCell(row.get("product_ref", String.class)),
              csvCell(row.get("product_title", String.class)),
              csvCell(row.get("variant_ref", String.class)),
              csvCell(row.get("variant_label", String.class)),
              Boolean.TRUE.equals(row.get("has_image", Boolean.class)) ? "是" : "否",
              Boolean.TRUE.equals(row.get("has_video", Boolean.class)) ? "是" : "否",
              csvCell(row.get("title", String.class)),
              csvCell(row.get("content", String.class)),
              csvCell(row.get("source", String.class)),
              csvCell(row.get("external_review_id", String.class)),
              csvCell(String.join(" | ", images)),
              csvCell(row.get("note", String.class)));
      csv.append(String.join(",", cells)).append('\n');
    }
    return new CrmProductReviewExport(csv.toString(), kept.size(), truncated);
  }

  // ================================================================ 过滤条件

  /**
   * 评价列表的筛选条件。
   *
   * <p>刻意做成一个值对象：列表、统计与导出**共用同一套口径** ——
   * 「页面上看到的」与「导出的」必须是同一批评价，否则导出会被当成另一份事实。
   */
  public record ReviewFilter(
      UUID shopId,
      String platformCode,
      Integer rating,
      Boolean hasImage,
      Boolean hasVideo,
      Boolean unreplied,
      String productRef,
      String variantRef,
      String salesOrderNo,
      String buyerRef,
      String keyword,
      OffsetDateTime from,
      OffsetDateTime to) {

    /** 换一个时间窗口（统计用）。 */
    ReviewFilter withWindow(OffsetDateTime newFrom, OffsetDateTime newTo) {
      return new ReviewFilter(
          shopId,
          platformCode,
          rating,
          hasImage,
          hasVideo,
          unreplied,
          productRef,
          variantRef,
          salesOrderNo,
          buyerRef,
          keyword,
          newFrom,
          newTo);
    }
  }

  /** 从请求参数装配筛选条件（非法值一律 400，不静默忽略）。 */
  public ReviewFilter filter(
      UUID shopId,
      String platformCode,
      Integer rating,
      Boolean hasImage,
      Boolean hasVideo,
      Boolean unreplied,
      String productRef,
      String variantRef,
      String salesOrderNo,
      String buyerRef,
      String keyword,
      String reviewedFrom,
      String reviewedTo) {
    if (rating != null && (rating < 1 || rating > 5)) {
      throw bad("星级只能是 1 到 5 之间，收到的是 " + rating + "。");
    }
    return new ReviewFilter(
        shopId,
        blankToNull(platformCode),
        rating,
        hasImage,
        hasVideo,
        unreplied,
        blankToNull(productRef),
        blankToNull(variantRef),
        blankToNull(salesOrderNo),
        blankToNull(buyerRef),
        blankToNull(keyword),
        optionalMoment(reviewedFrom, "平台评价时刻起点"),
        optionalMoment(reviewedTo, "平台评价时刻终点"));
  }

  /** 列表 / 统计 / 导出共用的 WHERE 片段与绑定。 */
  private String reviewWhere(ReviewFilter filter, List<Object> binds) {
    StringBuilder where = new StringBuilder(" where r.tenant_id = ?");
    binds.add(currentTenant());
    if (filter.shopId() != null) {
      where.append(" and r.shop_id = ?");
      binds.add(filter.shopId());
    }
    if (filter.platformCode() != null) {
      where.append(" and r.platform_code = ?");
      binds.add(filter.platformCode());
    }
    if (filter.rating() != null) {
      where.append(" and r.rating = ?");
      binds.add(filter.rating());
    }
    if (filter.hasImage() != null) {
      where.append(" and r.has_image = ?");
      binds.add(filter.hasImage());
    }
    if (filter.hasVideo() != null) {
      where.append(" and r.has_video = ?");
      binds.add(filter.hasVideo());
    }
    if (filter.unreplied() != null) {
      where.append(filter.unreplied() ? " and not r.replied" : " and r.replied");
    }
    if (filter.productRef() != null) {
      where.append(" and r.product_ref = ?");
      binds.add(filter.productRef());
    }
    if (filter.variantRef() != null) {
      where.append(" and r.variant_ref = ?");
      binds.add(filter.variantRef());
    }
    if (filter.salesOrderNo() != null) {
      where.append(" and r.sales_order_no = ?");
      binds.add(filter.salesOrderNo());
    }
    if (filter.buyerRef() != null) {
      where.append(" and r.buyer_ref = ?");
      binds.add(filter.buyerRef());
    }
    if (filter.keyword() != null) {
      where.append(
          " and (r.content ilike ? escape '\\' or r.title ilike ? escape '\\'"
              + " or r.sales_order_no ilike ? escape '\\'"
              + " or r.external_review_id ilike ? escape '\\'"
              + " or r.review_no ilike ? escape '\\'"
              + " or r.buyer_ref ilike ? escape '\\'"
              + " or r.variant_ref ilike ? escape '\\'"
              + " or r.product_ref ilike ? escape '\\')");
      String like = "%" + escapeLike(filter.keyword()) + "%";
      for (int i = 0; i < 8; i++) binds.add(like);
    }
    if (filter.from() != null) {
      where.append(" and r.platform_reviewed_at >= ?::timestamptz");
      binds.add(filter.from());
    }
    if (filter.to() != null) {
      where.append(" and r.platform_reviewed_at < ?::timestamptz");
      binds.add(filter.to());
    }
    return where.toString();
  }

  // ================================================================ 行映射与小工具

  private static final String REVIEW_COLUMNS =
      "r.id,r.review_no,r.shop_id,r.shop_name,r.platform_code,r.source,r.external_review_id,"
          + " r.rating,r.title,r.content,r.product_ref,r.product_title,r.variant_ref,"
          + " r.variant_label,r.image_urls,r.video_url,r.has_image,r.has_video,r.buyer_ref,"
          + " r.buyer_name,r.sales_order_no,r.replied,r.replied_at,r.platform_reviewed_at,"
          + " r.registered_at,r.note,r.created_by,r.created_at,r.updated_at";

  private static String reviewColumns() {
    return "select " + REVIEW_COLUMNS;
  }

  private static CrmProductReviewRow review(Record row) {
    return new CrmProductReviewRow(
        row.get("id", UUID.class),
        nz(row.get("review_no", String.class)),
        row.get("shop_id", UUID.class),
        nz(row.get("shop_name", String.class)),
        nz(row.get("platform_code", String.class)),
        nz(row.get("source", String.class)),
        row.get("external_review_id", String.class),
        row.get("rating", Integer.class),
        row.get("title", String.class),
        row.get("content", String.class),
        row.get("product_ref", String.class),
        row.get("product_title", String.class),
        row.get("variant_ref", String.class),
        row.get("variant_label", String.class),
        parseImages(row.get("image_urls", String.class)),
        row.get("video_url", String.class),
        Boolean.TRUE.equals(row.get("has_image", Boolean.class)),
        Boolean.TRUE.equals(row.get("has_video", Boolean.class)),
        row.get("buyer_ref", String.class),
        row.get("buyer_name", String.class),
        row.get("sales_order_no", String.class),
        Boolean.TRUE.equals(row.get("replied", Boolean.class)),
        momentText(row.get("replied_at", OffsetDateTime.class)),
        momentText(row.get("platform_reviewed_at", OffsetDateTime.class)),
        momentText(row.get("registered_at", OffsetDateTime.class)),
        row.get("note", String.class),
        row.get("created_by", UUID.class),
        momentText(row.get("created_at", OffsetDateTime.class)),
        momentText(row.get("updated_at", OffsetDateTime.class)));
  }

  private CrmProductReviewRow requireReview(UUID reviewId) {
    var row =
        sql.fetchOne(
            reviewColumns() + " from crm_product_review r where r.tenant_id = ? and r.id = ?",
            currentTenant(),
            reviewId);
    if (row == null) {
      throw notFound("评价不存在（或不属于当前租户）：" + reviewId);
    }
    return review(row);
  }

  private boolean existsByExternalId(ShopRef shop, String externalReviewId) {
    return sql.fetchOne(
            "select count(*) from crm_product_review"
                + " where tenant_id = ? and platform_code = ? and shop_id = ? and external_review_id = ?",
            currentTenant(),
            shop.platformCode(),
            shop.id(),
            externalReviewId)
        .get(0, Long.class)
        > 0;
  }

  /** 上游店铺的最小引用（id / 名称 / 平台）。 */
  private record ShopRef(UUID id, String name, String platformCode) {}

  /** 店铺必须在上游 channel 的真实清单里（读不到上游一律 503，绝不凭空建一个店铺）。 */
  private ShopRef requireShop(UUID shopId, String bearer) {
    for (var shop : shops.shops(bearer)) {
      if (shop.id().equals(shopId)) {
        return new ShopRef(shop.id(), nz(shop.name()), nz(shop.platformCode()));
      }
    }
    throw notFound(
        "店铺不存在（或不在当前租户的店铺清单里）："
            + shopId
            + "。评价必须挂在真实店铺上 —— 否则按店铺汇总的评分趋势会散架。");
  }

  /** 评价号：PR-yyyyMMdd-000001（序列单独授权，见 V294 授权段）。 */
  private String nextReviewNo() {
    var row =
        sql.fetchOne(
            "select nextval('crm_product_review_no_seq') as seq,"
                + " to_char(now() at time zone 'utc','YYYYMMDD') as day");
    long seq = row.get("seq", Long.class);
    String day = row.get("day", String.class);
    return "PR-" + day + "-" + String.format("%06d", seq);
  }

  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  /**
   * 解析带时区偏移量的时刻。
   *
   * <p>刻意用 {@code OffsetDateTime.parse}：用 {@code Timestamp.valueOf(LocalDateTime)} 会在东八区
   * **静默偏 −28800 秒**，写进去的评价时刻与运营填的差 8 小时，而页面看上去一切正常。
   */
  private static OffsetDateTime parseMoment(String value, String label) {
    String text = nz(value).trim();
    if (text.isEmpty()) throw bad("必须指定" + label + "，例如 2026-10-10T08:00:00+08:00");
    try {
      return OffsetDateTime.parse(text);
    } catch (java.time.format.DateTimeParseException e) {
      throw bad(
          label
              + "「"
              + text
              + "」格式不对：请带时区偏移量，例如 2026-10-10T08:00:00+08:00。"
              + "缺偏移量的时间会在东八区静默偏 8 小时，页面却看不出异常。");
    }
  }

  /** 可选时刻：空白即「不筛」。 */
  private static OffsetDateTime optionalMoment(String value, String label) {
    String text = nz(value).trim();
    return text.isEmpty() ? null : parseMoment(text, label);
  }

  /** 图片地址：去空白、去空串、最多 20 条。 */
  private static List<String> normalizeImages(List<String> raw) {
    List<String> out = new ArrayList<>();
    if (raw == null) return out;
    for (String item : raw) {
      String value = blankToNull(item);
      if (value == null) continue;
      if (value.length() > 500) {
        throw bad("图片地址过长（最多 500 个字符）：" + value.substring(0, 60) + "…");
      }
      out.add(value);
      if (out.size() >= 20) break;
    }
    return out;
  }

  /** 图片地址序列化成 jsonb 文本（写入时必须显式 {@code ?::jsonb}）。 */
  private String imagesJson(List<String> images) {
    ArrayNode array = json.createArrayNode();
    for (String image : images) array.add(image);
    try {
      return json.writeValueAsString(array);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 从 jsonb 文本读回图片地址；读不出来时返回空列表（不是「有图但地址丢了」）。 */
  private static List<String> parseImages(String raw) {
    if (raw == null || raw.isBlank()) return List.of();
    try {
      JsonNode node = JSON.readTree(raw);
      List<String> out = new ArrayList<>();
      if (node != null && node.isArray()) {
        node.forEach(item -> out.add(item.asText()));
      }
      return out;
    } catch (Exception e) {
      return List.of();
    }
  }

  /** 时刻的展示文本（null 安全）。 */
  private static String momentText(OffsetDateTime value) {
    return value == null ? null : value.toString();
  }

  /** CSV 单元格：空值写空串，含分隔符 / 引号 / 换行时加引号。 */
  private static String csvCell(String value) {
    String text = nz(value);
    if (text.isEmpty()) return "";
    if (text.indexOf(',') < 0
        && text.indexOf('"') < 0
        && text.indexOf('\n') < 0
        && text.indexOf('\r') < 0) {
      return text;
    }
    return "\"" + csvEscape(text) + "\"";
  }

  /** CSV 转义：双引号加倍。 */
  private static String csvEscape(String value) {
    return nz(value).replace("\"", "\"\"");
  }

  /** LIKE 里的通配符要转义，否则用户搜 {@code %} 会命中全表。 */
  private static String escapeLike(String value) {
    return nz(value).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
