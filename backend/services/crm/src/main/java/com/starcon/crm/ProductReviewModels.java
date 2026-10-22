package com.starcon.crm;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 15.2.1.1 评价列表的传输模型。
 *
 * <p>蓝图 15 卷 15.2.1.1 原文：
 *
 * <blockquote>
 *
 * 职责：汇总各店铺评价：星级、内容、变体、图片、平台时间、关联订单、是否已回复、店铺评分汇总，支持筛选（星级、含图/含视频、未回复、时间范围）与导出。 输入：评价同步；筛选条件。 输出：评价列表 + 评分统计。 关系：上游 = 2 同步；下游 = 15.2.1.2 回复、15.2.2.1 索评。 <b>评分趋势比单条评价更重要</b>：连续下滑要先查是产品问题还是履约问题。
 *
 * </blockquote>
 *
 * <p><b>建模纪律</b>：
 *
 * <ul>
 *   <li><b>「评分统计」是派生读，不是字段</b>：{@link CrmProductReviewStats} 里的均分、分布、
 *       趋势与下滑维度全部由服务层**现算**（显式 SQL 聚合）。本模块**不建**「店铺评分汇总表」——
 *       落库的汇总会变成第二份事实，明细改了汇总没改时，「平均分」与「趋势」会互相矛盾，
 *       而没人说得清哪一份是真的。
 *   <li><b>趋势的时间轴是「平台评价时刻」</b>：{@code platformReviewedAt} 是分桶的轴，
 *       {@code registeredAt} 只是「我们什么时候知道这条评价」。用登记时刻分桶会把
 *       「今天集中补录了一批上个月的老评价」显示成「今天的评分突然崩了」—— 那是假下滑。
 *   <li><b>「连续下滑」要能定位到维度</b>：{@link CrmProductReviewDecline} 把店铺 / 商品 /
 *       变体三个维度**分别**比近窗与前窗，并给出 {@code continuous}（三窗单调下滑），
 *       否则客服看到「评分在跌」却不知道该查产品还是履约。
 *   <li><b>「是否已回复」是只读属性</b>：下游 15.2.1.2 评价回复**未交付** ⇒ 本模块
 *       <b>不记录回复正文、不记录平台回执、不编造下游 id</b>；{@code replied} 只表示
 *       「平台侧是否已有卖家公开回复」（登记时由运营核对填写）。
 *   <li><b>数量一律 BigDecimal</b>：均分是 numeric 聚合的产物，禁浮点；
 *       时间一律带偏移量的 ISO-8601 字符串（用 {@code Timestamp.valueOf(LocalDateTime)}
 *       会在东八区静默偏 −28800 秒，本仓库反复踩过）。
 * </ul>
 */
public final class ProductReviewModels {

  private ProductReviewModels() {}

  // ------------------------------------------------------------ 输入

  /**
   * 登记一条评价。
   *
   * <p>⚠️ 平台评价同步适配器**未交付**（channel 的适配层只有 OAuth 适配器），
   * 所以 {@code source} **当前只允许 {@code MANUAL_ENTRY}**。同步端点单独走
   * {@code POST /crm/reviews/sync}，它明确答「未接入」（409）且**一行都不写**。
   *
   * <p>{@code platformReviewedAt} 是**平台上的评价时刻**，不是登记时刻 ——
   * 趋势按它分桶。{@code replied} 表示「平台侧是否已有卖家公开回复」，
   * 为 true 时 {@code repliedAt} 必填（DB 层有 CHECK 兜着）。
   */
  public record CrmProductReviewCreateInput(
      @NotNull UUID shopId,
      @NotNull @Min(1) @Max(5) Integer rating,
      @Size(max = 300) String title,
      @Size(max = 20000) String content,
      @Size(max = 128) String productRef,
      @Size(max = 300) String productTitle,
      @Size(max = 128) String variantRef,
      @Size(max = 200) String variantLabel,
      @Size(max = 20) List<@Size(max = 500) String> imageUrls,
      @Size(max = 500) String videoUrl,
      @Size(max = 128) String buyerRef,
      @Size(max = 200) String buyerName,
      @Size(max = 64) String salesOrderNo,
      @Size(max = 128) String externalReviewId,
      Boolean replied,
      @Size(max = 64) String repliedAt,
      @NotBlank
          @Schema(description = "平台上的评价时刻，例如 2026-10-10T08:00:00+08:00")
          String platformReviewedAt,
      @Size(max = 500) String note) {}

  /**
   * 平台评价同步的入参（当前恒为 409：平台评价适配器未交付）。
   *
   * <p>保留它是为了让「同步」这件事在契约里**有位置**：适配器接上之后，
   * 服务层与前端一行都不用改。
   */
  public record CrmProductReviewSyncInput(@NotNull UUID shopId) {}

  // ------------------------------------------------------------ 输出

  /** 能力维度：每一维都要说清「有没有、依据是什么、缺了会怎样」。 */
  public record CrmProductReviewCapabilityDimension(
      String dimension,
      String description,
      boolean available,
      String source,
      String ownerModule,
      String impact) {}

  /** 能力声明：前端在渲染前就知道哪些动作存在，而不是靠试。 */
  public record CrmProductReviewCapabilities(
      List<CrmProductReviewCapabilityDimension> dimensions,
      List<String> sources,
      List<String> replyStates,
      List<String> dimensionKeys,
      String boundaryNote,
      String highlight) {}

  /** 一条评价（= 蓝图输出句里的「评价列表」的一行）。 */
  public record CrmProductReviewRow(
      UUID id,
      String reviewNo,
      UUID shopId,
      String shopName,
      String platformCode,
      String source,
      @Schema(nullable = true) String externalReviewId,
      Integer rating,
      @Schema(nullable = true) String title,
      @Schema(nullable = true) String content,
      @Schema(nullable = true) String productRef,
      @Schema(nullable = true) String productTitle,
      @Schema(nullable = true) String variantRef,
      @Schema(nullable = true) String variantLabel,
      List<String> imageUrls,
      @Schema(nullable = true) String videoUrl,
      boolean hasImage,
      boolean hasVideo,
      @Schema(nullable = true) String buyerRef,
      @Schema(nullable = true) String buyerName,
      @Schema(nullable = true) String salesOrderNo,
      boolean replied,
      @Schema(nullable = true) String repliedAt,
      String platformReviewedAt,
      String registeredAt,
      @Schema(nullable = true) String note,
      @Schema(nullable = true) UUID createdBy,
      String createdAt,
      String updatedAt) {}

  /** 评价列表分页（真分页：总数与页数都从真实查询来）。 */
  public record CrmProductReviewPage(
      List<CrmProductReviewRow> items, int page, int size, long totalElements, int totalPages) {}

  /**
   * 关联订单上下文（**跨服务只读**，来自上游授权 HTTP 端口）。
   *
   * <p>🔴 {@code available=false} 时 {@code reason} 必填，页面**必须把它显示出来** ——
   * 客服看到一片空白会以为这条评价没有关联订单，而真相是「上游读不到」。
   * 绝不返回「空集合 / 空字段」冒充「上游没有订单」。
   */
  public record CrmProductReviewOrderContext(
      boolean available,
      String reason,
      @Schema(nullable = true) String salesOrderNo,
      @Schema(nullable = true) String orderNo,
      @Schema(nullable = true) String orderStatus,
      @Schema(nullable = true) String deliveredAt,
      @Schema(nullable = true) String deliveryState,
      @Schema(nullable = true) String carrierCode,
      @Schema(nullable = true) String caseNo) {}

  /** 单条评价详情 = 评价本体 + 关联订单上下文。 */
  public record CrmProductReviewDetail(
      CrmProductReviewRow review, CrmProductReviewOrderContext orderContext) {}

  /** 星级分布的一档。 */
  public record CrmProductReviewRatingBucket(int rating, long count) {}

  /** 趋势的一个时间桶（按平台评价时刻分桶）。 */
  public record CrmProductReviewTrendBucket(
      String bucketStart, String bucketEnd, long count, @Schema(nullable = true) BigDecimal averageRating) {}

  /**
   * 一个维度（店铺 / 商品 / 变体）的评分汇总。
   *
   * <p>{@code recentAverage} / {@code previousAverage} / {@code olderAverage} 是
   * 作用域**三等分**后的近窗 / 中窗 / 远窗均分，
   * {@code delta = recentAverage - previousAverage}（负数 = 下滑）。
   */
  public record CrmProductReviewDimensionBucket(
      String dimension,
      String key,
      String label,
      @Schema(nullable = true) UUID shopId,
      @Schema(nullable = true) String shopName,
      @Schema(nullable = true) String platformCode,
      long count,
      long unrepliedCount,
      @Schema(nullable = true) BigDecimal averageRating,
      @Schema(nullable = true) BigDecimal recentAverage,
      @Schema(nullable = true) BigDecimal previousAverage,
      @Schema(nullable = true) BigDecimal olderAverage,
      @Schema(nullable = true) BigDecimal delta) {}

  /**
   * 一个**在下滑**的维度。
   *
   * <p>加粗句「连续下滑要先查是产品问题还是履约问题」的落点：
   * 光有「评分在跌」没有维度，客服无从下手；这里把店铺 / 商品 / 变体分开列，
   * 并给 {@code continuous}（近窗 < 前窗 < 更前窗，**三窗单调下滑**）。
   */
  public record CrmProductReviewDecline(
      String dimension,
      String key,
      String label,
      long count,
      @Schema(nullable = true) BigDecimal recentAverage,
      @Schema(nullable = true) BigDecimal previousAverage,
      @Schema(nullable = true) BigDecimal olderAverage,
      @Schema(nullable = true) BigDecimal delta,
      boolean continuous) {}

  /**
   * 评分统计（= 蓝图输出句里的「评分统计」）。
   *
   * <p>🔴 全部数值**由 SQL 现算**，前端**只原样展示**（不做任何前端算术）。
   */
  public record CrmProductReviewStats(
      Integer scopeDays,
      String scopeFrom,
      String scopeTo,
      long totalReviews,
      long repliedCount,
      long unrepliedCount,
      long withImageCount,
      long withVideoCount,
      @Schema(nullable = true) BigDecimal averageRating,
      List<CrmProductReviewRatingBucket> ratingDistribution,
      List<CrmProductReviewTrendBucket> trend,
      List<CrmProductReviewDimensionBucket> byShop,
      List<CrmProductReviewDimensionBucket> byProduct,
      List<CrmProductReviewDimensionBucket> byVariant,
      List<CrmProductReviewDecline> declining,
      String note) {}

  /** 店铺目录的一行（来自上游 channel 的真实清单）+ 该平台的评价接入状态。 */
  public record CrmProductReviewShopView(
      UUID id,
      String code,
      String name,
      String platformCode,
      String platformName,
      String status,
      boolean platformReviewAvailable,
      String platformReviewNote) {}

  /**
   * 平台评价同步的结果。
   *
   * <p>🔴 当前**恒为 409**：平台评价适配器未交付（见 {@code PlatformReviewPort}），
   * 同步请求**不写任何数据**。这个记录只在适配器接上之后才会有 {@code synced > 0}。
   */
  public record CrmProductReviewSyncResult(
      int synced, boolean available, String reason, String note) {}
}
