package com.starcon.identity;

import static com.starcon.identity.AlertRuleModels.AlertRuleCapabilities;
import static com.starcon.identity.AlertRuleModels.AlertRuleCapabilityDimension;
import static com.starcon.identity.AlertRuleModels.AlertRuleEffective;
import static com.starcon.identity.AlertRuleModels.AlertRuleHit;
import static com.starcon.identity.AlertRuleModels.AlertRuleHitHistory;
import static com.starcon.identity.AlertRuleModels.AlertRuleList;
import static com.starcon.identity.AlertRuleModels.AlertRuleMetric;
import static com.starcon.identity.AlertRuleModels.AlertRulePreset;
import static com.starcon.identity.AlertRuleModels.AlertRuleSaveRequest;
import static com.starcon.identity.AlertRuleModels.AlertRuleVocabulary;

import com.starcon.identity.AlertRuleCatalog.ChangeBasis;
import com.starcon.identity.AlertRuleCatalog.MetricDefinition;
import com.starcon.identity.AlertRuleCatalog.PresetRule;
import com.starcon.identity.AlertRuleCatalog.Unit;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 1.3.2.1 预警规则 —— 租户级规则定义的读写。
 *
 * <p><b>本模块只做配置界面，不做规则求值。</b>
 *
 * <p>蓝图原文：「规则引擎本体在 <b>18 系统</b>，此处只做配置界面。」
 * 18.3 业务规则引擎（18.3.1.1 规则列表 / 18.3.1.2 规则编辑器 / 18.3.2.1 执行日志，
 * platform 服务）**尚未交付**（台账 module-progress.md 三条都是「未开发」），
 * 所以本服务：
 *
 * <ul>
 *   <li><b>管</b>规则定义（预置规则的参数覆盖 / 自定义规则 / 启停）；</li>
 *   <li><b>不求值</b>：不比阈值、不产命中、不发通知、不建待办；</li>
 *   <li><b>只读</b>命中历史：{@code alert_rule_hit} 的写入由引擎的授权 API 负责，
 *       本模块<b>不暴露任何写端点</b>，今天没有任何入口能写进假命中。</li>
 * </ul>
 *
 * <p><b>生效值 = 预置默认值 ⨯ 租户覆盖（覆盖优先）</b>，合并在本服务里做。
 * 读生效值时 <b>SQL 里不许硬编码任何阈值</b> —— 阈值只能来自
 * {@link AlertRuleCatalog}（预置定义）或 {@code alert_rule} 的覆盖行。
 *
 * <p><b>租户与成员都来自已认证会话</b>：两个都不是请求参数，更不是请求头。
 * 伪造 {@code X-Tenant-Id} 头改不动 {@code app.current_tenant}。
 *
 * <p><b>为什么不复用 {@code user_preference}（V336）</b>：规则是「多条带结构的记录」，
 * 条数随时间无上界；{@code user_preference} 的载荷上限是 16 KB / 单数组 64 项，
 * 且写入是整份覆盖。理由详见 V384 的第一节。
 */
@Service
public class AlertRuleService {

  /** 规则引擎的归属模块（引擎未交付时如实报出来）。 */
  public static final String ENGINE_OWNER_MODULE =
      "18.3 业务规则引擎（18.3.1.1 规则列表 / 18.3.1.2 规则编辑器 / 18.3.2.1 执行日志 · platform 服务）";

  /** 引擎是否已接通。**当前为否** —— 18.3 那一格尚未交付。 */
  public static final boolean ENGINE_AVAILABLE = false;

  /** 引擎未接通时的说明（界面上要显示，不能是空白）。 */
  public static final String ENGINE_NOTE =
      "规则引擎在 18 系统（18.3 业务规则引擎），那一格尚未交付。"
          + "本模块只做规则配置：规则命中判定、命中记录写入、通知与工作台待办的生成都不在这里，"
          + "所以「命中历史」现在是空的 —— 这是「引擎未接通」，不是「本租户还没有命中过」。";

  private final DSLContext sql;
  private final AccountLocaleService locales;

  public AlertRuleService(DSLContext sql, AccountLocaleService locales) {
    this.sql = sql;
    this.locales = locales;
  }

  // ==========================================================================
  // 内部形状
  // ==========================================================================

  /** 库里的一行（覆盖行 / 自定义行）。 */
  private record Row(
      UUID id,
      String ruleCode,
      String origin,
      String name,
      String objectType,
      String metricCode,
      BigDecimal threshold,
      String timeWindow,
      String comparison,
      String changeBasis,
      String objectScope,
      String objectRef,
      String timezone,
      String timezoneSource,
      String status,
      OffsetDateTime updatedAt) {}

  /** 解析结果：要么命中一行，要么命中一条预置规则（尚未被本租户改过）。 */
  private record Resolved(Row row, String presetCode) {
    boolean isPreset() {
      return presetCode != null;
    }
  }

  // ==========================================================================
  // 一、能力声明
  // ==========================================================================

  /** 能力声明：预置规则集 + 受控词表 + 引擎是否接通 + 逐条边界。 */
  public AlertRuleCapabilities capabilities() {
    List<AlertRulePreset> presets = new ArrayList<>();
    for (PresetRule preset : AlertRuleCatalog.PRESETS) {
      MetricDefinition metric = AlertRuleCatalog.metric(preset.metricCode());
      presets.add(
          new AlertRulePreset(
              preset.ruleCode(),
              preset.displayName(),
              preset.description(),
              preset.objectType(),
              preset.metricCode(),
              AlertRuleRules.thresholdText(preset.threshold()),
              unitCode(AlertRuleCatalog.thresholdUnit(metric, preset.changeBasis())),
              unitLabel(AlertRuleCatalog.thresholdUnit(metric, preset.changeBasis())),
              preset.timeWindow(),
              preset.comparison(),
              preset.changeBasis(),
              preset.objectScope(),
              preset.defaultTimezone(),
              preset.upstreamSystem(),
              preset.upstreamModule(),
              preset.upstreamEndpoint(),
              metric != null && metric.available(),
              metric == null ? null : metric.unavailableReason()));
    }

    List<AlertRuleMetric> metrics = new ArrayList<>();
    for (MetricDefinition metric : AlertRuleCatalog.METRICS) {
      metrics.add(
          new AlertRuleMetric(
              metric.metricCode(),
              metric.displayName(),
              unitCode(metric.unit()),
              unitLabel(metric.unit()),
              metric.ownerSystem(),
              metric.ownerModule(),
              metric.available(),
              metric.unavailableReason()));
    }

    AlertRuleVocabulary vocabulary =
        new AlertRuleVocabulary(
            metrics,
            AlertRuleRules.OBJECT_TYPES,
            AlertRuleRules.TIME_WINDOWS,
            AlertRuleRules.COMPARISONS,
            ChangeBasis.ALL,
            AlertRuleRules.OBJECT_SCOPES);

    List<AlertRuleCapabilityDimension> dimensions =
        List.of(
            new AlertRuleCapabilityDimension(
                "ruleConfiguration",
                true,
                "1.3.2.1（identity 服务）",
                "预置规则集的参数覆盖、自定义规则、启停与恢复默认都在本模块，规则定义真的落 PostgreSQL。",
                List.of()),
            new AlertRuleCapabilityDimension(
                "ruleEvaluation",
                false,
                ENGINE_OWNER_MODULE,
                "规则命中判定不在本模块：改完阈值不会立刻看到命中记录，这是预期行为，不是故障。",
                List.of(
                    "18.3.1.1 规则列表",
                    "18.3.1.2 规则编辑器",
                    "18.3.2.1 执行日志")),
            new AlertRuleCapabilityDimension(
                "hitHistory",
                false,
                ENGINE_OWNER_MODULE,
                "命中历史表已建好、只读端点已开放，但写入方（引擎）未交付 ⇒ 列表恒为空。"
                    + "空列表表示「引擎未接通」，不是「0 次命中」。",
                List.of("18.3.2.1 执行日志（写入方）")),
            new AlertRuleCapabilityDimension(
                "metricAvailability",
                false,
                "13 指标 / 07 库存 / 12 财务",
                "指标词表里逐条标注了上游是否已交付该口径；未交付的指标建的规则，求值时会判「不可判定」。",
                List.of("STOCK_COVER_DAYS 覆盖天数口径（07 库存未交付日均速率投影）")),
            new AlertRuleCapabilityDimension(
                "notificationDispatch",
                false,
                "1.3 通知 / 1.1 待办",
                "「命中 → 通知 + 工作台待办」是引擎的下游动作，不在本模块。",
                List.of("1.3.2.1 的命中消费方（随引擎一起交付）")));

    List<String> boundaries =
        List.of(
            "只做配置界面：规则求值、命中判定、命中记录写入都不在本模块（引擎在 18 系统）。",
            "预置规则集是代码里的声明（AlertRuleCatalog），不落库；落库的只有租户的覆盖 / 自建 / 关闭。",
            "预置规则不允许删除，只能关闭或恢复默认 —— 删除会让「这条规则存在过」变成说不清的事。",
            "请求体不接受 tenantId / userId / membershipId：租户来自已认证的成员关系。",
            "阈值在线上是十进制文本、Java 侧 BigDecimal、库里 NUMERIC(19,6)，全程不碰浮点。",
            "时间窗的边界按规则自己的时区（IANA 区域 id）算，不写死 UTC。");

    return new AlertRuleCapabilities(
        presets,
        vocabulary,
        ENGINE_AVAILABLE,
        ENGINE_OWNER_MODULE,
        ENGINE_NOTE,
        dimensions,
        boundaries);
  }

  // ==========================================================================
  // 二、生效规则清单
  // ==========================================================================

  /**
   * 生效规则清单（= 预置默认值 ⨯ 租户覆盖 的合并结果）。
   *
   * <p>预置规则**没被改过时库里没有行** —— 它照样出现在清单里，
   * {@code customized=false}、{@code enabled=true}，id 是由规则码派生的合成 id。
   */
  public AlertRuleList list(UUID tenantId) {
    Map<String, Row> byCode = new LinkedHashMap<>();
    for (Row row : loadRows(tenantId)) {
      byCode.put(row.ruleCode(), row);
    }

    List<AlertRuleEffective> rules = new ArrayList<>();
    int presetCount = 0;
    int customCount = 0;
    int customizedCount = 0;
    int disabledCount = 0;

    for (PresetRule preset : AlertRuleCatalog.PRESETS) {
      presetCount++;
      Row row = byCode.remove(preset.ruleCode());
      AlertRuleEffective effective = effectiveOfPreset(preset, row);
      rules.add(effective);
      if (effective.customized()) customizedCount++;
      if (!effective.enabled()) disabledCount++;
    }
    // 剩下的都是自定义规则（origin='CUSTOM'）。
    for (Row row : byCode.values()) {
      customCount++;
      AlertRuleEffective effective = effectiveOfCustom(row);
      rules.add(effective);
      if (!effective.enabled()) disabledCount++;
    }

    return new AlertRuleList(rules, presetCount, customCount, customizedCount, disabledCount);
  }

  /** 预置规则的生效值 = 预置默认值 ⨯ 覆盖行（非空列优先）。 */
  private AlertRuleEffective effectiveOfPreset(PresetRule preset, Row row) {
    String objectType =
        row != null && row.objectType() != null ? row.objectType() : preset.objectType();
    String metricCode =
        row != null && row.metricCode() != null ? row.metricCode() : preset.metricCode();
    BigDecimal threshold =
        row != null && row.threshold() != null ? row.threshold() : preset.threshold();
    String timeWindow =
        row != null && row.timeWindow() != null ? row.timeWindow() : preset.timeWindow();
    String comparison =
        row != null && row.comparison() != null ? row.comparison() : preset.comparison();
    String changeBasis =
        row != null && row.changeBasis() != null ? row.changeBasis() : preset.changeBasis();
    String objectScope =
        row != null && row.objectScope() != null ? row.objectScope() : preset.objectScope();
    String objectRef = row != null ? row.objectRef() : null;
    String timezone =
        row != null && row.timezone() != null ? row.timezone() : preset.defaultTimezone();
    String timezoneSource;
    if (row == null || row.timezone() == null) {
      timezoneSource = "PRESET_DEFAULT";
    } else {
      timezoneSource = row.timezoneSource() != null ? row.timezoneSource() : "EXPLICIT";
    }
    String displayName = row != null && row.name() != null ? row.name() : preset.displayName();
    boolean enabled = row == null || "ENABLED".equals(row.status());

    MetricDefinition metric = AlertRuleCatalog.metric(metricCode);
    Unit unit = AlertRuleCatalog.thresholdUnit(metric, changeBasis);

    return new AlertRuleEffective(
        AlertRuleCatalog.presetRowId(preset.ruleCode()),
        preset.ruleCode(),
        "PRESET",
        displayName,
        preset.description(),
        objectType,
        metricCode,
        AlertRuleRules.thresholdText(threshold),
        unitCode(unit),
        unitLabel(unit),
        timeWindow,
        comparison,
        changeBasis,
        objectScope,
        objectRef,
        timezone,
        enabled,
        row != null,
        timezoneSource,
        preset.upstreamSystem(),
        preset.upstreamModule(),
        row != null ? row.updatedAt() : null);
  }

  /** 自定义规则的生效值（整条都来自库里那一行）。 */
  private AlertRuleEffective effectiveOfCustom(Row row) {
    MetricDefinition metric = AlertRuleCatalog.metric(row.metricCode());
    Unit unit = AlertRuleCatalog.thresholdUnit(metric, row.changeBasis());
    return new AlertRuleEffective(
        row.id(),
        row.ruleCode(),
        "CUSTOM",
        row.name(),
        null,
        row.objectType(),
        row.metricCode(),
        AlertRuleRules.thresholdText(row.threshold()),
        unitCode(unit),
        unitLabel(unit),
        row.timeWindow(),
        row.comparison(),
        row.changeBasis(),
        row.objectScope(),
        row.objectRef(),
        row.timezone(),
        "ENABLED".equals(row.status()),
        true,
        row.timezoneSource() != null ? row.timezoneSource() : "EXPLICIT",
        null,
        null,
        row.updatedAt());
  }

  // ==========================================================================
  // 三、写操作
  // ==========================================================================

  /**
   * 新建自定义规则。
   *
   * <p><b>整条必填</b>：自定义规则没有默认值可继承，缺一项就是一条残缺规则。
   */
  public AlertRuleEffective create(UUID tenantId, UUID membershipId, AlertRuleSaveRequest input) {
    if (input == null) {
      throw badRequest("请求体不能为空");
    }
    String name = AlertRuleRules.normalizeName(input.name());
    String objectType = AlertRuleRules.normalizeObjectType(input.objectType());
    String metricCode = AlertRuleRules.normalizeMetricCode(input.metricCode());
    BigDecimal threshold = AlertRuleRules.normalizeThreshold(input.threshold());
    String timeWindow = AlertRuleRules.normalizeTimeWindow(input.timeWindow());
    String comparison = AlertRuleRules.normalizeComparison(input.comparison());
    String changeBasis = AlertRuleRules.normalizeChangeBasis(input.changeBasis());
    String objectScope = AlertRuleRules.normalizeObjectScope(input.objectScope());
    String objectRef = null;
    if ("SPECIFIC".equals(objectScope)) {
      objectRef = AlertRuleRules.normalizeObjectRef(input.objectRef());
    } else if (input.objectRef() != null && !input.objectRef().isBlank()) {
      throw badRequest("对象范围是「所有该类型对象」时不能给对象标识");
    }
    MetricDefinition metric = AlertRuleCatalog.metric(metricCode);
    if (!AlertRuleCatalog.appliesTo(metric, objectType)) {
      throw badRequest(
          "度量「"
              + metric.displayName()
              + "」不能挂在对象类型「"
              + objectType
              + "」上（该指标适用于："
              + String.join(" / ", metric.applicableObjectTypes())
              + "）");
    }
    AlertRuleRules.guardThresholdReachability(metric, threshold, comparison, changeBasis);

    String timezone = input.timezone();
    String timezoneSource;
    if (timezone == null || timezone.isBlank()) {
      // 不给时区就继承**当前操作人**的生效展示时区，并把来源记下来 ——
      // 来源不透明的话，用户会以为时间窗按某个时区算，结果按另一个时区算。
      timezone = locales.read(tenantId, membershipId).timezone();
      timezoneSource = "ACCOUNT_LOCALE";
    } else {
      timezone = AlertRuleRules.normalizeTimezone(timezone);
      timezoneSource = "EXPLICIT";
    }

    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    String ruleCode = AlertRuleRules.newCustomRuleCode();
    Row row =
        firstRow(
            sql.fetch(
                "insert into alert_rule"
                    + "(id,tenant_id,rule_code,origin,name,object_type,metric_code,threshold,"
                    + "time_window,comparison,change_basis,object_scope,object_ref,timezone,"
                    + "timezone_source,status,created_by,created_at,updated_at)"
                    + " values (?::uuid,?::uuid,?::varchar,'CUSTOM',?::varchar,?::varchar,?::varchar,"
                    + "?::numeric,?::varchar,?::varchar,?::varchar,?::varchar,?::varchar,?::varchar,"
                    + "?::varchar,'ENABLED',?::uuid,?::timestamptz,?::timestamptz)"
                    + " returning "
                    + COLUMNS,
                id,
                tenantId,
                ruleCode,
                name,
                objectType,
                metricCode,
                threshold,
                timeWindow,
                comparison,
                changeBasis,
                objectScope,
                objectRef,
                timezone,
                timezoneSource,
                membershipId,
                now,
                now));
    return effectiveOfCustom(row);
  }

  /**
   * 改一条规则（**逐字段覆盖**：请求里给了的字段覆盖当前值，没给的保持）。
   *
   * <p>端点是 PUT，但语义是「逐字段覆盖」而不是「整份替换」——
   * 「只改阈值」和「改阈值顺便改度量」是两件不同的事，前者不该被迫把后者一起提交。
   *
   * <p>对**预置规则**：写的是**覆盖行**；把某一项改回预置默认值 = **清掉那一项的覆盖**
   * （所以「恢复默认」到字段级是自然成立的，不需要另一个端点）。
   * 对**自定义规则**：直接改那一行。
   */
  public AlertRuleEffective update(
      UUID tenantId, UUID membershipId, UUID id, AlertRuleSaveRequest input) {
    if (input == null) {
      throw badRequest("请求体不能为空");
    }
    Resolved resolved = resolve(tenantId, id);
    if (resolved.row() != null && "CUSTOM".equals(resolved.row().origin())) {
      return updateCustom(tenantId, id, resolved.row(), input);
    }
    PresetRule preset =
        resolved.isPreset()
            ? AlertRuleCatalog.preset(resolved.presetCode())
            : AlertRuleCatalog.preset(resolved.row().ruleCode());
    if (preset == null) {
      throw badRequest("这条规则的预置定义不存在，无法修改");
    }
    return updatePresetOverride(tenantId, membershipId, preset, resolved.row(), input);
  }

  /** 改自定义规则：逐字段覆盖。 */
  private AlertRuleEffective updateCustom(UUID tenantId, UUID id, Row current, AlertRuleSaveRequest input) {
    String name = current.name();
    if (input.name() != null) {
      name = AlertRuleRules.normalizeName(input.name());
    }
    String objectType = current.objectType();
    if (input.objectType() != null) {
      objectType = AlertRuleRules.normalizeObjectType(input.objectType());
    }
    String metricCode = current.metricCode();
    if (input.metricCode() != null) {
      metricCode = AlertRuleRules.normalizeMetricCode(input.metricCode());
    }
    BigDecimal threshold = current.threshold();
    if (input.threshold() != null) {
      threshold = AlertRuleRules.normalizeThreshold(input.threshold());
    }
    String timeWindow = current.timeWindow();
    if (input.timeWindow() != null) {
      timeWindow = AlertRuleRules.normalizeTimeWindow(input.timeWindow());
    }
    String comparison = current.comparison();
    if (input.comparison() != null) {
      comparison = AlertRuleRules.normalizeComparison(input.comparison());
    }
    String changeBasis = current.changeBasis();
    if (input.changeBasis() != null) {
      changeBasis = AlertRuleRules.normalizeChangeBasis(input.changeBasis());
    }
    String objectScope = current.objectScope();
    String objectRef = current.objectRef();
    if (input.objectScope() != null) {
      objectScope = AlertRuleRules.normalizeObjectScope(input.objectScope());
    }
    if (input.objectRef() != null) {
      objectRef =
          input.objectRef().isBlank() ? null : AlertRuleRules.normalizeObjectRef(input.objectRef());
    }
    if ("SPECIFIC".equals(objectScope)) {
      if (objectRef == null) {
        throw badRequest("对象范围是「具体某对象」时必须给出对象标识");
      }
    } else if (objectRef != null) {
      throw badRequest("对象范围是「所有该类型对象」时不能给对象标识");
    }
    String timezone = current.timezone();
    String timezoneSource = current.timezoneSource();
    if (input.timezone() != null) {
      timezone = AlertRuleRules.normalizeTimezone(input.timezone());
      timezoneSource = "EXPLICIT";
    }
    MetricDefinition metric = AlertRuleCatalog.metric(metricCode);
    if (!AlertRuleCatalog.appliesTo(metric, objectType)) {
      throw badRequest(
          "度量「"
              + metric.displayName()
              + "」不能挂在对象类型「"
              + objectType
              + "」上（该指标适用于："
              + String.join(" / ", metric.applicableObjectTypes())
              + "）");
    }
    AlertRuleRules.guardThresholdReachability(metric, threshold, comparison, changeBasis);

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    Row row =
        firstRow(
            sql.fetch(
                "update alert_rule set name = ?::varchar, object_type = ?::varchar,"
                    + " metric_code = ?::varchar, threshold = ?::numeric, time_window = ?::varchar,"
                    + " comparison = ?::varchar, change_basis = ?::varchar, object_scope = ?::varchar,"
                    + " object_ref = ?::varchar, timezone = ?::varchar, timezone_source = ?::varchar,"
                    + " updated_at = ?::timestamptz"
                    + " where tenant_id = ?::uuid and id = ?::uuid"
                    + " returning "
                    + COLUMNS,
                name,
                objectType,
                metricCode,
                threshold,
                timeWindow,
                comparison,
                changeBasis,
                objectScope,
                objectRef,
                timezone,
                timezoneSource,
                now,
                tenantId,
                id));
    return effectiveOfCustom(row);
  }

  /**
   * 改预置规则：写**覆盖行**（不存在就建一行）。
   *
   * <p>把某一项改回预置默认值 ⇒ **清掉那一项的覆盖**（存 NULL）。
   * 覆盖清空后这行就没有意义了 ⇒ 直接删掉它，规则回到纯预置状态。
   */
  private AlertRuleEffective updatePresetOverride(
      UUID tenantId,
      UUID membershipId,
      PresetRule preset,
      Row current,
      AlertRuleSaveRequest input) {
    // 覆盖项：null = 没覆盖这一项（用预置默认）。
    String name = current != null ? current.name() : null;
    String objectType = current != null ? current.objectType() : null;
    String metricCode = current != null ? current.metricCode() : null;
    BigDecimal threshold = current != null ? current.threshold() : null;
    String timeWindow = current != null ? current.timeWindow() : null;
    String comparison = current != null ? current.comparison() : null;
    String changeBasis = current != null ? current.changeBasis() : null;
    String objectScope = current != null ? current.objectScope() : null;
    String objectRef = current != null ? current.objectRef() : null;
    String timezone = current != null ? current.timezone() : null;
    String timezoneSource = current != null ? current.timezoneSource() : null;

    if (input.name() != null) {
      name = blankToNull(AlertRuleRules.normalizeName(input.name()));
      if (preset.displayName().equals(name)) name = null;
    }
    if (input.objectType() != null) {
      objectType = AlertRuleRules.normalizeObjectType(input.objectType());
      if (preset.objectType().equals(objectType)) objectType = null;
    }
    if (input.metricCode() != null) {
      metricCode = AlertRuleRules.normalizeMetricCode(input.metricCode());
      if (preset.metricCode().equals(metricCode)) metricCode = null;
    }
    if (input.threshold() != null) {
      threshold = AlertRuleRules.normalizeThreshold(input.threshold());
      if (threshold.compareTo(preset.threshold()) == 0) threshold = null;
    }
    if (input.timeWindow() != null) {
      timeWindow = AlertRuleRules.normalizeTimeWindow(input.timeWindow());
      if (preset.timeWindow().equals(timeWindow)) timeWindow = null;
    }
    if (input.comparison() != null) {
      comparison = AlertRuleRules.normalizeComparison(input.comparison());
      if (preset.comparison().equals(comparison)) comparison = null;
    }
    if (input.changeBasis() != null) {
      changeBasis = AlertRuleRules.normalizeChangeBasis(input.changeBasis());
      if (preset.changeBasis().equals(changeBasis)) changeBasis = null;
    }
    if (input.objectScope() != null) {
      objectScope = AlertRuleRules.normalizeObjectScope(input.objectScope());
      if (preset.objectScope().equals(objectScope)) objectScope = null;
    }
    if (input.objectRef() != null) {
      objectRef = input.objectRef().isBlank() ? null : AlertRuleRules.normalizeObjectRef(input.objectRef());
    }
    if (input.timezone() != null) {
      timezone = AlertRuleRules.normalizeTimezone(input.timezone());
      timezoneSource = timezone.equals(preset.defaultTimezone()) ? null : "EXPLICIT";
      if (timezoneSource == null) timezone = null;
    }

    // 合并后的生效值（用于语义校验）：覆盖优先，缺省回预置默认。
    String effectiveObjectType = objectType != null ? objectType : preset.objectType();
    String effectiveMetricCode = metricCode != null ? metricCode : preset.metricCode();
    BigDecimal effectiveThreshold = threshold != null ? threshold : preset.threshold();
    String effectiveTimeWindow = timeWindow != null ? timeWindow : preset.timeWindow();
    String effectiveComparison = comparison != null ? comparison : preset.comparison();
    String effectiveChangeBasis = changeBasis != null ? changeBasis : preset.changeBasis();
    String effectiveScope = objectScope != null ? objectScope : preset.objectScope();

    MetricDefinition metric = AlertRuleCatalog.metric(effectiveMetricCode);
    if (!AlertRuleCatalog.appliesTo(metric, effectiveObjectType)) {
      throw badRequest(
          "度量「"
              + metric.displayName()
              + "」不能挂在对象类型「"
              + effectiveObjectType
              + "」上（该指标适用于："
              + String.join(" / ", metric.applicableObjectTypes())
              + "）");
    }
    if ("SPECIFIC".equals(effectiveScope) && objectRef == null) {
      throw badRequest("对象范围是「具体某对象」时必须给出对象标识");
    }
    if (!"SPECIFIC".equals(effectiveScope) && objectRef != null) {
      throw badRequest("对象范围是「所有该类型对象」时不能给对象标识");
    }
    AlertRuleRules.guardThresholdReachability(
        metric, effectiveThreshold, effectiveComparison, effectiveChangeBasis);

    // 这一轮改完之后，这一行还剩不剩「覆盖」：
    // 全部清空 = 本租户不再覆盖任何一项（只剩「关掉了」这一种可能）。
    boolean noOverride =
        name == null
            && objectType == null
            && metricCode == null
            && threshold == null
            && timeWindow == null
            && comparison == null
            && changeBasis == null
            && objectScope == null
            && objectRef == null
            && timezone == null;

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    Row row;
    if (current == null) {
      if (noOverride) {
        // 什么也没改（改的正好是预置默认值）⇒ 本来就是预置状态，不用建行。
        return effectiveOfPreset(preset, null);
      }
      row =
          firstRow(
              sql.fetch(
                  "insert into alert_rule"
                      + "(id,tenant_id,rule_code,origin,name,object_type,metric_code,threshold,"
                      + "time_window,comparison,change_basis,object_scope,object_ref,timezone,"
                      + "timezone_source,status,created_by,created_at,updated_at)"
                      + " values (?::uuid,?::uuid,?::varchar,'PRESET',?::varchar,?::varchar,?::varchar,"
                      + "?::numeric,?::varchar,?::varchar,?::varchar,?::varchar,?::varchar,?::varchar,"
                      + "?::varchar,'ENABLED',?::uuid,?::timestamptz,?::timestamptz)"
                      + " returning "
                      + COLUMNS,
                  UUID.randomUUID(),
                  tenantId,
                  preset.ruleCode(),
                  name,
                  objectType,
                  metricCode,
                  threshold,
                  timeWindow,
                  comparison,
                  changeBasis,
                  objectScope,
                  objectRef,
                  timezone,
                  timezoneSource,
                  membershipId,
                  now,
                  now));
    } else if (noOverride && "ENABLED".equals(current.status())) {
      // 🔴 **覆盖清空后这行必须删掉，不能 UPDATE 成空行。**
      //   数据库层的 CHECK（alert_rule_preset_meaningful_chk）不许存在
      //   「既没覆盖任何一项、又是 ENABLED」的覆盖行 —— 那行没有任何意义。
      //   先写成空行再删会在同一条语句里撞 CHECK（⇒ 409），所以在这里直接删。
      sql.execute(
          "delete from alert_rule where tenant_id = ?::uuid and id = ?::uuid",
          tenantId,
          current.id());
      return effectiveOfPreset(preset, null);
    } else {
      row =
          firstRow(
              sql.fetch(
                  "update alert_rule set name = ?::varchar, object_type = ?::varchar,"
                      + " metric_code = ?::varchar, threshold = ?::numeric, time_window = ?::varchar,"
                      + " comparison = ?::varchar, change_basis = ?::varchar,"
                      + " object_scope = ?::varchar, object_ref = ?::varchar, timezone = ?::varchar,"
                      + " timezone_source = ?::varchar, updated_at = ?::timestamptz"
                      + " where tenant_id = ?::uuid and id = ?::uuid"
                      + " returning "
                      + COLUMNS,
                  name,
                  objectType,
                  metricCode,
                  threshold,
                  timeWindow,
                  comparison,
                  changeBasis,
                  objectScope,
                  objectRef,
                  timezone,
                  timezoneSource,
                  now,
                  tenantId,
                  current.id()));
    }

    return effectiveOfPreset(preset, row);
  }

  /**
   * 启用 / 关闭一条规则。
   *
   * <p>关闭预置规则 = 写一行只有 {@code status='DISABLED'} 的覆盖行
   * （这是蓝图明说的「用户可关闭」）。
   *
   * <p>启用预置规则：若这行**没有别的覆盖**，直接删掉它 —— 规则回到纯预置状态，
   * 也就回到「生效」。留一行 {@code status='ENABLED'} 的空覆盖没有意义。
   */
  public AlertRuleEffective setEnabled(UUID tenantId, UUID membershipId, UUID id, boolean enabled) {
    Resolved resolved = resolve(tenantId, id);
    if (resolved.row() != null && "CUSTOM".equals(resolved.row().origin())) {
      OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
      Row row =
          firstRow(
              sql.fetch(
                  "update alert_rule set status = ?::varchar, updated_at = ?::timestamptz"
                      + " where tenant_id = ?::uuid and id = ?::uuid"
                      + " returning "
                      + COLUMNS,
                  enabled ? "ENABLED" : "DISABLED",
                  now,
                  tenantId,
                  id));
      return effectiveOfCustom(row);
    }
    PresetRule preset =
        resolved.isPreset()
            ? AlertRuleCatalog.preset(resolved.presetCode())
            : AlertRuleCatalog.preset(resolved.row().ruleCode());
    if (preset == null) {
      throw badRequest("这条规则的预置定义不存在");
    }
    if (enabled) {
      Row current = resolved.row();
      // 🔴 重新启用：若这行**没有别的覆盖**，它就该被删掉 ——
      //   「既没覆盖任何一项、又是 ENABLED」的行没有意义，
      //   而且数据库的 CHECK（alert_rule_preset_meaningful_chk）也不允许它存在
      //   （先 UPDATE 成那样再删会撞 CHECK ⇒ 409，所以直接删）。
      if (current != null && !hasOverrideColumns(current)) {
        sql.execute(
            "delete from alert_rule where tenant_id = ?::uuid and id = ?::uuid",
            tenantId,
            current.id());
        return effectiveOfPreset(preset, null);
      }
      if (current != null) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Row row =
            firstRow(
                sql.fetch(
                    "update alert_rule set status = 'ENABLED', updated_at = ?::timestamptz"
                        + " where tenant_id = ?::uuid and id = ?::uuid"
                        + " returning "
                        + COLUMNS,
                    now,
                    tenantId,
                    current.id()));
        return effectiveOfPreset(preset, row);
      }
      // 本来就没被改过 ⇒ 本来就是生效的。
      return effectiveOfPreset(preset, null);
    }
    // 关闭：建 / 更新一行只有 status='DISABLED' 的覆盖。
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    Row row;
    if (resolved.row() == null) {
      row =
          firstRow(
              sql.fetch(
                  "insert into alert_rule"
                      + "(id,tenant_id,rule_code,origin,status,created_by,created_at,updated_at)"
                      + " values (?::uuid,?::uuid,?::varchar,'PRESET','DISABLED',?::uuid,"
                      + "?::timestamptz,?::timestamptz)"
                      + " returning "
                      + COLUMNS,
                  UUID.randomUUID(),
                  tenantId,
                  preset.ruleCode(),
                  membershipId,
                  now,
                  now));
    } else {
      row =
          firstRow(
              sql.fetch(
                  "update alert_rule set status = 'DISABLED', updated_at = ?::timestamptz"
                      + " where tenant_id = ?::uuid and id = ?::uuid"
                      + " returning "
                      + COLUMNS,
                  now,
                  tenantId,
                  resolved.row().id()));
    }
    return effectiveOfPreset(preset, row);
  }

  /**
   * 恢复预置规则默认值（= 删掉那行覆盖）。
   *
   * <p>只对**预置规则**有意义：自定义规则没有默认值可恢复，请直接删除。
   */
  public void reset(UUID tenantId, UUID id) {
    Resolved resolved = resolve(tenantId, id);
    if (resolved.row() != null && "CUSTOM".equals(resolved.row().origin())) {
      throw badRequest("自定义规则没有默认值可恢复，请直接删除这条规则");
    }
    if (resolved.row() == null) {
      // 本来就是预置默认值 ⇒ 幂等成功。
      return;
    }
    sql.execute(
        "delete from alert_rule where tenant_id = ?::uuid and id = ?::uuid",
        tenantId,
        resolved.row().id());
  }

  /**
   * 删除一条**自定义**规则。
   *
   * <p>🔴 <b>预置规则不允许删除</b>（设计决定）：预置规则是平台给的「跨境常用规则集」，
   * 删掉它等于这条规则**从来没存在过** —— 但业务上它可能已经命中过、已经发过通知。
   * 用户想让它别生效，正确做法是**关闭**（disable），而「恢复默认」是 reset。
   */
  public void delete(UUID tenantId, UUID id) {
    Resolved resolved = resolve(tenantId, id);
    if (resolved.isPreset()
        || (resolved.row() != null && "PRESET".equals(resolved.row().origin()))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "预置规则不允许删除：它属于平台的跨境常用规则集，删掉会让「这条规则存在过」说不清。"
              + "请改用「关闭」（POST /identity/alert-rules/{id}/disable）；"
              + "如果只是想回到默认参数，请用「恢复默认」（POST /identity/alert-rules/{id}/reset）。");
    }
    sql.execute("delete from alert_rule where tenant_id = ?::uuid and id = ?::uuid", tenantId, id);
  }

  // ==========================================================================
  // 四、命中历史（只读）
  // ==========================================================================

  /**
   * 命中历史（**只读**）。
   *
   * <p>🔴 **引擎未接通 ⇒ 列表恒为空**，这不是「0 次命中」。
   * 响应里 {@code engineAvailable=false} + {@code engineOwnerModule} + 一句说明，
   * 让调用方能分清「没命中过」与「没人来判定」。
   */
  public AlertRuleHitHistory hits(UUID tenantId, int limit, int offset) {
    int size = Math.max(1, Math.min(limit <= 0 ? 50 : limit, 200));
    int from = Math.max(0, offset);
    List<AlertRuleHit> hits = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select id, rule_code, object_type, object_ref, metric_code, observed_value,"
                + " base_value, threshold, comparison, change_basis, time_window, timezone,"
                + " resolution, reason, hit_at from alert_rule_hit"
                + " where tenant_id = ?::uuid"
                + " order by hit_at desc, id desc limit ?::integer offset ?::integer",
            tenantId,
            size,
            from)) {
      hits.add(toHit(row));
    }
    int total =
        sql.fetchOne(
                "select count(*) from alert_rule_hit where tenant_id = ?::uuid", tenantId)
            .get(0, Integer.class);
    return new AlertRuleHitHistory(hits, ENGINE_AVAILABLE, ENGINE_OWNER_MODULE, ENGINE_NOTE, total);
  }

  private AlertRuleHit toHit(Record row) {
    return new AlertRuleHit(
        row.get("id", UUID.class),
        row.get("rule_code", String.class),
        row.get("object_type", String.class),
        row.get("object_ref", String.class),
        row.get("metric_code", String.class),
        AlertRuleRules.thresholdText(row.get("observed_value", BigDecimal.class)),
        AlertRuleRules.thresholdText(row.get("base_value", BigDecimal.class)),
        AlertRuleRules.thresholdText(row.get("threshold", BigDecimal.class)),
        row.get("comparison", String.class),
        row.get("change_basis", String.class),
        row.get("time_window", String.class),
        row.get("timezone", String.class),
        row.get("resolution", String.class),
        row.get("reason", String.class),
        row.get("hit_at", OffsetDateTime.class));
  }

  // ==========================================================================
  // 五、内部
  // ==========================================================================

  private static final String COLUMNS =
      "id, rule_code, origin, name, object_type, metric_code, threshold, time_window, comparison,"
          + " change_basis, object_scope, object_ref, timezone, timezone_source, status, updated_at";

  /** 读本租户的全部规则行。 */
  private List<Row> loadRows(UUID tenantId) {
    List<Row> rows = new ArrayList<>();
    for (Record record :
        sql.fetch(
            "select " + COLUMNS + " from alert_rule where tenant_id = ?::uuid order by rule_code",
            tenantId)) {
      rows.add(toRow(record));
    }
    return rows;
  }

  /** 按 id 读一行（租户内）。 */
  private Row loadRow(UUID tenantId, UUID id) {
    Record record =
        sql.fetchOne(
            "select " + COLUMNS + " from alert_rule where tenant_id = ?::uuid and id = ?::uuid",
            tenantId,
            id);
    return record == null ? null : toRow(record);
  }

  private Row toRow(Record record) {
    return new Row(
        record.get("id", UUID.class),
        record.get("rule_code", String.class),
        record.get("origin", String.class),
        record.get("name", String.class),
        record.get("object_type", String.class),
        record.get("metric_code", String.class),
        record.get("threshold", BigDecimal.class),
        record.get("time_window", String.class),
        record.get("comparison", String.class),
        record.get("change_basis", String.class),
        record.get("object_scope", String.class),
        record.get("object_ref", String.class),
        record.get("timezone", String.class),
        record.get("timezone_source", String.class),
        record.get("status", String.class),
        record.get("updated_at", OffsetDateTime.class));
  }

  private Row firstRow(List<Record> records) {
    if (records.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "规则不存在");
    }
    return toRow(records.getFirst());
  }

  /**
   * 把一个 id 解析成「库里的一行」或「一条预置规则（可能带着它的覆盖行）」。
   *
   * <p>预置规则没被改过时库里没有行 ⇒ 没有行 id，所以给预置规则派了一个
   * <b>由规则码派生的确定性合成 id</b>（{@link AlertRuleCatalog#presetRowId}）。
   *
   * <p>🔴 <b>预置规则的覆盖行用「规则码」定位，不是用行 id 定位。</b>
   * 合成 id 永远不等于覆盖行的行 id（那是数据库生成的 uuid），如果按 id 去找，
   * 第二次改同一条预置规则就会「找不到那一行」⇒ 走新建分支 ⇒ 撞
   * {@code UNIQUE(tenant_id, rule_code)} ⇒ 409。
   * 所以：合成 id → 规则码 → 覆盖行，三步定位。
   */
  private Resolved resolve(UUID tenantId, UUID id) {
    Row row = loadRow(tenantId, id);
    if (row != null) {
      return new Resolved(row, "PRESET".equals(row.origin()) ? row.ruleCode() : null);
    }
    String presetCode = AlertRuleCatalog.presetCodeOf(id);
    if (presetCode != null) {
      return new Resolved(loadRowByCode(tenantId, presetCode), presetCode);
    }
    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "规则不存在");
  }

  /** 按规则码读一行（租户内）。 */
  private Row loadRowByCode(UUID tenantId, String ruleCode) {
    for (Row row : loadRows(tenantId)) {
      if (row.ruleCode().equals(ruleCode)) return row;
    }
    return null;
  }

  /** 这行覆盖是否还留着「覆盖」内容（**不看 status**：只关不改也是有效的一行）。 */
  private boolean hasOverrideColumns(Row row) {
    return row.name() != null
        || row.objectType() != null
        || row.metricCode() != null
        || row.threshold() != null
        || row.timeWindow() != null
        || row.comparison() != null
        || row.changeBasis() != null
        || row.objectScope() != null
        || row.objectRef() != null
        || row.timezone() != null;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static String unitCode(Unit unit) {
    return unit.name();
  }

  private static String unitLabel(Unit unit) {
    return AlertRuleCatalog.unitLabel(unit);
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
