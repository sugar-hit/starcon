package com.starcon.reporting;

import com.starcon.tenancy.Ids;
import com.starcon.tenancy.TenantTransactions;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 13.3.1.2 生命周期与分层的**读模型**（蓝图输出句：分层结果 + 各层建议）。
 *
 * <p><b>🔴 口径纪律：本类不登记任何指标口径</b>。销量 / 销售额来自 13.2.2.2 的
 * {@code reporting_product_metric_definition}，毛利来自 13.3.1.1 的
 * {@code reporting_product_performance_metric_definition}（派生口径：销售额 − 出库成本）。
 * 本类只**按口径表里登记的分子分母求值**（{@link #evaluate}），**不写死任何公式** ——
 * 租户改了 13.3.1.1 的毛利口径，本模块自动跟着改。
 *
 * <p><b>🔴 上游是「同服务内已落成的投影」，不是 HTTP</b>：本模块读的是
 * 13.3.1.1 的日事实（{@code reporting_product_performance_fact}）与时点快照
 * （{@code reporting_product_performance_snapshot}）、13.2.2.2 的商品维度档案
 * （{@code reporting_product_dimension}）与日事实（{@code reporting_product_metric_fact}）。
 * 它们没刷过 ⇒ 本模块报「没有事实 / 口径未投影」，**不是 0**。
 *
 * <p><b>🔴 绝不跨币种相加</b>：金额按币种分别统计；件数（与货币无关）才跨币种合计。
 * ABC 按金额**在同一个币种内部**排名 —— 金额跨币种不可比。
 *
 * <p><b>🔴 「算不出来」与「等于 0」分开</b>：档位算不出来时 {@code segment} 是 null，
 * {@code availability} 说明是哪一种，{@code note} 写清原因与归属模块。
 */
@Service
public class ReportingLifecycleSegmentationService {

  /** 比率刻度（与库里 numeric(24,4) 对齐）。 */
  private static final int SCALE = 4;

  /** 数量刻度（与库里 numeric(19,6) 对齐）。 */
  private static final int QUANTITY_SCALE = 6;

  /** 判定窗口最多多少天。 */
  static final int MAX_WINDOW_DAYS = 730;

  /** 🔴 **比例一律是百分数**（与 13.3.1.1 的 PERCENT 口径同一纪律）。 */
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  /** 分页上限。 */
  static final int MAX_PAGE_SIZE = 200;

  private final DSLContext sql;
  private final TenantTransactions tenants;

  public ReportingLifecycleSegmentationService(DSLContext sql, TenantTransactions tenants) {
    this.sql = sql;
    this.tenants = tenants;
  }

  // ==========================================================================
  // 内部形状
  // ==========================================================================

  /** 口径定义（**两张口径表合并后的运行时真相**，带「事实住在哪张表」的标记）。 */
  private record Definition(
      String metricCode,
      String displayName,
      String unit,
      String aggregation,
      String numeratorMetricCode,
      String denominatorMetricCode,
      boolean currencyAware,
      boolean available,
      String ownerModule,
      String description,
      String availabilityNote,
      /** 事实表归属：PRODUCT = 13.2.2.2 的 reporting_product_metric_fact；PERFORMANCE = 13.3.1.1 的。 */
      String home) {}

  /** 一条事实（已按口径把「数量列 / 金额列」分开带）。 */
  private record FactRow(
      String metricCode, UUID dimensionId, String currency, LocalDate bucketDate,
      BigDecimal value, BigDecimal quantity) {}

  /** 商品维度档案的一行。 */
  private record ProductDim(UUID id, String key, String name) {}

  /**
   * 分层的输入宇宙。
   *
   * @param products 该维度层的商品（SKU 层 = SKU 自己；SPU 层 = SPU）
   * @param childSkus 每个商品由哪些 SKU 组成（SKU 层 = 自己）
   */
  private record Universe(Map<UUID, ProductDim> products, Map<UUID, List<UUID>> childSkus) {}

  /**
   * 一次求值的结果。
   *
   * @param values 币种 → 取值（**空串 = 与币种无关**）
   * @param failures 币种 → 为什么算不出来（**null 的取值有原因，不是 0**）
   * @param globalFailure 整个指标都算不出来的原因（口径未登记 / 口径不可用 / 递归环）
   */
  private record Eval(
      Map<String, BigDecimal> values, Map<String, String> failures, String globalFailure) {

    static Eval empty() {
      return new Eval(new LinkedHashMap<>(), new LinkedHashMap<>(), null);
    }

    static Eval global(String reason) {
      return new Eval(new LinkedHashMap<>(), new LinkedHashMap<>(), reason);
    }
  }

  /** 规则（运行时真相是那张表，这只是读出来的一行）。 */
  private record RuleRow(
      UUID id,
      String ruleCode,
      String ruleName,
      String dimensionType,
      int version,
      int windowDays,
      int newMaxDaysSinceFirstSale,
      int firstSaleLookbackDays,
      int minHistoryDays,
      BigDecimal decliningMinDeclineRatio,
      BigDecimal hotMinSalesShareRatio,
      BigDecimal strongMinSalesShareRatio,
      BigDecimal steadyMinSalesShareRatio,
      BigDecimal slowMaxSalesPerDay,
      BigDecimal abcACumulativeRatio,
      BigDecimal abcBCumulativeRatio,
      String quantityMetricCode,
      String amountMetricCode,
      String grossProfitMetricCode,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      boolean enabled,
      String createdBy,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 一个商品的计算结果（落库前）。 */
  private record Computed(
      UUID dimensionId,
      String dimensionKey,
      String dimensionName,
      BigDecimal salesQuantity,
      LocalDate firstSaleDate,
      int historyDays,
      BigDecimal recentQuantity,
      BigDecimal priorQuantity,
      BigDecimal declineRatio,
      BigDecimal salesShare,
      String segment,
      String availability,
      String note,
      String action,
      String actionNote,
      BigDecimal onHand,
      BigDecimal agingDays,
      OffsetDateTime sourceObservedAt,
      Map<String, BigDecimal> salesByCurrency,
      Map<String, BigDecimal> grossProfitByCurrency,
      Map<String, String> grossProfitFailures,
      String grossProfitGlobalFailure) {}

  // ==========================================================================
  // 刷新（写分层结果）
  // ==========================================================================

  /**
   * 计算并落库分层结果。
   *
   * <p>🔴 **窗口外的事实不参与计算**：销量 / 销售额 / 毛利 / 份额全部只取
   * {@code [from, to]}。新品判定要回看「首次销售日」、衰退判定要比「对比窗口」——
   * 这两段是**规则里显式配置的独立窗口**，响应里会写清楚。
   *
   * <p>🔴 **幂等**：同一窗口重刷先删旧行再整段重写（不留上一轮的残影）。
   */
  public List<ReportingLifecycleSegmentationModels.LifecycleSegmentationRefreshResult> refresh(
      UUID tenantId, LocalDate from, LocalDate to, String dimensionType) {
    return tenants.run(
        tenantId,
        () -> {
          var definitions = loadDefinitions(tenantId);
          var rule = ensureRule(tenantId, dimensionType, definitions);
          if (rule == null) {
            var missing = missingCalibers(definitions);
            return List.of(
                new ReportingLifecycleSegmentationModels.LifecycleSegmentationRefreshResult(
                    "13.3.1.1 产品表现分析 + 13.2.2.2 商品维度（服务内投影）",
                    0,
                    0,
                    "FAILED",
                    "🔴 **上游口径未投影**：分层依据的口径码（"
                        + String.join(" / ", missing)
                        + "）还没在 13.3.1.1 / 13.2.2.2 的口径表里登记 —— "
                        + "先刷新那两个模块，再回来分层。**不是 0，是还没准备好。**"));
          }

          var computed = compute(tenantId, rule, from, to, definitions);
          writeResults(tenantId, rule, from, to, computed);

          return List.of(
              new ReportingLifecycleSegmentationModels.LifecycleSegmentationRefreshResult(
                  "13.3.1.1 产品表现分析 + 13.2.2.2 商品维度（服务内投影）",
                  computed.size(),
                  computed.size(),
                  "SUCCEEDED",
                  "已按规则 "
                      + rule.ruleCode()
                      + "（v"
                      + rule.version()
                      + "）把 "
                      + computed.size()
                      + " 个商品分层；档位与建议动作全部落库，窗口 "
                      + from
                      + " ~ "
                      + to
                      + "（窗口外的事实不参与计算）。"));
        });
  }

  /** 口径未登记的指标码（错误信息里点名，**不说「未接入」这种空话**）。 */
  private List<String> missingCalibers(Map<String, Definition> definitions) {
    var missing = new ArrayList<String>();
    for (var code :
        List.of(
            ReportingLifecycleSegmentationCatalog.QUANTITY_METRIC_CODE,
            ReportingLifecycleSegmentationCatalog.AMOUNT_METRIC_CODE,
            ReportingLifecycleSegmentationCatalog.GROSS_PROFIT_METRIC_CODE)) {
      if (!definitions.containsKey(code)) missing.add(code);
    }
    return missing;
  }

  /**
   * 取生效规则；没有就按标准规则播种一份。
   *
   * <p>🔴 **播种前先验口径**：口径码还没登记时**不播种** —— 播种一条指向不存在口径的规则，
   * 只会让分层永远「算不出来」，而没人知道是规则坏了。
   */
  private RuleRow ensureRule(
      UUID tenantId, String dimensionType, Map<String, Definition> definitions) {
    var existing = loadEnabledRule(tenantId, dimensionType);
    if (existing != null) return existing;
    var standard = ReportingLifecycleSegmentationCatalog.standardOf(dimensionType);
    if (standard == null) return null;
    var missing = missingCalibers(definitions);
    if (!missing.isEmpty()) return null;

    var id = Ids.next();
    var today = LocalDate.now();
    sql.execute(
        """
        insert into reporting_lifecycle_segmentation_rule
          (id,tenant_id,rule_code,rule_name,dimension_type,version,window_days,
           new_max_days_since_first_sale,first_sale_lookback_days,min_history_days,
           declining_min_decline_ratio,hot_min_sales_share_ratio,strong_min_sales_share_ratio,
           steady_min_sales_share_ratio,slow_max_sales_per_day,
           abc_a_cumulative_ratio,abc_b_cumulative_ratio,
           quantity_metric_code,amount_metric_code,gross_profit_metric_code,
           effective_from,effective_to,enabled,created_by,note,created_at,updated_at)
        values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,null,true,?,?,now(),now())
        """,
        id,
        tenantId,
        standard.ruleCode(),
        standard.ruleName(),
        standard.dimensionType(),
        1,
        standard.windowDays(),
        standard.newMaxDaysSinceFirstSale(),
        standard.firstSaleLookbackDays(),
        standard.minHistoryDays(),
        new BigDecimal(standard.decliningMinDeclineRatio()),
        new BigDecimal(standard.hotMinSalesShareRatio()),
        new BigDecimal(standard.strongMinSalesShareRatio()),
        new BigDecimal(standard.steadyMinSalesShareRatio()),
        new BigDecimal(standard.slowMaxSalesPerDay()),
        new BigDecimal(standard.abcACumulativeRatio()),
        new BigDecimal(standard.abcBCumulativeRatio()),
        ReportingLifecycleSegmentationCatalog.QUANTITY_METRIC_CODE,
        ReportingLifecycleSegmentationCatalog.AMOUNT_METRIC_CODE,
        ReportingLifecycleSegmentationCatalog.GROSS_PROFIT_METRIC_CODE,
        today,
        "system-default",
        standard.note());
    return loadEnabledRule(tenantId, dimensionType);
  }

  // ==========================================================================
  // 计算
  // ==========================================================================

  /** 算一遍分层（不落库）。 */
  private List<Computed> compute(
      UUID tenantId,
      RuleRow rule,
      LocalDate from,
      LocalDate to,
      Map<String, Definition> definitions) {
    var universe = loadUniverse(tenantId, rule.dimensionType());
    var comparisonFrom = from.minusDays(rule.windowDays());
    var comparisonTo = from.minusDays(1);
    var lookbackFrom = to.minusDays(rule.firstSaleLookbackDays() - 1L);
    var earliest = comparisonFrom.isBefore(lookbackFrom) ? comparisonFrom : lookbackFrom;
    if (from.isBefore(earliest)) earliest = from;

    var needed = collectMetricCodes(rule, definitions);
    var facts = loadFacts(tenantId, needed, definitions, earliest, to);
    var inventory = loadInventory(tenantId);

    // 窗口内的销量总量（份额的分母）。窗口整体没有销量 ⇒ 份额算不出来（不是 0）。
    var windowTotal = BigDecimal.ZERO;
    for (var byDimension : facts.values()) {
      for (var rows : byDimension.values()) {
        for (var fact : rows) {
          if (fact.bucketDate().isBefore(from) || fact.bucketDate().isAfter(to)) continue;
          if (!rule.quantityMetricCode().equals(fact.metricCode())) continue;
          var definition = definitions.get(fact.metricCode());
          var quantity =
              definition != null && "QUANTITY".equals(definition.unit())
                  ? nz(fact.quantity())
                  : nz(fact.value());
          windowTotal = windowTotal.add(quantity);
        }
      }
    }
    var windowDays = ChronoUnit.DAYS.between(from, to) + 1;

    var computed = new ArrayList<Computed>();
    for (var product : universe.products().values()) {
      var skus = universe.childSkus().getOrDefault(product.id(), List.of());
      computed.add(
          computeProduct(
              rule, product, skus, facts, inventory, definitions,
              from, to, comparisonFrom, comparisonTo, lookbackFrom,
              windowDays, windowTotal));
    }
    return computed;
  }

  /** 一个商品的分层结论。 */
  private Computed computeProduct(
      RuleRow rule,
      ProductDim product,
      List<UUID> skus,
      Map<String, Map<UUID, List<FactRow>>> facts,
      Map<UUID, Inventory> inventory,
      Map<String, Definition> definitions,
      LocalDate from,
      LocalDate to,
      LocalDate comparisonFrom,
      LocalDate comparisonTo,
      LocalDate lookbackFrom,
      long windowDays,
      BigDecimal windowTotal) {

    // ---- 销量（件数，与币种无关）----
    var salesQuantity = BigDecimal.ZERO;
    var historyDays = new LinkedHashSet<LocalDate>();
    var priorQuantity = BigDecimal.ZERO;
    var priorHistoryDays = new LinkedHashSet<LocalDate>();
    LocalDate firstSaleDate = null;
    var hasFacts = false;
    var hasHistoryOutside = false;
    var quantityIsQuantityColumn =
        "QUANTITY".equals(
            definitions.get(rule.quantityMetricCode()) == null
                ? ""
                : definitions.get(rule.quantityMetricCode()).unit());

    for (var sku : skus) {
      for (var fact : rowsOf(facts, rule.quantityMetricCode(), sku)) {
        var quantity = quantityIsQuantityColumn ? nz(fact.quantity()) : nz(fact.value());
        var date = fact.bucketDate();
        if (!date.isBefore(from) && !date.isAfter(to)) {
          hasFacts = true;
          salesQuantity = salesQuantity.add(quantity);
          historyDays.add(date);
        } else {
          hasHistoryOutside = true;
        }
        if (!date.isBefore(comparisonFrom) && !date.isAfter(comparisonTo)) {
          priorQuantity = priorQuantity.add(quantity);
          if (quantity.signum() > 0) priorHistoryDays.add(date);
        }
        // 🔴 首次销售日在**回溯窗口 ∪ 分层窗口**里找：窗口比回溯窗口长时，
        //   窗口前段的历史也不能漏（否则会把老品误判成新品）。
        if (!date.isBefore(lookbackFrom) && !date.isAfter(to)
            && quantity.signum() > 0
            && (firstSaleDate == null || date.isBefore(firstSaleDate))) {
          firstSaleDate = date;
        }
      }
    }
    // 🔴 **当期销量 = 分层窗口的销量**（对比窗口是它前面紧挨着的那一段）。
    var recentQuantity = salesQuantity;

    // ---- 库存与库龄（时点快照，来自 13.3.1.1）----
    var onHand = BigDecimal.ZERO;
    BigDecimal agingDays = null;
    OffsetDateTime sourceObservedAt = null;
    for (var sku : skus) {
      var snapshot = inventory.get(sku);
      if (snapshot == null) continue;
      hasFacts = true;
      onHand = onHand.add(nz(snapshot.onHand()));
      if (snapshot.agingDays() != null
          && (agingDays == null || snapshot.agingDays().compareTo(agingDays) > 0)) {
        agingDays = snapshot.agingDays();
      }
      if (snapshot.observedAt() != null
          && (sourceObservedAt == null || snapshot.observedAt().isAfter(sourceObservedAt))) {
        sourceObservedAt = snapshot.observedAt();
      }
    }

    // ---- 销售额与毛利（按币种）----
    var salesByCurrency = new TreeMap<String, BigDecimal>();
    var grossProfitByCurrency = new TreeMap<String, BigDecimal>();
    var grossProfitFailures = new LinkedHashMap<String, String>();
    String grossProfitGlobalFailure = null;

    for (var sku : skus) {
      var amount = evaluate(rule.amountMetricCode(), sku, from, to, definitions, facts, new LinkedHashSet<>());
      if (amount.globalFailure() != null) {
        grossProfitGlobalFailure = amount.globalFailure();
      } else {
        for (var entry : amount.values().entrySet()) {
          salesByCurrency.merge(entry.getKey(), entry.getValue(), BigDecimal::add);
        }
      }
      var profit = evaluate(rule.grossProfitMetricCode(), sku, from, to, definitions, facts, new LinkedHashSet<>());
      if (profit.globalFailure() != null) {
        grossProfitGlobalFailure = profit.globalFailure();
        continue;
      }
      for (var entry : profit.values().entrySet()) {
        grossProfitByCurrency.merge(entry.getKey(), entry.getValue(), BigDecimal::add);
      }
      for (var entry : profit.failures().entrySet()) {
        grossProfitFailures.merge(
            entry.getKey(),
            entry.getValue(),
            (a, b) -> a + "；" + b);
      }
    }
    if (!salesByCurrency.isEmpty()) hasFacts = true;

    // ---- 判定 ----
    // 🔴 **比例一律是百分数**：先乘 100 再除 —— 先除到 4 位小数再乘 100 会把有效数字截掉。
    var share =
        windowTotal.signum() > 0
            ? salesQuantity
                .multiply(BigDecimal.valueOf(100))
                .divide(windowTotal, SCALE, RoundingMode.HALF_UP)
            : null;
    var avgDaily =
        salesQuantity.divide(BigDecimal.valueOf(Math.max(windowDays, 1)), QUANTITY_SCALE, RoundingMode.HALF_UP);

    BigDecimal declineRatio = null;
    String declineNote = null;
    var priorDaily =
        priorQuantity.divide(BigDecimal.valueOf(Math.max(rule.windowDays(), 1)), QUANTITY_SCALE, RoundingMode.HALF_UP);
    var recentDaily =
        recentQuantity.divide(BigDecimal.valueOf(Math.max(windowDays, 1)), QUANTITY_SCALE, RoundingMode.HALF_UP);
    if (priorDaily.signum() > 0 && priorHistoryDays.size() >= rule.minHistoryDays()) {
      declineRatio =
          priorDaily
              .subtract(recentDaily)
              .multiply(BigDecimal.valueOf(100))
              .divide(priorDaily, SCALE, RoundingMode.HALF_UP);
      if (declineRatio.signum() < 0) declineRatio = BigDecimal.ZERO;
    } else if (priorQuantity.signum() == 0) {
      declineNote =
          "衰退判定不可算：对比窗口（"
              + comparisonFrom
              + " ~ "
              + comparisonTo
              + "）内没有销量事实 —— "
              + "**不是「没衰退」**。对比窗口的销量历史来自 13.2.2.2 的日事实。";
    } else {
      declineNote =
          "衰退判定不可算：对比窗口内只有 "
              + priorHistoryDays.size()
              + " 天有销量事实，规则要求至少 "
              + rule.minHistoryDays()
              + " 天。";
    }

    String segment;
    String availability;
    String note;
    if (!hasFacts) {
      segment = null;
      availability = "NO_DATA";
      note =
          "窗口（"
              + from
              + " ~ "
              + to
              + "）内没有销量、金额与库存事实 —— "
              + (hasHistoryOutside
                  ? "对比窗口里有历史（这个商品在这个窗口没动）—— **不是 0，是这段时间没有数据**。"
                  : "先刷新 13.3.1.1 / 13.2.2.2 的投影。**不是 0，是还没有数据。**");
    } else {
      var age =
          firstSaleDate == null ? null : ChronoUnit.DAYS.between(firstSaleDate, to);
      if (age != null && age <= rule.newMaxDaysSinceFirstSale()) {
        segment = "NEW";
        availability = "AVAILABLE";
        note =
            "首次销售日 "
                + firstSaleDate
                + "（距判定基准日 "
                + age
                + " 天 ≤ 新品阈值 "
                + rule.newMaxDaysSinceFirstSale()
                + " 天）。";
      } else if (declineRatio != null
          && declineRatio.compareTo(rule.decliningMinDeclineRatio()) >= 0) {
        segment = "DECLINING";
        availability = "AVAILABLE";
        note =
            "近窗日均销量 "
                + recentDaily.toPlainString()
                + " 相对对比窗日均 "
                + priorDaily.toPlainString()
                + " 下滑 "
                + declineRatio.toPlainString()
                + "%（阈值 "
                + rule.decliningMinDeclineRatio().toPlainString()
                + "%）。";
      } else if (share != null && share.compareTo(rule.hotMinSalesShareRatio()) >= 0) {
        segment = "HOT";
        availability = "AVAILABLE";
        note =
            "窗口内销量份额 "
                + share.toPlainString()
                + "% ≥ 爆款门槛 "
                + rule.hotMinSalesShareRatio().toPlainString()
                + "%。";
      } else if (share != null && share.compareTo(rule.strongMinSalesShareRatio()) >= 0) {
        segment = "STRONG";
        availability = "AVAILABLE";
        note =
            "窗口内销量份额 "
                + share.toPlainString()
                + "% ≥ 旺款门槛 "
                + rule.strongMinSalesShareRatio().toPlainString()
                + "%。";
      } else if ((share == null || share.compareTo(rule.steadyMinSalesShareRatio()) < 0)
          && avgDaily.compareTo(rule.slowMaxSalesPerDay()) < 0
          && onHand.signum() > 0) {
        segment = "SLOW";
        availability = "AVAILABLE";
        note =
            "日均销量 "
                + avgDaily.toPlainString()
                + " 件 < 滞销门槛 "
                + rule.slowMaxSalesPerDay().toPlainString()
                + " 件，且在手库存 "
                + onHand.toPlainString()
                + " 件（没库存的卖不动不叫滞销）。";
      } else {
        segment = "STEADY";
        availability = "AVAILABLE";
        note =
            "窗口内销量份额 "
                + (share == null
                    ? "不可比（窗口内整体没有销量）"
                    : share.toPlainString() + "%")
                + "，日均销量 "
                + avgDaily.toPlainString()
                + " 件 —— 表现一般。";
      }
      if (declineNote != null && !"DECLINING".equals(segment)) {
        note = note + " " + declineNote;
      }
    }

    String action = null;
    String actionNote = "";
    if (segment != null) {
      var actionType = ReportingLifecycleSegmentationCatalog.actionOf(
          ReportingLifecycleSegmentationCatalog.Segment.valueOf(segment));
      action = actionType.name();
      actionNote = actionType.label() + "：" + note;
    }

    return new Computed(
        product.id(),
        product.key(),
        product.name(),
        salesQuantity,
        firstSaleDate,
        historyDays.size(),
        recentQuantity,
        priorQuantity,
        declineRatio,
        share,
        segment,
        availability,
        note,
        action,
        actionNote,
        onHand,
        agingDays,
        sourceObservedAt,
        salesByCurrency,
        grossProfitByCurrency,
        grossProfitFailures,
        grossProfitGlobalFailure);
  }

  // ==========================================================================
  // 口径求值（**按口径表递归求值，不写死公式**）
  // ==========================================================================

  /**
   * 按口径表求一个指标在某个商品维度上的窗口取值。
   *
   * <p>🔴 **派生口径是递归求值的**：毛利额 = 销售额 − 出库成本，分子分母来自
   * 13.3.1.1 的口径表；本方法**不写死**这条公式 —— 口径改了，这里自动跟着改。
   *
   * @param visiting 递归在途的指标码（防环）
   */
  private Eval evaluate(
      String metricCode,
      UUID dimensionId,
      LocalDate from,
      LocalDate to,
      Map<String, Definition> definitions,
      Map<String, Map<UUID, List<FactRow>>> facts,
      Set<String> visiting) {
    var definition = definitions.get(metricCode);
    if (definition == null) {
      return Eval.global(
          "口径「"
              + metricCode
              + "」还没在 13.3.1.1 / 13.2.2.2 的口径表里登记 —— "
              + "**不是 0，是口径未投影**（归 13.3.1.1 / 13.2.2.2 管）。");
    }
    if (!definition.available()) {
      return Eval.global(
          "口径「"
              + metricCode
              + "」本轮不可用（归 "
              + definition.ownerModule()
              + " 管）："
              + definition.availabilityNote());
    }
    if (!visiting.add(metricCode)) {
      return Eval.global("口径「" + metricCode + "」的派生关系成环了 —— 口径不成立。");
    }
    try {
      if ("SUM".equals(definition.aggregation())) {
        var values = new LinkedHashMap<String, BigDecimal>();
        var failures = new LinkedHashMap<String, String>();
        for (var fact : rowsOf(facts, metricCode, dimensionId)) {
          if (fact.bucketDate().isBefore(from) || fact.bucketDate().isAfter(to)) continue;
          var amount =
              "QUANTITY".equals(definition.unit()) ? nz(fact.quantity()) : nz(fact.value());
          // 🔴 **与币种无关的量落到空币种键**（空串 = 与币种无关，不是「币种未知」）。
          var currency = definition.currencyAware() ? fact.currency() : "";
          if (definition.currencyAware() && currency == null) {
            // 🔴 涉币指标的事实行缺币种 = 数据形状不对 —— 记账名，不当 0 处理。
            failures.put("", "事实行缺币种（涉币指标必须带币种）—— 这一行不计入合计。");
            continue;
          }
          values.merge(currency == null ? "" : currency, amount, BigDecimal::add);
        }
        return new Eval(values, failures, null);
      }
      var numerator = definition.numeratorMetricCode();
      var denominator = definition.denominatorMetricCode();
      if (numerator == null || denominator == null) {
        return Eval.global("口径「" + metricCode + "」是派生口径，却没写分子分母 —— 口径不成立。");
      }
      var num = evaluate(numerator, dimensionId, from, to, definitions, facts, visiting);
      var den = evaluate(denominator, dimensionId, from, to, definitions, facts, visiting);
      if (num.globalFailure() != null) return num;
      if (den.globalFailure() != null) return den;
      var values = new LinkedHashMap<String, BigDecimal>();
      var failures = new LinkedHashMap<>(num.failures());
      failures.putAll(den.failures());
      var currencies = new LinkedHashSet<String>();
      currencies.addAll(num.values().keySet());
      currencies.addAll(den.values().keySet());
      for (var currency : currencies) {
        var n = num.values().get(currency);
        var d = den.values().get(currency);
        if (n == null || d == null) {
          failures.put(
              currency,
              "派生口径「"
                  + metricCode
                  + "」（口径归 "
                  + caliberOwner(definition)
                  + "）的分子或分母在这个币种上没有事实 —— "
                  + "**算不出来，不是 0**。");
          continue;
        }
        if ("DERIVED_DIFF".equals(definition.aggregation())) {
          values.put(currency, n.subtract(d));
        } else {
          if (d.signum() == 0) {
            failures.put(currency, "派生口径「" + metricCode + "」的分母为 0 —— 比率算不出来。");
            continue;
          }
          values.put(currency, n.divide(d, SCALE, RoundingMode.HALF_UP));
        }
      }
      return new Eval(values, failures, null);
    } finally {
      visiting.remove(metricCode);
    }
  }

  /** 需要的指标码（含派生口径的分子分母闭包）。 */
  private Set<String> collectMetricCodes(RuleRow rule, Map<String, Definition> definitions) {
    var needed = new LinkedHashSet<String>();
    collect(rule.quantityMetricCode(), definitions, needed);
    collect(rule.amountMetricCode(), definitions, needed);
    collect(rule.grossProfitMetricCode(), definitions, needed);
    return needed;
  }

  private void collect(
      String metricCode, Map<String, Definition> definitions, Set<String> needed) {
    if (metricCode == null || !needed.add(metricCode)) return;
    var definition = definitions.get(metricCode);
    if (definition == null) return;
    collect(definition.numeratorMetricCode(), definitions, needed);
    collect(definition.denominatorMetricCode(), definitions, needed);
  }

  // ==========================================================================
  // 落库
  // ==========================================================================

  /** 把计算结果落库（**先删后写**，窗口内不留上一轮的残影）。 */
  private void writeResults(
      UUID tenantId, RuleRow rule, LocalDate from, LocalDate to, List<Computed> computed) {
    sql.execute(
        """
        delete from reporting_lifecycle_segmentation_amount
         where tenant_id = ? and dimension_type = ? and window_from = ? and window_to = ?
        """,
        tenantId,
        rule.dimensionType(),
        from,
        to);
    sql.execute(
        """
        delete from reporting_lifecycle_segmentation_result
         where tenant_id = ? and dimension_type = ? and window_from = ? and window_to = ?
        """,
        tenantId,
        rule.dimensionType(),
        from,
        to);

    var quantityAbc = abcByQuantity(computed, rule);
    var amountAbc = abcByAmount(computed, rule);

    for (var item : computed) {
      var resultId = Ids.next();
      sql.execute(
          """
          insert into reporting_lifecycle_segmentation_result
            (id,tenant_id,rule_id,rule_code,rule_version,dimension_type,dimension_id,
             dimension_key,dimension_name,bucket_date,window_from,window_to,
             sales_quantity,first_sale_date,history_days,recent_quantity,prior_quantity,
             decline_ratio,sales_share,lifecycle_segment,segment_availability,segment_note,
             abc_by_quantity,abc_rank_by_quantity,abc_total_by_quantity,
             recommended_action,action_note,on_hand_quantity,aging_days,source_observed_at,created_at)
          values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,now())
          """,
          resultId,
          tenantId,
          rule.id(),
          rule.ruleCode(),
          rule.version(),
          rule.dimensionType(),
          item.dimensionId(),
          item.dimensionKey(),
          item.dimensionName(),
          to,
          from,
          to,
          item.salesQuantity(),
          item.firstSaleDate(),
          item.historyDays(),
          item.recentQuantity(),
          item.priorQuantity(),
          item.declineRatio(),
          item.salesShare(),
          item.segment(),
          item.availability(),
          item.note(),
          quantityAbc.get(item.dimensionId()) == null ? null : quantityAbc.get(item.dimensionId()).classOf(),
          quantityAbc.get(item.dimensionId()) == null ? null : quantityAbc.get(item.dimensionId()).rank(),
          quantityAbc.get(item.dimensionId()) == null ? null : quantityAbc.get(item.dimensionId()).total(),
          item.action(),
          item.actionNote(),
          item.onHand(),
          item.agingDays(),
          item.sourceObservedAt());

      for (var currency : item.salesByCurrency().keySet()) {
        var salesAmount = item.salesByCurrency().get(currency);
        var grossProfit = item.grossProfitByCurrency().get(currency);
        String availability;
        String note;
        if (grossProfit != null) {
          availability = "AVAILABLE";
          note = "";
        } else if (item.grossProfitGlobalFailure() != null) {
          availability = "NOT_DELIVERED";
          note = item.grossProfitGlobalFailure();
        } else {
          availability = "NO_DATA";
          note =
              item.grossProfitFailures().getOrDefault(
                  currency,
                  "毛利额算不出来：这个币种上没有对应的出库成本事实（归 13.3.1.1 / 12.4.1.2 管）—— **不是 0**。");
        }
        var abc = amountAbc.get(new CurrencyKey(item.dimensionId(), currency));
        sql.execute(
            """
            insert into reporting_lifecycle_segmentation_amount
              (id,tenant_id,result_id,rule_id,dimension_type,dimension_id,
               bucket_date,window_from,window_to,currency,
               sales_amount,gross_profit_amount,gross_profit_availability,gross_profit_note,
               abc_by_amount,abc_rank_by_amount,abc_total_by_amount,created_at)
            values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now())
            """,
            Ids.next(),
            tenantId,
            resultId,
            rule.id(),
            rule.dimensionType(),
            item.dimensionId(),
            to,
            from,
            to,
            currency,
            salesAmount,
            grossProfit,
            availability,
            note,
            abc == null ? null : abc.classOf(),
            abc == null ? null : abc.rank(),
            abc == null ? null : abc.total());
      }
    }
  }

  // ==========================================================================
  // ABC 分类
  // ==========================================================================

  /** 一个 ABC 分档结果。 */
  private record Abc(String classOf, int rank, int total) {}

  /** 金额分档的键（商品维度 × 币种）。 */
  private record CurrencyKey(UUID dimensionId, String currency) {}

  /**
   * ABC（**按销量**）。
   *
   * <p>🔴 **只有有销量事实的商品进榜**：数了「没数据」的商品，排名分母会把没数据当成表现差。
   *
   * <p>🔴 **并列同档**：销量相同的商品**同档**（档位取决于数值，不取决于行的顺序）。
   */
  private Map<UUID, Abc> abcByQuantity(List<Computed> computed, RuleRow rule) {
    var ranked = new ArrayList<Computed>();
    for (var item : computed) {
      if (item.salesQuantity() != null && item.salesQuantity().signum() > 0) ranked.add(item);
    }
    var out = new LinkedHashMap<UUID, Abc>();
    assignAbc(
        ranked.stream()
            .sorted(
                Comparator.comparing((Computed item) -> item.salesQuantity()).reversed()
                    .thenComparing(Computed::dimensionKey))
            .map(item -> new Object[] {item.dimensionId(), item.salesQuantity()})
            .toList(),
        rule,
        out);
    return out;
  }

  /**
   * ABC（**按金额**）。
   *
   * <p>🔴 **按币种分别排名**：金额跨币种不可比（1 USD 与 1 EUR 谁大没有答案）。
   */
  private Map<CurrencyKey, Abc> abcByAmount(List<Computed> computed, RuleRow rule) {
    var out = new LinkedHashMap<CurrencyKey, Abc>();
    var currencies = new LinkedHashSet<String>();
    for (var item : computed) currencies.addAll(item.salesByCurrency().keySet());
    for (var currency : currencies) {
      var ranked = new ArrayList<Computed>();
      for (var item : computed) {
        var amount = item.salesByCurrency().get(currency);
        if (amount != null && amount.signum() > 0) ranked.add(item);
      }
      var perCurrency = new LinkedHashMap<UUID, Abc>();
      assignAbc(
          ranked.stream()
              .sorted(
                  Comparator.comparing(
                          (Computed item) -> item.salesByCurrency().get(currency))
                      .reversed()
                      .thenComparing(Computed::dimensionKey))
              .map(item -> new Object[] {item.dimensionId(), item.salesByCurrency().get(currency)})
              .toList(),
          rule,
          perCurrency);
      for (var entry : perCurrency.entrySet()) {
        out.put(new CurrencyKey(entry.getKey(), currency), entry.getValue());
      }
    }
    return out;
  }

  /**
   * 帕累托切分。
   *
   * @param ranked 按取值降序排好的（id, 取值）列表
   * @param rule 规则（累计比阈值）
   * @param out 结果容器
   */
  private void assignAbc(List<Object[]> ranked, RuleRow rule, Map<UUID, Abc> out) {
    if (ranked.isEmpty()) return;
    var total = BigDecimal.ZERO;
    for (var entry : ranked) total = total.add((BigDecimal) entry[1]);
    if (total.signum() == 0) return;

    var cumulative = BigDecimal.ZERO;
    var rank = 1;
    var index = 0;
    while (index < ranked.size()) {
      // 🔴 **并列同档**：同取值的商品一起进出榜（档位取决于数值，不取决于顺序）。
      var value = (BigDecimal) ranked.get(index)[1];
      var end = index;
      var groupTotal = BigDecimal.ZERO;
      while (end < ranked.size() && ((BigDecimal) ranked.get(end)[1]).compareTo(value) == 0) {
        groupTotal = groupTotal.add((BigDecimal) ranked.get(end)[1]);
        end++;
      }
      var ratioBefore =
          cumulative.multiply(BigDecimal.valueOf(100)).divide(total, SCALE, RoundingMode.HALF_UP);
      String classOf;
      if (ratioBefore.compareTo(rule.abcACumulativeRatio()) < 0) {
        classOf = "A";
      } else if (ratioBefore.compareTo(rule.abcBCumulativeRatio()) < 0) {
        classOf = "B";
      } else {
        classOf = "C";
      }
      for (var i = index; i < end; i++) {
        var id = (UUID) ranked.get(i)[0];
        out.put(id, new Abc(classOf, rank, ranked.size()));
      }
      cumulative = cumulative.add(groupTotal);
      rank = end + 1;
      index = end;
    }
  }

  // ==========================================================================
  // 读模型
  // ==========================================================================

  /**
   * 分层结果（一次拉齐：分档汇总 + 商品明细 + 规则 + 口径说明）。
   *
   * @param segment 只看某一档（空 = 全部）
   * @param abcByQuantity 只看某一 ABC 档（空 = 全部）
   */
  public ReportingLifecycleSegmentationModels.LifecycleSegmentationResponse segmentation(
      UUID tenantId,
      LocalDate from,
      LocalDate to,
      String dimensionType,
      String segment,
      String abcByQuantity,
      int page,
      int size) {
    return tenants.run(
        tenantId,
        () -> {
          var definitions = loadDefinitions(tenantId);
          var rule = loadEnabledRule(tenantId, dimensionType);
          var effectiveSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
          var effectivePage = Math.max(0, page);

          var window = resolveWindow(tenantId, dimensionType, from, to);
          var actualFrom = window.from();
          var actualTo = window.to();
          var matched = actualFrom.equals(from) && actualTo.equals(to);

          var total = countResults(tenantId, dimensionType, actualFrom, actualTo, segment, abcByQuantity);
          var allInWindow =
              countResults(tenantId, dimensionType, actualFrom, actualTo, null, null);
          var rows =
              loadRows(tenantId, dimensionType, actualFrom, actualTo, segment, abcByQuantity,
                  effectivePage, effectiveSize);
          var summary = loadSummary(tenantId, dimensionType, actualFrom, actualTo);
          var archived = countArchive(tenantId, dimensionType);
          var notSegmented = Math.max(0, archived - (int) allInWindow);
          var updatedAt = dataUpdatedAt(tenantId, dimensionType, actualFrom, actualTo);

          var status = total == 0 ? "EMPTY" : "OK";
          String emptyReason = null;
          String emptyNote = "";
          if (total == 0) {
            emptyReason = "NO_SEGMENTATION_IN_WINDOW";
            emptyNote =
                "窗口（"
                    + actualFrom
                    + " ~ "
                    + actualTo
                    + "）里还没有分层结果 —— "
                    + "先调用 POST /reporting/lifecycle-segmentation/refresh 算一遍。"
                    + (notSegmented > 0
                        ? "商品档案里有 " + notSegmented + " 个商品没有出现在分层结果里（见覆盖说明）。"
                        : "");
          }

          return new ReportingLifecycleSegmentationModels.LifecycleSegmentationResponse(
              from,
              to,
              actualFrom,
              actualTo,
              matched,
              matched
                  ? "分层窗口与请求一致。"
                  : "🔴 请求的窗口（"
                      + from
                      + " ~ "
                      + to
                      + "）里没有分层结果，这里返回的是**最近一次**分层结果（"
                      + actualFrom
                      + " ~ "
                      + actualTo
                      + "）。",
              dimensionType,
              rule == null ? "" : rule.ruleCode(),
              rule == null ? 0 : rule.version(),
              ruleViews(tenantId, dimensionType),
              summary,
              rows,
              effectivePage,
              effectiveSize,
              total,
              archived,
              notSegmented,
              "商品档案里共 "
                  + archived
                  + " 个"
                  + dimensionType
                  + "，窗口内有分层结果的是 "
                  + total
                  + " 个"
                  + (notSegmented > 0
                      ? "；**另外 " + notSegmented + " 个没有分层结果**（通常是没刷过上游投影，或该窗口没跑过分层）—— 这里点名，不静默省略。"
                      : "。"),
              metricReferences(rule, definitions),
              caliberNotes(dimensionType),
              ruleNotes(),
              updatedAt,
              "最近一次分层落库时刻（DB 钟域）。报表是投影，不是实时的；与业务系统冲突时以业务系统为准。",
              status,
              total == 0 ? "这个窗口还没有分层结果。" : "分层结果已就绪。",
              emptyReason,
              emptyNote);
        });
  }

  /**
   * 实际使用的窗口：请求窗口没有分层结果时，回退到**最近一次**分层结果的窗口。
   *
   * <p>🔴 回退时响应里会写明「返回的是最近一次结果」—— 不让用户以为看到的就是他要的窗口。
   */
  private Window resolveWindow(UUID tenantId, String dimensionType, LocalDate from, LocalDate to) {
    var exact =
        sql.fetchOne(
            """
            select window_from, window_to
              from reporting_lifecycle_segmentation_result
             where tenant_id = ? and dimension_type = ?
               and window_from = ? and window_to = ?
             limit 1
            """,
            tenantId,
            dimensionType,
            from,
            to);
    if (exact != null) return new Window(from, to);
    var latest =
        sql.fetchOne(
            """
            select window_from, window_to
              from reporting_lifecycle_segmentation_result
             where tenant_id = ? and dimension_type = ?
             order by bucket_date desc, window_to desc, created_at desc
             limit 1
            """,
            tenantId,
            dimensionType);
    if (latest != null) {
      return new Window(latest.get(0, LocalDate.class), latest.get(1, LocalDate.class));
    }
    return new Window(from, to);
  }

  /** 窗口（一对日期）。 */
  private record Window(LocalDate from, LocalDate to) {}

  private long countResults(
      UUID tenantId,
      String dimensionType,
      LocalDate from,
      LocalDate to,
      String segment,
      String abcByQuantity) {
    var args = new ArrayList<Object>();
    var where = new StringBuilder(" where tenant_id = ? and dimension_type = ? and window_from = ? and window_to = ?");
    args.add(tenantId);
    args.add(dimensionType);
    args.add(from);
    args.add(to);
    if (segment != null && !segment.isBlank()) {
      where.append(" and lifecycle_segment = ?");
      args.add(segment.trim().toUpperCase());
    }
    if (abcByQuantity != null && !abcByQuantity.isBlank()) {
      where.append(" and abc_by_quantity = ?");
      args.add(abcByQuantity.trim().toUpperCase());
    }
    var row =
        sql.fetchOne("select count(*) from reporting_lifecycle_segmentation_result" + where, args.toArray());
    return row == null ? 0 : row.get(0, Long.class);
  }

  private List<ReportingLifecycleSegmentationModels.LifecycleSegmentationRow> loadRows(
      UUID tenantId,
      String dimensionType,
      LocalDate from,
      LocalDate to,
      String segment,
      String abcByQuantity,
      int page,
      int size) {
    var args = new ArrayList<Object>();
    var where = new StringBuilder(" where tenant_id = ? and dimension_type = ? and window_from = ? and window_to = ?");
    args.add(tenantId);
    args.add(dimensionType);
    args.add(from);
    args.add(to);
    if (segment != null && !segment.isBlank()) {
      where.append(" and lifecycle_segment = ?");
      args.add(segment.trim().toUpperCase());
    }
    if (abcByQuantity != null && !abcByQuantity.isBlank()) {
      where.append(" and abc_by_quantity = ?");
      args.add(abcByQuantity.trim().toUpperCase());
    }
    args.add(size);
    args.add(page * size);
    var rows =
        sql.fetch(
            """
            select id, dimension_type, dimension_id, dimension_key, dimension_name,
                   bucket_date, window_from, window_to, sales_quantity, first_sale_date,
                   history_days, recent_quantity, prior_quantity, decline_ratio, sales_share,
                   lifecycle_segment, segment_availability, segment_note,
                   abc_by_quantity, abc_rank_by_quantity, abc_total_by_quantity,
                   recommended_action, action_note, on_hand_quantity, aging_days, source_observed_at
              from reporting_lifecycle_segmentation_result
            """
                + where
                + " order by sales_quantity desc, dimension_key asc limit ? offset ?",
            args.toArray());
    var out = new ArrayList<ReportingLifecycleSegmentationModels.LifecycleSegmentationRow>();
    for (var row : rows) {
      var id = row.get(0, UUID.class);
      out.add(
          new ReportingLifecycleSegmentationModels.LifecycleSegmentationRow(
              row.get(1, String.class),
              row.get(2, UUID.class).toString(),
              row.get(3, String.class),
              row.get(4, String.class),
              row.get(5, LocalDate.class),
              row.get(6, LocalDate.class),
              row.get(7, LocalDate.class),
              row.get(8, BigDecimal.class),
              row.get(9, LocalDate.class),
              row.get(10, Integer.class),
              row.get(11, BigDecimal.class),
              row.get(12, BigDecimal.class),
              row.get(13, BigDecimal.class),
              row.get(14, BigDecimal.class),
              row.get(15, String.class),
              row.get(16, String.class),
              row.get(17, String.class),
              row.get(18, String.class),
              row.get(19, Integer.class),
              row.get(20, Integer.class),
              row.get(21, String.class),
              row.get(22, String.class),
              row.get(23, BigDecimal.class),
              row.get(24, BigDecimal.class),
              row.get(25, OffsetDateTime.class),
              loadAmounts(tenantId, id)));
    }
    return out;
  }

  private List<ReportingLifecycleSegmentationModels.LifecycleSegmentationAmountRow> loadAmounts(
      UUID tenantId, UUID resultId) {
    var rows =
        sql.fetch(
            """
            select currency, sales_amount, gross_profit_amount, gross_profit_availability,
                   gross_profit_note, abc_by_amount, abc_rank_by_amount, abc_total_by_amount
              from reporting_lifecycle_segmentation_amount
             where tenant_id = ? and result_id = ?
             order by currency
            """,
            tenantId,
            resultId);
    var out = new ArrayList<ReportingLifecycleSegmentationModels.LifecycleSegmentationAmountRow>();
    for (var row : rows) {
      out.add(
          new ReportingLifecycleSegmentationModels.LifecycleSegmentationAmountRow(
              row.get(0, String.class),
              row.get(1, BigDecimal.class),
              row.get(2, BigDecimal.class),
              row.get(3, String.class),
              row.get(4, String.class),
              row.get(5, String.class),
              row.get(6, Integer.class),
              row.get(7, Integer.class)));
    }
    return out;
  }

  /**
   * 分档汇总（**各层建议**）。
   *
   * <p>🔴 **毛利合计的可得性要说清**：某些商品的毛利算不出来（出库成本没投影），
   * 那这一档的毛利合计**只含算得出来的那部分** —— 响应里给出「可算 / 总数」两个数，
   * 不用一个残缺的合计冒充完整的。
   */
  private List<ReportingLifecycleSegmentationModels.LifecycleSegmentSummary> loadSummary(
      UUID tenantId, String dimensionType, LocalDate from, LocalDate to) {
    var counts =
        sql.fetch(
            """
            select lifecycle_segment, count(*), sum(sales_quantity)
              from reporting_lifecycle_segmentation_result
             where tenant_id = ? and dimension_type = ? and window_from = ? and window_to = ?
             group by lifecycle_segment
            """,
            tenantId,
            dimensionType,
            from,
            to);
    var bySegment = new LinkedHashMap<String, ReportingLifecycleSegmentationModels.LifecycleSegmentSummary>();
    for (var segment : ReportingLifecycleSegmentationCatalog.Segment.values()) {
      bySegment.put(segment.name(), emptySummary(segment));
    }
    for (var row : counts) {
      var segment = row.get(0, String.class);
      if (segment == null) continue;
      var productCount = row.get(1, Integer.class);
      var quantity = row.get(2, BigDecimal.class);
      var action = actionOfSegment(segment);
      bySegment.put(
          segment,
          new ReportingLifecycleSegmentationModels.LifecycleSegmentSummary(
              segment,
              labelOfSegment(segment),
              action,
              action == null ? "" : labelOfAction(action),
              action == null ? "" : defaultActionNote(segment),
              productCount,
              quantity == null ? BigDecimal.ZERO : quantity,
              new ArrayList<>()));
    }

    var amountRows =
        sql.fetch(
            """
            select r.lifecycle_segment, a.currency, sum(a.sales_amount),
                   sum(a.gross_profit_amount),
                   count(*) filter (where a.gross_profit_amount is not null),
                   count(*)
              from reporting_lifecycle_segmentation_amount a
              join reporting_lifecycle_segmentation_result r
                on r.id = a.result_id and r.tenant_id = a.tenant_id
             where a.tenant_id = ? and a.dimension_type = ? and a.window_from = ? and a.window_to = ?
             group by r.lifecycle_segment, a.currency
             order by r.lifecycle_segment, a.currency
            """,
            tenantId,
            dimensionType,
            from,
            to);
    var amountsBySegment = new LinkedHashMap<String, List<ReportingLifecycleSegmentationModels.LifecycleSegmentationAmountRow>>();
    for (var segment : bySegment.keySet()) {
      amountsBySegment.put(segment, new ArrayList<>());
    }
    for (var row : amountRows) {
      var segment = row.get(0, String.class);
      if (segment == null) continue;
      var computed = row.get(3, BigDecimal.class);
      var computable = row.get(4, Integer.class);
      var all = row.get(5, Integer.class);
      String availability;
      String note;
      if (computed == null) {
        availability = "NO_DATA";
        note =
            "这一档在这个币种上的毛利额**全部算不出来**（出库成本没有投影，归 13.3.1.1 / 12.4.1.2 管）—— **不是 0**。";
      } else if (computable != null && all != null && computable < all) {
        availability = "NOT_COMPARABLE";
        note =
            "这一档在这个币种上有 "
                + (all - computable)
                + " 个商品的毛利算不出来（合计只含算得出来的 "
                + computable
                + " 个）—— **这不是这一档的完整毛利**。";
      } else {
        availability = "AVAILABLE";
        note = "";
      }
      var amounts = amountsBySegment.get(segment);
      if (amounts == null) continue;
      amounts.add(
          new ReportingLifecycleSegmentationModels.LifecycleSegmentationAmountRow(
              row.get(1, String.class),
              row.get(2, BigDecimal.class),
              computed,
              availability,
              note,
              null,
              null,
              null));
    }
    var merged = new LinkedHashMap<String, ReportingLifecycleSegmentationModels.LifecycleSegmentSummary>();
    for (var entry : bySegment.entrySet()) {
      merged.put(entry.getKey(), withAmounts(entry.getValue(), amountsBySegment.get(entry.getKey())));
    }
    return new ArrayList<>(merged.values());
  }

  private ReportingLifecycleSegmentationModels.LifecycleSegmentSummary withAmounts(
      ReportingLifecycleSegmentationModels.LifecycleSegmentSummary summary,
      List<ReportingLifecycleSegmentationModels.LifecycleSegmentationAmountRow> amounts) {
    return new ReportingLifecycleSegmentationModels.LifecycleSegmentSummary(
        summary.segment(),
        summary.segmentLabel(),
        summary.recommendedAction(),
        summary.actionLabel(),
        summary.actionNote(),
        summary.productCount(),
        summary.salesQuantity(),
        amounts);
  }

  private ReportingLifecycleSegmentationModels.LifecycleSegmentSummary emptySummary(
      ReportingLifecycleSegmentationCatalog.Segment segment) {
    return new ReportingLifecycleSegmentationModels.LifecycleSegmentSummary(
        segment.name(),
        segment.label(),
        null,
        "",
        "这个窗口内没有属于「" + segment.label() + "」的商品。",
        0,
        BigDecimal.ZERO,
        new ArrayList<>());
  }

  private int countArchive(UUID tenantId, String dimensionType) {
    var row =
        sql.fetchOne(
            "select count(*) from reporting_product_dimension where tenant_id = ? and dimension_type = ?",
            tenantId,
            dimensionType);
    return row == null ? 0 : row.get(0, Integer.class);
  }

  private OffsetDateTime dataUpdatedAt(
      UUID tenantId, String dimensionType, LocalDate from, LocalDate to) {
    var row =
        sql.fetchOne(
            """
            select max(created_at) from reporting_lifecycle_segmentation_result
             where tenant_id = ? and dimension_type = ? and window_from = ? and window_to = ?
            """,
            tenantId,
            dimensionType,
            from,
            to);
    return row == null ? null : row.get(0, OffsetDateTime.class);
  }

  // ==========================================================================
  // 规则（可配置、可审计）
  // ==========================================================================

  /** 规则清单（当前生效 + 历史版本）。 */
  public List<ReportingLifecycleSegmentationModels.LifecycleSegmentationRuleView> rules(
      UUID tenantId, String dimensionType) {
    return tenants.run(tenantId, () -> ruleViews(tenantId, dimensionType));
  }

  private List<ReportingLifecycleSegmentationModels.LifecycleSegmentationRuleView> ruleViews(
      UUID tenantId, String dimensionType) {
    var rows =
        sql.fetch(
            """
            select rule_code, rule_name, dimension_type, version, window_days,
                   new_max_days_since_first_sale, first_sale_lookback_days, min_history_days,
                   declining_min_decline_ratio, hot_min_sales_share_ratio,
                   strong_min_sales_share_ratio, steady_min_sales_share_ratio,
                   slow_max_sales_per_day, abc_a_cumulative_ratio, abc_b_cumulative_ratio,
                   quantity_metric_code, amount_metric_code, gross_profit_metric_code,
                   effective_from, effective_to, enabled, created_by, note, created_at, updated_at
              from reporting_lifecycle_segmentation_rule
             where tenant_id = ? and dimension_type = ?
             order by version desc
            """,
            tenantId,
            dimensionType);
    var out = new ArrayList<ReportingLifecycleSegmentationModels.LifecycleSegmentationRuleView>();
    for (var row : rows) {
      out.add(
          new ReportingLifecycleSegmentationModels.LifecycleSegmentationRuleView(
              row.get(0, String.class),
              row.get(1, String.class),
              row.get(2, String.class),
              row.get(3, Integer.class),
              row.get(4, Integer.class),
              row.get(5, Integer.class),
              row.get(6, Integer.class),
              row.get(7, Integer.class),
              row.get(8, BigDecimal.class),
              row.get(9, BigDecimal.class),
              row.get(10, BigDecimal.class),
              row.get(11, BigDecimal.class),
              row.get(12, BigDecimal.class),
              row.get(13, BigDecimal.class),
              row.get(14, BigDecimal.class),
              row.get(15, String.class),
              row.get(16, String.class),
              row.get(17, String.class),
              row.get(18, LocalDate.class),
              row.get(19, LocalDate.class),
              Boolean.TRUE.equals(row.get(20, Boolean.class)),
              row.get(21, String.class),
              row.get(22, String.class),
              row.get(23, OffsetDateTime.class),
              row.get(24, OffsetDateTime.class)));
    }
    return out;
  }

  /**
   * 更新规则（**可配置、可审计**）。
   *
   * <p>🔴 **改规则 = 加一个版本**：旧版本停用（写上生效结束日），新版本生效。
   * **历史分层结果仍指向旧版本** ⇒ 半年后回看，仍然知道当时是按哪版规则分的层。
   */
  public ReportingLifecycleSegmentationModels.LifecycleSegmentationRuleView updateRule(
      UUID tenantId,
      String dimensionType,
      ReportingLifecycleSegmentationModels.LifecycleSegmentationRuleInput input,
      String actor) {
    return tenants.run(
        tenantId,
        () -> {
          var current = loadEnabledRule(tenantId, dimensionType);
          if (current == null) {
            throw new ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "这个维度层还没有生效的规则 —— 先跑一次分层（refresh）让服务端播种标准规则，再改。");
          }
          var merged = merge(current, input);
          validate(merged);

          var effectiveFrom =
              merged.effectiveFrom() == null ? LocalDate.now() : merged.effectiveFrom();
          var maxVersion =
              sql.fetchOne(
                  """
                  select coalesce(max(version), 0) from reporting_lifecycle_segmentation_rule
                   where tenant_id = ? and rule_code = ?
                  """,
                  tenantId,
                  current.ruleCode());
          var nextVersion = (maxVersion == null ? 0 : maxVersion.get(0, Integer.class)) + 1;

          // 🔴 先停用旧的（部分唯一索引：同一维度层只能有一条 enabled）。
          var oldEnd = effectiveFrom.minusDays(1);
          if (oldEnd.isBefore(current.effectiveFrom())) oldEnd = current.effectiveFrom();
          sql.execute(
              """
              update reporting_lifecycle_segmentation_rule
                 set enabled = false, effective_to = ?, updated_at = now()
               where tenant_id = ? and id = ?
              """,
              oldEnd,
              tenantId,
              current.id());

          sql.execute(
              """
              insert into reporting_lifecycle_segmentation_rule
                (id,tenant_id,rule_code,rule_name,dimension_type,version,window_days,
                 new_max_days_since_first_sale,first_sale_lookback_days,min_history_days,
                 declining_min_decline_ratio,hot_min_sales_share_ratio,strong_min_sales_share_ratio,
                 steady_min_sales_share_ratio,slow_max_sales_per_day,
                 abc_a_cumulative_ratio,abc_b_cumulative_ratio,
                 quantity_metric_code,amount_metric_code,gross_profit_metric_code,
                 effective_from,effective_to,enabled,created_by,note,created_at,updated_at)
              values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,true,?,?,now(),now())
              """,
              Ids.next(),
              tenantId,
              current.ruleCode(),
              merged.ruleName(),
              dimensionType,
              nextVersion,
              merged.windowDays(),
              merged.newMaxDaysSinceFirstSale(),
              merged.firstSaleLookbackDays(),
              merged.minHistoryDays(),
              merged.decliningMinDeclineRatio(),
              merged.hotMinSalesShareRatio(),
              merged.strongMinSalesShareRatio(),
              merged.steadyMinSalesShareRatio(),
              merged.slowMaxSalesPerDay(),
              merged.abcACumulativeRatio(),
              merged.abcBCumulativeRatio(),
              current.quantityMetricCode(),
              current.amountMetricCode(),
              current.grossProfitMetricCode(),
              effectiveFrom,
              merged.effectiveTo(),
              actor == null || actor.isBlank() ? "unknown" : actor,
              merged.note());

          var updated = loadEnabledRule(tenantId, dimensionType);
          return ruleViews(tenantId, dimensionType).stream()
              .filter(view -> view.version() == updated.version())
              .findFirst()
              .orElseThrow();
        });
  }

  /** 规则的可变部分（口径码不可变 —— 改它等于换了一个真相）。 */
  private record MutableRule(
      String ruleName,
      int windowDays,
      int newMaxDaysSinceFirstSale,
      int firstSaleLookbackDays,
      int minHistoryDays,
      BigDecimal decliningMinDeclineRatio,
      BigDecimal hotMinSalesShareRatio,
      BigDecimal strongMinSalesShareRatio,
      BigDecimal steadyMinSalesShareRatio,
      BigDecimal slowMaxSalesPerDay,
      BigDecimal abcACumulativeRatio,
      BigDecimal abcBCumulativeRatio,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      String note) {}

  private MutableRule merge(
      RuleRow current, ReportingLifecycleSegmentationModels.LifecycleSegmentationRuleInput input) {
    return new MutableRule(
        input.ruleName() == null || input.ruleName().isBlank() ? current.ruleName() : input.ruleName(),
        input.windowDays() == null ? current.windowDays() : input.windowDays(),
        input.newMaxDaysSinceFirstSale() == null
            ? current.newMaxDaysSinceFirstSale()
            : input.newMaxDaysSinceFirstSale(),
        input.firstSaleLookbackDays() == null
            ? current.firstSaleLookbackDays()
            : input.firstSaleLookbackDays(),
        input.minHistoryDays() == null ? current.minHistoryDays() : input.minHistoryDays(),
        input.decliningMinDeclineRatio() == null
            ? current.decliningMinDeclineRatio()
            : input.decliningMinDeclineRatio(),
        input.hotMinSalesShareRatio() == null
            ? current.hotMinSalesShareRatio()
            : input.hotMinSalesShareRatio(),
        input.strongMinSalesShareRatio() == null
            ? current.strongMinSalesShareRatio()
            : input.strongMinSalesShareRatio(),
        input.steadyMinSalesShareRatio() == null
            ? current.steadyMinSalesShareRatio()
            : input.steadyMinSalesShareRatio(),
        input.slowMaxSalesPerDay() == null
            ? current.slowMaxSalesPerDay()
            : input.slowMaxSalesPerDay(),
        input.abcACumulativeRatio() == null
            ? current.abcACumulativeRatio()
            : input.abcACumulativeRatio(),
        input.abcBCumulativeRatio() == null
            ? current.abcBCumulativeRatio()
            : input.abcBCumulativeRatio(),
        input.effectiveFrom() == null ? current.effectiveFrom() : input.effectiveFrom(),
        input.effectiveTo() == null ? current.effectiveTo() : input.effectiveTo(),
        input.note() == null || input.note().isBlank() ? current.note() : input.note());
  }

  /** 规则校验（**非法阈值当场拒绝**，不写半条规则进库）。 */
  private void validate(MutableRule rule) {
    if (rule.windowDays() < 7 || rule.windowDays() > MAX_WINDOW_DAYS) {
      throw new ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "对比窗口长度必须在 7 ~ " + MAX_WINDOW_DAYS + " 天之间（收到 " + rule.windowDays() + "）。");
    }
    if (rule.newMaxDaysSinceFirstSale() <= 0 || rule.firstSaleLookbackDays() <= 0) {
      throw new ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "新品天数与首次销售回溯天数必须大于 0。");
    }
    if (rule.minHistoryDays() <= 0) {
      throw new ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "最小历史天数必须大于 0。");
    }
    for (var ratio :
        List.of(
            rule.decliningMinDeclineRatio(),
            rule.hotMinSalesShareRatio(),
            rule.strongMinSalesShareRatio(),
            rule.steadyMinSalesShareRatio())) {
      if (ratio == null
          || ratio.signum() < 0
          || ratio.compareTo(HUNDRED) > 0) {
        throw new ResponseStatusException(
            org.springframework.http.HttpStatus.BAD_REQUEST, "比率阈值必须在 0 ~ 100（百分数）之间。");
      }
    }
    if (rule.slowMaxSalesPerDay() == null || rule.slowMaxSalesPerDay().signum() < 0) {
      throw new ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "滞销日均销量阈值不能为负。");
    }
    if (rule.abcACumulativeRatio() == null
        || rule.abcBCumulativeRatio() == null
        || rule.abcACumulativeRatio().signum() <= 0
        || rule.abcACumulativeRatio().compareTo(HUNDRED) > 0
        || rule.abcBCumulativeRatio().signum() <= 0
        || rule.abcBCumulativeRatio().compareTo(HUNDRED) > 0) {
      throw new ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "ABC 累计比阈值必须在 0 ~ 100（百分数）之间。");
    }
    if (rule.abcACumulativeRatio().compareTo(rule.abcBCumulativeRatio()) >= 0) {
      throw new ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "ABC 的 A 累计比必须小于 B 累计比（收到 "
              + rule.abcACumulativeRatio().toPlainString()
              + " / "
              + rule.abcBCumulativeRatio().toPlainString()
              + "）。");
    }
    if (rule.hotMinSalesShareRatio().compareTo(rule.strongMinSalesShareRatio()) < 0) {
      throw new ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "爆款门槛不能低于旺款门槛。");
    }
    if (rule.strongMinSalesShareRatio().compareTo(rule.steadyMinSalesShareRatio()) < 0) {
      throw new ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "旺款门槛不能低于平款门槛。");
    }
    if (rule.effectiveTo() != null
        && rule.effectiveFrom() != null
        && rule.effectiveTo().isBefore(rule.effectiveFrom())) {
      throw new ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "生效结束日不能早于生效起始日。");
    }
  }

  // ==========================================================================
  // 取数
  // ==========================================================================

  /** 生效规则。 */
  private RuleRow loadEnabledRule(UUID tenantId, String dimensionType) {
    var row =
        sql.fetchOne(
            """
            select id, rule_code, rule_name, dimension_type, version, window_days,
                   new_max_days_since_first_sale, first_sale_lookback_days, min_history_days,
                   declining_min_decline_ratio, hot_min_sales_share_ratio,
                   strong_min_sales_share_ratio, steady_min_sales_share_ratio,
                   slow_max_sales_per_day, abc_a_cumulative_ratio, abc_b_cumulative_ratio,
                   quantity_metric_code, amount_metric_code, gross_profit_metric_code,
                   effective_from, effective_to, enabled, created_by, note, created_at, updated_at
              from reporting_lifecycle_segmentation_rule
             where tenant_id = ? and dimension_type = ? and enabled
             order by version desc
             limit 1
            """,
            tenantId,
            dimensionType);
    if (row == null) return null;
    return new RuleRow(
        row.get(0, UUID.class),
        row.get(1, String.class),
        row.get(2, String.class),
        row.get(3, String.class),
        row.get(4, Integer.class),
        row.get(5, Integer.class),
        row.get(6, Integer.class),
        row.get(7, Integer.class),
        row.get(8, Integer.class),
        row.get(9, BigDecimal.class),
        row.get(10, BigDecimal.class),
        row.get(11, BigDecimal.class),
        row.get(12, BigDecimal.class),
        row.get(13, BigDecimal.class),
        row.get(14, BigDecimal.class),
        row.get(15, BigDecimal.class),
        row.get(16, String.class),
        row.get(17, String.class),
        row.get(18, String.class),
        row.get(19, LocalDate.class),
        row.get(20, LocalDate.class),
        Boolean.TRUE.equals(row.get(21, Boolean.class)),
        row.get(22, String.class),
        row.get(23, String.class),
        row.get(24, OffsetDateTime.class),
        row.get(25, OffsetDateTime.class));
  }

  /** 读两张口径表（**13.2.2.2 的商品维度口径 + 13.3.1.1 的产品表现口径**），合成运行时真相。 */
  private Map<String, Definition> loadDefinitions(UUID tenantId) {
    var definitions = new LinkedHashMap<String, Definition>();
    for (var row :
        sql.fetch(
            """
            select metric_code, display_name, unit, aggregation, numerator_metric_code,
                   denominator_metric_code, currency_scope, currency_aware, description, owner_module
              from reporting_product_metric_definition
             where tenant_id = ? and enabled
            """,
            tenantId)) {
      var code = row.get(0, String.class);
      if (definitions.containsKey(code)) continue;
      definitions.put(
          code,
          new Definition(
              code,
              row.get(1, String.class),
              row.get(2, String.class),
              row.get(3, String.class),
              row.get(4, String.class),
              row.get(5, String.class),
              "ANY".equals(row.get(6, String.class)) ? false : Boolean.TRUE.equals(row.get(7, Boolean.class)),
              true,
              row.get(9, String.class),
              row.get(8, String.class),
              "",
              "PRODUCT"));
    }
    for (var row :
        sql.fetch(
            """
            select metric_code, display_name, unit, aggregation, numerator_metric_code,
                   denominator_metric_code, currency_scope, currency_aware, available, owner_module,
                   description, availability_note
              from reporting_product_performance_metric_definition
             where tenant_id = ? and enabled
            """,
            tenantId)) {
      var code = row.get(0, String.class);
      definitions.put(
          code,
          new Definition(
              code,
              row.get(1, String.class),
              row.get(2, String.class),
              row.get(3, String.class),
              row.get(4, String.class),
              row.get(5, String.class),
              Boolean.TRUE.equals(row.get(7, Boolean.class)),
              Boolean.TRUE.equals(row.get(8, Boolean.class)),
              row.get(9, String.class),
              row.get(10, String.class),
              row.get(11, String.class),
              "PERFORMANCE"));
    }
    return definitions;
  }

  /** 商品维度档案（**只读** 13.2.2.2 的投影）。 */
  private Universe loadUniverse(UUID tenantId, String dimensionType) {
    var products = new LinkedHashMap<UUID, ProductDim>();
    var childSkus = new LinkedHashMap<UUID, List<UUID>>();
    var skuToSpu = new LinkedHashMap<UUID, UUID>();
    for (var row :
        sql.fetch(
            """
            select dimension_type, dimension_id, dimension_key, dimension_name, parent_dimension_id
              from reporting_product_dimension
             where tenant_id = ?
            """,
            tenantId)) {
      var type = row.get(0, String.class);
      var id = row.get(1, UUID.class);
      var key = row.get(2, String.class);
      var name = row.get(3, String.class);
      var parentId = row.get(4, UUID.class);
      if (id == null || key == null) continue;
      if ("SKU".equals(type)) {
        if (parentId != null) skuToSpu.put(id, parentId);
        if ("SKU".equals(dimensionType)) {
          products.put(id, new ProductDim(id, key, name == null ? "" : name));
          childSkus.put(id, List.of(id));
        }
      } else if ("SPU".equals(type) && "SPU".equals(dimensionType)) {
        products.put(id, new ProductDim(id, key, name == null ? "" : name));
        childSkus.put(id, new ArrayList<>());
      }
    }
    if ("SPU".equals(dimensionType)) {
      for (var entry : skuToSpu.entrySet()) {
        childSkus.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
      }
      // 🔴 **没有 SKU 的 SPU 不进分层**（空壳 SPU 没有销量可比）。
      products.keySet().removeIf(id -> childSkus.getOrDefault(id, List.of()).isEmpty());
    }
    return new Universe(products, childSkus);
  }

  /** 事实（**按指标码归拢**，指标住在哪张表由口径决定）。 */
  private Map<String, Map<UUID, List<FactRow>>> loadFacts(
      UUID tenantId,
      Set<String> metricCodes,
      Map<String, Definition> definitions,
      LocalDate from,
      LocalDate to) {
    var out = new LinkedHashMap<String, Map<UUID, List<FactRow>>>();
    if (metricCodes.isEmpty()) return out;
    var productCodes = new ArrayList<String>();
    var performanceCodes = new ArrayList<String>();
    for (var code : metricCodes) {
      var definition = definitions.get(code);
      if (definition == null) continue;
      if ("PERFORMANCE".equals(definition.home())) performanceCodes.add(code);
      else productCodes.add(code);
    }
    loadFactTable(tenantId, productCodes, from, to, out, "reporting_product_metric_fact");
    loadFactTable(tenantId, performanceCodes, from, to, out, "reporting_product_performance_fact");
    return out;
  }

  private void loadFactTable(
      UUID tenantId,
      List<String> metricCodes,
      LocalDate from,
      LocalDate to,
      Map<String, Map<UUID, List<FactRow>>> out,
      String table) {
    if (metricCodes.isEmpty()) return;
    var args = new ArrayList<Object>();
    args.add(tenantId);
    args.add(from);
    args.add(to);
    var placeholders = new StringBuilder();
    for (var i = 0; i < metricCodes.size(); i++) {
      if (i > 0) placeholders.append(",");
      placeholders.append("?");
      args.add(metricCodes.get(i));
    }
    var rows =
        sql.fetch(
            "select metric_code, dimension_id, currency, bucket_date, value, quantity from "
                + table
                + " where tenant_id = ? and bucket_date between ? and ?"
                + " and dimension_type = 'SKU'"
                + " and metric_code in ("
                + placeholders
                + ")",
            args.toArray());
    // 🔴 **只取 SKU 层的事实**：13.2.2.2 把销量 / 销售额**同时**写在 SKU 层与 SPU 层，
    //   两层都取会把窗口总量算两遍（份额的分母翻倍 ⇒ 档位全错）。
    //   本模块的口径是「**SKU 原生、SPU 由 SKU 上卷**」⇒ 只取 SKU 层，SPU 层自己求和。
    for (var row : rows) {
      var code = row.get(0, String.class);
      var dimensionId = row.get(1, UUID.class);
      if (dimensionId == null) continue;
      out.computeIfAbsent(code, k -> new LinkedHashMap<>())
          .computeIfAbsent(dimensionId, k -> new ArrayList<>())
          .add(
              new FactRow(
                  code,
                  dimensionId,
                  row.get(2, String.class),
                  row.get(3, LocalDate.class),
                  row.get(4, BigDecimal.class),
                  row.get(5, BigDecimal.class)));
    }
  }

  /** 库存与库龄快照（13.3.1.1 的时点快照，**按 SKU**）。 */
  private record Inventory(BigDecimal onHand, BigDecimal agingDays, OffsetDateTime observedAt) {}

  private Map<UUID, Inventory> loadInventory(UUID tenantId) {
    var out = new LinkedHashMap<UUID, Inventory>();
    var onHand = new LinkedHashMap<UUID, BigDecimal>();
    var aging = new LinkedHashMap<UUID, BigDecimal>();
    var observed = new LinkedHashMap<UUID, OffsetDateTime>();
    for (var row :
        sql.fetch(
            """
            select dimension_id, metric_code, value, quantity, observed_at
              from reporting_product_performance_snapshot
             where tenant_id = ? and dimension_type = 'SKU'
               and metric_code in (?, ?)
            """,
            tenantId,
            ReportingProductPerformanceCatalog.PRODUCT_ON_HAND_QUANTITY,
            ReportingProductPerformanceCatalog.PRODUCT_AGING_DAYS)) {
      var dimensionId = row.get(0, UUID.class);
      if (dimensionId == null) continue;
      var metric = row.get(1, String.class);
      var value = row.get(2, BigDecimal.class);
      var quantity = row.get(3, BigDecimal.class);
      var observedAt = row.get(4, OffsetDateTime.class);
      if (observedAt != null) {
        var current = observed.get(dimensionId);
        if (current == null || observedAt.isAfter(current)) observed.put(dimensionId, observedAt);
      }
      if (ReportingProductPerformanceCatalog.PRODUCT_ON_HAND_QUANTITY.equals(metric)) {
        onHand.merge(dimensionId, nz(quantity), BigDecimal::add);
      } else if (ReportingProductPerformanceCatalog.PRODUCT_AGING_DAYS.equals(metric)) {
        var current = aging.get(dimensionId);
        if (current == null || nz(value).compareTo(current) > 0) aging.put(dimensionId, nz(value));
      }
    }
    for (var entry : onHand.entrySet()) {
      out.put(
          entry.getKey(),
          new Inventory(entry.getValue(), aging.get(entry.getKey()), observed.get(entry.getKey())));
    }
    for (var entry : aging.entrySet()) {
      var existing = out.get(entry.getKey());
      out.put(
          entry.getKey(),
          new Inventory(
              existing == null ? BigDecimal.ZERO : existing.onHand(),
              entry.getValue(),
              existing == null ? observed.get(entry.getKey()) : existing.observedAt()));
    }
    return out;
  }

  // ==========================================================================
  // 口径说明
  // ==========================================================================

  /** 被引用的口径（**引用别人的口径表，这里只把「按它算」显示出来**）。 */
  private List<ReportingLifecycleSegmentationModels.LifecycleMetricReference> metricReferences(
      RuleRow rule, Map<String, Definition> definitions) {
    var out = new ArrayList<ReportingLifecycleSegmentationModels.LifecycleMetricReference>();
    if (rule == null) return out;
    for (var code :
        List.of(
            rule.quantityMetricCode(),
            rule.amountMetricCode(),
            rule.grossProfitMetricCode())) {
      var definition = definitions.get(code);
      if (definition == null) {
        out.add(
            new ReportingLifecycleSegmentationModels.LifecycleMetricReference(
                code,
                code,
                "",
                "",
                null,
                null,
                "未登记",
                "口径还没在 13.3.1.1 / 13.2.2.2 的口径表里登记 —— **不是 0，是口径未投影**。",
                false,
                "13.3.1.1 / 13.2.2.2",
                "先刷新那两个模块的口径，再回来分层。"));
        continue;
      }
      out.add(
          new ReportingLifecycleSegmentationModels.LifecycleMetricReference(
              definition.metricCode(),
              definition.displayName(),
              definition.unit(),
              definition.aggregation(),
              definition.numeratorMetricCode(),
              definition.denominatorMetricCode(),
              "PERFORMANCE".equals(definition.home())
                  ? "13.3.1.1 产品表现分析"
                  : "13.2.2.2 SKU/SPU/Listing",
              definition.description(),
              definition.available(),
              definition.ownerModule(),
              definition.availabilityNote()));
    }
    return out;
  }

  /** 口径说明（页面上原样显示）。 */
  private List<String> caliberNotes(String dimensionType) {
    return List.of(
        "🔴 **口径引用，不重复登记**：销量 / 销售额的口径在 **13.2.2.2**，"
            + "毛利额的口径在 **13.3.1.1**（派生：销售额 − 出库成本）—— "
            + "本模块只**引用**，不另起一张口径表登记同一批指标。",
        "🔴 **窗口外的事实不参与计算**：销量 / 销售额 / 毛利 / 销量份额全部只取分层窗口。"
            + "新品判定要回看「首次销售日」，衰退判定要比「对比窗口」—— "
            + "这两段是**规则里显式配置的独立窗口**，规则页上写得很清楚。",
        "🔴 **绝不跨币种相加**：金额按币种分别列出；只有件数（与货币无关）给跨币种合计。",
        "🔴 **ABC 按金额在同一个币种内部排名**：金额跨币种不可比（1 USD 与 1 EUR 谁大没有答案），"
            + "所以每个币种各排一次，一个商品在两个币种里可能是不同的档。",
        "🔴 **没有销量事实的商品不进 ABC 榜**：数了它，排名分母会把「没数据」当成「表现差」。",
        "🔴 **分层只到 "
            + ("SKU".equals(dimensionType) ? "SKU" : "SPU")
            + " 层**：LISTING 是**上架形态**不是商品本身（同一款在多个 Listing 上就是多行）。",
        "🔴 **毛利额 ≠ 13.4 多模式利润**：这里的毛利 = 销售额 − 成本法出库成本，"
            + "不含平台费 / 头程 / 物流费 / 广告费。",
        "🔴 **数据延迟**：报表是投影，不是实时的；与业务系统冲突时**以业务系统为准**。");
  }

  /** 判定规则说明。 */
  private List<ReportingLifecycleSegmentationModels.LifecycleSegmentationRuleNote> ruleNotes() {
    return List.of(
        new ReportingLifecycleSegmentationModels.LifecycleSegmentationRuleNote(
            "SEGMENT_ORDER",
            "档位判定顺序（**先生命周期，后表现**）："
                + "① 新品（首次销售距判定基准日 ≤ 新品天数）→ "
                + "② 衰退款（近窗日均销量相对对比窗下滑 ≥ 阈值）→ "
                + "③ 爆款（销量份额 ≥ 爆款门槛）→ "
                + "④ 旺款（份额 ≥ 旺款门槛）→ "
                + "⑤ 滞销款（份额 < 平款门槛 且 日均销量 < 滞销门槛 且 在手库存 > 0）→ "
                + "⑥ 平款（其余）。新品与衰退是**阶段**，优先于表现。"),
        new ReportingLifecycleSegmentationModels.LifecycleSegmentationRuleNote(
            "ACTION_MAPPING",
            "建议动作：爆款 / 旺款 ⇒ **备货**；滞销款 ⇒ **清仓**；衰退款 ⇒ **下架**；"
                + "新品 / 平款 ⇒ **推广**。"),
        new ReportingLifecycleSegmentationModels.LifecycleSegmentationRuleNote(
            "ABC",
            "ABC 按帕累托切分（累计贡献比 A / B，默认 80% / 95%）："
                + "销量相同（金额也相同）的商品**同档**，档位取决于数值，不取决于行的顺序。"),
        new ReportingLifecycleSegmentationModels.LifecycleSegmentationRuleNote(
            "NO_ZERO_FAKE",
            "🔴 **「算不出来」与「等于 0」分开**：档位算不出来（历史不足 / 窗口内没有事实 / 口径未投影）"
                + "一律显示为「不可比 / 没有数据 / 本轮不可用」+ 原因，**绝不用 0 或某个默认档位冒充**。"),
        new ReportingLifecycleSegmentationModels.LifecycleSegmentationRuleNote(
            "RULE_AUDIT",
            "🔴 **规则可配置、可审计**：阈值 / 分类口径 / 版本 / 生效区间都在库里；"
                + "改规则 = 加一个版本（旧版本停用），**历史分层结果仍指向旧版本**。"));
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  private List<FactRow> rowsOf(
      Map<String, Map<UUID, List<FactRow>>> facts, String metricCode, UUID dimensionId) {
    var byDimension = facts.get(metricCode);
    if (byDimension == null) return List.of();
    var rows = byDimension.get(dimensionId);
    return rows == null ? List.of() : rows;
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  /** 口径归谁管（页面据此显示「这归谁管」）。 */
  private static String caliberOwner(Definition definition) {
    return "PERFORMANCE".equals(definition.home())
        ? "13.3.1.1 产品表现分析"
        : "13.2.2.2 商品维度";
  }

  private static String labelOfSegment(String segment) {
    for (var value : ReportingLifecycleSegmentationCatalog.Segment.values()) {
      if (value.name().equals(segment)) return value.label();
    }
    return segment;
  }

  private static String actionOfSegment(String segment) {
    try {
      return ReportingLifecycleSegmentationCatalog.actionOf(
              ReportingLifecycleSegmentationCatalog.Segment.valueOf(segment))
          .name();
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static String labelOfAction(String action) {
    for (var value : ReportingLifecycleSegmentationCatalog.Action.values()) {
      if (value.name().equals(action)) return value.label();
    }
    return action;
  }

  private static String defaultActionNote(String segment) {
    return switch (segment) {
      case "NEW" -> "推广：新品需要曝光与流量，跑不动就该调价或下架。";
      case "HOT" -> "备货：爆款是资源分配的第一顺位（蓝图：「爆款备货」）。";
      case "STRONG" -> "备货：旺款稳定出货，值得补货但优先级低于爆款。";
      case "STEADY" -> "推广：表现稳定，值得推一把冲一冲下一档。";
      case "SLOW" -> "清仓：卖得慢又有库存，先清库存再谈补货。";
      case "DECLINING" -> "下架：销量在衰退，继续挂着只会压资金。";
      default -> "";
    };
  }
}
