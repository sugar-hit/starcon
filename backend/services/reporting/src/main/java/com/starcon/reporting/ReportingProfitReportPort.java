package com.starcon.reporting;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 13.4.1.1 多模式利润报表的**跨服务只读端口**。
 *
 * <p>本模块的上游一共三条线（蓝图「输入」句：成本数据 12.4、费用数据 12.3/12.4、订单收入、
 * 模式标记 2.3.2.1、汇率快照）：
 *
 * <ol>
 *   <li>{@code channel} —— {@code GET /channel/shops}：店铺 + **经营模式（2.3.2.1 的
 *       {@code shop.operating_mode}）**。模式标记**只**从这里来，本模块**不硬编码模式当真相**；
 *   <li>{@code finance} —— {@code GET /finance/cost-collections/rules}（归集规则的
 *       {@code fee_category} ⇒ 成本项归属）、{@code GET /finance/cost-allocations/pools}（费用池
 *       ⇒ 归属期间 + 币种）、{@code GET /finance/cost-allocations/batches}（分摊批次）、
 *       {@code GET /finance/cost-allocations/results}（**12.4.2.2 的分摊结果** ⇒ 成本落到谁头上）；
 *   <li>订单收入与费用明细复用既有端口：{@link ReportingTradePort}（trade 订单 + 明细）、
 *       {@link ReportingSalesTrendPort}（trade 平台单 ⇒ 订单挂到哪个店铺）、
 *       {@link ReportingSettlementPort}（finance 12.3.1.2 结算明细）、
 *       {@link ReportingProductSalesPort}（channel 平台商品 ⇒ SKU 挂到哪个 MSKU）。
 * </ol>
 *
 * <p>⚠️ **不传租户号**：转发的是调用方自己的 {@code Authorization} bearer，上游侧仍按
 * 已认证成员关系解析租户并强制 RLS。
 *
 * <p>⚠️ **口径纪律：费率与分摊都在上游**。本端口**只读**上游算好的结果，
 * **不重算费率、不重算分摊** —— 重算就会出现第二个真相（规则一改，本模块的毛利就变成假的）。
 */
@Service
public class ReportingProfitReportPort {

  /** 上游分页上限。 */
  static final int PAGE_SIZE = 200;

  /** 最多翻多少页。 */
  static final int MAX_PAGES = 25;

  private final ReportingUpstreamClient channel;
  private final ReportingUpstreamClient finance;

  public ReportingProfitReportPort(
      @Value("${CHANNEL_URL:http://127.0.0.1:8083}") String channelUrl,
      @Value("${FINANCE_URL:http://127.0.0.1:8087}") String financeUrl) {
    this.channel = ReportingUpstreamClient.to("channel 服务", channelUrl);
    this.finance = ReportingUpstreamClient.to("finance 服务", financeUrl);
  }

  // ================================================================ 店铺与经营模式（2.3.2.1）

  /**
   * 上游一行店铺（含**经营模式**）。
   *
   * <p>⚠️ **字段名必须与上游逐字一致**（channel {@code ChannelModels.ShopView}）：
   * 本客户端关掉了 {@code FAIL_ON_UNKNOWN_PROPERTIES}，写错字段名不会报错，
   * 只会静默变成 null —— 那会让整份报表的经营模式变成「未归属」。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ShopRow(
      UUID id,
      String code,
      String name,
      String platformCode,
      String platformName,
      String operatingMode) {}

  /**
   * 拉店铺列表（含经营模式）。
   *
   * <p>⚠️ 上游返回的是**裸数组**（不是 page 对象）⇒ 必须走 {@code list(...)} 那一支，
   * 否则拿到的是 {@code LinkedHashMap}，经营模式全是 null。
   */
  public List<ShopRow> shops(String bearer) {
    return channel.list(
        uriBuilder -> uriBuilder.path("/channel/shops"),
        bearer,
        new org.springframework.core.ParameterizedTypeReference<List<ShopRow>>() {});
  }

  // ================================================================ 12.4 归集规则（成本项归属）

  /**
   * 上游一条归集规则（12.4.2.1）。
   *
   * <p>⚠️ {@code feeCategory} 是**上游固定词表**（PLATFORM_FEE / AD_FEE / SUBSCRIPTION_FEE /
   * LOGISTICS_FEE / HEAD_MILE_FEE / PERSONNEL_COST / OFFICE_COST），
   * 由 DB 的 CHECK 约束钉死 ⇒ 本模块据此把归集池映射到**成本项**。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CollectionRuleRow(
      UUID id,
      String ruleCode,
      String poolKey,
      String feeSource,
      String feeCategory,
      String targetDimension,
      String status) {}

  /** 拉归集规则列表（上游返回**裸数组**）。 */
  public List<CollectionRuleRow> collectionRules(String bearer) {
    return finance.list(
        uriBuilder -> uriBuilder.path("/finance/cost-collections/rules"),
        bearer,
        new org.springframework.core.ParameterizedTypeReference<List<CollectionRuleRow>>() {});
  }

  // ================================================================ 12.4.2.2 费用池 / 分摊批次 / 分摊结果

  /** 上游一个费用池（归属期间 + 币种都在池上）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PoolRow(
      UUID poolId,
      String poolKey,
      String poolName,
      String dimension,
      LocalDate periodStart,
      LocalDate periodEnd,
      String currency,
      BigDecimal totalAmount,
      boolean allocationAvailable) {}

  /** 上游费用池分页。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PoolPage(List<PoolRow> items, int page, int size, long total) {}

  /** 拉一页费用池。 */
  public PoolPage pools(int page, String bearer) {
    return finance.get(
        uriBuilder ->
            uriBuilder
                .path("/finance/cost-allocations/pools")
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        PoolPage.class);
  }

  /** 上游一个分摊批次（币种 + 池 + 生效状态）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record BatchRow(
      UUID id,
      UUID poolId,
      String poolKey,
      String poolName,
      String currency,
      BigDecimal allocatedTotal,
      String status,
      java.time.OffsetDateTime allocatedAt) {}

  /** 上游分摊批次分页。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record BatchPage(List<BatchRow> items, int page, int size, long total) {}

  /** 拉一页分摊批次（**只取已生效的批次**：作废 / 已回退的批次不参与利润）。 */
  public BatchPage batches(int page, String bearer) {
    return finance.get(
        uriBuilder ->
            uriBuilder
                .path("/finance/cost-allocations/batches")
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        BatchPage.class);
  }

  /** 上游一条分摊结果（**12.4.2.2 的分摊额落到谁头上**）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AllocationResultRow(
      UUID id,
      UUID batchId,
      String targetDimension,
      String targetCode,
      String targetName,
      boolean basisAvailable,
      BigDecimal allocatedAmount,
      String basisNote) {}

  /** 上游分摊结果分页。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AllocationResultPage(List<AllocationResultRow> items, int page, int size, long total) {}

  /** 拉一页分摊结果（按批次 + 维度筛）。 */
  public AllocationResultPage allocationResults(
      UUID batchId, String dimension, int page, String bearer) {
    return finance.get(
        uriBuilder -> {
          var b = uriBuilder.path("/finance/cost-allocations/results");
          b = b.queryParam("batchId", batchId);
          if (dimension != null && !dimension.isBlank()) b = b.queryParam("dimension", dimension);
          return b.queryParam("page", Math.max(page, 0)).queryParam("size", PAGE_SIZE);
        },
        bearer,
        AllocationResultPage.class);
  }
}
