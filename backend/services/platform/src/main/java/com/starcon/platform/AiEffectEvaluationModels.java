package com.starcon.platform;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 18.4.3.2「效果评估」的模型（卷 18 → 18.4 AI 治理 → 18.4.3 AI 使用与效果管理）。
 *
 * <p>蓝图原文：「<b>职责</b>：评估 AI 功能效果：翻译采纳率、预测准确度回测、助手答案有用性反馈，
 * 识别低价值功能并下线。<b>输入</b>：人工采纳/修改记录；预测回测结果；用户反馈。
 * <b>输出</b>：效果报表 + 改进建议。<b>关系</b>：上游 = 18.4 全部；下游 = 18.4 迭代。
 * <b>采纳率是 AI 功能唯一诚实的指标</b>——生成了但没人用的功能应下线。」
 *
 * <p><b>七条纪律：</b>
 *
 * <ol>
 *   <li><b>请求体里没有 tenantId / userId / membershipId</b>：归属一律由服务端从
 *       <b>已认证会话</b>解析（{@link AccessService#require}）——
 *       客户端自称的归属连字段都不存在（Jackson 全局 fail-on-unknown-properties ⇒ 400）。
 *   <li><b>比率与数量一律十进制文本</b>：{@code String} 承载 {@code NUMERIC} 的值，
 *       前端全程不碰浮点（AGENTS.md：禁浮点记账）。只有<b>次数</b>是整数，用 {@code long}。
 *   <li><b>分母为 0 ⇒ 比率是 {@code null}</b>：不是 0，也不是 Infinity ——
 *       「没有调用记录」与「调用了但没人用」是两件完全不同的事。
 *   <li><b>三态不是布尔</b>：采纳 = 直接采用 / 修改后采用 / 拒绝；有用性 = 有用 / 部分有用 / 无用。
 *   <li><b>口径必须随数一起给</b>：分子、分母、比率**三件齐全**，外加一句
 *       「这个分母来自哪张表、这个时间窗是哪个、分子是哪张表」——
 *       没有口径的数字没法被信任。
 *   <li><b>产物只记引用</b>：产物 id + 版本快照 + 人可读定位码，**不复制内容当事实**。
 *   <li><b>只出建议，不下线</b>：真正下线涉及 18.4.3.1 的开关 / 配额设置，
 *       本模块**只读**它的数据并输出建议清单。
 * </ol>
 */
public final class AiEffectEvaluationModels {

  private AiEffectEvaluationModels() {}

  // ================================================================ 口径枚举

  /**
   * 人工处置的三态。
   *
   * <p>🔴 蓝图原文「翻译采纳 = 用户直接采用（未修改）+ 人工修改后采用」⇒
   * {@link #ACCEPTED} 与 {@link #MODIFIED} **都算被采纳**（分子），
   * {@link #REJECTED} 不算。只存一个布尔值会把「改了才用」误判成「没用」。
   */
  public enum AdoptionDecision {
    ACCEPTED("直接采用"),
    MODIFIED("修改后采用"),
    REJECTED("拒绝");

    private final String label;

    AdoptionDecision(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 是否计入采纳率分子。 */
    public boolean adopted() {
      return this != REJECTED;
    }

    /** 解析处置；非法取值返回 {@code null}。 */
    public static AdoptionDecision parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var d : values()) {
        if (d.name().equalsIgnoreCase(raw.trim())) return d;
      }
      return null;
    }
  }

  /** 助手答案的有用性三档。 */
  public enum Usefulness {
    USEFUL("有用"),
    PARTIALLY_USEFUL("部分有用"),
    NOT_USEFUL("无用");

    private final String label;

    Usefulness(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 是否算正向反馈（有用 / 部分有用）。 */
    public boolean positive() {
      return this != NOT_USEFUL;
    }

    /** 解析档位；非法取值返回 {@code null}。 */
    public static Usefulness parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var u : values()) {
        if (u.name().equalsIgnoreCase(raw.trim())) return u;
      }
      return null;
    }
  }

  /** 被评估的 AI 产物类型。 */
  public enum ArtifactType {
    TRANSLATION("译文（18.4.2.1）"),
    FORECAST("预测结果（18.4.2.2）"),
    ASSISTANT("助手答案（18.4.2.3 未交付）"),
    OTHER("其它 AI 产物");

    private final String label;

    ArtifactType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析产物类型；非法取值返回 {@code null}。 */
    public static ArtifactType parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var a : values()) {
        if (a.name().equalsIgnoreCase(raw.trim())) return a;
      }
      return null;
    }
  }

  /**
   * 反馈理由的**受控词表**。
   *
   * <p>🔴 自由文本（{@code reasonText}）不做统计口径 —— 能聚合、能比较的只有这组码。
   */
  public enum FeedbackReason {
    INACCURATE("内容不准"),
    TOO_VERBOSE("太啰嗦"),
    MISSING_CONTEXT("缺少上下文"),
    STYLE_MISMATCH("风格不符"),
    WRONG_LANGUAGE("语言不对"),
    TERM_ISSUE("术语问题"),
    DATA_STALE("数据过时"),
    OTHER("其它");

    private final String label;

    FeedbackReason(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析理由码；非法取值返回 {@code null}（空串表示未选理由）。 */
    public static FeedbackReason parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var r : values()) {
        if (r.name().equalsIgnoreCase(raw.trim())) return r;
      }
      return null;
    }
  }

  /** 回测判定。 */
  public enum BacktestStatus {
    COMPARED("已回测"),
    NO_ACTUAL_SOURCE("无法回测，缺实际值来源"),
    NOT_COMPARABLE("不可比");

    private final String label;

    BacktestStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析判定；非法取值返回 {@code null}。 */
    public static BacktestStatus parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw.trim())) return s;
      }
      return null;
    }
  }

  /** 改进建议的类型。 */
  public enum SuggestionType {
    OFFLINE_CANDIDATE("低价值功能，建议下线"),
    LOW_USEFULNESS("有用性偏低"),
    FORECAST_INACCURACY("预测准确度偏低"),
    DATA_GAP("数据不足");

    private final String label;

    SuggestionType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析建议类型；非法取值返回 {@code null}。 */
    public static SuggestionType parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var t : values()) {
        if (t.name().equalsIgnoreCase(raw.trim())) return t;
      }
      return null;
    }
  }

  /** 建议的严重度。 */
  public enum SuggestionSeverity {
    HIGH("高"),
    MEDIUM("中"),
    LOW("低");

    private final String label;

    SuggestionSeverity(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析严重度；非法取值返回 {@code null}。 */
    public static SuggestionSeverity parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw.trim())) return s;
      }
      return null;
    }
  }

  /** 建议台账的状态机。 */
  public enum SuggestionStatus {
    OPEN("待决策"),
    ACCEPTED("已采纳"),
    REJECTED("已驳回"),
    RESOLVED("条件已不成立，已自动关闭");

    private final String label;

    SuggestionStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析状态；非法取值返回 {@code null}。 */
    public static SuggestionStatus parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw.trim())) return s;
      }
      return null;
    }
  }

  /** 功能的低价值判定。 */
  public enum FeatureVerdict {
    OK("正常"),
    LOW_VALUE("低价值"),
    INSUFFICIENT_DATA("数据不足");

    private final String label;

    FeatureVerdict(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析判定；非法取值返回 {@code null}。 */
    public static FeatureVerdict parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var v : values()) {
        if (v.name().equalsIgnoreCase(raw.trim())) return v;
      }
      return null;
    }
  }

  // ================================================================ 能力声明

  /** 能力条目：每一维说清「有没有、依据是什么、缺了归谁」。 */
  public record AiEffectCapability(
      String module,
      String name,
      boolean available,
      String basis,
      String ownerModule,
      String impact) {}

  /** 下游缺口：谁没交付、归谁。 */
  public record AiEffectGap(
      String module,
      String name,
      String ownerService,
      boolean available,
      String group,
      String detail) {}

  /** 18.4.3.2 的能力声明。 */
  public record AiEffectCapabilities(
      List<AiEffectCapability> capabilities, List<AiEffectGap> downstreamGaps) {}

  // ================================================================ 阈值

  /**
   * 评估阈值（**租户级一行**）。
   *
   * @param configured 库里**是否**登记过这一行（false = 没有登记，正在用系统默认阈值）
   */
  public record AiEffectThresholdRow(
      UUID id,
      long minCalls,
      String lowAdoptionRate,
      long minFeedbackCount,
      String lowUsefulRate,
      String maxMape,
      boolean configured,
      String basis,
      OffsetDateTime updatedAt) {}

  /** 登记 / 修改评估阈值的入参（**没有 tenantId / userId**）。 */
  public record AiEffectThresholdInput(
      @Schema(nullable = true) Long minCalls,
      @Schema(nullable = true) String lowAdoptionRate,
      @Schema(nullable = true) Long minFeedbackCount,
      @Schema(nullable = true) String lowUsefulRate,
      @Schema(nullable = true) String maxMape) {}

  // ================================================================ 采纳记录

  /** 采纳记录的一行。 */
  public record AiAdoptionRow(
      UUID id,
      String capabilityCode,
      String featureCode,
      String decision,
      String decisionLabel,
      String artifactType,
      String artifactTypeLabel,
      @Schema(nullable = true) UUID artifactId,
      @Schema(nullable = true) Integer artifactVersion,
      String artifactRef,
      @Schema(nullable = true) UUID usageRecordId,
      UUID membershipId,
      String editNote,
      String rejectReason,
      String status,
      @Schema(nullable = true) String withdrawnReason,
      OffsetDateTime occurredAt) {}

  /**
   * 录入采纳 / 修改 / 拒绝的入参。
   *
   * <p>🔴 <b>没有 tenantId / userId / membershipId</b>：反馈人由服务端从会话解析。
   *
   * @param usageRecordId 可空：挂上之后，采纳率的分子与分母计的是**同一种单位**（调用次数）
   */
  public record AiAdoptionInput(
      String capabilityCode,
      String featureCode,
      String decision,
      String artifactType,
      @Schema(nullable = true) UUID artifactId,
      @Schema(nullable = true) Integer artifactVersion,
      String artifactRef,
      @Schema(nullable = true) UUID usageRecordId,
      String editNote,
      String rejectReason) {}

  /** 撤回一条采纳记录的入参。 */
  public record AiWithdrawInput(String reason) {}

  /** 采纳记录台账（真分页 + 真筛选）。 */
  public record AiAdoptionPage(List<AiAdoptionRow> items, int page, int size, long total) {}

  // ================================================================ 有用性反馈

  /** 有用性反馈的一行。 */
  public record AiFeedbackRow(
      UUID id,
      String capabilityCode,
      String featureCode,
      String usefulness,
      String usefulnessLabel,
      String reasonCode,
      String reasonCodeLabel,
      String reasonText,
      String artifactType,
      String artifactTypeLabel,
      @Schema(nullable = true) UUID artifactId,
      @Schema(nullable = true) Integer artifactVersion,
      String artifactRef,
      UUID membershipId,
      String status,
      @Schema(nullable = true) String withdrawnReason,
      OffsetDateTime occurredAt) {}

  /** 有用性反馈台账（真分页 + 真筛选）。 */
  public record AiFeedbackPage(List<AiFeedbackRow> items, int page, int size, long total) {}

  /**
   * 录入有用性反馈的入参。
   *
   * <p>🔴 <b>没有 tenantId / userId / membershipId</b>：反馈人由服务端从会话解析。
   */
  public record AiFeedbackInput(
      String capabilityCode,
      String featureCode,
      String usefulness,
      String reasonCode,
      String reasonText,
      String artifactType,
      @Schema(nullable = true) UUID artifactId,
      @Schema(nullable = true) Integer artifactVersion,
      String artifactRef) {}

  // ================================================================ 回测

  /** 回测结果的一行。 */
  public record AiBacktestRow(
      UUID id,
      UUID forecastResultId,
      UUID runId,
      String runNo,
      String skuCode,
      String skuName,
      String forecastPoint,
      String forecastLower,
      String forecastUpper,
      @Schema(nullable = true) String actualUnits,
      String actualSource,
      @Schema(nullable = true) String actualWindowStart,
      @Schema(nullable = true) String actualWindowEnd,
      long actualRecordDays,
      @Schema(nullable = true) String absoluteError,
      @Schema(nullable = true) String relativeError,
      @Schema(nullable = true) Boolean withinInterval,
      String status,
      String statusLabel,
      String note,
      OffsetDateTime evaluatedAt) {}

  /** 跑一次回测的入参（{@code runId} 可空 = 取最近一次预测运行）。 */
  public record AiBacktestRunInput(@Schema(nullable = true) UUID runId) {}

  /** 回测结果台账（真分页 + 真筛选）。 */
  public record AiBacktestPage(List<AiBacktestRow> items, int page, int size, long total) {}

  /**
   * 跑一次回测的结果。
   *
   * @param mape 平均绝对百分比误差（**实际值为 0 的行不参与**，分母为 0 时为 {@code null}）
   * @param hitRate 命中区间比例（实际值落在预测置信区间内的比例）
   */
  public record AiBacktestRunResult(
      UUID runId,
      String runNo,
      long evaluatedCount,
      long comparedCount,
      long noActualSourceCount,
      @Schema(nullable = true) String mape,
      @Schema(nullable = true) String hitRate,
      String basis,
      List<AiBacktestRow> items) {}

  // ================================================================ 效果报表

  /**
   * 按功能的采纳率报表的一行。
   *
   * <p>🔴 <b>分子 / 分母 / 比率 / 口径四件齐全</b>，缺一不可。
   *
   * @param calls 分母：该功能在时间窗内的 **AI 调用次数**（来源 18.4.3.1 的用量台账）
   * @param adoptedCount 分子：被采纳的产物次数（直接采用 + 修改后采用，剔除已撤回）
   * @param adoptionRate 比率；**分母为 0 时为 {@code null}**（不是 0、不是 Infinity）
   * @param linkedAdoptedCount 分子中**已关联到具体调用记录**的那部分
   * @param unlinkedAdoptedCount 分子中**未关联调用记录**的那部分（与上一项**分开列**，不混算）
   */
  public record AiAdoptionReportRow(
      String capabilityCode,
      String featureCode,
      long calls,
      long adoptedCount,
      long acceptedCount,
      long modifiedCount,
      long rejectedCount,
      long linkedAdoptedCount,
      long unlinkedAdoptedCount,
      @Schema(nullable = true) String adoptionRate,
      String denominatorSource,
      String numeratorSource,
      String verdict,
      String verdictLabel,
      String reason,
      String windowStart,
      String windowEnd) {}

  /** 采纳率报表（真分页 + 真筛选）。 */
  public record AiAdoptionReportPage(
      List<AiAdoptionReportRow> items, int page, int size, long total, String windowStart, String windowEnd) {}

  /** 采纳率汇总（概览用）。 */
  public record AiAdoptionSummary(
      long calls,
      long adoptedCount,
      long rejectedCount,
      @Schema(nullable = true) String adoptionRate,
      long featuresEvaluated,
      long lowValueFeatures,
      String denominatorSource,
      String numeratorSource,
      String basis) {}

  /** 有用性反馈的汇总（概览用）。 */
  public record AiUsefulnessSummary(
      long total,
      long usefulCount,
      long partiallyUsefulCount,
      long notUsefulCount,
      @Schema(nullable = true) String usefulRate,
      @Schema(nullable = true) String partiallyUsefulRate,
      @Schema(nullable = true) String notUsefulRate,
      @Schema(nullable = true) String positiveRate,
      String basis) {}

  /** 预测回测的汇总（概览用）。 */
  public record AiForecastBacktestSummary(
      long evaluatedCount,
      long comparedCount,
      long noActualSourceCount,
      @Schema(nullable = true) String mape,
      @Schema(nullable = true) String hitRate,
      String basis) {}

  /** 低价值功能清单的一行。 */
  public record AiLowValueFeature(
      String capabilityCode,
      String featureCode,
      long calls,
      @Schema(nullable = true) String adoptionRate,
      String verdict,
      String verdictLabel,
      String reason) {}

  /**
   * 效果概览：三块（采纳率 / 预测回测误差 / 有用性反馈），每块都带**分子分母与口径**。
   *
   * @param thresholds 当前生效的评估阈值
   */
  public record AiEffectOverview(
      String windowStart,
      String windowEnd,
      AiAdoptionSummary adoption,
      AiForecastBacktestSummary forecast,
      AiUsefulnessSummary usefulness,
      AiEffectThresholdRow thresholds,
      List<AiLowValueFeature> lowValueFeatures,
      String basis) {}

  // ================================================================ 改进建议

  /** 改进建议的一行。 */
  public record AiSuggestionRow(
      UUID id,
      String capabilityCode,
      String featureCode,
      String suggestionType,
      String suggestionTypeLabel,
      String severity,
      String severityLabel,
      String title,
      String currentMetric,
      String recommendation,
      String evidence,
      String actionOwner,
      String offlineAction,
      String windowStart,
      String windowEnd,
      String status,
      String statusLabel,
      @Schema(nullable = true) UUID decidedBy,
      @Schema(nullable = true) OffsetDateTime decidedAt,
      String decisionNote,
      OffsetDateTime generatedAt,
      OffsetDateTime updatedAt) {}

  /** 改进建议列表（真分页 + 真筛选）。 */
  public record AiSuggestionPage(
      List<AiSuggestionRow> items, int page, int size, long total, long lowValueCount) {}

  /** 记录对一条建议的人工决策（**只记录决定，不执行下线**）。 */
  public record AiSuggestionDecisionInput(String note) {}

  /** 生成建议的入参（时间窗可空 = 用默认窗口）。 */
  public record AiSuggestionGenerateInput(
      @Schema(nullable = true) String from, @Schema(nullable = true) String to) {}

  /**
   * 生成建议的结果。
   *
   * @param refreshed 本次**刷新**的建议条数
   * @param created 本次**新建**的建议条数
   * @param resolved 因条件不再成立而被自动关闭的条数
   */
  public record AiSuggestionGenerateResult(
      long created, long refreshed, long resolved, String windowStart, String windowEnd, String basis) {}
}
