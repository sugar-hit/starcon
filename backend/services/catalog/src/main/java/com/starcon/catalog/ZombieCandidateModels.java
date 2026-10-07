package com.starcon.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 3.9.2.2 僵尸主数据清理的 DTO。
 *
 * <p>刻意**独立成文件**而不是并进 {@code CatalogModels}：那个文件有 5200 行且被并行开发的
 * 多个模块共享，与 {@code ImportWizardModels} / {@code ImportValidationModels} /
 * {@code PhysicalSafetyModels} 同一处理方式（同一个包里按模块分文件，避免互相踩）。
 */
public final class ZombieCandidateModels {
  private ZombieCandidateModels() {}

  // ------------------------------------------------------------ 引用统计快照（上游推送）

  /**
   * 上游推送的一条引用观测。
   *
   * <p>{@code observedAt} 必填且是**上游观测的时刻**而不是调用时刻：
   * 一次批量推送里可能包含几小时前就开始统计的快照，用调用时刻会让过期判定失真。
   */
  public record ReferenceStatInput(
      @NotNull BigDecimal metricValue,
      @NotNull OffsetDateTime observedAt) {}

  /** 一次推送的请求体。主体类型由路径给出，一个请求只推一种主体的一批观测。 */
  public record ReferenceStatPush(
      @NotEmpty(message = "至少要推送一条引用观测")
          @Size(max = 500, message = "单次推送最多 500 条")
          List<@NotNull @Valid ReferenceStatItem> items) {}

  public record ReferenceStatItem(
      @NotNull UUID entityId,
      @NotBlank @NotNull String metric,
      @NotNull @DecimalMin(value = "0", message = "引用量不能为负") BigDecimal metricValue,
      @NotBlank(message = "必须标明推送方（trade/warehouse/channel/purchase）") String source,
      @NotNull OffsetDateTime observedAt) {}

  public record ReferenceStatSaved(UUID entityId, String metric, String state) {}

  public record ReferenceStatPushResult(int received, int written, int rejected, List<String> rejectedReasons) {}

  // ------------------------------------------------------------ 规则配置

  public record CleanupRuleInput(
      @Min(value = 30, message = "维护阈值至少 30 天")
          @Max(value = 3650, message = "维护阈值最多 3650 天")
          int inactiveDays,
      @Min(value = 1, message = "快照过期阈值至少 1 天")
          @Max(value = 3650, message = "快照过期阈值最多 3650 天")
          int snapshotStaleDays,
      boolean requireZeroOrderReference,
      boolean requireZeroInventory,
      boolean requireZeroListing,
      boolean requireZeroCost,
      boolean enabled,
      @Size(max = 500, message = "备注最多 500 字") String deactivateNote,
      long version) {}

  public record CleanupRuleView(
      String entityType,
      String entityTypeLabel,
      int inactiveDays,
      int snapshotStaleDays,
      boolean requireZeroOrderReference,
      boolean requireZeroInventory,
      boolean requireZeroListing,
      boolean requireZeroCost,
      boolean enabled,
      String deactivateNote,
      long version,
      boolean configured) {
    public CleanupRuleView {}

    /** 该规则依赖几个上游维度 —— 界面上提示"还差几个事件源"的依据。 */
    public int requiredDimensionCount() {
      int n = 0;
      if (requireZeroOrderReference) n++;
      if (requireZeroInventory) n++;
      if (requireZeroListing) n++;
      if (requireZeroCost) n++;
      return n;
    }
  }

  // ------------------------------------------------------------ 候选清单

  public record CandidateQuery(
      String entityType, String keyword, String verdict, Integer page, Integer size) {}

  public record Candidate(
      UUID entityId,
      String entityType,
      String code,
      String name,
      String status,
      OffsetDateTime lastMaintainedAt,
      Long daysSinceMaintained,
      String verdict,
      String verdictLabel,
      List<String> reasons,
      List<ZombieCandidateRules.DimensionStatus> dimensions,
      int ruleInactiveDays,
      boolean ruleEnabled) {}

  public record CandidatePage(
      List<Candidate> items,
      long total,
      int page,
      int size,
      String entityType,
      long candidateCount,
      long inUseCount,
      long incompleteCount) {

    /** 三个判定计数逐项相加必须等于 {@code total} —— 集成测试钉死这条，分页漏算会立刻暴露。 */
    public boolean countsConsistent() {
      return candidateCount + inUseCount + incompleteCount == total;
    }
  }

  public record CandidateOverview(
      List<OverviewRow> rows, int coveredEntities, int coveredDimensions, List<String> unmappedDimensions) {}

  public record OverviewRow(
      String entityType,
      String entityTypeLabel,
      long totalActive,
      long candidates,
      long inUse,
      long incomplete,
      int inactiveDays,
      int snapshotStaleDays,
      int requiredDimensionCount,
      boolean ruleEnabled,
      long version) {}

  public record Options(
      List<EntityTypeOption> entityTypes,
      List<MetricOption> metrics,
      List<VerdictOption> verdicts,
      List<String> deactivateTargets) {}

  public record EntityTypeOption(String code, String label, String targetTable) {}

  public record MetricOption(String code, String label, String owner, String source, boolean wired) {}

  public record VerdictOption(String code, String label, boolean actionable) {}

  // ------------------------------------------------------------ 批量停用

  public record DeactivateInput(
      @NotEmpty(message = "至少要选一条主数据")
          @Size(max = 500, message = "单次最多停用 500 条")
          List<UUID> entityIds,
      @Size(max = 500, message = "停用理由最多 500 字") String reason) {}

  public record DeactivatedEntry(String code, String name, String previousStatus, String newStatus) {}

  public record DeactivateResult(
      String entityType, int affected, List<DeactivatedEntry> entries, String note) {}

  /** 批量准入被拒时的结构化清单（规则 Z4：整批拒收并逐条点名）。 */
  public record DeactivateBlocked(List<ZombieCandidateRules.BlockedEntry> blocked, String reason) {}

  // ------------------------------------------------------------ 停用记录

  public record RecordQuery(String entityType, UUID entityId, UUID decidedBy, Integer page, Integer size) {}

  public record CleanupRecord(
      UUID id,
      String entityType,
      UUID entityId,
      String entityCode,
      String entityName,
      String previousStatus,
      String newStatus,
      UUID decidedBy,
      String reason,
      OffsetDateTime lastMaintainedAt,
      int affectedCount,
      Object ruleSnapshot,
      Object evidence,
      OffsetDateTime createdAt) {}

  public record CleanupRecordPage(List<CleanupRecord> items, long total, int page, int size) {}

  /** 单条主数据的引用证据（详情面板）。 */
  public record ReferenceEvidence(
      String entityType,
      UUID entityId,
      String code,
      String name,
      String status,
      OffsetDateTime lastMaintainedAt,
      Long daysSinceMaintained,
      String verdict,
      List<ZombieCandidateRules.DimensionStatus> dimensions,
      List<CleanupRecord> recentRecords) {}
}