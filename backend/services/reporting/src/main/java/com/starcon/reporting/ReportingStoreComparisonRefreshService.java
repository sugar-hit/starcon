package com.starcon.reporting;

import com.starcon.tenancy.Ids;
import com.starcon.tenancy.TenantTransactions;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

/**
 * 13.1.1.2 多店对比的**拉取适配器**（上游授权只读 API → 店铺维度投影事实）。
 *
 * <p>⚠️ <b>本类只写投影</b>（{@code reporting_store_profile} /
 * {@code reporting_store_metric_fact}），不产生任何业务事实 —— 成交金额仍然在 trade，
 * 广告花费仍然在 channel，店铺归属仍然在 channel 的 {@code shop} 表。
 *
 * <p><b>🔴 三条纪律</b>：
 *
 * <ol>
 *   <li><b>幂等</b>：事实行按「店铺 + 指标 + 日 + 币种 + 来源服务 + 上游行」唯一 ⇒
 *       重复刷新是**覆盖**而不是翻倍。
 *   <li><b>口径唯一</b>：指标码必须已在 {@code reporting_store_metric_definition} 登记
 *       （DB 触发器钉死）⇒ 事实不能自己发明指标码。
 *   <li><b>读不到 ≠ 没有数据</b>：上游故障一律翻成失败（{@code FAILED}），**不用空集合冒充**。
 * </ol>
 *
 * <p><b>🔴 为什么广告指标要逐日拉</b>：channel 的 {@code dimension=SHOP} 分组里**没有日期桶**
 * （group by 是 {@code shop_code, shop_name, currency}），一次请求给的是整个区间的店铺合计。
 * 本模块的事实表按日存 ⇒ 区间有 N 天就发 N 次 {@code from = to = 某一天} 的请求。
 * 代价是请求数，所以服务层对区间长度有硬上限（{@link #MAX_WINDOW_DAYS}），超了直接 400。
 */
@Service
public class ReportingStoreComparisonRefreshService {

  /** 一次刷新允许的最大天数（含环比所需的上一等长窗口，总天数 = 2 × 窗口天数）。 */
  static final int MAX_WINDOW_DAYS = 46;

  /** 单次拉取最多翻多少页平台单。 */
  static final int MAX_ORDER_PAGES = 25;

  private final ReportingStoreComparisonPort port;
  private final ReportingTradePort trade;
  private final DSLContext sql;
  private final TenantTransactions tenants;

  public ReportingStoreComparisonRefreshService(
      ReportingStoreComparisonPort port,
      ReportingTradePort trade,
      DSLContext sql,
      TenantTransactions tenants) {
    this.port = port;
    this.trade = trade;
    this.sql = sql;
    this.tenants = tenants;
  }

  // ==========================================================================
  // 刷新
  // ==========================================================================

  /**
   * 刷新店铺维度投影。
   *
   * @param from 当前窗口起始日
   * @param to 当前窗口结束日
   * @param timezone 分桶所用时区（跨时区店铺汇总必须显示所用时区）
   */
  public List<ReportingStoreComparisonModels.StoreRefreshResult> refresh(
      java.util.UUID tenantId, String bearer, LocalDate from, LocalDate to, ZoneId timezone) {
    var previous = previousWindow(from, to);
    // ⚠️ 上一等长窗口也要投影：增长口径（窗口环比）要拿它当分母。
    var results = new ArrayList<ReportingStoreComparisonModels.StoreRefreshResult>();
    results.add(refreshProfiles(tenantId, bearer));
    results.add(refreshOrders(tenantId, bearer, from, to, previous.from(), previous.to(), timezone));
    results.add(refreshReturns(tenantId, bearer, from, to, previous.from(), previous.to(), timezone));
    results.add(refreshAds(tenantId, bearer, from, to, timezone));
    return results;
  }

  /** 上一等长窗口（[from, to] 往前挪一整个窗口长度）。 */
  public static Window previousWindow(LocalDate from, LocalDate to) {
    var days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
    return new Window(from.minusDays(days), from.minusDays(1));
  }

  /** 一个时间窗口。 */
  public record Window(LocalDate from, LocalDate to) {}

  // ==========================================================================
  // 店铺档案
  // ==========================================================================

  /**
   * 店铺档案：店铺清单（channel）+ 店铺归属的平台（channel 广告活动）。
   *
   * <p>⚠️ **店铺归属的平台只能从广告活动带出来**：上游的店铺清单只有「编码 + 名称」，
   * 没有平台列。没有任何广告活动的店铺 ⇒ 平台留空 ⇒ 查询侧归到「未标注平台」组并如实说明。
   */
  private ReportingStoreComparisonModels.StoreRefreshResult refreshProfiles(
      java.util.UUID tenantId, String bearer) {
    var names = new LinkedHashMap<String, String>();
    var platforms = new LinkedHashMap<String, String[]>();
    try {
      var options = port.adOptions(bearer);
      if (options != null && options.shops() != null) {
        for (var shop : options.shops()) {
          if (shop == null || shop.value() == null || shop.value().isBlank()) continue;
          names.put(shop.value().trim(), shop.label() == null ? "" : shop.label());
        }
      }
      for (int page = 0; page < ReportingStoreComparisonPort.MAX_PAGES; page++) {
        var campaigns = port.adCampaigns(page, bearer);
        if (campaigns == null || campaigns.items() == null || campaigns.items().isEmpty()) break;
        for (var campaign : campaigns.items()) {
          if (campaign.shopCode() == null || campaign.shopCode().isBlank()) continue;
          var code = campaign.shopCode().trim();
          if (campaign.shopName() != null && !campaign.shopName().isBlank()) {
            names.put(code, campaign.shopName());
          }
          platforms.put(
              code,
              new String[] {
                campaign.platformCode() == null ? "" : campaign.platformCode(),
                campaign.platformName() == null ? "" : campaign.platformName()
              });
        }
        if (campaigns.items().size() < ReportingStoreComparisonPort.PAGE_SIZE) break;
      }
    } catch (org.springframework.web.server.ResponseStatusException e) {
      return new ReportingStoreComparisonModels.StoreRefreshResult(
          "channel-shops",
          0,
          0,
          "FAILED",
          e.getReason() == null ? "上游读不到" : e.getReason());
    }
    // ⚠️ 广告活动带出来的店铺也要进档案（它们可能不在「店铺清单」下拉里，例如已归档店铺）。
    var all = new LinkedHashMap<String, String>(names);
    for (var code : platforms.keySet()) all.putIfAbsent(code, "");
    int projected =
        tenants.run(
            tenantId,
            () -> {
              var count = 0;
              for (var entry : all.entrySet()) {
                var platform = platforms.get(entry.getKey());
                sql.execute(
                    """
                    insert into reporting_store_profile
                      (id,tenant_id,shop_code,shop_name,platform_code,platform_name,
                       source_service,source_reference,created_at,updated_at)
                    values (?,?,?,?,?,?,'channel',?,now(),now())
                    on conflict (tenant_id, shop_code) do update
                      set shop_name = excluded.shop_name,
                          platform_code = excluded.platform_code,
                          platform_name = excluded.platform_name,
                          source_reference = excluded.source_reference,
                          updated_at = now()
                    """,
                    Ids.next(),
                    tenantId,
                    entry.getKey(),
                    entry.getValue(),
                    platform == null ? "" : platform[0],
                    platform == null ? "" : platform[1],
                    "channel:shops:" + entry.getKey());
                count++;
              }
              return count;
            });
    return new ReportingStoreComparisonModels.StoreRefreshResult(
        "channel-shops", all.size(), projected, "SUCCEEDED", "已按店铺清单 + 广告活动投影店铺档案。");
  }

  // ==========================================================================
  // 成交金额与订单量（trade 平台单）
  // ==========================================================================

  /**
   * 平台单 → 店铺维度的成交金额（平台口径 / 还原口径）与订单量。
   *
   * <p>⚠️ **两份成交金额都落**：平台报告口径（{@code platformGrandTotalAmount}）与我方还原口径
   * （{@code restoredGrandTotalAmount}）。两者之差就是「跨平台口径差异额」——
   * 蓝图的「跨平台对比要处理口径差异」在数据层的落法。
   *
   * <p>⚠️ **时间用平台下单时刻**（{@code platformPlacedAt}），没有它时回落到同步时刻并**如实标注**
   * （同步时刻是「我什么时候看到的」，不是「什么时候成交的」）。
   */
  private ReportingStoreComparisonModels.StoreRefreshResult refreshOrders(
      java.util.UUID tenantId,
      String bearer,
      LocalDate from,
      LocalDate to,
      LocalDate previousFrom,
      LocalDate previousTo,
      ZoneId timezone) {
    var facts = new ArrayList<Fact>();
    int fetched = 0;
    boolean truncated = false;
    for (int page = 0; page < MAX_ORDER_PAGES; page++) {
      var pageData = port.platformOrders(page, bearer);
      if (pageData == null || pageData.rows() == null || pageData.rows().isEmpty()) break;
      for (var row : pageData.rows()) {
        fetched++;
        if (row.shopCode() == null || row.shopCode().isBlank()) continue;
        var occurred = row.platformPlacedAt() == null ? row.syncedAt() : row.platformPlacedAt();
        if (occurred == null) continue;
        var bucketDate = occurred.atZoneSameInstant(timezone).toLocalDate();
        if (bucketDate.isBefore(previousFrom) || bucketDate.isAfter(to)) continue;
        var currency = row.currency() == null ? "USD" : row.currency();
        var reference = row.id() == null ? row.externalOrderNo() : row.id().toString();
        if (reference == null || reference.isBlank()) continue;
        facts.add(
            new Fact(
                row.shopCode().trim(),
                ReportingStoreComparisonCatalog.STORE_GMV_PLATFORM,
                bucketDate,
                currency,
                row.platformGrandTotalAmount() == null ? BigDecimal.ZERO : row.platformGrandTotalAmount(),
                BigDecimal.ZERO,
                "trade",
                "platform-order:" + reference));
        facts.add(
            new Fact(
                row.shopCode().trim(),
                ReportingStoreComparisonCatalog.STORE_GMV_RESTORED,
                bucketDate,
                currency,
                row.restoredGrandTotalAmount() == null ? BigDecimal.ZERO : row.restoredGrandTotalAmount(),
                BigDecimal.ZERO,
                "trade",
                "platform-order:" + reference));
        facts.add(
            new Fact(
                row.shopCode().trim(),
                ReportingStoreComparisonCatalog.STORE_ORDER_COUNT,
                bucketDate,
                currency,
                BigDecimal.ONE,
                BigDecimal.ZERO,
                "trade",
                "platform-order:" + reference));
      }
      if (pageData.rows().size() < ReportingStoreComparisonPort.PAGE_SIZE) break;
      if (page >= MAX_ORDER_PAGES - 1) truncated = true;
    }
    var projected = project(tenantId, facts);
    return new ReportingStoreComparisonModels.StoreRefreshResult(
        "trade-platform-orders",
        fetched,
        projected,
        truncated ? "TRUNCATED" : "SUCCEEDED",
        truncated
            ? "平台单只翻到 " + MAX_ORDER_PAGES + " 页为止，超过部分的成交金额**未计入**。"
            : "按平台单逐条投影成交金额（平台口径 + 还原口径）与订单量。");
  }

  // ==========================================================================
  // 退货额（trade 售后单 → 店铺）
  // ==========================================================================

  /**
   * 售后退货单 → 店铺维度的退货额。
   *
   * <p>⚠️ **售后单本身没有店铺维度** ⇒ 靠**订单号**回挂：售后单的 {@code orderNo} 是我方
   * 销售订单号，平台单投影里有同一个号（{@code platform_order.order_no}）。
   * 挂不上的退货单**不计入**店铺口径（猜一个店铺比不算更糟）。
   */
  private ReportingStoreComparisonModels.StoreRefreshResult refreshReturns(
      java.util.UUID tenantId,
      String bearer,
      LocalDate from,
      LocalDate to,
      LocalDate previousFrom,
      LocalDate previousTo,
      ZoneId timezone) {
    var shopByOrderNo = new LinkedHashMap<String, String>();
    for (int page = 0; page < MAX_ORDER_PAGES; page++) {
      var pageData = port.platformOrders(page, bearer);
      if (pageData == null || pageData.rows() == null || pageData.rows().isEmpty()) break;
      for (var row : pageData.rows()) {
        if (row.orderNo() == null || row.orderNo().isBlank()) continue;
        if (row.shopCode() == null || row.shopCode().isBlank()) continue;
        shopByOrderNo.putIfAbsent(row.orderNo().trim(), row.shopCode().trim());
      }
      if (pageData.rows().size() < ReportingStoreComparisonPort.PAGE_SIZE) break;
    }
    var facts = new ArrayList<Fact>();
    int fetched = 0;
    int unmatched = 0;
    for (int page = 0; page < ReportingTradePort.MAX_PAGES; page++) {
      var rows = trade.aftersalesCases(previousFrom, to, page, bearer);
      if (rows.rows() == null || rows.rows().isEmpty()) break;
      for (var row : rows.rows()) {
        fetched++;
        if (row.requestedAmount() == null) continue;
        if (row.receivedAt() == null) continue;
        if (row.orderNo() == null || row.orderNo().isBlank()) {
          unmatched++;
          continue;
        }
        var shopCode = shopByOrderNo.get(row.orderNo().trim());
        if (shopCode == null) {
          unmatched++;
          continue;
        }
        var bucketDate = row.receivedAt().atZoneSameInstant(timezone).toLocalDate();
        var currency = row.currency() == null ? "USD" : row.currency();
        facts.add(
            new Fact(
                shopCode,
                ReportingStoreComparisonCatalog.STORE_RETURN_AMOUNT,
                bucketDate,
                currency,
                row.requestedAmount(),
                row.requestedQuantity() == null ? BigDecimal.ZERO : row.requestedQuantity(),
                "trade",
                "aftersales:" + (row.caseNo() == null ? row.id() : row.caseNo())));
      }
      if (rows.rows().size() < ReportingTradePort.PAGE_SIZE) break;
    }
    var projected = project(tenantId, facts);
    return new ReportingStoreComparisonModels.StoreRefreshResult(
        "trade-returns",
        fetched,
        projected,
        "SUCCEEDED",
        "按售后单投影退货额；其中 " + unmatched + " 单挂不上店铺（售后单没有店铺维度，"
            + "按订单号回挂），**未计入**任何店铺。");
  }

  // ==========================================================================
  // 广告指标（channel）
  // ==========================================================================

  /**
   * 广告花费与广告销售额（**逐日**拉，见类注释里「为什么逐日拉」）。
   *
   * <p>⚠️ **只取花费与广告归因的销售额**，不取店铺总销售额 —— 那是收入侧的事。
   */
  private ReportingStoreComparisonModels.StoreRefreshResult refreshAds(
      java.util.UUID tenantId, String bearer, LocalDate from, LocalDate to, ZoneId timezone) {
    var facts = new ArrayList<Fact>();
    int fetched = 0;
    int days = 0;
    for (var day = from; !day.isAfter(to); day = day.plusDays(1)) {
      days++;
      var page = port.adShopRows(day, bearer);
      if (page == null || page.items() == null) continue;
      for (var row : page.items()) {
        if (row.dimensionKey() == null || row.dimensionKey().isBlank()) continue;
        var currency = row.currency() == null ? "USD" : row.currency();
        var reference = "ad-shop:" + row.dimensionKey() + ":" + day;
        if (row.spend() != null) {
          fetched++;
          facts.add(
              new Fact(
                  row.dimensionKey().trim(),
                  ReportingStoreComparisonCatalog.STORE_AD_SPEND,
                  day,
                  currency,
                  row.spend(),
                  BigDecimal.ZERO,
                  "channel",
                  reference));
        }
        if (row.sales() != null) {
          fetched++;
          facts.add(
              new Fact(
                  row.dimensionKey().trim(),
                  ReportingStoreComparisonCatalog.STORE_AD_SALES,
                  day,
                  currency,
                  row.sales(),
                  BigDecimal.ZERO,
                  "channel",
                  reference));
        }
      }
    }
    var projected = project(tenantId, facts);
    return new ReportingStoreComparisonModels.StoreRefreshResult(
        "channel-ads",
        fetched,
        projected,
        "SUCCEEDED",
        "逐日拉取店铺维度广告指标（" + days + " 天，channel 的店铺分组没有日期桶，"
            + "所以一天一次请求）。");
  }

  // ==========================================================================
  // 落库
  // ==========================================================================

  /** 一条待落库的事实。 */
  private record Fact(
      String shopCode,
      String metricCode,
      LocalDate bucketDate,
      String currency,
      BigDecimal value,
      BigDecimal quantity,
      String sourceService,
      String sourceReference) {}

  /** 事实 upsert（幂等：同一「店铺 + 指标 + 日 + 币种 + 来源 + 上游行」只留一条）。 */
  private int project(java.util.UUID tenantId, List<Fact> facts) {
    if (facts.isEmpty()) return 0;
    return tenants.run(
        tenantId,
        () -> {
          // ⚠️ 口径必须先播种：事实行的指标码必须已登记（DB 触发器钉死）。
          seedDefinitions(tenantId);
          var count = 0;
          for (var fact : facts) {
            sql.execute(
                """
                insert into reporting_store_metric_fact
                  (id,tenant_id,shop_code,metric_code,bucket_date,currency,value,quantity,
                   timezone,source_service,source_reference,updated_at)
                values (?,?,?,?,?,?,?,?,'UTC',?,?,now())
                on conflict (tenant_id, shop_code, metric_code, bucket_date, currency,
                             source_service, source_reference)
                do update set value = excluded.value,
                              quantity = excluded.quantity,
                              updated_at = now()
                """,
                Ids.next(),
                tenantId,
                fact.shopCode(),
                fact.metricCode(),
                fact.bucketDate(),
                fact.currency(),
                fact.value(),
                fact.quantity(),
                fact.sourceService(),
                fact.sourceReference());
            count++;
          }
          return count;
        });
  }

  /**
   * 确保该租户有标准口径（**幂等**）。
   *
   * <p>⚠️ 与 13.1.1.1 同一纪律：口径是**租户自己**的标准口径，第一次用到这个租户时按标准份
   * 播种一份；租户改了口径，播种**不会**覆盖它（{@code on conflict do nothing}）。
   */
  void seedDefinitions(java.util.UUID tenantId) {
    var exists =
        sql.fetch(
            "select 1 from reporting_store_metric_definition where tenant_id = ? limit 1", tenantId);
    if (!exists.isEmpty()) return;
    for (var definition : ReportingStoreComparisonCatalog.STANDARD) {
      sql.execute(
          """
          insert into reporting_store_metric_definition
            (id,tenant_id,metric_code,display_name,unit,aggregation,numerator_metric_code,
             denominator_metric_code,base_metric_code,currency_aware,description,owner_module,
             enabled,created_at,updated_at)
          values (?,?,?,?,?,?,?,?,?,?,?,?,true,now(),now())
          on conflict (tenant_id, metric_code) do nothing
          """,
          Ids.next(),
          tenantId,
          definition.metricCode(),
          definition.displayName(),
          definition.unit().name(),
          definition.aggregation().name(),
          definition.numeratorMetricCode(),
          definition.denominatorMetricCode(),
          definition.baseMetricCode(),
          definition.currencyAware(),
          definition.description(),
          definition.ownerModule());
    }
  }

  /** 供查询侧复用：把窗口长度换算成天数（校验用）。 */
  static int windowDays(LocalDate from, LocalDate to) {
    return (int) java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
  }
}
