package com.starcon.reporting;

import com.starcon.tenancy.Ids;
import com.starcon.tenancy.TenantTransactions;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

/**
 * 13.2.1.1 销量趋势的**拉取适配器**（上游授权只读 API → 销量趋势投影事实 + 异常事件）。
 *
 * <p>⚠️ <b>本类只写投影</b>（{@code reporting_sales_trend_fact} /
 * {@code reporting_sales_event}，以及共用的构成明细表 {@code reporting_drilldown_fact}），
 * 不产生任何业务事实 —— 成交金额仍然在 trade，促销仍然在 channel，断货风险仍然在 trade。
 *
 * <p><b>🔴 三条纪律</b>：
 *
 * <ol>
 *   <li><b>幂等</b>：事实行按「指标 + 币种 + 维度 + 维度键 + 时刻 + 来源服务 + 上游行」唯一 ⇒
 *       重复刷新是**覆盖**而不是翻倍；
 *   <li><b>口径唯一</b>：指标码必须已登记（本表 **或** 13.1.1.1 的口径表），
 *       DB 触发器钉死 ⇒ 事实不能自己发明指标码；
 *   <li><b>读不到 ≠ 没有数据</b>：上游故障一律翻成失败（{@code FAILED}），**不用空集合冒充**。
 * </ol>
 *
 * <p><b>🔴 为什么要投三个窗口</b>：本模块的输出含**环比**（上一个同粒度桶 / 上一等长窗口）
 * 与**同比**（去年同期）。基期没有投影 ⇒ 增长率**算不出**（返回 null，不是 0%）。
 * ⇒ 一次刷新把「当前窗口 + 上一等长窗口 + 去年同期窗口」一起投掉，
 * 否则用户第一次刷新就看到一片「无基期」。
 */
@Service
public class ReportingSalesTrendRefreshService {

  /** 当前窗口最多多少天（含边界桶）。 */
  static final int MAX_WINDOW_DAYS = 92;

  /** 每个窗口最多取多少张订单的**明细行**（明细要逐单取，有上限）。 */
  static final int MAX_DETAIL_FETCHES_PER_WINDOW = 50;

  /** 单个窗口最多翻多少页销售订单（{@code 200 × 25 = 5000} 行）。 */
  static final int MAX_ORDER_PAGES = 25;

  private final ReportingTradePort trade;
  private final ReportingSalesTrendPort port;
  private final DSLContext sql;
  private final TenantTransactions tenants;

  public ReportingSalesTrendRefreshService(
      ReportingTradePort trade,
      ReportingSalesTrendPort port,
      DSLContext sql,
      TenantTransactions tenants) {
    this.trade = trade;
    this.port = port;
    this.sql = sql;
    this.tenants = tenants;
  }

  // ==========================================================================
  // 窗口
  // ==========================================================================

  /** 一个投影窗口。 */
  public record Window(String name, LocalDate from, LocalDate to) {}

  /**
   * 三个投影窗口：当前 / 上一等长 / 去年同期。
   *
   * <p>⚠️ **按桶等长**，不是按天等长：月粒度下「上一等长窗口」必须是**上一个等数量的月桶**，
   * 否则拿 8 月 + 9 月两个月去比 10 月一个月，数字没有可比性。
   */
  public static List<Window> windows(
      LocalDate from, LocalDate to, ReportingSalesTrendGranularity granularity) {
    var currentFrom = granularity.alignStart(from);
    var currentTo = granularity.alignEnd(to);
    var buckets = granularity.bucketCount(currentFrom, currentTo);
    var currentKey = granularity.truncate(currentFrom.atStartOfDay());
    var previousKey = granularity.shift(currentKey, -buckets);
    var previousLastKey = granularity.shift(previousKey, buckets - 1);
    var yearAgoKey = granularity.yearAgo(currentKey);
    var yearAgoLastKey = granularity.shift(yearAgoKey, buckets - 1);
    return List.of(
        new Window("current", currentFrom, currentTo),
        new Window(
            "previous", previousKey.toLocalDate(), granularity.bucketEndDate(previousLastKey)),
        new Window(
            "year-ago",
            yearAgoKey.toLocalDate(),
            granularity.bucketEndDate(yearAgoLastKey)));
  }

  // ==========================================================================
  // 刷新
  // ==========================================================================

  /**
   * 刷新销量趋势投影。
   *
   * @param from 当前窗口起始日
   * @param to 当前窗口结束日
   * @param granularity 分桶粒度
   * @param timezone 构成明细（{@code reporting_drilldown_fact.bucket_date}）所用时区
   */
  public List<ReportingSalesTrendModels.SalesTrendRefreshResult> refresh(
      UUID tenantId,
      String bearer,
      LocalDate from,
      LocalDate to,
      ReportingSalesTrendGranularity granularity,
      ZoneId timezone) {
    var windows = windows(from, to, granularity);
    var results = new ArrayList<ReportingSalesTrendModels.SalesTrendRefreshResult>();
    // ⚠️ 归属表是**本次刷新内共享**的（三个窗口都用它），所以先取回来再逐窗口投影 ——
    //   刻意**不做成实例字段**：刷新服务是单例，并发刷新会互相覆盖。
    var attribution = fetchStoreAttribution(bearer);
    results.add(
        attribution.failure() == null
            ? new ReportingSalesTrendModels.SalesTrendRefreshResult(
                "trade-platform-orders",
                attribution.fetched(),
                attribution.mapping().size(),
                attribution.truncated() ? "TRUNCATED" : "SUCCEEDED",
                attribution.truncated()
                    ? "平台单超过单次上限，**更早的订单挂不上店铺**（不计入任何店铺）。"
                    : "已按平台单回挂销售订单的店铺归属（挂不上的订单不计入任何店铺）。")
            : new ReportingSalesTrendModels.SalesTrendRefreshResult(
                "trade-platform-orders", 0, 0, "FAILED", attribution.failure()));
    for (var window : windows) {
      results.add(refreshOrders(tenantId, bearer, window, timezone, attribution.mapping()));
    }
    results.add(refreshPromotions(tenantId, bearer));
    results.add(refreshFlashSales(tenantId, bearer));
    results.add(refreshStockoutEvents(tenantId, bearer));
    // 🔴 **上游读不到 ⇒ 整个刷新失败（503）**：不能返回一份「看起来成功、其实少了一半」的投影。
    //   失败原因写明是哪一路读不到 —— 「读不到」与「没有数据」必须分开说。
    var failed = new ArrayList<String>();
    for (var result : results) {
      if ("FAILED".equals(result.status())) {
        failed.add(result.source() + "：" + result.note());
      }
    }
    if (!failed.isEmpty()) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "销量趋势投影失败（上游读不到，不是「没有数据」）：" + String.join("；", failed));
    }
    return results;
  }

  /** 店铺归属的拉取结果。 */
  private record Attribution(
      Map<UUID, String> mapping, int fetched, boolean truncated, String failure) {}

  // ==========================================================================
  // 店铺归属（平台单回挂）
  // ==========================================================================

  /**
   * 销售订单 → 店铺的归属表（平台单回挂）。
   *
   * <p>⚠️ **销售订单本身没有店铺字段** —— 唯一来源是 trade 的平台单
   * （{@code platform_order.sales_order_id}）。挂不上的订单**不计入任何店铺**，
   * 刷新结果里会报出挂不上的条数。
   */
  private Attribution fetchStoreAttribution(String bearer) {
    var map = new LinkedHashMap<UUID, String>();
    int fetched = 0;
    try {
      for (int page = 0; page < ReportingSalesTrendPort.MAX_PAGES; page++) {
        var pageData = port.platformOrders(page, bearer);
        if (pageData == null || pageData.rows() == null || pageData.rows().isEmpty()) break;
        for (var row : pageData.rows()) {
          fetched++;
          if (row.salesOrderId() == null || row.shopCode() == null || row.shopCode().isBlank()) {
            continue;
          }
          map.put(row.salesOrderId(), row.shopCode().trim());
        }
        if (pageData.rows().size() < ReportingSalesTrendPort.PAGE_SIZE) break;
      }
    } catch (org.springframework.web.server.ResponseStatusException e) {
      // ⚠️ **读不到 ≠ 没有数据**：这一路失败就报 FAILED，**不用空映射冒充「没有店铺归属」**。
      return new Attribution(
          Map.of(), fetched, false, e.getReason() == null ? "上游读不到" : e.getReason());
    }
    var truncated = fetched >= ReportingSalesTrendPort.PAGE_SIZE * ReportingSalesTrendPort.MAX_PAGES;
    return new Attribution(map, fetched, truncated, null);
  }

  // ==========================================================================
  // 销售订单 → 趋势事实
  // ==========================================================================

  /**
   * 一个窗口的销售订单 → 趋势事实 + 构成明细。
   *
   * <p>⚠️ **金额与订单量全量，销量按明细上限截断** —— 销量只存在于订单明细里，
   * 而明细要逐单取（有上限）。截断**如实报出**，不悄悄少算。
   */
  private ReportingSalesTrendModels.SalesTrendRefreshResult refreshOrders(
      UUID tenantId,
      String bearer,
      Window window,
      ZoneId timezone,
      Map<UUID, String> attribution) {
    var facts = new ArrayList<Fact>();
    var drilldowns = new ArrayList<Drilldown>();
    int fetched = 0;
    int detailFetches = 0;
    boolean truncated = false;
    boolean detailTruncated = false;
    int unattributed = 0;
    try {
      for (int page = 0; page < MAX_ORDER_PAGES; page++) {
        var pageData = trade.salesOrders(window.from(), window.to(), page, bearer);
        if (pageData == null || pageData.rows() == null || pageData.rows().isEmpty()) break;
        for (var row : pageData.rows()) {
          fetched++;
          if (row.submittedAt() == null) continue;
          var currency = row.currency() == null || row.currency().isBlank() ? "USD" : row.currency();
          var bucketDate = row.submittedAt().atZoneSameInstant(timezone).toLocalDate();
          var amount = row.grandTotalAmount() == null ? BigDecimal.ZERO : row.grandTotalAmount();
          var reference = row.orderNo() == null || row.orderNo().isBlank() ? null : row.orderNo();
          if (reference == null) continue;

          facts.add(
              new Fact(
                  ReportingMetricCatalog.SALES_AMOUNT,
                  currency,
                  "TOTAL",
                  "",
                  row.submittedAt(),
                  amount,
                  BigDecimal.ZERO,
                  "trade",
                  "sales-order:" + reference));
          facts.add(
              new Fact(
                  ReportingMetricCatalog.ORDER_COUNT,
                  currency,
                  "TOTAL",
                  "",
                  row.submittedAt(),
                  BigDecimal.ONE,
                  BigDecimal.ZERO,
                  "trade",
                  "sales-order:" + reference));
          drilldowns.add(
              new Drilldown(
                  ReportingMetricCatalog.SALES_AMOUNT,
                  bucketDate,
                  currency,
                  "ORDER",
                  reference,
                  reference,
                  BigDecimal.ZERO,
                  amount,
                  reference));

          // 店铺维度：靠平台单回挂；挂不上就不计入任何店铺（如实计数）。
          var shopCode = row.id() == null ? null : attribution.get(row.id());
          if (shopCode == null) {
            unattributed++;
          } else {
            facts.add(
                new Fact(
                    ReportingSalesTrendCatalog.STORE_SALES_AMOUNT,
                    currency,
                    "STORE",
                    shopCode,
                    row.submittedAt(),
                    amount,
                    BigDecimal.ZERO,
                    "trade",
                    "sales-order:" + reference));
            facts.add(
                new Fact(
                    ReportingSalesTrendCatalog.STORE_SALES_ORDER_COUNT,
                    currency,
                    "STORE",
                    shopCode,
                    row.submittedAt(),
                    BigDecimal.ONE,
                    BigDecimal.ZERO,
                    "trade",
                    "sales-order:" + reference));
          }

          // 销量与商品维度只在**订单明细**里 ⇒ 逐单取（有上限）。
          if (detailFetches < MAX_DETAIL_FETCHES_PER_WINDOW && row.id() != null) {
            detailFetches++;
            var detail = trade.salesOrderDetail(row.id(), bearer);
            if (detail != null && detail.lines() != null) {
              for (var line : detail.lines()) {
                if (line.quantity() == null) continue;
                facts.add(
                    new Fact(
                        ReportingMetricCatalog.UNIT_SOLD,
                        currency,
                        "TOTAL",
                        "",
                        row.submittedAt(),
                        BigDecimal.ZERO,
                        line.quantity(),
                        "trade",
                        "sales-order:" + reference + "#" + line.skuCode()));
                if (line.skuCode() == null || line.skuCode().isBlank()) continue;
                var skuCode = line.skuCode().trim();
                facts.add(
                    new Fact(
                        ReportingSalesTrendCatalog.PRODUCT_SALES_QUANTITY,
                        currency,
                        "PRODUCT",
                        skuCode,
                        row.submittedAt(),
                        BigDecimal.ZERO,
                        line.quantity(),
                        "trade",
                        "sales-order:" + reference + "#" + skuCode));
                facts.add(
                    new Fact(
                        ReportingSalesTrendCatalog.PRODUCT_SALES_AMOUNT,
                        currency,
                        "PRODUCT",
                        skuCode,
                        row.submittedAt(),
                        line.lineAmount() == null ? BigDecimal.ZERO : line.lineAmount(),
                        BigDecimal.ZERO,
                        "trade",
                        "sales-order:" + reference + "#" + skuCode));
                drilldowns.add(
                    new Drilldown(
                        ReportingMetricCatalog.UNIT_SOLD,
                        bucketDate,
                        currency,
                        "SKU",
                        skuCode,
                        line.skuName() == null ? skuCode : line.skuName(),
                        line.quantity(),
                        line.lineAmount() == null ? BigDecimal.ZERO : line.lineAmount(),
                        reference + "#" + skuCode));
              }
            }
          } else if (row.id() != null) {
            detailTruncated = true;
          }
        }
        if (pageData.rows().size() < ReportingTradePort.PAGE_SIZE) break;
      }
    } catch (org.springframework.web.server.ResponseStatusException e) {
      // ⚠️ **读不到 ≠ 没有数据**：这一路失败就报 FAILED，**不用空集合冒充「上游没有订单」**。
      return new ReportingSalesTrendModels.SalesTrendRefreshResult(
          "trade-orders:" + window.name(),
          fetched,
          0,
          "FAILED",
          e.getReason() == null ? "上游读不到" : e.getReason());
    }
    truncated = fetched >= ReportingTradePort.PAGE_SIZE * MAX_ORDER_PAGES;
    var projected = project(tenantId, facts, drilldowns);
    var note =
        new StringBuilder()
            .append("窗口 ")
            .append(window.from())
            .append(" ~ ")
            .append(window.to())
            .append("：订单 ")
            .append(fetched)
            .append(" 张，")
            .append("其中 ")
            .append(unattributed)
            .append(" 张挂不上店铺（不计入店铺维度）。");
    if (detailTruncated) {
      note.append(
          " ⚠️ 订单明细取到 ")
              .append(MAX_DETAIL_FETCHES_PER_WINDOW)
              .append(" 张为止（超过部分**未计入销量与商品维度**）。");
    }
    if (truncated) {
      note.append(" ⚠️ 订单超过单次上限，超出部分**未计入**。");
    }
    return new ReportingSalesTrendModels.SalesTrendRefreshResult(
        "trade-orders:" + window.name(),
        fetched,
        projected,
        truncated || detailTruncated ? "TRUNCATED" : "SUCCEEDED",
        note.toString());
  }

  // ==========================================================================
  // 异常事件
  // ==========================================================================

  /** 大促事件（channel 的促销）。 */
  private ReportingSalesTrendModels.SalesTrendRefreshResult refreshPromotions(
      UUID tenantId, String bearer) {
    var events = new ArrayList<Event>();
    int fetched = 0;
    try {
      for (int page = 0; page < ReportingSalesTrendPort.MAX_PAGES; page++) {
        var pageData = port.promotions(page, bearer);
        if (pageData == null || pageData.items() == null || pageData.items().isEmpty()) break;
        for (var row : pageData.items()) {
          fetched++;
          if (row.promotionCode() == null || row.promotionCode().isBlank()) continue;
          if (row.startAt() == null || row.endAt() == null) continue;
          events.add(
              new Event(
                  row.promotionCode(),
                  "PROMOTION",
                  row.promotionName() == null ? row.promotionCode() : row.promotionName(),
                  promotionDetail(row),
                  row.startAt().toLocalDate(),
                  row.endAt().toLocalDate(),
                  row.startAt(),
                  row.endAt(),
                  row.shopCode() == null ? "" : row.shopCode().trim(),
                  "",
                  "INFO",
                  "channel-promo",
                  "promotion:" + row.promotionCode()));
        }
        if (pageData.items().size() < ReportingSalesTrendPort.PAGE_SIZE) break;
      }
    } catch (org.springframework.web.server.ResponseStatusException e) {
      return new ReportingSalesTrendModels.SalesTrendRefreshResult(
          "channel-promotions",
          0,
          0,
          "FAILED",
          e.getReason() == null ? "上游读不到" : e.getReason());
    }
    var projected = projectEvents(tenantId, events, "channel-promo");
    return new ReportingSalesTrendModels.SalesTrendRefreshResult(
        "channel-promotions", fetched, projected, "SUCCEEDED", "已投影大促事件（促销记录）。");
  }

  /** 秒杀事件（channel 的秒杀）。 */
  private ReportingSalesTrendModels.SalesTrendRefreshResult refreshFlashSales(
      UUID tenantId, String bearer) {
    var events = new ArrayList<Event>();
    int fetched = 0;
    try {
      for (int page = 0; page < ReportingSalesTrendPort.MAX_PAGES; page++) {
        var pageData = port.flashSales(page, bearer);
        if (pageData == null || pageData.items() == null || pageData.items().isEmpty()) break;
        for (var row : pageData.items()) {
          fetched++;
          if (row.saleCode() == null || row.saleCode().isBlank()) continue;
          if (row.startAt() == null || row.endAt() == null) continue;
          events.add(
              new Event(
                  row.saleCode(),
                  "FLASH_SALE",
                  row.saleName() == null ? row.saleCode() : row.saleName(),
                  row.flashPrice() == null
                      ? "秒杀（未登记秒杀价）"
                      : "秒杀价 " + row.flashPrice() + (row.currency() == null ? "" : " " + row.currency()),
                  row.startAt().toLocalDate(),
                  row.endAt().toLocalDate(),
                  row.startAt(),
                  row.endAt(),
                  row.shopCode() == null ? "" : row.shopCode().trim(),
                  "",
                  "INFO",
                  "channel-flash-sale",
                  "flash-sale:" + row.saleCode()));
        }
        if (pageData.items().size() < ReportingSalesTrendPort.PAGE_SIZE) break;
      }
    } catch (org.springframework.web.server.ResponseStatusException e) {
      return new ReportingSalesTrendModels.SalesTrendRefreshResult(
          "channel-flash-sales",
          0,
          0,
          "FAILED",
          e.getReason() == null ? "上游读不到" : e.getReason());
    }
    var projected = projectEvents(tenantId, events, "channel-flash-sale");
    return new ReportingSalesTrendModels.SalesTrendRefreshResult(
        "channel-flash-sales", fetched, projected, "SUCCEEDED", "已投影秒杀事件。");
  }

  /** 断货 / 缺货事件（trade 的缺货与断货风险）。 */
  private ReportingSalesTrendModels.SalesTrendRefreshResult refreshStockoutEvents(
      UUID tenantId, String bearer) {
    var events = new ArrayList<Event>();
    int fetched = 0;
    LocalDate asOfDate = null;
    try {
      for (int page = 0; page < ReportingSalesTrendPort.MAX_PAGES; page++) {
        var pageData = port.stockoutItems(page, bearer);
        if (pageData == null || pageData.rows() == null || pageData.rows().isEmpty()) break;
        if (pageData.asOfDate() != null) asOfDate = pageData.asOfDate();
        for (var row : pageData.rows()) {
          fetched++;
          var skuCode = row.skuCode() == null ? "" : row.skuCode().trim();
          var skuName = row.skuName() == null || row.skuName().isBlank() ? skuCode : row.skuName();
          if (row.expectedStockoutDate() != null) {
            var day = row.expectedStockoutDate();
            events.add(
                new Event(
                    "stockout:" + skuCode + ":" + day,
                    "STOCKOUT",
                    "预计断货：" + skuName,
                    "预计断货日 " + day + "；可支撑 " + row.coverageDays() + " 天"
                        + "（紧急度 " + (row.urgencyLabel() == null ? row.urgency() : row.urgencyLabel()) + "）",
                    day,
                    day,
                    null,
                    null,
                    "",
                    skuCode,
                    "CRITICAL",
                    "trade",
                    "stockout:" + skuCode + ":" + day));
          }
          if (row.shortageQuantity() != null && row.shortageQuantity().signum() > 0 && asOfDate != null) {
            events.add(
                new Event(
                    "shortage:" + skuCode + ":" + asOfDate,
                    "SHORTAGE",
                    "缺货：" + skuName,
                    "缺口 " + row.shortageQuantity() + " 件（快照日期 " + asOfDate + "，涉及 "
                        + row.shopCount() + " 个店铺）",
                    asOfDate,
                    asOfDate,
                    null,
                    null,
                    "",
                    skuCode,
                    "WARNING",
                    "trade",
                    "shortage:" + skuCode + ":" + asOfDate));
          }
        }
        if (pageData.rows().size() < ReportingSalesTrendPort.PAGE_SIZE) break;
      }
    } catch (org.springframework.web.server.ResponseStatusException e) {
      return new ReportingSalesTrendModels.SalesTrendRefreshResult(
          "trade-stockout-risk",
          0,
          0,
          "FAILED",
          e.getReason() == null ? "上游读不到" : e.getReason());
    }
    var projected = projectEvents(tenantId, events, "trade");
    var note =
        events.isEmpty()
            ? "上游**没有断货风险快照**（不是读不到）—— 上游算过快照后这里才会有事件。"
            : "已投影断货 / 缺货事件。";
    return new ReportingSalesTrendModels.SalesTrendRefreshResult(
        "trade-stockout-risk", fetched, projected, "SUCCEEDED", note);
  }

  private String promotionDetail(ReportingSalesTrendPort.PromotionRow row) {
    var parts = new ArrayList<String>();
    if (row.promotionType() != null && !row.promotionType().isBlank()) {
      parts.add("类型 " + row.promotionType());
    }
    if (row.discountRate() != null) parts.add("折扣率 " + row.discountRate());
    if (row.discountAmount() != null) {
      parts.add("固定减免 " + row.discountAmount() + (row.currency() == null ? "" : " " + row.currency()));
    }
    if (row.platformCode() != null && !row.platformCode().isBlank()) parts.add("平台 " + row.platformCode());
    return parts.isEmpty() ? "促销" : String.join("，", parts);
  }

  // ==========================================================================
  // 落库
  // ==========================================================================

  /** 一条待落库的事实。 */
  private record Fact(
      String metricCode,
      String currency,
      String dimensionType,
      String dimensionKey,
      java.time.OffsetDateTime bucketStart,
      BigDecimal value,
      BigDecimal quantity,
      String sourceService,
      String sourceReference) {}

  /** 一条待落库的构成明细（共用表 {@code reporting_drilldown_fact}）。 */
  private record Drilldown(
      String metricCode,
      LocalDate bucketDate,
      String currency,
      String sourceKind,
      String referenceId,
      String referenceLabel,
      BigDecimal quantity,
      BigDecimal value,
      String sourceReference) {}

  /** 一条待落库的事件。 */
  private record Event(
      String eventCode,
      String eventType,
      String title,
      String detail,
      LocalDate eventDate,
      LocalDate endDate,
      java.time.OffsetDateTime startAt,
      java.time.OffsetDateTime endAt,
      String shopCode,
      String skuCode,
      String severity,
      String sourceService,
      String sourceReference) {}

  /**
   * 事实 upsert（幂等：同一「指标 + 币种 + 维度 + 维度键 + 时刻 + 来源 + 上游行」只留一条）。
   *
   * <p>⚠️ **时刻列用 {@code ?::timestamptz} 显式转型**：jOOQ 的 {@code setObject} 在
   * timestamptz 列上会按本地时区解释，**静默偏 −28800s**（东八区）——
   * 显式转型才是「UTC 时刻」的本意。
   */
  private int project(UUID tenantId, List<Fact> facts, List<Drilldown> drilldowns) {
    if (facts.isEmpty() && drilldowns.isEmpty()) return 0;
    return tenants.run(
        tenantId,
        () -> {
          // ⚠️ 口径必须先播种：事实行的指标码必须已登记（DB 触发器钉死）。
          seedDefinitions(tenantId);
          var count = 0;
          for (var fact : facts) {
            sql.execute(
                """
                insert into reporting_sales_trend_fact
                  (id,tenant_id,metric_code,currency,dimension_type,dimension_key,bucket_start,
                   value,quantity,source_service,source_reference,updated_at)
                values (?,?,?,?,?,?,?::timestamptz,?,?,?,?,now())
                on conflict (tenant_id, metric_code, currency, dimension_type, dimension_key,
                             bucket_start, source_service, source_reference)
                do update set value = excluded.value,
                              quantity = excluded.quantity,
                              updated_at = now()
                """,
                Ids.next(),
                tenantId,
                fact.metricCode(),
                fact.currency(),
                fact.dimensionType(),
                fact.dimensionKey(),
                fact.bucketStart(),
                fact.value(),
                fact.quantity(),
                fact.sourceService(),
                fact.sourceReference());
            count++;
          }
          for (var drilldown : drilldowns) {
            // ⚠️ **on conflict do nothing**：构成明细表是**共用**的
            //   （13.1.1.1 也会写同一批订单的行），重复写不报错、也不产生第二份。
            // ⚠️ 冲突目标必须**逐字复刻**唯一索引里的表达式索引
            //   （coalesce(legal_entity_id, 零 uuid)）—— 写成裸列名 PG 认不出来。
            sql.execute(
                """
                insert into reporting_drilldown_fact
                  (id,tenant_id,scope_type,legal_entity_id,shop_id,metric_code,bucket_date,currency,
                   source_kind,reference_id,reference_label,quantity,value,source_service,
                   source_reference,updated_at)
                values (?,?,'COMPANY',null,null,?,?,?,?,?,?,?,?,'trade',?,now())
                on conflict (tenant_id, scope_type,
                    coalesce(legal_entity_id, '00000000-0000-0000-0000-000000000000'::uuid),
                    coalesce(shop_id, '00000000-0000-0000-0000-000000000000'::uuid),
                    metric_code, bucket_date, currency, source_kind, reference_id,
                    source_service, source_reference)
                do nothing
                """,
                Ids.next(),
                tenantId,
                drilldown.metricCode(),
                drilldown.bucketDate(),
                drilldown.currency(),
                drilldown.sourceKind(),
                drilldown.referenceId(),
                drilldown.referenceLabel(),
                drilldown.quantity(),
                drilldown.value(),
                drilldown.sourceReference());
            count++;
          }
          return count;
        });
  }

  /**
   * 事件 upsert（**先按来源清空再写入**：快照变了，旧事件不能留着冒充现状）。
   *
   * <p>⚠️ 清空范围限定在**同一个来源**（{@code source_service}）——
   * 别的来源的事件不受影响。
   */
  private int projectEvents(UUID tenantId, List<Event> events, String sourceService) {
    return tenants.run(
        tenantId,
        () -> {
          sql.execute(
              "delete from reporting_sales_event where tenant_id = ? and source_service = ?",
              tenantId,
              sourceService);
          var count = 0;
          for (var event : events) {
            sql.execute(
                """
                insert into reporting_sales_event
                  (id,tenant_id,event_code,event_type,title,detail,event_date,end_date,
                   start_at,end_at,shop_code,sku_code,severity,source_service,source_reference,
                   created_at,updated_at)
                values (?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,?,?,?,?,now(),now())
                on conflict (tenant_id, event_type, event_code) do update
                  set title = excluded.title,
                      detail = excluded.detail,
                      event_date = excluded.event_date,
                      end_date = excluded.end_date,
                      start_at = excluded.start_at,
                      end_at = excluded.end_at,
                      shop_code = excluded.shop_code,
                      sku_code = excluded.sku_code,
                      severity = excluded.severity,
                      source_reference = excluded.source_reference,
                      updated_at = now()
                """,
                Ids.next(),
                tenantId,
                event.eventCode(),
                event.eventType(),
                event.title(),
                event.detail(),
                event.eventDate(),
                event.endDate(),
                event.startAt(),
                event.endAt(),
                event.shopCode(),
                event.skuCode(),
                event.severity(),
                event.sourceService(),
                event.sourceReference());
            count++;
          }
          return count;
        });
  }

  /**
   * 确保该租户有标准口径（**幂等**）。
   *
   * <p>⚠️ **公司维度的三个基础口径沿用 13.1.1.1 的口径表**
   * （{@code reporting_metric_definition}，内容唯一来源 {@link ReportingMetricCatalog#STANDARD}）：
   * 本模块**引用**它们，**不另起一份定义** —— 同一个数只能有一个口径真相。
   * 这里用 {@code on conflict do nothing} 播种，与 13.1.1.1 的播种幂等一致。
   */
  void seedDefinitions(UUID tenantId) {
    for (var definition : ReportingMetricCatalog.STANDARD) {
      sql.execute(
          """
          insert into reporting_metric_definition
            (id,tenant_id,metric_code,display_name,unit,aggregation,numerator_metric_code,
             denominator_metric_code,currency_aware,description,owner_module,enabled,created_at,updated_at)
          values (?,?,?,?,?,?,?,?,?,?,?,true,now(),now())
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
          definition.currencyAware(),
          definition.description(),
          definition.ownerModule());
    }
    for (var definition : ReportingSalesTrendCatalog.STANDARD) {
      sql.execute(
          """
          insert into reporting_sales_metric_definition
            (id,tenant_id,metric_code,display_name,unit,aggregation,dimension_type,base_metric_code,
             currency_aware,description,owner_module,enabled,created_at,updated_at)
          values (?,?,?,?,?,?,?,?,?,?,?,true,now(),now())
          on conflict (tenant_id, metric_code) do nothing
          """,
          Ids.next(),
          tenantId,
          definition.metricCode(),
          definition.displayName(),
          definition.unit().name(),
          definition.aggregation().name(),
          definition.dimensionType().name(),
          definition.baseMetricCode(),
          definition.currencyAware(),
          definition.description(),
          definition.ownerModule());
    }
  }

  /** 供查询侧复用：把窗口长度换算成天数（校验用）。 */
  static int windowDays(LocalDate from, LocalDate to) {
    return (int) ChronoUnit.DAYS.between(from, to) + 1;
  }

  /** 供查询侧复用：桶的本地键。 */
  static LocalDateTime bucketKeyOf(
      ReportingSalesTrendGranularity granularity, LocalDateTime local) {
    return granularity.truncate(local);
  }
}
