package com.starcon.crm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.time.OffsetDateTime;
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
 * 读取上游 **10.3.2.2 揽收与签收确认**的**跨服务只读端口**（channel 服务）。
 *
 * <p>蓝图 15.2.2.1 的上游是「10.3.2.2 签收」，而**签收的真值不在本服务**：
 * 它在 <b>channel</b> 服务的 {@code logistics_pickup_signoff_case}（V217，端口 8083）。
 * AGENTS.md 模块边界纪律禁止跨服务 SQL / 跨库 FK / 共享视图，所以本端口
 * <b>只经 channel 的授权 HTTP API</b>读，不直连对方库、不建跨库视图、不建跨库外键。
 *
 * <p>本端口提供两样东西：
 *
 * <ol>
 *   <li>{@link #deliveredCases} —— <b>已签收订单集合</b>：{@code GET
 *       /channel/logistics-pickup-signoff/cases?status=DELIVERED}。
 *       它回答「这票**已签收**没有」，并带出<b>签收时刻</b>（索评时点的起算点）
 *       与<b>订单号</b>（索评的对象）。
 *   <li>{@link #shops} —— <b>店铺目录</b>：{@code GET /channel/shops}。
 *       索评活动的目标店铺从这份**真实清单**里选，而不是前端写死的常量。
 * </ol>
 *
 * <p>租户<b>不</b>由本端口传递租户号：转发的是<b>调用方自己的</b>
 * {@code Authorization} bearer，channel 侧仍按已认证成员关系解析租户并强制 RLS。
 * 也就是说租户隔离是<b>两道</b>（crm 侧 RLS + channel 侧 RLS），
 * 而不是「本服务替对方决定租户」。
 *
 * <p>用法与 channel 侧既有的 {@code ShipmentTrackingQueryPort}（读 trade）、
 * {@code SellableQuantityQueryPort}（读 warehouse）逐字同构：取不到就<b>显式失败</b>，
 * 绝不返回空集合冒充「上游没有已签收订单」—— 那会让现场看到 0 票，
 * 照着空清单以为这批货根本没送到。
 *
 * <p>⚠️ 上游确认单**不带店铺维度，也不带买家维度**：
 * 店铺只能作为「本次索评活动的目标」由运营选（{@link #shops} 提供真实清单），
 * 买家标识由运营按店铺提供（15.3.1.1 客户档案未交付）。
 * 这两件事在 capabilities 里都显式标成 available=false，**不编**。
 */
@Service
public class SignoffDeliveredOrderPort {

  /** 上游只读形状：{@code GET /channel/logistics-pickup-signoff/cases}。 */
  static final String CASES_PATH = "/channel/logistics-pickup-signoff/cases";

  /** 上游只读形状：{@code GET /channel/shops}。 */
  static final String SHOPS_PATH = "/channel/shops";

  /** 上游分页上限：channel 侧把 size 钳在 200 以内。 */
  static final int PAGE_SIZE = 200;

  /**
   * 最多翻多少页（{@code 200 × 25 = 5000} 件）。
   *
   * <p>必须有上限：翻页是无界的，一个宽时间窗 + 大租户能把本服务的请求线程占满。
   * 触到上限时<b>如实报 truncated</b>（而不是悄悄少算）—— 批次报告里会写明
   * 「本次只扫描了前 N 件」。
   */
  static final int MAX_PAGES = 25;

  /**
   * 上游一行确认单（只声明本模块真正用到的字段）。
   *
   * <p>刻意读得少：读得越少，上游加字段时本模块越不容易被撑爆。
   *
   * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)}：上游那页还带着
   * {@code statusLabel} / {@code gapCode} / 若干 SLA 列，本模块用不到，
   * 但<b>不因为读少就崩</b>。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record DeliveredCaseView(
      UUID id,
      String caseNo,
      String sourceBatchNo,
      String masterWaybillNo,
      String carrierCode,
      UUID channelId,
      String salesOrderNo,
      String destinationCountry,
      OffsetDateTime handoverAt,
      OffsetDateTime deliveryConfirmedAt,
      String status,
      String deliveryState) {}

  /** 上游一页确认单的形状。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record DeliveredCasePage(
      List<DeliveredCaseView> items, int page, int size, long totalElements, int totalPages) {}

  /** 上游单件确认单详情（只取 {@code signoffCase} 那一段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record DeliveredCaseDetail(DeliveredCaseView signoffCase) {}

  /** 上游一行店铺（只声明本模块真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record ShopView(
      UUID id,
      String code,
      String name,
      String platformCode,
      String platformName,
      String operatingMode,
      String status) {}

  /**
   * 上游 JSON 的 mapper。
   *
   * <p>两处配置都不是可有可无的：{@code JavaTimeModule}（上游带 {@code deliveryConfirmedAt}，
   * OffsetDateTime），以及关掉 {@code FAIL_ON_UNKNOWN_PROPERTIES}
   * （上游<b>加字段</b>不应该把本模块撑爆；类型不匹配照样炸 —— 那种是真读错了，必须炸）。
   */
  private static final ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private final RestClient client;

  public SignoffDeliveredOrderPort(@Value("${CHANNEL_URL:http://127.0.0.1:8083}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    // 读超时给到 10 秒：一次批量扫描要连续翻多页，比单条读慢一截，
    // 但仍然必须有上限 —— 没有上限时 channel 一慢就把本服务的请求线程全占住。
    factory.setReadTimeout(Duration.ofSeconds(10));
    this.client =
        RestClient.builder()
            .baseUrl(url)
            .requestFactory(factory)
            .messageConverters(java.util.List.of(new MappingJackson2HttpMessageConverter(JSON)))
            .build();
  }

  /**
   * 拉一页**已签收**的确认单。
   *
   * <p>{@code status=DELIVERED} 是硬筛：上游只有签收确认过的件才会是这一档，
   * 而未签收的件（{@code PENDING_PICKUP} / {@code PICKED_UP} / {@code PICKUP_OVERDUE}）
   * <b>不能</b>进索评候选集 —— 没签收就索评等于骚扰还没收到货的买家。
   *
   * @param carrierCode 承运商，空白表示不限
   * @param batchNo 交运批次号，空白表示不限
   * @param keyword 关键字（运单号 / 订单号 / 批次号），空白表示不限
   */
  public DeliveredCasePage deliveredCases(
      String carrierCode, String batchNo, String keyword, int page, int size, String bearer) {
    try {
      DeliveredCasePage result =
          client
              .get()
              .uri(
                  uriBuilder -> {
                    var b = uriBuilder.path(CASES_PATH).queryParam("status", "DELIVERED");
                    if (carrierCode != null && !carrierCode.isBlank()) {
                      b.queryParam("carrierCode", carrierCode.trim());
                    }
                    if (batchNo != null && !batchNo.isBlank()) {
                      b.queryParam("batchNo", batchNo.trim());
                    }
                    if (keyword != null && !keyword.isBlank()) {
                      b.queryParam("keyword", keyword.trim());
                    }
                    return b.queryParam("page", Math.max(page, 0))
                        .queryParam("size", size <= 0 ? PAGE_SIZE : Math.min(size, PAGE_SIZE))
                        .build();
                  })
              .header("Authorization", bearer)
              .retrieve()
              .body(DeliveredCasePage.class);
      if (result == null || result.items() == null) {
        throw unavailable("channel 服务没有返回已签收确认单列表");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "调用方无权读取上游签收确认单", e);
      }
      throw unavailable("channel 服务返回 " + e.getStatusCode().value() + "：已签收订单集合读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("channel 服务不可达");
    }
  }

  /**
   * 拉**单件**确认单。
   *
   * <p>单条发起索评时用这个形状：它按 id 精确定位，**不依赖**列表接口的分页与关键字，
   * 所以「这一票到底是不是已签收」是一个确定答案，而不是「在前 N 条里找一找」。
   */
  public DeliveredCaseView caseDetail(UUID caseId, String bearer) {
    try {
      DeliveredCaseDetail result =
          client
              .get()
              .uri(CASES_PATH + "/" + caseId)
              .header("Authorization", bearer)
              .retrieve()
              .body(DeliveredCaseDetail.class);
      if (result == null || result.signoffCase() == null) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND, "上游签收确认单不存在：" + caseId);
      }
      return result.signoffCase();
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 404) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND, "上游签收确认单不存在：" + caseId, e);
      }
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "调用方无权读取上游签收确认单", e);
      }
      throw unavailable("channel 服务返回 " + e.getStatusCode().value() + "：签收确认单读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("channel 服务不可达");
    }
  }

  /**
   * 拉店铺清单（索评活动的目标店铺从这份真实清单里选）。
   *
   * <p>取不到就<b>显式失败</b>：返回一个写死的店铺列表会让运营对着不存在的店铺发起索评。
   */
  public List<ShopView> shops(String bearer) {
    try {
      List<ShopView> result =
          client.get().uri(SHOPS_PATH).header("Authorization", bearer).retrieve().body(
              new org.springframework.core.ParameterizedTypeReference<List<ShopView>>() {});
      if (result == null) {
        throw unavailable("channel 服务没有返回店铺清单");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "调用方无权读取店铺清单", e);
      }
      throw unavailable("channel 服务返回 " + e.getStatusCode().value() + "：店铺清单读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("channel 服务不可达");
    }
  }

  /** 上游不可用一律 503，理由写明「读不到」而不是「没有数据」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }
}
