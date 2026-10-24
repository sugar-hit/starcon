package com.starcon.reporting;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 读取 **9 订单（trade）**事实的**跨服务只读端口**（trade 服务，端口 8085）。
 *
 * <p>蓝图 13.1.1.1 写明上游是「12 财务、<b>9 订单</b>、7 库存、14 广告」。订单真值在 trade：
 *
 * <ul>
 *   <li>{@code GET /trade/sales-orders/list} —— 销售订单列表（金额、币种、下单时刻）；
 *   <li>{@code GET /trade/sales-orders/detail/{id}} —— 订单明细行（**销量**与 SKU 下钻的唯一来源）；
 *   <li>{@code GET /trade/aftersales/cases} —— 售后退货单（**退货额**）。
 * </ul>
 *
 * <p>AGENTS.md 模块边界纪律禁止跨服务 SQL / 跨库 FK / 共享视图，
 * 所以本端口<b>只经 trade 的授权 HTTP API</b>读，不直连对方库。
 *
 * <p>⚠️ <b>不传租户号</b>：转发的是调用方自己的 {@code Authorization} bearer，
 * trade 侧仍按已认证成员关系解析租户并强制 RLS。租户隔离是**两道**。
 */
@Service
public class ReportingTradePort {

  /** 上游分页上限（trade 侧把 size 钳在 200 以内）。 */
  static final int PAGE_SIZE = 200;

  /** 最多翻多少页（{@code 200 × 25 = 5000} 行）。 */
  static final int MAX_PAGES = 25;

  /**
   * 一次刷新里最多取多少张订单的**明细行**。
   *
   * <p>⚠️ 销量与 SKU 下钻只存在于订单明细里 ⇒ 必须逐单取明细（N+1）。
   * 这个上限是**有意的**：没有上限时一个大租户能把本服务的请求线程占满。
   * 触到上限时**如实报 truncated**（而不是悄悄少算）。
   */
  static final int MAX_DETAIL_FETCHES = 50;

  private final ReportingUpstreamClient client;

  public ReportingTradePort(@Value("${TRADE_URL:http://127.0.0.1:8085}") String url) {
    this.client = ReportingUpstreamClient.to("trade 服务", url);
  }

  // ---------------------------------------------------------------- 销售订单

  /**
   * 上游一行销售订单（只声明本服务真正用到的字段）。
   *
   * <p>⚠️ **字段名必须与上游逐字一致**（trade {@code ManualOrderModels.SalesOrderRow}）：
   * 本客户端关掉了 {@code FAIL_ON_UNKNOWN_PROPERTIES}，所以**写错字段名不会报错，
   * 只会静默变成 null** —— 那会让销售额变成 null 而不是报错，比不写还危险。
   * 上游真有 {@code orderSource}（枚举，序列化成字符串）与 {@code status}（不是
   * {@code documentStatus}）；本服务只用到金额、币种、下单时刻与单号，其余字段不声明。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SalesOrderRow(
      UUID id,
      String orderNo,
      String orderSource,
      String currency,
      BigDecimal grandTotalAmount,
      int lineCount,
      OffsetDateTime submittedAt,
      OffsetDateTime createdAt) {}

  /**
   * 上游一页销售订单。
   *
   * <p>⚠️ **列表字段是 {@code rows}，不是 {@code items}**（上游
   * {@code ManualOrderModels.SalesOrderListPage}）。写错会得到 {@code items == null}，
   * 拉取循环第一页就判定「没有数据」⇒ **销售额恒为 0**。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SalesOrderPage(List<SalesOrderRow> rows, int page, int size, long total) {}

  /** 上游一行订单明细。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record OrderLineRow(
      String skuCode, String skuName, BigDecimal quantity, BigDecimal unitPrice, BigDecimal lineAmount) {}

  /** 上游订单明细（头 + 行）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SalesOrderDetail(
      @JsonIgnoreProperties(ignoreUnknown = true) SalesOrderHeader header, List<OrderLineRow> lines) {}

  /** 上游订单头（上游 {@code SalesOrderOverviewHeader} 的子集）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SalesOrderHeader(
      String orderNo, String currency, BigDecimal grandTotalAmount, OffsetDateTime submittedAt) {}

  /** 拉一页销售订单（按下单时刻过滤）。 */
  public SalesOrderPage salesOrders(LocalDate from, LocalDate to, int page, String bearer) {
    return client.get(
        uriBuilder ->
            uriBuilder
                .path("/trade/sales-orders/list")
                .queryParam(
                    "orderedFrom",
                    from == null ? null : from.atStartOfDay(java.time.ZoneOffset.UTC).toInstant())
                .queryParam(
                    "orderedTo",
                    to == null ? null : to.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant())
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        SalesOrderPage.class);
  }

  /** 拉一张订单的明细行（销量与 SKU 下钻的唯一来源）。 */
  public SalesOrderDetail salesOrderDetail(UUID id, String bearer) {
    return client.get(
        uriBuilder -> uriBuilder.path("/trade/sales-orders/detail/" + id),
        bearer,
        SalesOrderDetail.class);
  }

  // ---------------------------------------------------------------- 售后退货

  /** 上游一行售后单。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AftersalesRow(
      UUID id,
      String caseNo,
      String orderNo,
      String caseType,
      String status,
      BigDecimal requestedAmount,
      String currency,
      BigDecimal requestedQuantity,
      OffsetDateTime receivedAt) {}

  /**
   * 上游一页售后单。
   *
   * <p>⚠️ **列表字段是 {@code rows}，不是 {@code items}**（上游
   * {@code AftersalesCaseModels.AftersalesCasePage}）。写错 ⇒ 退货额恒为 0。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AftersalesPage(List<AftersalesRow> rows, int page, int size, long total) {}

  /** 拉一页售后退货单（按受理时刻过滤）。 */
  public AftersalesPage aftersalesCases(LocalDate from, LocalDate to, int page, String bearer) {
    return client.get(
        uriBuilder ->
            uriBuilder
                .path("/trade/aftersales/cases")
                .queryParam(
                    "receivedFrom",
                    from == null ? null : from.atStartOfDay(java.time.ZoneOffset.UTC).toInstant())
                .queryParam(
                    "receivedTo",
                    to == null ? null : to.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant())
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        AftersalesPage.class);
  }
}
