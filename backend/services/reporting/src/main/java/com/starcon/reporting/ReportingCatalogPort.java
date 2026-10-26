package com.starcon.reporting;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 读取 **3 商品（catalog）**事实的**跨服务只读端口**（catalog 服务，端口 8082）。
 *
 * <ul>
 *   <li>{@code GET /catalog/sku-cost-compositions} —— SKU 成本构成（**库存总额的单位成本**）；
 *   <li>{@code GET /catalog/exchange-rates} —— 汇率（**跨币种折算 + 显示汇率与时点**）。
 * </ul>
 *
 * <p>⚠️ **本服务不建汇率表、不抄汇率**（与 finance 的 {@code CatalogExchangeRatePort} 同一纪律）：
 * 汇率是外部事实，抄一份就会漂移。本服务只在 {@code reporting_currency_rate_snapshot} 里留
 * 「一次测算用的那一档 + 它的生效日」，那是快照，不是本服务拥有汇率。
 *
 * <p>⚠️ **刻意不读上游的 {@code /catalog/exchange-rates/latest}**：那个端点给的是「**当前**汇率」，
 * 而报表要的是「**这个窗口当时**用的那一档」—— 用 latest 会让今天的汇率变化改写上月的报表
 * （卷 13 卷首「今天的汇率变了不能让上月的报表变」）。所以这里只走列表端点，
 * 由服务层按 {@code effectiveDate} 挑出每个币种对最新的一条落成快照。
 */
@Service
public class ReportingCatalogPort {

  /** 上游分页上限。 */
  static final int PAGE_SIZE = 200;

  /** 最多翻多少页。 */
  static final int MAX_PAGES = 25;

  private final ReportingUpstreamClient client;

  public ReportingCatalogPort(@Value("${CATALOG_URL:http://127.0.0.1:8082}") String url) {
    this.client = ReportingUpstreamClient.to("catalog 服务", url);
  }

  // ---------------------------------------------------------------- 成本构成

  /** 上游一行 SKU 成本构成。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CostRow(
      UUID skuId,
      String skuCode,
      String skuName,
      boolean maintained,
      String costMethod,
      String currency,
      BigDecimal packagingCost,
      BigDecimal otherDirectCost,
      BigDecimal purchaseCost) {}

  /** 上游一页成本构成。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CostPage(List<CostRow> items, long total, int page, int size) {}

  /** 拉一页 SKU 成本构成。 */
  public CostPage skuCosts(int page, String bearer) {
    return client.get(
        uriBuilder ->
            uriBuilder
                .path("/catalog/sku-cost-compositions")
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        CostPage.class);
  }

  // ---------------------------------------------------------------- 汇率列表（快照用）

  /** 上游一行汇率（列表形状）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RateListRow(
      UUID id,
      String baseCurrency,
      String quoteCurrency,
      BigDecimal rate,
      LocalDate effectiveDate) {}

  /** 上游一页汇率。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RateListPage(List<RateListRow> items, long total, int page, int size) {}

  /**
   * 拉一页汇率（**生效日不晚于 {@code asOf}**）。
   *
   * <p>⚠️ 上游的 {@code /latest} 是**按需单查**（查不到直接 404），而本服务要的是
   * 「有哪些币种对可折算」—— 所以走列表端点，按 {@code dateTo} 收口，**在服务层挑出
   * 每个币种对的最新一条**再落快照。快照表是 append-only 的，所以挑「最新」这件事
   * 必须在服务层做，不能指望 DB 帮忙。
   */
  public RateListPage rates(LocalDate asOf, int page, String bearer) {
    return client.get(
        uriBuilder ->
            uriBuilder
                .path("/catalog/exchange-rates")
                .queryParam("dateTo", asOf)
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        RateListPage.class);
  }
}
