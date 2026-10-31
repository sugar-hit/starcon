package com.starcon.platform;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
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
 * catalog 服务（端口 8082）的**迁移端口** —— 18.1.3.2 唯一的目标库出口。
 *
 * <p>蓝图 18.1.3.2：「<b>关系</b>：上游 = 18.1.3.1；<b>下游 = 各业务域</b>。」
 * 本端口就是那个「下游」的第一站。
 *
 * <p>🔴 <b>只转发调用方自己的 bearer，不传租户号</b>：catalog 仍按已认证成员关系解析租户
 * 并强制 RLS。租户隔离因此是<b>两道</b>（platform 侧 RLS + catalog 侧 RLS），
 * 而不是「本服务替对方决定租户」。
 *
 * <p>🔴 <b>为什么不复用 {@code PlatformUpstreamClient}</b>：那个共用客户端只有
 * {@code get} / {@code postMultipart} / {@code postEmpty} / {@code getBytes} 四个动词，
 * 而迁移要调的是 <b>POST 带 JSON 体</b>（建商品）与 <b>DELETE</b>（回滚删商品）——
 * 两者语义与请求体形状都不同，硬塞进去会让「空体 POST」与「JSON 体 POST」混用。
 * ⇒ 本端口自带一个客户端（超时、mapper 配置与共用客户端**逐字同构**）。
 *
 * <p>⚠️ <b>失败分两类</b>：4xx（数据不合规 / 版本冲突）是<b>行级失败</b> ——
 * 那一行迁不成，但别的行还能迁；5xx 与网络不可达是<b>系统性失败** ——
 * 整个任务停在那里（已迁的部分留在库里，可回滚）。
 * 把两者混成一种会让「catalog 挂了」表现为「几千行数据格式错误」。
 */
@Service
public class DataMigrationCatalogPort {

  /** 上游 JSON 的 mapper（与 {@code PlatformUpstreamClient.JSON} 同构）。 */
  static final ObjectMapper JSON =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  /** catalog 的商品列表一次最多返回多少条（{@code list(q)} 里写死的 limit 200）。 */
  static final int PRODUCT_QUERY_CAP = 200;

  private final RestClient client;

  public DataMigrationCatalogPort(@Value("${CATALOG_URL:http://127.0.0.1:8082}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(10));
    this.client =
        RestClient.builder()
            .baseUrl(url)
            .requestFactory(factory)
            .messageConverters(List.of(new MappingJackson2HttpMessageConverter(JSON)))
            .build();
  }

  // ================================================================ 上游形状

  /** catalog 的商品（列表与详情里的 product）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SpuProduct(
      UUID id, String code, String name, String status, long version, int skuCount) {}

  /** catalog 的商品详情（只要 product.version —— 乐观锁要用）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SpuDetail(SpuProduct product, List<Object> attributes, List<Object> skus) {}

  /** catalog 的 SKU 列表行。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SkuRow(UUID id, UUID spuId, String code, String name, String barcode, String status, long version) {}

  /** catalog 的 SKU 列表页。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SkuPage(List<SkuRow> items, long total, int page, int size) {}

  /** 建 SPU 的请求体（字段名与 catalog 的 {@code SpuInput} 逐字一致）。 */
  public record SpuPayload(
      String code,
      String name,
      String description,
      String brand,
      String category,
      String status,
      long version) {}

  /** 建 SKU 的请求体（字段名与 catalog 的 {@code SkuInput} 逐字一致）。 */
  public record SkuPayload(
      String code,
      String name,
      String barcode,
      String baseUnit,
      String status,
      List<Object> values,
      long version) {}

  /** 目标库里某个编码的存在性（**三档**，不是布尔）。 */
  public enum Existence {
    /** 确实存在。 */
    EXISTS,
    /** 确实不存在（查全了）。 */
    ABSENT,
    /**
     * 查不清（结果被截断 / 上游读不到）。
     *
     * <p>🔴 查不清时**不能**当成「不存在」—— 那会把一条已存在的记录再迁一遍，
     * 在目标库里造出重复主数据。查不清时一律**跳过**这一行并写明原因。
     */
    UNKNOWN
  }

  /**
   * 目标库里某个 SPU 编码的命中结果。
   *
   * @param existence 三档存在性
   * @param id 命中的 SPU id（{@link Existence#EXISTS} 时有值）
   * @param version 命中时的版本号（乐观锁要用；**可能已过期**，写之前要重读）
   */
  public record SpuHit(Existence existence, UUID id, long version) {}

  // ================================================================ 异常

  /** 上游的**行级**失败（4xx）：这一行迁不成，别的行照迁。 */
  public static class RowFailure extends RuntimeException {
    public final int status;
    public final String detail;

    public RowFailure(int status, String detail) {
      super(detail);
      this.status = status;
      this.detail = detail;
    }
  }

  /** 上游的**系统性**失败（5xx / 不可达）：整个任务停住。 */
  public static class UpstreamUnavailable extends RuntimeException {
    public UpstreamUnavailable(String message, Throwable cause) {
      super(message, cause);
    }
  }

  // ================================================================ 存在性

  /**
   * 目标库里这个 SPU 编码在不在。
   *
   * <p>🔴 <b>catalog 的 {@code GET /catalog/products?q=} 是模糊匹配且上限 200 条</b>
   * （{@code name ilike '%q%' or code ilike '%q%'}，按 updated_at 倒序）。
   * 结果被截断时**不能**断言「不存在」⇒ 返回 {@link Existence#UNKNOWN}。
   */
  public SpuHit spuLookup(String code, String bearer) {
    List<SpuProduct> products;
    try {
      products =
          client
              .get()
              .uri(builder -> builder.path("/catalog/products").queryParam("q", code).build())
              .header("Authorization", bearer)
              .retrieve()
              .body(new org.springframework.core.ParameterizedTypeReference<List<SpuProduct>>() {});
    } catch (RestClientResponseException e) {
      throw unavailable("catalog 商品清单读不到：" + e.getStatusCode().value(), e);
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("catalog 不可达", e);
    }
    if (products == null) return new SpuHit(Existence.UNKNOWN, null, 0L);
    for (var p : products) {
      if (p != null && p.code() != null && p.code().equalsIgnoreCase(code)) {
        return new SpuHit(Existence.EXISTS, p.id(), p.version());
      }
    }
    // ⚠️ 命中上限 ⇒ 结果被截断 ⇒ 精确编码可能不在这一页里。
    return products.size() >= PRODUCT_QUERY_CAP
        ? new SpuHit(Existence.UNKNOWN, null, 0L)
        : new SpuHit(Existence.ABSENT, null, 0L);
  }

  /**
   * 目标库里这个 SKU 编码在不在。
   *
   * <p>catalog 的 SKU 列表**带 total**，所以能翻页翻完 ⇒ 结论是确定的。
   */
  public Existence skuExists(String code, String bearer) {
    var page = fetchSkuPage(code, 0, bearer);
    if (page == null) return Existence.UNKNOWN;
    if (contains(page.items(), code)) return Existence.EXISTS;
    if (page.total() > page.items().size()) {
      // 还有下一页没翻 ⇒ 继续翻。
      for (var p = 1; p <= 50; p++) {
        var next = fetchSkuPage(code, p, bearer);
        if (next == null) return Existence.UNKNOWN;
        if (contains(next.items(), code)) return Existence.EXISTS;
        if (next.items().isEmpty()) return Existence.ABSENT;
      }
      // 翻了 50 页还没翻完 ⇒ 判不了（不硬说「不存在」）。
      return Existence.UNKNOWN;
    }
    return Existence.ABSENT;
  }

  private static boolean contains(List<SkuRow> items, String code) {
    if (items == null) return false;
    for (var s : items) {
      if (s != null && s.code() != null && s.code().equalsIgnoreCase(code)) return true;
    }
    return false;
  }

  private SkuPage fetchSkuPage(String code, int page, String bearer) {
    try {
      return client
          .get()
          .uri(
              builder ->
                  builder
                      .path("/catalog/skus")
                      .queryParam("q", code)
                      .queryParam("page", page)
                      .queryParam("size", 200)
                      .build())
          .header("Authorization", bearer)
          .retrieve()
          .body(SkuPage.class);
    } catch (RestClientResponseException e) {
      throw unavailable("catalog SKU 清单读不到：" + e.getStatusCode().value(), e);
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("catalog 不可达", e);
    }
  }

  // ================================================================ 写入

  /**
   * 建一个 SPU（catalog 的 {@code POST /catalog/products}）。
   *
   * @return 新建出来的 SPU id（**回滚靠它**）
   */
  public UUID createSpu(SpuPayload payload, String bearer) {
    try {
      var id =
          client
              .post()
              .uri(builder -> builder.path("/catalog/products").build())
              .header("Authorization", bearer)
              .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
              .body(payload)
              .retrieve()
              .body(UUID.class);
      if (id == null) throw new RowFailure(502, "catalog 建商品时没有返回记录 id");
      return id;
    } catch (RestClientResponseException e) {
      throw rowFailure(e, "catalog 拒绝了这个商品");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("catalog 不可达", e);
    }
  }

  /**
   * 建一个 SKU（catalog 的 {@code POST /catalog/products/{id}/skus}）。
   *
   * @param spuVersion **父 SPU 的**版本号（catalog 的乐观锁用的是它，不是 SKU 的版本号）
   */
  public UUID createSku(UUID spuId, SkuPayload payload, String bearer) {
    try {
      var id =
          client
              .post()
              .uri(builder -> builder.path("/catalog/products/" + spuId + "/skus").build())
              .header("Authorization", bearer)
              .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
              .body(payload)
              .retrieve()
              .body(UUID.class);
      if (id == null) throw new RowFailure(502, "catalog 建 SKU 时没有返回记录 id");
      return id;
    } catch (RestClientResponseException e) {
      throw rowFailure(e, "catalog 拒绝了这个 SKU");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("catalog 不可达", e);
    }
  }

  // ================================================================ 回滚（删除）

  /**
   * 读一个 SPU 的**当前版本号**（回滚删除前必须读 —— catalog 用乐观锁）。
   */
  public long spuVersion(UUID spuId, String bearer) {
    try {
      var detail =
          client
              .get()
              .uri(builder -> builder.path("/catalog/products/" + spuId).build())
              .header("Authorization", bearer)
              .retrieve()
              .body(SpuDetail.class);
      if (detail == null || detail.product() == null) {
        throw new RowFailure(404, "catalog 里找不到这个商品");
      }
      return detail.product().version();
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 404) {
        throw new RowFailure(404, "catalog 里找不到这个商品");
      }
      throw unavailable("catalog 商品详情读不到：" + e.getStatusCode().value(), e);
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("catalog 不可达", e);
    }
  }

  /**
   * 删一个 SKU（catalog 的 {@code DELETE /catalog/products/{id}/skus/{sid}?version=}）。
   *
   * @param spuVersion **父 SPU 的**版本号
   */
  public void deleteSku(UUID spuId, UUID skuId, long spuVersion, String bearer) {
    try {
      client
          .delete()
          .uri(
              builder ->
                  builder
                      .path("/catalog/products/" + spuId + "/skus/" + skuId)
                      .queryParam("version", spuVersion)
                      .build())
          .header("Authorization", bearer)
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientResponseException e) {
      throw rowFailure(e, "catalog 没能删掉这个 SKU");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("catalog 不可达", e);
    }
  }

  /**
   * 删一个 SPU（catalog 的 {@code DELETE /catalog/products/{id}?version=}）。
   *
   * <p>⚠️ catalog 要求：商品是草稿、且名下没有 SKU。
   */
  public void deleteSpu(UUID spuId, long version, String bearer) {
    try {
      client
          .delete()
          .uri(
              builder ->
                  builder.path("/catalog/products/" + spuId).queryParam("version", version).build())
          .header("Authorization", bearer)
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientResponseException e) {
      throw rowFailure(e, "catalog 没能删掉这个商品");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("catalog 不可达", e);
    }
  }

  // ================================================================ 错误翻译

  /**
   * 上游 4xx → 行级失败；5xx → 系统性失败。
   *
   * <p>🔴 **409（乐观锁冲突）也是行级失败**：它说的是「这一条被别人改过了」，
   * 不是「catalog 整体不可用」—— 停掉整个迁移会让一次并发冲突毁掉整批迁移。
   */
  private static RowFailure rowFailure(RestClientResponseException e, String prefix) {
    var status = e.getStatusCode().value();
    if (status >= 400 && status < 500) {
      return new RowFailure(status, prefix + "（" + status + "）：" + bodyOf(e));
    }
    throw unavailable(prefix + "：" + status, e);
  }

  private static String bodyOf(RestClientResponseException e) {
    try {
      var body = e.getResponseBodyAsString();
      if (body == null || body.isBlank()) return "上游没有给出原因";
      // 上游的错误体可能是 ProblemDetail（{"detail":"..."}）或纯文本。
      try {
        var node = JSON.readTree(body);
        if (node.hasNonNull("detail")) return node.get("detail").asText();
        if (node.hasNonNull("message")) return node.get("message").asText();
      } catch (Exception ignored) {
        // 不是 JSON ⇒ 原样返回。
      }
      return body.length() > 300 ? body.substring(0, 300) : body;
    } catch (Exception ignored) {
      return "上游没有给出原因";
    }
  }

  private static UpstreamUnavailable unavailable(String message, Throwable cause) {
    return new UpstreamUnavailable("catalog " + message + "（是「读不到 / 写不进」，不是「没有数据」）", cause);
  }

  /** 供服务层把系统性失败翻成 503（对外的诚实口径）。 */
  public static ResponseStatusException toServiceUnavailable(UpstreamUnavailable e) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), e);
  }
}
