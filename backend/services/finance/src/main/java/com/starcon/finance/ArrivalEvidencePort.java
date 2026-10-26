package com.starcon.finance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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
 * 读取上游 **6.3.2.2 到货跟踪**的**到货流水**事实的跨服务只读端口（trade 服务，端口 8085）。
 *
 * <p>蓝图 12.1.2.3 的输入句写死「收货与质检明细」，上游写的是「6.5.1 采购发起」；
 * 本模块要核对的**收货**事实落在 6.3.2.2 的**到货流水**上：
 * 每一条流水记着「到了多少（arrivedQty）」与「挂着的仓库收货单号（warehouseReceiptCode）」。
 *
 * <p>⚠️ 仓库收货单号是**质检单（6.4.2.1）的关联键** —— 财务侧正是用它把
 * 「对账单的一行」与「质检单」对上。
 *
 * <p>🔴 <b>为什么不复用 12.1.2.1 的 {@code PurchaseArrivalPort}</b>：
 * 那个端口的形状是「到货跟踪头 + 应付部分确认」，**没有到货流水**；
 * 把它扩成也带流水，等于改 12.1.2.1 的端口契约（跨车道共享文件 ⇒ 合并必冲突）。
 * 本端口是**本模块自己的只读形状**，与既有端口并列，不动别人的文件。
 *
 * <p><b>🔴 租户不由本端口传递</b>：转发调用方自己的 bearer，trade 侧按已认证成员关系解析租户。
 *
 * <p><b>取不到就显式失败</b>（503 / 404 / 401），**绝不返回空流水冒充「这批没到货」** ——
 * 那会让差异清单里少一条最该出现的差异。
 */
@Service
public class ArrivalEvidencePort {

  /** 上游只读形状：{@code GET /trade/purchase-arrivals}（6.3.2.2 到货与逾期）。 */
  static final String ARRIVALS_PATH = "/trade/purchase-arrivals";

  /** 上游一条到货流水。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record ArrivalReceipt(
      UUID id,
      @JsonIgnoreProperties(ignoreUnknown = true) int arrivalSeq,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal arrivedQty,
      @JsonIgnoreProperties(ignoreUnknown = true) OffsetDateTime arrivedAt,
      @JsonIgnoreProperties(ignoreUnknown = true) String warehouseReceiptCode,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal warehouseReceiptReceivedQty) {}

  /** 上游到货跟踪详情（只取到货流水）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record ArrivalDetail(@JsonIgnoreProperties(ignoreUnknown = true) List<ArrivalReceipt> receipts) {}

  private static final ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private final RestClient client;

  public ArrivalEvidencePort(@Value("${TRADE_URL:http://127.0.0.1:8085}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(10));
    this.client =
        RestClient.builder()
            .baseUrl(url)
            .requestFactory(factory)
            .messageConverters(java.util.List.of(new MappingJackson2HttpMessageConverter(JSON)))
            .build();
  }

  /**
   * 读一张到货跟踪的详情（取它的到货流水）。
   *
   * @throws ResponseStatusException 上游不可达（503）/ 上游没有这张跟踪单（404）/ 调用方无权（401）
   */
  public ArrivalDetail arrival(UUID id, String bearer) {
    try {
      ArrivalDetail detail =
          client
              .get()
              .uri(ARRIVALS_PATH + "/" + id)
              .header("Authorization", bearer)
              .retrieve()
              .body(ArrivalDetail.class);
      if (detail == null) throw unavailable("trade 服务没有返回到货跟踪详情");
      return detail;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 404) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND, "上游 6.3.2.2 到货跟踪 " + id + " 不存在", e);
      }
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED,
            "调用方无权读取上游到货跟踪事实（需要 trade 侧的 trade.purchase.read 权限）",
            e);
      }
      throw unavailable("trade 服务返回 " + e.getStatusCode().value() + "：到货事实读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("trade 服务不可达");
    }
  }

  /** 上游不可用一律 503，理由写明「读不到」而不是「没有数据」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }
}
