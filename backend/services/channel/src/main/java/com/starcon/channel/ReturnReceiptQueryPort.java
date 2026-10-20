package com.starcon.channel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 10.7.1.1 退货接收与处置决策的**上游只读端口**（读 trade 服务的 11.2 退货管理）。
 *
 * <p>蓝图 10.7.1.1 的输入句是「退货实收记录（11.2.2.1）」与「商品再销售限制」，
 * 而实收记录与质检结论的真值<b>全在 trade 服务</b>：
 * {@code return_receipt} / {@code return_receipt_line}（V172）与
 * {@code return_quality_inspection}（V179）。AGENTS.md 模块边界纪律禁止跨服务 SQL /
 * FK / 共享视图，所以本端口<b>只经 trade 的授权 HTTP API</b>读，不直连对方库、
 * 不建跨库视图、不建跨库外键。
 *
 * <p>与 {@link PendingShipmentQueryPort}（同服务、同样读 trade）、{@link SellableQuantityQueryPort}
 * （读 warehouse）逐字同构：取不到就<b>显式失败</b>，绝不返回空集合冒充
 * 「上游没有退货」—— 那会让现场看到 0 件，照着空清单以为货已经全处理完了。
 *
 * <p>🔴 租户<b>不</b>由本端口传递租户号：转发的是<b>调用方自己的</b>
 * {@code Authorization} bearer，trade 侧仍按已认证成员关系解析租户并强制 RLS。
 * 也就是说租户隔离是<b>两道</b>（channel 侧 RLS + trade 侧 RLS），
 * 而不是「本服务替对方决定租户」。
 *
 * <p>🔴 「上游没有这条质检单」与「上游读不到」是<b>两件事</b>，本端口把它们分开：
 * <ul>
 *   <li>上游不可达 / 返回 5xx ⇒ <b>503</b>，理由写「读不到」；</li>
 *   <li>上游正常返回但<b>没有这一行</b> ⇒ 返回 {@code null}，由服务层据此判
 *       「上游尚未给出可再销售判定」⇒ <b>禁止</b>转销售。把它当成 503 会让
 *       「没质检」看起来像「系统坏了」；把它当成「可再销售」会直接把残次品当新品卖。</li>
 * </ul>
 */
@Service
public class ReturnReceiptQueryPort {
  /** 上游只读形状：{@code GET /trade/return-receipts/{id}}。 */
  static final String RECEIPT_PATH = "/trade/return-receipts/{id}";
  /** 上游只读形状：{@code GET /trade/return-quality/inspections}。 */
  static final String INSPECTIONS_PATH = "/trade/return-quality/inspections";
  /** 上游只读形状：{@code GET /trade/return-quality/inspections/{id}}。 */
  static final String INSPECTION_PATH = "/trade/return-quality/inspections/{id}";

  /**
   * 上游实收单头（本模块只声明真正用到的字段）。
   *
   * <p>刻意读得少：读得越少，上游加字段时本模块越不容易被撑爆。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ReceiptHeadView(UUID id, String receiptNo, String receiptStatus) {}

  /** 上游实收行：本模块要的是「这一行是哪个 SKU、收了多少」。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ReceiptLineView(
      UUID id, String skuCode, String skuName, String batchNo, BigDecimal receivedQty) {}

  /** 上游实收单全景（头 + 行）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ReceiptView(ReceiptHeadView head, List<ReceiptLineView> lines) {}

  /** 上游质检单列表行（本模块只要判定结论与残值）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record InspectionView(
      UUID id,
      UUID receiptId,
      UUID receiptLineId,
      String inspectionStatus,
      String verdict,
      BigDecimal originalValue,
      BigDecimal salvageAmount,
      String salvageCurrency) {}

  /** 上游质检单列表页。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record InspectionPage(List<InspectionView> rows, int page, int size, long total) {}

  /** 上游质检留痕：CONCLUDED 那一条带判定时刻。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record InspectionEventView(String eventType, OffsetDateTime occurredAt) {}

  /** 上游质检单详情（事件用于取判定时刻）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record InspectionDetail(List<InspectionEventView> events) {}

  private final RestClient client;

  public ReturnReceiptQueryPort(@Value("${TRADE_URL:http://127.0.0.1:8082}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    // 读超时给到 10 秒：同步一条实收单要穿透取逐行明细，比单条读慢一截，
    // 但仍然必须有上限 —— 没有上限时 trade 一慢就把本服务的请求线程全占住。
    factory.setReadTimeout(Duration.ofSeconds(10));
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  /**
   * 读一张上游实收单（头 + 逐行）。
   *
   * <p>返回 {@code null} = 上游正常响应但**没有这张单**（不是故障）。
   */
  public ReceiptView receipt(UUID receiptId, String bearer) {
    try {
      ReceiptView result =
          client
              .get()
              .uri(RECEIPT_PATH.replace("{id}", receiptId.toString()))
              .header("Authorization", bearer)
              .retrieve()
              .body(ReceiptView.class);
      if (result == null) {
        throw unavailable("trade 服务没有返回实收单");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "调用方无权读取上游退货实收记录", e);
      }
      if (e.getStatusCode().value() == 404) {
        // 上游明确说「没这张单」：这是**结论**，不是读不到。
        return null;
      }
      throw unavailable("trade 服务返回 " + e.getStatusCode().value() + "：退货实收记录读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("trade 服务不可达：退货实收记录读不到");
    }
  }

  /**
   * 读一张上游实收单的质检单列表。
   *
   * <p>只取 {@code status=CONCLUDED} 的已判定单：没判定的单没有结论可言，
   * 而「没有结论」在决策里是**禁止转销售**（保守口径），不是「大概率能卖」。
   */
  public List<InspectionView> concludedInspections(UUID receiptId, String bearer) {
    try {
      InspectionPage page =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(INSPECTIONS_PATH)
                          .queryParam("receiptId", receiptId.toString())
                          .queryParam("status", "CONCLUDED")
                          .queryParam("size", 200)
                          .queryParam("page", 0)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(InspectionPage.class);
      if (page == null || page.rows() == null) {
        throw unavailable("trade 服务没有返回质检单列表");
      }
      return page.rows();
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "调用方无权读取上游质检结论", e);
      }
      throw unavailable("trade 服务返回 " + e.getStatusCode().value() + "：质检结论读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("trade 服务不可达：质检结论读不到");
    }
  }

  /**
   * 读一张上游质检单的留痕（用来取**判定时刻**）。
   *
   * <p>只取事件类型与发生时刻：判定时刻是「当时凭什么这么判」的时间锚，
   * 少了它这条证据链就只有一个结论、没有结论的时间。
   */
  public List<InspectionEventView> inspectionEvents(UUID inspectionId, String bearer) {
    try {
      InspectionDetail detail =
          client
              .get()
              .uri(INSPECTION_PATH.replace("{id}", inspectionId.toString()))
              .header("Authorization", bearer)
              .retrieve()
              .body(InspectionDetail.class);
      if (detail == null || detail.events() == null) {
        throw unavailable("trade 服务没有返回质检留痕");
      }
      return detail.events();
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "调用方无权读取上游质检留痕", e);
      }
      throw unavailable("trade 服务返回 " + e.getStatusCode().value() + "：质检留痕读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("trade 服务不可达：质检留痕读不到");
    }
  }

  /** 上游不可用一律 503，理由写明「读不到」而不是「没有数据」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }
}
