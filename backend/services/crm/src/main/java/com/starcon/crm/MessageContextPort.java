package com.starcon.crm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 15.1.1.1 消息收件箱的**跨服务只读端口**。
 *
 * <p>蓝图 15.1.1.1 要两样上游事实，而它们**都不在本服务**：
 *
 * <ol>
 *   <li><b>店铺目录</b> —— {@code GET /channel/shops}（channel 服务，2.3.1.1，端口 8083）。
 *       收件箱的数据范围是**店铺维度**，筛选项里的店铺必须来自这份**真实清单**，
 *       不是前端写死的常量。
 *   <li><b>关联订单上下文</b> —— {@code GET /trade/sales-orders/list}
 *       （trade 服务，9.1.1.1 全渠道统一订单列表，端口 8085）。
 *       蓝图加粗句要求「消息必须关联到买家与订单」，而订单的**真值**在 trade 侧：
 *       订单号 / 平台 / 金额 / 状态都在那边，本服务只读补全，**不复制一份**。
 * </ol>
 *
 * <p>AGENTS.md 模块边界纪律：禁止跨服务 SQL / 跨库 FK / 共享视图，
 * 所以本端口<b>只经授权 HTTP API</b>读，不直连对方库。
 *
 * <p><b>租户不由本端口传递租户号</b>：转发的是<b>调用方自己的</b> {@code Authorization}
 * bearer，上游仍按已认证成员关系解析租户并强制 RLS —— 也就是说租户隔离是<b>两道</b>
 * （crm 侧 RLS + 上游侧 RLS），而不是「本服务替对方决定租户」。
 *
 * <p><b>取不到就显式失败</b>：上游不可达 / 返回非 2xx 一律抛 503（写明「读不到」而不是
 * 「没有数据」），绝不返回空集合冒充「上游没有店铺 / 没有订单」——
 * 那会让现场看到 0 家店铺、0 个订单，照着空清单以为这批消息根本没有关联对象。
 *
 * <p>用法与同服务既有的 {@link SignoffDeliveredOrderPort}（读 channel）逐字同构：
 * RestClient + 显式连接/读超时 + 转发调用方 bearer + 取不到就抛。
 */
@Service
public class MessageContextPort {

  /** 上游只读形状：{@code GET /channel/shops}。 */
  static final String SHOPS_PATH = "/channel/shops";

  /** 上游只读形状：{@code GET /trade/sales-orders/list}。 */
  static final String ORDERS_PATH = "/trade/sales-orders/list";

  /** 上游翻页上限：trade 侧把 size 钳在 200 以内，这里只要够用即可。 */
  static final int PAGE_SIZE = 50;

  /**
   * 上游一行店铺（只声明本模块真正用到的字段）。
   *
   * <p>刻意读得少：读得越少，上游加字段时本模块越不容易被撑爆。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record ShopView(
      UUID id,
      String code,
      String name,
      String platformCode,
      String platformName,
      String operatingMode,
      String status) {}

  /**
   * 上游一行订单（只声明本模块真正用到的字段）。
   *
   * <p>金额用 {@link BigDecimal}（上游是 numeric）：禁浮点，前端原样展示。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record OrderView(
      UUID id,
      String orderNo,
      String externalOrderNo,
      String orderSource,
      String manualKind,
      String documentStatus,
      String currency,
      BigDecimal grandTotalAmount,
      int lineCount,
      UUID customerPartyId,
      String buyerName,
      List<String> skuCodes,
      OffsetDateTime submittedAt,
      OffsetDateTime createdAt,
      String platformStatus,
      String paymentStatus,
      String fulfillmentStatus,
      String shippingWarehouse,
      String aftersalesStatus) {}

  /** 上游一页订单（只取本模块用得到的字段；{@code summary} 刻意不声明）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record OrderPage(
      List<OrderView> rows, int page, int size, long total, String dataScope, String dataScopeNote) {}

  /**
   * 订单查找的**结论**。
   *
   * <p>刻意区分三件事：<b>找到了</b> / <b>上游没有这一单</b> / <b>读不到上游</b>。
   * 把后两者混成同一个空值，页面就只能显示一片空白 —— 而客服会把它读成
   * 「这一单没有信息」，而不是「我们没查到」。
   */
  public record OrderLookup(
      boolean found,
      OrderView order,
      String dataScope,
      String dataScopeNote,
      String reason) {

    static OrderLookup hit(OrderView order, String dataScope, String dataScopeNote) {
      return new OrderLookup(true, order, dataScope, dataScopeNote, null);
    }

    static OrderLookup miss(String dataScope, String dataScopeNote, String reason) {
      return new OrderLookup(false, null, dataScope, dataScopeNote, reason);
    }
  }

  /**
   * 上游 JSON 的 mapper。
   *
   * <p>两处配置都不是可有可无的：{@code JavaTimeModule}（上游带 {@code submittedAt} 等
   * OffsetDateTime），以及关掉 {@code FAIL_ON_UNKNOWN_PROPERTIES}
   * （上游<b>加字段</b>不应该把本模块撑爆；类型不匹配照样炸 —— 那种是真读错了，必须炸）。
   */
  private static final ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private final RestClient channel;
  private final RestClient trade;

  public MessageContextPort(
      @Value("${CHANNEL_URL:http://127.0.0.1:8083}") String channelUrl,
      @Value("${TRADE_URL:http://127.0.0.1:8085}") String tradeUrl) {
    this.channel = client(channelUrl);
    this.trade = client(tradeUrl);
  }

  /** 显式连接超时 3 秒 + 读超时 10 秒：没有上限时上游一慢就把本服务的请求线程全占住。 */
  private static RestClient client(String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(10));
    return RestClient.builder()
        .baseUrl(url)
        .requestFactory(factory)
        .messageConverters(java.util.List.of(new MappingJackson2HttpMessageConverter(JSON)))
        .build();
  }

  /**
   * 拉店铺清单（收件箱的数据范围是**店铺维度**，筛选项里的店铺来自这份真实清单）。
   *
   * <p>取不到就<b>显式失败</b>：返回一个写死的店铺列表会让运营对着不存在的店铺筛消息。
   */
  public List<ShopView> shops(String bearer) {
    try {
      List<ShopView> result =
          channel
              .get()
              .uri(SHOPS_PATH)
              .header("Authorization", bearer)
              .retrieve()
              .body(new org.springframework.core.ParameterizedTypeReference<List<ShopView>>() {});
      if (result == null) {
        throw unavailable("channel 服务没有返回店铺清单");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "调用方无权读取店铺清单", e);
      }
      throw unavailable("channel 服务返回 " + e.getStatusCode().value() + "：店铺清单读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("channel 服务不可达");
    }
  }

  /**
   * 按订单标识查上游订单（供消息详情补全订单上下文）。
   *
   * <p>上游的 keyword 是**模糊**匹配（内部单号 / 外部单号 / 买家名），
   * 所以本方法**只认完全相等**的 {@code orderNo} / {@code externalOrderNo} ——
   * 拿一条「相似」的订单当上下文，比显示「不可用」更糟：客服会照着错的单子去处理。
   *
   * <p>「上游确实没有这一单」与「读不到上游」是两种结论：
   * 前者返回 {@code found=false} 并说明理由，后者抛 503。
   */
  public OrderLookup order(String orderReference, String bearer) {
    OrderPage page;
    try {
      page =
          trade
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(ORDERS_PATH)
                          .queryParam("keyword", orderReference)
                          .queryParam("page", 0)
                          .queryParam("size", PAGE_SIZE)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(OrderPage.class);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        // 调用方在 trade 侧没有 trade.orders.read —— 这是**权限**问题，不是「没有这一单」。
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN,
            "调用方没有读取上游订单的权限（trade.orders.read）：订单上下文读不到",
            e);
      }
      throw unavailable("trade 服务返回 " + e.getStatusCode().value() + "：订单上下文读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("trade 服务不可达");
    }
    if (page == null || page.rows() == null) {
      throw unavailable("trade 服务没有返回订单列表");
    }
    String scope = page.dataScope();
    String scopeNote = page.dataScopeNote();
    for (OrderView row : page.rows()) {
      if (orderReference.equals(row.orderNo()) || orderReference.equals(row.externalOrderNo())) {
        return OrderLookup.hit(row, scope, scopeNote);
      }
    }
    // 上游返回了行但没有一条完全匹配 —— 说清楚是「没有这一单」还是「有相近的」。
    String reason =
        page.rows().isEmpty()
            ? "上游 trade 订单服务里没有订单号 "
                + orderReference
                + " 这一单（可能订单号写错，或该订单尚未同步到 trade）。"
            : "上游 trade 订单服务按「"
                + orderReference
                + "」返回了 "
                + page.rows().size()
                + " 条**相近**订单，但没有一条的订单号完全相等；"
                + "为免客服照着错的单子处理，本模块不把它当成关联订单。";
    return OrderLookup.miss(scope, scopeNote, reason);
  }

  /** 上游不可用一律 503，理由写明「读不到」而不是「没有数据」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }
}
