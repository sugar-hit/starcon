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
 * 读取上游 3.6.1.2 角色档案的<b>跨服务端口</b> —— 经销商「是不是一个有效的客户角色往来单位」这个事实的<b>唯一来源</b>。
 *
 * <p>蓝图 9.7.1.2 的关系句写死「上游 = 3.6.1.2」，而客户角色档案
 * （{@code party_customer_profile}）在 <b>catalog 服务的库</b>里。AGENTS.md 模块边界纪律禁止跨服务 SQL / FK /
 * 共享视图，所以本端口<b>只经 catalog 的授权只读端点</b>读，
 * 不直连对方库、不建跨库视图、不建跨库外键。
 *
 * <p>用法与 {@link ShipmentHandoverQueryPort}（读 warehouse）、{@link PackingQueryPort}（读 warehouse）逐字同构 ——
 * 跨服务读一律走一个 port 类，取不到就<b>显式失败</b>，绝不返回空集合冒充
 * 「这个租户一个经销商都没有」：空清单会被运营读成「我们还没签任何经销商」，
 * 而真实情况是商品主数据服务挂了。
 *
 * <p>用 3.6.1.2（catalog V27/V30）已交付的只读形状：
 *
 * <ul>
 *   <li>{@code GET /catalog/parties/candidates?roleType=CUSTOMER} —— 具备 ACTIVE 客户角色的往来单位；</li>
 *   <li>{@code GET /catalog/parties/{id}/customer-profile} —— 单个主体的客户角色档案。</li>
 * </ul>
 *
 * <p><b>为什么只取 CUSTOMER 角色</b>：蓝图 3.6 的实体目录写死「经销商是往来单位的一种角色」，
 * 所以本模块<b>不建新的经销商主体</b>，只在一个已存在的 CUSTOMER 角色往来单位上挂授权。
 * 把 SUPPLIER / CARRIER 也拉进来，等于允许给一个供应商开经销授权 ——
 * 那会让「这个主体凭什么能卖我们的货」这个问题在数据上无法回答。
 *
 * <p>租户<b>不</b>由本端口传递租户号：转发的是<b>调用方自己的</b>
 * {@code Authorization} bearer，catalog 侧仍按已认证成员关系解析租户并强制 RLS。
 * 也就是说租户隔离是<b>两道</b>（trade 侧 RLS + catalog 侧 RLS），
 * 而不是「本服务替对方决定租户」。
 */
@Service
public class DealerPartyQueryPort {

  /**
   * catalog 侧的往来单位候选行（字段名逐字对齐 3.6.1.1 的 {@code PartyRow} 契约）。
   *
   * <p>刻意只声明本模块真正用到的字段：读得越少，跨服务契约越不容易在对方加字段时把本模块撑爆。
   */
  record PartyView(UUID id, String code, String name, String partyType, String status) {}

  /**
   * catalog 侧的客户角色档案（3.6.1.2）。
   *
   * <p>{@code defaultCreditLimit} 刻意保留：它可能为 {@code null}，而「没设额度」与「额度为 0」在下游是完全不同的两件事
   * （前者放行、后者拒绝），把两者折叠会让本模块无从判断一个经销商能不能继续赊销。
   */
  record CustomerProfileView(
      UUID partyId,
      String partyCode,
      String partyName,
      String customerLevel,
      String defaultCurrency,
      BigDecimal defaultCreditLimit,
      int paymentTermsDays,
      long version) {}

  private final RestClient client;

  public DealerPartyQueryPort(@Value("${CATALOG_URL:http://127.0.0.1:8082}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    // 读超时给到 8 秒：拉一批候选要逐条取角色档案，比单条读慢一截，
    // 但仍然必须有上限 —— 没有上限时商品主数据一慢就把本服务的请求线程全占住。
    factory.setReadTimeout(Duration.ofSeconds(8));
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  /** 拉一批具备 ACTIVE 客户角色的往来单位（建授权时的经销商候选）。 */
  public List<PartyView> customerCandidates(String keyword, String bearer) {
    try {
      List<PartyView> result =
          client
              .get()
              .uri(
                  uriBuilder -> {
                    var b = uriBuilder.path("/catalog/parties/candidates").queryParam("roleType", "CUSTOMER");
                    if (keyword != null && !keyword.isBlank()) {
                      b.queryParam("keyword", keyword.trim());
                    }
                    return b.build();
                  })
              .header("Authorization", bearer)
              .retrieve()
              .body(new org.springframework.core.ParameterizedTypeReference<List<PartyView>>() {});
      if (result == null) {
        throw unavailable("商品主数据服务没有返回客户角色候选清单");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
      }
      if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "当前身份无权读取往来单位候选，经销商暂时同步不了");
      }
      throw unavailable("商品主数据服务返回 " + e.getStatusCode().value() + "，经销商暂时同步不了");
    } catch (RestClientException e) {
      throw unavailable("商品主数据服务暂时不可达");
    }
  }

  /** 取一个主体的客户角色档案（建授权时确认它确实有 CUSTOMER 角色）。 */
  public CustomerProfileView customerProfile(UUID partyId, String bearer) {
    try {
      CustomerProfileView profile =
          client
              .get()
              .uri("/catalog/parties/" + partyId + "/customer-profile")
              .header("Authorization", bearer)
              .retrieve()
              .body(CustomerProfileView.class);
      if (profile == null) {
        throw unavailable("商品主数据服务没有返回这个主体的客户角色档案");
      }
      return profile;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND, "该往来单位没有客户角色档案，不能作为经销商：" + partyId);
      }
      if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
      }
      if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "当前身份无权读取该客户角色档案，经销商暂时同步不了");
      }
      throw unavailable("商品主数据服务返回 " + e.getStatusCode().value() + "，经销商暂时同步不了");
    } catch (RestClientException e) {
      throw unavailable("商品主数据服务暂时不可达");
    }
  }

  /**
   * 不可达一律 503，并把「为什么」如实报出。
   *
   * <p>刻意<b>不</b>返回空清单冒充「这个租户还没有经销商」：空清单会被当成
   * 「可以放心签新的经销商」而让运营推进，真实情况是商品主数据挂了。
   */
  private ResponseStatusException unavailable(String reason) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        reason
            + "：经销商主体的唯一来源是 3.6.1.2 的授权只读接口"
            + "（catalog 服务，跨库视图与跨服务 SQL 均被 AGENTS.md 禁止），"
            + "本服务不会凭空生成一份经销商清单");
  }
}
