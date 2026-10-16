package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 9.4.2.1 平台回传的契约形状。
 *
 * <p>风格照抄同服务相邻的 {@code ShipmentRecordModels}（9.4.1.2）与
 * {@code OrderConsolidationModels}（9.3.1.2）：枚举与记录同文件，
 * 输入用 {@code @Valid} record，响应是不可变 record。
 *
 * <p><b>状态词汇刻意不另起一套</b>：本模块的 {@link WritebackStatus} 与同服务
 * V119 {@code order_platform_writeback_task}、V144
 * {@code order_consolidation_writeback_task} 的取值域<b>逐字一致</b>
 * （PENDING / SUBMITTED / CONFIRMED / REJECTED / PENDING_VERIFICATION）。
 * 同一个词在三张表里三套值，是事后追责时最贵的一种不一致。
 */
final class ShipmentWritebackModels {
  private ShipmentWritebackModels() {}

  // ================================================================ 枚举

  /**
   * 回传任务状态机 —— <b>三态必须是三态</b>。
   *
   * <p>{@code PENDING_VERIFICATION} 是本枚举存在的唯一理由：蓝图那句加粗
   * 「<b>结果未知要先查证再重发</b>」否掉了两种偷懒实现 —— 把未知塞进
   * {@code REJECTED}（平台其实已经收到，重发 = 平台侧重复发货），或把未知
   * 当成 {@code CONFIRMED}（平台压根没收到，迟发判定成立而无人知晓）。
   */
  enum WritebackStatus {
    PENDING("待回传"),
    SUBMITTED("已提交平台"),
    CONFIRMED("平台已受理"),
    REJECTED("平台拒绝"),
    PENDING_VERIFICATION("结果未知·待查证"),
    SKIPPED("决定不回传"),
    SUPERSEDED("已被修正版取代");

    public final String displayName;

    WritebackStatus(String displayName) {
      this.displayName = displayName;
    }

    /** 终态：不可再动（DB 触发器同口径白名单）。 */
    public boolean terminal() {
      return this == CONFIRMED || this == SKIPPED || this == SUPERSEDED;
    }

    /** 只有「还可能重发」的态才允许进重试排程。 */
    public boolean retryable() {
      return this == PENDING || this == REJECTED;
    }

    /** 结果未知态：<b>重试前必须先查证</b>（本模块最容易被做错的一处）。 */
    public boolean unverified() {
      return this == PENDING_VERIFICATION;
    }
  }

  /**
   * 查证结论。取值域与 channel {@code platform_operation.verification} <b>逐字对齐</b>，
   * 所以本模块把结论转给 2.5.1.2 时不需要做映射表 —— 少一层映射就少一处能写歪的地方。
   */
  enum WritebackVerification {
    NOT_REQUIRED("无需查证"),
    CONFIRMED_APPLIED("查证确认已应用"),
    CONFIRMED_NOT_APPLIED("查证确认未应用"),
    STILL_UNKNOWN("查证后仍不确定");

    public final String displayName;

    WritebackVerification(String displayName) {
      this.displayName = displayName;
    }

    /** 只有「确认没应用」才允许重新提交（DB 触发器 {@code guard_shipment_writeback_state}）。 */
    public boolean allowsResubmit() {
      return this == CONFIRMED_NOT_APPLIED;
    }
  }

  /** 一次尝试的终局（与任务状态同词汇，少一个 SENDING 之外的独立命名）。 */
  enum WritebackAttemptOutcome {
    SENDING("已投递待回执"),
    CONFIRMED("平台已受理"),
    REJECTED("平台拒绝"),
    PENDING_VERIFICATION("结果未知·待查证");

    public final String displayName;

    WritebackAttemptOutcome(String displayName) {
      this.displayName = displayName;
    }
  }

  /** 查证结论（不含 NOT_REQUIRED —— 那是「没查过」，不是一种结论）。 */
  enum WritebackVerificationConclusion {
    CONFIRMED_APPLIED("查证确认已应用"),
    CONFIRMED_NOT_APPLIED("查证确认未应用"),
    STILL_UNKNOWN("查证后仍不确定");

    public final String displayName;

    WritebackVerificationConclusion(String displayName) {
      this.displayName = displayName;
    }
  }

  /** 查证走哪条路：经 2.5.1.2 的 verify 端点，还是人工在平台上查过后登记。 */
  enum WritebackVerificationSource {
    CHANNEL_VERIFY("经平台对接服务查证"),
    MANUAL_ATTESTATION("人工查证");

    public final String displayName;

    WritebackVerificationSource(String displayName) {
      this.displayName = displayName;
    }
  }

  /** 清单分组维度（都是真实的 GROUP BY，不是把一页在 Java 里数）。 */
  enum WritebackGroupBy {
    STATUS("回传状态"),
    PLATFORM("平台"),
    CARRIER("承运商"),
    VERIFICATION("查证结论");

    public final String displayName;

    WritebackGroupBy(String displayName) {
      this.displayName = displayName;
    }
  }

  /** 排序白名单（值不进 SQL 拼接）。 */
  enum WritebackSort {
    CREATED_AT_DESC,
    CREATED_AT_ASC,
    SHIPPED_AT_DESC,
    ATTEMPT_COUNT_DESC,
    NEXT_ATTEMPT_AT_ASC
  }

  // ================================================================ 能力声明

  /** 能力维度：哪些真读得到、哪些读不到（读不到就<b>显式报出</b>）。 */
  public record ShipmentWritebackCapabilityDimension(
      String dimension,
      String description,
      boolean available,
      String source,
      String ownerModule,
      String impact) {}

  public record ShipmentWritebackCapabilities(
      List<ShipmentWritebackCapabilityDimension> dimensions,
      List<String> statuses,
      List<String> verifications,
      List<String> groupByOptions,
      String idempotencyKeyBasis,
      String threeStatePolicy,
      String shopIdPolicy,
      String downstreamNote) {}

  // ================================================================ 输入

  /**
   * 建回传任务。
   *
   * <p><b>刻意不提供 {@code idempotencyKey} 入参</b>：蓝图把它列为本模块的输入，
   * 但「键由调用方自拟」恰好等于允许两次「不同键、同一次发货」，把幂等从根上绕开。
   * 键由服务端按 {@code ship-update:<recordId>:r<revision>} 构造
   * （见 V147 文件头第三节）。
   *
   * <p><b>{@code shopId} 必须由调用方给</b>：channel 的
   * {@code platform_operation.shop_id} 是 uuid 且带 FK，而本服务只有
   * {@code shop_code} 字符串；2.3 店铺目录在 channel 库里、跨服务读被禁，
   * 所以本模块<b>查不到</b>映射，只能由 UI 的店铺选择器带入。
   * 传错的后果是真实的：channel 侧 FK 顶回来，本模块落成 REJECTED + 真实错误。
   */
  public record ShipmentWritebackCreateInput(
      @NotBlank @Pattern(regexp = "^.{1,32}$", message = "平台代码最多 32 个字符")
          String platformCode,
      @NotNull UUID shopId,
      // null = 不指定 marketplace；channel 侧 marketplace_id 可空。
      @Schema(nullable = true) UUID marketplaceId,
      @Pattern(regexp = "^.{0,128}$", message = "平台订单号最多 128 个字符")
          String platformOrderRef,
      @NotEmpty List<UUID> recordIds,
      @Pattern(regexp = "^.{0,400}$", message = "备注最多 400 个字符")
          String note) {}

  /**
   * 人工修正 —— 改回传内容并重新发起。
   *
   * <p>刻意<b>不改</b>原任务：修正会<b>新建一版</b>（revision + 1、幂等键随之变化），
   * 原任务转 SUPERSEDED 留作证据。这是 V147 里那个「idempotency_key 不可就地改写」
   * 触发器的直接后果 —— 沿用旧键的话 channel 会把这次修正当重复静默丢弃，
   * 现场看到的是「我改了内容，平台还是老的」。
   */
  public record ShipmentWritebackCorrectionInput(
      // 全部可空：不改的字段留空即沿用发货记录的冻结值。
      @Schema(nullable = true)
          @Pattern(regexp = "^.{1,64}$", message = "承运商代码最多 64 个字符")
          String carrierCode,
      @Schema(nullable = true)
          @Pattern(regexp = "^.{1,64}$", message = "主运单号最多 64 个字符")
          String masterWaybillNo,
      @Schema(nullable = true) OffsetDateTime shippedAt,
      @Schema(nullable = true)
          @Pattern(regexp = "^.{0,128}$", message = "平台订单号最多 128 个字符")
          String platformOrderRef,
      @Schema(nullable = true) List<@Valid ParcelWaybillInput> parcels,
      @NotBlank @Pattern(regexp = "^.{4,400}$", message = "修正原因至少 4 个字符")
          String reason) {}

  /** 逐箱运单号。留空表示沿用发货记录里的逐箱号（不是「清空」）。 */
  public record ParcelWaybillInput(
      @NotNull @Positive Integer parcelNo, @NotBlank String waybillNo) {}

  /** 提交投递（入 2 队列 + 标记开始发送）。 */
  public record ShipmentWritebackSubmitInput(
      @Pattern(regexp = "^.{0,400}$", message = "备注最多 400 个字符") String note) {}

  /**
   * 登记平台侧结果 —— <b>三态在此分开</b>。
   *
   * <p>刻意只允许 CONFIRMED / REJECTED / PENDING_VERIFICATION 三个值：
   * 「待回传」不是平台结果（那是建任务时的态），「决定不回传」也不该从这条路径进来
   * （它是人为决定，不是平台答复），否则两个语义挤在一个入口，事后无法分辨
   * 「平台说不行」与「我们决定不发」。
   */
  public record ShipmentWritebackOutcomeInput(
      @NotNull WritebackStatus status,
      @Pattern(regexp = "^.{0,128}$", message = "平台请求号最多 128 个字符")
          String platformRequestId,
      @Pattern(regexp = "^.{0,800}$", message = "错误说明最多 800 个字符")
          String errorMessage,
      @Pattern(regexp = "^.{0,400}$", message = "备注最多 400 个字符")
          String note) {}

  /**
   * 查证结论 —— 未知态<b>必须</b>先走这条路径。
   *
   * <p>人工查证必须写依据（DB CHECK 强制）：「人工确认平台已收到」这句话
   * 没有主体与凭据时，等于没人说的话。
   */
  public record ShipmentWritebackVerifyInput(
      @NotNull WritebackVerificationConclusion conclusion,
      @NotNull WritebackVerificationSource source,
      @Pattern(regexp = "^.{0,800}$", message = "查证依据最多 800 个字符") String evidence) {}

  /** 单条重试。 */
  public record ShipmentWritebackRetryInput(
      @Pattern(regexp = "^.{0,400}$", message = "备注最多 400 个字符") String note) {}

  /**
   * 批量重试。
   *
   * <p>刻意带 {@code limit} 上限：一次「全量重试」在大促期会把几百条任务
   * 同时压向平台，而平台侧限流后全部变成结果未知 —— 那正是本模块要处理的最坏情形。
   */
  public record ShipmentWritebackBatchRetryInput(
      @Schema(nullable = true)
          @Pattern(regexp = "^.{1,32}$", message = "平台代码最多 32 个字符")
          String platformCode,
      @Schema(nullable = true)
          @Pattern(regexp = "^.{1,64}$", message = "承运商代码最多 64 个字符")
          String carrierCode,
      @NotNull @Positive Integer limit,
      @Pattern(regexp = "^.{0,400}$", message = "备注最多 400 个字符")
          String note) {}

  // ================================================================ 输出

  /** 建任务结果：建了几条、命中幂等复用几条、跳过几条（以及为什么跳过）。 */
  public record ShipmentWritebackCreateResult(
      int createdTasks,
      int reusedTasks,
      int skippedRecords,
      int unresolvedRecords,
      List<String> notes,
      OffsetDateTime createdAt) {}

  /** 批量重试结果：真正重投了几条，被闸门拦下几条（逐条说清拦在哪一道）。 */
  public record ShipmentWritebackBatchRetryResult(
      int submitted,
      int rescheduled,
      int blockedUnknown,
      int blockedAtCap,
      int blockedBackoff,
      int blockedTerminal,
      List<ShipmentWritebackTaskRow> retried,
      List<String> blocked,
      OffsetDateTime executedAt) {}

  public record ShipmentWritebackTaskRow(
      UUID id,
      String taskNo,
      UUID sourceRecordId,
      String sourceRecordNo,
      String salesOrderNo,
      String platformCode,
      String shopCode,
      UUID shopId,
      String platformOrderRef,
      String carrierCode,
      String masterWaybillNo,
      OffsetDateTime shippedAt,
      String idempotencyKey,
      WritebackStatus status,
      String statusLabel,
      WritebackVerification verification,
      String verificationLabel,
      OffsetDateTime verifiedAt,
      int attemptCount,
      int maxAttempts,
      OffsetDateTime nextAttemptAt,
      String lastError,
      UUID channelOperationId,
      String platformRequestId,
      int revision,
      UUID correctionOf,
      BigDecimal parcelCount,
      boolean resendAllowed,
      String blockedReason,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  public record ShipmentWritebackAttemptRow(
      UUID id,
      int attemptNo,
      String idempotencyKey,
      UUID channelOperationId,
      String platformRequestId,
      WritebackAttemptOutcome outcome,
      String outcomeLabel,
      String responseRef,
      String errorMessage,
      OffsetDateTime nextAttemptAt,
      OffsetDateTime startedAt,
      OffsetDateTime finishedAt) {}

  public record ShipmentWritebackVerificationRow(
      UUID id,
      int attemptNo,
      WritebackVerificationConclusion conclusion,
      String conclusionLabel,
      WritebackVerificationSource source,
      String sourceLabel,
      String evidence,
      OffsetDateTime queriedAt) {}

  public record ShipmentWritebackEventRow(
      UUID id,
      String eventKind,
      String fromStatus,
      String toStatus,
      String detail,
      Integer attemptNo,
      OffsetDateTime occurredAt) {}

  /** 汇总<b>不受分页影响</b>（与 9.4.1.1 / 9.4.1.2 / 9.6.1.2 同一纪律）。 */
  public record ShipmentWritebackSummary(
      long totalTasks,
      long pendingTasks,
      long submittedTasks,
      long confirmedTasks,
      long rejectedTasks,
      long unknownTasks,
      long skippedTasks,
      long supersededTasks,
      long dueTasks,
      long unverifiedUnknownTasks,
      long resendAllowedTasks,
      long atCapTasks,
      int totalAttempts) {}

  public record ShipmentWritebackBoard(
      String groupBy, List<ShipmentWritebackGroup> groups, ShipmentWritebackSummary summary,
      List<String> boundaryNotes) {}

  public record ShipmentWritebackGroup(
      String groupKey, String groupLabel, long taskCount, long unknownCount, long rejectedCount) {}

  public record ShipmentWritebackTaskDetail(
      ShipmentWritebackTaskRow task,
      String payloadJson,
      List<ShipmentWritebackAttemptRow> attempts,
      List<ShipmentWritebackVerificationRow> verifications,
      List<ShipmentWritebackEventRow> timeline,
      List<String> boundaryNotes) {}

  public record ShipmentWritebackTaskPage(
      List<ShipmentWritebackTaskRow> items,
      int page,
      int size,
      long totalElements,
      int totalPages,
      ShipmentWritebackSummary summary) {}

  /** 事件流读取：真分页 + 真筛选。 */
  public record ShipmentWritebackEventPage(
      List<ShipmentWritebackEventRow> items, int page, int size, long totalElements, int totalPages) {}
}
