package com.starcon.reporting;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 13.3.2.1 流量转化的**跨服务只读端口**（2 同步 / 14 广告 ⇒ channel 服务）。
 *
 * <p>蓝图写明「上游 = 2 同步、14 广告」，而这两个上游都在 **channel** 服务里：
 *
 * <ul>
 *   <li>{@code GET /channel/listing-performance} —— **平台表现快照**（4.5.1.1）：
 *       平台回报的**页面口径**数据 —— 曝光 / 点击 / Session / 平台销量 / 平台销售额，
 *       按**平台自然日**切桶，带店铺码与平台码。
 *   <li>{@code GET /channel/ad-reports/rows} —— **广告活动报表**（14.3.1.1）：
 *       广告口径的曝光 / 点击 / 订单 / 花费 / 销售额，按**平台自然日**切桶。
 * </ul>
 *
 * <p>订单数据（订单口径）走**既有的** {@link ReportingTradePort}（9 订单）——
 * 那条端口已经带好了取数形状，本模块不重复造。
 *
 * <p>AGENTS.md 模块边界纪律禁止跨服务 SQL / 跨库 FK / 共享视图，
 * 所以本端口<b>只经上游的授权 HTTP API</b>读，不直连对方库。
 *
 * <p>⚠️ <b>不传租户号</b>：转发的是调用方自己的 {@code Authorization} bearer，
 * 上游侧仍按已认证成员关系解析租户并强制 RLS。租户隔离是**两道**。
 *
 * <p>⚠️ <b>字段名必须与上游逐字一致</b>：本客户端关掉了 {@code FAIL_ON_UNKNOWN_PROPERTIES}，
 * 所以<b>写错字段名不会报错，只会静默变成 null</b> —— 那会让整层流量恒为 0，比报错更危险。
 *
 * <p>⚠️ <b>广告报表按活动分组时拿不到店铺码</b>（上游只给店铺**名**）⇒ 广告口径的事实行
 * 落库时 {@code shop_code} 为空串（**空 = 上游没给，绝不猜一个**）。
 */
@Service
public class ReportingTrafficConversionPort {

  /** 上游分页上限（channel 侧把 size 钳在 200）。 */
  static final int PAGE_SIZE = 200;

  /** 最多翻多少页（{@code 200 × 25 = 5000} 行）。 */
  static final int MAX_PAGES = 25;

  private final ReportingUpstreamClient channel;

  public ReportingTrafficConversionPort(@Value("${CHANNEL_URL:http://127.0.0.1:8083}") String url) {
    this.channel = ReportingUpstreamClient.to("channel 服务", url);
  }

  // ==================================================================
  // 2 同步：平台表现快照（**页面口径**）
  // ==================================================================

  /** 上游一行平台表现快照（channel {@code ListingPerformanceSnapshotRow} 的子集）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ListingRow(
      UUID listingId,
      String externalItemId,
      String title,
      String shopCode,
      String platformCode,
      String statDate,
      BigDecimal impressions,
      BigDecimal clicks,
      BigDecimal sessions,
      BigDecimal unitsOrdered,
      BigDecimal salesAmount,
      String currency) {}

  /** 上游一页平台表现快照。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ListingPage(List<ListingRow> items, int page, int size, long total, long syncCount) {}

  /**
   * 拉一页平台表现快照（**平台统计日**范围）。
   *
   * <p>⚠️ 上游的 {@code from} / {@code to} 收的是**平台统计日**（{@code yyyy-MM-dd}），
   * 不是瞬间 —— 这正是「页面口径」的形状，本模块原样使用。
   */
  public ListingPage listingSnapshots(LocalDate from, LocalDate to, int page, String bearer) {
    return channel.get(
        uriBuilder ->
            uriBuilder
                .path("/channel/listing-performance")
                .queryParam("from", from)
                .queryParam("to", to)
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        ListingPage.class);
  }

  // ==================================================================
  // 14 广告：活动报表（**广告口径**）
  // ==================================================================

  /** 上游一行广告报表（channel {@code AdCampaignReportRow} 的子集）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AdRow(
      String dimension,
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
      BigDecimal sales,
      LocalDate metricFrom,
      LocalDate metricTo) {}

  /** 上游一页广告报表。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AdPage(
      List<AdRow> items,
      int page,
      int size,
      long total,
      String granularity,
      String dimension,
      String emptyReason,
      String emptyNote) {}

  /**
   * 拉一页广告报表（**按活动分组、按日**）。
   *
   * <p>⚠️ {@code granularity=DAY} ⇒ 每一行对应**一个活动的一天**
   * （上游的 {@code metricFrom} 与 {@code metricTo} 是这一行的实际统计日范围）。
   */
  public AdPage adRows(LocalDate from, LocalDate to, int page, String bearer) {
    return channel.get(
        uriBuilder ->
            uriBuilder
                .path("/channel/ad-reports/rows")
                .queryParam("dimension", "CAMPAIGN")
                .queryParam("granularity", "DAY")
                .queryParam("from", from)
                .queryParam("to", to)
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        AdPage.class);
  }
}
