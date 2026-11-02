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
 * 读取上游 **9 订单**与 **11 售后**事实的**跨服务只读端口**（trade 服务，端口 8085）——
 * **15.3.1.2 客户详情专用**。
 *
 * <p>🔴 <b>为什么不复用 {@link CustomerFactsPort}</b>：
 * 那条端口（15.3.1.1 客户列表用）**刻意不读** {@code customerPartyId} 之外的
 * 「按客户筛」能力，也不带售后归因所需的时间窗扫描；而本模块要的是
 * 「<b>这一位客户</b>的订单」与「<b>这一位客户</b>的售后」——
 * trade 的列表端点**支持按 {@code customerPartyId} 精确筛**，
 * 按客户筛比全量扫描快一个数量级。
 *
 * <p>本模块按**同一写法**另起一条只读端口，<b>一个字都不改</b> {@code CustomerFactsPort} ——
 * AGENTS.md 的模块边界纪律要求并行车道**只碰自己的文件**
 * （改共享文件会让下轮并入产生结构性冲突，成果全卡在冲突里）。
 *
 * <p>🔴 <b>跨服务纪律</b>：只经 trade 的**授权 HTTP API** 读
 * （{@code GET /trade/sales-orders/list} / {@code GET /trade/aftersales/cases}），
 * <b>不直连对方库、不建跨库 FK、不建跨库视图</b>。
 * 转发的是<b>调用方自己的</b> {@code Authorization} bearer —— trade 侧仍按已认证成员
 * 关系解析租户并强制 RLS ⇒ 租户隔离是<b>两道</b>（crm 侧 RLS + trade 侧 RLS）。
 *
 * <p>🔴 <b>售后不带买家维度</b>：售后单只有 {@code orderId} ⇒ 归因链路是
 * 「售后单 → orderId → 订单 → customerPartyId → 客户」。
 * 本端口把售后按**时间窗**整段拉下来，由服务层用该客户的订单 id 建索引做归因 ——
 * 本库**不建任何指向 trade 的外键**。
 *
 * <p>🔴 <b>取不到就显式失败</b>：读不到一律 503，理由写明「读不到」而不是「没有数据」。
 * 绝不返回空集合冒充「这个客户没有订单」—— 那会让客服看到空时间线，
 * 照着空记录以为这位客户从来没下过单。
 */
@Service
public class CustomerDetailFactsPort {

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
   * 触到上限时<b>如实报 truncated</b>（而不是悄悄少算）。
   */
  static final int MAX_PAGES = 25;

  /**
   * 上游一行订单（只声明本模块真正用到的字段）。
   *
   * <p>刻意读得少：读得越少，上游加字段时本模块越不容易被撑爆。
   *
   * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)}：上游那页还带着
   * {@code skuCodes} / {@code externalOrderNo} / {@code manualKind} 等若干列，
   * 本模块用不到，但<b>不因为读少就崩</b>。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record OrderRow(
      UUID id,
      String orderNo,
      String orderSource,
      String documentStatus,
      String currency,
      BigDecimal grandTotalAmount,
      Integer lineCount,
      UUID customerPartyId,
      String buyerName,
      OffsetDateTime submittedAt,
      OffsetDateTime createdAt,
      String platformStatus,
      String paymentStatus,
      String fulfillmentStatus) {}

  /** 上游一页订单的形状。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record OrderPage(List<OrderRow> rows, int page, int size, long total) {}

  /** 上游一行售后单（只声明本模块真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record CaseRow(
      UUID id,
      String caseNo,
      UUID orderId,
      String orderNo,
      String caseType,
      String caseTypeLabel,
      String status,
      String statusLabel,
      String reasonCode,
      BigDecimal requestedAmount,
      String currency,
      BigDecimal requestedQuantity,
      OffsetDateTime receivedAt) {}

  /** 上游一页售后单的形状。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record CasePage(List<CaseRow> rows, int page, int size, long total) {}

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

  public CustomerDetailFactsPort(@Value("${TRADE_URL:http://127.0.0.1:8085}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    // 读超时给到 15 秒：一次扫描要连续翻多页，比单条读慢一截，
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
   * 拉**这一位客户**的订单事实。
   *
   * @param customerPartyId 上游客户标识（catalog 的 party id）
   * @param page 从 0 起的页号
   */
  public OrderPage ordersByCustomer(UUID customerPartyId, int page, String bearer) {
    try {
      OrderPage result =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(ORDERS_PATH)
                          .queryParam("customerPartyId", customerPartyId)
                          .queryParam("sort", "ORDERED_AT_DESC")
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
   * 拉**一个时间窗内**的售后事实（不带买家维度，由服务层按订单 id 归因）。
   *
   * @param from 受理时刻下界（闭区间，含）
   * @param to 受理时刻上界（开区间，不含）
   */
  public CasePage casesInWindow(OffsetDateTime from, OffsetDateTime to, int page, String bearer) {
    try {
      CasePage result =
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
              .body(CasePage.class);
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
