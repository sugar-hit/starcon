package com.starcon.platform;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 12 财务（finance 服务）的**跨服务只读端口**：18.4.1.1「对话助手」的「资金与现金流」意图走这里。
 *
 * <p>⚠️ <b>只转发调用方自己的 bearer，不传租户号</b>：上游仍按已认证成员关系解析租户并强制 RLS。
 *
 * <p>⚠️ <b>实际流出没有时间序列</b>（上游响应里的 {@code actualOutflowBasis} 会说明原因）——
 * 所以本模块的「净流入」只能是「实际流入 − 计划流出」这一口径的净值，
 * **不是完整现金流**。口径说明里必须写清这一点。
 */
@Service
public class AssistantFinancePort {

  private final PlatformUpstreamClient client;

  public AssistantFinancePort(@Value("${FINANCE_URL:http://127.0.0.1:8087}") String url) {
    this.client = PlatformUpstreamClient.to("finance 服务", url);
  }

  /** 一个时间桶 × 一个币种。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AssistantFundFlowBucket(
      LocalDate periodStart,
      String currency,
      BigDecimal actualInflowAmount,
      BigDecimal expectedInflowAmount,
      BigDecimal plannedOutflowAmount,
      BigDecimal netExpectedAmount,
      long actualInflowCount,
      long expectedInflowCount,
      long plannedOutflowCount) {}

  /** 一个币种在整段区间上的合计。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AssistantFundFlowTotal(
      String currency,
      BigDecimal actualInflowAmount,
      BigDecimal expectedInflowAmount,
      BigDecimal plannedOutflowAmount,
      BigDecimal netExpectedAmount) {}

  /** 现金流入流出趋势响应。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AssistantFundFlowTrend(
      LocalDate fromDate,
      LocalDate toDate,
      String granularity,
      LocalDate asOfDate,
      String actualOutflowBasis,
      String actualOutflowOwnerModule,
      List<AssistantFundFlowBucket> buckets,
      List<AssistantFundFlowTotal> totals) {}

  /** 拉现金流入流出趋势（**真实只读查询**）。 */
  public AssistantFundFlowTrend cashFlowTrend(LocalDate from, LocalDate to, String bearer) {
    return client.get(
        uriBuilder -> {
          uriBuilder
              .path("/finance/fund-analysis/cash-flow-trend")
              .queryParam("from", from.toString())
              .queryParam("to", to.toString())
              .queryParam("granularity", "MONTH");
          return uriBuilder;
        },
        bearer,
        AssistantFundFlowTrend.class);
  }
}
