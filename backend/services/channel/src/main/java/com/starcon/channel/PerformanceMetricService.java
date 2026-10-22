package com.starcon.channel;

import static com.starcon.channel.PerformanceMetricModels.*;
import static com.starcon.channel.PerformanceMetricRules.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 16.1.1.2 绩效指标监控。
 *
 * <p>蓝图：「监控各平台核心绩效指标的趋势与阈值：订单缺陷率、迟发率、取消率、有效追踪率、
 * 响应时间、账户评级，支持自定义阈值预警与趋势预测（是否会跌破阈值）。
 * 输入：指标时间序列；阈值配置；预测模型参数。
 * 输出：指标趋势图 + 阈值预警 + 跌破预测。
 * 关系：上游 = 16.1.1.1；下游 = 1 工作台预警、9 订单履约（迟发预警前置）。
 * <b>预测跌破比已跌破更有价值</b>——提前几天知道就能提前改。」
 *
 * <p>🔴🔴 本类里有六处<b>结构</b>上的不变量：
 *
 * <ol>
 *   <li><b>双来源如实表达</b>：四项指标的时间序列来自 16.1.1.1 的
 *       {@code account_health_metric}，两项指标（响应时间 / 账户评级）来自本模块自有的
 *       {@code account_health_performance_metric}。<b>零 ALTER 任何既有表</b>，
 *       两条来源在 API 与页面上分别标注，<b>不假装</b>是同一个来源。</li>
 *   <li><b>趋势是区间口径</b>：一行 = 一个区间的一个指标 ⇒ 按周期终点排序聚成序列，
 *       响应里写明「区间口径，不是瞬时点」。</li>
 *   <li><b>量不到 ≠ 0</b>：{@code metric_value} 为 NULL 的点不进已知数、<b>不算通过</b>，
 *       均值 / 环比 / 预测都<b>显式说明分母</b>。</li>
 *   <li><b>阈值在服务端</b>：预警阈值解析顺序是「本模块自定义 &gt; 上游租户覆盖 &gt;
 *       内置默认」，每条预警凭据<b>冻结当时用到的阈值快照</b>。</li>
 *   <li><b>预测不许造假</b>：点数不足就如实说「无法预测」并给原因。</li>
 *   <li><b>下游未交付一律不编</b>：1 工作台预警、9 订单履约（迟发预警前置）当前
 *       <b>都未交付</b> ⇒ 能力声明里如实标 {@code available=false} + {@code ownerModule}。</li>
 * </ol>
 */
@Service
public class PerformanceMetricService {

  /** 🔴 趋势窗口的默认周期数。 */
  private static final int DEFAULT_TREND_WINDOW = 12;

  /** 🔴 趋势窗口的最大周期数。 */
  private static final int MAX_TREND_WINDOW = 48;

  /** 🔴 预测窗口的默认周期数。 */
  private static final int DEFAULT_FORECAST_WINDOW = 6;

  /** 🔴 预测窗口的最大周期数。 */
  private static final int MAX_FORECAST_WINDOW = 24;

  /** 🔴 预测时向后推的默认周期数。 */
  private static final int DEFAULT_HORIZON = 6;

  /** 🔴 预测时向后推的最大周期数。 */
  private static final int MAX_HORIZON = 12;

  // ==================================================================
  // 口径说明（能力声明与各处共用同一句话）
  // ==================================================================

  private static final String GRANULARITY_SEMANTICS =
      "🔴 趋势是**区间口径**，不是瞬时点：上游 16.1.1.1 的指标一行 = **一个区间的一个指标**"
          + "（period_start ~ period_end），**一行不是一个日粒度点**。"
          + "本模块的序列按**周期终点**排序聚成，**不把区间值伪装成每日值**。";

  private static final String UNKNOWN_SEMANTICS =
      "🔴「量不到」是一个独立状态，**不是 0、也不是通过**："
          + "指标值为 NULL 时该点不进已知数、**不算通过**、产出一条 UNKNOWN 预警依据；"
          + "均值 / 环比 / 预测遇到 NULL 都**显式说明分母**。"
          + "把「没测到」写成「健康」或「0」是最危险的一种撒谎。";

  private static final String THRESHOLD_SEMANTICS =
      "🔴 预警阈值**在服务端算**，前端只显示结论与依据，不重算。"
          + "解析顺序：**本模块的租户自定义阈值** > **16.1.1.1 的租户阈值** > **模块内置默认**；"
          + "每条预警凭据都**冻结当时用到的阈值快照** ⇒ 改阈值**不追溯改变历史预警**。"
          + "内置默认阈值是**本模块的默认政策、不是平台事实**（各平台口径并不通用）。";

  private static final String TREND_SEMANTICS =
      "🔴 趋势的均值与环比都**只用量到的点**："
          + "均值分母 = 量到的点数（量不到的**不进分母**，但分母写清楚）；"
          + "环比对比的是序列里**上一个周期**，上一个周期量不到 ⇒ **环比不可知**（不是「没变化」）。";

  private static final String FORECAST_SEMANTICS =
      "🔴 预测跌破的口径是**明确的、可复核的**：取窗口内最近 N 个周期，对**量到的**点做"
          + "**最小二乘线性回归**，以**最后一个量到的周期**为起点向后推 k 个周期，"
          + "看第几个周期沿危险方向越线。用到的周期 / 点数 / 斜率**全部写进凭据**。"
          + "已知点数不足 3 个 ⇒ **无法预测**并给出原因，**绝不给一个假预测**。";

  private static final String SOURCE_NOTE =
      "🔴 **六项指标分属两个来源，本模块不假装是同一个来源**："
          + "订单缺陷率 / 迟发率 / 取消率 / 有效追踪率的时间序列来自 "
          + "**16.1.1.1 的 account_health_metric**（本模块**只读**它）；"
          + "响应时间 / 账户评级来自**本模块自有的 account_health_performance_metric**。"
          + "上游那张表的 metric_code 取值域由 16.1.1.1 的 CHECK 钉死，"
          + "而本批约定**零 ALTER 任何既有表** ⇒ 响应时间 / 账户评级**只能**落在本模块自己的表里。"
          + "每条序列都标注了它的来源，页面上看得出区别。";

  private static final String UPSTREAM_NOTE =
      "上游 = **16.1.1.1 账号健康总览**（同服务同库）：四项指标的时间序列直接读它的 "
          + "account_health_metric，阈值可读它的 account_health_threshold（租户覆盖优先于内置默认）。"
          + "本模块**不改它的任何表**（零 ALTER）。";

  private static final String DOWNSTREAM_NOTE =
      "下游 = **1 工作台预警**（**未交付** ⇒ 本模块**不编造**工作台预警推送）、"
          + "**9 订单履约 · 迟发预警前置**（**未交付** ⇒ 本模块**不编造**履约侧联动）。"
          + "这两条缺口在能力声明里是**可查的事实**（available=false + ownerModule + 影响说明），"
          + "不是文档里的一句话。";

  private final DSLContext sql;
  private final ObjectMapper json;

  public PerformanceMetricService(DSLContext sql, ObjectMapper json) {
    this.sql = sql;
    this.json = json;
  }

  // ==================================================================
  // 小工具
  // ==================================================================

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "不存在");
  }

  private static ResponseStatusException unprocessable(String message) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
  }

  private UUID currentTenant() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(String.valueOf(raw));
  }

  /** 🔴 <b>同源 DB 时钟</b>：采集 / 评估 / 预测时刻一律从这里取。 */
  private OffsetDateTime dbNow() {
    Object raw = sql.fetchValue("select now()");
    return raw instanceof OffsetDateTime value ? value : OffsetDateTime.now(ZoneOffset.UTC);
  }

  private UUID currentActor() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.actor',true),'')");
    return raw == null ? null : UUID.fromString(String.valueOf(raw));
  }

  private static UUID parseUuid(String raw) {
    String value = text(raw);
    if (value.isEmpty()) {
      return null;
    }
    try {
      return UUID.fromString(value);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * 🔴 非法 UUID <b>按「筛不出东西」处理</b>而不是报错 —— 筛选参数是用户敲进来的。
   *
   * <p>但<b>绝不能</b>当成「没筛选」：那会让用户以为筛选没生效，结果看到的是**全部店铺**。
   */
  private static final UUID NO_SUCH_SHOP = UUID.fromString("00000000-0000-0000-0000-000000000000");

  private static UUID parseFilterShopId(String raw) {
    String value = text(raw);
    if (value.isEmpty()) {
      return null;
    }
    UUID parsed = parseUuid(value);
    return parsed == null ? NO_SUCH_SHOP : parsed;
  }

  /** 解析日期入参：接受 {@code 2026-10-01} 与带偏移量的 ISO 串（取 UTC 当日）。 */
  static LocalDate parseDate(String raw, String field) {
    String value = text(raw);
    if (value.isEmpty()) {
      throw unprocessable(field + " 不能为空");
    }
    try {
      return LocalDate.parse(value);
    } catch (RuntimeException ignored) {
      // 继续按 ISO 日期时间解析
    }
    try {
      return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
    } catch (RuntimeException e) {
      throw unprocessable(field + " 不是合法日期（应为 YYYY-MM-DD）");
    }
  }

  private static BigDecimal decimalOf(Record row, String column) {
    return row.get(column, BigDecimal.class);
  }

  private static int intOf(Record row, String column) {
    Number value = row.get(column, Number.class);
    return value == null ? 0 : value.intValue();
  }

  private static long longOf(Record row, String column) {
    Number value = row.get(column, Number.class);
    return value == null ? 0L : value.longValue();
  }

  private static long count(Object raw) {
    return raw == null ? 0L : ((Number) raw).longValue();
  }

  private String writeJson(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("内容序列化失败", e);
    }
  }

  private Map<String, Object> readJsonMap(String raw) {
    if (raw == null || raw.isBlank()) {
      return Map.of();
    }
    try {
      return json.readValue(raw, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      return Map.of();
    }
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(value, max));
  }

  // ==================================================================
  // 内部形状
  // ==================================================================

  /** 店铺目录里的一行。 */
  private record ShopRow(
      UUID id, String code, String name, String platformCode, String platformName) {}

  /** 一条序列的键（店铺 × 指标）。 */
  private record SeriesKey(UUID shopId, String metricCode) {}

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 能力声明：六项指标分别归谁、数据来自哪张表、哪些下游未交付、口径是什么。
   *
   * <p>🔴 下游 **1 工作台预警** 与 **9 订单履约（迟发预警前置）当前都未交付**，
   * 如实报出 ownerModule 与影响。
   */
  public PerformanceMetricCapabilities capabilities() {
    List<PerformanceMetricCapabilityGap> gaps =
        List.of(
            new PerformanceMetricCapabilityGap(
                "1",
                "工作台预警",
                "1",
                false,
                "工作台预警未交付 ⇒ 本模块**不编造**工作台预警推送。"
                    + "预警凭据只落在本模块自己的表里（account_health_performance_alert），"
                    + "**没有**推送到任何工作台；等 1 交付后由那一侧来读。"),
            new PerformanceMetricCapabilityGap(
                "9",
                "订单履约（迟发预警前置）",
                "9",
                false,
                "订单履约的「迟发预警前置」未交付 ⇒ 本模块**不编造**履约侧的联动。"
                    + "迟发率的预警与跌破预测只在本模块内成立，"
                    + "**不会**自动改变履约侧的任何行为。"));

    List<PerformanceMetricDescriptor> catalog = new ArrayList<>();
    for (String code : METRIC_CODES) {
      String unit = METRIC_UNITS.get(code);
      boolean upstream = UPSTREAM_METRIC_CODES.contains(code);
      String direction = builtinDirection(code);
      catalog.add(
          new PerformanceMetricDescriptor(
              code,
              METRIC_LABELS.getOrDefault(code, code),
              unit,
              UNIT_LABELS.getOrDefault(unit, unit),
              direction,
              directionText(direction),
              upstream ? "16.1.1.1" : "16.1.1.2",
              upstream ? "16.1.1.1 · account_health_metric" : "16.1.1.2 · account_health_performance_metric",
              upstream ? "上游 16.1.1.1 的指标快照" : "本模块自有的指标快照",
              true,
              upstream
                  ? "比值口径（0..1，NULL = 量不到）；时间序列来自 16.1.1.1，本模块只读它。"
                  : UNIT_HOURS.equals(unit)
                      ? "单位是**小时**；这是本模块自有的指标，与上游那四项不是同一张表。"
                      : "0..5 星制是**本模块的默认口径，不是平台事实**（各平台评级体系不同），"
                          + "可按租户覆盖。"));
    }

    return new PerformanceMetricCapabilities(
        gaps,
        List.copyOf(catalog),
        METRIC_CODES,
        List.of(SOURCE_PLATFORM_SYNC, SOURCE_MANUAL),
        SEVERITIES,
        List.of(UNIT_RATIO, UNIT_HOURS, UNIT_SCORE),
        GRANULARITY_SEMANTICS,
        UNKNOWN_SEMANTICS,
        THRESHOLD_SEMANTICS,
        TREND_SEMANTICS,
        FORECAST_SEMANTICS,
        SOURCE_NOTE,
        UPSTREAM_NOTE,
        DOWNSTREAM_NOTE);
  }

  /** 指标的默认方向（内置默认阈值里的方向，也是能力声明里的方向）。 */
  private static String builtinDirection(String metricCode) {
    AlertThreshold threshold = BUILTIN_ALERT_THRESHOLDS.get(metricCode);
    return threshold == null ? DIRECTION_ABOVE_IS_BAD : threshold.direction();
  }

  // ==================================================================
  // 店铺目录
  // ==================================================================

  /**
   * 🔴 店铺清单<b>直接读本服务的店铺目录</b>（{@code shop} / {@code platform}），
   * 不写死店铺常量、不造平行店铺表。
   */
  private List<ShopRow> loadShops(UUID tenant, String platformCode, String keyword, UUID shopId) {
    StringBuilder statement =
        new StringBuilder(
            """
            select s.id, s.code, s.name, p.code as platform_code, p.name as platform_name
            from shop s join platform p on p.id = s.platform_id
            where s.tenant_id = ? and s.status <> 'ARCHIVED'
            """);
    List<Object> params = new ArrayList<>();
    params.add(tenant);
    if (!text(platformCode).isEmpty()) {
      statement.append(" and p.code = ?");
      params.add(text(platformCode).toUpperCase());
    }
    if (!text(keyword).isEmpty()) {
      statement.append(" and (s.code ilike '%' || ? || '%' or s.name ilike '%' || ? || '%')");
      params.add(text(keyword));
      params.add(text(keyword));
    }
    if (shopId != null) {
      statement.append(" and s.id = ?");
      params.add(shopId);
    }
    statement.append(" order by s.code");

    List<ShopRow> shops = new ArrayList<>();
    for (Record row : sql.fetch(statement.toString(), params.toArray())) {
      shops.add(
          new ShopRow(
              row.get("id", UUID.class),
              row.get("code", String.class),
              row.get("name", String.class),
              row.get("platform_code", String.class),
              row.get("platform_name", String.class)));
    }
    return shops;
  }

  /** 取店铺目录里的一行；不存在或不可见一律 404（跨租户与不存在逐字相同）。 */
  private ShopRow requireShop(UUID tenant, UUID shopId) {
    for (Record row :
        sql.fetch(
            """
            select s.id, s.code, s.name, p.code as platform_code, p.name as platform_name
            from shop s join platform p on p.id = s.platform_id
            where s.tenant_id = ? and s.id = ?
            """,
            tenant,
            shopId)) {
      return new ShopRow(
          row.get("id", UUID.class),
          row.get("code", String.class),
          row.get("name", String.class),
          row.get("platform_code", String.class),
          row.get("platform_name", String.class));
    }
    throw notFound();
  }

  // ==================================================================
  // 指标时间序列（双来源）
  // ==================================================================

  /**
   * 🔴 加载一组店铺 × 一个指标的时间序列点（**按店铺分组**）。
   *
   * <p><b>两个来源分两次查</b>，各自取窗口内<b>最近 N 个周期</b>（{@code row_number()}），
   * 然后按周期终点<b>升序</b>返回 —— 升序是「序列」的方向（最新的在最后）。
   */
  private Map<UUID, List<TrendPoint>> loadPoints(
      UUID tenant, List<ShopRow> shops, String metricCode, int window) {
    Map<UUID, List<TrendPoint>> result = new LinkedHashMap<>();
    if (shops.isEmpty() || window <= 0) {
      return result;
    }
    StringBuilder placeholders = new StringBuilder();
    List<Object> params = new ArrayList<>();
    params.add(tenant);
    for (ShopRow shop : shops) {
      placeholders.append(placeholders.length() == 0 ? "?" : ",?");
      params.add(shop.id());
    }

    if (UPSTREAM_METRIC_CODES.contains(metricCode)) {
      List<Object> upstreamParams = new ArrayList<>(params);
      upstreamParams.add(metricCode);
      upstreamParams.add(window);
      groupByShop(
          result,
          fetchPoints(
              """
              select shop_id, metric_code, period_start, period_end, metric_value, source,
                     captured_at, note
              from (
                select m.shop_id, m.metric_code, m.period_start, m.period_end, m.metric_value,
                       m.source, m.captured_at, m.note,
                       row_number() over (partition by m.shop_id, m.metric_code
                                          order by m.period_end desc, m.period_start desc, m.id desc) as rn
                from account_health_metric m
                where m.tenant_id = ? and m.shop_id in ("""
                  + placeholders
                  + """
                ) and m.metric_code = ?
              ) t
              where t.rn <= ?
              order by t.shop_id, t.metric_code, t.period_end, t.period_start
              """,
              upstreamParams));
    } else if (OWN_METRIC_CODES.contains(metricCode)) {
      List<Object> ownParams = new ArrayList<>(params);
      ownParams.add(metricCode);
      ownParams.add(window);
      groupByShop(
          result,
          fetchPoints(
              """
              select shop_id, metric_code, period_start, period_end, metric_value, source,
                     captured_at, note
              from (
                select m.shop_id, m.metric_code, m.period_start, m.period_end, m.metric_value,
                       m.source, m.captured_at, m.note,
                       row_number() over (partition by m.shop_id, m.metric_code
                                          order by m.period_end desc, m.period_start desc, m.id desc) as rn
                from account_health_performance_metric m
                where m.tenant_id = ? and m.shop_id in ("""
                  + placeholders
                  + """
                ) and m.metric_code = ?
              ) t
              where t.rn <= ?
              order by t.shop_id, t.metric_code, t.period_end, t.period_start
              """,
              ownParams));
    }
    return result;
  }

  /** 一行查询结果（带店铺 id）。 */
  private record ShopPoint(UUID shopId, TrendPoint point) {}

  private void groupByShop(Map<UUID, List<TrendPoint>> target, List<ShopPoint> points) {
    for (ShopPoint entry : points) {
      target.computeIfAbsent(entry.shopId(), key -> new ArrayList<>()).add(entry.point());
    }
  }

  private List<ShopPoint> fetchPoints(String statement, List<Object> params) {
    List<ShopPoint> points = new ArrayList<>();
    for (Record row : sql.fetch(statement, params.toArray())) {
      points.add(
          new ShopPoint(
              row.get("shop_id", UUID.class),
              new TrendPoint(
                  localDateText(row.get("period_start", LocalDate.class)),
                  localDateText(row.get("period_end", LocalDate.class)),
                  decimalOf(row, "metric_value"),
                  row.get("source", String.class),
                  offsetText(row.get("captured_at", OffsetDateTime.class)),
                  row.get("note", String.class))));
    }
    return points;
  }

  /**
   * 🔴 每个店铺每个指标的**点位计数**（**不受窗口限制**）—— 给「店铺范围」清单用。
   *
   * @return 店铺 id →（指标码 → 已知点数 / 未知点数）
   */
  private Map<UUID, Map<String, int[]>> loadMetricCounts(UUID tenant) {
    Map<UUID, Map<String, int[]>> result = new LinkedHashMap<>();
    for (Record row :
        sql.fetch(
            """
            select shop_id, metric_code,
                   count(*) as points, count(metric_value) as known
            from account_health_metric
            where tenant_id = ? and metric_code in ('ODR','LATE_SHIPMENT_RATE','CANCELLATION_RATE','VALID_TRACKING_RATE')
            group by shop_id, metric_code
            """,
            tenant)) {
      accumulate(result, row.get("shop_id", UUID.class), row.get("metric_code", String.class), row);
    }
    for (Record row :
        sql.fetch(
            """
            select shop_id, metric_code,
                   count(*) as points, count(metric_value) as known
            from account_health_performance_metric
            where tenant_id = ? and metric_code in ('RESPONSE_TIME','ACCOUNT_RATING')
            group by shop_id, metric_code
            """,
            tenant)) {
      accumulate(result, row.get("shop_id", UUID.class), row.get("metric_code", String.class), row);
    }
    return result;
  }

  private void accumulate(
      Map<UUID, Map<String, int[]>> result, UUID shopId, String metricCode, Record row) {
    int points = intOf(row, "points");
    int known = intOf(row, "known");
    result.computeIfAbsent(shopId, key -> new LinkedHashMap<>())
        .put(metricCode, new int[] {points, known, points - known});
  }

  private static String localDateText(LocalDate value) {
    return value == null ? "" : value.toString();
  }

  private static String offsetText(OffsetDateTime value) {
    return value == null ? "" : value.toString();
  }

  /**
   * 🔴 加载一个店铺**每个指标最新一条**读数（用于预警评估）。
   *
   * <p>「最新」的次序是 {@code period_end desc, period_start desc, captured_at desc, id desc}
   * —— 并列时结果确定。
   */
  private Map<String, TrendPoint> loadLatestPoints(UUID tenant, UUID shopId) {
    Map<String, TrendPoint> result = new LinkedHashMap<>();
    for (Record row :
        sql.fetch(
            """
            select distinct on (m.shop_id, m.metric_code)
                   m.metric_code, m.period_start, m.period_end, m.metric_value, m.source,
                   m.captured_at, m.note
            from account_health_metric m
            where m.tenant_id = ? and m.shop_id = ?
              and m.metric_code in ('ODR','LATE_SHIPMENT_RATE','CANCELLATION_RATE','VALID_TRACKING_RATE')
            order by m.shop_id, m.metric_code, m.period_end desc, m.period_start desc,
                     m.captured_at desc, m.id desc
            """,
            tenant,
            shopId)) {
      result.put(
          row.get("metric_code", String.class),
          new TrendPoint(
              localDateText(row.get("period_start", LocalDate.class)),
              localDateText(row.get("period_end", LocalDate.class)),
              decimalOf(row, "metric_value"),
              row.get("source", String.class),
              offsetText(row.get("captured_at", OffsetDateTime.class)),
              row.get("note", String.class)));
    }
    for (Record row :
        sql.fetch(
            """
            select distinct on (m.shop_id, m.metric_code)
                   m.metric_code, m.period_start, m.period_end, m.metric_value, m.source,
                   m.captured_at, m.note
            from account_health_performance_metric m
            where m.tenant_id = ? and m.shop_id = ?
              and m.metric_code in ('RESPONSE_TIME','ACCOUNT_RATING')
            order by m.shop_id, m.metric_code, m.period_end desc, m.period_start desc,
                     m.captured_at desc, m.id desc
            """,
            tenant,
            shopId)) {
      result.put(
          row.get("metric_code", String.class),
          new TrendPoint(
              localDateText(row.get("period_start", LocalDate.class)),
              localDateText(row.get("period_end", LocalDate.class)),
              decimalOf(row, "metric_value"),
              row.get("source", String.class),
              offsetText(row.get("captured_at", OffsetDateTime.class)),
              row.get("note", String.class)));
    }
    return result;
  }

  // ==================================================================
  // 阈值解析
  // ==================================================================

  /** 生效阈值 = 本模块自定义 > 上游租户覆盖 > 内置默认。 */
  private Map<String, AlertThreshold> loadThresholds(UUID tenant) {
    Map<String, AlertThreshold> moduleOverrides = new LinkedHashMap<>();
    for (Record row :
        sql.fetch(
            """
            select metric_code, direction, unit, warn_value, critical_value, rule_version
            from account_health_performance_alert_rule
            where tenant_id = ? and enabled
            """,
            tenant)) {
      String code = row.get("metric_code", String.class);
      moduleOverrides.put(
          code,
          new AlertThreshold(
              code,
              row.get("direction", String.class),
              row.get("unit", String.class),
              decimalOf(row, "warn_value"),
              decimalOf(row, "critical_value"),
              longOf(row, "rule_version"),
              RULE_SOURCE_CUSTOM));
    }

    Map<String, AlertThreshold> upstreamOverrides = new LinkedHashMap<>();
    for (Record row :
        sql.fetch(
            """
            select metric_code, direction, warn_value, critical_value, rule_version
            from account_health_threshold
            where tenant_id = ? and enabled
            """,
            tenant)) {
      String code = row.get("metric_code", String.class);
      upstreamOverrides.put(
          code,
          new AlertThreshold(
              code,
              row.get("direction", String.class),
              UNIT_RATIO,
              decimalOf(row, "warn_value"),
              decimalOf(row, "critical_value"),
              longOf(row, "rule_version"),
              RULE_SOURCE_UPSTREAM));
    }
    return effectiveThresholds(moduleOverrides, upstreamOverrides);
  }

  /** 阈值快照（冻进凭据 ⇒ 改阈值不追溯改变历史预警）。 */
  private Map<String, Object> thresholdSnapshot(Map<String, AlertThreshold> thresholds) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    for (String code : METRIC_CODES) {
      AlertThreshold threshold = thresholds.get(code);
      if (threshold == null) {
        continue;
      }
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("warnValue", threshold.warnValue());
      item.put("criticalValue", threshold.criticalValue());
      item.put("direction", threshold.direction());
      item.put("unit", threshold.unit());
      item.put("ruleSource", threshold.ruleSource());
      item.put("ruleVersion", threshold.ruleVersion());
      snapshot.put(code, item);
    }
    return snapshot;
  }

  // ==================================================================
  // 店铺范围
  // ==================================================================

  /**
   * 店铺范围清单（**真分页 + 真筛选**）。
   *
   * <p>🔴 店铺清单**直接读本服务的店铺目录**（{@code shop} / {@code platform}），
   * 不写死店铺常量、不造平行店铺表 —— 与 16.1.1.1 同一纪律。
   *
   * <p>本页的「跑一次预警评估 / 跑一次预测 / 录入指标」都是**按店铺**的动作，
   * 所以这里必须给出**真实的店铺清单**，否则第一次录入时连店铺都选不出来。
   */
  public PerformanceMetricShopPage shops(
      String platformCode, String keyword, int page, int size) {
    UUID tenant = currentTenant();
    int limit = Math.max(1, Math.min(size, 200));
    List<ShopRow> shops = loadShops(tenant, platformCode, keyword, null);
    Map<UUID, Map<String, int[]>> counts = loadMetricCounts(tenant);

    List<PerformanceMetricShop> all = new ArrayList<>();
    for (ShopRow shop : shops) {
      Map<String, int[]> byMetric = counts.get(shop.id());
      int series = 0;
      int known = 0;
      int unknown = 0;
      if (byMetric != null) {
        series = byMetric.size();
        for (int[] entry : byMetric.values()) {
          known += entry[1];
          unknown += entry[2];
        }
      }
      all.add(
          new PerformanceMetricShop(
              shop.id().toString(),
              shop.code(),
              shop.name(),
              shop.platformCode(),
              shop.platformName(),
              series,
              known,
              unknown));
    }

    int offset = Math.max(0, page) * limit;
    List<PerformanceMetricShop> items = new ArrayList<>();
    for (int index = offset; index < Math.min(offset + limit, all.size()); index++) {
      items.add(all.get(index));
    }
    return new PerformanceMetricShopPage(items, all.size(), Math.max(0, page), limit);
  }

  // ==================================================================
  // 趋势
  // ==================================================================

  /** 趋势序列清单（**真分页 + 真筛选**）。 */
  public PerformanceMetricTrendPage trends(
      String platformCode,
      String keyword,
      String shopId,
      String metricCode,
      Integer window,
      int page,
      int size) {
    UUID tenant = currentTenant();
    int limit = Math.max(1, Math.min(size, 200));
    int periods = window == null ? DEFAULT_TREND_WINDOW : clamp(window, 1, MAX_TREND_WINDOW);

    String code = text(metricCode).toUpperCase();
    if (!code.isEmpty() && !METRIC_CODES.contains(code)) {
      throw unprocessable("指标只能是 " + String.join(" / ", METRIC_CODES));
    }

    List<ShopRow> shops = loadShops(tenant, platformCode, keyword, parseFilterShopId(shopId));
    List<PerformanceMetricSeries> all = new ArrayList<>();
    for (String metric : METRIC_CODES) {
      if (!code.isEmpty() && !code.equals(metric)) {
        continue;
      }
      Map<UUID, List<TrendPoint>> byShop = loadPoints(tenant, shops, metric, periods);
      for (ShopRow shop : shops) {
        List<TrendPoint> points = byShop.getOrDefault(shop.id(), List.of());
        if (points.isEmpty()) {
          // 🔴 从来没有记录过这个指标的店铺**不产出一条空序列** ——
          //   「没有趋势」不是一条趋势。但「有周期、值量不到」会**产出一条带量不到点的序列**。
          continue;
        }
        all.add(series(shop, metric, points));
      }
    }

    int offset = Math.max(0, page) * limit;
    List<PerformanceMetricSeries> items = new ArrayList<>();
    for (int index = offset; index < Math.min(offset + limit, all.size()); index++) {
      items.add(all.get(index));
    }
    return new PerformanceMetricTrendPage(items, all.size(), Math.max(0, page), limit, TREND_SEMANTICS);
  }

  /** 单家店全部指标的趋势序列。 */
  public List<PerformanceMetricSeries> shopTrends(String shopId, String metricCode, Integer window) {
    UUID tenant = currentTenant();
    UUID id = parseUuid(shopId);
    if (id == null) {
      throw notFound();
    }
    int periods = window == null ? DEFAULT_TREND_WINDOW : clamp(window, 1, MAX_TREND_WINDOW);
    String code = text(metricCode).toUpperCase();
    if (!code.isEmpty() && !METRIC_CODES.contains(code)) {
      throw unprocessable("指标只能是 " + String.join(" / ", METRIC_CODES));
    }
    ShopRow shop = requireShop(tenant, id);

    List<PerformanceMetricSeries> series = new ArrayList<>();
    for (String metric : METRIC_CODES) {
      if (!code.isEmpty() && !code.equals(metric)) {
        continue;
      }
      Map<UUID, List<TrendPoint>> byShop = loadPoints(tenant, List.of(shop), metric, periods);
      List<TrendPoint> points = byShop.getOrDefault(shop.id(), List.of());
      if (points.isEmpty()) {
        continue;
      }
      series.add(series(shop, metric, points));
    }
    return series;
  }

  /** 组装一条序列（**均值 / 环比都只用��到的点**，并写明分母）。 */
  private PerformanceMetricSeries series(ShopRow shop, String metricCode, List<TrendPoint> points) {
    String unit = METRIC_UNITS.getOrDefault(metricCode, UNIT_RATIO);
    List<PerformanceMetricPoint> seriesPoints = new ArrayList<>();
    int known = 0;
    int unknown = 0;
    TrendPoint last = points.isEmpty() ? null : points.get(points.size() - 1);
    for (TrendPoint point : points) {
      boolean isKnown = point.value() != null;
      if (isKnown) {
        known++;
      } else {
        unknown++;
      }
      seriesPoints.add(
          new PerformanceMetricPoint(
              point.periodStart(),
              point.periodEnd(),
              point.value(),
              isKnown,
              point.source(),
              sourceLabel(point.source()),
              point.capturedAt(),
              point.note()));
    }
    return new PerformanceMetricSeries(
        shop.id().toString(),
        shop.code(),
        shop.name(),
        shop.platformCode(),
        metricCode,
        METRIC_LABELS.getOrDefault(metricCode, metricCode),
        unit,
        UNIT_LABELS.getOrDefault(unit, unit),
        UPSTREAM_METRIC_CODES.contains(metricCode)
            ? "16.1.1.1 · account_health_metric"
            : "16.1.1.2 · account_health_performance_metric",
        UPSTREAM_METRIC_CODES.contains(metricCode) ? "上游 16.1.1.1" : "本模块自有",
        "PERIOD_RANGE",
        List.copyOf(seriesPoints),
        known,
        unknown,
        averageOf(points),
        averageNote(points),
        last == null ? null : last.value(),
        last == null ? null : last.periodStart(),
        last == null ? null : last.periodEnd(),
        changeOf(points),
        changeNote(points),
        GRANULARITY_SEMANTICS);
  }

  // ==================================================================
  // 预警阈值
  // ==================================================================

  /** 生效预警阈值清单（内置默认 + 本模块自定义 + 上游覆盖，固定六条）。 */
  public PerformanceMetricAlertRuleList alertRules() {
    UUID tenant = currentTenant();
    Map<String, AlertThreshold> thresholds = loadThresholds(tenant);
    Map<String, OffsetDateTime> effectiveFrom = loadRuleEffectiveFrom(tenant);

    List<PerformanceMetricAlertRule> items = new ArrayList<>();
    for (String code : METRIC_CODES) {
      AlertThreshold threshold = thresholds.get(code);
      if (threshold == null) {
        continue;
      }
      boolean builtin = RULE_SOURCE_BUILTIN.equals(threshold.ruleSource());
      OffsetDateTime since = effectiveFrom.get(code);
      String unit = threshold.unit() == null ? UNIT_RATIO : threshold.unit();
      items.add(
          new PerformanceMetricAlertRule(
              code,
              METRIC_LABELS.getOrDefault(code, code),
              threshold.direction(),
              directionText(threshold.direction()),
              unit,
              UNIT_LABELS.getOrDefault(unit, unit),
              threshold.warnValue(),
              threshold.criticalValue(),
              threshold.ruleVersion(),
              threshold.ruleSource(),
              ruleSourceLabel(threshold.ruleSource()),
              builtin,
              true,
              since == null ? null : since.toString(),
              builtin
                  ? "内置默认阈值是本模块的默认政策，不是平台下发的口径；保存后即成为租户自定义并推进规则版本。"
                  : RULE_SOURCE_UPSTREAM.equals(threshold.ruleSource())
                      ? "沿用 16.1.1.1 的租户阈值（它的风险等级阈值）；在本模块保存后即以本模块的为准。"
                      : "租户自定义预警阈值。"));
    }
    return new PerformanceMetricAlertRuleList(List.copyOf(items), THRESHOLD_SEMANTICS);
  }

  private Map<String, OffsetDateTime> loadRuleEffectiveFrom(UUID tenant) {
    Map<String, OffsetDateTime> result = new LinkedHashMap<>();
    for (Record row :
        sql.fetch(
            """
            select metric_code, effective_from
            from account_health_performance_alert_rule
            where tenant_id = ? and enabled
            """,
            tenant)) {
      result.put(
          row.get("metric_code", String.class), row.get("effective_from", OffsetDateTime.class));
    }
    return result;
  }

  /**
   * 修改某项指标的预警阈值（**服务端行为**，前端不重算）。
   *
   * <p>🔴 改阈值**必然推进 rule_version**（DB 触发器钉住）⇒ 历史预警仍然是旧预警。
   */
  public PerformanceMetricAlertRule saveAlertRule(
      String metricCode, PerformanceMetricAlertRuleInput input) {
    UUID tenant = currentTenant();
    String code = text(metricCode).toUpperCase();
    if (!METRIC_CODES.contains(code)) {
      throw unprocessable("指标只能是 " + String.join(" / ", METRIC_CODES));
    }
    if (input == null) {
      throw unprocessable("缺少阈值内容");
    }
    String direction = text(input.direction()).toUpperCase();
    if (!DIRECTION_ABOVE_IS_BAD.equals(direction) && !DIRECTION_BELOW_IS_BAD.equals(direction)) {
      throw unprocessable("方向只能是 " + DIRECTION_ABOVE_IS_BAD + " / " + DIRECTION_BELOW_IS_BAD);
    }
    BigDecimal warn = input.warnValue();
    BigDecimal critical = input.criticalValue();
    if (warn == null || critical == null) {
      throw unprocessable("警告阈值与严重阈值都必须填写");
    }
    String unit = METRIC_UNITS.getOrDefault(code, UNIT_RATIO);
    BigDecimal max = UNIT_MAX.getOrDefault(unit, BigDecimal.ONE);
    if (warn.compareTo(BigDecimal.ZERO) < 0
        || warn.compareTo(max) > 0
        || critical.compareTo(BigDecimal.ZERO) < 0
        || critical.compareTo(max) > 0) {
      throw unprocessable(
          "阈值必须落在该指标的量纲范围内（"
              + UNIT_LABELS.getOrDefault(unit, unit)
              + "，0.."
              + max.toPlainString()
              + "）");
    }
    int compare = critical.compareTo(warn);
    if (DIRECTION_ABOVE_IS_BAD.equals(direction) && compare < 0) {
      throw unprocessable("越大越危险时，严重阈值不能小于警告阈值");
    }
    if (DIRECTION_BELOW_IS_BAD.equals(direction) && compare > 0) {
      throw unprocessable("越小越危险时，严重阈值不能大于警告阈值");
    }

    OffsetDateTime now = dbNow();
    sql.execute(
        """
        insert into account_health_performance_alert_rule
          (id, tenant_id, metric_code, direction, unit, warn_value, critical_value, rule_version,
           effective_from, enabled, note, created_at, updated_at)
        values (?, ?, ?, ?, ?, ?, ?, 1, ?::timestamptz, true, ?, now(), now())
        on conflict (tenant_id, metric_code)
        do update set direction = excluded.direction,
                      unit = excluded.unit,
                      warn_value = excluded.warn_value,
                      critical_value = excluded.critical_value,
                      rule_version = account_health_performance_alert_rule.rule_version + 1,
                      effective_from = excluded.effective_from,
                      enabled = true,
                      note = excluded.note,
                      updated_at = now()
        """,
        UUID.randomUUID(),
        tenant,
        code,
        direction,
        unit,
        warn,
        critical,
        now,
        text(input.note()));

    for (PerformanceMetricAlertRule view : alertRules().items()) {
      if (view.metricCode().equals(code)) {
        return view;
      }
    }
    throw notFound();
  }

  // ==================================================================
  // 阈值预警（追加式凭据）
  // ==================================================================

  /** 预警凭据清单（**真分页 + 真筛选**）。 */
  public PerformanceMetricAlertPage alerts(
      String shopId, String metricCode, String severity, int page, int size) {
    UUID tenant = currentTenant();
    int limit = Math.max(1, Math.min(size, 200));
    int offset = Math.max(0, page) * limit;

    String code = text(metricCode).toUpperCase();
    if (!code.isEmpty() && !METRIC_CODES.contains(code)) {
      throw unprocessable("指标只能是 " + String.join(" / ", METRIC_CODES));
    }
    String level = text(severity).toUpperCase();
    if (!level.isEmpty() && !SEVERITIES.contains(level)) {
      throw unprocessable("预警等级只能是 " + String.join(" / ", SEVERITIES));
    }

    List<Object> params = new ArrayList<>();
    params.add(tenant);
    StringBuilder clause = new StringBuilder(" where a.tenant_id = ?");
    UUID id = parseUuid(shopId);
    if (!text(shopId).isEmpty()) {
      if (id == null) {
        return new PerformanceMetricAlertPage(
            List.of(), 0, Math.max(0, page), limit, 0L, 0L, 0L, ALERT_SEMANTICS);
      }
      clause.append(" and a.shop_id = ?");
      params.add(id);
    }
    if (!code.isEmpty()) {
      clause.append(" and a.metric_code = ?");
      params.add(code);
    }
    if (!level.isEmpty()) {
      clause.append(" and a.severity = ?");
      params.add(level);
    }

    long total =
        count(
            sql.fetchValue(
                "select count(*) from account_health_performance_alert a" + clause,
                params.toArray()));

    // 🔴 各等级计数覆盖**整个筛选结果集**（不是当前页），页面上的统计卡才不会随分页跳动。
    Map<String, Long> bySeverity = new LinkedHashMap<>();
    for (Record row :
        sql.fetch(
            "select a.severity, count(*) as hits from account_health_performance_alert a"
                + clause
                + " group by a.severity",
            params.toArray())) {
      bySeverity.put(row.get("severity", String.class), longOf(row, "hits"));
    }

    List<Object> pageParams = new ArrayList<>(params);
    pageParams.add(limit);
    pageParams.add(offset);
    List<PerformanceMetricAlert> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            """
            select a.id, a.evaluation_id, a.shop_id, a.metric_code, a.severity, a.unit,
                   a.metric_value, a.period_start, a.period_end, a.threshold_kind,
                   a.threshold_value, a.direction, a.evaluated_at, a.rule_version, a.rule_source,
                   a.thresholds::text as thresholds_json, a.message,
                   s.code as shop_code, s.name as shop_name
            from account_health_performance_alert a
            left join shop s on s.tenant_id = a.tenant_id and s.id = a.shop_id
            """
                + clause
                + " order by a.evaluated_at desc, a.id desc limit ? offset ?",
            pageParams.toArray())) {
      items.add(alertOf(row));
    }
    return new PerformanceMetricAlertPage(
        items,
        total,
        Math.max(0, page),
        limit,
        bySeverity.getOrDefault(SEVERITY_CRITICAL, 0L),
        bySeverity.getOrDefault(SEVERITY_WARNING, 0L),
        bySeverity.getOrDefault(SEVERITY_UNKNOWN, 0L),
        ALERT_SEMANTICS);
  }

  private static final String ALERT_SEMANTICS =
      "🔴 预警凭据是**追加式**的：里面带着**当时用到的阈值快照**，"
          + "所以改阈值**不会追溯改变历史预警**。"
          + "severity=UNKNOWN 表示该指标**量不到**（**不算通过**），"
          + "已知且未命中的指标**不产��预警**（没有预警就没有凭据）。";

  private PerformanceMetricAlert alertOf(Record row) {
    String metricCode = row.get("metric_code", String.class);
    String unit = row.get("unit", String.class);
    BigDecimal metricValue = decimalOf(row, "metric_value");
    BigDecimal thresholdValue = decimalOf(row, "threshold_value");
    String severity = row.get("severity", String.class);
    return new PerformanceMetricAlert(
        row.get("id", UUID.class).toString(),
        row.get("evaluation_id", UUID.class).toString(),
        row.get("shop_id", UUID.class).toString(),
        row.get("shop_code", String.class),
        row.get("shop_name", String.class),
        metricCode,
        METRIC_LABELS.getOrDefault(metricCode, metricCode),
        severity,
        severityLabel(severity),
        unit,
        UNIT_LABELS.getOrDefault(unit, unit),
        metricValue,
        formatValue(metricValue, unit),
        row.get("threshold_kind", String.class),
        thresholdValue,
        formatValue(thresholdValue, unit),
        row.get("direction", String.class),
        localDateText(row.get("period_start", LocalDate.class)),
        localDateText(row.get("period_end", LocalDate.class)),
        offsetText(row.get("evaluated_at", OffsetDateTime.class)),
        longOf(row, "rule_version"),
        row.get("rule_source", String.class),
        ruleSourceLabel(row.get("rule_source", String.class)),
        readJsonMap(row.get("thresholds_json", String.class)),
        row.get("message", String.class));
  }

  /**
   * 跑一次阈值预警评估，落一条**不可改写**的凭据（每个需要关注的指标一条）。
   *
   * <p>🔴 <b>已知且未命中的指标不落行</b>；<b>量不到的指标落一条 UNKNOWN</b>
   * （量不到 ≠ 通过，它本身就是需要关注的事）。
   */
  public PerformanceMetricAlertEvaluation evaluateAlerts(String shopId) {
    UUID tenant = currentTenant();
    UUID id = parseUuid(shopId);
    if (id == null) {
      throw notFound();
    }
    ShopRow shop = requireShop(tenant, id);
    Map<String, AlertThreshold> thresholds = loadThresholds(tenant);
    Map<String, TrendPoint> latest = loadLatestPoints(tenant, id);
    Map<String, Object> snapshot = thresholdSnapshot(thresholds);

    OffsetDateTime now = dbNow();
    UUID evaluationId = UUID.randomUUID();
    List<PerformanceMetricAlert> alerts = new ArrayList<>();
    int known = 0;
    int unknown = 0;

    for (String code : METRIC_CODES) {
      TrendPoint point = latest.get(code);
      BigDecimal value = point == null ? null : point.value();
      if (value == null) {
        unknown++;
      } else {
        known++;
      }
      AlertThreshold threshold = thresholds.get(code);
      MetricVerdict verdict = evaluate(code, value, threshold);
      if (verdict.severity() == null) {
        // 已知且未命中 ⇒ 没有预警 ⇒ 不落凭据。
        continue;
      }
      LocalDate periodStart =
          point == null || point.periodStart().isEmpty()
              ? LocalDate.of(1970, 1, 1)
              : LocalDate.parse(point.periodStart());
      LocalDate periodEnd =
          point == null || point.periodEnd().isEmpty()
              ? LocalDate.of(1970, 1, 1)
              : LocalDate.parse(point.periodEnd());
      UUID alertId = UUID.randomUUID();
      String unit = threshold == null ? METRIC_UNITS.getOrDefault(code, UNIT_RATIO) : threshold.unit();

      sql.execute(
          """
          insert into account_health_performance_alert
            (id, tenant_id, shop_id, evaluation_id, evaluated_at, metric_code, severity, unit,
             metric_value, period_start, period_end, threshold_kind, threshold_value, direction,
             rule_version, rule_source, thresholds, message, created_at)
          values (?, ?, ?, ?, ?::timestamptz, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, now())
          """,
          alertId,
          tenant,
          id,
          evaluationId,
          now,
          code,
          verdict.severity(),
          unit,
          value,
          periodStart,
          periodEnd,
          verdict.thresholdKind(),
          verdict.thresholdValue(),
          threshold == null ? builtinDirection(code) : threshold.direction(),
          threshold == null ? 1L : threshold.ruleVersion(),
          threshold == null ? RULE_SOURCE_BUILTIN : threshold.ruleSource(),
          writeJson(snapshot),
          verdict.message());

      alerts.add(
          new PerformanceMetricAlert(
              alertId.toString(),
              evaluationId.toString(),
              id.toString(),
              shop.code(),
              shop.name(),
              code,
              METRIC_LABELS.getOrDefault(code, code),
              verdict.severity(),
              severityLabel(verdict.severity()),
              unit,
              UNIT_LABELS.getOrDefault(unit, unit),
              value,
              formatValue(value, unit),
              verdict.thresholdKind(),
              verdict.thresholdValue(),
              formatValue(verdict.thresholdValue(), unit),
              threshold == null ? builtinDirection(code) : threshold.direction(),
              periodStart.toString(),
              periodEnd.toString(),
              now.toString(),
              threshold == null ? 1L : threshold.ruleVersion(),
              threshold == null ? RULE_SOURCE_BUILTIN : threshold.ruleSource(),
              ruleSourceLabel(threshold == null ? RULE_SOURCE_BUILTIN : threshold.ruleSource()),
              snapshot,
              verdict.message()));
    }

    return new PerformanceMetricAlertEvaluation(
        evaluationId.toString(),
        id.toString(),
        shop.code(),
        shop.name(),
        now.toString(),
        List.copyOf(alerts),
        METRIC_CODES.size(),
        known,
        unknown,
        ALERT_SEMANTICS);
  }

  // ==================================================================
  // 跌破预测（追加式凭据）
  // ==================================================================

  /** 预测凭据清单（**真分页 + 真筛选**）。 */
  public PerformanceMetricForecastPage forecasts(
      String shopId, String metricCode, String result, int page, int size) {
    UUID tenant = currentTenant();
    int limit = Math.max(1, Math.min(size, 200));
    int offset = Math.max(0, page) * limit;

    String code = text(metricCode).toUpperCase();
    if (!code.isEmpty() && !METRIC_CODES.contains(code)) {
      throw unprocessable("指标只能是 " + String.join(" / ", METRIC_CODES));
    }
    String kind = text(result).toUpperCase();
    if (!kind.isEmpty()
        && !List.of(RESULT_WILL_BREACH, RESULT_NO_BREACH, RESULT_ALREADY_BREACHED, RESULT_CANNOT_PREDICT)
            .contains(kind)) {
      throw unprocessable("预测结论只能是 WILL_BREACH / NO_BREACH / ALREADY_BREACHED / CANNOT_PREDICT");
    }

    List<Object> params = new ArrayList<>();
    params.add(tenant);
    StringBuilder clause = new StringBuilder(" where a.tenant_id = ?");
    UUID id = parseUuid(shopId);
    if (!text(shopId).isEmpty()) {
      if (id == null) {
        return new PerformanceMetricForecastPage(
            List.of(), 0, Math.max(0, page), limit, 0L, 0L, 0L, 0L, FORECAST_SEMANTICS);
      }
      clause.append(" and a.shop_id = ?");
      params.add(id);
    }
    if (!code.isEmpty()) {
      clause.append(" and a.metric_code = ?");
      params.add(code);
    }
    if (!kind.isEmpty()) {
      clause.append(" and a.result = ?");
      params.add(kind);
    }

    long total =
        count(
            sql.fetchValue(
                "select count(*) from account_health_performance_forecast a" + clause,
                params.toArray()));

    // 🔴 各结论计数覆盖**整个筛选结果集**（不是当前页）。
    Map<String, Long> byResult = new LinkedHashMap<>();
    for (Record row :
        sql.fetch(
            "select a.result, count(*) as hits from account_health_performance_forecast a"
                + clause
                + " group by a.result",
            params.toArray())) {
      byResult.put(row.get("result", String.class), longOf(row, "hits"));
    }

    List<Object> pageParams = new ArrayList<>(params);
    pageParams.add(limit);
    pageParams.add(offset);
    List<PerformanceMetricForecast> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            """
            select a.id, a.shop_id, a.metric_code, a.unit, a.forecasted_at, a.method, a.method_note,
                   a.window_points, a.known_points, a.unknown_points, a.used_periods::text as used_json,
                   a.slope, a.intercept, a.result, a.result_reason, a.breach_level,
                   a.breach_in_periods, a.predicted_value, a.threshold_value, a.threshold_kind,
                   a.direction, a.rule_version, a.rule_source, a.thresholds::text as thresholds_json,
                   s.code as shop_code, s.name as shop_name
            from account_health_performance_forecast a
            left join shop s on s.tenant_id = a.tenant_id and s.id = a.shop_id
            """
                + clause
                + " order by a.forecasted_at desc, a.id desc limit ? offset ?",
            pageParams.toArray())) {
      items.add(forecastOf(row));
    }
    return new PerformanceMetricForecastPage(
        items,
        total,
        Math.max(0, page),
        limit,
        byResult.getOrDefault(RESULT_WILL_BREACH, 0L),
        byResult.getOrDefault(RESULT_ALREADY_BREACHED, 0L),
        byResult.getOrDefault(RESULT_NO_BREACH, 0L),
        byResult.getOrDefault(RESULT_CANNOT_PREDICT, 0L),
        FORECAST_SEMANTICS);
  }

  private PerformanceMetricForecast forecastOf(Record row) {
    String metricCode = row.get("metric_code", String.class);
    String unit = row.get("unit", String.class);
    BigDecimal predicted = decimalOf(row, "predicted_value");
    BigDecimal threshold = decimalOf(row, "threshold_value");
    String breachLevel = row.get("breach_level", String.class);
    Integer breachInPeriods = row.get("breach_in_periods", Integer.class);

    List<PerformanceMetricUsedPeriod> used = new ArrayList<>();
    String usedJson = row.get("used_json", String.class);
    if (usedJson != null && !usedJson.isBlank()) {
      try {
        for (Map<String, Object> item :
            json.readValue(usedJson, new TypeReference<List<Map<String, Object>>>() {})) {
          Map<String, Object> safe = item == null ? Map.of() : item;
          used.add(
              new PerformanceMetricUsedPeriod(
                  intValue(safe.get("index")),
                  stringValue(safe.get("periodStart")),
                  stringValue(safe.get("periodEnd")),
                  decimalValue(safe.get("value"))));
        }
      } catch (Exception ignored) {
        // 解析不出来就当没有明细（结论本身仍在 result_reason 里）。
      }
    }

    return new PerformanceMetricForecast(
        row.get("id", UUID.class).toString(),
        row.get("shop_id", UUID.class).toString(),
        row.get("shop_code", String.class),
        row.get("shop_name", String.class),
        metricCode,
        METRIC_LABELS.getOrDefault(metricCode, metricCode),
        unit,
        UNIT_LABELS.getOrDefault(unit, unit),
        row.get("method", String.class),
        row.get("method_note", String.class),
        row.get("result", String.class),
        resultLabel(row.get("result", String.class)),
        row.get("result_reason", String.class),
        breachLevel,
        breachLevelLabel(breachLevel),
        breachInPeriods,
        predicted,
        formatValue(predicted, unit),
        threshold,
        formatValue(threshold, unit),
        row.get("threshold_kind", String.class),
        row.get("direction", String.class),
        directionText(row.get("direction", String.class)),
        intOf(row, "known_points"),
        intOf(row, "unknown_points"),
        intOf(row, "window_points"),
        List.copyOf(used),
        decimalOf(row, "slope"),
        longOf(row, "rule_version"),
        row.get("rule_source", String.class),
        ruleSourceLabel(row.get("rule_source", String.class)),
        readJsonMap(row.get("thresholds_json", String.class)),
        offsetText(row.get("forecasted_at", OffsetDateTime.class)));
  }

  private static int intValue(Object raw) {
    return raw instanceof Number number ? number.intValue() : 0;
  }

  private static String stringValue(Object raw) {
    return raw == null ? "" : String.valueOf(raw);
  }

  private static BigDecimal decimalValue(Object raw) {
    if (raw == null) {
      return null;
    }
    if (raw instanceof BigDecimal decimal) {
      return decimal;
    }
    if (raw instanceof Number number) {
      return new BigDecimal(number.toString());
    }
    try {
      return new BigDecimal(String.valueOf(raw));
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * 跑一次跌破预测，落**不可改写**的凭据（每个指标一条）。
   *
   * <p>🔴 点数不足 ⇒ {@code CANNOT_PREDICT} + 原因，**绝不给一个假预测**。
   */
  public PerformanceMetricForecastRun forecast(String shopId, PerformanceMetricForecastInput input) {
    UUID tenant = currentTenant();
    UUID id = parseUuid(shopId);
    if (id == null) {
      throw notFound();
    }
    ShopRow shop = requireShop(tenant, id);

    int window =
        input == null || input.windowPoints() == null
            ? DEFAULT_FORECAST_WINDOW
            : clamp(input.windowPoints(), MIN_FORECAST_POINTS, MAX_FORECAST_WINDOW);
    int horizon =
        input == null || input.horizonPeriods() == null
            ? DEFAULT_HORIZON
            : clamp(input.horizonPeriods(), 1, MAX_HORIZON);

    Map<String, AlertThreshold> thresholds = loadThresholds(tenant);
    Map<String, Object> snapshot = thresholdSnapshot(thresholds);
    OffsetDateTime now = dbNow();

    List<PerformanceMetricForecast> results = new ArrayList<>();
    for (String code : METRIC_CODES) {
      Map<UUID, List<TrendPoint>> byShop = loadPoints(tenant, List.of(shop), code, window);
      List<TrendPoint> points = byShop.getOrDefault(shop.id(), List.of());
      AlertThreshold threshold = thresholds.get(code);
      Forecast forecast = PerformanceMetricRules.forecast(points, threshold, horizon);
      String unit = threshold == null ? METRIC_UNITS.getOrDefault(code, UNIT_RATIO) : threshold.unit();
      String methodNote = methodNote(window, forecast.knownPoints(), horizon);

      List<Map<String, Object>> usedPayload = new ArrayList<>();
      for (UsedPeriod period : forecast.usedPeriods()) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("index", period.index());
        item.put("periodStart", period.periodStart());
        item.put("periodEnd", period.periodEnd());
        item.put("value", period.value());
        usedPayload.add(item);
      }

      UUID forecastId = UUID.randomUUID();
      sql.execute(
          """
          insert into account_health_performance_forecast
            (id, tenant_id, shop_id, metric_code, unit, forecasted_at, method, method_note,
             window_points, known_points, unknown_points, used_periods, slope, intercept,
             result, result_reason, breach_level, breach_in_periods, predicted_value,
             threshold_value, threshold_kind, direction, rule_version, rule_source,
             thresholds, created_at)
          values (?, ?, ?, ?, ?, ?::timestamptz, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?,
                  ?, ?, ?, ?, ?::jsonb, now())
          """,
          forecastId,
          tenant,
          id,
          code,
          unit,
          now,
          METHOD_LINEAR,
          methodNote,
          window,
          forecast.knownPoints(),
          forecast.unknownPoints(),
          writeJson(usedPayload),
          forecast.slope(),
          forecast.intercept(),
          forecast.result(),
          forecast.reason(),
          forecast.breachLevel(),
          forecast.breachInPeriods(),
          forecast.predictedValue(),
          forecast.thresholdValue(),
          forecast.thresholdKind(),
          threshold == null ? builtinDirection(code) : threshold.direction(),
          threshold == null ? 1L : threshold.ruleVersion(),
          threshold == null ? RULE_SOURCE_BUILTIN : threshold.ruleSource(),
          writeJson(snapshot));

      results.add(
          new PerformanceMetricForecast(
              forecastId.toString(),
              id.toString(),
              shop.code(),
              shop.name(),
              code,
              METRIC_LABELS.getOrDefault(code, code),
              unit,
              UNIT_LABELS.getOrDefault(unit, unit),
              METHOD_LINEAR,
              methodNote,
              forecast.result(),
              resultLabel(forecast.result()),
              forecast.reason(),
              forecast.breachLevel(),
              severityLabel(forecast.breachLevel()),
              forecast.breachInPeriods(),
              forecast.predictedValue(),
              formatValue(forecast.predictedValue(), unit),
              forecast.thresholdValue(),
              formatValue(forecast.thresholdValue(), unit),
              forecast.thresholdKind(),
              threshold == null ? builtinDirection(code) : threshold.direction(),
              directionText(threshold == null ? builtinDirection(code) : threshold.direction()),
              forecast.knownPoints(),
              forecast.unknownPoints(),
              window,
              usedPeriodsOf(forecast),
              forecast.slope(),
              threshold == null ? 1L : threshold.ruleVersion(),
              threshold == null ? RULE_SOURCE_BUILTIN : threshold.ruleSource(),
              ruleSourceLabel(threshold == null ? RULE_SOURCE_BUILTIN : threshold.ruleSource()),
              snapshot,
              now.toString()));
    }
    return new PerformanceMetricForecastRun(
        id.toString(),
        shop.code(),
        shop.name(),
        now.toString(),
        List.copyOf(results),
        FORECAST_SEMANTICS);
  }

  private static List<PerformanceMetricUsedPeriod> usedPeriodsOf(Forecast forecast) {
    List<PerformanceMetricUsedPeriod> used = new ArrayList<>();
    for (UsedPeriod period : forecast.usedPeriods()) {
      used.add(
          new PerformanceMetricUsedPeriod(
              period.index(), period.periodStart(), period.periodEnd(), period.value()));
    }
    return List.copyOf(used);
  }

  /** 该店**最近一次**的预测结论（每个指标一条）。 */
  public PerformanceMetricForecastRun latestForecast(String shopId) {
    UUID tenant = currentTenant();
    UUID id = parseUuid(shopId);
    if (id == null) {
      throw notFound();
    }
    ShopRow shop = requireShop(tenant, id);

    List<PerformanceMetricForecast> results = new ArrayList<>();
    for (Record row :
        sql.fetch(
            """
            select distinct on (a.metric_code)
                   a.id, a.shop_id, a.metric_code, a.unit, a.forecasted_at, a.method, a.method_note,
                   a.window_points, a.known_points, a.unknown_points, a.used_periods::text as used_json,
                   a.slope, a.intercept, a.result, a.result_reason, a.breach_level,
                   a.breach_in_periods, a.predicted_value, a.threshold_value, a.threshold_kind,
                   a.direction, a.rule_version, a.rule_source, a.thresholds::text as thresholds_json,
                   s.code as shop_code, s.name as shop_name
            from account_health_performance_forecast a
            left join shop s on s.tenant_id = a.tenant_id and s.id = a.shop_id
            where a.tenant_id = ? and a.shop_id = ?
            order by a.metric_code, a.forecasted_at desc, a.id desc
            """,
            tenant,
            id)) {
      results.add(forecastOf(row));
    }
    String at =
        results.isEmpty()
            ? ""
            : results.stream()
                .map(PerformanceMetricForecast::forecastedAt)
                .filter(value -> value != null && !value.isEmpty())
                .max(String::compareTo)
                .orElse("");
    return new PerformanceMetricForecastRun(
        id.toString(), shop.code(), shop.name(), at, List.copyOf(results), FORECAST_SEMANTICS);
  }

  // ==================================================================
  // 本模块自有指标录入
  // ==================================================================

  /**
   * 手工录入一条**本模块自有**的指标（响应时间 / 账户评级）。
   *
   * <p>🔴 <b>source 恒为 MANUAL</b>：入参里**没有** source 字段，服务端写死。
   * <p>🔴 四个上游码**不接受**在这里录入 —— 它们归 16.1.1.1，本模块**只读**它。
   */
  public PerformanceMetricSeries recordMetric(String shopId, PerformanceMetricMetricInput input) {
    UUID tenant = currentTenant();
    UUID id = parseUuid(shopId);
    if (id == null) {
      throw notFound();
    }
    ShopRow shop = requireShop(tenant, id);
    String code = text(input == null ? null : input.metricCode()).toUpperCase();
    if (!OWN_METRIC_CODES.contains(code)) {
      throw unprocessable(
          "这里只能录入本模块自有的指标（"
              + String.join(" / ", OWN_METRIC_CODES)
              + "）；另外四项归 16.1.1.1，请在账号健康总览里录入。");
    }
    BigDecimal value = input == null ? null : input.metricValue();
    String unit = METRIC_UNITS.getOrDefault(code, UNIT_RATIO);
    BigDecimal max = UNIT_MAX.getOrDefault(unit, BigDecimal.ONE);
    if (value != null && (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(max) > 0)) {
      throw unprocessable(
          "数值必须落在该指标的量纲范围内（"
              + UNIT_LABELS.getOrDefault(unit, unit)
              + "，0.."
              + max.toPlainString()
              + "）");
    }
    LocalDate periodStart = parseDate(input == null ? null : input.periodStart(), "周期起点");
    LocalDate periodEnd = parseDate(input == null ? null : input.periodEnd(), "周期终点");
    if (periodEnd.isBefore(periodStart)) {
      throw unprocessable("周期终点不能早于周期起点");
    }

    OffsetDateTime now = dbNow();
    sql.execute(
        """
        insert into account_health_performance_metric
          (id, tenant_id, shop_id, period_start, period_end, metric_code, metric_value, unit,
           source, captured_at, recorded_by, note, created_at, updated_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, 'MANUAL', ?::timestamptz, ?, ?, now(), now())
        on conflict (tenant_id, shop_id, period_start, period_end, metric_code)
        do update set metric_value = excluded.metric_value,
                      unit = excluded.unit,
                      source = excluded.source,
                      captured_at = excluded.captured_at,
                      recorded_by = excluded.recorded_by,
                      note = excluded.note,
                      updated_at = now()
        """,
        UUID.randomUUID(),
        tenant,
        id,
        periodStart,
        periodEnd,
        code,
        value,
        unit,
        now,
        currentActor(),
        text(input == null ? null : input.note()));

    Map<UUID, List<TrendPoint>> byShop =
        loadPoints(tenant, List.of(shop), code, DEFAULT_TREND_WINDOW);
    return series(shop, code, byShop.getOrDefault(shop.id(), List.of()));
  }
}
