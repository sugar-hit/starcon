package com.starcon.platform;

import static com.starcon.platform.AutomationRuleEditorModels.*;
import static com.starcon.platform.AutomationRuleModels.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * 18.3.1.2「规则编辑器」的服务层。
 *
 * <p>蓝图 18-integration-and-automation.md 原文：
 *
 * <blockquote>
 *
 * <b>职责</b>：可视化编辑规则：选择触发事件 → 配置条件（字段/操作符/值）→
 * 编排动作（调接口/改状态/建任务/发通知/生成单据）→ 测试与保存。<br>
 * <b>输入</b>：规则配置；测试样本数据。<br>
 * <b>输出</b>：规则定义；测试运行结果。<br>
 * <b>关系</b>：上游 = 18.3.1.1；下游 = 18.3.2 执行。<b>规则必须支持用样本测试</b>，否则只能在生产上试错。
 *
 * </blockquote>
 *
 * <p><b>七条纪律：</b>
 *
 * <ol>
 *   <li><b>🔴 规则档案不另起一套</b>：条件 / 动作 / 启用范围三张表由 18.3.1.1 拥有，
 *       编辑器保存时**委托**给 {@link AutomationRuleService#saveRule} ——
 *       两套规则表意味着「编辑器的规则」与「规则列表的规则」会分叉，
 *       而分叉出来的规则永远不会出现在执行引擎的视野里。
 *   <li><b>🔴 测试必须真求值</b>：条件判定复用 18.3.1.1 的预演求值（同一套操作符语义），
 *       样本载荷真的进条件求值器；台账里存着**配置快照 + 样本载荷 + 逐条明细** ⇒ 可重放。
 *   <li><b>🔴 测试不产生副作用</b>：18.3.2 执行引擎尚未交付 ⇒ 测试只求值、只给动作计划，
 *       {@code sideEffectsApplied} 恒为 false；台账表里**连这个列都没有**。
 *   <li><b>🔴 字段目录来自真实上游</b>：条件可选的字段来自平台事件注册表的
 *       {@code payload_schema}（JSON Schema）**摊平**，不是本模块写死的一张清单；
 *       事件没登记字段结构 ⇒ 目录为空并**如实说明**。
 *   <li><b>🔴 动作目标能查证</b>：「调接口」动作的目标必须在 18.1.1.2 的**活 spec 接口目录**里
 *       真实存在（目录读不到时如实说读不到，不假装校验过）。
 *   <li><b>🔴 条件与动作是真校验</b>：字段路径必须在目录里（事件登记了 schema 时）、
 *       操作符必须与字段类型相符、动作参数必须符合该动作类型的参数规格 ——
 *       「配一条永远命不中的规则」在服务层就被拒掉。
 *   <li><b>数量一律 int / BigDecimal</b>：库里 {@code NUMERIC(19,6)}，本类<b>一个 double 都没有</b>。
 * </ol>
 */
@Service
public class AutomationRuleEditorService {

  /** 一次测试最多允许多少个条件 / 动作 / 范围（与 18.3.1.1 同一上限）。 */
  static final int MAX_CHILDREN = 200;

  /** 测试历史一次最多返回多少行。 */
  static final int MAX_TEST_RUNS = 200;

  private final DSLContext sql;
  private final ObjectMapper json;
  private final AutomationRuleService rules;
  private final ApiCatalogService catalog;

  public AutomationRuleEditorService(
      DSLContext sql, ObjectMapper json, AutomationRuleService rules, ApiCatalogService catalog) {
    this.sql = sql;
    this.json = json;
    this.rules = rules;
    this.catalog = catalog;
  }

  // ================================================================ 工具

  private static void check(boolean condition, String message) {
    if (!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static void notFound(String message) {
    throw new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static String optional(String value, String label, int max) {
    var trimmed = value == null ? "" : value.trim();
    check(trimmed.length() <= max, label + "长度不能超过 " + max + " 个字符");
    return trimmed;
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

  // ================================================================ 能力声明

  /**
   * 18.3.1.2 的能力声明：逐维说清「有没有、依据是什么、缺了会怎样」。
   *
   * <p>🔴 **动作的真实执行如实答「未交付」** —— 18.3.2 是**下游**模块，
   * 本模块**不冒充**它：样本测试只**求值**条件并给出动作计划。
   */
  public AutomationRuleEditorCapabilities capabilities() {
    var capabilities =
        List.of(
            new AutomationRuleEditorCapability(
                "18.3.1.2",
                "选择触发事件",
                true,
                "读 platform_webhook_event_type 注册表（18.1.2.1 交付）；"
                    + "编辑器只列出**注册过**的事件类型，未登记的事件配不出规则",
                "",
                "事件注册表是租户级的：没登记过的事件类型**配不出**规则"),
            new AutomationRuleEditorCapability(
                "18.3.1.2",
                "🔴 **条件字段目录**（从真实 schema 摊平）",
                true,
                "把 platform_webhook_event_type.payload_schema 的 JSON Schema **摊平**成字段列表"
                    + "（路径 / 类型 / 必填 / 枚举 / 该类型上说得通的操作符）；"
                    + "目录为空时如实标注「事件没登记字段结构」",
                "",
                "字段目录**不是**本模块写死的一张清单 —— "
                    + "事件换了 schema，目录跟着变（事件注册表本身禁止原地改 schema）"),
            new AutomationRuleEditorCapability(
                "18.3.1.2",
                "条件编排（字段 / 操作符 / 值）",
                true,
                "复用 18.3.1.1 的 platform_automation_rule_condition 表与受控操作符；"
                    + "服务端**真校验**：字段路径必须在目录里（事件登记了 schema 时）、"
                    + "操作符必须与字段类型相符",
                "",
                "值拆成 value_text / value_number / value_bool 三列 —— "
                    + "把数字塞进文本再比大小，早晚踩到字符串比较的坑"),
            new AutomationRuleEditorCapability(
                "18.3.1.2",
                "🔴 **动作编排**（五类：调接口 / 改状态 / 建任务 / 发通知 / 生成单据）",
                true,
                "动作类型目录（每个类型带参数规格：名字 / 类型 / 必填 / 枚举）与 18.3.1.1 的"
                    + "platform_automation_rule_action 表；服务端**真校验**：动作类型必须是受控枚举、"
                    + "参数必须是 JSON 对象、必填参数必须给、已给参数的类型必须对",
                "",
                "动作**顺序**由 seq 决定（多步 ⇒ 有序，不是集合）"),
            new AutomationRuleEditorCapability(
                "18.3.1.2",
                "🔴 **「调接口」动作的目标来自活 spec 接口目录**",
                true,
                "读 18.1.1.2 的接口目录（各服务 OpenAPI spec 的投影）；"
                    + "「调接口」动作的 target_ref 必须指向目录里**真实存在**的接口",
                "",
                "接口目录读不到时如实答「读不到」，**不假装**校验过 —— "
                    + "那正是「点了没反应」的开始"),
            new AutomationRuleEditorCapability(
                "18.3.1.2",
                "🔴 **样本测试**（规则必须支持用样本测试）",
                true,
                "POST /platform/automation-rule-editor/test：条件**真求值**（复用 18.3.1.1 的"
                    + "预演求值），逐条给出命中 / 未命中与原因；"
                    + "结果落 platform_automation_rule_test_run（配置快照 + 样本载荷 + 逐条明细）",
                "",
                "**测试不产生副作用**：18.3.2 执行引擎尚未交付 ⇒ "
                    + "sideEffectsApplied 恒为 false，台账表里连这个列都没有"),
            new AutomationRuleEditorCapability(
                "18.3.1.2",
                "保存规则定义",
                true,
                "POST /platform/automation-rule-editor/save 委托 18.3.1.1 的登记入口"
                    + "（规则 / 条件 / 动作 / 启用范围四张表**只有一个写入所有者**）",
                "",
                "编辑器**不另建一套规则表** —— 两套规则表必然分叉"),
            new AutomationRuleEditorCapability(
                "18.3.1.2",
                "🔴 **动作的真实执行**（真的调接口 / 改状态 / 建任务 / 发通知 / 生成单据）",
                false,
                "本仓**不存在**规则执行引擎（backend / services 下没有动作执行器），"
                    + "18.3.2「执行」尚未交付",
                "18.3.2",
                "样本测试**只求值、只给动作计划** —— 一个「点了必然失败」的按钮会让运营"
                    + "以为「点一下就能跑」"),
            new AutomationRuleEditorCapability(
                "18.3.1.2",
                "通知模板 / 任务类型 / 单据类型的目标目录",
                false,
                "这些登记在**别的服务**（通知模板、identity 的单据编号规则），"
                    + "按模块边界纪律**不跨库查**",
                "18.5.1.1",
                "编辑器给的是**受控词表 + 自由引用**：目标写清楚约定，"
                    + "由 18.3.2 的执行器按各服务自己的登记解析"));
    var gaps =
        List.of(
            new AutomationRuleEditorGap(
                "18.3.1.1",
                "规则列表（档案 / 条件 / 动作 / 启用范围 / 试运行 / 执行统计）",
                "platform",
                true,
                "上游 = 18.3.1.1",
                "编辑器**复用**它的规则档案与条件求值，不另起一套"),
            new AutomationRuleEditorGap(
                "18.3.2.1",
                "执行日志（全局执行明细 + 手动重跑）",
                "platform",
                false,
                "下游 = 18.3.2.1",
                "本模块的台账是**样本测试**记录，不是执行记录 —— "
                    + "两者不能混：测试没跑过动作"),
            new AutomationRuleEditorGap(
                "18.3.2.2",
                "规则效果（成功率 / 误触发率 / 优化建议）",
                "platform",
                false,
                "下游 = 18.3.2.2",
                "样本测试的命中率**不等于**线上成功率 —— 前者是「这份样本过不过」"),
            new AutomationRuleEditorGap(
                "18.5.1.1",
                "通知模板目录",
                "notification",
                false,
                "跨服务只读引用",
                "「发通知」动作的目标是**自由引用** + 受控词表，"
                    + "由执行器在通知服务里解析"));
    return new AutomationRuleEditorCapabilities(capabilities, gaps);
  }

  // ================================================================ 字段目录（真实上游）

  /**
   * 🔴 **触发事件的条件字段目录**（每个已登记事件一份）。
   *
   * <p>字段来自 {@code platform_webhook_event_type.payload_schema} 的 JSON Schema
   * **摊平** —— 不是本模块写死的清单。
   */
  public List<AutomationRuleEditorEventSchema> eventSchemas() {
    return sql.fetch(
            "select event_type,schema_version,status,source_service,source_artifact,"
                + " payload_schema::text as payload_schema"
                + " from platform_webhook_event_type order by event_type, schema_version desc")
        .map(row -> toEventSchema(row));
  }

  /**
   * 一个事件的字段目录（按事件类型 + 版本）。
   *
   * <p>🔴 **不带版本时取 ACTIVE 的最高版本** —— 与 18.3.1.1 的
   * {@code resolveTriggerEvent} 同一判据：编辑器展示的字段目录必须是
   * 「配规则时真正会用上的那一版」，而不是一个已废弃版本的目录。
   */
  public AutomationRuleEditorEventSchema eventSchema(String eventType, Integer schemaVersion) {
    var where = new StringBuilder(" where event_type = ?");
    var params = new ArrayList<Object>();
    params.add(eventType == null ? "" : eventType.trim());
    if (schemaVersion != null) {
      where.append(" and schema_version = ?");
      params.add(schemaVersion);
    }
    where.append(" order by (status = 'ACTIVE') desc, schema_version desc limit 1");
    var row =
        sql.fetchOne(
            "select event_type,schema_version,status,source_service,source_artifact,"
                + " payload_schema::text as payload_schema"
                + " from platform_webhook_event_type"
                + where,
            params.toArray());
    if (row == null) notFound("事件类型 " + eventType + " 没有在事件注册表里登记过");
    return toEventSchema(row);
  }

  /**
   * 🔴 **草稿用的触发事件解析** —— 与 18.3.1.1 的 {@code resolveTriggerEvent} 同一判据。
   *
   * <p>区别只在**错误码**：这里解析的是「用户正在编的草稿」，所以
   * 「这个事件没登记过」是 <b>400</b>（草稿写错了），而不是 404（资源不存在）。
   *
   * <p>并且**必须是 ACTIVE 的那一版** —— 配一条永远不会被触发的规则没有意义。
   */
  private AutomationRuleEditorEventSchema activeEventSchema(
      String eventType, Integer schemaVersion) {
    var where = new StringBuilder(" where event_type = ?");
    var params = new ArrayList<Object>();
    params.add(eventType == null ? "" : eventType.trim());
    if (schemaVersion != null) {
      where.append(" and schema_version = ?");
      params.add(schemaVersion);
    }
    where.append(" and status = 'ACTIVE'");
    where.append(" order by schema_version desc limit 1");
    var row =
        sql.fetchOne(
            "select event_type,schema_version,status,source_service,source_artifact,"
                + " payload_schema::text as payload_schema"
                + " from platform_webhook_event_type"
                + where,
            params.toArray());
    if (row == null) {
      check(
          false,
          "触发事件 "
              + eventType
              + (schemaVersion == null ? "" : " 第 " + schemaVersion + " 版")
              + " 没有在事件注册表里登记过「可用」的版本 —— "
              + "配一条永远不会被触发的规则没有意义；先在事件订阅里登记这个事件，再回来配规则");
    }
    return toEventSchema(row);
  }

  /** 一行事件注册表 → 字段目录模型。 */
  private AutomationRuleEditorEventSchema toEventSchema(org.jooq.Record row) {
    var schema = readJson(row.get("payload_schema", String.class));
    var declared = schema != null && schema.isObject() && schema.size() > 0;
    return new AutomationRuleEditorEventSchema(
        row.get("event_type", String.class),
        row.get("schema_version", Integer.class),
        row.get("status", String.class),
        row.get("source_service", String.class),
        row.get("source_artifact", String.class),
        declared,
        declared
            ? "字段目录来自事件注册表登记的 payload_schema（JSON Schema 摊平）。"
            : "这个事件**没有登记字段结构**（payload_schema 是空的）⇒ 字段目录为空；"
                + "条件仍可自由写字段路径，但没有任何字段可以对照校验。",
        declared ? flattenSchema(schema) : List.of());
  }

  // ================================================================ 动作目录

  /**
   * 🔴 **动作类型目录**（蓝图点名的五类）。
   *
   * <p>动作类型与 18.3.1.1 的受控枚举**逐字一致**（同一个
   * {@link AutomationRuleActionType}），参数规格是**本模块对动作的契约** ——
   * 编辑器据此做真校验（必填参数、参数类型、枚举取值）。
   */
  public AutomationRuleEditorActionCatalog actionCatalog() {
    var specs = new LinkedHashMap<String, List<AutomationRuleEditorParamSpec>>();
    specs.put(
        AutomationRuleActionType.CALL_API.name(),
        List.of(
            param("query", "查询参数", "OBJECT", false, "要带上的查询参数（JSON 对象）", List.of()),
            param("body", "请求体", "OBJECT", false, "要带上的请求体（JSON 对象）", List.of()),
            param("headers", "自定义请求头", "OBJECT", false, "额外请求头（JSON 对象）", List.of())));
    specs.put(
        AutomationRuleActionType.UPDATE_STATUS.name(),
        List.of(
            param(
                "status",
                "目标状态",
                "TEXT",
                true,
                "要写成的状态值（如 PAID / SHIPPED）",
                List.of()),
            param("entity", "业务对象", "TEXT", false, "要改哪个对象（如 trade.order）", List.of()),
            param("reason", "改状态的理由", "TEXT", false, "写清为什么改（留痕用）", List.of())));
    specs.put(
        AutomationRuleActionType.CREATE_TASK.name(),
        List.of(
            param("title", "任务标题", "TEXT", false, "任务的标题", List.of()),
            param("assignee", "负责人", "TEXT", false, "任务负责人", List.of()),
            param("dueAt", "截止时间", "TEXT", false, "截止时间（ISO-8601 带时区）", List.of()),
            param("priority", "优先级", "TEXT", false, "任务的优先级", List.of("LOW", "NORMAL", "HIGH"))));
    specs.put(
        AutomationRuleActionType.SEND_NOTIFICATION.name(),
        List.of(
            param(
                "channel",
                "通知渠道",
                "TEXT",
                true,
                "通知走哪个渠道",
                List.of("EMAIL", "SMS", "INAPP")),
            param("templateCode", "模板编码", "TEXT", false, "要用的通知模板编码", List.of()),
            param("recipients", "收件人", "TEXT_LIST", false, "收件人列表", List.of()),
            param("variables", "模板变量", "OBJECT", false, "模板变量（JSON 对象）", List.of())));
    specs.put(
        AutomationRuleActionType.CREATE_DOCUMENT.name(),
        List.of(
            param(
                "documentType",
                "单据类型",
                "TEXT",
                true,
                "要生成哪种单据（如 trade.order）",
                List.of()),
            param("fields", "单据字段", "OBJECT", false, "单据上的字段（JSON 对象）", List.of()),
            param("remark", "备注", "TEXT", false, "单据备注", List.of())));

    var descriptions = Map.of(
        AutomationRuleActionType.CALL_API.name(),
            "调接口：调用一个接口目录里真实存在的接口（本仓的活 spec 投影）。",
        AutomationRuleActionType.UPDATE_STATUS.name(),
            "改状态：把某个业务对象的状态改掉（目标由各业务服务自己解析）。",
        AutomationRuleActionType.CREATE_TASK.name(),
            "建任务：给责任人派一条待办（任务落在任务服务，本模块只写清怎么建）。",
        AutomationRuleActionType.SEND_NOTIFICATION.name(),
            "发通知：按模板与渠道发一条通知（模板在通知服务，本模块只写清怎么发）。",
        AutomationRuleActionType.CREATE_DOCUMENT.name(),
            "生成单据：生成一张业务单据（单据类型由各业务服务自己解析）。");
    var targetLabels = Map.of(
        AutomationRuleActionType.CALL_API.name(),
            "接口目录里的接口（如 platform GET /catalog/spus）",
        AutomationRuleActionType.UPDATE_STATUS.name(),
            "业务对象引用（如 trade.order）",
        AutomationRuleActionType.CREATE_TASK.name(),
            "任务类型引用（如 task.replenish）",
        AutomationRuleActionType.SEND_NOTIFICATION.name(),
            "通知模板引用（如 tpl.stock.low）",
        AutomationRuleActionType.CREATE_DOCUMENT.name(),
            "单据类型引用（如 trade.order）");

    var types = new ArrayList<AutomationRuleEditorActionType>();
    for (var actionType : AutomationRuleActionType.values()) {
      types.add(
          new AutomationRuleEditorActionType(
              actionType.name(),
              actionType.label(),
              descriptions.get(actionType.name()),
              targetLabels.get(actionType.name()),
              specs.getOrDefault(actionType.name(), List.of())));
    }
    return new AutomationRuleEditorActionCatalog(List.copyOf(types));
  }

  // ================================================================ 接口目录（活 spec）

  /**
   * 🔴 **「调接口」动作可选的目标接口**。
   *
   * <p>来自 18.1.1.2 的接口目录（各服务 OpenAPI spec 的**投影**）——
   * 目录读不到时如实答「读不到」，**不**悄悄变成空清单。
   */
  public AutomationRuleEditorApiCatalog apiCatalog() {
    var all = catalog.allInterfaces();
    if (all.isEmpty()) {
      return new AutomationRuleEditorApiCatalog(
          List.of(),
          false,
          "接口目录**暂时读不到**（各服务的 OpenAPI spec 不可达）⇒ "
              + "「调接口」动作的目标暂时**无法校验**，但仍可写。");
    }
    var endpoints =
        all.stream()
            .map(
                item ->
                    new AutomationRuleEditorApiEndpoint(
                        item.service(),
                        item.method(),
                        item.path(),
                        item.summary(),
                        item.domain()))
            .toList();
    return new AutomationRuleEditorApiCatalog(
        endpoints,
        true,
        "共 " + endpoints.size() + " 个接口，来自各服务的活 OpenAPI spec（不是手写清单）。");
  }

  // ================================================================ 保存（委托 18.3.1.1）

  /**
   * 🔴 **保存规则定义** —— 委托给 18.3.1.1 的登记入口。
   *
   * <p>编辑器**不另建规则表**：条件 / 动作 / 启用范围三张表的写入所有者始终是 18.3.1.1。
   */
  public AutomationRuleDetail save(AutomationRuleInput input, AccessService.Access actor) {
    validateDraft(input);
    return rules.saveRule(input, actor);
  }

  // ================================================================ 样本测试

  /**
   * 🔴 **样本测试**：用样本载荷**真的**求值条件，给出动作计划，落测试台账。
   *
   * <p>蓝图加粗句：「<b>规则必须支持用样本测试</b>，否则只能在生产上试错。」
   *
   * <p>🔴 **不产生任何副作用**：18.3.2 执行引擎尚未交付 ⇒ 只求值、只给计划。
   *
   * @param draft 规则配置（草稿）。{@code rule.id} 有值 ⇒ 测的是**已保存**规则的这一版配置；
   *     为空 ⇒ 测的是**草稿**（规则还没保存）。
   */
  public AutomationRuleEditorTestResult test(
      AutomationRuleEditorTestInput input, AccessService.Access actor) {
    check(input != null, "缺少测试请求");
    var draft = input.rule();
    check(draft != null, "缺少规则配置 —— 没有规则就无从测起");
    validateDraft(draft);

    var payload = parsePayload(input.payload());
    var conditions = draft.conditions() == null ? List.<AutomationRuleConditionInput>of() : draft.conditions();
    var actions = draft.actions() == null ? List.<AutomationRuleActionInput>of() : draft.actions();
    var scopes = draft.scopes() == null ? List.<AutomationRuleScopeInput>of() : draft.scopes();

    // ---- 条件真求值（复用 18.3.1.1 的求值器 ⇒ 操作符语义只有一套）
    var evaluations = new ArrayList<AutomationRuleConditionEvaluation>();
    var matched = 0;
    var total = 0;
    var seq = 0;
    for (var condition : conditions) {
      seq++;
      var row = toConditionRow(condition, seq);
      if (!row.enabled()) continue;
      total++;
      var evaluation = rules.evaluate(row, payload);
      if (evaluation.matched()) matched++;
      evaluations.add(evaluation);
    }
    var conditionsMatched = matched == total;

    // ---- 动作计划（不执行）
    var plan = new ArrayList<AutomationRuleEditorActionPlan>();
    var actionSeq = 0;
    var plannedActions = 0;
    for (var action : actions) {
      actionSeq++;
      var row = toActionRow(action, actionSeq);
      plan.add(row);
      if (row.enabled()) plannedActions++;
    }

    // ---- 启用范围（真约束）
    var scopeHit =
        rules.scopeHit(toScopeRows(scopes), input.shopId(), input.warehouseId());

    // ---- 被测对象：已保存的规则 / 草稿
    var ruleId = resolveRuleId(draft.id());
    var isDraft = ruleId == null;
    var ruleName = draft.name() == null ? "" : draft.name().trim();
    check(!ruleName.isEmpty(), "规则名称不能为空");

    var failureReason =
        conditionsMatched ? "" : describeUnmatched(evaluations, matched, total);

    // ---- 落台账（只追加）
    var runNo = nextRunNo(ruleId);
    var testRunId = UUID.randomUUID();
    var now = dbNow();
    // 🔴 jsonb 列一律**传 JSON 文本**（values 位置写 `?::jsonb`）——
    //   与 18.3.1.1 落动作参数、18.1.2.1 落事件 schema 同一口径（不把节点对象直接塞进参数）。
    var snapshot = configSnapshot(draft).toString();
    var evaluation = evaluationNode(evaluations, plan, scopeHit.applicable(), scopeHit.note()).toString();
    sql.execute(
        "insert into platform_automation_rule_test_run(id,tenant_id,rule_id,rule_name,run_no,"
            + " trigger_event_type,trigger_schema_version,config_snapshot,sample_payload,matched,"
            + " total_conditions,matched_conditions,planned_actions,evaluation,failure_reason,"
            + " created_by,created_at)"
            + " values(?,?,?::uuid,?,?,?,?,?::jsonb,?::jsonb,?,?::numeric,?::numeric,?::numeric,"
            + "?::jsonb,?,?::uuid,?::timestamptz)",
        testRunId,
        currentTenant(),
        ruleId,
        ruleName,
        runNo,
        draft.triggerEventType().trim(),
        resolveSchemaVersion(draft),
        snapshot,
        payload.toString(),
        conditionsMatched,
        java.math.BigDecimal.valueOf(total),
        java.math.BigDecimal.valueOf(matched),
        java.math.BigDecimal.valueOf(plannedActions),
        evaluation,
        failureReason,
        actor.userId(),
        now);

    return new AutomationRuleEditorTestResult(
        testRunId,
        ruleId,
        ruleName,
        isDraft,
        runNo,
        conditionsMatched,
        total,
        matched,
        plannedActions,
        false,
        scopeHit.applicable(),
        scopeHit.note(),
        List.copyOf(evaluations),
        List.copyOf(plan),
        failureReason,
        "本次是**样本测试**：只求值条件、只给动作计划，**不执行任何动作**。"
            + "（规则执行引擎归 18.3.2，尚未交付。）",
        now);
  }

  /** 某条规则（或本租户全部）的测试历史。 */
  public List<AutomationRuleEditorTestRunRow> testRuns(UUID ruleId, int limit) {
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (ruleId != null) {
      // 先确认这条规则**在当前租户名下存在** —— 否则会返回 200 + 空列表，
      // 而「这条规则不是你的」与「这条规则还没测过」是两件事。
      rules.ruleRow(ruleId);
      where.append(" and t.rule_id = ?::uuid");
      params.add(ruleId);
    }
    var size = Math.min(Math.max(limit, 1), MAX_TEST_RUNS);
    params.add(size);
    return sql.fetch(
            "select t.id,t.rule_id,t.rule_name,t.run_no,t.trigger_event_type,"
                + " t.trigger_schema_version,t.matched,t.total_conditions,t.matched_conditions,"
                + " t.planned_actions,t.evaluation::text as evaluation,"
                + " t.config_snapshot::text as config_snapshot,"
                + " t.sample_payload::text as sample_payload,"
                + " t.failure_reason,t.created_by,t.created_at"
                + " from platform_automation_rule_test_run t"
                + where
                + " order by t.created_at desc, t.id desc limit ?",
            params.toArray())
        .map(row -> toTestRunRow(row));
  }

  // ================================================================ 内部：校验

  /**
   * 🔴 **草稿真校验**（保存与测试之前都跑一遍）。
   *
   * <p>「配一条永远命不中的规则」在服务层就被拒掉：
   * 事件必须登记过、条件字段必须在目录里（事件登记了 schema 时）、
   * 操作符必须与字段类型相符、动作类型必须是受控枚举、参数必须符合该类型的规格。
   */
  private void validateDraft(AutomationRuleInput draft) {
    check(draft != null, "缺少规则配置");
    var name = optional(draft.name(), "规则名称", 120);
    check(!name.isEmpty(), "规则名称不能为空");
    var eventType = optional(draft.triggerEventType(), "触发事件", 64);
    check(!eventType.isEmpty(), "必须选一个触发事件");

    // 触发事件必须登记过（且是可用版本）—— 与 18.3.1.1 同一判据。
    var schema = activeEventSchema(eventType, draft.triggerSchemaVersion());

    var conditions = draft.conditions() == null ? List.<AutomationRuleConditionInput>of() : draft.conditions();
    var actions = draft.actions() == null ? List.<AutomationRuleActionInput>of() : draft.actions();
    var scopes = draft.scopes() == null ? List.<AutomationRuleScopeInput>of() : draft.scopes();
    check(conditions.size() <= MAX_CHILDREN, "一条规则最多配 " + MAX_CHILDREN + " 个条件");
    check(actions.size() <= MAX_CHILDREN, "一条规则最多配 " + MAX_CHILDREN + " 个动作");
    check(scopes.size() <= MAX_CHILDREN, "一条规则最多配 " + MAX_CHILDREN + " 个启用范围");

    var byPath = new LinkedHashMap<String, AutomationRuleEditorField>();
    for (var field : schema.fields()) byPath.put(field.path(), field);

    for (var condition : conditions) {
      var path = optional(condition.fieldPath(), "条件字段路径", 200);
      check(!path.isEmpty(), "条件字段路径不能为空");
      var operator = AutomationRuleConditionOperator.parse(condition.operator());
      check(operator != null, "条件操作符取值不合法");
      // 事件登记了字段结构 ⇒ 字段路径必须**在目录里**（否则这条条件永远命中不了）。
      if (schema.schemaDeclared()) {
        var field = byPath.get(path);
        check(
            field != null,
            "字段路径「"
                + path
                + "」不在事件 "
                + eventType
                + " 的字段目录里 —— 这样的条件永远命中不了。"
                + "可用的字段："
                + (byPath.isEmpty() ? "（目录为空）" : String.join("、", byPath.keySet())));
        check(
            field.allowedOperators().contains(operator.name()),
            "字段「"
                + path
                + "」是 "
                + field.type()
                + " 类型，不能用「"
                + operator.label()
                + "」比较 —— 这个类型上说得通的操作符："
                + String.join("、", field.allowedOperators()));
      }
      // 🔴 **条件值的形状**必须与操作符相符 —— 与 18.3.1.1 落库时的判据**逐字一致**。
      //   否则「测试通过」与「保存成功」会给出两套结论（保存时才报错 = 试错在生产上）。
      validateConditionValue(operator, condition);
    }

    var catalogEntry = actionCatalog();
    var apiEndpoints = apiCatalog();
    for (var action : actions) {
      var actionType = AutomationRuleActionType.parse(action.actionType());
      check(actionType != null, "动作类型取值不合法");
      var target = optional(action.targetRef(), "动作目标", 200);
      var params = parseParams(action.params());
      var spec =
          catalogEntry.actionTypes().stream()
              .filter(t -> t.actionType().equals(actionType.name()))
              .findFirst()
              .orElse(null);
      check(spec != null, "动作类型 " + actionType.name() + " 没有参数规格");
      for (var paramSpec : spec.params()) {
        var value = params.get(paramSpec.name());
        if (value == null || value.isNull()) {
          check(
              !paramSpec.required(),
              "动作「"
                  + actionType.label()
                  + "」缺少必填参数「"
                  + paramSpec.label()
                  + "」（"
                  + paramSpec.name()
                  + "）");
          continue;
        }
        checkParamType(paramSpec, value, actionType);
      }
      if (actionType == AutomationRuleActionType.CALL_API) {
        check(!target.isEmpty(), "「调接口」动作必须指定目标接口");
        if (apiEndpoints.available()) {
          var known =
              apiEndpoints.endpoints().stream()
                  .anyMatch(
                      e ->
                          (e.service() + " " + e.method() + " " + e.path()).equals(target)
                              || (e.method() + " " + e.path()).equals(target));
          check(
              known,
              "目标接口「"
                  + target
                  + "」不在接口目录里 —— 接口目录是各服务活 spec 的投影，"
                  + "调一个不存在的接口必然失败");
        }
      }
    }
  }

  /**
   * 🔴 **条件值的形状**必须与操作符相符（与 18.3.1.1 落库时的判据**逐字一致**）。
   *
   * <p>为什么编辑器也要查一遍：保存路径（18.3.1.1）会查，
   * 但**测试路径不经过保存** —— 不查的话，「测试通过」与「保存成功」
   * 会给出两套结论（保存那一刻才报错 = 试错搬到了生产上）。
   */
  private void validateConditionValue(
      AutomationRuleConditionOperator operator, AutomationRuleConditionInput condition) {
    var valueText = optional(condition.valueText(), "条件值", 500);
    var valueNumber = condition.valueNumber();
    var valueBool = condition.valueBool();
    switch (operator) {
      case GT, GE, LT, LE -> check(
          valueNumber != null,
          "大小比较类操作符必须给一个数值条件值（" + operator.label() + "）");
      case BETWEEN -> check(
          valueText.contains("~") && !valueText.trim().isEmpty(),
          "区间比较需要两个值（用 ~ 分隔，如 100~500）");
      case IN -> check(
          !valueText.trim().isEmpty(), "「属于」至少要有一个候选值（逗号分隔）");
      case IS_NULL, NOT_NULL, EXISTS -> check(
          valueText.isEmpty() && valueNumber == null && valueBool == null,
          "空值类操作符不需要条件值");
      default -> {
        // 其余操作符（EQ / NE / CONTAINS / …）：值由字段类型决定，
        // 类型不符已在**字段目录**那一关查过（allowedOperators）。
      }
    }
  }

  /** 参数类型必须与规格相符（真校验，不是「照单全收」）。 */
  private void checkParamType(
      AutomationRuleEditorParamSpec spec, JsonNode value, AutomationRuleActionType actionType) {
    switch (spec.type()) {
      case "TEXT" -> check(
          value.isTextual(),
          "动作「"
              + actionType.label()
              + "」的参数「"
              + spec.name()
              + "」必须是文本，实际是 "
              + value.getNodeType().toString().toLowerCase(Locale.ROOT));
      case "TEXT_LIST" -> check(
          value.isArray(),
          "动作「"
              + actionType.label()
              + "」的参数「"
              + spec.name()
              + "」必须是一个列表");
      case "OBJECT" -> check(
          value.isObject(),
          "动作「"
              + actionType.label()
              + "」的参数「"
              + spec.name()
              + "」必须是一个 JSON 对象");
      case "NUMBER" -> check(
          value.isNumber(),
          "动作「"
              + actionType.label()
              + "」的参数「"
              + spec.name()
              + "」必须是一个数值");
      case "BOOL" -> check(
          value.isBoolean(),
          "动作「"
              + actionType.label()
              + "」的参数「"
              + spec.name()
              + "」必须是一个布尔值");
      default -> throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "未知的参数类型 " + spec.type());
    }
    if (!spec.enumValues().isEmpty()) {
      check(
          spec.enumValues().contains(value.asText()),
          "动作「"
              + actionType.label()
              + "」的参数「"
              + spec.name()
              + "」只能是 "
              + String.join("、", spec.enumValues())
              + " 之一，实际是「"
              + value.asText()
              + "」");
    }
  }

  // ================================================================ 内部：JSON Schema 摊平

  /** 一个参数的规格（目录条目）。 */
  private static AutomationRuleEditorParamSpec param(
      String name, String label, String type, boolean required, String description, List<String> enums) {
    return new AutomationRuleEditorParamSpec(name, label, type, required, description, enums);
  }

  /** JSON Schema → 字段列表（递归摊平）。 */
  private List<AutomationRuleEditorField> flattenSchema(JsonNode schema) {
    var out = new ArrayList<AutomationRuleEditorField>();
    walk("", schema, out);
    return out;
  }

  private void walk(String prefix, JsonNode node, List<AutomationRuleEditorField> out) {
    if (node == null || !node.isObject()) return;
    var properties = node.get("properties");
    if (properties == null || !properties.isObject()) return;
    var requiredNames = node.get("required");
    var iterator = properties.fields();
    while (iterator.hasNext()) {
      var entry = iterator.next();
      var name = entry.getKey();
      var child = entry.getValue();
      var path = prefix.isEmpty() ? name : prefix + "." + name;
      var isRequired =
          requiredNames != null && requiredNames.isArray() && containsText(requiredNames, name);
      var type = typeOf(child);
      var label = labelOf(child, name);
      var enums = enumValues(child);
      out.add(
          new AutomationRuleEditorField(
              path, type, label, isRequired, enums, allowedOperators(type)));
      if ("object".equals(type)) {
        walk(path, child, out);
      } else if ("array".equals(type)) {
        var items = child.get("items");
        // 数组元素里的字段：路径用下标 0 —— 与条件求值器的数组下标语义一致
        // （resolvePath 支持 "字段.0.子字段" 这种写法）。
        if (items != null && items.isObject() && items.get("properties") != null) {
          walk(path + ".0", items, out);
        }
      }
    }
  }

  private static boolean containsText(JsonNode array, String value) {
    for (var item : array) {
      if (item.asText().equals(value)) return true;
    }
    return false;
  }

  /** 字段类型（没写 type 时按结构推断）。 */
  private static String typeOf(JsonNode node) {
    if (node == null || !node.isObject()) return "unknown";
    var type = node.get("type");
    if (type != null && type.isTextual()) return type.asText();
    if (node.get("properties") != null) return "object";
    if (node.get("items") != null) return "array";
    var enums = node.get("enum");
    if (enums != null && enums.isArray() && enums.size() > 0) {
      var first = enums.get(0);
      if (first.isNumber()) return "number";
      if (first.isBoolean()) return "boolean";
      if (first.isTextual()) return "string";
    }
    return "unknown";
  }

  private static String labelOf(JsonNode node, String fallback) {
    if (node == null || !node.isObject()) return fallback;
    var description = node.get("description");
    if (description != null && description.isTextual() && !description.asText().isBlank()) {
      return description.asText();
    }
    var title = node.get("title");
    if (title != null && title.isTextual() && !title.asText().isBlank()) return title.asText();
    return fallback;
  }

  private static List<String> enumValues(JsonNode node) {
    var enums = node.get("enum");
    if (enums == null || !enums.isArray()) return List.of();
    var out = new ArrayList<String>();
    for (var item : enums) out.add(item.asText());
    return out;
  }

  /**
   * 这个类型上**说得通**的操作符。
   *
   * <p>口径与 18.3.1.1 的落库校验一致：大小比较必须给数值、
   * 区间必须给两个值、「属于」至少一个候选值、空值类操作符不给值。
   */
  private static List<String> allowedOperators(String type) {
    var base = new ArrayList<>(List.of("EQ", "NE", "IS_NULL", "NOT_NULL", "EXISTS"));
    return switch (type) {
      case "string" -> {
        base.add("CONTAINS");
        base.add("STARTS_WITH");
        base.add("ENDS_WITH");
        base.add("IN");
        yield base;
      }
      case "number", "integer" -> {
        base.add("GT");
        base.add("GE");
        base.add("LT");
        base.add("LE");
        base.add("BETWEEN");
        yield base;
      }
      case "boolean" -> base;
      default -> base;
    };
  }

  // ================================================================ 内部：行映射与工具

  /** 条件入参 → 求值器认识的行。 */
  private static AutomationRuleConditionRow toConditionRow(AutomationRuleConditionInput input, int seq) {
    var operator = AutomationRuleConditionOperator.parse(input.operator());
    return new AutomationRuleConditionRow(
        null,
        input.seq() == null ? seq : input.seq(),
        input.fieldPath() == null ? "" : input.fieldPath(),
        operator == null ? (input.operator() == null ? "" : input.operator()) : operator.name(),
        operator == null ? "" : operator.label(),
        input.valueText() == null ? "" : input.valueText(),
        input.valueNumber(),
        input.valueBool(),
        input.enabled() == null || input.enabled(),
        input.remark() == null ? "" : input.remark());
  }

  /** 动作入参 → 计划里的一步（🔴 不带 id：草稿里的动作还不是行）。 */
  private static AutomationRuleEditorActionPlan toActionRow(AutomationRuleActionInput input, int seq) {
    var actionType = AutomationRuleActionType.parse(input.actionType());
    var params = input.params();
    return new AutomationRuleEditorActionPlan(
        input.seq() == null ? seq : input.seq(),
        actionType == null ? (input.actionType() == null ? "" : input.actionType()) : actionType.name(),
        actionType == null ? "" : actionType.label(),
        input.targetRef() == null ? "" : input.targetRef(),
        params == null || params.isBlank() ? "{}" : params,
        input.enabled() == null || input.enabled(),
        input.remark() == null ? "" : input.remark());
  }

  /** 范围入参 → 求值器认识的行。 */
  private static List<AutomationRuleScopeRow> toScopeRows(List<AutomationRuleScopeInput> scopes) {
    var out = new ArrayList<AutomationRuleScopeRow>();
    var seq = 0;
    for (var scope : scopes) {
      seq++;
      var scopeType = AutomationRuleScopeType.parse(scope.scopeType());
      out.add(
          new AutomationRuleScopeRow(
              null,
              scopeType == null ? (scope.scopeType() == null ? "" : scope.scopeType()) : scopeType.name(),
              scopeType == null ? "" : scopeType.label(),
              scope.scopeRef(),
              scope.scopeLabel() == null ? "" : scope.scopeLabel()));
    }
    return out;
  }

  /** 被测对象：已保存的规则（必须真实存在）/ 草稿（空）。 */
  private UUID resolveRuleId(UUID ruleId) {
    if (ruleId == null) return null;
    var found = sql.fetchOne("select id from platform_automation_rule where id = ?", ruleId);
    if (found == null) notFound("规则不存在（被测规则必须在当前租户名下真实存在）");
    return found.get("id", UUID.class);
  }

  /** 该被测对象内的下一个测试序号。 */
  private int nextRunNo(UUID ruleId) {
    if (ruleId == null) {
      return sql.fetchOne(
              "select coalesce(max(run_no), 0) from platform_automation_rule_test_run"
                  + " where rule_id is null")
          .get(0, Integer.class)
          + 1;
    }
    return sql.fetchOne(
            "select coalesce(max(run_no), 0) from platform_automation_rule_test_run where rule_id = ?",
            ruleId)
        .get(0, Integer.class)
        + 1;
  }

  /** 事件版本（不给时取注册表里可用的最高版本）。 */
  private int resolveSchemaVersion(AutomationRuleInput draft) {
    var schema = activeEventSchema(draft.triggerEventType(), draft.triggerSchemaVersion());
    return schema.schemaVersion();
  }

  /** 被测配置的快照（可重放的关键之一）。 */
  private ObjectNode configSnapshot(AutomationRuleInput draft) {
    var node = json.createObjectNode();
    node.put("name", draft.name() == null ? "" : draft.name().trim());
    node.put("triggerEventType", draft.triggerEventType() == null ? "" : draft.triggerEventType());
    var conditions = node.putArray("conditions");
    var seq = 0;
    if (draft.conditions() != null) {
      for (var condition : draft.conditions()) {
        seq++;
        var item = conditions.addObject();
        item.put("seq", condition.seq() == null ? seq : condition.seq());
        item.put("fieldPath", condition.fieldPath() == null ? "" : condition.fieldPath());
        item.put("operator", condition.operator() == null ? "" : condition.operator());
        item.put("valueText", condition.valueText() == null ? "" : condition.valueText());
        if (condition.valueNumber() != null) item.put("valueNumber", condition.valueNumber());
        if (condition.valueBool() != null) item.put("valueBool", condition.valueBool());
        item.put("enabled", condition.enabled() == null || condition.enabled());
      }
    }
    var actions = node.putArray("actions");
    seq = 0;
    if (draft.actions() != null) {
      for (var action : draft.actions()) {
        seq++;
        var item = actions.addObject();
        item.put("seq", action.seq() == null ? seq : action.seq());
        item.put("actionType", action.actionType() == null ? "" : action.actionType());
        item.put("targetRef", action.targetRef() == null ? "" : action.targetRef());
        item.put("params", action.params() == null || action.params().isBlank() ? "{}" : action.params());
        item.put("enabled", action.enabled() == null || action.enabled());
      }
    }
    var scopes = node.putArray("scopes");
    if (draft.scopes() != null) {
      for (var scope : draft.scopes()) {
        var item = scopes.addObject();
        item.put("scopeType", scope.scopeType() == null ? "" : scope.scopeType());
        if (scope.scopeRef() != null) item.put("scopeRef", scope.scopeRef().toString());
        item.put("scopeLabel", scope.scopeLabel() == null ? "" : scope.scopeLabel());
      }
    }
    return node;
  }

  /** 逐条求值明细 + 动作计划 + 范围结论（台账里的 evaluation）。 */
  private ObjectNode evaluationNode(
      List<AutomationRuleConditionEvaluation> evaluations,
      List<AutomationRuleEditorActionPlan> plan,
      boolean scopeApplicable,
      String scopeNote) {
    var node = json.createObjectNode();
    var conditions = node.putArray("conditions");
    for (var evaluation : evaluations) {
      var item = conditions.addObject();
      item.put("seq", evaluation.seq());
      item.put("fieldPath", evaluation.fieldPath());
      item.put("operator", evaluation.operator());
      item.put("operatorLabel", evaluation.operatorLabel());
      item.put("expectedValue", evaluation.expectedValue());
      item.put("actualValue", evaluation.actualValue());
      item.put("matched", evaluation.matched());
      item.put("reason", evaluation.reason());
    }
    var actions = node.putArray("actions");
    for (var step : plan) {
      var item = actions.addObject();
      item.put("seq", step.seq());
      item.put("actionType", step.actionType());
      item.put("actionTypeLabel", step.actionTypeLabel());
      item.put("targetRef", step.targetRef());
      item.put("params", step.params());
      item.put("enabled", step.enabled());
    }
    node.put("scopeApplicable", scopeApplicable);
    node.put("scopeNote", scopeNote == null ? "" : scopeNote);
    node.put("sideEffectMode", "SIMULATE");
    return node;
  }

  /** 「为什么没通过」—— 人可读，**不许**只写「未命中」。 */
  private static String describeUnmatched(
      List<AutomationRuleConditionEvaluation> evaluations, int matched, int total) {
    var reasons = new ArrayList<String>();
    for (var evaluation : evaluations) {
      if (evaluation.matched()) continue;
      reasons.add(
          evaluation.fieldPath()
              + "（期望 "
              + evaluation.expectedValue()
              + "，实际 "
              + (evaluation.actualValue().isEmpty() ? "（没有这个字段）" : evaluation.actualValue())
              + "，"
              + evaluation.reason()
              + "）");
    }
    var head = total + " 个条件里有 " + (total - matched) + " 个没命中：";
    return head + String.join("；", reasons);
  }

  /**
   * 样本载荷（非法 JSON ⇒ 400，不当成「空载荷」）。
   *
   * <p>🔴 **必须是 JSON 对象**：台账表的 {@code sample_payload} 列有
   * {@code CHECK(jsonb_typeof(sample_payload) = 'object')} ⇒ 数组 / 标量载荷
   * 在落库那一刻会被 DB 拒掉（变成一句看不懂的 500）。这里提前拒，并说清为什么。
   */
  private JsonNode parsePayload(String raw) {
    if (raw == null || raw.isBlank()) return json.createObjectNode();
    JsonNode node;
    try {
      node = json.readTree(raw.trim());
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "样本载荷不是合法的 JSON");
    }
    if (node == null || !node.isObject()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "样本载荷必须是一个 JSON 对象（事件载荷的顶层就是一个对象）");
    }
    return node;
  }

  /** 动作参数：必须是 JSON **对象**（不是数组、不是标量）。 */
  private JsonNode parseParams(String raw) {
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
    return node;
  }

  /** 台账一行 → 传输模型。 */
  private AutomationRuleEditorTestRunRow toTestRunRow(org.jooq.Record row) {
    var evaluation = readJson(row.get("evaluation", String.class));
    var evaluations = new ArrayList<AutomationRuleConditionEvaluation>();
    var plan = new ArrayList<AutomationRuleEditorActionPlan>();
    var scopeApplicable = false;
    var scopeNote = "";
    if (evaluation != null && evaluation.isObject()) {
      var conditions = evaluation.get("conditions");
      if (conditions != null && conditions.isArray()) {
        for (var item : conditions) {
          evaluations.add(
              new AutomationRuleConditionEvaluation(
                  item.path("seq").asInt(),
                  item.path("fieldPath").asText(""),
                  item.path("operator").asText(""),
                  item.path("operatorLabel").asText(""),
                  item.path("expectedValue").asText(""),
                  item.path("actualValue").asText(""),
                  item.path("matched").asBoolean(),
                  item.path("reason").asText("")));
        }
      }
      var actions = evaluation.get("actions");
      if (actions != null && actions.isArray()) {
        for (var item : actions) {
          plan.add(
              new AutomationRuleEditorActionPlan(
                  item.path("seq").asInt(),
                  item.path("actionType").asText(""),
                  item.path("actionTypeLabel").asText(""),
                  item.path("targetRef").asText(""),
                  item.path("params").asText("{}"),
                  item.path("enabled").asBoolean(),
                  item.path("remark").asText("")));
        }
      }
      scopeApplicable = evaluation.path("scopeApplicable").asBoolean();
      scopeNote = evaluation.path("scopeNote").asText("");
    }
    var ruleId = row.get("rule_id", UUID.class);
    return new AutomationRuleEditorTestRunRow(
        row.get("id", UUID.class),
        ruleId,
        row.get("rule_name", String.class),
        ruleId == null,
        row.get("run_no", Integer.class),
        row.get("trigger_event_type", String.class),
        row.get("trigger_schema_version", Integer.class),
        Boolean.TRUE.equals(row.get("matched", Boolean.class)),
        row.get("total_conditions", java.math.BigDecimal.class).intValue(),
        row.get("matched_conditions", java.math.BigDecimal.class).intValue(),
        row.get("planned_actions", java.math.BigDecimal.class).intValue(),
        false,
        scopeApplicable,
        scopeNote,
        List.copyOf(evaluations),
        List.copyOf(plan),
        row.get("failure_reason", String.class),
        row.get("config_snapshot", String.class),
        row.get("sample_payload", String.class),
        row.get("created_by", UUID.class),
        row.get("created_at", OffsetDateTime.class));
  }

  /**
   * 读 jsonb 列（SQL 侧已 {@code ::text}）：解析成节点，解析失败当作「空」。
   *
   * <p>⚠️ 库里存的是**我们自己写进去的** JSON ⇒ 解析失败只可能是历史脏数据，
   * 此时按空处理（页面显示「无明细」），而不是让整个列表 500。
   */
  private JsonNode readJson(String raw) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return json.readTree(raw);
    } catch (Exception e) {
      return null;
    }
  }
}
