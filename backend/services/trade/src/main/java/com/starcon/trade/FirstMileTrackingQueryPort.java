package com.starcon.trade;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 读取上游 10.4.1.2 在途跟踪的<b>跨服务端口</b> —— 本模块「在途滞留了几天」这个事实的<b>唯一来源</b>。
 *
 * <p>蓝图 06-purchase-and-supply.md 的输入句写死「物流在途状态（10）」，
 * 而头程在途跟踪（{@code first_mile_tracking} 及其节点事件流）在 <b>warehouse 服务的库</b>里
 * （卷 10 的 10.4.1.1 头程发运单 / 10.4.1.2 在途跟踪都归 warehouse 主责）。
 * AGENTS.md 模块边界纪律禁止跨服务 SQL / FK / 共享视图，所以本端口<b>只经 warehouse 的授权只读端点</b>读，
 * 不直连对方库、不建跨库视图、不建跨库外键。
 *
 * <p>用法与 {@link ShipmentHandoverQueryPort}（读 warehouse 的交接结果）逐字同构 ——
 * 跨服务读一律走一个 port 类，取不到就<b>显式失败</b>，绝不返回空集合冒充
 * 「这批货没有在途记录」：空集合会被读成「供应商还没发货」，
 * 而真实情况是在途服务挂了。
 *
 * <p>用 10.4.1.2（warehouse V84）已交付的只读形状：
 *
 * <ul>
 *   <li>{@code GET /warehouse/transit-trackings?keyword={code}&page=&size=} ——
 *       在途看板（真分页 + 真筛选）；</li>
 *   <li>{@code GET /warehouse/transit-trackings/{id}} ——
 *       头 + <b>节点事件流</b> + 在途腿 + 未结预警。</li>
 * </ul>
 *
 * <p><b>为什么必须取详情而不是只用列表行</b>：<b>滞留天数</b>要的是
 * 「最后一次轨迹节点距今多少天」，而节点事件流只在详情里给。
 * 列表行的 {@code remainingDays} 是「距预计到港还有几天」，
 * 那是 ETA 口径不是滞留口径 —— 拿 ETA 口径当滞留口径去支撑「建议催货」，
 * 依据就是错的。
 *
 * <p>⚠️ **本端口不决定「算不算逾期」**：逾期是承诺到货日口径（跟单 / 采购单带来的输入事实），
 * 在途只提供「货在路上的滞留天数」这一个事实。两者口径不同，不能互相替代。
 *
 * <p>租户<b>不</b>由本端口传递租户号：转发的是<b>调用方自己的</b>
 * {@code Authorization} bearer，warehouse 侧仍按已认证成员关系解析租户并强制 RLS。
 */
@Service
public class FirstMileTrackingQueryPort {

  /** 10.4.1.2 的在途跟踪行（字段名逐字对齐 warehouse 的 TrackingRow 契约）。 */
  record TrackingView(
      UUID id,
      UUID shipmentId,
      String shipmentCode,
      String transportMode,
      String carrierName,
      String bookingNo,
      String status,
      String trackingStage,
      String customsStatus,
      String currentLocation,
      OffsetDateTime locationOccurredAt,
      OffsetDateTime effectiveEta,
      BigDecimal remainingDays,
      boolean overdue,
      String delayLevel,
      long openAlertCount,
      long legCount,
      OffsetDateTime receiptConfirmedAt) {}

  /** 10.4.1.2 的节点事件行。 */
  record TrackingEventView(
      UUID id,
      String kind,
      String locationText,
      String detail,
      OffsetDateTime occurredAt) {}

  /** 在途详情（V84 的 {@code TransitTrackDetail}）。 */
  record TrackingDetail(TrackingView tracking, List<TrackingEventView> events) {}

  /** 列表响应（V84 的 {@code TransitTrackPage}）。 */
  record TrackingPage(List<TrackingView> rows, long total, int page, int size) {}

  private final RestClient client;

  public FirstMileTrackingQueryPort(@Value("${WAREHOUSE_URL:http://127.0.0.1:8084}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    // 读超时给到 8 秒：取详情要连节点事件流一起拉，比单条读慢一截，
    // 但仍然必须有上限 —— 没有上限时仓库一慢就把本服务的请求线程全占住。
    factory.setReadTimeout(Duration.ofSeconds(8));
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  /**
   * 按头程发运单号精确查一条在途跟踪。
   *
   * <p>返回 {@code null} 表示「没有这一票在途记录」；<b>上游不可达一律抛 503</b>，
   * 绝不把「查不到」与「仓库挂了」混成同一个答案。
   */
  public TrackingView findByShipmentCode(String shipmentCode, String bearer) {
    if (shipmentCode == null || shipmentCode.isBlank()) {
      return null;
    }
    String wanted = shipmentCode.trim();
    TrackingPage page = page(wanted, bearer);
    if (page == null || page.rows() == null) {
      throw unavailable("仓库服务没有返回在途跟踪列表");
    }
    for (TrackingView row : page.rows()) {
      // ⚠️ 逐字相等才算命中：warehouse 的 keyword 是模糊匹配
      //   （发运单号 / 承运人 / 订舱号 / 当前位置 / 船名航班号）。
      if (row.shipmentCode() != null && row.shipmentCode().equals(wanted)) {
        return row;
      }
    }
    return null;
  }

  /** 取一条在途跟踪的详情（含节点事件流 —— 滞留天数的唯一来源）。 */
  public TrackingDetail detail(UUID trackingId, String bearer) {
    try {
      TrackingDetail detail =
          client
              .get()
              .uri("/warehouse/transit-trackings/" + trackingId)
              .header("Authorization", bearer)
              .retrieve()
              .body(TrackingDetail.class);
      if (detail == null || detail.tracking() == null) {
        throw unavailable("仓库服务没有返回这条在途跟踪的详情");
      }
      return detail;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND, "仓库没有这条在途跟踪：" + trackingId);
      }
      if (e.getStatusCode() == HttpStatus.UNAUTHORIZED
          || e.getStatusCode() == HttpStatus.FORBIDDEN) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "当前身份无权读取该在途跟踪，在途状态暂时同步不了");
      }
      throw unavailable("仓库服务返回 " + e.getStatusCode().value() + "，在途状态暂时同步不了");
    } catch (RestClientException e) {
      throw unavailable("仓库服务暂时不可达");
    }
  }

  /** 拉一页候选在途跟踪。 */
  private TrackingPage page(String keyword, String bearer) {
    try {
      TrackingPage result =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path("/warehouse/transit-trackings")
                          .queryParam("keyword", keyword)
                          .queryParam("page", 0)
                          .queryParam("size", 20)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(TrackingPage.class);
      if (result == null) {
        throw unavailable("仓库服务没有返回在途跟踪列表");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
      }
      if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "当前身份无权读取仓库的在途跟踪，在途状态暂时同步不了");
      }
      throw unavailable("仓库服务返回 " + e.getStatusCode().value() + "，在途状态暂时同步不了");
    } catch (RestClientException e) {
      throw unavailable("仓库服务暂时不可达");
    }
  }

  /**
   * 不可达一律 503，并把「为什么」如实报出。
   *
   * <p>刻意<b>不</b>返回空集合冒充「没有在途记录」。
   */
  private ResponseStatusException unavailable(String reason) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        reason
            + "：物流在途状态（10.4.1.2）的唯一来源是仓库的授权只读接口"
            + "（warehouse 服务，跨库视图与跨服务 SQL 均被 AGENTS.md 禁止），"
            + "本服务不会凭空生成一份在途记录");
  }
}
