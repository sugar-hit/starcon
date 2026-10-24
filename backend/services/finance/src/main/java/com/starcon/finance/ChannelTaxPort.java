package com.starcon.finance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.time.LocalDate;
import java.math.BigDecimal;
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
 * 读取上游 **channel（2 渠道与店铺 / 16 合规）**事实的**跨服务只读端口**。
 *
 * <p>本端口只有两个用途，都与 12.6.1.1「税率配置」的边界直接相关：
 *
 * <ol>
 *   <li><b>辖区码表</b>（{@code GET /channel/tax-registration/jurisdictions}）：
 *       「目的国/地区」的值域由 channel 16.3.1.1 的受控码表决定。
 *       本模块**不复制**那张码表（复制 = 两个真值源）、**不建**指向它的外键
 *       （跨库 FK 是模块边界纪律明令禁止的），只在**落库前**经授权只读 API 校验
 *       「这个辖区码真实存在」⇒ 辖区码**不是自由文本**。</li>
 *   <li><b>州销售税规则</b>（{@code GET /channel/sales-tax/rate-rules}）：
 *       🔴 美国**州级销售税**归 channel 16.3.2.3 维护，本模块**不重复建表**。
 *       页面要展示州销售税时**只读**这一份，并如实标明来源模块。</li>
 * </ol>
 *
 * <p>⚠️ <b>跨服务只走授权 HTTP API</b>：AGENTS.md 的模块边界纪律禁止跨服务 SQL / 跨库 FK /
 * 共享视图。本端口不直连对方库、不建跨库视图、不建跨库外键。
 *
 * <p>⚠️ <b>租户不由本端口传递</b>：转发的是**调用方自己的** {@code Authorization} bearer，
 * channel 侧仍按已认证成员关系解析租户并强制 RLS。也就是说租户隔离是**两道**
 * （finance 侧 RLS + channel 侧 RLS），而不是「本服务替对方决定租户」。
 *
 * <p>用法与既有 {@link ChannelShopPort}（读 channel 店铺）逐字同构：取不到就<b>显式失败</b>，
 * 绝不返回空集合冒充「上游没有辖区」—— 那会让现场以为这个租户一个辖区都没有。
 */
@Service
public class ChannelTaxPort {

  /** 上游只读形状：受控辖区码表（16.3.1.1）。 */
  static final String JURISDICTIONS_PATH = "/channel/tax-registration/jurisdictions";

  /** 上游只读形状：州销售税规则清单（16.3.2.3）。 */
  static final String STATE_RATE_RULES_PATH = "/channel/sales-tax/rate-rules";

  /** 上游一行辖区（只声明本模块真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record ChannelJurisdictionView(
      String code,
      @JsonIgnoreProperties(ignoreUnknown = true) String name,
      @JsonIgnoreProperties(ignoreUnknown = true) String region,
      @JsonIgnoreProperties(ignoreUnknown = true) String countryCode,
      @JsonIgnoreProperties(ignoreUnknown = true) String subdivisionCode,
      @JsonIgnoreProperties(ignoreUnknown = true) String taxRegime,
      @JsonIgnoreProperties(ignoreUnknown = true) boolean vatRequired,
      @JsonIgnoreProperties(ignoreUnknown = true) boolean requiresEpr,
      @JsonIgnoreProperties(ignoreUnknown = true) String defaultFilingCycle,
      @JsonIgnoreProperties(ignoreUnknown = true) String derivationScope) {}

  /** 上游辖区码表响应。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record ChannelJurisdictionList(
      @JsonIgnoreProperties(ignoreUnknown = true) List<ChannelJurisdictionView> items,
      @JsonIgnoreProperties(ignoreUnknown = true) Integer total,
      @JsonIgnoreProperties(ignoreUnknown = true) String note) {}

  /** 上游一版州销售税规则。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record ChannelStateRateRuleView(
      String id,
      @JsonIgnoreProperties(ignoreUnknown = true) String stateCode,
      @JsonIgnoreProperties(ignoreUnknown = true) String stateName,
      @JsonIgnoreProperties(ignoreUnknown = true) Integer ruleVersion,
      @JsonIgnoreProperties(ignoreUnknown = true) LocalDate effectiveFrom,
      @JsonIgnoreProperties(ignoreUnknown = true) LocalDate effectiveTo,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal baseRate,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal thresholdAmount,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal exemptionAmount,
      @JsonIgnoreProperties(ignoreUnknown = true) boolean marketplaceFacilitator,
      @JsonIgnoreProperties(ignoreUnknown = true) String filingCycle,
      @JsonIgnoreProperties(ignoreUnknown = true) String status,
      @JsonIgnoreProperties(ignoreUnknown = true) String note) {}

  /** 上游州销售税规则清单响应。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record ChannelStateRateRulePage(
      @JsonIgnoreProperties(ignoreUnknown = true) List<ChannelStateRateRuleView> items,
      @JsonIgnoreProperties(ignoreUnknown = true) Long total,
      @JsonIgnoreProperties(ignoreUnknown = true) Integer page,
      @JsonIgnoreProperties(ignoreUnknown = true) Integer size,
      @JsonIgnoreProperties(ignoreUnknown = true) String note) {}

  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private final RestClient client;

  public ChannelTaxPort(@Value("${CHANNEL_URL:http://127.0.0.1:8083}") String url) {
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
   * 拉上游受控辖区码表。
   *
   * @param bearer 调用方自己的会话（租户由 channel 侧按已认证成员关系解析）
   */
  public List<ChannelJurisdictionView> jurisdictions(String bearer) {
    try {
      ChannelJurisdictionList result =
          client
              .get()
              .uri(JURISDICTIONS_PATH)
              .header("Authorization", bearer)
              .retrieve()
              .body(ChannelJurisdictionList.class);
      if (result == null || result.items() == null) {
        throw unavailable("channel 服务没有返回辖区码表");
      }
      return result.items();
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED,
            "调用方无权读取上游辖区码表（需要 channel 侧的 channel.operation.read 权限）",
            e);
      }
      throw unavailable("channel 服务返回 " + e.getStatusCode().value() + "：辖区码表读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("channel 服务不可达");
    }
  }

  /**
   * 拉上游州销售税规则（16.3.2.3，本模块**只读引用**）。
   *
   * @param bearer 调用方自己的会话
   * @param stateCode 州码（留空 = 全部）
   * @param status 状态（留空 = 全部）
   */
  public ChannelStateRateRulePage stateRateRules(String bearer, String stateCode, String status, int page, int size) {
    var uri =
        (StringBuilder)
            new StringBuilder(STATE_RATE_RULES_PATH)
                .append("?page=")
                .append(Math.max(page, 0))
                .append("&size=")
                .append(Math.min(Math.max(size, 1), 200));
    if (stateCode != null && !stateCode.isBlank()) {
      uri.append("&stateCode=").append(java.net.URLEncoder.encode(stateCode.trim(), java.nio.charset.StandardCharsets.UTF_8));
    }
    if (status != null && !status.isBlank()) {
      uri.append("&status=").append(java.net.URLEncoder.encode(status.trim(), java.nio.charset.StandardCharsets.UTF_8));
    }
    try {
      ChannelStateRateRulePage result =
          client
              .get()
              .uri(uri.toString())
              .header("Authorization", bearer)
              .retrieve()
              .body(ChannelStateRateRulePage.class);
      if (result == null || result.items() == null) {
        throw unavailable("channel 服务没有返回州销售税规则");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED,
            "调用方无权读取上游州销售税规则（需要 channel 侧的 channel.operation.read 权限）",
            e);
      }
      throw unavailable("channel 服务返回 " + e.getStatusCode().value() + "：州销售税规则读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("channel 服务不可达");
    }
  }

  /** 上游不可用一律 503，理由写明「读不到」而不是「没有数据」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }

  /** 供能力声明引用的上游模块号。 */
  static final String JURISDICTION_OWNER_MODULE = "16.3.1.1";

  /** 供能力声明引用的上游模块号（州销售税）。 */
  static final String STATE_SALES_TAX_OWNER_MODULE = "16.3.2.3";

  /** 供能力声明引用：州销售税规则的 id 形状（上游是 uuid 字符串）。 */
  static String ruleId(String raw) {
    if (raw == null) return null;
    try {
      return UUID.fromString(raw).toString();
    } catch (IllegalArgumentException e) {
      return raw;
    }
  }
}
