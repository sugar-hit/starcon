package com.starcon.trade;

import java.math.BigDecimal;
import java.time.Duration;
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
 * 读取上游 8.2.1.1 收货单的<b>跨服务端口</b> —— 本模块「这批货到了没有」这个事实的<b>唯一来源</b>。
 *
 * <p>蓝图 06-purchase-and-supply.md 的输入句写死「8 仓储的收货事件」，
 * 而收货单（{@code goods_receipt} 及其明细 / 实收事件）在 <b>warehouse 服务的库</b>里。
 * AGENTS.md 模块边界纪律禁止跨服务 SQL / FK / 共享视图，所以本端口<b>只经 warehouse 的授权只读端点</b>读，
 * 不直连对方库、不建跨库视图、不建跨库外键。
 *
 * <p>用法与 {@link ShipmentHandoverQueryPort}（读 warehouse 的交接结果）、
 * {@link DealerPartyQueryPort}（读 catalog 的往来单位）逐字同构 ——
 * 跨服务读一律走一个 port 类，取不到就<b>显式失败</b>，绝不返回空集合冒充
 * 「这个租户一张收货单都没有」：空清单会被采购员读成「货还没到」，
 * 而真实情况是仓库服务挂了 —— 那两件事的处理方式完全相反
 * （前者是正常业务，后者要叫人）。
 *
 * <p>用 8.2.1.1（warehouse V70）已交付的只读形状：
 *
 * <ul>
 *   <li>{@code GET /warehouse/goods-receipts?keyword={code}&page=&size=} ——
 *       按收货单号 / 来源单据号检索（真分页 + 真筛选）；</li>
 *   <li>{@code GET /warehouse/goods-receipts/{id}} —— 单张收货单详情。</li>
 * </ul>
 *
 * <p><b>为什么按「收货单号」精确匹配</b>：warehouse 的 {@code keyword} 是模糊匹配
 * （{@code ilike '%kw%'}），本模块要的是「就是这一张收货单」，
 * 所以取回后<b>在本服务按单号逐字相等</b>过滤 —— 模糊命中一个「GR-2024-1」就当成
 * 「GR-2024-1」的单据，会把别的单的实收量记到这一张采购单上。
 *
 * <p>⚠️ **8.2.1.1 侧当前不接受 PURCHASE 来源的收货单**
 * （warehouse 自己的能力声明里写明「6 采购与供应尚未交付」）。本模块交付的是
 * 6.3.2.2 到货跟踪，**不是** 6.3.1.x 采购单 ⇒ 那条限制**依然成立**，
 * 仓库侧不会因为本模块上线就突然接受采购来源。
 * 所以本端口的用途是：<b>当操作员报了一个收货单号时，去仓库核对这张单确实存在、
 * 确实在这个租户名下、实收了多少</b>，把核对结果快照进本地的到货流水；
 * 拿不到就报「仓库里没有这张收货单」而不是替它编一个。
 *
 * <p>租户<b>不</b>由本端口传递租户号：转发的是<b>调用方自己的</b>
 * {@code Authorization} bearer，warehouse 侧仍按已认证成员关系解析租户并强制 RLS。
 * 也就是说租户隔离是<b>两道</b>（trade 侧 RLS + warehouse 侧 RLS），
 * 而不是「本服务替对方决定租户」。
 */
@Service
public class WarehouseReceiptQueryPort {

  /**
   * 8.2.1.1 的收货单行（字段名逐字对齐 warehouse 的 GoodsReceiptRow 契约）。
   *
   * <p>刻意只声明本模块真正用到的字段：读得越少，跨服务契约越不容易在对方
   * 加字段时把本模块撑爆。
   */
  record ReceiptView(
      UUID id,
      String receiptCode,
      String sourceType,
      String sourceRefCode,
      UUID warehouseId,
      String warehouseCode,
      String status,
      String waybillNo,
      BigDecimal expectedQuantity,
      BigDecimal receivedQuantity,
      BigDecimal rejectedQuantity,
      BigDecimal outstandingQuantity,
      long eventCount,
      long discrepancyCount) {}

  /** 列表响应（V70 的 {@code GoodsReceiptPage}）。 */
  record ReceiptPage(List<ReceiptView> rows, long total, int page, int size) {}

  private final RestClient client;

  public WarehouseReceiptQueryPort(@Value("${WAREHOUSE_URL:http://127.0.0.1:8084}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    // 读超时给到 8 秒：拉一批候选要逐条比对单号，比单条读慢一截，
    // 但仍然必须有上限 —— 没有上限时仓库一慢就把本服务的请求线程全占住。
    factory.setReadTimeout(Duration.ofSeconds(8));
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  /**
   * 按收货单号精确查一张收货单。
   *
   * <p>返回 {@code null} 表示「仓库里没有这一张单」；<b>上游不可达一律抛 503</b>，
   * 绝不把「查不到」与「仓库挂了」混成同一个答案。
   */
  public ReceiptView findByCode(String receiptCode, String bearer) {
    if (receiptCode == null || receiptCode.isBlank()) {
      return null;
    }
    String wanted = receiptCode.trim();
    ReceiptPage page = page(wanted, bearer);
    if (page == null || page.rows() == null) {
      throw unavailable("仓库服务没有返回收货单列表");
    }
    for (ReceiptView row : page.rows()) {
      // ⚠️ 逐字相等才算命中：warehouse 的 keyword 是模糊匹配。
      if (row.receiptCode() != null && row.receiptCode().equals(wanted)) {
        return row;
      }
    }
    return null;
  }

  /** 拉一页候选收货单。 */
  private ReceiptPage page(String keyword, String bearer) {
    try {
      ReceiptPage result =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path("/warehouse/goods-receipts")
                          .queryParam("keyword", keyword)
                          .queryParam("page", 0)
                          .queryParam("size", 20)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(ReceiptPage.class);
      if (result == null) {
        throw unavailable("仓库服务没有返回收货单列表");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
      }
      if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "当前身份无权读取仓库的收货单，到货暂时无法与仓库核对");
      }
      throw unavailable("仓库服务返回 " + e.getStatusCode().value() + "，到货暂时无法与仓库核对");
    } catch (RestClientException e) {
      throw unavailable("仓库服务暂时不可达");
    }
  }

  /**
   * 不可达一律 503，并把「为什么」如实报出。
   *
   * <p>刻意<b>不</b>返回空清单冒充「仓库里没有收货单」：那会让采购员以为
   * 「这批货确实还没到」而继续等，而真实情况是仓库挂了。
   */
  private ResponseStatusException unavailable(String reason) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        reason
            + "：收货事件（8.2.1.1）的唯一来源是仓库的授权只读接口"
            + "（warehouse 服务，跨库视图与跨服务 SQL 均被 AGENTS.md 禁止），"
            + "本服务不会凭空生成一份收货单清单");
  }
}
