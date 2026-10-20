package com.starcon.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 5.2.2.1 汇率查询：**外部汇率源**的拉取端口。
 *
 * <p>蓝图的输入句里除了「币种对与日期」「手工汇率录入」，还有「**汇率来源配置**」，
 * 而来源被点名为「中行/央行/平台结算汇率」—— 这些是**外部系统**，不是本仓的内部服务。
 * 所以本端口与 {@link CostBasisPort}（读 warehouse）有一个**关键区别**：
 *
 * <p><b>🔴 不透传调用者的 Authorization 头。</b>跨服务调用要透传 bearer 是为了让对方
 * 从已认证成员关系解析租户；而外部汇率源（第三方网站 / 平台接口）**不是本仓服务** ——
 * 把用户的会话凭据送给第三方等于把凭据泄露给外部站点。所以这里发出去的请求
 * **不带任何 Authorization 头**，只带查询参数。
 *
 * <p><b>🔴 失败必须可见</b>：连不上 / 超时 / 非 2xx / 响应体不认 / 响应里没有汇率，
 * 五种情况都抛 {@link ExternalRateUnavailableException}，由服务层翻译成明确错误态并落
 * {@code exchange_rate_fetch_log}（FAILED + 原因）。**绝不**返回空集合冒充「这个源今天没有汇率」——
 * 那会让运营以为「今天没汇率」，而真相是「源挂了」。
 *
 * <p><b>响应契约</b>（来源地址是租户自己配的，所以约定写在这里，页面上也原样显示）：
 *
 * <pre>
 * {"rate":"7.1234","effectiveDate":"2026-10-20","sourceRef":"BOC-20261020"}
 * {"rates":[{"base":"USD","quote":"CNY","rate":"7.1234","effectiveDate":"2026-10-20","sourceRef":"..."}]}
 * </pre>
 */
public interface ExternalRateSourcePort {

  /** 外部源返回的一条汇率（方向由 base/quote 显式给出，与本服务的模型同构）。 */
  record FetchedRate(
      String baseCurrency,
      String quoteCurrency,
      BigDecimal rate,
      LocalDate effectiveDate,
      String sourceRef) {}

  /** 拉取结果。 */
  record FetchOutcome(List<FetchedRate> rates, Integer httpStatus) {}

  /** 外部源不可达 / 返回非 2xx / 返回体不认 / 没有汇率。由服务层翻译成 503 或 502 并落失败日志。 */
  class ExternalRateUnavailableException extends RuntimeException {
    public ExternalRateUnavailableException(String message, Throwable cause) {
      super(message, cause);
    }

    public ExternalRateUnavailableException(String message) {
      super(message);
    }
  }

  /**
   * 拉一次。
   *
   * @param endpoint 来源地址（租户配置）
   * @param baseCurrency 基准币种
   * @param quoteCurrency 报价币种
   * @param date 要取的生效日
   */
  FetchOutcome fetch(
      String endpoint, String baseCurrency, String quoteCurrency, LocalDate date);

  /**
   * 真实的 HTTP 实现。用 {@link RestClient}，与本仓既有的 {@link AccessService}（读 identity）、
   * {@link CostBasisPort}（读 warehouse）同构 —— 跨边界取数在本仓库只有这一种写法。
   */
  class HttpExternalRateSourcePort implements ExternalRateSourcePort {

    /** 连接超时。外部源慢的时候要尽快失败，而不是把请求线程挂住。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    /** 读取超时。 */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public HttpExternalRateSourcePort() {
      var factory =
          new org.springframework.http.client.JdkClientHttpRequestFactory(
              java.net.http.HttpClient.newBuilder()
                  .connectTimeout(CONNECT_TIMEOUT)
                  .build());
      factory.setReadTimeout(READ_TIMEOUT);
      this.client = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public FetchOutcome fetch(
        String endpoint, String baseCurrency, String quoteCurrency, LocalDate date) {
      if (endpoint == null || endpoint.trim().isEmpty()) {
        throw new ExternalRateUnavailableException("该汇率来源没有配置外部地址，无法拉取");
      }
      String body;
      int status;
      try {
        var response =
            client
                .get()
                .uri(
                    org.springframework.web.util.UriComponentsBuilder.fromHttpUrl(endpoint.trim())
                        .queryParam("base", baseCurrency)
                        .queryParam("quote", quoteCurrency)
                        .queryParam("date", date.toString())
                        .build()
                        .encode()
                        .toUri())
                // 🔴 刻意**不**带 Authorization：外部源是第三方，带上等于泄露用户凭据。
                .retrieve()
                .toEntity(String.class);
        status = response.getStatusCode().value();
        body = response.getBody();
      } catch (RestClientResponseException e) {
        throw new ExternalRateUnavailableException(
            "外部汇率源返回 " + e.getStatusCode().value() + "，拉取失败", e);
      } catch (RestClientException e) {
        throw new ExternalRateUnavailableException(
            "外部汇率源连不上或超时，拉取失败（"
                + e.getClass().getSimpleName()
                + "）："
                + e.getMessage(),
            e);
      }
      if (status < 200 || status >= 300) {
        throw new ExternalRateUnavailableException("外部汇率源返回 " + status + "，拉取失败");
      }
      if (body == null || body.isBlank()) {
        throw new ExternalRateUnavailableException("外部汇率源返回了空响应，拉取失败");
      }
      return new FetchOutcome(parse(body, baseCurrency, quoteCurrency, date), status);
    }

    /**
     * 解析响应体。两种形状都认：单条对象，或 {@code {"rates":[...]}} 数组包装。
     *
     * <p>解析不出来**不猜**：宁可报「响应不认」，也不把一个格式相近的数字当成汇率写进库 ——
     * 写错一条汇率会污染 5.3 测算与 13 利润，而页面上看不出任何异常。
     */
    private List<FetchedRate> parse(
        String body, String baseCurrency, String quoteCurrency, LocalDate date) {
      JsonNode root;
      try {
        root = mapper.readTree(body);
      } catch (Exception e) {
        throw new ExternalRateUnavailableException("外部汇率源返回的不是合法 JSON，拉取失败", e);
      }
      if (root == null || root.isNull()) {
        throw new ExternalRateUnavailableException("外部汇率源返回了空内容，拉取失败");
      }
      var rows = new ArrayList<FetchedRate>();
      if (root.isArray()) {
        for (var node : root) rows.add(toRate(node, baseCurrency, quoteCurrency, date));
      } else if (root.has("rates") && root.get("rates").isArray()) {
        for (var node : root.get("rates")) rows.add(toRate(node, baseCurrency, quoteCurrency, date));
      } else {
        rows.add(toRate(root, baseCurrency, quoteCurrency, date));
      }
      if (rows.isEmpty()) {
        throw new ExternalRateUnavailableException("外部汇率源没有返回任何汇率，拉取失败");
      }
      return rows;
    }

    private FetchedRate toRate(
        JsonNode node, String baseCurrency, String quoteCurrency, LocalDate date) {
      if (node == null || !node.isObject()) {
        throw new ExternalRateUnavailableException("外部汇率源返回的汇率条目不是对象，拉取失败");
      }
      var base = text(node, "base", "baseCurrency", "from");
      var quote = text(node, "quote", "quoteCurrency", "to");
      var rateText = text(node, "rate", "value", "price");
      if (rateText == null) {
        throw new ExternalRateUnavailableException("外部汇率源返回的汇率条目里没有 rate 字段，拉取失败");
      }
      BigDecimal rate;
      try {
        rate = new BigDecimal(rateText.trim());
      } catch (NumberFormatException e) {
        throw new ExternalRateUnavailableException(
            "外部汇率源返回的汇率不是数字：" + rateText, e);
      }
      if (rate.signum() <= 0) {
        throw new ExternalRateUnavailableException("外部汇率源返回的汇率必须大于 0：" + rateText);
      }
      var effectiveText = text(node, "effectiveDate", "date");
      LocalDate effective = date;
      if (effectiveText != null && !effectiveText.isBlank()) {
        try {
          effective = LocalDate.parse(effectiveText.trim());
        } catch (Exception e) {
          throw new ExternalRateUnavailableException(
              "外部汇率源返回的生效日不是 ISO 日期：" + effectiveText, e);
        }
      }
      return new FetchedRate(
          base == null ? baseCurrency : base,
          quote == null ? quoteCurrency : quote,
          rate.setScale(ExchangeRateRules.RATE_SCALE, ExchangeRateRules.ROUNDING),
          effective,
          text(node, "sourceRef", "ref", "reference"));
    }

    /** 取第一个存在且非空的文本字段。 */
    private static String text(JsonNode node, String... names) {
      for (var name : names) {
        var value = node.get(name);
        if (value != null && !value.isNull() && value.asText() != null && !value.asText().isBlank()) {
          return value.asText();
        }
      }
      return null;
    }
  }

}
