package com.starcon.trade;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.4.2.1 质检单读 3.7.2.1 物理安全属性的<b>跨服务只读端口</b>。
 *
 * <p>蓝图 06-purchase-and-supply.md 6.4.2.1 的关系句写死「上游 = 8 收货、<b>3.7.2 物理安全属性</b>」。
 * 物理与安全属性（电池 / 液体 / 危险品 / 磁性）在 <b>catalog 服务的库</b>里，
 * AGENTS.md 模块边界纪律禁止跨服务 SQL / FK / 共享视图，所以本端口<b>只经 catalog 的授权只读端点</b>读。
 *
 * <p>用法与 {@link QualityInspectionWarehousePort}、{@link PurchasePlanWarehousePort} 逐字同构 ——
 * 跨服务读一律走一个 port 类，取不到就<b>显式失败</b>。
 *
 * <p>用 3.7.2.1 已交付的形状：
 *
 * <ul>
 *   <li>{@code GET /catalog/physical-safety/skus/{skuId}} —— 单个 SKU 的物理安全档案。</li>
 * </ul>
 *
 * <p><b>为什么质检单要看它</b>：带电池 / 液体 / 危险品的 SKU 必须验<b>认证标识</b>，
 * 而认证标识是蓝图点名的五个检验项之一。所以本端口读到「这个 SKU 需要认证标识」时，
 * 质检单在完结时会拒掉「模板里没有认证标识检验项」的质检单 ——
 * 否则「危险品没验认证」这件事没有任何一道关口拦得住。
 *
 * <p><b>「查不到」与「挂了」要分开</b>：上游返回 404 ⇒ 该 SKU <b>没有安全属性档案</b>
 * （合法答案，状态记为 {@code NOT_COLLECTED}）；其它错误 / 连不上 ⇒ 一律 503。
 * 混成同一个答案的话，「这个 SKU 没采过安全属性」会被读成「catalog 挂了」。
 *
 * <p>租户<b>不</b>由本端口传递租户号：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * catalog 侧仍按已认证成员关系解析租户并强制 RLS。租户隔离是<b>两道</b>。
 */
@Service
public class QualityInspectionCatalogPort {

  /** catalog 3.7.2.1 的物理安全档案（只声明本模块真正用到的字段）。 */
  record SafetyProfileView(
      UUID skuId,
      String skuCode,
      String skuName,
      boolean safetyCollected,
      boolean containsBattery,
      boolean containsLiquid,
      boolean dangerousGoods,
      boolean isMagnetic,
      String dangerousSubstanceClass,
      List<String> activeFlags,
      List<String> requiredDeclarations) {}

  /** 端口专用的 ObjectMapper：忽略上游多出来的字段。 */
  private static final ObjectMapper PORT_JSON =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private final RestClient client;

  public QualityInspectionCatalogPort(@Value("${CATALOG_URL:http://127.0.0.1:8082}") String url) {
    var factory =
        new JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(5));
    var converter = new MappingJackson2HttpMessageConverter(PORT_JSON);
    this.client =
        RestClient.builder()
            .baseUrl(url)
            .requestFactory(factory)
            .messageConverters(java.util.List.of(converter))
            .build();
  }

  /**
   * 读一个 SKU 的物理安全档案。
   *
   * <p>返回 {@code null} = **该 SKU 没有安全属性档案**（上游 404）；
   * 上游不可达 / 报错一律 503，绝不返回 {@code null} 冒充「没档案」。
   */
  public SafetyProfileView findProfile(UUID skuId, String bearer) {
    if (skuId == null) {
      return null;
    }
    try {
      return client
          .get()
          .uri("/catalog/physical-safety/skus/" + skuId)
          .header("Authorization", bearer)
          .retrieve()
          .body(SafetyProfileView.class);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
      }
      if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
        return null;
      }
      if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "当前身份无权读取商品的安全属性，质检单暂时无法核对认证标识要求");
      }
      throw unavailable("商品服务返回 " + e.getStatusCode().value() + "，安全属性暂时核对不了");
    } catch (RestClientException e) {
      throw unavailable("商品服务暂时不可达");
    }
  }

  private ResponseStatusException unavailable(String reason) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        reason
            + "：物理安全属性（3.7.2.1）的唯一来源是商品服务的授权只读接口"
            + "（跨库视图与跨服务 SQL 均被 AGENTS.md 禁止），"
            + "本服务不会凭空编一份安全属性出来");
  }
}
