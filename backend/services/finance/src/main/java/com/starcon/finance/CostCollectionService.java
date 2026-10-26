package com.starcon.finance;

import static com.starcon.finance.CostCollectionModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 12.4.2.1 费用归集。
 *
 * <p>蓝图 12-finance.md：「归集各类费用到指定维度：按月分摊的平台费/订阅费、按单分摊的物流费、按 SKU 分摊的头程费、按期间分摊的人员与办公费用，<b>保留归集依据</b>。」输入：「费用来源（平台、物流、订阅、人工录入）；分摊目标维度。」输出：「归集明细（费用项 → 分摊对象 → 金额）。」关系：「上游 = 12.3.1.2、10 物流、14 广告；下游 = 12.4.2.2。<b>归集与分摊要分开</b>：先按规则归集费用池，再按维度分摊，便于核对与调整。」
 *
 * <p><b>🔴 本模块只做归集，不做分摊。</b>本服务里**没有任何一处**把费用按 SKU / 订单 / 期间拆开：
 *
 * <ul>
 *   <li>条目上只有「规则声明的目标维度」，没有「分摊到谁、摊了多少」；
 *   <li>数据层把「已分摊」钉死为写不出来（V351 的 {@code allocated_amount = 0} 与
 *       {@code allocation_status = 'UNALLOCATED'}）；
 *   <li>能力声明里 12.4.2.2 记为 available=false 并写明归属。
 * </ul>
 *
 * <p><b>上游没交付就说没交付，绝不造数。</b>
 *
 * <ul>
 *   <li>平台费 ← 12.3.1.2 的结算明细行（<b>同服务直读</b>，不走端口）；
 *   <li>广告费 ← 14.3.1.1 活动报表（channel 服务只读端口）；
 *   <li>物流费 ← 10.2.1.1 运单运费登记（trade 服务只读端口）；
 *   <li>头程费 ← 10.4.1.1 头程发运单（warehouse 服务只读端口）；
 *   <li>订阅费 / 人员费用 / 办公费用 ← <b>人工录入</b>（蓝图输入里点名的第四类来源）。
 * </ul>
 *
 * <p><b>时间字段</b>：{@code collected_at} / {@code created_at} / {@code updated_at} 是 {@code timestamptz}，
 * 一律 OffsetDateTime + {@code ?::timestamptz}；{@code base_date} / {@code occurred_on} 是 {@code date}
 * （日历日，不是某一瞬间），一律 {@code ?::date}。凡涉及「今天 / 本月」的判断一律用
 * <b>UTC 日历日</b>（{@code LocalDate.now(ZoneOffset.UTC)}），不用会话时区。
 */
@Service
public class CostCollectionService {

  /** 一页最大条数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 池标识 / 规则编码 / 上游引用的长度上限（与 DB 列宽一致）。 */
  static final int KEY_MAX = 200;

  /** 归集规则里「自然月」口径下的月度边界由 {@link #monthStart} / {@link #monthEnd} 给出。 */

  private final DSLContext sql;
  private final ObjectMapper json;
  private final WaybillFreightPort waybills;
  private final AdSpendPort adSpend;
  private final FirstMilePort firstMile;

  public CostCollectionService(
      DSLContext sql,
      ObjectMapper json,
      WaybillFreightPort waybills,
      AdSpendPort adSpend,
      FirstMilePort firstMile) {
    this.sql = sql;
    this.json = json;
    this.waybills = waybills;
    this.adSpend = adSpend;
    this.firstMile = firstMile;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：**每一维说清「有没有、依据是什么、缺了会怎样」**。
   *
   * <p>⚠️ 本模块的缺口必须分开说：12.4.2.2 分摊规则（下游，**本服务**的后续模块）与
   * 13 利润（**别的**服务）—— 把两者都写成「不可用」，运维会以为要去催分摊，
   * 而实际上分摊是本服务内部的事，利润才在别处。
   */
  public FinanceCostCollectionCapabilities capabilities() {
    var capabilities = new ArrayList<FinanceCostCollectionCapability>();
    var gaps = new ArrayList<FinanceCostCollectionDownstreamGap>();

    capabilities.add(
        new FinanceCostCollectionCapability(
            "12.4.2.1",
            "归集规则（费用来源 → 费用项 → 分摊目标维度 → 费用池）",
            true,
            "finance_cost_collection_rule + DB 层 CHECK 钉死四类口径配对",
            "",
            "租户自己维护、**系统不预置任何一行**（费用池怎么划分是租户的账务口径）。"
                + "四类口径的配对由表级 CHECK 钉死："
                + "按月（平台费 / 订阅费 / 广告费）、按单（物流费）、按 SKU（头程费）、按期间（人员与办公费用）"
                + "—— 「按 SKU 分摊的平台费」这种说不通的配对在 DB 层就进不来。"));

    capabilities.add(
        new FinanceCostCollectionCapability(
            "12.4.2.1",
            "费用池（按 池标识 × 维度 × 归属期间 × 币种 唯一）",
            true,
            "finance_cost_collection_pool + 条目触发器派生的合计与笔数",
            "",
            "池合计**由条目派生**（触发器），不靠应用层维护 —— "
                + "「池合计 ≠ Σ条目金额」正是财务最不能接受的错，而它正是下游分摊要拿来当被分摊总额的数。"));

    capabilities.add(
        new FinanceCostCollectionCapability(
            "12.4.2.1",
            "归集条目与归集依据",
            true,
            "finance_cost_collection_entry + finance_cost_collection_basis",
            "",
            "蓝图点名的「**保留归集依据**」落成一张独立的依据表：凭据号、原始金额、"
                + "上游分项（头程的运费 / 保险费 / 处理费三段）、凭据引用。"
                + "条目金额与依据金额不一致时差额能被看见 —— 那才谈得上核对。"));

    capabilities.add(
        new FinanceCostCollectionCapability(
            "12.3.1.2",
            "平台费来源（结算明细行）",
            true,
            "finance_settlement_line（**同服务直读**，不跨服务）",
            "",
            "归集佣金 / FBA 费用 / 仓储费 / 配送费四类平台费用行（作废与被取代的结算单不计）。"
                + "⚠️ **广告费不在这一档**：结算单里的 ADVERTISING 行与 14.3.1.1 活动报表是同一笔广告费的两种口径，"
                + "两个都归集会重复计费 ⇒ 广告费只走 AD_SPEND 口径。"));

    capabilities.add(
        new FinanceCostCollectionCapability(
            "14.3.1.1",
            "广告费来源（活动报表花费）",
            true,
            "channel 服务 /channel/ad-reports/rows（AdSpendPort 只读端口）",
            "",
            "取的是**花费（spend）**，不是销售额 —— 销售额是收入侧的事。"
                + "⚠️ 广告报表行是**活动级汇总、不带单一发生日期**，"
                + "所以归集基准日由操作员**显式给出**（要关账的那个月），不静默默认。"));

    capabilities.add(
        new FinanceCostCollectionCapability(
            "10.2.1.1",
            "物流费来源（运单运费登记）",
            true,
            "trade 服务 /trade/logistics/waybills/list（WaybillFreightPort 只读端口）",
            "",
            "粒度是**一票运单**（= 一个订单的包裹），正对应蓝图「按单分摊的物流费」。"
                + "⚠️ 只归集**已登记且金额大于 0** 的运费：「未登记」与「登记了 0 元」是两回事，"
                + "前者是缺口（要催），后者是合法事实。"));

    capabilities.add(
        new FinanceCostCollectionCapability(
            "10.4.1.1",
            "头程费来源（头程发运单）",
            true,
            "warehouse 服务 /warehouse/first-mile-shipments（FirstMilePort 只读端口）",
            "",
            "归集运费 + 保险费 + 处理费三段（**关税估算不算** —— 估算不是账单，且关税口径属 10.5.1.3）。"
                + "⚠️ 只归集**已发运**的发运单：没发出去的运费还是报价，不是已发生的成本。"));

    capabilities.add(
        new FinanceCostCollectionCapability(
            "12.4.2.2",
            "分摊规则与分摊结果",
            false,
            "本模块只产出**待分摊的费用池**，不产出分摊结果",
            "12.4.2.2",
            "🔴 **本模块不做分摊**：条目上没有分摊结果，分摊状态在数据层恒为 UNALLOCATED。"
                + "下游读 finance_cost_collection_pool / ..._entry 即可开始分摊，**不必再抄一份金额**。"));

    capabilities.add(
        new FinanceCostCollectionCapability(
            "13.1.1.1",
            "利润与经营分析",
            false,
            "reporting 服务不在本仓（编排闸 serviceBlocked）",
            "13 利润与经营分析",
            "费用池是它的一处上游输入；本模块**不产出任何利润数字**。"));

    gaps.add(
        new FinanceCostCollectionDownstreamGap(
            "12.4.2.2",
            "分摊规则",
            "finance",
            false,
            "12.4 费用分摊",
            "本模块只做归集；分摊算法、分摊基数、舍入差处理均未交付。"));

    gaps.add(
        new FinanceCostCollectionDownstreamGap(
            "13.1.1.1",
            "利润与经营分析",
            "analytics",
            false,
            "13 利润与经营分析",
            "费用池是它的一处上游输入；本模块不产出利润数字。"));

    return new FinanceCostCollectionCapabilities(List.copyOf(capabilities), List.copyOf(gaps));
  }

  // ================================================================ 归集规则

  /**
   * 蓝图标准绑定模板（<b>只读</b>，不落库）。
   *
   * <p>系统**不预置任何规则行**：费用池怎么划分是租户自己的账务口径。
   * 模板只是把蓝图那四类口径摊开，让界面能「按模板建规则」。
   */
  public List<FinanceCostCollectionRuleTemplate> ruleTemplates() {
    var configured = new LinkedHashSet<String>();
    for (var row :
        sql.fetch("select distinct fee_source from finance_cost_collection_rule order by fee_source")) {
      configured.add(row.get(0, String.class));
    }
    var templates = new ArrayList<FinanceCostCollectionRuleTemplate>();
    templates.add(
        new FinanceCostCollectionRuleTemplate(
            FeeSource.PLATFORM_SETTLEMENT,
            FeeCategory.PLATFORM_FEE,
            TargetDimension.MONTH,
            PeriodBasis.CALENDAR_MONTH,
            "PLATFORM_FEE",
            "平台费",
            "按月分摊的平台费：来源 12.3.1.2 结算明细行（佣金 / FBA 费用 / 仓储费 / 配送费）。",
            configured.contains(FeeSource.PLATFORM_SETTLEMENT.name())));
    templates.add(
        new FinanceCostCollectionRuleTemplate(
            FeeSource.AD_SPEND,
            FeeCategory.AD_FEE,
            TargetDimension.MONTH,
            PeriodBasis.CALENDAR_MONTH,
            "AD_FEE",
            "广告费",
            "按月分摊的广告费：来源 14.3.1.1 活动报表花费。"
                + "结算单里的 ADVERTISING 行不归这一档，避免同一笔钱进池两次。",
            configured.contains(FeeSource.AD_SPEND.name())));
    templates.add(
        new FinanceCostCollectionRuleTemplate(
            FeeSource.SUBSCRIPTION,
            FeeCategory.SUBSCRIPTION_FEE,
            TargetDimension.MONTH,
            PeriodBasis.CALENDAR_MONTH,
            "SUBSCRIPTION_FEE",
            "订阅费",
            "按月分摊的订阅费：来源人工录入（ERP / 仓储 / SaaS 等订阅账单）。",
            configured.contains(FeeSource.SUBSCRIPTION.name())));
    templates.add(
        new FinanceCostCollectionRuleTemplate(
            FeeSource.LOGISTICS_WAYBILL,
            FeeCategory.LOGISTICS_FEE,
            TargetDimension.ORDER,
            PeriodBasis.CALENDAR_MONTH,
            "LOGISTICS_FEE",
            "物流费",
            "按单分摊的物流费：来源 10.2.1.1 运单运费登记（一票运单 = 一个订单的包裹）。",
            configured.contains(FeeSource.LOGISTICS_WAYBILL.name())));
    templates.add(
        new FinanceCostCollectionRuleTemplate(
            FeeSource.HEAD_MILE_SHIPMENT,
            FeeCategory.HEAD_MILE_FEE,
            TargetDimension.SKU,
            PeriodBasis.CALENDAR_MONTH,
            "HEAD_MILE_FEE",
            "头程费",
            "按 SKU 分摊的头程费：来源 10.4.1.1 头程发运单。"
                + "本模块归集到**单票**发运单，SKU 级拆分是 12.4.2.2 的事。",
            configured.contains(FeeSource.HEAD_MILE_SHIPMENT.name())));
    templates.add(
        new FinanceCostCollectionRuleTemplate(
            FeeSource.PERSONNEL,
            FeeCategory.PERSONNEL_COST,
            TargetDimension.PERIOD,
            PeriodBasis.ACCOUNTING_PERIOD,
            "PERSONNEL_COST",
            "人员费用",
            "按期间分摊的人员费用：来源人工录入，归属期间由操作员显式给出（会计期间，不是自然月）。",
            configured.contains(FeeSource.PERSONNEL.name())));
    templates.add(
        new FinanceCostCollectionRuleTemplate(
            FeeSource.OFFICE,
            FeeCategory.OFFICE_COST,
            TargetDimension.PERIOD,
            PeriodBasis.ACCOUNTING_PERIOD,
            "OFFICE_COST",
            "办公费用",
            "按期间分摊的办公费用：来源人工录入，归属期间由操作员显式给出（会计期间，不是自然月）。",
            configured.contains(FeeSource.OFFICE.name())));
    return List.copyOf(templates);
  }

  /** 归集规则列表。 */
  public List<FinanceCostCollectionRule> rules() {
    return sql.fetch(
            "select * from finance_cost_collection_rule order by fee_source, effective_from desc, rule_code")
        .map(this::rule);
  }

  /** 新建归集规则。 */
  public FinanceCostCollectionRule createRule(FinanceCostCollectionRuleInput input) {
    validateRule(input, null);
    var id = UUID.randomUUID();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "insert into finance_cost_collection_rule(id,tenant_id,rule_code,rule_name,fee_source,"
            + " fee_category,target_dimension,period_basis,pool_key,pool_name,effective_from,"
            + " effective_to,status,note,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?::date,?::date,?,?,?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        input.ruleCode().trim(),
        input.ruleName().trim(),
        input.feeSource().name(),
        input.feeCategory().name(),
        input.targetDimension().name(),
        input.periodBasis().name(),
        input.poolKey().trim(),
        poolName(input),
        input.effectiveFrom(),
        input.effectiveTo(),
        (input.status() == null ? RuleStatus.ACTIVE : input.status()).name(),
        text(input.note()),
        now,
        now);
    return rule(id);
  }

  /** 修改归集规则（改口径会让历史条目与规则对不上 ⇒ 已有条目的规则不允许改口径）。 */
  public FinanceCostCollectionRule updateRule(UUID id, FinanceCostCollectionRuleInput input) {
    validateRule(input, id);
    var existing = rule(id);
    var entryCount =
        sql.fetchOne(
                "select count(*) from finance_cost_collection_entry where tenant_id = ? and rule_id = ?",
                id)
            .get(0, Long.class);
    if (entryCount > 0
        && (existing.feeSource() != input.feeSource()
            || existing.feeCategory() != input.feeCategory()
            || existing.targetDimension() != input.targetDimension()
            || existing.periodBasis() != input.periodBasis())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "这条规则下已经有 "
              + entryCount
              + " 条归集条目，改口径会让条目与规则对不上 —— 请新建一条规则，"
              + "或先删除已有条目再改");
    }
    sql.execute(
        "update finance_cost_collection_rule set rule_code = ?, rule_name = ?, fee_source = ?,"
            + " fee_category = ?, target_dimension = ?, period_basis = ?, pool_key = ?, pool_name = ?,"
            + " effective_from = ?::date, effective_to = ?::date, status = ?, note = ?,"
            + " updated_at = ?::timestamptz where id = ?",
        input.ruleCode().trim(),
        input.ruleName().trim(),
        input.feeSource().name(),
        input.feeCategory().name(),
        input.targetDimension().name(),
        input.periodBasis().name(),
        input.poolKey().trim(),
        poolName(input),
        input.effectiveFrom(),
        input.effectiveTo(),
        (input.status() == null ? RuleStatus.ACTIVE : input.status()).name(),
        text(input.note()),
        OffsetDateTime.now(ZoneOffset.UTC),
        id);
    return rule(id);
  }

  /**
   * 规则入参校验。
   *
   * <p>⚠️ 「费用来源 / 费用项 / 目标维度 / 期间口径」四元组是否合法由 DB 层 CHECK 钉死，
   * 这里提前拦一道是为了给出**能直接展示给人看**的错误，而不是一个约束名。
   */
  private void validateRule(FinanceCostCollectionRuleInput input, UUID selfId) {
    if (input.feeSource() == null
        || input.feeCategory() == null
        || input.targetDimension() == null
        || input.periodBasis() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "费用来源、费用项、目标维度与期间口径都必须给出");
    }
    var template =
        ruleTemplates().stream()
            .filter(t -> t.feeSource() == input.feeSource())
            .findFirst()
            .orElse(null);
    if (template == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的费用来源：" + input.feeSource());
    }
    if (template.feeCategory() != input.feeCategory()
        || template.targetDimension() != input.targetDimension()
        || template.periodBasis() != input.periodBasis()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "「"
              + input.feeSource().label()
              + "」按蓝图只能配成："
              + template.feeCategory().label()
              + " / "
              + template.targetDimension().label()
              + " / "
              + template.periodBasis().label()
              + "；收到的是："
              + input.feeCategory().label()
              + " / "
              + input.targetDimension().label()
              + " / "
              + input.periodBasis().label());
    }
    if (input.effectiveTo() != null && input.effectiveTo().isBefore(input.effectiveFrom())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "生效截止日不能早于生效起始日");
    }
    var duplicate =
        sql.fetchOne(
            "select count(*) from finance_cost_collection_rule where rule_code = ?"
                + (selfId == null ? "" : " and id <> ?"),
            selfId == null
                ? new Object[] {input.ruleCode().trim()}
                : new Object[] {input.ruleCode().trim(), selfId});
    if (duplicate != null && duplicate.get(0, Long.class) > 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "规则编码已存在：" + input.ruleCode());
    }
  }

  // ================================================================ 筛选项

  /** 筛选项下拉：全部来自**真实数据**（distinct 查询），不是写死的词表。 */
  public FinanceCostCollectionOptions options() {
    var sources =
        sql.fetch("select distinct fee_source from finance_cost_collection_rule order by fee_source")
            .map(r -> r.get(0, String.class));
    var categories =
        sql.fetch("select distinct fee_category from finance_cost_collection_rule order by fee_category")
            .map(r -> r.get(0, String.class));
    var dimensions =
        sql.fetch("select distinct dimension from finance_cost_collection_pool order by dimension")
            .map(r -> r.get(0, String.class));
    var bases =
        sql.fetch("select distinct period_basis from finance_cost_collection_rule order by period_basis")
            .map(r -> r.get(0, String.class));
    var currencies =
        sql.fetch("select distinct currency from finance_cost_collection_pool order by currency")
            .map(r -> r.get(0, String.class));
    var poolKeys =
        sql.fetch("select distinct pool_key from finance_cost_collection_pool order by pool_key")
            .map(r -> r.get(0, String.class));
    var pools =
        sql.fetch(
                "select id, pool_key, pool_name, dimension, period_start, period_end, currency,"
                    + " total_amount, entry_count from finance_cost_collection_pool"
                    + " order by period_start desc, pool_key")
            .map(
                r ->
                    new FinanceCostCollectionPoolOption(
                        r.get("id", UUID.class),
                        r.get("pool_key", String.class),
                        r.get("pool_name", String.class),
                        TargetDimension.valueOf(r.get("dimension", String.class)),
                        r.get("period_start", LocalDate.class),
                        r.get("period_end", LocalDate.class),
                        r.get("currency", String.class),
                        r.get("total_amount", BigDecimal.class),
                        r.get("entry_count", Integer.class)));
    var ruleOptions =
        sql.fetch(
                "select id, rule_code, rule_name, fee_source, fee_category, target_dimension,"
                    + " period_basis, pool_key, status from finance_cost_collection_rule"
                    + " order by fee_source, rule_code")
            .map(
                r ->
                    new FinanceCostCollectionRuleOption(
                        r.get("id", UUID.class),
                        r.get("rule_code", String.class),
                        r.get("rule_name", String.class),
                        FeeSource.valueOf(r.get("fee_source", String.class)),
                        FeeCategory.valueOf(r.get("fee_category", String.class)),
                        TargetDimension.valueOf(r.get("target_dimension", String.class)),
                        PeriodBasis.valueOf(r.get("period_basis", String.class)),
                        r.get("pool_key", String.class),
                        RuleStatus.valueOf(r.get("status", String.class))));
    return new FinanceCostCollectionOptions(
        sources, categories, dimensions, bases, currencies, poolKeys, pools, ruleOptions);
  }

  // ================================================================ 费用池

  /** 费用池列表：真分页 + 真筛选 + 汇总覆盖整个筛选结果集。 */
  public FinanceCostCollectionPoolPage pools(
      String poolKey, String dimension, String currency, LocalDate periodFrom, LocalDate periodTo) {
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    if (poolKey != null && !poolKey.isBlank()) {
      where.add("pool_key = ?");
      binds.add(poolKey.trim());
    }
    if (dimension != null && !dimension.isBlank()) {
      where.add("dimension = ?");
      binds.add(dimension.trim());
    }
    if (currency != null && !currency.isBlank()) {
      where.add("currency = ?");
      binds.add(currency.trim().toUpperCase(Locale.ROOT));
    }
    if (periodFrom != null) {
      where.add("period_end >= ?::date");
      binds.add(periodFrom);
    }
    if (periodTo != null) {
      where.add("period_start <= ?::date");
      binds.add(periodTo);
    }
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    int size = MAX_PAGE_SIZE;
    int page = 0;
    long total =
        sql.fetchOne("select count(*) from finance_cost_collection_pool" + clause, binds.toArray())
            .get(0, Long.class);
    var items =
        sql.fetch(
                "select * from finance_cost_collection_pool"
                    + clause
                    + " order by period_start desc, pool_key, currency limit ? offset ?",
                concat(binds, size, 0))
            .map(this::pool);
    return new FinanceCostCollectionPoolPage(items, page, size, total, poolTotals(clause, binds));
  }

  /** 费用池按币种的汇总（**绝不跨币种相加**）。 */
  private List<FinanceCostCollectionPoolCurrencyTotal> poolTotals(String clause, List<Object> binds) {
    return sql.fetch(
            "select currency, sum(total_amount) as amount, count(*) as pool_count,"
                + " sum(entry_count) as entry_count from finance_cost_collection_pool"
                + clause
                + " group by currency order by currency",
            binds.toArray())
        .map(
            r ->
                new FinanceCostCollectionPoolCurrencyTotal(
                    r.get("currency", String.class),
                    r.get("amount", BigDecimal.class),
                    r.get("pool_count", Integer.class),
                    r.get("entry_count", Integer.class) == null ? 0 : r.get("entry_count", Integer.class)));
  }

  /** 一个费用池的详情（池 + 它的条目）。 */
  public FinanceCostCollectionPoolDetail poolDetail(UUID id) {
    var pool = pool(id);
    var entries =
        sql.fetch(
                "select e.*, p.pool_key, p.pool_name, p.period_start, p.period_end, r.rule_code, r.rule_name"
                    + " from finance_cost_collection_entry e"
                    + " join finance_cost_collection_pool p on p.tenant_id = e.tenant_id and p.id = e.pool_id"
                    + " join finance_cost_collection_rule r on r.tenant_id = e.tenant_id and r.id = e.rule_id"
                    + " where e.pool_id = ? order by e.base_date desc, e.created_at desc, e.id",
                id)
            .map(this::entryWith);
    return new FinanceCostCollectionPoolDetail(pool, entries);
  }

  // ================================================================ 归集条目

  /** 归集明细列表：真分页 + 真筛选 + 真排序 + 汇总覆盖整个筛选结果集。 */
  public FinanceCostCollectionEntryPage entries(FinanceCostCollectionQuery query) {
    int p = Math.max(0, query.page());
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size()));
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    applyFilters(query, where, binds);
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    long total =
        sql.fetchOne(
                "select count(*) from finance_cost_collection_entry e"
                    + " join finance_cost_collection_pool p on p.tenant_id = e.tenant_id"
                    + " and p.id = e.pool_id"
                    + " join finance_cost_collection_rule r on r.tenant_id = e.tenant_id"
                    + " and r.id = e.rule_id"
                    + clause,
                binds.toArray())
            .get(0, Long.class);
    var items =
        sql.fetch(
                "select e.id, e.pool_id, e.rule_id, e.fee_source, e.fee_category,"
                    + " e.target_dimension, e.base_date, e.currency, e.amount, e.quantity,"
                    + " e.source_ref_type, e.source_ref_key, e.source_ref_code, e.source_ref_amount,"
                    + " e.allocated_amount, e.allocation_status, e.allocation_owner_module, e.note,"
                    + " e.collected_at, e.created_at, p.pool_key, p.pool_name, p.period_start,"
                    + " p.period_end, r.rule_code, r.rule_name"
                    + " from finance_cost_collection_entry e"
                    + " join finance_cost_collection_pool p on p.tenant_id = e.tenant_id"
                    + " and p.id = e.pool_id"
                    + " join finance_cost_collection_rule r on r.tenant_id = e.tenant_id"
                    + " and r.id = e.rule_id"
                    + clause
                    + " order by e.base_date desc, e.created_at desc, e.id limit ? offset ?",
                concat(binds, s, p * s))
            .map(r -> entryWith(r));

    return new FinanceCostCollectionEntryPage(
        items,
        p,
        s,
        total,
        entryTotalsByCurrency(query),
        entryTotalsByDimension(query));
  }

  private void applyFilters(
      FinanceCostCollectionQuery q, List<String> where, List<Object> binds) {
    if (q.poolId() != null) {
      where.add("e.pool_id = ?");
      binds.add(q.poolId());
    }
    if (q.ruleId() != null) {
      where.add("e.rule_id = ?");
      binds.add(q.ruleId());
    }
    if (q.feeSource() != null) {
      where.add("e.fee_source = ?");
      binds.add(q.feeSource().name());
    }
    if (q.feeCategory() != null) {
      where.add("e.fee_category = ?");
      binds.add(q.feeCategory().name());
    }
    if (q.targetDimension() != null) {
      where.add("e.target_dimension = ?");
      binds.add(q.targetDimension().name());
    }
    if (q.currency() != null && !q.currency().isBlank()) {
      where.add("e.currency = ?");
      binds.add(q.currency().trim().toUpperCase(Locale.ROOT));
    }
    if (q.poolKey() != null && !q.poolKey().isBlank()) {
      where.add("p.pool_key = ?");
      binds.add(q.poolKey().trim());
    }
    if (q.baseDateFrom() != null) {
      where.add("e.base_date >= ?::date");
      binds.add(q.baseDateFrom());
    }
    if (q.baseDateTo() != null) {
      where.add("e.base_date <= ?::date");
      binds.add(q.baseDateTo());
    }
    if (q.periodStart() != null) {
      where.add("p.period_end >= ?::date");
      binds.add(q.periodStart());
    }
    if (q.periodEnd() != null) {
      where.add("p.period_start <= ?::date");
      binds.add(q.periodEnd());
    }
    if (q.keyword() != null && !q.keyword().isBlank()) {
      where.add(
          "(e.source_ref_key ilike ? or e.source_ref_code ilike ? or e.note ilike ?"
              + " or p.pool_name ilike ? or r.rule_name ilike ?)");
      String like = "%" + q.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
  }

  /** 条目按币种的汇总（**绝不跨币种相加**）。 */
  private List<FinanceCostCollectionEntryCurrencyTotal> entryTotalsByCurrency(
      FinanceCostCollectionQuery query) {
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    applyFilters(query, where, binds);
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    return sql.fetch(
            "select e.currency, sum(e.amount) as amount, count(*) as cnt,"
                + " sum(e.allocated_amount) as allocated"
                + " from finance_cost_collection_entry e"
                + " join finance_cost_collection_pool p on p.tenant_id = e.tenant_id and p.id = e.pool_id"
                + " join finance_cost_collection_rule r on r.tenant_id = e.tenant_id and r.id = e.rule_id"
                + clause
                + " group by e.currency order by e.currency",
            binds.toArray())
        .map(
            r ->
                new FinanceCostCollectionEntryCurrencyTotal(
                    r.get("currency", String.class),
                    r.get("amount", BigDecimal.class),
                    r.get("cnt", Integer.class),
                    r.get("allocated", BigDecimal.class) == null
                        ? BigDecimal.ZERO
                        : r.get("allocated", BigDecimal.class)));
  }

  /** 条目按维度的汇总（口径维度，**不是分摊结果**）。 */
  private List<FinanceCostCollectionEntryDimensionTotal> entryTotalsByDimension(
      FinanceCostCollectionQuery query) {
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    applyFilters(query, where, binds);
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    return sql.fetch(
            "select e.target_dimension, e.currency, sum(e.amount) as amount, count(*) as cnt"
                + " from finance_cost_collection_entry e"
                + " join finance_cost_collection_pool p on p.tenant_id = e.tenant_id and p.id = e.pool_id"
                + " join finance_cost_collection_rule r on r.tenant_id = e.tenant_id and r.id = e.rule_id"
                + clause
                + " group by e.target_dimension, e.currency"
                + " order by e.target_dimension, e.currency",
            binds.toArray())
        .map(
            r ->
                new FinanceCostCollectionEntryDimensionTotal(
                    TargetDimension.valueOf(r.get("target_dimension", String.class)),
                    r.get("currency", String.class),
                    r.get("amount", BigDecimal.class),
                    r.get("cnt", Integer.class)));
  }

  /** 一条归集条目的详情（条目 + 依据）。 */
  public FinanceCostCollectionEntry entry(UUID id) {
    var row =
        sql.fetchOne(
            "select e.*, p.pool_key, p.pool_name, p.period_start, p.period_end, r.rule_code, r.rule_name"
                + " from finance_cost_collection_entry e"
                + " join finance_cost_collection_pool p on p.tenant_id = e.tenant_id and p.id = e.pool_id"
                + " join finance_cost_collection_rule r on r.tenant_id = e.tenant_id and r.id = e.rule_id"
                + " where e.id = ?",
            id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "归集条目不存在");
    }
    return entryWith(row);
  }

  /**
   * 删除一条归集条目（**调整**用）。
   *
   * <p>蓝图关系句：「先按规则归集费用池，再按维度分摊，<b>便于核对与调整</b>」——
   * 归集不是不可撤销的记账动作：费用进错了池就应当能撤出来重新归集。
   * 依据行随条目一起删（依据描述的是**这一条**，条目没了依据就没有意义）。
   */
  public void deleteEntry(UUID id) {
    var row =
        sql.fetchOne("select id, tenant_id, pool_id from finance_cost_collection_entry where id = ?", id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "归集条目不存在");
    }
    var pool =
        sql.fetchOne(
            "select status from finance_cost_collection_pool where tenant_id = ? and id = ?",
            row.get("tenant_id", UUID.class),
            row.get("pool_id", UUID.class));
    if (pool != null && "CLOSED".equals(pool.get(0, String.class))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "费用池已关闭，不能再调整它的条目");
    }
    sql.execute("delete from finance_cost_collection_basis where entry_id = ?", id);
    sql.execute("delete from finance_cost_collection_entry where id = ?", id);
  }

  // ================================================================ 归集：平台费（12.3.1.2）

  /**
   * 从 12.3.1.2 的结算明细行归集平台费（按月）。
   *
   * <p>⚠️ **广告费不在这一档**：结算单里的 ADVERTISING 行与 14.3.1.1 活动报表是同一笔钱的两种口径，
   * 两个都归集会重复计费 ⇒ 这里只取佣金 / FBA 费用 / 仓储费 / 配送费四类。
   */
  public FinanceCostCollectionResult collectPlatformSettlement(
      UUID ruleId, LocalDate from, LocalDate to) {
    var rule = activeRule(ruleId, FeeSource.PLATFORM_SETTLEMENT);
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    where.add("s.status not in ('VOID','SUPERSEDED')");
    where.add("l.transaction_type in ('COMMISSION','FBA_FEE','STORAGE_FEE','SHIPPING_FEE')");
    where.add("l.amount <> 0");
    where.add("l.occurred_on is not null");
    if (from != null) {
      where.add("l.occurred_on >= ?::date");
      binds.add(from);
    }
    if (to != null) {
      where.add("l.occurred_on <= ?::date");
      binds.add(to);
    }
    var lines =
        sql.fetch(
            "select l.id, l.line_no, l.occurred_on, l.currency, l.amount, l.raw_amount,"
                + " l.transaction_type, l.description, l.external_reference, l.quantity,"
                + " s.settlement_no, s.platform_code, s.shop_name, s.period_end"
                + " from finance_settlement_line l"
                + " join finance_settlement s on s.tenant_id = l.tenant_id and s.id = l.settlement_id"
                + " where "
                + String.join(" and ", where)
                + " order by l.occurred_on, s.settlement_no, l.line_no",
            binds.toArray());

    var notes = new ArrayList<String>();
    var candidates = new ArrayList<Candidate>();
    for (var line : lines) {
      var amount = line.get("amount", BigDecimal.class).abs();
      if (amount.signum() <= 0) continue;
      var baseDate = line.get("occurred_on", LocalDate.class);
      candidates.add(
          new Candidate(
              "SETTLEMENT_LINE:" + line.get("id", UUID.class),
              "SETTLEMENT_LINE",
              line.get("settlement_no", String.class),
              line.get("external_reference", String.class),
              amount,
              line.get("raw_amount", BigDecimal.class),
              baseDate,
              line.get("currency", String.class),
              line.get("quantity", BigDecimal.class),
              FinanceCostCollectionBasisDetail.settlementLine(
                  line.get("transaction_type", String.class),
                  line.get("settlement_no", String.class),
                  line.get("line_no", Integer.class),
                  line.get("platform_code", String.class)),
              line.get("description", String.class),
              baseDate));
    }
    return persist(rule, candidates, notes);
  }

  // ================================================================ 归集：广告费（14.3.1.1）

  /**
   * 从 14.3.1.1 活动报表归集广告费（按月）。
   *
   * <p>⚠️ 广告报表行是**活动级汇总、不带单一发生日期** ⇒ 基准日由操作员显式给出
   * （要关账的那个月），**不静默默认**「今天」。
   */
  public FinanceCostCollectionResult collectAdSpend(UUID ruleId, LocalDate baseDate, String bearer) {
    var rule = activeRule(ruleId, FeeSource.AD_SPEND);
    if (baseDate == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "广告报表行是活动级汇总、不带单一发生日期，必须显式给出归集基准日（要关账的那个月）");
    }
    var notes = new ArrayList<String>();
    var candidates = new ArrayList<Candidate>();
    var truncated = false;
    for (int page = 0; page < AdSpendPort.MAX_PAGES; page++) {
      var upstream = adSpend.rows(page, bearer);
      for (var row : upstream.items()) {
        var spend = row.spend();
        if (spend == null || spend.signum() <= 0) {
          notes.add("广告活动 " + label(row) + " 的花费为 0 或为空，不产生费用");
          continue;
        }
        if (row.currency() == null || row.currency().isBlank()) {
          notes.add("广告活动 " + label(row) + " 没有币种，无法归集（不同币种不相加）");
          continue;
        }
        var key = row.externalCampaignId() != null && !row.externalCampaignId().isBlank()
            ? row.externalCampaignId()
            : row.dimensionKey();
        if (key == null || key.isBlank()) {
          notes.add("广告报表行没有活动标识，无法作为幂等锚点，跳过");
          continue;
        }
        candidates.add(
            new Candidate(
                "AD_REPORT_ROW:" + key + ":" + baseDate,
                "AD_REPORT_ROW",
                key,
                row.dimensionLabel(),
                spend,
                spend,
                baseDate,
                row.currency(),
                null,
                FinanceCostCollectionBasisDetail.adReport(
                    key, row.dimensionLabel(), row.platformName()),
                "广告花费 " + label(row),
                baseDate));
      }
      if (upstream.items().size() < AdSpendPort.PAGE_SIZE) break;
      if (page == AdSpendPort.MAX_PAGES - 1) truncated = true;
    }
    if (truncated) {
      notes.add(
          "上游广告报表超过 "
              + (AdSpendPort.MAX_PAGES * AdSpendPort.PAGE_SIZE)
              + " 行，已截断 —— 需要更完整的归集请缩小活动范围");
    }
    return persist(rule, candidates, notes);
  }

  // ================================================================ 归集：物流费（10.2.1.1）

  /** 从 10.2.1.1 运单运费登记归集物流费（按单）。 */
  public FinanceCostCollectionResult collectLogistics(UUID ruleId, String bearer) {
    var rule = activeRule(ruleId, FeeSource.LOGISTICS_WAYBILL);
    var notes = new ArrayList<String>();
    var candidates = new ArrayList<Candidate>();
    var truncated = false;
    for (int page = 0; page < WaybillFreightPort.MAX_PAGES; page++) {
      var upstream = waybills.rows(page, bearer);
      for (var row : upstream.items()) {
        if (!WaybillFreightPort.isCollectable(row)) {
          notes.add(
              "运单 "
                  + (row.requestNo() == null ? row.id() : row.requestNo())
                  + " 的运费未登记或为 0，不产生费用");
          continue;
        }
        var occurred = row.issuedAt() != null ? row.issuedAt() : row.createdAt();
        if (occurred == null) {
          notes.add(
              "运单 "
                  + (row.requestNo() == null ? row.id() : row.requestNo())
                  + " 没有下单或出号时刻，无法确定归集基准日，跳过");
          continue;
        }
        var baseDate = occurred.atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
        var key =
            row.requestNo() != null && !row.requestNo().isBlank() ? row.requestNo() : row.id().toString();
        var amount = row.freightAmount();
        candidates.add(
            new Candidate(
                "WAYBILL_FREIGHT:" + key,
                "WAYBILL_FREIGHT",
                key,
                row.waybillNo(),
                amount,
                amount,
                baseDate,
                row.freightCurrency(),
                null,
                FinanceCostCollectionBasisDetail.waybillFreight(
                    row.freightSource(), row.waybillNo(), row.salesOrderNo(), row.carrierCode()),
                "运单 " + key + " 的登记运费",
                baseDate));
      }
      if (upstream.items().size() < WaybillFreightPort.PAGE_SIZE) break;
      if (page == WaybillFreightPort.MAX_PAGES - 1) truncated = true;
    }
    if (truncated) {
      notes.add(
          "上游运单超过 "
              + (WaybillFreightPort.MAX_PAGES * WaybillFreightPort.PAGE_SIZE)
              + " 票，已截断 —— 需要更完整的归集请分批处理");
    }
    return persist(rule, candidates, notes);
  }

  // ================================================================ 归集：头程费（10.4.1.1）

  /**
   * 从 10.4.1.1 头程发运单归集头程费（按 SKU）。
   *
   * <p>⚠️ **只归集已发运的**：没发出去的运费还是报价，不是已发生的成本。
   * <br>⚠️ **归集到单票发运单，不拆到 SKU**：SKU 级拆分是 12.4.2.2 的分摊职责，
   * 本模块越界做它，两条车道会互相打架。
   */
  public FinanceCostCollectionResult collectHeadMile(UUID ruleId, String bearer) {
    var rule = activeRule(ruleId, FeeSource.HEAD_MILE_SHIPMENT);
    var notes = new ArrayList<String>();
    var candidates = new ArrayList<Candidate>();
    var truncated = false;
    for (int page = 0; page < FirstMilePort.MAX_PAGES; page++) {
      var upstream = firstMile.shipments(page, bearer);
      for (var row : upstream.rows()) {
        if (row.dispatchedAt() == null) {
          notes.add("头程发运单 " + row.shipmentCode() + " 还没发出，运费是报价不是已发生成本，跳过");
          continue;
        }
        var freight = zero(row.freightCost());
        var insurance = zero(row.insuranceCost());
        var handling = zero(row.handlingCost());
        var amount = freight.add(insurance).add(handling);
        if (amount.signum() <= 0) {
          notes.add("头程发运单 " + row.shipmentCode() + " 没有运费 / 保险费 / 处理费，不产生费用");
          continue;
        }
        if (row.currency() == null || row.currency().isBlank()) {
          notes.add("头程发运单 " + row.shipmentCode() + " 没有币种，无法归集（不同币种不相加）");
          continue;
        }
        var baseDate = row.dispatchedAt().atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
        candidates.add(
            new Candidate(
                "FIRST_MILE_SHIPMENT:" + row.shipmentCode(),
                "FIRST_MILE_SHIPMENT",
                row.shipmentCode(),
                row.carrierName(),
                amount,
                amount,
                baseDate,
                row.currency(),
                null,
                FinanceCostCollectionBasisDetail.headMile(
                    row.carrierName(), row.shipmentCode(), freight, insurance, handling),
                "头程发运单 " + row.shipmentCode() + " 的头程费用（运费 + 保险费 + 处理费）",
                baseDate));
      }
      if (upstream.rows().size() < FirstMilePort.PAGE_SIZE) break;
      if (page == FirstMilePort.MAX_PAGES - 1) truncated = true;
    }
    if (truncated) {
      notes.add(
          "上游头程发运单超过 "
              + (FirstMilePort.MAX_PAGES * FirstMilePort.PAGE_SIZE)
              + " 票，已截断 —— 需要更完整的归集请分批处理");
    }
    return persist(rule, candidates, notes);
  }

  // ================================================================ 归集：人工录入

  /**
   * 人工录入归集（订阅费 / 人员费用 / 办公费用）。
   *
   * <p>蓝图输入里点名的第四类来源「人工录入」。它**不是**兜底的假数据源 ——
   * 订阅账单、工资与办公支出本来就没有上游系统事实，人工登记才是它们的正确来源，
   * 而「保留归集依据」要求把单据号与凭据号一起存下来。
   */
  public FinanceCostCollectionResult collectManual(FinanceCostCollectionManualInput input) {
    var rule = activeRule(input.ruleId(), null);
    if (rule.feeSource() != FeeSource.SUBSCRIPTION
        && rule.feeSource() != FeeSource.PERSONNEL
        && rule.feeSource() != FeeSource.OFFICE) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "人工录入只适用于订阅费 / 人员费用 / 办公费用；「"
              + rule.feeSource().label()
              + "」有上游事实来源，请走对应的归集入口");
    }
    if (input.amount() == null || input.amount().signum() <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "金额必须大于 0");
    }
    var currency = input.currency().trim().toUpperCase(Locale.ROOT);
    if (!currency.matches("^[A-Z]{3}$")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "币种必须是三位大写代码");
    }
    LocalDate periodStart;
    LocalDate periodEnd;
    if (rule.periodBasis() == PeriodBasis.ACCOUNTING_PERIOD) {
      if (input.periodStart() == null || input.periodEnd() == null) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "这条规则按「会计期间」归集，必须显式给出归属期间的起止日 —— "
                + "人员与办公费用天然跨自然月，不能按自然月切碎");
      }
      periodStart = input.periodStart();
      periodEnd = input.periodEnd();
      if (periodEnd.isBefore(periodStart)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "归属期间截止日不能早于起始日");
      }
    } else {
      periodStart = monthStart(input.baseDate());
      periodEnd = monthEnd(input.baseDate());
    }
    var candidate =
        new Candidate(
            "MANUAL_DOCUMENT:" + input.sourceRefKey().trim(),
            "MANUAL_DOCUMENT",
            input.sourceRefKey().trim(),
            input.sourceRefCode() == null ? null : input.sourceRefCode(),
            money(input.amount()),
            money(input.amount()),
            input.baseDate(),
            currency,
            input.quantity(),
            FinanceCostCollectionBasisDetail.manualDocument(
                "MANUAL_REGISTERED", input.sourceRefKey().trim(), input.subject()),
            input.subject(),
            input.baseDate());
    return persist(rule, List.of(candidate), new ArrayList<>(), periodStart, periodEnd, currency,
        input.evidenceRef(), input.note());
  }

  // ================================================================ 归集落库

  /** 一条待归集的费用（来自上游事实或人工录入）。 */
  private record Candidate(
      String sourceRefKey,
      String sourceRefType,
      String basisCode,
      String sourceRefCode,
      BigDecimal amount,
      BigDecimal sourceRefAmount,
      LocalDate baseDate,
      String currency,
      BigDecimal quantity,
      FinanceCostCollectionBasisDetail detail,
      String description,
      LocalDate basisDate) {}

  /** 把候选费用落成池条目（幂等：已存在的上游事实跳过）。 */
  private FinanceCostCollectionResult persist(
      FinanceCostCollectionRule rule, List<Candidate> candidates, List<String> notes) {
    return persist(
        rule,
        candidates,
        notes,
        null,
        null,
        null,
        null,
        null);
  }

  /**
   * 把候选费用落成池条目。
   *
   * @param forcedPeriodStart 人工录入且规则为「会计期间」时显式给出的期间起
   * @param forcedPeriodEnd 人工录入且规则为「会计期间」时显式给出的期间止
   * @param forcedCurrency 人工录入时显式给出的币种
   */
  private FinanceCostCollectionResult persist(
      FinanceCostCollectionRule rule,
      List<Candidate> candidates,
      List<String> notes,
      LocalDate forcedPeriodStart,
      LocalDate forcedPeriodEnd,
      String forcedCurrency,
      String evidenceRef,
      String note) {
    var skippedDuplicated = 0;
    var skippedNoFact = 0;
    var entries = new ArrayList<FinanceCostCollectionEntry>();
    var usedPools = new LinkedHashMap<String, FinanceCostCollectionPool>();
    var tenant = currentTenant();

    // 幂等：同一笔上游事实只许归集一次（V351 的部分唯一索引兜底，这里先查一遍给出友好计数）。
    var keys = new ArrayList<String>();
    for (var candidate : candidates) {
      keys.add(candidate.sourceRefKey());
    }
    Set<String> existing = existingKeys(rule.feeSource(), keys);

    var now = OffsetDateTime.now(ZoneOffset.UTC);

    for (var candidate : candidates) {
      if (existing.contains(candidate.sourceRefKey())) {
        skippedDuplicated++;
        continue;
      }
      var currency = forcedCurrency != null ? forcedCurrency : candidate.currency();
      if (currency == null || currency.isBlank()) {
        skippedNoFact++;
        notes.add(candidate.basisCode() + " 没有币种，跳过（不同币种不相加）");
        continue;
      }
      if (candidate.amount() == null || candidate.amount().signum() <= 0) {
        skippedNoFact++;
        notes.add(candidate.basisCode() + " 的金额不是正数，跳过");
        continue;
      }
      LocalDate periodStart = forcedPeriodStart;
      LocalDate periodEnd = forcedPeriodEnd;
      if (periodStart == null) {
        periodStart = monthStart(candidate.baseDate());
        periodEnd = monthEnd(candidate.baseDate());
      }
      // 一个池一个币种一个期间 ⇒ 换币种或换期间就换池。
      var pool = poolOf(rule, periodStart, periodEnd, currency);
      usedPools.put(pool.id().toString(), pool);
      var entryId = UUID.randomUUID();
      // ⚠️ 上游金额取**绝对值**：平台账单里费用写的是负号（-120.50），
      //   本模块归集的是成本的正额 ⇒ 依据金额与条目金额必须是同一个符号口径，
      //   否则「条目金额 vs 依据金额」这条核对等式永远对不上（费用类永远差一个负号）。
      var upstreamAmount = money(candidate.sourceRefAmount() == null ? candidate.amount() : candidate.sourceRefAmount()).abs();
      sql.execute(
          "insert into finance_cost_collection_entry(id,tenant_id,pool_id,rule_id,fee_source,"
              + " fee_category,target_dimension,base_date,currency,amount,quantity,source_ref_type,"
              + " source_ref_key,source_ref_code,source_ref_amount,note,collected_at,created_at,updated_at)"
              + " values(?,?,?,?,?,?,?,?::date,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?::timestamptz)",
          entryId,
          tenant,
          pool.id(),
          rule.id(),
          rule.feeSource().name(),
          rule.feeCategory().name(),
          rule.targetDimension().name(),
          candidate.baseDate(),
          currency,
          money(candidate.amount()),
          candidate.quantity() == null ? null : candidate.quantity(),
          candidate.sourceRefType(),
          candidate.sourceRefKey(),
          candidate.sourceRefCode() == null ? "" : candidate.sourceRefCode(),
          upstreamAmount,
          note != null && !note.isBlank()
              ? note
              : (candidate.description() == null ? "" : candidate.description()),
          now,
          now,
          now);
      sql.execute(
          "insert into finance_cost_collection_basis(id,tenant_id,entry_id,basis_type,basis_code,"
              + " basis_currency,basis_amount,basis_date,evidence_ref,detail,created_at)"
              + " values(?,?,?,?,?,?,?,?::date,?,?::jsonb,?::timestamptz)",
          UUID.randomUUID(),
          tenant,
          entryId,
          candidate.sourceRefType(),
          candidate.basisCode() == null ? candidate.sourceRefKey() : candidate.basisCode(),
          currency,
          upstreamAmount,
          candidate.basisDate(),
          evidenceRef == null ? "" : evidenceRef,
          detailJson(candidate.detail()),
          now);
      entries.add(entry(entryId));
      existing.add(candidate.sourceRefKey());
    }

    // 池合计由触发器派生 ⇒ 这里回读一次，保证响应里的合计与库里一致。
    var pools = new ArrayList<FinanceCostCollectionPool>();
    for (var id : usedPools.keySet()) {
      pools.add(pool(UUID.fromString(id)));
    }
    return new FinanceCostCollectionResult(
        entries.size(),
        skippedDuplicated,
        skippedNoFact,
        List.copyOf(notes),
        rule,
        List.copyOf(pools),
        List.copyOf(entries));
  }

  /** 依据的分项明细 → JSON 文本（存进 jsonb 列）。 */
  private String detailJson(FinanceCostCollectionBasisDetail detail) {
    try {
      return json.writeValueAsString(detail);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("归集依据分项序列化失败", e);
    }
  }

  /** 已经归集过的上游事实键。 */
  private Set<String> existingKeys(FeeSource source, List<String> keys) {
    var found = new LinkedHashSet<String>();
    if (keys.isEmpty()) return found;
    sql.fetch(
            "select source_ref_key from finance_cost_collection_entry where fee_source = ?"
                + " and source_ref_key = any(?)",
            source.name(),
            keys.toArray(new String[0]))
        .forEach(r -> found.add(r.get(0, String.class)));
    return found;
  }

  /** 找到（或建出）规则对应的费用池。 */
  private FinanceCostCollectionPool poolOf(
      FinanceCostCollectionRule rule, LocalDate periodStart, LocalDate periodEnd, String currency) {
    var existing =
        sql.fetchOne(
            "select * from finance_cost_collection_pool where pool_key = ? and dimension = ?"
                + " and period_start = ?::date and period_end = ?::date and currency = ?",
            rule.poolKey(),
            rule.targetDimension().name(),
            periodStart,
            periodEnd,
            currency);
    if (existing != null) {
      return pool(existing);
    }
    var id = UUID.randomUUID();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "insert into finance_cost_collection_pool(id,tenant_id,pool_key,pool_name,dimension,"
            + " period_start,period_end,currency,status,total_amount,entry_count,"
            + " allocation_available,allocation_owner_module,allocation_note,note,created_at,updated_at)"
            + " values(?,?,?,?,?,?::date,?::date,?,'OPEN',0,0,false,'12.4.2.2',?,'',?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        rule.poolKey(),
        rule.poolName() == null || rule.poolName().isBlank() ? rule.poolKey() : rule.poolName(),
        rule.targetDimension().name(),
        periodStart,
        periodEnd,
        currency,
        "归集池（未分摊）：分摊属 12.4.2.2，本模块只归集",
        now,
        now);
    return pool(sql.fetchOne("select * from finance_cost_collection_pool where id = ?", id));
  }

  // ================================================================ 行映射与工具

  private FinanceCostCollectionRule rule(Record r) {
    return new FinanceCostCollectionRule(
        r.get("id", UUID.class),
        r.get("rule_code", String.class),
        r.get("rule_name", String.class),
        FeeSource.valueOf(r.get("fee_source", String.class)),
        FeeCategory.valueOf(r.get("fee_category", String.class)),
        TargetDimension.valueOf(r.get("target_dimension", String.class)),
        PeriodBasis.valueOf(r.get("period_basis", String.class)),
        r.get("pool_key", String.class),
        r.get("pool_name", String.class),
        r.get("effective_from", LocalDate.class),
        r.get("effective_to", LocalDate.class),
        RuleStatus.valueOf(r.get("status", String.class)),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private FinanceCostCollectionRule rule(UUID id) {
    var row = sql.fetchOne("select * from finance_cost_collection_rule where id = ?", id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "归集规则不存在");
    }
    return rule(row);
  }

  private FinanceCostCollectionPool pool(Record r) {
    return new FinanceCostCollectionPool(
        r.get("id", UUID.class),
        r.get("pool_key", String.class),
        r.get("pool_name", String.class),
        TargetDimension.valueOf(r.get("dimension", String.class)),
        r.get("period_start", LocalDate.class),
        r.get("period_end", LocalDate.class),
        r.get("currency", String.class),
        PoolStatus.valueOf(r.get("status", String.class)),
        r.get("total_amount", BigDecimal.class),
        r.get("entry_count", Integer.class) == null ? 0 : r.get("entry_count", Integer.class),
        r.get("allocation_available", Boolean.class),
        r.get("allocation_owner_module", String.class),
        r.get("allocation_note", String.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private FinanceCostCollectionPool pool(UUID id) {
    var row = sql.fetchOne("select * from finance_cost_collection_pool where id = ?", id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "费用池不存在");
    }
    return pool(row);
  }

  /** 条目 + 池信息 + 依据。 */
  private FinanceCostCollectionEntry entryWith(Record r) {
    var basis = basisOf(r.get("id", UUID.class));
    return new FinanceCostCollectionEntry(
        r.get("id", UUID.class),
        r.get("pool_id", UUID.class),
        r.get("rule_id", UUID.class),
        FeeSource.valueOf(r.get("fee_source", String.class)),
        FeeCategory.valueOf(r.get("fee_category", String.class)),
        TargetDimension.valueOf(r.get("target_dimension", String.class)),
        r.get("base_date", LocalDate.class),
        r.get("period_start", LocalDate.class),
        r.get("period_end", LocalDate.class),
        r.get("currency", String.class),
        r.get("amount", BigDecimal.class),
        r.get("quantity", BigDecimal.class),
        r.get("source_ref_type", String.class),
        r.get("source_ref_key", String.class),
        r.get("source_ref_code", String.class),
        r.get("source_ref_amount", BigDecimal.class),
        r.get("allocated_amount", BigDecimal.class),
        r.get("allocation_status", String.class),
        r.get("allocation_owner_module", String.class),
        r.get("note", String.class),
        r.get("collected_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("pool_key", String.class),
        r.get("pool_name", String.class),
        r.get("rule_code", String.class),
        r.get("rule_name", String.class),
        basis);
  }

  /** 一条归集依据。 */
  private FinanceCostCollectionBasis basisOf(UUID entryId) {
    var row =
        sql.fetchOne("select * from finance_cost_collection_basis where entry_id = ?", entryId);
    if (row == null) return null;
    FinanceCostCollectionBasisDetail detail = null;
    var raw = row.get("detail", String.class);
    if (raw != null && !raw.isBlank() && !raw.equals("{}")) {
      try {
        detail = json.readValue(raw, FinanceCostCollectionBasisDetail.class);
      } catch (Exception e) {
        detail = null;
      }
    }
    return new FinanceCostCollectionBasis(
        row.get("id", UUID.class),
        row.get("entry_id", UUID.class),
        BasisType.valueOf(row.get("basis_type", String.class)),
        row.get("basis_code", String.class),
        row.get("basis_currency", String.class),
        row.get("basis_amount", BigDecimal.class),
        row.get("basis_date", LocalDate.class),
        row.get("evidence_ref", String.class),
        detail,
        row.get("created_at", OffsetDateTime.class));
  }

  /** 当前租户（RLS 的 GUC 是唯一真相来源，服务的租户上下文由 AccessService 绑定）。 */
  private UUID currentTenant() {
    var raw =
        sql.fetchOne("select nullif(current_setting('app.current_tenant', true), '')::uuid as t")
            .get("t", UUID.class);
    if (raw == null) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "租户上下文缺失：归集必须在绑定租户的事务里执行");
    }
    return raw;
  }

  /** 找一条**启用中**的归集规则（可选地限定来源）。 */
  private FinanceCostCollectionRule activeRule(UUID ruleId, FeeSource source) {
    var rule = rule(ruleId);
    if (rule.status() != RuleStatus.ACTIVE) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "归集规则已停用：" + rule.ruleCode());
    }
    if (source != null && rule.feeSource() != source) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "这条规则是「" + rule.feeSource().label() + "」，不能用「" + source.label() + "」的入口归集");
    }
    return rule;
  }

  /** 自然月的第一天。 */
  static LocalDate monthStart(LocalDate date) {
    return date.withDayOfMonth(1);
  }

  /** 自然月的最后一天。 */
  static LocalDate monthEnd(LocalDate date) {
    return date.withDayOfMonth(date.lengthOfMonth());
  }

  /** 金额规整到 4 位（与 DB 的 numeric(19,4) 一致）。 */
  static BigDecimal money(BigDecimal value) {
    return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal zero(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static String text(String value) {
    return value == null ? "" : value;
  }

  private static String poolName(FinanceCostCollectionRuleInput input) {
    if (input.poolName() != null && !input.poolName().isBlank()) return input.poolName().trim();
    return input.poolKey().trim();
  }

  private static String label(AdSpendPort.AdReportRow row) {
    if (row.dimensionLabel() != null && !row.dimensionLabel().isBlank()) return row.dimensionLabel();
    if (row.externalCampaignId() != null && !row.externalCampaignId().isBlank()) {
      return row.externalCampaignId();
    }
    return row.dimensionKey() == null ? "（无标识）" : row.dimensionKey();
  }

  /** 绑定参数追加。 */
  private static Object[] concat(List<Object> binds, Object... more) {
    var all = new ArrayList<Object>(binds);
    all.addAll(List.of(more));
    return all.toArray();
  }
}
