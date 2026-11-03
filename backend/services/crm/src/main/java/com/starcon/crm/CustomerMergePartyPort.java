package com.starcon.crm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 15.3.1.3 跨店合并读取 **catalog 客户档案地址**的跨服务只读端口（catalog 服务，端口 8082）。
 *
 * <p><b>为什么需要它</b>：蓝图 15.3.1.3 点名四种匹配依据，其中「<b>姓名 + 地址</b>」
 * 需要地址，而地址<b>不在</b> {@code crm_customer_profile} 里 ——
 * 15.3.1.1 的客户主档只存了客户标识、来源店铺、买家名快照、联系方式与统计口径，
 * <b>没有地址列</b>（地址的归属是 catalog 的往来单位档案，见 catalog 的
 * {@code /catalog/parties/{id}/addresses}）。
 * 不引入这个端口，「姓名+地址」这一档就<b>只能空谈</b>，而它是蓝图点名的依据之一。
 *
 * <p><b>跨服务纪律</b>：只经 catalog 的<b>授权 HTTP API</b>读，
 * 不直连对方库、不建跨库 FK、不建跨库视图。转发的是<b>调用方自己的</b> {@code Authorization} bearer ——
 * catalog 侧仍按已认证成员关系解析租户并强制 RLS，租户隔离是<b>两道</b>。
 *
 * <p><b>读不到就说读不到</b>（照抄 {@link CustomerPartyPort} 的口径）：
 * 三种结局互不混淆 —— 档案存在但没留地址（{@code available=true} 且地址列表为空）、
 * 档案不存在（404，{@code available=false} 且原因为空串）、读不到（401/403/5xx/网络不通，
 * {@code available=false} 且<b>带原因</b>）。调用方据此在运行台账里<b>如实报出</b>，
 * 绝不把「没取到」当成「这个人没有地址」—— 那会把「同名不同人」误判成「同一个人」。
 */
@Service
public class CustomerMergePartyPort {

  /** 上游只读形状：{@code GET /catalog/parties/{id}/addresses}。 */
  static final String ADDRESS_PATH = "/catalog/parties/";

  private static final ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .build();

  /**
   * 一次地址查询的结果。
   *
   * @param available 上游是否可读（false = 读不到，原因在 {@code unavailableReason}）
   * @param addresses 可比较的地址形态（{@code oneLine}，去重、去空）
   * @param unavailableReason 读不到时的原因（可读时为空串）
   * @param source 取值来源（CATALOG_PARTY / NONE）
   */
  public record AddressLookup(
      boolean available, List<String> addresses, String unavailableReason, String source) {

    /** 上游可读，且至少有一个可比地址。 */
    public boolean hasAddress() {
      return available && !addresses.isEmpty();
    }
  }

  private final RestClient client;

  public CustomerMergePartyPort(@Value("${CATALOG_URL:http://127.0.0.1:8082}") String url) {
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
   * 读一个客户档案的地址列表（只取 {@code oneLine} 这一种可比形态）。
   *
   * @param partyId catalog 往来单位 id（来自 {@code crm_customer_profile.customer_party_id}）
   */
  public AddressLookup addresses(UUID partyId, String bearer) {
    if (partyId == null) {
      return new AddressLookup(false, List.of(), "上游订单没有带客户档案标识", "NONE");
    }
    try {
      // 🔴 取 **JsonNode** 而不是 String：上游回的是 JSON **对象**
      //   （{"items":[...]}），Jackson 没法把一个 JSON 对象读成 String ——
      //   那样会抛 "Cannot deserialize value of type java.lang.String from Object value"。
      JsonNode node =
          client
              .get()
              .uri(ADDRESS_PATH + partyId + "/addresses?page=0&size=50")
              .header("Authorization", bearer)
              .retrieve()
              .body(JsonNode.class);
      if (node == null) {
        return new AddressLookup(false, List.of(), "catalog 服务没有返回客户地址", "NONE");
      }
      return new AddressLookup(true, parseAddresses(node), "", "CATALOG_PARTY");
    } catch (RestClientResponseException e) {
      int code = e.getStatusCode().value();
      if (code == 404) {
        // 档案不存在 —— 这是**事实**，不是故障。
        return new AddressLookup(false, List.of(), "", "NONE");
      }
      if (code == 401 || code == 403) {
        return new AddressLookup(
            false, List.of(), "调用方无权读取 catalog 客户地址（需要 catalog 侧读取权限）", "NONE");
      }
      return new AddressLookup(false, List.of(), "catalog 服务返回 " + code + "：客户地址读不到", "NONE");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      return new AddressLookup(false, List.of(), "catalog 服务不可达", "NONE");
    }
  }

  /**
   * 从上游响应里取出可比地址。
   *
   * <p>刻意用 {@link JsonNode} 而不是强绑上游 DTO：catalog 的地址 DTO 有二十来个字段
   * （版本、用途、选择阻断……），本模块只关心 {@code oneLine}。
   * 强绑会让上游加字段 / 改字段直接**编译失败**，而这里只需要「取不到就说取不到」。
   */
  static List<String> parseAddresses(JsonNode root) {
    List<String> out = new ArrayList<>();
    if (root == null) return List.of();
    JsonNode items = root.path("items");
    if (!items.isArray()) items = root.path("rows");
    if (!items.isArray()) return List.of();
    for (JsonNode item : items) {
      if (item.path("deleted").asBoolean(false)) continue;
      String oneLine = item.path("oneLine").asText("");
      if (oneLine.isBlank()) continue;
      if (!out.contains(oneLine)) out.add(oneLine);
    }
    return List.copyOf(new LinkedHashSet<>(out));
  }
}
