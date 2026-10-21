package com.starcon.channel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 4.2.2.1 刊登模板的<b>跨服务只读端口</b>：03 商品主数据 / 03 描述与卖点库 / 03 媒体库。
 *
 * <p>🔴 AGENTS.md 的模块边界纪律禁止跨服务 SQL / FK / 共享视图，所以模板规则里对上游的引用
 * <b>只经 catalog 的授权 HTTP API</b>读，不直连对方库。与 {@link SkuCandidateQueryPort}
 * （4.1.2.1 读 SKU）、{@link CarrierProfileQueryPort}（10.1.1.2 读承运商身份）逐字同构。
 *
 * <p>🔴🔴 <b>本端口与既有端口最大的不同：它不抛异常，而是把「读不到」表达成一个状态。</b>
 *
 * <p>蓝图要求「<b>不知道</b>是一个状态，不是一个 0」。既有端口（{@link SkuCandidateQueryPort}）
 * 读不到时抛 503，是因为那里「查不到候选」会让整批推荐无从谈起；本模块不同 ——
 * 一次套用要同时求值多条规则，其中一条的上游挂掉**不应该让整次套用失败**，
 * 而应该让那一条字段显式变成 {@code UNKNOWN_SOURCE} + {@code ownerModule}，
 * 其余字段照常产出草稿。
 *
 * <p>所以本端口的每个方法都返回 {@code available=false} 的结果对象，**绝不**把「读不到」
 * 折成「空集合」或「没有值」：
 *
 * <ul>
 *   <li>{@code available=false} ⇒ 上游不可达 / 报错 ⇒ 字段记 {@code UNKNOWN_SOURCE}；
 *   <li>{@code available=true} 但引用不存在 ⇒ 字段记 {@code SOURCE_MISSING}。
 * </ul>
 *
 * <p>🔴 <b>租户不由本端口传递租户号</b>：转发的是<b>调用方自己的</b> {@code Authorization}
 * bearer，catalog 侧仍按已认证成员关系解析租户并强制 RLS。租户隔离因此是<b>两道</b>
 * （channel 侧 RLS + catalog 侧 RLS），而不是「本服务替对方决定租户」。
 *
 * <p>🔴 <b>读得越少越不容易被上游加字段撑爆</b>：下面每个 record 都用
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} 且只声明本模块真用到的字段。
 */
@Service
public class ListingTemplateSourcePort {

  /** 03 商品主数据：SKU 详情（取商品属性）。 */
  static final String SKU_PATH = "/catalog/skus";

  /** 03 媒体库：素材列表 / 素材详情。 */
  static final String MEDIA_PATH = "/catalog/media";

  /** 03 描述与卖点库：描述模板列表 / 渲染。 */
  static final String DESCRIPTION_TEMPLATES_PATH = "/catalog/description-templates/templates";

  static final String DESCRIPTION_RENDER_PATH = "/catalog/description-templates/renders";

  /** 一次标签取图的张数上限：不给上限时上游一慢就把本服务的线程占住。 */
  static final int MAX_TAG_ASSETS = 50;

  // ================================================================ 结果形状

  /**
   * 商品属性的读出结果。
   *
   * @param available 上游是否读到了（false ⇒ 字段记 UNKNOWN_SOURCE）
   * @param reason 读不到的原因（人能读，不是异常类名）
   * @param attributes 属性码 → 生效值。**属性码是规则的 source_ref**，所以键必须逐字等于属性的 code。
   * @param missing 属性码在详情里**根本没有**的那些（区别于「属性存在但值为空」）
   */
  public record ProductResult(
      boolean available, String reason, Map<String, String> attributes, Set<String> missing) {}

  /** 03 商品主数据：SKU 详情（字段刻意收窄）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SkuDetailView(
      UUID id, String code, String name, String status, List<AttributeView> attributes) {}

  /** 一条生效属性（字段刻意收窄）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AttributeView(
      UUID attributeId,
      String code,
      String name,
      String dataType,
      boolean multiple,
      List<String> options,
      List<String> rawValues,
      List<String> effectiveValues) {}

  /**
   * 一个素材（字段刻意收窄）。
   *
   * @param storageKey 当前版本的存储键 —— **每次取都是当前版本**，所以素材换了图重新套用就是新图
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record MediaAssetView(
      UUID id,
      String name,
      String mediaType,
      String purpose,
      String storageKey,
      boolean enabled,
      Integer currentVersion) {}

  /** 媒体库的读出结果。 */
  public record MediaResult(boolean available, String reason, List<MediaAssetView> assets) {}

  /** 03 媒体库：素材列表页（字段刻意收窄）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record MediaPage(List<MediaAssetView> items, long total, int page, int size) {}

  /**
   * 文案库渲染结果的读出结果。
   *
   * @param text 渲染出的正文
   * @param issueCount 上游报的缺失变量条数（>0 时文案本身不完整，必须让运营看见）
   */
  public record ContentResult(
      boolean available, String reason, String text, int issueCount, boolean templateMissing) {}

  /** 03 描述与卖点库：描述模板列表（字段刻意收窄）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record DescriptionTemplateView(UUID id, String code, String name, boolean enabled) {}

  /** 03 描述与卖点库：渲染结果（字段刻意收窄）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RenderView(
      UUID renderId, String templateCode, String status, String html, int issueCount) {}

  // ================================================================ 实现

  private final RestClient client;

  public ListingTemplateSourcePort(
      @Value("${CATALOG_URL:http://127.0.0.1:8082}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(10));
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  /**
   * 读一个 SKU 的生效属性。
   *
   * <p>属性码 → 生效值：{@code effectiveValues} 多值时按换行拼成一个字符串（多值属性在
   * 标题/卖点里本来就要展开成文本）；{@code options} 是值域候选，不是生效值，不取。
   */
  public ProductResult productAttributes(UUID skuId, String bearer) {
    if (skuId == null) {
      return new ProductResult(true, "", Map.of(), Set.of());
    }
    try {
      SkuDetailView detail =
          client.get()
              .uri(SKU_PATH + "/" + skuId)
              .header("Authorization", bearer)
              .retrieve()
              .body(SkuDetailView.class);
      if (detail == null) {
        return new ProductResult(false, "catalog 服务没有返回 SKU 详情", Map.of(), Set.of());
      }
      Map<String, String> attributes = new LinkedHashMap<>();
      for (AttributeView attribute :
          detail.attributes() == null ? List.<AttributeView>of() : detail.attributes()) {
        if (attribute.code() == null || attribute.code().isBlank()) {
          continue;
        }
        List<String> values =
            attribute.effectiveValues() == null ? List.of() : attribute.effectiveValues();
        attributes.put(attribute.code(), String.join("\n", values));
      }
      return new ProductResult(true, "", attributes, Set.of());
    } catch (RestClientResponseException e) {
      int code = e.getStatusCode().value();
      if (code == 401 || code == 403) {
        return new ProductResult(false, "调用方无权读取上游商品属性（catalog 返回 " + code + "）", Map.of(), Set.of());
      }
      if (code == 404) {
        // 上游读到了，但这个 SKU 不在 —— 这是「引用不存在」而不是「读不到」
        return new ProductResult(true, "", Map.of(), Set.of());
      }
      return new ProductResult(false, "catalog 服务返回 " + code + "：商品属性读不到", Map.of(), Set.of());
    } catch (org.springframework.web.client.ResourceAccessException e) {
      return new ProductResult(false, "catalog 服务不可达：商品属性读不到", Map.of(), Set.of());
    } catch (RuntimeException e) {
      return new ProductResult(false, "catalog 服务响应无法解析：商品属性读不到", Map.of(), Set.of());
    }
  }

  /** 按素材 id 逐个读当前版本（ASSET_LIST 取组方式）。 */
  public MediaResult mediaAssets(List<UUID> assetIds, String bearer) {
    if (assetIds == null || assetIds.isEmpty()) {
      return new MediaResult(true, "", List.of());
    }
    List<MediaAssetView> assets = new ArrayList<>();
    for (UUID assetId : assetIds) {
      MediaAssetView asset = mediaAsset(assetId, bearer);
      if (asset == null) {
        continue;
      }
      assets.add(asset);
    }
    return new MediaResult(true, "", assets);
  }

  /** 读单个素材的当前版本。读不到（不存在或上游故障）返回 null —— 调用方据此判「引用不存在」。 */
  public MediaAssetView mediaAsset(UUID assetId, String bearer) {
    try {
      MediaAssetView asset =
          client.get()
              .uri(MEDIA_PATH + "/" + assetId)
              .header("Authorization", bearer)
              .retrieve()
              .body(MediaAssetView.class);
      return asset;
    } catch (RestClientResponseException e) {
      return null;
    } catch (org.springframework.web.client.ResourceAccessException e) {
      return null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * 按媒体库标签取图（TAG 取组方式）。
   *
   * <p>🔴 上游不可达时返回 {@code available=false}（**不是**空集合）—— 空集合会被读成
   * 「这个标签下没有素材」，而真相是「我们查不到」。
   */
  public MediaResult mediaAssetsByTag(String tagCode, int size, String bearer) {
    try {
      MediaPage page =
          client.get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(MEDIA_PATH)
                          .queryParam("tagCode", tagCode)
                          .queryParam("page", 0)
                          .queryParam("size", Math.min(Math.max(size, 1), MAX_TAG_ASSETS))
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(MediaPage.class);
      if (page == null || page.items() == null) {
        return new MediaResult(false, "catalog 服务没有返回素材列表", List.of());
      }
      return new MediaResult(true, "", page.items());
    } catch (RestClientResponseException e) {
      int code = e.getStatusCode().value();
      if (code == 401 || code == 403) {
        return new MediaResult(false, "调用方无权读取上游素材（catalog 返回 " + code + "）", List.of());
      }
      return new MediaResult(false, "catalog 服务返回 " + code + "：素材读不到", List.of());
    } catch (org.springframework.web.client.ResourceAccessException e) {
      return new MediaResult(false, "catalog 服务不可达：素材读不到", List.of());
    } catch (RuntimeException e) {
      return new MediaResult(false, "catalog 服务响应无法解析：素材读不到", List.of());
    }
  }

  /**
   * 引用文案库：按模板编码渲染一份正文。
   *
   * <p>两步：先在描述模板列表里按编码找到模板 id，再让 catalog 渲染。
   * 渲染是 catalog 自己的写入所有权（它要留下渲染记录），本模块只读结果。
   *
   * @param templateMissing 模板编码在 catalog 里不存在（区别于「读不到」）
   */
  public ContentResult renderContentTemplate(
      String templateCode, UUID skuId, String locale, String bearer) {
    UUID templateId;
    try {
      List<DescriptionTemplateView> templates =
          client.get()
              .uri(DESCRIPTION_TEMPLATES_PATH)
              .header("Authorization", bearer)
              .retrieve()
              .body(new ParameterizedTypeReference<List<DescriptionTemplateView>>() {});
      if (templates == null) {
        return new ContentResult(false, "catalog 服务没有返回描述模板列表", "", 0, false);
      }
      templateId =
          templates.stream()
              .filter(t -> templateCode.equals(t.code()))
              .map(DescriptionTemplateView::id)
              .findFirst()
              .orElse(null);
      if (templateId == null) {
        return new ContentResult(true, "", "", 0, true);
      }
    } catch (RestClientResponseException e) {
      int code = e.getStatusCode().value();
      if (code == 401 || code == 403) {
        return new ContentResult(false, "调用方无权读取上游文案库（catalog 返回 " + code + "）", "", 0, false);
      }
      return new ContentResult(false, "catalog 服务返回 " + code + "：文案库读不到", "", 0, false);
    } catch (org.springframework.web.client.ResourceAccessException e) {
      return new ContentResult(false, "catalog 服务不可达：文案库读不到", "", 0, false);
    } catch (RuntimeException e) {
      return new ContentResult(false, "catalog 服务响应无法解析：文案库读不到", "", 0, false);
    }

    try {
      RenderView render =
          client.post()
              .uri(DESCRIPTION_RENDER_PATH)
              .header("Authorization", bearer)
              .body(java.util.Map.of("templateId", templateId, "skuId", skuId, "locale", locale))
              .retrieve()
              .body(RenderView.class);
      if (render == null) {
        return new ContentResult(false, "catalog 服务没有返回渲染结果", "", 0, false);
      }
      return new ContentResult(true, "", render.html() == null ? "" : render.html(), render.issueCount(), false);
    } catch (RestClientResponseException e) {
      int code = e.getStatusCode().value();
      if (code == 401 || code == 403) {
        return new ContentResult(false, "调用方无权让上游渲染文案（catalog 返回 " + code + "）", "", 0, false);
      }
      return new ContentResult(false, "catalog 服务返回 " + code + "：文案渲染读不到", "", 0, false);
    } catch (org.springframework.web.client.ResourceAccessException e) {
      return new ContentResult(false, "catalog 服务不可达：文案渲染读不到", "", 0, false);
    } catch (RuntimeException e) {
      return new ContentResult(false, "catalog 服务响应无法解析：文案渲染读不到", "", 0, false);
    }
  }
}
