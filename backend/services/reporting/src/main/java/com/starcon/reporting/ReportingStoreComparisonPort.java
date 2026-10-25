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
 * 13.1.1.2 多店对比的**跨服务只读端口**。
 *
 * <p>⚠️ **本端口只读「带店铺维度」的上游事实**。这是本模块与 13.1.1.1 最大的差别：
 * 13.1.1.1 的销售类指标只能落在**公司维度**（上游 {@code sales_order} 没有 shop 维度），
 * 而本模块要的是**店铺维度**的横向对比，所以走的是**另外几条真正带店铺归属的**上游事实：
 *
 * <ul>
 *   <li>{@code GET /channel/ad-reports/options} —— 店铺清单与平台清单（channel，14.3.1.1）；
 *   <li>{@code GET /channel/ad-campaigns/campaigns} —— 广告活动（每个活动带 shopCode +
 *       platformCode ⇒ **店铺归属平台的唯一来源**）；
 *   <li>{@code GET /channel/ad-reports/rows?dimension=SHOP} —— 店铺维度的广告花费与
 *       广告销售额（14.3.1.1）；
 *   <li>{@code GET /trade/platform-orders/orders} —— 平台单（**带 shop_code** 的成交金额，
 *       9.1.1.1）。
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
public class ReportingStoreComparisonPort {

  /** 上游分页上限（channel 的 size 上限是 200）。 */
  static final int PAGE_SIZE = 200;

  /** 最多翻多少页（{@code 200 × 25 = 5000} 行）。 */
  static final int MAX_PAGES = 25;

  private final ReportingUpstreamClient channel;
  private final ReportingUpstreamClient trade;

  public ReportingStoreComparisonPort(
      @Value("${CHANNEL_URL:http://127.0.0.1:8083}") String channelUrl,
      @Value("${TRADE_URL:http://127.0.0.1:8085}") String tradeUrl) {
    this.channel = ReportingUpstreamClient.to("channel 服务", channelUrl);
    this.trade = ReportingUpstreamClient.to("trade 服务", tradeUrl);
  }

  // ==================================================================
  // channel：店铺清单与平台清单
  // ==================================================================

  /** 上游一个下拉项（取值 + 显示名）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Option(String value, String label) {}

  /** 上游的广告报表筛选项（店铺清单 + 平台清单）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AdOptions(List<Option> shops, List<Option> platforms) {}

  /**
   * 拉店铺清单与平台清单。
   *
   * <p>⚠️ 上游的 {@code shops} 只给**店铺编码与店铺名**、{@code platforms} 只给**平台编码与
   * 平台名**，**没有店铺到平台的映射** —— 那个映射由广告活动带出来（见 {@link #adCampaigns}）。
   */
  public AdOptions adOptions(String bearer) {
    return channel.get(
        uriBuilder -> uriBuilder.path("/channel/ad-reports/options"), bearer, AdOptions.class);
  }

  // ==================================================================
  // channel：广告活动（店铺 → 平台的唯一来源）
  // ==================================================================

  /** 上游一行广告活动（只声明本服务真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AdCampaignRow(
      String shopCode,
      String shopName,
      String platformCode,
      String platformName,
      String currency,
      BigDecimal spend,
      BigDecimal sales) {}

  /** 上游一页广告活动。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AdCampaignPage(List<AdCampaignRow> items, long total, int page, int size) {}

  /**
   * 拉一页广告活动（**只为拿店铺的平台归属**）。
   *
   * <p>⚠️ 上游的列表字段是 {@code items}，不是 {@code rows} —— 写错会得到 {@code items == null}，
   * 于是「店铺属于哪个平台」永远拿不到，页面上的平台分组会全空。
   */
  public AdCampaignPage adCampaigns(int page, String bearer) {
    return channel.get(
        uriBuilder ->
            uriBuilder
                .path("/channel/ad-campaigns/campaigns")
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        AdCampaignPage.class);
  }

  // ==================================================================
  // channel：店铺维度的广告指标
  // ==================================================================

  /** 上游一行店铺维度广告指标。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AdShopRow(
      String dimensionKey,
      String dimensionLabel,
      String currency,
      BigDecimal impressions,
      BigDecimal clicks,
      BigDecimal orders,
      BigDecimal spend,
      BigDecimal sales,
      LocalDate metricFrom,
      LocalDate metricTo) {}

  /** 上游一页店铺维度广告指标。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AdShopPage(List<AdShopRow> items, int page, int size, long total) {}

  /**
   * 拉**一天**的店铺维度广告指标。
   *
   * <p>⚠️ <b>上游的 {@code dimension=SHOP} 分组里没有日期桶</b>（group by 是
   * {@code shop_code, shop_name, currency}）—— 一次请求给的是**整个区间的店铺合计**。
   * 本模块的事实表是**按日**的（任意窗口都能直接聚合），所以这里**逐日拉**：
   * 区间有 N 天就发 N 次 {@code from = to = 某一天} 的请求。
   *
   * <p>⚠️ **逐日拉的代价是请求数**：N 天 = N 次 HTTP 调用。所以服务层对区间长度有硬上限，
   * 超了直接 400（**不悄悄截断** —— 悄悄截断 = 报表少算了一段却没人知道）。
   */
  public AdShopPage adShopRows(LocalDate day, String bearer) {
    return channel.get(
        uriBuilder ->
            uriBuilder
                .path("/channel/ad-reports/rows")
                .queryParam("dimension", "SHOP")
                .queryParam("granularity", "DAY")
                .queryParam("from", day)
                .queryParam("to", day)
                .queryParam("page", 0)
                .queryParam("size", PAGE_SIZE),
        bearer,
        AdShopPage.class);
  }

  // ==================================================================
  // trade：平台单（带店铺归属的成交金额）
  // ==================================================================

  /** 上游一行平台单（只声明本服务真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PlatformOrderRow(
      UUID id,
      UUID salesOrderId,
      String orderNo,
      String platformCode,
      String shopCode,
      String externalOrderNo,
      String currency,
      BigDecimal platformGrandTotalAmount,
      BigDecimal restoredGrandTotalAmount,
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
}
