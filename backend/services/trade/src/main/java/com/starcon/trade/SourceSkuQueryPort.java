package com.starcon.trade;

import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 读取上游 **内部 SKU** 事实的跨服务端口 —— 「这条货源关联的那个 SKU 到底存不存在、叫什么」这个事实的
 * <b>唯一来源</b>。
 *
 * <p>蓝图 6.1.2.1 输出句之一是「建立与内部 SKU 的货源关联」，下游 6.3.1.2 采购单要带入货源价。而 SKU 事实
 * （{@code sku} / {@code spu}）在 <b>catalog 服务的库</b>里，AGENTS.md 模块边界纪律禁止跨服务 SQL / FK /
 * 共享视图，所以本端口<b>只经 catalog 的授权只读端点</b>读。
 *
 * <p>用法与 {@link ExchangeSkuQueryPort}（读 catalog 的 SKU）逐字同构 —— 但**判据不同**：
 * {@code ExchangeSkuQueryPort} 要求 SKU 必须 <b>ACTIVE</b>（换货发一个停售 SKU 出去是客服事故），
 * 而本模块只是「把货源挂到内部 SKU 上做比价」，**停售 SKU 也仍然要能挂** ——
 * 一个已停售的 SKU 恰恰是最需要「还能从哪进货」的货源比价场景。
 * 把两条链路的判据混成一条，会让停售 SKU 的比价页直接查不出来。
 *
 * <p>租户<b>不</b>由本端口传递租户号：转发的是<b>调用方自己的</b> {@code Authorization} bearer，catalog 侧仍按已认证成员关系
 * 解析租户并强制 RLS。
 */
@Service
public class SourceSkuQueryPort {

  /**
   * catalog 侧的 SKU 行（字段名逐字对齐 {@code SkuDetail}）。
   *
   * <p>刻意只声明本模块真正用到的字段：读得越少，跨服务契约越不容易在对方加字段时把本模块撑爆。
   */
  record SkuView(UUID id, String code, String name, String status) {}

  private final RestClient client;

  public SourceSkuQueryPort(@Value("${CATALOG_URL:http://127.0.0.1:8082}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(5));
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  /**
   * 取一个 SKU 的事实；不存在就 404。
   *
   * <p>刻意<b>不</b>返回 null 让调用方自己判空：「商品不存在」与「商品服务挂了」是两件事，
   * 前者该当场拒绝关联，后者该重试。返回 null 会让前者被伪装成后者。
   */
  public SkuView require(UUID skuId, String bearer) {
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
            HttpStatus.NOT_FOUND, "内部 SKU 不存在：" + skuId + "，货源关联做不了，请重新选择商品");
      }
      if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
      }
      if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "当前身份无权读取商品主数据，货源关联暂时做不了");
      }
      throw unavailable("商品服务返回 " + e.getStatusCode().value());
    } catch (RestClientException e) {
      throw unavailable("商品服务暂时不可达");
    }
    if (view == null || view.id() == null) {
      throw unavailable("商品服务没有返回这个 SKU");
    }
    return view;
  }

  private ResponseStatusException unavailable(String reason) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        reason
            + "：内部 SKU 的唯一来源是 catalog 的授权只读接口（跨库视图与跨服务 SQL 均被 AGENTS.md 禁止），"
            + "本服务不会凭空编一个 SKU 出来挂货源");
  }
}
