package com.starcon.finance;

import static com.starcon.finance.CostVarianceModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 12.4.1.3 成本差异分析。
 *
 * <p>蓝图 12-finance.md：「分析成本差异：实际成本 vs 预算/标准成本、采购价波动、头程成本波动、损耗差异、汇率影响，定位利润波动主因。」输入：「成本计算结果；预算或历史基线；价格与汇率历史。」输出：「差异分析报表 + 主因归因 + 异常提示。」关系：「上游 = 12.4.1.2；下游 = 13 分析、6 采购（价格谈判依据）。<b>利润下滑时先看成本差异</b>，比看销售额更能定位问题。」
 *
 * <p><b>🔴 基数永远是 12.4.1.2 的真实结转金额。</b>本模块**只读**
 * {@code finance_cost_calculation_run} / {@code finance_cost_calculation_entry}
 * （同服务同库，jOOQ 显式 SQL），**自己绝不重算一遍成本** ——
 * 重算一遍就有了两个真相来源，两边对不上时没人说得清该信谁。
 *
 * <p><b>🔴 计价方法取「批次行上的快照」</b>（{@code valuation_method} /
 * {@code valuation_method_version}），**不是**「当前生效版本」——
 * 拿当前版本去套历史批次，口径就错了。
 *
 * <p><b>🔴 无基线 ≠ 差异为 0。</b>基线缺失时差异**不可计算**：明细行
 * {@code comparability = NO_BASELINE}、{@code amount} 为空、理由写明。
 * 绝不默默按 0 差异算。
 *
 * <p><b>🔴 多币种绝不相加。</b>报表按币种分组（{@code currencyGroups}），
 * 组内金额只在同一币种内相加；汇率影响用**两档真实汇率**（期初 / 期末）隔离出来，
 * 汇率查不到就如实标「上游不可得」，**绝不按 1:1 兜底**。
 *
 * <p><b>🔴 跨服务数据一律走授权只读端口</b>（AGENTS.md 禁止跨库 SQL / FK / 视图）：
 * 采购价走 {@link PurchasePricePort}（trade 6 采购）、头程成本走 {@link FirstMilePort}
 * （warehouse 10.4.1.1）、损耗数量走 {@link CostCalculationFlowPort}（warehouse 7.1.2.1）、
 * 汇率走 {@link ExchangeRatePort}（catalog 5.2.2.1）、SKU 类目走 {@link CatalogSkuPort}
 * （catalog 3.1.2.1）。
 * 每个上游读不到时**只影响它自己那一类差异**，其余照常出报表。
 *
 * <p><b>🔴 数量账在 warehouse、价值账在 finance。</b>本模块**只读**库存流水，
 * 一条都不改。
 *
 * <p><b>金额与数量</b>：金额 {@code numeric(19,4)}、数量 {@code numeric(19,6)}，
 * 全程 BigDecimal，无浮点；时间列 {@code timestamptz} 一律 OffsetDateTime 绑定 +
 * {@code ?::timestamptz}。
 */
@Service
public class CostVarianceService {

  /** 一页最大条数（与同服务其它模块一致）。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 金额小数位（与同服务 12.4.1.1 / 12.4.1.2 一致）。 */
  static final int MONEY_SCALE = 4;

  /** 数量小数位（AGENTS.md）。 */
  static final int QUANTITY_SCALE = 6;

  /**
   * 异常波动阈值：|差异| ÷ 标准成本 ≥ 20% 记一条提示。
   *
   * <p>🔴 这是**口径约定**（可讨论），不是从上游继承的规则 —— 差异分析必须有一条
   * 「多大算异常」的线，否则「异常提示」就退化成一句废话。它作用在**可比**的
   * 标准成本差异上；无基线的 SKU 走 NO_BASELINE 告警，不走这条。
   */
  static final BigDecimal VARIANCE_ALERT_RATE = new BigDecimal("0.20");

  /** 上游列表翻页上限（与 {@link PurchasePricePort} / {@link FirstMilePort} 一致）。 */
  private static final int MAX_UPSTREAM_PAGES = 25;

  private final DSLContext sql;
  private final PurchasePricePort purchasePrices;
  private final FirstMilePort firstMile;
  private final CostCalculationFlowPort flows;
  private final ExchangeRatePort exchangeRates;
  private final CatalogSkuPort catalogSkus;

  public CostVarianceService(
      DSLContext sql,
      PurchasePricePort purchasePrices,
      FirstMilePort firstMile,
      CostCalculationFlowPort flows,
      ExchangeRatePort exchangeRates,
      CatalogSkuPort catalogSkus) {
    this.sql = sql;
    this.purchasePrices = purchasePrices;
    this.firstMile = firstMile;
    this.flows = flows;
    this.exchangeRates = exchangeRates;
    this.catalogSkus = catalogSkus;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：**每一维说清「有没有、依据是什么、缺了会怎样」**。
   *
   * <p>⚠️ 本模块的缺口要说清归谁：13 利润在**别的服务**（未交付）；6 采购已交付，
   * 本模块只**读**它的采购事实。
   */
  public FinanceCostVarianceCapabilities capabilities() {
    var capabilities = new ArrayList<FinanceCostVarianceCapability>();
    var gaps = new ArrayList<FinanceCostVarianceDownstreamGap>();

    capabilities.add(
        new FinanceCostVarianceCapability(
            "12.4.1.3",
            "成本差异分析（报表 + 归因 + 异常提示）",
            true,
            "finance_cost_variance_report + finance_cost_variance_item"
                + " + finance_cost_variance_driver + finance_cost_variance_alert",
            "",
            "按期间与范围把 12.4.1.2 的真实结转金额拆成**五类差异**"
                + "（实际 vs 预算/标准成本、采购价波动、头程成本波动、损耗差异、汇率影响），"
                + "**按币种分组**呈现，给出主因归因与异常提示。"
                + "报表一经生成不可改写 —— 历史即证据。"));

    capabilities.add(
        new FinanceCostVarianceCapability(
            "12.4.1.2",
            "差异分析的基数（成本计算结果）",
            true,
            "finance_cost_calculation_run + finance_cost_calculation_entry（同服务同库，只读）",
            "",
            "基数 = 12.4.1.2 **已经算出来的应结转金额**，本模块**不重算一遍成本**。"
                + "计价方法取**批次行上的快照**，不是「当前生效版本」。"
                + "已被接替的批次（superseded_by_run_id 不为空）**不计入**，"
                + "否则重算会把两批成本加起来。"));

    capabilities.add(
        new FinanceCostVarianceCapability(
            "12.4.1.3",
            "标准成本 / 预算基线（自建）",
            true,
            "finance_cost_variance_baseline（按 SKU 或类目 × 币种 × 生效区间 版本化）",
            "",
            "🔴 本仓此前**没有**标准成本 / 预算表 ⇒「实际成本 vs 预算/标准成本」"
                + "在 12.4.1.3 之前根本算不出来，本表是它的唯一真相源。"
                + "改基线 = 追加新版本；生效区间不许重叠；历史版本不可改写。"
                + "🔴 **无基线 ≠ 差异为 0**：查不到基线时差异**不可计算**，"
                + "报表如实写「无基线，不可比」。"));

    capabilities.add(
        new FinanceCostVarianceCapability(
            "6",
            "采购价波动（跨服务只读）",
            true,
            "trade GET /trade/purchase-analytics/facts（PurchasePricePort 只读）",
            "",
            "按上游自己的口径（COST + ARRIVAL_RECEIPT）读采购成本事实，"
                + "按 (SKU × 币种) 算本期与上期的加权平均采购单价。"
                + "**绝不跨币种相加**。上游读不到 ⇒ 该类差异标「上游不可得」，"
                + "**不退化成 0 差异**。"));

    capabilities.add(
        new FinanceCostVarianceCapability(
            "10.4.1.1",
            "头程成本波动（跨服务只读）",
            true,
            "warehouse GET /warehouse/first-mile-shipments（FirstMilePort 只读）",
            "",
            "头程发运单的运费 + 保险费 + 处理费按币种汇总，与**上一等长期间**对比。"
                + "它是**单据级**的（按头程发运单，不按 SKU）⇒ 只进按币种的驱动因素汇总，"
                + "不进逐 SKU 明细。"));

    capabilities.add(
        new FinanceCostVarianceCapability(
            "7.1.2.1",
            "损耗差异（跨服务只读）",
            true,
            "warehouse GET /warehouse/inventory/ledger + /{transactionId}"
                + "（CostCalculationFlowPort 只读，ADJUSTMENT 类流水）",
            "",
            "数量账在 warehouse：损耗数量 = 调整流水的**负向**在手变动。"
                + "本模块**只读**库存流水，一条都不改。"
                + "损耗按该 SKU 的单位成本折成金额（取不到单位成本 ⇒ 不可计算，不按 0）。"));

    capabilities.add(
        new FinanceCostVarianceCapability(
            "5.2.2.1",
            "汇率影响（跨服务只读）",
            true,
            "catalog GET /catalog/exchange-rates/latest（ExchangeRatePort，只读）",
            "",
            "汇率影响 = 实际成本 × (1 − 期初汇率 ÷ 期末汇率)，"
                + "**在本币口径下**表达（不跨币种相加）。"
                + "两档汇率都从 catalog 真实读；查不到 ⇒ 「上游不可得」，"
                + "**绝不按 1:1 兜底**。"));

    capabilities.add(
        new FinanceCostVarianceCapability(
            "3.1.2.1",
            "SKU → 类目映射（仅类目维度基线时用到）",
            true,
            "catalog GET /catalog/skus（CatalogSkuPort 只读）",
            "",
            "类目维度的基线要按类目找，类目在 catalog 的 SKU 主数据里只有**编码**。"
                + "读不到 ⇒ 类目维度的差异标「上游不可得」，"
                + "**不退化成「无基线」更不退化成 0 差异**。"));

    gaps.add(
        new FinanceCostVarianceDownstreamGap(
            "13",
            "利润与经营分析",
            "reporting",
            false,
            "下游",
            "🔴 **未交付**。本模块产出**成本差异**，利润自己去取 —— "
                + "本模块**不产出任何利润数字**。"));

    gaps.add(
        new FinanceCostVarianceDownstreamGap(
            "6",
            "采购（价格谈判依据）",
            "trade",
            true,
            "下游",
            "6 采购已交付。本模块的「采购价波动」是**价格谈判的依据**，"
                + "但采购侧的价**不归本模块写** —— 本模块只读它的采购事实。"));

    gaps.add(
        new FinanceCostVarianceDownstreamGap(
            "12.4.1.2",
            "成本计算执行",
            "finance",
            true,
            "上游",
            "已交付。本模块**只读**它的批次与成本分录作为基数。"));

    return new FinanceCostVarianceCapabilities(capabilities, gaps);
  }

  // ================================================================ 选项

  /** 筛选项下拉：枚举项来自真实枚举，币种来自本服务真实数据。 */
  public FinanceCostVarianceOptions options() {
    var baselineTypes = new ArrayList<String>();
    for (var t : BaselineType.values()) baselineTypes.add(t.name());
    var dimensions = new ArrayList<String>();
    for (var d : BaselineDimension.values()) dimensions.add(d.name());
    var drivers = new ArrayList<String>();
    for (var d : VarianceDriver.values()) drivers.add(d.name());
    var comparabilities = new ArrayList<String>();
    for (var c : Comparability.values()) comparabilities.add(c.name());
    var severities = new ArrayList<String>();
    for (var s : AlertSeverity.values()) severities.add(s.name());
    var currencies =
        sql.fetch(
                "select distinct currency from finance_cost_variance_driver"
                    + " union select distinct currency from finance_cost_variance_baseline"
                    + " order by 1")
            .stream()
            .map(r -> r.get(0, String.class))
            .filter(java.util.Objects::nonNull)
            .toList();
    return new FinanceCostVarianceOptions(
        baselineTypes, dimensions, currencies, drivers, comparabilities, severities);
  }

  // ================================================================ 基线维护

  /** 基线列表（真分页 + 真筛选）。 */
  public FinanceCostVarianceBaselinePage baselines(
      String baselineType, String dimension, String dimensionRefId, String currency, String status,
      int page, int size) {
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    if (baselineType != null && !baselineType.isBlank()) {
      where.add("baseline_type = ?");
      binds.add(baselineType.trim().toUpperCase(Locale.ROOT));
    }
    if (dimension != null && !dimension.isBlank()) {
      where.add("dimension = ?");
      binds.add(dimension.trim().toUpperCase(Locale.ROOT));
    }
    if (dimensionRefId != null && !dimensionRefId.isBlank()) {
      where.add("(dimension_ref_id = ?::uuid or dimension_key = ? or dimension_code = ?)");
      binds.add(dimensionRefId.trim());
      binds.add(dimensionRefId.trim());
      binds.add(dimensionRefId.trim());
    }
    if (currency != null && !currency.isBlank()) {
      where.add("currency = ?");
      binds.add(currency.trim().toUpperCase(Locale.ROOT));
    }
    if (status != null && !status.isBlank()) {
      where.add("status = ?");
      binds.add(status.trim().toUpperCase(Locale.ROOT));
    }
    var clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from finance_cost_variance_baseline" + clause, binds.toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
            "select id,baseline_type,dimension,dimension_key,dimension_ref_id,dimension_code,"
                + " dimension_name,currency,unit_cost,effective_from,effective_to,status,version,"
                + " supersedes_id,note,created_by,created_at,updated_at"
                + " from finance_cost_variance_baseline"
                + clause
                + " order by dimension,dimension_code,currency,version desc limit ? offset ?",
            concat(binds, Math.min(Math.max(size, 1), MAX_PAGE_SIZE), Math.max(0, page) * size));
    return new FinanceCostVarianceBaselinePage(
        rows.stream().map(this::baselineRow).toList(),
        Math.max(0, page),
        Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
        total);
  }

  /**
   * 登记 / 切换一版基线（= **追加新版本**，不是就地改）。
   *
   * <p>🔴 写库顺序：先把上一版置为 SUPERSEDED 并**收口生效区间**，再插入新的一版。
   * 触发器钉死版本号递增 + 生效起点严格递进 + 区间不许重叠 + 历史不可改写。
   */
  public FinanceCostVarianceBaselineRow upsertBaseline(
      FinanceCostVarianceBaselineRequest request, AccessService.Access actor) {
    var type = BaselineType.parse(request.baselineType());
    if (type == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "基线类型只能是 STANDARD_COST 或 BUDGET");
    }
    var dimension = BaselineDimension.parse(request.dimension());
    if (dimension == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "基线维度只能是 SKU 或 CATEGORY");
    }
    if (dimension == BaselineDimension.SKU && request.dimensionRefId() == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "按 SKU 建基线必须给出 dimensionRefId（catalog 的 SKU id）");
    }
    if (dimension == BaselineDimension.CATEGORY
        && (request.dimensionCode() == null || request.dimensionCode().isBlank())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "按类目建基线必须给出 dimensionCode（catalog 的类目编码）");
    }
    var currency = request.currency() == null ? "" : request.currency().trim().toUpperCase(Locale.ROOT);
    if (!currency.matches("^[A-Z]{3}$")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "基线币种必须是三位大写字母（ISO 4217）");
    }
    if (request.unitCost() == null || request.unitCost().signum() <= 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "基线单位成本必须大于 0：写成 0 等于「这个 SKU 白送」，"
              + "那是参数没维护，不是成本为 0");
    }
    if (request.effectiveFrom() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须给出基线生效时间");
    }

    var dimensionKey =
        dimension == BaselineDimension.SKU
            ? request.dimensionRefId().toString()
            : "CATEGORY:" + request.dimensionCode().trim();
    var dimensionRefId = dimension == BaselineDimension.SKU ? request.dimensionRefId() : null;
    var dimensionCode = request.dimensionCode() == null ? "" : request.dimensionCode().trim();
    var dimensionName = request.dimensionName() == null ? "" : request.dimensionName().trim();

    // 上一版（版本号最大的那一版）。
    var last =
        sql.fetch(
                "select id,version,effective_from,effective_to,status from finance_cost_variance_baseline"
                    + " where baseline_type = ? and dimension = ? and dimension_key = ? and currency = ?"
                    + " order by version desc limit 1",
                type.name(), dimension.name(), dimensionKey, currency)
            .stream()
            .findFirst()
            .orElse(null);

    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var id = UUID.randomUUID();
    int version = 1;
    UUID supersedesId = null;
    if (last != null) {
      version = last.get("version", Integer.class) + 1;
      supersedesId = last.get("id", UUID.class);
      var lastFrom = last.get("effective_from", OffsetDateTime.class);
      if (!request.effectiveFrom().isAfter(lastFrom)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "新版本的生效时间必须晚于上一版本（"
                + lastFrom
                + "）：否则历史差异分析会被新基线追溯改写");
      }
      // 先收口上一版：置为 SUPERSEDED + 收口生效区间（历史只读，不改基线值）。
      sql.execute(
          "update finance_cost_variance_baseline set status = 'SUPERSEDED', effective_to = ?::timestamptz,"
              + " updated_at = ?::timestamptz where id = ? and tenant_id = ?",
          request.effectiveFrom(), now, last.get("id", UUID.class), currentTenant());
    }

    sql.execute(
        "insert into finance_cost_variance_baseline(id,tenant_id,baseline_type,dimension,dimension_key,"
            + " dimension_ref_id,dimension_code,dimension_name,currency,unit_cost,effective_from,"
            + " effective_to,status,version,supersedes_id,note,created_by,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?::timestamptz,null,'ACTIVE',?,?,?,?,?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        type.name(),
        dimension.name(),
        dimensionKey,
        dimensionRefId,
        dimensionCode,
        dimensionName,
        currency,
        request.unitCost().setScale(MONEY_SCALE, RoundingMode.HALF_UP),
        request.effectiveFrom(),
        version,
        supersedesId,
        request.note() == null ? "" : request.note().trim(),
        actor == null ? null : actor.userId(),
        now,
        now);
    return baseline(id);
  }

  /** 版本历史（新到旧）。 */
  public List<FinanceCostVarianceBaselineRow> baselineVersions(
      String baselineType, String dimension, String dimensionKey, String currency) {
    var type = BaselineType.parse(baselineType);
    var dim = BaselineDimension.parse(dimension);
    if (type == null || dim == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "基线类型与维度都要给（STANDARD_COST/BUDGET，SKU/CATEGORY）");
    }
    var cur = currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
    var rows =
        sql.fetch(
            "select id,baseline_type,dimension,dimension_key,dimension_ref_id,dimension_code,"
                + " dimension_name,currency,unit_cost,effective_from,effective_to,status,version,"
                + " supersedes_id,note,created_by,created_at,updated_at"
                + " from finance_cost_variance_baseline"
                + " where baseline_type = ? and dimension = ? and dimension_key = ?"
                + (cur.isEmpty() ? "" : " and currency = ?")
                + " order by currency, version desc",
            cur.isEmpty()
                ? new Object[] {type.name(), dim.name(), dimensionKey}
                : new Object[] {type.name(), dim.name(), dimensionKey, cur});
    return rows.stream().map(this::baselineRow).toList();
  }

  /** 版本对比（切换前 / 切换后）：逐字段给出差异。 */
  public FinanceCostVarianceBaselineComparison compareBaselines(
      String baselineType, String dimension, String dimensionKey, String currency,
      int fromVersion, int toVersion) {
    var type = BaselineType.parse(baselineType);
    var dim = BaselineDimension.parse(dimension);
    if (type == null || dim == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "基线类型与维度都要给");
    }
    var cur = currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
    if (cur.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "版本对比必须给出币种");
    }
    var from = baselineVersion(type.name(), dim.name(), dimensionKey, cur, fromVersion);
    var to = baselineVersion(type.name(), dim.name(), dimensionKey, cur, toVersion);
    if (from == null || to == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "要对比的那一版基线不存在");
    }
    var changed = new ArrayList<String>();
    if (from.unitCost().compareTo(to.unitCost()) != 0) changed.add("unitCost");
    if (!from.effectiveFrom().isEqual(to.effectiveFrom())) changed.add("effectiveFrom");
    if (!java.util.Objects.equals(from.effectiveTo(), to.effectiveTo())) changed.add("effectiveTo");
    if (!from.status().equals(to.status())) changed.add("status");
    return new FinanceCostVarianceBaselineComparison(
        from.id(), from.version(), from.unitCost(), from.effectiveFrom(), from.effectiveTo(),
        from.status(), to.id(), to.version(), to.unitCost(), to.effectiveFrom(), to.effectiveTo(),
        to.status(),
        to.unitCost().subtract(from.unitCost()).setScale(MONEY_SCALE, RoundingMode.HALF_UP),
        cur, dim.name(), from.dimensionRefId(), changed,
        "版本 " + from.version() + " → " + to.version()
            + "；历史差异分析仍按当时那版基线算（基线版本不可改写）。");
  }

  // ================================================================ 差异分析

  /**
   * 执行一次差异分析：12.4.1.2 的真实结转金额 → 五类差异 → 报表 + 归因 + 异常提示。
   *
   * <p>⚠️ **上游读不到不会让整张报表失败**：只有受影响的那一类差异被标成
   * 「上游不可得」，其余照常出数。整张报表只有在<b>基数</b>（12.4.1.2 的批次）
   * 缺失时才拒绝 —— 没有基数就没有差异可言。
   */
  public FinanceCostVarianceReportDetail analyze(
      FinanceCostVarianceReportRequest request, AccessService.Access actor, String bearer) {
    // ---- 1. 参数校验 ----
    var scopeType =
        request.scopeType() == null ? "" : request.scopeType().trim().toUpperCase(Locale.ROOT);
    if (!"LEGAL_ENTITY".equals(scopeType) && !"CATEGORY".equals(scopeType)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "计价范围只能是 LEGAL_ENTITY（按法人）或 CATEGORY（按品类）");
    }
    if (request.scopeRefId() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须指定计价范围（法人或品类）");
    }
    if (request.periodFrom() == null || request.periodTo() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须给出分析期间（起止都要）");
    }
    if (!request.periodTo().isAfter(request.periodFrom())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "分析期间的止必须晚于起（期间是左闭右开的，空区间不是分析）");
    }
    var reportingCurrency =
        request.reportingCurrency() == null
            ? ""
            : request.reportingCurrency().trim().toUpperCase(Locale.ROOT);
    if (!reportingCurrency.matches("^[A-Z]{3}$")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "报表币种必须是三位大写字母（ISO 4217）");
    }
    var baselineType = BaselineType.parse(request.baselineType());
    if (baselineType == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "基线类型只能是 STANDARD_COST 或 BUDGET");
    }
    var dimension =
        request.dimension() == null || request.dimension().isBlank()
            ? BaselineDimension.SKU
            : BaselineDimension.parse(request.dimension());
    if (dimension == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "基线维度只能是 SKU 或 CATEGORY");
    }

    // ---- 2. 基数：12.4.1.2 的批次（只读）----
    var runs =
        sql.fetch(
                "select id,valuation_method,valuation_method_version,currency,period_from,period_to,"
                    + " created_at from finance_cost_calculation_run"
                    + " where scope_type = ? and scope_ref_id = ?::uuid and status = 'COMPLETED'"
                    + " and superseded_by_run_id is null"
                    + " and period_from < ?::timestamptz and period_to > ?::timestamptz"
                    + " order by period_from, created_at, id",
                scopeType,
                request.scopeRefId(),
                request.periodTo(),
                request.periodFrom())
            .stream()
            .toList();
    if (runs.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "该范围该期间没有已完成的成本计算批次：请先在 12.4.1.2 执行一次成本计算，"
              + "差异分析的基数只能是它算出来的应结转金额");
    }
    // 🔴 同一段期间可能有多批（重算）。已被接替的批次已在 SQL 里排除；
    //   这里再按「期间完全相同」保留最新的一批，避免同一段时间的成本被加两遍。
    var picked = new LinkedHashMap<String, Record>();
    for (var run : runs) {
      // 🔴 去重键**必须含币种**：12.4.1.2 的批次是「期间 × 币种」的 ——
      //   同一期间里 USD 与 EUR 各有一批是**合法**的，不是重算。
      //   只有「同期间 + 同币种」的两批才是重算关系，保留较新的那一批。
      var key =
          run.get("period_from", OffsetDateTime.class)
              + "|"
              + run.get("period_to", OffsetDateTime.class)
              + "|"
              + run.get("currency", String.class);
      var existing = picked.get(key);
      if (existing == null
          || run.get("created_at", OffsetDateTime.class)
              .isAfter(existing.get("created_at", OffsetDateTime.class))) {
        picked.put(key, run);
      }
    }
    var runList = new ArrayList<>(picked.values());

    // ---- 3. 成本分录（按 SKU × 币种聚合）----
    var runIds = runList.stream().map(r -> r.get("id", UUID.class)).toList();
    var placeholders = String.join(",", java.util.Collections.nCopies(runIds.size(), "?"));
    var entries =
        sql.fetch(
                "select sku_id, max(sku_code) as sku_code, currency,"
                    + " sum(issue_quantity) as issue_qty, sum(covered_quantity) as covered_qty,"
                    + " sum(shortfall_quantity) as shortfall_qty, sum(cost_amount) as cost_amount,"
                    + " count(*) as entry_count,"
                    + " sum(case when status = 'INCOMPLETE' then 1 else 0 end) as incomplete_count"
                    + " from finance_cost_calculation_entry where run_id in (" + placeholders + ")"
                    + " group by sku_id, currency order by sku_id, currency",
                runIds.toArray())
            .stream()
            .toList();
    if (entries.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "该范围该期间的批次里没有成本分录：没有结转金额就没有差异可分析");
    }

    // ---- 4. 基线（标准成本 / 预算）与 SKU → 类目映射 ----
    var baselines = loadEffectiveBaselines(baselineType.name(), request.periodFrom());
    var skuCategories = new LinkedHashMap<UUID, String>();
    String categoryMapFailure = null;
    if (dimension == BaselineDimension.CATEGORY) {
      try {
        skuCategories.putAll(loadSkuCategories(bearer));
      } catch (RuntimeException e) {
        categoryMapFailure = e.getMessage() == null ? "读不到 catalog 的 SKU 类目映射" : e.getMessage();
      }
    }

    // ---- 5. 汇率快照（期初 / 期末各一档）----
    var periodFromDate = request.periodFrom().atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
    var periodToDate = request.periodTo().atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
    var currencies =
        entries.stream().map(r -> r.get("currency", String.class)).distinct().sorted().toList();
    var ratePlan = new LinkedHashMap<String, BigDecimal>();
    var rateActual = new LinkedHashMap<String, BigDecimal>();
    var rateFailure = new LinkedHashMap<String, String>();
    for (var currency : currencies) {
      if (currency.equals(reportingCurrency)) {
        // 同币种：汇率恒等（这不是「按 1:1 兜底」—— 本来就是同一个币种）。
        ratePlan.put(currency, BigDecimal.ONE);
        rateActual.put(currency, BigDecimal.ONE);
        continue;
      }
      var plan = rate(currency, reportingCurrency, periodFromDate, bearer);
      var actual = rate(currency, reportingCurrency, periodToDate, bearer);
      if (plan == null || actual == null) {
        rateFailure.put(
            currency, "catalog 服务读不到 " + currency + " → " + reportingCurrency + " 的汇率");
        continue;
      }
      ratePlan.put(currency, plan);
      rateActual.put(currency, actual);
    }

    // ---- 6. 采购价（trade 6 采购）：本期 vs 上一等长期间 ----
    var purchasePeriodTo = periodToDate.minusDays(1);
    var periodDays = periodToDate.toEpochDay() - periodFromDate.toEpochDay();
    var previousTo = periodFromDate.minusDays(1);
    var previousFrom = previousTo.minusDays(periodDays);
    var currentPurchase = new LinkedHashMap<String, PurchasePricePort.SkuPurchasePrice>();
    var previousPurchase = new LinkedHashMap<String, PurchasePricePort.SkuPurchasePrice>();
    String purchaseFailure = null;
    try {
      for (var p : purchasePrices.prices(periodFromDate, purchasePeriodTo, bearer)) {
        currentPurchase.put(purchaseKey(p.skuRefId(), p.currency()), p);
      }
      for (var p : purchasePrices.prices(previousFrom, previousTo, bearer)) {
        previousPurchase.put(purchaseKey(p.skuRefId(), p.currency()), p);
      }
    } catch (RuntimeException e) {
      purchaseFailure = e.getMessage() == null ? "上游读不到采购事实" : e.getMessage();
    }

    // ---- 7. 头程成本（warehouse 10.4.1.1）----
    Map<String, BigDecimal> firstMileCurrent = new LinkedHashMap<>();
    Map<String, BigDecimal> firstMilePrevious = new LinkedHashMap<>();
    String firstMileFailure = null;
    try {
      firstMileCurrent = firstMileTotals(request.periodFrom(), request.periodTo(), bearer);
      firstMilePrevious =
          firstMileTotals(
              request.periodFrom().minus(Duration.ofDays(periodDays)), request.periodFrom(), bearer);
    } catch (RuntimeException e) {
      firstMileFailure = e.getMessage() == null ? "上游读不到头程发运单" : e.getMessage();
    }

    // ---- 8. 损耗数量（warehouse 7.1.2.1）----
    Map<UUID, BigDecimal> shrinkage = new LinkedHashMap<>();
    String shrinkageFailure = null;
    try {
      shrinkage = shrinkageQuantities(request.periodFrom(), request.periodTo(), bearer);
    } catch (RuntimeException e) {
      shrinkageFailure = e.getMessage() == null ? "上游读不到库存流水" : e.getMessage();
    }

    // ---- 9. 逐 (SKU × 币种) 算五类差异 ----
    var alerts = new ArrayList<FinanceCostVarianceAlertRow>();
    var driverAccumulator = new LinkedHashMap<String, DriverAccumulator>();
    var itemRows = new ArrayList<FinanceCostVarianceItemRow>();
    var comparableSkuKeys = new LinkedHashMap<String, Boolean>();
    var noBaselineKeys = new LinkedHashMap<String, Boolean>();

    for (var entry : entries) {
      var skuId = entry.get("sku_id", UUID.class);
      var skuCode = entry.get("sku_code", String.class);
      var currency = entry.get("currency", String.class);
      var issueQty = entry.get("issue_qty", BigDecimal.class);
      var coveredQty = entry.get("covered_qty", BigDecimal.class);
      var shortfallQty = entry.get("shortfall_qty", BigDecimal.class);
      var actualCost = entry.get("cost_amount", BigDecimal.class);
      var incomplete = entry.get("incomplete_count", Long.class).intValue();
      var groupKey = skuId + "|" + currency;

      // ---- 9.1 实际成本 vs 预算 / 标准成本 ----
      var chainKey = baselineChainKey(dimension, skuId, skuCategories);
      var chain = chainKey == null ? null : baselines.get(chainKey);
      FinanceCostVarianceBaselineRow baseline = null;
      Comparability standardComparability;
      String standardReason;
      BigDecimal standardCost = null;
      BigDecimal unitCost = null;
      BigDecimal totalVariance = null;
      if (chainKey == null) {
        standardComparability = Comparability.UPSTREAM_UNAVAILABLE;
        standardReason =
            (categoryMapFailure == null ? "catalog 里没有该 SKU 的类目信息" : categoryMapFailure)
                + " ⇒ 找不到类目基线，差异不可计算";
      } else if (chain == null || chain.isEmpty()) {
        standardComparability = Comparability.NO_BASELINE;
        standardReason =
            "该 SKU 在 "
                + periodFromDate
                + " 没有生效的"
                + (baselineType == BaselineType.BUDGET ? "预算" : "标准成本")
                + "基线 ⇒ 差异不可计算（**无基线不是差异为 0**）";
      } else {
        for (var candidate : chain) {
          if (candidate.currency().equals(currency)) {
            baseline = candidate;
            break;
          }
        }
        if (baseline == null) {
          standardComparability = Comparability.CURRENCY_MISMATCH;
          var available = chain.stream().map(FinanceCostVarianceBaselineRow::currency).distinct().toList();
          standardReason =
              "基线币种是 " + String.join("/", available)
                  + "，实际成本币种是 "
                  + currency
                  + " ⇒ 绝不跨币种相减，差异不可计算";
        } else {
          standardComparability = Comparability.COMPARABLE;
          standardReason = "";
          unitCost = baseline.unitCost();
          standardCost = unitCost.multiply(issueQty).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
          totalVariance = actualCost.subtract(standardCost).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
      }
      if (standardComparability == Comparability.NO_BASELINE) {
        noBaselineKeys.put(groupKey, true);
      } else if (standardComparability == Comparability.COMPARABLE) {
        comparableSkuKeys.put(groupKey, true);
      }
      itemRows.add(
          new FinanceCostVarianceItemRow(
              null, null, skuId, skuCode, currency,
              VarianceDriver.STANDARD_COST.name(), VarianceDriver.STANDARD_COST.label(),
              totalVariance, null, issueQty, actualCost, standardCost, unitCost,
              baseline == null ? null : baseline.id(),
              baseline == null ? null : baseline.version(),
              standardComparability.name(), standardComparability.label(), standardReason,
              "实际成本 " + actualCost.toPlainString() + " " + currency
                  + "（出库量 " + issueQty.toPlainString() + "，结转量 " + coveredQty.toPlainString()
                  + "）；标准成本 = 标准单价 × 出库量（出库量才是「应该结转多少」的口径）。"
                  + (shortfallQty.signum() > 0
                      ? " 本 SKU 有 " + shortfallQty.toPlainString() + " 的结转缺口（库存不足）。"
                      : "")));

      // ---- 9.2 汇率影响（本币口径）----
      BigDecimal fxImpact = null;
      Comparability fxComparability;
      String fxReason;
      if (currency.equals(reportingCurrency)) {
        fxImpact = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        fxComparability = Comparability.COMPARABLE;
        fxReason = "成本币种与报表币种相同（" + currency + "）⇒ 汇率影响恒为 0";
      } else if (rateFailure.containsKey(currency)) {
        fxComparability = Comparability.UPSTREAM_UNAVAILABLE;
        fxReason = rateFailure.get(currency) + " ⇒ 汇率影响不可计算（**绝不按 1:1 兜底**）";
      } else {
        var plan = ratePlan.get(currency);
        var actual = rateActual.get(currency);
        // 汇率影响（本币口径）= 实际成本 × (1 − 期初汇率 ÷ 期末汇率)。
        fxImpact =
            actualCost
                .multiply(BigDecimal.ONE.subtract(plan.divide(actual, 12, RoundingMode.HALF_UP)))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        fxComparability = Comparability.COMPARABLE;
        fxReason =
            "实际成本 × (1 − 期初汇率 " + plan.toPlainString() + " ÷ 期末汇率 "
                + actual.toPlainString() + ")";
      }
      itemRows.add(
          new FinanceCostVarianceItemRow(
              null, null, skuId, skuCode, currency, VarianceDriver.FX.name(),
              VarianceDriver.FX.label(), fxImpact, null, null, null, null, null, null, null,
              fxComparability.name(), fxComparability.label(), fxReason,
              "汇率影响是**总差异的构成部分**（不是独立相加的一类）。"));

      // ---- 9.3 采购价波动 ----
      BigDecimal purchaseVariance = null;
      Comparability purchaseComparability;
      String purchaseReason;
      if (purchaseFailure != null) {
        purchaseComparability = Comparability.UPSTREAM_UNAVAILABLE;
        purchaseReason = purchaseFailure + " ⇒ 采购价波动不可计算（**不退化成 0 差异**）";
      } else {
        var current = currentPurchase.get(purchaseKey(skuId, currency));
        var previous = previousPurchase.get(purchaseKey(skuId, currency));
        if (current == null || previous == null) {
          purchaseComparability = Comparability.UPSTREAM_UNAVAILABLE;
          purchaseReason =
              "上游没有该 SKU 在 " + currency + " 的采购成本事实（本期或上期缺失）⇒ 采购价波动不可计算";
        } else if (!current.quantityBasis().equals(previous.quantityBasis())) {
          purchaseComparability = Comparability.UPSTREAM_UNAVAILABLE;
          purchaseReason =
              "两期采购数量口径不一致（本期 " + current.quantityBasis() + "，上期 " + previous.quantityBasis()
                  + "）⇒ 单价不可比，采购价波动不可计算";
        } else if (current.averagePrice() == null || previous.averagePrice() == null) {
          purchaseComparability = Comparability.UPSTREAM_UNAVAILABLE;
          purchaseReason = "上游采购事实没有可用的单价 ⇒ 采购价波动不可计算";
        } else {
          purchaseComparability = Comparability.COMPARABLE;
          var priceDiff = current.averagePrice().subtract(previous.averagePrice());
          purchaseVariance = priceDiff.multiply(coveredQty).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
          purchaseReason =
              "（本期均价 " + current.averagePrice().toPlainString() + " − 上期均价 "
                  + previous.averagePrice().toPlainString() + "）× 结转量 " + coveredQty.toPlainString();
        }
      }
      itemRows.add(
          new FinanceCostVarianceItemRow(
              null, null, skuId, skuCode, currency, VarianceDriver.PURCHASE_PRICE.name(),
              VarianceDriver.PURCHASE_PRICE.label(), purchaseVariance, null, null, null, null, null,
              null, null, purchaseComparability.name(), purchaseComparability.label(),
              purchaseReason,
              "采购价波动是**独立观察到的驱动因素**（解释单位成本为什么动了），与总差异不是可相加的两笔。"));

      // ---- 9.4 损耗差异 ----
      BigDecimal shrinkageQty = shrinkage.get(skuId);
      BigDecimal shrinkageVariance = null;
      Comparability shrinkageComparability;
      String shrinkageReason;
      if (shrinkageFailure != null) {
        shrinkageComparability = Comparability.UPSTREAM_UNAVAILABLE;
        shrinkageReason = shrinkageFailure + " ⇒ 损耗差异不可计算";
      } else if (shrinkageQty == null || shrinkageQty.signum() <= 0) {
        // 「本期没有损耗」是**事实**（0），不是「算不出来」—— 两者必须分开。
        shrinkageComparability = Comparability.COMPARABLE;
        shrinkageQty = BigDecimal.ZERO.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
        shrinkageVariance = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        shrinkageReason = "该 SKU 本期没有负向调整流水 ⇒ 无损耗";
      } else {
        var valuationUnit =
            coveredQty != null && coveredQty.signum() > 0
                ? actualCost.divide(coveredQty, MONEY_SCALE, RoundingMode.HALF_UP)
                : unitCost;
        if (valuationUnit == null) {
          shrinkageComparability = Comparability.UPSTREAM_UNAVAILABLE;
          shrinkageReason =
              "取不到该 SKU 的单位成本（既无结转单价也无标准成本）⇒ 损耗无法折成金额，不按 0 算";
        } else {
          shrinkageComparability = Comparability.COMPARABLE;
          shrinkageVariance = shrinkageQty.multiply(valuationUnit).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
          shrinkageReason =
              "损耗数量 " + shrinkageQty.toPlainString() + " × 单位成本 " + valuationUnit.toPlainString();
        }
      }
      itemRows.add(
          new FinanceCostVarianceItemRow(
              null, null, skuId, skuCode, currency, VarianceDriver.SHRINKAGE.name(),
              VarianceDriver.SHRINKAGE.label(), shrinkageVariance, shrinkageQty, null, null, null,
              null, null, null, shrinkageComparability.name(), shrinkageComparability.label(),
              shrinkageReason,
              "损耗数量来自 warehouse 的**负向调整流水**（数量账在 warehouse，本模块只读）。"));

      // ---- 9.5 逐 (SKU × 币种) 的驱动因素汇总 ----
      accumulate(driverAccumulator, currency, VarianceDriver.STANDARD_COST, totalVariance, null,
          actualCost, standardCost, issueQty, standardComparability, standardReason,
          "实际成本 − 标准成本（同一币种内相减，绝不跨币种）。");
      accumulate(driverAccumulator, currency, VarianceDriver.PURCHASE_PRICE, purchaseVariance, null,
          null, null, null, purchaseComparability, purchaseReason,
          "（本期加权平均采购单价 − 上期加权平均采购单价）× 结转数量。");
      accumulate(driverAccumulator, currency, VarianceDriver.SHRINKAGE, shrinkageVariance,
          shrinkageQty, null, null, null, shrinkageComparability, shrinkageReason,
          "损耗数量 × 单位成本。");
      accumulate(driverAccumulator, currency, VarianceDriver.FX, fxImpact, null, null, null, null,
          fxComparability, fxReason, "实际成本 × (1 − 期初汇率 ÷ 期末汇率)。");

      // ---- 9.6 异常提示 ----
      if (standardComparability == Comparability.NO_BASELINE) {
        alerts.add(
            alert("NO_BASELINE", AlertSeverity.WARNING, currency, skuId, skuCode, null,
                "该 SKU 在本期间没有生效的"
                    + (baselineType == BaselineType.BUDGET ? "预算" : "标准成本")
                    + "基线 ⇒ 它的成本差异**不可计算**。请先登记基线。"));
      }
      if (standardComparability == Comparability.CURRENCY_MISMATCH) {
        alerts.add(
            alert("CURRENCY_MISMATCH", AlertSeverity.WARNING, currency, skuId, skuCode, null,
                "基线币种与实际成本币种不一致 ⇒ 差异不可计算（绝不跨币种相减）。"));
      }
      if (shortfallQty != null && shortfallQty.signum() > 0) {
        alerts.add(
            alert("COST_SHORTFALL", AlertSeverity.WARNING, currency, skuId, skuCode, actualCost,
                "该 SKU 有 " + shortfallQty.toPlainString()
                    + " 的结转缺口（库存不足，12.4.1.2 的 INCOMPLETE 分录）⇒ "
                    + "实际成本与标准成本的比较建立在不完整的基数上。"));
      }
      if (incomplete > 0) {
        alerts.add(
            alert("INCOMPLETE_COST_ENTRY", AlertSeverity.CRITICAL, currency, skuId, skuCode, null,
                "该 SKU 有 " + incomplete + " 条**未结转完整**的成本分录（12.4.1.2）"
                    + " ⇒ 实际成本偏低，差异结论受影响。"));
      }
      if (standardComparability == Comparability.COMPARABLE
          && standardCost != null
          && standardCost.signum() != 0
          && totalVariance != null
          && totalVariance.abs().divide(standardCost.abs(), 6, RoundingMode.HALF_UP)
              .compareTo(VARIANCE_ALERT_RATE) >= 0) {
        alerts.add(
            alert("LARGE_VARIANCE", AlertSeverity.INFO, currency, skuId, skuCode,
                totalVariance.abs(),
                "该 SKU 的成本差异 " + totalVariance.toPlainString() + " " + currency
                    + "，相对标准成本超过 "
                    + VARIANCE_ALERT_RATE.multiply(new BigDecimal("100")).toPlainString()
                    + "% ⇒ 值得看一眼。"));
      }
    }

    // ---- 10. 头程成本波动（单据级，只进按币种的汇总）----
    for (var currency : currencies) {
      var current = firstMileCurrent.get(currency);
      var previous = firstMilePrevious.get(currency);
      BigDecimal amount = null;
      Comparability comparability;
      String reason;
      if (firstMileFailure != null) {
        comparability = Comparability.UPSTREAM_UNAVAILABLE;
        reason = firstMileFailure + " ⇒ 头程成本波动不可计算";
      } else if (current == null || previous == null) {
        comparability = Comparability.UPSTREAM_UNAVAILABLE;
        reason = "上游没有该币种在本期或上期的头程发运单事实 ⇒ 头程成本波动不可计算";
      } else {
        comparability = Comparability.COMPARABLE;
        amount = current.subtract(previous).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        reason =
            "本期头程成本 " + current.toPlainString() + " − 上期 " + previous.toPlainString()
                + "（运费 + 保险费 + 处理费，按头程发运单汇总）。";
      }
      accumulate(driverAccumulator, currency, VarianceDriver.FIRST_MILE, amount, null, null, null,
          null, comparability, reason,
          "头程发运单按币种汇总，与上一等长期间对比（单据级，不按 SKU）。");
      if (comparability == Comparability.UPSTREAM_UNAVAILABLE) {
        alerts.add(
            alert("FIRST_MILE_UNAVAILABLE", AlertSeverity.WARNING, currency, null, "", null,
                "读不到头程发运单事实（warehouse 10.4.1.1）⇒ 该币种的**头程成本波动不可计算**。"));
      }
    }

    // ---- 11. 上游不可得的告警（整类）----
    if (purchaseFailure != null) {
      alerts.add(
          alert("PURCHASE_PRICE_UNAVAILABLE", AlertSeverity.WARNING, null, null, "", null,
              "读不到采购成本事实（trade 6 采购）⇒ **采购价波动整类不可计算**，"
                  + "本次归因里它不参与排名。"));
    }
    if (shrinkageFailure != null) {
      alerts.add(
          alert("SHRINKAGE_UNAVAILABLE", AlertSeverity.WARNING, null, null, "", null,
              "读不到库存流水（warehouse 7.1.2.1）⇒ **损耗差异整类不可计算**，"
                  + "本次归因里它不参与排名。"));
    }
    for (var failure : rateFailure.values()) {
      alerts.add(
          alert("FX_RATE_UNAVAILABLE", AlertSeverity.WARNING, null, null, "", null,
              failure + " ⇒ 对应币种的**汇率影响不可计算**（绝不按 1:1 兜底）。"));
    }
    if (categoryMapFailure != null) {
      alerts.add(
          alert("SKU_CATEGORY_UNAVAILABLE", AlertSeverity.WARNING, null, null, "", null,
              categoryMapFailure + " ⇒ **类目维度的基线找不到**，相关差异不可计算。"));
    }

    // ---- 12. 归因：按币种排名，主因 = rank 1 且可计算且金额非零 ----
    for (var currency : currencies) {
      var comparable =
          driverAccumulator.values().stream()
              .filter(a -> a.currency.equals(currency))
              .filter(a -> a.comparableCount > 0)
              .sorted(Comparator.comparing((DriverAccumulator a) -> a.amount.abs()).reversed())
              .toList();
      var totalAbs =
          comparable.stream().map(a -> a.amount.abs()).reduce(BigDecimal.ZERO, BigDecimal::add);
      int rank = 0;
      for (var acc : comparable) {
        rank++;
        acc.rank = rank;
        acc.share =
            totalAbs.signum() == 0 ? null : acc.amount.abs().divide(totalAbs, 6, RoundingMode.HALF_UP);
        // 🔴 主因 = 贡献第一**且金额非零**；全部为 0 时没有主因（不硬凑一个）。
        acc.primary = rank == 1 && acc.amount.signum() != 0;
      }
    }
    var driverRows = new ArrayList<FinanceCostVarianceDriverRow>();
    for (var acc : driverAccumulator.values()) {
      driverRows.add(
          new FinanceCostVarianceDriverRow(
              null, null, acc.currency, acc.driver.name(), acc.driver.label(),
              acc.comparableCount > 0 ? acc.amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP) : null,
              acc.quantity, acc.actualCost, acc.standardCost, acc.actualQuantity, acc.standardQuantity,
              acc.groupComparability().name(), acc.groupComparability().label(),
              acc.groupReason(), acc.rank, acc.primary, acc.share, acc.basis));
    }
    driverRows.sort(
        Comparator.comparing((FinanceCostVarianceDriverRow r) -> r.currency())
            .thenComparing(r -> r.rank() == null ? Integer.MAX_VALUE : r.rank()));

    // ---- 13. 落库 ----
    var reportId = UUID.randomUUID();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var scopeCode = request.scopeCode() == null ? "" : request.scopeCode().trim();
    var scopeName = request.scopeName() == null ? "" : request.scopeName().trim();
    var runIdsText =
        runList.stream()
            .map(r -> r.get("id", UUID.class).toString())
            .reduce((a, b) -> a + "," + b)
            .orElse("");
    var valuationMethod = runList.get(0).get("valuation_method", String.class);
    var valuationMethodVersion = runList.get(0).get("valuation_method_version", Integer.class);
    if (valuationMethodVersion == null) valuationMethodVersion = 0;
    var currencyText = String.join(",", currencies);
    var skuCount = (int) entries.stream().map(r -> r.get("sku_id", UUID.class)).distinct().count();
    var noBaselineCount = noBaselineKeys.size();
    var comparableCount = comparableSkuKeys.size();
    var partial =
        noBaselineCount > 0
            || driverRows.stream().anyMatch(d -> d.comparability().equals("UPSTREAM_UNAVAILABLE"));
    var status = partial ? "PARTIAL" : "COMPLETED";
    var basisNote =
        "基数 = 12.4.1.2 的 " + runList.size() + " 个批次（" + runIdsText
            + "）里已结转的成本分录；计价方法取**批次行上的快照**（" + valuationMethod + " v"
            + valuationMethodVersion + "），不是「当前生效版本」。"
            + "基线 = " + (baselineType == BaselineType.BUDGET ? "预算" : "标准成本") + "（按 "
            + dimension.label() + " 维度，取期间起点 " + periodFromDate + " 生效的那一版）。"
            + "汇率影响用期初（" + periodFromDate + "）与期末（" + periodToDate
            + "）两档真实汇率隔离，按**本币口径**表达。";
    var sourceNote =
        "本次分析的可得性："
            + (purchaseFailure == null ? "采购价可读；" : "采购价**读不到**；")
            + (firstMileFailure == null ? "头程成本可读；" : "头程成本**读不到**；")
            + (shrinkageFailure == null ? "损耗流水可读；" : "损耗流水**读不到**；")
            + (rateFailure.isEmpty() ? "汇率可读。" : "汇率**读不到**（" + rateFailure.size() + " 个币种对）。");

    sql.execute(
        "insert into finance_cost_variance_report(id,tenant_id,scope_type,scope_ref_id,scope_code,"
            + " scope_name,period_from,period_to,reporting_currency,status,cost_run_ids,"
            + " valuation_method,valuation_method_version,basis_note,source_note,run_count,sku_count,"
            + " item_count,comparable_sku_count,no_baseline_sku_count,alert_count,currencies,"
            + " created_by,created_at)"
            + " values(?,?,?,?::uuid,?,?,?::timestamptz,?::timestamptz,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz)",
        reportId,
        currentTenant(),
        scopeType,
        request.scopeRefId(),
        scopeCode,
        scopeName,
        request.periodFrom(),
        request.periodTo(),
        reportingCurrency,
        status,
        runIdsText,
        valuationMethod,
        valuationMethodVersion,
        basisNote,
        sourceNote,
        runList.size(),
        skuCount,
        itemRows.size(),
        comparableCount,
        noBaselineCount,
        alerts.size(),
        currencyText,
        actor == null ? null : actor.userId(),
        now);

    var itemInserts = new ArrayList<Object[]>();
    for (var item : itemRows) {
      itemInserts.add(
          new Object[] {
            UUID.randomUUID(), currentTenant(), reportId, item.skuId(), item.skuCode(),
            item.currency(), item.driver(), item.amount(), item.quantity(), item.actualQuantity(),
            item.actualCostAmount(), item.standardCostAmount(), item.baselineUnitCost(),
            item.baselineId(), item.baselineVersion(), item.comparability(),
            item.comparableReason(), item.note(), now
          });
    }
    batchInsertItems(itemInserts);

    var driverInserts = new ArrayList<Object[]>();
    for (var driver : driverRows) {
      driverInserts.add(
          new Object[] {
            UUID.randomUUID(), currentTenant(), reportId, driver.currency(), driver.driver(),
            driver.amount(), driver.quantity(), driver.actualCostAmount(),
            driver.standardCostAmount(), driver.actualQuantity(), driver.standardQuantity(),
            driver.comparability(), driver.comparableReason(), driver.rank(), driver.isPrimary(),
            driver.contributionShare(), driver.basis(), now
          });
    }
    batchInsertDrivers(driverInserts);

    var alertInserts = new ArrayList<Object[]>();
    for (var a : alerts) {
      alertInserts.add(
          new Object[] {
            UUID.randomUUID(), currentTenant(), reportId, a.code(), a.severity(), a.currency(),
            a.skuId(), a.skuCode(), a.amount(), a.message(), now
          });
    }
    batchInsertAlerts(alertInserts);

    return reportDetail(reportId);
  }

  // ================================================================ 报表查询

  /** 报表列表（真分页 + 真筛选）。 */
  public FinanceCostVarianceReportPage reports(
      String scopeType, UUID scopeRefId, String status, int page, int size) {
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    if (scopeType != null && !scopeType.isBlank()) {
      where.add("scope_type = ?");
      binds.add(scopeType.trim().toUpperCase(Locale.ROOT));
    }
    if (scopeRefId != null) {
      where.add("scope_ref_id = ?::uuid");
      binds.add(scopeRefId);
    }
    if (status != null && !status.isBlank()) {
      where.add("status = ?");
      binds.add(status.trim().toUpperCase(Locale.ROOT));
    }
    var clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from finance_cost_variance_report" + clause, binds.toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
            "select id,scope_type,scope_ref_id,scope_code,scope_name,period_from,period_to,"
                + " reporting_currency,status,cost_run_ids,valuation_method,valuation_method_version,"
                + " basis_note,source_note,run_count,sku_count,item_count,comparable_sku_count,"
                + " no_baseline_sku_count,alert_count,currencies,created_by,created_at"
                + " from finance_cost_variance_report" + clause
                + " order by period_from desc, created_at desc, id limit ? offset ?",
            concat(binds, Math.min(Math.max(size, 1), MAX_PAGE_SIZE), Math.max(0, page) * size));
    return new FinanceCostVarianceReportPage(
        rows.stream().map(this::reportRow).toList(),
        Math.max(0, page),
        Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
        total);
  }

  /** 报表明细（表头 + 按币种分组 + 每组的驱动因素与告警）。 */
  public FinanceCostVarianceReportDetail reportDetail(UUID id) {
    var report = report(id);
    var driverRows = drivers(id, null, null, 0, MAX_PAGE_SIZE).items();
    var alertRows = alerts(id, null, null, 0, MAX_PAGE_SIZE).items();
    var groups = new ArrayList<FinanceCostVarianceCurrencyGroup>();
    var currencies = new ArrayList<String>();
    for (var d : driverRows) {
      if (!currencies.contains(d.currency())) currencies.add(d.currency());
    }
    for (var currency : currencies) {
      var groupDrivers = driverRows.stream().filter(d -> d.currency().equals(currency)).toList();
      var groupAlerts = alertRows.stream().filter(a -> currency.equals(a.currency())).toList();
      var standard =
          groupDrivers.stream()
              .filter(d -> d.driver().equals(VarianceDriver.STANDARD_COST.name()))
              .findFirst()
              .orElse(null);
      var fx =
          groupDrivers.stream().filter(d -> d.driver().equals(VarianceDriver.FX.name()))
              .findFirst()
              .orElse(null);
      var primary = groupDrivers.stream().filter(d -> d.isPrimary()).findFirst().orElse(null);
      var comparableCount =
          countDistinctSkus(id, currency, "COMPARABLE");
      var noBaselineCount =
          countDistinctSkus(id, currency, "NO_BASELINE");
      var skuCount =
          (int)
              sql.fetchOne(
                      "select count(distinct sku_id) from finance_cost_variance_item"
                          + " where report_id = ?::uuid and currency = ?",
                      id, currency)
                  .get(0, Long.class)
                  .intValue();
      groups.add(
          new FinanceCostVarianceCurrencyGroup(
              currency,
              standard == null ? null : standard.actualCostAmount(),
              standard == null ? null : standard.standardCostAmount(),
              standard == null ? null : standard.amount(),
              fx == null ? null : fx.amount(),
              standard == null ? null : standard.actualQuantity(),
              comparableCount,
              noBaselineCount,
              skuCount,
              primary == null ? null : primary.driver(),
              primary == null ? null : primary.driverLabel(),
              primary == null ? null : primary.amount(),
              groupDrivers,
              groupAlerts,
              "🔴 本组金额**只在 " + currency + " 内相加** —— 绝不跨币种相加。"
                  + (noBaselineCount > 0
                      ? " 其中 " + noBaselineCount + " 个 SKU 无基线，**不计入总差异**（不可比 ≠ 差异为 0）。"
                      : "")));
    }
    return new FinanceCostVarianceReportDetail(
        report,
        groups,
        report.itemCount(),
        report.alertCount(),
        "本报表的**基数是 12.4.1.2 的真实结转金额**（本模块不重算成本）；"
            + "五类差异**不可相加** —— 汇率影响是总差异的构成部分，"
            + "采购价 / 头程 / 损耗是独立观察到的驱动因素；"
            + "不可算的类别**不参与排名**。");
  }

  /** 明细列表（真分页 + 真筛选）。 */
  public FinanceCostVarianceItemPage items(
      UUID reportId, UUID skuId, String driver, String currency, String comparability, int page, int size) {
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    if (reportId != null) {
      where.add("report_id = ?::uuid");
      binds.add(reportId);
    }
    if (skuId != null) {
      where.add("sku_id = ?::uuid");
      binds.add(skuId);
    }
    if (driver != null && !driver.isBlank()) {
      where.add("driver = ?");
      binds.add(driver.trim().toUpperCase(Locale.ROOT));
    }
    if (currency != null && !currency.isBlank()) {
      where.add("currency = ?");
      binds.add(currency.trim().toUpperCase(Locale.ROOT));
    }
    if (comparability != null && !comparability.isBlank()) {
      where.add("comparability = ?");
      binds.add(comparability.trim().toUpperCase(Locale.ROOT));
    }
    var clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from finance_cost_variance_item" + clause, binds.toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
            "select id,report_id,sku_id,sku_code,currency,driver,amount,quantity,actual_quantity,"
                + " actual_cost_amount,standard_cost_amount,baseline_unit_cost,baseline_id,"
                + " baseline_version,comparability,comparable_reason,note"
                + " from finance_cost_variance_item" + clause
                + " order by sku_code, currency, driver limit ? offset ?",
            concat(binds, Math.min(Math.max(size, 1), MAX_PAGE_SIZE), Math.max(0, page) * size));
    return new FinanceCostVarianceItemPage(
        rows.stream().map(this::itemRow).toList(),
        Math.max(0, page),
        Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
        total);
  }

  /** 驱动因素列表（真分页 + 真筛选）—— 按币种分组 + 主因归因。 */
  public FinanceCostVarianceDriverPage drivers(
      UUID reportId, String currency, String driver, int page, int size) {
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    if (reportId != null) {
      where.add("report_id = ?::uuid");
      binds.add(reportId);
    }
    if (currency != null && !currency.isBlank()) {
      where.add("currency = ?");
      binds.add(currency.trim().toUpperCase(Locale.ROOT));
    }
    if (driver != null && !driver.isBlank()) {
      where.add("driver = ?");
      binds.add(driver.trim().toUpperCase(Locale.ROOT));
    }
    var clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from finance_cost_variance_driver" + clause, binds.toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
            "select id,report_id,currency,driver,amount,quantity,actual_cost_amount,"
                + " standard_cost_amount,actual_quantity,standard_quantity,comparability,"
                + " comparable_reason,rank,is_primary,contribution_share,basis"
                + " from finance_cost_variance_driver" + clause
                + " order by currency, rank nulls last, driver limit ? offset ?",
            concat(binds, Math.min(Math.max(size, 1), MAX_PAGE_SIZE), Math.max(0, page) * size));
    return new FinanceCostVarianceDriverPage(
        rows.stream().map(this::driverRow).toList(),
        Math.max(0, page),
        Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
        total);
  }

  /** 异常提示列表（真分页 + 真筛选）。 */
  public FinanceCostVarianceAlertPage alerts(
      UUID reportId, String code, String severity, int page, int size) {
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    if (reportId != null) {
      where.add("report_id = ?::uuid");
      binds.add(reportId);
    }
    if (code != null && !code.isBlank()) {
      where.add("code = ?");
      binds.add(code.trim().toUpperCase(Locale.ROOT));
    }
    if (severity != null && !severity.isBlank()) {
      where.add("severity = ?");
      binds.add(severity.trim().toUpperCase(Locale.ROOT));
    }
    var clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from finance_cost_variance_alert" + clause, binds.toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
            "select id,report_id,code,severity,currency,sku_id,sku_code,amount,message,created_at"
                + " from finance_cost_variance_alert" + clause
                + " order by severity, created_at, id limit ? offset ?",
            concat(binds, Math.min(Math.max(size, 1), MAX_PAGE_SIZE), Math.max(0, page) * size));
    return new FinanceCostVarianceAlertPage(
        rows.stream().map(this::alertRow).toList(),
        Math.max(0, page),
        Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
        total);
  }

  // ================================================================ 内部工具

  /** 当前租户（RLS 上下文里取，与同服务其它模块同构）。 */
  private UUID currentTenant() {
    var row =
        sql.fetchOne("select nullif(current_setting('app.current_tenant', true), '')::uuid as t");
    var tenant = row == null ? null : row.get("t", UUID.class);
    if (tenant == null) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "缺少租户上下文：差异分析必须在可信租户事务里执行");
    }
    return tenant;
  }

  private int countDistinctSkus(UUID reportId, String currency, String comparability) {
    return (int)
        sql.fetchOne(
                "select count(distinct sku_id) from finance_cost_variance_item"
                    + " where report_id = ?::uuid and currency = ? and driver = ? and comparability = ?",
                reportId, currency, VarianceDriver.STANDARD_COST.name(), comparability)
            .get(0, Long.class)
            .intValue();
  }

  private FinanceCostVarianceBaselineRow baselineRow(Record row) {
    var version = row.get("version", Integer.class);
    return new FinanceCostVarianceBaselineRow(
        row.get("id", UUID.class),
        row.get("baseline_type", String.class),
        row.get("dimension", String.class),
        row.get("dimension_ref_id", UUID.class),
        row.get("dimension_code", String.class),
        row.get("dimension_name", String.class),
        row.get("currency", String.class),
        row.get("unit_cost", BigDecimal.class),
        row.get("effective_from", OffsetDateTime.class),
        row.get("effective_to", OffsetDateTime.class),
        row.get("status", String.class),
        version == null ? 0 : version,
        row.get("supersedes_id", UUID.class),
        row.get("note", String.class),
        row.get("created_by", UUID.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  private FinanceCostVarianceBaselineRow baseline(UUID id) {
    var row =
        sql.fetch(
                "select id,baseline_type,dimension,dimension_key,dimension_ref_id,dimension_code,"
                    + " dimension_name,currency,unit_cost,effective_from,effective_to,status,version,"
                    + " supersedes_id,note,created_by,created_at,updated_at"
                    + " from finance_cost_variance_baseline where id = ?::uuid",
                id)
            .stream()
            .findFirst()
            .orElse(null);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "基线不存在");
    }
    return baselineRow(row);
  }

  private FinanceCostVarianceBaselineRow baselineVersion(
      String type, String dimension, String dimensionKey, String currency, int version) {
    var row =
        sql.fetch(
                "select id,baseline_type,dimension,dimension_key,dimension_ref_id,dimension_code,"
                    + " dimension_name,currency,unit_cost,effective_from,effective_to,status,version,"
                    + " supersedes_id,note,created_by,created_at,updated_at"
                    + " from finance_cost_variance_baseline"
                    + " where baseline_type = ? and dimension = ? and dimension_key = ? and currency = ?"
                    + " and version = ?",
                type, dimension, dimensionKey, currency, version)
            .stream()
            .findFirst()
            .orElse(null);
    return row == null ? null : baselineRow(row);
  }

  /**
   * 载入「在给定时刻生效」的基线（按链键分组）。
   *
   * <p>🔴 **按生效区间取，不是按 {@code status='ACTIVE'} 取** —— 那只是「当前」那一版，
   * 分析历史期间要的是**当时生效**的那一版。
   *
   * <p>返回的 Map：链键 → 该链键下**各币种**在时刻生效的版本（可能一个都没有）。
   */
  private Map<String, List<FinanceCostVarianceBaselineRow>> loadEffectiveBaselines(
      String baselineType, OffsetDateTime at) {
    var rows =
        sql.fetch(
            "select id,baseline_type,dimension,dimension_key,dimension_ref_id,dimension_code,"
                + " dimension_name,currency,unit_cost,effective_from,effective_to,status,version,"
                + " supersedes_id,note,created_by,created_at,updated_at"
                + " from finance_cost_variance_baseline where baseline_type = ?"
                + " order by dimension_key, currency, version desc",
            baselineType);
    var result = new LinkedHashMap<String, List<FinanceCostVarianceBaselineRow>>();
    for (var row : rows) {
      var from = row.get("effective_from", OffsetDateTime.class);
      var to = row.get("effective_to", OffsetDateTime.class);
      if (from == null || at.isBefore(from)) continue;
      if (to != null && !at.isBefore(to)) continue;
      var chainKey = row.get("dimension_key", String.class);
      var currency = row.get("currency", String.class);
      var chain = result.computeIfAbsent(chainKey, k -> new ArrayList<>());
      // 同一链键 + 同一币种只保留版本号最大的那一版（版本降序 ⇒ 先到的是最新）。
      boolean already = false;
      for (var existing : chain) {
        if (existing.currency().equals(currency)) {
          already = true;
          break;
        }
      }
      if (!already) chain.add(baselineRow(row));
    }
    return result;
  }

  /** SKU → 类目编码（catalog 3.1.2.1，只读）。 */
  private Map<UUID, String> loadSkuCategories(String bearer) {
    var result = new LinkedHashMap<UUID, String>();
    for (int page = 0; page < MAX_UPSTREAM_PAGES; page++) {
      var p = catalogSkus.skus(page, bearer);
      if (p == null || p.items() == null) break;
      for (var row : p.items()) {
        if (row == null || row.id() == null) continue;
        if (row.category() == null || row.category().isBlank()) continue;
        result.put(row.id(), row.category().trim());
      }
      if (p.items().size() < 200) break;
    }
    return result;
  }

  private FinanceCostVarianceReportRow reportRow(Record row) {
    var version = row.get("valuation_method_version", Integer.class);
    return new FinanceCostVarianceReportRow(
        row.get("id", UUID.class),
        row.get("scope_type", String.class),
        row.get("scope_ref_id", UUID.class),
        row.get("scope_code", String.class),
        row.get("scope_name", String.class),
        row.get("period_from", OffsetDateTime.class),
        row.get("period_to", OffsetDateTime.class),
        row.get("reporting_currency", String.class),
        row.get("status", String.class),
        row.get("cost_run_ids", String.class),
        row.get("valuation_method", String.class),
        version == null ? 0 : version,
        row.get("basis_note", String.class),
        row.get("source_note", String.class),
        intOrZero(row.get("run_count", Integer.class)),
        intOrZero(row.get("sku_count", Integer.class)),
        intOrZero(row.get("item_count", Integer.class)),
        intOrZero(row.get("comparable_sku_count", Integer.class)),
        intOrZero(row.get("no_baseline_sku_count", Integer.class)),
        intOrZero(row.get("alert_count", Integer.class)),
        row.get("currencies", String.class),
        row.get("created_by", UUID.class),
        row.get("created_at", OffsetDateTime.class));
  }

  private FinanceCostVarianceReportRow report(UUID id) {
    var row =
        sql.fetch(
                "select id,scope_type,scope_ref_id,scope_code,scope_name,period_from,period_to,"
                    + " reporting_currency,status,cost_run_ids,valuation_method,"
                    + " valuation_method_version,basis_note,source_note,run_count,sku_count,"
                    + " item_count,comparable_sku_count,no_baseline_sku_count,alert_count,currencies,"
                    + " created_by,created_at from finance_cost_variance_report where id = ?::uuid",
                id)
            .stream()
            .findFirst()
            .orElse(null);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "差异分析报表不存在");
    }
    return reportRow(row);
  }

  private FinanceCostVarianceItemRow itemRow(Record row) {
    var driver = VarianceDriver.parse(row.get("driver", String.class));
    var comparability = Comparability.parse(row.get("comparability", String.class));
    var baselineVersion = row.get("baseline_version", Integer.class);
    return new FinanceCostVarianceItemRow(
        row.get("id", UUID.class),
        row.get("report_id", UUID.class),
        row.get("sku_id", UUID.class),
        row.get("sku_code", String.class),
        row.get("currency", String.class),
        row.get("driver", String.class),
        driver == null ? row.get("driver", String.class) : driver.label(),
        row.get("amount", BigDecimal.class),
        row.get("quantity", BigDecimal.class),
        row.get("actual_quantity", BigDecimal.class),
        row.get("actual_cost_amount", BigDecimal.class),
        row.get("standard_cost_amount", BigDecimal.class),
        row.get("baseline_unit_cost", BigDecimal.class),
        row.get("baseline_id", UUID.class),
        baselineVersion,
        row.get("comparability", String.class),
        comparability == null ? row.get("comparability", String.class) : comparability.label(),
        row.get("comparable_reason", String.class),
        row.get("note", String.class));
  }

  private FinanceCostVarianceDriverRow driverRow(Record row) {
    var driver = VarianceDriver.parse(row.get("driver", String.class));
    var comparability = Comparability.parse(row.get("comparability", String.class));
    var rank = row.get("rank", Integer.class);
    return new FinanceCostVarianceDriverRow(
        row.get("id", UUID.class),
        row.get("report_id", UUID.class),
        row.get("currency", String.class),
        row.get("driver", String.class),
        driver == null ? row.get("driver", String.class) : driver.label(),
        row.get("amount", BigDecimal.class),
        row.get("quantity", BigDecimal.class),
        row.get("actual_cost_amount", BigDecimal.class),
        row.get("standard_cost_amount", BigDecimal.class),
        row.get("actual_quantity", BigDecimal.class),
        row.get("standard_quantity", BigDecimal.class),
        row.get("comparability", String.class),
        comparability == null ? row.get("comparability", String.class) : comparability.label(),
        row.get("comparable_reason", String.class),
        rank,
        Boolean.TRUE.equals(row.get("is_primary", Boolean.class)),
        row.get("contribution_share", BigDecimal.class),
        row.get("basis", String.class));
  }

  private FinanceCostVarianceAlertRow alertRow(Record row) {
    var severity = AlertSeverity.parse(row.get("severity", String.class));
    return new FinanceCostVarianceAlertRow(
        row.get("id", UUID.class),
        row.get("report_id", UUID.class),
        row.get("code", String.class),
        row.get("severity", String.class),
        severity == null ? row.get("severity", String.class) : severity.label(),
        row.get("currency", String.class),
        row.get("sku_id", UUID.class),
        row.get("sku_code", String.class),
        row.get("amount", BigDecimal.class),
        row.get("message", String.class),
        row.get("created_at", OffsetDateTime.class));
  }

  private FinanceCostVarianceAlertRow alert(
      String code, AlertSeverity severity, String currency, UUID skuId, String skuCode,
      BigDecimal amount, String message) {
    return new FinanceCostVarianceAlertRow(
        null, null, code, severity.name(), severity.label(), currency, skuId, skuCode, amount,
        message, null);
  }

  /** 基线链键（SKU 维度 = SKU uuid；类目维度 = 'CATEGORY:<类目编码>'）。 */
  private String baselineChainKey(
      BaselineDimension dimension, UUID skuId, Map<UUID, String> skuCategories) {
    if (dimension == BaselineDimension.SKU) return skuId.toString();
    var category = skuCategories.get(skuId);
    return category == null ? null : "CATEGORY:" + category;
  }

  private String purchaseKey(UUID skuId, String currency) {
    return skuId + "|" + currency;
  }

  /** 汇率（读不到就返回 null —— 由调用方如实标「上游不可得」，绝不按 1:1 兜底）。 */
  private BigDecimal rate(String base, String quote, LocalDate asOf, String bearer) {
    try {
      return exchangeRates.latest(base, quote, asOf, bearer).rate();
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** 头程发运单按币种汇总（期间内）。 */
  private Map<String, BigDecimal> firstMileTotals(OffsetDateTime from, OffsetDateTime to, String bearer) {
    var totals = new LinkedHashMap<String, BigDecimal>();
    for (int page = 0; page < MAX_UPSTREAM_PAGES; page++) {
      var result = firstMile.shipments(page, bearer);
      if (result == null || result.rows() == null) break;
      for (var row : result.rows()) {
        if (row == null || row.dispatchedAt() == null) continue;
        if (row.dispatchedAt().isBefore(from) || !row.dispatchedAt().isBefore(to)) continue;
        var currency = row.currency();
        if (currency == null || currency.isBlank()) continue;
        var total = row.totalCost();
        if (total == null || total.signum() <= 0) continue;
        totals.merge(currency.trim().toUpperCase(Locale.ROOT), total, BigDecimal::add);
      }
      if (result.rows().size() < 200) break;
    }
    return totals;
  }

  /** 损耗数量（warehouse 调整流水的负向在手变动，按 SKU 汇总）。 */
  private Map<UUID, BigDecimal> shrinkageQuantities(
      OffsetDateTime from, OffsetDateTime to, String bearer) {
    var result = new LinkedHashMap<UUID, BigDecimal>();
    for (int page = 0; page < MAX_UPSTREAM_PAGES; page++) {
      var ledger = flows.ledger(from, to, List.of("ADJUSTMENT"), bearer, page, 200);
      if (ledger == null || ledger.rows() == null) break;
      for (var row : ledger.rows()) {
        if (row == null || row.transactionId() == null) continue;
        var entries = flows.drill(row.transactionId(), bearer);
        if (entries == null) continue;
        for (var entry : entries) {
          if (entry == null || entry.skuId() == null || entry.onHandDelta() == null) continue;
          if (entry.onHandDelta().signum() >= 0) continue; // 正向 = 补货，不是损耗
          result.merge(entry.skuId(), entry.onHandDelta().abs(), BigDecimal::add);
        }
      }
      long total = ledger.total() == null ? 0 : ledger.total();
      if ((long) (page + 1) * 200 >= total) break;
    }
    return result;
  }

  /**
   * 把一个驱动因素累加到按币种的汇总里。
   *
   * <p>🔴 **只有可比的行才累加金额** —— 不可比的行不进合计（否则「算不出来」会被
   * 悄悄当成 0 加进去）。组级可比性 = 「有可比行 ⇒ 可比；一条都没有 ⇒ 取最严重的
   * 不可比原因」。
   */
  private void accumulate(
      Map<String, DriverAccumulator> accumulator, String currency, VarianceDriver driver,
      BigDecimal amount, BigDecimal quantity, BigDecimal actualCost, BigDecimal standardCost,
      BigDecimal quantityBase, Comparability comparability, String reason, String basis) {
    var key = currency + "|" + driver.name();
    var acc = accumulator.get(key);
    if (acc == null) {
      acc = new DriverAccumulator(currency, driver);
      accumulator.put(key, acc);
    }
    if (comparability == Comparability.COMPARABLE) {
      acc.comparableCount++;
      if (amount != null) acc.amount = acc.amount.add(amount);
      if (quantity != null) acc.quantity = acc.quantity.add(quantity);
      if (actualCost != null) acc.actualCost = acc.actualCost.add(actualCost);
      if (standardCost != null) acc.standardCost = acc.standardCost.add(standardCost);
      if (quantityBase != null) {
        acc.actualQuantity = acc.actualQuantity.add(quantityBase);
        // 标准成本用的也是同一批数量（标准成本 = 标准单价 × 出库量）。
        acc.standardQuantity = acc.standardQuantity.add(quantityBase);
      }
      acc.reason = reason;
    } else {
      acc.notComparable.putIfAbsent(comparability, reason);
    }
    acc.basis = basis;
  }

  /**
   * 逐行写入报表明细。
   *
   * <p>⚠️ **用逐行 execute 而不是 jOOQ 的 batch**：batch 路径对 {@code ?::timestamptz}
   * 这类带 cast 的占位符解析与普通 execute 不一致（会静默一行都不写）。
   * 明细行数是「SKU × 币种 × 驱动因素」量级（几十到几百），逐行写完全够用。
   */
  private void batchInsertItems(List<Object[]> rows) {
    for (var row : rows) {
      sql.execute(
          "insert into finance_cost_variance_item(id,tenant_id,report_id,sku_id,sku_code,currency,driver,"
              + " amount,quantity,actual_quantity,actual_cost_amount,standard_cost_amount,"
              + " baseline_unit_cost,baseline_id,baseline_version,comparability,comparable_reason,note,"
              + " created_at)"
              + " values(?,?,?::uuid,?::uuid,?,?,?,?,?,?,?,?,?,?::uuid,?,?,?,?,?::timestamptz)"
              + " on conflict (tenant_id,report_id,sku_id,currency,driver) do nothing",
          row);
    }
  }

  /** 逐行写入按币种的驱动因素汇总（主因归因）。 */
  private void batchInsertDrivers(List<Object[]> rows) {
    for (var row : rows) {
      sql.execute(
          "insert into finance_cost_variance_driver(id,tenant_id,report_id,currency,driver,amount,quantity,"
              + " actual_cost_amount,standard_cost_amount,actual_quantity,standard_quantity,comparability,"
              + " comparable_reason,rank,is_primary,contribution_share,basis,created_at)"
              + " values(?,?,?::uuid,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz)"
              + " on conflict (tenant_id,report_id,currency,driver) do nothing",
          row);
    }
  }

  /** 逐行写入异常提示。 */
  private void batchInsertAlerts(List<Object[]> rows) {
    for (var row : rows) {
      sql.execute(
          "insert into finance_cost_variance_alert(id,tenant_id,report_id,code,severity,currency,sku_id,"
              + " sku_code,amount,message,created_at)"
              + " values(?,?,?::uuid,?,?,?,?::uuid,?,?,?,?::timestamptz)"
              + " on conflict do nothing",
          row);
    }
  }

  private static int intOrZero(Integer value) {
    return value == null ? 0 : value;
  }

  private static Object[] concat(List<Object> binds, Object... extra) {
    var all = new ArrayList<Object>(binds);
    all.addAll(java.util.Arrays.asList(extra));
    return all.toArray();
  }

  /** 按币种的驱动因素累加器（🔴 金额只在同一币种内相加）。 */
  private static final class DriverAccumulator {
    final String currency;
    final VarianceDriver driver;
    BigDecimal amount = BigDecimal.ZERO;
    BigDecimal quantity = BigDecimal.ZERO;
    BigDecimal actualCost = BigDecimal.ZERO;
    BigDecimal standardCost = BigDecimal.ZERO;
    BigDecimal actualQuantity = BigDecimal.ZERO;
    BigDecimal standardQuantity = BigDecimal.ZERO;
    int comparableCount;
    final Map<Comparability, String> notComparable = new LinkedHashMap<>();
    String reason = "";
    String basis = "";
    Integer rank;
    boolean primary;
    BigDecimal share;

    DriverAccumulator(String currency, VarianceDriver driver) {
      this.currency = currency;
      this.driver = driver;
    }

    /** 组级可比性：有可比行 ⇒ 可比；一条都没有 ⇒ 取最严重的不可比原因。 */
    Comparability groupComparability() {
      if (comparableCount > 0) return Comparability.COMPARABLE;
      for (var c : List.of(
          Comparability.UPSTREAM_UNAVAILABLE,
          Comparability.CURRENCY_MISMATCH,
          Comparability.NO_BASELINE,
          Comparability.NOT_APPLICABLE)) {
        if (notComparable.containsKey(c)) return c;
      }
      return Comparability.NOT_APPLICABLE;
    }

    /** 组级理由：可比时说清排除了多少条；不可比时给出原因。 */
    String groupReason() {
      if (comparableCount > 0) {
        var excluded = notComparable.size();
        return excluded == 0 ? "" : ("有 " + excluded + " 个 SKU 不可比，未计入合计。");
      }
      for (var c : List.of(
          Comparability.UPSTREAM_UNAVAILABLE,
          Comparability.CURRENCY_MISMATCH,
          Comparability.NO_BASELINE,
          Comparability.NOT_APPLICABLE)) {
        var reason = notComparable.get(c);
        if (reason != null) return reason;
      }
      return "";
    }
  }
}
