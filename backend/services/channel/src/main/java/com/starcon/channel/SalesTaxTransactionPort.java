package com.starcon.channel;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
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
 * 读取 **finance 服务 12.3.1.2 交易明细**的**跨服务只读端口**。
 *
 * <p>蓝图 16.3.2.3 的输入写死「<b>自营应税销售额</b>」—— 它的真实归属是 finance 服务的
 * {@code finance_settlement_line}（V345 + V348，12.3.1.1 结算单下的明细行），
 * 其中 {@code transaction_type='ORDER_SALES'} 的行就是「卖出去的货」，
 * {@code transaction_type='WITHHOLDING_TAX'} 的行就是平台在结算单里扣走的**代扣税**。
 *
 * <p>⚠️ AGENTS.md 模块边界纪律禁止跨服务 SQL / 跨库 FK / 共享视图，所以本端口
 * <b>只经 finance 的授权 HTTP API</b>读，不直连对方库、不建跨库视图、不建跨库外键。
 *
 * <p>⚠️ <b>租户不由本端口传递</b>：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * finance 侧仍按已认证成员关系解析租户并强制 RLS。也就是说租户隔离是<b>两道</b>
 * （channel 侧 RLS + finance 侧 RLS），而不是「本服务替对方决定租户」。
 *
 * <p>用法与既有 {@code TradeFreightQueryPort}（读 trade）、{@code ChannelShopPort}（读 channel，
 * finance 侧）逐字同构：取不到就<b>显式失败</b>，
 * 绝不返回空集合冒充「上游没有销售」—— 那会让对账得出「应缴 0」的假结论，
 * 而「应缴 0」在申报里是最危险的一个数字。
 */
@Service
public class SalesTaxTransactionPort {

  /** 上游只读形状：{@code GET /finance/transactions}（12.3.1.2 交易明细）。 */
  static final String TRANSACTIONS_PATH = "/finance/transactions";

  /**
   * 单页拉多少行。finance 的列表端点把 size 钳在 200 以内，超了会被拒。
   */
  static final int PAGE_SIZE = 200;

  /**
   * 最多翻多少页（{@code 200 × 25 = 5000} 行）。
   *
   * <p>必须有上限：翻页是无界的，一个宽时间窗 + 大租户能把本服务的请求线程占满。
   * 触到上限时<b>如实报 truncated</b>（而不是悄悄少算）—— 对账少算了销售却不说，
   * 等于给出一份「看起来对过」的错误申报底稿。
   */
  static final int MAX_PAGES = 25;

  /**
   * 上游一行交易明细（只声明本模块真正用到的字段）。
   *
   * <p>刻意读得少：读得越少，上游加字段时本模块越不容易被撑爆。
   */
  record TransactionRow(
      UUID id,
      String settlementNo,
      String shopCode,
      String shopName,
      String platformCode,
      String transactionType,
      BigDecimal amount,
      String currency,
      LocalDate occurredOn) {}

  /** 上游一页的形状（本模块只取 items 与 total）。 */
  record TransactionPage(List<TransactionRow> items, int page, int size, long total) {}

  /**
   * 一次整窗扫描的结果。
   *
   * @param truncated 是否撞到 {@link #MAX_PAGES} 上限而没翻完
   */
  record TransactionScan(List<TransactionRow> items, long total, boolean truncated, int pagesFetched) {}

  /** 上游 JSON 的 mapper。 */
  private static final ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private final RestClient client;

  public SalesTaxTransactionPort(@Value("${FINANCE_URL:http://127.0.0.1:8087}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
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
   * 扫描一个时间窗内的全部交易明细（按页翻）。
   *
   * @param from 起始日（含）
   * @param to 终止日（含）—— 与上游 {@code occurredTo} 的口径一致（上游是闭区间）
   * @param transactionType 交易类型（{@code ORDER_SALES} / {@code WITHHOLDING_TAX}）
   * @param bearer 调用方自己的会话（租户由 finance 侧按已认证成员关系解析）
   */
  public TransactionScan scan(
      LocalDate from, LocalDate to, String transactionType, String bearer) {
    List<TransactionRow> collected = new ArrayList<>();
    long total = 0;
    boolean truncated = false;
    int pages = 0;
    for (int page = 0; page < MAX_PAGES; page++) {
      TransactionPage result = fetchPage(from, to, transactionType, page, bearer);
      total = result.total();
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
    return new TransactionScan(List.copyOf(collected), total, truncated, pages);
  }

  private TransactionPage fetchPage(
      LocalDate from, LocalDate to, String transactionType, int page, String bearer) {
    try {
      TransactionPage result =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(TRANSACTIONS_PATH)
                          .queryParam("transactionType", transactionType)
                          .queryParam("occurredFrom", from == null ? null : from.toString())
                          .queryParam("occurredTo", to == null ? null : to.toString())
                          .queryParam("page", page)
                          .queryParam("size", PAGE_SIZE)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(TransactionPage.class);
      if (result == null || result.items() == null) {
        throw unavailable("finance 服务没有返回交易明细");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED, "调用方无权读取上游交易明细（需要 finance 侧的读权限）", e);
      }
      throw unavailable("finance 服务返回 " + e.getStatusCode().value() + "：交易明细读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("finance 服务不可达");
    }
  }

  /** 上游不可用一律 503，理由写明「读不到」而不是「没有数据」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }
}
