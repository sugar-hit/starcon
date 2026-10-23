package com.starcon.finance;

import static com.starcon.finance.CostAllocationModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * 12.4.2.2 分摊规则。
 *
 * <p>蓝图 12-finance.md：「配置费用分摊规则：分摊维度（SKU/SPU/店铺/订单/类目）、分摊基数（数量/金额/重量/体积/等额）、生效区间、分摊顺序与舍入差处理。」输入：「规则配置；待分摊费用池。」输出：「分摊结果（保留舍入差额到指定对象）+ 分摊明细。」关系：「上游 = 12.4.2.1；下游 = 13 利润（SKU 利润）。<b>分摊结果之和必须等于被分摊总额</b>（含舍入差处理），否则利润报表总额对不上 —— 这是财务最不能接受的错误。」
 *
 * <p><b>🔴 本模块唯一必须恒成立的等式：Σ分摊结果 = 被分摊总额。</b>三道保障：
 *
 * <ol>
 *   <li>算法层：向下取整 + 把零头**补回去**（零头不消失，逐行落在 {@code rounding_adjustment} 上）；
 *   <li>应用层：写完结果行后**自己再算一遍**Σ，不等就抛异常、回滚（不写出一张对不上的单）；
 *   <li>数据层：批次上的 {@code CHECK(allocated_total = source_total)} +
 *       延迟约束触发器在**提交时**复核 Σ结果行。
 * </ol>
 *
 * <p><b>时间字段</b>：{@code allocated_at} / {@code reverted_at} / {@code collected_at} /
 * {@code created_at} / {@code updated_at} 是 {@code timestamptz}，一律 OffsetDateTime +
 * {@code ?::timestamptz}；{@code effective_from} / {@code effective_to} / {@code target_date}
 * 是 {@code date}（日历日，不是某一瞬间），一律 {@code ?::date}。
 * 凡涉及「今天 / 本月」的判断一律用 <b>UTC 日历日</b>（{@code LocalDate.now(ZoneOffset.UTC)}）。
 *
 * <p><b>上游没交付就说没交付，绝不造数。</b>
 *
 * <ul>
 *   <li>分摊对象 ← trade 平台订单 / trade 销售订单行 / catalog SKU 主数据（跨服务只读端口）；
 *   <li>13 利润 —— 未交付（不在本仓）⇒ 本模块<b>不产出任何利润数字</b>；
 *   <li>12.5.2.1 凭证 —— 未交付 ⇒ 分摊批次<b>不生成会计凭证</b>。
 * </ul>
 */
@Service
public class CostAllocationService {

  /** 一页最大条数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 规则编码 / 池标识的长度上限（与 DB 列宽一致）。 */
  static final int KEY_MAX = 200;

  /** 采集分摊对象时，最多翻多少页上游订单（每页 200 条）。 */
  static final int MAX_ORDER_PAGES = 10;

  /** 采集分摊对象时，最多取多少个 SKU 的包装档案（重量 / 体积）。 */
  static final int MAX_SKU_DETAIL = 200;

  /** 采集分摊对象时，最多翻多少页 catalog SKU 列表（用于 SKU → SPU / 类目映射）。 */
  static final int MAX_SKU_PAGES = 10;

  /** 「全部池」的池标识。 */
  static final String ANY_POOL = "*";

  /** 一份零头（0.0001）。 */
  private static final BigDecimal UNIT = new BigDecimal("0.0001");

  private final DSLContext sql;
  private final TradeOrderShipmentPort platformOrders;
  private final ChannelShopPort shops;
  private final SalesOrderLinePort salesOrders;
  private final CatalogSkuPort catalog;

  public CostAllocationService(
      DSLContext sql,
      TradeOrderShipmentPort platformOrders,
      ChannelShopPort shops,
      SalesOrderLinePort salesOrders,
      CatalogSkuPort catalog) {
    this.sql = sql;
    this.platformOrders = platformOrders;
    this.shops = shops;
    this.salesOrders = salesOrders;
    this.catalog = catalog;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：**每一维说清「有没有、依据是什么、缺了会怎样」**。
   *
   * <p>⚠️ 本模块的缺口要分开说：13 利润（**别的**服务）与 12.5.2.1 凭证（同服务但未交付）——
   * 把两者都写成「不可用」，运维会以为要去催凭证，而实际上凭证还没到该做的时候。
   */
  public FinanceCostAllocationCapabilities capabilities() {
    var capabilities = new ArrayList<FinanceCostAllocationCapability>();
    var gaps = new ArrayList<FinanceCostAllocationDownstreamGap>();

    capabilities.add(
        new FinanceCostAllocationCapability(
            "12.4.2.2",
            "分摊规则（维度 / 基数 / 生效区间 / 顺序 / 舍入差处理）",
            true,
            "finance_cost_allocation_rule + 表级 CHECK（生效区间不倒置、零头落点必须点名）",
            "",
            "规则由租户自己维护、**系统不预置任何一行**（费用怎么摊是租户自己的账务口径）。"
                + "生效区间落在**费用池的归属期间**上 —— 池的期间不在区间内的规则不命中，"
                + "所以**新规则不会追溯改写历史分摊**。"));

    capabilities.add(
        new FinanceCostAllocationCapability(
            "12.4.2.2",
            "分摊对象（真实上游事实的快照 + 四种基数）",
            true,
            "finance_cost_allocation_target + 采集端点 /targets/collect",
            "",
            "对象**不是本模块造出来的**：订单来自 trade 平台订单与销售订单行，"
                + "SKU → SPU / 类目的映射与包装重量 / 体积来自 catalog。"
                + "取不到的基数**留空并写明原因**，不用 0 顶替 —— "
                + "「未维护」与「真的是 0」是两回事，0 会静默改变分摊分母。"));

    capabilities.add(
        new FinanceCostAllocationCapability(
            "12.4.2.2",
            "分摊结果与分摊明细（🔴 Σ结果 = 被分摊总额）",
            true,
            "finance_cost_allocation_batch + finance_cost_allocation_result",
            "",
            "等式有三道保障：算法层向下取整 + 补零头、应用层写完自己再算一遍、"
                + "数据层 CHECK + 延迟约束触发器在提交时复核。"
                + "零头**不消失**：逐行记在 rounding_adjustment 上，批次记舍入差与落点对象。"));

    capabilities.add(
        new FinanceCostAllocationCapability(
            "12.4.2.2",
            "分摊顺序（多规则命中时的优先级）",
            true,
            "finance_cost_allocation_rule.priority（数字小的先用）",
            "",
            "命中规则 = 启用中 + 池标识匹配 + 生效区间覆盖池的归属期间，"
                + "按 priority 升序取**第一条**；命中多条时页面上能看见是按什么顺序选的。"));

    capabilities.add(
        new FinanceCostAllocationCapability(
            "12.4.2.1",
            "上游：待分摊费用池（归集明细）",
            true,
            "finance_cost_collection_pool / _entry（**同服务直读**，不跨服务）",
            "",
            "本模块只**读**费用池，不改归集口径。池合计由归集条目的触发器派生，"
                + "本模块把它当作**被分摊总额**（绝不另抄一份金额）。"));

    capabilities.add(
        new FinanceCostAllocationCapability(
            "9.1.2.1",
            "订单 / 店铺维度的分摊对象",
            true,
            "trade 平台订单 + channel 店铺主数据（跨服务只读端口）",
            "",
            "订单按**下单时间落在池的归属期间内**且**币种与池一致**才进对象集合 —— "
                + "绝不跨币种相加。店铺维度由订单按店铺汇总。"));

    capabilities.add(
        new FinanceCostAllocationCapability(
            "9.1.1.1",
            "SKU / SPU / 类目维度的分摊对象",
            true,
            "trade 销售订单行 + catalog SKU 列表 / SKU 档案（跨服务只读端口）",
            "",
            "数量与金额基数来自**真实卖出的订单行**；重量与体积来自 catalog 的包装档案。"
                + "SPU / 类目由 SKU 汇总而来 —— 其中**只要有一个构成 SKU 没维护包装**，"
                + "该 SPU / 类目的重量 / 体积基数就**整体不可得**（少算的和不叫缺失，叫算错）。"));

    gaps.add(
        new FinanceCostAllocationDownstreamGap(
            "13.4.1.1",
            "SKU 利润",
            "reporting（不在本仓）",
            false,
            "卷 13",
            "本模块产出「哪个对象摊到了多少钱」，正是 SKU 利润的费用侧输入；"
                + "但本模块**不产出任何利润数字**。"));

    gaps.add(
        new FinanceCostAllocationDownstreamGap(
            "12.5.2.1",
            "凭证管理",
            "finance",
            false,
            "卷 12",
            "分摊批次**还没有**生成会计凭证 —— 蓝图没写这一格，本模块不越界做它。"));

    gaps.add(
        new FinanceCostAllocationDownstreamGap(
            "12.4.1.2",
            "成本差异分析",
            "finance",
            false,
            "卷 12",
            "本模块的分摊结果是它的**上游事实**之一（分摊后的 SKU 成本才谈得上差异分析）。"));

    return new FinanceCostAllocationCapabilities(capabilities, gaps);
  }

  // ================================================================ 分摊规则

  /** 分摊规则列表（租户自己维护，系统不预置任何一行）。 */
  public List<FinanceCostAllocationRule> rules() {
    return sql.fetch(
            "select * from finance_cost_allocation_rule order by priority, effective_from desc, rule_code")
        .map(this::rule);
  }

  /** 新建分摊规则。 */
  public FinanceCostAllocationRule createRule(FinanceCostAllocationRuleInput input) {
    validateRule(input);
    var id = UUID.randomUUID();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "insert into finance_cost_allocation_rule(id,tenant_id,rule_code,rule_name,target_dimension,"
            + " allocation_basis,pool_key,priority,rounding_policy,rounding_target_type,"
            + " rounding_target_code,effective_from,effective_to,status,note,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?::date,?::date,?,?,?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        input.ruleCode().trim(),
        input.ruleName().trim(),
        input.targetDimension().name(),
        input.allocationBasis().name(),
        poolKey(input),
        input.priority() == null ? 100 : input.priority(),
        (input.roundingPolicy() == null ? RoundingPolicy.LARGEST_REMAINDER : input.roundingPolicy()).name(),
        roundingTargetType(input),
        roundingTargetCode(input),
        input.effectiveFrom(),
        input.effectiveTo(),
        (input.status() == null ? RuleStatus.ACTIVE : input.status()).name(),
        text(input.note()),
        now,
        now);
    return rule(id);
  }

  /**
   * 修改分摊规则。
   *
   * <p>⚠️ <b>已经产生过分摊批次的规则不允许改口径</b>（维度 / 基数 / 舍入差处理）：
   * 改了会让「当时按什么摊的」与规则对不上 —— 而分摊结果正是下游利润的历史事实。
   * 要换口径请新建一条规则（新规则靠生效区间与优先级生效，**不会追溯改写历史**）。
   */
  public FinanceCostAllocationRule updateRule(UUID id, FinanceCostAllocationRuleInput input) {
    validateRule(input);
    var existing = rule(id);
    var used =
        sql.fetchOne(
                "select count(*) from finance_cost_allocation_batch"
                    + " where tenant_id = ? and rule_id = ?",
                currentTenant(),
                id)
            .get(0, Long.class);
    if (used > 0
        && (existing.targetDimension() != input.targetDimension()
            || existing.allocationBasis() != input.allocationBasis()
            || existing.roundingPolicy() != input.roundingPolicy())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "这条规则已经分摊过 "
              + used
              + " 次，改口径会让「当时按什么摊的」与规则对不上 —— 请新建一条规则"
              + "（新规则靠生效区间与优先级生效，不会追溯改写历史分摊）");
    }
    sql.execute(
        "update finance_cost_allocation_rule set rule_code = ?, rule_name = ?, target_dimension = ?,"
            + " allocation_basis = ?, pool_key = ?, priority = ?, rounding_policy = ?,"
            + " rounding_target_type = ?, rounding_target_code = ?, effective_from = ?::date,"
            + " effective_to = ?::date, status = ?, note = ?, updated_at = ?::timestamptz where id = ?",
        input.ruleCode().trim(),
        input.ruleName().trim(),
        input.targetDimension().name(),
        input.allocationBasis().name(),
        poolKey(input),
        input.priority() == null ? 100 : input.priority(),
        (input.roundingPolicy() == null ? RoundingPolicy.LARGEST_REMAINDER : input.roundingPolicy()).name(),
        roundingTargetType(input),
        roundingTargetCode(input),
        input.effectiveFrom(),
        input.effectiveTo(),
        (input.status() == null ? RuleStatus.ACTIVE : input.status()).name(),
        text(input.note()),
        OffsetDateTime.now(ZoneOffset.UTC),
        id);
    return rule(id);
  }

  /** 规则入参校验（DB 层还有 CHECK 兜底，这里提前拦一道是为了给出能直接展示给人看的错误）。 */
  private void validateRule(FinanceCostAllocationRuleInput input) {
    if (input.targetDimension() == null || input.allocationBasis() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分摊维度与分摊基数都必须给出");
    }
    if (input.effectiveTo() != null && input.effectiveTo().isBefore(input.effectiveFrom())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "生效区间不能是反的（起 > 止）");
    }
    var policy =
        input.roundingPolicy() == null ? RoundingPolicy.LARGEST_REMAINDER : input.roundingPolicy();
    if (policy == RoundingPolicy.SPECIFIED) {
      if (input.roundingTargetType() == null
          || input.roundingTargetCode() == null
          || input.roundingTargetCode().isBlank()) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "舍入差策略选了「指定对象」，就必须点名是哪个对象");
      }
      if (input.roundingTargetType() != input.targetDimension()) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "零头落点的类型必须与分摊维度一致");
      }
    }
  }

  // ================================================================ 待分摊费用池

  /** 待分摊的费用池列表（真分页 + 真筛选）。 */
  public FinanceCostAllocationPoolPage pools(
      String poolKey, String currency, LocalDate periodFrom, LocalDate periodTo, int page, int size) {
    int p = Math.max(0, page);
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    if (poolKey != null && !poolKey.isBlank()) {
      where.add("p.pool_key = ?");
      binds.add(poolKey.trim());
    }
    if (currency != null && !currency.isBlank()) {
      where.add("p.currency = ?");
      binds.add(currency.trim().toUpperCase(Locale.ROOT));
    }
    if (periodFrom != null) {
      where.add("p.period_end >= ?::date");
      binds.add(periodFrom);
    }
    if (periodTo != null) {
      where.add("p.period_start <= ?::date");
      binds.add(periodTo);
    }
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from finance_cost_collection_pool p" + clause, binds.toArray())
            .get(0, Long.class);
    var items =
        sql.fetch(
                "select p.*,"
                    + " (select count(*) from finance_cost_allocation_target t"
                    + "   where t.tenant_id = p.tenant_id and t.pool_id = p.id) as target_count,"
                    + " b.id as batch_id, b.status as batch_status"
                    + " from finance_cost_collection_pool p"
                    + " left join finance_cost_allocation_batch b"
                    + "   on b.tenant_id = p.tenant_id and b.pool_id = p.id and b.status = 'ALLOCATED'"
                    + clause
                    + " order by p.period_start desc, p.pool_key, p.currency limit ? offset ?",
                concat(binds, s, p * s))
            .map(this::pool);
    return new FinanceCostAllocationPoolPage(items, p, s, total);
  }

  // ================================================================ 分摊对象

  /**
   * 采集分摊对象（从**真实上游**拉，落到本模块自己的对象表里）。
   *
   * <p>对象是一次采集的**快照**：采集时用的基数留在库里，之后上游改了也不影响已发生的分摊。
   */
  public FinanceCostAllocationTargetCollectResult collectTargets(
      UUID poolId, AllocationDimension dimension, String bearer) {
    var pool = poolRow(poolId);
    var collected = new ArrayList<CollectedTarget>();
    var notes = new ArrayList<String>();
    int skipped = 0;

    switch (dimension) {
      case ORDER -> {
        var result = collectOrderTargets(pool, bearer);
        collected.addAll(result.targets());
        skipped = result.skipped();
        notes.addAll(result.notes());
      }
      case SHOP -> {
        var result = collectShopTargets(pool, bearer);
        collected.addAll(result.targets());
        skipped = result.skipped();
        notes.addAll(result.notes());
      }
      case SKU, SPU, CATEGORY -> {
        var result = collectSkuFamilyTargets(pool, dimension, bearer);
        collected.addAll(result.targets());
        skipped = result.skipped();
        notes.addAll(result.notes());
      }
    }

    var now = OffsetDateTime.now(ZoneOffset.UTC);
    for (var target : collected) {
      sql.execute(
          "insert into finance_cost_allocation_target(id,tenant_id,pool_id,target_dimension,"
              + " target_type,target_code,target_name,basis_quantity,basis_amount,basis_weight,"
              + " basis_volume,basis_source,basis_note,target_date,collected_at,created_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?::date,?::timestamptz,?::timestamptz)"
              + " on conflict (tenant_id,pool_id,target_dimension,target_code) do update set"
              + " target_name = excluded.target_name, basis_quantity = excluded.basis_quantity,"
              + " basis_amount = excluded.basis_amount, basis_weight = excluded.basis_weight,"
              + " basis_volume = excluded.basis_volume, basis_source = excluded.basis_source,"
              + " basis_note = excluded.basis_note, target_date = excluded.target_date,"
              + " collected_at = excluded.collected_at",
          UUID.randomUUID(),
          currentTenant(),
          poolId,
          dimension.name(),
          dimension.name(),
          target.code(),
          target.name(),
          target.quantity(),
          target.amount(),
          target.weight(),
          target.volume(),
          target.source(),
          target.note(),
          target.date(),
          now,
          now);
    }
    // 从库里读回来（**回读真实落库的行**，而不是把内存里的对象当成结果）。
    var saved =
        sql.fetch(
                "select * from finance_cost_allocation_target where pool_id = ? and target_dimension = ?"
                    + " order by target_code",
                poolId,
                dimension.name())
            .map(this::target);
    return new FinanceCostAllocationTargetCollectResult(
        poolId, dimension, saved.size(), skipped, notes, saved);
  }

  /** 分摊对象列表（真分页 + 真筛选）。 */
  public FinanceCostAllocationTargetPage targets(
      UUID poolId, String dimension, String keyword, int page, int size) {
    int p = Math.max(0, page);
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    if (poolId != null) {
      where.add("pool_id = ?");
      binds.add(poolId);
    }
    if (dimension != null && !dimension.isBlank()) {
      where.add("target_dimension = ?");
      binds.add(dimension.trim());
    }
    if (keyword != null && !keyword.isBlank()) {
      where.add("(target_code like ? or target_name like ?)");
      binds.add("%" + keyword.trim() + "%");
      binds.add("%" + keyword.trim() + "%");
    }
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from finance_cost_allocation_target" + clause, binds.toArray())
            .get(0, Long.class);
    var items =
        sql.fetch(
                "select * from finance_cost_allocation_target"
                    + clause
                    + " order by target_dimension, target_code limit ? offset ?",
                concat(binds, s, p * s))
            .map(this::target);
    return new FinanceCostAllocationTargetPage(items, p, s, total);
  }

  // ================================================================ 执行分摊

  /**
   * 执行分摊：把一个费用池按规则摊到它的分摊对象上。
   *
   * <p>🔴 <b>本方法的核心承诺：Σ分摊结果 = 被分摊总额。</b>写完结果行后自己再算一遍，
   * 不等就抛异常回滚 —— 绝不写出一张总额对不上的单（那正是财务最不能接受的错误）。
   */
  public FinanceCostAllocationBatchDetail allocate(FinanceCostAllocationRequest request, String bearer) {
    var pool = poolRow(request.poolId());
    if (pool.totalAmount.signum() <= 0) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, "费用池「" + pool.poolKey + "」里没有金额，没什么可摊的");
    }
    if (pool.allocationAvailable) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "费用池「" + pool.poolKey + "」已经分摊过了 —— 要重摊请先撤销上一次分摊");
    }

    var rule = request.ruleId() == null ? selectRule(pool) : rule(request.ruleId());
    if (rule == null) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "没有匹配的分摊规则：需要一条启用中、池标识匹配「"
              + pool.poolKey
              + "」、且生效区间覆盖 "
              + pool.periodStart
              + " ~ "
              + pool.periodEnd
              + " 的规则");
    }
    if (rule.status() != RuleStatus.ACTIVE) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "分摊规则「" + rule.ruleCode() + "」已停用");
    }
    if (!ANY_POOL.equals(rule.poolKey()) && !rule.poolKey().equals(pool.poolKey())) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "分摊规则「" + rule.ruleCode() + "」适用于池「" + rule.poolKey() + "」，不是这个池");
    }
    if (rule.effectiveFrom().isAfter(pool.periodStart())
        || (rule.effectiveTo() != null && rule.effectiveTo().isBefore(pool.periodEnd()))) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "分摊规则「"
              + rule.ruleCode()
              + "」的生效区间（"
              + rule.effectiveFrom()
              + " ~ "
              + (rule.effectiveTo() == null ? "长期" : rule.effectiveTo())
              + "）没有覆盖费用池的归属期间（"
              + pool.periodStart
              + " ~ "
              + pool.periodEnd
              + "）");
    }

    var targets =
        sql.fetch(
                "select * from finance_cost_allocation_target where pool_id = ? order by target_code",
                pool.id())
            .map(this::target);
    if (targets.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "费用池「"
              + pool.poolKey()
              + "」还没有分摊对象 —— 先采集分摊对象（对象来自真实上游事实，本模块不凭空造）");
    }
    if (targets.stream().noneMatch(t -> t.targetDimension() == rule.targetDimension())) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "规则「"
              + rule.ruleCode()
              + "」按「"
              + rule.targetDimension().label()
              + "」分摊，但这个池采集到的对象是「"
              + targets.get(0).targetDimension().label()
              + "」");
    }

    var plan = plan(targets, rule, pool.totalAmount);
    if (plan.participants().isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "规则「"
              + rule.ruleCode()
              + "」指定的分摊基数（"
              + rule.allocationBasis().label()
              + "）在这个池的每一个分摊对象上都取不到 —— "
              + "没有基数就摊不出结果（摊出一张全 0 的单会让下游以为这个池不用花钱）");
    }

    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var batchId = UUID.randomUUID();
    sql.execute(
        "insert into finance_cost_allocation_batch(id,tenant_id,pool_id,rule_id,currency,"
            + " source_total,allocated_total,rounding_difference,rounding_target_type,"
            + " rounding_target_code,target_count,basis_unavailable_count,status,allocated_at,note,"
            + " created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,'ALLOCATED',?::timestamptz,?,?::timestamptz,?::timestamptz)",
        batchId,
        currentTenant(),
        pool.id(),
        rule.id(),
        pool.currency(),
        pool.totalAmount,
        pool.totalAmount,
        plan.roundingDifference(),
        plan.roundingTarget() == null ? null : rule.targetDimension().name(),
        plan.roundingTarget(),
        plan.participants().size(),
        plan.unavailable().size(),
        now,
        text(request.note()),
        now,
        now);

    for (var line : plan.lines()) {
      sql.execute(
          "insert into finance_cost_allocation_result(id,tenant_id,batch_id,target_dimension,"
              + " target_code,target_name,basis_value,basis_available,share_ratio,exact_amount,"
              + " allocated_amount,rounding_adjustment,is_rounding_target,basis_note,created_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz)",
          UUID.randomUUID(),
          currentTenant(),
          batchId,
          rule.targetDimension().name(),
          line.targetCode(),
          line.targetName(),
          line.basisValue(),
          line.basisAvailable(),
          line.shareRatio(),
          line.exactAmount(),
          line.allocatedAmount(),
          line.roundingAdjustment(),
          line.isRoundingTarget(),
          line.basisNote(),
          now);
    }

    // 🔴 应用层自己再算一遍：Σ结果必须等于被分摊总额，不等就回滚（不写出错账）。
    var sum =
        sql.fetchOne(
                "select coalesce(sum(allocated_amount),0) from finance_cost_allocation_result"
                    + " where batch_id = ?",
                batchId)
            .get(0, BigDecimal.class);
    if (sum.compareTo(pool.totalAmount) != 0) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "分摊结果之和 " + sum + " ≠ 被分摊总额 " + pool.totalAmount + " —— 已拒绝写出这次分摊");
    }

    return batchDetail(batchId);
  }

  /**
   * 撤销一次分摊。
   *
   * <p>撤销后费用池回到未分摊状态，可以按新规则重摊；
   * <b>被撤销的那次分摊结果留在库里</b>（历史是历史，不因为撤销就消失）。
   */
  public FinanceCostAllocationBatchDetail revert(UUID batchId) {
    var batch = batch(batchId);
    if (batch.status() != BatchStatus.ALLOCATED) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "这次分摊已经撤销过了");
    }
    sql.execute(
        "update finance_cost_allocation_batch set status = 'REVERTED', reverted_at = ?::timestamptz,"
            + " updated_at = ?::timestamptz where id = ?",
        OffsetDateTime.now(ZoneOffset.UTC),
        OffsetDateTime.now(ZoneOffset.UTC),
        batchId);
    return batchDetail(batchId);
  }

  // ================================================================ 分摊批次

  /** 分摊批次列表（真分页 + 真筛选 + 按币种分开的汇总）。 */
  public FinanceCostAllocationBatchPage batches(
      UUID poolId, String status, String currency, int page, int size) {
    int p = Math.max(0, page);
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    if (poolId != null) {
      where.add("b.pool_id = ?");
      binds.add(poolId);
    }
    if (status != null && !status.isBlank()) {
      where.add("b.status = ?");
      binds.add(status.trim());
    }
    if (currency != null && !currency.isBlank()) {
      where.add("b.currency = ?");
      binds.add(currency.trim().toUpperCase(Locale.ROOT));
    }
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    long total =
        sql.fetchOne(
                "select count(*) from finance_cost_allocation_batch b" + clause, binds.toArray())
            .get(0, Long.class);
    var items =
        sql.fetch(
                "select b.*, p.pool_key, p.pool_name, r.rule_code, r.rule_name"
                    + " from finance_cost_allocation_batch b"
                    + " join finance_cost_collection_pool p on p.tenant_id = b.tenant_id and p.id = b.pool_id"
                    + " join finance_cost_allocation_rule r on r.tenant_id = b.tenant_id and r.id = b.rule_id"
                    + clause
                    + " order by b.allocated_at desc, b.id limit ? offset ?",
                concat(binds, s, p * s))
            .map(this::batch);
    var totals =
        sql.fetch(
                "select b.currency, sum(b.source_total) as source_total,"
                    + " sum(b.allocated_total) as allocated_total, count(*) as batch_count"
                    + " from finance_cost_allocation_batch b"
                    + clause
                    + " group by b.currency order by b.currency",
                binds.toArray())
            .map(
                r ->
                    new FinanceCostAllocationBatchCurrencyTotal(
                        r.get("currency", String.class),
                        r.get("source_total", BigDecimal.class),
                        r.get("allocated_total", BigDecimal.class),
                        r.get("batch_count", Integer.class)));
    return new FinanceCostAllocationBatchPage(items, p, s, total, totals);
  }

  /** 一个批次的详情（批次 + 分摊明细）。 */
  public FinanceCostAllocationBatchDetail batchDetail(UUID batchId) {
    var batch = batch(batchId);
    var results =
        sql.fetch(
                "select * from finance_cost_allocation_result where batch_id = ? order by target_code",
                batchId)
            .map(this::result);
    return new FinanceCostAllocationBatchDetail(batch, results);
  }

  // ================================================================ 分摊结果（明细）

  /** 分摊结果列表（真分页 + 真筛选 + 汇总覆盖整个筛选结果集）。 */
  public FinanceCostAllocationResultPage results(
      UUID poolId, UUID batchId, String dimension, Boolean basisAvailable, int page, int size) {
    int p = Math.max(0, page);
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    if (batchId != null) {
      where.add("r.batch_id = ?");
      binds.add(batchId);
    }
    if (poolId != null) {
      where.add("b.pool_id = ?");
      binds.add(poolId);
    }
    if (dimension != null && !dimension.isBlank()) {
      where.add("r.target_dimension = ?");
      binds.add(dimension.trim());
    }
    if (basisAvailable != null) {
      where.add("r.basis_available = ?");
      binds.add(basisAvailable);
    }
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    long total =
        sql.fetchOne(
                "select count(*) from finance_cost_allocation_result r"
                    + " join finance_cost_allocation_batch b on b.tenant_id = r.tenant_id and b.id = r.batch_id"
                    + clause,
                binds.toArray())
            .get(0, Long.class);
    var items =
        sql.fetch(
                "select r.* from finance_cost_allocation_result r"
                    + " join finance_cost_allocation_batch b on b.tenant_id = r.tenant_id and b.id = r.batch_id"
                    + clause
                    + " order by r.created_at desc, r.target_code limit ? offset ?",
                concat(binds, s, p * s))
            .map(this::result);
    var totals =
        sql.fetchOne(
            "select coalesce(sum(r.allocated_amount),0) as total_amount,"
                + " coalesce(sum(r.rounding_adjustment),0) as rounding_difference"
                + " from finance_cost_allocation_result r"
                + " join finance_cost_allocation_batch b on b.tenant_id = r.tenant_id and b.id = r.batch_id"
                + clause,
            binds.toArray());
    return new FinanceCostAllocationResultPage(
        items,
        p,
        s,
        total,
        totals.get("total_amount", BigDecimal.class),
        totals.get("rounding_difference", BigDecimal.class));
  }

  // ================================================================ 筛选项

  /** 筛选项下拉（全部来自真实数据）。 */
  public FinanceCostAllocationOptions options() {
    var poolKeys =
        sql.fetch("select distinct pool_key from finance_cost_collection_pool order by pool_key")
            .map(r -> r.get(0, String.class));
    var ruleCodes =
        sql.fetch("select distinct rule_code from finance_cost_allocation_rule order by rule_code")
            .map(r -> r.get(0, String.class));
    var currencies =
        sql.fetch("select distinct currency from finance_cost_collection_pool order by currency")
            .map(r -> r.get(0, String.class));
    return new FinanceCostAllocationOptions(
        List.of(AllocationDimension.values()),
        List.of(AllocationBasis.values()),
        List.of(RoundingPolicy.values()),
        poolKeys,
        ruleCodes,
        currencies);
  }

  // ================================================================ 采集实现

  /** 采集出来的一个分摊对象（还没落库的那一份）。 */
  private record CollectedTarget(
      String code,
      String name,
      BigDecimal quantity,
      BigDecimal amount,
      BigDecimal weight,
      BigDecimal volume,
      String source,
      String note,
      LocalDate date) {}

  /** 一次采集的结果（对象 + 跳过计数 + 原因）。 */
  private record CollectOutcome(
      List<CollectedTarget> targets, int skipped, List<String> notes) {}

  /** 订单 / 店铺维度的对象：来自 trade 平台订单（9.1.2.1）。 */
  private CollectOutcome collectOrderTargets(PoolView pool, String bearer) {
    var notes = new ArrayList<String>();
    var scan = scanPlatformOrders(pool, bearer);
    var targets = new ArrayList<CollectedTarget>();
    for (var view : scan.views()) {
      targets.add(
          new CollectedTarget(
              view.orderNo(),
              view.orderNo(),
              null,
              view.amount(),
              null,
              null,
              "TRADE_PLATFORM_ORDER",
              view.amount() == null || view.amount().signum() <= 0
                  ? "订单总额为 0，金额基数不可得"
                  : "",
              view.placedAt().atZoneSameInstant(ZoneOffset.UTC).toLocalDate()));
    }
    if (targets.isEmpty()) {
      notes.add(
          "这个池的归属期间（"
              + pool.periodStart()
              + " ~ "
              + pool.periodEnd()
              + "）内、币种 "
              + pool.currency()
              + " 的平台订单为零 —— 没有订单就没有订单维度的分摊对象");
    }
    return new CollectOutcome(targets, scan.skipped(), notes);
  }

  /** 店铺维度的对象：平台订单按店铺汇总 + channel 店铺主数据取名字。 */
  private CollectOutcome collectShopTargets(PoolView pool, String bearer) {
    var notes = new ArrayList<String>();
    var scan = scanPlatformOrders(pool, bearer);
    var skipped = scan.skipped();

    var shopAmounts = new LinkedHashMap<String, BigDecimal>();
    var shopDates = new LinkedHashMap<String, LocalDate>();
    for (var view : scan.views()) {
      var shop = view.shopCode();
      if (shop == null || shop.isBlank()) {
        skipped++;
        continue;
      }
      shopAmounts.merge(shop, view.amount() == null ? BigDecimal.ZERO : view.amount(), BigDecimal::add);
      shopDates.put(shop, view.placedAt().atZoneSameInstant(ZoneOffset.UTC).toLocalDate());
    }

    var names = new LinkedHashMap<String, String>();
    try {
      for (var shop : shops.shops(bearer)) {
        if (shop.code() != null) names.put(shop.code(), shop.name() == null ? shop.code() : shop.name());
      }
    } catch (ResponseStatusException e) {
      notes.add("店铺主数据读不到（" + e.getMessage() + "）—— 店铺名用店铺编码顶着");
    }

    var targets = new ArrayList<CollectedTarget>();
    for (var entry : shopAmounts.entrySet()) {
      var code = entry.getKey();
      targets.add(
          new CollectedTarget(
              code,
              names.getOrDefault(code, code),
              null,
              entry.getValue(),
              null,
              null,
              "TRADE_PLATFORM_ORDER",
              entry.getValue().signum() <= 0 ? "这个店铺在期间内的订单总额为 0，金额基数不可得" : "",
              shopDates.getOrDefault(code, pool.periodEnd())));
    }
    if (targets.isEmpty()) {
      notes.add(
          "这个池的归属期间内、币种 "
              + pool.currency()
              + " 的平台订单为零 —— 没有订单就没有店铺维度的分摊对象");
    }
    return new CollectOutcome(targets, skipped, notes);
  }

  /** 一行平台订单（只声明本模块真正用到的字段）。 */
  private record PlatformOrderView(
      String orderNo, String shopCode, String currency, BigDecimal amount, OffsetDateTime placedAt) {}

  /** 扫一遍上游平台订单：按币种与归属期间过滤（**绝不跨币种相加**）。 */
  private OrderScan scanPlatformOrders(PoolView pool, String bearer) {
    var views = new ArrayList<PlatformOrderView>();
    int skipped = 0;
    var from = pool.periodStart().atStartOfDay().atOffset(ZoneOffset.UTC);
    var to = pool.periodEnd().plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);

    for (int page = 0; page < MAX_ORDER_PAGES; page++) {
      var result = platformOrders.orders(page, bearer);
      if (result.rows().isEmpty()) break;
      for (var row : result.rows()) {
        if (!pool.currency().equals(row.currency())) {
          skipped++;
          continue;
        }
        if (row.platformPlacedAt() == null
            || row.platformPlacedAt().isBefore(from)
            || !row.platformPlacedAt().isBefore(to)) {
          skipped++;
          continue;
        }
        if ("CANCELLED".equals(row.normalizedStatus())) {
          skipped++;
          continue;
        }
        views.add(
            new PlatformOrderView(
                row.orderNo(),
                row.shopCode(),
                row.currency(),
                row.platformGrandTotalAmount(),
                row.platformPlacedAt()));
      }
      if (result.rows().size() < 200) break;
    }
    return new OrderScan(views, skipped);
  }

  /** 一次平台订单扫描的结果。 */
  private record OrderScan(List<PlatformOrderView> views, int skipped) {}

  /** SKU / SPU / 类目维度的对象：trade 销售订单行 + catalog SKU 主数据与包装档案。 */
  private CollectOutcome collectSkuFamilyTargets(
      PoolView pool, AllocationDimension dimension, String bearer) {
    var notes = new ArrayList<String>();
    var skipped = 0;

    // 1) 真实卖出的订单行 → 每个 SKU 的数量与金额。
    var quantity = new LinkedHashMap<UUID, BigDecimal>();
    var amount = new LinkedHashMap<UUID, BigDecimal>();
    var skuCode = new LinkedHashMap<UUID, String>();
    var skuName = new LinkedHashMap<UUID, String>();
    var skuDate = new LinkedHashMap<UUID, LocalDate>();
    var from = pool.periodStart().atStartOfDay().atOffset(ZoneOffset.UTC);
    var to = pool.periodEnd().plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);

    for (int page = 0; page < MAX_ORDER_PAGES; page++) {
      var page1 = salesOrders.orders(page, pool.currency(), from, to, bearer);
      if (page1.rows().isEmpty()) break;
      for (var order : page1.rows()) {
        var lines = salesOrders.lines(order.id(), bearer);
        var date =
            order.submittedAt() == null
                ? pool.periodEnd()
                : order.submittedAt().atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
        for (var line : lines) {
          if (line.skuId() == null) {
            skipped++;
            continue;
          }
          quantity.merge(
              line.skuId(),
              line.quantity() == null ? BigDecimal.ZERO : line.quantity(),
              BigDecimal::add);
          amount.merge(
              line.skuId(),
              line.lineAmount() == null ? BigDecimal.ZERO : line.lineAmount(),
              BigDecimal::add);
          if (line.skuCode() != null) skuCode.put(line.skuId(), line.skuCode());
          if (line.skuName() != null) skuName.put(line.skuId(), line.skuName());
          skuDate.put(line.skuId(), date);
        }
      }
      if (page1.rows().size() < 200) break;
    }
    if (quantity.isEmpty()) {
      notes.add(
          "这个池的归属期间（"
              + pool.periodStart()
              + " ~ "
              + pool.periodEnd()
              + "）内、币种 "
              + pool.currency()
              + " 的销售订单行为零 —— 没有卖出记录就没有这些维度的分摊对象");
      return new CollectOutcome(List.of(), skipped, notes);
    }

    // 2) catalog 的 SKU → SPU / 类目映射。
    var catalogRows = new LinkedHashMap<UUID, CatalogSkuPort.CatalogSkuRow>();
    for (int page = 0; page < MAX_SKU_PAGES; page++) {
      var page1 = catalog.skus(page, bearer);
      if (page1.items().isEmpty()) break;
      for (var row : page1.items()) {
        catalogRows.put(row.id(), row);
      }
      if (page1.items().size() < 200) break;
    }

    // 3) 每个 SKU 的包装重量 / 体积（catalog 拥有包装事实）。
    var weight = new LinkedHashMap<UUID, BigDecimal>();
    var volume = new LinkedHashMap<UUID, BigDecimal>();
    var weightMissing = new LinkedHashMap<UUID, String>();
    int detail = 0;
    for (var skuId : quantity.keySet()) {
      if (detail >= MAX_SKU_DETAIL) {
        weightMissing.put(skuId, "超出单次取数上限（" + MAX_SKU_DETAIL + " 个 SKU），包装重量未取到");
        continue;
      }
      detail++;
      try {
        var sku = catalog.sku(skuId, bearer);
        if (sku.packaging() == null) {
          weightMissing.put(skuId, "catalog 没维护这个 SKU 的包装重量 / 体积");
          continue;
        }
        if (sku.packaging().grossWeightG() != null) {
          weight.put(skuId, sku.packaging().grossWeightG());
        } else {
          weightMissing.put(skuId, "catalog 没维护这个 SKU 的包装重量");
        }
        if (sku.packaging().volumeCm3() != null) {
          volume.put(skuId, sku.packaging().volumeCm3());
        }
      } catch (ResponseStatusException e) {
        weightMissing.put(skuId, "包装档案读不到（" + e.getMessage() + "）");
      }
    }

    if (dimension == AllocationDimension.SKU) {
      var targets = new ArrayList<CollectedTarget>();
      for (var entry : quantity.entrySet()) {
        var id = entry.getKey();
        var code = skuCode.getOrDefault(id, id.toString());
        var name = skuName.getOrDefault(id, code);
        var note = weightMissing.getOrDefault(id, "");
        targets.add(
            new CollectedTarget(
                code,
                name,
                entry.getValue(),
                amount.getOrDefault(id, BigDecimal.ZERO),
                weight.get(id),
                volume.get(id),
                "TRADE_SALES_ORDER_LINE",
                note,
                skuDate.getOrDefault(id, pool.periodEnd())));
      }
      return new CollectOutcome(targets, skipped, notes);
    }

    // 4) SPU / 类目：由 SKU 汇总。
    var buckets = new LinkedHashMap<String, List<UUID>>();
    var bucketNames = new LinkedHashMap<String, String>();
    for (var id : quantity.keySet()) {
      var row = catalogRows.get(id);
      String bucket;
      String bucketName;
      if (dimension == AllocationDimension.SPU) {
        if (row == null || row.spuId() == null) {
          skipped++;
          notes.add("SKU " + skuCode.getOrDefault(id, id.toString()) + " 在 catalog 里没有 SPU 归属，跳过");
          continue;
        }
        bucket = row.spuId().toString();
        bucketName = row.spuCode() == null ? bucket : row.spuCode();
      } else {
        if (row == null || row.category() == null || row.category().isBlank()) {
          skipped++;
          notes.add(
              "SKU "
                  + skuCode.getOrDefault(id, id.toString())
                  + " 在 catalog 里没有类目归属，跳过");
          continue;
        }
        bucket = row.category();
        bucketName = row.category();
      }
      buckets.computeIfAbsent(bucket, k -> new ArrayList<>()).add(id);
      bucketNames.put(bucket, bucketName);
    }

    var targets = new ArrayList<CollectedTarget>();
    for (var entry : buckets.entrySet()) {
      var members = entry.getValue();
      var q = BigDecimal.ZERO;
      var a = BigDecimal.ZERO;
      var w = BigDecimal.ZERO;
      var v = BigDecimal.ZERO;
      boolean weightComplete = true;
      boolean volumeComplete = true;
      String missingNote = "";
      for (var id : members) {
        q = q.add(quantity.getOrDefault(id, BigDecimal.ZERO));
        a = a.add(amount.getOrDefault(id, BigDecimal.ZERO));
        if (weight.containsKey(id)) {
          w = w.add(weight.get(id));
        } else {
          weightComplete = false;
          if (missingNote.isEmpty()) {
            missingNote =
                "构成 SKU 中有未维护包装重量 / 体积的（"
                    + skuCode.getOrDefault(id, id.toString())
                    + "）—— 少算的和不叫缺失，叫算错，所以这一档整体不可得";
          }
        }
        if (volume.containsKey(id)) {
          v = v.add(volume.get(id));
        } else {
          volumeComplete = false;
        }
      }
      targets.add(
          new CollectedTarget(
              entry.getKey(),
              bucketNames.getOrDefault(entry.getKey(), entry.getKey()),
              q,
              a,
              weightComplete ? w : null,
              volumeComplete ? v : null,
              "TRADE_SALES_ORDER_LINE",
              missingNote,
              pool.periodEnd()));
    }
    return new CollectOutcome(targets, skipped, notes);
  }

  // ================================================================ 分摊算法

  /** 一行分摊结果（还没落库）。 */
  private record PlanLine(
      String targetCode,
      String targetName,
      BigDecimal basisValue,
      boolean basisAvailable,
      BigDecimal shareRatio,
      BigDecimal exactAmount,
      BigDecimal allocatedAmount,
      BigDecimal roundingAdjustment,
      boolean isRoundingTarget,
      String basisNote) {}

  /** 一次分摊的算好的方案。 */
  private record Plan(
      List<PlanLine> lines,
      List<FinanceCostAllocationTarget> participants,
      List<FinanceCostAllocationTarget> unavailable,
      BigDecimal roundingDifference,
      String roundingTarget) {}

  /**
   * 算出分摊方案。
   *
   * <p>🔴 算法核心：
   *
   * <ol>
   *   <li><b>向下取整</b>：每个参与对象先 {@code trunc(理论额, 4)} ⇒ 每一行都 ≥ 0，
   *       且 Σfloor ≤ 总额（零头是正的，不会出现负成本）；
   *   <li><b>补零头</b>：剩下的零头（&lt; 参与对象数 × 0.0001）按舍入差策略补给指定对象 ——
   *       零头**不消失**，逐行记在 {@code rounding_adjustment} 上；
   *   <li><b>Σ 恒等于被分摊总额</b>。
   * </ol>
   */
  private Plan plan(
      List<FinanceCostAllocationTarget> targets,
      FinanceCostAllocationRule rule,
      BigDecimal total) {
    var participants = new ArrayList<FinanceCostAllocationTarget>();
    var unavailable = new ArrayList<FinanceCostAllocationTarget>();
    for (var target : targets) {
      if (target.targetDimension() != rule.targetDimension()) continue;
      var value = basisOf(rule.allocationBasis(), target);
      if (value == null || value.signum() <= 0) {
        unavailable.add(target);
      } else {
        participants.add(target);
      }
    }
    participants.sort(Comparator.comparing(FinanceCostAllocationTarget::targetCode));
    unavailable.sort(Comparator.comparing(FinanceCostAllocationTarget::targetCode));

    // 🔴 全部对象都取不到基数 ⇒ 不摊（摊出一张全 0 的单会让下游以为这个池不用花钱）。
    // 这里先返回空方案，由调用方给出「基数取不到」的明确错误。
    if (participants.isEmpty()) {
      return new Plan(List.of(), List.of(), unavailable, BigDecimal.ZERO.setScale(MONEY_SCALE), null);
    }

    var totalBasis = BigDecimal.ZERO;
    for (var target : participants) {
      totalBasis = totalBasis.add(basisOf(rule.allocationBasis(), target));
    }

    // 理论额（10 位小数，看得见舍入前是多少）。
    var exact = new LinkedHashMap<String, BigDecimal>();
    var floor = new LinkedHashMap<String, BigDecimal>();
    var remainder = new LinkedHashMap<String, BigDecimal>();
    for (var target : participants) {
      var value = basisOf(rule.allocationBasis(), target);
      var e = total.multiply(value).divide(totalBasis, RATIO_SCALE, RoundingMode.HALF_UP);
      exact.put(target.targetCode(), e);
      var f = e.setScale(MONEY_SCALE, RoundingMode.FLOOR);
      floor.put(target.targetCode(), f);
      remainder.put(target.targetCode(), e.subtract(f));
    }

    var sumFloor = BigDecimal.ZERO;
    for (var f : floor.values()) {
      sumFloor = sumFloor.add(f);
    }
    var leftover = total.subtract(sumFloor).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    var units = leftover.movePointRight(4).intValueExact();

    // 按策略决定零头补给谁。
    var order = new ArrayList<FinanceCostAllocationTarget>(participants);
    String designated = null;
    switch (rule.roundingPolicy()) {
      case LARGEST_REMAINDER -> {
        order.sort(
            Comparator.comparing((FinanceCostAllocationTarget t) -> remainder.get(t.targetCode()))
                .reversed()
                .thenComparing(FinanceCostAllocationTarget::targetCode));
      }
      case SMALLEST_REMAINDER ->
          order.sort(
              Comparator.comparing((FinanceCostAllocationTarget t) -> remainder.get(t.targetCode()))
                  .thenComparing(FinanceCostAllocationTarget::targetCode));
      case FIRST_TARGET -> {
        order.sort(Comparator.comparing(FinanceCostAllocationTarget::targetCode));
        designated = order.get(0).targetCode();
      }
      case LAST_TARGET -> {
        order.sort(Comparator.comparing(FinanceCostAllocationTarget::targetCode));
        designated = order.get(order.size() - 1).targetCode();
      }
      case SPECIFIED -> {
        var wanted = rule.roundingTargetCode();
        var hit =
            participants.stream()
                .filter(t -> t.targetCode().equals(wanted))
                .findFirst()
                .orElseThrow(
                    () ->
                        new ResponseStatusException(
                            HttpStatus.UNPROCESSABLE_ENTITY,
                            "规则「"
                                + rule.ruleCode()
                                + "」指定的零头落点「"
                                + wanted
                                + "」不在这个池的分摊对象集合里 —— 零头无处可落"));
        designated = hit.targetCode();
      }
    }

    var adjustments = new LinkedHashMap<String, BigDecimal>();
    for (var target : participants) {
      adjustments.put(target.targetCode(), BigDecimal.ZERO);
    }
    int left = units;
    if (designated != null) {
      // 策略点名了一个对象：零头**全部**给它（"保留舍入差额到指定对象"）。
      adjustments.put(designated, adjustments.get(designated).add(leftover));
      left = 0;
    } else {
      // 最大 / 最小余数：逐个 0.0001 地补（经典最大余数法）。
      var cursor = 0;
      while (left > 0 && !order.isEmpty()) {
        var target = order.get(cursor % order.size());
        adjustments.put(
            target.targetCode(), adjustments.get(target.targetCode()).add(UNIT));
        left--;
        cursor++;
      }
      if (left > 0) {
        throw new ResponseStatusException(
            HttpStatus.INTERNAL_SERVER_ERROR, "零头没有补完 —— 分摊结果会与被分摊总额对不上");
      }
      for (var target : order) {
        if (adjustments.get(target.targetCode()).signum() > 0) {
          designated = target.targetCode();
          break;
        }
      }
    }

    var lines = new ArrayList<PlanLine>();
    for (var target : participants) {
      var code = target.targetCode();
      var value = basisOf(rule.allocationBasis(), target);
      var adjustment = adjustments.get(code);
      lines.add(
          new PlanLine(
              code,
              target.targetName(),
              value,
              true,
              value.divide(totalBasis, RATIO_SCALE, RoundingMode.HALF_UP),
              exact.get(code),
              floor.get(code).add(adjustment),
              adjustment,
              code.equals(designated),
              target.basisNote()));
    }
    for (var target : unavailable) {
      lines.add(
          new PlanLine(
              target.targetCode(),
              target.targetName(),
              BigDecimal.ZERO,
              false,
              BigDecimal.ZERO,
              BigDecimal.ZERO,
              BigDecimal.ZERO,
              BigDecimal.ZERO,
              false,
              target.basisNote().isBlank()
                  ? "基数（" + rule.allocationBasis().label() + "）在这个对象上取不到"
                  : target.basisNote()));
    }
    return new Plan(lines, participants, unavailable, leftover, designated);
  }

  /** 取基数：等额口径恒为 1；其余口径取对象上的对应列（取不到就是 null）。 */
  private static BigDecimal basisOf(AllocationBasis basis, FinanceCostAllocationTarget target) {
    return switch (basis) {
      case EQUAL -> BigDecimal.ONE;
      case QUANTITY -> target.basisQuantity();
      case AMOUNT -> target.basisAmount();
      case WEIGHT -> target.basisWeight();
      case VOLUME -> target.basisVolume();
    };
  }

  // ================================================================ 行映射

  private FinanceCostAllocationRule rule(Record r) {
    return new FinanceCostAllocationRule(
        r.get("id", UUID.class),
        r.get("rule_code", String.class),
        r.get("rule_name", String.class),
        AllocationDimension.valueOf(r.get("target_dimension", String.class)),
        AllocationBasis.valueOf(r.get("allocation_basis", String.class)),
        r.get("pool_key", String.class),
        r.get("priority", Integer.class),
        RoundingPolicy.valueOf(r.get("rounding_policy", String.class)),
        r.get("rounding_target_type", String.class) == null
            ? null
            : AllocationDimension.valueOf(r.get("rounding_target_type", String.class)),
        r.get("rounding_target_code", String.class),
        r.get("effective_from", LocalDate.class),
        r.get("effective_to", LocalDate.class),
        RuleStatus.valueOf(r.get("status", String.class)),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private FinanceCostAllocationTarget target(Record r) {
    return new FinanceCostAllocationTarget(
        r.get("id", UUID.class),
        r.get("pool_id", UUID.class),
        AllocationDimension.valueOf(r.get("target_dimension", String.class)),
        r.get("target_type", String.class),
        r.get("target_code", String.class),
        r.get("target_name", String.class),
        r.get("basis_quantity", BigDecimal.class),
        r.get("basis_amount", BigDecimal.class),
        r.get("basis_weight", BigDecimal.class),
        r.get("basis_volume", BigDecimal.class),
        r.get("basis_source", String.class),
        r.get("basis_note", String.class),
        r.get("target_date", LocalDate.class),
        r.get("collected_at", OffsetDateTime.class));
  }

  private FinanceCostAllocationBatch batch(Record r) {
    return new FinanceCostAllocationBatch(
        r.get("id", UUID.class),
        r.get("pool_id", UUID.class),
        r.get("pool_key", String.class),
        r.get("pool_name", String.class),
        r.get("rule_id", UUID.class),
        r.get("rule_code", String.class),
        r.get("rule_name", String.class),
        r.get("currency", String.class),
        r.get("source_total", BigDecimal.class),
        r.get("allocated_total", BigDecimal.class),
        r.get("rounding_difference", BigDecimal.class),
        r.get("rounding_target_type", String.class) == null
            ? null
            : AllocationDimension.valueOf(r.get("rounding_target_type", String.class)),
        r.get("rounding_target_code", String.class),
        r.get("target_count", Integer.class),
        r.get("basis_unavailable_count", Integer.class),
        BatchStatus.valueOf(r.get("status", String.class)),
        r.get("allocated_at", OffsetDateTime.class),
        r.get("reverted_at", OffsetDateTime.class),
        r.get("note", String.class));
  }

  private FinanceCostAllocationResult result(Record r) {
    return new FinanceCostAllocationResult(
        r.get("id", UUID.class),
        r.get("batch_id", UUID.class),
        AllocationDimension.valueOf(r.get("target_dimension", String.class)),
        r.get("target_code", String.class),
        r.get("target_name", String.class),
        r.get("basis_value", BigDecimal.class),
        r.get("basis_available", Boolean.class),
        r.get("share_ratio", BigDecimal.class),
        r.get("exact_amount", BigDecimal.class),
        r.get("allocated_amount", BigDecimal.class),
        r.get("rounding_adjustment", BigDecimal.class),
        r.get("is_rounding_target", Boolean.class),
        r.get("basis_note", String.class));
  }

  private FinanceCostAllocationPool pool(Record r) {
    return new FinanceCostAllocationPool(
        r.get("id", UUID.class),
        r.get("pool_key", String.class),
        r.get("pool_name", String.class),
        r.get("dimension", String.class),
        r.get("period_start", LocalDate.class),
        r.get("period_end", LocalDate.class),
        r.get("currency", String.class),
        r.get("total_amount", BigDecimal.class),
        r.get("entry_count", Integer.class),
        r.get("allocation_available", Boolean.class),
        r.get("target_count", Integer.class) == null ? 0 : r.get("target_count", Integer.class),
        r.get("batch_id", UUID.class),
        r.get("batch_status", String.class));
  }

  // ================================================================ 内部取值

  /** 费用池的一行（12.4.2.1 的池，同服务直读）。 */
  private record PoolView(
      UUID id,
      String poolKey,
      String poolName,
      LocalDate periodStart,
      LocalDate periodEnd,
      String currency,
      BigDecimal totalAmount,
      boolean allocationAvailable) {}

  private PoolView poolRow(UUID id) {
    var row =
        sql.fetchOne("select * from finance_cost_collection_pool where id = ?", id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "费用池不存在或不属于本租户");
    }
    return new PoolView(
        row.get("id", UUID.class),
        row.get("pool_key", String.class),
        row.get("pool_name", String.class),
        row.get("period_start", LocalDate.class),
        row.get("period_end", LocalDate.class),
        row.get("currency", String.class),
        row.get("total_amount", BigDecimal.class),
        row.get("allocation_available", Boolean.class));
  }

  /** 按分摊顺序挑一条命中规则（启用中 + 池标识匹配 + 生效区间覆盖池的归属期间）。 */
  private FinanceCostAllocationRule selectRule(PoolView pool) {
    var row =
        sql.fetchOne(
            "select * from finance_cost_allocation_rule where status = 'ACTIVE'"
                + " and (pool_key = ? or pool_key = ?) and effective_from <= ?::date"
                + " and (effective_to is null or effective_to >= ?::date)"
                + " order by priority, effective_from desc, rule_code limit 1",
            ANY_POOL,
            pool.poolKey(),
            pool.periodStart(),
            pool.periodEnd());
    return row == null ? null : rule(row);
  }

  private FinanceCostAllocationRule rule(UUID id) {
    var row = sql.fetchOne("select * from finance_cost_allocation_rule where id = ?", id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分摊规则不存在或不属于本租户");
    }
    return rule(row);
  }

  private FinanceCostAllocationBatch batch(UUID id) {
    var row =
        sql.fetchOne(
            "select b.*, p.pool_key, p.pool_name, r.rule_code, r.rule_name"
                + " from finance_cost_allocation_batch b"
                + " join finance_cost_collection_pool p on p.tenant_id = b.tenant_id and p.id = b.pool_id"
                + " join finance_cost_allocation_rule r on r.tenant_id = b.tenant_id and r.id = b.rule_id"
                + " where b.id = ?",
            id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分摊批次不存在或不属于本租户");
    }
    return batch(row);
  }

  // ================================================================ 工具

  private UUID currentTenant() {
    var raw =
        sql.fetchOne("select nullif(current_setting('app.current_tenant', true), '')::uuid as t")
            .get("t", UUID.class);
    if (raw == null) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "租户上下文缺失：分摊必须在绑定租户的事务里执行");
    }
    return raw;
  }

  private static String text(String value) {
    return value == null ? "" : value;
  }

  private static String poolKey(FinanceCostAllocationRuleInput input) {
    return input.poolKey() == null || input.poolKey().isBlank() ? ANY_POOL : input.poolKey().trim();
  }

  private static String roundingTargetType(FinanceCostAllocationRuleInput input) {
    var policy =
        input.roundingPolicy() == null ? RoundingPolicy.LARGEST_REMAINDER : input.roundingPolicy();
    return policy == RoundingPolicy.SPECIFIED && input.roundingTargetType() != null
        ? input.roundingTargetType().name()
        : null;
  }

  private static String roundingTargetCode(FinanceCostAllocationRuleInput input) {
    var policy =
        input.roundingPolicy() == null ? RoundingPolicy.LARGEST_REMAINDER : input.roundingPolicy();
    return policy == RoundingPolicy.SPECIFIED && input.roundingTargetCode() != null
        ? input.roundingTargetCode().trim()
        : null;
  }

  private static Object[] concat(List<Object> binds, Object... more) {
    var all = new ArrayList<Object>(binds);
    all.addAll(List.of(more));
    return all.toArray();
  }
}
