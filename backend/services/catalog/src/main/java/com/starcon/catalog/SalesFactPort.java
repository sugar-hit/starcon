package com.starcon.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.starcon.catalog.PriceHistoryModels.PriceHistorySalesTotal;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 5.5.1.1 价格历史：**销量事实的跨服务只读端口**。
 *
 * <p><b>为什么销量必须走端口而不是本地表</b>（AGENTS.md「模块边界纪律」）：
 * 销量事实（订单行数量）属 **09 订单履约 / trade 服务**；本仓 catalog 里**没有**任何
 * Sales / Order 类，跨服务查表、跨库 FK、共享视图都被明令禁止。
 * ⇒ 本模块**不新建**任何销量影子表，只通过本端口读。
 *
 * <p>🔴 <b>「读不到」≠ 「销量是 0」</b>。蓝图加粗句
 * 「**价格历史 + 销量 + 利润三者对齐，才能回答"降价到底有没有用"**」——
 * 三者里少一个，这个问题的答案就不成立。把「读不到销量」写成 0，
 * 页面会显示「降价后销量 0，利润 −X」——那是一个**凭空造出来的结论**，
 * 比「显示读不到」危险得多。
 * 所以：
 *
 * <ul>
 *   <li>未接线（{@code TRADE_URL} 没配）⇒ {@link UnwiredSalesFactPort} **显式抛错**，
 *       由服务层翻成 503，页面上写「销量维度当前未接线」；
 *   <li>已接线但上游不可达 ⇒ 同上，503；
 *   <li>已接线且上游确实没有该区间的销量 ⇒ 返回 {@code quantity = null}，
 *       **不是 0**（「没有销量」与「读不到销量」在页面上要分得开）。
 * </ul>
 *
 * <p>🔴 <b>trade 侧尚未交付这个端点</b>：本端口约定的是
 * {@code GET /trade/sales-facts/sku-summary}（按 SKU × 店铺 × 区间聚合的销量与销售额）。
 * 该端点属于 **9 订单履约** 的读侧，**当前不存在** ⇒ 接线之前一律 503。
 * 本模块**不**因为「上游没有端点」就退化成自己造一份销量表。
 *
 * <p><b>租户上下文必须透传</b>：本端口把调用者的 {@code Authorization} 头原样带过去，
 * 租户由上游从已认证成员关系解析。本端口**不**发明 {@code X-Tenant-Id} 之类的头传租户。
 */
public interface SalesFactPort {

  /**
   * 取一段区间的销量事实。
   *
   * @param bearer 调用者的 Authorization 头（透传给上游）
   * @param from 区间起（含）
   * @param to 区间止（不含）
   */
  PriceHistorySalesTotal sales(
      String bearer, UUID skuId, String platformCode, String siteCode, OffsetDateTime from, OffsetDateTime to);

  /** 上游不可达 / 未交付 / 返回非 2xx。由服务层翻译成对调用方有意义的一句话。 */
  class SalesUnavailableException extends RuntimeException {
    public SalesUnavailableException(String message, Throwable cause) {
      super(message, cause);
    }

    public SalesUnavailableException(String message) {
      super(message);
    }
  }

  /** 真实的 HTTP 实现。与 {@link ChannelPlatformPort.HttpChannelPlatformPort} 同构。 */
  class HttpSalesFactPort implements SalesFactPort {
    private final RestClient client;

    public HttpSalesFactPort(String url) {
      var factory =
          new org.springframework.http.client.JdkClientHttpRequestFactory(
              java.net.http.HttpClient.newBuilder()
                  .connectTimeout(Duration.ofSeconds(3))
                  .build());
      factory.setReadTimeout(Duration.ofSeconds(5));
      this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }

    @Override
    public PriceHistorySalesTotal sales(
        String bearer,
        UUID skuId,
        String platformCode,
        String siteCode,
        OffsetDateTime from,
        OffsetDateTime to) {
      SalesView view;
      try {
        view =
            client
                .get()
                .uri(
                    uriBuilder ->
                        uriBuilder
                            .path("/trade/sales-facts/sku-summary")
                            .queryParam("skuId", skuId)
                            .queryParam("platformCode", platformCode)
                            .queryParam("siteCode", siteCode)
                            .queryParam("from", from.toString())
                            .queryParam("to", to.toString())
                            .build())
                .header("Authorization", bearer)
                .retrieve()
                .body(SalesView.class);
      } catch (RestClientResponseException e) {
        throw translate(e);
      } catch (RestClientException e) {
        throw new SalesUnavailableException(
            "销量服务暂时不可达，无法对齐销量：判断「降价到底有没有用」需要价格、销量、利润三者，"
                + "少一个这个问题就没有答案。本模块不会把读不到的销量当成 0 —— "
                + "那会让每次降价都显示成「降价后一件没卖出去」。请稍后重试",
            e);
      }
      if (view == null) {
        throw new SalesUnavailableException("销量服务没有返回销量结果");
      }
      return new PriceHistorySalesTotal(
          view.skuId() == null ? skuId : view.skuId(),
          view.platformCode() == null ? platformCode : view.platformCode(),
          view.siteCode() == null ? siteCode : view.siteCode(),
          view.quantity(),
          view.amount(),
          view.currency(),
          from,
          to,
          view.note());
    }

    private static SalesUnavailableException translate(RestClientResponseException e) {
      int status = e.getStatusCode().value();
      if (status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.FORBIDDEN.value()) {
        return new SalesUnavailableException(
            "销量服务拒绝了这次调用（"
                + status
                + "）：跨服务调用必须透传当前用户的 Authorization 头，租户由销量服务从成员关系解析。"
                + "这里选择明确拒绝而不是放行");
      }
      if (status == HttpStatus.NOT_FOUND.value()) {
        return new SalesUnavailableException(
            "销量服务尚未提供「按 SKU × 店铺聚合销量」的只读端点（GET /trade/sales-facts/sku-summary）："
                + "该端点属 9 订单履约的读侧，当前未交付。销量维度因此**未接线** —— "
                + "页面会显示「销量未接线」，而不是显示 0");
      }
      return new SalesUnavailableException("销量服务返回 " + status + "，读不到销量。请稍后重试", e);
    }
  }

  /**
   * 上游未配置时的兜底实现。
   *
   * <p>**显式抛错而不是静默返回 0**：{@code TRADE_URL} 没配时，一个销量数字都读不到。
   * 返回 0 会让页面显示「降价后销量 0」——那是一个凭空造出来的结论。
   */
  class UnwiredSalesFactPort implements SalesFactPort {
    @Override
    public PriceHistorySalesTotal sales(
        String bearer,
        UUID skuId,
        String platformCode,
        String siteCode,
        OffsetDateTime from,
        OffsetDateTime to) {
      throw new SalesUnavailableException(
          "销量服务地址未配置（TRADE_URL 为空）：销量事实的唯一来源是 9 订单履约的授权只读端点，"
              + "地址没配就一个销量数字都读不到。蓝图要求「价格历史 + 销量 + 利润三者对齐」，"
              + "缺了销量这一项就答不了「降价到底有没有用」——本模块不会用 0 冒充销量");
    }
  }

  /** 上游返回的形状（只读本模块用得到的列）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SalesView(
      UUID skuId,
      String platformCode,
      String siteCode,
      BigDecimal quantity,
      BigDecimal amount,
      String currency,
      String note) {}
}
