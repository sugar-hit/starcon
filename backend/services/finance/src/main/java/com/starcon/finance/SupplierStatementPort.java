package com.starcon.finance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 读取上游 **6.5.1.1 采购对账**事实的**跨服务只读端口**（trade 服务，端口 8085）。
 *
 * <p>蓝图 12-finance.md 把 6.5.1 列为 12.1.2.3 的上游：「关系：上游 = 6.5.1 采购发起」。
 *
 * <p>⚠️ 上游在 **trade 服务**（卷 06 采购与供应），**不在 finance**。
 * AGENTS.md 模块边界纪律写死：跨服务必须走授权 API / event，
 * **禁止跨服务 SQL / 跨库 FK / 共享视图**。
 * ⇒ 本端口**只经 trade 的授权 HTTP API**读，**不直连对方库、不建跨库视图、不建跨库外键**。
 *
 * <p><b>🔴 租户不由本端口传递</b>：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * trade 侧仍按已认证成员关系解析租户并强制 RLS。租户隔离是<b>两道</b>
 * （finance 侧 RLS + trade 侧 RLS），而不是「本服务替对方决定租户」。
 *
 * <p><b>取不到就显式失败</b>：上游不可达 / 无权 / 不存在，一律 503 或 404，
 * **绝不返回空集合冒充「上游没有对账单」** —— 那会让财务侧以为「这个供应商没对过账」。
 *
 * <p><b>本端口只声明本模块真正用到的字段</b>：上游加字段不该把本模块撑爆，
 * 类型不匹配照样炸（那种是真读错了）。
 */
@Service
public class SupplierStatementPort {

  /** 上游只读形状：{@code GET /trade/purchase-reconciliations}（6.5.1.1 采购对账）。 */
  static final String STATEMENTS_PATH = "/trade/purchase-reconciliations";

  /** 上游分页上限。 */
  static final int PAGE_SIZE = 200;

  /** 最多翻多少页（{@code 200 × 25 = 5000} 张）。必须有上限：翻页是无界的。 */
  static final int MAX_PAGES = 25;

  /** 上游对账单状态：供应商已确认（可导入财务侧）。 */
  static final String UPSTREAM_CONFIRMED = "CONFIRMED";

  /** 上游对账单状态：已关闭（可导入财务侧）。 */
  static final String UPSTREAM_CLOSED = "CLOSED";

  /** 上游一行对账单头（本模块真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record StatementRow(
      UUID id,
      String reconNo,
      UUID supplierRefId,
      String supplierCode,
      String supplierName,
      String periodType,
      LocalDate periodStart,
      LocalDate periodEnd,
      String statementCurrency,
      String status,
      BigDecimal receivedAmount,
      BigDecimal deductionAmount,
      BigDecimal claimAmount,
      BigDecimal suggestedPayableAmount,
      BigDecimal totalQuantity,
      long lineCount,
      long discrepancyCount,
      long openDiscrepancyCount,
      @JsonIgnoreProperties(ignoreUnknown = true) OffsetDateTime supplierConfirmedAt,
      @JsonIgnoreProperties(ignoreUnknown = true) String supplierReference) {}

  /** 上游对账单明细行。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record StatementLine(
      UUID id,
      int lineNo,
      String lineType,
      String sourceType,
      @JsonIgnoreProperties(ignoreUnknown = true) UUID sourceRefId,
      @JsonIgnoreProperties(ignoreUnknown = true) String sourceRefCode,
      @JsonIgnoreProperties(ignoreUnknown = true) UUID sourceLineRefId,
      @JsonIgnoreProperties(ignoreUnknown = true) String sourceLineRefCode,
      OffsetDateTime occurredAt,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal quantity,
      @JsonIgnoreProperties(ignoreUnknown = true) String unitCode,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal unitConversion,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal baseQuantity,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal unitPrice,
      String currency,
      BigDecimal fxRate,
      @JsonIgnoreProperties(ignoreUnknown = true) String fxRateSource,
      @JsonIgnoreProperties(ignoreUnknown = true) OffsetDateTime fxRateAt,
      BigDecimal amount,
      BigDecimal statementAmount,
      @JsonIgnoreProperties(ignoreUnknown = true) String deductionType,
      @JsonIgnoreProperties(ignoreUnknown = true) UUID claimId,
      @JsonIgnoreProperties(ignoreUnknown = true) String qualityDeductionStatus,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal qualityDeductionQuantity,
      @JsonIgnoreProperties(ignoreUnknown = true) String qualityDeductionNote,
      @JsonIgnoreProperties(ignoreUnknown = true) String note) {}

  /** 上游（采购侧）自己记的差异。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record StatementDiscrepancy(
      UUID id,
      @JsonIgnoreProperties(ignoreUnknown = true) UUID lineId,
      @JsonIgnoreProperties(ignoreUnknown = true) Integer lineNo,
      String discrepancyType,
      @JsonIgnoreProperties(ignoreUnknown = true) String fieldName,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal ourValue,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal supplierValue,
      BigDecimal difference,
      @JsonIgnoreProperties(ignoreUnknown = true) String currency,
      String status,
      @JsonIgnoreProperties(ignoreUnknown = true) String resolution,
      @JsonIgnoreProperties(ignoreUnknown = true) String resolutionNote,
      @JsonIgnoreProperties(ignoreUnknown = true) String note) {}

  /** 上游一页对账单。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record StatementPage(List<StatementRow> rows, int page, int size, long total) {}

  /** 上游对账单详情（头 + 明细 + 差异）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record StatementDetail(
      @JsonIgnoreProperties(ignoreUnknown = true) StatementRow head,
      @JsonIgnoreProperties(ignoreUnknown = true) List<StatementLine> lines,
      @JsonIgnoreProperties(ignoreUnknown = true) List<StatementDiscrepancy> discrepancies) {}

  /** 上游 JSON 的 mapper：关掉 {@code FAIL_ON_UNKNOWN_PROPERTIES}，类型不匹配照样炸。 */
  private static final ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private final RestClient client;

  public SupplierStatementPort(@Value("${TRADE_URL:http://127.0.0.1:8085}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(15));
    this.client =
        RestClient.builder()
            .baseUrl(url)
            .requestFactory(factory)
            .messageConverters(java.util.List.of(new MappingJackson2HttpMessageConverter(JSON)))
            .build();
  }

  /**
   * 拉一页上游对账单。
   *
   * @param bearer 调用方自己的会话（租户由 trade 侧按已认证成员关系解析）
   */
  public StatementPage statements(int page, String bearer) {
    try {
      StatementPage result =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(STATEMENTS_PATH)
                          .queryParam("page", Math.max(page, 0))
                          .queryParam("size", PAGE_SIZE)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(StatementPage.class);
      if (result == null || result.rows() == null) {
        throw unavailable("trade 服务没有返回采购对账列表");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED,
            "调用方无权读取上游采购对账事实（需要 trade 侧的 trade.purchase-reconciliation.read 权限）",
            e);
      }
      throw unavailable("trade 服务返回 " + e.getStatusCode().value() + "：采购对账事实读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("trade 服务不可达");
    }
  }

  /**
   * 读一张上游对账单的快照（头 + 明细 + 采购侧自己的差异）。
   *
   * @throws ResponseStatusException 上游不可达（503）/ 上游没有这张对账单（404）/ 调用方无权（401）
   */
  public StatementDetail statement(UUID id, String bearer) {
    try {
      StatementDetail detail =
          client
              .get()
              .uri(STATEMENTS_PATH + "/" + id)
              .header("Authorization", bearer)
              .retrieve()
              .body(StatementDetail.class);
      if (detail == null || detail.head() == null) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND, "上游 6.5.1.1 采购对账 " + id + " 不存在或无权读取");
      }
      return detail;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 404) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND, "上游 6.5.1.1 采购对账 " + id + " 不存在", e);
      }
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED,
            "调用方无权读取上游采购对账事实（需要 trade 侧的 trade.purchase-reconciliation.read 权限）",
            e);
      }
      throw unavailable("trade 服务返回 " + e.getStatusCode().value() + "：采购对账事实读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("trade 服务不可达");
    }
  }

  /** 上游状态是否**可以**导入财务侧。 */
  public static boolean importable(String upstreamStatus) {
    return UPSTREAM_CONFIRMED.equals(upstreamStatus) || UPSTREAM_CLOSED.equals(upstreamStatus);
  }

  /** 上游不可用一律 503，理由写明「读不到」而不是「没有数据」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }
}
