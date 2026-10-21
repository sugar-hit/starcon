package com.starcon.trade;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * 6.1.1.3 供应商评级读 catalog **3.6.2 报价台账**的跨服务只读端口 —— 价格竞争力维度的原料。
 *
 * <p>蓝图 6.1.1.3 的五个维度里「价格竞争力」需要市场报价，而报价台账
 * （{@code supplier_quote} / {@code supplier_quote_price_tier}）在 <b>catalog 服务的独立库</b>里
 * （卷 06 的主责是 {@code trade + catalog（party）}，party 与报价都归 catalog）。
 * AGENTS.md 模块边界纪律禁止跨服务 SQL / 跨库 FK / 共享视图，所以本端口
 * <b>只经 catalog 的授权只读端点</b>读，不直连对方库、不建跨库视图、不建跨库外键。
 *
 * <p>用法与 {@link PurchaseAnalyticsCatalogPort}（读 catalog 的类目 / 汇率）、
 * {@link ReplenishmentCatalogPort}（读 catalog 的报价与箱规）、{@link SupplierPartyQueryPort}
 * （读 catalog 的供应商）逐字同构 —— 跨服务读一律走一个 port 类，<b>取不到就显式失败</b>，
 * 绝不返回空集合冒充「市场上一个报价都没有」。
 *
 * <p>读的是 {@code GET /catalog/supplier-quotes}（3.6.2.1 的报价台账列表）的
 * {@code effectiveOnly=true} 视图：<b>status='ACTIVE' 且落在有效期区间内</b>的报价，
 * 判定条件与 catalog 侧的 {@code effectiveOf} / {@code overview} 逐字同源。
 * 报价的**阶梯价随行返回**（{@code tiers}），所以本端口一次就能拿到「某个数量处的单价」。
 *
 * <p>⚠️ <b>租户不由本端口传递</b>：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * catalog 侧仍按已认证成员关系解析租户并强制 RLS。隔离因此是<b>两道</b>
 * （trade 侧 RLS + catalog 侧 RLS），而不是「本服务替对方决定租户」。
 */
@Service
public class SupplierRatingQuotePort {

  /** 单页条数（与 catalog 侧的 {@code MAX_PAGE_SIZE} 一致）。 */
  private static final int PAGE_SIZE = 200;

  /** 最多翻多少页（200 × 5 = 1000 条有效报价）。 */
  private static final int MAX_PAGES = 5;

  /**
   * catalog 侧的一条报价（只声明本模块真正用到的字段）。
   *
   * <p>刻意只声明用到的字段：读得越少，跨服务契约越不容易在对方加字段时把本模块撑爆。
   *
   * @param supplierRoleId 供应商的 <b>SUPPLIER 角色 id</b>（与 trade 全线的 {@code supplier_ref_id} 同口径）
   * @param skuId 商品 id
   * @param currency 币种（ISO 4217 三位码）
   * @param tiers 阶梯价（起订量 + 单价）
   */
  public record QuoteView(
      UUID supplierRoleId, UUID skuId, String currency, List<SupplierRatingRules.QuoteTier> tiers) {}

  /** 报价台账列表的响应形状。 */
  record QuotePage(List<QuoteRowView> items, long total, int page, int size) {}

  /** 报价台账列表里的一行。 */
  record QuoteRowView(
      UUID supplierRoleId, UUID skuId, String currency, List<TierRowView> tiers) {}

  /** 阶梯价的一档。 */
  record TierRowView(BigDecimal minQuantity, BigDecimal unitPrice) {}

  /** 端口专用的 ObjectMapper：**忽略上游多出来的字段**。 */
  private static final ObjectMapper PORT_JSON =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private final RestClient client;

  public SupplierRatingQuotePort(@Value("${CATALOG_URL:http://127.0.0.1:8082}") String url) {
    var factory =
        new JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    // 读超时给到 10 秒：一次评级计算要翻几页报价台账，比单条读慢一截，
    // 但仍然必须有上限 —— 没有上限时商品主数据一慢就把本服务的请求线程全占住。
    factory.setReadTimeout(Duration.ofSeconds(10));
    var converter = new MappingJackson2HttpMessageConverter(PORT_JSON);
    this.client =
        RestClient.builder()
            .baseUrl(url)
            .requestFactory(factory)
            .messageConverters(java.util.List.of(converter))
            .build();
  }

  /**
   * 读某一���上的**全部有效报价**，按 SKU 分组。
   *
   * <p>翻页上限 {@link #MAX_PAGES}：报价台账可能有上千条，一次全量翻完会把评级计算变成
   * 一个长请求。**截断是如实报出来的**（{@link QuoteSnapshot#truncated}），
   * 不是悄悄少算一部分。
   *
   * @param at 判定「有效」的那一天（评级用周期结束日）
   */
  public QuoteSnapshot effectiveQuotes(LocalDate at, String bearer) {
    Map<UUID, List<QuoteView>> bySku = new LinkedHashMap<>();
    long total = 0;
    boolean truncated = false;
    for (int page = 0; page < MAX_PAGES; page++) {
      final int pageIndex = page;
      QuotePage body;
      try {
        body =
            client
                .get()
                .uri(
                    uriBuilder ->
                        uriBuilder
                            .path("/catalog/supplier-quotes")
                            .queryParam("effectiveOnly", "true")
                            .queryParam("at", at.toString())
                            .queryParam("size", PAGE_SIZE)
                            .queryParam("page", pageIndex)
                            .build())
                .header("Authorization", bearer)
                .retrieve()
                .body(QuotePage.class);
      } catch (RestClientResponseException e) {
        if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
          throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
        }
        if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
          throw new ResponseStatusException(
              HttpStatus.FORBIDDEN, "当前身份无权读取报价台账，价格竞争力暂时算不出来");
        }
        throw unavailable("商品主数据服务返回 " + e.getStatusCode().value() + "，价格竞争力暂时算不出来");
      } catch (RestClientException e) {
        throw unavailable("商品主数据服务暂时不可达");
      }
      if (body == null || body.items() == null) {
        throw unavailable("商品主数据服务没有返回报价台账");
      }
      total = body.total();
      for (QuoteRowView row : body.items()) {
        if (row == null || row.skuId() == null || row.supplierRoleId() == null) continue;
        List<SupplierRatingRules.QuoteTier> tiers = new ArrayList<>();
        if (row.tiers() != null) {
          for (TierRowView t : row.tiers()) {
            if (t == null || t.unitPrice() == null) continue;
            tiers.add(new SupplierRatingRules.QuoteTier(t.minQuantity(), t.unitPrice()));
          }
        }
        bySku
            .computeIfAbsent(row.skuId(), k -> new ArrayList<>())
            .add(new QuoteView(row.supplierRoleId(), row.skuId(), row.currency(), tiers));
      }
      if (body.items().size() < PAGE_SIZE) {
        return new QuoteSnapshot(bySku, total, false);
      }
      truncated = true;
    }
    return new QuoteSnapshot(bySku, total, truncated);
  }

  /**
   * 一次读回来的报价快照。
   *
   * @param truncated 报价台账被翻页上限截断了（{@code true} ⇒ 价格竞争力只覆盖了一部分市场）
   */
  public record QuoteSnapshot(
      Map<UUID, List<QuoteView>> bySku, long total, boolean truncated) {}

  private ResponseStatusException unavailable(String reason) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        reason
            + "：市场报价的唯一来源是商品主数据服务的授权只读接口"
            + "（3.6.2 报价台账，跨库视图与跨服务 SQL 均被 AGENTS.md 禁止），"
            + "本服务不会凭空造一份市场价格出来");
  }
}
