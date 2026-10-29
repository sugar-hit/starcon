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
 * 读取 **12 财务（finance）**事实的**跨服务只读端口**（finance 服务，端口 8087）。
 *
 * <ul>
 *   <li>{@code GET /finance/receivables} —— 应收单（**应收余额**）；
 *   <li>{@code GET /finance/cost-calculation/entries} —— 成本分录（**成本 / 毛利额**）。
 * </ul>
 *
 * <p>⚠️ **不传租户号**：转发的是调用方自己的 {@code Authorization} bearer，finance 侧仍按
 * 已认证成员关系解析租户并强制 RLS。
 */
@Service
public class ReportingFinancePort {

  /** 上游分页上限。 */
  static final int PAGE_SIZE = 200;

  /** 最多翻多少页。 */
  static final int MAX_PAGES = 25;

  private final ReportingUpstreamClient client;

  public ReportingFinancePort(@Value("${FINANCE_URL:http://127.0.0.1:8087}") String url) {
    this.client = ReportingUpstreamClient.to("finance 服务", url);
  }

  // ---------------------------------------------------------------- 应收单

  /** 上游一行应收单。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ReceivableRow(
      UUID id,
      String receivableNo,
      @JsonIgnoreProperties(ignoreUnknown = true) UUID shopId,
      String shopName,
      String currency,
      BigDecimal amount,
      BigDecimal settledAmount,
      String status,
      LocalDate dueDate,
      OffsetDateTime confirmedAt) {}

  /** 上游一页应收单。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ReceivablePage(List<ReceivableRow> items, int page, int size, long total) {}

  /** 拉一页应收单（按到期日过滤）。 */
  public ReceivablePage receivables(LocalDate from, LocalDate to, int page, String bearer) {
    return client.get(
        uriBuilder ->
            uriBuilder
                .path("/finance/receivables")
                .queryParam("dueFrom", from)
                .queryParam("dueTo", to)
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        ReceivablePage.class);
  }

  // ---------------------------------------------------------------- 收款单（回款）

  /**
   * 上游按币种的收款单汇总（**上游自己算好的合计**）。
   *
   * <p>⚠️ 本记录只声明本服务真正用到的字段：{@code amount}（到账金额合计）、
   * {@code count}（笔数）、{@code advanceAmount}（预收金额）。其余字段不声明 ——
   * 少声明不会读错，写错字段名会静默变 null（那个更危险）。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ReceiptCurrencySummary(
      String currency,
      BigDecimal amount,
      BigDecimal settledAmount,
      BigDecimal unsettledAmount,
      long count,
      long advanceCount,
      BigDecimal advanceAmount) {}

  /**
   * 上游收款单列表页。
   *
   * <p>⚠️ **只取 {@code summaryByCurrency}**：上游的汇总是**整个筛选结果集**的合计
   * （不是当前页），所以回款这一格直接用上游的合计 —— 本服务**不把收款单拉回来自己加**
   * （那就是在跨服务重算口径）。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ReceiptPage(List<ReceiptCurrencySummary> summaryByCurrency) {}

  /**
   * 拉一页收款单的按币种汇总（回款口径：12.2.1.1 收款单）。
   *
   * @param from 到账起日（含）
   * @param to 到账止日（含）
   * @param status 单据状态。回款只认 {@code RECORDED}（作废单不是到账）
   */
  public ReceiptPage receipts(LocalDate from, LocalDate to, String status, int page, String bearer) {
    return client.get(
        uriBuilder -> {
          var builder = uriBuilder.path("/finance/receipts");
          builder = builder.queryParam("receivedFrom", from);
          builder = builder.queryParam("receivedTo", to);
          if (status != null && !status.isBlank()) builder = builder.queryParam("status", status);
          return builder.queryParam("page", Math.max(page, 0)).queryParam("size", PAGE_SIZE);
        },
        bearer,
        ReceiptPage.class);
  }

  // ---------------------------------------------------------------- 成本分录

  /** 上游一行成本分录。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CostEntryRow(
      UUID id,
      OffsetDateTime occurredAt,
      String businessType,
      UUID skuId,
      String skuCode,
      BigDecimal issueQuantity,
      BigDecimal unitCost,
      BigDecimal costAmount,
      String currency,
      String status) {}

  /** 上游一页成本分录。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CostEntryPage(List<CostEntryRow> items, int page, int size, long total) {}

  /**
   * 拉一页成本分录。
   *
   * @param businessType 业务类型过滤（如销售出库）。空 ⇒ 不限（**服务层必须自己判口径**）。
   */
  public CostEntryPage costEntries(String businessType, int page, String bearer) {
    return client.get(
        uriBuilder ->
            uriBuilder
                .path("/finance/cost-calculation/entries")
                .queryParam("businessType", businessType)
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        CostEntryPage.class);
  }

  // ---------------------------------------------------------------- 会计期间

  /** 上游一行会计期间。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PeriodRow(
      UUID id,
      String periodCode,
      LocalDate periodStart,
      LocalDate periodEnd,
      String status,
      String baseCurrency) {}

  /** 上游一页会计期间。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PeriodPage(List<PeriodRow> items, int page, int size, long total) {}

  /**
   * 拉一页会计期间。
   *
   * <p>⚠️ 上游**没有按日期过滤的参数**（只有 status / periodType / calendarId /
   * fiscalYear / keyword），所以「取覆盖某段日期的期间」要**拉回来自己筛**。
   */
  public PeriodPage periods(int page, String bearer) {
    return client.get(
        uriBuilder ->
            uriBuilder
                .path("/finance/accounting-periods")
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        PeriodPage.class);
  }

  // ---------------------------------------------------------------- 利润表

  /** 利润表里的一行。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record IncomeStatementRow(
      String rowKey,
      String section,
      String lineCode,
      String lineName,
      String currency,
      BigDecimal periodNet,
      BigDecimal closingBalance) {}

  /** 利润表里按币种分的一组。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record IncomeStatementCurrencyGroup(
      String currency, List<IncomeStatementRow> rows) {}

  /** 利润表结果（**只声明本服务真正用到的字段**）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record IncomeStatementResult(
      String reportType,
      String periodFromCode,
      String periodToCode,
      String periodLabel,
      List<IncomeStatementCurrencyGroup> currencyGroups,
      String notice,
      long voucherCount,
      long lineCount) {}

  /**
   * 取一段会计期间区间的利润表。
   *
   * <p>⚠️ **净利口径归 12.5.2.2 财务报表（finance）所有**（卷 13 卷首「口径唯一」）：
   * 本服务**只读 finance 算好的「本期损益」行**，不自己把收入行加成本行 ——
   * 后者会在 finance 改了科目归类时变成**第二个真相**。
   *
   * <p>finance 的利润表按**会计期间**组织，所以本端口的参数是期间 id 而不是日期区间。
   */
  public IncomeStatementResult incomeStatement(
      UUID periodFromId, UUID periodToId, String bearer) {
    return client.get(
        uriBuilder ->
            uriBuilder
                .path("/finance/financial-reports/income-statement")
                .queryParam("periodFromId", periodFromId)
                .queryParam("periodToId", periodToId),
        bearer,
        IncomeStatementResult.class);
  }
}
