package com.starcon.channel;

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
 * 读取上游 9.4.1.1 待发货的**跨服务只读端口**。
 *
 * <p>蓝图 10.3.2.1 的输入句是「已发货包裹」，而本模块的候选池来自
 * <b>trade 服务</b>的 {@code shipment_pending_unit}：9.4.1.1（V124）的状态机是
 * {@code PENDING → WAYBILL_REQUESTED → LABEL_PRINTED → HANDED_OVER}，
 * <b>到 HANDED_OVER 为止</b>，它刻意不产生已发货 —— 「已发货」是承运商揽收后
 * 由下游确认的事实。所以本模块的「已发货包裹」落点就是上游的 HANDED_OVER 单元：
 * 已完成打包与面单、已从待发货队列交出来、正等着交给承运商的那一批。
 *
 * <p>⚠️ AGENTS.md 模块边界纪律禁止跨服务 SQL / FK / 共享视图，所以本端口
 * <b>只经 trade 的授权 HTTP API</b>读，不直连对方库、不建跨库视图、不建跨库外键。
 *
 * <p>租户<b>不</b>由本端口传递租户号：转发的是<b>调用方自己的</b>
 * {@code Authorization} bearer，trade 侧仍按已认证成员关系解析租户并强制 RLS。
 * 也就是说租户隔离是<b>两道</b>（channel 侧 RLS + trade 侧 RLS），
 * 而不是「本服务替对方决定租户」。
 *
 * <p>用法与 9.4.1.1 自己的 {@code PackingQueryPort}（读 warehouse）、6.4.1.1 的
 * {@code BomVersionPort}（读 catalog）逐字同构：取不到就<b>显式失败</b>，
 * 绝不返回空集合冒充「上游没有可交运的包裹」—— 那会让现场看到 0 件，
 * 照着空清单以为货已经全交出去了。
 */
@Service
public class PendingShipmentQueryPort {
  /** 上游只读形状：{@code GET /trade/fulfillment/pending-shipment/units}。 */
  static final String UNITS_PATH = "/trade/fulfillment/pending-shipment/units";
  static final String UNIT_DETAIL_PATH = "/trade/fulfillment/pending-shipment/units/{id}";

  /**
   * 上游一行待发货单元（只声明本模块真正用到的字段）。
   *
   * <p>刻意读得少：读得越少，上游加字段时本模块越不容易被撑爆。
   */
  record PendingUnitView(
      UUID id,
      UUID sourcePackingOrderId,
      String warehouseRef,
      String outboundOrderNo,
      String salesOrderNo,
      String destinationCountry,
      String carrierCode,
      String carrierChannel,
      int parcelCount,
      BigDecimal totalWeightKg,
      String status,
      String waybillNo,
      OffsetDateTime packedAt,
      OffsetDateTime handedOverAt) {}

  /** 上游逐包裹行（交接清单要逐件记重量尺寸，箱数多时并成一条就说不出是哪箱）。 */
  record PendingParcelView(
      UUID id, int parcelNo, BigDecimal grossWeightKg,
      BigDecimal lengthMm, BigDecimal widthMm, BigDecimal heightMm) {}

  /** 上游逐件详情：单元 + 包裹 + 运单。 */
  record PendingUnitDetail(
      PendingUnitView unit, List<PendingParcelView> parcels, List<PendingWaybillView> waybills) {}

  record PendingWaybillView(UUID id, String carrierCode, String status, String waybillNo, String errorMessage) {}

  record PendingUnitPage(List<PendingUnitView> items, int page, int size, long totalElements) {}

  private final RestClient client;

  public PendingShipmentQueryPort(@Value("${TRADE_URL:http://127.0.0.1:8082}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    // 读超时给到 10 秒：同步一页要穿透取每个单元的逐件详情，比单条读慢一截，
    // 但仍然必须有上限 —— 没有上限时 trade 一慢就把本服务的请求线程全占住。
    factory.setReadTimeout(Duration.ofSeconds(10));
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  /**
   * 拉一页<b>已交出待发货队列</b>（{@code HANDED_OVER}）的单元。
   *
   * <p>用 {@code status=HANDED_OVER} 而不是拉全量再本地筛：上游状态机里只有
   * HANDED_OVER 才意味着「已从待发货交出来、正等着交给承运商」，
   * PENDING / WAYBILL_REQUESTED / LABEL_PRINTED 三态都还没到交接这一跳
   * （LABEL_PRINTED 连面单都还没交完，交出去等于把没贴标的包裹交给承运商）。
   */
  public PendingUnitPage handedOverPage(
      UUID warehouseId, String carrierCode, String carrierChannel, int page, int size, String bearer) {
    try {
      PendingUnitPage result =
          client
              .get()
              .uri(
                  uriBuilder -> {
                    var b = uriBuilder.path(UNITS_PATH).queryParam("status", "HANDED_OVER");
                    if (warehouseId != null) b.queryParam("warehouseId", warehouseId);
                    if (carrierCode != null && !carrierCode.isBlank()) b.queryParam("carrierCode", carrierCode);
                    if (carrierChannel != null && !carrierChannel.isBlank())
                      b.queryParam("carrierChannel", carrierChannel);
                    return b.queryParam("page", page).queryParam("size", size).build();
                  })
              .header("Authorization", bearer)
              .retrieve()
              .body(PendingUnitPage.class);
      if (result == null || result.items() == null) {
        throw unavailable("trade 服务没有返回待发货单元列表");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "调用方无权读取上游待发货清单", e);
      }
      throw unavailable("trade 服务返回 " + e.getStatusCode().value() + "：待发货清单读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("trade 服务不可达");
    }
  }

  /** 取单个单元的逐件详情（包裹重量尺寸 + 运单号来源）。 */
  public PendingUnitDetail detail(UUID unitId, String bearer) {
    try {
      PendingUnitDetail result =
          client
              .get()
              .uri(UNIT_DETAIL_PATH.replace("{id}", unitId.toString()))
              .header("Authorization", bearer)
              .retrieve()
              .body(PendingUnitDetail.class);
      if (result == null || result.unit() == null) {
        throw unavailable("trade 服务没有返回待发货单元详情");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "调用方无权读取上游待发货单元详情", e);
      }
      throw unavailable("trade 服务返回 " + e.getStatusCode().value() + "：待发货单元详情读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("trade 服务不可达");
    }
  }

  /** 上游不可用一律 503，理由写明「读不到」而不是「没有数据」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }
}
