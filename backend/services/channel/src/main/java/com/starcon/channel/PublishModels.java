package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 4.3.1.2 批量发布 的传输模型。
 *
 * <p>蓝图：「批量刊登队列：按规则筛选待发布商品（如'SPU 状态=在售 且 未在任何店铺发布'），生成任务批次，支持暂停、调整顺序、批量重试。输入：筛选规则与数量上限；发布策略；用户确认。输出：批次任务列表 + 总体进度 + 失败明细（可跳转 4.3.1.3）。关系：上游 = 4.1.1.1、4.2.1.2；下游 = 4.3.1.3。<b>批量任务必须限流</b>：按平台各自的 API 配额排队，否则会触发平台限流并可能影响店铺安全。」
 *
 * <p>载荷一律用具名 {@code record}：Map 的值类型会被 springdoc 推成 object，codegen 出来是空对象
 * {@code {}}（9.6.1.3 已踩过）。
 *
 * <p>🔴 模型层就把三条核心不变量钉死：
 *
 * <ol>
 *   <li><b>重排走的是 {@link ReorderRequest} 的显式 id 顺序</b>：{@code taskIds} 是调用者给的
 *       完整执行次序，服务层据此重写每条任务的 {@code position} —— 不是让前端传个新
 *       {@code position} 数值（那样能传出一个与真实次序不符的队列）。
 *   <li><b>批量重试没有"全量重跑"入口</b>：唯一的写路径 {@link RetryRequest} 不接受任何
 *       "连成功的也重跑"的参数形状 —— 成功的项在 DB 层由触发器保护。
 *   <li><b>限流配置是数据不是常量</b>：{@link PublishRateLimitRow} 是可查可配的资源，
 *       {@link DispatchResponse} 的 {@code skipped} 逐条写明"为什么这条没派出去"。
 * </ol>
 */
public final class PublishModels {
  private PublishModels() {}

  // ================================================================ 能力声明

  public record PublishCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  public record PublishCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false") List<PublishCapabilityDimension> dimensions,
      @Schema(description = "批次状态取值") List<String> batchStates,
      @Schema(description = "任务状态取值") List<String> taskStates,
      @Schema(description = "平台限流口径说明；前端原样展示，不要自己编") String rateLimitNote,
      @Schema(description = "失败明细跳转目标说明（4.3.1.3）") String downstreamNote,
      @Schema(description = "上游 / 下游说明") String relationNote) {}

  // ================================================================ 筛选与限流配置

  /**
   * 🔴 平台限流配置（按平台，可细到店铺+平台）。
   *
   * <p>{@code shopId=null} 是**平台级**默认配额；给了店铺 id 就是该店铺单独的一条。
   * 这些数字是**数据**（可配置、可查），服务层没有一个写死的全局默认值兜底 ——
   * 缺配置的平台上**一条都不派发**。
   */
  public record PublishRateLimitRow(
      @Schema(description = "限流配置 id") UUID id,
      @Schema(description = "平台 id") UUID platformId,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "平台名") String platformName,
      @Schema(description = "店铺 id；null 表示平台级默认配额") UUID shopId,
      @Schema(description = "店铺编码；平台级为 null") String shopCode,
      @Schema(description = "🔴 在途并发上限：该限流行同一时刻允许多少条在途") int maxInFlight,
      @Schema(description = "🔴 滑动窗口派发上限") int requestsPerWindow,
      @Schema(description = "🔴 滑动窗口长度（秒）") int windowSeconds,
      @Schema(description = "是否启用；停用等价于该行不存在（不派发）") boolean enabled,
      @Schema(description = "🔴 当前在途数（DISPATCHED，窗口不限）") long currentInFlight,
      @Schema(description = "🔴 当前窗口内已派发次数") long currentWindowCount,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  public record PublishRateLimitRequest(
      @Schema(description = "平台 id（channel V1 全局码表）") @NotNull UUID platformId,
      @Schema(description = "店铺 id；不给 = 平台级默认配额，给了 = 该店铺单独一条") UUID shopId,
      @Schema(description = "🔴 在途并发上限（1~500）") @Min(1) @Max(500) Integer maxInFlight,
      @Schema(description = "🔴 滑动窗口派发上限（1~10000）") @Min(1) @Max(10000) Integer requestsPerWindow,
      @Schema(description = "🔴 滑动窗口长度秒（1~86400）") @Min(1) @Max(86400) Integer windowSeconds,
      @Schema(description = "是否启用") Boolean enabled) {}

  // ================================================================ 建批次

  /**
   * 建批次的输入（筛选规则 + 数量上限 + 发布策略 + 用户确认）。
   *
   * <p>筛选参数的形状**照抄 4.1.1.1 Listing 列表**（店铺 / 站点 / 状态 / 负责人 / 类目 / 关键字），
   * 再加本模块专属的 {@code hasPendingDraft}（只发有内容草稿的）与 {@code excludeAlreadyPublished}。
   */
  public record CreatePublishBatchRequest(
      @Schema(description = "店铺编码筛选（照抄 4.1.1.1）") @Size(max = 64) String shopCode,
      @Schema(description = "站点编码筛选（照抄 4.1.1.1）") @Size(max = 32) String marketplaceCode,
      @Schema(description = "listing 状态筛选（照抄 4.1.1.1 的七态）") @Size(max = 32) String status,
      @Schema(description = "负责人筛选（照抄 4.1.1.1）") UUID ownerId,
      @Schema(description = "类目路径模糊筛选（照抄 4.1.1.1）") @Size(max = 500) String category,
      @Schema(description = "标题 / 平台商品 ID 关键字（照抄 4.1.1.1）") @Size(max = 200) String search,
      @Schema(description = "🔴 只选**有待发布内容草稿**的 listing（4.2.1.2 的产出）；"
          + "不勾 = 不按草稿筛") Boolean hasPendingDraft,
      @Schema(description = "🔴 排除已发布过的（listing.status 已是 ON_SALE / UNDER_REVIEW / APPROVED）")
          Boolean excludeAlreadyPublished,
      @Schema(description = "🔴 数量上限（1~1000）：批次最多纳入多少条")
          @Min(1)
          @Max(1000)
          Integer limit) {}

  // ================================================================ 批次视图

  /**
   * 批次视图（总体进度）。
   *
   * <p>五个计数由服务层每次变更后从 {@code publish_task} 重算并写回，
   * DB 有 CHECK 钉住"五者之和 = 总数"，所以界面上不可能出现自相矛盾的进度。
   */
  public record PublishBatchRow(
      @Schema(description = "批次 id") UUID batchId,
      @Schema(description = "RUNNING 可派发 / PAUSED 停止派发新任务 / COMPLETED 全部终态 / CANCELLED 已中止")
          String state,
      @Schema(description = "🔴 筛选规则快照（提交那一刻的口径）") String filterRule,
      @Schema(description = "发布策略快照") String strategy,
      @Schema(description = "数量上限") int limitCount,
      @Schema(description = "任务总数") int totalCount,
      @Schema(description = "待派发") int pendingCount,
      @Schema(description = "🔴 在途（已派发未回报）") int dispatchedCount,
      @Schema(description = "🔴 已成功") int succeededCount,
      @Schema(description = "🔴 失败（可批量重试）") int failedCount,
      @Schema(description = "🔴 进度百分比（0~100，按已终态/总数；总数为 0 时为 0）") int progressPercent,
      @Schema(description = "创建人") UUID actorId,
      @Schema(description = "备注") String note,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "最近更新时刻") OffsetDateTime updatedAt,
      @Schema(description = "全部终态的时刻；仅 COMPLETED 非空") OffsetDateTime completedAt) {}

  // ================================================================ 任务视图

  /** 队列里的一条任务（「批次任务列表」+ 失败明细共用这一个形状）。 */
  public record PublishTaskRow(
      @Schema(description = "任务 id（4.3.1.3 的跳转参数）") UUID taskId,
      @Schema(description = "批次 id") UUID batchId,
      @Schema(description = "listing id") UUID listingId,
      @Schema(description = "平台商品 ID 原文") String externalItemId,
      @Schema(description = "标题") String title,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "🔴 平台编码；这条任务按**这个平台**的配额排队") String platformCode,
      @Schema(description = "🔴 生效的限流配置 id；null = 该平台未配置限流（fail-closed，不派发）")
          UUID rateLimitId,
      @Schema(description = "🔴 执行顺序（可重排；派发按它升序取）") int position,
      @Schema(description = "PENDING / DISPATCHED / SUCCEEDED / FAILED") String state,
      @Schema(description = "已派发次数（重试会累加）") int attempts,
      @Schema(description = "🔴 失败明细；可跳转 4.3.1.3") String failureReason,
      @Schema(description = "派发时刻；仅 DISPATCHED/SUCCEEDED/FAILED 非空") OffsetDateTime dispatchedAt,
      @Schema(description = "终态时刻；仅 SUCCEEDED/FAILED 非空") OffsetDateTime completedAt) {}

  // ================================================================ 派发 / 控制动作

  /** 一轮派发里某条限流行的情况（「为什么这条没派出去」逐条写明，不静默丢弃）。 */
  public record DispatchLaneReport(
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "店铺编码；平台级为 null") String shopCode,
      @Schema(description = "生效的限流配置 id；null = 未配置") UUID rateLimitId,
      @Schema(description = "在途上限") int maxInFlight,
      @Schema(description = "🔴 本轮派发前该限流行在途数") long inFlightBefore,
      @Schema(description = "🔴 窗口上限（滑动窗口内最多派发多少条）") int requestsPerWindow,
      @Schema(description = "🔴 窗口长度（秒）") int windowSeconds,
      @Schema(description = "🔴 本轮派发前窗口内已派发次数") long windowCountBefore,
      @Schema(description = "🔴 本轮实际派发条数") int dispatched,
      @Schema(description = "🔴 本轮因限流未派发的条数") int skippedByRateLimit,
      @Schema(description = "🔴 为什么没派；为空串表示按配额正常派发") String note) {}

  /**
   * 一轮派发的结果。
   *
   * <p>{@code lanes} 是<b>逐限流行</b>（平台+店铺）而不是一个全局数字 —— 这正是"按平台
   * 各自配额排队"在输出上的可查证据：Amazon 与 TikTok 各走各的账。
   */
  public record DispatchResponse(
      @Schema(description = "批次 id") UUID batchId,
      @Schema(description = "批次状态；PAUSED 时本轮一律不派发") String batchState,
      @Schema(description = "🔴 本轮一共派发了多少条") int totalDispatched,
      @Schema(description = "🔴 本轮一共因限流跳过多少条") int totalSkipped,
      @Schema(description = "🔴 逐限流行的派发情况") List<DispatchLaneReport> lanes,
      @Schema(description = "批次最新视图") PublishBatchRow batch) {}

  /** 暂停 / 恢复 / 中止。 */
  public record PublishBatchStateRequest(
      @Schema(description = "原因 / 备注，落进批次与事件留痕") @Size(max = 500) String note) {}

  /**
   * 🔴 重排：调用者给出**完整的新执行次序**。
   *
   * <p>服务层据此重写每条任务的 {@code position}，派发按 position 升序取，所以重排真的
   * 改变执行次序（而不是只存一个字段了事）。只接受本批次**当前可重排**的任务
   * （PENDING）；已派发 / 已成功 / 已失败的不参与重排（在途与终态不该被重排打乱）。
   */
  public record ReorderRequest(
      @Schema(description = "🔴 新的执行次序（**完整**列出要重排的任务 id，顺序即执行次序）")
          @NotNull
          List<UUID> taskIds,
      @Schema(description = "原因 / 备注") @Size(max = 500) String note) {}

  /** 🔴 批量重试：只重试失败项。模型层**没有**"连成功的一起重跑"的参数形状。 */
  public record RetryRequest(
      @Schema(description = "🔴 只把这些 FAILED 任务打回队列（不给 = 重试本批次全部失败项）")
          List<UUID> taskIds,
      @Schema(description = "原因 / 备注") @Size(max = 500) String note) {}

  /** 重排 / 重试的结果（逐条写明哪些真被改了、哪些没被改）。 */
  public record BatchActionResult(
      @Schema(description = "批次 id") UUID batchId,
      @Schema(description = "本批次状态") String state,
      @Schema(description = "🔴 本次实际被改动的任务 id（逐条）") List<UUID> changedTaskIds,
      @Schema(description = "🔴 被拒绝的任务 id + 原因（如：在途或终态不可重排 / 不属于本批次）")
          List<String> rejected,
      @Schema(description = "批次最新视图") PublishBatchRow batch) {}
}
