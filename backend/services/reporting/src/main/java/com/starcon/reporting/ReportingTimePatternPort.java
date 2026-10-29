package com.starcon.reporting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 13.2.1.2 分时与分周模式的**跨服务只读端口**。
 *
 * <p>蓝图写明「上游 = 9 订单」，输入句写明「订单时间戳（<b>按店铺时区换算</b>）」，
 * 关系句加粗写明「<b>跨境多时区店铺必须按各自时区统计</b>，汇总时区不同会造成误判」
 * ⇒ 本模块要读三处上游：
 *
 * <ul>
 *   <li><b>9 订单（trade）</b> —— 销售订单与订单明细（金额 / 币种 / <b>下单时刻</b> / 销量）；
 *   <li><b>9 订单 · 平台单（trade）</b> —— 用 {@code sales_order_id} 把销售订单**回挂到店铺**
 *       （店铺归属的唯一来源：销售订单本身<b>没有</b>店铺字段）；
 *   <li><b>2 渠道与店铺（channel）</b> —— 平台与站点目录（<b>站点的 default_timezone</b>）
 *       + 店铺清单 + 店铺详情（该店铺<b>启用了哪些站点</b>）⇒ 店铺时区。
 * </ul>
 *
 * <p>⚠️ <b>为什么店铺的时区要绕到站点上</b>：channel 的 {@code shop} 表<b>没有</b>时区列；
 * 时区在 {@code marketplace.default_timezone}（站点参数，2.1.1.3），
 * 店铺与站点的绑定在 {@code shop_marketplace}。
 * ⇒ 店铺时区 = <b>该店铺启用的站点的 default_timezone</b>：
 * 启用 1 个站点 ⇒ 唯一可定；启用多个站点但时区相同 ⇒ 仍可定；
 * 启用多个站点且<b>时区不同</b> ⇒ <b>定不了</b>（不能挑一个凑数）；
 * 启用 0 个站点 ⇒ <b>定不了</b>。
 *
 * <p>AGENTS.md 模块边界纪律禁止跨服务 SQL / 跨库 FK / 共享视图，
 * 所以本端口<b>只经上游的授权 HTTP API</b>读，不直连对方库。
 *
 * <p>⚠️ <b>不传租户号</b>：转发的是调用方自己的 {@code Authorization} bearer，
 * 上游侧仍按已认证成员关系解析租户并强制 RLS。租户隔离是<b>两道</b>。
 *
 * <p>⚠️ <b>上游读不到就是读不到</b>：上游故障原样翻成 503（消息写明「读不到」
 * 而不是「没有数据」），绝不返回空集合冒充「上游没有数据」。
 *
 * <p>⚠️ <b>底层取数形状复用既有端口</b>（{@link ReportingTradePort} /
 * {@link ReportingSalesTrendPort} / {@link ReportingStoreSitePort}）——
 * 本类只做<b>本模块语义</b>的整形（订单 → 店铺、店铺 → 时区），
 * 不重复声明上游的 JSON 形状（字段名写错不会报错、只会静默变 null —— 那是比报错更危险的失败）。
 */
@Service
public class ReportingTimePatternPort {

  /** 上游分页上限（trade / channel 侧都把 size 钳在 200 以内）。 */
  static final int PAGE_SIZE = 200;

  /** 最多翻多少页（{@code 200 × 25 = 5000} 行）。 */
  static final int MAX_PAGES = 25;

  private final ReportingTradePort trade;
  private final ReportingSalesTrendPort salesTrendPort;
  private final ReportingStoreSitePort channel;

  public ReportingTimePatternPort(
      ReportingTradePort trade,
      ReportingSalesTrendPort salesTrendPort,
      ReportingStoreSitePort channel) {
    this.trade = trade;
    this.salesTrendPort = salesTrendPort;
    this.channel = channel;
  }

  // ==========================================================================
  // 模型
  // ==========================================================================

  /**
   * 一个店铺的时区解析结果。
   *
   * @param shopCode 店铺业务编码
   * @param shopName 店铺名
   * @param timezone IANA 时区标识（**空串 = 定不了**）
   * @param status RESOLVED 定得了 / UNRESOLVED 定不了
   * @param reason 定不了 / 定得了的原因（页面上原样显示）
   */
  public record ShopTimezone(
      String shopCode, String shopName, String timezone, String status, String reason) {}

  /** 上游一行销售订单（金额 / 币种 / 下单时刻）。 */
  public record SalesOrderRow(
      UUID id,
      String orderNo,
      String currency,
      BigDecimal grandTotalAmount,
      OffsetDateTime submittedAt) {}

  /** 上游一页销售订单。 */
  public record SalesOrderPage(List<SalesOrderRow> rows, int page, int size, long total) {}

  /** 上游订单明细的一行。 */
  public record OrderLineRow(String skuCode, BigDecimal quantity, BigDecimal lineAmount) {}

  /** 上游订单明细（头 + 行）。 */
  public record SalesOrderDetail(List<OrderLineRow> lines) {}

  // ==========================================================================
  // 店铺时区
  // ==========================================================================

  /**
   * 解析每个店铺的统计时区。
   *
   * <p>⚠️ <b>定不了的店铺也要出现在结果里</b>（{@code status = UNRESOLVED} + 原因）——
   * 少一个店铺，查询侧只能报「这个店铺不在档案里」，那会让人以为数据缺失；
   * 有一行 UNRESOLVED 就把「它确实定不了时区」这件事说出来了。
   *
   * <p>⚠️ <b>多个站点的时区不一致 ⇒ 定不了</b>，不挑一个凑数（挑一个是造假）。
   */
  public List<ShopTimezone> shopTimezones(String bearer) {
    List<ReportingStoreSitePort.PlatformRow> platforms = channel.platforms(bearer);
    List<ReportingStoreSitePort.ShopRow> shops = channel.shops(bearer);
    if (platforms == null) platforms = List.of();
    if (shops == null) shops = List.of();

    // 站点目录：平台码 → 站点码 → 站点参数（时区）。
    var siteCatalog = new LinkedHashMap<String, Map<String, ReportingStoreSitePort.SiteRow>>();
    for (var platform : platforms) {
      if (platform == null || platform.code() == null) continue;
      var sites = new LinkedHashMap<String, ReportingStoreSitePort.SiteRow>();
      if (platform.marketplaces() != null) {
        for (var site : platform.marketplaces()) {
          if (site == null || site.code() == null) continue;
          sites.put(site.code().trim(), site);
        }
      }
      siteCatalog.put(platform.code().trim(), sites);
    }

    var out = new ArrayList<ShopTimezone>();
    for (var shop : shops) {
      if (shop == null || shop.code() == null || shop.code().isBlank()) continue;
      var shopCode = shop.code().trim();
      var shopName = shop.name() == null ? "" : shop.name();
      var platformCode = shop.platformCode() == null ? "" : shop.platformCode().trim();

      ReportingStoreSitePort.ShopDetailRow detail = channel.shop(shop.id(), bearer);
      var siteRows =
          detail == null || detail.marketplaces() == null
              ? List.<ReportingStoreSitePort.ShopSiteRow>of()
              : detail.marketplaces();

      var active = new ArrayList<ReportingStoreSitePort.ShopSiteRow>();
      for (var site : siteRows) {
        if (site == null || site.marketplaceCode() == null || site.marketplaceCode().isBlank()) continue;
        if (site.status() == null || "ACTIVE".equalsIgnoreCase(site.status())) active.add(site);
      }
      if (active.isEmpty()) {
        out.add(
            new ShopTimezone(
                shopCode,
                shopName,
                "",
                "UNRESOLVED",
                "该店铺没有启用任何站点（channel 的 shop_marketplace 为空）⇒ 拿不到站点时区，"
                    + "它的订单**进不了任何本地小时 / 星期桶**，单列成「未能按店铺本地时分桶」。"));
        continue;
      }

      // 该店铺启用的站点 ⇒ 各自的 default_timezone。
      var zones = new LinkedHashMap<String, String>();
      var missing = new ArrayList<String>();
      for (var site : active) {
        var siteCode = site.marketplaceCode().trim();
        var info = siteCatalog.getOrDefault(platformCode, Map.of()).get(siteCode);
        var zone = info == null ? null : info.defaultTimezone();
        if (zone == null || zone.isBlank()) {
          missing.add(siteCode);
          continue;
        }
        zones.put(zone.trim(), siteCode);
      }
      if (!missing.isEmpty()) {
        out.add(
            new ShopTimezone(
                shopCode,
                shopName,
                "",
                "UNRESOLVED",
                "站点 "
                    + String.join("、", missing)
                    + " 在 channel 的站点目录里查不到默认时区（映射缺失）⇒ 该店铺的订单"
                    + "**进不了任何本地小时 / 星期桶**，单列成「未能按店铺本地时分桶」。"));
        continue;
      }
      if (zones.size() > 1) {
        out.add(
            new ShopTimezone(
                shopCode,
                shopName,
                "",
                "UNRESOLVED",
                "该店铺启用了 "
                    + zones.size()
                    + " 个站点且它们的时区不一致（"
                    + String.join("、", zones.keySet())
                    + "）⇒ **无法确定按哪个时区统计**（挑一个是造假），"
                    + "它的订单单列成「未能按店铺本地时分桶」。"));
        continue;
      }
      var zone = zones.keySet().iterator().next();
      out.add(
          new ShopTimezone(
              shopCode,
              shopName,
              zone,
              "RESOLVED",
              "按站点 "
                  + zones.get(zone)
                  + " 的默认时区 "
                  + zone
                  + " 统计（店铺没有自己的时区字段，时区来自 channel 的站点参数）。"));
    }
    return out;
  }

  // ==========================================================================
  // 订单
  // ==========================================================================

  /** 拉一页销售订单（按下单时刻过滤）。 */
  public SalesOrderPage salesOrders(LocalDate from, LocalDate to, int page, String bearer) {
    var upstream = trade.salesOrders(from, to, page, bearer);
    if (upstream == null) return new SalesOrderPage(List.of(), page, 0, 0);
    var rows = new ArrayList<SalesOrderRow>();
    if (upstream.rows() != null) {
      for (var row : upstream.rows()) {
        if (row == null) continue;
        rows.add(
            new SalesOrderRow(
                row.id(),
                row.orderNo(),
                row.currency(),
                row.grandTotalAmount(),
                row.submittedAt()));
      }
    }
    return new SalesOrderPage(rows, upstream.page(), upstream.size(), upstream.total());
  }

  /** 拉一张订单的明细行（销量只存在于明细里）。 */
  public SalesOrderDetail salesOrderDetail(UUID id, String bearer) {
    var upstream = trade.salesOrderDetail(id, bearer);
    if (upstream == null || upstream.lines() == null) return new SalesOrderDetail(List.of());
    var lines = new ArrayList<OrderLineRow>();
    for (var line : upstream.lines()) {
      if (line == null) continue;
      lines.add(new OrderLineRow(line.skuCode(), line.quantity(), line.lineAmount()));
    }
    return new SalesOrderDetail(lines);
  }

  /**
   * 订单 → 店铺的归属（**平台单回挂**）。
   *
   * <p>⚠️ 挂不上店铺的订单**不落任何店铺**（不计入任何时段）——
   * 「摊给一个店铺」是造假。
   */
  public Map<UUID, String> orderShopAttribution(String bearer) {
    var mapping = new LinkedHashMap<UUID, String>();
    for (int page = 0; page < MAX_PAGES; page++) {
      ReportingSalesTrendPort.PlatformOrderPage pageData =
          salesTrendPort.platformOrders(page, bearer);
      if (pageData == null || pageData.rows() == null || pageData.rows().isEmpty()) break;
      for (var row : pageData.rows()) {
        if (row == null || row.salesOrderId() == null) continue;
        if (row.shopCode() == null || row.shopCode().isBlank()) continue;
        mapping.put(row.salesOrderId(), row.shopCode().trim());
      }
      if (pageData.rows().size() < PAGE_SIZE) break;
    }
    return mapping;
  }
}
