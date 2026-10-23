package com.starcon.finance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
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
 * 读取上游 **9 订单（发货确认）**事实的**跨服务只读端口**（trade 服务，端口 8085）。
 *
 * <p>蓝图 12.1.1.1 写明上游是「12.3.1 结算、<b>9 订单（发货确认）</b>」，而这两样真值都不在本服务：
 *
 * <ul>
 *   <li><b>12.3.1 结算</b> —— <b>尚未交付</b>（12.3.1.1 结算单管理仍是「未开发」）。
 *       所以「平台结算」来源的应收<b>当前不可自动产生</b>：字段留好、来源枚举留好、 能力声明里如实报出归属模块，但服务层拒绝用它建单， <b>绝不填 0、也绝不造一条假结算</b>。
 *   <li><b>9 订单（发货确认）</b> —— trade 服务已交付 9.7.1.1 批发订单与账期： 它在<b>发货确认</b>这一个动作里产出 {@code
 *       b2b_receivable}（账期应收事实）。 AGENTS.md 模块边界纪律禁止跨服务 SQL / 跨库 FK / 共享视图， 所以本端口<b>只经 trade 的授权
 *       HTTP API</b>读，不直连对方库、不建跨库视图、不建跨库外键。
 * </ul>
 *
 * <p>🔴 <b>为什么不是「租户可维护列」就完事</b>：租户可维护列 + 来源枚举 + {@code source_reference}
 * 解决的是「<b>这张应收是哪来的、幂等键是什么</b>」；而「<b>上游那笔账期应收到底是什么</b>」 （金额、币种、账期天数、到期日、发货明细）必须从上游的**真值**读。
 * 两件事各管一段，缺一段就会出问题：
 *
 * <ul>
 *   <li>只留可维护列 ⇒ 金额得靠人抄，抄错了没有任何东西能对出来；
 *   <li>只做实时读 ⇒ 上游临时不可用时，历史应收就「查不到了」， 而应收是**已经发生过的事实**，不该因为某个服务重启就消失。
 * </ul>
 *
 * <p>所以本模块的形状是<b>两者都要</b>：本端口读上游事实， 落成本库 {@code finance_receivable}（含 {@code source} / {@code
 * source_system} / {@code source_reference} 三列溯源），之后<b>本库的数据是自足的</b> —— 上游读不到时，已导入的应收照常可查、可算账龄。
 *
 * <p>租户<b>不</b>由本端口传递租户号：转发的是<b>调用方自己的</b> {@code Authorization} bearer，trade 侧仍按已认证成员关系解析租户并强制
 * RLS。 也就是说租户隔离是<b>两道</b>（finance 侧 RLS + trade 侧 RLS）， 而不是「本服务替对方决定租户」。
 *
 * <p>用法与既有 {@code CustomerFactsPort}（读 trade）逐字同构： 取不到就<b>显式失败</b>，绝不返回空集合冒充「上游没有数据」—— 那会让现场看到 0
 * 张应收，照着空名单以为这批货根本没发。
 */
@Service
public class TradeReceivablePort {

  /** 上游只读形状：{@code GET /trade/b2b-orders/receivables}（9.7.1.1 批发订单与账期）。 */
  static final String RECEIVABLES_PATH = "/trade/b2b-orders/receivables";

  /** 上游分页上限：trade 侧把 size 钳在 200 以内。 */
  static final int PAGE_SIZE = 200;

  /**
   * 最多翻多少页（{@code 200 × 25 = 5000} 件）。
   *
   * <p>必须有上限：翻页是无界的，一个大租户能把本服务的请求线程占满。 触到上限时<b>如实报 truncated</b>（而不是悄悄少算）。
   */
  static final int MAX_PAGES = 25;

  /** 上游一行账期应收（只声明本模块真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record TradeReceivableView(
      UUID id,
      String receivableNo,
      UUID orderId,
      @JsonIgnoreProperties(ignoreUnknown = true) String orderNo,
      UUID shipmentId,
      UUID customerPartyId,
      @JsonIgnoreProperties(ignoreUnknown = true) String customerName,
      String currency,
      @JsonIgnoreProperties(ignoreUnknown = true) String paymentMethod,
      @JsonIgnoreProperties(ignoreUnknown = true) Integer paymentTermsDays,
      BigDecimal amount,
      OffsetDateTime confirmedAt,
      @JsonIgnoreProperties(ignoreUnknown = true) OffsetDateTime dueDate,
      @JsonIgnoreProperties(ignoreUnknown = true) String status,
      @JsonIgnoreProperties(ignoreUnknown = true) String invoiceState,
      @JsonIgnoreProperties(ignoreUnknown = true) List<TradeReceivableLineView> lines) {}

  /** 上游一行应收明细。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record TradeReceivableLineView(
      UUID id,
      @JsonIgnoreProperties(ignoreUnknown = true) UUID orderLineId,
      String skuCode,
      BigDecimal quantity,
      BigDecimal unitPrice,
      BigDecimal lineAmount) {}

  /**
   * 上游一页应收的形状。
   *
   * <p>⚠️ 上游的 {@code dueDate} 是 <b>timestamptz</b>，本模块的到期日是 <b>date</b>： 账期是日历天数，不是某一瞬间。转换时取上游的
   * <b>UTC 日期</b> （{@code dueDate.toLocalDate()} 在上游给的是 +00:00 偏移时就是 UTC 日历日）。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record TradeReceivablePage(List<TradeReceivableView> items, int page, int size, long total) {}

  /**
   * 上游 JSON 的 mapper。
   *
   * <p>两处配置都不是可有可无的：{@code JavaTimeModule}（上游带 {@code confirmedAt} / {@code
   * dueDate}，OffsetDateTime），以及关掉 {@code FAIL_ON_UNKNOWN_PROPERTIES}
   * （上游<b>加字段</b>不应该把本模块撑爆；类型不匹配照样炸 —— 那种是真读错了，必须炸）。
   */
  private static final ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private final RestClient client;

  public TradeReceivablePort(@Value("${TRADE_URL:http://127.0.0.1:8085}") String url) {
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
   * 拉一页上游账期应收。
   *
   * @param bearer 调用方自己的会话（租户由 trade 侧按已认证成员关系解析）
   */
  public TradeReceivablePage receivables(int page, String bearer) {
    try {
      TradeReceivablePage result =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(RECEIVABLES_PATH)
                          .queryParam("page", Math.max(page, 0))
                          .queryParam("size", PAGE_SIZE)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(TradeReceivablePage.class);
      if (result == null || result.items() == null) {
        throw unavailable("trade 服务没有返回应收列表");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED, "调用方无权读取上游发货确认事实（需要 trade 侧的 trade.b2b.read 权限）", e);
      }
      throw unavailable("trade 服务返回 " + e.getStatusCode().value() + "：发货确认事实读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("trade 服务不可达");
    }
  }

  /** 上游不可用一律 503，理由写明「读不到」而不是「没有数据」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }

  /** 上游到期日 → 本模块的日历日。 */
  static LocalDate dueDateOf(TradeReceivableView view) {
    if (view.dueDate() == null) return null;
    return view.dueDate().toLocalDate();
  }
}
