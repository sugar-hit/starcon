package com.starcon.trade;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.3.1.2 采购单读 catalog **媒体库**（3.4.1.1）的跨服务只读端口。
 *
 * <p>蓝图 6.3.1.2 的职责句写死「备注与**附件**」，而素材本体在 <b>catalog 服务的库</b>里
 * （{@code GET /catalog/media/{id}}）。AGENTS.md 模块边界纪律禁止跨服务 SQL / FK / 共享视图，
 * 所以本端口<b>只经 catalog 的授权只读端点</b>读，不直连对方库、不建跨库外键、不跨库写。
 *
 * <p><b>为什么读元数据而不是让调用方自己填</b>：附件的**元数据快照**（文件名 / 类型 / 大小 /
 * 存储键）是「当时挂的是哪个素材」的凭据。调用方填的话，页面上显示的附件名与素材库里
 * 真实的那一个可以对不上，而事后没人能查清到底挂了什么。
 * ⇒ 取不到就<b>显式失败</b>（503），不写一条「只有 id、没有名字」的附件 —— 那条记录对用户毫无用处，
 * 还会把「素材服务挂了」这件事藏起来。
 *
 * <p>用法与 {@link ReplenishmentCatalogPort}（读 catalog 的报价与箱规）、
 * {@link StockoutInventoryPort}（读 warehouse 的库存）逐字同构 —— 跨服务读一律走一个 port 类。
 *
 * <p>租户<b>不</b>由本端口传递租户号：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * catalog 侧仍按已认证成员关系解析租户并强制 RLS。租户隔离因此是<b>两道</b>
 * （trade 侧 RLS + catalog 侧 RLS）。
 */
@Service
public class PurchaseOrderMediaPort {

  /** catalog 侧的素材行（只声明本模块真正用到的字段）。 */
  record MediaView(
      UUID id,
      String name,
      String mediaType,
      String mimeType,
      BigDecimal byteSize,
      String storageKey,
      String originalFilename,
      boolean enabled) {}

  /** 素材详情响应（只声明用到的字段）。 */
  record MediaDetailView(MediaView asset) {}

  /** 端口专用的 ObjectMapper：**忽略上游多出来的字段**。 */
  private static final ObjectMapper PORT_JSON =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private final RestClient client;

  public PurchaseOrderMediaPort(@Value("${CATALOG_URL:http://127.0.0.1:8082}") String url) {
    var factory =
        new JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(8));
    var converter = new MappingJackson2HttpMessageConverter(PORT_JSON);
    this.client =
        RestClient.builder()
            .baseUrl(url)
            .requestFactory(factory)
            .messageConverters(java.util.List.of(converter))
            .build();
  }

  /**
   * 读一个素材的元数据。
   *
   * <p>不存在就 404（**不返回 null 让调用方自己判空**：「素材不存在」与「素材服务挂了」
   * 是两件事，前者该当场拒绝，后者该重试）。
   */
  public MediaView media(UUID mediaId, String bearer) {
    MediaDetailView detail;
    try {
      detail =
          client
              .get()
              .uri("/catalog/media/" + mediaId)
              .header("Authorization", bearer)
              .retrieve()
              .body(MediaDetailView.class);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND, "素材不存在：media " + mediaId + "，采购单挂不上这个附件");
      }
      if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
      }
      if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "当前身份无权读取素材库，采购单挂不上附件");
      }
      throw unavailable("商品主数据服务返回 " + e.getStatusCode().value());
    } catch (RestClientException e) {
      throw unavailable("商品主数据服务暂时不可达");
    }
    if (detail == null || detail.asset() == null || detail.asset().id() == null) {
      throw unavailable("商品主数据服务没有返回这个素材");
    }
    return detail.asset();
  }

  private ResponseStatusException unavailable(String reason) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        reason
            + "：附件的事实来源是商品主数据服务的授权只读接口（3.4.1.1 媒体库），"
            + "本服务不会凭空造一条「只有 id、没有文件名」的附件出来");
  }
}
