package com.starcon.platform;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 读取 **5.2.2.1 汇率事实**（catalog 服务，端口 8083）的**跨服务只读端口**。
 *
 * <p>蓝图 18.2.2.1 的五件职责里有「<b>汇率查询</b>」。汇率有两个来源，**口径必须分开**：
 *
 * <ol>
 *   <li><b>参考汇率</b>（本端口，catalog 5.2.2.1）—— 本系统登记的汇率事实，
 *       「1 本单币种 = exchange_rate 本位币」是它的口径；
 *   <li><b>服务商报价</b>（{@link PaymentProviderPort#quoteRate}）—— 服务商侧的锁汇价，
 *       含点差。它归**服务商通道**，本仓**未接入**（见
 *       {@link AbstractUnavailablePaymentProviderAdapter}）。
 * </ol>
 *
 * <p>⚠️ catalog 只登记了反方向时（{@code 1 CNY = 0.14 USD}），
 * 本端口取其倒数得到 {@code 1 USD = 7.12 CNY}，并打 {@code inverted=true} ——
 * 页面必须让人看见「这个汇率是推导出来的」，而不是把它当成一条直接登记的事实。
 *
 * <p>⚠️ <b>只转发调用方自己的 bearer，不传租户号</b>：catalog 侧仍按已认证成员关系
 * 解析租户并强制 RLS。租户隔离是<b>两道</b>（本服务 RLS + 上游 RLS）。
 *
 * <p>⚠️ <b>查不到就 503</b>，不返回空集合、也**不**回退到「最近的一版」。
 */
@Service
public class CatalogRatePort {

  /** 5.2.2.1 汇率查询：某个币种对在指定日期当天或之前**最新**的一条汇率。 */
  static final String LATEST_PATH = "/catalog/exchange-rates/latest";

  private final PlatformUpstreamClient client;

  public CatalogRatePort(@Value("${CATALOG_URL:http://127.0.0.1:8083}") String url) {
    this.client = PlatformUpstreamClient.to("catalog 服务", url);
  }

  /** 上游一行汇率（只声明本模块真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RateView(
      UUID id,
      String baseCurrency,
      String quoteCurrency,
      BigDecimal rate,
      LocalDate effectiveDate,
      String sourceCode,
      String sourceName,
      BigDecimal inverseRate,
      String display) {}

  /** 拉某个币种对的最新汇率。 */
  public RateView latest(String baseCurrency, String quoteCurrency, LocalDate asOf, String bearer) {
    return client.get(
        uriBuilder -> {
          uriBuilder
              .path(LATEST_PATH)
              .queryParam("baseCurrency", baseCurrency)
              .queryParam("quoteCurrency", quoteCurrency);
          if (asOf != null) uriBuilder.queryParam("asOf", asOf.toString());
          return uriBuilder;
        },
        bearer,
        RateView.class);
  }
}
