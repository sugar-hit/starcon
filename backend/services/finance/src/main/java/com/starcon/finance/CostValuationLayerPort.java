package com.starcon.finance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
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
 * 12.4.1.1 试算用的**跨服务只读端口**：真实成本层（catalog 5.2.1.1 成本构成）。
 *
 * <p>蓝图要求「系统提供选项与<b>试算</b>」—— 试算必须用**系统里真实存在的数据**算，
 * 不能用一份写死的示例成本。所以本端口去读 catalog 的成本构成明细：
 * {@code GET /catalog/sku-cost-compositions/{skuId}}。
 *
 * <p>🔴 <b>为什么是 catalog 而不是自己造一张成本层表</b>：
 *
 * <ul>
 *   <li>catalog 的成本层是**真实到货记录**（{@code vendor_delivery_record}，6.1.1.1 供应商到货）
 *       按成本法派生出来的批次清单 —— 每一层都带到货日、数量、金额、单价；
 *   <li>本服务（finance）**没有**任何到货事实表（采购到货在 trade / catalog 侧），
 *       自己造一张就等于**造数**：那批成本不是真的，界面上的试算结果也就没有意义；
 *   <li>AGENTS.md 禁止跨服务 SQL ⇒ 只能经授权 HTTP 只读，**不建影子表**。
 * </ul>
 *
 * <p>🔴 <b>本端口只取「采购成本层」这一个分项</b>：头程分摊与关税是 catalog 自己从 warehouse 读的，
 * 本模块的计价方法只决定**采购成本怎么结转**，不该把跨三服务的成本口径搅进来。
 *
 * <p>🔴 <b>上游读不到一律 503</b>，理由写明「读不到」而不是「没有成本层」——
 * 试算页显示「没有成本层」和「上游挂了」是两回事：前者说明库存确实没到货，
 * 后者说明你看到的试算结果是假的。
 */
@Service
public class CostValuationLayerPort {

  /** 上游只读形状：{@code GET /catalog/sku-cost-compositions/{skuId}}（5.2.1.1 成本构成）。 */
  static final String COST_COMPOSITION_PATH = "/catalog/sku-cost-compositions/{skuId}";

  /**
   * 上游 JSON 的解析器。
   *
   * <p>⚠️ {@code USE_BIG_DECIMAL_FOR_FLOATS} 必须开：默认 Jackson 把 JSON 里的浮点数读成 {@code double}，
   * 于是上游的 {@code 12.3456} 变成 {@code 12.345600000000001} —— 金额被悄悄改了一位。
   */
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  /** 上游一行成本层（到货记录派生的批次）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CostLayer(
      String lotRef,
      @JsonIgnoreProperties(ignoreUnknown = true) LocalDate receivedDate,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal quantity,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal baseQuantity,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal amount,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal unitCost,
      @JsonIgnoreProperties(ignoreUnknown = true) String currency) {}

  /**
   * 上游成本构成明细（只声明本模块真正用到的字段）。
   *
   * @param excludedDeliveryCount 窗口内被排除的到货记录数（&gt;0 ⇒ 成本层不完整，必须如实报出）
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CostComposition(
      UUID skuId,
      @JsonIgnoreProperties(ignoreUnknown = true) String skuCode,
      @JsonIgnoreProperties(ignoreUnknown = true) String skuName,
      @JsonIgnoreProperties(ignoreUnknown = true) String currency,
      @JsonIgnoreProperties(ignoreUnknown = true) List<CostLayer> lots,
      @JsonIgnoreProperties(ignoreUnknown = true) Integer excludedDeliveryCount,
      @JsonIgnoreProperties(ignoreUnknown = true) LocalDate windowFrom,
      @JsonIgnoreProperties(ignoreUnknown = true) LocalDate windowTo,
      @JsonIgnoreProperties(ignoreUnknown = true) List<String> missingComponents) {}

  private final RestClient catalog;

  public CostValuationLayerPort(@Value("${CATALOG_URL:http://127.0.0.1:8082}") String catalogUrl) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(15));
    this.catalog =
        RestClient.builder()
            .baseUrl(catalogUrl)
            .requestFactory(factory)
            .messageConverters(
                java.util.List.of(
                    new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(JSON)))
            .build();
  }

  /**
   * 读一个 SKU 的真实成本层。
   *
   * @param currency 计价币种（只取同币种的层 —— **绝不跨币种相加**）
   * @param from 成本窗口起（到货记录在窗口内的才算成本层）
   * @param to 成本窗口止
   * @param bearer 调用方自己的 bearer（转发它，租户由 catalog 按成员关系解析）
   */
  public CostComposition composition(
      UUID skuId, String currency, LocalDate from, LocalDate to, String bearer) {
    try {
      CostComposition result =
          catalog
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(COST_COMPOSITION_PATH.replace("{skuId}", skuId.toString()))
                          .queryParam("currency", currency)
                          .queryParam("windowFrom", from)
                          .queryParam("windowTo", to)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(CostComposition.class);
      if (result == null) {
        throw unavailable("catalog 服务没有返回成本构成明细（SKU " + skuId + "）");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED,
            "调用方无权读取上游成本构成（需要 catalog 侧的 catalog.spu.read 权限）",
            e);
      }
      throw unavailable("catalog 服务返回 " + e.getStatusCode().value() + "：成本层读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("catalog 服务不可达：成本层读不到");
    }
  }

  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }
}
