package com.starcon.trade;

import com.starcon.trade.CarrierWaybillPort.DeclarationLine;
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
 * 读取 3.7 报关属性的**跨服务端口**（3.7.1.1 HS 编码与申报要素 / 3.7.1.2 目的地报关版本）。
 *
 * <p>蓝图输入句第三项是「申报信息（3.7 报关属性）」，而报关属性在 catalog 服务的库里。
 * AGENTS.md 模块边界纪律禁止跨服务 SQL / FK / 共享视图，所以本端口<b>只经 catalog
 * 的授权 HTTP 只读端点</b>读，形态与 {@link PackingQueryPort}（9.4.1.1 读 8.3.3.2）、
 * {@link CarrierLabelTemplatePort}（本模块读 8.3.4.2）逐字同构。
 *
 * <p>用到的形状是 3.7.1.1 已交付的 {@code GET /catalog/customs-attributes/skus/{skuId}}，
 * 它返回 {@code CustomsProfile}，其中 {@code declarationReady} + {@code blockers}
 * 正是本模块要的东西：<b>只读已审核通过的申报属性</b>，草稿态的 HS 编码不能拿去报关。
 *
 * <p>租户<b>不</b>由本端口传递：转发<b>调用方自己的</b> {@code Authorization} bearer，
 * catalog 侧仍按已认证成员关系解析租户并强制 RLS。两道隔离。
 *
 * <p>⚠️ <b>取不到就 503，不返回空申报</b>：空申报会被当成「这票货不用报关」，
 * 而真实情况是商品中心挂了 —— 后果是一批货带着空申报单进了清关环节。
 */
@Service
public class CustomsAttributePort {

  /**
   * 3.7.1.1 的报关属性投影（字段名逐字对齐 catalog 的 {@code CustomsProfile}）。
   *
   * <p>只声明本模块要用的字段：{@code declarationReady} 决定能不能用于申报，
   * {@code blockers} 决定拒绝时给界面什么可读原因。
   */
  record CustomsAttributeView(
      UUID skuId,
      String skuCode,
      String skuName,
      UUID attributeId,
      String hsCode,
      String originCountryCode,
      String declaredName,
      BigDecimal declaredValueReference,
      String statutoryUnitCode,
      String status,
      boolean declarationReady,
      List<String> blockers,
      List<String> blockerLabels) {}

  private final RestClient client;

  public CustomsAttributePort(@Value("${CATALOG_URL:http://127.0.0.1:8082}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(8));
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  /** 读一个 SKU 的报关属性与可报关性判定。 */
  public CustomsAttributeView profile(UUID skuId, String bearer) {
    try {
      CustomsAttributeView view =
          client
              .get()
              .uri("/catalog/customs-attributes/skus/" + skuId)
              .header("Authorization", bearer)
              .retrieve()
              .body(CustomsAttributeView.class);
      if (view == null) {
        throw unavailable("商品中心没有返回这个 SKU 的报关属性");
      }
      return view;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
        // SKU 没有报关属性：这不是服务不可达，是**业务事实**——
        // 所以它由调用方决定拦不拦（申报必填，因此本模块一律拦）。
        return null;
      }
      if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
      }
      if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "当前身份无权读取商品中心的报关属性，运单申请暂时提交不了");
      }
      throw unavailable("商品中心返回 " + e.getStatusCode().value() + "，申报信息暂时核不了");
    } catch (RestClientException e) {
      throw unavailable("商品中心暂时不可达");
    }
  }

  /**
   * 核对一条申报明细与 3.7 的口径是否对得上。
   *
   * <p>返回 null 表示「对得上」；非 null 是<b>拒绝原因</b>，调用方据此把这一票置
   * FAILED 并把原因原样落库。刻意返回原因字符串而不是 boolean ——
   * 界面需要显示「HS 编码与商品中心登记的不一致（申报 61091000 / 登记 42021210）」，
   * 一个 boolean 只会让界面显示「申报信息有问题」。
   *
   * @param declared 申报方给的口径，{@code destination} 是收件国
   */
  public String mismatch(
      DeclarationLine declared, String destination, CustomsAttributeView profile) {
    if (profile == null) {
      return "SKU " + declared.skuRef() + " 在商品中心没有报关属性，无法核对申报口径";
    }
    if (!profile.declarationReady()) {
      var labels =
          profile.blockerLabels() == null || profile.blockerLabels().isEmpty()
              ? profile.blockers()
              : profile.blockerLabels();
      return "SKU "
          + declared.skuRef()
          + " 的报关属性尚未审核通过"
          + (labels == null || labels.isEmpty() ? "" : "：" + String.join("；", labels))
          + "（3.7.1.1 要求审核通过才可用于报关，草稿态的 HS 编码承运商与海关都会拒收）";
    }
    if (profile.hsCode() != null
        && !profile.hsCode().isBlank()
        && !profile.hsCode().equalsIgnoreCase(declared.hsCode())) {
      return "SKU "
          + declared.skuRef()
          + " 的 HS 编码与商品中心登记的不一致（申报 "
          + declared.hsCode()
          + " / 登记 "
          + profile.hsCode()
          + "）";
    }
    if (destination != null
        && !destination.isBlank()
        && profile.originCountryCode() != null
        && !profile.originCountryCode().isBlank()
        && declared.originCountry() != null
        && !declared.originCountry().isBlank()
        && !profile.originCountryCode().equalsIgnoreCase(declared.originCountry())) {
      return "SKU "
          + declared.skuRef()
          + " 的原产地与商品中心登记的不一致（申报 "
          + declared.originCountry()
          + " / 登记 "
          + profile.originCountryCode()
          + "）";
    }
    return null;
  }

  private ResponseStatusException unavailable(String reason) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        reason
            + "：申报信息的唯一来源是 3.7.1.1 的授权只读接口"
            + "（catalog 服务，跨库视图与跨服务 SQL 均被 AGENTS.md 禁止），"
            + "本服务不会凭空生成一份申报信息");
  }
}
