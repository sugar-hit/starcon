package com.starcon.channel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
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
 * 10.4.2.2 备货预报的<b>在途只读端口</b> —— 头程在途量的<b>唯一来源</b>。
 *
 * <p>蓝图 §10.4.2.2 的输入句第二样是「库存与在途数据」。在途腿的真值在
 * <b>warehouse 服务的库</b>里（V63 inventory_transfer / 7.5.1.2 在途库存，
 * 头程发运单 10.4.1.1 在 V80），AGENTS.md 禁止跨服务 SQL / 跨库 FK / 共享视图，
 * 所以本端口<b>只经 warehouse 的授权只读端点</b>读：
 * {@code GET /warehouse/inventory/in-transit}。
 *
 * <p>🔴 只取 {@code transitKind=FIRST_MILE}（头程）：备货预报要补的是
 * <b>正在路上的头程货</b>，调拨腿 / 采购腿 / FBA 出库腿不是同一个口径，
 * 混在一起会把「还没到平台的货」和「已经在平台卖掉的货」当成同一件事。
 *
 * <p>🔴 <b>「查不到」与「确实没有在途」必须分得开</b>：
 *
 * <ul>
 *   <li>上游<b>不可达</b> → 抛 503，<b>绝不</b>返回「在途 = 0」。
 *   <li>上游<b>正常返回、但没有这个 SKU 的行</b> → {@code inTransitKnown=false}，
 *       {@code inTransitQuantity=null}。<b>不是 0</b>。
 * </ul>
 *
 * <p>⚠️ 口径说明：在途腿的 {@code toWarehouseId} 指的是 <b>warehouse 服务的仓</b>，
 * 与本模块的「目的仓」（10.4.2.1 的 {@code warehouse_account}，海外仓/平台仓账户）
 * <b>不是同一套编码</b>，两者之间没有跨服务映射表。因此本端口只能给出
 * <b>按 SKU 汇总的头程在途量</b>，<b>不能</b>声称「其中多少是去这个目的仓的」。
 * 这一点在接口返回里如实标注，不拿一个假的分仓口径糊过去。
 *
 * <p>🔴 <b>租户不由本端口传递租户号</b>：转发的是调用方自己的 {@code Authorization} bearer。
 */
@Service
public class ForecastInTransitQueryPort {
  /** warehouse 的在途清单读出口。 */
  static final String IN_TRANSIT_PATH = "/warehouse/inventory/in-transit";

  /** 备货预报只关心头程在途。 */
  static final String TRANSIT_KIND = "FIRST_MILE";

  /** 单次查询的 SKU 数上限。 */
  public static final int MAX_SKUS_PER_CALL = 20;

  /** warehouse 的一行在途腿（只声明本模块真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record InTransitRow(
      UUID id,
      String transitKind,
      UUID skuId,
      String skuCode,
      BigDecimal outstandingQuantity,
      BigDecimal settledQuantity,
      BigDecimal declaredQuantity,
      OffsetDateTime shippedAt,
      @Schema(nullable = true) OffsetDateTime expectedArriveAt,
      @Schema(nullable = true) OffsetDateTime receivedAt,
      String status,
      String carrierName,
      String trackingNo) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record InTransitPage(List<InTransitRow> rows, long total, int page, int size) {}

  /**
   * 一个 SKU 的在途结论。
   *
   * @param inTransitKnown 上游是否给出了这个 SKU 的在途腿。false 时
   *     {@code inTransitQuantity} 是 {@code null}（<b>不是 0</b>）。
   */
  public record InTransitAvailability(
      String skuCode,
      BigDecimal inTransitQuantity,
      boolean inTransitKnown,
      String note) {}

  private final RestClient client;

  public ForecastInTransitQueryPort(@Value("${WAREHOUSE_URL:http://127.0.0.1:8084}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(8));
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  /**
   * 读一组 SKU 的头程在途量（逐 SKU 汇总所有未结在途腿）。
   *
   * @param bearer 调用方自己的 bearer（转发，warehouse 侧按成员关系解析租户）
   */
  public List<InTransitAvailability> outstanding(List<String> skuCodes, String bearer) {
    List<InTransitAvailability> out = new java.util.ArrayList<>();
    for (String skuCode : skuCodes) {
      out.add(lookup(skuCode, bearer));
    }
    return out;
  }

  /** 读一个 SKU 的头程在途量。 */
  public InTransitAvailability lookup(String skuCode, String bearer) {
    InTransitPage page = fetch(skuCode, bearer);
    BigDecimal sum = null;
    int legs = 0;
    for (InTransitRow row : page.rows()) {
      // ⚠️ 逐字相等才算命中：warehouse 的 keyword 是模糊匹配，
      //   「SKU-1」会模糊命中「SKU-10」，那会把别人的在途量算进这张预报单。
      if (row.skuCode() == null || !row.skuCode().equals(skuCode)) continue;
      if (row.outstandingQuantity() == null) continue;
      sum = sum == null ? row.outstandingQuantity() : sum.add(row.outstandingQuantity());
      legs++;
    }
    if (sum == null) {
      return new InTransitAvailability(
          skuCode, null, false, "上游在途清单没有该 SKU 的头程在途腿（无记录 ≠ 在途为 0）");
    }
    return new InTransitAvailability(
        skuCode, sum, true, "上游在途清单命中 " + legs + " 条头程在途腿");
  }

  private InTransitPage fetch(String skuCode, String bearer) {
    try {
      InTransitPage page =
          client.get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(IN_TRANSIT_PATH)
                          .queryParam("transitKind", TRANSIT_KIND)
                          .queryParam("keyword", skuCode)
                          .queryParam("page", 0)
                          .queryParam("size", MAX_SKUS_PER_CALL)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(InTransitPage.class);
      if (page == null || page.rows() == null) {
        throw unavailable("warehouse 服务没有返回在途清单");
      }
      return page;
    } catch (RestClientResponseException e) {
      int code = e.getStatusCode().value();
      if (code == 401 || code == 403) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "当前身份无权读取在途数据，备货可用性暂时核不了", e);
      }
      throw unavailable("warehouse 服务返回 " + code + "：在途数据读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      // 🔴 绝不降级成「在途 = 0」：读不到就报读不到。
      throw unavailable("warehouse 服务不可达，无法核对备货可用性");
    }
  }

  /** 上游不可用一律 503，理由写明「读不到」而不是「没有在途」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        detail
            + "：在途数据（7.5.1.2 在途库存 / 10.4.1.1 头程发运单）的唯一来源是 warehouse 的授权只读接口"
            + "（跨库视图与跨服务 SQL 均被 AGENTS.md 禁止），本服务不会凭空报一个在途量");
  }
}
