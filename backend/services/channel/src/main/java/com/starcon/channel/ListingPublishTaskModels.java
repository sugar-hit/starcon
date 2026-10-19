package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 4.3.1.3 发布任务 的传输模型。
 *
 * <p>蓝图：「单次发布任务的进度视图：各步骤状态（内容校验 → 平台创建 → 变体创建 → 媒体上传 → 价格库存设置 → 提交审核）、失败步骤与原因、可重试的步骤。输入：任务 ID；平台返回结果。输出：步骤时间线 + 错误明细 + 重试操作。关系：上游 = 4.3.1.1/4.3.1.2；下游 = 2.5.1.1。<b>平台提交审核是异步的</b>，任务在「审核中」结束，实际结果由 4.3.2.2 回流。」
 *
 * <p>🔴 模型层把三件事钉死，它们是这个模块最容易在赶工时被做丢的：
 *
 * <ol>
 *   <li><b>本模块是进度视图，不是另一个发布系统</b>。它读 4.3.1.1 的
 *       {@code listing_publish_job_target}（= 一个发布任务）与
 *       {@code listing_publish_job_step}（= 步骤时间线），以及 4.3.1.2 的
 *       {@code publish_task}（= 队列里的一条发布任务）。<b>它自己只写一张表</b>：
 *       {@code listing_publish_step_attempt}（重试尝试留痕）。
 *   <li><b>「审核中」不是「已发布」</b>。{@code stage=IN_REVIEW} 的含义是
 *       **已提交平台、等回执**（4.3.1.1 的 SUBMITTED / 4.3.1.2 的 DISPATCHED）。
 *       重试尝试的 {@code outcome=SUCCEEDED} 只表示「已重新提交平台」，
 *       <b>不代表平台审核通过</b> —— 蓝图加粗句明写「平台提交审核是异步的」。
 *   <li><b>「不可重试」也是一条信息</b>。每个步骤都带 {@code retryable} +
 *       {@code retryBlockedReason}：内容快照已冻结的步骤、平台已驳回的任务、
 *       刻意跳过的库存步骤 —— 它们都**不能**重试，理由逐条写在模型里，
 *       界面上原样显示，不给一个点了没反应的按钮。
 * </ol>
 *
 * <p>载荷一律用具名 {@code record}，不用 {@code Map<String,Object>}：Map 的值类型会被
 * springdoc 推成 object，codegen 出来是空对象 {@code {}}（9.6.1.3 已踩过）。
 */
public final class ListingPublishTaskModels {
  private ListingPublishTaskModels() {}

  // ================================================================ 值域

  /**
   * 任务来源。
   *
   * <p>🔴 {@code ONECLICK} = 4.3.1.1 的发布任务行（<b>每个目标一个</b>，有完整步骤时间线）；
   * {@code BATCH} = 4.3.1.2 队列里的一条发布任务（<b>按平台配额排队</b>，没有步骤行）。
   *
   * <p>两条上游是**两套独立的状态机**（蓝图：跨平台发布不是一个事务 vs 按配额排队），
   * 但它们产出的东西都叫「发布任务」⇒ 本视图把它们聚在一起，用 {@code taskKind} 区分，
   * <b>不把它们揉成同一套状态</b>。
   */
  public static final List<String> TASK_KINDS = List.of("ONECLICK", "BATCH");

  /**
   * 归一化后的任务阶段 —— 两条上游各自的原始状态映射到同一组阶段词。
   *
   * <p>🔴 归一化只用于**筛选与展示**；每个任务行同时带原始 {@code state}，
   * 原始状态永远不丢 —— 4.3.1.1 的 DRAFT 与 4.3.1.2 的 PENDING 不是一回事，
   * 归一化只是让「有哪些阶段可筛」有个共同词表。
   */
  public static final List<String> STAGES =
      List.of("PENDING", "IN_REVIEW", "SUCCEEDED", "REJECTED", "FAILED", "CANCELLED");

  /** 步骤状态（与 4.3.1.1 的 listing_publish_job_step.state 同值域）。 */
  public static final List<String> STEP_STATES =
      List.of("PENDING", "RUNNING", "SUCCEEDED", "FAILED", "SKIPPED", "BLOCKED");

  /** 步骤键（与 4.3.1.1 的 listing_publish_job_step.step_key 同值域）。 */
  public static final List<String> STEP_KEYS =
      List.of("TEMPLATE", "CONTENT", "MAPPING", "MEDIA", "PRICE", "STOCK", "SUBMIT", "RESULT");

  /** 错误明细的来源层级。 */
  public static final List<String> ERROR_SCOPES = List.of("TASK", "STEP", "ATTEMPT");

  // ================================================================ 能力声明

  public record ListingPublishTaskCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  /**
   * 蓝图阶段 → 实际落库的步骤键。
   *
   * <p>🔴 蓝图那句「内容校验 → 平台创建 → 变体创建 → 媒体上传 → 价格库存设置 → 提交审核」
   * 里，<b>「平台创建」与「变体创建」在 4.3.1.1 没有单独的步骤行</b>
   * （4.3.1.1 记的是套模板 / 填充内容 / 建立映射 / 上传媒体 / 提交审核 / 平台结果）。
   * 本模块**如实报出**这两段没有独立步骤（{@code stepKeys} 为空 + note 写明归属），
   * <b>不凭空造两个步骤行</b> —— 造出来的步骤是假数据。
   */
  public record ListingPublishTaskPhase(
      @Schema(description = "蓝图阶段名（原文措辞）") String phase,
      @Schema(description = "阶段说明") String description,
      @Schema(description = "落到哪些步骤键（空数组 = 4.3.1.1 没有单独记录这一段）")
          List<String> stepKeys,
      @Schema(description = "没有独立步骤行时的说明（含归属模块）") String note) {}

  public record ListingPublishTaskCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false")
          List<ListingPublishTaskCapabilityDimension> dimensions,
      @Schema(description = "🔴 蓝图阶段 → 步骤键的映射（没有独立步骤行的阶段如实为空）")
          List<ListingPublishTaskPhase> phases,
      @Schema(description = "任务来源取值域") List<String> taskKinds,
      @Schema(description = "归一化后的任务阶段取值域") List<String> stages,
      @Schema(description = "步骤状态取值域") List<String> stepStates,
      @Schema(description = "步骤键取值域") List<String> stepKeys,
      @Schema(description = "错误明细的来源层级") List<String> errorScopes,
      @Schema(description = "🔴 一页最多几条任务") int maxPageSize,
      @Schema(description = "🔴 平台提交审核是异步的（前端原样展示）") String asyncReviewNote,
      @Schema(description = "🔴 跨平台发布不是一个事务的说明") String nonTransactionalNote,
      @Schema(description = "🔴 重试语义的说明") String retryNote,
      @Schema(description = "上游 / 下游说明") String relationNote) {}

  // ================================================================ 输出

  /**
   * 一个发布任务（列表行）。
   *
   * <p>🔴 {@code stage} 是**归一化**后的阶段词，{@code state} 是**原始**状态 —— 两个都带，
   * 原始状态永远不丢。{@code taskKind} 说明这条任务来自哪条上游。
   */
  public record ListingPublishTaskRow(
      @Schema(description = "发布任务 id") UUID taskId,
      @Schema(description = "任务来源：ONECLICK 4.3.1.1 / BATCH 4.3.1.2") String taskKind,
      @Schema(description = "🔴 归一化后的阶段：PENDING / IN_REVIEW / SUCCEEDED / REJECTED / FAILED / CANCELLED")
          String stage,
      @Schema(description = "🔴 原始状态（两条上游各自的原词，原样保留）") String state,
      @Schema(description = "所属一键刊登作业 id；BATCH 任务为空", nullable = true) UUID jobId,
      @Schema(description = "所属批量发布批次 id；ONECLICK 任务为空", nullable = true) UUID batchId,
      @Schema(description = "平台 id") UUID platformId,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "店铺 id") UUID shopId,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "站点 id；BATCH 任务没有站点维度", nullable = true) UUID marketplaceId,
      @Schema(description = "站点编码", nullable = true) String marketplaceCode,
      @Schema(description = "语言；BATCH 任务没有语言维度", nullable = true) String locale,
      @Schema(description = "次序") int position,
      @Schema(description = "尝试次数") int attempts,
      @Schema(description = "🔴 失败 / 驳回原因") String failureReason,
      @Schema(description = "🔴 步骤总数（BATCH 任务恒为 0：那条通道不落步骤行）") int stepCount,
      @Schema(description = "🔴 失败的步骤数") int failedStepCount,
      @Schema(description = "🔴 步骤时间线是否可用（BATCH 任务为 false）") boolean stepTimelineAvailable,
      @Schema(description = "派发时刻", nullable = true) OffsetDateTime dispatchedAt,
      @Schema(description = "完成时刻", nullable = true) OffsetDateTime completedAt,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "最近更新时刻") OffsetDateTime updatedAt) {}

  /** 任务列表（真分页：带总数，前端的「下一页」按 total 判断，不猜）。 */
  public record ListingPublishTaskPage(
      @Schema(description = "本页任务") List<ListingPublishTaskRow> rows,
      @Schema(description = "页码（0 起）") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "🔴 符合条件的总条数") int total) {}

  /** 一次重试尝试（留痕）。 */
  public record ListingPublishTaskAttemptRow(
      @Schema(description = "尝试 id") UUID attemptId,
      @Schema(description = "🔴 第几次尝试（该任务上的第几次重试，跨步骤递增）") int attemptNo,
      @Schema(description = "被重试的步骤键；任务级重试没有步骤键", nullable = true) String stepKey,
      @Schema(description = "步骤归属：TASK 整条任务 / TARGET 目标级 / JOB 作业级") String scope,
      @Schema(description = "🔴 SUCCEEDED = 已重新提交平台（≠ 平台审核通过）/ FAILED = 重试没成")
          String outcome,
      @Schema(description = "失败原因 / 说明") String reason,
      @Schema(description = "下游 2.5.1.1 的回写任务 id", nullable = true) UUID platformOperationId,
      @Schema(description = "发起时刻") OffsetDateTime requestedAt,
      @Schema(description = "出结果时刻", nullable = true) OffsetDateTime finishedAt) {}

  /**
   * 步骤时间线上的一步。
   *
   * <p>🔴 {@code retryable=false} 时 {@code retryBlockedReason} 必填 —— 界面上原样显示
   * 「为什么这一步不能重试」，不给一个点了没反应的按钮。
   */
  public record ListingPublishTaskStepRow(
      @Schema(description = "步骤 id") UUID stepId,
      @Schema(description = "所属发布任务 id") UUID taskId,
      @Schema(description = "所属作业 id") UUID jobId,
      @Schema(description = "目标 id；作业级步骤为空", nullable = true) UUID targetId,
      @Schema(description = "步骤键") String stepKey,
      @Schema(description = "🔴 蓝图阶段名") String phase,
      @Schema(description = "步骤归属：TARGET 目标级 / JOB 作业级") String scope,
      @Schema(description = "次序（同一归属内的次序）") int position,
      @Schema(description = "PENDING / RUNNING / SUCCEEDED / FAILED / SKIPPED / BLOCKED") String state,
      @Schema(description = "说明（人能读）") String detail,
      @Schema(description = "🔴 这一步能不能重试") boolean retryable,
      @Schema(description = "🔴 不能重试的原因（retryable=false 时必填）") String retryBlockedReason,
      @Schema(description = "开始时刻", nullable = true) OffsetDateTime startedAt,
      @Schema(description = "完成时刻", nullable = true) OffsetDateTime finishedAt,
      @Schema(description = "🔴 耗时毫秒（未结束的步骤为空）", nullable = true) Long durationMillis,
      @Schema(description = "🔴 这一步的重试尝试次数") int attemptCount,
      @Schema(description = "🔴 这一步的重试尝试留痕") List<ListingPublishTaskAttemptRow> attempts) {}

  /**
   * 一条错误明细。
   *
   * <p>🔴 三个来源分得开：{@code TASK} = 任务行自己的失败 / 驳回原因；
   * {@code STEP} = 某一步失败的说明；{@code ATTEMPT} = 某一次重试尝试没成的原因。
   */
  public record ListingPublishTaskError(
      @Schema(description = "来源层级：TASK / STEP / ATTEMPT") String scope,
      @Schema(description = "步骤键；TASK 级错误为空", nullable = true) String stepKey,
      @Schema(description = "蓝图阶段名；TASK 级错误为空", nullable = true) String phase,
      @Schema(description = "🔴 原因（人能读，前端原样展示）") String reason,
      @Schema(description = "发生时刻", nullable = true) OffsetDateTime occurredAt,
      @Schema(description = "🔴 这条错误对应的步骤能不能重试") boolean retryable) {}

  /** 平台侧的发布留痕（读 4.1.1.2 的 listing_publish_record，只读）。 */
  public record ListingPublishTaskPublishRecord(
      @Schema(description = "留痕 id") UUID recordId,
      @Schema(description = "PUBLISH 发布 / CONTENT_WRITEBACK 内容回写") String action,
      @Schema(description = "SUCCEEDED / FAILED / REJECTED") String outcome,
      @Schema(description = "失败 / 驳回原因") String reason,
      @Schema(description = "平台请求 id") String platformRequestId,
      @Schema(description = "发生时刻") OffsetDateTime occurredAt) {}

  /**
   * 任务详情 = 步骤时间线 + 错误明细 + 重试尝试。
   *
   * <p>🔴 {@code stepTimelineAvailable=false} 时 {@code stepTimelineNote} 必填 ——
   * 4.3.1.2 的队列任务**不落步骤行**（那条通道按平台配额排队，没有逐步骤记录），
   * 界面据此显示「本任务没有步骤时间线」并说明原因，**不拿空数组冒充「已完成」**。
   */
  public record ListingPublishTaskDetail(
      @Schema(description = "任务") ListingPublishTaskRow task,
      @Schema(description = "🔴 步骤时间线（按蓝图阶段序，同阶段内按 position）")
          List<ListingPublishTaskStepRow> timeline,
      @Schema(description = "🔴 错误明细") List<ListingPublishTaskError> errors,
      @Schema(description = "🔴 全部重试尝试留痕") List<ListingPublishTaskAttemptRow> attempts,
      @Schema(description = "🔴 步骤时间线是否可用") boolean stepTimelineAvailable,
      @Schema(description = "🔴 步骤时间线不可用时的说明（可用时为空）") String stepTimelineNote,
      @Schema(description = "🔴 平台侧发布留痕（读 4.1.1.2 的 listing_publish_record）")
          List<ListingPublishTaskPublishRecord> publishRecords,
      @Schema(description = "🔴 那一刻冻结的内容标题（给人看「当时发的是什么」）", nullable = true)
          String contentSnapshotTitle,
      @Schema(description = "🔴 平台商品 id（回执成功后回填）", nullable = true) String externalItemId,
      @Schema(description = "🔴 异步审核说明（前端原样展示）") String asyncReviewNote) {}

  /**
   * 重试结果。
   *
   * <p>🔴 {@code outcome=SUCCEEDED} 只表示**重试动作被接受并已重新提交平台**，
   * 不表示平台审核通过 —— 平台提交审核是异步的，结果由 4.3.2.2 回流。
   */
  public record ListingPublishTaskRetryResult(
      @Schema(description = "发布任务 id") UUID taskId,
      @Schema(description = "被重试的步骤键；任务级重试没有步骤键", nullable = true) String stepKey,
      @Schema(description = "🔴 第几次尝试") int attemptNo,
      @Schema(description = "SUCCEEDED 已重新提交平台 / FAILED 重试没成") String outcome,
      @Schema(description = "说明 / 失败原因") String reason,
      @Schema(description = "下游 2.5.1.1 的回写任务 id", nullable = true) UUID platformOperationId,
      @Schema(description = "🔴 重试后的步骤时间线（前端直接替换）")
          List<ListingPublishTaskStepRow> timeline) {}
}
