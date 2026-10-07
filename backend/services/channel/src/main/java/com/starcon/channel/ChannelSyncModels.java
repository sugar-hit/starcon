package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Wire contracts for 02.4 同步运行.
 *
 * <p>Module references follow docs/product/capability-blueprint/02-channel-and-shop.md §2.4.
 *
 * <p>The composite identifiers here are records rather than four loose {@code UUID}s on purpose.
 * 2.4.1.1 makes （连接, 站点, 资源类型）the task granularity — passing those four values around as
 * independent parameters is how a caller ends up syncing a shop's orders against another shop's
 * marketplace, which the schema would happily accept.
 */
public final class ChannelSyncModels {
  private ChannelSyncModels() {}

  /** 资源类型。与 DB 的 CHECK 枚举一致，避免契约与存储各说各话。 */
  public enum ResourceType {
    ORDER,
    LISTING,
    INVENTORY,
    SETTLEMENT
  }

  /** 2.4.2.1 失败原因分类。封闭枚举，原因分布图才能按类聚合而不是按自由文本。 */
  public enum FailureReason {
    AUTH_EXPIRED,
    FIELD_PARSE_FAILED,
    MAPPING_MISSING,
    RATE_LIMITED,
    NETWORK_ERROR,
    UNKNOWN
  }

  public enum RunStatus {
    RUNNING,
    SUCCEEDED,
    PARTIAL,
    FAILED
  }

  /**
   * 2.4.1.1 任务键：一条同步任务的完整身份。
   *
   * <p>A record so a partial key cannot be passed by accident — the whole point of the granularity
   * rule is that all four parts travel together.
   */
  public record SyncTaskKey(
      UUID connectionId,
      UUID marketplaceId,
      ResourceType resourceType,
      String partitionKey) {}

  /**
   * 2.4.1.3 游标水位。
   *
   * <p>Carries both halves deliberately: most platforms watermark by time, but several paginate with
   * an opaque string marker. Advancing only one half silently restarts a full-window pull.
   */
  public record SyncWatermark(Instant value, String externalMarker) {
    public static SyncWatermark start() {
      return new SyncWatermark(Instant.EPOCH, "");
    }
  }

  // ---- 2.4.1 同步任务 ----

  /** 2.4.1.1 同步任务列表：每个（连接, 站点, 资源, 分区）一行，附带最近运行与积压量。 */
  public record SyncCursorView(
      UUID id,
      SyncTaskKey task,
      /** 2.4.1.3 当前已提交水位。只有处理提交后才推进。 */
      SyncWatermark watermark,
      /** 2.4.1.3 积压量：上次运行起失败或未处理的记录数。 */
      int backlogCount,
      int consecutiveFailures,
      String lastRunStatus,
      Instant lastRunAt,
      long version) {}

  /** 2.4.1.2 运行日志：单次同步运行的分页进度与处理计数。 */
  public record SyncRunView(
      UUID id,
      SyncTaskKey task,
      String triggerKind,
      String status,
      Instant startedAt,
      Instant finishedAt,
      Long durationMs,
      int fetchedCount,
      int writtenCount,
      int updatedCount,
      int skippedCount,
      int warningCount,
      int errorCount,
      String errorMessage,
      SyncWatermark cursorBefore,
      /** 游标推进到的位置。FAILED 运行为 null —— 没提交就不会推进。 */
      @Schema(nullable = true) SyncWatermark cursorAfter,
      int openFailureCount) {}

  /**
   * 2.4.1.2/2.4.1.3 启动一次运行。
   *
   * <p>{@code from} 是可选的起拉点：留空表示从当前游标续传，给值表示按外部单号/时间重拉一段
   * 窗口——那是回退补拉的常规操作，重拉必须幂等由 sync_failure 的去重唯一键保证。
   */
  public record SyncRunInput(
      @NotNull UUID connectionId,
      @NotNull UUID marketplaceId,
      @NotNull ResourceType resourceType,
      String partitionKey,
      @Pattern(regexp = "SCHEDULED|MANUAL|RESUME|ROLLBACK_REPLAY") String triggerKind,
      @Schema(nullable = true) Instant from,
      @Schema(nullable = true) String externalMarkerFrom) {}

  /**
   * 2.4.1.2 提交一次运行。
   *
   * <p>{@code cursorValue} 是"已处理并提交到的水位"，服务端只接受不早于本次起始水位的推进；
   * 失败的部分必须同时写进失败队列，否则下一次续传会跳过它们。
   */
  public record SyncRunCommit(
      @NotNull String status,
      @Min(0) int fetchedCount,
      @Min(0) int writtenCount,
      @Min(0) int updatedCount,
      @Min(0) int skippedCount,
      @Min(0) int warningCount,
      @Min(0) int errorCount,
      String errorMessage,
      @Schema(nullable = true) Instant cursorValue,
      String externalMarkerTo) {}

  // ---- 2.4.2 异常处理 ----

  /** 2.4.2.1 失败队列条目。 */
  public record SyncFailureView(
      UUID id,
      UUID runId,
      SyncTaskKey task,
      String failureReason,
      String externalRecordKey,
      String externalPayloadRef,
      int attemptCount,
      String status,
      /** 2.4.2.1 授权失效类失败自动升级为工作台待办。 */
      boolean escalated,
      String lastError,
      Instant createdAt,
      Instant resolvedAt) {}

  /**
   * 2.4.2.1 登记一条失败。
   *
   * <p>{@code externalRecordKey} 是幂等键：同租户同连接同资源的同一条外部记录只有一条未关闭的
   * 失败，因此重放或重试不会堆出第二张待办。
   */
  public record SyncFailureRecordInput(
      @NotNull UUID runId,
      @NotNull FailureReason failureReason,
      @NotBlank @jakarta.validation.constraints.Size(max = 255) String externalRecordKey,
      String externalPayloadRef,
      String lastError) {}

  /** 2.4.2.2 人工重放输入。 */
  public record SyncFailureReplayInput(
      @NotBlank String outcome,
      @Schema(nullable = true) String lastError,
      @Min(1) Integer attemptCount) {}

  /** 2.4.2.1 失败原因分布。 */
  public record FailureClusterView(String reason, long open, long escalated, long total) {}

  /** 2.4.1.3 游标回退。必须显式声明意图，触发器据此放行。 */
  public record SyncCursorRewindInput(@NotNull Instant to, String externalMarker) {}
}