package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 4.4.2.1 商品认领 的传输模型。
 *
 * <p>蓝图：「把已在 A 店铺的 listing 认领到 B 店铺（跨店搬家），复用原商品内容与映射，生成 B 店铺的新 listing 草稿。输入：源
 * listing；目标店铺与站点；保留/重写的内容字段选择。输出：目标店铺的 listing 草稿 + 待发布任务。关系：上游 = 4.1.1.2；下游 =
 * 4.3.1.1。<b>新店铺 listing 是全新平台资源</b>，需重新过平台审核；<b>源 listing 不受影响</b>。」
 *
 * <p>载荷一律用具名 {@code record}，不用 {@code Map<String,Object>}：Map 的值类型会被 springdoc 推成 object，codegen 出来是空对象
 * {@code {}}（9.6.1.3 已踩过）。
 *
 * <p>🔴 模型层就把三条边界钉死，它们是本模块最容易在赶工时被做丢的：
 *
 * <ol>
 *   <li><b>请求里没有"改源"这个形状</b>：{@link ClaimListingRequest} 里只有源 listing 的<b>只读引用</b>
 *       （{@code sourceListingId}），没有任何"修改源 listing"的字段。认领是一次<b>创建</b>动作，
 *       不是一个"搬走"动作 —— 源商品留在原店铺，货权与历史都不动。
 *   <li><b>目标恒为草稿态</b>：请求里<b>没有</b> status / reviewStatus / 平台商品 ID / 评分 / 币种 这些字段。
 *       目标 listing 的 {@code status='DRAFT'}、{@code review_status='NOT_SUBMITTED'} 由服务层写死，
 *       并且由 V190 的 DB 触发器在认领记录落库时复核一次。
 *   <li><b>没有价格 / 库存 / 评分入口</b>：售价归 05 定价（整卷未交付）、内部可售量归 07 库存、
 *       评分是平台侧事实且新商品在平台上还不存在。三者在能力声明里 available=false + ownerModule。
 * </ol>
 */
public final class ListingClaimModels {
  private ListingClaimModels() {}

  /**
   * 🔴 可选的内容字段集。
   *
   * <p>与 V175 {@code listing_content_snapshot} 与 V171 {@code listing} 的列<b>逐字对齐</b>：
   * TITLE / BULLETS / IMAGES / DESCRIPTION / A_PLUS / VIDEO_URL 来自内容快照，
   * CATEGORY_PATH / PLATFORM_CATEGORY_ID 来自 listing 行。
   *
   * <p><b>这里没有 PRICE / STOCK / RATING / CURRENCY</b>：售价归 05、内部可售量归 07，
   * 评分与币种是源商品在 A 店铺的平台事实，搬到 B 店铺后它们一个都不成立。
   */
  public static final List<String> FIELDS =
      List.of(
          "TITLE",
          "BULLETS",
          "IMAGES",
          "DESCRIPTION",
          "A_PLUS",
          "VIDEO_URL",
          "CATEGORY_PATH",
          "PLATFORM_CATEGORY_ID",
          "CATEGORY_ATTRIBUTES",
          "COMPLIANCE_STATEMENTS");

  /** 字段决策的三个取值：保留 / 重写 / 跳过。 */
  public static final List<String> DECISIONS = List.of("KEEP", "REWRITE", "SKIP");

  /** 认领记录的取值域：DRAFT = 目标草稿待发布；PUBLISHED = 目标已离开草稿态（不代表平台已批准）。 */
  public static final List<String> CLAIM_STATES = List.of("DRAFT", "PUBLISHED");

  // ================================================================ 能力声明

  public record ListingClaimCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  public record ListingClaimCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false") List<ListingClaimCapabilityDimension> dimensions,
      @Schema(description = "可参与保留 / 重写的字段集") List<String> fields,
      @Schema(description = "字段决策取值域") List<String> decisions,
      @Schema(description = "认领状态取值域") List<String> claimStates,
      @Schema(description = "🔴 是否保证源 listing 不受影响") boolean sourceUntouched,
      @Schema(description = "🔴 源 listing 不受影响的说明（前端原样展示）") String sourceUntouchedNote,
      @Schema(description = "🔴 目标是否恒为草稿态（尚未过平台审核）") boolean draftOnly,
      @Schema(description = "🔴 草稿态的说明（前端原样展示）") String draftNote,
      @Schema(description = "待发布任务是否复用 4.3.1.2 的既有队列") boolean pendingPublishTask,
      @Schema(description = "待发布任务的说明") String publishTaskNote,
      @Schema(description = "🔴 售价是否已接入（恒为 false）") boolean priceAvailable,
      @Schema(description = "🔴 售价的归属模块") String priceOwnerModule,
      @Schema(description = "🔴 未接入售价时的界面措辞") String priceNote,
      @Schema(description = "上游依赖说明") String upstreamNote) {}

  // ================================================================ 请求

  /**
   * 逐字段的保留 / 重写决策。
   *
   * @param field 字段键，取值域见 {@link ListingClaimModels#FIELDS}
   * @param mode {@code KEEP}（复用源的值）/ {@code REWRITE}（用 {@code value} 重写）/ {@code SKIP}（不写入目标草稿）
   * @param value 重写值；{@code REWRITE} 时必填（且非空），其余模式忽略
   */
  public record ListingClaimFieldDecision(
      @Schema(description = "字段键：TITLE / BULLETS / IMAGES / DESCRIPTION / A_PLUS / VIDEO_URL / CATEGORY_PATH / PLATFORM_CATEGORY_ID / CATEGORY_ATTRIBUTES / COMPLIANCE_STATEMENTS")
          @NotBlank
          String field,
      @Schema(description = "决策：KEEP 复用源值 / REWRITE 重写 / SKIP 不写入目标草稿") @NotBlank String mode,
      @Schema(description = "重写值（仅 REWRITE 使用；JSON 字段填 JSON 原文）") String value) {}

  /**
   * 认领请求。
   *
   * <p>🔴 请求体里<b>没有</b>源 listing 的可写字段、<b>没有</b>目标 listing 的状态 / 审核状态 /
   * 平台商品 ID / 评分 / 币种 —— 那些由服务层按"新店铺全新平台资源"的规则写死。
   */
  public record ClaimListingRequest(
      @Schema(description = "🔴 源 listing id（只读引用；本模块不会修改它）") @NotNull UUID sourceListingId,
      @Schema(description = "目标店铺 id（必须与源店铺不同）") @NotNull UUID targetShopId,
      @Schema(description = "目标站点 id（必须已被目标店铺启用）") @NotNull UUID targetMarketplaceId,
      @Schema(description = "复用哪一份源内容快照的语言；不给则用源当前快照的语言")
          @Size(max = 16)
          String language,
      @Schema(description = "逐字段的保留 / 重写决策") @NotEmpty List<@Valid ListingClaimFieldDecision> fieldDecisions,
      @Schema(description = "备注") @Size(max = 500) String note) {}

  // ================================================================ 结果

  /**
   * 逐字段决策的结果行。
   *
   * @param sourceOrigin 源值来自哪里：{@code PLATFORM_SNAPSHOT}（平台内容快照）/ {@code LISTING}（listing 行）/ {@code NOT_PRESENT}（源侧没有这个字段）
   * @param sourceValue 源值原文（JSON 字段存 JSON 原文；不翻译、不裁剪）
   * @param targetValue 落到目标草稿上的值（{@code SKIP} 时为 null）
   */
  public record ListingClaimFieldRow(
      @Schema(description = "字段键") String field,
      @Schema(description = "决策：KEEP / REWRITE / SKIP") String decision,
      @Schema(description = "源值来源：PLATFORM_SNAPSHOT / LISTING / NOT_PRESENT") String sourceOrigin,
      @Schema(description = "源值原文") String sourceValue,
      @Schema(description = "目标值原文（SKIP 时为 null）") String targetValue,
      @Schema(description = "说明（例如为什么跳过）") String note) {}

  /**
   * 一次认领的结果行。
   *
   * <p>🔴 源侧与目标侧的字段**成对出现**，运营一眼能看出「源没动、目标是新草稿」。
   */
  public record ListingClaimRow(
      @Schema(description = "认领记录 id") UUID claimId,
      @Schema(description = "源 listing id") UUID sourceListingId,
      @Schema(description = "源 listing 的平台商品 ID 原文") String sourceExternalItemId,
      @Schema(description = "源 listing 标题") String sourceTitle,
      @Schema(description = "源 listing 所属店铺编码") String sourceShopCode,
      @Schema(description = "源 listing 所属店铺名") String sourceShopName,
      @Schema(description = "🔴 源 listing 所属站点编码（可能为空）") String sourceMarketplaceCode,
      @Schema(description = "🔴 认领时记录的源 listing 状态（认领前后应逐字相同）") String sourceStatus,
      @Schema(description = "🔴 源 listing 当前状态（与认领时记录的比对结果）") String sourceCurrentStatus,
      @Schema(description = "🔴 认领时记录的源 listing 审核状态") String sourceReviewStatus,
      @Schema(description = "🔴 源 listing 当前审核状态（与认领时记录的比对结果）") String sourceCurrentReviewStatus,
      @Schema(description = "🔴 认领时记录的源 listing 版本号") long sourceVersion,
      @Schema(description = "🔴 源 listing 当前版本号（与认领时记录的比对结果）") long sourceCurrentVersion,
      @Schema(description = "目标 listing id（本模块新建的草稿）") UUID targetListingId,
      @Schema(description = "🔴 目标 listing 的平台商品 ID 保留位（草稿未发布，平台尚未分配）")
          String targetExternalItemId,
      @Schema(description = "🔴 目标 listing 状态，恒为 DRAFT（尚未过平台审核）") String targetStatus,
      @Schema(description = "🔴 目标 listing 审核状态，恒为 NOT_SUBMITTED（尚未提交平台审核）")
          String targetReviewStatus,
      @Schema(description = "🔴 目标 listing 的平台可售状态，恒为 UNKNOWN（平台上还不存在）")
          String targetPlatformStockState,
      @Schema(description = "目标 listing 标题") String targetTitle,
      @Schema(description = "目标店铺编码") String targetShopCode,
      @Schema(description = "目标店铺名") String targetShopName,
      @Schema(description = "目标站点编码") String targetMarketplaceCode,
      @Schema(description = "🔴 认领状态：DRAFT 目标草稿待发布 / PUBLISHED 目标已离开草稿态（不代表平台已批准）")
          String state,
      @Schema(description = "复制的变体条数") int variantCount,
      @Schema(description = "其中已映射到内部 SKU 的条数") int mappedVariantCount,
      @Schema(description = "待发布批次 id（复用 4.3.1.2 的 V183 批次）") UUID publishBatchId,
      @Schema(description = "待发布任务 id（复用 4.3.1.2 的 V183 任务）") UUID publishTaskId,
      @Schema(description = "操作人") UUID actorId,
      @Schema(description = "备注") String note,
      @Schema(description = "创建时间") OffsetDateTime createdAt,
      @Schema(description = "更新时间") OffsetDateTime updatedAt,
      @Schema(description = "逐字段决策") List<ListingClaimFieldRow> fields) {}
}
