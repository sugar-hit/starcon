package com.starcon.finance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 读取 **6 采购**（trade 服务）采购成本事实的**跨服务只读端口**。
 *
 * <p>蓝图 12-finance.md 的 12.4.1.3 把「<b>采购价波动</b>」写成本模块的五类差异之一，
 * 而采购价的真相在 trade 服务（{@code GET /trade/purchase-analytics/facts}）。
 *
 * <p>🔴 <b>为什么必须走 HTTP 而不是直接查 trade 的表</b>：
 *
 * <ul>
 *   <li>AGENTS.md 明令禁止跨服务 SQL / 跨库 FK / 共享视图；
 *   <li>采购价是**别的服务的写入所有权**（每张事实表只有一个写入所有者）；
 *   <li>蓝图关系里写着「下游 = <b>6 采购</b>（价格谈判依据）」——
 *       本模块是 6 采购的**消费者**，价格谈判的依据由本模块提供，
 *       但采购侧的价**不归本模块写**。
 * </ul>
 *
 * <p>🔴 <b>口径与上游自己的「采购价格波动」一致</b>：上游 6 采购的
 * {@code GET /trade/purchase-analytics/price-variance} 用的是
 * {@code fact_kind='COST' AND source_type='ARRIVAL_RECEIPT'}（到货成本事实）。
 * 本端口用**同一套过滤**读事实台账 —— 两处口径若不一致，
 * 「本模块说涨了 3%」与「6 采购说涨了 3%」就会打架。
 *
 * <p>🔴 <b>绝不跨币种相加</b>：采购事实按币种分开聚合，一个 (SKU × 币种) 一条聚合。
 * 本模块不会把 USD 的采购额与 CNY 的采购额加到一起。
 *
 * <p>🔴 <b>取不到就显式失败</b>（{@link ResponseStatusException}），绝不返回空集合冒充
 * 「上游没有采购价」—— 空集合在报表里会长成「采购价波动 = 0」。
 * 调用方（{@link CostVarianceService}）把失败**如实标成「上游不可得」**，
 * 而**不是**默默按 0 差异算。
 */
@Service
public class PurchasePricePort {

  /** 上游只读形状：{@code GET /trade/purchase-analytics/facts}（6 采购事实台账）。 */
  static final String FACTS_PATH = "/trade/purchase-analytics/facts";

  /** 上游分页上限（与上游 MAX_PAGE_SIZE 一致）。 */
  static final int PAGE_SIZE = 200;

  /** 最多翻多少页（{@code 200 × 25 = 5000} 条事实）。 */
  static final int MAX_PAGES = 25;

  /**
   * 上游 JSON 的解析器。
   *
   * <p>⚠️ {@code USE_BIG_DECIMAL_FOR_FLOWS} 必须开：默认 Jackson 把 JSON 里的浮点数读成
   * {@code double}，于是上游的 {@code 12.3456} 变成 {@code 12.345600000000001} ——
   * 采购单价被悄悄改了一位（采购价波动跟着错）。
   */
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  /** 上游一行采购成本事实（只声明本模块真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record FactRow(
      UUID id,
      @JsonIgnoreProperties(ignoreUnknown = true) String factKind,
      @JsonIgnoreProperties(ignoreUnknown = true) String sourceType,
      @JsonIgnoreProperties(ignoreUnknown = true) LocalDate occurredDate,
      @JsonIgnoreProperties(ignoreUnknown = true) UUID skuRefId,
      @JsonIgnoreProperties(ignoreUnknown = true) String skuCode,
      @JsonIgnoreProperties(ignoreUnknown = true) String skuName,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal quantity,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal baseQuantity,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal unitPrice,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal amount,
      @JsonIgnoreProperties(ignoreUnknown = true) String currency) {}

  /** 上游事实台账一页。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record FactPage(List<FactRow> items, long total, int page, int size) {}

  /**
   * 一个 (SKU × 币种) 的采购价聚合。
   *
   * @param quantityBasis 数量口径（{@code BASE_QUANTITY} = 用基础单位；{@code QUANTITY} = 用记录单位）——
   *     两期口径不一致时**不可比**（见服务层的判据）
   * @param averagePrice 加权平均采购单价（= Σ 金额 ÷ Σ 数量；分母为 0 时为空）
   */
  public record SkuPurchasePrice(
      UUID skuRefId,
      String skuCode,
      String skuName,
      String currency,
      BigDecimal quantity,
      BigDecimal amount,
      BigDecimal averagePrice,
      String quantityBasis) {}

  private final RestClient client;

  public PurchasePricePort(@Value("${TRADE_URL:http://127.0.0.1:8085}") String url) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(15));
    this.client =
        RestClient.builder()
            .baseUrl(url)
            .requestFactory(factory)
            .messageConverters(
                java.util.List.of(new MappingJackson2HttpMessageConverter(JSON)))
            .build();
  }

  /**
   * 读一段期间内、按 (SKU × 币种) 聚合的采购价。
   *
   * @param from 期间起（含）
   * @param to 期间止（不含）
   * @param bearer 调用方自己的会话（租户由 trade 侧按已认证成员关系解析）
   */
  public List<SkuPurchasePrice> prices(LocalDate from, LocalDate to, String bearer) {
    var aggregated = new LinkedHashMap<String, Accumulator>();
    var total = Long.MAX_VALUE;
    for (int page = 0; page < MAX_PAGES; page++) {
      FactPage result = page(page, from, to, bearer);
      if (result == null || result.items() == null) {
        throw unavailable("trade 服务没有返回采购事实（期间 " + from + " ~ " + to + "）");
      }
      for (var row : result.items()) {
        if (row == null || row.factKind() == null || !"COST".equals(row.factKind())) continue;
        if (row.sourceType() != null && !"ARRIVAL_RECEIPT".equals(row.sourceType())) continue;
        if (row.skuRefId() == null || row.currency() == null || row.currency().isBlank()) continue;
        var amount = row.amount();
        if (amount == null || amount.signum() <= 0) continue;
        // 数量口径：优先用基础单位（baseQuantity），没有就用记录单位。
        var quantity = row.baseQuantity();
        var basis = "BASE_QUANTITY";
        if (quantity == null) {
          quantity = row.quantity();
          basis = "QUANTITY";
        }
        if (quantity == null || quantity.signum() <= 0) continue;
        var key = row.skuRefId() + "|" + row.currency().trim().toUpperCase(java.util.Locale.ROOT);
        var acc = aggregated.computeIfAbsent(key, k -> new Accumulator());
        acc.skuRefId = row.skuRefId();
        acc.skuCode = row.skuCode() == null ? "" : row.skuCode();
        acc.skuName = row.skuName() == null ? "" : row.skuName();
        acc.currency = row.currency().trim().toUpperCase(java.util.Locale.ROOT);
        acc.quantity = acc.quantity.add(quantity);
        acc.amount = acc.amount.add(amount);
        // 🔴 口径必须全程一致：本期用基础单位、上期用记录单位时，两期的单价**不可比**。
        if (acc.quantityBasis == null) acc.quantityBasis = basis;
        else if (!acc.quantityBasis.equals(basis)) acc.quantityBasis = "MIXED";
      }
      total = result.total() < 0 ? 0 : result.total();
      if ((long) (page + 1) * PAGE_SIZE >= total) break;
    }

    var result = new ArrayList<SkuPurchasePrice>();
    for (var acc : aggregated.values()) {
      var average =
          acc.quantity.signum() == 0
              ? null
              : acc.amount.divide(acc.quantity, CostVarianceModels.MONEY_SCALE, RoundingMode.HALF_UP);
      result.add(
          new SkuPurchasePrice(
              acc.skuRefId, acc.skuCode, acc.skuName, acc.currency, acc.quantity, acc.amount, average,
              acc.quantityBasis));
    }
    return result;
  }

  /** 拉一页上游采购事实。 */
  private FactPage page(int page, LocalDate from, LocalDate to, String bearer) {
    try {
      FactPage result =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(FACTS_PATH)
                          .queryParam("from", from)
                          .queryParam("to", to)
                          .queryParam("factKind", "COST")
                          .queryParam("sourceType", "ARRIVAL_RECEIPT")
                          .queryParam("page", page)
                          .queryParam("size", PAGE_SIZE)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(FactPage.class);
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED,
            "调用方无权读取上游采购事实（需要 trade 侧的 purchase-analytics.read 权限）",
            e);
      }
      throw unavailable("trade 服务返回 " + e.getStatusCode().value() + "：采购事实读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("trade 服务不可达：采购事实读不到");
    }
  }

  /** 上游不可用一律 503，理由写明「读不到」而不是「没有采购价」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }

  /** 聚合累加器。 */
  private static final class Accumulator {
    UUID skuRefId;
    String skuCode;
    String skuName;
    String currency;
    BigDecimal quantity = BigDecimal.ZERO;
    BigDecimal amount = BigDecimal.ZERO;
    String quantityBasis;
  }
}
