package com.starcon.channel;

import static com.starcon.channel.AdCampaignReportModels.*;

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
 * 14.3.1.1 活动报表。
 *
 * <p>蓝图：「活动维度效果报表：花费、曝光、点击、CTR、CPC、订单、转化率、销售额、ACOS/ROAS/TACOS，
 * 按日/周/月与多维对比，支持导出。输入：时间范围；维度；广告数据。
 * 输出：报表表 + 趋势图 + 口径说明。关系：上游 = 14.1.1.1；下游 = 14.3.2.1。
 * <b>ACOS/ROAS/TACOS 三个口径要在表头写明定义</b>，否则跨部门沟通会各说各话。」
 *
 * <p>本类按职责句逐项落点：
 *
 * <ul>
 *   <li><b>活动维度效果报表</b> → {@link #report(Query)}：花费、曝光、点击、CTR、CPC、订单、转化率、
 *       销售额、ACOS/ROAS 十项指标全部来自上游 14.1.1.1 的 {@code ad_campaign_daily_metric} 真实事实表。
 *   <li><b>按日/周/月</b> → {@code granularity} ∈ DAY / WEEK / MONTH，落到 SQL 的 {@code date_trunc}。
 *   <li><b>多维对比</b> → {@code dimension} ∈ CAMPAIGN / PLATFORM / SHOP / AD_TYPE / CURRENCY。
 *   <li><b>支持导出</b> → {@link #recordExport} + {@link #streamCsv}：CSV（口径写进表头）+ 导出留痕。
 *   <li><b>报表表 + 趋势图 + 口径说明</b> → {@link #report} / {@link #trend} / {@code metricDefinitions}。
 * </ul>
 *
 * <p>🔴 本类最容易做丢的四件事，这里都挡住了：
 *
 * <ol>
 *   <li><b>口径是「先汇总再相除」，不是比率的平均</b>。上游 {@code ad_campaign_daily_metric} 上有
 *       {@code ctr/acos/roas} 三个生成列，那是<b>单行</b>口径；报表是<b>多行聚合</b>口径，
 *       必须 Σ分子 / Σ分母。对生成列求平均是数学错误（两条 ACOS 100% 的活动平均成 50%，
 *       会让运营以为整体在盈亏线上 —— 它其实在亏）。本类的聚合 SQL 一律先 sum 再相除。
 *   <li><b>TACOS 没有分母，就不出数值</b>。总销售额来自 13.4 利润模块，而卷 13 的主责服务
 *       reporting 不在集成头 ⇒ TACOS 现在算不出来。不许用 0 当分母，也不许用广告销售额顶替
 *       总销售额（那算出来的是 ACOS 不是 TACOS）。本类走能力声明口径显式标 {@code available=false}。
 *   <li><b>不同币种不合并</b>。本仓没有汇率源，把 USD 与 EUR 的花费加在一起就是假总数 ⇒
 *       分组键里始终带币种，汇总也按币种拆开。
 *   <li><b>「没数据」与「没投放」分开说</b>。空态三值：{@code NO_SYNCED_DATA}（从没成功同步过）
 *       / {@code NO_METRICS_IN_RANGE}（同步过，但这个区间没有指标）/ {@code FILTERED_EMPTY}
 *       （区间里有指标，被筛选条件排除了）。
 * </ol>
 *
 * <p>🔴 报表查询的性能纪律（蓝图加粗句「查询必须分页与缓存，不能实时全表扫」）：
 * 时间范围**必填**且按粒度封顶（最多 400 个桶）；所有聚合走 jOOQ 显式 SQL，谓词顺序对齐上游索引
 * {@code ad_campaign_daily_metric_window (tenant_id, metric_date, campaign_id)}（先 tenant_id、
 * 再 metric_date 范围）；**真分页**（LIMIT/OFFSET）；**汇总单独一条聚合查询**（统计全集，不是当前页）。
 *
 * <p>🔴 数据来源纪律：事实全部来自上游 14.1.1.1 的表（V273），本模块**不复制**任何一列指标，
 * 也**不建**第二套事实表；平台目录（V1 {@code platform}）与店铺（V1 {@code shop}）同库只读引用。
 */
@Service
public class AdCampaignReportService {

  private static final String TENANT =
      "nullif(current_setting('app.current_tenant',true),'')::uuid";

  /** 时间粒度。 */
  static final List<String> GRANULARITIES = List.of("DAY", "WEEK", "MONTH");

  /** 分组维度（多维对比）。 */
  static final List<String> DIMENSIONS =
      List.of("CAMPAIGN", "PLATFORM", "SHOP", "AD_TYPE", "CURRENCY");

  /** 🔴 每个粒度下允许的最大桶数：区间跨度过大直接拒，不做「悄悄截断」。 */
  static final int MAX_BUCKETS = 400;

  static final String DEFAULT_GRANULARITY = "DAY";
  static final String DEFAULT_DIMENSION = "CAMPAIGN";
  static final String DEFAULT_SORT = "SPEND_DESC";

  /** 排序方式 → ORDER BY 子句。**白名单映射**：排序串绝不拼进 SQL。 */
  private static final Map<String, String> SORTS =
      Map.ofEntries(
          Map.entry("SPEND_DESC", "order by sum(dm.spend) desc, c.currency asc, dimension_key asc"),
          Map.entry("SPEND_ASC", "order by sum(dm.spend) asc, c.currency asc, dimension_key asc"),
          Map.entry("SALES_DESC", "order by sum(dm.sales) desc, c.currency asc, dimension_key asc"),
          Map.entry(
              "ACOS_ASC",
              "order by (case when sum(dm.sales) > 0 then sum(dm.spend)/sum(dm.sales) end)"
                  + " asc nulls last, c.currency asc, dimension_key asc"),
          Map.entry(
              "ROAS_DESC",
              "order by (case when sum(dm.spend) > 0 then sum(dm.sales)/sum(dm.spend) end)"
                  + " desc nulls last, c.currency asc, dimension_key asc"),
          Map.entry(
              "IMPRESSIONS_DESC",
              "order by sum(dm.impressions) desc, c.currency asc, dimension_key asc"),
          Map.entry(
              "CLICKS_DESC", "order by sum(dm.clicks) desc, c.currency asc, dimension_key asc"),
          Map.entry(
              "ORDERS_DESC", "order by sum(dm.orders) desc, c.currency asc, dimension_key asc"),
          Map.entry("LABEL_ASC", "order by dimension_label asc, c.currency asc, dimension_key asc"));

  // ==========================================================================
  // 🔴 三个口径的定义：写在返回值里，不写在注释里
  // ==========================================================================

  /**
   * 逐指标的口径定义 —— **表头就是从这里渲染的**。
   *
   * <p>🔴 {@code tacos} 的 {@code available=false}：总销售额来自 13.4 利润模块，
   * 而卷 13 的主责服务 reporting 不在集成头 ⇒ TACOS <b>没有分母</b>。
   * 不许用 0 当分母，也不许用广告销售额顶替总销售额（那算出来的是 ACOS 不是 TACOS）。
   */
  static final List<AdReportMetricDefinition> METRIC_DEFINITIONS =
      List.of(
          new AdReportMetricDefinition(
              "spend",
              "花费",
              true,
              "花费 = Σ广告消耗（平台回传的当日消耗）",
              "ad_campaign_daily_metric.spend（V273）",
              "",
              ""),
          new AdReportMetricDefinition(
              "impressions",
              "曝光",
              true,
              "曝光 = Σ曝光量",
              "ad_campaign_daily_metric.impressions（V273）",
              "",
              ""),
          new AdReportMetricDefinition(
              "clicks",
              "点击",
              true,
              "点击 = Σ点击量",
              "ad_campaign_daily_metric.clicks（V273）",
              "",
              ""),
          new AdReportMetricDefinition(
              "ctr",
              "CTR 点击率",
              true,
              "CTR = Σ点击 / Σ曝光",
              "ad_campaign_daily_metric（V273）聚合",
              "",
              ""),
          new AdReportMetricDefinition(
              "cpc",
              "CPC 单次点击成本",
              true,
              "CPC = Σ花费 / Σ点击",
              "ad_campaign_daily_metric（V273）聚合",
              "",
              ""),
          new AdReportMetricDefinition(
              "orders",
              "订单",
              true,
              "订单 = Σ广告带来的订单量",
              "ad_campaign_daily_metric.orders（V273）",
              "",
              ""),
          new AdReportMetricDefinition(
              "cvr",
              "转化率",
              true,
              "转化率 = Σ订单 / Σ点击",
              "ad_campaign_daily_metric（V273）聚合",
              "",
              ""),
          new AdReportMetricDefinition(
              "sales",
              "销售额",
              true,
              "销售额 = Σ广告带来的销售额（不是店铺总销售额）",
              "ad_campaign_daily_metric.sales（V273）",
              "",
              ""),
          new AdReportMetricDefinition(
              "acos",
              "ACOS",
              true,
              "ACOS = Σ花费 / Σ广告销售额",
              "ad_campaign_daily_metric（V273）聚合",
              "",
              ""),
          new AdReportMetricDefinition(
              "roas",
              "ROAS",
              true,
              "ROAS = Σ广告销售额 / Σ花费",
              "ad_campaign_daily_metric（V273）聚合",
              "",
              ""),
          new AdReportMetricDefinition(
              "tacos",
              "TACOS",
              false,
              "TACOS = 广告花费 / 总销售额（总销售额含自然单，不是广告销售额）",
              "—",
              "13.4 利润模块（未交付：卷 13 的主责服务 reporting 不在集成头）",
              "总销售额口径未接入，当前不出 TACOS 数值。"
                  + "不用 0 当分母，也不用广告销售额顶替总销售额（那算出来的是 ACOS 不是 TACOS）。"));

  /** 三个口径的一句话说明（随报表一起返回，界面表头直接显示）。 */
  static final String METRIC_DEFINITION_NOTE =
      "ACOS = 广告花费 / 广告销售额，ROAS = 广告销售额 / 广告花费（两者互为倒数），"
          + "TACOS = 广告花费 / 总销售额（总销售额含自然单）。"
          + "三者都按「先汇总再相除」算（Σ分子 / Σ分母），**不是**各行比率的平均 —— "
          + "后者是数学错误，会把两条 ACOS 100% 的活动说成整体 50%。"
          + "分母为 0 一律返回空值（不可比），不是 0。"
          + "🔴 当前不出 TACOS 数值：总销售额口径（13.4 利润模块）未接入，没有分母。";

  /** 数据截至时刻的口径说明。 */
  static final String DATA_AS_OF_NOTE =
      "「数据截至 X 时间」= 各次**成功/部分成功**的广告数据同步里最大的 data_as_of；"
          + "失败的同步**不推进水位**（失败就是没拿到数据）。"
          + "报表里的每一行都来自这批已同步的数据 —— 水位之前的空白是「还没同步到」，不是「没投放」。";

  private final DSLContext sql;
  private final AccessService access;

  public AdCampaignReportService(DSLContext sql, AccessService access) {
    this.sql = sql;
    this.access = access;
  }

  // ==========================================================================
  // 小工具（照抄 AdCampaignService 的同名写法）
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

  // ==========================================================================
  // 报表查询条件
  // ==========================================================================

  /**
   * 一次报表查询的全部条件。
   *
   * <p>🔴 {@code from} / {@code to} **必填**：没有时间范围的报表就是全表扫，
   * 而蓝图加粗句点名「不能实时全表扫」。不给区间就报错，不给默认区间。
   */
  public record Query(
      LocalDate from,
      LocalDate to,
      String granularity,
      String dimension,
      String sort,
      UUID platformId,
      String shopCode,
      String internalAdType,
      String status,
      int page,
      int size) {}

  /** 归一化并校验查询条件（非法值直接 400，不静默回落到默认值 —— 静默回落会让筛选「不生效」）。 */
  Query normalize(
      LocalDate from,
      LocalDate to,
      String granularity,
      String dimension,
      String sort,
      UUID platformId,
      String shopCode,
      String internalAdType,
      String status,
      int page,
      int size) {
    if (from == null || to == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "报表必须给出统计起始日与截止日（from 与 to 都是必填）");
    }
    if (from.isAfter(to)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "统计起始日不能晚于截止日");
    }
    String gran = text(granularity).toUpperCase(Locale.ROOT);
    if (gran.isEmpty()) gran = DEFAULT_GRANULARITY;
    if (!GRANULARITIES.contains(gran)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "时间粒度只能是 DAY / WEEK / MONTH");
    }
    String dim = text(dimension).toUpperCase(Locale.ROOT);
    if (dim.isEmpty()) dim = DEFAULT_DIMENSION;
    if (!DIMENSIONS.contains(dim)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "分组维度只能是 CAMPAIGN / PLATFORM / SHOP / AD_TYPE / CURRENCY");
    }
    String sortKey = text(sort).toUpperCase(Locale.ROOT);
    if (sortKey.isEmpty()) sortKey = DEFAULT_SORT;
    if (!SORTS.containsKey(sortKey)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "排序方式不在白名单里");
    }
    // 🔴 时间跨度封顶：桶太多就拒，不悄悄截断（悄悄截断 = 报表少算了一段却没人知道）。
    long buckets = bucketCount(gran, from, to);
    if (buckets > MAX_BUCKETS) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "时间跨度过大：按 " + gran + " 粒度最多 " + MAX_BUCKETS + " 个区间，当前约 " + buckets + " 个");
    }
    return new Query(
        from,
        to,
        gran,
        dim,
        sortKey,
        platformId,
        blankToNull(shopCode),
        blankToNull(internalAdType),
        blankToNull(status),
        Math.max(page, 0),
        Math.min(Math.max(size, 1), 200));
  }

  private static long bucketCount(String granularity, LocalDate from, LocalDate to) {
    long days = ChronoUnit.DAYS.between(from, to) + 1;
    return switch (granularity) {
      case "WEEK" -> (days + 6) / 7;
      case "MONTH" -> ChronoUnit.MONTHS.between(from, to) + 1;
      default -> days;
    };
  }

  /** 一条筛选条件（where 子句 + 绑定值）。 */
  private record Filters(String where, List<Object> binds) {}

  /**
   * 构造筛选条件。
   *
   * <p>🔴 谓词顺序对齐上游索引 {@code ad_campaign_daily_metric_window (tenant_id, metric_date, campaign_id)}：
   * **先 tenant_id，再 metric_date 范围**。这样查询走的是窗口索引而不是全表扫。
   */
  private Filters buildFilters(Query query, boolean withCampaignFilters) {
    StringBuilder where = new StringBuilder(" where dm.tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();

    where.append(" and dm.metric_date >= ?::date");
    binds.add(query.from());
    where.append(" and dm.metric_date <= ?::date");
    binds.add(query.to());

    if (withCampaignFilters) {
      if (query.platformId() != null) {
        where.append(" and c.platform_id = ?");
        binds.add(query.platformId());
      }
      if (query.shopCode() != null) {
        where.append(" and c.shop_code = ?");
        binds.add(query.shopCode());
      }
      if (query.internalAdType() != null) {
        where.append(" and c.internal_ad_type = ?");
        binds.add(query.internalAdType());
      }
      if (query.status() != null) {
        where.append(" and c.status = ?");
        binds.add(query.status());
      }
    }
    return new Filters(where.toString(), binds);
  }

  /** 分组维度的 SQL 片段（键、显示名、以及三个上下文列）。 */
  private record Grouping(
      String keyExpr,
      String labelExpr,
      String platformExpr,
      String shopExpr,
      String externalExpr,
      String groupBy) {}

  private static Grouping grouping(String dimension) {
    return switch (dimension) {
      case "PLATFORM" ->
          new Grouping(
              "c.platform_id::text",
              "pf.name",
              "pf.name",
              "null::varchar",
              "null::varchar",
              "c.platform_id, pf.name");
      case "SHOP" ->
          new Grouping(
              "c.shop_code",
              "s.name",
              "null::varchar",
              "s.name",
              "null::varchar",
              "c.shop_code, s.name");
      case "AD_TYPE" ->
          new Grouping(
              "c.internal_ad_type",
              "c.internal_ad_type",
              "null::varchar",
              "null::varchar",
              "null::varchar",
              "c.internal_ad_type");
      case "CURRENCY" ->
          new Grouping(
              "c.currency",
              "c.currency",
              "null::varchar",
              "null::varchar",
              "null::varchar",
              "c.currency");
      default ->
          new Grouping(
              "c.id::text",
              "coalesce(nullif(c.campaign_name,''), c.external_campaign_id)",
              "pf.name",
              "s.name",
              "c.external_campaign_id",
              "c.id, c.campaign_name, c.external_campaign_id, pf.name, s.name");
    };
  }

  /**
   * 聚合指标列 —— 🔴 **全部先 sum 再相除**，不对上游的生成列求平均。
   *
   * <p>分母为 0 一律给 NULL（不可比），不是 0。
   */
  private static final String AGGREGATE_COLUMNS =
      """
       sum(dm.impressions) impressions,
       sum(dm.clicks) clicks,
       sum(dm.orders) orders,
       sum(dm.spend) spend,
       sum(dm.sales) sales,
       case when sum(dm.impressions) > 0
            then round(sum(dm.clicks) / sum(dm.impressions), 6) end ctr,
       case when sum(dm.clicks) > 0
            then round(sum(dm.spend) / sum(dm.clicks), 6) end cpc,
       case when sum(dm.clicks) > 0
            then round(sum(dm.orders) / sum(dm.clicks), 6) end cvr,
       case when sum(dm.sales) > 0
            then round(sum(dm.spend) / sum(dm.sales), 6) end acos,
       case when sum(dm.spend) > 0
            then round(sum(dm.sales) / sum(dm.spend), 6) end roas,
       min(dm.metric_date) metric_from,
       max(dm.metric_date) metric_to
      """;

  /** 事实表 + 维表的连接（平台目录与店铺同库只读引用）。 */
  private static final String FROM_JOINS =
      """
       from ad_campaign_daily_metric dm
       join ad_campaign c on c.tenant_id = dm.tenant_id and c.id = dm.campaign_id
       join platform pf on pf.id = c.platform_id
       join shop s on s.tenant_id = c.tenant_id and s.code = c.shop_code
      """;

  // ==========================================================================
  // 能力声明
  // ==========================================================================

  public AdCampaignReportCapabilities capabilities() {
    return new AdCampaignReportCapabilities(
        List.of(
            new AdReportCapabilityDimension(
                "campaign_report",
                "活动维度效果报表（花费、曝光、点击、CTR、CPC、订单、转化率、销售额、ACOS/ROAS）",
                true,
                "ad_campaign_daily_metric 聚合（V273，上游 14.1.1.1）",
                "",
                "没有它就没有广告投放效果的唯一事实来源，14.3.2.1 归因分析也没有落点"),
            new AdReportCapabilityDimension(
                "granularity",
                "按日 / 周 / 月三种时间粒度出表",
                true,
                "SQL date_trunc（V279 服务层）",
                "",
                "只能按天看就没法回答「这一周比上一周好还是差」"),
            new AdReportCapabilityDimension(
                "multi_dimension",
                "按活动 / 平台 / 店铺 / 归一后类型 / 币种多维对比",
                true,
                "ad_campaign + ad_campaign_daily_metric（V273）",
                "",
                "只能按活动看，跨平台横向比较就得靠人肉汇总"),
            new AdReportCapabilityDimension(
                "trend",
                "趋势图（按粒度分桶的时间序列）",
                true,
                "ad_campaign_daily_metric 聚合（V273）",
                "",
                "只有汇总没有趋势，就看不出异常是哪天开始的"),
            new AdReportCapabilityDimension(
                "export",
                "导出 CSV（口径写进表头）+ 导出留痕",
                true,
                "ad_campaign_report_export（V279）",
                "",
                "运营要拿报表去开会时只能截图，数字与口径都对不上"),
            new AdReportCapabilityDimension(
                "tacos",
                "TACOS（广告花费 / 总销售额）",
                false,
                "—",
                "13.4 利润模块（未交付：卷 13 的主责服务 reporting 不在集成头）",
                "总销售额口径未接入 ⇒ 当前不出 TACOS 数值。"
                    + "不用 0 当分母，也不用广告销售额顶替总销售额（那算出来的是 ACOS 不是 TACOS）"),
            new AdReportCapabilityDimension(
                "multi_currency_unification",
                "跨币种统一口径（把不同币种换算成同一货币再汇总）",
                false,
                "—",
                "汇率源（未交付：本仓没有汇率表，也没有汇率同步器）",
                "报表按币种拆开：不同币种的花费 / 销售额不会被加成一个假总数，"
                    + "代价是跨币种的横向比较暂时做不了"),
            new AdReportCapabilityDimension(
                "ad_group_keyword_report",
                "广告组 / 关键词 / 搜索词维度报表",
                false,
                "—",
                "14.3.1.2 广告组与词报表",
                "本模块只到活动维度；关键词级数据量极大，其分页与缓存策略归 14.3.1.2"),
            new AdReportCapabilityDimension(
                "product_dimension_report",
                "商品（SKU / SPU / Listing）维度报表",
                false,
                "—",
                "14.3.1.3 商品维度报表",
                "本模块只到活动维度；商品维度需要 14.3.1.3"),
            new AdReportCapabilityDimension(
                "attribution",
                "广告归因（把花费归到订单 / SKU，算广告后净利与增量效果）",
                false,
                "—",
                "14.3.2.1 归因分析",
                "本模块给的是活动维度效果，不做归因"),
            new AdReportCapabilityDimension(
                "report_cache",
                "报表结果的物化缓存（预聚合表 / 结果缓存）",
                false,
                "—",
                "本模块的索引 + 分页 + 时间范围封顶（已交付部分）",
                "本模块的活动维度靠「索引对齐 + 必填时间范围 + 真分页 + 桶数封顶」保证不实时全表扫；"
                    + "真正的物化预聚合留给关键词级（14.3.1.2）那种量级")),
        METRIC_DEFINITIONS,
        GRANULARITIES,
        DIMENSIONS,
        List.copyOf(SORTS.keySet()),
        List.of("NO_SYNCED_DATA", "NO_METRICS_IN_RANGE", "FILTERED_EMPTY"),
        METRIC_DEFINITION_NOTE,
        DATA_AS_OF_NOTE,
        "上游 = 14.1.1.1 广告活动列表（已交付）：事实表 ad_campaign_daily_metric（V273）、"
            + "水位 ad_campaign_sync_run（V273）、活动维表 ad_campaign（V273）；"
            + "平台目录（2.1.1.1）与店铺（2.3.1.1）同库只读引用。",
        "下游 = 14.3.2.1 归因分析（未交付）；14.3.1.2 / 14.3.1.3 另有自己的维度。"
            + "🔴 本模块**不做**跨币种换算（没有汇率源）、**不出** TACOS 数值（13.4 未接入）—— "
            + "这两件事都在能力声明里显式标 available=false，不用假数字糊过去。");
  }

  // ==========================================================================
  // 选项
  // ==========================================================================

  public AdCampaignReportOptions options() {
    List<AdCampaignReportOption> platforms =
        sql.fetch("select id, code, name from platform order by position, code")
            .stream()
            .map(r -> new AdCampaignReportOption(r.get("code", String.class), r.get("name", String.class)))
            .toList();

    List<AdCampaignReportOption> shops =
        sql.fetch("select code, name from shop where tenant_id = " + TENANT + " order by code")
            .stream()
            .map(r -> new AdCampaignReportOption(r.get("code", String.class), r.get("name", String.class)))
            .toList();

    List<AdCampaignReportOption> granularities =
        List.of(
            new AdCampaignReportOption("DAY", "按日"),
            new AdCampaignReportOption("WEEK", "按周（周一为一周之始）"),
            new AdCampaignReportOption("MONTH", "按月"));

    List<AdCampaignReportOption> dimensions =
        List.of(
            new AdCampaignReportOption("CAMPAIGN", "按活动"),
            new AdCampaignReportOption("PLATFORM", "按平台"),
            new AdCampaignReportOption("SHOP", "按店铺"),
            new AdCampaignReportOption("AD_TYPE", "按归一后广告类型"),
            new AdCampaignReportOption("CURRENCY", "按币种"));

    List<AdCampaignReportOption> types =
        List.of(
                "AUTO_DISCOVERY",
                "SPONSORED_BRAND",
                "SPONSORED_DISPLAY",
                "BRAND_AD",
                "DSP_PROGRAMMATIC",
                "PROMOTION_EVENT",
                "UNMAPPED")
            .stream()
            .map(t -> new AdCampaignReportOption(t, AdCampaignService.internalTypeLabel(t)))
            .toList();

    List<AdCampaignReportOption> statuses =
        List.of("ENABLED", "PAUSED", "ENDED", "ARCHIVED", "PENDING_REVIEW", "UNKNOWN")
            .stream()
            .map(s -> new AdCampaignReportOption(s, AdCampaignService.statusLabel(s)))
            .toList();

    List<AdCampaignReportOption> sorts =
        List.of(
            new AdCampaignReportOption("SPEND_DESC", "花费高到低"),
            new AdCampaignReportOption("SPEND_ASC", "花费低到高"),
            new AdCampaignReportOption("SALES_DESC", "销售额高到低"),
            new AdCampaignReportOption("ACOS_ASC", "ACOS 低到高（不可比的排最后）"),
            new AdCampaignReportOption("ROAS_DESC", "ROAS 高到低（不可比的排最后）"),
            new AdCampaignReportOption("IMPRESSIONS_DESC", "曝光高到低"),
            new AdCampaignReportOption("CLICKS_DESC", "点击高到低"),
            new AdCampaignReportOption("ORDERS_DESC", "订单高到低"),
            new AdCampaignReportOption("LABEL_ASC", "名称"));

    return new AdCampaignReportOptions(
        granularities,
        dimensions,
        platforms,
        shops,
        types,
        statuses,
        sorts,
        METRIC_DEFINITIONS,
        METRIC_DEFINITION_NOTE,
        "时间范围必填（from / to 都是必填项），且按粒度封顶："
            + "DAY 最多 " + MAX_BUCKETS + " 天、WEEK 最多 " + MAX_BUCKETS + " 周、"
            + "MONTH 最多 " + MAX_BUCKETS + " 个月。跨度过大直接报错，不做悄悄截断。");
  }

  // ==========================================================================
  // 报表表
  // ==========================================================================

  public AdCampaignReportPage report(Query query) {
    Filters filters = buildFilters(query, true);
    Grouping grouping = grouping(query.dimension());
    String orderBy = SORTS.get(query.sort());

    // ---- 列表本体（真分页）----
    String sqlText =
        "select "
            + grouping.keyExpr()
            + " dimension_key, "
            + grouping.labelExpr()
            + " dimension_label, c.currency currency, "
            + grouping.platformExpr()
            + " platform_name, "
            + grouping.shopExpr()
            + " shop_name, "
            + grouping.externalExpr()
            + " external_campaign_id, "
            + AGGREGATE_COLUMNS
            + FROM_JOINS
            + filters.where()
            + " group by "
            + grouping.groupBy()
            + ", c.currency"
            + " "
            + orderBy
            + " limit ? offset ?";

    List<Object> binds = new ArrayList<>(filters.binds());
    binds.add(query.size());
    binds.add(query.page() * query.size());

    List<AdCampaignReportRow> items =
        sql.fetch(sqlText, binds.toArray()).stream().map(r -> reportRow(r, query.dimension())).toList();

    // ---- 总条数（分组后的行数）----
    long total = countGroups(query, grouping);

    // ---- 汇总：单独一条聚合查询（统计筛选后的全集，不受分页影响）----
    List<AdCampaignReportSummary> summaries = summaries(query, grouping);

    // ---- 数据截至 X 时间 ----
    OffsetDateTime dataAsOf = dataAsOf();

    // ---- 空态：把三种「空」分开 ----
    String emptyReason = null;
    String emptyNote = "";
    if (total == 0) {
      if (!hasSuccessfulSync()) {
        // 🔴 从来没成功同步过 ⇒ 只知道「还没同步到数据」，**不能**说「这个区间没有投放」。
        emptyReason = "NO_SYNCED_DATA";
        emptyNote =
            "本租户还没有任何一次**成功的广告数据同步**。"
                + "这不等于「这段时间没有投放」—— 只是还没同步到数据。";
      } else if (metricsInRange(query.from(), query.to()) > 0) {
        // 🔴 区间里有指标，但被平台 / 店铺 / 类型 / 状态这些条件全部排除了。
        emptyReason = "FILTERED_EMPTY";
        emptyNote = "这个时间区间里确实有广告数据，但被当前的筛选条件全部排除了。放宽条件再试。";
      } else {
        // 🔴 同步成功过，而这个区间里一条指标都没有 —— 这次是真的「这段时间没有投放数据」。
        emptyReason = "NO_METRICS_IN_RANGE";
        emptyNote =
            "广告数据同步成功过，但所选时间区间里没有任何投放指标 —— 这次是真的「这段时间没有投放数据」。";
      }
    }

    return new AdCampaignReportPage(
        items,
        total,
        query.page(),
        query.size(),
        query.granularity(),
        query.dimension(),
        summaries,
        dataAsOf,
        DATA_AS_OF_NOTE,
        METRIC_DEFINITIONS,
        emptyReason,
        emptyNote,
        METRIC_DEFINITION_NOTE);
  }

  private AdCampaignReportRow reportRow(Record r, String dimension) {
    String label = r.get("dimension_label", String.class);
    // 🔴 归一后类型的显示名走 14.1.1.1 的同一份映射（前端不自己造第二套）。
    if ("AD_TYPE".equals(dimension) && label != null) {
      label = AdCampaignService.internalTypeLabel(label);
    }
    return new AdCampaignReportRow(
        dimension,
        r.get("dimension_key", String.class),
        label,
        r.get("currency", String.class),
        r.get("platform_name", String.class),
        r.get("shop_name", String.class),
        r.get("external_campaign_id", String.class),
        r.get("impressions", BigDecimal.class),
        r.get("clicks", BigDecimal.class),
        r.get("orders", BigDecimal.class),
        r.get("spend", BigDecimal.class),
        r.get("sales", BigDecimal.class),
        r.get("ctr", BigDecimal.class),
        r.get("cpc", BigDecimal.class),
        r.get("cvr", BigDecimal.class),
        r.get("acos", BigDecimal.class),
        r.get("roas", BigDecimal.class),
        r.get("metric_from", LocalDate.class),
        r.get("metric_to", LocalDate.class));
  }

  /** 分组后的总条数（独立查询，不复用列表那条）。 */
  private long countGroups(Query query, Grouping grouping) {
    Filters filters = buildFilters(query, true);
    return count(
        "select count(*) from (select " + grouping.keyExpr() + " dimension_key, c.currency"
            + FROM_JOINS
            + filters.where()
            + " group by "
            + grouping.groupBy()
            + ", c.currency) t",
        filters.binds().toArray());
  }

  /**
   * 汇总 —— 🔴 **单独一条聚合查询**，统计的是筛选后的全集（不是当前页）。
   *
   * <p>按币种拆开：本仓没有汇率源，不同币种的花费加在一起就是假总数。
   */
  private List<AdCampaignReportSummary> summaries(Query query, Grouping grouping) {
    Filters filters = buildFilters(query, true);
    String sqlText =
        "select c.currency currency, count(distinct " + grouping.keyExpr() + ") group_count,"
            + AGGREGATE_COLUMNS
            + FROM_JOINS
            + filters.where()
            + " group by c.currency order by c.currency";
    return sql.fetch(sqlText, filters.binds().toArray()).stream()
        .map(
            r ->
                new AdCampaignReportSummary(
                    r.get("currency", String.class),
                    r.get("group_count", Long.class),
                    r.get("impressions", BigDecimal.class),
                    r.get("clicks", BigDecimal.class),
                    r.get("orders", BigDecimal.class),
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

  // ==========================================================================
  // 趋势图
  // ==========================================================================

  /** 时间桶表达式（周从周一开始，月从 1 号开始）。 */
  private static String bucketExpr(String granularity) {
    return switch (granularity) {
      case "WEEK" -> "date_trunc('week', dm.metric_date)::date";
      case "MONTH" -> "date_trunc('month', dm.metric_date)::date";
      default -> "dm.metric_date";
    };
  }

  public AdCampaignReportTrend trend(Query query) {
    Filters filters = buildFilters(query, true);
    String bucket = bucketExpr(query.granularity());

    String sqlText =
        "select " + bucket + " period_start, c.currency currency, " + AGGREGATE_COLUMNS
            + FROM_JOINS
            + filters.where()
            + " group by " + bucket + ", c.currency order by c.currency, " + bucket;

    List<Record> rows = sql.fetch(sqlText, filters.binds().toArray());

    // 🔴 按币种拆成多条趋势线：没有汇率源就不许把两条线加在一起。
    Map<String, List<AdCampaignReportTrendPoint>> byCurrency = new LinkedHashMap<>();
    for (Record r : rows) {
      String currency = r.get("currency", String.class);
      byCurrency
          .computeIfAbsent(currency, k -> new ArrayList<>())
          .add(
              new AdCampaignReportTrendPoint(
                  r.get("period_start", LocalDate.class),
                  r.get("impressions", BigDecimal.class),
                  r.get("clicks", BigDecimal.class),
                  r.get("orders", BigDecimal.class),
                  r.get("spend", BigDecimal.class),
                  r.get("sales", BigDecimal.class),
                  r.get("ctr", BigDecimal.class),
                  r.get("cpc", BigDecimal.class),
                  r.get("cvr", BigDecimal.class),
                  r.get("acos", BigDecimal.class),
                  r.get("roas", BigDecimal.class)));
    }

    List<AdCampaignReportTrendSeries> series =
        byCurrency.entrySet().stream()
            .map(e -> new AdCampaignReportTrendSeries(e.getKey(), List.copyOf(e.getValue())))
            .toList();

    String emptyReason = null;
    String emptyNote = "";
    if (rows.isEmpty()) {
      if (!hasSuccessfulSync()) {
        emptyReason = "NO_SYNCED_DATA";
        emptyNote = "本租户还没有任何一次**成功的广告数据同步** —— 趋势图现在是空的，不等于没有投放。";
      } else if (metricsInRange(query.from(), query.to()) > 0) {
        emptyReason = "FILTERED_EMPTY";
        emptyNote = "这个时间区间里确实有广告数据，但被当前的筛选条件全部排除了。";
      } else {
        emptyReason = "NO_METRICS_IN_RANGE";
        emptyNote = "所选时间区间里没有任何投放指标 —— 这次是真的「这段时间没有投放数据」。";
      }
    }

    return new AdCampaignReportTrend(
        query.granularity(),
        series,
        dataAsOf(),
        METRIC_DEFINITIONS,
        emptyReason,
        emptyNote,
        METRIC_DEFINITION_NOTE);
  }

  // ==========================================================================
  // 水位与空态判定
  // ==========================================================================

  /** 数据截至时刻（成功同步的水位；失败同步不推进）。 */
  private OffsetDateTime dataAsOf() {
    return one(
        OffsetDateTime.class,
        "select max(data_as_of) from ad_campaign_sync_run where tenant_id = "
            + TENANT
            + " and data_as_of is not null");
  }

  /** 本租户是否有过一次成功的同步（决定「没投放」还是「没同步」）。 */
  private boolean hasSuccessfulSync() {
    Long n =
        one(
            Long.class,
            "select count(*) from ad_campaign_sync_run where tenant_id = "
                + TENANT
                + " and data_as_of is not null");
    return n != null && n > 0;
  }

  /** 这个时间区间里有没有指标（**不带**平台 / 店铺 / 类型 / 状态筛选）。 */
  private long metricsInRange(LocalDate from, LocalDate to) {
    return count(
        "select count(*) from ad_campaign_daily_metric dm where dm.tenant_id = "
            + TENANT
            + " and dm.metric_date >= ?::date and dm.metric_date <= ?::date",
        from,
        to);
  }

  // ==========================================================================
  // 导出
  // ==========================================================================

  /**
   * 登记一次导出（**留痕**：谁、什么时候、什么筛选条件、导出了多少行）。
   *
   * <p>🔴 留痕里存的是<b>筛选条件</b>而不是报表数据本身：报表随时可以按同样的条件重算，
   * 而数据快照会在下一次同步后与「当时的报表」对不上 —— 留一份反而是假事实。
   */
  public AdCampaignReportExportRow recordExport(Query query) {
    Grouping grouping = grouping(query.dimension());
    int rows = (int) countGroups(query, grouping);
    UUID id = Ids.next();
    OffsetDateTime watermark = dataAsOf();
    sql.execute(
        "insert into ad_campaign_report_export (tenant_id,id,requested_by,granularity,dimension,"
            + " platform_id,shop_code,internal_ad_type,status,date_from,date_to,row_count,"
            + " data_as_of,metric_definition_note,created_at)"
            + " values (?,?,?,?,?,?,?,?,?,?::date,?::date,?,?::timestamptz,?,now())",
        currentTenant(),
        id,
        currentActor(),
        query.granularity(),
        query.dimension(),
        query.platformId(),
        query.shopCode(),
        query.internalAdType(),
        query.status(),
        query.from(),
        query.to(),
        rows,
        watermark,
        METRIC_DEFINITION_NOTE);
    return new AdCampaignReportExportRow(
        id,
        currentActor(),
        query.granularity(),
        query.dimension(),
        query.platformId(),
        query.shopCode(),
        query.internalAdType(),
        query.status(),
        query.from(),
        query.to(),
        rows,
        watermark,
        METRIC_DEFINITION_NOTE,
        OffsetDateTime.now());
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
   * 不可比的比率写「不可比」（不是 0，也不是空）；TACOS 写「不可用」并说明原因。
   */
  public void streamCsv(
      AccessService.Access actor, Query query, Consumer<String> emit, String dimensionLabel) {
    Grouping grouping = grouping(query.dimension());
    Filters filters = buildFilters(query, true);
    int size = 200;
    int offset = 0;

    emit.accept(
        "维度,分组键,分组名,币种,平台,店铺,平台侧活动id,"
            + "花费,曝光,点击,CTR(Σ点击/Σ曝光),CPC(Σ花费/Σ点击),订单,转化率(Σ订单/Σ点击),"
            + "销售额,ACOS(Σ花费/Σ销售额),ROAS(Σ销售额/Σ花费),"
            + "TACOS(广告花费/总销售额),统计起,统计止\n");

    while (true) {
      int at = offset;
      String sqlText =
          "select "
              + grouping.keyExpr()
              + " dimension_key, "
              + grouping.labelExpr()
              + " dimension_label, c.currency currency, "
              + grouping.platformExpr()
              + " platform_name, "
              + grouping.shopExpr()
              + " shop_name, "
              + grouping.externalExpr()
              + " external_campaign_id, "
              + AGGREGATE_COLUMNS
              + FROM_JOINS
              + filters.where()
              + " group by "
              + grouping.groupBy()
              + ", c.currency"
              + " order by c.currency asc, dimension_key asc limit ? offset ?";
      List<Object> binds = new ArrayList<>(filters.binds());
      binds.add(size);
      binds.add(at);

      List<Record> batch =
          access.run(
              actor,
              "channel.operation.read",
              () -> sql.fetch(sqlText, binds.toArray()));
      if (batch.isEmpty()) break;

      StringBuilder sb = new StringBuilder();
      for (Record r : batch) {
        String label = r.get("dimension_label", String.class);
        if ("AD_TYPE".equals(query.dimension()) && label != null) {
          label = AdCampaignService.internalTypeLabel(label);
        }
        List<String> cells = new ArrayList<>();
        cells.add(dimensionLabel);
        cells.add(r.get("dimension_key", String.class));
        cells.add(label);
        cells.add(r.get("currency", String.class));
        cells.add(r.get("platform_name", String.class));
        cells.add(r.get("shop_name", String.class));
        cells.add(r.get("external_campaign_id", String.class));
        cells.add(plain(r.get("spend", BigDecimal.class)));
        cells.add(plain(r.get("impressions", BigDecimal.class)));
        cells.add(plain(r.get("clicks", BigDecimal.class)));
        cells.add(ratio(r.get("ctr", BigDecimal.class)));
        cells.add(ratio(r.get("cpc", BigDecimal.class)));
        cells.add(plain(r.get("orders", BigDecimal.class)));
        cells.add(ratio(r.get("cvr", BigDecimal.class)));
        cells.add(plain(r.get("sales", BigDecimal.class)));
        cells.add(ratio(r.get("acos", BigDecimal.class)));
        cells.add(ratio(r.get("roas", BigDecimal.class)));
        // 🔴 TACOS 没有分母 ⇒ 明确写「不可用」，不写 0、也不写空。
        cells.add("不可用（总销售额口径未接入）");
        cells.add(String.valueOf(r.get("metric_from", LocalDate.class)));
        cells.add(String.valueOf(r.get("metric_to", LocalDate.class)));
        sb.append(String.join(",", cells.stream().map(AdCampaignReportService::csvCell).toList()));
        sb.append('\n');
      }
      emit.accept(sb.toString());
      offset += size;
    }
  }

  /** 导出留痕列表（真分页）。 */
  public AdCampaignReportExportPage exports(int page, int size) {
    int safePage = Math.max(page, 0);
    int safeSize = Math.min(Math.max(size, 1), 200);
    List<AdCampaignReportExportRow> items =
        sql.fetch(
                "select id, requested_by, granularity, dimension, platform_id, shop_code,"
                    + " internal_ad_type, status, date_from, date_to, row_count,"
                    + " data_as_of, metric_definition_note, created_at"
                    + " from ad_campaign_report_export where tenant_id = "
                    + TENANT
                    + " order by created_at desc, id desc limit ? offset ?",
                safeSize,
                safePage * safeSize)
            .stream()
            .map(
                r ->
                    new AdCampaignReportExportRow(
                        r.get("id", UUID.class),
                        r.get("requested_by", UUID.class),
                        r.get("granularity", String.class),
                        r.get("dimension", String.class),
                        r.get("platform_id", UUID.class),
                        r.get("shop_code", String.class),
                        r.get("internal_ad_type", String.class),
                        r.get("status", String.class),
                        r.get("date_from", LocalDate.class),
                        r.get("date_to", LocalDate.class),
                        r.get("row_count", Integer.class),
                        r.get("data_as_of", OffsetDateTime.class),
                        r.get("metric_definition_note", String.class),
                        r.get("created_at", OffsetDateTime.class)))
            .toList();
    long total =
        count("select count(*) from ad_campaign_report_export where tenant_id = " + TENANT);
    return new AdCampaignReportExportPage(items, total, safePage, safeSize);
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

  /** 分组维度的显示名（CSV 的第一列）。 */
  static String dimensionLabel(String dimension) {
    return switch (dimension) {
      case "PLATFORM" -> "按平台";
      case "SHOP" -> "按店铺";
      case "AD_TYPE" -> "按归一后广告类型";
      case "CURRENCY" -> "按币种";
      default -> "按活动";
    };
  }
}
