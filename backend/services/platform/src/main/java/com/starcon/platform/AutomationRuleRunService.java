package com.starcon.platform;

import static com.starcon.platform.AutomationRuleRunModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 18.3.2.1「执行日志」的服务层。
 *
 * <p>数据访问一律走 <b>jOOQ 显式 SQL</b>（AGENTS.md：不用 JPA 懒加载掩盖关键逻辑）。
 * 租户上下文由 {@link AccessService#run} 绑定 —— 本类<b>不</b>读任何请求头里的租户。
 *
 * <p><b>六条纪律：</b>
 *
 * <ol>
 *   <li><b>🔴 幂等是硬约束</b>（蓝图加粗句）：
 *       同一规则 + 同一 {@code eventId} ⇒ <b>至多</b>一条执行记录
 *       （DB 层部分唯一索引 {@code (tenant_id, rule_id, event_id) WHERE
 *       rerun_of_run_id IS NULL}）。重复投递 ⇒ 服务层 409、DB 层唯一约束兜底 ——
 *       <b>不可能凭空多出一条 run 并宣称已执行</b>。
 *   <li><b>🔴 手动重跑是「新的一次执行」</b>：新 run，{@code rerun_of_run_id} 指向原 run，
 *       原 run <b>一个字都不改</b>（只追加台账的触发器物理上不允许改）。
 *   <li><b>🔴 手动重跑不擅自执行副作用</b>：本仓<b>没有</b>规则执行引擎 ⇒ 重跑落成
 *       {@code outcome = NOT_ATTEMPTED} + 写清未接通原因。
 *       <b>绝不</b>写成「已重跑成功」（同 {@code UnavailableAiTextGenerationPort} 的纪律）。
 *   <li><b>🔴 每步都可定位</b>：逐步明细逐条记 step_no / 动作类型 / 条件命中情况 /
 *       命中的是哪条 condition / 结果 / 耗时（整数毫秒）/ 错误码与错误信息 ⇒
 *       失败原因能从 run 直接下钻到 step。
 *   <li><b>🔴 只写自己的两张表</b>：{@code platform_automation_rule_run} 与
 *       {@code platform_automation_rule_run_step}。18.3.1.1 的五张表<b>一个字都不写</b>
 *       （只读规则档案取名字与试运行开关）⇒ 两边各有各的写入所有者。
 *   <li><b>数量一律 BigDecimal、耗时一律整数毫秒</b>：库里 {@code NUMERIC(19,6)} /
 *       {@code bigint}，本类<b>一个 double 都没有</b>。
 * </ol>
 */
@Service
public class AutomationRuleRunService {

  /** 一页最多返回多少行。 */
  static final int MAX_PAGE_SIZE = 100;

  /** 一次执行最多登记多少步。 */
  static final int MAX_STEPS = 500;

  /** 事件 id / 幂等键的长度上限。 */
  static final int MAX_EVENT_ID = 200;

  static final int MAX_IDEMPOTENCY_KEY = 320;

  private final DSLContext sql;
  private final ObjectMapper json;

  public AutomationRuleRunService(DSLContext sql, ObjectMapper json) {
    this.sql = sql;
    this.json = json;
  }

  // ================================================================ 工具

  private static void check(boolean condition, String message) {
    if (!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  /** 状态冲突（409）：不是「参数不对」，是「当前状态不允许」。 */
  private static void conflict(String message) {
    throw new ResponseStatusException(HttpStatus.CONFLICT, message);
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

  /** 数量（命中条件数 / 计划动作数 / 已执行动作数）。 */
  private static BigDecimal count(Long raw, String label) {
    if (raw == null) return BigDecimal.ZERO;
    check(raw >= 0, label + "不能为负");
    check(raw <= 1_000_000, label + "过大");
    return BigDecimal.valueOf(raw);
  }

  /** 耗时（**整数毫秒**，禁浮点）。 */
  private static long duration(Long raw, String label) {
    if (raw == null) return 0L;
    check(raw >= 0, label + "不能为负");
    check(raw <= 86_400_000L, label + "不能超过 24 小时");
    return raw;
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

  /**
   * JSON 对象文本：必须能解析、且**必须是对象**（不是数组、不是标量）。
   *
   * <p>解析不了就点名报错 —— 不把「载荷坏了」伪装成「上下文为空」。
   */
  private String jsonObject(String raw, String label) {
    if (raw == null || raw.isBlank()) return "{}";
    var trimmed = raw.trim();
    try {
      var node = json.readTree(trimmed);
      check(node != null && node.isObject(), label + "必须是一个 JSON 对象（如 {\"orderNo\":\"SO-1\"}）");
      return json.writeValueAsString(node);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "不是合法的 JSON");
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

  private long countOf(String sqlText) {
    return sql.fetchOne(sqlText).get(0, Long.class);
  }

  /** 规则档案的只读引用（名字 + 试运行开关）。 */
  private record RuleRef(UUID id, String name, boolean dryRun) {}

  /**
   * 读规则档案（**只读**）。
   *
   * <p>🔴 本模块**一个字都不写**进 18.3.1.1 的表 —— 执行统计台账
   * （{@code platform_automation_rule_execution}）的写入所有者始终是 18.3.1.1。
   */
  private RuleRef ruleRef(UUID ruleId) {
    var row =
        sql.fetchOne(
            "select id, name, dry_run from platform_automation_rule where id = ?", ruleId);
    if (row == null) notFound("规则不存在");
    return new RuleRef(row.get(0, UUID.class), row.get(1, String.class), row.get(2, Boolean.class));
  }

  // ================================================================ 能力声明

  /**
   * 18.3.2.1 的能力声明：逐维说清「有没有、依据是什么、缺了会怎样」。
   *
   * <p>🔴 **规则执行引擎如实答「未交付」** —— 本模块是<b>日志</b>不是<b>引擎</b>：
   * 端点提供的是「登记一次执行 / 查看执行明细 / 提交一次重跑请求」，
   * 重跑请求落成 {@code NOT_ATTEMPTED} 的待执行记录，**不假装**已经重跑过。
   */
  public AutomationRuleRunCapabilities capabilities() {
    var capabilities =
        List.of(
            new AutomationRuleRunCapability(
                "18.3.2.1",
                "执行记录台账（run）",
                true,
                "platform_automation_rule_run 表 + /platform/automation/runs 端点；"
                    + "记触发事件、触发来源、触发上下文、结论、耗时、错误、"
                    + "是否真的产生了副作用",
                "",
                "表是**只追加**的（触发器钉死 UPDATE / DELETE）—— 能改的执行历史等于没有执行历史"),
            new AutomationRuleRunCapability(
                "18.3.2.1",
                "🔴 **逐步明细**（step）",
                true,
                "platform_automation_rule_run_step 表；每步记 step_no / 种类（条件判定 / 动作执行）/ "
                    + "条件命中情况（命中与否 + 命中的是哪条 condition）/ 结果 / "
                    + "耗时（整数毫秒）/ 错误码与错误信息",
                "",
                "18.3.1.1 的 platform_automation_rule_execution **只是统计台账**（聚合计数），"
                    + "不含逐步明细 ⇒ 「失败卡在第几步」在那边查不到"),
            new AutomationRuleRunCapability(
                "18.3.2.1",
                "🔴 **幂等**（蓝图加粗句的落点）",
                true,
                "DB 层部分唯一索引 (tenant_id, rule_id, event_id) WHERE rerun_of_run_id IS NULL "
                    + "+ 幂等键唯一索引 (tenant_id, rule_id, idempotency_key)；"
                    + "重复投递 ⇒ 服务层 409，DB 层唯一约束兜底",
                "",
                "「同一事件重复触发」与「手动重跑」因此**不会互相打架**："
                    + "前者被拒，后者放行成一条**新的、显式挂在原 run 下**的记录"),
            new AutomationRuleRunCapability(
                "18.3.2.1",
                "🔴 **失败原因定位**（run → step 下钻）",
                true,
                "GET /platform/automation/runs/{uuid} 返回 run + 逐步明细；"
                    + "失败步骤必须写清错误码与错误信息（DB 层 CHECK 钉死）",
                "",
                "「失败了但不知道为什么」又是一种静默失败 —— 这一格不许出现"),
            new AutomationRuleRunCapability(
                "18.3.2.1",
                "🔴 **查看上下文**",
                true,
                "run 落库时保存 event_payload（触发载荷快照）+ triggered_by + trigger_event_type",
                "",
                "失败之后要能还原「当时到底发生了什么」，否则复盘无从下手"),
            new AutomationRuleRunCapability(
                "18.3.2.1",
                "🔴 **手动重跑**（只做记录与编排）",
                true,
                "POST /platform/automation/runs/{uuid}/rerun 落成**新的一条** run"
                    + "（rerun_of_run_id 指向原 run、rerun_seq 标明第几次）",
                "",
                "原 run **一个字都不改**（只追加台账的触发器物理上不允许改）—— "
                    + "重跑结果不会把历史覆盖掉"),
            new AutomationRuleRunCapability(
                "18.3.2.1",
                "🔴 **规则执行引擎**（真的跑规则、真的落副作用）",
                false,
                "本仓**不存在**规则执行引擎（backend / services 下没有求值器与动作执行器）；"
                    + "18.3.2「执行」尚未交付",
                "18.3.2",
                "手动重跑因此落成 outcome = NOT_ATTEMPTED + 写清未接通原因，"
                    + "**绝不伪造「已重跑成功」**（同 UnavailableAiTextGenerationPort 的纪律）"),
            new AutomationRuleRunCapability(
                "18.3.2.1",
                "上游规则档案（只读引用）",
                true,
                "platform_automation_rule（18.3.1.1 拥有）；本模块**只读**它的名字与试运行开关，"
                    + "并用真 FK 把执行日志挂在真实存在的规则上",
                "",
                "每张事实表只有一个写入所有者 ⇒ 本模块**不写** 18.3.1.1 的任何一张表"));
    var gaps =
        List.of(
            new AutomationRuleRunGap(
                "18.3.1.2",
                "规则编辑器（可视化编辑 + 样本测试）",
                "platform",
                true,
                "上游 = 18.3.1.2",
                "编辑器保存的规则定义会出现在本模块的规则名下（快照）；"
                    + "编辑器**不执行**规则，本模块**不编辑**规则"),
            new AutomationRuleRunGap(
                "18.3.2.2",
                "规则效果（成功率 / 误触发率 / 优化建议）",
                "platform",
                false,
                "下游 = 18.3.2.2",
                "本模块提供**执行明细**（事实）；聚合报表、误触发反馈与优化建议归 18.3.2.2"),
            new AutomationRuleRunGap(
                "1",
                "工作台告警",
                "platform",
                false,
                "下游 = 1 工作台告警",
                "本模块只产出执行事实；「连续失败要告警」归 1 系统的工作台告警模块"));
    return new AutomationRuleRunCapabilities(capabilities, gaps);
  }

  /** 概览：几个数。 */
  public AutomationRuleRunOverview overview() {
    return new AutomationRuleRunOverview(
        countOf("select count(*) from platform_automation_rule_run"),
        countOf("select count(*) from platform_automation_rule_run where outcome = 'SUCCEEDED'"),
        countOf("select count(*) from platform_automation_rule_run where outcome = 'FAILED'"),
        countOf("select count(*) from platform_automation_rule_run where outcome = 'SKIPPED'"),
        countOf("select count(*) from platform_automation_rule_run where outcome = 'TIMEOUT'"),
        countOf("select count(*) from platform_automation_rule_run where outcome = 'NOT_ATTEMPTED'"),
        countOf("select count(*) from platform_automation_rule_run where source = 'RERUN'"),
        countOf("select count(*) from platform_automation_rule_run where side_effects_applied"),
        countOf("select coalesce(sum(duration_ms), 0) from platform_automation_rule_run"),
        countOf("select count(*) from platform_automation_rule_run_step where result = 'FAILED'"),
        dbNow());
  }

  // ================================================================ 列表（真分页 + 真筛选）

  /** 执行日志列表（真分页 + 真筛选 + **全序**排序）。 */
  public AutomationRuleRunPage runs(
      UUID ruleId,
      String outcome,
      String source,
      String eventType,
      String eventId,
      Boolean dryRun,
      String keyword,
      String from,
      String to,
      int page,
      int size) {
    var size2 = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    var page2 = Math.max(page, 0);

    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (ruleId != null) {
      where.append(" and r.rule_id = ?");
      params.add(ruleId);
    }
    if (outcome != null && !outcome.isBlank()) {
      var parsed = AutomationRuleRunOutcome.parse(outcome);
      check(parsed != null, "执行结论只能是 SUCCEEDED / FAILED / SKIPPED / TIMEOUT / NOT_ATTEMPTED");
      where.append(" and r.outcome = ?");
      params.add(parsed.name());
    }
    if (source != null && !source.isBlank()) {
      var parsed = AutomationRuleRunSource.parse(source);
      check(parsed != null, "执行来源只能是 ENGINE / MANUAL / RERUN");
      where.append(" and r.source = ?");
      params.add(parsed.name());
    }
    if (eventType != null && !eventType.isBlank()) {
      where.append(" and r.trigger_event_type = ?");
      params.add(eventType.trim());
    }
    if (eventId != null && !eventId.isBlank()) {
      where.append(" and r.event_id = ?");
      params.add(eventId.trim());
    }
    if (dryRun != null) {
      where.append(" and r.dry_run = ?");
      params.add(dryRun);
    }
    if (keyword != null && !keyword.isBlank()) {
      where.append(
          " and (r.rule_name ilike ? or r.event_id ilike ? or r.triggered_by ilike ?"
              + " or r.trigger_event_type ilike ? or r.failure_reason ilike ?"
              + " or r.error_message ilike ?)");
      var like = "%" + keyword.trim() + "%";
      for (var i = 0; i < 6; i++) params.add(like);
    }
    var fromAt = parseTime(from, "开始时间");
    if (fromAt != null) {
      where.append(" and r.created_at >= ?::timestamptz");
      params.add(fromAt);
    }
    var toAt = parseTime(to, "结束时间");
    if (toAt != null) {
      where.append(" and r.created_at <= ?::timestamptz");
      params.add(toAt);
    }
    if (fromAt != null && toAt != null) {
      check(!toAt.isBefore(fromAt), "结束时间不能早于开始时间");
    }

    var total =
        sql.fetchOne(
                "select count(*) from platform_automation_rule_run r" + where, params.toArray())
            .get(0, Long.class);

    // 🔴 **全序**：created_at DESC → id DESC。id 是全局唯一主键 ⇒
    //    同一时刻的多条记录次序**确定**，不靠 DB 返回顺序（那是未定义行为）。
    var pageRows =
        sql.fetch(
                "select r.id,r.rule_id,r.rule_name,r.event_id,r.idempotency_key,"
                    + " r.trigger_event_type,r.triggered_by,r.event_payload::text,r.outcome,r.source,"
                    + " r.dry_run,r.side_effects_applied,r.matched_conditions,r.planned_actions,"
                    + " r.executed_actions,r.duration_ms,r.failure_reason,r.failure_class,"
                    + " r.error_code,r.error_message,r.not_attempted_reason,r.rerun_of_run_id,"
                    + " r.rerun_seq,r.rerun_requested_by,r.rerun_requested_at,r.rerun_reason,"
                    + " r.started_at,r.finished_at,r.created_by,r.created_at"
                    + " from platform_automation_rule_run r"
                    + where
                    + " order by r.created_at desc, r.id desc"
                    + " limit ? offset ?",
                appendPage(params, size2, page2))
            .map(AutomationRuleRunService::toRunRow);
    return new AutomationRuleRunPage(pageRows, page2, size2, total);
  }

  private static Object[] appendPage(List<Object> params, int size, int page) {
    var all = new ArrayList<>(params);
    all.add(size);
    all.add((long) page * size);
    return all.toArray();
  }

  // ================================================================ 详情

  /** 一次执行的详情（含逐步明细 ⇒ 失败原因可以从 run 直接下钻到 step）。 */
  public AutomationRuleRunDetail runDetail(UUID id) {
    var run = runRow(id);
    return new AutomationRuleRunDetail(run, stepRows(id), rerunCountOf(id));
  }

  /** 一条执行记录。 */
  public AutomationRuleRunRow runRow(UUID id) {
    var row =
        sql.fetchOne(
            "select r.id,r.rule_id,r.rule_name,r.event_id,r.idempotency_key,"
                + " r.trigger_event_type,r.triggered_by,r.event_payload::text,r.outcome,r.source,"
                + " r.dry_run,r.side_effects_applied,r.matched_conditions,r.planned_actions,"
                + " r.executed_actions,r.duration_ms,r.failure_reason,r.failure_class,"
                + " r.error_code,r.error_message,r.not_attempted_reason,r.rerun_of_run_id,"
                + " r.rerun_seq,r.rerun_requested_by,r.rerun_requested_at,r.rerun_reason,"
                + " r.started_at,r.finished_at,r.created_by,r.created_at"
                + " from platform_automation_rule_run r where r.id = ?",
            id);
    if (row == null) notFound("执行记录不存在");
    return toRunRow(row);
  }

  /** 逐步明细（**全序**：step_no）。 */
  public List<AutomationRuleRunStepRow> stepRows(UUID runId) {
    return sql.fetch(
            "select s.id,s.run_id,s.step_no,s.step_kind,s.condition_id,s.condition_seq,"
                + " s.field_path,s.operator,s.expected_value,s.actual_value,s.matched,"
                + " s.action_type,s.target_ref,s.params::text,s.result,s.duration_ms,"
                + " s.error_code,s.error_message,s.side_effect_applied,s.created_at"
                + " from platform_automation_rule_run_step s where s.run_id = ?"
                + " order by s.step_no",
            runId)
        .map(AutomationRuleRunService::toStepRow);
  }

  private long rerunCountOf(UUID runId) {
    return sql.fetchOne(
            "select count(*) from platform_automation_rule_run where rerun_of_run_id = ?", runId)
        .get(0, Long.class);
  }

  // ================================================================ 登记一次执行

  /**
   * 🔴 **登记一次执行** —— 执行日志的唯一写入口（事件触发的执行）。
   *
   * <p>🔴 **幂等**：同一规则 + 同一 {@code eventId} 已经登记过 ⇒ <b>409</b>，
   * 并在消息里点名那一条既有 run 的 id。
   * <b>绝不</b>凭空多出一条 run 并宣称已执行（蓝图加粗句）。
   */
  public AutomationRuleRunDetail recordRun(
      AutomationRuleRunInput input, AccessService.Access actor) {
    check(input.ruleId() != null, "必须指定这次执行属于哪条规则");
    var rule = ruleRef(input.ruleId());

    var eventId = required(input.eventId(), "触发事件 id", MAX_EVENT_ID);
    var outcome =
        AutomationRuleRunOutcome.parse(input.outcome() == null ? "" : input.outcome());
    check(outcome != null, "执行结论只能是 SUCCEEDED / FAILED / SKIPPED / TIMEOUT / NOT_ATTEMPTED");

    var source =
        input.source() == null || input.source().isBlank()
            ? AutomationRuleRunSource.MANUAL
            : AutomationRuleRunSource.parse(input.source());
    check(source != null, "执行来源只能是 ENGINE / MANUAL / RERUN");
    // 🔴 「重跑」来源**只能**由重跑端点写入 —— 手工登记成 RERUN 会伪造重跑链。
    check(source != AutomationRuleRunSource.RERUN, "RERUN 来源只能由重跑端点产生，不能手工登记");

    var matchedConditions = count(input.matchedConditions(), "命中条件数");
    var plannedActions = count(input.plannedActions(), "计划动作数");
    var executedActions = count(input.executedActions(), "已执行动作数");
    check(
        executedActions.compareTo(plannedActions) <= 0,
        "已执行动作数不能超过计划动作数");

    // 🔴 **试运行标记由规则档案推导**（客户端自称不算数）。
    var dryRun = rule.dryRun();
    var sideEffects = Boolean.TRUE.equals(input.sideEffectsApplied());
    if (dryRun && sideEffects) {
      conflict("这条规则处于试运行状态，不允许登记「产生了真实副作用」的执行记录");
    }
    if (dryRun && executedActions.signum() > 0) {
      conflict("这条规则处于试运行状态，不允许登记「已经执行了动作」的执行记录");
    }
    check(
        !sideEffects || executedActions.signum() > 0,
        "声称产生了副作用就必须至少真的执行了一个动作，否则这一行会自相矛盾");

    var failureReason = optional(input.failureReason(), "失败原因", 500);
    var failureClassRaw = optional(input.failureClass(), "失败分类", 40);
    var failureClass =
        failureClassRaw.isEmpty() ? null : AutomationRuleRunFailureClass.parse(failureClassRaw);
    check(failureClassRaw.isEmpty() || failureClass != null, "失败分类取值不合法");
    check(
        outcome != AutomationRuleRunOutcome.FAILED || !failureReason.isEmpty(),
        "失败必须写清失败原因 —— 否则就是又一种静默失败");
    check(
        failureReason.isEmpty() == (failureClass == null),
        "失败原因与失败分类必须同时给或同时不给 —— 只给一半说不清是哪一类失败");

    var notAttemptedReason = optional(input.notAttemptedReason(), "未接通原因", 500);
    if (outcome == AutomationRuleRunOutcome.NOT_ATTEMPTED) {
      check(!notAttemptedReason.isEmpty(), "「未执行」必须写清未接通原因 —— 否则又是一种静默失败");
    } else {
      check(
          notAttemptedReason.isEmpty(),
          "只有「未执行」这一种结论才允许写未接通原因（否则「未执行」与「已执行」会同时成立）");
    }

    var errorCode = optional(input.errorCode(), "错误码", 64);
    var errorMessage = optional(input.errorMessage(), "错误信息", 1000);

    var startedAt = parseTime(input.startedAt(), "开始时间");
    var finishedAt = parseTime(input.finishedAt(), "结束时间");
    var now = dbNow();
    if (startedAt == null) startedAt = now;
    if (finishedAt == null) finishedAt = now;
    check(!finishedAt.isBefore(startedAt), "结束时间不能早于开始时间");

    // 🔴 耗时：客户端没给就**从起止时间推**（整数毫秒，不猜、不用浮点）。
    var durationMs = duration(input.durationMs(), "耗时");
    if (input.durationMs() == null) {
      durationMs = Math.max(0L, Duration.between(startedAt, finishedAt).toMillis());
    }

    var eventPayload = jsonObject(input.eventPayload(), "触发上下文");
    var idempotencyKey = "evt:" + eventId;

    // 🔴 **幂等闸**：同一规则 + 同一事件 id 已经登记过 ⇒ 409，并点名既有记录。
    var existing =
        sql.fetchOne(
            "select id from platform_automation_rule_run"
                + " where rule_id = ? and idempotency_key = ?",
            rule.id(),
            idempotencyKey);
    if (existing != null) {
      conflict(
          "同一事件（"
              + eventId
              + "）已经登记过一次执行（run_id = "
              + existing.get(0, UUID.class)
              + "）。按幂等要求，同一事件重复触发不会产生第二次副作用；"
              + "若确实需要再跑一次，请走「手动重跑」。");
    }

    var runId = UUID.randomUUID();
    sql.execute(
        "insert into platform_automation_rule_run(id,tenant_id,rule_id,rule_name,event_id,"
            + " idempotency_key,trigger_event_type,triggered_by,event_payload,outcome,source,"
            + " dry_run,side_effects_applied,matched_conditions,planned_actions,executed_actions,"
            + " duration_ms,failure_reason,failure_class,error_code,error_message,"
            + " not_attempted_reason,rerun_of_run_id,rerun_seq,rerun_requested_by,"
            + " rerun_requested_at,rerun_reason,started_at,finished_at,created_by,created_at)"
            + " values(?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?::numeric,?::numeric,?::numeric,?,?,?,?,?,"
            + " ?,null,null,null,null,'',?::timestamptz,?::timestamptz,?,?::timestamptz)",
        runId,
        currentTenant(),
        rule.id(),
        rule.name(),
        eventId,
        idempotencyKey,
        optional(input.triggerEventType(), "触发事件类型", 64),
        optional(input.triggeredBy(), "触发来源", 120),
        eventPayload,
        outcome.name(),
        source.name(),
        dryRun,
        sideEffects,
        matchedConditions,
        plannedActions,
        executedActions,
        durationMs,
        failureReason,
        failureClass == null ? "" : failureClass.name(),
        errorCode,
        errorMessage,
        notAttemptedReason,
        startedAt,
        finishedAt,
        actor.userId(),
        now);

    insertSteps(runId, input.steps(), dryRun);
    return runDetail(runId);
  }

  // ================================================================ 手动重跑

  /**
   * 🔴 **手动重跑** —— 落成**新的一条**执行记录，与原 run 显式关联。
   *
   * <p>🔴 **只做记录与编排，不擅自执行副作用**：本仓没有规则执行引擎 ⇒
   * 新记录落成 {@code outcome = NOT_ATTEMPTED} + 写清未接通原因，
   * <b>绝不伪造「已重跑成功」**。
   *
   * <p>🔴 **原 run 一个字都不改**：它是只追加台账（DB 触发器钉死 UPDATE / DELETE），
   * 重跑结果**不会**把历史覆盖掉。
   *
   * <p>幂等：调用方自带 {@code idempotencyKey} 时，同一个键重复提交 ⇒
   * 返回**同一条**记录（双击按钮不会变成跑两次）。
   */
  public AutomationRuleRunRerunResult rerun(
      UUID runId, AutomationRuleRunRerunInput input, AccessService.Access actor) {
    var original = runRow(runId);
    var rule = ruleRef(original.ruleId());
    var now = dbNow();

    var reason = optional(input == null ? null : input.reason(), "重跑理由", 500);
    var key = input == null ? "" : (input.idempotencyKey() == null ? "" : input.idempotencyKey().trim());
    if (!key.isEmpty()) {
      check(key.length() <= MAX_IDEMPOTENCY_KEY, "幂等键长度不能超过 " + MAX_IDEMPOTENCY_KEY + " 个字符");
    } else {
      // 没带幂等键 ⇒ 按「第几次重跑」生成一个稳定的键（同一原 run 内不重复）。
      var seq =
          sql.fetchOne(
                  "select coalesce(max(r.rerun_seq), 0) from platform_automation_rule_run r"
                      + " where r.rerun_of_run_id = ?",
                  runId)
              .get(0, Integer.class)
          + 1;
      key = "rerun:" + runId + ":" + seq;
    }

    // 🔴 同一个幂等键重复提交 ⇒ 返回**同一条**记录（不产生第二次重跑）。
    var existing =
        sql.fetchOne(
            "select id from platform_automation_rule_run where rule_id = ? and idempotency_key = ?",
            original.ruleId(),
            key);
    if (existing != null) {
      var existingId = existing.get(0, UUID.class);
      return new AutomationRuleRunRerunResult(
          runDetail(existingId), true, "同一个幂等键已经提交过一次重跑，返回的是那一条记录。");
    }

    var seq =
        sql.fetchOne(
                "select coalesce(max(r.rerun_seq), 0) from platform_automation_rule_run r"
                    + " where r.rerun_of_run_id = ?",
                runId)
            .get(0, Integer.class)
        + 1;

    var notAttemptedReason = executorUnavailableReason();
    var newId = UUID.randomUUID();
    sql.execute(
        "insert into platform_automation_rule_run(id,tenant_id,rule_id,rule_name,event_id,"
            + " idempotency_key,trigger_event_type,triggered_by,event_payload,outcome,source,"
            + " dry_run,side_effects_applied,matched_conditions,planned_actions,executed_actions,"
            + " duration_ms,failure_reason,failure_class,error_code,error_message,"
            + " not_attempted_reason,rerun_of_run_id,rerun_seq,rerun_requested_by,"
            + " rerun_requested_at,rerun_reason,started_at,finished_at,created_by,created_at)"
            + " values(?,?,?,?,?,?,?,?,?::jsonb,'NOT_ATTEMPTED','RERUN',?,false,0,0,0,0,?,"
            + " 'EXECUTOR_UNAVAILABLE','EXECUTOR_NOT_CONNECTED',?,?,?,?,?,?::timestamptz,?,"
            + " ?::timestamptz,?::timestamptz,?,?::timestamptz)",
        newId,
        currentTenant(),
        original.ruleId(),
        original.ruleName(),
        original.eventId(),
        key,
        original.triggerEventType(),
        "手动重跑",
        original.eventPayload(),
        // 🔴 试运行标记同样由**规则档案**推导（客户端不能自称）。
        rule.dryRun(),
        notAttemptedReason,
        notAttemptedReason,
        notAttemptedReason,
        runId,
        seq,
        actor.userId(),
        now,
        reason,
        now,
        now,
        actor.userId(),
        now);

    return new AutomationRuleRunRerunResult(runDetail(newId), false, notAttemptedReason);
  }

  /** 执行器未接通的**如实**说明：缺什么、归谁、现在怎么办。 */
  private String executorUnavailableReason() {
    return "规则执行引擎**未接通**：本仓目前没有任何真实的规则执行器"
        + "（backend / services 下不存在求值器与动作执行器），18.3.2「执行」尚未交付。"
        + "因此这次重跑**只登记了请求，没有真的执行任何动作**（未产生任何副作用）。"
        + "该能力归 **18.3.2 业务规则引擎 · 执行**；"
        + "在此之前请先在 18.3.1.2 规则编辑器里用样本测试确认规则本身是对的。";
  }

  // ================================================================ 逐步明细写入

  private void insertSteps(UUID runId, List<AutomationRuleRunStepInput> steps, boolean dryRun) {
    if (steps == null || steps.isEmpty()) return;
    check(steps.size() <= MAX_STEPS, "一次执行最多登记 " + MAX_STEPS + " 步");
    var now = dbNow();
    var seen = new java.util.HashSet<Integer>();
    for (var step : steps) {
      check(step.stepNo() != null, "每一步都必须有步号（stepNo）");
      check(step.stepNo() >= 1, "步号必须从 1 开始");
      check(seen.add(step.stepNo()), "步号重复：" + step.stepNo() + "（同一次执行内步号唯一）");

      var kind = AutomationRuleRunStepKind.parse(step.stepKind() == null ? "" : step.stepKind());
      check(kind != null, "步骤种类只能是 CONDITION（条件判定）或 ACTION（动作执行）");
      var result =
          AutomationRuleRunStepResult.parse(step.result() == null ? "" : step.result());
      check(
          result != null,
          "步骤结果只能是 SUCCEEDED / FAILED / SKIPPED / NOT_MATCHED / NOT_ATTEMPTED");

      var actionType = optional(step.actionType(), "动作类型", 64);
      if (kind == AutomationRuleRunStepKind.ACTION) {
        check(!actionType.isEmpty(), "动作执行这一步必须写清动作类型");
      }
      var matched = step.matched();
      if (kind == AutomationRuleRunStepKind.CONDITION) {
        check(matched != null, "条件判定这一步必须给出「命中与否」");
      } else {
        check(matched == null, "动作执行这一步没有「命中与否」这回事");
      }

      var sideEffect = Boolean.TRUE.equals(step.sideEffectApplied());
      check(
          !sideEffect || result == AutomationRuleRunStepResult.SUCCEEDED,
          "声称产生了副作用的步骤必须成功");
      if (dryRun) {
        check(!sideEffect, "这条规则处于试运行状态，不允许登记「产生了真实副作用」的步骤");
      }

      var errorCode = optional(step.errorCode(), "错误码", 64);
      var errorMessage = optional(step.errorMessage(), "错误信息", 1000);
      if (result == AutomationRuleRunStepResult.FAILED) {
        check(
            !errorCode.isEmpty() && !errorMessage.isEmpty(),
            "失败的那一步必须写清错误码与错误信息 —— 否则「卡在第几步」就说不清");
      }

      sql.execute(
          "insert into platform_automation_rule_run_step(id,tenant_id,run_id,step_no,step_kind,"
              + " condition_id,condition_seq,field_path,operator,expected_value,actual_value,"
              + " matched,action_type,target_ref,params,result,duration_ms,error_code,"
              + " error_message,side_effect_applied,created_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?::timestamptz)",
          UUID.randomUUID(),
          currentTenant(),
          runId,
          step.stepNo(),
          kind.name(),
          step.conditionId(),
          step.conditionSeq(),
          optional(step.fieldPath(), "字段路径", 200),
          optional(step.operator(), "操作符", 32),
          optional(step.expectedValue(), "期望值", 500),
          optional(step.actualValue(), "实际值", 500),
          matched,
          actionType,
          optional(step.targetRef(), "动作目标", 200),
          jsonObject(step.params(), "动作参数"),
          result.name(),
          duration(step.durationMs(), "步骤耗时"),
          errorCode,
          errorMessage,
          sideEffect,
          now);
    }
  }

  // ================================================================ 映射

  private static AutomationRuleRunRow toRunRow(org.jooq.Record row) {
    var outcome = AutomationRuleRunOutcome.parse(row.get("outcome", String.class));
    var source = AutomationRuleRunSource.parse(row.get("source", String.class));
    var failureClassRaw = row.get("failure_class", String.class);
    var failureClass =
        failureClassRaw == null || failureClassRaw.isBlank()
            ? null
            : AutomationRuleRunFailureClass.parse(failureClassRaw);
    return new AutomationRuleRunRow(
        row.get("id", UUID.class),
        row.get("rule_id", UUID.class),
        row.get("rule_name", String.class),
        row.get("event_id", String.class),
        row.get("idempotency_key", String.class),
        row.get("trigger_event_type", String.class),
        row.get("triggered_by", String.class),
        row.get("event_payload", String.class),
        row.get("outcome", String.class),
        outcome == null ? row.get("outcome", String.class) : outcome.label(),
        row.get("source", String.class),
        source == null ? row.get("source", String.class) : source.label(),
        row.get("dry_run", Boolean.class),
        row.get("side_effects_applied", Boolean.class),
        row.get("matched_conditions", BigDecimal.class),
        row.get("planned_actions", BigDecimal.class),
        row.get("executed_actions", BigDecimal.class),
        row.get("duration_ms", Long.class),
        row.get("failure_reason", String.class),
        failureClassRaw,
        failureClass == null ? "" : failureClass.label(),
        row.get("error_code", String.class),
        row.get("error_message", String.class),
        row.get("not_attempted_reason", String.class),
        row.get("rerun_of_run_id", UUID.class),
        row.get("rerun_seq", Integer.class),
        row.get("rerun_requested_by", UUID.class),
        row.get("rerun_requested_at", OffsetDateTime.class),
        row.get("rerun_reason", String.class),
        row.get("started_at", OffsetDateTime.class),
        row.get("finished_at", OffsetDateTime.class),
        row.get("created_by", UUID.class),
        row.get("created_at", OffsetDateTime.class));
  }

  private static AutomationRuleRunStepRow toStepRow(org.jooq.Record row) {
    var kind = AutomationRuleRunStepKind.parse(row.get("step_kind", String.class));
    var result = AutomationRuleRunStepResult.parse(row.get("result", String.class));
    return new AutomationRuleRunStepRow(
        row.get("id", UUID.class),
        row.get("run_id", UUID.class),
        row.get("step_no", Integer.class),
        row.get("step_kind", String.class),
        kind == null ? row.get("step_kind", String.class) : kind.label(),
        row.get("condition_id", UUID.class),
        row.get("condition_seq", Integer.class),
        row.get("field_path", String.class),
        row.get("operator", String.class),
        row.get("expected_value", String.class),
        row.get("actual_value", String.class),
        row.get("matched", Boolean.class),
        row.get("action_type", String.class),
        row.get("target_ref", String.class),
        row.get("params", String.class),
        row.get("result", String.class),
        result == null ? row.get("result", String.class) : result.label(),
        row.get("duration_ms", Long.class),
        row.get("error_code", String.class),
        row.get("error_message", String.class),
        row.get("side_effect_applied", Boolean.class),
        row.get("created_at", OffsetDateTime.class));
  }
}
