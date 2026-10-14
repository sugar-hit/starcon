package com.starcon.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 11.4.2.2 客服知识库与话术的请求与响应记录。
 *
 * <p><b>为什么单独成文件而不是并进 {@code CatalogModels}</b>：{@code CatalogModels} 是 并行车道都会追加的公共文件，且它<b>不在</b>
 * {@code merge-union} 驱动器的按键求并范围内 （驱动器只覆盖 {@code packages/api-contracts} 与台账）。本模块的记录放进公共文件会让
 * 多条车道在同一处各追加一段，行级三方合并必然冲突。模块自成一个文件则天然无交集， 与 {@code CompletenessScoreModels} / {@code
 * PhysicalSafetyModels} 同一做法。
 *
 * <p>蓝图 11.4.2.2：「维护客服知识库与回复模板：常见问题标准答法、平台政策说明、 退换货话术、多语言版本；记录使用效果。」「输出：话术库 + 客服一键引用；命中率统计。」
 */
public final class KnowledgeBaseModels {
  private KnowledgeBaseModels() {}

  // ================================================================ 词表与概览

  /** 词表选项：前端下拉的数据源，与 {@link KnowledgeBaseRules} 同一份枚举。 */
  public record KnowledgeBaseOptions(
      List<String> platforms,
      List<KnowledgeBaseRules.Option> platformOptions,
      List<String> entryTypes,
      List<KnowledgeBaseRules.Option> entryTypeOptions,
      List<String> usageOutcomes,
      List<KnowledgeBaseRules.Option> outcomeOptions,
      List<String> reviewStatuses,
      List<KnowledgeBaseRules.Option> reviewStatusOptions,
      List<String> translationOrigins,
      List<KnowledgeBaseRules.Option> translationOriginOptions,
      String note) {}

  /**
   * 概览：库存量 + 命中率 + 待办量。
   *
   * <p>{@code hitRate} 可为 {@code null}（规则 R4）：「一条话术都没有」与 「话术一条都没被用过」是两件不同的事，页面必须能区分，所以不返回 0 冒充。
   */
  public record KnowledgeBaseOverview(
      long entryTotal,
      long entryEnabled,
      long entryDisabled,
      long entryUntranslated,
      long translationTotal,
      long translationDraft,
      long ruleVersionTotal,
      long ruleVersionActive,
      long bindingTotal,
      long reviewPending,
      long reviewReviewing,
      long reviewDone,
      long usageTotal,
      long usageHit,
      long usageRejected,
      long usageAbandoned,
      long hitEntryCount,
      BigDecimal hitRate,
      String note) {}

  // ================================================================ 知识条目

  /** 一条话术在列表里的一行（含类型标签与已配语言数）。 */
  public record KnowledgeEntryRow(
      UUID id,
      String code,
      String title,
      String entryType,
      String entryTypeLabel,
      String body,
      String sourceLocale,
      List<String> triggerKeywords,
      boolean enabled,
      long translationCount,
      long usageCount,
      long reviewPendingCount,
      long version,
      String createdAt,
      String updatedAt) {}

  /**
   * 单条话术详情：条目 + 译文 + 挂钩的规则版本 + 命中率 + 最近引用。
   *
   * <p>{@code ruleBindings} 是蓝图硬要求「话术需与平台规则版本挂钩」的读出口： 详情页要能当场回答「这条话术依据哪几版平台政策」。
   */
  public record KnowledgeEntryDetail(
      UUID id,
      String code,
      String title,
      String entryType,
      String entryTypeLabel,
      String body,
      String sourceLocale,
      List<String> triggerKeywords,
      boolean enabled,
      long version,
      String createdAt,
      String updatedAt,
      List<EntryTranslation> translations,
      List<EntryRuleBinding> ruleBindings,
      List<KbUsageRow> recentUsages,
      EntryEffect effect) {}

  /** 单条话术的使用效果：命中率按 R2~R4 算，与页面上的数字同源。 */
  public record EntryEffect(
      long usageCount,
      long resolvedCount,
      long partialCount,
      long unresolvedCount,
      long abandonedCount,
      long rejectedCount,
      long hitUsageCount,
      BigDecimal hitRate,
      String lastUsedAt) {}

  /** 列表分页。{@code total} 是真 count（与 items 同条件），不是前端算的。 */
  public record KnowledgeEntryPage(
      List<KnowledgeEntryRow> items, int page, int size, long total, int totalPages) {}

  // ================================================================ 多语言版本

  public record EntryTranslation(
      UUID id,
      UUID entryId,
      String locale,
      String title,
      String body,
      String origin,
      String status,
      long version,
      String createdAt,
      String updatedAt) {}

  /** 译文完整度：这些语言里哪些能直接发给买家。 */
  public record EntryTranslationCoverage(
      String sourceLocale, List<LocaleCoverage> locales, long readyCount, long totalCount) {}

  public record LocaleCoverage(
      String locale, boolean translated, boolean reviewed, boolean usable) {}

  // ================================================================ 平台规则版本

  public record RuleVersionRow(
      UUID id,
      String platform,
      String policyKey,
      String versionLabel,
      String title,
      String changeSummary,
      String status,
      String effectiveFrom,
      String effectiveTo,
      long bindingCount,
      long version,
      String createdAt,
      String updatedAt) {}

  public record RuleVersionPage(
      List<RuleVersionRow> items, int page, int size, long total, int totalPages) {}

  // ================================================================ 挂钩与待复核

  /** 挂钩一行：这条话术依据哪一版政策。 */
  public record EntryRuleBinding(
      UUID id,
      UUID entryId,
      UUID ruleVersionId,
      String platform,
      String policyKey,
      String versionLabel,
      String ruleTitle,
      String ruleStatus,
      String effectiveFrom,
      String note,
      String boundAt) {}

  /**
   * 待复核任务一行 —— 蓝图「平台政策变化时能定位哪些话术需要更新」的读出口。
   *
   * <p>{@code supersedingRuleLabel} 是<b>接替它的那一版</b>：没有这一列客服看到 「待复核」不知道该去比对哪一版，只能一条条翻规则历史。
   */
  public record ReviewTaskRow(
      UUID id,
      UUID entryId,
      String entryCode,
      String entryTitle,
      UUID ruleVersionId,
      String platform,
      String policyKey,
      String versionLabel,
      String ruleChangeSummary,
      UUID supersedingRuleId,
      String supersedingRuleLabel,
      String supersedingChangeSummary,
      String reviewStatus,
      String note,
      String assignedTo,
      String assignedAt,
      String reviewedBy,
      String reviewedAt,
      String createdAt) {}

  public record ReviewTaskPage(
      List<ReviewTaskRow> items, int page, int size, long total, int totalPages) {}

  // ================================================================ 引用记录

  /** 一次引用事件。 */
  public record KbUsageRow(
      UUID id,
      UUID entryId,
      String entryCode,
      String entryTitle,
      UUID entryTranslationId,
      String locale,
      String agentId,
      String conversationRef,
      String outcome,
      String outcomeLabel,
      boolean countsAsHit,
      String note,
      String createdAt) {}

  public record KbUsagePage(
      List<KbUsageRow> items, int page, int size, long total, int totalPages) {}

  /**
   * 命中率排行：蓝图输出「命中率统计」的可查面。
   *
   * <p>每行自带 {@code hitRate}（该条话术自己的命中引用比例）与全库的 {@code hitRate} 分位参照，供页面直接排序而不必在前端重算。
   */
  public record EntryHitRateRow(
      UUID entryId,
      String code,
      String title,
      String entryType,
      String entryTypeLabel,
      boolean enabled,
      long usageCount,
      long hitUsageCount,
      long rejectedCount,
      BigDecimal hitRate,
      long reviewPendingCount,
      String lastUsedAt) {}

  // ================================================================ 请求体

  public record KnowledgeEntryInput(
      @NotBlank(message = "话术编码不能为空") String code,
      @NotBlank(message = "标题不能为空") @Size(max = KnowledgeBaseRules.MAX_TITLE, message = "标题过长")
          String title,
      @NotBlank(message = "话术类型不能为空") String entryType,
      @NotBlank(message = "标准答法正文不能为空") String body,
      @NotBlank(message = "源语言不能为空") String sourceLocale,
      String triggerKeywords,
      @NotNull(message = "启用状态不能为空") Boolean enabled,
      Long version) {}

  public record EntryTranslationInput(
      @NotBlank(message = "语言不能为空") String locale,
      @NotBlank(message = "标题不能为空") @Size(max = KnowledgeBaseRules.MAX_TITLE, message = "标题过长")
          String title,
      @NotBlank(message = "译文正文不能为空") String body,
      String origin,
      String status,
      Long version) {}

  public record RuleVersionInput(
      @NotBlank(message = "平台不能为空") String platform,
      @NotBlank(message = "政策编码不能为空") String policyKey,
      @NotBlank(message = "版本标签不能为空") String versionLabel,
      @NotBlank(message = "标题不能为空") @Size(max = KnowledgeBaseRules.MAX_TITLE, message = "标题过长")
          String title,
      String changeSummary,
      @NotNull(message = "生效起点不能为空") OffsetDateTime effectiveFrom,
      OffsetDateTime effectiveTo) {}

  public record RuleBindingInput(@NotNull(message = "规则版本不能为空") UUID ruleVersionId, String note) {}

  public record ReviewTaskUpdateInput(
      @NotBlank(message = "复核状态不能为空") @Schema(description = "PENDING / REVIEWING / DONE")
          String reviewStatus,
      String note) {}

  public record UsageInput(
      @NotBlank(message = "引用结果不能为空")
          @Schema(description = "RESOLVED / PARTIAL / UNRESOLVED / ABANDONED / REJECTED")
          String outcome,
      UUID entryTranslationId,
      String conversationRef,
      String note) {}

  /** 计数器：创建类操作返回的最小回执（与 V21 的 ContentTemplateSaved 同一形状）。 */
  public record KnowledgeSaved(UUID id, long version) {}

  public record DeletedResponse(boolean deleted) {}
}
