package com.starcon.platform;

import static com.starcon.platform.AutomationRuleEffectModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.platform.AutomationRuleRunModels.AutomationRuleRunOutcome;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
 * 18.3.2.2「规则效果」的服务层。
 *
 * <p>数据访问一律走 <b>jOOQ 显式 SQL</b>（AGENTS.md：不用 JPA 懒加载掩盖关键逻辑）。
 * 租户上下文由 {@link AccessService#run} 绑定 —— 本类<b>不</b>读任何请求头里的租户。
 *
 * <p><b>七条纪律：</b>
 *
 * <ol>
 *   <li><b>🔴 只读 18.3.2.1 的表</b>：{@code platform_automation_rule_run} 与
 *       {@code platform_automation_rule_run_step} 是<b>只读聚合</b>的来源。
 *       本模块**一个字都不改**它们的结构（每张事实表只有一个写入所有者）。
 *       ⚠️ <b>逐步明细（_step）不参与聚合</b>：逐步明细是「一次执行内部」的粒度，
 *       混进来会把执行次数放大好几倍，成功率就成了假的。
 *   <li><b>🔴 五档结论不许压扁</b>：成功 / 失败 / 跳过 / 超时 / <b>未执行</b>。
 *       「未执行」既不算成功也不算失败，<b>单列一档</b>，也<b>不进</b>成功率的母。
 *       成功率的母 = 成功 + 失败 + 超时（= 「真正跑过」的次数）。
 *   <li><b>🔴 耗时用整数毫秒</b>：{@code duration_ms} 是整数毫秒，禁浮点；
 *       平均耗时由 SQL 的<b>精确 numeric 除法 + round(...,0)</b> 得出。
 *   <li><b>🔴 节省工时预估必须带口径</b>：口径落在
 *       {@code platform_automation_rule_effect_baseline.manual_minutes_per_run}
 *       （租户自己登记，<b>不许硬编码常量</b>），并随报表一起返回
 *       （{@link AutomationRuleEffectSavingBasis}）。没有口径的规则，
 *       节省工时就是<b>空</b>（不是 0）。
 *   <li><b>🔴 误触发反馈能定位到规则与执行</b>：反馈带 {@code rule_id}，
 *       可选带 {@code run_id}；「误触发率高的规则」因此能被**快速定位**。
 *   <li><b>🔴 关闭动作归 18.3.1.1</b>：本模块只输出「建议关闭 + 可定位到具体规则的入口」
 *       （规则 id / 规则名快照 / 指向规则档案的引用），<b>不去改 18.3.1.1 的表</b>。
 *   <li><b>🔴 统计的是「记录」，不是「真实执行」</b>：18.3.2 执行引擎<b>尚未交付</b> ⇒
 *       报表必须如实说明这一点（能力声明 + 报表字段 {@code engineDelivered}）。
 * </ol>
 */
@Service
public class AutomationRuleEffectService {

  /** 一页最多返回多少行。 */
  static final int MAX_PAGE_SIZE = 100;

  /** 下钻列表最多给多少条 run id / run 行。 */
  static final int MAX_DRILLDOWN = 50;

  // ==========================================================================
  // 判定阈值（**写清楚的口径**，不是拍脑袋的数）
  // ==========================================================================

  /** 误触发率超过这个比例就**建议关闭或收紧条件**（蓝图加粗句的落点）。 */
  static final BigDecimal FALSE_TRIGGER_RATE_THRESHOLD = new BigDecimal("0.20");

  /** 失败率超过这个比例就建议排查动作 / 条件。 */
  static final BigDecimal FAILURE_RATE_THRESHOLD = new BigDecimal("0.30");

  /** 超时占比超过这个比例就建议调阈值 / 拆分动作。 */
  static final BigDecimal TIMEOUT_RATE_THRESHOLD = new BigDecimal("0.10");

  /** 跳过占比超过这个比例就建议放宽条件。 */
  static final BigDecimal SKIPPED_RATE_THRESHOLD = new BigDecimal("0.50");

  /** 平均耗时超过这个毫秒数就算「慢」。 */
  static final long SLOW_DURATION_MS = 5000L;

  /** 有效执行次数少于这个数就只能说「样本太少」。 */
  static final int INSUFFICIENT_DATA_MIN_RATED = 3;

  /** 有效执行次数至少达到这个数，且成功率达标，才敢说「表现良好」。 */
  static final int PERFORMING_WELL_MIN_RATED = 5;

  /** 「表现良好」的成功率门槛。 */
  static final BigDecimal PERFORMING_WELL_RATE = new BigDecimal("0.90");

  /** 比率的小数位（NUMERIC(19,6)）。 */
  private static final int RATE_SCALE = 6;

  private final DSLContext sql;
  private final ObjectMapper json;

  public AutomationRuleEffectService(DSLContext sql, ObjectMapper json) {
    this.sql = sql;
    this.json = json;
  }

  // ================================================================ 工具

  private static void check(boolean condition, String message) {
    if (!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  /** 找不到（404）。 */
  private static void notFound(String message) {
    throw new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static String required(String value, String label, int max) {
    var trimmed = value == null ? "" : value.trim();
    check(!trimmed.isEmpty(), label + "不能为空");
    check(trimmed.length() <= max, label + "长度不能超过 " + max + " 个字符");
    return trimmed;
  }

  private static String optional(String value, String label, int max) {
    var trimmed = value == null ? "" : value.trim();
    check(trimmed.length() <= max, label + "长度不能超过 " + max + " 个字符");
    return trimmed;
  }

  /** ISO-8601 带时区的时间（如 {@code 2026-10-30T00:00:00Z}）。 */
  private static OffsetDateTime parseTime(String raw, String label) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return OffsetDateTime.parse(raw.trim());
    } catch (RuntimeException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          label + "必须是 ISO-8601 带时区的时间（如 2026-10-30T00:00:00Z）");
    }
  }

  /** 当前会话绑定的租户（**不**来自请求头）。 */
  private UUID currentTenant() {
    var raw =
        sql.fetchOne("select current_setting('app.current_tenant', true) as t").get(0, String.class);
    check(raw != null && !raw.isBlank(), "缺少可信租户上下文");
    return UUID.fromString(raw);
  }

  /** **DB 时钟**（与落库的时间列同一个钟域）。 */
  private OffsetDateTime dbNow() {
    return sql.fetchOne("select clock_timestamp()").get(0, OffsetDateTime.class);
  }

  /** 规则档案的只读引用（**只读**，不写 18.3.1.1 的任何一张表）。 */
  private record RuleRef(UUID id, String name, String triggerEventType) {}

  private RuleRef ruleRef(UUID ruleId) {
    var row =
        sql.fetchOne(
            "select id, name, trigger_event_type from platform_automation_rule where id = ?",
            ruleId);
    if (row == null) notFound("规则不存在");
    return new RuleRef(
        row.get(0, UUID.class), row.get(1, String.class), row.get(2, String.class));
  }

  /** 比率（**精确 numeric 除法**，不是浮点）。分母为 0 ⇒ {@code null}（不是 0%）。 */
  private static BigDecimal rate(long numerator, long denominator) {
    if (denominator <= 0) return null;
    return BigDecimal.valueOf(numerator)
        .divide(BigDecimal.valueOf(denominator), RATE_SCALE, RoundingMode.HALF_UP);
  }

  // ================================================================ 能力声明

  /**
   * 18.3.2.2 的能力声明：逐维说清「有没有、依据是什么、缺了会怎样」。
   *
   * <p>🔴 **规则执行引擎如实答「未交付」** —— 本模块统计的是<b>记录</b>不是<b>真实执行</b>：
   * 现阶段 run 行绝大多数是人工登记或「未执行」的重跑请求。
   */
  public AutomationRuleEffectCapabilities capabilities() {
    var capabilities =
        List.of(
            new AutomationRuleEffectCapability(
                "18.3.2.2",
                "🔴 **触发次数 / 五档结论**（不压扁）",
                true,
                "platform_automation_rule_run 表 + /platform/automation/effect 端点；"
                    + "结论分成功 / 失败 / 跳过 / 超时 / **未执行** 五档，「未执行」单列一档，"
                    + "既不算成功也不算失败",
                "",
                "压成两档的话，「执行器未接通所以一次没跑」会被算成「失败」或「成功」—— 报表会说谎"),
            new AutomationRuleEffectCapability(
                "18.3.2.2",
                "🔴 **成功率**（母 = 成功 + 失败 + 超时）",
                true,
                "SQL 精确 numeric 除法 + round(...,6)；分母为 0 时返回 null（**不是 0%**）；"
                    + "跳过与未执行**不进母**",
                "",
                "「没有样本」与「成功率是零」是两件事 —— 前者返回 null，后者才返回 0"),
            new AutomationRuleEffectCapability(
                "18.3.2.2",
                "🔴 **平均耗时**（整数毫秒）",
                true,
                "duration_ms 是 bigint；平均值只在**真正跑过**的 run 上算"
                    + "（成功 + 失败 + 超时），跳过 / 未执行的 0 不参与",
                "",
                "把「没跑过」的 0 一起平均进去，会让平均耗时显示成 0 —— 报表会说这条规则跑得飞快"),
            new AutomationRuleEffectCapability(
                "18.3.2.2",
                "🔴 **节省工时预估（带口径）**",
                true,
                "口径落在 platform_automation_rule_effect_baseline.manual_minutes_per_run"
                    + "（租户自己登记）；公式 = 成功执行次数 × 该规则的人工基线分钟数；"
                    + "口径（表 / 字段 / 公式 / 排除项）随报表一起返回",
                "",
                "🔴 **不许硬编码「一次省 3 分钟」当数据源** —— 那是 mock；"
                    + "没有口径的规则，节省工时是**空**（不是 0）"),
            new AutomationRuleEffectCapability(
                "18.3.2.2",
                "🔴 **误触发反馈**（能定位到规则与执行）",
                true,
                "platform_automation_rule_effect_feedback 表；每条反馈带 rule_id"
                    + "（真 FK）+ 可选 run_id（真 FK + 触发器钉死「run 必须属于同一条规则」）",
                "",
                "蓝图要求「误触发率高的规则要能快速定位」—— 没有 rule_id / run_id 就定位不了"),
            new AutomationRuleEffectCapability(
                "18.3.2.2",
                "🔴 **规则优化建议**（带依据 + 可定位）",
                true,
                "platform_automation_rule_effect_advice 表；建议类型受控、severity 分级、"
                    + "evidence 存算这条建议用的数字、target_ref 指向规则档案里那条规则",
                "",
                "没有依据的建议是空话（「建议优化一下」谁都会说）"),
            new AutomationRuleEffectCapability(
                "18.3.2.2",
                "🔴 **报表可下钻**（每个聚合数字都追得到来源 run）",
                true,
                "GET /platform/automation/effect/runs 真分页下钻（可按规则 / 结论 / 时间筛）；"
                    + "单规则详情带最近执行记录与 run id",
                "",
                "只给总数的话，「成功率 80%」没法追问是哪几次跑的"),
            new AutomationRuleEffectCapability(
                "18.3.2.2",
                "🔴 **规则执行引擎**（真的跑规则、真的落副作用）",
                false,
                "本仓**不存在**规则执行引擎（backend / services 下没有求值器与动作执行器）；"
                    + "18.3.2「执行」尚未交付",
                "18.3.2",
                "🔴 本模块统计的是**记录**不是**真实执行**：现阶段 run 行绝大多数是人工登记"
                    + "或「未执行」的重跑请求，报表里的成功率**不能**当成「引擎跑对了多少」"),
            new AutomationRuleEffectCapability(
                "18.3.2.2",
                "上游执行日志（只读聚合）",
                true,
                "platform_automation_rule_run / _run_step（18.3.2.1 拥有）；"
                    + "本模块**只读**、**一个字都不改**它们的结构",
                "",
                "每张事实表只有一个写入所有者 ⇒ 本模块**不写** 18.3.2.1 的任何一张表"),
            new AutomationRuleEffectCapability(
                "18.3.2.2",
                "下游规则档案（**关闭动作归它**）",
                true,
                "platform_automation_rule（18.3.1.1 拥有）+ /platform/automation-rules 端点；"
                    + "本模块只输出「建议关闭 + 规则 id / 名称快照 / 定位引用」",
                "",
                "🔴 **本模块不做关闭动作**：停用规则是 18.3.1.1 的动作，"
                    + "本模块只负责把人**快速带到**那条规则面前"));
    var gaps =
        List.of(
            new AutomationRuleEffectGap(
                "18.3.2.1",
                "执行日志（逐步明细 + 手动重跑）",
                "platform",
                true,
                "上游 = 18.3.2.1",
                "本模块提供**聚合**（效果报表）；逐步明细与重跑在那边 —— "
                    + "本模块的「未执行」这一档几乎全部来自重跑请求"),
            new AutomationRuleEffectGap(
                "18.3.1.1",
                "规则列表（**关闭 / 停用动作归它**）",
                "platform",
                true,
                "下游 = 18.3.1.1",
                "本模块输出「建议关闭 + 可定位入口」（规则 id / 名称快照 / 定位引用）；"
                    + "**真正的停用动作在 18.3.1.1** —— 本模块**不写**它的表"),
            new AutomationRuleEffectGap(
                "18.3.2",
                "规则执行引擎（真的跑规则）",
                "platform",
                false,
                "下游 = 18.3.2 执行引擎",
                "**尚未交付** ⇒ 本模块统计的是「记录」不是「真实执行」；"
                    + "报表里的成功率不能当成引擎的准确率"));
    return new AutomationRuleEffectCapabilities(capabilities, gaps);
  }

  // ================================================================ 口径说明

  /** 🔴 节省工时预估的口径（**随报表一起返回**）。 */
  public AutomationRuleEffectSavingBasis savingBasis() {
    return new AutomationRuleEffectSavingBasis(
        "platform_automation_rule_effect_baseline",
        "manual_minutes_per_run",
        "节省工时（分钟） = 该规则的**成功执行次数** × 该规则登记的 manual_minutes_per_run",
        "失败 / 跳过 / 超时 / 未执行**都不计**（没跑成的活没省下时间）；"
            + "**没有登记口径的规则不计入合计**（合计里不含「按 0 分钟算」的规则）",
        "分钟",
        "口径由租户自己登记（人工估算 / 实测均值 / 公司口径），"
            + "并写清这个分钟数怎么算出来的；本模块**不预置任何默认分钟数** —— "
            + "预置一个「一次省 3 分钟」就是 mock。");
  }

  // ================================================================ 概览

  /** 规则效果概览。 */
  public AutomationRuleEffectOverview overview() {
    var totals =
        sql.fetchOne(
                "select t.total_runs, t.succeeded_runs, t.failed_runs, t.skipped_runs,"
                    + " t.timeout_runs, t.not_attempted_runs, t.rated_runs,"
                    + " t.total_duration_ms, t.rated_duration_ms, t.rules_with_runs,"
                    + " round(t.succeeded_runs::numeric / nullif(t.rated_runs,0), 6) as success_rate,"
                    + " case when t.rated_runs > 0"
                    + "      then round(t.rated_duration_ms::numeric / t.rated_runs, 0)::bigint"
                    + "      else 0 end as avg_duration_ms"
                    + " from ("
                    + "   select count(*) as total_runs,"
                    + "     count(*) filter (where outcome='SUCCEEDED') as succeeded_runs,"
                    + "     count(*) filter (where outcome='FAILED') as failed_runs,"
                    + "     count(*) filter (where outcome='SKIPPED') as skipped_runs,"
                    + "     count(*) filter (where outcome='TIMEOUT') as timeout_runs,"
                    + "     count(*) filter (where outcome='NOT_ATTEMPTED') as not_attempted_runs,"
                    + "     count(*) filter (where outcome in ('SUCCEEDED','FAILED','TIMEOUT'))"
                    + "       as rated_runs,"
                    + "     coalesce(sum(duration_ms),0) as total_duration_ms,"
                    + "     coalesce(sum(duration_ms) filter"
                    + "       (where outcome in ('SUCCEEDED','FAILED','TIMEOUT')),0) as rated_duration_ms,"
                    + "     count(distinct rule_id) as rules_with_runs"
                    + "   from platform_automation_rule_run) t");

    var feedback =
        sql.fetchOne(
                "select count(*) as feedbacks,"
                    + " count(*) filter (where f.run_id is null) as unlinked,"
                    + " count(distinct f.run_id) filter"
                    + "   (where r.outcome in ('SUCCEEDED','FAILED','TIMEOUT')) as false_trigger_runs"
                    + " from platform_automation_rule_effect_feedback f"
                    + " left join platform_automation_rule_run r"
                    + "   on r.tenant_id = f.tenant_id and r.id = f.run_id");

    var baseline =
        sql.fetchOne(
                "select count(*) as rules_with_baseline"
                    + " from platform_automation_rule_effect_baseline b"
                    + " join (select distinct rule_id from platform_automation_rule_run) x"
                    + "   on x.rule_id = b.rule_id");

    // 🔴 **不要 coalesce(..., 0)**：没有一条规则登记过口径 ⇒ sum 是 NULL ⇒ 合计是**空**。
    //   「按 0 分钟算」会把「从没评估过」说成「评估结果是省了 0 分钟」——
    //   报表会说谎。而「有口径但一次都没成功」的规则，它的行存在 ⇒ sum = 0，
    //   那个 0 是**真的 0**（口径在、成功次数为 0），不是「没口径」。
    var saved =
        sql.fetchOne(
                "select sum(x.succeeded_runs::numeric * b.manual_minutes_per_run)"
                    + " from (select rule_id,"
                    + "         count(*) filter (where outcome='SUCCEEDED') as succeeded_runs"
                    + "       from platform_automation_rule_run group by rule_id) x"
                    + " join platform_automation_rule_effect_baseline b on b.rule_id = x.rule_id")
            .get(0, BigDecimal.class);

    var advice =
        sql.fetchOne(
                "select count(*) filter (where status='OPEN' and active) as open_advices,"
                    + " count(*) filter (where status='OPEN' and active and severity='CRITICAL')"
                    + "   as critical_advices"
                    + " from platform_automation_rule_effect_advice");

    var recentRunIds =
        sql.fetch("select id from platform_automation_rule_run order by created_at desc, id desc"
                + " limit ?", MAX_DRILLDOWN)
            .map(r -> r.get(0, UUID.class));

    var totalRuns = totals.get("total_runs", Long.class);
    var succeeded = totals.get("succeeded_runs", Long.class);
    var failed = totals.get("failed_runs", Long.class);
    var skipped = totals.get("skipped_runs", Long.class);
    var timeout = totals.get("timeout_runs", Long.class);
    var notAttempted = totals.get("not_attempted_runs", Long.class);
    var rated = totals.get("rated_runs", Long.class);
    var rulesWithRuns = totals.get("rules_with_runs", Long.class);
    var rulesWithBaseline = baseline.get("rules_with_baseline", Long.class);
    var falseTriggerRuns = feedback.get("false_trigger_runs", Long.class);

    return new AutomationRuleEffectOverview(
        totalRuns,
        succeeded,
        failed,
        skipped,
        timeout,
        notAttempted,
        rated,
        totals.get("success_rate", BigDecimal.class),
        totals.get("total_duration_ms", Long.class),
        totals.get("rated_duration_ms", Long.class),
        totals.get("avg_duration_ms", Long.class),
        rulesWithRuns,
        rulesWithBaseline,
        Math.max(0L, rulesWithRuns - rulesWithBaseline),
        saved,
        savingBasis(),
        feedback.get("feedbacks", Long.class),
        falseTriggerRuns,
        feedback.get("unlinked", Long.class),
        rate(falseTriggerRuns, rated),
        advice.get("open_advices", Long.class),
        advice.get("critical_advices", Long.class),
        recentRunIds,
        dbNow());
  }

  // ================================================================ 按规则的效果明细

  /**
   * 按规则的效果明细（**真分页 + 真筛选 + 真排序**）。
   *
   * <p>🔴 聚合只读 18.3.2.1 的执行台账；口径 / 反馈 / 建议读本模块自己的三张表。
   */
  public AutomationRuleEffectRulePage rules(
      String keyword,
      Boolean onlyFalseTrigger,
      Boolean onlyWithAdvice,
      String falseTriggerRateAtLeast,
      String adviseDisableOnly,
      String order,
      int page,
      int size) {
    var size2 = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    var page2 = Math.max(page, 0);

    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (keyword != null && !keyword.isBlank()) {
      where.append(" and m.rule_name ilike ?");
      params.add("%" + keyword.trim() + "%");
    }
    if (Boolean.TRUE.equals(onlyFalseTrigger)) {
      where.append(" and m.false_trigger_runs > 0");
    }
    if (Boolean.TRUE.equals(onlyWithAdvice)) {
      where.append(" and m.open_advices > 0");
    }
    if (falseTriggerRateAtLeast != null && !falseTriggerRateAtLeast.isBlank()) {
      var threshold = parseRate(falseTriggerRateAtLeast, "误触发率门槛");
      where.append(" and m.false_trigger_rate is not null and m.false_trigger_rate >= ?");
      params.add(threshold);
    }
    if (Boolean.TRUE.equals(adviseDisableOnly)) {
      where.append(" and m.false_trigger_advices > 0");
    }

    var total =
        sql.fetchOne(RULE_AGGREGATE_SQL + "select count(*) from m" + where, params.toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
                RULE_AGGREGATE_SQL
                    + "select * from m"
                    + where
                    + orderClause(order)
                    + " limit ? offset ?",
                pageParams(params, size2, page2))
            .map(AutomationRuleEffectService::toRuleRow);
    return new AutomationRuleEffectRulePage(rows, page2, size2, total);
  }

  /** 单条规则的效果详情（聚合 + 口径 + 反馈 + 建议 + 可下钻的执行记录）。 */
  public AutomationRuleEffectRuleDetail ruleDetail(UUID ruleId) {
    var row =
        sql.fetchOne(
                RULE_AGGREGATE_SQL + "select * from m where m.rule_id = ?", ruleId);
    if (row == null) notFound("没有这条规则的执行记录，暂无效果可统计");
    var rule = toRuleRow(row);

    var baseline = baselineOf(ruleId);
    var feedbacks =
        sql.fetch(
                "select id, rule_id, rule_name, run_id, false_trigger_type, note, status,"
                    + " reported_by, created_at"
                    + " from platform_automation_rule_effect_feedback where rule_id = ?"
                    + " order by created_at desc, id desc",
                ruleId)
            .map(AutomationRuleEffectService::toFeedbackRow);
    var advices =
        sql.fetch(
                "select id, rule_id, rule_name, advice_type, severity, title, detail, evidence::text,"
                    + " source, status, active, target_module, target_ref, created_at, updated_at"
                    + " from platform_automation_rule_effect_advice where rule_id = ?"
                    + " order by active desc,"
                    + "   case severity when 'CRITICAL' then 1 when 'WARNING' then 2 else 3 end,"
                    + "   created_at desc, id desc",
                ruleId)
            .map(AutomationRuleEffectService::toAdviceRow);
    var recentRuns =
        sql.fetch(
                "select id, event_id, outcome, duration_ms, side_effects_applied, dry_run,"
                    + " failure_reason, created_at"
                    + " from platform_automation_rule_run where rule_id = ?"
                    + " order by created_at desc, id desc limit ?",
                ruleId, MAX_DRILLDOWN)
            .map(AutomationRuleEffectService::toRunRef);
    var runCount =
        sql.fetchOne("select count(*) from platform_automation_rule_run where rule_id = ?", ruleId)
            .get(0, Long.class);

    return new AutomationRuleEffectRuleDetail(
        rule, baseline, feedbacks, advices, recentRuns, runCount);
  }

  // ================================================================ 执行记录下钻

  /** 执行记录下钻（**真分页 + 真筛选**）—— 报表里的每个数字都能追到这里。 */
  public AutomationRuleEffectRunPage runs(
      UUID ruleId, String outcome, String keyword, String from, String to, int page, int size) {
    var size2 = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    var page2 = Math.max(page, 0);

    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (ruleId != null) {
      where.append(" and rule_id = ?");
      params.add(ruleId);
    }
    if (outcome != null && !outcome.isBlank()) {
      var parsed = AutomationRuleRunOutcome.parse(outcome);
      check(parsed != null, "执行结论只能是 SUCCEEDED / FAILED / SKIPPED / TIMEOUT / NOT_ATTEMPTED");
      where.append(" and outcome = ?");
      params.add(parsed.name());
    }
    if (keyword != null && !keyword.isBlank()) {
      where.append(" and (event_id ilike ? or failure_reason ilike ? or rule_name ilike ?)");
      var like = "%" + keyword.trim() + "%";
      params.add(like);
      params.add(like);
      params.add(like);
    }
    var fromAt = parseTime(from, "开始时间");
    if (fromAt != null) {
      where.append(" and created_at >= ?::timestamptz");
      params.add(fromAt);
    }
    var toAt = parseTime(to, "结束时间");
    if (toAt != null) {
      where.append(" and created_at <= ?::timestamptz");
      params.add(toAt);
    }
    if (fromAt != null && toAt != null) {
      check(!toAt.isBefore(fromAt), "结束时间不能早于开始时间");
    }

    var total =
        sql.fetchOne(
                "select count(*) from platform_automation_rule_run" + where, params.toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
                "select id, event_id, outcome, duration_ms, side_effects_applied, dry_run,"
                    + " failure_reason, created_at from platform_automation_rule_run"
                    + where
                    + " order by created_at desc, id desc limit ? offset ?",
                pageParams(params, size2, page2))
            .map(AutomationRuleEffectService::toRunRef);
    return new AutomationRuleEffectRunPage(rows, page2, size2, total);
  }

  // ================================================================ 口径（人工基线）

  /** 口径列表（**真分页**）。 */
  public AutomationRuleEffectBaselinePage baselines(UUID ruleId, int page, int size) {
    var size2 = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    var page2 = Math.max(page, 0);
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (ruleId != null) {
      where.append(" and rule_id = ?");
      params.add(ruleId);
    }
    var total =
        sql.fetchOne(
                "select count(*) from platform_automation_rule_effect_baseline" + where,
                params.toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
                "select id, rule_id, rule_name, manual_minutes_per_run, baseline_source,"
                    + " baseline_note, effective_from, updated_at"
                    + " from platform_automation_rule_effect_baseline"
                    + where
                    + " order by updated_at desc, id desc limit ? offset ?",
                pageParams(params, size2, page2))
            .map(AutomationRuleEffectService::toBaselineRow);
    return new AutomationRuleEffectBaselinePage(rows, page2, size2, total);
  }

  /** 某条规则当前的口径（可空）。 */
  public AutomationRuleEffectBaselineRow baselineOf(UUID ruleId) {
    var row =
        sql.fetchOne(
                "select id, rule_id, rule_name, manual_minutes_per_run, baseline_source,"
                    + " baseline_note, effective_from, updated_at"
                    + " from platform_automation_rule_effect_baseline where rule_id = ?",
                ruleId);
    return row == null ? null : toBaselineRow(row);
  }

  /**
   * 配置 / 更新一条规则的**人工基线**（节省工时预估的口径）。
   *
   * <p>🔴 **口径必须写清来源与算法** —— 只填一个分钟数不说怎么来的，这个数不可用。
   * <p>🔴 **一条规则一条当前口径**：重复提交是 upsert，不会堆出一串历史行。
   */
  public AutomationRuleEffectBaselineRow upsertBaseline(
      AutomationRuleEffectBaselineInput input, AccessService.Access actor) {
    check(input != null, "请求体不能为空");
    check(input.ruleId() != null, "必须指定这条口径属于哪条规则");
    var rule = ruleRef(input.ruleId());

    check(input.manualMinutesPerRun() != null, "必须给出人工基线分钟数");
    var minutes = input.manualMinutesPerRun();
    check(minutes.signum() >= 0, "人工基线分钟数不能为负");
    check(
        minutes.compareTo(new BigDecimal("100000")) <= 0,
        "人工基线分钟数过大（单次执行替代 10 万分钟人工明显不真实）");

    var source =
        AutomationRuleEffectBaselineSource.parse(
            input.baselineSource() == null ? "" : input.baselineSource());
    check(source != null, "口径来源只能是 MANUAL_ESTIMATE / OBSERVED_AVERAGE / POLICY");

    var note = optional(input.baselineNote(), "口径说明", 500);
    check(
        source != AutomationRuleEffectBaselineSource.POLICY || !note.isEmpty(),
        "口径说明必须写清这个分钟数**怎么算出来的**（公司口径也要写明依据）");

    var effectiveFrom = parseTime(input.effectiveFrom(), "口径生效时间");
    var now = dbNow();
    if (effectiveFrom == null) effectiveFrom = now;

    var id =
        sql.fetchOne(
                "insert into platform_automation_rule_effect_baseline(id,tenant_id,rule_id,"
                    + " rule_name,manual_minutes_per_run,baseline_source,baseline_note,"
                    + " effective_from,created_by,created_at,updated_at)"
                    + " values(?,?,?,?,?,?,?,?::timestamptz,?,?::timestamptz,?::timestamptz)"
                    + " on conflict (tenant_id, rule_id) do update"
                    + " set manual_minutes_per_run = excluded.manual_minutes_per_run,"
                    + "     baseline_source = excluded.baseline_source,"
                    + "     baseline_note = excluded.baseline_note,"
                    + "     rule_name = excluded.rule_name,"
                    + "     effective_from = excluded.effective_from,"
                    + "     updated_at = excluded.updated_at"
                    + " returning id",
                UUID.randomUUID(),
                currentTenant(),
                rule.id(),
                rule.name(),
                minutes,
                source.name(),
                note,
                effectiveFrom,
                actor.userId(),
                now,
                now)
            .get(0, UUID.class);
    check(id != null, "口径写入失败");
    return baselineOf(rule.id());
  }

  // ================================================================ 误触发反馈

  /** 误触发反馈列表（**真分页 + 真筛选**）。 */
  public AutomationRuleEffectFeedbackPage feedbacks(
      UUID ruleId, String status, String falseTriggerType, String keyword, int page, int size) {
    var size2 = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    var page2 = Math.max(page, 0);
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (ruleId != null) {
      where.append(" and rule_id = ?");
      params.add(ruleId);
    }
    if (status != null && !status.isBlank()) {
      var parsed = AutomationRuleEffectFeedbackStatus.parse(status);
      check(parsed != null, "反馈状态只能是 OPEN / ACCEPTED / DISMISSED");
      where.append(" and status = ?");
      params.add(parsed.name());
    }
    if (falseTriggerType != null && !falseTriggerType.isBlank()) {
      var parsed = AutomationRuleEffectFalseTriggerType.parse(falseTriggerType);
      check(
          parsed != null,
          "误触发分类只能是 WRONG_CONDITION / WRONG_SCOPE / TOO_FREQUENT /"
              + " UNEXPECTED_ACTION / OTHER");
      where.append(" and false_trigger_type = ?");
      params.add(parsed.name());
    }
    if (keyword != null && !keyword.isBlank()) {
      where.append(" and (rule_name ilike ? or note ilike ?)");
      var like = "%" + keyword.trim() + "%";
      params.add(like);
      params.add(like);
    }
    var total =
        sql.fetchOne(
                "select count(*) from platform_automation_rule_effect_feedback" + where,
                params.toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
                "select id, rule_id, rule_name, run_id, false_trigger_type, note, status,"
                    + " reported_by, created_at from platform_automation_rule_effect_feedback"
                    + where
                    + " order by created_at desc, id desc limit ? offset ?",
                pageParams(params, size2, page2))
            .map(AutomationRuleEffectService::toFeedbackRow);
    return new AutomationRuleEffectFeedbackPage(rows, page2, size2, total);
  }

  /**
   * 登记一条**误触发反馈**。
   *
   * <p>🔴 反馈必须能**定位**：带 {@code ruleId}（必填）；带 {@code runId} 时会校验
   * 那次执行**确实属于这条规则**（否则反馈会算到别的规则头上）。
   */
  public AutomationRuleEffectFeedbackRow recordFeedback(
      AutomationRuleEffectFeedbackInput input, AccessService.Access actor) {
    check(input != null, "请求体不能为空");
    check(input.ruleId() != null, "必须指定这条反馈说的是哪条规则");
    var rule = ruleRef(input.ruleId());

    var type =
        AutomationRuleEffectFalseTriggerType.parse(
            input.falseTriggerType() == null ? "" : input.falseTriggerType());
    check(
        type != null,
        "误触发分类只能是 WRONG_CONDITION / WRONG_SCOPE / TOO_FREQUENT / UNEXPECTED_ACTION / OTHER");

    var note = optional(input.note(), "反馈说明", 500);
    check(
        type != AutomationRuleEffectFalseTriggerType.OTHER || !note.isEmpty(),
        "「其它」类反馈必须写清说明 —— 否则这条反馈没有可执行的信息");

    if (input.runId() != null) {
      var runRule =
          sql.fetchOne(
                  "select rule_id from platform_automation_rule_run where id = ?", input.runId());
      if (runRule == null) notFound("指向的执行记录不存在");
      check(
          rule.id().equals(runRule.get("rule_id", UUID.class)),
          "指向的执行记录不属于这条规则 —— 反馈会算到别的规则头上");
    }

    var now = dbNow();
    var id =
        UUID.randomUUID();
    sql.execute(
        "insert into platform_automation_rule_effect_feedback(id,tenant_id,rule_id,rule_name,"
            + " run_id,false_trigger_type,note,status,reported_by,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,'OPEN',?,?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        rule.id(),
        rule.name(),
        input.runId(),
        type.name(),
        note,
        actor.userId(),
        now,
        now);

    var row =
        sql.fetchOne(
                "select id, rule_id, rule_name, run_id, false_trigger_type, note, status,"
                    + " reported_by, created_at from platform_automation_rule_effect_feedback"
                    + " where id = ?",
                id);
    check(row != null, "误触发反馈写入后读不回来");
    return toFeedbackRow(row);
  }

  /**
   * **处理一条误触发反馈**（把它标成「已采纳」或「已忽略」）。
   *
   * <p>🔴 反馈**不是只追加台账** —— 它会被人处理掉，处理结果要能落下来。
   * <p>🔴 **状态与「活跃」是两件事**：{@code status} 是**人的决定**
   * （待处理 / 已采纳 / 已忽略），本方法**只改**它；误触发率的分母与分子
   * **不看 status** —— 一条被「已忽略」的反馈仍然是发生过的误触发，
   * 把它从误触发率里抹掉等于让报表说谎。
   */
  public AutomationRuleEffectFeedbackRow setFeedbackStatus(UUID id, String status) {
    var parsed = AutomationRuleEffectFeedbackStatus.parse(status == null ? "" : status);
    check(parsed != null, "反馈状态只能是 OPEN / ACCEPTED / DISMISSED");

    var affected =
        sql.execute(
            "update platform_automation_rule_effect_feedback set status = ?,"
                + " updated_at = ?::timestamptz where id = ?",
            parsed.name(),
            dbNow(),
            id);
    check(affected > 0, "误触发反馈不存在");

    var row =
        sql.fetchOne(
                "select id, rule_id, rule_name, run_id, false_trigger_type, note, status,"
                    + " reported_by, created_at from platform_automation_rule_effect_feedback"
                    + " where id = ?",
                id);
    check(row != null, "误触发反馈写入后读不回来");
    return toFeedbackRow(row);
  }

  // ================================================================ 优化建议

  /** 优化建议列表（**真分页 + 真筛选**）。 */
  public AutomationRuleEffectAdvicePage advices(
      UUID ruleId,
      String status,
      String adviceType,
      String severity,
      String source,
      Boolean onlyActive,
      String keyword,
      int page,
      int size) {
    var size2 = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    var page2 = Math.max(page, 0);
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (ruleId != null) {
      where.append(" and rule_id = ?");
      params.add(ruleId);
    }
    if (status != null && !status.isBlank()) {
      var parsed = AutomationRuleEffectAdviceStatus.parse(status);
      check(parsed != null, "建议状态只能是 OPEN / ADOPTED / IGNORED");
      where.append(" and status = ?");
      params.add(parsed.name());
    }
    if (adviceType != null && !adviceType.isBlank()) {
      var parsed = AutomationRuleEffectAdviceType.parse(adviceType);
      check(parsed != null, "建议类型不合法");
      where.append(" and advice_type = ?");
      params.add(parsed.name());
    }
    if (severity != null && !severity.isBlank()) {
      var parsed = AutomationRuleEffectSeverity.parse(severity);
      check(parsed != null, "严重度只能是 CRITICAL / WARNING / INFO");
      where.append(" and severity = ?");
      params.add(parsed.name());
    }
    if (source != null && !source.isBlank()) {
      var parsed = AutomationRuleEffectAdviceSource.parse(source);
      check(parsed != null, "建议来源只能是 SYSTEM / MANUAL");
      where.append(" and source = ?");
      params.add(parsed.name());
    }
    if (Boolean.TRUE.equals(onlyActive)) {
      where.append(" and active");
    }
    if (keyword != null && !keyword.isBlank()) {
      where.append(" and (rule_name ilike ? or title ilike ? or detail ilike ?)");
      var like = "%" + keyword.trim() + "%";
      params.add(like);
      params.add(like);
      params.add(like);
    }
    var total =
        sql.fetchOne(
                "select count(*) from platform_automation_rule_effect_advice" + where,
                params.toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
                "select id, rule_id, rule_name, advice_type, severity, title, detail, evidence::text,"
                    + " source, status, active, target_module, target_ref, created_at, updated_at"
                    + " from platform_automation_rule_effect_advice"
                    + where
                    + " order by active desc,"
                    + "   case severity when 'CRITICAL' then 1 when 'WARNING' then 2 else 3 end,"
                    + "   created_at desc, id desc limit ? offset ?",
                pageParams(params, size2, page2))
            .map(AutomationRuleEffectService::toAdviceRow);
    return new AutomationRuleEffectAdvicePage(rows, page2, size2, total);
  }

  /**
   * 登记一条**人工**优化建议。
   *
   * <p>🔴 系统重新生成建议时**不动**人工建议（source = 'MANUAL' 的行）。
   */
  public AutomationRuleEffectAdviceRow recordAdvice(
      AutomationRuleEffectAdviceInput input, AccessService.Access actor) {
    check(input != null, "请求体不能为空");
    check(input.ruleId() != null, "必须指定这条建议针对哪条规则");
    var rule = ruleRef(input.ruleId());

    var type =
        AutomationRuleEffectAdviceType.parse(input.adviceType() == null ? "" : input.adviceType());
    check(type != null, "建议类型不合法");
    var severity =
        AutomationRuleEffectSeverity.parse(input.severity() == null ? "" : input.severity());
    check(severity != null, "严重度只能是 CRITICAL / WARNING / INFO");

    var title = required(input.title(), "建议标题", 120);
    var detail = optional(input.detail(), "建议正文", 1000);

    var now = dbNow();
    var id = UUID.randomUUID();
    sql.execute(
        "insert into platform_automation_rule_effect_advice(id,tenant_id,rule_id,rule_name,"
            + " advice_type,severity,title,detail,evidence,source,status,active,target_module,"
            + " target_ref,created_by,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?::jsonb,'MANUAL','OPEN',true,'18.3.1.1',?,?,"
            + " ?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        rule.id(),
        rule.name(),
        type.name(),
        severity.name(),
        title,
        detail,
        "{}",
        targetRefOf(rule.id()),
        actor.userId(),
        now,
        now);

    var row =
        sql.fetchOne(
                "select id, rule_id, rule_name, advice_type, severity, title, detail, evidence::text,"
                    + " source, status, active, target_module, target_ref, created_at, updated_at"
                    + " from platform_automation_rule_effect_advice where id = ?",
                id);
    return toAdviceRow(row);
  }

  /**
   * **处理一条优化建议**（把它标成「已采纳」或「已忽略」）。
   *
   * <p>🔴 这条端点是**系统建议的处理入口**的另一半：系统重新生成建议时
   * <b>不会</b>覆盖 {@code status}（人的决定不该被重新计算推翻）——
   * 但如果没有人能改 {@code status}，那句话就是空话。
   * <p>🔴 **状态与「仍然成立」是两件事**：{@code status} 是**人的决定**
   * （待处理 / 已采纳 / 已忽略）；{@code active} 是「按当前聚合这条建议还成不成立」。
   * 本方法**只改 status**，不改 {@code active} —— 一条被人忽略的建议
   * 可能按当前聚合仍然成立，那是系统刷新该管的事。
   */
  public AutomationRuleEffectAdviceRow setAdviceStatus(UUID id, String status) {
    var parsed = AutomationRuleEffectAdviceStatus.parse(status == null ? "" : status);
    check(parsed != null, "建议状态只能是 OPEN / ADOPTED / IGNORED");

    var affected =
        sql.execute(
            "update platform_automation_rule_effect_advice set status = ?,"
                + " updated_at = ?::timestamptz where id = ?",
            parsed.name(),
            dbNow(),
            id);
    check(affected > 0, "优化建议不存在");

    var row =
        sql.fetchOne(
                "select id, rule_id, rule_name, advice_type, severity, title, detail, evidence::text,"
                    + " source, status, active, target_module, target_ref, created_at, updated_at"
                    + " from platform_automation_rule_effect_advice where id = ?",
                id);
    check(row != null, "优化建议写入后读不回来");
    return toAdviceRow(row);
  }

  /**
   * 依**当前聚合**重新生成系统建议（**幂等**：同一条规则 + 同一个建议类型 ⇒ 至多一条）。
   *
   * <p>🔴 已经被人处理过（ADOPTED / IGNORED）的建议**不会被覆盖状态** ——
   * 系统重新算一遍不該把人的决定推翻。
   * <p>🔴 不再成立的系统建议被置成 {@code active = false}（**不删**：历史建议要能复现），
   * 但不再出现在「当前建议」里。
   */
  public AutomationRuleEffectAdviceRefreshResult refreshAdvices(AccessService.Access actor) {
    var now = dbNow();
    var rows =
        sql.fetch(RULE_AGGREGATE_SQL + "select * from m order by m.rule_id")
            .map(AutomationRuleEffectService::toRuleRow);

    var created = 0;
    var updated = 0;
    var deactivated = 0;

    for (var rule : rows) {
      var drafts = adviceDrafts(rule);
      var wanted = drafts.stream().map(d -> d.type().name()).collect(java.util.stream.Collectors.toSet());

      for (var draft : drafts) {
        var existing =
            sql.fetchOne(
                    "select id from platform_automation_rule_effect_advice"
                        + " where rule_id = ? and advice_type = ? and source = 'SYSTEM'",
                    rule.ruleId(), draft.type().name());
        if (existing == null) {
          sql.execute(
              "insert into platform_automation_rule_effect_advice(id,tenant_id,rule_id,rule_name,"
                  + " advice_type,severity,title,detail,evidence,source,status,active,target_module,"
                  + " target_ref,created_by,created_at,updated_at)"
                  + " values(?,?,?,?,?,?,?,?,?::jsonb,'SYSTEM','OPEN',true,'18.3.1.1',?,?,"
                  + " ?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              currentTenant(),
              rule.ruleId(),
              rule.ruleName(),
              draft.type().name(),
              draft.severity().name(),
              draft.title(),
              draft.detail(),
              draft.evidence(),
              targetRefOf(rule.ruleId()),
              actor.userId(),
              now,
              now);
          created++;
        } else {
          sql.execute(
              "update platform_automation_rule_effect_advice"
                  + " set severity = ?, title = ?, detail = ?, evidence = ?::jsonb, active = true,"
                  + "     rule_name = ?, target_ref = ?, updated_at = ?::timestamptz"
                  + " where id = ?",
              draft.severity().name(),
              draft.title(),
              draft.detail(),
              draft.evidence(),
              rule.ruleName(),
              targetRefOf(rule.ruleId()),
              now,
              existing.get("id", UUID.class));
          updated++;
        }
      }

      // 不再成立的系统建议：置成 active = false（不删）。
      var stale =
          sql.fetch(
                  "select id, advice_type from platform_automation_rule_effect_advice"
                      + " where rule_id = ? and source = 'SYSTEM' and active",
                  rule.ruleId());
      for (var row : stale) {
        if (wanted.contains(row.get("advice_type", String.class))) continue;
        sql.execute(
            "update platform_automation_rule_effect_advice"
                + " set active = false, updated_at = ?::timestamptz where id = ?",
            now, row.get("id", UUID.class));
        deactivated++;
      }
    }

    return new AutomationRuleEffectAdviceRefreshResult(rows.size(), created, updated, deactivated, now);
  }

  /** 一条建议的草稿（生成时用）。 */
  private record AdviceDraft(
      AutomationRuleEffectAdviceType type,
      AutomationRuleEffectSeverity severity,
      String title,
      String detail,
      String evidence) {}

  /** 依据当前聚合，为一条规则算出它应该有哪些建议。 */
  private List<AdviceDraft> adviceDrafts(AutomationRuleEffectRuleRow rule) {
    var drafts = new ArrayList<AdviceDraft>();
    var rated = rule.ratedRuns();
    var successRate = rule.successRate();
    var falseTriggerRate = rule.falseTriggerRate();

    if (rule.falseTriggerRuns() > 0
        && falseTriggerRate != null
        && falseTriggerRate.compareTo(FALSE_TRIGGER_RATE_THRESHOLD) >= 0) {
      var evidence =
          evidence(
              Map.of(
                  "falseTriggerRuns", rule.falseTriggerRuns(),
                  "ratedRuns", rated,
                  "falseTriggerRate", falseTriggerRate,
                  "threshold", FALSE_TRIGGER_RATE_THRESHOLD));
      drafts.add(
          new AdviceDraft(
              AutomationRuleEffectAdviceType.FALSE_TRIGGER_HIGH,
              AutomationRuleEffectSeverity.CRITICAL,
              "建议关闭或收紧条件",
              "有效执行里有 "
                  + rule.falseTriggerRuns()
                  + " 次被登记为误触发（误触发率 "
                  + falseTriggerRate.stripTrailingZeros().toPlainString()
                  + "，门槛 "
                  + FALSE_TRIGGER_RATE_THRESHOLD.stripTrailingZeros().toPlainString()
                  + "）。自动化失控比没有自动化更危险 —— 请到规则档案里**停用**或收紧条件。"
                  + "⚠️ **关闭动作归 18.3.1.1 规则档案**，本模块只提供定位入口，不代为关闭。",
              evidence));
    }

    if (rated > 0 && rule.failedRuns() > 0) {
      var failureRate = rate(rule.failedRuns(), rated);
      if (failureRate.compareTo(FAILURE_RATE_THRESHOLD) >= 0) {
        drafts.add(
            new AdviceDraft(
                AutomationRuleEffectAdviceType.HIGH_FAILURE_RATE,
                AutomationRuleEffectSeverity.WARNING,
                "失败率偏高，建议排查动作与条件",
                "成功 "
                    + rule.succeededRuns()
                    + " / 失败 "
                    + rule.failedRuns()
                    + " / 超时 "
                    + rule.timeoutRuns()
                    + "（有效执行 "
                    + rated
                    + " 次，失败率 "
                    + failureRate.stripTrailingZeros().toPlainString()
                    + "）。请在 18.3.2.1 执行日志里按结论=失败筛出具体那几次，"
                    + "再从逐步明细定位卡在第几步。",
                evidence(
                    Map.of(
                        "failedRuns", rule.failedRuns(),
                        "ratedRuns", rated,
                        "failureRate", failureRate,
                        "threshold", FAILURE_RATE_THRESHOLD))));
      }
    }

    if (rated > 0 && rule.timeoutRuns() > 0) {
      var timeoutRate = rate(rule.timeoutRuns(), rated);
      if (timeoutRate.compareTo(TIMEOUT_RATE_THRESHOLD) >= 0) {
        drafts.add(
            new AdviceDraft(
                AutomationRuleEffectAdviceType.TIMEOUT_HIGH,
                AutomationRuleEffectSeverity.WARNING,
                "超时偏多，建议调阈值或拆分动作",
                "有效执行里有 "
                    + rule.timeoutRuns()
                    + " 次超时（占比 "
                    + timeoutRate.stripTrailingZeros().toPlainString()
                    + "）。动作太多或目标太慢都会这样。",
                evidence(
                    Map.of(
                        "timeoutRuns", rule.timeoutRuns(),
                        "ratedRuns", rated,
                        "timeoutRate", timeoutRate,
                        "threshold", TIMEOUT_RATE_THRESHOLD))));
      }
    }

    if (rule.notAttemptedRuns() > 0) {
      drafts.add(
          new AdviceDraft(
              AutomationRuleEffectAdviceType.NOT_ATTEMPTED_HIGH,
              AutomationRuleEffectSeverity.INFO,
              "大量「未执行」—— 这是执行引擎的缺口，不是规则的错",
              "有 "
                  + rule.notAttemptedRuns()
                  + " 次结论是「未执行」（执行器未接通，重跑请求都落在这里）。"
                  + "🔴 18.3.2 执行引擎**尚未交付**，这些记录**不代表规则跑失败** —— "
                  + "它们在报表里单列一档，既不算成功也不算失败。",
              evidence(Map.of("notAttemptedRuns", rule.notAttemptedRuns()))));
    }

    if (rule.totalRuns() > 0 && rule.skippedRuns() > 0) {
      var skippedRate = rate(rule.skippedRuns(), rule.totalRuns());
      if (skippedRate.compareTo(SKIPPED_RATE_THRESHOLD) >= 0) {
        drafts.add(
            new AdviceDraft(
                AutomationRuleEffectAdviceType.SKIPPED_HIGH,
                AutomationRuleEffectSeverity.INFO,
                "大量跳过，条件可能过窄",
                rule.skippedRuns()
                    + " / "
                    + rule.totalRuns()
                    + " 次被跳过（条件未命中 / 规则停用 / 试运行）。跳过是**预期行为**，"
                    + "但占比这么高通常意味着条件写得太窄。",
                evidence(
                    Map.of(
                        "skippedRuns", rule.skippedRuns(),
                        "totalRuns", rule.totalRuns(),
                        "skippedRate", skippedRate,
                        "threshold", SKIPPED_RATE_THRESHOLD))));
      }
    }

    if (rated > 0 && rule.avgDurationMs() >= SLOW_DURATION_MS) {
      drafts.add(
          new AdviceDraft(
              AutomationRuleEffectAdviceType.SLOW,
              AutomationRuleEffectSeverity.WARNING,
              "平均耗时偏长",
              "有效执行上的平均耗时是 "
                  + rule.avgDurationMs()
                  + " 毫秒（门槛 "
                  + SLOW_DURATION_MS
                  + " 毫秒）。动作步骤的耗时可以在 18.3.2.1 的逐步明细里逐条看。",
              evidence(
                  Map.of(
                      "avgDurationMs", rule.avgDurationMs(),
                      "ratedRuns", rated,
                      "thresholdMs", SLOW_DURATION_MS))));
    }

    if (rule.manualMinutesPerRun() == null) {
      drafts.add(
          new AdviceDraft(
              AutomationRuleEffectAdviceType.NO_BASELINE,
              AutomationRuleEffectSeverity.INFO,
              "缺少人工基线口径 —— 节省工时预估算不出来",
              "这条规则还没登记「一次成功执行替代多少分钟人工」，所以它在节省工时合计里"
                  + "**不计入**（而不是按 0 分钟算）。请在「节省工时口径」里补一条，"
                  + "并写清这个分钟数是怎么估出来的。",
              evidence(Map.of("ruleId", rule.ruleId().toString()))));
    }

    if (rated < INSUFFICIENT_DATA_MIN_RATED) {
      drafts.add(
          new AdviceDraft(
              AutomationRuleEffectAdviceType.INSUFFICIENT_DATA,
              AutomationRuleEffectSeverity.INFO,
              "样本太少，结论不可靠",
              "真正跑过（成功 + 失败 + 超时）只有 "
                  + rated
                  + " 次，少于 "
                  + INSUFFICIENT_DATA_MIN_RATED
                  + " 次。现在的成功率与平均耗时**参考价值有限**，别拿它下结论。",
              evidence(Map.of("ratedRuns", rated, "minRatedRuns", INSUFFICIENT_DATA_MIN_RATED))));
    }

    if (rated >= PERFORMING_WELL_MIN_RATED
        && successRate != null
        && successRate.compareTo(PERFORMING_WELL_RATE) >= 0) {
      drafts.add(
          new AdviceDraft(
              AutomationRuleEffectAdviceType.PERFORMING_WELL,
              AutomationRuleEffectSeverity.INFO,
              "表现良好，值得保留",
              "有效执行 "
                  + rated
                  + " 次，成功率 "
                  + successRate.stripTrailingZeros().toPlainString()
                  + "，平均耗时 "
                  + rule.avgDurationMs()
                  + " 毫秒。这条规则可以留着，也可以作为其它规则的模板。",
              evidence(
                  Map.of(
                      "ratedRuns", rated,
                      "successRate", successRate,
                      "avgDurationMs", rule.avgDurationMs()))));
    }

    return drafts;
  }

  // ================================================================ 口径与工具实现

  /** 定位引用：指向 18.3.1.1 规则档案里那条规则（**关闭动作归它**）。 */
  private static String targetRefOf(UUID ruleId) {
    return "/platform/automation-rules/rules/" + ruleId;
  }

  /** 依据 JSON（**没有依据的建议是空话**）。 */
  private String evidence(Map<String, Object> values) {
    var out = new LinkedHashMap<String, Object>();
    out.putAll(values);
    try {
      return json.writeValueAsString(out);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static BigDecimal parseRate(String raw, String label) {
    try {
      var value = new BigDecimal(raw.trim());
      check(value.signum() >= 0 && value.compareTo(BigDecimal.ONE) <= 0, label + "必须在 0 到 1 之间");
      return value.setScale(RATE_SCALE, RoundingMode.HALF_UP);
    } catch (NumberFormatException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "必须是一个 0 到 1 之间的数字");
    }
  }

  private static Object[] pageParams(List<Object> params, int size, int page) {
    var all = new ArrayList<>(params);
    all.add(size);
    all.add((long) page * size);
    return all.toArray();
  }

  /** 排序（**全序**：末尾一定跟 rule_id ⇒ 同值次序确定，不靠 DB 返回顺序）。 */
  private static String orderClause(String order) {
    var key = order == null ? "" : order.trim().toLowerCase(Locale.ROOT);
    return switch (key) {
      case "lastrunat" -> " order by m.last_run_at desc nulls last, m.rule_id";
      case "successrate" -> " order by m.success_rate desc nulls last, m.rule_id";
      case "avgdurationms" -> " order by m.avg_duration_ms desc, m.rule_id";
      case "falsetriggerrate" -> " order by m.false_trigger_rate desc nulls last, m.rule_id";
      case "savedminutes" -> " order by m.saved_minutes desc nulls last, m.rule_id";
      case "totalruns" -> " order by m.total_runs desc, m.rule_id";
      default -> " order by m.total_runs desc, m.rule_id";
    };
  }

  // ================================================================ 映射

  private static AutomationRuleEffectRuleRow toRuleRow(org.jooq.Record row) {
    var successRate = row.get("success_rate", BigDecimal.class);
    var falseTriggerRate = row.get("false_trigger_rate", BigDecimal.class);
    var baseline = row.get("manual_minutes_per_run", BigDecimal.class);
    var falseTriggerRuns = row.get("false_trigger_runs", Long.class);
    var rated = row.get("rated_runs", Long.class);
    var adviseDisable = row.get("false_trigger_advices", Long.class) > 0;
    var reason =
        adviseDisable
            ? "有效执行里有 "
                + falseTriggerRuns
                + " 次被登记为误触发（误触发率 "
                + (falseTriggerRate == null
                    ? "—"
                    : falseTriggerRate.stripTrailingZeros().toPlainString())
                + "）—— 建议到规则档案里停用或收紧条件（关闭动作归 18.3.1.1，本模块不做）"
            : "";
    return new AutomationRuleEffectRuleRow(
        row.get("rule_id", UUID.class),
        row.get("rule_name", String.class),
        row.get("trigger_event_type", String.class),
        row.get("total_runs", Long.class),
        row.get("succeeded_runs", Long.class),
        row.get("failed_runs", Long.class),
        row.get("skipped_runs", Long.class),
        row.get("timeout_runs", Long.class),
        row.get("not_attempted_runs", Long.class),
        rated,
        successRate,
        row.get("total_duration_ms", Long.class),
        row.get("rated_duration_ms", Long.class),
        row.get("avg_duration_ms", Long.class),
        row.get("false_trigger_feedbacks", Long.class),
        row.get("unlinked_feedbacks", Long.class),
        falseTriggerRuns,
        falseTriggerRate,
        baseline,
        row.get("baseline_source", String.class),
        row.get("saved_minutes", BigDecimal.class),
        row.get("open_advices", Long.class),
        adviseDisable,
        reason,
        row.get("last_run_at", OffsetDateTime.class),
        row.get("last_run_id", UUID.class));
  }

  private static AutomationRuleEffectRunRef toRunRef(org.jooq.Record row) {
    var outcome = AutomationRuleRunOutcome.parse(row.get("outcome", String.class));
    return new AutomationRuleEffectRunRef(
        row.get("id", UUID.class),
        row.get("event_id", String.class),
        row.get("outcome", String.class),
        outcome == null ? row.get("outcome", String.class) : outcome.label(),
        row.get("duration_ms", Long.class),
        row.get("side_effects_applied", Boolean.class),
        row.get("dry_run", Boolean.class),
        row.get("failure_reason", String.class),
        row.get("created_at", OffsetDateTime.class));
  }

  private static AutomationRuleEffectBaselineRow toBaselineRow(org.jooq.Record row) {
    return new AutomationRuleEffectBaselineRow(
        row.get("id", UUID.class),
        row.get("rule_id", UUID.class),
        row.get("rule_name", String.class),
        row.get("manual_minutes_per_run", BigDecimal.class),
        row.get("baseline_source", String.class),
        row.get("baseline_note", String.class),
        row.get("effective_from", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  private static AutomationRuleEffectFeedbackRow toFeedbackRow(org.jooq.Record row) {
    var type =
        AutomationRuleEffectFalseTriggerType.parse(row.get("false_trigger_type", String.class));
    var status = AutomationRuleEffectFeedbackStatus.parse(row.get("status", String.class));
    return new AutomationRuleEffectFeedbackRow(
        row.get("id", UUID.class),
        row.get("rule_id", UUID.class),
        row.get("rule_name", String.class),
        row.get("run_id", UUID.class),
        row.get("false_trigger_type", String.class),
        type == null ? row.get("false_trigger_type", String.class) : type.label(),
        row.get("note", String.class),
        row.get("status", String.class),
        status == null ? row.get("status", String.class) : status.label(),
        row.get("reported_by", UUID.class),
        row.get("created_at", OffsetDateTime.class));
  }

  private static AutomationRuleEffectAdviceRow toAdviceRow(org.jooq.Record row) {
    var type = AutomationRuleEffectAdviceType.parse(row.get("advice_type", String.class));
    var severity = AutomationRuleEffectSeverity.parse(row.get("severity", String.class));
    var status = AutomationRuleEffectAdviceStatus.parse(row.get("status", String.class));
    return new AutomationRuleEffectAdviceRow(
        row.get("id", UUID.class),
        row.get("rule_id", UUID.class),
        row.get("rule_name", String.class),
        row.get("advice_type", String.class),
        type == null ? row.get("advice_type", String.class) : type.label(),
        row.get("severity", String.class),
        severity == null ? row.get("severity", String.class) : severity.label(),
        row.get("title", String.class),
        row.get("detail", String.class),
        row.get("evidence", String.class),
        row.get("source", String.class),
        row.get("status", String.class),
        status == null ? row.get("status", String.class) : status.label(),
        row.get("active", Boolean.class),
        row.get("target_module", String.class),
        row.get("target_ref", String.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  // ==========================================================================
  // 聚合 SQL（**只读** 18.3.2.1 的执行台账 + 本模块自己的三张表）
  // ==========================================================================
  //
  // 🔴 **逐步明细（_step）不参与聚合**：逐步明细是「一次执行内部」的粒度，
  //    混进来会把执行次数放大好几倍，成功率就成了假的。
  //
  // 🔴 **比率一律用精确 numeric 除法 + round(...,6)**，不是 double。
  // 🔴 **「未执行」既不算成功也不算失败**，也不进成功率的母。
  private static final String RULE_AGGREGATE_SQL =
      "with agg as ("
          + "  select r.rule_id,"
          + "    (array_agg(r.rule_name order by r.created_at desc, r.id desc))[1] as rule_name,"
          + "    (array_agg(r.trigger_event_type order by r.created_at desc, r.id desc))[1]"
          + "      as trigger_event_type,"
          + "    count(*) as total_runs,"
          + "    count(*) filter (where r.outcome='SUCCEEDED') as succeeded_runs,"
          + "    count(*) filter (where r.outcome='FAILED') as failed_runs,"
          + "    count(*) filter (where r.outcome='SKIPPED') as skipped_runs,"
          + "    count(*) filter (where r.outcome='TIMEOUT') as timeout_runs,"
          + "    count(*) filter (where r.outcome='NOT_ATTEMPTED') as not_attempted_runs,"
          + "    count(*) filter (where r.outcome in ('SUCCEEDED','FAILED','TIMEOUT'))"
          + "      as rated_runs,"
          + "    coalesce(sum(r.duration_ms),0) as total_duration_ms,"
          + "    coalesce(sum(r.duration_ms) filter"
          + "      (where r.outcome in ('SUCCEEDED','FAILED','TIMEOUT')),0) as rated_duration_ms,"
          + "    max(r.created_at) as last_run_at,"
          + "    (array_agg(r.id order by r.created_at desc, r.id desc))[1] as last_run_id"
          + "  from platform_automation_rule_run r group by r.rule_id"
          + "), fb as ("
          + "  select f.rule_id, count(*) as feedbacks,"
          + "    count(*) filter (where f.run_id is null) as unlinked_feedbacks,"
          + "    count(distinct f.run_id) filter"
          + "      (where r.outcome in ('SUCCEEDED','FAILED','TIMEOUT')) as false_trigger_runs"
          + "  from platform_automation_rule_effect_feedback f"
          + "  left join platform_automation_rule_run r"
          + "    on r.tenant_id = f.tenant_id and r.id = f.run_id"
          + "  group by f.rule_id"
          + "), bl as ("
          + "  select b.rule_id, b.manual_minutes_per_run, b.baseline_source"
          + "  from platform_automation_rule_effect_baseline b"
          + "), ad as ("
          + "  select a.rule_id,"
          + "    count(*) filter (where a.status='OPEN' and a.active) as open_advices,"
          + "    count(*) filter (where a.status='OPEN' and a.active"
          + "      and a.advice_type='FALSE_TRIGGER_HIGH') as false_trigger_advices"
          + "  from platform_automation_rule_effect_advice a group by a.rule_id"
          + "), m as ("
          + "  select a.rule_id, a.rule_name, a.trigger_event_type,"
          + "    a.total_runs, a.succeeded_runs, a.failed_runs, a.skipped_runs,"
          + "    a.timeout_runs, a.not_attempted_runs, a.rated_runs,"
          + "    round(a.succeeded_runs::numeric / nullif(a.rated_runs,0), 6) as success_rate,"
          + "    a.total_duration_ms, a.rated_duration_ms,"
          + "    case when a.rated_runs > 0"
          + "      then round(a.rated_duration_ms::numeric / a.rated_runs, 0)::bigint"
          + "      else 0 end as avg_duration_ms,"
          + "    coalesce(f.feedbacks,0) as false_trigger_feedbacks,"
          + "    coalesce(f.unlinked_feedbacks,0) as unlinked_feedbacks,"
          + "    coalesce(f.false_trigger_runs,0) as false_trigger_runs,"
          + "    round(coalesce(f.false_trigger_runs,0)::numeric / nullif(a.rated_runs,0), 6)"
          + "      as false_trigger_rate,"
          + "    b.manual_minutes_per_run, b.baseline_source,"
          + "    case when b.manual_minutes_per_run is null then null"
          + "      else a.succeeded_runs::numeric * b.manual_minutes_per_run end as saved_minutes,"
          + "    coalesce(d.open_advices,0) as open_advices,"
          + "    coalesce(d.false_trigger_advices,0) as false_trigger_advices,"
          + "    a.last_run_at, a.last_run_id"
          + "  from agg a"
          + "  left join fb f on f.rule_id = a.rule_id"
          + "  left join bl b on b.rule_id = a.rule_id"
          + "  left join ad d on d.rule_id = a.rule_id"
          + ") ";
}
