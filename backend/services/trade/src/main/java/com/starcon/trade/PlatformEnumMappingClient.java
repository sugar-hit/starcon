package com.starcon.trade;

import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 2.1.2.1 平台枚举映射的**跨服务读取端口**（channel 服务 → trade 服务）。
 *
 * <p>蓝图 9.1.2.1 的输入句点名「平台枚举映射（2.1.2.1）」。实查集成头：那张表
 * （{@code platform_enum_mapping} / {@code platform_enum_override}）在
 * **channel 服务的独立库** {@code starcon_channel}，
 * <b>不是</b> trade 库。AGENTS.md 模块边界纪律明文：
 * <b>禁止跨服务 SQL / 跨库 FK / 共享视图</b>。
 *
 * <p>⇒ 本模块读不到那张表，只能走<b>授权 API</b>：channel 的
 * {@code GET /channel/platforms/{platformCode}/enums?kind={kind}}
 * （2.1.2.1 的生效映射 = 平台默认 + 本租户覆盖，租户覆盖优先）。
 *
 * <p><b>与 {@link ShipmentWritebackPort}（9.4.2.1 经 2 队列）逐字同构的纪律</b>：
 *
 * <ul>
 *   <li><b>租户不由本端口传递</b>：转发的是<b>调用方自己的</b> {@code Authorization}
 *       bearer，channel 侧仍按已认证成员关系解析租户并强制 RLS。租户隔离因此是
 *       <b>两道</b>（trade 侧 RLS + channel 侧 RLS），伪造 {@code X-Tenant-Id} 头
 *       在任何一侧都改不动租户。
 *   <li><b>取不到就显式失败</b>：channel 不可用或拒绝时抛
 *       {@link ResponseStatusException}，绝不把「没拉到」当成「没有映射」——
 *       后者会让订单状态机猜出一个内部状态码（见迁移第四节）。
 * </ul>
 */
@Service
public class PlatformEnumMappingClient {

  /**
   * channel 侧 {@code EnumMappingView} 的**本地形状**。
   *
   * <p><b>刻意不引用 channel 的类</b>：两个服务不共享编译单元，跨服务只共享
   * HTTP 契约。字段名逐个照抄 channel 的 {@code ChannelModels.EnumMappingView}，
   * 改字段名时两边一起改。
   */
  public record EnumMappingView(
      String id,
      String platformCode,
      String enumKind,
      String externalValue,
      String internalValue,
      /** {@code PLATFORM} = 平台默认（全局只读）；{@code TENANT} = 本租户覆盖。 */
      String source) {}

  private final RestClient client;

  public PlatformEnumMappingClient(@Value("${CHANNEL_URL:http://127.0.0.1:8083}") String url) {
    var factory =
        new JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    // 读超时给到 8 秒：映射是一次性的小查询，但仍必须有上限 ——
    // 没有上限时 channel 一慢就把本服务的请求线程全占住。
    factory.setReadTimeout(Duration.ofSeconds(8));
    this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  /**
   * 拉取某平台某类枚举的**生效映射**（平台默认 + 本租户覆盖）。
   *
   * @param bearer 调用方自己的 Authorization 头（租户与权限都由 channel 侧解析）
   * @param platformCode 平台码（如 AMAZON）
   * @param kind 映射种类（ORDER_STATUS / PAYMENT_METHOD / COMPONENT_TYPE）
   */
  public List<EnumMappingView> fetch(String bearer, String platformCode, String kind) {
    try {
      List<EnumMappingView> rows =
          client
              .get()
              .uri(
                  "/channel/platforms/{code}/enums?kind={kind}",
                  platformCode,
                  kind)
              .header("Authorization", bearer)
              .retrieve()
              .body(new ParameterizedTypeReference<List<EnumMappingView>>() {});
      return rows == null ? List.of() : rows;
    } catch (RestClientResponseException e) {
      throw new ResponseStatusException(
          e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403
              ? HttpStatus.FORBIDDEN
              : HttpStatus.SERVICE_UNAVAILABLE,
          "拉取 2.1.2.1 平台枚举映射失败（channel 返回 "
              + e.getStatusCode().value()
              + "）：映射拉不到时本模块不会猜状态码，请确认当前角色具备 channel.registry.read 权限且 channel 服务可用");
    } catch (RestClientException e) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "拉取 2.1.2.1 平台枚举映射失败：channel 服务暂时不可用，请稍后重试");
    }
  }
}
