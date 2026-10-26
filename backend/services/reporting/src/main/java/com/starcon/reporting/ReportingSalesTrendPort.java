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
 * 13.2.1.1 销量趋势的**跨服务只读端口**。
 *
 * <p>蓝图写明「上游 = 9 订单数据」，关系句写明「**销量异常必须能关联事件**
 * （大促、断货、价格调整）」⇒ 本模块要读三处上游：
 *
 * <ul>
 *   <li><b>9 订单（trade）</b> —— 销售订单与订单明细（销量 / 金额 / 笔数）走本服务已有的
 *       {@link ReportingTradePort}（口径与 13.1.1.1 完全同源，避免同一批订单两种口径）；
 *   <li><b>9 订单 · 平台单（trade）</b> —— {@code GET /trade/platform-orders/orders}，
 *       用 {@code sales_order_id} 把销售订单**回挂到店铺**（店铺维度的唯一来源）；
 *   <li><b>14 促销（channel）</b> —— {@code GET /channel/ad-promotions/promotions}（大促）与
 *       {@code GET /channel/ad-flash-sales/flash-sales}（秒杀）⇒ 异常事件；
 *   <li><b>6.2.1 缺货与断货风险（trade）</b> —— {@code GET /trade/stockout-risk/items}
 *       ⇒ 断货日 / 缺货日异常事件。
 * </ul>
 *
 * <p>AGENTS.md 模块边界纪律禁止跨服务 SQL / 跨库 FK / 共享视图，
 * 所以本端口<b>只经上游的授权 HTTP API</b>读，不直连对方库。
 *
 * <p>⚠️ <b>不传租户号</b>：转发的是调用方自己的 {@code Authorization} bearer，
 * 上游侧仍按已认证成员关系解析租户并强制 RLS。租户隔离是**两道**。
 *
 * <p>⚠️ **上游读不到就是读不到**：本端口把上游故障原样翻成 503（消息写明「读不到」
 * 而不是「没有数据」），绝不返回空集合冒充「上游没有数据」。
 */
@Service
public class ReportingSalesTrendPort {

  /** 上游分页上限（channel / trade 侧都把 size 钳在 200 以内）。 */
  static final int PAGE_SIZE = 200;

  /** 最多翻多少页（{@code 200 × 25 = 5000} 行）。 */
  static final int MAX_PAGES = 25;

  private final ReportingUpstreamClient channel;
  private final ReportingUpstreamClient trade;

  public ReportingSalesTrendPort(
      @Value("${CHANNEL_URL:http://127.0.0.1:8083}") String channelUrl,
      @Value("${TRADE_URL:http://127.0.0.1:8085}") String tradeUrl) {
    this.channel = ReportingUpstreamClient.to("channel 服务", channelUrl);
    this.trade = ReportingUpstreamClient.to("trade 服务", tradeUrl);
  }

  // ==================================================================
  // trade：平台单（销售订单 → 店铺归属）
  // ==================================================================

  /**
   * 上游一行平台单（只声明本服务真正用到的字段）。
   *
   * <p>⚠️ {@code salesOrderId} 是**店铺归属的唯一来源**：销售订单本身**没有店铺字段**，
   * 靠平台单把订单回挂到 {@code shopCode}。挂不上的订单不计入任何店铺。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PlatformOrderRow(
      UUID id,
      UUID salesOrderId,
      String orderNo,
      String platformCode,
      String shopCode,
      String currency,
      OffsetDateTime platformPlacedAt,
      OffsetDateTime syncedAt) {}

  /** 上游一页平台单。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PlatformOrderPage(List<PlatformOrderRow> rows, int page, int size, long total) {}

  /**
   * 拉一页平台单。
   *
   * <p>⚠️ <b>上游没有按日期过滤的参数</b>（只有 platformCode / shopCode / 状态 / 关键字），
   * 所以「取窗口内的单」要**拉回来自己筛**（与 finance 会计期间端口同一纪律）。
   *
   * <p>⚠️ **列表字段是 {@code rows}，不是 {@code items}** —— 写错会得到 {@code rows == null}，
   * 于是「订单属于哪个店铺」永远拿不到，店铺维度会整片空。
   */
  public PlatformOrderPage platformOrders(int page, String bearer) {
    return trade.get(
        uriBuilder ->
            uriBuilder
                .path("/trade/platform-orders/orders")
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        PlatformOrderPage.class);
  }

  // ==================================================================
  // channel：促销（大促）与秒杀
  // ==================================================================

  /** 上游一行促销（只声明本服务真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PromotionRow(
      UUID id,
      String promotionCode,
      String promotionName,
      String promotionType,
      String platformCode,
      String shopCode,
      String currency,
      BigDecimal discountRate,
      BigDecimal discountAmount,
      String status,
      OffsetDateTime startAt,
      OffsetDateTime endAt) {}

  /** 上游一页促销。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PromotionPage(List<PromotionRow> items, long total, int page, int size) {}

  /**
   * 拉一页促销（大促事件）。
   *
   * <p>⚠️ **列表字段是 {@code items}，不是 {@code rows}**（上游
   * {@code AdPromotionModels.AdPromotionPage}）—— 写错 ⇒ 大促事件恒为空，
   * 趋势图上的「大促当天放量」就永远解释不了。
   */
  public PromotionPage promotions(int page, String bearer) {
    return channel.get(
        uriBuilder ->
            uriBuilder
                .path("/channel/ad-promotions/promotions")
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        PromotionPage.class);
  }

  /** 上游一行秒杀。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record FlashSaleRow(
      UUID id,
      String saleCode,
      String saleName,
      String platformCode,
      String shopCode,
      String listingTitle,
      String currency,
      BigDecimal flashPrice,
      String status,
      OffsetDateTime startAt,
      OffsetDateTime endAt) {}

  /** 上游一页秒杀。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record FlashSalePage(List<FlashSaleRow> items, long total, int page, int size) {}

  /** 拉一页秒杀（秒杀事件）。⚠️ 列表字段同样是 {@code items}。 */
  public FlashSalePage flashSales(int page, String bearer) {
    return channel.get(
        uriBuilder ->
            uriBuilder
                .path("/channel/ad-flash-sales/flash-sales")
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        FlashSalePage.class);
  }

  // ==================================================================
  // trade：缺货与断货风险
  // ==================================================================

  /**
   * 上游一行断货风险项（只声明本服务真正用到的字段）。
   *
   * <p>⚠️ **断货日与缺货日是两个不同的概念**：{@code expectedStockoutDate} 是**预计断货日**
   * （可以是未来），{@code shortageQuantity} 才是**当下的缺口**（缺货）。
   * 本模块把前者落成 {@code STOCKOUT} 事件、后者落成 {@code SHORTAGE} 事件。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record StockoutItemRow(
      UUID id,
      String skuCode,
      String skuName,
      BigDecimal availableQuantity,
      BigDecimal dailySalesQuantity,
      Integer coverageDays,
      LocalDate expectedStockoutDate,
      BigDecimal shortageQuantity,
      String urgency,
      String urgencyLabel,
      String urgencyBasis,
      int shopCount,
      List<String> shopCodes) {}

  /** 上游一页断货风险项。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record StockoutItemPage(
      List<StockoutItemRow> rows,
      long total,
      int page,
      int size,
      UUID snapshotId,
      String snapshotNo,
      LocalDate asOfDate,
      int leadTimeDays,
      String note) {}

  /**
   * 拉一页断货风险项。
   *
   * <p>⚠️ **上游没有快照时返回空页**（不是报错）—— 那是「上游还没有算过断货风险」，
   * 与「读不到」是两件事，能力声明里如实说明。
   */
  public StockoutItemPage stockoutItems(int page, String bearer) {
    return trade.get(
        uriBuilder ->
            uriBuilder
                .path("/trade/stockout-risk/items")
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        StockoutItemPage.class);
  }
}
