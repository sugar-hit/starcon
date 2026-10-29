package com.starcon.reporting;

import com.starcon.tenancy.Ids;
import com.starcon.tenancy.TenantTransactions;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

/**
 * 13.3.2.1 流量转化的**拉取适配器**（上游授权只读 API → 流量/转化投影事实 + 构成明细）。
 *
 * <p>⚠️ <b>本类只写投影</b>（{@code reporting_traffic_conversion_fact} /
 * {@code reporting_traffic_conversion_drilldown_fact}），不产生任何业务事实 ——
 * 平台流量数据仍然在 channel（2 同步的平台表现、14 广告的活动报表），
 * 内部订单仍然在 trade（9 订单）。
 *
 * <p><b>🔴 三条纪律</b>：
 *
 * <ol>
 *   <li><b>口径唯一</b>：指标码必须已登记（本模块的口径表），DB 触发器钉死 ⇒
 *       事实不能自己发明指标码；
 *   <li><b>两套口径不许混算</b>：每条事实行都带 {@code traffic_source}（LISTING / AD / ORDER）、
 *       {@code bucket_basis}（PLATFORM_DAY / UTC_DAY）与币种形状 ⇒ 两套口径在数据层就分得开；
 *   <li><b>读不到 ≠ 没有数据</b>：上游故障一律翻成失败（{@code FAILED}），**不用空集合冒充**。
 * </ol>
 *
 * <p><b>🔴 窗口是「重建」不是「叠加」</b>：刷新会先删掉该窗口内的旧事实与明细，再整段重写 ——
 * 平台修正了同一天的报表时，旧行必须**消失**而不是留着两份。
 * 删与写在同一个租户事务里 ⇒ 不会留下半张表。
 */
@Service
public class ReportingTrafficConversionRefreshService {

  /** 当前窗口最多多少天（含边界）。 */
  static final int MAX_WINDOW_DAYS = 92;

  private final ReportingTrafficConversionPort port;
  private final ReportingTradePort trade;
  private final DSLContext sql;
  private final TenantTransactions tenants;

  public ReportingTrafficConversionRefreshService(
      ReportingTrafficConversionPort port,
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
   * 刷新流量与转化投影。
   *
   * @param from 当前窗口起始日
   * @param to 当前窗口结束日
   */
  public List<ReportingTrafficConversionModels.TrafficRefreshResult> refresh(
      UUID tenantId, String bearer, LocalDate from, LocalDate to) {
    var results = new ArrayList<ReportingTrafficConversionModels.TrafficRefreshResult>();
    results.add(refreshListing(tenantId, bearer, from, to));
    results.add(refreshAd(tenantId, bearer, from, to));
    results.add(refreshOrders(tenantId, bearer, from, to));

    // 🔴 **上游读不到 ⇒ 整个刷新失败（503）**：不能返回一份「看起来成功、其实少了一半」的投影。
    var failed = new ArrayList<String>();
    for (var result : results) {
      if ("FAILED".equals(result.status())) {
        failed.add(result.source() + "：" + result.note());
      }
    }
    if (!failed.isEmpty()) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "流量转化投影失败（上游读不到，不是「没有数据」）：" + String.join("；", failed));
    }
    return results;
  }

  // ==========================================================================
  // 2 同步：平台表现快照（**页面口径**）
  // ==========================================================================

  /** 平台表现 ⇒ 事实 + 构成明细。 */
  private ReportingTrafficConversionModels.TrafficRefreshResult refreshListing(
      UUID tenantId, String bearer, LocalDate from, LocalDate to) {
    var facts = new LinkedHashMap<String, Fact>();
    var drilldowns = new ArrayList<Drilldown>();
    int fetched = 0;
    int missingCurrency = 0;
    try {
      for (int page = 0; page < ReportingTrafficConversionPort.MAX_PAGES; page++) {
        var pageData = port.listingSnapshots(from, to, page, bearer);
        if (pageData == null || pageData.items() == null || pageData.items().isEmpty()) break;
        for (var row : pageData.items()) {
          if (row == null) continue;
          fetched++;
          var statDate = parseDate(row.statDate());
          if (statDate == null) continue;
          var shopCode = trim(row.shopCode());
          var platformCode = trim(row.platformCode());
          var reference = row.listingId() == null ? trim(row.externalItemId()) : row.listingId().toString();
          if (reference.isEmpty()) continue;

          // 🔴 **事实按币种分行存储**（与 13.2.2.2 同纪律）：件数与金额都跟着这一行的币种落库，
          //   这样「客单价 = 金额 ÷ 件数」能按**同币种**取数（分子分母口径一致）。
          //   ⚠️ 平台没给币种时：件数照落（件数与货币无关），**金额不落**（算进某个币种是编造）。
          var currency = currency(row.currency());

          accumulate(facts, "LISTING", ReportingTrafficConversionCatalog.IMPRESSIONS, statDate,
              currency, shopCode, platformCode, BigDecimal.ZERO, nz(row.impressions()));
          accumulate(facts, "LISTING", ReportingTrafficConversionCatalog.CLICKS, statDate,
              currency, shopCode, platformCode, BigDecimal.ZERO, nz(row.clicks()));
          accumulate(facts, "LISTING", ReportingTrafficConversionCatalog.SESSIONS, statDate,
              currency, shopCode, platformCode, BigDecimal.ZERO, nz(row.sessions()));
          accumulate(facts, "LISTING", ReportingTrafficConversionCatalog.PLATFORM_UNITS, statDate,
              currency, shopCode, platformCode, BigDecimal.ZERO, nz(row.unitsOrdered()));

          drilldowns.add(drilldown("LISTING", ReportingTrafficConversionCatalog.IMPRESSIONS, statDate,
              "PLATFORM_DAY", currency, "LISTING", reference, "", shopCode, platformCode, "",
              BigDecimal.ZERO, nz(row.impressions())));
          drilldowns.add(drilldown("LISTING", ReportingTrafficConversionCatalog.CLICKS, statDate,
              "PLATFORM_DAY", currency, "LISTING", reference, "", shopCode, platformCode, "",
              BigDecimal.ZERO, nz(row.clicks())));
          drilldowns.add(drilldown("LISTING", ReportingTrafficConversionCatalog.SESSIONS, statDate,
              "PLATFORM_DAY", currency, "LISTING", reference, "", shopCode, platformCode, "",
              BigDecimal.ZERO, nz(row.sessions())));
          drilldowns.add(drilldown("LISTING", ReportingTrafficConversionCatalog.PLATFORM_UNITS, statDate,
              "PLATFORM_DAY", currency, "LISTING", reference, "", shopCode, platformCode, "",
              BigDecimal.ZERO, nz(row.unitsOrdered())));

          if (currency.isEmpty()) {
            if (nz(row.salesAmount()).signum() != 0) missingCurrency++;
          } else {
            accumulate(facts, "LISTING", ReportingTrafficConversionCatalog.PLATFORM_SALES_AMOUNT, statDate,
                currency, shopCode, platformCode, nz(row.salesAmount()), BigDecimal.ZERO);
            drilldowns.add(drilldown("LISTING", ReportingTrafficConversionCatalog.PLATFORM_SALES_AMOUNT, statDate,
                "PLATFORM_DAY", currency, "LISTING", reference, "", shopCode, platformCode, "",
                nz(row.salesAmount()), BigDecimal.ZERO));
          }
        }
        if (pageData.items().size() < ReportingTrafficConversionPort.PAGE_SIZE) break;
      }
    } catch (org.springframework.web.server.ResponseStatusException e) {
      return new ReportingTrafficConversionModels.TrafficRefreshResult(
          "channel-listing-performance",
          fetched,
          0,
          "FAILED",
          e.getReason() == null ? "上游读不到" : e.getReason());
    }
    var truncated = fetched >= ReportingTrafficConversionPort.PAGE_SIZE * ReportingTrafficConversionPort.MAX_PAGES;
    var projected = tenants.run(tenantId, () -> write(tenantId, "LISTING", from, to, facts, drilldowns));
    var note = new StringBuilder();
    note.append("窗口 ").append(from).append(" ~ ").append(to).append("：平台表现快照 ")
        .append(fetched).append(" 条（平台自然日口径）");
    if (missingCurrency > 0) {
      note.append("，").append(missingCurrency).append(" 条金额缺币种（**不落库**，不计入任何币种）");
    }
    if (truncated) {
      note.append(" ⚠️ 超过单次上限，超出部分**未计入**");
    }
    return new ReportingTrafficConversionModels.TrafficRefreshResult(
        "channel-listing-performance",
        fetched,
        projected,
        truncated ? "TRUNCATED" : "SUCCEEDED",
        note.toString());
  }

  // ==========================================================================
  // 14 广告：活动报表（**广告口径**）
  // ==========================================================================

  /** 广告报表 ⇒ 事实 + 构成明细。 */
  private ReportingTrafficConversionModels.TrafficRefreshResult refreshAd(
      UUID tenantId, String bearer, LocalDate from, LocalDate to) {
    var facts = new LinkedHashMap<String, Fact>();
    var drilldowns = new ArrayList<Drilldown>();
    int fetched = 0;
    int missingCurrency = 0;
    try {
      for (int page = 0; page < ReportingTrafficConversionPort.MAX_PAGES; page++) {
        var pageData = port.adRows(from, to, page, bearer);
        if (pageData == null || pageData.items() == null || pageData.items().isEmpty()) break;
        for (var row : pageData.items()) {
          if (row == null) continue;
          fetched++;
          // 🔴 粒度是 DAY ⇒ 一行对应一个活动的一天；metricFrom / metricTo 是这一天的范围。
          var statDate = row.metricTo() == null ? row.metricFrom() : row.metricTo();
          if (statDate == null) continue;
          var reference = trim(row.dimensionKey());
          if (reference.isEmpty()) reference = trim(row.externalCampaignId());
          if (reference.isEmpty()) continue;
          var label = trim(row.dimensionLabel());
          if (label.isEmpty()) label = trim(row.externalCampaignId());

          // 🔴 事实按币种分行存储（与 13.2.2.2 同纪律）⇒ 广告客单价能按同币种取数。
          var currency = currency(row.currency());

          accumulate(facts, "AD", ReportingTrafficConversionCatalog.AD_IMPRESSIONS, statDate,
              currency, "", "", BigDecimal.ZERO, nz(row.impressions()));
          accumulate(facts, "AD", ReportingTrafficConversionCatalog.AD_CLICKS, statDate,
              currency, "", "", BigDecimal.ZERO, nz(row.clicks()));
          accumulate(facts, "AD", ReportingTrafficConversionCatalog.AD_ORDERS, statDate,
              currency, "", "", BigDecimal.ZERO, nz(row.orders()));

          drilldowns.add(drilldown("AD", ReportingTrafficConversionCatalog.AD_IMPRESSIONS, statDate,
              "PLATFORM_DAY", currency, "AD_CAMPAIGN", reference, label, "", "", "",
              BigDecimal.ZERO, nz(row.impressions())));
          drilldowns.add(drilldown("AD", ReportingTrafficConversionCatalog.AD_CLICKS, statDate,
              "PLATFORM_DAY", currency, "AD_CAMPAIGN", reference, label, "", "", "",
              BigDecimal.ZERO, nz(row.clicks())));
          drilldowns.add(drilldown("AD", ReportingTrafficConversionCatalog.AD_ORDERS, statDate,
              "PLATFORM_DAY", currency, "AD_CAMPAIGN", reference, label, "", "", "",
              BigDecimal.ZERO, nz(row.orders())));

          if (currency.isEmpty()) {
            if (nz(row.spend()).signum() != 0 || nz(row.sales()).signum() != 0) missingCurrency++;
          } else {
            accumulate(facts, "AD", ReportingTrafficConversionCatalog.AD_SPEND, statDate,
                currency, "", "", nz(row.spend()), BigDecimal.ZERO);
            accumulate(facts, "AD", ReportingTrafficConversionCatalog.AD_SALES_AMOUNT, statDate,
                currency, "", "", nz(row.sales()), BigDecimal.ZERO);
            drilldowns.add(drilldown("AD", ReportingTrafficConversionCatalog.AD_SPEND, statDate,
                "PLATFORM_DAY", currency, "AD_CAMPAIGN", reference, label, "", "", "",
                nz(row.spend()), BigDecimal.ZERO));
            drilldowns.add(drilldown("AD", ReportingTrafficConversionCatalog.AD_SALES_AMOUNT, statDate,
                "PLATFORM_DAY", currency, "AD_CAMPAIGN", reference, label, "", "", "",
                nz(row.sales()), BigDecimal.ZERO));
          }
        }
        if (pageData.items().size() < ReportingTrafficConversionPort.PAGE_SIZE) break;
      }
    } catch (org.springframework.web.server.ResponseStatusException e) {
      return new ReportingTrafficConversionModels.TrafficRefreshResult(
          "channel-ad-reports",
          fetched,
          0,
          "FAILED",
          e.getReason() == null ? "上游读不到" : e.getReason());
    }
    var truncated = fetched >= ReportingTrafficConversionPort.PAGE_SIZE * ReportingTrafficConversionPort.MAX_PAGES;
    var projected = tenants.run(tenantId, () -> write(tenantId, "AD", from, to, facts, drilldowns));
    var note = new StringBuilder();
    note.append("窗口 ").append(from).append(" ~ ").append(to).append("：广告报表 ")
        .append(fetched).append(" 行（广告口径，平台自然日）");
    if (missingCurrency > 0) {
      note.append("，").append(missingCurrency).append(" 行金额缺币种（**不落库**）");
    }
    if (truncated) {
      note.append(" ⚠️ 超过单次上限，超出部分**未计入**");
    }
    return new ReportingTrafficConversionModels.TrafficRefreshResult(
        "channel-ad-reports", fetched, projected, truncated ? "TRUNCATED" : "SUCCEEDED", note.toString());
  }

  // ==========================================================================
  // 9 订单：内部销售订单（**订单口径**）
  // ==========================================================================

  /** 内部订单 ⇒ 事实 + 构成明细。 */
  private ReportingTrafficConversionModels.TrafficRefreshResult refreshOrders(
      UUID tenantId, String bearer, LocalDate from, LocalDate to) {
    var facts = new LinkedHashMap<String, Fact>();
    var drilldowns = new ArrayList<Drilldown>();
    int fetched = 0;
    int missingCurrency = 0;
    try {
      for (int page = 0; page < ReportingTradePort.MAX_PAGES; page++) {
        var pageData = trade.salesOrders(from, to, page, bearer);
        if (pageData == null || pageData.rows() == null || pageData.rows().isEmpty()) break;
        for (var row : pageData.rows()) {
          if (row == null) continue;
          fetched++;
          if (row.submittedAt() == null) continue;
          // 🔴 订单口径按 **UTC 自然日**切桶（与平台自然日**不是同一个日**）。
          var statDate = row.submittedAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
          var reference = trim(row.orderNo());
          if (reference.isEmpty()) continue;

          // 🔴 事实按币种分行存储（与 13.2.2.2 同纪律）⇒ 订单客单价能按同币种取数。
          var currency = currency(row.currency());

          accumulate(facts, "ORDER", ReportingTrafficConversionCatalog.ORDER_COUNT, statDate,
              currency, "", "", BigDecimal.ZERO, BigDecimal.ONE);
          drilldowns.add(drilldown("ORDER", ReportingTrafficConversionCatalog.ORDER_COUNT, statDate,
              "UTC_DAY", currency, "ORDER", reference, reference, "", "", reference,
              BigDecimal.ZERO, BigDecimal.ONE, row.submittedAt()));

          if (currency.isEmpty()) {
            // ⚠️ 笔数照落（件数与货币无关），**金额不落**（算进某个币种是编造）。
            missingCurrency++;
          } else {
            var amount = nz(row.grandTotalAmount());
            accumulate(facts, "ORDER", ReportingTrafficConversionCatalog.ORDER_AMOUNT, statDate,
                currency, "", "", amount, BigDecimal.ZERO);
            drilldowns.add(drilldown("ORDER", ReportingTrafficConversionCatalog.ORDER_AMOUNT, statDate,
                "UTC_DAY", currency, "ORDER", reference, reference, "", "", reference,
                amount, BigDecimal.ZERO, row.submittedAt()));
          }
        }
        if (pageData.rows().size() < ReportingTradePort.PAGE_SIZE) break;
      }
    } catch (org.springframework.web.server.ResponseStatusException e) {
      return new ReportingTrafficConversionModels.TrafficRefreshResult(
          "trade-sales-orders",
          fetched,
          0,
          "FAILED",
          e.getReason() == null ? "上游读不到" : e.getReason());
    }
    var truncated = fetched >= ReportingTradePort.PAGE_SIZE * ReportingTradePort.MAX_PAGES;
    var projected = tenants.run(tenantId, () -> write(tenantId, "ORDER", from, to, facts, drilldowns));
    var note = new StringBuilder();
    note.append("窗口 ").append(from).append(" ~ ").append(to).append("：内部订单 ")
        .append(fetched).append(" 张（订单口径，UTC 自然日）");
    if (missingCurrency > 0) {
      note.append("，").append(missingCurrency).append(" 张币种缺失（**不计入任何币种**）");
    }
    if (truncated) {
      note.append(" ⚠️ 超过单次上限，超出部分**未计入**");
    }
    return new ReportingTrafficConversionModels.TrafficRefreshResult(
        "trade-sales-orders", fetched, projected, truncated ? "TRUNCATED" : "SUCCEEDED", note.toString());
  }

  // ==========================================================================
  // 写库
  // ==========================================================================

  /** 一条事实（写库前的内存形态）。 */
  private record Fact(
      String trafficSource,
      String metricCode,
      LocalDate statDate,
      String currency,
      String shopCode,
      String platformCode,
      BigDecimal value,
      BigDecimal quantity) {}

  /** 一条构成明细（写库前的内存形态）。 */
  private record Drilldown(
      String trafficSource,
      String metricCode,
      LocalDate statDate,
      String bucketBasis,
      String currency,
      String sourceKind,
      String referenceId,
      String referenceLabel,
      String shopCode,
      String platformCode,
      String orderNo,
      BigDecimal value,
      BigDecimal quantity,
      OffsetDateTime occurredAt) {}

  /** 把一条贡献累加到当天的聚合里。 */
  private static void accumulate(
      Map<String, Fact> facts,
      String trafficSource,
      String metricCode,
      LocalDate statDate,
      String currency,
      String shopCode,
      String platformCode,
      BigDecimal value,
      BigDecimal quantity) {
    if (value.signum() == 0 && quantity.signum() == 0) return;
    var key = String.join("|", trafficSource, metricCode, statDate.toString(), currency, shopCode, platformCode);
    var existing = facts.get(key);
    if (existing == null) {
      facts.put(
          key,
          new Fact(trafficSource, metricCode, statDate, currency, shopCode, platformCode, value, quantity));
      return;
    }
    facts.put(
        key,
        new Fact(
            trafficSource,
            metricCode,
            statDate,
            currency,
            shopCode,
            platformCode,
            existing.value().add(value),
            existing.quantity().add(quantity)));
  }

  /** 造一条构成明细。 */
  private static Drilldown drilldown(
      String trafficSource,
      String metricCode,
      LocalDate statDate,
      String bucketBasis,
      String currency,
      String sourceKind,
      String referenceId,
      String referenceLabel,
      String shopCode,
      String platformCode,
      String orderNo,
      BigDecimal value,
      BigDecimal quantity) {
    return drilldown(
        trafficSource, metricCode, statDate, bucketBasis, currency, sourceKind, referenceId,
        referenceLabel, shopCode, platformCode, orderNo, value, quantity, null);
  }

  /** 造一条构成明细（带发生时刻）。 */
  private static Drilldown drilldown(
      String trafficSource,
      String metricCode,
      LocalDate statDate,
      String bucketBasis,
      String currency,
      String sourceKind,
      String referenceId,
      String referenceLabel,
      String shopCode,
      String platformCode,
      String orderNo,
      BigDecimal value,
      BigDecimal quantity,
      OffsetDateTime occurredAt) {
    return new Drilldown(
        trafficSource, metricCode, statDate, bucketBasis, currency, sourceKind, referenceId,
        referenceLabel, shopCode, platformCode, orderNo, value, quantity, occurredAt);
  }

  /**
   * 写事实与构成明细（**窗口重建**：先删该窗口旧行，再整段重写）。
   *
   * <p>⚠️ 口径必须先播种：事实行的指标码必须已登记（DB 触发器钉死）。
   *   没有这一句，**第一次刷新**就会在触发器上被拒（口径表是空的）⇒ 新租户完全用不了。
   */
  private int write(
      UUID tenantId,
      String trafficSource,
      LocalDate from,
      LocalDate to,
      Map<String, Fact> facts,
      List<Drilldown> drilldowns) {
    seedDefinitions(tenantId);
    sql.execute(
        "delete from reporting_traffic_conversion_drilldown_fact"
            + " where tenant_id = ? and traffic_source = ? and stat_date >= ? and stat_date <= ?",
        tenantId,
        trafficSource,
        from,
        to);
    sql.execute(
        "delete from reporting_traffic_conversion_fact"
            + " where tenant_id = ? and traffic_source = ? and stat_date >= ? and stat_date <= ?",
        tenantId,
        trafficSource,
        from,
        to);
    var count = 0;
    for (var fact : facts.values()) {
      var sourceService = "ORDER".equals(fact.trafficSource()) ? "trade" : "channel";
      sql.execute(
          """
          insert into reporting_traffic_conversion_fact
            (id,tenant_id,traffic_source,metric_code,stat_date,bucket_basis,currency,value,quantity,
             shop_code,platform_code,source_service,source_reference,updated_at)
          values (?,?,?,?,?,?,?,?,?,?,?,?,?,now())
          on conflict (tenant_id, traffic_source, metric_code, stat_date, currency,
                       shop_code, platform_code, source_service, source_reference)
          do update set value = excluded.value, quantity = excluded.quantity, updated_at = now()
          """,
          Ids.next(),
          tenantId,
          fact.trafficSource(),
          fact.metricCode(),
          fact.statDate(),
          "ORDER".equals(fact.trafficSource()) ? "UTC_DAY" : "PLATFORM_DAY",
          fact.currency(),
          fact.value(),
          fact.quantity(),
          fact.shopCode(),
          fact.platformCode(),
          sourceService,
          "daily");
      count++;
    }
    for (var drilldown : drilldowns) {
      var sourceService = "ORDER".equals(drilldown.trafficSource()) ? "trade" : "channel";
      sql.execute(
          """
          insert into reporting_traffic_conversion_drilldown_fact
            (id,tenant_id,traffic_source,metric_code,stat_date,bucket_basis,currency,source_kind,
             reference_id,reference_label,shop_code,platform_code,order_no,occurred_at,
             quantity,value,source_service,source_reference,updated_at)
          values (?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?,?,?,?,now())
          on conflict (tenant_id, traffic_source, metric_code, stat_date, currency,
                       source_kind, reference_id, source_service, source_reference)
          do update set reference_label = excluded.reference_label,
                        shop_code = excluded.shop_code,
                        platform_code = excluded.platform_code,
                        order_no = excluded.order_no,
                        occurred_at = excluded.occurred_at,
                        quantity = excluded.quantity,
                        value = excluded.value,
                        updated_at = now()
          """,
          Ids.next(),
          tenantId,
          drilldown.trafficSource(),
          drilldown.metricCode(),
          drilldown.statDate(),
          drilldown.bucketBasis(),
          drilldown.currency(),
          drilldown.sourceKind(),
          drilldown.referenceId(),
          drilldown.referenceLabel(),
          drilldown.shopCode(),
          drilldown.platformCode(),
          drilldown.orderNo(),
          drilldown.occurredAt(),
          drilldown.quantity(),
          drilldown.value(),
          sourceService,
          "drilldown:" + drilldown.referenceId());
      count++;
    }
    return count;
  }

  // ==========================================================================
  // 口径播种
  // ==========================================================================

  /**
   * 确保该租户有标准口径（**幂等**）。
   *
   * <p>⚠️ 与 13.1.1.1 / 13.2.1.1 / 13.2.2.1 / 13.2.2.2 同一纪律：口径是**租户自己**的标准口径，
   * 第一次用到这个租户时按标准份播种一份；租户改了口径，播种**不会**覆盖它。
   */
  void seedDefinitions(UUID tenantId) {
    var exists =
        sql.fetch(
            "select 1 from reporting_traffic_conversion_metric_definition where tenant_id = ? limit 1",
            tenantId);
    if (!exists.isEmpty()) return;
    for (var definition : ReportingTrafficConversionCatalog.STANDARD) {
      sql.execute(
          """
          insert into reporting_traffic_conversion_metric_definition
            (id,tenant_id,metric_code,display_name,unit,aggregation,numerator_metric_code,
             denominator_metric_code,caliber,traffic_source,currency_aware,description,owner_module,
             enabled,created_at,updated_at)
          values (?,?,?,?,?,?,?,?,?,?,?,?,?,true,now(),now())
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
          definition.caliber().name(),
          definition.trafficSource() == null ? null : definition.trafficSource().name(),
          definition.currencyAware(),
          definition.description(),
          definition.ownerModule());
    }
  }

  /** 供查询侧复用：把窗口长度换算成天数（校验用）。 */
  static int windowDays(LocalDate from, LocalDate to) {
    return (int) java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
  }

  // ==========================================================================
  // 小工具
  // ==========================================================================

  /** 解析平台统计日（上游给的是 {@code yyyy-MM-dd} 字符串）。 */
  static LocalDate parseDate(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return LocalDate.parse(value.trim());
    } catch (java.time.format.DateTimeParseException e) {
      return null;
    }
  }

  /** 币种：只收 3 位大写码；其它一律当「没有币种」（**不算进任何币种**）。 */
  static String currency(String value) {
    if (value == null) return "";
    var normalized = value.trim().toUpperCase();
    return normalized.matches("^[A-Z]{3}$") ? normalized : "";
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
