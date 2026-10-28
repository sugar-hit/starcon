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
 * 13.4.1.2 SKU 利润的**读模型**（蓝图「输出」句：SKU 利润表 + 瀑布图 + 订单明细钻取）。
 *
 * <p>⚠️ **本模块拥有 SKU 级的口径**：13.4.1.1 的利润报表按**经营模式**分行，而 SKU 维度的成本
 * 由上游 12.4.2.2 **按 SKU 维度**分摊（**不落到经营模式**）⇒ 本模块的一行是
 * 「一个 SKU × 一个币种」，收入按经营模式给出构成，成本是 SKU 口径的合计。
 *
 * <p>⚠️ **「算不出来」与「等于 0」必须分开**：某一类成本拿不到时它是 {@code null}
 * （页面上是「—」），**不是 0**；净利算不出来时整个 {@code netProfit} 是 {@code null}
 * 并附一句原因（缺哪几项、为什么拿不到）。
 *
 * <p>⚠️ **不重算费率、不重算分摊**：SKU 维度的成本**只取上游已经分摊到 SKU 的那些行**。
 * 上游把成本池分摊到**店铺 / 订单**时，SKU 维度就拿不到这笔成本 —— 那一格是
 * 「不可归属到 SKU」，**不是 0**（把它当 0 等于说「这个 SKU 完全没花钱」）。
 *
 * <p>⚠️ **不跨币种相加**：每一行都带币种；跨币种的合计不存在。
 */
@Service
public class ReportingSkuProfitService {

  /** 净利率的标度（百分数，保留 4 位 —— 与既有指标口径一致）。 */
  private static final int RATE_SCALE = 4;

  /** 中间除法保留的位数。 */
  private static final int DIVIDE_SCALE = 8;

  /** 下钻每页最多多少行。 */
  static final int MAX_PAGE_SIZE = 200;

  private final DSLContext sql;
  private final TenantTransactions tenants;

  public ReportingSkuProfitService(DSLContext sql, TenantTransactions tenants) {
    this.sql = sql;
    this.tenants = tenants;
  }

  // ==========================================================================
  // SKU 利润表
  // ==========================================================================

  /**
   * SKU 利润表。
   *
   * @param skuCode 只看某个 SKU（空 ⇒ 全部）
   * @param currency 只看某个币种（空 ⇒ 全部，**绝不跨币种相加**）
   */
  public ReportingSkuProfitModels.SkuProfitResponse report(
      UUID tenantId, LocalDate from, LocalDate to, String skuCode, String currency) {
    return tenants.run(
        tenantId,
        () -> {
          var facts = loadFacts(tenantId, from, to, skuCode, currency);
          var rows = new ArrayList<ReportingSkuProfitModels.SkuProfitRow>();
          for (var group : facts.groups().values()) {
            rows.add(buildRow(group, facts));
          }

          var comparable = rows.stream().filter(r -> r.netProfitComparable()).count();
          var warnings = new ArrayList<String>();
          if (rows.isEmpty()) {
            warnings.add("本窗口内**没有投影任何 SKU 级事实**（不是「利润为 0」）：先刷新利润事实再来看。");
          }
          var notComparable = rows.size() - comparable;
          if (notComparable > 0) {
            warnings.add(
                "有 "
                    + notComparable
                    + " 个 SKU 的净利**算不出来**（显示「—（不可比）」，不是 0）：每一行都写明了缺哪几项、为什么拿不到。");
          }
          var unattributable = new LinkedHashSet<String>();
          for (var row : rows) {
            for (var component : row.components()) {
              if ("NOT_ATTRIBUTABLE".equals(component.status())) {
                unattributable.add(component.label());
              }
            }
          }
          if (!unattributable.isEmpty()) {
            warnings.add(
                "**"
                    + String.join("、", unattributable)
                    + " 在 SKU 维度上不可归属**（上游只分摊到店铺 / 订单）—— 那些 SKU 的净利不可比。"
                    + "本模块**不重算分摊**：要让 SKU 级净利可比，上游 12.4.2.2 必须把成本池按 **SKU 维度**分摊。");
          }
          var multiMode = new LinkedHashSet<String>();
          for (var row : rows) {
            if (row.modeBreakdown().size() > 1) multiMode.add(row.skuCode());
          }
          if (!multiMode.isEmpty()) {
            warnings.add(
                "有 "
                    + multiMode.size()
                    + " 个 SKU 在**多个经营模式**下有销售，而 SKU 级成本不落到经营模式 ⇒ 那些行的净利是**跨模式口径**"
                    + "（逐模式的收入构成见每行的「经营模式构成」）。");
          }
          warnings.add("报表是投影，数据更新时间取自投影落库时刻；与业务系统冲突时以业务系统为准。");

          return new ReportingSkuProfitModels.SkuProfitResponse(
              from,
              to,
              "SKU",
              rows,
              rows.size(),
              comparable,
              notComparable,
              dataUpdatedAt(tenantId),
              "最近一次投影落库时刻（利润事实表与退款损失事实表两张表 updated_at 取较晚者）。",
              caliberNote(),
              warnings);
        });
  }

  // ==========================================================================
  // 构成瀑布
  // ==========================================================================

  /** 构成瀑布（**一个 SKU 一个币种**；算不出来的那一步是「—」）。 */
  public ReportingSkuProfitModels.SkuProfitWaterfall waterfall(
      UUID tenantId, LocalDate from, LocalDate to, String skuCode, String currency) {
    return tenants.run(
        tenantId,
        () -> {
          var facts = loadFacts(tenantId, from, to, skuCode, currency);
          var group = resolveGroup(facts, skuCode, currency);
          if (group == null) {
            // ⚠️ **没有事实就说没有**：不给一个「全 0」的瀑布（那会被读成「这个 SKU 不赚钱」）。
            return new ReportingSkuProfitModels.SkuProfitWaterfall(
                skuCode,
                currency,
                List.of(),
                null,
                false,
                "这个 SKU 在这个窗口内**没有投影任何事实**（不是「利润为 0」）。");
          }
          var row = buildRow(group, facts);
          var steps = new ArrayList<ReportingSkuProfitModels.SkuProfitWaterfallStep>();
          var cumulative = BigDecimal.ZERO;
          var broken = false;
          var index = 0;
          for (var component : ReportingSkuProfitModels.Component.values()) {
            index++;
            var amount = row.components().stream()
                .filter(c -> c.component().equals(component.name()))
                .findFirst()
                .orElseThrow()
                .amount();
            var available = amount != null;
            if (!available) broken = true;
            var signed = available && component.isCost() ? amount.negate() : amount;
            var running = available && !broken ? cumulative.add(signed) : null;
            if (available && !broken) cumulative = running;
            steps.add(
                new ReportingSkuProfitModels.SkuProfitWaterfallStep(
                    index,
                    component.name(),
                    component.label(),
                    amount,
                    component.isCost() ? "-" : "+",
                    running,
                    available,
                    available
                        ? ""
                        : row.components().stream()
                            .filter(c -> c.component().equals(component.name()))
                            .findFirst()
                            .orElseThrow()
                            .reason()));
          }
          index++;
          steps.add(
              new ReportingSkuProfitModels.SkuProfitWaterfallStep(
                  index,
                  "NET_PROFIT",
                  "净利",
                  row.netProfit(),
                  "=",
                  row.netProfit(),
                  row.netProfitComparable(),
                  row.netProfitComparable()
                      ? ""
                      : "净利**算不出来**（不是 0）：" + String.join("、", row.missingComponents())));

          return new ReportingSkuProfitModels.SkuProfitWaterfall(
              row.skuCode(),
              row.currency(),
              steps,
              row.netProfit(),
              row.netProfitComparable(),
              "瀑布的每一步都是**服务端算好的**：收入 → 逐项成本 → 净利；"
                  + "任何一项拿不到，净利就是「—（不可比）」，**不是 0**。");
        });
  }

  // ==========================================================================
  // 订单明细钻取
  // ==========================================================================

  /** 订单级明细钻取（真分页 + 真计数）。 */
  public ReportingSkuProfitModels.SkuProfitOrderPage orders(
      UUID tenantId, LocalDate from, LocalDate to, String skuCode, String currency, int page, int size) {
    return tenants.run(
        tenantId,
        () -> {
          var params = new ArrayList<Object>();
          // ⚠️ **参数顺序必须与 SQL 里的占位符顺序逐字一致**：
          //   第一个分支的（租户, SKU, 起, 止[, 币种]）之后**才是**第二个分支的 ——
          //   把两个分支的参数都攒到最后会让第 5 个占位符拿到租户号（uuid），
          //   数据库报的是「operator does not exist: character = uuid」，看不出是顺序错了。
          params.add(tenantId);
          params.add(skuCode);
          params.add(from);
          params.add(to);
          if (currency != null && !currency.isBlank()) params.add(currency);
          params.add(tenantId);
          params.add(skuCode);
          params.add(from);
          params.add(to);
          if (currency != null && !currency.isBlank()) params.add(currency);
          var union =
              "select component, operating_mode, order_ref, sku_code, period_start, period_end,"
                  + " currency, amount, quantity, source_service, source_reference"
                  + " from reporting_profit_fact"
                  + " where tenant_id = ? and sku_code = ? and period_start >= ? and period_start <= ?"
                  + (currency != null && !currency.isBlank() ? " and currency = ?" : "")
                  + " union all select 'REFUND_LOSS', operating_mode, order_ref, sku_code,"
                  + " period_start, period_end, currency, amount, quantity, source_service,"
                  + " source_reference"
                  + " from reporting_sku_profit_refund_fact"
                  + " where tenant_id = ? and sku_code = ? and period_start >= ? and period_start <= ?"
                  + (currency != null && !currency.isBlank() ? " and currency = ?" : "");

          var total = sql.fetchOne("select count(*) from (" + union + ") t", params.toArray());
          var rows =
              sql.fetch(
                  "select component, operating_mode, order_ref, sku_code, period_start, period_end,"
                      + " currency, amount, quantity, source_service, source_reference"
                      + " from ("
                      + union
                      + ") t order by period_start, component, order_ref, source_reference limit ? offset ?",
                  append(params, size, Math.max(page, 0) * size));

          var items = new ArrayList<ReportingSkuProfitModels.SkuProfitOrderRow>();
          for (var row : rows) {
            var component = row.get(0, String.class);
            var mode = row.get(1, String.class);
            items.add(
                new ReportingSkuProfitModels.SkuProfitOrderRow(
                    component,
                    componentLabel(component),
                    mode,
                    modeLabel(mode),
                    row.get(2, String.class),
                    row.get(3, String.class),
                    row.get(4, LocalDate.class),
                    row.get(5, LocalDate.class),
                    row.get(6, String.class),
                    row.get(7, BigDecimal.class),
                    row.get(8, BigDecimal.class),
                    row.get(9, String.class),
                    row.get(10, String.class),
                    sourceNote(row.get(9, String.class))));
          }
          return new ReportingSkuProfitModels.SkuProfitOrderPage(
              items,
              Math.max(page, 0),
              size,
              total == null ? 0L : total.get(0, Long.class),
              skuCode,
              currency == null ? "" : currency,
              "下钻列出的是这个 SKU 背后的**事实行**（来源服务 + 上游行标识），可以逐条回到上游核对。");
        });
  }

  // ==========================================================================
  // 能力声明
  // ==========================================================================

  /** 能力声明：哪些上游真接通、哪些确实算不出来、归谁管。 */
  public ReportingSkuProfitModels.SkuProfitCapabilities capabilities() {
    return new ReportingSkuProfitModels.SkuProfitCapabilities(
        true,
        List.of(
            ReportingSkuProfitModels.Component.REVENUE.label(),
            ReportingSkuProfitModels.Component.COGS.label(),
            ReportingSkuProfitModels.Component.HEAD_FREIGHT.label(),
            ReportingSkuProfitModels.Component.PLATFORM_FEE.label(),
            ReportingSkuProfitModels.Component.LOGISTICS_FEE.label(),
            ReportingSkuProfitModels.Component.AD_FEE.label(),
            ReportingSkuProfitModels.Component.REFUND_LOSS.label()),
        List.of("SKU"),
        List.of(
            "13.4.1.1 多模式利润报表的投影事实（reporting_profit_fact：单品收入 / 采购成本 / 头程 / 平台费 / 物流费 / 广告费）",
            "12.4.2.2 成本分摊结果（上游按 **SKU 维度**分摊的那些行）",
            "9 订单 / 11 售后的**退款申请单**（trade `GET /trade/refund-applications` + `/trade/aftersales/cases/{id}`）"),
        List.of(
            "**SKU 级成本常常拿不到**：上游 12.4.2.2 把成本池分摊到**店铺 / 订单**维度时，"
                + "SKU 维度就没有这笔成本 —— 那一格显示「不可归属到 SKU」，**不是 0**。"
                + "本模块**不重算分摊**（重算就会出现第二个真相）：要让 SKU 级净利可比，"
                + "上游必须把成本池按 **SKU 维度**分摊。",
            "**退款损失只算已经退出去的钱**：只有 `application_status = 'SUCCEEDED'` 的退款申请单算损失；"
                + "申请中 / 审批中 / 执行中都不算（申请了不等于退了）。",
            "**退款挂不到唯一 SKU 时就不挂**：退款金额与售后单涉及行的申请金额之和逐字相等才按行归属；"
                + "对不上（部分退款 / 上游改了金额）就**不摊给任何 SKU**，并在刷新结果里如实报数量。",
            "**SKU 级成本不落到经营模式**：上游按 SKU 维度分摊时并没有说这笔成本属于哪个模式 ⇒ "
                + "一个 SKU 若在多个模式下有销售，它的净利是**跨模式口径**（收入的逐模式构成在每一行里给出）。",
            "**不跨币种相加**：每一行带自己的币种；跨币种的净利合计不存在。",
            "**报表是投影**：与业务系统冲突时以业务系统为准；本模块**没有任何「创建业务事实」的权限**。"),
        "口径归属：单品收入 / 采购成本 / 头程 / 平台费 / 物流费 / 广告费取 13.4.1.1 的利润事实"
            + "（SKU 维度只取上游已分摊到 SKU 的行）；退款损失取 9/11 售后的已成功退款（本模块拥有）；"
            + "**本模块不重算费率、不重算分摊、不拉实时汇率。**");
  }

  // ==========================================================================
  // 数据更新时间
  // ==========================================================================

  /** 数据更新时间（两张表都空时返回 {@code null} —— **「尚未投影任何事实」**）。 */
  public OffsetDateTime dataUpdatedAt(UUID tenantId) {
    return tenants.run(
        tenantId,
        () -> {
          var factRow =
              sql.fetchOne("select max(updated_at) from reporting_profit_fact where tenant_id = ?", tenantId);
          var refundRow =
              sql.fetchOne(
                  "select max(updated_at) from reporting_sku_profit_refund_fact where tenant_id = ?",
                  tenantId);
          var factAt = factRow == null ? null : factRow.get(0, OffsetDateTime.class);
          var refundAt = refundRow == null ? null : refundRow.get(0, OffsetDateTime.class);
          if (factAt == null) return refundAt;
          if (refundAt == null) return factAt;
          return factAt.isAfter(refundAt) ? factAt : refundAt;
        });
  }

  // ==========================================================================
  // 内部
  // ==========================================================================

  /** 一个 SKU × 一个币种的聚合。 */
  private record SkuGroup(
      String skuCode,
      String currency,
      Map<String, Amount> amounts,
      Map<String, Amount> modeRevenue) {}

  /** 一个成本项（或一段收入）的聚合值。 */
  private record Amount(BigDecimal amount, BigDecimal quantity, long count) {}

  /** 一次读出来的事实与判定所需的三个集合。 */
  private record Facts(
      Map<String, SkuGroup> groups,
      Set<String> skuLevelComponents,
      Set<String> tenantComponents,
      long tenantRefundCount) {}

  /** 分组键（SKU × 币种 —— **币种必须在键里**，绝不跨币种相加）。 */
  private static String key(String skuCode, String currency) {
    return skuCode + "|" + currency;
  }

  /**
   * 取某个 SKU 的分组。
   *
   * <p>⚠️ **币种没指定时不能随便挑一个**：一个 SKU 有两个币种时，瀑布图给哪一个都是编造 ⇒
   * 返回 {@code null} 并让调用方给出「请指定币种」。
   */
  private static SkuGroup resolveGroup(Facts facts, String skuCode, String currency) {
    if (currency != null && !currency.isBlank()) {
      return facts.groups().get(key(skuCode, currency));
    }
    SkuGroup found = null;
    for (var group : facts.groups().values()) {
      if (!group.skuCode().equals(skuCode)) continue;
      if (found != null) return null;
      found = group;
    }
    return found;
  }

  /** 读事实：SKU 级利润事实 + 退款损失事实 + 两个「适用性」判定集合。 */
  private Facts loadFacts(UUID tenantId, LocalDate from, LocalDate to, String skuCode, String currency) {
    var params = new ArrayList<Object>();
    params.add(tenantId);
    params.add(from);
    params.add(to);
    var filters = new StringBuilder();
    if (skuCode != null && !skuCode.isBlank()) {
      filters.append(" and sku_code = ?");
      params.add(skuCode);
    }
    if (currency != null && !currency.isBlank()) {
      filters.append(" and currency = ?");
      params.add(currency);
    }

    var groups = new LinkedHashMap<String, SkuGroup>();

    // ---- ① 13.4.1.1 的利润事实（SKU 维度）----
    var rows =
        sql.fetch(
            "select sku_code, operating_mode, component, currency, sum(amount), sum(quantity), count(*)"
                + " from reporting_profit_fact"
                + " where tenant_id = ? and period_start >= ? and period_start <= ? and sku_code <> ''"
                + filters
                + " group by sku_code, operating_mode, component, currency",
            params.toArray());
    for (var row : rows) {
      var code = row.get(0, String.class);
      var mode = row.get(1, String.class);
      var component = row.get(2, String.class);
      var rowCurrency = row.get(3, String.class);
      var amount = row.get(4, BigDecimal.class);
      var quantity = row.get(5, BigDecimal.class);
      var count = row.get(6, Long.class);
      var group = groups.computeIfAbsent(
          key(code, rowCurrency), k -> new SkuGroup(code, rowCurrency, new LinkedHashMap<>(), new LinkedHashMap<>()));
      merge(group.amounts(), component, amount, quantity, count);
      if ("REVENUE".equals(component)) {
        merge(group.modeRevenue(), mode, amount, quantity, count);
      }
    }

    // ---- ② 本模块自己的退款损失事实 ----
    var refundParams = new ArrayList<Object>();
    refundParams.add(tenantId);
    refundParams.add(from);
    refundParams.add(to);
    var refundFilters = new StringBuilder();
    if (skuCode != null && !skuCode.isBlank()) {
      refundFilters.append(" and sku_code = ?");
      refundParams.add(skuCode);
    }
    if (currency != null && !currency.isBlank()) {
      refundFilters.append(" and currency = ?");
      refundParams.add(currency);
    }
    var refundRows =
        sql.fetch(
            "select sku_code, currency, sum(amount), sum(quantity), count(*)"
                + " from reporting_sku_profit_refund_fact"
                + " where tenant_id = ? and period_start >= ? and period_start <= ? and sku_code <> ''"
                + refundFilters
                + " group by sku_code, currency",
            refundParams.toArray());
    for (var row : refundRows) {
      var code = row.get(0, String.class);
      var rowCurrency = row.get(1, String.class);
      var group =
          groups.computeIfAbsent(
              key(code, rowCurrency),
              k -> new SkuGroup(code, rowCurrency, new LinkedHashMap<>(), new LinkedHashMap<>()));
      merge(group.amounts(), "REFUND_LOSS", row.get(2, BigDecimal.class), row.get(3, BigDecimal.class), row.get(4, Long.class));
    }

    // ---- ③ SKU 维度上有哪些成本项（**租户级**判定：不受 skuCode 筛选影响）----
    //   ⚠️ 「这个成本项在 SKU 维度上适不适用」是**租户级**的事实：
    //   别的 SKU 上有广告费 ⇒ 广告费在这个租户的 SKU 维度上**适用**，
    //   于是当前 SKU 缺它就是「缺这一项」（算不出来），而不是「不适用」。
    //   若这里跟着 skuCode 一起筛，判定就会退化成「不可归属到 SKU」，两种语义会混。
    var skuLevelRows =
        sql.fetch(
            "select component, count(*) from reporting_profit_fact"
                + " where tenant_id = ? and period_start >= ? and period_start <= ? and sku_code <> ''"
                + " group by component",
            tenantId,
            from,
            to);
    var skuLevelComponents = new LinkedHashSet<String>();
    for (var row : skuLevelRows) {
      skuLevelComponents.add(row.get(0, String.class));
    }

    // ---- ④ 租户在窗口内有没有这类成本（**任意维度**）----
    var tenantRows =
        sql.fetch(
            "select component, count(*) from reporting_profit_fact"
                + " where tenant_id = ? and period_start >= ? and period_start <= ?"
                + " group by component",
            tenantId,
            from,
            to);
    var tenantComponents = new LinkedHashSet<String>();
    for (var row : tenantRows) {
      tenantComponents.add(row.get(0, String.class));
    }

    // ---- ⑤ 租户在窗口内有没有退款事实（**任意 SKU / 币种**）----
    //   退款是**逐笔枚举**的事实（上游逐条列出已成功退款），所以「这个 SKU 不在其中」
    //   **真的等于 0** —— 与成本池分摊的「缺项」是两种不同的语义。
    var refundCount =
        sql.fetchOne(
            "select count(*) from reporting_sku_profit_refund_fact"
                + " where tenant_id = ? and period_start >= ? and period_start <= ?",
            tenantId,
            from,
            to);
    var tenantRefundCount = refundCount == null ? 0L : refundCount.get(0, Long.class);

    return new Facts(groups, skuLevelComponents, tenantComponents, tenantRefundCount);
  }

  /** 把一条聚合并进分组（**同一维度、同一成本项**可能有多行）。 */
  private static void merge(
      Map<String, Amount> amounts, String component, BigDecimal amount, BigDecimal quantity, Long count) {
    var existing = amounts.get(component);
    if (existing == null) {
      amounts.put(
          component,
          new Amount(
              amount == null ? BigDecimal.ZERO : amount,
              quantity == null ? BigDecimal.ZERO : quantity,
              count == null ? 0L : count));
      return;
    }
    amounts.put(
        component,
        new Amount(
            existing.amount().add(amount == null ? BigDecimal.ZERO : amount),
            existing.quantity().add(quantity == null ? BigDecimal.ZERO : quantity),
            existing.count() + (count == null ? 0L : count)));
  }

  /**
   * 组装一行。
   *
   * <p>⚠️ **每个成本项的取值状态有四种**（{@code AVAILABLE} / {@code MISSING} /
   * {@code NOT_ATTRIBUTABLE} / {@code NOT_APPLICABLE}）—— 后两种的差别是：
   * 一个是「这笔成本在 SKU 维度上拿不到」，一个是「本租户压根没有这类成本」。
   */
  private ReportingSkuProfitModels.SkuProfitRow buildRow(SkuGroup group, Facts facts) {
    var components = new ArrayList<ReportingSkuProfitModels.SkuProfitComponentAmount>();
    var missing = new ArrayList<String>();
    var totalCost = BigDecimal.ZERO;
    var hasCost = false;
    BigDecimal revenue = null;

    for (var component : ReportingSkuProfitModels.Component.values()) {
      var amount = group.amounts().get(component.name());
      if (amount == null) {
        // 🔴 **退款损失是「逐笔枚举」的事实**：上游把本窗口的已成功退款逐条列出来了，
        //   这个 SKU 不在其中 ⇒ **真的没有退款** ⇒ 0 是真相（不是「算不出来」）。
        //   与成本池分摊的「缺项」是两种语义，不能混。
        if (component == ReportingSkuProfitModels.Component.REFUND_LOSS
            && facts.tenantRefundCount() > 0) {
          components.add(
              new ReportingSkuProfitModels.SkuProfitComponentAmount(
                  component.name(),
                  component.label(),
                  BigDecimal.ZERO,
                  BigDecimal.ZERO,
                  ReportingSkuProfitModels.Availability.AVAILABLE.name(),
                  true,
                  "上游逐笔枚举了本窗口的已成功退款，这个 SKU 不在其中 ⇒ 退款损失为 0。"));
          hasCost = true;
          continue;
        }
        var status = statusOf(component, facts);
        if (status == ReportingSkuProfitModels.Availability.MISSING
            || status == ReportingSkuProfitModels.Availability.NOT_ATTRIBUTABLE) {
          missing.add(component.label());
        }
        components.add(
            new ReportingSkuProfitModels.SkuProfitComponentAmount(
                component.name(),
                component.label(),
                null,
                null,
                status.name(),
                false,
                reasonOf(component, status)));
        continue;
      }
      components.add(
          new ReportingSkuProfitModels.SkuProfitComponentAmount(
              component.name(),
              component.label(),
              amount.amount(),
              amount.quantity(),
              ReportingSkuProfitModels.Availability.AVAILABLE.name(),
              true,
              ""));
      if (component.isCost()) {
        hasCost = true;
        totalCost = totalCost.add(amount.amount());
      } else {
        revenue = amount.amount();
      }
    }

    // ⚠️ **净利要求有收入**：没有收入就没有净利（上面的分支已经把「单品收入」记进 missing）。
    var comparable = missing.isEmpty() && hasCost && revenue != null;
    BigDecimal totalCostValue = null;
    BigDecimal netProfit = null;
    BigDecimal netMargin = null;
    if (comparable) {
      totalCostValue = totalCost;
      netProfit = revenue.subtract(totalCost);
      netMargin =
          revenue.signum() == 0
              ? null
              : netProfit.multiply(new BigDecimal("100")).divide(revenue, DIVIDE_SCALE, RoundingMode.HALF_UP)
                  .setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }

    var modeBreakdown = new ArrayList<ReportingSkuProfitModels.SkuProfitModeAmount>();
    for (var entry : group.modeRevenue().entrySet()) {
      modeBreakdown.add(
          new ReportingSkuProfitModels.SkuProfitModeAmount(
              entry.getKey(),
              modeLabel(entry.getKey()),
              entry.getValue().amount(),
              "UNKNOWN".equals(entry.getKey())
                  ? "这笔收入挂不上店铺 ⇒ 归「未归属」，**不混进任何真实模式**。"
                  : "模式取自 2.3.2.1 的 `shop.operating_mode`。"));
    }

    return new ReportingSkuProfitModels.SkuProfitRow(
        group.skuCode(),
        group.currency(),
        components,
        totalCostValue,
        netProfit,
        netMargin,
        comparable,
        missing,
        modeBreakdown,
        group.amounts().values().stream().mapToLong(Amount::count).sum(),
        rowNote(group, comparable, missing, modeBreakdown.size()));
  }

  /** 一个成本项在这个 SKU 上的取值状态。 */
  private static ReportingSkuProfitModels.Availability statusOf(
      ReportingSkuProfitModels.Component component, Facts facts) {
    if (component == ReportingSkuProfitModels.Component.REVENUE) {
      // ⚠️ 收入**天生**可归属到 SKU（订单行口径）—— 没有就是「缺」，不是「不可归属」。
      return ReportingSkuProfitModels.Availability.MISSING;
    }
    if (facts.skuLevelComponents().contains(component.name())) {
      // 上游在 SKU 维度上有这类成本（别的 SKU 有）⇒ 这个 SKU 缺它。
      return ReportingSkuProfitModels.Availability.MISSING;
    }
    if (facts.tenantComponents().contains(component.name())) {
      // 上游有这笔成本，但只分摊到店铺 / 订单 ⇒ SKU 维度**不可归属**。
      return ReportingSkuProfitModels.Availability.NOT_ATTRIBUTABLE;
    }
    return ReportingSkuProfitModels.Availability.NOT_APPLICABLE;
  }

  /** 状态对应的原因说明（**写清「为什么没有值」**）。 */
  private static String reasonOf(
      ReportingSkuProfitModels.Component component,
      ReportingSkuProfitModels.Availability status) {
    return switch (status) {
      case AVAILABLE -> "";
      case MISSING -> component.isCost()
          ? "这个 SKU 在本窗口内没有这类成本事实（上游在 SKU 维度上有这类成本，只是这个 SKU 没有）—— "
              + "**算不出来，不是 0**。"
          : "这个 SKU 在本窗口内没有收入事实 —— **算不出来，不是 0**。";
      case NOT_ATTRIBUTABLE -> "上游有这笔成本，但只分摊到**店铺 / 订单**，SKU 维度拿不到 —— "
          + "**不可归属，不是 0**。本模块**不重算分摊**：上游按 **SKU 维度**分摊后这一格才会有值。";
      case NOT_APPLICABLE -> "本租户在本窗口内**没有这类成本事实**（不适用，不参与净利计算；"
          + "若上游其实有、只是还没投影，净利会偏高）。";
    };
  }

  /** 一行的口径说明（**写清这一行是怎么算出来的**）。 */
  private static String rowNote(
      SkuGroup group, boolean comparable, List<String> missing, int modeCount) {
    var note = new StringBuilder();
    note.append("单品收入取**订单行金额**口径（13.4.1.1 投影的 SKU 行收入，");
    note.append("不含订单级运费 / 税 / 折扣）。");
    note.append(" 成本取上游**已分摊到 SKU** 的行（采购成本 / 头程 / 平台费 / 物流费 / 广告费），");
    note.append("退款损失取 9/11 售后的**已成功退款**。");
    if (modeCount > 1) {
      note.append(" ⚠️ 这个 SKU 在 ").append(modeCount).append(" 个经营模式下有销售，");
      note.append("而 SKU 级成本不落到经营模式 ⇒ 这一行是**跨模式口径**（逐模式收入构成见下）。");
    }
    if (!comparable) {
      note.append(" 净利**算不出来**：");
      note.append(
          missing.isEmpty()
              ? "本窗口内**完全没有成本事实**（成本侧未知，不是成本为 0）。"
              : "缺 " + String.join("、", missing) + "（成本项不全，净利不可比）。");
    }
    return note.toString();
  }

  /** 口径说明（**收入怎么算、成本取自哪一路上游、哪些算不出来**）。 */
  private static String caliberNote() {
    return "单品收入：13.4.1.1 投影的**订单行金额**口径（SKU 维度只有这一个收入口径；"
        + "订单总额口径挂在订单上，**不与它相加**）。"
        + " 成本：采购成本 / 头程 / 平台费 / 物流费 / 广告费取 13.4.1.1 的利润事实里"
        + "**上游已分摊到 SKU** 的那些行（12.4 归集 + 12.4.2.2 分摊结果 + 12.3.1.2 结算明细）。"
        + " 退款损失：9/11 售后的**已成功退款**（只有钱退出去才算损失），SKU 归属来自售后单的涉及行。"
        + " 分摊与费率都在上游，本模块**不重算**。"
        + " 汇率：本模块**不拉实时汇率**，也不做跨币种合计。"
        + " SKU 级成本**不落到经营模式** ⇒ 一个 SKU 若多模式混卖，净利是跨模式口径（收入构成逐模式给出）。";
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  private static String modeLabel(String mode) {
    var parsed = ReportingProfitReportModels.OperatingMode.parse(mode);
    return parsed == null ? mode : parsed.label();
  }

  private static String componentLabel(String component) {
    var parsed = ReportingSkuProfitModels.Component.parse(component);
    return parsed == null ? component : parsed.label();
  }

  private static String sourceNote(String sourceService) {
    return switch (sourceService == null ? "" : sourceService) {
      case "trade" -> "来源：9 订单 / 11 售后（trade）";
      case "finance" -> "来源：12 财务（finance）";
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
