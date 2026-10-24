package com.starcon.finance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
 * 读取上游 **16.3.2.1 VAT/IOSS（channel 服务）**事实的**跨服务只读端口**。
 *
 * <p>蓝图 12-finance.md 把 16.3.2.1 写成 12.6.1.2 的**上游**：IOSS 订单清单与平台
 * 代收金额的**事实归属在 channel**，本模块只负责把它汇总成申报底稿。
 *
 * <p>⚠️ <b>16.3.2.1 与本模块同批开发中（迁移 V365），集成头里还没有</b>。
 * ⇒ 本端口**刻意不把上游当硬依赖**：
 *
 * <ul>
 *   <li>上游 404 / 不可达 / 形状对不上 ⇒ 本端口<b>显式失败</b>（503，理由写明
 *       「读不到」）；</li>
 *   <li>服务层接住这个失败 ⇒ 底稿里 IOSS 两列<b>留成 NULL</b>（不是 0）并写明
 *       「读不到 IOSS 订单清单」，底稿的其它部分（应税销售额 / 已收 VAT /
 *       可抵扣进项）照常产出。</li>
 * </ul>
 *
 * 🔴 <b>绝不因为上游没交付就编一份 IOSS 清单出来</b>（编出来的清单会被拿去申报）。
 *
 * <p>⚠️ <b>跨服务只走授权 HTTP API</b>：禁止跨服务 SQL / 跨库 FK / 共享视图。
 * 租户<b>不由本端口传递</b>：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * channel 侧仍按已认证成员关系解析租户并强制 RLS。
 */
@Service
public class ChannelIossPort {

  /**
   * 上游只读形状：IOSS 订单清单。
   *
   * <p>⚠️ 16.3.2.1 **与本模块同批开发中**，本仓此刻**没有**这份契约可对齐 ⇒
   * 这里的路径是<b>按 16 卷命名空间惯例</b>定的候选值（首 token {@code vat-ioss}，
   * 与调度者给本车道定的 api-proxy 放行禁令一致）。它**只影响「读不到」这件事
   * 怎么被措辞**，不影响本模块的其它职责 —— 上游交付后改这一行即可。
   */
  static final String ORDERS_PATH = "/channel/vat-ioss/orders";

  /** 上游 JSON 的 mapper（上游会加字段 —— 加字段不该把本模块撑爆）。 */
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  /** 上游一行 IOSS 订单（只声明本模块真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record IossOrderRow(
      UUID id,
      @JsonIgnoreProperties(ignoreUnknown = true) String orderNo,
      @JsonIgnoreProperties(ignoreUnknown = true) String shopCode,
      @JsonIgnoreProperties(ignoreUnknown = true) String countryCode,
      @JsonIgnoreProperties(ignoreUnknown = true) String currency,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal taxableAmount,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal vatAmount,
      @JsonIgnoreProperties(ignoreUnknown = true) LocalDate orderDate) {}

  /** 上游 IOSS 订单清单响应（**形状宽松**：上游可能给 rows，也可能给 items）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record IossOrderList(List<IossOrderRow> rows, List<IossOrderRow> items, Long total) {}

  private final RestClient client;

  public ChannelIossPort(@Value("${CHANNEL_URL:http://127.0.0.1:8083}") String url) {
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
   * 拉上游 IOSS 订单清单（覆盖期间内、属于该辖区的）。
   *
   * @param jurisdictionCode 辖区码（上游受控码表里的值）
   * @param from 期间起始日（含）
   * @param to 期间结束日（含）
   * @param bearer 调用方自己的会话
   * @throws ResponseStatusException 上游读不到时一律 503，理由写明「读不到」
   */
  public List<IossOrderRow> orders(
      String jurisdictionCode, LocalDate from, LocalDate to, String bearer) {
    try {
      IossOrderList result =
          client
              .get()
              .uri(
                  uriBuilder -> {
                    var b = uriBuilder.path(ORDERS_PATH);
                    b.queryParam("jurisdictionCode", jurisdictionCode);
                    b.queryParam("periodStart", from.toString());
                    b.queryParam("periodEnd", to.toString());
                    return b.build();
                  })
              .header("Authorization", bearer)
              .retrieve()
              .body(IossOrderList.class);
      if (result == null) {
        throw unavailable("channel 服务没有返回 IOSS 订单清单");
      }
      var rows = result.rows() != null ? result.rows() : result.items();
      if (rows == null) {
        throw unavailable("channel 服务返回的 IOSS 订单清单里没有订单数组");
      }
      return rows;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED,
            "调用方无权读取上游 IOSS 订单清单（需要 channel 侧的 channel.operation.read 权限）",
            e);
      }
      throw unavailable(
          "channel 服务返回 " + e.getStatusCode().value() + "：IOSS 订单清单读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("channel 服务不可达：IOSS 订单清单读不到");
    }
  }

  /** 上游不可用一律 503，理由写明「读不到」而不是「没有数据」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }
}
