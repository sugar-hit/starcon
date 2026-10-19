package com.starcon.channel;

import static com.starcon.channel.ListingPerformanceModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 4.5.1.1 Listing 表现。
 *
 * <p>蓝图：「单个 listing 的表现明细：曝光、点击、转化率、销量、销售额、Session、退货率、评分变化，支持时间范围对比。输入：{@code listing_id}；平台表现数据同步结果；时间范围。输出：表现指标 + 趋势图；下钻到 13 产品表现。关系：上游 = 02 同步的平台流量数据；下游 = 13 产品表现、14 广告（关联投放）。<b>表现数据来自平台而非内部订单</b>，与内部订单口径不同，展示时需标注来源。」
 *
 * <p>🔴 本类的四个核心不变量，每一个都有集成测试逐条钉住：
 *
 * <ol>
 *   <li><b>🔴🔴 数据来自平台，不是内部订单</b> —— 本类**只接收**平台回报（ingest 端点），
 *       <b>绝不</b>去 trade 的订单表聚合（跨服务查表是 AGENTS.md 明令禁止的）。每个响应都带
 *       {@code dataSource='PLATFORM_REPORTED'} 与 {@code sourceNote}，前端把它显示在指标区顶部
 *       —— 蓝图加粗句「展示时需标注来源」是**契约级**的硬要求。
 *   <li><b>🔴🔴 比率一律现算，分母为 0 时是 null</b> —— 转化率 / 退货率 / 点击率由 SQL 按固定
 *       分子分母算出（区间内<b>分子合计 ÷ 分母合计</b>，不是日均值的平均）。分母为 0 时返回
 *       <b>null</b>，不是 0 也不是 100：报 0 会被读成「转化率 0%」，那是结论不是缺口。
 *   <li><b>🔴 幂等与覆盖是两件事</b> —— 批次级幂等键（{@code listing_performance_sync}）保证
 *       同一个批次重复投递**整批不再落库**；平台修正同一天的报表用<b>新</b>的批次键推进来，
 *       同一天那一行被覆盖、{@code revision} 递增。两个机制都在 DB 层有约束钉着。
 *   <li><b>🔴 未交付的下游显式声明</b> —— 13 产品表现、14 广告（关联投放）都没交付，
 *       {@link #capabilities()} 里 available=false + ownerModule + impact，
 *       前端显示「未接入」，<b>不显示假数据</b>。
 * </ol>
 *
 * <p>🔴 时间口径：统计日（{@code stat_date}）是<b>平台自然日</b>（{@code date}），
 * 与内部下单日不是一回事；同步批次的口径区间是 {@code timestamptz}，用 {@code OffsetDateTime}
 * 绑定、values 位置写 {@code ?::timestamptz} —— 用 {@code Timestamp.valueOf(LocalDateTime...)}
 * 会静默偏 −28800s，趋势会整条错位（AGENTS.md 与 4.1.1.1 实测踩过）。
 */
@Service
public class ListingPerformanceService {

  /** 🔴 区间聚合的基础列（分子/分母）+ 三个现算比率。比率不落表，落表就会与事实漂移。 */
  private static final String AGG =
      """
      select
        coalesce(sum(impressions),0)   as impressions,
        coalesce(sum(clicks),0)        as clicks,
        coalesce(sum(sessions),0)      as sessions,
        coalesce(sum(units_ordered),0) as units_ordered,
        coalesce(sum(sales_amount),0)  as sales_amount,
        coalesce(sum(returns_count),0) as returns_count,
        coalesce(sum(return_amount),0) as return_amount,
        count(*)                       as snapshot_count,
        count(rating)                  as rated_count,
        max(currency)                  as currency,
        case when sum(impressions) <> 0
             then round(sum(clicks) / sum(impressions), 6) else null end as ctr,
        case when sum(sessions) <> 0
             then round(sum(units_ordered) / sum(sessions), 6) else null end as conversion_rate,
        case when sum(units_ordered) <> 0
             then round(sum(returns_count) / sum(units_ordered), 6) else null end as return_rate,
        case when count(rating) >= 2
             then round(
               (array_agg(rating order by stat_date desc) filter (where rating is not null))[1]
               - (array_agg(rating order by stat_date asc)  filter (where rating is not null))[1], 4)
             else null end as rating_change,
        (array_agg(rating order by stat_date desc) filter (where rating is not null))[1] as rating_last,
        (array_agg(rating_count order by stat_date desc) filter (where rating_count is not null))[1]
          as rating_count_last
      from listing_performance_snapshot
      where tenant_id=? and listing_id=? and stat_date >= ?::date and stat_date <= ?::date
      """;

  private final DSLContext sql;
  private final ObjectMapper json;

  public ListingPerformanceService(DSLContext sql, ObjectMapper json) {
    this.sql = sql;
    this.json = json;
  }

  // ==================================================================
  // 小工具（照抄 ListingStatusSyncService 的同名写法）
  // ==================================================================

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static String blankToNull(String value) {
    String trimmed = text(value);
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "不存在");
  }

  private static ResponseStatusException unprocessable(String message) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private UUID currentTenant() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(String.valueOf(raw));
  }

  private String writeJson(Object value) {
    try {
      return json.writeValueAsString(value == null ? Map.of() : value);
    } catch (Exception e) {
      return "{}";
    }
  }

  private static int clampSize(int size) {
    if (size <= 0) {
      return 50;
    }
    return Math.min(size, 200);
  }

  private static UUID parseId(String raw) {
    try {
      return UUID.fromString(text(raw));
    } catch (RuntimeException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id 格式不正确");
    }
  }

  /** 统计日解析：只认 {@code yyyy-MM-dd}。 */
  private static LocalDate parseDate(String raw, String label) {
    String value = text(raw);
    if (value.isEmpty()) {
      return null;
    }
    try {
      return LocalDate.parse(value);
    } catch (RuntimeException e) {
      throw badRequest(label + "必须是 yyyy-MM-dd 格式的日期（收到「" + value + "」）");
    }
  }

  private static BigDecimal dec(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 🔴 逐维度能力声明。
   *
   * <p>本模块在本仓能**真实交付**的是「平台表现数据的接收与入库 + 区间指标聚合 +
   * 时间范围对比 + 趋势序列 + 数据来源标注」。
   *
   * <p>它<b>不能</b>在本批真实交付的有四段，逐条在下面声明而不是假装具备：
   * 13 产品表现、14 广告关联投放、02 的真实平台流量抓取调度器、09 的内部订单口径对账。
   */
  public ListingPerformanceCapabilities capabilities() {
    return new ListingPerformanceCapabilities(
        List.of(
            new ListingPerformanceCapabilityDimension(
                "platform_performance_ingest",
                "🔴 接收平台表现数据同步结果并入库：一批 = 一个有范围、有通道、有成败的同步，"
                    + "内含按平台统计日的快照明细；批次级幂等键保证重复投递整批不再落库",
                true, "listing_performance_sync + listing_performance_snapshot（V203）", "",
                "幂等键让「平台重投同一批」与「平台修正同一天」变成两件可分辨的事："
                    + "前者整批跳过，后者覆盖该日并把覆盖次数 +1"),
            new ListingPerformanceCapabilityDimension(
                "performance_snapshot",
                "🔴 平台口径的表现快照：曝光 / 点击 / Session / 销量 / 销售额 / 退货 / 评分 / 评价数，"
                    + "按平台统计日一行",
                true, "listing_performance_snapshot（V203 新表）", "",
                "「同一天同一个数」是 UNIQUE(tenant_id, listing_id, stat_date, source) 的结构事实，"
                    + "不靠服务层自觉"),
            new ListingPerformanceCapabilityDimension(
                "metric_aggregation",
                "🔴 区间聚合：曝光 / 点击 / 点击率 / Session / 销量 / 销售额 / 转化率 / "
                    + "退货件数 / 退货金额 / 退货率 / 评分 / 评分变化",
                true, "listing_performance_snapshot + 服务层现算的比率", "",
                "比率一律**区间合计的分子 ÷ 区间合计的分母**（不是日均值的平均），"
                    + "分母为 0 时是 null —— 报 0 会被读成「转化率 0%」，那是结论不是缺口"),
            new ListingPerformanceCapabilityDimension(
                "period_comparison",
                "🔴 时间范围对比：本期 vs 上期，逐指标给出变化量与变化率；"
                    + "上期不给时默认取紧邻的等长区间",
                true, "listing_performance_snapshot + 变化率现算", "",
                "上期为 0 时变化率是 null（不是 0、不是 +∞）："
                    + "「上期是 0」与「本期没变」是两件事"),
            new ListingPerformanceCapabilityDimension(
                "trend_series",
                "🔴 趋势序列：按日 / 周 / 月归集，没有数据时是**空数组**而不是假曲线",
                true, "listing_performance_snapshot + date_trunc 归集", "",
                "趋势按**平台统计日**归集（平台自然日），与内部下单日不是一回事"),
            new ListingPerformanceCapabilityDimension(
                "rating_change",
                "🔴 评分变化：区间内「最后一个有评分的快照值 − 第一个有评分的快照值」；"
                    + "有评分的快照不足 2 个时是 null",
                true, "listing_performance_snapshot.rating", "",
                "只有一个评分点时谈不上「变化」，硬算成 0 是在编数据"),
            new ListingPerformanceCapabilityDimension(
                "data_source_labeling",
                "🔴 数据来源标注：每个响应都带 dataSource='PLATFORM_REPORTED' 与来源说明，"
                    + "前端必须把它显示在指标区顶部",
                true, "服务层常量 DATA_SOURCE / SOURCE_NOTE", "",
                "蓝图加粗句「表现数据来自平台而非内部订单……展示时需标注来源」——"
                    + "不标注就会有人把平台销量与内部订单行数当成一回事"),
            new ListingPerformanceCapabilityDimension(
                "product_performance_drilldown",
                "🔴 下钻到**13 产品表现**（按 SPU / 内部 SKU 汇总的表现分析）",
                false, "—", "13 产品表现与利润分析（整卷未交付）",
                "本模块只看**单个 listing** 的表现，跨商品的汇总分析（含内部成本与利润口径）"
                    + "属 13 卷；本模块**不假装**给出这个下钻，页面上显示「未接入」并写明归属"),
            new ListingPerformanceCapabilityDimension(
                "ad_attribution",
                "🔴 **14 广告（关联投放）**：把表现与广告投放关联起来",
                false, "—", "14 广告与增长（整卷未交付）",
                "广告带来的流量与花费不在平台回报的日表现里（也不在 04 任何表里），"
                    + "本模块**不**用「曝光里有多少来自广告」这种猜出来的口径替代；"
                    + "关联投放交付后由它来读本模块的表现数据"),
            new ListingPerformanceCapabilityDimension(
                "real_platform_traffic_sync",
                "🔴 真实平台流量抓取的**调度器**（定时去平台拉表现报表的那个任务）",
                false, "—", "02 渠道与店铺（同步运行时）",
                "本模块提供**入库端点**（通道 WEBHOOK / POLL / API 都被记下来），"
                    + "但**没有**那个定时调度器 —— 平台限流与店铺配额由 02 统一裁决，本模块不自建轮询"),
            new ListingPerformanceCapabilityDimension(
                "internal_order_reconciliation",
                "🔴 平台表现与**内部订单口径的对账**（两套数为什么对不上）",
                false, "—", "09 订单与履约系统",
                "平台销量与内部订单行数**天然对不上**（平台按平台自然日归集、含取消与部分退款；"
                    + "内部订单按本系统单据归集）。本模块跨服务不读 09 的表，也**不**伪造一个"
                    + "「换算系数」把两套数硬凑成一个 —— 那会让运营按错误的数做选品决策")),
        SOURCES,
        INGEST_CHANNELS,
        GRANULARITIES,
        "曝光 impressions（平台回报，区间合计）· 点击 clicks（平台回报，区间合计）· "
            + "点击率 ctr = 点击 ÷ 曝光 · Session sessions（平台回报，区间合计）· "
            + "销量 unitsOrdered（**平台口径，非内部订单**，区间合计）· "
            + "销售额 salesAmount（**平台口径，非内部订单**，区间合计）· "
            + "转化率 conversionRate = 销量 ÷ Session · "
            + "退货件数 returnsCount / 退货金额 returnAmount（平台回报，区间合计）· "
            + "退货率 returnRate = 退货件数 ÷ 销量 · "
            + "评分 rating（区间内最后一个有评分的快照值）· "
            + "评分变化 ratingChange = 期末评分 − 期初评分（有评分的快照 ≥ 2 个时才有值）。"
            + "所有比率一律由服务端现算，**区间合计的分子 ÷ 区间合计的分母**（不是日均值的平均）；"
            + "**分母为 0 时是 null**（不是 0、也不是 100）。前端原样显示，不要自己重算。",
        SOURCE_NOTE,
        "上游 = 02 同步的平台流量数据（真实抓取调度器归 02，本模块只提供入库端点）· "
            + "下游 = 13 产品表现（整卷未交付）、14 广告关联投放（整卷未交付）—— "
            + "两段都在本能力表里 available=false + ownerModule，页面上显示「未接入」。"
            + "本模块**无任何内部订单派生的数字**：销量与销售额是平台回报口径，"
            + "与 09 的内部订单口径**不可互相替代，也不可相加**。");
  }

  // ==================================================================
  // 🔴 回流入库
  // ==================================================================

  /**
   * 🔴🔴 接收一次平台表现数据同步结果。
   *
   * <p>五件事，逐条都有理由：
   *
   * <ol>
   *   <li><b>定位商品</b>：给 listingId 就用它；给 (shopCode, externalItemId) 就按那对定位。
   *       两者都定位不到 ⇒ 422（平台报的是谁的表现都���不知道的话，这条数据没有意义）。
   *   <li><b>幂等</b>：同一个 {@code idempotencyKey} 再次投递 ⇒ <b>整批不再落库</b>，
   *       直接返回既有那条同步记录并把 {@code replayed} 置 true。
   *   <li><b>校验</b>：负数 / 缺统计日 ⇒ 整批拒绝（422），**不落半批**。
   *   <li><b>落快照</b>：统计日落在声明区间外的 ⇒ <b>跳过并逐条写明原因</b>（不静默丢弃）；
   *       区间内的按 (listing, 统计日, 来源) 覆盖写入，{@code revision} 递增。
   *   <li><b>回执</b>：真的落了几条、跳过几条、是不是重复投递，逐条写回。
   * </ol>
   */
  public ListingPerformanceIngestResult ingest(IngestPerformanceRequest request, UUID actorId) {
    UUID tenant = currentTenant();

    String source = text(request.source()).toUpperCase(Locale.ROOT);
    if (source.isEmpty()) {
      source = "PLATFORM";
    }
    if (!SOURCES.contains(source)) {
      throw unprocessable(
          "未知的来源「" + text(request.source()) + "」。只认这两条：" + String.join(" / ", SOURCES));
    }
    String channel = text(request.ingestChannel()).toUpperCase(Locale.ROOT);
    if (channel.isEmpty()) {
      channel = "API";
    }
    if (!INGEST_CHANNELS.contains(channel)) {
      throw unprocessable(
          "未知的通道「" + text(request.ingestChannel()) + "」。只认这三条："
              + String.join(" / ", INGEST_CHANNELS));
    }
    String idempotencyKey = text(request.idempotencyKey());
    if (idempotencyKey.isEmpty()) {
      throw unprocessable(
          "必须给出幂等键（idempotencyKey）—— 平台重投同一批时靠它整批跳过，"
              + "而平台修正同一天时用新键。没有它就分不清「重投」与「修正」");
    }
    if (request.periodEnd().isBefore(request.periodStart())) {
      throw unprocessable("口径区间的终点不能早于起点（" + request.periodEnd() + " < "
          + request.periodStart() + "）");
    }
    if (request.statTo().isBefore(request.statFrom())) {
      throw unprocessable("统计日区间的终点不能早于起点（" + request.statTo() + " < "
          + request.statFrom() + "）");
    }

    // ---- 定位商品
    Record listing = resolveListing(tenant, request);
    if (listing == null) {
      throw unprocessable(
          "定位不到商品：请给 listingId，或给 (店铺编码 shopCode + 平台商品 ID externalItemId)。"
              + "平台报的是谁的表现我们都不知道的话，这批数据挂不到任何人身上");
    }
    UUID listingId = listing.get("id", UUID.class);

    // ---- 🔴 幂等：同一个 key 再次投递 ⇒ 整批不再落库
    Record existing =
        sql.fetchOne(
            "select id,status,received_count,skipped_count,received_at from listing_performance_sync"
                + " where tenant_id=? and idempotency_key=?",
            tenant,
            idempotencyKey);
    if (existing != null) {
      return new ListingPerformanceIngestResult(
          existing.get("id", UUID.class),
          listingId,
          existing.get("status", String.class),
          intOf(existing, "received_count"),
          appliedCountOf(existing),
          intOf(existing, "skipped_count"),
          true,
          idempotencyKey,
          List.of(),
          existing.get("received_at", OffsetDateTime.class));
    }

    // ---- 校验并分流：区间内 = 落库；区间外 = 跳过（逐条写明原因）
    List<PerformanceSnapshotInput> applicable = new ArrayList<>();
    List<Map<String, String>> skipped = new ArrayList<>();
    for (PerformanceSnapshotInput input : request.snapshots()) {
      if (input.statDate() == null) {
        throw unprocessable("每一条快照都必须给出统计日（statDate）");
      }
      requireNonNegative(input.impressions(), "impressions", input.statDate());
      requireNonNegative(input.clicks(), "clicks", input.statDate());
      requireNonNegative(input.sessions(), "sessions", input.statDate());
      requireNonNegative(input.unitsOrdered(), "unitsOrdered", input.statDate());
      requireNonNegative(input.salesAmount(), "salesAmount", input.statDate());
      requireNonNegative(input.returnsCount(), "returnsCount", input.statDate());
      requireNonNegative(input.returnAmount(), "returnAmount", input.statDate());

      if (input.statDate().isBefore(request.statFrom()) || input.statDate().isAfter(request.statTo())) {
        Map<String, String> reason = new LinkedHashMap<>();
        reason.put("statDate", input.statDate().toString());
        reason.put("reason", "落在声明的统计日区间 [" + request.statFrom() + " ~ " + request.statTo()
            + "] 之外，本批不入库");
        skipped.add(reason);
        continue;
      }
      applicable.add(input);
    }

    // ---- 落同步批次（先落，��照它）
    UUID syncId = Ids.next();
    Map<String, Object> detail = new LinkedHashMap<>();
    if (request.detail() != null) {
      detail.putAll(request.detail());
    }
    detail.put("skipped", skipped);

    sql.execute(
        """
        insert into listing_performance_sync
          (id,tenant_id,listing_id,source,ingest_channel,period_start,period_end,
           stat_from,stat_to,received_count,skipped_count,status,idempotency_key,detail,note,
           received_at,created_at,updated_at)
        values (?,?,?,?,?,?::timestamptz,?::timestamptz,?::date,?::date,?,?,'RECEIVED',?,?::jsonb,?,now(),now(),now())
        """,
        syncId,
        tenant,
        listingId,
        source,
        channel,
        Timestamp.from(request.periodStart().toInstant()),
        Timestamp.from(request.periodEnd().toInstant()),
        request.statFrom(),
        request.statTo(),
        request.snapshots().size(),
        skipped.size(),
        idempotencyKey,
        writeJson(detail),
        note(request.note()));

    // ---- 落快照（同一天同一来源 = 覆盖，覆盖次数 +1）
    for (PerformanceSnapshotInput input : applicable) {
      sql.execute(
          """
          insert into listing_performance_snapshot
            (id,tenant_id,listing_id,stat_date,impressions,clicks,sessions,units_ordered,
             sales_amount,currency,returns_count,return_amount,rating,rating_count,source,
             ingest_channel,last_sync_id,revision,detail,received_at,created_at,updated_at)
          values (?,?,?,?::date,?,?,?,?,?,?,?,?,?,?,?,?,?,1,?::jsonb,now(),now(),now())
          on conflict (tenant_id,listing_id,stat_date,source) do update set
            impressions=excluded.impressions,
            clicks=excluded.clicks,
            sessions=excluded.sessions,
            units_ordered=excluded.units_ordered,
            sales_amount=excluded.sales_amount,
            currency=excluded.currency,
            returns_count=excluded.returns_count,
            return_amount=excluded.return_amount,
            rating=excluded.rating,
            rating_count=excluded.rating_count,
            ingest_channel=excluded.ingest_channel,
            last_sync_id=excluded.last_sync_id,
            revision=listing_performance_snapshot.revision+1,
            detail=excluded.detail,
            received_at=excluded.received_at,
            updated_at=now()
          """,
          Ids.next(),
          tenant,
          listingId,
          input.statDate(),
          dec(input.impressions()),
          dec(input.clicks()),
          dec(input.sessions()),
          dec(input.unitsOrdered()),
          dec(input.salesAmount()),
          blankToNull(input.currency()) == null ? "" : blankToNull(input.currency()),
          dec(input.returnsCount()),
          dec(input.returnAmount()),
          input.rating(),
          input.ratingCount(),
          source,
          channel,
          syncId,
          writeJson(input.detail() == null ? Map.of() : input.detail()));
    }

    // ---- 收口同步批次（终态不可再改，由 DB 触发器钉死）
    String status = skipped.isEmpty() ? "APPLIED" : "PARTIAL";
    sql.execute(
        "update listing_performance_sync set status=?, updated_at=now() where id=? and tenant_id=?",
        status,
        syncId,
        tenant);

    return new ListingPerformanceIngestResult(
        syncId,
        listingId,
        status,
        request.snapshots().size(),
        applicable.size(),
        skipped.size(),
        false,
        idempotencyKey,
        skipped.stream().map(s -> s.get("statDate") + "：" + s.get("reason")).toList(),
        (OffsetDateTime)
            sql.fetchValue(
                "select received_at from listing_performance_sync where id=? and tenant_id=?",
                syncId,
                tenant));
  }

  private static void requireNonNegative(BigDecimal value, String field, LocalDate statDate) {
    if (value != null && value.signum() < 0) {
      throw unprocessable(
          "「" + field + "」不能是负数（统计日 " + statDate + " 收到 " + value + "）");
    }
  }

  private static String note(String raw) {
    String v = text(raw);
    return v.length() > 500 ? v.substring(0, 500) : v;
  }

  private static int intOf(Record r, String field) {
    Long v = r.get(field, Long.class);
    return v == null ? 0 : v.intValue();
  }

  /** 已落库的快照条数 = 指向这个批次的快照行数（可查事实，不靠冗余列）。 */
  private int appliedCountOf(Record sync) {
    Long v =
        (Long)
            sql.fetchValue(
                "select count(*) from listing_performance_snapshot where tenant_id=? and last_sync_id=?",
                currentTenant(),
                sync.get("id", UUID.class));
    return v == null ? 0 : v.intValue();
  }

  /** 定位商品：给 listingId 就用它；否则按 (店铺编码, 平台商品 ID) 定位。 */
  private Record resolveListing(UUID tenant, IngestPerformanceRequest request) {
    StringBuilder sqlText =
        new StringBuilder(
            """
            select l.id,l.shop_id,l.marketplace_id,l.status,l.external_item_id,l.title
            from listing l
            where l.tenant_id=?
            """);
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);

    if (request.listingId() != null) {
      sqlText.append(" and l.id=?");
      binds.add(request.listingId());
    } else {
      String shopCode = blankToNull(request.shopCode());
      String externalItemId = blankToNull(request.externalItemId());
      if (shopCode == null || externalItemId == null) {
        throw unprocessable("请给 listingId，或给 (店铺编码 shopCode + 平台商品 ID externalItemId)");
      }
      sqlText.append(
          " and l.external_item_id=? and exists (select 1 from shop s where s.tenant_id=l.tenant_id"
              + " and s.id=l.shop_id and s.code=?)");
      binds.add(externalItemId);
      binds.add(shopCode);
    }
    return sql.fetchOne(sqlText.toString(), binds.toArray());
  }

  // ==================================================================
  // 表现快照列表（真分页 + 筛选）
  // ==================================================================

  /**
   * 表现快照列表。
   *
   * <p>🔴 筛选口径：{@code listingId}、{@code shopCode}、{@code platformCode}、
   * {@code from} / {@code to}（平台统计日范围）、真分页。
   */
  public ListingPerformanceSnapshotPage snapshots(
      String listingId, String shopCode, String platformCode, String from, String to, int page, int size) {
    UUID tenant = currentTenant();
    StringBuilder where = new StringBuilder(" where s.tenant_id=?");
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);

    String listing = blankToNull(listingId);
    if (listing != null) {
      where.append(" and s.listing_id=?");
      binds.add(parseId(listing));
    }
    String shop = blankToNull(shopCode);
    if (shop != null) {
      where.append(" and sh.code=?");
      binds.add(shop);
    }
    String platform = blankToNull(platformCode);
    if (platform != null) {
      where.append(" and p.code=?");
      binds.add(platform);
    }
    LocalDate fromDate = parseDate(from, "起始统计日");
    if (fromDate != null) {
      where.append(" and s.stat_date >= ?::date");
      binds.add(fromDate);
    }
    LocalDate toDate = parseDate(to, "结束统计日");
    if (toDate != null) {
      where.append(" and s.stat_date <= ?::date");
      binds.add(toDate);
    }

    long total = count(SNAPSHOT_FROM + where.toString(), binds.toArray());
    int lim = clampSize(size);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(lim);
    pageBinds.add(Math.max(0, page) * lim);
    List<ListingPerformanceSnapshotRow> items =
        sql.fetch(
                SNAPSHOT_FROM
                    + where.toString()
                    + " order by s.stat_date desc, s.received_at desc, s.id desc limit ? offset ?",
                pageBinds.toArray())
            .stream()
            .map(this::snapshotRow)
            .toList();

    // 🔴 同步结果条数：让前端能分辨「还没同步过」与「同步过但这个条件下没数据」
    StringBuilder syncWhere = new StringBuilder(" where tenant_id=?");
    List<Object> syncBinds = new ArrayList<>();
    syncBinds.add(tenant);
    if (listing != null) {
      syncWhere.append(" and listing_id=?");
      syncBinds.add(parseId(listing));
    }
    Long syncTotal =
        (Long)
            sql.fetchValue(
                "select count(*) from listing_performance_sync" + syncWhere,
                syncBinds.toArray());
    long syncCount = syncTotal == null ? 0 : syncTotal;

    return new ListingPerformanceSnapshotPage(items, Math.max(0, page), lim, total, syncCount);
  }

  private static final String SNAPSHOT_FROM =
      """
      select s.id,s.listing_id,l.external_item_id,l.title,sh.code as shop_code,p.code as platform_code,
             s.stat_date,s.impressions,s.clicks,s.sessions,s.units_ordered,s.sales_amount,s.currency,
             s.returns_count,s.return_amount,s.rating,s.rating_count,s.source,s.ingest_channel,
             s.revision,s.received_at,s.updated_at
      from listing_performance_snapshot s
      join listing l on l.tenant_id=s.tenant_id and l.id=s.listing_id
      join shop sh on sh.tenant_id=s.tenant_id and sh.id=l.shop_id
      join platform p on p.id=sh.platform_id
      """;

  private long count(String from, Object[] binds) {
    Record r = sql.fetchOne("select count(*) as n from (" + from + ") t", binds);
    Long n = r == null ? null : r.get("n", Long.class);
    return n == null ? 0 : n;
  }

  private ListingPerformanceSnapshotRow snapshotRow(Record r) {
    BigDecimal impressions = r.get("impressions", BigDecimal.class);
    BigDecimal clicks = r.get("clicks", BigDecimal.class);
    BigDecimal sessions = r.get("sessions", BigDecimal.class);
    BigDecimal units = r.get("units_ordered", BigDecimal.class);
    BigDecimal returns = r.get("returns_count", BigDecimal.class);
    return new ListingPerformanceSnapshotRow(
        r.get("id", UUID.class),
        r.get("listing_id", UUID.class),
        nullToEmpty(r.get("external_item_id", String.class)),
        nullToEmpty(r.get("title", String.class)),
        nullToEmpty(r.get("shop_code", String.class)),
        nullToEmpty(r.get("platform_code", String.class)),
        r.get("stat_date", LocalDate.class) == null
            ? ""
            : r.get("stat_date", LocalDate.class).toString(),
        impressions,
        clicks,
        ratio(clicks, impressions),
        sessions,
        units,
        r.get("sales_amount", BigDecimal.class),
        ratio(units, sessions),
        returns,
        r.get("return_amount", BigDecimal.class),
        ratio(returns, units),
        r.get("rating", BigDecimal.class),
        r.get("rating_count", BigDecimal.class),
        r.get("source", String.class),
        r.get("ingest_channel", String.class),
        nullToEmpty(r.get("currency", String.class)),
        intOf(r, "revision"),
        r.get("received_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  /** 🔴 比率：分子 ÷ 分母；<b>分母为 0 时是 null</b>（不是 0、也不是 100）。 */
  private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
    if (numerator == null || denominator == null) {
      return null;
    }
    if (denominator.signum() == 0) {
      return null;
    }
    return numerator.divide(denominator, 6, RoundingMode.HALF_UP);
  }

  // ==================================================================
  // 🔴 单个 listing 的表现明细 + 时间范围对比
  // ==================================================================

  /**
   * 🔴 单个 listing 的表现明细（含本期 vs 上期对比）。
   *
   * <p>🔴 对比口径：上期不给时默认取**紧邻的等长区间**（[from − N 天, from − 1 天]），
   * 这样「本期 vs 上期」不需要调用方自己算日期。变化率在 SQL 里现算，
   * <b>上期为 0 时是 null</b>（不是 0、不是 +∞）。
   */
  public ListingPerformanceDetail detail(
      String listingId, String from, String to, String compareFrom, String compareTo) {
    UUID tenant = currentTenant();
    UUID id = parseId(listingId);

    Record listing = sql.fetchOne(
        """
        select l.id,l.external_item_id,l.title,sh.code as shop_code,p.code as platform_code
        from listing l
        join shop sh on sh.tenant_id=l.tenant_id and sh.id=l.shop_id
        join platform p on p.id=sh.platform_id
        where l.tenant_id=? and l.id=?
        """,
        tenant,
        id);
    if (listing == null) {
      throw notFound();
    }

    LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
    LocalDate toDate = parseDate(to, "结束统计日");
    if (toDate == null) {
      toDate = today;
    }
    LocalDate fromDate = parseDate(from, "起始统计日");
    if (fromDate == null) {
      fromDate = toDate.minusDays(29);
    }
    if (fromDate.isAfter(toDate)) {
      throw badRequest("起始统计日不能晚于结束统计日（" + fromDate + " > " + toDate + "）");
    }

    LocalDate compareToDate = parseDate(compareTo, "上期结束统计日");
    LocalDate compareFromDate = parseDate(compareFrom, "上期起始统计日");
    if (compareFromDate == null || compareToDate == null) {
      long days = ChronoUnit.DAYS.between(fromDate, toDate) + 1;
      compareToDate = fromDate.minusDays(1);
      compareFromDate = compareToDate.minusDays(days - 1);
    }
    if (compareFromDate.isAfter(compareToDate)) {
      throw badRequest(
          "上期起始统计日不能晚于上期结束统计日（" + compareFromDate + " > " + compareToDate + "）");
    }

    // ---- 本期 vs 上期：一次 SQL 出两期指标 + 逐指标变化率
    // 当前期与上期的指标：各跑一次聚合（比率在 SQL 里算）
    ListingPerformanceMetrics current = metrics(tenant, id, fromDate, toDate);
    ListingPerformanceMetrics previous = metrics(tenant, id, compareFromDate, compareToDate);

    List<ListingPerformanceComparisonEntry> entries = new ArrayList<>();
    {
      for (Record row : sql.fetch(
          """
          with cur as (
          """
              + AGG
              + """
          ), prev as (
          """
              + AGG
              + """
          )
          select m.metric, m.current, m.previous,
                 (m.current - m.previous) as change,
                 case when m.previous <> 0
                      then round((m.current - m.previous) / abs(m.previous), 6)
                      else null end as change_rate,
                 m.unit
          from (
            select 'impressions' as metric, c.impressions as current, p.impressions as previous, 'COUNT' as unit from cur c cross join prev p
            union all select 'clicks', c.clicks, p.clicks, 'COUNT' from cur c cross join prev p
            union all select 'ctr', c.ctr, p.ctr, 'RATE' from cur c cross join prev p
            union all select 'sessions', c.sessions, p.sessions, 'COUNT' from cur c cross join prev p
            union all select 'unitsOrdered', c.units_ordered, p.units_ordered, 'COUNT' from cur c cross join prev p
            union all select 'salesAmount', c.sales_amount, p.sales_amount, 'AMOUNT' from cur c cross join prev p
            union all select 'conversionRate', c.conversion_rate, p.conversion_rate, 'RATE' from cur c cross join prev p
            union all select 'returnsCount', c.returns_count, p.returns_count, 'COUNT' from cur c cross join prev p
            union all select 'returnAmount', c.return_amount, p.return_amount, 'AMOUNT' from cur c cross join prev p
            union all select 'returnRate', c.return_rate, p.return_rate, 'RATE' from cur c cross join prev p
            union all select 'rating', c.rating_last, p.rating_last, 'SCORE' from cur c cross join prev p
            union all select 'ratingCount', c.rating_count_last, p.rating_count_last, 'COUNT' from cur c cross join prev p
          ) m
          order by m.metric
          """,
          tenant,
          id,
          fromDate,
          toDate,
          tenant,
          id,
          compareFromDate,
          compareToDate)) {
        entries.add(
            new ListingPerformanceComparisonEntry(
                row.get("metric", String.class),
                row.get("current", BigDecimal.class),
                row.get("previous", BigDecimal.class),
                row.get("change", BigDecimal.class),
                row.get("change_rate", BigDecimal.class),
                row.get("unit", String.class)));
      }
    }

    boolean hasData = current.snapshotCount() > 0;
    String emptyReason = "";
    if (!hasData) {
      long syncCount = syncCount(tenant, id);
      emptyReason =
          syncCount == 0
              ? "这个商品还没有平台表现数据的同步结果 —— 平台侧的表现报表到达后会出现在这里。"
              : "这个商品同步过平台表现数据，但本期区间（" + fromDate + " ~ " + toDate
                  + "）内没有数据。";
    }

    Record sync =
        sql.fetchOne(
            "select count(*) as n, max(received_at) as last_at from listing_performance_sync"
                + " where tenant_id=? and listing_id=?",
            tenant,
            id);

    return new ListingPerformanceDetail(
        id,
        nullToEmpty(listing.get("external_item_id", String.class)),
        nullToEmpty(listing.get("title", String.class)),
        nullToEmpty(listing.get("shop_code", String.class)),
        nullToEmpty(listing.get("platform_code", String.class)),
        DATA_SOURCE,
        SOURCE_NOTE,
        fromDate.toString(),
        toDate.toString(),
        compareFromDate.toString(),
        compareToDate.toString(),
        current,
        previous,
        new ListingPerformanceComparison(entries),
        hasData,
        emptyReason,
        sync == null ? 0L : (sync.get("n", Long.class) == null ? 0L : sync.get("n", Long.class)),
        sync == null ? null : sync.get("last_at", OffsetDateTime.class));
  }

  private long syncCount(UUID tenant, UUID listingId) {
    Long v =
        (Long)
            sql.fetchValue(
                "select count(*) from listing_performance_sync where tenant_id=? and listing_id=?",
                tenant,
                listingId);
    return v == null ? 0 : v;
  }

  /** 区间聚合（比率在 SQL 里算）。 */
  private ListingPerformanceMetrics metrics(UUID tenant, UUID listingId, LocalDate from, LocalDate to) {
    Record r = sql.fetchOne(AGG, tenant, listingId, from, to);
    if (r == null) {
      return new ListingPerformanceMetrics(
          BigDecimal.ZERO, BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
          null, BigDecimal.ZERO, BigDecimal.ZERO, null, null, null, null, 0, "");
    }
    return new ListingPerformanceMetrics(
        r.get("impressions", BigDecimal.class),
        r.get("clicks", BigDecimal.class),
        r.get("ctr", BigDecimal.class),
        r.get("sessions", BigDecimal.class),
        r.get("units_ordered", BigDecimal.class),
        r.get("sales_amount", BigDecimal.class),
        r.get("conversion_rate", BigDecimal.class),
        r.get("returns_count", BigDecimal.class),
        r.get("return_amount", BigDecimal.class),
        r.get("return_rate", BigDecimal.class),
        r.get("rating_last", BigDecimal.class),
        r.get("rating_count_last", BigDecimal.class),
        r.get("rating_change", BigDecimal.class),
        intOf(r, "snapshot_count"),
        nullToEmpty(r.get("currency", String.class)));
  }

  // ==================================================================
  // 趋势序列
  // ==================================================================

  /**
   * 🔴 趋势序列（按日 / 周 / 月归集）。
   *
   * <p>🔴 没有数据时 {@code points} 是<b>空数组</b>，不是一条平线 —— 假曲线会让人
   * 以为「这段时间表现平稳」，而真相是「平台没报数据」。
   */
  public ListingPerformanceTrend trend(
      String listingId, String from, String to, String granularity) {
    UUID tenant = currentTenant();
    UUID id = parseId(listingId);

    if (sql.fetchOne("select 1 from listing where tenant_id=? and id=?", tenant, id) == null) {
      throw notFound();
    }

    String gran = text(granularity).toUpperCase(Locale.ROOT);
    if (gran.isEmpty()) {
      gran = "DAY";
    }
    if (!GRANULARITIES.contains(gran)) {
      throw badRequest(
          "未知的趋势粒度「" + text(granularity) + "」。只认这三种："
              + String.join(" / ", GRANULARITIES));
    }

    LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
    LocalDate toDate = parseDate(to, "结束统计日");
    if (toDate == null) {
      toDate = today;
    }
    LocalDate fromDate = parseDate(from, "起始统计日");
    if (fromDate == null) {
      fromDate = toDate.minusDays(29);
    }
    if (fromDate.isAfter(toDate)) {
      throw badRequest("起始统计日不能晚于结束统计日（" + fromDate + " > " + toDate + "）");
    }

    String bucketExpr =
        switch (gran) {
          case "WEEK" -> "date_trunc('week', stat_date)";
          case "MONTH" -> "date_trunc('month', stat_date)";
          default -> "stat_date";
        };

    List<ListingPerformanceTrendPoint> points =
        sql.fetch(
                """
                select to_char(%s, 'YYYY-MM-DD') as bucket,
                       coalesce(sum(impressions),0) as impressions,
                       coalesce(sum(clicks),0) as clicks,
                       coalesce(sum(sessions),0) as sessions,
                       coalesce(sum(units_ordered),0) as units_ordered,
                       coalesce(sum(sales_amount),0) as sales_amount,
                       coalesce(sum(returns_count),0) as returns_count,
                       coalesce(sum(return_amount),0) as return_amount,
                       case when sum(impressions) <> 0
                            then round(sum(clicks) / sum(impressions), 6) else null end as ctr,
                       case when sum(sessions) <> 0
                            then round(sum(units_ordered) / sum(sessions), 6) else null end as conversion_rate,
                       case when sum(units_ordered) <> 0
                            then round(sum(returns_count) / sum(units_ordered), 6) else null end as return_rate,
                       (array_agg(rating order by stat_date desc) filter (where rating is not null))[1] as rating,
                       (array_agg(rating_count order by stat_date desc) filter (where rating_count is not null))[1]
                         as rating_count
                from listing_performance_snapshot
                where tenant_id=? and listing_id=? and stat_date >= ?::date and stat_date <= ?::date
                group by %s
                order by 1
                """
                    .formatted(bucketExpr, bucketExpr),
                tenant,
                id,
                fromDate,
                toDate)
            .stream()
            .map(
                r -> {
                  BigDecimal impressions = r.get("impressions", BigDecimal.class);
                  BigDecimal clicks = r.get("clicks", BigDecimal.class);
                  BigDecimal sessions = r.get("sessions", BigDecimal.class);
                  BigDecimal units = r.get("units_ordered", BigDecimal.class);
                  BigDecimal returns = r.get("returns_count", BigDecimal.class);
                  return new ListingPerformanceTrendPoint(
                      nullToEmpty(r.get("bucket", String.class)),
                      impressions,
                      clicks,
                      r.get("ctr", BigDecimal.class),
                      sessions,
                      units,
                      r.get("sales_amount", BigDecimal.class),
                      r.get("conversion_rate", BigDecimal.class),
                      returns,
                      r.get("return_amount", BigDecimal.class),
                      r.get("return_rate", BigDecimal.class),
                      r.get("rating", BigDecimal.class),
                      r.get("rating_count", BigDecimal.class));
                })
            .toList();

    return new ListingPerformanceTrend(
        id,
        gran,
        fromDate.toString(),
        toDate.toString(),
        DATA_SOURCE,
        SOURCE_NOTE,
        points,
        !points.isEmpty());
  }
}
