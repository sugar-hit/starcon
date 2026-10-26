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
 * 读取上游 **10.4.1.1 头程发运单**事实的**跨服务只读端口**（warehouse 服务，端口 8084）。
 *
 * <p>蓝图 12.1.2.1 的输入写的是「服务费用（物流、平台、广告）」。物流这一段的可产生来源是头程发运单：
 * 它带着<b>承运商</b>、<b>币种</b>与<b>运费 / 保险费 / 处理费</b>——
 * 这三样正是「应付给谁、多少钱、什么币种」的全套依据。
 *
 * <p>🔴 <b>为什么不是 10.6.1.1 物流成本分析</b>：那一格记的是**成本估算与分配口径**
 * （每票包材成本参数、分配规则、按订单/按渠道的分摊视图），
 * 它算的是「这批货的成本大概是多少」，<b>不是「欠承运商多少钱」的账单事实</b>。
 * 拿估算值当应付，等于把预估当结算 —— 所以本模块不接它。
 *
 * <p>⚠️ <b>关税 estimate 不进应付</b>：{@code dutyEstimate} 是**估算**（估算 != 账单），
 * 且真正的关税属 10.5.1.3 的税则口径。只取运费、保险费与处理费这三项**已发生**的费用。
 *
 * <p>用法与既有端口逐字同构：取不到就<b>显式失败</b>，绝不返回空集合冒充「上游没有数据」。
 */
@Service
public class FirstMilePort {

  /** 上游只读形状：{@code GET /warehouse/first-mile-shipments}（10.4.1.1 头程发运单）。 */
  static final String FIRST_MILE_PATH = "/warehouse/first-mile-shipments";

  /** 上游分页上限。 */
  static final int PAGE_SIZE = 200;

  /** 最多翻多少页（{@code 200 × 25 = 5000} 单）。 */
  static final int MAX_PAGES = 25;

  /** 上游一行头程发运单（只声明本模块真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record FirstMileRow(
      UUID id,
      String shipmentCode,
      @JsonIgnoreProperties(ignoreUnknown = true) String carrierName,
      @JsonIgnoreProperties(ignoreUnknown = true) String currency,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal freightCost,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal insuranceCost,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal handlingCost,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal totalCost,
      @JsonIgnoreProperties(ignoreUnknown = true) String status,
      @JsonIgnoreProperties(ignoreUnknown = true) OffsetDateTime dispatchedAt,
      @JsonIgnoreProperties(ignoreUnknown = true) OffsetDateTime expectedArriveAt) {}

  /** 上游一页头程发运单。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record FirstMilePage(List<FirstMileRow> rows, int page, int size, long total) {}

  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private final RestClient client;

  public FirstMilePort(@Value("${WAREHOUSE_URL:http://127.0.0.1:8084}") String url) {
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

  /** 拉一页上游头程发运单。 */
  public FirstMilePage shipments(int page, String bearer) {
    try {
      FirstMilePage result =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(FIRST_MILE_PATH)
                          .queryParam("page", Math.max(page, 0))
                          .queryParam("size", PAGE_SIZE)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(FirstMilePage.class);
      if (result == null || result.rows() == null) {
        throw unavailable("warehouse 服务没有返回头程发运单列表");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED,
            "调用方无权读取上游头程发运单事实（需要 warehouse 侧的 warehouse.inventory.read 权限）",
            e);
      }
      throw unavailable("warehouse 服务返回 " + e.getStatusCode().value() + "：头程发运单事实读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("warehouse 服务不可达");
    }
  }

  /**
   * 把一行头程发运单压成上游事实形状。
   *
   * <p>⚠️ **只取已发生的费用**：运费 + 保险费 + 处理费（关税估算不算，见类注释）。
   * 三项全为 0 ⇒ 返回 {@code null}（没有可产生的应付），调用方据此跳过并说明原因 ——
   * **绝不生成一条 0 元应付**，那在未付清单里就是一行噪声。
   */
  public static PayableSourceFact toFact(FirstMileRow row) {
    BigDecimal freight = zero(row.freightCost());
    BigDecimal insurance = zero(row.insuranceCost());
    BigDecimal handling = zero(row.handlingCost());
    BigDecimal total = freight.add(insurance).add(handling);
    if (total.signum() <= 0) return null;
    if (row.carrierName() == null || row.carrierName().isBlank()) return null;
    return new PayableSourceFact(
        row.shipmentCode(),
        row.id(),
        PayableModels.CounterpartyType.CARRIER,
        null,
        row.carrierName(),
        row.currency(),
        total,
        null,
        null,
        "头程发运单 " + row.shipmentCode() + " 的物流费用（运费 + 保险费 + 处理费）",
        row.dispatchedAt(),
        "由 10.4.1.1 头程发运单导入");
  }

  private static BigDecimal zero(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  /** 上游不可用一律 503，理由写明「读不到」而不是「没有数据」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }
}
