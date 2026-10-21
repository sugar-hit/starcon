package com.starcon.warehouse;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 7.7.2.2 的**备货预报只读端口** —— 10.4.2.2（备货预报）的唯一来源。
 *
 * <p>蓝图 7.7.2.2 的上游之一是「10.4.2.2 备货预报」。该模块**已交付**，但它在
 * <b>channel 服务</b>（{@code warehouse_forecast} 等表，迁移 {@code V243__stock_forecast.sql}），
 * 而本模块在 warehouse —— AGENTS.md 模块边界纪律禁止跨服务 SQL / 跨库 FK / 共享视图。
 * 因此预报单与发运数据一律经本端口**只读**取回，读到的 {@code forecastId} / {@code forecastNo}
 * 只是**定位键**，本库不建外键、不建影子表。
 *
 * <p>🔴 <b>「读不到」与「确实没有这次预报」必须分得开</b>：
 *
 * <ul>
 *   <li>上游<b>不可达</b> → 抛 503，理由写明「读不到」。<b>绝不</b>返回空明细冒充
 *       「这次预报没有货要备」—— 那正是会真的少备一批货的路径。
 *   <li>上游<b>正常返回、但没有这张预报单</b> → {@code known=false} + 明细为空，
 *       理由写明「无记录 ≠ 没有备货需求」。
 * </ul>
 *
 * <p><b>租户不由本端口传递租户号</b>：转发的是调用方自己的 {@code Authorization} bearer，
 * channel 侧仍按已认证成员关系解析租户并强制 RLS。
 */
@Service
public class ChannelForecastPort {

  /** channel 的预报单详情读取出口。 */
  static final String FORECAST_PATH = "/channel/stock-forecasts/{id}";

  private final RestClient client;

  public ChannelForecastPort(@Value("${CHANNEL_URL:http://127.0.0.1:8082}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(8));
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  /** channel 的预报单头（**只声明本模块真正用到的字段**）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ForecastRow(
      UUID id,
      String forecastNo,
      String accountName,
      String status,
      OffsetDateTime expectedArrivalAt,
      BigDecimal totalQuantity,
      int lineCount) {}

  /** channel 的预报明细行。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ForecastLineRow(String skuCode, BigDecimal quantity, String unit) {}

  /** channel 的预报单详情。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ForecastDetail(ForecastRow forecast, List<ForecastLineRow> lines) {}

  /**
   * 一次预报的结论。
   *
   * @param known 上游是否给出了这张预报单。false 时明细为空（**不是**「这张预报没有货」）
   */
  public record ForecastView(
      UUID forecastId,
      String forecastNo,
      boolean known,
      String status,
      OffsetDateTime expectedArrivalAt,
      BigDecimal totalQuantity,
      List<ForecastLineRow> lines,
      String note) {}

  /**
   * 读一张备货预报单。
   *
   * @param forecastId 预报单标识
   * @param bearer 调用方自己的 bearer（转发，channel 侧按成员关系解析租户）
   * @throws ResponseStatusException 403（无权读）/ 503（channel 读不到）
   */
  public ForecastView forecast(UUID forecastId, String bearer) {
    ForecastDetail detail;
    try {
      detail =
          client
              .get()
              .uri(FORECAST_PATH, forecastId)
              .header("Authorization", bearer)
              .retrieve()
              .body(ForecastDetail.class);
    } catch (RestClientResponseException e) {
      int code = e.getStatusCode().value();
      if (code == 404) {
        // 上游正常应答，只是没有这张预报单：无记录 ≠ 没有备货需求。
        return new ForecastView(
            forecastId,
            null,
            false,
            null,
            null,
            null,
            List.of(),
            "channel 没有这张备货预报单的记录（无记录 ≠ 没有备货需求）");
      }
      if (code == 401 || code == 403) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "当前身份无权读取备货预报，托管备货单暂时不能按预报预填", e);
      }
      throw unavailable("channel 服务返回 " + code + "：备货预报读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("channel 服务不可达，备货预报读不到");
    }
    if (detail == null || detail.forecast() == null) {
      throw unavailable("channel 服务没有返回备货预报内容");
    }
    var head = detail.forecast();
    var lines = detail.lines() == null ? List.<ForecastLineRow>of() : detail.lines();
    return new ForecastView(
        head.id() == null ? forecastId : head.id(),
        head.forecastNo(),
        true,
        head.status(),
        head.expectedArrivalAt(),
        head.totalQuantity(),
        lines,
        "备货预报来自 channel 的 10.4.2.2（只读，本服务不建影子表、不跨库写）");
  }

  /** 上游不可用一律 503，理由写明「读不到」而不是「没有备货需求」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        detail
            + "：备货预报（10.4.2.2）的唯一来源是 channel 的授权只读接口"
            + "（跨库视图与跨服务 SQL 均被 AGENTS.md 禁止），本服务不会凭空报一份预报");
  }
}
