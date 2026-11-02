package com.starcon.crm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 读取 **catalog 03 客户档案**（往来单位 party）的**跨服务只读端口**（catalog 服务，端口 8082）。
 *
 * <p>蓝图 15 卷开篇写明：「买家主档在 <b>catalog</b>（{@code party} {@code customer_profile}）」，
 * 而上游 9.1.1.1 订单里的 {@code customer_party_id} 指向的就是它
 * （trade 的 {@code sales_order_party_snapshot} 注释写死这件事）。
 * 15.3.1.1 职责里的「联系方式（脱敏）」在本仓**没有别的来源** ——
 * 订单事实里没有邮箱与电话，crm 自己的接触记录（15.3.2.2）也只存客户标识与姓名。
 *
 * <p>🔴 <b>联系方式是补充维度，不是核心事实</b>：
 * 上游订单事实（9 订单）读不到 ⇒ 整个刷新失败（一条客户档案都不落）；
 * 而 catalog 的联系方式读不到 ⇒ 该客户的联系方式留空，并在刷新运行记录里
 * <b>如实报出没取到几个</b> —— 绝不假装「这个客户没有邮箱」。
 *
 * <p><b>跨服务纪律</b>：本端口<b>只经 catalog 的授权 HTTP API</b>读，
 * 不直连对方库、不建跨库 FK、不建跨库视图。转发的是<b>调用方自己的</b>
 * {@code Authorization} bearer —— catalog 侧仍按已认证成员关系解析租户并强制 RLS，
 * 也就是说租户隔离是<b>两道</b>（crm 侧 RLS + catalog 侧 RLS）。
 *
 * <p>用法与既有 {@link CustomerFactsPort}（读 trade）、{@link MessageContextPort}（读 channel）
 * 逐字同构：取不到就<b>显式失败</b>，绝不返回空对象冒充「这个客户没有联系方式」。
 */
@Service
public class CustomerPartyPort {

  /** 上游只读形状：{@code GET /catalog/parties/{id}}。 */
  static final String PARTY_PATH = "/catalog/parties/";

  /**
   * 上游一行客户档案（只声明本模块真正用到的字段）。
   *
   * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)}：上游那页还带着
   * {@code roles} / {@code version} / {@code usable} 等若干字段，本模块用不到，
   * 但<b>不因为读少就崩</b>。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record PartyView(UUID id, String code, String name, String email, String phone, String status) {}

  /**
   * 一次联系方式查询的结果。
   *
   * @param found 上游确实有这个客户档案（与「档案存在但没留联系方式」是两回事）
   * @param unavailableReason 上游读不到时的原因（{@code found=false} 且档案不存在时为空串）
   */
  public record ContactLookup(
      boolean found, String email, String phone, String unavailableReason, String source) {

    /** 档案存在但没留任何联系方式。 */
    public boolean hasContact() {
      return found && email != null && !email.isBlank();
    }
  }

  private static final ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private final RestClient client;

  public CustomerPartyPort(@Value("${CATALOG_URL:http://127.0.0.1:8082}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(10));
    this.client =
        RestClient.builder()
            .baseUrl(url)
            .requestFactory(factory)
            .messageConverters(java.util.List.of(new MappingJackson2HttpMessageConverter(JSON)))
            .build();
  }

  /**
   * 读一份客户档案的联系方式。
   *
   * <p>三种结局，各不相同，**不混为一谈**：
   *
   * <ul>
   *   <li>档案存在 ⇒ {@code found=true}，邮箱 / 电话可能为空（人家没留）。</li>
   *   <li>档案不存在（404）⇒ {@code found=false} 且原因为空串 —— 这是**事实**，
   *       不是「读不到」。</li>
   *   <li>读不到（401 / 403 / 5xx / 网络不通）⇒ {@code found=false} 且**带原因** ——
   *       调用方据此在运行记录里如实报出。</li>
   * </ul>
   */
  public ContactLookup contact(UUID partyId, String bearer) {
    if (partyId == null) {
      return new ContactLookup(false, null, null, "上游订单没有带客户档案标识", "NONE");
    }
    try {
      PartyView view =
          client
              .get()
              .uri(PARTY_PATH + partyId)
              .header("Authorization", bearer)
              .retrieve()
              .body(PartyView.class);
      if (view == null) {
        return new ContactLookup(false, null, null, "catalog 服务没有返回客户档案", "NONE");
      }
      return new ContactLookup(true, view.email(), view.phone(), "", "CATALOG_PARTY");
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 404) {
        // 档案不存在 —— 这是事实，不是故障。
        return new ContactLookup(false, null, null, "", "NONE");
      }
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        return new ContactLookup(
            false, null, null, "调用方无权读取 catalog 客户档案（需要 catalog 侧读取权限）", "NONE");
      }
      return new ContactLookup(
          false, null, null, "catalog 服务返回 " + e.getStatusCode().value() + "：客户档案读不到", "NONE");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      return new ContactLookup(false, null, null, "catalog 服务不可达", "NONE");
    }
  }
}
