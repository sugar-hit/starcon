package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 4.2.1.2 批量编辑 的传输模型。
 *
 * <p>蓝图：「对一批 listing 执行统一的字段修改（追加/替换/正则替换标题、批量换图、统一加卖点、批量改类目），支持按平台差异化模板与操作预览（改前 vs 改后）。输入：目标
 * listing 集合；批量操作定义；预览确认。输出：批量修改草稿（待发布）；修改记录。关系：上游 = 4.1.1.1；下游 =
 * 4.3.1.2。<b>批量编辑只改草稿不直接改线上</b>，必须走发布流程——这是"误改全店标题"事故的唯一防线。」
 *
 * <p>载荷一律用具名 {@code record}，不用 {@code Map<String,Object>}：Map 的值类型会被 springdoc 推成 object，
 * codegen 出来是空对象 {@code {}}（9.6.1.3 已踩过）。
 *
 * <p>🔴 模型层就把三件事钉死：
 *
 * <ol>
 *   <li><b>没有"直接保存到线上"的入口</b>：本模块只有一个确认动作 {@code confirm}，它的产出是
 *       {@link ListingContentDraftRow}（草稿）。不存在任何"改线上"的方法或参数 —— 防线首先体现在
 *       API 形状上，其次才是 DB 触发器。
 *   <li><b>预览与执行共用同一批 before/after</b>：{@link ListingBulkChangeRow} 同时是预览输出与修改记录，
 *       {@link BulkEditPreviewResponse} 与 {@link ListingBulkEditOperation} 引用的是同一个 record。
 *   <li><b>没有金额字段</b>：批量编辑的六件事里没有改价（归 05 定价）。有金额字段就得填值，
 *       填 0 会被读成「批量把全店商品改成 0 元」。列表用 {@link BigDecimal} 的地方只有被刻意排除的
 *       说明性计数，且不参与任何金额计算。
 * </ol>
 */
public final class ListingBulkEditModels {
  private ListingBulkEditModels() {}

  // ================================================================ 能力声明

  public record BulkEditCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "🔴 该维度的**线上**基线是否可读（false 时 before 只能来自已有草稿）")
          boolean onlineBaselineAvailable,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  public record BulkEditCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false") List<BulkEditCapabilityDimension> dimensions,
      @Schema(description = "六种批量操作取值") List<String> operationTypes,
      @Schema(description = "被改字段取值") List<String> fields,
      @Schema(description = "逐条结果取值") List<String> outcomes,
      @Schema(description = "草稿状态取值") List<String> draftStates,
      @Schema(description = "🔴 草稿态与线上态分离的说明；前端据此禁用任何「直接上线」按钮")
          String draftIsolationNote,
      @Schema(description = "🔴 卖点 / 图片线上基线未接入时的界面措辞（前端原样展示，不要自己编）")
          String onlineBaselineNote,
      @Schema(description = "上游 / 下游说明") String relationNote) {}

  // ================================================================ 批量操作定义（输入）

  /**
   * 批量操作参数。
   *
   * <p>🔴 六种操作共用一个载荷而不是六个载荷：它们的参数形状确实不同（find/replacement/bullets/…），
   * 拆成六个 record 会让端点、校验与模型膨胀六倍，而实际差异只落在"用哪几个字段"上，
   * 校验规则可以一条式地写成「用到了就必须非空」。按操作类型取字段的规则在服务层显式给出。
   */
  public record BulkEditParams(
      @Schema(description = "TITLE_APPEND 追加到标题末尾的文本") @Size(max = 500) String titleSuffix,
      @Schema(description = "TITLE_REPLACE 要被替换的**字面量**片段（不做正则解释）") @Size(max = 200) String find,
      @Schema(description = "TITLE_REPLACE / TITLE_REGEX_REPLACE 的替换文本") @Size(max = 500) String replace,
      @Schema(description = "🔴 TITLE_REGEX_REPLACE 的正则；带嵌套量词 / 反向引用的模式会被直接拒（ReDoS）")
          @Size(max = 200) String pattern,
      @Schema(description = "🔴 TITLE_REGEX_REPLACE 的替换串；允许 $1 这样的分组引用") @Size(max = 500) String replacement,
      @Schema(description = "REPLACE_IMAGES 的图片 URL / 存储键，整组替换") @Valid List<@Size(max = 1024) String> images,
      @Schema(description = "BULLET_APPEND 要追加的卖点") @Valid List<@Size(max = 500) String> bullets,
      @Schema(description = "REPLACE_CATEGORY 的平台类目路径原文") @Size(max = 500) String categoryPath,
      @Schema(description = "REPLACE_CATEGORY 的平台类目 id") @Size(max = 128) String platformCategoryId) {}

  /** 批量操作请求：预览与确认走的是同一份定义。 */
  public record BulkEditRequest(
      @Schema(description = "目标 listing 集合（UUID）；跨租户 / 不在数据范围内的会被自动切分并逐条列出")
          @NotEmpty
          List<UUID> listingIds,
      @Schema(description = "平台差异化模板 id；给了就以模板的 operationType / params / 上限为准")
          UUID templateId,
      @Schema(description = "TITLE_APPEND / TITLE_REPLACE / TITLE_REGEX_REPLACE / REPLACE_IMAGES / "
          + "BULLET_APPEND / REPLACE_CATEGORY；与 templateId 同时给时以模板为准")
          String operationType,
      @Schema(description = "操作参数") @Valid BulkEditParams params,
      @Schema(description = "🔴 标题长度上限；不给时取模板值，再不给时取平台列上限 500。**超限是显式跳过，不是静默截断**")
          @Min(1)
          @Max(500)
          Integer titleMaxLength,
      @Schema(description = "图片条数上限；不给时取模板值，再不给时取 9") @Min(1) @Max(20) Integer imageMaxCount,
      @Schema(description = "卖点条数上限；不给时取模板值，再不给时取 10") @Min(1) @Max(20)
          Integer bulletMaxCount,
      @Schema(description = "备注，落进修改记录便于回溯「当时为什么这么批量改」") @Size(max = 500) String note) {}

  // ================================================================ 预览（改前 vs 改后）

  /**
   * 🔴 逐条改前/改后。预览与修改记录是同一条记录的两个视图。
   *
   * <p>{@code changed=false} 与 {@code outcome} 永远自洽（DB 有 CHECK 钉住）：声称改了却前后一字不差，
   * 或者声称没改却前后不同，两种都进不了库。
   */
  public record ListingBulkChangeRow(
      @Schema(description = "listing id") UUID listingId,
      @Schema(description = "平台商品 ID 原文；预览要让人认得出是哪一条") String externalItemId,
      @Schema(description = "被改字段：TITLE / BULLETS / IMAGES / CATEGORY_PATH / PLATFORM_CATEGORY_ID")
          String field,
      @Schema(description = "🔴 改前值（标题 / 类目取自线上 listing；卖点与图片取自已有草稿）") String beforeValue,
      @Schema(description = "🔴 改后值（草稿里将被写入的提议值）") String afterValue,
      @Schema(description = "改后是否与改前逐字不同") boolean changed,
      @Schema(description = "APPLIED / NO_CHANGE / SKIPPED_LIMIT / SKIPPED_NO_BASELINE") String outcome,
      @Schema(description = "🔴 这条为什么是这个结果；前端原样展示") String reason) {}

  /** 按平台差异化的切分结果：一批目标可能跨多个平台，每个平台走各自的模板与上限。 */
  public record BulkEditPlatformSplit(
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "平台名") String platformName,
      @Schema(description = "本段用的模板 id；未套模板为 null") UUID templateId,
      @Schema(description = "本段的目标 listing") int listingCount,
      @Schema(description = "本段采用的标题长度上限") int titleMaxLength,
      @Schema(description = "本段是否被排除；被排除时 reason 非空") boolean excluded,
      @Schema(description = "排除理由；未排除时为空串") String reason) {}

  public record BulkEditPreviewResponse(
      @Schema(description = "本次预览的操作 id（确认时凭它推进）") UUID operationId,
      @Schema(description = "PREVIEWED（仅预览，未改任何东西）/ APPLIED / DISCARDED") String state,
      @Schema(description = "本次使用的操作类型") String operationType,
      @Schema(description = "请求条数") int requestedCount,
      @Schema(description = "🔴 请求里**不在当前租户数据范围内**的 listing（跨租户与不存在都落这里）")
          List<UUID> outOfScopeIds,
      @Schema(description = "🔴 按平台差异化模板的切分") List<BulkEditPlatformSplit> platformSplits,
      @Schema(description = "🔴 逐条改前/改后；确认时写入草稿的就是这批值") List<ListingBulkChangeRow> changes,
      @Schema(description = "🔴 确认前线上**不会被改**；确认后改的也只是草稿") String draftIsolationNote) {}

  // ================================================================ 确认 / 修改记录

  /** 预览确认（输入三）。凭 operationId 推进，凭 id 推进避免"确认的其实是另一份定义"。 */
  public record BulkEditConfirmRequest(
      @Schema(description = "要确认的预览操作 id") @NotNull UUID operationId,
      @Schema(description = "备注") @Size(max = 500) String note) {}

  /** 修改记录（输出二）。 */
  public record ListingBulkEditOperation(
      @Schema(description = "操作 id") UUID operationId,
      @Schema(description = "操作类型") String operationType,
      @Schema(description = "所用平台差异化模板；未套模板为 null") UUID templateId,
      @Schema(description = "PREVIEWED 仅预览 / APPLIED 已写入草稿 / DISCARDED 已放弃") String state,
      @Schema(description = "请求条数") int requestedCount,
      @Schema(description = "实际写入草稿的条数") int appliedCount,
      @Schema(description = "逐条被跳过的条数") int skippedCount,
      @Schema(description = "目标集合快照") @Valid List<UUID> listingIds,
      @Schema(description = "🔴 按平台差异化模板的切分") List<BulkEditPlatformSplit> platformSplits,
      @Schema(description = "🔴 逐条改前/改后（预览看到的与落库的是同一批）") List<ListingBulkChangeRow> changes,
      @Schema(description = "操作人") UUID actorId,
      @Schema(description = "备注") String note,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "确认落草稿的时刻；仅 APPLIED 非空") OffsetDateTime appliedAt) {}

  // ================================================================ 草稿（输出一：待发布）

  /**
   * 🔴 批量修改草稿（待发布）。
   *
   * <p>{@code baseVersion} 与 {@code stale} 是发布前必须能回答的两个问题：「这份草稿是从哪个线上版本
   * 派生的」「线上在这之后被改过没有」。答不上来就发布，等于用旧快照覆盖新线上值。
   */
  public record ListingContentDraftRow(
      @Schema(description = "草稿 id") UUID draftId,
      @Schema(description = "listing id") UUID listingId,
      @Schema(description = "平台商品 ID 原文") String externalItemId,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "拟改标题；null 表示本次没拟改标题") String title,
      @Schema(description = "拟改卖点；null 表示本次没拟改卖点") @Valid List<String> bullets,
      @Schema(description = "拟改图片；null 表示本次没拟改图片") @Valid List<String> images,
      @Schema(description = "拟改类目路径；null 表示本次没拟改类目") String categoryPath,
      @Schema(description = "拟改平台类目 id；null 表示本次没拟改类目 id") String platformCategoryId,
      @Schema(description = "🔴 本草稿拟改过的字段；不在这里面的字段发布时保持线上一致，绝不被覆盖")
          @Valid List<String> touchedFields,
      @Schema(description = "线上标题原值（草稿要能和线上并排看）") String liveTitle,
      @Schema(description = "线上类目路径原值") String liveCategoryPath,
      @Schema(description = "DRAFT 待发布 / PUBLISHED 已发布 / DISCARDED 已废弃") String state,
      @Schema(description = "草稿派生的线上版本") long baseVersion,
      @Schema(description = "🔴 线上是否在草稿生成后被改过（true 时发布前必须先复核）") boolean stale,
      @Schema(description = "最后改这份草稿的批量操作 id") UUID lastOperationId,
      @Schema(description = "草稿生成时刻") OffsetDateTime createdAt,
      @Schema(description = "草稿最近更新时刻") OffsetDateTime updatedAt) {}

  // ================================================================ 平台差异化模板

  public record BulkTemplateRequest(
      @Schema(description = "平台 id（channel V1 全局码表）") @NotNull UUID platformId,
      @Schema(description = "模板编码；同平台内唯一") @NotBlank @Size(max = 64) String code,
      @Schema(description = "模板名") @NotBlank @Size(max = 200) String name,
      @Schema(description = "六种批量操作之一") @NotBlank String operationType,
      @Schema(description = "操作参数") @Valid BulkEditParams params,
      @Schema(description = "🔴 该平台标题长度上限（按平台配，不写死在服务层）") @Min(1) @Max(500)
          Integer titleMaxLength,
      @Schema(description = "该平台图片条数上限") @Min(1) @Max(20) Integer imageMaxCount,
      @Schema(description = "该平台卖点数上限") @Min(1) @Max(20) Integer bulletMaxCount) {}

  public record BulkTemplateRow(
      @Schema(description = "模板 id") UUID id,
      @Schema(description = "平台 id") UUID platformId,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "模板编码") String code,
      @Schema(description = "模板名") String name,
      @Schema(description = "批量操作类型") String operationType,
      @Schema(description = "操作参数") BulkEditParams params,
      @Schema(description = "标题长度上限") int titleMaxLength,
      @Schema(description = "图片条数上限") int imageMaxCount,
      @Schema(description = "卖点数上限") int bulletMaxCount,
      @Schema(description = "是否启用") boolean enabled,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  /** 供前端提交前的预检使用：正则替换的安全裁决。被拒时把原因原样带回，不静默降级成字面量替换。 */
  public record RegexSafetyVerdict(
      @Schema(description = "模式是否通过 ReDoS 检查") boolean safe,
      @Schema(description = "未通过的原因；通过时为空串") String reason) {}
}
