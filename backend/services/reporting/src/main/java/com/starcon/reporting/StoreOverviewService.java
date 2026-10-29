package com.starcon.reporting;

import com.starcon.tenancy.TenantTransactions;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 1.1.2.2 店铺概览的**读模型**（蓝图 01 卷「输入」句：店铺维度聚合投影；店铺列表（02）；
 * 权限过滤后的可见店铺集合）。
 *
 * <p>🔴 <b>本模块是 13.1.1.2 多店对比读模型之上的「工作台视图」，不重算任何口径</b>：
 *
 * <ul>
 *   <li><b>数值</b>来自 {@link ReportingStoreComparisonService#comparison} ——
 *       那是 13.1.1.2 已交付的店铺维度读模型，口径（单位 / 聚合方式 / 分子分母）全部来自
 *       {@code reporting_store_metric_definition}，派生型指标（口径差异额）由它**递归求值**。
 *       本模块**不复述**这套求值（复述一遍 = 第二个真相，租户改了口径两边就会打架）。
 *   <li><b>掉队判定</b>同样来自那份读模型（环比增长率 ≤ −20%）——
 *       「掉队」是一条**口径**，必须与 13 一致，不能各判各的。
 *   <li><b>本模块自己做的</b>是：① 把五个蓝图维度映射到店铺级口径码
 *       （{@link StoreOverviewCatalog}）；② 按**运行时口径表**判定每个维度接不接得通
 *       （{@code reporting_store_metric_definition}）；③ 读**权限过滤后的可见店铺集合**
 *       （{@code reporting_store_profile}，RLS 已按会话租户隔离）。
 * </ul>
 *
 * <p>🔴 <b>「销售额」不是一个数，是三个口径的并列</b>：平台侧成交金额 / 还原口径成交金额 /
 * 口径差异额。详见 {@link StoreOverviewCatalog} 的类注释。
 *
 * <p>🔴 <b>接不通的维度按「未接通」显示</b>：{@code available=false} + 归属模块，
 * **不给公式也不给分母**。
 *
 * <p>🔴 <b>「没有事实」与「事实是 0」分开</b>：事实不存在一律 {@code NO_DATA} + null，
 * **不用 0 冒充**。销售额 0 与「这一格没算出来」在页面上长得一样，含义却相反。
 *
 * <p>🔴 <b>不跨币种相加</b>：涉币维度按币种分别报。
 */
@Service
public class StoreOverviewService {

  /** 时间口径选择（与 1.1.2.1 核心指标卡同一组，工作台的口径选择保持一致）。 */
  public static final String WINDOW_TODAY = "TODAY";

  public static final String WINDOW_YESTERDAY = "YESTERDAY";
  public static final String WINDOW_THIS_MONTH = "THIS_MONTH";
  public static final String WINDOW_CUSTOM = "CUSTOM";

  /** 维度状态。 */
  public static final String STATUS_AVAILABLE = "AVAILABLE";

  public static final String STATUS_NO_DATA = "NO_DATA";
  public static final String STATUS_NOT_CONNECTED = "NOT_CONNECTED";

  /** 取值可用性。 */
  public static final String VALUE_AVAILABLE = "AVAILABLE";

  public static final String VALUE_NO_DATA = "NO_DATA";

  /** 该店铺在 13 的分析页（**已交付的路由** —— 指向未交付的页面是假承诺）。 */
  private static final String ANALYSIS_PATH = "/analytics/dashboard/store-comparison";

  private static final String ANALYSIS_NAME = "13.1.1.2 多店对比（13 店铺分析）";

  /** 该店铺订单页（**已交付的路由**：09 的平台单列表）。 */
  private static final String ORDERS_PATH = "/orders/platform-sync";

  private final ReportingStoreComparisonService comparison;
  private final DSLContext sql;
  private final TenantTransactions tenants;
  private final Clock clock;

  public StoreOverviewService(
      ReportingStoreComparisonService comparison,
      DSLContext sql,
      TenantTransactions tenants,
      Clock clock) {
    this.comparison = comparison;
    this.sql = sql;
    this.tenants = tenants;
    this.clock = clock;
  }

  // ==========================================================================
  // 能力声明
  // ==========================================================================

  /**
   * 逐条报出五个维度接不接得通、口径归谁、背后是哪些口径码。
   *
   * <p>🔴 **可用性是运行时判定的**：口径码有没有在本租户的口径表里登记，
   * 查的是 {@code reporting_store_metric_definition} —— 租户改了口径，能力声明跟着改。
   */
  public StoreOverviewModels.StoreOverviewCapabilities capabilities(UUID tenantId) {
    return tenants.run(
        tenantId,
        () -> {
          var registered = registeredCalibers(tenantId);
          var dimensions = new ArrayList<StoreOverviewModels.StoreOverviewDimensionCapability>();
          for (var spec : StoreOverviewCatalog.DIMENSIONS) {
            dimensions.add(capability(spec, registered));
          }
          return new StoreOverviewModels.StoreOverviewCapabilities(
              true, dimensions, UPSTREAM_MODULES, NOT_DELIVERED, RULES, NOTES);
        });
  }

  /** 一个维度的能力声明。 */
  private StoreOverviewModels.StoreOverviewDimensionCapability capability(
      StoreOverviewCatalog.DimensionSpec spec,
      LinkedHashMap<String, Caliber> registered) {
    if (spec.source() == StoreOverviewCatalog.Source.NOT_CONNECTED) {
      return new StoreOverviewModels.StoreOverviewDimensionCapability(
          spec.key(),
          spec.displayName(),
          false,
          spec.ownerModule(),
          spec.caliber(),
          spec.unit(),
          spec.currencyAware(),
          spec.source().name(),
          List.of(),
          spec.unavailableReason(),
          "未接通的维度**不给公式也不给分母** —— 给了就是编一个出来。");
    }
    var calibers = new ArrayList<StoreOverviewModels.StoreOverviewCaliber>();
    var missing = new ArrayList<String>();
    for (var code : spec.metricCodes()) {
      var caliber = registered.get(code);
      if (caliber == null) {
        missing.add(code);
        calibers.add(
            new StoreOverviewModels.StoreOverviewCaliber(
                code, "", spec.unit(), "", null, null, spec.ownerModule(), "", false));
        continue;
      }
      calibers.add(
          new StoreOverviewModels.StoreOverviewCaliber(
              code,
              caliber.displayName(),
              caliber.unit(),
              caliber.aggregation(),
              caliber.numeratorMetricCode(),
              caliber.denominatorMetricCode(),
              caliber.ownerModule(),
              caliber.description(),
              true));
    }
    if (!missing.isEmpty()) {
      return new StoreOverviewModels.StoreOverviewDimensionCapability(
          spec.key(),
          spec.displayName(),
          false,
          spec.ownerModule(),
          spec.caliber(),
          spec.unit(),
          spec.currencyAware(),
          spec.source().name(),
          calibers,
          "该维度引用的口径码 " + String.join("、", missing)
              + " **未在本租户的店铺口径表里登记** —— 口径唯一，没有登记就没有这个指标。",
          "口径表的登记在 13.1.1.2 多店对比里维护。");
    }
    var owner =
        calibers.stream()
            .map(StoreOverviewModels.StoreOverviewCaliber::ownerModule)
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .reduce((a, b) -> a + " / " + b)
            .orElse(spec.ownerModule());
    return new StoreOverviewModels.StoreOverviewDimensionCapability(
        spec.key(),
        spec.displayName(),
        true,
        owner,
        caliberText(spec, registered),
        spec.unit(),
        spec.currencyAware(),
        spec.source().name(),
        calibers,
        "",
        "口径取自 reporting_store_metric_definition（运行时唯一真相），本模块不复制一份。");
  }

  /** 维度的口径说明：清单里写死的纪律 + 每个口径码自己的说明。 */
  private String caliberText(
      StoreOverviewCatalog.DimensionSpec spec, LinkedHashMap<String, Caliber> registered) {
    var lines = new ArrayList<String>();
    if (spec.caliber() != null && !spec.caliber().isBlank()) lines.add(spec.caliber());
    for (var code : spec.metricCodes()) {
      var caliber = registered.get(code);
      if (caliber == null) continue;
      lines.add(
          "「"
              + caliber.displayName()
              + "」（"
              + code
              + "，归属 "
              + caliber.ownerModule()
              + "）："
              + caliber.description());
    }
    return String.join("\n", lines);
  }

  // ==========================================================================
  // 店铺概览
  // ==========================================================================

  /**
   * 一次取数：店铺对比行 + 掉队店铺 + 口径差异说明 + 数据截至时刻。
   *
   * @param preset 时间口径：TODAY / YESTERDAY / THIS_MONTH / CUSTOM
   * @param from 自定义窗口的起（CUSTOM 时必填）
   * @param to 自定义窗口的止（CUSTOM 时必填）
   * @param platformCode 只看某个平台（可空 ⇒ 全部平台）
   */
  public StoreOverviewModels.StoreOverviewResponse overview(
      UUID tenantId, String preset, LocalDate from, LocalDate to, String platformCode) {
    return tenants.run(
        tenantId,
        () -> {
          var window = resolveWindow(tenantId, preset, from, to);
          var registered = registeredCalibers(tenantId);

          // 🔴 数值与掉队判定都来自 13.1.1.2 的读模型（口径唯一，不重算）。
          var source =
              comparison.comparison(tenantId, window.from(), window.to(), platformCode, null);

          var shops = visibleStores(tenantId, window.from(), window.to(), platformCode);
          var byShop = new LinkedHashMap<String, ReportingStoreComparisonModels.StoreComparisonRow>();
          for (var row : source.rows()) {
            byShop.put(row.shopCode(), row);
          }

          var rows = new ArrayList<StoreOverviewModels.StoreOverviewRow>();
          for (var shop : shops) {
            var comparisonRow = byShop.get(shop.shopCode());
            rows.add(
                row(
                    shop,
                    comparisonRow,
                    registered,
                    shop.hasFactsInWindow()
                        ? "该店铺在这个窗口里有店铺维度事实。"
                        : "该店铺在这个窗口里**没有店铺维度事实**（不是 0）—— "
                            + "上游还没有投影这个窗口的数据。"));
          }
          // 排序：沿用 13.1.1.2 的规模排序（平台侧成交金额倒序），没有可比数据时按店铺编码排。
          rows.sort(
              java.util.Comparator.comparing(
                      (StoreOverviewModels.StoreOverviewRow row) -> rankOf(byShop.get(row.shopCode())))
                  .thenComparing(StoreOverviewModels.StoreOverviewRow::shopCode));

          var laggards = new ArrayList<StoreOverviewModels.StoreOverviewLaggard>();
          for (var flag : source.laggards()) {
            laggards.add(
                new StoreOverviewModels.StoreOverviewLaggard(
                    flag.shopCode(), flag.shopName(), flag.platformCode(), flag.reason()));
          }

          var warnings = new ArrayList<String>();
          if (!"UTC".equals(window.timezone())) {
            warnings.add(
                "「今日 / 昨日 / 本月」的边界按本租户时区 " + window.timezone() + " 计算（不是写死 UTC）。");
          } else if ("FALLBACK_UTC".equals(window.timezoneSource())) {
            warnings.add(
                "本租户还没有任何店铺投影事实，取不到自己的分桶时区，窗口边界暂按 UTC 计算 —— "
                    + "一旦投影进来就会改用该租户自己的时区。");
          }
          warnings.add(
              "「销售额」是**三个口径的并列**（平台侧成交金额 / 还原口径成交金额 / 口径差异额），"
                  + "不是单一口径 —— 只看其中一个等于把口径差读成经营差。");
          warnings.add(
              "「利润率」与「库存天数」两个维度**尚未接通**：店铺口径表里没有这两个口径码，"
                  + "上游的成本与库存事实也没有店铺维度 —— 本模块不编公式、不给分母。");

          var status = source.status();
          var statusNote = source.statusNote();
          var emptyReason = source.emptyReason();
          var emptyNote = source.emptyNote();
          // 🔴 三种空态必须分开说：「没有店铺档案」/「有店铺但这个窗口没投影」/「有数据」。
          var anyFacts =
              shops.stream().anyMatch(StoreOverviewModels.StoreOverviewShop::hasFactsInWindow);
          if (shops.isEmpty()) {
            status = "NOT_PROJECTED";
            emptyReason = "NO_STORES";
            emptyNote =
                "这个租户还没有任何店铺档案 —— 店铺归属来自 channel 的店铺清单，还没有投影过就无从对比。";
            statusNote = emptyNote;
          } else if (!anyFacts) {
            status = "NOT_PROJECTED";
            emptyReason = "NOT_PROJECTED";
            emptyNote =
                "这个租户已有店铺档案，但这个窗口还没有投影任何店铺维度事实 —— "
                    + "请先刷新该窗口的店铺维度投影。";
            statusNote = emptyNote;
          } else {
            status = "PROJECTED";
            emptyReason = null;
            emptyNote = "";
            statusNote = "已按店铺维度投影给出当期表现。";
          }

          return new StoreOverviewModels.StoreOverviewResponse(
              window.preset(),
              window.from(),
              window.to(),
              window.asOfDate(),
              window.timezone(),
              window.timezoneSource(),
              rows,
              laggards,
              source.caliberNotes(),
              source.dataUpdatedAt(),
              source.dataUpdatedNote(),
              status,
              statusNote,
              emptyReason,
              emptyNote,
              warnings);
        });
  }

  /** 一个店铺的五个维度。 */
  private StoreOverviewModels.StoreOverviewRow row(
      StoreOverviewModels.StoreOverviewShop shop,
      ReportingStoreComparisonModels.StoreComparisonRow comparisonRow,
      LinkedHashMap<String, Caliber> registered,
      String note) {
    var currencies =
        comparisonRow == null ? List.<String>of() : comparisonRow.currencies();
    var metrics = new LinkedHashMap<String, ReportingStoreComparisonModels.StoreMetricValue>();
    if (comparisonRow != null) {
      for (var metric : comparisonRow.metrics()) {
        metrics.put(metric.metricCode() + "|" + metric.currency(), metric);
      }
    }
    var dimensions = new ArrayList<StoreOverviewModels.StoreOverviewDimension>();
    for (var spec : StoreOverviewCatalog.DIMENSIONS) {
      dimensions.add(dimension(spec, shop, currencies, metrics, registered));
    }
    var classification = comparisonRow == null ? "UNCLASSIFIED" : comparisonRow.classification();
    var classificationNote =
        comparisonRow == null
            ? "该店铺在这个窗口里没有可比的店铺维度事实 —— 不可比（不是 0%）。"
            : comparisonRow.classificationNote();
    return new StoreOverviewModels.StoreOverviewRow(
        shop.shopCode(),
        shop.shopName(),
        shop.platformCode(),
        shop.platformName(),
        currencies,
        shop.hasFactsInWindow(),
        dimensions,
        classification,
        classificationNote,
        ANALYSIS_PATH,
        ANALYSIS_NAME,
        ORDERS_PATH,
        note);
  }

  /** 一个维度在某个店铺上的取值。 */
  private StoreOverviewModels.StoreOverviewDimension dimension(
      StoreOverviewCatalog.DimensionSpec spec,
      StoreOverviewModels.StoreOverviewShop shop,
      List<String> currencies,
      LinkedHashMap<String, ReportingStoreComparisonModels.StoreMetricValue> metrics,
      LinkedHashMap<String, Caliber> registered) {
    if (spec.source() == StoreOverviewCatalog.Source.NOT_CONNECTED) {
      return new StoreOverviewModels.StoreOverviewDimension(
          spec.key(),
          spec.displayName(),
          spec.unit(),
          spec.currencyAware(),
          false,
          spec.ownerModule(),
          spec.caliber(),
          STATUS_NOT_CONNECTED,
          List.of(),
          spec.unavailableReason());
    }
    var missing = new ArrayList<String>();
    for (var code : spec.metricCodes()) {
      if (!registered.containsKey(code)) missing.add(code);
    }
    if (!missing.isEmpty()) {
      return new StoreOverviewModels.StoreOverviewDimension(
          spec.key(),
          spec.displayName(),
          spec.unit(),
          spec.currencyAware(),
          false,
          spec.ownerModule(),
          spec.caliber(),
          STATUS_NOT_CONNECTED,
          List.of(),
          "该维度引用的口径码 "
              + String.join("、", missing)
              + " **未在本租户的店铺口径表里登记** —— 口径唯一，没有登记就没有这个指标。");
    }
    var values = new ArrayList<StoreOverviewModels.StoreOverviewMetricValue>();
    var anyValue = false;
    for (var code : spec.metricCodes()) {
      var caliber = registered.get(code);
      for (var currency : currencies) {
        var metric = metrics.get(code + "|" + currency);
        var value = metric == null ? null : metric.value();
        var availability = VALUE_AVAILABLE;
        var valueNote = "";
        if (metric == null) {
          availability = VALUE_NO_DATA;
          valueNote = "该店铺在这个窗口、这个币种下没有这个口径的事实数据（不是 0）。";
        } else if (value == null) {
          availability = VALUE_NO_DATA;
          valueNote =
              metric.availabilityNote() == null || metric.availabilityNote().isBlank()
                  ? "该店铺在这个窗口、这个币种下没有这个口径的事实数据（不是 0）。"
                  : metric.availabilityNote();
        } else {
          anyValue = true;
        }
        values.add(
            new StoreOverviewModels.StoreOverviewMetricValue(
                code,
                caliber == null ? code : caliber.displayName(),
                caliber == null ? spec.unit() : caliber.unit(),
                caliber == null ? spec.ownerModule() : caliber.ownerModule(),
                currency,
                value,
                availability,
                valueNote));
      }
    }
    var status = anyValue ? STATUS_AVAILABLE : STATUS_NO_DATA;
    var statusNote =
        anyValue
            ? ""
            : "该维度口径已接通，但这个店铺在这个窗口里**没有事实数据**（不是 0）。";
    return new StoreOverviewModels.StoreOverviewDimension(
        spec.key(),
        spec.displayName(),
        spec.unit(),
        spec.currencyAware(),
        true,
        spec.ownerModule(),
        caliberText(spec, registered),
        status,
        values,
        statusNote);
  }

  // ==========================================================================
  // 可见店铺集合
  // ==========================================================================

  /**
   * 权限过滤后的可见店铺集合（蓝图 1.1.2.2「输入」句）。
   *
   * <p>🔴 **RLS 已经按会话租户过滤**：这里只查 {@code reporting_store_profile}
   * （本租户的店铺档案），租户不来自任何请求参数。
   *
   * <p>⚠️ 「有没有事实」按**窗口**判定（13.1.1.2 的 {@code /shops} 端点给的是全时段计数）——
   * 本模块要的是「当期有没有数据」，两者含义不同。
   */
  public List<StoreOverviewModels.StoreOverviewShop> visibleStores(
      UUID tenantId, LocalDate from, LocalDate to, String platformCode) {
    return tenants.run(
        tenantId,
        () -> {
          // ⚠️ 平台过滤用**两段 SQL**而不是 `? is null or …`：
          //   jOOQ 从绑定值推断参数类型，null 绑定推不出类型 ⇒ 会直接抛类型错误。
          var filtered = platformCode != null && !platformCode.isBlank();
          var sqlText =
              filtered
                  ? """
                    select p.shop_code, p.shop_name, p.platform_code, p.platform_name,
                           (select count(*) from reporting_store_metric_fact f
                             where f.tenant_id = p.tenant_id and f.shop_code = p.shop_code
                               and f.bucket_date >= ? and f.bucket_date <= ?) fact_rows,
                           (select max(f.updated_at) from reporting_store_metric_fact f
                             where f.tenant_id = p.tenant_id and f.shop_code = p.shop_code
                               and f.bucket_date >= ? and f.bucket_date <= ?) last_fact_at
                      from reporting_store_profile p
                     where p.tenant_id = ? and p.platform_code = ?
                     order by p.platform_code, p.shop_code
                    """
                  : """
                    select p.shop_code, p.shop_name, p.platform_code, p.platform_name,
                           (select count(*) from reporting_store_metric_fact f
                             where f.tenant_id = p.tenant_id and f.shop_code = p.shop_code
                               and f.bucket_date >= ? and f.bucket_date <= ?) fact_rows,
                           (select max(f.updated_at) from reporting_store_metric_fact f
                             where f.tenant_id = p.tenant_id and f.shop_code = p.shop_code
                               and f.bucket_date >= ? and f.bucket_date <= ?) last_fact_at
                      from reporting_store_profile p
                     where p.tenant_id = ?
                     order by p.platform_code, p.shop_code
                    """;
          var rows =
              filtered
                  ? sql.fetch(sqlText, from, to, from, to, tenantId, platformCode)
                  : sql.fetch(sqlText, from, to, from, to, tenantId);
          var out = new ArrayList<StoreOverviewModels.StoreOverviewShop>();
          for (var row : rows) {
            out.add(
                new StoreOverviewModels.StoreOverviewShop(
                    row.get(0, String.class),
                    row.get(1, String.class) == null ? "" : row.get(1, String.class),
                    row.get(2, String.class) == null ? "" : row.get(2, String.class),
                    row.get(3, String.class) == null ? "" : row.get(3, String.class),
                    row.get(4, Long.class) != null && row.get(4, Long.class) > 0,
                    row.get(5, java.time.OffsetDateTime.class)));
          }
          return out;
        });
  }

  /** 店铺规模排名（13.1.1.2 给的：按平台侧成交金额，1 = 最大）。 */
  private int rankOf(ReportingStoreComparisonModels.StoreComparisonRow row) {
    if (row == null || row.scaleRank() == null) return Integer.MAX_VALUE;
    return row.scaleRank();
  }

  // ==========================================================================
  // 口径表
  // ==========================================================================

  /** 一条口径登记。 */
  private record Caliber(
      String metricCode,
      String displayName,
      String unit,
      String aggregation,
      String numeratorMetricCode,
      String denominatorMetricCode,
      String ownerModule,
      String description) {}

  /**
   * 本租户**已登记**的店铺级口径（运行时唯一真相）。
   *
   * <p>⚠️ 取全表再在 Java 侧过滤：这张表每租户十几行（13.1.1.2 登记的 13 个口径码），
   * 而 {@code = any (?)} 的数组绑定在不同驱动上写法不一致 —— 为了几行数据去赌驱动行为不划算。
   */
  private LinkedHashMap<String, Caliber> registeredCalibers(UUID tenantId) {
    var out = new LinkedHashMap<String, Caliber>();
    var rows =
        sql.fetch(
            """
            select metric_code, display_name, unit, aggregation,
                   numerator_metric_code, denominator_metric_code, owner_module, description
              from reporting_store_metric_definition
             where tenant_id = ? and enabled
             order by metric_code
            """,
            tenantId);
    for (var row : rows) {
      out.put(
          row.get(0, String.class),
          new Caliber(
              row.get(0, String.class),
              row.get(1, String.class) == null ? "" : row.get(1, String.class),
              row.get(2, String.class) == null ? "" : row.get(2, String.class),
              row.get(3, String.class) == null ? "" : row.get(3, String.class),
              row.get(4, String.class),
              row.get(5, String.class),
              row.get(6, String.class) == null ? "" : row.get(6, String.class),
              row.get(7, String.class) == null ? "" : row.get(7, String.class)));
    }
    return out;
  }

  // ==========================================================================
  // 时间窗口
  // ==========================================================================

  /** 窗口（带时区与口径说明）。 */
  public record Window(
      String preset,
      LocalDate from,
      LocalDate to,
      LocalDate asOfDate,
      String timezone,
      String timezoneSource,
      String note) {}

  /**
   * 解析时间窗口（**对外可见**，供「可见店铺集合」端点用同一个窗口口径）。
   *
   * <p>⚠️ 「可见店铺集合」与「店铺概览」**必须用同一个窗口** ——
   * 否则筛选器里「这个窗口有事实的店铺」和表格里的店铺对不上。
   */
  public Window resolveWindowFor(UUID tenantId, String preset, LocalDate from, LocalDate to) {
    return resolveWindow(tenantId, preset, from, to);
  }

  /**
   * 解析时间窗口。
   *
   * <p>🔴 **「今日 / 昨日 / 本月」的边界按本租户时区算**：时区取自本租户**店铺维度事实**上的
   * 分桶时区（{@code reporting_store_metric_fact.timezone}），**不写死 UTC**
   * （13.1.1.2 栽过一次：行里声称自己按 UTC 分桶，实际按的是别的东西）。
   */
  private Window resolveWindow(UUID tenantId, String preset, LocalDate from, LocalDate to) {
    var zone = tenantZone(tenantId);
    var asOf = LocalDate.now(clock.withZone(ZoneId.of(zone.zone())));
    var effective =
        preset == null || preset.isBlank()
            ? WINDOW_TODAY
            : preset.trim().toUpperCase(java.util.Locale.ROOT);
    switch (effective) {
      case WINDOW_TODAY -> {
        return new Window(WINDOW_TODAY, asOf, asOf, asOf, zone.zone(), zone.source(), "");
      }
      case WINDOW_YESTERDAY -> {
        var day = asOf.minusDays(1);
        return new Window(WINDOW_YESTERDAY, day, day, asOf, zone.zone(), zone.source(), "");
      }
      case WINDOW_THIS_MONTH -> {
        var start = asOf.withDayOfMonth(1);
        var end = asOf.withDayOfMonth(asOf.lengthOfMonth());
        var note =
            asOf.getDayOfMonth() == asOf.lengthOfMonth()
                ? ""
                : "本月尚未结束（今天到 "
                    + asOf
                    + "）：本月窗口按自然月整月计算，环比对比的是上一等长窗口，"
                    + "本月后续日期尚无事实。";
        return new Window(WINDOW_THIS_MONTH, start, end, asOf, zone.zone(), zone.source(), note);
      }
      case WINDOW_CUSTOM -> {
        if (from == null || to == null) {
          throw new ResponseStatusException(
              org.springframework.http.HttpStatus.BAD_REQUEST,
              "自定义窗口必须同时给 from 与 to（日期，yyyy-MM-dd）");
        }
        if (from.isAfter(to)) {
          throw new ResponseStatusException(
              org.springframework.http.HttpStatus.BAD_REQUEST,
              "起始日期不能晚于结束日期（from=" + from + "、to=" + to + "）");
        }
        return new Window(WINDOW_CUSTOM, from, to, asOf, zone.zone(), zone.source(), "");
      }
      default ->
          throw new ResponseStatusException(
              org.springframework.http.HttpStatus.BAD_REQUEST,
              "window 只能是 TODAY / YESTERDAY / THIS_MONTH / CUSTOM，收到：" + effective);
    }
  }

  /** 本租户的分桶时区（**服务端判定**，不来自请求参数）。 */
  private record TenantZone(String zone, String source) {}

  private TenantZone tenantZone(UUID tenantId) {
    var row =
        sql.fetchOne(
            "select timezone from reporting_store_metric_fact "
                + "where tenant_id = ? and timezone <> '' order by updated_at desc limit 1",
            tenantId);
    if (row != null) {
      var zone = row.get(0, String.class);
      if (zone != null && !zone.isBlank()) {
        try {
          ZoneId.of(zone);
          return new TenantZone(zone, "PROJECTION");
        } catch (RuntimeException ignored) {
          // 落库的是非法时区 id ⇒ 落回 UTC，并在响应里说清楚（不静默）。
        }
      }
    }
    return new TenantZone("UTC", "FALLBACK_UTC");
  }

  // ==========================================================================
  // 能力声明的静态事实
  // ==========================================================================

  /** 本服务**真的读过**的上游模块。 */
  private static final List<String> UPSTREAM_MODULES =
      List.of(
          "13.1.1.2 多店对比（reporting 自己的店铺维度读模型：口径表 + 店铺维度投影事实 + 掉队判定）",
          "9.1.1.1 平台单列表（trade：带店铺归属的成交金额与订单量，经 13.1.1.2 的刷新链路投影）",
          "14.3.1.1 活动报表（channel：店铺维度广告花费，经 13.1.1.2 的刷新链路投影）",
          "14.1.1.1 广告活动列表（channel：店铺归属平台的唯一来源，经 13.1.1.2 的刷新链路投影）");

  /** **确实拿不到**的部分（每条写清原因与影响）。 */
  private static final List<String> NOT_DELIVERED =
      List.of(
          "**店铺维度的利润率**：店铺口径表（reporting_store_metric_definition）里没有利润 / 毛利类的"
              + "店铺级指标码；上游成本分录（12.4.1.2）与利润表（12.5.2.2）**只有法人 / 公司维度**。"
              + "⇒ 店铺概览里「利润率」这一格按「未接通」显示（available=false + 归属模块 + 原因），"
              + "**不给公式也不给分母**。归属：13.2 利润分析 / 12 财务成本核算。",
          "**店铺维度的库存天数**：上游在手库存（7.1.1.1 库存总览）按 SKU × 法人 × 仓组织，"
              + "**没有店铺维度**；店铺口径表里也没有库存天数指标码。"
              + "⇒ 店铺概览里「库存天数」这一格按「未接通」显示，**不给公式也不给分母**。"
              + "归属：7.1.1.1 库存总览（warehouse）/ 13.5.1.1 库存分析。",
          "**「销售额」没有单一口径**：店铺级成交金额分成平台侧口径与还原口径两个，"
              + "另加两者的口径差异额。本模块三者并列呈现 —— 只取其一当「销售额」等于抹掉口径差异。",
          "**下钻只能到整页**：13 的店铺分析页（13.1.1.2 多店对比）与该店铺订单页（09 平台单列表）"
              + "都已交付，但这两页**目前都不接受按店铺预筛选的 URL 参数**，所以点进去看到的是全量列表 —— "
              + "本模块不编一个不存在的深链参数。",
          "**店铺维度的可见范围按租户隔离**：本模块的可见店铺集合 = 本租户投影出来的店铺档案；"
              + "**没有**「按成员 / 按部门再切一刀店铺」的维度（上游没有这个维度可切）。");

  /** 判定规则（阈值写清楚，页面上照着显示）。 */
  private static final List<StoreOverviewModels.StoreOverviewRule> RULES =
      List.of(
          new StoreOverviewModels.StoreOverviewRule(
              "LAGGARD",
              "掉队店铺：平台侧成交金额的环比增长率 ≤ −20%。"
                  + "环比 = (当前窗口 − 上一等长窗口) ÷ 上一等长窗口；上一窗口为 0 或没有事实时不可比。"
                  + "这条判定与 13.1.1.2 多店对比**完全同源**（口径归 13，工作台只引用不重算）。"),
          new StoreOverviewModels.StoreOverviewRule(
              "GROWTH",
              "增长店铺：平台侧成交金额的环比增长率 ≥ +20%。"),
          new StoreOverviewModels.StoreOverviewRule(
              "STABLE",
              "稳定：环比增长率在 ±20% 之间。"),
          new StoreOverviewModels.StoreOverviewRule(
              "UNCLASSIFIED",
              "不可比：上一等长窗口没有该店铺的成交金额事实（或金额为 0），算不出环比增长率 —— "
                  + "不按 0% 处理。"),
          new StoreOverviewModels.StoreOverviewRule(
              "RANKING",
              "排序按平台侧成交金额规模（1 = 最大）；**排名在币种内做** —— "
                  + "一个 USD 店铺和一个 EUR 店铺的大小本来就不可比。"));

  /** 口径纪律说明。 */
  private static final List<String> NOTES =
      List.of(
          "口径**单点定义**在 13.1.1.2（reporting_store_metric_definition）：本模块只引用，不重算 —— "
              + "派生型口径（口径差异额）在口径表里只写「分子 / 分母引用」，不写公式。",
          "「没有数据」与「数据是 0」是两件事：事实不存在、口径未登记、派生指标分子或分母缺失，"
              + "三种情况一律显示「—」并写明原因，绝不显示成 0。",
          "多币种**绝不相加**：涉币维度按币种分列，界面上**永远没有跨币种的「总计」**。",
          "「今日 / 昨日 / 本月」的边界按**本租户时区**算（取自本租户店铺投影事实的分桶时区），"
              + "不写死 UTC。",
          "工作台**只读 + 跳转**：本模块不提供任何写端点，也不下发租户号与范围参数 —— "
              + "租户来自已认证成员关系，范围由服务端判定。");
}
