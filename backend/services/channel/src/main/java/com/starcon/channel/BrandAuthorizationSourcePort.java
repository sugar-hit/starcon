package com.starcon.channel;

import static com.starcon.channel.BrandAuthorizationModels.BrandAuthorizationCandidate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 16.4.2.1 品牌授权的<b>跨服务只读端口</b>：03 品牌（catalog）与 06.1.2.3 供应商资质（<b>trade</b> 服务）。
 *
 * <p>🔴 AGENTS.md 的模块边界纪律禁止跨服务 SQL / FK / 共享视图，所以本端口<b>只经授权 HTTP API</b>读：
 *
 * <ul>
 *   <li>03 品牌 —— {@code GET /catalog/brands?keyword=}（品牌档案与关联类目，<b>只读</b>）。
 *   <li>06.1.2.3 供应商资质 —— {@code GET /trade/supplier-qualifications/suppliers}、
 *       {@code /qualifications}（被授权主体的候选来源）。
 * </ul>
 *
 * <p>🔴 <b>本模块不建 catalog 的品牌授权影子表</b>。catalog 3.2.1.2 那套「品牌档案自带的授权凭证」
 * 与本模块的「合规口径授权档案」<b>不是同一件事</b>（边界写在 V248 的文件头），
 * catalog 侧的一切都只从这里**只读**进来。
 *
 * <p>🔴🔴 <b>本端口不抛异常，而是把「读不到」表达成一个状态</b> —— 与
 * {@link CompliancePrecheckSourcePort} 同一手法，理由是本模块的产出是<b>授权档案 + 校验结果</b>：
 * 读不到上游时，正确答案是「这个候选列表<b>读不到</b>」（available=false + 原因），
 * 而不是「候选列表是空的」。把读不到折成空集合，界面会显示「这个品牌没有被授权过」，
 * 而真相是「我们没查到」—— 那是这类台账最致命的一种撒谎。
 *
 * <p>🔴 <b>租户不由本端口传递租户号</b>：转发的是<b>调用方自己的</b> {@code Authorization}
 * bearer，catalog / trade 侧仍按已认证成员关系解析租户并强制 RLS。租户隔离因此是<b>两道</b>。
 *
 * <p>🔴 <b>读得越少越不容易被上游加字段撑爆</b>：下面每个 record 都用
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} 且只声明本模块真用到的字段。
 */
@Service
public class BrandAuthorizationSourcePort {

  /** 03 品牌：品牌列表。 */
  static final String BRAND_PATH = "/catalog/brands";

  /** 06.1.2.3 供应商资质：供应商候选。 */
  static final String SUPPLIER_PATH = "/trade/supplier-qualifications/suppliers";

  /** 06.1.2.3 供应商资质：资质列表。 */
  static final String QUALIFICATION_PATH = "/trade/supplier-qualifications/qualifications";

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
  public record CandidateSet(
      boolean available, List<BrandAuthorizationCandidate> items, String note) {}

  // ================================================================ 内部形状

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record CatalogBrandRow(String id, String code, String name, String authorizationStatus, boolean enabled) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record CatalogBrandPage(List<CatalogBrandRow> items, long total, int page, int size) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record SupplierRow(String roleId, String partyCode, String partyName, String partyStatus) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record QualificationRow(
      String id,
      String code,
      String supplierRoleId,
      String supplierName,
      String qualificationType,
      String reviewStatus,
      String validFrom,
      String validUntil,
      String expiryState,
      int daysToExpiry) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record QualificationPage(List<QualificationRow> items, int page, int size, long total) {}

  // ================================================================ 客户端

  private final RestClient catalog;
  private final RestClient trade;

  public BrandAuthorizationSourcePort(
      @Value("${CATALOG_URL:http://127.0.0.1:8082}") String catalogUrl,
      @Value("${TRADE_URL:http://127.0.0.1:8085}") String tradeUrl) {
    this.catalog = client(catalogUrl);
    this.trade = client(tradeUrl);
  }

  private static RestClient client(String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(10));
    return RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  // ================================================================ 03 品牌

  /** 03 品牌候选（品牌档案，<b>只读</b>）。 */
  public CandidateSet brands(String keyword, String bearer) {
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
        return new CandidateSet(false, List.of(), "catalog 服务没有返回品牌列表");
      }
      List<BrandAuthorizationCandidate> items = new ArrayList<>();
      for (CatalogBrandRow row : page.items()) {
        items.add(
            new BrandAuthorizationCandidate(
                row.id(),
                row.code() == null ? "" : row.code(),
                row.name() == null ? "" : row.name(),
                row.authorizationStatus() == null ? "" : row.authorizationStatus(),
                "03 品牌档案（只读）"));
      }
      return new CandidateSet(true, items, "");
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        return new CandidateSet(false, List.of(), "调用方无权读取品牌档案（catalog 拒绝）");
      }
      return new CandidateSet(false, List.of(), "catalog 服务返回 " + e.getStatusCode().value());
    } catch (org.springframework.web.client.ResourceAccessException e) {
      return new CandidateSet(false, List.of(), "catalog 服务不可达");
    }
  }

  // ================================================================ 06.1.2.3 供应商资质

  /**
   * 🔴 6.1.2.3 供应商资质里的<b>被授权主体候选</b>。
   *
   * <p>任务书把 6.1.2.3 写成 catalog，实测它在 <b>trade</b> 服务
   * （{@code GET /trade/supplier-qualifications/suppliers}）。
   */
  public CandidateSet suppliers(String keyword, String bearer) {
    try {
      List<SupplierRow> rows =
          trade
              .get()
              .uri(
                  uriBuilder -> {
                    uriBuilder.path(SUPPLIER_PATH);
                    if (keyword != null && !keyword.isBlank()) {
                      uriBuilder.queryParam("keyword", keyword.trim());
                    }
                    return uriBuilder.build();
                  })
              .header("Authorization", bearer)
              .retrieve()
              .body(new org.springframework.core.ParameterizedTypeReference<List<SupplierRow>>() {});
      if (rows == null) {
        return new CandidateSet(false, List.of(), "trade 服务没有返回供应商候选");
      }
      List<BrandAuthorizationCandidate> items = new ArrayList<>();
      for (SupplierRow row : rows) {
        items.add(
            new BrandAuthorizationCandidate(
                row.roleId(),
                row.partyCode() == null ? "" : row.partyCode(),
                row.partyName() == null ? "" : row.partyName(),
                row.partyStatus() == null ? "" : row.partyStatus(),
                "6.1.2.3 供应商资质（trade 服务，只读）"));
      }
      return new CandidateSet(true, items, "");
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        return new CandidateSet(false, List.of(), "调用方无权读取供应商候选（trade 拒绝）");
      }
      return new CandidateSet(false, List.of(), "trade 服务返回 " + e.getStatusCode().value());
    } catch (org.springframework.web.client.ResourceAccessException e) {
      return new CandidateSet(false, List.of(), "trade 服务不可达");
    }
  }

  /** 某个品牌下已登记的供应商资质（被授权主体该选谁，答案在这里）。 */
  public CandidateSet qualifications(String brandId, String bearer) {
    try {
      QualificationPage page =
          trade
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(QUALIFICATION_PATH)
                          .queryParam("brandId", brandId)
                          .queryParam("page", 0)
                          .queryParam("size", CANDIDATE_SIZE)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(QualificationPage.class);
      if (page == null || page.items() == null) {
        return new CandidateSet(false, List.of(), "trade 服务没有返回资质列表");
      }
      List<BrandAuthorizationCandidate> items = new ArrayList<>();
      for (QualificationRow row : page.items()) {
        items.add(
            new BrandAuthorizationCandidate(
                row.id(),
                row.code() == null ? "" : row.code(),
                row.supplierName() == null ? "" : row.supplierName(),
                row.reviewStatus() == null ? "" : row.reviewStatus(),
                "6.1.2.3 供应商资质（trade 服务，只读）："
                    + (row.qualificationType() == null ? "" : row.qualificationType())
                    + (row.expiryState() == null ? "" : " / " + row.expiryState())));
      }
      return new CandidateSet(true, items, "");
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        return new CandidateSet(false, List.of(), "调用方无权读取供应商资质（trade 拒绝）");
      }
      return new CandidateSet(false, List.of(), "trade 服务返回 " + e.getStatusCode().value());
    } catch (org.springframework.web.client.ResourceAccessException e) {
      return new CandidateSet(false, List.of(), "trade 服务不可达");
    }
  }
}
