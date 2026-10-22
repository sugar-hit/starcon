package com.starcon.crm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
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
 * 读取上游 **支付状态观察值**的**跨服务只读端口**（trade 服务，端口 8085）。
 *
 * <p>🔴 **为什么需要这个端口**：蓝图 15.5.2.1 的催付点是「**催付（未付款订单）**」，
 * 而「未付款」这个事实**不在** {@link CustomerFactsPort} 读到的订单清单里 ——
 * 实查 trade 服务的 {@code sales_order} **没有 payment_status 列**，
 * {@code GET /trade/sales-orders/list} 返回的 {@code paymentStatus} **恒为 null**
 * （那是 12 财务的缺口，不是本模块能补的）。
 *
 * <p>trade 服务里**确实存在**支付状态的观察值：{@code order_address_check}
 * （V210）把「调用方观察到的支付状态」连同**来源**（{@code PLATFORM_CALLBACK} /
 * {@code TENANT_REPORTED} / {@code FINANCE_LEDGER}）与**观察时刻**一起存着，
 * 并对外只读暴露在 {@code GET /trade/order-address-payment-check/checks}。
 * 本端口读的就是它。
 *
 * <p>⚠️ <b>「没有观察值」不等于「已付款」，也不等于「未付款」</b>：
 * 没登记过支付状态的订单，本模块**不纳入催付**（并如实计数为「支付状态未知」）——
 * 把「没量到」当成「未付款」去催款，是会引发投诉与平台处罚的一类事故。
 *
 * <p>租户<b>不</b>由本端口传递租户号：转发的是<b>调用方自己的</b>
 * {@code Authorization} bearer，trade 侧仍按已认证成员关系解析租户并强制 RLS。
 * 也就是说租户隔离是<b>两道</b>（crm 侧 RLS + trade 侧 RLS），
 * 而不是「本服务替对方决定租户」。
 *
 * <p>用法与既有 {@link CustomerFactsPort} / {@link SignoffDeliveredOrderPort} 逐字同构：
 * 取不到就<b>显式失败</b>，绝不返回空集合冒充「上游没有数据」——
 * 那会让现场看到 0 位待催付的买家，照着空名单以为这批订单都已付清。
 */
@Service
public class OrderPaymentStatusPort {

  /** 上游只读形状：{@code GET /trade/order-address-payment-check/checks}。 */
  static final String CHECKS_PATH = "/trade/order-address-payment-check/checks";

  /** 上游分页上限：trade 侧把 size 钳在 200 以内。 */
  static final int PAGE_SIZE = 200;

  /**
   * 最多翻多少页（{@code 200 × 25 = 5000} 件）。
   *
   * <p>必须有上限：翻页是无界的，一个宽时间窗 + 大租户能把本服务的请求线程占满。
   * 触到上限时<b>如实报 truncated</b>（而不是悄悄少算）。
   */
  static final int MAX_PAGES = 25;

  /**
   * 上游一行支付校验（只声明本模块真正用到的字段）。
   *
   * <p>刻意读得少：读得越少，上游加字段时本模块越不容易被撑爆。
   *
   * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)}：上游那页还带着
   * 收货地址快照、风险等级、若干判定计数等列，本模块用不到，
   * 但<b>不因为读少就崩</b>。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record PaymentCheckView(
      UUID id,
      UUID orderId,
      String orderNo,
      String orderSource,
      BigDecimal orderAmount,
      OffsetDateTime orderSubmittedAt,
      String paymentMethod,
      String paymentStatus,
      String paymentStatusSource,
      OffsetDateTime paymentStatusObservedAt) {}

  /** 上游一页支付校验的形状。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record PaymentCheckPage(List<PaymentCheckView> items, long total, int page, int size, int totalPages) {}

  /**
   * 上游 JSON 的 mapper。
   *
   * <p>两处配置都不是可有可无的：{@code JavaTimeModule}（上游带
   * {@code paymentStatusObservedAt}，OffsetDateTime），以及关掉
   * {@code FAIL_ON_UNKNOWN_PROPERTIES}（上游<b>加字段</b>不应该把本模块撑爆；
   * 类型不匹配照样炸 —— 那种是真读错了，必须炸）。
   */
  private static final ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private final RestClient client;

  public OrderPaymentStatusPort(@Value("${TRADE_URL:http://127.0.0.1:8085}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    // 读超时给到 10 秒：一次批量扫描要连续翻多页，比单条读慢一截，
    // 但仍然必须有上限 —— 没有上限时 trade 一慢就把本服务的请求线程全占住。
    factory.setReadTimeout(Duration.ofSeconds(10));
    this.client =
        RestClient.builder()
            .baseUrl(url)
            .requestFactory(factory)
            .messageConverters(java.util.List.of(new MappingJackson2HttpMessageConverter(JSON)))
            .build();
  }

  /**
   * 拉一页支付状态观察值。
   *
   * @param keyword 订单号关键字，空白表示不限
   */
  public PaymentCheckPage checks(String keyword, int page, String bearer) {
    try {
      PaymentCheckPage result =
          client
              .get()
              .uri(
                  uriBuilder -> {
                    var b = uriBuilder.path(CHECKS_PATH).queryParam("page", Math.max(page, 0));
                    if (keyword != null && !keyword.isBlank()) {
                      b.queryParam("keyword", keyword.trim());
                    }
                    return b.queryParam("size", PAGE_SIZE).build();
                  })
              .header("Authorization", bearer)
              .retrieve()
              .body(PaymentCheckPage.class);
      if (result == null || result.items() == null) {
        throw unavailable("trade 服务没有返回支付状态清单");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED, "调用方无权读取上游支付状态观察值（需要 trade 侧的订单读取权限）", e);
      }
      throw unavailable("trade 服务返回 " + e.getStatusCode().value() + "：支付状态观察值读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("trade 服务不可达");
    }
  }

  /** 上游不可用一律 503，理由写明「读不到」而不是「没有数据」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }
}
