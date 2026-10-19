package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 4.1.1.3 生命周期与下架 的传输模型。
 *
 * <p>蓝图：「管理 listing 状态：在售 / 草稿 / 平台审核中 / 平台驳回 / 主动下架 / 平台下架 /
 * 被封禁。支持平台侧状态变更被动同步，以及主动下架时选择是否回写平台。输出：状态变更记录
 * （来源：平台 or 人工）+ 可选的平台操作任务。关系：上游 = 02 同步、4.3.2.2；下游 = 02 回写队列（5.1.1）。
 * <b>平台下架与内部下架是两个事实</b>，分别记录并展示差异 —— 平台把商品下架了而内部不知情，
 * 是最常见的漏单原因。」
 *
 * <p>🔴 模型层把四个不变量钉死（每个都有集成测试逐条钉住）：
 *
 * <ol>
 *   <li><b>七态是封闭取值域</b>：{@link #LIFECYCLE_STATUSES} 与 V171 的
 *       {@code listing.status} 的 CHECK 逐字相同。拼错一个词就是平台侧的一个新状态，
 *       猜一个最像的去执行比报错危险得多。
 *   <li><b>来源只有两种</b>：{@link #ACTION_SOURCES} = MANUAL / PLATFORM —— 这就是
 *       「平台下架与内部下架是两个事实」在模型上的落点，页面上每条事件都必须显式标注。
 *   <li><b>「是否回写平台」是请求里的一等公民</b>：{@link LifecycleActionRequest#writebackToPlatform}
 *       —— 它决定这次主动下架会不会产生一个平台操作任务。不给就默认**不回写**（保守默认：
 *       误发一个「下架」到平台是**有资损风险**的动作，而漏发只是内部与平台暂时不一致）。
 *   <li><b>非法迁移不长得像成功</b>：状态机的拒绝理由由服务端给出（{@code reason}），
 *       前端原样显示，<b>不自己猜</b>该允许什么。
 * </ol>
 *
 * <p>载荷一律用具名 {@code record}：Map 的值类型会被 springdoc 推成 object，codegen 出来是
 * 空对象 {@code {}}（9.6.1.3 已踩过）。
 */
public final class ListingLifecycleModels {
  private ListingLifecycleModels() {}

  /**
   * 🔴 七态生命周期 —— 与 V171 的 {@code listing.status} CHECK 逐字相同。
   *
   * <p>顺序就是蓝图的列举顺序：在售 / 草稿 / 平台审核中 / 平台驳回 / 主动下架 / 平台下架 / 被封禁。
   * 这里按「草稿 → 在售 → 审核中 → 驳回 → 下架 → 封禁」的经营顺序排，页面按这个顺序展示。
   */
  public static final List<String> LIFECYCLE_STATUSES =
      List.of(
          "DRAFT",
          "ON_SALE",
          "UNDER_REVIEW",
          "REJECTED",
          "DELISTED_BY_US",
          "DELISTED_BY_PLATFORM",
          "BLOCKED");

  /** 来源：MANUAL = 内部人工决定；PLATFORM = 平台侧权威状态的被动同步。 */
  public static final List<String> ACTION_SOURCES = List.of("MANUAL", "PLATFORM");

  /**
   * 人工动作的四种 + 平台联动一种。
   *
   * <p>「主动下架 / 重新上架」是蓝图点名的两个用户动作（"用户下架/上架操作"）；
   * 「提交平台审核」「标记被封禁」是为了让七态**每一个**都可达 —— 否则「平台审核中」
   * 与「被封禁」两态永远只能由平台侧写入，内部运营反而没有合法路径把它们记下来。
   */
  public static final List<String> ACTIONS =
      List.of(
          "DELIST",
          "REPUBLISH",
          "SUBMIT_REVIEW",
          "MARK_BLOCKED",
          "APPLY_PLATFORM_STATUS");

  /**
   * 平台状态事件类型 —— **直接复用 4.3.2.2 的取值域**（{@code ListingStatusSyncModels.EVENT_TYPES}）。
   *
   * <p>不复制的理由：平台事件的类型是**一个**封闭词表，两处各写一份必然随版本漂移，
   * 而「平台说的 DELISTED 和我们理解的 DELISTED 是不是一回事」错一次就是漏单。
   */
  public static final List<String> PLATFORM_EVENT_TYPES = ListingStatusSyncModels.EVENT_TYPES;

  /** 平台事件进来的通道（与 4.3.2.2 同一取值域）。 */
  public static final List<String> INGEST_CHANNELS = ListingStatusSyncModels.INGEST_CHANNELS;

  // ================================================================ 能力声明

  public record ListingLifecycleCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  public record ListingLifecycleCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false") List<ListingLifecycleCapabilityDimension> dimensions,
      @Schema(description = "七态取值域") List<String> lifecycleStatuses,
      @Schema(description = "事件来源取值域：MANUAL / PLATFORM") List<String> actionSources,
      @Schema(description = "动作类型取值域") List<String> actions,
      @Schema(description = "平台事件类型取值域（复用 4.3.2.2）") List<String> platformEventTypes,
      @Schema(description = "平台事件通道取值域") List<String> ingestChannels,
      @Schema(description = "🔴 状态机规则（服务端算，前端原样显示）") String stateMachineRule,
      @Schema(description = "🔴 「平台下架 vs 内部下架」的判定口径") String divergenceRule,
      @Schema(description = "上游 / 下游说明") String relationNote) {}

  // ================================================================ 列表

  /**
   * 生命周期列表的一行 —— 一个 listing 一行。
   *
   * <p>🔴 {@code divergenceKind} / {@code divergenceNote} 是**服务端算好的结论**：
   * 前端原样显示，不重算。判定口径（双方对「是否在售」的主张是否相反）是一套业务规则，
   * 前端再实现一遍必然随版本漂移，而「哪一边说了算」错一次就是运营误操作。
   */
  public record ListingLifecycleRow(
      @Schema(description = "listing id") UUID listingId,
      @Schema(description = "平台商品 ID 原文") String externalItemId,
      @Schema(description = "商品标题") String title,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "店铺名") String shopName,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "🔴 当前内部状态（七态）") String status,
      @Schema(description = "🔴 最近一次状态变更的来源：MANUAL / PLATFORM") String lastChangeSource,
      @Schema(description = "最近一次状态变更的时刻") OffsetDateTime lastChangeAt,
      @Schema(description = "最近一次状态变更的起始状态") String lastChangeFrom,
      @Schema(description = "最近一次状态变更的目标状态") String lastChangeTo,
      @Schema(description = "平台侧最新权威状态原文（没有平台事件时为空串）") String platformStatus,
      @Schema(description = "平台侧最新事件类型（没有平台事件时为空串）") String platformEventType,
      @Schema(description = "平台侧最新事件的时刻") OffsetDateTime platformEventAt,
      @Schema(description = "🔴 是否存在平台侧与内部侧的差异") boolean divergence,
      @Schema(description = "🔴 差异类型（无差异时为空串）") String divergenceKind,
      @Schema(description = "🔴 差异说明（逐字写出两边状态）") String divergenceNote,
      @Schema(description = "🔴 当前状态下合法的人工动作（服务端算，页面据此渲染按钮）")
          List<String> allowedActions,
      @Schema(description = "该商品的状态变更事件条数") int eventCount,
      @Schema(description = "最近更新时刻") OffsetDateTime updatedAt) {}

  // ================================================================ 单条视图

  /** 单个 listing 的生命周期视图：当前状态 + 平台侧事实 + 差异 + 动作留痕。 */
  public record ListingLifecycleDetail(
      @Schema(description = "当前状态与列表行同构") ListingLifecycleRow listing,
      @Schema(description = "🔴 状态变更时间线（occurred_at 倒序）") List<ListingLifecycleEventRow> timeline,
      @Schema(description = "🔴 人工动作留痕（谁 / 为什么 / 是否回写平台）") List<ListingLifecycleActionRow> actions,
      @Schema(description = "🔴 下一次合法动作（服务端算，前端按它渲染按钮）") List<String> allowedActions,
      @Schema(description = "🔴 状态机的完整规则说明") String stateMachineRule) {}

  // ================================================================ 状态变更事件

  /**
   * 一条状态变更事件。
   *
   * <p>🔴 {@code source} 是**这个模块最要紧的一列**：MANUAL = 内部人工改状态；
   * PLATFORM = 平台侧状态变更被动同步带来的内部状态变化。
   *
   * <p>🔴 {@code toStatus} 的两种语义（页面据此区分「内部状态变更」与「平台事件原文」）：
   * <ul>
   *   <li>{@code toStatus} 落在七态里 ⇒ 这是**内部状态变更**（fromStatus → toStatus 是内部七态之间的迁移）。
   *   <li>{@code toStatus} 不在七态里 ⇒ 这是**平台事件原文**（4.3.2.2 落的平台状态记录，
   *       toStatus 是平台自己的 token，如 DELISTED / SUPPRESSED）。
   * </ul>
   */
  public record ListingLifecycleEventRow(
      @Schema(description = "事件 id") UUID eventId,
      @Schema(description = "listing id") UUID listingId,
      @Schema(description = "平台商品 ID 原文") String externalItemId,
      @Schema(description = "商品标题") String title,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "🔴 起始状态") String fromStatus,
      @Schema(description = "🔴 目标状态（七态 ⇒ 内部状态变更；否则 ⇒ 平台事件原文）") String toStatus,
      @Schema(description = "🔴 来源：MANUAL 人工 / PLATFORM 平台") String source,
      @Schema(description = "事件类型：STATUS_CHANGE / APPROVED / REJECTED / DELISTED / …") String eventType,
      @Schema(description = "平台权威状态原文") String platformStatus,
      @Schema(description = "判定冲突时的内部状态快照") String internalStatus,
      @Schema(description = "🔴 是否与内部状态冲突（服务端判定）") boolean conflictDetected,
      @Schema(description = "冲突说明") String conflictNote,
      @Schema(description = "处置口径：PLATFORM_WINS / NOT_REQUIRED") String resolution,
      @Schema(description = "驳回原因（平台原文）") String rejectionReason,
      @Schema(description = "备注") String note,
      @Schema(description = "🔴 这条事件是「内部状态变更」还是「平台事件原文」（服务端判定）")
          boolean internalStateChange,
      @Schema(description = "🔴 发生时刻（平台事件是平台那边发生的时刻）") OffsetDateTime occurredAt,
      @Schema(description = "入库时刻") OffsetDateTime receivedAt) {}

  // ================================================================ 动作留痕

  /** 一次人工动作（或一次平台联动）的意图留痕。 */
  public record ListingLifecycleActionRow(
      @Schema(description = "动作 id") UUID actionId,
      @Schema(description = "listing id") UUID listingId,
      @Schema(description = "🔴 动作类型") String action,
      @Schema(description = "动作前的内部状态") String fromStatus,
      @Schema(description = "动作后的内部状态") String toStatus,
      @Schema(description = "🔴 来源：MANUAL / PLATFORM") String source,
      @Schema(description = "平台事件类型（平台联动才有）") String platformEventType,
      @Schema(description = "平台权威状态原文（平台联动才有）") String platformStatus,
      @Schema(description = "驳回原因") String rejectionReason,
      @Schema(description = "人工动因") String reason,
      @Schema(description = "🔴 这次动作是否选择了回写平台") boolean writebackRequested,
      @Schema(description = "🔴 平台操作任务批次 id（未回写为 null）") UUID writebackBatchId,
      @Schema(description = "🔴 平台操作任务批次号（未回写为空串）") String writebackBatchNo,
      @Schema(description = "平台操作任务条目 id（未回写为 null）") UUID writebackItemId,
      @Schema(description = "🔴 平台队列任务 id（未回写为 null）") UUID operationId,
      @Schema(description = "响应的那条平台事件 id（平台联动才有）") UUID platformEventId,
      @Schema(description = "操作人") UUID actorId,
      @Schema(description = "动作时刻") OffsetDateTime createdAt) {}

  // ================================================================ 差异区

  /**
   * 一条差异 —— 「平台下架 vs 内部下架」的对照。
   *
   * <p>🔴 三种差异类型（服务端判定，前端原样显示）：
   *
   * <ul>
   *   <li>{@code PLATFORM_DELISTED_INTERNAL_ON_SALE} —— 平台已下架、内部还在售。
   *       **最常见的漏单原因**：平台把商品拿下了，内部还以为在卖。
   *   <li>{@code INTERNAL_DELISTED_PLATFORM_ON_SALE} —— 内部已主动下架，平台侧仍在售
   *       （平台最后一条事件说的是「在售」）。内部与平台对同一件商品的说法相反。
   *   <li>{@code INTERNAL_DELISTED_NOT_WRITEBACK} —— 内部已主动下架，但那次下架**明确没有**
   *       选择回写平台 ⇒ 平台侧不知情，平台可能还在卖。
   *   <li>{@code INTERNAL_DELISTED_NO_WRITEBACK_RECORD} —— 内部已主动下架，但**找不到**任何
   *       下架动作留痕（多半走的是 4.1.1.1 的批量下架，那条路径不回写平台）。
   * </ul>
   */
  public record ListingLifecycleDivergenceRow(
      @Schema(description = "listing id") UUID listingId,
      @Schema(description = "平台商品 ID 原文") String externalItemId,
      @Schema(description = "商品标题") String title,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "🔴 内部状态") String internalStatus,
      @Schema(description = "🔴 平台侧状态原文") String platformStatus,
      @Schema(description = "🔴 平台侧事件类型") String platformEventType,
      @Schema(description = "🔴 差异类型") String divergenceKind,
      @Schema(description = "🔴 差异说明（逐字写出两边状态）") String divergenceNote,
      @Schema(description = "🔴 严重程度：HIGH 漏单风险 / MEDIUM 需跟进") String severity,
      @Schema(description = "内部状态最近一次变化时刻") OffsetDateTime internalChangedAt,
      @Schema(description = "平台侧最近一条事件的时刻") OffsetDateTime platformChangedAt) {}

  // ================================================================ 请求

  /**
   * 🔴 人工动作请求。
   *
   * <p>{@code writebackToPlatform} 只对 {@code DELIST} 有意义（主动下架）：
   * 给 true ⇒ 这次下架会产生一个平台操作任务（复用 02 的 {@code writeback_batch}，kind='DELIST'）；
   * 不给或给 false ⇒ **只改内部状态，平台侧不知情**。
   *
   * <p>🔴 重新上架**不提供**回写选项：02 回写队列的 {@code writeback_batch.kind} 值域里
   * 没有「重新上架」（既有 CHECK 不能动），平台侧的上架动作归 4.3.1.2 批量发布 /
   * 4.3.2.2 一键修正重发。宁可少给一个必然 500 的选项，也不给。
   */
  public record LifecycleActionRequest(
      @Schema(description = "🔴 动作类型：DELIST 主动下架 / REPUBLISH 重新上架 / "
          + "SUBMIT_REVIEW 提交平台审核 / MARK_BLOCKED 标记被封禁")
          @NotBlank @Size(max = 32) String action,
      @Schema(description = "🔴 主动下架时是否回写平台（仅 DELIST 有效；默认 false = 只改内部状态）")
          Boolean writebackToPlatform,
      @Schema(description = "动因（为什么下架 / 为什么重新上架）") @Size(max = 500) String reason) {}

  /**
   * 🔴 平台状态回流事件 —— 平台侧状态变更被动同步的写入入口。
   *
   * <p>两条用法（不冲突）：
   *
   * <ul>
   *   <li>{@code platformEventId} 给了 ⇒ 用 4.3.2.2 已经落库的那条平台事件作为事实来源，
   *       body 里的 eventType / platformStatus / rejectionReason 会被**忽略**（以库里的为准）。
   *   <li>不给 ⇒ 用 body 里的字段作为平台事实（平台适配器直接推过来时的用法）。
   * </ul>
   */
  public record PlatformStatusRequest(
      @Schema(description = "🔴 4.3.2.2 已落库的平台事件 id（给了就以它为准）") UUID platformEventId,
      @Schema(description = "平台事件类型：APPROVED / REJECTED / DELISTED / THROTTLED / "
          + "SUPPRESSED / CATEGORY_REVIEW / PRICE_ALERT") @Size(max = 32) String eventType,
      @Schema(description = "🔴 平台的权威状态原文（平台自己的 token，原样存不折叠大小写）")
          @Size(max = 64) String platformStatus,
      @Schema(description = "驳回原因（平台原文；REJECTED 必填）") @Size(max = 300) String rejectionReason,
      @Schema(description = "事件进来的通道：WEBHOOK / POLL / API") @Size(max = 16) String ingestChannel,
      @Schema(description = "平台事件发生时刻（不给 = 入库时刻）") OffsetDateTime occurredAt,
      @Schema(description = "备注（进留痕）") @Size(max = 500) String note) {}

  // ================================================================ 回执

  /**
   * 一次人工动作的**回执**。
   *
   * <p>🔴 {@code writebackBatchId} / {@code operationId} 是「真的产生了平台任务」的凭据
   * （结构事实，不是文案）。没选择回写时它们是 null —— 页面据此显示「只改了内部状态，
   * 平台侧不知情」，**不显示**一个必然失败的「已下发平台」按钮。
   */
  public record LifecycleActionReceipt(
      @Schema(description = "🔴 动作 id") UUID actionId,
      @Schema(description = "listing id") UUID listingId,
      @Schema(description = "动作类型") String action,
      @Schema(description = "动作前的状态") String fromStatus,
      @Schema(description = "🔴 动作后的状态") String toStatus,
      @Schema(description = "来源：MANUAL / PLATFORM") String source,
      @Schema(description = "🔴 这次动作是否回写了平台") boolean writebackRequested,
      @Schema(description = "🔴 平台操作任务批次 id（未回写为 null）") UUID writebackBatchId,
      @Schema(description = "🔴 平台操作任务批次号（未回写为空串）") String writebackBatchNo,
      @Schema(description = "🔴 平台队列任务 id（未回写为 null）") UUID operationId,
      @Schema(description = "🔴 落库的状态变更事件 id（由 DB 触发器落，服务层不直接 INSERT）")
          UUID statusEventId,
      @Schema(description = "说明") String note) {}

  // ================================================================ 分页 / 词表

  /**
   * 🔴 批量同步的回执（把还没联动过的平台事件应用到内部状态）。
   *
   * <p>🔴 {@code applied} 与 {@code skipped} **都逐条写明**：
   * 跳过的写明**为什么**没联动（内部状态已经一样 / 受限类事件不改变内部状态 / 商品已不在
   * 数据范围内）。静默跳过会让运营以为「全同步了」。
   */
  public record LifecycleSyncReceipt(
      @Schema(description = "🔴 真的联动了几条") int appliedCount,
      @Schema(description = "🔴 未联动几条") int skippedCount,
      @Schema(description = "🔴 逐条回执（真的联动的）") List<String> applied,
      @Schema(description = "🔴 被跳过的条目 + 原因") List<String> skipped,
      @Schema(description = "说明") String note) {}

  /** 一页生命周期行。 */
  public record ListingLifecyclePage(
      @Schema(description = "本页数据") List<ListingLifecycleRow> items,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "符合条件的总条数") long total) {}

  /** 一页状态变更事件。 */
  public record ListingLifecycleEventPage(
      @Schema(description = "本页数据") List<ListingLifecycleEventRow> items,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "符合条件的总条数") long total) {}

  /** 一页差异。 */
  public record ListingLifecycleDivergencePage(
      @Schema(description = "本页数据") List<ListingLifecycleDivergenceRow> items,
      @Schema(description = "页码（从 0 起）") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "符合条件的总条数") long total) {}

  /**
   * 筛选面板词表 —— **不硬编码在前端**。
   *
   * <p>七态、来源、动作类型、平台事件类型全部由服务端下发：前端硬编码一份必然随版本漂移，
   * 而「界面上能选的状态」与「后端认的状态」不一致时，选了就是 422。
   */
  public record ListingLifecycleFilterOptions(
      @Schema(description = "七态取值域") List<String> lifecycleStatuses,
      @Schema(description = "来源取值域") List<String> actionSources,
      @Schema(description = "动作类型取值域") List<String> actions,
      @Schema(description = "平台事件类型取值域") List<String> platformEventTypes,
      @Schema(description = "平台事件通道取值域") List<String> ingestChannels,
      @Schema(description = "差异类型取值域") List<String> divergenceKinds) {}
}
