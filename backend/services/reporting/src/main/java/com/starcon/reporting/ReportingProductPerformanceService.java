package com.starcon.reporting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

/**
 * 13.3.1.1 产品表现分析的**读模型**（蓝图「输出」句：多指标分析页 + 趋势 + 排名分位）。
 *
 * <p>⚠️ <b>本类不重算口径</b>：单位 / 聚合方式 / 分子分母 / 币种口径全部来自口径表，
 * 本类只**按定义聚合**。派生指标（毛利额 / 毛利率 / 退货率 / 点击率 / 转化率）
 * 是**递归求值**出来的 —— 而不是在这里写死一句 SQL 公式。
 *
 * <p>⚠️ <b>「算不出来」与「等于 0」必须分开</b>：分母为 0、事实不存在、指标未登记、
 * 上游未交付，四种情况一律返回 {@code null} / {@code NO_DATA} / {@code NOT_COMPARABLE} /
 * {@code NOT_DELIVERED}，**不用 0 冒充**。
 *
 * <p>⚠️ <b>不跨币种相加</b>：金额按币种分别报。**只有件数与次数**（与货币无关的量）
 * 才给出一个跨币种合计，它的 {@code currency} 是**空串** —— 空串 = 与币种无关。
 *
 * <p><b>🔴 维度不重复计数</b>（本模块最容易算错的地方）：
 *
 * <ul>
 *   <li><b>订单侧指标</b>（销量 / 销售额 / 出库成本 / 退货）：SKU 层取自己；
 *       SPU 层 = 名下所有 SKU 之和；Listing 层 = **可唯一归属**的那些 SKU 之和
 *       （同一 SKU 挂在多个 Listing 上 ⇒ 归不了，**不摊给任何 Listing**）。
 *   <li><b>平台侧指标</b>（流量 / 转化 / 广告 / 跟卖）：原生粒度是 Listing。
 *       SKU 层 = 它挂的 Listing 之和；SPU 层 = 名下 SKU 涉及的**去重后**的 Listing 之和
 *       （同一个 Listing 挂两个 SKU 时**只算一次** —— 算两遍就是虚增流量）。
 *   <li><b>库存 / 库龄</b>：原生粒度是 SKU（物理事实，不可按平台拆分）。
 * </ul>
 */
@Service
public class ReportingProductPerformanceService {

  /** 变化量与变化率的刻度（与库里 numeric(24,4) 对齐）。 */
  private static final int SCALE = 4;

  /** 百分数刻度。 */
  private static final int PERCENT_SCALE = 4;

  private final DSLContext sql;
  private final com.starcon.tenancy.TenantTransactions tenants;
  private final ReportingProductPerformanceRefreshService refresh;

  public ReportingProductPerformanceService(
      DSLContext sql,
      com.starcon.tenancy.TenantTransactions tenants,
      ReportingProductPerformanceRefreshService refresh) {
    this.sql = sql;
    this.tenants = tenants;
    this.refresh = refresh;
  }

  // ==========================================================================
  // 内部形状
  // ==========================================================================

  /** 一条事实（已按口径把「数量列 / 金额列」归一成一个数）。 */
  private record FactRow(
      String dimensionType,
      UUID dimensionId,
      String metricCode,
      String currency,
      LocalDate bucketDate,
      BigDecimal value) {}

  /** 一条快照。 */
  private record SnapshotRow(
      String dimensionType,
      UUID dimensionId,
      String metricCode,
      String currency,
      BigDecimal value,
      String textValue,
      OffsetDateTime sourceObservedAt,
      LocalDate windowFrom,
      LocalDate windowTo,
      String sourceService,
      String sourceReference) {}

  /** 指标口径（两张口径表合并后的运行时真相）。 */
  private record Definition(
      String metricCode,
      String displayName,
      String unit,
      String aggregation,
      String numeratorMetricCode,
      String denominatorMetricCode,
      String currencyScope,
      boolean currencyAware,
      boolean available,
      String ownerModule,
      String description,
      String availabilityNote) {}

  /** 目标产品的覆盖范围（三个维度各自的集合）。 */
  private record Scope(
      String dimensionType,
      UUID dimensionId,
      String dimensionKey,
      String dimensionName,
      /** 订单侧指标用：可唯一归属的 SKU。 */
      List<UUID> orderSkus,
      /** 库存 / 库龄用：全部绑定的 SKU。 */
      List<UUID> allSkus,
      /** 平台侧指标用：去重后的 Listing。 */
      List<UUID> listings,
      List<String> platformCodes) {}

  // ==========================================================================
  // 主入口
  // ==========================================================================

  /**
   * 单产品 360 度分析（**一次性拉齐**：一页滚动看完，不用来回跳）。
   *
   * @param currency 只看某个币种（空 = 全部币种分别列）
   */
  public ReportingProductPerformanceModels.ProductPerformanceResponse analyze(
      UUID tenantId,
      LocalDate from,
      LocalDate to,
      String dimensionType,
      String dimensionKey,
      String currency) {
    var baselineFrom = from.minusDays(daysBetween(from, to));
    var baselineTo = from.minusDays(1);
    return analyze(tenantId, from, to, baselineFrom, baselineTo, dimensionType, dimensionKey, currency);
  }

  /** 同上，但基线窗口显式给定。 */
  public ReportingProductPerformanceModels.ProductPerformanceResponse analyze(
      UUID tenantId,
      LocalDate from,
      LocalDate to,
      LocalDate baselineFrom,
      LocalDate baselineTo,
      String dimensionType,
      String dimensionKey,
      String currency) {
    return tenants.run(
        tenantId,
        () -> {
          refresh.seedDefinitions(tenantId);
          var definitions = loadDefinitions(tenantId);
          var catalog = refresh.loadCatalog(tenantId);
          var scope = resolveScope(catalog, dimensionType, dimensionKey);
          var dataUpdatedAt = dataUpdatedAt(tenantId);
          var timezones = timezones(tenantId);

          if (scope == null) {
            return new ReportingProductPerformanceModels.ProductPerformanceResponse(
                from,
                to,
                baselineFrom,
                baselineTo,
                "PREVIOUS_PERIOD",
                dimensionType,
                "",
                dimensionKey,
                "",
                List.of(),
                List.of(),
                List.of(),
                0,
                0,
                List.of(),
                List.of(),
                null,
                definitionViews(definitions),
                caliberNotes(dimensionType, List.of(), List.of()),
                timezones,
                dataUpdatedAt,
                "最近一次 360 度投影落库时刻。",
                "NOT_PROJECTED",
                "找不到这个商品维度。",
                "NO_DIMENSION",
                "商品档案（13.2.2.2 的投影）里没有「" + dimensionKey + "」—— 先刷新商品维度投影。");
          }

          var facts = loadFacts(tenantId, baselineFrom, to);
          var snapshots = loadSnapshots(tenantId, baselineFrom, to);

          var current = aggregate(scope, facts, snapshots, from, to, definitions);
          var baseline = aggregate(scope, facts, snapshots, baselineFrom, baselineTo, definitions);

          var sections = new ArrayList<ReportingProductPerformanceModels.PerformanceSection>();
          for (var section : ReportingProductPerformanceCatalog.sections()) {
            sections.add(
                section(
                    section,
                    scope,
                    definitions,
                    current,
                    baseline,
                    currency,
                    snapshots,
                    from,
                    to));
          }

          var trend = trend(scope, facts, definitions, from, to);
          var ranking = ranking(tenantId, scope, facts, definitions, from, to);

          var caliber =
              caliberNotes(
                  scope.dimensionType(),
                  scope.listings(),
                  scope.allSkus().size() > scope.orderSkus().size()
                      ? scopedSharedSkus(catalog)
                      : List.of());

          var status = "PROJECTED";
          var statusNote = "";
          String emptyReason = null;
          var emptyNote = "";
          var hasAnyValue = current.values().stream().anyMatch(java.util.Objects::nonNull);
          if (!hasAnyValue) {
            status = "NOT_PROJECTED";
            emptyReason = "NO_FACTS";
            emptyNote =
                "这个商品在 " + from + " ~ " + to + " 这个窗口里**没有投影任何 360 度事实** —— "
                    + "请先刷新（POST /reporting/product-performance/refresh）。";
            statusNote = emptyNote;
          }

          return new ReportingProductPerformanceModels.ProductPerformanceResponse(
              from,
              to,
              baselineFrom,
              baselineTo,
              "PREVIOUS_PERIOD",
              scope.dimensionType(),
              scope.dimensionId().toString(),
              scope.dimensionKey(),
              scope.dimensionName(),
              scope.platformCodes(),
              scope.listings().stream().map(UUID::toString).toList(),
              scope.allSkus().stream().map(UUID::toString).toList(),
              scope.allSkus().size(),
              scope.listings().size(),
              sections,
              trend,
              ranking,
              definitionViews(definitions),
              caliber,
              timezones,
              dataUpdatedAt,
              "最近一次 360 度投影落库时刻（事实与快照的 updated_at 取较晚者）。",
              status,
              statusNote,
              emptyReason,
              emptyNote);
        });
  }

  /** 窗口天数（含首尾）。 */
  private static long daysBetween(LocalDate from, LocalDate to) {
    return java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
  }

  // ==========================================================================
  // 目标解析
  // ==========================================================================

  /**
   * 解析目标产品的覆盖范围。
   *
   * <p>🔴 **Listing 层的「可唯一归属」**：同一 SKU 挂在多个 Listing 上时，
   * 它的订单侧指标归不了任何一个 Listing ⇒ **不摊给任何 Listing**（摊给谁都是造假）。
   */
  private Scope resolveScope(
      ReportingProductPerformanceRefreshService.Catalog catalog,
      String dimensionType,
      String dimensionKey) {
    var key = dimensionKey == null ? "" : dimensionKey.trim();
    if ("SKU".equals(dimensionType)) {
      var sku = catalog.skuByCode().get(key);
      if (sku == null) return null;
      var listings = catalog.listingsOf(sku.id());
      return new Scope(
          "SKU",
          sku.id(),
          sku.code(),
          sku.name(),
          List.of(sku.id()),
          List.of(sku.id()),
          listings,
          platformCodesOf(catalog, listings));
    }
    if ("SPU".equals(dimensionType)) {
      for (var spu : catalog.spuById().values()) {
        if (!spu.code().equals(key)) continue;
        var skus = catalog.skusBySpuId().getOrDefault(spu.id(), List.of());
        var listings = new LinkedHashSet<UUID>();
        for (var skuId : skus) listings.addAll(catalog.listingsOf(skuId));
        return new Scope(
            "SPU",
            spu.id(),
            spu.code(),
            spu.name(),
            skus,
            skus,
            new ArrayList<>(listings),
            platformCodesOf(catalog, new ArrayList<>(listings)));
      }
      return null;
    }
    // LISTING
    for (var listing : catalog.listingById().values()) {
      if (!listing.key().equals(key)) continue;
      var allSkus = new ArrayList<UUID>();
      for (var entry : catalog.listingsBySkuId().entrySet()) {
        if (entry.getValue().contains(listing.id())) allSkus.add(entry.getKey());
      }
      var orderSkus = new ArrayList<UUID>();
      for (var skuId : allSkus) {
        if (catalog.listingsOf(skuId).size() == 1) orderSkus.add(skuId);
      }
      return new Scope(
          "LISTING",
          listing.id(),
          listing.key(),
          listing.name(),
          orderSkus,
          allSkus,
          List.of(listing.id()),
          platformCodesOf(catalog, List.of(listing.id())));
    }
    return null;
  }

  private List<String> platformCodesOf(
      ReportingProductPerformanceRefreshService.Catalog catalog, List<UUID> listingIds) {
    var codes = new LinkedHashSet<String>();
    for (var listingId : listingIds) {
      var listing = catalog.listingById().get(listingId);
      if (listing == null) continue;
      if (!listing.platformCode().isBlank()) codes.add(listing.platformCode());
    }
    return new ArrayList<>(codes);
  }

  /** 挂在多个 Listing 上的 SKU（Listing 层无法唯一归属的那部分）。 */
  private List<String> scopedSharedSkus(ReportingProductPerformanceRefreshService.Catalog catalog) {
    var shared = new ArrayList<String>();
    for (var entry : catalog.listingsBySkuId().entrySet()) {
      if (entry.getValue().size() <= 1) continue;
      var sku = catalog.skuById().get(entry.getKey());
      if (sku != null) shared.add(sku.code());
    }
    shared.sort(String::compareTo);
    return shared;
  }

  // ==========================================================================
  // 取数
  // ==========================================================================

  /** 读两张口径表（**13.2.2.2 的商品维度口径 + 本模块的口径**），合成运行时真相。 */
  private Map<String, Definition> loadDefinitions(UUID tenantId) {
    var definitions = new LinkedHashMap<String, Definition>();
    for (var row :
        sql.fetch(
            """
            select metric_code, display_name, unit, aggregation, numerator_metric_code,
                   denominator_metric_code, currency_scope, currency_aware, available, owner_module,
                   description, availability_note
              from reporting_product_performance_metric_definition
             where tenant_id = ? and enabled
            """,
            tenantId)) {
      definitions.put(
          row.get(0, String.class),
          new Definition(
              row.get(0, String.class),
              row.get(1, String.class),
              row.get(2, String.class),
              row.get(3, String.class),
              row.get(4, String.class),
              row.get(5, String.class),
              row.get(6, String.class),
              Boolean.TRUE.equals(row.get(7, Boolean.class)),
              Boolean.TRUE.equals(row.get(8, Boolean.class)),
              row.get(9, String.class),
              row.get(10, String.class),
              row.get(11, String.class)));
    }
    // 🔴 13.2.2.2 的商品维度口径（销售额 / 销量 / 订单数）也进同一张运行时视图 ——
    //   口径唯一：它们只在这里定义一次，本模块只引用。
    for (var row :
        sql.fetch(
            """
            select metric_code, display_name, unit, aggregation, numerator_metric_code,
                   denominator_metric_code, currency_scope, currency_aware, description, owner_module
              from reporting_product_metric_definition
             where tenant_id = ? and enabled
            """,
            tenantId)) {
      var code = row.get(0, String.class);
      if (definitions.containsKey(code)) continue;
      definitions.put(
          code,
          new Definition(
              code,
              row.get(1, String.class),
              row.get(2, String.class),
              row.get(3, String.class),
              row.get(4, String.class),
              row.get(5, String.class),
              row.get(6, String.class),
              Boolean.TRUE.equals(row.get(7, Boolean.class)),
              true,
              row.get(9, String.class),
              row.get(8, String.class),
              ""));
    }
    return definitions;
  }

  /** 口径目录（供前端渲染与「指标」端点）。 */
  public List<ReportingProductPerformanceModels.PerformanceMetricDefinitionView> metricDefinitions(
      UUID tenantId) {
    return tenants.run(
        tenantId,
        () -> {
          refresh.seedDefinitions(tenantId);
          return definitionViews(loadDefinitions(tenantId));
        });
  }

  /** 口径目录（供前端渲染）。 */
  private List<ReportingProductPerformanceModels.PerformanceMetricDefinitionView> definitionViews(
      Map<String, Definition> definitions) {
    var out = new ArrayList<ReportingProductPerformanceModels.PerformanceMetricDefinitionView>();
    for (var section : ReportingProductPerformanceCatalog.sections()) {
      for (var code : section.metricCodes()) {
        var definition = definitions.get(code);
        if (definition == null) continue;
        out.add(
            new ReportingProductPerformanceModels.PerformanceMetricDefinitionView(
                definition.metricCode(),
                definition.displayName(),
                definition.unit(),
                definition.aggregation(),
                definition.numeratorMetricCode(),
                definition.denominatorMetricCode(),
                definition.currencyScope(),
                definition.currencyAware(),
                definition.available(),
                definition.ownerModule(),
                definition.description(),
                definition.availabilityNote()));
      }
    }
    return out;
  }

  /** 读事实（13.2.2.2 的商品维度事实 + 本模块的事实），**按口径把数量列归一成一个数**。 */
  private List<FactRow> loadFacts(UUID tenantId, LocalDate from, LocalDate to) {
    var definitions = loadDefinitions(tenantId);
    var rows = new ArrayList<FactRow>();
    for (var row :
        sql.fetch(
            """
            select dimension_type, dimension_id, metric_code, currency, bucket_date, value, quantity
              from reporting_product_metric_fact
             where tenant_id = ? and bucket_date between ? and ?
            """,
            tenantId,
            from,
            to)) {
      var code = row.get(2, String.class);
      var definition = definitions.get(code);
      if (definition == null) continue;
      var quantity = row.get(6, BigDecimal.class);
      var value = row.get(5, BigDecimal.class);
      rows.add(
          new FactRow(
              row.get(0, String.class),
              row.get(1, UUID.class),
              code,
              row.get(3, String.class),
              row.get(4, LocalDate.class),
              "QUANTITY".equals(definition.unit()) ? quantity : value));
    }
    for (var row :
        sql.fetch(
            """
            select dimension_type, dimension_id, metric_code, currency, bucket_date, value, quantity
              from reporting_product_performance_fact
             where tenant_id = ? and bucket_date between ? and ?
            """,
            tenantId,
            from,
            to)) {
      var code = row.get(2, String.class);
      var definition = definitions.get(code);
      if (definition == null) continue;
      var quantity = row.get(6, BigDecimal.class);
      var value = row.get(5, BigDecimal.class);
      rows.add(
          new FactRow(
              row.get(0, String.class),
              row.get(1, UUID.class),
              code,
              row.get(3, String.class),
              row.get(4, LocalDate.class),
              "QUANTITY".equals(definition.unit()) ? quantity : value));
    }
    return rows;
  }

  /** 读快照。 */
  private List<SnapshotRow> loadSnapshots(UUID tenantId, LocalDate from, LocalDate to) {
    var rows = new ArrayList<SnapshotRow>();
    for (var row :
        sql.fetch(
            """
            select dimension_type, dimension_id, metric_code, currency, value, quantity, text_value,
                   source_observed_at, window_from, window_to, source_service, source_reference
              from reporting_product_performance_snapshot
             where tenant_id = ?
            """,
            tenantId)) {
      var currency = row.get(3, String.class);
      var value = row.get(4, BigDecimal.class);
      var quantity = row.get(5, BigDecimal.class);
      // 数量型指标（库龄 / 件数）落在 quantity 列上，其余落在 value 列上。
      var code = row.get(2, String.class);
      // 🔴 **只有 QUANTITY 单位的指标落在 quantity 列**（库存三兄弟）；
      //   库龄天数的单位是 COUNT（天数），落在 value 列 —— 与 13.2.2.2 的
      //   「COUNT 指标落 value 列」同一条纪律。
      var isQuantity =
          ReportingProductPerformanceCatalog.PRODUCT_ON_HAND_QUANTITY.equals(code)
              || ReportingProductPerformanceCatalog.PRODUCT_RESERVED_QUANTITY.equals(code)
              || ReportingProductPerformanceCatalog.PRODUCT_AVAILABLE_QUANTITY.equals(code);
      rows.add(
          new SnapshotRow(
              row.get(0, String.class),
              row.get(1, UUID.class),
              code,
              currency,
              isQuantity ? quantity : value,
              row.get(6, String.class),
              row.get(7, OffsetDateTime.class),
              row.get(8, LocalDate.class),
              row.get(9, LocalDate.class),
              row.get(10, String.class),
              row.get(11, String.class)));
    }
    return rows;
  }

  // ==========================================================================
  // 聚合
  // ==========================================================================

  /** 指标在某个窗口上的取值：key = metricCode + "|" + currency。 */
  private Map<String, BigDecimal> aggregate(
      Scope scope,
      List<FactRow> facts,
      List<SnapshotRow> snapshots,
      LocalDate from,
      LocalDate to,
      Map<String, Definition> definitions) {
    var out = new LinkedHashMap<String, BigDecimal>();

    // ---- 订单侧指标（SKU 原生粒度；Listing 层只算可唯一归属的 SKU）----
    for (var code :
        List.of(
            "PRODUCT_SALES_QUANTITY",
            "PRODUCT_SALES_AMOUNT",
            "PRODUCT_ORDER_COUNT",
            ReportingProductPerformanceCatalog.PRODUCT_COGS_AMOUNT,
            ReportingProductPerformanceCatalog.PRODUCT_RETURNED_QUANTITY,
            ReportingProductPerformanceCatalog.PRODUCT_RETURNED_AMOUNT)) {
      var definition = definitions.get(code);
      if (definition == null) continue;
      var currencyAware = definition.currencyAware();
      var found = false;
      for (var fact : facts) {
        if (!fact.metricCode().equals(code)) continue;
        if (fact.bucketDate().isBefore(from) || fact.bucketDate().isAfter(to)) continue;
        if (!"SKU".equals(fact.dimensionType())) continue;
        if (!scope.orderSkus().contains(fact.dimensionId())) continue;
        if (currencyAware && fact.currency() == null) continue;
        // 🔴 **与币种无关的量（件数 / 单数）一律落到空币种键**：13.2.2.2 的数量事实
        //   带着**订单的币种**（那是它按币种分行存的要求），但件数本身与货币无关 ——
        //   这里必须跨币种合并，否则同一件数的两行会被拆成两个「币种」。
        var key = code + "|" + (currencyAware ? (fact.currency() == null ? "" : fact.currency()) : "");
        var current = out.getOrDefault(key, BigDecimal.ZERO);
        out.put(key, current.add(fact.value() == null ? BigDecimal.ZERO : fact.value()));
        found = true;
      }
      if (!found) continue;
      if (!currencyAware) {
        out.putIfAbsent(code + "|", BigDecimal.ZERO);
      }
    }

    // ---- 平台侧指标（Listing 原生粒度；SKU/SPU 层按去重后的 Listing 求和）----
    for (var code :
        List.of(
            ReportingProductPerformanceCatalog.PRODUCT_IMPRESSIONS,
            ReportingProductPerformanceCatalog.PRODUCT_CLICKS,
            ReportingProductPerformanceCatalog.PRODUCT_SESSIONS,
            ReportingProductPerformanceCatalog.PRODUCT_PLATFORM_UNITS,
            ReportingProductPerformanceCatalog.PRODUCT_PLATFORM_SALES_AMOUNT)) {
      var definition = definitions.get(code);
      if (definition == null) continue;
      var currencyAware = definition.currencyAware();
      var found = false;
      for (var fact : facts) {
        if (!fact.metricCode().equals(code)) continue;
        if (fact.bucketDate().isBefore(from) || fact.bucketDate().isAfter(to)) continue;
        if (!"LISTING".equals(fact.dimensionType())) continue;
        if (!scope.listings().contains(fact.dimensionId())) continue;
        if (currencyAware && fact.currency() == null) continue;
        var key = code + "|" + (currencyAware ? (fact.currency() == null ? "" : fact.currency()) : "");
        var current = out.getOrDefault(key, BigDecimal.ZERO);
        out.put(key, current.add(fact.value() == null ? BigDecimal.ZERO : fact.value()));
        found = true;
      }
      if (!found) continue;
      if (!currencyAware) {
        out.putIfAbsent(code + "|", BigDecimal.ZERO);
      }
    }

    // ---- 广告（Listing 原生；**按活动去重**：一个活动投多个 Listing 时只算一次）----
    for (var code :
        List.of(
            ReportingProductPerformanceCatalog.PRODUCT_AD_SPEND,
            ReportingProductPerformanceCatalog.PRODUCT_AD_IMPRESSIONS,
            ReportingProductPerformanceCatalog.PRODUCT_AD_CLICKS,
            ReportingProductPerformanceCatalog.PRODUCT_AD_ORDERS,
            ReportingProductPerformanceCatalog.PRODUCT_AD_SALES_AMOUNT)) {
      var definition = definitions.get(code);
      if (definition == null) continue;
      var perCampaign = new LinkedHashMap<String, BigDecimal>();
      for (var snapshot : snapshots) {
        if (!snapshot.metricCode().equals(code)) continue;
        if (!"LISTING".equals(snapshot.dimensionType())) continue;
        if (!scope.listings().contains(snapshot.dimensionId())) continue;
        if (!inWindow(snapshot, from, to)) continue;
        var campaign = snapshot.sourceReference();
        var value = snapshot.value() == null ? BigDecimal.ZERO : snapshot.value();
        var existing = perCampaign.get(campaign);
        if (existing == null || value.compareTo(existing) > 0) perCampaign.put(campaign, value);
      }
      if (perCampaign.isEmpty()) continue;
      var currencyAware = definition.currencyAware();
      var byCurrency = new TreeMap<String, BigDecimal>();
      for (var entry : perCampaign.entrySet()) {
        var currency = currencyOf(entry.getKey(), currencyAware);
        byCurrency.merge(currency, entry.getValue(), BigDecimal::add);
      }
      for (var entry : byCurrency.entrySet()) {
        out.put(code + "|" + entry.getKey(), entry.getValue());
      }
    }

    // ---- 库存与库龄（SKU 原生；时点快照 ⇒ 不按窗口过滤）----
    for (var code :
        List.of(
            ReportingProductPerformanceCatalog.PRODUCT_ON_HAND_QUANTITY,
            ReportingProductPerformanceCatalog.PRODUCT_RESERVED_QUANTITY,
            ReportingProductPerformanceCatalog.PRODUCT_AVAILABLE_QUANTITY)) {
      var sum = BigDecimal.ZERO;
      var found = false;
      for (var snapshot : snapshots) {
        if (!snapshot.metricCode().equals(code)) continue;
        if (!"SKU".equals(snapshot.dimensionType())) continue;
        if (!scope.allSkus().contains(snapshot.dimensionId())) continue;
        sum = sum.add(snapshot.value() == null ? BigDecimal.ZERO : snapshot.value());
        found = true;
      }
      if (found) out.put(code + "|", sum);
    }
    // 库龄：取该维度下**最长**的库龄（不是求和）。
    var longestAging = (BigDecimal) null;
    String agingBucket = "";
    for (var snapshot : snapshots) {
      if (!snapshot.metricCode().equals(ReportingProductPerformanceCatalog.PRODUCT_AGING_DAYS)) continue;
      if (!"SKU".equals(snapshot.dimensionType())) continue;
      if (!scope.allSkus().contains(snapshot.dimensionId())) continue;
      var value = snapshot.value();
      if (value == null) continue;
      if (longestAging == null || value.compareTo(longestAging) > 0) {
        longestAging = value;
        agingBucket = snapshot.textValue() == null ? "" : snapshot.textValue();
      }
    }
    if (longestAging != null) out.put(ReportingProductPerformanceCatalog.PRODUCT_AGING_DAYS + "|", longestAging);

    // ---- 跟卖价格（Listing 原生；取窗口内**最近一次**观测）----
    for (var code :
        List.of(
            ReportingProductPerformanceCatalog.PRODUCT_HIJACKER_PRICE,
            ReportingProductPerformanceCatalog.PRODUCT_REFERENCE_PRICE)) {
      var latest = new LinkedHashMap<String, SnapshotRow>();
      for (var snapshot : snapshots) {
        if (!snapshot.metricCode().equals(code)) continue;
        if (!"LISTING".equals(snapshot.dimensionType())) continue;
        if (!scope.listings().contains(snapshot.dimensionId())) continue;
        if (!inWindow(snapshot, from, to)) continue;
        var currency = snapshot.currency() == null ? "" : snapshot.currency();
        var current = latest.get(currency);
        if (current == null || laterThan(snapshot, current)) latest.put(currency, snapshot);
      }
      for (var entry : latest.entrySet()) {
        out.put(code + "|" + entry.getKey(), entry.getValue().value());
      }
    }

    // ---- 派生指标（递归求值；分母为 0 ⇒ 算不出来）----
    derive(out, definitions, ReportingProductPerformanceCatalog.PRODUCT_GROSS_PROFIT_AMOUNT);
    derive(out, definitions, ReportingProductPerformanceCatalog.PRODUCT_GROSS_MARGIN);
    derive(out, definitions, ReportingProductPerformanceCatalog.PRODUCT_RETURN_RATE);
    derive(out, definitions, ReportingProductPerformanceCatalog.PRODUCT_CTR);
    derive(out, definitions, ReportingProductPerformanceCatalog.PRODUCT_CONVERSION_RATE);
    derive(out, definitions, ReportingProductPerformanceCatalog.PRODUCT_AD_CTR);
    return out;
  }

  /** 派生指标求值（口径来自口径表：分子 ÷ 分母 / 分子 − 分母）。 */
  private void derive(
      Map<String, BigDecimal> out, Map<String, Definition> definitions, String metricCode) {
    var definition = definitions.get(metricCode);
    if (definition == null) return;
    var numerator = definition.numeratorMetricCode();
    var denominator = definition.denominatorMetricCode();
    if (numerator == null || denominator == null) return;
    var currencies = new LinkedHashSet<String>();
    for (var key : out.keySet()) {
      if (key.startsWith(numerator + "|")) currencies.add(key.substring(numerator.length() + 1));
    }
    for (var key : out.keySet()) {
      if (key.startsWith(denominator + "|")) currencies.add(key.substring(denominator.length() + 1));
    }
    for (var currency : currencies) {
      var numeratorValue = out.get(numerator + "|" + currency);
      var denominatorValue = out.get(denominator + "|" + currency);
      if (numeratorValue == null || denominatorValue == null) continue;
      BigDecimal derived;
      if ("DERIVED_DIFF".equals(definition.aggregation())) {
        derived = numeratorValue.subtract(denominatorValue);
      } else {
        if (denominatorValue.signum() == 0) continue; // 算不出来（不是 0）
        // 🔴 **先乘 100 再除**：先除到 4 位小数再乘 100 会把有效数字截掉
        //   （4 ÷ 55 先得 0.0727 再乘 100 = 7.27，而正确值是 7.2727）。
        derived =
            numeratorValue
                .multiply(BigDecimal.valueOf(100))
                .divide(denominatorValue.abs(), PERCENT_SCALE, RoundingMode.HALF_UP);
      }
      out.put(metricCode + "|" + currency, derived);
    }
  }

  /** 快照是否落在窗口内（时点类恒为 true；窗口类比 window_from / window_to）。 */
  private boolean inWindow(SnapshotRow snapshot, LocalDate from, LocalDate to) {
    if (snapshot.windowFrom() == null) {
      // 时点类：按观测时刻落在窗口内算（观测时刻为空 = 投影时刻，只在当期窗口里有值）。
      if (snapshot.sourceObservedAt() == null) return true;
      var observed = snapshot.sourceObservedAt().toLocalDate();
      return !observed.isBefore(from) && !observed.isAfter(to);
    }
    return snapshot.windowFrom().equals(from) && snapshot.windowTo().equals(to);
  }

  private boolean laterThan(SnapshotRow candidate, SnapshotRow current) {
    if (candidate.sourceObservedAt() == null) return false;
    if (current.sourceObservedAt() == null) return true;
    return candidate.sourceObservedAt().isAfter(current.sourceObservedAt());
  }

  /** 从快照的来源标识里取币种（广告那一族的来源标识尾部就是币种）。 */
  private String currencyOf(String sourceReference, boolean currencyAware) {
    if (!currencyAware) return "";
    var index = sourceReference.lastIndexOf(':');
    if (index < 0) return "";
    var currency = sourceReference.substring(index + 1);
    return currency.isBlank() ? "" : currency;
  }

  // ==========================================================================
  // 区块
  // ==========================================================================

  private ReportingProductPerformanceModels.PerformanceSection section(
      ReportingProductPerformanceCatalog.Section section,
      Scope scope,
      Map<String, Definition> definitions,
      Map<String, BigDecimal> current,
      Map<String, BigDecimal> baseline,
      String currencyFilter,
      List<SnapshotRow> snapshots,
      LocalDate from,
      LocalDate to) {
    var metrics = new ArrayList<ReportingProductPerformanceModels.PerformanceMetric>();
    var sectionAvailability = "NO_DATA";
    var sectionNote = "这一段在这个窗口里没有投影任何事实。";

    for (var code : section.metricCodes()) {
      var definition = definitions.get(code);
      if (definition == null) continue;
      if (!definition.available()) {
        metrics.add(
            new ReportingProductPerformanceModels.PerformanceMetric(
                code,
                definition.displayName(),
                definition.unit(),
                "",
                null,
                null,
                null,
                null,
                "NOT_DELIVERED",
                definition.availabilityNote(),
                ""));
        sectionAvailability = "NOT_DELIVERED";
        sectionNote = definition.availabilityNote();
        continue;
      }
      for (var currency : currenciesOf(code, current, baseline, definitions)) {
        if (currencyFilter != null
            && !currencyFilter.isBlank()
            && !currencyFilter.equals(currency)
            && !currency.isBlank()) {
          continue;
        }
        var value = current.get(code + "|" + currency);
        var baselineValue = baseline.get(code + "|" + currency);
        var availability = "AVAILABLE";
        var note = "";
        if (value == null && baselineValue == null) {
          availability = "NO_DATA";
          note = "这个窗口里没有投影「" + definition.displayName() + "」的事实（不是 0）。";
        } else if (value == null) {
          availability = "NO_DATA";
          note = "当期没有事实，基线窗口有（不是 0）。";
        }
        BigDecimal change = null;
        BigDecimal changeRate = null;
        if (value != null && baselineValue != null) {
          change = value.subtract(baselineValue).setScale(SCALE, RoundingMode.HALF_UP);
          if (baselineValue.signum() != 0) {
            // 🔴 同上：先乘 100 再除，别把有效数字截在中间。
            changeRate =
                change.multiply(BigDecimal.valueOf(100)).divide(baselineValue.abs(), SCALE, RoundingMode.HALF_UP);
          } else {
            note = "基线为 0 ⇒ 变化率**算不出来**（「上期是 0」与「本期没变」是两件事）。";
          }
        }
        // 🔴 时点快照（库存 / 库龄）没有可比基线。
        if (isSnapshotMetric(code)) {
          baselineValue = null;
          change = null;
          changeRate = null;
          if (availability == "AVAILABLE") {
            note = "时点快照：没有可比基线（库存与库龄不是一个「窗口内累计」的量）。";
          }
        }
        metrics.add(
            new ReportingProductPerformanceModels.PerformanceMetric(
                code,
                definition.displayName(),
                definition.unit(),
                currency,
                value,
                baselineValue,
                change,
                changeRate,
                availability,
                note,
                sourceNoteOf(code)));
        if ("AVAILABLE".equals(availability)) {
          sectionAvailability = "AVAILABLE";
          sectionNote = "";
        }
      }
    }

    var competitors = List.<ReportingProductPerformanceModels.PerformanceCompetitorRow>of();
    var adCampaigns = List.<ReportingProductPerformanceModels.PerformanceAdCampaignRow>of();
    if ("COMPETITION".equals(section.sectionCode())) {
      competitors = competitors(snapshots, scope, from, to);
    }
    if ("ADS".equals(section.sectionCode())) {
      adCampaigns = adCampaigns(snapshots, scope, from, to);
    }
    return new ReportingProductPerformanceModels.PerformanceSection(
        section.sectionCode(),
        section.title(),
        section.description(),
        sectionAvailability,
        sectionNote,
        metrics,
        competitors,
        adCampaigns);
  }

  /** 时点类指标（库存 / 库龄）：没有可比基线。 */
  private boolean isSnapshotMetric(String metricCode) {
    return ReportingProductPerformanceCatalog.PRODUCT_ON_HAND_QUANTITY.equals(metricCode)
        || ReportingProductPerformanceCatalog.PRODUCT_RESERVED_QUANTITY.equals(metricCode)
        || ReportingProductPerformanceCatalog.PRODUCT_AVAILABLE_QUANTITY.equals(metricCode)
        || ReportingProductPerformanceCatalog.PRODUCT_AGING_DAYS.equals(metricCode);
  }

  /** 指标在两个窗口上出现过的币种（与币种无关的指标只有一个空币种）。 */
  private Set<String> currenciesOf(
      String metricCode,
      Map<String, BigDecimal> current,
      Map<String, BigDecimal> baseline,
      Map<String, Definition> definitions) {
    var definition = definitions.get(metricCode);
    if (definition == null) return Set.of();
    if (!definition.currencyAware()) return Set.of("");
    var currencies = new LinkedHashSet<String>();
    for (var map : List.of(current, baseline)) {
      for (var key : map.keySet()) {
        if (!key.startsWith(metricCode + "|")) continue;
        currencies.add(key.substring(metricCode.length() + 1));
      }
    }
    if (currencies.isEmpty()) {
      // 🔴 没有任何事实时也要**出现这一行**（标成「没有数据」）——
      //   少一行会让用户以为「这个指标不存在」，而不是「这个窗口没有数据」。
      currencies.add("");
    }
    return currencies;
  }

  /** 数据来源标注（页面上原样显示）。 */
  private String sourceNoteOf(String metricCode) {
    if (metricCode.startsWith("PRODUCT_AD_")
        || metricCode.startsWith("PRODUCT_IMPRESSIONS")
        || metricCode.startsWith("PRODUCT_CLICKS")
        || metricCode.startsWith("PRODUCT_SESSIONS")
        || metricCode.startsWith("PRODUCT_PLATFORM_")) {
      return "平台报表口径（4.5.1.1 / 14.3.1.1）";
    }
    if (ReportingProductPerformanceCatalog.PRODUCT_COGS_AMOUNT.equals(metricCode)) {
      return "12.4.1.2 成本法出库成本";
    }
    if (metricCode.startsWith("PRODUCT_RETURNED")) {
      return "内部售后单口径";
    }
    if (metricCode.startsWith("PRODUCT_ON_HAND")
        || metricCode.startsWith("PRODUCT_RESERVED")
        || metricCode.startsWith("PRODUCT_AVAILABLE")
        || ReportingProductPerformanceCatalog.PRODUCT_AGING_DAYS.equals(metricCode)) {
      return "7 库存（时点快照）";
    }
    if (metricCode.startsWith("PRODUCT_HIJACKER")
        || ReportingProductPerformanceCatalog.PRODUCT_REFERENCE_PRICE.equals(metricCode)) {
      return "4.5.2.1（参考指标）";
    }
    if (metricCode.startsWith("PRODUCT_SALES") || "PRODUCT_ORDER_COUNT".equals(metricCode)) {
      return "内部订单口径（9.1.1.1，经 13.2.2.2 投影）";
    }
    return "";
  }

  /** 跟卖 / 竞品行（按价格差比例升序 —— 最狠的跟卖排最前）。 */
  private List<ReportingProductPerformanceModels.PerformanceCompetitorRow> competitors(
      List<SnapshotRow> snapshots, Scope scope, LocalDate from, LocalDate to) {
    var byRecord = new LinkedHashMap<String, Map<String, SnapshotRow>>();
    for (var snapshot : snapshots) {
      if (!"LISTING".equals(snapshot.dimensionType())) continue;
      if (!scope.listings().contains(snapshot.dimensionId())) continue;
      if (!inWindow(snapshot, from, to)) continue;
      var code = snapshot.metricCode();
      if (!ReportingProductPerformanceCatalog.PRODUCT_HIJACKER_PRICE.equals(code)
          && !ReportingProductPerformanceCatalog.PRODUCT_REFERENCE_PRICE.equals(code)) {
        continue;
      }
      var record = snapshot.sourceReference().replaceFirst(":price$", "").replaceFirst(":reference$", "");
      byRecord
          .computeIfAbsent(record, k -> new LinkedHashMap<>())
          .put(code, snapshot);
    }
    var rows = new ArrayList<ReportingProductPerformanceModels.PerformanceCompetitorRow>();
    for (var entry : byRecord.entrySet()) {
      var price = entry.getValue().get(ReportingProductPerformanceCatalog.PRODUCT_HIJACKER_PRICE);
      var reference =
          entry.getValue().get(ReportingProductPerformanceCatalog.PRODUCT_REFERENCE_PRICE);
      if (price == null) continue;
      var text = price.textValue() == null ? "" : price.textValue();
      var parts = text.split("\\|", 3);
      var currency = price.currency() == null ? "" : price.currency();
      var referencePrice = reference == null ? null : reference.value();
      BigDecimal gap = null;
      BigDecimal gapRatio = null;
      if (referencePrice != null) {
        gap = referencePrice.subtract(price.value());
        if (referencePrice.signum() != 0) {
          gapRatio =
              gap.multiply(BigDecimal.valueOf(100)).divide(referencePrice.abs(), PERCENT_SCALE, RoundingMode.HALF_UP);
        }
      }
      rows.add(
          new ReportingProductPerformanceModels.PerformanceCompetitorRow(
              parts.length > 0 ? parts[0] : "",
              parts.length > 1 ? parts[1] : "",
              parts.length > 2 ? parts[2] : "",
              price.value(),
              referencePrice,
              gap,
              gapRatio,
              currency,
              price.sourceObservedAt(),
              "🔴 **参考指标**：自家参考售价是人工维护的，非平台权威价；竞品数据不进自有商品模型。"));
    }
    rows.sort(
        Comparator.comparing(
                (ReportingProductPerformanceModels.PerformanceCompetitorRow r) ->
                    r.priceGapRatio() == null ? BigDecimal.valueOf(Long.MAX_VALUE) : r.priceGapRatio())
            .thenComparing(ReportingProductPerformanceModels.PerformanceCompetitorRow::sellerId));
    return rows;
  }

  /** 广告活动行（活动级数据 —— 不是这个商品分摊到的）。 */
  private List<ReportingProductPerformanceModels.PerformanceAdCampaignRow> adCampaigns(
      List<SnapshotRow> snapshots, Scope scope, LocalDate from, LocalDate to) {
    var byCampaign = new LinkedHashMap<String, Map<String, SnapshotRow>>();
    for (var snapshot : snapshots) {
      if (!"LISTING".equals(snapshot.dimensionType())) continue;
      if (!scope.listings().contains(snapshot.dimensionId())) continue;
      if (!snapshot.sourceService().equals(ReportingProductPerformanceRefreshService.SOURCE_AD)) continue;
      if (!inWindow(snapshot, from, to)) continue;
      var campaign = snapshot.textValue() == null ? "" : snapshot.textValue();
      if (campaign.isBlank()) continue;
      byCampaign.computeIfAbsent(campaign, k -> new LinkedHashMap<>()).put(snapshot.metricCode(), snapshot);
    }
    var rows = new ArrayList<ReportingProductPerformanceModels.PerformanceAdCampaignRow>();
    for (var entry : byCampaign.entrySet()) {
      var spend = entry.getValue().get(ReportingProductPerformanceCatalog.PRODUCT_AD_SPEND);
      var currency = spend == null || spend.currency() == null ? "" : spend.currency();
      rows.add(
          new ReportingProductPerformanceModels.PerformanceAdCampaignRow(
              entry.getKey(),
              entry.getKey(),
              "",
              "",
              spend == null ? null : spend.value(),
              valueOf(entry.getValue(), ReportingProductPerformanceCatalog.PRODUCT_AD_IMPRESSIONS),
              valueOf(entry.getValue(), ReportingProductPerformanceCatalog.PRODUCT_AD_CLICKS),
              valueOf(entry.getValue(), ReportingProductPerformanceCatalog.PRODUCT_AD_ORDERS),
              valueOf(entry.getValue(), ReportingProductPerformanceCatalog.PRODUCT_AD_SALES_AMOUNT),
              currency,
              "🔴 **活动级**数据：这个活动的花费覆盖它投放的**全部**商品，"
                  + "不是这个商品分摊到的花费（商品维度广告报表归 14.3.1.3、归因归 14.3.2.1，"
                  + "本轮都未交付）。"));
    }
    rows.sort(
        Comparator.comparing(
                (ReportingProductPerformanceModels.PerformanceAdCampaignRow r) ->
                    r.spend() == null ? BigDecimal.ZERO : r.spend())
            .reversed());
    return rows;
  }

  private BigDecimal valueOf(Map<String, SnapshotRow> snapshots, String metricCode) {
    var snapshot = snapshots.get(metricCode);
    return snapshot == null ? null : snapshot.value();
  }

  // ==========================================================================
  // 趋势
  // ==========================================================================

  /** 逐日趋势（**一天一行**；没有事实的那天不给假数据）。 */
  private List<ReportingProductPerformanceModels.PerformanceTrendPoint> trend(
      Scope scope, List<FactRow> facts, Map<String, Definition> definitions, LocalDate from, LocalDate to) {
    var salesByDate = new TreeMap<LocalDate, BigDecimal>();
    var returnsByDate = new TreeMap<LocalDate, BigDecimal>();
    var impressionsByDate = new TreeMap<LocalDate, BigDecimal>();
    var clicksByDate = new TreeMap<LocalDate, BigDecimal>();
    var sessionsByDate = new TreeMap<LocalDate, BigDecimal>();
    var salesAmountByDate = new TreeMap<LocalDate, Map<String, BigDecimal>>();
    var cogsByDate = new TreeMap<LocalDate, Map<String, BigDecimal>>();

    for (var fact : facts) {
      if (fact.bucketDate().isBefore(from) || fact.bucketDate().isAfter(to)) continue;
      var code = fact.metricCode();
      if ("PRODUCT_SALES_QUANTITY".equals(code)) {
        if (!"SKU".equals(fact.dimensionType()) || !scope.orderSkus().contains(fact.dimensionId())) {
          continue;
        }
        salesByDate.merge(fact.bucketDate(), fact.value(), BigDecimal::add);
      } else if (ReportingProductPerformanceCatalog.PRODUCT_COGS_AMOUNT.equals(code)) {
        if (!"SKU".equals(fact.dimensionType()) || !scope.orderSkus().contains(fact.dimensionId())) {
          continue;
        }
        cogsByDate
            .computeIfAbsent(fact.bucketDate(), k -> new TreeMap<>())
            .merge(fact.currency() == null ? "" : fact.currency(), fact.value(), BigDecimal::add);
      } else if (ReportingProductPerformanceCatalog.PRODUCT_RETURNED_QUANTITY.equals(code)) {
        if (!"SKU".equals(fact.dimensionType()) || !scope.orderSkus().contains(fact.dimensionId())) {
          continue;
        }
        returnsByDate.merge(fact.bucketDate(), fact.value(), BigDecimal::add);
      } else if (ReportingProductPerformanceCatalog.PRODUCT_IMPRESSIONS.equals(code)) {
        if (!"LISTING".equals(fact.dimensionType()) || !scope.listings().contains(fact.dimensionId())) {
          continue;
        }
        impressionsByDate.merge(fact.bucketDate(), fact.value(), BigDecimal::add);
      } else if (ReportingProductPerformanceCatalog.PRODUCT_CLICKS.equals(code)) {
        if (!"LISTING".equals(fact.dimensionType()) || !scope.listings().contains(fact.dimensionId())) {
          continue;
        }
        clicksByDate.merge(fact.bucketDate(), fact.value(), BigDecimal::add);
      } else if (ReportingProductPerformanceCatalog.PRODUCT_SESSIONS.equals(code)) {
        if (!"LISTING".equals(fact.dimensionType()) || !scope.listings().contains(fact.dimensionId())) {
          continue;
        }
        sessionsByDate.merge(fact.bucketDate(), fact.value(), BigDecimal::add);
      } else if ("PRODUCT_SALES_AMOUNT".equals(code)) {
        if (!"SKU".equals(fact.dimensionType()) || !scope.orderSkus().contains(fact.dimensionId())) {
          continue;
        }
        salesAmountByDate
            .computeIfAbsent(fact.bucketDate(), k -> new TreeMap<>())
            .merge(fact.currency() == null ? "" : fact.currency(), fact.value(), BigDecimal::add);
      }
    }

    var points = new ArrayList<ReportingProductPerformanceModels.PerformanceTrendPoint>();
    var day = from;
    while (!day.isAfter(to)) {
      var salesAmounts = currencyAmounts(salesAmountByDate.get(day));
      var cogsAmounts = currencyAmounts(cogsByDate.get(day));
      var grossProfits = new ArrayList<ReportingProductPerformanceModels.PerformanceCurrencyValue>();
      if (salesAmounts != null) {
        var cogsMap = cogsByDate.get(day);
        for (var amount : salesAmounts) {
          var cogs = cogsMap == null ? null : cogsMap.get(amount.currency());
          if (cogs == null) continue;
          grossProfits.add(
              new ReportingProductPerformanceModels.PerformanceCurrencyValue(
                  amount.currency(), amount.amount().subtract(cogs)));
        }
      }
      points.add(
          new ReportingProductPerformanceModels.PerformanceTrendPoint(
              day,
              salesByDate.get(day),
              returnsByDate.get(day),
              impressionsByDate.get(day),
              clicksByDate.get(day),
              sessionsByDate.get(day),
              salesAmounts == null ? List.of() : salesAmounts,
              cogsAmounts == null ? List.of() : cogsAmounts,
              grossProfits));
      day = day.plusDays(1);
    }
    return points;
  }

  private List<ReportingProductPerformanceModels.PerformanceCurrencyValue> currencyAmounts(
      Map<String, BigDecimal> amounts) {
    if (amounts == null || amounts.isEmpty()) return null;
    var out = new ArrayList<ReportingProductPerformanceModels.PerformanceCurrencyValue>();
    for (var entry : amounts.entrySet()) {
      out.add(
          new ReportingProductPerformanceModels.PerformanceCurrencyValue(
              entry.getKey(), entry.getValue()));
    }
    return out;
  }

  // ==========================================================================
  // 排名分位
  // ==========================================================================

  /**
   * 同层级排名分位。
   *
   * <p>🔴 **按「销量（件数）」排，不按金额排**：金额跨币种不可比 ——
   * 用金额排会把 USD 的第 1 名和 EUR 的第 1 名混在一起。
   *
   * <p>🔴 **不可比的不进榜**：同层级里**没有事实**的商品不参与排名（数了它，
   * 排名的分母就会把「没数据」当成「表现差」）。
   */
  private ReportingProductPerformanceModels.PerformanceRanking ranking(
      UUID tenantId,
      Scope scope,
      List<FactRow> facts,
      Map<String, Definition> definitions,
      LocalDate from,
      LocalDate to) {
    var values = new LinkedHashMap<String, BigDecimal>();
    var catalog = refresh.loadCatalog(tenantId);
    if (scope.dimensionType().equals("SKU")) {
      for (var sku : catalog.skuById().values()) {
        var quantity = sumQuantity(facts, sku.id(), from, to);
        if (quantity != null) values.put(sku.code(), quantity);
      }
    } else if (scope.dimensionType().equals("SPU")) {
      for (var spu : catalog.spuById().values()) {
        var quantity = BigDecimal.ZERO;
        var found = false;
        for (var skuId : catalog.skusBySpuId().getOrDefault(spu.id(), List.of())) {
          var value = sumQuantity(facts, skuId, from, to);
          if (value == null) continue;
          quantity = quantity.add(value);
          found = true;
        }
        if (found) values.put(spu.code(), quantity);
      }
    } else {
      for (var listing : catalog.listingById().values()) {
        var quantity = BigDecimal.ZERO;
        var found = false;
        for (var fact : facts) {
          if (!"PRODUCT_SALES_QUANTITY".equals(fact.metricCode())) continue;
          if (!"LISTING".equals(fact.dimensionType())) continue;
          if (!listing.id().equals(fact.dimensionId())) continue;
          if (fact.bucketDate().isBefore(from) || fact.bucketDate().isAfter(to)) continue;
          quantity = quantity.add(fact.value() == null ? BigDecimal.ZERO : fact.value());
          found = true;
        }
        if (found) values.put(listing.key(), quantity);
      }
    }
    if (values.isEmpty()) {
      return new ReportingProductPerformanceModels.PerformanceRanking(
          "PRODUCT_SALES_QUANTITY",
          "销量（件）",
          0,
          0,
          null,
          null,
          null,
          "同层级（" + scope.dimensionType() + " 层）有事实的商品",
          "NO_DATA",
          "同层级还没有任何商品在这个窗口里有销量事实 ⇒ 没有可比的排名。");
    }
    var sorted = new ArrayList<>(values.values());
    sorted.sort(Comparator.reverseOrder());
    var total = sorted.size();
    var target = values.get(scope.dimensionKey());
    if (target == null) {
      return new ReportingProductPerformanceModels.PerformanceRanking(
          "PRODUCT_SALES_QUANTITY",
          "销量（件）",
          0,
          total,
          null,
          median(sorted),
          average(sorted),
          "同层级（" + scope.dimensionType() + " 层）有事实的商品",
          "NOT_COMPARABLE",
          "这个商品在这个窗口里没有销量事实 ⇒ 不进榜（不是最后一名）。");
    }
    var rank = 1;
    for (var value : sorted) {
      if (value.compareTo(target) > 0) rank++;
    }
    var percentile =
        BigDecimal.valueOf(total - rank + 1)
            .multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(total), PERCENT_SCALE, RoundingMode.HALF_UP);
    return new ReportingProductPerformanceModels.PerformanceRanking(
        "PRODUCT_SALES_QUANTITY",
        "销量（件）",
        rank,
        total,
        percentile,
        median(sorted),
        average(sorted),
        "同层级（" + scope.dimensionType() + " 层）有事实的商品，按**销量（件数）**排"
            + "（金额跨币种不可比 ⇒ 不用金额排）",
        "AVAILABLE",
        "");
  }

  /** 某个 SKU 在窗口里的销量（没有事实 ⇒ null = 不进榜）。 */
  private BigDecimal sumQuantity(List<FactRow> facts, UUID skuId, LocalDate from, LocalDate to) {
    var sum = BigDecimal.ZERO;
    var found = false;
    for (var fact : facts) {
      if (!"PRODUCT_SALES_QUANTITY".equals(fact.metricCode())) continue;
      if (!"SKU".equals(fact.dimensionType()) || !skuId.equals(fact.dimensionId())) continue;
      if (fact.bucketDate().isBefore(from) || fact.bucketDate().isAfter(to)) continue;
      sum = sum.add(fact.value() == null ? BigDecimal.ZERO : fact.value());
      found = true;
    }
    return found ? sum : null;
  }

  private BigDecimal median(List<BigDecimal> sorted) {
    if (sorted.isEmpty()) return null;
    var size = sorted.size();
    if (size % 2 == 1) return sorted.get(size / 2);
    return sorted.get(size / 2 - 1).add(sorted.get(size / 2)).divide(BigDecimal.valueOf(2), PERCENT_SCALE, RoundingMode.HALF_UP);
  }

  private BigDecimal average(List<BigDecimal> values) {
    if (values.isEmpty()) return null;
    var sum = BigDecimal.ZERO;
    for (var value : values) sum = sum.add(value);
    return sum.divide(BigDecimal.valueOf(values.size()), PERCENT_SCALE, RoundingMode.HALF_UP);
  }

  // ==========================================================================
  // 口径说明与数据更新时间
  // ==========================================================================

  /** 口径说明（页面上原样显示）。 */
  private List<String> caliberNotes(
      String dimensionType, List<UUID> listings, List<String> sharedSkus) {
    var notes = new ArrayList<String>();
    notes.add(
        "🔴 **绝不跨币种相加**：金额按币种分别列出；只有件数与次数（与货币无关）"
            + "给一个跨币种合计，它的币种显示为「与币种无关」。");
    notes.add(
        "🔴 **「算不出来」与「等于 0」分开**：分母为 0 或没有事实时，这一格是"
            + "「不可比」/「没有数据」，**不是 0**。");
    notes.add(
        "基线默认「**上一个等长周期**」；变化率 = 变化量 ÷ |基线|，基线为 0 时**算不出来**。");
    notes.add(
        "排名分位按**销量（件数）**排：金额跨币种不可比，用金额排会让不同币种的"
            + "「第一名」混在一起；同层级里没有事实的商品**不进榜**。");
    notes.add(
        "流量 / 转化是**平台报表口径**（4.5.1.1），与内部订单口径不同 —— 两套口径并列显示，"
            + "**不混算**。");
    notes.add(
        "毛利 = 销售额 − 出库成本（12.4.1.2 **成本法**）：🔴 **不等于 13.4 多模式利润** —— "
            + "它不含平台费 / 头程 / 物流费 / 广告费等成本项。");
    notes.add(
        "广告表现是**活动级**数据（14.3.1.1）：channel 未交付商品维度广告报表（14.3.1.3）"
            + "与归因（14.3.2.1）⇒ **不给** SKU/SPU/Listing 粒度的 ACOS / ROAS。");
    notes.add(
        "竞品对比是**参考指标**（4.5.2.1 跟卖价格 vs 自家参考售价）："
            + "自家参考售价是人工维护的，**非平台权威价**；竞品数据**不进自有商品模型**。");
    notes.add(
        "库存与库龄是**时点快照**，没有可比基线（它们不是一个「窗口内累计」的量）；"
            + "warehouse 侧没有成本列 ⇒ **库存金额算不出来**。");
    if ("LISTING".equals(dimensionType)) {
      notes.add(
          "🔴 **Listing 层的订单侧指标只算「可唯一归属」的那部分**："
              + "同一款内部 SKU 挂在多个 Listing 上时，它的销量 / 成本 / 退货归不了任何 Listing —— "
              + "本模块**不摊给任何 Listing**。"
              + (sharedSkus.isEmpty()
                  ? ""
                  : "当前涉及：" + String.join("、", sharedSkus) + "。"));
    }
    if ("SPU".equals(dimensionType)) {
      notes.add(
          "🔴 **SPU 是跨平台经营的基本单位**：SPU 层把名下所有 SKU 合并，不按平台拆 —— "
              + "只按平台 SKU 看会误判商品整体表现。");
    }
    if (!listings.isEmpty()) {
      notes.add(
          "流量 / 转化 / 广告 / 跟卖是 **Listing 层**的事实：SPU 与 SKU 层按**去重后**的 Listing 求和"
              + "（同一个 Listing 挂多个 SKU 时**只算一次**）。");
    }
    return notes;
  }

  /** 数据更新时间（最近一次投影落库时刻）。 */
  private OffsetDateTime dataUpdatedAt(UUID tenantId) {
    // ⚠️ **不用 greatest()**：PostgreSQL 里 greatest(NULL, x) 是 NULL ——
    //   只要有一张表还没有行，数据更新时间就会变成 null（「看起来像没数据」）。
    OffsetDateTime latest = null;
    for (var sqlText :
        List.of(
            "select max(updated_at) from reporting_product_performance_fact where tenant_id = ?",
            "select max(updated_at) from reporting_product_performance_snapshot where tenant_id = ?",
            "select max(updated_at) from reporting_product_metric_fact where tenant_id = ?")) {
      var row = sql.fetchOne(sqlText, tenantId);
      if (row == null) continue;
      var value = row.get(0, OffsetDateTime.class);
      if (value == null) continue;
      if (latest == null || value.isAfter(latest)) latest = value;
    }
    return latest;
  }

  /** 本次窗口内出现过的分桶时区。 */
  private List<String> timezones(UUID tenantId) {
    var zones = new LinkedHashSet<String>();
    for (var row :
        sql.fetch(
            "select distinct timezone from reporting_product_performance_fact where tenant_id = ?",
            tenantId)) {
      var zone = row.get(0, String.class);
      if (zone != null && !zone.isBlank()) zones.add(zone);
    }
    for (var row :
        sql.fetch(
            "select distinct timezone from reporting_product_metric_fact where tenant_id = ?",
            tenantId)) {
      var zone = row.get(0, String.class);
      if (zone != null && !zone.isBlank()) zones.add(zone);
    }
    return new ArrayList<>(zones);
  }
}
