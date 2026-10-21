package com.starcon.channel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 读取上游 9.4.1.2 运单集合的**跨服务只读端口**。
 *
 * <p>蓝图 10.3.1.1 的输入句第一样是「运单/包裹集合」，而**运单的真值不在本服务**：
 * 它在 <b>trade</b> 服务的 {@code shipment_record} 族（V127）。AGENTS.md 模块边界
 * 纪律禁止跨服务 SQL / 跨库 FK / 共享视图，所以本端口<b>只经 trade 的授权 HTTP API</b>读，
 * 不直连对方库、不建跨库视图、不建跨库外键。
 *
 * <p>租户<b>不</b>由本端口传递租户号：转发的是<b>调用方自己的</b>
 * {@code Authorization} bearer，trade 侧仍按已认证成员关系解析租户并强制 RLS。
 * 也就是说租户隔离是<b>两道</b>（channel 侧 RLS + trade 侧 RLS），
 * 而不是「本服务替对方决定租户」。
 *
 * <p>用法与本服务既有的 {@code PendingShipmentQueryPort}（读 trade 的待发货）、
 * {@code SellableQuantityQueryPort}（读 warehouse）逐字同构：取不到就<b>显式失败</b>，
 * 绝不返回空集合冒充「上游没有运单」—— 那会让现场看到 0 票，
 * 照着空清单以为货全没发。
 */
@Service
public class ShipmentTrackingQueryPort {
  /** 上游只读形状：{@code GET /trade/fulfillment/shipment-records/records}。 */
  static final String RECORDS_PATH = "/trade/fulfillment/shipment-records/records";

  /**
   * 上游一行发货记录（只声明本模块真正用到的字段）。
   *
   * <p>刻意读得少：读得越少，上游加字段时本模块越不容易被撑爆。
   *
   * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)}：上游那页还带着
   * {@code summary} 与若干本模块用不到的列，读少是刻意的，但**不因为读少就崩**。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record ShipmentRecordView(
      UUID id,
      String recordNo,
      String outboundOrderNo,
      String salesOrderNo,
      String warehouseRef,
      String carrierCode,
      String carrierName,
      String masterWaybillNo,
      String destinationCountry,
      OffsetDateTime shippedAt,
      int parcelCount,
      BigDecimal totalQuantity,
      BigDecimal totalGrossWeightG,
      String status,
      String trackingStatus) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record ShipmentRecordPage(List<ShipmentRecordView> items, int page, int size, long totalElements) {}

  private final RestClient client;

  public ShipmentTrackingQueryPort(@Value("${TRADE_URL:http://127.0.0.1:8085}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    // 读超时给到 10 秒：同步一页要穿透取运单集合，比单条读慢一截，
    // 但仍然必须有上限 —— 没有上限时 trade 一慢就把本服务的请求线程全占住。
    factory.setReadTimeout(Duration.ofSeconds(10));
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  /**
   * 拉一页**已发货**的运单集合。
   *
   * <p>承运商为空时拉全部承运商；给了就只拉那一家（页面上「按承运商」的主筛选）。
   */
  public ShipmentRecordPage records(
      String carrierCode, int page, int size, String bearer) {
    try {
      ShipmentRecordPage result =
          client
              .get()
              .uri(
                  uriBuilder -> {
                    var b = uriBuilder.path(RECORDS_PATH);
                    if (carrierCode != null && !carrierCode.isBlank()) {
                      b.queryParam("carrierCode", carrierCode);
                    }
                    return b.queryParam("page", page).queryParam("size", size).build();
                  })
              .header("Authorization", bearer)
              .retrieve()
              .body(ShipmentRecordPage.class);
      if (result == null || result.items() == null) {
        throw unavailable("trade 服务没有返回运单列表");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "调用方无权读取上游运单集合", e);
      }
      throw unavailable("trade 服务返回 " + e.getStatusCode().value() + "：运单集合读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("trade 服务不可达");
    }
  }

  /** 上游不可用一律 503，理由写明「读不到」而不是「没有数据」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }
}
