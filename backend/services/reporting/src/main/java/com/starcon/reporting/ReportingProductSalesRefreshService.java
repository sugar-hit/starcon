package com.starcon.reporting;

import com.starcon.tenancy.Ids;
import com.starcon.tenancy.TenantTransactions;
import java.math.BigDecimal;
import java.time.LocalDate;
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
 * 13.2.2.2 SKU/SPU/Listing 的**拉取适配器**（上游授权只读 API → 商品维度投影事实）。
 *
 * <p>⚠️ <b>本类只写投影</b>（{@code reporting_product_dimension} /
 * {@code reporting_product_listing_sku} / {@code reporting_product_metric_fact} /
 * {@code reporting_product_drilldown_fact}），不产生任何业务事实 ——
 * 商品主数据仍然在 catalog，平台商品仍然在 channel，订单与明细仍然在 trade。
 *
 * <p><b>🔴 三条纪律</b>：
 *
 * <ol>
 *   <li><b>幂等</b>：事实行按「维度 + 指标 + 日 + 币种」唯一 ⇒ 重复刷新是**覆盖**而不是翻倍。
 *       （窗口内的事实先按日删干净再重写 ⇒ 上游改了映射关系也不会留下陈旧行。）
 *   <li><b>口径唯一</b>：指标码必须已在 {@code reporting_product_metric_definition} 登记
 *       （DB 触发器钉死）⇒ 事实不能自己发明指标码。
 *   <li><b>读不到 ≠ 没有数据</b>：上游故障一律翻成失败（{@code FAILED}），**不用空集合冒充**。
 * </ol>
 *
 * <p><b>🔴 三层维度怎么落</b>：
 *
 * <ul>
 *   <li><b>SKU 层</b>：订单行按 {@code skuCode} 挂到 catalog 的 SKU 档案上。
 *       <b>catalog 里查不到的 SKU 码不猜</b>（按名称匹配是 4.1.2.1 明令禁止的），
 *       它的销量计入「无法归属」并在刷新结果里报出条数。
 *   <li><b>SPU 层</b>：SKU 档案自带 {@code spuId} ⇒ 直接上卷。
 *       <b>尚未归入 SPU 的 SKU（spuId 为 null）不进 SPU 汇总</b> —— 不猜一个 SPU。
 *   <li><b>Listing 层</b>：channel 的 Listing 挂着内部 SKU。
 *       🔴 <b>一个 SKU 挂在多个 Listing 上时，它的销量无法唯一归属</b> ——
 *       订单行不带平台商品 id（trade 的订单明细里没有它），所以摊给谁都是造假。
 *       ⇒ 那部分销量**不计入任何 Listing**，单列「无法唯一归属」。
 * </ul>
 */
@Service
public class ReportingProductSalesRefreshService {

  /** 一次刷新允许的最大窗口天数（订单明细要逐单取，窗口越长请求越多）。 */
  static final int MAX_WINDOW_DAYS = 62;

  private final ReportingProductSalesPort port;
  private final ReportingTradePort trade;
  private final DSLContext sql;
  private final TenantTransactions tenants;

  public ReportingProductSalesRefreshService(
      ReportingProductSalesPort port,
      ReportingTradePort trade,
      DSLContext sql,
      TenantTransactions tenants) {
    this.port = port;
    this.trade = trade;
    this.sql = sql;
    this.tenants = tenants;
  }

  // ==========================================================================
  // 刷新
  // ==========================================================================

  /**
   * 刷新商品维度投影。
   *
   * @param from 当前窗口起始日
   * @param to 当前窗口结束日
   * @param timezone 分桶所用时区（跨时区店铺汇总必须显示所用时区）
   */
  public List<ReportingProductSalesModels.ProductRefreshResult> refresh(
      UUID tenantId, String bearer, LocalDate from, LocalDate to, ZoneId timezone) {
    var results = new ArrayList<ReportingProductSalesModels.ProductRefreshResult>();
    var catalog = refreshDimensions(tenantId, bearer);
    results.add(catalog.result());
    results.add(refreshSales(tenantId, bearer, from, to, timezone, catalog));
    return results;
  }

  // ==========================================================================
  // 维度档案（catalog SKU/SPU + channel Listing）
  // ==========================================================================

  /** 一个 SKU 的档案（投影后）。 */
  record SkuDim(UUID id, String code, String name, UUID spuId, String spuCode, String spuName, String variantSignature) {}

  /** 一个 SPU 的档案。 */
  record SpuDim(UUID id, String code, String name) {}

  /** 一个 Listing 的档案。 */
  record ListingDim(
      UUID id,
      String key,
      String platformCode,
      String shopCode,
      String externalItemId,
      String title,
      String mappingStatus) {}

  /** 刷新后拿到的商品维度档案（三层 + Listing↔SKU 绑定）。 */
  record DimensionCatalog(
      Map<String, SkuDim> skuByCode,
      Map<UUID, SkuDim> skuById,
      Map<UUID, SpuDim> spuById,
      Map<UUID, ListingDim> listingById,
      Map<UUID, List<ListingDim>> listingsBySkuId,
      int skuCount,
      int listingCount,
      ReportingProductSalesModels.ProductRefreshResult result) {

    /**
     * 该 SKU 能否**唯一归属**到某个 Listing。
     *
     * <p>🔴 返回 {@code null} 表示**归不了**：SKU 挂在多个 Listing 上（摊给谁都是造假），
     * 或者它一个 Listing 都没挂（没有平台商品，销量无处可归）。
     */
    ListingDim uniqueListingOf(UUID skuId) {
      var listings = listingsBySkuId.get(skuId);
      if (listings == null || listings.isEmpty()) return null;
      if (listings.size() > 1) return null;
      return listings.get(0);
    }
  }

  /**
   * 维度档案：catalog 的 SKU/SPU + channel 的 Listing 与绑定。
   *
   * <p>⚠️ **上游读不到就是读不到**：catalog 或 channel 挂掉时，这一路**失败**（503），
   * 绝不用空集合冒充「上游没有商品」。
   */
  private DimensionCatalog refreshDimensions(UUID tenantId, String bearer) {
    var skuByCode = new LinkedHashMap<String, SkuDim>();
    var skuById = new LinkedHashMap<UUID, SkuDim>();
    var spuById = new LinkedHashMap<UUID, SpuDim>();
    var listingById = new LinkedHashMap<UUID, ListingDim>();
    var listingsBySkuId = new LinkedHashMap<UUID, List<ListingDim>>();
    int skuFetched = 0;
    int listingFetched = 0;
    boolean skuTruncated = false;
    boolean listingTruncated = false;

    // ---- catalog：SKU 与 SPU ----
    for (int page = 0; page < ReportingProductSalesPort.MAX_PAGES; page++) {
      var pageData = port.skus(page, bearer);
      if (pageData == null || pageData.items() == null || pageData.items().isEmpty()) break;
      for (var row : pageData.items()) {
        if (row.id() == null || row.code() == null || row.code().isBlank()) continue;
        skuFetched++;
        var sku =
            new SkuDim(
                row.id(),
                row.code().trim(),
                row.name() == null ? "" : row.name(),
                row.spuId(),
                row.spuCode(),
                row.spuName(),
                row.variantSignature() == null ? "" : row.variantSignature());
        skuByCode.put(sku.code(), sku);
        skuById.put(sku.id(), sku);
        if (sku.spuId() != null) {
          var spu =
              new SpuDim(
                  sku.spuId(),
                  sku.spuCode() == null ? sku.spuId().toString() : sku.spuCode(),
                  sku.spuName() == null ? "" : sku.spuName());
          spuById.put(spu.id(), spu);
        }
      }
      if (pageData.items().size() < ReportingProductSalesPort.PAGE_SIZE) break;
      if (page >= ReportingProductSalesPort.MAX_PAGES - 1) skuTruncated = true;
    }

    // ---- channel：Listing 与 Listing↔SKU 绑定 ----
    for (int page = 0; page < ReportingProductSalesPort.MAX_PAGES; page++) {
      var pageData = port.listings(page, bearer);
      if (pageData == null || pageData.items() == null || pageData.items().isEmpty()) break;
      for (var row : pageData.items()) {
        if (row.id() == null) continue;
        listingFetched++;
        var listing =
            new ListingDim(
                row.id(),
                row.id().toString(),
                row.platformCode() == null ? "" : row.platformCode(),
                row.shopCode() == null ? "" : row.shopCode(),
                row.externalItemId() == null ? "" : row.externalItemId(),
                row.title() == null ? "" : row.title(),
                row.mappingStatus() == null ? "" : row.mappingStatus());
        listingById.put(listing.id(), listing);
        if (row.internalSkuIds() == null) continue;
        for (var skuId : row.internalSkuIds()) {
          if (skuId == null) continue;
          listingsBySkuId.computeIfAbsent(skuId, k -> new ArrayList<>()).add(listing);
        }
      }
      if (pageData.items().size() < ReportingProductSalesPort.PAGE_SIZE) break;
      if (page >= ReportingProductSalesPort.MAX_PAGES - 1) listingTruncated = true;
    }

    var note =
        (skuTruncated ? "⚠️ SKU 档案只翻到 " + ReportingProductSalesPort.MAX_PAGES + " 页为止。 " : "")
            + (listingTruncated
                ? "⚠️ Listing 档案只翻到 " + ReportingProductSalesPort.MAX_PAGES + " 页为止。"
                : "")
            + "已按商品主数据（catalog）与平台商品（channel）投影 SKU / SPU / Listing 三层档案。";

    var projected =
        tenants.run(
            tenantId,
            () -> {
              var count = 0;
              for (var sku : skuById.values()) {
                sql.execute(
                    """
                    insert into reporting_product_dimension
                      (id,tenant_id,dimension_type,dimension_id,dimension_key,dimension_name,
                       parent_dimension_type,parent_dimension_id,parent_dimension_key,variant_signature,
                       platform_code,shop_code,external_item_id,mapping_status,
                       source_service,source_reference,created_at,updated_at)
                    values (?,?,'SKU',?,?,?,'SPU',?,?,?,'','','','','catalog',?,now(),now())
                    on conflict (tenant_id, dimension_type, dimension_id) do update
                      set dimension_key = excluded.dimension_key,
                          dimension_name = excluded.dimension_name,
                          parent_dimension_id = excluded.parent_dimension_id,
                          parent_dimension_key = excluded.parent_dimension_key,
                          variant_signature = excluded.variant_signature,
                          source_reference = excluded.source_reference,
                          updated_at = now()
                    """,
                    Ids.next(),
                    tenantId,
                    sku.id(),
                    sku.code(),
                    sku.name(),
                    sku.spuId(),
                    sku.spuCode() == null ? "" : sku.spuCode(),
                    sku.variantSignature(),
                    "catalog:sku:" + sku.id());
                count++;
              }
              for (var spu : spuById.values()) {
                sql.execute(
                    """
                    insert into reporting_product_dimension
                      (id,tenant_id,dimension_type,dimension_id,dimension_key,dimension_name,
                       parent_dimension_type,parent_dimension_id,parent_dimension_key,variant_signature,
                       platform_code,shop_code,external_item_id,mapping_status,
                       source_service,source_reference,created_at,updated_at)
                    values (?,?,'SPU',?,?,?,null,null,'','','','','','','catalog',?,now(),now())
                    on conflict (tenant_id, dimension_type, dimension_id) do update
                      set dimension_key = excluded.dimension_key,
                          dimension_name = excluded.dimension_name,
                          source_reference = excluded.source_reference,
                          updated_at = now()
                    """,
                    Ids.next(),
                    tenantId,
                    spu.id(),
                    spu.code(),
                    spu.name(),
                    "catalog:spu:" + spu.id());
                count++;
              }
              for (var listing : listingById.values()) {
                sql.execute(
                    """
                    insert into reporting_product_dimension
                      (id,tenant_id,dimension_type,dimension_id,dimension_key,dimension_name,
                       parent_dimension_type,parent_dimension_id,parent_dimension_key,variant_signature,
                       platform_code,shop_code,external_item_id,mapping_status,
                       source_service,source_reference,created_at,updated_at)
                    values (?,?,'LISTING',?,?,?,null,null,'','',?,?,?,?,'channel',?,now(),now())
                    on conflict (tenant_id, dimension_type, dimension_id) do update
                      set dimension_key = excluded.dimension_key,
                          dimension_name = excluded.dimension_name,
                          platform_code = excluded.platform_code,
                          shop_code = excluded.shop_code,
                          external_item_id = excluded.external_item_id,
                          mapping_status = excluded.mapping_status,
                          source_reference = excluded.source_reference,
                          updated_at = now()
                    """,
                    Ids.next(),
                    tenantId,
                    listing.id(),
                    listing.key(),
                    listing.title(),
                    listing.platformCode(),
                    listing.shopCode(),
                    listing.externalItemId(),
                    listing.mappingStatus(),
                    "channel:listing:" + listing.id());
                count++;
              }
              // ---- Listing ↔ SKU 绑定 ----
              for (var entry : listingsBySkuId.entrySet()) {
                var skuId = entry.getKey();
                var sku = skuById.get(skuId);
                for (var listing : entry.getValue()) {
                  sql.execute(
                      """
                      insert into reporting_product_listing_sku
                        (id,tenant_id,listing_id,listing_key,platform_code,shop_code,external_item_id,
                         sku_id,sku_key,mapping_status,source_service,source_reference,created_at,updated_at)
                      values (?,?,?,?,?,?,?,?,?,?,'channel',?,now(),now())
                      on conflict (tenant_id, listing_id, sku_id) do update
                        set listing_key = excluded.listing_key,
                            platform_code = excluded.platform_code,
                            shop_code = excluded.shop_code,
                            external_item_id = excluded.external_item_id,
                            sku_key = excluded.sku_key,
                            mapping_status = excluded.mapping_status,
                            source_reference = excluded.source_reference,
                            updated_at = now()
                      """,
                      Ids.next(),
                      tenantId,
                      listing.id(),
                      listing.key(),
                      listing.platformCode(),
                      listing.shopCode(),
                      listing.externalItemId(),
                      skuId,
                      sku == null ? "" : sku.code(),
                      listing.mappingStatus(),
                      "channel:listing:" + listing.id() + ":sku:" + skuId);
                  count++;
                }
              }
              return count;
            });

    return new DimensionCatalog(
        skuByCode,
        skuById,
        spuById,
        listingById,
        listingsBySkuId,
        skuFetched,
        listingFetched,
        new ReportingProductSalesModels.ProductRefreshResult(
            "catalog-channel-product-dimensions",
            skuFetched + listingFetched,
            projected,
            (skuTruncated || listingTruncated) ? "TRUNCATED" : "SUCCEEDED",
            note));
  }

  // ==========================================================================
  // 销量事实与构成明细（trade 订单 + 订单明细）
  // ==========================================================================

  /** 一条事实的聚合键。 */
  record FactKey(
      String dimensionType, UUID dimensionId, String dimensionKey, String metricCode, LocalDate bucketDate, String currency) {}

  /** 一条构成明细（一个订单行对一个维度的一次贡献）。 */
  record Drilldown(
      String dimensionType,
      UUID dimensionId,
      String dimensionKey,
      String metricCode,
      LocalDate bucketDate,
      String currency,
      String referenceId,
      String referenceLabel,
      UUID orderId,
      String orderNo,
      java.time.OffsetDateTime occurredAt,
      String skuCode,
      String skuName,
      BigDecimal quantity,
      BigDecimal value,
      String timezone) {}

  /**
   * 订单与订单明细 → 三层维度事实 + 构成明细。
   *
   * <p>⚠️ **订单明细要逐单取**（销量与 SKU 下钻只存在于明细里），有硬上限
   * （{@link ReportingTradePort#MAX_DETAIL_FETCHES}）：触到上限时**如实报 truncated**，
   * 而不是悄悄少算。
   */
  private ReportingProductSalesModels.ProductRefreshResult refreshSales(
      UUID tenantId,
      String bearer,
      LocalDate from,
      LocalDate to,
      ZoneId timezone,
      DimensionCatalog catalog) {
    var amounts = new LinkedHashMap<FactKey, BigDecimal>();
    var quantities = new LinkedHashMap<FactKey, BigDecimal>();
    var orderSets = new LinkedHashMap<FactKey, Set<String>>();
    var drilldowns = new ArrayList<Drilldown>();

    // 无法唯一归属到 Listing 的销量（单独统计，不摊给任何 Listing）。
    var unattributedQuantity = BigDecimal.ZERO;
    var unattributedAmounts = new LinkedHashMap<String, BigDecimal>();
    var unattributedOrders = new LinkedHashMap<String, Set<String>>();
    var unattributedSkuCodes = new LinkedHashSet<String>();
    int unmatchedSkuLines = 0;
    int sharedSkuLines = 0;
    int noListingLines = 0;

    int fetched = 0;
    int detailFetches = 0;
    boolean truncated = false;

    for (int page = 0; page < ReportingTradePort.MAX_PAGES; page++) {
      var pageData = trade.salesOrders(from, to, page, bearer);
      if (pageData == null || pageData.rows() == null || pageData.rows().isEmpty()) break;
      for (var order : pageData.rows()) {
        fetched++;
        if (order.submittedAt() == null || order.id() == null) continue;
        var bucketDate = order.submittedAt().atZoneSameInstant(timezone).toLocalDate();
        var currency = order.currency() == null ? "USD" : order.currency();
        var orderNo = order.orderNo() == null ? order.id().toString() : order.orderNo();

        if (detailFetches >= ReportingTradePort.MAX_DETAIL_FETCHES) {
          truncated = true;
          continue;
        }
        detailFetches++;
        var detail = trade.salesOrderDetail(order.id(), bearer);
        if (detail == null || detail.lines() == null) continue;

        var lineIndex = 0;
        for (var line : detail.lines()) {
          lineIndex++;
          if (line.skuCode() == null || line.skuCode().isBlank()) continue;
          if (line.quantity() == null) continue;
          var quantity = line.quantity();
          var amount = line.lineAmount() == null ? BigDecimal.ZERO : line.lineAmount();
          var sku = catalog.skuByCode().get(line.skuCode().trim());
          var referenceId = orderNo + "#" + lineIndex;

          if (sku == null) {
            // 🔴 **catalog 里查不到这个 SKU 码**：不按名称猜（4.1.2.1 明令禁止），
            //   它的销量计入「无法归属」，并报出条数。
            unmatchedSkuLines++;
            unattributedQuantity = unattributedQuantity.add(quantity);
            unattributedAmounts.merge(currency, amount, BigDecimal::add);
            unattributedOrders.computeIfAbsent(currency, k -> new LinkedHashSet<>()).add(orderNo);
            unattributedSkuCodes.add(line.skuCode().trim());
            continue;
          }

          // ---- SKU 层 ----
          accumulate(amounts, quantities, orderSets, "SKU", sku.id(), sku.code(), bucketDate, currency,
              quantity, amount, orderNo);
          drilldowns.add(
              new Drilldown("SKU", sku.id(), sku.code(), ReportingProductSalesCatalog.PRODUCT_SALES_QUANTITY,
                  bucketDate, currency, referenceId, orderNo, order.id(), orderNo, order.submittedAt(), sku.code(), sku.name(),
                  quantity, BigDecimal.ZERO, timezone.getId()));
          drilldowns.add(
              new Drilldown("SKU", sku.id(), sku.code(), ReportingProductSalesCatalog.PRODUCT_SALES_AMOUNT,
                  bucketDate, currency, referenceId, orderNo, order.id(), orderNo, order.submittedAt(), sku.code(), sku.name(),
                  quantity, amount, timezone.getId()));

          // ---- SPU 层 ----
          if (sku.spuId() != null) {
            var spu = catalog.spuById().get(sku.spuId());
            var spuKey = spu == null ? sku.spuId().toString() : spu.code();
            accumulate(amounts, quantities, orderSets, "SPU", sku.spuId(), spuKey, bucketDate, currency,
                quantity, amount, orderNo);
            drilldowns.add(
                new Drilldown("SPU", sku.spuId(), spuKey, ReportingProductSalesCatalog.PRODUCT_SALES_QUANTITY,
                    bucketDate, currency, referenceId, orderNo, order.id(), orderNo, order.submittedAt(), sku.code(), sku.name(),
                    quantity, BigDecimal.ZERO, timezone.getId()));
            drilldowns.add(
                new Drilldown("SPU", sku.spuId(), spuKey, ReportingProductSalesCatalog.PRODUCT_SALES_AMOUNT,
                    bucketDate, currency, referenceId, orderNo, order.id(), orderNo, order.submittedAt(), sku.code(), sku.name(),
                    quantity, amount, timezone.getId()));
          }

          // ---- Listing 层 ----
          var listings = catalog.listingsBySkuId().get(sku.id());
          var unique = catalog.uniqueListingOf(sku.id());
          if (unique != null) {
            accumulate(amounts, quantities, orderSets, "LISTING", unique.id(), unique.key(), bucketDate,
                currency, quantity, amount, orderNo);
            drilldowns.add(
                new Drilldown("LISTING", unique.id(), unique.key(),
                    ReportingProductSalesCatalog.PRODUCT_SALES_QUANTITY, bucketDate, currency, referenceId,
                    orderNo, order.id(), orderNo, order.submittedAt(), sku.code(), sku.name(),
                    quantity, BigDecimal.ZERO, timezone.getId()));
            drilldowns.add(
                new Drilldown("LISTING", unique.id(), unique.key(),
                    ReportingProductSalesCatalog.PRODUCT_SALES_AMOUNT, bucketDate, currency, referenceId,
                    orderNo, order.id(), orderNo, order.submittedAt(), sku.code(), sku.name(), quantity, amount, timezone.getId()));
          } else {
            // 🔴 **归不了**：SKU 挂在多个 Listing 上，或一个 Listing 都没挂。
            //   摊给任何一个都是造假 ⇒ 不计入任何 Listing，单列「无法唯一归属」。
            if (listings != null && listings.size() > 1) {
              sharedSkuLines++;
            } else {
              noListingLines++;
            }
            unattributedQuantity = unattributedQuantity.add(quantity);
            unattributedAmounts.merge(currency, amount, BigDecimal::add);
            unattributedOrders.computeIfAbsent(currency, k -> new LinkedHashSet<>()).add(orderNo);
            unattributedSkuCodes.add(sku.code());
          }
        }
      }
      if (pageData.rows().size() < ReportingTradePort.PAGE_SIZE) break;
    }

    var projected =
        tenants.run(
            tenantId,
            () -> {
              // ⚠️ 口径必须先播种：事实行的指标码必须已登记（DB 触发器钉死）。
              seedDefinitions(tenantId);
              // ⚠️ 窗口内的事实与构成明细先删干净再重写：
              //   上游改了「SKU 挂在哪个 Listing 上」之后，陈旧的行会留在表里，
              //   两份行会被一起求和 ⇒ 销量翻倍。投影可重建 ⇒ 按窗口重建是安全的。
              sql.execute(
                  "delete from reporting_product_metric_fact where tenant_id = ? and bucket_date between ? and ?",
                  tenantId, from, to);
              sql.execute(
                  "delete from reporting_product_drilldown_fact where tenant_id = ? and bucket_date between ? and ?",
                  tenantId, from, to);

              var count = 0;
              for (var entry : quantities.entrySet()) {
                var key = entry.getKey();
                sql.execute(
                    """
                    insert into reporting_product_metric_fact
                      (id,tenant_id,dimension_type,dimension_id,dimension_key,metric_code,bucket_date,
                       currency,value,quantity,timezone,source_service,source_reference,updated_at)
                    values (?,?,?,?,?,?,?,?,0,?,?,'trade',?,now())
                    on conflict (tenant_id, dimension_type, dimension_id, metric_code, bucket_date, currency,
                                 source_service, source_reference)
                    do update set quantity = excluded.quantity,
                                  value = excluded.value,
                                  timezone = excluded.timezone,
                                  updated_at = now()
                    """,
                    Ids.next(), tenantId, key.dimensionType(), key.dimensionId(), key.dimensionKey(),
                    key.metricCode(), key.bucketDate(), key.currency(), entry.getValue(), timezone.getId(),
                    "agg:" + key.dimensionType() + ":" + key.metricCode() + ":" + key.bucketDate());
                count++;
              }
              for (var entry : amounts.entrySet()) {
                var key = entry.getKey();
                sql.execute(
                    """
                    insert into reporting_product_metric_fact
                      (id,tenant_id,dimension_type,dimension_id,dimension_key,metric_code,bucket_date,
                       currency,value,quantity,timezone,source_service,source_reference,updated_at)
                    values (?,?,?,?,?,?,?,?,?,0,?,'trade',?,now())
                    on conflict (tenant_id, dimension_type, dimension_id, metric_code, bucket_date, currency,
                                 source_service, source_reference)
                    do update set quantity = excluded.quantity,
                                  value = excluded.value,
                                  timezone = excluded.timezone,
                                  updated_at = now()
                    """,
                    Ids.next(), tenantId, key.dimensionType(), key.dimensionId(), key.dimensionKey(),
                    key.metricCode(), key.bucketDate(), key.currency(), entry.getValue(), timezone.getId(),
                    "agg:" + key.dimensionType() + ":" + key.metricCode() + ":" + key.bucketDate());
                count++;
              }
              for (var entry : orderSets.entrySet()) {
                var key = entry.getKey();
                sql.execute(
                    """
                    insert into reporting_product_metric_fact
                      (id,tenant_id,dimension_type,dimension_id,dimension_key,metric_code,bucket_date,
                       currency,value,quantity,timezone,source_service,source_reference,updated_at)
                    values (?,?,?,?,?,?,?,?,?,0,?,'trade',?,now())
                    on conflict (tenant_id, dimension_type, dimension_id, metric_code, bucket_date, currency,
                                 source_service, source_reference)
                    do update set quantity = excluded.quantity,
                                  value = excluded.value,
                                  timezone = excluded.timezone,
                                  updated_at = now()
                    """,
                    Ids.next(), tenantId, key.dimensionType(), key.dimensionId(), key.dimensionKey(),
                    key.metricCode(), key.bucketDate(), key.currency(),
                    new BigDecimal(entry.getValue().size()), timezone.getId(),
                    "agg:" + key.dimensionType() + ":" + key.metricCode() + ":" + key.bucketDate());
                count++;
              }
              for (var drilldown : drilldowns) {
                sql.execute(
                    """
                    insert into reporting_product_drilldown_fact
                      (id,tenant_id,dimension_type,dimension_id,dimension_key,metric_code,bucket_date,
                       currency,source_kind,reference_id,reference_label,order_id,order_no,occurred_at,
                       sku_code,sku_name,quantity,value,timezone,source_service,source_reference,updated_at)
                    values (?,?,?,?,?,?,?,?,'ORDER',?,?,?,?,?::timestamptz,?,?,?,?,?,'trade',?,now())
                    on conflict (tenant_id, dimension_type, dimension_id, metric_code, bucket_date, currency,
                                 source_kind, reference_id, source_service, source_reference)
                    do update set quantity = excluded.quantity,
                                  value = excluded.value,
                                  reference_label = excluded.reference_label,
                                  timezone = excluded.timezone,
                                  updated_at = now()
                    """,
                    Ids.next(), tenantId, drilldown.dimensionType(), drilldown.dimensionId(),
                    drilldown.dimensionKey(), drilldown.metricCode(), drilldown.bucketDate(),
                    drilldown.currency(), drilldown.referenceId(), drilldown.referenceLabel(),
                    drilldown.orderId(), drilldown.orderNo(), drilldown.occurredAt(),
                    drilldown.skuCode(), drilldown.skuName(),
                    drilldown.quantity(), drilldown.value(), drilldown.timezone(),
                    "trade:order:" + drilldown.referenceId());
                count++;
              }
              return count;
            });

    var notes = new StringBuilder();
    notes.append("已按订单明细投影三层维度事实与构成明细。");
    if (truncated) {
      notes
          .append(" ⚠️ 订单明细取到 ")
          .append(ReportingTradePort.MAX_DETAIL_FETCHES)
          .append(" 张为止（超过部分**未计入销量**）：销量与 SKU 下钻需要逐单取明细。");
    }
    if (unmatchedSkuLines > 0) {
      notes
          .append(" ⚠️ 有 ")
          .append(unmatchedSkuLines)
          .append(" 条订单行的 SKU 码在商品主数据里查不到（不按名称猜 SKU），")
          .append("其销量计入「无法唯一归属」。");
    }
    if (sharedSkuLines > 0) {
      notes
          .append(" ⚠️ 有 ")
          .append(sharedSkuLines)
          .append(" 条订单行属于「同一 SKU 挂在多个 Listing 上」的 SKU，")
          .append("销量**不摊给任何 Listing**，单列「无法唯一归属」。");
    }
    if (noListingLines > 0) {
      notes
          .append(" ⚠️ 有 ")
          .append(noListingLines)
          .append(" 条订单行属于「没有任何 Listing」的 SKU，销量单列「无法唯一归属」。");
    }

    return new ReportingProductSalesModels.ProductRefreshResult(
        "trade-product-sales",
        fetched,
        projected,
        truncated ? "TRUNCATED" : "SUCCEEDED",
        notes.toString());
  }

  /**
   * 累加一笔贡献（金额 / 件数 / 去重订单数）。
   *
   * <p>⚠️ **订单数按订单去重**：一张订单里同一款 SKU 出现两行也只算一单 ——
   * 直接数行会把「一单两行」读成「两单」。
   */
  private void accumulate(
      Map<FactKey, BigDecimal> amounts,
      Map<FactKey, BigDecimal> quantities,
      Map<FactKey, Set<String>> orderSets,
      String dimensionType,
      UUID dimensionId,
      String dimensionKey,
      LocalDate bucketDate,
      String currency,
      BigDecimal quantity,
      BigDecimal amount,
      String orderNo) {
    var amountKey =
        new FactKey(dimensionType, dimensionId, dimensionKey,
            ReportingProductSalesCatalog.PRODUCT_SALES_AMOUNT, bucketDate, currency);
    var quantityKey =
        new FactKey(dimensionType, dimensionId, dimensionKey,
            ReportingProductSalesCatalog.PRODUCT_SALES_QUANTITY, bucketDate, currency);
    var orderKey =
        new FactKey(dimensionType, dimensionId, dimensionKey,
            ReportingProductSalesCatalog.PRODUCT_ORDER_COUNT, bucketDate, currency);
    amounts.merge(amountKey, amount, BigDecimal::add);
    quantities.merge(quantityKey, quantity, BigDecimal::add);
    orderSets.computeIfAbsent(orderKey, k -> new LinkedHashSet<>()).add(orderNo);
  }

  // ==========================================================================
  // 口径播种
  // ==========================================================================

  /**
   * 确保该租户有标准口径（**幂等**）。
   *
   * <p>⚠️ 与 13.1.1.1 / 13.1.1.2 同一纪律：口径是**租户自己**的标准口径，第一次用到这个租户时
   * 按标准份播种一份；租户改了口径，播种**不会**覆盖它（{@code on conflict do nothing}）。
   */
  void seedDefinitions(UUID tenantId) {
    var exists =
        sql.fetch(
            "select 1 from reporting_product_metric_definition where tenant_id = ? limit 1", tenantId);
    if (!exists.isEmpty()) return;
    for (var definition : ReportingProductSalesCatalog.STANDARD) {
      sql.execute(
          """
          insert into reporting_product_metric_definition
            (id,tenant_id,metric_code,display_name,unit,aggregation,numerator_metric_code,
             denominator_metric_code,numerator_dimension_type,denominator_dimension_type,currency_scope,
             currency_aware,description,owner_module,enabled,created_at,updated_at)
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
          definition.numeratorDimensionType(),
          definition.denominatorDimensionType(),
          definition.currencyScope().name(),
          definition.currencyAware(),
          definition.description(),
          definition.ownerModule());
    }
  }

  /** 供查询侧复用：把窗口长度换算成天数（校验用）。 */
  static int windowDays(LocalDate from, LocalDate to) {
    return (int) java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
  }
}
