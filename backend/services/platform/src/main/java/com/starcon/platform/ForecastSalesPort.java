package com.starcon.platform;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * **13 分析（reporting 服务，端口 8088）**的跨服务只读端口：按 SKU 的逐日销量。
 *
 * <p>蓝图 18.4.2.2 的关系句点名的上游是「13 分析、6 采购、7 库存」——
 * 历史销量这一格归 <b>13 分析</b>（它的口径是「商品维度销量 = 销售订单明细行的数量之和，
 * 按 SKU 码分组」）。
 *
 * <p>⚠️ <b>只转发调用方自己的 bearer，不传租户号</b>：上游仍按已认证成员关系解析租户
 * 并强制 RLS。租户隔离因此是<b>两道</b>（platform 侧 RLS + 上游侧 RLS）。
 *
 * <p>⚠️ <b>取不到就 503</b>，绝不返回空集合冒充「上游没有销量」——
 * 一个空集合在预测页面上会长成「这个 SKU 没销量」，而真相是「这一格没读出来」。
 * 「上游确实没有数据」由上游自己在响应里说明（{@code emptyReason}），本端口如实搬运。
 *
 * <p>⚠️ <b>单价不自己编</b>：上游的商品销量口径**不带价格**。
 * 本端口只搬运销量与（可选的）成交金额；单价的推导规则见
 * {@link ForecastSalesPort#unitPrice} 的注释，**多个币种时不给单价**。
 */
@Service
public class ForecastSalesPort {

  /** 13 分析里「商品维度销量」的指标码（SKU 口径）。 */
  static final String PRODUCT_SALES_QUANTITY = "PRODUCT_SALES_QUANTITY";

  /** 13 分析里「商品维度成交金额」的指标码（SKU 口径，按币种分列）。 */
  static final String PRODUCT_SALES_AMOUNT = "PRODUCT_SALES_AMOUNT";

  private final PlatformUpstreamClient client;

  public ForecastSalesPort(@Value("${REPORTING_URL:http://127.0.0.1:8088}") String url) {
    this.client = PlatformUpstreamClient.to("reporting 服务", url);
  }

  /** 上游趋势里的一个点（一个桶 × 一个 SKU × 一个指标）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TrendPoint(LocalDate bucketStart, BigDecimal value) {}

  /** 上游一条序列（一个指标 × 一个币种 × 一个维度键）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TrendSeries(
      String metricCode,
      String dimensionType,
      String dimensionKey,
      String dimensionLabel,
      String currency,
      List<TrendPoint> points) {}

  /** 上游趋势响应（只声明本模块真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TrendResponse(
      LocalDate from,
      LocalDate to,
      String granularity,
      List<TrendSeries> series,
      String status,
      String emptyReason,
      String emptyNote,
      List<String> warnings) {}

  /**
   * 一个 SKU 在一天上的销量事实（端口内部口径）。
   *
   * @param units 销量（件）
   * @param revenue 成交金额（**可为 null**：上游只给销量时算不出来）
   * @param currency 币种（有金额才有币种）
   * @param unitPrice 单价（**可为 null**：多个币种时无法归一，见 {@link #unitPrice}）
   */
  public record DailySales(
      String skuCode,
      String skuName,
      LocalDate businessDate,
      BigDecimal units,
      BigDecimal revenue,
      String currency,
      BigDecimal unitPrice) {}

  /**
   * 拉一段窗口内按 SKU 的逐日销量。
   *
   * <p>⚠️ **上游自己的「没有数据」不是「读不到」**：上游返回空序列时，
   * 本方法返回空列表（**不写一行台账**），由服务层如实告诉调用方
   * 「上游窗口内没有销量事实」；上游读不到时上游客户端已经翻成 503。
   */
  public List<DailySales> salesBySku(LocalDate from, LocalDate to, String bearer) {
    var response =
        client.get(
            uriBuilder -> {
              uriBuilder
                  .path("/reporting/sales-trend")
                  .queryParam("from", from.toString())
                  .queryParam("to", to.toString())
                  .queryParam("granularity", "DAY")
                  .queryParam("timezone", "UTC")
                  .queryParam("metricCodes", PRODUCT_SALES_QUANTITY + "," + PRODUCT_SALES_AMOUNT);
              return uriBuilder;
            },
            bearer,
            TrendResponse.class);

    // 先按 (sku, 日期) 归拢销量，再按 (sku, 日期) 归拢金额（金额按币种分开）。
    var units = new LinkedHashMap<String, DailySales>();
    var amounts = new LinkedHashMap<String, Map<String, BigDecimal>>();
    for (var series : nullSafe(response.series())) {
      if (!PRODUCT_SALES_QUANTITY.equals(series.metricCode())
          && !PRODUCT_SALES_AMOUNT.equals(series.metricCode())) continue;
      if (series.dimensionKey() == null || series.dimensionKey().isBlank()) continue;
      for (var point : nullSafe(series.points())) {
        if (point.bucketStart() == null || point.value() == null) continue;
        var key = series.dimensionKey() + "@" + point.bucketStart();
        if (PRODUCT_SALES_QUANTITY.equals(series.metricCode())) {
          // 🔴 上游同一 SKU 同一天出现两条销量序列（口径异常）⇒ **不累加**：
          //   累加会把基线直接抬高，预测偏高 ⇒ 补货偏多。取第一条并记账。
          units.putIfAbsent(
              key,
              new DailySales(
                  series.dimensionKey(),
                  series.dimensionLabel() == null ? "" : series.dimensionLabel(),
                  point.bucketStart(),
                  point.value(),
                  null,
                  null,
                  null));
        } else {
          var byCurrency =
              amounts.computeIfAbsent(key, k -> new LinkedHashMap<String, BigDecimal>());
          byCurrency.put(series.currency() == null ? "" : series.currency(), point.value());
        }
      }
    }

    var out = new ArrayList<DailySales>();
    for (var entry : units.entrySet()) {
      var base = entry.getValue();
      var byCurrency = amounts.get(entry.getKey());
      BigDecimal revenue = null;
      String currency = null;
      if (byCurrency != null && byCurrency.size() == 1) {
        var only = byCurrency.entrySet().iterator().next();
        if (!only.getKey().isBlank()) {
          revenue = only.getValue();
          currency = only.getKey();
        }
      }
      out.add(
          new DailySales(
              base.skuCode(),
              base.skuName(),
              base.businessDate(),
              base.units(),
              revenue,
              currency,
              unitPrice(revenue, base.units())));
    }
    out.sort(
        (a, b) -> {
          var byCode = a.skuCode().compareTo(b.skuCode());
          return byCode != 0 ? byCode : a.businessDate().compareTo(b.businessDate());
        });
    return out;
  }

  /**
   * 单价 = 成交金额 ÷ 销量。
   *
   * <p>🔴 <b>只在「恰好一个币种」时才算</b>：销量口径与币种无关（它是全渠道合计），
   * 而金额按币种分列 ⇒ 多个币种时「金额 ÷ 销量」会把不同币种混在一起，
   * 算出来的「单价」是假的。所以那种情况下单价为 <b>null</b>，
   * 由租户人工录入价格（价格弹性因此会如实报「样本不足」）。
   */
  static BigDecimal unitPrice(BigDecimal revenue, BigDecimal units) {
    if (revenue == null || units == null || units.signum() <= 0) return null;
    return revenue.divide(units, 6, java.math.RoundingMode.HALF_UP);
  }

  /** 上游「没有数据」时的说明（如实搬运，不自己编一句「暂无数据」）。 */
  public static String emptyNote(TrendResponse response) {
    if (response == null) return "上游没有返回内容";
    if (response.emptyReason() != null && !response.emptyReason().isBlank()) {
      return response.emptyReason() + (response.emptyNote() == null ? "" : "：" + response.emptyNote());
    }
    return response.status() == null ? "上游没有返回内容" : response.status();
  }

  private static <T> List<T> nullSafe(List<T> list) {
    return list == null ? List.of() : list;
  }
}
