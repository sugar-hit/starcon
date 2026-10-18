package com.starcon.trade;

import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 读取上游 2.x 商品主数据的<b>跨服务端口</b> —— 「替换商品到底存不存在、是不是在售」这个事实的<b>唯一来源</b>。
 *
 * <p>蓝图 11.3.2.1 输入句点名的「替换商品选择」，选的必须是一个<b>真实的、在售的 SKU</b>。
 * 而 SKU 事实（{@code sku} / SPU）在 <b>catalog 服务的库</b>里，AGENTS.md 模块边界纪律禁止
 * 跨服务 SQL / FK / 共享视图，所以本端口<b>只经 catalog 的授权只读端点</b>读。
 *
 * <p>用法与 {@link ExchangeReservationPort}（写 warehouse）、{@link DealerPartyQueryPort}（读 catalog）逐字同构 ——
 * 跨服务访问一律走一个 port 类，取不到就<b>显式失败</b>。
 *
 * <p>用 2.x 已交付的形状：{@code GET /catalog/skus/{id}} —— 单个 SKU 详情。
 *
 * <p><b>为什么校验在售状态</b>：换货是把货发给买家，发一个已下架 / 已停售的 SKU 出去，
 * 买家收到的东西在平台上根本买不到 —— 那不是换货，那是客服事故。所以 {@code status}
 * 不是「顺手带回来显示一下」的字段，而是本模块的准入判据。
 *
 * <p>租户<b>不</b>由本端口传递租户号：转发调用方自己的 {@code Authorization} bearer，
 * catalog 侧仍按已认证成员关系解析租户并强制 RLS。
 */
@Service
public class ExchangeSkuQueryPort {

  /**
   * catalog 侧的 SKU 行（字段名逐字对齐 {@code SkuDetail}）。
   *
   * <p>刻意只声明本模块真正用到的四个字段：读得越少，跨服务契约越不容易在对方加字段时把本模块撑爆。
   */
  record SkuView(UUID id, String code, String name, String status) {}

  private final RestClient client;

  public ExchangeSkuQueryPort(@Value("${CATALOG_URL:http://127.0.0.1:8082}") String url) {
    var factory =
        new JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(5));
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  /**
   * 取一个替换 SKU 的事实；不存在就 404。
   *
   * <p>刻意<b>不</b>返回 null 让调用方自己判空：「商品不存在」与「商品服务挂了」是两件事，
   * 前者该当场拒绝换货，后者该重试。返回 null 会让前者被伪装成后者，
   * 运营会对着一个必然失败的请求一直重试下去。
   */
  public SkuView requireActive(UUID skuId, String bearer) {
    SkuView view;
    try {
      view =
          client
              .get()
              .uri("/catalog/skus/" + skuId)
              .header("Authorization", bearer)
              .retrieve()
              .body(SkuView.class);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND, "替换商品不存在：SKU " + skuId + "，请重新选择替换商品");
      }
      if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
      }
      if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "当前身份无权读取商品主数据，换货授权暂时做不了");
      }
      throw unavailable("商品服务返回 " + e.getStatusCode().value());
    } catch (RestClientException e) {
      throw unavailable("商品服务暂时不可达");
    }
    if (view == null || view.id() == null) {
      throw unavailable("商品服务没有返回这个 SKU");
    }
    if (!"ACTIVE".equals(view.status())) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "替换商品「"
              + view.name()
              + "」当前状态是 "
              + view.status()
              + "，已停售或未上架的商品不能作为换货替换品");
    }
    return view;
  }

  private ResponseStatusException unavailable(String reason) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        reason
            + "：替换商品事实的唯一来源是 catalog 的授权只读接口（跨库视图与跨服务 SQL 均被 AGENTS.md 禁止），"
            + "本服务不会凭空编一个替换商品出来授权换货");
  }
}
