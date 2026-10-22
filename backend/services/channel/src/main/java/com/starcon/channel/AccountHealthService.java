package com.starcon.channel;

import static com.starcon.channel.AccountHealthModels.*;
import static com.starcon.channel.AccountHealthRules.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.channel.vendor.PlatformPerformancePort;
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
 * 16.1.1.1 账号健康总览。
 *
 * <p>蓝图：「按店铺展示账号健康状态：绩效指标（ODR、迟发率、取消率、有效追踪率）、政策违规计数、
 * 申诉状态、风险等级，支持跨店铺对比与风险排序。输入：平台绩效数据同步；风险等级计算规则；
 * 用户数据范围（店铺维度）。输出：店铺健康卡片 + 风险排序 + 需处理项跳转。
 * 关系：上游 = 2 同步；下游 = 16.1.1.2 指标明细、16.1.2.1 违规。
 * <b>风险排序比明细更重要</b>：先处理最可能掉号的店铺。」
 *
 * <p>🔴🔴 本类里有六处<b>结构</b>上的不变量：
 *
 * <ol>
 *   <li><b>店铺清单来自本服务自己的店铺目录</b>（{@code shop} / {@code platform}），
 *       <b>不写死店铺常量、不造平行店铺表</b>。</li>
 *   <li><b>量不到 ≠ 0</b>：{@code metric_value} 为 NULL 的指标不进已知数、<b>不算通过</b>。</li>
 *   <li><b>量不到 ≠ 低风险</b>：四项没量全就<b>不能</b>断言 {@code HEALTHY}，
 *       等级是 {@code UNKNOWN}（判定逻辑在 {@link AccountHealthRules}）。</li>
 *   <li><b>阈值在服务端</b>：默认阈值是模块的默认政策、可按租户覆盖；
 *       覆盖推进 {@code rule_version}，历史判定**不追溯改变**（V300 的触发器钉住）。</li>
 *   <li><b>排序真的按风险排</b>，且每一家都带得出「为什么排在这个位置」。</li>
 *   <li><b>平台绩效同步未接入</b> ⇒ 同步端点<b>恒 409 且一条数据都不写</b>；
 *       手工录入的数据<b>显式标 MANUAL</b>，不冒充平台同步。</li>
 * </ol>
 */
@Service
public class AccountHealthService {

  /** 风险排序的口径说明（能力声明与排序清单共用同一句话）。 */
  private static final String RANKING_SEMANTICS =
      "🔴 排序口径（**风险排序比明细更重要**：先处理最可能掉号的店铺）："
          + "先按风险等级 CRITICAL → WARNING → UNKNOWN → HEALTHY；"
          + "同级比命中阈值的指标数（多的先），再比最大越线幅度（越线越狠的先），"
          + "最后按店铺编码稳定排序（保证分页不因并列而抖动）。"
          + "UNKNOWN 排在 HEALTHY 前面 —— 量不全的店铺**还不知道**会不会掉号，"
          + "而量全且未命中阈值的店铺是确凿健康。";

  /** 指标值的口径说明。 */
  private static final String METRIC_VALUE_SEMANTICS =
      "🔴 指标值一律是 **0..1 比值**（0.028 = 2.8%），**NULL = 该指标量不到**，**不是 0**。"
          + "量不到的指标不进已知数、**不算通过**；四项没量全时风险等级只能是 UNKNOWN，"
          + "不能断言「健康」。";

  /** 阈值的口径说明。 */
  private static final String THRESHOLD_SEMANTICS =
      "🔴 阈值**在服务端算**，前端只显示结论与依据，不重算。"
          + "内置默认阈值是**本模块的默认政策**（各平台口径并不通用，不是平台下发的数字），"
          + "可按租户覆盖；覆盖产生**新版本**（rule_version），"
          + "历史判定仍然是旧判定（判定凭据里带着当时用到的阈值快照）。";

  /** 「量不到」的语义。 */
  private static final String UNKNOWN_SEMANTICS =
      "🔴「量不到」是一个独立状态，不是 0、也不是低风险："
          + "指标值为 NULL 时该指标不进已知数、不算通过；"
          + "四项没量全 ⇒ 风险等级 UNKNOWN（**不能**断言健康）。"
          + "把「没测到」写成「健康」是最危险的一种撒谎。";

  /** 平台绩效同步的缺口说明。 */
  private static final String PLATFORM_SYNC_NOTE =
      "🔴 平台账号绩效（ODR / 迟发率 / 取消率 / 有效追踪率）**同步适配器未交付**，"
          + "归卷 02「同步运行」。因此：① 同步端点恒 409 且**一条数据都不写**；"
          + "② 绩效数据目前只能**人工录入**，录入的条目一律标 **MANUAL**，"
          + "页面上能看出这条是手工来的；③ 本模块**绝不返回假指标** —— "
          + "一条「ODR = 3.2%」的假同步会让运营以为账号真的在掉号。";

  /** 上游说明。 */
  private static final String UPSTREAM_NOTE =
      "上游 = 2.4 同步运行（平台账号绩效适配器，**当前未交付**）"
          + "+ 本服务自己的店铺目录（2.1.1.1 `shop` / `platform`，"
          + "健康卡片的店铺清单**直接读它**，不造平行店铺表）。";

  /** 下游说明。 */
  private static final String DOWNSTREAM_NOTE =
      "下游 = 16.1.1.2 绩效指标监控（趋势 / 阈值预警 / 跌破预测，**已交付**但"
          + "**本模块未接入**）、"
          + "16.1.2.1 违规与处罚（**已交付**但**本模块未接入** ⇒ 本模块**不编造**政策违规计数）、"
          + "16.1.2.2 申诉管理（**已交付**但**本模块未接入** ⇒ 本模块**不编造**申诉状态，"
          + "申诉事实以 16.1.2.2 为准）。"
          + "「需处理项」只给**本模块自己真实存在**的待处理项，跳转目标是本模块自己的页面。";

  private final DSLContext sql;
  private final ObjectMapper json;
  private final PlatformPerformancePort platformPerformance;

  public AccountHealthService(
      DSLContext sql, ObjectMapper json, PlatformPerformancePort platformPerformance) {
    this.sql = sql;
    this.json = json;
    this.platformPerformance = platformPerformance;
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

  /** 🔴 <b>同源 DB 时钟</b>：判定时刻 / 采集时刻一律从这里取。 */
  private OffsetDateTime dbNow() {
    Object raw = sql.fetchValue("select now()");
    return raw instanceof OffsetDateTime value ? value : OffsetDateTime.now(ZoneOffset.UTC);
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
   * 店铺筛选参数。
   *
   * <p>🔴 非法 UUID **按「筛不出东西」处理**而不是报错 —— 筛选参数是用户敲进来的，
   * 敲错一个字符就整页 422 反而更难用；返回空列表即可（页面上会显示「筛选无结果」）。
   *
   * <p>🔴 但**绝不能**当成「没筛选」：那会让用户以为筛选没生效，结果看到的是**全部店铺**。
   * 这里用一个不存在的哨兵 id 保证结果是空集。
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

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 能力声明：哪些维度量不到、量不到归谁、结论与排序的口径是什么。
   *
   * <p>🔴 真正<b>未交付</b>的下游只有 2.4 同步运行（平台账号绩效适配器）；
   * 16.1.1.2 绩效指标监控、16.1.2.1 违规与处罚、16.1.2.2 申诉管理<b>已交付</b>
   * （同服务同库）但<b>本模块未接入</b> ⇒ 三者仍如实标 {@code available=false}
   * + ownerModule + 影响说明 —— 标的是「<b>本模块没有接入</b>」，不是「那个模块没交付」。
   */
  public AccountHealthCapabilities capabilities() {
    List<AccountHealthCapabilityGap> gaps =
        List.of(
            new AccountHealthCapabilityGap(
                "2.4",
                "同步运行（平台账号绩效适配器）",
                "2.4",
                false,
                "平台账号绩效（ODR / 迟发率 / 取消率 / 有效追踪率）的拉取适配器**未交付** ⇒ "
                    + "本模块**拉不到**任何平台绩效数据：同步端点恒 409 且一条数据都不写，"
                    + "绩效指标目前只能**人工录入**（录入的条目一律标 MANUAL，"
                    + "页面上一眼能看出这条是手工来的）。"
                    + "本模块**绝不返回假指标** —— 一条「ODR = 3.2%」的假同步会让运营"
                    + "以为账号真的在掉号，而现场一个真实指标都没有。"),
            new AccountHealthCapabilityGap(
                "16.1.1.2",
                "绩效指标监控",
                "16.1.1.2",
                false,
                "绩效指标监控**已交付**（同服务同库，V304 account_health_performance_*）"
                    + "⇒ 但**本模块未接入**：本模块只给**当前**状态与跨店铺风险排序，"
                    + "不做指标趋势图、不做阈值预警、不做「是否会跌破阈值」的预测。"),
            new AccountHealthCapabilityGap(
                "16.1.2.1",
                "违规与处罚",
                "16.1.2.1",
                false,
                "违规与处罚**已交付**（同服务同库，V308 platform_violation*）"
                    + "⇒ 但**本模块未接入**：本模块**不编造**政策违规计数。"
                    + "蓝图输出里的「政策违规计数」这一维当前**没有数据**，"
                    + "而不是「违规数为 0」；违规事实以 16.1.2.1 为准。"),
            new AccountHealthCapabilityGap(
                "16.1.2.2",
                "申诉管理",
                "16.1.2.2",
                false,
                "申诉管理**已交付**（同服务同库，V310 platform_appeal*）"
                    + "⇒ 但**本模块未接入**：本模块**不编造**申诉状态，"
                    + "申诉事实以 16.1.2.2 为准；也**不提供**指向那个模块的跳转 —— "
                    + "跳转目标只能是本模块自己真实存在的页面。"));
    return new AccountHealthCapabilities(
        gaps,
        METRIC_CODES,
        List.of(LEVEL_CRITICAL, LEVEL_WARNING, LEVEL_HEALTHY, LEVEL_UNKNOWN),
        List.of(SOURCE_PLATFORM_SYNC, SOURCE_MANUAL),
        METRIC_VALUE_SEMANTICS,
        THRESHOLD_SEMANTICS,
        UNKNOWN_SEMANTICS,
        RANKING_SEMANTICS,
        PLATFORM_SYNC_NOTE,
        UPSTREAM_NOTE,
        DOWNSTREAM_NOTE);
  }

  // ==================================================================
  // 内部形状
  // ==================================================================

  /** 店铺目录里的一行。 */
  private record ShopRow(
      UUID id, String code, String name, String platformCode, String platformName) {}

  /** 一个店铺的完整上下文（店铺 + 指标读数 + 判定结果 + 本次生效的阈值）。 */
  private record ShopContext(
      ShopRow shop,
      Map<String, MetricReading> readings,
      Evaluation evaluation,
      Map<String, Threshold> thresholds,
      int rank,
      String rankReason) {}

  // ==================================================================
  // 店铺目录 + 指标读数
  // ==================================================================

  /**
   * 🔴 店铺清单**直接读本服务的店铺目录**（{@code shop} / {@code platform}），
   * 不写死店铺常量、不造平行店铺表。{@code shop} 表自带 data_scope 策略，
   * 可见性随店铺负责人 / 部门收敛 —— 这就是蓝图输入句「用户数据范围（店铺维度）」。
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

  /**
   * 每个店铺每个指标的<b>最新一条</b>读数。
   *
   * <p>🔴 「最新」的次序是 {@code period_end desc, captured_at desc, id desc} ——
   * 先按周期、再按采集时刻、最后按 id 兜底，<b>并列时结果确定</b>，
   * 不会出现同一份数据两次读取给出两个答案。
   */
  private Map<UUID, Map<String, MetricReading>> loadReadings(UUID tenant, List<ShopRow> shops) {
    Map<UUID, Map<String, MetricReading>> result = new LinkedHashMap<>();
    if (shops.isEmpty()) {
      return result;
    }
    StringBuilder placeholders = new StringBuilder();
    List<Object> params = new ArrayList<>();
    params.add(tenant);
    for (ShopRow shop : shops) {
      placeholders.append(placeholders.length() == 0 ? "?" : ",?");
      params.add(shop.id());
    }
    String statement =
        """
        select distinct on (m.shop_id, m.metric_code)
               m.shop_id, m.metric_code, m.metric_value, m.source,
               m.period_start, m.period_end, m.captured_at, m.note
        from account_health_metric m
        where m.tenant_id = ? and m.shop_id in ("""
            + placeholders
            + """
        )
        order by m.shop_id, m.metric_code, m.period_end desc, m.captured_at desc, m.id desc
        """;

    for (Record row : sql.fetch(statement, params.toArray())) {
      UUID shopId = row.get("shop_id", UUID.class);
      String metricCode = row.get("metric_code", String.class);
      Map<String, MetricReading> byMetric =
          result.computeIfAbsent(shopId, key -> new LinkedHashMap<>());
      byMetric.put(
          metricCode,
          new MetricReading(
              metricCode,
              decimalOf(row, "metric_value"),
              row.get("source", String.class),
              localDateText(row.get("period_start", LocalDate.class)),
              localDateText(row.get("period_end", LocalDate.class)),
              offsetText(row.get("captured_at", OffsetDateTime.class)),
              row.get("note", String.class)));
    }
    return result;
  }

  private static String localDateText(LocalDate value) {
    return value == null ? "" : value.toString();
  }

  private static String offsetText(OffsetDateTime value) {
    return value == null ? "" : value.toString();
  }

  /** 租户自定义的阈值规则（没覆盖的用内置默认）。 */
  private Map<String, Threshold> loadThresholds(UUID tenant) {
    Map<String, Threshold> overrides = new LinkedHashMap<>();
    for (Record row :
        sql.fetch(
            """
            select metric_code, direction, warn_value, critical_value, rule_version
            from account_health_threshold
            where tenant_id = ? and enabled
            """,
            tenant)) {
      overrides.put(
          row.get("metric_code", String.class),
          new Threshold(
              row.get("metric_code", String.class),
              row.get("direction", String.class),
              decimalOf(row, "warn_value"),
              decimalOf(row, "critical_value"),
              longOf(row, "rule_version"),
              RULE_SOURCE_CUSTOM));
    }
    return effectiveThresholds(overrides);
  }

  /** 加载全部店铺的上下文并**按风险排好序**。 */
  private List<ShopContext> loadRanked(UUID tenant, String platformCode, String keyword, UUID shopId) {
    List<ShopRow> shops = loadShops(tenant, platformCode, keyword, shopId);
    Map<UUID, Map<String, MetricReading>> readings = loadReadings(tenant, shops);
    Map<String, Threshold> thresholds = loadThresholds(tenant);

    List<RankedShop> ranked = new ArrayList<>();
    for (ShopRow shop : shops) {
      Evaluation evaluation = evaluate(readings.getOrDefault(shop.id(), Map.of()), thresholds);
      ranked.add(
          new RankedShop(shop.id().toString(), shop.code(), shop.name(), shop.platformCode(), evaluation));
    }
    ranked.sort(AccountHealthRules.ranking());

    List<ShopContext> contexts = new ArrayList<>();
    int rank = 1;
    for (RankedShop entry : ranked) {
      ShopRow shop =
          new ShopRow(
              UUID.fromString(entry.shopId()),
              entry.shopCode(),
              entry.shopName(),
              entry.platformCode(),
              platformNameOf(shops, entry.shopId()));
      int position = rank++;
      contexts.add(
          new ShopContext(
              shop,
              readings.getOrDefault(shop.id(), Map.of()),
              entry.evaluation(),
              thresholds,
              position,
              rankReason(entry.evaluation(), position)));
    }
    return contexts;
  }

  private static String platformNameOf(List<ShopRow> shops, String shopId) {
    UUID id = UUID.fromString(shopId);
    for (ShopRow shop : shops) {
      if (shop.id().equals(id)) {
        return shop.platformName();
      }
    }
    return "";
  }

  // ==================================================================
  // 卡片 / 排序
  // ==================================================================

  /** 健康卡片列表（**真分页 + 真筛选**，排序恒为风险优先）。 */
  public AccountHealthShopPage shops(
      String platformCode, String keyword, String riskLevel, String dataState, String shopId,
      int page, int size) {
    UUID tenant = currentTenant();
    int limit = Math.max(1, Math.min(size, 200));
    List<ShopContext> contexts = loadRanked(tenant, platformCode, keyword, parseFilterShopId(shopId));

    String level = text(riskLevel).toUpperCase();
    if (!level.isEmpty() && !RISK_LEVELS.contains(level)) {
      throw unprocessable("风险等级只能是 " + String.join(" / ", RISK_LEVELS));
    }
    String state = text(dataState).toUpperCase();
    if (!state.isEmpty() && !List.of("HAS_DATA", "NO_DATA").contains(state)) {
      throw unprocessable("数据状态只能是 HAS_DATA / NO_DATA");
    }

    List<ShopContext> filtered = new ArrayList<>();
    for (ShopContext context : contexts) {
      if (!level.isEmpty() && !level.equals(context.evaluation().riskLevel())) {
        continue;
      }
      boolean hasData = context.evaluation().knownMetricCount() > 0;
      if ("HAS_DATA".equals(state) && !hasData) {
        continue;
      }
      if ("NO_DATA".equals(state) && hasData) {
        continue;
      }
      filtered.add(context);
    }

    int offset = Math.max(0, page) * limit;
    List<AccountHealthShopCard> items = new ArrayList<>();
    for (int index = offset; index < Math.min(offset + limit, filtered.size()); index++) {
      items.add(card(filtered.get(index)));
    }
    return new AccountHealthShopPage(items, filtered.size(), Math.max(0, page), limit);
  }

  /** 单家店的健康卡片。跨租户与不存在返回逐字相同的 404。 */
  public AccountHealthShopCard shop(String shopId) {
    UUID tenant = currentTenant();
    UUID id = parseUuid(shopId);
    if (id == null) {
      throw notFound();
    }
    List<ShopContext> contexts = loadRanked(tenant, null, null, id);
    if (contexts.isEmpty()) {
      throw notFound();
    }
    return card(contexts.get(0));
  }

  /** 跨店铺风险排序（**真分页**，名次是**全局名次**）。 */
  public AccountHealthRankingPage ranking(
      String platformCode, String keyword, String riskLevel, String shopId, int page, int size) {
    UUID tenant = currentTenant();
    int limit = Math.max(1, Math.min(size, 200));
    List<ShopContext> contexts = loadRanked(tenant, platformCode, keyword, parseFilterShopId(shopId));

    String level = text(riskLevel).toUpperCase();
    if (!level.isEmpty() && !RISK_LEVELS.contains(level)) {
      throw unprocessable("风险等级只能是 " + String.join(" / ", RISK_LEVELS));
    }

    List<ShopContext> filtered = new ArrayList<>();
    long critical = 0;
    long warning = 0;
    long unknown = 0;
    long healthy = 0;
    for (ShopContext context : contexts) {
      switch (context.evaluation().riskLevel()) {
        case LEVEL_CRITICAL -> critical++;
        case LEVEL_WARNING -> warning++;
        case LEVEL_UNKNOWN -> unknown++;
        case LEVEL_HEALTHY -> healthy++;
        default -> {
          // 理论上到不了这里：等级只有四种，判定层保证。
        }
      }
      if (!level.isEmpty() && !level.equals(context.evaluation().riskLevel())) {
        continue;
      }
      filtered.add(context);
    }

    int offset = Math.max(0, page) * limit;
    List<AccountHealthRankingRow> items = new ArrayList<>();
    for (int index = offset; index < Math.min(offset + limit, filtered.size()); index++) {
      ShopContext context = filtered.get(index);
      items.add(
          new AccountHealthRankingRow(
              context.rank(),
              context.shop().id().toString(),
              context.shop().code(),
              context.shop().name(),
              context.shop().platformCode(),
              context.evaluation().riskLevel(),
              levelLabel(context.evaluation().riskLevel()),
              context.evaluation().knownMetricCount(),
              context.evaluation().breachCount(),
              context.evaluation().knownMetricCount() < METRIC_CODES.size(),
              context.rankReason()));
    }
    return new AccountHealthRankingPage(
        items,
        filtered.size(),
        Math.max(0, page),
        limit,
        critical,
        warning,
        unknown,
        healthy,
        RANKING_SEMANTICS);
  }

  /** 健康卡片。 */
  private AccountHealthShopCard card(ShopContext context) {
    Evaluation evaluation = context.evaluation();
    List<AccountHealthMetricCard> metrics = new ArrayList<>();
    for (String code : METRIC_CODES) {
      MetricReading reading = context.readings().get(code);
      metrics.add(
          new AccountHealthMetricCard(
              code,
              METRIC_LABELS.getOrDefault(code, code),
              reading == null ? null : reading.value(),
              reading == null ? null : reading.source(),
              reading == null ? null : sourceLabel(reading.source()),
              reading == null ? null : reading.periodStart(),
              reading == null ? null : reading.periodEnd(),
              reading == null ? null : reading.capturedAt(),
              reading == null ? null : reading.note()));
    }
    LocalDate periodStart = null;
    LocalDate periodEnd = null;
    for (MetricReading reading : context.readings().values()) {
      if (reading.value() == null) {
        continue;
      }
      LocalDate start = parseDateOrNull(reading.periodStart());
      LocalDate end = parseDateOrNull(reading.periodEnd());
      if (start != null && (periodStart == null || start.isBefore(periodStart))) {
        periodStart = start;
      }
      if (end != null && (periodEnd == null || end.isAfter(periodEnd))) {
        periodEnd = end;
      }
    }
    Map<String, Threshold> thresholds = context.thresholds();
    return new AccountHealthShopCard(
        context.shop().id().toString(),
        context.shop().code(),
        context.shop().name(),
        context.shop().platformCode(),
        context.shop().platformName(),
        evaluation.riskLevel(),
        levelLabel(evaluation.riskLevel()),
        context.rank(),
        context.rankReason(),
        evaluation.knownMetricCount(),
        evaluation.breachCount(),
        evaluation.knownMetricCount() < METRIC_CODES.size(),
        periodStart == null ? null : periodStart.toString(),
        periodEnd == null ? null : periodEnd.toString(),
        metrics,
        evaluation.reasons(),
        evaluation.unknownMetricCodes(),
        ruleSourceOf(thresholds),
        ruleVersionOf(thresholds),
        platformPerformance.availability(context.shop().platformCode()).available());
  }

  private static LocalDate parseDateOrNull(String raw) {
    try {
      return raw == null || raw.isBlank() ? null : LocalDate.parse(raw);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** 「为什么排在这个位置」。 */
  private static String rankReason(Evaluation evaluation, int rank) {
    String prefix = "第 " + rank + " 位：";
    switch (evaluation.riskLevel()) {
      case LEVEL_CRITICAL:
        return prefix
            + "命中 "
            + evaluation.breachCount()
            + " 项阈值（最狠的一项越线 "
            + percent(evaluation.worstMargin())
            + "），**先处理这家**。";
      case LEVEL_WARNING:
        return prefix + "命中 " + evaluation.breachCount() + " 项警告阈值，需要尽快跟进。";
      case LEVEL_UNKNOWN:
        return prefix
            + "只量到 "
            + evaluation.knownMetricCount()
            + "/"
            + METRIC_CODES.size()
            + " 项指标，**判不了**（量不到不等于健康），先补数据。";
      case LEVEL_HEALTHY:
        return prefix + "四项指标齐全且都未命中阈值，确凿健康。";
      default:
        return prefix + "状态未知。";
    }
  }

  // ==================================================================
  // 手工录入绩效指标
  // ==================================================================

  /**
   * 手工录入一条绩效指标。
   *
   * <p>🔴 <b>source 恒为 MANUAL</b>：入参里**没有** source 字段，服务端写死。
   * 人工录入的那部分**绝不能冒充平台同步来的** —— 界面上要能看出这条是手工来的。
   */
  public AccountHealthShopCard recordMetric(String shopId, AccountHealthMetricInput input) {
    UUID tenant = currentTenant();
    UUID id = parseUuid(shopId);
    if (id == null) {
      throw notFound();
    }
    String metricCode = text(input == null ? null : input.metricCode()).toUpperCase();
    if (!METRIC_CODES.contains(metricCode)) {
      throw unprocessable("指标只能是 " + String.join(" / ", METRIC_CODES));
    }
    BigDecimal value = input == null ? null : input.metricValue();
    if (value != null
        && (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0)) {
      throw unprocessable("指标值必须是 0..1 的比值（例如 2.8% 写 0.0280）");
    }
    LocalDate periodStart = parseDate(input == null ? null : input.periodStart(), "周期起点");
    LocalDate periodEnd = parseDate(input == null ? null : input.periodEnd(), "周期终点");
    if (periodEnd.isBefore(periodStart)) {
      throw unprocessable("周期终点不能早于周期起点");
    }
    requireShop(tenant, id);

    OffsetDateTime now = dbNow();
    UUID actor = currentActor();
    sql.execute(
        """
        insert into account_health_metric
          (id, tenant_id, shop_id, period_start, period_end, metric_code, metric_value,
           source, captured_at, recorded_by, note, created_at, updated_at)
        values (?, ?, ?, ?, ?, ?, ?, 'MANUAL', ?::timestamptz, ?, ?, now(), now())
        on conflict (tenant_id, shop_id, period_start, period_end, metric_code)
        do update set metric_value = excluded.metric_value,
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
        metricCode,
        value,
        now,
        actor,
        text(input == null ? null : input.note()));
    return shop(shopId);
  }

  private UUID currentActor() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.actor',true),'')");
    return raw == null ? null : UUID.fromString(String.valueOf(raw));
  }

  // ==================================================================
  // 判定（追加式凭据）
  // ==================================================================

  /**
   * 跑一次账号健康判定，落一条**不可改写**的凭据。
   *
   * <p>🔴 凭据里带着**当时用到的阈值快照**与规则版本 ⇒ 阈值改了也不追溯改变历史判定。
   */
  public AccountHealthAssessmentResult assess(String shopId) {
    UUID tenant = currentTenant();
    UUID id = parseUuid(shopId);
    if (id == null) {
      throw notFound();
    }
    List<ShopContext> contexts = loadRanked(tenant, null, null, id);
    if (contexts.isEmpty()) {
      throw notFound();
    }
    ShopContext context = contexts.get(0);
    Map<String, Threshold> thresholds = loadThresholds(tenant);
    Evaluation evaluation = context.evaluation();

    LocalDate periodStart = null;
    LocalDate periodEnd = null;
    for (MetricReading reading : context.readings().values()) {
      if (reading.value() == null) {
        continue;
      }
      LocalDate start = parseDateOrNull(reading.periodStart());
      LocalDate end = parseDateOrNull(reading.periodEnd());
      if (start != null && (periodStart == null || start.isBefore(periodStart))) {
        periodStart = start;
      }
      if (end != null && (periodEnd == null || end.isAfter(periodEnd))) {
        periodEnd = end;
      }
    }
    if (periodStart == null) {
      periodStart = LocalDate.of(1970, 1, 1);
    }
    if (periodEnd == null) {
      periodEnd = LocalDate.of(1970, 1, 1);
    }

    OffsetDateTime now = dbNow();
    Map<String, Object> thresholdSnapshot = new LinkedHashMap<>();
    for (String code : METRIC_CODES) {
      Threshold threshold = thresholds.get(code);
      if (threshold == null) {
        continue;
      }
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("warnValue", threshold.warnValue());
      item.put("criticalValue", threshold.criticalValue());
      item.put("direction", threshold.direction());
      item.put("ruleSource", threshold.ruleSource());
      item.put("ruleVersion", threshold.ruleVersion());
      thresholdSnapshot.put(code, item);
    }

    List<Map<String, Object>> reasonPayload = new ArrayList<>();
    for (AccountHealthReason reason : evaluation.reasons()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("metricCode", reason.metricCode());
      item.put("metricLabel", reason.metricLabel());
      item.put("level", reason.level());
      item.put("metricValue", reason.metricValue());
      item.put("thresholdValue", reason.thresholdValue());
      item.put("thresholdKind", reason.thresholdKind());
      item.put("direction", reason.direction());
      item.put("message", reason.message());
      reasonPayload.add(item);
    }

    String summary = summaryOf(evaluation);
    UUID assessmentId = UUID.randomUUID();
    sql.execute(
        """
        insert into account_health_assessment
          (id, tenant_id, shop_id, assessed_at, period_start, period_end, risk_level,
           known_metric_count, breach_count, rule_version, rule_source, thresholds, reasons,
           summary, created_at)
        values (?, ?, ?, ?::timestamptz, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, now())
        """,
        assessmentId,
        tenant,
        id,
        now,
        periodStart,
        periodEnd,
        evaluation.riskLevel(),
        evaluation.knownMetricCount(),
        evaluation.breachCount(),
        ruleVersionOf(thresholds),
        ruleSourceOf(thresholds),
        writeJson(thresholdSnapshot),
        writeJson(reasonPayload),
        summary);

    return new AccountHealthAssessmentResult(
        assessmentId.toString(),
        id.toString(),
        now.toString(),
        periodStart.toString(),
        periodEnd.toString(),
        evaluation.riskLevel(),
        levelLabel(evaluation.riskLevel()),
        evaluation.knownMetricCount(),
        evaluation.breachCount(),
        ruleVersionOf(thresholds),
        ruleSourceOf(thresholds),
        thresholdSnapshot,
        evaluation.reasons(),
        summary);
  }

  private static String summaryOf(Evaluation evaluation) {
    switch (evaluation.riskLevel()) {
      case LEVEL_CRITICAL:
        return "命中 "
            + evaluation.breachCount()
            + " 项阈值（严重档），先处理这家店铺的绩效问题。";
      case LEVEL_WARNING:
        return "命中 " + evaluation.breachCount() + " 项警告阈值，需要尽快跟进。";
      case LEVEL_HEALTHY:
        return "四项绩效指标齐全且都未命中阈值，账号健康。";
      default:
        return "只量到 "
            + evaluation.knownMetricCount()
            + "/"
            + METRIC_CODES.size()
            + " 项指标，判不了（量不到不等于健康），先补齐指标。";
    }
  }

  /** 判定历史（**真分页**）。 */
  public AccountHealthAssessmentPage assessments(String shopId, int page, int size) {
    UUID tenant = currentTenant();
    int limit = Math.max(1, Math.min(size, 200));
    int offset = Math.max(0, page) * limit;

    List<Object> where = new ArrayList<>();
    where.add(tenant);
    String clause = " where tenant_id = ?";
    UUID id = parseUuid(shopId);
    if (!text(shopId).isEmpty()) {
      if (id == null) {
        return new AccountHealthAssessmentPage(List.of(), 0, Math.max(0, page), limit);
      }
      clause += " and shop_id = ?";
      where.add(id);
    }

    long total =
        count(sql.fetchValue("select count(*) from account_health_assessment" + clause, where.toArray()));

    List<Object> params = new ArrayList<>(where);
    params.add(limit);
    params.add(offset);
    List<AccountHealthAssessmentSummary> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            """
            select id, shop_id, assessed_at, period_start, period_end, risk_level,
                   known_metric_count, breach_count, rule_version, rule_source, summary,
                   jsonb_array_length(reasons) as reason_count
            from account_health_assessment
            """
                + clause
                + " order by assessed_at desc, id desc limit ? offset ?",
            params.toArray())) {
      String riskLevel = row.get("risk_level", String.class);
      items.add(
          new AccountHealthAssessmentSummary(
              row.get("id", UUID.class).toString(),
              row.get("shop_id", UUID.class).toString(),
              offsetText(row.get("assessed_at", OffsetDateTime.class)),
              localDateText(row.get("period_start", LocalDate.class)),
              localDateText(row.get("period_end", LocalDate.class)),
              riskLevel,
              levelLabel(riskLevel),
              intOf(row, "known_metric_count"),
              intOf(row, "breach_count"),
              longOf(row, "rule_version"),
              row.get("rule_source", String.class),
              row.get("summary", String.class),
              intOf(row, "reason_count")));
    }
    return new AccountHealthAssessmentPage(items, total, Math.max(0, page), limit);
  }

  // ==================================================================
  // 阈值
  // ==================================================================

  /** 生效阈值清单（内置默认 + 租户覆盖，固定四条）。 */
  public AccountHealthThresholdList thresholds() {
    UUID tenant = currentTenant();
    Map<String, Threshold> thresholds = loadThresholds(tenant);
    Map<String, OffsetDateTime> effectiveFrom = loadThresholdEffectiveFrom(tenant);
    List<AccountHealthThresholdView> items = new ArrayList<>();
    for (String code : METRIC_CODES) {
      Threshold threshold = thresholds.get(code);
      if (threshold == null) {
        continue;
      }
      boolean builtin = RULE_SOURCE_BUILTIN.equals(threshold.ruleSource());
      OffsetDateTime since = effectiveFrom.get(code);
      items.add(
          new AccountHealthThresholdView(
              code,
              METRIC_LABELS.getOrDefault(code, code),
              threshold.direction(),
              threshold.warnValue(),
              threshold.criticalValue(),
              threshold.ruleVersion(),
              threshold.ruleSource(),
              builtin ? "模块内置默认（可覆盖）" : "租户自定义",
              builtin,
              true,
              since == null ? null : since.toString(),
              builtin
                  ? "内置默认阈值是本模块的默认政策，不是平台下发的口径；"
                      + "保存后即成为租户自定义并推进规则版本。"
                  : "租户自定义阈值。"));
    }
    return new AccountHealthThresholdList(items, THRESHOLD_SEMANTICS);
  }

  private Map<String, OffsetDateTime> loadThresholdEffectiveFrom(UUID tenant) {
    Map<String, OffsetDateTime> result = new LinkedHashMap<>();
    for (Record row :
        sql.fetch(
            """
            select metric_code, effective_from
            from account_health_threshold
            where tenant_id = ? and enabled
            """,
            tenant)) {
      result.put(
          row.get("metric_code", String.class), row.get("effective_from", OffsetDateTime.class));
    }
    return result;
  }

  /**
   * 修改某项指标的阈值（**服务端行为**，前端不重算）。
   *
   * <p>🔴 改阈值**必然推进 rule_version**（DB 触发器钉住）⇒ 历史判定仍然是旧判定。
   */
  public AccountHealthThresholdView saveThreshold(
      String metricCode, AccountHealthThresholdInput input) {
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
    if (warn.compareTo(BigDecimal.ZERO) < 0
        || warn.compareTo(BigDecimal.ONE) > 0
        || critical.compareTo(BigDecimal.ZERO) < 0
        || critical.compareTo(BigDecimal.ONE) > 0) {
      throw unprocessable("阈值必须是 0..1 的比值（例如 2.8% 写 0.0280）");
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
        insert into account_health_threshold
          (id, tenant_id, metric_code, direction, warn_value, critical_value, rule_version,
           effective_from, enabled, note, created_at, updated_at)
        values (?, ?, ?, ?, ?, ?, 1, ?::timestamptz, true, ?, now(), now())
        on conflict (tenant_id, metric_code)
        do update set direction = excluded.direction,
                      warn_value = excluded.warn_value,
                      critical_value = excluded.critical_value,
                      rule_version = account_health_threshold.rule_version + 1,
                      effective_from = excluded.effective_from,
                      enabled = true,
                      note = excluded.note,
                      updated_at = now()
        """,
        UUID.randomUUID(),
        tenant,
        code,
        direction,
        warn,
        critical,
        now,
        text(input.note()));

    for (AccountHealthThresholdView view : thresholds().items()) {
      if (view.metricCode().equals(code)) {
        return view;
      }
    }
    throw notFound();
  }

  // ==================================================================
  // 平台绩效同步（未接入 ⇒ 恒 409，一条数据都不写）
  // ==================================================================

  /**
   * 🔴 触发一次平台账号绩效同步。
   *
   * <p><b>当前恒 409 且一条数据都不写</b>：平台账号绩效适配器未交付（归 2.4）。
   * 适配器接上之后，这条路径会真的把平台侧的指标以 {@code source=PLATFORM_SYNC} 落库，
   * 服务层与前端一行都不用改。
   */
  public AccountHealthShopCard sync(String shopId, String bearer) {
    UUID tenant = currentTenant();
    UUID id = parseUuid(shopId);
    if (id == null) {
      throw notFound();
    }
    ShopRow shop = requireShop(tenant, id);

    PlatformPerformancePort.Availability availability =
        platformPerformance.availability(shop.platformCode());
    if (!availability.available()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "平台账号绩效同步未接入（归属模块 "
              + availability.ownerModule()
              + "）："
              + availability.reason());
    }
    PlatformPerformancePort.FetchResult result =
        platformPerformance.fetch(shop.platformCode(), shop.code(), bearer);
    if (!result.available()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "平台账号绩效拉取失败：" + result.reason());
    }

    OffsetDateTime now = dbNow();
    for (PlatformPerformancePort.RawMetric metric : result.metrics()) {
      String metricCode = text(metric.metricCode()).toUpperCase();
      if (!METRIC_CODES.contains(metric.metricCode())) {
        continue;
      }
      LocalDate periodStart = parseDate(metric.periodStart(), "周期起点");
      LocalDate periodEnd = parseDate(metric.periodEnd(), "周期终点");
      sql.execute(
          """
          insert into account_health_metric
            (id, tenant_id, shop_id, period_start, period_end, metric_code, metric_value,
             source, captured_at, recorded_by, note, created_at, updated_at)
          values (?, ?, ?, ?, ?, ?, ?, 'PLATFORM_SYNC', ?::timestamptz, null, '', now(), now())
          on conflict (tenant_id, shop_id, period_start, period_end, metric_code)
          do update set metric_value = excluded.metric_value,
                        source = excluded.source,
                        captured_at = excluded.captured_at,
                        note = excluded.note,
                        updated_at = now()
          """,
          UUID.randomUUID(),
          tenant,
          id,
          periodStart,
          periodEnd,
          metricCode,
          metric.value(),
          now);
    }
    return shop(shopId);
  }

  // ==================================================================
  // 需处理项
  // ==================================================================

  /**
   * 需处理项清单。
   *
   * <p>🔴 <b>只给本模块自己真实存在的待处理项</b>：命中阈值的店铺（先处理）、
   * 指标不全的店铺（先补数据）、平台绩效同步未接入（归 2.4）。
   * 跳转目标**只能是本模块自己真实存在的页面** —— 蓝图输出里的「政策违规计数」「申诉状态」
   * 归 16.1.2.1 / 16.1.2.2（两者<b>已交付</b>，但<b>本模块未接入</b>）⇒
   * 这里**不编造**这两维，也**不跳**到别的模块的页面。
   */
  public AccountHealthPendingList pendingItems(String platformCode, String keyword) {
    UUID tenant = currentTenant();
    List<ShopContext> contexts = loadRanked(tenant, platformCode, keyword, null);
    List<AccountHealthPendingItem> items = new ArrayList<>();
    for (ShopContext context : contexts) {
      Evaluation evaluation = context.evaluation();
      String kind;
      String label;
      String description;
      if (LEVEL_CRITICAL.equals(evaluation.riskLevel())
          || LEVEL_WARNING.equals(evaluation.riskLevel())) {
        kind = "RISK_BREACH";
        label = context.shop().name() + " 命中 " + evaluation.breachCount() + " 项阈值";
        description =
            "风险等级 "
                + levelLabel(evaluation.riskLevel())
                + "："
                + context.rankReason();
      } else if (evaluation.knownMetricCount() < METRIC_CODES.size()) {
        kind = "DATA_MISSING";
        label = context.shop().name() + " 指标不全（" + evaluation.knownMetricCount() + "/4）";
        description =
            "量到的指标不足以断言账号健康（量不到不是低风险），先补齐绩效指标再判。";
      } else {
        continue;
      }
      items.add(
          new AccountHealthPendingItem(
              kind,
              label,
              description,
              context.shop().id().toString(),
              context.shop().code(),
              context.shop().name(),
              "16.1.1.1",
              "/compliance/account?shopId=" + context.shop().id(),
              "打开这家店的健康卡片"));
    }

    PlatformPerformancePort.Availability availability =
        platformPerformance.availability(platformCode);
    if (!availability.available()) {
      items.add(
          new AccountHealthPendingItem(
              "PLATFORM_SYNC_UNAVAILABLE",
              "平台账号绩效同步未接入",
              availability.reason(),
              null,
              null,
              null,
              availability.ownerModule(),
              "/compliance/account",
              "查看账号健康总览"));
    }
    return new AccountHealthPendingList(items, items.size(), DOWNSTREAM_NOTE);
  }

  // ==================================================================
  // 辅助
  // ==================================================================

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
}
