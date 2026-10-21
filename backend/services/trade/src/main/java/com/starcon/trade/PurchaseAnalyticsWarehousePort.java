package com.starcon.trade;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.5.2.1 采购分析读 warehouse 的**8.2.1.1 收货单**的跨服务只读端口。
 *
 * <p>蓝图 6.5.2.1 的输入句写死「采购单与收货数据」。本仓的采购单原单（6.3.1.x）**未交付**，
 * 而收货数据**已交付**且在 warehouse 服务的独立库 ⇒ 实收量只能经
 * <b>warehouse 的授权只读端点</b>读，<b>不建跨库 FK、不建跨库视图、不跨服务直查对方的表</b>
 * （AGENTS.md 模块边界纪律）。
 *
 * <p>用两个已交付形状：
 *
 * <ul>
 *   <li>{@code GET /warehouse/goods-receipts} —— 收货单列表（头：收货单号 / 目标仓 / 状态 / 实收量）。</li>
 *   <li>{@code GET /warehouse/goods-receipts/{receiptId}} —— 收货单详情（**明细行**带 SKU 与实收量，
 *       以及逐笔实收事件带 {@code receivedAt}）。</li>
 * </ul>
 *
 * <p><b>🔴 已知代价：端口内部是 1+N 次 HTTP 调用</b> —— warehouse 目前**没有**
 * 「按 SKU 汇总实收量」的只读端点，明细行只在收货单详情里，所以本端口先取收货单列表、
 * 再逐张取详情、在<b>端口内部</b>按行摊平（与 catalog 的 {@code CostBasisPort} 同一手法）。
 * 本端口**不新增 warehouse 的端点**（那是另一个服务的写入所有权）。
 * 为避免一次同步把仓库服务打穿，取单量有上限；被截断时如实报出（{@code truncated}），
 * <b>绝不假装扫完了全量</b>。
 *
 * <p><b>租户不由本端口传递租户号</b>：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * warehouse 侧仍按已认证成员关系解析租户并强制 RLS。隔离是<b>两道</b>。
 */
@Service
public class PurchaseAnalyticsWarehousePort {

  /** 收货单头（只声明本模块真正用到的字段）。 */
  record ReceiptView(
      UUID id,
      String receiptCode,
      String warehouseCode,
      String status,
      BigDecimal receivedQuantity,
      OffsetDateTime closedAt,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 收货单详情里的一个明细行。 */
  record ReceiptLineView(
      UUID id,
      UUID skuId,
      String skuCode,
      String skuName,
      BigDecimal expectedQuantity,
      BigDecimal receivedQuantity,
      BigDecimal rejectedQuantity) {}

  /** 逐笔实收事件。 */
  record ReceiptEventView(UUID id, OffsetDateTime receivedAt) {}

  /** 收货单详情（只声明本模块真正用到的字段）。 */
  record ReceiptDetailView(
      ReceiptView receipt, List<ReceiptLineView> lines, List<ReceiptEventView> events) {}

  /** 收货单列表响应。 */
  record ReceiptPage(List<ReceiptView> rows, long total, int page, int size, String note) {}

  /**
   * 一条摊平后的收货明细事实。
   *
   * @param occurredAt 实收时刻（逐笔实收事件的最后一条；没有事件时退回关单 / 建单时刻）
   */
  record ReceiptLineFact(
      UUID lineId,
      UUID receiptId,
      String receiptCode,
      String warehouseCode,
      String status,
      OffsetDateTime occurredAt,
      UUID skuId,
      String skuCode,
      String skuName,
      BigDecimal receivedQuantity,
      BigDecimal rejectedQuantity) {}

  /** 一次扫描的结果（{@code truncated=true} 表示取单量触顶，本批不是全量）。 */
  record ReceiptScan(List<ReceiptLineFact> lines, int receiptCount, boolean truncated, String note) {}

  /** 端口专用的 ObjectMapper：**忽略上游多出来的字段**。 */
  private static final ObjectMapper PORT_JSON =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private final RestClient client;

  public PurchaseAnalyticsWarehousePort(@Value("${WAREHOUSE_URL:http://127.0.0.1:8084}") String url) {
    var factory =
        new JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    // 读超时给到 10 秒：本端口一次要翻多张收货单的详情，比单条读慢。
    factory.setReadTimeout(Duration.ofSeconds(10));
    var converter = new MappingJackson2HttpMessageConverter(PORT_JSON);
    this.client =
        RestClient.builder()
            .baseUrl(url)
            .requestFactory(factory)
            .messageConverters(java.util.List.of(converter))
            .build();
  }

  /**
   * 扫描收货单，把明细行摊平成事实。
   *
   * @param maxReceipts 本次最多取多少张收货单（触顶即截断并如实报出）
   */
  public ReceiptScan receiptLines(String bearer, int maxReceipts) {
    List<ReceiptLineFact> facts = new ArrayList<>();
    int scanned = 0;
    boolean truncated = false;
    int page = 0;
    int size = 50;
    while (scanned < maxReceipts) {
      ReceiptPage page1 = receipts(bearer, page, size);
      if (page1 == null || page1.rows() == null || page1.rows().isEmpty()) {
        break;
      }
      for (ReceiptView receipt : page1.rows()) {
        if (scanned >= maxReceipts) {
          truncated = true;
          break;
        }
        scanned++;
        ReceiptDetailView detail = receiptDetail(bearer, receipt.id());
        if (detail == null || detail.lines() == null) {
          continue;
        }
        OffsetDateTime occurredAt = occurredAt(detail);
        for (ReceiptLineView line : detail.lines()) {
          facts.add(
              new ReceiptLineFact(
                  line.id(),
                  receipt.id(),
                  receipt.receiptCode(),
                  receipt.warehouseCode(),
                  receipt.status(),
                  occurredAt,
                  line.skuId(),
                  line.skuCode(),
                  line.skuName(),
                  line.receivedQuantity(),
                  line.rejectedQuantity()));
        }
      }
      if (truncated) {
        break;
      }
      page++;
      if (page1.rows().size() < size) {
        break;
      }
    }
    String note =
        truncated
            ? "本次只取了最近 " + maxReceipts + " 张收货单（取单量触顶）—— 这一批**不是全量**，"
                + "界面上必须显示这条说明，不能把它当成全量采购实绩。"
            : "已扫完收货单全量（" + scanned + " 张）。";
    return new ReceiptScan(facts, scanned, truncated, note);
  }

  /** 收货单列表。 */
  private ReceiptPage receipts(String bearer, int page, int size) {
    try {
      return client
          .get()
          .uri(
              uriBuilder ->
                  uriBuilder
                      .path("/warehouse/goods-receipts")
                      .queryParam("page", page)
                      .queryParam("size", size)
                      .build())
          .header("Authorization", bearer)
          .retrieve()
          .body(ReceiptPage.class);
    } catch (RestClientResponseException e) {
      throw upstream(e.getStatusCode().value(), "读收货单列表");
    } catch (RestClientException e) {
      throw unavailable("仓库服务暂时不可达");
    }
  }

  /** 收货单详情（明细行在这里）。 */
  private ReceiptDetailView receiptDetail(String bearer, UUID receiptId) {
    try {
      return client
          .get()
          .uri("/warehouse/goods-receipts/" + receiptId)
          .header("Authorization", bearer)
          .retrieve()
          .body(ReceiptDetailView.class);
    } catch (RestClientResponseException e) {
      throw upstream(e.getStatusCode().value(), "读收货单详情");
    } catch (RestClientException e) {
      throw unavailable("仓库服务暂时不可达");
    }
  }

  /** 实收时刻：逐笔实收的最后一条；没有实收事件时退回关单时刻，再退回建单时刻。 */
  private static OffsetDateTime occurredAt(ReceiptDetailView detail) {
    OffsetDateTime last = null;
    if (detail.events() != null) {
      for (ReceiptEventView event : detail.events()) {
        if (event == null || event.receivedAt() == null) {
          continue;
        }
        if (last == null || event.receivedAt().isAfter(last)) {
          last = event.receivedAt();
        }
      }
    }
    if (last != null) {
      return last;
    }
    ReceiptView receipt = detail.receipt();
    if (receipt != null && receipt.closedAt() != null) {
      return receipt.closedAt();
    }
    if (receipt != null && receipt.createdAt() != null) {
      return receipt.createdAt();
    }
    return OffsetDateTime.now();
  }

  private ResponseStatusException upstream(int status, String what) {
    if (status == HttpStatus.UNAUTHORIZED.value()) {
      return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
    }
    if (status == HttpStatus.FORBIDDEN.value()) {
      return new ResponseStatusException(
          HttpStatus.FORBIDDEN, "当前身份无权读取仓储作业数据，采购分析暂时取不到实收量");
    }
    return unavailable("仓库服务返回 " + status + "（读取" + what + "时）");
  }

  private ResponseStatusException unavailable(String reason) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        reason
            + "：实收量的事实来源是仓库服务的授权只读接口"
            + "（跨库视图与跨服务 SQL 均被 AGENTS.md 禁止），"
            + "本服务不会凭空造一个收货单出来");
  }
}
