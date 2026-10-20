package com.starcon.channel;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
 * 读取 **trade 服务**尾程运费的**跨服务只读端口**。
 *
 * <p>蓝图 10.6.1.1 成本分析的五分量里，「尾程运费」的真实归属是 trade 服务的
 * {@code logistics_waybill_freight}（V132，10.2.1.1 运单列表登记的**现行**运费行：
 * 人工登记或渠道对账单登记，append-only，更正走「追加新行 + 撤下旧行」）。
 *
 * <p>⚠️ AGENTS.md 模块边界纪律禁止跨服务 SQL / FK / 共享视图，所以本端口
 * <b>只经 trade 的授权 HTTP API</b>读，不直连对方库、不建跨库视图、不建跨库外键。
 *
 * <p>租户<b>不</b>由本端口传递租户号：转发的是<b>调用方自己的</b>
 * {@code Authorization} bearer，trade 侧仍按已认证成员关系解析租户并强制 RLS。
 * 也就是说租户隔离是<b>两道</b>（channel 侧 RLS + trade 侧 RLS），
 * 而不是「本服务替对方决定租户」。
 *
 * <p>用法与 {@link PendingShipmentQueryPort}（读 trade）、{@link SellableQuantityQueryPort}
 * （读 warehouse）逐字同构：取不到就<b>显式失败</b>，
 * 绝不返回空集合冒充「上游没有运费数据」—— 那会让现场看到「成本 0 元」，
 * 照着空清单以为这批货根本没花过运费。
 */
@Service
public class TradeFreightQueryPort {
  /** 上游只读形状：{@code GET /trade/logistics/waybills/list}。 */
  static final String WAYBILLS_PATH = "/trade/logistics/waybills/list";

  /**
   * 单页拉多少票。trade 的列表端点把 size 钳在 200 以内，超了会被拒。
   *
   * <p>成本报表要的是<b>整窗</b>的运费，不是一页 —— 所以这里按页翻，
   * 翻到 {@code totalElements} 为止。
   */
  static final int PAGE_SIZE = 200;

  /**
   * 最多翻多少页（{@code 200 × 25 = 5000} 票）。
   *
   * <p>必须有上限：翻页是无界的，一个宽时间窗 + 大租户能把本服务的请求线程占满。
   * 触到上限时<b>如实报 truncated</b>（而不是悄悄少算）—— 报表会写明
   * 「本次只统计了前 N 票」，因为把截断的统计说成全量比不给更糟。
   */
  static final int MAX_PAGES = 25;

  /**
   * 上游一行运单（只声明本模块真正用到的字段）。
   *
   * <p>刻意读得少：读得越少，上游加字段时本模块越不容易被撑爆。
   */
  record WaybillCostRow(
      UUID id,
      String waybillNo,
      String salesOrderNo,
      String outboundOrderNo,
      String carrierCode,
      String carrierChannel,
      String carrierChannelLabel,
      String destinationCountry,
      BigDecimal grossWeightKg,
      BigDecimal freightAmount,
      String freightCurrency,
      String freightGapCode,
      String freightSource,
      OffsetDateTime createdAt) {}

  /** 上游一页的形状（本模块只取 items 与 totalElements）。 */
  record WaybillCostPage(List<WaybillCostRow> items, long totalElements) {}

  /**
   * 一次整窗扫描的结果。
   *
   * @param truncated 是否撞到 {@link #MAX_PAGES} 上限而没翻完
   */
  record WaybillCostScan(List<WaybillCostRow> items, long totalElements, boolean truncated, int pagesFetched) {}

  /**
   * 上游 JSON 的 mapper。
   *
   * <p>两处配置都不是可有可无的：
   *
   * <ul>
   *   <li>{@code JavaTimeModule} —— 上游的运单行带 {@code createdAt}（OffsetDateTime），
   *       不注册就抛「Java 8 date/time type OffsetDateTime not supported by default」。
   *   <li>关掉 {@code FAIL_ON_UNKNOWN_PROPERTIES} —— 上游**加字段**不应该把本模块撑爆；
   *       类型不匹配照样炸（那种是真读错了，必须炸）。
   * </ul>
   */
  private static final ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private final RestClient client;

  public TradeFreightQueryPort(@Value("${TRADE_URL:http://127.0.0.1:8085}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    // 读超时给到 10 秒：一次整窗扫描要连续翻多页，比单条读慢一截，
    // 但仍然必须有上限 —— 没有上限时 trade 一慢就把本服务的请求线程全占住。
    factory.setReadTimeout(Duration.ofSeconds(10));
    this.client =
        RestClient.builder()
            .baseUrl(url)
            .requestFactory(factory)
            .messageConverters(
                java.util.List.of(new MappingJackson2HttpMessageConverter(JSON)))
            .build();
  }

  /**
   * 扫描一个时间窗内的全部运单（按页翻）。
   *
   * <p>时间窗是<b>闭开区间</b> {@code [from, to)}，与 trade 端点自己的口径一致。
   */
  public WaybillCostScan scan(
      OffsetDateTime from, OffsetDateTime to, String bearer) {
    List<WaybillCostRow> collected = new ArrayList<>();
    long total = 0;
    boolean truncated = false;
    int pages = 0;
    for (int page = 0; page < MAX_PAGES; page++) {
      WaybillCostPage result = fetchPage(from, to, page, bearer);
      total = result.totalElements();
      pages = page + 1;
      if (result.items() != null) {
        collected.addAll(result.items());
      }
      if (collected.size() >= total) {
        break;
      }
      if (result.items() == null || result.items().isEmpty()) {
        break;
      }
      if (page == MAX_PAGES - 1) {
        truncated = true;
      }
    }
    return new WaybillCostScan(List.copyOf(collected), total, truncated, pages);
  }

  private WaybillCostPage fetchPage(
      OffsetDateTime from, OffsetDateTime to, int page, String bearer) {
    try {
      WaybillCostPage result =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(WAYBILLS_PATH)
                          .queryParam("dateFrom", from == null ? null : from.toString())
                          .queryParam("dateTo", to == null ? null : to.toString())
                          .queryParam("page", page)
                          .queryParam("size", PAGE_SIZE)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(WaybillCostPage.class);
      if (result == null || result.items() == null) {
        throw unavailable("trade 服务没有返回运单清单");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "调用方无权读取上游运单运费", e);
      }
      throw unavailable("trade 服务返回 " + e.getStatusCode().value() + "：运单运费读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("trade 服务不可达");
    }
  }

  /** 上游不可用一律 503，理由写明「读不到」而不是「没有数据」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }
}
