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
 * 4.2.1.1 刊登编辑器 的 <b>03 上游只读端口</b>：源商品主数据（3.1.2.2）与媒体库（3.4.1.1）。
 *
 * <p>🔴 AGENTS.md 的模块边界纪律禁止跨服务 SQL / FK / 共享视图，所以本端口<b>只经 catalog 的
 * 授权 HTTP API</b>读（{@code GET /catalog/skus/{id}} 与 {@code GET /catalog/media}），
 * 不直连对方库。与 {@link SkuCandidateQueryPort}（4.1.2.1 读 SKU 候选）、
 * {@link SellableQuantityQueryPort}（4.1.2.3 读 warehouse 可售量）逐字同构。
 *
 * <p><b>刻意只声明本模块用到的字段</b>：源商品只要身份 / 规格 / 属性 / 已挂素材 ——
 * 读得越少，上游加字段时本模块越不容易被撑爆。{@code @JsonIgnoreProperties} 让上游多返回的
 * 字段不至于炸掉本模块。
 *
 * <p>🔴 <b>租户不由本端口传递租户号</b>：转发的是<b>调用方自己的</b> {@code Authorization}
 * bearer，catalog 侧仍按已认证成员关系解析租户并强制 RLS。租户隔离因此是<b>两道</b>
 * （channel 侧 RLS + catalog 侧 RLS），而不是「本服务替对方决定租户」。
 *
 * <p>🔴 取不到就<b>显式失败</b>（503），绝不返回空集合冒充「这个租户一个素材都没有」——
 * 那会让运营以为素材库是空的（于是以为没图可配），而真相是「我们查不到」。
 */
@Service
public class EditorSourceQueryPort {

  /** 源商品详情（3.1.2.2 SKU 详情）。 */
  static final String SKU_PATH = "/catalog/skus";

  /** 媒体库素材列表（3.4.1.1）。 */
  static final String MEDIA_PATH = "/catalog/media";

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SkuSpec(String axis, String value) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SkuAttribute(
      String code, String name, List<String> effectiveValues, boolean variantAxis) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SkuMedia(String id, String title, String reference, String status) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SkuDetail(
      String id,
      String code,
      String name,
      String status,
      String spuCode,
      String spuName,
      String brand,
      String category,
      List<SkuSpec> specs,
      List<SkuAttribute> attributes,
      List<SkuMedia> media) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record MediaAsset(
      String id,
      String name,
      String mediaType,
      String storageKey,
      Integer widthPx,
      Integer heightPx,
      String integrityStatus) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record MediaPage(List<MediaAsset> items, long total, int page, int size) {}

  private final RestClient client;

  public EditorSourceQueryPort(@Value("${CATALOG_URL:http://127.0.0.1:8082}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(10));
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  /** 源商品详情：编辑器的头部与属性预填都从这里来。 */
  public SkuDetail sku(String skuId, String bearer) {
    UUID parsed = parseId(skuId);
    if (parsed == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "源商品 id 不是合法的 UUID");
    }
    try {
      SkuDetail result =
          client.get().uri(SKU_PATH + "/" + parsed).header("Authorization", bearer).retrieve()
              .body(SkuDetail.class);
      if (result == null) {
        throw unavailable("catalog 服务没有返回源商品");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "调用方无权读取源商品", e);
      }
      if (e.getStatusCode().value() == 404) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "源商品不存在", e);
      }
      throw unavailable("catalog 服务返回 " + e.getStatusCode().value() + "：源商品读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("catalog 服务不可达");
    }
  }

  /** 媒体库素材：图片集字段的素材来源。 */
  public List<MediaAsset> media(String keyword, String bearer) {
    try {
      MediaPage page =
          client
              .get()
              .uri(
                  uriBuilder -> {
                    var builder = uriBuilder.path(MEDIA_PATH);
                    if (keyword != null && !keyword.isBlank()) {
                      builder.queryParam("keyword", keyword.trim());
                    }
                    return builder.queryParam("page", 0).queryParam("size", 20).build();
                  })
              .header("Authorization", bearer)
              .retrieve()
              .body(MediaPage.class);
      if (page == null || page.items() == null) {
        throw unavailable("catalog 服务没有返回素材列表");
      }
      return page.items();
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "调用方无权读取素材库", e);
      }
      throw unavailable("catalog 服务返回 " + e.getStatusCode().value() + "：素材库读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("catalog 服务不可达");
    }
  }

  /** 解析上游的 id 字符串。畸形 id 直接拒（这里不是候选集，不需要"丢一条脏数据"的宽容）。 */
  static UUID parseId(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
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
