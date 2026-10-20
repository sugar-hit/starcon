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
 * 读取上游 <b>03 品牌</b>事实的跨服务端口 —— 「这个品牌授权到底关联的是哪个品牌、那个品牌存不存在」这个事实的
 * <b>唯一来源</b>。
 *
 * <p>蓝图 6.1.2.3 的关系句写死了「上游 = 3.3.2 品牌」，而品牌主数据（{@code brand} /
 * {@code brand_authorization}）在 <b>catalog 服务的库</b>里。AGENTS.md 模块边界纪律禁止跨服务 SQL / FK /
 * 共享视图，所以本端口<b>只经 catalog 的授权只读端点</b>读，不直连对方库、不建跨库视图、不建跨库外键。
 *
 * <p>用法与 {@link DealerPartyQueryPort}（读 catalog 的往来单位）、{@link SupplierPartyQueryPort}
 * （读 catalog 的供应商）逐字同构 —— 跨服务读一律走一个 port 类，取不到就<b>显式失败</b>。
 *
 * <p>用 3.3.2 已交付的形状（catalog {@code GET /catalog/brands} 与
 * {@code GET /catalog/brands/{id}}）：
 *
 * <ul>
 *   <li>列表：一行一个品牌，带 id / 编码 / 名称 / 授权状态 / 启用状态；</li>
 *   <li>单条：按 id 取该品牌的详情。</li>
 * </ul>
 *
 * <p><b>为什么品牌授权必须真的核对</b>：蓝图加粗句「品牌授权需关联 03 品牌」意味着授权书**必须**指向一个
 * 真实存在的品牌。不核对就收下，等于允许把授权书挂在一个不存在的品牌上 —— 那时「这个供应商能不能卖这个牌子」
 * 这个问题在数据上永远无法回答，而它正是本模块阻断判定要回答的问题之一。
 *
 * <p>租户<b>不</b>由本端口传递租户号：转发的是<b>调用方自己的</b> {@code Authorization} bearer，catalog 侧仍按已认证成员关系
 * 解析租户并强制 RLS。也就是说租户隔离是<b>两道</b>（trade 侧 RLS + catalog 侧 RLS），而不是「本服务替对方决定租户」。
 */
@Service
public class BrandQueryPort {

  /**
   * catalog 侧的品牌行（字段名逐字对齐 3.3.2 的 {@code BrandRow} 契约）。
   *
   * <p>刻意只声明本模块真正用到的字段：读得越少，跨服务契约越不容易在对方加字段时把本模块撑爆。
   */
  record BrandView(UUID id, String code, String name, String authorizationStatus, boolean enabled) {}

  private final RestClient client;

  public BrandQueryPort(@Value("${CATALOG_URL:http://127.0.0.1:8082}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    // 读超时给到 8 秒：拉一批候选要逐个服务往返，比单条读慢一截，
    // 但仍然必须有上限 —— 没有上限时商品主数据一慢就把本服务的请求线程全占住。
    factory.setReadTimeout(Duration.ofSeconds(8));
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  /** 拉一批品牌候选（登记品牌授权时选品牌用）。 */
  public List<BrandView> brands(String keyword, String bearer) {
    BrandPageView page;
    try {
      page =
          client
              .get()
              .uri(
                  uriBuilder -> {
                    var b = uriBuilder.path("/catalog/brands").queryParam("page", 0).queryParam("size", 200);
                    if (keyword != null && !keyword.isBlank()) {
                      b.queryParam("keyword", keyword.trim());
                    }
                    return b.build();
                  })
              .header("Authorization", bearer)
              .retrieve()
              .body(BrandPageView.class);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
      }
      if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "当前身份无权读取品牌清单，品牌授权暂时关联不了品牌");
      }
      throw unavailable("商品主数据服务返回 " + e.getStatusCode().value() + "，品牌清单暂时读不到");
    } catch (RestClientException e) {
      throw unavailable("商品主数据服务暂时不可达");
    }
    if (page == null || page.items() == null) {
      throw unavailable("商品主数据服务没有返回品牌清单");
    }
    return page.items().stream()
        .filter(row -> row != null && row.id() != null)
        .map(
            row ->
                new BrandView(
                    row.id(),
                    row.code() == null ? "" : row.code(),
                    row.name() == null ? "" : row.name(),
                    row.authorizationStatus() == null ? "" : row.authorizationStatus(),
                    Boolean.TRUE.equals(row.enabled())))
        .toList();
  }

  /** 取一个品牌；不存在就 404，**不返回 null 让调用方自己判空**。 */
  public BrandView require(UUID brandId, String bearer) {
    BrandDetailView view;
    try {
      view =
          client
              .get()
              .uri("/catalog/brands/" + brandId)
              .header("Authorization", bearer)
              .retrieve()
              .body(BrandDetailView.class);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND, "该品牌不存在：" + brandId + "，品牌授权请重新选择品牌");
      }
      if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
      }
      if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "当前身份无权读取该品牌，品牌授权暂时关联不了品牌");
      }
      throw unavailable("商品主数据服务返回 " + e.getStatusCode().value() + "，品牌暂时读不到");
    } catch (RestClientException e) {
      throw unavailable("商品主数据服务暂时不可达");
    }
    if (view == null || view.id() == null) {
      throw unavailable("商品主数据服务没有返回这个品牌");
    }
    return new BrandView(
        view.id(),
        view.code() == null ? "" : view.code(),
        view.name() == null ? "" : view.name(),
        view.authorizationStatus() == null ? "" : view.authorizationStatus(),
        Boolean.TRUE.equals(view.enabled()));
  }

  /**
   * 不可达一律 503，并把「为什么」如实报出。
   *
   * <p>刻意<b>不</b>返回空清单冒充「这个租户一个品牌都没有」：空清单会被当成
   * 「可以放心登记品牌授权」，而真实情况是商品主数据挂了。
   */
  private ResponseStatusException unavailable(String reason) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        reason
            + "：03 品牌主数据的唯一来源是 catalog 的授权只读接口"
            + "（3.3.2 品牌列表 / 品牌详情，跨库视图与跨服务 SQL 均被 AGENTS.md 禁止），"
            + "本服务不会凭空生成一份品牌清单");
  }

  // ---- catalog 侧的响应形状（只声明本模块用到的字段） ----

  record BrandPageView(List<BrandRowView> items, long total, int page, int size) {}

  record BrandRowView(
      UUID id,
      String code,
      String name,
      String authorizationStatus,
      Boolean enabled) {}

  record BrandDetailView(
      UUID id, String code, String name, String authorizationStatus, Boolean enabled) {}
}
