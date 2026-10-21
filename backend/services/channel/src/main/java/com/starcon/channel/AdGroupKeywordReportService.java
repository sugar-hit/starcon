package com.starcon.channel;

import static com.starcon.channel.AdGroupKeywordReportModels.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 14.3.1.2 广告组与词报表。
 *
 * <p>蓝图：「广告组与关键词维度报表：单组/单词效果、搜索词质量、否定效果评估，支持批量选中导出。
 * 输入：维度选择（组/词/搜索词）；时间范围。输出：明细报表 + 汇总。
 * 关系：上游 = 14.1.3.1；下游 = 14.1.3.1 否定决策。
 * <b>关键词级数据量极大</b>，查询必须分页与缓存，不能实时全表扫。」
 *
 * <p>本类按职责句逐项落点：
 *
 * <ul>
 *   <li><b>维度选择（组 / 词 / 搜索词）</b> → {@link #report(Query)} 的 {@code dimension}：
 *       GROUP / KEYWORD / SEARCH_TERM，外加职责句点名的<b>否定效果评估</b> NEGATIVE_EFFECT。
 *   <li><b>单词效果 / 搜索词质量</b> → {@link #keywordReport} / {@link #searchTermReport}：
 *       指标<b>全部现算</b>，事实源是上游 14.1.3.1 的 {@code ad_keyword_metric} 与
 *       {@code ad_search_term}，本模块<b>不复制任何一列指标，也不建第二套指标表</b>。
 *   <li><b>单组效果</b> → {@link #groupReport}：🔴 <b>组级指标没有事实源</b>，
 *       所以只给<b>结构事实</b>（组名 / 状态 / 默认竞价 / 投放商品数 / 所属活动），
 *       指标列一律 <b>null</b>，并在能力声明里显式 available=false（见
 *       {@link #capabilities()}）。
 *   <li><b>否定效果评估</b> → {@link #negativeEffectReport}：口径由本类<b>定死并公示</b>
 *       （{@link #NEGATIVE_EFFECT_NOTE}）。
 *   <li><b>批量选中导出</b> → {@link #recordExport} + {@link #streamCsv}：
 *       留痕落在 {@code ad_group_keyword_report_export}（V283，形状与 14.3.1.1 的
 *       {@code ad_campaign_report_export} 一一对应）。
 *   <li><b>明细报表 + 汇总</b> → {@link #report(Query)}：行是明细，汇总是**单独一条聚合查询**
 *       统计筛选后的全集（不受分页影响），且**按币种拆开**。
 * </ul>
 *
 * <p>🔴 本类最容易做丢的五件事，这里都挡住了：
 *
 * <ol>
 *   <li><b>组级指标不冒充</b>。现有事实表没有组级指标表，而且 {@code ad_keyword} /
 *       {@code ad_search_term} <b>根本没有组的引用列</b> ⇒ 词指标在物理上无法归到组。
 *       所以组的指标列是 null（不是 0），能力声明里 available=false + ownerModule + 影响。
 *   <li><b>汇总先汇总再相除</b>。上游的指标表<b>没有</b> acos / roas 生成列（不像
 *       {@code ad_campaign_daily_metric} 有），所以本类一律用 Σ分子 / Σ分母现算；
 *       对比率求平均是数学错误（两条 ACOS 100% 的词平均成 50%，会让人以为整体在盈亏线上）。
 *   <li><b>分母为 0 给 NULL（不可比），不是 0</b>。ACOS = Σ花费 / Σ销售额、ROAS = Σ销售额 / Σ花费、
 *       CPC = Σ花费 / Σ点击。
 *   <li><b>否定后仍有数据 ≠ 0</b>。否定生效日之后的曝光/点击应当归零；若数据源仍在报，
 *       要能识别并标注「否定后仍有数据」，而不是把它当成 0 —— 「没有数据」（可能只是平台停报）
 *       与「有数据但都是 0」是两件事。
 *   <li><b>不同币种不合并</b>。本仓没有汇率源 ⇒ 分组键里始终带币种，汇总也按币种拆开。
 * </ol>
 *
 * <p>🔴 关键词级的性能纪律（蓝图加粗句「查询必须分页与缓存，不能实时全表扫」）：
 * 时间范围<b>必填</b>且按粒度封顶；谓词顺序对齐 V283 补的窗口索引
 * {@code ad_keyword_metric_report_window (tenant_id, bucket_date, keyword_id)}
 * （先 tenant_id、再 bucket_date 范围、最后 keyword_id）；<b>真分页</b>（LIMIT/OFFSET）；
 * <b>汇总单独一条聚合查询</b>；<b>缓存</b>带<b>明确的失效依据</b>（水位 + 批次 + 失效令牌 + 过期时刻）。
 */
@Service
public class AdGroupKeywordReportService {

  private static final String TENANT =
      "nullif(current_setting('app.current_tenant',true),'')::uuid";

  /** 报表维度。 */
  static final List<String> DIMENSIONS =
      List.of("GROUP", "KEYWORD", "SEARCH_TERM", "NEGATIVE_EFFECT");

  /** 时间粒度。TOTAL = 整个区间一行；DAY / WEEK / MONTH = 按桶拆明细。 */
  static final List<String> GRANULARITIES = List.of("TOTAL", "DAY", "WEEK", "MONTH");

  /** 🔴 时间跨度的上限（天）。超过直接拒，不做「悄悄截断」。 */
  static final int MAX_DAYS = 400;

  /** 🔴 缓存的存活时长（秒）。过期时刻会随响应一起返回。 */
  static final int CACHE_TTL_SECONDS = 300;

  static final String DEFAULT_GRANULARITY = "TOTAL";

  // ==========================================================================
  // 🔴 三个口径的定义：写在返回值里，不写在注释里
  // ==========================================================================

  /**
   * 逐指标的口径定义 —— **表头就是从这里渲染的**。
   *
   * <p>🔴 指标表（{@code ad_keyword_metric} / {@code ad_search_term}）上<b>没有</b>
   * acos / roas 生成列（不像 {@code ad_campaign_daily_metric} 有），所以这三个比率
   * 由本服务层**按同口径现算**：ACOS = Σ花费 / Σ销售额、ROAS = Σ销售额 / Σ花费、
   * CPC = Σ花费 / Σ点击。分母为 0 一律 <b>null（不可比）</b>，不是 0。
   */
  static final List<AdGroupReportMetricDefinition> METRIC_DEFINITIONS =
      List.of(
          new AdGroupReportMetricDefinition(
              "spend",
              "花费",
              true,
              "花费 = Σ广告消耗（平台回传的当日消耗）",
              "ad_keyword_metric.spend / ad_search_term.spend（V274）",
              "",
              ""),
          new AdGroupReportMetricDefinition(
              "impressions",
              "曝光",
              true,
              "曝光 = Σ展示量",
              "ad_keyword_metric.impressions / ad_search_term.impressions（V274）",
              "",
              ""),
          new AdGroupReportMetricDefinition(
              "clicks",
              "点击",
              true,
              "点击 = Σ点击量",
              "ad_keyword_metric.clicks / ad_search_term.clicks（V274）",
              "",
              ""),
          new AdGroupReportMetricDefinition(
              "conversions",
              "转化",
              true,
              "转化 = Σ转化次数（不是订单数）",
              "ad_keyword_metric.conversions / ad_search_term.conversions（V274）",
              "",
              ""),
          new AdGroupReportMetricDefinition(
              "ctr",
              "CTR 点击率",
              true,
              "CTR = Σ点击 / Σ曝光",
              "V274 指标表聚合（本服务层现算）",
              "",
              ""),
          new AdGroupReportMetricDefinition(
              "cpc",
              "CPC 单次点击成本",
              true,
              "CPC = Σ花费 / Σ点击",
              "V274 指标表聚合（本服务层现算）",
              "",
              ""),
          new AdGroupReportMetricDefinition(
              "cvr",
              "转化率",
              true,
              "转化率 = Σ转化 / Σ点击",
              "V274 指标表聚合（本服务层现算）",
              "",
              ""),
          new AdGroupReportMetricDefinition(
              "sales",
              "销售额",
              true,
              "销售额 = Σ广告带来的销售额（不是店铺总销售额）",
              "ad_keyword_metric.sales / ad_search_term.sales（V274）",
              "",
              ""),
          new AdGroupReportMetricDefinition(
              "acos",
              "ACOS",
              true,
              "ACOS = Σ花费 / Σ销售额",
              "V274 指标表聚合（本服务层现算）",
              "",
              ""),
          new AdGroupReportMetricDefinition(
              "roas",
              "ROAS",
              true,
              "ROAS = Σ销售额 / Σ花费",
              "V274 指标表聚合（本服务层现算）",
              "",
              ""),
          new AdGroupReportMetricDefinition(
              "group_metrics",
              "广告组指标",
              false,
              "组级指标 = 该组在区间内的展示/点击/转化/花费/销售额",
              "—",
              "14.1.2.1 广告组管理",
              "现有事实表里没有组级指标表，而且 ad_keyword / ad_search_term 根本没有组的引用列，"
                  + "词指标在物理上无法归到组 ⇒ 组维度只给结构事实（组名/状态/默认竞价/"
                  + "投放商品数/所属活动），指标列一律为空。不用活动级指标冒充组级指标，"
                  + "也不建一张空表冒充「没有数据」。"));

  /** 三个比率口径的一句话说明（随报表一起返回，界面表头直接显示）。 */
  static final String METRIC_DEFINITION_NOTE =
      "ACOS = Σ花费 / Σ销售额，ROAS = Σ销售额 / Σ花费，CPC = Σ花费 / Σ点击，"
          + "CTR = Σ点击 / Σ曝光，转化率 = Σ转化 / Σ点击。"
          + "全部按「先汇总再相除」算（Σ分子 / Σ分母），**不是**各行比率的平均 —— "
          + "后者是数学错误，会把两条 ACOS 100% 的词说成整体 50%。"
          + "分母为 0 一律返回空值（不可比），不是 0。"
          + "指标表上没有 acos / roas 生成列，这三个比率由服务层按上述口径现算。";

  /**
   * 🔴 否定效果评估的口径 —— <b>本模块定死并公示</b>。
   *
   * <p>蓝图 14.3.2.1 那句「归因规则必须固定并公示，规则变了历史报表不可比」在这里同样成立：
   * 否定之后该词的表现数据是否还在采集，<b>没有事实保证</b>，所以口径必须写清楚。
   */
  static final String NEGATIVE_EFFECT_NOTE =
      "否定生效日 = applied_at 的 UTC 日期（平台回执生效日）；"
          + "applied_at 为空时回退到 evidence_to（依据区间末日，即「从这天起应当不再有数据」）；"
          + "两者都空 ⇒ 判定为「无法评估」（NOT_EVALUABLE），**不是**「否定后没数据」。"
          + "否定生效日之后的曝光/点击**应当**归零（否定已生效）；"
          + "若数据源仍在报，判定为「否定后仍有数据」（STILL_REPORTING），"
          + "**不把它当成 0** —— 「没有数据」（可能只是平台停报）与「有数据但都是 0」是两件事。"
          + "否定前窗口 = [起始日, min(截止日, 生效日)]；否定后窗口 = [max(起始日, 生效日+1天), 截止日]。"
          + "否定后的表现以**搜索词报告**（ad_search_term）为准 —— 否定的对象是「用户搜出来的词」；"
          + "被否定的**投放词自身**是否还在跑，另由 ad_keyword_metric 判定，"
          + "两者任一仍有花费或点击即判为「否定后仍有数据」。"
          + "🔴 这套口径是本模块定的，规则变了历史报表不可比 —— 复盘时请带上这条说明。";

  /** 数据截至时刻的口径说明。 */
  static final String DATA_AS_OF_NOTE =
      "「数据截至 X 时间」= 该维度**涉及的事实表**里最大的 synced_at"
          + "（组维度是 ad_campaign_group.data_as_of；否定维度还包含否定记录自身的更新时间）。"
          + "水位之前的空白是「还没同步到」，不是「没投放」。"
          + "失效令牌 = md5(最大 synced_at + 最大 updated_at)："
          + "补录的历史数据 synced_at 可能早于已有水位，只看 synced_at 会漏掉它。";

  /** 缓存口径说明。 */
  static final String CACHE_NOTE =
      "关键词级数据量极大 ⇒ 报表按查询指纹缓存。三条失效依据同时生效："
          + "① 水位（data_as_of）变了；② 失效令牌（source_watermark）变了；"
          + "③ 过了 expires_at 这一刻。命中与否、失效依据、过期时刻**一起返回**，"
          + "不会出现「缓存了但不知道什么时候过期」。"
          + "缓存按数据范围指纹隔离：范围窄的成员算出来的缓存，范围宽的成员看不到。";

  // ==========================================================================
  // 排序白名单（排序串绝不拼进 SQL）
  // ==========================================================================

  private static final Map<String, String> GROUP_SORTS =
      Map.of(
          "NAME_ASC", "order by dimension_label asc, dimension_key asc",
          "STATUS_ASC", "order by g.status asc, dimension_label asc, dimension_key asc",
          "PRODUCTS_DESC", "order by product_count desc, dimension_label asc, dimension_key asc",
          "BID_DESC",
              "order by g.default_bid desc nulls last, dimension_label asc, dimension_key asc");

  private static final Map<String, String> KEYWORD_SORTS =
      Map.of(
          "SPEND_DESC", "order by sum(mt.spend) desc, mt.currency asc, k.id asc",
          "SPEND_ASC", "order by sum(mt.spend) asc, mt.currency asc, k.id asc",
          "SALES_DESC", "order by sum(mt.sales) desc, mt.currency asc, k.id asc",
          "ACOS_ASC",
              "order by (case when sum(mt.sales) > 0 then sum(mt.spend)/sum(mt.sales) end)"
                  + " asc nulls last, mt.currency asc, k.id asc",
          "ROAS_DESC",
              "order by (case when sum(mt.spend) > 0 then sum(mt.sales)/sum(mt.spend) end)"
                  + " desc nulls last, mt.currency asc, k.id asc",
          "IMPRESSIONS_DESC", "order by sum(mt.impressions) desc, mt.currency asc, k.id asc",
          "CLICKS_DESC", "order by sum(mt.clicks) desc, mt.currency asc, k.id asc",
          "CONVERSIONS_DESC", "order by sum(mt.conversions) desc, mt.currency asc, k.id asc",
          "WASTE_FIRST",
              "order by (case when sum(mt.impressions) > 0 and sum(mt.conversions) = 0"
                  + " then 0 else 1 end) asc, sum(mt.spend) desc, k.id asc",
          "LABEL_ASC", "order by k.keyword asc, k.id asc");

  private static final Map<String, String> SEARCH_TERM_SORTS =
      Map.of(
          "SPEND_DESC", "order by sum(st.spend) desc, st.currency asc, st.search_term asc",
          "SPEND_ASC", "order by sum(st.spend) asc, st.currency asc, st.search_term asc",
          "SALES_DESC", "order by sum(st.sales) desc, st.currency asc, st.search_term asc",
          "ACOS_ASC",
              "order by (case when sum(st.sales) > 0 then sum(st.spend)/sum(st.sales) end)"
                  + " asc nulls last, st.currency asc, st.search_term asc",
          "ROAS_DESC",
              "order by (case when sum(st.spend) > 0 then sum(st.sales)/sum(st.spend) end)"
                  + " desc nulls last, st.currency asc, st.search_term asc",
          "IMPRESSIONS_DESC", "order by sum(st.impressions) desc, st.currency asc, st.search_term asc",
          "CLICKS_DESC", "order by sum(st.clicks) desc, st.currency asc, st.search_term asc",
          "CONVERSIONS_DESC", "order by sum(st.conversions) desc, st.currency asc, st.search_term asc",
          "WASTE_FIRST",
              "order by (case when sum(st.impressions) > 0 and sum(st.conversions) = 0"
                  + " then 0 else 1 end) asc, sum(st.spend) desc, st.search_term asc",
          "LABEL_ASC", "order by st.search_term asc");

  private static final Map<String, String> NEGATIVE_SORTS =
      Map.of(
          "RECENT_FIRST", "order by n.applied_at desc nulls last, n.created_at desc, n.id asc",
          "STILL_REPORTING_FIRST",
              "order by (case when coalesce(neg_post.spend,0) > 0"
                  + " or coalesce(neg_post.clicks,0) > 0"
                  + " or coalesce(neg_kwpost.spend,0) > 0"
                  + " or coalesce(neg_kwpost.clicks,0) > 0 then 0 else 1 end) asc,"
                  + " n.created_at desc, n.id asc",
          "SPEND_DESC", "order by coalesce(neg_pre.spend,0) desc, n.created_at desc, n.id asc",
          "TEXT_ASC", "order by n.negation_text asc, n.id asc");

  private static Map<String, String> sortsOf(String dimension) {
    return switch (dimension) {
      case "GROUP" -> GROUP_SORTS;
      case "SEARCH_TERM" -> SEARCH_TERM_SORTS;
      case "NEGATIVE_EFFECT" -> NEGATIVE_SORTS;
      default -> KEYWORD_SORTS;
    };
  }

  /**
   * 🔴 每个维度的**默认**排序。
   *
   * <p>排序白名单是**按维度**分的（组按名称、否定按生效时间、词按花费），
   * 所以「默认排序」也必须按维度走 —— 否则「不传 sort」会被判成非法值。
   */
  private static final Map<String, String> DEFAULT_SORTS =
      Map.of(
          "GROUP", "NAME_ASC",
          "KEYWORD", "SPEND_DESC",
          "SEARCH_TERM", "SPEND_DESC",
          "NEGATIVE_EFFECT", "RECENT_FIRST");

  private final DSLContext sql;
  private final ObjectMapper json;
  private final AccessService access;

  public AdGroupKeywordReportService(DSLContext sql, ObjectMapper json, AccessService access) {
    this.sql = sql;
    this.json = json;
    this.access = access;
  }

  // ==========================================================================
  // 小工具
  // ==========================================================================

  private <T> T one(Class<T> type, String query, Object... binds) {
    Record row = sql.fetchOne(query, binds);
    return row == null ? null : row.get(0, type);
  }

  private long count(String query, Object... binds) {
    Long v = one(Long.class, query, binds);
    return v == null ? 0L : v;
  }

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static String blankToNull(String value) {
    String trimmed = text(value);
    return trimmed.isEmpty() ? null : trimmed;
  }

  /** 当前事务内的租户（由 backend/libraries/tenancy 绑定，不来自任何客户端头）。 */
  private UUID currentTenant() {
    String raw = one(String.class, "select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(raw);
  }

  /** 当前操作者（已认证成员）。 */
  private UUID currentActor() {
    String raw = one(String.class, "select nullif(current_setting('app.actor',true),'')");
    return raw == null || raw.isBlank() ? null : UUID.fromString(raw);
  }

  /**
   * 🔴 当前调用者的**数据范围指纹**（app.grants + app.department + app.permission 的 md5）。
   *
   * <p>缓存键与缓存表的 RLS 策略都用它：范围窄的成员算出来的缓存，范围宽的成员看不到 ——
   * 报表是「谁能看到哪些店铺的投放表现」，缓存不能绕过它。
   */
  private String scopeFingerprint() {
    String raw =
        one(
            String.class,
            "select md5(coalesce(current_setting('app.grants',true),'')"
                + " || '|' || coalesce(current_setting('app.department',true),'')"
                + " || '|' || coalesce(current_setting('app.permission',true),''))");
    return raw == null ? "" : raw;
  }

  // ==========================================================================
  // 报表查询条件
  // ==========================================================================

  /**
   * 一次报表查询的全部条件。
   *
   * <p>🔴 {@code from} / {@code to} **必填**：没有时间范围的报表就是全表扫，
   * 而蓝图加粗句点名「不能实时全表扫」。不给区间就报错，不给默认区间。
   *
   * <p>🔴 {@code selectedKeys} 是「批量选中导出」的那把钥匙：它进 <b>SQL 的 WHERE</b>，
   * 不是在服务层筛 —— 在服务层筛的话，只能筛到**第一页**，
   * 用户选了第 3 页上的词，导出里就会没有它。
   */
  public record Query(
      String dimension,
      String granularity,
      String sort,
      LocalDate from,
      LocalDate to,
      UUID platformId,
      String shopCode,
      String internalAdType,
      String status,
      String externalCampaignId,
      String keywordText,
      List<String> selectedKeys,
      int page,
      int size) {}

  /** 🔴 批量选中一次最多多少条（防止一个巨大的 IN 子句把数据库拖垮）。 */
  static final int MAX_SELECTED_KEYS = 500;

  /** 归一化并校验查询条件（非法值直接 400，不静默回落到默认值）。 */
  Query normalize(
      String dimension,
      String granularity,
      String sort,
      LocalDate from,
      LocalDate to,
      UUID platformId,
      String shopCode,
      String internalAdType,
      String status,
      String externalCampaignId,
      String keywordText,
      List<String> selectedKeys,
      int page,
      int size) {
    if (from == null || to == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "报表必须给出统计起始日与截止日（from 与 to 都是必填）");
    }
    if (from.isAfter(to)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "统计起始日不能晚于截止日");
    }
    String dim = text(dimension).toUpperCase(Locale.ROOT);
    if (dim.isEmpty()) dim = "KEYWORD";
    if (!DIMENSIONS.contains(dim)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "报表维度只能是 GROUP / KEYWORD / SEARCH_TERM / NEGATIVE_EFFECT");
    }
    String gran = text(granularity).toUpperCase(Locale.ROOT);
    if (gran.isEmpty()) gran = DEFAULT_GRANULARITY;
    if (!GRANULARITIES.contains(gran)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "时间粒度只能是 TOTAL / DAY / WEEK / MONTH");
    }
    Map<String, String> sorts = sortsOf(dim);
    String sortKey = text(sort).toUpperCase(Locale.ROOT);
    if (sortKey.isEmpty()) sortKey = DEFAULT_SORTS.get(dim);
    if (!sorts.containsKey(sortKey)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "排序方式不在该维度的白名单里（" + dim + " 可用：" + sorts.keySet() + "）");
    }
    // 🔴 时间跨度封顶：跨度太大直接拒，不悄悄截断（悄悄截断 = 报表少算了一段却没人知道）。
    long days = ChronoUnit.DAYS.between(from, to) + 1;
    if (days > MAX_DAYS) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "时间跨度过大：最多 " + MAX_DAYS + " 天，当前 " + days + " 天");
    }
    List<String> selection = selectedKeys == null ? List.of() : selectedKeys.stream()
        .filter(k -> k != null && !k.isBlank())
        .map(String::trim)
        .distinct()
        .toList();
    if (selection.size() > MAX_SELECTED_KEYS) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "一次最多选中 " + MAX_SELECTED_KEYS + " 条，当前 " + selection.size() + " 条");
    }
    return new Query(
        dim,
        gran,
        sortKey,
        from,
        to,
        platformId,
        blankToNull(shopCode),
        blankToNull(internalAdType),
        blankToNull(status),
        blankToNull(externalCampaignId),
        blankToNull(keywordText),
        selection,
        Math.max(page, 0),
        Math.min(Math.max(size, 1), 200));
  }

  // ==========================================================================
  // 能力声明
  // ==========================================================================

  public AdGroupKeywordReportCapabilities capabilities() {
    return new AdGroupKeywordReportCapabilities(
        List.of(
            new AdGroupReportCapabilityDimension(
                "group_report",
                "广告组维度报表（组名 / 状态 / 默认竞价 / 投放商品数 / 所属活动）",
                true,
                "ad_campaign_group + ad_campaign_group_product（V277，上游 14.1.1.2）",
                "",
                "没有它就没有「这个组在投什么、投在哪个活动、投了多少商品」这条事实"),
            new AdGroupReportCapabilityDimension(
                "group_metrics",
                "🔴 广告组维度的**指标**（展示 / 点击 / 转化 / 花费 / 销售额）",
                false,
                "—",
                "14.1.2.1 广告组管理",
                "现有事实表里没有组级指标表，而且 ad_keyword / ad_search_term 根本没有组的引用列，"
                    + "词指标在物理上无法归到组 ⇒ 组维度的指标列一律为空。"
                    + "不用活动级指标冒充组级指标（那是假事实），也不建一张空表冒充「没有数据」。"
                    + "责任方：14.1.2.1 补上组引用或另建组级指标事实表并给真实写入口。"),
            new AdGroupReportCapabilityDimension(
                "keyword_report",
                "单词效果报表（展示 / 点击 / 转化 / CPC / ACOS / ROAS）",
                true,
                "ad_keyword + ad_keyword_metric（V274，上游 14.1.3.1）",
                "",
                "没有它就没有「哪个词在烧钱」的答案，14.2.1.1 竞价管理也没有落点"),
            new AdGroupReportCapabilityDimension(
                "search_term_report",
                "搜索词质量报表（用户实际搜出来的词：高曝光零转化 = 浪费）",
                true,
                "ad_search_term（V274，上游 14.1.3.1）",
                "",
                "没有它就找不到浪费入口 —— 高曝光零转化的搜索词会一直烧预算"),
            new AdGroupReportCapabilityDimension(
                "negative_effect_report",
                "否定效果评估（否定生效后是否还有数据 / 还在花钱）",
                true,
                "ad_negative_keyword + ad_search_term + ad_keyword_metric（V274）",
                "",
                "没有它就没有「否定到底生效没有」的证据，14.1.3.1 的否定决策只能靠猜"),
            new AdGroupReportCapabilityDimension(
                "detail_and_summary",
                "明细报表 + 汇总（汇总单独一条聚合查询，不受分页影响）",
                true,
                "本服务层的聚合 SQL",
                "",
                "只有明细没有汇总，运营就得自己把几十行加起来 —— 加法口径一定会错"),
            new AdGroupReportCapabilityDimension(
                "batch_export",
                "批量选中导出（选中哪几条也留痕）",
                true,
                "ad_group_keyword_report_export（V283）",
                "",
                "运营要拿报表去开会时只能截图，数字与口径都对不上"),
            new AdGroupReportCapabilityDimension(
                "report_cache",
                "报表结果缓存（带水位 / 批次 / 失效令牌 / 过期时刻四重依据）",
                true,
                "ad_group_keyword_report_cache（V283）",
                "",
                "没有缓存，关键词级的量级会把数据库拖垮（蓝图点名「不能实时全表扫」）"),
            new AdGroupReportCapabilityDimension(
                "multi_currency_unification",
                "跨币种统一口径（把不同币种换算成同一货币再汇总）",
                false,
                "—",
                "汇率源（未交付：本仓没有汇率表，也没有汇率同步器）",
                "报表按币种拆开：不同币种的花费 / 销售额不会被加成一个假总数，"
                    + "代价是跨币种的横向比较暂时做不了"),
            new AdGroupReportCapabilityDimension(
                "attribution",
                "广告归因（把花费归到订单 / SKU，算广告后净利）",
                false,
                "—",
                "14.3.2.1 归因分析",
                "本模块只到组 / 词 / 搜索词维度，不做归因"),
            new AdGroupReportCapabilityDimension(
                "group_level_sync",
                "广告组指标的平台侧定时同步",
                false,
                "—",
                "14.1.2.1 广告组管理（平台侧结构同步）",
                "组结构靠上游同步进来；组级指标事实源缺失这件事本身就是它的责任，"
                    + "本模块把影响写在能力声明里，不假装已交付")),
        METRIC_DEFINITIONS,
        DIMENSIONS,
        GRANULARITIES,
        sortOptionsByDimension(),
        List.of("NO_SYNCED_DATA", "NO_METRICS_IN_RANGE", "FILTERED_EMPTY"),
        NEGATIVE_EFFECT_NOTE,
        METRIC_DEFINITION_NOTE,
        DATA_AS_OF_NOTE,
        CACHE_NOTE,
        "上游 = 14.1.3.1 关键词管理（已交付）：事实表 ad_keyword_metric / ad_search_term / "
            + "ad_negative_keyword（V274）；"
            + "上游 = 14.1.1.2 活动详情（已交付）：ad_campaign_group / ad_campaign_group_product（V277）；"
            + "上游 = 14.1.1.1 活动列表（已交付）：ad_campaign（V273）；"
            + "平台目录（2.1.1.1）与店铺（2.3.1.1）同库只读引用。",
        "下游 = 14.1.3.1 否定决策（本模块给出「否定后仍有数据」的证据，决策仍在它那里）。"
            + "🔴 本模块**不做**跨币种换算（没有汇率源）、**不出**组级指标（没有事实源）、"
            + "**不做**归因（归 14.3.2.1）—— 这三件事都在能力声明里显式标 available=false，"
            + "不用假数字糊过去。");
  }

  // ==========================================================================
  // 选项
  // ==========================================================================

  /** 🔴 每个维度各自的排序白名单（维度不同，可排的列也不同）。 */
  private static Map<String, List<String>> sortOptionsByDimension() {
    Map<String, List<String>> out = new LinkedHashMap<>();
    for (String dimension : DIMENSIONS) {
      out.put(dimension, List.copyOf(sortsOf(dimension).keySet()));
    }
    return out;
  }

  /** 排序方式的显示名。 */
  private static String sortLabel(String sort) {
    return switch (sort) {
      case "SPEND_DESC" -> "花费高到低";
      case "SPEND_ASC" -> "花费低到高";
      case "SALES_DESC" -> "销售额高到低";
      case "ACOS_ASC" -> "ACOS 低到高（不可比的排最后）";
      case "ROAS_DESC" -> "ROAS 高到低（不可比的排最后）";
      case "IMPRESSIONS_DESC" -> "曝光高到低";
      case "CLICKS_DESC" -> "点击高到低";
      case "CONVERSIONS_DESC" -> "转化高到低";
      case "WASTE_FIRST" -> "高曝光零转化优先（浪费的入口）";
      case "LABEL_ASC" -> "名称";
      case "NAME_ASC" -> "名称";
      case "STATUS_ASC" -> "状态";
      case "PRODUCTS_DESC" -> "投放商品数多到少";
      case "BID_DESC" -> "默认竞价高到低";
      case "RECENT_FIRST" -> "最近生效的在前";
      case "STILL_REPORTING_FIRST" -> "否定后仍有数据的优先";
      case "TEXT_ASC" -> "词原文";
      default -> sort;
    };
  }

  public AdGroupKeywordReportOptions options() {
    List<AdGroupKeywordReportOption> platforms =
        sql.fetch("select id, code, name from platform order by position, code")
            .stream()
            .map(r -> new AdGroupKeywordReportOption(r.get("code", String.class), r.get("name", String.class)))
            .toList();

    List<AdGroupKeywordReportOption> shops =
        sql.fetch("select code, name from shop where tenant_id = " + TENANT + " order by code")
            .stream()
            .map(r -> new AdGroupKeywordReportOption(r.get("code", String.class), r.get("name", String.class)))
            .toList();

    List<AdGroupKeywordReportOption> dimensions =
        List.of(
            new AdGroupKeywordReportOption("GROUP", "按广告组（结构事实，组级指标未接入）"),
            new AdGroupKeywordReportOption("KEYWORD", "按关键词（单词效果）"),
            new AdGroupKeywordReportOption("SEARCH_TERM", "按搜索词（搜索词质量）"),
            new AdGroupKeywordReportOption("NEGATIVE_EFFECT", "按否定记录（否定效果评估）"));

    List<AdGroupKeywordReportOption> granularities =
        List.of(
            new AdGroupKeywordReportOption("TOTAL", "整个区间一行"),
            new AdGroupKeywordReportOption("DAY", "按日"),
            new AdGroupKeywordReportOption("WEEK", "按周（周一为一周之始）"),
            new AdGroupKeywordReportOption("MONTH", "按月"));

    List<AdGroupKeywordReportOption> types =
        List.of(
                "AUTO_DISCOVERY",
                "SPONSORED_BRAND",
                "SPONSORED_DISPLAY",
                "BRAND_AD",
                "DSP_PROGRAMMATIC",
                "PROMOTION_EVENT",
                "UNMAPPED")
            .stream()
            .map(t -> new AdGroupKeywordReportOption(t, AdCampaignService.internalTypeLabel(t)))
            .toList();

    // 🔴 状态筛选的含义随维度而异：组状态 / 词状态 / 否定状态。列在这里，不在别处。
    List<AdGroupKeywordReportOption> statuses =
        List.of(
                "ENABLED", "PAUSED", "ENDED", "ARCHIVED", "PENDING_REVIEW", "UNKNOWN", "ACTIVE",
                "DECIDED", "PUSHED", "REJECTED")
            .stream()
            .map(s -> new AdGroupKeywordReportOption(s, s))
            .toList();

    Map<String, List<AdGroupKeywordReportOption>> sortsByDimension = new LinkedHashMap<>();
    for (String dimension : DIMENSIONS) {
      sortsByDimension.put(
          dimension,
          sortsOf(dimension).keySet().stream()
              .map(s -> new AdGroupKeywordReportOption(s, sortLabel(s)))
              .toList());
    }

    return new AdGroupKeywordReportOptions(
        dimensions,
        granularities,
        platforms,
        shops,
        types,
        statuses,
        sortsByDimension,
        METRIC_DEFINITIONS,
        METRIC_DEFINITION_NOTE,
        NEGATIVE_EFFECT_NOTE,
        "时间范围必填（from / to 都是必填项），跨度最多 " + MAX_DAYS + " 天，超过直接报错，不做悄悄截断。"
            + "每页最多 200 行。",
        CACHE_NOTE,
        "状态下拉项的含义随维度而异：GROUP = 广告组状态、KEYWORD = 词状态、"
            + "SEARCH_TERM = 不适用、NEGATIVE_EFFECT = 否定状态。");
  }

  // ==========================================================================
  // 报表主体
  // ==========================================================================

  /** 报表的内部结果（行 + 总条数 + 汇总）。 */
  private record ReportData(
      List<AdGroupKeywordReportRow> items,
      long total,
      List<AdGroupKeywordReportSummary> summaries) {}

  /** 缓存载荷（存进 jsonb 的东西）。 */
  private record ReportPayload(
      List<AdGroupKeywordReportRow> items,
      long total,
      List<AdGroupKeywordReportSummary> summaries) {}

  /**
   * 报表入口：真分页 + 真筛选 + 真排序 + 汇总 + 口径说明 + 数据截至时刻 + 缓存。
   *
   * <p>🔴 缓存的判定顺序：**先看水位与失效令牌，再看过期时刻**。
   * 三条依据里任何一条不满足，就重算并把新的缓存写回去 ——
   * 「缓存了但不知道什么时候过期」是最不可接受的一种结果。
   */
  public AdGroupKeywordReportPage report(Query query) {
    Watermark watermark = watermark(query.dimension());
    String scope = scopeFingerprint();
    String cacheKey = cacheKey(query, scope);

    // ---- 先试缓存 ----
    Record cached =
        sql.fetchOne(
            "select payload, data_as_of, source_batch_key, source_watermark, expires_at"
                + " from ad_group_keyword_report_cache where tenant_id = " + TENANT
                + " and cache_key = ?",
            cacheKey);

    if (cached != null && watermark.usable()) {
      OffsetDateTime cachedAsOf = cached.get("data_as_of", OffsetDateTime.class);
      String cachedToken = cached.get("source_watermark", String.class);
      OffsetDateTime expiresAt = cached.get("expires_at", OffsetDateTime.class);
      boolean fresh =
          cachedAsOf != null
              && cachedAsOf.equals(watermark.dataAsOf())
              && cachedToken != null
              && cachedToken.equals(watermark.token())
              && expiresAt != null
              && expiresAt.isAfter(OffsetDateTime.now());
      if (fresh) {
        ReportPayload payload = readPayload(cached.get("payload", String.class));
        if (payload != null) {
          return page(
              query,
              payload.items(),
              payload.total(),
              payload.summaries(),
              watermark,
              new AdGroupKeywordReportCacheInfo(
                  "HIT",
                  cacheKey,
                  cachedAsOf,
                  cached.get("source_batch_key", String.class),
                  cachedToken,
                  expiresAt,
                  CACHE_NOTE));
        }
      }
    }

    // ---- 重算 ----
    ReportData data =
        switch (query.dimension()) {
          case "GROUP" -> groupReport(query);
          case "SEARCH_TERM" -> searchTermReport(query);
          case "NEGATIVE_EFFECT" -> negativeEffectReport(query);
          default -> keywordReport(query);
        };

    String cacheStatus = cached == null ? "MISS" : "STALE";
    OffsetDateTime expiresAt = OffsetDateTime.now().plusSeconds(CACHE_TTL_SECONDS);
    if (!watermark.usable()) {
      // 🔴 没有水位 ⇒ 没有失效依据 ⇒ **不缓存**。返回一个「不知道什么时候过期」的缓存
      //   比不缓存更糟，所以这里显式 BYPASS，并把原因写在 note 里。
      cacheStatus = "BYPASS";
      expiresAt = null;
    } else {
      writeCache(cacheKey, query, data, watermark, expiresAt, scope);
    }

    return page(
        query,
        data.items(),
        data.total(),
        data.summaries(),
        watermark,
        new AdGroupKeywordReportCacheInfo(
            cacheStatus,
            cacheKey,
            watermark.dataAsOf(),
            watermark.batchKey(),
            watermark.token(),
            expiresAt,
            watermark.usable() ? CACHE_NOTE : "本租户在这个维度上还没有任何数据（水位为空），"
                + "没有失效依据所以不缓存 —— 宁可每次现算，也不返回一个「不知道什么时候过期」的结果。"));
  }

  private AdGroupKeywordReportPage page(
      Query query,
      List<AdGroupKeywordReportRow> items,
      long total,
      List<AdGroupKeywordReportSummary> summaries,
      Watermark watermark,
      AdGroupKeywordReportCacheInfo cache) {
    String emptyReason = null;
    String emptyNote = "";
    if (total == 0) {
      EmptyReason reason = resolveEmptyReason(query, watermark);
      emptyReason = reason.reason();
      emptyNote = reason.note();
    }
    return new AdGroupKeywordReportPage(
        items,
        total,
        query.page(),
        query.size(),
        query.dimension(),
        query.granularity(),
        summaries,
        watermark.dataAsOf(),
        DATA_AS_OF_NOTE,
        METRIC_DEFINITIONS,
        METRIC_DEFINITION_NOTE,
        NEGATIVE_EFFECT_NOTE,
        emptyReason,
        emptyNote,
        cache,
        dimensionNote(query.dimension()));
  }

  /** 维度补充说明（写进响应的 note，界面直接显示）。 */
  private static String dimensionNote(String dimension) {
    return switch (dimension) {
      case "GROUP" ->
          "组维度给的是**结构事实**（组名 / 状态 / 默认竞价 / 投放商品数 / 所属活动）。"
              + "组级**指标**没有事实源（现有事实表里没有组级指标表，且词表根本没有组的引用列），"
              + "所以指标列一律为空 —— 见能力声明 group_metrics。"
              + "组结构是**当前状态**，没有按日快照，所以时间粒度对它不生效（时间范围只影响其它三个维度）。";
      case "SEARCH_TERM" ->
          "搜索词是**用户实际搜出来的词**，与「我们投了哪个词」是两件事。"
              + "「高曝光零转化」标记是浪费的入口（否定决策归 14.1.3.1）。";
      case "NEGATIVE_EFFECT" ->
          "否定效果评估的口径见 negativeEffectNote。"
              + "「否定后仍有数据」是**识别出来的事实**，不是 0。"
              + "评估按**否定生效日**切分否定前 / 否定后两个窗口，不按时间桶，"
              + "所以时间粒度对它不生效。";
      default ->
          "单词效果的口径见 metricDefinitionNote。"
              + "同一 (keyword_id, bucket_date) 只有一行 ⇒ 区间求和不会重复计数，"
              + "有数据的天数见 activeDays。";
    };
  }

  /** 空态原因（三种「空」必须分开说）。 */
  private record EmptyReason(String reason, String note) {}

  private EmptyReason resolveEmptyReason(Query query, Watermark watermark) {
    if (!watermark.usable()) {
      return new EmptyReason(
          "NO_SYNCED_DATA",
          "本租户在这个维度上还没有任何数据 —— 这不等于「这段时间没有投放」，只是还没同步到数据。");
    }
    if ("GROUP".equals(query.dimension())) {
      long groups = count("select count(*) from ad_campaign_group where tenant_id = " + TENANT);
      if (groups > 0) {
        return new EmptyReason(
            "FILTERED_EMPTY", "本租户确实有广告组，但被当前的筛选条件全部排除了。放宽条件再试。");
      }
      return new EmptyReason(
          "NO_SYNCED_DATA", "本租户还没有任何广告组结构数据（上游 14.1.1.2 还没同步进来）。");
    }
    if ("NEGATIVE_EFFECT".equals(query.dimension())) {
      long negatives = count("select count(*) from ad_negative_keyword where tenant_id = " + TENANT);
      if (negatives > 0) {
        return new EmptyReason(
            "FILTERED_EMPTY", "本租户确实有否定记录，但被当前的筛选条件全部排除了。放宽条件再试。");
      }
      return new EmptyReason("NO_SYNCED_DATA", "本租户还没有任何否定记录。");
    }
    long inRange = metricsInRange(query.dimension(), query.from(), query.to());
    if (inRange > 0) {
      return new EmptyReason(
          "FILTERED_EMPTY", "这个时间区间里确实有投放数据，但被当前的筛选条件全部排除了。放宽条件再试。");
    }
    return new EmptyReason(
        "NO_METRICS_IN_RANGE", "数据同步过，但所选时间区间里没有任何投放指标 —— 这次是真的「这段时间没有投放数据」。");
  }

  // ==========================================================================
  // 水位与缓存
  // ==========================================================================

  /**
   * 🔴 维度的水位（数据截至时刻）+ 失效令牌 + 批次键。
   *
   * @param dataAsOf 数据截至时刻（最大的 synced_at / data_as_of）
   * @param token 失效令牌 = md5(最大 synced_at + 最大 updated_at)
   * @param batchKey 水位那一行来自哪一次同步批次
   */
  private record Watermark(OffsetDateTime dataAsOf, String token, String batchKey) {
    boolean usable() {
      return dataAsOf != null && token != null;
    }
  }

  private Watermark watermark(String dimension) {
    if ("NEGATIVE_EFFECT".equals(dimension)) {
      return negativeWatermark();
    }
    String table;
    String asOfColumn;
    switch (dimension) {
      case "GROUP" -> {
        table = "ad_campaign_group";
        asOfColumn = "data_as_of";
      }
      case "SEARCH_TERM" -> {
        table = "ad_search_term";
        asOfColumn = "synced_at";
      }
      default -> {
        table = "ad_keyword_metric";
        asOfColumn = "synced_at";
      }
    }
    OffsetDateTime asOf =
        one(
            OffsetDateTime.class,
            "select max(" + asOfColumn + ") from " + table + " where tenant_id = " + TENANT);
    OffsetDateTime updated =
        one(
            OffsetDateTime.class,
            "select max(updated_at) from " + table + " where tenant_id = " + TENANT);
    // 🔴 广告组结构没有 batch_key（它不是一次指标同步的产物）⇒ 批次键留空。
    String batchKey = "";
    if (!"GROUP".equals(dimension)) {
      batchKey =
          one(
              String.class,
              "select batch_key from " + table + " where tenant_id = " + TENANT
                  + " order by " + asOfColumn + " desc limit 1");
    }
    if (asOf == null && updated == null) {
      return new Watermark(null, null, batchKey == null ? "" : batchKey);
    }
    return new Watermark(asOf, token(asOf, updated), batchKey == null ? "" : batchKey);
  }

  /**
   * 否定维度的水位跨三张表（否定记录 + 搜索词 + 词指标）。
   *
   * <p>🔴 否定记录表**没有 batch_key**（它不是一次指标同步的产物），
   * 所以批次键从搜索词报告那一侧取 —— 那是真正带数据的一边。
   */
  private Watermark negativeWatermark() {
    OffsetDateTime asOf =
        one(
            OffsetDateTime.class,
            "select max(updated_at) from ad_negative_keyword where tenant_id = " + TENANT);
    OffsetDateTime updated = asOf;
    for (String table : List.of("ad_search_term", "ad_keyword_metric")) {
      OffsetDateTime synced =
          one(
              OffsetDateTime.class,
              "select max(synced_at) from " + table + " where tenant_id = " + TENANT);
      if (synced != null && (asOf == null || synced.isAfter(asOf))) asOf = synced;
      OffsetDateTime touched =
          one(
              OffsetDateTime.class,
              "select max(updated_at) from " + table + " where tenant_id = " + TENANT);
      if (touched != null && (updated == null || touched.isAfter(updated))) updated = touched;
    }
    String batchKey =
        one(
            String.class,
            "select batch_key from ad_search_term where tenant_id = " + TENANT
                + " order by synced_at desc limit 1");
    if (asOf == null && updated == null) {
      return new Watermark(null, null, batchKey == null ? "" : batchKey);
    }
    return new Watermark(asOf, token(asOf, updated), batchKey == null ? "" : batchKey);
  }

  /** 失效令牌 = md5(最大 synced_at + 最大 updated_at)。 */
  private String token(OffsetDateTime asOf, OffsetDateTime updated) {
    return one(
        String.class,
        "select md5(coalesce(?::text,'') || '|' || coalesce(?::text,''))",
        asOf == null ? null : asOf.toString(),
        updated == null ? null : updated.toString());
  }

  /** 缓存键 = 维度 + 查询指纹的 md5（长度有界，跨车道不冲突）。 */
  private String cacheKey(Query query, String scope) {
    String raw =
        String.join(
            "|",
            query.dimension(),
            query.granularity(),
            query.sort(),
            String.valueOf(query.from()),
            String.valueOf(query.to()),
            String.valueOf(query.platformId()),
            String.valueOf(query.shopCode()),
            String.valueOf(query.internalAdType()),
            String.valueOf(query.status()),
            String.valueOf(query.externalCampaignId()),
            String.valueOf(query.keywordText()),
            String.valueOf(query.page()),
            String.valueOf(query.size()),
            scope);
    return query.dimension() + "|" + one(String.class, "select md5(?)", raw);
  }

  private void writeCache(
      String cacheKey,
      Query query,
      ReportData data,
      Watermark watermark,
      OffsetDateTime expiresAt,
      String scope) {
    String payload;
    try {
      payload =
          json.writeValueAsString(
              new ReportPayload(data.items(), data.total(), data.summaries()));
    } catch (Exception e) {
      // 缓存写不进去不影响报表本身 —— 返回的是**真实算出来的数**，不是缓存里的数。
      return;
    }
    sql.execute(
        "insert into ad_group_keyword_report_cache (tenant_id,id,cache_key,dimension,payload,"
            + " row_count,data_as_of,source_batch_key,source_watermark,expires_at,scope_fingerprint,"
            + " created_at) values (?,?,?,?,?::jsonb,?,?::timestamptz,?,?,?::timestamptz,?,now())"
            + " on conflict (tenant_id,cache_key) do update set payload=excluded.payload,"
            + " row_count=excluded.row_count, data_as_of=excluded.data_as_of,"
            + " source_batch_key=excluded.source_batch_key, source_watermark=excluded.source_watermark,"
            + " expires_at=excluded.expires_at, created_at=now()",
        currentTenant(),
        Ids.next(),
        cacheKey,
        query.dimension(),
        payload,
        data.items().size(),
        watermark.dataAsOf(),
        watermark.batchKey(),
        watermark.token(),
        expiresAt,
        scope);
    // 顺手清掉过期行（缓存表不能无限长）。
    sql.execute(
        "delete from ad_group_keyword_report_cache where tenant_id = " + TENANT
            + " and expires_at < now()");
  }

  private ReportPayload readPayload(String raw) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return json.readValue(raw, new TypeReference<ReportPayload>() {});
    } catch (Exception e) {
      return null;
    }
  }

  // ==========================================================================
  // 筛选条件
  // ==========================================================================

  /** 一条筛选条件（where 子句 + 绑定值）。 */
  private record Filters(String where, List<Object> binds) {}

  /** 时间桶表达式（TOTAL = 不分桶）。 */
  private static String periodExpr(String granularity, String dateColumn) {
    return switch (granularity) {
      case "DAY" -> dateColumn;
      case "WEEK" -> "date_trunc('week', " + dateColumn + ")::date";
      case "MONTH" -> "date_trunc('month', " + dateColumn + ")::date";
      default -> "null::date";
    };
  }

  // ==========================================================================
  // 单词效果
  // ==========================================================================

  private static final String KEYWORD_AGGREGATE_COLUMNS =
      """
       sum(mt.impressions) impressions,
       sum(mt.clicks) clicks,
       sum(mt.conversions) conversions,
       sum(mt.spend) spend,
       sum(mt.sales) sales,
       count(*) active_days,
       min(mt.bucket_date) metric_from,
       max(mt.bucket_date) metric_to,
       max(mt.synced_at) data_as_of,
       case when sum(mt.impressions) > 0
            then round(sum(mt.clicks)::numeric / sum(mt.impressions), 6) end ctr,
       case when sum(mt.clicks) > 0
            then round(sum(mt.spend) / sum(mt.clicks), 6) end cpc,
       case when sum(mt.clicks) > 0
            then round(sum(mt.conversions)::numeric / sum(mt.clicks), 6) end cvr,
       case when sum(mt.sales) > 0
            then round(sum(mt.spend) / sum(mt.sales), 6) end acos,
       case when sum(mt.spend) > 0
            then round(sum(mt.sales) / sum(mt.spend), 6) end roas,
       case when sum(mt.impressions) > 0 and sum(mt.conversions) = 0
            then true else false end zero_conversion_waste
      """;

  private ReportData keywordReport(Query query) {
    String period = periodExpr(query.granularity(), "mt.bucket_date");
    boolean bucketed = !"TOTAL".equals(query.granularity());
    Filters filters = keywordFilters(query);

    String groupBy =
        "k.id, k.keyword, k.external_keyword_id, k.external_campaign_id, k.internal_ad_type,"
            + " k.platform_ad_type, k.status, k.bid, pf.name, sh.code, sh.name, mt.currency"
            + (bucketed ? ", " + period : "");

    String selectSql =
        "select k.id::text dimension_key, k.keyword dimension_label, k.keyword keyword_text,"
            + " k.external_keyword_id, k.external_campaign_id, k.internal_ad_type, k.platform_ad_type,"
            + " k.status keyword_status, k.bid, pf.name platform_name, sh.code shop_code,"
            + " sh.name shop_name, mt.currency currency, "
            + (bucketed ? period + " period_start, " + period + " period_end, " : "null::date period_start, null::date period_end, ")
            + KEYWORD_AGGREGATE_COLUMNS
            + " from ad_keyword k"
            + " join platform pf on pf.id = k.platform_id"
            + " join shop sh on sh.tenant_id = k.tenant_id and sh.id = k.shop_id"
            + " join ad_keyword_metric mt on mt.tenant_id = k.tenant_id and mt.keyword_id = k.id"
            + filters.where()
            + " group by " + groupBy
            + " " + orderByWithPeriod(KEYWORD_SORTS.get(query.sort()), period, bucketed)
            + " limit ? offset ?";

    List<Object> binds = new ArrayList<>(filters.binds());
    binds.add(query.size());
    binds.add(query.page() * query.size());

    List<AdGroupKeywordReportRow> items =
        sql.fetch(selectSql, binds.toArray()).stream().map(this::keywordRow).toList();

    long total = keywordTotal(query);
    List<AdGroupKeywordReportSummary> summaries = keywordSummaries(query);
    return new ReportData(items, total, summaries);
  }

  /**
   * 🔴 分页的排序必须是<b>全序</b>：分桶时同一个词会出现多行，
   * 只按 (currency, k.id) 排序的话同一词的多行之间没有稳定次序 ——
   * 翻页时行会重复或漏掉。所以分桶时把时间桶追加到排序末尾。
   */
  private static String orderByWithPeriod(String orderBy, String period, boolean bucketed) {
    return bucketed ? orderBy + ", " + period + " asc" : orderBy;
  }

  private Filters keywordFilters(Query query) {
    StringBuilder where = new StringBuilder(" where k.tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();
    where.append(" and mt.bucket_date >= ?::date");
    binds.add(query.from());
    where.append(" and mt.bucket_date <= ?::date");
    binds.add(query.to());
    if (query.platformId() != null) {
      where.append(" and k.platform_id = ?");
      binds.add(query.platformId());
    }
    if (query.shopCode() != null) {
      where.append(" and sh.code = ?");
      binds.add(query.shopCode());
    }
    if (query.internalAdType() != null) {
      where.append(" and k.internal_ad_type = ?");
      binds.add(query.internalAdType());
    }
    if (query.status() != null) {
      where.append(" and k.status = ?");
      binds.add(query.status());
    }
    if (query.externalCampaignId() != null) {
      where.append(" and k.external_campaign_id = ?");
      binds.add(query.externalCampaignId());
    }
    if (query.keywordText() != null) {
      where.append(" and lower(k.keyword) like ('%' || lower(?) || '%')");
      binds.add(query.keywordText());
    }
    // 🔴 批量选中：进 WHERE，不在服务层筛（服务层筛只能筛到第一页）。
    if (!query.selectedKeys().isEmpty()) {
      where.append(" and k.id::text in (");
      where.append(String.join(",", java.util.Collections.nCopies(query.selectedKeys().size(), "?")));
      where.append(")");
      binds.addAll(query.selectedKeys());
    }
    return new Filters(where.toString(), binds);
  }

  private long keywordTotal(Query query) {
    String period = periodExpr(query.granularity(), "mt.bucket_date");
    boolean bucketed = !"TOTAL".equals(query.granularity());
    Filters filters = keywordFilters(query);
    return count(
        "select count(*) from (select k.id, mt.currency"
            + (bucketed ? ", " + period : "")
            + " from ad_keyword k"
            + " join shop sh on sh.tenant_id = k.tenant_id and sh.id = k.shop_id"
            + " join ad_keyword_metric mt on mt.tenant_id = k.tenant_id and mt.keyword_id = k.id"
            + filters.where()
            + " group by k.id, mt.currency"
            + (bucketed ? ", " + period : "")
            + ") t",
        filters.binds().toArray());
  }

  private List<AdGroupKeywordReportSummary> keywordSummaries(Query query) {
    Filters filters = keywordFilters(query);
    // 🔴 汇总**不按时间桶拆**：汇总是「整个筛选结果」的汇总，
    //   拆成每桶一份会让人以为「按日汇总」和「整段汇总」是同一个数。
    String sqlText =
        "select mt.currency currency, count(distinct k.id) entity_count,"
            + KEYWORD_AGGREGATE_COLUMNS
            + " from ad_keyword k"
            + " join shop sh on sh.tenant_id = k.tenant_id and sh.id = k.shop_id"
            + " join ad_keyword_metric mt on mt.tenant_id = k.tenant_id and mt.keyword_id = k.id"
            + filters.where()
            + " group by mt.currency"
            + " order by mt.currency";
    return sql.fetch(sqlText, filters.binds().toArray()).stream()
        .map(
            r ->
                new AdGroupKeywordReportSummary(
                    r.get("currency", String.class),
                    r.get("entity_count", Long.class),
                    r.get("entity_count", Long.class),
                    r.get("impressions", BigDecimal.class),
                    r.get("clicks", BigDecimal.class),
                    r.get("conversions", BigDecimal.class),
                    r.get("spend", BigDecimal.class),
                    r.get("sales", BigDecimal.class),
                    r.get("ctr", BigDecimal.class),
                    r.get("cpc", BigDecimal.class),
                    r.get("cvr", BigDecimal.class),
                    r.get("acos", BigDecimal.class),
                    r.get("roas", BigDecimal.class),
                    r.get("metric_from", LocalDate.class),
                    r.get("metric_to", LocalDate.class),
                    METRIC_DEFINITION_NOTE))
        .toList();
  }

  private AdGroupKeywordReportRow keywordRow(Record r) {
    return baseRow(r, "KEYWORD")
        .keywordText(r.get("keyword_text", String.class))
        .externalKeywordId(r.get("external_keyword_id", String.class))
        .internalAdType(r.get("internal_ad_type", String.class))
        .platformAdType(r.get("platform_ad_type", String.class))
        .keywordStatus(r.get("keyword_status", String.class))
        .bid(r.get("bid", BigDecimal.class))
        .zeroConversionWaste(r.get("zero_conversion_waste", Boolean.class))
        .build();
  }

  // ==========================================================================
  // 搜索词质量
  // ==========================================================================

  private static final String SEARCH_TERM_AGGREGATE_COLUMNS =
      """
       sum(st.impressions) impressions,
       sum(st.clicks) clicks,
       sum(st.conversions) conversions,
       sum(st.spend) spend,
       sum(st.sales) sales,
       count(*) active_days,
       min(st.bucket_date) metric_from,
       max(st.bucket_date) metric_to,
       max(st.synced_at) data_as_of,
       case when sum(st.impressions) > 0
            then round(sum(st.clicks)::numeric / sum(st.impressions), 6) end ctr,
       case when sum(st.clicks) > 0
            then round(sum(st.spend) / sum(st.clicks), 6) end cpc,
       case when sum(st.clicks) > 0
            then round(sum(st.conversions)::numeric / sum(st.clicks), 6) end cvr,
       case when sum(st.sales) > 0
            then round(sum(st.spend) / sum(st.sales), 6) end acos,
       case when sum(st.spend) > 0
            then round(sum(st.sales) / sum(st.spend), 6) end roas,
       case when sum(st.impressions) > 0 and sum(st.conversions) = 0
            then true else false end zero_conversion_waste
      """;

  private ReportData searchTermReport(Query query) {
    String period = periodExpr(query.granularity(), "st.bucket_date");
    boolean bucketed = !"TOTAL".equals(query.granularity());
    Filters filters = searchTermFilters(query);

    String groupBy =
        "st.search_term, st.currency" + (bucketed ? ", " + period : "");

    String selectSql =
        "select st.search_term dimension_key, st.search_term dimension_label,"
            + " st.search_term search_term, min(st.external_campaign_id) external_campaign_id,"
            + " count(distinct st.keyword_id) source_keyword_count,"
            + " count(distinct st.external_campaign_id) source_campaign_count,"
            + " min(k.keyword) keyword_text, st.currency currency,"
            + " min(pf.name) platform_name, min(sh.name) shop_name, "
            + (bucketed ? period + " period_start, " + period + " period_end, " : "null::date period_start, null::date period_end, ")
            + SEARCH_TERM_AGGREGATE_COLUMNS
            + " from ad_search_term st"
            + " join ad_keyword k on k.tenant_id = st.tenant_id and k.id = st.keyword_id"
            + " join platform pf on pf.id = k.platform_id"
            + " join shop sh on sh.tenant_id = k.tenant_id and sh.id = k.shop_id"
            + filters.where()
            + " group by " + groupBy
            + " " + orderByWithPeriod(SEARCH_TERM_SORTS.get(query.sort()), period, bucketed)
            + " limit ? offset ?";

    List<Object> binds = new ArrayList<>(filters.binds());
    binds.add(query.size());
    binds.add(query.page() * query.size());

    List<AdGroupKeywordReportRow> items =
        sql.fetch(selectSql, binds.toArray()).stream().map(this::searchTermRow).toList();

    long total = count(
        "select count(*) from (select st.search_term, st.currency"
            + (bucketed ? ", " + period : "")
            + " from ad_search_term st"
            + " join ad_keyword k on k.tenant_id = st.tenant_id and k.id = st.keyword_id"
            + " join platform pf on pf.id = k.platform_id"
            + " join shop sh on sh.tenant_id = k.tenant_id and sh.id = k.shop_id"
            + filters.where()
            + " group by st.search_term, st.currency"
            + (bucketed ? ", " + period : "")
            + ") t",
        filters.binds().toArray());

    // 🔴 汇总**不按时间桶拆**（与单词效果同一口径）。
    List<AdGroupKeywordReportSummary> summaries =
        sql.fetch(
                "select st.currency currency, count(distinct st.search_term) entity_count,"
                    + SEARCH_TERM_AGGREGATE_COLUMNS
                    + " from ad_search_term st"
                    + " join ad_keyword k on k.tenant_id = st.tenant_id and k.id = st.keyword_id"
                    + " join shop sh on sh.tenant_id = k.tenant_id and sh.id = k.shop_id"
                    + filters.where()
                    + " group by st.currency"
                    + " order by st.currency",
                filters.binds().toArray())
            .stream()
            .map(
                r ->
                    new AdGroupKeywordReportSummary(
                        r.get("currency", String.class),
                        r.get("entity_count", Long.class),
                        r.get("entity_count", Long.class),
                        r.get("impressions", BigDecimal.class),
                        r.get("clicks", BigDecimal.class),
                        r.get("conversions", BigDecimal.class),
                        r.get("spend", BigDecimal.class),
                        r.get("sales", BigDecimal.class),
                        r.get("ctr", BigDecimal.class),
                        r.get("cpc", BigDecimal.class),
                        r.get("cvr", BigDecimal.class),
                        r.get("acos", BigDecimal.class),
                        r.get("roas", BigDecimal.class),
                        r.get("metric_from", LocalDate.class),
                        r.get("metric_to", LocalDate.class),
                        METRIC_DEFINITION_NOTE))
            .toList();

    return new ReportData(items, total, summaries);
  }

  private Filters searchTermFilters(Query query) {
    StringBuilder where = new StringBuilder(" where st.tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();
    where.append(" and st.bucket_date >= ?::date");
    binds.add(query.from());
    where.append(" and st.bucket_date <= ?::date");
    binds.add(query.to());
    if (query.platformId() != null) {
      where.append(" and k.platform_id = ?");
      binds.add(query.platformId());
    }
    if (query.shopCode() != null) {
      where.append(" and sh.code = ?");
      binds.add(query.shopCode());
    }
    if (query.internalAdType() != null) {
      where.append(" and k.internal_ad_type = ?");
      binds.add(query.internalAdType());
    }
    if (query.status() != null) {
      where.append(" and k.status = ?");
      binds.add(query.status());
    }
    if (query.externalCampaignId() != null) {
      where.append(" and st.external_campaign_id = ?");
      binds.add(query.externalCampaignId());
    }
    if (query.keywordText() != null) {
      where.append(" and lower(st.search_term) like ('%' || lower(?) || '%')");
      binds.add(query.keywordText());
    }
    // 🔴 批量选中：进 WHERE，不在服务层筛（服务层筛只能筛到第一页）。
    if (!query.selectedKeys().isEmpty()) {
      where.append(" and st.search_term in (");
      where.append(String.join(",", java.util.Collections.nCopies(query.selectedKeys().size(), "?")));
      where.append(")");
      binds.addAll(query.selectedKeys());
    }
    return new Filters(where.toString(), binds);
  }

  private AdGroupKeywordReportRow searchTermRow(Record r) {
    return baseRow(r, "SEARCH_TERM")
        .searchTerm(r.get("search_term", String.class))
        .sourceKeywordCount(r.get("source_keyword_count", Long.class))
        .sourceCampaignCount(r.get("source_campaign_count", Long.class))
        .keywordText(r.get("keyword_text", String.class))
        .zeroConversionWaste(r.get("zero_conversion_waste", Boolean.class))
        .build();
  }

  // ==========================================================================
  // 广告组（结构事实；组级指标没有事实源）
  // ==========================================================================

  private ReportData groupReport(Query query) {
    StringBuilder where = new StringBuilder(" where g.tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();
    if (query.platformId() != null) {
      where.append(" and c.platform_id = ?");
      binds.add(query.platformId());
    }
    if (query.shopCode() != null) {
      where.append(" and sh.code = ?");
      binds.add(query.shopCode());
    }
    if (query.internalAdType() != null) {
      where.append(" and c.internal_ad_type = ?");
      binds.add(query.internalAdType());
    }
    if (query.status() != null) {
      where.append(" and g.status = ?");
      binds.add(query.status());
    }
    if (query.externalCampaignId() != null) {
      where.append(" and c.external_campaign_id = ?");
      binds.add(query.externalCampaignId());
    }
    if (query.keywordText() != null) {
      where.append(" and lower(g.group_name) like ('%' || lower(?) || '%')");
      binds.add(query.keywordText());
    }
    // 🔴 批量选中：进 WHERE，不在服务层筛（服务层筛只能筛到第一页）。
    if (!query.selectedKeys().isEmpty()) {
      where.append(" and g.id::text in (");
      where.append(String.join(",", java.util.Collections.nCopies(query.selectedKeys().size(), "?")));
      where.append(")");
      binds.addAll(query.selectedKeys());
    }

    String orderBy = GROUP_SORTS.get(query.sort());
    String selectSql =
        "select g.id::text dimension_key,"
            + " coalesce(nullif(g.group_name,''), g.external_group_id) dimension_label,"
            + " g.external_group_id, g.group_name, g.status group_status, g.default_bid,"
            + " g.bid_currency, g.daily_budget, g.targeting_type,"
            + " c.external_campaign_id, c.campaign_name, c.status campaign_status,"
            + " c.internal_ad_type, pf.name platform_name, sh.code shop_code, sh.name shop_name,"
            + " '' as currency,"
            + " (select count(*) from ad_campaign_group_product gp"
            + "   where gp.tenant_id = g.tenant_id and gp.group_id = g.id) product_count,"
            + " null::numeric impressions, null::numeric clicks, null::numeric conversions,"
            + " null::numeric spend, null::numeric sales, null::numeric ctr, null::numeric cpc,"
            + " null::numeric cvr, null::numeric acos, null::numeric roas,"
            + " null::bigint active_days, null::date period_start, null::date period_end,"
            + " null::date metric_from, null::date metric_to, g.data_as_of data_as_of"
            + " from ad_campaign_group g"
            + " join ad_campaign c on c.tenant_id = g.tenant_id and c.id = g.campaign_id"
            + " join platform pf on pf.id = c.platform_id"
            + " join shop sh on sh.tenant_id = c.tenant_id and sh.code = c.shop_code"
            + where
            + " " + orderBy
            + " limit ? offset ?";

    List<Object> listBinds = new ArrayList<>(binds);
    listBinds.add(query.size());
    listBinds.add(query.page() * query.size());

    List<AdGroupKeywordReportRow> items =
        sql.fetch(selectSql, listBinds.toArray()).stream().map(this::groupRow).toList();

    long total =
        count(
            "select count(*) from ad_campaign_group g"
                + " join ad_campaign c on c.tenant_id = g.tenant_id and c.id = g.campaign_id"
                + " join shop sh on sh.tenant_id = c.tenant_id and sh.code = c.shop_code"
                + where,
            binds.toArray());

    // 🔴 组维度是结构事实 ⇒ 汇总里**没有金额**（不是 0）。
    List<AdGroupKeywordReportSummary> summaries =
        List.of(
            new AdGroupKeywordReportSummary(
                "",
                total,
                total,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "组维度只汇总**结构事实**（组数）：组级指标没有事实源，"
                    + "所以金额与比率列一律为空 —— 不是 0，也不是漏算。"));

    return new ReportData(items, total, summaries);
  }

  private AdGroupKeywordReportRow groupRow(Record r) {
    return baseRow(r, "GROUP")
        .groupStatus(r.get("group_status", String.class))
        .externalGroupId(r.get("external_group_id", String.class))
        .campaignName(r.get("campaign_name", String.class))
        .campaignStatus(r.get("campaign_status", String.class))
        .defaultBid(r.get("default_bid", BigDecimal.class))
        .dailyBudget(r.get("daily_budget", BigDecimal.class))
        .bidCurrency(r.get("bid_currency", String.class))
        .targetingType(r.get("targeting_type", String.class))
        .productCount(r.get("product_count", Long.class))
        .note("组级指标未接入（无事实源）—— 本行只含结构事实。")
        .build();
  }

  // ==========================================================================
  // 否定效果评估
  // ==========================================================================

  /**
   * 否定效果评估的 SQL。
   *
   * <p>🔴 口径（见 {@link #NEGATIVE_EFFECT_NOTE}）：
   * 生效日 = coalesce(applied_at::date, evidence_to)，为空 ⇒ 无法评估。
   * 否定前窗口 = [from, min(to, 生效日)]；否定后窗口 = [max(from, 生效日+1天), to]。
   * 用 {@code case when ... is null then null else least/greatest ... end} 表达，
   * 因为 PostgreSQL 的 {@code least/greatest} <b>忽略 NULL</b>：
   * {@code least(to, NULL)} 会返回 to —— 那会让「无法评估」的否定记录
   * 看起来像「否定前有数据」，是错的。
   */
  private static final String NEGATIVE_SELECT_LIST =
      """
      select n.id::text dimension_key, n.negation_text dimension_label,
             n.negation_text, n.negation_type, n.status negation_status,
             n.keyword_id::text keyword_id, k.keyword keyword_text,
             n.external_campaign_id, n.evidence_from, n.evidence_to, n.applied_at,
             coalesce((n.applied_at)::date, n.evidence_to) effective_date,
             pf.name platform_name, sh.code shop_code, sh.name shop_name, '' as currency,
             neg_pre.impressions pre_impressions, neg_pre.clicks pre_clicks,
             neg_pre.conversions pre_conversions, neg_pre.spend pre_spend, neg_pre.sales pre_sales,
             neg_post.impressions post_impressions, neg_post.clicks post_clicks,
             neg_post.conversions post_conversions, neg_post.spend post_spend, neg_post.sales post_sales,
             neg_post.row_count post_row_count,
             neg_kwpost.spend kw_post_spend, neg_kwpost.clicks kw_post_clicks,
             neg_kwpost.row_count kw_post_row_count,
             null::numeric impressions, null::numeric clicks, null::numeric conversions,
             null::numeric spend, null::numeric sales, null::numeric ctr, null::numeric cpc,
             null::numeric cvr, null::numeric acos, null::numeric roas,
             null::bigint active_days, null::date period_start, null::date period_end,
             null::date metric_from, null::date metric_to, null::timestamptz data_as_of
      """;

  /** 🔴 否定报表的 FROM 段（**列表与总数共用同一段** ⇒ 两者的口径不可能漂移）。 */
  private static final String NEGATIVE_FROM =
      """
      from ad_negative_keyword n
      left join ad_keyword k on k.tenant_id = n.tenant_id and k.id = n.keyword_id
      left join platform pf on pf.id = k.platform_id
      join shop sh on sh.tenant_id = n.tenant_id and sh.id = n.shop_id
      """;

  /** 否定词匹配的搜索词口径：完全匹配 / 包含匹配。 */
  private static final String NEGATION_MATCH =
      " and (case when n.negation_type = 'NEGATIVE_EXACT'"
          + "       then lower(st.search_term) = lower(n.negation_text)"
          + "       else lower(st.search_term) like ('%' || lower(n.negation_text) || '%') end)";

  private ReportData negativeEffectReport(Query query) {
    StringBuilder where = new StringBuilder(" where n.tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();
    if (query.platformId() != null) {
      where.append(" and k.platform_id = ?");
      binds.add(query.platformId());
    }
    if (query.shopCode() != null) {
      where.append(" and sh.code = ?");
      binds.add(query.shopCode());
    }
    if (query.internalAdType() != null) {
      where.append(" and k.internal_ad_type = ?");
      binds.add(query.internalAdType());
    }
    if (query.status() != null) {
      where.append(" and n.status = ?");
      binds.add(query.status());
    }
    if (query.externalCampaignId() != null) {
      where.append(" and n.external_campaign_id = ?");
      binds.add(query.externalCampaignId());
    }
    if (query.keywordText() != null) {
      where.append(" and lower(n.negation_text) like ('%' || lower(?) || '%')");
      binds.add(query.keywordText());
    }
    // 🔴 批量选中：进 WHERE，不在服务层筛（服务层筛只能筛到第一页）。
    if (!query.selectedKeys().isEmpty()) {
      where.append(" and n.id::text in (");
      where.append(String.join(",", java.util.Collections.nCopies(query.selectedKeys().size(), "?")));
      where.append(")");
      binds.addAll(query.selectedKeys());
    }

    // ---- 三个 lateral 的绑定值（顺序必须与 SQL 里的问号顺序一致）----
    List<Object> laterals = new ArrayList<>();
    // neg_pre: from, to
    laterals.add(query.from());
    laterals.add(query.to());
    // neg_post: from, to
    laterals.add(query.from());
    laterals.add(query.to());
    // neg_kwpost: from, to
    laterals.add(query.from());
    laterals.add(query.to());

    String lateralsSql =
        """
        left join lateral (
          select sum(st.spend) spend, sum(st.clicks) clicks, sum(st.impressions) impressions,
                 sum(st.sales) sales, sum(st.conversions) conversions, count(*) row_count
            from ad_search_term st
           where st.tenant_id = n.tenant_id
             and st.bucket_date >= ?::date
             and (n.keyword_id is null or st.keyword_id = n.keyword_id)
             and (n.keyword_id is not null or st.external_campaign_id = n.external_campaign_id)
        %s
             and st.bucket_date <= case
                   when coalesce((n.applied_at)::date, n.evidence_to) is null then null
                   else least(?::date, coalesce((n.applied_at)::date, n.evidence_to)) end
        ) neg_pre on true
        left join lateral (
          select sum(st.spend) spend, sum(st.clicks) clicks, sum(st.impressions) impressions,
                 sum(st.sales) sales, sum(st.conversions) conversions, count(*) row_count
            from ad_search_term st
           where st.tenant_id = n.tenant_id
             and st.bucket_date >= case
                   when coalesce((n.applied_at)::date, n.evidence_to) is null then null
                   else greatest(?::date, coalesce((n.applied_at)::date, n.evidence_to) + 1) end
             and st.bucket_date <= ?::date
             and (n.keyword_id is null or st.keyword_id = n.keyword_id)
             and (n.keyword_id is not null or st.external_campaign_id = n.external_campaign_id)
        %s
        ) neg_post on true
        left join lateral (
          select sum(mt.spend) spend, sum(mt.clicks) clicks, count(*) row_count
            from ad_keyword_metric mt
           where mt.tenant_id = n.tenant_id
             and mt.keyword_id = n.keyword_id
             and mt.bucket_date >= case
                   when coalesce((n.applied_at)::date, n.evidence_to) is null then null
                   else greatest(?::date, coalesce((n.applied_at)::date, n.evidence_to) + 1) end
             and mt.bucket_date <= ?::date
        ) neg_kwpost on true
        """
            .formatted(NEGATION_MATCH, NEGATION_MATCH);

    // 🔴 绑定顺序：laterals 里的问号在 SQL 文本上出现在 where 之前（lateral 在 from 段），
    //   但 jOOQ 按**问号在文本里出现的顺序**绑定 ⇒ laterals 的值先，where 的值后。
    String sqlText =
        NEGATIVE_SELECT_LIST
            + NEGATIVE_FROM
            + lateralsSql
            + where
            + " " + NEGATIVE_SORTS.get(query.sort())
            + " limit ? offset ?";

    List<Object> allBinds = new ArrayList<>(laterals);
    allBinds.addAll(binds);
    allBinds.add(query.size());
    allBinds.add(query.page() * query.size());

    List<AdGroupKeywordReportRow> items =
        sql.fetch(sqlText, allBinds.toArray()).stream().map(this::negativeRow).toList();

    // 总条数：同样的 lateral 形态（只是不取列），避免「列表与总数口径不一致」。
    String countSql =
        "select count(*) from (select n.id "
            + NEGATIVE_FROM
            + lateralsSql
            + where
            + " ) t";
    long total = count(countSql, allBinds.subList(0, laterals.size() + binds.size()).toArray());

    // 汇总：否定评估的汇总是「评估结论分布」，没有金额口径。
    List<AdGroupKeywordReportSummary> summaries =
        List.of(
            new AdGroupKeywordReportSummary(
                "",
                total,
                total,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "否定效果评估的汇总是**记录条数**：口径是「否定后是否仍有数据」，"
                    + "没有金额口径（金额在每一行里按否定前 / 否定后分开列）。"));

    return new ReportData(items, total, summaries);
  }

  private AdGroupKeywordReportRow negativeRow(Record r) {
    LocalDate effective = r.get("effective_date", LocalDate.class);
    Long postRows = r.get("post_row_count", Long.class);
    Long kwPostRows = r.get("kw_post_row_count", Long.class);
    BigDecimal postSpend = r.get("post_spend", BigDecimal.class);
    BigDecimal postClicks = r.get("post_clicks", BigDecimal.class);
    BigDecimal kwPostSpend = r.get("kw_post_spend", BigDecimal.class);
    BigDecimal kwPostClicks = r.get("kw_post_clicks", BigDecimal.class);

    boolean postActive =
        (postSpend != null && postSpend.signum() > 0) || (postClicks != null && postClicks.signum() > 0);
    boolean kwPostActive =
        (kwPostSpend != null && kwPostSpend.signum() > 0)
            || (kwPostClicks != null && kwPostClicks.signum() > 0);

    String outcome;
    String note;
    Boolean stillReporting = null;
    if (effective == null) {
      outcome = "NOT_EVALUABLE";
      note =
          "没有 applied_at（平台回执）也没有 evidence_to（依据区间末日）⇒ 没有生效日，"
              + "**无法评估**。这不是「否定后没数据」。";
    } else {
      stillReporting = postActive || kwPostActive;
      if (stillReporting) {
        outcome = "STILL_REPORTING";
        note =
            "否定生效日（" + effective + "）之后**仍有数据**："
                + (postActive
                    ? "搜索词报告里还有花费/点击；"
                    : "")
                + (kwPostActive ? "被否定的投放词自身还在跑；" : "")
                + "否定没有真正生效，或数据源仍在报 —— 已标注，**不当作 0**。";
      } else if ((postRows == null || postRows == 0) && (kwPostRows == null || kwPostRows == 0)) {
        outcome = "NO_DATA_AFTER";
        note =
            "否定生效日（" + effective + "）之后**没有任何匹配数据**。"
                + "注意：这不等于「否定省下了多少」—— 也可能是数据源整体停报。";
      } else {
        outcome = "ZERO_SPEND_AFTER";
        note =
            "否定生效日（" + effective + "）之后仍有数据行，但花费与点击都是 0。";
      }
    }

    return baseRow(r, "NEGATIVE_EFFECT")
        .negationText(r.get("negation_text", String.class))
        .negationType(r.get("negation_type", String.class))
        .negationStatus(r.get("negation_status", String.class))
        .effectiveDate(effective)
        .evidenceFrom(r.get("evidence_from", LocalDate.class))
        .evidenceTo(r.get("evidence_to", LocalDate.class))
        .appliedAt(r.get("applied_at", OffsetDateTime.class))
        .evaluationOutcome(outcome)
        .evaluationNote(note)
        .preImpressions(r.get("pre_impressions", BigDecimal.class))
        .preClicks(r.get("pre_clicks", BigDecimal.class))
        .preConversions(r.get("pre_conversions", BigDecimal.class))
        .preSpend(r.get("pre_spend", BigDecimal.class))
        .preSales(r.get("pre_sales", BigDecimal.class))
        .postImpressions(r.get("post_impressions", BigDecimal.class))
        .postClicks(r.get("post_clicks", BigDecimal.class))
        .postConversions(r.get("post_conversions", BigDecimal.class))
        .postSpend(r.get("post_spend", BigDecimal.class))
        .postSales(r.get("post_sales", BigDecimal.class))
        .postStillReporting(stillReporting)
        .note(note)
        .build();
  }

  // ==========================================================================
  // 行的公共部分
  // ==========================================================================

  /** 行的公共部分用 builder 拼（字段太多，构造器顺序容易写错）。 */
  private static final class RowBuilder {
    private final String dimension;
    private String dimensionKey = "";
    private String dimensionLabel = "";
    private String currency = "";
    private String platformName;
    private String shopName;
    private String externalCampaignId;
    private BigDecimal impressions;
    private BigDecimal clicks;
    private BigDecimal conversions;
    private BigDecimal spend;
    private BigDecimal sales;
    private BigDecimal ctr;
    private BigDecimal cpc;
    private BigDecimal cvr;
    private BigDecimal acos;
    private BigDecimal roas;
    private Long activeDays;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private LocalDate metricFrom;
    private LocalDate metricTo;
    private String groupStatus;
    private String externalGroupId;
    private String campaignName;
    private String campaignStatus;
    private BigDecimal defaultBid;
    private BigDecimal dailyBudget;
    private String bidCurrency;
    private String targetingType;
    private Long productCount;
    private String keywordText;
    private String externalKeywordId;
    private String internalAdType;
    private String platformAdType;
    private String keywordStatus;
    private BigDecimal bid;
    private String searchTerm;
    private Long sourceKeywordCount;
    private Long sourceCampaignCount;
    private String negationText;
    private String negationType;
    private String negationStatus;
    private LocalDate effectiveDate;
    private LocalDate evidenceFrom;
    private LocalDate evidenceTo;
    private OffsetDateTime appliedAt;
    private String evaluationOutcome;
    private String evaluationNote;
    private BigDecimal preImpressions;
    private BigDecimal preClicks;
    private BigDecimal preConversions;
    private BigDecimal preSpend;
    private BigDecimal preSales;
    private BigDecimal postImpressions;
    private BigDecimal postClicks;
    private BigDecimal postConversions;
    private BigDecimal postSpend;
    private BigDecimal postSales;
    private Boolean postStillReporting;
    private Boolean zeroConversionWaste;
    private OffsetDateTime dataAsOf;
    private String note = "";

    RowBuilder(String dimension) {
      this.dimension = dimension;
    }

    RowBuilder dimensionKey(String v) { dimensionKey = v; return this; }
    RowBuilder dimensionLabel(String v) { dimensionLabel = v; return this; }
    RowBuilder currency(String v) { currency = v; return this; }
    RowBuilder platformName(String v) { platformName = v; return this; }
    RowBuilder shopName(String v) { shopName = v; return this; }
    RowBuilder externalCampaignId(String v) { externalCampaignId = v; return this; }
    RowBuilder impressions(BigDecimal v) { impressions = v; return this; }
    RowBuilder clicks(BigDecimal v) { clicks = v; return this; }
    RowBuilder conversions(BigDecimal v) { conversions = v; return this; }
    RowBuilder spend(BigDecimal v) { spend = v; return this; }
    RowBuilder sales(BigDecimal v) { sales = v; return this; }
    RowBuilder ctr(BigDecimal v) { ctr = v; return this; }
    RowBuilder cpc(BigDecimal v) { cpc = v; return this; }
    RowBuilder cvr(BigDecimal v) { cvr = v; return this; }
    RowBuilder acos(BigDecimal v) { acos = v; return this; }
    RowBuilder roas(BigDecimal v) { roas = v; return this; }
    RowBuilder activeDays(Long v) { activeDays = v; return this; }
    RowBuilder periodStart(LocalDate v) { periodStart = v; return this; }
    RowBuilder periodEnd(LocalDate v) { periodEnd = v; return this; }
    RowBuilder metricFrom(LocalDate v) { metricFrom = v; return this; }
    RowBuilder metricTo(LocalDate v) { metricTo = v; return this; }
    RowBuilder groupStatus(String v) { groupStatus = v; return this; }
    RowBuilder externalGroupId(String v) { externalGroupId = v; return this; }
    RowBuilder campaignName(String v) { campaignName = v; return this; }
    RowBuilder campaignStatus(String v) { campaignStatus = v; return this; }
    RowBuilder defaultBid(BigDecimal v) { defaultBid = v; return this; }
    RowBuilder dailyBudget(BigDecimal v) { dailyBudget = v; return this; }
    RowBuilder bidCurrency(String v) { bidCurrency = v; return this; }
    RowBuilder targetingType(String v) { targetingType = v; return this; }
    RowBuilder productCount(Long v) { productCount = v; return this; }
    RowBuilder keywordText(String v) { keywordText = v; return this; }
    RowBuilder externalKeywordId(String v) { externalKeywordId = v; return this; }
    RowBuilder internalAdType(String v) { internalAdType = v; return this; }
    RowBuilder platformAdType(String v) { platformAdType = v; return this; }
    RowBuilder keywordStatus(String v) { keywordStatus = v; return this; }
    RowBuilder bid(BigDecimal v) { bid = v; return this; }
    RowBuilder searchTerm(String v) { searchTerm = v; return this; }
    RowBuilder sourceKeywordCount(Long v) { sourceKeywordCount = v; return this; }
    RowBuilder sourceCampaignCount(Long v) { sourceCampaignCount = v; return this; }
    RowBuilder negationText(String v) { negationText = v; return this; }
    RowBuilder negationType(String v) { negationType = v; return this; }
    RowBuilder negationStatus(String v) { negationStatus = v; return this; }
    RowBuilder effectiveDate(LocalDate v) { effectiveDate = v; return this; }
    RowBuilder evidenceFrom(LocalDate v) { evidenceFrom = v; return this; }
    RowBuilder evidenceTo(LocalDate v) { evidenceTo = v; return this; }
    RowBuilder appliedAt(OffsetDateTime v) { appliedAt = v; return this; }
    RowBuilder evaluationOutcome(String v) { evaluationOutcome = v; return this; }
    RowBuilder evaluationNote(String v) { evaluationNote = v; return this; }
    RowBuilder preImpressions(BigDecimal v) { preImpressions = v; return this; }
    RowBuilder preClicks(BigDecimal v) { preClicks = v; return this; }
    RowBuilder preConversions(BigDecimal v) { preConversions = v; return this; }
    RowBuilder preSpend(BigDecimal v) { preSpend = v; return this; }
    RowBuilder preSales(BigDecimal v) { preSales = v; return this; }
    RowBuilder postImpressions(BigDecimal v) { postImpressions = v; return this; }
    RowBuilder postClicks(BigDecimal v) { postClicks = v; return this; }
    RowBuilder postConversions(BigDecimal v) { postConversions = v; return this; }
    RowBuilder postSpend(BigDecimal v) { postSpend = v; return this; }
    RowBuilder postSales(BigDecimal v) { postSales = v; return this; }
    RowBuilder postStillReporting(Boolean v) { postStillReporting = v; return this; }
    RowBuilder zeroConversionWaste(Boolean v) { zeroConversionWaste = v; return this; }
    RowBuilder dataAsOf(OffsetDateTime v) { dataAsOf = v; return this; }
    RowBuilder note(String v) { note = v; return this; }

    AdGroupKeywordReportRow build() {
      return new AdGroupKeywordReportRow(
          dimension, dimensionKey, dimensionLabel, currency, platformName, shopName,
          externalCampaignId, impressions, clicks, conversions, spend, sales, ctr, cpc, cvr, acos,
          roas, activeDays, periodStart, periodEnd, metricFrom, metricTo, groupStatus,
          externalGroupId, campaignName, campaignStatus, defaultBid, dailyBudget, bidCurrency,
          targetingType, productCount, keywordText, externalKeywordId, internalAdType, platformAdType,
          keywordStatus, bid, searchTerm, sourceKeywordCount, sourceCampaignCount, negationText,
          negationType, negationStatus, effectiveDate, evidenceFrom, evidenceTo, appliedAt,
          evaluationOutcome, evaluationNote, preImpressions, preClicks, preConversions, preSpend,
          preSales, postImpressions, postClicks, postConversions, postSpend, postSales,
          postStillReporting, zeroConversionWaste, dataAsOf, note);
    }
  }

  private RowBuilder baseRow(Record r, String dimension) {
    return new RowBuilder(dimension)
        .dimensionKey(r.get("dimension_key", String.class))
        .dimensionLabel(r.get("dimension_label", String.class))
        .currency(orEmpty(r.get("currency", String.class)))
        .platformName(r.get("platform_name", String.class))
        .shopName(r.get("shop_name", String.class))
        .externalCampaignId(r.get("external_campaign_id", String.class))
        .impressions(r.get("impressions", BigDecimal.class))
        .clicks(r.get("clicks", BigDecimal.class))
        .conversions(r.get("conversions", BigDecimal.class))
        .spend(r.get("spend", BigDecimal.class))
        .sales(r.get("sales", BigDecimal.class))
        .ctr(r.get("ctr", BigDecimal.class))
        .cpc(r.get("cpc", BigDecimal.class))
        .cvr(r.get("cvr", BigDecimal.class))
        .acos(r.get("acos", BigDecimal.class))
        .roas(r.get("roas", BigDecimal.class))
        .activeDays(r.get("active_days", Long.class))
        .periodStart(r.get("period_start", LocalDate.class))
        .periodEnd(r.get("period_end", LocalDate.class))
        .metricFrom(r.get("metric_from", LocalDate.class))
        .metricTo(r.get("metric_to", LocalDate.class))
        .dataAsOf(r.get("data_as_of", OffsetDateTime.class));
  }

  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }

  // ==========================================================================
  // 导出
  // ==========================================================================

  /**
   * 登记一次导出（**留痕**：谁、什么时候、什么筛选条件、选了哪几条、导出了多少行）。
   *
   * <p>🔴 留痕里存的是<b>筛选条件 + 选中集合</b>，**不存报表数据本身** ——
   * 报表随时可以按同样的条件重算，而数据快照会在下一次同步后与「当时的报表」对不上，
   * 留一份反而是假事实。
   */
  public AdGroupKeywordReportExportRow recordExport(Query query) {
    int rows = (int) exportRowCount(query);
    UUID id = Ids.next();
    OffsetDateTime watermark = watermark(query.dimension()).dataAsOf();
    List<String> selection = query.selectedKeys();
    String selectionJson;
    try {
      selectionJson = json.writeValueAsString(selection);
    } catch (Exception e) {
      selectionJson = "[]";
    }
    sql.execute(
        "insert into ad_group_keyword_report_export (tenant_id,id,requested_by,dimension,granularity,"
            + " platform_id,shop_code,internal_ad_type,status,external_campaign_id,keyword_text,"
            + " date_from,date_to,row_count,selection,data_as_of,metric_definition_note,created_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?::date,?::date,?,?::jsonb,?::timestamptz,?,now())",
        currentTenant(),
        id,
        currentActor(),
        query.dimension(),
        query.granularity(),
        query.platformId(),
        query.shopCode(),
        query.internalAdType(),
        query.status(),
        query.externalCampaignId(),
        query.keywordText(),
        query.from(),
        query.to(),
        rows,
        selectionJson,
        watermark,
        METRIC_DEFINITION_NOTE + " " + NEGATIVE_EFFECT_NOTE);
    return new AdGroupKeywordReportExportRow(
        id,
        currentActor(),
        query.dimension(),
        query.granularity(),
        query.platformId(),
        query.shopCode(),
        query.internalAdType(),
        query.status(),
        query.externalCampaignId(),
        query.keywordText(),
        query.from(),
        query.to(),
        rows,
        selection,
        watermark,
        METRIC_DEFINITION_NOTE,
        OffsetDateTime.now());
  }

  /**
   * 从导出留痕**重建**查询条件（用于按 id 重新导出 CSV）。
   *
   * <p>🔴 重建的是**筛选条件 + 选中集合**，不是报表数据快照 —— 与 14.3.1.1 同一口径：
   * 留一份数据快照会在下一次同步后与「当时的报表」对不上，那反而是假事实。
   */
  public Query queryOf(AdGroupKeywordReportExportRow row) {
    return normalize(
        row.dimension(),
        row.granularity(),
        DEFAULT_SORTS.get(row.dimension()),
        row.dateFrom(),
        row.dateTo(),
        row.platformId(),
        row.shopCode(),
        row.internalAdType(),
        row.status(),
        row.externalCampaignId(),
        row.keywordText(),
        row.selection(),
        0,
        50);
  }

  /** 导出的行数（按**分组后**的行数，不是明细行数）。 */
  private long exportRowCount(Query query) {
    return switch (query.dimension()) {
      case "GROUP" -> groupReport(query).total();
      case "SEARCH_TERM" -> searchTermReport(query).total();
      case "NEGATIVE_EFFECT" -> negativeEffectReport(query).total();
      default -> keywordReport(query).total();
    };
  }

  /**
   * 流式导出 CSV。
   *
   * <p>🔴 <b>每批在流式线程里重新开租户事务</b>：租户上下文是事务级 GUC，
   * controller 那个事务在响应开始前就结束了，而流式写发生在另一个线程上 ——
   * 那条连接上没有 {@code app.current_tenant}，FORCE RLS 会把所有行藏起来。
   *
   * <p>🔴 <b>口径写进表头</b>：列名里带着公式（{@code ACOS(Σ花费/Σ销售额)}），
   * 这样拿去做跨部门沟通时，CSV 自己就说明了「ACOS 是哪个除哪个」。
   * 不可比的比率写「不可比」（不是 0，也不是空）。
   */
  public void streamCsv(AccessService.Access actor, Query query, Consumer<String> emit) {
    int size = 200;
    int offset = 0;

    emit.accept(csvHeader(query.dimension()));

    while (true) {
      final int at = offset;
      final int take = size;
      // 🔴 批量选中已经进 SQL 的 WHERE（{@link Query#selectedKeys()}），
      //   所以这里拿到多少行就导出多少行 —— 不会出现「选了第 3 页的词，导出里没有」。
      List<AdGroupKeywordReportRow> batch =
          access.run(
              actor,
              "channel.operation.read",
              () -> {
                Query paged =
                    new Query(
                        query.dimension(),
                        query.granularity(),
                        query.sort(),
                        query.from(),
                        query.to(),
                        query.platformId(),
                        query.shopCode(),
                        query.internalAdType(),
                        query.status(),
                        query.externalCampaignId(),
                        query.keywordText(),
                        query.selectedKeys(),
                        at / take,
                        take);
                return switch (paged.dimension()) {
                  case "GROUP" -> groupReport(paged).items();
                  case "SEARCH_TERM" -> searchTermReport(paged).items();
                  case "NEGATIVE_EFFECT" -> negativeEffectReport(paged).items();
                  default -> keywordReport(paged).items();
                };
              });
      if (batch.isEmpty()) break;

      StringBuilder sb = new StringBuilder();
      for (AdGroupKeywordReportRow row : batch) {
        List<String> cells = new ArrayList<>();
        cells.add(dimensionLabel(query.dimension()));
        cells.add(row.dimensionKey());
        cells.add(row.dimensionLabel());
        cells.add(row.currency());
        cells.add(row.platformName());
        cells.add(row.shopName());
        cells.add(row.externalCampaignId());
        switch (query.dimension()) {
          case "GROUP" -> {
            cells.add(row.externalGroupId());
            cells.add(row.groupStatus());
            cells.add(row.campaignName());
            cells.add(row.campaignStatus());
            cells.add(plain(row.defaultBid()));
            cells.add(row.bidCurrency());
            cells.add(plain(row.dailyBudget()));
            cells.add(row.targetingType());
            cells.add(String.valueOf(row.productCount()));
            cells.add("未接入（无组级指标事实源）");
            cells.add(String.valueOf(row.dataAsOf()));
          }
          case "SEARCH_TERM" -> {
            cells.add(row.searchTerm());
            cells.add(String.valueOf(row.sourceKeywordCount()));
            cells.add(String.valueOf(row.sourceCampaignCount()));
            cells.add(row.keywordText());
            cells.add(plain(row.spend()));
            cells.add(plain(row.impressions()));
            cells.add(plain(row.clicks()));
            cells.add(plain(row.conversions()));
            cells.add(ratio(row.ctr()));
            cells.add(ratio(row.cpc()));
            cells.add(ratio(row.cvr()));
            cells.add(plain(row.sales()));
            cells.add(ratio(row.acos()));
            cells.add(ratio(row.roas()));
            cells.add(Boolean.TRUE.equals(row.zeroConversionWaste()) ? "是" : "否");
            cells.add(String.valueOf(row.metricFrom()));
            cells.add(String.valueOf(row.metricTo()));
            cells.add(String.valueOf(row.dataAsOf()));
          }
          case "NEGATIVE_EFFECT" -> {
            cells.add(row.negationText());
            cells.add(row.negationType());
            cells.add(row.negationStatus());
            cells.add(row.keywordText());
            cells.add(String.valueOf(row.effectiveDate()));
            cells.add(String.valueOf(row.evidenceFrom()));
            cells.add(String.valueOf(row.evidenceTo()));
            cells.add(row.evaluationOutcome());
            cells.add(plain(row.preImpressions()));
            cells.add(plain(row.preClicks()));
            cells.add(plain(row.preSpend()));
            cells.add(plain(row.preSales()));
            cells.add(plain(row.postImpressions()));
            cells.add(plain(row.postClicks()));
            cells.add(plain(row.postSpend()));
            cells.add(plain(row.postSales()));
            cells.add(row.postStillReporting() == null ? "无法评估" : (row.postStillReporting() ? "是" : "否"));
            cells.add(String.valueOf(row.dataAsOf()));
          }
          default -> {
            cells.add(row.keywordText());
            cells.add(row.externalKeywordId());
            cells.add(row.internalAdType());
            cells.add(row.platformAdType());
            cells.add(row.keywordStatus());
            cells.add(plain(row.spend()));
            cells.add(plain(row.impressions()));
            cells.add(plain(row.clicks()));
            cells.add(plain(row.conversions()));
            cells.add(ratio(row.ctr()));
            cells.add(ratio(row.cpc()));
            cells.add(ratio(row.cvr()));
            cells.add(plain(row.sales()));
            cells.add(ratio(row.acos()));
            cells.add(ratio(row.roas()));
            cells.add(String.valueOf(row.activeDays()));
            cells.add(String.valueOf(row.metricFrom()));
            cells.add(String.valueOf(row.metricTo()));
            cells.add(String.valueOf(row.dataAsOf()));
          }
        }
        sb.append(String.join(",", cells.stream().map(AdGroupKeywordReportService::csvCell).toList()));
        sb.append('\n');
      }
      emit.accept(sb.toString());
      offset += size;
      if (batch.size() < size) break;
    }
  }

  /** CSV 表头（**口径写进列名**）。 */
  private static String csvHeader(String dimension) {
    return switch (dimension) {
      case "GROUP" ->
          "维度,组id,组名,币种,平台,店铺,外部活动id,平台侧组id,组状态,所属活动,活动状态,"
              + "默认竞价,竞价币种,日预算,定向类型,投放商品数,组级指标,数据截至\n";
      case "SEARCH_TERM" ->
          "维度,搜索词,搜索词名,币种,平台,店铺,外部活动id,搜索词原文,来源词数,来源活动数,样本词,"
              + "花费,曝光,点击,转化,CTR(Σ点击/Σ曝光),CPC(Σ花费/Σ点击),转化率(Σ转化/Σ点击),"
              + "销售额,ACOS(Σ花费/Σ销售额),ROAS(Σ销售额/Σ花费),高曝光零转化,统计起,统计止,数据截至\n";
      case "NEGATIVE_EFFECT" ->
          "维度,否定id,被否定词,币种,平台,店铺,外部活动id,被否定词原文,否定类型,否定状态,所属词,"
              + "生效日(applied_at的UTC日期,空则回退evidence_to),依据起,依据止,"
              + "评估结论(否定后仍有花费或点击=否定后仍有数据),"
              + "否定前曝光,否定前点击,否定前花费,否定前销售额,"
              + "否定后曝光,否定后点击,否定后花费,否定后销售额,否定后仍有数据,数据截至\n";
      default ->
          "维度,词id,词,币种,平台,店铺,外部活动id,关键词原文,平台侧词id,归一后类型,原始平台类型,"
              + "词状态,花费,曝光,点击,转化,CTR(Σ点击/Σ曝光),CPC(Σ花费/Σ点击),转化率(Σ转化/Σ点击),"
              + "销售额,ACOS(Σ花费/Σ销售额),ROAS(Σ销售额/Σ花费),有数据天数,统计起,统计止,数据截至\n";
    };
  }

  /** 导出留痕列表（真分页）。 */
  public AdGroupKeywordReportExportPage exports(int page, int size) {
    int safePage = Math.max(page, 0);
    int safeSize = Math.min(Math.max(size, 1), 200);
    List<AdGroupKeywordReportExportRow> items =
        sql.fetch(
                "select id, requested_by, dimension, granularity, platform_id, shop_code,"
                    + " internal_ad_type, status, external_campaign_id, keyword_text, date_from,"
                    + " date_to, row_count, selection, data_as_of, metric_definition_note, created_at"
                    + " from ad_group_keyword_report_export where tenant_id = " + TENANT
                    + " order by created_at desc, id desc limit ? offset ?",
                safeSize,
                safePage * safeSize)
            .stream()
            .map(
                r ->
                    new AdGroupKeywordReportExportRow(
                        r.get("id", UUID.class),
                        r.get("requested_by", UUID.class),
                        r.get("dimension", String.class),
                        r.get("granularity", String.class),
                        r.get("platform_id", UUID.class),
                        r.get("shop_code", String.class),
                        r.get("internal_ad_type", String.class),
                        r.get("status", String.class),
                        r.get("external_campaign_id", String.class),
                        r.get("keyword_text", String.class),
                        r.get("date_from", LocalDate.class),
                        r.get("date_to", LocalDate.class),
                        r.get("row_count", Integer.class),
                        selectionOf(r.get("selection", String.class)),
                        r.get("data_as_of", OffsetDateTime.class),
                        r.get("metric_definition_note", String.class),
                        r.get("created_at", OffsetDateTime.class)))
            .toList();
    long total =
        count("select count(*) from ad_group_keyword_report_export where tenant_id = " + TENANT);
    return new AdGroupKeywordReportExportPage(items, total, safePage, safeSize);
  }

  /** 读回导出留痕（用于按 id 重新导出 CSV）。 */
  public AdGroupKeywordReportExportRow exportById(UUID id) {
    Record r =
        sql.fetchOne(
            "select id, requested_by, dimension, granularity, platform_id, shop_code,"
                + " internal_ad_type, status, external_campaign_id, keyword_text, date_from,"
                + " date_to, row_count, selection, data_as_of, metric_definition_note, created_at"
                + " from ad_group_keyword_report_export where tenant_id = " + TENANT + " and id = ?",
            id);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "导出留痕不存在");
    }
    return new AdGroupKeywordReportExportRow(
        r.get("id", UUID.class),
        r.get("requested_by", UUID.class),
        r.get("dimension", String.class),
        r.get("granularity", String.class),
        r.get("platform_id", UUID.class),
        r.get("shop_code", String.class),
        r.get("internal_ad_type", String.class),
        r.get("status", String.class),
        r.get("external_campaign_id", String.class),
        r.get("keyword_text", String.class),
        r.get("date_from", LocalDate.class),
        r.get("date_to", LocalDate.class),
        r.get("row_count", Integer.class),
        selectionOf(r.get("selection", String.class)),
        r.get("data_as_of", OffsetDateTime.class),
        r.get("metric_definition_note", String.class),
        r.get("created_at", OffsetDateTime.class));
  }

  @SuppressWarnings("unchecked")
  private List<String> selectionOf(String raw) {
    if (raw == null || raw.isBlank()) return List.of();
    try {
      return json.readValue(raw, new TypeReference<List<String>>() {});
    } catch (Exception e) {
      return List.of();
    }
  }

  // ==========================================================================
  // 小工具
  // ==========================================================================

  /** 金额 / 数量的文本（不做任何前端算术，服务端给什么就是什么）。 */
  private static String plain(BigDecimal value) {
    return value == null ? "" : value.toPlainString();
  }

  /**
   * 比率的文本：🔴 分母为 0 是「不可比」，写「不可比」而<b>不是</b> 0。
   *
   * <p>0 与空在报表里含义相反：0 = 可比但真的是零；不可比 = 压根算不出来。
   */
  private static String ratio(BigDecimal value) {
    return value == null ? "不可比" : value.toPlainString();
  }

  /** CSV 单元格转义（值里带逗号 / 引号 / 换行时加引号）。 */
  static String csvCell(String value) {
    if (value == null) return "";
    if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
      return "\"" + value.replace("\"", "\"\"") + "\"";
    }
    return value;
  }

  /** 维度的显示名（CSV 的第一列）。 */
  static String dimensionLabel(String dimension) {
    return switch (dimension) {
      case "GROUP" -> "按广告组";
      case "SEARCH_TERM" -> "按搜索词";
      case "NEGATIVE_EFFECT" -> "按否定记录";
      default -> "按关键词";
    };
  }

  /** 这个区间里有没有指标（**不带**平台 / 店铺 / 类型 / 状态筛选）。 */
  private long metricsInRange(String dimension, LocalDate from, LocalDate to) {
    return switch (dimension) {
      case "SEARCH_TERM" ->
          count(
              "select count(*) from ad_search_term where tenant_id = " + TENANT
                  + " and bucket_date >= ?::date and bucket_date <= ?::date",
              from,
              to);
      default ->
          count(
              "select count(*) from ad_keyword_metric where tenant_id = " + TENANT
                  + " and bucket_date >= ?::date and bucket_date <= ?::date",
              from,
              to);
    };
  }
}
