package com.starcon.reporting;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 13.5.3.1 物流分析的**跨服务只读端口**（channel / trade / catalog 三路）。
 *
 * <p>蓝图 13.5.3.1 的输入句写着「物流数据（10）；成本数据」，关系句写着
 * 「上游 = 10 物流与头程」。本端口负责这三侧：
 *
 * <ul>
 *   <li>{@code GET /channel/logistics-cost-analysis/overview} —— **全口径成本**
 *       （10.6.1.1）：逐币种的尾程 / 头程分摊 / 关税 / 包材 / 合计 + 覆盖说明。
 *       ⇒ 本模块的**成本总额口径**全部取自它。</li>
 *   <li>{@code GET /channel/logistics-cost-analysis/by-channel} —— **渠道对比**
 *       （10.6.1.1）：承运商 × 渠道 × 币种的金额与票数。</li>
 *   <li>{@code GET /channel/logistics-cost-analysis/allocations} + {@code /{id}} ——
 *       **头程分摊单与分摊明细**（10.6.1.1）：分摊规则版本、结账时点、
 *       目的国、逐 SKU 的分摊金额。</li>
 *   <li>{@code GET /channel/logistics-timeliness/overview} + {@code /reports/matrix} ——
 *       **时效达成率**（10.6.2.1）：渠道 × 国家矩阵与全量合计。</li>
 *   <li>{@code GET /channel/logistics-tracking/exceptions} —— **异常件**（10.3.1.2）：
 *       一行一个已判定的异常件，带发现时刻。</li>
 *   <li>{@code GET /trade/logistics/waybills/list} —— **运单**（10.2.1.1）：
 *       运费金额 + 目的国 + 销售订单号 ⇒ **各国成本对比**与**单均运费的订单分母**。</li>
 *   <li>{@code GET /catalog/skus} —— **SKU → 类目**（6.1.1.1）：
 *       类目维度的物流成本占比需要它。</li>
 * </ul>
 *
 * <p>⚠️ **为什么「各国成本对比」读 trade 的运单清单，而不走 channel**：
 * 渠道维度的成本报表按**渠道**分组，装不下「目的国」这一维；只有运单级事实
 * 同时带**运费金额**与**目的国**。而运单本来就是尾程运费的**最小颗粒** ——
 * channel 自己的成本报表也是经 {@code GET /trade/logistics/waybills/list}
 * 读同一批运单（见 channel 的 {@code TradeFreightQueryPort}）⇒ **同一个上游端点，
 * 不是第二个真相**。
 *
 * <p>⚠️ **不传租户号**：转发的是调用方自己的 {@code Authorization} bearer，
 * 上游侧仍按已认证成员关系解析租户并强制 RLS。租户隔离是**两道**。
 *
 * <p>⚠️ **跨服务不直连对方库**（AGENTS.md「模块边界纪律」）：本端口只经授权 HTTP API 读，
 * 不建跨库 FK、不建跨库视图、不直连对方 schema。
 *
 * <p>⚠️ **字段名必须与上游逐字一致**：本客户端关掉了 {@code FAIL_ON_UNKNOWN_PROPERTIES}，
 * 写错字段名不会报错，只会静默变成 null —— 那会让运费恒为 0，比报错还危险。
 */
@Service
public class ReportingLogisticsAnalysisPort {

  /** 上游分页上限（channel / trade / catalog 都把 size 钳在 200 以内）。 */
  static final int PAGE_SIZE = 200;

  /** 最多翻多少页（{@code 200 × 25 = 5000} 行）。 */
  static final int MAX_PAGES = 25;

  private final ReportingUpstreamClient channel;
  private final ReportingUpstreamClient trade;
  private final ReportingUpstreamClient catalog;

  public ReportingLogisticsAnalysisPort(
      @Value("${CHANNEL_URL:http://127.0.0.1:8084}") String channelUrl,
      @Value("${TRADE_URL:http://127.0.0.1:8085}") String tradeUrl,
      @Value("${CATALOG_URL:http://127.0.0.1:8082}") String catalogUrl) {
    this.channel = ReportingUpstreamClient.to("channel 服务", channelUrl);
    this.trade = ReportingUpstreamClient.to("trade 服务", tradeUrl);
    this.catalog = ReportingUpstreamClient.to("catalog 服务", catalogUrl);
  }

  // ==================================================================
  // channel 10.6.1.1：成本分析（全口径成本 + 渠道对比 + 头程分摊）
  // ==================================================================

  /** 上游逐币种的单位成本指标。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ChannelUnitMetric(
      String currency,
      @Schema(nullable = true) BigDecimal totalFreightAmount,
      long registeredWaybills,
      long notRegisteredWaybills,
      @Schema(nullable = true) BigDecimal totalWeightKg,
      @Schema(nullable = true) BigDecimal perOrderFreight,
      @Schema(nullable = true) BigDecimal perKgFreight) {}

  /** 上游逐币种的成本行。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ChannelCurrencyCostRow(
      String currency,
      @Schema(nullable = true) BigDecimal lastMileFreight,
      @Schema(nullable = true) BigDecimal firstMileAllocation,
      @Schema(nullable = true) BigDecimal dutyAndTax,
      @Schema(nullable = true) BigDecimal packaging,
      @Schema(nullable = true) BigDecimal subtotal,
      @Schema(nullable = true) BigDecimal lastMileShare,
      @Schema(nullable = true) BigDecimal firstMileShare,
      @Schema(nullable = true) BigDecimal dutyAndTaxShare,
      @Schema(nullable = true) BigDecimal packagingShare,
      List<String> includedComponents,
      List<String> missingComponents) {}

  /** 上游覆盖说明（缺哪几个分量）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ChannelCostCoverage(
      boolean complete, List<String> missingComponents, String note, List<String> notes) {}

  /** 上游成本总览（整窗口径）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ChannelCostOverview(
      OffsetDateTime computedAt,
      OffsetDateTime windowFrom,
      OffsetDateTime windowTo,
      List<ChannelCurrencyCostRow> currencyRows,
      List<ChannelUnitMetric> unitMetrics,
      ChannelCostCoverage coverage,
      long scannedWaybills,
      boolean truncated,
      List<String> notes) {}

  /**
   * 拉成本总览（**本模块的成本总额口径的唯一来源**）。
   *
   * @param from 窗口起（含）
   * @param to 窗口止（**含**；端口内部按上游的闭开区间语义转成 {@code [from, to+1)}）
   */
  public ChannelCostOverview costOverview(LocalDate from, LocalDate to, String bearer) {
    var start = from.atStartOfDay(java.time.ZoneOffset.UTC).toOffsetDateTime();
    var end = to.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toOffsetDateTime();
    return channel.get(
        uriBuilder ->
            uriBuilder
                .path("/channel/logistics-cost-analysis/overview")
                .queryParam("dateFrom", start.toString())
                .queryParam("dateTo", end.toString()),
        bearer,
        ChannelCostOverview.class);
  }

  /** 上游逐渠道逐币种的指标。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ChannelCostByCurrency(
      String currency,
      BigDecimal amount,
      long waybills,
      @Schema(nullable = true) BigDecimal totalWeightKg,
      @Schema(nullable = true) BigDecimal perOrderFreight,
      @Schema(nullable = true) BigDecimal perKgFreight) {}

  /** 上游渠道对比的一行（承运商 × 渠道）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ChannelCostChannelRow(
      String carrierCode,
      String carrierChannel,
      String carrierChannelLabel,
      long waybills,
      long freightRegistered,
      long freightNotRegistered,
      List<ChannelCostByCurrency> byCurrency) {}

  /** 上游渠道对比汇总。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ChannelCostChannelSummary(
      long carrierCount, long waybills, long freightRegistered, long freightNotRegistered) {}

  /** 上游渠道对比（整窗口径，不受分页影响）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ChannelCostChannelComparison(
      OffsetDateTime computedAt,
      OffsetDateTime windowFrom,
      OffsetDateTime windowTo,
      List<ChannelCostChannelRow> rows,
      ChannelCostChannelSummary summary,
      long scannedWaybills,
      boolean truncated,
      List<String> notes) {}

  /** 拉渠道对比（**本模块的渠道对比口径的唯一来源**）。 */
  public ChannelCostChannelComparison costByChannel(LocalDate from, LocalDate to, String bearer) {
    var start = from.atStartOfDay(java.time.ZoneOffset.UTC).toOffsetDateTime();
    var end = to.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toOffsetDateTime();
    return channel.get(
        uriBuilder ->
            uriBuilder
                .path("/channel/logistics-cost-analysis/by-channel")
                .queryParam("dateFrom", start.toString())
                .queryParam("dateTo", end.toString()),
        bearer,
        ChannelCostChannelComparison.class);
  }

  /** 上游头程分摊单的一行。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ChannelAllocationRow(
      UUID id,
      String allocationCode,
      @Schema(nullable = true) UUID ruleId,
      String ruleCode,
      int ruleVersion,
      String allocationBasis,
      String allocationBasisLabel,
      @Schema(nullable = true) UUID sourceShipmentId,
      String sourceShipmentCode,
      String sourceTransportMode,
      String sourceCarrierName,
      String destinationCountryCode,
      String currency,
      BigDecimal sourceTotalAmount,
      BigDecimal allocatedTotalAmount,
      BigDecimal basisTotal,
      int lineCount,
      String status,
      String statusLabel,
      OffsetDateTime computedAt,
      OffsetDateTime costAsOf,
      @Schema(nullable = true) OffsetDateTime lockedAt,
      @Schema(nullable = true) OffsetDateTime supersededAt,
      String note) {}

  /** 上游分摊单分页（列表字段是 {@code items}）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ChannelAllocationPage(
      List<ChannelAllocationRow> items,
      int page,
      int size,
      long totalElements,
      int totalPages,
      long computedCount,
      long lockedCount,
      long supersededCount,
      List<ChannelCurrencyTotal> allocatedByCurrency) {}

  /** 上游逐币种合计（分摊单的汇总序列）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ChannelCurrencyTotal(String currency, BigDecimal amount, long count) {}

  /** 拉一页头程分摊单。 */
  public ChannelAllocationPage allocations(int page, String bearer) {
    return channel.get(
        uriBuilder ->
            uriBuilder
                .path("/channel/logistics-cost-analysis/allocations")
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        ChannelAllocationPage.class);
  }

  /** 上游分摊明细的一行（**逐 SKU**）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ChannelAllocationLineRow(
      UUID id,
      int lineNo,
      @Schema(nullable = true) UUID skuId,
      String skuCode,
      String skuName,
      String hsCode,
      BigDecimal basisValue,
      String basisUnit,
      @Schema(nullable = true) BigDecimal ratio,
      BigDecimal allocatedAmount) {}

  /** 上游分摊单详情（头 + 明细行）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ChannelAllocationDetail(
      ChannelAllocationRow allocation, List<ChannelAllocationLineRow> lines) {}

  /** 拉一张分摊单的明细（**类目维度的唯一来源**）。 */
  public ChannelAllocationDetail allocation(UUID allocationId, String bearer) {
    return channel.get(
        uriBuilder ->
            uriBuilder.path("/channel/logistics-cost-analysis/allocations/" + allocationId),
        bearer,
        ChannelAllocationDetail.class);
  }

  // ==================================================================
  // channel 10.6.2.1：时效分析（达成率）
  // ==================================================================

  /** 上游时效矩阵的一格（渠道 × 国家）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ChannelTimelinessMatrixRow(
      @Schema(nullable = true) UUID channelId,
      String channelCode,
      String channelName,
      String countryCode,
      int shippedCount,
      int deliveredCount,
      int comparableCount,
      int onTimeCount,
      int overdueCount,
      int noPromiseCount,
      int pendingCount,
      int returnedCount,
      @Schema(nullable = true) BigDecimal deliveryRate,
      @Schema(nullable = true) BigDecimal onTimeRate,
      @Schema(nullable = true) BigDecimal overdueRate,
      @Schema(nullable = true) BigDecimal avgTransitHours,
      @Schema(nullable = true) BigDecimal avgDeviationHours,
      @Schema(nullable = true) BigDecimal stddevTransitHours,
      @Schema(nullable = true) BigDecimal p90TransitHours,
      @Schema(nullable = true) BigDecimal maxOverdueHours,
      @Schema(nullable = true) BigDecimal score,
      @Schema(nullable = true) String scoreGrade,
      boolean sampleSufficient,
      int minSampleThreshold) {}

  /** 上游时效矩阵分页（合计在 {@code totals} 里，**不受分页影响**）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ChannelTimelinessMatrixPage(
      List<ChannelTimelinessMatrixRow> rows,
      int page,
      int size,
      long totalElements,
      int totalPages,
      ChannelTimelinessTotals totals,
      String note) {}

  /** 上游时效全量合计。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ChannelTimelinessTotals(
      int shippedCount,
      int deliveredCount,
      int comparableCount,
      int onTimeCount,
      int overdueCount,
      int noPromiseCount,
      int pendingCount,
      int returnedCount,
      @Schema(nullable = true) BigDecimal deliveryRate,
      @Schema(nullable = true) BigDecimal onTimeRate,
      @Schema(nullable = true) BigDecimal overdueRate,
      @Schema(nullable = true) BigDecimal avgTransitHours,
      @Schema(nullable = true) BigDecimal avgDeviationHours,
      @Schema(nullable = true) BigDecimal stddevTransitHours,
      @Schema(nullable = true) BigDecimal p90TransitHours,
      @Schema(nullable = true) BigDecimal maxOverdueHours) {}

  /** 拉一页时效矩阵。 */
  public ChannelTimelinessMatrixPage timelinessMatrix(
      LocalDate from, LocalDate to, int page, String bearer) {
    return channel.get(
        uriBuilder ->
            uriBuilder
                .path("/channel/logistics-timeliness/reports/matrix")
                .queryParam("from", from.toString())
                .queryParam("to", to.toString())
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        ChannelTimelinessMatrixPage.class);
  }

  // ==================================================================
  // channel 10.3.1.2：异常件
  // ==================================================================

  /** 上游异常件的一行。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ChannelExceptionCaseRow(
      UUID id,
      String caseNo,
      String source,
      String sourceLabel,
      String exceptionType,
      String exceptionTypeLabel,
      String severity,
      String severityLabel,
      String status,
      String statusLabel,
      @Schema(nullable = true) UUID subjectId,
      @Schema(nullable = true) UUID sourceEventId,
      String waybillNo,
      String carrierCode,
      String salesOrderNo,
      long stalledDurationMinutes,
      BigDecimal stalledDurationHours,
      @Schema(nullable = true) UUID ownerMembershipId,
      OffsetDateTime detectedAt,
      OffsetDateTime dueAt,
      boolean overdue,
      @Schema(nullable = true) OffsetDateTime closedAt,
      String closeReason,
      long dispositionCount,
      long claimCount,
      @Schema(nullable = true) String note) {}

  /** 上游异常件分页（列表字段是 {@code rows}）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ChannelExceptionCasePage(
      List<ChannelExceptionCaseRow> rows,
      int page,
      int size,
      long total,
      OffsetDateTime evaluatedAt,
      String note,
      String responsibilityNote) {}

  /** 拉一页异常件。 */
  public ChannelExceptionCasePage exceptions(int page, String bearer) {
    return channel.get(
        uriBuilder ->
            uriBuilder
                .path("/channel/logistics-tracking/exceptions")
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        ChannelExceptionCasePage.class);
  }

  // ==================================================================
  // trade 10.2.1.1：运单清单（各国成本对比 + 单均运费的订单分母）
  // ==================================================================

  /**
   * 上游一行运单。
   *
   * <p>⚠️ {@code freightAmount} 可空 = **运费没登记**（{@code freightGapCode=NOT_REGISTERED}），
   * 这与「登记为 0」是两件事 ⇒ 本模块原样搬运这个区分。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TradeWaybillRow(
      UUID id,
      String requestNo,
      String waybillNo,
      String salesOrderNo,
      String outboundOrderNo,
      String carrierCode,
      String carrierChannel,
      String carrierChannelLabel,
      String destinationCountry,
      @Schema(nullable = true) BigDecimal grossWeightKg,
      @Schema(nullable = true) BigDecimal freightAmount,
      @Schema(nullable = true) String freightCurrency,
      @Schema(nullable = true) String freightGapCode,
      @Schema(nullable = true) String freightSource,
      OffsetDateTime createdAt) {}

  /** 上游运单分页（列表字段是 {@code items}）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TradeWaybillPage(
      List<TradeWaybillRow> items,
      int page,
      int size,
      long totalElements,
      int totalPages) {}

  /** 拉一页运单（**已按窗口过滤**，上游支持 dateFrom / dateTo）。 */
  public TradeWaybillPage waybills(LocalDate from, LocalDate to, int page, String bearer) {
    var start = from.atStartOfDay(java.time.ZoneOffset.UTC).toOffsetDateTime();
    var end = to.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toOffsetDateTime();
    return trade.get(
        uriBuilder ->
            uriBuilder
                .path("/trade/logistics/waybills/list")
                .queryParam("dateFrom", start.toString())
                .queryParam("dateTo", end.toString())
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        TradeWaybillPage.class);
  }

  // ==================================================================
  // catalog 6.1.1.1：SKU → 类目映射
  // ==================================================================

  /** 上游一行 SKU。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CatalogSkuRow(
      UUID id,
      @Schema(nullable = true) UUID spuId,
      @Schema(nullable = true) String category,
      String code,
      String name,
      @Schema(nullable = true) String status) {}

  /** 上游 SKU 分页（列表字段是 {@code items}）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CatalogSkuPage(List<CatalogSkuRow> items, long total, int page, int size) {}

  /** 拉一页 SKU（**只取 id / code / name / category**，刻意读得少）。 */
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
}
