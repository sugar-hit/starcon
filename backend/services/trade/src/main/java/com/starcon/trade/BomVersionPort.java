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
 * 6.4.1.1 读取上游 3.5.2.1 BOM 的**跨服务端口**。
 *
 * <p>蓝图关系句写死「上游 = 3.5.2.1 BOM」，而 BOM 表在 **catalog 服务的库**里。
 * AGENTS.md 模块边界纪律禁止跨服务 SQL / FK / 共享视图，所以本端口
 * **只经 catalog 的授权 HTTP 接口**读，不直连对方库、不建跨库外键。
 *
 * <p>用的是 catalog V25 文件头第六节**明确留给下游**的那两个接口：
 *
 * <ul>
 *   <li>{@code GET /catalog/bom-versions/{id}/requirement?outputQuantity=} ——
 *       规则 R7 的单层需求量，返回 {@code unitQuantity} / {@code scrapRate} /
 *       {@code requiredQuantity} 三元组；
 *   <li>{@code GET /catalog/bom-versions/{id}} ——
 *       取回版本本身（bomId / versionNo / 成品 SKU / 状态）。
 * </ul>
 *
 * <p><b>R6 / R7 语义逐字沿用，不重新定义</b>：损耗率是百分数（5 = 5%），
 * 需求量 = 单位用量 × 产量 × (1 + 损耗率/100)、6 位小数 HALF_UP、**不取整**。
 * 本端口原样搬运 catalog 返回的三元组，不在本服务重算 ——
 * 两处各实现一遍 R7 必然漂移，而漂移的后果是「按不同口径领料」。
 *
 * <p>取不到就**显式失败**，绝不返回空组件列表冒充「这个 BOM 没有组件」：
 * 那会让加工单凭空生成一张零组件的单子，而这恰恰被 DB 触发器
 * {@code guard_processing_order_released} 之外的路径漏过去。
 */
@Service
public class BomVersionPort {

  /** catalog 返回的单层需求量（字段名逐字对齐 catalog 的 BomRequirement 契约）。 */
  record RequirementLine(
      UUID skuId, String skuCode, BigDecimal unitQuantity, BigDecimal scrapRate,
      BigDecimal requiredQuantity) {}

  /** catalog 返回的 BOM 版本（字段名逐字对齐 catalog 的 BomVersionView 契约）。 */
  record VersionView(
      UUID id,
      UUID bomId,
      String bomCode,
      String versionNo,
      UUID outputSkuId,
      String outputSkuCode,
      String status) {}

  record Requirement(
      UUID bomId,
      UUID versionId,
      String versionNo,
      UUID outputSkuId,
      String outputSkuCode,
      BigDecimal outputQuantity,
      List<RequirementLine> lines,
      boolean singleLevel) {}

  private final RestClient client;

  public BomVersionPort(@Value("${CATALOG_URL:http://127.0.0.1:8082}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(8));
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  /** 取一个 BOM 版本的档案（用于核对「必须已发布」与成品 SKU）。 */
  public VersionView version(UUID versionId, String bearer) {
    try {
      VersionView view =
          client
              .get()
              .uri("/catalog/bom-versions/" + versionId)
              .header("Authorization", bearer)
              .retrieve()
              .body(VersionView.class);
      if (view == null) {
        throw unavailable("商品中心没有返回这个 BOM 版本");
      }
      return view;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 404) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND, "BOM 版本不存在或无权查看：" + versionId);
      }
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "当前身份无权读取该 BOM 版本，加工单无法绑定 BOM");
      }
      throw unavailable("商品中心返回 " + e.getStatusCode().value() + "，加工单暂时无法读取 BOM");
    } catch (RestClientException e) {
      throw unavailable("商品中心暂时不可达");
    }
  }

  /**
   * 取单层需求量（R7）。这是加工单组件领用记录的**唯一来源**。
   *
   * <p>结果原样返回 catalog 算好的三个数，本服务不重算、不取整。
   */
  public Requirement requirement(UUID versionId, BigDecimal outputQuantity, String bearer) {
    try {
      Requirement requirement =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path("/catalog/bom-versions/{id}/requirement")
                          .queryParam("outputQuantity", outputQuantity.toPlainString())
                          .build(versionId))
              .header("Authorization", bearer)
              .retrieve()
              .body(Requirement.class);
      if (requirement == null || requirement.lines() == null) {
        throw unavailable("商品中心没有返回组件需求量");
      }
      return requirement;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 404) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND, "BOM 版本不存在或无权查看：" + versionId);
      }
      throw unavailable("商品中心返回 " + e.getStatusCode().value() + "，加工单暂时无法读取组件需求量");
    } catch (RestClientException e) {
      throw unavailable("商品中心暂时不可达");
    }
  }

  /** 未接入 / 不可达一律 503，并把「为什么」如实报出（不返回空集合冒充没有组件）。 */
  private ResponseStatusException unavailable(String reason) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        reason
            + "：加工单的组件领用量必须由商品中心的 BOM 版本算出（3.5.2.1 已交付，"
            + "规则 R6/R7），本服务不复制一份算法，也不会凭空生成组件行");
  }
}
