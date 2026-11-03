package com.starcon.platform;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * **6 采购（trade 服务，端口 8085）**的跨服务只读端口：采购事实（计划量 / 到货量）。
 *
 * <p>蓝图 18.4.2.2 的关系句点名的上游含「6 采购」。本端口读的是
 * {@code GET /trade/purchase-analytics/facts}：它的 {@code factKind} 分三类 ——
 * {@code PLAN}（采购计划量）、{@code RECEIPT}（到货量）、{@code COST}（成本）。
 *
 * <p>🔴 <b>本模块只把采购侧未到货量当「参考信息」展示，不参与补货扣减</b>：
 * 7 库存的「在途净额」端点里已经含采购发运腿，两边都扣会**重复扣减** ⇒ 建议量偏小。
 * （理由写在补货建议的 basis 里，页面直接显示。）
 *
 * <p>⚠️ <b>只转发调用方自己的 bearer，不传租户号</b>：上游仍按已认证成员关系解析租户
 * 并强制 RLS。租户隔离是<b>两道</b>（platform 侧 RLS + 上游侧 RLS）。
 *
 * <p>⚠️ <b>取不到就 503</b>：读不到采购事实时，采购侧参考量为空，
 * 补货建议**照常生成**（在途来自 7 库存）—— 但依据里会写明「采购侧未到货量读不到」。
 */
@Service
public class ForecastPurchasePort {

  /** 采购计划量的 factKind。 */
  static final String FACT_KIND_PLAN = "PLAN";

  /** 到货量的 factKind。 */
  static final String FACT_KIND_RECEIPT = "RECEIPT";

  private final PlatformUpstreamClient client;

  public ForecastPurchasePort(@Value("${TRADE_URL:http://127.0.0.1:8085}") String url) {
    this.client = PlatformUpstreamClient.to("trade 服务", url);
  }

  /** 上游一行采购事实（只声明本模块真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record FactRow(
      String factKind,
      String sourceType,
      LocalDate occurredDate,
      String skuCode,
      String skuName,
      BigDecimal quantity) {}

  /** 上游采购事实页（列表字段是 {@code items}）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record FactPage(List<FactRow> items, long total, int page, int size, String note) {}

  /**
   * 一个 SKU 的采购侧未到货量（计划量 − 到货量）。
   *
   * @param plannedQuantity 计划采购量
   * @param receivedQuantity 已到货量
   * @param pendingQuantity 未到货量（**不为负**：计划量与到货量对不上时取 0，并如实说明）
   */
  public record PendingPurchase(String skuCode, String skuName, BigDecimal plannedQuantity, BigDecimal receivedQuantity, BigDecimal pendingQuantity) {}

  /**
   * 拉一段窗口内按 SKU 的采购计划量与到货量。
   *
   * <p>⚠️ **只取 SKU 维度的行**：上游的采购事实也可能落在供应商 / 店铺维度上，
   * 那些行没有 skuCode ⇒ 不参与按 SKU 的汇总。
   */
  public List<PendingPurchase> pendingBySku(LocalDate from, LocalDate to, String bearer) {
    var planned = new LinkedHashMap<String, PendingPurchase>();
    var received = new LinkedHashMap<String, BigDecimal>();
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
                    .queryParam("page", current)
                    .queryParam("size", PlatformUpstreamClient.PAGE_SIZE);
                return uriBuilder;
              },
              bearer,
              FactPage.class);
      var items = batch.items() == null ? List.<FactRow>of() : batch.items();
      for (var row : items) {
        if (row.skuCode() == null || row.skuCode().isBlank()) continue;
        if (row.quantity() == null) continue;
        if (FACT_KIND_PLAN.equals(row.factKind())) {
          var existing = planned.get(row.skuCode());
          planned.put(
              row.skuCode(),
              new PendingPurchase(
                  row.skuCode(),
                  row.skuName() == null ? (existing == null ? "" : existing.skuName()) : row.skuName(),
                  (existing == null ? BigDecimal.ZERO : existing.plannedQuantity()).add(row.quantity()),
                  received.getOrDefault(row.skuCode(), BigDecimal.ZERO),
                  BigDecimal.ZERO));
        } else if (FACT_KIND_RECEIPT.equals(row.factKind())) {
          received.merge(row.skuCode(), row.quantity(), BigDecimal::add);
        }
      }
      if (items.isEmpty()) break;
      if (items.size() < PlatformUpstreamClient.PAGE_SIZE) break;
      page++;
    }
    var out = new ArrayList<PendingPurchase>();
    for (var entry : planned.entrySet()) {
      var receivedQty = received.getOrDefault(entry.getKey(), BigDecimal.ZERO);
      var pending = entry.getValue().plannedQuantity().subtract(receivedQty);
      if (pending.signum() < 0) pending = BigDecimal.ZERO;
      out.add(
          new PendingPurchase(
              entry.getKey(),
              entry.getValue().skuName(),
              entry.getValue().plannedQuantity(),
              receivedQty,
              pending));
    }
    return out;
  }
}
