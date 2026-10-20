package com.starcon.channel;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 读取 **warehouse 服务**成本事实的**跨服务只读端口**。
 *
 * <p>蓝图 10.6.1.1 的五分量里有两个在 warehouse：
 *
 * <ul>
 *   <li><b>头程费用</b> —— {@code first_mile_shipment}（V80，10.4.1.1 头程发运单）上的
 *       运费 / 保险 / 关税预估 / 操作费，以及它的逐行明细（SKU × 批次 × 申报信息）。
 *       头程分摊的分摊基准（数量 / 重量 / 申报价值）就来自这些明细行。
 *   <li><b>关税与增值税</b> —— {@code duty_tax_estimate}（V90，10.5.1.3 关税与税费）上的
 *       估算税额、实缴税额与到岸成本（landed cost）。
 * </ul>
 *
 * <p>⚠️ AGENTS.md 模块边界纪律禁止跨服务 SQL / FK / 共享视图，所以本端口
 * <b>只经 warehouse 的授权 HTTP API</b>读，不直连对方库、不建跨库视图、不建跨库外键。
 *
 * <p>租户<b>不</b>由本端口传递租户号：转发的是<b>调用方自己的</b>
 * {@code Authorization} bearer，warehouse 侧仍按已认证成员关系解析租户并强制 RLS。
 * 租户隔离是<b>两道</b>（channel 侧 RLS + warehouse 侧 RLS）。
 *
 * <p>用法与 {@link PendingShipmentQueryPort}（读 trade）逐字同构：取不到就<b>显式失败</b>，
 * 绝不返回空集合冒充「上游没有头程发运单 / 没有税费估算」——
 * 那会让成本报表少算一块而看不出来。
 */
@Service
public class WarehouseCostQueryPort {
  /** 上游只读形状：头程发运单列表与详情。 */
  static final String FIRST_MILE_PATH = "/warehouse/first-mile-shipments";

  /** 上游只读形状：税费估算单列表。 */
  static final String DUTY_TAX_PATH = "/warehouse/duty-taxes";

  /**
   * 上游一张头程发运单（只声明本模块真正用到的字段）。
   *
   * <p>{@code totalCost} 是上游自己的合计（运费 + 保险 + 关税预估 + 操作费），
   * 本模块**不重算**它 —— 重算一遍就会出现「两边各算一次、差一点」的经典账差。
   */
  record FirstMileShipmentView(
      UUID id,
      String shipmentCode,
      String status,
      String transportMode,
      String carrierName,
      String destinationCountryCode,
      String currency,
      BigDecimal freightCost,
      BigDecimal insuranceCost,
      BigDecimal dutyEstimate,
      BigDecimal handlingCost,
      BigDecimal totalCost,
      OffsetDateTime createdAt) {}

  /** 上游一条头程明细行（= 一个 SKU × 一个批次 × 一份申报信息）。 */
  record FirstMileLineView(
      UUID id,
      int lineNo,
      UUID skuId,
      String skuCode,
      String skuName,
      String hsCode,
      BigDecimal shippedQuantity,
      BigDecimal declaredValue,
      BigDecimal grossWeightKg) {}

  record FirstMileDetailView(FirstMileShipmentView shipment, List<FirstMileLineView> lines) {}

  /** 上游一页头程发运单。 */
  record FirstMilePageView(List<FirstMileShipmentView> rows, long total) {}

  /** 上游一张税费估算单。 */
  record DutyEstimateView(
      UUID id,
      String estimateCode,
      String destinationCountryCode,
      String estimateCurrency,
      BigDecimal estimatedTotalTax,
      BigDecimal paidTotalAmount,
      BigDecimal landedCostTotal,
      BigDecimal landedCostSeller,
      String status,
      OffsetDateTime estimatedAt) {}

  /**
   * 上游的汇总摘要。
   *
   * <p>刻意声明出来：上游的 {@code DutyEstimatePage} **没有** 顶层 {@code total}，
   * 条数在 {@code summary.total} 里。不声明它就取不到，翻页会翻成一个死循环。
   */
  record DutyEstimateSummaryView(int total) {}

  /** 上游一页税费估算单。 */
  record DutyEstimatePageView(List<DutyEstimateView> rows, DutyEstimateSummaryView summary) {

    /** 全量条数（上游的 {@code summary.total}）。 */
    long total() {
      return summary == null ? 0L : summary.total();
    }
  }

  /**
   * 上游 JSON 的 mapper。
   *
   * <p>两处配置都不是可有可无的：
   *
   * <ul>
   *   <li>{@code JavaTimeModule} —— 上游的发运单带 {@code createdAt}（OffsetDateTime），
   *       不注册就抛「Java 8 date/time type OffsetDateTime not supported by default」。
   *   <li>关掉 {@code FAIL_ON_UNKNOWN_PROPERTIES} —— 上游**加字段**不应该把本模块撑爆；
   *       类型不匹配照样炸（那种是真读错了，必须炸）。
   * </ul>
   */
  private static final ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private final RestClient client;

  public WarehouseCostQueryPort(@Value("${WAREHOUSE_URL:http://127.0.0.1:8084}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    // 读超时给到 10 秒：头程发运单详情要连明细行一起取，比单条读慢一截，
    // 但仍然必须有上限 —— 没有上限时 warehouse 一慢就把本服务的请求线程全占住。
    factory.setReadTimeout(Duration.ofSeconds(10));
    this.client =
        RestClient.builder()
            .baseUrl(url)
            .requestFactory(factory)
            // 上游**加字段**不应该把本模块撑爆：显式关掉「遇到未知属性就炸」。
            // 只关「未知属性」这一项 —— 类型不匹配照样炸（那种是真读错了，必须炸）。
            .messageConverters(
                java.util.List.of(new MappingJackson2HttpMessageConverter(JSON)))
            .build();
  }

  /** 头程发运单列表（候选池）：真分页，{@code total} 来自上游的 summary.total。 */
  public FirstMilePageView firstMilePage(int page, int size, String bearer) {
    try {
      FirstMilePageView result =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(FIRST_MILE_PATH)
                          .queryParam("page", page)
                          .queryParam("size", size)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(FirstMilePageView.class);
      if (result == null || result.rows() == null) {
        throw unavailable("warehouse 服务没有返回头程发运单列表");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "调用方无权读取上游头程发运单", e);
      }
      throw unavailable("warehouse 服务返回 " + e.getStatusCode().value() + "：头程发运单读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("warehouse 服务不可达");
    }
  }

  /** 单张头程发运单详情（头 + 明细行）：分摊的输入。 */
  public FirstMileDetailView firstMileDetail(UUID shipmentId, String bearer) {
    try {
      FirstMileDetailView result =
          client
              .get()
              .uri(FIRST_MILE_PATH + "/" + shipmentId)
              .header("Authorization", bearer)
              .retrieve()
              .body(FirstMileDetailView.class);
      if (result == null || result.shipment() == null) {
        throw unavailable("warehouse 服务没有返回头程发运单详情");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 404) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "头程发运单不存在或不属于当前租户", e);
      }
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "调用方无权读取上游头程发运单详情", e);
      }
      throw unavailable("warehouse 服务返回 " + e.getStatusCode().value() + "：头程发运单详情读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("warehouse 服务不可达");
    }
  }

  /** 税费估算单列表（关税与到岸成本）：真分页。 */
  public DutyEstimatePageView dutyEstimatePage(int page, int size, String bearer) {
    try {
      DutyEstimatePageView result =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(DUTY_TAX_PATH)
                          .queryParam("page", page)
                          .queryParam("size", size)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(DutyEstimatePageView.class);
      if (result == null || result.rows() == null) {
        throw unavailable("warehouse 服务没有返回税费估算单列表");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "调用方无权读取上游税费估算单", e);
      }
      throw unavailable("warehouse 服务返回 " + e.getStatusCode().value() + "：税费估算单读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("warehouse 服务不可达");
    }
  }

  /** 上游不可用一律 503，理由写明「读不到」而不是「没有数据」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }
}
