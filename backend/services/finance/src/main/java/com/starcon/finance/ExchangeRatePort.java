package com.starcon.finance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 5.2.2.1 汇率事实（catalog 服务）的**跨服务只读端口** —— 12.2.2.1 付款单与审批的汇率快照来源。
 *
 * <p>蓝图 12-finance.md 把「<b>汇率快照</b>」写成本模块的输入之一。
 * 汇率的**真相**在 catalog 服务（5.2.2.1 已交付，{@code GET /catalog/exchange-rates/latest}）：
 * 「查不到返回 404 并写明原因，<b>不</b>回退到『最近的一版』」。
 *
 * <p>AGENTS.md 模块边界纪律禁止跨服务 SQL / 跨库 FK / 共享视图 ⇒ 本端口<b>只经 catalog 的
 * 授权 HTTP API</b>读，租户<b>不由本端口传递</b>：转发的是<b>调用方自己的</b>
 * {@code Authorization} bearer，catalog 侧仍按已认证成员关系解析租户并强制 RLS。
 *
 * <p><b>方向必须显式</b>（5.2.2.1 的纪律）：本模块的 {@code exchange_rate} 口径是
 * 「<b>1 本单币种 = exchange_rate 本位币</b>」，与 {@code finance_payable.base_amount}
 * 的生成列 {@code round(amount * exchange_rate, 4)} 同一口径。
 *
 * <p>🔴 <b>反向推导会如实标注</b>：catalog 只登记了反方向时（{@code 1 CNY = 0.14 USD}），
 * 本端口取其倒数得到 {@code 1 USD = 7.12 CNY}，并在结果上打 {@code inverted=true} ——
 * 页面必须让人看见「这个汇率是推导出来的」，而不是把它当成一条直接登记的事实。
 */
@Service
public class ExchangeRatePort {

  /** 5.2.2.1 汇率查询：某个币种对在指定日期当天或之前**最新**的一条汇率。 */
  static final String LATEST_PATH = "/catalog/exchange-rates/latest";

  /** 上游 JSON 的 mapper。 */
  private static final ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  /** 上游一行汇率（只声明本模块真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record RateView(
      UUID id,
      String baseCurrency,
      String quoteCurrency,
      BigDecimal rate,
      LocalDate effectiveDate,
      @JsonIgnoreProperties(ignoreUnknown = true) String sourceCode,
      @JsonIgnoreProperties(ignoreUnknown = true) String sourceName,
      @JsonIgnoreProperties(ignoreUnknown = true) String display,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal inverseRate) {}

  private final RestClient client;

  public ExchangeRatePort(@Value("${CATALOG_URL:http://127.0.0.1:8083}") String url) {
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
   * 取「1 baseCurrency = ? quoteCurrency」的汇率快照。
   *
   * <p>先按正向查；上游 404 时**再试反向**并取倒数 —— 但**绝不**回退到「最近的一版」
   * （上游的纪律），两个方向都查不到就是**真没有这条汇率**，显式失败。
   *
   * @param asOf 基准日（UTC 日历日）；null = 上游默认
   * @param bearer 调用方自己的会话
   */
  public RateSnapshot latest(String baseCurrency, String quoteCurrency, LocalDate asOf, String bearer) {
    if (baseCurrency.equals(quoteCurrency)) {
      // 同币种：汇率恒为 1，不需要去问上游。
      return new RateSnapshot(
          baseCurrency, quoteCurrency, BigDecimal.ONE, asOf, "SELF", "同币种", false, "1 " + baseCurrency + " = 1 " + quoteCurrency);
    }
    var direct = tryLatest(baseCurrency, quoteCurrency, asOf, bearer);
    if (direct != null) {
      return new RateSnapshot(
          direct.baseCurrency(),
          direct.quoteCurrency(),
          direct.rate(),
          direct.effectiveDate(),
          direct.sourceCode(),
          direct.sourceName(),
          false,
          "1 " + direct.baseCurrency() + " = " + direct.rate() + " " + direct.quoteCurrency());
    }
    var reverse = tryLatest(quoteCurrency, baseCurrency, asOf, bearer);
    if (reverse != null) {
      var inverted =
          BigDecimal.ONE.divide(reverse.rate(), PaymentOrderModels.RATE_SCALE, RoundingMode.HALF_UP);
      return new RateSnapshot(
          baseCurrency,
          quoteCurrency,
          inverted,
          reverse.effectiveDate(),
          reverse.sourceCode(),
          reverse.sourceName(),
          true,
          "1 " + baseCurrency + " = " + inverted + " " + quoteCurrency + "（由 1 " + reverse.quoteCurrency()
              + " = " + reverse.rate() + " " + reverse.baseCurrency() + " 推导）");
    }
    throw new ResponseStatusException(
        HttpStatus.NOT_FOUND,
        "catalog 服务查不到 "
            + baseCurrency
            + " → "
            + quoteCurrency
            + " 的汇率（正反两个方向都查不到）：汇率快照取不到，付款单不能建",
        null);
  }

  /** 正向查一次；404 ⇒ 返回 null（由调用方决定要不要试反向）。 */
  private RateView tryLatest(
      String baseCurrency, String quoteCurrency, LocalDate asOf, String bearer) {
    try {
      RateView result =
          client
              .get()
              .uri(
                  uriBuilder -> {
                    var b = uriBuilder.path(LATEST_PATH);
                    b.queryParam("baseCurrency", baseCurrency);
                    b.queryParam("quoteCurrency", quoteCurrency);
                    if (asOf != null) b.queryParam("asOf", asOf.toString());
                    return b.build();
                  })
              .header("Authorization", bearer)
              .retrieve()
              .body(RateView.class);
      if (result == null || result.rate() == null || result.rate().signum() <= 0) return null;
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 404) return null;
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN,
            "读取汇率需要 catalog.spu.read 权限（当前角色没有）",
            e);
      }
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "catalog 服务返回 " + e.getStatusCode().value() + "：汇率读不到", e);
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "catalog 服务不可达", e);
    }
  }

  /** 汇率快照（端口的返回值）。 */
  public record RateSnapshot(
      String baseCurrency,
      String quoteCurrency,
      BigDecimal rate,
      LocalDate effectiveDate,
      String sourceCode,
      String sourceName,
      boolean inverted,
      String display) {}

  /** 当前 UTC 日历日（上游 {@code asOf} 的默认值口径）。 */
  static LocalDate todayUtc() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  /** 上游时间戳 → 本模块的 OffsetDateTime。 */
  static OffsetDateTime toOffset(OffsetDateTime value) {
    return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
  }
}
