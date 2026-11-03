package com.starcon.platform;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 18.3.2.2「规则效果」的传输模型。
 *
 * <p>蓝图 18-integration-and-automation.md 原文：
 *
 * <blockquote>
 *
 * 职责：统计规则效果：触发次数、成功率、平均耗时、节省工时预估、误触发反馈与规则优化建议。
 * 输入：执行日志聚合。 输出：规则效果报表。
 * 关系：上游 = 18.3.2.1；下游 = 18.3.1.1（优化依据）。
 * <b>误触发率高的规则要能快速定位并关闭</b>，自动化失控比没有自动化更危险。
 *
 * </blockquote>
 *
 * <p><b>🔴 前缀是 {@code AutomationRuleEffect}</b>：契约被 codegen 合并进<b>同一个扁平命名空间</b>，
 * 而 18.3.1.1 已有 {@code AutomationRule*} 一族、18.3.2.1 已有 {@code AutomationRuleRun*} 一族。
 * 本模块的类型一律带 {@code Effect} 段 —— 「规则的效果」与「规则的执行」「规则的档案」
 * 是**三件不同的事**，混在一起命名迟早读错。
 *
 * <p><b>🔴 本模块一个 {@code double} 都没有</b>：数量与比率一律 {@link BigDecimal}
 * （库里 {@code NUMERIC(19,6)}），耗时一律**整数毫秒**（{@code long}）。
 *
 * <p><b>🔴 五档结论不许压扁</b>：18.3.2.1 的结论是五档 ——
 * 成功 / 失败 / 跳过 / 超时 / <b>未执行</b>。算成功率时「未执行」<b>既不算成功也不算失败</b>，
 * 必须单列一档（否则报表会说谎）。
 *
 * <p><b>🔴 节省工时预估必须带口径</b>：{@link AutomationRuleEffectSavingBasis} 与
 * {@link AutomationRuleEffectRuleRow#baselineSource()} 一起返回 ——
 * 只给一个数字不给口径，那个数字不能用于决策。
 */
public final class AutomationRuleEffectModels {

  private AutomationRuleEffectModels() {}

  /** 数量小数位（与本服务 18.3.2.1 / 18.3.1.1 同一口径）。 */
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
  public record AutomationRuleEffectCapability(
      String moduleId,
      String name,
      boolean available,
      String evidence,
      String ownerModule,
      String note) {}

  /** 一个「还没交付」的下游 / 兄弟能力。 */
  public record AutomationRuleEffectGap(
      String moduleId, String name, String service, boolean available, String relation, String note) {}

  /** 能力声明总览。 */
  public record AutomationRuleEffectCapabilities(
      List<AutomationRuleEffectCapability> capabilities, List<AutomationRuleEffectGap> gaps) {}

  // ================================================================ 口径

  /**
   * 🔴 **节省工时预估的口径**。
   *
   * <p>蓝图要「节省工时预估」，但预估不能是常量。这里把「预估从哪张表、哪个字段、
   * 怎么算、什么被排除了」**原样返回给前端** —— 只给一个数字不给口径，
   * 那个数字就不能用于决策。
   *
   * @param sourceTable 口径落在哪张表
   * @param sourceField 口径的哪个字段
   * @param formula 公式
   * @param excluded 哪些**不**计入
   * @param unit 单位
   * @param note 补充说明（为什么要这么算）
   */
  public record AutomationRuleEffectSavingBasis(
      String sourceTable,
      String sourceField,
      String formula,
      String excluded,
      String unit,
      String note) {}

  // ================================================================ 概览

  /**
   * 规则效果概览。
   *
   * <p>🔴 <b>五档结论分开列</b>：{@code skippedRuns}（跳过）与 {@code notAttemptedRuns}（未执行）
   * 都不是「失败」，也都不进成功率的母。
   */
  public record AutomationRuleEffectOverview(
      /** 🔴 触发次数（执行台账里的总行数）。 */
      long totalRuns,
      long succeededRuns,
      long failedRuns,
      long skippedRuns,
      long timeoutRuns,
      /** 🔴 未执行（执行器未接通 ⇒ 重跑请求都落在这里）。 */
      long notAttemptedRuns,
      /** 🔴 有效执行次数 = 成功 + 失败 + 超时（成功率的**分母**）。 */
      long ratedRuns,
      /** 🔴 成功率；分母为 0 时是 {@code null}（**不是 0%**）。 */
      @Schema(nullable = true) BigDecimal successRate,
      long totalDurationMs,
      long ratedDurationMs,
      /** 🔴 平均耗时（**整数毫秒**，只在真正跑过的 run 上平均）。 */
      long avgDurationMs,
      long rulesWithRuns,
      long rulesWithBaseline,
      long rulesWithoutBaseline,
      /** 🔴 节省工时预估（分钟）；**没有口径的规则不计入**。 */
      @Schema(nullable = true) BigDecimal savedMinutes,
      AutomationRuleEffectSavingBasis savingBasis,
      long falseTriggerFeedbacks,
      /** 🔴 指向**有效执行**的误触发 run 数（误触发率的分子）。 */
      long falseTriggerRuns,
      /** 🔴 没有指向具体 run 的反馈条数（**不计入分子**，但如实显示）。 */
      long unlinkedFeedbacks,
      /** 🔴 误触发率；分母为 0 时是 {@code null}。 */
      @Schema(nullable = true) BigDecimal falseTriggerRate,
      long openAdvices,
      long criticalAdvices,
      /** 🔴 可下钻的最近 run id（每个聚合数字都要能追到来源 run）。 */
      List<UUID> recentRunIds,
      OffsetDateTime generatedAt) {}

  // ================================================================ 按规则的效果明细

  /** 一条规则的效果（一行）。 */
  public record AutomationRuleEffectRuleRow(
      UUID ruleId,
      /** 规则名快照（取最近一次执行时的名字）。 */
      String ruleName,
      String triggerEventType,
      long totalRuns,
      long succeededRuns,
      long failedRuns,
      long skippedRuns,
      long timeoutRuns,
      long notAttemptedRuns,
      long ratedRuns,
      @Schema(nullable = true) BigDecimal successRate,
      long totalDurationMs,
      long ratedDurationMs,
      long avgDurationMs,
      long falseTriggerFeedbacks,
      long unlinkedFeedbacks,
      long falseTriggerRuns,
      @Schema(nullable = true) BigDecimal falseTriggerRate,
      /** 🔴 该规则的人工基线（**没有口径就是 null**，不是 0）。 */
      @Schema(nullable = true) BigDecimal manualMinutesPerRun,
      String baselineSource,
      /** 🔴 节省工时预估（分钟）；没有口径时是 {@code null}。 */
      @Schema(nullable = true) BigDecimal savedMinutes,
      long openAdvices,
      /** 🔴 这条规则**是否被建议关闭**（误触发率过高）。 */
      boolean adviseDisable,
      /** 建议关闭的理由（人可读）。 */
      String adviseReason,
      @Schema(nullable = true) OffsetDateTime lastRunAt,
      /** 最近一次执行的 run id（可下钻）。 */
      @Schema(nullable = true) UUID lastRunId) {}

  /** 按规则的效果明细（**真分页**）。 */
  public record AutomationRuleEffectRulePage(
      List<AutomationRuleEffectRuleRow> items, int page, int size, long total) {}

  /** 单条规则的效果详情（聚合 + 反馈 + 建议 + 可下钻的执行记录）。 */
  public record AutomationRuleEffectRuleDetail(
      AutomationRuleEffectRuleRow rule,
      @Schema(nullable = true) AutomationRuleEffectBaselineRow baseline,
      List<AutomationRuleEffectFeedbackRow> feedbacks,
      List<AutomationRuleEffectAdviceRow> advices,
      List<AutomationRuleEffectRunRef> recentRuns,
      long runCount) {}

  // ================================================================ 执行记录下钻

  /** 执行记录的一条**下钻引用**（足够定位到 18.3.2.1 的详情）。 */
  public record AutomationRuleEffectRunRef(
      UUID id,
      String eventId,
      String outcome,
      String outcomeLabel,
      long durationMs,
      boolean sideEffectsApplied,
      boolean dryRun,
      @Schema(nullable = true) String failureReason,
      OffsetDateTime createdAt) {}

  /** 执行记录下钻列表（**真分页**）。 */
  public record AutomationRuleEffectRunPage(
      List<AutomationRuleEffectRunRef> items, int page, int size, long total) {}

  // ================================================================ 口径（人工基线）

  /** 一条节省工时口径。 */
  public record AutomationRuleEffectBaselineRow(
      UUID id,
      UUID ruleId,
      String ruleName,
      BigDecimal manualMinutesPerRun,
      String baselineSource,
      String baselineNote,
      OffsetDateTime effectiveFrom,
      OffsetDateTime updatedAt) {}

  /** 口径列表（**真分页**）。 */
  public record AutomationRuleEffectBaselinePage(
      List<AutomationRuleEffectBaselineRow> items, int page, int size, long total) {}

  // ================================================================ 误触发反馈

  /** 一条误触发反馈。 */
  public record AutomationRuleEffectFeedbackRow(
      UUID id,
      UUID ruleId,
      String ruleName,
      @Schema(nullable = true) UUID runId,
      String falseTriggerType,
      String falseTriggerTypeLabel,
      String note,
      String status,
      String statusLabel,
      @Schema(nullable = true) UUID reportedBy,
      OffsetDateTime createdAt) {}

  /** 误触发反馈列表（**真分页**）。 */
  public record AutomationRuleEffectFeedbackPage(
      List<AutomationRuleEffectFeedbackRow> items, int page, int size, long total) {}

  // ================================================================ 优化建议

  /** 一条规则优化建议。 */
  public record AutomationRuleEffectAdviceRow(
      UUID id,
      UUID ruleId,
      String ruleName,
      String adviceType,
      String adviceTypeLabel,
      String severity,
      String severityLabel,
      String title,
      String detail,
      /** 🔴 依据（JSON 对象文本）—— 没有依据的建议是空话。 */
      String evidence,
      String source,
      String status,
      String statusLabel,
      /** 🔴 这条建议**当前是否仍然成立**。 */
      boolean active,
      /** 🔴 动作归属（恒为 18.3.1.1 —— 关闭动作归规则档案）。 */
      String targetModule,
      /** 定位引用（指向规则档案里那条规则）。 */
      String targetRef,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 优化建议列表（**真分页**）。 */
  public record AutomationRuleEffectAdvicePage(
      List<AutomationRuleEffectAdviceRow> items, int page, int size, long total) {}

  // ================================================================ 入参

  /**
   * 配置一条规则的**人工基线**（节省工时预估的口径）。
   *
   * <p>🔴 **不接受 {@code tenantId} / {@code userId} / {@code membershipId}**：
   * Jackson {@code fail-on-unknown-properties} 直接 400 拒掉，不是被静默忽略。
   * 租户与操作人一律从**已认证的成员关系**解析。
   */
  public record AutomationRuleEffectBaselineInput(
      UUID ruleId,
      /** 一次**成功**执行替代多少分钟人工（禁浮点）。 */
      BigDecimal manualMinutesPerRun,
      /** 口径来源：MANUAL_ESTIMATE / OBSERVED_AVERAGE / POLICY。 */
      String baselineSource,
      /** 这个分钟数**怎么算出来的**（人可读）。 */
      String baselineNote,
      /** 口径生效时间（ISO-8601 带时区；不填就是「现在起生效」）。 */
      @Schema(nullable = true) String effectiveFrom) {}

  /**
   * 登记一条**误触发反馈**。
   *
   * <p>🔴 请求体**不接受** {@code tenantId} / {@code userId} / {@code membershipId}。
   */
  public record AutomationRuleEffectFeedbackInput(
      UUID ruleId,
      /** 指向**哪一次执行**（可空：反馈不一定能定位到某一次）。 */
      @Schema(nullable = true) UUID runId,
      /** 误触发分类：WRONG_CONDITION / WRONG_SCOPE / TOO_FREQUENT / UNEXPECTED_ACTION / OTHER。 */
      String falseTriggerType,
      /** 反馈说明（「OTHER」类**必须**写清）。 */
      String note) {}

  /**
   * 登记一条**人工**优化建议。
   *
   * <p>🔴 请求体**不接受** {@code tenantId} / {@code userId} / {@code membershipId}。
   */
  public record AutomationRuleEffectAdviceInput(
      UUID ruleId,
      /** 建议类型（与系统建议同一受控词表）。 */
      String adviceType,
      /** 严重度：CRITICAL / WARNING / INFO。 */
      String severity,
      String title,
      String detail) {}

  /** 重新生成系统建议的结果（**如实报数**：新增 / 更新 / 失效各多少条）。 */
  public record AutomationRuleEffectAdviceRefreshResult(
      int scannedRules,
      int createdAdvices,
      int updatedAdvices,
      int deactivatedAdvices,
      OffsetDateTime generatedAt) {}

  // ================================================================ 枚举

  /** 误触发分类。 */
  public enum AutomationRuleEffectFalseTriggerType {
    /** 条件写错了。 */
    WRONG_CONDITION("条件写错了"),
    /** 生效范围不对。 */
    WRONG_SCOPE("生效范围不对"),
    /** 触发太频繁。 */
    TOO_FREQUENT("触发太频繁"),
    /** 执行了不该执行的动作。 */
    UNEXPECTED_ACTION("执行了不该执行的动作"),
    /** 其它（必须写清说明）。 */
    OTHER("其它");

    private final String label;

    AutomationRuleEffectFalseTriggerType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}（服务层给一句能直接展示的话）。 */
    public static AutomationRuleEffectFalseTriggerType parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var v : values()) {
        if (v.name().equalsIgnoreCase(raw.trim())) return v;
      }
      return null;
    }
  }

  /** 误触发反馈的处理状态。 */
  public enum AutomationRuleEffectFeedbackStatus {
    /** 待处理。 */
    OPEN("待处理"),
    /** 已采纳。 */
    ACCEPTED("已采纳"),
    /** 已忽略。 */
    DISMISSED("已忽略");

    private final String label;

    AutomationRuleEffectFeedbackStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}。 */
    public static AutomationRuleEffectFeedbackStatus parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var v : values()) {
        if (v.name().equalsIgnoreCase(raw.trim())) return v;
      }
      return null;
    }
  }

  /** 节省工时口径的来源。 */
  public enum AutomationRuleEffectBaselineSource {
    /** 人工估算。 */
    MANUAL_ESTIMATE("人工估算"),
    /** 实测均值。 */
    OBSERVED_AVERAGE("实测均值"),
    /** 公司统一口径。 */
    POLICY("公司口径");

    private final String label;

    AutomationRuleEffectBaselineSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}。 */
    public static AutomationRuleEffectBaselineSource parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var v : values()) {
        if (v.name().equalsIgnoreCase(raw.trim())) return v;
      }
      return null;
    }
  }

  /** 优化建议的类型。 */
  public enum AutomationRuleEffectAdviceType {
    /** 🔴 误触发率过高 ⇒ 建议关闭或收紧条件。 */
    FALSE_TRIGGER_HIGH("误触发率过高"),
    /** 失败率过高。 */
    HIGH_FAILURE_RATE("失败率过高"),
    /** 超时偏多。 */
    TIMEOUT_HIGH("超时偏多"),
    /** 大量「未执行」。 */
    NOT_ATTEMPTED_HIGH("大量未执行"),
    /** 大量跳过。 */
    SKIPPED_HIGH("大量跳过"),
    /** 平均耗时过长。 */
    SLOW("平均耗时过长"),
    /** 缺人工基线口径。 */
    NO_BASELINE("缺少人工基线口径"),
    /** 样本太少。 */
    INSUFFICIENT_DATA("样本太少"),
    /** 表现良好。 */
    PERFORMING_WELL("表现良好");

    private final String label;

    AutomationRuleEffectAdviceType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}。 */
    public static AutomationRuleEffectAdviceType parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var v : values()) {
        if (v.name().equalsIgnoreCase(raw.trim())) return v;
      }
      return null;
    }
  }

  /** 建议的严重度。 */
  public enum AutomationRuleEffectSeverity {
    /** 最急。 */
    CRITICAL("紧急"),
    /** 值得看。 */
    WARNING("警告"),
    /** 提示。 */
    INFO("提示");

    private final String label;

    AutomationRuleEffectSeverity(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}。 */
    public static AutomationRuleEffectSeverity parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var v : values()) {
        if (v.name().equalsIgnoreCase(raw.trim())) return v;
      }
      return null;
    }
  }

  /** 建议的处理状态。 */
  public enum AutomationRuleEffectAdviceStatus {
    /** 待处理。 */
    OPEN("待处理"),
    /** 已采纳。 */
    ADOPTED("已采纳"),
    /** 已忽略。 */
    IGNORED("已忽略");

    private final String label;

    AutomationRuleEffectAdviceStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}。 */
    public static AutomationRuleEffectAdviceStatus parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var v : values()) {
        if (v.name().equalsIgnoreCase(raw.trim())) return v;
      }
      return null;
    }
  }

  /** 建议的来源。 */
  public enum AutomationRuleEffectAdviceSource {
    /** 系统按当前聚合自动生成。 */
    SYSTEM("系统生成"),
    /** 人工登记。 */
    MANUAL("人工登记");

    private final String label;

    AutomationRuleEffectAdviceSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}。 */
    public static AutomationRuleEffectAdviceSource parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var v : values()) {
        if (v.name().equalsIgnoreCase(raw.trim())) return v;
      }
      return null;
    }
  }
}
