package com.starcon.finance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 读取上游 **2 渠道与店铺（2.3.1.1 店铺列表）**事实的**跨服务只读端口**（channel 服务，端口 8083）。
 *
 * <p>蓝图 12.3.1.1 写明「上游 = 2 渠道与店铺」。结算单的**店铺**就是上游那份真值 ——
 * 结算周期、结算金额、到账状态全挂在一家具体的店铺上，店铺认错了整张单就废了。
 *
 * <p>⚠️ <b>跨服务只走授权 HTTP API</b>：AGENTS.md 的模块边界纪律禁止跨服务 SQL / 跨库 FK / 共享视图。
 * 本端口只读 channel 的 {@code GET /channel/shops}，不直连对方库、不建跨库视图、不建跨库外键。
 *
 * <p>⚠️ <b>租户不由本端口传递</b>：转发的是**调用方自己的** {@code Authorization} bearer，
 * channel 侧仍按已认证成员关系解析租户并强制 RLS。也就是说租户隔离是**两道**
 * （finance 侧 RLS + channel 侧 RLS），而不是「本服务替对方决定租户」。
 *
 * <p>用法与既有 {@code AdSpendPort}（读 channel）逐字同构：取不到就<b>显式失败</b>，
 * 绝不返回空集合冒充「上游没有店铺」—— 那会让现场以为这个租户一家店都没有。
 */
@Service
public class ChannelShopPort {

  /** 上游只读形状：{@code GET /channel/shops}（2.3.1.1 店铺列表）。 */
  static final String SHOPS_PATH = "/channel/shops";

  /** 上游一行店铺（只声明本模块真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record ChannelShopView(
      UUID id,
      @JsonIgnoreProperties(ignoreUnknown = true) String code,
      @JsonIgnoreProperties(ignoreUnknown = true) String name,
      @JsonIgnoreProperties(ignoreUnknown = true) String platformCode,
      @JsonIgnoreProperties(ignoreUnknown = true) String platformName,
      @JsonIgnoreProperties(ignoreUnknown = true) String status) {}

  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private final RestClient client;

  public ChannelShopPort(@Value("${CHANNEL_URL:http://127.0.0.1:8083}") String url) {
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
   * 拉上游店铺列表。
   *
   * @param bearer 调用方自己的会话（租户由 channel 侧按已认证成员关系解析）
   */
  public List<ChannelShopView> shops(String bearer) {
    try {
      List<ChannelShopView> result =
          client
              .get()
              .uri(SHOPS_PATH)
              .header("Authorization", bearer)
              .retrieve()
              .body(new org.springframework.core.ParameterizedTypeReference<>() {});
      if (result == null) {
        throw unavailable("channel 服务没有返回店铺列表");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED,
            "调用方无权读取上游店铺主数据（需要 channel 侧的 channel.shop.read 权限）",
            e);
      }
      throw unavailable("channel 服务返回 " + e.getStatusCode().value() + "：店铺主数据读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("channel 服务不可达");
    }
  }

  /** 上游不可用一律 503，理由写明「读不到」而不是「没有数据」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }
}
