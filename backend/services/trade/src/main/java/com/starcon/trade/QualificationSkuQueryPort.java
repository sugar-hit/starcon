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
 * 读取上游 <b>内部 SKU</b>事实的跨服务端口 —— 「这个商品到底存不存在、叫什么」这个事实的
 * <b>唯一来源</b>。
 *
 * <p>蓝图 6.1.2.3 的加粗句「资质过期必须阻断<b>相关商品</b>的刊登与发货」要求阻断判定能落到商品维度，
 * 而 SKU 事实（{@code sku} / {@code spu}）在 <b>catalog 服务的库</b>里。AGENTS.md 模块边界纪律禁止跨服务 SQL /
 * FK / 共享视图，所以本端口<b>只经 catalog 的授权只读端点</b>读。
 *
 * <p>用法与 {@link SourceSkuQueryPort}（6.1.2.1 的货源关联用）、{@link ExchangeSkuQueryPort}
 * （9.x 换货链路的 SKU 读取）逐字同构 —— 仓库里已经有三个「读 catalog 的 SKU」端口，
 * 各服务各持一份是<b>刻意</b>的：判据不同、报错语境不同，把三条链路的口径揉成一条端口，
 * 就会出现「换货查不到停售 SKU」或「阻断判定拿不到已停售 SKU」这类互相拖累的故障。
 *
 * <p><b>判据</b>：本模块的阻断判定<b>不要求 SKU 处于 ACTIVE</b>。
 * 一个已停售的 SKU 恰恰最需要阻断结论（它可能正在清货、可能还在铺货），把它查不出来等于
 * 「查不到就不阻断」—— 那是把安全默认值写反了。停售只影响能否继续卖，不影响资质判定。
 *
 * <p>租户<b>不</b>由本端口传递租户号：转发的是<b>调用方自己的</b> {@code Authorization} bearer，catalog 侧仍按已认证成员关系
 * 解析租户并强制 RLS。
 */
@Service
public class QualificationSkuQueryPort {

  /**
   * catalog 侧的 SKU 行（字段名逐字对齐 {@code SkuDetail}）。
   *
   * <p>刻意只声明本模块真正用到的字段：读得越少，跨服务契约越不容易在对方加字段时把本模块撑爆。
   */
  record SkuView(UUID id, String code, String name, String status) {}

  private final RestClient client;

  public QualificationSkuQueryPort(@Value("${CATALOG_URL:http://127.0.0.1:8082}") String url) {
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
   * 前者该当场拒绝判定，后者该重试。返回 null 会让前者被伪装成后者。
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
            HttpStatus.NOT_FOUND, "内部 SKU 不存在：" + skuId + "，资质阻断判定做不了，请重新选择商品");
      }
      if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
      }
      if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "当前身份无权读取商品主数据，资质阻断判定暂时做不了");
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
            + "本服务不会凭空编一个 SKU 出来做阻断判定");
  }
}
