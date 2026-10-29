package com.starcon.reporting;

import com.starcon.tenancy.Ids;
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
 * 13.5.3.1 物流分析的**读模型**（蓝图「输出」句：物流报表 + 渠道对比）。
 *
 * <p>⚠️ <b>六件指标，一个口径</b>：
 *
 * <ol>
 *   <li><b>单均运费</b> —— 逐币种：运费总额 ÷ **订单数**；订单分母取
 *       <b>同一批运单上的 distinct 销售订单号</b>（与分子同范围）；</li>
 *   <li><b>头程分摊后成本</b> —— 逐币种，读 channel 10.6.1.1 的分摊单
 *       （<b>不重算分摊</b>）；</li>
 *   <li><b>渠道对比</b> —— 逐（承运商 × 渠道 × 币种），读 10.6.1.1 的 by-channel；</li>
 *   <li><b>时效达成率</b> —— 逐（渠道 × 国家），读 10.6.2.1 的矩阵
 *       （<b>判定不重算</b>）；</li>
 *   <li><b>各国成本对比</b> —— 逐（币种 × 国家），读 trade 的运单事实
 *       （运费金额 + 目的国同颗粒）；</li>
 *   <li><b>异常件占比</b> —— 窗口内异常件 ÷ <b>窗口内运单</b>（分子分母同范围）。</li>
 * </ol>
 *
 * <p>🔴 <b>三条硬纪律贯穿本类</b>：
 *
 * <ol>
 *   <li><b>分母为 0 ⇒ null（不可比），不是 0</b>。「单均运费 0 元」与「这一格算不出来」
 *       在报表里长得一模一样，含义却完全相反。</li>
 *   <li><b>多币种绝不相加</b>。每一行带自己的币种；跨币种的合计不存在。</li>
 *   <li><b>先汇总再相除</b>。占比与比率的分母都是**先加总**再相除，
 *       不是对逐行的比率求平均。</li>
 * </ol>
 */
@Service
public class ReportingLogisticsAnalysisService {

  /** 金额刻度（对齐 {@code numeric(24,4)}）。 */
  static final int MONEY_SCALE = 4;

  /** 数量刻度（对齐 {@code numeric(19,6)}）。 */
  static final int QUANTITY_SCALE = 6;

  /** 比率刻度（百分数，保留 4 位）。 */
  static final int RATE_SCALE = 4;

  /** 默认时效达成率目标（百分数）。 */
  static final BigDecimal DEFAULT_ON_TIME_TARGET = new BigDecimal("90");

  /** 默认异常件占比上限（百分数）。 */
  static final BigDecimal DEFAULT_EXCEPTION_SHARE_TARGET = new BigDecimal("5");

  /** 默认物流成本占销售额比上限（百分数）。 */
  static final BigDecimal DEFAULT_COST_TO_SALES_TARGET = new BigDecimal("30");

  /** 默认单均运费目标（金额 + 币种）。 */
  static final BigDecimal DEFAULT_FREIGHT_PER_ORDER_TARGET = new BigDecimal("5");

  /** 默认单均运费目标币种。 */
  static final String DEFAULT_FREIGHT_PER_ORDER_CURRENCY = "USD";

  /** 分类分组最多列多少条（超出部分在 note 里说明，**不静默截断**）。 */
  static final int GROUP_ROW_LIMIT = 50;

  /** 下钻清单最多列多少条（分页由调用方决定，这里只兜底）。 */
  static final int PAGE_LIMIT = 200;

  private static final String FREIGHT_CALIBER =
      "单均运费 = **运费总额 ÷ 订单数**。订单分母取**同一批运单上的 distinct 销售订单号** —— "
          + "这样分子与分母**同范围**（同一窗口、同一批运单）；"
          + "**只有未登记运费的订单不进分母**（运费没登记 ⇒ 分子里没有它，分母里也不该有它）。"
          + "没产生运单的订单既没有分子也没有分母。"
          + "⚠️ **channel 自己的 `perOrderFreight` 不是单均运费**：它的分母是"
          + "**已登记运费的运单票数**（票），一单多票时票数 > 订单数 ⇒ 那个数**偏小**。"
          + "本模块把上游那个数原样放在「每票运费」一列**并排对照**，不混为一谈。";

  private static final String HEAD_MILE_CALIBER =
      "头程分摊后成本 = **channel 10.6.1.1 的分摊结果**（分摊单 + 逐 SKU 的分摊明细行）。"
          + "本模块**不重算一套分摊** —— 两个分摊口径会让「物流成本占比」有两个答案。"
          + "分摊单按**结账时点（cost_as_of）**归入时间窗：成本算到哪一刻的头上要查得到。";

  private static final String TIMELINESS_CALIBER =
      "时效达成率 = **准点数 ÷ 可比数**（可比数 = 准点 + 超时）；"
          + "没有承诺送达日（NO_PROMISE）与未签收（IN_TRANSIT）的票**不进分母**。"
          + "**判定归 channel 10.6.2.1**，本模块只搬运、不重算。"
          + "分母为 0 ⇒ 达成率**不可比**（不是 0%）。";

  private static final String COUNTRY_CALIBER =
      "各国成本对比 = **运单级尾程运费**（trade 10.2.1.1 的运单清单）。"
          + "运单是运费金额与目的国**共同的最小颗粒** —— 渠道维度的成本报表装不下「目的国」这一维。"
          + "**逐币种**：某国的金额 ÷ **同币种**的合计；跨币种绝不相加。"
          + "**运费没登记的票有票数、没有金额**，不进任何金额合计。";

  private static final String EXCEPTION_CALIBER =
      "异常件占比 = **窗口内异常件数 ÷ 窗口内运单数**。"
          + "分子取 channel 10.3.1.2 的**已判定异常件**（按发现日归窗），"
          + "分母取**同一窗口**的运单数 ⇒ 两者同范围。"
          + "⚠️ channel 的 `/exceptions/overview` 是**累计全量**（没有时间窗），"
          + "拿它除以窗口内运单数会得到一个**没有意义**的比率 —— "
          + "本模块把它单列为「累计口径」对照，不参与占比计算。";

  private static final String CATEGORY_CALIBER =
      "类目维度的物流成本占比 = **头程分摊后成本 ÷ 同币种销售额**。"
          + "分母的销售额取 **13.4.1.2 SKU 利润的 `reporting_profit_fact`（component='REVENUE'）**"
          + "—— 与利润侧同口径，本模块**不重算销售额**。"
          + "⚠️ **类目维度只覆盖头程分摊部分**：尾程运费落在**运单**上，一票可能多 SKU，"
          + "**没有 SKU 维度** ⇒ 那一段进不了类目。**统一毛利率会吃掉利润** —— "
          + "物流成本占比高的类目必须单独定价（下游 5 定价的输入）。";

  private static final String CURRENCY_NOTE =
      "**多币种绝不相加**：每一行带自己的币种，跨币种的「总计」不存在（单币种时才给）。"
          + "本服务**不自行折算币种**（本仓没有汇率台账与汇率同步器）。";

  private final DSLContext sql;
  private final TenantTransactions tenants;

  public ReportingLogisticsAnalysisService(DSLContext sql, TenantTransactions tenants) {
    this.sql = sql;
    this.tenants = tenants;
  }

  // ==========================================================================
  // 能力声明
  // ==========================================================================

  /**
   * 能力声明：六件指标逐条报可得性。
   *
   * <p>🔴 **接不通的必须给出归属模块号与原因** —— 「算不出来」与「数值是 0」
   * 在页面上长得一样，含义却相反。
   */
  public ReportingLogisticsAnalysisModels.LogisticsAnalysisCapabilities capabilities(
      UUID tenantId) {
    return tenants.run(
        tenantId,
        () -> {
          var waybills = countOf(sql.fetchOne("select count(*) from reporting_logistics_analysis_waybill_fact where tenant_id = ?", tenantId));
          var costs = countOf(sql.fetchOne("select count(*) from reporting_logistics_analysis_cost_fact where tenant_id = ?", tenantId));
          var channels = countOf(sql.fetchOne("select count(*) from reporting_logistics_analysis_channel_fact where tenant_id = ?", tenantId));
          var allocations = countOf(sql.fetchOne("select count(*) from reporting_logistics_analysis_allocation_fact where tenant_id = ?", tenantId));
          var lines = countOf(sql.fetchOne("select count(*) from reporting_logistics_analysis_allocation_line_fact where tenant_id = ?", tenantId));
          var timeliness = countOf(sql.fetchOne("select count(*) from reporting_logistics_analysis_timeliness_fact where tenant_id = ?", tenantId));
          var exceptions = countOf(sql.fetchOne("select count(*) from reporting_logistics_analysis_exception_fact where tenant_id = ?", tenantId));
          var skus = countOf(sql.fetchOne("select count(*) from reporting_logistics_analysis_sku_category_fact where tenant_id = ?", tenantId));
          var revenue = countOf(sql.fetchOne("select count(*) from reporting_profit_fact where tenant_id = ? and component = 'REVENUE'", tenantId));

          var sections = new ArrayList<ReportingLogisticsAnalysisModels.LogisticsAnalysisCapabilitySection>();

          sections.add(section(
              "FREIGHT_PER_ORDER",
              "单均运费（运费总额 ÷ 订单数）",
              waybills > 0,
              null,
              null,
              null,
              FREIGHT_CALIBER,
              "trade 10.2.1.1 `GET /trade/logistics/waybills/list`"
                  + " → `reporting_logistics_analysis_waybill_fact`"));
          sections.add(section(
              "HEAD_MILE_ALLOCATION",
              "头程分摊后成本",
              allocations > 0,
              null,
              null,
              null,
              HEAD_MILE_CALIBER,
              "channel 10.6.1.1 `GET /channel/logistics-cost-analysis/allocations[/{id}]`"
                  + " → `reporting_logistics_analysis_allocation_fact`"));
          sections.add(section(
              "CHANNEL_COMPARISON",
              "渠道对比（承运商 × 渠道 × 币种）",
              channels > 0,
              null,
              null,
              null,
              "渠道维度取 channel 10.6.1.1 的 by-channel（**逐币种**，跨币种合计不存在）。",
              "channel 10.6.1.1 `GET /channel/logistics-cost-analysis/by-channel`"
                  + " → `reporting_logistics_analysis_channel_fact`"));
          sections.add(section(
              "TIMELINESS_ON_TIME_RATE",
              "时效达成率（渠道 × 国家）",
              timeliness > 0,
              null,
              null,
              null,
              TIMELINESS_CALIBER,
              "channel 10.6.2.1 `GET /channel/logistics-timeliness/reports/matrix`"
                  + " → `reporting_logistics_analysis_timeliness_fact`"));
          sections.add(section(
              "COUNTRY_COST",
              "各国成本对比（逐币种）",
              waybills > 0,
              null,
              null,
              null,
              COUNTRY_CALIBER,
              "trade 10.2.1.1 运单清单 → `reporting_logistics_analysis_waybill_fact`"));
          sections.add(section(
              "EXCEPTION_SHARE",
              "异常件占比（窗口内异常件 ÷ 窗口内运单）",
              waybills > 0 && exceptions > 0,
              exceptions > 0 || waybills > 0 ? null : "10.3.1.2 异常件（channel 服务）",
              exceptions > 0 || waybills > 0 ? null
                  : "**分子**需要 channel 10.3.1.2 的异常件、**分母**需要 trade 的运单，"
                      + "两者都没有投影 ⇒ 异常件占比**不可比**（**不是 0%**）。",
              exceptions > 0 || waybills > 0
                  ? null
                  : "没有异常件就**判不出占比**，运营会以为「异常率 0%」而放松盯单。",
              EXCEPTION_CALIBER,
              "channel 10.3.1.2 `GET /channel/logistics-tracking/exceptions`"
                  + " → `reporting_logistics_analysis_exception_fact`"));
          sections.add(section(
              "CATEGORY_COST_SHARE",
              "类目维度的物流成本占销售额比（**下游 5 定价**）",
              lines > 0 && revenue > 0,
              lines > 0 && revenue > 0 ? null : "13.4.1.2 SKU 利润的销售额投影",
              lines > 0 && revenue > 0 ? null
                  : "**分子**需要 channel 的头程分摊明细行、**分母**需要 13.4.1.2 的销售额投影，"
                      + "缺一 ⇒ 类目占比**不可比**（**不是 0%**）。",
              lines > 0 && revenue > 0
                  ? null
                  : "5 定价拿不到类目维度的物流成本占比 ⇒ 物流成本高的类目会被**统一毛利率吃掉利润**。",
              CATEGORY_CALIBER,
              "channel 10.6.1.1 分摊明细 + catalog 6.1.1.1 类目 + `reporting_profit_fact`(REVENUE)"));

          return new ReportingLogisticsAnalysisModels.LogisticsAnalysisCapabilities(
              sections.stream().filter(s -> s.available()).map(s -> s.key()).toList(),
              sections,
              "上游 = **10 物流与头程**（运单 / 轨迹 / 时效 / 异常件的事实全在 channel 与 trade），"
                  + "成本数据来自 13.4.1.2 的销售额投影。本模块是**分析投影**，"
                  + "与业务系统冲突时以业务系统为准。",
              "六件指标的口径逐条写在 sections 里；**算不出来**的一律是 null + 原因，不是 0。",
              CURRENCY_NOTE,
              "**单均运费的分母是订单数**（不是运单票数）："
                  + "运单票数 ≥ 订单数（一单多票时），用票数会把单均运费**压低**。",
              "**头程分摊不重算**：分摊规则、基准与结账时点（cost_as_of）全归 channel 10.6.1.1。",
              "**类目维度只覆盖头程分摊部分**：尾程运费落在运单上，**没有 SKU 维度**。",
              "各表之间**没有跨服务 FK / 跨库视图**（AGENTS.md 模块边界纪律）："
                  + "全部经授权只读 API 拉取后落地，租户上下文由调用方的已认证成员关系解析。");
        });
  }

  private static ReportingLogisticsAnalysisModels.LogisticsAnalysisCapabilitySection section(
      String key,
      String label,
      boolean available,
      String ownerModule,
      String reason,
      String impact,
      String caliber,
      String evidence) {
    return new ReportingLogisticsAnalysisModels.LogisticsAnalysisCapabilitySection(
        key, label, available, ownerModule, reason, impact, caliber, evidence);
  }

  // ==========================================================================
  // ① 成本总览
  // ==========================================================================

  /** 物流成本总览（**逐币种**）。 */
  public ReportingLogisticsAnalysisModels.LogisticsCostOverviewResponse overview(
      UUID tenantId, LocalDate from, LocalDate to) {
    return tenants.run(
        tenantId,
        () -> {
          var asOf = latestCostAsOf(tenantId, to);
          var rows = new ArrayList<ReportingLogisticsAnalysisModels.LogisticsCostCurrencyRow>();
          var currencies = new ArrayList<String>();
          var missing = new ArrayList<String>();
          var scanned = 0L;
          var truncated = false;
          var updatedAt = (OffsetDateTime) null;

          if (asOf != null) {
            var records =
                sql.fetch(
                    "select currency,last_mile_freight,first_mile_allocation,duty_and_tax,packaging,"
                        + "subtotal,"
                        + "registered_waybills,not_registered_waybills,total_weight_kg,"
                        + "upstream_per_waybill_freight,upstream_per_kg_freight,"
                        + "included_components,missing_components,scanned_waybills,truncated,updated_at,"
                        + "window_from,window_to "
                        + "from reporting_logistics_analysis_cost_fact "
                        + "where tenant_id = ? and as_of = ? order by currency",
                    tenantId,
                    asOf);
            for (var record : records) {
              var currency = record.get("currency", String.class);
              currencies.add(currency);
              var included = split(record.get("included_components", String.class));
              var rowMissing = split(record.get("missing_components", String.class));
              for (var m : rowMissing) {
                if (!missing.contains(m)) missing.add(m);
              }
              scanned = Math.max(scanned, longOf(record.get("scanned_waybills", Long.class)));
              truncated = truncated || Boolean.TRUE.equals(record.get("truncated", Boolean.class));
              var updated = record.get("updated_at", OffsetDateTime.class);
              if (updated != null && (updatedAt == null || updated.isAfter(updatedAt))) {
                updatedAt = updated;
              }
              var subtotal = record.get("subtotal", BigDecimal.class);
              var lastMile = record.get("last_mile_freight", BigDecimal.class);
              var firstMile = record.get("first_mile_allocation", BigDecimal.class);
              var duty = record.get("duty_and_tax", BigDecimal.class);
              var packaging = record.get("packaging", BigDecimal.class);
              var note =
                  rowMissing.isEmpty()
                      ? "五个成本分量都取到了数。"
                      : "缺 " + String.join(" / ", rowMissing) + " —— 这些位是**「算不出来」不是 0**，"
                          + "合计因此只是「已计入分量之和」。";
              rows.add(
                  new ReportingLogisticsAnalysisModels.LogisticsCostCurrencyRow(
                      currency,
                      lastMile,
                      firstMile,
                      duty,
                      packaging,
                      subtotal,
                      shareOf(lastMile, subtotal),
                      shareOf(firstMile, subtotal),
                      shareOf(duty, subtotal),
                      shareOf(packaging, subtotal),
                      longOf(record.get("registered_waybills", Long.class)),
                      longOf(record.get("not_registered_waybills", Long.class)),
                      record.get("total_weight_kg", BigDecimal.class),
                      record.get("upstream_per_waybill_freight", BigDecimal.class),
                      record.get("upstream_per_kg_freight", BigDecimal.class),
                      included,
                      rowMissing,
                      note));
            }
          }

          var warnings = new ArrayList<String>();
          if (asOf == null) {
            warnings.add(
                "**这个窗口还没有成本快照** —— 先刷新投影。没投影时各金额是「—」，**不是 0**。");
          }
          if (!missing.isEmpty()) {
            warnings.add(
                "成本覆盖不全：缺 " + String.join(" / ", missing)
                    + " —— 这些分量在本仓**没有来源**，报 0 等于凭空给这段成本免了账。");
          }
          if (truncated) {
            warnings.add("上游扫描撞到了上限 —— 统计**不是全量**。");
          }

          return new ReportingLogisticsAnalysisModels.LogisticsCostOverviewResponse(
              from,
              to,
              asOf,
              rows,
              currencies,
              missing.isEmpty(),
              missing,
              scanned,
              truncated,
              updatedAt,
              asOf == null
                  ? "尚未投影任何成本快照。"
                  : "数据截至 " + asOf + " 的成本快照（快照日 = 刷新窗口的止日）。",
              "全口径成本 = **channel 10.6.1.1 的成本分析**（尾程 + 头程分摊 + 关税 + 包材）。"
                  + "**逐币种**：跨币种的合计不存在。"
                  + "⚠️ 上游的「每票运费」（perOrderFreight）分母是**票**不是**单**，"
                  + "单均运费看 `/freight-per-order`。",
              warnings);
        });
  }

  // ==========================================================================
  // ② 单均运费
  // ==========================================================================

  /** 单均运费（**逐币种**）。 */
  public ReportingLogisticsAnalysisModels.LogisticsFreightPerOrderResponse freightPerOrder(
      UUID tenantId, LocalDate from, LocalDate to) {
    return tenants.run(
        tenantId,
        () -> {
          var rows = new ArrayList<ReportingLogisticsAnalysisModels.LogisticsFreightPerOrderRow>();
          var currencies = new ArrayList<String>();
          var updatedAt = (OffsetDateTime) null;

          // ⚠️ **窗口级计数单独查一次**（不限币种）：
          //   「没登记运费而且**连币种都没有**」的票进不了任何币种桶 ⇒
          //   各币种的票数加起来会少于窗口总票数。这个差额必须**如实报出来**，
          //   不能悄悄少算。
          var windowCounts =
              sql.fetchOne(
                  "select count(*) as waybills,"
                      + "count(*) filter (where freight_gap_code = 'REGISTERED') as registered,"
                      + "count(*) filter (where freight_gap_code <> 'REGISTERED') as not_registered,"
                      + "count(distinct nullif(sales_order_no,'')) filter (where freight_amount is not null) as orders "
                      + "from reporting_logistics_analysis_waybill_fact "
                      + "where tenant_id = ? and occurred_on >= ? and occurred_on <= ?",
                  tenantId,
                  from,
                  to);
          var totalWaybills = longOf(windowCounts.get("waybills", Long.class));
          var totalRegistered = longOf(windowCounts.get("registered", Long.class));
          var totalNotRegistered = longOf(windowCounts.get("not_registered", Long.class));
          var totalOrders = longOf(windowCounts.get("orders", Long.class));
          var totalOrdersAll =
              countOf(
                  sql.fetchOne(
                      "select count(distinct nullif(sales_order_no,'')) "
                          + "from reporting_logistics_analysis_waybill_fact "
                          + "where tenant_id = ? and occurred_on >= ? and occurred_on <= ?",
                      tenantId,
                      from,
                      to));

          var records =
              sql.fetch(
                  "select freight_currency,"
                      + "coalesce(sum(freight_amount),0) as amount,"
                      + "count(*) as waybills,"
                      + "count(*) filter (where freight_gap_code = 'REGISTERED') as registered,"
                      + "count(*) filter (where freight_gap_code <> 'REGISTERED') as not_registered,"
                      + "count(distinct nullif(sales_order_no,'')) filter (where freight_amount is not null) as orders,"
                      + "count(distinct nullif(sales_order_no,'')) as orders_all,"
                      + "coalesce(sum(gross_weight_kg),0) as weight,"
                      + "max(updated_at) as updated_at "
                      + "from reporting_logistics_analysis_waybill_fact "
                      + "where tenant_id = ? and occurred_on >= ? and occurred_on <= ? "
                      + "group by freight_currency order by freight_currency",
                  tenantId,
                  from,
                  to);

          for (var record : records) {
            var currency = record.get("freight_currency", String.class);
            if (currency == null || currency.isBlank()) {
              // 有票但**币种未知** ⇒ 金额进不了任何币种桶（如实记一笔，不塞进某个币种）。
              continue;
            }
            currencies.add(currency);
            var amount = record.get("amount", BigDecimal.class);
            var waybills = longOf(record.get("waybills", Long.class));
            var registered = longOf(record.get("registered", Long.class));
            var notRegistered = longOf(record.get("not_registered", Long.class));
            var orders = longOf(record.get("orders", Long.class));
            var ordersAll = longOf(record.get("orders_all", Long.class));
            var weight = record.get("weight", BigDecimal.class);
            var updated = record.get("updated_at", OffsetDateTime.class);
            if (updated != null && (updatedAt == null || updated.isAfter(updatedAt))) {
              updatedAt = updated;
            }
            var perOrder = orders > 0 ? divide(amount, BigDecimal.valueOf(orders)) : null;
            var perWaybill = registered > 0 ? divide(amount, BigDecimal.valueOf(registered)) : null;
            var perKg =
                weight != null && weight.signum() > 0 ? divide(amount, weight) : null;
            var note =
                orders > 0
                    ? "分母 = 该币种下**有已登记运费的订单数**（distinct 销售订单号），"
                        + "与运费总额**同范围**（运费没登记的订单既无分子也无分母）。"
                    : "**没有订单** ⇒ 单均运费**不可比**（不是 0）。";
            if (ordersAll > orders) {
              note +=
                  " ⚠️ 该币种下还有 "
                      + (ordersAll - orders)
                      + " 个订单**只有未登记运费的票** —— 它们不进分母（进了会把单均运费压低）。";
            }
            if (notRegistered > 0) {
              note += " ⚠️ 有 " + notRegistered + " 票运费**未登记**（有票数、无金额），不进合计。";
            }
            rows.add(
                new ReportingLogisticsAnalysisModels.LogisticsFreightPerOrderRow(
                    currency,
                    amount,
                    orders,
                    ordersAll,
                    waybills,
                    registered,
                    notRegistered,
                    perOrder,
                    perWaybill,
                    perKg,
                    weight,
                    note));
          }

          var rule = rule(tenantId, "FREIGHT_PER_ORDER_TARGET");
          var target = rule == null ? DEFAULT_FREIGHT_PER_ORDER_TARGET : rule.freightPerOrderTarget();
          var targetCurrency =
              rule == null ? DEFAULT_FREIGHT_PER_ORDER_CURRENCY : rule.freightPerOrderCurrency();

          var warnings = new ArrayList<String>();
          if (rows.isEmpty()) {
            warnings.add(
                "**这个窗口没有任何运单** —— 单均运费**不可比**（不是 0 元）。"
                    + "先刷新投影，或检查上游运单是否已同步。");
          }

          return new ReportingLogisticsAnalysisModels.LogisticsFreightPerOrderResponse(
              from,
              to,
              rows,
              currencies,
              totalOrders,
              totalOrdersAll,
              totalWaybills,
              totalRegistered,
              totalNotRegistered,
              "WAYBILL_BACKED_ORDER（窗口内有**已登记运费**的 distinct 销售订单号；"
                  + "只有未登记运费的订单不进分母）",
              target,
              targetCurrency,
              null,
              updatedAt,
              updatedAt == null
                  ? "尚未投影任何运单事实。"
                  : "数据截至 " + updatedAt,
              FREIGHT_CALIBER,
              warnings);
        });
  }

  // ==========================================================================
  // ③ 头程分摊后成本
  // ==========================================================================

  /** 头程分摊后成本（**逐币种** + 分摊单清单）。 */
  public ReportingLogisticsAnalysisModels.LogisticsHeadMileResponse headMile(
      UUID tenantId, LocalDate from, LocalDate to) {
    return tenants.run(
        tenantId,
        () -> {
          var windowFrom = from.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
          var windowTo = to.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();

          var records =
              sql.fetch(
                  "select currency, coalesce(sum(allocated_total_amount),0) as allocated,"
                      + "coalesce(sum(source_total_amount),0) as source_amount,"
                      + "count(*) as allocations, coalesce(sum(line_count),0) as lines "
                      + "from reporting_logistics_analysis_allocation_fact "
                      + "where tenant_id = ? and cost_as_of >= ?::timestamptz and cost_as_of < ?::timestamptz "
                      + "group by currency order by currency",
                  tenantId,
                  windowFrom,
                  windowTo);

          var overviewAmounts = headMileOverviewAmounts(tenantId, to);
          var rows = new ArrayList<ReportingLogisticsAnalysisModels.LogisticsHeadMileCurrencyRow>();
          for (var record : records) {
            var currency = record.get("currency", String.class);
            var allocated = record.get("allocated", BigDecimal.class);
            var sourceAmount = record.get("source_amount", BigDecimal.class);
            var upstream = overviewAmounts.get(currency);
            var difference = upstream == null ? null : allocated.subtract(upstream);
            var note =
                "分摊单按**结账时点（cost_as_of）**归入窗口；本模块**不重算分摊**。";
            if (upstream == null) {
              note += " ⚠️ 上游成本总览里**没有这个币种**的头程分摊位 ⇒ 对照不上（不是 0）。";
            } else if (difference != null && difference.signum() != 0) {
              note +=
                  " ⚠️ 与上游成本总览的头程分摊位**不一致**（差 "
                      + difference.stripTrailingZeros().toPlainString()
                      + "）—— 以 channel 10.6.1.1 为准。";
            }
            rows.add(
                new ReportingLogisticsAnalysisModels.LogisticsHeadMileCurrencyRow(
                    currency,
                    allocated,
                    sourceAmount,
                    longOf(record.get("allocations", Long.class)),
                    longOf(record.get("lines", Long.class)),
                    upstream,
                    difference,
                    note));
          }

          var allocations =
              sql.fetch(
                  "select allocation_id,allocation_code,rule_code,rule_version,allocation_basis,"
                      + "allocation_basis_label,source_shipment_code,source_carrier_name,"
                      + "destination_country,currency,source_total_amount,allocated_total_amount,"
                      + "line_count,status,status_label,computed_at,cost_as_of,locked_at "
                      + "from reporting_logistics_analysis_allocation_fact "
                      + "where tenant_id = ? and cost_as_of >= ?::timestamptz and cost_as_of < ?::timestamptz "
                      + "order by cost_as_of desc, allocation_code limit ?",
                  tenantId,
                  windowFrom,
                  windowTo,
                  GROUP_ROW_LIMIT);

          var allocationRows =
              allocations.stream()
                  .map(
                      record ->
                          new ReportingLogisticsAnalysisModels.LogisticsAllocationRow(
                              record.get("allocation_id", UUID.class),
                              record.get("allocation_code", String.class),
                              record.get("rule_code", String.class),
                              intOf(record.get("rule_version", Integer.class)),
                              record.get("allocation_basis", String.class),
                              record.get("allocation_basis_label", String.class),
                              record.get("source_shipment_code", String.class),
                              record.get("source_carrier_name", String.class),
                              record.get("destination_country", String.class),
                              record.get("currency", String.class),
                              record.get("source_total_amount", BigDecimal.class),
                              record.get("allocated_total_amount", BigDecimal.class),
                              intOf(record.get("line_count", Integer.class)),
                              record.get("status", String.class),
                              record.get("status_label", String.class),
                              record.get("computed_at", OffsetDateTime.class),
                              record.get("cost_as_of", OffsetDateTime.class),
                              record.get("locked_at", OffsetDateTime.class),
                              "分摊单明细可回上游逐条核对。"))
                  .toList();

          var updatedAt =
              sql.fetchOne(
                      "select max(updated_at) from reporting_logistics_analysis_allocation_fact"
                          + " where tenant_id = ?",
                      tenantId)
                  .get(0, OffsetDateTime.class);

          var warnings = new ArrayList<String>();
          if (rows.isEmpty()) {
            warnings.add(
                "**这个窗口没有头程分摊单** —— 头程分摊后成本**不可比**（不是 0 元）。");
          }

          return new ReportingLogisticsAnalysisModels.LogisticsHeadMileResponse(
              from,
              to,
              rows,
              allocationRows,
              allocationRows.size(),
              countOf(
                  sql.fetchOne(
                      "select count(*) from reporting_logistics_analysis_allocation_fact"
                          + " where tenant_id = ? and cost_as_of >= ?::timestamptz and cost_as_of < ?::timestamptz"
                          + " and status = 'LOCKED'",
                      tenantId,
                      windowFrom,
                      windowTo)),
              countOf(
                  sql.fetchOne(
                      "select count(*) from reporting_logistics_analysis_allocation_fact"
                          + " where tenant_id = ? and cost_as_of >= ?::timestamptz and cost_as_of < ?::timestamptz"
                          + " and status = 'SUPERSEDED'",
                      tenantId,
                      windowFrom,
                      windowTo)),
              updatedAt,
              updatedAt == null ? "尚未投影任何头程分摊单。" : "数据截至 " + updatedAt,
              HEAD_MILE_CALIBER,
              warnings);
        });
  }

  /** 上游成本总览里的头程分摊位（逐币种），用于与分摊单合计对照。 */
  private Map<String, BigDecimal> headMileOverviewAmounts(UUID tenantId, LocalDate to) {
    var map = new LinkedHashMap<String, BigDecimal>();
    var asOf = latestCostAsOf(tenantId, to);
    if (asOf == null) return map;
    var records =
        sql.fetch(
            "select currency, first_mile_allocation "
                + "from reporting_logistics_analysis_cost_fact where tenant_id = ? and as_of = ?",
            tenantId,
            asOf);
    for (var record : records) {
      var amount = record.get("first_mile_allocation", BigDecimal.class);
      if (amount == null) continue;
      map.put(record.get("currency", String.class), amount);
    }
    return map;
  }

  // ==========================================================================
  // ④ 渠道对比
  // ==========================================================================

  /** 渠道对比（真分页 + 真计数）。 */
  public ReportingLogisticsAnalysisModels.LogisticsChannelComparisonResponse channels(
      UUID tenantId, LocalDate from, LocalDate to, String currency, int page, int size) {
    return tenants.run(
        tenantId,
        () -> {
          var asOf = latestChannelAsOf(tenantId, to);
          var where = new StringBuilder(" where tenant_id = ? and as_of = ?");
          var params = new ArrayList<Object>();
          params.add(tenantId);
          params.add(asOf);
          if (currency != null && !currency.isBlank()) {
            where.append(" and currency = ?");
            params.add(currency);
          }
          var total =
              countOf(sql.fetchOne("select count(*) from reporting_logistics_analysis_channel_fact" + where, params.toArray()));
          var limited = Math.min(Math.max(size, 1), PAGE_LIMIT);
          var offset = Math.max(page, 0) * limited;
          var records =
              sql.fetch(
                  "select carrier_code,carrier_channel,carrier_channel_label,currency,amount,waybills,"
                      + "total_weight_kg,upstream_per_waybill_freight,updated_at "
                      + "from reporting_logistics_analysis_channel_fact"
                      + where
                      + " order by amount desc, carrier_code, carrier_channel limit ? offset ?",
                  concat(params, limited, offset));

          var totals =
              sql.fetch(
                  "select coalesce(sum(amount),0) as amount, coalesce(sum(waybills),0) as waybills "
                      + "from reporting_logistics_analysis_channel_fact where tenant_id = ? and as_of = ?"
                      + (currency != null && !currency.isBlank() ? " and currency = ?" : ""),
                  currency != null && !currency.isBlank()
                      ? new Object[] {tenantId, asOf, currency}
                      : new Object[] {tenantId, asOf});

          var currencyTotals = new LinkedHashMap<String, BigDecimal>();
          var currencyRecords =
              sql.fetch(
                  "select currency, coalesce(sum(amount),0) as amount "
                      + "from reporting_logistics_analysis_channel_fact where tenant_id = ? and as_of = ? "
                      + "group by currency",
                  tenantId,
                  asOf);
          for (var record : currencyRecords) {
            currencyTotals.put(record.get("currency", String.class), record.get("amount", BigDecimal.class));
          }

          var carrierCount =
              countOf(
                  sql.fetchOne(
                      "select count(distinct carrier_code) from reporting_logistics_analysis_channel_fact"
                          + " where tenant_id = ? and as_of = ?",
                      tenantId,
                      asOf));
          var summary =
              sql.fetchOne(
                  "select coalesce(sum(waybills),0) as waybills from reporting_logistics_analysis_channel_fact"
                      + " where tenant_id = ? and as_of = ?"
                      + (currency != null && !currency.isBlank() ? " and currency = ?" : ""),
                  currency != null && !currency.isBlank()
                      ? new Object[] {tenantId, asOf, currency}
                      : new Object[] {tenantId, asOf});

          var rows = new ArrayList<ReportingLogisticsAnalysisModels.LogisticsChannelComparisonRow>();
          var updatedAt = (OffsetDateTime) null;
          for (var record : records) {
            var rowCurrency = record.get("currency", String.class);
            var amount = record.get("amount", BigDecimal.class);
            var currencyTotal = currencyTotals.get(rowCurrency);
            var updated = record.get("updated_at", OffsetDateTime.class);
            if (updated != null && (updatedAt == null || updated.isAfter(updatedAt))) {
              updatedAt = updated;
            }
            rows.add(
                new ReportingLogisticsAnalysisModels.LogisticsChannelComparisonRow(
                    record.get("carrier_code", String.class),
                    record.get("carrier_channel", String.class),
                    record.get("carrier_channel_label", String.class),
                    rowCurrency,
                    amount,
                    longOf(record.get("waybills", Long.class)),
                    record.get("total_weight_kg", BigDecimal.class),
                    record.get("upstream_per_waybill_freight", BigDecimal.class),
                    currencyTotal == null || currencyTotal.signum() == 0
                        ? null
                        : rate(amount.multiply(new BigDecimal("100")).divide(currencyTotal, RATE_SCALE, RoundingMode.HALF_UP)),
                    "占比的分母是**同币种**的窗口合计；跨币种没有占比。"));
          }

          var warnings = new ArrayList<String>();
          if (asOf == null) {
            warnings.add("**这个窗口还没有渠道成本快照** —— 先刷新投影。");
          }

          return new ReportingLogisticsAnalysisModels.LogisticsChannelComparisonResponse(
              from,
              to,
              asOf,
              rows,
              total,
              Math.max(page, 0),
              limited,
              carrierCount,
              longOf(summary.get("waybills", Long.class)),
              countOf(
                  sql.fetchOne(
                      "select count(*) from reporting_logistics_analysis_channel_fact"
                          + " where tenant_id = ? and as_of = ? and upstream_per_waybill_freight is not null",
                      tenantId,
                      asOf)),
              0,
              updatedAt,
              updatedAt == null ? "尚未投影任何渠道成本快照。" : "数据截至 " + updatedAt,
              "渠道对比 = **channel 10.6.1.1 的 by-channel**（承运商 × 渠道）。"
                  + "**逐币种**：每一行带自己的币种，跨币种的渠道合计不存在。",
              warnings);
        });
  }

  // ==========================================================================
  // ⑤ 时效达成率
  // ==========================================================================

  /** 时效达成率（**判定归 channel 10.6.2.1**，本模块只搬运）。 */
  public ReportingLogisticsAnalysisModels.LogisticsTimelinessResponse timeliness(
      UUID tenantId, LocalDate from, LocalDate to) {
    return tenants.run(
        tenantId,
        () -> {
          var asOf = latestTimelinessAsOf(tenantId, to);
          var cells = new ArrayList<ReportingLogisticsAnalysisModels.LogisticsTimelinessCell>();
          var updatedAt = (OffsetDateTime) null;
          long shipped = 0;
          long delivered = 0;
          long comparable = 0;
          long onTime = 0;
          long overdue = 0;
          long noPromise = 0;
          long pending = 0;
          long returned = 0;
          var transitSum = BigDecimal.ZERO;
          var transitSamples = 0L;

          if (asOf != null) {
            var records =
                sql.fetch(
                    "select channel_id,channel_code,channel_name,country_code,"
                        + "shipped_count,delivered_count,comparable_count,on_time_count,overdue_count,"
                        + "no_promise_count,pending_count,returned_count,"
                        + "on_time_rate,delivery_rate,overdue_rate,avg_transit_hours,avg_deviation_hours,"
                        + "score,score_grade,updated_at "
                        + "from reporting_logistics_analysis_timeliness_fact "
                        + "where tenant_id = ? and as_of = ? "
                        + "order by country_code, channel_code",
                    tenantId,
                    asOf);
            for (var record : records) {
              var rowComparable = longOf(record.get("comparable_count", Long.class));
              var rowOnTime = longOf(record.get("on_time_count", Long.class));
              var rowOverdue = longOf(record.get("overdue_count", Long.class));
              var rowShipped = longOf(record.get("shipped_count", Long.class));
              var rowDelivered = longOf(record.get("delivered_count", Long.class));
              var rowTransit = record.get("avg_transit_hours", BigDecimal.class);
              shipped += rowShipped;
              delivered += rowDelivered;
              comparable += rowComparable;
              onTime += rowOnTime;
              overdue += rowOverdue;
              noPromise += longOf(record.get("no_promise_count", Long.class));
              pending += longOf(record.get("pending_count", Long.class));
              returned += longOf(record.get("returned_count", Long.class));
              if (rowTransit != null) {
                transitSum = transitSum.add(rowTransit.multiply(BigDecimal.valueOf(rowComparable)));
                transitSamples += rowComparable;
              }
              var updated = record.get("updated_at", OffsetDateTime.class);
              if (updated != null && (updatedAt == null || updated.isAfter(updatedAt))) {
                updatedAt = updated;
              }
              cells.add(
                  new ReportingLogisticsAnalysisModels.LogisticsTimelinessCell(
                      record.get("channel_id", UUID.class),
                      record.get("channel_code", String.class),
                      record.get("channel_name", String.class),
                      record.get("country_code", String.class),
                      rowShipped,
                      rowDelivered,
                      rowComparable,
                      rowOnTime,
                      rowOverdue,
                      longOf(record.get("no_promise_count", Long.class)),
                      longOf(record.get("pending_count", Long.class)),
                      longOf(record.get("returned_count", Long.class)),
                      record.get("on_time_rate", BigDecimal.class),
                      record.get("delivery_rate", BigDecimal.class),
                      record.get("overdue_rate", BigDecimal.class),
                      rowTransit,
                      record.get("avg_deviation_hours", BigDecimal.class),
                      record.get("score", BigDecimal.class),
                      record.get("score_grade", String.class),
                      true,
                      "达成率 = 准点数 ÷ 可比数；分母为 0 ⇒ **不可比**（不是 0%）。"));
            }
          }

          // 🔴 **先汇总再相除**：按国家的达成率用**该国汇总后的**准点数 ÷ 可比数，
          //   不是各格比率的平均。
          var countryAgg = new LinkedHashMap<String, long[]>();
          for (var cell : cells) {
            var agg =
                countryAgg.computeIfAbsent(
                    cell.countryCode(),
                    k -> new long[] {0, 0, 0, 0}); // shipped, comparable, onTime, overdue
            agg[0] += cell.shippedCount();
            agg[1] += cell.comparableCount();
            agg[2] += cell.onTimeCount();
            agg[3] += cell.overdueCount();
          }
          var byCountry = new ArrayList<ReportingLogisticsAnalysisModels.LogisticsTimelinessCountryRow>();
          for (var entry : countryAgg.entrySet()) {
            var agg = entry.getValue();
            byCountry.add(
                new ReportingLogisticsAnalysisModels.LogisticsTimelinessCountryRow(
                    entry.getKey(),
                    agg[0],
                    agg[1],
                    agg[2],
                    agg[3],
                    agg[1] > 0
                        ? rate(
                            BigDecimal.valueOf(agg[2])
                                .multiply(new BigDecimal("100"))
                                .divide(BigDecimal.valueOf(agg[1]), RATE_SCALE, RoundingMode.HALF_UP))
                        : null,
                    agg[1] > 0
                        ? rate(
                            BigDecimal.valueOf(agg[3])
                                .multiply(new BigDecimal("100"))
                                .divide(BigDecimal.valueOf(agg[1]), RATE_SCALE, RoundingMode.HALF_UP))
                        : null,
                    null));
          }

          var rule = rule(tenantId, "ON_TIME_TARGET");
          var target = rule == null ? DEFAULT_ON_TIME_TARGET : rule.onTimeTargetPct();
          var overallRate =
              comparable > 0
                  ? rate(
                      BigDecimal.valueOf(onTime)
                          .multiply(new BigDecimal("100"))
                          .divide(BigDecimal.valueOf(comparable), RATE_SCALE, RoundingMode.HALF_UP))
                  : null;
          var deliveryRate =
              shipped > 0
                  ? rate(
                      BigDecimal.valueOf(delivered)
                          .multiply(new BigDecimal("100"))
                          .divide(BigDecimal.valueOf(shipped), RATE_SCALE, RoundingMode.HALF_UP))
                  : null;
          var overdueRate =
              comparable > 0
                  ? rate(
                      BigDecimal.valueOf(overdue)
                          .multiply(new BigDecimal("100"))
                          .divide(BigDecimal.valueOf(comparable), RATE_SCALE, RoundingMode.HALF_UP))
                  : null;

          var warnings = new ArrayList<String>();
          if (asOf == null) {
            warnings.add("**这个窗口还没有时效快照** —— 达成率**不可比**（不是 0%）。");
          } else if (comparable == 0) {
            warnings.add("**没有可比的签收票**（分母为 0）⇒ 达成率**不可比**（不是 0%）。");
          }

          return new ReportingLogisticsAnalysisModels.LogisticsTimelinessResponse(
              from,
              to,
              asOf,
              cells,
              overallRate,
              comparable,
              onTime,
              overdue,
              shipped,
              delivered,
              noPromise,
              pending,
              returned,
              deliveryRate,
              overdueRate,
              transitSamples > 0
                  ? transitSum.divide(BigDecimal.valueOf(transitSamples), RATE_SCALE, RoundingMode.HALF_UP)
                  : null,
              target,
              overallRate == null ? null : overallRate.compareTo(target) >= 0,
              byCountry,
              updatedAt,
              updatedAt == null ? "尚未投影任何时效事实。" : "数据截至 " + updatedAt,
              TIMELINESS_CALIBER,
              warnings);
        });
  }

  // ==========================================================================
  // ⑥ 各国成本对比
  // ==========================================================================

  /** 各国成本对比（**逐币种**）。 */
  public ReportingLogisticsAnalysisModels.LogisticsCountryCostResponse countryCost(
      UUID tenantId, LocalDate from, LocalDate to) {
    return tenants.run(
        tenantId,
        () -> {
          var records =
              sql.fetch(
                  "select freight_currency, destination_country,"
                      + "coalesce(sum(freight_amount),0) as amount,"
                      + "count(*) as waybills,"
                      + "count(distinct nullif(sales_order_no,'')) filter (where freight_amount is not null) as orders,"
                      + "count(distinct nullif(sales_order_no,'')) as orders_all,"
                      + "coalesce(sum(gross_weight_kg),0) as weight "
                      + "from reporting_logistics_analysis_waybill_fact "
                      + "where tenant_id = ? and occurred_on >= ? and occurred_on <= ? "
                      + "and freight_currency is not null "
                      + "group by freight_currency, destination_country "
                      + "order by freight_currency, amount desc",
                  tenantId,
                  from,
                  to);

          var currencyTotals = new LinkedHashMap<String, BigDecimal>();
          var currencyWays = new LinkedHashMap<String, Long>();
          for (var record : records) {
            var currency = record.get("freight_currency", String.class);
            currencyTotals.merge(currency, record.get("amount", BigDecimal.class), BigDecimal::add);
            currencyWays.merge(currency, longOf(record.get("waybills", Long.class)), Long::sum);
          }

          var rows = new ArrayList<ReportingLogisticsAnalysisModels.LogisticsCountryCostRow>();
          var currencies = new ArrayList<String>();
          var countries = new LinkedHashSet<String>();
          var missingCountry = 0L;
          var waybillTotal = 0L;
          var orderTotal = 0L;

          for (var record : records) {
            var currency = record.get("freight_currency", String.class);
            if (!currencies.contains(currency)) currencies.add(currency);
            var country = record.get("destination_country", String.class);
            country = country == null ? "" : country;
            if (country.isBlank()) {
              missingCountry++;
              country = "UNKNOWN";
            } else {
              countries.add(country);
            }
            var amount = record.get("amount", BigDecimal.class);
            var waybills = longOf(record.get("waybills", Long.class));
            var orders = longOf(record.get("orders", Long.class));
            var weight = record.get("weight", BigDecimal.class);
            waybillTotal += waybills;
            orderTotal += orders;
            var currencyTotal = currencyTotals.get(currency);
            var note =
                country.isEmpty() || "UNKNOWN".equals(country)
                    ? "这批运单**没有目的国** ⇒ 归在「未标注目的国」，不能算进任何国家的对比。"
                    : "金额 = 该国该币种的**已登记尾程运费**合计；运费未登记的票不进合计。";
            rows.add(
                new ReportingLogisticsAnalysisModels.LogisticsCountryCostRow(
                    currency,
                    country,
                    amount,
                    waybills,
                    orders,
                    waybills > 0 ? divide(amount, BigDecimal.valueOf(waybills)) : null,
                    orders > 0 ? divide(amount, BigDecimal.valueOf(orders)) : null,
                    currencyTotal == null || currencyTotal.signum() == 0
                        ? null
                        : rate(amount.multiply(new BigDecimal("100")).divide(currencyTotal, RATE_SCALE, RoundingMode.HALF_UP)),
                    weight,
                    weight != null && weight.signum() > 0 ? divide(amount, weight) : null,
                    note));
          }

          var updatedAt =
              sql.fetchOne(
                      "select max(updated_at) from reporting_logistics_analysis_waybill_fact"
                          + " where tenant_id = ?",
                      tenantId)
                  .get(0, OffsetDateTime.class);

          var warnings = new ArrayList<String>();
          if (rows.isEmpty()) {
            warnings.add("**这个窗口没有任何运单** —— 各国成本**不可比**（不是 0）。");
          }
          if (missingCountry > 0) {
            warnings.add(
                "有 " + missingCountry + " 条运单**没有目的国** —— 单列「未标注目的国」，"
                    + "不摊进任何国家（摊进去就是编造）。");
          }

          return new ReportingLogisticsAnalysisModels.LogisticsCountryCostResponse(
              from,
              to,
              rows,
              currencies,
              waybillTotal,
              orderTotal,
              countries.size(),
              missingCountry,
              updatedAt,
              updatedAt == null ? "尚未投影任何运单事实。" : "数据截至 " + updatedAt,
              COUNTRY_CALIBER,
              warnings);
        });
  }

  // ==========================================================================
  // ⑦ 异常件占比
  // ==========================================================================

  /** 异常件占比（**分子分母同范围**）。 */
  public ReportingLogisticsAnalysisModels.LogisticsExceptionShareResponse exceptionShare(
      UUID tenantId, LocalDate from, LocalDate to) {
    return tenants.run(
        tenantId,
        () -> {
          var records =
              sql.fetch(
                  "select exception_type, exception_type_label, severity, severity_label, status,"
                      + "status_label, count(*) as count from reporting_logistics_analysis_exception_fact "
                      + "where tenant_id = ? and detected_on >= ? and detected_on <= ? "
                      + "group by exception_type, exception_type_label, severity, severity_label, status, status_label",
                  tenantId,
                  from,
                  to);

          long exceptionCount = 0;
          var byType = new LinkedHashMap<String, ReportingLogisticsAnalysisModels.LogisticsExceptionGroupRow>();
          var bySeverity =
              new LinkedHashMap<String, ReportingLogisticsAnalysisModels.LogisticsExceptionGroupRow>();
          var byStatus = new LinkedHashMap<String, ReportingLogisticsAnalysisModels.LogisticsExceptionGroupRow>();
          var labels = new LinkedHashMap<String, String>();

          for (var record : records) {
            var count = longOf(record.get("count", Long.class));
            exceptionCount += count;
            var type = record.get("exception_type", String.class);
            var typeLabel = record.get("exception_type_label", String.class);
            var severity = record.get("severity", String.class);
            var severityLabel = record.get("severity_label", String.class);
            var status = record.get("status", String.class);
            var statusLabel = record.get("status_label", String.class);
            merge(byType, "TYPE", type, typeLabel, count);
            merge(bySeverity, "SEVERITY", severity, severityLabel, count);
            merge(byStatus, "STATUS", status, statusLabel, count);
            labels.put(type, typeLabel);
          }

          var waybillCount =
              countOf(
                  sql.fetchOne(
                      "select count(*) from reporting_logistics_analysis_waybill_fact "
                          + "where tenant_id = ? and occurred_on >= ? and occurred_on <= ?",
                      tenantId,
                      from,
                      to));

          var rule = rule(tenantId, "EXCEPTION_SHARE_TARGET");
          var target = rule == null ? DEFAULT_EXCEPTION_SHARE_TARGET : rule.exceptionShareTargetPct();
          var share =
              waybillCount > 0
                  ? rate(
                      BigDecimal.valueOf(exceptionCount)
                          .multiply(new BigDecimal("100"))
                          .divide(BigDecimal.valueOf(waybillCount), RATE_SCALE, RoundingMode.HALF_UP))
                  : null;

          var topCases =
              sql.fetch(
                      "select case_id,case_no,exception_type,exception_type_label,severity,severity_label,"
                          + "status,status_label,waybill_no,carrier_code,sales_order_no,detected_on,"
                          + "detected_at,overdue from reporting_logistics_analysis_exception_fact "
                          + "where tenant_id = ? and detected_on >= ? and detected_on <= ? "
                          + "order by detected_on desc, case_no limit ?",
                      tenantId,
                      from,
                      to,
                      GROUP_ROW_LIMIT)
                  .stream()
                  .map(
                      record ->
                          new ReportingLogisticsAnalysisModels.LogisticsExceptionRow(
                              record.get("case_id", UUID.class),
                              record.get("case_no", String.class),
                              record.get("exception_type", String.class),
                              record.get("exception_type_label", String.class),
                              record.get("severity", String.class),
                              record.get("severity_label", String.class),
                              record.get("status", String.class),
                              record.get("status_label", String.class),
                              record.get("waybill_no", String.class),
                              record.get("carrier_code", String.class),
                              record.get("sales_order_no", String.class),
                              record.get("detected_on", LocalDate.class),
                              record.get("detected_at", OffsetDateTime.class),
                              Boolean.TRUE.equals(record.get("overdue", Boolean.class)),
                              "异常件是上游**已判定**的件，本模块不重判。"))
                  .toList();

          var updatedAt =
              sql.fetchOne(
                      "select max(updated_at) from reporting_logistics_analysis_exception_fact"
                          + " where tenant_id = ?",
                      tenantId)
                  .get(0, OffsetDateTime.class);

          var warnings = new ArrayList<String>();
          if (waybillCount == 0) {
            warnings.add("**窗口内没有运单** ⇒ 异常件占比**不可比**（不是 0%）。");
          }
          if (exceptionCount == 0 && waybillCount > 0) {
            warnings.add("窗口内**没有异常件**（可能是真没有，也可能是上游没扫 —— 需要人工确认）。");
          }

          return new ReportingLogisticsAnalysisModels.LogisticsExceptionShareResponse(
              from,
              to,
              exceptionCount,
              waybillCount,
              share,
              target,
              share == null ? null : share.compareTo(target) <= 0,
              sorted(byType),
              sorted(bySeverity),
              sorted(byStatus),
              topCases,
              updatedAt,
              updatedAt == null ? "尚未投影任何异常件。" : "数据截至 " + updatedAt,
              EXCEPTION_CALIBER,
              warnings);
        });
  }

  /** 异常件分组累加。 */
  private static void merge(
      Map<String, ReportingLogisticsAnalysisModels.LogisticsExceptionGroupRow> target,
      String dimension,
      String key,
      String label,
      long count) {
    var existing = target.get(key);
    if (existing == null) {
      target.put(
          key,
          new ReportingLogisticsAnalysisModels.LogisticsExceptionGroupRow(
              dimension, key, label, count, null, ""));
    } else {
      target.put(
          key,
          new ReportingLogisticsAnalysisModels.LogisticsExceptionGroupRow(
              dimension, key, label, existing.count() + count, null, ""));
    }
  }

  /** 分组按计数倒序（并列时按 key 排序，顺序稳定）。 */
  private static List<ReportingLogisticsAnalysisModels.LogisticsExceptionGroupRow> sorted(
      Map<String, ReportingLogisticsAnalysisModels.LogisticsExceptionGroupRow> source) {
    var rows = new ArrayList<>(source.values());
    rows.sort(
        (a, b) -> {
          var byCount = Long.compare(b.count(), a.count());
          return byCount != 0 ? byCount : a.dimensionKey().compareTo(b.dimensionKey());
        });
    var total = rows.stream().mapToLong(r -> r.count()).sum();
    var result = new ArrayList<ReportingLogisticsAnalysisModels.LogisticsExceptionGroupRow>();
    for (var row : rows) {
      var share =
          total > 0
              ? rate(
                  BigDecimal.valueOf(row.count())
                      .multiply(new BigDecimal("100"))
                      .divide(BigDecimal.valueOf(total), RATE_SCALE, RoundingMode.HALF_UP))
              : null;
      result.add(
          new ReportingLogisticsAnalysisModels.LogisticsExceptionGroupRow(
              row.dimension(),
              row.dimensionKey(),
              row.dimensionLabel(),
              row.count(),
              share,
              "占比的分母是**窗口内的异常件总数**。"));
    }
    return result;
  }

  // ==========================================================================
  // ⑧ 类目维度的物流成本占比（下游 5 定价）
  // ==========================================================================

  /** 类目维度的物流成本占销售额比。 */
  public ReportingLogisticsAnalysisModels.LogisticsCategoryCostResponse categoryCost(
      UUID tenantId, LocalDate from, LocalDate to) {
    return tenants.run(
        tenantId,
        () -> {
          var windowFrom = from.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
          var windowTo = to.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();

          // 分子：头程分摊明细行（逐 SKU）→ 归到类目。
          var costRecords =
              sql.fetch(
                  "select a.currency, l.category_code, sum(l.allocated_amount) as cost,"
                      + "count(*) as lines, count(distinct nullif(l.sku_code,'')) as skus "
                      + "from reporting_logistics_analysis_allocation_line_fact l "
                      + "join reporting_logistics_analysis_allocation_fact a on a.tenant_id = l.tenant_id "
                      + "and a.allocation_id = l.allocation_id "
                      + "where l.tenant_id = ? and a.cost_as_of >= ?::timestamptz and a.cost_as_of < ?::timestamptz "
                      + "group by a.currency, l.category_code order by a.currency, cost desc",
                  tenantId,
                  windowFrom,
                  windowTo);

          // 分母：13.4.1.2 的销售额投影（REVENUE）→ 按 SKU → 类目归集。
          var skuCategory =
              sql.fetch(
                  "select sku_code, category_code from reporting_logistics_analysis_sku_category_fact "
                      + "where tenant_id = ?",
                  tenantId)
                  .stream()
                  .collect(
                      java.util.stream.Collectors.toMap(
                          r -> r.get("sku_code", String.class),
                          r -> r.get("category_code", String.class),
                          (a, b) -> a));

          var revenueRecords =
              sql.fetch(
                  "select currency, sku_code, sum(amount) as revenue "
                      + "from reporting_profit_fact "
                      + "where tenant_id = ? and component = 'REVENUE' "
                      + "and period_start >= ? and period_start <= ? "
                      + "group by currency, sku_code",
                  tenantId,
                  from,
                  to);

          var revenueByCategory = new LinkedHashMap<String, BigDecimal>();
          var revenueSkuCount = new LinkedHashMap<String, Set<String>>();
          long revenueMissingSku = 0;
          for (var record : revenueRecords) {
            var skuCode = record.get("sku_code", String.class);
            if (skuCode == null || skuCode.isBlank()) {
              revenueMissingSku++;
              continue;
            }
            var category = skuCategory.get(skuCode);
            if (category == null || category.isBlank()) {
              revenueMissingSku++;
              continue;
            }
            var key = record.get("currency", String.class) + " " + category;
            revenueByCategory.merge(key, record.get("revenue", BigDecimal.class), BigDecimal::add);
            revenueSkuCount.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(skuCode);
          }

          var rule = rule(tenantId, "COST_TO_SALES_TARGET");
          var target = rule == null ? DEFAULT_COST_TO_SALES_TARGET : rule.costToSalesTargetPct();

          var rows = new ArrayList<ReportingLogisticsAnalysisModels.LogisticsCategoryCostRow>();
          var currencies = new ArrayList<String>();
          var categorySet = new LinkedHashSet<String>();
          var skuSet = new LinkedHashSet<String>();
          long unattributedLines = 0;
          BigDecimal unattributedAmount = BigDecimal.ZERO;

          for (var record : costRecords) {
            var currency = record.get("currency", String.class);
            if (currency == null || currency.isBlank()) continue;
            if (!currencies.contains(currency)) currencies.add(currency);
            var category = record.get("category_code", String.class);
            category = category == null ? "" : category;
            var cost = record.get("cost", BigDecimal.class);
            var lines = longOf(record.get("lines", Long.class));
            var skus = longOf(record.get("skus", Long.class));
            if (category.isBlank()) {
              unattributedLines += lines;
              unattributedAmount = unattributedAmount.add(cost);
              category = "UNATTRIBUTED";
            } else {
              categorySet.add(category);
            }
            skuSet.add(category);
            var key = currency + " " + category;
            var revenue = revenueByCategory.get(key);
            var ratio =
                revenue == null || revenue.signum() == 0
                    ? null
                    : rate(
                        cost.multiply(new BigDecimal("100")).divide(revenue, RATE_SCALE, RoundingMode.HALF_UP));
            var note =
                revenue == null
                    ? "这个类目**没有销售额投影** ⇒ 占比**不可比**（不是 0%）。"
                    : "分子 = 该类目的**头程分摊后成本**；分母 = 13.4.1.2 的**同币种销售额**。";
            if ("UNATTRIBUTED".equals(category)) {
              note = "分摊明细行**挂不上类目**（SKU 没进 catalog 档案）⇒ 单列「未归属」，不摊进任何类目。";
            }
            rows.add(
                new ReportingLogisticsAnalysisModels.LogisticsCategoryCostRow(
                    currency,
                    category,
                    cost,
                    revenue,
                    ratio,
                    skus,
                    lines,
                    skus,
                    (long) revenueSkuCount.getOrDefault(key, Set.of()).size(),
                    ratio == null || target == null ? null : ratio.compareTo(target) > 0,
                    note));
          }

          var updatedAt =
              sql.fetchOne(
                      "select max(updated_at) from reporting_logistics_analysis_allocation_line_fact"
                          + " where tenant_id = ?",
                      tenantId)
                  .get(0, OffsetDateTime.class);

          var warnings = new ArrayList<String>();
          if (rows.isEmpty()) {
            warnings.add(
                "**这个窗口没有类目维度的头程分摊** —— 物流成本占比**不可比**（不是 0%）。");
          }
          if (revenueMissingSku > 0) {
            warnings.add(
                "有 " + revenueMissingSku + " 条销售额事实**挂不上类目**（SKU 没进 catalog 档案）"
                    + " ⇒ 它们**不进任何类目的分母**，也不会让占比被悄悄抬高。");
          }
          warnings.add(
              "⚠️ **类目维度只覆盖头程分摊部分**：尾程运费落在**运单**上，一票可能多 SKU，"
                  + "**没有 SKU 维度** ⇒ 那一段进不了类目。下游 5 定价用这一维时要知道这件事。");

          return new ReportingLogisticsAnalysisModels.LogisticsCategoryCostResponse(
              from,
              to,
              rows,
              currencies,
              categorySet.size(),
              skuSet.size(),
              unattributedLines,
              unattributedAmount.signum() == 0 ? null : unattributedAmount,
              revenueMissingSku,
              target,
              updatedAt,
              updatedAt == null ? "尚未投影任何分摊明细行。" : "数据截至 " + updatedAt,
              CATEGORY_CALIBER,
              warnings);
        });
  }

  // ==========================================================================
  // ⑨ 下钻
  // ==========================================================================

  /** 运单级下钻（真分页 + 真计数）。 */
  public ReportingLogisticsAnalysisModels.LogisticsDrilldownResponse drilldown(
      UUID tenantId,
      LocalDate from,
      LocalDate to,
      String currency,
      String country,
      String carrierChannel,
      int page,
      int size) {
    return tenants.run(
        tenantId,
        () -> {
          var where = new StringBuilder(
              " where tenant_id = ? and occurred_on >= ? and occurred_on <= ?");
          var params = new ArrayList<Object>();
          params.add(tenantId);
          params.add(from);
          params.add(to);
          if (currency != null && !currency.isBlank()) {
            where.append(" and freight_currency = ?");
            params.add(currency);
          }
          if (country != null && !country.isBlank()) {
            where.append(" and destination_country = ?");
            params.add(country);
          }
          if (carrierChannel != null && !carrierChannel.isBlank()) {
            where.append(" and carrier_channel = ?");
            params.add(carrierChannel);
          }
          var total =
              countOf(
                  sql.fetchOne(
                      "select count(*) from reporting_logistics_analysis_waybill_fact" + where,
                      params.toArray()));
          var limited = Math.min(Math.max(size, 1), PAGE_LIMIT);
          var offset = Math.max(page, 0) * limited;
          var records =
              sql.fetch(
                  "select waybill_id,waybill_no,sales_order_no,occurred_on,occurred_at,carrier_code,"
                      + "carrier_channel,destination_country,gross_weight_kg,freight_amount,"
                      + "freight_currency,freight_gap_code from reporting_logistics_analysis_waybill_fact"
                      + where
                      + " order by occurred_on desc, waybill_no limit ? offset ?",
                  concat(params, limited, offset));

          var rows = new ArrayList<ReportingLogisticsAnalysisModels.LogisticsDrilldownRow>();
          for (var record : records) {
            var gap = record.get("freight_gap_code", String.class);
            var note =
                "REGISTERED".equals(gap)
                    ? "运费已登记。"
                    : "**运费未登记**（有票数、无金额）—— 不是 0 元，别当免费运费算。";
            rows.add(
                new ReportingLogisticsAnalysisModels.LogisticsDrilldownRow(
                    record.get("waybill_id", UUID.class),
                    record.get("waybill_no", String.class),
                    record.get("sales_order_no", String.class),
                    record.get("occurred_on", LocalDate.class),
                    record.get("occurred_at", OffsetDateTime.class),
                    record.get("carrier_code", String.class),
                    record.get("carrier_channel", String.class),
                    record.get("destination_country", String.class),
                    record.get("gross_weight_kg", BigDecimal.class),
                    record.get("freight_amount", BigDecimal.class),
                    record.get("freight_currency", String.class),
                    gap,
                    note));
          }

          var updatedAt =
              sql.fetchOne(
                      "select max(updated_at) from reporting_logistics_analysis_waybill_fact"
                          + " where tenant_id = ?",
                      tenantId)
                  .get(0, OffsetDateTime.class);

          var warnings = new ArrayList<String>();
          if (total == 0) {
            warnings.add("这个窗口**没有运单** —— 先刷新投影，或检查上游运单是否已同步。");
          }

          return new ReportingLogisticsAnalysisModels.LogisticsDrilldownResponse(
              from,
              to,
              rows,
              total,
              Math.max(page, 0),
              limited,
              updatedAt,
              updatedAt == null ? "尚未投影任何运单事实。" : "数据截至 " + updatedAt,
              "下钻 = **运单级事实**（trade 10.2.1.1）。"
                  + "**没有运费的行是「—」**，不在任何合计里（不是 0 元）。",
              warnings);
        });
  }

  // ==========================================================================
  // ⑩ 阈值规则
  // ==========================================================================

  /** 阈值规则清单（没有配置时给默认值）。 */
  public List<ReportingLogisticsAnalysisModels.LogisticsAnalysisRule> rules(UUID tenantId) {
    return tenants.run(
        tenantId,
        () -> {
          var list = new ArrayList<ReportingLogisticsAnalysisModels.LogisticsAnalysisRule>();
          list.add(
              ruleOrDefault(
                  tenantId,
                  "ON_TIME_TARGET",
                  new ReportingLogisticsAnalysisModels.LogisticsAnalysisRule(
                      "ON_TIME_TARGET",
                      DEFAULT_ON_TIME_TARGET,
                      null,
                      null,
                      null,
                      null,
                      1,
                      true,
                      null)));
          list.add(
              ruleOrDefault(
                  tenantId,
                  "FREIGHT_PER_ORDER_TARGET",
                  new ReportingLogisticsAnalysisModels.LogisticsAnalysisRule(
                      "FREIGHT_PER_ORDER_TARGET",
                      null,
                      DEFAULT_FREIGHT_PER_ORDER_TARGET,
                      DEFAULT_FREIGHT_PER_ORDER_CURRENCY,
                      null,
                      null,
                      1,
                      true,
                      null)));
          list.add(
              ruleOrDefault(
                  tenantId,
                  "EXCEPTION_SHARE_TARGET",
                  new ReportingLogisticsAnalysisModels.LogisticsAnalysisRule(
                      "EXCEPTION_SHARE_TARGET",
                      null,
                      null,
                      null,
                      DEFAULT_EXCEPTION_SHARE_TARGET,
                      null,
                      1,
                      true,
                      null)));
          list.add(
              ruleOrDefault(
                  tenantId,
                  "COST_TO_SALES_TARGET",
                  new ReportingLogisticsAnalysisModels.LogisticsAnalysisRule(
                      "COST_TO_SALES_TARGET",
                      null,
                      null,
                      null,
                      null,
                      DEFAULT_COST_TO_SALES_TARGET,
                      1,
                      true,
                      null)));
          return list;
        });
  }

  private ReportingLogisticsAnalysisModels.LogisticsAnalysisRule ruleOrDefault(
      UUID tenantId, String ruleType, ReportingLogisticsAnalysisModels.LogisticsAnalysisRule fallback) {
    var record =
        sql.fetchOne(
            "select rule_type,on_time_target_pct,freight_per_order_target,freight_per_order_currency,"
                + "exception_share_target_pct,cost_to_sales_target_pct,min_history_days,enabled,updated_at "
                + "from reporting_logistics_analysis_rule where tenant_id = ? and rule_type = ?",
            tenantId,
            ruleType);
    if (record == null) return fallback;
    return new ReportingLogisticsAnalysisModels.LogisticsAnalysisRule(
        ruleType,
        record.get("on_time_target_pct", BigDecimal.class),
        record.get("freight_per_order_target", BigDecimal.class),
        record.get("freight_per_order_currency", String.class),
        record.get("exception_share_target_pct", BigDecimal.class),
        record.get("cost_to_sales_target_pct", BigDecimal.class),
        intOf(record.get("min_history_days", Integer.class)),
        Boolean.TRUE.equals(record.get("enabled", Boolean.class)),
        record.get("updated_at", OffsetDateTime.class));
  }

  private ReportingLogisticsAnalysisModels.LogisticsAnalysisRule rule(UUID tenantId, String ruleType) {
    var record =
        sql.fetchOne(
            "select rule_type,on_time_target_pct,freight_per_order_target,freight_per_order_currency,"
                + "exception_share_target_pct,cost_to_sales_target_pct,min_history_days,enabled,updated_at "
                + "from reporting_logistics_analysis_rule where tenant_id = ? and rule_type = ?",
            tenantId,
            ruleType);
    if (record == null) return null;
    return new ReportingLogisticsAnalysisModels.LogisticsAnalysisRule(
        record.get("rule_type", String.class),
        record.get("on_time_target_pct", BigDecimal.class),
        record.get("freight_per_order_target", BigDecimal.class),
        record.get("freight_per_order_currency", String.class),
        record.get("exception_share_target_pct", BigDecimal.class),
        record.get("cost_to_sales_target_pct", BigDecimal.class),
        intOf(record.get("min_history_days", Integer.class)),
        Boolean.TRUE.equals(record.get("enabled", Boolean.class)),
        record.get("updated_at", OffsetDateTime.class));
  }

  /**
   * 改阈值（**只改本模块自己的规则表**）。
   *
   * <p>⚠️ 不写任何上游口径：达成率在 channel、分摊在 channel、运费登记在 trade。
   */
  public ReportingLogisticsAnalysisModels.LogisticsAnalysisRule updateRule(
      UUID tenantId,
      String ruleType,
      ReportingLogisticsAnalysisModels.LogisticsAnalysisRuleInput input) {
    return tenants.run(
        tenantId,
        () -> {
          var current = ruleOrDefault(tenantId, ruleType, null);
          var onTime = input.onTimeTargetPct() != null ? input.onTimeTargetPct()
              : (current == null ? null : current.onTimeTargetPct());
          var perOrder = input.freightPerOrderTarget() != null ? input.freightPerOrderTarget()
              : (current == null ? null : current.freightPerOrderTarget());
          var perOrderCurrency =
              input.freightPerOrderCurrency() != null && !input.freightPerOrderCurrency().isBlank()
                  ? input.freightPerOrderCurrency().trim().toUpperCase()
                  : (current == null ? null : current.freightPerOrderCurrency());
          var exceptionShare =
              input.exceptionShareTargetPct() != null ? input.exceptionShareTargetPct()
                  : (current == null ? null : current.exceptionShareTargetPct());
          var costToSales =
              input.costToSalesTargetPct() != null ? input.costToSalesTargetPct()
                  : (current == null ? null : current.costToSalesTargetPct());
          var minHistory =
              input.minHistoryDays() != null ? input.minHistoryDays()
                  : (current == null ? 1 : current.minHistoryDays());
          var enabled = input.enabled() != null ? input.enabled()
              : (current == null ? true : current.enabled());

          sql.execute(
              """
              insert into reporting_logistics_analysis_rule
                (id,tenant_id,rule_type,on_time_target_pct,
                 freight_per_order_target,freight_per_order_currency,
                 exception_share_target_pct,cost_to_sales_target_pct,
                 min_history_days,enabled,updated_at)
              values (?,?,?,?,?,?,?,?,?,?,now())
              on conflict (tenant_id,rule_type)
              do update set on_time_target_pct = excluded.on_time_target_pct,
                            freight_per_order_target = excluded.freight_per_order_target,
                            freight_per_order_currency = excluded.freight_per_order_currency,
                            exception_share_target_pct = excluded.exception_share_target_pct,
                            cost_to_sales_target_pct = excluded.cost_to_sales_target_pct,
                            min_history_days = excluded.min_history_days,
                            enabled = excluded.enabled,
                            updated_at = now()
              """,
              Ids.next(),
              tenantId,
              ruleType,
              onTime,
              perOrder,
              perOrderCurrency,
              exceptionShare,
              costToSales,
              minHistory,
              enabled);
          return ruleOrDefault(tenantId, ruleType, null);
        });
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  /** 最新成本快照日（{@code as_of <= 窗口止日} 里最大的一张）。 */
  private LocalDate latestCostAsOf(UUID tenantId, LocalDate to) {
    var row =
        sql.fetchOne(
            "select max(as_of) from reporting_logistics_analysis_cost_fact "
                + "where tenant_id = ? and as_of <= ?",
            tenantId,
            to);
    return row == null ? null : row.get(0, LocalDate.class);
  }

  /** 最新渠道成本快照日。 */
  private LocalDate latestChannelAsOf(UUID tenantId, LocalDate to) {
    var row =
        sql.fetchOne(
            "select max(as_of) from reporting_logistics_analysis_channel_fact "
                + "where tenant_id = ? and as_of <= ?",
            tenantId,
            to);
    return row == null ? null : row.get(0, LocalDate.class);
  }

  /** 最新时效快照日。 */
  private LocalDate latestTimelinessAsOf(UUID tenantId, LocalDate to) {
    var row =
        sql.fetchOne(
            "select max(as_of) from reporting_logistics_analysis_timeliness_fact "
                + "where tenant_id = ? and as_of <= ?",
            tenantId,
            to);
    return row == null ? null : row.get(0, LocalDate.class);
  }

  /** 相除（分母为 0 ⇒ null，**不可比不是 0**）。 */
  private static BigDecimal divide(BigDecimal numerator, BigDecimal denominator) {
    if (numerator == null || denominator == null || denominator.signum() == 0) return null;
    return numerator.divide(denominator, RATE_SCALE, RoundingMode.HALF_UP);
  }

  /** 百分数定标。 */
  private static BigDecimal rate(BigDecimal value) {
    return value == null ? null : value.setScale(RATE_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * 分量占合计的比重（**先汇总再相除**：分母是合计，不是各行比率的平均）。
   *
   * <p>🔴 分量或合计为 null ⇒ 比重是 **null（不可比）**，不是 0%；
   * 合计为 0 ⇒ 同样是 null（除法无意义）。
   */
  private static BigDecimal shareOf(BigDecimal component, BigDecimal subtotal) {
    if (component == null || subtotal == null || subtotal.signum() == 0) return null;
    return rate(component.multiply(new BigDecimal("100")).divide(subtotal, RATE_SCALE, RoundingMode.HALF_UP));
  }

  /** null 安全的长整型。 */
  private static long longOf(Long value) {
    return value == null ? 0L : value;
  }

  /** null 安全的整型。 */
  private static int intOf(Integer value) {
    return value == null ? 0 : value;
  }

  /** count(*) 结果。 */
  private static long countOf(org.jooq.Record record) {
    if (record == null) return 0L;
    return longOf(record.get(0, Long.class));
  }

  /** 逗号分隔 → 列表（空串 → 空列表）。 */
  private static List<String> split(String raw) {
    if (raw == null || raw.isBlank()) return List.of();
    var out = new ArrayList<String>();
    for (var part : raw.split(",")) {
      var trimmed = part.trim();
      if (!trimmed.isEmpty()) out.add(trimmed);
    }
    return out;
  }

  /** 参数数组拼接。 */
  private static Object[] concat(List<Object> params, Object... extra) {
    var all = new ArrayList<Object>(params);
    all.addAll(java.util.Arrays.asList(extra));
    return all.toArray();
  }
}
