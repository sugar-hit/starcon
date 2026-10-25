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
 * 读取 **12 财务（finance）**事实的**跨服务只读端口**（finance 服务，端口 8087），
 * 专供 13.4.2.2 费用差异的**实际侧**。
 *
 * <p>蓝图 13.4.2.2 的输入句点名「实际费用（<b>12.3.1.2</b>）」⇒
 * 本端口只读 12.3.1.2 的**结算交易明细**（{@code GET /finance/transactions}），
 * 按结算类别挑出三类费用：
 *
 * <ul>
 *   <li>{@code COMMISSION} —— 佣金 ⇒ <b>平台费</b>；
 *   <li>{@code FBA_FEE} —— FBA 费用 ⇒ <b>物流费</b>；
 *   <li>{@code ADVERTISING} —— 广告费 ⇒ <b>广告费</b>。
 * </ul>
 *
 * <p>⚠️ **不传租户号**：转发的是调用方自己的 {@code Authorization} bearer，finance 侧仍按
 * 已认证成员关系解析租户并强制 RLS。
 *
 * <p>⚠️ **结算明细不带销售额 / 数量基数**（只有费用金额与件数）⇒ 那一侧的
 * 「按规则重算预计值」**算不出来**。本模块如实报成「有实际、无预计」，
 * **不用 0 冒充预计值**。
 *
 * <p>⚠️ 与既有的 {@link ReportingFinancePort} 是**两个不同模块的两个读端口**
 * （那边是 13.1.1.1 用的应收 / 成本分录 / 利润表），本端口只服务 13.4.2.2 的结算明细。
 */
@Service
public class ReportingSettlementPort {

  /** 上游分页上限。 */
  static final int PAGE_SIZE = 200;

  /** 最多翻多少页。 */
  static final int MAX_PAGES = 25;

  private final ReportingUpstreamClient client;

  public ReportingSettlementPort(@Value("${FINANCE_URL:http://127.0.0.1:8087}") String url) {
    this.client = ReportingUpstreamClient.to("finance 服务", url);
  }

  /**
   * 结算明细的一行。
   *
   * <p>⚠️ **字段名必须与上游逐字一致**（finance {@code TransactionModels.FinanceTransactionRow}）：
   * 本客户端关掉了 {@code FAIL_ON_UNKNOWN_PROPERTIES}，写错字段名不会报错，
   * 只会静默变成 null —— 那会让金额变成 null 而不是报错，比不写还危险。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SettlementTransactionRow(
      UUID id,
      UUID settlementId,
      String settlementNo,
      String shopCode,
      String shopName,
      String platformCode,
      LocalDate periodStart,
      LocalDate periodEnd,
      int lineNo,
      String transactionType,
      String transactionTypeLabel,
      String category,
      String categoryLabel,
      BigDecimal amount,
      BigDecimal rawAmount,
      String currency,
      BigDecimal quantity,
      LocalDate occurredOn,
      String externalReference,
      String orderReference,
      String orderLineReference,
      String idempotencyKey,
      String source,
      String sourceLabel,
      String description) {}

  /** 结算明细分页结果。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SettlementTransactionPage(
      List<SettlementTransactionRow> items, int page, int size, long total) {}

  /**
   * 拉一页结算明细（按结算类别 + 发生日区间过滤）。
   *
   * @param category 结算类别（{@code COMMISSION} / {@code FBA_FEE} / {@code ADVERTISING}）
   */
  public SettlementTransactionPage transactions(
      String category, LocalDate from, LocalDate to, int page, String bearer) {
    return client.get(
        uriBuilder -> {
          var b = uriBuilder.path("/finance/transactions");
          b = param(b, "category", category);
          b = param(b, "occurredFrom", from);
          b = param(b, "occurredTo", to);
          return b.queryParam("page", Math.max(page, 0)).queryParam("size", PAGE_SIZE);
        },
        bearer,
        SettlementTransactionPage.class);
  }

  /**
   * 条件查询参数（空值不带进 URL：{@code ?name} 这种没有 {@code =} 的写法会让上游绑定失败）。
   */
  private static UriBuilder param(UriBuilder builder, String name, Object value) {
    if (value == null) return builder;
    if (value instanceof String s && s.isBlank()) return builder;
    return builder.queryParam(name, value);
  }
}
