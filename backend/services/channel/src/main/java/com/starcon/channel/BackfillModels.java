package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 02.6.2 增量补拉的线上契约。
 *
 * <p>对应 docs/product/capability-blueprint/02-channel-and-shop.md §2.6.2.1 增量补拉。
 *
 * <p>四条不可让步的纪律在这个契约里都有对应形状：
 *
 * <ol>
 *   <li>幂等：{@code BackfillRecordInput.externalKey} + {@code idempotencyKey}
 *   <li>分段轮询：{@code PaginationMode.RECENT_N_SCAN} + {@link BackfillTaskView#delayedDiscovery()}
 *   <li>四个数字：{@link BackfillTaskView} 的 fetched/created/updated/deduplicated 四个独立计数
 *   <li>幂等键是入参：{@link BackfillTaskInput#idempotencyKey()} 不是可选项
 * </ol>
 */
public final class BackfillModels {
  private BackfillModels() {}

  /**
   * 02.6.2.1 平台增量接口形态。
   *
   * <p>这是本模块唯一需要外部知识（各平台 API 的真实行为）的判断，因此显式建模成入参
   * 而不是由代码推断 —— 猜错的后果是"补拉看起来成功了，其实只捞到最近几条"。
   */
  public enum PaginationMode {
    /**
     * 平台支持按时间/游标取增量：能直接定位到时间窗，一次轮询即可覆盖。
     */
    TIME_INCREMENTAL,
    /**
     * 平台只提供"最近 N 条"：无法直接定位到窗口，必须分段轮询并靠去重兜底。
     *
     * <p>选这一档时 {@code delayedDiscovery} 被 DB 层 CHECK 强制为 true，
     * 页面上必须标明「补拉可能延迟发现」。
     */
    RECENT_N_SCAN
  }

  /** 补拉状态机。取值与 {@code backfill_task.status} 的 CHECK 约束一一对应。 */
  public enum BackfillStatus {
    /** 已受理，等待执行。 */
    PENDING,
    /** 执行中。 */
    RUNNING,
    /** 全段完成。 */
    SUCCEEDED,
    /** 部分完成：去重或失败导致。 */
    PARTIAL,
    /** 整体失败。 */
    FAILED,
    /** 人工中止。 */
    CANCELLED
  }

  /** 逐条判定结果。取值与 {@code backfill_record} 的处置语义一一对应。 */
  public enum RecordDisposition {
    /** 首次补到：下游应新增。 */
    CREATED,
    /** 之前补过、但内容指纹变了：下游应更新，不新增。 */
    UPDATED,
    /** 之前已补过、内容一致：下游**不得**再产生第二条业务记录。 */
    DEDUPLICATED,
    /** 解析或校验失败。 */
    FAILED
  }

  /**
   * 02.6.2.1 发起补拉。
   *
   * <p>{@code idempotencyKey} 必填（蓝图「输入」把 {@code idempotency_key} 列为契约的一部分）：
   * 相同 key 重复发起返回既有任务，不会产生第二个补拉任务。
   */
  public record BackfillTaskInput(
      @NotNull UUID connectionId,
      @Schema(nullable = true) UUID marketplaceId,
      @NotBlank @Pattern(regexp = "ORDER|LISTING|INVENTORY|SETTLEMENT|PRODUCT") String resourceType,
      /** 时间窗左端（闭）。绑 timestamptz 列必须写 ?::timestamptz。 */
      @NotNull OffsetDateTime windowStart,
      /** 时间窗右端（开）。 */
      @NotNull OffsetDateTime windowEnd,
      @NotNull BackfillTrigger triggerSource,
      /** 幂等键。同租户下重复 key 不会产生第二个任务。 */
      @NotBlank @Size(max = 200) String idempotencyKey,
      @Schema(nullable = true) UUID cursorId,
      @NotNull PaginationMode paginationMode,
      /** 分段轮询时由执行端声明是否已可能延迟发现。RECENT_N_SCAN 强制为 true。 */
      Boolean delayedDiscovery,
      @Min(1) @Max(1000) Integer pageSize,
      @Min(1) @Max(500) Integer maxPages,
      @Min(1) Integer rateLimitPerMinute) {}

  /** 补拉触发来源，对应蓝图「输入」里点名的三类触发。 */
  public enum BackfillTrigger {
    /** 漏推检测：webhook 接收记录里发现时间窗缺口。 */
    WEBHOOK_GAP,
    /** 游标回退：2.4.1.3 rewind 之后重拉。 */
    CURSOR_REWIND,
    /** 人工发起。 */
    MANUAL,
    /** 平台故障恢复后补拉。 */
    PLATFORM_RECOVERY
  }

  /**
   * 02.6.2.1 补拉任务视图。
   *
   * <p>{@code fetchedCount / createdCount / updatedCount / deduplicatedCount} 是蓝图「输出」
   * 点名的四个数字，刻意分列四个字段而不是一个合计 —— 合计会把"补到了东西"与
   * "补到了没见过的东西"混为一谈，而后者才是缺口是否真被填上的依据。
   */
  public record BackfillTaskView(
      UUID id,
      UUID connectionId,
      String authorizedAccount,
      String platformCode,
      @Schema(nullable = true) UUID marketplaceId,
      @Schema(nullable = true) String marketplaceCode,
      String resourceType,
      Instant windowStart,
      Instant windowEnd,
      String triggerSource,
      String idempotencyKey,
      @Schema(nullable = true) UUID cursorId,
      String paginationMode,
      /** 页面上必须原样标出「补拉可能延迟发现」。 */
      boolean delayedDiscovery,
      int pageSize,
      int maxPages,
      int rateLimitPerMinute,
      String status,
      int fetchedCount,
      int createdCount,
      int updatedCount,
      int deduplicatedCount,
      int failedCount,
      int segmentsDone,
      int segmentTotal,
      String cursorAfter,
      String lastError,
      @Schema(nullable = true) Integer durationMs,
      @Schema(nullable = true) Instant startedAt,
      @Schema(nullable = true) Instant finishedAt,
      Instant createdAt) {}

  /** 一条外部记录的补拉结果。externalKey + payloadDigest 是幂等判定的输入。 */
  public record BackfillRecordInput(
      /** 外部记录主键。同连接同资源类型下全局唯一 —— 重复补拉靠它去重。 */
      @NotBlank @Size(max = 200) String externalKey,
      /** 内容指纹：指纹相同 = 重复（不新增），不同 = 更新。 */
      @Size(max = 64) String payloadDigest,
      /** 原始报文（已脱敏）。不存明文凭据。 */
      String payload,
      /** 该记录在平台侧的发生时刻。补拉统计与排查都依赖它。 */
      @NotNull OffsetDateTime externalOccurredAt,
      /** 失败原因（仅当处置为 FAILED）。 */
      @Size(max = 500) String failureReason) {}

  /**
   * 02.6.2.1 提交一批补拉结果。
   *
   * <p>批次大小受任务 {@code pageSize} 与平台分页上限约束 —— 这就是蓝图
   * 「识别平台接口的分页上限与限频」的落点。
   */
  public record BackfillSubmitInput(
      @NotNull @Valid List<BackfillRecordInput> records,
      /** 本次是否命中平台限频（留痕，命中要退避重试）。 */
      Boolean rateLimited,
      /**
       * 分段轮询水位：本次取回的最早一条记录时刻。
       * RECENT_N_SCAN 时逐段向窗口起点推进，水位单调递减就是「往回够」的证据。
       */
      @Schema(nullable = true) OffsetDateTime segmentCoveredFrom,
      /** 本段是否已覆盖到窗口起点。 */
      Boolean segmentReachedWindowStart) {}

  /** 逐条结果：让"这批里哪条是重复"一眼可见，而不是只给一个合计。 */
  public record BackfillRecordResult(
      String externalKey,
      String disposition,
      /** CREATED 时是新记录 ID；其余为既有记录 ID（更新/去重都指向它）。 */
      @Schema(nullable = true) UUID recordId,
      @Schema(nullable = true) Integer seenCount,
      @Schema(nullable = true) String failureReason) {}

  /** 提交一批的结果汇总。四个数字与任务头一致，便于前端即时反馈。 */
  public record BackfillSubmitResult(
      UUID taskId,
      int fetched,
      int created,
      int updated,
      int deduplicated,
      int failed,
      List<BackfillRecordResult> results) {}

  /** 补拉任务收尾：结算四数、推进游标。 */
  public record BackfillCompleteInput(
      @NotNull BackfillStatus status,
      /** 推进后的游标值。只有 SUCCEEDED / PARTIAL 才写回 sync_cursor。 */
      @Size(max = 128) String cursorAfter,
      @Size(max = 500) String lastError,
      @Schema(nullable = true) Integer durationMs) {}

  /**
   * 02.6.2.1 能力声明。
   *
   * <p>下游未交付的部分显式 {@code available=false} 并点名 {@code ownerModule}，
   * 界面上据此禁用按钮 —— 而不是让用户点了才报错。
   */
  public record BackfillCapabilities(
      List<BackfillCapability> capabilities,
      /** 各资源类型可用的平台增量接口形态。 */
      List<BackfillResourceSupport> resources) {}

  public record BackfillCapability(
      String key, boolean available, String description, @Schema(nullable = true) String ownerModule) {}

  public record BackfillResourceSupport(String resourceType, List<String> paginationModes, String note) {}

  /** 补拉状态分布：让"补拉大面积失败"这类事故一眼可见。 */
  public record BackfillStatusSummary(String status, long total, @Schema(nullable = true) Instant oldest) {}

  /**
   * 02.6.2.1 缺口检测结果（漏推检测触发源）。
   *
   * <p>来自 2.6.1.1 的接收记录：某个时间窗内一条推送都没收到，就是漏推的候选区间。
   */
  public record BackfillGap(
      UUID connectionId,
      @Schema(nullable = true) UUID shopId,
      String resourceType,
      Instant windowStart,
      Instant windowEnd,
      /** 该窗口内收到的推送条数：0 才是缺口。 */
      long receiptsInWindow,
      /** 相对上一次推送的静默时长，静默越久越可疑。 */
      @Schema(nullable = true) Long silenceSeconds) {}
}
