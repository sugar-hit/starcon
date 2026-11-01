package com.starcon.platform;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 18.3.1.2「规则编辑器」的传输模型。
 *
 * <p>蓝图 18-integration-and-automation.md 原文：
 *
 * <blockquote>
 *
 * <b>职责</b>：可视化编辑规则：选择触发事件 → 配置条件（字段/操作符/值）→ 编排动作（调接口/改状态/建任务/发通知/生成单据）→
 * 测试与保存。<br>
 * <b>输入</b>：规则配置；测试样本数据。<br>
 * <b>输出</b>：规则定义；测试运行结果。<br>
 * <b>关系</b>：上游 = 18.3.1.1；下游 = 18.3.2 执行。<b>规则必须支持用样本测试</b>，否则只能在生产上试错。
 *
 * </blockquote>
 *
 * <p><b>🔴 前缀是 {@code AutomationRuleEditor}**：契约被 codegen 合并进<b>同一个扁平命名空间</b>，
 * 而 18.3.1.1 已经占了 {@code AutomationRule*} 一族 ⇒ 本模块的类型必须带自己的前缀，
 * 否则「规则档案」与「规则编辑器」在契约层就分不清了。
 *
 * <p><b>🔴 本模块一个 {@code double} 都没有</b>：条件数 / 命中数 / 动作数一律
 * {@code int}（计数不是业务数量，不带小数；库里落 {@code NUMERIC(19,6)}）。
 *
 * <p><b>🔴 复用 18.3.1.1 的模型，不另起一套</b>：规则配置入参用
 * {@link AutomationRuleModels.AutomationRuleInput}，条件求值明细用
 * {@link AutomationRuleModels.AutomationRuleConditionEvaluation} ——
 * 编辑器与规则列表讲的是<b>同一条规则</b>，两套模型只会在合并时打架。
 *
 * <p><b>🔴 动作「计划」另起一个不带 id 的形状</b>（{@link AutomationRuleEditorActionPlan}）：
 * 草稿里的动作<b>还不是行</b>，给它编一个 id 就是造假；而
 * {@link AutomationRuleModels.AutomationRuleActionRow} 的 id 在契约里是<b>非空</b>的
 * ⇒ 拿它装草稿动作要么编 id、要么在类型上撒谎。计划本身只需要
 * 序号 / 类型 / 目标 / 参数 —— 这正是「动作编排」要看的东西。
 */
public final class AutomationRuleEditorModels {

  private AutomationRuleEditorModels() {}

  // ================================================================ 能力声明

  /**
   * 一项能力（或一项「还没接通」的能力）。
   *
   * @param available 该能力**现在**能不能用（不是「将来会有」）
   * @param evidence 凭什么这么判（表 / 端点 / 真实数据源的名字）
   * @param ownerModule 还没接通的能力，**缺在哪个模块**
   * @param note 缺了会怎样 / 缺了归谁
   */
  public record AutomationRuleEditorCapability(
      String moduleId,
      String name,
      boolean available,
      String evidence,
      String ownerModule,
      String note) {}

  /** 一个「还没交付」的下游 / 兄弟能力。 */
  public record AutomationRuleEditorGap(
      String moduleId, String name, String service, boolean available, String relation, String note) {}

  /** 能力声明总览。 */
  public record AutomationRuleEditorCapabilities(
      List<AutomationRuleEditorCapability> capabilities, List<AutomationRuleEditorGap> gaps) {}

  // ================================================================ 字段目录

  /**
   * 事件载荷里的**一个字段**（从事件注册表的 {@code payload_schema} 摊平而来）。
   *
   * @param path 字段路径（如 {@code order.totalAmount}）
   * @param type 字段类型（{@code string} / {@code number} / {@code integer} / {@code boolean} / {@code object} / {@code array} / {@code unknown}）
   * @param label 人可读标签（schema 的 {@code description}，没有就用字段名）
   * @param required 该字段在父对象里是不是必填
   * @param enumValues 枚举候选（schema 里写了 {@code enum} 才有；否则空集合）
   * @param allowedOperators 这个类型上**说得通**的操作符（类型不符的比较在服务层直接拒掉）
   */
  public record AutomationRuleEditorField(
      String path,
      String type,
      String label,
      boolean required,
      List<String> enumValues,
      List<String> allowedOperators) {}

  /**
   * 某个触发事件的**条件字段目录**。
   *
   * @param schemaDeclared 事件**到底登记没登记**字段结构（没登记 ⇒ 字段目录为空，这是事实，不是缺数据）
   */
  public record AutomationRuleEditorEventSchema(
      String eventType,
      int schemaVersion,
      String status,
      String sourceService,
      String sourceArtifact,
      boolean schemaDeclared,
      String note,
      List<AutomationRuleEditorField> fields) {}

  // ================================================================ 动作目录

  /** 一个动作参数的规格。 */
  public record AutomationRuleEditorParamSpec(
      String name,
      String label,
      /** 参数类型：{@code TEXT} / {@code TEXT_LIST} / {@code OBJECT} / {@code NUMBER} / {@code BOOL}。 */
      String type,
      boolean required,
      String description,
      List<String> enumValues) {}

  /** 动作类型目录里的一项（五类：调接口 / 改状态 / 建任务 / 发通知 / 生成单据）。 */
  public record AutomationRuleEditorActionType(
      String actionType,
      String label,
      String description,
      /** 动作目标（{@code target_ref}）该填什么。 */
      String targetLabel,
      List<AutomationRuleEditorParamSpec> params) {}

  /** 动作类型目录总览。 */
  public record AutomationRuleEditorActionCatalog(List<AutomationRuleEditorActionType> actionTypes) {}

  /** 「调接口」动作可选的目标接口（来自 18.1.1.2 的接口目录 = 各服务活 spec 的投影）。 */
  public record AutomationRuleEditorApiEndpoint(
      String service, String method, String path, String summary, String domain) {}

  /**
   * 接口目录的可读状态。
   *
   * @param available 接口目录**读到了没有**（读不到时 {@code endpoints} 为空，且 {@code note} 写明原因）
   */
  public record AutomationRuleEditorApiCatalog(
      List<AutomationRuleEditorApiEndpoint> endpoints, boolean available, String note) {}

  // ================================================================ 动作计划

  /**
   * 动作**计划**里的一步（🔴 **不带 id**：草稿里的动作还不是行）。
   *
   * @param params 动作参数（JSON 对象文本）
   */
  public record AutomationRuleEditorActionPlan(
      int seq,
      String actionType,
      String actionTypeLabel,
      String targetRef,
      String params,
      boolean enabled,
      String remark) {}

  // ================================================================ 测试

  /**
   * 样本测试入参：**规则配置** + **测试样本数据**（蓝图的两个输入）。
   *
   * <p>规则配置直接用 18.3.1.1 的 {@link AutomationRuleModels.AutomationRuleInput} ——
   * 编辑器编出来的东西与规则档案是同一种形状，保存路径才能复用。
   */
  public record AutomationRuleEditorTestInput(
      @Schema(nullable = true) AutomationRuleInput rule,
      @Schema(nullable = true) String payload,
      @Schema(nullable = true) UUID shopId,
      @Schema(nullable = true) UUID warehouseId) {}

  /**
   * 样本测试结果。
   *
   * <p>🔴 {@code sideEffectsApplied} **恒为 false**：18.3.2 执行引擎尚未交付 ⇒
   * 「测试」只求值、只给动作计划，<b>不执行任何动作</b>。
   *
   * @param ruleId 被测规则（🔴 **草稿测试时为空** —— 规则还没保存）
   * @param draft 测的是不是**草稿**（true = 还没保存的配置）
   */
  public record AutomationRuleEditorTestResult(
      UUID testRunId,
      @Schema(nullable = true) UUID ruleId,
      String ruleName,
      boolean draft,
      int runNo,
      boolean matched,
      int totalConditions,
      int matchedConditions,
      int plannedActions,
      boolean sideEffectsApplied,
      boolean scopeApplicable,
      String scopeNote,
      List<AutomationRuleConditionEvaluation> conditionEvaluations,
      List<AutomationRuleEditorActionPlan> actionPlan,
      String failureReason,
      String note,
      OffsetDateTime createdAt) {}

  /** 测试运行台账的一行（**只追加**）。 */
  public record AutomationRuleEditorTestRunRow(
      UUID id,
      @Schema(nullable = true) UUID ruleId,
      String ruleName,
      boolean draft,
      int runNo,
      String triggerEventType,
      int triggerSchemaVersion,
      boolean matched,
      int totalConditions,
      int matchedConditions,
      int plannedActions,
      boolean sideEffectsApplied,
      boolean scopeApplicable,
      String scopeNote,
      List<AutomationRuleConditionEvaluation> conditionEvaluations,
      List<AutomationRuleEditorActionPlan> actionPlan,
      String failureReason,
      @Schema(nullable = true) UUID createdBy,
      OffsetDateTime createdAt) {}
}
