package com.starcon.platform;

import static com.starcon.platform.AutomationRuleModels.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
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
 * 18.3.1.1「规则列表」的服务层。
 *
 * <p>数据访问一律走 <b>jOOQ 显式 SQL</b>（AGENTS.md：不用 JPA 懒加载掩盖关键逻辑）。
 * 租户上下文由 {@link AccessService#run} 绑定 —— 本类<b>不</b>读任何请求头里的租户。
 *
 * <p><b>六条纪律：</b>
 *
 * <ol>
 *   <li><b>🔴 坏规则不许静默失败</b>（蓝图加粗句，本模块最核心的一条）：
 *       规则的档案行上带着<b>最后一次执行的时间 / 结果 / 失败原因</b>三件套，
 *       外加<b>连续失败计数</b>与<b>坏规则标记</b>；连续失败达到阈值 ⇒ 标记为
 *       {@code failing}，可以被列表按 {@code failing} 筛出来。
 *       登记执行结果的 SQL <b>只更新统计列</b>，<b>不碰</b> {@code enabled} / {@code status}
 *       ⇒ 一条坏规则<b>不会</b>被「悄悄改回正常」。
 *   <li><b>🔴 试运行是独立状态</b>：{@code dry_run} 与 {@code enabled} / {@code status}
 *       互不影响；试运行的规则**不产生真实副作用** ——
 *       执行行里 {@code executed_actions} 必须是 0、{@code side_effects_applied} 必须是 false
 *       （服务层 409 + DB 层 CHECK + DB 层触发器三重钉死）。
 *   <li><b>🔴 启用范围是真约束</b>：范围表参与过滤（{@link #applicable} 的 SQL 真的
 *       JOIN 它），不是只存一个展示用的字段。
 *   <li><b>🔴 优先级与排序真实生效</b>：列表排序是**全序**
 *       （{@code priority, created_at, id}）⇒ 相同优先级的规则次序**确定**，
 *       不靠 DB 返回顺序（那是未定义行为）。
 *   <li><b>不执行规则</b>：18.3.2「执行」是下游、尚未交付 ⇒ 本模块只提供
 *       <b>执行结果登记</b>入口，台账的 {@code source} 字段如实说明这一行是谁写的。
 *   <li><b>数量一律 BigDecimal</b>：库里 {@code NUMERIC(19,6)}，本类<b>一个 double 都没有</b>。
 * </ol>
 */
@Service
public class AutomationRuleService {

  /** 「不指定」的哨兵 uuid：真实 id 不会等于它，用来避免绑定 NULL 参数。 */
  static final UUID ZERO_UUID = new UUID(0L, 0L);

  /** 一次执行最多允许登记多少个「已执行动作」。 */
  static final long MAX_ACTIONS = 1000;

  /** 一次执行最多允许登记多少个「命中条件」。 */
  static final long MAX_CONDITIONS = 1000;

  /** 一条规则最多允许配多少个条件 / 动作 / 范围行。 */
  static final int MAX_CHILDREN = 200;

  private final DSLContext sql;
  private final ObjectMapper json;
  private final ChannelPlatformPort channel;
  private final WarehouseScopePort warehouse;

  public AutomationRuleService(
      DSLContext sql, ObjectMapper json, ChannelPlatformPort channel, WarehouseScopePort warehouse) {
    this.sql = sql;
    this.json = json;
    this.channel = channel;
    this.warehouse = warehouse;
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

  /** 优先级（1..1000，数字越小越先执行）。 */
  private static int priority(Integer raw, String label) {
    var value = raw == null ? 100 : raw;
    check(value >= 1 && value <= 1000, label + "只能在 1 到 1000 之间（数字越小越先执行）");
    return value;
  }

  /** 连续失败阈值（1..100）。 */
  private static int threshold(Integer raw) {
    var value = raw == null ? 3 : raw;
    check(value >= 1 && value <= 100, "连续失败阈值只能在 1 到 100 之间");
    return value;
  }

  /** 数量（执行次数 / 命中条件数 / 动作数）。 */
  private static BigDecimal count(Long raw, String label) {
    if (raw == null) return BigDecimal.ZERO;
    check(raw >= 0, label + "不能为负");
    check(raw <= MAX_ACTIONS, label + "不能超过 " + MAX_ACTIONS);
    return BigDecimal.valueOf(raw);
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

  private long countOf(String sqlText) {
    return sql.fetchOne(sqlText).get(0, Long.class);
  }

  // ================================================================ 能力声明

  /**
   * 18.3.1.1 的能力声明：逐维说清「有没有、依据是什么、缺了会怎样」。
   *
   * <p>🔴 **规则执行引擎如实答「未交付」** —— 18.3.2 是**下游**模块，
   * 本模块**不冒充**它：端点提供的是<b>执行结果登记</b>，台账的 {@code source} 列
   * 写明这一行是「人工登记」还是「引擎写入」。
   */
  public AutomationRuleCapabilities capabilities() {
    var capabilities =
        List.of(
            new AutomationRuleCapability(
                "18.3.1.1",
                "规则档案管理（增删改 + 启用停用 + 排序）",
                true,
                "platform_automation_rule 表 + /platform/automation-rules 端点；"
                    + "记规则名称 / 触发事件 / 优先级 / 启用状态 / 试运行开关 / 备注；"
                    + "排序是**全序**（priority, created_at, id）⇒ 相同优先级次序确定",
                "",
                "排序**不靠 DB 返回顺序** —— 那是未定义行为，两次查询可能不同"),
            new AutomationRuleCapability(
                "18.3.1.1",
                "条件（过滤）",
                true,
                "platform_automation_rule_condition 表；逐条字段路径 + 受控操作符 + 值；"
                    + "GET /rules/{uuid}/preview 会按样本载荷**真的求值**（逐条给出命中与否）",
                "",
                "值拆成 value_text / value_number / value_bool 三列 —— "
                    + "把数字塞进文本再比大小，早晚踩到字符串比较的坑"),
            new AutomationRuleCapability(
                "18.3.1.1",
                "动作（多步）",
                true,
                "platform_automation_rule_action 表；seq 决定**执行顺序**（多步 ⇒ 有序，不是集合）；"
                    + "params 是 JSON 对象（DB 层 CHECK 钉死不是数组 / 不是标量）",
                "",
                "本模块**不解释**参数语义：参数语义归 18.3.1.2 编辑器与 18.3.2 执行"),
            new AutomationRuleCapability(
                "18.3.1.1",
                "🔴 **启用范围是真约束**（租户 / 店铺 / 仓库）",
                true,
                "platform_automation_rule_scope 表 + GET /rules/applicable；"
                    + "该端点的 SQL **真的 JOIN 范围表**（租户范围 / 指定店铺 / 指定仓库三种命中）；"
                    + "范围行有 CHECK：店铺 / 仓库范围**必须**带引用 —— "
                    + "「范围是店铺」却没写是哪家店 = 没有范围",
                "",
                "店铺 / 仓库是**只读引用**（上游 channel / warehouse 服务）："
                    + "不建跨库外键、不抄上游档案（AGENTS.md「模块边界纪律」）"),
            new AutomationRuleCapability(
                "18.3.1.1",
                "🔴 **试运行开关是独立状态**",
                true,
                "platform_automation_rule.dry_run 是**自己的列**，与 enabled / status 互不影响；"
                    + "执行台账有 CHECK：dry_run ⇒ executed_actions = 0 且 side_effects_applied = false；"
                    + "DB 触发器还钉死「执行行的 dry_run 必须与规则的 dry_run 一致」",
                "",
                "「试运行的规则跑过 12 次」与「试运行的规则一个动作都没真跑」"
                    + "这两件事**不可能**被同时误读"),
            new AutomationRuleCapability(
                "18.3.1.1",
                "🔴 **执行统计能从规则档案读到**（时间 / 结果 / 失败原因）",
                true,
                "platform_automation_rule 上有 last_run_at / last_run_status / "
                    + "last_run_failure_reason / last_run_execution_id 四列 —— "
                    + "**不是一个「有没有执行过」的布尔位**；规则列表页直接读这几列",
                "",
                "蓝图加粗句「规则要能看出最后一次执行结果」的落点"),
            new AutomationRuleCapability(
                "18.3.1.1",
                "🔴 **坏规则有反面判据**（连续失败计数 + 标记）",
                true,
                "consecutive_failure_count + consecutive_failure_threshold + failing 三件套；"
                    + "DB 层 CHECK 把 failing 与「连续失败数 ≥ 阈值」钉成**永远一致**；"
                    + "列表可按 failing=true 过滤，概览有「连续失败」这一档",
                "",
                "登记执行结果的 SQL **只更新统计列**，不碰 enabled / status ⇒ "
                    + "坏规则**不会**被悄悄改回正常"),
            new AutomationRuleCapability(
                "18.3.1.1",
                "🔴 **执行结果登记**（执行统计台账）",
                true,
                "platform_automation_rule_execution 表（**append-only**，触发器钉死 UPDATE / DELETE）"
                    + "+ POST /rules/{uuid}/executions；登记后规则档案上的统计指针与计数同步更新",
                "",
                "台账的 source 列如实区分 ENGINE / MANUAL —— "
                    + "现阶段（执行引擎未交付）只可能是 MANUAL，**不伪造**「引擎跑过了」"),
            new AutomationRuleCapability(
                "18.3.1.1",
                "🔴 **规则执行**（真的跑规则、真的落副作用）",
                false,
                "本仓**不存在**规则执行引擎（backend / services 下没有求值器与动作执行器），"
                    + "18.3.2「执行」尚未交付",
                "18.3.2",
                "端点如实答「执行引擎未交付」，**不假装**跑过。"
                    + "GET /rules/{uuid}/preview 只**求值**条件并给出动作计划，"
                    + "**不执行**任何动作（sideEffectsApplied 恒为 false）"),
            new AutomationRuleCapability(
                "18.3.1.1",
                "上游触发事件目录",
                true,
                "读 platform_webhook_event_type 注册表（18.1.2.1 交付）；"
                    + "规则登记时**必须**命中注册表里 ACTIVE 的那一行，"
                    + "否则「配了一条永远不会被触发的规则」在服务层就被拒",
                "",
                "注册表是租户级的：没登记过的事件类型**配不出**规则"));
    var gaps =
        List.of(
            new AutomationRuleGap(
                "18.3.1.2",
                "规则编辑器（可视化编辑 + 样本测试）",
                "platform",
                false,
                "下游 = 18.3.1.2 编辑器",
                "本模块只提供**档案与统计**；可视化编排与样本测试归 18.3.1.2"),
            new AutomationRuleGap(
                "18.3.2.1",
                "执行日志（全局执行明细 + 手动重跑）",
                "platform",
                false,
                "下游 = 18.3.2.1 执行日志",
                "本模块只提供**单条规则**的执行历史与登记入口；"
                    + "全局执行日志、上下文查看与手动重跑归 18.3.2.1"),
            new AutomationRuleGap(
                "18.3.2.2",
                "规则效果（成功率 / 误触发率 / 优化建议）",
                "platform",
                false,
                "下游 = 18.3.2.2 规则效果",
                "本模块的统计是**规则档案上的快照**（够看出坏规则）；"
                    + "聚合报表与误触发反馈归 18.3.2.2"));
    return new AutomationRuleCapabilities(capabilities, gaps);
  }

  /** 概览：几个数。 */
  public AutomationRuleOverview overview() {
    return new AutomationRuleOverview(
        countOf("select count(*) from platform_automation_rule"),
        countOf("select count(*) from platform_automation_rule where enabled"),
        countOf("select count(*) from platform_automation_rule where not enabled"),
        countOf("select count(*) from platform_automation_rule where dry_run"),
        countOf("select count(*) from platform_automation_rule where failing"),
        countOf(
            "select count(*) from platform_automation_rule where last_run_status = ''"),
        countOf("select count(*) from platform_automation_rule_execution"),
        countOf(
            "select count(*) from platform_automation_rule_execution where status in ('FAILED','TIMEOUT')"),
        dbNow());
  }

  // ================================================================ 规则档案

  /** 规则列表（真筛选 + 真关键词 + **全序**排序）。 */
  public List<AutomationRuleRow> ruleRows(
      String eventType,
      String status,
      Boolean enabled,
      Boolean dryRun,
      Boolean failing,
      String scopeType,
      UUID scopeRef,
      String keyword) {
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (eventType != null && !eventType.isBlank()) {
      where.append(" and r.trigger_event_type = ?");
      params.add(eventType.trim());
    }
    if (status != null && !status.isBlank()) {
      where.append(" and r.status = ?");
      params.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (enabled != null) {
      where.append(" and r.enabled = ?");
      params.add(enabled);
    }
    if (dryRun != null) {
      where.append(" and r.dry_run = ?");
      params.add(dryRun);
    }
    if (failing != null) {
      where.append(" and r.failing = ?");
      params.add(failing);
    }
    if (scopeType != null && !scopeType.isBlank()) {
      var parsed = AutomationRuleScopeType.parse(scopeType);
      check(parsed != null, "启用范围类别只能是 TENANT / SHOP / WAREHOUSE");
      where.append(" and exists (select 1 from platform_automation_rule_scope s"
          + " where s.rule_id = r.id and s.tenant_id = r.tenant_id and s.scope_type = ?)");
      params.add(parsed.name());
      if (scopeRef != null) {
        where.append(" and exists (select 1 from platform_automation_rule_scope s"
            + " where s.rule_id = r.id and s.tenant_id = r.tenant_id and s.scope_type = ?"
            + " and s.scope_ref = ?::uuid)");
        params.add(parsed.name());
        params.add(scopeRef);
      }
    }
    if (keyword != null && !keyword.isBlank()) {
      where.append(" and (r.name ilike ? or r.description ilike ? or r.remark ilike ?"
          + " or r.trigger_event_type ilike ?)");
      var like = "%" + keyword.trim() + "%";
      params.add(like);
      params.add(like);
      params.add(like);
      params.add(like);
    }
    // 🔴 **全序**：priority → created_at → id。id 是全局唯一主键 ⇒ 次序确定，
    //    相同优先级的规则也**不会**因 DB 返回顺序不同而抖动。
    return sql.fetch(
            "select r.id,r.name,r.description,r.trigger_event_type,r.trigger_schema_version,"
                + " r.priority,r.enabled,r.status,r.dry_run,r.consecutive_failure_threshold,"
                + " r.last_run_at,r.last_run_status,r.last_run_failure_reason,r.last_run_execution_id,"
                + " r.run_count,r.success_count,r.failure_count,r.consecutive_failure_count,"
                + " r.failing,r.failing_since,r.remark,r.created_by,r.created_at,r.updated_at,"
                + " (select count(*) from platform_automation_rule_condition c"
                + "   where c.rule_id = r.id) as condition_count,"
                + " (select count(*) from platform_automation_rule_action a"
                + "   where a.rule_id = r.id) as action_count,"
                + " (select count(*) from platform_automation_rule_scope s"
                + "   where s.rule_id = r.id) as scope_count"
                + " from platform_automation_rule r"
                + where
                + " order by r.priority, r.created_at, r.id",
            params.toArray())
        .map(AutomationRuleService::toRuleRow);
  }

  /** 一条规则档案（含条件 / 动作 / 范围 / 最近几次执行）。 */
  public AutomationRuleDetail ruleDetail(UUID id) {
    var rule = ruleRow(id);
    return new AutomationRuleDetail(
        rule, conditionRows(id), actionRows(id), scopeRows(id), executionRows(id, 20));
  }

  /** 一条规则档案。 */
  public AutomationRuleRow ruleRow(UUID id) {
    var row =
        sql.fetchOne(
            "select r.id,r.name,r.description,r.trigger_event_type,r.trigger_schema_version,"
                + " r.priority,r.enabled,r.status,r.dry_run,r.consecutive_failure_threshold,"
                + " r.last_run_at,r.last_run_status,r.last_run_failure_reason,r.last_run_execution_id,"
                + " r.run_count,r.success_count,r.failure_count,r.consecutive_failure_count,"
                + " r.failing,r.failing_since,r.remark,r.created_by,r.created_at,r.updated_at,"
                + " (select count(*) from platform_automation_rule_condition c"
                + "   where c.rule_id = r.id) as condition_count,"
                + " (select count(*) from platform_automation_rule_action a"
                + "   where a.rule_id = r.id) as action_count,"
                + " (select count(*) from platform_automation_rule_scope s"
                + "   where s.rule_id = r.id) as scope_count"
                + " from platform_automation_rule r where r.id = ?",
            id);
    if (row == null) notFound("规则不存在");
    return toRuleRow(row);
  }

  /**
   * 新增或修改一条规则（条件 / 动作 / 启用范围**整体替换**）。
   *
   * <p>🔴 登记时**不碰**执行统计那几列 —— 统计只由执行结果登记路径更新，
   * 改配置不该把「上次跑成什么样」抹掉。
   */
  public AutomationRuleDetail saveRule(AutomationRuleInput input, AccessService.Access actor) {
    var name = required(input.name(), "规则名称", 120);
    var description = optional(input.description(), "规则说明", 500);
    var remark = optional(input.remark(), "备注", 500);
    var eventType = required(input.triggerEventType(), "触发事件", 64);
    var schemaVersion = resolveTriggerEvent(eventType, input.triggerSchemaVersion());
    var rulePriority = priority(input.priority(), "优先级");
    var failureThreshold = threshold(input.consecutiveFailureThreshold());

    var id = input.id();
    UUID existingId = null;
    if (id != null) {
      var found = sql.fetchOne("select id from platform_automation_rule where id = ?", id);
      if (found == null) notFound("规则不存在");
      existingId = found.get("id", UUID.class);
    } else {
      var byName = sql.fetchOne("select id from platform_automation_rule where name = ?", name);
      if (byName != null) existingId = byName.get("id", UUID.class);
    }
    if (existingId != null) {
      var clash =
          sql.fetchOne(
              "select id from platform_automation_rule where name = ? and id <> ?::uuid",
              name,
              existingId);
      if (clash != null) conflict("已经有同名规则了（规则名在租户内唯一）");
    }

    // 状态与启用开关：status 是生命周期，enabled 是「是否参与匹配」，两者与试运行开关互不影响。
    var status =
        input.status() == null || input.status().isBlank()
            ? (existingId == null ? "ACTIVE" : currentStatus(existingId))
            : input.status().trim().toUpperCase(Locale.ROOT);
    check(List.of("ACTIVE", "DISABLED").contains(status), "状态只能是 ACTIVE 或 DISABLED");
    var enabled =
        input.enabled() != null
            ? input.enabled()
            : (input.status() == null || input.status().isBlank()
                ? (existingId == null ? true : currentEnabled(existingId))
                : "ACTIVE".equals(status));
    var dryRun =
        input.dryRun() != null
            ? input.dryRun()
            : (existingId == null ? false : currentDryRun(existingId));

    var conditions = input.conditions() == null ? List.<AutomationRuleConditionInput>of() : input.conditions();
    var actions = input.actions() == null ? List.<AutomationRuleActionInput>of() : input.actions();
    var scopes = input.scopes() == null ? List.<AutomationRuleScopeInput>of() : input.scopes();
    check(conditions.size() <= MAX_CHILDREN, "一条规则最多配 " + MAX_CHILDREN + " 个条件");
    check(actions.size() <= MAX_CHILDREN, "一条规则最多配 " + MAX_CHILDREN + " 个动作");
    check(scopes.size() <= MAX_CHILDREN, "一条规则最多配 " + MAX_CHILDREN + " 个启用范围");

    var now = dbNow();
    var ruleId = existingId == null ? UUID.randomUUID() : existingId;
    if (existingId == null) {
      sql.execute(
          "insert into platform_automation_rule(id,tenant_id,name,description,trigger_event_type,"
              + " trigger_schema_version,priority,enabled,status,dry_run,consecutive_failure_threshold,"
              + " remark,created_by,created_at,updated_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
          ruleId,
          currentTenant(),
          name,
          description,
          eventType,
          schemaVersion,
          rulePriority,
          enabled,
          status,
          dryRun,
          failureThreshold,
          remark,
          actor.userId(),
          now,
          now);
    } else {
      sql.execute(
          "update platform_automation_rule set name = ?, description = ?, trigger_event_type = ?,"
              + " trigger_schema_version = ?, priority = ?, enabled = ?, status = ?, dry_run = ?,"
              + " consecutive_failure_threshold = ?, remark = ?, updated_at = ?::timestamptz"
              + " where id = ?",
          name,
          description,
          eventType,
          schemaVersion,
          rulePriority,
          enabled,
          status,
          dryRun,
          failureThreshold,
          remark,
          now,
          ruleId);
      replaceChildren(ruleId, conditions, actions, scopes);
    }
    if (existingId == null) {
      insertChildren(ruleId, conditions, actions, scopes);
    }
    return ruleDetail(ruleId);
  }

  /** 启用一条规则。 */
  public AutomationRuleRow enableRule(UUID id) {
    var updated =
        sql.execute(
            "update platform_automation_rule set status = 'ACTIVE', enabled = true,"
                + " updated_at = ?::timestamptz where id = ?",
            dbNow(),
            id);
    if (updated == 0) notFound("规则不存在");
    return ruleRow(id);
  }

  /** 停用一条规则（**不删**：执行历史要能复现）。 */
  public AutomationRuleRow disableRule(UUID id) {
    var updated =
        sql.execute(
            "update platform_automation_rule set status = 'DISABLED', enabled = false,"
                + " updated_at = ?::timestamptz where id = ?",
            dbNow(),
            id);
    if (updated == 0) notFound("规则不存在");
    return ruleRow(id);
  }

  /**
   * 🔴 **开关试运行**（独立状态）。
   *
   * <p>试运行**不改变**启用 / 停用状态 —— 一条规则可以「启用 + 试运行」。
   */
  public AutomationRuleRow setDryRun(UUID id, Boolean dryRun) {
    check(dryRun != null, "试运行开关必须明确给 true 或 false");
    var updated =
        sql.execute(
            "update platform_automation_rule set dry_run = ?, updated_at = ?::timestamptz where id = ?",
            dryRun,
            dbNow(),
            id);
    if (updated == 0) notFound("规则不存在");
    return ruleRow(id);
  }

  /** 🔴 **排序**（一次性给出若干条规则的优先级）。 */
  public List<AutomationRuleRow> reorder(AutomationRuleReorderInput input) {
    var entries = input == null ? null : input.entries();
    check(entries != null && !entries.isEmpty(), "排序条目不能为空");
    check(entries.size() <= MAX_CHILDREN, "一次最多调整 " + MAX_CHILDREN + " 条规则的优先级");
    var now = dbNow();
    for (var entry : entries) {
      check(entry != null && entry.ruleId() != null, "排序条目必须带规则 id");
      var rulePriority = priority(entry.priority(), "优先级");
      var updated =
          sql.execute(
              "update platform_automation_rule set priority = ?, updated_at = ?::timestamptz"
                  + " where id = ?",
              rulePriority,
              now,
              entry.ruleId());
      if (updated == 0) notFound("规则不存在（" + entry.ruleId() + "）");
    }
    return ruleRows(null, null, null, null, null, null, null, null);
  }

  // ================================================================ 启用范围（真约束）

  /**
   * 🔴 **范围内生效的规则** —— 启用范围是**真约束**的落点。
   *
   * <p>SQL **真的 JOIN 范围表**：命中「整个租户」或「指定店铺」或「指定仓库」才算生效。
   * 一条没有 TENANT 范围行的规则**不会**出现在这里。
   */
  public List<AutomationRuleApplicableRow> applicable(UUID shopId, UUID warehouseId) {
    var shop = shopId == null ? ZERO_UUID : shopId;
    var wh = warehouseId == null ? ZERO_UUID : warehouseId;
    return sql.fetch(
            "select r.id,r.name,r.priority,r.enabled,r.dry_run,r.failing,r.last_run_status,"
                + " s.scope_type,s.scope_label"
                + " from platform_automation_rule r"
                + " join lateral ("
                + "   select sc.scope_type, sc.scope_label"
                + "     from platform_automation_rule_scope sc"
                + "    where sc.rule_id = r.id and sc.tenant_id = r.tenant_id"
                + "      and (sc.scope_type = 'TENANT'"
                + "        or (sc.scope_type = 'SHOP' and sc.scope_ref = ?::uuid)"
                + "        or (sc.scope_type = 'WAREHOUSE' and sc.scope_ref = ?::uuid))"
                + "    order by case sc.scope_type when 'TENANT' then 0 when 'SHOP' then 1 else 2 end,"
                + "             sc.scope_label, sc.scope_ref"
                + "    limit 1"
                + " ) s on true"
                + " where r.enabled = true and r.status = 'ACTIVE'"
                + " order by r.priority, r.created_at, r.id",
            shop,
            wh)
        .map(
            row -> {
              var scopeType = AutomationRuleScopeType.parse(row.get("scope_type", String.class));
              var lastRunStatus = row.get("last_run_status", String.class);
              var failing = Boolean.TRUE.equals(row.get("failing", Boolean.class));
              var health = healthOf(failing, lastRunStatus);
              var dryRun = Boolean.TRUE.equals(row.get("dry_run", Boolean.class));
              return new AutomationRuleApplicableRow(
                  row.get("id", UUID.class),
                  row.get("name", String.class),
                  row.get("priority", Integer.class),
                  Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
                  dryRun,
                  dryRun
                      ? AutomationRuleSideEffectMode.SIMULATE.name()
                      : AutomationRuleSideEffectMode.EXECUTE.name(),
                  health.name(),
                  health.label(),
                  scopeType == null ? row.get("scope_type", String.class) : scopeType.name(),
                  scopeType == null ? "" : scopeType.label(),
                  row.get("scope_label", String.class));
            });
  }

  // ================================================================ 预演（求值，不执行）

  /**
   * 🔴 **预演**：按样本载荷真的求值条件、真的判范围，给出动作计划。
   *
   * <p>本端点<b>不执行任何动作</b>（{@code sideEffectsApplied} 恒为 false）——
   * 规则执行引擎归 18.3.2、尚未交付。
   */
  public AutomationRulePreview preview(UUID id, AutomationRulePreviewInput input) {
    var rule = ruleRow(id);
    var conditions = conditionRows(id);
    var actions = actionRows(id);
    var scopes = scopeRows(id);

    var payload = parsePayload(input == null ? null : input.payload());
    var evaluations = new ArrayList<AutomationRuleConditionEvaluation>();
    var matched = 0;
    var enabledConditions = 0;
    for (var condition : conditions) {
      if (!condition.enabled()) continue;
      enabledConditions++;
      var evaluation = evaluate(condition, payload);
      if (evaluation.matched()) matched++;
      evaluations.add(evaluation);
    }
    var conditionsMatched = enabledConditions == 0 || matched == enabledConditions;

    var shopId = input == null ? null : input.shopId();
    var warehouseId = input == null ? null : input.warehouseId();
    var scopeHit = scopeHit(scopes, shopId, warehouseId);
    var dryRun = rule.dryRun();
    var mode =
        dryRun ? AutomationRuleSideEffectMode.SIMULATE : AutomationRuleSideEffectMode.EXECUTE;
    return new AutomationRulePreview(
        rule.id(),
        rule.name(),
        rule.enabled(),
        dryRun,
        mode.name(),
        mode.label(),
        scopeHit.applicable(),
        scopeHit.note(),
        enabledConditions,
        matched,
        conditionsMatched,
        evaluations,
        actions,
        false,
        dryRun
            ? "这条规则处于**试运行**：动作计划已算出，但**不产生真实副作用**。"
                + "（执行引擎归 18.3.2，尚未交付；本端点只求值，不执行。）"
            : "本端点只**求值**条件并给出动作计划，**不执行**任何动作 —— "
                + "执行引擎归 18.3.2，尚未交付。");
  }

  // ================================================================ 执行统计

  /** 某条规则的执行历史（只追加台账，倒序）。 */
  public List<AutomationRuleExecutionRow> executionRows(UUID ruleId, int limit) {
    // 🔴 先确认这条规则**在当前租户名下存在** —— 否则会返回 200 + 空列表，
    //    而「这条规则不是你的」与「这条规则还没有执行过」是两件事。
    ruleRow(ruleId);
    var size = Math.min(Math.max(limit, 1), 200);
    return sql.fetch(
            "select e.id,e.rule_id,r.name as rule_name,e.run_no,e.status,e.source,e.dry_run,"
                + " e.side_effects_applied,e.triggered_by,e.trigger_event_type,e.matched_conditions,"
                + " e.planned_actions,e.executed_actions,e.failure_reason,e.failure_class,"
                + " e.started_at,e.finished_at,e.created_by,e.created_at"
                + " from platform_automation_rule_execution e"
                + " join platform_automation_rule r on r.id = e.rule_id and r.tenant_id = e.tenant_id"
                + " where e.rule_id = ? order by e.run_no desc limit ?",
            ruleId,
            size)
        .map(AutomationRuleService::toExecutionRow);
  }

  /**
   * 🔴 **登记一次执行结果** —— 执行统计的唯一写入口。
   *
   * <p>登记后规则档案上的<b>最后一次执行（时间 / 结果 / 失败原因）</b>、
   * 累计计数与<b>连续失败计数 / 坏规则标记</b>同步更新。
   *
   * <p>⚠️ 这条 SQL <b>只更新统计列</b>，<b>不碰</b> {@code enabled} / {@code status}
   * ⇒ 一条坏规则<b>不会</b>被「悄悄改回正常」。
   */
  public AutomationRuleExecutionRow recordExecution(
      UUID ruleId, AutomationRuleExecutionInput input, AccessService.Access actor) {
    var rule = ruleRow(ruleId);
    var status = AutomationRuleRunStatus.parse(input.status());
    check(status != null, "执行结论只能是 SUCCEEDED / FAILED / SKIPPED / TIMEOUT");
    var source =
        input.source() == null || input.source().isBlank()
            ? AutomationRuleExecutionSource.MANUAL
            : AutomationRuleExecutionSource.parse(input.source());
    check(source != null, "执行来源只能是 ENGINE 或 MANUAL");

    var dryRun = rule.dryRun();
    var sideEffects = Boolean.TRUE.equals(input.sideEffectsApplied());
    var matchedConditions = count(input.matchedConditions(), "命中条件数");
    var plannedActions = count(input.plannedActions(), "计划动作数");
    var executedActions = count(input.executedActions(), "已执行动作数");
    var failureReason = optional(input.failureReason(), "失败原因", 500);
    var failureClassRaw = optional(input.failureClass(), "失败分类", 40);
    var failureClass =
        failureClassRaw.isEmpty() ? null : AutomationRuleFailureClass.parse(failureClassRaw);
    check(failureClassRaw.isEmpty() || failureClass != null, "失败分类取值不合法");

    // 🔴 试运行 ⇒ 不产生真实副作用（服务层这一道 + DB 层 CHECK + DB 层触发器）。
    if (dryRun && sideEffects) {
      conflict("这条规则处于试运行状态，不允许登记「产生了真实副作用」的执行结果");
    }
    if (dryRun && executedActions.signum() > 0) {
      conflict("这条规则处于试运行状态，不允许登记「已经执行了动作」的执行结果");
    }
    check(
        !sideEffects || executedActions.signum() > 0,
        "声称产生了副作用就必须至少真的执行了一个动作，否则这一行会自相矛盾");
    check(
        executedActions.compareTo(plannedActions) <= 0,
        "已执行动作数不能超过计划动作数");
    check(
        status != AutomationRuleRunStatus.FAILED || !failureReason.isEmpty(),
        "失败必须写清失败原因 —— 否则就是又一种静默失败");
    check(
        failureReason.isEmpty() == (failureClass == null),
        "失败原因与失败分类必须同时给或同时不给 —— 只给一半说不清是哪一类失败");

    var startedAt = parseTime(input.startedAt(), "开始时间");
    var finishedAt = parseTime(input.finishedAt(), "结束时间");
    var now = dbNow();
    if (startedAt == null) startedAt = now;
    if (finishedAt == null) finishedAt = now;
    check(!finishedAt.isBefore(startedAt), "结束时间不能早于开始时间");

    var runNo =
        sql.fetchOne(
                "select coalesce(max(e.run_no), 0) from platform_automation_rule_execution e"
                    + " where e.rule_id = ?",
                ruleId)
            .get(0, Integer.class)
        + 1;
    var executionId = UUID.randomUUID();
    sql.execute(
        "insert into platform_automation_rule_execution(id,tenant_id,rule_id,run_no,status,source,"
            + " dry_run,side_effects_applied,triggered_by,trigger_event_type,matched_conditions,"
            + " planned_actions,executed_actions,failure_reason,failure_class,started_at,finished_at,"
            + " created_by,created_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?::numeric,?::numeric,?::numeric,?,?,?::timestamptz,"
            + "?::timestamptz,?,?::timestamptz)",
        executionId,
        currentTenant(),
        ruleId,
        runNo,
        status.name(),
        source.name(),
        dryRun,
        sideEffects,
        optional(input.triggeredBy(), "触发来源", 120),
        optional(input.triggerEventType(), "触发事件", 64),
        matchedConditions,
        plannedActions,
        executedActions,
        failureReason,
        failureClass == null ? "" : failureClass.name(),
        startedAt,
        finishedAt,
        actor.userId(),
        now);

    updateRuleStatistics(ruleId, status, failureReason, executionId, finishedAt, now);
    return executionRow(executionId);
  }

  /**
   * 🔴 更新规则档案上的执行统计 —— <b>只更新统计列</b>。
   *
   * <p>连续失败计数与坏规则标记在 SQL 里算出来（不靠应用层读改写），
   * {@code failing} 与「连续失败数 ≥ 阈值」因此**永远一致**（DB 层 CHECK 兜底）。
   */
  private void updateRuleStatistics(
      UUID ruleId,
      AutomationRuleRunStatus status,
      String failureReason,
      UUID executionId,
      OffsetDateTime finishedAt,
      OffsetDateTime now) {
    var isFailure = status == AutomationRuleRunStatus.FAILED || status == AutomationRuleRunStatus.TIMEOUT;
    var isSuccess = status == AutomationRuleRunStatus.SUCCEEDED;
    sql.execute(
        "with calc as ("
            + " select r.id,"
            + "   case when ?::text in ('FAILED','TIMEOUT') then r.consecutive_failure_count + 1"
            + "        when ?::text = 'SUCCEEDED' then 0"
            + "        else r.consecutive_failure_count end as next_consecutive"
            + "   from platform_automation_rule r where r.id = ?)"
            + " update platform_automation_rule r"
            + " set run_count = r.run_count + 1,"
            + "     success_count = r.success_count + case when ?::text = 'SUCCEEDED' then 1 else 0 end,"
            + "     failure_count = r.failure_count + case when ?::text in ('FAILED','TIMEOUT') then 1 else 0 end,"
            + "     consecutive_failure_count = c.next_consecutive,"
            + "     failing = (c.next_consecutive >= r.consecutive_failure_threshold),"
            + "     failing_since = case when (c.next_consecutive >= r.consecutive_failure_threshold)"
            + "                       then coalesce(r.failing_since, ?::timestamptz) else null end,"
            + "     last_run_at = ?::timestamptz,"
            + "     last_run_status = ?,"
            + "     last_run_failure_reason = ?,"
            + "     last_run_execution_id = ?,"
            + "     updated_at = ?::timestamptz"
            + " from calc c where r.id = c.id",
        status.name(),
        status.name(),
        ruleId,
        status.name(),
        status.name(),
        now,
        finishedAt,
        status.name(),
        failureReason,
        executionId,
        now);
  }

  /** 一条执行记录。 */
  public AutomationRuleExecutionRow executionRow(UUID id) {
    var row =
        sql.fetchOne(
            "select e.id,e.rule_id,r.name as rule_name,e.run_no,e.status,e.source,e.dry_run,"
                + " e.side_effects_applied,e.triggered_by,e.trigger_event_type,e.matched_conditions,"
                + " e.planned_actions,e.executed_actions,e.failure_reason,e.failure_class,"
                + " e.started_at,e.finished_at,e.created_by,e.created_at"
                + " from platform_automation_rule_execution e"
                + " join platform_automation_rule r on r.id = e.rule_id and r.tenant_id = e.tenant_id"
                + " where e.id = ?",
            id);
    if (row == null) notFound("执行记录不存在");
    return toExecutionRow(row);
  }

  // ================================================================ 上游（只读）

  /** 可用触发事件（来自平台事件注册表）。 */
  public List<AutomationRuleEventTypeRow> eventTypes() {
    return sql.fetch(
            "select event_type,schema_version,status,source_service,source_artifact"
                + " from platform_webhook_event_type order by event_type, schema_version")
        .map(
            row ->
                new AutomationRuleEventTypeRow(
                    row.get("event_type", String.class),
                    row.get("schema_version", Integer.class),
                    row.get("status", String.class),
                    row.get("source_service", String.class),
                    row.get("source_artifact", String.class)));
  }

  /** 上游 2.3 店铺（**只读**；读不到 ⇒ 503）。 */
  public List<AutomationRuleUpstreamShopRow> upstreamShops(String bearer) {
    return channel.shops(bearer).stream()
        .map(
            r ->
                new AutomationRuleUpstreamShopRow(
                    r.id(), r.code(), r.name(), r.platformCode(), r.platformName(), r.status()))
        .toList();
  }

  /** 上游仓库（**只读**；读不到 ⇒ 503）。 */
  public List<AutomationRuleUpstreamWarehouseRow> upstreamWarehouses(String bearer) {
    return warehouse.warehouses(bearer).stream()
        .map(
            r ->
                new AutomationRuleUpstreamWarehouseRow(
                    r.id(), r.code(), r.name(), r.countryCode(), r.status(), r.isDefault()))
        .toList();
  }

  // ================================================================ 内部：规则档案读取

  private String currentStatus(UUID id) {
    return sql.fetchOne("select status from platform_automation_rule where id = ?", id)
        .get("status", String.class);
  }

  private boolean currentEnabled(UUID id) {
    return Boolean.TRUE.equals(
        sql.fetchOne("select enabled from platform_automation_rule where id = ?", id)
            .get("enabled", Boolean.class));
  }

  private boolean currentDryRun(UUID id) {
    return Boolean.TRUE.equals(
        sql.fetchOne("select dry_run from platform_automation_rule where id = ?", id)
            .get("dry_run", Boolean.class));
  }

  /**
   * 解析触发事件：必须是**事件注册表里 ACTIVE 的那一行**。
   *
   * <p>🔴 不给版本时取该事件类型**可用版本里最高**的那个；没有可用版本 ⇒ 400。
   * 「配了一条永远不会被触发的规则」在服务层就被拒掉。
   */
  private int resolveTriggerEvent(String eventType, Integer schemaVersion) {
    if (schemaVersion != null) {
      var found =
          sql.fetchOne(
              "select schema_version from platform_webhook_event_type"
                  + " where event_type = ? and schema_version = ? and status = 'ACTIVE'",
              eventType,
              schemaVersion);
      if (found == null) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "触发事件 "
                + eventType
                + " 第 "
                + schemaVersion
                + " 版没有在事件注册表里登记，或不是「可用」状态 —— "
                + "配一条永远不会被触发的规则没有意义");
      }
      return found.get("schema_version", Integer.class);
    }
    var latest =
        sql.fetchOne(
            "select max(schema_version) from platform_webhook_event_type"
                + " where event_type = ? and status = 'ACTIVE'",
            eventType);
    var version = latest == null ? null : latest.get(0, Integer.class);
    if (version == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "触发事件 "
              + eventType
              + " 没有在事件注册表里登记过「可用」的版本 —— "
              + "先在事件订阅里登记这个事件，再回来配规则");
    }
    return version;
  }

  // ================================================================ 内部：子表

  private List<AutomationRuleConditionRow> conditionRows(UUID ruleId) {
    return sql.fetch(
            "select id,seq,field_path,operator,value_text,value_number,value_bool,enabled,remark"
                + " from platform_automation_rule_condition where rule_id = ? order by seq",
            ruleId)
        .map(
            row -> {
              var operator =
                  AutomationRuleConditionOperator.parse(row.get("operator", String.class));
              return new AutomationRuleConditionRow(
                  row.get("id", UUID.class),
                  row.get("seq", Integer.class),
                  row.get("field_path", String.class),
                  operator == null ? row.get("operator", String.class) : operator.name(),
                  operator == null ? "" : operator.label(),
                  row.get("value_text", String.class),
                  row.get("value_number", BigDecimal.class),
                  row.get("value_bool", Boolean.class),
                  Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
                  row.get("remark", String.class));
            });
  }

  private List<AutomationRuleActionRow> actionRows(UUID ruleId) {
    return sql.fetch(
            "select id,seq,action_type,target_ref,params::text as params,enabled,remark"
                + " from platform_automation_rule_action where rule_id = ? order by seq",
            ruleId)
        .map(
            row -> {
              var actionType = AutomationRuleActionType.parse(row.get("action_type", String.class));
              return new AutomationRuleActionRow(
                  row.get("id", UUID.class),
                  row.get("seq", Integer.class),
                  actionType == null ? row.get("action_type", String.class) : actionType.name(),
                  actionType == null ? "" : actionType.label(),
                  row.get("target_ref", String.class),
                  row.get("params", String.class),
                  Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
                  row.get("remark", String.class));
            });
  }

  private List<AutomationRuleScopeRow> scopeRows(UUID ruleId) {
    return sql.fetch(
            "select id,scope_type,scope_ref,scope_label from platform_automation_rule_scope"
                + " where rule_id = ? order by case scope_type when 'TENANT' then 0"
                + " when 'SHOP' then 1 else 2 end, scope_label, scope_ref",
            ruleId)
        .map(
            row -> {
              var scopeType = AutomationRuleScopeType.parse(row.get("scope_type", String.class));
              return new AutomationRuleScopeRow(
                  row.get("id", UUID.class),
                  scopeType == null ? row.get("scope_type", String.class) : scopeType.name(),
                  scopeType == null ? "" : scopeType.label(),
                  row.get("scope_ref", UUID.class),
                  row.get("scope_label", String.class));
            });
  }

  /** 条件 / 动作 / 范围**整体替换**（改配置 = 说清「现在这条规则长什么样」）。 */
  private void replaceChildren(
      UUID ruleId,
      List<AutomationRuleConditionInput> conditions,
      List<AutomationRuleActionInput> actions,
      List<AutomationRuleScopeInput> scopes) {
    sql.execute("delete from platform_automation_rule_condition where rule_id = ?", ruleId);
    sql.execute("delete from platform_automation_rule_action where rule_id = ?", ruleId);
    sql.execute("delete from platform_automation_rule_scope where rule_id = ?", ruleId);
    insertChildren(ruleId, conditions, actions, scopes);
  }

  private void insertChildren(
      UUID ruleId,
      List<AutomationRuleConditionInput> conditions,
      List<AutomationRuleActionInput> actions,
      List<AutomationRuleScopeInput> scopes) {
    var now = dbNow();
    var seq = 0;
    for (var condition : conditions) {
      seq++;
      var operator = AutomationRuleConditionOperator.parse(condition.operator());
      check(operator != null, "条件操作符取值不合法");
      var fieldPath = required(condition.fieldPath(), "条件字段路径", 200);
      var valueText = optional(condition.valueText(), "条件值", 500);
      var valueNumber = condition.valueNumber();
      var valueBool = condition.valueBool();
      check(
          operator != AutomationRuleConditionOperator.GT
              && operator != AutomationRuleConditionOperator.GE
              && operator != AutomationRuleConditionOperator.LT
              && operator != AutomationRuleConditionOperator.LE
              || valueNumber != null,
          "大小比较类操作符必须给一个数值条件值");
      check(
          operator != AutomationRuleConditionOperator.BETWEEN
              || (valueText.contains("~") && !valueText.trim().isEmpty()),
          "区间比较需要两个值（用 ~ 分隔，如 100~500）");
      check(
          operator != AutomationRuleConditionOperator.IN || !valueText.trim().isEmpty(),
          "「属于」至少要有一个候选值（逗号分隔）");
      check(
          operator != AutomationRuleConditionOperator.IS_NULL
              && operator != AutomationRuleConditionOperator.NOT_NULL
              && operator != AutomationRuleConditionOperator.EXISTS
              || (valueText.isEmpty() && valueNumber == null && valueBool == null),
          "空值类操作符不需要条件值");
      sql.execute(
          "insert into platform_automation_rule_condition(id,tenant_id,rule_id,seq,field_path,"
              + " operator,value_text,value_number,value_bool,enabled,remark,created_at)"
              + " values(?,?,?,?,?,?,?,?::numeric,?,?,?,?::timestamptz)",
          UUID.randomUUID(),
          currentTenant(),
          ruleId,
          condition.seq() == null ? seq : condition.seq(),
          fieldPath,
          operator.name(),
          valueText,
          valueNumber,
          valueBool,
          condition.enabled() == null || condition.enabled(),
          optional(condition.remark(), "备注", 500),
          now);
    }
    seq = 0;
    for (var action : actions) {
      seq++;
      var actionType = AutomationRuleActionType.parse(action.actionType());
      check(actionType != null, "动作类型取值不合法");
      var params = normalizeParams(action.params());
      sql.execute(
          "insert into platform_automation_rule_action(id,tenant_id,rule_id,seq,action_type,"
              + " target_ref,params,enabled,remark,created_at)"
              + " values(?,?,?,?,?,?,?::jsonb,?,?,?::timestamptz)",
          UUID.randomUUID(),
          currentTenant(),
          ruleId,
          action.seq() == null ? seq : action.seq(),
          actionType.name(),
          optional(action.targetRef(), "动作目标", 200),
          params,
          action.enabled() == null || action.enabled(),
          optional(action.remark(), "备注", 500),
          now);
    }
    for (var scope : scopes) {
      var scopeType = AutomationRuleScopeType.parse(scope.scopeType());
      check(scopeType != null, "启用范围类别只能是 TENANT / SHOP / WAREHOUSE");
      check(
          scopeType != AutomationRuleScopeType.TENANT || scope.scopeRef() == null,
          "整个租户的范围**不能**带引用");
      check(
          scopeType == AutomationRuleScopeType.TENANT || scope.scopeRef() != null,
          "指定店铺 / 仓库的范围**必须**带引用 —— 「范围是店铺」却没写是哪家店等于没有范围");
      sql.execute(
          "insert into platform_automation_rule_scope(id,tenant_id,rule_id,scope_type,scope_ref,"
              + " scope_label,created_at) values(?,?,?,?,?::uuid,?,?::timestamptz)",
          UUID.randomUUID(),
          currentTenant(),
          ruleId,
          scopeType.name(),
          scope.scopeRef(),
          optional(scope.scopeLabel(), "范围名称", 120),
          now);
    }
  }

  /** 动作参数：必须是 JSON **对象**（不是数组、不是标量）。 */
  private String normalizeParams(String raw) {
    var text = raw == null || raw.isBlank() ? "{}" : raw.trim();
    JsonNode node;
    try {
      node = json.readTree(text);
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "动作参数不是合法的 JSON");
    }
    if (node == null || !node.isObject()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "动作参数必须是一个 JSON 对象");
    }
    return node.toString();
  }

  // ================================================================ 内部：预演求值

  /** 解析样本载荷（非法 JSON ⇒ 400，不当成「空载荷」）。 */
  private JsonNode parsePayload(String raw) {
    if (raw == null || raw.isBlank()) return json.createObjectNode();
    try {
      return json.readTree(raw.trim());
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "样本载荷不是合法的 JSON");
    }
  }

  /** 按点号路径取字段（支持数组下标）。 */
  private static JsonNode resolvePath(JsonNode root, String path) {
    var current = root;
    for (var segment : path.split("\\.")) {
      if (current == null || current.isMissingNode()) return null;
      if (current.isArray()) {
        int index;
        try {
          index = Integer.parseInt(segment);
        } catch (NumberFormatException e) {
          return null;
        }
        if (index < 0 || index >= current.size()) return null;
        current = current.get(index);
        continue;
      }
      if (!current.isObject()) return null;
      current = current.get(segment);
    }
    return current == null || current.isMissingNode() ? null : current;
  }

  /** 条件求值（逐条给出命中与否与原因）。 */
  private AutomationRuleConditionEvaluation evaluate(AutomationRuleConditionRow condition, JsonNode payload) {
    var operator = AutomationRuleConditionOperator.parse(condition.operator());
    var node = resolvePath(payload, condition.fieldPath());
    var actual = node == null ? "" : (node.isValueNode() ? node.asText() : node.toString());
    var expected = condition.valueText();
    if (condition.valueNumber() != null) expected = condition.valueNumber().toPlainString();
    if (condition.valueBool() != null) expected = String.valueOf(condition.valueBool());
    boolean matched;
    String reason;
    if (operator == null) {
      matched = false;
      reason = "操作符取值不合法";
    } else {
      switch (operator) {
        case EXISTS -> {
          matched = node != null;
          reason = matched ? "字段存在" : "载荷里没有这个字段";
        }
        case IS_NULL -> {
          matched = node == null || node.isNull();
          reason = matched ? "字段为空或不存在" : "字段有值";
        }
        case NOT_NULL -> {
          matched = node != null && !node.isNull();
          reason = matched ? "字段有值" : "字段为空或不存在";
        }
        case EQ -> {
          matched = equalsValue(node, condition);
          reason = matched ? "取值相等" : "取值不等";
        }
        case NE -> {
          matched = !equalsValue(node, condition);
          reason = matched ? "取值不等" : "取值相等";
        }
        case GT, GE, LT, LE -> {
          var comparison = compareNumbers(node, condition);
          matched =
              switch (operator) {
                case GT -> comparison > 0;
                case GE -> comparison >= 0;
                case LT -> comparison < 0;
                default -> comparison <= 0;
              };
          reason = matched ? "数值比较成立" : "数值比较不成立";
        }
        case CONTAINS -> {
          matched = node != null && node.isValueNode() && node.asText().contains(expected);
          reason = matched ? "文本包含目标值" : "文本不包含目标值";
        }
        case STARTS_WITH -> {
          matched = node != null && node.isValueNode() && node.asText().startsWith(expected);
          reason = matched ? "文本以目标值开头" : "文本不以目标值开头";
        }
        case ENDS_WITH -> {
          matched = node != null && node.isValueNode() && node.asText().endsWith(expected);
          reason = matched ? "文本以目标值结尾" : "文本不以目标值结尾";
        }
        case IN -> {
          var candidates = expected.split(",");
          matched = false;
          for (var candidate : candidates) {
            if (node != null && node.isValueNode() && node.asText().equals(candidate.trim())) {
              matched = true;
              break;
            }
          }
          reason = matched ? "取值属于候选集" : "取值不属于候选集";
        }
        case BETWEEN -> {
          var parts = expected.split("~");
          if (parts.length != 2) {
            matched = false;
            reason = "区间值格式不对（需要两个值，用 ~ 分隔）";
          } else {
            var low = compareNumbers(node, condition, parts[0].trim());
            var high = compareNumbers(node, condition, parts[1].trim());
            matched = low >= 0 && high <= 0;
            reason = matched ? "取值落在区间内" : "取值不在区间内";
          }
        }
        default -> {
          matched = false;
          reason = "操作符暂不支持";
        }
      }
    }
    return new AutomationRuleConditionEvaluation(
        condition.seq(),
        condition.fieldPath(),
        condition.operator(),
        operator == null ? "" : operator.label(),
        expected,
        actual,
        matched,
        reason);
  }

  /** 相等比较（按实际值类型选比较口径）。 */
  private static boolean equalsValue(JsonNode node, AutomationRuleConditionRow condition) {
    if (node == null) return condition.valueText().isEmpty() && condition.valueNumber() == null;
    if (node.isNumber()) {
      if (condition.valueNumber() != null) {
        return node.decimalValue().compareTo(condition.valueNumber()) == 0;
      }
      try {
        return node.decimalValue().compareTo(new BigDecimal(condition.valueText().trim())) == 0;
      } catch (NumberFormatException e) {
        return false;
      }
    }
    if (node.isBoolean()) {
      if (condition.valueBool() != null) return node.asBoolean() == condition.valueBool();
      return node.asText().equalsIgnoreCase(condition.valueText().trim());
    }
    return node.asText().equals(condition.valueText());
  }

  /** 数值比较（返回 &gt;0 / =0 / &lt;0；不可比时按「不成立」处理）。 */
  private static int compareNumbers(JsonNode node, AutomationRuleConditionRow condition) {
    return compareNumbers(node, condition, condition.valueNumber() == null ? null : condition.valueNumber().toPlainString());
  }

  private static int compareNumbers(
      JsonNode node, AutomationRuleConditionRow condition, String raw) {
    if (node == null || !node.isNumber() || raw == null || raw.isBlank()) return Integer.MIN_VALUE;
    try {
      return node.decimalValue().compareTo(new BigDecimal(raw.trim()));
    } catch (NumberFormatException e) {
      return Integer.MIN_VALUE;
    }
  }

  /** 范围命中（真约束的判定）。 */
  private ScopeHit scopeHit(List<AutomationRuleScopeRow> scopes, UUID shopId, UUID warehouseId) {
    for (var scope : scopes) {
      if ("TENANT".equals(scope.scopeType())) {
        return new ScopeHit(true, "范围是整个租户");
      }
    }
    if (shopId != null) {
      for (var scope : scopes) {
        if ("SHOP".equals(scope.scopeType()) && shopId.equals(scope.scopeRef())) {
          return new ScopeHit(true, "范围命中指定店铺");
        }
      }
    }
    if (warehouseId != null) {
      for (var scope : scopes) {
        if ("WAREHOUSE".equals(scope.scopeType()) && warehouseId.equals(scope.scopeRef())) {
          return new ScopeHit(true, "范围命中指定仓库");
        }
      }
    }
    if (scopes.isEmpty()) {
      return new ScopeHit(false, "这条规则**没有配启用范围** —— 它对任何店铺 / 仓库都不生效");
    }
    return new ScopeHit(false, "范围没有命中给定的店铺 / 仓库");
  }

  private record ScopeHit(boolean applicable, String note) {}

  // ================================================================ 行映射

  private static AutomationRuleHealth healthOf(boolean failing, String lastRunStatus) {
    if (failing) return AutomationRuleHealth.FAILING;
    if (lastRunStatus == null || lastRunStatus.isBlank()) return AutomationRuleHealth.NEVER_RUN;
    return switch (lastRunStatus) {
      case "SUCCEEDED" -> AutomationRuleHealth.HEALTHY;
      case "FAILED", "TIMEOUT" -> AutomationRuleHealth.DEGRADED;
      case "SKIPPED" -> AutomationRuleHealth.SKIPPED;
      default -> AutomationRuleHealth.NEVER_RUN;
    };
  }

  private static AutomationRuleRow toRuleRow(org.jooq.Record row) {
    var status = AutomationRuleStatus.parse(row.get("status", String.class));
    var lastRunStatus = row.get("last_run_status", String.class);
    var failing = Boolean.TRUE.equals(row.get("failing", Boolean.class));
    var health = healthOf(failing, lastRunStatus);
    var dryRun = Boolean.TRUE.equals(row.get("dry_run", Boolean.class));
    var mode = dryRun ? AutomationRuleSideEffectMode.SIMULATE : AutomationRuleSideEffectMode.EXECUTE;
    var runStatusLabel =
        lastRunStatus == null || lastRunStatus.isBlank()
            ? "尚未执行"
            : switch (lastRunStatus) {
                case "SUCCEEDED" -> AutomationRuleRunStatus.SUCCEEDED.label();
                case "FAILED" -> AutomationRuleRunStatus.FAILED.label();
                case "SKIPPED" -> AutomationRuleRunStatus.SKIPPED.label();
                case "TIMEOUT" -> AutomationRuleRunStatus.TIMEOUT.label();
                default -> lastRunStatus;
              };
    return new AutomationRuleRow(
        row.get("id", UUID.class),
        row.get("name", String.class),
        row.get("description", String.class),
        row.get("trigger_event_type", String.class),
        row.get("trigger_schema_version", Integer.class),
        row.get("priority", Integer.class),
        Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
        status == null ? row.get("status", String.class) : status.name(),
        status == null ? "" : status.label(),
        dryRun,
        mode.name(),
        mode.label(),
        row.get("consecutive_failure_threshold", Integer.class),
        row.get("condition_count", Long.class),
        row.get("action_count", Long.class),
        row.get("scope_count", Long.class),
        row.get("last_run_at", OffsetDateTime.class),
        lastRunStatus == null ? "" : lastRunStatus,
        runStatusLabel,
        row.get("last_run_failure_reason", String.class),
        row.get("last_run_execution_id", UUID.class),
        row.get("run_count", BigDecimal.class),
        row.get("success_count", BigDecimal.class),
        row.get("failure_count", BigDecimal.class),
        row.get("consecutive_failure_count", Integer.class),
        failing,
        row.get("failing_since", OffsetDateTime.class),
        health.name(),
        health.label(),
        row.get("remark", String.class),
        row.get("created_by", UUID.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  private static AutomationRuleExecutionRow toExecutionRow(org.jooq.Record row) {
    var status = AutomationRuleRunStatus.parse(row.get("status", String.class));
    var source = AutomationRuleExecutionSource.parse(row.get("source", String.class));
    return new AutomationRuleExecutionRow(
        row.get("id", UUID.class),
        row.get("rule_id", UUID.class),
        row.get("rule_name", String.class),
        row.get("run_no", Integer.class),
        status == null ? row.get("status", String.class) : status.name(),
        status == null ? "" : status.label(),
        source == null ? row.get("source", String.class) : source.name(),
        source == null ? "" : source.label(),
        Boolean.TRUE.equals(row.get("dry_run", Boolean.class)),
        Boolean.TRUE.equals(row.get("side_effects_applied", Boolean.class)),
        row.get("triggered_by", String.class),
        row.get("trigger_event_type", String.class),
        row.get("matched_conditions", BigDecimal.class),
        row.get("planned_actions", BigDecimal.class),
        row.get("executed_actions", BigDecimal.class),
        row.get("failure_reason", String.class),
        row.get("failure_class", String.class),
        row.get("started_at", OffsetDateTime.class),
        row.get("finished_at", OffsetDateTime.class),
        row.get("created_by", UUID.class),
        row.get("created_at", OffsetDateTime.class));
  }
}
