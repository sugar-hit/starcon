package com.starcon.reporting;

import com.starcon.tenancy.Ids;
import com.starcon.tenancy.TenantTransactions;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

/**
 * 13.5.3.1 物流分析的**投影摄取**（蓝图「输入」句：物流数据（10）；成本数据）。
 *
 * <p>本服务把**上游算好的**事实拉成本服务的投影表：
 *
 * <table>
 *   <caption>三路上游</caption>
 *   <tr><th>上游</th><th>端点</th><th>落到哪张表</th></tr>
 *   <tr><td>10.6.1.1 成本分析（channel）</td><td>{@code /channel/logistics-cost-analysis/overview}</td>
 *       <td>{@code reporting_logistics_analysis_cost_fact}</td></tr>
 *   <tr><td>10.6.1.1 成本分析（channel）</td><td>{@code /channel/logistics-cost-analysis/by-channel}</td>
 *       <td>{@code reporting_logistics_analysis_channel_fact}</td></tr>
 *   <tr><td>10.6.1.1 成本分析（channel）</td><td>{@code /channel/logistics-cost-analysis/allocations[/{id}]}</td>
 *       <td>{@code reporting_logistics_analysis_allocation_fact} + {@code _allocation_line_fact}</td></tr>
 *   <tr><td>10.6.2.1 时效分析（channel）</td><td>{@code /channel/logistics-timeliness/reports/matrix}</td>
 *       <td>{@code reporting_logistics_analysis_timeliness_fact}</td></tr>
 *   <tr><td>10.3.1.2 异常件（channel）</td><td>{@code /channel/logistics-tracking/exceptions}</td>
 *       <td>{@code reporting_logistics_analysis_exception_fact}</td></tr>
 *   <tr><td>10.2.1.1 运单清单（trade）</td><td>{@code /trade/logistics/waybills/list}</td>
 *       <td>{@code reporting_logistics_analysis_waybill_fact}</td></tr>
 *   <tr><td>6.1.1.1 SKU 档案（catalog）</td><td>{@code /catalog/skus}</td>
 *       <td>{@code reporting_logistics_analysis_sku_category_fact}</td></tr>
 * </table>
 *
 * <p>⚠️ **本服务不重算任何上游口径**：全口径成本与渠道成本在 10.6.1.1、
 * 达成率在 10.6.2.1、异常件判定在 10.3.1.2、运费登记在 trade 10.2.1.1 ——
 * 本服务只做**搬运与聚合**。重算就会出现第二个真相。
 *
 * <p>⚠️ **不重算头程分摊**：分摊规则、基准与结账时点（cost_as_of）全归 channel
 * 10.6.1.1，本服务只搬它算好的分摊单与分摊明细行。
 *
 * <p>⚠️ **跨服务调用一律走只读端口并转发调用方自己的 bearer**：不传租户号，
 * 上游侧仍按已认证成员关系解析租户并强制 RLS。
 *
 * <p>⚠️ **幂等**：写入走 {@code on conflict ... do update}（幂等键见 V406 的唯一索引），
 * 同一批上游事实重放**不会翻倍**。
 *
 * <p>⚠️ **上游读不到就如实报 FAILED**：绝不返回空集合冒充「上游没有数据」——
 * 一个空集合在报表里长成「运费 0」，而真相是「这一格没算出来」。
 */
@Service
public class ReportingLogisticsAnalysisRefreshService {

  /** 金额刻度（对齐 {@code numeric(24,4)}）。 */
  static final int MONEY_SCALE = 4;

  /** 数量刻度（对齐 {@code numeric(19,6)}）。 */
  static final int QUANTITY_SCALE = 6;

  /** 比率刻度（对齐 {@code numeric(19,6)}）。 */
  static final int RATIO_SCALE = 6;

  private final DSLContext sql;
  private final TenantTransactions tenants;
  private final ReportingLogisticsAnalysisPort logisticsPort;

  public ReportingLogisticsAnalysisRefreshService(
      DSLContext sql, TenantTransactions tenants, ReportingLogisticsAnalysisPort logisticsPort) {
    this.sql = sql;
    this.tenants = tenants;
    this.logisticsPort = logisticsPort;
  }

  // ==========================================================================
  // 入口
  // ==========================================================================

  /**
   * 刷新物流分析投影。
   *
   * @param from 窗口起（含）
   * @param to 窗口止（含）
   * @param zone 运单 / 异常件的「业务日」换算时区（随行落库，报表可复盘）
   */
  public ReportingLogisticsAnalysisModels.LogisticsAnalysisRefreshResult refresh(
      UUID tenantId, String bearer, LocalDate from, LocalDate to, ZoneId zone) {
    var summaries = new ArrayList<ReportingLogisticsAnalysisModels.LogisticsAnalysisRefreshSummary>();
    var truncated = false;

    // ---- ⓿ SKU → 类目映射（**先拉**：分摊明细行要挂类目）----
    var skuCategories = new HashMap<String, String>();
    try {
      var skus = pullSkuCategories(bearer);
      skuCategories.putAll(skus.map());
      var written = writeSkuCategories(tenantId, skus.facts());
      summaries.add(
          summary(
              "CATALOG_SKU",
              "SKU → 类目映射（类目维度的物流成本占比要用）",
              skus.facts().size(),
              written,
              "OK",
              "catalog 6.1.1.1 的 SKU 档案 —— 本模块**只读**类目归属，不写商品主数据。"));
    } catch (RuntimeException e) {
      summaries.add(
          summary(
              "CATALOG_SKU",
              "SKU → 类目映射（类目维度的物流成本占比要用）",
              0,
              0,
              "FAILED",
              "上游读不到：" + e.getClass().getSimpleName() + ": " + e.getMessage()));
    }

    // ---- ① 全口径成本（channel 10.6.1.1）----
    try {
      var overview = logisticsPort.costOverview(from, to, bearer);
      var written = writeCostOverview(tenantId, overview, from, to);
      var note =
          "channel 10.6.1.1 的成本总览 —— **逐币种**，跨币种绝不相加；"
              + "上游没取到数的分量在 missingComponents 里逐条列明（**不是 0**）。";
      if (overview.truncated()) {
        truncated = true;
        note += " ⚠️ 上游撞到了扫描上限，统计**不是全量**。";
      }
      summaries.add(
          summary(
              "CHANNEL_COST_OVERVIEW",
              "全口径成本（尾程 / 头程分摊 / 关税 / 包材 / 合计，逐币种）",
              overview.currencyRows() == null ? 0 : overview.currencyRows().size(),
              written,
              "OK",
              note));
    } catch (RuntimeException e) {
      summaries.add(
          summary(
              "CHANNEL_COST_OVERVIEW",
              "全口径成本（尾程 / 头程分摊 / 关税 / 包材 / 合计，逐币种）",
              0,
              0,
              "FAILED",
              "上游读不到：" + e.getClass().getSimpleName() + ": " + e.getMessage()));
    }

    // ---- ② 渠道对比（channel 10.6.1.1）----
    try {
      var comparison = logisticsPort.costByChannel(from, to, bearer);
      var written = writeChannelComparison(tenantId, comparison, from, to);
      var note =
          "channel 10.6.1.1 的渠道成本对比（承运商 × 渠道 × 币种）—— "
              + "**逐币种**，跨币种的渠道合计不存在。";
      if (comparison.truncated()) {
        truncated = true;
        note += " ⚠️ 上游撞到了扫描上限，统计**不是全量**。";
      }
      summaries.add(
          summary(
              "CHANNEL_COST_BY_CHANNEL",
              "渠道对比（承运商 × 渠道 × 币种）",
              comparison.rows() == null ? 0 : comparison.rows().size(),
              written,
              "OK",
              note));
    } catch (RuntimeException e) {
      summaries.add(
          summary(
              "CHANNEL_COST_BY_CHANNEL",
              "渠道对比（承运商 × 渠道 × 币种）",
              0,
              0,
              "FAILED",
              "上游读不到：" + e.getClass().getSimpleName() + ": " + e.getMessage()));
    }

    // ---- ③ 头程分摊单与明细（channel 10.6.1.1）----
    try {
      var allocations = pullAllocations(bearer);
      var written = writeAllocations(tenantId, allocations.allocations());
      var linesWritten = writeAllocationLines(tenantId, allocations.lines(), skuCategories);
      var note =
          "channel 10.6.1.1 的头程分摊单与**分摊明细行** —— 本模块**不重算分摊**，"
              + "分摊规则版本与结账时点（cost_as_of）原样搬运。";
      if (allocations.truncated()) {
        truncated = true;
        note += " ⚠️ 分摊单取到上限为止，超出的未投影。";
      }
      if (allocations.detailFailed() > 0) {
        note += " ⚠️ " + allocations.detailFailed() + " 张分摊单的明细读不到（单头已投影，明细缺失）。";
      }
      summaries.add(
          summary(
              "CHANNEL_ALLOCATION",
              "头程分摊单 + 分摊明细行（**头程分摊后成本**）",
              allocations.allocations().size() + allocations.lines().size(),
              written + linesWritten,
              "OK",
              note));
    } catch (RuntimeException e) {
      summaries.add(
          summary(
              "CHANNEL_ALLOCATION",
              "头程分摊单 + 分摊明细行（**头程分摊后成本**）",
              0,
              0,
              "FAILED",
              "上游读不到：" + e.getClass().getSimpleName() + ": " + e.getMessage()));
    }

    // ---- ④ 时效达成率（channel 10.6.2.1）----
    try {
      var matrix = pullTimeliness(from, to, bearer);
      var written = writeTimeliness(tenantId, matrix, from, to);
      var note =
          "channel 10.6.2.1 的渠道 × 国家时效矩阵 —— **达成率原样搬运，不重算**；"
              + "分母是「可比数」（没有承诺送达日与未签收的票不进分母）。";
      if (matrix.truncated()) {
        truncated = true;
        note += " ⚠️ 矩阵取到上限为止，超出的未投影。";
      }
      summaries.add(
          summary(
              "CHANNEL_TIMELINESS",
              "时效达成率（渠道 × 国家）",
              matrix.cells().size(),
              written,
              "OK",
              note));
    } catch (RuntimeException e) {
      summaries.add(
          summary(
              "CHANNEL_TIMELINESS",
              "时效达成率（渠道 × 国家）",
              0,
              0,
              "FAILED",
              "上游读不到：" + e.getClass().getSimpleName() + ": " + e.getMessage()));
    }

    // ---- ⑤ 运单（trade 10.2.1.1）----
    try {
      var waybills = pullWaybills(from, to, bearer, zone);
      var written = writeWaybills(tenantId, waybills);
      var note =
          "trade 10.2.1.1 的运单清单 —— **各国成本对比**与**单均运费的订单分母**的唯一来源；"
              + "运费没登记的票**有票数、没有金额**，不进任何金额合计。";
      if (waybills.truncated()) {
        truncated = true;
        note += " ⚠️ 运单取到上限为止（" + ReportingLogisticsAnalysisPort.MAX_PAGES + " 页），超出的未投影。";
      }
      summaries.add(
          summary(
              "TRADE_WAYBILL",
              "运单清单（运费 + 目的国 + 销售订单号）",
              waybills.facts().size(),
              written,
              "OK",
              note));
    } catch (RuntimeException e) {
      summaries.add(
          summary(
              "TRADE_WAYBILL",
              "运单清单（运费 + 目的国 + 销售订单号）",
              0,
              0,
              "FAILED",
              "上游读不到：" + e.getClass().getSimpleName() + ": " + e.getMessage()));
    }

    // ---- ⑥ 异常件（channel 10.3.1.2）----
    try {
      var cases = pullExceptions(bearer, zone);
      var written = writeExceptions(tenantId, cases);
      var note =
          "channel 10.3.1.2 的异常件（**一行一个已判定的件**）—— "
              + "异常件占比的分子取**窗口内**的件，与分母（窗口内运单）**同范围**。";
      if (cases.truncated()) {
        truncated = true;
        note += " ⚠️ 异常件取到上限为止，超出的未投影。";
      }
      summaries.add(
          summary(
              "CHANNEL_EXCEPTION",
              "异常件（窗口内，异常件占比的分子）",
              cases.facts().size(),
              written,
              "OK",
              note));
    } catch (RuntimeException e) {
      summaries.add(
          summary(
              "CHANNEL_EXCEPTION",
              "异常件（窗口内，异常件占比的分子）",
              0,
              0,
              "FAILED",
              "上游读不到：" + e.getClass().getSimpleName() + ": " + e.getMessage()));
    }

    var totalWritten = summaries.stream().mapToInt(s -> s.written()).sum();
    var note =
        "物流分析是**投影**：与业务系统冲突时以业务系统为准"
            + "（渠道与时效归 channel 10.6.1.1 / 10.6.2.1、异常件归 channel 10.3.1.2、"
            + "运单与运费登记归 trade 10.2.1.1、商品档案归 catalog 6.1.1.1）。";
    if (truncated) {
      note += " ⚠️ 本次刷新**触到了取数上限**，超出的部分未投影。";
    }
    return new ReportingLogisticsAnalysisModels.LogisticsAnalysisRefreshResult(
        summaries, totalWritten, truncated, note);
  }

  private static ReportingLogisticsAnalysisModels.LogisticsAnalysisRefreshSummary summary(
      String source, String label, int factCount, int written, String status, String note) {
    return new ReportingLogisticsAnalysisModels.LogisticsAnalysisRefreshSummary(
        source, label, factCount, written, status, note);
  }

  // ==========================================================================
  // ⓿ SKU → 类目
  // ==========================================================================

  /** 一条 SKU → 类目的映射。 */
  private record SkuCategoryFact(
      UUID skuId, String skuCode, String skuName, String categoryCode) {}

  /** SKU 映射的拉取结果。 */
  private record SkuCategories(List<SkuCategoryFact> facts, Map<String, String> map) {}

  private SkuCategories pullSkuCategories(String bearer) {
    var facts = new ArrayList<SkuCategoryFact>();
    var map = new HashMap<String, String>();
    for (int page = 0; page < ReportingLogisticsAnalysisPort.MAX_PAGES; page++) {
      var paged = logisticsPort.skus(page, bearer);
      if (paged == null || paged.items() == null || paged.items().isEmpty()) break;
      for (var row : paged.items()) {
        if (row == null || row.id() == null) continue;
        var category = text(row.category());
        facts.add(
            new SkuCategoryFact(row.id(), text(row.code()), text(row.name()), category));
        map.put(text(row.code()), category);
      }
      if (paged.items().size() < ReportingLogisticsAnalysisPort.PAGE_SIZE) break;
    }
    return new SkuCategories(facts, map);
  }

  private int writeSkuCategories(UUID tenantId, List<SkuCategoryFact> facts) {
    if (facts.isEmpty()) return 0;
    return tenants.run(
        tenantId,
        () -> {
          var written = 0;
          for (var fact : facts) {
            sql.execute(
                """
                insert into reporting_logistics_analysis_sku_category_fact
                  (id,tenant_id,sku_id,sku_code,sku_name,category_code,
                   source_service,source_reference,updated_at)
                values (?,?,?,?,?,?,
                         'catalog',?,now())
                on conflict (tenant_id,sku_id)
                do update set sku_code = excluded.sku_code,
                              sku_name = excluded.sku_name,
                              category_code = excluded.category_code,
                              source_reference = excluded.source_reference,
                              updated_at = now()
                """,
                Ids.next(),
                tenantId,
                fact.skuId(),
                fact.skuCode(),
                fact.skuName(),
                fact.categoryCode(),
                "catalog:sku:" + fact.skuId());
            written++;
          }
          return written;
        });
  }

  // ==========================================================================
  // ① 全口径成本
  // ==========================================================================

  private int writeCostOverview(
      UUID tenantId,
      ReportingLogisticsAnalysisPort.ChannelCostOverview overview,
      LocalDate from,
      LocalDate to) {
    if (overview == null || overview.currencyRows() == null || overview.currencyRows().isEmpty()) {
      return 0;
    }
    var units = new HashMap<String, ReportingLogisticsAnalysisPort.ChannelUnitMetric>();
    if (overview.unitMetrics() != null) {
      for (var metric : overview.unitMetrics()) {
        if (metric == null || metric.currency() == null) continue;
        units.put(metric.currency(), metric);
      }
    }
    var asOf = to;
    var rows = new ArrayList<Object[]>();
    for (var row : overview.currencyRows()) {
      if (row == null || row.currency() == null || row.currency().isBlank()) continue;
      var unit = units.get(row.currency());
      rows.add(
          new Object[] {
            row.currency(),
            row.lastMileFreight(),
            row.firstMileAllocation(),
            row.dutyAndTax(),
            row.packaging(),
            row.subtotal(),
            unit == null ? null : unit.registeredWaybills(),
            unit == null ? null : unit.notRegisteredWaybills(),
            unit == null ? null : unit.totalWeightKg(),
            unit == null ? null : unit.perOrderFreight(),
            unit == null ? null : unit.perKgFreight(),
            join(row.includedComponents()),
            join(row.missingComponents()),
            overview.scannedWaybills(),
            overview.truncated(),
            "channel:cost-overview:" + asOf + ":" + row.currency()
          });
    }
    if (rows.isEmpty()) return 0;
    return tenants.run(
        tenantId,
        () -> {
          var written = 0;
          for (var r : rows) {
            sql.execute(
                """
                insert into reporting_logistics_analysis_cost_fact
                  (id,tenant_id,as_of,window_from,window_to,currency,
                   last_mile_freight,first_mile_allocation,duty_and_tax,packaging,subtotal,
                   registered_waybills,not_registered_waybills,total_weight_kg,
                   upstream_per_waybill_freight,upstream_per_kg_freight,
                   included_components,missing_components,
                   scanned_waybills,truncated,
                   source_service,source_reference,updated_at)
                values (?,?,?,?,?,?,
                         ?,?,?,?,?,
                         ?,?,?,
                         ?,?,
                         ?,?,
                         ?,?,
                         'channel',?,now())
                on conflict (tenant_id,as_of,currency)
                do update set window_from = excluded.window_from,
                              window_to = excluded.window_to,
                              last_mile_freight = excluded.last_mile_freight,
                              first_mile_allocation = excluded.first_mile_allocation,
                              duty_and_tax = excluded.duty_and_tax,
                              packaging = excluded.packaging,
                              subtotal = excluded.subtotal,
                              registered_waybills = excluded.registered_waybills,
                              not_registered_waybills = excluded.not_registered_waybills,
                              total_weight_kg = excluded.total_weight_kg,
                              upstream_per_waybill_freight = excluded.upstream_per_waybill_freight,
                              upstream_per_kg_freight = excluded.upstream_per_kg_freight,
                              included_components = excluded.included_components,
                              missing_components = excluded.missing_components,
                              scanned_waybills = excluded.scanned_waybills,
                              truncated = excluded.truncated,
                              source_reference = excluded.source_reference,
                              updated_at = now()
                """,
                Ids.next(),
                tenantId,
                asOf,
                from,
                to,
                r[0],
                r[1],
                r[2],
                r[3],
                r[4],
                r[5],
                r[6],
                r[7],
                r[8],
                r[9],
                r[10],
                r[11],
                r[12],
                r[13],
                r[14],
                r[15]);
            written++;
          }
          return written;
        });
  }

  // ==========================================================================
  // ② 渠道对比
  // ==========================================================================

  private int writeChannelComparison(
      UUID tenantId,
      ReportingLogisticsAnalysisPort.ChannelCostChannelComparison comparison,
      LocalDate from,
      LocalDate to) {
    if (comparison == null || comparison.rows() == null) return 0;
    var asOf = to;
    var rows = new ArrayList<Object[]>();
    for (var row : comparison.rows()) {
      if (row == null) continue;
      if (row.byCurrency() == null) continue;
      for (var metric : row.byCurrency()) {
        if (metric == null || metric.currency() == null || metric.currency().isBlank()) continue;
        rows.add(
            new Object[] {
              text(row.carrierCode()),
              text(row.carrierChannel()),
              text(row.carrierChannelLabel()),
              metric.currency(),
              metric.amount() == null ? BigDecimal.ZERO : metric.amount(),
              metric.waybills(),
              metric.totalWeightKg(),
              metric.perOrderFreight(),
              "channel:cost-by-channel:" + asOf + ":" + text(row.carrierCode()) + ":"
                  + text(row.carrierChannel()) + ":" + metric.currency()
            });
      }
    }
    if (rows.isEmpty()) return 0;
    return tenants.run(
        tenantId,
        () -> {
          var written = 0;
          for (var r : rows) {
            sql.execute(
                """
                insert into reporting_logistics_analysis_channel_fact
                  (id,tenant_id,as_of,window_from,window_to,
                   carrier_code,carrier_channel,carrier_channel_label,currency,
                   amount,waybills,total_weight_kg,upstream_per_waybill_freight,
                   source_service,source_reference,updated_at)
                values (?,?,?,?,?,
                         ?,?,?,?,
                         ?,?,?,?,
                         'channel',?,now())
                on conflict (tenant_id,as_of,carrier_code,carrier_channel,currency)
                do update set window_from = excluded.window_from,
                              window_to = excluded.window_to,
                              carrier_channel_label = excluded.carrier_channel_label,
                              amount = excluded.amount,
                              waybills = excluded.waybills,
                              total_weight_kg = excluded.total_weight_kg,
                              upstream_per_waybill_freight = excluded.upstream_per_waybill_freight,
                              source_reference = excluded.source_reference,
                              updated_at = now()
                """,
                Ids.next(),
                tenantId,
                asOf,
                from,
                to,
                r[0],
                r[1],
                r[2],
                r[3],
                r[4],
                r[5],
                r[6],
                r[7],
                r[8]);
            written++;
          }
          return written;
        });
  }

  // ==========================================================================
  // ③ 头程分摊
  // ==========================================================================

  /** 一条待落库的分摊明细行。 */
  private record AllocationLineFact(
      UUID allocationId,
      int lineNo,
      UUID skuId,
      String skuCode,
      String skuName,
      String categoryCode,
      BigDecimal basisValue,
      String basisUnit,
      BigDecimal ratio,
      BigDecimal allocatedAmount) {}

  /** 分摊的拉取结果。 */
  private record Allocations(
      List<ReportingLogisticsAnalysisPort.ChannelAllocationRow> allocations,
      List<AllocationLineFact> lines,
      boolean truncated,
      int detailFailed) {}

  private Allocations pullAllocations(String bearer) {
    var allocations = new ArrayList<ReportingLogisticsAnalysisPort.ChannelAllocationRow>();
    var lines = new ArrayList<AllocationLineFact>();
    var truncated = false;
    var detailFailed = 0;
    for (int page = 0; page < ReportingLogisticsAnalysisPort.MAX_PAGES; page++) {
      var paged = logisticsPort.allocations(page, bearer);
      if (paged == null || paged.items() == null || paged.items().isEmpty()) break;
      for (var row : paged.items()) {
        if (row == null || row.id() == null) continue;
        allocations.add(row);
        // 明细行要**逐张**拉：分摊单的明细端点是按 id 的。
        // ⚠️ 一张单读不到明细时**跳过这一张**，不返回空明细冒充「这张单没有明细」——
        //   那会让类目维度的合计少算这一张而报表不报错。
        try {
          var detail = logisticsPort.allocation(row.id(), bearer);
          if (detail == null || detail.lines() == null) {
            detailFailed++;
            continue;
          }
          for (var line : detail.lines()) {
            if (line == null) continue;
            lines.add(
                new AllocationLineFact(
                    row.id(),
                    line.lineNo(),
                    line.skuId(),
                    text(line.skuCode()),
                    text(line.skuName()),
                    "",
                    line.basisValue() == null ? BigDecimal.ZERO : line.basisValue(),
                    text(line.basisUnit()),
                    line.ratio(),
                    line.allocatedAmount() == null ? BigDecimal.ZERO : line.allocatedAmount()));
          }
        } catch (RuntimeException e) {
          detailFailed++;
        }
      }
      if (paged.items().size() < ReportingLogisticsAnalysisPort.PAGE_SIZE) break;
      truncated = true;
    }
    return new Allocations(allocations, lines, truncated, detailFailed);
  }

  private int writeAllocations(
      UUID tenantId, List<ReportingLogisticsAnalysisPort.ChannelAllocationRow> allocations) {
    if (allocations.isEmpty()) return 0;
    return tenants.run(
        tenantId,
        () -> {
          var written = 0;
          for (var row : allocations) {
            if (row.currency() == null || row.currency().isBlank()) continue;
            sql.execute(
                """
                insert into reporting_logistics_analysis_allocation_fact
                  (id,tenant_id,allocation_id,allocation_code,
                   rule_id,rule_code,rule_version,allocation_basis,allocation_basis_label,
                   currency,source_total_amount,allocated_total_amount,line_count,
                   source_shipment_code,source_carrier_name,destination_country,
                   status,status_label,computed_at,cost_as_of,locked_at,superseded_at,
                   source_service,source_reference,updated_at)
                values (?,?,?,?,
                         ?,?,?,?,?,
                         ?,?,?,?,
                         ?,?,?,
                         ?,?,?::timestamptz,?::timestamptz,?::timestamptz,?::timestamptz,
                         'channel',?,now())
                on conflict (tenant_id,allocation_id)
                do update set allocation_code = excluded.allocation_code,
                              rule_id = excluded.rule_id,
                              rule_code = excluded.rule_code,
                              rule_version = excluded.rule_version,
                              allocation_basis = excluded.allocation_basis,
                              allocation_basis_label = excluded.allocation_basis_label,
                              currency = excluded.currency,
                              source_total_amount = excluded.source_total_amount,
                              allocated_total_amount = excluded.allocated_total_amount,
                              line_count = excluded.line_count,
                              source_shipment_code = excluded.source_shipment_code,
                              source_carrier_name = excluded.source_carrier_name,
                              destination_country = excluded.destination_country,
                              status = excluded.status,
                              status_label = excluded.status_label,
                              computed_at = excluded.computed_at,
                              cost_as_of = excluded.cost_as_of,
                              locked_at = excluded.locked_at,
                              superseded_at = excluded.superseded_at,
                              source_reference = excluded.source_reference,
                              updated_at = now()
                """,
                Ids.next(),
                tenantId,
                row.id(),
                text(row.allocationCode()),
                row.ruleId(),
                text(row.ruleCode()),
                row.ruleVersion(),
                text(row.allocationBasis()),
                text(row.allocationBasisLabel()),
                row.currency(),
                row.sourceTotalAmount() == null ? BigDecimal.ZERO : row.sourceTotalAmount(),
                row.allocatedTotalAmount() == null ? BigDecimal.ZERO : row.allocatedTotalAmount(),
                row.lineCount(),
                text(row.sourceShipmentCode()),
                text(row.sourceCarrierName()),
                text(row.destinationCountryCode()),
                text(row.status()),
                text(row.statusLabel()),
                row.computedAt(),
                row.costAsOf(),
                row.lockedAt(),
                row.supersededAt(),
                "channel:allocation:" + row.id());
            written++;
          }
          return written;
        });
  }

  private int writeAllocationLines(
      UUID tenantId, List<AllocationLineFact> lines, Map<String, String> skuCategories) {
    if (lines.isEmpty()) return 0;
    return tenants.run(
        tenantId,
        () -> {
          var written = 0;
          for (var line : lines) {
            var category = skuCategories.getOrDefault(line.skuCode(), "");
            sql.execute(
                """
                insert into reporting_logistics_analysis_allocation_line_fact
                  (id,tenant_id,allocation_id,line_no,
                   sku_id,sku_code,sku_name,category_code,
                   basis_value,basis_unit,ratio,allocated_amount,
                   source_service,source_reference,updated_at)
                values (?,?,?,?,
                         ?,?,?,?,
                         ?,?,?,?,
                         'channel',?,now())
                on conflict (tenant_id,allocation_id,line_no)
                do update set sku_id = excluded.sku_id,
                              sku_code = excluded.sku_code,
                              sku_name = excluded.sku_name,
                              category_code = excluded.category_code,
                              basis_value = excluded.basis_value,
                              basis_unit = excluded.basis_unit,
                              ratio = excluded.ratio,
                              allocated_amount = excluded.allocated_amount,
                              source_reference = excluded.source_reference,
                              updated_at = now()
                """,
                Ids.next(),
                tenantId,
                line.allocationId(),
                line.lineNo(),
                line.skuId(),
                line.skuCode(),
                line.skuName(),
                category,
                line.basisValue(),
                line.basisUnit(),
                line.ratio(),
                line.allocatedAmount(),
                "channel:allocation-line:" + line.allocationId() + ":" + line.lineNo());
            written++;
          }
          return written;
        });
  }

  // ==========================================================================
  // ④ 时效达成率
  // ==========================================================================

  private record TimelinessCells(
      List<ReportingLogisticsAnalysisPort.ChannelTimelinessMatrixRow> cells, boolean truncated) {}

  private TimelinessCells pullTimeliness(LocalDate from, LocalDate to, String bearer) {
    var cells = new ArrayList<ReportingLogisticsAnalysisPort.ChannelTimelinessMatrixRow>();
    var truncated = false;
    for (int page = 0; page < ReportingLogisticsAnalysisPort.MAX_PAGES; page++) {
      var paged = logisticsPort.timelinessMatrix(from, to, page, bearer);
      if (paged == null || paged.rows() == null || paged.rows().isEmpty()) break;
      cells.addAll(paged.rows());
      if (paged.rows().size() < ReportingLogisticsAnalysisPort.PAGE_SIZE) break;
      truncated = true;
    }
    return new TimelinessCells(cells, truncated);
  }

  private int writeTimeliness(
      UUID tenantId, TimelinessCells matrix, LocalDate from, LocalDate to) {
    if (matrix.cells().isEmpty()) return 0;
    var asOf = to;
    return tenants.run(
        tenantId,
        () -> {
          var written = 0;
          for (var cell : matrix.cells()) {
            if (cell == null) continue;
            sql.execute(
                """
                insert into reporting_logistics_analysis_timeliness_fact
                  (id,tenant_id,as_of,window_from,window_to,
                   channel_id,channel_code,channel_name,country_code,
                   shipped_count,delivered_count,comparable_count,on_time_count,overdue_count,
                   no_promise_count,pending_count,returned_count,
                   on_time_rate,delivery_rate,overdue_rate,
                   avg_transit_hours,avg_deviation_hours,score,score_grade,
                   source_service,source_reference,updated_at)
                values (?,?,?,?,?,
                         ?,?,?,?,
                         ?,?,?,?,?,
                         ?,?,?,
                         ?,?,?,
                         ?,?,?,?,
                         'channel',?,now())
                on conflict (tenant_id,as_of,channel_id,country_code)
                do update set window_from = excluded.window_from,
                              window_to = excluded.window_to,
                              channel_code = excluded.channel_code,
                              channel_name = excluded.channel_name,
                              shipped_count = excluded.shipped_count,
                              delivered_count = excluded.delivered_count,
                              comparable_count = excluded.comparable_count,
                              on_time_count = excluded.on_time_count,
                              overdue_count = excluded.overdue_count,
                              no_promise_count = excluded.no_promise_count,
                              pending_count = excluded.pending_count,
                              returned_count = excluded.returned_count,
                              on_time_rate = excluded.on_time_rate,
                              delivery_rate = excluded.delivery_rate,
                              overdue_rate = excluded.overdue_rate,
                              avg_transit_hours = excluded.avg_transit_hours,
                              avg_deviation_hours = excluded.avg_deviation_hours,
                              score = excluded.score,
                              score_grade = excluded.score_grade,
                              source_reference = excluded.source_reference,
                              updated_at = now()
                """,
                Ids.next(),
                tenantId,
                asOf,
                from,
                to,
                cell.channelId(),
                text(cell.channelCode()),
                text(cell.channelName()),
                text(cell.countryCode()),
                cell.shippedCount(),
                cell.deliveredCount(),
                cell.comparableCount(),
                cell.onTimeCount(),
                cell.overdueCount(),
                cell.noPromiseCount(),
                cell.pendingCount(),
                cell.returnedCount(),
                cell.onTimeRate(),
                cell.deliveryRate(),
                cell.overdueRate(),
                cell.avgTransitHours(),
                cell.avgDeviationHours(),
                cell.score(),
                text(cell.scoreGrade()),
                "channel:timeliness:" + asOf + ":" + text(cell.channelCode()) + ":"
                    + text(cell.countryCode()));
            written++;
          }
          return written;
        });
  }

  // ==========================================================================
  // ⑤ 运单
  // ==========================================================================

  /** 一条待落库的运单。 */
  private record WaybillFact(
      UUID waybillId,
      LocalDate occurredOn,
      OffsetDateTime occurredAt,
      String waybillNo,
      String salesOrderNo,
      String carrierCode,
      String carrierChannel,
      String destinationCountry,
      BigDecimal grossWeightKg,
      BigDecimal freightAmount,
      String freightCurrency,
      String freightGapCode) {}

  private record Waybills(List<WaybillFact> facts, boolean truncated) {}

  private Waybills pullWaybills(LocalDate from, LocalDate to, String bearer, ZoneId zone) {
    var facts = new ArrayList<WaybillFact>();
    var truncated = false;
    for (int page = 0; page < ReportingLogisticsAnalysisPort.MAX_PAGES; page++) {
      var paged = logisticsPort.waybills(from, to, page, bearer);
      if (paged == null || paged.items() == null || paged.items().isEmpty()) break;
      for (var row : paged.items()) {
        if (row == null || row.id() == null) continue;
        if (row.createdAt() == null) continue;
        var gap = row.freightGapCode();
        if (gap == null || gap.isBlank()) {
          gap = row.freightAmount() == null ? "NOT_REGISTERED" : "REGISTERED";
        }
        facts.add(
            new WaybillFact(
                row.id(),
                row.createdAt().atZoneSameInstant(zone).toLocalDate(),
                row.createdAt(),
                text(row.waybillNo()),
                text(row.salesOrderNo()),
                text(row.carrierCode()),
                text(row.carrierChannel()),
                text(row.destinationCountry()),
                row.grossWeightKg(),
                row.freightAmount(),
                row.freightCurrency() == null || row.freightCurrency().isBlank()
                    ? null
                    : row.freightCurrency().trim().toUpperCase(),
                gap));
      }
      if (paged.items().size() < ReportingLogisticsAnalysisPort.PAGE_SIZE) break;
      truncated = true;
    }
    return new Waybills(facts, truncated);
  }

  private int writeWaybills(UUID tenantId, Waybills waybills) {
    if (waybills.facts().isEmpty()) return 0;
    return tenants.run(
        tenantId,
        () -> {
          var written = 0;
          for (var fact : waybills.facts()) {
            sql.execute(
                """
                insert into reporting_logistics_analysis_waybill_fact
                  (id,tenant_id,occurred_on,occurred_at,
                   waybill_id,waybill_no,sales_order_no,
                   carrier_code,carrier_channel,destination_country,
                   gross_weight_kg,freight_amount,freight_currency,freight_gap_code,
                   source_service,source_reference,updated_at)
                values (?,?,?,?::timestamptz,
                        ?,?,?,
                        ?,?,?,
                        ?,?,?,?,
                        'trade',?,now())
                on conflict (tenant_id,waybill_id)
                do update set occurred_on = excluded.occurred_on,
                              occurred_at = excluded.occurred_at,
                              waybill_no = excluded.waybill_no,
                              sales_order_no = excluded.sales_order_no,
                              carrier_code = excluded.carrier_code,
                              carrier_channel = excluded.carrier_channel,
                              destination_country = excluded.destination_country,
                              gross_weight_kg = excluded.gross_weight_kg,
                              freight_amount = excluded.freight_amount,
                              freight_currency = excluded.freight_currency,
                              freight_gap_code = excluded.freight_gap_code,
                              source_reference = excluded.source_reference,
                              updated_at = now()
                """,
                Ids.next(),
                tenantId,
                fact.occurredOn(),
                fact.occurredAt(),
                fact.waybillId(),
                fact.waybillNo(),
                fact.salesOrderNo(),
                fact.carrierCode(),
                fact.carrierChannel(),
                fact.destinationCountry(),
                fact.grossWeightKg(),
                fact.freightAmount(),
                fact.freightCurrency(),
                fact.freightGapCode(),
                "trade:waybill:" + fact.waybillId());
            written++;
          }
          return written;
        });
  }

  // ==========================================================================
  // ⑥ 异常件
  // ==========================================================================

  /** 一条待落库的异常件。 */
  private record ExceptionFact(
      UUID caseId,
      String caseNo,
      LocalDate detectedOn,
      OffsetDateTime detectedAt,
      String exceptionType,
      String exceptionTypeLabel,
      String severity,
      String severityLabel,
      String status,
      String statusLabel,
      String source,
      String waybillNo,
      String carrierCode,
      String salesOrderNo,
      boolean overdue) {}

  private record Exceptions(List<ExceptionFact> facts, boolean truncated) {}

  private Exceptions pullExceptions(String bearer, ZoneId zone) {
    var facts = new ArrayList<ExceptionFact>();
    var truncated = false;
    for (int page = 0; page < ReportingLogisticsAnalysisPort.MAX_PAGES; page++) {
      var paged = logisticsPort.exceptions(page, bearer);
      if (paged == null || paged.rows() == null || paged.rows().isEmpty()) break;
      for (var row : paged.rows()) {
        if (row == null || row.id() == null) continue;
        if (row.detectedAt() == null) continue;
        facts.add(
            new ExceptionFact(
                row.id(),
                text(row.caseNo()),
                row.detectedAt().atZoneSameInstant(zone).toLocalDate(),
                row.detectedAt(),
                text(row.exceptionType()),
                text(row.exceptionTypeLabel()),
                text(row.severity()),
                text(row.severityLabel()),
                text(row.status()),
                text(row.statusLabel()),
                text(row.source()),
                text(row.waybillNo()),
                text(row.carrierCode()),
                text(row.salesOrderNo()),
                row.overdue()));
      }
      if (paged.rows().size() < ReportingLogisticsAnalysisPort.PAGE_SIZE) break;
      truncated = true;
    }
    return new Exceptions(facts, truncated);
  }

  private int writeExceptions(UUID tenantId, Exceptions exceptions) {
    if (exceptions.facts().isEmpty()) return 0;
    return tenants.run(
        tenantId,
        () -> {
          var written = 0;
          for (var fact : exceptions.facts()) {
            sql.execute(
                """
                insert into reporting_logistics_analysis_exception_fact
                  (id,tenant_id,case_id,case_no,
                   detected_on,detected_at,
                   exception_type,exception_type_label,severity,severity_label,status,status_label,source,
                   waybill_no,carrier_code,sales_order_no,overdue,
                   source_service,source_reference,updated_at)
                values (?,?,?,?,
                         ?,?::timestamptz,
                         ?,?,?,?,?,?,?,
                         ?,?,?,?,
                         'channel',?,now())
                on conflict (tenant_id,case_id)
                do update set case_no = excluded.case_no,
                              detected_on = excluded.detected_on,
                              detected_at = excluded.detected_at,
                              exception_type = excluded.exception_type,
                              exception_type_label = excluded.exception_type_label,
                              severity = excluded.severity,
                              severity_label = excluded.severity_label,
                              status = excluded.status,
                              status_label = excluded.status_label,
                              source = excluded.source,
                              waybill_no = excluded.waybill_no,
                              carrier_code = excluded.carrier_code,
                              sales_order_no = excluded.sales_order_no,
                              overdue = excluded.overdue,
                              source_reference = excluded.source_reference,
                              updated_at = now()
                """,
                Ids.next(),
                tenantId,
                fact.caseId(),
                fact.caseNo(),
                fact.detectedOn(),
                fact.detectedAt(),
                fact.exceptionType(),
                fact.exceptionTypeLabel(),
                fact.severity(),
                fact.severityLabel(),
                fact.status(),
                fact.statusLabel(),
                fact.source(),
                fact.waybillNo(),
                fact.carrierCode(),
                fact.salesOrderNo(),
                fact.overdue(),
                "channel:exception:" + fact.caseId());
            written++;
          }
          return written;
        });
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  /** null → 空串（不制造「null 字符串」）。 */
  private static String text(String raw) {
    return raw == null ? "" : raw.trim();
  }

  /** 列表 → 逗号分隔（空列表 → 空串）。 */
  private static String join(List<String> values) {
    if (values == null || values.isEmpty()) return "";
    return String.join(",", values);
  }

  /** 金额定标（保留 4 位小数）。 */
  static BigDecimal money(BigDecimal value) {
    return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  /** 数量定标（保留 6 位小数）。 */
  static BigDecimal quantity(BigDecimal value) {
    return value == null ? null : value.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
  }

  /** 比率定标（保留 6 位小数）。 */
  static BigDecimal ratio(BigDecimal value) {
    return value == null ? null : value.setScale(RATIO_SCALE, RoundingMode.HALF_UP);
  }
}
