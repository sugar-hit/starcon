package com.starcon.platform;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 18.4.2.3「异常诊断」的传输模型。
 *
 * <p>蓝图 18-integration-and-automation.md 原文：
 *
 * <blockquote>
 *
 * 职责：AI 异常诊断：对库存异常、利润异常、发货延迟、同步失败、广告亏损等给出可能原因分析（自动关联相关数据）与处置建议。
 * 输入：异常事件 + 关联数据（订单、库存、成本、历史记录）。 输出：诊断结论 + 依据 + 建议动作。
 * 关系：上游 = 1 工作台待办、各域异常；下游 = 对应处理页。
 * <b>诊断要给依据</b>（"因为 SKU X 的头程成本上涨 18%"），不能只给结论。
 *
 * </blockquote>
 *
 * <p><b>命名。</b>统一用 {@code AiDiagnosis*} 前缀。SpringDoc 用**简单类名**当 schema 名，
 * 所以每个嵌套记录都带前缀 —— 否则「Conclusion」「Evidence」这种通用名会与别的服务的
 * 同名 schema 撞车（契约生成器的撞名闸会直接中止）。
 *
 * <p><b>🔴 本模块一个 {@code double} 都没有</b>：数量与金额是 {@link BigDecimal}
 * （库里 {@code NUMERIC(19,6)}），比率 {@code NUMERIC(9,6)}；百分比走
 * {@code impactText}（如 {@code "+18.4%"}）的**文本**通道。
 *
 * <p><b>🔴 依据是一等公民</b>：{@link AiDiagnosisEvidenceRow} 带
 * {@code factTable + factField + factValue + snapshotAt + factRef} ——
 * 页面可以从结论**下钻到具体事实行**，而不是只读一段说明文字。
 */
public final class AiDiagnosisModels {

  private AiDiagnosisModels() {}

  // ================================================================ 能力声明

  /**
   * 一项能力（或一项「还没接通」的能力）。
   *
   * @param available 该能力**现在**能不能用（不是「将来会有」）
   * @param evidence 凭什么这么判（端点 / 表 / 规则口径的具体名字）
   * @param ownerModule 还没接通的能力，**缺在哪个模块**
   */
  public record AiDiagnosisCapability(
      String moduleId, String name, boolean available, String evidence, String ownerModule, String note) {}

  /** 一个「还没交付」的上游 / 下游能力。 */
  public record AiDiagnosisGap(
      String moduleId, String name, String service, boolean available, String relation, String note) {}

  /**
   * 一条**归因规则**（页面照抄后端原文，不自己编）。
   *
   * @param code 规则码（与结论上的 ruleCode 一一对应）
   * @param anomalyType 归哪一类异常
   * @param trigger **触发条件**：什么情况下这条规则会命中
   * @param basis **归因依据**：拿哪张表哪个字段的什么事实来判
   * @param recommendation **处置建议**：命中后该做什么（并说明归谁执行）
   * @param available 这条规则**现在**能不能跑（上游没接通时为 false）
   */
  public record AiDiagnosisRule(
      String code,
      String name,
      String anomalyType,
      String anomalyTypeLabel,
      String trigger,
      String basis,
      String recommendation,
      String actionOwner,
      boolean available,
      String note) {}

  /**
   * 一个**数据来源**（事实源）。
   *
   * @param connected 这一格**现在**读不读得到（读不到就是 false，**不假装有数据**）
   * @param ownerModule 读不到时，缺在哪个模块
   */
  public record AiDiagnosisSource(
      String code,
      String name,
      String service,
      boolean connected,
      String endpoint,
      String ownerModule,
      String note) {}

  /** 一个阈值（归因判定用）。 */
  public record AiDiagnosisThreshold(
      String code, String name, BigDecimal value, String unit, String description) {}

  /** 能力声明总览。 */
  public record AiDiagnosisCapabilities(
      List<AiDiagnosisCapability> capabilities,
      List<AiDiagnosisGap> gaps,
      List<AiDiagnosisRule> rules,
      List<AiDiagnosisThreshold> thresholds,
      List<AiDiagnosisSource> sources) {}

  // ================================================================ 概览

  /** 某一类异常的事件数。 */
  public record AiDiagnosisAnomalyCount(String anomalyType, String anomalyTypeLabel, long count) {}

  /** 概览：几个数。 */
  public record AiDiagnosisOverview(
      long events,
      long openEvents,
      long diagnosedEvents,
      long resolvedEvents,
      long runs,
      long conclusions,
      long evidences,
      List<AiDiagnosisAnomalyCount> byAnomalyType,
      OffsetDateTime generatedAt) {}

  // ================================================================ 异常事件

  /** 异常事件一行。 */
  public record AiDiagnosisEventRow(
      UUID id,
      String eventNo,
      String anomalyType,
      String anomalyTypeLabel,
      String severity,
      String subjectType,
      String subjectRef,
      String subjectLabel,
      OffsetDateTime occurredAt,
      OffsetDateTime detectedAt,
      String source,
      String sourceRef,
      @Schema(nullable = true) BigDecimal reportedValue,
      String reportedUnit,
      String status,
      String note,
      OffsetDateTime createdAt) {}

  /** 异常事件列表（真分页）。 */
  public record AiDiagnosisEventPage(
      List<AiDiagnosisEventRow> items, int page, int size, long total, long totalPages) {}

  /** 提交异常事件（**调用方提交**这一路来源）。 */
  public record AiDiagnosisEventInput(
      String anomalyType,
      String subjectType,
      String subjectRef,
      String subjectLabel,
      @Schema(nullable = true) OffsetDateTime occurredAt,
      @Schema(nullable = true) BigDecimal reportedValue,
      String reportedUnit,
      String severity,
      String note) {}

  /** 人工标记已处置时写的一句处置说明。 */
  public record AiDiagnosisResolveInput(String note) {}

  // ================================================================ 诊断运行

  /** 一次诊断运行一行。 */
  public record AiDiagnosisRunRow(
      UUID id,
      String runNo,
      UUID eventId,
      String status,
      String ruleVersion,
      int conclusionCount,
      int evidenceCount,
      String sourcesRead,
      String sourcesUnavailable,
      String note,
      @Schema(nullable = true) UUID requestedBy,
      OffsetDateTime startedAt,
      @Schema(nullable = true) OffsetDateTime finishedAt) {}

  // ================================================================ 结论与依据

  /**
   * 一条**归因结论**。
   *
   * <p>🔴 {@code recommendation}（处置建议）**必填**：蓝图输出句里「诊断结论 + 依据 +
   * 建议动作」是三件套，缺一即未完成。
   *
   * <p>🔴 {@code evidenceLevel} 说的是**依据有多硬**（STRONG / WEAK / INSUFFICIENT），
   * **不是**「我有多确信」—— 本仓没有可校准的置信度来源，不写「置信度 87%」。
   */
  public record AiDiagnosisConclusionRow(
      UUID id,
      UUID runId,
      UUID eventId,
      String ruleCode,
      String ruleLabel,
      String anomalyType,
      String severity,
      String subjectRef,
      String summary,
      String detail,
      String evidenceLevel,
      @Schema(nullable = true) BigDecimal impact,
      String impactUnit,
      String impactText,
      String recommendation,
      String actionRef,
      String actionOwner,
      OffsetDateTime createdAt) {}

  /**
   * 一条**依据**。
   *
   * <p>🔴 **可下钻**：{@code factTable} + {@code factField} + {@code factValue} +
   * {@code snapshotAt} + {@code factRef} —— 拿着 {@code factRef} 能回到那条事实行。
   */
  public record AiDiagnosisEvidenceRow(
      UUID id,
      UUID conclusionId,
      String factService,
      String factTable,
      String factField,
      @Schema(nullable = true) BigDecimal factValue,
      String factValueText,
      String factUnit,
      String factRef,
      @Schema(nullable = true) OffsetDateTime factOccurredAt,
      OffsetDateTime snapshotAt,
      String explanation,
      OffsetDateTime createdAt) {}

  /** 一条结论 + 它的全部依据。 */
  public record AiDiagnosisConclusionDetail(
      AiDiagnosisConclusionRow conclusion, List<AiDiagnosisEvidenceRow> evidences) {}

  /** 一次运行 + 它的全部结论与依据。 */
  public record AiDiagnosisRunDetail(AiDiagnosisRunRow run, List<AiDiagnosisConclusionDetail> conclusions) {}

  /**
   * 事件详情：事件 + 它的历史运行。
   *
   * <p>🔴 <b>这里刻意不内嵌「最近一次运行」</b>：还没跑过诊断的事件没有最近一次运行，
   * 而契约（OpenAPI）对「可为 null 的嵌套对象」表达不了（springdoc 会把 {@code $ref}
   * 上的 {@code nullable} 丢掉）⇒ 一旦内嵌，契约就会把「还没诊断过」谎报成「有运行」。
   * 所以拆成两个端点：事件详情给事件与运行列表，<b>运行详情</b>（{@code GET /runs/{id}}）
   * 给结论与依据。前端用 {@code runs} 是否为空判断「诊断过没有」，不需要猜。
   */
  public record AiDiagnosisEventDetail(AiDiagnosisEventRow event, List<AiDiagnosisRunRow> runs) {}

  // ================================================================ 内部失败源同步

  /**
   * 一次「平台内部失败源同步」的结果。
   *
   * @param scanned 扫了多少条失败源事实
   * @param created 新登记了多少个异常事件
   * @param skipped 因为已登记过而跳过的事件数（**幂等，不刷屏**）
   */
  public record AiDiagnosisSyncResult(
      int scanned, int created, int skipped, List<String> sources, String note) {}
}
