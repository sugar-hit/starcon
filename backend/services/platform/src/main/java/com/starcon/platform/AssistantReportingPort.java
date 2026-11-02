package com.starcon.platform;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 13 分析（reporting 服务）的**跨服务只读端口**：18.4.1.1「对话助手」的四条受控查询都走这里。
 *
 * <p>⚠️ <b>只转发调用方自己的 bearer，不传租户号</b>：上游仍按已认证成员关系解析租户
 * 并强制 RLS。租户隔离因此是<b>两道</b>（platform 侧 RLS + 上游侧 RLS），
 * 而不是「本服务替对方决定租户」。
 *
 * <p>⚠️ <b>取不到就 503</b>（{@link PlatformUpstreamClient} 已经这么做了）：
 * 绝不返回空集合冒充「上游没有数据」—— 一个空集合在答案里会长成「这个店没有利润」，
 * 而真相是「这一格没读出来」。
 *
 * <p>⚠️ <b>响应只声明本模块真正用到的字段</b>，其余字段由 Jackson 忽略
 * （上游加字段不应该把本服务撑爆；类型不匹配照样炸 —— 那种是真读错了）。
 */
@Service
public class AssistantReportingPort {

  /** 商品销量口径码（13.2 的 PRODUCT_SALES_QUANTITY）。 */
  static final String PRODUCT_SALES_QUANTITY = "PRODUCT_SALES_QUANTITY";

  private final PlatformUpstreamClient client;

  public AssistantReportingPort(@Value("${REPORTING_URL:http://127.0.0.1:8088}") String url) {
    this.client = PlatformUpstreamClient.to("reporting 服务", url);
  }

  // ================================================================ 利润报表（店铺维度）

  /** 利润报表一行（多模式利润报表 13.4.1.1）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AssistantProfitRow(
      String dimension,
      String dimensionCode,
      String dimensionLabel,
      String operatingMode,
      String operatingModeLabel,
      LocalDate periodStart,
      LocalDate periodEnd,
      String currency,
      BigDecimal totalCost,
      BigDecimal grossProfit,
      BigDecimal grossMargin,
      boolean grossProfitComparable,
      List<String> missingComponents,
      Long factCount,
      String note) {}

  /** 利润报表响应。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AssistantProfitReport(
      LocalDate from,
      LocalDate to,
      String granularity,
      String dimension,
      List<AssistantProfitRow> rows,
      OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String caliberNote,
      List<String> warnings) {}

  /** 拉店铺维度的利润报表（**真实只读查询**）。 */
  public AssistantProfitReport profitByShop(LocalDate from, LocalDate to, String bearer) {
    return client.get(
        uriBuilder -> {
          uriBuilder
              .path("/reporting/profit-report/report")
              .queryParam("from", from.toString())
              .queryParam("to", to.toString())
              .queryParam("dimension", "SHOP");
          return uriBuilder;
        },
        bearer,
        AssistantProfitReport.class);
  }

  // ================================================================ 库存分析（SKU 下钻）

  /** 库存下钻一行（13.5.1.1）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AssistantInventoryRow(
      String skuCode,
      String skuName,
      String currency,
      String categoryCode,
      String warehouseCode,
      String warehouseName,
      String shopCode,
      String shopName,
      BigDecimal onHandQuantity,
      BigDecimal reservedQuantity,
      BigDecimal availableQuantity,
      BigDecimal unitCost,
      BigDecimal inventoryValue,
      BigDecimal soldQuantity,
      BigDecimal costOfGoodsSold,
      boolean zeroStock,
      boolean overstock,
      boolean thresholdConfigured,
      String abcClass,
      String abcClassLabel,
      boolean comparable,
      String note) {}

  /** 库存下钻响应。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AssistantInventoryDrilldown(
      LocalDate asOf,
      LocalDate from,
      LocalDate to,
      List<AssistantInventoryRow> rows,
      long total,
      int page,
      int size,
      OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String caliberNote,
      List<String> warnings) {}

  /** 拉 SKU 级库存下钻（**真实只读查询**）。 */
  public AssistantInventoryDrilldown inventoryDrilldown(LocalDate from, LocalDate to, String bearer) {
    return client.get(
        uriBuilder -> {
          uriBuilder
              .path("/reporting/inventory-analysis/drilldown")
              .queryParam("from", from.toString())
              .queryParam("to", to.toString())
              .queryParam("size", 200);
          return uriBuilder;
        },
        bearer,
        AssistantInventoryDrilldown.class);
  }

  // ================================================================ 销量趋势（商品维度）

  /** 趋势上的一个点（上游还有环比 / 同比字段，本模块不用）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AssistantTrendPoint(
      LocalDate bucketStart, LocalDate bucketEnd, String bucketLabel, String currency, BigDecimal value) {}

  /** 一条序列（一个指标 × 一个币种 × 一个维度键）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AssistantTrendSeries(
      String metricCode,
      String displayName,
      String unit,
      String dimensionType,
      String dimensionKey,
      String dimensionLabel,
      String currency,
      List<AssistantTrendPoint> points) {}

  /** 销量趋势响应。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AssistantSalesTrend(
      LocalDate from,
      LocalDate to,
      String granularity,
      String timezone,
      List<AssistantTrendSeries> series,
      List<String> warnings,
      String status,
      String emptyReason,
      String emptyNote) {}

  /** 拉商品维度销量趋势（**真实只读查询**）。 */
  public AssistantSalesTrend salesTrend(LocalDate from, LocalDate to, String bearer) {
    return client.get(
        uriBuilder -> {
          uriBuilder
              .path("/reporting/sales-trend")
              .queryParam("from", from.toString())
              .queryParam("to", to.toString())
              .queryParam("granularity", "DAY")
              .queryParam("timezone", "UTC")
              .queryParam("metricCodes", PRODUCT_SALES_QUANTITY);
          return uriBuilder;
        },
        bearer,
        AssistantSalesTrend.class);
  }

  // ================================================================ 生命周期与分层（商品维度）

  /** 分层结果的一行（13.3.1.2）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AssistantLifecycleRow(
      String dimensionType,
      String dimensionId,
      String dimensionKey,
      String dimensionName,
      LocalDate bucketDate,
      BigDecimal salesQuantity,
      BigDecimal recentQuantity,
      BigDecimal priorQuantity,
      BigDecimal declineRatio,
      BigDecimal salesShare,
      String lifecycleSegment,
      String segmentAvailability,
      String segmentNote,
      String recommendedAction,
      String actionNote,
      BigDecimal onHandQuantity,
      OffsetDateTime sourceObservedAt) {}

  /** 分层响应。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AssistantLifecycle(
      LocalDate from,
      LocalDate to,
      String dimensionType,
      List<AssistantLifecycleRow> rows,
      int page,
      int size,
      long total,
      List<String> caliberNotes,
      OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String status,
      String emptyReason,
      String emptyNote) {}

  /** 拉商品维度的生命周期与分层（**真实只读查询**）。 */
  public AssistantLifecycle lifecycle(LocalDate from, LocalDate to, String bearer) {
    return client.get(
        uriBuilder -> {
          uriBuilder
              .path("/reporting/lifecycle-segmentation")
              .queryParam("from", from.toString())
              .queryParam("to", to.toString())
              .queryParam("dimensionType", "SKU")
              .queryParam("size", 200);
          return uriBuilder;
        },
        bearer,
        AssistantLifecycle.class);
  }
}
