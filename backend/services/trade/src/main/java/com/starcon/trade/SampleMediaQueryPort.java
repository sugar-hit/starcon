package com.starcon.trade;

import java.math.BigDecimal;
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
 * 读取上游 <b>3.4.1.1 媒体库</b>事实的跨服务端口 —— 「这张样品图片到底是哪一张」
 * 这个事实的<b>唯一系统来源</b>。
 *
 * <p>蓝图 6.6.1.2 的输入句写死了「样品信息与<b>图片</b>」，而媒体库在 <b>catalog 服务</b>的库里
 * （端点 {@code GET /catalog/media} 与 {@code GET /catalog/media/{id}}，已交付）。
 * AGENTS.md 模块边界纪律禁止跨服务 SQL / 跨库 FK / 共享视图，所以本端口<b>只经 catalog 的授权只读端点</b>读，
 * 不直连对方库、不建跨库视图、不建跨库外键 —— 与 {@link DevelopmentCostEstimatePort}
 * （读 5.3.1.1 利润测算器）、{@link SupplierPartyQueryPort}（读 6.1.1.x 供应商）逐字同构。
 *
 * <p><b>🔴 本端口只读，绝不跨库写</b>：media_* 那组表的**唯一写入所有者是 catalog**。
 * 本模块在 trade 库里只存「media id + 一份元数据快照」
 * （{@code development_sample_media}），catalog 那边改名 / 换存储键之后，
 * 样品这边仍能显示「当时挂的是哪一张」。
 *
 * <p>租户<b>不</b>由本端口传递：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * catalog 侧仍按已认证成员关系解析租户并强制 RLS。
 */
@Service
public class SampleMediaQueryPort {

  private final RestClient client;

  public SampleMediaQueryPort(@Value("${CATALOG_URL:http://127.0.0.1:8082}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(8));
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  /**
   * 拉一批媒体候选（挂样品图片时选图用）。
   *
   * @param keyword 关键词（null = 不筛）
   * @param bearer 调用方自己的 bearer（原样转发给 catalog）
   */
  public List<MediaView> candidates(String keyword, String bearer) {
    MediaPage page;
    try {
      page =
          client
              .get()
              .uri(
                  uriBuilder -> {
                    var b = uriBuilder.path("/catalog/media").queryParam("page", 0).queryParam("size", 50);
                    if (keyword != null && !keyword.isBlank()) {
                      b.queryParam("keyword", keyword.trim());
                    }
                    return b.build();
                  })
              .header("Authorization", bearer)
              .retrieve()
              .body(MediaPage.class);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
      }
      if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "当前身份无权读取媒体库，样品图片暂时挂不上");
      }
      throw unavailable("商品主数据服务返回 " + e.getStatusCode().value() + "，媒体库暂时读不到");
    } catch (RestClientException e) {
      throw unavailable("商品主数据服务暂时不可达");
    }
    if (page == null || page.items() == null) {
      throw unavailable("商品主数据服务没有返回媒体库清单");
    }
    return page.items().stream()
        .filter(row -> row != null && row.id() != null)
        .map(SampleMediaQueryPort::toView)
        .toList();
  }

  /**
   * 取一个媒体对象（按 id）。
   *
   * <p>不存在就 404，**不返回 null 让调用方自己判空** —— 挂一个不存在的图片 id
   * 会让「这张图是哪个对象」永远答不出来。
   */
  public MediaView require(UUID mediaRefId, String bearer) {
    MediaDetail detail;
    try {
      detail =
          client
              .get()
              .uri("/catalog/media/" + mediaRefId)
              .header("Authorization", bearer)
              .retrieve()
              .body(MediaDetail.class);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "媒体库中不存在这个素材（id=" + mediaRefId + "）：请先在媒体库上传或重新选择");
      }
      if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
      }
      if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "当前身份无权读取该素材，样品图片暂时挂不上");
      }
      throw unavailable("商品主数据服务返回 " + e.getStatusCode().value() + "，素材暂时读不到");
    } catch (RestClientException e) {
      throw unavailable("商品主数据服务暂时不可达");
    }
    if (detail == null || detail.asset() == null || detail.asset().id() == null) {
      throw unavailable("商品主数据服务没有返回这个素材");
    }
    return toView(detail.asset());
  }

  /**
   * 不可达一律 503，并把「为什么」如实报出。
   *
   * <p>刻意<b>不</b>返回空清单冒充「媒体库是空的」：空清单会被当成
   * 「可以放心随便挂一个 id」，而真实情况是商品主数据挂了。
   */
  private ResponseStatusException unavailable(String reason) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        reason
            + "：样品图片的唯一系统来源是 catalog 的媒体库"
            + "（3.4.1.1，跨库视图与跨服务 SQL 均被 AGENTS.md 禁止），"
            + "本服务不会凭空生成一个素材引用");
  }

  private static MediaView toView(MediaRow row) {
    return new MediaView(
        row.id(),
        row.name() == null ? "" : row.name(),
        row.mediaType() == null ? "" : row.mediaType(),
        row.fileFormat() == null ? "" : row.fileFormat(),
        row.mimeType() == null ? "" : row.mimeType(),
        row.byteSize(),
        row.widthPx() == null ? null : row.widthPx().intValue(),
        row.heightPx() == null ? null : row.heightPx().intValue(),
        row.contentSha256() == null ? "" : row.contentSha256(),
        row.storageKey() == null ? "" : row.storageKey());
  }

  // ---- catalog 侧的响应形状（只声明本模块用到的字段） ----

  /** catalog 媒体库的一行。 */
  record MediaRow(
      UUID id,
      String name,
      String mediaType,
      String fileFormat,
      String mimeType,
      BigDecimal byteSize,
      BigDecimal widthPx,
      BigDecimal heightPx,
      String contentSha256,
      String storageKey) {}

  /** catalog 媒体库的分页结果。 */
  record MediaPage(List<MediaRow> items, long total, int page, int size) {}

  /** catalog 媒体详情（资产 + 版本 / 引用 / 校验）。 */
  record MediaDetail(MediaRow asset) {}

  /** 供本模块使用的媒体视图（跨服务读的边界形状）。 */
  public record MediaView(
      UUID id,
      String name,
      String mediaType,
      String fileFormat,
      String mimeType,
      BigDecimal byteSize,
      Integer widthPx,
      Integer heightPx,
      String contentSha256,
      String storageKey) {}
}
