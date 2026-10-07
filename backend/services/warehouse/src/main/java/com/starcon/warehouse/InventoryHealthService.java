package com.starcon.warehouse;

import static com.starcon.warehouse.InventoryHealthModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 7.6.2.1 库存健康。
 *
 * <p>蓝图：「库存健康度诊断：周转率、缺货率、超储率、呆滞占比、库存准确率、负库存发生次数，
 * 按仓库与类目打分并给出改进建议」「输出：健康度评分卡 + 趋势 + 问题清单与建议」
 * 「**库存准确率是硬指标**，低于阈值应阻断结算与对账——账不准时报表全是错的」。
 *
 * <p><b>本模块只对算得出来的指标给值。</b>六个指标里当前真实可算的是两个：
 *
 * <table border="1">
 *   <caption>指标可算性</caption>
 *   <tr><th>指标</th><th>结论</th><th>依据</th></tr>
 *   <tr><td>库存准确率</td><td>可算</td><td>7.1.2.2 核对（V54）+ 7.4.2.1 盘点（V59）</td></tr>
 *   <tr><td>负库存发生次数</td><td>可算（恒为 0）</td><td>V1/V2 的 CHECK 在结构上排除负库存</td></tr>
 *   <tr><td>周转率 / 缺货率 / 超储率</td><td>不可用</td><td>依赖 13 销量，warehouse 不持有销售事实</td></tr>
 *   <tr><td>呆滞占比</td><td>不可用</td><td>依赖 7.6.1 库龄与呆滞（同批在途，未落库）</td></tr>
 * </table>
 *
 * <p>拿库存自身数据拼一个假的周转率，比不给指标危害大得多：假指标会被当真去调补货，
 * 而真正的缺口一个也没被修掉。所以四个不可用的指标照样占一行、照样有 basis 与
 * blockedByModule —— 它们是要被追债的信息，不是可以省略的噪声。
 *
 * <p><b>准确率口径（全模块唯一一套）</b>。两个真实来源，按优先级合成一个值：
 *
 * <ol>
 *   <li><b>核对口径（优先）</b>：窗口内<b>最后一次</b>该范围的核对批次，
 *       {@code (checked_balance_count − discrepancy_count) / checked_balance_count}。
 *       取最后一次而不是把窗口内所有批次平均：重复核对同一批余额行会让分母被数几遍，
 *       平均出来的数没有口径。历史批次不丢 —— 它们在趋势里。
 *   <li><b>盘点口径（回落）</b>：窗口内全部已录实盘数的盘点行，
 *       {@code 账面数=实盘数的行数 / 已录入行数}。逐行取自不可变的 {@code stocktake_line}，
 *       跨单相加不会重复计数（每行只属于一张盘点单）。
 * </ol>
 *
 * <p><b>打分只对可算指标加权</b>，并把覆盖度一起返回。六个指标里算得出一个时，
 * 那个分数必须让人看出它是「已覆盖维度上的分数」而不是「整体健康度」——
 * 否则一个 100 分会被当成「这个仓没问题」，掩盖另外五个维度根本没测。
 *
 * <p><b>准确率红线只做判定，不代下游执行</b>。蓝图说低于阈值「应阻断结算与对账」，
 * 但阻断动作发生在 12 财务结算与 7.1.2.2 对账各自的边界内；warehouse 无权知道
 * 它们的单据状态，也不应该替它们扣单。本服务给出的是一个<b>可查询、可复现、带证据</b>的判定
 * （{@link InventoryHealthGate}），接入状态见会话日志的后续待办。
 *
 * <p>三条实现纪律：
 *
 * <ul>
 *   <li><b>时间绑定</b>：{@code ?::timestamptz} 集中在最前面的 {@code win} CTE 里，
 *       后面所有 {@code ? } 都是普通绑定 —— cast 落在绑定序列末位会让 jOOQ 整体错位一格
 *       （与 7.1.2.2 同一纪律）。
 *   <li><b>列一律按列名读</b>（{@code row.get("col")}）而不是按下标：纯文本 SQL 的列序
 *       在改查询时会悄悄漂移，按下标读会把另一个字段当成准确率算进扣分里。
 *   <li><b>写入逐行 execute</b>，不用 batch：本仓库统一这么写，原因与 7.1.2.1 相同 ——
 *       纯文本语句上带 {@code ?::cast} 的 batch 绑定不可靠。
 * </ul>
 */
@Service
public class InventoryHealthService {

  /** 单页最大行数：健康度评分卡按仓库聚合，一页给不完整个仓库列表。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 窗口缺省天数：与 7.1.2.2 核对窗口同一套默认值，让「最近一次核对」落在窗口内。 */
  static final int DEFAULT_WINDOW_DAYS = 30;

  /** 准确率缺省阈值 99%。蓝图没给数字，这个值取自对账实务里「差一行就要追」的口径。 */
  static final BigDecimal DEFAULT_ACCURACY_THRESHOLD = new BigDecimal("0.99");

  /**
   * 可算指标在 100 分制里的权重。
   *
   * <p>准确率给 70：它是蓝图点名的硬指标。负库存给 30：它恒为 0，权重只决定
   * 「万一哪天 DB 约束被改掉了」时后果有多严重 —— 一旦真出现负库存，那是账已经错了，
   * 不该只扣 30 分。
   *
   * <p>不可算的指标<b>不占权重也不扣分</b>：权重只在 {@link MetricAvailability#AVAILABLE} 时计入。
   */
  static final BigDecimal WEIGHT_ACCURACY = new BigDecimal("70");

  static final BigDecimal WEIGHT_NEGATIVE_STOCK = new BigDecimal("30");

  private static final BigDecimal ZERO = BigDecimal.ZERO;
  private static final BigDecimal ONE = BigDecimal.ONE;
  private static final BigDecimal HUNDRED = new BigDecimal("100");
  private static final BigDecimal ZERO2 = new BigDecimal("0.00");

  /**
   * 核对区间绑定。
   *
   * <p>两个 {@code ?::timestamptz} 刻意放在整条语句最前面，理由见类注释。
   */
  private static final String WIN = "with win as (select ?::timestamptz as from_at, ?::timestamptz as to_at) ";

  private final DSLContext sql;

  public InventoryHealthService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 写：执行一次评估

  /**
   * 执行一次评估并落库。
   *
   * <p><b>一次事务</b>：评分卡（每个范围 6 行指标）与结论行同生共死。半截的评估比没有更糟 ——
   * 结算前置校验可能拿它当「已判定账准」的依据。
   */
  @Transactional
  public InventoryHealthAssessed assess(InventoryHealthQuery query, UUID actorId) {
    var window = window(query);
    var threshold = threshold(query);
    var evaluatedAt = now();

    var tenantScope = new Scope(HealthScopeType.TENANT, null, "全部仓库");
    var tenantCard = card(tenantScope, window, threshold, evaluatedAt);

    var warehouseCards = new ArrayList<InventoryHealthScorecard>();
    for (var wh : warehouses(query)) {
      var scope = new Scope(HealthScopeType.WAREHOUSE, wh.id(), wh.code() + " " + wh.name());
      warehouseCards.add(card(scope, window, threshold, evaluatedAt));
    }

    var tenantGate = gateFor(tenantScope, window, threshold, tenantCard, evaluatedAt);
    var warehouseGates = new ArrayList<InventoryHealthGate>();
    for (var wc : warehouseCards) {
      var scope = new Scope(HealthScopeType.WAREHOUSE, wc.scopeKey(), wc.scopeLabel());
      warehouseGates.add(gateFor(scope, window, threshold, wc, evaluatedAt));
    }

    // 每个范围一份独立的 assessment（结论行 1:1 指向它），租户行与仓库行互不覆盖。
    persistAssessment(tenantScope, window, threshold, tenantCard, evaluatedAt);
    persistVerdict(tenantScope, threshold, tenantCard, tenantGate, issues(tenantCard), actorId, evaluatedAt);
    for (var i = 0; i < warehouseCards.size(); i++) {
      var wc = warehouseCards.get(i);
      var scope = new Scope(HealthScopeType.WAREHOUSE, wc.scopeKey(), wc.scopeLabel());
      persistAssessment(scope, window, threshold, wc, evaluatedAt);
      persistVerdict(scope, threshold, wc, warehouseGates.get(i), issues(wc), actorId, evaluatedAt);
    }

    return new InventoryHealthAssessed(tenantCard, warehouseCards, tenantGate);
  }

  // ================================================================ 读：评分卡

  /** 评分卡列表（读最近一次落库的评估，不重新评估 —— 重评是写动作，有独立的端点）。 */
  public InventoryHealthScorecardPage page(InventoryHealthQuery query, int page, int size) {
    int limit = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int offset = Math.max(page, 0) * limit;

    var where = new StringBuilder(" where v.evaluated_at = (select max(evaluated_at) from inventory_health_verdict)");
    var binds = new ArrayList<Object>();
    if (query.warehouseId() != null) {
      where.append(" and v.scope_key = ?");
      binds.add(query.warehouseId());
    }
    var sqlWhere = where.toString();
    var from =
        " from inventory_health_verdict v"
            + " join inventory_health_assessment a on a.tenant_id = v.tenant_id"
            + " and a.id = v.assessment_id";

    long total =
        sql.fetchOne("select count(*)" + from + sqlWhere, binds.toArray()).get("count", Long.class);

    var rows =
        sql.fetch(
            "select v.id, v.scope_type, v.scope_key, v.scope_label, v.score, v.grade,"
                + " a.accuracy_threshold, v.accuracy_rate, v.accuracy_state, v.blocked,"
                + " v.block_reason, v.issue_count, v.critical_issue_count, v.summary, v.evaluated_at"
                + from
                + sqlWhere
                + " order by (v.scope_type='TENANT') desc, v.scope_label, v.id"
                + " limit ? offset ?",
            concat(binds, limit, offset));

    var cards = new ArrayList<InventoryHealthScorecard>();
    for (var r : rows) {
      cards.add(storedCard(r));
    }
    return new InventoryHealthScorecardPage(cards, total, page, size);
  }

  /** 从落库的指标行重建评分卡，让读路径与写路径给的是同一套口径。 */
  private InventoryHealthScorecard storedCard(org.jooq.Record r) {
    var verdictId = r.get("id", UUID.class);
    var scopeType = HealthScopeType.valueOf(r.get("scope_type", String.class));
    var scopeKey = r.get("scope_key", UUID.class);
    var threshold = r.get("accuracy_threshold", BigDecimal.class);
    var grade = HealthGrade.valueOf(r.get("grade", String.class));

    var metrics = metricsOf(verdictId);
    var accuracy = metricOf(metrics, HealthMetricType.ACCURACY);
    var available =
        (int) metrics.stream().filter(m -> m.availability() == MetricAvailability.AVAILABLE).count();
    var window = windowOf(verdictId);

    return new InventoryHealthScorecard(
        scopeType,
        scopeKey,
        r.get("scope_label", String.class),
        r.get("score", BigDecimal.class),
        grade,
        grade.label(),
        available,
        metrics.size(),
        accuracy.value(),
        threshold,
        accuracy.availability() == MetricAvailability.AVAILABLE
            && accuracy.value().compareTo(threshold) >= 0,
        Boolean.TRUE.equals(r.get("blocked", Boolean.class)),
        nullSafe(r.get("block_reason", String.class)),
        window.from(),
        window.to(),
        r.get("evaluated_at", OffsetDateTime.class),
        metrics);
  }

  private List<InventoryHealthMetric> metricsOf(UUID verdictId) {
    return sql.fetch(
            "select m.metric_type, m.metric_state, m.value, m.metric_source, m.numerator,"
                + " m.denominator, m.reason, m.blocked_by_module, m.deduction"
                + " from inventory_health_metric m"
                + " join inventory_health_verdict v on v.tenant_id = m.tenant_id"
                + " and v.assessment_id = m.assessment_id"
                + " where v.id = ?"
                + " order by m.metric_type",
            verdictId)
        .map(m -> {
          var type = HealthMetricType.valueOf(m.get("metric_type", String.class));
          var state = MetricAvailability.valueOf(m.get("metric_state", String.class));
          var reason = nullSafe(m.get("reason", String.class));
          return new InventoryHealthMetric(
              type,
              type.label(),
              state,
              m.get("value", BigDecimal.class),
              m.get("metric_source", String.class),
              m.get("numerator", BigDecimal.class),
              m.get("denominator", BigDecimal.class),
              m.get("deduction", BigDecimal.class),
              // AVAILABLE 的 reason 是空串（口径写在 basis 里），所以退回枚举自带的说明。
              state == MetricAvailability.AVAILABLE && reason.isEmpty() ? type.basis() : reason,
              m.get("blocked_by_module", String.class));
        });
  }

  private static InventoryHealthMetric metricOf(List<InventoryHealthMetric> metrics, HealthMetricType type) {
    return metrics.stream()
        .filter(m -> m.metricType() == type)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("评估缺少指标行：" + type));
  }

  private Window windowOf(UUID verdictId) {
    var r = sql.fetchOne(
        "select a.window_from, a.window_to from inventory_health_assessment a"
            + " join inventory_health_verdict v on v.tenant_id = a.tenant_id and v.assessment_id = a.id"
            + " where v.id = ? limit 1",
        verdictId);
    return new Window(r.get("window_from", OffsetDateTime.class), r.get("window_to", OffsetDateTime.class));
  }

  // ================================================================ 读：趋势

  /** 准确率趋势：核对口径与盘点口径各自成序列，不混成一条线。 */
  public InventoryHealthTrend trend(InventoryHealthQuery query) {
    var window = window(query);
    var points = new ArrayList<InventoryHealthTrendPoint>();
    points.addAll(reconciliationPoints(window, query.warehouseId()));
    points.addAll(stocktakePoints(window, query.warehouseId()));
    points.sort(Comparator.comparing(InventoryHealthTrendPoint::at).thenComparing(InventoryHealthTrendPoint::source));

    var scopeType = query.warehouseId() == null ? HealthScopeType.TENANT : HealthScopeType.WAREHOUSE;
    var label = query.warehouseId() == null ? "全部仓库" : warehouseLabel(query.warehouseId());
    return new InventoryHealthTrend(scopeType, query.warehouseId(), label, window.from(), window.to(), points);
  }

  private List<InventoryHealthTrendPoint> reconciliationPoints(Window window, UUID warehouseId) {
    var where = new StringBuilder(" from inventory_reconciliation_run r cross join win")
        .append(" where r.created_at >= win.from_at and r.created_at < win.to_at");
    Object[] binds;
    if (warehouseId == null) {
      where.append(" and r.scope_type = 'ALL'");
      binds = new Object[] {window.from(), window.to()};
    } else {
      where.append(" and r.scope_type = 'WAREHOUSE' and r.scope_key = ?");
      binds = new Object[] {window.from(), window.to(), warehouseId};
    }
    return sql.fetch(
            WIN
                + "select r.id, r.scope_label, r.created_at, r.checked_balance_count, r.discrepancy_count"
                + where
                + " order by r.created_at desc, r.id desc",
            binds)
        .map(r -> {
          var checked = r.get("checked_balance_count", Long.class);
          var discrepancies = r.get("discrepancy_count", Long.class);
          return new InventoryHealthTrendPoint(
              "RECONCILIATION",
              r.get("id", UUID.class),
              r.get("scope_label", String.class),
              r.get("created_at", OffsetDateTime.class),
              rate(checked - discrepancies, checked),
              checked,
              discrepancies);
        });
  }

  private List<InventoryHealthTrendPoint> stocktakePoints(Window window, UUID warehouseId) {
    var where = new StringBuilder(" from stocktake_sheet s")
        .append(" join stocktake_line l on l.tenant_id = s.tenant_id and l.sheet_id = s.id")
        .append(" cross join win")
        .append(" where s.frozen_at >= win.from_at and s.frozen_at < win.to_at")
        .append(" and l.counted_qty is not null");
    Object[] binds;
    if (warehouseId == null) {
      binds = new Object[] {window.from(), window.to()};
    } else {
      where.append(" and l.warehouse_id = ?");
      binds = new Object[] {window.from(), window.to(), warehouseId};
    }
    return sql.fetch(
            WIN
                + "select s.id, s.code, s.scope_label, s.frozen_at,"
                + " count(*) as counted_lines, count(*) filter (where l.variance = 0) as accurate_lines"
                + where
                + " group by s.id, s.code, s.scope_label, s.frozen_at"
                + " order by s.frozen_at desc, s.id desc",
            binds)
        .map(r -> {
          var counted = r.get("counted_lines", Long.class);
          var accurate = r.get("accurate_lines", Long.class);
          return new InventoryHealthTrendPoint(
              "STOCKTAKE",
              r.get("id", UUID.class),
              r.get("code", String.class) + " " + r.get("scope_label", String.class),
              r.get("frozen_at", OffsetDateTime.class),
              rate(accurate, counted),
              counted,
              counted - accurate);
        });
  }

  private static BigDecimal rate(long accurate, long total) {
    if (total <= 0) return null;
    return BigDecimal.valueOf(Math.max(accurate, 0))
        .divide(BigDecimal.valueOf(total), 6, RoundingMode.HALF_UP);
  }

  // ================================================================ 读：红线判定

  /** 准确率红线判定（读最近一次落库的结论，不触发重评）。 */
  public InventoryHealthGate gate(InventoryHealthQuery query) {
    var threshold = threshold(query);
    var scopeType = query.warehouseId() == null ? HealthScopeType.TENANT : HealthScopeType.WAREHOUSE;
    var label = query.warehouseId() == null ? "全部仓库" : warehouseLabel(query.warehouseId());
    var window = window(query);

    var row =
        query.warehouseId() == null
            ? sql.fetchOne(
                "select v.id, v.scope_type, v.scope_key, v.scope_label, v.accuracy_state,"
                    + " v.accuracy_rate, a.accuracy_threshold, v.blocked, v.block_reason,"
                    + " v.evaluated_at"
                    + " from inventory_health_verdict v"
                    + " join inventory_health_assessment a on a.tenant_id = v.tenant_id"
                    + " and a.id = v.assessment_id"
                    + " where v.scope_type = 'TENANT'"
                    + " order by v.evaluated_at desc, v.id desc limit 1")
            : sql.fetchOne(
                "select v.id, v.scope_type, v.scope_key, v.scope_label, v.accuracy_state,"
                    + " v.accuracy_rate, a.accuracy_threshold, v.blocked, v.block_reason,"
                    + " v.evaluated_at"
                    + " from inventory_health_verdict v"
                    + " join inventory_health_assessment a on a.tenant_id = v.tenant_id"
                    + " and a.id = v.assessment_id"
                    + " where v.scope_type = 'WAREHOUSE' and v.scope_key = ?"
                    + " order by v.evaluated_at desc, v.id desc limit 1",
                query.warehouseId());

    if (row == null) {
      // 还没有任何评估结论时不能回答「准不准」—— 但也不能因此回答「准」。
      // 结算场景下「没证据」必须按阻断处理，理由要说清楚是缺证据而不是账错了。
      var reason =
          "尚无库存健康评估结论，无法证明账目准确率；按最保守口径阻断结算与对账，"
              + "请先执行一次 7.1.2.2 账实核对。";
      return new InventoryHealthGate(
          scopeType,
          query.warehouseId(),
          label,
          MetricAvailability.NO_DATA,
          null,
          threshold,
          true,
          reason,
          "没有任何已落库的评估结论可比对。",
          reconciliationAt(window, query.warehouseId()),
          stocktakeAt(window, query.warehouseId()),
          now(),
          List.of(
              new InventoryHealthIssue(
                  HealthIssueSeverity.CRITICAL,
                  HealthMetricType.ACCURACY,
                  label,
                  "尚未评估，账目准确率无证据",
                  reason,
                  "执行一次 7.6.2.1 库存健康评估（或直接跑一次 7.1.2.2 账实核对）后再结算。",
                  "inventory_health_verdict 中无该范围的记录")));
    }

    var verdictId = row.get("id", UUID.class);
    return new InventoryHealthGate(
        HealthScopeType.valueOf(row.get("scope_type", String.class)),
        row.get("scope_key", UUID.class),
        row.get("scope_label", String.class),
        MetricAvailability.valueOf(row.get("accuracy_state", String.class)),
        row.get("accuracy_rate", BigDecimal.class),
        row.get("accuracy_threshold", BigDecimal.class),
        Boolean.TRUE.equals(row.get("blocked", Boolean.class)),
        nullSafe(row.get("block_reason", String.class)),
        HealthMetricType.ACCURACY.basis(),
        reconciliationAt(window, query.warehouseId()),
        stocktakeAt(window, query.warehouseId()),
        row.get("evaluated_at", OffsetDateTime.class),
        storedIssues(verdictId));
  }

  // ================================================================ 读：问题清单

  /** 问题清单（读最近一次评估产出的问题，按严重度排序）。 */
  public InventoryHealthIssuePage issues(InventoryHealthQuery query, int page, int size) {
    int limit = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int offset = Math.max(page, 0) * limit;

    var all = new ArrayList<InventoryHealthIssue>();
    var binds = new ArrayList<Object>();
    var where = new StringBuilder(" where evaluated_at = (select max(evaluated_at) from inventory_health_verdict)");
    if (query.warehouseId() != null) {
      where.append(" and scope_key = ?");
      binds.add(query.warehouseId());
    }
    for (var r :
        sql.fetch("select id, scope_label, summary from inventory_health_verdict" + where, binds.toArray())) {
      all.addAll(storedIssues(r.get("id", UUID.class)));
    }

    var sorted =
        all.stream()
            .sorted(
                Comparator.comparing(InventoryHealthIssue::severity)
                    .reversed()
                    .thenComparing(InventoryHealthIssue::scopeLabel)
                    .thenComparing(i -> i.metricType().name()))
            .toList();
    long critical =
        sorted.stream().filter(i -> i.severity() == HealthIssueSeverity.CRITICAL).count();
    int from = Math.min(offset, sorted.size());
    return new InventoryHealthIssuePage(
        sorted.subList(from, Math.min(from + limit, sorted.size())), sorted.size(), (int) critical, page, size);
  }

  /**
   * 读回某个结论对应的问题。
   *
   * <p>问题不在库里单独存表 —— 它是指标的确定性函数（{@link #issues(InventoryHealthScorecard)}），
   * 存一份就多一个可能与指标漂移的真相来源。这里用同样的口径从落库的指标行重算，
   * 保证「页面上看到的扣分」与「列表里的问题」永远说的是同一件事。
   */
  private List<InventoryHealthIssue> storedIssues(UUID verdictId) {
    var r = sql.fetchOne(
        "select v.scope_type, v.scope_key, v.scope_label, v.score, v.grade, a.accuracy_threshold,"
            + " v.blocked, v.block_reason, v.accuracy_state, v.accuracy_rate, v.evaluated_at,"
            + " a.window_from, a.window_to"
            + " from inventory_health_verdict v"
            + " join inventory_health_assessment a on a.tenant_id = v.tenant_id and a.id = v.assessment_id"
            + " where v.id = ?",
        verdictId);
    var metrics = metricsOf(verdictId);
    var accuracy = metricOf(metrics, HealthMetricType.ACCURACY);
    var negative = metricOf(metrics, HealthMetricType.NEGATIVE_STOCK);
    var available =
        (int) metrics.stream().filter(m -> m.availability() == MetricAvailability.AVAILABLE).count();
    var threshold = r.get("accuracy_threshold", BigDecimal.class);
    var blocked = Boolean.TRUE.equals(r.get("blocked", Boolean.class));
    var blockReason = nullSafe(r.get("block_reason", String.class));
    var state = MetricAvailability.valueOf(r.get("accuracy_state", String.class));
    var label = r.get("scope_label", String.class);

    var card =
        new InventoryHealthScorecard(
            HealthScopeType.valueOf(r.get("scope_type", String.class)),
            r.get("scope_key", UUID.class),
            label,
            r.get("score", BigDecimal.class),
            HealthGrade.valueOf(r.get("grade", String.class)),
            HealthGrade.valueOf(r.get("grade", String.class)).label(),
            available,
            metrics.size(),
            accuracy.value(),
            threshold,
            accuracy.availability() == MetricAvailability.AVAILABLE
                && accuracy.value().compareTo(threshold) >= 0,
            blocked,
            blockReason,
            r.get("window_from", OffsetDateTime.class),
            r.get("window_to", OffsetDateTime.class),
            r.get("evaluated_at", OffsetDateTime.class),
            metrics);

    var out = new ArrayList<>(issues(card));

    // 触发红线本身就是一条必须出现在问题清单里的问题：结算前置校验只看这张清单。
    if (blocked && out.stream().noneMatch(i -> i.severity() == HealthIssueSeverity.CRITICAL
        && "准确率红线已触发".equals(i.title()))) {
      out.add(
          0,
          new InventoryHealthIssue(
              HealthIssueSeverity.CRITICAL,
              HealthMetricType.ACCURACY,
              label,
              "准确率红线已触发",
              blockReason,
              "修掉账实差异或补齐核对证据后重跑 7.6.2.1 评估；解除前阻断结算与对账。",
              state == MetricAvailability.AVAILABLE
                  ? "准确率 " + plain(r.get("accuracy_rate", BigDecimal.class)) + " vs 阈值 " + plain(threshold)
                  : "准确率状态 " + state.label()));
    }
    return out;
  }

  // ================================================================ 指标计算

  /**
   * 一个范围的六项指标 + 由此打出的分数。
   *
   * <p>顺序固定为 {@link HealthMetricType} 的声明顺序，前端不用排序也不会跳。
   */
  private InventoryHealthScorecard card(
      Scope scope, Window window, BigDecimal threshold, OffsetDateTime evaluatedAt) {
    var raw = new ArrayList<InventoryHealthMetric>();
    for (var type : HealthMetricType.all()) {
      switch (type) {
        case ACCURACY -> raw.add(accuracyMetric(scope, window));
        case NEGATIVE_STOCK -> raw.add(negativeStockMetric(scope));
        // 四个依赖未交付上游的指标：给 UNAVAILABLE + 原因 + 阻塞模块，**不给值**。
        default -> raw.add(blockedMetric(type));
      }
    }

    var deduction = ZERO2;
    var metrics = new ArrayList<InventoryHealthMetric>();
    for (var m : raw) {
      var d = deductionOf(m);
      deduction = deduction.add(d);
      metrics.add(withDeduction(m, d));
    }
    var available =
        (int) metrics.stream().filter(m -> m.availability() == MetricAvailability.AVAILABLE).count();
    var score = HUNDRED.subtract(deduction).max(ZERO).setScale(2, RoundingMode.HALF_UP);
    var accuracy = metricOf(metrics, HealthMetricType.ACCURACY);
    var grade =
        grade(available, score, accuracy.availability() == MetricAvailability.AVAILABLE);
    var blockReason = blockReason(accuracy, threshold);

    return new InventoryHealthScorecard(
        scope.type(),
        scope.key(),
        scope.label(),
        score,
        grade,
        grade.label(),
        available,
        HealthMetricType.all().size(),
        accuracy.value(),
        threshold,
        accuracy.availability() == MetricAvailability.AVAILABLE
            && accuracy.value().compareTo(threshold) >= 0,
        blockReason != null,
        blockReason == null ? "" : blockReason,
        window.from(),
        window.to(),
        evaluatedAt,
        List.copyOf(metrics));
  }

  /**
   * 扣分：只有可算的指标才扣，且扣分额不超过它的权重。
   *
   * <p>准确率扣 {@code (1 − 准确率) × 70}：准确率 0.99 扣 0.7 分，0.90 扣 7 分，0 扣 70 分。
   * 负库存每发生一次扣满 30 分 —— 它一旦发生就是账已经错了，不是轻微瑕疵。
   *
   * <p><b>没有值的指标一律不扣分</b>：UNAVAILABLE / NO_DATA 的 {@code value} 是 null，
   * 对它做减法会 NPE；更重要的是扣了分就等于把「没测出来」说成「测出来不合格」，
   * 那是两回事。「没测出来」由 {@link #grade} 用 UNVERIFIABLE 表达，不由扣分表达。
   */
  private static BigDecimal deductionOf(InventoryHealthMetric metric) {
    if (metric.availability() != MetricAvailability.AVAILABLE || metric.value() == null) {
      return ZERO2;
    }
    var d =
        switch (metric.metricType()) {
          case ACCURACY ->
              ONE.subtract(metric.value()).max(ZERO).multiply(WEIGHT_ACCURACY).min(WEIGHT_ACCURACY);
          case NEGATIVE_STOCK ->
              metric.value().multiply(WEIGHT_NEGATIVE_STOCK).min(WEIGHT_NEGATIVE_STOCK);
          default -> ZERO;
        };
    return d.setScale(2, RoundingMode.HALF_UP);
  }

  private static InventoryHealthMetric withDeduction(InventoryHealthMetric m, BigDecimal deduction) {
    return new InventoryHealthMetric(
        m.metricType(),
        m.metricLabel(),
        m.availability(),
        m.value(),
        m.source(),
        m.numerator(),
        m.denominator(),
        deduction,
        m.basis(),
        m.blockedByModule());
  }

  /**
   * 评级。三个「不给好成绩」的理由，按严重程度排：
   *
   * <ol>
   *   <li>准确率没有证据（NO_DATA）→ UNVERIFIABLE。负库存恒为 0 会白送满分，
   *       若照常评级就会得到「没核过账但显示健康」这种最危险的结果。
   *   <li>一个可算指标都没有 → UNVERIFIABLE。
   *   <li>其余按分数：≥95 HEALTHY，≥80 WATCH，否则 RISK。
   * </ol>
   *
   * <p>「没测出来」和「很好」在结算与补货决策上是完全相反的两件事，不能混成同一个词。
   */
  private static HealthGrade grade(
      int availableMetricCount, BigDecimal score, boolean accuracyHasEvidence) {
    if (!accuracyHasEvidence || availableMetricCount == 0) return HealthGrade.UNVERIFIABLE;
    if (score.compareTo(new BigDecimal("95")) >= 0) return HealthGrade.HEALTHY;
    return score.compareTo(new BigDecimal("80")) >= 0 ? HealthGrade.WATCH : HealthGrade.RISK;
  }

  /**
   * 阻断理由。两个分支，没有第三个：
   *
   * <ul>
   *   <li>有值且低于阈值 → 阻断（蓝图的字面要求）。
   *   <li>有值且达到阈值 → 不阻断。
   *   <li>没有值（NO_DATA）→ 阻断。账不准时报表全是错的，「无法证明准」在结算场景下
   *       与「不准」必须同等对待，否则「从没核过账」会成为绕过红线的最短路径。
   * </ul>
   */
  private static String blockReason(InventoryHealthMetric accuracy, BigDecimal threshold) {
    if (accuracy.availability() == MetricAvailability.AVAILABLE) {
      if (accuracy.value().compareTo(threshold) >= 0) return null;
      return "库存准确率 "
          + plain(accuracy.value())
          + " 低于阈值 "
          + plain(threshold)
          + "，账目不可信，阻断结算与对账。证据："
          + accuracy.source()
          + " 口径 "
          + plain(accuracy.numerator())
          + "/"
          + plain(accuracy.denominator())
          + "。";
    }
    return "库存准确率无证据（"
        + accuracy.availability().label()
        + "）："
        + accuracy.basis()
        + " 账目未获证明即视为不可信，阻断结算与对账。";
  }

  // ------------------------------------------------------------ 准确率

  /**
   * 库存准确率。核对口径优先，盘点口径回落，两个都没有才是 NO_DATA。
   *
   * <p>核对口径取窗口内<b>最后一次</b>该范围的批次而不是平均：重复核对会让同一批余额行
   * 在分母里被数好几遍，平均出来的数没有口径（理由见类注释）。
   */
  private InventoryHealthMetric accuracyMetric(Scope scope, Window window) {
    var reconciliation = latestReconciliation(window, scope);
    if (reconciliation != null && reconciliation.checkedBalanceCount() > 0) {
      var checked = reconciliation.checkedBalanceCount();
      var accurate = BigDecimal.valueOf(checked - reconciliation.discrepancyCount());
      var denominator = BigDecimal.valueOf(checked);
      return accuracyAvailable(
          accurate.divide(denominator, 6, RoundingMode.HALF_UP),
          "RECONCILIATION",
          accurate,
          denominator,
          "取自 7.1.2.2 账实核对批次「"
              + reconciliation.scopeLabel()
              + "」（"
              + reconciliation.createdAt()
              + "）：核对 "
              + checked
              + " 条余额行，其中 "
              + reconciliation.discrepancyCount()
              + " 条对不上。");
    }
    // 核对了 0 行 = 没核到东西，不等于账是准的。退回盘点口径。

    var counted = countedLines(window, scope);
    if (counted.countedLines() > 0) {
      var accurate = BigDecimal.valueOf(counted.accurateLines());
      var denominator = BigDecimal.valueOf(counted.countedLines());
      return accuracyAvailable(
          accurate.divide(denominator, 6, RoundingMode.HALF_UP),
          "STOCKTAKE",
          accurate,
          denominator,
          "取自 7.4.2.1 盘点单（窗口内 "
              + counted.sheetCount()
              + " 张，共 "
              + counted.countedLines()
              + " 条已录实盘）：其中 "
              + counted.accurateLines()
              + " 条账面数与实盘数一致，绝对差异合计 "
              + plain(counted.absVariance())
              + "。");
    }

    return new InventoryHealthMetric(
        HealthMetricType.ACCURACY,
        HealthMetricType.ACCURACY.label(),
        MetricAvailability.NO_DATA,
        null,
        null,
        null,
        null,
        ZERO2,
        "评估窗口内既没有该范围的账实核对批次，也没有任何已录实盘数的盘点行 —— 没有样本可以证明账准。",
        null);
  }

  private static InventoryHealthMetric accuracyAvailable(
      BigDecimal rate, String source, BigDecimal numerator, BigDecimal denominator, String basis) {
    return new InventoryHealthMetric(
        HealthMetricType.ACCURACY,
        HealthMetricType.ACCURACY.label(),
        MetricAvailability.AVAILABLE,
        rate,
        source,
        numerator,
        denominator,
        ZERO2,
        basis,
        null);
  }

  /** 依赖未交付上游的指标：给 UNAVAILABLE + 原因 + 阻塞模块，**不给值**。 */
  private static InventoryHealthMetric blockedMetric(HealthMetricType type) {
    return new InventoryHealthMetric(
        type,
        type.label(),
        MetricAvailability.UNAVAILABLE,
        null,
        null,
        null,
        null,
        ZERO2,
        type.basis(),
        type.blockedByModule());
  }

  // ------------------------------------------------------------ 负库存

  /**
   * 负库存发生次数。
   *
   * <p>V1 的 {@code CHECK(on_hand>=0)/CHECK(reserved>=0)/CHECK(available>=0)} 与 V2 的同一组
   * CHECK 已经把负库存在结构上排除，所以这个查询<b>正常情况下恒为 0</b>。仍然把它写成一条真实的
   * COUNT 而不是直接返回常量 0：约束万一被改掉，这条查询会立刻变成非 0，而一个写死的 0
   * 会一直骗人。集成测试实跑「DB 拒绝写入负库存」来证明这个 0 不是编的。
   */
  private InventoryHealthMetric negativeStockMetric(Scope scope) {
    var rows =
        scope.key() == null
            ? sql.fetch(
                "select (select count(*) from inventory_balance where on_hand<0)"
                    + " + (select count(*) from inventory_balance where reserved<0)"
                    + " + (select count(*) from inventory_balance where available<0)"
                    + " + (select count(*) from stock_bucket where on_hand<0)"
                    + " + (select count(*) from stock_bucket where reserved<0)"
                    + " + (select count(*) from stock_bucket where available<0) as violations")
            : sql.fetch(
                "select (select count(*) from inventory_balance where warehouse_id = ? and on_hand<0)"
                    + " + (select count(*) from inventory_balance where warehouse_id = ? and reserved<0)"
                    + " + (select count(*) from inventory_balance where warehouse_id = ? and available<0)"
                    + " + (select count(*) from stock_bucket where warehouse_id = ? and on_hand<0)"
                    + " + (select count(*) from stock_bucket where warehouse_id = ? and reserved<0)"
                    + " + (select count(*) from stock_bucket where warehouse_id = ? and available<0) as violations",
                scope.key(), scope.key(), scope.key(), scope.key(), scope.key(), scope.key());
    var count = rows.get(0).get("violations", Long.class);
    var value = BigDecimal.valueOf(count);
    return new InventoryHealthMetric(
        HealthMetricType.NEGATIVE_STOCK,
        HealthMetricType.NEGATIVE_STOCK.label(),
        MetricAvailability.AVAILABLE,
        value,
        "INVENTORY_BALANCE",
        value,
        null,
        ZERO2,
        "对 inventory_balance 与 stock_bucket 的 on_hand / reserved / available 三个列逐列计数"
            + "（V1 与 V2 的 CHECK 已在结构上排除负数），本次命中 "
            + count
            + " 行。",
        null);
  }

  // ------------------------------------------------------------ 上游读取

  private record ReconciliationSample(
      UUID id,
      String scopeLabel,
      long checkedBalanceCount,
      long discrepancyCount,
      OffsetDateTime createdAt) {}

  /**
   * 窗口内该范围的最后一次核对批次。
   *
   * <p>全租户口径只认 {@code scope_type='ALL'} 的批次；仓库口径只认该仓自己的批次。
   * 刻意不把「SKU 范围」「法人范围」的批次混进来：它们的分母只覆盖一部分余额行，
   * 当成全范围准确率会把偏高的覆盖率说成账很准。
   */
  private ReconciliationSample latestReconciliation(Window window, Scope scope) {
    var where = new StringBuilder(" from inventory_reconciliation_run r cross join win")
        .append(" where r.created_at >= win.from_at and r.created_at < win.to_at");
    Object[] binds;
    if (scope.type() == HealthScopeType.TENANT) {
      where.append(" and r.scope_type = 'ALL'");
      binds = new Object[] {window.from(), window.to()};
    } else {
      where.append(" and r.scope_type = 'WAREHOUSE' and r.scope_key = ?");
      binds = new Object[] {window.from(), window.to(), scope.key()};
    }
    where.append(" order by r.created_at desc, r.id desc limit 1");

    var r = sql.fetchOne(
        WIN + "select r.id, r.scope_label, r.checked_balance_count, r.discrepancy_count, r.created_at"
            + where,
        binds);
    if (r == null) return null;
    return new ReconciliationSample(
        r.get("id", UUID.class),
        r.get("scope_label", String.class),
        r.get("checked_balance_count", Long.class),
        r.get("discrepancy_count", Long.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private record CountedSample(long countedLines, long accurateLines, long sheetCount, BigDecimal absVariance) {}

  /**
   * 窗口内该范围全部已录实盘的盘点行。
   *
   * <p>逐行取自不可变的 {@code stocktake_line}，跨单相加不会重复计数（每行只属于一张盘点单）。
   * 用 {@code line.warehouse_id} 过滤而不是单的 {@code scope_key}：盘点单可以按库位下派，
   * 行上的仓库列才是这行货实际在哪个仓的准确定位。
   */
  private CountedSample countedLines(Window window, Scope scope) {
    var where = new StringBuilder(" from stocktake_sheet s")
        .append(" join stocktake_line l on l.tenant_id = s.tenant_id and l.sheet_id = s.id")
        .append(" cross join win")
        .append(" where s.frozen_at >= win.from_at and s.frozen_at < win.to_at")
        .append(" and l.counted_qty is not null");
    Object[] binds;
    if (scope.key() == null) {
      binds = new Object[] {window.from(), window.to()};
    } else {
      where.append(" and l.warehouse_id = ?");
      binds = new Object[] {window.from(), window.to(), scope.key()};
    }

    var r = sql.fetchOne(
        WIN
            + "select count(*) as counted_lines,"
            + " count(*) filter (where l.variance = 0) as accurate_lines,"
            + " count(distinct s.id) as sheet_count,"
            + " coalesce(sum(abs(l.variance)), 0::numeric(19,4)) as abs_variance"
            + where,
        binds);
    return new CountedSample(
        r.get("counted_lines", Long.class),
        r.get("accurate_lines", Long.class),
        r.get("sheet_count", Long.class),
        r.get("abs_variance", BigDecimal.class));
  }

  private record WarehouseRow(UUID id, String code, String name) {}

  private List<WarehouseRow> warehouses(InventoryHealthQuery query) {
    var where = new StringBuilder(" where status = 'ACTIVE'");
    var binds = new ArrayList<Object>();
    if (query.warehouseId() != null) {
      where.append(" and id = ?");
      binds.add(query.warehouseId());
    }
    return sql.fetch(
            "select id, code, name from warehouse" + where + " order by code",
            binds.toArray())
        .map(r -> new WarehouseRow(r.get("id", UUID.class), r.get("code", String.class), r.get("name", String.class)));
  }

  private String warehouseLabel(UUID warehouseId) {
    var code = sql.fetchOne("select code from warehouse where id = ?", warehouseId);
    return code == null ? warehouseId.toString() : String.valueOf(code.get("code", String.class));
  }

  private OffsetDateTime reconciliationAt(Window window, UUID warehouseId) {
    var where = warehouseId == null
        ? " from inventory_reconciliation_run r cross join win"
            + " where r.created_at >= win.from_at and r.created_at < win.to_at"
            + " and r.scope_type = 'ALL'"
        : " from inventory_reconciliation_run r cross join win"
            + " where r.created_at >= win.from_at and r.created_at < win.to_at"
            + " and r.scope_type = 'WAREHOUSE' and r.scope_key = ?";
    Object[] binds = warehouseId == null
        ? new Object[] {window.from(), window.to()}
        : new Object[] {window.from(), window.to(), warehouseId};
    var r = sql.fetchOne(WIN + "select max(r.created_at) as last_at" + where, binds);
    return r == null ? null : r.get("last_at", OffsetDateTime.class);
  }

  private OffsetDateTime stocktakeAt(Window window, UUID warehouseId) {
    var where = warehouseId == null
        ? " from stocktake_sheet s cross join win"
            + " where s.frozen_at >= win.from_at and s.frozen_at < win.to_at"
        : " from stocktake_sheet s join stocktake_line l on l.tenant_id = s.tenant_id"
            + " and l.sheet_id = s.id"
            + " cross join win"
            + " where s.frozen_at >= win.from_at and s.frozen_at < win.to_at"
            + " and l.warehouse_id = ?";
    Object[] binds = warehouseId == null
        ? new Object[] {window.from(), window.to()}
        : new Object[] {window.from(), window.to(), warehouseId};
    var r = sql.fetchOne(WIN + "select max(s.frozen_at) as last_at" + where, binds);
    return r == null ? null : r.get("last_at", OffsetDateTime.class);
  }

  // ================================================================ 问题清单

  /**
   * 从指标推出问题清单。
   *
   * <p>每条问题都必须指向一个可执行动作或一条可回查的证据行，不能是「注意库存管理」这种评价。
   * 扣分与问题是同一套事实的两种呈现：分数给总览，问题给动作。
   */
  private List<InventoryHealthIssue> issues(InventoryHealthScorecard card) {
    var out = new ArrayList<InventoryHealthIssue>();
    var accuracy = metricOf(card.metrics(), HealthMetricType.ACCURACY);
    var negative = metricOf(card.metrics(), HealthMetricType.NEGATIVE_STOCK);

    if (accuracy.availability() == MetricAvailability.AVAILABLE) {
      if (accuracy.value().compareTo(card.accuracyThreshold()) < 0) {
        out.add(new InventoryHealthIssue(
            HealthIssueSeverity.CRITICAL,
            HealthMetricType.ACCURACY,
            card.scopeLabel(),
            "库存准确率低于阈值",
            "准确率 "
                + plain(accuracy.value())
                + "，阈值 "
                + plain(card.accuracyThreshold())
                + "（"
                + accuracy.source()
                + " 口径 "
                + plain(accuracy.numerator())
                + "/"
                + plain(accuracy.denominator())
                + "）。蓝图红线：账不准时报表全是错的，应阻断结算与对账。",
            "先跑 7.1.2.2 账实核对定位差异行（附可疑流水定位），修掉差异后重跑核对；"
                + "在准确率回到阈值以上之前不要结算。",
            "核对批次口径；差异行见 7.1.2.2 的 reconciliation findings"));
      } else if (accuracy.numerator().compareTo(accuracy.denominator()) < 0) {
        out.add(new InventoryHealthIssue(
            HealthIssueSeverity.WARN,
            HealthMetricType.ACCURACY,
            card.scopeLabel(),
            "存在账实差异",
            "准确率 "
                + plain(accuracy.value())
                + " 已高于阈值，但仍有 "
                + plain(accuracy.denominator().subtract(accuracy.numerator()))
                + " 行对不上。",
            "查看 7.1.2.2 的差异明细并逐条处理 —— 未处理的差异会持续污染报表与结算。",
            "见 7.1.2.2 差异明细"));
      }
    } else {
      out.add(new InventoryHealthIssue(
          HealthIssueSeverity.CRITICAL,
          HealthMetricType.ACCURACY,
          card.scopeLabel(),
          "账目准确率无证据",
          accuracy.basis(),
          "执行一次 7.1.2.2 账实核对；在有核对结论之前，结算与对账都应视为不可信。",
          "窗口内无核对批次、无已录实盘行"));
    }

    if (negative.availability() == MetricAvailability.AVAILABLE
        && negative.value().compareTo(ZERO) > 0) {
      out.add(new InventoryHealthIssue(
          HealthIssueSeverity.CRITICAL,
          HealthMetricType.NEGATIVE_STOCK,
          card.scopeLabel(),
          "出现负库存",
          "命中 "
              + plain(negative.value())
              + " 行 on_hand / reserved / available 为负。这三列都有 CHECK 约束，"
              + "出现负数说明约束被绕过或迁移被改动。",
          "立即排查最近一次迁移变更；在约束恢复前冻结该范围的出库操作。",
          "inventory_balance / stock_bucket 负值行"));
    }

    // 覆盖度问题：可算指标少于总数时必须说出来，否则部分维度的分数会被当成整体健康度。
    if (card.availableMetricCount() < card.totalMetricCount()) {
      var blocked =
          card.metrics().stream()
              .filter(m -> m.availability() == MetricAvailability.UNAVAILABLE)
              .map(m -> m.metricLabel() + "（待 " + m.blockedByModule() + "）")
              .toList();
      out.add(new InventoryHealthIssue(
          HealthIssueSeverity.INFO,
          HealthMetricType.ACCURACY,
          card.scopeLabel(),
          "评分只覆盖部分指标",
          card.totalMetricCount()
              + " 个指标里只有 "
              + card.availableMetricCount()
              + " 个可算，得分 "
              + plain(card.score())
              + " 只代表已覆盖维度。缺："
              + String.join("、", blocked),
          "等上游模块（13 销量、7.6.1 库龄与呆滞）交付后重跑本评估。",
          "六项指标的 availability 见同屏指标明细"));
    }

    return out;
  }

  // ================================================================ 落库

  /**
 * 落一次评估：1 行头 + 6 行指标。
 *
 * <p>头与指标必须<b>同一个事务</b>（{@link #assess} 上有 @Transactional）：
 * 只写头不写指标会得到一个「有结论没依据」的评分卡，只写指标不写头则是孤儿行。
 */
private void persistAssessment(
      Scope scope,
      Window window,
      BigDecimal threshold,
      InventoryHealthScorecard card,
      OffsetDateTime evaluatedAt) {
    var tenantId = currentTenant();
    var assessmentId = UUID.randomUUID();
    // created_at 的 cast 放在绑定序列第 1 位、window 的两个 cast 在第 4/5 位：
    // cast 永远不落在末位（末位会让 jOOQ 整体错位一格）。
    sql.execute(
        "insert into inventory_health_assessment"
            + " (created_at, id, tenant_id, window_from, window_to, scope_type, scope_key, scope_label,"
            + "  score, available_metric_count, total_metric_count, grade, accuracy_threshold)"
            + " values (?::timestamptz,?,?,?::timestamptz,?::timestamptz,?,?,?,?,?,?,?,?)",
        evaluatedAt,
        assessmentId,
        tenantId,
        window.from(),
        window.to(),
        scope.type().name(),
        scope.key(),
        scope.label(),
        card.score(),
        card.availableMetricCount(),
        card.totalMetricCount(),
        card.grade().name(),
        threshold);

    for (var metric : card.metrics()) {
      sql.execute(
          "insert into inventory_health_metric"
              + " (id, tenant_id, assessment_id, metric_type, metric_state, value, metric_source,"
              + "  numerator, denominator, reason, blocked_by_module, deduction)"
              + " values (?,?,?,?,?,?,?,?,?,?,?,?)",
          UUID.randomUUID(),
          tenantId,
          assessmentId,
          metric.metricType().name(),
          metric.availability().name(),
          metric.value(),
          metric.source(),
          metric.numerator(),
          metric.denominator(),
          metric.availability() == MetricAvailability.AVAILABLE ? "" : metric.basis(),
          metric.blockedByModule(),
          metric.deduction());
    }
  }

  private void persistVerdict(
      Scope scope,
      BigDecimal threshold,
      InventoryHealthScorecard card,
      InventoryHealthGate gate,
      List<InventoryHealthIssue> issues,
      UUID actorId,
      OffsetDateTime evaluatedAt) {
    var tenantId = currentTenant();
    // 结论行必须指向同一范围内、本次评估刚写下的那份头 —— 按 created_at 定位，
    // cast 放在绑定序列第 1 位不在末位。
    var assessmentId =
        sql.fetchOne(
                "select id from inventory_health_assessment"
                    + " where created_at = ?::timestamptz and scope_type = ?"
                    + " and scope_key is not distinct from ?"
                    + " order by id limit 1",
                evaluatedAt, scope.type().name(), scope.key())
            .get("id", UUID.class);

    var critical = (int) issues.stream().filter(i -> i.severity() == HealthIssueSeverity.CRITICAL).count();
    sql.execute(
        "insert into inventory_health_verdict"
            + " (id, tenant_id, assessment_id, scope_type, scope_key, scope_label, score, grade,"
            + "  accuracy_rate, accuracy_state, blocked, block_reason,"
            + "  issue_count, critical_issue_count, summary, evaluated_at, evaluated_by)"
            // evaluated_at 的 cast 在第 16 位、evaluated_by（第 17 位，末位）是普通 uuid 绑定 ——
            // cast 不落在绑定序列末位。
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?)",
        UUID.randomUUID(),
        tenantId,
        assessmentId,
        scope.type().name(),
        scope.key(),
        scope.label(),
        card.score(),
        card.grade().name(),
        card.accuracyRate(),
        gate.accuracyState().name(),
        gate.blocked(),
        gate.blockReason(),
        issues.size(),
        critical,
        summary(card, gate),
        evaluatedAt,
        actorId);
  }

  /** 人话版摘要，供工作台直接显示。 */
  private static String summary(InventoryHealthScorecard card, InventoryHealthGate gate) {
    var blocked =
        (int) card.metrics().stream()
            .filter(m -> m.availability() == MetricAvailability.UNAVAILABLE)
            .count();
    return "得分 "
        + plain(card.score())
        + "/100（"
        + card.grade().label()
        + "），覆盖 "
        + card.availableMetricCount()
        + "/"
        + card.totalMetricCount()
        + " 个指标（"
        + blocked
        + " 个待上游交付）；"
        + (gate.blocked() ? "准确率红线已触发，阻断结算与对账。" : "准确率达标，未触发阻断。");
  }

  /**
   * 写入的 tenant_id 与 RLS 注入的租户<b>必须是同一个</b>，否则 WITH CHECK 会直接拒写 ——
   * 这比在应用层自查可靠（与 7.1.2.2 同一纪律）。
   */
  private UUID currentTenant() {
    var row = sql.fetchOne("select current_setting('app.current_tenant', true)::uuid as t");
    var tenant = row == null ? null : row.get("t", UUID.class);
    if (tenant == null) {
      throw new IllegalStateException("缺少租户上下文：请在 AccessService.run 绑定的事务内调用");
    }
    return tenant;
  }

  // ================================================================ 小工具

  private record Scope(HealthScopeType type, UUID key, String label) {}

  private record Window(OffsetDateTime from, OffsetDateTime to) {}

  private InventoryHealthGate gateFor(
      Scope scope, Window window, BigDecimal threshold, InventoryHealthScorecard card, OffsetDateTime evaluatedAt) {
    var accuracy = metricOf(card.metrics(), HealthMetricType.ACCURACY);
    return new InventoryHealthGate(
        scope.type(),
        scope.key(),
        scope.label(),
        accuracy.availability(),
        accuracy.value(),
        threshold,
        card.blocked(),
        card.blockReason(),
        accuracy.basis(),
        latestReconciliation(window, scope) == null
            ? null
            : latestReconciliation(window, scope).createdAt(),
        latestStocktakeAt(window, scope),
        evaluatedAt,
        issues(card));
  }

  private OffsetDateTime latestStocktakeAt(Window window, Scope scope) {
    var where = new StringBuilder(" from stocktake_sheet s")
        .append(" join stocktake_line l on l.tenant_id = s.tenant_id and l.sheet_id = s.id")
        .append(" cross join win")
        .append(" where s.frozen_at >= win.from_at and s.frozen_at < win.to_at")
        .append(" and l.counted_qty is not null");
    Object[] binds;
    if (scope.key() == null) {
      binds = new Object[] {window.from(), window.to()};
    } else {
      where.append(" and l.warehouse_id = ?");
      binds = new Object[] {window.from(), window.to(), scope.key()};
    }
    var r = sql.fetchOne(WIN + "select max(s.frozen_at) as last_at" + where, binds);
    return r == null ? null : r.get("last_at", OffsetDateTime.class);
  }

  /** 核对区间：左闭右开，缺省近 30 天，与 7.1.2.2 同一套默认值。 */
  private static Window window(InventoryHealthQuery query) {
    var toAt = (query.to() == null ? now() : query.to()).truncatedTo(ChronoUnit.SECONDS);
    var fromAt =
        (query.from() == null ? toAt.minusDays(DEFAULT_WINDOW_DAYS) : query.from())
            .truncatedTo(ChronoUnit.SECONDS);
    if (!fromAt.isBefore(toAt)) {
      throw new IllegalArgumentException(
          "评估区间需为左闭右开且起点早于终点，当前为 " + fromAt + " → " + toAt);
    }
    return new Window(fromAt, toAt);
  }

  /** 阈值缺省 99%，也只允许落在 0..1 —— 传 1.5 是调用方的 bug，不是「更严格」。 */
  private static BigDecimal threshold(InventoryHealthQuery query) {
    if (query.accuracyThreshold() == null) return DEFAULT_ACCURACY_THRESHOLD;
    var t = query.accuracyThreshold();
    if (t.compareTo(ZERO) < 0 || t.compareTo(ONE) > 0) {
      throw new IllegalArgumentException("库存准确率阈值需落在 0..1，当前为 " + plain(t));
    }
    return t;
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
  }

  private static String nullSafe(String value) {
    return value == null ? "" : value;
  }

  /** 去掉尾随零，避免响应里出现 0.990000 这种没人爱看的写法。 */
  private static String plain(BigDecimal value) {
    return value == null ? "—" : value.stripTrailingZeros().toPlainString();
  }

  private static Object[] concat(List<Object> head, Object... tail) {
    var all = new ArrayList<Object>(head);
    all.addAll(List.of(tail));
    return all.toArray();
  }
}