package com.starcon.finance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 读取上游 **3.1.2.1 SKU 主数据 / 3.1.2.2 SKU 档案**事实的**跨服务只读端口**（catalog 服务，端口 8082）。
 *
 * <p>12.4.2.2 分摊里，它供两件事：
 *
 * <ul>
 *   <li><b>SKU → SPU / 类目的映射</b>：分摊到 SPU / 类目两个维度时，
 *       要知道某个 SKU 属于哪个 SPU、哪个类目 —— 这份映射只有 catalog 有；
 *   <li><b>重量 / 体积基数</b>：{@code sku_package} 的毛重与体积（catalog 拥有包装事实，
 *       见 V224 第���节的同一口径）。
 * </ul>
 *
 * <p>⚠️ <b>跨服务只走授权 HTTP API</b>：AGENTS.md 的模块边界纪律禁止跨服务 SQL / 跨库 FK / 共享视图。
 *
 * <p>⚠️ <b>租户不由本端口传递</b>：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * catalog 侧仍按已认证成员关系解析租户并强制 RLS。租户隔离是<b>两道</b>（finance 侧 RLS + catalog 侧 RLS）。
 *
 * <p>🔴 <b>包装重量 / 体积取不到就是取不到</b>：catalog 里没维护包装的 SKU，
 * 它的重量 / 体积就是<b>空</b>，本端口不编造、也不用 0 顶替 ——
 * 0 会被当成「这个 SKU 重量为零」，从而静默改变分摊分母。
 * 「未维护」与「真的是 0」是两回事。
 */
@Service
public class CatalogSkuPort {

  /** 上游只读形状：{@code GET /catalog/skus}（3.1.2.1 SKU 列表）。 */
  static final String SKUS_PATH = "/catalog/skus";

  /** 上游只读形状：{@code GET /catalog/skus/{id}}（3.1.2.2 SKU 档案详情）。 */
  static final String SKU_DETAIL_PATH = "/catalog/skus/";

  /** 上游分页上限。 */
  static final int PAGE_SIZE = 200;

  /** 上游 JSON 的 mapper。 */
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  /** 上游 SKU 列表一行（只声明本模块真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record CatalogSkuRow(
      UUID id,
      @JsonIgnoreProperties(ignoreUnknown = true) UUID spuId,
      @JsonIgnoreProperties(ignoreUnknown = true) String spuCode,
      @JsonIgnoreProperties(ignoreUnknown = true) String spuName,
      @JsonIgnoreProperties(ignoreUnknown = true) String category,
      @JsonIgnoreProperties(ignoreUnknown = true) String code,
      @JsonIgnoreProperties(ignoreUnknown = true) String name,
      @JsonIgnoreProperties(ignoreUnknown = true) String status) {}

  /** 上游 SKU 列表一页。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record CatalogSkuPage(List<CatalogSkuRow> items, int page, int size, long total) {}

  /** 上游 SKU 包装（重量 / 体积）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record CatalogSkuPackaging(
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal grossWeightG,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal volumeCm3) {}

  /** 上游 SKU 详情（只声明本模块真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record CatalogSkuDetail(
      UUID id,
      @JsonIgnoreProperties(ignoreUnknown = true) UUID spuId,
      @JsonIgnoreProperties(ignoreUnknown = true) String spuCode,
      @JsonIgnoreProperties(ignoreUnknown = true) String category,
      @JsonIgnoreProperties(ignoreUnknown = true) String code,
      @JsonIgnoreProperties(ignoreUnknown = true) String name,
      @JsonIgnoreProperties(ignoreUnknown = true) CatalogSkuPackaging packaging) {}

  private final RestClient client;

  public CatalogSkuPort(@Value("${CATALOG_URL:http://127.0.0.1:8082}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(15));
    this.client =
        RestClient.builder()
            .baseUrl(url)
            .requestFactory(factory)
            .messageConverters(java.util.List.of(new MappingJackson2HttpMessageConverter(JSON)))
            .build();
  }

  /**
   * 拉一页上游 SKU 列表（SKU → SPU / 类目的映射就是从这里来的）。
   *
   * @param bearer 调用方自己的会话（租户由 catalog 侧按已认证成员关系解析）
   */
  public CatalogSkuPage skus(int page, String bearer) {
    try {
      CatalogSkuPage result =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(SKUS_PATH)
                          .queryParam("page", Math.max(page, 0))
                          .queryParam("size", PAGE_SIZE)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(CatalogSkuPage.class);
      if (result == null || result.items() == null) {
        throw unavailable("catalog 服务没有返回 SKU 列表");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED,
            "调用方无权读取上游 SKU 主数据（需要 catalog 侧的 catalog.sku.read 权限）",
            e);
      }
      throw unavailable("catalog 服务返回 " + e.getStatusCode().value() + "：SKU 主数据读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("catalog 服务不可达：SKU 主数据读不到");
    }
  }

  /**
   * 拉一个 SKU 的档案（包装重量 / 体积在这里）。
   *
   * @param bearer 调用方自己的会话（租户由 catalog 侧按已认证成员关系解析）
   */
  public CatalogSkuDetail sku(UUID id, String bearer) {
    try {
      CatalogSkuDetail result =
          client
              .get()
              .uri(SKU_DETAIL_PATH + id)
              .header("Authorization", bearer)
              .retrieve()
              .body(CatalogSkuDetail.class);
      if (result == null) {
        throw unavailable("catalog 服务没有返回 SKU " + id + " 的档案");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED,
            "调用方无权读取上游 SKU 档案（需要 catalog 侧的 catalog.sku.read 权限）",
            e);
      }
      throw unavailable("catalog 服务返回 " + e.getStatusCode().value() + "：SKU 档案读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("catalog 服务不可达：SKU 档案读不到");
    }
  }

  /** 上游不可用一律 503，理由写明「读不到」而不是「没有数据」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }
}
