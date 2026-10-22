package com.starcon.channel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 16.4.1.2 检测与预警的<b>跨服务只读端口</b>：03 商品主数据（SKU 详情里的品牌名）。
 *
 * <p>🔴 AGENTS.md 的模块边界纪律禁止跨服务 SQL / FK / 共享视图，所以本端口<b>只经 catalog 的
 * 授权 HTTP API</b>读（{@code GET /catalog/skus/{id}}），不直连对方库。
 * 与 {@link CompliancePrecheckSourcePort}（4.2.3.2）、{@link IpRiskLibrarySourcePort}（16.4.1.1）
 * 逐字同构。
 *
 * <p>🔴🔴 <b>本端口不抛异常，而是把「读不到」表达成一个状态</b> ——
 * 与 {@link ListingComplianceSourcePort} 同一手法，理由是本模块的产出是<b>检测报告</b>：
 * 品牌这一维度读不到时，正确答案是「<b>品牌维度没查</b>」（available=false + 原因），
 * 而不是「整次检测失败」，更不是「这个商品没有品牌」——
 * 后者会把「查不到」说成「查过了，没有」，那是对检测结论撒谎。
 *
 * <p>🔴 <b>租户不由本端口传递租户号</b>：转发的是<b>调用方自己的</b> {@code Authorization}
 * bearer，catalog 侧仍按已认证成员关系解析租户并强制 RLS。租户隔离因此是<b>两道</b>
 * （channel 侧 RLS + catalog 侧 RLS），而不是「本服务替对方决定租户」。
 */
@Service
public class IpDetectionSourcePort {

  /** 03 商品主数据：SKU 详情。 */
  static final String SKU_PATH = "/catalog/skus";

  /**
   * 品牌解析结果。
   *
   * @param available 是否解析到了品牌（false ⇒ 品牌维度<b>没查</b>）
   * @param brand 品牌名（available=false 时为空串）
   * @param note 读不到 / 读到了什么的原因说明
   */
  public record BrandLookup(boolean available, String brand, String note) {}

  /** 源商品详情（03 SKU 主数据）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private record SkuDetail(String id, String name, String status, String brand) {}

  private final RestClient client;

  public IpDetectionSourcePort(@Value("${CATALOG_URL:http://127.0.0.1:8082}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(10));
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  /**
   * 取源商品的品牌名。
   *
   * @param skuId 内部 SKU id（catalog 的逻辑 id）
   * @param bearer 调用方自己的 bearer（转发给上游，租户由上游解析）
   * @return 品牌解析结果（<b>读不到就说读不到</b>）
   */
  public BrandLookup brandOfSku(String skuId, String bearer) {
    UUID parsed = parseId(skuId);
    if (parsed == null) {
      return new BrandLookup(false, "", "内部 SKU id 不是合法的 UUID");
    }
    try {
      SkuDetail result =
          client.get().uri(SKU_PATH + "/" + parsed).header("Authorization", bearer).retrieve()
              .body(SkuDetail.class);
      if (result == null) {
        return new BrandLookup(false, "", "catalog 服务没有返回源商品");
      }
      String brand = result.brand() == null ? "" : result.brand().trim();
      if (brand.isEmpty()) {
        return new BrandLookup(false, "", "源商品上没有品牌名（品牌维度没查，不是「没有品牌风险」）");
      }
      return new BrandLookup(true, brand, "");
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 404) {
        return new BrandLookup(false, "", "源商品不存在");
      }
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        return new BrandLookup(false, "", "调用方无权读取源商品（catalog 拒绝）");
      }
      return new BrandLookup(false, "", "catalog 服务返回 " + e.getStatusCode().value());
    } catch (ResourceAccessException e) {
      return new BrandLookup(false, "", "catalog 服务不可达");
    }
  }

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
