package com.starcon.channel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
 * 4.1.2.1 批量映射的<b>候选集来源</b>：03 商品 SKU 的跨服务只读端口。
 *
 * <p>🔴 AGENTS.md 的模块边界纪律禁止跨服务 SQL / FK / 共享视图，所以本端口<b>只经 catalog 的
 * 授权 HTTP API</b>读（{@code GET /catalog/skus}），不直连对方库。与 {@link CarrierProfileQueryPort}
 * （10.1.1.2 读承运商身份）、{@link PendingShipmentQueryPort}（10.3.2.1 读 trade）逐字同构。
 *
 * <p>三条匹配规则各自需要的上游形状：
 *
 * <ul>
 *   <li><b>SKU 编码匹配</b> → {@code q} 关键字（catalog 侧按 code / name / barcode ILIKE）；
 *   <li><b>条码匹配</b> → {@code barcode} 精确过滤（catalog 侧就是 {@code s.barcode = ?}）；
 *   <li><b>标题相似度推荐</b> → {@code q} 拉一批候选回来，本服务在 PG 里用 {@code pg_trgm
 *       similarity()} 真算分数。
 * </ul>
 *
 * <p><b>刻意只声明本模块用到的字段</b>：读得越少，上游加字段时本模块越不容易被撑爆
 * （与 {@link CarrierProfileQueryPort} 同款理由）。{@code @JsonIgnoreProperties} 让上游多返回的
 * 字段不至于炸掉本模块。
 *
 * <p>🔴 <b>租户不由本端口传递租户号</b>：转发的是<b>调用方自己的</b> {@code Authorization}
 * bearer，catalog 侧仍按已认证成员关系解析租户并强制 RLS。租户隔离因此是<b>两道</b>
 * （channel 侧 RLS + catalog 侧 RLS），而不是「本服务替对方决定租户」。
 *
 * <p>🔴 取不到就<b>显式失败</b>（503），绝不返回空集合冒充「这个租户一个 SKU 都没有」——
 * 那会让运营以为候选池是空的（于是以为没有可映射的商品），而真相是「我们查不到」。
 */
@Service
public class SkuCandidateQueryPort {

  /** 上游 SKU 列表页。3.1.2.1 的分页形状（catalog V1 sku 表的真实读出口）。 */
  static final String SKUS_PATH = "/catalog/skus";

  /**
   * 上游一页 SKU。
   *
   * <p>字段刻意收窄：本模块只要 {@code id / code / name / barcode / status} ——
   * 编码与名称用于候选展示快照，条码用于条码匹配，状态用于把已停用 SKU 标出来
   * （不藏起来：给运营一个已停用的 SKU 当候选，比不给候选更可解释）。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SkuPage(List<SkuRow> items, long total, int page, int size) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SkuRow(String id, String code, String name, String barcode, String status) {}

  private final RestClient client;

  public SkuCandidateQueryPort(@Value("${CATALOG_URL:http://127.0.0.1:8082}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    // 批量候选一次要拉几十条 SKU 列表，比单条读慢一截，但仍然必须有上限 ——
    // 没有上限时 catalog 一慢就把本服务的请求线程全占住。
    factory.setReadTimeout(Duration.ofSeconds(10));
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  /**
   * 按关键字拉一页候选 SKU（SKU 编码匹配与标题相似度共用这条）。
   *
   * @param keyword catalog 侧的关键字过滤（code / name / barcode 三个字段 ILIKE）
   * @param size 页大小；服务层给一个硬上限，不让调用方一次拖走整张 SKU 表
   */
  public SkuPage skusByKeyword(String keyword, int size, String bearer) {
    return fetch(
        uriBuilder ->
            uriBuilder
                .path(SKUS_PATH)
                .queryParam("q", keyword)
                .queryParam("page", 0)
                .queryParam("size", size)
                .build(),
        bearer);
  }

  /** 按条码精确拉候选（条码匹配规则）。catalog 侧就是 {@code s.barcode = ?}，不是模糊。 */
  public SkuPage skusByBarcode(String barcode, int size, String bearer) {
    return fetch(
        uriBuilder ->
            uriBuilder
                .path(SKUS_PATH)
                .queryParam("barcode", barcode)
                .queryParam("page", 0)
                .queryParam("size", size)
                .build(),
        bearer);
  }

  private SkuPage fetch(
      java.util.function.Function<org.springframework.web.util.UriBuilder, java.net.URI> uri,
      String bearer) {
    try {
      SkuPage result =
          client.get().uri(uri).header("Authorization", bearer).retrieve().body(SkuPage.class);
      if (result == null || result.items() == null) {
        throw unavailable("catalog 服务没有返回 SKU 列表");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "调用方无权读取上游 SKU", e);
      }
      throw unavailable("catalog 服务返回 " + e.getStatusCode().value() + "：SKU 候选读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("catalog 服务不可达");
    }
  }

  /** 解析上游的 id 字符串。畸形 id 丢弃而不是让整批失败 —— 一条脏数据不该毁掉整批推荐。 */
  static UUID parseId(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** 上游不可用一律 503，理由写明「读不到」而不是「没有数据」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }
}
