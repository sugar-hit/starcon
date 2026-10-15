package com.starcon.channel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 读取上游 3.6.1.1 往来单位 / 3.6.1.2 角色档案的**跨服务只读端口**。
 *
 * <p>🔴 蓝图加粗句「**承运商是往来单位的一种角色，不在此处重复建档**」的物理落点
 * 就是本端口：承运商的**身份主档在 catalog 服务**
 * （{@code party} + {@code party_role} + {@code party_carrier_profile}，
 * 迁移 {@code V27__party_profile.sql} / {@code V30__party_role_profiles.sql}），
 * 本模块只读它、只投影它，<b>一条 SQL 都不碰它的库</b>。
 *
 * <p>⚠️ 一处必须写明的事实纠正（与派单说明里的写法不同，以代码为准）：
 * 上游**不在 identity 服务**。identity 那边是 17.1.2.1「法人档案」
 * （{@code V5__legal_entity.sql}，{@code /legal-entities}），那是「我们自己这个
 * 开票 / 记账主体」，与「承运商这个对手方」是两回事 —— 照 /legal-entities 去读
 * 会把**我方法人**当成**上游承运商**，那是一个能跑通但业务上完全错误的接法。
 *
 * <p>⚠️ AGENTS.md 模块边界纪律禁止跨服务 SQL / FK / 共享视图，所以本端口
 * <b>只经 catalog 的授权 HTTP API</b>读，不直连对方库、不建跨库视图、不建跨库外键。
 *
 * <p>租户<b>不</b>由本端口传递租户号：转发的是<b>调用方自己的</b>
 * {@code Authorization} bearer，catalog 侧仍按已认证成员关系解析租户并强制 RLS。
 * 租户隔离因此是<b>两道</b>（channel 侧 RLS + catalog 侧 RLS），
 * 而不是「本服务替对方决定租户」。
 *
 * <p>用法与 10.3.2.1 的 {@link PendingShipmentQueryPort}（读 trade）逐字同构：
 * 取不到就<b>显式失败</b>，绝不返回空集合冒充「这个租户一个承运商都没有」——
 * 那会让运营以为物流侧从没配过承运商，而真相是「我们查不到」。
 */
@Service
public class CarrierProfileQueryPort {
  /** 上游只读形状：承运商身份的来源是往来单位里挂了 CARRIER 角色的那些主体。 */
  static final String CARRIERS_PATH = "/catalog/parties";

  /** 上游角色档案形状（拿 interface_type / account_ref / service_capabilities）。 */
  static final String CARRIER_PROFILE_PATH = "/catalog/parties/{id}/carrier-profile";

  /**
   * 上游一页带 CARRIER 角色的往来单位。
   *
   * <p>刻意<b>只声明本模块用到的字段</b>：读得越少，上游加字段时本模块越不容易被撑爆
   * （与 {@link PendingShipmentQueryPort} 同款理由）。{@code @JsonIgnoreProperties}
   * 是为了在上游形状变胖时不炸 —— 上游多返回的字段本模块用不上。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PartyPage(List<PartyRow> items, long total, int page, int size) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PartyRow(
      UUID id,
      String code,
      String name,
      String status,
      List<String> roles,
      boolean usable,
      String usableBlockCode) {}

  /**
   * 上游承运商角色档案（3.6.1.2 的读出口）。
   *
   * <p>{@code profileFound=false} 表示「指派了承运商身份但还没建档案」——
   * 它与「主体不存在」（404）是两回事，投影时如实区分，不替上游编造接口类型。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CarrierProfileView(
      UUID partyId,
      UUID roleId,
      String roleStatus,
      boolean profileFound,
      String interfaceType,
      String accountRef,
      List<String> serviceCapabilities) {}

  private final RestClient client;

  public CarrierProfileQueryPort(@Value("${CATALOG_URL:http://127.0.0.1:8082}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    // 读超时 10 秒：一次同步要逐个主体穿透取角色档案，比单条读慢一截，
    // 但仍然必须有上限 —— 没有上限时 catalog 一慢就把本服务的请求线程全占住。
    factory.setReadTimeout(Duration.ofSeconds(10));
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  /**
   * 拉一页挂了 CARRIER 角色的往来单位。
   *
   * <p>用 {@code roleType=CARRIER} 让**上游**去筛而不是拉全量回来本地筛：
   * 上游的 {@code party_role} 上有真实索引，而「取全量再在 Java 里 filter」
   * 会让第 1 页里出现一堆与承运商无关的供应商和客户 —— 那是假分页的一种。
   */
  public PartyPage carrierParties(int page, int size, String bearer) {
    try {
      PartyPage result =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(CARRIERS_PATH)
                          .queryParam("roleType", "CARRIER")
                          .queryParam("page", page)
                          .queryParam("size", size)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(PartyPage.class);
      if (result == null || result.items() == null) {
        throw unavailable("catalog 服务没有返回往来单位列表");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "调用方无权读取上游承运商身份", e);
      }
      throw unavailable("catalog 服务返回 " + e.getStatusCode().value() + "：承运商身份读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("catalog 服务不可达");
    }
  }

  /** 取单个主体的承运商角色档案（接口类型 / 账号 / 服务能力）。 */
  public CarrierProfileView carrierProfile(UUID partyId, String bearer) {
    try {
      CarrierProfileView result =
          client
              .get()
              .uri(CARRIER_PROFILE_PATH.replace("{id}", partyId.toString()))
              .header("Authorization", bearer)
              .retrieve()
              .body(CarrierProfileView.class);
      if (result == null) {
        throw unavailable("catalog 服务没有返回承运商角色档案");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "调用方无权读取上游承运商角色档案", e);
      }
      throw unavailable("catalog 服务返回 " + e.getStatusCode().value() + "：承运商角色档案读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("catalog 服务不可达");
    }
  }

  /** 上游不可用一律 503，理由写明「读不到」而不是「没有数据」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }
}
