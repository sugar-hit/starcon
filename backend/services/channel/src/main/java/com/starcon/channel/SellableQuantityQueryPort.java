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
 * 4.1.2.3 的<b>下游只读端口</b>：07 库存（warehouse）的组件可售量。
 *
 * <p>🔴 AGENTS.md 禁止跨服务 SQL / FK / 共享视图，所以「校验组件 SKU 在目标仓库可售」
 * 只能经 warehouse 的授权 HTTP API 读（{@code GET /warehouse/inventory/sellable/skus}）。
 * 与 {@link BundleVersionQueryPort}（读 catalog）逐字同构，只是方向相反：本端口读下游。
 *
 * <p><b>可售量口径不在本模块定义</b>：warehouse 侧的公式是「合格可售 + 待检 + 残次 +
 * 未上架/不可发货 + 批次冻结 = available」，且还要扣安全缓冲。本模块<b>原样采用</b>它算出的
 * {@code sellable}，<b>不自己重算一遍</b> —— 两个地方各算一次必然漂移，而漂移的方向
 * 一定是"这里说能卖、扣的时候不够"。
 *
 * <p>🔴 <b>「查不到」与「确实没有货」必须分得开</b>，这正是本端口最容易做丢的一处：
 *
 * <ul>
 *   <li>上游<b>不可达</b> → 抛 503。<b>绝不</b>返回「全部可售」——那会让运营把「我们查不到」
 *       读成「能卖」，而那正是会真的超卖的路径。</li>
 *   <li>上游<b>正常返回、但某个组件 SKU 没有行</b> → 该组件 {@code sellable=false}。
 *       这<b>不是</b>故障：查询成功了，结论就是「这个 SKU 在该仓库没有可售量」。</li>
 * </ul>
 *
 * <p>🔴 <b>租户不由本端口传递租户号</b>：转发的是调用方自己的 {@code Authorization} bearer，
 * warehouse 侧仍按已认证成员关系解析租户并强制 RLS。
 */
@Service
public class SellableQuantityQueryPort {

  /** warehouse 的分 SKU 可售量。 */
  static final String SELLABLE_SKUS_PATH = "/warehouse/inventory/sellable/skus";

  /** 一次校验的组件数上限：不给上限时，组件多的一版套装能把本服务的线程占住。 */
  static final int MAX_SKUS_PER_CALL = 50;

  /**
   * 上游的一行可售量。
   *
   * <p>字段刻意收窄：只要 {@code skuId} 与 {@code sellable}（已扣安全缓冲的最终可售量）。
   * 其余切片（在手 / 预占 / 各剔除片）本模块用不上，读得越少越不容易被上游加字段撑爆。
   */
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

  private final RestClient client;

  public SellableQuantityQueryPort(@Value("${WAREHOUSE_URL:http://127.0.0.1:8086}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(10));
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  /**
   * 在一个目标仓库里读一组组件 SKU 的可售量。
   *
   * @param warehouseId 目标仓库（蓝图的「目标仓库」）；为 null 时由 warehouse 按调用方可见范围取全量
   * @param skuIds 组件 SKU；为空直接返回空页（不是故障）
   * @throws ResponseStatusException 401（无权读）/ 503（warehouse 读不到）/ 400（一次太多）
   */
  public SellablePage sellable(UUID warehouseId, List<UUID> skuIds, String bearer) {
    if (skuIds == null || skuIds.isEmpty()) {
      return new SellablePage(List.of(), 0, 0, 0, "没有需要校验的组件");
    }
    if (skuIds.size() > MAX_SKUS_PER_CALL) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "一次最多校验 " + MAX_SKUS_PER_CALL + " 个组件");
    }

    String csv = skuIds.stream().map(UUID::toString).reduce((a, b) -> a + "," + b).orElse("");
    try {
      var uriBuilder =
          org.springframework.web.util.UriComponentsBuilder.fromPath(SELLABLE_SKUS_PATH)
              .queryParam("skuId", (Object[]) csv.split(","))
              .queryParam("size", MAX_SKUS_PER_CALL)
              .queryParam("page", 0);
      if (warehouseId != null) {
        uriBuilder.queryParam("warehouseId", warehouseId.toString());
      }
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
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "调用方无权读取库存可售量", e);
      }
      throw unavailable("warehouse 服务返回 " + code + "：可售量读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      // 🔴 绝不降级成「可售量 = 0」或「全部可售」：读不到就报读不到。
      throw unavailable("warehouse 服务不可达，无法校验组件可售性");
    }
  }

  /** 上游不可用一律 503，理由写明「读不到」而不是「没有货」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }
}
