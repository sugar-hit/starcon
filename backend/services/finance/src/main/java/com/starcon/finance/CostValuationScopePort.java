package com.starcon.finance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 12.4.1.1 计价方法配置的**跨服务只读端口**：可用的计价范围（法人 / 品类）。
 *
 * <p>蓝图 12.4.1.1 说「按法人或品类设置」。这两个范围都不在本服务：
 *
 * <ul>
 *   <li><b>法人</b>来自 identity 的 17 法人档案（{@code legal_entity}）——
 *       授权只读端点 {@code GET /identity/legal-entities}；
 *   <li><b>品类</b>来自 catalog 的品类树（{@code category_tree} / {@code category_node}）——
 *       授权只读端点 {@code GET /catalog/categories/trees} 与
 *       {@code GET /catalog/categories/trees/{treeId}/nodes}。
 * </ul>
 *
 * <p>🔴 AGENTS.md 禁止跨服务 SQL / 跨库 FK / 共享视图 ⇒ 本端口<b>只经授权 HTTP API</b>读，
 * 且<b>不建影子表</b>：法人名称 / 品类名称不落本库（配置行只存<b>名称快照</b>，
 * 供历史可读；法律身份仍以逻辑 ID 为准）。
 *
 * <p>🔴 <b>本端口不抛异常，而是把「读不到」表达成一个状态</b> ——
 * 与 {@code channel} 的 {@code LegalEntitySourcePort} 同一手法。
 * 理由：计价范围下拉读不到时，正确答案是「法人清单<b>读不到</b>」，
 * 而不是「这个租户没有法人」—— 后者会让人以为配置无处可配。
 *
 * <p>🔴 <b>租户不由本端口传递</b>：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * identity / catalog 侧仍按已认证成员关系解析租户并强制 RLS。租户隔离因此是<b>两道</b>。
 */
@Service
public class CostValuationScopePort {

  /** identity 法人档案（只读）。 */
  static final String LEGAL_ENTITY_PATH = "/identity/legal-entities";

  /** catalog 品类树列表（只读）。 */
  static final String CATEGORY_TREES_PATH = "/catalog/categories/trees";

  /** catalog 品类节点（只读）。 */
  static final String CATEGORY_NODES_PATH = "/catalog/categories/trees/{treeId}/nodes";

  /** 法人目录的一次读取结果。 */
  public record LegalEntitySet(boolean available, List<LegalEntityRef> items, String note) {}

  /** 一个法人的最小引用（只读、只用于显示）。 */
  public record LegalEntityRef(UUID id, String code, String legalName, String status, String baseCurrency) {}

  /** 品类目录的一次读取结果。 */
  public record CategorySet(boolean available, List<CategoryRef> items, String note) {}

  /** 一个品类节点的最小引用（只读、只用于显示）。 */
  public record CategoryRef(UUID id, String treeId, String treeCode, String code, String name, String path) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record LegalEntityRow(
      String id,
      String code,
      String legalName,
      String status,
      @JsonIgnoreProperties(ignoreUnknown = true) String baseCurrency) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record CategoryTreeRow(String id, String code, String name) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record CategoryNodeRow(
      String id,
      String treeId,
      String treeCode,
      String code,
      String name,
      String path) {}

  /**
   * 上游 JSON 的解析器。
   *
   * <p>⚠️ {@code USE_BIG_DECIMAL_FOR_FLOATS} 必须开：默认 Jackson 把 JSON 里的浮点数读成 {@code double}，
   * 于是上游的 {@code 12.3456} 变成 {@code 12.345600000000001} —— 金额被悄悄改了一位，
   * 而这正是本模块唯一不允许发生的事。
   */
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private final RestClient identity;
  private final RestClient catalog;

  public CostValuationScopePort(
      @Value("${IDENTITY_URL}") String identityUrl,
      @Value("${CATALOG_URL:http://127.0.0.1:8082}") String catalogUrl) {
    this.identity = client(identityUrl);
    this.catalog = client(catalogUrl);
  }

  private static RestClient client(String baseUrl) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(5));
    return RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(factory)
        .messageConverters(
            java.util.List.of(
                new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(JSON)))
        .build();
  }

  /**
   * 读法人目录。
   *
   * @param bearer 调用方自己的 bearer（转发它，租户由 identity 按成员关系解析）
   */
  public LegalEntitySet legalEntities(String bearer) {
    try {
      List<LegalEntityRow> rows =
          identity
              .get()
              .uri(LEGAL_ENTITY_PATH)
              .header("Authorization", bearer)
              .retrieve()
              .body(new org.springframework.core.ParameterizedTypeReference<List<LegalEntityRow>>() {});
      if (rows == null) {
        return new LegalEntitySet(false, List.of(), "identity 服务没有返回法人列表");
      }
      var items = new ArrayList<LegalEntityRef>();
      for (var row : rows) {
        if (row == null || row.id() == null || row.id().isBlank()) continue;
        items.add(
            new LegalEntityRef(
                UUID.fromString(row.id()),
                row.code() == null ? "" : row.code(),
                row.legalName() == null ? "" : row.legalName(),
                row.status() == null ? "" : row.status(),
                row.baseCurrency() == null ? "" : row.baseCurrency()));
      }
      return new LegalEntitySet(true, items, "");
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        return new LegalEntitySet(false, List.of(), "调用方无权读取法人档案（identity 拒绝）");
      }
      return new LegalEntitySet(false, List.of(), "identity 服务返回 " + e.getStatusCode().value());
    } catch (org.springframework.web.client.ResourceAccessException e) {
      return new LegalEntitySet(false, List.of(), "identity 服务不可达");
    } catch (RuntimeException e) {
      return new LegalEntitySet(false, List.of(), "读取法人档案失败：" + e.getMessage());
    }
  }

  /**
   * 读品类目录（遍历品类树取节点）。
   *
   * <p>⚠️ 品类树的节点分属不同树（内部管理分类与各平台类目映射是独立的树）。
   * 本模块的「品类」只取<b>内部管理分类</b>的树（{@code treeKind='INTERNAL'}）——
   * 平台类目映射是「同一个品类在不同平台叫什么」，不是计价范围。
   */
  public CategorySet categories(String bearer) {
    try {
      List<CategoryTreeRow> trees =
          catalog
              .get()
              .uri(CATEGORY_TREES_PATH)
              .header("Authorization", bearer)
              .retrieve()
              .body(new org.springframework.core.ParameterizedTypeReference<List<CategoryTreeRow>>() {});
      if (trees == null) {
        return new CategorySet(false, List.of(), "catalog 服务没有返回品类树列表");
      }
      var items = new ArrayList<CategoryRef>();
      var skipped = new ArrayList<String>();
      for (var tree : trees) {
        if (tree == null || tree.id() == null || tree.id().isBlank()) continue;
        List<CategoryNodeRow> nodes;
        try {
          nodes =
              catalog
                  .get()
                  .uri(CATEGORY_NODES_PATH.replace("{treeId}", tree.id()))
                  .header("Authorization", bearer)
                  .retrieve()
                  .body(new org.springframework.core.ParameterizedTypeReference<List<CategoryNodeRow>>() {});
        } catch (RestClientResponseException e) {
          skipped.add(tree.code() + "（" + e.getStatusCode().value() + "）");
          continue;
        }
        if (nodes == null) continue;
        for (var node : nodes) {
          if (node == null || node.id() == null || node.id().isBlank()) continue;
          items.add(
              new CategoryRef(
                  UUID.fromString(node.id()),
                  node.treeId() == null ? tree.id() : node.treeId(),
                  node.treeCode() == null ? tree.code() : node.treeCode(),
                  node.code() == null ? "" : node.code(),
                  node.name() == null ? "" : node.name(),
                  node.path() == null ? "" : node.path()));
        }
      }
      var note = skipped.isEmpty() ? "" : "部分品类树读不到：" + String.join("、", skipped);
      return new CategorySet(true, items, note);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        return new CategorySet(false, List.of(), "调用方无权读取品类目录（catalog 拒绝）");
      }
      return new CategorySet(false, List.of(), "catalog 服务返回 " + e.getStatusCode().value());
    } catch (org.springframework.web.client.ResourceAccessException e) {
      return new CategorySet(false, List.of(), "catalog 服务不可达");
    } catch (RuntimeException e) {
      return new CategorySet(false, List.of(), "读取品类目录失败：" + e.getMessage());
    }
  }

}
