package com.starcon.finance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 读取 **5.2.2.1 汇率查询**事实的**跨服务只读端口**（catalog 服务，端口 8082）。
 *
 * <p>蓝图 12.7.2.1 的输入里写着「汇率数据」，而汇率真值在 catalog 服务：
 * {@code GET /catalog/exchange-rates/latest}（某个币种对在指定日期当天或之前最新的一条汇率）。
 * AGENTS.md 模块边界纪律禁止跨服务 SQL / 跨库 FK / 共享视图，
 * 所以本端口<b>只经 catalog 的授权 HTTP API</b>读，不直连对方库、不建跨库视图、不建跨库外键。
 *
 * <p><b>本服务不建汇率表、不抄汇率。</b>理由：汇率是外部事实，抄一份就会漂移
 * （catalog 那边更正一条汇率，本地的副本就成了第二份互相矛盾的事实）。
 * 敞口快照里记的是**当时用的那一档汇率 + 它的生效日**，那是「一次测算的输入」，
 * 不是「本服务拥有汇率」—— 快照是快照，汇率主数据仍归 catalog。
 *
 * <p>🔴 <b>取不到就显式失败</b>，绝不返回空集合冒充「没有汇率」：
 * 一个空集合在报表里长成「敞口 0」，而真相是「这一格没算出来」。
 * 所以本端口的失败一律是 <b>503</b>，消息里写明缺的是哪个币种对。
 *
 * <p>租户<b>不</b>由本端口传递租户号：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * catalog 侧仍按已认证成员关系解析租户并强制 RLS。租户隔离是<b>两道</b>
 * （finance 侧 RLS + catalog 侧 RLS），而不是「本服务替对方决定租户」。
 */
@Service
public class CatalogExchangeRatePort {

  /** 上游只读形状：{@code GET /catalog/exchange-rates/latest}（5.2.2.1 汇率查询）。 */
  static final String LATEST_PATH = "/catalog/exchange-rates/latest";

  /**
   * 上游一行汇率（只声明本模块真正用到的字段）。
   *
   * <p>{@code display} 是上游给的整句（「1 USD = 7.1234 CNY」）——
   * 方向在句子里，本模块**原样转发**，不在自己这边拼方向。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record RateView(
      String baseCurrency,
      String quoteCurrency,
      BigDecimal rate,
      LocalDate effectiveDate,
      String sourceCode,
      String display) {}

  /**
   * 上游 JSON 的 mapper。
   *
   * <p>两处配置都不是可有可无的：{@code JavaTimeModule}（上游带 {@code effectiveDate}），
   * 以及关掉 {@code FAIL_ON_UNKNOWN_PROPERTIES}（上游<b>加字段</b>不应该把本模块撑爆；
   * 类型不匹配照样炸 —— 那种是真读错了，必须炸）。
   */
  private static final ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new JavaTimeModule())
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
            .messageConverters(List.of(new MappingJackson2HttpMessageConverter(JSON)))
            .build();
  }

  /**
   * 取某个币种对在指定日期当天或之前**最新**的一条汇率。
   *
   * @param bearer 调用方自己的会话（租户由 catalog 侧按已认证成员关系解析）
   * @param asOf 基准日（UTC 日历日）
   * @throws ResponseStatusException 上游取不到时一律 503，消息写明缺哪个币种对
   */
  public RateView latest(String baseCurrency, String quoteCurrency, LocalDate asOf, String bearer) {
    try {
      RateView result =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(LATEST_PATH)
                          .queryParam("baseCurrency", baseCurrency)
                          .queryParam("quoteCurrency", quoteCurrency)
                          .queryParam("asOf", asOf)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(RateView.class);
      if (result == null || result.rate() == null) {
        throw unavailable(baseCurrency, quoteCurrency);
      }
      return result;
    } catch (RestClientResponseException e) {
      // 404 = 上游确实没有这一档汇率（catalog 的 latest 就是这么答的：不回退到「最近的一版」）。
      // 403/401 = 调用方在 catalog 侧没有读汇率的权限。
      // 其它 = 上游故障。三者都**不是**「汇率为 0」或「没有敞口」。
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "无权读取汇率事实（需要 catalog 侧的 catalog.spu.read 权限），汇率敞口无法测算",
            e);
      }
      throw unavailable(baseCurrency, quoteCurrency);
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable(baseCurrency, quoteCurrency);
    }
  }

  /** 上游取不到一律 503，理由写明「读不到」而不是「没有敞口」。 */
  private ResponseStatusException unavailable(String baseCurrency, String quoteCurrency) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "读不到汇率事实（"
            + baseCurrency
            + " → "
            + quoteCurrency
            + "）：汇率敞口无法测算，请先在 5.2.2.1 登记该币种对的汇率");
  }
}
