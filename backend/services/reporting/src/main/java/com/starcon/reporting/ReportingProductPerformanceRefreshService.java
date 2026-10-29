package com.starcon.reporting;

import com.starcon.tenancy.Ids;
import com.starcon.tenancy.TenantTransactions;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

/**
 * 13.3.1.1 产品表现分析的**拉取适配器**（上游授权只读 API → 360 度事实 / 快照）。
 *
 * <p>⚠️ <b>本类只写投影</b>（{@code reporting_product_performance_fact} /
 * {@code reporting_product_performance_snapshot}），不产生任何业务事实 ——
 * 订单仍在 trade，成本仍在 finance，库存在 warehouse，流量与广告仍在 channel。
 *
 * <p><b>🔴 三条纪律</b>：
 *
 * <ol>
 *   <li><b>幂等</b>：事实行按「维度 + 指标 + 日 + 币种 + 来源」唯一 ⇒ 重复刷新是**覆盖**而不是翻倍。
 *   <li><b>口径唯一</b>：指标码必须已在 {@code reporting_product_performance_metric_definition}
 *       登记（DB 触发器钉死）⇒ 事实不能自己发明指标码。
 *   <li><b>读不到 ≠ 没有数据</b>：上游故障一律翻成失败（503），**不用空集合冒充**。
 * </ol>
 *
 * <p><b>🔴 维度只存「上游原生粒度」</b>：流量 / 广告 / 跟卖是 <b>Listing 层</b>，
 * 退货 / 成本是 <b>SKU 层</b>。SPU 与 Listing 的口径在**查询侧**算 ——
 * 在落库时硬摊会把同一个 Listing 的流量算两遍（一个 Listing 挂多个 SKU 时）。
 */
@Service
public class ReportingProductPerformanceRefreshService {

  /** 一次刷新允许的最大窗口天数（基线窗口与当期窗口合计）。 */
  static final int MAX_WINDOW_DAYS = 62;

  /** 来源服务标识（幂等键的一部分，也是「谁写的」的唯一根据）。 */
  static final String SOURCE_PERFORMANCE = "channel-performance";

  static final String SOURCE_AFTERSALES = "trade-aftersales";
  static final String SOURCE_COST = "finance-cost";
  static final String SOURCE_INVENTORY = "warehouse-inventory";
  static final String SOURCE_AGING = "warehouse-aging";
  static final String SOURCE_AD = "channel-ad";
  static final String SOURCE_HIJACKER = "channel-hijacker";

  private final ReportingProductPerformancePort port;
  private final ReportingTradePort trade;
  private final ReportingWarehousePort warehouse;
  private final ReportingProductSalesRefreshService productSales;
  private final DSLContext sql;
  private final TenantTransactions tenants;

  public ReportingProductPerformanceRefreshService(
      ReportingProductPerformancePort port,
      ReportingTradePort trade,
      ReportingWarehousePort warehouse,
      ReportingProductSalesRefreshService productSales,
      DSLContext sql,
      TenantTransactions tenants) {
    this.port = port;
    this.trade = trade;
    this.warehouse = warehouse;
    this.productSales = productSales;
    this.sql = sql;
    this.tenants = tenants;
  }

  // ==========================================================================
  // 商品档案（**只读复用** 13.2.2.2 的投影，不写它一张表）
  // ==========================================================================

  /** 一个 SKU 的档案。 */
  record SkuDim(UUID id, String code, String name, UUID spuId) {}

  /** 一个 SPU 的档案。 */
  record SpuDim(UUID id, String code, String name) {}

  /** 一个 Listing 的档案。 */
  record ListingDim(
      UUID id,
      String key,
      String name,
      String platformCode,
      String shopCode,
      String externalItemId) {}

  /**
   * 商品维度档案（**读** 13.2.2.2 的 {@code reporting_product_dimension} 与
   * {@code reporting_product_listing_sku}）。
   *
   * <p>🔴 本模块**不写**这两张表：它们是 13.2.2.2 的读模型，13.2.2.2 的刷新会
   * **按窗口删表**（{@code delete ... where bucket_date between ...}）—— 本模块的事实若写进去，
   * 兄弟模块刷一次就会被抹掉。
   */
  record Catalog(
      Map<UUID, SkuDim> skuById,
      Map<String, SkuDim> skuByCode,
      Map<UUID, SpuDim> spuById,
      Map<UUID, ListingDim> listingById,
      Map<UUID, List<UUID>> listingsBySkuId,
      Map<UUID, List<UUID>> skusBySpuId) {

    /** 这个 SKU 挂在哪些 Listing 上。 */
    List<UUID> listingsOf(UUID skuId) {
      return listingsBySkuId.getOrDefault(skuId, List.of());
    }
  }

  /** 读商品维度档案（**只读** 13.2.2.2 的投影）。 */
  Catalog loadCatalog(UUID tenantId) {
    return tenants.run(
        tenantId,
        () -> {
          var skuById = new LinkedHashMap<UUID, SkuDim>();
          var skuByCode = new LinkedHashMap<String, SkuDim>();
          var spuById = new LinkedHashMap<UUID, SpuDim>();
          var listingById = new LinkedHashMap<UUID, ListingDim>();
          var skusBySpuId = new LinkedHashMap<UUID, List<UUID>>();
          var listingsBySkuId = new LinkedHashMap<UUID, List<UUID>>();

          for (var row :
              sql.fetch(
                  """
                  select dimension_type, dimension_id, dimension_key, dimension_name,
                         parent_dimension_id, platform_code, shop_code, external_item_id
                    from reporting_product_dimension
                   where tenant_id = ?
                  """,
                  tenantId)) {
            var type = row.get(0, String.class);
            var id = row.get(1, UUID.class);
            var key = row.get(2, String.class);
            var name = row.get(3, String.class);
            var parentId = row.get(4, UUID.class);
            if (id == null || key == null) continue;
            if ("SKU".equals(type)) {
              var sku = new SkuDim(id, key, name == null ? "" : name, parentId);
              skuById.put(id, sku);
              skuByCode.put(key, sku);
              if (parentId != null) {
                skusBySpuId.computeIfAbsent(parentId, k -> new ArrayList<>()).add(id);
              }
            } else if ("SPU".equals(type)) {
              spuById.put(id, new SpuDim(id, key, name == null ? "" : name));
            } else if ("LISTING".equals(type)) {
              listingById.put(
                  id,
                  new ListingDim(
                      id,
                      key,
                      name == null ? "" : name,
                      row.get(5, String.class) == null ? "" : row.get(5, String.class),
                      row.get(6, String.class) == null ? "" : row.get(6, String.class),
                      row.get(7, String.class) == null ? "" : row.get(7, String.class)));
            }
          }

          for (var row :
              sql.fetch(
                  """
                  select listing_id, sku_id
                    from reporting_product_listing_sku
                   where tenant_id = ?
                  """,
                  tenantId)) {
            var listingId = row.get(0, UUID.class);
            var skuId = row.get(1, UUID.class);
            if (listingId == null || skuId == null) continue;
            listingsBySkuId.computeIfAbsent(skuId, k -> new ArrayList<>()).add(listingId);
          }

          return new Catalog(
              skuById, skuByCode, spuById, listingById, listingsBySkuId, skusBySpuId);
        });
  }

  // ==========================================================================
  // 刷新
  // ==========================================================================

  /**
   * 刷新 360 度投影。
   *
   * @param from 当期窗口起始日
   * @param to 当期窗口结束日
   * @param baselineFrom 基线窗口起始日（默认「上一个等长周期」）
   * @param baselineTo 基线窗口结束日
   * @param timezone 分桶所用时区（跨时区店铺汇总必须显示所用时区）
   */
  public List<ReportingProductPerformanceModels.ProductPerformanceRefreshResult> refresh(
      UUID tenantId,
      String bearer,
      LocalDate from,
      LocalDate to,
      LocalDate baselineFrom,
      LocalDate baselineTo,
      ZoneId timezone) {
    var results = new ArrayList<ReportingProductPerformanceModels.ProductPerformanceRefreshResult>();
    var catalog = loadCatalog(tenantId);
    // ⚠️ 口径必须先播种（事实行的指标码必须已登记，DB 触发器钉死）。
    seedDefinitions(tenantId);

    var rangeFrom = baselineFrom.isBefore(from) ? baselineFrom : from;
    var rangeTo = baselineTo.isAfter(to) ? baselineTo : to;

    results.add(refreshTraffic(tenantId, bearer, rangeFrom, rangeTo, catalog, timezone));
    results.add(refreshReturns(tenantId, bearer, rangeFrom, rangeTo, catalog, timezone));
    results.add(refreshCogs(tenantId, bearer, rangeFrom, rangeTo, catalog, timezone));
    results.add(refreshInventory(tenantId, bearer, catalog));
    results.add(refreshAging(tenantId, bearer, catalog));
    results.add(refreshAds(tenantId, bearer, from, to, baselineFrom, baselineTo, catalog));
    results.add(refreshCompetitors(tenantId, bearer, rangeFrom, rangeTo, catalog));
    return results;
  }

  // ==========================================================================
  // 流量与转化（channel 4.5.1.1 Listing 表现）—— **Listing 层日粒度**
  // ==========================================================================

  /** 一条日事实。 */
  record Fact(
      String dimensionType,
      UUID dimensionId,
      String dimensionKey,
      String metricCode,
      LocalDate bucketDate,
      String currency,
      BigDecimal value,
      BigDecimal quantity,
      String sourceService,
      String sourceReference) {}

  private ReportingProductPerformanceModels.ProductPerformanceRefreshResult refreshTraffic(
      UUID tenantId,
      String bearer,
      LocalDate from,
      LocalDate to,
      Catalog catalog,
      ZoneId timezone) {
    var facts = new LinkedHashMap<String, Fact>();
    int fetched = 0;
    int unmatched = 0;
    boolean truncated = false;

    for (int page = 0; page < ReportingProductPerformancePort.MAX_PAGES; page++) {
      var pageData = port.listingPerformance(from, to, page, bearer);
      if (pageData == null || pageData.items() == null || pageData.items().isEmpty()) break;
      for (var row : pageData.items()) {
        fetched++;
        if (row.listingId() == null || row.statDate() == null) continue;
        var listing = catalog.listingById().get(row.listingId());
        if (listing == null) {
          // 🔴 这个平台商品不在商品档案里（13.2.2.2 还没投影它）⇒ 不猜一个名字。
          unmatched++;
          continue;
        }
        var bucketDate = LocalDate.parse(row.statDate());
        var currency = row.currency() == null || row.currency().isBlank() ? null : row.currency();
        put(
            facts,
            new Fact(
                "LISTING",
                listing.id(),
                listing.key(),
                ReportingProductPerformanceCatalog.PRODUCT_IMPRESSIONS,
                bucketDate,
                null,
                row.impressions(),
                null,
                SOURCE_PERFORMANCE,
                listing.id() + ":impressions:" + bucketDate));
        put(
            facts,
            new Fact(
                "LISTING",
                listing.id(),
                listing.key(),
                ReportingProductPerformanceCatalog.PRODUCT_CLICKS,
                bucketDate,
                null,
                row.clicks(),
                null,
                SOURCE_PERFORMANCE,
                listing.id() + ":clicks:" + bucketDate));
        put(
            facts,
            new Fact(
                "LISTING",
                listing.id(),
                listing.key(),
                ReportingProductPerformanceCatalog.PRODUCT_SESSIONS,
                bucketDate,
                null,
                row.sessions(),
                null,
                SOURCE_PERFORMANCE,
                listing.id() + ":sessions:" + bucketDate));
        put(
            facts,
            new Fact(
                "LISTING",
                listing.id(),
                listing.key(),
                ReportingProductPerformanceCatalog.PRODUCT_PLATFORM_UNITS,
                bucketDate,
                null,
                null,
                row.unitsOrdered(),
                SOURCE_PERFORMANCE,
                listing.id() + ":units:" + bucketDate));
        if (currency != null) {
          put(
              facts,
              new Fact(
                  "LISTING",
                  listing.id(),
                  listing.key(),
                  ReportingProductPerformanceCatalog.PRODUCT_PLATFORM_SALES_AMOUNT,
                  bucketDate,
                  currency,
                  row.salesAmount(),
                  null,
                  SOURCE_PERFORMANCE,
                  listing.id() + ":sales:" + currency + ":" + bucketDate));
        }
      }
      if (pageData.items().size() < ReportingProductPerformancePort.PAGE_SIZE) break;
      if (page >= ReportingProductPerformancePort.MAX_PAGES - 1) truncated = true;
    }

    var projected =
        tenants.run(
            tenantId,
            () -> {
              deleteFacts(tenantId, SOURCE_PERFORMANCE, from, to);
              var count = 0;
              for (var fact : facts.values()) {
                insertFact(tenantId, fact, timezone);
                count++;
              }
              return count;
            });

    var note =
        (truncated ? "⚠️ 平台表现只翻到 " + ReportingProductPerformancePort.MAX_PAGES + " 页为止。 " : "")
            + (unmatched > 0
                ? "⚠️ 有 " + unmatched + " 条平台表现落在商品档案之外（13.2.2.2 还没投影这些平台商品）—— 没有归属，不摊给任何商品。"
                : "")
            + "已按平台统计日投影流量 / 转化事实（**平台报表口径**，不是内部订单口径）。";
    return new ReportingProductPerformanceModels.ProductPerformanceRefreshResult(
        "channel-listing-performance",
        fetched,
        projected,
        (truncated || unmatched > 0) ? "TRUNCATED" : "SUCCEEDED",
        note);
  }

  // ==========================================================================
  // 退货（trade 售后单）—— **SKU 层日粒度**
  // ==========================================================================

  private ReportingProductPerformanceModels.ProductPerformanceRefreshResult refreshReturns(
      UUID tenantId,
      String bearer,
      LocalDate from,
      LocalDate to,
      Catalog catalog,
      ZoneId timezone) {
    var facts = new LinkedHashMap<String, Fact>();
    int fetched = 0;
    int unmatched = 0;
    int detailFetches = 0;
    boolean truncated = false;

    for (int page = 0; page < ReportingProductPerformancePort.MAX_PAGES; page++) {
      var pageData = trade.aftersalesCases(from, to, page, bearer);
      if (pageData == null || pageData.rows() == null || pageData.rows().isEmpty()) break;
      for (var row : pageData.rows()) {
        fetched++;
        if (row.id() == null || row.receivedAt() == null) continue;
        if (detailFetches >= ReportingProductPerformancePort.MAX_AFTERSALE_DETAIL_FETCHES) {
          truncated = true;
          continue;
        }
        detailFetches++;
        var detail = port.aftersalesDetail(row.id(), bearer);
        if (detail == null || detail.orderLines() == null) continue;
        var bucketDate = row.receivedAt().atZoneSameInstant(timezone).toLocalDate();
        var currency = row.currency() == null || row.currency().isBlank() ? null : row.currency();
        for (var line : detail.orderLines()) {
          if (!line.involved()) continue;
          if (line.skuCode() == null || line.skuCode().isBlank()) continue;
          var sku = catalog.skuByCode().get(line.skuCode().trim());
          if (sku == null) {
            unmatched++;
            continue;
          }
          if (line.aftersalesQuantity() != null) {
            put(
                facts,
                new Fact(
                    "SKU",
                    sku.id(),
                    sku.code(),
                    ReportingProductPerformanceCatalog.PRODUCT_RETURNED_QUANTITY,
                    bucketDate,
                    null,
                    null,
                    line.aftersalesQuantity(),
                    SOURCE_AFTERSALES,
                    row.id() + ":" + line.lineNo() + ":qty"));
          }
          if (currency != null && line.aftersalesAmount() != null) {
            put(
                facts,
                new Fact(
                    "SKU",
                    sku.id(),
                    sku.code(),
                    ReportingProductPerformanceCatalog.PRODUCT_RETURNED_AMOUNT,
                    bucketDate,
                    currency,
                    line.aftersalesAmount(),
                    null,
                    SOURCE_AFTERSALES,
                    row.id() + ":" + line.lineNo() + ":amt"));
          }
        }
      }
      if (pageData.rows().size() < ReportingTradePort.PAGE_SIZE) break;
    }

    var projected =
        tenants.run(
            tenantId,
            () -> {
              deleteFacts(tenantId, SOURCE_AFTERSALES, from, to);
              var count = 0;
              for (var fact : facts.values()) {
                insertFact(tenantId, fact, timezone);
                count++;
              }
              return count;
            });

    var note =
        (truncated
            ? "⚠️ 售后明细最多取 "
                + ReportingProductPerformancePort.MAX_AFTERSALE_DETAIL_FETCHES
                + " 张（退货按 SKU 归集只存在于明细里），触到上限如实报已截断。 "
            : "")
            + (unmatched > 0
                ? "⚠️ 有 " + unmatched + " 条退货明细的 SKU 不在商品档案里（不猜一个 SKU）。"
                : "")
            + "已按受理日投影退货件数与退货金额（内部售后单口径）。";
    return new ReportingProductPerformanceModels.ProductPerformanceRefreshResult(
        "trade-aftersales", fetched, projected, truncated ? "TRUNCATED" : "SUCCEEDED", note);
  }

  // ==========================================================================
  // 出库成本（finance 12.4.1.2）—— **SKU 层日粒度**
  // ==========================================================================

  private ReportingProductPerformanceModels.ProductPerformanceRefreshResult refreshCogs(
      UUID tenantId,
      String bearer,
      LocalDate from,
      LocalDate to,
      Catalog catalog,
      ZoneId timezone) {
    // ⚠️ **先拉成本批次**：同一条出库流水被重算过时会产生新批次的新分录，
    //   旧分录仍在表里 ⇒ 直接加总会把成本算两遍。本服务按「同一流水取最新批次那条」。
    var runOrder = new LinkedHashMap<UUID, Integer>();
    int runsFetched = 0;
    boolean runsTruncated = false;
    for (int page = 0; page < ReportingProductPerformancePort.MAX_PAGES; page++) {
      var pageData = port.costRuns(page, bearer);
      if (pageData == null || pageData.items() == null || pageData.items().isEmpty()) break;
      for (var row : pageData.items()) {
        if (row.id() == null) continue;
        runsFetched++;
        runOrder.putIfAbsent(row.id(), runOrder.size());
      }
      if (pageData.items().size() < ReportingProductPerformancePort.PAGE_SIZE) break;
      if (page >= ReportingProductPerformancePort.MAX_PAGES - 1) runsTruncated = true;
    }

    var facts = new LinkedHashMap<String, Fact>();
    var latestByFlow = new LinkedHashMap<String, FlowPick>();
    int fetched = 0;
    int unmatched = 0;
    int unknownRun = 0;
    boolean truncated = false;

    for (int page = 0; page < ReportingProductPerformancePort.MAX_PAGES; page++) {
      var pageData = port.costEntries("SALES_OUTBOUND", page, bearer);
      if (pageData == null || pageData.items() == null || pageData.items().isEmpty()) break;
      for (var row : pageData.items()) {
        fetched++;
        if (row.occurredAt() == null || row.skuId() == null) continue;
        var bucketDate = row.occurredAt().atZoneSameInstant(timezone).toLocalDate();
        if (bucketDate.isBefore(from) || bucketDate.isAfter(to)) continue;
        var sku = catalog.skuById().get(row.skuId());
        if (sku == null) {
          unmatched++;
          continue;
        }
        if (row.costAmount() == null || row.currency() == null || row.currency().isBlank()) continue;
        var flowKey =
            (row.flowTransactionId() == null ? row.id().toString() : row.flowTransactionId().toString())
                + "|"
                + row.skuId();
        var previous = latestByFlow.get(flowKey);
        if (previous == null) {
          latestByFlow.put(flowKey, new FlowPick(row.runId(), costFact(sku, row, bucketDate)));
        } else if (row.runId() != null
            && runOrder.containsKey(row.runId())
            && (!runOrder.containsKey(previous.runId())
                || runOrder.get(row.runId()) < runOrder.get(previous.runId()))) {
          // 这一条来自**更新的批次** ⇒ 旧的（已被重算覆盖的）那条让位。
          latestByFlow.put(flowKey, new FlowPick(row.runId(), costFact(sku, row, bucketDate)));
        } else if (row.runId() == null || !runOrder.containsKey(row.runId())) {
          unknownRun++;
        }
      }
      if (pageData.items().size() < ReportingProductPerformancePort.PAGE_SIZE) break;
      if (page >= ReportingProductPerformancePort.MAX_PAGES - 1) truncated = true;
    }

    for (var pick : latestByFlow.values()) {
      put(facts, pick.fact());
    }

    var projected =
        tenants.run(
            tenantId,
            () -> {
              deleteFacts(tenantId, SOURCE_COST, from, to);
              var count = 0;
              for (var fact : facts.values()) {
                insertFact(tenantId, fact, timezone);
                count++;
              }
              return count;
            });

    var note =
        (truncated || runsTruncated
            ? "⚠️ 成本分录只翻到 " + ReportingProductPerformancePort.MAX_PAGES + " 页为止。 "
            : "")
            + (unmatched > 0 ? "⚠️ 有 " + unmatched + " 条成本分录的 SKU 不在商品档案里。" : "")
            + (unknownRun > 0
                ? "⚠️ 有 " + unknownRun + " 条成本分录找不到所属批次 —— 无法判断它是否已被重算覆盖，"
                    + "按原样保留并在此报出。"
                : "")
            + "已按出库日投影出库成本（12.4.1.2 **成本法**；🔴 不含平台费 / 头程 / 物流费 / 广告费，"
            + "所以它**不等于** 13.4 多模式利润）。";
    return new ReportingProductPerformanceModels.ProductPerformanceRefreshResult(
        "finance-cost-calculation",
        fetched,
        projected,
        (truncated || runsTruncated) ? "TRUNCATED" : "SUCCEEDED",
        note);
  }

  /** 一条出库成本事实（由成本分录换算）。 */
  private Fact costFact(
      SkuDim sku,
      ReportingProductPerformancePort.CostEntryRow row,
      LocalDate bucketDate) {
    return new Fact(
        "SKU",
        sku.id(),
        sku.code(),
        ReportingProductPerformanceCatalog.PRODUCT_COGS_AMOUNT,
        bucketDate,
        row.currency(),
        row.costAmount(),
        null,
        SOURCE_COST,
        (row.flowTransactionId() == null ? row.id().toString() : row.flowTransactionId().toString())
            + ":"
            + (row.runId() == null ? "" : row.runId())
            + ":"
            + bucketDate);
  }

  /** 同一条出库流水的**最新批次**那一版。 */
  private record FlowPick(UUID runId, Fact fact) {}

  // ==========================================================================
  // 库存与库龄（warehouse）—— **时点快照**
  // ==========================================================================

  /** 一条快照。 */
  record Snapshot(
      String dimensionType,
      UUID dimensionId,
      String dimensionKey,
      String metricCode,
      String currency,
      BigDecimal value,
      BigDecimal quantity,
      String textValue,
      OffsetDateTime sourceObservedAt,
      LocalDate windowFrom,
      LocalDate windowTo,
      String sourceService,
      String sourceReference) {}

  private ReportingProductPerformanceModels.ProductPerformanceRefreshResult refreshInventory(
      UUID tenantId, String bearer, Catalog catalog) {
    var snapshots = new LinkedHashMap<String, Snapshot>();
    int fetched = 0;
    int unmatched = 0;
    boolean truncated = false;

    for (int page = 0; page < ReportingProductPerformancePort.MAX_PAGES; page++) {
      var pageData = warehouse.balances(page, bearer);
      if (pageData == null || pageData.rows() == null || pageData.rows().isEmpty()) break;
      for (var row : pageData.rows()) {
        fetched++;
        if (row.skuId() == null) continue;
        var sku = catalog.skuById().get(row.skuId());
        if (sku == null) {
          unmatched++;
          continue;
        }
        put(
            snapshots,
            new Snapshot(
                "SKU",
                sku.id(),
                sku.code(),
                ReportingProductPerformanceCatalog.PRODUCT_ON_HAND_QUANTITY,
                null,
                null,
                row.onHand(),
                "",
                null,
                null,
                null,
                SOURCE_INVENTORY,
                sku.id() + ":on-hand"));
        put(
            snapshots,
            new Snapshot(
                "SKU",
                sku.id(),
                sku.code(),
                ReportingProductPerformanceCatalog.PRODUCT_RESERVED_QUANTITY,
                null,
                null,
                row.reserved(),
                "",
                null,
                null,
                null,
                SOURCE_INVENTORY,
                sku.id() + ":reserved"));
        put(
            snapshots,
            new Snapshot(
                "SKU",
                sku.id(),
                sku.code(),
                ReportingProductPerformanceCatalog.PRODUCT_AVAILABLE_QUANTITY,
                null,
                null,
                row.available(),
                "",
                null,
                null,
                null,
                SOURCE_INVENTORY,
                sku.id() + ":available"));
      }
      if (pageData.rows().size() < ReportingProductPerformancePort.PAGE_SIZE) break;
      if (page >= ReportingProductPerformancePort.MAX_PAGES - 1) truncated = true;
    }

    var projected =
        tenants.run(
            tenantId,
            () -> {
              deleteSnapshots(tenantId, SOURCE_INVENTORY, null, null);
              var count = 0;
              for (var snapshot : snapshots.values()) {
                insertSnapshot(tenantId, snapshot);
                count++;
              }
              return count;
            });

    var note =
        (truncated ? "⚠️ 库存余额只翻到 " + ReportingProductPerformancePort.MAX_PAGES + " 页为止。 " : "")
            + (unmatched > 0 ? "⚠️ 有 " + unmatched + " 条库存余额的 SKU 不在商品档案里。" : "")
            + "已投影库存快照（**时点**，不是日粒度）；🔴 warehouse 侧没有成本列 ⇒ 库存金额算不出来。";
    return new ReportingProductPerformanceModels.ProductPerformanceRefreshResult(
        "warehouse-inventory", fetched, projected, truncated ? "TRUNCATED" : "SUCCEEDED", note);
  }

  private ReportingProductPerformanceModels.ProductPerformanceRefreshResult refreshAging(
      UUID tenantId, String bearer, Catalog catalog) {
    // 每个 SKU 只留**最长库龄**那一行（一个 SKU 可能有多个批次 / 库位）。
    var longest = new LinkedHashMap<UUID, ReportingProductPerformancePort.AgingRow>();
    int fetched = 0;
    int unmatched = 0;
    boolean truncated = false;

    for (int page = 0; page < ReportingProductPerformancePort.MAX_PAGES; page++) {
      var pageData = port.agingRows(page, bearer);
      if (pageData == null || pageData.rows() == null || pageData.rows().isEmpty()) break;
      for (var row : pageData.rows()) {
        fetched++;
        if (row.skuId() == null || row.agingDays() == null) continue;
        var sku = catalog.skuById().get(row.skuId());
        if (sku == null) {
          unmatched++;
          continue;
        }
        var current = longest.get(row.skuId());
        if (current == null || row.agingDays() > current.agingDays()) longest.put(row.skuId(), row);
      }
      if (pageData.rows().size() < ReportingProductPerformancePort.PAGE_SIZE) break;
      if (page >= ReportingProductPerformancePort.MAX_PAGES - 1) truncated = true;
    }

    var snapshots = new LinkedHashMap<String, Snapshot>();
    for (var entry : longest.entrySet()) {
      var sku = catalog.skuById().get(entry.getKey());
      var row = entry.getValue();
      put(
          snapshots,
          new Snapshot(
              "SKU",
              sku.id(),
              sku.code(),
              ReportingProductPerformanceCatalog.PRODUCT_AGING_DAYS,
              null,
              BigDecimal.valueOf(row.agingDays()),
              null,
              row.agingBucket() == null ? "" : row.agingBucket(),
              null,
              null,
              null,
              SOURCE_AGING,
              sku.id() + ":aging"));
    }

    var projected =
        tenants.run(
            tenantId,
            () -> {
              deleteSnapshots(tenantId, SOURCE_AGING, null, null);
              var count = 0;
              for (var snapshot : snapshots.values()) {
                insertSnapshot(tenantId, snapshot);
                count++;
              }
              return count;
            });

    var note =
        (truncated ? "⚠️ 库龄清单只翻到 " + ReportingProductPerformancePort.MAX_PAGES + " 页为止。 " : "")
            + (unmatched > 0 ? "⚠️ 有 " + unmatched + " 条库龄行的 SKU 不在商品档案里。" : "")
            + "已投影库龄快照（**时点**）：每个 SKU 取最长库龄那一行，档位随行保留。";
    return new ReportingProductPerformanceModels.ProductPerformanceRefreshResult(
        "warehouse-aging", fetched, projected, truncated ? "TRUNCATED" : "SUCCEEDED", note);
  }

  // ==========================================================================
  // 广告表现（channel 14.3.1.1 活动报表 + 14.1.1.2 活动 ↔ Listing 绑定）
  // ==========================================================================

  private ReportingProductPerformanceModels.ProductPerformanceRefreshResult refreshAds(
      UUID tenantId,
      String bearer,
      LocalDate from,
      LocalDate to,
      LocalDate baselineFrom,
      LocalDate baselineTo,
      Catalog catalog) {
    var snapshots = new LinkedHashMap<String, Snapshot>();
    var campaignListings = new LinkedHashMap<String, List<UUID>>();
    var campaignLabels = new LinkedHashMap<String, String>();
    int fetched = 0;
    int productFetches = 0;
    boolean truncated = false;
    boolean bindingsTruncated = false;

    // ⚠️ **两个窗口各拉一次**：上游的活动报表是**窗口聚合**（不是日粒度），
    //   所以当期窗口与基线窗口是两份互不相同的聚合。
    var windows = new LinkedHashMap<String, LocalDate[]>();
    windows.put("current", new LocalDate[] {from, to});
    windows.put("baseline", new LocalDate[] {baselineFrom, baselineTo});

    for (var window : windows.entrySet()) {
      var range = window.getValue();
      for (int page = 0; page < ReportingProductPerformancePort.MAX_PAGES; page++) {
        var pageData = port.adCampaignRows(range[0], range[1], page, bearer);
        if (pageData == null || pageData.items() == null || pageData.items().isEmpty()) break;
        for (var row : pageData.items()) {
          fetched++;
          if (row.dimensionKey() == null || row.dimensionKey().isBlank()) continue;
          campaignLabels.putIfAbsent(row.dimensionKey(), row.dimensionLabel() == null ? "" : row.dimensionLabel());

          // 活动 ↔ Listing 绑定（每个活动取一次）。
          if (!campaignListings.containsKey(row.dimensionKey())) {
            if (productFetches >= ReportingProductPerformancePort.MAX_CAMPAIGN_PRODUCT_FETCHES) {
              bindingsTruncated = true;
              campaignListings.put(row.dimensionKey(), List.of());
              continue;
            }
            productFetches++;
            var bindings = new ArrayList<UUID>();
            for (int productPage = 0;
                productPage < ReportingProductPerformancePort.MAX_PAGES;
                productPage++) {
              var products = port.adCampaignProducts(row.dimensionKey(), productPage, bearer);
              if (products == null || products.items() == null || products.items().isEmpty()) break;
              for (var product : products.items()) {
                if (product.listingId() == null) continue;
                if (!catalog.listingById().containsKey(product.listingId())) continue;
                if (!bindings.contains(product.listingId())) bindings.add(product.listingId());
              }
              if (products.items().size() < ReportingProductPerformancePort.PAGE_SIZE) break;
            }
            campaignListings.put(row.dimensionKey(), bindings);
          }

          var listings = campaignListings.get(row.dimensionKey());
          if (listings == null || listings.isEmpty()) continue;
          var currency = row.currency() == null || row.currency().isBlank() ? null : row.currency();
          for (var listingId : listings) {
            var listing = catalog.listingById().get(listingId);
            adSnapshot(
                snapshots,
                listing,
                ReportingProductPerformanceCatalog.PRODUCT_AD_SPEND,
                currency,
                row.spend(),
                null,
                row.dimensionKey(),
                range[0],
                range[1]);
            adSnapshot(
                snapshots,
                listing,
                ReportingProductPerformanceCatalog.PRODUCT_AD_IMPRESSIONS,
                null,
                row.impressions(),
                null,
                row.dimensionKey(),
                range[0],
                range[1]);
            adSnapshot(
                snapshots,
                listing,
                ReportingProductPerformanceCatalog.PRODUCT_AD_CLICKS,
                null,
                row.clicks(),
                null,
                row.dimensionKey(),
                range[0],
                range[1]);
            adSnapshot(
                snapshots,
                listing,
                ReportingProductPerformanceCatalog.PRODUCT_AD_ORDERS,
                null,
                row.orders(),
                null,
                row.dimensionKey(),
                range[0],
                range[1]);
            adSnapshot(
                snapshots,
                listing,
                ReportingProductPerformanceCatalog.PRODUCT_AD_SALES_AMOUNT,
                currency,
                row.sales(),
                null,
                row.dimensionKey(),
                range[0],
                range[1]);
          }
        }
        if (pageData.items().size() < ReportingProductPerformancePort.PAGE_SIZE) break;
        if (page >= ReportingProductPerformancePort.MAX_PAGES - 1) truncated = true;
      }
    }

    var projected =
        tenants.run(
            tenantId,
            () -> {
              deleteSnapshots(tenantId, SOURCE_AD, from, to);
              deleteSnapshots(tenantId, SOURCE_AD, baselineFrom, baselineTo);
              var count = 0;
              for (var snapshot : snapshots.values()) {
                insertSnapshot(tenantId, snapshot);
                count++;
              }
              return count;
            });

    var note =
        (truncated ? "⚠️ 广告活动报表只翻到 " + ReportingProductPerformancePort.MAX_PAGES + " 页为止。 " : "")
            + (bindingsTruncated
                ? "⚠️ 广告活动的投放商品最多取 "
                    + ReportingProductPerformancePort.MAX_CAMPAIGN_PRODUCT_FETCHES
                    + " 个（活动 ↔ Listing 绑定逐活动取），触到上限如实报已截断 —— "
                    + "没取到绑定的活动，其花费**不摊给任何商品**。"
                : "")
            + "已按窗口投影广告活动数据（**活动级**）。🔴 channel 未交付商品维度广告报表（14.3.1.3）"
            + "与归因（14.3.2.1）⇒ 本模块**不提供** SKU/SPU/Listing 粒度的 ACOS / ROAS。";
    return new ReportingProductPerformanceModels.ProductPerformanceRefreshResult(
        "channel-ad-campaigns", fetched, projected, truncated ? "TRUNCATED" : "SUCCEEDED", note);
  }

  /** 一条广告快照（值 + 活动 + 窗口）。 */
  private void adSnapshot(
      Map<String, Snapshot> snapshots,
      ListingDim listing,
      String metricCode,
      String currency,
      BigDecimal value,
      BigDecimal quantity,
      String campaignId,
      LocalDate windowFrom,
      LocalDate windowTo) {
    put(
        snapshots,
        new Snapshot(
            "LISTING",
            listing.id(),
            listing.key(),
            metricCode,
            currency,
            value,
            quantity,
            campaignId,
            null,
            windowFrom,
            windowTo,
            SOURCE_AD,
            campaignId + ":" + metricCode + ":" + (currency == null ? "" : currency)));
  }

  // ==========================================================================
  // 竞品 / 跟卖价格（channel 4.5.2.1）—— **参考指标，时点快照**
  // ==========================================================================

  private ReportingProductPerformanceModels.ProductPerformanceRefreshResult refreshCompetitors(
      UUID tenantId,
      String bearer,
      LocalDate from,
      LocalDate to,
      Catalog catalog) {
    var snapshots = new LinkedHashMap<String, Snapshot>();
    int fetched = 0;
    int unmatched = 0;
    boolean truncated = false;

    for (int page = 0; page < ReportingProductPerformancePort.MAX_PAGES; page++) {
      var pageData = port.hijackerRecords(null, from, to, page, bearer);
      if (pageData == null || pageData.items() == null || pageData.items().isEmpty()) break;
      for (var row : pageData.items()) {
        fetched++;
        if (row.listingId() == null || row.recordId() == null) continue;
        var listing = catalog.listingById().get(row.listingId());
        if (listing == null) {
          unmatched++;
          continue;
        }
        var currency = row.currency() == null || row.currency().isBlank() ? null : row.currency();
        var text =
            (row.hijackerSellerId() == null ? "" : row.hijackerSellerId())
                + "|"
                + (row.hijackerShopName() == null ? "" : row.hijackerShopName())
                + "|"
                + (row.hijackerKind() == null ? "" : row.hijackerKind());
        if (currency != null && row.hijackerPrice() != null) {
          put(
              snapshots,
              new Snapshot(
                  "LISTING",
                  listing.id(),
                  listing.key(),
                  ReportingProductPerformanceCatalog.PRODUCT_HIJACKER_PRICE,
                  currency,
                  row.hijackerPrice(),
                  null,
                  text,
                  row.observedAt(),
                  null,
                  null,
                  SOURCE_HIJACKER,
                  row.recordId() + ":price"));
        }
        if (currency != null && row.referencePrice() != null) {
          put(
              snapshots,
              new Snapshot(
                  "LISTING",
                  listing.id(),
                  listing.key(),
                  ReportingProductPerformanceCatalog.PRODUCT_REFERENCE_PRICE,
                  currency,
                  row.referencePrice(),
                  null,
                  text,
                  row.observedAt(),
                  null,
                  null,
                  SOURCE_HIJACKER,
                  row.recordId() + ":reference"));
        }
      }
      if (pageData.items().size() < ReportingProductPerformancePort.PAGE_SIZE) break;
      if (page >= ReportingProductPerformancePort.MAX_PAGES - 1) truncated = true;
    }

    var projected =
        tenants.run(
            tenantId,
            () -> {
              var count = 0;
              for (var snapshot : snapshots.values()) {
                insertSnapshot(tenantId, snapshot);
                count++;
              }
              return count;
            });

    var note =
        (truncated ? "⚠️ 跟卖记录只翻到 " + ReportingProductPerformancePort.MAX_PAGES + " 页为止。 " : "")
            + (unmatched > 0 ? "⚠️ 有 " + unmatched + " 条跟卖记录的商品不在档案里。" : "")
            + "已投影跟卖价格与自家参考售价（**参考指标**：竞品数据不进自有商品模型）。";
    return new ReportingProductPerformanceModels.ProductPerformanceRefreshResult(
        "channel-listing-hijackers", fetched, projected, truncated ? "TRUNCATED" : "SUCCEEDED", note);
  }

  // ==========================================================================
  // 落库
  // ==========================================================================

  /** 事实聚合键。 */
  private static String factKey(Fact fact) {
    return fact.dimensionType()
        + "|"
        + fact.dimensionId()
        + "|"
        + fact.metricCode()
        + "|"
        + fact.bucketDate()
        + "|"
        + (fact.currency() == null ? "" : fact.currency())
        + "|"
        + fact.sourceService();
  }

  private void put(Map<String, Fact> facts, Fact fact) {
    facts.putIfAbsent(factKey(fact), fact);
  }

  private void put(Map<String, Snapshot> snapshots, Snapshot snapshot) {
    snapshots.putIfAbsent(
        snapshot.dimensionType()
            + "|"
            + snapshot.dimensionId()
            + "|"
            + snapshot.metricCode()
            + "|"
            + (snapshot.currency() == null ? "" : snapshot.currency())
            + "|"
            + snapshot.sourceService()
            + "|"
            + snapshot.sourceReference()
            + "|"
            + (snapshot.windowFrom() == null ? "" : snapshot.windowFrom()),
        snapshot);
  }

  /** 删掉某来源在窗口内的旧事实（**只删自己那一份**）。 */
  private void deleteFacts(UUID tenantId, String sourceService, LocalDate from, LocalDate to) {
    sql.execute(
        """
        delete from reporting_product_performance_fact
         where tenant_id = ? and source_service = ? and bucket_date between ? and ?
        """,
        tenantId,
        sourceService,
        from,
        to);
  }

  /** 删掉某来源的旧快照（窗口为空 = 全删该来源）。 */
  private void deleteSnapshots(UUID tenantId, String sourceService, LocalDate from, LocalDate to) {
    if (from == null || to == null) {
      sql.execute(
          "delete from reporting_product_performance_snapshot where tenant_id = ? and source_service = ?",
          tenantId,
          sourceService);
      return;
    }
    sql.execute(
        """
        delete from reporting_product_performance_snapshot
         where tenant_id = ? and source_service = ? and window_from = ? and window_to = ?
        """,
        tenantId,
        sourceService,
        from,
        to);
  }

  /** 落一条事实。 */
  private void insertFact(UUID tenantId, Fact fact, ZoneId timezone) {
    sql.execute(
        """
        insert into reporting_product_performance_fact
          (id,tenant_id,dimension_type,dimension_id,dimension_key,metric_code,bucket_date,
           currency,value,quantity,timezone,source_service,source_reference,updated_at)
        values (?,?,?,?,?,?,?,?,?,?,?,?,?,now())
        on conflict (tenant_id, dimension_type, dimension_id, metric_code, bucket_date, coalesce(currency,''),
                     source_service, source_reference)
        do update set value = excluded.value,
                      quantity = excluded.quantity,
                      timezone = excluded.timezone,
                      updated_at = now()
        """,
        Ids.next(),
        tenantId,
        fact.dimensionType(),
        fact.dimensionId(),
        fact.dimensionKey(),
        fact.metricCode(),
        fact.bucketDate(),
        fact.currency(),
        fact.value() == null ? BigDecimal.ZERO : fact.value(),
        fact.quantity() == null ? BigDecimal.ZERO : fact.quantity(),
        timezone.getId(),
        fact.sourceService(),
        fact.sourceReference());
  }

  /** 落一条快照。 */
  private void insertSnapshot(UUID tenantId, Snapshot snapshot) {
    sql.execute(
        """
        insert into reporting_product_performance_snapshot
          (id,tenant_id,dimension_type,dimension_id,dimension_key,metric_code,currency,value,quantity,
           text_value,window_from,window_to,observed_at,source_observed_at,timezone,
           source_service,source_reference,updated_at)
        values (?,?,?,?,?,?,?,?,?,?,?,?,now(),?::timestamptz,?,?,?,now())
        on conflict (tenant_id, dimension_type, dimension_id, metric_code, coalesce(currency,''),
                     coalesce(window_from, date '0001-01-01'), source_service, source_reference)
        do update set value = excluded.value,
                      quantity = excluded.quantity,
                      text_value = excluded.text_value,
                      source_observed_at = excluded.source_observed_at,
                      updated_at = now()
        """,
        Ids.next(),
        tenantId,
        snapshot.dimensionType(),
        snapshot.dimensionId(),
        snapshot.dimensionKey(),
        snapshot.metricCode(),
        snapshot.currency(),
        snapshot.value() == null ? BigDecimal.ZERO : snapshot.value(),
        snapshot.quantity() == null ? BigDecimal.ZERO : snapshot.quantity(),
        snapshot.textValue() == null ? "" : snapshot.textValue(),
        snapshot.windowFrom(),
        snapshot.windowTo(),
        snapshot.sourceObservedAt(),
        "UTC",
        snapshot.sourceService(),
        snapshot.sourceReference());
  }

  // ==========================================================================
  // 口径播种
  // ==========================================================================

  /**
   * 确保该租户有标准口径（**幂等**）。
   *
   * <p>⚠️ **先播种 13.2.2.2 的商品维度口径**：本模块的「毛利额」引用了
   * 「销售额」—— 它的定义登记在 13.2.2.2 的口径表里（口径唯一，不重复登记），
   * DB 触发器会校验引用 ⇒ 兄弟口径没播种时，本模块的口径**登记不进去**。
   */
  void seedDefinitions(UUID tenantId) {
    tenants.run(
        tenantId,
        () -> {
          productSales.seedDefinitions(tenantId);
          var exists =
              sql.fetch(
                  "select 1 from reporting_product_performance_metric_definition where tenant_id = ? limit 1",
                  tenantId);
          if (!exists.isEmpty()) return null;
          for (var definition : ReportingProductPerformanceCatalog.STANDARD) {
            sql.execute(
                """
                insert into reporting_product_performance_metric_definition
                  (id,tenant_id,metric_code,display_name,unit,aggregation,numerator_metric_code,
                   denominator_metric_code,currency_scope,currency_aware,available,owner_module,
                   description,availability_note,enabled,created_at,updated_at)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,true,now(),now())
                on conflict (tenant_id, metric_code) do nothing
                """,
                Ids.next(),
                tenantId,
                definition.metricCode(),
                definition.displayName(),
                definition.unit().name(),
                definition.aggregation().name(),
                definition.numeratorMetricCode(),
                definition.denominatorMetricCode(),
                definition.currencyScope().name(),
                definition.currencyAware(),
                definition.available(),
                definition.ownerModule(),
                definition.description(),
                definition.availabilityNote());
          }
          return null;
        });
  }
}
