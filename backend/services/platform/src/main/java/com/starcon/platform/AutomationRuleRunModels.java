package com.starcon.platform;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 18.3.2.1「执行日志」的传输模型。
 *
 * <p>蓝图 18-integration-and-automation.md 原文：
 *
 * <blockquote>
 *
 * 职责：记录每次规则执行：触发事件、条件命中情况、执行的动作、每步结果、耗时、错误；支持查看上下文与手动重跑。
 * 输入：执行记录；重跑请求。 输出：执行明细 + 失败原因定位。
 * 关系：上游 = 18.3.1.2；下游 = 1 工作台告警。
 * <b>规则必须幂等</b>：同一事件重复触发不能重复执行副作用（如重复改价、重复建单）。
 *
 * </blockquote>
 *
 * <p><b>🔴 前缀是 {@code AutomationRuleRun}</b>：契约被 codegen 合并进<b>同一个扁平命名空间</b>，
 * 而 18.3.1.1 已经有 {@code AutomationRule*} 一族类型（{@code AutomationRuleRow} /
 * {@code AutomationRuleExecutionRow} …）。本模块的类型一律带 {@code Run} 段，
 * 免得读代码的人把「18.3.1.1 的执行统计」与「18.3.2.1 的执行明细」当成一件事 ——
 * 它们是**两张不同的表**、两个不同的写入所有者。
 *
 * <p><b>🔴 本模块一个 {@code double} 都没有</b>：数量一律 {@link BigDecimal}
 * （库里 {@code NUMERIC(19,6)}），耗时一律**整数毫秒**（{@code long}）。
 *
 * <p><b>🔴 「未执行」是一个独立的结论</b>（{@link AutomationRuleRunOutcome#NOT_ATTEMPTED}）：
 * 手动重跑在本仓当前<b>没有执行器</b> ⇒ 落成一条「待执行 / 未接通」的记录，
 * <b>绝不</b>写成「已重跑成功」。伪造一次成功比不做重跑更糟。
 */
public final class AutomationRuleRunModels {

  private AutomationRuleRunModels() {}

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
  public record AutomationRuleRunCapability(
      String moduleId,
      String name,
      boolean available,
      String evidence,
      String ownerModule,
      String note) {}

  /** 一个「还没交付」的下游 / 兄弟能力。 */
  public record AutomationRuleRunGap(
      String moduleId, String name, String service, boolean available, String relation, String note) {}

  /** 能力声明总览。 */
  public record AutomationRuleRunCapabilities(
      List<AutomationRuleRunCapability> capabilities, List<AutomationRuleRunGap> gaps) {}

  /** 概览：几个数。 */
  public record AutomationRuleRunOverview(
      long runs,
      long succeededRuns,
      long failedRuns,
      long skippedRuns,
      long timeoutRuns,
      /** 🔴 未执行的次数（执行器未接通 ⇒ 重跑请求都落在这里）。 */
      long notAttemptedRuns,
      long reruns,
      long sideEffectRuns,
      long totalDurationMs,
      long failedSteps,
      OffsetDateTime generatedAt) {}

  // ================================================================ 枚举

  /** 一次执行的结论。 */
  public enum AutomationRuleRunOutcome {
    /** 执行成功。 */
    SUCCEEDED("成功"),
    /** 执行失败。 */
    FAILED("失败"),
    /** 跳过（条件未命中 / 规则被停用 / 试运行不落副作用）。 */
    SKIPPED("跳过"),
    /** 超时。 */
    TIMEOUT("超时"),
    /** 🔴 **未执行**（执行器未接通）—— 不是「成功」，也不是「失败」。 */
    NOT_ATTEMPTED("未执行");

    private final String label;

    AutomationRuleRunOutcome(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}（服务层给一句能直接展示的话）。 */
    public static AutomationRuleRunOutcome parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var v : values()) {
        if (v.name().equalsIgnoreCase(raw.trim())) return v;
      }
      return null;
    }
  }

  /** 执行记录的来源（**如实**说明这一行是谁写的）。 */
  public enum AutomationRuleRunSource {
    /** 由规则执行引擎写入（18.3.2 尚未交付 ⇒ 现阶段的行不会是这个来源）。 */
    ENGINE("执行引擎写入"),
    /** 人工登记。 */
    MANUAL("人工登记"),
    /** 🔴 手动重跑请求（**新的一次执行**，与原 run 显式关联）。 */
    RERUN("手动重跑");

    private final String label;

    AutomationRuleRunSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}（服务层给一句能直接展示的话）。 */
    public static AutomationRuleRunSource parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var v : values()) {
        if (v.name().equalsIgnoreCase(raw.trim())) return v;
      }
      return null;
    }
  }

  /** 逐步明细的种类。 */
  public enum AutomationRuleRunStepKind {
    /** 条件判定。 */
    CONDITION("条件判定"),
    /** 动作执行。 */
    ACTION("动作执行");

    private final String label;

    AutomationRuleRunStepKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}（服务层给一句能直接展示的话）。 */
    public static AutomationRuleRunStepKind parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var v : values()) {
        if (v.name().equalsIgnoreCase(raw.trim())) return v;
      }
      return null;
    }
  }

  /** 一步的结果。 */
  public enum AutomationRuleRunStepResult {
    /** 成功。 */
    SUCCEEDED("成功"),
    /** 失败。 */
    FAILED("失败"),
    /** 跳过。 */
    SKIPPED("跳过"),
    /** 🔴 条件未命中（这不是「失败」—— 条件不满足本来就该跳过）。 */
    NOT_MATCHED("条件未命中"),
    /** 未执行（执行器未接通）。 */
    NOT_ATTEMPTED("未执行");

    private final String label;

    AutomationRuleRunStepResult(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}（服务层给一句能直接展示的话）。 */
    public static AutomationRuleRunStepResult parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var v : values()) {
        if (v.name().equalsIgnoreCase(raw.trim())) return v;
      }
      return null;
    }
  }

  /** 失败分类。 */
  public enum AutomationRuleRunFailureClass {
    /** 条件未命中（跳过，不是失败）。 */
    CONDITION_NOT_MATCHED("条件未命中"),
    /** 动作执行失败。 */
    ACTION_FAILED("动作失败"),
    /** 超时。 */
    TIMEOUT("超时"),
    /** 规则被停用。 */
    RULE_DISABLED("规则已停用"),
    /** 引擎内部错误。 */
    ENGINE_ERROR("引擎错误"),
    /** 🔴 **执行器未接通**（手动重跑落在这里）。 */
    EXECUTOR_UNAVAILABLE("执行器未接通");

    private final String label;

    AutomationRuleRunFailureClass(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}（服务层给一句能直接展示的话）。 */
    public static AutomationRuleRunFailureClass parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var v : values()) {
        if (v.name().equalsIgnoreCase(raw.trim())) return v;
      }
      return null;
    }
  }

  // ================================================================ 执行记录

  /** 一次执行的明细台账一行。 */
  public record AutomationRuleRunRow(
      UUID id,
      UUID ruleId,
      /** 规则名快照（规则后来改名了，历史里仍看得到当时跑的是哪条）。 */
      String ruleName,
      /** 🔴 触发事件 id（幂等键的一半）。 */
      String eventId,
      /** 🔴 幂等键。 */
      String idempotencyKey,
      String triggerEventType,
      String triggeredBy,
      /** 🔴 触发上下文（JSON 对象文本）。 */
      String eventPayload,
      String outcome,
      String outcomeLabel,
      String source,
      String sourceLabel,
      /** 🔴 试运行标记（由规则档案推导）。 */
      boolean dryRun,
      /** 🔴 该次执行**是否真的产生了副作用**。 */
      boolean sideEffectsApplied,
      BigDecimal matchedConditions,
      BigDecimal plannedActions,
      BigDecimal executedActions,
      /** 🔴 总耗时，**整数毫秒**。 */
      long durationMs,
      String failureReason,
      String failureClass,
      String failureClassLabel,
      String errorCode,
      String errorMessage,
      /** 🔴 未接通原因（只在「未执行」这一种结论上有值）。 */
      String notAttemptedReason,
      /** 🔴 手动重跑：指向被重跑的那条 run。 */
      @Schema(nullable = true) UUID rerunOfRunId,
      @Schema(nullable = true) Integer rerunSeq,
      @Schema(nullable = true) UUID rerunRequestedBy,
      @Schema(nullable = true) OffsetDateTime rerunRequestedAt,
      String rerunReason,
      OffsetDateTime startedAt,
      OffsetDateTime finishedAt,
      @Schema(nullable = true) UUID createdBy,
      OffsetDateTime createdAt) {}

  /** 逐步明细一行。 */
  public record AutomationRuleRunStepRow(
      UUID id,
      UUID runId,
      /** 🔴 第几步（全序，不靠 DB 返回顺序）。 */
      int stepNo,
      String stepKind,
      String stepKindLabel,
      /** 🔴 命中的是**哪条 condition**（18.3.1.1 的条件行）。 */
      @Schema(nullable = true) UUID conditionId,
      @Schema(nullable = true) Integer conditionSeq,
      String fieldPath,
      String operator,
      String expectedValue,
      String actualValue,
      /** 🔴 **条件命中情况**（命中与否）；动作步骤为空。 */
      @Schema(nullable = true) Boolean matched,
      String actionType,
      String targetRef,
      /** 动作参数（JSON 对象文本）。 */
      String params,
      String result,
      String resultLabel,
      /** 🔴 每步耗时，**整数毫秒**。 */
      long durationMs,
      String errorCode,
      String errorMessage,
      boolean sideEffectApplied,
      OffsetDateTime createdAt) {}

  /** 一次执行的详情（**含逐步明细**，失败原因可以从 run 直接下钻到 step）。 */
  public record AutomationRuleRunDetail(
      AutomationRuleRunRow run, List<AutomationRuleRunStepRow> steps, long rerunCount) {}

  /**
   * 手动重跑的结果。
   *
   * @param detail 新落成的那条重跑记录（含逐步明细）
   * @param duplicate **true** = 同一个幂等键已经提交过一次，返回的是**那一条**记录
   *     （双击按钮不会变成跑两次）
   * @param note 如实说明（为什么是重复 / 为什么没真的执行）
   */
  public record AutomationRuleRunRerunResult(
      AutomationRuleRunDetail detail, boolean duplicate, String note) {}

  /** 执行日志列表页（**真分页**：items / page / size / total）。 */
  public record AutomationRuleRunPage(
      List<AutomationRuleRunRow> items, int page, int size, long total) {}

  // ================================================================ 入参

  /**
   * 登记一次执行。
   *
   * <p>🔴 **不接受 {@code tenantId} / {@code userId} / {@code membershipId}**：
   * Jackson {@code fail-on-unknown-properties} 直接 400 拒掉，不是被静默忽略。
   * 租户与操作人一律从**已认证的成员关系**解析。
   *
   * <p>🔴 **不接受 {@code dryRun}**：试运行标记由**规则档案**推导（服务层读规则），
   * 客户端自称「我这是试运行」不算数。
   */
  public record AutomationRuleRunInput(
      @Schema(nullable = true) UUID ruleId,
      /** 🔴 触发事件 id（幂等键的一半，必填）。 */
      @Schema(nullable = true) String eventId,
      @Schema(nullable = true) String triggerEventType,
      @Schema(nullable = true) String triggeredBy,
      /** 触发上下文（JSON 对象文本，如 {@code {"orderNo":"SO-1"}}）。 */
      @Schema(nullable = true) String eventPayload,
      /** 结论：SUCCEEDED / FAILED / SKIPPED / TIMEOUT / NOT_ATTEMPTED。 */
      @Schema(nullable = true) String outcome,
      @Schema(nullable = true) String source,
      @Schema(nullable = true) Boolean sideEffectsApplied,
      @Schema(nullable = true) Long matchedConditions,
      @Schema(nullable = true) Long plannedActions,
      @Schema(nullable = true) Long executedActions,
      /** 总耗时，**整数毫秒**。 */
      @Schema(nullable = true) Long durationMs,
      @Schema(nullable = true) String failureReason,
      @Schema(nullable = true) String failureClass,
      @Schema(nullable = true) String errorCode,
      @Schema(nullable = true) String errorMessage,
      @Schema(nullable = true) String notAttemptedReason,
      @Schema(nullable = true) String startedAt,
      @Schema(nullable = true) String finishedAt,
      @Schema(nullable = true) List<AutomationRuleRunStepInput> steps) {}

  /** 逐步明细入参。 */
  public record AutomationRuleRunStepInput(
      @Schema(nullable = true) Integer stepNo,
      /** 种类：CONDITION（条件判定）/ ACTION（动作执行）。 */
      @Schema(nullable = true) String stepKind,
      /** 动作类型（step_kind = ACTION 时必填）。 */
      @Schema(nullable = true) String actionType,
      /** 命中的 condition（18.3.1.1 的条件行 id）。 */
      @Schema(nullable = true) UUID conditionId,
      @Schema(nullable = true) Integer conditionSeq,
      @Schema(nullable = true) String fieldPath,
      @Schema(nullable = true) String operator,
      @Schema(nullable = true) String expectedValue,
      @Schema(nullable = true) String actualValue,
      /** 🔴 条件命中情况（命中与否）；step_kind = CONDITION 时必填。 */
      @Schema(nullable = true) Boolean matched,
      @Schema(nullable = true) String targetRef,
      /** 动作参数（JSON 对象文本）。 */
      @Schema(nullable = true) String params,
      /** 步结果：SUCCEEDED / FAILED / SKIPPED / NOT_MATCHED / NOT_ATTEMPTED。 */
      @Schema(nullable = true) String result,
      /** 每步耗时，**整数毫秒**。 */
      @Schema(nullable = true) Long durationMs,
      @Schema(nullable = true) String errorCode,
      @Schema(nullable = true) String errorMessage,
      @Schema(nullable = true) Boolean sideEffectApplied) {}

  /**
   * 手动重跑请求。
   *
   * <p>🔴 **幂等键**（可选）：同一个幂等键重复提交 ⇒ 返回**同一条**重跑记录，
   * 不会多出第二次重跑（双击按钮不会变成跑两次）。
   */
  public record AutomationRuleRunRerunInput(
      @Schema(nullable = true) String idempotencyKey,
      /** 重跑理由（人可读：「为什么再跑一次」）。 */
      @Schema(nullable = true) String reason) {}
}
