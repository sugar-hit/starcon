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
 * 16.2.2.2 认证与资质的<b>跨服务只读端口</b>：03 商品主数据（SKU）与 03 分类（类目树）。
 *
 * <p>🔴 AGENTS.md 的模块边界纪律禁止跨服务 SQL / FK / 共享视图，所以本端口<b>只经 catalog 的
 * 授权 HTTP API</b>读（{@code GET /catalog/skus}、{@code GET /catalog/skus/{id}}、
 * {@code GET /catalog/categories/trees}、{@code GET /catalog/categories/trees/{treeId}/nodes}），
 * 不直连对方库。与 {@link CompliancePrecheckSourcePort}（16.2.2.1）、{@link EditorSourceQueryPort}
 * （4.2.1.1）逐字同构。
 *
 * <p>🔴🔴 <b>本端口不抛异常，而是把「读不到」表达成一个状态</b> —— 与
 * {@link CompliancePrecheckSourcePort} 同一手法，理由完全一样：本模块的产出是
 * <b>认证档案 + 到期提醒 + 覆盖统计</b>，而这三样都建立在「SKU 与类目是真的」这个前提上。
 * 上游读不到时，正确答案是<b>显式报「读不到」</b>（服务层翻成 503 + 说明），
 * 而不是折成一份空列表 —— 空列表会被读成「这个租户没有 SKU / 没有类目要求」，
 * 而真相是「我们没查到」。这是这类统计最致命的一种撒谎。
 *
 * <p>🔴 <b>租户不由本端口传递租户号</b>：转发的是<b>调用方自己的</b> {@code Authorization}
 * bearer，catalog 侧仍按已认证成员关系解析租户并强制 RLS。租户隔离因此是<b>两道</b>
 * （channel 侧 RLS + catalog 侧 RLS），而不是「本服务替对方决定租户」。
 *
 * <p>🔴 <b>读得越少越不容易被上游加字段撑爆</b>：下面每个 record 都用
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} 且只声明本模块真用到的字段。
 */
@Service
public class ProductCertificationSourcePort {

  /** 03 商品主数据：SKU 列表。 */
  static final String SKU_PATH = "/catalog/skus";

  /** 03 商品主数据：SKU 详情。 */
  static final String SKU_DETAIL_PATH = "/catalog/skus";

  /** 03 分类：类目树列表。 */
  static final String CATEGORY_TREE_PATH = "/catalog/categories/trees";

  /** 03 分类：某棵树的节点。 */
  static final String CATEGORY_NODE_PATH = "/catalog/categories/trees";

  /** 一次候选检索最多取多少行。 */
  static final int CANDIDATE_SIZE = 50;

  // ================================================================ 结果形状

  /**
   * SKU 候选读出结果。
   *
   * @param available 上游是否读到了（false ⇒ 服务层翻成 503，**绝不**折成空列表）
   */
  public record SkuCandidates(
      boolean available, List<SkuRow> items, long total, int page, int size, String note) {}

  /** 一个 SKU（catalog 的 SKU 列表行）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SkuRow(
      String id,
      String code,
      String name,
      String category,
      String spuId,
      String spuCode,
      String spuName,
      String status) {}

  /** catalog 的 SKU 列表页。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private record SkuPage(List<SkuRow> items, long total, int page, int size) {}

  /** catalog 的 SKU 详情（只声明本模块用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private record SkuDetail(
      String id, String code, String name, String category, String spuId, String spuName, String status) {}

  /**
   * 类目树读出结果。
   *
   * @param available 上游是否读到了
   */
  public record CategoryTrees(boolean available, List<CategoryTreeRow> items, String note) {}

  /** 一棵类目树。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CategoryTreeRow(
      String id, String code, String name, String treeKind, String platformCode, boolean enabled) {}

  /**
   * 类目节点读出结果。
   *
   * @param available 上游是否读到了
   */
  public record CategoryNodes(boolean available, List<CategoryNodeRow> items, String note) {}

  /** 一个类目节点。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CategoryNodeRow(
      String id, String treeId, String code, String name, String path, String treeKind, boolean enabled) {}

  private final RestClient client;

  public ProductCertificationSourcePort(
      @Value("${CATALOG_URL:http://127.0.0.1:8082}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(10));
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  // ================================================================ SKU

  /** SKU 候选列表（检索用）。 */
  public SkuCandidates skus(String keyword, String category, int page, int size, String bearer) {
    try {
      var builder =
          org.springframework.web.util.UriComponentsBuilder.fromPath(SKU_PATH)
              .queryParam("page", page)
              .queryParam("size", size);
      String keywordValue = blankToNull(keyword);
      if (keywordValue != null) {
        builder.queryParam("q", keywordValue);
      }
      String categoryValue = blankToNull(category);
      if (categoryValue != null) {
        builder.queryParam("category", categoryValue);
      }
      java.net.URI uri = builder.build().encode().toUri();
      SkuPage result =
          client.get().uri(uri).header("Authorization", bearer).retrieve().body(SkuPage.class);
      if (result == null || result.items() == null) {
        return new SkuCandidates(false, List.of(), 0, page, size, "catalog 服务没有返回 SKU 列表");
      }
      return new SkuCandidates(true, result.items(), result.total(), page, size, "");
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        return new SkuCandidates(
            false, List.of(), 0, page, size, "调用方无权读取 SKU（catalog 拒绝）");
      }
      return new SkuCandidates(
          false, List.of(), 0, page, size, "catalog 服务返回 " + e.getStatusCode().value());
    } catch (org.springframework.web.client.ResourceAccessException e) {
      return new SkuCandidates(false, List.of(), 0, page, size, "catalog 服务不可达");
    }
  }

  /** 单个 SKU（登记要求 / 挂适用 SKU 时核对存在性）。 */
  public SkuRow sku(String skuId, String bearer) {
    UUID parsed = parseId(skuId);
    if (parsed == null) {
      return null;
    }
    try {
      SkuDetail result =
          client.get().uri(SKU_DETAIL_PATH + "/" + parsed).header("Authorization", bearer).retrieve()
              .body(SkuDetail.class);
      if (result == null) {
        return null;
      }
      return new SkuRow(
          result.id(),
          result.code(),
          result.name(),
          result.category(),
          result.spuId(),
          null,
          result.spuName(),
          result.status());
    } catch (RestClientResponseException e) {
      return null;
    } catch (org.springframework.web.client.ResourceAccessException e) {
      return null;
    }
  }

  // ================================================================ 类目

  /** 类目树列表。 */
  public CategoryTrees categoryTrees(String bearer) {
    try {
      List<CategoryTreeRow> result =
          client.get().uri(CATEGORY_TREE_PATH).header("Authorization", bearer).retrieve()
              .body(new org.springframework.core.ParameterizedTypeReference<List<CategoryTreeRow>>() {});
      if (result == null) {
        return new CategoryTrees(false, List.of(), "catalog 服务没有返回类目树列表");
      }
      return new CategoryTrees(true, result, "");
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        return new CategoryTrees(false, List.of(), "调用方无权读取类目树（catalog 拒绝）");
      }
      return new CategoryTrees(false, List.of(), "catalog 服务返回 " + e.getStatusCode().value());
    } catch (org.springframework.web.client.ResourceAccessException e) {
      return new CategoryTrees(false, List.of(), "catalog 服务不可达");
    }
  }

  /** 某棵树的类目节点。 */
  public CategoryNodes categoryNodes(String treeId, String bearer) {
    UUID parsed = parseId(treeId);
    if (parsed == null) {
      return new CategoryNodes(false, List.of(), "类目树 id 不是合法的 UUID");
    }
    try {
      List<CategoryNodeRow> result =
          client
              .get()
              .uri(CATEGORY_NODE_PATH + "/" + parsed + "/nodes")
              .header("Authorization", bearer)
              .retrieve()
              .body(new org.springframework.core.ParameterizedTypeReference<List<CategoryNodeRow>>() {});
      if (result == null) {
        return new CategoryNodes(false, List.of(), "catalog 服务没有返回类目节点");
      }
      return new CategoryNodes(true, result, "");
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        return new CategoryNodes(false, List.of(), "调用方无权读取类目节点（catalog 拒绝）");
      }
      return new CategoryNodes(false, List.of(), "catalog 服务返回 " + e.getStatusCode().value());
    } catch (org.springframework.web.client.ResourceAccessException e) {
      return new CategoryNodes(false, List.of(), "catalog 服务不可达");
    }
  }

  // ================================================================ 内部

  static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  static UUID parseId(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(raw.trim());
    } catch (RuntimeException e) {
      return null;
    }
  }
}
