package com.starcon.warehouse;

import static com.starcon.warehouse.StockoutAlertModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 7.8.2.2 缺货与饥饿预警。
 *
 * <p>蓝图原话：「识别因分摊/优先级导致的店铺"饥饿"（长期分不到货、影响绩效），以及真正的全网缺货，区分两者给出不同处置建议。输入：分摊结果；店铺销售与绩效；总可用量。输出：饥饿店铺清单 + 缺货清单 + 建议（调优先级/调保留/补货）。」以及关系段的红线：「**"没货卖"和"没分到货"是两种问题**，处置完全不同，不能混在一个缺货告警里。」
 *
 * <p><b>六条实现纪律</b>：
 *
 * <ul>
 *   <li><b>两类问题在 schema、端点、枚举、建议四个层面都是分开的</b>：
 *       {@code starvation_alert} / {@code stockout_alert} 两张表、两个列表端点、
 *       两套状态枚举、两套建议生成逻辑。**没有**「一张表 + 一个 type 列」——
 *       那正是「混在一个缺货告警里」。
 *   <li><b>分界线是「同期有没有货可分」</b>：饥饿的成立要件里有一条硬条件 ——
 *       观察窗口内存在一轮 {@code allocation_run.available_quantity > 0} 的分摊。
 *       货在、店分不到 ⇒ 饥饿（调优先级 / 调保底）；货都没有 ⇒ 缺货（补货）。
 *       某 SKU 已**全网缺货**时，它被排除在饥饿清单之外（并计入
 *       {@code excludedByStockoutCount}，让这些店不无声消失）—— 那种情况下
 *       处置是补货，调保底没有意义。
 *   <li><b>「不知道」是一个状态，不是一个 0</b>：账本里查不到库存记录 ⇒
 *       {@code NO_LEDGER_RECORD} 且 {@code availableQuantity = null}；
 *       没有销量登记 ⇒ {@code UNKNOWN_DEMAND} 且 {@code demandUnits = null}。
 *       绝不用 0 顶替 —— 0 会让人去补货，而真相是「我们不知道，该去查」。
 *   <li><b>建议是结构，不是文案</b>：每条建议都带**目标对象**（7.8.1.2 的
 *       {@code allocation_floor} / 7.8.1.1 的 {@code allocation_policy_member} /
 *       7.8.1.1 的 {@code allocation_shop_sales} / 6.2.1.2 的补货需求）、
 *       定位键、当前值、建议值与理由。**本模块只给指针，不替上游改配置** ——
 *       改保底要开新档、改优先级要改名册、补货属 06 采购域（尚未交付）。
 *   <li><b>总可用量只有��个来源</b>：{@code available = on_hand − reserved}，
 *       直接取 {@code stock_bucket.available} 生成列，**不重算第三套公式**。
 *       7.1.1.3 的「可售量」还要再扣安全缓冲并排除待检 / 残次 / 未上架 / 冻结批次，
 *       本模块**不复制那套口径**（7.1.1.3 是全系统单一口径来源）——
 *       所以本模块报的是**账本可用量**，两者不是同一个数，页面同时给出
 *       分摊时的总可用量以便交叉核对。
 *   <li><b>数量一律 numeric(19,6) + BigDecimal</b>，比较一律 {@code compareTo}；
 *       时间一律 {@code ?::timestamptz} 且 cast 不落在绑定序列末位。
 * </ul>
 *
 * <p><b>未交付的上游如实标注</b>：店铺绩效（17 绩效域）与补货（6.2.1.2，06 采购域）
 * 都还没有真相来源，所以能力声明里给 {@code available=false} + {@code ownerModule}，
 * 本模块**不假装能算**「绩效影响」—— 它能算的是「长期分不到货」这个事实。
 */
@Service
public class StockoutAlertService {

  /** 单页最大行数，与 7.8.1.1 / 7.8.1.2 / 7.8.2.1 同一上限与理由。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 观察窗口的默认轮次数：每个 SKU 各取最近 N 轮。 */
  static final int DEFAULT_WINDOW_ROUNDS = 5;

  /** 观察窗口的最大轮次数。上限是有的 —— 轮次太多时单次评估的扫描量会失控。 */
  static final int MAX_WINDOW_ROUNDS = 50;

  /** 覆盖率阈值默认值：累计分摊量 ÷ 需求量低于它算供给不足。 */
  static final BigDecimal DEFAULT_COVERAGE_THRESHOLD = new BigDecimal("0.5");

  /** 零分配轮次占比阈值默认值：窗口内分到 0 件的轮次占比高于它算「长期」。 */
  static final BigDecimal DEFAULT_ZERO_ROUND_RATIO = new BigDecimal("0.5");

  /** 需求窗口天数默认值（当 SKU 没有分摊轮次可参照时用）。 */
  static final int DEFAULT_DEMAND_WINDOW_DAYS = 30;

  /** 单次评估允许扫描的最大组合数。超过就是「这个租户的数据规模已经不适合整扫」。 */
  static final int MAX_SCAN_ROWS = 20_000;

  /** numeric(19,6) 口径下的零。 */
  private static final BigDecimal ZERO = new BigDecimal("0.000000");

  private final DSLContext sql;

  public StockoutAlertService(DSLContext sql) {
    this.sql = sql;
  }

  // ============================================================ 能力声明

  /**
   * 能力清单：跨服务边界、两套判定依据的差别、「不知道」的语义、建议的边界。
   *
   * <p>前端据此渲染文案与状态色，而不是把规则硬编码在界面上。
   */
  public StockoutAlertCapabilities capabilities() {
    var dimensions =
        List.of(
            new StockoutAlertCapabilityDimension(
                "分摊结果",
                "店铺饥饿的判定依据：分摊轮次、分摊量、分摊次序与结论（7.8.1.1 的 allocation_run / allocation_run_line）",
                true,
                "warehouse.allocation_run / allocation_run_line",
                "",
                "缺了它就没有「长期分不到货」这个事实 —— 那时饥饿清单整体不可判"),
            new StockoutAlertCapabilityDimension(
                "分摊优先级与保底",
                "它到底是被什么挤下去的：成员优先级、保底承诺、保底缺口（7.8.1.1 名册 + 7.8.1.2 保底）",
                true,
                "warehouse.allocation_policy_member / allocation_floor / allocation_priority_resolution",
                "",
                "缺了它只能答「谁没分到」，答不出「为什么」—— 而「为什么」才是处置的入口"),
            new StockoutAlertCapabilityDimension(
                "店铺销量",
                "需求侧的真相来源：没有它就判不出「饿」（7.8.1.1 的 allocation_shop_sales）",
                true,
                "warehouse.allocation_shop_sales",
                "",
                "缺了它只能看到「分到 0 件」，答不出「该不该分到」—— 于是判 UNKNOWN_DEMAND 而不是 0"),
            new StockoutAlertCapabilityDimension(
                "总可用量",
                "缺货的判定依据：账本可用量 = on_hand − reserved（stock_bucket 的生成列，没有第三个来源）",
                true,
                "warehouse.stock_bucket",
                "",
                "缺了它就答不出「全网还有没有货」—— 而那正是缺货与饥饿的分界线"),
            new StockoutAlertCapabilityDimension(
                "店铺绩效",
                "蓝图输入里的「店铺销售与绩效」中的**绩效**部分：店铺考核指标、平台考核、断货天数",
                false,
                "",
                "17",
                "本模块能算「长期分不到货」这个**事实**，但算不出它对绩效的影响："
                    + "缺了绩效，影响面只能标成「不知道」，不能编一个影响分值出来"),
            new StockoutAlertCapabilityDimension(
                "补货执行",
                "把「缺 N 件」变成一张采购需求单",
                false,
                "",
                "6.2.1.2",
                "补货需求单属 06 采购域，尚未交付：本模块只给出**缺口件数与销量窗口**，"
                    + "不落采购单、不跨库写"),
            new StockoutAlertCapabilityDimension(
                "工作台预警",
                "把预警推送到 1 工作台的待办",
                false,
                "",
                "1",
                "1 工作台尚未交付：本模块的预警只在本服务可查，页面直接读本服务的两张表"));
    return new StockoutAlertCapabilities(
        dimensions,
        List.of(
            StarvationState.STARVED.name(),
            StarvationState.UNDER_SUPPLIED.name(),
            StarvationState.UNKNOWN_DEMAND.name(),
            StarvationState.HEALTHY.name()),
        List.of(
            StockoutState.NO_LEDGER_RECORD.name(),
            StockoutState.NETWORK_STOCKED_OUT.name(),
            StockoutState.WAREHOUSE_STOCKED_OUT.name(),
            StockoutState.AT_RISK.name(),
            StockoutState.UNKNOWN_DEMAND.name(),
            StockoutState.HEALTHY.name()),
        List.of(Severity.CRITICAL.name(), Severity.WARNING.name(), Severity.INFO.name()),
        List.of(
            RecommendationKind.RAISE_FLOOR.name(),
            RecommendationKind.RAISE_PRIORITY.name(),
            RecommendationKind.REGISTER_SALES.name(),
            RecommendationKind.REPLENISH.name(),
            RecommendationKind.NONE.name()),
        List.of(
            LifecycleState.OPEN.name(),
            LifecycleState.ACKNOWLEDGED.name(),
            LifecycleState.RESOLVED.name()),
        false,
        "17",
        false,
        "6.2.1.2",
        false,
        "1",
        "「没货卖」与「没分到货」是两种问题，处置完全不同，本模块刻意**不合并**："
            + "饥饿清单判「同期有货可分却分不到」⇒ 处置是调优先级 / 调保底；"
            + "缺货清单判「总可用量不够」⇒ 处置是补货 / 仓间调拨。"
            + "某 SKU 已全网缺货时，它被排除在饥饿清单之外（并计入 excludedByStockoutCount），"
            + "因为那种情况下处置是补货，调保底没有意义。",
        "「不知道」是一个状态，不是一个 0：账本里查不到这个 SKU 的库存记录 ⇒ "
            + "NO_LEDGER_RECORD 且可用量为 null（0 意味着「一件都没有」，查不到记录意味着「要去查」）；"
            + "没有销量登记 ⇒ UNKNOWN_DEMAND 且需求为 null（0 需求会被读成「不用补货」）。"
            + "店铺绩效未交付，所以「对绩效的影响」一律标成不知道，本模块不编影响分值。",
        "本模块的总可用量 = 账本可用量 = stock_bucket 的 available 生成列（= on_hand − reserved，"
            + "没有第三个来源）。它与 7.1.1.3 的**可售量**不是同一个数：可售量还要扣安全缓冲，"
            + "并排除待检 / 残次 / 未上架 / 冻结批次 —— 本模块**不复制那套口径**"
            + "（7.1.1.3 是全系统单一口径来源）。页面同时给出分摊时的总可用量"
            + "（allocation_run.available_quantity），两者背离说明分摊之后库存动过或口径漂了。",
        "上游：7.8.1.1（分摊结果 / 销量登记 / 名册）、7.8.1.2（优先级与保底）、"
            + "7.2.2.1（可售量口径）、1（库存账本）。全部在本服务内，"
            + "所以本模块**不跨服务读表**；店铺与 SKU 属别的服务，只存逻辑 id + 写入时的可读名快照。",
        "下游：6.2.1.2 补货（尚未交付 —— 本模块给缺口件数与销量窗口，不落采购单）、"
            + "1 工作台预警（尚未交付 —— 预警只在本服务可查）、"
            + "17 绩效（尚未交付 —— 影响面标成不知道）。"
            + "调优先级 / 调保底的落地在 7.8.1.1 / 7.8.1.2：改保底要开新档、改优先级要改名册，"
            + "本模块**只给指针，不替上游改配置**。");
  }

  // ============================================================ 评估

  /**
   * 一次评估：分别判定「缺货」与「饥饿」两类问题，各自落各自的表。
   *
   * <p>两类判定**顺序执行**而不是并行：饥饿的判定要用「该 SKU 是否已全网缺货」
   * 来做排除，而这个事实正是缺货侧刚算出来的。
   */
  @Transactional
  public StockoutEvaluationResult evaluate(StockoutEvaluationRequest request) {
    StockoutEvaluationRequest req = request == null ? new StockoutEvaluationRequest(null, null, null, null, null, null, null) : request;
    int windowRounds = clampRounds(req.windowRounds());
    BigDecimal coverageThreshold = ratio(req.coverageThreshold(), DEFAULT_COVERAGE_THRESHOLD);
    BigDecimal zeroRoundRatio = ratio(req.zeroRoundRatio(), DEFAULT_ZERO_ROUND_RATIO);
    int demandWindowDays = clampDays(req.demandWindowDays());
    OffsetDateTime at = OffsetDateTime.now();

    List<StockoutDetection> stockouts = detectStockouts(at, req.skuId(), req.warehouseId(), windowRounds, demandWindowDays);
    List<StarvationDetection> starvations =
        detectStarvations(
            at, req.skuId(), req.referenceId(), windowRounds, coverageThreshold, zeroRoundRatio);

    // 「全网缺货」的 SKU 集合：饥饿侧据此排除（处置不同：补货 vs 调优先级）。
    Set<UUID> networkStockedOut =
        stockouts.stream()
            .filter(d -> d.state() == StockoutState.NETWORK_STOCKED_OUT)
            .map(StockoutDetection::skuId)
            .collect(java.util.stream.Collectors.toSet());

    int excluded = 0;
    int starvationProblems = 0;
    int unknownDemand = 0;
    for (StarvationDetection d : starvations) {
      if (networkStockedOut.contains(d.skuId())) {
        excluded++;
        continue;
      }
      if (d.state() == StarvationState.UNKNOWN_DEMAND) {
        unknownDemand++;
      }
      // **UNKNOWN_DEMAND 也要落库**：它是「我们不知道这家店饿不饿」这一事实，
      // 落下来才看得见、才能被处置（去登记销量）。只落「有问题的」会让
      // 「判不出来」的那部分在清单上彻底消失 —— 那正是「不知道被当成 0」的老毛病。
      if (d.state() != StarvationState.HEALTHY) {
        starvationProblems++;
        upsertStarvation(d, at);
      }
    }
    closeMissingStarvations(at, req.skuId(), req.referenceId());

    int stockoutProblems = 0;
    for (StockoutDetection d : stockouts) {
      if (d.state() == StockoutState.UNKNOWN_DEMAND) {
        unknownDemand++;
      }
      if (d.isProblem()) {
        stockoutProblems++;
        upsertStockout(d, at);
      }
    }
    closeMissingStockouts(at, req.skuId(), req.warehouseId());

    return new StockoutEvaluationResult(
        at,
        windowRounds,
        stockouts.size(),
        starvations.size(),
        stockoutProblems,
        starvationProblems,
        excluded,
        unknownDemand,
        "评估口径：观察每个 SKU 最近 "
            + windowRounds
            + " 轮分摊；覆盖率阈值 "
            + coverageThreshold.stripTrailingZeros().toPlainString()
            + "；零分配轮次占比阈值 "
            + zeroRoundRatio.stripTrailingZeros().toPlainString()
            + "。饥饿清单已排除全网缺货的 SKU（"
            + excluded
            + " 个店铺组合）—— 那类问题的处置是补货，不是调优先级。");
  }

  // ============================================================ 清单

  /** 店铺饥饿清单（真分页）。 */
  public StarvationAlertPage starvations(StarvationAlertQuery query) {
    StarvationAlertQuery q = query == null ? new StarvationAlertQuery(null, null, null, null, null, null, null, 0, 50) : query;
    int page = Math.max(0, q.page());
    int size = Math.min(Math.max(1, q.size()), MAX_PAGE_SIZE);
    StarvationScope scope = starvationScope(q.scope());
    Severity severity = severity(q.severity());
    LifecycleState lifecycle = lifecycle(q.lifecycle());

    List<Object> binds = new ArrayList<>();
    StringBuilder base =
        new StringBuilder(" where s.tenant_id = current_setting('app.current_tenant')::uuid");
    if (q.skuId() != null) {
      base.append(" and s.sku_id = ?::uuid");
      binds.add(q.skuId());
    }
    if (q.referenceId() != null) {
      base.append(" and s.reference_id = ?::uuid");
      binds.add(q.referenceId());
    }
    if (q.policyId() != null) {
      base.append(" and s.policy_id = ?::uuid");
      binds.add(q.policyId());
    }
    if (q.shopOperatingMode() != null && !q.shopOperatingMode().isBlank()) {
      base.append(" and s.shop_operating_mode = ?::text");
      binds.add(q.shopOperatingMode().trim().toUpperCase(Locale.ROOT));
    }
    if (severity != null) {
      base.append(" and s.severity = ?::text");
      binds.add(severity.name());
    }
    if (lifecycle != null) {
      base.append(" and s.lifecycle_state = ?::text");
      binds.add(lifecycle.name());
    }
    String where = base + starvationScopeClause(scope);

    long total = count("select count(*) as c from starvation_alert s" + where, binds.toArray());
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(size);
    pageBinds.add(page * size);
    var rows =
        sql.fetch(
            "select s.*, sr.code as sku_code from starvation_alert s"
                + " left join sku_ref sr on sr.tenant_id = s.tenant_id and sr.id = s.sku_id"
                + where
                + " order by case s.severity when 'CRITICAL' then 0 when 'WARNING' then 1 else 2 end,"
                + " s.zero_round_count desc, s.detected_at desc, s.id desc limit ? offset ?",
            pageBinds.toArray());
    var summary =
        sql.fetchOne(
            "select count(*) as row_count,"
                + " count(*) filter (where s.alert_state = 'STARVED') as starved_count,"
                + " count(*) filter (where s.alert_state = 'UNDER_SUPPLIED') as under_supplied_count,"
                + " count(*) filter (where s.alert_state = 'UNKNOWN_DEMAND') as unknown_demand_count,"
                + " count(*) filter (where s.alert_state = 'HEALTHY') as healthy_count,"
                + " count(*) filter (where s.severity = 'CRITICAL') as critical_count,"
                + " count(*) filter (where s.severity = 'WARNING') as warning_count,"
                + " count(*) filter (where s.lifecycle_state = 'OPEN') as open_count,"
                + " count(*) filter (where s.lifecycle_state = 'ACKNOWLEDGED') as acknowledged_count,"
                + " count(*) filter (where s.lifecycle_state = 'RESOLVED') as resolved_count,"
                + " count(*) filter (where s.floor_shortfall_quantity > 0) as floor_shortfall_count,"
                + " coalesce(sum(s.allocated_total), 0) as allocated_total,"
                + " coalesce(sum(s.demand_units), 0) as demand_total,"
                + " count(s.demand_units) as demand_known_count"
                + " from starvation_alert s"
                + where,
            binds.toArray());
    // 上次评估时刻**不受口径筛选影响** —— 否则「筛完没行」与「还没评估过」长得一模一样。
    var lastDetected =
        sql.fetchOne(
            "select max(s.detected_at) as d from starvation_alert s" + base, binds.toArray());
    return new StarvationAlertPage(
        rows.map(StockoutAlertService::starvationRow),
        total,
        page,
        size,
        new StarvationAlertSummary(
            longOf(summary, "row_count"),
            longOf(summary, "starved_count"),
            longOf(summary, "under_supplied_count"),
            longOf(summary, "unknown_demand_count"),
            longOf(summary, "healthy_count"),
            longOf(summary, "critical_count"),
            longOf(summary, "warning_count"),
            longOf(summary, "open_count"),
            longOf(summary, "acknowledged_count"),
            longOf(summary, "resolved_count"),
            longOf(summary, "floor_shortfall_count"),
            decimalOf(summary, "allocated_total"),
            decimalOf(summary, "demand_total"),
            longOf(summary, "demand_known_count"),
            lastDetected == null ? null : lastDetected.get("d", OffsetDateTime.class)));
  }

  /** 缺货清单（真分页）。 */
  public StockoutAlertPage stockouts(StockoutAlertQuery query) {
    StockoutAlertQuery q = query == null ? new StockoutAlertQuery(null, null, null, null, null, 0, 50) : query;
    int page = Math.max(0, q.page());
    int size = Math.min(Math.max(1, q.size()), MAX_PAGE_SIZE);
    StockoutScope scope = stockoutScope(q.scope());
    Severity severity = severity(q.severity());
    LifecycleState lifecycle = lifecycle(q.lifecycle());

    List<Object> binds = new ArrayList<>();
    StringBuilder base =
        new StringBuilder(" where s.tenant_id = current_setting('app.current_tenant')::uuid");
    if (q.skuId() != null) {
      base.append(" and s.sku_id = ?::uuid");
      binds.add(q.skuId());
    }
    if (q.warehouseId() != null) {
      base.append(" and s.warehouse_id = ?::uuid");
      binds.add(q.warehouseId());
    }
    if (severity != null) {
      base.append(" and s.severity = ?::text");
      binds.add(severity.name());
    }
    if (lifecycle != null) {
      base.append(" and s.lifecycle_state = ?::text");
      binds.add(lifecycle.name());
    }
    String where = base + stockoutScopeClause(scope);

    long total = count("select count(*) as c from stockout_alert s" + where, binds.toArray());
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(size);
    pageBinds.add(page * size);
    var rows =
        sql.fetch(
            "select s.*, sr.code as sku_code from stockout_alert s"
                + " left join sku_ref sr on sr.tenant_id = s.tenant_id and sr.id = s.sku_id"
                + where
                + " order by case s.severity when 'CRITICAL' then 0 when 'WARNING' then 1 else 2 end,"
                + " case s.alert_state when 'NETWORK_STOCKED_OUT' then 0"
                + " when 'WAREHOUSE_STOCKED_OUT' then 1"
                + " when 'AT_RISK' then 2 when 'NO_LEDGER_RECORD' then 3 when 'UNKNOWN_DEMAND' then 4 else 5 end,"
                + " s.detected_at desc, s.id desc limit ? offset ?",
            pageBinds.toArray());
    var summary =
        sql.fetchOne(
            "select count(*) as row_count,"
                + " count(*) filter (where s.alert_state = 'NETWORK_STOCKED_OUT') as network_stocked_out_count,"
                + " count(*) filter (where s.alert_state = 'WAREHOUSE_STOCKED_OUT') as warehouse_stocked_out_count,"
                + " count(*) filter (where s.alert_state = 'AT_RISK') as at_risk_count,"
                + " count(*) filter (where s.alert_state = 'NO_LEDGER_RECORD') as no_ledger_record_count,"
                + " count(*) filter (where s.alert_state = 'UNKNOWN_DEMAND') as unknown_demand_count,"
                + " count(*) filter (where s.alert_state = 'HEALTHY') as healthy_count,"
                + " count(*) filter (where s.severity = 'CRITICAL') as critical_count,"
                + " count(*) filter (where s.severity = 'WARNING') as warning_count,"
                + " count(*) filter (where s.lifecycle_state = 'OPEN') as open_count,"
                + " count(*) filter (where s.lifecycle_state = 'ACKNOWLEDGED') as acknowledged_count,"
                + " count(*) filter (where s.lifecycle_state = 'RESOLVED') as resolved_count,"
                + " coalesce(sum(s.available_quantity), 0) as available_total,"
                + " count(s.available_quantity) as available_known_count,"
                + " coalesce(sum(s.demand_units), 0) as demand_total,"
                + " count(s.demand_units) as demand_known_count,"
                + " count(distinct s.sku_id) filter (where s.alert_state = 'NETWORK_STOCKED_OUT')"
                + "   as network_stocked_out_sku_count"
                + " from stockout_alert s"
                + where,
            binds.toArray());
    var lastDetected =
        sql.fetchOne("select max(s.detected_at) as d from stockout_alert s" + base, binds.toArray());
    return new StockoutAlertPage(
        rows.map(StockoutAlertService::stockoutRow),
        total,
        page,
        size,
        new StockoutAlertSummary(
            longOf(summary, "row_count"),
            longOf(summary, "network_stocked_out_count"),
            longOf(summary, "warehouse_stocked_out_count"),
            longOf(summary, "at_risk_count"),
            longOf(summary, "no_ledger_record_count"),
            longOf(summary, "unknown_demand_count"),
            longOf(summary, "healthy_count"),
            longOf(summary, "critical_count"),
            longOf(summary, "warning_count"),
            longOf(summary, "open_count"),
            longOf(summary, "acknowledged_count"),
            longOf(summary, "resolved_count"),
            decimalOf(summary, "available_total"),
            longOf(summary, "available_known_count"),
            decimalOf(summary, "demand_total"),
            longOf(summary, "demand_known_count"),
            longOf(summary, "network_stocked_out_sku_count"),
            lastDetected == null ? null : lastDetected.get("d", OffsetDateTime.class)));
  }

  // ============================================================ 详情与处置

  /** 饥饿预警详情。 */
  public StarvationAlertRow starvation(UUID id) {
    var row =
        sql.fetchOne(
            "select s.*, sr.code as sku_code from starvation_alert s"
                + " left join sku_ref sr on sr.tenant_id = s.tenant_id and sr.id = s.sku_id"
                + " where s.tenant_id = current_setting('app.current_tenant')::uuid and s.id = ?::uuid",
            id);
    if (row == null) {
      throw bad("找不到这条饥饿预警（可能已被别的租户隔离，或 id 不存在）");
    }
    return starvationRow(row);
  }

  /** 缺货预警详情。 */
  public StockoutAlertRow stockout(UUID id) {
    var row =
        sql.fetchOne(
            "select s.*, sr.code as sku_code from stockout_alert s"
                + " left join sku_ref sr on sr.tenant_id = s.tenant_id and sr.id = s.sku_id"
                + " where s.tenant_id = current_setting('app.current_tenant')::uuid and s.id = ?::uuid",
            id);
    if (row == null) {
      throw bad("找不到这条缺货预警（可能已被别的租户隔离，或 id 不存在）");
    }
    return stockoutRow(row);
  }

  /** 认领一条饥饿预警。 */
  @Transactional
  public StockoutAlertActionResult acknowledgeStarvation(UUID id, UUID actorId) {
    var row = lockStarvation(id);
    if ("RESOLVED".equals(row.get("lifecycle_state", String.class))) {
      throw bad("这条预警已经处置过了，不能再认领 —— 重新评估时问题复发会自动重新打开它。");
    }
    sql.execute(
        "update starvation_alert set lifecycle_state = 'ACKNOWLEDGED',"
            + " acknowledged_by_membership_id = ?::uuid, acknowledged_at = ?::timestamptz,"
            + " updated_at = ?::timestamptz"
            + " where tenant_id = current_setting('app.current_tenant')::uuid and id = ?::uuid",
        actorId,
        OffsetDateTime.now(),
        OffsetDateTime.now(),
        id);
    return new StockoutAlertActionResult(
        id,
        "STARVATION",
        LifecycleState.ACKNOWLEDGED,
        OffsetDateTime.now(),
        "已认领。处置建议见该行的建议字段 —— 注意本模块**只给指针**："
            + "改保底要去 7.8.1.2 开新档，改优先级要去 7.8.1.1 改名册。");
  }

  /** 处置一条饥饿预警。 */
  @Transactional
  public StockoutAlertActionResult resolveStarvation(UUID id, String note, UUID actorId) {
    lockStarvation(id);
    String reason = note == null ? "" : note.trim();
    if (reason.isEmpty()) {
      throw bad("处置必须写明做了什么：没有处置说明的记录在复盘时与「没发生过」长得一模一样。");
    }
    sql.execute(
        "update starvation_alert set lifecycle_state = 'RESOLVED', resolved_at = ?::timestamptz,"
            + " resolution_note = ?, updated_at = ?::timestamptz"
            + " where tenant_id = current_setting('app.current_tenant')::uuid and id = ?::uuid",
        OffsetDateTime.now(),
        reason,
        OffsetDateTime.now(),
        id);
    return new StockoutAlertActionResult(
        id, "STARVATION", LifecycleState.RESOLVED, OffsetDateTime.now(), "已处置。");
  }

  /** 认领一条缺货预警。 */
  @Transactional
  public StockoutAlertActionResult acknowledgeStockout(UUID id, UUID actorId) {
    var row = lockStockout(id);
    if ("RESOLVED".equals(row.get("lifecycle_state", String.class))) {
      throw bad("这条预警已经处置过了，不能再认领 —— 重新评估时问题复发会自动重新打开它。");
    }
    sql.execute(
        "update stockout_alert set lifecycle_state = 'ACKNOWLEDGED',"
            + " acknowledged_by_membership_id = ?::uuid, acknowledged_at = ?::timestamptz,"
            + " updated_at = ?::timestamptz"
            + " where tenant_id = current_setting('app.current_tenant')::uuid and id = ?::uuid",
        actorId,
        OffsetDateTime.now(),
        OffsetDateTime.now(),
        id);
    return new StockoutAlertActionResult(
        id,
        "STOCKOUT",
        LifecycleState.ACKNOWLEDGED,
        OffsetDateTime.now(),
        "已认领。补货属 6.2.1.2（尚未交付）—— 本模块只给缺口件数与销量窗口，"
            + "不落采购单。");
  }

  /** 处置一条缺货预警。 */
  @Transactional
  public StockoutAlertActionResult resolveStockout(UUID id, String note, UUID actorId) {
    lockStockout(id);
    String reason = note == null ? "" : note.trim();
    if (reason.isEmpty()) {
      throw bad("处置必须写明做了什么：没有处置说明的记录在复盘时与「没发生过」长得一模一样。");
    }
    sql.execute(
        "update stockout_alert set lifecycle_state = 'RESOLVED', resolved_at = ?::timestamptz,"
            + " resolution_note = ?, updated_at = ?::timestamptz"
            + " where tenant_id = current_setting('app.current_tenant')::uuid and id = ?::uuid",
        OffsetDateTime.now(),
        reason,
        OffsetDateTime.now(),
        id);
    return new StockoutAlertActionResult(
        id, "STOCKOUT", LifecycleState.RESOLVED, OffsetDateTime.now(), "已处置。");
  }

  // ============================================================ 检测：饥饿

  /**
   * 饥饿检测：按 (SKU × 店铺) 聚合观察窗口内的分摊事实。
   *
   * <p>绑定顺序（**必须与 SQL 里的问号顺序一致**）：
   *
   * <ol>
   *   <li>{@code at}（求值时刻）、{@code from_at} / {@code to_at}（分摊时刻区间，左闭右开）
   *   <li>{@code skuId} ×2
   *   <li>{@code windowRounds}（<b>它在店铺筛选之前</b> —— {@code kept} 这个 CTE
   *       排在 {@code lines} 前面）
   *   <li>{@code referenceId} ×2
   *   <li>{@code limit}
   * </ol>
   */
  private static final String STARVATION_SQL =
      """
      with win as (select ?::timestamptz as at, ?::timestamptz as from_at, ?::timestamptz as to_at),
      obs as (
        select r.id, r.sku_id, r.method, r.policy_id, r.allocated_at,
               r.available_quantity, r.allocatable_quantity,
               r.sales_window_start, r.sales_window_end,
               row_number() over (partition by r.sku_id order by r.allocated_at desc, r.id desc) as rn
          from allocation_run r, win
         where r.tenant_id = current_setting('app.current_tenant')::uuid
           and (?::uuid is null or r.sku_id = ?::uuid)
           and (win.from_at is null or r.allocated_at >= win.from_at)
           and (win.to_at is null or r.allocated_at < win.to_at)
      ),
      kept as (select * from obs where rn <= ?),
      lines as (
        select l.run_id, k.sku_id, k.policy_id, k.method, k.allocated_at,
               k.available_quantity, k.allocatable_quantity,
               k.sales_window_start, k.sales_window_end,
               l.source_system, l.reference_id, l.reference_label, l.shop_operating_mode,
               l.allocation_order, l.allocated_qty, l.outcome, l.sales_units
          from allocation_run_line l
          join kept k on k.id = l.run_id
         where l.tenant_id = current_setting('app.current_tenant')::uuid
           and (?::uuid is null or l.reference_id = ?::uuid)
      ),
      agg as (
        select l.sku_id, l.reference_id,
               count(*) as run_count,
               count(*) filter (where l.allocated_qty = 0) as zero_round_count,
               sum(l.allocated_qty) as allocated_total,
               max(l.available_quantity) as max_run_available,
               min(l.available_quantity) as min_run_available,
               max(l.sales_units) as snapshot_sales_units,
               (array_agg(l.source_system order by l.allocated_at desc, l.run_id desc))[1] as source_system,
               (array_agg(l.reference_label order by l.allocated_at desc, l.run_id desc))[1] as reference_label,
               (array_agg(l.shop_operating_mode order by l.allocated_at desc, l.run_id desc))[1] as shop_operating_mode,
               (array_agg(l.policy_id order by l.allocated_at desc, l.run_id desc))[1] as policy_id,
               (array_agg(l.allocation_order order by l.allocated_at desc, l.run_id desc))[1] as last_allocation_order,
               (array_agg(l.outcome order by l.allocated_at desc, l.run_id desc))[1] as last_outcome,
               (array_agg(l.run_id order by l.allocated_at desc, l.run_id desc))[1] as last_run_id,
               (array_agg(l.allocatable_quantity order by l.allocated_at desc, l.run_id desc))[1] as last_allocatable,
               min(l.allocated_at) as window_start,
               max(l.allocated_at) as window_end,
               min(l.sales_window_start) as sales_window_start,
               max(l.sales_window_end) as sales_window_end
          from lines l
         group by l.sku_id, l.reference_id
      ),
      demand as (
        select s.sku_id, s.reference_id,
               sum(s.units_sold) as demand_units,
               count(*) as registrations,
               min(s.period_start) as demand_window_start,
               max(s.period_end) as demand_window_end
          from allocation_shop_sales s
         where s.tenant_id = current_setting('app.current_tenant')::uuid
           and exists (
             select 1 from agg a
              where a.sku_id = s.sku_id and a.reference_id = s.reference_id
                and s.period_end >= a.sales_window_start
                and s.period_start <= a.sales_window_end)
         group by s.sku_id, s.reference_id
      ),
      lastline as (
        select distinct on (l.sku_id, l.reference_id)
               l.sku_id, l.reference_id, l.run_id
          from lines l
         order by l.sku_id, l.reference_id, l.allocated_at desc, l.run_id desc
      ),
      floorres as (
        select f.run_id, f.reference_id, f.floor_order, f.status, f.shortfall_qty,
               f.suppressed, f.suppression_reason
          from allocation_priority_resolution f
         where f.tenant_id = current_setting('app.current_tenant')::uuid
      ),
      floorcfg as (
        select fl.policy_id, fl.reference_id, fl.floor_mode, fl.floor_ratio,
               fl.floor_quantity, fl.safety_quantity
          from allocation_floor fl
         where fl.tenant_id = current_setting('app.current_tenant')::uuid
           and fl.effective_to is null
      ),
      member as (
        select m.policy_id, m.reference_id, m.priority, m.share_limit, m.enabled
          from allocation_policy_member m
         where m.tenant_id = current_setting('app.current_tenant')::uuid
      ),
      peer as (
        select m.policy_id, min(m.priority) as peer_priority
          from allocation_policy_member m
         where m.tenant_id = current_setting('app.current_tenant')::uuid
           and m.enabled
           and exists (
             select 1 from lines l
              where l.policy_id = m.policy_id and l.reference_id = m.reference_id
                and l.allocated_qty > 0)
         group by m.policy_id
      ),
      netavail as (
        select b.sku_id, sum(b.available) as available
          from stock_bucket b
         where b.tenant_id = current_setting('app.current_tenant')::uuid
         group by b.sku_id
      )
      select a.sku_id, sr.code as sku_code, a.source_system, a.reference_id, a.reference_label,
             a.shop_operating_mode, a.policy_id,
             a.window_start, a.window_end, a.run_count, a.zero_round_count, a.allocated_total,
             a.max_run_available, a.min_run_available, a.last_allocation_order, a.last_outcome,
             a.last_run_id, a.last_allocatable, a.snapshot_sales_units,
             d.demand_units, (d.registrations is not null) as demand_registered,
             d.demand_window_start, d.demand_window_end,
             fr.floor_order, fr.status as floor_status, fr.shortfall_qty as floor_shortfall,
             fr.suppressed as floor_suppressed, fr.suppression_reason as floor_suppression_reason,
             fc.floor_mode as floor_committed_mode, fc.floor_ratio as floor_committed_ratio,
             fc.floor_quantity as floor_committed_quantity, fc.safety_quantity as floor_safety_quantity,
             m.priority as member_priority, m.share_limit as member_share_limit, m.enabled as member_enabled,
             p.peer_priority, na.available as network_available
        from agg a
        left join sku_ref sr
          on sr.tenant_id = current_setting('app.current_tenant')::uuid and sr.id = a.sku_id
        left join demand d on d.sku_id = a.sku_id and d.reference_id = a.reference_id
        left join lastline ll on ll.sku_id = a.sku_id and ll.reference_id = a.reference_id
        left join floorres fr on fr.run_id = ll.run_id and fr.reference_id = a.reference_id
        left join floorcfg fc on fc.policy_id = a.policy_id and fc.reference_id = a.reference_id
        left join member m on m.policy_id = a.policy_id and m.reference_id = a.reference_id
        left join peer p on p.policy_id = a.policy_id
        left join netavail na on na.sku_id = a.sku_id
       order by a.zero_round_count desc, a.allocated_total asc, a.sku_id, a.reference_id
       limit ?
      """;

  private List<StarvationDetection> detectStarvations(
      OffsetDateTime at,
      UUID skuId,
      UUID referenceId,
      int windowRounds,
      BigDecimal coverageThreshold,
      BigDecimal zeroRoundRatio) {
    var rows =
        sql.fetch(
            STARVATION_SQL,
            at,
            null,
            null,
            skuId,
            skuId,
            windowRounds,
            referenceId,
            referenceId,
            MAX_SCAN_ROWS);
    List<StarvationDetection> out = new ArrayList<>();
    for (Record r : rows) {
      out.add(starvationDetection(r, coverageThreshold, zeroRoundRatio));
    }
    return out;
  }

  private StarvationDetection starvationDetection(
      Record r, BigDecimal coverageThreshold, BigDecimal zeroRoundRatio) {
    UUID skuId = r.get("sku_id", UUID.class);
    UUID referenceId = r.get("reference_id", UUID.class);
    int runCount = intOf(r, "run_count");
    int zeroRounds = intOf(r, "zero_round_count");
    BigDecimal allocated = decimalOf(r, "allocated_total");
    BigDecimal demand = decimalOf(r, "demand_units");
    boolean demandRegistered = Boolean.TRUE.equals(r.get("demand_registered", Boolean.class));
    BigDecimal maxAvailable = decimalOf(r, "max_run_available");
    BigDecimal networkAvailable = decimalOf(r, "network_available");
    BigDecimal floorShortfall = decimalOf(r, "floor_shortfall");
    String lastOutcome = r.get("last_outcome", String.class);
    Integer memberPriority = r.get("member_priority", Integer.class);
    Integer peerPriority = r.get("peer_priority", Integer.class);
    BigDecimal lastAllocatable = decimalOf(r, "last_allocatable");

    BigDecimal coverage = null;
    if (demand != null && demand.signum() > 0) {
      coverage = allocated.divide(demand, 6, RoundingMode.HALF_UP);
      if (coverage.compareTo(BigDecimal.ONE) > 0) {
        coverage = BigDecimal.ONE;
      }
    }

    StarvationState state;
    if (!demandRegistered || demand == null) {
      state = StarvationState.UNKNOWN_DEMAND;
    } else if (demand.signum() == 0) {
      // 登记了 0 需求 = 这家店不卖这个 SKU ⇒ 不构成饥饿。
      state = StarvationState.HEALTHY;
    } else {
      BigDecimal zeroRatio =
          runCount <= 0
              ? BigDecimal.ONE
              : BigDecimal.valueOf(zeroRounds).divide(BigDecimal.valueOf(runCount), 6, RoundingMode.HALF_UP);
      boolean zeroDominates = zeroRounds >= 1 && zeroRatio.compareTo(zeroRoundRatio) >= 0;
      boolean under = coverage != null && coverage.compareTo(coverageThreshold) < 0;
      state = zeroDominates && under ? StarvationState.STARVED : (under ? StarvationState.UNDER_SUPPLIED : StarvationState.HEALTHY);
    }

    boolean networkStockout = networkAvailable != null && networkAvailable.signum() <= 0;
    boolean starved = state == StarvationState.STARVED;

    StarvationRootCause cause;
    if (floorShortfall != null && floorShortfall.signum() > 0) {
      cause = StarvationRootCause.FLOOR;
    } else if ("CAP_REACHED".equals(lastOutcome)) {
      cause = StarvationRootCause.SHARE_LIMIT;
    } else if ("NO_SALES".equals(lastOutcome)) {
      cause = StarvationRootCause.NO_SALES;
    } else {
      cause = StarvationRootCause.PRIORITY;
    }

    Severity severity;
    if (!starved) {
      severity = Severity.INFO;
    } else if ((floorShortfall != null && floorShortfall.signum() > 0)
        || (coverage != null && coverage.compareTo(new BigDecimal("0.5")) < 0)) {
      severity = Severity.CRITICAL;
    } else {
      severity = Severity.WARNING;
    }

    String note =
        starved
            ? "观察窗口内 " + runCount + " 轮有 " + zeroRounds + " 轮分到 0 件"
                + (demand == null ? "" : "，登记需求 " + plain(demand) + " 件、累计分到 " + plain(allocated) + " 件")
                + "；同期总可用量最高 "
                + (maxAvailable == null ? "—" : plain(maxAvailable))
                + " 件 —— 货在、这家店分不到，所以是饥饿而不是缺货。"
            : state == StarvationState.UNDER_SUPPLIED
                ? "拿到了货但不足登记需求的覆盖率：累计分到 "
                    + plain(allocated)
                    + " 件，登记需求 "
                    + (demand == null ? "—" : plain(demand))
                    + " 件。"
                : state == StarvationState.UNKNOWN_DEMAND
                    ? "没有销量登记（7.8.1.1 的 allocation_shop_sales），"
                        + "因此判不出它到底饿不饿 —— 这是「不知道」，不是「不缺货」。"
                    : "供给正常。";

    AlertRecommendation recommendation =
        starvationRecommendation(
            r, state, demand, allocated, runCount, memberPriority, peerPriority, lastAllocatable);

    return new StarvationDetection(
        skuId,
        r.get("sku_code", String.class),
        r.get("source_system", String.class),
        referenceId,
        r.get("reference_label", String.class),
        r.get("shop_operating_mode", String.class),
        r.get("policy_id", UUID.class),
        r.get("window_start", OffsetDateTime.class),
        r.get("window_end", OffsetDateTime.class),
        runCount,
        zeroRounds,
        allocated,
        maxAvailable,
        decimalOf(r, "min_run_available"),
        r.get("last_allocation_order", Integer.class),
        lastOutcome,
        r.get("last_run_id", UUID.class),
        demand,
        demandRegistered,
        r.get("demand_window_start", LocalDate.class),
        r.get("demand_window_end", LocalDate.class),
        coverage,
        memberPriority,
        decimalOf(r, "member_share_limit"),
        r.get("member_enabled", Boolean.class),
        r.get("floor_order", Integer.class),
        r.get("floor_status", String.class),
        floorShortfall,
        r.get("floor_suppressed", Boolean.class),
        r.get("floor_suppression_reason", String.class),
        r.get("floor_committed_mode", String.class),
        decimalOf(r, "floor_committed_ratio"),
        decimalOf(r, "floor_committed_quantity"),
        decimalOf(r, "floor_safety_quantity"),
        state,
        severity,
        demand == null || !demandRegistered ? DemandState.UNKNOWN : DemandState.KNOWN,
        cause,
        note,
        recommendation,
        networkStockout);
  }

  /**
   * 饥饿侧的建议生成。
   *
   * <p>三条不同的路，指向**三个不同的上游对象**：
   *
   * <ol>
   *   <li>需求未知 → {@code REGISTER_SALES}（7.8.1.1 的 allocation_shop_sales）：
   *       先有需求才谈得上饿不饿。
   *   <li>该店有生效的保底档 → {@code RAISE_FLOOR}（7.8.1.2 的 allocation_floor）：
   *       建议值 = 窗口内平均每轮应得件数（向上取整）。
   *   <li>该店在名册里但没有保底 → {@code RAISE_PRIORITY}（7.8.1.1 的
   *       {@code allocation_policy_member}）：建议值 = 比「拿到货的那家店的优先级」更靠前一位。
   *       若它本来就排在前面 ⇒ 次序不是瓶颈，如实说「给不出可执行建议」。
   * </ol>
   */
  private AlertRecommendation starvationRecommendation(
      Record r,
      StarvationState state,
      BigDecimal demand,
      BigDecimal allocated,
      int runCount,
      Integer memberPriority,
      Integer peerPriority,
      BigDecimal lastAllocatable) {
    UUID policyId = r.get("policy_id", UUID.class);
    UUID referenceId = r.get("reference_id", UUID.class);
    String committedMode = r.get("floor_committed_mode", String.class);
    BigDecimal committedQuantity = decimalOf(r, "floor_committed_quantity");
    BigDecimal committedRatio = decimalOf(r, "floor_committed_ratio");

    if (state == StarvationState.UNKNOWN_DEMAND) {
      return new AlertRecommendation(
          RecommendationKind.REGISTER_SALES,
          "allocation_shop_sales",
          "7.8.1.1 销量登记",
          policyId,
          referenceId,
          null,
          null,
          null,
          "没有销量登记，无法判断这家店到底该不该分到货 —— "
              + "先在 7.8.1.1 登记该店在该 SKU 的销量窗口，再评估一次。");
    }
    if (state == StarvationState.HEALTHY) {
      return new AlertRecommendation(
          RecommendationKind.NONE,
          "",
          "",
          policyId,
          referenceId,
          null,
          null,
          null,
          "供给正常，无需处置。");
    }

    BigDecimal perRound =
        demand == null || runCount <= 0
            ? BigDecimal.ONE
            : demand.divide(BigDecimal.valueOf(runCount), 0, RoundingMode.CEILING);

    if ("QUANTITY".equals(committedMode)) {
      return new AlertRecommendation(
          RecommendationKind.RAISE_FLOOR,
          "allocation_floor",
          "7.8.1.2 保底档（按件数）",
          policyId,
          referenceId,
          null,
          committedQuantity,
          perRound.max(BigDecimal.ONE),
          "窗口内登记需求 "
              + plain(demand)
              + " 件、摊到 "
              + runCount
              + " 轮，平均每轮应得 "
              + plain(perRound)
              + " 件；该店当前保底承诺 "
              + plain(committedQuantity)
              + " 件。"
              + "把保底调到每轮 "
              + plain(perRound.max(BigDecimal.ONE))
              + " 件以上，货不够分时它才保得住"
              + "（改保底要开新一档 —— 本模块只给指针，不替你改档）。");
    }
    if ("RATIO".equals(committedMode)) {
      BigDecimal suggested = null;
      if (lastAllocatable != null && lastAllocatable.signum() > 0) {
        suggested =
            perRound
                .divide(lastAllocatable, 6, RoundingMode.HALF_UP)
                .min(BigDecimal.ONE)
                .max(committedRatio == null ? BigDecimal.ZERO : committedRatio);
      }
      return new AlertRecommendation(
          RecommendationKind.RAISE_FLOOR,
          "allocation_floor",
          "7.8.1.2 保底档（按比例）",
          policyId,
          referenceId,
          null,
          committedRatio,
          suggested,
          "该店的保底是按比例承诺的（当前 "
              + (committedRatio == null ? "—" : committedRatio.stripTrailingZeros().toPlainString())
              + "）。每轮应得 "
              + plain(perRound)
              + " 件、该轮可分配量 "
              + (lastAllocatable == null ? "—" : plain(lastAllocatable))
              + " 件 ⇒ 建议比例 "
              + (suggested == null ? "算不出来（该轮可分配量为 0）" : suggested.stripTrailingZeros().toPlainString())
              + "。改保底要开新一档。");
    }
    if (memberPriority != null && peerPriority != null) {
      int suggested = Math.max(1, peerPriority - 1);
      if (suggested >= memberPriority) {
        return new AlertRecommendation(
            RecommendationKind.NONE,
            "",
            "",
            policyId,
            referenceId,
            null,
            BigDecimal.valueOf(memberPriority),
            null,
            "该店在名册里的优先级已经是 "
                + memberPriority
                + "，比拿到货的那家（"
                + peerPriority
                + "）更靠前 —— 次序不是瓶颈，给不出「调优先级」这个建议。");
      }
      return new AlertRecommendation(
          RecommendationKind.RAISE_PRIORITY,
          "allocation_policy_member",
          "7.8.1.1 成员名册优先级",
          policyId,
          referenceId,
          null,
          BigDecimal.valueOf(memberPriority),
          BigDecimal.valueOf(suggested),
          "该店当前优先级 "
              + memberPriority
              + "，而同一策略里拿到货的店优先级是 "
              + peerPriority
              + "。把优先级调到 "
              + suggested
              + "（数值小 = 先分）才能排到它们前面"
              + "（改优先级要改 7.8.1.1 的名册 —— 本模块只给指针，不替你改名册）。");
    }
    return new AlertRecommendation(
        RecommendationKind.NONE,
        "",
        "",
        policyId,
        referenceId,
        null,
        null,
        null,
        "该店不在任何分摊策略的成员名册里，给不出可执行建议 —— "
            + "先把它加进 7.8.1.1 的名册，它才可能被分到货。");
  }

  // ============================================================ 检测：缺货

  /**
   * 缺货检测：按 (SKU × 仓) 判定「没货卖」。
   *
   * <p>绑定顺序：
   *
   * <ol>
   *   <li>{@code at} / {@code from_at} / {@code to_at}
   *   <li>{@code skuId} ×2（候选 SKU 的第一个来源：分摊结果）
   *   <li>{@code skuId} ×2（候选 SKU 的第二个来源：销量登记）
   *   <li>{@code windowRounds}（销量窗口取自最近 N 轮 —— <b>它在账本筛选之前</b>，
   *       因为 saleswin 这个 CTE 排在 ledger 前面）
   *   <li>{@code warehouseId} ×2（账本范围）
   *   <li>{@code limit}
   * </ol>
   */
  private static final String STOCKOUT_SQL =
      """
      with win as (select ?::timestamptz as at, ?::timestamptz as from_at, ?::timestamptz as to_at),
      cand as (
        select distinct r.sku_id
          from allocation_run r, win
         where r.tenant_id = current_setting('app.current_tenant')::uuid
           and (?::uuid is null or r.sku_id = ?::uuid)
           and (win.from_at is null or r.allocated_at >= win.from_at)
           and (win.to_at is null or r.allocated_at < win.to_at)
        union
        select distinct s.sku_id
          from allocation_shop_sales s
         where s.tenant_id = current_setting('app.current_tenant')::uuid
           and (?::uuid is null or s.sku_id = ?::uuid)
      ),
      obs as (
        select r.id, r.sku_id, r.method, r.allocated_at, r.available_quantity,
               r.allocatable_quantity, r.allocated_total, r.unallocated_quantity,
               r.sales_window_start, r.sales_window_end,
               row_number() over (partition by r.sku_id order by r.allocated_at desc, r.id desc) as rn
          from allocation_run r
          join cand c on c.sku_id = r.sku_id, win
         where r.tenant_id = current_setting('app.current_tenant')::uuid
           and (win.from_at is null or r.allocated_at >= win.from_at)
           and (win.to_at is null or r.allocated_at < win.to_at)
      ),
      lastrun as (
        select distinct on (o.sku_id) o.sku_id, o.id, o.allocated_at, o.method,
               o.available_quantity, o.allocatable_quantity, o.allocated_total, o.unallocated_quantity
          from obs o where o.rn = 1
         order by o.sku_id
      ),
      saleswin as (
        select o.sku_id, min(o.sales_window_start) as ws, max(o.sales_window_end) as we
          from obs o where o.rn <= ?
         group by o.sku_id
      ),
      ledger as (
        select b.sku_id, b.warehouse_id,
               sum(b.on_hand) as on_hand, sum(b.reserved) as reserved, sum(b.available) as available
          from stock_bucket b
          join cand c on c.sku_id = b.sku_id
         where b.tenant_id = current_setting('app.current_tenant')::uuid
           and (?::uuid is null or b.warehouse_id = ?::uuid)
         group by b.sku_id, b.warehouse_id
      ),
      net as (
        select b.sku_id,
               sum(b.on_hand) as on_hand, sum(b.reserved) as reserved, sum(b.available) as available
          from stock_bucket b
          join cand c on c.sku_id = b.sku_id
         where b.tenant_id = current_setting('app.current_tenant')::uuid
         group by b.sku_id
      ),
      demand as (
        select s.sku_id, sum(s.units_sold) as demand_units, count(*) as registrations,
               count(distinct s.reference_id) as shop_count,
               min(s.period_start) as demand_window_start, max(s.period_end) as demand_window_end
          from allocation_shop_sales s
          join cand c on c.sku_id = s.sku_id
          left join saleswin sw on sw.sku_id = s.sku_id
         where s.tenant_id = current_setting('app.current_tenant')::uuid
           and (sw.sku_id is null
                or (s.period_end >= sw.ws and s.period_start <= sw.we))
         group by s.sku_id
      )
      select c.sku_id, sr.code as sku_code,
             l.warehouse_id, coalesce(w.code, '') as warehouse_code,
             coalesce(w.name, '') as warehouse_name,
             l.on_hand, l.reserved, l.available,
             n.on_hand as network_on_hand, n.reserved as network_reserved, n.available as network_available,
             lr.id as run_id, lr.allocated_at as run_at, lr.method as run_method,
             lr.available_quantity as run_available, lr.allocatable_quantity as run_allocatable,
             lr.unallocated_quantity as run_unallocated, lr.allocated_total as run_allocated_total,
             d.demand_units, (d.registrations is not null) as demand_registered,
             d.demand_window_start, d.demand_window_end, d.shop_count as demand_shop_count
        from cand c
        left join ledger l on l.sku_id = c.sku_id
        left join warehouse w
          on w.tenant_id = current_setting('app.current_tenant')::uuid and w.id = l.warehouse_id
        left join net n on n.sku_id = c.sku_id
        left join lastrun lr on lr.sku_id = c.sku_id
        left join demand d on d.sku_id = c.sku_id
        left join sku_ref sr
          on sr.tenant_id = current_setting('app.current_tenant')::uuid and sr.id = c.sku_id
       order by c.sku_id, l.warehouse_id
       limit ?
      """;

  private List<StockoutDetection> detectStockouts(
      OffsetDateTime at, UUID skuId, UUID warehouseId, int windowRounds, int demandWindowDays) {
    var rows =
        sql.fetch(
            STOCKOUT_SQL,
            at,
            null,
            null,
            skuId,
            skuId,
            skuId,
            skuId,
            windowRounds,
            warehouseId,
            warehouseId,
            MAX_SCAN_ROWS);
    List<StockoutDetection> out = new ArrayList<>();
    LocalDate fallbackFrom = at.toLocalDate().minusDays(demandWindowDays - 1L);
    for (Record r : rows) {
      out.add(stockoutDetection(r, fallbackFrom, at.toLocalDate()));
    }
    return out;
  }

  private StockoutDetection stockoutDetection(Record r, LocalDate fallbackFrom, LocalDate fallbackTo) {
    UUID skuId = r.get("sku_id", UUID.class);
    UUID warehouseId = r.get("warehouse_id", UUID.class);
    BigDecimal onHand = decimalOf(r, "on_hand");
    BigDecimal reserved = decimalOf(r, "reserved");
    BigDecimal available = decimalOf(r, "available");
    BigDecimal networkOnHand = decimalOf(r, "network_on_hand");
    BigDecimal networkReserved = decimalOf(r, "network_reserved");
    BigDecimal networkAvailable = decimalOf(r, "network_available");
    BigDecimal demand = decimalOf(r, "demand_units");
    boolean demandRegistered = Boolean.TRUE.equals(r.get("demand_registered", Boolean.class));

    StockoutState state;
    if (networkAvailable == null) {
      // 账本里查不到这个 SKU 的任何库存记录 —— 不知道，不是 0 件。
      state = StockoutState.NO_LEDGER_RECORD;
    } else if (networkAvailable.signum() <= 0) {
      state = StockoutState.NETWORK_STOCKED_OUT;
    } else if (available != null && available.signum() <= 0) {
      state = StockoutState.WAREHOUSE_STOCKED_OUT;
    } else if (!demandRegistered || demand == null) {
      state = StockoutState.UNKNOWN_DEMAND;
    } else if (networkAvailable.compareTo(demand) < 0) {
      state = StockoutState.AT_RISK;
    } else {
      state = StockoutState.HEALTHY;
    }

    BigDecimal coverage = null;
    if (demand != null && demand.signum() > 0 && networkAvailable != null) {
      coverage = networkAvailable.divide(demand, 6, RoundingMode.HALF_UP);
      if (coverage.compareTo(BigDecimal.ONE) > 0) {
        coverage = BigDecimal.ONE;
      }
    }
    BigDecimal daysOfCover = null;
    if (coverage != null && demand != null && demand.signum() > 0 && networkAvailable != null) {
      LocalDate from = r.get("demand_window_start", LocalDate.class);
      LocalDate to = r.get("demand_window_end", LocalDate.class);
      if (from == null || to == null) {
        from = fallbackFrom;
        to = fallbackTo;
      }
      long days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1L;
      if (days > 0) {
        BigDecimal daily = demand.divide(BigDecimal.valueOf(days), 6, RoundingMode.HALF_UP);
        if (daily.signum() > 0) {
          daysOfCover = networkAvailable.divide(daily, 4, RoundingMode.HALF_UP);
        }
      }
    }

    Severity severity;
    switch (state) {
      case NETWORK_STOCKED_OUT ->
          severity = (demand != null && demand.signum() > 0) ? Severity.CRITICAL : Severity.WARNING;
      case WAREHOUSE_STOCKED_OUT, AT_RISK -> severity = Severity.WARNING;
      default -> severity = Severity.INFO;
    }

    String note =
        switch (state) {
          case NO_LEDGER_RECORD ->
              "账本里查不到这个 SKU 的任何库存记录 —— 这是「不知道」，不是「0 件」。"
                  + "请先核对库存台账（7.1 库存账本）。";
          case NETWORK_STOCKED_OUT ->
              "全网账本可用量 "
                  + plain(networkAvailable)
                  + " 件（= 在手 "
                  + plain(networkOnHand)
                  + " − 预占 "
                  + plain(networkReserved)
                  + "），已经是全网没货。"
                  + (demand == null ? "需求未登记，影响面未知。" : "登记需求 " + plain(demand) + " 件。");
          case WAREHOUSE_STOCKED_OUT ->
              "该仓账本可用量 0 件，而全网还有 "
                  + plain(networkAvailable)
                  + " 件 —— 货在别的仓，处置是仓间调拨（7.5）或补到这个仓。";
          case AT_RISK ->
              "全网账本可用量 "
                  + plain(networkAvailable)
                  + " 件 < 登记需求 "
                  + plain(demand)
                  + " 件：覆盖不足，缺口 "
                  + plain(demand.subtract(networkAvailable))
                  + " 件。";
          case UNKNOWN_DEMAND ->
              "没有销量登记（7.8.1.1 的 allocation_shop_sales），"
                  + "所以判不出影响面 —— 这是「不知道」，不是「不缺货」。";
          default -> "供给正常。";
        };

    AlertRecommendation recommendation =
        stockoutRecommendation(state, demand, networkAvailable, available, skuId, warehouseId, r);

    return new StockoutDetection(
        skuId,
        r.get("sku_code", String.class),
        warehouseId,
        r.get("warehouse_code", String.class),
        r.get("warehouse_name", String.class),
        onHand,
        reserved,
        available,
        networkOnHand,
        networkReserved,
        networkAvailable,
        r.get("run_id", UUID.class),
        r.get("run_at", OffsetDateTime.class),
        r.get("run_method", String.class),
        decimalOf(r, "run_available"),
        decimalOf(r, "run_allocatable"),
        decimalOf(r, "run_unallocated"),
        decimalOf(r, "run_allocated_total"),
        demand,
        demandRegistered,
        r.get("demand_window_start", LocalDate.class),
        r.get("demand_window_end", LocalDate.class),
        intOf(r, "demand_shop_count"),
        coverage,
        daysOfCover,
        state,
        severity,
        demand == null || !demandRegistered ? DemandState.UNKNOWN : DemandState.KNOWN,
        note,
        recommendation);
  }

  /** 缺货侧的建议生成。 */
  private AlertRecommendation stockoutRecommendation(
      StockoutState state,
      BigDecimal demand,
      BigDecimal networkAvailable,
      BigDecimal available,
      UUID skuId,
      UUID warehouseId,
      Record r) {
    UUID targetWarehouse = warehouseId;
    if (state == StockoutState.NO_LEDGER_RECORD) {
      return new AlertRecommendation(
          RecommendationKind.NONE,
          "",
          "",
          null,
          null,
          null,
          null,
          null,
          "账本里查不到这个 SKU 的库存记录 —— 「不知道」不是「0 件」，"
              + "所以本模块给不出补货量。先核对库存台账（7.1 库存账本）再评估一次。");
    }
    if (state == StockoutState.UNKNOWN_DEMAND) {
      return new AlertRecommendation(
          RecommendationKind.REGISTER_SALES,
          "allocation_shop_sales",
          "7.8.1.1 销量登记",
          null,
          null,
          null,
          null,
          null,
          "没有销量登记，算不出该补多少 —— 先在 7.8.1.1 登记该 SKU 的销量窗口，再评估一次。");
    }
    if (state == StockoutState.HEALTHY) {
      return new AlertRecommendation(
          RecommendationKind.NONE, "", "", null, null, null, null, null, "供给正常，无需处置。");
    }
    if (state == StockoutState.WAREHOUSE_STOCKED_OUT) {
      return new AlertRecommendation(
          RecommendationKind.REPLENISH,
          "6.2.1.2",
          "6.2.1.2 补货需求（尚未交付）",
          null,
          null,
          targetWarehouse,
          available,
          null,
          "该仓账本可用量 0 件而全网还有 "
              + plain(networkAvailable)
              + " 件：先看 7.5 的仓间调拨，其次是补货到这个仓。"
              + "补货量要按需求算，而需求窗口来自 7.8.1.1 的 allocation_shop_sales。");
    }
    BigDecimal gap = demand.subtract(networkAvailable == null ? ZERO : networkAvailable);
    if (gap.signum() < 0) {
      gap = ZERO;
    }
    LocalDate from = r.get("demand_window_start", LocalDate.class);
    LocalDate to = r.get("demand_window_end", LocalDate.class);
    return new AlertRecommendation(
        RecommendationKind.REPLENISH,
        "6.2.1.2",
        "6.2.1.2 补货需求（尚未交付）",
        null,
        null,
        targetWarehouse,
        networkAvailable,
        gap,
        "全网账本可用量 "
            + plain(networkAvailable)
            + " 件，登记需求 "
            + plain(demand)
            + " 件"
            + (from == null || to == null ? "" : "（需求窗口 " + from + " ~ " + to + "）")
            + "，缺口 "
            + plain(gap)
            + " 件。按该窗口补货即可（补货需求单属 6.2.1.2，尚未交付 —— 本模块只给缺口件数，不落采购单）。");
  }

  // ============================================================ 落库

  private void upsertStarvation(StarvationDetection d, OffsetDateTime at) {
    sql.execute(
        "with t as (select ?::timestamptz as ts)"
            + " insert into starvation_alert (id,tenant_id,source_system,reference_id,reference_label,"
            + " shop_operating_mode,sku_id,window_start,window_end,observed_run_count,zero_round_count,"
            + " allocated_total,max_run_available_quantity,min_run_available_quantity,last_allocation_order,"
            + " last_outcome,last_run_id,demand_units,demand_registered,demand_window_start,"
            + " demand_window_end,coverage_ratio,policy_id,member_priority,member_share_limit,member_enabled,"
            + " floor_order,floor_status,floor_shortfall_quantity,floor_suppressed,floor_suppression_reason,"
            + " floor_committed_mode,floor_committed_ratio,floor_committed_quantity,floor_safety_quantity,"
            + " alert_state,severity,demand_state,root_cause,note,recommendation_kind,recommendation_target,"
            + " recommendation_target_label,recommendation_policy_id,recommendation_reference_id,"
            + " recommendation_current_value,recommendation_suggested_value,recommendation_reason,"
            + " lifecycle_state,recurrence_count,detected_at,created_at,updated_at)"
            + " select ?::uuid,?::uuid,?::text,?::uuid,?::text,?::text,?::uuid,?::timestamptz,?::timestamptz,?,?,"
            + " ?::numeric,?::numeric,?::numeric,?,?::text,?::uuid,?::numeric,?,?::date,?::date,?::numeric,"
            + " ?::uuid,?,?::numeric,?,?,?::text,?::numeric,?,?::text,?::text,?::numeric,?::numeric,?::numeric,"
            + " ?::text,?::text,?::text,?::text,?::text,?::text,?::text,?::text,?::uuid,?::uuid,?::numeric,"
            + " ?::numeric,?::text,'OPEN',0,t.ts,t.ts,t.ts from t where true"
            + " on conflict (tenant_id,sku_id,reference_id) do update set"
            + " window_start = excluded.window_start, window_end = excluded.window_end,"
            + " observed_run_count = excluded.observed_run_count, zero_round_count = excluded.zero_round_count,"
            + " allocated_total = excluded.allocated_total,"
            + " max_run_available_quantity = excluded.max_run_available_quantity,"
            + " min_run_available_quantity = excluded.min_run_available_quantity,"
            + " last_allocation_order = excluded.last_allocation_order, last_outcome = excluded.last_outcome,"
            + " last_run_id = excluded.last_run_id, demand_units = excluded.demand_units,"
            + " demand_registered = excluded.demand_registered, demand_window_start = excluded.demand_window_start,"
            + " demand_window_end = excluded.demand_window_end, coverage_ratio = excluded.coverage_ratio,"
            + " policy_id = excluded.policy_id, member_priority = excluded.member_priority,"
            + " member_share_limit = excluded.member_share_limit, member_enabled = excluded.member_enabled,"
            + " floor_order = excluded.floor_order, floor_status = excluded.floor_status,"
            + " floor_shortfall_quantity = excluded.floor_shortfall_quantity,"
            + " floor_suppressed = excluded.floor_suppressed,"
            + " floor_suppression_reason = excluded.floor_suppression_reason,"
            + " floor_committed_mode = excluded.floor_committed_mode,"
            + " floor_committed_ratio = excluded.floor_committed_ratio,"
            + " floor_committed_quantity = excluded.floor_committed_quantity,"
            + " floor_safety_quantity = excluded.floor_safety_quantity,"
            + " alert_state = excluded.alert_state, severity = excluded.severity,"
            + " demand_state = excluded.demand_state, root_cause = excluded.root_cause,"
            + " note = excluded.note, recommendation_kind = excluded.recommendation_kind,"
            + " recommendation_target = excluded.recommendation_target,"
            + " recommendation_target_label = excluded.recommendation_target_label,"
            + " recommendation_policy_id = excluded.recommendation_policy_id,"
            + " recommendation_reference_id = excluded.recommendation_reference_id,"
            + " recommendation_current_value = excluded.recommendation_current_value,"
            + " recommendation_suggested_value = excluded.recommendation_suggested_value,"
            + " recommendation_reason = excluded.recommendation_reason,"
            + " lifecycle_state = case when starvation_alert.lifecycle_state = 'RESOLVED' then 'OPEN'"
            + "   else starvation_alert.lifecycle_state end,"
            + " acknowledged_by_membership_id = case when starvation_alert.lifecycle_state = 'RESOLVED'"
            + "   then null else starvation_alert.acknowledged_by_membership_id end,"
            + " acknowledged_at = case when starvation_alert.lifecycle_state = 'RESOLVED' then null"
            + "   else starvation_alert.acknowledged_at end,"
            + " resolved_at = case when starvation_alert.lifecycle_state = 'RESOLVED' then null"
            + "   else starvation_alert.resolved_at end,"
            // 复发重开时清掉上一次的处置说明 ——「不再命中」在问题重新出现之后就是错的。
            + " resolution_note = case when starvation_alert.lifecycle_state = 'RESOLVED' then ''"
            + "   else starvation_alert.resolution_note end,"
            + " recurrence_count = case when starvation_alert.lifecycle_state = 'RESOLVED'"
            + "   then starvation_alert.recurrence_count + 1 else starvation_alert.recurrence_count end,"
            + " detected_at = excluded.detected_at, updated_at = excluded.updated_at",
        at,
        UUID.randomUUID(),
        tenant(),
        d.sourceSystem(),
        d.referenceId(),
        d.referenceLabel(),
        d.shopOperatingMode(),
        d.skuId(),
        d.windowStart(),
        d.windowEnd(),
        d.runCount(),
        d.zeroRoundCount(),
        d.allocatedTotal(),
        d.maxRunAvailable(),
        d.minRunAvailable(),
        d.lastAllocationOrder(),
        d.lastOutcome(),
        d.lastRunId(),
        d.demandUnits(),
        d.demandRegistered(),
        d.demandWindowStart(),
        d.demandWindowEnd(),
        d.coverageRatio(),
        d.policyId(),
        d.memberPriority(),
        d.memberShareLimit(),
        d.memberEnabled(),
        d.floorOrder(),
        d.floorStatus(),
        d.floorShortfallQuantity(),
        d.floorSuppressed(),
        d.floorSuppressionReason(),
        d.floorCommittedMode(),
        d.floorCommittedRatio(),
        d.floorCommittedQuantity(),
        d.floorSafetyQuantity(),
        d.state().name(),
        d.severity().name(),
        d.demandState().name(),
        d.rootCause().name(),
        d.note(),
        d.recommendation().kind().name(),
        d.recommendation().target(),
        d.recommendation().targetLabel(),
        d.recommendation().policyId(),
        d.recommendation().referenceId(),
        d.recommendation().currentValue(),
        d.recommendation().suggestedValue(),
        d.recommendation().reason());
  }

  private void upsertStockout(StockoutDetection d, OffsetDateTime at) {
    boolean networkRow = d.warehouseId() == null;
    String conflict =
        networkRow
            ? " on conflict (tenant_id,sku_id) where warehouse_id is null do update set"
            : " on conflict (tenant_id,sku_id,warehouse_id) where warehouse_id is not null do update set";
    sql.execute(
        "with t as (select ?::timestamptz as ts)"
            + " insert into stockout_alert (id,tenant_id,sku_id,warehouse_id,warehouse_code,warehouse_name,"
            + " on_hand_quantity,reserved_quantity,available_quantity,network_on_hand_quantity,"
            + " network_reserved_quantity,network_available_quantity,run_id,run_at,run_method,"
            + " run_available_quantity,run_allocatable_quantity,run_unallocated_quantity,run_allocated_total,"
            + " demand_units,demand_registered,demand_window_start,demand_window_end,demand_shop_count,"
            + " coverage_ratio,days_of_cover,alert_state,severity,demand_state,note,recommendation_kind,"
            + " recommendation_target,recommendation_target_label,recommendation_sku_id,"
            + " recommendation_warehouse_id,recommendation_current_value,recommendation_suggested_value,"
            + " recommendation_reason,lifecycle_state,recurrence_count,detected_at,created_at,updated_at)"
            + " select ?::uuid,?::uuid,?::uuid,?::uuid,?::text,?::text,?::numeric,?::numeric,?::numeric,"
            + " ?::numeric,?::numeric,?::numeric,?::uuid,?::timestamptz,?::text,?::numeric,?::numeric,"
            + " ?::numeric,?::numeric,?::numeric,?,?::date,?::date,?,?::numeric,?::numeric,?::text,?::text,"
            + " ?::text,?::text,?::text,?::text,?::text,?::uuid,?::uuid,?::numeric,?::numeric,?::text,"
            + " 'OPEN',0,t.ts,t.ts,t.ts from t where true"
            + conflict
            + " warehouse_code = excluded.warehouse_code, warehouse_name = excluded.warehouse_name,"
            + " on_hand_quantity = excluded.on_hand_quantity, reserved_quantity = excluded.reserved_quantity,"
            + " available_quantity = excluded.available_quantity,"
            + " network_on_hand_quantity = excluded.network_on_hand_quantity,"
            + " network_reserved_quantity = excluded.network_reserved_quantity,"
            + " network_available_quantity = excluded.network_available_quantity,"
            + " run_id = excluded.run_id, run_at = excluded.run_at, run_method = excluded.run_method,"
            + " run_available_quantity = excluded.run_available_quantity,"
            + " run_allocatable_quantity = excluded.run_allocatable_quantity,"
            + " run_unallocated_quantity = excluded.run_unallocated_quantity,"
            + " run_allocated_total = excluded.run_allocated_total, demand_units = excluded.demand_units,"
            + " demand_registered = excluded.demand_registered,"
            + " demand_window_start = excluded.demand_window_start, demand_window_end = excluded.demand_window_end,"
            + " demand_shop_count = excluded.demand_shop_count, coverage_ratio = excluded.coverage_ratio,"
            + " days_of_cover = excluded.days_of_cover, alert_state = excluded.alert_state,"
            + " severity = excluded.severity, demand_state = excluded.demand_state, note = excluded.note,"
            + " recommendation_kind = excluded.recommendation_kind,"
            + " recommendation_target = excluded.recommendation_target,"
            + " recommendation_target_label = excluded.recommendation_target_label,"
            + " recommendation_sku_id = excluded.recommendation_sku_id,"
            + " recommendation_warehouse_id = excluded.recommendation_warehouse_id,"
            + " recommendation_current_value = excluded.recommendation_current_value,"
            + " recommendation_suggested_value = excluded.recommendation_suggested_value,"
            + " recommendation_reason = excluded.recommendation_reason,"
            + " lifecycle_state = case when stockout_alert.lifecycle_state = 'RESOLVED' then 'OPEN'"
            + "   else stockout_alert.lifecycle_state end,"
            + " acknowledged_by_membership_id = case when stockout_alert.lifecycle_state = 'RESOLVED'"
            + "   then null else stockout_alert.acknowledged_by_membership_id end,"
            + " acknowledged_at = case when stockout_alert.lifecycle_state = 'RESOLVED' then null"
            + "   else stockout_alert.acknowledged_at end,"
            + " resolved_at = case when stockout_alert.lifecycle_state = 'RESOLVED' then null"
            + "   else stockout_alert.resolved_at end,"
            // 复发重开时清掉上一次的处置说明 ——「不再命中」在问题重新出现之后就是错的。
            + " resolution_note = case when stockout_alert.lifecycle_state = 'RESOLVED' then ''"
            + "   else stockout_alert.resolution_note end,"
            + " recurrence_count = case when stockout_alert.lifecycle_state = 'RESOLVED'"
            + "   then stockout_alert.recurrence_count + 1 else stockout_alert.recurrence_count end,"
            + " detected_at = excluded.detected_at, updated_at = excluded.updated_at",
        at,
        UUID.randomUUID(),
        tenant(),
        d.skuId(),
        d.warehouseId(),
        d.warehouseCode(),
        d.warehouseName(),
        d.onHandQuantity(),
        d.reservedQuantity(),
        d.availableQuantity(),
        d.networkOnHandQuantity(),
        d.networkReservedQuantity(),
        d.networkAvailableQuantity(),
        d.runId(),
        d.runAt(),
        d.runMethod(),
        d.runAvailableQuantity(),
        d.runAllocatableQuantity(),
        d.runUnallocatedQuantity(),
        d.runAllocatedTotal(),
        d.demandUnits(),
        d.demandRegistered(),
        d.demandWindowStart(),
        d.demandWindowEnd(),
        d.demandShopCount(),
        d.coverageRatio(),
        d.daysOfCover(),
        d.state().name(),
        d.severity().name(),
        d.demandState().name(),
        d.note(),
        d.recommendation().kind().name(),
        d.recommendation().target(),
        d.recommendation().targetLabel(),
        d.skuId(),
        d.recommendation().warehouseId(),
        d.recommendation().currentValue(),
        d.recommendation().suggestedValue(),
        d.recommendation().reason());
  }

  /** 这次评估没再命中的旧预警自动关闭（它们不无声消失，而是留下一条「已自动关闭」）。 */
  private void closeMissingStarvations(OffsetDateTime at, UUID skuId, UUID referenceId) {
    sql.execute(
        "with t as (select ?::timestamptz as ts)"
            + " update starvation_alert s set lifecycle_state = 'RESOLVED', resolved_at = t.ts,"
            + " resolution_note = '重新评估后不再命中（系统自动关闭）', updated_at = t.ts"
            + " from t where s.tenant_id = current_setting('app.current_tenant')::uuid"
            + " and s.lifecycle_state <> 'RESOLVED' and s.detected_at < t.ts"
            + " and (?::uuid is null or s.sku_id = ?::uuid)"
            + " and (?::uuid is null or s.reference_id = ?::uuid)",
        at,
        skuId,
        skuId,
        referenceId,
        referenceId);
  }

  private void closeMissingStockouts(OffsetDateTime at, UUID skuId, UUID warehouseId) {
    sql.execute(
        "with t as (select ?::timestamptz as ts)"
            + " update stockout_alert s set lifecycle_state = 'RESOLVED', resolved_at = t.ts,"
            + " resolution_note = '重新评估后不再命中（系统自动关闭）', updated_at = t.ts"
            + " from t where s.tenant_id = current_setting('app.current_tenant')::uuid"
            + " and s.lifecycle_state <> 'RESOLVED' and s.detected_at < t.ts"
            + " and (?::uuid is null or s.sku_id = ?::uuid)"
            + " and (?::uuid is null or s.warehouse_id = ?::uuid)",
        at,
        skuId,
        skuId,
        warehouseId,
        warehouseId);
  }

  // ============================================================ 行映射

  private static StarvationAlertRow starvationRow(Record r) {
    return new StarvationAlertRow(
        r.get("id", UUID.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("source_system", String.class),
        r.get("reference_id", UUID.class),
        r.get("reference_label", String.class),
        r.get("shop_operating_mode", String.class),
        r.get("policy_id", UUID.class),
        r.get("window_start", OffsetDateTime.class),
        r.get("window_end", OffsetDateTime.class),
        intOf(r, "observed_run_count"),
        intOf(r, "zero_round_count"),
        decimalOf(r, "allocated_total"),
        decimalOf(r, "max_run_available_quantity"),
        decimalOf(r, "min_run_available_quantity"),
        r.get("last_allocation_order", Integer.class),
        r.get("last_outcome", String.class),
        r.get("last_run_id", UUID.class),
        decimalOf(r, "demand_units"),
        Boolean.TRUE.equals(r.get("demand_registered", Boolean.class)),
        r.get("demand_window_start", LocalDate.class),
        r.get("demand_window_end", LocalDate.class),
        decimalOf(r, "coverage_ratio"),
        r.get("member_priority", Integer.class),
        decimalOf(r, "member_share_limit"),
        r.get("member_enabled", Boolean.class),
        r.get("floor_order", Integer.class),
        r.get("floor_status", String.class),
        decimalOf(r, "floor_shortfall_quantity"),
        r.get("floor_suppressed", Boolean.class),
        r.get("floor_suppression_reason", String.class),
        r.get("floor_committed_mode", String.class),
        decimalOf(r, "floor_committed_ratio"),
        decimalOf(r, "floor_committed_quantity"),
        decimalOf(r, "floor_safety_quantity"),
        StarvationState.valueOf(r.get("alert_state", String.class)),
        Severity.valueOf(r.get("severity", String.class)),
        DemandState.valueOf(r.get("demand_state", String.class)),
        StarvationRootCause.valueOf(r.get("root_cause", String.class)),
        r.get("note", String.class),
        new AlertRecommendation(
            RecommendationKind.valueOf(r.get("recommendation_kind", String.class)),
            r.get("recommendation_target", String.class),
            r.get("recommendation_target_label", String.class),
            r.get("recommendation_policy_id", UUID.class),
            r.get("recommendation_reference_id", UUID.class),
            // 饥饿侧的建议永远不指向仓：它要么调保底、要么调名册、要么去登记销量。
            null,
            decimalOf(r, "recommendation_current_value"),
            decimalOf(r, "recommendation_suggested_value"),
            r.get("recommendation_reason", String.class)),
        LifecycleState.valueOf(r.get("lifecycle_state", String.class)),
        r.get("acknowledged_by_membership_id", UUID.class),
        r.get("acknowledged_at", OffsetDateTime.class),
        r.get("resolved_at", OffsetDateTime.class),
        r.get("resolution_note", String.class),
        intOf(r, "recurrence_count"),
        r.get("detected_at", OffsetDateTime.class));
  }

  private static StockoutAlertRow stockoutRow(Record r) {
    return new StockoutAlertRow(
        r.get("id", UUID.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("warehouse_id", UUID.class),
        r.get("warehouse_code", String.class),
        r.get("warehouse_name", String.class),
        decimalOf(r, "on_hand_quantity"),
        decimalOf(r, "reserved_quantity"),
        decimalOf(r, "available_quantity"),
        decimalOf(r, "network_on_hand_quantity"),
        decimalOf(r, "network_reserved_quantity"),
        decimalOf(r, "network_available_quantity"),
        r.get("run_id", UUID.class),
        r.get("run_at", OffsetDateTime.class),
        r.get("run_method", String.class),
        decimalOf(r, "run_available_quantity"),
        decimalOf(r, "run_allocatable_quantity"),
        decimalOf(r, "run_unallocated_quantity"),
        decimalOf(r, "run_allocated_total"),
        decimalOf(r, "demand_units"),
        Boolean.TRUE.equals(r.get("demand_registered", Boolean.class)),
        r.get("demand_window_start", LocalDate.class),
        r.get("demand_window_end", LocalDate.class),
        intOf(r, "demand_shop_count"),
        decimalOf(r, "coverage_ratio"),
        decimalOf(r, "days_of_cover"),
        StockoutState.valueOf(r.get("alert_state", String.class)),
        Severity.valueOf(r.get("severity", String.class)),
        DemandState.valueOf(r.get("demand_state", String.class)),
        r.get("note", String.class),
        new AlertRecommendation(
            RecommendationKind.valueOf(r.get("recommendation_kind", String.class)),
            r.get("recommendation_target", String.class),
            r.get("recommendation_target_label", String.class),
            // 缺货侧的建议不指向策略或店铺：它指向补货需求（6.2.1.2）或销量登记。
            null,
            null,
            r.get("recommendation_warehouse_id", UUID.class),
            decimalOf(r, "recommendation_current_value"),
            decimalOf(r, "recommendation_suggested_value"),
            r.get("recommendation_reason", String.class)),
        LifecycleState.valueOf(r.get("lifecycle_state", String.class)),
        r.get("acknowledged_by_membership_id", UUID.class),
        r.get("acknowledged_at", OffsetDateTime.class),
        r.get("resolved_at", OffsetDateTime.class),
        r.get("resolution_note", String.class),
        intOf(r, "recurrence_count"),
        r.get("detected_at", OffsetDateTime.class));
  }

  // ============================================================ 工具

  /** 当前租户（RLS 上下文里已经绑好，这里只是把它读出来用于显式绑定）。 */
  private UUID tenant() {
    return UUID.fromString(
        sql.fetchOne("select current_setting('app.current_tenant')::uuid").get(0, String.class));
  }

  private Record lockStarvation(UUID id) {
    var row =
        sql.fetchOne(
            "select * from starvation_alert where tenant_id = current_setting('app.current_tenant')::uuid"
                + " and id = ?::uuid for update",
            id);
    if (row == null) {
      throw bad("找不到这条饥饿预警（可能已被别的租户隔离，或 id 不存在）");
    }
    return row;
  }

  private Record lockStockout(UUID id) {
    var row =
        sql.fetchOne(
            "select * from stockout_alert where tenant_id = current_setting('app.current_tenant')::uuid"
                + " and id = ?::uuid for update",
            id);
    if (row == null) {
      throw bad("找不到这条缺货预警（可能已被别的租户隔离，或 id 不存在）");
    }
    return row;
  }

  private long count(String statement, Object... binds) {
    return sql.fetchOne(statement, binds).get(0, Long.class);
  }

  private static long longOf(Record r, String column) {
    if (r == null) {
      return 0L;
    }
    Long v = r.get(column, Long.class);
    return v == null ? 0L : v;
  }

  private static int intOf(Record r, String column) {
    if (r == null) {
      return 0;
    }
    Integer v = r.get(column, Integer.class);
    return v == null ? 0 : v;
  }

  private static BigDecimal decimalOf(Record r, String column) {
    if (r == null) {
      return null;
    }
    return r.get(column, BigDecimal.class);
  }

  private static String plain(BigDecimal v) {
    return v == null ? "—" : v.stripTrailingZeros().toPlainString();
  }

  private static int clampRounds(Integer v) {
    if (v == null) {
      return DEFAULT_WINDOW_ROUNDS;
    }
    if (v < 1 || v > MAX_WINDOW_ROUNDS) {
      throw bad("windowRounds 必须在 1 ~ " + MAX_WINDOW_ROUNDS + " 之间，收到 " + v);
    }
    return v;
  }

  private static int clampDays(Integer v) {
    if (v == null) {
      return DEFAULT_DEMAND_WINDOW_DAYS;
    }
    if (v < 1 || v > 365) {
      throw bad("demandWindowDays 必须在 1 ~ 365 之间，收到 " + v);
    }
    return v;
  }

  private static BigDecimal ratio(BigDecimal v, BigDecimal fallback) {
    if (v == null) {
      return fallback;
    }
    if (v.signum() < 0 || v.compareTo(BigDecimal.ONE) > 0) {
      throw bad("阈值必须是 0 ~ 1 之间的比例，收到 " + v.toPlainString());
    }
    return v;
  }

  private static StarvationScope starvationScope(String raw) {
    if (raw == null || raw.isBlank()) {
      return StarvationScope.ALL;
    }
    try {
      return StarvationScope.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad("未知的饥饿清单口径：" + raw);
    }
  }

  private static StockoutScope stockoutScope(String raw) {
    if (raw == null || raw.isBlank()) {
      return StockoutScope.ALL;
    }
    try {
      return StockoutScope.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad("未知的缺货清单口径：" + raw);
    }
  }

  private static Severity severity(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Severity.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad("未知的严重度：" + raw);
    }
  }

  private static LifecycleState lifecycle(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return LifecycleState.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad("未知的处置状态：" + raw);
    }
  }

  private static String starvationScopeClause(StarvationScope scope) {
    return switch (scope) {
      case ALL -> "";
      case PROBLEM -> " and s.alert_state in ('STARVED','UNDER_SUPPLIED')";
      case STARVED -> " and s.alert_state = 'STARVED'";
      case UNDER_SUPPLIED -> " and s.alert_state = 'UNDER_SUPPLIED'";
      case UNKNOWN -> " and s.alert_state = 'UNKNOWN_DEMAND'";
      case OPEN -> " and s.lifecycle_state <> 'RESOLVED'";
    };
  }

  private static String stockoutScopeClause(StockoutScope scope) {
    return switch (scope) {
      case ALL -> "";
      case PROBLEM ->
          " and s.alert_state in ('NETWORK_STOCKED_OUT','WAREHOUSE_STOCKED_OUT','AT_RISK',"
              + "'NO_LEDGER_RECORD')";
      case STOCKED_OUT ->
          " and s.alert_state in ('NETWORK_STOCKED_OUT','WAREHOUSE_STOCKED_OUT')";
      case AT_RISK -> " and s.alert_state = 'AT_RISK'";
      case UNKNOWN -> " and s.alert_state in ('UNKNOWN_DEMAND','NO_LEDGER_RECORD')";
      case OPEN -> " and s.lifecycle_state <> 'RESOLVED'";
    };
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, message);
  }

  // ------------------------------------------------------------ 内部结构

  /** 一次评估的结果载体（Java 侧，尚未落库）。 */
  private record StarvationDetection(
      UUID skuId,
      String skuCode,
      String sourceSystem,
      UUID referenceId,
      String referenceLabel,
      String shopOperatingMode,
      UUID policyId,
      OffsetDateTime windowStart,
      OffsetDateTime windowEnd,
      int runCount,
      int zeroRoundCount,
      BigDecimal allocatedTotal,
      BigDecimal maxRunAvailable,
      BigDecimal minRunAvailable,
      Integer lastAllocationOrder,
      String lastOutcome,
      UUID lastRunId,
      BigDecimal demandUnits,
      boolean demandRegistered,
      LocalDate demandWindowStart,
      LocalDate demandWindowEnd,
      BigDecimal coverageRatio,
      Integer memberPriority,
      BigDecimal memberShareLimit,
      Boolean memberEnabled,
      Integer floorOrder,
      String floorStatus,
      BigDecimal floorShortfallQuantity,
      Boolean floorSuppressed,
      String floorSuppressionReason,
      String floorCommittedMode,
      BigDecimal floorCommittedRatio,
      BigDecimal floorCommittedQuantity,
      BigDecimal floorSafetyQuantity,
      StarvationState state,
      Severity severity,
      DemandState demandState,
      StarvationRootCause rootCause,
      String note,
      AlertRecommendation recommendation,
      boolean networkStockout) {}

  /** 一次评估的结果载体（Java 侧，尚未落库）。 */
  private record StockoutDetection(
      UUID skuId,
      String skuCode,
      UUID warehouseId,
      String warehouseCode,
      String warehouseName,
      BigDecimal onHandQuantity,
      BigDecimal reservedQuantity,
      BigDecimal availableQuantity,
      BigDecimal networkOnHandQuantity,
      BigDecimal networkReservedQuantity,
      BigDecimal networkAvailableQuantity,
      UUID runId,
      OffsetDateTime runAt,
      String runMethod,
      BigDecimal runAvailableQuantity,
      BigDecimal runAllocatableQuantity,
      BigDecimal runUnallocatedQuantity,
      BigDecimal runAllocatedTotal,
      BigDecimal demandUnits,
      boolean demandRegistered,
      LocalDate demandWindowStart,
      LocalDate demandWindowEnd,
      int demandShopCount,
      BigDecimal coverageRatio,
      BigDecimal daysOfCover,
      StockoutState state,
      Severity severity,
      DemandState demandState,
      String note,
      AlertRecommendation recommendation) {

    boolean isProblem() {
      return state != StockoutState.HEALTHY;
    }
  }
}
