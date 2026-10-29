package com.starcon.channel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 14.3.1.3 商品维度报表的**商品主数据跨服务只读端口**：上游 = **03 商品主数据**（catalog 服务）。
 *
 * <p>🔴 本服务（channel）只认 listing ↔ 内部 SKU 的**逻辑 id**（{@code listing_variant.internal_sku_id}，
 * 4.1.1.1 的现状位，跨服务不建 FK、不复制 catalog 任何一列）。要把它变成人看得懂的
 * <b>SKU 编码 / SPU 编码</b>，只能去 catalog 问 —— 这就是本端口存在的唯一理由。
 *
 * <p>🔴 <b>不传租户号</b>：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * catalog 侧仍按已认证成员关系解析租户并强制 RLS。租户隔离因此是<b>两道</b>。
 *
 * <p>🔴 <b>「catalog 里没有这个 SKU」与「catalog 读不到」是两件事</b>：
 * 前者返回 {@code null} 身份（那个 SKU 在本租户的商品主数据里还没有档案），
 * 后者把整批标成 {@code available=false} 并说明原因。混起来会让运营以为商品主数据坏了。
 */
@Service
public class AdProductCatalogPort {

  /** catalog 服务默认端口（照抄 {@link CatalogExchangeRatePort} 的默认值）。 */
  static final String CATALOG_URL = "http://127.0.0.1:8082";

  /** catalog 的单 SKU 编码面板读端点。 */
  static final String SKU_CODE_PATH = "/catalog/sku-code/skus/";

  /** 一次拉取的上游超时（秒）。 */
  private static final int READ_TIMEOUT_SECONDS = 10;

  /** catalog SKU 列表的读端点。 */
  static final String SKU_LIST_PATH = "/catalog/skus";

  /** SKU 列表每页条数（与上游的页大小对齐）。 */
  static final int LIST_PAGE_SIZE = 200;

  /** 🔴 最多翻多少页（200 × 10 = 2000 个 SKU）。翻到上限就**如实报截断**，不假装读完了。 */
  static final int MAX_LIST_PAGES = 10;

  // ================================================================ 内部形状

  /** 上游的单 SKU 编码面板（本端口只取真用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private record SkuCodePanel(
      UUID skuId, String skuCode, String skuName, String spuCode) {}

  /** 上游的 SKU 列表页（本端口只取真用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private record SkuListRow(UUID id, String code, String name, String spuCode) {}

  /** 上游的 SKU 列表页响应。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private record SkuListPage(List<SkuListRow> items, Long total, int page, int size) {}

  // ================================================================ 对外形状

  /**
   * 一个内部 SKU 的身份。
   *
   * @param skuCode 内部 SKU 编码（catalog 里没有这个 SKU 时整个身份是 null）
   * @param spuCode SPU 编码（catalog 里没有这个 SKU 时整个身份是 null）
   */
  public record SkuIdentity(UUID skuId, String skuCode, String skuName, String spuCode) {}

  /**
   * 一次商品主数据的读取结果。
   *
   * @param available 上游这次**有没有答**（false ⇒ 原因在 {@code unavailableReason}）
   * @param bySkuId 键 = 内部 SKU id（**只放真正解析到的**；catalog 里没有的 SKU 不在表里）
   * @param missingSkuIds catalog 里**没有档案**的 SKU id（如实报，不当成 0 也不当成失败）
   * @param unavailableReason 上游读不到时的原因（读到了则为空串）
   */
  public record CatalogSnapshot(
      boolean available,
      Map<UUID, SkuIdentity> bySkuId,
      List<UUID> missingSkuIds,
      String unavailableReason) {}

  /**
   * 一次**批量**读取的结果。
   *
   * @param available 上游这次**有没有答**
   * @param bySkuId 键 = 内部 SKU id（值含 SKU 编码与 SPU 编码）
   * @param total 上游 SKU 总数（用来判断有没有被页数上限截断）
   * @param truncated 有没有被 {@link #MAX_LIST_PAGES} 截断（true ⇒ 有一部分 SKU 没读到）
   * @param unavailableReason 上游读不到时的原因（读到了则为空串）
   */
  public record CatalogListSnapshot(
      boolean available,
      Map<UUID, SkuIdentity> bySkuId,
      long total,
      boolean truncated,
      String unavailableReason) {}

  // ================================================================ 客户端

  private final RestClient catalog;

  public AdProductCatalogPort(@Value("${CATALOG_URL:" + CATALOG_URL + "}") String catalogUrl) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build());
    factory.setReadTimeout(java.time.Duration.ofSeconds(READ_TIMEOUT_SECONDS));
    this.catalog = RestClient.builder().baseUrl(catalogUrl).requestFactory(factory).build();
  }

  /**
   * **批量**解析内部 SKU 的身份（SKU 编码 / SPU 编码）。
   *
   * <p>🔴 走 catalog 的 SKU 列表端点（每页 200 条），最多翻 {@link #MAX_LIST_PAGES} 页：
   * 逐个 id 去问是 N 次往返，本模块的汇总要按**在范围内的全部 SKU** 归集利润，
   * 那个 N 会随投放规模涨 ⇒ 改成「先拉一版 SKU 目录，建 id → (编码, SPU 编码) 的表」。
   *
   * <p>🔴 翻到页数上限就**如实报截断**（{@code truncated=true}）：没读到的 SKU 在报表里
   * 拿不到编码，于是它的利润归不到任何一行 —— 这一点由调用方写进响应，不藏。
   *
   * @param bearer 调用方自己的会话（转发给上游，**不传租户号**）
   */
  public CatalogListSnapshot listIdentities(String bearer) {
    Map<UUID, SkuIdentity> resolved = new LinkedHashMap<>();
    long total = 0;
    for (int page = 0; page < MAX_LIST_PAGES; page++) {
      final int currentPage = page;
      SkuListPage response;
      try {
        response =
            catalog
                .get()
                .uri(
                    uriBuilder ->
                        uriBuilder
                            .path(SKU_LIST_PATH)
                            .queryParam("page", currentPage)
                            .queryParam("size", LIST_PAGE_SIZE)
                            .build())
                .header("Authorization", bearer)
                .retrieve()
                .body(SkuListPage.class);
      } catch (RestClientResponseException e) {
        return new CatalogListSnapshot(
            false,
            resolved,
            total,
            false,
            e.getStatusCode().value() == 403
                ? "上游 03 商品主数据（catalog 服务）返回 403：当前账号没有 catalog.sku.read 权限，"
                    + "SKU 编码 / SPU 编码这次取不到（LISTING 维度不受影响）。"
                : "上游 03 商品主数据（catalog 服务）返回 " + e.getStatusCode().value()
                    + "，SKU 编码 / SPU 编码这次取不到（LISTING 维度不受影响）。");
      } catch (org.springframework.web.client.ResourceAccessException e) {
        return new CatalogListSnapshot(
            false,
            resolved,
            total,
            false,
            "上游 03 商品主数据（catalog 服务）不可达，SKU 编码 / SPU 编码这次取不到"
                + "（LISTING 维度不受影响）。");
      }
      if (response == null) {
        return new CatalogListSnapshot(
            false, resolved, total, false, "上游 03 商品主数据（catalog 服务）没有返回内容。");
      }
      if (response.total() != null) total = response.total();
      List<SkuListRow> items = response.items() == null ? List.of() : response.items();
      for (SkuListRow row : items) {
        if (row == null || row.id() == null) continue;
        resolved.put(
            row.id(),
            new SkuIdentity(
                row.id(),
                row.code() == null ? "" : row.code(),
                row.name() == null ? "" : row.name(),
                row.spuCode() == null ? "" : row.spuCode()));
      }
      if (items.isEmpty()) {
        return new CatalogListSnapshot(true, resolved, total, false, "");
      }
      if (total > 0 && (long) items.size() * (page + 1) >= total) {
        return new CatalogListSnapshot(true, resolved, total, false, "");
      }
    }
    boolean truncated = total > resolved.size();
    return new CatalogListSnapshot(
        true,
        resolved,
        total,
        truncated,
        truncated
            ? "商品主数据（catalog 服务）里的 SKU 超过本次读取上限（"
                + (MAX_LIST_PAGES * LIST_PAGE_SIZE)
                + " 个），"
                + "超出部分的 SKU 编码 / SPU 编码这次没读到 ⇒ 它们的利润归不到任何一行。"
            : "");
  }

  /**
   * 批量解析内部 SKU 的身份（SKU 编码 / SPU 编码）。
   *
   * @param skuIds 要解析的内部 SKU id（**去重后**的集合；空集合直接返回空结果，不发请求）
   * @param bearer 调用方自己的会话（转发给上游，**不传租户号**）
   */
  public CatalogSnapshot resolveAll(Collection<UUID> skuIds, String bearer) {
    Map<UUID, SkuIdentity> resolved = new LinkedHashMap<>();
    java.util.List<UUID> missing = new java.util.ArrayList<>();
    if (skuIds == null || skuIds.isEmpty()) {
      return new CatalogSnapshot(true, resolved, missing, "");
    }
    for (UUID skuId : skuIds) {
      if (skuId == null || resolved.containsKey(skuId)) continue;
      SkuCodePanel panel;
      try {
        panel =
            catalog
                .get()
                .uri(SKU_CODE_PATH + skuId)
                .header("Authorization", bearer)
                .retrieve()
                .body(SkuCodePanel.class);
      } catch (RestClientResponseException e) {
        if (e.getStatusCode().value() == 404) {
          // 🔴 catalog 里没有这个 SKU ⇒ 身份未知（**不是**「catalog 挂了」）。
          missing.add(skuId);
          continue;
        }
        return new CatalogSnapshot(
            false,
            resolved,
            missing,
            e.getStatusCode().value() == 403
                ? "上游 03 商品主数据（catalog 服务）返回 403：当前账号没有 catalog.sku.read 权限，"
                    + "SKU 编码 / SPU 编码这次取不到（LISTING 维度不受影响）。"
                : "上游 03 商品主数据（catalog 服务）返回 " + e.getStatusCode().value()
                    + "，SKU 编码 / SPU 编码这次取不到（LISTING 维度不受影响）。");
      } catch (org.springframework.web.client.ResourceAccessException e) {
        return new CatalogSnapshot(
            false,
            resolved,
            missing,
            "上游 03 商品主数据（catalog 服务）不可达，SKU 编码 / SPU 编码这次取不到"
                + "（LISTING 维度不受影响）。");
      }
      if (panel == null) {
        missing.add(skuId);
        continue;
      }
      resolved.put(
          skuId,
          new SkuIdentity(
              panel.skuId() == null ? skuId : panel.skuId(),
              panel.skuCode() == null ? "" : panel.skuCode(),
              panel.skuName() == null ? "" : panel.skuName(),
              panel.spuCode() == null ? "" : panel.spuCode()));
    }
    return new CatalogSnapshot(true, resolved, missing, "");
  }
}
