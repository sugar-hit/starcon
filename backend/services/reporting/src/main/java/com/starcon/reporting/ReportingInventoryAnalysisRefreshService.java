package com.starcon.reporting;

import com.starcon.tenancy.Ids;
import com.starcon.tenancy.TenantTransactions;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

/**
 * 13.5.1.1 库存分析的**投影摄取**（蓝图「输入」句：库存数据（7）；销售数据；盘点历史）。
 *
 * <p>本服务把**上游算好的**事实拉成本服务的投影表：
 *
 * <table>
 *   <caption>四路上游</caption>
 *   <tr><th>上游</th><th>端点</th><th>落到哪张表</th></tr>
 *   <tr><td>7 库存（warehouse）</td><td>{@code /warehouse/inventory/overview}</td><td>{@code reporting_inventory_analysis_position}</td></tr>
 *   <tr><td>7 库存（warehouse）</td><td>{@code /warehouse/inventory/aging/buckets} + {@code /slow-moving}</td><td>{@code reporting_inventory_analysis_aging_fact}</td></tr>
 *   <tr><td>7 库存（warehouse）</td><td>{@code /warehouse/inventory/stocktakes}</td><td>{@code reporting_inventory_analysis_stocktake_fact}</td></tr>
 *   <tr><td>9 订单（trade）</td><td>{@code /trade/sales-orders/list} + {@code /detail/{id}}</td><td>{@code reporting_inventory_analysis_sales_fact}</td></tr>
 * </table>
 *
 * <p>⚠️ **本服务不重算任何上游口径**：库龄与呆滞的判定在 warehouse、成本单价在 catalog、
 * 销量在 trade —— 本服务只做**搬运与聚合**。重算就会出现第二个真相。
 *
 * <p>⚠️ **跨服务调用一律走只读端口并转发调用方自己的 bearer**：不传租户号，
 * 上游侧仍按已认证成员关系解析租户并强制 RLS。
 *
 * <p>⚠️ **幂等**：写入走 {@code on conflict ... do update}（幂等键见 V403 的唯一索引），
 * 同一批上游事实重放**不会翻倍**。
 *
 * <p>⚠️ **上游读不到就如实报 FAILED**：绝不返回空集合冒充「上游没有数据」——
 * 一个空集合在报表里长成「库存金额 0」，而真相是「这一格没算出来」。
 */
@Service
public class ReportingInventoryAnalysisRefreshService {

  /** 一次刷新里最多取多少张订单的**明细行**（N+1 的硬上限）。 */
  static final int MAX_DETAIL_FETCHES = 200;

  /** 金额刻度（对齐 {@code numeric(24,4)}）。 */
  static final int MONEY_SCALE = 4;

  /** 数量刻度（对齐 {@code numeric(19,6)}）。 */
  static final int QUANTITY_SCALE = 6;

  private final DSLContext sql;
  private final TenantTransactions tenants;
  private final ReportingInventoryAnalysisPort inventoryPort;

  public ReportingInventoryAnalysisRefreshService(
      DSLContext sql, TenantTransactions tenants, ReportingInventoryAnalysisPort inventoryPort) {
    this.sql = sql;
    this.tenants = tenants;
    this.inventoryPort = inventoryPort;
  }

  // ==========================================================================
  // 入口
  // ==========================================================================

  /**
   * 刷新库存分析投影。
   *
   * @param from 销售出库的窗口起（库存头寸 / 库龄 / 盘点不按窗口切）
   * @param to 销售出库的窗口止
   */
  public ReportingInventoryAnalysisModels.InventoryAnalysisRefreshResult refresh(
      UUID tenantId, String bearer, LocalDate from, LocalDate to) {
    var summaries = new ArrayList<ReportingInventoryAnalysisModels.InventoryAnalysisRefreshSummary>();
    var truncated = false;

    // ---- 上游引用（拉一次，多路复用）----
    var catalogSkus = loadCatalogSkus(bearer);
    var shopBySkuId = loadShopBySkuId(bearer, catalogSkus);
    var costs = loadCosts(bearer);

    // ---- ① 库存头寸 ----
    try {
      var facts = pullPositions(bearer, catalogSkus, shopBySkuId, costs);
      var written = writePositions(tenantId, facts);
      summaries.add(
          summary(
              "WAREHOUSE_BALANCE",
              facts.size(),
              written,
              "OK",
              "库存头寸（数量 + 成本口径金额 + 缺货/超储标记）已按「今天」拍成快照。"));
    } catch (RuntimeException e) {
      summaries.add(summary("WAREHOUSE_BALANCE", 0, 0, "FAILED", "上游读不到：" + e.getMessage()));
    }

    // ---- ② 销售出库（成本口径）----
    try {
      var outcome = pullSales(bearer, from, to, catalogSkus, shopBySkuId, costs);
      var written = writeSales(tenantId, outcome.facts());
      truncated = truncated || outcome.truncated();
      var note =
          "已提交（documentStatus=SUBMITTED）的销售订单明细，销量 × catalog 单位成本（**成本口径**，不用售价）。";
      if (outcome.drafts() > 0) {
        note += " ⚠️ 跳过 " + outcome.drafts() + " 张草稿单（草稿不是销量）。";
      }
      if (outcome.unattributable() > 0) {
        note +=
            " ⚠️ "
                + outcome.unattributable()
                + " 行销量**挂不到 SKU 档案或没有维护成本**，未计入任何 SKU（挂上去就是编造）。";
      }
      if (outcome.truncated()) {
        note += " ⚠️ 订单明细取到 " + MAX_DETAIL_FETCHES + " 张为止，超出的未投影。";
      }
      summaries.add(summary("TRADE_SALES", outcome.facts().size(), written, "OK", note));
    } catch (RuntimeException e) {
      summaries.add(summary("TRADE_SALES", 0, 0, "FAILED", "上游读不到：" + e.getMessage()));
    }

    // ---- ③ 库龄与呆滞 ----
    try {
      var facts = pullAging(bearer);
      var written = writeAging(tenantId, facts);
      summaries.add(
          summary(
              "WAREHOUSE_AGING",
              facts.size(),
              written,
              "OK",
              "库龄分桶与呆滞汇总（**判定归 warehouse 7.6.1.1**，本模块只搬运，不重算）。"));
    } catch (RuntimeException e) {
      summaries.add(summary("WAREHOUSE_AGING", 0, 0, "FAILED", "上游读不到：" + e.getMessage()));
    }

    // ---- ④ 盘点历史 ----
    try {
      var facts = pullStocktakes(bearer);
      var written = writeStocktakes(tenantId, facts);
      summaries.add(
          summary(
              "WAREHOUSE_STOCKTAKE",
              facts.size(),
              written,
              "OK",
              "盘点单（账面 / 已盘 / 差异三列计数，**差异不直接改余额** —— 那归 warehouse 的调整单审批链）。"));
    } catch (RuntimeException e) {
      summaries.add(summary("WAREHOUSE_STOCKTAKE", 0, 0, "FAILED", "上游读不到：" + e.getMessage()));
    }

    var totalWritten = summaries.stream().mapToInt(s -> s.written()).sum();
    var note =
        "库存分析是**投影**：与业务系统冲突时以业务系统为准（warehouse / trade / catalog / channel）。";
    if (truncated) {
      note += " ⚠️ 本次刷新**触到了取数上限**（订单明细 " + MAX_DETAIL_FETCHES + " 张），超出的部分未投影。";
    }
    return new ReportingInventoryAnalysisModels.InventoryAnalysisRefreshResult(
        summaries, totalWritten, truncated, note);
  }

  private static ReportingInventoryAnalysisModels.InventoryAnalysisRefreshSummary summary(
      String source, int factCount, int written, String status, String note) {
    return new ReportingInventoryAnalysisModels.InventoryAnalysisRefreshSummary(
        source, factCount, written, status, note);
  }

  // ==========================================================================
  // 上游引用
  // ==========================================================================

  /** catalog 的 SKU 档案（id → 类目 / 编码）。 */
  private record CatalogSku(UUID id, String code, String category) {}

  /** SKU → 店铺（**只在唯一映射时才有值**）。 */
  private record ShopRef(String shopCode, String shopName) {}

  /** catalog 的成本构成（**单位**成本：采购 + 包装 + 其它直接）。 */
  private record UnitCost(
      BigDecimal unitCost, String currency, String costMethod, boolean maintained) {}

  /** catalog SKU 档案（按编码索引）。 */
  private Map<String, CatalogSku> loadCatalogSkus(String bearer) {
    var map = new HashMap<String, CatalogSku>();
    for (int page = 0; page < ReportingInventoryAnalysisPort.MAX_PAGES; page++) {
      var paged = inventoryPort.skus(page, bearer);
      if (paged == null || paged.items() == null || paged.items().isEmpty()) break;
      for (var row : paged.items()) {
        if (row == null || row.code() == null) continue;
        var code = row.code().trim();
        if (code.isEmpty()) continue;
        map.putIfAbsent(
            code,
            new CatalogSku(
                row.id(), code, row.category() == null ? "" : row.category().trim()));
      }
      if (paged.items().size() < ReportingInventoryAnalysisPort.PAGE_SIZE) break;
    }
    return map;
  }

  /**
   * SKU → 店铺（**唯一映射才有值**）。
   *
   * <p>⚠️ 同一款内部 SKU 挂在多个店铺的 Listing 上是常事 ⇒ 它的库存**无法唯一归属**到某个店铺。
   * 本模块**如实留空**（页面上归到「未归属店铺」），**不摊给任何一个店铺** ——
   * 摊下去就是造假（那会把整仓的货说成是某一家店的）。
   */
  private Map<UUID, ShopRef> loadShopBySkuId(String bearer, Map<String, CatalogSku> catalogSkus) {
    var codeById = new HashMap<UUID, String>();
    for (var sku : catalogSkus.values()) {
      if (sku.id() != null) codeById.putIfAbsent(sku.id(), sku.code());
    }
    var shopById = new HashMap<UUID, ShopRef>();
    var ambiguous = new HashSet<UUID>();
    for (int page = 0; page < ReportingInventoryAnalysisPort.MAX_PAGES; page++) {
      var paged = inventoryPort.listings(page, bearer);
      if (paged == null || paged.items() == null || paged.items().isEmpty()) break;
      for (var row : paged.items()) {
        if (row == null || row.internalSkuIds() == null) continue;
        var shopCode = trim(row.shopCode());
        if (shopCode == null) continue;
        for (var skuId : row.internalSkuIds()) {
          if (skuId == null) continue;
          var existing = shopById.get(skuId);
          if (existing == null) {
            shopById.put(skuId, new ShopRef(shopCode, trim(row.shopName()) == null ? "" : row.shopName().trim()));
          } else if (!existing.shopCode().equals(shopCode)) {
            // 🔴 **多店铺共用**：无法唯一归属 ⇒ 明确标成「歧义」，不归属给任何一个。
            ambiguous.add(skuId);
          }
        }
      }
      if (paged.items().size() < ReportingInventoryAnalysisPort.PAGE_SIZE) break;
    }
    for (var skuId : ambiguous) shopById.remove(skuId);
    return shopById;
  }

  /** catalog 的成本构成（按 SKU 编码索引）。 */
  private Map<String, UnitCost> loadCosts(String bearer) {
    var map = new HashMap<String, UnitCost>();
    for (int page = 0; page < ReportingInventoryAnalysisPort.MAX_PAGES; page++) {
      var paged = inventoryPort.costs(page, bearer);
      if (paged == null || paged.items() == null || paged.items().isEmpty()) break;
      for (var row : paged.items()) {
        if (row == null || row.skuCode() == null) continue;
        var code = row.skuCode().trim();
        if (code.isEmpty()) continue;
        map.putIfAbsent(code, toUnitCost(row));
      }
      if (paged.items().size() < ReportingInventoryAnalysisPort.PAGE_SIZE) break;
    }
    return map;
  }

  /**
   * 上游成本构成行 → 单位成本。
   *
   * <p>⚠️ **三个分项都是「单位」成本**（上游 {@code purchaseCost} 在成本分摊里被直接
   * {@code purchaseCost × quantity} 使用，可证它是单价不是总额）。
   *
   * <p>⚠️ **没维护（{@code maintained=false}）或币种缺失 ⇒ 算不出来**（返回 null 的单价）：
   * 那种 SKU 的库存金额是「—」，**不是 0 元**。
   */
  private static UnitCost toUnitCost(ReportingInventoryAnalysisPort.CatalogCostRow row) {
    var currency = currency(row.currency());
    if (!row.maintained() || currency == null) {
      return new UnitCost(null, null, trim(row.costMethod()) == null ? "" : row.costMethod(), false);
    }
    var parts = new ArrayList<BigDecimal>(3);
    boolean any = false;
    for (var part : List.of(row.purchaseCost(), row.packagingCost(), row.otherDirectCost())) {
      if (part != null) {
        any = true;
        parts.add(part);
      }
    }
    if (!any) {
      // 🔴 三项皆空 ⇒ **算不出来**（不是「成本是 0」）。
      return new UnitCost(null, null, trim(row.costMethod()) == null ? "" : row.costMethod(), true);
    }
    var total = BigDecimal.ZERO;
    for (var part : parts) total = total.add(part);
    return new UnitCost(
        total.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP),
        currency,
        trim(row.costMethod()) == null ? "" : row.costMethod(),
        true);
  }

  // ==========================================================================
  // ① 库存头寸
  // ==========================================================================

  /** 一条待落库的库存头寸。 */
  private record PositionFact(
      LocalDate asOf,
      UUID skuId,
      String skuCode,
      String skuName,
      UUID warehouseId,
      String warehouseCode,
      String warehouseName,
      UUID legalEntityId,
      String legalEntityCode,
      String categoryCode,
      String shopCode,
      String shopName,
      BigDecimal onHand,
      BigDecimal reserved,
      BigDecimal available,
      BigDecimal maxOnHand,
      BigDecimal unitCost,
      String costCurrency,
      String costMethod,
      BigDecimal inventoryValue,
      boolean zeroStock,
      boolean overstock,
      boolean thresholdConfigured,
      String sourceReference) {}

  private List<PositionFact> pullPositions(
      String bearer,
      Map<String, CatalogSku> catalogSkus,
      Map<UUID, ShopRef> shopBySkuId,
      Map<String, UnitCost> costs) {
    var asOf = LocalDate.now(ZoneOffset.UTC);
    var facts = new ArrayList<PositionFact>();
    for (int page = 0; page < ReportingInventoryAnalysisPort.MAX_PAGES; page++) {
      var paged = inventoryPort.balances(page, bearer);
      if (paged == null || paged.rows() == null || paged.rows().isEmpty()) break;
      for (var row : paged.rows()) {
        if (row == null || row.skuCode() == null || row.warehouseId() == null) continue;
        var skuCode = row.skuCode().trim();
        if (skuCode.isEmpty()) continue;
        var catalog = catalogSkus.get(skuCode);
        var cost = costs.get(skuCode);
        var shop = catalog == null || catalog.id() == null ? null : shopBySkuId.get(catalog.id());
        var onHand = quantity(row.onHand());
        BigDecimal inventoryValue = null;
        if (cost != null && cost.unitCost() != null) {
          inventoryValue = onHand.multiply(cost.unitCost()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        facts.add(
            new PositionFact(
                asOf,
                row.skuId(),
                skuCode,
                trim(row.skuName()) == null ? "" : row.skuName().trim(),
                row.warehouseId(),
                trim(row.warehouseCode()) == null ? "" : row.warehouseCode().trim(),
                trim(row.warehouseName()) == null ? "" : row.warehouseName().trim(),
                row.legalEntityId(),
                trim(row.legalEntityCode()) == null ? "" : row.legalEntityCode().trim(),
                catalog == null ? "" : catalog.category(),
                shop == null ? "" : shop.shopCode(),
                shop == null ? "" : shop.shopName(),
                onHand,
                quantity(row.reserved()),
                quantity(row.available()),
                quantity(row.maxOnHand()),
                cost == null ? null : cost.unitCost(),
                cost == null ? null : cost.currency(),
                cost == null ? "" : cost.costMethod(),
                inventoryValue,
                row.zeroStock(),
                row.overstock(),
                row.thresholdConfigured(),
                "warehouse:inventory-overview:" + row.balanceId()));
      }
      if (paged.rows().size() < ReportingInventoryAnalysisPort.PAGE_SIZE) break;
    }
    return facts;
  }

  private int writePositions(UUID tenantId, List<PositionFact> facts) {
    if (facts.isEmpty()) return 0;
    return tenants.run(
        tenantId,
        () -> {
          var written = 0;
          for (var fact : facts) {
            sql.execute(
                """
                insert into reporting_inventory_analysis_position
                  (id,tenant_id,as_of,sku_id,sku_code,sku_name,
                   warehouse_id,warehouse_code,warehouse_name,
                   legal_entity_id,legal_entity_code,category_code,shop_code,shop_name,
                   on_hand,reserved,available,max_on_hand,
                   unit_cost,cost_currency,cost_method,inventory_value,
                   zero_stock,overstock,threshold_configured,
                   source_service,source_reference,updated_at)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now())
                on conflict (tenant_id,as_of,sku_id,warehouse_id)
                do update set sku_name = excluded.sku_name,
                              warehouse_code = excluded.warehouse_code,
                              warehouse_name = excluded.warehouse_name,
                              legal_entity_code = excluded.legal_entity_code,
                              category_code = excluded.category_code,
                              shop_code = excluded.shop_code,
                              shop_name = excluded.shop_name,
                              on_hand = excluded.on_hand,
                              reserved = excluded.reserved,
                              available = excluded.available,
                              max_on_hand = excluded.max_on_hand,
                              unit_cost = excluded.unit_cost,
                              cost_currency = excluded.cost_currency,
                              cost_method = excluded.cost_method,
                              inventory_value = excluded.inventory_value,
                              zero_stock = excluded.zero_stock,
                              overstock = excluded.overstock,
                              threshold_configured = excluded.threshold_configured,
                              source_reference = excluded.source_reference,
                              updated_at = now()
                """,
                Ids.next(),
                tenantId,
                fact.asOf(),
                fact.skuId(),
                fact.skuCode(),
                fact.skuName(),
                fact.warehouseId(),
                fact.warehouseCode(),
                fact.warehouseName(),
                fact.legalEntityId(),
                fact.legalEntityCode(),
                fact.categoryCode(),
                fact.shopCode(),
                fact.shopName(),
                fact.onHand(),
                fact.reserved(),
                fact.available(),
                fact.maxOnHand(),
                fact.unitCost(),
                fact.costCurrency(),
                fact.costMethod(),
                fact.inventoryValue(),
                fact.zeroStock(),
                fact.overstock(),
                fact.thresholdConfigured(),
                "warehouse",
                fact.sourceReference());
            written++;
          }
          return written;
        });
  }

  // ==========================================================================
  // ② 销售出库（成本口径）
  // ==========================================================================

  /** 一条待落库的销售出库事实。 */
  private record SalesFact(
      LocalDate soldOn,
      UUID skuId,
      String skuCode,
      String skuName,
      String categoryCode,
      String shopCode,
      String shopName,
      BigDecimal quantity,
      BigDecimal unitCost,
      String costCurrency,
      BigDecimal costAmount,
      String sourceReference) {}

  /** 一次销售拉取的结果（含「如实报出来」的计数）。 */
  private record SalesOutcome(
      List<SalesFact> facts, int drafts, int unattributable, boolean truncated) {}

  private SalesOutcome pullSales(
      String bearer,
      LocalDate from,
      LocalDate to,
      Map<String, CatalogSku> catalogSkus,
      Map<UUID, ShopRef> shopBySkuId,
      Map<String, UnitCost> costs) {
    var facts = new ArrayList<SalesFact>();
    var drafts = 0;
    var unattributable = 0;
    var truncated = false;
    var detailFetches = 0;

    for (int page = 0; page < ReportingInventoryAnalysisPort.MAX_PAGES; page++) {
      var paged = inventoryPort.tradeOrders(from, to, page, bearer);
      if (paged == null || paged.rows() == null || paged.rows().isEmpty()) break;
      for (var order : paged.rows()) {
        if (order == null || order.id() == null) continue;
        // ⚠️ **草稿单不是销量**：算进周转率的分子就是虚高。
        if ("DRAFT".equalsIgnoreCase(trim(order.documentStatus()))) {
          drafts++;
          continue;
        }
        var soldOn = date(order.submittedAt());
        if (soldOn == null) {
          unattributable++;
          continue;
        }
        if (soldOn.isBefore(from) || soldOn.isAfter(to)) continue;
        if (detailFetches >= MAX_DETAIL_FETCHES) {
          truncated = true;
          continue;
        }
        detailFetches++;
        var detail = inventoryPort.tradeOrderDetail(order.id(), bearer);
        if (detail == null || detail.lines() == null) {
          unattributable++;
          continue;
        }
        for (var line : detail.lines()) {
          if (line == null || line.skuCode() == null) continue;
          var skuCode = line.skuCode().trim();
          if (skuCode.isEmpty()) continue;
          var catalog = catalogSkus.get(skuCode);
          if (catalog == null || catalog.id() == null) {
            // 🔴 **挂不到 SKU 档案就不落**：落上去就是一个编出来的 SKU。
            unattributable++;
            continue;
          }
          var cost = costs.get(skuCode);
          var shop = shopBySkuId.get(catalog.id());
          BigDecimal unitCost = null;
          String costCurrency = null;
          BigDecimal costAmount = null;
          if (cost != null && cost.unitCost() != null) {
            unitCost = cost.unitCost();
            costCurrency = cost.currency();
            costAmount =
                quantity(line.quantity())
                    .multiply(unitCost)
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
          }
          facts.add(
              new SalesFact(
                  soldOn,
                  catalog.id(),
                  skuCode,
                  trim(line.skuName()) == null ? "" : line.skuName().trim(),
                  catalog.category(),
                  shop == null ? "" : shop.shopCode(),
                  shop == null ? "" : shop.shopName(),
                  quantity(line.quantity()),
                  unitCost,
                  costCurrency,
                  costAmount,
                  "trade:sales-order:" + order.id() + ":line:" + (line.id() == null ? line.lineNo() : line.id())));
        }
      }
      if (paged.rows().size() < ReportingInventoryAnalysisPort.PAGE_SIZE) break;
    }
    return new SalesOutcome(facts, drafts, unattributable, truncated);
  }

  private int writeSales(UUID tenantId, List<SalesFact> facts) {
    if (facts.isEmpty()) return 0;
    return tenants.run(
        tenantId,
        () -> {
          var written = 0;
          for (var fact : facts) {
            sql.execute(
                """
                insert into reporting_inventory_analysis_sales_fact
                  (id,tenant_id,sold_on,sku_id,sku_code,sku_name,
                   category_code,shop_code,shop_name,
                   quantity,unit_cost,cost_currency,cost_amount,
                   source_service,source_reference,updated_at)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now())
                on conflict (tenant_id,sold_on,sku_id,source_reference)
                do update set sku_name = excluded.sku_name,
                              category_code = excluded.category_code,
                              shop_code = excluded.shop_code,
                              shop_name = excluded.shop_name,
                              quantity = excluded.quantity,
                              unit_cost = excluded.unit_cost,
                              cost_currency = excluded.cost_currency,
                              cost_amount = excluded.cost_amount,
                              updated_at = now()
                """,
                Ids.next(),
                tenantId,
                fact.soldOn(),
                fact.skuId(),
                fact.skuCode(),
                fact.skuName(),
                fact.categoryCode(),
                fact.shopCode(),
                fact.shopName(),
                fact.quantity(),
                fact.unitCost(),
                fact.costCurrency(),
                fact.costAmount(),
                "trade",
                fact.sourceReference());
            written++;
          }
          return written;
        });
  }

  // ==========================================================================
  // ③ 库龄与呆滞
  // ==========================================================================

  /** 一条待落库的库龄 / 呆滞聚合。 */
  private record AgingFact(
      LocalDate asOf,
      String scopeType,
      String scopeKey,
      String scopeLabel,
      String metricType,
      String metricKey,
      String metricLabel,
      Integer lowerDays,
      Integer upperDays,
      long skuCount,
      long bucketCount,
      BigDecimal onHand,
      BigDecimal reserved,
      BigDecimal available,
      Integer oldestAgingDays,
      String sourceReference) {}

  private List<AgingFact> pullAging(String bearer) {
    var facts = new ArrayList<AgingFact>();
    // 全租户口径
    facts.addAll(pullAgingScope(bearer, null, "TENANT", "", "全租户"));
    // 逐仓库口径（**按仓库拆解**是蓝图点名要的维度）
    var warehouses = inventoryPort.warehouses(bearer);
    if (warehouses != null) {
      for (var warehouse : warehouses) {
        if (warehouse == null || warehouse.id() == null) continue;
        facts.addAll(
            pullAgingScope(
                bearer,
                warehouse.id(),
                "WAREHOUSE",
                warehouse.id().toString(),
                trim(warehouse.name()) == null ? trim(warehouse.code()) : warehouse.name()));
      }
    }
    return facts;
  }

  private List<AgingFact> pullAgingScope(
      String bearer, UUID warehouseId, String scopeType, String scopeKey, String scopeLabel) {
    var facts = new ArrayList<AgingFact>();
    var buckets = inventoryPort.agingBuckets(warehouseId, bearer);
    if (buckets == null || buckets.buckets() == null) return facts;
    var asOf = buckets.asOf() == null ? LocalDate.now(ZoneOffset.UTC) : buckets.asOf();
    for (var bucket : buckets.buckets()) {
      if (bucket == null || bucket.bucket() == null) continue;
      facts.add(
          new AgingFact(
              asOf,
              scopeType,
              scopeKey,
              scopeLabel,
              "AGING_BUCKET",
              bucket.bucket(),
              bucket.label() == null ? "" : bucket.label(),
              bucket.lowerDays(),
              bucket.upperDays(),
              bucket.skuCount(),
              bucket.bucketCount(),
              quantity(bucket.onHand()),
              quantity(bucket.reserved()),
              quantity(bucket.available()),
              null,
              "warehouse:inventory-aging-buckets:" + scopeKey));
    }

    var slow = inventoryPort.slowMoving(warehouseId, bearer);
    if (slow != null && slow.summary() != null) {
      var s = slow.summary();
      var obsolete = quantity(s.obsoleteAvailable());
      var slowMoving = quantity(s.slowMovingAvailable());
      facts.add(
          new AgingFact(
              asOf,
              scopeType,
              scopeKey,
              scopeLabel,
              "SLOW_MOVING",
              "OBSOLETE",
              "呆滞",
              null,
              null,
              s.obsoleteCount(),
              s.obsoleteCount(),
              BigDecimal.ZERO,
              BigDecimal.ZERO,
              obsolete,
              s.oldestAgingDays(),
              "warehouse:inventory-aging-slow-moving:" + scopeKey));
      facts.add(
          new AgingFact(
              asOf,
              scopeType,
              scopeKey,
              scopeLabel,
              "SLOW_MOVING",
              "SLOW_MOVING",
              "滞销",
              null,
              null,
              s.slowMovingCount(),
              s.slowMovingCount(),
              BigDecimal.ZERO,
              BigDecimal.ZERO,
              slowMoving,
              s.oldestAgingDays(),
              "warehouse:inventory-aging-slow-moving:" + scopeKey));
      facts.add(
          new AgingFact(
              asOf,
              scopeType,
              scopeKey,
              scopeLabel,
              "SLOW_MOVING",
              "OBSOLETE_AND_UP",
              "呆滞及以上（呆滞 + 滞销）",
              null,
              null,
              s.obsoleteCount() + s.slowMovingCount(),
              s.obsoleteCount() + s.slowMovingCount(),
              BigDecimal.ZERO,
              BigDecimal.ZERO,
              obsolete.add(slowMoving),
              s.oldestAgingDays(),
              "warehouse:inventory-aging-slow-moving:" + scopeKey));
    }
    return facts;
  }

  private int writeAging(UUID tenantId, List<AgingFact> facts) {
    if (facts.isEmpty()) return 0;
    return tenants.run(
        tenantId,
        () -> {
          var written = 0;
          for (var fact : facts) {
            sql.execute(
                """
                insert into reporting_inventory_analysis_aging_fact
                  (id,tenant_id,as_of,scope_type,scope_key,scope_label,
                   metric_type,metric_key,metric_label,lower_days,upper_days,
                   sku_count,bucket_count,on_hand,reserved,available,oldest_aging_days,
                   source_service,source_reference,updated_at)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now())
                on conflict (tenant_id,as_of,scope_type,scope_key,metric_type,metric_key)
                do update set scope_label = excluded.scope_label,
                              metric_label = excluded.metric_label,
                              lower_days = excluded.lower_days,
                              upper_days = excluded.upper_days,
                              sku_count = excluded.sku_count,
                              bucket_count = excluded.bucket_count,
                              on_hand = excluded.on_hand,
                              reserved = excluded.reserved,
                              available = excluded.available,
                              oldest_aging_days = excluded.oldest_aging_days,
                              updated_at = now()
                """,
                Ids.next(),
                tenantId,
                fact.asOf(),
                fact.scopeType(),
                fact.scopeKey(),
                fact.scopeLabel(),
                fact.metricType(),
                fact.metricKey(),
                fact.metricLabel(),
                fact.lowerDays(),
                fact.upperDays(),
                fact.skuCount(),
                fact.bucketCount(),
                fact.onHand(),
                fact.reserved(),
                fact.available(),
                fact.oldestAgingDays(),
                "warehouse",
                fact.sourceReference());
            written++;
          }
          return written;
        });
  }

  // ==========================================================================
  // ④ 盘点历史
  // ==========================================================================

  /** 一条待落库的盘点单。 */
  private record StocktakeFact(
      UUID stocktakeId,
      String code,
      String scopeType,
      String scopeLabel,
      String status,
      OffsetDateTime frozenAt,
      long lineCount,
      long countedLineCount,
      long varianceLineCount,
      BigDecimal bookTotal,
      String sourceReference) {}

  private List<StocktakeFact> pullStocktakes(String bearer) {
    var facts = new ArrayList<StocktakeFact>();
    for (int page = 0; page < ReportingInventoryAnalysisPort.MAX_PAGES; page++) {
      var paged = inventoryPort.stocktakes(page, bearer);
      if (paged == null || paged.sheets() == null || paged.sheets().isEmpty()) break;
      for (var sheet : paged.sheets()) {
        if (sheet == null || sheet.id() == null) continue;
        facts.add(
            new StocktakeFact(
                sheet.id(),
                trim(sheet.code()) == null ? "" : sheet.code().trim(),
                trim(sheet.scopeType()) == null ? "" : sheet.scopeType().trim(),
                trim(sheet.scopeLabel()) == null ? "" : sheet.scopeLabel().trim(),
                trim(sheet.status()) == null ? "" : sheet.status().trim(),
                sheet.frozenAt(),
                sheet.lineCount(),
                sheet.countedLineCount(),
                sheet.varianceLineCount(),
                quantity(sheet.bookTotal()),
                "warehouse:inventory-stocktake:" + sheet.id()));
      }
      if (paged.sheets().size() < ReportingInventoryAnalysisPort.PAGE_SIZE) break;
    }
    return facts;
  }

  private int writeStocktakes(UUID tenantId, List<StocktakeFact> facts) {
    if (facts.isEmpty()) return 0;
    return tenants.run(
        tenantId,
        () -> {
          var written = 0;
          for (var fact : facts) {
            sql.execute(
                """
                insert into reporting_inventory_analysis_stocktake_fact
                  (id,tenant_id,stocktake_id,code,scope_type,scope_label,status,frozen_at,
                   line_count,counted_line_count,variance_line_count,book_total,
                   source_service,source_reference,updated_at)
                values (?,?,?,?,?,?,?,?::timestamptz,?,?,?,?,?,?,now())
                on conflict (tenant_id,stocktake_id)
                do update set code = excluded.code,
                              scope_type = excluded.scope_type,
                              scope_label = excluded.scope_label,
                              status = excluded.status,
                              frozen_at = excluded.frozen_at,
                              line_count = excluded.line_count,
                              counted_line_count = excluded.counted_line_count,
                              variance_line_count = excluded.variance_line_count,
                              book_total = excluded.book_total,
                              updated_at = now()
                """,
                Ids.next(),
                tenantId,
                fact.stocktakeId(),
                fact.code(),
                fact.scopeType(),
                fact.scopeLabel(),
                fact.status(),
                fact.frozenAt(),
                fact.lineCount(),
                fact.countedLineCount(),
                fact.varianceLineCount(),
                fact.bookTotal(),
                "warehouse",
                fact.sourceReference());
            written++;
          }
          return written;
        });
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  /** 数量规范化：空 ⇒ 0；小数位对齐 {@code numeric(19,6)}。 */
  private static BigDecimal quantity(BigDecimal raw) {
    if (raw == null) return BigDecimal.ZERO.setScale(QUANTITY_SCALE);
    return raw.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
  }

  /** 币种规范化（空 / 非 ISO alpha-3 ⇒ null，即「这一行不算」）。 */
  private static String currency(String raw) {
    if (raw == null || raw.isBlank()) return null;
    var value = raw.trim().toUpperCase();
    return value.matches("^[A-Z]{3}$") ? value : null;
  }

  /** 上游时间 → 日期（按 UTC 截断）。 */
  private static LocalDate date(OffsetDateTime moment) {
    return moment == null ? null : moment.atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
  }

  /** 字符串去空白（空 ⇒ null）。 */
  private static String trim(String raw) {
    if (raw == null) return null;
    var value = raw.trim();
    return value.isEmpty() ? null : value;
  }
}
