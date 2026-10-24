package com.starcon.channel;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 读取 **catalog 服务 5.2.2.2 汇率快照**的**跨服务只读端口**。
 *
 * <p>用途只有一个：**IOSS 免征门槛是欧元口径**（150 EUR），而订单可以是任意币种。
 * 订单币种 ≠ 门槛币种时，货值必须折算成欧元才能和门槛比。
 *
 * <p>🔴 <b>读不到就判不了</b>：本端口返回 {@code null} 表示「读不到」，
 * 上层据此把该单判成 {@code UNRESOLVED / THRESHOLD_UNRESOLVED} ——
 * <b>绝不允许拿一个猜的汇率把单子归档进 IOSS</b>（那等于凭空造一份申报数据）。
 *
 * <p>⚠️ 聚合口径**永远不折算**：统计一律**按币种分组**（EUR + USD 不是钱）。
 * 汇率只用来做「门槛比较」这一个判断，不进任何金额合计。
 *
 * <p>⚠️ AGENTS.md 模块边界纪律禁止跨服务 SQL / 跨库 FK / 共享视图，所以本端口
 * <b>只经 catalog 的授权 HTTP API</b>读。租户由 catalog 侧按已认证成员关系解析。
 */
@Service
public class CatalogExchangeRatePort {

  /** 上游只读形状：{@code GET /catalog/exchange-rates/latest}。 */
  static final String LATEST_PATH = "/catalog/exchange-rates/latest";

  /** 上游 JSON 的 mapper。 */
  private static final ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private final RestClient client;

  public CatalogExchangeRatePort(@Value("${CATALOG_URL:http://127.0.0.1:8082}") String url) {
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

  /**
   * 取「1 原币 = ? 目标币」的汇率。
   *
   * @param baseCurrency 原币（订单币种）
   * @param quoteCurrency 目标币（门槛币种，EUR）
   * @param asOf 取汇率的日期（订单日期）
   * @param bearer 调用方自己的会话
   * @return 汇率；**读不到返回 null**（调用方据此判「判不了」，**不许拿 1 或 0 顶替**）
   */
  public BigDecimal rate(String baseCurrency, String quoteCurrency, LocalDate asOf, String bearer) {
    if (baseCurrency == null
        || quoteCurrency == null
        || baseCurrency.isBlank()
        || quoteCurrency.isBlank()) {
      return null;
    }
    if (baseCurrency.equalsIgnoreCase(quoteCurrency)) {
      return BigDecimal.ONE;
    }
    try {
      RateBody body =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(LATEST_PATH)
                          .queryParam("baseCurrency", baseCurrency)
                          .queryParam("quoteCurrency", quoteCurrency)
                          .queryParam("asOf", asOf == null ? null : asOf.toString())
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(RateBody.class);
      if (body == null || body.rate() == null) {
        return null;
      }
      return body.rate();
    } catch (RestClientResponseException e) {
      // 🔴 查不到（404）/ 无权限 / 上游异常 —— 一律「读不到」，返回 null。
      return null;
    } catch (org.springframework.web.client.ResourceAccessException e) {
      return null;
    }
  }

  /** 上游返回体（只声明本模块真正用到的字段）。 */
  record RateBody(
      java.util.UUID id,
      String baseCurrency,
      String quoteCurrency,
      BigDecimal rate,
      LocalDate effectiveDate) {}
}
