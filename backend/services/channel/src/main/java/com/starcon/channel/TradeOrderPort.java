package com.starcon.channel;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
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
 * 读取 **trade 服务 9 订单**的**跨服务只读端口**（IOSS 订单识别的数据来源）。
 *
 * <p>蓝图 16.3.2.1 的输入写「订单税务数据」、关系写「上游 = 9 订单」。
 * 9 订单的**主责是 trade + channel**，而**本仓的订单事实在 trade 服务**：
 *
 * <ul>
 *   <li>{@code platform_order}（V207，平台单投影）—— 有**店铺**（{@code shop_code}）、
 *       币种、平台口径的金额与下单时刻；</li>
 *   <li>{@code sales_order}（V99，统一销售订单）—— 有**收货国家**、买家类型、行明细。</li>
 * </ul>
 *
 * <p>🔴 **为什么读两个端点**：IOSS 判定同时需要**店铺**（判「适用店铺」）与
 * **收货国家 / 买家类型 / 行明细**（判「欧盟 B2C / 非应税消费品 / 货值」），
 * 而这两组字段在 trade 的两个端点里。本端口**只读**，不建跨库 FK / 视图。
 *
 * <p>⚠️ AGENTS.md 模块边界纪律禁止跨服务 SQL / 跨库 FK / 共享视图，所以本端口
 * <b>只经 trade 的授权 HTTP API</b>读。
 *
 * <p>⚠️ <b>租户不由本端口传递</b>：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * trade 侧仍按已认证成员关系解析租户并强制 RLS。
 *
 * <p>🔴 <b>读不到就整体失败</b>（503），绝不返回空集合冒充「上游没有订单」——
 * 那会让统计凭空少一截，却看起来像「这段时间没卖货」。
 */
@Service
public class TradeOrderPort {

  /** 上游只读形状：平台单列表。 */
  static final String PLATFORM_ORDERS_PATH = "/trade/platform-orders/orders";

  /** 上游只读形状：统一销售订单详情。 */
  static final String SALES_ORDER_PATH = "/trade/sales-orders/";

  /** 单页拉多少行。上游把 size 钳在 200 以内，超了会被拒。 */
  static final int PAGE_SIZE = 200;

  /**
   * 最多翻多少页（{@code 200 × 25 = 5000} 单）。
   *
   * <p>必须有上限：翻页是无界的，一个宽时间窗 + 大租户能把本服务的请求线程占满。
   * 触到上限时<b>如实报 truncated</b>（而不是悄悄少算）。
   */
  static final int MAX_PAGES = 25;

  /** 上游 JSON 的 mapper。 */
  private static final ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  /**
   * 上游一行平台单（只声明本模块真正用到的字段）。
   *
   * <p>金额口径（与 trade 的 {@code totals(...)} 逐条对齐）：
   *
   * <ul>
   *   <li>{@code platformOriginalAmount} = Σ 行小计（**不含**折扣 / 税 / 运费）</li>
   *   <li>{@code platformDiscountAmount} = 折扣（**正数**）</li>
   *   <li>{@code platformTaxAmount} = 税额</li>
   *   <li>{@code platformShippingAmount} = 运费</li>
   * </ul>
   */
  record PlatformOrderRow(
      UUID id,
      UUID salesOrderId,
      String orderNo,
      String platformCode,
      String shopCode,
      String externalOrderNo,
      String orderSource,
      String normalizedStatus,
      String currency,
      BigDecimal platformOriginalAmount,
      BigDecimal platformDiscountAmount,
      BigDecimal platformTaxAmount,
      BigDecimal platformShippingAmount,
      BigDecimal platformGrandTotalAmount,
      OffsetDateTime platformPlacedAt) {}

  /** 上游一页平台单（字段名是上游自己的 {@code rows}）。 */
  record PlatformOrderPage(List<PlatformOrderRow> rows, int page, int size, long total) {}

  /** 上游统一销售订单的一行。 */
  record SalesOrderRow(
      UUID id,
      String orderNo,
      String orderSource,
      String manualKind,
      String externalOrderNo,
      String currency,
      String status,
      String paymentMethod,
      int paymentTermsDays,
      String customerPartyId,
      String customerName,
      int lineCount,
      BigDecimal grandTotalAmount,
      OffsetDateTime submittedAt,
      OffsetDateTime createdAt,
      long version) {}

  /** 上游买家快照（B2C / B2B）。 */
  record SalesOrderCustomerRow(
      UUID customerPartyId,
      String customerCode,
      String customerName,
      String customerType,
      String customerDefaultCurrency) {}

  /** 上游订单行。 */
  record SalesOrderLineRow(
      UUID id,
      int lineNo,
      UUID skuId,
      String skuCode,
      String skuName,
      BigDecimal quantity,
      BigDecimal unitPrice,
      BigDecimal discountAmount,
      BigDecimal taxAmount,
      BigDecimal lineAmount,
      String note) {}

  /** 上游地址（收货地址带国家码）。 */
  record SalesOrderAddressRow(
      UUID id,
      String addressRole,
      String recipientName,
      String countryCode,
      String province,
      String city,
      String postcode,
      String note) {}

  /** 上游销售订单详情（只取本模块用得到的四块）。 */
  record SalesOrderDetail(
      SalesOrderRow order,
      SalesOrderCustomerRow customer,
      List<SalesOrderLineRow> lines,
      List<SalesOrderAddressRow> addresses) {}

  /** 一次整窗扫描的结果。 */
  record PlatformOrderScan(
      List<PlatformOrderRow> items, long total, boolean truncated, int pagesFetched) {}

  private final RestClient client;

  public TradeOrderPort(@Value("${TRADE_URL:http://127.0.0.1:8085}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(10));
    this.client =
        RestClient.builder()
            .baseUrl(url)
            .requestFactory(factory)
            .messageConverters(
                java.util.List.of(new MappingJackson2HttpMessageConverter(JSON)))
            .build();
  }

  /** 扫描平台单（按页翻，翻到 {@code total} 为止）。 */
  public PlatformOrderScan scan(String shopCode, String bearer) {
    List<PlatformOrderRow> collected = new ArrayList<>();
    long total = 0;
    boolean truncated = false;
    int pages = 0;
    for (int page = 0; page < MAX_PAGES; page++) {
      PlatformOrderPage result = fetchPage(shopCode, page, bearer);
      total = result.total();
      pages = page + 1;
      if (result.rows() != null) {
        collected.addAll(result.rows());
      }
      if (collected.size() >= total) {
        break;
      }
      if (result.rows() == null || result.rows().isEmpty()) {
        break;
      }
      if (page == MAX_PAGES - 1) {
        truncated = true;
      }
    }
    return new PlatformOrderScan(List.copyOf(collected), total, truncated, pages);
  }

  private PlatformOrderPage fetchPage(String shopCode, int page, String bearer) {
    try {
      PlatformOrderPage result =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(PLATFORM_ORDERS_PATH)
                          .queryParam("shopCode", shopCode)
                          .queryParam("page", page)
                          .queryParam("size", PAGE_SIZE)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(PlatformOrderPage.class);
      if (result == null || result.rows() == null) {
        throw unavailable("trade 服务没有返回平台单清单");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED, "调用方无权读取上游订单（需要 trade 侧的读权限）", e);
      }
      throw unavailable("trade 服务返回 " + e.getStatusCode().value() + "：订单清单读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("trade 服务不可达");
    }
  }

  /** 取一张统一销售订单的详情（收货国家 / 买家类型 / 行明细都在这里）。 */
  public SalesOrderDetail detail(UUID salesOrderId, String bearer) {
    try {
      SalesOrderDetail result =
          client
              .get()
              .uri(SALES_ORDER_PATH + salesOrderId)
              .header("Authorization", bearer)
              .retrieve()
              .body(SalesOrderDetail.class);
      if (result == null || result.order() == null) {
        throw unavailable("trade 服务没有返回订单详情");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED, "调用方无权读取上游订单详情（需要 trade 侧的读权限）", e);
      }
      throw unavailable("trade 服务返回 " + e.getStatusCode().value() + "：订单详情读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("trade 服务不可达");
    }
  }

  /** 上游不可用一律 503，理由写明「读不到」而不是「没有数据」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }
}
