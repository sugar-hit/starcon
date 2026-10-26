package com.starcon.reporting;

import com.starcon.tenancy.Ids;
import com.starcon.tenancy.TenantTransactions;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 13.1.2.2 达成追踪的**读模型**（蓝图「输入」句：13.1.2.1 目标；实际值投影；时间进度）。
 *
 * <p>蓝图原文（docs/product/capability-blueprint/13-profit-and-analytics.md:100）：
 *
 * <blockquote>
 *
 *   - **职责**：追踪目标达成：当前值、达成率、预测达成（按当前速度推算期末值）、未达标项高亮、
 *     人员/产品维度排名。
 *   - **输入**：13.1.2.1 目标；实际值投影；时间进度。
 *   - **输出**：达成率表 + 预测 + 预警。
 *   - **关系**：上游 = 13.1.2.1；下游 = 17 绩效核算、1 工作台预警。
 *     **预测达成值比当前值更有管理价值**——月初就知道会不会达标，才有时间干预。
 *
 * </blockquote>
 *
 * <p>🔴 <b>本类不重算口径</b>：口径（单位 / 聚合方式 / 分子分母）来自口径表
 * （{@code reporting_metric_definition} / {@code reporting_store_metric_definition}），
 * 本类只按目标里**引用的那个口径码**去事实表里取数。
 *
 * <p>🔴 <b>「算不出来」与「等于 0」必须分开</b>：凡是可以「算不出来」的数值一律 {@code null}，
 * <b>不用 0 冒充</b>。0% 达成率是有业务含义的断言（确实一件都没做成），
 * 「不可得」不是（我根本拿不到这个维度的实际值）。
 *
 * <p>🔴 <b>时间进度按数据库时钟算</b>：评估日取自 PG 的 {@code now()}（按本租户时区折算成日历日），
 * <b>不是</b> JVM 墙钟 —— 两个钟域混用会让「今天」在跨时区时偏移一天，
 * 而一天在预测达成率上就是几个百分点。
 *
 * <p>🔴 <b>多币种绝不相加</b>：实际值**按目标币种取数**，排名**不跨币种**。
 */
@Service
public class ReportingTargetAttainmentService {

  /** 金额刻度（与服务端 numeric(24,4) 对齐）。 */
  static final int VALUE_SCALE = 4;

  /** 比率刻度（达成率 / 预测达成率 / 时间进度：numeric(19,6)）。 */
  static final int RATE_SCALE = 6;

  /** 「按当前速度能达标」的阈值（预测达成率 ≥ 100%）。 */
  static final BigDecimal ON_TRACK_THRESHOLD = new BigDecimal("1");

  /** 「差一点，大概率还能救」的阈值（预测达成率 ≥ 80%）。 */
  static final BigDecimal AT_RISK_THRESHOLD = new BigDecimal("0.8");

  /** 命中达标的阈值（达成率 ≥ 100%）。 */
  static final BigDecimal ACHIEVED_THRESHOLD = new BigDecimal("1");

  /**
   * 产品维度的口径映射：**租户口径码 → 商品维度口径码**。
   *
   * <p>🔴 **为什么需要这张映射**：13.1.2.1 的目标**只引用租户口径表**里的码
   * （{@code reporting_metric_definition}，V387 的触发器钉死），而商品维度的事实走
   * **另一套口径表**（{@code reporting_product_metric_definition}，V389）。
   * 两套码表各有各的口径真相（这正是「口径唯一」的本意），但「同一件事」在两边各有一个码。
   *
   * <p>⇒ 只映射**口径完全等价**的三条：
   * <ul>
   *   <li>{@code SALES_AMOUNT} → {@code PRODUCT_SALES_AMOUNT}（都是订单明细的成交金额合计）；</li>
   *   <li>{@code ORDER_COUNT} → {@code PRODUCT_ORDER_COUNT}（都是订单笔数，按订单去重）；</li>
   *   <li>{@code UNIT_SOLD} → {@code PRODUCT_SALES_QUANTITY}（都是订单行件数之和）。</li>
   * </ul>
   *
   * <p>🔴 **其它口径一律不映射**：毛利额 / 毛利率在商品维度**没有等价口径**
   * （成本分录没有商品维度）⇒ 如实报「不可得」，**不拿销售额冒充毛利额**。
   */
  static final Map<String, String> PRODUCT_CALIBER =
      Map.of(
          ReportingMetricCatalog.SALES_AMOUNT,
          ReportingProductSalesCatalog.PRODUCT_SALES_AMOUNT,
          ReportingMetricCatalog.ORDER_COUNT,
          ReportingProductSalesCatalog.PRODUCT_ORDER_COUNT,
          ReportingMetricCatalog.UNIT_SOLD,
          ReportingProductSalesCatalog.PRODUCT_SALES_QUANTITY);

  private final DSLContext sql;
  private final TenantTransactions tenants;

  public ReportingTargetAttainmentService(DSLContext sql, TenantTransactions tenants) {
    this.sql = sql;
    this.tenants = tenants;
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 能力声明（**哪些拿得到、哪些确实拿不到、判定规则是什么**）。
   *
   * <p>🔴 {@code notDelivered} 里列的是**确实拿不到**的部分，每条都写清原因与归属模块 ——
   * 把「拿不到」伪装成 0 比说清楚更糟。
   */
  public ReportingTargetAttainmentModels.AttainmentCapabilities capabilities(UUID tenantId) {
    return new ReportingTargetAttainmentModels.AttainmentCapabilities(
        true,
        true,
        UPSTREAM_MODULES,
        NOT_DELIVERED,
        RULES);
  }

  /** 本服务**真的读过**的上游模块。 */
  private static final List<String> UPSTREAM_MODULES =
      List.of(
          "13.1.2.1 目标设定（本服务 reporting_target + reporting_target_bucket —— 目标与分解计划）",
          "13.1.1.2 多店对比（本服务 reporting_store_metric_fact —— **店铺维度**的实际值）",
          "13.2.2.2 SKU/SPU/Listing（本服务 reporting_product_metric_fact —— **产品维度**的实际值）");

  /** **确实拿不到**的部分（每条都写清原因，不写「未接入」这种空话）。 */
  private static final List<String> NOT_DELIVERED =
      List.of(
          "🔴 **部门 / 人员维度的实际值不可得**：上游只把事实投影到公司 / 法人 / 店铺维度"
              + "（reporting_metric_fact 的 scope_type 只有 COMPANY / LEGAL_ENTITY / SHOP），"
              + "**部门 / 人员的实际值还没有投影到 reporting**。⇒ 本模块对这两个维度如实报"
              + "「不可得」（actualStatus = NOT_AVAILABLE、达成率为 null），"
              + "**不拿租户级的数字冒充某个部门的实际值** —— 那是把公司的成绩算到部门头上。"
              + "等上游补齐这两个维度的事实投影后，本模块**不需要改表、不需要改接口**就会自动算出来。",
          "🔴 **产品维度的实际值取决于 13.2.2.2 的商品维度投影是否跑过**："
              + "本模块读 reporting_product_metric_fact（V389 已建表，13.2.2.2 的拉取适配器负责落数）。"
              + "该租户还没有投影过商品事实时 ⇒ actualStatus = NO_DATA、当前值为 null，"
              + "**不是 0% 达成率**。",
          "🔴 **毛利额 / 毛利率在产品维度不可得**：成本分录（12.4.1.2）与利润表（12.5.2.2）"
              + "都**没有商品维度**，商品口径表里也没有这两个码 ⇒ 如实报「不可得」，"
              + "**不拿成交金额冒充毛利额**。",
          "🔴 **广告 ROI 与库存周转在任何维度都不可得**：口径表里没有登记这两个码"
              + "（13.1.2.1 已如实报「未登记」）⇒ 没有码就没有实际值，"
              + "本模块**不自己造公式**（那会出现第二个真相）。",
          "**实际值按目标币种取数**：事实按币种分行存放，本模块只取**与目标币种相同**的那些行，"
              + "**绝不跨币种相加**。对数量 / 单数型口径来说，这是保守做法："
              + "别的币种的订单行不计入本目标的当前值。",
          "**排名不跨币种、不跨口径**：销售额达成率与毛利率达成率不是一回事，"
              + "USD 目标与 EUR 目标的大小本来就不可比 ⇒ 排名只在"
              + "「同归属维度 + 同口径 + 同币种 + 同周期」内进行。");

  /** 判定规则与阈值（页面上照着显示，用户看得懂「为什么这条是 BEHIND」）。 */
  private static final List<ReportingTargetAttainmentModels.AttainmentRuleNote> RULES =
      List.of(
          new ReportingTargetAttainmentModels.AttainmentRuleNote(
              "RATE",
              "达成率 = 当前值 ÷ 目标值。**目标值不是正数 ⇒ 达成率没有定义**（分母为 0），"
                  + "此时状态是 NOT_COMPARABLE、达成率为 null（**不是 0%**）。"),
          new ReportingTargetAttainmentModels.AttainmentRuleNote(
              "ELAPSED",
              "时间进度 = 已过日历日 ÷ 周期总日历日（**按本租户时区**的日历日，"
                  + "评估日取自数据库时钟）。周期已走完时为 1。"),
          new ReportingTargetAttainmentModels.AttainmentRuleNote(
              "FORECAST",
              "预测期末值 = 当前值 ÷ 时间进度（**按当前速度直线推算**）。"
                  + "时间进度为 0（周期还没开始）⇒ 预测值算不出来（null，**不是 0**）。"
                  + "预测达成率 = 预测期末值 ÷ 目标值。"),
          new ReportingTargetAttainmentModels.AttainmentRuleNote(
              "STATUS",
              "ACHIEVED 达成率 ≥ 100%；ON_TRACK 预测达成率 ≥ 100%（按当前速度能达标）；"
                  + "AT_RISK 预测达成率 80% ~ 100%（差一点）；BEHIND 预测达成率 < 80%（明显跟不上）；"
                  + "NOT_STARTED 周期尚未开始；NOT_COMPARABLE 目标值不是正数；"
                  + "UNAVAILABLE 实际值不可得。"),
          new ReportingTargetAttainmentModels.AttainmentRuleNote(
              "UNDER_ACHIEVED",
              "**未达标高亮只认 AT_RISK 与 BEHIND** —— 「算不出来」的那三类"
                  + "（NOT_STARTED / NOT_COMPARABLE / UNAVAILABLE）**不是「没达标」，是「还没法判定」**。"
                  + "把它们混进未达标名单等于告诉管理者「你落后了」，而真相是「我没数据」。"),
          new ReportingTargetAttainmentModels.AttainmentRuleNote(
              "ACTUAL_STATUS",
              "AVAILABLE 取到了（当前值**可能是 0**：周期内某些天没有事实，合计为 0 是真的 0）；"
                  + "NO_DATA 来源可用但**这个归属对象在这套口径上从来没有事实行** ⇒ 当前值为 null"
                  + "（**不是 0**）；NOT_AVAILABLE 该维度 / 口径**根本没有事实来源** ⇒ 当前值为 null。"),
          new ReportingTargetAttainmentModels.AttainmentRuleNote(
              "CALIBER",
              "本模块**只引用口径码，不重算口径**：口径真相在口径表里"
                  + "（reporting_metric_definition / reporting_store_metric_definition）。"
                  + "产品维度额外做一次**口径等价映射**（成交金额 / 订单数 / 件数三条），"
                  + "没有等价口径的一律报「不可得」。"));

  // ==================================================================
  // 达成追踪看板
  // ==================================================================

  /**
   * 达成追踪看板（达成率表 + 预测 + 未达标高亮 + 排名 + 预警）。
   *
   * @param scopeType 归属维度筛选（可空 ⇒ 全部）
   * @param metricCode 口径码筛选（可空 ⇒ 全部）
   * @param currency 币种筛选（可空 ⇒ 全部）
   * @param status 目标状态筛选（ACTIVE / ARCHIVED；可空 ⇒ 全部）
   */
  public ReportingTargetAttainmentModels.AttainmentBoard board(
      UUID tenantId,
      String scopeType,
      String metricCode,
      String currency,
      String status,
      int page,
      int size) {
    return tenants.run(tenantId, () -> boardInTransaction(tenantId, scopeType, metricCode, currency, status, page, size));
  }

  private ReportingTargetAttainmentModels.AttainmentBoard boardInTransaction(
      UUID tenantId,
      String scopeType,
      String metricCode,
      String currency,
      String status,
      int page,
      int size) {
    var zone = tenantZone(tenantId);
    var clock = dbClock(zone.zone());
    var asOf = clock.asOfDate();
    var evaluatedAt = clock.evaluatedAt();

    var targets = loadTargets(tenantId, scopeType, metricCode, currency, status);
    var rows = evaluateAll(tenantId, targets, asOf);

    var ranked = rank(rows);
    var alerts = alerts(ranked);

    var effectiveSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    var effectivePage = Math.max(page, 0);
    var from = (long) effectivePage * effectiveSize;
    var to = from + effectiveSize;
    var slice = ranked.subList((int) Math.min(from, ranked.size()), (int) Math.min(to, ranked.size()));

    return new ReportingTargetAttainmentModels.AttainmentBoard(
        asOf,
        zone.zone(),
        zone.source(),
        zone.note(),
        evaluatedAt,
        effectivePage,
        effectiveSize,
        ranked.size(),
        groups(slice),
        alerts,
        summary(ranked),
        "排名位次在**全量**符合条件的目标上计算（不随分页变化），"
            + "且只对**算得出达成率**的目标排名（算不出来的不进排名）。",
        "本模块只引用口径码、不重算口径；实际值按目标币种取数，**绝不跨币种相加**。");
  }

  /** 每页最大条数。 */
  static final int MAX_PAGE_SIZE = 200;

  // ==================================================================
  // 排名
  // ==================================================================

  /**
   * 排名（**人员 / 产品维度排名**）。
   *
   * <p>🔴 **只对算得出达成率的目标排名**：算不出来的行不占名次 ——
   * 给「不可得」一个名次等于替它编了一个成绩。
   */
  public ReportingTargetAttainmentModels.AttainmentRanking ranking(
      UUID tenantId,
      String scopeType,
      String metricCode,
      String currency,
      String status,
      int page,
      int size) {
    return tenants.run(
        tenantId,
        () -> {
          var zone = tenantZone(tenantId);
          var asOf = dbClock(zone.zone()).asOfDate();
          var targets = loadTargets(tenantId, scopeType, metricCode, currency, status);
          var ranked = rank(evaluateAll(tenantId, targets, asOf));
          var entries = new ArrayList<ReportingTargetAttainmentModels.AttainmentRankEntry>();
          for (var row : ranked) {
            if (row.attainmentRate() == null) continue;
            entries.add(
                new ReportingTargetAttainmentModels.AttainmentRankEntry(
                    row.rank(),
                    row.rankTotal(),
                    row.rankGroup(),
                    row.scopeType(),
                    row.ownerId(),
                    row.ownerCode(),
                    row.ownerName(),
                    row.metricCode(),
                    row.currency(),
                    row.targetValue(),
                    row.currentValue(),
                    row.attainmentRate(),
                    row.forecastRate(),
                    row.status()));
          }
          var effectiveSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
          var effectivePage = Math.max(page, 0);
          var from = (long) effectivePage * effectiveSize;
          var to = Math.min(from + effectiveSize, entries.size());
          var slice = entries.subList((int) Math.min(from, entries.size()), (int) to);
          return new ReportingTargetAttainmentModels.AttainmentRanking(
              effectivePage,
              effectiveSize,
              entries.size(),
              slice,
              "排名只在「同归属维度 + 同口径 + 同币种 + 同周期」内进行（**不跨币种、不跨指标**）；"
                  + "算不出达成率的目标不进排名。");
        });
  }

  // ==================================================================
  // 评估（写派生读模型）
  // ==================================================================

  /**
   * 评估并**落快照**（一次评估 = 一行快照；重复评估是覆盖，不是堆积）。
   *
   * <p>🔴 **本模块写的是派生读模型，不是业务事实**：它记的是「在某个评估日、"
   * "按当时已投影的事实，这个目标的达成率与预测达成是多少」。
   * 下游（17 绩效核算、1 工作台预警）读它 —— 「月初就知道会不会达标」这句话
   * 要求的是**一个可回看的时间点**，不是一个随时会变的实时值。
   */
  public ReportingTargetAttainmentModels.AttainmentEvaluateResult evaluate(
      UUID tenantId,
      String scopeType,
      String metricCode,
      String currency,
      String status) {
    return tenants.run(
        tenantId,
        () -> {
          var zone = tenantZone(tenantId);
          var clock = dbClock(zone.zone());
          var asOf = clock.asOfDate();
          var evaluatedAt = clock.evaluatedAt();
          var targets = loadTargets(tenantId, scopeType, metricCode, currency, status);
          var rows = rank(evaluateAll(tenantId, targets, asOf));
          for (var row : rows) {
            upsertSnapshot(tenantId, row, asOf, evaluatedAt);
          }
          var ranked = rows;
          var alerts = alerts(ranked);
          var board =
              new ReportingTargetAttainmentModels.AttainmentBoard(
                  asOf,
                  zone.zone(),
                  zone.source(),
                  zone.note(),
                  evaluatedAt,
                  0,
                  MAX_PAGE_SIZE,
                  ranked.size(),
                  groups(ranked),
                  alerts,
                  summary(ranked),
                  "排名位次在**全量**符合条件的目标上计算（不随分页变化），"
                      + "且只对**算得出达成率**的目标排名（算不出来的不进排名）。",
                  "本模块只引用口径码、不重算口径；实际值按目标币种取数，**绝不跨币种相加**。");
          return new ReportingTargetAttainmentModels.AttainmentEvaluateResult(
              ranked.size(), asOf, evaluatedAt, board);
        });
  }

  /**
   * 预测侧说明（落进快照的 {@code forecast_note}）。
   *
   * <p>🔴 **预测算不出来时写「算不出来」，不写 0** —— 「预测期末值 = 0」与「推不出来」
   * 在复盘时是完全不同的两件事。
   */
  private static String forecastNote(ReportingTargetAttainmentModels.AttainmentRow row) {
    if (row.forecastRate() == null) {
      return "预测算不出来（时间进度为 0，或实际值不可得）—— **不是预测期末值为 0**。";
    }
    return "按当前速度（时间进度 "
        + row.elapsedRatio()
        + "）推算，期末可达目标值的 "
        + row.forecastRate()
        + "。";
  }

  /**
   * 落一条快照（同一目标同一天覆盖）。
   *
   * <p>⚠️ **绑定值先取成局部变量再进 {@code sql.execute(...)} 的可变参数**：
   * 这样 SQL 的列顺序与绑定值顺序能逐行对读（27 个 {@code ?} 对 26 个绑定值 + 两个
   * {@code now()}），改列时不会悄悄错位。
   */
  private void upsertSnapshot(
      UUID tenantId,
      ReportingTargetAttainmentModels.AttainmentRow row,
      LocalDate asOf,
      OffsetDateTime evaluatedAt) {
    var targetId = row.targetId();
    var metricCode = row.metricCode();
    var scopeType = row.scopeType();
    var ownerId = row.ownerId();
    var ownerCode = row.ownerCode();
    var ownerName = row.ownerName();
    var currency = row.currency();
    var periodStart = row.periodStart();
    var periodEnd = row.periodEnd();
    var targetValue = row.targetValue();
    var currentValue = row.currentValue();
    var attainmentRate = row.attainmentRate();
    var forecastValue = row.forecastValue();
    var forecastRate = row.forecastRate();
    var elapsedRatio = row.elapsedRatio();
    var actualStatus = row.actualStatus();
    var status = row.status();
    var actualNote = row.statusNote();
    var forecastNote = forecastNote(row);
    var rankPosition = row.rank();
    var rankTotal = row.rankTotal();
    var rankGroup = row.rankGroup();
    sql.execute(
        """
        insert into reporting_target_attainment
          (id,tenant_id,target_id,evaluated_on,metric_code,scope_type,owner_id,owner_code,owner_name,
           currency,period_start,period_end,target_value,current_value,attainment_rate,
           forecast_value,forecast_attainment_rate,elapsed_ratio,actual_status,status,
           actual_note,forecast_note,rank_position,rank_total,rank_group,evaluated_at,created_at,updated_at)
        values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,now(),now())
        on conflict (tenant_id, target_id, evaluated_on) do update set
          metric_code = excluded.metric_code,
          scope_type = excluded.scope_type,
          owner_id = excluded.owner_id,
          owner_code = excluded.owner_code,
          owner_name = excluded.owner_name,
          currency = excluded.currency,
          period_start = excluded.period_start,
          period_end = excluded.period_end,
          target_value = excluded.target_value,
          current_value = excluded.current_value,
          attainment_rate = excluded.attainment_rate,
          forecast_value = excluded.forecast_value,
          forecast_attainment_rate = excluded.forecast_attainment_rate,
          elapsed_ratio = excluded.elapsed_ratio,
          actual_status = excluded.actual_status,
          status = excluded.status,
          actual_note = excluded.actual_note,
          forecast_note = excluded.forecast_note,
          rank_position = excluded.rank_position,
          rank_total = excluded.rank_total,
          rank_group = excluded.rank_group,
          evaluated_at = excluded.evaluated_at,
          updated_at = now()
        """,
        Ids.next(),
        tenantId,
        targetId,
        asOf,
        metricCode,
        scopeType,
        ownerId,
        ownerCode,
        ownerName,
        currency,
        periodStart,
        periodEnd,
        targetValue,
        currentValue,
        attainmentRate,
        forecastValue,
        forecastRate,
        elapsedRatio,
        actualStatus,
        status,
        actualNote,
        forecastNote,
        rankPosition,
        rankTotal,
        rankGroup,
        evaluatedAt);
  }

  // ==================================================================
  // 快照清单
  // ==================================================================

  /** 快照清单（真分页 + 真计数）：下游 17 绩效核算 / 1 工作台预警读的就是它。 */
  public ReportingTargetAttainmentModels.AttainmentSnapshotPage snapshots(
      UUID tenantId,
      String scopeType,
      String metricCode,
      String evaluatedOn,
      int page,
      int size) {
    return tenants.run(
        tenantId,
        () -> {
          var where = new StringBuilder(" where tenant_id = ?");
          var params = new ArrayList<Object>();
          params.add(tenantId);
          if (scopeType != null && !scopeType.isBlank()) {
            where.append(" and scope_type = ?");
            params.add(scopeType.trim().toUpperCase(Locale.ROOT));
          }
          if (metricCode != null && !metricCode.isBlank()) {
            where.append(" and metric_code = ?");
            params.add(metricCode.trim());
          }
          if (evaluatedOn != null && !evaluatedOn.isBlank()) {
            where.append(" and evaluated_on = ?");
            params.add(parseDate(evaluatedOn));
          }
          var total =
              sql.fetchOne("select count(*) from reporting_target_attainment" + where, params.toArray())
                  .get(0, Long.class);
          var effectiveSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
          var effectivePage = Math.max(page, 0);
          var query =
              "select id,target_id,evaluated_on,scope_type,owner_id,owner_code,owner_name,metric_code,"
                  + "currency,period_start,period_end,target_value,current_value,attainment_rate,"
                  + "forecast_value,forecast_attainment_rate,elapsed_ratio,actual_status,status,"
                  + "actual_note,forecast_note,rank_position,rank_total,rank_group,evaluated_at "
                  + "from reporting_target_attainment"
                  + where
                  + " order by evaluated_on desc, scope_type, metric_code, currency, rank_position, owner_name "
                  + "limit ? offset ?";
          var pageParams = new ArrayList<Object>(params);
          pageParams.add(effectiveSize);
          pageParams.add((long) effectivePage * effectiveSize);
          var rows = sql.fetch(query, pageParams.toArray());
          var out = new ArrayList<ReportingTargetAttainmentModels.AttainmentSnapshot>();
          for (var row : rows) {
            out.add(
                new ReportingTargetAttainmentModels.AttainmentSnapshot(
                    row.get(0, UUID.class),
                    row.get(1, UUID.class),
                    row.get(2, LocalDate.class),
                    row.get(3, String.class),
                    row.get(4, String.class),
                    row.get(5, String.class),
                    row.get(6, String.class),
                    row.get(7, String.class),
                    row.get(8, String.class),
                    row.get(9, LocalDate.class),
                    row.get(10, LocalDate.class),
                    row.get(11, BigDecimal.class),
                    row.get(12, BigDecimal.class),
                    row.get(13, BigDecimal.class),
                    row.get(14, BigDecimal.class),
                    row.get(15, BigDecimal.class),
                    row.get(16, BigDecimal.class),
                    row.get(17, String.class),
                    row.get(18, String.class),
                    row.get(19, String.class),
                    row.get(20, String.class),
                    row.get(21, Integer.class),
                    row.get(22, Integer.class),
                    row.get(23, String.class),
                    row.get(24, OffsetDateTime.class)));
          }
          return new ReportingTargetAttainmentModels.AttainmentSnapshotPage(
              effectivePage,
              effectiveSize,
              total,
              out,
              "快照是**一次评估的落库形态**（派生读模型，不是业务事实）："
                  + "记的是「在某个评估日、按当时已投影的事实，达成率与预测达成是多少」。"
                  + "「当前值 / 达成率」为 null 表示**当时拿不到实际值**（不是 0%）。");
        });
  }

  // ==================================================================
  // 内部：目标装载
  // ==================================================================

  /** 一行目标（来自 13.1.2.1 的 reporting_target）。 */
  private record TargetRow(
      UUID id,
      String metricCode,
      String scopeType,
      String ownerId,
      String ownerCode,
      String ownerName,
      String currency,
      BigDecimal targetValue,
      LocalDate periodStart,
      LocalDate periodEnd) {}

  private List<TargetRow> loadTargets(
      UUID tenantId, String scopeType, String metricCode, String currency, String status) {
    var where = new StringBuilder(" where tenant_id = ?");
    var params = new ArrayList<Object>();
    params.add(tenantId);
    if (scopeType != null && !scopeType.isBlank()) {
      where.append(" and scope_type = ?");
      params.add(scopeType.trim().toUpperCase(Locale.ROOT));
    }
    if (metricCode != null && !metricCode.isBlank()) {
      where.append(" and metric_code = ?");
      params.add(metricCode.trim());
    }
    if (currency != null && !currency.isBlank()) {
      where.append(" and currency = ?");
      params.add(currency.trim().toUpperCase(Locale.ROOT));
    }
    if (status != null && !status.isBlank()) {
      where.append(" and status = ?");
      params.add(status.trim().toUpperCase(Locale.ROOT));
    }
    var rows =
        sql.fetch(
            "select id,metric_code,scope_type,owner_id,owner_code,owner_name,currency,target_value,"
                + "period_start,period_end from reporting_target"
                + where
                + " order by scope_type, metric_code, currency, period_start, owner_name",
            params.toArray());
    var out = new ArrayList<TargetRow>();
    for (var row : rows) {
      out.add(
          new TargetRow(
              row.get(0, UUID.class),
              row.get(1, String.class),
              row.get(2, String.class),
              row.get(3, String.class),
              row.get(4, String.class),
              row.get(5, String.class),
              row.get(6, String.class),
              row.get(7, BigDecimal.class),
              row.get(8, LocalDate.class),
              row.get(9, LocalDate.class)));
    }
    return out;
  }

  // ==================================================================
  // 内部：实际值
  // ==================================================================

  /**
   * 某个归属对象 + 某套口径的可用性。
   *
   * @param available 来源是否存在（结构性）
   * @param livened 该归属对象在这套口径上**有没有过事实行**（决定 NO_DATA 还是 AVAILABLE）
   * @param daily 逐日事实（窗口内）
   */
  private record ActualSource(boolean available, boolean livened, Map<LocalDate, BigDecimal> daily) {

    static ActualSource none() {
      return new ActualSource(false, false, Map.of());
    }

    /**
     * 窗口内的合计。
     *
     * <p>🔴 **「这个归属对象在这套口径上从来没有过事实」⇒ 返回 null（拿不到，不是 0）**。
     *
     * <p>🔴 **「它有过事实，但这个窗口内一行都没有」⇒ 返回 0（真的 0）** ——
     * 窗口内确实没有发生，这是事实，不是「拿不到」。
     */
    BigDecimal sum(LocalDate from, LocalDate to) {
      if (!livened) return null;
      var total = BigDecimal.ZERO;
      for (var entry : daily.entrySet()) {
        var day = entry.getKey();
        if (day.isBefore(from) || day.isAfter(to)) continue;
        total = total.add(entry.getValue());
      }
      return total;
    }
  }

  /**
   * 装载某一批目标的实际值（按来源分两次查询，**每个来源一次**）。
   *
   * <p>🔴 **按目标币种取数**（事实按币种分行；不同币种绝不相加）。
   */
  private Map<String, ActualSource> loadActuals(
      UUID tenantId, List<TargetRow> targets, LocalDate asOf) {
    var out = new HashMap<String, ActualSource>();
    if (targets.isEmpty()) return out;

    var shopTargets =
        targets.stream()
            .filter(t -> ReportingTargetMetricCatalog.SCOPE_SHOP.equals(t.scopeType()))
            .toList();
    var productTargets =
        targets.stream()
            .filter(t -> ReportingTargetMetricCatalog.SCOPE_PRODUCT.equals(t.scopeType()))
            .toList();

    loadShopActuals(tenantId, shopTargets, asOf, out);
    loadProductActuals(tenantId, productTargets, asOf, out);
    return out;
  }

  private void loadShopActuals(
      UUID tenantId, List<TargetRow> targets, LocalDate asOf, Map<String, ActualSource> out) {
    if (targets.isEmpty()) return;
    var owners = targets.stream().map(t -> t.ownerId()).distinct().toList();
    var metrics = targets.stream().map(t -> t.metricCode()).distinct().toList();
    var from = targets.stream().map(t -> t.periodStart()).min(LocalDate::compareTo).orElseThrow();
    var to = asOf;

    var daily = new HashMap<String, Map<LocalDate, BigDecimal>>();
    var live = new HashSet<String>();
    var sqlText =
        "select shop_code, metric_code, currency, bucket_date, sum(value) "
            + "from reporting_store_metric_fact "
            + "where tenant_id = ? and shop_code in ("
            + placeholders(owners.size())
            + ") and metric_code in ("
            + placeholders(metrics.size())
            + ") and bucket_date >= ? and bucket_date <= ? "
            + "group by shop_code, metric_code, currency, bucket_date";
    var params = new ArrayList<Object>();
    params.add(tenantId);
    params.addAll(owners);
    params.addAll(metrics);
    params.add(from);
    params.add(to);
    for (var row : sql.fetch(sqlText, params.toArray())) {
      var owner = row.get(0, String.class);
      var metric = row.get(1, String.class);
      var currency = row.get(2, String.class);
      var day = row.get(3, LocalDate.class);
      var value = row.get(4, BigDecimal.class);
      daily.computeIfAbsent(key(owner, metric, currency), k -> new LinkedHashMap<>())
          .merge(day, value, BigDecimal::add);
    }

    // 生命周期：这个归属对象在这套口径上**有没有过**事实行（用来区分 NO_DATA 与真的 0）。
    var liveSql =
        "select shop_code, metric_code from reporting_store_metric_fact "
            + "where tenant_id = ? and shop_code in ("
            + placeholders(owners.size())
            + ") and metric_code in ("
            + placeholders(metrics.size())
            + ") group by shop_code, metric_code";
    var liveParams = new ArrayList<Object>();
    liveParams.add(tenantId);
    liveParams.addAll(owners);
    liveParams.addAll(metrics);
    for (var row : sql.fetch(liveSql, liveParams.toArray())) {
      live.add(key(row.get(0, String.class), row.get(1, String.class), null));
    }

    for (var target : targets) {
      var currency = target.currency();
      var source =
          new ActualSource(
              true,
              live.contains(key(target.ownerId(), target.metricCode(), null)),
              daily.getOrDefault(key(target.ownerId(), target.metricCode(), currency), Map.of()));
      out.put(targetKey(target), source);
    }
  }

  private void loadProductActuals(
      UUID tenantId, List<TargetRow> targets, LocalDate asOf, Map<String, ActualSource> out) {
    if (targets.isEmpty()) return;
    // 产品维度的口径映射：**没有等价口径的一律不做**（如实报不可得）。
    var mapped = new HashMap<String, String>();
    var owners = new ArrayList<String>();
    var metrics = new ArrayList<String>();
    for (var target : targets) {
      var productMetric = PRODUCT_CALIBER.get(target.metricCode());
      if (productMetric == null) continue;
      mapped.put(targetKey(target), productMetric);
      if (!owners.contains(target.ownerId())) owners.add(target.ownerId());
      if (!metrics.contains(productMetric)) metrics.add(productMetric);
    }
    if (owners.isEmpty()) {
      // 一个都映射不上 ⇒ 全部按「没有来源」处理。
      for (var target : targets) {
        out.put(targetKey(target), ActualSource.none());
      }
      return;
    }
    var uuids = new ArrayList<UUID>();
    for (var owner : owners) {
      try {
        uuids.add(UUID.fromString(owner));
      } catch (IllegalArgumentException ignored) {
        // owner_id 不是 uuid ⇒ 这个归属对象在商品维度没有档案 ⇒ 当作「没有来源」。
      }
    }
    if (uuids.isEmpty()) {
      for (var target : targets) {
        out.put(targetKey(target), ActualSource.none());
      }
      return;
    }
    var from = targets.stream().map(t -> t.periodStart()).min(LocalDate::compareTo).orElseThrow();
    var to = asOf;

    var daily = new HashMap<String, Map<LocalDate, BigDecimal>>();
    var live = new HashSet<String>();
    var sqlText =
        "select dimension_id, metric_code, currency, bucket_date, sum(value) "
            + "from reporting_product_metric_fact "
            + "where tenant_id = ? and dimension_type = 'SPU' and dimension_id in ("
            + placeholders(uuids.size())
            + ") and metric_code in ("
            + placeholders(metrics.size())
            + ") and bucket_date >= ? and bucket_date <= ? "
            + "group by dimension_id, metric_code, currency, bucket_date";
    var params = new ArrayList<Object>();
    params.add(tenantId);
    params.addAll(uuids);
    params.addAll(metrics);
    params.add(from);
    params.add(to);
    for (var row : sql.fetch(sqlText, params.toArray())) {
      var dimension = row.get(0, UUID.class).toString();
      var metric = row.get(1, String.class);
      var currency = row.get(2, String.class);
      var day = row.get(3, LocalDate.class);
      var value = row.get(4, BigDecimal.class);
      daily.computeIfAbsent(key(dimension, metric, currency), k -> new LinkedHashMap<>())
          .merge(day, value, BigDecimal::add);
    }

    var liveSql =
        "select dimension_id, metric_code from reporting_product_metric_fact "
            + "where tenant_id = ? and dimension_type = 'SPU' and dimension_id in ("
            + placeholders(uuids.size())
            + ") and metric_code in ("
            + placeholders(metrics.size())
            + ") group by dimension_id, metric_code";
    var liveParams = new ArrayList<Object>();
    liveParams.add(tenantId);
    liveParams.addAll(uuids);
    liveParams.addAll(metrics);
    for (var row : sql.fetch(liveSql, liveParams.toArray())) {
      live.add(key(row.get(0, UUID.class).toString(), row.get(1, String.class), null));
    }

    for (var target : targets) {
      var productMetric = mapped.get(targetKey(target));
      if (productMetric == null) {
        out.put(targetKey(target), ActualSource.none());
        continue;
      }
      // ⚠️ **归属对象标识要归一成 uuid 的规范小写形式**再查事实：
      //   事实行的 dimension_id 落库后取回的是 uuid 的小写文本，
      //   而目标里的 owner_id 是**用户写进来的字符串**（可能是大写）。
      //   不归一就会「大写 UUID 查不到自己的事实」—— 那会被误读成「没有事实」。
      var ownerKey = normalizeUuid(target.ownerId());
      var source =
          new ActualSource(
              true,
              live.contains(key(ownerKey, productMetric, null)),
              daily.getOrDefault(key(ownerKey, productMetric, target.currency()), Map.of()));
      out.put(targetKey(target), source);
    }
  }

  /** uuid 的规范小写文本（不是 uuid 就原样返回）。 */
  private static String normalizeUuid(String raw) {
    try {
      return UUID.fromString(raw).toString();
    } catch (RuntimeException ignored) {
      return raw;
    }
  }

  // ==================================================================
  // 内部：评估
  // ==================================================================

  /** 评估结果（算出来的那些数与状态）。 */
  private record Evaluated(
      BigDecimal currentValue,
      BigDecimal attainmentRate,
      BigDecimal forecastValue,
      BigDecimal forecastRate,
      BigDecimal elapsedRatio,
      String actualStatus,
      String status,
      String note,
      boolean underAchieved) {}

  private List<ReportingTargetAttainmentModels.AttainmentRow> evaluateAll(
      UUID tenantId, List<TargetRow> targets, LocalDate asOf) {
    var actuals = loadActuals(tenantId, targets, asOf);
    var out = new ArrayList<ReportingTargetAttainmentModels.AttainmentRow>();
    for (var target : targets) {
      var source = actuals.getOrDefault(targetKey(target), ActualSource.none());
      var metric = metricInfo(tenantId, target);
      var buckets = buckets(tenantId, target, source, asOf);
      var windowEnd = target.periodEnd().isBefore(asOf) ? target.periodEnd() : asOf;
      var evaluated =
          evaluateWindow(
              target.targetValue(),
              source,
              target.periodStart(),
              windowEnd,
              target.periodStart(),
              target.periodEnd(),
              asOf);
      var remainingDays =
          target.periodEnd().isBefore(asOf)
              ? 0
              : (int) java.time.temporal.ChronoUnit.DAYS.between(asOf, target.periodEnd());
      out.add(
          new ReportingTargetAttainmentModels.AttainmentRow(
              target.id(),
              target.scopeType(),
              target.ownerId(),
              target.ownerCode(),
              target.ownerName(),
              target.metricCode(),
              metric.displayName(),
              metric.unit(),
              target.currency(),
              target.periodStart(),
              target.periodEnd(),
              target.targetValue(),
              evaluated.currentValue(),
              evaluated.attainmentRate(),
              evaluated.forecastValue(),
              evaluated.forecastRate(),
              evaluated.elapsedRatio(),
              remainingDays,
              evaluated.actualStatus(),
              evaluated.status(),
              evaluated.note(),
              evaluated.underAchieved(),
              0,
              0,
              "",
              buckets));
    }
    return out;
  }

  /**
   * 评估一个窗口（目标本身或一个分解项）。
   *
   * @param targetValue 目标值
   * @param source 实际值来源
   * @param valueFrom 取数窗口起
   * @param valueTo 取数窗口止
   * @param periodStart 周期起（用于判定「周期尚未开始」）
   * @param periodEnd 周期止
   * @param asOf 评估日
   */
  private Evaluated evaluateWindow(
      BigDecimal targetValue,
      ActualSource source,
      LocalDate valueFrom,
      LocalDate valueTo,
      LocalDate periodStart,
      LocalDate periodEnd,
      LocalDate asOf) {
    // 1) 没有来源（结构性）：该维度 / 该口径根本没有事实投影。
    if (!source.available()) {
      return new Evaluated(
          null,
          null,
          null,
          null,
          BigDecimal.ZERO.setScale(RATE_SCALE, RoundingMode.HALF_UP),
          "NOT_AVAILABLE",
          "UNAVAILABLE",
          "该归属维度 / 该口径**没有实际值来源** ⇒ 算不出达成率（**不是 0%**）。",
          false);
    }
    // 2) 周期还没开始：一天都没发生 ⇒ 算不出达成率（**不是 0%**）。
    if (asOf.isBefore(periodStart)) {
      return new Evaluated(
          null,
          null,
          null,
          null,
          BigDecimal.ZERO.setScale(RATE_SCALE, RoundingMode.HALF_UP),
          "NO_DATA",
          "NOT_STARTED",
          "周期尚未开始（评估日 "
              + asOf
              + " 早于周期起 "
              + periodStart
              + "）⇒ 这个周期里还没有任何一天发生，算不出达成率（**不是 0%**）。",
          false);
    }
    // 3) 取数：窗口内没有事实行 ⇒ 拿不到（**不是 0**）。
    var current = source.sum(valueFrom, valueTo);
    if (current == null) {
      return new Evaluated(
          null,
          null,
          null,
          null,
          BigDecimal.ZERO.setScale(RATE_SCALE, RoundingMode.HALF_UP),
          "NO_DATA",
          "UNAVAILABLE",
          "这个归属对象在这套口径上**还没有任何实际值事实** ⇒ 算不出达成率（**不是 0%**）。",
          false);
    }
    var totalDays = (int) java.time.temporal.ChronoUnit.DAYS.between(periodStart, periodEnd) + 1;
    var elapsedDays =
        Math.min(
            Math.max((int) java.time.temporal.ChronoUnit.DAYS.between(periodStart, asOf) + 1, 0),
            totalDays);
    var elapsedRatio =
        BigDecimal.valueOf(elapsedDays)
            .divide(BigDecimal.valueOf(totalDays), RATE_SCALE, RoundingMode.HALF_UP);

    // 4) 目标值不是正数 ⇒ 达成率的分母为 0 ⇒ **达成率没有定义**。
    if (targetValue == null || targetValue.compareTo(BigDecimal.ZERO) <= 0) {
      return new Evaluated(
          current.setScale(VALUE_SCALE, RoundingMode.HALF_UP),
          null,
          null,
          null,
          elapsedRatio,
          "AVAILABLE",
          "NOT_COMPARABLE",
          "目标值不是正数 ⇒ 达成率的分母为 0，**达成率没有定义**（**不是 0%**）。",
          false);
    }
    var attainmentRate =
        current.divide(targetValue, RATE_SCALE, RoundingMode.HALF_UP);
    // 预测期末值 = 当前值 ÷ 时间进度（**用精确比例算，不拿舍入后的比例去乘**）。
    BigDecimal forecastValue = null;
    BigDecimal forecastRate = null;
    if (elapsedDays > 0) {
      forecastValue =
          current
              .multiply(BigDecimal.valueOf(totalDays))
              .divide(BigDecimal.valueOf(elapsedDays), VALUE_SCALE, RoundingMode.HALF_UP);
      forecastRate =
          current
              .multiply(BigDecimal.valueOf(totalDays))
              .divide(
                  BigDecimal.valueOf(elapsedDays).multiply(targetValue),
                  RATE_SCALE,
                  RoundingMode.HALF_UP);
    }
    String status;
    String note;
    boolean underAchieved;
    if (attainmentRate.compareTo(ACHIEVED_THRESHOLD) >= 0) {
      status = "ACHIEVED";
      note = "达成率已 ≥ 100%。";
      underAchieved = false;
    } else if (forecastRate == null) {
      status = "ON_TRACK";
      note = "周期刚开始，还推不出期末值。";
      underAchieved = false;
    } else if (forecastRate.compareTo(ON_TRACK_THRESHOLD) >= 0) {
      status = "ON_TRACK";
      note =
          "按当前速度（时间进度 "
              + elapsedRatio
              + "）推算，期末可达 "
              + forecastRate
              + " ⇒ 能达标。";
      underAchieved = false;
    } else if (forecastRate.compareTo(AT_RISK_THRESHOLD) >= 0) {
      status = "AT_RISK";
      note =
          "按当前速度推算，期末只能到 "
              + forecastRate
              + " ⇒ **差一点**，但还有机会补回来。";
      underAchieved = true;
    } else {
      status = "BEHIND";
      note =
          "按当前速度推算，期末只能到 "
              + forecastRate
              + " ⇒ **明显跟不上**（低于 80%）。";
      underAchieved = true;
    }
    return new Evaluated(
        current.setScale(VALUE_SCALE, RoundingMode.HALF_UP),
        attainmentRate,
        forecastValue,
        forecastRate,
        elapsedRatio,
        "AVAILABLE",
        status,
        note,
        underAchieved);
  }

  /** 分解计划的逐项达成（真数据，来自 13.1.2.1 的分解计划表）。 */
  private List<ReportingTargetAttainmentModels.AttainmentBucket> buckets(
      UUID tenantId, TargetRow target, ActualSource source, LocalDate asOf) {
    var rows =
        sql.fetch(
            "select bucket_start,bucket_end,granularity,currency,target_value "
                + "from reporting_target_bucket where tenant_id = ? and target_id = ? "
                + "order by bucket_start",
            tenantId,
            target.id());
    var out = new ArrayList<ReportingTargetAttainmentModels.AttainmentBucket>();
    for (var row : rows) {
      var bucketStart = row.get(0, LocalDate.class);
      var bucketEnd = row.get(1, LocalDate.class);
      var granularity = row.get(2, String.class);
      var currency = row.get(3, String.class);
      var bucketTarget = row.get(4, BigDecimal.class);
      var windowEnd = bucketEnd.isBefore(asOf) ? bucketEnd : asOf;
      var evaluated =
          evaluateWindow(
              bucketTarget, source, bucketStart, windowEnd, bucketStart, bucketEnd, asOf);
      out.add(
          new ReportingTargetAttainmentModels.AttainmentBucket(
              bucketStart,
              bucketEnd,
              granularity,
              currency,
              bucketTarget,
              evaluated.currentValue(),
              evaluated.attainmentRate(),
              evaluated.elapsedRatio(),
              evaluated.forecastValue(),
              evaluated.forecastRate(),
              evaluated.actualStatus(),
              evaluated.status(),
              evaluated.note()));
    }
    return out;
  }

  /** 口径信息（显示名 + 单位），从口径表读（**口径真相在表里，不在本类里**）。 */
  private MetricInfo metricInfo(UUID tenantId, TargetRow target) {
    var table =
        ReportingTargetMetricCatalog.SCOPE_SHOP.equals(target.scopeType())
            ? "reporting_store_metric_definition"
            : "reporting_metric_definition";
    var row =
        sql.fetchOne(
            "select display_name, unit from " + table + " where tenant_id = ? and metric_code = ?",
            tenantId,
            target.metricCode());
    if (row == null) {
      return new MetricInfo(target.metricCode(), "UNKNOWN");
    }
    return new MetricInfo(row.get(0, String.class), row.get(1, String.class));
  }

  private record MetricInfo(String displayName, String unit) {}

  // ==================================================================
  // 内部：排名 / 分组 / 预警
  // ==================================================================

  /**
   * 排名（**在「同归属维度 + 同口径 + 同币种 + 同周期」内**）。
   *
   * <p>🔴 **算不出达成率的不进排名**：给「不可得」一个名次等于替它编了一个成绩。
   */
  private List<ReportingTargetAttainmentModels.AttainmentRow> rank(
      List<ReportingTargetAttainmentModels.AttainmentRow> rows) {
    var groups = new LinkedHashMap<String, List<ReportingTargetAttainmentModels.AttainmentRow>>();
    for (var row : rows) {
      groups
          .computeIfAbsent(groupKey(row), k -> new ArrayList<>())
          .add(row);
    }
    var out = new ArrayList<ReportingTargetAttainmentModels.AttainmentRow>();
    for (var entry : groups.entrySet()) {
      var members = entry.getValue();
      var rankable =
          members.stream()
              .filter(r -> r.attainmentRate() != null)
              .sorted(
                  Comparator.comparing(ReportingTargetAttainmentModels.AttainmentRow::attainmentRate)
                      .reversed()
                      .thenComparing(
                          ReportingTargetAttainmentModels.AttainmentRow::forecastRate,
                          Comparator.nullsLast(Comparator.reverseOrder()))
                      .thenComparing(ReportingTargetAttainmentModels.AttainmentRow::ownerName))
              .toList();
      var positions = new HashMap<UUID, Integer>();
      for (int i = 0; i < rankable.size(); i++) {
        positions.put(rankable.get(i).targetId(), i + 1);
      }
      for (var member : members) {
        var position = positions.getOrDefault(member.targetId(), 0);
        out.add(
            new ReportingTargetAttainmentModels.AttainmentRow(
                member.targetId(),
                member.scopeType(),
                member.ownerId(),
                member.ownerCode(),
                member.ownerName(),
                member.metricCode(),
                member.metricDisplayName(),
                member.unit(),
                member.currency(),
                member.periodStart(),
                member.periodEnd(),
                member.targetValue(),
                member.currentValue(),
                member.attainmentRate(),
                member.forecastValue(),
                member.forecastRate(),
                member.elapsedRatio(),
                member.remainingDays(),
                member.actualStatus(),
                member.status(),
                member.statusNote(),
                member.underAchieved(),
                position,
                rankable.size(),
                entry.getKey(),
                member.buckets()));
      }
    }
    // 稳定排序：先按分组，再按组内位次（未参与排名的排在最后，按归属对象名排）。
    out.sort(
        Comparator.comparing(ReportingTargetAttainmentModels.AttainmentRow::rankGroup)
            .thenComparingInt(ReportingTargetAttainmentModels.AttainmentRow::rank)
            .thenComparing(ReportingTargetAttainmentModels.AttainmentRow::ownerName));
    return out;
  }

  /** 分组（响应里的 groups）。 */
  private List<ReportingTargetAttainmentModels.AttainmentGroup> groups(
      List<ReportingTargetAttainmentModels.AttainmentRow> rows) {
    var grouped = new LinkedHashMap<String, List<ReportingTargetAttainmentModels.AttainmentRow>>();
    for (var row : rows) {
      grouped.computeIfAbsent(row.rankGroup(), k -> new ArrayList<>()).add(row);
    }
    var out = new ArrayList<ReportingTargetAttainmentModels.AttainmentGroup>();
    for (var entry : grouped.entrySet()) {
      var members = entry.getValue();
      var first = members.get(0);
      out.add(
          new ReportingTargetAttainmentModels.AttainmentGroup(
              first.scopeType(),
              first.metricCode(),
              first.currency(),
              first.periodStart(),
              first.periodEnd(),
              entry.getKey(),
              members.size(),
              (int) members.stream().filter(r -> r.attainmentRate() != null).count(),
              (int) members.stream().filter(r -> r.attainmentRate() == null).count(),
              members));
    }
    return out;
  }

  /** 预警清单（**覆盖全量，不随分页截断**）。 */
  private List<ReportingTargetAttainmentModels.AttainmentAlert> alerts(
      List<ReportingTargetAttainmentModels.AttainmentRow> rows) {
    var out = new ArrayList<ReportingTargetAttainmentModels.AttainmentAlert>();
    for (var row : rows) {
      if ("BEHIND".equals(row.status())) {
        out.add(
            new ReportingTargetAttainmentModels.AttainmentAlert(
                row.targetId(),
                row.scopeType(),
                row.ownerId(),
                row.ownerName(),
                row.metricCode(),
                row.currency(),
                "CRITICAL",
                "FORECAST_BELOW_TARGET",
                row.ownerName()
                    + " 的 "
                    + row.metricDisplayName()
                    + " 按当前速度推算只能到 "
                    + row.forecastRate()
                    + "（时间进度已过 "
                    + row.elapsedRatio()
                    + "）—— **明显跟不上**。",
                row.attainmentRate(),
                row.forecastRate(),
                row.elapsedRatio()));
      } else if ("AT_RISK".equals(row.status())) {
        out.add(
            new ReportingTargetAttainmentModels.AttainmentAlert(
                row.targetId(),
                row.scopeType(),
                row.ownerId(),
                row.ownerName(),
                row.metricCode(),
                row.currency(),
                "WARNING",
                "FORECAST_BELOW_TARGET",
                row.ownerName()
                    + " 的 "
                    + row.metricDisplayName()
                    + " 按当前速度推算只能到 "
                    + row.forecastRate()
                    + " —— **差一点**，还有补回来的机会。",
                row.attainmentRate(),
                row.forecastRate(),
                row.elapsedRatio()));
      } else if ("UNAVAILABLE".equals(row.status())) {
        out.add(
            new ReportingTargetAttainmentModels.AttainmentAlert(
                row.targetId(),
                row.scopeType(),
                row.ownerId(),
                row.ownerName(),
                row.metricCode(),
                row.currency(),
                "WARNING",
                "NO_ACTUALS",
                row.ownerName()
                    + " 的 "
                    + row.metricDisplayName()
                    + " **算不出达成率**（"
                    + row.statusNote()
                    + "）—— 这是「拿不到实际值」，**不是「落后了」**。",
                row.attainmentRate(),
                row.forecastRate(),
                row.elapsedRatio()));
      } else if ("NOT_COMPARABLE".equals(row.status())) {
        out.add(
            new ReportingTargetAttainmentModels.AttainmentAlert(
                row.targetId(),
                row.scopeType(),
                row.ownerId(),
                row.ownerName(),
                row.metricCode(),
                row.currency(),
                "WARNING",
                "TARGET_VALUE_INVALID",
                row.ownerName()
                    + " 的 "
                    + row.metricDisplayName()
                    + " 目标值不是正数 ⇒ **达成率没有定义**（请检查目标值）。",
                row.attainmentRate(),
                row.forecastRate(),
                row.elapsedRatio()));
      }
    }
    return out;
  }

  /** 概览计数（**「算不出」单独计数，不并进未达标**）。 */
  private ReportingTargetAttainmentModels.AttainmentSummary summary(
      List<ReportingTargetAttainmentModels.AttainmentRow> rows) {
    int achieved = 0, onTrack = 0, atRisk = 0, behind = 0, notStarted = 0, notComparable = 0;
    int unavailable = 0, underAchieved = 0;
    for (var row : rows) {
      switch (row.status()) {
        case "ACHIEVED" -> achieved++;
        case "ON_TRACK" -> onTrack++;
        case "AT_RISK" -> atRisk++;
        case "BEHIND" -> behind++;
        case "NOT_STARTED" -> notStarted++;
        case "NOT_COMPARABLE" -> notComparable++;
        default -> unavailable++;
      }
      if (row.underAchieved()) underAchieved++;
    }
    return new ReportingTargetAttainmentModels.AttainmentSummary(
        rows.size(),
        achieved,
        onTrack,
        atRisk,
        behind,
        notStarted,
        notComparable,
        unavailable,
        underAchieved);
  }

  // ==================================================================
  // 内部：时钟与时区
  // ==================================================================

  /** 本租户的分桶时区（**服务端判定**，不来自请求参数）。 */
  private record TenantZone(String zone, String source, String note) {}

  /**
   * 本租户的分桶时区。
   *
   * <p>⚠️ 与 13.1.2.1 / 1.1.2.1 / 13.1.1.1 **同一条纪律**：时区取自本租户事实上的分桶时区，
   * 拿不到才回退 UTC 并**在响应里说清楚**（{@code timezoneSource} = FALLBACK_UTC）。
   */
  private TenantZone tenantZone(UUID tenantId) {
    var row =
        sql.fetchOne(
            "select timezone from reporting_metric_fact "
                + "where tenant_id = ? and timezone <> '' order by updated_at desc limit 1",
            tenantId);
    if (row != null) {
      var zone = row.get(0, String.class);
      if (zone != null && !zone.isBlank()) {
        try {
          java.time.ZoneId.of(zone);
          return new TenantZone(zone, "PROJECTION", "");
        } catch (RuntimeException ignored) {
          // 落库的是非法时区 id ⇒ 落回 UTC，并在响应里说清楚（不静默）。
        }
      }
    }
    // 租户级事实还没有 ⇒ 看店铺 / 商品事实（同一租户的其它事实表）。
    for (var table : List.of("reporting_store_metric_fact", "reporting_product_metric_fact")) {
      var fallback =
          sql.fetchOne(
              "select timezone from " + table + " "
                  + "where tenant_id = ? and timezone <> '' order by updated_at desc limit 1",
              tenantId);
      if (fallback == null) continue;
      var zone = fallback.get(0, String.class);
      if (zone == null || zone.isBlank()) continue;
      try {
        java.time.ZoneId.of(zone);
        return new TenantZone(
            zone,
            "PROJECTION",
            "本租户的租户级事实（reporting_metric_fact）还没有，取自 "
                + table
                + " 上的分桶时区。");
      } catch (RuntimeException ignored) {
        // 非法时区 id ⇒ 继续找下一个来源。
      }
    }
    return new TenantZone(
        "UTC",
        "FALLBACK_UTC",
        "本租户还没有任何投影事实，取不到自己的分桶时区，当前评估日暂按 UTC 计算 —— "
            + "一旦投影进来就会改用该租户自己的时区。");
  }

  /** 数据库时钟（**评估日与评估时刻都取自 PG，不是 JVM 墙钟**）。 */
  private record DbClock(LocalDate asOfDate, OffsetDateTime evaluatedAt) {}

  /**
   * 取数据库时钟。
   *
   * <p>🔴 <b>为什么不用 {@code LocalDate.now()}</b>：JVM 墙钟与数据库时钟是两个钟域。
   * 「今天是哪一天」一旦在两个钟域里各算一次，跨时区时就会偏一天 ——
   * 而一天在预测达成率上就是几个百分点（本仓已有两次同类事故：
   * identity 审批待办超时、warehouse 货权档漂移判定）。
   * ⇒ 评估日与评估时刻**同源于 PG 的 {@code now()}**，一次查询取回。
   */
  private DbClock dbClock(String zone) {
    var row =
        sql.fetchOne("select (now() at time zone ?::text)::date, now()", zone);
    if (row == null) {
      throw new IllegalStateException("取不到数据库时钟。");
    }
    return new DbClock(row.get(0, LocalDate.class), row.get(1, OffsetDateTime.class));
  }

  // ==================================================================
  // 内部：工具
  // ==================================================================

  /** 目标的唯一键（同一租户内）。 */
  private static String targetKey(TargetRow target) {
    return target.id().toString();
  }

  /** 日期参数（**格式不对要 400，不是 500**）。 */
  private static LocalDate parseDate(String raw) {
    try {
      return LocalDate.parse(raw.trim());
    } catch (RuntimeException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "评估日必须是 ISO 日期（yyyy-MM-dd），收到：" + raw);
    }
  }

  /** 事实行的键。 */
  private static String key(String owner, String metric, String currency) {
    return owner + " " + metric + " " + (currency == null ? "" : currency);
  }

  /** 排名分组键。 */
  private static String groupKey(ReportingTargetAttainmentModels.AttainmentRow row) {
    return row.scopeType()
        + "|"
        + row.metricCode()
        + "|"
        + row.currency()
        + "|"
        + row.periodStart()
        + "|"
        + row.periodEnd();
  }

  /** IN 列表的占位符（**值仍然是绑定参数**，不是拼进 SQL 的）。 */
  private static String placeholders(int count) {
    return String.join(",", java.util.Collections.nCopies(Math.max(count, 1), "?"));
  }
}
