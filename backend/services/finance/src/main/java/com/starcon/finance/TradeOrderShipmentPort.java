package com.starcon.finance;

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
 * 读取上游 **9 订单（9.1.2.1 平台订单 / 9.4.1.2 发货记录）**事实的**跨服务只读端口**（trade 服务，端口 8085）。
 *
 * <p>蓝图 12.3.1.3 的输入写的是「订单与结算状态」，上游写的是「9 订单」。本端口读的是 trade 的两个只读端点：
 *
 * <ul>
 *   <li>{@code GET /trade/platform-orders/orders} —— 平台订单：订单号、平台、店铺、币种、订单总额。
 *   <li>{@code GET /trade/fulfillment/shipment-records/records} —— 发货记录：**发货时间**与发货状态。
 * </ul>
 *
 * <p>「订单已发货但钱未到账」这个判断**必须**同时有这两样事实，所以本模块只能经 trade 的授权 API 读，
 * <b>绝不直连对方库、不建跨库 FK / 视图</b>（AGENTS.md 模块边界纪律）。
 *
 * <p>🔴 <b>租户不由本端口传递</b>：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * trade 侧仍按已认证成员关系解析租户并强制 RLS。租户隔离因此是<b>两道</b>
 * （finance 侧 RLS + trade 侧 RLS），而不是「本服务替对方决定租户」。
 *
 * <p>用法与既有 {@code PurchaseArrivalPort} / {@code TradeReceivablePort} 逐字同构：取不到就<b>显式失败</b>
 * （503），绝不返回空集合冒充「上游没有订单」—— 那会让现场看到 0 笔延迟结算，照着空名单以为平台没有订单。
 */
@Service
public class TradeOrderShipmentPort {

  /** 上游只读形状：{@code GET /trade/platform-orders/orders}（9.1.2.1 平台订单同步）。 */
  static final String ORDERS_PATH = "/trade/platform-orders/orders";

  /** 上游只读形状：{@code GET /trade/fulfillment/shipment-records/records}（9.4.1.2 发货记录）。 */
  static final String SHIPMENTS_PATH = "/trade/fulfillment/shipment-records/records";

  /** 上游分页上限。 */
  static final int PAGE_SIZE = 200;

  /**
   * 最多翻多少页（{@code 200 × 25 = 5000} 件）。
   *
   * <p>必须有上限：翻页是无界的，一个大租户能把本服务的请求线程占满。触到上限时<b>如实报 truncated</b>。
   */
  static final int MAX_PAGES = 25;

  /** 上游平台订单里「已取消」的归一状态（取消的订单不参与延迟结算）。 */
  static final String CANCELLED = "CANCELLED";

  /** 上游发货记录里「已作废」的状态（作废的发货记录不算已发货）。 */
  static final String VOID_STATUS = "VOID";

  /** 上游平台订单一行（只声明本模块真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record TradePlatformOrderRow(
      UUID id,
      @JsonIgnoreProperties(ignoreUnknown = true) UUID salesOrderId,
      @JsonIgnoreProperties(ignoreUnknown = true) String orderNo,
      @JsonIgnoreProperties(ignoreUnknown = true) String platformCode,
      @JsonIgnoreProperties(ignoreUnknown = true) String shopCode,
      @JsonIgnoreProperties(ignoreUnknown = true) String externalOrderNo,
      @JsonIgnoreProperties(ignoreUnknown = true) String normalizedStatus,
      @JsonIgnoreProperties(ignoreUnknown = true) String currency,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal platformGrandTotalAmount,
      @JsonIgnoreProperties(ignoreUnknown = true) OffsetDateTime platformPlacedAt) {}

  /** 上游一页平台订单。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record TradePlatformOrderPage(List<TradePlatformOrderRow> rows, int page, int size, long total) {}

  /** 上游发货记录一行（只声明本模块真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record TradeShipmentRecordRow(
      UUID id,
      @JsonIgnoreProperties(ignoreUnknown = true) String recordNo,
      @JsonIgnoreProperties(ignoreUnknown = true) UUID salesOrderId,
      @JsonIgnoreProperties(ignoreUnknown = true) String salesOrderNo,
      @JsonIgnoreProperties(ignoreUnknown = true) String shopRef,
      @JsonIgnoreProperties(ignoreUnknown = true) OffsetDateTime shippedAt,
      @JsonIgnoreProperties(ignoreUnknown = true) String status) {}

  /** 上游一页发货记录。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record TradeShipmentRecordPage(
      List<TradeShipmentRecordRow> items, int page, int size, long totalElements, int totalPages) {}

  /**
   * 上游 JSON 的 mapper。
   *
   * <p>两处配置都不是可有可无的：{@code JavaTimeModule}（上游带 {@code shippedAt} 等时间字段），
   * 以及关掉 {@code FAIL_ON_UNKNOWN_PROPERTIES}（上游<b>加字段</b>不应该把本模块撑爆；
   * 类型不匹配照样炸 —— 那种是真读错了，必须炸）。
   */
  private static final ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private final RestClient client;

  public TradeOrderShipmentPort(@Value("${TRADE_URL:http://127.0.0.1:8085}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    // 读超时给到 15 秒：一次扫描要连续翻多页，比单条读慢一截，
    // 但仍然必须有上限 —— 没有上限时 trade 一慢就把本服务的请求线程全占住。
    factory.setReadTimeout(Duration.ofSeconds(15));
    this.client =
        RestClient.builder()
            .baseUrl(url)
            .requestFactory(factory)
            .messageConverters(java.util.List.of(new MappingJackson2HttpMessageConverter(JSON)))
            .build();
  }

  /**
   * 拉一页上游平台订单。
   *
   * @param bearer 调用方自己的会话（租户由 trade 侧按已认证成员关系解析）
   */
  public TradePlatformOrderPage orders(int page, String bearer) {
    try {
      TradePlatformOrderPage result =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(ORDERS_PATH)
                          .queryParam("page", Math.max(page, 0))
                          .queryParam("size", PAGE_SIZE)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(TradePlatformOrderPage.class);
      if (result == null || result.rows() == null) {
        throw unavailable("trade 服务没有返回平台订单列表");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED,
            "调用方无权读取上游平台订单事实（需要 trade 侧的 trade.orders.read 权限）",
            e);
      }
      throw unavailable("trade 服务返回 " + e.getStatusCode().value() + "：平台订单事实读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("trade 服务不可达：平台订单事实读不到");
    }
  }

  /**
   * 拉一页上游发货记录。
   *
   * @param bearer 调用方自己的会话（租户由 trade 侧按已认证成员关系解析）
   */
  public TradeShipmentRecordPage shipments(int page, String bearer) {
    try {
      TradeShipmentRecordPage result =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(SHIPMENTS_PATH)
                          .queryParam("page", Math.max(page, 0))
                          .queryParam("size", PAGE_SIZE)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(TradeShipmentRecordPage.class);
      if (result == null || result.items() == null) {
        throw unavailable("trade 服务没有返回发货记录列表");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED,
            "调用方无权读取上游发货记录事实（需要 trade 侧的 trade.fulfillment.read 权限）",
            e);
      }
      throw unavailable("trade 服务返回 " + e.getStatusCode().value() + "：发货记录事实读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("trade 服务不可达：发货记录事实读不到");
    }
  }

  /** 上游不可用一律 503，理由写明「读不到」而不是「没有数据」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }
}
