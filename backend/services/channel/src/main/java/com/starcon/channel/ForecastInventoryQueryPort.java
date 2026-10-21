package com.starcon.channel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 10.4.2.2 备货预报的<b>库存只读端口</b> —— 自营可售量的<b>唯一来源</b>。
 *
 * <p>蓝图 §10.4.2.2 的输入句第二样是「库存与在途数据」。自营库存账本在
 * <b>warehouse 服务的库</b>里（V51 warehouse_inventory_ledger），AGENTS.md 模块边界纪律
 * 禁止跨服务 SQL / 跨库 FK / 共享视图，所以本端口<b>只经 warehouse 的授权只读端点</b>读：
 * {@code GET /warehouse/inventory/sellable/skus}。
 *
 * <p>🔴 <b>「查不到」与「确实没有货」必须分得开</b>，这是本端口最容易做丢的一处：
 *
 * <ul>
 *   <li>上游<b>不可达</b> → 抛 503。<b>绝不</b>返回「可售量 = 0」—— 那会让运营把
 *       「我们查不到」读成「没有货」，而那正是会真的少备 / 多备的路径。
 *   <li>上游<b>正常返回、但没有这个 SKU 的行</b> → 该 SKU {@code sellableKnown=false}，
 *       {@code sellable=null}。这不是故障：查询成功了，结论就是「这个 SKU 在可售量台账里没有行」。
 * </ul>
 *
 * <p>🔴 <b>可售量口径不在本模块定义</b>：warehouse 侧的公式是
 * 「合格可售 + 待检 + 残次 + 未上架/不可发货 + 批次冻结 = available」并扣安全缓冲。
 * 本模块<b>原样采用</b>它算出的 {@code sellable}，<b>不自己重算一遍</b> ——
 * 两个地方各算一次必然漂移，而漂移的方向一定是「这里说能卖、扣的时候不够」。
 *
 * <p>🔴 <b>租户不由本端口传递租户号</b>：转发的是调用方自己的 {@code Authorization} bearer，
 * warehouse 侧仍按已认证成员关系解析租户并强制 RLS。租户隔离是<b>两道</b>
 * （channel 侧 RLS + warehouse 侧 RLS），而不是本服务替对方决定租户。
 */
@Service
public class ForecastInventoryQueryPort {
  /** warehouse 的分 SKU 可售量读出口。 */
  static final String SELLABLE_SKUS_PATH = "/warehouse/inventory/sellable/skus";

  /** 单次校验的 SKU 数上限：不给上限时，一张开预报单能把本服务的线程占住。 */
  public static final int MAX_SKUS_PER_CALL = 20;

  /** warehouse 的一行可售量（只声明本模块真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SellableRow(
      UUID skuId,
      String skuCode,
      String skuName,
      BigDecimal onHand,
      BigDecimal reserved,
      BigDecimal available,
      BigDecimal sellableBeforeBuffer,
      BigDecimal safetyBuffer,
      BigDecimal sellable) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SellablePage(List<SellableRow> rows, long total, int page, int size, String note) {}

  /**
   * 一个 SKU 的可售量结论。
   *
   * @param sellableKnown 上游是否给出了这个 SKU 的可售量行。false 时 {@code sellable} 是
   *     {@code null}（<b>不是 0</b>）—— 「查不到」与「确实是 0」是两件事。
   */
  public record InventoryAvailability(
      String skuCode, BigDecimal sellable, boolean sellableKnown, String note) {}

  private final RestClient client;

  public ForecastInventoryQueryPort(@Value("${WAREHOUSE_URL:http://127.0.0.1:8084}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    // 读超时给到 8 秒：一次要穿透取一页可售量，比单条读慢一截，
    // 但仍然必须有上限 —— 没有上限时仓库一慢就把本服务的请求线程全占住。
    factory.setReadTimeout(Duration.ofSeconds(8));
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  /**
   * 读一组 SKU 的可售量。
   *
   * @param skuCodes 要查的 SKU 编码（逐字精确匹配，见 {@link #lookup}）
   * @param bearer 调用方自己的 bearer（转发，warehouse 侧按成员关系解析租户）
   * @throws ResponseStatusException 401（无权读）/ 503（warehouse 读不到）
   */
  public List<InventoryAvailability> sellable(List<String> skuCodes, String bearer) {
    java.util.List<InventoryAvailability> out = new java.util.ArrayList<>();
    for (String skuCode : skuCodes) {
      out.add(lookup(skuCode, bearer));
    }
    return out;
  }

  /**
   * 读一个 SKU 的可售量。
   *
   * <p>warehouse 的 {@code keyword} 是模糊匹配，所以必须<b>逐字相等</b>才算命中：
   * 模糊命中会把「SKU-1」算成「SKU-10」的可售量，用错一个数字就多备一批货。
   */
  public InventoryAvailability lookup(String skuCode, String bearer) {
    SellablePage page = fetch(skuCode, bearer);
    for (SellableRow row : page.rows()) {
      if (row.skuCode() != null && row.skuCode().equals(skuCode)) {
        return new InventoryAvailability(skuCode, row.sellable(), true, "上游可售量台账命中该 SKU");
      }
    }
    // 上游查得到、但没有这个 SKU 的行：**不是 0**，是「没有这一行」。
    return new InventoryAvailability(
        skuCode, null, false, "上游可售量台账没有该 SKU 的行（无记录 ≠ 可售量为 0）");
  }

  private SellablePage fetch(String skuCode, String bearer) {
    try {
      var uriBuilder =
          org.springframework.web.util.UriComponentsBuilder.fromPath(SELLABLE_SKUS_PATH)
              .queryParam("keyword", skuCode)
              .queryParam("page", 0)
              .queryParam("size", MAX_SKUS_PER_CALL);
      SellablePage page =
          client.get()
              .uri(uriBuilder.build().toUri())
              .header("Authorization", bearer)
              .retrieve()
              .body(SellablePage.class);
      if (page == null || page.rows() == null) {
        throw unavailable("warehouse 服务没有返回可售量");
      }
      return page;
    } catch (RestClientResponseException e) {
      int code = e.getStatusCode().value();
      if (code == 401 || code == 403) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "当前身份无权读取库存可售量，备货可用性暂时核不了", e);
      }
      throw unavailable("warehouse 服务返回 " + code + "：可售量读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      // 🔴 绝不降级成「可售量 = 0」或「全部可售」：读不到就报读不到。
      throw unavailable("warehouse 服务不可达，读不到可售量");
    }
  }

  /** 上游不可用一律 503，理由写明「读不到」而不是「没有货」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        detail
            + "：读不到自营可售量（7.2.2.1 口径）——它的唯一来源是 warehouse 的授权只读接口"
            + "（跨库视图与跨服务 SQL 均被 AGENTS.md 禁止），本服务不会凭空报一个可售量");
  }
}
