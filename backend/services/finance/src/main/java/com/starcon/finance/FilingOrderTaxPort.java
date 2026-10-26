package com.starcon.finance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
 * 读取上游 **9 订单（trade 服务）**税务事实的**跨服务只读端口**。
 *
 * <p>12.6.1.2 的输入之一是「订单税务数据」：应税销售额、已收 VAT 都只能来自订单侧。
 * 订单在 **trade 服务**（9.1.1.1 销售订单 + 9.1.2.2 手工单 + 9.1.2.1 平台同步共用
 * 一批表），**不在 finance 库**。
 *
 * <p>⚠️ <b>跨服务只走授权 HTTP API</b>：AGENTS.md 的模块边界纪律禁止跨服务 SQL / 跨库 FK /
 * 共享视图。本端口不直连对方库、不建跨库视图、不建跨库外键。
 *
 * <p>⚠️ <b>租户不由本端口传递</b>：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * trade 侧仍按已认证成员关系解析租户并强制 RLS。租户隔离因此是<b>两道</b>
 * （finance 侧 RLS + trade 侧 RLS），而不是「本服务替对方决定租户」。
 *
 * <p>🔴 <b>本端口与 12.4.2.2 的 {@code SalesOrderLinePort} 是两个端口</b>：那个端口读的是
 * 「分摊基数」（数量 + 行小计），本端口读的是「申报口径的订单税务事实」（小计 / 折扣 /
 * 税额 / 币种 / 收货国）。**不并进那个端口** —— 它是别的模块的文件，改它会与别的车道冲突。
 *
 * <p>🔴 <b>读不到就显式失败（503），绝不返回空集合</b>：空集合会让现场以为「这个期间
 * 一单都没有」，照着空底稿去申报。
 */
@Service
public class FilingOrderTaxPort {

  /** 上游只读形状：{@code GET /trade/sales-orders/list}（9.1.1.1 订单列表）。 */
  static final String ORDERS_PATH = "/trade/sales-orders/list";

  /** 上游只读形状：{@code GET /trade/sales-orders/detail/{id}}（9.1.1.2 订单全景详情）。 */
  static final String DETAIL_PATH = "/trade/sales-orders/detail/";

  /** 上游分页上限。 */
  static final int PAGE_SIZE = 200;

  /** 上游 JSON 的 mapper（上游会加字段 —— 加字段不该把本模块撑爆）。 */
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  /** 上游订单列表一行（只声明本模块真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record OrderRow(
      UUID id,
      @JsonIgnoreProperties(ignoreUnknown = true) String orderNo,
      @JsonIgnoreProperties(ignoreUnknown = true) String orderSource,
      @JsonIgnoreProperties(ignoreUnknown = true) String documentStatus,
      @JsonIgnoreProperties(ignoreUnknown = true) String currency,
      @JsonIgnoreProperties(ignoreUnknown = true) OffsetDateTime submittedAt) {}

  /** 上游订单列表一页。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record OrderPage(List<OrderRow> rows, int page, int size, long total) {}

  /** 上游订单头（申报口径的税额真值）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record OrderHeader(
      UUID id,
      @JsonIgnoreProperties(ignoreUnknown = true) String orderNo,
      @JsonIgnoreProperties(ignoreUnknown = true) String orderSource,
      @JsonIgnoreProperties(ignoreUnknown = true) String documentStatus,
      @JsonIgnoreProperties(ignoreUnknown = true) String currency,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal subtotalAmount,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal discountAmount,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal taxAmount,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal shippingAmount,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal otherAmount,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal grandTotalAmount,
      @JsonIgnoreProperties(ignoreUnknown = true) OffsetDateTime submittedAt) {}

  /** 上游地址快照一行（**收货国** = 目的国 / 辖区归属的判据）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record OrderAddress(
      @JsonIgnoreProperties(ignoreUnknown = true) String addressRole,
      @JsonIgnoreProperties(ignoreUnknown = true) String countryCode) {}

  /** 上游订单全景详情（本模块只取头 + 地址两段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record OrderDetail(
      @JsonIgnoreProperties(ignoreUnknown = true) OrderHeader header,
      @JsonIgnoreProperties(ignoreUnknown = true) List<OrderAddress> addresses) {}

  private final RestClient client;

  public FilingOrderTaxPort(@Value("${TRADE_URL:http://127.0.0.1:8085}") String url) {
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
   * 拉一页覆盖期间内的订单。
   *
   * @param orderSource 订单来源过滤（可空 = 全部来源）
   * @param from 下单时间下界（含）
   * @param to 下单时间上界（含）
   * @param bearer 调用方自己的会话（租户由 trade 侧按已认证成员关系解析）
   */
  public OrderPage orders(String orderSource, OffsetDateTime from, OffsetDateTime to, String bearer) {
    try {
      OrderPage result =
          client
              .get()
              .uri(
                  uriBuilder -> {
                    var b = uriBuilder.path(ORDERS_PATH);
                    b.queryParam("page", 0);
                    b.queryParam("size", PAGE_SIZE);
                    if (orderSource != null && !orderSource.isBlank()) {
                      b.queryParam("orderSource", orderSource.trim());
                    }
                    if (from != null) b.queryParam("orderedFrom", from);
                    if (to != null) b.queryParam("orderedTo", to);
                    return b.build();
                  })
              .header("Authorization", bearer)
              .retrieve()
              .body(OrderPage.class);
      if (result == null || result.rows() == null) {
        throw unavailable("trade 服务没有返回销售订单列表");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED,
            "调用方无权读取上游订单税务事实（需要 trade 侧的 trade.orders.read 权限）",
            e);
      }
      throw unavailable("trade 服务返回 " + e.getStatusCode().value() + "：订单税务事实读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("trade 服务不可达：订单税务事实读不到");
    }
  }

  /**
   * 拉一张订单的申报口径事实（订单头金额 + 收货国）。
   *
   * @param bearer 调用方自己的会话
   */
  public OrderDetail detail(UUID orderId, String bearer) {
    try {
      OrderDetail result =
          client
              .get()
              .uri(DETAIL_PATH + orderId)
              .header("Authorization", bearer)
              .retrieve()
              .body(OrderDetail.class);
      if (result == null || result.header() == null) {
        throw unavailable("trade 服务没有返回订单 " + orderId + " 的明细");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED,
            "调用方无权读取上游订单税务事实（需要 trade 侧的 trade.orders.read 权限）",
            e);
      }
      throw unavailable("trade 服务返回 " + e.getStatusCode().value() + "：订单税务事实读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("trade 服务不可达：订单税务事实读不到");
    }
  }

  /**
   * 订单的<b>收货国</b>（ISO alpha-2）：申报辖区归属的判据。
   *
   * <p>收货地址是「货到哪」，也就是 VAT / IOSS 的**目的国**；账单地址不是。
   */
  static String shippingCountry(OrderDetail detail) {
    if (detail.addresses() == null) return "";
    for (var address : detail.addresses()) {
      if (address != null
          && "SHIPPING".equalsIgnoreCase(address.addressRole() == null ? "" : address.addressRole().trim())
          && address.countryCode() != null
          && !address.countryCode().isBlank()) {
        return address.countryCode().trim().toUpperCase(java.util.Locale.ROOT);
      }
    }
    return "";
  }

  /**
   * 覆盖期间的边界（**左闭右闭**的日历日，转成上游要的 OffsetDateTime）。
   *
   * <p>期间结束日取当天的 23:59:59.999 —— 订单落在当天 12:00 时不能被漏掉。
   */
  static OffsetDateTime periodStart(LocalDate start) {
    return start.atStartOfDay().atOffset(ZoneOffset.UTC);
  }

  /** 期间结束（含当天最后一刻）。 */
  static OffsetDateTime periodEnd(LocalDate end) {
    return end.atTime(23, 59, 59, 999_000_000).atOffset(ZoneOffset.UTC);
  }

  /** 上游不可用一律 503，理由写明「读不到」而不是「没有数据」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }

  /** 便于测试断言的辅助：把上游明细的地址段摊平成列表。 */
  static List<OrderAddress> addressesOf(OrderDetail detail) {
    return detail.addresses() == null ? new ArrayList<>() : detail.addresses();
  }
}
