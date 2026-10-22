package com.starcon.crm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 15.2.1.1 评价列表的<b>关联订单上下文 · 跨服务只读端口</b>。
 *
 * <p>蓝图 15.2.1.1 的评价里有一列「<b>关联订单</b>」，而加粗句是
 * 「<b>连续下滑要先查是产品问题还是履约问题</b>」——
 * 要回答「这是产品问题还是履约问题」，光有订单号**不够**：需要知道
 * <b>这张单签收了没有、什么时候签的、走的哪家承运商</b>。
 *
 * <p>🔴 <b>订单事实不在本服务</b>：
 *
 * <ul>
 *   <li><b>订单事实在 trade 服务</b>（9.x，{@code sales_order}）：订单号、状态、来源。
 *   <li><b>履约签收事实在 channel 服务</b>（10.3.2.2，{@code logistics_pickup_signoff_case}）：
 *       签收时刻、履约状态、承运商。
 * </ul>
 *
 * AGENTS.md 模块边界纪律禁止跨服务 SQL / 跨库 FK / 共享视图，所以本端口
 * <b>只经这两个服务的授权 HTTP API</b>读，<b>不直连对方库</b>、<b>不建跨库视图</b>、
 * <b>不建跨库外键</b>。本库只存上游的**逻辑引用**（{@code sales_order_no}）。
 *
 * <p>🔴 端口<b>不传租户号</b>：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * 上游仍按已认证成员关系解析租户并强制 RLS。租户隔离因此是<b>两道</b>
 * （crm 侧 RLS + 上游侧 RLS），不是本服务替对方决定租户。
 *
 * <p>🔴 <b>取不到就显式失败，绝不返回空集合冒充「上游没有订单」</b>：
 * 上游不可达 ⇒ {@code available=false} + 一句能读的原因；
 * 上游可达但**确实没有**这条订单 ⇒ {@code available=false} + 「上游没有这条订单的记录」。
 * 这两种都必须让页面显示出来 —— 客服看到一片空白会以为这条评价没有关联订单，
 * 而真相可能是「上游挂了」，那会让客服去查错的方向。
 *
 * <p><b>为什么不是复用 {@link SignoffDeliveredOrderPort}</b>：
 * 那个端口是 15.2.2.1 索评的「<b>已签收</b>候选集」，它**硬筛 {@code status=DELIVERED}** ——
 * 本模块要的恰恰是「<b>还没签收</b>」这一档（那正是履约问题的信号）。
 * 所以这里另起一个端口读**不带状态筛**的确认单清单，<b>不改兄弟模块的文件</b>。
 */
@Service
public class ReviewOrderContextPort {

  /** 订单事实：trade 服务的销售订单清单（端口 8085）。 */
  static final String TRADE_ORDERS_PATH = "/trade/sales-orders";

  /** 履约事实：channel 服务的签收确认单清单（端口 8083）。 */
  static final String SIGNOFF_CASES_PATH = "/channel/logistics-pickup-signoff/cases";

  /** 上游分页上限（两个上游都把 size 钳在 200 以内）。 */
  static final int PAGE_SIZE = 200;

  /** 上游一行销售订单（只声明本模块真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record SalesOrderRow(
      UUID id,
      String orderNo,
      String orderSource,
      String externalOrderNo,
      String status,
      String currency,
      String customerName) {}

  /** 上游销售订单页的形状。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record SalesOrderPage(List<SalesOrderRow> rows, int page, int size, long total) {}

  /** 上游一行签收确认单（只声明本模块真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record SignoffCaseRow(
      UUID id,
      String caseNo,
      String salesOrderNo,
      String carrierCode,
      OffsetDateTime handoverAt,
      OffsetDateTime deliveryConfirmedAt,
      String status,
      String deliveryState) {}

  /** 上游签收确认单页的形状。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record SignoffCasePage(List<SignoffCaseRow> items, int page, int size, long totalElements) {}

  /** 上游 JSON 的 mapper（同 {@code SignoffDeliveredOrderPort}：加字段不该把本模块撑爆）。 */
  private static final ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  /**
   * 一次订单上下文读取的结论。
   *
   * @param available 订单事实**是否取到**（false ⇒ 页面必须显示 {@code reason}）
   * @param reason 人能读的原因（读不到 / 读到了但没有履约记录时必填）
   */
  public record OrderContext(
      boolean available,
      String reason,
      String salesOrderNo,
      String orderNo,
      String orderStatus,
      String orderSource,
      String deliveredAt,
      String deliveryState,
      String carrierCode,
      String caseNo) {}

  private final RestClient trade;
  private final RestClient channel;

  public ReviewOrderContextPort(
      @Value("${TRADE_URL:http://127.0.0.1:8085}") String tradeUrl,
      @Value("${CHANNEL_URL:http://127.0.0.1:8083}") String channelUrl) {
    this.trade = client(tradeUrl);
    this.channel = client(channelUrl);
  }

  private static RestClient client(String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    // 读超时给到 8 秒：一次上下文要连读两个上游，比单条读慢一截，
    // 但仍然必须有上限 —— 没有上限时上游一慢就把本服务的请求线程全占住。
    factory.setReadTimeout(Duration.ofSeconds(8));
    return RestClient.builder()
        .baseUrl(url)
        .requestFactory(factory)
        .messageConverters(java.util.List.of(new MappingJackson2HttpMessageConverter(JSON)))
        .build();
  }

  /**
   * 读一条评价的关联订单上下文。
   *
   * <p>两个上游**分别**读：**一个挂掉不会把另一个的结论一起抹掉** ——
   * trade 挂了但 channel 正常时，客服至少还能看到履约事实。
   */
  public OrderContext context(String salesOrderNo, String bearer) {
    String orderNo = nz(salesOrderNo).trim();
    if (orderNo.isEmpty()) {
      // 没有订单号的评价不是「读不到」，是**本来就没有**关联订单。
      return new OrderContext(
          false,
          "这条评价没有登记关联订单号：评价可能来自没有订单的浏览/互动，"
              + "或者登记时漏填了订单号（可以在详情里补上）。",
          null, null, null, null, null, null, null, null);
    }

    // ---- 订单事实（trade）----
    SalesOrderRow order = null;
    String orderProblem;
    try {
      SalesOrderPage page =
          trade
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(TRADE_ORDERS_PATH)
                          .queryParam("keyword", orderNo)
                          .queryParam("page", 0)
                          .queryParam("size", PAGE_SIZE)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(SalesOrderPage.class);
      if (page == null || page.rows() == null) {
        orderProblem = "订单事实读不到：trade 服务没有返回销售订单清单。";
      } else {
        order = exactOrder(page.rows(), orderNo);
        orderProblem =
            order == null ? "上游 trade 服务里没有订单号 " + orderNo + " 的记录。" : null;
      }
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        orderProblem = "调用方无权读取上游订单事实（trade 服务返回 " + e.getStatusCode().value() + "）。";
      } else {
        orderProblem = "订单事实读不到：trade 服务返回 " + e.getStatusCode().value() + "。";
      }
    } catch (org.springframework.web.client.ResourceAccessException e) {
      orderProblem = "订单事实读不到：trade 服务不可达。";
    }

    if (order == null) {
      return new OrderContext(
          false, orderProblem, orderNo, null, null, null, null, null, null, null);
    }

    // ---- 履约事实（channel）----
    SignoffCaseRow signoff = null;
    String signoffProblem = null;
    try {
      SignoffCasePage page =
          channel
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(SIGNOFF_CASES_PATH)
                          .queryParam("keyword", orderNo)
                          .queryParam("page", 0)
                          .queryParam("size", PAGE_SIZE)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(SignoffCasePage.class);
      if (page == null || page.items() == null) {
        signoffProblem = "履约事实读不到：channel 服务没有返回签收确认单清单。";
      } else {
        signoff = exactCase(page.items(), orderNo);
        if (signoff == null) {
          signoffProblem =
              "订单已定位，但上游 channel 服务里没有这张单的签收记录"
                  + "（可能还没揽收，或者物流事实还没同步过来）——"
                  + "这本身就是一条履约信号：评价在货还没到的时候就已经写下了。";
        }
      }
    } catch (RestClientResponseException e) {
      signoffProblem =
          "履约事实读不到：channel 服务返回 " + e.getStatusCode().value() + "。";
    } catch (org.springframework.web.client.ResourceAccessException e) {
      signoffProblem = "履约事实读不到：channel 服务不可达。";
    }

    return new OrderContext(
        true,
        signoffProblem,
        orderNo,
        nz(order.orderNo()),
        nz(order.status()),
        nz(order.orderSource()),
        signoff == null || signoff.deliveryConfirmedAt() == null
            ? null
            : signoff.deliveryConfirmedAt().toString(),
        signoff == null ? null : nz(signoff.deliveryState()),
        signoff == null ? null : nz(signoff.carrierCode()),
        signoff == null ? null : nz(signoff.caseNo()));
  }

  /**
   * 在上游返回的行里找**订单号完全相等**的那一行。
   *
   * <p>🔴 为什么要精确匹配：上游的 keyword 是 {@code ilike '%关键字%'}，
   * 订单号 {@code SO-1} 会把 {@code SO-10}、{@code XSO-1} 一并捞回来 ——
   * 而「SO-10 的履约事实」挂到「SO-1 的评价」上，是**比查不到更糟**的错误。
   */
  private static SalesOrderRow exactOrder(List<SalesOrderRow> rows, String orderNo) {
    for (var row : rows) {
      if (row == null) continue;
      if (orderNo.equalsIgnoreCase(nz(row.orderNo()))
          || orderNo.equalsIgnoreCase(nz(row.externalOrderNo()))) {
        return row;
      }
    }
    return null;
  }

  /** 同上：在上游确认单里找**订单号完全相等**的那一行。 */
  private static SignoffCaseRow exactCase(List<SignoffCaseRow> rows, String orderNo) {
    SignoffCaseRow hit = null;
    for (var row : rows) {
      if (row == null) continue;
      if (!orderNo.equalsIgnoreCase(nz(row.salesOrderNo()))) continue;
      // 已签收的那一行优先：它带签收时刻，是「履约完成」的凭据。
      if ("DELIVERED".equals(row.status())) {
        return row;
      }
      if (hit == null) hit = row;
    }
    return hit;
  }

  private static String nz(String value) {
    return value == null ? "" : value.trim();
  }
}
