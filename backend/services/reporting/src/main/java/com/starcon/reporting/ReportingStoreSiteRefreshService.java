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
 * 13.2.2.1 店铺与站点的**拉取适配器**（上游授权只读 API → 店铺/站点档案 + 店铺维度投影事实）。
 *
 * <p>⚠️ <b>本类只写投影</b>（{@code reporting_store_site_profile} /
 * {@code reporting_store_site_metric_fact} / {@code reporting_store_site_drilldown_fact}），
 * 不产生任何业务事实 —— 成交金额与销量仍然在 trade，店铺与站点归属仍然在 channel。
 *
 * <p><b>🔴 三条纪律</b>：
 *
 * <ol>
 *   <li><b>幂等</b>：事实行按「店铺 + 指标 + 币种 + 时刻 + 来源服务 + 上游行」唯一 ⇒
 *       重复刷新是**覆盖**而不是翻倍；
 *   <li><b>口径唯一</b>：指标码必须已登记（本模块的口径表），DB 触发器钉死 ⇒
 *       事实不能自己发明指标码；
 *   <li><b>读不到 ≠ 没有数据</b>：上游故障一律翻成失败（{@code FAILED}），**不用空集合冒充**。
 * </ol>
 *
 * <p><b>🔴 为什么一次刷新要投两个窗口</b>：本模块的核心输出是**增长贡献度**
 * （谁的增量算在总增量里）⇒ 基期必须也有事实，否则贡献度**算不出来**（返回 null，不是 0%）。
 * ⇒ 一次刷新把「当前窗口 + 上一等长窗口」一起投掉。
 *
 * <p><b>🔴 为什么取数窗口要留余量</b>：事实存的是**精确时刻**，而查询期按**调用方指定的时区**
 * 把日期窗口换算成瞬间区间。取数时多取一天（{@link #MARGIN_DAYS}），
 * 任何时区（UTC−12 … UTC+14）的边界订单都能落进投影 ⇒ 换时区不需要重新投影。
 */
@Service
public class ReportingStoreSiteRefreshService {

  /** 当前窗口最多多少天（含边界）。 */
  static final int MAX_WINDOW_DAYS = 92;

  /**
   * 取数窗口两侧多留几天（**时区余量**）。
   *
   * <p>⚠️ 事实存精确时刻，查询期按调用方时区换算窗口；不多取一天的话，
   * 东八区窗口的第一天会比投影窗口早 8 小时 ⇒ 那一天的订单会**静默少算**。
   */
  static final int MARGIN_DAYS = 1;

  /**
   * 一个窗口最多取多少张订单的**明细行**（销量只存在于明细里 ⇒ 必须逐单取）。
   *
   * <p>⚠️ 触到上限时**如实报 truncated**（不悄悄少算），并把明细覆盖率报给查询侧。
   */
  static final int MAX_DETAIL_FETCHES_PER_WINDOW = 200;

  private final ReportingTradePort trade;
  private final ReportingSalesTrendPort salesTrendPort;
  private final ReportingStoreSitePort port;
  private final DSLContext sql;
  private final TenantTransactions tenants;

  public ReportingStoreSiteRefreshService(
      ReportingTradePort trade,
      ReportingSalesTrendPort salesTrendPort,
      ReportingStoreSitePort port,
      DSLContext sql,
      TenantTransactions tenants) {
    this.trade = trade;
    this.salesTrendPort = salesTrendPort;
    this.port = port;
    this.sql = sql;
    this.tenants = tenants;
  }

  /** 一个投影窗口。 */
  public record Window(String name, LocalDate from, LocalDate to) {}

  /** 上一等长窗口（[from, to] 往前挪一整个窗口长度）。 */
  public static Window previousWindow(LocalDate from, LocalDate to) {
    var days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
    return new Window("base", from.minusDays(days), from.minusDays(1));
  }

  // ==========================================================================
  // 刷新
  // ==========================================================================

  /**
   * 刷新店铺/站点档案与店铺维度投影事实。
   *
   * @param from 当前窗口起始日
   * @param to 当前窗口结束日
   */
  public List<ReportingStoreSiteModels.StoreSiteRefreshResult> refresh(
      UUID tenantId, String bearer, LocalDate from, LocalDate to) {
    var results = new ArrayList<ReportingStoreSiteModels.StoreSiteRefreshResult>();
    var base = previousWindow(from, to);
    results.add(refreshProfiles(tenantId, bearer));
    results.add(refreshFacts(tenantId, bearer, from, to, "current"));
    results.add(refreshFacts(tenantId, bearer, base.from(), base.to(), "base"));
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
          "店铺与站点投影失败（上游读不到，不是「没有数据」）：" + String.join("；", failed));
    }
    return results;
  }

  // ==========================================================================
  // 店铺 / 站点 / 平台档案
  // ==========================================================================

  /**
   * 刷新档案：平台与站点目录（channel）+ 店铺清单（channel）+ 每个店铺启用的站点（channel）。
   *
   * <p>🔴 **「店铺的销量能不能唯一落到某个站点」由这里决定**：
   * 订单不带站点，站点归属只来自 {@code shop_marketplace} ⇒
   * 启用 1 个站点 ⇒ UNIQUE；≥2 个 ⇒ AMBIGUOUS；0 个 ⇒ NONE。
   *
   * <p>⚠️ **档案是全量投影** ⇒ 写之前先删掉该租户的旧档案（写之前先把上游全部取到手，
   * 取失败就整轮失败，**不会**删成半张表）。
   */
  private ReportingStoreSiteModels.StoreSiteRefreshResult refreshProfiles(
      UUID tenantId, String bearer) {
    List<ReportingStoreSitePort.PlatformRow> platforms;
    List<ReportingStoreSitePort.ShopRow> shops;
    try {
      platforms = port.platforms(bearer);
      shops = port.shops(bearer);
    } catch (org.springframework.web.server.ResponseStatusException e) {
      return new ReportingStoreSiteModels.StoreSiteRefreshResult(
          "channel-shops", 0, 0, "FAILED", e.getReason() == null ? "上游读不到" : e.getReason());
    }
    if (platforms == null) platforms = List.of();
    if (shops == null) shops = List.of();

    // 站点目录：平台码 → 站点码 → 站点参数（币种 / 时区 / 国家）。
    var siteCatalog = new LinkedHashMap<String, Map<String, ReportingStoreSitePort.SiteRow>>();
    for (var platform : platforms) {
      if (platform == null || platform.code() == null) continue;
      var sites = new LinkedHashMap<String, ReportingStoreSitePort.SiteRow>();
      if (platform.marketplaces() != null) {
        for (var site : platform.marketplaces()) {
          if (site == null || site.code() == null) continue;
          sites.put(site.code().trim(), site);
        }
      }
      siteCatalog.put(platform.code().trim(), sites);
    }

    // 逐店取详情（店铺列表端点不带站点）。
    var rows = new ArrayList<ProfileRow>();
    int fetched = 0;
    for (var shop : shops) {
      if (shop == null || shop.code() == null || shop.code().isBlank()) continue;
      fetched++;
      ReportingStoreSitePort.ShopDetailRow detail;
      try {
        detail = port.shop(shop.id(), bearer);
      } catch (org.springframework.web.server.ResponseStatusException e) {
        return new ReportingStoreSiteModels.StoreSiteRefreshResult(
            "channel-shop-detail",
            fetched,
            0,
            "FAILED",
            "店铺 " + shop.code() + " 的详情读不到：" + (e.getReason() == null ? "上游读不到" : e.getReason()));
      }
      var shopCode = shop.code().trim();
      var platformCode = shop.platformCode() == null ? "" : shop.platformCode().trim();
      var platformName = shop.platformName() == null ? "" : shop.platformName().trim();
      var siteRows = detail == null || detail.marketplaces() == null ? List.<ReportingStoreSitePort.ShopSiteRow>of()
          : detail.marketplaces();
      var active = new ArrayList<ReportingStoreSitePort.ShopSiteRow>();
      for (var site : siteRows) {
        if (site == null || site.marketplaceCode() == null || site.marketplaceCode().isBlank()) continue;
        if (site.status() != null && "ACTIVE".equalsIgnoreCase(site.status())) active.add(site);
      }
      if (active.isEmpty()) {
        rows.add(
            new ProfileRow(
                shopCode, shop.name(), platformCode, platformName, "", "", "", "", "", "", "NONE", null));
        continue;
      }
      var attribution = active.size() == 1 ? "UNIQUE" : "AMBIGUOUS";
      for (var site : active) {
        var siteCode = site.marketplaceCode().trim();
        var info = siteCatalog.getOrDefault(platformCode, Map.of()).get(siteCode);
        rows.add(
            new ProfileRow(
                shopCode,
                shop.name(),
                platformCode,
                platformName,
                siteCode,
                site.marketplaceName() == null ? "" : site.marketplaceName(),
                info == null ? "" : nullToEmpty(info.countryCode()),
                info == null ? "" : nullToEmpty(info.defaultCurrency()),
                info == null ? "" : nullToEmpty(info.defaultTimezone()),
                site.status() == null ? "" : site.status(),
                attribution,
                site.enabledAt() == null ? null : site.enabledAt().atOffset(ZoneOffset.UTC)));
      }
    }

    var projected = tenants.run(tenantId, () -> writeProfiles(tenantId, rows));
    return new ReportingStoreSiteModels.StoreSiteRefreshResult(
        "channel-shops",
        fetched,
        projected,
        "SUCCEEDED",
        "已投影 " + fetched + " 个店铺的站点/平台档案（其中 "
            + rows.stream().filter(r -> "NONE".equals(r.attributionStatus())).count()
            + " 个店铺没有启用站点，销量归不了任何站点）。");
  }

  /** 一行档案（写库前的内存形态）。 */
  private record ProfileRow(
      String shopCode,
      String shopName,
      String platformCode,
      String platformName,
      String siteCode,
      String siteName,
      String siteCountryCode,
      String siteCurrency,
      String siteTimezone,
      String siteStatus,
      String attributionStatus,
      OffsetDateTime siteEnabledAt) {}

  /** 写档案（全量替换）。 */
  private int writeProfiles(UUID tenantId, List<ProfileRow> rows) {
    sql.execute("delete from reporting_store_site_profile where tenant_id = ?", tenantId);
    var count = 0;
    for (var row : rows) {
      sql.execute(
          """
          insert into reporting_store_site_profile
            (id,tenant_id,shop_code,shop_name,platform_code,platform_name,site_code,site_name,
             site_country_code,site_currency,site_timezone,site_status,site_enabled_at,
             attribution_status,source_service,source_reference,created_at,updated_at)
          values (?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?,'channel','shop:' || ?,now(),now())
          on conflict (tenant_id, shop_code, site_code)
          do update set shop_name = excluded.shop_name,
                        platform_code = excluded.platform_code,
                        platform_name = excluded.platform_name,
                        site_name = excluded.site_name,
                        site_country_code = excluded.site_country_code,
                        site_currency = excluded.site_currency,
                        site_timezone = excluded.site_timezone,
                        site_status = excluded.site_status,
                        site_enabled_at = excluded.site_enabled_at,
                        attribution_status = excluded.attribution_status,
                        updated_at = now()
          """,
          Ids.next(),
          tenantId,
          row.shopCode(),
          nullToEmpty(row.shopName()),
          row.platformCode(),
          nullToEmpty(row.platformName()),
          row.siteCode(),
          nullToEmpty(row.siteName()),
          row.siteCountryCode(),
          row.siteCurrency(),
          row.siteTimezone(),
          row.siteStatus(),
          row.siteEnabledAt(),
          row.attributionStatus(),
          row.shopCode());
      count++;
    }
    return count;
  }

  // ==========================================================================
  // 店铺维度事实
  // ==========================================================================

  /**
   * 一个窗口的销售订单 → 店铺维度事实 + 构成明细。
   *
   * <p>⚠️ **金额与订单量全量，销量按明细上限截断** —— 销量只存在于订单明细里，
   * 而明细要逐单取（有上限）。截断**如实报出**，不悄悄少算。
   */
  private ReportingStoreSiteModels.StoreSiteRefreshResult refreshFacts(
      UUID tenantId, String bearer, LocalDate from, LocalDate to, String windowName) {
    var facts = new ArrayList<Fact>();
    var drilldowns = new ArrayList<Drilldown>();
    var attribution = fetchStoreAttribution(bearer);
    if (attribution.failure() != null) {
      return new ReportingStoreSiteModels.StoreSiteRefreshResult(
          "trade-platform-orders",
          0,
          0,
          "FAILED",
          attribution.failure());
    }
    var fetchFrom = from.minusDays(MARGIN_DAYS);
    var fetchTo = to.plusDays(MARGIN_DAYS);
    int fetched = 0;
    int detailFetches = 0;
    int withDetail = 0;
    boolean truncated = false;
    int unattributed = 0;
    int missingCurrency = 0;
    try {
      for (int page = 0; page < ReportingTradePort.MAX_PAGES; page++) {
        var pageData = trade.salesOrders(fetchFrom, fetchTo, page, bearer);
        if (pageData == null || pageData.rows() == null || pageData.rows().isEmpty()) break;
        for (var row : pageData.rows()) {
          fetched++;
          if (row.submittedAt() == null) continue;
          var currency = row.currency() == null ? "" : row.currency().trim().toUpperCase();
          // 🔴 **币种缺失的订单不计入任何币种**：算进某个币种是编造（DB 的 currency 列也只收 3 位大写码）。
          if (!currency.matches("^[A-Z]{3}$")) {
            missingCurrency++;
            continue;
          }
          var shopCode = row.id() == null ? null : attribution.mapping().get(row.id());
          if (shopCode == null || shopCode.isBlank()) {
            // ⚠️ **挂不上店铺的订单不计入任何店铺**（平台单缺 sales_order_id 关联时）。
            unattributed++;
            continue;
          }
          var shop = shopCode.trim();
          var reference = row.orderNo() == null || row.orderNo().isBlank() ? null : row.orderNo();
          if (reference == null) continue;
          var amount = row.grandTotalAmount() == null ? BigDecimal.ZERO : row.grandTotalAmount();

          facts.add(
              new Fact(
                  shop,
                  ReportingStoreSiteCatalog.STORE_SITE_SALES_AMOUNT,
                  currency,
                  row.submittedAt(),
                  amount,
                  BigDecimal.ZERO,
                  "trade",
                  "sales-order:" + reference));
          facts.add(
              new Fact(
                  shop,
                  ReportingStoreSiteCatalog.STORE_SITE_ORDER_COUNT,
                  currency,
                  row.submittedAt(),
                  BigDecimal.ONE,
                  BigDecimal.ZERO,
                  "trade",
                  "sales-order:" + reference));
          drilldowns.add(
              new Drilldown(
                  shop,
                  ReportingStoreSiteCatalog.STORE_SITE_SALES_AMOUNT,
                  currency,
                  row.submittedAt(),
                  "ORDER",
                  reference,
                  reference,
                  reference,
                  row.submittedAt(),
                  "",
                  "",
                  BigDecimal.ZERO,
                  amount));

          // 销量只在**订单明细**里 ⇒ 逐单取（有上限）。
          if (detailFetches < MAX_DETAIL_FETCHES_PER_WINDOW && row.id() != null) {
            detailFetches++;
            var detail = trade.salesOrderDetail(row.id(), bearer);
            if (detail != null && detail.lines() != null && !detail.lines().isEmpty()) {
              withDetail++;
              var total = BigDecimal.ZERO;
              for (var line : detail.lines()) {
                if (line.quantity() == null) continue;
                total = total.add(line.quantity());
                var skuCode = line.skuCode() == null ? "" : line.skuCode();
                drilldowns.add(
                    new Drilldown(
                        shop,
                        ReportingStoreSiteCatalog.STORE_SITE_SALES_QUANTITY,
                        currency,
                        row.submittedAt(),
                        "ORDER_LINE",
                        reference + "#" + skuCode,
                        reference + " · " + (line.skuName() == null ? skuCode : line.skuName()),
                        reference,
                        row.submittedAt(),
                        skuCode,
                        line.skuName() == null ? "" : line.skuName(),
                        line.quantity(),
                        line.lineAmount() == null ? BigDecimal.ZERO : line.lineAmount()));
              }
              facts.add(
                  new Fact(
                      shop,
                      ReportingStoreSiteCatalog.STORE_SITE_SALES_QUANTITY,
                      currency,
                      row.submittedAt(),
                      BigDecimal.ZERO,
                      total,
                      "trade",
                      "sales-order:" + reference));
            }
          } else if (row.id() != null) {
            truncated = true;
          }
        }
        if (pageData.rows().size() < ReportingTradePort.PAGE_SIZE) break;
      }
    } catch (org.springframework.web.server.ResponseStatusException e) {
      return new ReportingStoreSiteModels.StoreSiteRefreshResult(
          "trade-orders:" + windowName,
          fetched,
          0,
          "FAILED",
          e.getReason() == null ? "上游读不到" : e.getReason());
    }
    var orderTruncated = fetched >= ReportingTradePort.PAGE_SIZE * ReportingTradePort.MAX_PAGES;
    var projected = tenants.run(tenantId, () -> writeFacts(tenantId, facts, drilldowns));
    var note = new StringBuilder();
    note.append("窗口 ")
        .append(from)
        .append(" ~ ")
        .append(to)
        .append("：订单 ")
        .append(fetched)
        .append(" 张，其中 ")
        .append(unattributed)
        .append(" 张挂不上店铺（不计入任何店铺）");
    if (missingCurrency > 0) {
      note.append("，").append(missingCurrency).append(" 张币种缺失（不计入任何币种）");
    }
    note.append("；销量明细覆盖 ").append(withDetail).append(" 张订单");
    if (truncated) {
      note.append(
          " ⚠️ 订单明细取到 ")
          .append(MAX_DETAIL_FETCHES_PER_WINDOW)
          .append(" 张为止（超过部分**未计入销量**）");
    }
    if (orderTruncated) {
      note.append(" ⚠️ 订单超过单次上限，超出部分**未计入**");
    }
    return new ReportingStoreSiteModels.StoreSiteRefreshResult(
        "trade-orders:" + windowName,
        fetched,
        projected,
        (truncated || orderTruncated) ? "TRUNCATED" : "SUCCEEDED",
        note.toString());
  }

  /** 店铺归属的拉取结果。 */
  private record Attribution(Map<UUID, String> mapping, String failure) {}

  /**
   * 销售订单 → 店铺的归属表（平台单回挂）。
   *
   * <p>⚠️ **销售订单本身没有店铺字段** —— 唯一来源是 trade 的平台单
   * （{@code platform_order.sales_order_id}）。挂不上的订单**不计入任何店铺**。
   */
  private Attribution fetchStoreAttribution(String bearer) {
    var map = new LinkedHashMap<UUID, String>();
    try {
      for (int page = 0; page < ReportingSalesTrendPort.MAX_PAGES; page++) {
        var pageData = salesTrendPort.platformOrders(page, bearer);
        if (pageData == null || pageData.rows() == null || pageData.rows().isEmpty()) break;
        for (var row : pageData.rows()) {
          if (row.salesOrderId() == null || row.shopCode() == null || row.shopCode().isBlank()) continue;
          map.put(row.salesOrderId(), row.shopCode().trim());
        }
        if (pageData.rows().size() < ReportingSalesTrendPort.PAGE_SIZE) break;
      }
    } catch (org.springframework.web.server.ResponseStatusException e) {
      return new Attribution(Map.of(), e.getReason() == null ? "上游读不到" : e.getReason());
    }
    return new Attribution(map, null);
  }

  /** 一条事实（写库前的内存形态）。 */
  private record Fact(
      String shopCode,
      String metricCode,
      String currency,
      OffsetDateTime bucketStart,
      BigDecimal value,
      BigDecimal quantity,
      String sourceService,
      String sourceReference) {}

  /** 一条构成明细（写库前的内存形态）。 */
  private record Drilldown(
      String shopCode,
      String metricCode,
      String currency,
      OffsetDateTime bucketStart,
      String sourceKind,
      String referenceId,
      String referenceLabel,
      String orderNo,
      OffsetDateTime occurredAt,
      String skuCode,
      String skuName,
      BigDecimal quantity,
      BigDecimal value) {}

  /** 写事实与构成明细（幂等 upsert）。 */
  private int writeFacts(UUID tenantId, List<Fact> facts, List<Drilldown> drilldowns) {
    // ⚠️ 口径必须先播种：事实行的指标码必须已登记（DB 触发器钉死）。
    //   没有这一句，**第一次刷新**就会在触发器上被拒（口径表是空的）⇒ 新租户完全用不了。
    seedDefinitions(tenantId);
    var count = 0;
    for (var fact : facts) {
      sql.execute(
          """
          insert into reporting_store_site_metric_fact
            (id,tenant_id,shop_code,metric_code,bucket_start,currency,value,quantity,
             source_service,source_reference,updated_at)
          values (?,?,?,?,?::timestamptz,?,?,?,?,?,now())
          on conflict (tenant_id, shop_code, metric_code, bucket_start, currency,
                       source_service, source_reference)
          do update set value = excluded.value, quantity = excluded.quantity, updated_at = now()
          """,
          Ids.next(),
          tenantId,
          fact.shopCode(),
          fact.metricCode(),
          fact.bucketStart(),
          fact.currency(),
          fact.value(),
          fact.quantity(),
          fact.sourceService(),
          fact.sourceReference());
      count++;
    }
    for (var drilldown : drilldowns) {
      sql.execute(
          """
          insert into reporting_store_site_drilldown_fact
            (id,tenant_id,shop_code,metric_code,bucket_start,currency,source_kind,reference_id,
             reference_label,order_no,occurred_at,sku_code,sku_name,quantity,value,
             source_service,source_reference,updated_at)
          values (?,?,?,?,?::timestamptz,?,?,?,?,?,?::timestamptz,?,?,?,?,?,?,now())
          on conflict (tenant_id, shop_code, metric_code, bucket_start, currency,
                       source_kind, reference_id, source_service, source_reference)
          do update set reference_label = excluded.reference_label,
                        occurred_at = excluded.occurred_at,
                        sku_code = excluded.sku_code,
                        sku_name = excluded.sku_name,
                        quantity = excluded.quantity,
                        value = excluded.value,
                        updated_at = now()
          """,
          Ids.next(),
          tenantId,
          drilldown.shopCode(),
          drilldown.metricCode(),
          drilldown.bucketStart(),
          drilldown.currency(),
          drilldown.sourceKind(),
          drilldown.referenceId(),
          drilldown.referenceLabel(),
          drilldown.orderNo(),
          drilldown.occurredAt(),
          drilldown.skuCode(),
          drilldown.skuName(),
          drilldown.quantity(),
          drilldown.value(),
          "trade",
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
   * <p>⚠️ 与 13.1.1.1 / 13.1.1.2 / 13.2.1.1 / 13.2.2.2 同一纪律：口径是**租户自己**的标准口径，
   * 第一次用到这个租户时按标准份播种一份；租户改了口径，播种**不会**覆盖它。
   */
  void seedDefinitions(UUID tenantId) {
    var exists =
        sql.fetch(
            "select 1 from reporting_store_site_metric_definition where tenant_id = ? limit 1",
            tenantId);
    if (!exists.isEmpty()) return;
    for (var definition : ReportingStoreSiteCatalog.STANDARD) {
      sql.execute(
          """
          insert into reporting_store_site_metric_definition
            (id,tenant_id,metric_code,display_name,unit,aggregation,base_metric_code,
             currency_aware,description,owner_module,enabled,created_at,updated_at)
          values (?,?,?,?,?,?,?,?,?,?,true,now(),now())
          on conflict (tenant_id, metric_code) do nothing
          """,
          Ids.next(),
          tenantId,
          definition.metricCode(),
          definition.displayName(),
          definition.unit().name(),
          definition.aggregation().name(),
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

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
