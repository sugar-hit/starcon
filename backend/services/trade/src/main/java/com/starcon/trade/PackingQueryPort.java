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
 * 读取上游 8.3.3.2 打包与称重的**跨服务端口**。
 *
 * <p>蓝图 9.4.1.1 的输入句第一个词是「已完成打包的出库单」，而打包结果 （{@code packing_order} / {@code parcel} / {@code
 * parcel_item}）在 <b>warehouse 服务的库</b>里。AGENTS.md 模块边界纪律禁止跨服务 SQL / FK / 共享视图，所以本端口<b>只经 warehouse
 * 的授权 HTTP 只读端点</b>读， 不直连对方库、不建跨库视图、不建跨库外键。
 *
 * <p>用法与 6.4.1.1 的 {@code BomVersionPort}（读 catalog）逐字同构 —— 跨服务读一律走一个 port
 * 类，取不到就<b>显式失败</b>，绝不返回空集合冒充 「仓库没有已打包的出库单」：那会让看板显示 0 单，而真实情况是仓库不可达， 现场照着空看板以为货已经发完。
 *
 * <p>用的是 8.3.3.2（warehouse V121）已交付的三个只读形状：
 *
 * <ul>
 *   <li>{@code GET /warehouse/packing-orders?status=COMPLETED&page=&size=} —— 已完成打包的打包单（真分页 / 真筛选 /
 *       带汇总）；
 *   <li>{@code GET /warehouse/packing-orders/{id}} —— 单头 + 包裹 + 明细；
 *   <li>{@code GET /warehouse/packing-orders/capabilities} —— 上游能力声明。
 * </ul>
 *
 * <p>租户<b>不</b>由本端口传递租户号：转发的是<b>调用方自己的</b> {@code Authorization} bearer，warehouse 侧仍按已认证成员关系解析租户并强制
 * RLS。 也就是说租户隔离是<b>两道</b>（trade 侧 RLS + warehouse 侧 RLS）， 而不是「本服务替对方决定租户」。
 */
@Service
public class PackingQueryPort {

  /**
   * 8.3.3.2 的打包单行（字段名逐字对齐 warehouse 的 PackingRow 契约）。
   *
   * <p>刻意只声明本模块真正用到的字段：读得越少，跨服务契约越不容易在对方 加字段时把本模块撑爆。
   */
  record PackingOrderView(
      UUID id,
      UUID outboundOrderId,
      String outboundOrderNo,
      UUID warehouseId,
      String warehouseRef,
      String status,
      OffsetDateTime completedAt,
      List<ParcelView> parcels) {}

  /** 8.3.3.2 的包裹行（称重与量尺寸的实测读数）。 */
  record ParcelView(
      UUID id,
      int parcelNo,
      String status,
      BigDecimal grossWeightKg,
      BigDecimal lengthMm,
      BigDecimal widthMm,
      BigDecimal heightMm,
      boolean labelPrinted,
      String labelJobRef) {}

  /** 列表响应（P122 的 {@code PackingPage}）。 */
  record PackingPage(List<PackingOrderView> items, int page, int size, long totalElements) {}

  /** 详情响应（P122 的 {@code PackingDetail}）。 */
  record PackingDetail(PackingOrderView packingOrder) {}

  private final RestClient client;

  public PackingQueryPort(@Value("${WAREHOUSE_URL:http://127.0.0.1:8084}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    // 读超时给到 8 秒：同步一页可能要几十张打包单的详情，比单条读慢一截，
    // 但仍然必须有上限 —— 没有上限时仓库一慢就把本服务的请求线程全占住。
    factory.setReadTimeout(Duration.ofSeconds(8));
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  /**
   * 拉一页<b>已完成打包</b>的打包单。
   *
   * <p>用 {@code status=COMPLETED} 而不是拉全量再在本地筛：已完成是蓝图输入句的 原词（「已完成打包的出库单」），而 8.3.3.2 的状态机里 COMPLETED
   * 是唯一把 出库单推进到「待发货」的那一步（STARTED / FAILED / CANCELLED 都不该出现在 待发货看板上）。
   */
  public PackingPage completedPage(UUID warehouseId, int page, int size, String bearer) {
    try {
      PackingPage result =
          client
              .get()
              .uri(
                  uriBuilder -> {
                    var b =
                        uriBuilder
                            .path("/warehouse/packing-orders")
                            .queryParam("status", "COMPLETED");
                    if (warehouseId != null) b.queryParam("warehouseId", warehouseId);
                    return b.queryParam("page", page).queryParam("size", size).build();
                  })
              .header("Authorization", bearer)
              .retrieve()
              .body(PackingPage.class);
      if (result == null || result.items() == null) {
        throw unavailable("仓库服务没有返回已打包的出库单列表");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
      }
      if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前身份无权读取仓库的打包结果，待发货清单暂时同步不了");
      }
      throw unavailable("仓库服务返回 " + e.getStatusCode().value() + "，待发货清单暂时同步不了");
    } catch (RestClientException e) {
      throw unavailable("仓库服务暂时不可达");
    }
  }

  /** 取一张打包单的包裹读数（同步时逐张取，包裹只在详情里给全）。 */
  public PackingDetail detail(UUID packingOrderId, String bearer) {
    try {
      PackingDetail detail =
          client
              .get()
              .uri("/warehouse/packing-orders/" + packingOrderId)
              .header("Authorization", bearer)
              .retrieve()
              .body(PackingDetail.class);
      if (detail == null || detail.packingOrder() == null) {
        throw unavailable("仓库服务没有返回这张打包单的详情");
      }
      return detail;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "仓库没有这张打包单：" + packingOrderId);
      }
      if (e.getStatusCode() == HttpStatus.UNAUTHORIZED
          || e.getStatusCode() == HttpStatus.FORBIDDEN) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前身份无权读取该打包单，待发货清单暂时同步不了");
      }
      throw unavailable("仓库服务返回 " + e.getStatusCode().value() + "，待发货清单暂时同步不了");
    } catch (RestClientException e) {
      throw unavailable("仓库服务暂时不可达");
    }
  }

  /**
   * 不可达一律 503，并把「为什么」如实报出。
   *
   * <p>刻意<b>不</b>返回空列表冒充「没有已打包的出库单」：空清单会被当成 「今天没有待发货的货」而让现场收工，而真实情况是仓库挂了。
   */
  private ResponseStatusException unavailable(String reason) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        reason
            + "：待发货清单的唯一来源是 8.3.3.2 的授权只读接口"
            + "（warehouse 服务，跨库视图与跨服务 SQL 均被 AGENTS.md 禁止），"
            + "本服务不会凭空生成一份清单");
  }
}
