package com.starcon.reporting;

import com.starcon.tenancy.TenantTransactions;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

/**
 * 13.4.1.1 多模式利润报表的**读模型**（蓝图「输出」句：利润报表 + 多维下钻 + 口径说明）。
 *
 * <p>⚠️ **不同经营模式的利润含义完全不同** ⇒ 报表**按模式分行**，模式之间**不合并**，
 * 也不提供「全部模式合计」—— 混在一起会得出错误结论。
 *
 * <p>⚠️ **「算不出来」与「等于 0」必须分开**：某一项成本没有事实时它是 {@code null}
 * （页面上是「—」），**不是 0**；毛利算不出来时整个 {@code grossProfit} 是 {@code null}
 * 并附一句原因（缺哪几项、归谁管）。
 *
 * <p>⚠️ **两种收入口径**（不混用）：经营模式 / 店铺 / 订单维度用**订单总额**；
 * SKU / SPU / MSKU 维度用**订单行金额合计**（差的是订单级运费 / 税 / 折扣）。
 *
 * <p>⚠️ **不跨币种相加**：每一行都带币种；本位币合计只在**显式指定**本位币、
 * 且汇率快照里有生效日不晚于窗口末的汇率时才给，并把**汇率与时点**一起返回。
 */
@Service
public class ReportingProfitReportService {

  /** 毛利率的标度（百分数，保留 4 位 —— 与既有指标口径一致）。 */
  private static final int RATE_SCALE = 4;

  /** 下钻每页最多多少行。 */
  static final int MAX_PAGE_SIZE = 200;

  private final DSLContext sql;
  private final TenantTransactions tenants;

  public ReportingProfitReportService(DSLContext sql, TenantTransactions tenants) {
    this.sql = sql;
    this.tenants = tenants;
  }

  // ==========================================================================
  // 利润报表
  // ==========================================================================

  /**
   * 多模式利润报表。
   *
   * @param granularity 期间粒度：{@code DAY} / {@code MONTH}
   * @param dimension 核算维度：{@code MODE} / {@code SHOP} / {@code SKU} / {@code SPU} /
   *     {@code MSKU} / {@code ORDER}
   * @param operatingMode 只看某个经营模式（空 ⇒ 全部模式**分别**出行，不合并）
   * @param baseCurrency 本位币（可空 ⇒ 只按币种分别报，不做跨币种合计）
   */
  public ReportingProfitReportModels.ProfitReportResponse report(
      UUID tenantId,
      LocalDate from,
      LocalDate to,
      String granularity,
      String dimension,
      String operatingMode,
      String shopCode,
      String skuCode,
      String baseCurrency) {
    return tenants.run(
        tenantId,
        () -> {
          var grain =
              ReportingProfitReportModels.Granularity.parse(granularity) == null
                  ? ReportingProfitReportModels.Granularity.DAY
                  : ReportingProfitReportModels.Granularity.parse(granularity);
          var dim =
              ReportingProfitReportModels.Dimension.parse(dimension) == null
                  ? ReportingProfitReportModels.Dimension.MODE
                  : ReportingProfitReportModels.Dimension.parse(dimension);

          var params = new ArrayList<Object>();
          params.add(tenantId);
          params.add(from);
          params.add(to);
          var filters = new StringBuilder();
          if (operatingMode != null && !operatingMode.isBlank()) {
            filters.append(" and operating_mode = ?");
            params.add(operatingMode);
          }
          if (shopCode != null && !shopCode.isBlank()) {
            filters.append(" and shop_code = ?");
            params.add(shopCode);
          }
          if (skuCode != null && !skuCode.isBlank()) {
            filters.append(" and sku_code = ?");
            params.add(skuCode);
          }

          var dimSql = dimSql(dim);
          var bucket = grain == ReportingProfitReportModels.Granularity.MONTH
              ? "date_trunc('month', period_start)::date"
              : "period_start";
          var groupBy = new StringBuilder("operating_mode, component, currency, ").append(bucket);
          if (!dimSql.groupBy().isBlank()) groupBy.append(", ").append(dimSql.groupBy());

          var rows =
              sql.fetch(
                  "select operating_mode, component, currency, "
                      + bucket
                      + " as bucket_start, "
                      + dimSql.select()
                      + " as dimension_code, sum(amount) as amount, sum(quantity) as quantity,"
                      + " count(*) as cnt from reporting_profit_fact"
                      + " where tenant_id = ? and period_start >= ? and period_start <= ?"
                      + filters
                      + dimSql.filter()
                      + " group by "
                      + groupBy
                      + " order by operating_mode, dimension_code, bucket_start, currency, component",
                  params.toArray());

          // ---- 分组：每一行 = （模式 × 维度取值 × 期间桶 × 币种）----
          var groups = new LinkedHashMap<String, Group>();
          var tenantComponents = new LinkedHashSet<String>();
          for (var row : rows) {
            var mode = row.get(0, String.class);
            var component = row.get(1, String.class);
            var currency = row.get(2, String.class);
            var bucketStart = row.get(3, LocalDate.class);
            var dimensionCode = row.get(4, String.class);
            var amount = row.get(5, BigDecimal.class);
            var quantity = row.get(6, BigDecimal.class);
            var count = row.get(7, Long.class);

            var key = mode + "|" + dimensionCode + "|" + bucketStart + "|" + currency;
            var group = groups.get(key);
            if (group == null) {
              group = new Group(mode, dimensionCode, bucketStart, currency);
              groups.put(key, group);
            }
            group
                .amounts()
                .put(
                    component,
                    new Amount(
                        amount == null ? BigDecimal.ZERO : amount,
                        quantity == null ? BigDecimal.ZERO : quantity,
                        count == null ? 0L : count));
            tenantComponents.add(component);
          }

          var reportRows = new ArrayList<ReportingProfitReportModels.ProfitReportRow>();
          for (var group : groups.values()) {
            reportRows.add(
                buildRow(
                    tenantId,
                    dim,
                    group,
                    tenantComponents,
                    baseCurrency,
                    to));
          }

          var warnings = new ArrayList<String>();
          if (reportRows.isEmpty()) {
            warnings.add("本窗口内**没有投影任何利润事实**（不是「利润为 0」）：先刷新利润事实再来看。");
          }
          var unpriced = reportRows.stream().filter(r -> !r.grossProfitComparable()).count();
          if (unpriced > 0) {
            warnings.add(
                "有 "
                    + unpriced
                    + " 行的毛利**算不出来**（显示「—」，不是 0）：每一行都写明了缺哪几项成本、归谁管。");
          }
          // ⚠️ **「这个租户压根没有这类成本」也要说**：那几项不参与毛利计算 ——
          //   若上游其实有、只是还没投影，毛利会偏高。
          var absent = new ArrayList<String>();
          for (var component : ReportingProfitReportModels.Component.values()) {
            if (component.isCost() && !tenantComponents.contains(component.name())) {
              absent.add(component.label());
            }
          }
          if (!absent.isEmpty()) {
            warnings.add(
                "本租户在【"
                    + dim.label()
                    + "】维度上**没有** "
                    + String.join("、", absent)
                    + " 的事实：这几项**不参与毛利计算**。若上游其实有、只是还没投影到本服务，毛利会偏高。");
          }
          warnings.add("报表是投影，数据更新时间取自投影落库时刻；与业务系统冲突时以业务系统为准。");

          return new ReportingProfitReportModels.ProfitReportResponse(
              from,
              to,
              grain.name(),
              dim.name(),
              reportRows,
              modes(tenantId),
              dataUpdatedAt(tenantId),
              "最近一次投影落库时刻（利润事实与店铺档案两张表 updated_at 取较晚者）。",
              caliberNote(dim, grain),
              warnings);
        });
  }

  // ==========================================================================
  // 下钻
  // ==========================================================================

  /** 下钻构成明细（真分页 + 真计数）。 */
  public ReportingProfitReportModels.ProfitDrilldownPage drilldown(
      UUID tenantId,
      LocalDate from,
      LocalDate to,
      String dimension,
      String dimensionCode,
      String operatingMode,
      int page,
      int size) {
    return tenants.run(
        tenantId,
        () -> {
          var dim =
              ReportingProfitReportModels.Dimension.parse(dimension) == null
                  ? ReportingProfitReportModels.Dimension.MODE
                  : ReportingProfitReportModels.Dimension.parse(dimension);
          var dimSql = dimSql(dim);
          var params = new ArrayList<Object>();
          params.add(tenantId);
          params.add(from);
          params.add(to);
          var filters = new StringBuilder();
          if (dim != ReportingProfitReportModels.Dimension.MODE) {
            filters.append(" and ").append(dimSql.groupBy()).append(" = ?");
            params.add(dimensionCode == null ? "" : dimensionCode);
          }
          if (operatingMode != null && !operatingMode.isBlank()) {
            filters.append(" and operating_mode = ?");
            params.add(operatingMode);
          }
          var where =
              " where tenant_id = ? and period_start >= ? and period_start <= ?"
                  + filters
                  + dimSql.filter();

          var total = sql.fetchOne("select count(*) from reporting_profit_fact" + where, params.toArray());
          var rows =
              sql.fetch(
                  "select component, operating_mode, shop_code, sku_code, spu_code, msku_code, order_ref,"
                      + " period_start, period_end, currency, amount, quantity, source_service,"
                      + " source_reference from reporting_profit_fact"
                      + where
                      + " order by component, period_start, source_reference limit ? offset ?",
                  append(params, size, Math.max(page, 0) * size));

          var items = new ArrayList<ReportingProfitReportModels.ProfitDrilldownRow>();
          for (var row : rows) {
            var component = row.get(0, String.class);
            var mode = row.get(1, String.class);
            items.add(
                new ReportingProfitReportModels.ProfitDrilldownRow(
                    component,
                    componentLabel(component),
                    mode,
                    modeLabel(mode),
                    row.get(2, String.class),
                    row.get(3, String.class),
                    row.get(4, String.class),
                    row.get(5, String.class),
                    row.get(6, String.class),
                    row.get(7, LocalDate.class),
                    row.get(8, LocalDate.class),
                    row.get(9, String.class),
                    row.get(10, BigDecimal.class),
                    row.get(11, BigDecimal.class),
                    row.get(12, String.class),
                    row.get(13, String.class),
                    sourceNote(row.get(12, String.class))));
          }
          return new ReportingProfitReportModels.ProfitDrilldownPage(
              items,
              Math.max(page, 0),
              size,
              total == null ? 0L : total.get(0, Long.class),
              dim.name(),
              dimensionCode == null ? "" : dimensionCode,
              operatingMode == null ? "" : operatingMode,
              "下钻列出的是这一格背后的**事实行**（来源服务 + 上游行标识），"
                  + "可以逐条回到上游核对。");
        });
  }

  // ==========================================================================
  // 经营模式清单
  // ==========================================================================

  /** 该租户的经营模式清单（每个模式带店铺数与店铺编码）。 */
  public List<ReportingProfitReportModels.ProfitModeView> modes(UUID tenantId) {
    return tenants.run(
        tenantId,
        () -> {
          var rows =
              sql.fetch(
                  "select operating_mode, count(*), array_agg(shop_code order by shop_code)"
                      + " from reporting_profit_shop_profile where tenant_id = ?"
                      + " group by operating_mode order by operating_mode",
                  tenantId);
          var out = new ArrayList<ReportingProfitReportModels.ProfitModeView>();
          for (var row : rows) {
            var mode = row.get(0, String.class);
            var shops = new ArrayList<String>();
            var array = row.get(2, Object[].class);
            if (array != null) {
              for (var item : array) {
                if (item != null) shops.add(item.toString());
              }
            }
            out.add(
                new ReportingProfitReportModels.ProfitModeView(
                    mode,
                    modeLabel(mode),
                    row.get(1, Long.class) == null ? 0L : row.get(1, Long.class),
                    shops,
                    "UNKNOWN".equals(mode)
                        ? "这些店铺上游没给经营模式（或还没建档）——它们的收入与成本归「未归属」，不混进任何真实模式。"
                        : "模式取自 channel 2.3.2.1 的 `shop.operating_mode`。"));
          }
          return out;
        });
  }

  // ==========================================================================
  // 能力声明
  // ==========================================================================

  /** 能力声明：哪些上游真接通、哪些确实算不出来、归谁管。 */
  public ReportingProfitReportModels.ProfitReportCapabilities capabilities() {
    return new ReportingProfitReportModels.ProfitReportCapabilities(
        true,
        List.of(
            ReportingProfitReportModels.Component.REVENUE.label(),
            ReportingProfitReportModels.Component.COGS.label(),
            ReportingProfitReportModels.Component.PLATFORM_FEE.label(),
            ReportingProfitReportModels.Component.LOGISTICS_FEE.label(),
            ReportingProfitReportModels.Component.AD_FEE.label(),
            ReportingProfitReportModels.Component.HEAD_FREIGHT.label(),
            ReportingProfitReportModels.Component.OTHER_COST.label()),
        List.of(
            ReportingProfitReportModels.Dimension.MODE.label(),
            ReportingProfitReportModels.Dimension.SHOP.label(),
            ReportingProfitReportModels.Dimension.ORDER.label(),
            ReportingProfitReportModels.Dimension.SKU.label(),
            ReportingProfitReportModels.Dimension.SPU.label(),
            ReportingProfitReportModels.Dimension.MSKU.label()),
        List.of(
            "2.3.2.1 经营模式（channel：GET /channel/shops 的 `shop.operating_mode`）",
            "9 订单收入（trade：GET /trade/sales-orders/list + /detail/{id}）",
            "12.3.1.2 结算明细（finance：GET /finance/transactions，佣金 / FBA 费用 / 广告）",
            "12.4 成本归集 + 12.4.2.2 分摊结果（finance：GET /finance/cost-collections/rules、"
                + "/cost-allocations/pools、/batches、/results）",
            "汇率快照（reporting_currency_rate_snapshot，本服务的既有快照表）"),
        List.of(
            "**SKU / SPU / MSKU 维度的成本常常算不出来**：上游 12.4.2.2 把费用池分摊到**店铺 / 订单**维度时，"
                + "SKU 维度就没有成本 —— 那一格显示「—（不可比）」，**不是 0**。"
                + "SKU 级利润的口径归 13.4.1.2。",
            "**挂不上店铺的订单归「未归属」**：销售订单本身没有店铺字段，归属来自 trade 的平台单回挂；"
                + "挂不上的订单不会被塞进任何一个模式（塞进去就是编造）。",
            "**平台费 / 物流费 / 广告费的唯一写入所有者是 12.3.1.2 结算明细**："
                + "12.4 费用池里的同名费用**不重复计入**（同一笔钱记两次就是重复计成本）。"
                + "若上游只归集了费用池而没有结算明细，这三项会显示为「算不出来」。",
            "**MSKU 需要唯一归属**：一个内部 SKU 挂在多个平台商品上时无法唯一归属（与 13.2.2.2 同一纪律），"
                + "此时 MSKU 留空，MSKU 维度那一格没有事实。",
            "**汇率用上游快照**：本位币合计只在**显式指定**本位币、且汇率快照里有生效日不晚于窗口末的汇率时给出，"
                + "并把汇率与时点一起返回 —— 本模块**不拉实时汇率**。",
            "**上游的推事件**：上游尚未产出利润事实型 outbox 事件 ⇒ 现在的事实来自 "
                + "POST /reporting/profit-report/refresh 的拉取路径。"),
        "口径归属：收入取 trade 订单（订单总额 / 订单行金额两种口径）；"
            + "平台费 / 物流费 / 广告费取 12.3.1.2 结算明细；头程费与其它成本取 12.4 归集 + 12.4.2.2 分摊结果；"
            + "经营模式取 2.3.2.1。**本模块不重算费率、不重算分摊、不拉实时汇率。**");
  }

  // ==========================================================================
  // 数据更新时间
  // ==========================================================================

  /**
   * 数据更新时间（卷 13 卷首「数据延迟要显示」）。
   *
   * <p>⚠️ **两张表都空时返回 {@code null}**（写「尚未投影任何利润事实」）——
   * 不用 {@code '-infinity'::timestamptz} 当哨兵：那是一个长得像时间的假值。
   */
  public OffsetDateTime dataUpdatedAt(UUID tenantId) {
    return tenants.run(
        tenantId,
        () -> {
          var factRow =
              sql.fetchOne("select max(updated_at) from reporting_profit_fact where tenant_id = ?", tenantId);
          var profileRow =
              sql.fetchOne(
                  "select max(updated_at) from reporting_profit_shop_profile where tenant_id = ?", tenantId);
          var factAt = factRow == null ? null : factRow.get(0, OffsetDateTime.class);
          var profileAt = profileRow == null ? null : profileRow.get(0, OffsetDateTime.class);
          if (factAt == null) return profileAt;
          if (profileAt == null) return factAt;
          return factAt.isAfter(profileAt) ? factAt : profileAt;
        });
  }

  // ==========================================================================
  // 内部
  // ==========================================================================

  /** 一个分组（模式 × 维度取值 × 期间桶 × 币种）。 */
  private record Group(
      String mode, String dimensionCode, LocalDate bucketStart, String currency, Map<String, Amount> amounts) {
    Group(String mode, String dimensionCode, LocalDate bucketStart, String currency) {
      this(mode, dimensionCode, bucketStart, currency, new LinkedHashMap<>());
    }
  }

  /** 一个成本项的聚合值。 */
  private record Amount(BigDecimal amount, BigDecimal quantity, long count) {}

  /** 维度在 SQL 里的三段（选中列 / 分组列 / 过滤条件）。 */
  private record DimSql(String select, String groupBy, String filter) {}

  /**
   * 维度 → SQL 三段。
   *
   * <p>⚠️ **收入的两种口径在这里分开**（不混用、不相加）：
   * 经营模式 / 店铺 / 订单维度只取**订单总额口径**的收入行
   * （{@code sku_code / spu_code / msku_code} 全为空的那一批）；
   * SKU / SPU / MSKU 维度只取**订单行金额口径**的收入行（该维度列非空的那一批）。
   */
  private static DimSql dimSql(ReportingProfitReportModels.Dimension dim) {
    return switch (dim) {
      case MODE -> new DimSql("''", "", revenueCaliber());
      case SHOP -> new DimSql("shop_code", "shop_code", " and shop_code <> ''" + revenueCaliber());
      case SKU -> new DimSql("sku_code", "sku_code", " and sku_code <> ''");
      case SPU -> new DimSql("spu_code", "spu_code", " and spu_code <> ''");
      case MSKU -> new DimSql("msku_code", "msku_code", " and msku_code <> ''");
      case ORDER -> new DimSql("order_ref", "order_ref", " and order_ref <> ''" + revenueCaliber());
    };
  }

  /** 订单总额口径的收入过滤（**只有没有 SKU 归属的那一批收入行**）。 */
  private static String revenueCaliber() {
    return " and (component <> 'REVENUE' or (sku_code = '' and spu_code = '' and msku_code = ''))";
  }

  /**
   * 组装一行。
   *
   * <p>⚠️ **毛利只在「成本项齐全且至少有一项成本」时才算**：
   * 缺任何一项 ⇒ {@code null}（页面显示「—（不可比）」），**不是 0**。
   *
   * <p>⚠️ 「适用」按**租户在该维度上**的事实判定（不是按模式）：
   * 租户在这里压根没有广告费 ⇒ 广告费**不适用**（不参与毛利计算）；
   * 租户在这里有广告费、只是**本模式没有** ⇒ 广告费**适用**而本行缺它 ⇒ 毛利算不出来。
   * 用租户口径而不是模式口径，是为了让**各模式的判定一致**。
   */
  private ReportingProfitReportModels.ProfitReportRow buildRow(
      UUID tenantId,
      ReportingProfitReportModels.Dimension dim,
      Group group,
      Set<String> expected,
      String baseCurrency,
      LocalDate windowEnd) {
    var components = new ArrayList<ReportingProfitReportModels.ProfitComponentAmount>();
    var missing = new ArrayList<String>();
    var totalCost = BigDecimal.ZERO;
    var hasCost = false;
    var revenue = (BigDecimal) null;

    for (var component : ReportingProfitReportModels.Component.values()) {
      var amount = group.amounts().get(component.name());
      if (amount == null) {
        // ⚠️ **分两种「没有」**：本模式压根没有这类成本（不适用）vs 本租户有、这个模式没有（算不出来）。
        var applicable = expected.contains(component.name());
        if (applicable) missing.add(component.label());
        components.add(
            new ReportingProfitReportModels.ProfitComponentAmount(
                component.name(),
                component.label(),
                null,
                false,
                applicable
                    ? "本模式在本窗口内没有这类成本事实（该成本项在本租户的这个维度上存在）—— "
                        + "**算不出来，不是 0**（可能确实没有该笔费用，也可能上游未归属到本模式）。"
                    : "本租户在该维度上**没有这类成本事实**（不适用，不参与毛利计算；"
                        + "若上游其实有、只是还没投影，毛利会偏高）。"));
        continue;
      }
      components.add(
          new ReportingProfitReportModels.ProfitComponentAmount(
              component.name(), component.label(), amount.amount(), true, ""));
      if (component.isCost()) {
        hasCost = true;
        totalCost = totalCost.add(amount.amount());
      } else {
        revenue = amount.amount();
      }
    }

    var comparable = revenue != null && hasCost && missing.isEmpty();
    BigDecimal totalCostValue = null;
    BigDecimal grossProfit = null;
    BigDecimal grossMargin = null;
    if (comparable) {
      totalCostValue = totalCost;
      grossProfit = revenue.subtract(totalCost);
      grossMargin =
          revenue.signum() == 0
              ? null
              : grossProfit.multiply(new BigDecimal("100")).divide(revenue, 8, RoundingMode.HALF_UP)
                  .setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }

    // ---- 本位币合计（**只在显式指定且有汇率快照时**）----
    String baseCurrencyValue = null;
    BigDecimal baseRevenue = null;
    BigDecimal baseGrossProfit = null;
    BigDecimal baseRate = null;
    OffsetDateTime baseRateAsOf = null;
    if (baseCurrency != null && !baseCurrency.isBlank() && revenue != null) {
      if (group.currency().equals(baseCurrency)) {
        baseCurrencyValue = baseCurrency;
        baseRevenue = revenue;
        baseGrossProfit = grossProfit;
        baseRate = BigDecimal.ONE;
      } else {
        var rate = resolveRate(tenantId, group.currency(), baseCurrency, windowEnd);
        if (rate != null) {
          baseCurrencyValue = baseCurrency;
          baseRate = rate.rate();
          baseRateAsOf = rate.asOf();
          baseRevenue = revenue.multiply(rate.rate()).setScale(4, RoundingMode.HALF_UP);
          if (grossProfit != null) {
            baseGrossProfit = grossProfit.multiply(rate.rate()).setScale(4, RoundingMode.HALF_UP);
          }
        }
      }
    }

    var note = rowNote(dim, group, comparable, missing);
    return new ReportingProfitReportModels.ProfitReportRow(
        dim.name(),
        group.dimensionCode(),
        dimensionLabel(dim, group.dimensionCode()),
        group.mode(),
        modeLabel(group.mode()),
        group.bucketStart(),
        group.bucketStart(),
        group.currency(),
        components,
        totalCostValue,
        grossProfit,
        grossMargin,
        comparable,
        missing,
        baseCurrencyValue,
        baseRevenue,
        baseGrossProfit,
        baseRate,
        baseRateAsOf,
        group.amounts().values().stream().mapToLong(Amount::count).sum(),
        note);
  }

  /** 一行的口径说明（**写清这一行是怎么算出来的**）。 */
  private static String rowNote(
      ReportingProfitReportModels.Dimension dim,
      Group group,
      boolean comparable,
      List<String> missing) {
    var note = new StringBuilder();
    switch (dim) {
      case MODE -> note.append("经营模式维度：收入取**订单总额**口径（trade `grand_total_amount`）。");
      case SHOP -> note.append("店铺维度：收入取**订单总额**口径（trade `grand_total_amount`）。");
      case ORDER -> note.append("订单维度：收入取**订单总额**口径（trade `grand_total_amount`）。");
      case SKU -> note.append("SKU 维度：收入取**订单行金额**口径（trade `line_amount` 之和，不含订单级运费 / 税 / 折扣）。");
      case SPU -> note.append("SPU 维度：收入取**订单行金额**口径（trade `line_amount` 之和，不含订单级运费 / 税 / 折扣）。");
      case MSKU -> note.append("MSKU 维度：收入取**订单行金额**口径（trade `line_amount` 之和，不含订单级运费 / 税 / 折扣）。");
    }
    if ("UNKNOWN".equals(group.mode())) {
      note.append(" 本行归「未归属」：这笔钱挂不上店铺，或上游没给经营模式 —— **不混进任何真实模式**。");
    }
    if (!comparable) {
      note.append(" 毛利**算不出来**：");
      if (missing.isEmpty()) {
        note.append("本模式在本窗口内**完全没有成本事实**（成本侧未知，不是成本为 0）。");
      } else {
        note.append("缺 ").append(String.join("、", missing)).append("（成本项不全，毛利不可比）。");
      }
    }
    return note.toString();
  }

  /** 口径说明（**收入怎么算、成本取自哪一路上游、汇率用哪个快照、哪些维度算不出来**）。 */
  private static String caliberNote(
      ReportingProfitReportModels.Dimension dim, ReportingProfitReportModels.Granularity grain) {
    var note = new StringBuilder();
    note.append("收入：");
    switch (dim) {
      case MODE, SHOP, ORDER ->
          note.append("订单总额口径（trade `grand_total_amount`，含订单级运费 / 税 / 折扣）。");
      case SKU, SPU, MSKU ->
          note.append("订单行金额口径（trade `line_amount` 之和，不含订单级运费 / 税 / 折扣）—— "
              + "与订单总额口径**不是同一笔钱**，两者不相加。");
    }
    note.append(" 成本：平台费 / 物流费 / 广告费取 12.3.1.2 结算明细（唯一写入所有者）；"
        + "商品成本 / 头程费 / 其它成本取 12.4 归集 + 12.4.2.2 分摊结果。");
    note.append(" 分摊与费率都在上游，本模块**不重算**。");
    note.append(" 汇率：只用 `reporting_currency_rate_snapshot` 里的**上游快照**（按窗口末取最近一条），"
        + "本模块**不拉实时汇率**；没有快照就不给本位币合计。");
    note.append(" 期间：按事实的**期间起始日**落桶（")
        .append(grain == ReportingProfitReportModels.Granularity.MONTH ? "按月" : "按天")
        .append("）；月度分摊池的费用落在当月 1 号，按天看时只有那一天有它。");
    note.append(" 不同经营模式的利润含义完全不同，**模式之间不合并**。");
    return note.toString();
  }

  /** 汇率快照。 */
  private record RateSnapshot(BigDecimal rate, OffsetDateTime asOf) {}

  /** 取生效日不晚于 {@code asOf} 的最近一条汇率快照。 */
  private RateSnapshot resolveRate(
      UUID tenantId, String currency, String baseCurrency, LocalDate asOf) {
    var row =
        sql.fetchOne(
            "select rate, as_of from reporting_currency_rate_snapshot "
                + "where tenant_id = ? and currency = ? and base_currency = ? "
                + "and as_of <= ?::timestamptz "
                + "order by as_of desc limit 1",
            tenantId,
            currency,
            baseCurrency,
            asOf.atStartOfDay(java.time.ZoneOffset.UTC).toOffsetDateTime());
    if (row == null) return null;
    return new RateSnapshot(row.get(0, BigDecimal.class), row.get(1, OffsetDateTime.class));
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  private static String modeLabel(String mode) {
    var parsed = ReportingProfitReportModels.OperatingMode.parse(mode);
    return parsed == null ? mode : parsed.label();
  }

  private static String componentLabel(String component) {
    var parsed = ReportingProfitReportModels.Component.parse(component);
    return parsed == null ? component : parsed.label();
  }

  private static String dimensionLabel(ReportingProfitReportModels.Dimension dim, String code) {
    if (dim == ReportingProfitReportModels.Dimension.MODE) return dim.label();
    return code == null || code.isBlank() ? "（未归属到该维度）" : code;
  }

  private static String sourceNote(String sourceService) {
    return switch (sourceService == null ? "" : sourceService) {
      case "trade" -> "来源：9 订单（trade）";
      case "finance" -> "来源：12 财务（finance）";
      case "channel" -> "来源：2 渠道（channel）";
      case "catalog" -> "来源：5 商品（catalog）";
      default -> "来源：" + sourceService;
    };
  }

  /** 拼接查询参数。 */
  private static Object[] append(List<Object> params, Object... extra) {
    var out = new ArrayList<Object>(params);
    for (var item : extra) out.add(item);
    return out.toArray();
  }
}
