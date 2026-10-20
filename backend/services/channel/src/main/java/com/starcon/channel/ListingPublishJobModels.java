package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 4.3.1.1 一键刊登 的传输模型。
 *
 * <p>蓝图：「单个 SKU 一次性发布到多个目标店铺/站点：自动套模板、填充内容、建立映射、上传媒体、设置价格库存、提交平台审核。输入：源 SKU；目标店铺与站点集合；模板选择；价格库存来源策略；{@code idempotency_key}。输出：N 个发布任务（每个目标一个）+ 各任务的进度与结果（草稿/审核中/成功/驳回+原因）。关系：上游 = 4.2.1.1、5 定价（取价）、7 库存（取可售量）、02 平台能力；下游 = 2.5.1.1 回写任务。<b>跨平台发布不是一个事务</b>：某个平台失败不影响其他平台，各自独立重试。」
 *
 * <p>🔴 模型层就把三件事钉死，它们是这个模块最容易在赶工时被做丢的：
 *
 * <ol>
 *   <li><b>一个目标一个任务</b>：{@link CreateListingPublishJobRequest#targets()} 是<b>目标集合</b>，
 *       服务端为每个目标建一行 {@code listing_publish_job_target}，各有各的状态与重试次数。
 *       载荷里**没有**「整体成功 / 整体失败」这种字段 —— 作业的总体结论由服务端聚合。
 *   <li><b>幂等键必填</b>：{@link CreateListingPublishJobRequest#idempotencyKey()} 是
 *       {@code @NotBlank}，不是可选。重复提交同一个键返回同一条作业。
 *   <li><b>「不知道」是一个状态</b>：取价（5.1.1.1 未交付）时价格状态是
 *       {@code UNAVAILABLE} + 原因 + 归属模块，**不是** 0、也不是「没有这一栏」。
 * </ol>
 *
 * <p>载荷一律用具名 {@code record}，不用 {@code Map<String,Object>}：Map 的值类型会被 springdoc 推成
 * object，codegen 出来是空对象 {@code {}}（9.6.1.3 已踩过）。
 */
public final class ListingPublishJobModels {
  private ListingPublishJobModels() {}

  // ================================================================ 值域

  /** 内容来源：EDITOR_SUBMISSION（4.2.1.1 的入队记录）/ TEMPLATE（4.2.2.1 刊登模板）。 */
  public static final List<String> CONTENT_SOURCES = List.of("EDITOR_SUBMISSION", "TEMPLATE");

  /** 价格来源策略：PRICING_SERVICE（05 取价）/ MANUAL（运营手填）。 */
  public static final List<String> PRICE_SOURCES = List.of("PRICING_SERVICE", "MANUAL");

  /** 库存来源策略：WAREHOUSE_AVAILABLE（07 可售量）/ KEEP_UNSET（不设库存）。 */
  public static final List<String> STOCK_SOURCES = List.of("WAREHOUSE_AVAILABLE", "KEEP_UNSET");

  /** 作业状态：DRAFT 已建任务未派发 / DISPATCHED 已派发（等平台回执）/ CANCELLED 已撤销。 */
  public static final List<String> JOB_STATES = List.of("DRAFT", "DISPATCHED", "CANCELLED");

  /**
   * 目标状态 —— 逐字对应蓝图输出句「草稿/审核中/成功/驳回+原因」。
   *
   * <p>🔴 {@code SUBMITTED} 的含义是**已提交平台、等回执**（= 审核中），不是「已发布」。
   */
  public static final List<String> TARGET_STATES =
      List.of("DRAFT", "SUBMITTED", "SUCCEEDED", "REJECTED", "FAILED", "CANCELLED");

  /** 步骤状态。 */
  public static final List<String> STEP_STATES =
      List.of("PENDING", "RUNNING", "SUCCEEDED", "FAILED", "SKIPPED", "BLOCKED");

  /** 步骤键。PRICE / STOCK 是作业级，其余是目标级。 */
  public static final List<String> STEP_KEYS =
      List.of("TEMPLATE", "CONTENT", "MAPPING", "MEDIA", "PRICE", "STOCK", "SUBMIT", "RESULT");

  // ================================================================ 能力声明

  public record ListingPublishCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  public record ListingPublishCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false") List<ListingPublishCapabilityDimension> dimensions,
      @Schema(description = "内容来源取值域") List<String> contentSources,
      @Schema(description = "价格来源取值域") List<String> priceSources,
      @Schema(description = "库存来源取值域") List<String> stockSources,
      @Schema(description = "作业状态取值域") List<String> jobStates,
      @Schema(description = "目标状态取值域") List<String> targetStates,
      @Schema(description = "步骤状态取值域") List<String> stepStates,
      @Schema(description = "步骤键取值域") List<String> stepKeys,
      @Schema(description = "🔴 一次最多几个目标") int maxTargets,
      @Schema(description = "🔴 跨平台发布不是一个事务的说明（前端原样展示）") String nonTransactionalNote,
      @Schema(description = "🔴 幂等语义说明") String idempotencyNote,
      @Schema(description = "🔴 内容快照语义说明") String snapshotNote,
      @Schema(description = "上游 / 下游说明") String relationNote) {}

  // ================================================================ 选目标 / 选内容来源

  /**
   * 一个可选的目标（店铺 + 站点）。
   *
   * <p>🔴 这不是写死在前端的清单：它来自 {@code shop} / {@code shop_marketplace} /
   * {@code platform} / {@code marketplace} 四张真实表，**只列已启用（ACTIVE）的站点**。
   */
  public record ListingPublishTargetOption(
      @Schema(description = "店铺 id") UUID shopId,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "平台 id") UUID platformId,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "平台名") String platformName,
      @Schema(description = "平台接入状态：AVAILABLE / BETA / PLANNED") String supportStatus,
      @Schema(description = "站点 id") UUID marketplaceId,
      @Schema(description = "站点编码") String marketplaceCode,
      @Schema(description = "站点名") String marketplaceName,
      @Schema(description = "站点国家码") String countryCode,
      @Schema(description = "站点默认语言") String defaultLocale,
      @Schema(description = "🔴 平台能力矩阵（键缺失 = 该能力未声明，不是 false）")
          java.util.Map<String, Boolean> capabilities) {}

  /** 4.2.1.1 的入队记录（编辑器草稿来源）。 */
  public record ListingPublishEditorSubmissionOption(
      @Schema(description = "入队记录 id") UUID submissionId,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "店铺 id") UUID shopId,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "站点 id") UUID marketplaceId,
      @Schema(description = "站点编码") String marketplaceCode,
      @Schema(description = "语言") String locale,
      @Schema(description = "QUEUED 已入队 / DISPATCHED 已派发 / FAILED 派发失败 / CANCELLED 已撤销")
          String state,
      @Schema(description = "🔴 入队那一刻冻结的标题") String title,
      @Schema(description = "🔴 入队那一刻冻结的图片数") int imageCount,
      @Schema(description = "入队时刻") OffsetDateTime queuedAt) {}

  /** 4.2.2.1 的刊登模板（模板来源）。 */
  public record ListingPublishTemplateOption(
      @Schema(description = "模板 id") UUID templateId,
      @Schema(description = "模板编码") String code,
      @Schema(description = "模板名") String name,
      @Schema(description = "适用范围：CATEGORY / SHOP") String scope,
      @Schema(description = "🔴 适用范围的落点（类目路径 或 店铺编码）") String scopeTarget,
      @Schema(description = "平台 id") UUID platformId,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "站点 id；为空表示该平台全站点") UUID marketplaceId,
      @Schema(description = "站点编码；为空表示该平台全站点") String marketplaceCode,
      @Schema(description = "语言") String language,
      @Schema(description = "状态：DRAFT / ACTIVE / DISABLED") String status,
      @Schema(description = "字段规则条数") int fieldCount,
      @Schema(description = "图片组条数") int assetGroupCount) {}

  /** 内容来源候选（编辑器入队记录 + 可用模板）。 */
  public record ListingPublishContentSources(
      @Schema(description = "4.2.1.1 的入队记录（编辑器草稿来源）")
          List<ListingPublishEditorSubmissionOption> editorSubmissions,
      @Schema(description = "4.2.2.1 的刊登模板（模板来源）") List<ListingPublishTemplateOption> templates) {}

  // ================================================================ 请求

  /** 一个目标（店铺 + 站点 + 语言）。 */
  public record ListingPublishTargetInput(
      @Schema(description = "目标店铺 id") @NotNull UUID shopId,
      @Schema(description = "目标站点 id") @NotNull UUID marketplaceId,
      @Schema(description = "语言（内容按平台 + 站点 + 语言分别保存）") @NotBlank @Size(max = 16) String locale,
      @Schema(description = "目标平台类目路径（CATEGORY 作用域的模板靠它匹配）") @Size(max = 500)
          String categoryPath) {}

  /**
   * 建一键刊登作业的请求。
   *
   * <p>🔴 幂等键是 {@code @NotBlank}：重复提交同一个键返回同一条作业，不产生第二条。
   */
  public record CreateListingPublishJobRequest(
      @Schema(description = "源 SKU id（03 的逻辑 id，跨服务）") @NotNull UUID sourceSkuId,
      @Schema(description = "目标店铺与站点集合（每个目标一个发布任务）") @NotEmpty
          @Valid List<ListingPublishTargetInput> targets,
      @Schema(description = "内容来源：EDITOR_SUBMISSION / TEMPLATE") @NotBlank String contentSource,
      @Schema(description = "内容来源候选 id：EDITOR_SUBMISSION 传入队记录 id；TEMPLATE 传模板 id")
          List<UUID> contentSourceIds,
      @Schema(description = "价格来源策略：PRICING_SERVICE / MANUAL") @NotBlank String priceSource,
      @Schema(description = "价格金额（priceSource=MANUAL 时必填；NUMERIC(19,4)，禁浮点）")
          @DecimalMin(value = "0.00", inclusive = true) BigDecimal priceAmount,
      @Schema(description = "价格币种（priceSource=MANUAL 时必填）")
          @Pattern(regexp = "[A-Za-z]{3,8}", message = "币种必须是 3~8 位字母代码") String priceCurrency,
      @Schema(description = "库存来源策略：WAREHOUSE_AVAILABLE / KEEP_UNSET") @NotBlank String stockSource,
      @Schema(description = "目标仓库（stockSource=WAREHOUSE_AVAILABLE 时可指定）") UUID warehouseId,
      @Schema(description = "🔴 幂等键：同一个键重复提交返回同一条作业，不产生第二条") @NotBlank
          @Size(max = 128) String idempotencyKey,
      @Schema(description = "备注") @Size(max = 500) String note) {}

  // ================================================================ 输出

  /**
   * 一键刊登作业（输出：N 个发布任务的容器 + 总体进度）。
   *
   * <p>🔴 进度与结论**全部由服务端算好**：{@code progress} 的五个计数与百分比来自
   * 目标行的聚合，前端原样显示，**不自己重算**。
   */
  public record ListingPublishJobRow(
      @Schema(description = "作业 id") UUID jobId,
      @Schema(description = "源 SKU id") UUID sourceSkuId,
      @Schema(description = "内容来源：EDITOR_SUBMISSION / TEMPLATE") String contentSource,
      @Schema(description = "内容来源候选 id 列表") List<String> contentSourceIds,
      @Schema(description = "价格来源策略") String priceSource,
      @Schema(description = "🔴 价格金额快照；没有取到价时为空（不是 0）", nullable = true) BigDecimal priceAmount,
      @Schema(description = "🔴 价格币种快照", nullable = true) String priceCurrency,
      @Schema(description = "价格状态：SET / UNAVAILABLE / NOT_SET") String priceState,
      @Schema(description = "🔴 价格不可用的原因（前端原样展示）") String priceStateReason,
      @Schema(description = "库存来源策略") String stockSource,
      @Schema(description = "🔴 可售量快照；未取到时为空", nullable = true) BigDecimal stockQuantity,
      @Schema(description = "库存状态：SET / UNAVAILABLE / NOT_SET") String stockState,
      @Schema(description = "🔴 库存不可用的原因（前端原样展示）") String stockStateReason,
      @Schema(description = "DRAFT 已建任务未派发 / DISPATCHED 已派发（等平台回执） / CANCELLED 已撤销")
          String state,
      @Schema(description = "🔴 首选店铺（数据范围按它收敛）") UUID primaryShopId,
      @Schema(description = "幂等键") String idempotencyKey,
      @Schema(description = "备注") String note,
      @Schema(description = "🔴 进度（服务端算好）") ListingPublishProgress progress,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "最近更新时刻") OffsetDateTime updatedAt,
      @Schema(description = "派发时刻；仅 DISPATCHED 起非空", nullable = true) OffsetDateTime dispatchedAt,
      @Schema(description = "完成时刻；全部目标有结果时非空", nullable = true) OffsetDateTime completedAt) {}

  /**
   * 进度与总体结论。
   *
   * <p>🔴 {@code outcome} 是**聚合结论**：全部成功 COMPLETED、部分成功 PARTIAL、
   * 全部失败 FAILED、还没派发 NOT_DISPATCHED。作业表里**没有**这一列 ——
   * 它每次都从目标行算出来，所以它永远不会和目标行对不上。
   */
  public record ListingPublishProgress(
      @Schema(description = "目标总数") int total,
      @Schema(description = "草稿（已冻结内容，未提交平台）") int draft,
      @Schema(description = "审核中（已提交平台，等回执）") int inReview,
      @Schema(description = "成功") int succeeded,
      @Schema(description = "驳回（带原因）") int rejected,
      @Schema(description = "失败（带原因）") int failed,
      @Schema(description = "已出结果的比例（0~100，服务端算好）") int percent,
      @Schema(description = "总体结论：COMPLETED / PARTIAL / FAILED / NOT_DISPATCHED") String outcome) {}

  /**
   * 一个发布任务（输出：每个目标一个）。
   *
   * <p>🔴 {@code state=REJECTED} 时 {@code failureReason} 必填 —— 驳回必须带原因。
   */
  public record ListingPublishTargetRow(
      @Schema(description = "目标 id") UUID targetId,
      @Schema(description = "作业 id") UUID jobId,
      @Schema(description = "目标次序") int position,
      @Schema(description = "平台 id") UUID platformId,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "店铺 id") UUID shopId,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "站点 id") UUID marketplaceId,
      @Schema(description = "站点编码") String marketplaceCode,
      @Schema(description = "语言") String locale,
      @Schema(description = "类目路径") String categoryPath,
      @Schema(description = "DRAFT / SUBMITTED 审核中 / SUCCEEDED / REJECTED / FAILED / CANCELLED")
          String state,
      @Schema(description = "内容来源：EDITOR_SUBMISSION / TEMPLATE") String contentSource,
      @Schema(description = "🔴 内容来源 id（入队记录 id 或 模板 id）") UUID contentSourceId,
      @Schema(description = "🔴 那一刻冻结的内容快照") ListingPublishContentSnapshot contentSnapshot,
      @Schema(description = "平台商品 id（回执成功后回填）", nullable = true) String externalItemId,
      @Schema(description = "平台请求 id（2.5.1.2 核验用）") String platformRequestId,
      @Schema(description = "🔴 驳回 / 失败原因") String failureReason,
      @Schema(description = "🔴 各自独立重试的尝试次数") int attempts,
      @Schema(description = "下游 2.5.1.1 的回写任务 id", nullable = true) UUID platformOperationId,
      @Schema(description = "派发时刻", nullable = true) OffsetDateTime dispatchedAt,
      @Schema(description = "完成时刻", nullable = true) OffsetDateTime completedAt) {}

  /**
   * 🔴 内容快照：那一刻发的是什么。
   *
   * <p>写进 {@code listing_publish_job_target.content_snapshot}，**不可变**。
   * 事后源商品改了、模板改了、店铺改了，这里仍然是当时那一份。
   */
  public record ListingPublishContentSnapshot(
      @Schema(description = "标题", nullable = true) String title,
      @Schema(description = "卖点", nullable = true) List<String> bullets,
      @Schema(description = "描述", nullable = true) String description,
      @Schema(description = "图片（素材存储键）", nullable = true) List<String> images,
      @Schema(description = "类目路径", nullable = true) String categoryPath,
      @Schema(description = "平台类目 id", nullable = true) String platformCategoryId,
      @Schema(description = "🔴 内容来源：EDITOR_SUBMISSION / TEMPLATE") String sourceKind,
      @Schema(description = "🔴 来源 id（入队记录 id 或 模板 id）") UUID sourceId,
      @Schema(description = "🔴 来源说明（人能读：当时是从哪儿取的内容）") String sourceNote,
      @Schema(description = "🔴 求值明细（模板来源逐条列出：取到 / 取不到 / 为什么）")
          List<ListingPublishContentField> fields) {}

  /** 内容快照里的一栏（求值明细）。 */
  public record ListingPublishContentField(
      @Schema(description = "字段键") String field,
      @Schema(description = "取值来源：EDITOR_SUBMISSION / TEMPLATE") String sourceKind,
      @Schema(description = "求值状态：RESOLVED 取到 / EMPTY 取到但为空 / SOURCE_MISSING 引用不存在 / "
          + "UNAVAILABLE 来源不可用 / NOT_APPLICABLE 本次不适用") String status,
      @Schema(description = "🔴 归属模块；来源不可用时非空") String ownerModule,
      @Schema(description = "说明（人能读）") String note) {}

  /** 一个步骤。 */
  public record ListingPublishStepRow(
      @Schema(description = "步骤 id") UUID stepId,
      @Schema(description = "作业 id") UUID jobId,
      @Schema(description = "目标 id；作业级步骤为空", nullable = true) UUID targetId,
      @Schema(description = "步骤键") String stepKey,
      @Schema(description = "次序") int position,
      @Schema(description = "PENDING / RUNNING / SUCCEEDED / FAILED / SKIPPED / BLOCKED") String state,
      @Schema(description = "说明（人能读）") String detail,
      @Schema(description = "开始时刻", nullable = true) OffsetDateTime startedAt,
      @Schema(description = "完成时刻", nullable = true) OffsetDateTime finishedAt) {}

  /**
   * 派发结果。
   *
   * <p>🔴 逐目标列出，**不合并成一个总数**：某个目标失败时，其余目标照旧派发成功。
   */
  public record ListingPublishDispatchResponse(
      @Schema(description = "作业 id") UUID jobId,
      @Schema(description = "作业状态") String jobState,
      @Schema(description = "🔴 逐目标结果（各自成败）") List<ListingPublishDispatchEntry> entries,
      @Schema(description = "🔴 进度（服务端算好）") ListingPublishProgress progress) {}

  /** 派发结果里的一条（一个目标）。 */
  public record ListingPublishDispatchEntry(
      @Schema(description = "目标 id") UUID targetId,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "站点编码") String marketplaceCode,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "DISPATCHED 已派发 / FAILED 派发失败 / SKIPPED 跳过") String result,
      @Schema(description = "说明（人能读）") String note) {}

  /**
   * 平台回执请求（由平台适配器 / 2.5.1.2 核验通道调用）。
   *
   * <p>🔴 三个结果**分得开**：SUCCEEDED 成功 / REJECTED 平台驳回（内容问题，要改内容再发）/
   * FAILED 提交失败（技术问题，可以原样重试）。把「驳回」和「失败」合成一个失败，
   * 运营就分不清该改内容还是该重试 —— 所以这里是三值而不是布尔。
   */
  public record ListingPublishTargetReportRequest(
      @Schema(description = "🔴 回执结果：SUCCEEDED 成功 / REJECTED 平台驳回 / FAILED 提交失败")
          @NotBlank String result,
      @Schema(description = "🔴 驳回 / 失败原因（REJECTED / FAILED 时必填）") @Size(max = 2000)
          String failureReason,
      @Schema(description = "平台商品 id（成功时回填）") @Size(max = 128) String externalItemId,
      @Schema(description = "平台请求 id（2.5.1.2 核验用）") @Size(max = 128) String platformRequestId) {}
}
