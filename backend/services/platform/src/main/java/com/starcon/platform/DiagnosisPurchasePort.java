package com.starcon.platform;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * **6 采购（trade 服务，端口 8085）**的跨服务只读端口：采购**成本**事实。
 *
 * <p>蓝图 18.4.2.3 的利润异常归因要回答「为什么这个 SKU 不赚钱了」，最硬的依据是
 * **采购单价 / 采购金额**。这些事实住在 trade 的 {@code purchase_analytics_fact} 表里，
 * 本端口只读它，<b>不抄它</b>。
 *
 * <p>⚠️ <b>刻意不并进 18.4.2.2 的 {@code ForecastPurchasePort}</b>：那个端口只取
 * PLAN / RECEIPT 的**数量**（用来算未到货量），本端口要取 COST 的**单价与金额**。
 * 两个口径不同的读需求各自成端口，改一个不会连带另一个。
 *
 * <p>⚠️ <b>只转发调用方自己的 bearer，不传租户号</b>：上游仍按已认证成员关系解析租户
 * 并强制 RLS。租户隔离因此是<b>两道</b>（platform 侧 RLS + 上游侧 RLS）。
 *
 * <p>⚠️ <b>取不到就 503</b>，绝不返回空集合冒充「上游没有采购成本」——
 * 一个空集合在诊断页面上会长成「这个 SKU 没有成本问题」，而真相是「这一格没读出来」。
 */
@Service
public class DiagnosisPurchasePort {

  /** 成本类采购事实的 factKind。 */
  static final String FACT_KIND_COST = "COST";

  private final PlatformUpstreamClient client;

  public DiagnosisPurchasePort(@Value("${TRADE_URL:http://127.0.0.1:8085}") String url) {
    this.client = PlatformUpstreamClient.to("trade 服务", url);
  }

  /** 上游一行采购事实（只声明本模块真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CostFact(
      String id,
      String factKind,
      String sourceDocNo,
      LocalDate occurredDate,
      String skuCode,
      String skuName,
      BigDecimal quantity,
      BigDecimal unitPrice,
      BigDecimal amount,
      String currency) {}

  /** 上游采购事实页（列表字段是 {@code items}）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record FactPage(List<CostFact> items, long total, int page, int size, String note) {}

  /**
   * 拉一段窗口内的采购**成本**事实（按 SKU 过滤）。
   *
   * @param skuCode 要诊断的 SKU；为空时拉全部（调用方自己按 SKU 归拢）
   */
  public List<CostFact> costFacts(LocalDate from, LocalDate to, String skuCode, String bearer) {
    var out = new ArrayList<CostFact>();
    var page = 0;
    while (page < PlatformUpstreamClient.MAX_PAGES) {
      final int current = page;
      FactPage batch =
          client.get(
              uriBuilder -> {
                uriBuilder
                    .path("/trade/purchase-analytics/facts")
                    .queryParam("from", from.toString())
                    .queryParam("to", to.toString())
                    .queryParam("factKind", FACT_KIND_COST)
                    .queryParam("page", current)
                    .queryParam("size", PlatformUpstreamClient.PAGE_SIZE);
                return uriBuilder;
              },
              bearer,
              FactPage.class);
      var items = batch.items() == null ? List.<CostFact>of() : batch.items();
      for (var row : items) {
        if (!FACT_KIND_COST.equals(row.factKind())) continue;
        if (row.skuCode() == null || row.skuCode().isBlank()) continue;
        // 🔴 上游的 keyword 是模糊匹配（可能命中单号 / 供应商名）⇒ 这里**按 skuCode 精确过滤**，
        //   否则会把别的 SKU 的成本算到本 SKU 头上。
        if (skuCode != null && !skuCode.isBlank() && !skuCode.equals(row.skuCode())) continue;
        out.add(row);
      }
      if (items.isEmpty()) break;
      if (items.size() < PlatformUpstreamClient.PAGE_SIZE) break;
      page++;
    }
    return out;
  }
}
