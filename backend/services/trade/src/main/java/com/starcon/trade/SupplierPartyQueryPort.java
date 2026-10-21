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
 * 读取上游 3.6.1.2 / 6.1.1.x **供应商**事实的跨服务端口 —— 「这个档口挂的到底是不是一个真实供应商」这个事实的
 * <b>唯一来源</b>。
 *
 * <p>蓝图 6.1.2.1 的关系句写死了「上游 = 外部货源平台」，而<b>供应商主数据在 catalog 的库里</b>
 * （卷 06 的主责是 trade + catalog（party），party 归 catalog）。AGENTS.md 模块边界纪律禁止跨服务 SQL / FK /
 * 共享视图，所以本端口<b>只经 catalog 的授权只读端点</b>读，不直连对方库、不建跨库视图、不建跨库外键。
 *
 * <p>用法与 {@link DealerPartyQueryPort}（读 catalog 的往来单位）、{@link ExchangeSkuQueryPort}（读 catalog 的
 * SKU）逐字同构 —— 跨服务读一律走一个 port 类，取不到就<b>显式失败</b>。
 *
 * <p>用 6.1.1.1 已交付的形状（catalog {@code GET /catalog/supply/vendors} 与
 * {@code GET /catalog/supply/vendors/{roleId}}）：
 *
 * <ul>
 *   <li>列表：一行一个 <b>SUPPLIER 角色</b>，带 roleId（角色 id，<b>不是</b> party id）、编码与名称；</li>
 *   <li>单条：按 roleId 取该供应商的采购视角详情。</li>
 * </ul>
 *
 * <p><b>为什么用 roleId 而不是 partyId</b>：6.1.1.x 全线以 SUPPLIER <b>角色</b> id 寻址供应商
 * （{@code supply/vendors/{roleId}/profile}），本模块沿用同一口径，档口关联的也是角色 id。
 *
 * <p>租户<b>不</b>由本端口传递租户号：转发的是<b>调用方自己的</b> {@code Authorization} bearer，catalog 侧仍按已认证成员关系
 * 解析租户并强制 RLS。也就是说租户隔离是<b>两道</b>（trade 侧 RLS + catalog 侧 RLS），而不是「本服务替对方决定租户」。
 */
@Service
public class SupplierPartyQueryPort {

  /**
   * catalog 侧的供应商行（字段名逐字对齐 6.1.1.1 的 {@code VendorRow} 契约）。
   *
   * <p>刻意只声明本模块真正用到的字段：读得越少，跨服务契约越不容易在对方加字段时把本模块撑爆。
   */
  record SupplierView(UUID roleId, String partyCode, String partyName, String partyStatus) {}

  private final RestClient client;

  public SupplierPartyQueryPort(@Value("${CATALOG_URL:http://127.0.0.1:8082}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    // 读超时给到 8 秒：拉一批候选要逐个服务往返，比单条读慢一截，
    // 但仍然必须有上限 —— 没有上限时商品主数据一慢就把本服务的请求线程全占住。
    factory.setReadTimeout(Duration.ofSeconds(8));
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  /** 拉一批供应商候选（建档口时选供应商用）。 */
  public List<SupplierView> suppliers(String keyword, String bearer) {
    VendorPageView page;
    try {
      page =
          client
              .get()
              .uri(
                  uriBuilder -> {
                    var b = uriBuilder.path("/catalog/supply/vendors").queryParam("page", 0).queryParam("size", 200);
                    if (keyword != null && !keyword.isBlank()) {
                      b.queryParam("keyword", keyword.trim());
                    }
                    return b.build();
                  })
              .header("Authorization", bearer)
              .retrieve()
              .body(VendorPageView.class);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
      }
      if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "当前身份无权读取供应商清单，档口暂时关联不了供应商");
      }
      throw unavailable("商品主数据服务返回 " + e.getStatusCode().value() + "，供应商清单暂时读不到");
    } catch (RestClientException e) {
      throw unavailable("商品主数据服务暂时不可达");
    }
    if (page == null || page.items() == null) {
      throw unavailable("商品主数据服务没有返回供应商清单");
    }
    return page.items().stream()
        .filter(row -> row != null && row.roleId() != null)
        .map(
            row ->
                new SupplierView(
                    row.roleId(),
                    row.partyCode() == null ? "" : row.partyCode(),
                    row.partyName() == null ? "" : row.partyName(),
                    row.partyStatus() == null ? "" : row.partyStatus()))
        .toList();
  }

  /** 取一个供应商（roleId）；不存在就 404，**不返回 null 让调用方自己判空**。 */
  public SupplierView require(UUID roleId, String bearer) {
    VendorPurchaseViewView view;
    try {
      view =
          client
              .get()
              .uri("/catalog/supply/vendors/" + roleId)
              .header("Authorization", bearer)
              .retrieve()
              .body(VendorPurchaseViewView.class);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND, "该供应商不存在：SUPPLIER 角色 " + roleId + "，请重新选择");
      }
      if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
      }
      if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "当前身份无权读取该供应商，档口暂时关联不了供应商");
      }
      throw unavailable("商品主数据服务返回 " + e.getStatusCode().value() + "，供应商暂时读不到");
    } catch (RestClientException e) {
      throw unavailable("商品主数据服务暂时不可达");
    }
    if (view == null || view.summary() == null || view.summary().roleId() == null) {
      throw unavailable("商品主数据服务没有返回这个供应商");
    }
    var row = view.summary();
    return new SupplierView(
        row.roleId(),
        row.partyCode() == null ? "" : row.partyCode(),
        row.partyName() == null ? "" : row.partyName(),
        row.partyStatus() == null ? "" : row.partyStatus());
  }

  /**
   * 不可达一律 503，并把「为什么」如实报出。
   *
   * <p>刻意<b>不</b>返回空清单冒充「这个租户一个供应商都没有」：空清单会被当成
   * 「可以放心新建档口」，而真实情况是商品主数据挂了。
   */
  private ResponseStatusException unavailable(String reason) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        reason
            + "：供应商主数据的唯一来源是 catalog 的授权只读接口"
            + "（6.1.1.x 供应商列表，跨库视图与跨服务 SQL 均被 AGENTS.md 禁止），"
            + "本服务不会凭空生成一份供应商清单");
  }

  // ---- catalog 侧的响应形状（只声明本模块用到的字段） ----

  record VendorPageView(List<VendorRowView> items, long total, int page, int size) {}

  record VendorPurchaseViewView(VendorRowView summary) {}

  record VendorRowView(
      UUID roleId,
      UUID partyId,
      String partyCode,
      String partyName,
      String partyStatus) {}
}
