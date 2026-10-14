package com.starcon.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
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
 * <p>刻意**独立成文件**而不是并进 {@code CatalogModels}：那个文件有 5200 行且被并行开发的 多个模块共享，与 {@code ImportWizardModels}
 * / {@code ImportValidationModels} / {@code PhysicalSafetyModels} 同一处理方式。
 *
 * <p><b>每个类型名都带 {@code Zombie} 前缀</b>，这不是审美偏好而是硬约束： {@code tools/codegen/catalog-contracts.py}
 * 把四个服务的 schemas **拍平进同一个命名空间** （identity / catalog / channel / warehouse），重名的类型后者直接覆盖前者。 第一版用
 * {@code ZombieOptions} / {@code ZombieOverviewRow} / {@code ZombieDimensionStatus} 这类通用名时，
 * warehouse 的 {@code ZombieOverviewRow}（balanceId / qualityStatusId …）就把本模块的覆盖掉了 ——
 * 生成物里看不出任何异常，前端却会拿到一个字段完全不相干的类型。
 */
public final class ZombieCandidateModels {
  private ZombieCandidateModels() {}

  // ------------------------------------------------------------ 引用统计快照（上游推送）

  /** 一次推送的请求体。一个请求只推一种主体的一批观测（主体类型由路径给出）。 */
  public record ZombieReferenceStatPush(
      @NotEmpty(message = "至少要推送一条引用观测") @Size(max = 500, message = "单次推送最多 500 条")
          List<@NotNull @Valid ZombieReferenceStatItem> items) {}

  public record ZombieReferenceStatItem(
      @NotNull UUID entityId,
      @NotBlank @NotNull String metric,
      @NotNull @DecimalMin(value = "0", message = "引用量不能为负") BigDecimal metricValue,
      @NotBlank(message = "必须标明推送方（trade/warehouse/channel/purchase）") String source,
      @NotNull OffsetDateTime observedAt) {}

  /**
   * 推送结果。区分 {@code written} 与 {@code rejected} 很重要： 主体不存在或角色不匹配的条目逐条回报原因，而不是整批静默丢弃 ——
   * 静默丢弃会让上游以为推送成功，而那一维会永远停留在"未知"。
   */
  public record ZombieReferenceStatPushResult(
      int received, int written, int rejected, List<String> rejectedReasons) {}

  // ------------------------------------------------------------ 规则配置

  public record ZombieCleanupRuleInput(
      @Min(value = 30, message = "维护阈值至少 30 天") @Max(value = 3650, message = "维护阈值最多 3650 天")
          int inactiveDays,
      @Min(value = 1, message = "快照过期阈值至少 1 天") @Max(value = 3650, message = "快照过期阈值最多 3650 天")
          int snapshotStaleDays,
      boolean requireZeroOrderReference,
      boolean requireZeroInventory,
      boolean requireZeroListing,
      boolean requireZeroCost,
      boolean enabled,
      @Size(max = 500, message = "备注最多 500 字") String deactivateNote,
      long version) {}

  public record ZombieCleanupRuleView(
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
      boolean configured) {}

  // ------------------------------------------------------------ 候选清单

  public record ZombieCandidateQuery(
      String entityType, String keyword, String verdict, Integer page, Integer size) {}

  public record ZombieCandidate(
      UUID entityId,
      String entityType,
      String code,
      String name,
      String status,
      @Schema(nullable = true) OffsetDateTime lastMaintainedAt,
      @Schema(nullable = true) Long daysSinceMaintained,
      String verdict,
      String verdictLabel,
      List<String> reasons,
      List<ZombieCandidateRules.ZombieDimensionStatus> dimensions,
      int ruleInactiveDays,
      boolean ruleEnabled) {}

  public record ZombieCandidatePage(
      List<ZombieCandidate> items,
      long total,
      int page,
      int size,
      String entityType,
      long candidateCount,
      long inUseCount,
      long incompleteCount) {}

  public record ZombieCandidateOverview(
      List<ZombieOverviewRow> rows,
      int coveredEntities,
      int coveredDimensions,
      List<String> unmappedDimensions) {}

  public record ZombieOverviewRow(
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

  public record ZombieOptions(
      List<ZombieEntityTypeOption> entityTypes,
      List<ZombieMetricOption> metrics,
      List<ZombieVerdictOption> verdicts,
      List<String> deactivateTargets) {}

  public record ZombieEntityTypeOption(String code, String label, String targetTable) {}

  /**
   * 引用维度选项。{@code wired} 标注该维度的上游服务在本仓库是否已存在 —— 界面对未接入的维度禁用开关（与 warehouse 的 /inventory/capabilities
   * 同一手法）。
   */
  public record ZombieMetricOption(
      String code, String label, String owner, String source, boolean wired) {}

  public record ZombieVerdictOption(String code, String label, boolean actionable) {}

  // ------------------------------------------------------------ 批量停用

  public record ZombieDeactivateInput(
      @NotEmpty(message = "至少要选一条主数据") @Size(max = 500, message = "单次最多停用 500 条")
          List<UUID> entityIds,
      @Size(max = 500, message = "停用理由最多 500 字") String reason) {}

  public record ZombieDeactivatedEntry(
      String code, String name, String previousStatus, String newStatus) {}

  public record ZombieDeactivateResult(
      String entityType, int affected, List<ZombieDeactivatedEntry> entries, String note) {}

  // ------------------------------------------------------------ 停用记录

  public record ZombieRecordQuery(
      String entityType, UUID entityId, UUID decidedBy, Integer page, Integer size) {}

  /**
   * 停用记录（规则 Z5：冻结当时的规则与证据）。
   *
   * <p>{@code ruleSnapshot} 与 {@code evidence} 是**显式结构**而不是 {@code Object}： 写成 {@code Object} 时
   * SpringDoc 生成的 TS 类型是 {@code { }}（无索引签名）， 前端读任何一个键都是 TS2339 —— 契约必须能被安全消费，而不是"能编过就行"。
   */
  public record ZombieCleanupRecord(
      UUID id,
      String entityType,
      UUID entityId,
      String entityCode,
      String entityName,
      String previousStatus,
      String newStatus,
      UUID decidedBy,
      String reason,
      @Schema(nullable = true) OffsetDateTime lastMaintainedAt,
      int affectedCount,
      ZombieRuleSnapshot ruleSnapshot,
      ZombieEvidenceSnapshot evidence,
      OffsetDateTime createdAt) {}

  /**
   * 规则冻结快照。
   *
   * <p>四个维度开关写成**显式布尔列**而不是 {@code Map<String,Boolean>}： codegen 的 {@code ts()} 不处理 {@code
   * additionalProperties}，Map 会退化成 {@code { }} —— 一个前端读任何键都是 TS2339 的类型。显式列是完整可用的类型，
   * 而且新增维度时忘记加一列会被编译器发现，Map 不会。
   */
  public record ZombieRuleSnapshot(
      String entityType,
      int inactiveDays,
      int snapshotStaleDays,
      String deactivateNote,
      long ruleVersion,
      boolean requireZeroOrderReference,
      boolean requireZeroInventory,
      boolean requireZeroListing,
      boolean requireZeroCost) {}

  /** 证据冻结快照：四个维度当时的取值、观测时刻与判定状态。 */
  public record ZombieEvidenceSnapshot(
      String verdict,
      ZombieDimensionEvidence orderLineCount,
      ZombieDimensionEvidence inventoryOnHand,
      ZombieDimensionEvidence listingCount,
      ZombieDimensionEvidence unitCost) {}

  public record ZombieDimensionEvidence(
      String state,
      @Schema(nullable = true) String value,
      @Schema(nullable = true) String observedAt,
      boolean stale) {}

  public record ZombieCleanupRecordPage(
      List<ZombieCleanupRecord> items, long total, int page, int size) {}

  /** 单条主数据的引用证据 + 停用历史（详情面板）。 */
  public record ZombieReferenceEvidence(
      String entityType,
      UUID entityId,
      String code,
      String name,
      String status,
      @Schema(nullable = true) OffsetDateTime lastMaintainedAt,
      @Schema(nullable = true) Long daysSinceMaintained,
      String verdict,
      List<ZombieCandidateRules.ZombieDimensionStatus> dimensions,
      List<ZombieCleanupRecord> recentRecords) {}
}
