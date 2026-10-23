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
 * 读取上游 **10.2.1.1 运单列表**里「运费登记」的**跨服务只读端口**（trade 服务，端口 8085）。
 *
 * <p>蓝图 12.4.2.1 的输入写的是「费用来源（平台、物流、订阅、人工录入）」，其中「物流」这一档
 * 在 12.4.2.1 里是「<b>按单分摊的物流费</b>」—— 它的粒度是**一票运单**（一票 = 一个订单的包裹），
 * 正好对应本端口读到的 {@code logistics_waybill_freight}（10.2.1.1 建的运费登记表）。
 *
 * <p>🔴 <b>为什么不是 10.6.1.1 物流成本分析</b>：那一格记的是**成本估算与分配口径**，
 * 算的是「这批货的成本大概是多少」，<b>不是「这一票实际登记了多少运费」的账单事实</b>。
 * 拿估算值当费用池里的钱，等于把预估当结算。
 *
 * <p>🔴 <b>10.1.2.1 运费计算器 / 10.1.2.2 比价未交付</b>：所以运费**不是算出来的**，
 * 是**登记的**（人工登记或渠道对账单登记）。本端口只读已登记的金额，
 * 读不到就<b>显式失败</b>，绝不返回空集合冒充「上游没有物流费」。
 *
 * <p>用法与既有端口（{@link FirstMilePort} / {@link AdSpendPort}）逐字同构：
 * 取不到就<b>显式失败</b>，绝不返回空集合。
 */
@Service
public class WaybillFreightPort {

  /** 上游只读形状：{@code GET /trade/logistics/waybills/list}（10.2.1.1 运单列表）。 */
  static final String WAYBILL_LIST_PATH = "/trade/logistics/waybills/list";

  /** 上游分页上限（与上游 MAX_PAGE_SIZE 一致）。 */
  static final int PAGE_SIZE = 200;

  /** 最多翻多少页（{@code 200 × 25 = 5000} 票）。 */
  static final int MAX_PAGES = 25;

  /** 上游一行运单（只声明本模块真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record WaybillFreightRow(
      UUID id,
      String requestNo,
      @JsonIgnoreProperties(ignoreUnknown = true) String waybillNo,
      @JsonIgnoreProperties(ignoreUnknown = true) String salesOrderNo,
      @JsonIgnoreProperties(ignoreUnknown = true) String carrierCode,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal freightAmount,
      @JsonIgnoreProperties(ignoreUnknown = true) String freightCurrency,
      @JsonIgnoreProperties(ignoreUnknown = true) String freightGapCode,
      @JsonIgnoreProperties(ignoreUnknown = true) String freightSource,
      @JsonIgnoreProperties(ignoreUnknown = true) OffsetDateTime createdAt,
      @JsonIgnoreProperties(ignoreUnknown = true) OffsetDateTime issuedAt) {}

  /** 上游一页运单。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record WaybillFreightPage(List<WaybillFreightRow> items, int page, int size, long totalElements) {}

  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private final RestClient client;

  public WaybillFreightPort(@Value("${TRADE_URL:http://127.0.0.1:8085}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(15));
    this.client =
        RestClient.builder()
            .baseUrl(url)
            .requestFactory(factory)
            .messageConverters(java.util.List.of(new MappingJackson2HttpMessageConverter(JSON)))
            .build();
  }

  /** 拉一页上游运单。 */
  public WaybillFreightPage rows(int page, String bearer) {
    try {
      WaybillFreightPage result =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(WAYBILL_LIST_PATH)
                          .queryParam("page", Math.max(page, 0))
                          .queryParam("size", PAGE_SIZE)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(WaybillFreightPage.class);
      if (result == null || result.items() == null) {
        throw unavailable("trade 服务没有返回运单列表");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED,
            "调用方无权读取上游运单运费事实（需要 trade 侧的 fulfillment.read 权限）",
            e);
      }
      throw unavailable("trade 服务返回 " + e.getStatusCode().value() + "：运单运费事实读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("trade 服务不可达");
    }
  }

  /**
   * 这一票有没有<b>可归集的运费事实</b>。
   *
   * <p>⚠️ 「未登记」与「登记了 0 元」是**两回事**：前者是缺口（要催着补），后者是合法事实
   * （渠道活动 / 内部调拨）。上游用 {@code freightGapCode} 把两者分开，本模块沿用同一个判据 ——
   * 只有 {@code REGISTERED} 且金额大于 0 才是可归集的费用。
   */
  public static boolean isCollectable(WaybillFreightRow row) {
    return row != null
        && "REGISTERED".equals(row.freightGapCode())
        && row.freightAmount() != null
        && row.freightAmount().signum() > 0
        && row.freightCurrency() != null
        && !row.freightCurrency().isBlank();
  }

  /** 上游不可用一律 503，理由写明「读不到」而不是「没有数据」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }
}
