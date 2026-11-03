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
 * 读取上游 **9 订单**事实的**跨服务只读端口**（trade 服务，端口 8085）——
 * 15.6.1.1 会员与等级用的那条。
 *
 * <p>🔴 <b>为什么另起一条端口，而不是复用 {@link CustomerOrderFactsPort}</b>：
 * 那条是 **15.6.2.1 生命周期与流失预警**的文件（已交付），AGENTS.md 的模块边界纪律要求
 * 并行车道**只碰自己的文件**（改共享文件会让下轮并入产生结构性冲突，成果全卡在冲突里）。
 * 所以本模块按**同一写法**另起一条只读端口，<b>一个字都不改</b> {@code CustomerOrderFactsPort}。
 *
 * <p>🔴 <b>本端口比那条多读一个字段：{@code submittedAt} 的窗口可以留空</b>。
 * 会员的「累计消费 / 订单数 / 复购率」默认口径是**全部历史**（窗口起点留空 ⇒ 上游不设下界），
 * 而生命周期模块那侧默认要扫「最近两年」。窗口起点的语义不同 ⇒ 端口也不同。
 *
 * <p>🔴 <b>跨服务纪律</b>：只经 trade 的**授权 HTTP API**读
 * （{@code GET /trade/sales-orders/list}），<b>不直连对方库、不建跨库 FK、不建跨库视图</b>。
 * 转发的是<b>调用方自己的</b> {@code Authorization} bearer —— trade 侧仍按已认证成员
 * 关系解析租户并强制 RLS。也就是说租户隔离是<b>两道</b>（crm 侧 RLS + trade 侧 RLS）。
 *
 * <p>🔴 <b>取不到就显式失败</b>：读不到一律 503，理由写明「读不到」而不是「没有数据」。
 * 绝不返回空集合冒充「这个租户没有订单」—— 那会让会员等级**全员掉到 NONE**，
 * 而运营以为这批买家真的没下过单。
 */
@Service
public class MembershipOrderFactsPort {

  /** 上游只读形状：{@code GET /trade/sales-orders/list}。 */
  static final String ORDERS_PATH = "/trade/sales-orders/list";

  /** 上游分页上限：trade 侧把 size 钳在 200 以内。 */
  static final int PAGE_SIZE = 200;

  /**
   * 最多翻多少页（{@code 200 × 25 = 5000} 单）。
   *
   * <p>必须有上限：翻页是无界的，一个宽时间窗 + 大租户能把本服务的请求线程占满。
   * 触到上限时<b>如实报 truncated</b>（而不是悄悄少算）。
   */
  static final int MAX_PAGES = 25;

  /**
   * 上游一行订单（只声明本模块真正用到的字段）。
   *
   * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)}：上游那页还带着
   * {@code externalOrderNo} / {@code manualKind} / {@code lineCount} / {@code paymentStatus}
   * 等若干列，本模块用不到，但<b>不因为读少就崩</b>。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record OrderFact(
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
  record OrderPage(List<OrderFact> rows, int page, int size, long total) {}

  /**
   * 上游 JSON 的 mapper。
   *
   * <p>两处配置都不是可有可无的：{@code JavaTimeModule}（上游带 {@code submittedAt}），
   * 以及关掉 {@code FAIL_ON_UNKNOWN_PROPERTIES}（上游<b>加字段</b>不应该把本模块撑爆；
   * 类型不匹配照样炸 —— 那种是真读错了，必须炸）。
   */
  private static final ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private final RestClient client;

  public MembershipOrderFactsPort(@Value("${TRADE_URL:http://127.0.0.1:8085}") String url) {
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
   * @param from 下单时刻下界（闭区间，含）；<b>null = 全部历史</b>（上游不设下界）
   * @param to 下单时刻上界（开区间，不含）
   */
  public OrderPage orders(OffsetDateTime from, OffsetDateTime to, int page, String bearer) {
    try {
      OrderPage result =
          client
              .get()
              .uri(
                  uriBuilder -> {
                    var b = uriBuilder.path(ORDERS_PATH);
                    if (from != null) {
                      b.queryParam("orderedFrom", from.toString());
                    }
                    b.queryParam("orderedTo", to.toString());
                    b.queryParam("sort", "ORDERED_AT_ASC");
                    b.queryParam("page", Math.max(page, 0));
                    b.queryParam("size", PAGE_SIZE);
                    return b.build();
                  })
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

  /** 上游不可用一律 503，理由写明「读不到」而不是「没有数据」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }
}
