package com.starcon.reporting;

import com.starcon.tenancy.TenantTransactions;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

/**
 * 13.1.1.2 多店对比的**读模型**（蓝图「输入」句：店铺维度聚合；对比指标选择）。
 *
 * <p>⚠️ **本类不重算口径**：口径（单位 / 聚合方式 / 分子分母）全部来自
 * {@code reporting_store_metric_definition}，本类只**按定义聚合**。派生型指标
 * （口径差异额 / 退货率 / 广告产出比 …）是**递归求值**出来的：先求分子分母，再相减或相除 ——
 * 而不是在这里写死一句 SQL 公式。
 *
 * <p>⚠️ **「算不出来」与「等于 0」必须分开**：分母为 0、事实不存在、指标未登记，
 * 三种情况一律返回 {@code null} / {@code NO_DATA} / {@code NOT_COMPARABLE}，**不用 0 冒充**。
 *
 * <p>⚠️ **不跨币种相加**：涉币指标按币种分别报，**排名也在币种内做** ——
 * 一个 USD 店铺和一个 EUR 店铺的大小本来就不可比。给了本位币时额外给出折算值，
 * 并把**汇率与时点**一起返回。
 *
 * <p>⚠️ **跨平台口径差异要标注**（蓝图关系句）：矩阵里同时给出「平台侧成交金额」与
 * 「还原口径成交金额」，并派生出「口径差异额」；平台分组上再挂一条口径说明。
 */
@Service
public class ReportingStoreComparisonService {

  /** 增长店铺阈值：环比增长率 ≥ +20%。 */
  static final BigDecimal GROWTH_THRESHOLD = new BigDecimal("0.20");

  /** 掉队店铺阈值：环比增长率 ≤ −20%。 */
  static final BigDecimal LAGGARD_THRESHOLD = new BigDecimal("-0.20");

  /** 派生求值的深度上限。 */
  private static final int MAX_DERIVE_DEPTH = 4;

  private final DSLContext sql;
  private final TenantTransactions tenants;
  private final ReportingStoreComparisonRefreshService refresh;

  public ReportingStoreComparisonService(
      DSLContext sql, TenantTransactions tenants, ReportingStoreComparisonRefreshService refresh) {
    this.sql = sql;
    this.tenants = tenants;
    this.refresh = refresh;
  }

  // ==========================================================================
  // 指标口径目录
  // ==========================================================================

  /** 读该租户的**店铺对比指标口径目录**（口径唯一的展示形态）。 */
  public List<ReportingStoreComparisonModels.MetricDefinitionView> metricDefinitions(
      java.util.UUID tenantId) {
    return tenants.run(
        tenantId,
        () -> {
          refresh.seedDefinitions(tenantId);
          var rows =
              sql.fetch(
                  """
                  select metric_code, display_name, unit, aggregation,
                         numerator_metric_code, denominator_metric_code, base_metric_code,
                         currency_aware, description, owner_module
                    from reporting_store_metric_definition
                   where tenant_id = ? and enabled
                   order by metric_code
                  """,
                  tenantId);
          var out = new ArrayList<ReportingStoreComparisonModels.MetricDefinitionView>();
          for (var row : rows) {
            out.add(
                new ReportingStoreComparisonModels.MetricDefinitionView(
                    row.get(0, String.class),
                    row.get(1, String.class),
                    row.get(2, String.class),
                    row.get(3, String.class),
                    row.get(4, String.class),
                    row.get(5, String.class),
                    row.get(6, String.class),
                    Boolean.TRUE.equals(row.get(7, Boolean.class)),
                    row.get(8, String.class),
                    row.get(9, String.class)));
          }
          return out;
        });
  }

  /** 口径定义 → 出参视图。 */
  private List<ReportingStoreComparisonModels.MetricDefinitionView> definitionViews(
      Map<String, Definition> definitions) {
    var out = new ArrayList<ReportingStoreComparisonModels.MetricDefinitionView>();
    for (var definition : definitions.values()) {
      out.add(
          new ReportingStoreComparisonModels.MetricDefinitionView(
              definition.metricCode(),
              definition.displayName(),
              definition.unit(),
              definition.aggregation(),
              definition.numeratorMetricCode(),
              definition.denominatorMetricCode(),
              definition.baseMetricCode(),
              definition.currencyAware(),
              definition.description(),
              definition.ownerModule()));
    }
    return out;
  }

  // ==========================================================================
  // 店铺清单
  // ==========================================================================

  /** 店铺清单 + 平台清单（筛选器与平台分组用）。 */
  public ReportingStoreComparisonModels.StoreListResponse stores(java.util.UUID tenantId) {
    return tenants.run(
        tenantId,
        () -> {
          var rows =
              sql.fetch(
                  """
                  select p.shop_code, p.shop_name, p.platform_code, p.platform_name,
                         (select count(*) from reporting_store_metric_fact f
                           where f.tenant_id = p.tenant_id and f.shop_code = p.shop_code) has_facts,
                         p.updated_at
                    from reporting_store_profile p
                   where p.tenant_id = ?
                   order by p.platform_code, p.shop_code
                  """,
                  tenantId);
          var stores = new ArrayList<ReportingStoreComparisonModels.StoreSummary>();
          var platformCounts = new LinkedHashMap<String, int[]>();
          var platformNames = new LinkedHashMap<String, String>();
          for (var row : rows) {
            var code = row.get(0, String.class);
            var platformCode = row.get(2, String.class) == null ? "" : row.get(2, String.class);
            stores.add(
                new ReportingStoreComparisonModels.StoreSummary(
                    code,
                    row.get(1, String.class) == null ? "" : row.get(1, String.class),
                    platformCode,
                    row.get(3, String.class) == null ? "" : row.get(3, String.class),
                    (row.get(4, Long.class) != null && row.get(4, Long.class) > 0),
                    row.get(5, OffsetDateTime.class)));
            var counts = platformCounts.computeIfAbsent(platformCode, k -> new int[1]);
            counts[0]++;
            if (row.get(3, String.class) != null) platformNames.put(platformCode, row.get(3, String.class));
          }
          var platforms = new ArrayList<ReportingStoreComparisonModels.PlatformOption>();
          for (var entry : platformCounts.entrySet()) {
            platforms.add(
                new ReportingStoreComparisonModels.PlatformOption(
                    entry.getKey(),
                    platformNames.getOrDefault(entry.getKey(), ""),
                    entry.getValue()[0]));
          }
          return new ReportingStoreComparisonModels.StoreListResponse(stores, platforms);
        });
  }

  // ==========================================================================
  // 对比矩阵
  // ==========================================================================

  /**
   * 多店对比：对比矩阵 + 排名 + 差异归因提示 + 掉队/增长店铺。
   *
   * @param platformCode 只看某个平台（可空 ⇒ 全部平台）
   * @param baseCurrency 本位币（可空 ⇒ 只按币种分别报，不做跨币种折算）
   */
  public ReportingStoreComparisonModels.ComparisonResponse comparison(
      java.util.UUID tenantId,
      LocalDate from,
      LocalDate to,
      String platformCode,
      String baseCurrency) {
    var previous = ReportingStoreComparisonRefreshService.previousWindow(from, to);
    return tenants.run(
        tenantId,
        () -> {
          refresh.seedDefinitions(tenantId);
          var definitions = definitionMap(tenantId);
          var profiles = profileMap(tenantId);

          var current = facts(tenantId, from, to);
          var previousFacts = facts(tenantId, previous.from(), previous.to());

          var currencies = new java.util.TreeSet<String>();
          for (var key : current.keySet()) currencies.add(key.currency());
          var rates = baseCurrency == null ? List.<ReportingStoreComparisonModels.CurrencyRate>of()
              : rates(tenantId, baseCurrency, to, currencies);

          var memo = new LinkedHashMap<String, Map<Key, BigDecimal>>();
          var memoPrevious = new LinkedHashMap<String, Map<Key, BigDecimal>>();

          // ---- 每个店铺的指标取值（按币种）----
          var perStore = new LinkedHashMap<String, LinkedHashMap<String, BigDecimal>>();
          for (var shopCode : profiles.keySet()) {
            perStore.put(shopCode, new LinkedHashMap<String, BigDecimal>());
          }
          for (var definition : definitions.values()) {
            var scope = new Scope(definition.metricCode());
            if ("WINDOW_GROWTH".equals(definition.aggregation())) {
              // ⚠️ 窗口环比比的是**被比较指标**（base_metric_code）的两个窗口：
              //   (当前窗口值 − 上一等长窗口值) ÷ 上一等长窗口值。
              //   上一窗口缺事实或为 0 ⇒ **算不出**（null），不用 0% 冒充。
              var base = definitions.get(definition.baseMetricCode());
              if (base == null) continue;
              var baseScope = new Scope(base.metricCode());
              var currentBase =
                  values(tenantId, baseScope, current, definitions, memo, 0);
              var previousBase =
                  values(tenantId, baseScope, previousFacts, definitions, memoPrevious, 0);
              for (var entry : currentBase.entrySet()) {
                var shopCode = entry.getKey().shopCode();
                var currency = entry.getKey().currency();
                if (!perStore.containsKey(shopCode)) continue;
                var previousBaseValue = previousBase.get(entry.getKey());
                if (previousBaseValue == null || previousBaseValue.signum() == 0) {
                  perStore.get(shopCode).put(scope.metricCode() + "|" + currency, null);
                  continue;
                }
                var growth =
                    entry.getValue()
                        .subtract(previousBaseValue)
                        .divide(previousBaseValue, 8, RoundingMode.HALF_UP)
                        .movePointRight(2)
                        .setScale(4, RoundingMode.HALF_UP);
                perStore.get(shopCode).put(scope.metricCode() + "|" + currency, growth);
              }
              continue;
            }
            var values = values(tenantId, scope, current, definitions, memo, 0);
            for (var entry : values.entrySet()) {
              var shopCode = entry.getKey().shopCode();
              var currency = entry.getKey().currency();
              if (!perStore.containsKey(shopCode)) continue;
              perStore.get(shopCode).put(scope.metricCode() + "|" + currency, entry.getValue());
            }
          }

          // ---- 每个店铺出现过的币种 ----
          var shopCurrencies = new LinkedHashMap<String, List<String>>();
          for (var shopCode : perStore.keySet()) {
            var set = new java.util.TreeSet<String>();
            for (var key : perStore.get(shopCode).keySet()) {
              set.add(key.substring(key.indexOf('|') + 1));
            }
            shopCurrencies.put(shopCode, new ArrayList<>(set));
          }

          // ---- 排名（**币种内**做：跨币种的大小本来就不可比）----
          var ranks = new LinkedHashMap<String, Map<String, Integer>>();
          for (var definition : definitions.values()) {
            var byCurrency = new LinkedHashMap<String, List<Map.Entry<String, BigDecimal>>>();
            for (var shopCode : perStore.keySet()) {
              for (var currency : shopCurrencies.get(shopCode)) {
                var value = perStore.get(shopCode).get(definition.metricCode() + "|" + currency);
                if (value == null) continue;
                byCurrency
                    .computeIfAbsent(currency, k -> new ArrayList<>())
                    .add(Map.entry(shopCode, value));
              }
            }
            var rankMap = new LinkedHashMap<String, Integer>();
            for (var entry : byCurrency.entrySet()) {
              var sorted = new ArrayList<>(entry.getValue());
              sorted.sort(
                  Comparator.comparing((Map.Entry<String, BigDecimal> e) -> e.getValue())
                      .reversed()
                      .thenComparing(Map.Entry::getKey));
              var rank = 1;
              for (var item : sorted) {
                rankMap.put(item.getKey() + "|" + entry.getKey(), rank);
                rank++;
              }
            }
            ranks.put(definition.metricCode(), rankMap);
          }

          // ---- 中位数（归因提示的基准）----
          var medians = medians(perStore, definitions.keySet());

          // ---- 组装行 ----
          var rows = new ArrayList<ReportingStoreComparisonModels.StoreComparisonRow>();
          for (var shopCode : perStore.keySet()) {
            var profile = profiles.get(shopCode);
            if (platformCode != null
                && !platformCode.isBlank()
                && !platformCode.equalsIgnoreCase(profile.platformCode())) {
              continue;
            }
            var metrics = new ArrayList<ReportingStoreComparisonModels.StoreMetricValue>();
            for (var definition : definitions.values()) {
              for (var currency : shopCurrencies.get(shopCode)) {
                var value = perStore.get(shopCode).get(definition.metricCode() + "|" + currency);
                var availability = "NO_DATA";
                String note = "该店铺在这个窗口、这个币种下没有该指标的事实数据。";
                if (value != null) {
                  availability = "AVAILABLE";
                  note = "";
                }
                var rank = ranks.get(definition.metricCode()).get(shopCode + "|" + currency);
                var rate = rateFor(rates, currency);
                var baseValue =
                    value == null || rate == null ? null : value.multiply(rate.rate());
                metrics.add(
                    new ReportingStoreComparisonModels.StoreMetricValue(
                        definition.metricCode(),
                        currency,
                        value,
                        baseValue,
                        rate == null ? "" : baseCurrency,
                        rank,
                        availability,
                        note));
              }
            }
            var scaleRank =
                ranks
                    .get(ReportingStoreComparisonCatalog.STORE_GMV_PLATFORM)
                    .get(shopCode + "|" + primaryCurrency(shopCurrencies.get(shopCode)));
            var growthRank =
                ranks
                    .get(ReportingStoreComparisonCatalog.STORE_GMV_GROWTH)
                    .get(shopCode + "|" + primaryCurrency(shopCurrencies.get(shopCode)));
            var growth =
                perStore
                    .get(shopCode)
                    .get(
                        ReportingStoreComparisonCatalog.STORE_GMV_GROWTH
                            + "|"
                            + primaryCurrency(shopCurrencies.get(shopCode)));
            var classification = classify(growth);
            var classificationNote = classifyNote(growth, classification);
            rows.add(
                new ReportingStoreComparisonModels.StoreComparisonRow(
                    shopCode,
                    profile.shopName(),
                    profile.platformCode(),
                    profile.platformName(),
                    shopCurrencies.get(shopCode),
                    metrics,
                    scaleRank,
                    growthRank,
                    classification,
                    classificationNote,
                    attributionHints(
                        shopCode, perStore, medians, profile.platformCode(), shopCurrencies)));
          }
          rows.sort(
              Comparator.comparing(
                      (ReportingStoreComparisonModels.StoreComparisonRow r) -> r.scaleRank() == null ? Integer.MAX_VALUE : r.scaleRank())
                  .thenComparing(ReportingStoreComparisonModels.StoreComparisonRow::shopCode));

          // ---- 平台分组 ----
          var groups = new ArrayList<ReportingStoreComparisonModels.PlatformGroup>();
          var grouped = new LinkedHashMap<String, List<ReportingStoreComparisonModels.StoreComparisonRow>>();
          for (var row : rows) {
            grouped.computeIfAbsent(row.platformCode(), k -> new ArrayList<>()).add(row);
          }
          for (var entry : grouped.entrySet()) {
            var platformName =
                entry.getValue().isEmpty() ? "" : entry.getValue().get(0).platformName();
            groups.add(
                new ReportingStoreComparisonModels.PlatformGroup(
                    entry.getKey(),
                    platformName,
                    entry.getValue(),
                    entry.getValue().size(),
                    platformCaliberNote(entry.getKey(), platformName, entry.getValue())));
          }

          // ---- 指标排名榜 ----
          var rankings = new ArrayList<ReportingStoreComparisonModels.MetricRanking>();
          for (var definition : definitions.values()) {
            var entries = new ArrayList<ReportingStoreComparisonModels.RankingEntry>();
            var byCurrency = new LinkedHashMap<String, List<Map.Entry<String, BigDecimal>>>();
            for (var shopCode : perStore.keySet()) {
              for (var currency : shopCurrencies.get(shopCode)) {
                var value = perStore.get(shopCode).get(definition.metricCode() + "|" + currency);
                if (value == null) continue;
                byCurrency
                    .computeIfAbsent(currency, k -> new ArrayList<>())
                    .add(Map.entry(shopCode, value));
              }
            }
            for (var byCur : byCurrency.entrySet()) {
              var sorted = new ArrayList<>(byCur.getValue());
              sorted.sort(
                  Comparator.comparing((Map.Entry<String, BigDecimal> e) -> e.getValue())
                      .reversed()
                      .thenComparing(Map.Entry::getKey));
              var rank = 1;
              for (var item : sorted) {
                var profile = profiles.get(item.getKey());
                entries.add(
                    new ReportingStoreComparisonModels.RankingEntry(
                        rank,
                        item.getKey(),
                        profile == null ? "" : profile.shopName(),
                        profile == null ? "" : profile.platformCode(),
                        byCur.getKey(),
                        item.getValue()));
                rank++;
              }
            }
            rankings.add(
                new ReportingStoreComparisonModels.MetricRanking(
                    definition.metricCode(),
                    definition.displayName(),
                    definition.unit(),
                    entries,
                    entries.isEmpty() ? "本窗口内没有任何店铺的这个指标可比（没有事实或分母为 0）。" : ""));
          }

          // ---- 掉队店铺 / 增长店铺 ----
          var laggards = new ArrayList<ReportingStoreComparisonModels.StoreFlag>();
          var growthStores = new ArrayList<ReportingStoreComparisonModels.StoreFlag>();
          for (var row : rows) {
            if ("LAGGARD".equals(row.classification())) {
              laggards.add(
                  new ReportingStoreComparisonModels.StoreFlag(
                      row.shopCode(), row.shopName(), row.platformCode(), row.classificationNote()));
            } else if ("GROWTH".equals(row.classification())) {
              growthStores.add(
                  new ReportingStoreComparisonModels.StoreFlag(
                      row.shopCode(), row.shopName(), row.platformCode(), row.classificationNote()));
            }
          }

          var dataUpdatedAt = dataUpdatedAt(tenantId);
          var caliberNotes = caliberNotes(rows, groups);
          var status = "PROJECTED";
          var statusNote = "";
          String emptyReason = null;
          String emptyNote = "";
          if (rows.isEmpty()) {
            status = "NOT_PROJECTED";
            emptyReason =
                profiles.isEmpty() ? "NO_STORES" : "NOT_PROJECTED";
            emptyNote =
                profiles.isEmpty()
                    ? "这个租户还没有任何店铺档案 —— 店铺归属来自 channel 的店铺清单，"
                        + "还没有投影过就无从对比。"
                    : "这个租户已有店铺档案，但这个窗口还没有投影任何店铺维度事实 —— "
                        + "请先刷新该窗口的店铺维度投影。";
            statusNote = emptyNote;
          }

          return new ReportingStoreComparisonModels.ComparisonResponse(
              from,
              to,
              previous.from(),
              previous.to(),
              rows,
              groups,
              rankings,
              laggards,
              growthStores,
              definitionViews(definitions),
              caliberNotes,
              dataUpdatedAt,
              "最近一次店铺维度投影落库时刻（reporting_store_metric_fact.updated_at 取较晚者）。",
              status,
              statusNote,
              baseCurrency,
              rates,
              emptyReason,
              emptyNote);
        });
  }

  // ==========================================================================
  // 指标求值
  // ==========================================================================

  /** 一个口径定义。 */
  private record Definition(
      String metricCode,
      String displayName,
      String unit,
      String aggregation,
      String numeratorMetricCode,
      String denominatorMetricCode,
      String baseMetricCode,
      boolean currencyAware,
      String description,
      String ownerModule) {}

  /** 一个（店铺, 币种）键。 */
  private record Key(String shopCode, String currency) {}

  /** 求值作用域。 */
  private record Scope(String metricCode) {}

  /** 一条事实桶。 */
  private record Bucket(LocalDate date, BigDecimal value, BigDecimal quantity) {}

  private Map<String, Definition> definitionMap(java.util.UUID tenantId) {
    var out = new LinkedHashMap<String, Definition>();
    var rows =
        sql.fetch(
            """
            select metric_code, display_name, unit, aggregation,
                   numerator_metric_code, denominator_metric_code, base_metric_code,
                   currency_aware, description, owner_module
              from reporting_store_metric_definition
             where tenant_id = ? and enabled
             order by metric_code
            """,
            tenantId);
    for (var row : rows) {
      out.put(
          row.get(0, String.class),
          new Definition(
              row.get(0, String.class),
              row.get(1, String.class),
              row.get(2, String.class),
              row.get(3, String.class),
              row.get(4, String.class),
              row.get(5, String.class),
              row.get(6, String.class),
              Boolean.TRUE.equals(row.get(7, Boolean.class)),
              row.get(8, String.class),
              row.get(9, String.class)));
    }
    return out;
  }

  /** 一个店铺的档案。 */
  private record Profile(String shopCode, String shopName, String platformCode, String platformName) {}

  private Map<String, Profile> profileMap(java.util.UUID tenantId) {
    var out = new LinkedHashMap<String, Profile>();
    var rows =
        sql.fetch(
            """
            select shop_code, shop_name, platform_code, platform_name
              from reporting_store_profile
             where tenant_id = ?
             order by shop_code
            """,
            tenantId);
    for (var row : rows) {
      out.put(
          row.get(0, String.class),
          new Profile(
              row.get(0, String.class),
              row.get(1, String.class) == null ? "" : row.get(1, String.class),
              row.get(2, String.class) == null ? "" : row.get(2, String.class),
              row.get(3, String.class) == null ? "" : row.get(3, String.class)));
    }
    return out;
  }

  /** 读一个窗口内的事实（按 指标 + 店铺 + 币种 → 日桶 建索引）。 */
  private Map<Key, List<Bucket>> facts(
      java.util.UUID tenantId, LocalDate from, LocalDate to) {
    var out = new LinkedHashMap<Key, List<Bucket>>();
    var rows =
        sql.fetch(
            """
            select shop_code, metric_code, currency, bucket_date, sum(value), sum(quantity)
              from reporting_store_metric_fact
             where tenant_id = ? and bucket_date >= ? and bucket_date <= ?
             group by shop_code, metric_code, currency, bucket_date
             order by shop_code, metric_code, currency, bucket_date
            """,
            tenantId,
            from,
            to);
    for (var row : rows) {
      var key =
          new Key(row.get(1, String.class) + "|" + row.get(0, String.class), row.get(2, String.class));
      out.computeIfAbsent(key, k -> new ArrayList<>())
          .add(
              new Bucket(
                  row.get(3, LocalDate.class),
                  row.get(4, BigDecimal.class),
                  row.get(5, BigDecimal.class)));
    }
    return out;
  }

  /**
   * 求一个指标在某个窗口下的取值（按店铺 × 币种）。
   *
   * <p>⚠️ **递归**：派生型指标的分子分母本身可能是派生型。深度上限
   * {@value #MAX_DERIVE_DEPTH}，超了直接报错 —— 口径表被改坏时**要炸**。
   */
  private Map<Key, BigDecimal> values(
      java.util.UUID tenantId,
      Scope scope,
      Map<Key, List<Bucket>> facts,
      Map<String, Definition> definitions,
      Map<String, Map<Key, BigDecimal>> memo,
      int depth) {
    if (depth > MAX_DERIVE_DEPTH) {
      throw new IllegalStateException(
          "店铺对比指标派生层级超过上限，可能是口径表被改坏了：" + scope.metricCode());
    }
    var memoKey = scope.metricCode();
    var cached = memo.get(memoKey);
    if (cached != null) return cached;
    var definition = definitions.get(scope.metricCode());
    var result = new LinkedHashMap<Key, BigDecimal>();
    if (definition != null) {
      switch (definition.aggregation()) {
        case "SUM" -> {
          for (var entry : facts.entrySet()) {
            if (!entry.getKey().shopCode().startsWith(scope.metricCode() + "|")) continue;
            var total = BigDecimal.ZERO;
            for (var bucket : entry.getValue()) total = total.add(bucket.value());
            result.put(
                new Key(
                    entry.getKey().shopCode().substring(scope.metricCode().length() + 1),
                    entry.getKey().currency()),
                total);
          }
        }
        case "LAST" -> {
          for (var entry : facts.entrySet()) {
            if (!entry.getKey().shopCode().startsWith(scope.metricCode() + "|")) continue;
            Bucket latest = null;
            for (var bucket : entry.getValue()) {
              if (latest == null || bucket.date().isAfter(latest.date())) latest = bucket;
            }
            if (latest == null) continue;
            result.put(
                new Key(
                    entry.getKey().shopCode().substring(scope.metricCode().length() + 1),
                    entry.getKey().currency()),
                latest.value());
          }
        }
        case "DERIVED_DIFF" -> {
          var numerator =
              values(
                  tenantId,
                  new Scope(definition.numeratorMetricCode()),
                  facts,
                  definitions,
                  memo,
                  depth + 1);
          var denominator =
              values(
                  tenantId,
                  new Scope(definition.denominatorMetricCode()),
                  facts,
                  definitions,
                  memo,
                  depth + 1);
          for (var entry : numerator.entrySet()) {
            var other = denominator.get(entry.getKey());
            // ⚠️ 分子分母**任一缺失就不算**（不用 0 冒充）。
            if (other == null) continue;
            result.put(entry.getKey(), entry.getValue().subtract(other));
          }
        }
        case "DERIVED_RATIO" -> {
          var numerator =
              values(
                  tenantId,
                  new Scope(definition.numeratorMetricCode()),
                  facts,
                  definitions,
                  memo,
                  depth + 1);
          var denominator =
              values(
                  tenantId,
                  new Scope(definition.denominatorMetricCode()),
                  facts,
                  definitions,
                  memo,
                  depth + 1);
          for (var entry : numerator.entrySet()) {
            var other = denominator.get(entry.getKey());
            if (other == null || other.signum() == 0) continue;
            var ratio = entry.getValue().divide(other, 8, RoundingMode.HALF_UP);
            if ("PERCENT".equals(definition.unit())) {
              ratio = ratio.movePointRight(2).setScale(4, RoundingMode.HALF_UP);
            } else if ("RATIO".equals(definition.unit())) {
              ratio = ratio.setScale(4, RoundingMode.HALF_UP);
            }
            result.put(entry.getKey(), ratio);
          }
        }
        default -> {}
      }
    }
    memo.put(memoKey, result);
    return result;
  }

  // ==========================================================================
  // 中位数 / 归因 / 分类
  // ==========================================================================

  /** 每个指标（按币种）的中位数 —— 归因提示的基准。 */
  private Map<String, BigDecimal> medians(
      Map<String, LinkedHashMap<String, BigDecimal>> perStore, Iterable<String> metricCodes) {
    var out = new LinkedHashMap<String, BigDecimal>();
    for (var metricCode : metricCodes) {
      var values = new ArrayList<BigDecimal>();
      for (var shop : perStore.values()) {
        for (var entry : shop.entrySet()) {
          if (!entry.getKey().startsWith(metricCode + "|")) continue;
          if (entry.getValue() == null) continue;
          values.add(entry.getValue());
        }
      }
      if (values.isEmpty()) continue;
      values.sort(Comparator.naturalOrder());
      var middle = values.size() / 2;
      BigDecimal median;
      if (values.size() % 2 == 1) {
        median = values.get(middle);
      } else {
        median =
            values.get(middle - 1).add(values.get(middle)).divide(new BigDecimal("2"), 8, RoundingMode.HALF_UP);
      }
      out.put(metricCode, median);
    }
    return out;
  }

  /** 差异归因提示：这一行相对同平台中位数差在哪。 */
  private List<String> attributionHints(
      String shopCode,
      Map<String, LinkedHashMap<String, BigDecimal>> perStore,
      Map<String, BigDecimal> medians,
      String platformCode,
      Map<String, List<String>> shopCurrencies) {
    var hints = new ArrayList<String>();
    var currency = primaryCurrency(shopCurrencies.get(shopCode));
    var shop = perStore.get(shopCode);
    if (shop == null) return hints;
    addHint(hints, shop, medians, currency, ReportingStoreComparisonCatalog.STORE_GMV_PLATFORM,
        "成交金额", "低于", "高于");
    addHint(hints, shop, medians, currency, ReportingStoreComparisonCatalog.STORE_RETURN_RATE,
        "退货率", "低于", "高于");
    addHint(hints, shop, medians, currency, ReportingStoreComparisonCatalog.STORE_AD_ACOS,
        "广告花费占比", "低于", "高于");
    addHint(hints, shop, medians, currency, ReportingStoreComparisonCatalog.STORE_AD_ROAS,
        "广告产出比", "低于", "高于");
    addHint(hints, shop, medians, currency, ReportingStoreComparisonCatalog.STORE_CALIBER_GAP,
        "口径差异额", "低于", "高于");
    if (hints.isEmpty()) {
      hints.add("该店铺在本次窗口内没有可与中位数比较的指标（缺事实或分母为 0）。");
    }
    return hints;
  }

  private void addHint(
      List<String> hints,
      Map<String, BigDecimal> shop,
      Map<String, BigDecimal> medians,
      String currency,
      String metricCode,
      String label,
      String lowWord,
      String highWord) {
    var value = shop.get(metricCode + "|" + currency);
    var median = medians.get(metricCode);
    if (value == null || median == null) return;
    if (median.signum() == 0) return;
    var diff = value.subtract(median);
    if (diff.signum() == 0) return;
    hints.add(
        label
            + (diff.signum() > 0 ? highWord : lowWord)
            + "中位数 "
            + (diff.signum() > 0 ? "" : "-")
            + diff.abs().setScale(4, RoundingMode.HALF_UP)
            + "（中位数 "
            + median.setScale(4, RoundingMode.HALF_UP)
            + "，币种 "
            + currency
            + "）");
  }

  /** 分类：增长店铺 / 掉队店铺 / 稳定 / 不可比。 */
  private String classify(BigDecimal growth) {
    if (growth == null) return "UNCLASSIFIED";
    var ratio = growth.movePointLeft(2);
    if (ratio.compareTo(GROWTH_THRESHOLD) >= 0) return "GROWTH";
    if (ratio.compareTo(LAGGARD_THRESHOLD) <= 0) return "LAGGARD";
    return "STABLE";
  }

  private String classifyNote(BigDecimal growth, String classification) {
    if (growth == null) {
      return "不可比：上一等长窗口没有该店铺的成交金额事实（或金额为 0），算不出环比增长率。";
    }
    return switch (classification) {
      case "GROWTH" -> "成交金额环比增长率 ≥ +20%，判为增长店铺。";
      case "LAGGARD" -> "成交金额环比增长率 ≤ −20%，判为掉队店铺。";
      default -> "成交金额环比增长率在 ±20% 之间，判为稳定。";
    };
  }

  // ==========================================================================
  // 汇率 / 数据更新时间 / 口径说明
  // ==========================================================================

  /** 取生效日不晚于窗口末的汇率快照（**带时点**）。 */
  private List<ReportingStoreComparisonModels.CurrencyRate> rates(
      java.util.UUID tenantId, String baseCurrency, LocalDate to, java.util.Set<String> currencies) {
    var out = new ArrayList<ReportingStoreComparisonModels.CurrencyRate>();
    for (var currency : currencies) {
      if (currency.equals(baseCurrency)) {
        out.add(
            new ReportingStoreComparisonModels.CurrencyRate(
                currency, baseCurrency, BigDecimal.ONE, null));
        continue;
      }
      var row =
          sql.fetchOne(
              """
              select currency, base_currency, rate, as_of
                from reporting_currency_rate_snapshot
               where tenant_id = ? and currency = ? and base_currency = ?
                 and as_of <= ?
               order by as_of desc
               limit 1
              """,
              tenantId,
              currency,
              baseCurrency,
              to.atStartOfDay(java.time.ZoneOffset.UTC).toOffsetDateTime());
      if (row == null) continue;
      out.add(
          new ReportingStoreComparisonModels.CurrencyRate(
              row.get(0, String.class),
              row.get(1, String.class),
              row.get(2, BigDecimal.class),
              row.get(3, OffsetDateTime.class)));
    }
    return out;
  }

  private ReportingStoreComparisonModels.CurrencyRate rateFor(
      List<ReportingStoreComparisonModels.CurrencyRate> rates, String currency) {
    for (var rate : rates) {
      if (rate.currency().equals(currency)) return rate;
    }
    return null;
  }

  /** 最近一次投影落库时刻（卷 13 卷首「数据延迟要显示」）。 */
  private OffsetDateTime dataUpdatedAt(java.util.UUID tenantId) {
    var row =
        sql.fetchOne(
            """
            select max(updated_at) from reporting_store_metric_fact where tenant_id = ?
            """,
            tenantId);
    if (row == null) return null;
    return row.get(0, OffsetDateTime.class);
  }

  /** 跨平台口径差异说明（蓝图的加粗要求）。 */
  private List<String> caliberNotes(
      List<ReportingStoreComparisonModels.StoreComparisonRow> rows,
      List<ReportingStoreComparisonModels.PlatformGroup> groups) {
    var notes = new ArrayList<String>();
    notes.add(
        "矩阵里的「平台侧成交金额」是**平台自己报的口径**，「还原口径成交金额」是我方从平台订单"
            + "还原出来的口径，两者之差列在「口径差异额」里 —— 跨平台对比时这个差必须一起看，"
            + "否则会把口径差读成经营差。");
    var multiCurrency = false;
    for (var row : rows) {
      if (row.currencies().size() > 1) {
        multiCurrency = true;
        break;
      }
    }
    if (multiCurrency) {
      notes.add(
          "本次对比涉及**多个币种**：金额按币种分别列出，**排名也在币种内做** —— "
              + "一个 USD 店铺和一个 EUR 店铺的大小本来就不可比。");
    }
    if (groups.size() > 1) {
      notes.add(
          "本次对比涉及 "
              + groups.size()
              + " 个平台分组。不同平台的 GMV 口径（是否含运费、是否含税、是否含平台佣金）"
              + "由平台各自决定，**分组之间的大小差异不能直接当成经营能力差异**。");
    }
    return notes;
  }

  /** 平台分组上的口径说明。 */
  private String platformCaliberNote(
      String platformCode, String platformName, List<ReportingStoreComparisonModels.StoreComparisonRow> stores) {
    if (platformCode == null || platformCode.isBlank()) {
      return "这个分组的店铺**尚未确认平台归属**（上游只在广告活动里带店铺的平台，"
          + "没有任何广告活动的店铺拿不到平台）—— 它的口径差异无法按平台标注。";
    }
    var currencies = new java.util.TreeSet<String>();
    for (var store : stores) currencies.addAll(store.currencies());
    var currencyNote =
        currencies.size() > 1
            ? "这个平台内的店铺跨 " + currencies.size() + " 个币种，排名按币种分别做。"
            : "这个平台内的店铺都在 " + currencies + " 计价，排名可直接横向比较。";
    return platformName
        + "（"
        + platformCode
        + "）平台的成交金额是**平台侧报告口径**；"
        + currencyNote
        + " 各平台之间的 GMV 口径差异请看矩阵里的「口径差异额」。";
  }

  /** 店铺的首要币种（排名用；多币种时取字典序第一个，并另有说明）。 */
  private String primaryCurrency(List<String> currencies) {
    if (currencies == null || currencies.isEmpty()) return "";
    return currencies.get(0);
  }
}
