package com.starcon.reporting;

import com.starcon.tenancy.TenantTransactions;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

/**
 * 13.2.2.1 店铺与站点的**读模型**（蓝图「输入」句：维度选择；时间范围）。
 *
 * <p>🔴 **三个维度是同一棵树的三个高度**：平台 → 站点 → 店铺。
 * 事实只落在**店铺层**（见 V391 的说明），站点 / 平台维度在**查询期**按档案重分组 ——
 * 这样「同一个数三个真相」这种事不会发生。
 *
 * <p>🔴 **本类不重算口径**：口径（单位 / 聚合方式 / 被比较指标）全部来自
 * {@code reporting_store_site_metric_definition}，本类只**按定义聚合**。
 *
 * <p>⚠️ **「算不出来」与「等于 0」必须分开**：基期缺失、基期为 0、事实不存在，
 * 三种情况一律返回 {@code null} / {@code NO_DATA} / {@code NOT_COMPARABLE}，**不用 0 冒充**。
 *
 * <p>⚠️ **不跨币种相加**：金额按币种分别报；只有与货币无关的量（件数 / 单数）给跨币种合计，
 * 币种显示为空串 —— 空串 = 与币种无关，**不是「币种未知」**。
 */
@Service
public class ReportingStoreSiteService {

  /** 百分比的展示刻度（与 13.1.1.2 一致：-15.0000 表示 -15%）。 */
  private static final int PERCENT_SCALE = 4;

  /** 「算不出来」的占位说明。 */
  private static final String NO_DATA_NOTE = "这个窗口、这个币种下没有这个指标的事实数据。";

  private final DSLContext sql;
  private final TenantTransactions tenants;
  private final ReportingStoreSiteRefreshService refresh;

  public ReportingStoreSiteService(
      DSLContext sql, TenantTransactions tenants, ReportingStoreSiteRefreshService refresh) {
    this.sql = sql;
    this.tenants = tenants;
    this.refresh = refresh;
  }

  // ==========================================================================
  // 指标口径目录
  // ==========================================================================

  /** 读该租户的**店铺/站点指标口径目录**（口径唯一的展示形态）。 */
  public List<ReportingStoreSiteModels.StoreSiteMetricDefinitionView> metricDefinitions(
      UUID tenantId) {
    return tenants.run(
        tenantId,
        () -> {
          refresh.seedDefinitions(tenantId);
          var rows =
              sql.fetch(
                  """
                  select metric_code, display_name, unit, aggregation, base_metric_code,
                         currency_aware, description, owner_module
                    from reporting_store_site_metric_definition
                   where tenant_id = ? and enabled
                   order by metric_code
                  """,
                  tenantId);
          var out = new ArrayList<ReportingStoreSiteModels.StoreSiteMetricDefinitionView>();
          for (var row : rows) {
            out.add(
                new ReportingStoreSiteModels.StoreSiteMetricDefinitionView(
                    row.get(0, String.class),
                    row.get(1, String.class),
                    row.get(2, String.class),
                    row.get(3, String.class),
                    row.get(4, String.class),
                    Boolean.TRUE.equals(row.get(5, Boolean.class)),
                    row.get(6, String.class),
                    row.get(7, String.class)));
          }
          return out;
        });
  }

  // ==========================================================================
  // 分组报表
  // ==========================================================================

  /**
   * 店铺 / 站点 / 平台维度拆解：分组报表 + 占比 + 增长贡献 + 新老店 + 渠道占比。
   *
   * @param dimension SHOP / SITE / PLATFORM（调用方选的拆解维度）
   */
  public ReportingStoreSiteModels.StoreSiteResponse report(
      UUID tenantId, LocalDate from, LocalDate to, String dimension, int page, int size) {
    var base = ReportingStoreSiteRefreshService.previousWindow(from, to);
    var type = normalizeDimension(dimension);
    var zone = ZoneId.of("UTC");

    return tenants.run(
        tenantId,
        () -> {
          refresh.seedDefinitions(tenantId);
          var definitions = definitionMap(tenantId);
          var profiles = loadProfiles(tenantId);

          var current = loadFacts(tenantId, from, to, zone);
          var previous = loadFacts(tenantId, base.from(), base.to(), zone);

          // ---- 维度键（平台 / 站点 / 店铺 是同一棵树的三层）----
          var keys = dimensionKeys(type, profiles, current, previous);
          var attributable = new LinkedHashMap<String, DimValues>();
          for (var key : keys) {
            attributable.put(
                key.key(),
                new DimValues(
                    aggregate(current, key.shopCodes()), aggregate(previous, key.shopCodes())));
          }
          // 站点维度里「归不了任何站点」的店铺：单列，不摊给任何站点。
          var unattributedShops = new ArrayList<String>();
          if (type == ReportingStoreSiteCatalog.DimensionType.SITE) {
            for (var shop : shopCodes(current, previous)) {
              if (!attributableShopCodes(profiles).contains(shop)) unattributedShops.add(shop);
            }
          }
          var unattributedValues =
              new DimValues(
                  aggregate(current, unattributedShops), aggregate(previous, unattributedShops));

          // ---- 占比 / 贡献度的分母（**只含能唯一归属到该层级的部分**）----
          var totals = totals(attributable);
          var deltas = deltas(attributable);

          // ---- 组装行 ----
          var rows = new ArrayList<ReportingStoreSiteModels.StoreSiteRow>();
          for (var key : keys) {
            var values = attributable.get(key.key());
            var age = shopAges(profiles, current, previous, key.shopCodes());
            rows.add(
                new ReportingStoreSiteModels.StoreSiteRow(
                    key.key(),
                    key.label(),
                    key.platformCode(),
                    key.platformName(),
                    key.siteCode(),
                    key.siteName(),
                    type == ReportingStoreSiteCatalog.DimensionType.SHOP ? key.key() : "",
                    type == ReportingStoreSiteCatalog.DimensionType.SHOP ? shopName(profiles, key.key()) : "",
                    type == ReportingStoreSiteCatalog.DimensionType.SHOP ? age.storeAge() : "",
                    age.newShopCount(),
                    age.existingShopCount(),
                    metricValues(definitions, values, totals, deltas, false),
                    key.note()));
          }
          // 排序：按**跨币种可比的销量**倒序（金额按币种分别报，跨币种比大小本来就不可比）。
          rows.sort(
              Comparator.comparing(
                      (ReportingStoreSiteModels.StoreSiteRow r) ->
                          negated(cellValue(r, ReportingStoreSiteCatalog.STORE_SITE_SALES_QUANTITY)))
                  .thenComparing(ReportingStoreSiteModels.StoreSiteRow::dimensionKey));

          var totalRows = rows.size();
          var safePage = Math.max(page, 0);
          var safeSize = size <= 0 ? 50 : Math.min(size, 200);
          var paged = rows.subList(
              Math.min(safePage * safeSize, totalRows),
              Math.min((safePage + 1) * safeSize, totalRows));

          // ---- 渠道占比（**始终按平台维度算**，与当前选中的维度无关）----
          var platformKeys = dimensionKeys(
              ReportingStoreSiteCatalog.DimensionType.PLATFORM, profiles, current, previous);
          var platformValues = new LinkedHashMap<String, DimValues>();
          for (var key : platformKeys) {
            platformValues.put(
                key.key(),
                new DimValues(
                    aggregate(current, key.shopCodes()), aggregate(previous, key.shopCodes())));
          }
          var platformTotals = totals(platformValues);
          var platformDeltas = deltas(platformValues);
          var channelShares = new ArrayList<ReportingStoreSiteModels.StoreSiteChannelShare>();
          for (var key : platformKeys) {
            var values = platformValues.get(key.key());
            channelShares.add(
                new ReportingStoreSiteModels.StoreSiteChannelShare(
                    key.platformCode(),
                    key.platformName(),
                    metricValues(definitions, values, platformTotals, platformDeltas, false),
                    key.note()));
          }

          // ---- 新店 / 老店（**按店铺算**，分母是全部店铺，与选中的维度无关）----
          var allShops = new ArrayList<>(shopCodes(current, previous));
          var allValues = new DimValues(aggregate(current, allShops), aggregate(previous, allShops));
          var allTotals = totals(Map.of("all", allValues));
          var allDeltas = deltas(Map.of("all", allValues));
          var newVsExisting =
              newVsExisting(profiles, current, previous, definitions, allTotals, allDeltas);

          // ---- 无法唯一归属到站点 ----
          var unattributed =
              new ReportingStoreSiteModels.StoreSiteUnattributed(
                  unattributedShops.size(),
                  List.copyOf(unattributedShops),
                  unattributedShops.isEmpty()
                      ? List.of()
                      : metricValues(
                          definitions,
                          unattributedValues,
                          totals,
                          deltas,
                          false),
                  unattributedShops.isEmpty()
                      ? ""
                      : "这些店铺启用了多个站点（或没有启用站点），而**订单不带站点**，"
                          + "所以它们的销量归不了任何站点 —— 摊给谁是造假，因此单列。",
                  unattributedShops.isEmpty()
                      ? "本窗口内没有「无法唯一归属到站点」的店铺。"
                      : "这一部分的成交金额**不在**站点维度的占比分母里 —— "
                          + "这里显示的占比是「这一部分 ÷ **可唯一归属那部分**的合计」，"
                          + "**不是**「这一部分 ÷ 全部」。");

          // ---- 覆盖度 ----
          var coverage = coverage(tenantId, from, to, zone);

          var updatedAt = dataUpdatedAt(tenantId);
          var warnings = new ArrayList<String>();
          if (coverage.truncated()) {
            warnings.add(
                "⚠️ 销量只存在于订单明细里，明细要逐单取（有张数上限）："
                    + "本次销量只覆盖了 "
                    + coverage.orderCountWithDetail()
                    + " / "
                    + coverage.orderCount()
                    + " 张订单（明细覆盖率 "
                    + (coverage.detailCoveragePercent() == null
                        ? "—"
                        : coverage.detailCoveragePercent().toPlainString())
                    + "%）。**未覆盖部分的销量没有算进来**，成交金额与订单数不受影响。");
          }
          if (!unattributedShops.isEmpty()) {
            warnings.add(
                "⚠️ 有 "
                    + unattributedShops.size()
                    + " 个店铺的销量**无法唯一归属到站点**（订单不带站点），"
                    + "它们不计入站点维度的占比分母。");
          }

          var empty = rows.isEmpty();
          return new ReportingStoreSiteModels.StoreSiteResponse(
              from,
              to,
              base.from(),
              base.to(),
              type.name(),
              dimensionLabel(type),
              zone.getId(),
              totalRows,
              safePage,
              safeSize,
              List.copyOf(paged),
              definitionViews(definitions),
              channelShares,
              newVsExisting,
              unattributed,
              coverage,
              updatedAt,
              updatedAt == null
                  ? "本租户还没有任何店铺维度投影数据（空表时**不显示**假更新时间）。"
                  : "数据更新时间 = 最近一次投影落库时刻（报表是投影，不是实时的）。",
              empty ? "EMPTY" : "OK",
              empty
                  ? "这个窗口内没有任何店铺维度的成交事实。"
                  : "已按 "
                      + dimensionLabel(type)
                      + " 维度拆解；基期（上一等长窗口）用于算增长贡献度。",
              empty ? "NO_FACTS_IN_WINDOW" : null,
              empty
                  ? "这个窗口内没有可拆解的成交事实。可能是这段时间本来就没出单，"
                      + "也可能是**还没跑过投影刷新** —— 两者页面上长得一样，所以这里如实说明。"
                  : "",
              warnings,
              caliberNotes(type, unattributedShops.size()));
        });
  }

  // ==========================================================================
  // 下钻
  // ==========================================================================

  /**
   * 构成明细下钻：某个维度对象、某个指标的构成明细（哪张订单、哪张订单的哪一行）。
   *
   * @param dimensionKey 维度键（SHOP 维度 = 店铺码；SITE / PLATFORM 维度 = 对应的维度键）
   */
  public ReportingStoreSiteModels.StoreSiteDrilldownResponse drilldown(
      UUID tenantId,
      LocalDate from,
      LocalDate to,
      String dimension,
      String dimensionKey,
      String metricCode,
      int page,
      int size) {
    var type = normalizeDimension(dimension);
    var zone = ZoneId.of("UTC");
    return tenants.run(
        tenantId,
        () -> {
          var profiles = loadProfiles(tenantId);
          var shops = shopsOf(type, dimensionKey, profiles);
          var rangeStart = from.atStartOfDay(zone).toOffsetDateTime();
          var rangeEnd = to.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
          var warnings = new ArrayList<String>();
          if (shops.isEmpty()) {
            return new ReportingStoreSiteModels.StoreSiteDrilldownResponse(
                from,
                to,
                zone.getId(),
                dimensionKey,
                0,
                Math.max(page, 0),
                size <= 0 ? 50 : Math.min(size, 200),
                List.of(),
                dataUpdatedAt(tenantId),
                "本租户还没有任何店铺维度投影数据。",
                "EMPTY",
                "这个维度对象没有可下钻的构成明细。",
                "NO_FACTS_IN_WINDOW",
                "这个维度对象在档案里查不到，或它在窗口内没有成交事实。",
                warnings);
          }
          var placeholders = new StringBuilder();
          for (int i = 0; i < shops.size(); i++) placeholders.append(i == 0 ? "?" : ",?");
          var params = new ArrayList<Object>();
          params.add(tenantId);
          params.add(rangeStart);
          params.add(rangeEnd);
          params.addAll(shops);
          if (metricCode != null && !metricCode.isBlank()) params.add(metricCode.trim());
          var sqlText =
              """
              select shop_code, metric_code, currency, source_kind, reference_id, reference_label,
                     order_no, sku_code, sku_name, occurred_at, quantity, value
                from reporting_store_site_drilldown_fact
               where tenant_id = ?
                 and bucket_start >= ?::timestamptz and bucket_start < ?::timestamptz
                 and shop_code in ("""
                  + placeholders
                  + """
               )
              """
                  + (metricCode != null && !metricCode.isBlank() ? " and metric_code = ? " : "")
                  + " order by value desc, quantity desc, reference_id";
          var raw = sql.fetch(sqlText, params.toArray());
          var all = new ArrayList<ReportingStoreSiteModels.StoreSiteDrilldownRow>();
          for (var row : raw) {
            all.add(
                new ReportingStoreSiteModels.StoreSiteDrilldownRow(
                    row.get(0, String.class),
                    row.get(1, String.class),
                    row.get(2, String.class),
                    row.get(3, String.class),
                    row.get(4, String.class),
                    row.get(5, String.class),
                    row.get(6, String.class),
                    row.get(7, String.class),
                    row.get(8, String.class),
                    row.get(9, OffsetDateTime.class),
                    nz(row.get(10, BigDecimal.class)),
                    nz(row.get(11, BigDecimal.class))));
          }
          var safePage = Math.max(page, 0);
          var safeSize = size <= 0 ? 50 : Math.min(size, 200);
          var paged =
              all.subList(
                  Math.min(safePage * safeSize, all.size()),
                  Math.min((safePage + 1) * safeSize, all.size()));
          return new ReportingStoreSiteModels.StoreSiteDrilldownResponse(
              from,
              to,
              zone.getId(),
              dimensionKey,
              all.size(),
              safePage,
              safeSize,
              List.copyOf(paged),
              dataUpdatedAt(tenantId),
              "数据更新时间 = 最近一次投影落库时刻。",
              all.isEmpty() ? "EMPTY" : "OK",
              all.isEmpty() ? "这个维度对象在窗口内没有构成明细。" : "构成明细按贡献倒序。",
              all.isEmpty() ? "NO_FACTS_IN_WINDOW" : null,
              all.isEmpty() ? "这个窗口内没有构成明细。" : "",
              warnings);
        });
  }

  // ==========================================================================
  // 内部结构
  // ==========================================================================

  /** 一个指标的聚合值（金额 / 笔数走 value，件数走 quantity）。 */
  private record Agg(BigDecimal value, BigDecimal quantity) {}

  /** 一个维度对象在两个窗口里的聚合值。 */
  private record DimValues(Map<String, Map<String, Agg>> current, Map<String, Map<String, Agg>> base) {}

  /** 一个维度对象。 */
  private record DimKey(
      String key,
      String label,
      String platformCode,
      String platformName,
      String siteCode,
      String siteName,
      List<String> shopCodes,
      String note) {}

  /** 一个店铺的档案（一个店铺可能有多行 —— 启用了多个站点时）。 */
  private record ShopProfile(
      String shopCode,
      String shopName,
      String platformCode,
      String platformName,
      String siteCode,
      String siteName,
      String siteCurrency,
      String siteTimezone,
      String attributionStatus) {}

  /** 一个店铺的新老判定。 */
  private record ShopAge(String storeAge, int newShopCount, int existingShopCount) {}

  // ==========================================================================
  // 档案
  // ==========================================================================

  private Map<String, List<ShopProfile>> loadProfiles(UUID tenantId) {
    var rows =
        sql.fetch(
            """
            select shop_code, shop_name, platform_code, platform_name, site_code, site_name,
                   site_currency, site_timezone, attribution_status
              from reporting_store_site_profile
             where tenant_id = ?
             order by shop_code, site_code
            """,
            tenantId);
    var out = new LinkedHashMap<String, List<ShopProfile>>();
    for (var row : rows) {
      var profile =
          new ShopProfile(
              row.get(0, String.class),
              row.get(1, String.class),
              row.get(2, String.class),
              row.get(3, String.class),
              row.get(4, String.class),
              row.get(5, String.class),
              row.get(6, String.class),
              row.get(7, String.class),
              row.get(8, String.class));
      out.computeIfAbsent(profile.shopCode(), k -> new ArrayList<>()).add(profile);
    }
    return out;
  }

  /** 店铺编码 → 平台码（取该店铺的任意一行 —— 平台是店铺级属性）。 */
  private String platformOf(Map<String, List<ShopProfile>> profiles, String shopCode) {
    var list = profiles.get(shopCode);
    if (list == null || list.isEmpty()) return "";
    return list.get(0).platformCode();
  }

  private String platformNameOf(Map<String, List<ShopProfile>> profiles, String shopCode) {
    var list = profiles.get(shopCode);
    if (list == null || list.isEmpty()) return "";
    return list.get(0).platformName();
  }

  private String shopName(Map<String, List<ShopProfile>> profiles, String shopCode) {
    var list = profiles.get(shopCode);
    if (list == null || list.isEmpty()) return "";
    return list.get(0).shopName;
  }

  /** 能唯一归属到站点的店铺编码集合（只启用了一个站点的那些）。 */
  private Set<String> attributableShopCodes(Map<String, List<ShopProfile>> profiles) {
    var out = new LinkedHashSet<String>();
    for (var entry : profiles.entrySet()) {
      for (var profile : entry.getValue()) {
        if ("UNIQUE".equals(profile.attributionStatus())) {
          out.add(entry.getKey());
          break;
        }
      }
    }
    return out;
  }

  // ==========================================================================
  // 维度键
  // ==========================================================================

  /** 按选中的维度把店铺分组。 */
  private List<DimKey> dimensionKeys(
      ReportingStoreSiteCatalog.DimensionType type,
      Map<String, List<ShopProfile>> profiles,
      Map<String, Map<String, Map<String, Agg>>> current,
      Map<String, Map<String, Map<String, Agg>>> previous) {
    var shops = shopCodes(current, previous);
    var grouped = new LinkedHashMap<String, DimKey>();
    for (var shop : shops) {
      var platformCode = platformOf(profiles, shop);
      String key;
      String label;
      String siteCode = "";
      String siteName = "";
      String note = "";
      switch (type) {
        case SHOP -> {
          key = shop;
          label = shopName(profiles, shop).isBlank() ? shop : shopName(profiles, shop);
        }
        case PLATFORM -> {
          key = platformCode.isBlank() ? "" : platformCode;
          label = platformCode.isBlank() ? "未标注平台" : platformNameOf(profiles, shop);
          note =
              platformCode.isBlank()
                  ? "这个店铺在渠道侧**没有确认平台归属**（空平台码 = 尚未确认），"
                      + "归到「未标注平台」组 —— **绝不猜一个平台**。"
                  : "";
        }
        case SITE -> {
          var site = uniqueSite(profiles, shop);
          if (site == null) continue; // 归不了任何站点 ⇒ 不进站点维度（单列在「无法唯一归属」）
          key = site.platformCode() + ":" + site.siteCode();
          label =
              site.siteName().isBlank()
                  ? site.platformName() + " · " + site.siteCode()
                  : site.siteName();
          siteCode = site.siteCode();
          siteName = site.siteName();
        }
        default -> {
          key = shop;
          label = shop;
        }
      }
      var existing = grouped.get(key);
      if (existing == null) {
        grouped.put(
            key,
            new DimKey(
                key,
                label,
                platformCode,
                platformNameOf(profiles, shop),
                siteCode,
                siteName,
                new ArrayList<>(),
                note));
      }
      grouped.get(key).shopCodes().add(shop);
    }
    return new ArrayList<>(grouped.values());
  }

  /** 该店铺唯一启用的站点（启用多个或没启用 ⇒ 返回 null = 归不了任何站点）。 */
  private ShopProfile uniqueSite(Map<String, List<ShopProfile>> profiles, String shopCode) {
    var list = profiles.get(shopCode);
    if (list == null) return null;
    for (var profile : list) {
      if ("UNIQUE".equals(profile.attributionStatus())) return profile;
    }
    return null;
  }

  /** 维度对象对应的店铺集合（下钻用）。 */
  private List<String> shopsOf(
      ReportingStoreSiteCatalog.DimensionType type,
      String dimensionKey,
      Map<String, List<ShopProfile>> profiles) {
    var out = new ArrayList<String>();
    if (dimensionKey == null || dimensionKey.isBlank()) return out;
    switch (type) {
      case SHOP -> out.add(dimensionKey.trim());
      case PLATFORM -> {
        for (var entry : profiles.entrySet()) {
          if (platformOf(profiles, entry.getKey()).equals(dimensionKey.trim())) {
            out.add(entry.getKey());
          }
        }
      }
      case SITE -> {
        for (var entry : profiles.entrySet()) {
          var site = uniqueSite(profiles, entry.getKey());
          if (site == null) continue;
          if ((site.platformCode() + ":" + site.siteCode()).equals(dimensionKey.trim())) {
            out.add(entry.getKey());
          }
        }
      }
      default -> {}
    }
    return out;
  }

  // ==========================================================================
  // 事实取数
  // ==========================================================================

  /**
   * 取一个窗口内的事实（店铺 → 指标 → 币种 → 聚合值）。
   *
   * <p>⚠️ **窗口按调用方时区换算成瞬间区间**（与 13.2.1.1 逐字同一纪律）：
   * {@code from 00:00（含）到 to+1 00:00（不含）}，按该时区换算成瞬间。
   */
  private Map<String, Map<String, Map<String, Agg>>> loadFacts(
      UUID tenantId, LocalDate from, LocalDate to, ZoneId zone) {
    var rangeStart = from.atStartOfDay(zone).toOffsetDateTime();
    var rangeEnd = to.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
    var rows =
        sql.fetch(
            """
            select shop_code, metric_code, currency, sum(value) as v, sum(quantity) as q
              from reporting_store_site_metric_fact
             where tenant_id = ?
               and bucket_start >= ?::timestamptz and bucket_start < ?::timestamptz
             group by shop_code, metric_code, currency
            """,
            tenantId,
            rangeStart,
            rangeEnd);
    var out = new LinkedHashMap<String, Map<String, Map<String, Agg>>>();
    for (var row : rows) {
      var shop = row.get(0, String.class);
      var metric = row.get(1, String.class);
      var currency = row.get(2, String.class);
      var agg = new Agg(nz(row.get(3, BigDecimal.class)), nz(row.get(4, BigDecimal.class)));
      out.computeIfAbsent(shop, k -> new LinkedHashMap<>())
          .computeIfAbsent(metric, k -> new LinkedHashMap<>())
          .put(currency, agg);
    }
    return out;
  }

  /** 把一组店铺的事实聚合成一个维度对象的值。 */
  private Map<String, Map<String, Agg>> aggregate(
      Map<String, Map<String, Map<String, Agg>>> facts, List<String> shopCodes) {
    var out = new LinkedHashMap<String, Map<String, Agg>>();
    for (var shop : shopCodes) {
      var perMetric = facts.get(shop);
      if (perMetric == null) continue;
      for (var metricEntry : perMetric.entrySet()) {
        var metric = metricEntry.getKey();
        var target = out.computeIfAbsent(metric, k -> new LinkedHashMap<>());
        for (var currencyEntry : metricEntry.getValue().entrySet()) {
          var currency = currencyEntry.getKey();
          var agg = currencyEntry.getValue();
          var acc = target.get(currency);
          target.put(
              currency,
              acc == null
                  ? agg
                  : new Agg(acc.value().add(agg.value()), acc.quantity().add(agg.quantity())));
        }
      }
    }
    return out;
  }

  /** 增量（当前 − 基期）。 */
  private static Map<String, Map<String, Agg>> delta(
      Map<String, Map<String, Agg>> current, Map<String, Map<String, Agg>> base) {
    var out = new LinkedHashMap<String, Map<String, Agg>>();
    var metrics = new LinkedHashSet<String>();
    metrics.addAll(current.keySet());
    metrics.addAll(base.keySet());
    for (var metric : metrics) {
      var currentCurrencies = current.getOrDefault(metric, Map.of());
      var baseCurrencies = base.getOrDefault(metric, Map.of());
      var currencies = new TreeSet<String>();
      currencies.addAll(currentCurrencies.keySet());
      currencies.addAll(baseCurrencies.keySet());
      var perCurrency = new LinkedHashMap<String, Agg>();
      for (var currency : currencies) {
        var currentAgg = currentCurrencies.get(currency);
        var baseAgg = baseCurrencies.get(currency);
        var value =
            currentAgg == null
                ? (baseAgg == null ? null : baseAgg.value().negate())
                : (baseAgg == null ? currentAgg.value() : currentAgg.value().subtract(baseAgg.value()));
        var quantity =
            currentAgg == null
                ? (baseAgg == null ? null : baseAgg.quantity().negate())
                : (baseAgg == null
                    ? currentAgg.quantity()
                    : currentAgg.quantity().subtract(baseAgg.quantity()));
        perCurrency.put(currency, new Agg(value, quantity));
      }
      out.put(metric, perCurrency);
    }
    return out;
  }

  /** 各维度对象的合计（占比的分母）。 */
  private Map<String, Map<String, Agg>> totals(Map<String, DimValues> values) {
    var out = new LinkedHashMap<String, Map<String, Agg>>();
    for (var entry : values.entrySet()) {
      for (var metricEntry : entry.getValue().current().entrySet()) {
        var metric = metricEntry.getKey();
        var target = out.computeIfAbsent(metric, k -> new LinkedHashMap<>());
        for (var currencyEntry : metricEntry.getValue().entrySet()) {
          var currency = currencyEntry.getKey();
          var agg = currencyEntry.getValue();
          var acc = target.get(currency);
          target.put(
              currency,
              acc == null
                  ? agg
                  : new Agg(acc.value().add(agg.value()), acc.quantity().add(agg.quantity())));
        }
      }
    }
    return out;
  }

  /** 各维度对象的增量之和（贡献度的分母）。 */
  private Map<String, Map<String, Agg>> deltas(Map<String, DimValues> values) {
    var out = new LinkedHashMap<String, Map<String, Agg>>();
    for (var entry : values.entrySet()) {
      var perMetric =
          delta(entry.getValue().current(), entry.getValue().base());
      for (var metricEntry : perMetric.entrySet()) {
        var metric = metricEntry.getKey();
        var target = out.computeIfAbsent(metric, k -> new LinkedHashMap<>());
        for (var currencyEntry : metricEntry.getValue().entrySet()) {
          var currency = currencyEntry.getKey();
          var agg = currencyEntry.getValue();
          var acc = target.get(currency);
          target.put(
              currency,
              acc == null
                  ? agg
                  : new Agg(acc.value().add(agg.value()), acc.quantity().add(agg.quantity())));
        }
      }
    }
    return out;
  }

  // ==========================================================================
  // 派生指标求值
  // ==========================================================================

  /**
   * 按口径定义求一个维度对象的全部指标取值。
   *
   * @param totals 同层级合计（占比的分母）
   * @param deltas 同层级增量之和（贡献度的分母）
   * @param includeNewStoreContribution 是否带上「新店增长贡献度」——
   *     它是「新店这一组」的指标，**不出现在分组报表的每一行里**
   */
  private List<ReportingStoreSiteModels.StoreSiteMetricValue> metricValues(
      Map<String, ReportingStoreSiteCatalog.Definition> definitions,
      DimValues values,
      Map<String, Map<String, Agg>> totals,
      Map<String, Map<String, Agg>> deltas,
      boolean includeNewStoreContribution) {
    var out = new ArrayList<ReportingStoreSiteModels.StoreSiteMetricValue>();
    for (var definition : definitions.values()) {
      var metricCode = definition.metricCode();
      if (!includeNewStoreContribution
          && ReportingStoreSiteCatalog.STORE_SITE_NEW_STORE_AMOUNT_CONTRIBUTION.equals(metricCode)) {
        continue;
      }
      var quantity = usesQuantity(metricCode);
      switch (definition.aggregation()) {
        case SUM -> {
          if (definition.currencyAware()) {
            for (var currency : currenciesOf(values, metricCode)) {
              var value = metricValue(values.current(), metricCode, currency, quantity);
              var baseValue = metricValue(values.base(), metricCode, currency, quantity);
              out.add(
                  sumValue(metricCode, currency, value, baseValue, definition, quantity));
            }
          } else {
            var value = metricValue(values.current(), metricCode, null, quantity);
            var baseValue = metricValue(values.base(), metricCode, null, quantity);
            out.add(sumValue(metricCode, "", value, baseValue, definition, quantity));
          }
        }
        case GROUP_SHARE -> {
          var baseMetric = definition.baseMetricCode();
          var baseQuantity = usesQuantity(baseMetric);
          if (definition.currencyAware()) {
            for (var currency : currenciesOf(values, baseMetric)) {
              var numerator = metricValue(values.current(), baseMetric, currency, baseQuantity);
              var denominator = totalValue(totals, baseMetric, currency, baseQuantity);
              out.add(
                  shareValue(
                      metricCode, currency, numerator, denominator, definition, baseQuantity));
            }
          } else {
            var numerator = metricValue(values.current(), baseMetric, null, baseQuantity);
            var denominator = totalValue(totals, baseMetric, null, baseQuantity);
            out.add(shareValue(metricCode, "", numerator, denominator, definition, baseQuantity));
          }
        }
        case GROUP_CONTRIBUTION -> {
          var baseMetric = definition.baseMetricCode();
          var baseQuantity = usesQuantity(baseMetric);
          if (definition.currencyAware()) {
            for (var currency : currenciesOf(values, baseMetric)) {
              var numerator = deltaValue(values, baseMetric, currency, baseQuantity);
              var denominator = totalDelta(deltas, baseMetric, currency, baseQuantity);
              out.add(
                  contributionValue(
                      metricCode, currency, numerator, denominator, definition, baseQuantity));
            }
          } else {
            var numerator = deltaValue(values, baseMetric, null, baseQuantity);
            var denominator = totalDelta(deltas, baseMetric, null, baseQuantity);
            out.add(
                contributionValue(metricCode, "", numerator, denominator, definition, baseQuantity));
          }
        }
        case WINDOW_GROWTH -> {
          var baseMetric = definition.baseMetricCode();
          var baseQuantity = usesQuantity(baseMetric);
          if (definition.currencyAware()) {
            for (var currency : currenciesOf(values, baseMetric)) {
              var current = metricValue(values.current(), baseMetric, currency, baseQuantity);
              var base = metricValue(values.base(), baseMetric, currency, baseQuantity);
              out.add(growthValue(metricCode, currency, current, base, definition));
            }
          } else {
            var current = metricValue(values.current(), baseMetric, null, baseQuantity);
            var base = metricValue(values.base(), baseMetric, null, baseQuantity);
            out.add(growthValue(metricCode, "", current, base, definition));
          }
        }
        default -> {}
      }
    }
    return out;
  }

  private ReportingStoreSiteModels.StoreSiteMetricValue sumValue(
      String metricCode,
      String currency,
      BigDecimal value,
      BigDecimal baseValue,
      ReportingStoreSiteCatalog.Definition definition,
      boolean quantity) {
    var availability = value == null ? "NO_DATA" : "AVAILABLE";
    var note = value == null ? NO_DATA_NOTE : "";
    return new ReportingStoreSiteModels.StoreSiteMetricValue(
        metricCode, currency, value, baseValue, null, availability, note);
  }

  private ReportingStoreSiteModels.StoreSiteMetricValue shareValue(
      String metricCode,
      String currency,
      BigDecimal numerator,
      BigDecimal denominator,
      ReportingStoreSiteCatalog.Definition definition,
      boolean quantity) {
    if (numerator == null) {
      return new ReportingStoreSiteModels.StoreSiteMetricValue(
          metricCode, currency, null, null, null, "NO_DATA", NO_DATA_NOTE);
    }
    if (denominator == null || denominator.signum() == 0) {
      return new ReportingStoreSiteModels.StoreSiteMetricValue(
          metricCode,
          currency,
          null,
          null,
          null,
          "NOT_COMPARABLE",
          "同层级合计为 0（或没有事实）⇒ 占比**算不出来**（不是 0%）。");
    }
    var share =
        numerator
            .divide(denominator, 10, RoundingMode.HALF_UP)
            .movePointRight(2)
            .setScale(PERCENT_SCALE, RoundingMode.HALF_UP);
    return new ReportingStoreSiteModels.StoreSiteMetricValue(
        metricCode, currency, share, null, null, "AVAILABLE", "");
  }

  private ReportingStoreSiteModels.StoreSiteMetricValue contributionValue(
      String metricCode,
      String currency,
      BigDecimal numerator,
      BigDecimal denominator,
      ReportingStoreSiteCatalog.Definition definition,
      boolean quantity) {
    if (numerator == null) {
      return new ReportingStoreSiteModels.StoreSiteMetricValue(
          metricCode,
          currency,
          null,
          null,
          null,
          "NO_DATA",
          "基期没有事实 ⇒ **增量算不出来**（不是 0）。");
    }
    if (denominator == null || denominator.signum() == 0) {
      return new ReportingStoreSiteModels.StoreSiteMetricValue(
          metricCode,
          currency,
          null,
          null,
          null,
          "NOT_COMPARABLE",
          "全部维度的增量之和为 0 ⇒ 贡献度**算不出来**（不是 0%）。");
    }
    var contribution =
        numerator
            .divide(denominator, 10, RoundingMode.HALF_UP)
            .movePointRight(2)
            .setScale(PERCENT_SCALE, RoundingMode.HALF_UP);
    return new ReportingStoreSiteModels.StoreSiteMetricValue(
        metricCode, currency, contribution, null, numerator, "AVAILABLE", "");
  }

  private ReportingStoreSiteModels.StoreSiteMetricValue growthValue(
      String metricCode,
      String currency,
      BigDecimal current,
      BigDecimal base,
      ReportingStoreSiteCatalog.Definition definition) {
    if (current == null) {
      return new ReportingStoreSiteModels.StoreSiteMetricValue(
          metricCode, currency, null, null, null, "NO_DATA", NO_DATA_NOTE);
    }
    if (base == null) {
      return new ReportingStoreSiteModels.StoreSiteMetricValue(
          metricCode,
          currency,
          null,
          null,
          null,
          "NO_DATA",
          "基期（上一等长窗口）没有这个指标的事实 ⇒ 增长率**算不出来**（不是 0%）。");
    }
    if (base.signum() == 0) {
      return new ReportingStoreSiteModels.StoreSiteMetricValue(
          metricCode,
          currency,
          null,
          base,
          current,
          "NOT_COMPARABLE",
          "基期为 0 ⇒ 增长率**算不出来**（不是 0%）。");
    }
    var rate =
        current
            .subtract(base)
            .divide(base, 10, RoundingMode.HALF_UP)
            .movePointRight(2)
            .setScale(PERCENT_SCALE, RoundingMode.HALF_UP);
    return new ReportingStoreSiteModels.StoreSiteMetricValue(
        metricCode, currency, rate, base, current.subtract(base), "AVAILABLE", "");
  }

  // ==========================================================================
  // 取值辅助
  // ==========================================================================

  /** 该指标落在 value 列还是 quantity 列。 */
  private static boolean usesQuantity(String metricCode) {
    return ReportingStoreSiteCatalog.STORE_SITE_SALES_QUANTITY.equals(metricCode);
  }

  /** 该维度对象在某指标上出现过的币种（当前窗口 ∪ 基期）。 */
  private Set<String> currenciesOf(DimValues values, String metricCode) {
    var out = new TreeSet<String>();
    for (var source : List.of(values.current(), values.base())) {
      var perCurrency = source.get(metricCode);
      if (perCurrency != null) out.addAll(perCurrency.keySet());
    }
    return out;
  }

  /**
   * 取一个指标的取值。
   *
   * @param currency 精确币种；**null = 跨币种合计**（与货币无关的量）
   */
  private static BigDecimal metricValue(
      Map<String, Map<String, Agg>> agg, String metricCode, String currency, boolean quantity) {
    var perCurrency = agg.get(metricCode);
    if (perCurrency == null) return null;
    if (currency != null) {
      var value = perCurrency.get(currency);
      return value == null ? null : (quantity ? value.quantity() : value.value());
    }
    BigDecimal sum = null;
    for (var value : perCurrency.values()) {
      var part = quantity ? value.quantity() : value.value();
      sum = sum == null ? part : sum.add(part);
    }
    return sum;
  }

  /** 该维度对象相对基期的增量。 */
  private static BigDecimal deltaValue(
      DimValues values, String metricCode, String currency, boolean quantity) {
    var perMetric = delta(values.current(), values.base());
    return metricValue(perMetric, metricCode, currency, quantity);
  }

  /** 同层级合计。 */
  private static BigDecimal totalValue(
      Map<String, Map<String, Agg>> totals, String metricCode, String currency, boolean quantity) {
    return metricValue(totals, metricCode, currency, quantity);
  }

  /** 同层级增量之和。 */
  private static BigDecimal totalDelta(
      Map<String, Map<String, Agg>> deltas, String metricCode, String currency, boolean quantity) {
    return metricValue(deltas, metricCode, currency, quantity);
  }

  // ==========================================================================
  // 新店 / 老店
  // ==========================================================================

  /** 一个店铺集合的新老判定与计数。 */
  private ShopAge shopAges(
      Map<String, List<ShopProfile>> profiles,
      Map<String, Map<String, Map<String, Agg>>> current,
      Map<String, Map<String, Map<String, Agg>>> previous,
      List<String> shopCodes) {
    int fresh = 0;
    int established = 0;
    String age = "";
    for (var shop : shopCodes) {
      var hasCurrent = hasFact(current, shop);
      var hasBase = hasFact(previous, shop);
      if (hasCurrent && !hasBase) {
        fresh++;
        if (age.isBlank()) age = ReportingStoreSiteCatalog.STORE_AGE_NEW;
      } else if (hasBase) {
        established++;
        if (age.isBlank()) age = ReportingStoreSiteCatalog.STORE_AGE_EXISTING;
      }
    }
    return new ShopAge(age, fresh, established);
  }

  private static boolean hasFact(
      Map<String, Map<String, Map<String, Agg>>> facts, String shopCode) {
    var perMetric = facts.get(shopCode);
    return perMetric != null && !perMetric.isEmpty();
  }

  /** 新店 / 老店汇总（增量里有多少来自新店）。 */
  private ReportingStoreSiteModels.StoreSiteNewVsExisting newVsExisting(
      Map<String, List<ShopProfile>> profiles,
      Map<String, Map<String, Map<String, Agg>>> current,
      Map<String, Map<String, Map<String, Agg>>> previous,
      Map<String, ReportingStoreSiteCatalog.Definition> definitions,
      Map<String, Map<String, Agg>> allTotals,
      Map<String, Map<String, Agg>> allDeltas) {
    var fresh = new ArrayList<String>();
    var established = new ArrayList<String>();
    for (var shop : shopCodes(current, previous)) {
      if (hasFact(previous, shop)) established.add(shop);
      else if (hasFact(current, shop)) fresh.add(shop);
    }
    var freshValues = new DimValues(aggregate(current, fresh), aggregate(previous, fresh));
    var establishedValues =
        new DimValues(aggregate(current, established), aggregate(previous, established));

    // 🔴 新店增长贡献度 = 新店的金额增量 ÷ 全部店铺的金额增量之和
    //   （分母用**全部店铺**，与当前选中的维度无关 —— 新店 / 老店是店铺级的事）。
    var freshMetrics =
        metricValues(definitions, freshValues, allTotals, allDeltas, true);
    var contributions = new ArrayList<ReportingStoreSiteModels.StoreSiteMetricValue>();
    for (var metric : freshMetrics) {
      if (ReportingStoreSiteCatalog.STORE_SITE_NEW_STORE_AMOUNT_CONTRIBUTION.equals(
          metric.metricCode())) {
        contributions.add(metric);
      }
    }
    if (contributions.isEmpty()) {
      // 该指标是 currency_aware ⇒ 币种来自新店实际出现过的币种；
      // 新店完全没有成交时它不会出现 ⇒ 明确说「算不出来」，而不是给一个 0%。
      contributions.add(
          new ReportingStoreSiteModels.StoreSiteMetricValue(
              ReportingStoreSiteCatalog.STORE_SITE_NEW_STORE_AMOUNT_CONTRIBUTION,
              "",
              null,
              null,
              null,
              "NO_DATA",
              "本窗口内没有新店 ⇒ 新店增长贡献度**算不出来**（不是 0%）。"));
    }

    return new ReportingStoreSiteModels.StoreSiteNewVsExisting(
        fresh.size(),
        established.size(),
        freshMetrics,
        metricValues(definitions, establishedValues, allTotals, allDeltas, false),
        contributions,
        ReportingStoreSiteCatalog.STORE_AGE_RULE);
  }

  // ==========================================================================
  // 覆盖度 / 更新时间 / 口径说明
  // ==========================================================================

  /** 销量口径的覆盖度（销量只存在于订单明细里 ⇒ 明细要逐单取，有上限）。 */
  private ReportingStoreSiteModels.StoreSiteCoverage coverage(
      UUID tenantId, LocalDate from, LocalDate to, ZoneId zone) {
    var rangeStart = from.atStartOfDay(zone).toOffsetDateTime();
    var rangeEnd = to.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
    var rows =
        sql.fetch(
            """
            select metric_code, count(distinct source_reference) as n
              from reporting_store_site_metric_fact
             where tenant_id = ?
               and bucket_start >= ?::timestamptz and bucket_start < ?::timestamptz
               and metric_code in (?, ?)
             group by metric_code
            """,
            tenantId,
            rangeStart,
            rangeEnd,
            ReportingStoreSiteCatalog.STORE_SITE_ORDER_COUNT,
            ReportingStoreSiteCatalog.STORE_SITE_SALES_QUANTITY);
    long orders = 0;
    long withDetail = 0;
    for (var row : rows) {
      var metric = row.get(0, String.class);
      var count = row.get(1, Long.class) == null ? 0L : row.get(1, Long.class);
      if (ReportingStoreSiteCatalog.STORE_SITE_ORDER_COUNT.equals(metric)) orders = count;
      if (ReportingStoreSiteCatalog.STORE_SITE_SALES_QUANTITY.equals(metric)) withDetail = count;
    }
    var percent =
        orders == 0
            ? null
            : BigDecimal.valueOf(withDetail)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(orders), PERCENT_SCALE, RoundingMode.HALF_UP);
    var truncated = orders > 0 && withDetail < orders;
    return new ReportingStoreSiteModels.StoreSiteCoverage(
        (int) orders,
        (int) withDetail,
        percent,
        truncated,
        truncated
            ? "⚠️ 销量只存在于订单明细里，明细要逐单取（有张数上限）⇒ 有一部分订单的销量**没有算进来**。"
                + "成交金额与订单数不受影响。"
            : "本窗口内每一张订单都取到了明细，销量口径是完整的。");
  }

  /** 数据更新时间（最近一次投影落库时刻）。 */
  private OffsetDateTime dataUpdatedAt(UUID tenantId) {
    // ⚠️ 事实表与构成明细表**都**算进去（与 13.2.1.1 / 13.2.2.2 同一纪律）：
    //   两张表是同一次投影写的，但明细表可能被单独补写 —— 只看事实表会让「数据截至」偏旧。
    var factRow =
        sql.fetchOne(
            "select max(updated_at) from reporting_store_site_metric_fact where tenant_id = ?",
            tenantId);
    var drillRow =
        sql.fetchOne(
            "select max(updated_at) from reporting_store_site_drilldown_fact where tenant_id = ?",
            tenantId);
    var factAt = factRow == null ? null : factRow.get(0, OffsetDateTime.class);
    var drillAt = drillRow == null ? null : drillRow.get(0, OffsetDateTime.class);
    if (factAt == null) return drillAt;
    if (drillAt == null) return factAt;
    return factAt.isAfter(drillAt) ? factAt : drillAt;
  }

  /** 口径说明（页面上原样显示）。 */
  private List<String> caliberNotes(ReportingStoreSiteCatalog.DimensionType type, int unattributed) {
    var notes = new ArrayList<String>();
    notes.add(
        "🔴 **绝不跨币种相加**：成交金额按币种分别报；只有件数与单数（与货币无关）给跨币种合计，"
            + "币种显示为空串 —— 空串 = 与币种无关，不是「币种未知」。");
    notes.add(
        "🔴 **「算不出来」不等于 0**：基期缺失、基期为 0、事实不存在，一律显示为「不可比 / 没有数据」，"
            + "不用 0 冒充。");
    notes.add(
        "**成交金额口径**：9 订单的订单总额 + 平台单回挂店铺；"
            + "与 13.2.1.1 销量趋势的店铺成交金额**同源同口径**，差别只在本模块多了维度树与增长贡献。");
    notes.add(
        "🔴 **站点维度有归属边界**：trade 的订单**不带站点**，站点归属只来自 channel 的 shop_marketplace ⇒ "
            + "启用多个站点（或没启用站点）的店铺，其销量**归不了任何站点**，本模块**单列**、不摊给任何站点"
            + "（当前窗口内共 "
            + unattributed
            + " 个店铺）。");
    notes.add(
        "**增长贡献度** = 该维度的增量 ÷ 全部维度的增量之和；"
            + "总增量为负时贡献度**照样是负数**（回答的是「这次掉的量里各家各掉多少」），"
            + "总增量为 0 时算不出来（不是 0%）。");
    notes.add("**新店 / 老店**按**成交事实**判定：" + ReportingStoreSiteCatalog.STORE_AGE_RULE);
    notes.add(
        "**数据延迟**：报表是投影，不是实时的。页面上常驻显示**数据更新时间**；"
            + "报表数字与业务系统冲突时，**以业务系统为准**。");
    return notes;
  }

  // ==========================================================================
  // 杂项
  // ==========================================================================

  private Map<String, ReportingStoreSiteCatalog.Definition> definitionMap(UUID tenantId) {
    var out = new LinkedHashMap<String, ReportingStoreSiteCatalog.Definition>();
    for (var definition : ReportingStoreSiteCatalog.STANDARD) {
      out.put(definition.metricCode(), definition);
    }
    return out;
  }

  private List<ReportingStoreSiteModels.StoreSiteMetricDefinitionView> definitionViews(
      Map<String, ReportingStoreSiteCatalog.Definition> definitions) {
    var out = new ArrayList<ReportingStoreSiteModels.StoreSiteMetricDefinitionView>();
    for (var definition : definitions.values()) {
      out.add(
          new ReportingStoreSiteModels.StoreSiteMetricDefinitionView(
              definition.metricCode(),
              definition.displayName(),
              definition.unit().name(),
              definition.aggregation().name(),
              definition.baseMetricCode(),
              definition.currencyAware(),
              definition.description(),
              definition.ownerModule()));
    }
    return out;
  }

  /** 出现在当前窗口或基期里的店铺编码。 */
  private Set<String> shopCodes(
      Map<String, Map<String, Map<String, Agg>>> current,
      Map<String, Map<String, Map<String, Agg>>> previous) {
    var out = new LinkedHashSet<String>();
    out.addAll(current.keySet());
    out.addAll(previous.keySet());
    return out;
  }

  /** 维度取值（取不到 ⇒ null = 算不出来）。 */
  private static BigDecimal cellValue(ReportingStoreSiteModels.StoreSiteRow row, String metricCode) {
    for (var metric : row.metrics()) {
      if (metric.metricCode().equals(metricCode)) {
        return metric.value() == null ? BigDecimal.ZERO : metric.value();
      }
    }
    return BigDecimal.ZERO;
  }

  private static BigDecimal negated(BigDecimal value) {
    return value.negate();
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static String dimensionLabel(ReportingStoreSiteCatalog.DimensionType type) {
    switch (type) {
      case SHOP:
        return "店铺";
      case SITE:
        return "站点";
      case PLATFORM:
        return "平台（渠道）";
      default:
        return type.name();
    }
  }

  /** 维度取值（非法取值 ⇒ 抛 400，不静默回落）。 */
  private ReportingStoreSiteCatalog.DimensionType normalizeDimension(String dimension) {
    if (dimension == null || dimension.isBlank()) {
      return ReportingStoreSiteCatalog.DimensionType.SHOP;
    }
    var normalized = dimension.trim().toUpperCase();
    try {
      return ReportingStoreSiteCatalog.DimensionType.valueOf(normalized);
    } catch (IllegalArgumentException e) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "拆解维度只能是 SHOP / SITE / PLATFORM（收到的是「" + dimension + "」）");
    }
  }
}
