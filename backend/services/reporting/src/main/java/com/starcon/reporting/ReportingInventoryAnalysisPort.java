package com.starcon.reporting;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 13.5.1.1 库存分析的**跨服务只读端口**（warehouse / catalog / channel 三路）。
 *
 * <p>蓝图 13.5.1.1 的输入句写着「库存数据（7）；销售数据；盘点历史」，关系句写着
 * 「上游 = 7 库存、9 订单」。本端口负责**7 库存**这一侧（warehouse），外加
 * **成本单价**（catalog 5.2.1.2 成本构成）与 **SKU → 店铺**的归属（channel 4.1.1.1 Listing）：
 *
 * <ul>
 *   <li>{@code GET /warehouse/inventory/overview} —— 库存头寸（数量 + 缺货 / 超储标记）；</li>
 *   <li>{@code GET /warehouse/inventory/aging/buckets} —— 库龄分桶（7.6.1.1）；</li>
 *   <li>{@code GET /warehouse/inventory/aging/slow-moving} —— 呆滞汇总（7.6.1.1）；</li>
 *   <li>{@code GET /warehouse/inventory/stocktakes} —— 盘点单（7.4.2.1，盘点历史）；</li>
 *   <li>{@code GET /warehouse/warehouses} —— 仓库参照；</li>
 *   <li>{@code GET /catalog/skus} —— SKU → 类目 / 内部 id（catalog 3.1.2.1）；</li>
 *   <li>{@code GET /channel/listings} —— 平台商品挂载的内部 SKU ⇒ SKU → 店铺（channel 4.1.1.1）；</li>
 *   <li>{@code GET /trade/sales-orders/list} + {@code /trade/sales-orders/detail/{id}} ——
 *       销售订单与明细行（9.1.1.1，周转率的分子来源）。</li>
 * </ul>
 *
 * <p>⚠️ **销售数据这一路刻意不复用既有的 {@link ReportingTradePort}**：那条端口的行模型
 * 不声明 {@code documentStatus}，而本模块必须排除草稿单（草稿不是销量）。详见
 * {@link TradeOrderRow} 的注释。
 *
 * <p>⚠️ **不传租户号**：转发的是调用方自己的 {@code Authorization} bearer，
 * 上游侧仍按已认证成员关系解析租户并强制 RLS。租户隔离是**两道**。
 *
 * <p>⚠️ **跨服务不直连对方库**（AGENTS.md「模块边界纪律」）：本端口只经授权 HTTP API 读，
 * 不建跨库 FK、不建跨库视图、不直连对方 schema。
 *
 * <p>⚠️ **字段名必须与上游逐字一致**：本客户端关掉了 {@code FAIL_ON_UNKNOWN_PROPERTIES}，
 * 写错字段名不会报错，只会静默变成 null —— 那会让库存金额恒为 0，比报错还危险。
 */
@Service
public class ReportingInventoryAnalysisPort {

  /** 上游分页上限（warehouse / catalog / channel 都把 size 钳在 200 以内）。 */
  static final int PAGE_SIZE = 200;

  /** 最多翻多少页（{@code 200 × 25 = 5000} 行）。 */
  static final int MAX_PAGES = 25;

  private final ReportingUpstreamClient warehouse;
  private final ReportingUpstreamClient catalog;
  private final ReportingUpstreamClient channel;
  private final ReportingUpstreamClient trade;

  public ReportingInventoryAnalysisPort(
      @Value("${WAREHOUSE_URL:http://127.0.0.1:8084}") String warehouseUrl,
      @Value("${CATALOG_URL:http://127.0.0.1:8082}") String catalogUrl,
      @Value("${CHANNEL_URL:http://127.0.0.1:8083}") String channelUrl,
      @Value("${TRADE_URL:http://127.0.0.1:8085}") String tradeUrl) {
    this.warehouse = ReportingUpstreamClient.to("warehouse 服务", warehouseUrl);
    this.catalog = ReportingUpstreamClient.to("catalog 服务", catalogUrl);
    this.channel = ReportingUpstreamClient.to("channel 服务", channelUrl);
    this.trade = ReportingUpstreamClient.to("trade 服务", tradeUrl);
  }

  // ==================================================================
  // trade：销售订单（9.1.1.1）
  // ==================================================================

  /**
   * 上游一行销售订单。
   *
   * <p>⚠️ **本端口自己声明这一份，而不复用 {@link ReportingTradePort}**：既有端口的行模型
   * **刻意不声明 {@code documentStatus}**（13.4.1.2 只用到金额、币种、下单时刻与单号），
   * 而本模块**必须**排除草稿单 —— 草稿单不是销量，把它算进周转率的分子就是虚高。
   * ⇒ 只**新增**本文件，不改既有端口（避免动别人模块的文件）。
   *
   * <p>⚠️ 上游的词表只有 {@code DRAFT / SUBMITTED}（**单据状态**，不是履约状态），
   * 履约状态在本服务恒为 null。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TradeOrderRow(
      UUID id,
      String orderNo,
      String documentStatus,
      String currency,
      BigDecimal grandTotalAmount,
      int lineCount,
      OffsetDateTime submittedAt,
      OffsetDateTime createdAt) {}

  /** 上游销售订单页（列表字段是 {@code rows}）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TradeOrderPage(List<TradeOrderRow> rows, int page, int size, long total) {}

  /** 上游一行订单明细（**带 id** ⇒ 幂等键可以落在行上，不受行序影响）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TradeOrderLine(
      UUID id,
      int lineNo,
      UUID skuId,
      String skuCode,
      String skuName,
      BigDecimal quantity,
      BigDecimal unitPrice,
      BigDecimal lineAmount) {}

  /** 上游订单明细（头 + 行）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TradeOrderDetail(
      @JsonIgnoreProperties(ignoreUnknown = true) TradeOrderHeader header, List<TradeOrderLine> lines) {}

  /** 上游订单头（只声明本模块用得到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TradeOrderHeader(
      String orderNo, String documentStatus, String currency, BigDecimal grandTotalAmount, OffsetDateTime submittedAt) {}

  /** 拉一页销售订单（按下单时刻过滤）。 */
  public TradeOrderPage tradeOrders(LocalDate from, LocalDate to, int page, String bearer) {
    return trade.get(
        uriBuilder ->
            uriBuilder
                .path("/trade/sales-orders/list")
                .queryParam(
                    "orderedFrom",
                    from == null ? null : from.atStartOfDay(java.time.ZoneOffset.UTC).toInstant())
                .queryParam(
                    "orderedTo",
                    to == null ? null : to.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant())
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        TradeOrderPage.class);
  }

  /** 拉一张订单的明细行（销量与 SKU 维度的唯一来源）。 */
  public TradeOrderDetail tradeOrderDetail(UUID id, String bearer) {
    return trade.get(
        uriBuilder -> uriBuilder.path("/trade/sales-orders/detail/" + id),
        bearer,
        TradeOrderDetail.class);
  }

  // ==================================================================
  // warehouse：库存头寸
  // ==================================================================

  /**
   * 上游一行库存头寸。
   *
   * <p>⚠️ {@code maxOnHand} 可能为 null —— **没配超储阈值**与「配了阈值但没超储」是两件事，
   * 本模块原样搬运这个区分（页面上「未设阈值」要单独计数）。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record BalanceRow(
      UUID balanceId,
      UUID skuId,
      String skuCode,
      String skuName,
      UUID warehouseId,
      String warehouseCode,
      String warehouseName,
      UUID legalEntityId,
      String legalEntityCode,
      BigDecimal onHand,
      BigDecimal reserved,
      BigDecimal available,
      boolean zeroStock,
      boolean thresholdConfigured,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal maxOnHand,
      boolean overstock) {}

  /** 上游库存总览页（列表字段是 {@code rows}，**不是 {@code items}**）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record BalancePage(List<BalanceRow> rows, long total, int page, int size) {}

  /** 拉一页在手库存。 */
  public BalancePage balances(int page, String bearer) {
    return warehouse.get(
        uriBuilder ->
            uriBuilder
                .path("/warehouse/inventory/overview")
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        BalancePage.class);
  }

  // ==================================================================
  // warehouse：库龄与呆滞
  // ==================================================================

  /** 上游一个库龄桶。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AgingBucketRow(
      String bucket,
      String label,
      @JsonIgnoreProperties(ignoreUnknown = true) Integer lowerDays,
      @JsonIgnoreProperties(ignoreUnknown = true) Integer upperDays,
      long skuCount,
      long bucketCount,
      long lotBasedBucketCount,
      BigDecimal onHand,
      BigDecimal reserved,
      BigDecimal available) {}

  /** 上游库龄分桶报表。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AgingBucketReport(
      LocalDate asOf,
      List<AgingBucketRow> buckets,
      String agingBasisNote) {}

  /**
   * 拉库龄分桶报表。
   *
   * @param warehouseId 仓库过滤；null = 全租户
   */
  public AgingBucketReport agingBuckets(UUID warehouseId, String bearer) {
    return warehouse.get(
        uriBuilder -> {
          var b = uriBuilder.path("/warehouse/inventory/aging/buckets");
          if (warehouseId != null) b = b.queryParam("warehouseId", warehouseId);
          return b;
        },
        bearer,
        AgingBucketReport.class);
  }

  /** 上游呆滞汇总（**全量**，不受分页与档位筛选影响）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SlowMovingSummary(
      long skuCount,
      long bucketCount,
      long obsoleteCount,
      long slowMovingCount,
      BigDecimal obsoleteAvailable,
      BigDecimal slowMovingAvailable,
      BigDecimal obsoleteAndUpAvailable,
      @JsonIgnoreProperties(ignoreUnknown = true) Integer oldestAgingDays) {}

  /** 上游呆滞清单页（**只取汇总**，明细行本模块用不到）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SlowMovingPage(SlowMovingSummary summary, long total, int page, int size) {}

  /**
   * 拉呆滞汇总。
   *
   * @param warehouseId 仓库过滤；null = 全租户
   */
  public SlowMovingPage slowMoving(UUID warehouseId, String bearer) {
    return warehouse.get(
        uriBuilder -> {
          var b = uriBuilder.path("/warehouse/inventory/aging/slow-moving");
          if (warehouseId != null) b = b.queryParam("warehouseId", warehouseId);
          return b.queryParam("page", 0).queryParam("size", 1);
        },
        bearer,
        SlowMovingPage.class);
  }

  // ==================================================================
  // warehouse：盘点历史
  // ==================================================================

  /** 上游一张盘点单。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record StocktakeSheet(
      UUID id,
      String code,
      String scopeType,
      String scopeLabel,
      String status,
      @JsonIgnoreProperties(ignoreUnknown = true) OffsetDateTime frozenAt,
      long lineCount,
      long countedLineCount,
      long varianceLineCount,
      BigDecimal bookTotal) {}

  /** 上游盘点单页（列表字段是 {@code sheets}）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record StocktakePage(List<StocktakeSheet> sheets, long total, int page, int size) {}

  /** 拉一页盘点单。 */
  public StocktakePage stocktakes(int page, String bearer) {
    return warehouse.get(
        uriBuilder ->
            uriBuilder
                .path("/warehouse/inventory/stocktakes")
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        StocktakePage.class);
  }

  /** 上游一个仓库参照项。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record WarehouseRef(UUID id, String code, String name) {}

  /** 拉仓库列表（**裸数组**，不是 page 对象）。 */
  public List<WarehouseRef> warehouses(String bearer) {
    return warehouse.list(
        uriBuilder -> uriBuilder.path("/warehouse/warehouses"),
        bearer,
        new org.springframework.core.ParameterizedTypeReference<List<WarehouseRef>>() {});
  }

  // ==================================================================
  // catalog：SKU → 类目 / 内部 id
  // ==================================================================

  /**
   * 上游一行 SKU。
   *
   * <p>⚠️ {@code category} 是**类目名**（上游 {@code SkuListRow.category}），可能为 null
   * —— 没归类的 SKU 就是 null，那种 SKU 的类目维度**留空串**（不猜一个类目）。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CatalogSkuRow(UUID id, String code, String name, String category) {}

  /** 上游一页 SKU（列表字段是 {@code items}）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CatalogSkuPage(List<CatalogSkuRow> items, long total, int page, int size) {}

  /** 拉一页 SKU（拿 id → 类目 / 编码 的映射）。 */
  public CatalogSkuPage skus(int page, String bearer) {
    return catalog.get(
        uriBuilder ->
            uriBuilder
                .path("/catalog/skus")
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        CatalogSkuPage.class);
  }

  // ==================================================================
  // catalog：成本构成（单位成本 = 库存金额的单位成本）
  // ==================================================================

  /**
   * 上游一行成本构成。
   *
   * <p>⚠️ **三个分项都是「单位」成本**（不是总额）：上游在成本分摊里直接用
   * {@code purchaseCost × quantity}，可证 {@code purchaseCost} 是单价。
   *
   * <p>⚠️ {@code maintained=false} ⇒ 这个 SKU 的成本**没维护** ⇒ 库存金额算不出来，
   * 本模块报「—」，**不是 0 元**。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CatalogCostRow(
      UUID skuId,
      String skuCode,
      String skuName,
      boolean maintained,
      String costMethod,
      @JsonIgnoreProperties(ignoreUnknown = true) String currency,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal packagingCost,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal otherDirectCost,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal purchaseCost) {}

  /** 上游成本构成页（列表字段是 {@code items}）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CatalogCostPage(List<CatalogCostRow> items, long total, int page, int size) {}

  /** 拉一页 SKU 成本构成（**单位成本**的唯一来源）。 */
  public CatalogCostPage costs(int page, String bearer) {
    return catalog.get(
        uriBuilder ->
            uriBuilder
                .path("/catalog/sku-cost-compositions")
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        CatalogCostPage.class);
  }

  // ==================================================================
  // channel：SKU → 店铺
  // ==================================================================

  /**
   * 上游一个平台商品（Listing）。
   *
   * <p>⚠️ {@code internalSkuIds} 是**变体挂载的内部 SKU 逻辑 id**（catalog 拥有），
   * 未映射时为 null。**同一款内部 SKU 挂在多个 Listing 上是常事** ——
   * 那时它的库存无法唯一归属到某个店铺，本模块如实报「未归属」，
   * **不摊给任何一个店铺**（摊了就是造假）。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ListingRow(
      UUID id,
      String shopCode,
      String shopName,
      String platformCode,
      String status,
      List<UUID> internalSkuIds) {}

  /** 上游一页平台商品（列表字段是 {@code items}）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ListingPage(List<ListingRow> items, long total, int page, int size) {}

  /** 拉一页平台商品。 */
  public ListingPage listings(int page, String bearer) {
    return channel.get(
        uriBuilder ->
            uriBuilder
                .path("/channel/listings")
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        ListingPage.class);
  }
}
