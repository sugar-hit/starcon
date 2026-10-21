package com.starcon.channel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 4.2.3.2 合规预检 的 <b>03 / 04 上游只读端口</b>。
 *
 * <p>🔴 AGENTS.md 的模块边界纪律禁止跨服务 SQL / FK / 共享视图，所以本端口<b>只经 catalog 的
 * 授权 HTTP API</b>读，不直连对方库。与 {@link EditorSourceQueryPort}（4.2.1.1 读源商品与媒体库）、
 * {@link ListingTemplateSourcePort}（4.2.2.1 读文案库）逐字同构。
 *
 * <p><b>🔴 本端口<b>不新建</b> 4.2.1.1 的那个端口</b>：4.2.1.1 的 {@code EditorSourceQueryPort}
 * 是它自己的写入所有权范围内的东西，本模块自己起一个端口，读自己需要的几个上游形状。
 *
 * <p>🔴 <b>租户不由本端口传递租户号</b>：转发的是<b>调用方自己的</b> {@code Authorization}
 * bearer，catalog 侧仍按已认证成员关系解析租户并强制 RLS。租户隔离因此是<b>两道</b>
 * （channel 侧 RLS + catalog 侧 RLS），而不是「本服务替对方决定租户」。
 *
 * <p>🔴 <b>读不到就显式说读不到</b>：本端口的每个方法都返回 {@link Upstream}，
 * 里面带着 {@code available} 与原因，**绝不返回空集合冒充「上游一个数据都没有」** ——
 * 那个区别在合规预检里是生死攸关的：把「查不到品牌授权」当成「品牌已授权」，
 * 等于把一个侵权商品放行。所以「读不到」在上层一律变成该维度的 {@code UNKNOWN} 状态，
 * **不计入已判定维度的分母、也不算通过**。
 */
@Service
public class ListingComplianceSourcePort {

  /** 源商品详情（03 SKU 主数据）。 */
  static final String SKU_PATH = "/catalog/skus";

  /** 品牌目录（03 品牌）。 */
  static final String BRAND_PATH = "/catalog/brands";

  /** 平台类目映射（4.2.3.1，在 catalog 服务侧）。 */
  static final String CATEGORY_MAPPING_PATH = "/catalog/category-mappings";

  /** 媒体库（03 媒体库）。 */
  static final String MEDIA_PATH = "/catalog/media";

  /** 图片处理配方（03 图片处理，3.4.1.2）。 */
  static final String IMAGE_RECIPE_PATH = "/catalog/media/processing/recipes";

  /**
   * 一次上游读取的结果。
   *
   * @param available 上游是否读到了（false ⇒ 这一维度量不到）
   * @param reason 读不到的原因（人话，直接进扫描结果）
   * @param value 读到的值；{@code available=false} 时为 null
   */
  public record Upstream<T>(boolean available, String reason, T value) {
    public static <T> Upstream<T> ok(T value) {
      return new Upstream<>(true, "", value);
    }

    public static <T> Upstream<T> unavailable(String reason) {
      return new Upstream<>(false, reason, null);
    }

    /** 取值；读不到时返回 null（调用方必须先看 {@code available}）。 */
    public T orNull() {
      return value;
    }
  }

  // ---------------------------------------------------------------- 上游 DTO

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SkuDetail(
      String id,
      String code,
      String name,
      String status,
      String spuId,
      String spuCode,
      String spuName,
      String brand,
      String category,
      List<SkuMedia> media) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SkuMedia(String id, String title, String reference, String status) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record BrandRow(
      String id,
      String code,
      String name,
      String authorizationStatus,
      boolean enabled,
      boolean publishable,
      String blockCode,
      String blockLabel,
      String blockMessage,
      int authorizationCount,
      int validAuthorizationCount,
      String asOf) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record BrandPage(List<BrandRow> items, long total, int page, int size) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record BrandAuthorization(
      String id,
      String brandId,
      String certificateNo,
      String grantor,
      String validFrom,
      String validTo,
      String evidenceUri,
      String note,
      boolean enabled,
      boolean validOn,
      int daysToExpiry) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CategoryMappingRow(
      String id,
      String internalCategoryId,
      String internalCategoryCode,
      String internalCategoryName,
      String platformCode,
      String siteCode,
      String platformCategoryId,
      String platformCategoryCode,
      String platformCategoryName,
      String platformCategoryPath,
      boolean isDefault,
      boolean enabled,
      long requiredCount,
      long unmappedRequiredCount) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CategoryMappingPage(List<CategoryMappingRow> items, long total, int page, int size) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RequiredAttribute(
      String platformAttributeCode,
      String platformAttributeName,
      String requirement,
      String platformDataType,
      boolean multiple,
      List<String> allowedValues,
      String transform,
      String internalAttributeId,
      String internalAttributeCode,
      String internalAttributeName,
      boolean mapped) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CategoryResolveResult(
      String mappingId,
      String internalCategoryId,
      String platformCode,
      String siteCode,
      String platformCategoryPath,
      boolean isDefault,
      List<RequiredAttribute> requiredAttributes,
      long unmappedRequiredCount) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record MediaAsset(
      String id,
      String name,
      String mediaType,
      String purpose,
      String fileFormat,
      String mimeType,
      BigDecimal byteSize,
      BigDecimal widthPx,
      BigDecimal heightPx,
      String storageKey,
      String originalFilename,
      String altText,
      String integrityStatus,
      String integrityDetail,
      boolean enabled) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record MediaPage(List<MediaAsset> items, long total, int page, int size) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ImageRecipe(
      String id,
      String code,
      String name,
      String targetPlatform,
      String targetFormat,
      boolean removeBackground,
      boolean whiteBackground,
      String watermarkText,
      String watermarkPosition,
      int watermarkOpacity,
      BigDecimal targetWidthPx,
      BigDecimal targetHeightPx,
      String cropMode,
      boolean enabled,
      List<RecipeRatio> ratios) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RecipeRatio(String label, BigDecimal widthPx, BigDecimal heightPx, String cropMode) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AssetJobRow(
      String id,
      String recipeId,
      String recipeName,
      String recipeCode,
      String targetPlatform,
      String status,
      int assetCount,
      int succeededCount,
      int failedCount,
      String createdAt,
      String finishedAt) {}

  // ---------------------------------------------------------------- 端口实现

  private final RestClient client;

  public ListingComplianceSourcePort(
      @Value("${CATALOG_URL:http://127.0.0.1:8082}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(10));
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  /** 源商品：品牌名与关联素材的来源。 */
  public Upstream<SkuDetail> sku(String skuId, String bearer) {
    if (!isUuid(skuId)) {
      return Upstream.unavailable("源商品 id 不是合法的 UUID");
    }
    try {
      SkuDetail result =
          client.get().uri(SKU_PATH + "/" + skuId).header("Authorization", bearer).retrieve()
              .body(SkuDetail.class);
      if (result == null) {
        return Upstream.unavailable("catalog 服务没有返回源商品");
      }
      return Upstream.ok(result);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 404) {
        return Upstream.unavailable("源商品在 catalog 侧不存在");
      }
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        return Upstream.unavailable("调用方无权读取源商品");
      }
      return Upstream.unavailable("catalog 服务返回 " + e.getStatusCode().value() + "：源商品读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      return Upstream.unavailable("catalog 服务不可达");
    }
  }

  /** 品牌候选（按名称找租户品牌库里的那一个）。 */
  public Upstream<List<BrandRow>> brands(String keyword, String bearer) {
    try {
      BrandPage page =
          client
              .get()
              .uri(
                  uriBuilder -> {
                    var builder = uriBuilder.path(BRAND_PATH).queryParam("page", 0).queryParam("size", 20);
                    if (keyword != null && !keyword.isBlank()) {
                      builder.queryParam("keyword", keyword.trim());
                    }
                    return builder.build();
                  })
              .header("Authorization", bearer)
              .retrieve()
              .body(BrandPage.class);
      if (page == null || page.items() == null) {
        return Upstream.unavailable("catalog 服务没有返回品牌列表");
      }
      return Upstream.ok(page.items());
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        return Upstream.unavailable("调用方无权读取品牌库");
      }
      return Upstream.unavailable("catalog 服务返回 " + e.getStatusCode().value() + "：品牌库读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      return Upstream.unavailable("catalog 服务不可达");
    }
  }

  /** 单个品牌的授权凭证（03 品牌授权状态）。 */
  public Upstream<List<BrandAuthorization>> brandAuthorizations(String brandId, String bearer) {
    if (!isUuid(brandId)) {
      return Upstream.unavailable("品牌 id 不是合法的 UUID");
    }
    try {
      List<BrandAuthorization> result =
          client
              .get()
              .uri(BRAND_PATH + "/" + brandId + "/authorizations")
              .header("Authorization", bearer)
              .retrieve()
              .body(new ParameterizedTypeReference<List<BrandAuthorization>>() {});
      if (result == null) {
        return Upstream.unavailable("catalog 服务没有返回品牌授权凭证");
      }
      return Upstream.ok(result);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 404) {
        return Upstream.unavailable("品牌在 catalog 侧不存在");
      }
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        return Upstream.unavailable("调用方无权读取品牌授权凭证");
      }
      return Upstream.unavailable(
          "catalog 服务返回 " + e.getStatusCode().value() + "：品牌授权凭证读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      return Upstream.unavailable("catalog 服务不可达");
    }
  }

  /** 平台类目映射台账（4.2.3.1 已交付，在 catalog 服务侧）。 */
  public Upstream<List<CategoryMappingRow>> categoryMappings(
      String platformCode, String siteCode, String bearer) {
    try {
      CategoryMappingPage page =
          client
              .get()
              .uri(
                  uriBuilder -> {
                    var builder = uriBuilder.path(CATEGORY_MAPPING_PATH);
                    if (platformCode != null && !platformCode.isBlank()) {
                      builder.queryParam("platformCode", platformCode.trim());
                    }
                    if (siteCode != null && !siteCode.isBlank()) {
                      builder.queryParam("siteCode", siteCode.trim());
                    }
                    return builder.queryParam("page", 1).queryParam("size", 200).build();
                  })
              .header("Authorization", bearer)
              .retrieve()
              .body(CategoryMappingPage.class);
      if (page == null || page.items() == null) {
        return Upstream.unavailable("catalog 服务没有返回平台类目映射");
      }
      return Upstream.ok(page.items());
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        return Upstream.unavailable("调用方无权读取平台类目映射");
      }
      return Upstream.unavailable(
          "catalog 服务返回 " + e.getStatusCode().value() + "：平台类目映射读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      return Upstream.unavailable("catalog 服务不可达");
    }
  }

  /** 平台类目必填属性清单（4.2.3.1 的 resolve 出口）。 */
  public Upstream<CategoryResolveResult> categoryResolve(
      String internalCategoryId, String platformCode, String siteCode, String bearer) {
    if (!isUuid(internalCategoryId)) {
      return Upstream.unavailable("内部分类 id 不是合法的 UUID");
    }
    try {
      CategoryResolveResult result =
          client
              .get()
              .uri(
                  uriBuilder -> {
                    var builder =
                        uriBuilder
                            .path(CATEGORY_MAPPING_PATH + "/resolve")
                            .queryParam("internalCategoryId", internalCategoryId)
                            .queryParam("platformCode", platformCode)
                            .queryParam("siteCode", siteCode);
                    return builder.build();
                  })
              .header("Authorization", bearer)
              .retrieve()
              .body(CategoryResolveResult.class);
      if (result == null) {
        return Upstream.unavailable("catalog 服务没有返回必填属性清单");
      }
      return Upstream.ok(result);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 404) {
        return Upstream.unavailable("该内部分类在这个平台 / 站点下没有映射");
      }
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        return Upstream.unavailable("调用方无权读取必填属性清单");
      }
      return Upstream.unavailable(
          "catalog 服务返回 " + e.getStatusCode().value() + "：必填属性清单读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      return Upstream.unavailable("catalog 服务不可达");
    }
  }

  /** 媒体库素材（按存储键 / 名称检索）。 */
  public Upstream<List<MediaAsset>> media(String keyword, String bearer) {
    try {
      MediaPage page =
          client
              .get()
              .uri(
                  uriBuilder -> {
                    var builder = uriBuilder.path(MEDIA_PATH).queryParam("page", 0).queryParam("size", 50);
                    if (keyword != null && !keyword.isBlank()) {
                      builder.queryParam("keyword", keyword.trim());
                    }
                    return builder.build();
                  })
              .header("Authorization", bearer)
              .retrieve()
              .body(MediaPage.class);
      if (page == null || page.items() == null) {
        return Upstream.unavailable("catalog 服务没有返回素材列表");
      }
      return Upstream.ok(page.items());
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        return Upstream.unavailable("调用方无权读取素材库");
      }
      return Upstream.unavailable("catalog 服务返回 " + e.getStatusCode().value() + "：素材库读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      return Upstream.unavailable("catalog 服务不可达");
    }
  }

  /** 图片处理配方（目标平台图片规格：白底 / 水印 / 目标尺寸）。 */
  public Upstream<List<ImageRecipe>> imageRecipes(String bearer) {
    try {
      List<ImageRecipe> result =
          client
              .get()
              .uri(IMAGE_RECIPE_PATH)
              .header("Authorization", bearer)
              .retrieve()
              .body(new ParameterizedTypeReference<List<ImageRecipe>>() {});
      if (result == null) {
        return Upstream.unavailable("catalog 服务没有返回图片处理配方");
      }
      return Upstream.ok(result);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        return Upstream.unavailable("调用方无权读取图片处理配方");
      }
      return Upstream.unavailable(
          "catalog 服务返回 " + e.getStatusCode().value() + "：图片处理配方读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      return Upstream.unavailable("catalog 服务不可达");
    }
  }

  /** 某个素材的处理历史（判断它有没有被按白底 / 水印配方处理过）。 */
  public Upstream<List<AssetJobRow>> assetHistory(String assetId, String bearer) {
    if (!isUuid(assetId)) {
      return Upstream.unavailable("素材 id 不是合法的 UUID");
    }
    try {
      List<AssetJobRow> result =
          client
              .get()
              .uri(MEDIA_PATH + "/processing/assets/" + assetId + "/history")
              .header("Authorization", bearer)
              .retrieve()
              .body(new ParameterizedTypeReference<List<AssetJobRow>>() {});
      if (result == null) {
        return Upstream.unavailable("catalog 服务没有返回素材处理历史");
      }
      return Upstream.ok(result);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 404) {
        return Upstream.unavailable("该素材没有处理历史");
      }
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        return Upstream.unavailable("调用方无权读取素材处理历史");
      }
      return Upstream.unavailable(
          "catalog 服务返回 " + e.getStatusCode().value() + "：素材处理历史读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      return Upstream.unavailable("catalog 服务不可达");
    }
  }

  /** 401 / 403 时不该被当成「读不到」静默吞掉 —— 调用方要能看到权限问题。 */
  static ResponseStatusException forbidden(String message) {
    return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
  }

  private static boolean isUuid(String raw) {
    if (raw == null || raw.isBlank()) {
      return false;
    }
    try {
      UUID.fromString(raw.trim());
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }
}
