package com.starcon.reporting;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 读取 **9 订单 / 11 售后（trade）**事实的**跨服务只读端口**，专供 13.4.1.2 SKU 利润的
 * **退款损失**这一项。
 *
 * <p>蓝图 13.4.1.2 的职责句点名「退款损失」，而这笔钱在 13.4.1.1 的
 * {@code reporting_profit_fact} 里**压根不存在**（13.4.1.1 的四路上游不投退款）⇒
 * 本模块是这笔钱在本仓的**唯一写入所有者**。
 *
 * <ul>
 *   <li>{@code GET /trade/refund-applications} —— **退款申请单**（{@code refund_application}）。
 *       只有 {@code application_status = 'SUCCEEDED'} 的才**算损失**（钱真的退出去了）；
 *       申请中 / 执行中的都**不算**（申请了不等于退了）。</li>
 *   <li>{@code GET /trade/aftersales/cases/{id}} —— **售后单明细**，用于把一笔**订单级**退款
 *       落到**具体 SKU**：{@code aftersales_case_line} 带着 {@code sku_code} 与行级申请金额。</li>
 * </ul>
 *
 * <p>⚠️ **不传租户号**：转发的是调用方自己的 {@code Authorization} bearer，trade 侧仍按
 * 已认证成员关系解析租户并强制 RLS。
 *
 * <p>⚠️ **不重算分摊**：本端口只**读**上游写好的金额与行级拆分。退款金额与售后单行金额
 * 对不上时（部分退款 / 上游改了金额），本模块**不摊给任何 SKU** —— 摊下去就是编造。
 */
@Service
public class ReportingSkuProfitPort {

  /** 上游分页上限。 */
  static final int PAGE_SIZE = 200;

  /** 最多翻多少页。 */
  static final int MAX_PAGES = 25;

  private final ReportingUpstreamClient client;

  public ReportingSkuProfitPort(@Value("${TRADE_URL:http://127.0.0.1:8085}") String url) {
    this.client = ReportingUpstreamClient.to("trade 服务", url);
  }

  // ================================================================ 退款申请单

  /**
   * 一张退款申请单（**只声明本模块真正用到的字段**）。
   *
   * <p>⚠️ **字段名必须与上游逐字一致**（trade {@code RefundApplicationModels.RefundHead}）：
   * 本客户端关掉了 {@code FAIL_ON_UNKNOWN_PROPERTIES}，写错字段名不会报错，只会静默变成
   * null —— 那会让退款金额变成 null 而不是报错，比不写还危险。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RefundApplicationHead(
      UUID id,
      String applicationNo,
      UUID caseId,
      String caseNo,
      UUID orderId,
      String orderNo,
      String applicationStatus,
      BigDecimal refundAmount,
      String currency,
      String refundReason,
      @Schema(nullable = true) OffsetDateTime outcomeRecordedAt,
      @Schema(nullable = true) OffsetDateTime createdAt,
      @Schema(nullable = true) OffsetDateTime updatedAt) {}

  /** 退款申请单列表行（上游 {@code RefundRow}）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RefundApplicationRow(RefundApplicationHead head) {}

  /** 退款申请单分页（上游 {@code RefundPage}）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RefundApplicationPage(List<RefundApplicationRow> rows, int page, int size, long total) {}

  /**
   * 拉一页退款申请单。
   *
   * <p>⚠️ **按状态过滤交给调用方做**：上游的 {@code status} 参数是白名单过滤，本模块要在
   * 读回来之后自己判定「哪些状态算损失」（只有 SUCCEEDED），不在 URL 上做筛选 ——
   * 免得上游词表变了以后这里静默少拉一批。
   */
  public RefundApplicationPage refundApplications(int page, String bearer) {
    return client.get(
        uriBuilder ->
            uriBuilder
                .path("/trade/refund-applications")
                .queryParam("page", Math.max(page, 0))
                .queryParam("size", PAGE_SIZE),
        bearer,
        RefundApplicationPage.class);
  }

  // ================================================================ 售后单明细

  /** 售后单头（上游 {@code AftersalesCaseHeader} 的子集）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AftersalesCaseHeader(
      UUID id,
      String caseNo,
      String caseType,
      String status,
      BigDecimal requestedAmount,
      String currency,
      @Schema(nullable = true) OffsetDateTime receivedAt) {}

  /** 售后单的**涉及行**（上游 {@code AftersalesCaseLine}：带着 SKU 与行级申请金额）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AftersalesCaseLine(
      UUID id,
      UUID orderLineId,
      int lineNo,
      String skuCode,
      BigDecimal quantity,
      @Schema(nullable = true) BigDecimal requestedAmount,
      @Schema(nullable = true) String currency) {}

  /** 售后单明细（上游 {@code AftersalesCaseDetail} 的子集）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AftersalesCaseDetail(AftersalesCaseHeader header, List<AftersalesCaseLine> lines) {}

  /** 拉一张售后单的明细（**SKU 归属的唯一来源**）。 */
  public AftersalesCaseDetail aftersalesCase(UUID caseId, String bearer) {
    return client.get(
        uriBuilder -> uriBuilder.path("/trade/aftersales/cases/" + caseId),
        bearer,
        AftersalesCaseDetail.class);
  }
}
