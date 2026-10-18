package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 4.1.1.1 Listing 列表 的传输模型。
 *
 * <p>蓝图：「按店铺/站点/状态/负责人/类目/是否有映射/是否缺货筛选平台商品，展示内部 SKU、价格、库存、销量、评分、近 7
 * 日状态变化，支持批量改价改库存（跳 05/07）、批量下架、批量分配负责人。输入：筛选条件；用户数据范围（店铺维度）；
 * {@code listing} + {@code listing_variant} + 销量/库存投影。输出：分页列表（含内部 SKU 映射状态、缺货标记、审核状态）；批量操作任务；点击进入
 * 4.1.1.2。关系：上游 = 02 店铺、03 SKU、05 价格、07 库存；下游 = 4.1.1.2、4.2.1.1、4.3.1。<b>「未映射」和「缺货」是两个独立状态</b>，列表要能同时显示且可分别筛选。」
 *
 * <p>载荷一律用具名 {@code record}，不用 {@code Map<String,Object>}：Map 的值类型会被 springdoc 推成
 * object，codegen 出来是空对象 {@code {}}（9.6.1.3 已踩过）。
 *
 * <p>🔴 模型层就把三件事钉死，它们是最容易在赶工时被做丢的：
 *
 * <ol>
 *   <li><b>价格不在模型里</b>：全库不存在 listing 售价事实表（卷 05 整卷 0/19 未交付），
 *       所以 {@link ListingCapabilities} 里 price / sales / internalStock 三个维度显式
 *       {@code available=false} + {@code ownerModule} + {@code impact}。**没有 price 字段**——
 *       有字段就得填值，填 0 会被读成「这个商品卖 0 块」，填影子值就成了第二个价格事实来源。
 *   <li><b>「未映射」与「缺货」是两个字段，不是一个</b>：{@code mappingStatus}（UNMAPPED /
 *       PARTIAL / MAPPED）与 {@code platformStockState}（IN_STOCK / OUT_OF_STOCK / UNKNOWN）
 *       在每一行都成对出现，可以同时为真。
 *   <li><b>缺货标的是平台侧事实</b>：{@code platformStockState} 与 07 的内部可售量是<b>两个事实</b>
 *       （蓝图 4.1.1.3 加粗句「平台下架与内部下架是两个事实」是同一纪律），不得互相推导。
 * </ol>
 *
 * <p>🔴 金额与数量一律 {@link BigDecimal}，**没有 double**：评分与评论数是平台侧数值，
 * 直接参与跨平台比较与 4.5.1.1 表现监控，浮点不可接受。
 */
public final class ListingModels {
  private ListingModels() {}

  // ================================================================ 能力声明

  public record ListingCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  public record ListingCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false") List<ListingCapabilityDimension> dimensions,
      @Schema(description = "🔴 售价是否已接入：卷 05 整卷未交付，恒为 false，前端据此显示「价格能力未接入」")
          boolean priceAvailable,
      @Schema(description = "🔴 销量的归属模块") String priceOwnerModule,
      @Schema(description = "🔴 未接入价格时的界面措辞（前端原样展示，不要自己编）") String priceNote,
      @Schema(description = "平台商品状态取值") List<String> statuses,
      @Schema(description = "平台侧可售状态取值（缺货标记的来源）") List<String> platformStockStates,
      @Schema(description = "审核状态取值") List<String> reviewStatuses,
      @Schema(description = "🔴 映射状态取值：UNMAPPED / PARTIAL / MAPPED（列表级聚合，不是单变体值）")
          List<String> mappingStatuses,
      @Schema(description = "单变体映射状态取值") List<String> variantMappingStatuses,
      @Schema(description = "批量操作取值") List<String> batchActions,
      @Schema(description = "🔴「未映射与缺货是两个独立状态」的说明") String independentStateNote,
      @Schema(description = "上游依赖说明") String upstreamNote) {}

  // ================================================================ 列表

  /**
   * 列表的一行。
   *
   * <p>🔴 两个独立状态成对出现：{@code mappingStatus} 答「平台变体有没有落到内部 SKU」，
   * {@code platformStockState} 答「平台侧还能不能买」。它们可以同时为真 —— 一个已映射但
   * 平台缺货的商品是最常见的漏单场景，把两者合成一个字段就看不见它了。
   */
  public record ListingRow(
      @Schema(description = "listing id") UUID id,
      @Schema(description = "上游 02 店铺 id") UUID shopId,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "店铺名") String shopName,
      @Schema(description = "平台 id") UUID platformId,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "上游 02 站点 id；为空表示未归站点") UUID marketplaceId,
      @Schema(description = "站点编码；为空表示未归站点") String marketplaceCode,
      @Schema(description = "🔴 平台商品 ID 原文（ASIN / item_id），不折叠大小写") String externalItemId,
      @Schema(description = "平台标题原文") String title,
      @Schema(description = "平台类目路径原文") String categoryPath,
      @Schema(description = "平台类目 id") String platformCategoryId,
      @Schema(description = "平台商品状态") String status,
      @Schema(description = "审核状态") String reviewStatus,
      @Schema(description = "平台驳回原因 / 审核备注") String reviewMessage,
      @Schema(description = "负责人 id（创建时的归属快照）") UUID ownerId,
      @Schema(description = "平台评分；平台未给则为 null") BigDecimal rating,
      @Schema(description = "平台评论数") BigDecimal ratingCount,
      @Schema(description = "币种；仅作展示标签，本模块不据此做任何换算") String currency,
      @Schema(description = "🔴 缺货标记（平台侧事实）：IN_STOCK / OUT_OF_STOCK / UNKNOWN")
          String platformStockState,
      @Schema(description = "平台侧可售量；UNKNOWN 时为 null") BigDecimal platformStockQty,
      @Schema(description = "🔴 内部 SKU 映射状态：UNMAPPED / PARTIAL / MAPPED") String mappingStatus,
      @Schema(description = "平台变体条数") int variantCount,
      @Schema(description = "已映射到内部 SKU 的变体条数") int mappedVariantCount,
      @Schema(description = "变体上的内部 SKU 逻辑 id（跨服务，03 拥有）；未映射时为 null") List<UUID> internalSkuIds,
      @Schema(description = "🔴 近 7 日状态变化次数") int statusChangeCount7d,
      @Schema(description = "🔴 近 7 日状态变化明细（新 → 旧）") List<ListingStatusChange> recentStatusChanges,
      @Schema(description = "上架时刻") OffsetDateTime launchedAt,
      @Schema(description = "最近一次平台同步时刻") OffsetDateTime lastSyncedAt,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  /** 近 7 日状态变化的一条。来源固定 MANUAL / PLATFORM，服务层写不出别的值。 */
  public record ListingStatusChange(
      @Schema(description = "原状态；首次登记为 null") String fromStatus,
      @Schema(description = "新状态") String toStatus,
      @Schema(description = "来源：MANUAL 人工 / PLATFORM 平台回流（4.3.2.2）") String source,
      @Schema(description = "发生时刻") OffsetDateTime occurredAt) {}

  public record ListingPage(
      @Schema(description = "当前页数据") List<ListingRow> items,
      @Schema(description = "符合筛选条件的总条数（不受分页影响）") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size,
      @Schema(description = "🔴 未接入的维度名；筛选条件命中它们会被显式拒绝而不是静默忽略")
          List<String> rejectedFilters) {}

  // ================================================================ 批量操作

  public record ListingBatchRequest(
      @Schema(description = "ASSIGN_OWNER 批量分配负责人 / DELIST 批量下架 / REPRICE 批量改价 / RESTOCK 批量改库存")
          @NotBlank
          String action,
      @Schema(description = "目标 listing 集合（UUID）") @NotEmpty List<UUID> listingIds,
      @Schema(description = "ASSIGN_OWNER 的目标负责人 id") UUID ownerId,
      @Schema(description = "REPRICE 的目标价；本模块显式拒绝（归 05 定价）") BigDecimal price,
      @Schema(description = "RESTOCK 的目标库存；本模块显式拒绝（归 07 库存）") BigDecimal stockQuantity) {}

  /**
   * 批量操作结果。
   *
   * <p>🔴 被拒绝的批量操作**同样返回**（而不是 422 掉整次请求）：用户点了改价、系统没做，
   * 这是一条必须回显的事实，且已落 {@code listing_batch_task} 留痕。
   */
  public record ListingBatchResult(
      @Schema(description = "批量操作任务 id") UUID taskId,
      @Schema(description = "动作") String action,
      @Schema(description = "SUCCEEDED 已执行 / REJECTED 显式拒绝（归 05 / 07）") String outcome,
      @Schema(description = "请求条数") int requestedCount,
      @Schema(description = "实际影响条数") int affectedCount,
      @Schema(description = "被跳过的条数（不在数据范围内）") int skipCount,
      @Schema(description = "被跳过的 listing id") List<UUID> skippedIds,
      @Schema(description = "🔴 拒绝 / 说明理由；前端原样展示") String reason,
      @Schema(description = "归属模块；REJECTED 时非空") String ownerModule,
      @Schema(description = "任务创建时刻") OffsetDateTime createdAt) {}

  // ================================================================ 登记（02 同步入口）

  public record SaveListingRequest(
      @Schema(description = "为空表示新建；非空表示改这条（乐观锁）") UUID id,
      @Schema(description = "上游 02 店铺 id") @NotNull UUID shopId,
      @Schema(description = "上游 02 站点 id") UUID marketplaceId,
      @Schema(description = "🔴 平台商品 ID 原文") @NotBlank @Size(max = 128) String externalItemId,
      @Schema(description = "平台标题原文") @NotBlank @Size(max = 500) String title,
      @Schema(description = "平台类目路径原文") @Size(max = 500) String categoryPath,
      @Schema(description = "平台类目 id") @Size(max = 128) String platformCategoryId,
      @Schema(description = "平台商品状态；为空按 DRAFT 建") String status,
      @Schema(description = "审核状态；为空按 NOT_SUBMITTED 建") String reviewStatus,
      @Schema(description = "平台驳回原因 / 审核备注") @Size(max = 1000) String reviewMessage,
      @Schema(description = "🔴 平台侧可售状态；缺货标记的来源") String platformStockState,
      @Schema(description = "平台侧可售量") @DecimalMin(value = "0") BigDecimal platformStockQty,
      @Schema(description = "平台评分") @DecimalMin(value = "0") BigDecimal rating,
      @Schema(description = "平台评论数") @DecimalMin(value = "0") BigDecimal ratingCount,
      @Schema(description = "币种；仅作展示标签") @Size(min = 3, max = 3) String currency,
      @Schema(description = "上架时刻") OffsetDateTime launchedAt,
      @Schema(description = "最近一次平台同步时刻") OffsetDateTime lastSyncedAt,
      @Schema(description = "变体集合；同一请求内按 externalVariantId 幂等") @Valid List<SaveVariantRequest> variants,
      @Schema(description = "乐观锁版本") Long version) {}

  public record SaveVariantRequest(
      @Schema(description = "为空表示新建；非空表示改这条") UUID id,
      @Schema(description = "平台变体 ID 原文") @NotBlank @Size(max = 128) String externalVariantId,
      @Schema(description = "🔴 映射状态：UNMAPPED / MAPPED / BUNDLE；为空按 UNMAPPED 建") String mappingStatus,
      @Schema(description = "🔴 内部 SKU 逻辑 id（03 catalog 拥有，不跨库建 FK）；未映射时为 null")
          UUID internalSkuId,
      @Schema(description = "组件数量（仅 BUNDLE 有意义，必须 > 0）") @DecimalMin(value = "0.000001") BigDecimal componentQuantity,
      @Schema(description = "平台侧变体属性原文（json 对象字符串）") String attributes) {}

  @Schema(description = "平台筛选维度取值")
  public record ListingFilterOptions(
      @Schema(description = "平台商品状态取值") @Valid List<String> statuses,
      @Schema(description = "平台侧可售状态取值") @Valid List<String> platformStockStates,
      @Schema(description = "审核状态取值") @Valid List<String> reviewStatuses,
      @Schema(description = "映射状态取值") @Valid List<String> mappingStatuses,
      @Schema(description = "可排序字段") @Valid List<String> sortFields,
      @Schema(description = "每页条数上限") @Min(1) @Max(200) int maxSize) {}
}
