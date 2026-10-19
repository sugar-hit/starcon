package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 4.3.2.2 平台状态回流 的传输模型。
 *
 * <p>蓝图：「接收并展示平台侧的商品状态变化：审核通过/驳回、下架、限流、被抑制（suppressed）、类目审核、价格异常通知，并支持一键处理（如按驳回原因批量修正重发）。输入：平台事件/webhook；轮询补充。输出：状态变更记录 + 需处理队列。关系：上游 = 02 同步；下游 = 4.1.1.3、4.3.1.3。<b>回流是平台状态的权威来源</b>，内部状态与之冲突时以平台为准并告警。」
 *
 * <p>🔴 模型层把三个不变量钉死（每个都有集成测试逐条钉住）：
 *
 * <ol>
 *   <li><b>冲突判定是后端算的</b>：{@link ListingStatusEventRow} 上的 {@code conflictDetected} /
 *       {@code internalStatus} / {@code platformStatus} / {@code resolution} 是**服务端算好的结论**，
 *       前端**原样显示**，不重算。理由：判定口径（双方对「是否在售」的主张是否相反）是一套
 *       业务规则，让前端再实现一遍必然随版本漂移，而"哪一边说了算"错一次就是运营误操作。
 *   <li><b>没有"改内部状态"的入口</b>：{@link IngestStatusEventRequest} 里**没有任何**能改
 *       {@code listing.status} 的字段。内部生命周期联动归 4.1.1.3（未交付），
 *       本模块**只记录、不改写**内部状态 —— 改了就是抢另一个模块的写入所有权。
 *   <li><b>「一键处理」只有一个形状</b>：{@link RepublishRequest} 走 2.5.1.1 的
 *       {@code platform_operation} 既有队列（幂等键唯一），**不新建平行任务表**。
 * </ol>
 *
 * <p>载荷一律用具名 {@code record}：Map 的值类型会被 springdoc 推成 object，codegen 出来是空对象
 * {@code {}}（9.6.1.3 已踩过）。{@code detail} 用 {@code Map<String,String>} 是**刻意**的：
 * 平台事件的原文结构按文本原样存，不解释不加工。
 */
public final class ListingStatusSyncModels {
  private ListingStatusSyncModels() {}

  /**
   * 🔴 平台状态事件的七种类型 —— 职责句逐词对应。
   *
   * <p>审核通过 / 审核驳回 / 下架 / 限流 / 被抑制（suppressed）/ 类目审核 / 价格异常通知。
   * 每一类在「是否在售」这个维度上的归属不同（见服务层的口径说明），所以取值域是封闭的：
   * 拼错一个词就是平台侧的一个新状态，猜一个最像的去执行比报错危险得多。
   */
  public static final List<String> EVENT_TYPES =
      List.of(
          "APPROVED",
          "REJECTED",
          "DELISTED",
          "THROTTLED",
          "SUPPRESSED",
          "CATEGORY_REVIEW",
          "PRICE_ALERT");

  /**
   * 平台事件进来的通道：WEBHOOK 平台推送 / POLL 轮询补充 / API 显式录入。
   *
   * <p>蓝图「输入：平台事件/webhook；轮询补充」逐词对应。真实平台推送的适配不在本模块
   * （见能力表 {@code real_platform_webhook}），但**事件从哪条通道进来必须记下来** ——
   * 「同步延迟」这件事只有在知道通道之后才答得出来。
   */
  public static final List<String> INGEST_CHANNELS = List.of("WEBHOOK", "POLL", "API");

  /** 需处理队列的三种项：可一键修正重发 / 需人工处理 / 冲突告警。 */
  public static final List<String> QUEUE_KINDS = List.of("REPUBLISH", "REVIEW", "CONFLICT_ALERT");

  /** 队列项状态：待处理 / 已确认 / 已发起（在途）/ 已处理完 / 已作废。 */
  public static final List<String> QUEUE_STATES =
      List.of("OPEN", "ACKNOWLEDGED", "DISPATCHED", "RESOLVED", "CANCELLED");

  // ================================================================ 能力声明

  public record ListingStatusCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  public record ListingStatusCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false") List<ListingStatusCapabilityDimension> dimensions,
      @Schema(description = "平台状态事件类型取值域") List<String> eventTypes,
      @Schema(description = "事件进来的通道") List<String> ingestChannels,
      @Schema(description = "需处理队列的项类型") List<String> queueKinds,
      @Schema(description = "队列项状态取值") List<String> queueStates,
      @Schema(description = "🔴 冲突判定口径（服务端算，前端原样显示，不要自己重算）") String conflictRule,
      @Schema(description = "「以平台为准」的处置口径说明") String authorityNote,
      @Schema(description = "上游 / 下游说明") String relationNote) {}

  // ================================================================ 回流入库

  /**
   * 接收一条平台状态事件。
   *
   * <p>🔴 模型层<b>没有</b>能改内部生命周期状态的字段：本模块只**记录**平台说了什么，
   * 内部 {@code listing.status} 的联动归 4.1.1.3（未交付）。
   */
  public record IngestStatusEventRequest(
      @Schema(description = "listing id（给 listingId 或 externalItemId+shopCode 其一）") UUID listingId,
      @Schema(description = "店铺编码（与 externalItemId 配对定位商品）") @Size(max = 64) String shopCode,
      @Schema(description = "平台商品 ID 原文（与 shopCode 配对定位商品）") @Size(max = 128) String externalItemId,
      @Schema(description = "🔴 事件类型：APPROVED / REJECTED / DELISTED / THROTTLED / SUPPRESSED / "
          + "CATEGORY_REVIEW / PRICE_ALERT") @NotBlank @Size(max = 32) String eventType,
      @Schema(description = "🔴 平台的权威状态原文（平台自己的 token，原样存不折叠大小写）")
          @NotBlank @Size(max = 64) String platformStatus,
      @Schema(description = "驳回原因（平台给的原文；REJECTED 必填）") @Size(max = 300) String rejectionReason,
      @Schema(description = "事件进来的通道：WEBHOOK / POLL / API（不给 = API）") @Size(max = 16)
          String ingestChannel,
      @Schema(description = "平台事件发生时刻（不给 = 入库时刻）") OffsetDateTime occurredAt,
      @Schema(description = "平台事件原文结构（原样存，不解释不加工）") Map<String, String> detail,
      @Schema(description = "备注（进留痕）") @Size(max = 500) String note) {}

  // ================================================================ 状态变更记录

  /**
   * 一条状态变更记录。
   *
   * <p>🔴 {@code conflictDetected} / {@code internalStatus} / {@code platformStatus} /
   * {@code resolution} 四个字段是**服务端算好的结论**，前端原样显示。
   */
  public record ListingStatusEventRow(
      @Schema(description = "事件 id") UUID eventId,
      @Schema(description = "listing id") UUID listingId,
      @Schema(description = "平台商品 ID 原文") String externalItemId,
      @Schema(description = "商品标题") String title,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "🔴 事件类型") String eventType,
      @Schema(description = "来源：PLATFORM 平台回流 / MANUAL 内部人工改状态") String source,
      @Schema(description = "进来的通道：WEBHOOK / POLL / API / MANUAL") String ingestChannel,
      @Schema(description = "上一个平台状态（没有平台前序事件时为空串）") String fromStatus,
      @Schema(description = "本次的平台权威状态") String toStatus,
      @Schema(description = "🔴 平台状态原文（与 toStatus 同值，单独给出便于按平台口径展示）")
          String platformStatus,
      @Schema(description = "🔴 判定冲突时的内部状态快照（不冲突时为空串）") String internalStatus,
      @Schema(description = "🔴 是否与内部状态冲突（服务端判定）") boolean conflictDetected,
      @Schema(description = "🔴 冲突说明（内部状态与平台状态逐字）") String conflictNote,
      @Schema(description = "🔴 处置口径：PLATFORM_WINS 以平台为准 / NOT_REQUIRED 无冲突")
          String resolution,
      @Schema(description = "驳回原因（平台原文；非驳回事件为空串）") String rejectionReason,
      @Schema(description = "平台事件原文（JSON 文本）") String detail,
      @Schema(description = "备注") String note,
      @Schema(description = "平台事件发生时刻") OffsetDateTime occurredAt,
      @Schema(description = "入库时刻（与发生时刻的差值 = 同步延迟）") OffsetDateTime receivedAt) {}

  // ================================================================ 需处理队列

  /**
   * 一条需处理队列项（也承载冲突告警行）。
   *
   * <p>🔴 {@code operationStatus} / {@code operationVerification} 直接读 2.5.1.1 的
   * {@code platform_operation}：在途（{@code PENDING} / {@code SENDING}）与**结果未知**
   * （{@code status='UNKNOWN' 且 verification='STILL_UNKNOWN'}）是两件不同的事，
   * 页面上必须分开显示 —— 把"结果未知"显示成"已提交"就是在骗人。
   */
  public record ListingStatusQueueRow(
      @Schema(description = "队列项 id") UUID queueId,
      @Schema(description = "来源事件 id") UUID eventId,
      @Schema(description = "listing id") UUID listingId,
      @Schema(description = "平台商品 ID 原文") String externalItemId,
      @Schema(description = "商品标题") String title,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "事件类型") String eventType,
      @Schema(description = "REPUBLISH 可一键修正重发 / REVIEW 需人工处理 / CONFLICT_ALERT 冲突告警")
          String kind,
      @Schema(description = "OPEN / ACKNOWLEDGED / DISPATCHED / RESOLVED / CANCELLED") String state,
      @Schema(description = "驳回原因（平台原文；🔴 一键处理按它筛）") String rejectionReason,
      @Schema(description = "02 队列任务 id（V2 platform_operation）；未发起为 null") UUID operationId,
      @Schema(description = "幂等键（与 02 队列的 idempotency_key 同一口径）") String idempotencyKey,
      @Schema(description = "🔴 02 队列任务状态：PENDING / SENDING / SUCCEEDED / FAILED / UNKNOWN")
          String operationStatus,
      @Schema(description = "🔴 未知结果核验结论（2.5.1.2 口径）") String operationVerification,
      @Schema(description = "备注") String note,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "最近更新时刻") OffsetDateTime updatedAt,
      @Schema(description = "处理完成时刻；仅 RESOLVED 非空") OffsetDateTime completedAt) {}

  // ================================================================ 一键处理

  /**
   * 🔴 一键处理：按驳回原因批量修正重发。
   *
   * <p>三个筛选口径（给空 = 不按它筛）：{@code rejectionReason} 按驳回原因、
   * {@code eventTypes} 按事件类型、{@code queueItemIds} 显式点名。
   * 动作走 2.5.1.1 的 {@code platform_operation} 既有队列，幂等键由服务端从队列项 id 派生
   * （**不接受调用方自拟** —— 自拟就能两次「不同键、同一个目标」，恰好绕开幂等）。
   */
  public record RepublishRequest(
      @Schema(description = "🔴 按驳回原因筛（平台原文，逐字相等）") @Size(max = 300) String rejectionReason,
      @Schema(description = "按事件类型筛（不给 = 全部可重发类型）") List<String> eventTypes,
      @Schema(description = "显式点名的队列项 id（不给 = 按上面的条件筛）") List<UUID> queueItemIds,
      @Schema(description = "按店铺编码筛") @Size(max = 64) String shopCode,
      @Schema(description = "🔴 数量上限（1~500）") @Min(1) @Max(500) Integer limit,
      @Schema(description = "备注（进留痕）") @Size(max = 500) String note) {}

  /**
   * 一键处理的**回执**：逐条列出真的发起了什么。
   *
   * <p>🔴 {@code operationId} 是 2.5.1.1 的 {@code platform_operation} id ——
   * 「经队列」是可查事实，不是一句描述。
   */
  public record RepublishReceiptItem(
      @Schema(description = "队列项 id") UUID queueId,
      @Schema(description = "来源事件 id") UUID eventId,
      @Schema(description = "listing id") UUID listingId,
      @Schema(description = "平台商品 ID 原文") String externalItemId,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "事件类型") String eventType,
      @Schema(description = "驳回原因") String rejectionReason,
      @Schema(description = "🔴 02 队列任务 id") UUID operationId,
      @Schema(description = "幂等键") String idempotencyKey,
      @Schema(description = "🔴 02 队列任务状态") String operationStatus,
      @Schema(description = "🔴 未知结果核验结论") String operationVerification) {}

  /**
   * 一键处理的结果。
   *
   * <p>🔴 {@code rejected} 逐条写明"为什么没发"（已派发过 / 不属于本次筛选 / 平台状态未知），
   * **不静默丢弃** —— 静默丢弃会让运营以为全发出去了。
   */
  public record RepublishReceipt(
      @Schema(description = "本次筛中的队列项总数") int matchedCount,
      @Schema(description = "🔴 本次真的发起了多少条") int dispatchedCount,
      @Schema(description = "因已派发等原因未发起的条数") int skippedCount,
      @Schema(description = "🔴 逐条回执（真的发起的）") List<RepublishReceiptItem> dispatched,
      @Schema(description = "🔴 被跳过的条目 + 原因") List<String> rejected,
      @Schema(description = "说明") String note) {}

  // ================================================================ 人工动作

  /** 人工确认 / 处理完成的请求体（只有一条备注 —— 动作本身由端点形状决定）。 */
  public record StatusActionRequest(
      @Schema(description = "备注 / 处理说明（进留痕）") @Size(max = 500) String note) {}

  // ================================================================ 分页

  /**
   * 一页状态变更记录。
   *
   * <p>🔴 真分页：{@code total} 是服务端 count 出来的总条数，页面上能显示「第 X / 共 Y 页」；
   * 不做假分页（返回全量再让前端切片）—— 那会让"翻到第 50 页"这种动作永远加载不出东西。
   */
  public record ListingStatusEventPage(
      @Schema(description = "本页数据") List<ListingStatusEventRow> items,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "符合条件的总条数") long total) {}

  /** 一页需处理队列项。 */
  public record ListingStatusQueuePage(
      @Schema(description = "本页数据") List<ListingStatusQueueRow> items,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "符合条件的总条数") long total) {}
}
