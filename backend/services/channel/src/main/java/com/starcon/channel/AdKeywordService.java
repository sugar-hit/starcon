package com.starcon.channel;

import static com.starcon.channel.AdKeywordModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
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
 * 14.1.3.1 关键词管理。
 *
 * <p>蓝图：「关键词管理与分析：搜索词报告、关键词表现（展示、点击、转化、CPC、ACOS）、否定词建议、词频与排名、批量调价与否定。输入：搜索词与关键词数据同步；批量操作参数。输出：关键词报表 + 否定建议 + 批量调整任务。关系：上游 = 平台搜索词报告；下游 = 14.2.1.1 竞价、4.5.1.2 排名。<b>搜索词报告是发现浪费的入口</b>：高曝光零转化词应尽快否定，否则预算持续流失。」
 *
 * <p>🔴 本类的核心不变量，每一个都有集成测试逐条钉住：
 *
 * <ol>
 *   <li><b>🔴🔴 归一类型与原始类型并存</b> —— {@code internal_ad_type} 与
 *       {@code platform_ad_type} 同表同存，响应里同时返回。归一口径**不在本模块**：
 *       走 14.1.2.2 的两层解析（租户覆盖 &gt; 平台默认 &gt; 未映射），
 *       声明值与解析结果不一致直接拒（照抄 14.1.2.2 登记档案的同一纪律）。
 *   <li><b>🔴🔴 ACOS 的分母是销售额，且为 0 时是 null</b> —— 「可比但真的是零」与
 *       「压根算不出来」是两件事，写 0 会被下游读成「效率完美」。
 *   <li><b>🔴🔴 数据截至时间是契约的一部分</b> —— 每个带指标的响应都带 {@code dataAsOf}
 *       与 {@code syncStatus}，把「没跑这个词」与「还没同步到数据」分开。
 *   <li><b>🔴🔴 否定必须带依据</b> —— 依据由服务端按与建议**同一套口径**现算，
 *       写进 {@code evidence}；DB 触发器也拦空依据。
 *   <li><b>🔴🔴 批量调价有护栏，越线整批不动</b> —— 单次条目上限、幅度上限、绝对上限、
 *       总上调上限；越线时**整批拒绝**并逐条标跳过。调价是花钱的动作，
 *       「部分执行」比「不动」危险得多。
 *   <li><b>🔴 排名与词频引用 4.5.1.2</b> —— 名次读的是 4.5.1.2 的
 *       {@code listing_keyword_rank_snapshot}，词频读的是 4.5.1.2 的 {@code listing_keyword}。
 *       本模块**不新建排名表、不自算名次**。
 *   <li><b>🔴 竞价只做「读形状」与「批量调价」这个动作</b> —— 竞价策略引擎、动态竞价、
 *       目标 ACOS 反推竞价归 14.2.1.1，本模块**不实现**（能力表 available=false）。
 * </ol>
 *
 * <p>🔴 时间口径：{@code synced_at} / {@code created_at} 是 {@code timestamptz}，用
 * {@code OffsetDateTime} 绑定、values 位置写 {@code ?::timestamptz} —— 用
 * {@code Timestamp.valueOf(LocalDateTime...)} 会静默偏 −28800s。
 * 指标按 <b>UTC 日</b>归集（{@code bucket_date} 是 {@code date}），
 * 否则同一份数据在不同的库里会算出不同的桶。
 *
 * <p>🔴 数据访问全部走 {@code DSLContext} 的**显式 SQL**（{@code sql.execute(...)} /
 * {@code sql.fetch(...)}）—— 本服务的 {@code jooq-codegen} 是**不默认激活的 profile**
 * （需要 {@code DB_URL} 等环境变量），代码里**不引用任何生成类**。
 */
@Service
public class AdKeywordService {

  // ==================================================================
  // 护栏常量（照抄 14.1.1.3 的批量护栏口径：单次上限 + 总消耗上限）
  // ==================================================================

  /** 🔴 单次批量条目上限。 */
  public static final int MAX_BATCH_ITEMS = 200;

  /** 🔴 单条竞价调整幅度上限（±50%）。 */
  public static final BigDecimal MAX_BID_DELTA_RATIO = new BigDecimal("0.50");

  /** 🔴 单条竞价绝对上限。 */
  public static final BigDecimal MAX_BID_ABSOLUTE = new BigDecimal("1000.00");

  /** 🔴 单次批量**上调**总额上限（按本次声明的币种计价；下调不限 —— 下调不烧钱）。 */
  public static final BigDecimal MAX_TOTAL_BID_INCREASE = new BigDecimal("5000.00");

  /** 🔴 否定建议的默认判定阈值。 */
  public static final long DEFAULT_MIN_IMPRESSIONS = 100L;

  /** 🔴 否定建议的默认点击阈值。 */
  public static final long DEFAULT_MIN_CLICKS = 5L;

  /** 归一类型未映射时的显式值（照抄 14.1.2.2 / 2.1.2.1：不是兜底）。 */
  private static final String UNMAPPED = "UNMAPPED";

  /** 关键词状态的合法取值。 */
  private static final List<String> KEYWORD_STATUSES = List.of("ACTIVE", "PAUSED", "ARCHIVED");

  /** 否定类型的合法取值。 */
  private static final List<String> NEGATION_TYPES = List.of("NEGATIVE_EXACT", "NEGATIVE_PHRASE");

  /** 否定状态的合法取值。 */
  private static final List<String> NEGATION_STATUSES = List.of("DECIDED", "PUSHED", "REJECTED");

  /** 批量动作类型的合法取值。 */
  private static final List<String> BULK_ACTION_TYPES = List.of("BID_ADJUST", "NEGATE");

  /** 批量任务状态的合法取值。 */
  private static final List<String> BULK_ACTION_STATUSES =
      List.of("RECEIVED", "APPLIED", "PARTIAL", "REJECTED");

  /** 指标与搜索词的来源。 */
  private static final List<String> METRIC_SOURCES = List.of("PLATFORM", "API", "IMPORT");

  // ==================================================================
  // 取数 SQL
  // ==================================================================

  /**
   * 关键词取数：真 SQL 聚合 + lateral 取区间指标 / 最近一次排名 / 词频 / 否定状态。
   *
   * <p>🔴 四条 lateral 各自独立：
   *
   * <ul>
   *   <li><b>指标</b>：区间内 {@code ad_keyword_metric} 的聚合（ACOS / CPC / CTR / CVR
   *       **现算**，分母为 0 给 null）。
   *   <li><b>排名</b>：{@code listing_keyword_rank_snapshot} 里**最近一次**
   *       （{@code order by crawled_at desc, received_at desc limit 1}）——
   *       用 {@code limit 1} 而不是 {@code array_agg}：{@code array_agg} 会**跳过 null**，
   *       「平台没报名次」会被读成上一条有名字次的行。
   *   <li><b>词频</b>：4.5.1.2 的 {@code listing_keyword} 里同平台同词被多少个商品监控着。
   *   <li><b>否定</b>：该关键词下有没有否定记录。
   * </ul>
   */
  private static final String KEYWORD_SELECT =
      """
      select k.id as keyword_id, k.platform_id, f.code as platform_code, k.shop_id, sh.code as shop_code,
             k.external_campaign_id, k.external_keyword_id, k.keyword,
             k.internal_ad_type, k.platform_ad_type, k.bid, k.bid_currency, k.status,
             k.listing_keyword_id, k.note, k.created_at, k.updated_at,
             coalesce(m.impressions,0) as impressions, coalesce(m.clicks,0) as clicks,
             coalesce(m.conversions,0) as conversions, coalesce(m.spend,0) as spend,
             coalesce(m.sales,0) as sales, coalesce(m.currency,'') as currency,
             coalesce(m.active_days,0) as active_days, m.synced_at as metric_synced_at,
             case when coalesce(m.sales,0) > 0 then round(coalesce(m.spend,0)/m.sales,4) else null end as acos,
             case when coalesce(m.clicks,0) > 0 then round(coalesce(m.spend,0)/m.clicks,4) else null end as cpc,
             case when coalesce(m.impressions,0) > 0
                  then round(coalesce(m.clicks,0)::numeric/m.impressions,6) else null end as ctr,
             case when coalesce(m.clicks,0) > 0
                  then round(coalesce(m.conversions,0)::numeric/m.clicks,6) else null end as cvr,
             case when exists (select 1 from ad_keyword_metric x
                                where x.tenant_id=k.tenant_id and x.keyword_id=k.id)
                  then true else false end as has_metrics,
             r.natural_rank, r.ad_rank, r.crawled_at as ranking_crawled_at,
             r.accuracy_window_minutes as ranking_accuracy_window_minutes,
             coalesce(fq.frequency,0) as frequency,
             coalesce(fq.listing_count,0) as frequency_listing_count,
             case when ng.negated then true else false end as negated, ng.latest_negative_id
      from ad_keyword k
      join platform f on f.id = k.platform_id
      join shop sh on sh.tenant_id = k.tenant_id and sh.id = k.shop_id
      left join lateral (
        select sum(mt.impressions) as impressions, sum(mt.clicks) as clicks,
               sum(mt.conversions) as conversions, sum(mt.spend) as spend, sum(mt.sales) as sales,
               max(mt.synced_at) as synced_at, count(*) as active_days, max(mt.currency) as currency
        from ad_keyword_metric mt
        where mt.tenant_id = k.tenant_id and mt.keyword_id = k.id
          and mt.bucket_date >= ? and mt.bucket_date <= ?
      ) m on true
      left join lateral (
        select s.natural_rank, s.ad_rank, s.crawled_at, s.accuracy_window_minutes
        from listing_keyword_rank_snapshot s
        where s.tenant_id = k.tenant_id and s.keyword_id = k.listing_keyword_id
        order by s.crawled_at desc, s.received_at desc
        limit 1
      ) r on true
      left join lateral (
        select count(*) as frequency, count(distinct lk.listing_id) as listing_count
        from listing_keyword lk
        where lk.tenant_id = k.tenant_id and lk.platform_code = f.code and lk.keyword = k.keyword
      ) fq on true
      left join lateral (
        select count(*) > 0 as negated,
               (array_agg(n2.id order by n2.created_at desc))[1] as latest_negative_id
        from ad_negative_keyword n2
        where n2.tenant_id = k.tenant_id and n2.keyword_id = k.id
      ) ng on true
      """;

  /** 关键词列表的筛选条件（可复用给 count 查询）。 */
  private static final class Filters {
    final StringBuilder where;
    final List<Object> binds = new ArrayList<>();

    /**
     * @param alias 主表别名（不同查询里主表不总是 {@code k} —— 否定记录的主表是 {@code n}）。
     *     🔴 别名写错时 SQL 报「missing FROM-clause entry」，而不是静默少查一点。
     */
    Filters(String alias) {
      where = new StringBuilder(" where " + alias + ".tenant_id = ?");
    }

    void add(String clause, Object... values) {
      where.append(clause);
      binds.addAll(List.of(values));
    }

    /**
     * 🔴 只取「 and ...」那一段（不含租户锚点），供**换别名**的查询复用。
     *
     * <p>子查询把 {@code ad_keyword k} 换成了 {@code ad_search_term st}，
     * 租户锚点要自己写一遍 —— 但**筛选条件本身**必须复用同一份，
     * 否则「列表里筛平台 A、搜索词报告里没筛平台 A」这种不一致会看不出来。
     */
    String extra() {
      int at = where.indexOf(" and ");
      return at >= 0 ? where.substring(at) : "";
    }
  }

  private final DSLContext sql;
  private final ObjectMapper json;
  private final AdTypeDiffService adTypes;

  public AdKeywordService(DSLContext sql, ObjectMapper json, AdTypeDiffService adTypes) {
    this.sql = sql;
    this.json = json;
    this.adTypes = adTypes;
  }

  // ==================================================================
  // 小工具
  // ==================================================================

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static String blankToNull(String value) {
    String trimmed = text(value);
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static ResponseStatusException notFound(String what) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, what + "不存在");
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException unprocessable(String message) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
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

  private <T> T one(Class<T> type, String query, Object... binds) {
    Record row = sql.fetchOne(query, binds);
    return row == null ? null : row.get(0, type);
  }

  private long count(String query, Object... binds) {
    Long v = one(Long.class, query, binds);
    return v == null ? 0L : v;
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
      throw badRequest("id 格式不正确");
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

  private static String note(String raw) {
    String v = text(raw);
    return v.length() > 500 ? v.substring(0, 500) : v;
  }

  private static BigDecimal money(BigDecimal value) {
    if (value == null) {
      return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    }
    return value.setScale(4, RoundingMode.HALF_UP);
  }

  private static long longOf(Record r, String field) {
    Long v = r.get(field, Long.class);
    return v == null ? 0L : v;
  }

  private static int intOf(Record r, String field) {
    Long v = r.get(field, Long.class);
    return v == null ? 0 : v.intValue();
  }

  /** long → int（计数类字段用；jOOQ 的 count(*) 回来是 long）。 */
  private static int asInt(long value) {
    return (int) Math.min(Integer.MAX_VALUE, Math.max(Integer.MIN_VALUE, value));
  }

  private static BigDecimal decimalOf(Record r, String field) {
    BigDecimal v = r.get(field, BigDecimal.class);
    return v == null ? null : v;
  }

  private static String stringOf(Record r, String field) {
    String v = r.get(field, String.class);
    return v == null ? "" : v;
  }

  private static OffsetDateTime timeOf(Record r, String field) {
    Timestamp v = r.get(field, Timestamp.class);
    return v == null ? null : v.toInstant().atOffset(ZoneOffset.UTC);
  }

  private static Timestamp bind(OffsetDateTime value) {
    return Timestamp.from(value.toInstant());
  }

  /** 🔴 日区间（UTC）：默认近 30 天。 */
  private record DayRange(LocalDate from, LocalDate to) {}

  private DayRange dayRange(String from, String to) {
    LocalDate toDate = parseDate(to, "结束日期");
    if (toDate == null) {
      toDate = LocalDate.now(ZoneOffset.UTC);
    }
    LocalDate fromDate = parseDate(from, "起始日期");
    if (fromDate == null) {
      fromDate = toDate.minusDays(29);
    }
    if (fromDate.isAfter(toDate)) {
      throw badRequest("起始日期不能晚于结束日期（" + fromDate + " > " + toDate + "）");
    }
    return new DayRange(fromDate, toDate);
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 🔴 逐维度能力声明。
   *
   * <p>本模块在本仓能**真实交付**的是「关键词登记 + 表现数据同步 + 搜索词报告 + 否定建议 +
   * 否定记录 + 批量调价与否定 + 关键词报表 + 词频与排名」。
   *
   * <p>它<b>不能</b>在本批真实交付的有四段，逐条在下面声明而不是假装具备：
   * 14.2.1.1 竞价策略引擎、14.1.3.2 投放定位、14.1.1.1 活动列表、平台否定推送。
   */
  public AdKeywordCapabilities capabilities() {
    return new AdKeywordCapabilities(
        List.of(
            new AdKeywordCapabilityDimension(
                "keyword_registry",
                "🔴 投放关键词登记与维护：平台 + 店铺 + 外部活动 id + 外部关键词 id + 关键词原文，"
                    + "带当前竞价与竞价币种；归档是**软删除**（表现与否定历史必须留着）",
                true, "ad_keyword（V274 新表）", "",
                "外部活动 id 是**外部引用**而不是跨车道外键 —— 14.1.1.1 活动列表是同批并行的另一条车道，"
                    + "建 FK 会让两边的迁移互相等"),
            new AdKeywordCapabilityDimension(
                "keyword_metrics_sync",
                "🔴 关键词表现数据同步（**真实写入口**）：按 UTC 日一行，带展示 / 点击 / 转化 / "
                    + "花费 / 销售额 / 币种与**数据截至时间**；批次级幂等",
                true, "ad_keyword_metric（V274 新表）", "",
                "平台搜索词报告的同步器**未交付**（上游 = 平台侧）—— 本模块自带真实写入口，"
                    + "同步器归 02 通道，能力表里单列"),
            new AdKeywordCapabilityDimension(
                "search_term_report",
                "🔴 搜索词报告（**真实写入口** + 报告读）：用户实际搜出来的词，"
                    + "与「我们投了哪个词」分开 —— 混在一张表里「高曝光零转化」就永远算不出来",
                true, "ad_search_term（V274 新表）", "",
                "「高曝光零转化」是浪费的判定依据，缺了它，否定建议就只剩一个词表"),
            new AdKeywordCapabilityDimension(
                "negative_suggestions",
                "🔴 否定词建议：三条规则（高曝光零转化 / 花了钱没转化 / ACOS 超标），"
                    + "每条建议都带**实际数值**（曝光 / 点击 / 转化 / 花费 / 销售额 / ACOS / 截至时间）",
                true, "ad_search_term + ad_keyword_metric 现算", "",
                "规则在服务端判，前端不自己判 —— 两处各判一次会出现两个版本"),
            new AdKeywordCapabilityDimension(
                "negative_keywords",
                "🔴 否定记录 + 依据快照 + 平台回执登记：依据由服务端按与建议**同一套口径**现算，"
                    + "DB 触发器也拦空依据",
                true, "ad_negative_keyword（V274 新表）", "",
                "本模块**不推平台**（推给平台广告 API 归 02 通道 / 平台适配器），"
                    + "但回执必须能进来，否则否定永远停在「已决定」"),
            new AdKeywordCapabilityDimension(
                "bulk_bid_and_negate",
                "🔴 批量调价与否定：单次条目上限、幅度上限、绝对上限、总上调上限四道护栏；"
                    + "越线**整批不动**；逐条返回执行结果（成了 / 没成 / 为什么没成）",
                true, "ad_keyword_bulk_action + ad_keyword_bulk_action_item（V274 新表）", "",
                "调价是花钱的动作，重复执行就是事故 ⇒ 批次级幂等"),
            new AdKeywordCapabilityDimension(
                "keyword_report",
                "🔴 关键词报表：按平台 / 店铺 / 活动 / 归一类型聚合，**按币种分行**"
                    + "（跨币种不可相加）",
                true, "ad_keyword_metric 聚合", "",
                "ACOS / CPC / CTR / CVR 全部现算，分母为 0 给 null"),
            new AdKeywordCapabilityDimension(
                "frequency_and_rank",
                "🔴 词频与排名：名次读的是 **4.5.1.2 的 ranking 快照**（同一张表、同一套 null 语义），"
                    + "词频读的是 4.5.1.2 的关键词集合（同一个词被多少个商品监控着）",
                true, "listing_keyword / listing_keyword_rank_snapshot（4.5.1.2 只读引用）", "",
                "本模块**不新建排名表、不自算名次** —— 两套互不相干的排名口径会让运营无所适从"),
            new AdKeywordCapabilityDimension(
                "bid_strategy_engine",
                "🔴 竞价策略引擎、动态竞价、目标 ACOS 反推竞价 —— **本模块不实现**",
                false, "14.2.1.1 竞价管理", "14.2.1.1 竞价管理",
                "本模块只提供「关键词当前竞价」的**读取形状**与「批量调价」这个**动作**。"
                    + "策略、建议竞价、动态调价都归 14.2.1.1"),
            new AdKeywordCapabilityDimension(
                "targeting",
                "🔴 投放定位（地域 / 人群 / 设备 / 时段 / 匹配类型）—— **本模块不实现**",
                false, "14.1.3.2 投放定位", "14.1.3.2 投放定位",
                "本模块**不存也不展示**任何定位维度；匹配类型同属 14.1.3.2"),
            new AdKeywordCapabilityDimension(
                "campaign_binding",
                "🔴 广告活动的实体与活动级报表 —— **本模块不实现**",
                false, "14.1.1.1 活动列表", "14.1.1.1 活动列表",
                "本模块用「平台 + 店铺 + 外部活动 id」表达活动，不建跨车道 FK；"
                    + "活动级报表待 14.1.1.1 落地后按外部 id 对接"),
            new AdKeywordCapabilityDimension(
                "platform_negative_push",
                "🔴 把否定推送到平台广告 API —— **本模块不实现**",
                false, "02 通道 / 平台适配器", "02 渠道与店铺（平台适配器）",
                "本模块只登记否定与平台回执；推送归平台适配器，"
                    + "否则同一件事会有两个投递路径")),
        KEYWORD_STATUSES,
        NEGATION_TYPES,
        NEGATION_STATUSES,
        BULK_ACTION_TYPES,
        BULK_ACTION_STATUSES,
        METRIC_SOURCES,
        METRIC_DEFINITIONS,
        FREQUENCY_DEFINITIONS,
        NEGATIVE_EVIDENCE_NOTE,
        GUARDRAIL_NOTE,
        Map.of(
            "maxBatchItems", MAX_BATCH_ITEMS,
            "maxBidDeltaRatio", MAX_BID_DELTA_RATIO,
            "maxBidAbsolute", MAX_BID_ABSOLUTE,
            "maxTotalBidIncrease", MAX_TOTAL_BID_INCREASE,
            "defaultMinImpressions", DEFAULT_MIN_IMPRESSIONS,
            "defaultMinClicks", DEFAULT_MIN_CLICKS),
        "上游 = 平台搜索词报告 / 关键词数据同步（本模块自带**真实写入口**，同步器归 02 通道）；"
            + "下游 = 14.2.1.1 竞价（只提供读取形状与批量调价动作）、"
            + "4.5.1.2 排名（名次与词频口径直接引用，不另起一套）。"
            + "同卷 14.1.2.2 广告类型差异的归一类型沿用它：归一类型与原始类型标记并存，"
            + "UNMAPPED 是显式合法值不是兜底。");
  }

  // ==================================================================
  // 筛选选项
  // ==================================================================

  /** 筛选面板选项：平台 / 店铺 / 外部活动 / 归一类型 / 币种（都取本租户真实有的）。 */
  public AdKeywordFilterOptions filterOptions() {
    UUID tenant = currentTenant();
    List<AdKeywordFilterOption> platforms =
        sql.fetch(
                "select p.code as code, p.name as name, '' as platform_code"
                    + " from platform p"
                    + " where exists (select 1 from shop s where s.tenant_id=? and s.platform_id=p.id)"
                    + " order by p.position, p.code",
                tenant)
            .stream()
            .map(r -> new AdKeywordFilterOption(r.get("code", String.class),
                r.get("name", String.class), ""))
            .toList();
    List<AdKeywordFilterOption> shops =
        sql.fetch(
                "select s.code as code, s.name as name, p.code as platform_code"
                    + " from shop s join platform p on p.id=s.platform_id"
                    + " where s.tenant_id=? order by p.position, s.code",
                tenant)
            .stream()
            .map(r -> new AdKeywordFilterOption(r.get("code", String.class),
                r.get("name", String.class), r.get("platform_code", String.class)))
            .toList();
    // 🔴 外部活动 id 来自**本租户真实的关键词**，不是 14.1.1.1 的活动表（它未交付）
    List<AdKeywordFilterOption> campaigns =
        sql.fetch(
                "select distinct k.external_campaign_id as code, k.external_campaign_id as name,"
                    + " f.code as platform_code"
                    + " from ad_keyword k join platform f on f.id=k.platform_id"
                    + " where k.tenant_id=? order by f.position, k.external_campaign_id",
                tenant)
            .stream()
            .map(r -> new AdKeywordFilterOption(r.get("code", String.class),
                r.get("name", String.class), r.get("platform_code", String.class)))
            .toList();
    List<AdKeywordFilterOption> adTypes =
        sql.fetch(
                "select distinct k.internal_ad_type as code, k.internal_ad_type as name,"
                    + " f.code as platform_code"
                    + " from ad_keyword k join platform f on f.id=k.platform_id"
                    + " where k.tenant_id=? order by k.internal_ad_type",
                tenant)
            .stream()
            .map(r -> new AdKeywordFilterOption(r.get("code", String.class),
                r.get("name", String.class), r.get("platform_code", String.class)))
            .toList();
    List<AdKeywordFilterOption> currencies =
        sql.fetch(
                "select distinct k.metric_currency as code, k.metric_currency as name, '' as platform_code"
                    + " from ad_keyword k where k.tenant_id=? and k.metric_currency <> ''"
                    + " order by k.metric_currency",
                tenant)
            .stream()
            .map(r -> new AdKeywordFilterOption(r.get("code", String.class),
                r.get("name", String.class), ""))
            .toList();
    return new AdKeywordFilterOptions(platforms, shops, campaigns, adTypes, currencies);
  }

  // ==================================================================
  // 关键词列表
  // ==================================================================

  /** 关键词列表（真分页 + 筛选）。 */
  public AdKeywordPage keywords(
      String platformCode,
      String shopCode,
      String externalCampaignId,
      String internalAdType,
      String platformAdType,
      String status,
      String keyword,
      String negated,
      String listingKeywordId,
      String from,
      String to,
      String sort,
      int page,
      int size) {
    UUID tenant = currentTenant();
    DayRange range = dayRange(from, to);
    Filters filters = new Filters("k");
    filters.binds.add(tenant);
    if (!text(platformCode).isEmpty()) {
      filters.add(" and f.code = ?", platformCode.trim());
    }
    if (!text(shopCode).isEmpty()) {
      filters.add(" and sh.code = ?", shopCode.trim());
    }
    if (!text(externalCampaignId).isEmpty()) {
      filters.add(" and k.external_campaign_id = ?", externalCampaignId.trim());
    }
    if (!text(internalAdType).isEmpty()) {
      filters.add(" and k.internal_ad_type = ?", internalAdType.trim());
    }
    if (!text(platformAdType).isEmpty()) {
      filters.add(" and k.platform_ad_type = ?", platformAdType.trim());
    }
    String statusValue = text(status).toUpperCase(Locale.ROOT);
    if (!statusValue.isEmpty() && !"ALL".equals(statusValue)) {
      if (!KEYWORD_STATUSES.contains(statusValue)) {
        throw unprocessable("状态只能是 ACTIVE / PAUSED / ARCHIVED / ALL（收到「" + statusValue + "」）");
      }
      filters.add(" and k.status = ?", statusValue);
    }
    if (!text(keyword).isEmpty()) {
      filters.add(" and lower(k.keyword) like ?", "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%");
    }
    if (!text(listingKeywordId).isEmpty()) {
      filters.add(" and k.listing_keyword_id = ?", parseId(listingKeywordId));
    }
    String negatedValue = text(negated).toLowerCase(Locale.ROOT);
    if (!negatedValue.isEmpty()) {
      if ("true".equals(negatedValue)) {
        filters.add(
            " and exists (select 1 from ad_negative_keyword n3 where n3.tenant_id=k.tenant_id"
                + " and n3.keyword_id=k.id)");
      } else if ("false".equals(negatedValue)) {
        filters.add(
            " and not exists (select 1 from ad_negative_keyword n3 where n3.tenant_id=k.tenant_id"
                + " and n3.keyword_id=k.id)");
      } else {
        throw unprocessable("negated 只能是 true / false（收到「" + negatedValue + "」）");
      }
    }

    long total =
        count(
            "select count(*) from ad_keyword k"
                + " join platform f on f.id = k.platform_id"
                + " join shop sh on sh.tenant_id = k.tenant_id and sh.id = k.shop_id"
                + filters.where,
            filters.binds.toArray());

    String orderBy = orderByClause(sort);
    // 🔴 bind 顺序必须与 SQL 文本顺序一致：KEYWORD_SELECT 里的 lateral（区间两个占位符）
    //    **出现在 WHERE 之前** ⇒ 先 from/to，后 filters。
    List<Object> binds = new ArrayList<>();
    binds.add(range.from());
    binds.add(range.to());
    binds.addAll(filters.binds);
    binds.add(clampSize(size));
    binds.add(page * clampSize(size));

    List<AdKeywordRow> items =
        sql.fetch(
                KEYWORD_SELECT
                    + filters.where
                    + " order by "
                    + orderBy
                    + " limit ? offset ?",
                binds.toArray())
            .stream()
            .map(AdKeywordService::keywordRow)
            .toList();

    OffsetDateTime asOf = dataAsOf(tenant);
    return new AdKeywordPage(
        items, page, clampSize(size), total, asOf, syncStatus(asOf), syncNote(asOf));
  }

  /** 🔴 排序：ORDER BY 写**表达式**而不是输出列别名 —— 别名与 lateral 的列同名会触发 PG 的
   * 「ORDER BY \"spend\" is ambiguous」。 */
  private static String orderByClause(String sort) {
    String value = text(sort).toUpperCase(Locale.ROOT);
    return switch (value) {
      case "IMPRESSIONS_DESC" -> "coalesce(m.impressions,0) desc, k.keyword asc, k.id asc";
      case "CLICKS_DESC" -> "coalesce(m.clicks,0) desc, k.keyword asc, k.id asc";
      case "CONVERSIONS_DESC" -> "coalesce(m.conversions,0) desc, k.keyword asc, k.id asc";
      case "KEYWORD_ASC" -> "k.keyword asc, k.id asc";
      case "ACOS_DESC" ->
          "case when coalesce(m.sales,0) > 0 then coalesce(m.spend,0)/m.sales end desc nulls last,"
              + " k.keyword asc, k.id asc";
      default -> "coalesce(m.spend,0) desc, k.keyword asc, k.id asc";
    };
  }

  private static AdKeywordRow keywordRow(Record r) {
    return new AdKeywordRow(
        r.get("keyword_id", UUID.class),
        r.get("platform_id", UUID.class),
        stringOf(r, "platform_code"),
        r.get("shop_id", UUID.class),
        stringOf(r, "shop_code"),
        stringOf(r, "external_campaign_id"),
        stringOf(r, "external_keyword_id"),
        stringOf(r, "keyword"),
        stringOf(r, "internal_ad_type"),
        stringOf(r, "platform_ad_type"),
        decimalOf(r, "bid"),
        stringOf(r, "bid_currency"),
        stringOf(r, "status"),
        r.get("listing_keyword_id", UUID.class),
        stringOf(r, "note"),
        longOf(r, "impressions"),
        longOf(r, "clicks"),
        intOf(r, "conversions"),
        decimalOf(r, "spend"),
        decimalOf(r, "sales"),
        stringOf(r, "currency"),
        decimalOf(r, "acos"),
        decimalOf(r, "cpc"),
        decimalOf(r, "ctr"),
        decimalOf(r, "cvr"),
        intOf(r, "active_days"),
        timeOf(r, "metric_synced_at"),
        Boolean.TRUE.equals(r.get("has_metrics", Boolean.class)),
        r.get("natural_rank", Integer.class),
        r.get("ad_rank", Integer.class),
        timeOf(r, "ranking_crawled_at"),
        r.get("ranking_accuracy_window_minutes", Integer.class),
        longOf(r, "frequency"),
        longOf(r, "frequency_listing_count"),
        Boolean.TRUE.equals(r.get("negated", Boolean.class)),
        r.get("latest_negative_id", UUID.class),
        timeOf(r, "created_at"),
        timeOf(r, "updated_at"));
  }

  // ==================================================================
  // 关键词明细
  // ==================================================================

  /** 单个关键词的明细（含搜索词浪费摘要与否定历史）。 */
  public AdKeywordDetail detail(String keywordId) {
    UUID tenant = currentTenant();
    UUID id = parseId(keywordId);
    DayRange range = dayRange(null, null);
    List<Object> binds = new ArrayList<>();
    binds.add(range.from());
    binds.add(range.to());
    binds.add(tenant);
    binds.add(id);
    Record row = sql.fetchOne(KEYWORD_SELECT + " where k.tenant_id = ? and k.id = ?", binds.toArray());
    if (row == null) {
      throw notFound("关键词");
    }
    AdKeywordRow base = keywordRow(row);

    long searchTermCount =
        count(
            "select count(*) from ad_search_term where tenant_id=? and keyword_id=?",
            tenant, id);
    long wasted =
        count(
            "select count(*) from (select keyword_id, search_term from ad_search_term"
                + " where tenant_id=? and keyword_id=? and bucket_date >= ? and bucket_date <= ?"
                + " group by keyword_id, search_term"
                + " having sum(impressions) >= ? and sum(conversions) = 0) x",
            tenant, id, range.from(), range.to(), DEFAULT_MIN_IMPRESSIONS);
    long negatives =
        count("select count(*) from ad_negative_keyword where tenant_id=? and keyword_id=?", tenant, id);

    boolean hasMetrics = base.hasMetrics();
    boolean hasRangeMetrics = base.activeDays() > 0;
    String emptyReason =
        hasMetrics
            ? (hasRangeMetrics ? "" : "已同步过指标，但所查区间内没有数据（区间 " + range.from() + " ~ " + range.to() + "）")
            : "该关键词还没有同步过任何指标数据（不是「没花钱」，是「没数据」）";

    OffsetDateTime asOf = dataAsOf(tenant);
    return new AdKeywordDetail(
        base.keywordId(),
        base.platformId(),
        base.platformCode(),
        base.shopId(),
        base.shopCode(),
        base.externalCampaignId(),
        base.externalKeywordId(),
        base.keyword(),
        base.internalAdType(),
        base.platformAdType(),
        base.bid(),
        base.bidCurrency(),
        base.status(),
        base.listingKeywordId(),
        base.note(),
        base.impressions(),
        base.clicks(),
        base.conversions(),
        base.spend(),
        base.sales(),
        base.currency(),
        base.acos(),
        base.cpc(),
        base.ctr(),
        base.cvr(),
        base.activeDays(),
        base.metricAsOf(),
        hasMetrics,
        base.naturalRank(),
        base.adRank(),
        base.rankingCrawledAt(),
        base.rankingAccuracyWindowMinutes(),
        base.frequency(),
        base.frequencyListingCount(),
        searchTermCount,
        wasted,
        negatives,
        hasRangeMetrics,
        emptyReason,
        asOf,
        syncStatus(asOf),
        syncNote(asOf));
  }

  // ==================================================================
  // 关键词登记与维护
  // ==================================================================

  /**
   * 新建 / 改一个投放关键词。
   *
   * <p>🔴 归一类型由 <b>14.1.2.2 的两层解析</b>带出（租户覆盖 &gt; 平台默认 &gt; 未映射），
   * 声明值与解析结果不一致直接拒 —— 归一口径的真相在 2.1.2.1，不在请求里。
   *
   * <p>🔴 竞价与竞价币种成对出现：给了竞价没给币种 ⇒ 400（批量调价要按币种计价）。
   */
  public AdKeywordRow saveKeyword(UUID keywordId, AdKeywordInput input) {
    UUID tenant = currentTenant();
    String keyword = text(input.keyword());
    String platformAdType = text(input.platformAdType());
    if (keyword.isEmpty() || platformAdType.isEmpty()) {
      throw badRequest("关键词原文与原始平台类型标记都不能为空");
    }
    String externalCampaignId = text(input.externalCampaignId());
    String externalKeywordId = text(input.externalKeywordId());
    if (externalCampaignId.isEmpty() || externalKeywordId.isEmpty()) {
      throw badRequest("外部活动 id 与外部关键词 id 都不能为空");
    }

    // 🔴 店铺必须属于本租户且平台一致（否则关键词会挂到别的平台的店铺上）
    Record shop =
        sql.fetchOne(
            "select s.id, s.platform_id, p.code as platform_code from shop s"
                + " join platform p on p.id=s.platform_id"
                + " where s.tenant_id=? and s.id=?",
            tenant, input.shopId());
    if (shop == null) {
      throw notFound("店铺");
    }
    if (!shop.get("platform_id", UUID.class).equals(input.platformId())) {
      throw badRequest(
          "店铺「"
              + shop.get("platform_code", String.class)
              + "」不属于所选平台 —— 关键词的平台与店铺的平台必须一致");
    }

    // 🔴 归一类型：走 14.1.2.2 的解析（UNMAPPED 是显式合法值，不是兜底）
    var resolution = adTypes.resolve(input.platformId(), platformAdType);
    String declared = text(input.internalAdType()).toUpperCase(Locale.ROOT);
    if (!declared.isEmpty() && !declared.equals(resolution.internalAdType())) {
      throw badRequest(
          "声明的归一类型（" + declared + "）与 14.1.2.2 解析结果（"
              + resolution.internalAdType() + "，来源 " + resolution.mappingSource() + "）不一致");
    }
    String internalAdType = resolution.internalAdType();

    // 🔴 竞价与币种成对
    BigDecimal bid = input.bid() == null ? BigDecimal.ZERO : money(input.bid());
    String bidCurrency = text(input.bidCurrency()).toUpperCase(Locale.ROOT);
    if (input.bid() != null && bidCurrency.isEmpty()) {
      throw badRequest("给了竞价就必须给竞价币种 —— 批量调价按币种计价，币种缺失会让护栏失去意义");
    }
    if (bid.compareTo(MAX_BID_ABSOLUTE) > 0) {
      throw unprocessable(
          "竞价 " + bid + " 超过单条绝对上限 " + MAX_BID_ABSOLUTE + "（护栏，不可绕过）");
    }

    String status = text(input.status()).toUpperCase(Locale.ROOT);
    if (status.isEmpty()) {
      status = "ACTIVE";
    }
    if (!KEYWORD_STATUSES.contains(status)) {
      throw unprocessable("状态只能是 ACTIVE / PAUSED / ARCHIVED（收到「" + status + "」）");
    }

    // 🔴 关联到 4.5.1.2 的关键词集合：必须存在且属于本租户
    UUID listingKeywordId = input.listingKeywordId();
    if (listingKeywordId != null) {
      long found =
          count(
              "select count(*) from listing_keyword where tenant_id=? and id=?",
              tenant, listingKeywordId);
      if (found == 0L) {
        throw notFound("4.5.1.2 关键词");
      }
    }

    UUID id = keywordId == null ? Ids.next() : keywordId;
    if (keywordId != null) {
      long found = count("select count(*) from ad_keyword where tenant_id=? and id=?", tenant, id);
      if (found == 0L) {
        throw notFound("关键词");
      }
      // 🔴 改外部键（活动 id / 外部关键词 id）时可能撞上另一条 ⇒ 409，不让 DB 抛 500
      long clash =
          count(
              "select count(*) from ad_keyword where tenant_id=? and shop_id=? and id<>?"
                  + " and external_campaign_id=? and external_keyword_id=?",
              tenant,
              input.shopId(),
              id,
              externalCampaignId,
              externalKeywordId);
      if (clash > 0L) {
        throw conflict("同一店铺下已存在外部活动 " + externalCampaignId + " 下的外部关键词 "
            + externalKeywordId + "，改这两个字段会撞车");
      }
      sql.execute(
          """
          update ad_keyword set
            platform_id=?, shop_id=?, external_campaign_id=?, external_keyword_id=?, keyword=?,
            internal_ad_type=?, platform_ad_type=?, bid=?, bid_currency=?, status=?,
            listing_keyword_id=?, note=?, updated_at=now()
          where tenant_id=? and id=?
          """,
          input.platformId(),
          input.shopId(),
          externalCampaignId,
          externalKeywordId,
          keyword,
          internalAdType,
          platformAdType,
          bid,
          bidCurrency.isEmpty() ? null : bidCurrency,
          status,
          listingKeywordId,
          note(input.note()),
          tenant,
          id);
      return fetchKeyword(tenant, id);
    }
    // 🔴 新建：同一（店铺, 外部活动, 外部关键词）只留一条 —— 重投是「改」，不是「多一条」
    sql.execute(
        """
        insert into ad_keyword
          (id,tenant_id,platform_id,shop_id,external_campaign_id,external_keyword_id,keyword,
           internal_ad_type,platform_ad_type,bid,bid_currency,metric_currency,status,
           listing_keyword_id,note,created_at,updated_at)
        values (?,?,?,?,?,?,?,?,?,?,?,'',?,?,?,now(),now())
        on conflict (tenant_id,shop_id,external_campaign_id,external_keyword_id) do update set
          keyword=excluded.keyword,
          internal_ad_type=excluded.internal_ad_type,
          platform_ad_type=excluded.platform_ad_type,
          bid=excluded.bid,
          bid_currency=case when excluded.bid_currency='' then ad_keyword.bid_currency
                            else excluded.bid_currency end,
          status=excluded.status,
          listing_keyword_id=excluded.listing_keyword_id,
          note=excluded.note,
          updated_at=now()
        """,
        id,
        tenant,
        input.platformId(),
        input.shopId(),
        externalCampaignId,
        externalKeywordId,
        keyword,
        internalAdType,
        platformAdType,
        bid,
        bidCurrency,
        status,
        listingKeywordId,
        note(input.note()));

    UUID stored =
        one(
            UUID.class,
            "select id from ad_keyword where tenant_id=? and shop_id=? and external_campaign_id=?"
                + " and external_keyword_id=?",
            tenant,
            input.shopId(),
            externalCampaignId,
            externalKeywordId);
    return fetchKeyword(tenant, stored == null ? id : stored);
  }

  /**
   * 🔴 归档一个关键词（**软删除**）。
   *
   * <p>表现、搜索词、否定历史**必须留着** —— 关键词停了不等于当初的花费与浪费不算数。
   */
  public AdKeywordRow archiveKeyword(String keywordId) {
    UUID tenant = currentTenant();
    UUID id = parseId(keywordId);
    long found = count("select count(*) from ad_keyword where tenant_id=? and id=?", tenant, id);
    if (found == 0L) {
      throw notFound("关键词");
    }
    sql.execute(
        "update ad_keyword set status='ARCHIVED', updated_at=now() where tenant_id=? and id=?",
        tenant, id);
    return fetchKeyword(tenant, id);
  }

  private AdKeywordRow fetchKeyword(UUID tenant, UUID id) {
    List<Object> binds = new ArrayList<>();
    binds.add(LocalDate.now(ZoneOffset.UTC).minusDays(29));
    binds.add(LocalDate.now(ZoneOffset.UTC));
    binds.add(tenant);
    binds.add(id);
    Record row = sql.fetchOne(KEYWORD_SELECT + " where k.tenant_id = ? and k.id = ?", binds.toArray());
    if (row == null) {
      throw notFound("关键词");
    }
    return keywordRow(row);
  }

  // ==================================================================
  // 🔴 关键词表现同步（真实写入口）
  // ==================================================================

  /**
   * 🔴 接收一批关键词表现。
   *
   * <p>🔴 **批次级幂等**：{@code idempotencyKey} 重复投递 ⇒ 整批不再落库（指标落两遍会让
   * ACOS 与 CPC 变成两倍），返回既有批次并把 {@code replayed} 置 true。
   *
   * <p>🔴 逐条跳过原因**不静默丢弃**：关键词不存在、币种与关键词既有币种不一致的都会被跳过
   * 并写明原因。
   */
  public AdKeywordMetricBatchResult ingestMetrics(AdKeywordMetricBatchRequest request) {
    UUID tenant = currentTenant();
    String key = text(request.idempotencyKey());
    String source = sourceOf(request.source());
    List<String> skipped = new ArrayList<>();
    int applied = 0;

    // 🔴 幂等：这一批落过没有
    long already =
        count(
            "select count(*) from ad_keyword_metric where tenant_id=? and batch_key=?",
            tenant, key);
    if (already > 0L) {
      // 🔴 重投：**本次一行都没落**（appliedCount=0）。库里已有多少行写在说明里。
      return new AdKeywordMetricBatchResult(
          true,
          key,
          request.metrics().size(),
          0,
          request.metrics().size(),
          List.of(
              "该批次此前已落库（幂等：本次不重放）；库里已有 "
                  + asInt(already)
                  + " 行属于这一批"),
          metricBatchAsOf(tenant, key));
    }

    for (AdKeywordMetricInput input : request.metrics()) {
      Record keyword =
          sql.fetchOne(
              "select id, metric_currency, bid_currency from ad_keyword where tenant_id=? and id=?",
              tenant, input.keywordId());
      if (keyword == null) {
        skipped.add(
            "关键词 " + input.keywordId() + "（" + input.bucketDate() + "）不存在或不属于本租户");
        continue;
      }
      String currency = text(input.currency()).toUpperCase(Locale.ROOT);
      String keywordCurrency = stringOf(keyword, "metric_currency");
      if (!keywordCurrency.isEmpty() && !keywordCurrency.equals(currency)) {
        skipped.add(
            "关键词 "
                + input.keywordId()
                + "（"
                + input.bucketDate()
                + "）的币种 "
                + currency
                + " 与该关键词既有币种 "
                + keywordCurrency
                + " 不一致 —— 跨币种相加再相除算出来的 ACOS 是假的");
        continue;
      }
      sql.execute(
          """
          insert into ad_keyword_metric
            (id,tenant_id,keyword_id,bucket_date,impressions,clicks,conversions,spend,sales,
             currency,synced_at,source,source_ref,batch_key,note,created_at,updated_at)
          values (?,?,?,?,?,?,?,?,?,?,?::timestamptz,?,?,?,?,now(),now())
          on conflict (tenant_id,keyword_id,bucket_date) do update set
            impressions=excluded.impressions,
            clicks=excluded.clicks,
            conversions=excluded.conversions,
            spend=excluded.spend,
            sales=excluded.sales,
            currency=excluded.currency,
            synced_at=excluded.synced_at,
            source=excluded.source,
            source_ref=excluded.source_ref,
            batch_key=excluded.batch_key,
            note=excluded.note,
            updated_at=now()
          """,
          Ids.next(),
          tenant,
          input.keywordId(),
          input.bucketDate(),
          input.impressions() == null ? 0L : input.impressions(),
          input.clicks() == null ? 0L : input.clicks(),
          input.conversions() == null ? 0 : input.conversions(),
          money(input.spend()),
          money(input.sales()),
          currency,
          bind(input.syncedAt()),
          source,
          text(request.sourceRef()),
          key,
          note(request.note()));
      // 🔴 首个指标行把该关键词的指标币种定下来（之后不一致的行被 DB 触发器也拒一层）
      if (keywordCurrency.isEmpty()) {
        sql.execute(
            "update ad_keyword set metric_currency=? where tenant_id=? and id=? and metric_currency=''",
            currency, tenant, input.keywordId());
      }
      applied++;
    }
    return new AdKeywordMetricBatchResult(
        false,
        key,
        request.metrics().size(),
        applied,
        skipped.size(),
        skipped,
        metricBatchAsOf(tenant, key));
  }

  private OffsetDateTime metricBatchAsOf(UUID tenant, String key) {
    Record row =
        sql.fetchOne(
            "select max(synced_at) as as_of from ad_keyword_metric where tenant_id=? and batch_key=?",
            tenant, key);
    return row == null ? null : timeOf(row, "as_of");
  }

  // ==================================================================
  // 🔴 搜索词报告同步（真实写入口）
  // ==================================================================

  /**
   * 🔴 接收一批搜索词报告。
   *
   * <p>🔴 与指标同一套口径：批次幂等 + 逐条跳过原因 + 数据截至时间。
   */
  public AdSearchTermBatchResult ingestSearchTerms(AdSearchTermBatchRequest request) {
    UUID tenant = currentTenant();
    String key = text(request.idempotencyKey());
    String source = sourceOf(request.source());
    List<String> skipped = new ArrayList<>();
    int applied = 0;

    long already =
        count(
            "select count(*) from ad_search_term where tenant_id=? and batch_key=?", tenant, key);
    if (already > 0L) {
      return new AdSearchTermBatchResult(
          true,
          key,
          request.terms().size(),
          0,
          request.terms().size(),
          List.of(
              "该批次此前已落库（幂等：本次不重放）；库里已有 "
                  + asInt(already)
                  + " 行属于这一批"),
          searchTermBatchAsOf(tenant, key));
    }

    for (AdSearchTermInput input : request.terms()) {
      Record keyword =
          sql.fetchOne(
              "select id, external_campaign_id, metric_currency from ad_keyword where tenant_id=? and id=?",
              tenant, input.keywordId());
      if (keyword == null) {
        skipped.add(
            "关键词 " + input.keywordId() + "（" + input.bucketDate() + "）不存在或不属于本租户");
        continue;
      }
      String currency = text(input.currency()).toUpperCase(Locale.ROOT);
      String keywordCurrency = stringOf(keyword, "metric_currency");
      if (!keywordCurrency.isEmpty() && !keywordCurrency.equals(currency)) {
        skipped.add(
            "关键词 "
                + input.keywordId()
                + "（"
                + input.bucketDate()
                + "）的币种 "
                + currency
                + " 与该关键词既有币种 "
                + keywordCurrency
                + " 不一致");
        continue;
      }
      sql.execute(
          """
          insert into ad_search_term
            (id,tenant_id,keyword_id,bucket_date,search_term,external_campaign_id,
             impressions,clicks,conversions,spend,sales,currency,synced_at,source,source_ref,
             batch_key,note,created_at,updated_at)
          values (?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?,?,?,?,now(),now())
          on conflict (tenant_id,keyword_id,bucket_date,search_term) do update set
            external_campaign_id=excluded.external_campaign_id,
            impressions=excluded.impressions,
            clicks=excluded.clicks,
            conversions=excluded.conversions,
            spend=excluded.spend,
            sales=excluded.sales,
            currency=excluded.currency,
            synced_at=excluded.synced_at,
            source=excluded.source,
            source_ref=excluded.source_ref,
            batch_key=excluded.batch_key,
            note=excluded.note,
            updated_at=now()
          """,
          Ids.next(),
          tenant,
          input.keywordId(),
          input.bucketDate(),
          text(input.searchTerm()),
          text(input.externalCampaignId()),
          input.impressions() == null ? 0L : input.impressions(),
          input.clicks() == null ? 0L : input.clicks(),
          input.conversions() == null ? 0 : input.conversions(),
          money(input.spend()),
          money(input.sales()),
          currency,
          bind(input.syncedAt()),
          source,
          text(request.sourceRef()),
          key,
          note(request.note()));
      if (keywordCurrency.isEmpty()) {
        sql.execute(
            "update ad_keyword set metric_currency=? where tenant_id=? and id=? and metric_currency=''",
            currency, tenant, input.keywordId());
      }
      applied++;
    }
    return new AdSearchTermBatchResult(
        false,
        key,
        request.terms().size(),
        applied,
        skipped.size(),
        skipped,
        searchTermBatchAsOf(tenant, key));
  }

  private OffsetDateTime searchTermBatchAsOf(UUID tenant, String key) {
    Record row =
        sql.fetchOne(
            "select max(synced_at) as as_of from ad_search_term where tenant_id=? and batch_key=?",
            tenant, key);
    return row == null ? null : timeOf(row, "as_of");
  }

  private static String sourceOf(String raw) {
    String value = text(raw).toUpperCase(Locale.ROOT);
    if (value.isEmpty()) {
      return "PLATFORM";
    }
    if (!METRIC_SOURCES.contains(value)) {
      throw unprocessable("来源只能是 PLATFORM / API / IMPORT（收到「" + value + "」）");
    }
    return value;
  }

  // ==================================================================
  // 搜索词报告
  // ==================================================================

  /** 搜索词报告（真分页 + 筛选）；「高曝光零转化」可一眼识别。 */
  public AdSearchTermPage searchTerms(
      String platformCode,
      String shopCode,
      String externalCampaignId,
      String searchTerm,
      String wastedOnly,
      String from,
      String to,
      int page,
      int size) {
    UUID tenant = currentTenant();
    DayRange range = dayRange(from, to);
    Filters filters = new Filters("k");
    filters.binds.add(tenant);
    if (!text(platformCode).isEmpty()) {
      filters.add(" and f.code = ?", platformCode.trim());
    }
    if (!text(shopCode).isEmpty()) {
      filters.add(" and sh.code = ?", shopCode.trim());
    }
    if (!text(externalCampaignId).isEmpty()) {
      filters.add(" and st.external_campaign_id = ?", externalCampaignId.trim());
    }
    if (!text(searchTerm).isEmpty()) {
      filters.add(" and lower(st.search_term) like ?", "%" + searchTerm.trim().toLowerCase(Locale.ROOT) + "%");
    }
    boolean onlyWasted = "true".equalsIgnoreCase(text(wastedOnly));

    // 🔴 「高曝光零转化」的判定只有这一处（前端不自己判）
    String wasteHaving =
        " having (sum(st.impressions) >= " + DEFAULT_MIN_IMPRESSIONS + " and sum(st.conversions) = 0)";
    String having = onlyWasted ? wasteHaving : "";

    String fromClause =
        """
        from ad_search_term st
        join ad_keyword k on k.tenant_id = st.tenant_id and k.id = st.keyword_id
        join platform f on f.id = k.platform_id
        join shop sh on sh.tenant_id = k.tenant_id and sh.id = k.shop_id
        """;
    String where =
        " where st.tenant_id = ? and st.bucket_date >= ? and st.bucket_date <= ?" + filters.extra();
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    binds.add(range.from());
    binds.add(range.to());
    binds.addAll(filters.binds.subList(1, filters.binds.size()));

    String groupBy =
        " group by st.keyword_id, st.search_term, st.external_campaign_id, k.keyword,"
            + " f.code, sh.code, k.shop_id";

    long total =
        count(
            "select count(*) from (select 1 " + fromClause + where + groupBy + having + ") x",
            binds.toArray());

    // 🔴 浪费条数**不受 wasted 筛选影响**（它是这份报表的头条数字）
    long wastedCount =
        count(
            "select count(*) from (select 1 " + fromClause + where + groupBy + wasteHaving + ") x",
            binds.toArray());

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(clampSize(size));
    pageBinds.add(page * clampSize(size));

    List<AdSearchTermRow> items =
        sql.fetch(
                "select st.keyword_id, st.search_term, st.external_campaign_id, k.keyword,"
                    + " f.code as platform_code, sh.code as shop_code,"
                    + " sum(st.impressions) as impressions, sum(st.clicks) as clicks,"
                    + " sum(st.conversions) as conversions, sum(st.spend) as spend,"
                    + " sum(st.sales) as sales, max(st.currency) as currency,"
                    + " max(st.synced_at) as synced_at, max(st.source) as source "
                    + fromClause
                    + where
                    + groupBy
                    + having
                    + " order by sum(st.spend) desc, sum(st.impressions) desc, st.search_term asc"
                    + " limit ? offset ?",
                pageBinds.toArray())
            .stream()
            .map(r -> searchTermRow(r))
            .toList();

    OffsetDateTime asOf = dataAsOf(tenant);
    return new AdSearchTermPage(
        items, page, clampSize(size), total, wastedCount, asOf, syncStatus(asOf), syncNote(asOf));
  }

  private AdSearchTermRow searchTermRow(Record r) {
    long impressions = longOf(r, "impressions");
    long clicks = longOf(r, "clicks");
    int conversions = intOf(r, "conversions");
    BigDecimal spend = decimalOf(r, "spend");
    BigDecimal sales = decimalOf(r, "sales");
    BigDecimal acos = sales != null && sales.signum() > 0 ? spend.divide(sales, 4, RoundingMode.HALF_UP) : null;
    BigDecimal cpc = clicks > 0 ? spend.divide(BigDecimal.valueOf(clicks), 4, RoundingMode.HALF_UP) : null;
    BigDecimal ctr =
        impressions > 0
            ? BigDecimal.valueOf(clicks).divide(BigDecimal.valueOf(impressions), 6, RoundingMode.HALF_UP)
            : null;
    BigDecimal cvr =
        clicks > 0
            ? BigDecimal.valueOf(conversions).divide(BigDecimal.valueOf(clicks), 6, RoundingMode.HALF_UP)
            : null;
    boolean wasted = impressions >= DEFAULT_MIN_IMPRESSIONS && conversions == 0;
    return new AdSearchTermRow(
        null,
        r.get("keyword_id", UUID.class),
        stringOf(r, "search_term"),
        stringOf(r, "keyword"),
        stringOf(r, "external_campaign_id"),
        stringOf(r, "platform_code"),
        stringOf(r, "shop_code"),
        impressions,
        clicks,
        conversions,
        spend,
        sales,
        stringOf(r, "currency"),
        acos,
        cpc,
        ctr,
        cvr,
        wasted,
        false,
        timeOf(r, "synced_at"),
        stringOf(r, "source"));
  }

  // ==================================================================
  // 🔴 否定词建议
  // ==================================================================

  /**
   * 🔴 否定词建议 —— **带依据数值**。
   *
   * <p>🔴 三条规则都在**服务端**判（前端不自己判 —— 两处各判一次会出现两个版本）：
   * ① 高曝光零转化：曝光 ≥ minImpressions 且转化 = 0；
   * ② 花了钱没转化：点击 ≥ minClicks 且转化 = 0 且花费 &gt; 0；
   * ③ ACOS 超标：给了 targetAcos 时 ACOS &gt; targetAcos 且点击 ≥ minClicks。
   */
  public AdNegativeSuggestionPage negativeSuggestions(
      String platformCode,
      String shopCode,
      String externalCampaignId,
      String from,
      String to,
      Long minImpressions,
      Long minClicks,
      String targetAcos,
      int page,
      int size) {
    UUID tenant = currentTenant();
    DayRange range = dayRange(from, to);
    long impressionsFloor = minImpressions == null ? DEFAULT_MIN_IMPRESSIONS : Math.max(0L, minImpressions);
    long clicksFloor = minClicks == null ? DEFAULT_MIN_CLICKS : Math.max(0L, minClicks);
    BigDecimal acosTarget = blankToNull(targetAcos) == null ? null : new BigDecimal(text(targetAcos));

    Filters filters = new Filters("k");
    filters.binds.add(tenant);
    if (!text(platformCode).isEmpty()) {
      filters.add(" and f.code = ?", platformCode.trim());
    }
    if (!text(shopCode).isEmpty()) {
      filters.add(" and sh.code = ?", shopCode.trim());
    }
    if (!text(externalCampaignId).isEmpty()) {
      filters.add(" and st.external_campaign_id = ?", externalCampaignId.trim());
    }

    String having =
        " having ((sum(st.impressions) >= ? and sum(st.conversions) = 0)"
            + " or (sum(st.clicks) >= ? and sum(st.conversions) = 0 and sum(st.spend) > 0)";
    List<Object> havingBinds = new ArrayList<>();
    havingBinds.add(impressionsFloor);
    havingBinds.add(clicksFloor);
    if (acosTarget != null) {
      having +=
          " or (sum(st.sales) > 0 and sum(st.spend)/sum(st.sales) > ? and sum(st.clicks) >= ?))";
      havingBinds.add(acosTarget);
      havingBinds.add(clicksFloor);
    } else {
      having += ")";
    }

    String fromClause =
        """
        from ad_search_term st
        join ad_keyword k on k.tenant_id = st.tenant_id and k.id = st.keyword_id
        join platform f on f.id = k.platform_id
        join shop sh on sh.tenant_id = k.tenant_id and sh.id = k.shop_id
        """;
    String where =
        " where st.tenant_id = ? and st.bucket_date >= ? and st.bucket_date <= ?" + filters.extra();
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    binds.add(range.from());
    binds.add(range.to());
    binds.addAll(filters.binds.subList(1, filters.binds.size()));

    String groupBy =
        " group by st.keyword_id, st.search_term, st.external_campaign_id, k.keyword,"
            + " f.code, sh.code, k.shop_id";

    long total =
        count(
            "select count(*) from (select 1 " + fromClause + where + groupBy + having + ") x",
            concatBinds(binds, havingBinds).toArray());

    List<Object> pageBinds = concatBinds(binds, havingBinds);
    pageBinds.add(clampSize(size));
    pageBinds.add(page * clampSize(size));

    List<AdNegativeSuggestion> items =
        sql.fetch(
                "select st.keyword_id, st.search_term, st.external_campaign_id, k.keyword,"
                    + " f.code as platform_code, sh.code as shop_code, k.shop_id,"
                    + " sum(st.impressions) as impressions, sum(st.clicks) as clicks,"
                    + " sum(st.conversions) as conversions, sum(st.spend) as spend,"
                    + " sum(st.sales) as sales, max(st.currency) as currency,"
                    + " max(st.synced_at) as synced_at "
                    + fromClause
                    + where
                    + groupBy
                    + having
                    + " order by sum(st.spend) desc, sum(st.impressions) desc, st.search_term asc"
                    + " limit ? offset ?",
                pageBinds.toArray())
            .stream()
            .map(r -> suggestion(r, impressionsFloor, clicksFloor, acosTarget))
            .toList();

    OffsetDateTime asOf = dataAsOf(tenant);
    return new AdNegativeSuggestionPage(
        items,
        page,
        clampSize(size),
        total,
        NEGATIVE_EVIDENCE_NOTE,
        range.from(),
        range.to(),
        asOf);
  }

  private static List<Object> concatBinds(List<Object> a, List<Object> b) {
    List<Object> out = new ArrayList<>(a);
    out.addAll(b);
    return out;
  }

  private AdNegativeSuggestion suggestion(
      Record r, long impressionsFloor, long clicksFloor, BigDecimal acosTarget) {
    long impressions = longOf(r, "impressions");
    long clicks = longOf(r, "clicks");
    int conversions = intOf(r, "conversions");
    BigDecimal spend = decimalOf(r, "spend");
    BigDecimal sales = decimalOf(r, "sales");
    BigDecimal acos = sales != null && sales.signum() > 0 ? spend.divide(sales, 4, RoundingMode.HALF_UP) : null;
    BigDecimal cpc = clicks > 0 ? spend.divide(BigDecimal.valueOf(clicks), 4, RoundingMode.HALF_UP) : null;
    BigDecimal ctr =
        impressions > 0
            ? BigDecimal.valueOf(clicks).divide(BigDecimal.valueOf(impressions), 6, RoundingMode.HALF_UP)
            : null;
    String currency = stringOf(r, "currency");

    String rule;
    String reason;
    if (impressions >= impressionsFloor && conversions == 0) {
      rule = "HIGH_IMPRESSION_ZERO_CONVERSION";
      reason =
          "高曝光零转化：曝光 "
              + impressions
              + " 次（阈值 "
              + impressionsFloor
              + "）、点击 "
              + clicks
              + " 次、转化 0 次、花费 "
              + spend
              + " "
              + currency
              + " —— 白烧的钱应尽快否定";
    } else if (clicks >= clicksFloor && conversions == 0 && spend.signum() > 0) {
      rule = "SPEND_NO_CONVERSION";
      reason =
          "花了钱没转化：点击 "
              + clicks
              + " 次（阈值 "
              + clicksFloor
              + "）、转化 0 次、花费 "
              + spend
              + " "
              + currency
              + "、销售额 "
              + sales
              + " "
              + currency;
    } else {
      rule = "HIGH_ACOS";
      reason =
          "ACOS 超标：ACOS "
              + (acos == null ? "不可比（销售额为 0）" : acos.toPlainString())
              + " > 目标 "
              + acosTarget.toPlainString()
              + "、点击 "
              + clicks
              + " 次、转化 "
              + conversions
              + " 次、花费 "
              + spend
              + " "
              + currency
              + "、销售额 "
              + sales
              + " "
              + currency;
    }
    return new AdNegativeSuggestion(
        r.get("keyword_id", UUID.class),
        stringOf(r, "search_term"),
        stringOf(r, "keyword"),
        stringOf(r, "external_campaign_id"),
        stringOf(r, "platform_code"),
        stringOf(r, "shop_code"),
        r.get("shop_id", UUID.class),
        rule,
        reason,
        impressions,
        clicks,
        conversions,
        spend,
        sales,
        currency,
        acos,
        cpc,
        ctr,
        null,
        null,
        timeOf(r, "synced_at"),
        "NEGATIVE_EXACT",
        false);
  }

  // ==================================================================
  // 否定记录
  // ==================================================================

  /** 否定记录列表（真分页 + 筛选）。 */
  public AdNegativePage negatives(
      String shopCode, String externalCampaignId, String negationText, String status, int page, int size) {
    UUID tenant = currentTenant();
    // 🔴 主表别名是 n（否定记录表），不是 k
    Filters filters = new Filters("n");
    filters.binds.add(tenant);
    if (!text(shopCode).isEmpty()) {
      filters.add(" and sh.code = ?", shopCode.trim());
    }
    if (!text(externalCampaignId).isEmpty()) {
      filters.add(" and n.external_campaign_id = ?", externalCampaignId.trim());
    }
    if (!text(negationText).isEmpty()) {
      filters.add(" and lower(n.negation_text) like ?", "%" + negationText.trim().toLowerCase(Locale.ROOT) + "%");
    }
    String statusValue = text(status).toUpperCase(Locale.ROOT);
    if (!statusValue.isEmpty() && !"ALL".equals(statusValue)) {
      if (!NEGATION_STATUSES.contains(statusValue)) {
        throw unprocessable("否定状态只能是 DECIDED / PUSHED / REJECTED / ALL");
      }
      filters.add(" and n.status = ?", statusValue);
    }
    long total =
        count(
            "select count(*) from ad_negative_keyword n"
                + " join shop sh on sh.tenant_id = n.tenant_id and sh.id = n.shop_id"
                + filters.where,
            filters.binds.toArray());

    List<Object> binds = new ArrayList<>(filters.binds);
    binds.add(clampSize(size));
    binds.add(page * clampSize(size));
    List<AdNegativeRow> items =
        sql.fetch(
                "select n.id as negative_id, n.keyword_id, n.negation_text, n.negation_type,"
                    + " n.status, n.external_campaign_id, n.shop_id, sh.code as shop_code,"
                    + " f.code as platform_code, n.reason, n.evidence, n.evidence_from, n.evidence_to,"
                    + " n.suggested_by, n.applied_at, n.platform_note, n.created_at"
                    + " from ad_negative_keyword n"
                    + " join shop sh on sh.tenant_id = n.tenant_id and sh.id = n.shop_id"
                    + " left join ad_keyword k on k.tenant_id = n.tenant_id and k.id = n.keyword_id"
                    + " left join platform f on f.id = k.platform_id"
                    + filters.where
                    + " order by n.created_at desc, n.id desc limit ? offset ?",
                binds.toArray())
            .stream()
            .map(r -> negativeRow(r))
            .toList();
    return new AdNegativePage(items, page, clampSize(size), total);
  }

  private AdNegativeRow negativeRow(Record r) {
    Map<String, Object> evidence = readJson(r.get("evidence"));
    Object claimedValue = evidence.remove("claimed");
    Map<String, Object> claimedEvidence =
        claimedValue instanceof Map<?, ?> m ? castMap(m) : Map.of();
    return new AdNegativeRow(
        r.get("negative_id", UUID.class),
        r.get("keyword_id", UUID.class),
        stringOf(r, "negation_text"),
        stringOf(r, "negation_type"),
        stringOf(r, "status"),
        stringOf(r, "external_campaign_id"),
        r.get("shop_id", UUID.class),
        stringOf(r, "shop_code"),
        stringOf(r, "platform_code"),
        stringOf(r, "reason"),
        evidence,
        claimedEvidence,
        r.get("evidence_from", LocalDate.class),
        r.get("evidence_to", LocalDate.class),
        stringOf(r, "suggested_by"),
        timeOf(r, "applied_at"),
        stringOf(r, "platform_note"),
        timeOf(r, "created_at"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castMap(Map<?, ?> raw) {
    Map<String, Object> out = new LinkedHashMap<>();
    raw.forEach((k, v) -> out.put(String.valueOf(k), v));
    return out;
  }

  /**
   * 🔴 读 jsonb 列（jOOQ 把它当字符串取回）。
   *
   * <p>🔴 **数字一律读成 {@link BigDecimal}，用流式解析器逐字读**（实测踩到的坑）：
   *
   * <ul>
   *   <li>{@code readTree(...)} 得到的 {@code DecimalNode} 会把尾随零**抹掉**
   *       （1000.00 → 1E+3、90.0000 → 9E+1）—— 护栏里的金额上限与实际总上调会掉精度；
   *   <li>{@code readerFor(Map...).with(USE_BIG_DECIMAL_FOR_FLOATS)} 在**共享的**
   *       ObjectMapper 上也不可靠：{@code UntypedObjectDeserializer} 变体由 provider
   *       按**创建时**的配置缓存，reader 级 {@code with()} 改不动它。
   * </ul>
   *
   * <p>所以这里走 {@link com.fasterxml.jackson.core.JsonParser} 流式解析：数字用
   * {@code new BigDecimal(p.getText())} 按**字面量**还原，精度一字不差
   * （AGENTS.md：禁止浮点记账）。
   */
  private Map<String, Object> readJson(Object raw) {
    if (raw == null) {
      return Map.of();
    }
    try (var parser = json.getFactory().createParser(String.valueOf(raw))) {
      if (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.START_OBJECT) {
        return Map.of();
      }
      return readJsonObject(parser);
    } catch (Exception e) {
      return Map.of();
    }
  }

  private static Map<String, Object> readJsonObject(com.fasterxml.jackson.core.JsonParser parser)
      throws java.io.IOException {
    Map<String, Object> out = new LinkedHashMap<>();
    while (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.END_OBJECT) {
      String field = parser.currentName();
      parser.nextToken();
      out.put(field, readJsonValue(parser));
    }
    return out;
  }

  private static Object readJsonValue(com.fasterxml.jackson.core.JsonParser parser)
      throws java.io.IOException {
    var token = parser.currentToken();
    if (token == null) {
      return null;
    }
    if (token == com.fasterxml.jackson.core.JsonToken.START_OBJECT) {
      return readJsonObject(parser);
    }
    if (token == com.fasterxml.jackson.core.JsonToken.START_ARRAY) {
      List<Object> out = new ArrayList<>();
      while (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.END_ARRAY) {
        out.add(readJsonValue(parser));
      }
      return out;
    }
    if (token == com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_INT
        || token == com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_FLOAT) {
      return new BigDecimal(parser.getText());
    }
    if (token == com.fasterxml.jackson.core.JsonToken.VALUE_TRUE) {
      return Boolean.TRUE;
    }
    if (token == com.fasterxml.jackson.core.JsonToken.VALUE_FALSE) {
      return Boolean.FALSE;
    }
    if (token == com.fasterxml.jackson.core.JsonToken.VALUE_NULL) {
      return null;
    }
    return parser.getText();
  }

  /**
   * 🔴 登记一条否定。
   *
   * <p>🔴 **依据由服务端现算**（搜索词报告优先，落到关键词表现），不采信调用方给的数字 ——
   * 这样「凭什么否定它」永远是系统看到的那份数据。调用方自述的依据原样存进
   * {@code claimedEvidence} 作审计留痕。
   */
  public AdNegativeRow createNegative(AdNegativeInput input) {
    UUID tenant = currentTenant();
    String negationText = text(input.negationText());
    if (negationText.isEmpty()) {
      throw badRequest("被否定的词原文不能为空");
    }
    String reason = text(input.reason());
    if (reason.isEmpty()) {
      throw badRequest("否定必须写明原因 —— 否定要能追溯到依据");
    }
    String negationType = text(input.negationType()).toUpperCase(Locale.ROOT);
    if (negationType.isEmpty()) {
      negationType = "NEGATIVE_EXACT";
    }
    if (!NEGATION_TYPES.contains(negationType)) {
      throw unprocessable("否定类型只能是 NEGATIVE_EXACT / NEGATIVE_PHRASE（收到「" + negationType + "」）");
    }
    String externalCampaignId = text(input.externalCampaignId());
    if (externalCampaignId.isEmpty()) {
      throw badRequest("外部活动 id 不能为空");
    }

    // 🔴 关键词必须属于本租户；否定词的店铺由关键词推导（DB 触发器也核一遍）
    UUID keywordId = input.keywordId();
    UUID shopId;
    if (keywordId != null) {
      Record keyword =
          sql.fetchOne(
              "select id, shop_id, external_campaign_id from ad_keyword where tenant_id=? and id=?",
              tenant, keywordId);
      if (keyword == null) {
        throw notFound("关键词");
      }
      shopId = keyword.get("shop_id", UUID.class);
    } else {
      throw badRequest("否定必须带上关键词 id —— 否定词是活动级但要挂在具体的投放词下才能回溯");
    }

    LocalDate to = input.evidenceTo() == null ? LocalDate.now(ZoneOffset.UTC) : input.evidenceTo();
    LocalDate from = input.evidenceFrom() == null ? to.minusDays(29) : input.evidenceFrom();
    if (from.isAfter(to)) {
      throw badRequest("依据区间起点不能晚于终点");
    }

    // 🔴 现算依据：先看搜索词报告（否定的通常是一个搜索词），没有就落到关键词表现
    Map<String, Object> evidence = computeEvidence(tenant, keywordId, negationText, from, to);

    UUID id = Ids.next();
    Map<String, Object> evidenceWithClaim = new LinkedHashMap<>(evidence);
    if (input.claimedEvidence() != null && !input.claimedEvidence().isEmpty()) {
      evidenceWithClaim.put("claimed", input.claimedEvidence());
    }
    sql.execute(
        """
        insert into ad_negative_keyword
          (id,tenant_id,keyword_id,shop_id,external_campaign_id,negation_text,negation_type,
           status,reason,evidence,evidence_from,evidence_to,suggested_by,created_at,updated_at)
        values (?,?,?,?,?,?,?,'DECIDED',?,?::jsonb,?,?,?,now(),now())
        """,
        id,
        tenant,
        keywordId,
        shopId,
        externalCampaignId,
        negationText,
        negationType,
        reason,
        writeJson(evidenceWithClaim),
        from,
        to,
        text(input.suggestedBy()).isEmpty()
            ? "MANUAL"
            : text(input.suggestedBy()).toUpperCase(Locale.ROOT));

    return fetchNegative(tenant, id);
  }

  /**
   * 🔴 现算否定依据：曝光 / 点击 / 转化 / 花费 / 销售额 / ACOS / 截至时间。
   *
   * <p>🔴 优先取**搜索词报告**（否定的通常是一个搜索词）；没有对应搜索词时落到
   * **关键词表现**（否定的就是投放词本身）。两者都没有 ⇒ {@code hasData=false}，
   * 依据里如实写「没有数据」，不编数字。
   */
  private Map<String, Object> computeEvidence(
      UUID tenant, UUID keywordId, String negationText, LocalDate from, LocalDate to) {
    Record searchTerm =
        sql.fetchOne(
            "select sum(impressions) as impressions, sum(clicks) as clicks,"
                + " sum(conversions) as conversions, sum(spend) as spend, sum(sales) as sales,"
                + " max(currency) as currency, max(synced_at) as synced_at"
                + " from ad_search_term where tenant_id=? and keyword_id=? and lower(search_term)=?"
                + " and bucket_date >= ? and bucket_date <= ?",
            tenant, keywordId, negationText.toLowerCase(Locale.ROOT), from, to);
    boolean fromSearchTerm = searchTerm != null && searchTerm.get("impressions", Long.class) != null;
    Record source = fromSearchTerm ? searchTerm : null;
    if (source == null) {
      Record metric =
          sql.fetchOne(
              "select sum(impressions) as impressions, sum(clicks) as clicks,"
                  + " sum(conversions) as conversions, sum(spend) as spend, sum(sales) as sales,"
                  + " max(currency) as currency, max(synced_at) as synced_at"
                  + " from ad_keyword_metric where tenant_id=? and keyword_id=?"
                  + " and bucket_date >= ? and bucket_date <= ?",
              tenant, keywordId, from, to);
      if (metric != null && metric.get("impressions", Long.class) != null) {
        source = metric;
      }
    }
    Map<String, Object> evidence = new LinkedHashMap<>();
    if (source == null) {
      evidence.put("hasData", false);
      evidence.put("impressions", 0L);
      evidence.put("clicks", 0L);
      evidence.put("conversions", 0);
      evidence.put("spend", BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
      evidence.put("sales", BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
      evidence.put("acos", null);
      evidence.put("currency", "");
      evidence.put("dataAsOf", null);
      evidence.put("source", fromSearchTerm ? "SEARCH_TERM" : "KEYWORD_METRIC");
      evidence.put("from", from.toString());
      evidence.put("to", to.toString());
      return evidence;
    }
    long impressions = longOf(source, "impressions");
    long clicks = longOf(source, "clicks");
    int conversions = intOf(source, "conversions");
    BigDecimal spend = decimalOf(source, "spend");
    BigDecimal sales = decimalOf(source, "sales");
    BigDecimal acos =
        sales != null && sales.signum() > 0 ? spend.divide(sales, 4, RoundingMode.HALF_UP) : null;
    evidence.put("hasData", true);
    evidence.put("impressions", impressions);
    evidence.put("clicks", clicks);
    evidence.put("conversions", conversions);
    evidence.put("spend", money(spend));
    evidence.put("sales", money(sales));
    evidence.put("acos", acos);
    evidence.put("currency", stringOf(source, "currency"));
    evidence.put("dataAsOf", timeOf(source, "synced_at"));
    evidence.put("source", fromSearchTerm ? "SEARCH_TERM" : "KEYWORD_METRIC");
    evidence.put("from", from.toString());
    evidence.put("to", to.toString());
    return evidence;
  }

  private AdNegativeRow fetchNegative(UUID tenant, UUID id) {
    Record row =
        sql.fetchOne(
            "select n.id as negative_id, n.keyword_id, n.negation_text, n.negation_type,"
                + " n.status, n.external_campaign_id, n.shop_id, sh.code as shop_code,"
                + " f.code as platform_code, n.reason, n.evidence, n.evidence_from, n.evidence_to,"
                + " n.suggested_by, n.applied_at, n.platform_note, n.created_at"
                + " from ad_negative_keyword n"
                + " join shop sh on sh.tenant_id = n.tenant_id and sh.id = n.shop_id"
                + " left join ad_keyword k on k.tenant_id = n.tenant_id and k.id = n.keyword_id"
                + " left join platform f on f.id = k.platform_id"
                + " where n.tenant_id = ? and n.id = ?",
            tenant, id);
    if (row == null) {
      throw notFound("否定记录");
    }
    return negativeRow(row);
  }

  /**
   * 🔴 登记平台对一条否定的回执。
   *
   * <p>🔴 本模块**不推平台**（推送归 02 通道 / 平台适配器），但回执必须能进来 ——
   * 否则这条否定永远停在「已决定」，运营不知道它到底生效没有。
   */
  public AdNegativeRow recordNegativeResult(String negativeId, AdNegativeResultInput input) {
    UUID tenant = currentTenant();
    UUID id = parseId(negativeId);
    Record existing =
        sql.fetchOne(
            "select id, status from ad_negative_keyword where tenant_id=? and id=?", tenant, id);
    if (existing == null) {
      throw notFound("否定记录");
    }
    boolean applied = Boolean.TRUE.equals(input.applied());
    sql.execute(
        "update ad_negative_keyword set status=?, platform_note=?, applied_at=?, updated_at=now()"
            + " where tenant_id=? and id=?",
        applied ? "PUSHED" : "REJECTED",
        note(input.platformNote()),
        bind(java.time.OffsetDateTime.now(ZoneOffset.UTC)),
        tenant,
        id);
    return fetchNegative(tenant, id);
  }

  // ==================================================================
  // 🔴 批量调价与否定
  // ==================================================================

  /**
   * 🔴 批量调价与否定。
   *
   * <p>🔴 护栏（蓝图 14.1.1.3 同一口径：单次上限 + 总消耗上限）：
   *
   * <ul>
   *   <li>单次条目上限 {@value #MAX_BATCH_ITEMS} 条 —— 超过直接 400（整批不动）。
   *   <li>单条调整幅度上限 ±50%。
   *   <li>单条竞价绝对上限 {@value #MAX_BID_ABSOLUTE}。
   *   <li>单次**总上调**上限 {@value #MAX_TOTAL_BID_INCREASE}（按本次声明的币种计价）。
   * </ul>
   *
   * <p>🔴 **总上调越线 ⇒ 整批拒绝**（逐条标跳过并写清是哪条上限）：调价是花钱的动作，
   * 「部分执行」比「不动」危险得多。
   *
   * <p>🔴 批次级幂等：同一个 {@code idempotencyKey} 再次投递 ⇒ 整批不执行（重复调价就是事故）。
   */
  public AdBulkActionResult bulkAction(AdBulkActionRequest request) {
    UUID tenant = currentTenant();
    String actionType = text(request.actionType()).toUpperCase(Locale.ROOT);
    if (!BULK_ACTION_TYPES.contains(actionType)) {
      throw unprocessable("动作类型只能是 BID_ADJUST / NEGATE（收到「" + actionType + "」）");
    }
    String idempotencyKey = text(request.idempotencyKey());
    if (idempotencyKey.isEmpty()) {
      throw badRequest("幂等键不能为空 —— 调价是花钱的动作，重复执行就是事故");
    }
    List<AdBulkBidTarget> targets =
        request.targets() == null ? List.of() : request.targets();
    if (targets.isEmpty()) {
      throw badRequest("批量操作必须给出目标条目");
    }
    if (targets.size() > MAX_BATCH_ITEMS) {
      throw badRequest(
          "单次批量条目上限 " + MAX_BATCH_ITEMS + " 条（收到 " + targets.size() + " 条）—— 越线整批不动");
    }
    // 🔴 同一批次里**不能有重复的关键词**：DB 上 UNIQUE(tenant_id, action_id, keyword_id)
    //    是一条硬约束，服务层提前拒（写明是哪个重复），而不是让 DB 抛一个看不懂的
    //    DuplicateKey 500。
    {
      Map<UUID, Integer> seen = new LinkedHashMap<>();
      for (int i = 0; i < targets.size(); i++) {
        Integer at = seen.put(targets.get(i).keywordId(), i);
        if (at != null) {
          throw badRequest(
              "同一批次里第 "
                  + (i + 1)
                  + " 条目标与第 "
                  + (at + 1)
                  + " 条是同一个关键词（"
                  + targets.get(i).keywordId()
                  + "）—— 一个关键词在一次批量操作里只能出现一次");
        }
      }
    }
    String currency = text(request.currency()).toUpperCase(Locale.ROOT);
    String bidMode = text(request.bidMode()).toUpperCase(Locale.ROOT);
    if ("BID_ADJUST".equals(actionType)) {
      if (!"PERCENT".equals(bidMode) && !"ABSOLUTE".equals(bidMode)) {
        throw badRequest("批量调价必须给调价方式：PERCENT（百分比）或 ABSOLUTE（直接设成某个竞价）");
      }
      if (currency.isEmpty()) {
        throw badRequest("批量调价必须声明币种 —— 逐条按币种核对，币种缺失会让护栏失去意义");
      }
    }

    // 🔴 幂等：这一批跑过没有
    Record existing =
        sql.fetchOne(
            "select id, status, action_type, requested_count, applied_count, failed_count,"
                + " reason, guardrail, currency from ad_keyword_bulk_action"
                + " where tenant_id=? and idempotency_key=?",
            tenant, idempotencyKey);
    if (existing != null) {
      return bulkResult(tenant, existing.get("id", UUID.class), true);
    }

    UUID shopId = request.shopId();
    long shopFound =
        count("select count(*) from shop where tenant_id=? and id=?", tenant, shopId);
    if (shopFound == 0L) {
      throw notFound("店铺");
    }

    UUID actionId = Ids.next();
    List<AdBulkActionItemRow> items = new ArrayList<>();
    int applied = 0;
    int failed = 0;
    String rejectReason = "";
    BigDecimal totalIncrease = BigDecimal.ZERO;

    // 🔴 逐条解析目标关键词
    for (AdBulkBidTarget target : targets) {
      Record keyword =
          sql.fetchOne(
              "select id, keyword, shop_id, bid, bid_currency, external_campaign_id, status"
                  + " from ad_keyword where tenant_id=? and id=?",
              tenant, target.keywordId());
      if (keyword == null) {
        items.add(
            new AdBulkActionItemRow(
                null, target.keywordId(), "", null, null, "FAILED",
                "关键词不存在或不属于本租户", Map.of()));
        failed++;
        continue;
      }
      if (!keyword.get("shop_id", UUID.class).equals(shopId)) {
        items.add(
            new AdBulkActionItemRow(
                null, target.keywordId(), stringOf(keyword, "keyword"), null, null, "FAILED",
                "关键词不属于本次批量操作指定的店铺", Map.of()));
        failed++;
        continue;
      }
      if ("ARCHIVED".equals(stringOf(keyword, "status"))) {
        items.add(
            new AdBulkActionItemRow(
                null, target.keywordId(), stringOf(keyword, "keyword"), null, null, "FAILED",
                "关键词已归档，不能再调价", Map.of()));
        failed++;
        continue;
      }
      if ("BID_ADJUST".equals(actionType)) {
        BulkBidOutcome outcome =
            applyBid(
                tenant,
                keyword,
                bidMode,
                currency,
                target.bidValue(),
                totalIncrease);
        if (outcome.rejected()) {
          rejectReason = outcome.reason();
          items.add(
              new AdBulkActionItemRow(
                  null, target.keywordId(), stringOf(keyword, "keyword"),
                  outcome.beforeBid(), outcome.afterBid(), "SKIPPED", outcome.reason(), Map.of()));
          failed++;
          continue;
        }
        totalIncrease = totalIncrease.add(outcome.increase());
        if (outcome.failed()) {
          items.add(
              new AdBulkActionItemRow(
                  null, target.keywordId(), stringOf(keyword, "keyword"),
                  outcome.beforeBid(), outcome.afterBid(), "FAILED", outcome.reason(), Map.of()));
          failed++;
          continue;
        }
        items.add(
            new AdBulkActionItemRow(
                null, target.keywordId(), stringOf(keyword, "keyword"),
                outcome.beforeBid(), outcome.afterBid(), "APPLIED", outcome.reason(), Map.of()));
        applied++;
      } else {
        BulkNegateOutcome outcome = applyNegate(tenant, keyword, request.negationType());
        if (outcome.failed()) {
          items.add(
              new AdBulkActionItemRow(
                  null, target.keywordId(), stringOf(keyword, "keyword"), null, null, "FAILED",
                  outcome.reason(), Map.of()));
          failed++;
          continue;
        }
        items.add(
            new AdBulkActionItemRow(
                null, target.keywordId(), stringOf(keyword, "keyword"), null, null, "APPLIED",
                outcome.reason(), outcome.evidence()));
        applied++;
      }
    }

    // 🔴 总上调上限：**整批拒绝**（一条都不动），逐条标跳过
    boolean overTotal = totalIncrease.compareTo(MAX_TOTAL_BID_INCREASE) > 0;
    if (overTotal) {
      rejectReason =
          "单次总上调 "
              + totalIncrease.toPlainString()
              + " "
              + currency
              + " 超过上限 "
              + MAX_TOTAL_BID_INCREASE.toPlainString()
              + " —— 整批不动（部分执行比不动危险）";
      for (AdBulkActionItemRow item : items) {
        if ("APPLIED".equals(item.status())) {
          items.set(
              items.indexOf(item),
              new AdBulkActionItemRow(
                  item.itemId(), item.keywordId(), item.targetRef(), item.beforeBid(),
                  item.afterBid(), "SKIPPED", rejectReason, item.result()));
        }
      }
      applied = 0;
      failed = items.size();
    }

    String status =
        !rejectReason.isEmpty() ? "REJECTED" : (failed == 0 ? "APPLIED" : (applied == 0 ? "REJECTED" : "PARTIAL"));
    Map<String, Object> guardrail = new LinkedHashMap<>();
    guardrail.put("maxBatchItems", MAX_BATCH_ITEMS);
    guardrail.put("maxBidDeltaRatio", MAX_BID_DELTA_RATIO);
    guardrail.put("maxBidAbsolute", MAX_BID_ABSOLUTE);
    guardrail.put("maxTotalBidIncrease", MAX_TOTAL_BID_INCREASE);
    guardrail.put("currency", currency);
    guardrail.put("totalIncrease", totalIncrease);

    sql.execute(
        """
        insert into ad_keyword_bulk_action
          (id,tenant_id,shop_id,action_type,status,currency,requested_count,applied_count,
           failed_count,guardrail,idempotency_key,reason,note,created_at,updated_at)
        values (?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,now(),now())
        """,
        actionId,
        tenant,
        shopId,
        actionType,
        status,
        currency,
        targets.size(),
        applied,
        failed,
        writeJson(guardrail),
        idempotencyKey,
        note(rejectReason),
        note(request.note()));

    for (AdBulkActionItemRow item : items) {
      sql.execute(
          """
          insert into ad_keyword_bulk_action_item
            (id,tenant_id,action_id,keyword_id,target_ref,before_bid,after_bid,status,reason,
             result,created_at)
          values (?,?,?,?,?,?,?,?,?,?::jsonb,now())
          """,
          Ids.next(),
          tenant,
          actionId,
          item.keywordId(),
          item.targetRef(),
          item.beforeBid(),
          item.afterBid(),
          item.status(),
          item.reason(),
          writeJson(item.result()));
    }

    return bulkResult(tenant, actionId, false);
  }

  /** 一次调价的结果。 */
  private record BulkBidOutcome(
      boolean failed,
      boolean rejected,
      String reason,
      BigDecimal beforeBid,
      BigDecimal afterBid,
      BigDecimal increase) {
    static BulkBidOutcome fail(String reason, BigDecimal before) {
      return new BulkBidOutcome(true, false, reason, before, null, BigDecimal.ZERO);
    }

    static BulkBidOutcome ok(String reason, BigDecimal before, BigDecimal after, BigDecimal increase) {
      return new BulkBidOutcome(false, false, reason, before, after, increase);
    }
  }

  /**
   * 🔴 单条调价的护栏判定。
   *
   * <p>🔴 币种不一致 ⇒ 该条**失败**（不按同一个数把不同币种一起调）。
   * 🔴 幅度上限 ±50%、绝对上限 1000.00、竞价不得为负。
   */
  private BulkBidOutcome applyBid(
      UUID tenant,
      Record keyword,
      String bidMode,
      String currency,
      BigDecimal requested,
      BigDecimal alreadyIncreased) {
    BigDecimal before = decimalOf(keyword, "bid");
    if (before == null) {
      before = BigDecimal.ZERO;
    }
    String keywordCurrency = text(stringOf(keyword, "bid_currency")).toUpperCase(Locale.ROOT);
    String note = "";
    if (!keywordCurrency.isEmpty() && !keywordCurrency.equals(currency)) {
      return BulkBidOutcome.fail(
          "币种不一致（关键词 " + keywordCurrency + " vs 本次 " + currency + "）—— 不按同一个数把不同币种一起调",
          before);
    }
    if (keywordCurrency.isEmpty()) {
      note = "（已按本次币种 " + currency + " 首次设定该关键词的竞价币种）";
    }
    if (requested == null) {
      return BulkBidOutcome.fail("没有给期望的调价值", before);
    }
    BigDecimal after;
    if ("PERCENT".equals(bidMode)) {
      BigDecimal ratio =
          requested.abs().divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP);
      if (ratio.compareTo(MAX_BID_DELTA_RATIO) > 0) {
        return BulkBidOutcome.fail(
            "单次调整幅度 " + requested.toPlainString() + "% 超过上限 "
                + MAX_BID_DELTA_RATIO.multiply(new BigDecimal("100")).toPlainString() + "%",
            before);
      }
      after =
          before
              .multiply(BigDecimal.ONE.add(ratio))
              .setScale(4, RoundingMode.HALF_UP);
    } else {
      after = money(requested);
    }
    if (after.signum() < 0) {
      return BulkBidOutcome.fail("调价结果为负数", before);
    }
    if (after.compareTo(MAX_BID_ABSOLUTE) > 0) {
      return BulkBidOutcome.fail(
          "调价结果 " + after.toPlainString() + " 超过单条绝对上限 " + MAX_BID_ABSOLUTE.toPlainString(),
          before);
    }
    BigDecimal increase = after.subtract(before);
    if (increase.signum() < 0) {
      increase = BigDecimal.ZERO;
    }
    // 🔴 总上调上限在整批层判（这里先给单条的上限），避免逐条写入后才发现越线
    if (alreadyIncreased.add(increase).compareTo(MAX_TOTAL_BID_INCREASE) > 0) {
      return new BulkBidOutcome(
          false, true,
          "整次批量调价的总上调将超过上限 " + MAX_TOTAL_BID_INCREASE.toPlainString() + " —— 整批不动",
          before, after, increase);
    }
    sql.execute(
        "update ad_keyword set bid=?, bid_currency=?, updated_at=now() where tenant_id=? and id=?",
        after, currency, tenant, keyword.get("id", UUID.class));
    return BulkBidOutcome.ok(
        "竞价 " + before.toPlainString() + " → " + after.toPlainString() + " " + currency + note,
        before,
        after,
        increase);
  }

  /** 一次否定的结果。 */
  private record BulkNegateOutcome(boolean failed, String reason, Map<String, Object> evidence) {}

  /**
   * 🔴 批量否定：按**关键词表现**现算依据（否定的就是投放词本身），落否定记录。
   *
   * <p>🔴 已经否定过（DECIDED / PUSHED）的关键词**不再重复否定** —— 重复否定会让
   * 否定列表变成一份「看起来否定了很多次」的假账。
   */
  private BulkNegateOutcome applyNegate(UUID tenant, Record keyword, String negationType) {
    UUID keywordId = keyword.get("id", UUID.class);
    long already =
        count(
            "select count(*) from ad_negative_keyword where tenant_id=? and keyword_id=?"
                + " and status in ('DECIDED','PUSHED')",
            tenant, keywordId);
    if (already > 0L) {
      return new BulkNegateOutcome(true, "该关键词已有否定记录（DECIDED / PUSHED），不重复否定", Map.of());
    }
    String type = text(negationType).toUpperCase(Locale.ROOT);
    if (type.isEmpty()) {
      type = "NEGATIVE_EXACT";
    }
    if (!NEGATION_TYPES.contains(type)) {
      return new BulkNegateOutcome(
          true, "否定类型只能是 NEGATIVE_EXACT / NEGATIVE_PHRASE（收到「" + type + "」）", Map.of());
    }
    LocalDate to = LocalDate.now(ZoneOffset.UTC);
    LocalDate from = to.minusDays(29);
    Map<String, Object> evidence =
        computeEvidence(tenant, keywordId, stringOf(keyword, "keyword"), from, to);
    sql.execute(
        """
        insert into ad_negative_keyword
          (id,tenant_id,keyword_id,shop_id,external_campaign_id,negation_text,negation_type,
           status,reason,evidence,evidence_from,evidence_to,suggested_by,created_at,updated_at)
        values (?,?,?,?,?,?,?,'DECIDED',?,?::jsonb,?,?,'SYSTEM_SUGGESTION',now(),now())
        """,
        Ids.next(),
        tenant,
        keywordId,
        keyword.get("shop_id", UUID.class),
        stringOf(keyword, "external_campaign_id"),
        stringOf(keyword, "keyword"),
        type,
        "批量否定：高曝光零转化 / 花了钱没转化",
        writeJson(evidence),
        from,
        to);
    return new BulkNegateOutcome(
        false,
        "已登记否定（依据：曝光 "
            + evidence.get("impressions")
            + "、点击 "
            + evidence.get("clicks")
            + "、转化 "
            + evidence.get("conversions")
            + "、花费 "
            + evidence.get("spend")
            + "、ACOS "
            + evidence.get("acos")
            + "）",
        evidence);
  }

  /** 批量任务列表（真分页 + 筛选）。 */
  public AdBulkActionPage bulkActions(String actionType, String status, int page, int size) {
    UUID tenant = currentTenant();
    // 🔴 主表别名是 a（批量任务表）
    Filters filters = new Filters("a");
    filters.binds.add(tenant);
    String typeValue = text(actionType).toUpperCase(Locale.ROOT);
    if (!typeValue.isEmpty() && !"ALL".equals(typeValue)) {
      if (!BULK_ACTION_TYPES.contains(typeValue)) {
        throw unprocessable("动作类型只能是 BID_ADJUST / NEGATE / ALL");
      }
      filters.add(" and a.action_type = ?", typeValue);
    }
    String statusValue = text(status).toUpperCase(Locale.ROOT);
    if (!statusValue.isEmpty() && !"ALL".equals(statusValue)) {
      if (!BULK_ACTION_STATUSES.contains(statusValue)) {
        throw unprocessable("任务状态只能是 RECEIVED / APPLIED / PARTIAL / REJECTED / ALL");
      }
      filters.add(" and a.status = ?", statusValue);
    }
    long total =
        count(
            "select count(*) from ad_keyword_bulk_action a" + filters.where,
            filters.binds.toArray());
    List<Object> binds = new ArrayList<>(filters.binds);
    binds.add(clampSize(size));
    binds.add(page * clampSize(size));
    List<AdBulkActionRow> items =
        sql.fetch(
                "select a.id as action_id, a.action_type, a.status, a.shop_id, sh.code as shop_code,"
                    + " a.currency, a.requested_count, a.applied_count, a.failed_count, a.reason,"
                    + " a.guardrail, a.idempotency_key, a.note, a.created_at"
                    + " from ad_keyword_bulk_action a"
                    + " join shop sh on sh.tenant_id = a.tenant_id and sh.id = a.shop_id"
                    + filters.where
                    + " order by a.created_at desc, a.id desc limit ? offset ?",
                binds.toArray())
            .stream()
            .map(
                r ->
                    new AdBulkActionRow(
                        r.get("action_id", UUID.class),
                        stringOf(r, "action_type"),
                        stringOf(r, "status"),
                        r.get("shop_id", UUID.class),
                        stringOf(r, "shop_code"),
                        stringOf(r, "currency"),
                        intOf(r, "requested_count"),
                        intOf(r, "applied_count"),
                        intOf(r, "failed_count"),
                        stringOf(r, "reason"),
                        readJson(r.get("guardrail")),
                        stringOf(r, "idempotency_key"),
                        stringOf(r, "note"),
                        timeOf(r, "created_at")))
            .toList();
    return new AdBulkActionPage(items, page, clampSize(size), total);
  }

  /** 批量任务详情（含逐条执行结果）。 */
  public AdBulkActionResult bulkActionDetail(String actionId) {
    UUID tenant = currentTenant();
    return bulkResult(tenant, parseId(actionId), false);
  }

  private AdBulkActionResult bulkResult(UUID tenant, UUID actionId, boolean replayed) {
    Record row =
        sql.fetchOne(
            "select id, action_type, status, currency, requested_count, applied_count, failed_count,"
                + " reason, guardrail, idempotency_key from ad_keyword_bulk_action"
                + " where tenant_id=? and id=?",
            tenant, actionId);
    if (row == null) {
      throw notFound("批量任务");
    }
    List<AdBulkActionItemRow> items =
        sql.fetch(
                "select id as item_id, keyword_id, target_ref, before_bid, after_bid, status, reason,"
                    + " result from ad_keyword_bulk_action_item where tenant_id=? and action_id=?"
                    + " order by created_at asc, id asc",
                tenant, actionId)
            .stream()
            .map(
                r ->
                    new AdBulkActionItemRow(
                        r.get("item_id", UUID.class),
                        r.get("keyword_id", UUID.class),
                        stringOf(r, "target_ref"),
                        decimalOf(r, "before_bid"),
                        decimalOf(r, "after_bid"),
                        stringOf(r, "status"),
                        stringOf(r, "reason"),
                        readJson(r.get("result"))))
            .toList();
    // 🔴 重投时把「本次没有执行」写进 reason —— 页面必须显示出来，
    //    否则运营会以为系统又调了一次价（调价是花钱的动作）。
    String reason = stringOf(row, "reason");
    if (replayed) {
      reason =
          "该批次此前已执行（幂等：本次未执行）；既有任务状态 "
              + stringOf(row, "status")
              + "，成功 "
              + intOf(row, "applied_count")
              + " 条 / 失败 "
              + intOf(row, "failed_count")
              + " 条";
    }
    return new AdBulkActionResult(
        row.get("id", UUID.class),
        stringOf(row, "action_type"),
        stringOf(row, "status"),
        stringOf(row, "idempotency_key"),
        replayed,
        intOf(row, "requested_count"),
        intOf(row, "applied_count"),
        intOf(row, "failed_count"),
        reason,
        readJson(row.get("guardrail")),
        items);
  }

  // ==================================================================
  // 关键词报表
  // ==================================================================

  /**
   * 🔴 关键词报表（真 SQL 聚合）。
   *
   * <p>🔴 维度：PLATFORM / SHOP / CAMPAIGN / AD_TYPE / ALL。
   * 🔴 **按币种分行**：跨币种相加没有意义，混在一行里的 ACOS 是假的。
   * 🔴 **UNMAPPED 不参与可比聚合**：它单列一行、带完整原始类型标记。
   */
  public AdKeywordReport report(
      String dimension, String from, String to, String internalAdType, int page, int size) {
    UUID tenant = currentTenant();
    DayRange range = dayRange(from, to);
    String dim = text(dimension).toUpperCase(Locale.ROOT);
    if (dim.isEmpty()) {
      dim = "PLATFORM";
    }
    if (!List.of("PLATFORM", "SHOP", "CAMPAIGN", "AD_TYPE", "ALL").contains(dim)) {
      throw unprocessable("维度只能是 PLATFORM / SHOP / CAMPAIGN / AD_TYPE / ALL（收到「" + dim + "」）");
    }

    String dimValue;
    String dimExpr;
    switch (dim) {
      case "SHOP" -> {
        dimValue = "sh.code";
        dimExpr = "sh.code";
      }
      case "CAMPAIGN" -> {
        dimValue = "k.external_campaign_id";
        dimExpr = "k.external_campaign_id";
      }
      case "AD_TYPE" -> {
        dimValue = "k.internal_ad_type";
        dimExpr = "k.internal_ad_type";
      }
      case "ALL" -> {
        // 🔴 ALL 维度的「维度值」是常量 —— 常量**不进 GROUP BY**
        //    （PostgreSQL 允许，但把它排除掉更省事，也免得踩「常量不在 GROUP BY」那类边角）
        dimValue = "'ALL'";
        dimExpr = null;
      }
      default -> {
        dimValue = "f.code";
        dimExpr = "f.code";
      }
    }

    String where = " where k.tenant_id = ? and mt.bucket_date >= ? and mt.bucket_date <= ?";
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    binds.add(range.from());
    binds.add(range.to());
    if (!text(internalAdType).isEmpty()) {
      where += " and k.internal_ad_type = ?";
      binds.add(internalAdType.trim());
    }

    // 🔴 两层聚合：**内层按关键词归集**（所以 k.id 可用、外层的 sum 不会把一条关键词的
    //    多天指标算成多条），**外层按维度归集**。
    //    内层已经把「这条关键词有没有被否定」算成 0/1，外层直接 sum ——
    //    聚合参数里的列不受 GROUP BY 检查（写成 FILTER 反而会踩到）。
    String inner =
        """
        select k.id as keyword_id, %s as dimension_value, k.metric_currency as currency,
               k.internal_ad_type, k.platform_ad_type,
               sum(mt.impressions) as impressions, sum(mt.clicks) as clicks,
               sum(mt.conversions) as conversions, sum(mt.spend) as spend, sum(mt.sales) as sales,
               case when exists (select 1 from ad_negative_keyword n4
                                 where n4.tenant_id = k.tenant_id and n4.keyword_id = k.id)
                    then 1 else 0 end as negated
        from ad_keyword k
        join platform f on f.id = k.platform_id
        join shop sh on sh.tenant_id = k.tenant_id and sh.id = k.shop_id
        join ad_keyword_metric mt on mt.tenant_id = k.tenant_id and mt.keyword_id = k.id
        """
            .formatted(dimValue)
            + where
            + " group by k.id, "
            + (dimExpr == null ? "" : dimExpr + ", ")
            + "k.metric_currency, k.internal_ad_type, k.platform_ad_type";

    String groupClause =
        " group by km.dimension_value, km.currency, km.internal_ad_type, km.platform_ad_type";

    long total =
        count(
            "select count(*) from (select km.dimension_value from (" + inner + ") km"
                + groupClause
                + ") x",
            binds.toArray());

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(clampSize(size));
    pageBinds.add(page * clampSize(size));

    List<AdKeywordReportRow> rows =
        sql.fetch(
                "select km.dimension_value, km.currency, km.internal_ad_type, km.platform_ad_type,"
                    + " count(*) as keyword_count, sum(km.impressions) as impressions,"
                    + " sum(km.clicks) as clicks, sum(km.conversions) as conversions,"
                    + " sum(km.spend) as spend, sum(km.sales) as sales,"
                    + " sum(km.negated) as negated_count"
                    + " from (" + inner + ") km"
                    + groupClause
                    + " order by sum(km.spend) desc, km.dimension_value asc"
                    + " limit ? offset ?",
                pageBinds.toArray())
            .stream()
            .map(AdKeywordService::reportRow)
            .toList();

    OffsetDateTime asOf = dataAsOf(tenant);
    return new AdKeywordReport(
        dim,
        rows,
        METRIC_DEFINITIONS,
        asOf,
        range.from(),
        range.to(),
        syncStatus(asOf),
        syncNote(asOf),
        "🔴 报表只统计**区间内有指标数据**的关键词；跨币种**不可相加** ⇒ 按币种分行，"
            + "同一维度会出现多行（每行一个币种）。");
  }

  private static AdKeywordReportRow reportRow(Record r) {
    long impressions = longOf(r, "impressions");
    long clicks = longOf(r, "clicks");
    int conversions = intOf(r, "conversions");
    BigDecimal spend = decimalOf(r, "spend");
    BigDecimal sales = decimalOf(r, "sales");
    BigDecimal acos = sales != null && sales.signum() > 0 ? spend.divide(sales, 4, RoundingMode.HALF_UP) : null;
    BigDecimal cpc = clicks > 0 ? spend.divide(BigDecimal.valueOf(clicks), 4, RoundingMode.HALF_UP) : null;
    BigDecimal ctr =
        impressions > 0
            ? BigDecimal.valueOf(clicks).divide(BigDecimal.valueOf(impressions), 6, RoundingMode.HALF_UP)
            : null;
    BigDecimal cvr =
        clicks > 0
            ? BigDecimal.valueOf(conversions).divide(BigDecimal.valueOf(clicks), 6, RoundingMode.HALF_UP)
            : null;
    return new AdKeywordReportRow(
        null,
        stringOf(r, "dimension_value"),
        stringOf(r, "currency"),
        stringOf(r, "internal_ad_type"),
        stringOf(r, "platform_ad_type"),
        longOf(r, "keyword_count"),
        impressions,
        clicks,
        conversions,
        spend,
        sales,
        acos,
        cpc,
        ctr,
        cvr,
        longOf(r, "negated_count"));
  }

  // ==================================================================
  // 词频与排名
  // ==================================================================

  /**
   * 🔴 词频与排名（口径**引用 4.5.1.2**）。
   *
   * <p>🔴 名次读 4.5.1.2 的 {@code listing_keyword_rank_snapshot}（最近一次）；
   * 词频读 4.5.1.2 的 {@code listing_keyword}（同平台同词被多少个商品监控着）。
   * 本模块**不新建排名表、不自算名次**。
   */
  public AdKeywordFrequencyPage frequency(
      String platformCode, String from, String to, int page, int size) {
    UUID tenant = currentTenant();
    DayRange range = dayRange(from, to);
    Filters filters = new Filters("k");
    filters.binds.add(tenant);
    if (!text(platformCode).isEmpty()) {
      filters.add(" and f.code = ?", platformCode.trim());
    }
    String where =
        " where k.tenant_id = ? and mt.bucket_date >= ? and mt.bucket_date <= ?" + filters.extra();
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    binds.add(range.from());
    binds.add(range.to());
    binds.addAll(filters.binds.subList(1, filters.binds.size()));

    // 🔴 词频与名次用 **lateral** 取（不是 select 列表里的相关子查询）：
    //    lateral 在 FROM 里逐行求值，能引用 k.* / f.* 而**不受 GROUP BY 检查**；
    //    写在 select 列表里的相关子查询引用 k.tenant_id 会踩「must appear in the GROUP BY」。
    String fromClause =
        """
        from ad_keyword k
        join platform f on f.id = k.platform_id
        join shop sh on sh.tenant_id = k.tenant_id and sh.id = k.shop_id
        join ad_keyword_metric mt on mt.tenant_id = k.tenant_id and mt.keyword_id = k.id
        left join lateral (
          select count(*) as frequency, count(distinct lk.listing_id) as listing_count
          from listing_keyword lk
          where lk.tenant_id = k.tenant_id and lk.platform_code = f.code and lk.keyword = k.keyword
        ) fq on true
        left join lateral (
          select s.natural_rank, s.ad_rank, s.crawled_at, s.accuracy_window_minutes
          from listing_keyword_rank_snapshot s
          where s.tenant_id = k.tenant_id and s.keyword_id = k.listing_keyword_id
          order by s.crawled_at desc, s.received_at desc
          limit 1
        ) r on true
        """;
    String groupBy =
        " group by f.code, k.keyword, k.internal_ad_type, k.platform_ad_type, k.metric_currency";

    long total =
        count(
            "select count(*) from (select 1 " + fromClause + where + groupBy + ") x",
            binds.toArray());

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(clampSize(size));
    pageBinds.add(page * clampSize(size));

    List<AdKeywordFrequencyRow> rows =
        sql.fetch(
                "select f.code as platform_code, k.keyword, k.metric_currency as currency,"
                    + " k.internal_ad_type, k.platform_ad_type,"
                    + " count(distinct k.id) as keyword_count,"
                    + " count(distinct k.external_campaign_id) as campaign_count,"
                    + " sum(mt.impressions) as impressions, sum(mt.clicks) as clicks,"
                    + " sum(mt.conversions) as conversions, sum(mt.spend) as spend,"
                    + " sum(mt.sales) as sales,"
                    + " max(coalesce(fq.frequency,0)) as frequency,"
                    + " max(coalesce(fq.listing_count,0)) as frequency_listing_count,"
                    + " min(r.natural_rank) as natural_rank, min(r.ad_rank) as ad_rank,"
                    + " max(r.crawled_at) as ranking_crawled_at,"
                    + " max(r.accuracy_window_minutes) as ranking_accuracy_window_minutes "
                    + fromClause
                    + where
                    + groupBy
                    + " order by sum(mt.spend) desc, f.code asc, k.keyword asc"
                    + " limit ? offset ?",
                pageBinds.toArray())
            .stream()
            .map(
                r -> {
                  long impressions = longOf(r, "impressions");
                  long clicks = longOf(r, "clicks");
                  int conversions = intOf(r, "conversions");
                  BigDecimal spend = decimalOf(r, "spend");
                  BigDecimal sales = decimalOf(r, "sales");
                  BigDecimal acos =
                      sales != null && sales.signum() > 0
                          ? spend.divide(sales, 4, RoundingMode.HALF_UP)
                          : null;
                  return new AdKeywordFrequencyRow(
                      stringOf(r, "platform_code"),
                      stringOf(r, "keyword"),
                      stringOf(r, "currency"),
                      stringOf(r, "internal_ad_type"),
                      stringOf(r, "platform_ad_type"),
                      longOf(r, "keyword_count"),
                      longOf(r, "campaign_count"),
                      longOf(r, "frequency"),
                      longOf(r, "frequency_listing_count"),
                      r.get("natural_rank", Integer.class),
                      r.get("ad_rank", Integer.class),
                      timeOf(r, "ranking_crawled_at"),
                      r.get("ranking_accuracy_window_minutes", Integer.class),
                      impressions,
                      clicks,
                      conversions,
                      spend,
                      sales,
                      acos,
                      FREQUENCY_DEFINITIONS);
                })
            .toList();

    return new AdKeywordFrequencyPage(
        rows, page, clampSize(size), total, FREQUENCY_DEFINITIONS, dataAsOf(tenant));
  }

  // ==================================================================
  // 同步状态
  // ==================================================================

  /** 🔴 本租户的数据截至时间（指标与搜索词里最新的一批）。 */
  private OffsetDateTime dataAsOf(UUID tenant) {
    Record row =
        sql.fetchOne(
            "select greatest("
                + " (select max(synced_at) from ad_keyword_metric where tenant_id = ?),"
                + " (select max(synced_at) from ad_search_term where tenant_id = ?)) as as_of",
            tenant, tenant);
    return row == null ? null : timeOf(row, "as_of");
  }

  private static String syncStatus(OffsetDateTime asOf) {
    return asOf == null ? "NEVER_SYNCED" : "SYNCED";
  }

  private static String syncNote(OffsetDateTime asOf) {
    if (asOf == null) {
      return "本租户还没有同步过任何关键词指标或搜索词报告 —— 页面上的指标都是 0，"
          + "那是「没数据」，不是「没花钱」";
    }
    return "指标与搜索词数据截至 " + asOf + "（UTC）。"
        + "「没跑这个词」与「还没同步到数据」是���回事：同步过的关键词即使区间内为 0，"
        + "也有 metricAsOf 可查。";
  }
}
