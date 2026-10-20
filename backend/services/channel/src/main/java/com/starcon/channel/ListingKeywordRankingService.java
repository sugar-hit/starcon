package com.starcon.channel;

import static com.starcon.channel.ListingKeywordRankingModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * 4.5.1.2 关键词排名。
 *
 * <p>蓝图：「监控商品在核心关键词下的自然排名与广告排名，记录历史变化，低于阈值时预警。输入：关键词集合（按商品维护）；排名抓取任务；阈值设置。输出：排名历史表 + 变化预警 + 关键词维度报表。关系：上游 = 抓取任务（02 通道）；下游 = 1 工作台预警、14 广告。<b>排名数据依赖抓取频率与平台限制，必须明确标注抓取时间与可能的不准确窗口</b>。」
 *
 * <p>🔴 本类的五个核心不变量，每一个都有集成测试逐条钉住：
 *
 * <ol>
 *   <li><b>🔴🔴 抓取时间与不准确窗口是契约级的</b> —— 抓取请求必须带
 *       {@code crawledAt} 与 {@code accuracyWindowMinutes}；每个返回排名数据的响应都带
 *       这两个字段与 {@code accuracyNote}。蓝图加粗句「必须明确标注抓取时间与可能的不准确窗口」
 *       因此不是页面上的装饰，而是**接口的一部分**。
 *   <li><b>🔴🔴 排名没有就写 null</b> —— 平台没报名次 = null，<b>不是 0</b>。
 *       写 0 会被下游读成「排第一」或「完全没排名」，那两件事都不是事实。
 *   <li><b>🔴 变化量一律现算</b> —— 「本次名次 − 上次名次」由 SQL 现算，<b>不落表</b>：
 *       「上一次」是按抓取时间定的，落列天然会错。
 *   <li><b>🔴 预警是记录，不是通知</b> —— 下游 1 工作台预警与 14 广告都未交付，
 *       本模块只把判定写下来，<b>不发通知</b>；{@link #capabilities()} 里
 *       available=false + ownerModule + impact，前端显示「未接入」。
 *   <li><b>🔴 关键词集合是软删除</b> —— 停用只翻 {@code active}，排名历史与预警记录必须留着
 *       （「当时发生了什么」查无对证比多一行数据严重得多）。
 * </ol>
 *
 * <p>🔴 时间口径：{@code crawled_at} 是 {@code timestamptz}，用 {@code OffsetDateTime}
 * 绑定、values 位置写 {@code ?::timestamptz} —— 用 {@code Timestamp.valueOf(LocalDateTime...)}
 * 会静默偏 −28800s，整条趋势会错位（AGENTS.md 与 4.1.1.1 实测踩过）。
 * 趋势的日/周/月归集一律按 <b>UTC</b> 做（{@code crawled_at at time zone 'UTC'}），
 * 否则同一份数据在不同的库里会算出不同的桶。
 */
@Service
public class ListingKeywordRankingService {

  /**
   * 关键词列表 / 明细的取数（真 SQL 聚合 + lateral 取最近两次抓取）。
   *
   * <p>🔴 「最近两次」用 {@code array_agg(...)[1]} 与 {@code array_agg(...)[2]} 取 ——
   * 「变化」就是这两者之差，<b>不落表</b>（按抓取时间定的「上一次」，落列天然会错）。
   */
  private static final String KEYWORD_SELECT =
      """
      select k.id as keyword_id, k.listing_id, k.keyword, k.platform_code, k.site_code,
             k.natural_rank_threshold, k.ad_rank_threshold, k.rank_drop_threshold,
             k.active, k.note,
             l.external_item_id, l.title, sh.code as shop_code,
             s.natural_rank as latest_natural_rank, s.ad_rank as latest_ad_rank,
             s.crawled_at as latest_crawled_at, s.window_minutes as latest_window,
             case when s.prev_natural_rank is not null and s.natural_rank is not null
                  then s.natural_rank - s.prev_natural_rank else null end as natural_rank_change,
             case when s.prev_ad_rank is not null and s.ad_rank is not null
                  then s.ad_rank - s.prev_ad_rank else null end as ad_rank_change,
             coalesce(cnt.snapshot_count,0) as snapshot_count,
             coalesce(cnt.crawl_count,0) as crawl_count,
             coalesce(al.total_alert_count,0) as total_alert_count,
             coalesce(al.open_alert_count,0) as open_alert_count
      from listing_keyword k
      join listing l on l.tenant_id=k.tenant_id and l.id=k.listing_id
      join shop sh on sh.tenant_id=k.tenant_id and sh.id=l.shop_id
      left join lateral (
        select (array_agg(t.natural_rank order by t.crawled_at desc, t.received_at desc))[1] as natural_rank,
               (array_agg(t.natural_rank order by t.crawled_at desc, t.received_at desc))[2] as prev_natural_rank,
               (array_agg(t.ad_rank order by t.crawled_at desc, t.received_at desc))[1] as ad_rank,
               (array_agg(t.ad_rank order by t.crawled_at desc, t.received_at desc))[2] as prev_ad_rank,
               (array_agg(t.crawled_at order by t.crawled_at desc, t.received_at desc))[1] as crawled_at,
               (array_agg(t.accuracy_window_minutes order by t.crawled_at desc, t.received_at desc))[1]
                 as window_minutes
        from (
          select natural_rank, ad_rank, crawled_at, received_at, accuracy_window_minutes
          from listing_keyword_rank_snapshot
          where tenant_id=k.tenant_id and keyword_id=k.id
          order by crawled_at desc, received_at desc
          limit 2
        ) t
      ) s on true
      left join lateral (
        select count(*) as snapshot_count, count(distinct last_crawl_id) as crawl_count
        from listing_keyword_rank_snapshot s3
        where s3.tenant_id=k.tenant_id and s3.keyword_id=k.id
      ) cnt on true
      left join lateral (
        select count(*) as total_alert_count,
               count(*) filter (where acknowledged_at is null) as open_alert_count
        from listing_keyword_rank_alert a3
        where a3.tenant_id=k.tenant_id and a3.keyword_id=k.id
      ) al on true
      """;

  /** 排名字段的口径说明（前端与报表都显示）。 */
  private static final String RANK_DEFINITIONS =
      "自然排名 naturalRank / 广告排名 adRank 是**名次**（第几名，数字越小越好）。"
          + "🔴 平台没报这个维度的名次时是 **null**，不是 0 —— 「这次没进监测范围」与「排名第 0」"
          + "是两件完全不同的事，写 0 会被读成「排第一」或「完全没排名」。"
          + "🔴 排名变化 rankChange = 本次名次 − 上次名次（**名次变大 = 变差**），"
          + "由服务端现算、**不落表**（「上一次」是按抓取时间定的，落列天然会错）；"
          + "没有上一次时是 null（那是「没有可比的上次」，不是「没变化」）。"
          + "🔴 低于阈值：名次数字**大于**阈值算低于阈值（如阈值 10、名次 25 ⇒ 掉出前 10）。"
          + "🔴 三个阈值可空 = 该维度不产生预警（「没配阈值」与「阈值是 0」是两件事）。"
          + "前端原样显示，不要自己重算。";

  private final DSLContext sql;
  private final ObjectMapper json;

  public ListingKeywordRankingService(DSLContext sql, ObjectMapper json) {
    this.sql = sql;
    this.json = json;
  }

  // ==================================================================
  // 小工具（照抄 ListingPerformanceService 的同名写法）
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

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
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

  private static int intOf(Record r, String field) {
    Long v = r.get(field, Long.class);
    return v == null ? 0 : v.intValue();
  }

  private static long longOf(Record r, String field) {
    Long v = r.get(field, Long.class);
    return v == null ? 0L : v;
  }

  private static Integer integerOrNull(Record r, String field) {
    Integer v = r.get(field, Integer.class);
    return v;
  }

  private static String note(String raw) {
    String v = text(raw);
    return v.length() > 500 ? v.substring(0, 500) : v;
  }

  /** 日区间（UTC）：from 00:00:00Z ~ to 23:59:59.999999Z。 */
  private record DayRange(OffsetDateTime from, OffsetDateTime to) {}

  private DayRange dayRange(String from, String to) {
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    LocalDate toDate = parseDate(to, "结束日期");
    if (toDate == null) {
      toDate = today;
    }
    LocalDate fromDate = parseDate(from, "起始日期");
    if (fromDate == null) {
      fromDate = toDate.minusDays(29);
    }
    if (fromDate.isAfter(toDate)) {
      throw badRequest("起始日期不能晚于结束日期（" + fromDate + " > " + toDate + "）");
    }
    return new DayRange(
        fromDate.atStartOfDay().atOffset(ZoneOffset.UTC),
        toDate.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC).minusNanos(1));
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 🔴 逐维度能力声明。
   *
   * <p>本模块在本仓能**真实交付**的是「按商品维护的关键词集合 + 接收排名抓取任务 +
   * 排名历史 + 变化预警记录 + 关键词维度报表 + 抓取时间与不准确窗口的标注」。
   *
   * <p>它<b>不能</b>在本批真实交付的有三段，逐条在下面声明而不是假装具备：
   * 1 工作台预警推送、14 广告关联投放、02 的真实排名抓取调度器。
   */
  public ListingKeywordRankingCapabilities capabilities() {
    return new ListingKeywordRankingCapabilities(
        List.of(
            new ListingKeywordRankingCapabilityDimension(
                "keyword_set",
                "🔴 按商品维护的关键词集合：可增删改查，每个关键词关联到商品 + 平台 + 站点，"
                    + "并带三个可空阈值（自然排名阈值 / 广告排名阈值 / 排名退步阈值）",
                true, "listing_keyword（V205 新表）", "",
                "平台与站点由商品所属店铺/站点**推导**（商品是唯一事实来源），"
                    + "独立维护一份就可能与商品矛盾，而矛盾的平台归属会让排名报表按错误的维度分组。"
                    + "停用是**软删除**：排名历史与预警记录必须留着"),
            new ListingKeywordRankingCapabilityDimension(
                "rank_crawl_ingest",
                "🔴 接收排名抓取任务并入库：一次抓取 = 一个有范围、有通道、有成败的整体，"
                    + "内含各关键词的自然排名与广告排名；批次级幂等键保证重复投递整批不再落库",
                true, "listing_keyword_crawl + listing_keyword_rank_snapshot（V205 新表）", "",
                "幂等键让「抓取方重投同一批」与「抓取方修正同一时刻」变成两件可分辨的事："
                    + "前者整批跳过（连预警都不会重复产生），后者覆盖该时刻并把覆盖次数 +1"),
            new ListingKeywordRankingCapabilityDimension(
                "rank_history",
                "🔴 排名历史：按抓取时刻一行，含自然排名 / 广告排名；"
                    + "「较上次的变化」由服务端现算，**不落表**",
                true, "listing_keyword_rank_snapshot（V205 新表）", "",
                "「变化」不落表是因为「上一次」按**抓取时间**定；落了列，改了抓取顺序就会算错。"
                    + "没找到的名次是 null（不是 0）"),
            new ListingKeywordRankingCapabilityDimension(
                "accuracy_window_labeling",
                "🔴 抓取时间与不准确窗口的标注：每个排名数据点都带 crawledAt 与 "
                    + "accuracyWindowMinutes，每个响应都带 accuracyNote",
                true, "listing_keyword_rank_snapshot.crawled_at / accuracy_window_minutes", "",
                "蓝图加粗句「排名数据依赖抓取频率与平台限制，必须明确标注抓取时间与可能的不准确窗口」"
                    + "—— 这是**契约级**要求：导出报表时这两列也得带着，否则报告里那个名次"
                    + "看不出是什么时候的、可能有多不准"),
            new ListingKeywordRankingCapabilityDimension(
                "rank_alerts",
                "🔴 变化预警：低于阈值（自然 / 广告）与退步超阈值时产生**记录**",
                true, "listing_keyword_rank_alert（V205 新表）", "",
                "预警是**记录**，不是通知：下游 1 工作台预警未交付，本模块只把判定写下来，"
                    + "**不发通知**。预警带 acknowledged_at，「有没有人看过」这个事实仍然留痕"),
            new ListingKeywordRankingCapabilityDimension(
                "keyword_dimension_report",
                "🔴 关键词维度报表：按（平台, 站点, 关键词）聚合的平均 / 最好 / 最差名次、"
                    + "低于阈值次数与未确认预警数（**真 SQL 聚合**）",
                true, "listing_keyword_rank_snapshot + 实时聚合", "",
                "报表**不建表** —— 它是排名历史的聚合视图，落表就会与事实漂移。"
                    + "平均名次在没有样本时是 null（不是 0）"),
            new ListingKeywordRankingCapabilityDimension(
                "workbench_alert_delivery",
                "🔴 **1 工作台预警**：把排名预警推送到运营工作台",
                false, "—", "1 工作台与门户（整卷未交付）",
                "本模块只把预警**记录**下来并允许人工确认，**不去发通知** —— "
                    + "推送通道、免打扰、升级策略都归 1 工作台。页面上显示「未接入」并写明归属，"
                    + "不显示一个必然失败的「推送」按钮"),
            new ListingKeywordRankingCapabilityDimension(
                "ad_attribution",
                "🔴 **14 广告（关联投放）**：把关键词排名与广告投放关联起来",
                false, "—", "14 广告与增长（整卷未交付）",
                "广告排名只说明「付费位在第几名」，**不含**花费、点击与转化口径；"
                    + "本模块**不用**「自然名次涨了 ⇒ 广告有效」这种猜出来的因果替代，"
                    + "关联投放交付后由它来读本模块的排名数据"),
            new ListingKeywordRankingCapabilityDimension(
                "real_crawl_scheduler",
                "🔴 真实排名抓取的**调度器**（定时去平台查关键词名次的那个任务）",
                false, "—", "02 渠道与店铺（同步运行时）",
                "本模块提供**入库端点**（通道 WEBHOOK / POLL / API 都被记下来），"
                    + "但**没有**那个定时调度器 —— 平台限流与店铺配额由 02 统一裁决，"
                    + "本模块不自建轮询，也不替平台编造抓取频率"),
            new ListingKeywordRankingCapabilityDimension(
                "competitor_rank_tracking",
                "🔴 **4.5.2.1 跟卖监控 / 4.5.2.2 上新与变更监控**：竞品侧的排名与变更",
                false, "—", "4.5.2.1 跟卖监控、4.5.2.2 上新与变更监控",
                "本模块只监控**自有商品**的关键词名次。竞品数据不进自有商品模型，"
                    + "那是 4.5.2.x 的职责")),
        SOURCES,
        INGEST_CHANNELS,
        ALERT_TYPES,
        GRANULARITIES,
        ACCURACY_NOTE,
        RANK_DEFINITIONS,
        "上游 = 抓取任务（02 通道；本模块只提供入库端点，调度器归 02）· "
            + "下游 = 1 工作台预警（整卷未交付）、14 广告（整卷未交付）—— "
            + "两段都在本能力表里 available=false + ownerModule，页面上显示「未接入」。"
            + "本模块**不跨服务查表**：排名只能由抓取任务**推进来**。");
  }

  // ==================================================================
  // 筛选词表
  // ==================================================================

  /** 筛选面板选项：平台 / 店铺 / 站点（都取本租户真实有的）。 */
  public ListingKeywordRankingFilterOptions filterOptions() {
    UUID tenant = currentTenant();
    List<ListingKeywordRankingFilterOption> platforms =
        sql.fetch(
                // 🔴 用 exists 而不是 select distinct：PG 不允许 DISTINCT 的 ORDER BY 表达式
                //    不在 select list 里（position 不在列表里就会报 42P10）。
                "select p.code as code, p.name as name, '' as platform_code"
                    + " from platform p"
                    + " where exists (select 1 from shop s where s.tenant_id=? and s.platform_id=p.id)"
                    + " order by p.position, p.code",
                tenant)
            .stream()
            .map(r -> new ListingKeywordRankingFilterOption(r.get("code", String.class),
                r.get("name", String.class), ""))
            .toList();
    List<ListingKeywordRankingFilterOption> shops =
        sql.fetch(
                "select s.code as code, s.name as name, p.code as platform_code"
                    + " from shop s join platform p on p.id=s.platform_id"
                    + " where s.tenant_id=? order by p.position, s.code",
                tenant)
            .stream()
            .map(r -> new ListingKeywordRankingFilterOption(r.get("code", String.class),
                r.get("name", String.class), r.get("platform_code", String.class)))
            .toList();
    List<ListingKeywordRankingFilterOption> sites =
        sql.fetch(
                "select m.code as code, m.name as name, p.code as platform_code"
                    + " from marketplace m join platform p on p.id=m.platform_id"
                    + " where m.id in (select distinct marketplace_id from listing"
                    + "                where tenant_id=? and marketplace_id is not null)"
                    + " order by p.position, m.code",
                tenant)
            .stream()
            .map(r -> new ListingKeywordRankingFilterOption(r.get("code", String.class),
                r.get("name", String.class), r.get("platform_code", String.class)))
            .toList();
    return new ListingKeywordRankingFilterOptions(platforms, shops, sites);
  }

  // ==================================================================
  // 关键词集合（增删改查）
  // ==================================================================

  /**
   * 关键词列表（真分页 + 真筛选 + 真搜索）。
   *
   * <p>🔴 筛选口径：{@code listingId}、{@code shopCode}、{@code platformCode}、
   * {@code siteCode}、{@code keyword}（模糊搜索）、{@code status}（ACTIVE / INACTIVE / ALL）。
   */
  public ListingKeywordRankingKeywordPage keywords(
      String listingId,
      String shopCode,
      String platformCode,
      String siteCode,
      String keyword,
      String status,
      int page,
      int size) {
    UUID tenant = currentTenant();
    StringBuilder where = new StringBuilder(" where k.tenant_id=?");
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);

    String listing = blankToNull(listingId);
    if (listing != null) {
      where.append(" and k.listing_id=?");
      binds.add(parseId(listing));
    }
    String shop = blankToNull(shopCode);
    if (shop != null) {
      where.append(" and sh.code=?");
      binds.add(shop);
    }
    String platform = blankToNull(platformCode);
    if (platform != null) {
      where.append(" and k.platform_code=?");
      binds.add(platform);
    }
    String site = blankToNull(siteCode);
    if (site != null) {
      where.append(" and k.site_code=?");
      binds.add(site);
    }
    String word = blankToNull(keyword);
    if (word != null) {
      where.append(" and k.keyword ilike ?");
      binds.add("%" + word + "%");
    }
    String statusFilter = text(status).toUpperCase(Locale.ROOT);
    if (statusFilter.isEmpty()) {
      statusFilter = "ALL";
    }
    if (!List.of("ALL", "ACTIVE", "INACTIVE").contains(statusFilter)) {
      throw badRequest("未知的启用状态「" + text(status) + "」。只认：ALL / ACTIVE / INACTIVE");
    }
    if (statusFilter.equals("ACTIVE")) {
      where.append(" and k.active=true");
    } else if (statusFilter.equals("INACTIVE")) {
      where.append(" and k.active=false");
    }

    long total = count(KEYWORD_SELECT + where.toString(), binds.toArray());
    int lim = clampSize(size);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(lim);
    pageBinds.add(Math.max(0, page) * lim);
    List<ListingKeywordRankingKeywordRow> items =
        sql.fetch(
                KEYWORD_SELECT
                    + where.toString()
                    + " order by k.active desc, k.keyword asc, k.id asc limit ? offset ?",
                pageBinds.toArray())
            .stream()
            .map(this::keywordRow)
            .toList();
    return new ListingKeywordRankingKeywordPage(items, Math.max(0, page), lim, total);
  }

  private long count(String from, Object[] binds) {
    Record r = sql.fetchOne("select count(*) as n from (" + from + ") t", binds);
    Long n = r == null ? null : r.get("n", Long.class);
    return n == null ? 0 : n;
  }

  private ListingKeywordRankingKeywordRow keywordRow(Record r) {
    return new ListingKeywordRankingKeywordRow(
        r.get("keyword_id", UUID.class),
        r.get("listing_id", UUID.class),
        nullToEmpty(r.get("external_item_id", String.class)),
        nullToEmpty(r.get("title", String.class)),
        nullToEmpty(r.get("shop_code", String.class)),
        nullToEmpty(r.get("platform_code", String.class)),
        nullToEmpty(r.get("site_code", String.class)),
        nullToEmpty(r.get("keyword", String.class)),
        integerOrNull(r, "natural_rank_threshold"),
        integerOrNull(r, "ad_rank_threshold"),
        integerOrNull(r, "rank_drop_threshold"),
        Boolean.TRUE.equals(r.get("active", Boolean.class)),
        nullToEmpty(r.get("note", String.class)),
        integerOrNull(r, "latest_natural_rank"),
        integerOrNull(r, "latest_ad_rank"),
        integerOrNull(r, "natural_rank_change"),
        integerOrNull(r, "ad_rank_change"),
        r.get("latest_crawled_at", OffsetDateTime.class),
        integerOrNull(r, "latest_window"),
        longOf(r, "crawl_count"),
        longOf(r, "snapshot_count"),
        longOf(r, "open_alert_count"),
        longOf(r, "total_alert_count"));
  }

  /**
   * 新建 / 改一个关键词。
   *
   * <p>🔴 平台与站点**由商品推导**，不接受调用方直接给 —— 商品是唯一事实来源。
   *
   * <p>🔴 同一商品下同一关键词在同一平台站点只能有一条（改阈值走更新，不新建），
   * 重复新建 ⇒ 409（DB 层还有 UNIQUE 约束兜底）。
   */
  public ListingKeywordRankingKeywordRow saveKeyword(
      UUID id, ListingKeywordRankingKeywordInput input) {
    UUID tenant = currentTenant();
    String word = text(input.keyword());
    if (word.isEmpty()) {
      throw unprocessable("关键词不能为空");
    }
    if (word.length() > 200) {
      throw unprocessable("关键词太长了（最多 200 个字符，收到 " + word.length() + " 个）");
    }

    // ---- 🔴 定位商品，并推导平台与站点
    Record listing =
        sql.fetchOne(
            """
            select l.id, l.marketplace_id, p.code as platform_code,
                   coalesce(m.code,'') as site_code
            from listing l
            join shop sh on sh.tenant_id=l.tenant_id and sh.id=l.shop_id
            join platform p on p.id=sh.platform_id
            left join marketplace m on m.id=l.marketplace_id
            where l.tenant_id=? and l.id=?
            """,
            tenant,
            input.listingId());
    if (listing == null) {
      throw unprocessable(
          "定位不到商品（listingId=" + input.listingId() + "）—— 关键词必须挂在真实存在的商品上");
    }
    String platformCode = nullToEmpty(listing.get("platform_code", String.class));
    String siteCode = nullToEmpty(listing.get("site_code", String.class));

    if (id == null) {
      Record existing =
          sql.fetchOne(
              "select id from listing_keyword"
                  + " where tenant_id=? and listing_id=? and keyword=? and platform_code=? and site_code=?",
              tenant,
              input.listingId(),
              word,
              platformCode,
              siteCode);
      if (existing != null) {
        throw conflict(
            "这个商品下已经监控了关键词「" + word + "」（平台 " + platformCode + " / 站点 "
                + (siteCode.isEmpty() ? "—" : siteCode) + "）—— 改阈值请直接改它，不要新建一条");
      }
      UUID keywordId = Ids.next();
      sql.execute(
          """
          insert into listing_keyword
            (id,tenant_id,listing_id,keyword,platform_code,site_code,
             natural_rank_threshold,ad_rank_threshold,rank_drop_threshold,active,note,
             created_at,updated_at)
          values (?,?,?,?,?,?,?,?,?,true,?,now(),now())
          """,
          keywordId,
          tenant,
          input.listingId(),
          word,
          platformCode,
          siteCode,
          input.naturalRankThreshold(),
          input.adRankThreshold(),
          input.rankDropThreshold(),
          note(input.note()));
      return loadKeyword(tenant, keywordId);
    }

    int changed =
        sql.execute(
            """
            update listing_keyword
               set keyword=?, natural_rank_threshold=?, ad_rank_threshold=?,
                   rank_drop_threshold=?, note=?, updated_at=now()
             where tenant_id=? and id=?
            """,
            word,
            input.naturalRankThreshold(),
            input.adRankThreshold(),
            input.rankDropThreshold(),
            note(input.note()),
            tenant,
            id);
    if (changed == 0) {
      throw notFound();
    }
    return loadKeyword(tenant, id);
  }

  private ListingKeywordRankingKeywordRow loadKeyword(UUID tenant, UUID id) {
    Record r =
        sql.fetchOne(KEYWORD_SELECT + " where k.tenant_id=? and k.id=?", tenant, id);
    if (r == null) {
      throw notFound();
    }
    return keywordRow(r);
  }

  /**
   * 🔴 停用一个关键词（**软删除**）。
   *
   * <p>排名历史与预警记录**必须留着** —— 关键词停了不等于当初抓到的名次不算数。
   * 物理删除会级联抹掉历史与预警，「当时发生了什么」就查无对证了。
   */
  public ListingKeywordRankingKeywordRow disableKeyword(String keywordId) {
    UUID tenant = currentTenant();
    UUID id = parseId(keywordId);
    int changed =
        sql.execute(
            "update listing_keyword set active=false, updated_at=now() where tenant_id=? and id=?",
            tenant,
            id);
    if (changed == 0) {
      throw notFound();
    }
    return loadKeyword(tenant, id);
  }

  // ==================================================================
  // 🔴 排名抓取任务（蓝图输入项）
  // ==================================================================

  /**
   * 🔴🔴 接收一次排名抓取任务。
   *
   * <p>六件事，逐条都有理由：
   *
   * <ol>
   *   <li><b>幂等</b>：同一个 {@code idempotencyKey} 再次投递 ⇒ <b>整批不再落库</b>，
   *       直接返回既有那条抓取记录并把 {@code replayed} 置 true（连预警都不会重复产生）。
   *   <li><b>校验</b>：缺抓取时间 / 缺不准确窗口 / 负的名次 ⇒ 整批拒绝（422），**不落半批**。
   *   <li><b>归属</b>：payload 里的关键词必须属于本次抓取的商品 ⇒ 否则 422
   *       （把 A 的关键词挂到 B 的抓取上，是调用方的契约错误，不是数据缺口）。
   *   <li><b>停用</b>：已停用的关键词被**跳过并逐条写明原因**（不静默丢弃）。
   *   <li><b>落快照</b>：按 (关键词, 抓取时间, 来源) 覆盖写入，{@code revision} 递增；
   *       「上一次」按抓取时间定，用于算变化与退步预警。
   *   <li><b>落预警</b>：低于阈值 / 退步超阈值各产生一条**记录**（不发通知）。
   * </ol>
   */
  public ListingKeywordRankingCrawlResult crawl(ListingKeywordRankingCrawlRequest request) {
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
          "必须给出幂等键（idempotencyKey）—— 抓取方重投同一批时靠它整批跳过，"
              + "而抓取方修正同一时刻时用新键。没有它就分不清「重投」与「修正」");
    }
    if (request.crawledAt() == null) {
      throw unprocessable("必须给出抓取时间（crawledAt）—— 没有抓取时间的排名是不合格的排名数据");
    }
    if (request.accuracyWindowMinutes() == null) {
      throw unprocessable(
          "必须给出可能的不准确窗口（accuracyWindowMinutes，分钟）—— "
              + "排名依赖抓取频率与平台限制，窗口大小由抓取方按平台限制与抓取频率给出");
    }
    if (request.accuracyWindowMinutes() < 0) {
      throw unprocessable("不准确窗口不能是负数（收到 " + request.accuracyWindowMinutes() + "）");
    }

    // ---- 🔴 幂等：同一个 key 再次投递 ⇒ 整批不再落库
    Record existing =
        sql.fetchOne(
            "select id,listing_id,status,received_count,skipped_count,crawled_at,"
                + "accuracy_window_minutes,received_at"
                + " from listing_keyword_crawl where tenant_id=? and idempotency_key=?",
            tenant,
            idempotencyKey);
    if (existing != null) {
      return new ListingKeywordRankingCrawlResult(
          existing.get("id", UUID.class),
          existing.get("listing_id", UUID.class),
          existing.get("status", String.class),
          intOf(existing, "received_count"),
          appliedCountOf(existing),
          intOf(existing, "skipped_count"),
          true,
          idempotencyKey,
          List.of(),
          existing.get("crawled_at", OffsetDateTime.class),
          intOf(existing, "accuracy_window_minutes"),
          0);
    }

    // ---- 定位商品
    Record listing =
        sql.fetchOne(
            "select id from listing where tenant_id=? and id=?", tenant, request.listingId());
    if (listing == null) {
      throw unprocessable("定位不到商品（listingId=" + request.listingId() + "）");
    }
    UUID listingId = request.listingId();

    // ---- 该商品的关键词集合
    Map<UUID, Record> keywords = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select id,keyword,active,natural_rank_threshold,ad_rank_threshold,rank_drop_threshold"
                + " from listing_keyword where tenant_id=? and listing_id=?",
            tenant,
            listingId)) {
      keywords.put(r.get("id", UUID.class), r);
    }

    // ---- 校验并分流：可抓 = 落库；已停用 = 跳过（逐条写明原因）
    List<ListingKeywordRankingRankInput> applicable = new ArrayList<>();
    List<String> skipped = new ArrayList<>();
    Map<UUID, Boolean> seen = new LinkedHashMap<>();
    for (ListingKeywordRankingRankInput input : request.ranks()) {
      Record keyword = keywords.get(input.keywordId());
      if (keyword == null) {
        throw unprocessable(
            "关键词 " + input.keywordId() + " 不属于商品 " + listingId
                + " —— 把别的商品的关键词挂到这次抓取上是调用方的契约错误，整批拒绝");
      }
      if (seen.containsKey(input.keywordId())) {
        throw unprocessable(
            "关键词 " + input.keywordId() + " 在同一次抓取里出现了两次 —— 一次抓取对每个关键词"
                + "只该有一个名次，整批拒绝");
      }
      seen.put(input.keywordId(), Boolean.TRUE);
      requirePositive(input.naturalRank(), "naturalRank");
      requirePositive(input.adRank(), "adRank");
      if (!Boolean.TRUE.equals(keyword.get("active", Boolean.class))) {
        skipped.add(
            keyword.get("keyword", String.class) + "：关键词已停用，本次抓取不入库");
        continue;
      }
      applicable.add(input);
    }

    // ---- 落抓取批次（先落，状态留 RECEIVED）
    UUID crawlId = Ids.next();
    Map<String, Object> detail = new LinkedHashMap<>();
    if (request.detail() != null) {
      detail.putAll(request.detail());
    }
    detail.put("skipped", skipped);

    sql.execute(
        """
        insert into listing_keyword_crawl
          (id,tenant_id,listing_id,source,ingest_channel,crawled_at,accuracy_window_minutes,
           received_count,skipped_count,status,idempotency_key,detail,note,
           received_at,created_at,updated_at)
        values (?,?,?,?,?,?::timestamptz,?,?,?,'RECEIVED',?,?::jsonb,?,now(),now(),now())
        """,
        crawlId,
        tenant,
        listingId,
        source,
        channel,
        Timestamp.from(request.crawledAt().toInstant()),
        request.accuracyWindowMinutes(),
        request.ranks().size(),
        skipped.size(),
        idempotencyKey,
        writeJson(detail),
        note(request.note()));

    // ---- 落快照 + 落预警
    int alertCount = 0;
    for (ListingKeywordRankingRankInput input : applicable) {
      Record keyword = keywords.get(input.keywordId());
      Integer natural = input.naturalRank();
      Integer ad = input.adRank();

      // 🔴 「上一次」按抓取时间定（不是按行号）：同一次抓取的修正不会把自己当上一次
      Record previous =
          sql.fetchOne(
              "select natural_rank,ad_rank from listing_keyword_rank_snapshot"
                  + " where tenant_id=? and keyword_id=? and crawled_at < ?::timestamptz"
                  + " order by crawled_at desc, received_at desc limit 1",
              tenant,
              input.keywordId(),
              Timestamp.from(request.crawledAt().toInstant()));
      Integer previousNatural = previous == null ? null : previous.get("natural_rank", Integer.class);
      Integer previousAd = previous == null ? null : previous.get("ad_rank", Integer.class);

      sql.execute(
          """
          insert into listing_keyword_rank_snapshot
            (id,tenant_id,keyword_id,listing_id,natural_rank,ad_rank,crawled_at,
             accuracy_window_minutes,source,ingest_channel,last_crawl_id,revision,detail,
             received_at,created_at,updated_at)
          values (?,?,?,?,?,?,?::timestamptz,?,?,?,?,1,?::jsonb,now(),now(),now())
          on conflict (tenant_id,keyword_id,crawled_at,source) do update set
            natural_rank=excluded.natural_rank,
            ad_rank=excluded.ad_rank,
            accuracy_window_minutes=excluded.accuracy_window_minutes,
            ingest_channel=excluded.ingest_channel,
            last_crawl_id=excluded.last_crawl_id,
            revision=listing_keyword_rank_snapshot.revision+1,
            detail=excluded.detail,
            received_at=excluded.received_at,
            updated_at=now()
          """,
          Ids.next(),
          tenant,
          input.keywordId(),
          listingId,
          natural,
          ad,
          Timestamp.from(request.crawledAt().toInstant()),
          request.accuracyWindowMinutes(),
          source,
          channel,
          crawlId,
          writeJson(input.detail() == null ? Map.of() : input.detail()));

      Record snapshotRow =
          sql.fetchOne(
              "select id from listing_keyword_rank_snapshot"
                  + " where tenant_id=? and keyword_id=? and crawled_at=?::timestamptz and source=?",
              tenant,
              input.keywordId(),
              Timestamp.from(request.crawledAt().toInstant()),
              source);
      UUID snapshotId = snapshotRow == null ? null : snapshotRow.get("id", UUID.class);

      alertCount +=
          raiseAlerts(
              tenant,
              crawlId,
              snapshotId,
              listingId,
              input.keywordId(),
              natural,
              ad,
              previousNatural,
              previousAd,
              keyword,
              request.crawledAt(),
              request.accuracyWindowMinutes());
    }

    // ---- 收口抓取批次（终态不可再改，由 DB 触发器钉死）
    String status = skipped.isEmpty() ? "APPLIED" : "PARTIAL";
    sql.execute(
        "update listing_keyword_crawl set status=?, updated_at=now() where id=? and tenant_id=?",
        status,
        crawlId,
        tenant);

    return new ListingKeywordRankingCrawlResult(
        crawlId,
        listingId,
        status,
        request.ranks().size(),
        applicable.size(),
        skipped.size(),
        false,
        idempotencyKey,
        skipped,
        request.crawledAt(),
        request.accuracyWindowMinutes(),
        alertCount);
  }

  private static void requirePositive(Integer value, String field) {
    if (value != null && value <= 0) {
      throw unprocessable("「" + field + "」必须是正整数名次（收到 " + value + "）—— 名次从 1 起");
    }
  }

  /**
   * 🔴 落预警（**记录**，不是通知）。
   *
   * <p>四类判定：自然/广告低于阈值、自然/广告退步超阈值。
   * 阈值没配（null）就**不产生**该类预警 —— 「没配阈值」与「阈值是 0」是两件事。
   */
  private int raiseAlerts(
      UUID tenant,
      UUID crawlId,
      UUID snapshotId,
      UUID listingId,
      UUID keywordId,
      Integer natural,
      Integer ad,
      Integer previousNatural,
      Integer previousAd,
      Record keyword,
      OffsetDateTime detectedAt,
      int accuracyWindowMinutes) {
    Integer naturalThreshold = keyword.get("natural_rank_threshold", Integer.class);
    Integer adThreshold = keyword.get("ad_rank_threshold", Integer.class);
    Integer dropThreshold = keyword.get("rank_drop_threshold", Integer.class);
    int raised = 0;

    if (natural != null && naturalThreshold != null && natural > naturalThreshold) {
      insertAlert(tenant, crawlId, snapshotId, listingId, keywordId, "NATURAL_BELOW_THRESHOLD",
          natural, previousNatural, naturalThreshold, detectedAt, accuracyWindowMinutes);
      raised++;
    }
    if (ad != null && adThreshold != null && ad > adThreshold) {
      insertAlert(tenant, crawlId, snapshotId, listingId, keywordId, "AD_BELOW_THRESHOLD",
          ad, previousAd, adThreshold, detectedAt, accuracyWindowMinutes);
      raised++;
    }
    // 🔴 退步 = 名次数字变大（变差）。名次变小是**变好**，不产生预警。
    if (natural != null && previousNatural != null && dropThreshold != null
        && (natural - previousNatural) > dropThreshold) {
      insertAlert(tenant, crawlId, snapshotId, listingId, keywordId, "NATURAL_RANK_DROPPED",
          natural, previousNatural, dropThreshold, detectedAt, accuracyWindowMinutes);
      raised++;
    }
    if (ad != null && previousAd != null && dropThreshold != null
        && (ad - previousAd) > dropThreshold) {
      insertAlert(tenant, crawlId, snapshotId, listingId, keywordId, "AD_RANK_DROPPED",
          ad, previousAd, dropThreshold, detectedAt, accuracyWindowMinutes);
      raised++;
    }
    return raised;
  }

  private void insertAlert(
      UUID tenant,
      UUID crawlId,
      UUID snapshotId,
      UUID listingId,
      UUID keywordId,
      String alertType,
      Integer rankValue,
      Integer previousRank,
      Integer thresholdValue,
      OffsetDateTime detectedAt,
      int accuracyWindowMinutes) {
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("rankValue", rankValue == null ? "" : String.valueOf(rankValue));
    detail.put("previousRank", previousRank == null ? "" : String.valueOf(previousRank));
    detail.put("thresholdValue", thresholdValue == null ? "" : String.valueOf(thresholdValue));
    detail.put("accuracyWindowMinutes", String.valueOf(accuracyWindowMinutes));
    sql.execute(
        """
        insert into listing_keyword_rank_alert
          (id,tenant_id,keyword_id,listing_id,alert_type,rank_value,previous_rank,threshold_value,
           detected_at,crawl_id,snapshot_id,detail,created_at,updated_at)
        values (?,?,?,?,?,?,?,?,?::timestamptz,?,?,?::jsonb,now(),now())
        on conflict (tenant_id,crawl_id,keyword_id,alert_type) do nothing
        """,
        Ids.next(),
        tenant,
        keywordId,
        listingId,
        alertType,
        rankValue,
        previousRank,
        thresholdValue,
        Timestamp.from(detectedAt.toInstant()),
        crawlId,
        snapshotId,
        writeJson(detail));
  }

  /** 已落库的排名条数 = 指向这个批次的快照行数（可查事实，不靠冗余列）。 */
  private int appliedCountOf(Record crawl) {
    Long v =
        (Long)
            sql.fetchValue(
                "select count(*) from listing_keyword_rank_snapshot where tenant_id=? and last_crawl_id=?",
                currentTenant(),
                crawl.get("id", UUID.class));
    return v == null ? 0 : v.intValue();
  }

  // ==================================================================
  // 🔴 单个关键词的排名明细
  // ==================================================================

  /**
   * 🔴 单个关键词的排名明细。
   *
   * <p>🔴 {@code hasData=false} 时所有名次都是 null，{@code emptyReason} 写明是
   * 「还没抓过」还是「抓过但这个关键词没有数据」—— 那是两回事。
   */
  public ListingKeywordRankingDetail detail(String keywordId) {
    UUID tenant = currentTenant();
    UUID id = parseId(keywordId);
    ListingKeywordRankingKeywordRow row = loadKeyword(tenant, id);

    boolean hasData = row.snapshotCount() > 0;
    String emptyReason = "";
    if (!hasData) {
      emptyReason =
          row.crawlCount() == 0
              ? "这个关键词还没有排名抓取结果 —— 抓取任务到达后会出现在这里。"
              : "这个关键词被 " + row.crawlCount() + " 次抓取覆盖过，但目前还没有排名数据。";
    }
    Object lastCrawledAt =
        sql.fetchValue(
            "select max(crawled_at) from listing_keyword_rank_snapshot"
                + " where tenant_id=? and keyword_id=?",
            tenant,
            id);

    return new ListingKeywordRankingDetail(
        row.keywordId(),
        row.listingId(),
        row.externalItemId(),
        row.title(),
        row.shopCode(),
        row.platformCode(),
        row.siteCode(),
        row.keyword(),
        row.naturalRankThreshold(),
        row.adRankThreshold(),
        row.rankDropThreshold(),
        row.active(),
        row.note(),
        row.latestNaturalRank(),
        row.latestAdRank(),
        row.naturalRankChange(),
        row.adRankChange(),
        row.latestCrawledAt(),
        row.latestAccuracyWindowMinutes(),
        ACCURACY_NOTE,
        row.crawlCount(),
        row.snapshotCount(),
        row.openAlertCount(),
        row.totalAlertCount(),
        hasData,
        emptyReason,
        lastCrawledAt == null ? null : (OffsetDateTime) lastCrawledAt);
  }

  // ==================================================================
  // 排名历史
  // ==================================================================

  /**
   * 排名历史（真分页 + 时间范围筛选）。
   *
   * <p>🔴 没有数据时是<b>空数组</b>，不是假曲线 —— 一条平的假曲线会让人以为
   * 「这段时间排名没变」，而真相是「没抓到数据」。
   */
  public ListingKeywordRankingHistoryPage history(
      String keywordId, String from, String to, int page, int size) {
    UUID tenant = currentTenant();
    UUID id = parseId(keywordId);
    if (sql.fetchOne("select 1 from listing_keyword where tenant_id=? and id=?", tenant, id) == null) {
      throw notFound();
    }
    DayRange range = dayRange(from, to);

    StringBuilder where =
        new StringBuilder(" where s.tenant_id=? and s.keyword_id=? and s.crawled_at >= ?::timestamptz"
            + " and s.crawled_at <= ?::timestamptz");
    List<Object> binds =
        new ArrayList<>(
            List.of(tenant, id, Timestamp.from(range.from().toInstant()),
                Timestamp.from(range.to().toInstant())));

    long total = count(HISTORY_SELECT + where.toString(), binds.toArray());
    int lim = clampSize(size);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(lim);
    pageBinds.add(Math.max(0, page) * lim);
    List<ListingKeywordRankingHistoryRow> items =
        sql.fetch(
                HISTORY_SELECT
                    + where.toString()
                    + " order by s.crawled_at desc, s.received_at desc, s.id desc limit ? offset ?",
                pageBinds.toArray())
            .stream()
            .map(this::historyRow)
            .toList();
    return new ListingKeywordRankingHistoryPage(items, Math.max(0, page), lim, total);
  }

  /**
   * 排名历史取数。
   *
   * <p>🔴 「较上次的变化」用 lateral 取**该行之前**最近的一条 —— 「上一次」按抓取时间定，
   * <b>不落表</b>。
   */
  private static final String HISTORY_SELECT =
      """
      select s.id as snapshot_id, s.keyword_id, s.listing_id, k.keyword,
             k.platform_code, k.site_code,
             s.natural_rank, s.ad_rank,
             case when p.natural_rank is not null and s.natural_rank is not null
                  then s.natural_rank - p.natural_rank else null end as natural_rank_change,
             case when p.ad_rank is not null and s.ad_rank is not null
                  then s.ad_rank - p.ad_rank else null end as ad_rank_change,
             s.crawled_at, s.accuracy_window_minutes, s.source, s.ingest_channel,
             s.revision, s.received_at
      from listing_keyword_rank_snapshot s
      join listing_keyword k on k.tenant_id=s.tenant_id and k.id=s.keyword_id
      left join lateral (
        select p2.natural_rank, p2.ad_rank
        from listing_keyword_rank_snapshot p2
        where p2.tenant_id=s.tenant_id and p2.keyword_id=s.keyword_id
          and (p2.crawled_at, p2.received_at) < (s.crawled_at, s.received_at)
        order by p2.crawled_at desc, p2.received_at desc
        limit 1
      ) p on true
      """;

  private ListingKeywordRankingHistoryRow historyRow(Record r) {
    return new ListingKeywordRankingHistoryRow(
        r.get("snapshot_id", UUID.class),
        r.get("keyword_id", UUID.class),
        r.get("listing_id", UUID.class),
        nullToEmpty(r.get("keyword", String.class)),
        nullToEmpty(r.get("platform_code", String.class)),
        nullToEmpty(r.get("site_code", String.class)),
        integerOrNull(r, "natural_rank"),
        integerOrNull(r, "ad_rank"),
        integerOrNull(r, "natural_rank_change"),
        integerOrNull(r, "ad_rank_change"),
        r.get("crawled_at", OffsetDateTime.class),
        intOf(r, "accuracy_window_minutes"),
        ACCURACY_NOTE,
        nullToEmpty(r.get("source", String.class)),
        nullToEmpty(r.get("ingest_channel", String.class)),
        intOf(r, "revision"),
        r.get("received_at", OffsetDateTime.class));
  }

  // ==================================================================
  // 趋势
  // ==================================================================

  /**
   * 趋势序列（按日 / 周 / 月归集）。
   *
   * <p>🔴 归集一律按 <b>UTC</b> 做（{@code crawled_at at time zone 'UTC'}）——
   * 否则同一份数据在不同的库里会算出不同的桶。
   *
   * <p>🔴 没有数据时 {@code points} 是<b>空数组</b>，不是一条平线。
   */
  public ListingKeywordRankingTrend trend(
      String keywordId, String from, String to, String granularity) {
    UUID tenant = currentTenant();
    UUID id = parseId(keywordId);
    Record keyword =
        sql.fetchOne(
            "select id, keyword from listing_keyword where tenant_id=? and id=?", tenant, id);
    if (keyword == null) {
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
    DayRange range = dayRange(from, to);

    String bucketExpr =
        switch (gran) {
          case "WEEK" -> "date_trunc('week', crawled_at at time zone 'UTC')";
          case "MONTH" -> "date_trunc('month', crawled_at at time zone 'UTC')";
          default -> "date_trunc('day', crawled_at at time zone 'UTC')";
        };

    List<ListingKeywordRankingTrendPoint> points =
        sql.fetch(
                """
                select to_char(%s, 'YYYY-MM-DD') as bucket,
                       (array_agg(natural_rank order by crawled_at desc, received_at desc))[1]
                         as last_natural_rank,
                       (array_agg(ad_rank order by crawled_at desc, received_at desc))[1]
                         as last_ad_rank,
                       min(natural_rank) as best_natural_rank,
                       max(natural_rank) as worst_natural_rank,
                       min(ad_rank) as best_ad_rank,
                       max(ad_rank) as worst_ad_rank,
                       count(*) as sample_count,
                       max(crawled_at) as last_crawled_at
                from listing_keyword_rank_snapshot
                where tenant_id=? and keyword_id=?
                  and crawled_at >= ?::timestamptz and crawled_at <= ?::timestamptz
                group by %s
                order by 1
                """
                    .formatted(bucketExpr, bucketExpr),
                tenant,
                id,
                Timestamp.from(range.from().toInstant()),
                Timestamp.from(range.to().toInstant()))
            .stream()
            .map(
                r ->
                    new ListingKeywordRankingTrendPoint(
                        nullToEmpty(r.get("bucket", String.class)),
                        integerOrNull(r, "last_natural_rank"),
                        integerOrNull(r, "last_ad_rank"),
                        integerOrNull(r, "best_natural_rank"),
                        integerOrNull(r, "worst_natural_rank"),
                        integerOrNull(r, "best_ad_rank"),
                        integerOrNull(r, "worst_ad_rank"),
                        intOf(r, "sample_count"),
                        r.get("last_crawled_at", OffsetDateTime.class)))
            .toList();

    return new ListingKeywordRankingTrend(
        id,
        nullToEmpty(keyword.get("keyword", String.class)),
        gran,
        range.from().toLocalDate().toString(),
        range.to().toLocalDate().toString(),
        ACCURACY_NOTE,
        points,
        !points.isEmpty());
  }

  // ==================================================================
  // 变化预警
  // ==================================================================

  /**
   * 变化预警列表（真分页 + 筛选）。
   *
   * <p>🔴 预警是**记录**，本模块**不发通知**（下游 1 工作台预警未交付）。
   */
  public ListingKeywordRankingAlertPage alerts(
      String keywordId, String listingId, String alertType, String acknowledged, int page, int size) {
    UUID tenant = currentTenant();
    StringBuilder where = new StringBuilder(" where a.tenant_id=?");
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);

    String keyword = blankToNull(keywordId);
    if (keyword != null) {
      where.append(" and a.keyword_id=?");
      binds.add(parseId(keyword));
    }
    String listing = blankToNull(listingId);
    if (listing != null) {
      where.append(" and a.listing_id=?");
      binds.add(parseId(listing));
    }
    String type = blankToNull(alertType);
    if (type != null) {
      String upper = type.toUpperCase(Locale.ROOT);
      if (!ALERT_TYPES.contains(upper)) {
        throw badRequest(
            "未知的预警类型「" + type + "」。只认这几种：" + String.join(" / ", ALERT_TYPES));
      }
      where.append(" and a.alert_type=?");
      binds.add(upper);
    }
    String ack = text(acknowledged).toUpperCase(Locale.ROOT);
    if (!ack.isEmpty() && !List.of("ALL", "OPEN", "ACKNOWLEDGED").contains(ack)) {
      throw badRequest(
          "未知的确认状态「" + text(acknowledged) + "」。只认：ALL / OPEN / ACKNOWLEDGED");
    }
    if (ack.equals("OPEN")) {
      where.append(" and a.acknowledged_at is null");
    } else if (ack.equals("ACKNOWLEDGED")) {
      where.append(" and a.acknowledged_at is not null");
    }

    long total = count(ALERT_SELECT + where.toString(), binds.toArray());

    StringBuilder openWhere = new StringBuilder(" where a.tenant_id=? and a.acknowledged_at is null");
    List<Object> openBinds = new ArrayList<>();
    openBinds.add(tenant);
    if (keyword != null) {
      openWhere.append(" and a.keyword_id=?");
      openBinds.add(parseId(keyword));
    }
    if (listing != null) {
      openWhere.append(" and a.listing_id=?");
      openBinds.add(parseId(listing));
    }
    if (type != null) {
      openWhere.append(" and a.alert_type=?");
      openBinds.add(type.toUpperCase(Locale.ROOT));
    }
    long openCount = count(ALERT_SELECT + openWhere.toString(), openBinds.toArray());

    int lim = clampSize(size);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(lim);
    pageBinds.add(Math.max(0, page) * lim);
    List<ListingKeywordRankingAlertRow> items =
        sql.fetch(
                ALERT_SELECT
                    + where.toString()
                    + " order by a.detected_at desc, a.id desc limit ? offset ?",
                pageBinds.toArray())
            .stream()
            .map(this::alertRow)
            .toList();
    return new ListingKeywordRankingAlertPage(items, Math.max(0, page), lim, total, openCount);
  }

  private static final String ALERT_SELECT =
      """
      select a.id as alert_id, a.keyword_id, a.listing_id, k.keyword, l.title,
             sh.code as shop_code, k.platform_code, k.site_code,
             a.alert_type, a.rank_value, a.previous_rank, a.threshold_value,
             a.detected_at, a.acknowledged_at, a.acknowledged_by, a.crawl_id, a.snapshot_id
      from listing_keyword_rank_alert a
      join listing_keyword k on k.tenant_id=a.tenant_id and k.id=a.keyword_id
      join listing l on l.tenant_id=a.tenant_id and l.id=a.listing_id
      join shop sh on sh.tenant_id=a.tenant_id and sh.id=l.shop_id
      """;

  private ListingKeywordRankingAlertRow alertRow(Record r) {
    return new ListingKeywordRankingAlertRow(
        r.get("alert_id", UUID.class),
        r.get("keyword_id", UUID.class),
        r.get("listing_id", UUID.class),
        nullToEmpty(r.get("keyword", String.class)),
        nullToEmpty(r.get("title", String.class)),
        nullToEmpty(r.get("shop_code", String.class)),
        nullToEmpty(r.get("platform_code", String.class)),
        nullToEmpty(r.get("site_code", String.class)),
        nullToEmpty(r.get("alert_type", String.class)),
        integerOrNull(r, "rank_value"),
        integerOrNull(r, "previous_rank"),
        integerOrNull(r, "threshold_value"),
        r.get("detected_at", OffsetDateTime.class),
        r.get("acknowledged_at", OffsetDateTime.class),
        r.get("acknowledged_by", UUID.class),
        r.get("crawl_id", UUID.class),
        r.get("snapshot_id", UUID.class));
  }

  /**
   * 🔴 人工确认一条预警。
   *
   * <p>预警**只是记录**（下游 1 工作台预警未交付，本模块不发通知），
   * 但「有没有人看过」这个事实仍然要留痕 ⇒ {@code acknowledged_at} + {@code acknowledged_by}。
   */
  public ListingKeywordRankingAlertRow acknowledge(String alertId, UUID actorId) {
    UUID tenant = currentTenant();
    UUID id = parseId(alertId);
    int changed =
        sql.execute(
            "update listing_keyword_rank_alert"
                + " set acknowledged_at=now(), acknowledged_by=?, updated_at=now()"
                + " where tenant_id=? and id=? and acknowledged_at is null",
            actorId,
            tenant,
            id);
    if (changed == 0) {
      // 已经确认过 ⇒ 返回既有那条（幂等），找不到才是 404
      Record existing =
          sql.fetchOne(ALERT_SELECT + " where a.tenant_id=? and a.id=?", tenant, id);
      if (existing == null) {
        throw notFound();
      }
      return alertRow(existing);
    }
    Record row = sql.fetchOne(ALERT_SELECT + " where a.tenant_id=? and a.id=?", tenant, id);
    if (row == null) {
      throw notFound();
    }
    return alertRow(row);
  }

  // ==================================================================
  // 🔴 关键词维度报表
  // ==================================================================

  /**
   * 🔴 关键词维度报表（**真 SQL 聚合**，不是前端拼）。
   *
   * <p>聚合键是（平台, 站点, 关键词）—— 站点码只在平台内唯一，平台才是父级。
   *
   * <p>🔴 平均名次在**没有样本**时是 null（不是 0）：报 0 会被读成「平均第 0 名」，
   * 那是结论不是缺口。
   */
  public ListingKeywordRankingReport report(
      String platformCode, String siteCode, String keyword, String from, String to) {
    UUID tenant = currentTenant();
    DayRange range = dayRange(from, to);

    // 🔴 筛选条件**不带 where 关键字** —— 它要拼在 LEFT JOIN **之后**
    //    （SQL 的 WHERE 必须在所有 JOIN 之后，写在 JOIN 前面是语法错误）。
    StringBuilder filter =
        new StringBuilder(
            " k.tenant_id=? and s.crawled_at >= ?::timestamptz and s.crawled_at <= ?::timestamptz");
    List<Object> binds =
        new ArrayList<>(
            List.of(
                tenant,
                Timestamp.from(range.from().toInstant()),
                Timestamp.from(range.to().toInstant())));

    String platform = blankToNull(platformCode);
    if (platform != null) {
      filter.append(" and k.platform_code=?");
      binds.add(platform);
    }
    String site = blankToNull(siteCode);
    if (site != null) {
      filter.append(" and k.site_code=?");
      binds.add(site);
    }
    String word = blankToNull(keyword);
    if (word != null) {
      filter.append(" and k.keyword ilike ?");
      binds.add("%" + word + "%");
    }

    // 🔴 绑定顺序 = SQL 里的 ? 出现顺序：breaches(3) → open_alerts(1) → 外层 where(3)。
    //    ⚠️ 必须把**全部**绑定放进同一个数组再 `toArray()` 传进去 ——
    //    Java 的可变参数在「前面还有别的实参」时**不会**展开尾部的 Object[]，
    //    那个数组会被当成**一个**绑定值（jOOQ 还会把它渲染成 `cast(? as any[])`），
    //    结果是 PG 报「参数 N 没有值」。踩过一次，钉在这里。
    List<Object> allBinds = new ArrayList<>();
    allBinds.add(tenant);
    allBinds.add(Timestamp.from(range.from().toInstant()));
    allBinds.add(Timestamp.from(range.to().toInstant()));
    allBinds.add(tenant);
    allBinds.addAll(binds);

    List<ListingKeywordRankingReportRow> rows =
        sql.fetch(
                """
                with breaches as (
                  select kk.platform_code, kk.site_code, kk.keyword,
                         count(*) as below_threshold_count
                  from listing_keyword_rank_snapshot s
                  join listing_keyword kk on kk.tenant_id=s.tenant_id and kk.id=s.keyword_id
                  where s.tenant_id=?
                    and s.crawled_at >= ?::timestamptz and s.crawled_at <= ?::timestamptz
                    and (
                      (s.natural_rank is not null and kk.natural_rank_threshold is not null
                       and s.natural_rank > kk.natural_rank_threshold)
                      or
                      (s.ad_rank is not null and kk.ad_rank_threshold is not null
                       and s.ad_rank > kk.ad_rank_threshold)
                    )
                  group by kk.platform_code, kk.site_code, kk.keyword
                ), open_alerts as (
                  select kk.platform_code, kk.site_code, kk.keyword,
                         count(*) as open_alert_count
                  from listing_keyword_rank_alert a
                  join listing_keyword kk on kk.tenant_id=a.tenant_id and kk.id=a.keyword_id
                  where a.tenant_id=? and a.acknowledged_at is null
                  group by kk.platform_code, kk.site_code, kk.keyword
                )
                select k.platform_code, k.site_code, k.keyword,
                       count(distinct k.listing_id) as listing_count,
                       count(s.id) as sample_count,
                       round(avg(s.natural_rank), 2) as average_natural_rank,
                       min(s.natural_rank) as best_natural_rank,
                       max(s.natural_rank) as worst_natural_rank,
                       round(avg(s.ad_rank), 2) as average_ad_rank,
                       min(s.ad_rank) as best_ad_rank,
                       max(s.ad_rank) as worst_ad_rank,
                       max(s.crawled_at) as last_crawled_at,
                       max(s.accuracy_window_minutes) as accuracy_window_minutes,
                       coalesce(b.below_threshold_count, 0) as below_threshold_count,
                       coalesce(o.open_alert_count, 0) as open_alert_count
                from listing_keyword k
                join listing_keyword_rank_snapshot s
                  on s.tenant_id=k.tenant_id and s.keyword_id=k.id
                left join breaches b on b.platform_code=k.platform_code
                  and b.site_code=k.site_code and b.keyword=k.keyword
                left join open_alerts o on o.platform_code=k.platform_code
                  and o.site_code=k.site_code and o.keyword=k.keyword
                """
                    + "where"
                    + filter.toString()
                    + " group by k.platform_code, k.site_code, k.keyword,"
                    + " b.below_threshold_count, o.open_alert_count"
                    + " order by k.platform_code, k.site_code, k.keyword",
                allBinds.toArray())
            .stream()
            .map(
                r ->
                    new ListingKeywordRankingReportRow(
                        nullToEmpty(r.get("keyword", String.class)),
                        nullToEmpty(r.get("platform_code", String.class)),
                        nullToEmpty(r.get("site_code", String.class)),
                        longOf(r, "listing_count"),
                        longOf(r, "sample_count"),
                        r.get("average_natural_rank", BigDecimal.class),
                        integerOrNull(r, "best_natural_rank"),
                        integerOrNull(r, "worst_natural_rank"),
                        r.get("average_ad_rank", BigDecimal.class),
                        integerOrNull(r, "best_ad_rank"),
                        integerOrNull(r, "worst_ad_rank"),
                        longOf(r, "below_threshold_count"),
                        longOf(r, "open_alert_count"),
                        r.get("last_crawled_at", OffsetDateTime.class),
                        integerOrNull(r, "accuracy_window_minutes")))
            .toList();

    long keywordCount =
        rows.stream().map(ListingKeywordRankingReportRow::keyword).distinct().count();
    return new ListingKeywordRankingReport(ACCURACY_NOTE, RANK_DEFINITIONS, rows, keywordCount);
  }
}
