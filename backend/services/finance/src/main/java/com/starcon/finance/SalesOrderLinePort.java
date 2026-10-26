package com.starcon.finance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
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
 * 读取上游 **9.1.1.1 销售订单（订单 + 订单行）**事实的**跨服务只读端口**（trade 服务，端口 8085）。
 *
 * <p>12.4.2.2 分摊的 SKU / SPU / 类目三个维度的**分摊基数**（数量、金额）来自这里：
 * 一笔费用按「这个期间卖了多少、卖了多少钱」摊到 SKU 身上，是 ERP 里最常见的一种分摊口径。
 *
 * <p>读的是两个上游只读端点：
 *
 * <ul>
 *   <li>{@code GET /trade/sales-orders/list} —— 订单头（订单号、币种、总额、下单时间）；
 *   <li>{@code GET /trade/sales-orders/detail/{id}} —— 订单行（SKU、数量、行小计）。
 * </ul>
 *
 * <p>⚠️ <b>跨服务只走授权 HTTP API</b>：AGENTS.md 的模块边界纪律禁止跨服务 SQL / 跨库 FK / 共享视图。
 * 本端口不直连对方库、不建跨库视图、不建跨库外键。
 *
 * <p>⚠️ <b>租户不由本端口传递</b>：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * trade 侧仍按已认证成员关系解析租户并强制 RLS。租户隔离因此是<b>两道</b>
 * （finance 侧 RLS + trade 侧 RLS），而不是「本服务替对方决定租户」。
 *
 * <p>用法与既有 {@code TradeOrderShipmentPort} 逐字同构：取不到就<b>显式失败</b>（503），
 * 绝不返回空集合冒充「上游没有订单」—— 那会让现场看到 0 个分摊对象，
 * 照着空名单以为「这个期间没卖过东西」。
 */
@Service
public class SalesOrderLinePort {

  /** 上游只读形状：{@code GET /trade/sales-orders/list}（9.1.1.1 订单列表）。 */
  static final String ORDERS_PATH = "/trade/sales-orders/list";

  /** 上游只读形状：{@code GET /trade/sales-orders/detail/{id}}（9.1.1.1 订单全景详情）。 */
  static final String DETAIL_PATH = "/trade/sales-orders/detail/";

  /** 上游分页上限。 */
  static final int PAGE_SIZE = 200;

  /** 上游 JSON 的 mapper（上游带时间字段，且会加字段 —— 加字段不该把本模块撑爆）。 */
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  /** 上游订单头一行（只声明本模块真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record SalesOrderRow(
      UUID id,
      @JsonIgnoreProperties(ignoreUnknown = true) String orderNo,
      @JsonIgnoreProperties(ignoreUnknown = true) String currency,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal grandTotalAmount,
      @JsonIgnoreProperties(ignoreUnknown = true) String documentStatus,
      @JsonIgnoreProperties(ignoreUnknown = true) OffsetDateTime submittedAt,
      @JsonIgnoreProperties(ignoreUnknown = true) OffsetDateTime createdAt) {}

  /** 上游订单列表一页。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record SalesOrderPage(List<SalesOrderRow> rows, int page, int size, long total) {}

  /** 上游订单行一行（只声明本模块真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record SalesOrderLineRow(
      UUID id,
      @JsonIgnoreProperties(ignoreUnknown = true) int lineNo,
      @JsonIgnoreProperties(ignoreUnknown = true) UUID skuId,
      @JsonIgnoreProperties(ignoreUnknown = true) String skuCode,
      @JsonIgnoreProperties(ignoreUnknown = true) String skuName,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal quantity,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal lineAmount) {}

  /** 上游订单全景详情（只取订单行那一段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record SalesOrderDetail(
      @JsonIgnoreProperties(ignoreUnknown = true) List<SalesOrderLineRow> lines) {}

  private final RestClient client;

  public SalesOrderLinePort(@Value("${TRADE_URL:http://127.0.0.1:8085}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    // 读超时给到 15 秒：订单详情比列表慢一截，但仍然必须有上限 ——
    // 没有上限时 trade 一慢就把本服务的请求线程全占住。
    factory.setReadTimeout(Duration.ofSeconds(15));
    this.client =
        RestClient.builder()
            .baseUrl(url)
            .requestFactory(factory)
            .messageConverters(java.util.List.of(new MappingJackson2HttpMessageConverter(JSON)))
            .build();
  }

  /**
   * 拉一页上游销售订单。
   *
   * @param currency 币种过滤（**绝不跨币种相加**：分摊基数必须与池的币种同口径）
   * @param from 下单时间下界（含）
   * @param to 下单时间上界（含）
   * @param bearer 调用方自己的会话（租户由 trade 侧按已认证成员关系解析）
   */
  public SalesOrderPage orders(
      int page, String currency, OffsetDateTime from, OffsetDateTime to, String bearer) {
    try {
      SalesOrderPage result =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(ORDERS_PATH)
                          .queryParam("page", Math.max(page, 0))
                          .queryParam("size", PAGE_SIZE)
                          .queryParam("currency", currency)
                          .queryParam("orderedFrom", from)
                          .queryParam("orderedTo", to)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(SalesOrderPage.class);
      if (result == null || result.rows() == null) {
        throw unavailable("trade 服务没有返回销售订单列表");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED,
            "调用方无权读取上游销售订单事实（需要 trade 侧的 trade.orders.read 权限）",
            e);
      }
      throw unavailable("trade 服务返回 " + e.getStatusCode().value() + "：销售订单事实读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("trade 服务不可达：销售订单事实读不到");
    }
  }

  /**
   * 拉一张订单的行（SKU + 数量 + 行小计）。
   *
   * @param bearer 调用方自己的会话（租户由 trade 侧按已认证成员关系解析）
   */
  public List<SalesOrderLineRow> lines(UUID orderId, String bearer) {
    try {
      SalesOrderDetail result =
          client
              .get()
              .uri(DETAIL_PATH + orderId)
              .header("Authorization", bearer)
              .retrieve()
              .body(SalesOrderDetail.class);
      if (result == null || result.lines() == null) {
        throw unavailable("trade 服务没有返回订单 " + orderId + " 的行");
      }
      return result.lines();
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED,
            "调用方无权读取上游订单行事实（需要 trade 侧的 trade.orders.read 权限）",
            e);
      }
      throw unavailable("trade 服务返回 " + e.getStatusCode().value() + "：订单行事实读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("trade 服务不可达：订单行事实读不到");
    }
  }

  /** 上游不可用一律 503，理由写明「读不到」而不是「没有数据」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }
}
