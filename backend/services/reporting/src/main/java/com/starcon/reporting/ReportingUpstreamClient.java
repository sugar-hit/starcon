package com.starcon.reporting;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 上游只读端口的**共用 HTTP 客户端**。
 *
 * <p>⚠️ **租户不由本客户端传递**：转发的是**调用方自己的** {@code Authorization} bearer，
 * 上游侧仍按已认证成员关系解析租户并强制 RLS。租户隔离因此是**两道**
 * （reporting 侧 RLS + 上游侧 RLS），而不是「本服务替对方决定租户」。
 *
 * <p>⚠️ **取不到就显式失败**，绝不返回空集合冒充「上游没有数据」：
 * 一个空集合在报表里长成「销售额 0」，而真相是「这一格没算出来」。
 * 所以本类把上游故障一律翻成 <b>503</b>，消息里写明「读不到」而不是「没有数据」。
 *
 * <p>用法与既有端口（finance 的 {@code TradeReceivablePort} / {@code AdSpendPort}、
 * catalog 的 {@code CatalogExchangeRatePort}）逐字同构。
 */
final class ReportingUpstreamClient {

  /**
   * 上游 JSON 的 mapper。
   *
   * <p>两处配置都不是可有可无的：{@code JavaTimeModule}（上游带 {@code OffsetDateTime} /
   * {@code LocalDate}）以及关掉 {@code FAIL_ON_UNKNOWN_PROPERTIES}（上游<b>加字段</b>不应该把
   * 本服务撑爆；类型不匹配照样炸 —— 那种是真读错了，必须炸）。
   */
  static final ObjectMapper JSON =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private final RestClient client;
  private final String serviceName;

  private ReportingUpstreamClient(String serviceName, String url) {
    this.serviceName = serviceName;
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    // 读超时给到 15 秒：一次批量扫描要连续翻多页，比单条读慢一截，
    // 但仍然必须有上限 —— 没有上限时上游一慢就把本服务的请求线程全占住。
    factory.setReadTimeout(Duration.ofSeconds(15));
    this.client =
        RestClient.builder()
            .baseUrl(url)
            .requestFactory(factory)
            .messageConverters(List.of(new MappingJackson2HttpMessageConverter(JSON)))
            .build();
  }

  /** 建一个指向上游的客户端。 */
  static ReportingUpstreamClient to(String serviceName, String url) {
    return new ReportingUpstreamClient(serviceName, url);
  }

  /**
   * GET 一个上游端点并反序列化成 {@code type}。
   *
   * @param uriBuilder 端点路径与查询参数
   * @param bearer 调用方自己的会话
   * @param type 目标类型
   */
  <T> T get(
      java.util.function.Function<org.springframework.web.util.UriBuilder, org.springframework.web.util.UriBuilder>
          uriBuilder,
      String bearer,
      Class<T> type) {
    try {
      T result =
          client
              .get()
              // ⚠️ Spring 的 {@code uri(...)} 重载里没有「Function<UriBuilder, UriBuilder]>」这一支 ——
              // 只有 {@code Function<UriBuilder, URI>}。所以在这里把 UriBuilder 收尾成 URI，
              // 各端口的 lambda 保持「拼路径与查询参数」的写法，不必自己 build()。
              .uri(builder -> uriBuilder.apply(builder).build())
              .header("Authorization", bearer)
              .retrieve()
              .body(type);
      if (result == null) throw unavailable("上游没有返回内容");
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "无权读取 " + serviceName + " 的上游事实，报表无法投影",
            e);
      }
      throw unavailable("上游返回 " + e.getStatusCode().value() + "：事实读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("上游不可达");
    }
  }

  /** 上游不可用一律 503，理由写明「读不到」而不是「没有数据」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE, serviceName + " " + detail + "（是「读不到」，不是「没有数据」）");
  }
}
