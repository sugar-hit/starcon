package com.starcon.trade;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.6.2.2 的 <b>catalog 出站端口</b>：正式 SKU 建档（3.1.2.1）与报关属性（3.7.1.1）。
 *
 * <p><b>取证结论（本车道开工前 10 分钟自检）</b>：catalog 侧<b>确实有</b>可调的建档入口 ——
 * <ul>
 *   <li>{@code POST /catalog/products/{id}/skus}（{@code SkuInput}）—— 建 SKU，权限 {@code catalog.sku.create}，
 *       返回新建的 SKU id；</li>
 *   <li>{@code PUT /catalog/customs-attributes/skus/{skuId}}（{@code CustomsInput}）—— 写报关属性与申报要素，
 *       权限 {@code catalog.compliance.update}；</li>
 *   <li>{@code GET /catalog/products} / {@code GET /catalog/products/{id}} —— 读 SPU 与它的属性候选（只读）。</li>
 * </ul>
 * <b>建档的写入所有权在 catalog</b> ⇒ 本服务**不建 SKU 表、不跨库写 SKU**，
 * 只落<b>本服务侧的建档请求事实</b>，再经这个端口把 SKU 建出来，
 * SKU id 是<b>从 catalog 读回来的</b>，不是本服务编的。
 *
 * <p><b>🔴 关键取舍：catalog 的建档端点不接受幂等键</b> ⇒ 本端口必须把
 * 「请求超时 / 连接中断」与「请求被拒」<b>区分开</b>：
 * <ul>
 *   <li>被拒（4xx）/ 服务端错误（5xx）⇒ 请求<b>没有</b>生效 ⇒ 失败，可重试；</li>
 *   <li>超时 / 连接中断 ⇒ <b>可能已经建出来了</b> ⇒ 结果<b>未知</b>，不许盲目重试
 *       （重试可能建出第二个 SKU）。</li>
 * </ul>
 * 这条区分就是 {@link CreateSkuOutcome#unknown} 存在的理由，
 * 宁可让用户去商品中心核对一次，也不要悄悄建出重复 SKU。
 *
 * <p>租户<b>不</b>由本端口发明：{@code Authorization} 头是<b>调用方自己的</b>，
 * catalog 从已认证成员关系解析租户。
 */
@Service
public class MassProductionCatalogPort {

  /** catalog 的商品档案（{@code GET /catalog/products} 与 {@code /catalog/products/{id}}）。 */
  record SpuView(
      UUID id,
      String code,
      String name,
      String description,
      String brand,
      String category,
      String status,
      long version,
      int skuCount) {}

  /** catalog 的商品属性（建档载荷里「属性」那一段的候选）。 */
  record AttributeView(
      UUID id,
      String code,
      String name,
      String dataType,
      String valueMode,
      boolean multiple,
      boolean required,
      boolean variantAxis,
      String unit,
      String groupName,
      List<String> options,
      List<String> defaultValues) {}

  /** 商品档案详情（属性 + SKU）。 */
  record SpuDetail(SpuView product, List<AttributeView> attributes) {}

  /** 建档载荷里的一个属性取值（catalog 的 {@code ValueInput}）。 */
  record ValueInput(UUID attributeId, String state, List<String> values) {}

  /** 建档载荷（catalog 的 {@code SkuInput}）。 */
  record SkuInput(
      String code,
      String name,
      String barcode,
      String baseUnit,
      String status,
      List<ValueInput> values,
      long version) {}

  /** 报关申报要素（catalog 的 {@code DeclarationElementInput}）。 */
  record DeclarationElementInput(
      int ordinal, String elementCode, String elementName, String elementValue, boolean required) {}

  /** 报关属性载荷（catalog 的 {@code CustomsInput}）。 */
  record CustomsInput(
      String hsCode,
      String originCountryCode,
      String declaredName,
      java.math.BigDecimal declaredValueReference,
      String statutoryUnitCode,
      boolean preferentialOriginEligible,
      String preferenceAgreement,
      String note,
      List<DeclarationElementInput> elements,
      long version) {}

  /**
   * 建 SKU 的结果。
   *
   * <p>🔴 {@link #unknown} 与 {@link #rejected} 的区别就是「到底建没建出来」——
   * 未知时**不许**重试。
   */
  public sealed interface CreateSkuOutcome {
    /** 建成了：catalog 返回了 SKU id。 */
    record Created(UUID skuId, String skuCode) implements CreateSkuOutcome {}

    /** 被拒（4xx）：请求没有生效，可以重试（改了载荷再提）。 */
    record Rejected(String reason) implements CreateSkuOutcome {}

    /** 服务端错误（5xx）：请求没有生效，可以重试。 */
    record Unavailable(String reason) implements CreateSkuOutcome {}

    /** 🔴 结果未知（超时 / 连接中断）：可能已经建出来了，**不许盲目重试**。 */
    record Unknown(String reason) implements CreateSkuOutcome {}
  }

  /** 写报关属性的结果。 */
  public sealed interface SaveCustomsOutcome {
    /** 写进去了。 */
    record Saved() implements SaveCustomsOutcome {}

    /** 被拒（4xx）：没写进去。 */
    record Rejected(String reason) implements SaveCustomsOutcome {}

    /** 服务端错误（5xx）：没写进去。 */
    record Unavailable(String reason) implements SaveCustomsOutcome {}

    /** 🔴 结果未知（超时 / 连接中断）：可能已经写进去了。 */
    record Unknown(String reason) implements SaveCustomsOutcome {}
  }

  private final RestClient client;

  public MassProductionCatalogPort(
      @Value("${CATALOG_URL:http://127.0.0.1:8082}") String url,
      @Value("${CATALOG_TIMEOUT_SECONDS:8}") long timeoutSeconds) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)));
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  // ================================================================ 只读

  /** SPU 候选（SKU 建档的「目标键」）。 */
  public List<SpuView> spus(String keyword, String bearer) {
    try {
      SpuView[] result =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path("/catalog/products")
                          .queryParamIfPresent(
                              "q",
                              java.util.Optional.ofNullable(
                                  keyword == null || keyword.isBlank() ? null : keyword.trim()))
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(SpuView[].class);
      if (result == null) {
        throw unavailable("商品中心没有返回商品档案列表");
      }
      return List.of(result);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "当前身份无权读取商品档案，无法选择 SKU 要建在哪个 SPU 下面");
      }
      throw unavailable("商品中心返回 " + e.getStatusCode().value() + "，暂时无法读取商品档案");
    } catch (RestClientException e) {
      throw unavailable("商品中心暂时不可达");
    }
  }

  /** 某个 SPU 的属性候选（建档载荷里「属性」那一段能取哪些值）。 */
  public SpuDetail spuDetail(UUID spuId, String bearer) {
    try {
      SpuDetail detail =
          client
              .get()
              .uri("/catalog/products/" + spuId)
              .header("Authorization", bearer)
              .retrieve()
              .body(SpuDetail.class);
      if (detail == null || detail.product() == null) {
        throw unavailable("商品中心没有返回这个 SPU");
      }
      return detail;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 404) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND, "SPU 不存在或无权查看：" + spuId);
      }
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "当前身份无权读取该 SPU 的属性，无法准备 SKU 建档载荷");
      }
      throw unavailable("商品中心返回 " + e.getStatusCode().value() + "，暂时无法读取该 SPU");
    } catch (RestClientException e) {
      throw unavailable("商品中心暂时不可达");
    }
  }

  // ================================================================ 写

  /**
   * 在 catalog 建 SKU。
   *
   * <p>🔴 把「被拒 / 服务端错误 / 结果未知」<b>分成三种结果返回</b>，不合并成一种失败 ——
   * 因为只有前两者能安全重试。
   */
  public CreateSkuOutcome createSku(UUID spuId, SkuInput input, String bearer) {
    try {
      UUID skuId =
          client
              .post()
              .uri("/catalog/products/" + spuId + "/skus")
              .header("Authorization", bearer)
              .body(input)
              .retrieve()
              .body(UUID.class);
      if (skuId == null) {
        return new CreateSkuOutcome.Unknown("商品中心没有返回新建的 SKU id");
      }
      return new CreateSkuOutcome.Created(skuId, input.code());
    } catch (RestClientResponseException e) {
      int code = e.getStatusCode().value();
      if (code == 401 || code == 403) {
        return new CreateSkuOutcome.Rejected(
            "当前身份无权在商品中心建 SKU（需要 catalog.sku.create 与 catalog.sku.read）："
                + e.getStatusCode().value());
      }
      if (code >= 500) {
        return new CreateSkuOutcome.Unavailable(
            "商品中心返回 " + code + "，请求没有生效：" + shortBody(e));
      }
      return new CreateSkuOutcome.Rejected("商品中心返回 " + code + "：" + shortBody(e));
    } catch (RestClientException e) {
      // 🔴 超时 / 连接中断：请求**可能已经生效**（SKU 可能已经建出来了），
      //    盲目重试会建出第二个 SKU ⇒ 结果未知。
      return new CreateSkuOutcome.Unknown(
          "调用商品中心超时或连接中断，无法判断 SKU 是否已经建出来：" + e.getClass().getSimpleName());
    }
  }

  /**
   * 把报关属性写进已经建好的 SKU。
   *
   * <p>🔴 报关属性是 catalog 的<b>另一个模块</b>（3.7.1.1，权限 {@code catalog.compliance.update}），
   * 它自己还有「草稿 → 提交 → 审核」的状态机 —— 本模块只把内容存成草稿，
   * <b>不替它提交审核</b>（能不能审核通过是商品中心自己的事）。
   */
  public SaveCustomsOutcome saveCustoms(UUID skuId, CustomsInput input, String bearer) {
    try {
      client
          .put()
          .uri("/catalog/customs-attributes/skus/" + skuId)
          .header("Authorization", bearer)
          .body(input)
          .retrieve()
          .toBodilessEntity();
      return new SaveCustomsOutcome.Saved();
    } catch (RestClientResponseException e) {
      int code = e.getStatusCode().value();
      if (code == 401 || code == 403) {
        return new SaveCustomsOutcome.Rejected(
            "当前身份无权在商品中心写报关属性（需要 catalog.compliance.update）：" + code);
      }
      if (code >= 500) {
        return new SaveCustomsOutcome.Unavailable(
            "商品中心返回 " + code + "，报关属性没有写进去：" + shortBody(e));
      }
      return new SaveCustomsOutcome.Rejected("商品中心返回 " + code + "：" + shortBody(e));
    } catch (RestClientException e) {
      return new SaveCustomsOutcome.Unknown(
          "调用商品中心超时或连接中断，无法判断报关属性是否已写入：" + e.getClass().getSimpleName());
    }
  }

  /** 未接入 / 不可达一律 503，并把「为什么」如实报出。 */
  private ResponseStatusException unavailable(String reason) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        reason
            + "：正式 SKU 的写入所有权在商品中心（3.1.2.1 / POST /catalog/products/{id}/skus），"
            + "本服务不复制一份商品主数据，也不凭空造一个 SKU");
  }

  /** 截短错误正文，避免把整页 HTML 塞进数据库。 */
  private static String shortBody(RestClientResponseException e) {
    String body = e.getResponseBodyAsString();
    if (body == null || body.isBlank()) {
      return "(无响应正文)";
    }
    String trimmed = body.replaceAll("\\s+", " ").trim();
    return trimmed.length() > 300 ? trimmed.substring(0, 300) : trimmed;
  }
}
