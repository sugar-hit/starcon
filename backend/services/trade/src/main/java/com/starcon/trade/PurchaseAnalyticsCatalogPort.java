package com.starcon.trade;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.5.2.1 采购分析读 catalog 的**类目、单位换算与汇率**的跨服务只读端口。
 *
 * <p>蓝图 6.5.2.1 的两个硬口径靠本端口落地：
 *
 * <ul>
 *   <li><b>单位换算</b>（卷 06 硬约束「必须记录币种、汇率快照、单位换算」）：
 *       SKU 的采购单位箱规在 catalog 的 3.1.2.4 单位换算里（role='PURCHASE' 的
 *       {@code quantityPerBase}）—— 本模块**不猜**箱规是 1，猜成 1 会让数量差一个数量级。</li>
 *   <li><b>汇率快照</b>（同一句硬约束的另一半）：汇率台账在 catalog（5.2.2.2 汇率台账），
 *       本模块在**物化那一刻**取到汇率并**冻结进事实行** —— 之后 catalog 改了汇率，
 *       历史报表的数字不会跟着漂。</li>
 * </ul>
 *
 * <p>用法与 {@link ReplenishmentCatalogPort}（读 catalog 的报价与箱规）、
 * {@link PurchasePlanWarehousePort}（读 warehouse 的仓与法人）逐字同构 ——
 * 跨服务读一律走一个 port 类，取不到就<b>显式失败</b>，绝不返回空集合冒充「没有类目」。
 *
 * <p>用三个已交付形状：
 *
 * <ul>
 *   <li>{@code GET /catalog/skus/{id}} —— 3.1.2.1 的 SKU 详情（分类 / 基础单位 / 单位换算）。</li>
 *   <li>{@code GET /catalog/exchange-rates?baseCurrency=&quoteCurrency=&dateFrom=&dateTo=} ——
 *       5.2.2.2 的汇率台账（按生效日区间读，取「发生日当天或之前最新的一条」）。</li>
 *   <li>{@code GET /catalog/exchange-rates/functional-currencies} —— 各法人本位币
 *       （17.1.2.2 的法人档案 → 本位币）。读不到 ⇒ 本模块**不做**任何折算，
 *       相应事实一律标 fx_status='UNSET'。</li>
 * </ul>
 *
 * <p><b>租户不由本端口传递租户号</b>：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * catalog 侧仍按已认证成员关系解析租户并强制 RLS。租户隔离因此是<b>两道</b>
 * （trade 侧 RLS + catalog 侧 RLS）。
 */
@Service
public class PurchaseAnalyticsCatalogPort {

  /** catalog 的 SKU 详情（只声明本模块真正用到的字段）。 */
  record SkuView(UUID id, String code, String name, String category, String baseUnit, List<ConversionView> unitConversions) {}

  /** 单位换算的一行（1 个该单位 = 多少个基础单位）。 */
  record ConversionView(String unitCode, String role, BigDecimal quantityPerBase, boolean current) {}

  /** 一条汇率（方向：**1 base = rate quote**）。 */
  record RateView(
      UUID id,
      String baseCurrency,
      String quoteCurrency,
      BigDecimal rate,
      LocalDate effectiveDate,
      String sourceCode,
      String sourceKind,
      String display) {}

  /** 汇率列表响应（只声明用到的字段）。 */
  record RatePage(List<RateView> items, long total, int page, int size, String note) {}

  /** 一个法人的本位币。 */
  record FunctionalCurrencyRow(
      UUID legalEntityId, String legalEntityCode, String legalEntityName, String baseCurrency) {}

  /** 本位币面板。 */
  record FunctionalCurrencyPanel(List<FunctionalCurrencyRow> rows, String note) {}

  /** 端口专用的 ObjectMapper：**忽略上游多出来的字段**。 */
  private static final ObjectMapper PORT_JSON =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private final RestClient client;

  public PurchaseAnalyticsCatalogPort(@Value("${CATALOG_URL:http://127.0.0.1:8082}") String url) {
    var factory =
        new JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    // 读超时给到 8 秒：一次同步要翻几十个 SKU 与若干条汇率，比单条读慢一截，
    // 但仍然必须有上限 —— 没有上限时商品主数据一慢就把本服务的请求线程全占住。
    factory.setReadTimeout(Duration.ofSeconds(8));
    var converter = new MappingJackson2HttpMessageConverter(PORT_JSON);
    this.client =
        RestClient.builder()
            .baseUrl(url)
            .requestFactory(factory)
            .messageConverters(java.util.List.of(converter))
            .build();
  }

  /**
   * 读一个 SKU 的详情（类目 + 基础单位 + 采购单位箱规）。
   *
   * <p>不存在就 404，<b>不返回 null 让调用方自己判空</b>：「商品不存在」与「商品服务挂了」
   * 是两件事，前者该当场拒绝，后者该重试。
   */
  public SkuView sku(UUID skuId, String bearer) {
    SkuView view;
    try {
      view =
          client
              .get()
              .uri("/catalog/skus/" + skuId)
              .header("Authorization", bearer)
              .retrieve()
              .body(SkuView.class);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND, "商品不存在：SKU " + skuId + "，采购分析解析不出它的类目与箱规");
      }
      throw upstream(e.getStatusCode().value(), "读商品主数据");
    } catch (RestClientException e) {
      throw unavailable("商品主数据服务暂时不可达");
    }
    if (view == null || view.id() == null) {
      throw unavailable("商品主数据服务没有返回这个 SKU");
    }
    return view;
  }

  /**
   * 读一段区间内的汇率台账（1 base = rate quote）。
   *
   * <p>区间读而不是「取最新一条」：物化时要按**发生日**取「当天或之前最新的一条」，
   * 否则月初的采购成本会被月末的汇率折算掉。
   */
  public List<RateView> rates(
      String baseCurrency, String quoteCurrency, LocalDate from, LocalDate to, String bearer) {
    RatePage page;
    try {
      page =
          client
              .get()
              .uri(
                  uriBuilder -> {
                    var b =
                        uriBuilder
                            .path("/catalog/exchange-rates")
                            .queryParam("baseCurrency", baseCurrency)
                            .queryParam("quoteCurrency", quoteCurrency);
                    if (from != null) {
                      b.queryParam("dateFrom", from.toString());
                    }
                    if (to != null) {
                      b.queryParam("dateTo", to.toString());
                    }
                    return b.queryParam("size", 500).build();
                  })
              .header("Authorization", bearer)
              .retrieve()
              .body(RatePage.class);
    } catch (RestClientResponseException e) {
      throw upstream(e.getStatusCode().value(), "读汇率台账");
    } catch (RestClientException e) {
      throw unavailable("商品主数据服务暂时不可达");
    }
    if (page == null || page.items() == null) {
      throw unavailable("汇率台账没有返回列表");
    }
    return page.items();
  }

  /**
   * 各法人的本位币。
   *
   * <p>读不到 ⇒ 本模块**不做任何折算**（本位币不明时折出来的数是假数）。
   */
  public List<FunctionalCurrencyRow> functionalCurrencies(String bearer) {
    FunctionalCurrencyPanel panel;
    try {
      panel =
          client
              .get()
              .uri("/catalog/exchange-rates/functional-currencies")
              .header("Authorization", bearer)
              .retrieve()
              .body(FunctionalCurrencyPanel.class);
    } catch (RestClientResponseException e) {
      throw upstream(e.getStatusCode().value(), "读本位币");
    } catch (RestClientException e) {
      throw unavailable("商品主数据服务暂时不可达");
    }
    if (panel == null || panel.rows() == null) {
      throw unavailable("本位币面板没有返回列表");
    }
    return panel.rows();
  }

  private ResponseStatusException upstream(int status, String what) {
    if (status == HttpStatus.UNAUTHORIZED.value()) {
      return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
    }
    if (status == HttpStatus.FORBIDDEN.value()) {
      return new ResponseStatusException(
          HttpStatus.FORBIDDEN,
          "当前身份无权读取" + what + "，采购分析暂时解析不了类目、箱规与汇率");
    }
    return unavailable("商品主数据服务返回 " + status + "（读取" + what + "时）");
  }

  private ResponseStatusException unavailable(String reason) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        reason
            + "：类目、单位换算与汇率的事实来源是商品主数据服务的授权只读接口"
            + "（跨库视图与跨服务 SQL 均被 AGENTS.md 禁止），"
            + "本服务不会凭空造一个类目或汇率出来");
  }
}
