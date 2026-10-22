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
 * 读取上游 **9 订单**与 **11 售后**事实的**跨服务只读端口**（trade 服务，端口 8085）。
 *
 * <p>蓝图 15.3.2.1 的上游写明是「9 订单、11 售后」，而**这两样真值都不在本服务**：
 * 它们在 <b>trade</b> 服务（{@code sales_order} / {@code sales_order_party_snapshot} /
 * {@code aftersales_case}）。AGENTS.md 模块边界纪律禁止跨服务 SQL / 跨库 FK / 共享视图，
 * 所以本端口<b>只经 trade 的授权 HTTP API</b>读，不直连对方库、不建跨库视图、不建跨库外键。
 *
 * <p>本端口提供两样东西：
 *
 * <ol>
 *   <li>{@link #orders} —— <b>订单事实</b>：{@code GET /trade/sales-orders/list}。
 *       它回答「这位客户在窗口里下了几单、花了多少钱、来自哪些渠道、什么时候下的」——
 *       标签判定所需的**全部**订单侧维度都在这里。</li>
 *   <li>{@link #aftersalesCases} —— <b>售后事实</b>：{@code GET /trade/aftersales/cases}。
 *       它回答「窗口里受理了哪些售后单」。</li>
 * </ol>
 *
 * <p>🔴 <b>归因链路</b>：售后单<b>不带买家维度</b>，只有 {@code orderId}。所以
 * 「退货率高」这条规则的归因是
 * 「售后单 → orderId → 订单 → customerPartyId → 客户」，
 * 由本端口在**一次扫描内**建索引完成 —— 本库**不建任何指向 trade 的外键**。
 *
 * <p>租户<b>不</b>由本端口传递租户号：转发的是<b>调用方自己的</b>
 * {@code Authorization} bearer，trade 侧仍按已认证成员关系解析租户并强制 RLS。
 * 也就是说租户隔离是<b>两道</b>（crm 侧 RLS + trade 侧 RLS），
 * 而不是「本服务替对方决定租户」。
 *
 * <p>用法与既有 {@link SignoffDeliveredOrderPort}（读 channel）逐字同构：
 * 取不到就<b>显式失败</b>，绝不返回空集合冒充「上游没有数据」——
 * 那会让现场看到 0 位客户，照着空名单以为这批买家根本没下过单。
 */
@Service
public class CustomerFactsPort {

  /** 上游只读形状：{@code GET /trade/sales-orders/list}。 */
  static final String ORDERS_PATH = "/trade/sales-orders/list";

  /** 上游只读形状：{@code GET /trade/aftersales/cases}。 */
  static final String CASES_PATH = "/trade/aftersales/cases";

  /** 上游分页上限：trade 侧把 size 钳在 200 以内。 */
  static final int PAGE_SIZE = 200;

  /**
   * 最多翻多少页（{@code 200 × 25 = 5000} 件）。
   *
   * <p>必须有上限：翻页是无界的，一个宽时间窗 + 大租户能把本服务的请求线程占满。
   * 触到上限时<b>如实报 truncated</b>（而不是悄悄少算）—— 运行记录里会写明
   * 「本次只扫描了前 N 件」。
   */
  static final int MAX_PAGES = 25;

  /**
   * 上游一行订单（只声明本模块真正用到的字段）。
   *
   * <p>刻意读得少：读得越少，上游加字段时本模块越不容易被撑爆。
   *
   * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)}：上游那页还带着
   * {@code skuCodes} / {@code documentStatus} / {@code paymentStatus} 等若干列，
   * 本模块用不到，但<b>不因为读少就崩</b>。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record OrderView(
      UUID id,
      String orderNo,
      String orderSource,
      String documentStatus,
      String currency,
      BigDecimal grandTotalAmount,
      UUID customerPartyId,
      String buyerName,
      OffsetDateTime submittedAt,
      OffsetDateTime createdAt) {}

  /** 上游一页订单的形状。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record OrderPage(List<OrderView> rows, int page, int size, long total) {}

  /** 上游一行售后单（只声明本模块真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record AftersalesCaseView(
      UUID id,
      String caseNo,
      UUID orderId,
      String orderNo,
      String caseType,
      String status,
      String reasonCode,
      BigDecimal requestedAmount,
      String currency,
      OffsetDateTime receivedAt) {}

  /** 上游一页售后单的形状。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record AftersalesCasePage(List<AftersalesCaseView> rows, int page, int size, long total) {}

  /**
   * 上游 JSON 的 mapper。
   *
   * <p>两处配置都不是可有可无的：{@code JavaTimeModule}（上游带 {@code submittedAt} /
   * {@code receivedAt}，OffsetDateTime），以及关掉 {@code FAIL_ON_UNKNOWN_PROPERTIES}
   * （上游<b>加字段</b>不应该把本模块撑爆；类型不匹配照样炸 —— 那种是真读错了，必须炸）。
   */
  private static final ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private final RestClient client;

  public CustomerFactsPort(@Value("${TRADE_URL:http://127.0.0.1:8085}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    // 读超时给到 15 秒：一次批量扫描要连续翻多页，比单条读慢一截，
    // 但仍然必须有上限 —— 没有上限时 trade 一慢就把本服务的请求线程全占住。
    factory.setReadTimeout(Duration.ofSeconds(15));
    this.client =
        RestClient.builder()
            .baseUrl(url)
            .requestFactory(factory)
            .messageConverters(java.util.List.of(new MappingJackson2HttpMessageConverter(JSON)))
            .build();
  }

  /**
   * 拉一页订单事实。
   *
   * @param from 下单时刻下界（闭区间，含）
   * @param to 下单时刻上界（开区间，不含）
   */
  public OrderPage orders(OffsetDateTime from, OffsetDateTime to, int page, String bearer) {
    try {
      OrderPage result =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(ORDERS_PATH)
                          .queryParam("orderedFrom", from.toString())
                          .queryParam("orderedTo", to.toString())
                          .queryParam("sort", "ORDERED_AT_ASC")
                          .queryParam("page", Math.max(page, 0))
                          .queryParam("size", PAGE_SIZE)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(OrderPage.class);
      if (result == null || result.rows() == null) {
        throw unavailable("trade 服务没有返回订单列表");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED, "调用方无权读取上游订单事实（需要 trade 侧的订单读取权限）", e);
      }
      throw unavailable("trade 服务返回 " + e.getStatusCode().value() + "：订单事实读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("trade 服务不可达");
    }
  }

  /**
   * 拉一页售后事实。
   *
   * @param from 受理时刻下界（闭区间，含）
   * @param to 受理时刻上界（开区间，不含）
   */
  public AftersalesCasePage aftersalesCases(OffsetDateTime from, OffsetDateTime to, int page, String bearer) {
    try {
      AftersalesCasePage result =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(CASES_PATH)
                          .queryParam("receivedFrom", from.toString())
                          .queryParam("receivedTo", to.toString())
                          .queryParam("page", Math.max(page, 0))
                          .queryParam("size", PAGE_SIZE)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(AftersalesCasePage.class);
      if (result == null || result.rows() == null) {
        throw unavailable("trade 服务没有返回售后单列表");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED, "调用方无权读取上游售后事实（需要 trade 侧的售后读取权限）", e);
      }
      throw unavailable("trade 服务返回 " + e.getStatusCode().value() + "：售后事实读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("trade 服务不可达");
    }
  }

  /** 上游不可用一律 503，理由写明「读不到」而不是「没有数据」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }
}
