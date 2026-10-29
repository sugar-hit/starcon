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
 * 13.3.1.1 产品表现分析的**跨服务只读端口**（把「360 度」真正接到业务域上）。
 *
 * <p>本端口只补齐**13.2.2.2 之外的**那几条上游事实；已经有的端口直接复用
 * （{@link ReportingTradePort} 的售后列表、{@link ReportingWarehousePort} 的库存余额、
 * {@link ReportingFinancePort} 的成本分录）—— **同一个上游不写两条取数路径**，
 * 两条路径迟早会读出不一样的数。
 *
 * <ul>
 *   <li>{@code GET /channel/listing-performance} —— **流量 / 转化**（4.5.1.1，平台报表口径）；
 *   <li>{@code GET /channel/listing-hijackers/records} —— **跟卖 / 竞品价格**（4.5.2.1，参考指标）；
 *   <li>{@code GET /channel/ad-reports/rows} —— **广告活动级花费与表现**（14.3.1.1）；
 *   <li>{@code GET /channel/ad-campaign-details/campaigns/{id}/products} —— **活动 ↔ Listing 绑定**（14.1.1.2）；
 *   <li>{@code GET /warehouse/inventory/aging/slow-moving} —— **库龄**（7 库存库龄，时点快照）；
 *   <li>{@code GET /trade/aftersales/cases/{id}} —— **退货按 SKU 归集**（9 售后单明细）；
 *   <li>{@code GET /finance/cost-calculation/runs} —— **成本批次**（12.4.1.2，用于剔除被重算覆盖的旧批次）。
 * </ul>
 *
 * <p>⚠️ <b>不传租户号</b>：转发的是调用方自己的 {@code Authorization} bearer，
 * 上游侧仍按已认证成员关系解析租户并强制 RLS。租户隔离是**两道**。
 *
 * <p>⚠️ **上游读不到就是读不到**：本端口把上游故障原样翻成 503（消息写明「读不到」
 * 而不是「没有数据」），绝不返回空集合冒充「上游没有数据」。
 *
 * <p>⚠️ **字段名必须与上游逐字一致**：本客户端关掉了 {@code FAIL_ON_UNKNOWN_PROPERTIES}，
 * 所以**写错字段名不会报错，只会静默变成 null** —— 那会让整块指标恒为 0，比报错更危险。
 */
@Service
public class ReportingProductPerformancePort {

  /** 上游分页上限（channel / warehouse / trade / finance 都把 size 钳在 200 以内）。 */
  static final int PAGE_SIZE = 200;

  /** 最多翻多少页（{@code 200 × 25 = 5000} 行）。 */
  static final int MAX_PAGES = 25;

  /** 一次刷新里最多取多少张售后单的**明细**（退货按 SKU 归集只存在于明细里 ⇒ 必须逐单取）。 */
  static final int MAX_AFTERSALE_DETAIL_FETCHES = 50;

  /** 一次刷新里最多取多少个广告活动的投放商品（活动 ↔ Listing 绑定逐活动取）。 */
  static final int MAX_CAMPAIGN_PRODUCT_FETCHES = 50;

  private final ReportingUpstreamClient channel;
  private final ReportingUpstreamClient warehouse;
  private final ReportingUpstreamClient trade;
  private final ReportingUpstreamClient finance;

  public ReportingProductPerformancePort(
      @Value("${CHANNEL_URL:http://127.0.0.1:8083}") String channelUrl,
      @Value("${WAREHOUSE_URL:http://127.0.0.1:8084}") String warehouseUrl,
      @Value("${TRADE_URL:http://127.0.0.1:8085}") String tradeUrl,
      @Value("${FINANCE_URL:http://127.0.0.1:8087}") String financeUrl) {
    this.channel = ReportingUpstreamClient.to("channel 服务", channelUrl);
    this.warehouse = ReportingUpstreamClient.to("warehouse 服务", warehouseUrl);
    this.trade = ReportingUpstreamClient.to("trade 服务", tradeUrl);
    this.finance = ReportingUpstreamClient.to("finance 服务", financeUrl);
  }

  // ==================================================================
  // channel：流量与转化（4.5.1.1 Listing 表现）
  // ==================================================================

  /** 上游一行平台表现快照（只声明本模块真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ListingPerformanceRow(
      UUID listingId,
      String title,
      String shopCode,
      String platformCode,
      /** 平台统计日（{@code yyyy-MM-dd}）。 */
      String statDate,
      BigDecimal impressions,
      BigDecimal clicks,
      BigDecimal sessions,
      BigDecimal unitsOrdered,
      BigDecimal salesAmount,
      BigDecimal conversionRate,
      String currency) {}

  /** 上游一页平台表现快照（列表字段是 {@code items}）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ListingPerformancePage(
      List<ListingPerformanceRow> items, int page, int size, long total, long syncCount) {}

  /**
   * 拉一页平台表现快照。
   *
   * <p>⚠️ 上游的列表字段是 {@code items}，不是 {@code rows} —— 写错会得到 {@code items == null}，
   * 拉取循环第一页就判定「没有数据」⇒ **流量 / 转化恒为 0**。
   */
  public ListingPerformancePage listingPerformance(
      LocalDate from, LocalDate to, int page, String bearer) {
    return channel.get(
        uriBuilder ->
            uriBuilder
                .path("/channel/listing-performance")
                .queryParam("from", from == null ? null : from.toString())
                .queryParam("to", to == null ? null : to.toString())
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        ListingPerformancePage.class);
  }

  // ==================================================================
  // channel：跟卖 / 竞品价格（4.5.2.1）
  // ==================================================================

  /** 上游一行跟卖记录（只声明本模块真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record HijackerRow(
      UUID recordId,
      UUID watchId,
      UUID listingId,
      String externalItemId,
      String title,
      String shopCode,
      String platformCode,
      String hijackerSellerId,
      String hijackerShopName,
      /** 跟卖者性质：OWN_SHOP / EXTERNAL。 */
      String hijackerKind,
      BigDecimal hijackerPrice,
      String currency,
      BigDecimal referencePrice,
      BigDecimal priceGap,
      BigDecimal priceGapRatio,
      OffsetDateTime observedAt,
      String dataSource) {}

  /** 上游一页跟卖记录（列表字段是 {@code items}）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record HijackerPage(
      List<HijackerRow> items,
      int page,
      int size,
      long total,
      long alertCount,
      long watchCount,
      String dataSource,
      String sourceNote) {}

  /**
   * 拉一页跟卖记录。
   *
   * @param listingId 只看某个平台商品（空 = 全部）
   */
  public HijackerPage hijackerRecords(
      String listingId, LocalDate from, LocalDate to, int page, String bearer) {
    return channel.get(
        uriBuilder -> {
          var builder = uriBuilder.path("/channel/listing-hijackers/records");
          if (listingId != null && !listingId.isBlank()) builder = builder.queryParam("listingId", listingId);
          builder = builder.queryParam("from", from == null ? null : from.toString());
          builder = builder.queryParam("to", to == null ? null : to.toString());
          return builder.queryParam("page", Math.max(page, 0)).queryParam("size", PAGE_SIZE);
        },
        bearer,
        HijackerPage.class);
  }

  // ==================================================================
  // channel：广告活动报表（14.3.1.1）与活动 ↔ Listing 绑定（14.1.1.2）
  // ==================================================================

  /** 上游一行广告活动报表（**活动级**口径）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AdCampaignRow(
      String dimension,
      /** 分组键：**CAMPAIGN 分组下是活动 id（uuid 文本）**。 */
      String dimensionKey,
      String dimensionLabel,
      String currency,
      String platformName,
      String shopName,
      String externalCampaignId,
      BigDecimal impressions,
      BigDecimal clicks,
      BigDecimal orders,
      BigDecimal spend,
      BigDecimal sales) {}

  /** 上游一页广告活动报表（列表字段是 {@code items}）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AdCampaignPage(List<AdCampaignRow> items, int page, int size, long total) {}

  /**
   * 拉一页广告活动报表（**按活动分组**）。
   *
   * <p>⚠️ **这一行是「活动在窗口内的合计」，不是日粒度**：上游按
   * 「活动 × 币种」聚合整个 {@code from..to} 区间 ⇒ 本模块把它当**窗口聚合**存快照，
   * 不假装它是某一天的数字。
   */
  public AdCampaignPage adCampaignRows(LocalDate from, LocalDate to, int page, String bearer) {
    return channel.get(
        uriBuilder ->
            uriBuilder
                .path("/channel/ad-reports/rows")
                .queryParam("dimension", "CAMPAIGN")
                .queryParam("from", from == null ? null : from.toString())
                .queryParam("to", to == null ? null : to.toString())
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        AdCampaignPage.class);
  }

  /** 上游一行投放商品（活动 ↔ Listing 的绑定）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AdCampaignProductRow(
      UUID id,
      UUID groupId,
      String groupName,
      UUID listingId,
      String externalItemId,
      String title) {}

  /** 上游一页投放商品（列表字段是 {@code items}）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AdCampaignProductPage(
      List<AdCampaignProductRow> items, int page, int size, long total) {}

  /** 拉一页某个广告活动的投放商品（**活动 ↔ Listing 绑定**）。 */
  public AdCampaignProductPage adCampaignProducts(String campaignId, int page, String bearer) {
    return channel.get(
        uriBuilder ->
            uriBuilder
                .path("/channel/ad-campaign-details/campaigns/" + campaignId + "/products")
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        AdCampaignProductPage.class);
  }

  // ==================================================================
  // warehouse：库龄（7 库存）
  // ==================================================================

  /** 上游一行呆滞清单（**批次 × 库位粒度**，带 SKU 与库龄）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AgingRow(
      UUID bucketId,
      UUID skuId,
      String skuCode,
      String skuName,
      Integer agingDays,
      String agingBucket,
      String agingBucketLabel,
      BigDecimal onHand,
      BigDecimal reserved,
      BigDecimal available,
      String slowMovingStatus) {}

  /** 上游一页呆滞清单（列表字段是 {@code rows}）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AgingPage(
      List<AgingRow> rows, long total, int page, int size, String agingBasisNote) {}

  /**
   * 拉一页呆滞清单（含 NORMAL 档位）。
   *
   * <p>⚠️ **必须显式带上全部三个档位**（{@code NORMAL / SLOW_MOVING / OBSOLETE}）：
   * 上游默认只给「需要处理」的行 —— 那样快销 SKU 的库龄会**整段缺失**，
   * 页面会误以为「这个商品没有库龄」。
   */
  public AgingPage agingRows(int page, String bearer) {
    return warehouse.get(
        uriBuilder ->
            uriBuilder
                .path("/warehouse/inventory/aging/slow-moving")
                .queryParam("slowMovingStatus", "NORMAL,SLOW_MOVING,OBSOLETE")
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        AgingPage.class);
  }

  // ==================================================================
  // trade：退货按 SKU 归集（9 售后单）
  // ==================================================================

  /** 上游售后单里的订单行（**带 SKU 与退货数量 / 金额**）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AftersalesOrderLine(
      UUID id,
      int lineNo,
      UUID skuId,
      String skuCode,
      String skuName,
      BigDecimal quantity,
      boolean involved,
      BigDecimal aftersalesQuantity,
      BigDecimal aftersalesAmount) {}

  /** 上游售后单的商品行（登记的涉及行，带 SKU 与申请金额）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AftersalesCaseLine(
      UUID id,
      UUID orderLineId,
      int lineNo,
      String skuCode,
      String skuName,
      BigDecimal quantity,
      BigDecimal requestedAmount,
      String currency) {}

  /** 上游售后单详情。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AftersalesDetail(
      List<AftersalesOrderLine> orderLines, List<AftersalesCaseLine> lines) {}

  /**
   * 拉一张售后单的明细（**退货按 SKU 归集的唯一来源**）。
   *
   * <p>⚠️ 售后单**列表**不带 SKU ⇒ 必须逐单取明细（N+1），有张数上限，
   * 触到上限时如实报「已截断」。
   */
  public AftersalesDetail aftersalesDetail(UUID id, String bearer) {
    return trade.get(
        uriBuilder -> uriBuilder.path("/trade/aftersales/cases/" + id),
        bearer,
        AftersalesDetail.class);
  }

  // ==================================================================
  // finance：成本批次（12.4.1.2）
  // ==================================================================

  /** 上游一行成本批次（**按创建时间倒序** ⇒ 第一个是最新的批次）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CostRunRow(
      UUID id,
      String scopeType,
      UUID scopeRefId,
      String valuationMethod,
      String currency,
      OffsetDateTime periodFrom,
      OffsetDateTime periodTo,
      String status,
      int entryCount) {}

  /** 上游一页成本批次（列表字段是 {@code items}）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CostRunPage(List<CostRunRow> items, int page, int size, long total) {}

  /**
   * 拉一页成本批次。
   *
   * <p>⚠️ **为什么要它**：成本分录是**按批次**落的，同一条出库流水被**重算**过时
   * 会产生**新批次的新分录**，旧分录仍在表里 ⇒ 直接把分录加总会**把成本算两遍**。
   * 本服务用批次的先后顺序挑出「每个出库流水最新那一版」，多算的那份不进来。
   */
  public CostRunPage costRuns(int page, String bearer) {
    return finance.get(
        uriBuilder ->
            uriBuilder
                .path("/finance/cost-calculation/runs")
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        CostRunPage.class);
  }

  /**
   * 上游一行成本分录。
   *
   * <p>⚠️ **为什么这里自己声明一份**（而不是复用 {@link ReportingFinancePort} 的同名记录）：
   * 本模块需要 {@code runId} 与 {@code flowTransactionId} 两个字段 ——
   * 没有它们就无法识别「这条出库流水已被重算覆盖」，成本会**算两遍**。
   * 少声明字段不会读错，写错字段名会静默变 null（那个更危险）。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CostEntryRow(
      UUID id,
      /** 所属成本批次（12.4.1.2 的 run）。 */
      UUID runId,
      /** 上游库存流水（warehouse 7.1.2.1）—— 同一流水的重算版本靠它对上号。 */
      UUID flowTransactionId,
      OffsetDateTime occurredAt,
      String businessType,
      UUID skuId,
      String skuCode,
      BigDecimal issueQuantity,
      BigDecimal costAmount,
      String currency,
      String status) {}

  /** 上游一页成本分录（列表字段是 {@code items}）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CostEntryPage(List<CostEntryRow> items, int page, int size, long total) {}

  /**
   * 拉一页成本分录（按业务类型过滤）。
   *
   * @param businessType 业务类型（{@code SALES_OUTBOUND} = 销售出库）
   */
  public CostEntryPage costEntries(String businessType, int page, String bearer) {
    return finance.get(
        uriBuilder ->
            uriBuilder
                .path("/finance/cost-calculation/entries")
                .queryParam("businessType", businessType)
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        CostEntryPage.class);
  }
}
