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
 * 读取上游 8.3.4.1 交接的**跨服务端口** —— 本模块「已发货」这个事实的<b>唯一来源</b>。
 *
 * <p>蓝图 9.4.1.2 的关系句写死「上游 = 8.3.4.1」，而交接结果
 * （{@code shipment_handover} / {@code shipment_handover_parcel}）在
 * <b>warehouse 服务的库</b>里。AGENTS.md 模块边界纪律禁止跨服务 SQL / FK /
 * 共享视图，所以本端口<b>只经 warehouse 的授权 HTTP 只读端点</b>读，
 * 不直连对方库、不建跨库视图、不建跨库外键。
 *
 * <p>用法与 {@link PackingQueryPort}（9.4.1.1 读 8.3.3.2）逐字同构 ——
 * 跨服务读一律走一个 port 类，取不到就<b>显式失败</b>，绝不返回空集合冒充
 * 「没有已发货的货」：那会让发货记录清单显示 0 单，而真实情况是仓库不可达，
 * 现场照着空清单以为今天一票都没发出去。
 *
 * <p>用 8.3.4.1（warehouse V123）已交付的只读形状：
 *
 * <ul>
 *   <li>{@code GET /warehouse/shipment-handovers?status=HANDED_OVER&page=&size=} ——
 *       <b>已确认交接</b>的交接单（真分页 / 真筛选 / 带汇总）；</li>
 *   <li>{@code GET /warehouse/shipment-handovers/{id}} —— 单头 + 逐箱运单号与实测读数；</li>
 * </ul>
 *
 * <p><b>为什么只取 HANDED_OVER</b>：蓝图那句「已发货」指货已经交运给承运商。
 * warehouse 侧只有 status = HANDED_OVER 才同时写死了承运商、主运单号、交接人、
 * 交接时刻，并在同一事务里完成实发扣减；OPEN 是「车还没来」，CANCELLED 是
 * 「货没交出去」。把 OPEN 也拉进来，就等于本模块凭空宣布货已发出 ——
 * 那正是 9.4.1.1 拒绝做 SHIPPED 的同一个理由。
 *
 * <p>租户<b>不</b>由本端口传递租户号：转发的是<b>调用方自己的</b>
 * {@code Authorization} bearer，warehouse 侧仍按已认证成员关系解析租户并强制 RLS。
 * 也就是说租户隔离是<b>两道</b>（trade 侧 RLS + warehouse 侧 RLS），
 * 而不是「本服务替对方决定租户」。
 */
@Service
public class ShipmentHandoverQueryPort {

  /**
   * 8.3.4.1 的交接单行（字段名逐字对齐 warehouse 的 HandoverRow 契约）。
   *
   * <p>刻意只声明本模块真正用到的字段：读得越少，跨服务契约越不容易在对方
   * 加字段时把本模块撑爆。
   *
   * <p>注意<b>没有</b>销售订单 / 店铺 / 买家 / 收货国：8.3.4.1 的交接单压根没有
   * 这几列（本模块从同服务 9.4.1.1 的投影里顺带补，见 V127 文件头），
   * 这里要是编出来几个字段，就等于把不存在的上游数据读成了「读到了」。
   */
  record HandoverView(
      UUID id,
      String handoverCode,
      UUID packingOrderId,
      UUID outboundOrderId,
      String outboundOrderCode,
      UUID warehouseId,
      String warehouseCode,
      String carrierCode,
      String carrierName,
      String masterWaybillNo,
      OffsetDateTime handoverAt,
      String status,
      int parcelCount,
      int parcelItemCount,
      String totalQuantity,
      String totalGrossWeightG,
      String totalVolumeCbm) {}

  /**
   * 8.3.4.1 的逐箱行。
   *
   * <p>{@code effectiveWaybillNo} 是逐箱生效的运单号：一票多箱时每个箱子都有
   * 自己的号（买家举证与有效追踪率都按箱走），主运单号只是这一整次的对外号。
   */
  record HandoverParcelView(
      UUID id,
      UUID parcelId,
      int parcelNo,
      String parcelCode,
      String parcelWaybillNo,
      String effectiveWaybillNo,
      String grossWeightG,
      String volumeCbm,
      int itemCount,
      String totalQuantity) {}

  /** 列表响应（V123 的 {@code HandoverPage}，total 字段名是 total 而不是 totalElements）。 */
  record HandoverPage(List<HandoverView> items, int page, int size, long total) {}

  /** 详情响应（V123 的 {@code HandoverDetail}）。 */
  record HandoverDetail(HandoverView order, List<HandoverParcelView> parcels) {}

  private final RestClient client;

  public ShipmentHandoverQueryPort(@Value("${WAREHOUSE_URL:http://127.0.0.1:8084}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    // 读超时给到 8 秒：同步一页可能要逐张取交接详情，比单条读慢一截，
    // 但仍然必须有上限 —— 没有上限时仓库一慢就把本服务的请求线程全占住。
    factory.setReadTimeout(Duration.ofSeconds(8));
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  /**
   * 拉一页<b>已确认交接</b>的交接单。
   *
   * <p>用 {@code status=HANDED_OVER} 而不是拉全量再在本地筛：OPEN 与 CANCELLED
   * 都不是「已发货」，本地筛会让人有机会把「车还没来」误登记成发货记录。
   */
  public HandoverPage handedOverPage(int page, int size, String bearer) {
    try {
      HandoverPage result =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path("/warehouse/shipment-handovers")
                          .queryParam("status", "HANDED_OVER")
                          .queryParam("page", page)
                          .queryParam("size", size)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(HandoverPage.class);
      if (result == null || result.items() == null) {
        throw unavailable("仓库服务没有返回已交接的交接单列表");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
      }
      if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "当前身份无权读取仓库的交接结果，发货记录暂时同步不了");
      }
      throw unavailable("仓库服务返回 " + e.getStatusCode().value() + "，发货记录暂时同步不了");
    } catch (RestClientException e) {
      throw unavailable("仓库服务暂时不可达");
    }
  }

  /** 取一张交接单的逐箱明细（同步时逐张取，逐箱运单号只在详情里给全）。 */
  public HandoverDetail detail(UUID handoverId, String bearer) {
    try {
      HandoverDetail detail =
          client
              .get()
              .uri("/warehouse/shipment-handovers/" + handoverId)
              .header("Authorization", bearer)
              .retrieve()
              .body(HandoverDetail.class);
      if (detail == null || detail.order() == null) {
        throw unavailable("仓库服务没有返回这张交接单的详情");
      }
      return detail;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND, "仓库没有这张交接单：" + handoverId);
      }
      if (e.getStatusCode() == HttpStatus.UNAUTHORIZED
          || e.getStatusCode() == HttpStatus.FORBIDDEN) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "当前身份无权读取该交接单，发货记录暂时同步不了");
      }
      throw unavailable("仓库服务返回 " + e.getStatusCode().value() + "，发货记录暂时同步不了");
    } catch (RestClientException e) {
      throw unavailable("仓库服务暂时不可达");
    }
  }

  /**
   * 不可达一律 503，并把「为什么」如实报出。
   *
   * <p>刻意<b>不</b>返回空列表冒充「今天没有发货」：空清单会被当成
   * 「今天一票都没发」而让运营收工，而真实情况是仓库挂了。
   */
  private ResponseStatusException unavailable(String reason) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        reason
            + "：发货记录的唯一来源是 8.3.4.1 的授权只读接口"
            + "（warehouse 服务，跨库视图与跨服务 SQL 均被 AGENTS.md 禁止），"
            + "本服务不会凭空生成一份发货清单");
  }
}
