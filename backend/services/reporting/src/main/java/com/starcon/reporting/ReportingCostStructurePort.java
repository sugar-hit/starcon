package com.starcon.reporting;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriBuilder;

/**
 * 13.4.2.1 成本结构的**跨服务只读端口**（只补两个既有端口没覆盖的上游）。
 *
 * <p>⚠️ **为什么不复用现成的端口**：本模块要读的上游里，**八路上游有六路**已被既有端口覆盖
 * （catalog 5.2.3.1 / 5.2.3.2 / 5.2.1.3、finance 12.3.1.2 / 12.4.1.2、trade 11.1.1.1），
 * 本端口**只补两路**：
 *
 * <ul>
 *   <li>{@code GET /catalog/skus} —— SKU → 类目的映射（**上游成本事实里没有类目**，
 *       本模块自己补这一维；映射不到就留空串，**不猜一个类目**）；</li>
 *   <li>{@code GET /finance/cost-collections/entries} —— 12.4.2.1 费用归集条目
 *       （订阅费 / 人员费用 / 办公费用 ⇒ 归到「其它」这一类成本）。</li>
 * </ul>
 *
 * <p>⚠️ **不传租户号**：转发的是调用方自己的 {@code Authorization} bearer，
 * 上游侧仍按已认证成员关系解析租户并强制 RLS。
 *
 * <p>⚠️ **本模块不重算任何口径**：只读上游算好的分摊 / 归集结果。
 */
@Service
public class ReportingCostStructurePort {

  /** 上游分页上限。 */
  static final int PAGE_SIZE = 200;

  private final ReportingUpstreamClient catalog;
  private final ReportingUpstreamClient finance;

  public ReportingCostStructurePort(
      @Value("${CATALOG_URL:http://127.0.0.1:8082}") String catalogUrl,
      @Value("${FINANCE_URL:http://127.0.0.1:8087}") String financeUrl) {
    this.catalog = ReportingUpstreamClient.to("catalog 服务", catalogUrl);
    this.finance = ReportingUpstreamClient.to("finance 服务", financeUrl);
  }

  // ==================================================================
  // catalog：SKU → 类目
  // ==================================================================

  /**
   * 上游一行 SKU（只声明本模块真正用到的字段）。
   *
   * <p>⚠️ {@code category} 是**类目名**（上游 {@code SkuListRow.category}），可能为 null
   * —— 没归类的 SKU 就是 null，那种 SKU 的类目维度**留空串**（不猜）。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CatalogSkuRow(UUID id, String code, String category) {}

  /** 上游一页 SKU（列表字段是 {@code items}）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CatalogSkuPage(List<CatalogSkuRow> items, long total, int page, int size) {}

  /** 拉一页 SKU（只为拿 {@code code → category} 映射）。 */
  public CatalogSkuPage skus(int page, String bearer) {
    return catalog.get(
        uriBuilder ->
            uriBuilder
                .path("/catalog/skus")
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        CatalogSkuPage.class);
  }

  // ==================================================================
  // finance：12.4.2.1 费用归集条目（「其它」这一类成本）
  // ==================================================================

  /**
   * 上游一条归集条目。
   *
   * <p>⚠️ **字段名必须与上游逐字一致**（finance {@code FinanceCostCollectionEntry}）：
   * 本客户端关掉了 {@code FAIL_ON_UNKNOWN_PROPERTIES}，写错字段名不会报错，
   * 只会静默变成 null —— 那会让金额变成 null 而不是报错，比不写还危险。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CostCollectionEntry(
      UUID id,
      UUID poolId,
      String feeSource,
      String feeCategory,
      String targetDimension,
      LocalDate baseDate,
      LocalDate periodStart,
      LocalDate periodEnd,
      String currency,
      BigDecimal amount,
      BigDecimal quantity,
      String sourceRefKey,
      String sourceRefCode,
      String poolKey,
      String ruleCode) {}

  /** 上游一页归集条目（列表字段是 {@code items}）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CostCollectionEntryPage(
      List<CostCollectionEntry> items, int page, int size, long total) {}

  /**
   * 拉一页归集条目（按费用项 + 基准日区间过滤）。
   *
   * @param feeCategory 费用项（如 {@code SUBSCRIPTION_FEE} / {@code PERSONNEL_COST} / {@code OFFICE_COST}）
   */
  public CostCollectionEntryPage costCollectionEntries(
      String feeCategory, LocalDate from, LocalDate to, int page, String bearer) {
    return finance.get(
        uriBuilder -> {
          var b = uriBuilder.path("/finance/cost-collections/entries");
          b = param(b, "feeCategory", feeCategory);
          b = param(b, "baseDateFrom", from);
          b = param(b, "baseDateTo", to);
          return b.queryParam("page", Math.max(page, 0)).queryParam("size", PAGE_SIZE);
        },
        bearer,
        CostCollectionEntryPage.class);
  }

  // ==================================================================
  // 工具
  // ==================================================================

  /**
   * 条件查询参数。
   *
   * <p>⚠️ **空值不带进 URL**：{@code UriBuilder.queryParam(name, null)} 会渲染成
   * {@code ?name}（没有 {@code =}），上游按日期绑定时会直接 400。
   */
  private static UriBuilder param(UriBuilder builder, String name, Object value) {
    if (value == null) return builder;
    if (value instanceof String s && s.isBlank()) return builder;
    return builder.queryParam(name, value);
  }
}
