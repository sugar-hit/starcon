package com.starcon.trade;

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
 * 6.6.2.1 读取上游 3.5.2.1 BOM 版本的**候选清单**（跨服务端口）。
 *
 * <p>蓝图 6.6.2.1 的输入写死「试产申请（**BOM 版本**、目标数量）」，而 BOM 版本表在
 * <b>catalog 服务的库</b>里。AGENTS.md 模块边界纪律禁止跨服务 SQL / FK / 共享视图，
 * 所以本端口**只经 catalog 的授权 HTTP 接口**读，不直连对方库、不建跨库外键。
 *
 * <p>用的是 catalog V25 明确留给下游的 {@code GET /catalog/bom-versions}（分页 + 关键词 + 状态）。
 *
 * <p><b>为什么本端口只管「候选清单」，而版本详情与需求量走 {@link BomVersionPort}</b>：
 * {@link BomVersionPort} 是 6.4.1.1 交付的既有端口，本模块<b>直接复用</b>它读版本详情与
 * R7 需求量（**不复制一份算法** —— 两处各实现一遍 R7 必然漂移，而漂移的后果是
 * 「按不同口径领料」）。本端口只补它没有的那一块：**建单时要选的 BOM 版本从哪来**。
 *
 * <p>取不到就<b>显式失败</b>，绝不返回空列表冒充「catalog 没有 BOM 版本」。
 */
@Service
public class ProductionTrialBomPort {

  /** catalog 返回的 BOM 版本行（字段名逐字对齐 catalog 的 {@code BomVersionRow} 契约）。 */
  record VersionRow(
      UUID id,
      UUID bomId,
      String bomCode,
      UUID outputSkuId,
      String outputSkuCode,
      String outputSkuName,
      String versionNo,
      String name,
      String status,
      String statusLabel,
      long componentCount,
      boolean effective) {}

  /** catalog 返回的 BOM 版本分页（字段名逐字对齐 catalog 的 {@code BomVersionPage} 契约）。 */
  record VersionPage(List<VersionRow> items, int page, int size, long total, int totalPages) {}

  /**
   * catalog 返回的 BOM 版本详情（字段名逐字对齐 catalog 的 {@code BomVersionDetail} 契约）。
   *
   * <p>只取本模块要用的字段 —— <b>不在本服务复制 catalog 的 BOM 模型</b>。
   */
  record VersionDetail(
      UUID id,
      UUID bomId,
      String bomCode,
      String outputSkuCode,
      String outputSkuName,
      String versionNo,
      String name,
      String status,
      String statusLabel,
      long componentCount,
      boolean effective) {}

  /** catalog 的详情响应外壳。 */
  record DetailResponse(VersionDetail version) {}

  private final RestClient client;

  public ProductionTrialBomPort(@Value("${CATALOG_URL:http://127.0.0.1:8082}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(8));
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  /** BOM 版本候选（建单下拉）。 */
  public VersionPage candidates(String keyword, String status, int page, int size, String bearer) {
    try {
      VersionPage result =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path("/catalog/bom-versions")
                          .queryParamIfPresent(
                              "keyword",
                              java.util.Optional.ofNullable(
                                  keyword == null || keyword.isBlank() ? null : keyword.trim()))
                          .queryParamIfPresent(
                              "status",
                              java.util.Optional.ofNullable(
                                  status == null || status.isBlank() ? null : status.trim()))
                          .queryParam("page", page)
                          .queryParam("size", size)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(VersionPage.class);
      if (result == null || result.items() == null) {
        throw unavailable("商品中心没有返回 BOM 版本列表");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "当前身份无权读取 BOM 版本，无法选择试产用的 BOM 版本");
      }
      throw unavailable("商品中心返回 " + e.getStatusCode().value() + "，暂时无法读取 BOM 版本");
    } catch (RestClientException e) {
      throw unavailable("商品中心暂时不可达");
    }
  }

  /**
   * BOM 版本详情（**快照来源**）。
   *
   * <p>试产单要存 BOM 版本的<b>展示快照</b>（版本号 / BOM 编码 / 成品 SKU 编码与名称）——
   * catalog 之后改版或改名，这张试产单仍能显示「当时用的是哪一版」。
   */
  public VersionDetail detail(UUID versionId, String bearer) {
    try {
      DetailResponse response =
          client
              .get()
              .uri("/catalog/bom-versions/" + versionId)
              .header("Authorization", bearer)
              .retrieve()
              .body(DetailResponse.class);
      if (response == null || response.version() == null) {
        throw unavailable("商品中心没有返回这个 BOM 版本");
      }
      return response.version();
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 404) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "BOM 版本不存在或无权查看：" + versionId);
      }
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "当前身份无权读取该 BOM 版本，无法建立试产单");
      }
      throw unavailable("商品中心返回 " + e.getStatusCode().value() + "，暂时无法读取 BOM 版本");
    } catch (RestClientException e) {
      throw unavailable("商品中心暂时不可达");
    }
  }

  /** 未接入 / 不可达一律 503，并把「为什么」如实报出（不返回空列表冒充没有 BOM 版本）。 */
  private ResponseStatusException unavailable(String reason) {    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        reason
            + "：试产单必须挂在 catalog 已交付的 BOM 版本（3.5.2.1）上，"
            + "本服务不复制一份 BOM，也不凭空生成一个版本号");
  }
}
