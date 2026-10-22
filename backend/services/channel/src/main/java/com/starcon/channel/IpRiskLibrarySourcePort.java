package com.starcon.channel;

import static com.starcon.channel.IpRiskLibraryModels.IpRiskLibraryBrandCandidate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 16.4.1.1 侵权库的<b>跨服务只读端口</b>：03 品牌（<b>catalog</b> 服务，端口 8082）。
 *
 * <p>🔴 AGENTS.md 的模块边界纪律禁止跨服务 SQL / FK / 共享视图，所以本端口<b>只经授权 HTTP API</b>读：
 * {@code GET /catalog/brands?keyword=}（品牌档案，<b>只读</b>）。
 *
 * <p>🔴🔴 <b>本模块不建 catalog 品牌的影子表</b>：catalog 3.3.2 的「品牌档案」与本模块的
 * 「侵权风险条目」<b>不是同一件事</b>，落库时只存品牌<b>逻辑 id</b>（{@code brand_id}），
 * <b>零跨库 FOREIGN KEY、零跨库视图、零跨服务 SQL</b>。
 *
 * <p>🔴🔴 <b>本端口不抛异常，而是把「读不到」表达成一个状态</b> —— 与
 * {@link BrandAuthorizationSourcePort} / {@link CompliancePrecheckSourcePort} 同一手法。
 * 理由是本模块的产出是<b>侵权库记录</b>：读不到上游时，正确答案是「这个品牌候选<b>读不到</b>」
 * （available=false + 原因），而不是「候选是空的」。把读不到折成空集合，
 * 界面会显示「上游一个品牌都没有」，而真相是「我们没查到」—— 那是这类台账最致命的一种撒谎。
 *
 * <p>🔴 <b>租户不由本端口传递租户号</b>：转发的是<b>调用方自己的</b> {@code Authorization}
 * bearer，catalog 侧仍按已认证成员关系解析租户并强制 RLS。租户隔离因此是<b>两道</b>。
 *
 * <p>🔴 <b>读得越少越不容易被上游加字段撑爆</b>：下面每个 record 都用
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} 且只声明本模块真用到的字段。
 */
@Service
public class IpRiskLibrarySourcePort {

  /** 03 品牌：品牌列表。 */
  static final String BRAND_PATH = "/catalog/brands";

  /** 一次候选检索最多取多少行：候选是字典级的，命中即止。 */
  static final int CANDIDATE_SIZE = 20;

  // ================================================================ 结果形状

  /**
   * 候选读出结果。
   *
   * @param available 上游是否读到了（false ⇒ 界面显示「读不到」，**不是**「没有候选」）
   * @param items 候选
   * @param note 读不到 / 读到了什么的原因说明
   */
  public record BrandSet(boolean available, List<IpRiskLibraryBrandCandidate> items, String note) {}

  // ================================================================ 内部形状

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record CatalogBrandRow(String id, String code, String name, String authorizationStatus, boolean enabled) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record CatalogBrandPage(List<CatalogBrandRow> items, long total, int page, int size) {}

  // ================================================================ 客户端

  private final RestClient catalog;

  public IpRiskLibrarySourcePort(
      @Value("${CATALOG_URL:http://127.0.0.1:8082}") String catalogUrl) {
    this.catalog = client(catalogUrl);
  }

  private static RestClient client(String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(10));
    return RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  // ================================================================ 03 品牌

  /**
   * 03 品牌候选（品牌档案，<b>只读</b>）。
   *
   * @param keyword 关键字（可空）
   * @param bearer 调用方自己的 {@code Authorization} 头（**原样转发**，不传租户号）
   */
  public BrandSet brands(String keyword, String bearer) {
    try {
      CatalogBrandPage page =
          catalog
              .get()
              .uri(
                  uriBuilder -> {
                    uriBuilder.path(BRAND_PATH).queryParam("page", 0).queryParam("size", CANDIDATE_SIZE);
                    if (keyword != null && !keyword.isBlank()) {
                      uriBuilder.queryParam("keyword", keyword.trim());
                    }
                    return uriBuilder.build();
                  })
              .header("Authorization", bearer)
              .retrieve()
              .body(CatalogBrandPage.class);
      if (page == null || page.items() == null) {
        return new BrandSet(false, List.of(), "catalog 服务没有返回品牌列表");
      }
      List<IpRiskLibraryBrandCandidate> items = new ArrayList<>();
      for (CatalogBrandRow row : page.items()) {
        items.add(
            new IpRiskLibraryBrandCandidate(
                row.id(),
                row.code() == null ? "" : row.code(),
                row.name() == null ? "" : row.name(),
                row.authorizationStatus() == null ? "" : row.authorizationStatus(),
                "3.3.2 品牌档案（catalog 服务，只读）"));
      }
      return new BrandSet(true, items, "");
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        return new BrandSet(false, List.of(), "调用方无权读取品牌档案（catalog 拒绝）");
      }
      return new BrandSet(false, List.of(), "catalog 服务返回 " + e.getStatusCode().value());
    } catch (org.springframework.web.client.ResourceAccessException e) {
      return new BrandSet(false, List.of(), "catalog 服务不可达");
    }
  }
}
