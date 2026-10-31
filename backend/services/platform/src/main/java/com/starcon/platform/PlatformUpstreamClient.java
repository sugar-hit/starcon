package com.starcon.platform;

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
 * 上游只读端口的**共用 HTTP 客户端**（与 reporting 的 {@code ReportingUpstreamClient} 同构）。
 *
 * <p>⚠️ <b>租户不由本客户端传递</b>：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * 上游侧仍按已认证成员关系解析租户并强制 RLS。租户隔离因此是<b>两道</b>
 * （platform 侧 RLS + 上游侧 RLS），而不是「本服务替对方决定租户」。
 *
 * <p>⚠️ <b>取不到就显式失败</b>，绝不返回空集合冒充「上游没有数据」：
 * 一个空集合在文案工作区里会长成「上游没有需要翻译的文案」，而真相是「这一格没算出来」。
 * 所以本类把上游故障一律翻成 <b>503</b>，消息里写明「读不到」而不是「没有数据」。
 *
 * <p>⚠️ <b>显式超时</b>：连接 3 秒 / 读 10 秒。没有上限时上游一慢就把本服务的请求线程全占住。
 */
final class PlatformUpstreamClient {

  /**
   * 上游 JSON 的 mapper。
   *
   * <p>两处配置都不是可有可无的：{@code JavaTimeModule}（上游带 {@code OffsetDateTime}）
   * 以及关掉 {@code FAIL_ON_UNKNOWN_PROPERTIES}（上游<b>加字段</b>不应该把本服务撑爆；
   * 类型不匹配照样炸 —— 那种是真读错了，必须炸）。
   */
  static final ObjectMapper JSON =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  /** 上游分页的默认页大小。 */
  static final int PAGE_SIZE = 200;

  /** 最多翻多少页（分页翻页有上限 —— 不能让它把上游当无限流接口用）。 */
  static final int MAX_PAGES = 25;

  private final RestClient client;
  private final String serviceName;

  private PlatformUpstreamClient(String serviceName, String url) {
    this.serviceName = serviceName;
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

  /** 建一个指向上游的客户端。 */
  static PlatformUpstreamClient to(String serviceName, String url) {
    return new PlatformUpstreamClient(serviceName, url);
  }

  /**
   * GET 一个上游端点并反序列化成 {@code type}。
   *
   * @param uriBuilder 端点路径与查询参数
   * @param bearer 调用方自己的会话（**只转发它，不传租户号**）
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
            "无权读取 " + serviceName + " 的上游事实，本模块无法投影",
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

  // ================================================================ 文件型通道

  /**
   * 以 {@code multipart/form-data} POST 一份文件给上游（18.1.3.1 的**通道 C**：导入委托）。
   *
   * <p>⚠️ <b>只转发调用方自己的 bearer</b>，与 {@link #get} 同一纪律：
   * 上游仍按已认证成员关系解析租户并强制 RLS。
   *
   * <p>⚠️ <b>入参里没有租户号</b>：平台不替上游决定租户。
   */
  <T> T postMultipart(
      java.util.function.Function<org.springframework.web.util.UriBuilder, org.springframework.web.util.UriBuilder>
          uriBuilder,
      String bearer,
      org.springframework.util.MultiValueMap<String, Object> parts,
      Class<T> type) {
    try {
      T result =
          client
              .post()
              .uri(builder -> uriBuilder.apply(builder).build())
              .header("Authorization", bearer)
              .contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
              .body(parts)
              .retrieve()
              .body(type);
      if (result == null) throw unavailable("上游没有返回内容");
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "无权向 " + serviceName + " 提交导入文件，本模块无法代为导入",
            e);
      }
      throw unavailable("上游返回 " + e.getStatusCode().value() + "：导入委托被拒");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("上游不可达");
    }
  }

  /**
   * POST 一个**空体**的 JSON 端点（上游的「推进作业」类端点：预校验 / 执行 / 重投）。
   *
   * <p>⚠️ 与 {@link #postMultipart} 分开：这两种端点的**请求体语义不同**
   * （一个是文件，一个是「请开始做这件事」），混用会让上游按错误的 Content-Type 解析。
   */
  <T> T postEmpty(
      java.util.function.Function<org.springframework.web.util.UriBuilder, org.springframework.web.util.UriBuilder>
          uriBuilder,
      String bearer,
      Class<T> type) {
    try {
      T result =
          client
              .post()
              .uri(builder -> uriBuilder.apply(builder).build())
              .header("Authorization", bearer)
              .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
              .retrieve()
              .body(type);
      if (result == null) throw unavailable("上游没有返回内容");
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "无权推进 " + serviceName + " 的作业，本模块无法代为执行",
            e);
      }
      throw unavailable("上游返回 " + e.getStatusCode().value() + "：作业推进被拒");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("上游不可达");
    }
  }

  /**
   * 取一份**二进制**文件（模板 CSV 之类的非 JSON 载荷）。
   *
   * <p>⚠️ {@code PlatformUpstreamClient} 的 JSON 通道只按 {@code Class<T>} 反序列化，
   * 裸数组会被 Jackson 拆成 {@code List<Object>}、字段全丢 —— 所以二进制载荷走这一支。
   */
  byte[] getBytes(
      java.util.function.Function<org.springframework.web.util.UriBuilder, org.springframework.web.util.UriBuilder>
          uriBuilder,
      String bearer) {
    try {
      byte[] result =
          client
              .get()
              .uri(builder -> uriBuilder.apply(builder).build())
              .header("Authorization", bearer)
              .retrieve()
              .body(byte[].class);
      if (result == null) throw unavailable("上游没有返回文件内容");
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "无权读取 " + serviceName + " 的文件，本模块无法取回",
            e);
      }
      throw unavailable("上游返回 " + e.getStatusCode().value() + "：文件读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("上游不可达");
    }
  }
}
