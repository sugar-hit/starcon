package com.starcon.trade;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 11.3.2.1 换货授权的**跨服务写端口** —— 替换货预占在本模块的<b>唯一</b>来源与唯一出口。
 *
 * <p>蓝图 11.3.2.1 职责句把「<b>要真实预占替换货</b>」写成加粗，这是本模块最容易做成假的一条：
 * 查一次可用量、在自己库里写个 {@code is_reserved = true}，看起来预占完成了 ——
 * 那不是预占，那是把「查到什么」记成「占住了什么」，并发两单会把同一份库存预占两次。
 *
 * <p>实查集成头：<b>预占事实的写入所有者是 warehouse</b>（7.2.1.1 {@code inventory_reservation}，迁移 V102；
 * 物理圈货到 bucket 由 7.2.1.2 {@code reservation_allocation} 落 V107）。AGENTS.md 模块边界纪律禁止
 * 跨服务 SQL / 外键 / 共享视图，所以本端口<b>只经 warehouse 的授权 HTTP 端点</b>读写，
 * 不直连对方库、不建跨库视图、不建跨库外键。
 *
 * <p>用 7.2.1.1 / 7.4.1.x / 7.2.1.3 已交付的形状：
 *
 * <ul>
 *   <li>{@code GET /warehouse/inventory/sellable/skus?warehouseId=&skuId=} —— <b>可售量</b>（输入句「库存可用性」）；</li>
 *   <li>{@code POST /warehouse/inventory/reservations} —— <b>登记预占</b>（输出句「替换货预占」）；</li>
 *   <li>{@code POST /warehouse/inventory/reservations/{id}/release} —— <b>释放</b>（撤销换货时把货真还回去）。</li>
 * </ul>
 *
 * <p><b>为什么必须走这三个而不是自己算</b>：V107 第三节明写「可用量是否真的够，由 7.1.1.x 的分配策略裁决，
 * 本表不重复实现扣减，避免两个地方各算一次且口径分叉」。本服务若自己拿 available 减一遍再写库，
 * 就在两个地方各算了一次，且第二个地方没有 {@code ck_balance_available} 兜底。
 *
 * <p><b>补偿是真释放，不是改标志位</b>：本地事务失败时，本端口调 warehouse 的 release 把货还回去。
 * 服务层在拿到 warehouse 应答之前不会把 {@code exchange_reservation.status} 置 RELEASED ——
 * 「本地说还了、仓库还占着」的幽灵占用正是从这里来的。
 *
 * <p>租户<b>不</b>由本端口传递租户号：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * warehouse 侧仍按已认证成员关系解析租户并强制 RLS。隔离是<b>两道</b>（trade 侧 RLS + warehouse 侧 RLS），
 * 而不是「本服务替对方决定租户」。
 *
 * <p>⚠️ 登记与释放在 warehouse 侧要 {@code inventory.update} 权限。调用方角色没有该权限时
 * warehouse 返回 403，本端口<b>原样如实报出</b>而不是降级成「先记着稍后补预占」——
 * 那样等于凭空造出一批只发不收的单子。
 */
@Service
public class ExchangeReservationPort {

  /**
   * warehouse 的可售量行（字段名逐字对齐 7.4.1.x 的 {@code AvailableQuantityRow}）。
   *
   * <p>只声明本模块真正用到的字段：读得越少，跨服务契约越不容易在对方加字段时把本模块撑爆。
   * {@code sellable} 是**含安全缓冲**后的真正可卖量，{@code available} 是 {@code on_hand − reserved}；
   * 本模块按 {@code sellable} 判能不能换 —— 按 available 判会在安全库存被击穿时发出替换货。
   */
  record SellableView(
      UUID skuId,
      String skuCode,
      String skuName,
      BigDecimal onHand,
      BigDecimal reserved,
      BigDecimal available,
      BigDecimal sellable) {}

  record SellablePage(List<SellableView> rows, long total, int page, int size) {}

  /** 7.2.1.1 登记结果（{@code ReservationCreated}）。 */
  record ReservationCreatedView(UUID id, String reservationNo, String status, String statusLabel) {}

  /** 7.2.1.3 释放结果（本模块只用到确认位与幂等重放位）。 */
  record ReleaseOutcomeView(
      UUID reservationId,
      String reservationNo,
      boolean terminal,
      String statusBefore,
      String statusAfter,
      String restoredQuantity,
      boolean replayed,
      String message) {}

  private final RestClient client;

  public ExchangeReservationPort(@Value("${WAREHOUSE_URL:http://127.0.0.1:8084}") String url) {
    var factory =
        new JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    // 写操作要给得比读宽一点：预占要落对方的库并过 DB 触发器，但仍必须有上限 ——
    // 没有上限时仓库一慢就把本服务的请求线程全占住。
    factory.setReadTimeout(Duration.ofSeconds(8));
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  /** 7.2.1.1 登记预占的入参（字段名逐字对齐 {@code ReservationCreateInput}）。 */
  record ReservationCreate(
      String sourceSystem,
      String sourceType,
      UUID referenceId,
      String referenceCode,
      UUID legalEntityId,
      UUID skuId,
      BigDecimal requestedQuantity,
      OffsetDateTime heldAt,
      OffsetDateTime expiresAt,
      String note) {}

  // ================================================================ 可售量

  /**
   * 取一个替换 SKU 在指定仓的<b>可售量</b>（含安全缓冲）。
   *
   * <p>刻意<b>不</b>在找不到时返回 0：「仓里没有这个 SKU」与「仓库服务挂了」是两件事，
   * 前者该拒绝换货，后者该重试。返回 0 会让前者被伪装成后者，运营会一直等一个永远不会成功的重试。
   */
  public SellableView sellable(UUID warehouseId, UUID skuId, String bearer) {
    try {
      SellablePage page =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path("/warehouse/inventory/sellable/skus")
                          .queryParam("warehouseId", warehouseId)
                          .queryParam("skuId", skuId)
                          .queryParam("page", 0)
                          .queryParam("size", 1)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(SellablePage.class);
      if (page == null || page.rows() == null || page.rows().isEmpty()) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "替换 SKU " + skuId + " 在所选仓没有可售量记录，请先确认该 SKU 在本仓有货");
      }
      return page.rows().get(0);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
      }
      if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "当前身份无权读取仓库可售量，换货授权暂时做不了");
      }
      throw unavailable("仓库可售量查询返回 " + e.getStatusCode().value());
    } catch (RestClientException e) {
      throw unavailable("仓库服务暂时不可达");
    }
  }

  // ================================================================ 预占

  /**
   * <b>真实登记一笔替换货预占</b>（7.2.1.1）。
   *
   * <p>这是本模块最关键的一次远程写：它落的是 warehouse 自己的 {@code inventory_reservation} 行，
   * 由 V102 的 DB 触发器与 CHECK 兜底。返回的 {@code id} / {@code reservationNo} 被如实记进
   * {@code exchange_reservation} —— 本服务**不会**凭空造一个预占 id。
   *
   * <p>{@code referenceId} 传换货授权单的 id：warehouse 侧的定位键是
   * {@code source_system + reference_id}，用它才能在跨服务重试时认出「这是同一笔需求」。
   */
  public ReservationCreatedView create(ReservationCreate input, String bearer) {
    try {
      ReservationCreatedView created =
          client
              .post()
              .uri("/warehouse/inventory/reservations")
              .header("Authorization", bearer)
              .body(input)
              .retrieve()
              .body(ReservationCreatedView.class);
      if (created == null || created.id() == null) {
        throw unavailable("仓库服务没有返回预占登记结果");
      }
      return created;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
      }
      if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN,
            "当前身份无权在仓库登记预占（需要仓库的库存更新权限），换货授权已中止，没有产生任何预占");
      }
      throw unavailable("仓库预占登记返回 " + e.getStatusCode().value());
    } catch (RestClientException e) {
      throw unavailable("仓库服务暂时不可达", e);
    }
  }

  /**
   * 真把一笔预占释放掉（7.2.1.3）。
   *
   * <p>撤销换货时调用。取不到 warehouse 的应答就整笔失败 ——
   * 「本地置 RELEASED 而仓库还占着」的幽灵占用正是这样产生的。
   */
  public ReleaseOutcomeView release(UUID reservationId, BigDecimal quantity, String reason, String bearer) {
    try {
      ReleaseOutcomeView outcome =
          client
              .post()
              .uri("/warehouse/inventory/reservations/" + reservationId + "/release")
              .header("Authorization", bearer)
              // 释放入参：整笔终结 + 触发来源 + 原因。idempotencyKey 让跨服务重试幂等 ——
              // 上游事件至少投递一次，第二次到达必须成功而不是把货再还一次。
              .body(
                  java.util.Map.of(
                      "quantity", quantity,
                      "triggerSource", "TRADE_EXCHANGE_CANCEL",
                      "reason", reason == null ? "换货撤销" : reason,
                      "idempotencyKey", "exchange-cancel-" + reservationId))
              .retrieve()
              .body(ReleaseOutcomeView.class);
      if (outcome == null || outcome.reservationId() == null) {
        throw unavailable("仓库服务没有返回释放结果");
      }
      return outcome;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
      }
      if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "当前身份无权释放仓库预占，换货未撤销，预占仍在仓库占用着");
      }
      throw unavailable("仓库预占释放返回 " + e.getStatusCode().value());
    } catch (RestClientException e) {
      throw unavailable("仓库服务暂时不可达", e);
    }
  }

  /**
   * 不可达一律 503，并把「为什么」如实报出。
   *
   * <p>刻意<b>不</b>降级成「先授权、预占稍后补」：那样会造出一批账上已授权、仓库里没占住货的单子，
   * 而这些单子正是蓝图加粗句要挡的「只发不收」的前身。
   */
  private ResponseStatusException unavailable(String reason) {
    return unavailable(reason, null);
  }

  /**
   * 不可达一律 503，并**保留原始异常**。
   *
   * <p>把 {@code RestClientException} 直接吞掉而不带上 cause，等于把「连接被重置」
   * 与「响应体解析失败」这两类完全不同的故障压成同一句话 ——
   * 排查时只能靠猜。这里保留 cause，调用方在日志里能看到真实的传输层错误。
   */
  private ResponseStatusException unavailable(String reason, Throwable cause) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        reason
            + "：替换货预占的唯一合法入口是 warehouse 的授权写接口（7.2.1.1 / 7.2.1.3，"
            + "跨库视图与跨服务 SQL 均被 AGENTS.md 禁止），"
            + "本服务不会在没有真实预占的情况下把换货单写成已授权",
        cause);
  }
}
