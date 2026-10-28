package com.starcon.reporting;

import com.starcon.tenancy.Ids;
import com.starcon.tenancy.TenantTransactions;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

/**
 * 13.4.1.1 多模式利润报表的**拉取适配器**：把上游的授权只读端点读成事实，落到本模块自己的
 * 投影表（{@code reporting_profit_shop_profile} / {@code reporting_profit_fact}）。
 *
 * <p><b>四路上游</b>（每一路对应蓝图「输入」句的一项）：
 *
 * <ol>
 *   <li>{@code channel-shop-mode} —— 2.3.2.1 的**店铺经营模式**（channel {@code shop.operating_mode}）。
 *       模式标记**只**从这里来；本模块**不硬编码模式当真相**。
 *   <li>{@code trade-orders-revenue} —— 9 订单的**订单收入**（订单总额口径 + 订单行金额口径）。
 *   <li>{@code finance-settlement-fee} —— 12.3.1.2 结算明细里的**平台费 / 物流费 / 广告费**。
 *   <li>{@code finance-cost-allocation} —— 12.4 成本归集 + 12.4.2.2 **分摊结果**（头程费与其它成本）。
 * </ol>
 *
 * <p>⚠️ **不重算费率、不重算分摊**：费率在 catalog 的规则里、分摊在 finance 的 12.4.2.2 里，
 * 本类**只读**它们算好的结果 —— 重算就会出现第二个真相（规则一改，本模块的毛利就变成假的）。
 *
 * <p>⚠️ **每一笔钱只有一个写入所有者**（避免同一笔钱记两次）：
 * 平台费 / 物流费 / 广告费归 **12.3.1.2 结算明细**（它更细：带店铺与订单）；
 * 12.4.2.2 分摊结果里的**同名**费用池**不再计入**。
 */
@Service
public class ReportingProfitReportRefreshService {

  /** 本模块的四路上游（**空的 ⇒ 全部**）。 */
  static final List<String> SOURCES =
      List.of(
          "channel-shop-mode",
          "trade-orders-revenue",
          "finance-settlement-fee",
          "finance-cost-allocation");

  /**
   * 一次刷新里最多取多少张订单的**明细**（订单行金额只在明细里 ⇒ 必须逐单取）。
   *
   * <p>⚠️ 触顶时**如实报 truncated**：剩下的订单**没有行口径收入**（不是收入为 0），
   * 订单总额口径的收入仍然完整。
   */
  static final int MAX_DETAIL_FETCHES = 200;

  /** 一次刷新里最多翻多少页列表（店铺 / SKU / 平台商品 / 分摊批次 …）。 */
  static final int MAX_LIST_PAGES = 25;

  private final ReportingProfitReportPort profit;
  private final ReportingTradePort trade;
  private final ReportingSalesTrendPort salesTrend;
  private final ReportingSettlementPort settlement;
  private final ReportingProductSalesPort productSales;
  private final DSLContext sql;
  private final TenantTransactions tenants;

  public ReportingProfitReportRefreshService(
      ReportingProfitReportPort profit,
      ReportingTradePort trade,
      ReportingSalesTrendPort salesTrend,
      ReportingSettlementPort settlement,
      ReportingProductSalesPort productSales,
      DSLContext sql,
      TenantTransactions tenants) {
    this.profit = profit;
    this.trade = trade;
    this.salesTrend = salesTrend;
    this.settlement = settlement;
    this.productSales = productSales;
    this.sql = sql;
    this.tenants = tenants;
  }

  // ==========================================================================
  // 刷新
  // ==========================================================================

  /**
   * 刷新利润事实。
   *
   * @param sources 要刷新的来源（空 ⇒ 全部）
   * @param timezone 分桶所用时区（跨时区店铺汇总必须显示所用时区）
   */
  public List<ReportingProfitReportModels.ProfitRefreshResult> refresh(
      UUID tenantId, String bearer, LocalDate from, LocalDate to, List<String> sources, ZoneId timezone) {
    var wanted = sources == null || sources.isEmpty() ? SOURCES : sources;
    var results = new ArrayList<ReportingProfitReportModels.ProfitRefreshResult>();

    // 店铺档案与经营模式（其它几路都要用它算模式）。
    if (wanted.contains("channel-shop-mode")) {
      try {
        results.add(refreshShopProfiles(tenantId, bearer));
      } catch (org.springframework.web.server.ResponseStatusException e) {
        results.add(
            failed("channel-shop-mode", e.getReason() == null ? "上游读不到" : e.getReason()));
      }
    }
    var modes = shopModes(tenantId);

    if (wanted.contains("trade-orders-revenue")) {
      try {
        results.add(refreshRevenue(tenantId, bearer, from, to, modes));
      } catch (org.springframework.web.server.ResponseStatusException e) {
        results.add(
            failed("trade-orders-revenue", e.getReason() == null ? "上游读不到" : e.getReason()));
      }
    }
    if (wanted.contains("finance-settlement-fee")) {
      try {
        results.add(refreshSettlementFees(tenantId, bearer, from, to, modes));
      } catch (org.springframework.web.server.ResponseStatusException e) {
        results.add(
            failed("finance-settlement-fee", e.getReason() == null ? "上游读不到" : e.getReason()));
      }
    }
    if (wanted.contains("finance-cost-allocation")) {
      try {
        results.add(refreshCostAllocation(tenantId, bearer, from, to, modes));
      } catch (org.springframework.web.server.ResponseStatusException e) {
        results.add(
            failed("finance-cost-allocation", e.getReason() == null ? "上游读不到" : e.getReason()));
      }
    }
    return results;
  }

  // ==========================================================================
  // ① 店铺档案 + 经营模式（2.3.2.1）
  // ==========================================================================

  /** 店铺档案：一行 = 一个店铺 + 它当前的经营模式。 */
  private record ShopProfile(String shopCode, String shopName, String platformCode, String mode) {}

  private ReportingProfitReportModels.ProfitRefreshResult refreshShopProfiles(
      UUID tenantId, String bearer) {
    var rows = profit.shops(bearer);
    var profiles = new ArrayList<ShopProfile>();
    var skipped = 0;
    for (var shop : rows) {
      if (shop == null || shop.code() == null || shop.code().isBlank()) {
        skipped++;
        continue;
      }
      profiles.add(
          new ShopProfile(
              shop.code().trim(),
              shop.name() == null ? "" : shop.name(),
              shop.platformCode() == null ? "" : shop.platformCode(),
              normalizeMode(shop.operatingMode())));
    }
    var written =
        tenants.run(
            tenantId,
            () -> {
              int count = 0;
              for (var profile : profiles) {
                sql.execute(
                    """
                    insert into reporting_profit_shop_profile
                      (id,tenant_id,shop_code,shop_name,platform_code,operating_mode,
                       source_service,source_reference,updated_at)
                    values (?,?,?,?,?,?,'channel',?,now())
                    on conflict (tenant_id, shop_code) do update
                      set shop_name = excluded.shop_name,
                          platform_code = excluded.platform_code,
                          operating_mode = excluded.operating_mode,
                          source_reference = excluded.source_reference,
                          updated_at = now()
                    """,
                    Ids.next(),
                    tenantId,
                    profile.shopCode(),
                    profile.shopName(),
                    profile.platformCode(),
                    profile.mode(),
                    "channel:shop:" + profile.shopCode());
                count++;
              }
              return count;
            });
    var note =
        "经营模式取自 channel 2.3.2.1 的 `shop.operating_mode`（原样保留上游词表）；"
            + "挂不上店铺 / 上游没给模式的订单归入「未归属」，**不混进任何真实模式**。";
    if (skipped > 0) note += " ⚠️ 有 " + skipped + " 个店铺没有店铺编码，未投影。";
    return new ReportingProfitReportModels.ProfitRefreshResult(
        "channel-shop-mode", profiles.size(), written, "SUCCEEDED", note);
  }

  /** 该租户的店铺 → 经营模式映射（**读 DB**，不是上游 —— 上游那一份已投影成档案表）。 */
  private Map<String, String> shopModes(UUID tenantId) {
    var rows =
        tenants.run(
            tenantId,
            () ->
                sql.fetch(
                    "select shop_code, operating_mode from reporting_profit_shop_profile where tenant_id = ?",
                    tenantId));
    var out = new HashMap<String, String>();
    for (var row : rows) {
      out.put(row.get(0, String.class), row.get(1, String.class));
    }
    return out;
  }

  // ==========================================================================
  // ② 订单收入（9 订单）
  // ==========================================================================

  /**
   * 订单收入。
   *
   * <p>⚠️ **两种收入口径并存**（报表按维度分别取用，口径写在行备注里）：
   *
   * <ul>
   *   <li><b>订单总额口径</b>：trade 的 {@code grand_total_amount}（含订单级运费 / 税 / 折扣）。
   *       落在 {@code order_ref} 上、**不带 SKU 归属** ⇒ 经营模式 / 店铺 / 订单三个维度用它；
   *   <li><b>订单行金额口径</b>：trade 的 {@code line_amount} 之和（不含订单级运费 / 税 / 折扣）。
   *       落在 {@code sku_code} 上 ⇒ SKU / SPU / MSKU 三个维度用它。
   * </ul>
   *
   * <p>两个口径**不是同一笔钱**（差的是订单级尾差），所以**绝不相加**。
   */
  private ReportingProfitReportModels.ProfitRefreshResult refreshRevenue(
      UUID tenantId, String bearer, LocalDate from, LocalDate to, Map<String, String> modes) {
    var attribution = fetchStoreAttribution(bearer);
    var skuMeta = fetchSkuMeta(bearer);

    var facts = new ArrayList<Fact>();
    var missingCurrency = 0;
    var unattributed = 0;
    var truncated = false;
    var detailFetches = 0;
    var fetched = 0;

    for (int page = 0; page < ReportingTradePort.MAX_PAGES; page++) {
      var pageData = trade.salesOrders(from, to, page, bearer);
      if (pageData == null || pageData.rows() == null || pageData.rows().isEmpty()) break;
      for (var row : pageData.rows()) {
        fetched++;
        if (row.submittedAt() == null) continue;
        var currency = currency(row.currency());
        if (currency == null) {
          // 🔴 **币种缺失的订单不计入任何币种**：算进某个币种是编造。
          missingCurrency++;
          continue;
        }
        var day = row.submittedAt().atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
        var shopCode = attribution.salesOrderShop().get(row.id());
        if (shopCode == null || shopCode.isBlank()) {
          // ⚠️ **挂不上店铺的订单不丢**：归入「未归属」，不混进任何真实模式。
          shopCode = "";
          unattributed++;
        } else {
          shopCode = shopCode.trim();
        }
        var mode = modeOf(modes, shopCode);
        var orderNo = row.orderNo() == null ? "" : row.orderNo().trim();

        // ---- 订单总额口径 ----
        if (row.grandTotalAmount() != null && !orderNo.isBlank()) {
          facts.add(
              new Fact(
                  mode,
                  "REVENUE",
                  shopCode,
                  "",
                  "",
                  "",
                  orderNo,
                  day,
                  day,
                  currency,
                  row.grandTotalAmount(),
                  BigDecimal.ZERO,
                  "trade",
                  "sales-order:" + orderNo));
        }

        // ---- 订单行金额口径（逐单取明细，有上限）----
        if (detailFetches < MAX_DETAIL_FETCHES && row.id() != null && !orderNo.isBlank()) {
          detailFetches++;
          var detail = trade.salesOrderDetail(row.id(), bearer);
          if (detail != null && detail.lines() != null && !detail.lines().isEmpty()) {
            // ⚠️ 同一订单里**同一个 SKU 可能有多行** ⇒ 先按 SKU 聚合再落库，
            //   否则两行会撞同一个幂等键（后者覆盖前者 ⇒ 少算钱）。
            var bySku = new LinkedHashMap<String, BigDecimal>();
            var qtyBySku = new LinkedHashMap<String, BigDecimal>();
            var spuBySku = new HashMap<String, String>();
            var mskuBySku = new HashMap<String, String>();
            for (var line : detail.lines()) {
              var skuCode = line.skuCode() == null ? "" : line.skuCode().trim();
              if (skuCode.isBlank()) continue;
              var amount = line.lineAmount() == null ? BigDecimal.ZERO : line.lineAmount();
              bySku.merge(skuCode, amount, BigDecimal::add);
              qtyBySku.merge(
                  skuCode, line.quantity() == null ? BigDecimal.ZERO : line.quantity(), BigDecimal::add);
              var meta = skuMeta.get(skuCode);
              if (meta != null) {
                spuBySku.putIfAbsent(skuCode, meta.spuCode());
                mskuBySku.putIfAbsent(skuCode, meta.mskuCode());
              }
            }
            for (var entry : bySku.entrySet()) {
              var skuCode = entry.getKey();
              facts.add(
                  new Fact(
                      mode,
                      "REVENUE",
                      shopCode,
                      skuCode,
                      spuBySku.getOrDefault(skuCode, ""),
                      mskuBySku.getOrDefault(skuCode, ""),
                      orderNo,
                      day,
                      day,
                      currency,
                      entry.getValue(),
                      qtyBySku.getOrDefault(skuCode, BigDecimal.ZERO),
                      "trade",
                      "sales-order-line:" + orderNo + "#" + skuCode));
            }
          }
        } else {
          truncated = true;
        }
      }
      if (pageData.rows().size() < ReportingTradePort.PAGE_SIZE) break;
    }

    var written = writeFacts(tenantId, facts);
    var note = new StringBuilder();
    note.append("订单 ")
        .append(fetched)
        .append(" 张：订单总额口径（经营模式 / 店铺 / 订单维度用它）与订单行金额口径")
        .append("（SKU / SPU / MSKU 维度用它）**分别落库，不相加**。");
    if (unattributed > 0) {
      note.append(" 其中 ").append(unattributed).append(" 张挂不上店铺，归入「未归属」。");
    }
    if (missingCurrency > 0) {
      note.append(" ").append(missingCurrency).append(" 张币种缺失，不计入任何币种。");
    }
    if (truncated) {
      note.append(
          " ⚠️ 订单明细取到 ")
              .append(MAX_DETAIL_FETCHES)
              .append(" 张为止，超出的订单**没有行口径收入**（不是收入为 0）。");
    }
    return new ReportingProfitReportModels.ProfitRefreshResult(
        "trade-orders-revenue",
        fetched,
        written,
        truncated ? "TRUNCATED" : "SUCCEEDED",
        note.toString());
  }

  // ==========================================================================
  // ③ 结算明细费用（12.3.1.2）
  // ==========================================================================

  /** 结算类别 → 本模块的成本项（**每一类只有一个写入所有者**）。 */
  private static final List<SettlementFee> SETTLEMENT_FEES =
      List.of(
          new SettlementFee("COMMISSION", "PLATFORM_FEE"),
          new SettlementFee("FBA_FEE", "LOGISTICS_FEE"),
          new SettlementFee("ADVERTISING", "AD_FEE"));

  private record SettlementFee(String category, String component) {}

  private ReportingProfitReportModels.ProfitRefreshResult refreshSettlementFees(
      UUID tenantId, String bearer, LocalDate from, LocalDate to, Map<String, String> modes) {
    var facts = new ArrayList<Fact>();
    var missingCurrency = 0;
    var fetched = 0;
    for (var fee : SETTLEMENT_FEES) {
      for (int page = 0; page < ReportingSettlementPort.MAX_PAGES; page++) {
        var rows = settlement.transactions(fee.category(), from, to, page, bearer);
        if (rows == null || rows.items() == null || rows.items().isEmpty()) break;
        for (var row : rows.items()) {
          fetched++;
          if (row.amount() == null || row.occurredOn() == null) continue;
          var currency = currency(row.currency());
          if (currency == null) {
            missingCurrency++;
            continue;
          }
          var shopCode = row.shopCode() == null ? "" : row.shopCode().trim();
          var mode = modeOf(modes, shopCode);
          var orderRef = row.orderReference() == null ? "" : row.orderReference().trim();
          facts.add(
              new Fact(
                  mode,
                  fee.component(),
                  shopCode,
                  "",
                  "",
                  "",
                  orderRef,
                  row.occurredOn(),
                  row.occurredOn(),
                  currency,
                  // ⚠️ **符号翻正**：上游是「结算贡献」，费用是减项（与 13.4.2.2 同一约定）。
                  row.amount().negate(),
                  row.quantity() == null ? BigDecimal.ZERO : row.quantity(),
                  "finance",
                  "settlement-transaction:" + row.id()));
        }
        if (rows.items().size() < ReportingSettlementPort.PAGE_SIZE) break;
      }
    }
    var written = writeFacts(tenantId, facts);
    var note =
        "12.3.1.2 结算明细里的佣金 / FBA 费用 / 广告费作为**实际发生的费用**；符号已翻正（上游是结算贡献，费用是减项）。"
            + "这三个成本项的**唯一写入所有者是结算明细** —— 12.4 费用池里的同名费用不重复计入。";
    if (missingCurrency > 0) note += " ⚠️ 有 " + missingCurrency + " 条币种缺失，未计入任何币种。";
    return new ReportingProfitReportModels.ProfitRefreshResult(
        "finance-settlement-fee", fetched, written, "SUCCEEDED", note);
  }

  // ==========================================================================
  // ④ 12.4 成本归集 + 12.4.2.2 分摊结果
  // ==========================================================================

  /**
   * 12.4.2.2 的**分摊结果**（成本落到谁头上）。
   *
   * <p>⚠️ **只取已生效（ALLOCATED）的批次**：已撤销（REVERTED）的批次不再是有效分摊。
   *
   * <p>⚠️ **基数不可得的分摊行不算成本**：上游把它的分摊额写成 0（不是「这笔成本是 0」，
   * 是「按这个口径分不下去」）⇒ 本模块**跳过**它，并如实报数量。
   */
  private ReportingProfitReportModels.ProfitRefreshResult refreshCostAllocation(
      UUID tenantId, String bearer, LocalDate from, LocalDate to, Map<String, String> modes) {
    // 归集规则 ⇒ 费用池 ⇒ 成本项（上游固定词表）。
    var categoryByPoolKey = new HashMap<String, String>();
    for (var rule : profit.collectionRules(bearer)) {
      if (rule == null || rule.poolKey() == null || rule.poolKey().isBlank()) continue;
      if (rule.feeCategory() == null || rule.feeCategory().isBlank()) continue;
      categoryByPoolKey.putIfAbsent(rule.poolKey().trim(), rule.feeCategory().trim());
    }

    // 费用池 ⇒ 归属期间 + 币种。
    var pools = new HashMap<UUID, ReportingProfitReportPort.PoolRow>();
    for (int page = 0; page < MAX_LIST_PAGES; page++) {
      var poolPage = profit.pools(page, bearer);
      if (poolPage == null || poolPage.items() == null || poolPage.items().isEmpty()) break;
      for (var pool : poolPage.items()) {
        if (pool == null || pool.poolId() == null) continue;
        pools.put(pool.poolId(), pool);
      }
      if (poolPage.items().size() < ReportingProfitReportPort.PAGE_SIZE) break;
    }

    // 订单 → 店铺（ORDER 维度的分摊结果要靠它算经营模式）。
    var attribution = fetchStoreAttribution(bearer);

    var facts = new ArrayList<Fact>();
    var fetched = 0;
    var skippedReverted = 0;
    var skippedDoubleCount = 0;
    var skippedDimension = 0;
    var skippedBasis = 0;
    var skippedPool = 0;

    for (int page = 0; page < MAX_LIST_PAGES; page++) {
      var batchPage = profit.batches(page, bearer);
      if (batchPage == null || batchPage.items() == null || batchPage.items().isEmpty()) break;
      for (var batch : batchPage.items()) {
        if (batch == null || batch.id() == null) continue;
        if (!"ALLOCATED".equals(batch.status())) {
          skippedReverted++;
          continue;
        }
        var pool = pools.get(batch.poolId());
        if (pool == null || pool.periodStart() == null || pool.periodEnd() == null) {
          skippedPool++;
          continue;
        }
        // 池的归属期间与报表窗口有交集才要（**按期间起始日落桶**）。
        if (pool.periodStart().isAfter(to) || pool.periodEnd().isBefore(from)) continue;
        var currency = currency(pool.currency());
        if (currency == null) continue;
        var component = componentOf(categoryByPoolKey.get(pool.poolKey()));
        if (component == null) {
          // ⚠️ **同一笔钱不记两次**：平台费 / 物流费 / 广告费归 12.3.1.2 结算明细。
          skippedDoubleCount++;
          continue;
        }
        for (int resultPage = 0; resultPage < MAX_LIST_PAGES; resultPage++) {
          var results =
              profit.allocationResults(batch.id(), null, resultPage, bearer);
          if (results == null || results.items() == null || results.items().isEmpty()) break;
          for (var result : results.items()) {
            fetched++;
            if (!result.basisAvailable()) {
              // 基数不可得 ⇒ 上游把分摊额写成 0（**不是**「这笔成本是 0」）。
              skippedBasis++;
              continue;
            }
            var amount = result.allocatedAmount();
            if (amount == null) continue;
            var target = result.targetCode() == null ? "" : result.targetCode().trim();
            if (target.isBlank()) {
              skippedDimension++;
              continue;
            }
            var shopCode = "";
            var skuCode = "";
            var spuCode = "";
            var orderRef = "";
            switch (result.targetDimension()) {
              case "SHOP" -> shopCode = target;
              case "ORDER" -> {
                orderRef = target;
                var mapped = attribution.orderShop().get(target);
                shopCode = mapped == null ? "" : mapped;
              }
              case "SKU" -> skuCode = target;
              case "SPU" -> spuCode = target;
              default -> {
                skippedDimension++;
                continue;
              }
            }
            facts.add(
                new Fact(
                    modeOf(modes, shopCode),
                    component,
                    shopCode,
                    skuCode,
                    spuCode,
                    "",
                    orderRef,
                    pool.periodStart(),
                    pool.periodEnd(),
                    currency,
                    amount,
                    BigDecimal.ZERO,
                    "finance",
                    "cost-allocation:" + result.id()));
          }
          if (results.items().size() < ReportingProfitReportPort.PAGE_SIZE) break;
        }
      }
      if (batchPage.items().size() < ReportingProfitReportPort.PAGE_SIZE) break;
    }

    var written = writeFacts(tenantId, facts);
    var note = new StringBuilder();
    note.append("12.4 成本归集 + 12.4.2.2 分摊结果：只取**已生效**批次，按分摊维度落到店铺 / 订单 / SKU / SPU。");
    note.append(" 平台费 / 物流费 / 广告费**不在这里计入**（它们的唯一写入所有者是 12.3.1.2 结算明细）。");
    if (skippedReverted > 0) note.append(" ⚠️ 跳过 ").append(skippedReverted).append(" 个已撤销批次。");
    if (skippedDoubleCount > 0) {
      note.append(" 跳过 ").append(skippedDoubleCount).append(" 条与结算明细重复的费用池分摊。");
    }
    if (skippedBasis > 0) {
      note.append(" ⚠️ 跳过 ").append(skippedBasis).append(" 条基数不可得的分摊行（分摊额为 0 ≠ 这笔成本是 0）。");
    }
    if (skippedDimension > 0) {
      note.append(" 跳过 ").append(skippedDimension).append(" 条本模块不支持的分摊维度（类目）。");
    }
    if (skippedPool > 0) note.append(" 跳过 ").append(skippedPool).append(" 个找不到费用池的批次。");
    return new ReportingProfitReportModels.ProfitRefreshResult(
        "finance-cost-allocation", fetched, written, "SUCCEEDED", note.toString());
  }

  // ==========================================================================
  // 上游辅助
  // ==========================================================================

  /** 订单归属：销售订单 id → 店铺编码；订单号 → 店铺编码。 */
  private record Attribution(Map<UUID, String> salesOrderShop, Map<String, String> orderShop) {}

  /**
   * 销售订单 → 店铺的归属表（平台单回挂）。
   *
   * <p>⚠️ **销售订单本身没有店铺字段** —— 唯一来源是 trade 的平台单
   * （{@code platform_order.sales_order_id}）。挂不上的订单归入「未归属」，**不丢**。
   */
  private Attribution fetchStoreAttribution(String bearer) {
    var bySalesOrder = new HashMap<UUID, String>();
    var byOrderNo = new HashMap<String, String>();
    for (int page = 0; page < ReportingSalesTrendPort.MAX_PAGES; page++) {
      var pageData = salesTrend.platformOrders(page, bearer);
      if (pageData == null || pageData.rows() == null || pageData.rows().isEmpty()) break;
      for (var row : pageData.rows()) {
        if (row.shopCode() == null || row.shopCode().isBlank()) continue;
        var shop = row.shopCode().trim();
        if (row.salesOrderId() != null) bySalesOrder.putIfAbsent(row.salesOrderId(), shop);
        if (row.orderNo() != null && !row.orderNo().isBlank()) byOrderNo.putIfAbsent(row.orderNo().trim(), shop);
      }
      if (pageData.rows().size() < ReportingSalesTrendPort.PAGE_SIZE) break;
    }
    return new Attribution(bySalesOrder, byOrderNo);
  }

  /** SKU 归属：SKU 编码 → SPU / MSKU。 */
  private record SkuMeta(String spuCode, String mskuCode) {}

  /**
   * SKU 编码 → SPU / MSKU 的归属表。
   *
   * <p>⚠️ **一个 SKU 挂在多个平台商品上时无法唯一归属**（13.2.2.2 同一纪律）⇒ 不摊给任何一个，
   * MSKU 留空并由报表说明。
   *
   * <p>⚠️ **按 SKU 编码做键**（不是 SKU id）：trade 的订单行只给 {@code sku_code}，
   * 没有给 SKU id —— 用 id 做键会取不到任何归属。
   */
  private Map<String, SkuMeta> fetchSkuMeta(String bearer) {
    // SKU 编码 → SKU id（catalog 的 SKU 列表）。
    var skuIdByCode = new HashMap<String, UUID>();
    var spuByCode = new HashMap<String, String>();
    for (int page = 0; page < MAX_LIST_PAGES; page++) {
      var skuPage = productSales.skus(page, bearer);
      if (skuPage == null || skuPage.items() == null || skuPage.items().isEmpty()) break;
      for (var sku : skuPage.items()) {
        if (sku == null || sku.code() == null || sku.code().isBlank()) continue;
        var code = sku.code().trim();
        if (sku.id() != null) skuIdByCode.putIfAbsent(code, sku.id());
        if (sku.spuCode() != null && !sku.spuCode().isBlank()) {
          spuByCode.putIfAbsent(code, sku.spuCode().trim());
        }
      }
      if (skuPage.items().size() < ReportingProductSalesPort.PAGE_SIZE) break;
    }

    // SKU id → 平台商品编码（MSKU）。
    var listingsBySkuId = new HashMap<UUID, Set<String>>();
    for (int page = 0; page < MAX_LIST_PAGES; page++) {
      var listingPage = productSales.listings(page, bearer);
      if (listingPage == null || listingPage.items() == null || listingPage.items().isEmpty()) break;
      for (var listing : listingPage.items()) {
        if (listing == null || listing.internalSkuIds() == null) continue;
        var msku =
            listing.externalItemId() == null || listing.externalItemId().isBlank()
                ? ""
                : listing.externalItemId().trim();
        if (msku.isBlank()) continue;
        for (var skuId : listing.internalSkuIds()) {
          if (skuId == null) continue;
          listingsBySkuId.computeIfAbsent(skuId, k -> new HashSet<>()).add(msku);
        }
      }
      if (listingPage.items().size() < ReportingProductSalesPort.PAGE_SIZE) break;
    }

    // SKU 编码 → 归属。
    var out = new HashMap<String, SkuMeta>();
    for (var entry : spuByCode.entrySet()) {
      out.put(entry.getKey(), new SkuMeta(entry.getValue(), ""));
    }
    for (var entry : listingsBySkuId.entrySet()) {
      var unique = entry.getValue();
      // ⚠️ **多归属 ⇒ 不唯一归属**：留空，不摊给任何一个（摊了就是造假）。
      var msku = unique.size() == 1 ? unique.iterator().next() : "";
      for (var code : skuIdByCode.entrySet()) {
        if (!code.getValue().equals(entry.getKey())) continue;
        var spu = spuByCode.getOrDefault(code.getKey(), "");
        out.put(code.getKey(), new SkuMeta(spu, msku));
      }
    }
    return out;
  }

  // ==========================================================================
  // 落库
  // ==========================================================================

  /** 一条利润事实（写库前的内存形态）。 */
  private record Fact(
      String operatingMode,
      String component,
      String shopCode,
      String skuCode,
      String spuCode,
      String mskuCode,
      String orderRef,
      LocalDate periodStart,
      LocalDate periodEnd,
      String currency,
      BigDecimal amount,
      BigDecimal quantity,
      String sourceService,
      String sourceReference) {}

  /**
   * 事实 upsert：同一「模式 + 成本项 + 维度 + 期间 + 币种 + 来源服务 + 上游行」只留一条。
   *
   * <p>⚠️ 维度列全部 {@code NOT NULL DEFAULT ''} ⇒ 冲突目标可以逐列列出，
   * 不用 coalesce 把 NULL 归一（见 V379 与 V398 的说明：PostgreSQL 的 UNIQUE 把 NULL 视为互不相等）。
   */
  private static final String FACT_UPSERT =
      """
      insert into reporting_profit_fact
        (id,tenant_id,operating_mode,component,shop_code,sku_code,spu_code,msku_code,order_ref,
         period_start,period_end,currency,amount,quantity,source_service,source_reference,updated_at)
      values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now())
      on conflict (tenant_id,operating_mode,component,shop_code,sku_code,spu_code,msku_code,order_ref,
                   period_start,period_end,currency,source_service,source_reference)
      do update set amount = excluded.amount,
                    quantity = excluded.quantity,
                    updated_at = now()
      """;

  /** 写一批事实（**整批在一个事务里**）。 */
  private int writeFacts(UUID tenantId, List<Fact> facts) {
    if (facts.isEmpty()) return 0;
    return tenants.run(
        tenantId,
        () -> {
          int written = 0;
          for (var fact : facts) {
            sql.execute(
                FACT_UPSERT,
                Ids.next(),
                tenantId,
                fact.operatingMode(),
                fact.component(),
                fact.shopCode(),
                fact.skuCode(),
                fact.spuCode(),
                fact.mskuCode(),
                fact.orderRef(),
                fact.periodStart(),
                fact.periodEnd(),
                fact.currency(),
                fact.amount(),
                fact.quantity(),
                fact.sourceService(),
                fact.sourceReference());
            written++;
          }
          return written;
        });
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  /**
   * 归集费用类别 → 本模块的成本项。
   *
   * <p>⚠️ **返回 null = 这笔钱不归本模块写**（平台费 / 物流费 / 广告费归 12.3.1.2 结算明细；
   * 同一笔钱记两次就是重复计成本）。
   */
  static String componentOf(String feeCategory) {
    if (feeCategory == null) return null;
    return switch (feeCategory.trim().toUpperCase()) {
      case "HEAD_MILE_FEE" -> "HEAD_FREIGHT";
      case "PERSONNEL_COST", "OFFICE_COST", "SUBSCRIPTION_FEE" -> "OTHER_COST";
      case "PLATFORM_FEE", "LOGISTICS_FEE", "AD_FEE" -> null;
      default -> null;
    };
  }

  /** 上游经营模式 → 本模块的记号（**原样保留上游词表**；认不出来就是「未归属」）。 */
  static String normalizeMode(String raw) {
    if (raw == null || raw.isBlank()) return "UNKNOWN";
    for (var mode : ReportingProfitReportModels.OperatingMode.values()) {
      if (mode.name().equals(raw.trim())) return mode.name();
    }
    return "UNKNOWN";
  }

  /** 店铺 → 经营模式（**挂不上店铺就是「未归属」**，不猜）。 */
  private static String modeOf(Map<String, String> modes, String shopCode) {
    if (shopCode == null || shopCode.isBlank()) return "UNKNOWN";
    var mode = modes.get(shopCode);
    return mode == null || mode.isBlank() ? "UNKNOWN" : mode;
  }

  /** 币种（**只收 3 位大写码**；不合规就当「这条没有币种」，不硬塞一个）。 */
  private static String currency(String raw) {
    if (raw == null) return null;
    var value = raw.trim().toUpperCase();
    return value.matches("^[A-Z]{3}$") ? value : null;
  }

  private static ReportingProfitReportModels.ProfitRefreshResult failed(String source, String reason) {
    // ⚠️ 上游读不到 ⇒ 这一路**失败**，不是「这一路没有数据」。
    return new ReportingProfitReportModels.ProfitRefreshResult(source, 0, 0, "FAILED", reason);
  }
}
