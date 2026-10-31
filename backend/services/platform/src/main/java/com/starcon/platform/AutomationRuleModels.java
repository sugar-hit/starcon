package com.starcon.platform;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 18.3.1.1「规则列表」的传输模型。
 *
 * <p>蓝图 18-integration-and-automation.md 原文：
 *
 * <blockquote>
 *
 * 职责：管理自动化规则：规则名称、触发事件、条件（过滤）、动作（多步）、优先级、启用范围（租户/店铺/仓库）、试运行开关、执行统计。
 * 输入：规则增删改；启用停用；排序。 输出：规则档案 + 执行统计。
 * 关系：上游 = 各业务事件；下游 = 18.3.1.2 编辑器、18.3.2.1 执行日志。
 * <b>规则要能看出最后一次执行结果</b>，否则坏规则静默失败无人察觉。
 *
 * </blockquote>
 *
 * <p><b>🔴 前缀是 {@code AutomationRule}**：契约被 codegen 合并进<b>同一个扁平命名空间</b>，
 * 而 trade / channel / warehouse 三个服务里已经有 {@code Rule*} 一族类型。
 * 前缀撞车会让读代码的人以为是一件事。
 *
 * <p><b>🔴 本模块一个 {@code double} 都没有</b>：执行次数 / 命中条件数 / 动作数
 * 一律 {@link BigDecimal}（库里 {@code NUMERIC(19,6)}）。次数也是数量，同样不许用浮点。
 *
 * <p><b>🔴 「执行结果」是三件套，不是布尔位</b>：{@code lastRunAt} /
 * {@code lastRunStatus} / {@code lastRunFailureReason} 同时出现在规则档案上 ——
 * 蓝图加粗句「规则要能看出最后一次执行结果」的落点。
 *
 * <p><b>🔴 「试运行」是独立状态</b>：{@code dryRun} 与 {@code enabled} / {@code status}
 * 互不影响；{@code sideEffectMode} 是由 {@code dryRun} **推导**出来的
 * （{@code SIMULATE} = 不产生真实副作用），不是另一个可以随手填的字段。
 */
public final class AutomationRuleModels {

  private AutomationRuleModels() {}

  /** 数量小数位（与本服务 18.1.3.1 / 18.1.3.2 / 18.2.3.1 同一口径）。 */
  public static final int COUNT_SCALE = 6;

  // ================================================================ 能力声明

  /**
   * 一项能力（或一项「还没接通」的能力）。
   *
   * @param available 该能力**现在**能不能用（不是「将来会有」）
   * @param evidence 凭什么这么判（表 / 端点 / 规则的具体名字）
   * @param ownerModule 还没接通的能力，**缺在哪个模块**
   * @param note 缺了会怎样 / 缺了归谁
   */
  public record AutomationRuleCapability(
      String moduleId,
      String name,
      boolean available,
      String evidence,
      String ownerModule,
      String note) {}

  /** 一个「还没交付」的下游 / 兄弟能力。 */
  public record AutomationRuleGap(
      String moduleId, String name, String service, boolean available, String relation, String note) {}

  /** 能力声明总览。 */
  public record AutomationRuleCapabilities(
      List<AutomationRuleCapability> capabilities, List<AutomationRuleGap> gaps) {}

  /** 概览：几个数。 */
  public record AutomationRuleOverview(
      long rules,
      long enabledRules,
      long disabledRules,
      long dryRunRules,
      long failingRules,
      long neverRunRules,
      long executions,
      long failedExecutions,
      OffsetDateTime generatedAt) {}

  // ================================================================ 枚举

  /** 规则档案的生命周期状态。 */
  public enum AutomationRuleStatus {
    /** 生效中。 */
    ACTIVE("生效"),
    /** 停用（**不删**：执行历史要能复现）。 */
    DISABLED("停用");

    private final String label;

    AutomationRuleStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}（服务层给一句能直接展示的话）。 */
    public static AutomationRuleStatus parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var v : values()) {
        if (v.name().equalsIgnoreCase(raw.trim())) return v;
      }
      return null;
    }
  }

  /** 启用范围的类别。 */
  public enum AutomationRuleScopeType {
    /** 整个租户。 */
    TENANT("整个租户"),
    /** 某家店铺（channel 服务的只读引用）。 */
    SHOP("指定店铺"),
    /** 某个仓库（warehouse 服务的只读引用）。 */
    WAREHOUSE("指定仓库");

    private final String label;

    AutomationRuleScopeType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}（服务层给一句能直接展示的话）。 */
    public static AutomationRuleScopeType parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var v : values()) {
        if (v.name().equalsIgnoreCase(raw.trim())) return v;
      }
      return null;
    }
  }

  /** 条件比较操作符。 */
  public enum AutomationRuleConditionOperator {
    /** 等于。 */
    EQ("等于"),
    /** 不等于。 */
    NE("不等于"),
    /** 大于。 */
    GT("大于"),
    /** 大于等于。 */
    GE("大于等于"),
    /** 小于。 */
    LT("小于"),
    /** 小于等于。 */
    LE("小于等于"),
    /** 包含（文本）。 */
    CONTAINS("包含"),
    /** 以…开头。 */
    STARTS_WITH("开头是"),
    /** 以…结尾。 */
    ENDS_WITH("结尾是"),
    /** 属于候选集（逗号分隔）。 */
    IN("属于"),
    /** 区间（两个值用 {@code ~} 分隔）。 */
    BETWEEN("介于"),
    /** 为空。 */
    IS_NULL("为空"),
    /** 不为空。 */
    NOT_NULL("不为空"),
    /** 字段存在。 */
    EXISTS("存在");

    private final String label;

    AutomationRuleConditionOperator(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}（服务层给一句能直接展示的话）。 */
    public static AutomationRuleConditionOperator parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var v : values()) {
        if (v.name().equalsIgnoreCase(raw.trim())) return v;
      }
      return null;
    }
  }

  /** 动作类型（蓝图 18.3.1.2 点名的五类）。 */
  public enum AutomationRuleActionType {
    /** 调接口。 */
    CALL_API("调用接口"),
    /** 改状态。 */
    UPDATE_STATUS("修改状态"),
    /** 建任务。 */
    CREATE_TASK("创建任务"),
    /** 发通知。 */
    SEND_NOTIFICATION("发送通知"),
    /** 生成单据。 */
    CREATE_DOCUMENT("生成单据");

    private final String label;

    AutomationRuleActionType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}（服务层给一句能直接展示的话）。 */
    public static AutomationRuleActionType parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var v : values()) {
        if (v.name().equalsIgnoreCase(raw.trim())) return v;
      }
      return null;
    }
  }

  /** 一次执行的结论。 */
  public enum AutomationRuleRunStatus {
    /** 执行成功。 */
    SUCCEEDED("成功"),
    /** 执行失败。 */
    FAILED("失败"),
    /** 跳过（条件未命中 / 规则停用 / 试运行不落副作用）。 */
    SKIPPED("跳过"),
    /** 超时。 */
    TIMEOUT("超时");

    private final String label;

    AutomationRuleRunStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}（服务层给一句能直接展示的话）。 */
    public static AutomationRuleRunStatus parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var v : values()) {
        if (v.name().equalsIgnoreCase(raw.trim())) return v;
      }
      return null;
    }
  }

  /** 执行记录的来源（**如实**说明这一行是谁写的）。 */
  public enum AutomationRuleExecutionSource {
    /** 由规则执行引擎写入（18.3.2 尚未交付 ⇒ 现阶段的行不会是这个来源）。 */
    ENGINE("执行引擎写入"),
    /** 人工登记（**没有**经过本系统的执行引擎）。 */
    MANUAL("人工登记");

    private final String label;

    AutomationRuleExecutionSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}（服务层给一句能直接展示的话）。 */
    public static AutomationRuleExecutionSource parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var v : values()) {
        if (v.name().equalsIgnoreCase(raw.trim())) return v;
      }
      return null;
    }
  }

  /** 失败分类。 */
  public enum AutomationRuleFailureClass {
    /** 条件未命中（跳过，不是失败）。 */
    CONDITION_NOT_MATCHED("条件未命中"),
    /** 动作执行失败。 */
    ACTION_FAILED("动作失败"),
    /** 超时。 */
    TIMEOUT("超时"),
    /** 规则被停用。 */
    RULE_DISABLED("规则已停用"),
    /** 引擎内部错误。 */
    ENGINE_ERROR("引擎错误");

    private final String label;

    AutomationRuleFailureClass(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}（服务层给一句能直接展示的话）。 */
    public static AutomationRuleFailureClass parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var v : values()) {
        if (v.name().equalsIgnoreCase(raw.trim())) return v;
      }
      return null;
    }
  }

  /**
   * 规则的健康判定（**推导**出来的，不是存下来的列）。
   *
   * <p>🔴 之所以推导而不存：存下来的「健康」会与统计口径漂移
   * （统计说连败 5 次、标记却写着正常）—— 那正是蓝图加粗句要防的静默失败。
   */
  public enum AutomationRuleHealth {
    /** 还没跑过（**「没跑过」不等于「坏了」**）。 */
    NEVER_RUN("未运行"),
    /** 最近一次成功。 */
    HEALTHY("正常"),
    /** 最近一次失败，但连续失败还没到阈值。 */
    DEGRADED("不稳定"),
    /** 🔴 连续失败达到阈值 —— **坏规则**，能被筛出来。 */
    FAILING("连续失败"),
    /** 最近一次是跳过（条件未命中 / 试运行）。 */
    SKIPPED("已跳过");

    private final String label;

    AutomationRuleHealth(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 副作用模式（由「试运行开关」**推导**）。
   *
   * <p>🔴 {@code SIMULATE} = 这条规则处于试运行，**不产生真实副作用**。
   */
  public enum AutomationRuleSideEffectMode {
    /** 真实执行（会产生副作用）。 */
    EXECUTE("真实执行"),
    /** 试运行（**不产生真实副作用**）。 */
    SIMULATE("试运行 · 不产生副作用");

    private final String label;

    AutomationRuleSideEffectMode(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 规则档案

  /** 规则档案一行（含**执行统计快照**）。 */
  public record AutomationRuleRow(
      UUID id,
      String name,
      String description,
      /** 触发事件（上游 = 各业务事件）。 */
      String triggerEventType,
      int triggerSchemaVersion,
      /** 优先级：数字越小越先执行。 */
      int priority,
      boolean enabled,
      String status,
      String statusLabel,
      /** 🔴 **试运行开关（独立状态）**。 */
      boolean dryRun,
      /** 🔴 由试运行开关推导的副作用模式。 */
      String sideEffectMode,
      String sideEffectModeLabel,
      /** 连续失败达到这个数即判为坏规则。 */
      int consecutiveFailureThreshold,
      long conditionCount,
      long actionCount,
      long scopeCount,
      // =========================================================================
      // 🔴 执行统计：**时间 / 结果 / 失败原因**三件套（不是布尔位）
      // =========================================================================
      @Schema(nullable = true) OffsetDateTime lastRunAt,
      String lastRunStatus,
      String lastRunStatusLabel,
      String lastRunFailureReason,
      @Schema(nullable = true) UUID lastRunExecutionId,
      BigDecimal runCount,
      BigDecimal successCount,
      BigDecimal failureCount,
      /** 连续失败计数。 */
      int consecutiveFailureCount,
      /** 🔴 **坏规则标记**（连续失败达到阈值）。 */
      boolean failing,
      @Schema(nullable = true) OffsetDateTime failingSince,
      /** 健康判定（推导）。 */
      String health,
      String healthLabel,
      String remark,
      UUID createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 条件（过滤）一行。 */
  public record AutomationRuleConditionRow(
      UUID id,
      int seq,
      String fieldPath,
      String operator,
      String operatorLabel,
      String valueText,
      @Schema(nullable = true) BigDecimal valueNumber,
      @Schema(nullable = true) Boolean valueBool,
      boolean enabled,
      String remark) {}

  /** 动作（多步）一行。 */
  public record AutomationRuleActionRow(
      UUID id,
      int seq,
      String actionType,
      String actionTypeLabel,
      String targetRef,
      /** 动作参数（JSON 对象文本）。 */
      String params,
      boolean enabled,
      String remark) {}

  /** 启用范围一行。 */
  public record AutomationRuleScopeRow(
      UUID id,
      String scopeType,
      String scopeTypeLabel,
      @Schema(nullable = true) UUID scopeRef,
      String scopeLabel) {}

  /** 执行记录一行（只追加台账）。 */
  public record AutomationRuleExecutionRow(
      UUID id,
      UUID ruleId,
      String ruleName,
      int runNo,
      String status,
      String statusLabel,
      String source,
      String sourceLabel,
      /** 该次执行是否处于试运行。 */
      boolean dryRun,
      /** 该次执行**是否真的产生了副作用**。 */
      boolean sideEffectsApplied,
      String triggeredBy,
      String triggerEventType,
      BigDecimal matchedConditions,
      BigDecimal plannedActions,
      BigDecimal executedActions,
      String failureReason,
      String failureClass,
      OffsetDateTime startedAt,
      OffsetDateTime finishedAt,
      UUID createdBy,
      OffsetDateTime createdAt) {}

  /** 规则档案详情（档案 + 条件 + 动作 + 范围 + 最近几次执行）。 */
  public record AutomationRuleDetail(
      AutomationRuleRow rule,
      List<AutomationRuleConditionRow> conditions,
      List<AutomationRuleActionRow> actions,
      List<AutomationRuleScopeRow> scopes,
      List<AutomationRuleExecutionRow> recentExecutions) {}

  // ================================================================ 入参

  /** 规则配置（新增 / 修改）。 */
  public record AutomationRuleInput(
      @Schema(nullable = true) UUID id,
      @Schema(nullable = true) String name,
      @Schema(nullable = true) String description,
      @Schema(nullable = true) String triggerEventType,
      @Schema(nullable = true) Integer triggerSchemaVersion,
      @Schema(nullable = true) Integer priority,
      @Schema(nullable = true) Boolean enabled,
      @Schema(nullable = true) String status,
      @Schema(nullable = true) Boolean dryRun,
      @Schema(nullable = true) Integer consecutiveFailureThreshold,
      @Schema(nullable = true) String remark,
      @Schema(nullable = true) List<AutomationRuleConditionInput> conditions,
      @Schema(nullable = true) List<AutomationRuleActionInput> actions,
      @Schema(nullable = true) List<AutomationRuleScopeInput> scopes) {}

  /** 条件（过滤）入参。 */
  public record AutomationRuleConditionInput(
      @Schema(nullable = true) Integer seq,
      @Schema(nullable = true) String fieldPath,
      @Schema(nullable = true) String operator,
      @Schema(nullable = true) String valueText,
      @Schema(nullable = true) BigDecimal valueNumber,
      @Schema(nullable = true) Boolean valueBool,
      @Schema(nullable = true) Boolean enabled,
      @Schema(nullable = true) String remark) {}

  /** 动作（多步）入参。 */
  public record AutomationRuleActionInput(
      @Schema(nullable = true) Integer seq,
      @Schema(nullable = true) String actionType,
      @Schema(nullable = true) String targetRef,
      /** 动作参数（JSON 对象文本，如 {@code {"orderStatus":"PAID"}}）。 */
      @Schema(nullable = true) String params,
      @Schema(nullable = true) Boolean enabled,
      @Schema(nullable = true) String remark) {}

  /** 启用范围入参。 */
  public record AutomationRuleScopeInput(
      @Schema(nullable = true) String scopeType,
      @Schema(nullable = true) UUID scopeRef,
      @Schema(nullable = true) String scopeLabel) {}

  /** 试运行开关（**独立状态**）。 */
  public record AutomationRuleDryRunInput(Boolean dryRun) {}

  /** 排序入参（一次性给出若干条规则的优先级）。 */
  public record AutomationRuleReorderInput(List<AutomationRuleOrderEntry> entries) {}

  /** 排序条目。 */
  public record AutomationRuleOrderEntry(UUID ruleId, Integer priority) {}

  /**
   * 登记一次执行结果。
   *
   * <p>🔴 {@code sideEffectsApplied} 对**试运行规则**恒为 false（服务层 409 + DB 层 CHECK）。
   */
  public record AutomationRuleExecutionInput(
      @Schema(nullable = true) String status,
      @Schema(nullable = true) String source,
      @Schema(nullable = true) Boolean sideEffectsApplied,
      @Schema(nullable = true) String triggeredBy,
      @Schema(nullable = true) String triggerEventType,
      @Schema(nullable = true) Long matchedConditions,
      @Schema(nullable = true) Long plannedActions,
      @Schema(nullable = true) Long executedActions,
      @Schema(nullable = true) String failureReason,
      @Schema(nullable = true) String failureClass,
      @Schema(nullable = true) String startedAt,
      @Schema(nullable = true) String finishedAt) {}

  /** 预演入参（样本载荷 + 生效范围）。 */
  public record AutomationRulePreviewInput(
      @Schema(nullable = true) String payload, @Schema(nullable = true) UUID shopId,
      @Schema(nullable = true) UUID warehouseId) {}

  // ================================================================ 预演输出

  /** 条件求值结果（逐条）。 */
  public record AutomationRuleConditionEvaluation(
      int seq,
      String fieldPath,
      String operator,
      String operatorLabel,
      String expectedValue,
      String actualValue,
      boolean matched,
      String reason) {}

  /**
   * 规则预演结果。
   *
   * <p>🔴 {@code sideEffectMode} = {@code SIMULATE} 时 {@code wouldProduceSideEffects} 恒为 false。
   */
  public record AutomationRulePreview(
      UUID ruleId,
      String ruleName,
      boolean enabled,
      boolean dryRun,
      String sideEffectMode,
      String sideEffectModeLabel,
      boolean scopeApplicable,
      String scopeNote,
      int totalConditions,
      int matchedConditions,
      boolean conditionsMatched,
      List<AutomationRuleConditionEvaluation> conditionEvaluations,
      List<AutomationRuleActionRow> plannedActions,
      boolean wouldProduceSideEffects,
      String note) {}

  /** 生效范围内的一条规则（**范围是真约束** ⇒ 这行来自范围表的真实 JOIN）。 */
  public record AutomationRuleApplicableRow(
      UUID ruleId,
      String ruleName,
      int priority,
      boolean enabled,
      boolean dryRun,
      String sideEffectMode,
      String health,
      String healthLabel,
      String matchedScopeType,
      String matchedScopeTypeLabel,
      String matchedScopeLabel) {}

  // ================================================================ 上游（只读）

  /** 可用触发事件（来自平台事件注册表，18.1.2.1 交付）。 */
  public record AutomationRuleEventTypeRow(
      String eventType, int schemaVersion, String status, String sourceService, String sourceArtifact) {}

  /** 上游 2.3 店铺（**只读**，来自 channel 服务）。 */
  public record AutomationRuleUpstreamShopRow(
      UUID id, String code, String name, String platformCode, String platformName, String status) {}

  /** 上游仓库（**只读**，来自 warehouse 服务）。 */
  public record AutomationRuleUpstreamWarehouseRow(
      UUID id, String code, String name, String countryCode, String status, boolean isDefault) {}
}
