package com.starcon.platform;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 9 订单履约（trade 服务）的**跨服务只读端口**：18.4.1.1「对话助手」的「待发货积压」意图走这里。
 *
 * <p>⚠️ <b>只转发调用方自己的 bearer，不传租户号</b>：上游仍按已认证成员关系解析租户并强制 RLS。
 *
 * <p>⚠️ <b>列表字段是 {@code items}</b>（trade 的这个端点用 items，不是 rows）。
 * 写错会得到 {@code items == null}，而「没有待发货单元」与「这一格没读出来」含义完全相反 ——
 * 所以本端口在 {@code items == null} 时**显式失败**，不返回空列表冒充「没有积压」。
 */
@Service
public class AssistantTradePort {

  private final PlatformUpstreamClient client;

  public AssistantTradePort(@Value("${TRADE_URL:http://127.0.0.1:8085}") String url) {
    this.client = PlatformUpstreamClient.to("trade 服务", url);
  }

  /** 一个待发货单元（一行 = 一个包裹单元）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AssistantPendingUnit(
      UUID id,
      String warehouseRef,
      String outboundOrderNo,
      String salesOrderNo,
      String shopRef,
      String buyerName,
      String destinationCountry,
      String carrierChannel,
      String carrierCode,
      String slaBucket,
      String slaSource,
      int parcelCount,
      BigDecimal totalWeightKg,
      String status,
      String waybillNo,
      boolean labelPrinted,
      OffsetDateTime packedAt,
      OffsetDateTime slaDeadline,
      OffsetDateTime handedOverAt,
      OffsetDateTime syncedAt,
      String nextActions) {}

  /** 待发货单元页（真分页 + 真计数）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AssistantPendingUnitPage(
      List<AssistantPendingUnit> items, int page, int size, long totalElements, int totalPages) {}

  /**
   * 拉待发货单元清单（**真实只读查询**）。
   *
   * <p>⚠️ <b>这是时点口径</b>：上游这个端点没有时间窗口参数 —— 待发货是**现在还卡着**的清单，
   * 不是「某段时间里卡过」的清单。所以问句里的「上周 / 本月」对它**不生效**，
   * 口径说明里会如实写明「时点口径，非窗口口径」。
   */
  public AssistantPendingUnitPage pendingUnits(String bearer) {
    return client.get(
        uriBuilder -> {
          uriBuilder
              .path("/trade/fulfillment/pending-shipment/units")
              .queryParam("page", 0)
              .queryParam("size", 50);
          return uriBuilder;
        },
        bearer,
        AssistantPendingUnitPage.class);
  }
}
