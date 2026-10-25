package com.starcon.reporting;

import com.starcon.reporting.ReportingProjectionModels.ProjectionDrilldown;
import com.starcon.reporting.ReportingProjectionModels.ProjectionEvent;
import com.starcon.reporting.ReportingProjectionModels.ProjectionFact;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 13.1.1.1 的**拉取适配器**：把上游的授权只读端点读成事实，归一化成 envelope，交给投影器。
 *
 * <p>⚠️ <b>为什么既有「拉」又有「推」</b>：
 *
 * <ul>
 *   <li><b>推</b>（{@code POST /reporting/projection/events}）是蓝图设计的**主路径**：
 *       「基于事件构建的可重建投影」。上游服务把事实作为事件投递过来，本服务只投影。
 *   <li><b>拉</b>（本类）是**同一条管道的另一种入口**：本服务自己去上游的授权只读 API 取事实，
 *       取到后<b>归一化成同一套 envelope</b>，再交给<b>同一个投影器</b>。
 * </ul>
 *
 * <p>⇒ 两条入口共用一份幂等键、一份口径表、一套重建逻辑。
 * 拉取端点在本仓**先落地**，是因为上游的事实型 outbox 事件尚未产出（catalog / channel 的
 * {@code outbox_event} 目前只有商品主数据类事件）—— 但这**不改变**「投影可重建」的性质。
 *
 * <p>⚠️ <b>内容寻址的幂等键</b>：拉取适配器给每条上游事实生成
 * {@code sourceEventId = 服务:流:上游行标识:内容摘要}。
 *
 * <ul>
 *   <li>同一行内容没变 ⇒ id 不变 ⇒ 事件账本判重 ⇒ <b>跳过</b>（重复拉取不会翻倍）；
 *   <li>同一行内容变了 ⇒ id 变 ⇒ 重新投影 ⇒ 事实行按幂等唯一键<b>覆盖</b>（不是追加）。
 * </ul>
 *
 * <p>这样「重复拉取」与「上游改了」两种情况用同一个键表达，不需要两套机制。
 */
@Service
public class ReportingRefreshService {

  /**
   * finance 利润表里「本期损益」行的行码（**稳定键**）。
   *
   * <p>finance 的利润表是**模板驱动**的（租户自己配报表模板），行名会变，但
   * 「合计」段落的行码固定是 {@code TOTAL:PROFIT} —— 用它定位比用行名「本期损益」
   * 稳（租户改模板行名时行码不变）。
   */
  private static final String NET_PROFIT_LINE_CODE = "TOTAL:PROFIT";

  private final ReportingTradePort trade;
  private final ReportingChannelPort channel;
  private final ReportingFinancePort finance;
  private final ReportingWarehousePort warehouse;
  private final ReportingCatalogPort catalog;
  private final ReportingProjectionService projection;
  private final ReportingExpenseRefreshService expenseRefresh;

  public ReportingRefreshService(
      ReportingTradePort trade,
      ReportingChannelPort channel,
      ReportingFinancePort finance,
      ReportingWarehousePort warehouse,
      ReportingCatalogPort catalog,
      ReportingProjectionService projection,
      ReportingExpenseRefreshService expenseRefresh) {
    this.trade = trade;
    this.channel = channel;
    this.finance = finance;
    this.warehouse = warehouse;
    this.catalog = catalog;
    this.projection = projection;
    this.expenseRefresh = expenseRefresh;
  }

  /**
   * 刷新：拉取 → 归一化 → 投影。
   *
   * @param sources 要刷新的来源（空 ⇒ 全部）。取值：{@code trade-orders} / {@code trade-returns} /
   *     {@code channel-ads} / {@code finance-receivables} / {@code finance-cost} /
   *     {@code finance-profit} / {@code warehouse-inventory} / {@code catalog-rates}，
   *     以及 13.4.2.2 费用差异的五路（{@code catalog-expense-platform} /
   *     {@code catalog-expense-freight} / {@code catalog-expense-head-freight} /
   *     {@code finance-expense-settlement} / {@code channel-expense-ad-budget}）。
   * @param timezone 分桶所用时区（跨时区店铺汇总必须显示所用时区）。
   */
  public List<ReportingProjectionModels.RefreshResult> refresh(
      UUID tenantId,
      String bearer,
      LocalDate from,
      LocalDate to,
      List<String> sources,
      ZoneId timezone) {
    var wanted =
        sources == null || sources.isEmpty()
            ? List.of(
                "trade-orders",
                "trade-returns",
                "channel-ads",
                "finance-receivables",
                "finance-cost",
                "finance-profit",
                "warehouse-inventory",
                "catalog-rates",
                "catalog-expense-platform",
                "catalog-expense-freight",
                "catalog-expense-head-freight",
                "finance-expense-settlement",
                "channel-expense-ad-budget")
            : sources;
    var results = new ArrayList<ReportingProjectionModels.RefreshResult>();
    // ⚠️ 费用差异的五路上游由**它自己的拉取适配器**处理（ReportingExpenseRefreshService），
    //   但投影仍然交给**同一个**投影器 ⇒ 幂等 / 口径 / 重建三条纪律仍然只有一处实现。
    var expenseSources = new ArrayList<String>();
    for (var source : wanted) {
      if (ReportingExpenseRefreshService.SOURCES.contains(source)) {
        expenseSources.add(source);
        continue;
      }
      try {
        results.add(switch (source) {
          case "trade-orders" -> refreshTradeOrders(tenantId, bearer, from, to, timezone);
          case "trade-returns" -> refreshTradeReturns(tenantId, bearer, from, to, timezone);
          case "channel-ads" -> refreshChannelAds(tenantId, bearer, from, to, timezone);
          case "finance-receivables" -> refreshReceivables(tenantId, bearer, to, timezone);
          case "finance-cost" -> refreshCost(tenantId, bearer, timezone);
          case "finance-profit" -> refreshProfit(tenantId, bearer, from, to, timezone);
          case "warehouse-inventory" -> refreshInventory(tenantId, bearer, timezone);
          case "catalog-rates" -> refreshExchangeRates(tenantId, bearer, to, timezone);
          default ->
              new ReportingProjectionModels.RefreshResult(
                  source, source, 0, 0, 0, "SKIPPED", "未知的刷新来源：" + source);
        });
      } catch (org.springframework.web.server.ResponseStatusException e) {
        // ⚠️ 上游读不到 ⇒ 这一路**失败**，不是「这一路没有数据」。
        // 失败原因原样带出去（503 的消息写明了是「读不到」还是「没有权限」）。
        results.add(
            new ReportingProjectionModels.RefreshResult(
                source, source, 0, 0, 0, "FAILED", e.getReason() == null ? "上游读不到" : e.getReason()));
      }
    }
    if (!expenseSources.isEmpty()) {
      // ⚠️ 费用差异这一组由它自己的适配器跑（内部逐路 try/catch，一路上游读不到不影响其它路）。
      results.addAll(expenseRefresh.refresh(tenantId, bearer, from, to, expenseSources, timezone));
    }
    return results;
  }

  // ==========================================================================
  // trade：销售订单（销售额 / 订单量 / 销量 + 订单与 SKU 下钻）
  // ==========================================================================

  private ReportingProjectionModels.RefreshResult refreshTradeOrders(
      UUID tenantId,
      String bearer,
      LocalDate from,
      LocalDate to,
      ZoneId timezone) {
    var events = new ArrayList<ProjectionEvent>();
    int detailFetches = 0;
    boolean truncated = false;
    for (int page = 0; page < ReportingTradePort.MAX_PAGES; page++) {
      var rows = trade.salesOrders(from, to, page, bearer);
      if (rows.rows() == null || rows.rows().isEmpty()) break;
      for (var row : rows.rows()) {
        if (row.submittedAt() == null) continue;
        var bucketDate = row.submittedAt().atZoneSameInstant(timezone).toLocalDate();
        var facts = new ArrayList<ProjectionFact>();
        var drilldowns = new ArrayList<ProjectionDrilldown>();
        var currency = row.currency() == null ? "USD" : row.currency();
        facts.add(
            fact(ReportingMetricCatalog.SALES_AMOUNT, bucketDate, currency, row.grandTotalAmount(), row.orderNo()));
        facts.add(
            fact(
                ReportingMetricCatalog.ORDER_COUNT,
                bucketDate,
                currency,
                BigDecimal.ONE,
                row.orderNo()));
        drilldowns.add(
            drilldown(
                ReportingMetricCatalog.SALES_AMOUNT,
                bucketDate,
                currency,
                "ORDER",
                row.orderNo(),
                row.orderNo(),
                BigDecimal.ZERO,
                row.grandTotalAmount(),
                row.orderNo()));

        // 销量与 SKU 下钻只在**订单明细**里 ⇒ 逐单取（有上限）。
        if (detailFetches < ReportingTradePort.MAX_DETAIL_FETCHES && row.id() != null) {
          detailFetches++;
          var detail = trade.salesOrderDetail(row.id(), bearer);
          if (detail != null && detail.lines() != null) {
            for (var line : detail.lines()) {
              if (line.quantity() == null) continue;
              facts.add(
                  fact(
                      ReportingMetricCatalog.UNIT_SOLD,
                      bucketDate,
                      currency,
                      line.quantity(),
                      row.orderNo() + "#" + line.skuCode()));
              drilldowns.add(
                  drilldown(
                      ReportingMetricCatalog.UNIT_SOLD,
                      bucketDate,
                      currency,
                      "SKU",
                      line.skuCode(),
                      line.skuName() == null ? line.skuCode() : line.skuName(),
                      line.quantity(),
                      line.lineAmount() == null ? BigDecimal.ZERO : line.lineAmount(),
                      row.orderNo() + "#" + line.skuCode()));
            }
          }
        } else if (row.id() != null) {
          truncated = true;
        }

        events.add(
            event(
                "trade",
                "trade:orders:" + row.orderNo(),
                ReportingProjectionModels.ReportingEventTypes.SALES_FACT,
                row.id(),
                row.submittedAt(),
                timezone,
                facts,
                drilldowns));
      }
      if (rows.rows().size() < ReportingTradePort.PAGE_SIZE) break;
    }
    var applied = projection.applyBatch(tenantId, events);
    var note =
        truncated
            ? "订单明细取到 "
                + ReportingTradePort.MAX_DETAIL_FETCHES
                + " 张为止（超过部分**未计入销量**）：销量与 SKU 下钻需要逐单取明细。"
            : "已按订单逐条投影（订单量与销售额全量，销量取到明细上限为止）。";
    return result("trade", "trade:orders", events.size(), applied, note);
  }

  // ==========================================================================
  // trade：售后退货（退货额 → 退货率）
  // ==========================================================================

  private ReportingProjectionModels.RefreshResult refreshTradeReturns(
      UUID tenantId,
      String bearer,
      LocalDate from,
      LocalDate to,
      ZoneId timezone) {
    var events = new ArrayList<ProjectionEvent>();
    for (int page = 0; page < ReportingTradePort.MAX_PAGES; page++) {
      var rows = trade.aftersalesCases(from, to, page, bearer);
      if (rows.rows() == null || rows.rows().isEmpty()) break;
      for (var row : rows.rows()) {
        if (row.requestedAmount() == null) continue;
        // ⚠️ 上游字段是 receivedAt（受理时刻），不是 createdAt —— 写错会拿到 null，
        // 然后落到 now() 兜底 ⇒ **每次刷新的分桶日都不一样**，投影不可重放。
        var occurred = row.receivedAt();
        if (occurred == null) continue;
        var bucketDate = occurred.atZoneSameInstant(timezone).toLocalDate();
        var currency = row.currency() == null ? "USD" : row.currency();
        var facts = new ArrayList<ProjectionFact>();
        facts.add(
            fact(
                ReportingMetricCatalog.RETURNED_AMOUNT,
                bucketDate,
                currency,
                row.requestedAmount(),
                row.caseNo()));
        var drilldowns = new ArrayList<ProjectionDrilldown>();
        drilldowns.add(
            drilldown(
                ReportingMetricCatalog.RETURNED_AMOUNT,
                bucketDate,
                currency,
                "ORDER",
                row.caseNo(),
                row.orderNo() == null ? row.caseNo() : row.orderNo(),
                row.requestedQuantity() == null ? BigDecimal.ZERO : row.requestedQuantity(),
                row.requestedAmount(),
                row.caseNo()));
        events.add(
            event(
                "trade",
                "trade:returns:" + row.caseNo(),
                ReportingProjectionModels.ReportingEventTypes.RETURN_FACT,
                row.id(),
                occurred,
                timezone,
                facts,
                drilldowns));
      }
      if (rows.rows().size() < ReportingTradePort.PAGE_SIZE) break;
    }
    var applied = projection.applyBatch(tenantId, events);
    return result(
        "trade",
        "trade:returns",
        events.size(),
        applied,
        "按售后单逐条投影退货额（退货率由口径表按「退货额 ÷ 销售额」派生）。");
  }

  // ==========================================================================
  // channel：广告花费
  // ==========================================================================

  private ReportingProjectionModels.RefreshResult refreshChannelAds(
      UUID tenantId,
      String bearer,
      LocalDate from,
      LocalDate to,
      ZoneId timezone) {
    var events = new ArrayList<ProjectionEvent>();
    for (int page = 0; page < ReportingChannelPort.MAX_PAGES; page++) {
      var rows = channel.adRows(from, to, page, bearer);
      if (rows.items() == null || rows.items().isEmpty()) break;
      for (var row : rows.items()) {
        if (row.spend() == null || row.spend().signum() <= 0) continue;
        var bucketDate = row.metricFrom() == null ? (row.metricTo() == null ? null : row.metricTo()) : row.metricFrom();
        if (bucketDate == null) continue;
        var currency = row.currency() == null ? "USD" : row.currency();
        var reference =
            row.externalCampaignId() == null || row.externalCampaignId().isBlank()
                ? row.dimensionKey()
                : row.externalCampaignId();
        if (reference == null || reference.isBlank()) continue;
        var facts = new ArrayList<ProjectionFact>();
        facts.add(
            fact(
                ReportingMetricCatalog.AD_SPEND,
                bucketDate,
                currency,
                row.spend(),
                reference + "@" + bucketDate));
        var drilldowns = new ArrayList<ProjectionDrilldown>();
        drilldowns.add(
            drilldown(
                ReportingMetricCatalog.AD_SPEND,
                bucketDate,
                currency,
                "CAMPAIGN",
                reference,
                row.dimensionLabel() == null ? reference : row.dimensionLabel(),
                BigDecimal.ZERO,
                row.spend(),
                reference + "@" + bucketDate));
        events.add(
            event(
                "channel",
                "channel:ads:" + reference + ":" + bucketDate,
                ReportingProjectionModels.ReportingEventTypes.AD_SPEND_FACT,
                null,
                bucketDate.atStartOfDay(timezone).toOffsetDateTime(),
                timezone,
                facts,
                drilldowns));
      }
      if (rows.items().size() < ReportingChannelPort.PAGE_SIZE) break;
    }
    var applied = projection.applyBatch(tenantId, events);
    return result(
        "channel",
        "channel:ads",
        events.size(),
        applied,
        "按广告活动 × 日投影花费（**只取花费，不取销售额**）。");
  }

  // ==========================================================================
  // finance：应收余额（时点快照）
  // ==========================================================================

  /**
   * finance：应收余额（时点快照）。
   *
   * <p>⚠️ **这是本模块唯一带店铺归属的事实**（上游 {@code finance_receivable.shop_id}
   * 真的存在），所以它投影成**双层立方**：
   *
   * <ul>
   *   <li><b>SHOP 层</b>：逐张应收单一行，带 {@code shop_id}（上游没给店铺时为空 ⇒
   *       「店铺归属未知」，不猜）；
   *   <li><b>COMPANY 层</b>：按币种一行合计（{@code source_reference} 前缀 {@code ROLLUP:}）。
   * </ul>
   *
   * <p>两层**不重叠**：查询永远只按一个 {@code scope_type} 取数，所以公司口径不会被
   * 店铺口径重复累加。少了 COMPANY 层，公司视角就会漏掉全部应收。
   */
  private ReportingProjectionModels.RefreshResult refreshReceivables(
      UUID tenantId, String bearer, LocalDate to, ZoneId timezone) {
    var facts = new ArrayList<ProjectionFact>();
    var drilldowns = new ArrayList<ProjectionDrilldown>();
    // 公司层合计：币种 → 未核销余额。
    var companyTotals = new java.util.LinkedHashMap<String, BigDecimal>();
    for (int page = 0; page < ReportingFinancePort.MAX_PAGES; page++) {
      var rows = finance.receivables(null, to, page, bearer);
      if (rows.items() == null || rows.items().isEmpty()) break;
      for (var row : rows.items()) {
        if (row.amount() == null) continue;
        var currency = row.currency() == null ? "USD" : row.currency();
        var settled = row.settledAmount() == null ? BigDecimal.ZERO : row.settledAmount();
        var balance = row.amount().subtract(settled);
        if (balance.signum() <= 0) continue;
        companyTotals.merge(currency, balance, BigDecimal::add);
        facts.add(
            fact(
                "SHOP",
                null,
                row.shopId(),
                ReportingMetricCatalog.RECEIVABLE_BALANCE,
                to,
                currency,
                balance,
                "RECEIVABLE:" + row.receivableNo()));
        drilldowns.add(
            drilldown(
                "SHOP",
                null,
                row.shopId(),
                ReportingMetricCatalog.RECEIVABLE_BALANCE,
                to,
                currency,
                "RECEIVABLE",
                row.receivableNo(),
                row.shopName() == null ? row.receivableNo() : row.shopName(),
                BigDecimal.ZERO,
                balance,
                "RECEIVABLE:" + row.receivableNo()));
      }
      if (rows.items().size() < ReportingFinancePort.PAGE_SIZE) break;
    }
    for (var entry : companyTotals.entrySet()) {
      facts.add(
          fact(
              "COMPANY",
              null,
              null,
              ReportingMetricCatalog.RECEIVABLE_BALANCE,
              to,
              entry.getKey(),
              entry.getValue(),
              "ROLLUP:" + ReportingMetricCatalog.RECEIVABLE_BALANCE));
    }
    var occurred = to.atStartOfDay(timezone).toOffsetDateTime();
    var events = new ArrayList<ProjectionEvent>();
    events.add(
        event(
            "finance",
            "finance:receivables:" + to,
            ReportingProjectionModels.ReportingEventTypes.RECEIVABLE_SNAPSHOT,
            null,
            occurred,
            timezone,
            facts,
            drilldowns));
    var applied = projection.applyBatch(tenantId, events);
    return result(
        "finance",
        "finance:receivables",
        events.size(),
        applied,
        "未核销应收余额的**时点快照**（余额不能累加，口径表里 aggregation=LAST）；"
            + "同时投影店铺层与公司层合计。");
  }

  // ==========================================================================
  // finance：出库成本（→ 毛利额 / 毛利率）
  // ==========================================================================

  private ReportingProjectionModels.RefreshResult refreshCost(
      UUID tenantId, String bearer, ZoneId timezone) {
    var events = new ArrayList<ProjectionEvent>();
    for (int page = 0; page < ReportingFinancePort.MAX_PAGES; page++) {
      var rows = finance.costEntries(null, page, bearer);
      if (rows.items() == null || rows.items().isEmpty()) break;
      for (var row : rows.items()) {
        if (row.costAmount() == null || row.costAmount().signum() == 0) continue;
        var occurred = row.occurredAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : row.occurredAt();
        var bucketDate = occurred.atZoneSameInstant(timezone).toLocalDate();
        var currency = row.currency() == null ? "USD" : row.currency();
        var reference = row.skuCode() == null ? row.id().toString() : row.skuCode();
        var facts = new ArrayList<ProjectionFact>();
        facts.add(
            fact(
                ReportingMetricCatalog.COGS_AMOUNT,
                bucketDate,
                currency,
                row.costAmount(),
                reference + "@" + bucketDate + "#" + row.id()));
        var drilldowns = new ArrayList<ProjectionDrilldown>();
        drilldowns.add(
            drilldown(
                ReportingMetricCatalog.COGS_AMOUNT,
                bucketDate,
                currency,
                "SKU",
                reference,
                reference,
                row.issueQuantity() == null ? BigDecimal.ZERO : row.issueQuantity(),
                row.costAmount(),
                reference + "@" + bucketDate + "#" + row.id()));
        events.add(
            event(
                "finance",
                "finance:cost:" + row.id(),
                ReportingProjectionModels.ReportingEventTypes.COST_FACT,
                row.id(),
                occurred,
                timezone,
                facts,
                drilldowns));
      }
      if (rows.items().size() < ReportingFinancePort.PAGE_SIZE) break;
    }
    var applied = projection.applyBatch(tenantId, events);
    return result(
        "finance",
        "finance:cost",
        events.size(),
        applied,
        "按成本分录投影出库成本（毛利额 = 销售额 − 成本，由口径表派生）。");
  }

  // ==========================================================================
  // warehouse + catalog：在手库存的成本法估值（→ 库存总额）
  // ==========================================================================

  /**
   * warehouse + catalog：在手库存的成本法估值（→ 库存总额）。
   *
   * <p>⚠️ **这是本模块唯一带法人归属的事实**（上游 {@code warehouse_inventory_balance}
   * 的 {@code legal_entity_id}），所以它同样投影成**双层立方**：LEGAL_ENTITY 层
   * （逐法人一行）+ COMPANY 层（按币种合计）。销售 / 成本 / 退货 / 广告这四类事实
   * 的上游**根本没有**店铺或法人归属（{@code sales_order} 表里就没有这两列），
   * 所以它们只落在 COMPANY 层 —— **这是上游的事实缺口，不是本模块偷懒**。
   */
  private ReportingProjectionModels.RefreshResult refreshInventory(
      UUID tenantId, String bearer, ZoneId timezone) {
    // ① 先读 SKU 成本构成（catalog 拥有单位成本）。
    var costs = new java.util.HashMap<UUID, ReportingCatalogPort.CostRow>();
    for (int page = 0; page < ReportingCatalogPort.MAX_PAGES; page++) {
      var rows = catalog.skuCosts(page, bearer);
      if (rows.items() == null || rows.items().isEmpty()) break;
      for (var row : rows.items()) {
        if (row.skuId() != null) costs.put(row.skuId(), row);
      }
      if (rows.items().size() < ReportingCatalogPort.PAGE_SIZE) break;
    }
    // ② 再读在手库存（warehouse 拥有数量）。
    var facts = new ArrayList<ProjectionFact>();
    var drilldowns = new ArrayList<ProjectionDrilldown>();
    // 公司层合计：币种 → 库存金额。
    var companyTotals = new java.util.LinkedHashMap<String, BigDecimal>();
    // 法人层合计：法人 + 币种 → 库存金额。
    var entityTotals = new java.util.LinkedHashMap<String, BigDecimal>();
    var snapshotDate = LocalDate.now(timezone);
    int missingCost = 0;
    for (int page = 0; page < ReportingWarehousePort.MAX_PAGES; page++) {
      var rows = warehouse.balances(page, bearer);
      if (rows.rows() == null || rows.rows().isEmpty()) break;
      for (var row : rows.rows()) {
        if (row.onHand() == null || row.onHand().signum() <= 0) continue;
        var cost = costs.get(row.skuId());
        if (cost == null || cost.currency() == null) {
          // ⚠️ **没有单位成本就不估值**：宁可少算也不要拿 0 成本把库存算成 0 金额。
          missingCost++;
          continue;
        }
        var unitCost =
            nvl(cost.purchaseCost())
                .add(nvl(cost.packagingCost()))
                .add(nvl(cost.otherDirectCost()))
                .setScale(6, RoundingMode.HALF_UP);
        var value = row.onHand().multiply(unitCost).setScale(4, RoundingMode.HALF_UP);
        companyTotals.merge(cost.currency(), value, BigDecimal::add);
        entityTotals.merge(
            row.legalEntityId() + "|" + cost.currency(), value, BigDecimal::add);
        var skuReference = row.skuCode() == null ? row.skuId().toString() : row.skuCode();
        facts.add(
            fact(
                "LEGAL_ENTITY",
                row.legalEntityId(),
                null,
                ReportingMetricCatalog.INVENTORY_VALUE,
                snapshotDate,
                cost.currency(),
                value,
                "STOCK:" + skuReference));
        drilldowns.add(
            drilldown(
                "LEGAL_ENTITY",
                row.legalEntityId(),
                null,
                ReportingMetricCatalog.INVENTORY_VALUE,
                snapshotDate,
                cost.currency(),
                "STOCK",
                skuReference,
                row.skuName() == null ? row.skuCode() : row.skuName(),
                row.onHand(),
                value,
                "STOCK:" + skuReference));
      }
      if (rows.rows().size() < ReportingWarehousePort.PAGE_SIZE) break;
    }
    for (var entry : entityTotals.entrySet()) {
      var parts = entry.getKey().split("\\|", 2);
      var legalEntityId = "null".equals(parts[0]) ? null : UUID.fromString(parts[0]);
      facts.add(
          fact(
              "LEGAL_ENTITY",
              legalEntityId,
              null,
              ReportingMetricCatalog.INVENTORY_VALUE,
              snapshotDate,
              parts[1],
              entry.getValue(),
              "ROLLUP:" + ReportingMetricCatalog.INVENTORY_VALUE + ":" + parts[0]));
    }
    for (var entry : companyTotals.entrySet()) {
      facts.add(
          fact(
              "COMPANY",
              null,
              null,
              ReportingMetricCatalog.INVENTORY_VALUE,
              snapshotDate,
              entry.getKey(),
              entry.getValue(),
              "ROLLUP:" + ReportingMetricCatalog.INVENTORY_VALUE));
    }
    var events = new ArrayList<ProjectionEvent>();
    events.add(
        event(
            "warehouse",
            "warehouse:inventory:" + snapshotDate,
            ReportingProjectionModels.ReportingEventTypes.INVENTORY_SNAPSHOT,
            null,
            snapshotDate.atStartOfDay(timezone).toOffsetDateTime(),
            timezone,
            facts,
            drilldowns));
    var applied = projection.applyBatch(tenantId, events);
    var note =
        missingCost == 0
            ? "在手数量 × catalog 成本构成（采购 + 包装 + 其他直接成本）的**时点快照**；"
                + "同时投影法人层与公司层合计。"
            : "在手数量 × catalog 成本构成的时点快照；其中 "
                + missingCost
                + " 行**没有登记单位成本**，未计入金额（宁可少算，不用 0 成本冒充）。";
    return result("warehouse", "warehouse:inventory", events.size(), applied, note);
  }

  /**
   * finance：净利（12.5.2.2 财务报表口径）。
   *
   * <p>⚠️ **净利口径归 12.5.2.2 财务报表（finance）所有**（卷 13 卷首「口径唯一」）：
   * 本服务**只读 finance 算好的「本期损益」行**（{@code lineCode = "TOTAL:PROFIT"}），
   * **不自己**把收入行加成本行 —— 后者会在 finance 改了科目归类时变成**第二个真相**。
   *
   * <p>⚠️ **分桶规则（设计决策）**：finance 的利润表按**会计期间**组织，不是按天。
   * 本服务把「本窗口的净利」落在**窗口所覆盖的最后一个会计期间的期末日**上，
   * 口径说明写进指标定义。刷新窗口变了 ⇒ 落点也变，所以**刷新窗口 = 报表口径**，
   * 这一点在端点的返回体里原样报出来。
   */
  private ReportingProjectionModels.RefreshResult refreshProfit(
      UUID tenantId, String bearer, LocalDate from, LocalDate to, ZoneId timezone) {
    // ① 找出与窗口有交集的会计期间（上游没有按日期过滤的参数，只能拉回来自己筛）。
    var periods = new ArrayList<ReportingFinancePort.PeriodRow>();
    for (int page = 0; page < ReportingFinancePort.MAX_PAGES; page++) {
      var rows = finance.periods(page, bearer);
      if (rows.items() == null || rows.items().isEmpty()) break;
      for (var row : rows.items()) {
        if (row.periodStart() == null || row.periodEnd() == null) continue;
        if (row.periodEnd().isBefore(from) || row.periodStart().isAfter(to)) continue;
        periods.add(row);
      }
      if (rows.items().size() < ReportingFinancePort.PAGE_SIZE) break;
    }
    if (periods.isEmpty()) {
      return new ReportingProjectionModels.RefreshResult(
          "finance",
          "finance:profit",
          0,
          0,
          0,
          "SKIPPED",
          "窗口内没有会计期间 —— finance 的利润表按会计期间组织，没有期间就没有净利可读。");
    }
    periods.sort(java.util.Comparator.comparing(ReportingFinancePort.PeriodRow::periodStart));
    var first = periods.get(0);
    var last = periods.get(periods.size() - 1);
    // ② 取这段期间的利润表，只认 finance 算好的「本期损益」行。
    var statement = finance.incomeStatement(first.id(), last.id(), bearer);
    var facts = new ArrayList<ProjectionFact>();
    var bucketDate = last.periodEnd();
    for (var group : statement.currencyGroups() == null ? List.<ReportingFinancePort.IncomeStatementCurrencyGroup>of() : statement.currencyGroups()) {
      for (var row : group.rows() == null ? List.<ReportingFinancePort.IncomeStatementRow>of() : group.rows()) {
        if (!NET_PROFIT_LINE_CODE.equals(row.lineCode())) continue;
        if (row.periodNet() == null) continue;
        facts.add(
            fact(
                "COMPANY",
                null,
                null,
                ReportingMetricCatalog.NET_PROFIT,
                bucketDate,
                group.currency(),
                row.periodNet(),
                "PROFIT:" + group.currency()));
      }
    }
    var events = new ArrayList<ProjectionEvent>();
    events.add(
        event(
            "finance",
            "finance:profit:" + bucketDate,
            ReportingProjectionModels.ReportingEventTypes.PROFIT_FACT,
            null,
            bucketDate.atStartOfDay(timezone).toOffsetDateTime(),
            timezone,
            facts,
            List.of()));
    var applied = projection.applyBatch(tenantId, events);
    var note =
        "净利取 12.5.2.2 利润表的「本期损益」行（lineCode=TOTAL:PROFIT），落在窗口内"
            + "最后一个会计期间的期末日 "
            + bucketDate
            + "。";
    return result("finance", "finance:profit", events.size(), applied, note);
  }

  /**
   * catalog：汇率快照（跨币种折算 + 显示汇率与时点）。
   *
   * <p>⚠️ 快照表是 **append-only**（DB 层已 REVOKE UPDATE/DELETE），所以同一币种对
   * 反复刷新是**追加多条**，不是覆盖。查询侧按 {@code as_of} 取**最近的那条**，
   * 并把它连同时点一起返回 —— 「今天的汇率变了不能让上月的报表变」。
   */
  private ReportingProjectionModels.RefreshResult refreshExchangeRates(
      UUID tenantId, String bearer, LocalDate to, ZoneId timezone) {
    // 每个币种对只留最新的一条（快照表 append-only，服务层负责挑「最新」）。
    var latest = new java.util.LinkedHashMap<String, ReportingCatalogPort.RateListRow>();
    for (int page = 0; page < ReportingCatalogPort.MAX_PAGES; page++) {
      var rows = catalog.rates(to, page, bearer);
      if (rows.items() == null || rows.items().isEmpty()) break;
      for (var row : rows.items()) {
        if (row.baseCurrency() == null || row.quoteCurrency() == null) continue;
        if (row.baseCurrency().equals(row.quoteCurrency())) continue;
        if (row.rate() == null || row.rate().signum() <= 0) continue;
        var key = row.baseCurrency() + "|" + row.quoteCurrency();
        var seen = latest.get(key);
        if (seen == null
            || (row.effectiveDate() != null
                && (seen.effectiveDate() == null || row.effectiveDate().isAfter(seen.effectiveDate())))) {
          latest.put(key, row);
        }
      }
      if (rows.items().size() < ReportingCatalogPort.PAGE_SIZE) break;
    }
    var events = new ArrayList<ProjectionEvent>();
    int snapshots = 0;
    for (var row : latest.values()) {
      var asOf =
          (row.effectiveDate() == null ? to : row.effectiveDate())
              .atStartOfDay(java.time.ZoneOffset.UTC)
              .toOffsetDateTime();
      projection.recordCurrencyRate(
          tenantId, row.baseCurrency(), row.quoteCurrency(), row.rate(), asOf, "catalog");
      snapshots++;
    }
    return new ReportingProjectionModels.RefreshResult(
        "catalog",
        "catalog:rates",
        snapshots,
        snapshots,
        0,
        "OK",
        "汇率快照按币种对的**最新生效日**各留一条（append-only）；"
            + "跨币种合计时按 as_of 取最近的那条，并把汇率与时点一起返回。");
  }

  // ==========================================================================
  // 小工具
  // ==========================================================================

  /** 公司级事实（上游不带店铺 / 法人归属的事实走这里）。 */
  private ProjectionFact fact(
      String metricCode, LocalDate bucketDate, String currency, BigDecimal value, String sourceReference) {
    return fact("COMPANY", null, null, metricCode, bucketDate, currency, value, sourceReference);
  }

  /** 带维度的事实。 */
  private ProjectionFact fact(
      String scopeType,
      UUID legalEntityId,
      UUID shopId,
      String metricCode,
      LocalDate bucketDate,
      String currency,
      BigDecimal value,
      String sourceReference) {
    return new ProjectionFact(
        scopeType,
        legalEntityId,
        shopId,
        metricCode,
        bucketDate,
        currency,
        value,
        BigDecimal.ZERO,
        sourceReference);
  }

  /** 公司级构成明细。 */
  private ProjectionDrilldown drilldown(
      String metricCode,
      LocalDate bucketDate,
      String currency,
      String sourceKind,
      String referenceId,
      String referenceLabel,
      BigDecimal quantity,
      BigDecimal value,
      String sourceReference) {
    return drilldown(
        "COMPANY",
        null,
        null,
        metricCode,
        bucketDate,
        currency,
        sourceKind,
        referenceId,
        referenceLabel,
        quantity,
        value,
        sourceReference);
  }

  /** 带维度的构成明细。 */
  private ProjectionDrilldown drilldown(
      String scopeType,
      UUID legalEntityId,
      UUID shopId,
      String metricCode,
      LocalDate bucketDate,
      String currency,
      String sourceKind,
      String referenceId,
      String referenceLabel,
      BigDecimal quantity,
      BigDecimal value,
      String sourceReference) {
    return new ProjectionDrilldown(
        scopeType,
        legalEntityId,
        shopId,
        metricCode,
        bucketDate,
        currency,
        sourceKind,
        referenceId,
        referenceLabel,
        quantity,
        value,
        sourceReference);
  }

  /** 事件 id = 服务:流:上游行标识:内容摘要（**内容寻址**：内容不变 ⇒ id 不变 ⇒ 幂等跳过）。 */
  private ProjectionEvent event(
      String sourceService,
      String key,
      String eventType,
      UUID aggregateId,
      OffsetDateTime occurredAt,
      ZoneId timezone,
      List<ProjectionFact> facts,
      List<ProjectionDrilldown> drilldowns) {
    var signature = new StringBuilder();
    for (var fact : facts) {
      signature
          .append(fact.metricCode())
          .append('|')
          .append(fact.bucketDate())
          .append('|')
          .append(fact.currency())
          .append('|')
          .append(fact.value())
          .append(';');
    }
    return new ProjectionEvent(
        sourceService,
        sourceService + ":" + key + ":" + digest(signature.toString()),
        eventType,
        aggregateId,
        0,
        occurredAt,
        timezone.getId(),
        facts,
        drilldowns);
  }

  /** 内容摘要（SHA-256 前 16 字节，十六进制）。 */
  private static String digest(String content) {
    try {
      var md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(md.digest(content.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static BigDecimal nvl(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private ReportingProjectionModels.RefreshResult result(
      String sourceService, String stream, int eventCount, ReportingProjectionModels.ProjectionBatchResult applied, String note) {
    return new ReportingProjectionModels.RefreshResult(
        sourceService,
        stream,
        eventCount,
        applied.appliedCount(),
        applied.skippedCount(),
        "OK",
        note);
  }
}
