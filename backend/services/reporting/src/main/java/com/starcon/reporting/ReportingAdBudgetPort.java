package com.starcon.reporting;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriBuilder;

/**
 * 读取 **14 广告（channel）**预算事实的**跨服务只读端口**（channel 服务，端口 8083）。
 *
 * <p>13.4.2.2 的四类费用里，**广告费是唯一没有「费用规则」的一类**：
 * 5.2.3 交付的是平台费与物流头程费的规则，广告那边交付的是**预算分配方案**。
 * 所以广告费的「预计费用」取自**分配方案的分配额**（渠道侧本来就以预算为计划口径），
 * 实际费用取 12.3.1.2 的结算广告费明细 —— 两边同口径（平台 + 店铺）才可比。
 *
 * <p>⚠️ **不传租户号**：转发的是调用方自己的 {@code Authorization} bearer，channel 侧仍按
 * 已认证成员关系解析租户并强制 RLS。
 *
 * <p>⚠️ **SUPERSEDED 的方案不参与**：被取代的方案与取代它的新方案窗口重叠，
 * 两份都算就会把预计费用算两遍。
 */
@Service
public class ReportingAdBudgetPort {

  /** 上游分页上限。 */
  static final int PAGE_SIZE = 200;

  /** 最多翻多少页。 */
  static final int MAX_PAGES = 25;

  /** 方案状态词表（上游 {@code AdBudgetAllocationService.PLAN_STATUSES}）。 */
  static final List<String> PLAN_STATUSES = List.of("COMPUTED", "APPROVED", "APPLIED", "SUPERSEDED");

  private final ReportingUpstreamClient client;

  public ReportingAdBudgetPort(@Value("${CHANNEL_URL:http://127.0.0.1:8083}") String url) {
    this.client = ReportingUpstreamClient.to("channel 服务", url);
  }

  /** 分配方案的一行（列表形状）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AdBudgetPlanRow(
      UUID id,
      UUID poolId,
      String poolName,
      UUID ruleId,
      String ruleName,
      String status,
      String currency,
      BigDecimal totalAmount,
      BigDecimal allocatedAmount,
      BigDecimal unallocatedAmount,
      BigDecimal releasedAmount,
      BigDecimal idleBudget,
      BigDecimal constrainedShortfall,
      int campaignCount,
      int skippedCount,
      LocalDate windowStart,
      LocalDate windowEnd,
      OffsetDateTime computedAt,
      String note) {}

  /** 分配方案分页结果。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AdBudgetPlanPage(List<AdBudgetPlanRow> items, long total, int page, int size) {}

  /** 分配方案的一行明细（**一个广告活动一行**，带平台码与店铺码）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AdBudgetPlanLineRow(
      UUID id,
      UUID campaignId,
      String campaignName,
      String platformCode,
      String shopCode,
      String campaignStatus,
      String currency,
      BigDecimal currentBudget,
      BigDecimal historicalSpend,
      BigDecimal historicalSales,
      BigDecimal historicalOrders,
      BigDecimal roas,
      BigDecimal acos,
      BigDecimal dailyAvgSpend,
      BigDecimal utilization,
      BigDecimal salesTarget,
      BigDecimal score,
      BigDecimal weight,
      BigDecimal weightShare,
      BigDecimal allocatedBudget,
      BigDecimal budgetDelta,
      boolean budgetConstrained,
      boolean idleBudget,
      String recommendation,
      String reason,
      int position) {}

  /** 分配方案明细（方案 + 逐活动行 + 未纳入分配的活动与原因）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AdBudgetPlanDetail(
      AdBudgetPlanRow plan,
      List<AdBudgetPlanLineRow> lines,
      List<Object> skipped,
      List<Object> rebalances,
      String note) {}

  /** 拉一页分配方案。 */
  public AdBudgetPlanPage plans(int page, String bearer) {
    return client.get(
        uriBuilder ->
            uriBuilder
                .path("/channel/ad-budgets/plans")
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        AdBudgetPlanPage.class);
  }

  /** 取一份分配方案的明细（逐活动一行）。 */
  public AdBudgetPlanDetail plan(UUID planId, String bearer) {
    return client.get(
        uriBuilder -> uriBuilder.path("/channel/ad-budgets/plans/" + planId),
        bearer,
        AdBudgetPlanDetail.class);
  }

  /**
   * 条件查询参数（空值不带进 URL）。
   */
  private static UriBuilder param(UriBuilder builder, String name, Object value) {
    if (value == null) return builder;
    if (value instanceof String s && s.isBlank()) return builder;
    return builder.queryParam(name, value);
  }
}
