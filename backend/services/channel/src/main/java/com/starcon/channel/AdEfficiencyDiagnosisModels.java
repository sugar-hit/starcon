package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 14.3.2.2 效率诊断 的传输模型。
 *
 * <p>蓝图：「诊断投放效率问题：高花费零转化、ACOS 长期超标、预算受限、竞价过低曝光不足、创意衰退，
 * 给出可执行建议与一键调整。输入：14.3 报表指标；诊断规则。输出：诊断清单 + 建议动作。
 * 关系：上游 = 14.3.1；下游 = 14.2.1.1、14.1.3.1。<b>诊断要给动作而不是只报警</b>，否则用户看完还是要自己想怎么办。」
 *
 * <p>🔴 这个模型层把本模块最容易做丢的三件事钉死：
 *
 * <ol>
 *   <li><b>结论必须带观察量快照</b>。{@code spend} / {@code sales} / {@code acos} / {@code overTargetDays}
 *       / {@code budgetUtilization} / {@code deliveryRatio} 全部随结论返回 ——
 *       任何人都能拿它复算「为什么这条被判成高花费零转化」。没有快照的结论等于一句断言。</li>
 *   <li><b>动作必须带可用性</b>。{@code actionMode} ∈ WRITE / NAVIGATE / UNAVAILABLE：
 *       {@code UNAVAILABLE} 必须同时给出 {@code ownerModule} 与 {@code impact} ——
 *       <b>竞价调整待 14.2.1.1 交付后开放</b>这句话是数据，不是文案。</li>
 *   <li><b>执行结论六种分开</b>：{@code NOT_APPLIED} / {@code DRY_RUN} / {@code APPLIED} /
 *       {@code REJECTED} / {@code SKIPPED} / {@code UNAVAILABLE}。
 *       事后能说清「上周那条到底执行了没有」—— 被护栏拒绝的照样留痕，
 *       <b>绝不静默截断</b>（截断会让人以为按预期工作了）。</li>
 * </ol>
 *
 * <p>🔴 金额与数量一律 {@link BigDecimal}，**没有 double**：花费 / 销售额 / 预算 / 阈值都是钱。
 *
 * <p>🔴 比率的 {@code null} 与 {@code 0} 含义相反（照抄 14.1.1.1 的 {@code AdRatio} 约定）：
 * {@code null} = <b>不可比</b>（分母为 0，比如销售额为 0 时 ACOS 算不出来），
 * {@code 0} = 可比但真的是零。用 0 冒充「不可比」会让「烧了钱没转化」看起来像「效率极高」。
 */
public final class AdEfficiencyDiagnosisModels {
  private AdEfficiencyDiagnosisModels() {}

  // ================================================================ 能力声明

  public record AdDiagnosisCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  public record AdDiagnosisCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false") List<AdDiagnosisCapabilityDimension> dimensions,
      @Schema(description = "诊断规则类型取值") List<String> ruleTypes,
      @Schema(description = "严重度取值") List<String> severities,
      @Schema(description = "建议动作类型取值") List<String> actionTypes,
      @Schema(description = "动作模式：WRITE 本模块可写 / NAVIGATE 去已交付模块 / UNAVAILABLE 依赖未交付模块")
          List<String> actionModes,
      @Schema(description = "执行结论：NOT_APPLIED / DRY_RUN / APPLIED / REJECTED / SKIPPED / UNAVAILABLE")
          List<String> outcomes,
      @Schema(description = "诊断结论状态：OPEN / ACTIONED / DISMISSED / STALE") List<String> diagnosisStatuses,
      @Schema(description = "空态原因取值") List<String> emptyReasons,
      @Schema(description = "🔴 指标口径说明（ACOS / ROAS / 预算利用率 / 曝光相对倍数）")
          String metricDefinitionNote,
      @Schema(description = "🔴 五种问题的判定口径说明") String ruleDefinitionNote,
      @Schema(description = "🔴 「诊断要给动作」的执行边界说明") String actionNote,
      @Schema(description = "🔴 上限 / 试运行 / 幂等 / 审计的说明") String guardrailNote,
      @Schema(description = "上游与数据截至时间的说明") String upstreamNote,
      @Schema(description = "下游与边界说明") String boundaryNote) {}

  // ================================================================ 诊断规则

  public record AdDiagnosisRuleRow(
      @Schema(description = "规则 id") UUID id,
      @Schema(description = "规则编码（租户内唯一）") String ruleCode,
      @Schema(description = "规则名称") String ruleName,
      @Schema(description = "🔴 判定口径：五种问题之一") String ruleType,
      @Schema(description = "严重度") String severity,
      @Schema(description = "是否启用") boolean enabled,
      @Schema(description = "观察窗口（天）") int lookbackDays,
      @Schema(description = "🔴 最少需要几天的数据才下结论（数据不足 ⇒ 不下结论）") int minMetricDays,
      @Schema(description = "🔴 金额阈值（高花费的门槛）", type = "string") BigDecimal thresholdAmount,
      @Schema(description = "🔴 比率阈值（ACOS 目标线）", type = "string") BigDecimal thresholdRatio,
      @Schema(description = "🔴 「超标天数」下限（ACOS 长期超标的判据）") int minOverTargetDays,
      @Schema(description = "🔴 预算利用率下限（预算受限的判据）", type = "string")
          BigDecimal budgetUtilizationRatio,
      @Schema(description = "🔴 曝光相对下限（曝光不足的判据）", type = "string") BigDecimal deliveryRatio,
      @Schema(description = "🔴 建议动作（主）") String actionType,
      @Schema(description = "建议幅度（比例）", type = "string") BigDecimal actionPercent,
      @Schema(description = "建议幅度（绝对额）", type = "string") BigDecimal actionAmount,
      @Schema(description = "🔴 次要建议动作（可空）") String secondaryActionType,
      @Schema(description = "次要动作幅度（比例）", type = "string") BigDecimal secondaryActionPercent,
      @Schema(description = "次要动作幅度（绝对额）", type = "string") BigDecimal secondaryActionAmount,
      @Schema(description = "🔴 数据源是否就绪：false ⇒ 该规则不产出任何结论") boolean dataSourceReady,
      @Schema(description = "🔴 试运行开关（默认 true）：与本次请求的 dry_run 取更严的那个") boolean dryRun,
      @Schema(description = "备注") String note,
      @Schema(description = "乐观锁版本") long version,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  public record AdDiagnosisRulePage(
      @Schema(description = "当前页数据") List<AdDiagnosisRuleRow> items,
      @Schema(description = "符合筛选条件的总条数（不受分页影响）") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "其中启用的条数") long enabledCount,
      @Schema(description = "其中数据源未就绪的条数") long notReadyCount) {}

  /** 初始化默认诊断规则的结果（幂等：已存在的不覆盖）。 */
  public record AdDiagnosisRuleDefaultsResult(
      @Schema(description = "本次新建的规则条数") int created,
      @Schema(description = "本租户已存在、未被覆盖的规则条数") int existing,
      @Schema(description = "本租户当前的规则清单") List<AdDiagnosisRuleRow> rules,
      @Schema(description = "说明") String note) {}

  // ================================================================ 诊断运行

  public record AdDiagnosisRunRow(
      @Schema(description = "运行 id") UUID id,
      @Schema(description = "观察窗口（天）") int lookbackDays,
      @Schema(description = "统计区间开始") LocalDate windowFrom,
      @Schema(description = "统计区间结束") LocalDate windowTo,
      @Schema(description = "评估的规则数") int ruleCount,
      @Schema(description = "其中启用的条数") int enabledRuleCount,
      @Schema(description = "其中被跳过的条数（数据源未就绪）") int skippedRuleCount,
      @Schema(description = "扫描到的活动数") int scannedCampaignCount,
      @Schema(description = "产出的诊断结论数") int diagnosisCount,
      @Schema(description = "🔴 数据截至时刻（本次诊断用的数据截至什么时候）") OffsetDateTime dataAsOf,
      @Schema(description = "状态：SUCCESS / FAILED") String status,
      @Schema(description = "说明（FAILED 时必有内容）") String message,
      @Schema(description = "开始时刻") OffsetDateTime startedAt,
      @Schema(description = "结束时刻") OffsetDateTime finishedAt,
      @Schema(description = "创建时刻") OffsetDateTime createdAt) {}

  public record AdDiagnosisRunPage(
      @Schema(description = "当前页数据") List<AdDiagnosisRunRow> items,
      @Schema(description = "符合筛选条件的总条数（不受分页影响）") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  /**
   * 运行里**每一条规则**的评估结论。
   *
   * <p>🔴 「没产出结论也要留痕」：{@code evaluated=false} + {@code skipReason}
   * 与「评估过但没命中」是**两件不同的事** —— 前者是「这条规则这次没跑」
   * （创意衰退：创意数据源未就绪），后者是「跑了，但这条活动不符合判据」。
   */
  public record AdDiagnosisRunRuleRow(
      @Schema(description = "评估记录 id") UUID id,
      @Schema(description = "运行 id") UUID runId,
      @Schema(description = "规则 id") UUID ruleId,
      @Schema(description = "规则编码") String ruleCode,
      @Schema(description = "规则类型") String ruleType,
      @Schema(description = "严重度") String severity,
      @Schema(description = "🔴 是否真的评估了") boolean evaluated,
      @Schema(description = "🔴 跳过原因（evaluated=false 时必填）") String skipReason,
      @Schema(description = "评估过但没命中的原因说明") String noMatchReason,
      @Schema(description = "命中的活动数") int matchedCampaignCount,
      @Schema(description = "创建时刻") OffsetDateTime createdAt) {}

  public record AdDiagnosisRunDetail(
      @Schema(description = "运行") AdDiagnosisRunRow run,
      @Schema(description = "🔴 逐条规则的评估结论（跳过的也在）") List<AdDiagnosisRunRuleRow> ruleResults,
      @Schema(description = "本次产出的诊断结论") List<AdDiagnosisRow> diagnoses,
      @Schema(description = "本次产出的建议动作") List<AdDiagnosisActionRow> actions,
      @Schema(description = "说明") String note) {}

  // ================================================================ 诊断结论

  public record AdDiagnosisRow(
      @Schema(description = "诊断结论 id") UUID id,
      @Schema(description = "运行 id") UUID runId,
      @Schema(description = "规则 id") UUID ruleId,
      @Schema(description = "规则编码") String ruleCode,
      @Schema(description = "🔴 规则类型（五种问题之一）") String ruleType,
      @Schema(description = "严重度") String severity,
      @Schema(description = "活动 id") UUID campaignId,
      @Schema(description = "活动名") String campaignName,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "状态：OPEN / ACTIONED / DISMISSED / STALE") String status,
      @Schema(description = "统计区间开始") LocalDate windowFrom,
      @Schema(description = "统计区间结束") LocalDate windowTo,
      @Schema(description = "🔴 实际有数据的天数") int metricDays,
      @Schema(description = "曝光合计", type = "string") BigDecimal impressions,
      @Schema(description = "点击合计", type = "string") BigDecimal clicks,
      @Schema(description = "订单合计", type = "string") BigDecimal orders,
      @Schema(description = "消耗合计", type = "string") BigDecimal spend,
      @Schema(description = "销售额合计", type = "string") BigDecimal sales,
      @Schema(description = "🔴 ACOS = 消耗 / 销售额；null = 不可比（分母为 0），与 0 含义相反", type = "string")
          BigDecimal acos,
      @Schema(description = "🔴 ROAS = 销售额 / 消耗；null = 不可比", type = "string") BigDecimal roas,
      @Schema(description = "🔴 CTR = 点击 / 曝光；null = 不可比", type = "string") BigDecimal ctr,
      @Schema(description = "当时的日预算", type = "string") BigDecimal dailyBudget,
      @Schema(description = "🔴 预算利用率 = 日均花费 / 日预算；null = 没有预算（不可比）", type = "string")
          BigDecimal budgetUtilization,
      @Schema(description = "🔴 曝光相对倍数 = 日均曝光 / 同平台在投活动中位数；null = 没有可比口径", type = "string")
          BigDecimal deliveryRatio,
      @Schema(description = "🔴 窗口内超标天数") int overTargetDays,
      @Schema(description = "🔴 结论标题") String title,
      @Schema(description = "🔴 判定依据（写清为什么判定为这个问题）") String evidence,
      @Schema(description = "🔴 可执行建议（诊断要给动作而不是只报警）") String recommendation,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  public record AdDiagnosisPage(
      @Schema(description = "当前页数据") List<AdDiagnosisRow> items,
      @Schema(description = "符合筛选条件的总条数（不受分页影响）") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "其中待处理的条数") long openCount,
      @Schema(description = "其中 CRITICAL 的条数") long criticalCount,
      @Schema(description = "🔴 空态原因：NO_RULES / NO_DATA / NO_FINDINGS / FILTERED_EMPTY / null")
          String emptyReason,
      @Schema(description = "🔴 空态说明文案（几种空的解释各不相同）") String emptyNote,
      @Schema(description = "🔴 指标口径说明") String metricDefinitionNote) {}

  public record AdDiagnosisDetail(
      @Schema(description = "诊断结论") AdDiagnosisRow diagnosis,
      @Schema(description = "🔴 建议动作（可执行的那几个 + 明确不可用的那几个）")
          List<AdDiagnosisActionRow> actions,
      @Schema(description = "说明") String note) {}

  // ================================================================ 建议动作

  public record AdDiagnosisActionRow(
      @Schema(description = "建议动作 id") UUID id,
      @Schema(description = "诊断结论 id") UUID diagnosisId,
      @Schema(description = "规则 id") UUID ruleId,
      @Schema(description = "活动 id") UUID campaignId,
      @Schema(description = "活动名") String campaignName,
      @Schema(description = "动作类型") String actionType,
      @Schema(description = "🔴 动作模式：WRITE / NAVIGATE / UNAVAILABLE") String actionMode,
      @Schema(description = "🔴 本动作现在能不能做") boolean available,
      @Schema(description = "🔴 未交付段的责任模块") String ownerModule,
      @Schema(description = "🔴 不交付的影响") String impact,
      @Schema(description = "🔴 NAVIGATE 段：去哪个模块处理") String targetModule,
      @Schema(description = "🔴 NAVIGATE 段：去哪个页面") String targetPath,
      @Schema(description = "建议幅度（比例）", type = "string") BigDecimal actionPercent,
      @Schema(description = "建议幅度（绝对额）", type = "string") BigDecimal actionAmount,
      @Schema(description = "调整前的日预算", type = "string") BigDecimal beforeValue,
      @Schema(description = "🔴 「本来会变成多少」（试运行里也是这个值）", type = "string") BigDecimal afterValue,
      @Schema(description = "🔴 执行结论：NOT_APPLIED / DRY_RUN / APPLIED / REJECTED / SKIPPED / UNAVAILABLE")
          String outcome,
      @Schema(description = "结果说明（被拒时给出原因）") String message,
      @Schema(description = "🔴 护栏结论（被哪一道闸拦下）") String guardrailNote,
      @Schema(description = "🔴 本次执行口径：true = 试运行（只算不调）") boolean dryRun,
      @Schema(description = "发起人（已认证成员）") UUID requestedBy,
      @Schema(description = "执行时刻") OffsetDateTime appliedAt,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  public record AdDiagnosisActionPage(
      @Schema(description = "当前页数据") List<AdDiagnosisActionRow> items,
      @Schema(description = "符合筛选条件的总条数（不受分页影响）") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "其中现在可执行的条数") long availableCount,
      @Schema(description = "其中已生效的条数") long appliedCount,
      @Schema(description = "其中被护栏拒绝的条数") long rejectedCount,
      @Schema(description = "其中试运行的条数") long dryRunCount,
      @Schema(description = "其中依赖未交付模块的条数") long unavailableCount) {}

  // ================================================================ 请求模型

  /**
   * 登记 / 改一条诊断规则。
   *
   * <p>🔴 阈值**没有「不填」这一档**：每种 {@code ruleType} 用到的阈值是必填的
   * （DB 层还有延迟约束触发器兜底），「一条没有阈值的规则」永远判不出东西。
   */
  public record SaveRuleRequest(
      @Schema(description = "规则 id（新建时留空）") UUID id,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "规则编码（租户内唯一）")
          @NotBlank
          @Size(max = 64)
          String ruleCode,
      @Schema(description = "规则名称") @Size(max = 200) String ruleName,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "🔴 判定口径（五种问题之一）")
          @NotBlank
          String ruleType,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "严重度") @NotBlank String severity,
      @Schema(description = "是否启用（默认 true）") Boolean enabled,
      @Schema(description = "观察窗口（天，1..365，默认 14）")
          @Min(1)
          @Max(365)
          Integer lookbackDays,
      @Schema(description = "最少需要几天的数据才下结论（默认 3）")
          @Min(1)
          @Max(365)
          Integer minMetricDays,
      @Schema(description = "🔴 金额阈值（高花费的门槛）") BigDecimal thresholdAmount,
      @Schema(description = "🔴 比率阈值（ACOS 目标线）") BigDecimal thresholdRatio,
      @Schema(description = "🔴 「超标天数」下限（ACOS 长期超标）") @Min(1) @Max(365) Integer minOverTargetDays,
      @Schema(description = "🔴 预算利用率下限（预算受限）") BigDecimal budgetUtilizationRatio,
      @Schema(description = "🔴 曝光相对下限（曝光不足）") BigDecimal deliveryRatio,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "🔴 建议动作（主）") @NotBlank
          String actionType,
      @Schema(description = "建议幅度（比例）") @DecimalMin("0") BigDecimal actionPercent,
      @Schema(description = "建议幅度（绝对额）") @DecimalMin("0") BigDecimal actionAmount,
      @Schema(description = "🔴 次要建议动作（可空）") String secondaryActionType,
      @Schema(description = "次要动作幅度（比例）") @DecimalMin("0") BigDecimal secondaryActionPercent,
      @Schema(description = "次要动作幅度（绝对额）") @DecimalMin("0") BigDecimal secondaryActionAmount,
      @Schema(description = "🔴 数据源是否就绪（创意衰退必须为 false）") Boolean dataSourceReady,
      @Schema(description = "🔴 试运行开关（默认 true）") Boolean dryRun,
      @Schema(description = "备注") String note) {}

  public record RuleStatusRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "状态：ENABLED / DISABLED")
          @NotBlank
          String status) {}

  /**
   * 跑一次诊断。
   *
   * <p>🔴 {@code lookbackDays} 缺省走规则自己的窗口；显式给了则以本次为准
   * （允许运营临时把窗口拉长做一次回溯）。
   */
  public record RunDiagnosisRequest(
      @Schema(description = "观察窗口（天，缺省用规则自己的窗口）") @Min(1) @Max(365) Integer lookbackDays,
      @Schema(description = "🔴 试运行（默认 true）：只诊断不产生任何实际调整") Boolean dryRun,
      @Schema(description = "备注") String note) {}

  /**
   * 一键调整。
   *
   * <p>🔴 {@code dryRun} 与规则自身的试运行开关**取更严的那个**：
   * 一次「真执行」不能把一条开着试运行的规则推成真的调整。
   */
  public record ApplyActionRequest(
      @Schema(description = "🔴 试运行（默认 true）：只算「本来会变成多少」，不真的调") Boolean dryRun,
      @Schema(description = "操作理由（写进审计）") @Size(max = 500) String reason) {}

  /** 一键调整的返回：动作的最新状态 + 本次执行结论。 */
  public record AdDiagnosisApplyResult(
      @Schema(description = "建议动作（执行后的状态）") AdDiagnosisActionRow action,
      @Schema(description = "🔴 本次执行结论：APPLIED / REJECTED / SKIPPED / DRY_RUN") String outcome,
      @Schema(description = "🔴 结果说明（被拒时给出原因）") String message,
      @Schema(description = "🔴 护栏结论") String guardrailNote,
      @Schema(description = "说明") String note) {}
}
