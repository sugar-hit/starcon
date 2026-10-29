package com.starcon.channel;

import static com.starcon.channel.AdProductReportModels.*;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * 14.3.1.3 商品维度报表。
 *
 * <p>蓝图：「商品维度广告效果：SKU/SPU/Listing 维度的广告花费、广告销售额、总销售额、
 * ACOS、TACOS、广告占比利润。输入：商品维度；时间范围；利润数据（13）。
 * 输出：商品广告报表 + 广告销售占比 + 广告后净利。
 * 关系：上游 = 14.3.1.1、13.4.1.2；下游 = 13.4 利润。
 * <b>广告销售占比（广告单/总单）是判断广告是否带来增量还是抢自然单的关键</b>，
 * 只看广告 ROAS 无法回答这个问题。」
 *
 * <p>本类按职责句逐项落点：
 *
 * <ul>
 *   <li><b>SKU / SPU / Listing 三个维度</b> → {@link #report} 的 {@code dimension}。
 *   <li><b>广告花费 / 广告销售额</b> → {@link #loadGrain}：全部来自上游 14.1.1.1 的
 *       {@code ad_campaign_daily_metric}（V273），本模块<b>不复制任何一列指标，也不建第二套事实表</b>。
 *   <li><b>总销售额 / 净利 / 广告费</b> → {@link AdProductProfitPort}：
 *       跨服务<b>只读 API</b>读 13.4.1.2（reporting 服务），零跨库 SQL / FK / 视图。
 *   <li><b>ACOS</b> → Σ花费 / Σ广告销售额；<b>TACOS</b> → Σ广告花费 / 总销售额；
 *       <b>广告销售占比</b> → 广告销售额 / 总销售额。
 * </ul>
 *
 * <p>🔴 本类最容易做丢的五件事，这里都挡住了：
 *
 * <ol>
 *   <li><b>商品维度是要「分摊」出来的，规则必须固定并公示</b>。上游的广告事实是<b>活动级</b>的
 *       （{@code ad_campaign_daily_metric} 一行 = 一个活动 × 一天），商品维度必须回答
 *       「这笔钱落在哪个商品上」。本类的规则是<b>两级等分</b>（活动 → Listing → SKU），
 *       原文写在 {@link #ATTRIBUTION_NOTE}，随报表一起返回 ——
 *       <b>换算就是编造归因，所以规则必须公示</b>（蓝图 14.3.2.1 同一纪律）。
 *   <li><b>「直接归属」与「等分摊销」分开标记</b>：{@code attributionMode} ∈
 *       DIRECT / ALLOCATED_EVENLY / MIXED。投放一个商品的活动拿到的是<b>平台原值</b>；
 *       投放多个商品的 ⇒ 数字是分摊值，界面必须标出来。
 *   <li><b>归不到商品的广告花费不摊给任何商品</b>：汇总里
 *       {@code campaignTotalSpend}（范围内总额）与 {@code totalSpend}（行合计）的差
 *       就是 {@code unattributedSpend} —— 摊成 0 等于说「这些广告没花钱」。
 *   <li><b>先汇总再相除</b>：ACOS / TACOS / 广告销售占比一律 Σ分子 / Σ分母，
 *       <b>不是</b>各行比率的平均（那是数学错误）。
 *   <li><b>利润归不到唯一商品时不摊</b>：13.4.1.2 的利润事实只到 SKU 粒度。
 *       一个 listing 挂了多个 SKU、或某个 SKU 挂在多个 listing 上 ⇒
 *       SKU 级利润<b>归不到唯一 listing</b> ⇒ 那一行的利润列是<b>空</b>并注明原因，
 *       <b>不摊给任何一个</b>（13.2.2.2 同一纪律）。
 * </ol>
 *
 * <p>🔴 报表查询的性能纪律（照抄 14.3.1.1）：时间范围<b>必填</b>且封顶；
 * 谓词顺序对齐上游索引 {@code ad_campaign_daily_metric_window (tenant_id, metric_date, campaign_id)}；
 * <b>真分页</b>（LIMIT/OFFSET）；<b>汇总单独一条聚合查询</b>（统计全集，不是当前页）；
 * 跨服务调用放在<b>数据库事务之外</b>（不在事务里等 HTTP）。
 */
@Service
public class AdProductReportService {

  private static final String TENANT =
      "nullif(current_setting('app.current_tenant',true),'')::uuid";

  /** 商品维度。 */
  static final List<String> DIMENSIONS = List.of("LISTING", "SKU", "SPU");

  /** 🔴 时间跨度的上限（天）。超过直接拒，不做「悄悄截断」。 */
  static final int MAX_DAYS = 400;

  /**
   * 🔴 SPU 维度的 SKU 候选集上限。
   *
   * <p>SPU 维度要先拿到「SKU → SPU」的归属（catalog 服务）才能把 SKU 归并成 SPU，
   * 而候选集越大、跨服务往返越多 ⇒ 设上限，<b>超出会如实报截断</b>（不假装读完了）。
   */
  static final int MAX_SPU_CANDIDATE_SKUS = 200;

  /**
   * 🔴 利润归集范围的 SKU 上限。
   *
   * <p>汇总的利润侧要覆盖「在范围内的全部 SKU」，候选集无界 ⇒ 设上限，
   * 超出会如实报截断（{@code candidateTruncated}）。
   */
  static final int MAX_PROFIT_SCOPE_SKUS = 2000;

  static final String DEFAULT_DIMENSION = "LISTING";
  static final String DEFAULT_SORT = "SPEND_DESC";

  /** 排序方式 → ORDER BY 子句。**白名单映射**：排序串绝不拼进 SQL。 */
  private static final Map<String, String> SORTS =
      Map.ofEntries(
          Map.entry("SPEND_DESC", "spend desc, dimension_key asc"),
          Map.entry("SPEND_ASC", "spend asc, dimension_key asc"),
          Map.entry("SALES_DESC", "sales desc, dimension_key asc"),
          Map.entry("ACOS_ASC", "acos asc nulls last, dimension_key asc"),
          Map.entry("ORDERS_DESC", "orders desc, dimension_key asc"),
          Map.entry("IMPRESSIONS_DESC", "impressions desc, dimension_key asc"),
          Map.entry("CLICKS_DESC", "clicks desc, dimension_key asc"),
          Map.entry("LABEL_ASC", "dimension_label asc, dimension_key asc"));

  // ==========================================================================
  // 🔴 口径原文：写在返回值里，不写在注释里
  // ==========================================================================

  /**
   * 归因口径原文（**随报表一起返回**，CSV 表头与导出留痕里也写一份）。
   *
   * <p>🔴 规则变了历史报表就不可比 —— 所以规则必须固定、必须公示。
   */
  static final String ATTRIBUTION_NOTE =
      "商品维度的广告数字是**从活动级事实分摊出来的**，分摊规则固定如下（规则变了历史报表不可比）："
          + "① **活动 → Listing**：按该活动**当前**登记的投放商品数等分"
          + "（投放关系取 ad_campaign_group_product，14.1.1.2）。"
          + "投放 1 个商品的活动的数字是**平台原值**（标 DIRECT）；"
          + "投放 N>1 个的活动的数字是**等分摊销值**（标 ALLOCATED_EVENLY）；"
          + "既有直接又有摊销的（标 MIXED）。"
          + "② **Listing → 内部 SKU**：按该 listing **当前**登记的 SKU 变体数等分"
          + "（映射现状取 listing_variant，4.1.1.1）；挂 1 个 SKU 的直接归属，挂 N>1 个的等分摊销。"
          + "③ **SPU 维度** = 该 SPU 下**在报表内**（有广告花费）的 SKU 归并："
          + "广告数字相加，**不是再分摊一次**；SPU 下没有广告花费的 SKU 不在本报表范围内。"
          + "④ **没有投放商品的活动**，它的广告花费与广告销售额**归不到任何商品**："
          + "汇总里单列（campaignTotalSpend − 行合计 = unattributedSpend），**不摊给任何商品，也不摊成 0**。"
          + "⚠️ 投放关系与 SKU 映射都取**当前**登记态（上游没有历史版本）："
          + "改了投放商品或 SKU 映射，历史报表的分配比例会跟着变 —— 这是上游结构决定的，本模块改不了。";

  /** 逐指标口径的一句话说明。 */
  static final String METRIC_DEFINITION_NOTE =
      "ACOS = Σ广告花费 / Σ广告销售额；ROAS = Σ广告销售额 / Σ广告花费；"
          + "TACOS = Σ广告花费 / 总销售额（总销售额含自然单，不是广告销售额）；"
          + "广告销售占比 = 广告销售额 / 总销售额；"
          + "广告后净利 = 总净利 − 广告花费；广告占比利润 = 广告花费 / 总净利。"
          + "全部按「先汇总再相除」算（Σ分子 / Σ分母），**不是**各行比率的平均 —— "
          + "后者是数学错误，会把两条 ACOS 100% 的商品说成整体 50%。"
          + "分母为 0 一律返回空值（不可比），不是 0。"
          + "🔴 不同币种不合并（本仓没有汇率源）：每一行带自己的币种，汇总按币种拆开。";

  /** 「与利润侧同一范围」的口径说明（汇总里要写清 TACOS 的分子到底是哪一段花费）。 */
  static final String PROFIT_SCOPE_NOTE =
      "汇总的 TACOS / 广告销售占比用**与利润侧同一范围**的广告花费与广告销售额做分子"
          + "（scopedSpend / scopedSales）："
          + "SKU / SPU 维度 = 报表里的全部商品；"
          + "LISTING 维度 = **利润能归到唯一 listing 的那些 listing**"
          + "（挂了多个 SKU、或 SKU 还挂在别的 listing 上的，利润归不到唯一 listing ⇒ 不摊，那几行利润列为空）。"
          + "🔴 拿「全量广告花费」除以「部分商品的总销售额」算出来的 TACOS 是没有意义的。";

  /** 数据截至时刻的口径说明。 */
  static final String DATA_AS_OF_NOTE =
      "「数据截至 X 时间」= 各次**成功/部分成功**的广告数据同步里最大的 data_as_of；"
          + "失败的同步**不推进水位**（失败就是没拿到数据）。"
          + "报表里的每一行都来自这批已同步的数据 —— 水位之前的空白是「还没同步到」，不是「没投放」。";

  /** 空态原因。 */
  static final List<String> EMPTY_REASONS =
      List.of("NO_SYNCED_DATA", "NO_METRICS_IN_RANGE", "FILTERED_EMPTY", "NO_PRODUCT_BINDING");

  private final DSLContext sql;
  private final AccessService access;
  private final AdProductProfitPort profitPort;
  private final AdProductCatalogPort catalogPort;

  public AdProductReportService(
      DSLContext sql,
      AccessService access,
      AdProductProfitPort profitPort,
      AdProductCatalogPort catalogPort) {
    this.sql = sql;
    this.access = access;
    this.profitPort = profitPort;
    this.catalogPort = catalogPort;
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

  // ==========================================================================
  // 报表查询条件
  // ==========================================================================

  /**
   * 一次报表查询的全部条件。
   *
   * <p>🔴 {@code from} / {@code to} **必填**：没有时间范围的报表就是全表扫。不给区间就报错。
   */
  public record Query(
      LocalDate from,
      LocalDate to,
      String dimension,
      String sort,
      UUID platformId,
      String shopCode,
      String internalAdType,
      String status,
      int page,
      int size) {}

  /** 归一化并校验查询条件（非法值直接 400，不静默回落）。 */
  public Query normalize(
      LocalDate from,
      LocalDate to,
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
    long days = ChronoUnit.DAYS.between(from, to) + 1;
    if (days > MAX_DAYS) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "时间跨度过大：一次最多统计 " + MAX_DAYS + " 天，当前 " + days + " 天");
    }
    String dim = text(dimension).toUpperCase(Locale.ROOT);
    if (dim.isEmpty()) dim = DEFAULT_DIMENSION;
    if (!DIMENSIONS.contains(dim)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "商品维度只能是 LISTING / SKU / SPU");
    }
    String sortKey = text(sort).toUpperCase(Locale.ROOT);
    if (sortKey.isEmpty()) sortKey = DEFAULT_SORT;
    if (!SORTS.containsKey(sortKey)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "排序方式不在白名单里");
    }
    return new Query(
        from,
        to,
        dim,
        sortKey,
        platformId,
        blankToNull(shopCode),
        blankToNull(internalAdType),
        blankToNull(status),
        Math.max(page, 0),
        Math.min(Math.max(size, 1), 200));
  }

  // ==========================================================================
  // SQL 片段
  // ==========================================================================

  /** 一条筛选条件（where 子句 + 绑定值）。 */
  private record Filters(String where, List<Object> binds) {}

  /**
   * 构造活动的筛选条件。
   *
   * <p>🔴 谓词顺序对齐上游索引 {@code ad_campaign_daily_metric_window (tenant_id, metric_date, campaign_id)}：
   * **先 tenant_id，再 metric_date 范围**。
   */
  private Filters buildFilters(Query query) {
    StringBuilder where =
        new StringBuilder(
            " where dm.tenant_id = "
                + TENANT
                + " and dm.metric_date >= ?::date and dm.metric_date <= ?::date");
    List<Object> binds = new ArrayList<>();
    binds.add(query.from());
    binds.add(query.to());
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
    return new Filters(where.toString(), binds);
  }

  /**
   * 全套 CTE：活动每日事实 → 活动分摊 → Listing 指标 → SKU 指标。
   *
   * <p>🔴 <b>「先汇总再相除」在这里落地</b>：{@code campaign_alloc} 先把活动在窗口内
   * 的花费 / 销售额**加起来**，再除以投放商品数；{@code listing_metrics} 把各活动分摊额
   * **加起来**；比率一律在最后一步现算（Σ分子 / Σ分母）。
   */
  private String ctes(Query query) {
    Filters filters = buildFilters(query);
    return """
        campaign_daily as (
          select dm.campaign_id, dm.metric_date, c.currency,
                 dm.impressions, dm.clicks, dm.orders, dm.spend, dm.sales
            from ad_campaign_daily_metric dm
            join ad_campaign c on c.tenant_id = dm.tenant_id and c.id = dm.campaign_id
        %s
        ),
        campaign_targets as (
          select gp.campaign_id, count(distinct gp.listing_id) listing_count
            from ad_campaign_group_product gp
           where gp.tenant_id = %s
           group by gp.campaign_id
        ),
        campaign_alloc as (
          select cd.campaign_id, cd.currency,
                 sum(cd.impressions) impressions, sum(cd.clicks) clicks, sum(cd.orders) orders,
                 sum(cd.spend) spend, sum(cd.sales) sales,
                 min(cd.metric_date) metric_from, max(cd.metric_date) metric_to,
                 coalesce(ct.listing_count, 0) listing_count
            from campaign_daily cd
            left join campaign_targets ct on ct.campaign_id = cd.campaign_id
           group by cd.campaign_id, cd.currency, coalesce(ct.listing_count, 0)
        ),
        listing_metrics as (
          select gp.listing_id, ca.currency,
                 sum(ca.impressions / nullif(ca.listing_count, 0)) impressions,
                 sum(ca.clicks / nullif(ca.listing_count, 0)) clicks,
                 sum(ca.orders / nullif(ca.listing_count, 0)) orders,
                 sum(ca.spend / nullif(ca.listing_count, 0)) spend,
                 sum(ca.sales / nullif(ca.listing_count, 0)) sales,
                 min(ca.metric_from) metric_from, max(ca.metric_to) metric_to,
                 count(distinct ca.campaign_id) campaign_count,
                 count(distinct ca.campaign_id) filter (where ca.listing_count = 1) direct_campaign_count,
                 case when count(distinct ca.campaign_id) = count(distinct ca.campaign_id) filter (where ca.listing_count = 1)
                        then 'DIRECT'
                      when count(distinct ca.campaign_id) filter (where ca.listing_count = 1) = 0
                        then 'ALLOCATED_EVENLY'
                      else 'MIXED' end listing_mode
            from campaign_alloc ca
            join ad_campaign_group_product gp
              on gp.tenant_id = %s and gp.campaign_id = ca.campaign_id
           group by gp.listing_id, ca.currency
        ),
        listing_sku_map as (
          select distinct lv.listing_id, lv.internal_sku_id
            from listing_variant lv
           where lv.tenant_id = %s and lv.internal_sku_id is not null
        ),
        sku_listing_count as (
          select internal_sku_id, count(distinct listing_id) listing_count
            from listing_sku_map
           group by internal_sku_id
        ),
        listing_sku_info as (
          select m.listing_id, count(*) sku_count,
                 count(*) filter (where sl.listing_count = 1) unique_sku_count
            from listing_sku_map m
            join sku_listing_count sl on sl.internal_sku_id = m.internal_sku_id
           group by m.listing_id
        ),
        sku_metrics as (
          select lsm.internal_sku_id sku_id, lm.currency,
                 sum(lm.impressions / nullif(lsi.sku_count, 0)) impressions,
                 sum(lm.clicks / nullif(lsi.sku_count, 0)) clicks,
                 sum(lm.orders / nullif(lsi.sku_count, 0)) orders,
                 sum(lm.spend / nullif(lsi.sku_count, 0)) spend,
                 sum(lm.sales / nullif(lsi.sku_count, 0)) sales,
                 min(lm.metric_from) metric_from, max(lm.metric_to) metric_to,
                 count(distinct lm.listing_id) listing_count,
                 count(distinct lm.listing_id) filter (where lm.listing_mode = 'DIRECT') direct_listings,
                 count(distinct lm.listing_id) filter (where lm.listing_mode = 'ALLOCATED_EVENLY')
                   allocated_listings,
                 count(distinct lm.listing_id) filter (where lm.listing_mode = 'MIXED') mixed_listings,
                 count(distinct lm.listing_id) filter (where lsi.sku_count = 1) single_sku_listings
            from listing_metrics lm
            join listing_sku_map lsm on lsm.listing_id = lm.listing_id
            join listing_sku_info lsi on lsi.listing_id = lm.listing_id
           group by lsm.internal_sku_id, lm.currency
        )
        """
        .formatted(filters.where(), TENANT, TENANT, TENANT);
  }

  /** LISTING 维度的行（商品身份 + 广告侧指标 + 归因计数 + 利润归属判定）。 */
  private static final String LISTING_CTE =
      """
       listing_rows as (
       select lm.listing_id dimension_key, lm.currency currency,
              round(lm.impressions, 6) impressions, round(lm.clicks, 6) clicks,
              round(lm.orders, 6) orders, round(lm.spend, 4) spend, round(lm.sales, 4) sales,
              case when lm.impressions > 0 then round(lm.clicks / lm.impressions, 6) end ctr,
              case when lm.clicks > 0 then round(lm.spend / lm.clicks, 6) end cpc,
              case when lm.clicks > 0 then round(lm.orders / lm.clicks, 6) end cvr,
              case when lm.sales > 0 then round(lm.spend / lm.sales, 6) end acos,
              lm.metric_from metric_from, lm.metric_to metric_to,
              lm.campaign_count campaign_count, lm.direct_campaign_count direct_campaign_count,
              coalesce(lsi.sku_count, 0) sku_count, coalesce(lsi.unique_sku_count, 0) unique_sku_count,
              coalesce(l.external_item_id, '') external_item_id, coalesce(l.title, '') dimension_label,
              coalesce(s.name, '') shop_name, coalesce(pf.name, '') platform_name,
              (select lsm.internal_sku_id from listing_sku_map lsm
                where lsm.listing_id = lm.listing_id limit 1) sku_id
         from listing_metrics lm
         left join listing l on l.tenant_id = %s and l.id = lm.listing_id
         left join listing_sku_info lsi on lsi.listing_id = lm.listing_id
         left join shop s on s.tenant_id = %s and s.id = l.shop_id
         left join platform pf on pf.id = s.platform_id
       )
      """;

  /** SKU 维度的行。 */
  private static final String SKU_CTE =
      """
       sku_rows as (
       select sm.sku_id dimension_key, sm.sku_id sku_id, sm.currency currency,
              round(sm.impressions, 6) impressions, round(sm.clicks, 6) clicks,
              round(sm.orders, 6) orders, round(sm.spend, 4) spend, round(sm.sales, 4) sales,
              case when sm.impressions > 0 then round(sm.clicks / sm.impressions, 6) end ctr,
              case when sm.clicks > 0 then round(sm.spend / sm.clicks, 6) end cpc,
              case when sm.clicks > 0 then round(sm.orders / sm.clicks, 6) end cvr,
              case when sm.sales > 0 then round(sm.spend / sm.sales, 6) end acos,
              sm.metric_from metric_from, sm.metric_to metric_to,
              sm.listing_count source_listing_count, sm.direct_listings direct_listings,
              sm.allocated_listings allocated_listings, sm.mixed_listings mixed_listings,
              sm.single_sku_listings single_sku_listings
         from sku_metrics sm
       )
      """;

  /** 该维度的粒度 CTE（LISTING → listing_rows；SKU / SPU → sku_rows）。 */
  private String grainCte(String dimension) {
    return "LISTING".equals(dimension)
        ? LISTING_CTE.formatted(TENANT, TENANT)
        : SKU_CTE;
  }

  /** 该维度的粒度 CTE 名字（外层查询要按它取数）。 */
  private static String grainName(String dimension) {
    return "LISTING".equals(dimension) ? "listing_rows" : "sku_rows";
  }

  // ==========================================================================
  // 内部数据结构
  // ==========================================================================

  /**
   * 一个分组 × 一个币种（LISTING / SKU / SPU 三种粒度共用）。
   *
   * <p>🔴 它是**服务层内部**的结构：DB 行 → Grain → 报表行。
   * SPU 维度要把多个 SKU 归并成一个 SPU ⇒ Grain 要能装下「一组 SKU」。
   */
  private static final class Grain {
    String dimensionKey = "";
    String dimensionLabel = "";
    String currency = "";
    String platformName = "";
    String shopName = "";
    String externalItemId = "";
    String listingId = "";
    String listingTitle = "";
    UUID skuId;
    List<UUID> skuIds = List.of();
    BigDecimal impressions = BigDecimal.ZERO;
    BigDecimal clicks = BigDecimal.ZERO;
    BigDecimal orders = BigDecimal.ZERO;
    BigDecimal spend = BigDecimal.ZERO;
    BigDecimal sales = BigDecimal.ZERO;
    BigDecimal ctr;
    BigDecimal cpc;
    BigDecimal cvr;
    BigDecimal acos;
    LocalDate metricFrom;
    LocalDate metricTo;
    long campaignCount;
    long directCampaignCount;
    long listingCount;
    long directListings;
    long allocatedListings;
    long mixedListings;
    long singleSkuListings;
    long skuCount;
    long uniqueSkuCount;
  }

  /** 广告侧的一份结果（还没拼利润侧）。 */
  private record AdSide(
      List<Grain> rows,
      long total,
      List<AdSideSummary> summaries,
      List<ProfitScopeRow> profitScope,
      boolean scopeTruncated) {}

  /** 广告侧的一个币种汇总（还没拼利润侧）。 */
  private record AdSideSummary(
      String currency,
      long groupCount,
      BigDecimal impressions,
      BigDecimal clicks,
      BigDecimal orders,
      BigDecimal spend,
      BigDecimal sales,
      LocalDate metricFrom,
      LocalDate metricTo) {}

  /** 利润归集范围里的一行（一个 SKU × 一个币种，附它在广告侧的花费 / 销售额）。 */
  private record ProfitScopeRow(
      UUID skuId, String currency, BigDecimal spend, BigDecimal sales) {}

  /** 广告侧的「范围内总额」（活动级），用来和行合计对账。 */
  private record CampaignTotal(
      String currency,
      BigDecimal impressions,
      BigDecimal clicks,
      BigDecimal orders,
      BigDecimal spend,
      BigDecimal sales) {}

  // ==========================================================================
  // 能力声明
  // ==========================================================================

  public AdProductReportCapabilities capabilities() {
    return new AdProductReportCapabilities(
        List.of(
            new AdProductCapabilityDimension(
                "listing_dimension",
                "Listing（平台商品）维度广告效果",
                true,
                "ad_campaign_daily_metric × ad_campaign_group_product × listing（V273 / V277 / V171）",
                "",
                "没有它就没有「投的是哪个商品」的落点，13.3.1.1 的商品维度 ACOS 也无处可算"),
            new AdProductCapabilityDimension(
                "sku_dimension",
                "SKU（内部商品）维度广告效果",
                true,
                "listing_variant（V171）+ catalog 的 SKU 编码（授权只读 API）",
                "",
                "运营按 SKU 看效果时只能自己换算，换算就会和报表对不上"),
            new AdProductCapabilityDimension(
                "spu_dimension",
                "SPU（标准产品单元）维度广告效果",
                true,
                "catalog 的 SKU → SPU 归属（授权只读 API）",
                "",
                "同一款产品在不同平台 / 店铺的投放效果没法定在一起看"),
            new AdProductCapabilityDimension(
                "attribution",
                "活动级广告事实到商品维度的**分摊规则**（固定并公示）",
                true,
                "本模块的服务层分摊（见 attributionNote）",
                "",
                "不公示规则 ⇒ 同一张报表两个人算出两个数，广告与财务对账必然吵架"),
            new AdProductCapabilityDimension(
                "profit_side",
                "利润侧（总销售额 / 净利 / 广告费）",
                true,
                "13.4.1.2 SKU 利润（reporting 服务，授权只读 API）",
                "",
                "没有总销售额就没有 TACOS，也没有广告销售占比 —— 只看广告 ROAS 答不了"
                    + "「广告是带来增量还是抢自然单」这个问题"),
            new AdProductCapabilityDimension(
                "ad_order_share",
                "广告销售占比的**单量口径**（广告单数 ÷ 总单数）",
                false,
                "—",
                "13.4.1.1 利润事实（只到金额与件数，没有 SKU 级订单数）",
                "本模块给的是**金额口径**的广告销售占比（广告销售额 ÷ 总销售额）。"
                    + "单量口径在 SKU 维度**没有分母**（利润事实给的是金额与件数，不是订单数）⇒ "
                    + "不编一个分母出来。广告带来的**订单数**本身是可看的（广告侧事实里有）。"),
            new AdProductCapabilityDimension(
                "attribution_14_3_2_1",
                "广告归因（把花费归到订单 / SKU，算增量效果）",
                false,
                "—",
                "14.3.2.1 归因分析",
                "本模块给的是**投放关系分摊**（按投放商品数等分），**不是归因** —— "
                    + "归因要看订单与广告的匹配规则，规则归 14.3.2.1 固定并公示"),
            new AdProductCapabilityDimension(
                "multi_currency_unification",
                "跨币种统一口径（把不同币种换算成同一货币再汇总）",
                false,
                "—",
                "汇率源（本仓没有汇率表，也没有汇率同步器）",
                "报表按币种拆开：不同币种的花费 / 销售额不会被加成一个假总数，"
                    + "代价是跨币种的横向比较暂时做不了")),
        METRIC_DEFINITIONS(true, ""),
        DIMENSIONS,
        List.copyOf(SORTS.keySet()),
        EMPTY_REASONS,
        ATTRIBUTION_NOTE,
        METRIC_DEFINITION_NOTE,
        DATA_AS_OF_NOTE,
        "上游 = 14.1.1.1 广告活动列表（已交付）：ad_campaign_daily_metric / ad_campaign（V273）；"
            + "14.1.1.2 活动详情（已交付）：ad_campaign_group_product（V277）；"
            + "4.1.1.1 Listing 列表（已交付）：listing / listing_variant（V171）；"
            + "13.4.1.2 SKU 利润（已交付，reporting 服务，**只经授权只读 API**）；"
            + "03 商品主数据（catalog 服务，**只经授权只读 API**：SKU 编码 / SPU 归属）。",
        "下游 = 13.4 利润（广告后净利与广告占比利润回流）；5 定价。"
            + "🔴 本模块**不**做归因（14.3.2.1）、**不**做跨币种换算（没有汇率源）、"
            + "**不**给单量口径的广告销售占比（13.4.1.1 的利润事实没有 SKU 级订单数）—— "
            + "这三件事都在能力声明里显式标 available=false，不用假数字糊过去。");
  }

  /**
   * 逐指标口径定义 —— **表头就是从这里渲染的**。
   *
   * @param profitAvailable 利润上游这次读到了没有（false ⇒ 利润侧的指标全部 available=false）
   */
  static List<AdProductMetricDefinition> METRIC_DEFINITIONS(
      boolean profitAvailable, String profitUnavailableReason) {
    String profitSource = "13.4.1.2 SKU 利润（reporting 服务，授权只读 API）";
    String profitReason =
        profitUnavailableReason.isBlank()
            ? "上游 13.4.1.2 这次没读到利润事实。"
            : profitUnavailableReason;
    return List.of(
        new AdProductMetricDefinition(
            "spend",
            "广告花费",
            true,
            "广告花费 = Σ（活动当日消耗 ÷ 该活动的投放商品数）",
            "ad_campaign_daily_metric.spend（V273）经两级等分",
            "",
            ""),
        new AdProductMetricDefinition(
            "sales",
            "广告销售额",
            true,
            "广告销售额 = Σ（活动当日广告销售额 ÷ 该活动的投放商品数）",
            "ad_campaign_daily_metric.sales（V273）经两级等分",
            "",
            ""),
        new AdProductMetricDefinition(
            "impressions",
            "曝光",
            true,
            "曝光 = Σ（活动当日曝光 ÷ 该活动的投放商品数）",
            "ad_campaign_daily_metric.impressions（V273）经两级等分",
            "",
            ""),
        new AdProductMetricDefinition(
            "clicks",
            "点击",
            true,
            "点击 = Σ（活动当日点击 ÷ 该活动的投放商品数）",
            "ad_campaign_daily_metric.clicks（V273）经两级等分",
            "",
            ""),
        new AdProductMetricDefinition(
            "orders",
            "广告订单数",
            true,
            "广告订单数 = Σ（活动当日广告带来的订单 ÷ 该活动的投放商品数）",
            "ad_campaign_daily_metric.orders（V273）经两级等分",
            "",
            ""),
        new AdProductMetricDefinition(
            "ctr",
            "CTR 点击率",
            true,
            "CTR = Σ点击 / Σ曝光",
            "V273 聚合（本服务层现算）",
            "",
            ""),
        new AdProductMetricDefinition(
            "cpc",
            "CPC 单次点击成本",
            true,
            "CPC = Σ花费 / Σ点击",
            "V273 聚合（本服务层现算）",
            "",
            ""),
        new AdProductMetricDefinition(
            "cvr",
            "转化率",
            true,
            "转化率 = Σ订单 / Σ点击",
            "V273 聚合（本服务层现算）",
            "",
            ""),
        new AdProductMetricDefinition(
            "acos",
            "ACOS",
            true,
            "ACOS = Σ广告花费 / Σ广告销售额",
            "V273 聚合（本服务层现算）",
            "",
            ""),
        new AdProductMetricDefinition(
            "roas",
            "ROAS",
            true,
            "ROAS = Σ广告销售额 / Σ广告花费",
            "V273 聚合（本服务层现算）",
            "",
            ""),
        new AdProductMetricDefinition(
            "total_sales",
            "总销售额",
            profitAvailable,
            "总销售额 = Σ（该商品名下 SKU 的单品收入，13.4.1.2 利润口径）",
            profitSource,
            profitAvailable ? "" : "13.4.1.2（reporting 服务）",
            profitAvailable
                ? ""
                : profitReason
                    + " ⇒ 总销售额这一格取不到（**不是 0**：0 会被读成「这个商品没卖出去」）。"),
        new AdProductMetricDefinition(
            "tacos",
            "TACOS",
            profitAvailable,
            "TACOS = Σ广告花费 / 总销售额（总销售额含自然单，不是广告销售额）",
            profitAvailable ? "V273 聚合 + " + profitSource : "—",
            profitAvailable ? "" : "13.4.1.2（reporting 服务）",
            profitAvailable
                ? ""
                : "TACOS 没有分母 ⇒ 不出数值。不用 0 当分母，也不用广告销售额顶替总销售额"
                    + "（那算出来的是 ACOS 不是 TACOS）。"),
        new AdProductMetricDefinition(
            "ad_sales_share",
            "广告销售占比",
            profitAvailable,
            "广告销售占比 = 广告销售额 / 总销售额（判断广告是带来增量还是抢自然单的关键）",
            profitAvailable ? "V273 聚合 + " + profitSource : "—",
            profitAvailable ? "" : "13.4.1.2（reporting 服务）",
            profitAvailable
                ? ""
                : "分母（总销售额）取不到 ⇒ 这一格取不到。"
                    + "只看广告 ROAS 答不了「广告是带来增量还是抢自然单」这个问题。"),
        new AdProductMetricDefinition(
            "net_profit",
            "总净利",
            profitAvailable,
            "总净利 = 13.4.1.2 的净利（收入 − 成本；成本项缺项时上游给 null）",
            profitSource,
            profitAvailable ? "" : "13.4.1.2（reporting 服务）",
            profitAvailable ? "" : profitReason + " ⇒ 总净利这一格取不到。"),
        new AdProductMetricDefinition(
            "ad_after_net_profit",
            "广告后净利",
            profitAvailable,
            "广告后净利 = 总净利 − 广告花费（任一项缺失时为 null）",
            profitAvailable ? "13.4.1.2 + V273" : "—",
            profitAvailable ? "" : "13.4.1.2（reporting 服务）",
            profitAvailable
                ? ""
                : "总净利取不到 ⇒ 广告后净利算不出来（**不是 0**：0 会被读成「广告后不赚钱」）。"),
        new AdProductMetricDefinition(
            "ad_profit_share",
            "广告占比利润",
            profitAvailable,
            "广告占比利润 = 广告花费 / 总净利（广告成本对利润的侵蚀；分母 ≤ 0 时不可比）",
            profitAvailable ? "13.4.1.2 + V273" : "—",
            profitAvailable ? "" : "13.4.1.2（reporting 服务）",
            profitAvailable ? "" : profitReason + " ⇒ 广告占比利润这一格取不到。"));
  }

  // ==========================================================================
  // 选项
  // ==========================================================================

  public AdProductReportOptions options() {
    List<AdProductReportOption> platforms =
        sql.fetch("select id, code, name from platform order by position, code")
            .stream()
            .map(r -> new AdProductReportOption(r.get("code", String.class), r.get("name", String.class)))
            .toList();

    List<AdProductReportOption> shops =
        sql.fetch("select code, name from shop where tenant_id = " + TENANT + " order by code")
            .stream()
            .map(r -> new AdProductReportOption(r.get("code", String.class), r.get("name", String.class)))
            .toList();

    List<AdProductReportOption> dimensions =
        List.of(
            new AdProductReportOption("LISTING", "按平台商品（Listing）"),
            new AdProductReportOption("SKU", "按内部 SKU"),
            new AdProductReportOption("SPU", "按标准产品单元（SPU）"));

    List<AdProductReportOption> types =
        List.of(
                "AUTO_DISCOVERY",
                "SPONSORED_BRAND",
                "SPONSORED_DISPLAY",
                "BRAND_AD",
                "DSP_PROGRAMMATIC",
                "PROMOTION_EVENT",
                "UNMAPPED")
            .stream()
            .map(t -> new AdProductReportOption(t, AdCampaignService.internalTypeLabel(t)))
            .toList();

    List<AdProductReportOption> statuses =
        List.of("ENABLED", "PAUSED", "ENDED", "ARCHIVED", "PENDING_REVIEW", "UNKNOWN")
            .stream()
            .map(s -> new AdProductReportOption(s, AdCampaignService.statusLabel(s)))
            .toList();

    List<AdProductReportOption> sorts =
        List.of(
            new AdProductReportOption("SPEND_DESC", "广告花费高到低"),
            new AdProductReportOption("SPEND_ASC", "广告花费低到高"),
            new AdProductReportOption("SALES_DESC", "广告销售额高到低"),
            new AdProductReportOption("ACOS_ASC", "ACOS 低到高（不可比的排最后）"),
            new AdProductReportOption("ORDERS_DESC", "广告订单数高到低"),
            new AdProductReportOption("IMPRESSIONS_DESC", "曝光高到低"),
            new AdProductReportOption("CLICKS_DESC", "点击高到低"),
            new AdProductReportOption("LABEL_ASC", "名称"));

    return new AdProductReportOptions(
        dimensions,
        platforms,
        shops,
        types,
        statuses,
        sorts,
        METRIC_DEFINITIONS(true, ""),
        METRIC_DEFINITION_NOTE,
        ATTRIBUTION_NOTE,
        "时间范围必填（from / to 都是必填项），一次最多统计 "
            + MAX_DAYS
            + " 天。跨度过大直接报错，不做悄悄截断。");
  }

  // ==========================================================================
  // 报表
  // ==========================================================================

  /**
   * 报表。
   *
   * @param actor 已认证成员（用于在事务里绑定租户上下文）
   * @param bearer 调用方自己的会话（转发给跨服务只读端口，**不传租户号**）
   */
  public AdProductReportPage report(Query query, AccessService.Access actor, String bearer) {
    // ---- 第一步：广告侧（数据库事务内）----
    AdSide adSide = access.run(actor, "channel.operation.read", () -> loadGrain(query));
    List<CampaignTotal> campaignTotals =
        access.run(actor, "channel.operation.read", () -> loadCampaignTotals(query));

    // ---- 第二步：跨服务只读（**事务之外**：不在事务里等 HTTP）----
    AdProductProfitPort.ProfitSnapshot profit =
        profitPort.fetch(query.from(), query.to(), bearer);
    AdProductCatalogPort.CatalogListSnapshot catalog = catalogPort.listIdentities(bearer);

    // ---- 第三步：SPU 维度要在服务层把 SKU 归并成 SPU ----
    List<Grain> rows = adSide.rows();
    long total = adSide.total();
    List<AdSideSummary> adSummaries = adSide.summaries();
    boolean truncated = adSide.scopeTruncated() || catalog.truncated();
    if ("SPU".equals(query.dimension())) {
      SpuMerge merged = mergeToSpu(rows, catalog, query.sort());
      List<Grain> all = merged.rows();
      total = all.size();
      // 🔴 SPU 的分页在服务层做：归并必须先知道 SKU → SPU 的归属，而那要问 catalog。
      int from = query.page() * query.size();
      int to = Math.min(from + query.size(), all.size());
      rows = from >= all.size() ? List.of() : new ArrayList<>(all.subList(from, to));
      adSummaries = summarizeGrains(all);
      truncated = truncated || merged.truncated();
    }

    List<AdProductReportRow> items = buildRows(query, rows, catalog, profit);
    List<AdProductReportSummary> summaries =
        buildSummaries(query, adSummaries, adSide, campaignTotals, catalog, profit);

    OffsetDateTime dataAsOf = access.run(actor, "channel.operation.read", this::dataAsOf);

    StringBuilder truncation = new StringBuilder();
    if (adSide.scopeTruncated()) {
      truncation
          .append("利润归集范围的 SKU 超过本次上限（")
          .append(MAX_PROFIT_SCOPE_SKUS)
          .append(" 个），超出的部分没进汇总；");
    }
    if (catalog.truncated()) {
      truncation.append(catalog.unavailableReason());
    }
    String truncationText = truncation.toString();

    String emptyReason = null;
    String emptyNote = "";
    if (total == 0) {
      if (!hasSuccessfulSync()) {
        emptyReason = "NO_SYNCED_DATA";
        emptyNote =
            "本租户还没有任何一次**成功的广告数据同步**。"
                + "这不等于「这段时间没有投放」—— 只是还没同步到数据。";
      } else if (metricsInRange(query) > 0) {
        emptyReason = "FILTERED_EMPTY";
        emptyNote = "这个时间区间里确实有广告数据，但被当前的筛选条件全部排除了。放宽条件再试。";
      } else if (adSide.summaries().isEmpty()) {
        emptyReason = "NO_METRICS_IN_RANGE";
        emptyNote =
            "广告数据同步成功过，但所选时间区间里没有任何投放指标 —— 这次是真的「这段时间没有投放数据」。";
      } else {
        emptyReason = "NO_PRODUCT_BINDING";
        emptyNote =
            "这个时间区间里确实有广告数据，但**没有登记投放商品**（活动 ↔ listing 的投放关系为空）"
                + " —— 广告花费归不到任何商品上。";
      }
    }

    return new AdProductReportPage(
        items,
        total,
        query.page(),
        query.size(),
        query.dimension(),
        summaries,
        dataAsOf,
        DATA_AS_OF_NOTE,
        METRIC_DEFINITIONS(profit.available(), profit.unavailableReason()),
        emptyReason,
        emptyNote,
        METRIC_DEFINITION_NOTE,
        ATTRIBUTION_NOTE,
        profit.available(),
        profit.unavailableReason(),
        catalog.available(),
        catalog.unavailableReason(),
        truncated,
        truncationText);
  }

  /** SPU 归并的结果。 */
  private record SpuMerge(List<Grain> rows, long total, boolean truncated) {}

  /**
   * 把 SKU 粒度的行归并成 SPU 粒度。
   *
   * <p>🔴 <b>是「相加」，不是「再分摊一次」</b>：SPU 名下各 SKU 的广告数字相加就是该 SPU 的
   * 广告数字（分摊已经在 SKU 那一级做过了，再分一次就重复摊销了）。
   *
   * <p>🔴 <b>SPU 下没有广告花费的 SKU 不在本报表范围内</b>（候选集只含有广告花费的 SKU）
   * ⇒ 该 SPU 的「总销售额」= 它名下**在报表内**的 SKU 的单品收入之和。
   */
  private SpuMerge mergeToSpu(
      List<Grain> skuRows, AdProductCatalogPort.CatalogListSnapshot catalog, String sortKey) {
    Map<String, Grain> merged = new LinkedHashMap<>();
    boolean truncated = false;
    for (Grain sku : skuRows) {
      AdProductCatalogPort.SkuIdentity identity =
          sku.skuId == null ? null : catalog.bySkuId().get(sku.skuId);
      String spuCode = identity == null ? "" : identity.spuCode();
      if (spuCode == null || spuCode.isBlank()) {
        // 🔴 商品主数据里读不到这个 SKU 的 SPU 归属 ⇒ 归不到任何 SPU，
        //   **如实计入未归属**，不塞进一个空 SPU 里（那会造出一个不存在的商品）。
        truncated = true;
        continue;
      }
      String key = spuCode + "|" + sku.currency;
      Grain target = merged.get(key);
      if (target == null) {
        target = new Grain();
        target.dimensionKey = spuCode;
        target.dimensionLabel = spuCode;
        target.currency = sku.currency;
        merged.put(key, target);
      }
      target.impressions = nz(target.impressions).add(nz(sku.impressions));
      target.clicks = nz(target.clicks).add(nz(sku.clicks));
      target.orders = nz(target.orders).add(nz(sku.orders));
      target.spend = nz(target.spend).add(nz(sku.spend));
      target.sales = nz(target.sales).add(nz(sku.sales));
      target.listingCount += sku.listingCount;
      target.directListings += sku.directListings;
      target.allocatedListings += sku.allocatedListings;
      target.mixedListings += sku.mixedListings;
      target.singleSkuListings += sku.singleSkuListings;
      target.skuIds = appendUuid(target.skuIds, sku.skuId);
      target.metricFrom = minDate(target.metricFrom, sku.metricFrom);
      target.metricTo = maxDate(target.metricTo, sku.metricTo);
    }
    List<Grain> rows = new ArrayList<>(merged.values());
    for (Grain g : rows) {
      g.ctr = divide(g.clicks, g.impressions);
      g.cpc = divide(g.spend, g.clicks);
      g.cvr = divide(g.orders, g.clicks);
      g.acos = divide(g.spend, g.sales);
    }
    sortGrains(rows, sortKey);
    long total = rows.size();
    return new SpuMerge(rows, total, truncated);
  }

  /** SPU 归并后的排序（与 SQL 排序白名单同口径）。 */
  private static void sortGrains(List<Grain> rows, String sortKey) {
    java.util.Comparator<Grain> bySpendDesc =
        java.util.Comparator.comparing((Grain g) -> g.spend, java.util.Comparator.reverseOrder());
    java.util.Comparator<Grain> bySpendAsc = java.util.Comparator.comparing(g -> g.spend);
    java.util.Comparator<Grain> bySalesDesc =
        java.util.Comparator.comparing((Grain g) -> g.sales, java.util.Comparator.reverseOrder());
    java.util.Comparator<Grain> byOrdersDesc =
        java.util.Comparator.comparing((Grain g) -> g.orders, java.util.Comparator.reverseOrder());
    java.util.Comparator<Grain> byImpressionsDesc =
        java.util.Comparator.comparing((Grain g) -> g.impressions, java.util.Comparator.reverseOrder());
    java.util.Comparator<Grain> byClicksDesc =
        java.util.Comparator.comparing((Grain g) -> g.clicks, java.util.Comparator.reverseOrder());
    java.util.Comparator<Grain> byAcosAsc =
        java.util.Comparator.comparing(
            (Grain g) -> g.acos == null ? BigDecimal.ONE : g.acos,
            java.util.Comparator.naturalOrder());
    java.util.Comparator<Grain> byLabel =
        java.util.Comparator.comparing(g -> g.dimensionLabel == null ? "" : g.dimensionLabel);
    java.util.Comparator<Grain> byKey = java.util.Comparator.comparing(g -> g.dimensionKey);
    java.util.Comparator<Grain> comparator =
        switch (sortKey) {
          case "SPEND_ASC" -> bySpendAsc.thenComparing(byKey);
          case "SALES_DESC" -> bySalesDesc.thenComparing(byKey);
          case "ORDERS_DESC" -> byOrdersDesc.thenComparing(byKey);
          case "IMPRESSIONS_DESC" -> byImpressionsDesc.thenComparing(byKey);
          case "CLICKS_DESC" -> byClicksDesc.thenComparing(byKey);
          case "ACOS_ASC" -> byAcosAsc.thenComparing(byKey);
          case "LABEL_ASC" -> byLabel.thenComparing(byKey);
          default -> bySpendDesc.thenComparing(byKey);
        };
    rows.sort(comparator);
  }

  /** 从归并后的粒度数据算广告侧汇总（SPU 维度用；先汇总再相除）。 */
  private static List<AdSideSummary> summarizeGrains(List<Grain> rows) {
    Map<String, List<Grain>> byCurrency = new LinkedHashMap<>();
    for (Grain g : rows) {
      byCurrency.computeIfAbsent(g.currency, k -> new ArrayList<>()).add(g);
    }
    List<AdSideSummary> summaries = new ArrayList<>();
    for (Map.Entry<String, List<Grain>> entry : byCurrency.entrySet()) {
      BigDecimal impressions = BigDecimal.ZERO;
      BigDecimal clicks = BigDecimal.ZERO;
      BigDecimal orders = BigDecimal.ZERO;
      BigDecimal spend = BigDecimal.ZERO;
      BigDecimal sales = BigDecimal.ZERO;
      LocalDate from = null;
      LocalDate to = null;
      for (Grain g : entry.getValue()) {
        impressions = impressions.add(nz(g.impressions));
        clicks = clicks.add(nz(g.clicks));
        orders = orders.add(nz(g.orders));
        spend = spend.add(nz(g.spend));
        sales = sales.add(nz(g.sales));
        from = minDate(from, g.metricFrom);
        to = maxDate(to, g.metricTo);
      }
      summaries.add(
          new AdSideSummary(
              entry.getKey(),
              entry.getValue().size(),
              impressions,
              clicks,
              orders,
              spend,
              sales,
              from,
              to));
    }
    return summaries;
  }

  /** 加载粒度数据（LISTING / SKU 直接分页；SPU 取候选集，稍后归并）。 */
  private AdSide loadGrain(Query query) {
    String ctes = ctes(query);
    Filters filters = buildFilters(query);
    boolean listing = "LISTING".equals(query.dimension());
    String grain = grainCte(query.dimension());
    String grainName = grainName(query.dimension());

    // ---- 行 ----
    List<Grain> rows;
    long total;
    if ("SPU".equals(query.dimension())) {
      // 🔴 SPU 维度要先拿候选集再归并 ⇒ 这里取「按花费排序的前 N 个 SKU」。
      String sqlText =
          "with " + ctes + ", " + grain + " select * from " + grainName + " t order by spend desc"
              + " limit ?";
      List<Object> binds = new ArrayList<>(filters.binds());
      binds.add(MAX_SPU_CANDIDATE_SKUS + 1);
      List<Record> raw = sql.fetch(sqlText, binds.toArray());
      boolean truncated = raw.size() > MAX_SPU_CANDIDATE_SKUS;
      rows =
          raw.stream().limit(MAX_SPU_CANDIDATE_SKUS).map(AdProductReportService::toGrain).toList();
      total = rows.size();
      List<ProfitScopeRow> scope =
          rows.stream()
              .map(g -> new ProfitScopeRow(g.skuId, g.currency, g.spend, g.sales))
              .toList();
      return new AdSide(rows, total, List.of(), scope, truncated);
    }

    String sqlText =
        "with " + ctes + ", " + grain + " select * from " + grainName + " t order by "
            + SORTS.get(query.sort())
            + " limit ? offset ?";
    List<Object> binds = new ArrayList<>(filters.binds());
    binds.add(query.size());
    binds.add(query.page() * query.size());
    rows = sql.fetch(sqlText, binds.toArray()).stream().map(AdProductReportService::toGrain).toList();

    total =
        count(
            "with " + ctes + ", " + grain + " select count(*) from " + grainName + " t",
            filters.binds().toArray());

    // ---- 广告侧汇总（独立聚合查询，统计筛选后的全集）----
    String summaryGrain =
        listing
            ? "select lm.currency currency, count(*) group_count, sum(lm.impressions) impressions,"
                + " sum(lm.clicks) clicks, sum(lm.orders) orders, sum(lm.spend) spend,"
                + " sum(lm.sales) sales, min(lm.metric_from) metric_from, max(lm.metric_to) metric_to"
                + " from listing_metrics lm group by lm.currency order by lm.currency"
            : "select sm.currency currency, count(*) group_count, sum(sm.impressions) impressions,"
                + " sum(sm.clicks) clicks, sum(sm.orders) orders, sum(sm.spend) spend,"
                + " sum(sm.sales) sales, min(sm.metric_from) metric_from, max(sm.metric_to) metric_to"
                + " from sku_metrics sm group by sm.currency order by sm.currency";
    List<AdSideSummary> summaries =
        sql.fetch("with " + ctes + " " + summaryGrain, filters.binds().toArray()).stream()
            .map(
                r ->
                    new AdSideSummary(
                        r.get("currency", String.class),
                        r.get("group_count", Long.class),
                        r.get("impressions", BigDecimal.class),
                        r.get("clicks", BigDecimal.class),
                        r.get("orders", BigDecimal.class),
                        r.get("spend", BigDecimal.class),
                        r.get("sales", BigDecimal.class),
                        r.get("metric_from", LocalDate.class),
                        r.get("metric_to", LocalDate.class)))
            .toList();

    // ---- 利润归集范围（哪些 SKU 的利润能进这一次的汇总）----
    String scopeGrain =
        listing
            ? "select lsm.internal_sku_id sku_id, lm.currency currency, lm.spend spend, lm.sales sales"
                + " from listing_metrics lm"
                + " join listing_sku_info lsi on lsi.listing_id = lm.listing_id"
                + " join listing_sku_map lsm on lsm.listing_id = lm.listing_id"
                + " where lsi.sku_count = 1 and lsi.unique_sku_count = 1"
            : "select sm.sku_id sku_id, sm.currency currency, sm.spend spend, sm.sales sales"
                + " from sku_metrics sm";
    List<Record> scopeRows =
        sql.fetch(
            "with " + ctes + " " + scopeGrain + " limit ?",
            append(filters.binds(), MAX_PROFIT_SCOPE_SKUS + 1).toArray());
    boolean scopeTruncated = scopeRows.size() > MAX_PROFIT_SCOPE_SKUS;
    List<ProfitScopeRow> scope =
        scopeRows.stream()
            .limit(MAX_PROFIT_SCOPE_SKUS)
            .map(
                r ->
                    new ProfitScopeRow(
                        r.get("sku_id", UUID.class),
                        r.get("currency", String.class),
                        r.get("spend", BigDecimal.class),
                        r.get("sales", BigDecimal.class)))
            .toList();

    return new AdSide(rows, total, summaries, scope, scopeTruncated);
  }

  /** DB 行 → 内部粒度结构。 */
  private static Grain toGrain(Record r) {
    Grain g = new Grain();
    g.dimensionKey = str(r, "dimension_key");
    g.dimensionLabel = str(r, "dimension_label");
    g.currency = str(r, "currency");
    g.platformName = str(r, "platform_name");
    g.shopName = str(r, "shop_name");
    g.externalItemId = str(r, "external_item_id");
    g.skuId = id(r, "sku_id");
    g.impressions = nz(dec(r, "impressions"));
    g.clicks = nz(dec(r, "clicks"));
    g.orders = nz(dec(r, "orders"));
    g.spend = nz(dec(r, "spend"));
    g.sales = nz(dec(r, "sales"));
    g.ctr = dec(r, "ctr");
    g.cpc = dec(r, "cpc");
    g.cvr = dec(r, "cvr");
    g.acos = dec(r, "acos");
    g.metricFrom = date(r, "metric_from");
    g.metricTo = date(r, "metric_to");
    g.campaignCount = num(r, "campaign_count");
    g.directCampaignCount = num(r, "direct_campaign_count");
    g.listingCount = num(r, "source_listing_count");
    g.directListings = num(r, "direct_listings");
    g.allocatedListings = num(r, "allocated_listings");
    g.mixedListings = num(r, "mixed_listings");
    g.singleSkuListings = num(r, "single_sku_listings");
    g.skuCount = num(r, "sku_count");
    g.uniqueSkuCount = num(r, "unique_sku_count");
    return g;
  }

  /**
   * 取一列（**列不存在时给 null**，不抛异常）。
   *
   * <p>🔴 LISTING 粒度与 SKU 粒度的行**列集合不同**（各有各的归因计数列），
   * 所以这里不能用 {@code record.get(name, type)} —— 它遇到不存在的列会抛
   * {@code IllegalArgumentException}，把「这行本来就没这一列」说成「查询失败」。
   */
  private static <T> T value(Record r, String column, Class<T> type) {
    org.jooq.Field<?> field = r.field(column);
    if (field == null) return null;
    Object raw = field.getValue(r);
    return raw == null ? null : type.cast(raw);
  }

  private static String str(Record r, String column) {
    org.jooq.Field<?> field = r.field(column);
    if (field == null) return "";
    Object raw = field.getValue(r);
    // 🔴 分组键在不同维度里是不同类型（listing uuid / sku uuid）⇒ 统一用 toString，
    //   不去 cast 成 String（cast 会抛 ClassCastException）。
    return raw == null ? "" : raw.toString();
  }

  private static BigDecimal dec(Record r, String column) {
    return value(r, column, BigDecimal.class);
  }

  private static UUID id(Record r, String column) {
    return value(r, column, UUID.class);
  }

  private static LocalDate date(Record r, String column) {
    org.jooq.Field<?> field = r.field(column);
    if (field == null) return null;
    Object raw = field.getValue(r);
    if (raw == null) return null;
    // 🔴 jOOQ 对 date 列给的是 java.sql.Date，不是 java.time.LocalDate ⇒ 统一转一次。
    if (raw instanceof LocalDate localDate) return localDate;
    if (raw instanceof java.sql.Date sqlDate) return sqlDate.toLocalDate();
    return LocalDate.parse(raw.toString());
  }

  private static long num(Record r, String column) {
    Long v = value(r, column, Long.class);
    return v == null ? 0L : v;
  }

  /** 活动级的「范围内总额」（用来和行合计对账，算出未归属的部分）。 */
  private List<CampaignTotal> loadCampaignTotals(Query query) {
    Filters filters = buildFilters(query);
    return sql.fetch(
            "with "
                + ctes(query)
                + " select ca.currency currency, sum(ca.impressions) impressions,"
                + " sum(ca.clicks) clicks, sum(ca.orders) orders, sum(ca.spend) spend,"
                + " sum(ca.sales) sales from campaign_alloc ca group by ca.currency order by ca.currency",
            filters.binds().toArray())
        .stream()
        .map(
            r ->
                new CampaignTotal(
                    r.get("currency", String.class),
                    r.get("impressions", BigDecimal.class),
                    r.get("clicks", BigDecimal.class),
                    r.get("orders", BigDecimal.class),
                    r.get("spend", BigDecimal.class),
                    r.get("sales", BigDecimal.class)))
        .toList();
  }

  // ==========================================================================
  // 行与汇总的拼装
  // ==========================================================================

  /** 把粒度数据 + 商品主数据 + 利润事实拼成报表行。 */
  private List<AdProductReportRow> buildRows(
      Query query,
      List<Grain> rows,
      AdProductCatalogPort.CatalogListSnapshot catalog,
      AdProductProfitPort.ProfitSnapshot profit) {
    List<AdProductReportRow> items = new ArrayList<>();
    for (Grain g : rows) {
      String skuCode = "";
      String spuCode = "";
      if (g.skuId != null) {
        AdProductCatalogPort.SkuIdentity identity = catalog.bySkuId().get(g.skuId);
        if (identity != null) {
          skuCode = identity.skuCode();
          spuCode = identity.spuCode();
        }
      }

      String attributionMode;
      int sourceListingCount;
      String profitNote = "";
      BigDecimal revenue = null;
      BigDecimal netProfit = null;

      if ("LISTING".equals(query.dimension())) {
        attributionMode = listingMode(g.campaignCount, g.directCampaignCount);
        sourceListingCount = 1;
        if (g.skuCount == 0) {
          profitNote =
              "这个 listing 还没挂任何内部 SKU（4.1.1.1 的映射现状为空）⇒ "
                  + "SKU 级利润归不到它 ⇒ 利润列取不到（**不是 0**）。";
        } else if (g.skuCount > 1) {
          profitNote =
              "这个 listing 挂了 "
                  + g.skuCount
                  + " 个内部 SKU ⇒ SKU 级利润**归不到唯一 listing** ⇒ 利润列取不到"
                  + "（**不摊给任何一个**：摊了就是编造归因）。";
        } else if (g.uniqueSkuCount == 0) {
          profitNote =
              "这个 listing 挂的那个 SKU 还挂在别的 listing 上 ⇒ "
                  + "SKU 级利润**归不到唯一 listing** ⇒ 利润列取不到（**不摊给任何一个**）。";
        } else {
          AdProductProfitPort.SkuProfit p = profitOf(profit, skuCode, g.currency);
          if (p != null) {
            revenue = p.revenue();
            netProfit = p.netProfit();
          }
        }
      } else if ("SPU".equals(query.dimension())) {
        attributionMode =
            skuMode(
                g.listingCount,
                g.directListings,
                g.allocatedListings,
                g.mixedListings,
                g.singleSkuListings);
        sourceListingCount = (int) g.listingCount;
        // 🔴 SPU 的利润 = 它名下**在报表内**的 SKU 的利润之和（相加，不是分摊）。
        BigDecimal revenueSum = BigDecimal.ZERO;
        BigDecimal profitSum = BigDecimal.ZERO;
        boolean any = false;
        for (UUID skuId : g.skuIds) {
          AdProductCatalogPort.SkuIdentity identity = catalog.bySkuId().get(skuId);
          if (identity == null || identity.skuCode().isBlank()) continue;
          AdProductProfitPort.SkuProfit p = profitOf(profit, identity.skuCode(), g.currency);
          if (p == null) continue;
          any = true;
          if (p.revenue() != null) revenueSum = revenueSum.add(p.revenue());
          if (p.netProfit() != null) profitSum = profitSum.add(p.netProfit());
        }
        if (any) {
          revenue = revenueSum;
          netProfit = profitSum;
        } else {
          profitNote =
              "这个 SPU 名下**在报表内**的 SKU 这次都没取到利润事实 ⇒ 利润列取不到（**不是 0**）。";
        }
      } else {
        attributionMode =
            skuMode(
                g.listingCount,
                g.directListings,
                g.allocatedListings,
                g.mixedListings,
                g.singleSkuListings);
        sourceListingCount = (int) g.listingCount;
        AdProductProfitPort.SkuProfit p = profitOf(profit, skuCode, g.currency);
        if (p != null) {
          revenue = p.revenue();
          netProfit = p.netProfit();
        } else if (skuCode.isBlank()) {
          profitNote =
              "商品主数据（catalog 服务）里还没有这个 SKU 的档案 ⇒ 拿不到它的 SKU 编码 ⇒ "
                  + "利润事实（按 SKU 编码索引）归不到它 ⇒ 利润列取不到。";
        }
      }

      BigDecimal spend = g.spend;
      BigDecimal sales = g.sales;
      BigDecimal tacos = divide(spend, revenue);
      BigDecimal adSalesShare = divide(sales, revenue);
      BigDecimal adAfterNetProfit =
          (netProfit == null || spend == null) ? null : netProfit.subtract(spend);
      BigDecimal adProfitShare =
          (netProfit == null || netProfit.signum() <= 0 || spend == null)
              ? null
              : spend.divide(netProfit, 6, RoundingMode.HALF_UP);

      String label =
          "SKU".equals(query.dimension()) || "SPU".equals(query.dimension())
              ? (skuCode.isBlank() ? g.dimensionKey : skuCode)
              : g.dimensionLabel;

      items.add(
          new AdProductReportRow(
              query.dimension(),
              g.dimensionKey,
              label,
              g.currency,
              g.platformName,
              g.shopName,
              g.externalItemId,
              "LISTING".equals(query.dimension()) ? g.dimensionKey : "",
              "LISTING".equals(query.dimension()) ? g.dimensionLabel : "",
              g.skuId == null ? "" : g.skuId.toString(),
              skuCode,
              "SPU".equals(query.dimension()) ? g.dimensionKey : spuCode,
              g.impressions,
              g.clicks,
              g.orders,
              spend,
              sales,
              g.ctr,
              g.cpc,
              g.cvr,
              g.acos,
              divide(sales, spend),
              revenue,
              tacos,
              adSalesShare,
              netProfit,
              adAfterNetProfit,
              adProfitShare,
              attributionMode,
              attributionLabel(attributionMode),
              sourceListingCount,
              profitNote,
              g.metricFrom,
              g.metricTo));
    }
    return items;
  }

  /** 汇总：广告侧（全集）+ 利润侧（与利润同一范围）。 */
  private List<AdProductReportSummary> buildSummaries(
      Query query,
      List<AdSideSummary> adSummaries,
      AdSide adSide,
      List<CampaignTotal> campaignTotals,
      AdProductCatalogPort.CatalogListSnapshot catalog,
      AdProductProfitPort.ProfitSnapshot profit) {
    Map<String, CampaignTotal> totalsByCurrency = new LinkedHashMap<>();
    for (CampaignTotal t : campaignTotals) {
      totalsByCurrency.put(t.currency(), t);
    }

    List<AdProductReportSummary> summaries = new ArrayList<>();
    for (AdSideSummary s : adSummaries) {
      BigDecimal scopedSpend = BigDecimal.ZERO;
      BigDecimal scopedSales = BigDecimal.ZERO;
      BigDecimal totalRevenue = BigDecimal.ZERO;
      BigDecimal totalNetProfit = BigDecimal.ZERO;
      boolean revenueComparable = false;
      boolean netProfitComparable = false;
      long scopedGroups = 0;
      for (ProfitScopeRow scope : adSide.profitScope()) {
        if (!s.currency().equals(scope.currency())) continue;
        AdProductCatalogPort.SkuIdentity identity = catalog.bySkuId().get(scope.skuId());
        if (identity == null || identity.skuCode().isBlank()) continue;
        AdProductProfitPort.SkuProfit p = profitOf(profit, identity.skuCode(), scope.currency());
        if (p == null) continue;
        scopedGroups++;
        scopedSpend = scopedSpend.add(nz(scope.spend()));
        scopedSales = scopedSales.add(nz(scope.sales()));
        if (p.revenue() != null) {
          totalRevenue = totalRevenue.add(p.revenue());
          revenueComparable = true;
        }
        if (p.netProfit() != null) {
          totalNetProfit = totalNetProfit.add(p.netProfit());
          netProfitComparable = true;
        }
      }

      BigDecimal spend = nz(s.spend());
      BigDecimal sales = nz(s.sales());
      CampaignTotal campaignTotal = totalsByCurrency.get(s.currency());
      BigDecimal campaignSpend = campaignTotal == null ? BigDecimal.ZERO : nz(campaignTotal.spend());
      BigDecimal campaignSales = campaignTotal == null ? BigDecimal.ZERO : nz(campaignTotal.sales());

      summaries.add(
          new AdProductReportSummary(
              s.currency(),
              s.groupCount(),
              s.impressions(),
              s.clicks(),
              s.orders(),
              spend,
              sales,
              divide(s.clicks(), s.impressions()),
              divide(spend, s.clicks()),
              divide(s.orders(), s.clicks()),
              divide(spend, sales),
              divide(sales, spend),
              revenueComparable ? totalRevenue : null,
              revenueComparable ? divide(scopedSpend, totalRevenue) : null,
              revenueComparable ? divide(scopedSales, totalRevenue) : null,
              netProfitComparable ? totalNetProfit : null,
              netProfitComparable ? totalNetProfit.subtract(scopedSpend) : null,
              netProfitComparable && totalNetProfit.signum() > 0
                  ? scopedSpend.divide(totalNetProfit, 6, RoundingMode.HALF_UP)
                  : null,
              scopedSpend,
              scopedSales,
              scopedGroups,
              PROFIT_SCOPE_NOTE,
              campaignSpend,
              campaignSales,
              campaignSpend.subtract(spend),
              campaignSales.subtract(sales),
              s.metricFrom(),
              s.metricTo(),
              METRIC_DEFINITION_NOTE,
              ATTRIBUTION_NOTE));
    }
    return summaries;
  }

  // ==========================================================================
  // 口径辅助
  // ==========================================================================

  /** 归因方式：活动级。 */
  private static String listingMode(long campaignCount, long directCampaignCount) {
    if (campaignCount <= 0) return "ALLOCATED_EVENLY";
    if (directCampaignCount == campaignCount) return "DIRECT";
    if (directCampaignCount == 0) return "ALLOCATED_EVENLY";
    return "MIXED";
  }

  /**
   * 归因方式：SKU 级。
   *
   * <p>🔴 它由**它名下各 listing 的归因方式**合出来，而不是「各 listing 直接归属的数量」：
   * 一个 listing 的数字本身就是混的（既有直接又有摊销）时，挂在它下面的 SKU 也是混的 ——
   * 标成「等分摊销」会把「有些是平台原值」这件事藏起来。
   *
   * @param singleSkuListings 名下只挂了一个 SKU 的 listing 数（= listingCount 时 SKU 侧没有再分摊）
   */
  private static String skuMode(
      long listingCount, long directListings, long allocatedListings, long mixedListings,
      long singleSkuListings) {
    if (listingCount <= 0) return "ALLOCATED_EVENLY";
    if (mixedListings > 0) return "MIXED";
    if (directListings > 0 && allocatedListings > 0) return "MIXED";
    if (directListings == listingCount && singleSkuListings == listingCount) return "DIRECT";
    if (directListings == listingCount) return "ALLOCATED_EVENLY";
    return "ALLOCATED_EVENLY";
  }

  /** 归因方式的中文说明。 */
  static String attributionLabel(String mode) {
    return switch (mode) {
      case "DIRECT" -> "直接归属（这个商品的活动只投了它一个，数字是平台原值）";
      case "MIXED" -> "部分直接、部分等分摊销（数字里混着平台原值与分摊值）";
      default -> "等分摊销（活动投了多个商品，数字是按投放商品数分摊出来的）";
    };
  }

  /** 取某个 SKU × 币种的利润事实（读不到就是 null，**不是 0**）。 */
  private static AdProductProfitPort.SkuProfit profitOf(
      AdProductProfitPort.ProfitSnapshot profit, String skuCode, String currency) {
    if (profit == null || !profit.available() || skuCode == null || skuCode.isBlank()) return null;
    return profit.bySkuAndCurrency().get(skuCode + "|" + currency);
  }

  /** 先汇总再相除：分母为 0 / 缺失 ⇒ null（不可比），不是 0。 */
  private static BigDecimal divide(BigDecimal numerator, BigDecimal denominator) {
    if (numerator == null || denominator == null) return null;
    if (denominator.signum() == 0) return null;
    return numerator.divide(denominator, 6, RoundingMode.HALF_UP);
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static long longOf(Record r, String column) {
    Long v = r.get(column, Long.class);
    return v == null ? 0L : v;
  }

  private static List<Object> append(List<Object> binds, Object... extra) {
    List<Object> all = new ArrayList<>(binds);
    all.addAll(List.of(extra));
    return all;
  }

  private static List<UUID> appendUuid(List<UUID> base, UUID id) {
    if (id == null) return base;
    List<UUID> all = new ArrayList<>(base);
    if (!all.contains(id)) all.add(id);
    return all;
  }

  private static LocalDate minDate(LocalDate a, LocalDate b) {
    if (a == null) return b;
    if (b == null) return a;
    return a.isBefore(b) ? a : b;
  }

  private static LocalDate maxDate(LocalDate a, LocalDate b) {
    if (a == null) return b;
    if (b == null) return a;
    return a.isAfter(b) ? a : b;
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
  private long metricsInRange(Query query) {
    return count(
        "select count(*) from ad_campaign_daily_metric dm where dm.tenant_id = "
            + TENANT
            + " and dm.metric_date >= ?::date and dm.metric_date <= ?::date",
        query.from(),
        query.to());
  }

  // ==========================================================================
  // 导出
  // ==========================================================================

  /**
   * 登记一次导出（**留痕**：谁、什么时候、什么筛选条件、导出了多少行）。
   *
   * <p>🔴 留痕里存的是<b>筛选条件</b>而不是报表数据本身：报表随时可以按同样的条件重算。
   */
  public AdProductReportExportRow recordExport(Query query, AccessService.Access actor) {
    long rows = access.run(actor, "channel.operation.read", () -> countGroups(query));
    UUID id = Ids.next();
    OffsetDateTime watermark = access.run(actor, "channel.operation.read", this::dataAsOf);
    access.run(
        actor,
        "channel.operation.read",
        () -> {
          sql.execute(
              "insert into ad_product_report_export (tenant_id,id,requested_by,dimension,"
                  + " platform_id,shop_code,internal_ad_type,status,date_from,date_to,row_count,"
                  + " data_as_of,metric_definition_note,attribution_note,created_at)"
                  + " values (?,?,?,?,?,?,?,?,?::date,?::date,?,?::timestamptz,?,?,now())",
              currentTenant(),
              id,
              actor.userId(),
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
              ATTRIBUTION_NOTE);
          return null;
        });
    return new AdProductReportExportRow(
        id,
        actor.userId(),
        query.dimension(),
        query.platformId(),
        query.shopCode(),
        query.internalAdType(),
        query.status(),
        query.from(),
        query.to(),
        (int) rows,
        watermark,
        METRIC_DEFINITION_NOTE,
        ATTRIBUTION_NOTE,
        OffsetDateTime.now());
  }

  /** 分组后的总条数（独立查询）。 */
  private long countGroups(Query query) {
    String grain = grainCte(query.dimension());
    String grainName = grainName(query.dimension());
    return count(
        "with " + ctes(query) + ", " + grain + " select count(*) from " + grainName + " t",
        buildFilters(query).binds().toArray());
  }

  /**
   * 流式导出 CSV。
   *
   * <p>🔴 <b>每批在流式线程里重新开租户事务</b>：租户上下文是事务级 GUC，
   * controller 那个事务在响应开始前就结束了，而流式写发生在另一个线程上 ——
   * 那条连接上没有 {@code app.current_tenant}，FORCE RLS 会把所有行藏起来。
   *
   * <p>🔴 <b>口径写进表头</b>：列名里带着公式，拿去做跨部门沟通时 CSV 自己就说明了口径。
   */
  public void streamCsv(
      AccessService.Access actor, Query query, Consumer<String> emit, String bearer) {
    int size = 200;
    int offset = 0;

    emit.accept(
        "商品维度,分组键,商品名,币种,平台,店铺,平台侧商品id,SKU编码,SPU编码,"
            + "广告花费(Σ活动花费/投放商品数),曝光,点击,CTR(Σ点击/Σ曝光),CPC(Σ花费/Σ点击),"
            + "广告订单数,转化率(Σ订单/Σ点击),广告销售额,ACOS(Σ花费/Σ广告销售额),"
            + "ROAS(Σ广告销售额/Σ花费),归因方式,总销售额,TACOS(Σ广告花费/总销售额),"
            + "广告销售占比(广告销售额/总销售额),总净利,广告后净利(总净利-广告花费),"
            + "广告占比利润(广告花费/总净利),统计起,统计止\n");

    while (true) {
      int at = offset;
      Query pageQuery =
          new Query(
              query.from(),
              query.to(),
              query.dimension(),
              query.sort(),
              query.platformId(),
              query.shopCode(),
              query.internalAdType(),
              query.status(),
              at / size,
              size);
      AdSide batch =
          access.run(actor, "channel.operation.read", () -> loadGrain(pageQuery));

      AdProductProfitPort.ProfitSnapshot profit =
          profitPort.fetch(query.from(), query.to(), bearer);
      AdProductCatalogPort.CatalogListSnapshot catalog = catalogPort.listIdentities(bearer);

      List<Grain> grainRows = batch.rows();
      if ("SPU".equals(query.dimension())) {
        // 🔴 SPU 的分页在服务层做 ⇒ 导出时**一次取全量归并结果**，不走分页循环。
        grainRows = mergeToSpu(grainRows, catalog, query.sort()).rows();
      }
      List<AdProductReportRow> rows = buildRows(query, grainRows, catalog, profit);
      if (rows.isEmpty()) break;

      StringBuilder sb = new StringBuilder();
      for (AdProductReportRow r : rows) {
        List<String> cells = new ArrayList<>();
        cells.add(dimensionLabel(query.dimension()));
        cells.add(r.dimensionKey());
        cells.add(r.dimensionLabel());
        cells.add(r.currency());
        cells.add(r.platformName());
        cells.add(r.shopName());
        cells.add(r.externalItemId());
        cells.add(r.skuCode());
        cells.add(r.spuCode());
        cells.add(plain(r.spend()));
        cells.add(plain(r.impressions()));
        cells.add(plain(r.clicks()));
        cells.add(ratio(r.ctr()));
        cells.add(ratio(r.cpc()));
        cells.add(plain(r.orders()));
        cells.add(ratio(r.cvr()));
        cells.add(plain(r.sales()));
        cells.add(ratio(r.acos()));
        cells.add(ratio(r.roas()));
        cells.add(r.attributionMode());
        cells.add(plain(r.totalSales()));
        cells.add(ratio(r.tacos()));
        cells.add(ratio(r.adSalesShare()));
        cells.add(plain(r.netProfit()));
        cells.add(plain(r.adAfterNetProfit()));
        cells.add(ratio(r.adProfitShare()));
        cells.add(String.valueOf(r.metricFrom()));
        cells.add(String.valueOf(r.metricTo()));
        sb.append(String.join(",", cells.stream().map(AdProductReportService::csvCell).toList()));
        sb.append('\n');
      }
      emit.accept(sb.toString());
      offset += size;
      // 🔴 SPU 维度一次就取全量归并结果 ⇒ 直接结束（否则会拿同一批数据死循环）。
      if ("SPU".equals(query.dimension())) break;
      if (batch.rows().size() < size) break;
    }
  }

  /** 导出留痕列表（真分页）。 */
  public AdProductReportExportPage exports(int page, int size) {
    int safePage = Math.max(page, 0);
    int safeSize = Math.min(Math.max(size, 1), 200);
    List<AdProductReportExportRow> items =
        sql.fetch(
                "select id, requested_by, dimension, platform_id, shop_code,"
                    + " internal_ad_type, status, date_from, date_to, row_count,"
                    + " data_as_of, metric_definition_note, attribution_note, created_at"
                    + " from ad_product_report_export where tenant_id = "
                    + TENANT
                    + " order by created_at desc, id desc limit ? offset ?",
                safeSize,
                safePage * safeSize)
            .stream()
            .map(
                r ->
                    new AdProductReportExportRow(
                        r.get("id", UUID.class),
                        r.get("requested_by", UUID.class),
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
                        r.get("attribution_note", String.class),
                        r.get("created_at", OffsetDateTime.class)))
            .toList();
    long total =
        count("select count(*) from ad_product_report_export where tenant_id = " + TENANT);
    return new AdProductReportExportPage(items, total, safePage, safeSize);
  }

  // ==========================================================================
  // 小工具
  // ==========================================================================

  /** 金额 / 数量的文本（不做任何前端算术，服务端给什么就是什么）。 */
  private static String plain(BigDecimal value) {
    return value == null ? "" : value.toPlainString();
  }

  /** 比率的文本：🔴 分母为 0 是「不可比」，写「不可比」而**不是** 0。 */
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

  /** 商品维度的显示名（CSV 的第一列）。 */
  static String dimensionLabel(String dimension) {
    return switch (dimension) {
      case "SKU" -> "按内部 SKU";
      case "SPU" -> "按标准产品单元（SPU）";
      default -> "按平台商品（Listing）";
    };
  }
}
