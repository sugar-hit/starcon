package com.starcon.channel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 16.2.2.1 合规预检的<b>跨服务只读端口</b>：03 商品主数据（源商品与属性）与 03 品牌（授权与刊登资格）。
 *
 * <p>🔴 AGENTS.md 的模块边界纪律禁止跨服务 SQL / FK / 共享视图，所以本端口<b>只经 catalog 的
 * 授权 HTTP API</b>读（{@code GET /catalog/skus/{id}}、{@code GET /catalog/brands}、
 * {@code GET /catalog/brands/{id}/authorizations}、{@code GET /catalog/brands/{id}/listing-eligibility}），
 * 不直连对方库。与 {@link EditorSourceQueryPort}（4.2.1.1）、{@link ListingTemplateSourcePort}（4.2.2.1）
 * 逐字同构。
 *
 * <p>🔴🔴 <b>本端口不抛异常，而是把「读不到」表达成一个状态</b> —— 与
 * {@link ListingTemplateSourcePort} 同一手法，理由是本模块的产出是<b>体检报告</b>：
 * 一条检查项读不到上游时，正确答案是「这一项<b>量不到</b>」（UNKNOWN + 归属模块 + 整改建议），
 * 而不是「整次体检失败」。把读不到折成 0 分是这类评分系统最致命的一种撒谎：
 * 界面会显示「内容合规 0 分」，而真相是「我们没查到」。
 *
 * <p>🔴 <b>租户不由本端口传递租户号</b>：转发的是<b>调用方自己的</b> {@code Authorization}
 * bearer，catalog 侧仍按已认证成员关系解析租户并强制 RLS。租户隔离因此是<b>两道</b>
 * （channel 侧 RLS + catalog 侧 RLS），而不是「本服务替对方决定租户」。
 *
 * <p>🔴 <b>读得越少越不容易被上游加字段撑爆</b>：下面每个 record 都用
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} 且只声明本模块真用到的字段。
 */
@Service
public class CompliancePrecheckSourcePort {

  /** 03 商品主数据：SKU 详情。 */
  static final String SKU_PATH = "/catalog/skus";

  /** 03 品牌：品牌列表（按名称 / 编码解析品牌 id）。 */
  static final String BRAND_PATH = "/catalog/brands";

  /** 03 品牌：品牌授权凭证列表。 */
  static final String BRAND_AUTHORIZATIONS_PATH = "/catalog/brands";

  /** 03 品牌：品牌刊登资格判定。 */
  static final String BRAND_ELIGIBILITY_PATH = "/catalog/brands";

  /** 一次品牌检索最多取多少行：品牌字典是租户级的，命中即止。 */
  static final int BRAND_SEARCH_SIZE = 20;

  // ================================================================ 结果形状

  /** 源商品的规格（3.1.2.2 SKU 详情里的 specs）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SourceSpec(String axis, String value) {}

  /** 源商品的属性（3.1.2.2 SKU 详情里的 attributes）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SourceAttribute(String code, String name, List<String> effectiveValues) {}

  /**
   * 源商品读出结果。
   *
   * @param available 上游是否读到了（false ⇒ 相关检查项记 UNKNOWN，**不记 0 分**）
   * @param skuId 源商品 id
   * @param brand 源商品的品牌名（去解析品牌 id）
   * @param specs 源商品规格
   * @param attributes 源商品属性
   * @param note 读不到 / 读到了什么的原因说明
   */
  public record SourceProduct(
      boolean available,
      String skuId,
      String brand,
      List<SourceSpec> specs,
      List<SourceAttribute> attributes,
      String note) {}

  /**
   * 品牌引用读出结果。
   *
   * @param available 是否解析到了品牌
   * @param brandId 品牌 id
   * @param name 品牌名
   * @param code 品牌编码
   * @param note 说明
   */
  public record BrandReference(boolean available, String brandId, String name, String code, String note) {}

  /**
   * 品牌授权读出结果。
   *
   * @param available 上游是否读到了
   * @param brandId 品牌 id
   * @param authorizations 授权凭证
   * @param note 说明
   */
  public record BrandAuthorizations(
      boolean available, String brandId, List<BrandAuthorization> authorizations, String note) {}

  /** 一条品牌授权凭证（3.2.1.2）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record BrandAuthorization(
      String id,
      String certificateNo,
      String grantor,
      String validFrom,
      String validTo,
      boolean enabled,
      boolean validOn) {}

  /**
   * 品牌刊登资格读出结果。
   *
   * @param available 上游是否读到了
   * @param brandId 品牌 id
   * @param publishable 是否允许刊登
   * @param blockCode 阻止刊登的原因码
   * @param blockMessage 阻止刊登的原因
   * @param validAuthorizationCount 当前有效的授权数
   * @param note 说明
   */
  public record BrandEligibility(
      boolean available,
      String brandId,
      boolean publishable,
      String blockCode,
      String blockMessage,
      int validAuthorizationCount,
      String note) {}

  private final RestClient client;

  public CompliancePrecheckSourcePort(@Value("${CATALOG_URL:http://127.0.0.1:8082}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(10));
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  // ================================================================ 源商品

  /** 源商品详情：品牌名 / 规格 / 属性。 */
  public SourceProduct sku(String skuId, String bearer) {
    UUID parsed = parseId(skuId);
    if (parsed == null) {
      return new SourceProduct(false, skuId, "", List.of(), List.of(), "源商品 id 不是合法的 UUID");
    }
    try {
      SkuDetail result =
          client.get().uri(SKU_PATH + "/" + parsed).header("Authorization", bearer).retrieve()
              .body(SkuDetail.class);
      if (result == null) {
        return new SourceProduct(false, skuId, "", List.of(), List.of(), "catalog 服务没有返回源商品");
      }
      return new SourceProduct(
          true,
          result.id(),
          result.brand() == null ? "" : result.brand(),
          result.specs() == null
              ? List.of()
              : result.specs().stream()
                  .map(spec -> new SourceSpec(spec.axis(), spec.value()))
                  .toList(),
          result.attributes() == null
              ? List.of()
              : result.attributes().stream()
                  .map(
                      attribute ->
                          new SourceAttribute(
                              attribute.code(),
                              attribute.name(),
                              attribute.effectiveValues() == null
                                  ? List.of()
                                  : attribute.effectiveValues()))
                  .toList(),
          "");
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 404) {
        return new SourceProduct(false, skuId, "", List.of(), List.of(), "源商品不存在");
      }
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        return new SourceProduct(
            false, skuId, "", List.of(), List.of(), "调用方无权读取源商品（catalog 拒绝）");
      }
      return new SourceProduct(
          false, skuId, "", List.of(), List.of(), "catalog 服务返回 " + e.getStatusCode().value());
    } catch (org.springframework.web.client.ResourceAccessException e) {
      return new SourceProduct(false, skuId, "", List.of(), List.of(), "catalog 服务不可达");
    }
  }

  // ================================================================ 品牌

  /**
   * 按名称 / 编码解析品牌引用。
   *
   * <p>🔴 源商品上带的是<b>品牌名</b>，不是品牌 id，所以这里用 catalog 的品牌列表
   * （{@code GET /catalog/brands?keyword=}）解析出 id —— 仍只经授权 HTTP API，不跨库读。
   */
  public BrandReference brand(String keyword, String bearer) {
    if (keyword == null || keyword.isBlank()) {
      return new BrandReference(false, null, "", "", "源商品没有品牌名");
    }
    String trimmed = keyword.trim();
    try {
      BrandPage page =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(BRAND_PATH)
                          .queryParam("keyword", trimmed)
                          .queryParam("page", 0)
                          .queryParam("size", BRAND_SEARCH_SIZE)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(BrandPage.class);
      if (page == null || page.items() == null) {
        return new BrandReference(false, null, trimmed, "", "catalog 服务没有返回品牌列表");
      }
      BrandRow exact = null;
      for (BrandRow row : page.items()) {
        if (trimmed.equalsIgnoreCase(row.name()) || trimmed.equalsIgnoreCase(row.code())) {
          exact = row;
          break;
        }
      }
      if (exact == null) {
        return new BrandReference(
            false, null, trimmed, "", "品牌字典里没有名为「" + trimmed + "」的品牌");
      }
      return new BrandReference(true, exact.id(), exact.name(), exact.code(), "");
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        return new BrandReference(false, null, trimmed, "", "调用方无权读取品牌字典（catalog 拒绝）");
      }
      return new BrandReference(
          false, null, trimmed, "", "catalog 服务返回 " + e.getStatusCode().value());
    } catch (org.springframework.web.client.ResourceAccessException e) {
      return new BrandReference(false, null, trimmed, "", "catalog 服务不可达");
    }
  }

  /** 品牌授权凭证：有没有在有效期内的授权。 */
  public BrandAuthorizations authorizations(String brandId, String bearer) {
    UUID parsed = parseId(brandId);
    if (parsed == null) {
      return new BrandAuthorizations(false, brandId, List.of(), "品牌 id 不是合法的 UUID");
    }
    try {
      List<BrandAuthorization> result =
          client
              .get()
              .uri(BRAND_AUTHORIZATIONS_PATH + "/" + parsed + "/authorizations")
              .header("Authorization", bearer)
              .retrieve()
              .body(new org.springframework.core.ParameterizedTypeReference<List<BrandAuthorization>>() {});
      if (result == null) {
        return new BrandAuthorizations(false, brandId, List.of(), "catalog 服务没有返回授权列表");
      }
      return new BrandAuthorizations(true, brandId, result, "");
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 404) {
        return new BrandAuthorizations(false, brandId, List.of(), "品牌不存在");
      }
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        return new BrandAuthorizations(false, brandId, List.of(), "调用方无权读取品牌授权（catalog 拒绝）");
      }
      return new BrandAuthorizations(
          false, brandId, List.of(), "catalog 服务返回 " + e.getStatusCode().value());
    } catch (org.springframework.web.client.ResourceAccessException e) {
      return new BrandAuthorizations(false, brandId, List.of(), "catalog 服务不可达");
    }
  }

  /** 品牌刊登资格：catalog 侧算好的判定（3.2.1.2）。 */
  public BrandEligibility eligibility(String brandId, String bearer) {
    UUID parsed = parseId(brandId);
    if (parsed == null) {
      return new BrandEligibility(false, brandId, false, "", "品牌 id 不是合法的 UUID", 0, "");
    }
    try {
      Eligibility result =
          client
              .get()
              .uri(BRAND_ELIGIBILITY_PATH + "/" + parsed + "/listing-eligibility")
              .header("Authorization", bearer)
              .retrieve()
              .body(Eligibility.class);
      if (result == null) {
        return new BrandEligibility(false, brandId, false, "", "", 0, "catalog 服务没有返回刊登判定");
      }
      return new BrandEligibility(
          true,
          brandId,
          result.publishable(),
          result.blockCode() == null ? "" : result.blockCode(),
          result.blockMessage() == null ? "" : result.blockMessage(),
          result.validAuthorizationCount(),
          "");
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 404) {
        return new BrandEligibility(false, brandId, false, "", "", 0, "品牌不存在");
      }
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        return new BrandEligibility(false, brandId, false, "", "", 0, "调用方无权读取品牌刊登判定（catalog 拒绝）");
      }
      return new BrandEligibility(
          false, brandId, false, "", "", 0, "catalog 服务返回 " + e.getStatusCode().value());
    } catch (org.springframework.web.client.ResourceAccessException e) {
      return new BrandEligibility(false, brandId, false, "", "", 0, "catalog 服务不可达");
    }
  }

  // ================================================================ 内部形状

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record SkuSpec(String axis, String value) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record SkuAttribute(String code, String name, List<String> effectiveValues) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record SkuDetail(
      String id,
      String name,
      String status,
      String brand,
      List<SkuSpec> specs,
      List<SkuAttribute> attributes) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record BrandRow(String id, String code, String name) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record BrandPage(List<BrandRow> items, long total, int page, int size) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Eligibility(
      String brandId,
      boolean publishable,
      String blockCode,
      String blockMessage,
      int validAuthorizationCount) {}

  /** 解析上游的 id 字符串。畸形 id 直接拒（这里不是候选集，不需要「丢一条脏数据」的宽容）。 */
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
}
