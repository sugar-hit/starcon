package com.starcon.crm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 读取 **catalog 的 SKU 类目**的**跨服务只读端口**（catalog 服务，端口 8082）——
 * 15.6.2.1 生命周期与流失预警的**类目维度**来源。
 *
 * <p>蓝图 15 卷 15.6.2.1 加粗句：「**复购周期因品类差异极大**（快消周级、家具月级），
 * 预警阈值需可按类目配置，不能一刀切」。要「按类目配置」就得先知道
 * <b>这位客户买的是哪一类</b> —— 而类目这个维度**只在 catalog 里**（SKU 主档的
 * {@code category} 列，SPU 维度的主分类）。
 *
 * <p>🔴 <b>跨服务纪律</b>：只经 catalog 的**授权 HTTP API** 读
 * （{@code GET /catalog/skus}），<b>不直连对方库、不建跨库 FK、不建跨库视图</b>。
 * 转发的是<b>调用方自己的</b> {@code Authorization} bearer —— catalog 侧仍按已认证成员
 * 关系解析租户并强制 RLS ⇒ 租户隔离是<b>两道</b>（crm 侧 RLS + catalog 侧 RLS）。
 *
 * <p>🔴 <b>三种结局必须分清</b>（照抄 {@link CustomerPartyPort} 的纪律）：
 *
 * <ul>
 *   <li><b>解析出来了</b> ⇒ {@code resolved=true} + 类目编码。</li>
 *   <li><b>catalog 里没有这个 SKU</b> ⇒ {@code resolved=false} +
 *       「catalog 里没有这个 SKU 编码」—— 这是<b>事实</b>（客户买了没建档的 SKU），不是故障。</li>
 *   <li><b>catalog 读不到</b>（401 / 403 / 5xx / 网络不通）⇒ <b>抛 503</b> ——
 *       「读不到」绝不能被当成「没有类目」，那会让整批客户悄悄落到全局阈值上，
 *       而运营以为自己是按类目配的。</li>
 * </ul>
 */
@Service
public class CustomerCategoryPort {

  /** 上游只读形状：{@code GET /catalog/skus}。 */
  static final String SKUS_PATH = "/catalog/skus";

  /**
   * 每次查询取多少行。
   *
   * <p>上游的 {@code q} 是 {@code ilike '%keyword%'}（编码 / 名称 / 条码三选一），
   * 所以一次查询会命中一批相似编码 ⇒ 取一小页即可在**客户端**做精确匹配。
   */
  static final int PAGE_SIZE = 20;

  /**
   * 上游一行 SKU（只声明本模块真正用到的字段）。
   *
   * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)}：上游那页还带着规格、
   * 投影单元格等若干列，本模块用不到，但<b>不因为读少就崩</b>。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record SkuRow(UUID id, String code, String name, String category) {}

  /** 上游一页 SKU 的形状。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record SkuPage(List<SkuRow> items, long total, int page, int size) {}

  /**
   * 一次类目解析的结果。
   *
   * @param resolved 解析出类目了吗
   * @param category catalog 的类目编码（未解析出来时为空串）
   * @param reason 没解析出来时的原因（人能读）
   */
  public record CategoryLookup(boolean resolved, String category, String reason) {}

  private static final ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private final RestClient client;

  public CustomerCategoryPort(@Value("${CATALOG_URL:http://127.0.0.1:8082}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(10));
    this.client =
        RestClient.builder()
            .baseUrl(url)
            .requestFactory(factory)
            .messageConverters(java.util.List.of(new MappingJackson2HttpMessageConverter(JSON)))
            .build();
  }

  /**
   * 解析一个 SKU 编码的类目。
   *
   * <p>🔴 **精确匹配**：上游的 {@code q} 是模糊匹配，会顺带命中
   * {@code SKU-001-A} / {@code SKU-001-B} 这样的同族编码 ——
   * 拿「第一条命中」当类目，等于把类目**猜**出来。所以这里在客户端做
   * {@code code} 全等匹配，匹配不上就是「解析不出来」，不猜。
   */
  public CategoryLookup categoryOf(String skuCode, String bearer) {
    if (skuCode == null || skuCode.isBlank()) {
      return new CategoryLookup(false, "", "上游订单没有带 SKU 编码");
    }
    String code = skuCode.trim();
    try {
      SkuPage page =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(SKUS_PATH)
                          .queryParam("q", code)
                          .queryParam("page", 0)
                          .queryParam("size", PAGE_SIZE)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(SkuPage.class);
      if (page == null || page.items() == null) {
        throw unavailable("catalog 服务没有返回 SKU 列表");
      }
      for (SkuRow row : page.items()) {
        if (row != null && code.equals(row.code())) {
          String category = row.category() == null ? "" : row.category().trim();
          if (category.isEmpty()) {
            return new CategoryLookup(false, "", "catalog 里这个 SKU 没有挂类目");
          }
          return new CategoryLookup(true, category, "");
        }
      }
      // 没匹配上 —— 这是**事实**（catalog 里没有这个 SKU 编码），不是故障。
      return new CategoryLookup(false, "", "catalog 里没有这个 SKU 编码");
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED, "调用方无权读取 catalog 的 SKU 类目（需要 catalog 侧的 SKU 读取权限）", e);
      }
      throw unavailable("catalog 服务返回 " + e.getStatusCode().value() + "：类目读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("catalog 服务不可达");
    }
  }

  /** 上游不可用一律 503，理由写明「读不到」而不是「没有类目」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }
}
