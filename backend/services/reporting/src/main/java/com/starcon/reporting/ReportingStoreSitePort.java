package com.starcon.reporting;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 13.2.2.1 店铺与站点的**跨服务只读端口**（2 渠道与店铺 / channel 服务）。
 *
 * <p>蓝图写明「按**店铺、站点、平台**维度拆解」⇒ 本模块要读 2 渠道的三样东西：
 *
 * <ul>
 *   <li>{@code GET /channel/platforms} —— **平台与站点目录**（2.1.1.1 / 2.1.1.3）：
 *       站点码只在平台内唯一（{@code UNIQUE(platform_id, code)}），
 *       站点还带默认币种 / 默认时区 / 国家码；
 *   <li>{@code GET /channel/shops} —— **店铺清单**（2.3.1.1）：店铺编码、名称、所属平台；
 *   <li>{@code GET /channel/shops/{id}} —— **店铺详情**（2.3.1.2）：该店铺**启用哪些站点**
 *       （{@code shop_marketplace}）⇒ 这是「店铺的销量能不能唯一落到某个站点」的唯一判据。
 * </ul>
 *
 * <p>订单与订单明细走**既有的** {@link ReportingTradePort}（9 订单），
 * 店铺归属（销售订单 → 店铺）走**既有的** {@link ReportingSalesTrendPort}（9 平台单）——
 * 那两条端口已经带好了取数形状，本模块不重复造。
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
public class ReportingStoreSitePort {

  /** 上游分页上限（channel 的 size 上限是 200）。 */
  static final int PAGE_SIZE = 200;

  /** 最多翻多少页（{@code 200 × 25 = 5000} 行）。 */
  static final int MAX_PAGES = 25;

  private final ReportingUpstreamClient channel;

  public ReportingStoreSitePort(@Value("${CHANNEL_URL:http://127.0.0.1:8083}") String url) {
    this.channel = ReportingUpstreamClient.to("channel 服务", url);
  }

  // ==================================================================
  // channel：平台与站点目录
  // ==================================================================

  /** 上游一个站点（channel {@code MarketplaceView} 的子集）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SiteRow(
      UUID id,
      String code,
      String name,
      String countryCode,
      String defaultCurrency,
      String defaultTimezone) {}

  /** 上游一个平台（channel {@code PlatformView} 的子集）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PlatformRow(
      UUID id, String code, String name, List<SiteRow> marketplaces) {}

  /**
   * 拉平台与站点目录。
   *
   * <p>⚠️ 上游**直接返回 JSON 数组**（不是包在 page 对象里）⇒ 必须用
   * {@link ReportingUpstreamClient#list} 保住元素类型；用 {@code get} 会得到
   * {@code LinkedHashMap}（类型信息丢了），站点参数整片拿不到。
   */
  public List<PlatformRow> platforms(String bearer) {
    return channel.list(
        uriBuilder -> uriBuilder.path("/channel/platforms"),
        bearer,
        new org.springframework.core.ParameterizedTypeReference<List<PlatformRow>>() {});
  }

  // ==================================================================
  // channel：店铺清单与店铺详情
  // ==================================================================

  /** 上游一行店铺（channel {@code ShopView} 的子集）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ShopRow(UUID id, String code, String name, String platformCode, String platformName) {}

  /**
   * 拉店铺清单。
   *
   * <p>⚠️ 上游**直接返回 JSON 数组** ⇒ 同上，必须用 {@code list}。
   */
  public List<ShopRow> shops(String bearer) {
    return channel.list(
        uriBuilder -> uriBuilder.path("/channel/shops"),
        bearer,
        new org.springframework.core.ParameterizedTypeReference<List<ShopRow>>() {});
  }

  /** 上游一个店铺启用的站点（channel {@code ShopMarketplaceView} 的子集）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ShopSiteRow(
      UUID marketplaceId,
      String marketplaceCode,
      String marketplaceName,
      String status,
      Instant enabledAt) {}

  /** 上游一个店铺详情（**带启用的站点**）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ShopDetailRow(
      UUID id,
      String code,
      String name,
      String platformCode,
      String platformName,
      List<ShopSiteRow> marketplaces) {}

  /**
   * 拉一个店铺详情（**带启用的站点**）。
   *
   * <p>⚠️ **店铺列表端点不返回站点**（上游的列表实现把站点填成空列表）——
   * 只有详情端点才有 {@code marketplaces} ⇒ 想知道「这家店开了哪些站点」必须逐店取详情。
   */
  public ShopDetailRow shop(UUID shopId, String bearer) {
    return channel.get(
        uriBuilder -> uriBuilder.path("/channel/shops/" + shopId),
        bearer,
        ShopDetailRow.class);
  }
}
