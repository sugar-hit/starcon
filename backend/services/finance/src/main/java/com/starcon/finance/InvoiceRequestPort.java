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
 * 读取上游 **9 订单**「开票需求」的**跨服务只读端口**（trade 服务，端口 8085）。
 *
 * <p>蓝图 12.6.2.1 的输入写的是「订单与结算数据」。订单侧的**开票需求**落在 trade 的
 * {@code b2b_receivable.invoice_state='REQUESTED'}（9.5.1.2 B2B 批发订单，
 * 该模块明确写着「12.6.2 发票交付后由它消费 invoice_state='REQUESTED'」）。
 *
 * <p>🔴 <b>本端口只读</b>：AGENTS.md 的模块边界纪律规定跨服务只走授权 API，
 * 且本车道纪律表写明「订单与结算数据<b>只读</b>；不要写订单表或结算表」。
 * 所以本模块<b>不回写</b> {@code invoice_state='INVOICED'} —— 那张表的写入所有权在 trade。
 *
 * <p>🔴 <b>租户不由本端口传递</b>：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * trade 侧仍按已认证成员关系解析租户并强制 RLS。租户隔离因此是<b>两道</b>
 * （finance 侧 RLS + trade 侧 RLS），而不是「本服务替对方决定租户」。
 *
 * <p>用法与既有 {@link TradeOrderShipmentPort} 逐字同构：取不到就<b>显式失败</b>（503），
 * 绝不返回空集合冒充「上游没有待开票需求」—— 那会让现场以为一张发票都不用开。
 */
@Service
public class InvoiceRequestPort {

  /** 上游只读形状：{@code GET /trade/b2b-orders/receivables}（9.5.1.2 B2B 批发订单）。 */
  static final String RECEIVABLES_PATH = "/trade/b2b-orders/receivables";

  /** 上游分页上限。 */
  static final int PAGE_SIZE = 200;

  /** 最多翻多少页（{@code 200 × 25 = 5000} 件）。 */
  static final int MAX_PAGES = 25;

  /** 上游「已提出开票需求」的状态取值。 */
  static final String INVOICE_STATE_REQUESTED = "REQUESTED";

  /** 上游一行待开票需求（只声明本模块真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record TradeReceivableRow(
      UUID id,
      @JsonIgnoreProperties(ignoreUnknown = true) String receivableNo,
      @JsonIgnoreProperties(ignoreUnknown = true) UUID orderId,
      @JsonIgnoreProperties(ignoreUnknown = true) String orderNo,
      @JsonIgnoreProperties(ignoreUnknown = true) String customerName,
      @JsonIgnoreProperties(ignoreUnknown = true) String currency,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal amount,
      @JsonIgnoreProperties(ignoreUnknown = true) OffsetDateTime invoiceRequestedAt,
      @JsonIgnoreProperties(ignoreUnknown = true) String invoiceState,
      @JsonIgnoreProperties(ignoreUnknown = true) List<TradeReceivableLineRow> lines) {}

  /** 上游待开票需求的一行明细。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record TradeReceivableLineRow(
      @JsonIgnoreProperties(ignoreUnknown = true) String skuCode,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal quantity,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal unitPrice,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal lineAmount) {}

  /** 上游一页待开票需求。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record TradeReceivablePage(
      @JsonIgnoreProperties(ignoreUnknown = true) List<TradeReceivableRow> items,
      @JsonIgnoreProperties(ignoreUnknown = true) int page,
      @JsonIgnoreProperties(ignoreUnknown = true) int size,
      @JsonIgnoreProperties(ignoreUnknown = true) long total,
      @JsonIgnoreProperties(ignoreUnknown = true) long invoicePending) {}

  private static final ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private final RestClient client;

  public InvoiceRequestPort(@Value("${TRADE_URL:http://127.0.0.1:8085}") String url) {
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
   * 拉一页上游「已提出开票需求」的应收。
   *
   * @param bearer 调用方自己的会话（租户由 trade 侧按已认证成员关系解析）
   */
  public TradeReceivablePage invoiceRequests(int page, String bearer) {
    try {
      TradeReceivablePage result =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(RECEIVABLES_PATH)
                          .queryParam("invoiceState", INVOICE_STATE_REQUESTED)
                          .queryParam("page", Math.max(page, 0))
                          .queryParam("size", PAGE_SIZE)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(TradeReceivablePage.class);
      if (result == null || result.items() == null) {
        throw unavailable("trade 服务没有返回待开票需求列表");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED,
            "调用方无权读取上游待开票需求（需要 trade 侧的 trade.order.read 权限）",
            e);
      }
      throw unavailable("trade 服务返回 " + e.getStatusCode().value() + "：待开票需求读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("trade 服务不可达：待开票需求读不到");
    }
  }

  /** 上游不可用一律 503，理由写明「读不到」而不是「没有数据」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }
}
