package com.starcon.trade;

import com.starcon.trade.CustomsElementSetModels.ElementSetItemRow;
import com.starcon.trade.CustomsElementSetModels.ElementSetUpstreamPreview;
import java.time.Duration;
import java.util.ArrayList;
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
 * 10.5.1.2 读 3.7.1.2 目的地报关版本的<b>跨服务端口</b>。
 *
 * <p><b>为什么不复用 {@link CustomsAttributePort}</b>（任务书建议优先复用，这里说明实查结论）：
 * {@code CustomsAttributePort} 打的是 {@code GET /catalog/customs-attributes/skus/{skuId}}，
 * 那个端点回答的是「这个 SKU 的<b>基础</b>报关属性是什么」（3.7.1.1 那一层，
 * 无目的地维度）。本模块的蓝图输入句是「<b>3.7.1.2</b> 目的地报关版本」，
 * 对应的端点是 {@code GET /catalog/customs-declaration/resolve?skuId&destination&at} ——
 * 它返回**合成后**的资料（目的地覆盖后的税号与品名 + 基础属性 + 认证要求）。
 * 两者是 catalog 服务里两个不同的授权端点、两种不同的响应形状，
 * 所以本模块是<b>新开一个端口</b>而不是把 resolve 塞进既有端口：
 * 塞进去会让 10.2.1.2 的运单申报核对（一个高频写路径上的校验）
 * 与 10.5.1.2 的要素维护（一个低频写路径上的取数）共用一个方法体，
 * 而两者的失败语义不同（见下面「取不到就 503」那段的两个分支）。
 *
 * <p><b>共享的纪律</b>（逐字沿用 {@code CustomsAttributePort}）：
 *
 * <ul>
 *   <li><b>只经 catalog 的授权只读 HTTP 端点读</b>。不直连对方库、不建跨库视图、
 *       不建跨库 FK（AGENTS.md 模块边界纪律）。catalog 侧仍按已认证成员关系解析租户
 *       并强制 RLS。
 *   <li><b>租户不由本端口传递</b>：转发<b>调用方自己的</b> {@code Authorization} bearer。
 *       两道隔离（本服务 RLS + catalog RLS）。
 *   <li><b>取不到就 503，不返回空申报</b>。空申报会被当成「这票货不用报关」，
 *       而真实情况是商品中心挂了 —— 后果是一批货带着空申报单进了清关环节。
 * </ul>
 *
 * <p><b>两条失败语义刻意分开</b>：
 * 404 = **业务事实**（catalog 侧这个 SKU / 这个目的地没有可用版本）→ 由调用方决定拦不拦，
 * 本模块一律拦（申报必填），文案要说清是「没维护」而不是「查不到」；
 * 其余 4xx/5xx 与连接失败 = **服务不可达** → 503，文案说清是暂时不可达。
 * 两者混成一句「取不到申报信息」的话，运营会以为要去补维护，而真实原因可能只是商品中心重启。
 */
@Service
public class CustomsElementSetPort {

  /**
   * catalog {@code ResolvedDeclaration} 的**只读子集**。
   *
   * <p>刻意只声明本模块要用的字段，并逐个写明来源层（catalog 自己的注释里标了
   * MODULE_3_7_1_1 / MODULE_3_7_1_2 两层，这里沿用同一套标注，免得读代码的人
   * 误以为某一格是本服务算的）。
   */
  record ResolvedDeclarationView(
      UUID skuId,
      String skuCode,
      /** catalog 的 sku_name —— 它就是**销售标题**，规则 C1 的比对基准。 */
      String skuName,
      String destinationCountryCode,
      String destinationVersionRef,
      int destinationVersionNo,
      String declaredName,
      String declaredNameSource,
      String hsCode,
      String originCountryCode,
      String statutoryUnitCode,
      boolean preferentialOriginEligible,
      boolean preferentialApplicable,
      /** 两者都满足才 true（catalog 侧合成口径）。 */
      boolean preferentialGranted,
      String preferenceAgreement,
      DeclarationElementsView declarationElements,
      String declarationElementStatus) {}

  /** catalog 的 {@code DeclarationElementsObject}：同时带有序清单与 jsonb 键值对象。 */
  record DeclarationElementsView(List<ElementView> entries, java.util.Map<String, String> byCode) {}

  /** catalog 的一条基础申报要素。 */
  record ElementView(
      int ordinal, String elementCode, String elementName, String elementValue, boolean required) {}

  private final RestClient client;

  public CustomsElementSetPort(@Value("${CATALOG_URL:http://127.0.0.1:8082}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(8));
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  /**
   * 取「这个 SKU 发到这个目的地」此刻的合成申报资料。
   *
   * @param at 判定的时刻；{@code null} = 此刻。传时刻是为了回溯「这批货当时是按哪一版
   *     目的地版本报的」—— 与 catalog 该端点的语义一致（它的 {@code at} 同样可选）。
   * @param bearer 调用方的会话令牌，原样转发。
   * @throws ResponseStatusException 404 = 该 SKU / 该目的地没有生效的报关版本（业务事实）；
   *     503 = 商品中心不可达或返回异常（服务问题）。
   */
  public ElementSetUpstreamPreview preview(UUID skuId, String destination, OffsetDateTimeHolder at, String bearer) {
    var uri =
        java.net.URLEncoder.encode(destination == null ? "" : destination.trim().toUpperCase(java.util.Locale.ROOT),
            java.nio.charset.StandardCharsets.UTF_8);
    var query = new StringBuilder("/catalog/customs-declaration/resolve?skuId=").append(skuId)
        .append("&destination=").append(uri);
    if (at != null && at.instant != null) {
      query.append("&at=").append(java.net.URLEncoder.encode(at.instant.toString(),
          java.nio.charset.StandardCharsets.UTF_8));
    }
    try {
      ResolvedDeclarationView view =
          client.get().uri(query.toString()).header("Authorization", bearer)
              .retrieve().body(ResolvedDeclarationView.class);
      if (view == null) {
        throw unavailable("商品中心没有返回这个 SKU 的合成申报资料");
      }
      var elements = new ArrayList<ElementSetItemRow>();
      if (view.declarationElements() != null && view.declarationElements().entries() != null) {
        for (var e : view.declarationElements().entries()) {
          elements.add(
              new ElementSetItemRow(null, e.ordinal(), e.elementCode(), e.elementName(),
                  e.elementValue(), e.required()));
        }
      }
      return new ElementSetUpstreamPreview(
          view.skuId(),
          view.skuCode(),
          view.skuName(),
          view.destinationCountryCode(),
          view.destinationVersionRef(),
          view.destinationVersionNo(),
          view.declaredName(),
          view.declaredNameSource(),
          view.hsCode(),
          view.originCountryCode(),
          view.statutoryUnitCode(),
          view.preferentialOriginEligible(),
          view.preferentialApplicable(),
          view.preferentialGranted(),
          view.preferenceAgreement(),
          elements,
          view.declarationElementStatus());
    } catch (RestClientResponseException e) {
      if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
        // 业务事实：这个 SKU 在这个目的地**没有可用的报关版本**。
        // 文案必须指向「去维护」，而不是「查不到」—— 两者会让运营做完全不同的事。
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "SKU " + skuId + " 在目的国 " + destination
                + " 没有生效的报关版本：3.7.1.2 要求每个目的地单独维护一个版本，"
                + "且该目的地的基础报关属性要已审核通过。先在商品中心维护并发布该目的地的报关版本，"
                + "再回到这里建申报要素集（本模块不代维护，也不会退回基础属性凑一份申报资料 —— "
                + "那正是「用一套申报资料打通全球」）。");
      }
      if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
      }
      if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "当前身份无权读取商品中心的报关属性，申报要素暂时核不了");
      }
      throw unavailable("商品中心返回 " + e.getStatusCode().value() + "，申报口径暂时核不了");
    } catch (RestClientException e) {
      throw unavailable("商品中心暂时不可达");
    }
  }

  /** 只取销售标题（建集 / 改集时校验申报品名不得照搬它）。 */
  public String salesTitle(ElementSetUpstreamPreview preview) {
    return preview == null || preview.salesTitle() == null ? "" : preview.salesTitle();
  }

  /**
   * 判定时刻的持有者。
   *
   * <p>刻意做成一个单字段小类型而不是直接传 {@code OffsetDateTime}：可空的时刻参数
   * 在 Java 里很容易被写成「传 null 就用当前时间」，而这里必须让调用方**显式**决定
   * 是查此刻还是查某个历史时刻 —— 两者在申报追溯场景下是不同的问题。
   */
  public record OffsetDateTimeHolder(java.time.OffsetDateTime instant) {
    public static OffsetDateTimeHolder now() {
      return new OffsetDateTimeHolder(null);
    }

    public static OffsetDateTimeHolder at(java.time.OffsetDateTime instant) {
      return new OffsetDateTimeHolder(instant);
    }
  }

  private ResponseStatusException unavailable(String reason) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        reason
            + "：申报口径的唯一来源是 3.7.1.2 的授权只读接口"
            + "（catalog 服务，跨库视图与跨服务 SQL 均被 AGENTS.md 禁止），"
            + "本服务不会凭空生成一份申报口径");
  }
}
