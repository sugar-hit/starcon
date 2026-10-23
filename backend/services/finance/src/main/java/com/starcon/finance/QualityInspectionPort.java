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
 * 读取上游 **6.4.2.1 质检单**事实的**跨服务只读端口**（trade 服务，端口 8085）。
 *
 * <p>蓝图 12.1.2.3 的输入句写死「收货与**质检明细**」；质检单（来料质检）在
 * <b>trade 服务</b>的 {@code /trade/quality-inspections}（卷 06：6.4 加工与质检 → trade + warehouse，
 * 列表第一项是 trade）。
 *
 * <p>⚠️ <b>它与 11.x 的退货质检（{@code /trade/return-quality}）是两个模块</b>：
 * 退货质检判的是「退回来的货」，本模块判的是「进来的货」。
 * 表、端点、权限码、DTO 前缀全部分开 —— 本模块只认 {@code quality_inspection_*}。
 *
 * <p><b>关联键</b>：质检单的 {@code receiptCode}（仓库收货单号）= 6.3.2.2 到货流水挂着的
 * {@code warehouseReceiptCode} ⇒ 财务侧用「对账单行 → 到货流水 → 仓库收货单号 → 质检单」
 * 这条链把质检证据挂到对账行上。
 *
 * <p><b>扣量口径</b>：本模块用上游自己的 {@code grossDeductedQuantity}
 * （= 不合格量 + 让步接收量，DB 层有 CHECK 保证）作为「应付要扣的数量」，
 * **不自己发明一套扣量规则**。
 *
 * <p>🔴 <b>租户不由本端口传递</b>：转发调用方自己的 bearer，trade 侧按已认证成员关系解析租户。
 *
 * <p><b>取不到就显式失败</b>（503 / 401），**绝不返回空集合冒充「这批没质检」** ——
 * 空集合会让「质检依据缺失」这条差异凭空消失。
 */
@Service
public class QualityInspectionPort {

  /** 上游只读形状：{@code GET /trade/quality-inspections}（6.4.2.1 质检单）。 */
  static final String INSPECTIONS_PATH = "/trade/quality-inspections";

  /** 上游一页质检单。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record InspectionPage(List<InspectionRow> rows, int page, int size, long total) {}

  /** 上游一张质检单（本模块真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record InspectionRow(
      UUID id,
      String inspectionNo,
      @JsonIgnoreProperties(ignoreUnknown = true) UUID supplierId,
      @JsonIgnoreProperties(ignoreUnknown = true) String supplierName,
      @JsonIgnoreProperties(ignoreUnknown = true) String receiptCode,
      @JsonIgnoreProperties(ignoreUnknown = true) String skuCode,
      String status,
      @JsonIgnoreProperties(ignoreUnknown = true) String result,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal arrivedQuantity,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal lotSize,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal qualifiedQuantity,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal defectiveQuantity,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal concessionQuantity,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal grossDeductedQuantity,
      @JsonIgnoreProperties(ignoreUnknown = true) String currencyCode,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal unitPrice,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal deductedAmount,
      @JsonIgnoreProperties(ignoreUnknown = true) OffsetDateTime completedAt) {}

  private static final ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private final RestClient client;

  public QualityInspectionPort(@Value("${TRADE_URL:http://127.0.0.1:8085}") String url) {
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
   * 按**仓库收货单号**读质检单。
   *
   * <p>⚠️ 上游对同一个收货单号可能有多张质检单（一个 SKU 一张）：本模块返回**全部**，
   * 由服务层按供应商过滤并汇总 —— 过滤与汇总的口径在服务层，不在端口里。
   *
   * @throws ResponseStatusException 上游不可达（503）/ 调用方无权（401）
   */
  public InspectionPage inspections(String receiptCode, String bearer) {
    try {
      InspectionPage result =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(INSPECTIONS_PATH)
                          .queryParam("receiptCode", receiptCode)
                          .queryParam("size", 200)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(InspectionPage.class);
      if (result == null || result.rows() == null) {
        throw unavailable("trade 服务没有返回质检单列表");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED,
            "调用方无权读取上游质检单事实（需要 trade 侧的 trade.quality-inspection.read 权限）",
            e);
      }
      throw unavailable("trade 服务返回 " + e.getStatusCode().value() + "：质检事实读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("trade 服务不可达");
    }
  }

  /** 上游不可用一律 503，理由写明「读不到」而不是「没有数据」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }
}
