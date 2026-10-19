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
 * 4.1.2.3 套装组件映射的<b>上游真值端口</b>：catalog 3.5.1 套装版本的组件构成。
 *
 * <p>🔴 AGENTS.md 的模块边界纪律禁止跨服务 SQL / FK / 共享视图，所以本端口<b>只经 catalog 的
 * 授权 HTTP API</b>读（{@code GET /catalog/versions/{id}/components}），不直连对方库。与
 * {@link SkuCandidateQueryPort}（4.1.2.1 读 SKU 候选）、{@link CarrierProfileQueryPort} 逐字同构。
 *
 * <p><b>本端口为什么是必需的</b>：本模块存下的 {@code bundleVersionId} 与
 * {@code componentSkuId} 都是<b>逻辑 ID</b>，数据库层面表达不了「这个组件 SKU 是不是真的属于
 * 这一版套装」。没有这个校验，一个手滑的 uuid 就能被写进组件表，而 07 库存会照着它去扣一个
 * 与套装毫无关系的 SKU —— <b>扣得动，所以更危险</b>。组件构成的真值永远在 catalog，
 * 本端口负责在写入那一刻把它读出来核对。
 *
 * <p><b>刻意不复制构成快照</b>：本端口的读出结果只用于<b>校验</b>，不落进本服务的表。
 * 「当时这一版由哪些 SKU 组成」的冻结快照是 09 订单 {@code order_line_component} 的职责，
 * 在这里存一份就会有两个真相（V186 第一节）。
 *
 * <p>🔴 <b>租户不由本端口传递租户号</b>：转发的是<b>调用方自己的</b> {@code Authorization}
 * bearer，catalog 侧仍按已认证成员关系解析租户并强制 RLS。租户隔离因此是<b>两道</b>
 * （channel 侧 RLS + catalog 侧 RLS），而不是「本服务替对方决定租户」。
 *
 * <p>🔴 取不到就<b>显式失败</b>（503），绝不返回空列表冒充「这个套装版本没有组件」——
 * 那会让运营以为可以先建绑定再补组件，而真相是「我们查不到 catalog」。
 */
@Service
public class BundleVersionQueryPort {

  /** catalog 的套装版本组件清单（3.5.1 的读出口，09 订单冻结展开前读的就是它）。 */
  static final String VERSION_COMPONENTS_PATH = "/catalog/versions/{id}/components";

  /**
   * 上游的组件行。
   *
   * <p>字段刻意收窄：校验只需要 {@code skuId} 与 {@code quantity}；{@code skuCode} /
   * {@code skuName} 仅用于把「这个组件是哪支货」带进错误提示。{@code @JsonIgnoreProperties}
   * 让上游多返回的字段不至于炸掉本模块。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CatalogComponentRow(
      UUID id, UUID versionId, UUID skuId, String skuCode, String skuName, java.math.BigDecimal quantity,
      String role, String note) {}

  private final RestClient client;

  public BundleVersionQueryPort(@Value("${CATALOG_URL:http://127.0.0.1:8082}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(10));
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  /**
   * 读一个套装版本的组件构成。
   *
   * @param versionId catalog 3.5.1 的套装版本 id
   * @param bearer 调用方自己的 Authorization 头（原样转发，不替换租户）
   * @throws ResponseStatusException 401（无权读）/ 503（catalog 读不到）/ 404（版本不存在）
   */
  public List<CatalogComponentRow> components(UUID versionId, String bearer) {
    if (versionId == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须指定套装版本");
    }
    try {
      List<CatalogComponentRow> rows =
          client.get()
              .uri(VERSION_COMPONENTS_PATH, versionId.toString())
              .header("Authorization", bearer)
              .retrieve()
              .body(new org.springframework.core.ParameterizedTypeReference<List<CatalogComponentRow>>() {});
      if (rows == null) {
        throw unavailable("catalog 服务没有返回套装版本的组件清单");
      }
      return rows;
    } catch (RestClientResponseException e) {
      int code = e.getStatusCode().value();
      if (code == 401 || code == 403) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "调用方无权读取上游套装版本", e);
      }
      // 🔴 上游 404 如实透出「这一版不存在」，不降级成「没有组件」：
      // 「版本不存在」与「版本存在但一个组件都没有」在业务上完全不是一回事。
      if (code == 404) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "套装版本不存在于商品中心", e);
      }
      throw unavailable("catalog 服务返回 " + code + "：套装版本组件清单读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("catalog 服务不可达");
    }
  }

  /** 上游不可用一律 503，理由写明「读不到」而不是「没有数据」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }
}
