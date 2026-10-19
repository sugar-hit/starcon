package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 4.3.2.1 内容回写 的传输模型。
 *
 * <p>蓝图：「把内部已发布的商品内容变更推送到平台（改标题、改图、改描述、改 A+），支持按变更类型触发或批量触发，并记录回写前后快照。输入：内容变更事件；用户批量回写操作；目标 listing 集合。输出：回写任务（经 02 队列）+ 变更历史。关系：上游 = 3 商品主数据变更、4.2.1.2 批量编辑；下游 = 2.5.1.1。<b>内容回写不触碰价格与库存</b>（那是 05/07 的事），三条回写通道独立失败独立重试。」
 *
 * <p>载荷一律用具名 {@code record}：Map 的值类型会被 springdoc 推成 object，codegen 出来是空对象
 * {@code {}}（9.6.1.3 已踩过）。{@code overrides} 用 {@code Map<String,String>} 是**刻意**的：
 * 它的值是文本（A+ 模块也是序列化成文本），不是嵌套对象 —— 用 {@code Map<String,Object>}
 * 只会把 codegen 产物变成空对象。
 *
 * <p>🔴 模型层就把核心不变量钉死：
 *
 * <ol>
 *   <li><b>请求里没有"价格"与"库存"的入口</b>：{@link CreateContentWritebackRequest} 只有
 *       {@code changeTypes}（{@link #CHANGE_TYPES} 那五个值）与 {@code overrides}
 *       （同一组键），<b>没有任何价格 / 库存字段</b>。内容通道与 05 定价 / 07 库存三条通道
 *       在 HTTP 契约这一层就已经分开，而不只是 DB 层的 CHECK。
 *   <li><b>没有"跳过已成功项"的参数形状</b>：{@link RetryContentWritebackRequest} 只接受
 *       FAILED 任务 id（不给 = 本次全部失败项），已成功的在 DB 层由触发器保护。
 *   <li><b>{@code SKIPPED_NO_CHANGE} 是一等状态</b>：改后与线上逐字相同的内容<strong>不派发</strong>，
 *       也不报成功 —— 对平台的写调用是真实的外部副作用，无意义的写不该发生，
 *       而谎报"已推送"比不推送更难发现。
 * </ol>
 */
public final class ContentWritebackModels {
  private ContentWritebackModels() {}

  /**
   * 🔴 内容通道的五个变更类型。
   *
   * <p>职责句逐词对应：「改标题」→ TITLE、「改描述」→ DESCRIPTION + BULLETS（描述在数据上是
   * 长文本与卖点短语两段）、「改图」→ IMAGES、「改 A+」→ A_PLUS。
   *
   * <p><b>这里没有 PRICE，也没有 STOCK</b> —— 蓝图加粗句「内容回写不触碰价格与库存」。
   * 服务层收到含这两个词的请求会 422（不是静默忽略），因为静默忽略会让用户以为价格也推上去了。
   */
  public static final List<String> CHANGE_TYPES =
      List.of("TITLE", "BULLETS", "IMAGES", "DESCRIPTION", "A_PLUS");

  /**
   * 服务层 422 拒绝时用的口径：把不属于内容通道的变更类型逐个点名，<b>不静默忽略</b>。
   *
   * <p>静默忽略比拒绝危险得多：用户勾了「标题 + 价格」，系统只推了标题却回一个成功，
   * 用户会以为价格也推上去了 —— 而价格根本不在本模块的通道里（蓝图加粗句）。
   */
  public static final List<String> REJECTED_CHANGE_TYPES =
      List.of("PRICE", "STOCK", "QUANTITY", "CATEGORY", "A_PLUS_MODULES");

  // ================================================================ 能力声明

  public record ContentWritebackCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  public record ContentWritebackChannel(
      @Schema(description = "通道键") String channel,
      @Schema(description = "通道名") String label,
      @Schema(description = "归属模块") String ownerModule,
      @Schema(description = "🔴 本模块是否处理它") boolean handledHere,
      @Schema(description = "说明") String note) {}

  public record ContentWritebackCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false") List<ContentWritebackCapabilityDimension> dimensions,
      @Schema(description = "变更类型取值域（🔴 不含 PRICE / STOCK）") List<String> changeTypes,
      @Schema(description = "🔴 本请求会被 422 拒绝的变更类型（价格 / 库存等，归属 05/07）")
          List<String> rejectedChangeTypes,
      @Schema(description = "三条回写通道的归属与独立失败独立重试口径") List<ContentWritebackChannel> channels,
      @Schema(description = "触发状态取值") List<String> triggerStates,
      @Schema(description = "任务状态取值") List<String> taskStates,
      @Schema(description = "回写前后快照口径说明；前端原样展示，不要自己编") String snapshotNote,
      @Schema(description = "上游 / 下游说明") String relationNote) {}

  // ================================================================ 建一次回写

  /**
   * 建一次内容回写（按变更类型触发 / 批量触发 / 目标 listing 集合）。
   *
   * <p>🔴 模型层<b>没有</b> price / stock / quantity 字段：内容通道与 05 定价 / 07 库存
   * 三条通道在 HTTP 契约这一层就分开了。
   */
  public record CreateContentWritebackRequest(
      @Schema(description = "🔴 本次回写要改的字段；取值 TITLE / BULLETS / IMAGES / DESCRIPTION / A_PLUS。"
          + "**不含 PRICE / STOCK**（那是 05 定价 / 07 库存的事）")
          @NotEmpty
          List<String> changeTypes,
      @Schema(description = "目标 listing 集合（不给 = 按下面的筛选条件挑）")
          List<UUID> listingIds,
      @Schema(description = "店铺编码筛选（不给 listingIds 时按它筛）") @Size(max = 64) String shopCode,
      @Schema(description = "listing 状态筛选（不给 listingIds 时按它筛）") @Size(max = 32) String status,
      @Schema(description = "🔴 显式覆盖值：给 DESCRIPTION / A_PLUS 用的新值。"
          + "内部没有对应草稿字段时（4.2.1.1 未交付），这是唯一真实来源；"
          + "既无草稿又无覆盖值的那条任务判 SKIPPED_NO_SOURCE，**不编一个假值**")
          Map<String, String> overrides,
      @Schema(description = "平台内容语言（listing + language 是平台侧内容的维度）；"
          + "不给 = 取该 listing 的当前快照语言，连快照都没有时那条任务判 SKIPPED_NO_SOURCE")
          @Size(max = 16)
          String language,
      @Schema(description = "🔴 数量上限（1~500）：一次触发最多纳入多少条 listing")
          @Min(1)
          @Max(500)
          Integer limit,
      @Schema(description = "触发来源备注（进留痕）") @Size(max = 500) String note) {}

  // ================================================================ 一次触发视图

  /** 一次回写触发的视图（总体进度；五个计数由服务层每次变更后重算写回）。 */
  public record ContentWritebackRow(
      @Schema(description = "回写触发 id") UUID writebackId,
      @Schema(description = "SINGLE 按变更类型单条触发 / BULK_EVENT 批量触发") String triggerKind,
      @Schema(description = "🔴 本次的变更类型集合（**不含价格与库存**）") List<String> changeTypes,
      @Schema(description = "🔴 目标集合快照（提交那一刻选了哪些）") List<UUID> listingIds,
      @Schema(description = "🔴 提交那一刻的显式覆盖值原文") String overrides,
      @Schema(description = "解析出的平台内容语言；空串 = 未能解析（相关任务判 SKIPPED_NO_SOURCE）")
          String language,
      @Schema(description = "RUNNING 可派发 / COMPLETED 全部终态 / CANCELLED 已中止") String state,
      @Schema(description = "目标总数") int totalCount,
      @Schema(description = "待派发") int pendingCount,
      @Schema(description = "🔴 跳过（内容与线上逐字相同，未派发）") int skippedCount,
      @Schema(description = "🔴 在途（已进 02 队列未回报）") int dispatchedCount,
      @Schema(description = "🔴 已成功") int succeededCount,
      @Schema(description = "🔴 失败（可批量重试）") int failedCount,
      @Schema(description = "🔴 进度百分比（0~100，按已终态 / 总数；总数为 0 时为 0）") int progressPercent,
      @Schema(description = "触发人") UUID actorId,
      @Schema(description = "备注") String note,
      @Schema(description = "触发时刻") OffsetDateTime createdAt,
      @Schema(description = "最近更新时刻") OffsetDateTime updatedAt,
      @Schema(description = "全部终态的时刻；仅 COMPLETED 非空") OffsetDateTime completedAt) {}

  // ================================================================ 任务视图

  /** 一条回写任务（「输出：回写任务（经 02 队列）」）。 */
  public record ContentWritebackTaskRow(
      @Schema(description = "任务 id") UUID taskId,
      @Schema(description = "回写触发 id") UUID writebackId,
      @Schema(description = "listing id") UUID listingId,
      @Schema(description = "平台商品 ID 原文") String externalItemId,
      @Schema(description = "线上标题") String title,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "🔴 平台编码；这条任务按**这个平台**的配额排队") String platformCode,
      @Schema(description = "平台内容语言") String language,
      @Schema(description = "🔴 本次要改的字段") List<String> changeTypes,
      @Schema(description = "改后内容取自的内部草稿 id（null = 无草稿，用显式覆盖值或判无来源）")
          UUID draftId,
      @Schema(description = "改前基线取自的平台侧当前快照 id（null = 该 listing 无当前快照）")
          UUID snapshotId,
      @Schema(description = "PENDING / SKIPPED_NO_CHANGE / DISPATCHED / SUCCEEDED / FAILED") String state,
      @Schema(description = "已派发次数（重试会累加）") int attempts,
      @Schema(description = "🔴 02 队列任务 id（V2 platform_operation）；PENDING / SKIPPED 时为 null")
          UUID operationId,
      @Schema(description = "跳过或失败的原因；成功时为空串") String failureReason,
      @Schema(description = "派发时刻；仅 DISPATCHED / 终态非空") OffsetDateTime dispatchedAt,
      @Schema(description = "终态时刻；仅 SUCCEEDED / FAILED 非空") OffsetDateTime completedAt) {}

  // ================================================================ 变更历史

  /**
   * 🔴 逐字段「改前 vs 改后」（= 职责句里的「记录回写前后快照」）。
   *
   * <p>{@code beforeSource} / {@code afterSource} 把两侧的出处逐行记下：
   * 改前来自哪一份平台侧快照（{@code PLATFORM_SNAPSHOT}）或根本没有（{@code MISSING}），
   * 改后来自内部草稿（{@code INTERNAL_DRAFT}）还是调用方给的显式覆盖值（{@code OVERRIDE}）。
   * 没有这两个来源字段，"改前值是什么时候的"就答不上来。
   */
  public record ContentWritebackChangeRow(
      @Schema(description = "变更 id") UUID changeId,
      @Schema(description = "任务 id") UUID taskId,
      @Schema(description = "listing id") UUID listingId,
      @Schema(description = "🔴 字段：TITLE / BULLETS / IMAGES / DESCRIPTION / A_PLUS") String field,
      @Schema(description = "改前值（平台侧当前内容；无基线时为空串）") String beforeValue,
      @Schema(description = "改后值（内部草稿或显式覆盖值）") String afterValue,
      @Schema(description = "改前是否与改后逐字不同") boolean changed,
      @Schema(description = "APPLIED 有改 / NO_CHANGE 与线上逐字相同 / SKIPPED_NO_SOURCE 内部没有该字段的待推送内容")
          String outcome,
      @Schema(description = "改前值来源：PLATFORM_SNAPSHOT / MISSING") String beforeSource,
      @Schema(description = "改后值来源：INTERNAL_DRAFT / OVERRIDE / MISSING") String afterSource,
      @Schema(description = "原因说明") String reason) {}

  // ================================================================ 派发 / 控制动作

  /** 一轮派发里某条限流行的情况（「为什么这条没派出去」逐条写明，不静默丢弃）。 */
  public record ContentWritebackLaneReport(
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "店铺编码；平台级配额为 null") String shopCode,
      @Schema(description = "生效的限流配置 id；null = 未配置") UUID rateLimitId,
      @Schema(description = "在途上限") int maxInFlight,
      @Schema(description = "🔴 本轮派发前该限流行在途数") long inFlightBefore,
      @Schema(description = "🔴 窗口上限（滑动窗口内最多派发多少条）") int requestsPerWindow,
      @Schema(description = "窗口长度（秒）") int windowSeconds,
      @Schema(description = "🔴 本轮派发前窗口内已派发次数") long windowCountBefore,
      @Schema(description = "🔴 本轮实际派发条数") int dispatched,
      @Schema(description = "🔴 本轮因限流未派发的条数") int skippedByRateLimit,
      @Schema(description = "🔴 为什么没派；为空串表示按配额正常派发") String note) {}

  /**
   * 一轮派发的结果。
   *
   * <p>🔴 {@code queuedOperationIds} 是本轮写进 02 队列（V2 {@code platform_operation}）的
   * 任务 id —— 「输出：回写任务（经 02 队列）」在响应上的直接凭据：在 2.5.1.1 的回写任务列表
   * 里能按这些 id 查到它们。
   */
  public record ContentWritebackDispatchResponse(
      @Schema(description = "回写触发 id") UUID writebackId,
      @Schema(description = "触发状态；非 RUNNING 时本轮一律不派发") String triggerState,
      @Schema(description = "🔴 本轮一共派发了多少条") int totalDispatched,
      @Schema(description = "🔴 本轮一共因限流跳过多少条") int totalSkippedByRateLimit,
      @Schema(description = "🔴 本轮写进 02 队列的 platform_operation id 列表")
          List<UUID> queuedOperationIds,
      @Schema(description = "🔴 逐限流行的派发情况") List<ContentWritebackLaneReport> lanes,
      @Schema(description = "触发最新视图") ContentWritebackRow writeback) {}

  /** 人工中止。已在途的任务不取消（已发出的平台写请求撤不回），仍可被回报结果。 */
  public record ContentWritebackStateRequest(
      @Schema(description = "原因 / 备注，落进留痕") @Size(max = 500) String note) {}

  /**
   * 🔴 批量重试：**只**重试失败项。
   *
   * <p>模型层<b>没有</b>"连成功的一起重跑"的参数形状 —— 成功的项在 DB 层由
   * {@code guard_content_writeback_terminal} 触发器保护，任何路径都无法把它们打回队列。
   * 重跑已成功的 = 平台上被重复改一轮内容，而内容与线上已一致时连"有没有真的改"都答不上来。
   */
  public record RetryContentWritebackRequest(
      @Schema(description = "🔴 只把这些 FAILED 任务打回队列（不给 = 重试本次全部失败项）")
          List<UUID> taskIds,
      @Schema(description = "原因 / 备注") @Size(max = 500) String note) {}

  /** 重试 / 中止的结果（逐条写明哪些真被改了、哪些没被改）。 */
  public record ContentWritebackActionResult(
      @Schema(description = "回写触发 id") UUID writebackId,
      @Schema(description = "触发状态") String state,
      @Schema(description = "🔴 本次实际被改动的任务 id（逐条）") List<UUID> changedTaskIds,
      @Schema(description = "🔴 被拒绝的任务 id + 原因（如：已成功不可重试 / 不属于本次触发）")
          List<String> rejected,
      @Schema(description = "触发最新视图") ContentWritebackRow writeback) {}

  // ================================================================ 商品维度的变更历史

  /**
   * 某个 listing 的内容回写历史（按时间倒序，一次触发带它全部的字段变更）。
   *
   * <p>与 {@code /listings/{id}/publish-history}（V175 的 {@code listing_publish_record}）
   * 是<b>两个不同的东西</b>：那边记的是「发布动作本身发生了什么」，这边记的是
   * 「这次推了哪些字段、改前是什么、改后是什么」。两者互补，不互相替代。
   */
  public record ListingContentHistoryEntry(
      @Schema(description = "回写触发 id") UUID writebackId,
      @Schema(description = "触发状态") String triggerState,
      @Schema(description = "🔴 触发时刻") OffsetDateTime createdAt,
      @Schema(description = "🔴 该次触发在本商品上的任务状态") String taskState,
      @Schema(description = "02 队列任务 id；未派发为 null") UUID operationId,
      @Schema(description = "🔴 逐字段改前 / 改后") List<ContentWritebackChangeRow> changes) {}
}
