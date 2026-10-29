package com.starcon.reporting;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 13.2.2.2 SKU/SPU/Listing 的**跨服务只读端口**。
 *
 * <p>⚠️ **本端口只读「商品主数据」与「平台商品」两条上游事实**：
 *
 * <ul>
 *   <li>{@code GET /catalog/skus} —— SKU 档案与 **SKU → SPU** 的归属（catalog，3.1.2.1 SKU 列表）；
 *   <li>{@code GET /channel/listings} —— 平台商品（Listing）与其**挂载的内部 SKU**
 *       （channel，4.1.1.1 Listing 列表）。
 * </ul>
 *
 * <p>订单与订单明细走**既有的** {@link ReportingTradePort}（trade，9.1.1.1）——
 * 那条端口已经带好了 {@code salesOrders} 与 {@code salesOrderDetail}，本模块不重复造。
 *
 * <p>AGENTS.md 模块边界纪律禁止跨服务 SQL / 跨库 FK / 共享视图，
 * 所以本端口<b>只经上游的授权 HTTP API</b>读，不直连对方库。
 *
 * <p>⚠️ <b>不传租户号</b>：转发的是调用方自己的 {@code Authorization} bearer，
 * 上游侧仍按已认证成员关系解析租户并强制 RLS。租户隔离是**两道**。
 *
 * <p>⚠️ **上游读不到就是读不到**：本端口把上游故障原样翻成 503（消息写明「读不到」
 * 而不是「没有数据」），绝不返回空集合冒充「上游没有数据」。
 *
 * <p>⚠️ **字段名必须与上游逐字一致**：本客户端关掉了 {@code FAIL_ON_UNKNOWN_PROPERTIES}，
 * 所以**写错字段名不会报错，只会静默变成 null** —— 那会让整层销量恒为 0，比报错更危险。
 */
@Service
public class ReportingProductSalesPort {

  /** 上游分页上限（catalog 与 channel 都把 size 钳在 200 以内）。 */
  static final int PAGE_SIZE = 200;

  /** 最多翻多少页（{@code 200 × 25 = 5000} 行）。 */
  static final int MAX_PAGES = 25;

  private final ReportingUpstreamClient catalog;
  private final ReportingUpstreamClient channel;

  public ReportingProductSalesPort(
      @Value("${CATALOG_URL:http://127.0.0.1:8082}") String catalogUrl,
      @Value("${CHANNEL_URL:http://127.0.0.1:8083}") String channelUrl) {
    this.catalog = ReportingUpstreamClient.to("catalog 服务", catalogUrl);
    this.channel = ReportingUpstreamClient.to("channel 服务", channelUrl);
  }

  // ==================================================================
  // catalog：SKU 档案 + SKU → SPU 归属
  // ==================================================================

  /**
   * 上游一行 SKU（只声明本服务真正用到的字段）。
   *
   * <p>⚠️ {@code spuId} **可能为 null** —— 尚未归入任何 SPU 的 SKU 就是 null。
   * 那种 SKU **不进 SPU 汇总**（不猜一个 SPU），但在 SKU 层照样有自己的销量。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SkuRow(
      UUID id,
      String code,
      String name,
      UUID spuId,
      String spuCode,
      String spuName,
      /** 变体签名：同一个 SPU 下区分变体的那串规格。 */
      String variantSignature,
      String status) {}

  /** 上游一页 SKU（列表字段是 {@code items}）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SkuPage(List<SkuRow> items, long total, int page, int size) {}

  /**
   * 拉一页 SKU。
   *
   * <p>⚠️ 上游的列表字段是 {@code items}，不是 {@code rows} —— 写错会得到 {@code items == null}，
   * 于是「SKU → SPU 的归属」永远拿不到，SPU 汇总整张表会是空的。
   */
  public SkuPage skus(int page, String bearer) {
    return catalog.get(
        uriBuilder ->
            uriBuilder
                .path("/catalog/skus")
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        SkuPage.class);
  }

  // ==================================================================
  // channel：Listing（平台商品）与其挂载的内部 SKU
  // ==================================================================

  /**
   * 上游一行平台商品（只声明本服务真正用到的字段）。
   *
   * <p>⚠️ {@code internalSkuIds} 是**变体挂载的内部 SKU 逻辑 id**（跨服务，03 拥有），
   * 未映射时为 null。**同一款内部 SKU 挂在多个 Listing 上是常事** ——
   * 那时它的销量无法唯一归属到某个 Listing，本服务如实报「无法唯一归属」，
   * **不摊给任何一个 Listing**（摊了就是造假）。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ListingRow(
      UUID id,
      String shopCode,
      String shopName,
      String platformCode,
      String marketplaceCode,
      String externalItemId,
      String title,
      String status,
      String mappingStatus,
      List<UUID> internalSkuIds) {}

  /** 上游一页平台商品（列表字段是 {@code items}）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ListingPage(List<ListingRow> items, long total, int page, int size) {}

  /**
   * 拉一页平台商品。
   *
   * <p>⚠️ 上游的列表字段是 {@code items}，不是 {@code rows}。
   */
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
