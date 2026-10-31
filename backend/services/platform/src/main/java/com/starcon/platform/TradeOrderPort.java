package com.starcon.platform;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * trade 服务（端口 8085）的**跨服务只读端口**：销售订单清单（9.x）。
 *
 * <p>蓝图 18.1.3.1 写「上游 = 全部业务域」—— 订单是其中一格。
 * 订单的事实住在 trade 的库里（{@code trade_sales_order} 一族），
 * 本端口只读它，<b>不抄它</b>。
 *
 * <p>⚠️ <b>只转发调用方自己的 bearer，不传租户号</b>：上游仍按已认证成员关系解析租户并强制 RLS。
 *
 * <p>⚠️ <b>列表字段是 {@code rows}，不是 {@code items}</b>：写错会得到 {@code items == null}，
 * 拉取循环第一页就判定「没有数据」⇒ <b>导出文件会是空的</b>，而真相是「字段名写错了」。
 */
@Service
public class TradeOrderPort {

  private final PlatformUpstreamClient client;

  public TradeOrderPort(@Value("${TRADE_URL:http://127.0.0.1:8085}") String url) {
    this.client = PlatformUpstreamClient.to("trade 服务", url);
  }

  /** 上游一行销售订单。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record OrderRow(
      UUID id,
      String orderNo,
      String orderSource,
      String currency,
      BigDecimal grandTotalAmount,
      int lineCount,
      OffsetDateTime submittedAt,
      OffsetDateTime createdAt) {}

  /** 上游一页销售订单（列表字段是 {@code rows}）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record OrderPage(List<OrderRow> rows, int page, int size, long total) {}

  /** 拉一页销售订单（按下单时刻过滤）。 */
  public OrderPage orders(
      LocalDate from, LocalDate to, int page, int size, String bearer) {
    return client.get(
        uriBuilder -> {
          uriBuilder
              .path("/trade/sales-orders/list")
              .queryParam(
                  "orderedFrom",
                  from == null
                      ? null
                      : from.atStartOfDay(ZoneOffset.UTC).toInstant())
              .queryParam(
                  "orderedTo",
                  to == null ? null : to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant())
              .queryParam("page", Math.max(page, 0))
              .queryParam("size", Math.max(size, 1));
          return uriBuilder;
        },
        bearer,
        OrderPage.class);
  }
}
