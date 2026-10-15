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
 * 读取 8.3.4.2 面单模板的**跨服务端口**。
 *
 * <p>面单模板在 warehouse 服务的库里，AGENTS.md 模块边界纪律禁止跨服务 SQL / FK /
 * 共享视图，所以本端口<b>只经 warehouse 的授权 HTTP 只读端点</b>读，
 * 与 {@link PackingQueryPort}（9.4.1.1 读 8.3.3.2）逐字同构。
 *
 * <p>用的是 8.3.4.2 已交付的形状：
 * {@code GET /warehouse/inventory/printing/templates}（模板清单）与
 * {@code GET /warehouse/inventory/printing/templates/{templateId}}（单模板）。
 *
 * <p>租户<b>不</b>由本端口传递租户号：转发的是<b>调用方自己的</b> {@code Authorization}
 * bearer，warehouse 侧仍按已认证成员关系解析租户并强制 RLS ——
 * 租户隔离是<b>两道</b>（trade 侧 RLS + warehouse 侧 RLS），不是本服务替对方决定租户。
 *
 * <p><b>本模块不渲染面单</b>：运单申请要的是「这一票用哪套模板」的引用，
 * ZPL 渲染与打印是 8.3.4.2 的活。自建第二个打印引擎会出现两份「印的是什么」。
 */
@Service
public class CarrierLabelTemplatePort {

  /** 模板行（只声明本模块真用到的字段：读得越少，对方加字段越不容易把本模块撑爆）。 */
  record LabelTemplateView(UUID id, String code, String name, String carrierCode, boolean active) {}

  record LabelTemplatePage(List<LabelTemplateView> items, int page, int size, long totalElements) {}

  private final RestClient client;

  public CarrierLabelTemplatePort(@Value("${WAREHOUSE_URL:http://127.0.0.1:8084}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(8));
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  /** 拉一页面单模板（真分页，不本地筛）。 */
  public LabelTemplatePage templates(String bearer, int page, int size) {
    try {
      LabelTemplatePage result =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path("/warehouse/inventory/printing/templates")
                          .queryParam("page", page)
                          .queryParam("size", size)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(LabelTemplatePage.class);
      if (result == null || result.items() == null) {
        throw unavailable("仓库服务没有返回面单模板列表");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
      }
      if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "当前身份无权读取仓库的面单模板");
      }
      throw unavailable("仓库服务返回 " + e.getStatusCode().value() + "，面单模板暂时读不到");
    } catch (RestClientException e) {
      throw unavailable("仓库服务暂时不可达");
    }
  }

  /**
   * 不可达一律 503，并把「为什么」如实报出。
   *
   * <p>刻意<b>不</b>返回空模板列表：空列表会被当成「没有可用面单模板」，
   * 而真实情况是仓库挂了 —— 现场会以为该配模板，于是先去配模板，
   * 耽误的却是本来只要等仓库恢复的时间。
   */
  private ResponseStatusException unavailable(String reason) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        reason
            + "：面单模板的唯一来源是 8.3.4.2 的授权只读接口"
            + "（warehouse 服务，跨库视图与跨服务 SQL 均被 AGENTS.md 禁止）");
  }
}
