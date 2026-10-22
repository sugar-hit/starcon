package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 14.4.1.2 优惠券与秒杀（秒杀这一半）的传输模型。
 *
 * <p>蓝图：「管理优惠券（折扣码、满减、买赠）与**秒杀活动**：生成码、设置库存与限领、
 * 投放渠道（站内/站外/社媒），跟踪领取与核销。……<b>券的核销要与订单绑定</b>，
 * 否则无法评估活动真实效果与补贴成本。」
 *
 * <p>🔴 秒杀与券的差别被这个模型层显式表达出来：
 *
 * <ol>
 *   <li><b>秒杀不需要券码</b>。它靠**库存 + 限领 + 限时**驱动 ⇒ 模型里**没有** code 字段
 *       （硬塞一个「秒杀码」进去只会让「这张券有没有码」变成一个说不清的问题）。</li>
 *   <li><b>秒杀价与原价都是快照</b>。上游 5 定价在 catalog（跨服务不可读）⇒
 *       {@code flashPrice} / {@code originalPrice} 是建活动时登记的输入。
 *       原价没登记时让利额**不可比（null）**，**不是 0**。</li>
 *   <li><b>核销同样要绑订单</b>。{@code orderRef} 是核销的必备字段，没有就写不进来。</li>
 *   <li><b>补贴成本本地可算</b>。秒杀是价格直降 ⇒ 让利额 = (原价 − 秒杀价) × 数量；
 *       成本价在 05 定价（跨服务不可读）⇒ 补贴成本按**让利额**记（券面即成本），
 *       而**利润 / 毛利**维度不可用（与 14.4.1.1 同纪律）。</li>
 * </ol>
 *
 * <p>🔴 金额与数量一律 {@link BigDecimal}，**没有 double**：秒杀价、库存、限领件数、
 * 让利额、补贴成本都是钱或数量。
 */
public final class AdFlashSaleModels {
  private AdFlashSaleModels() {}

  // ================================================================ 能力声明

  public record AdFlashSaleCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  public record AdFlashSaleCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false")
          List<AdFlashSaleCapabilityDimension> dimensions,
      @Schema(description = "本地状态取值") List<String> statuses,
      @Schema(description = "平台同步状态取值") List<String> syncStatuses,
      @Schema(description = "投放渠道取值") List<String> channelTypes,
      @Schema(description = "🔴 秒杀价与原价的口径说明") String pricingSnapshotNote,
      @Schema(description = "🔴 平台营销回写当前不可用的说明") String syncNote,
      @Schema(description = "🔴 核销与订单绑定的说明") String orderBindingNote,
      @Schema(description = "🔴 毛利维度当前不可用的说明") String marginNote,
      @Schema(description = "上游与边界说明") String upstreamNote,
      @Schema(description = "下游与边界说明") String boundaryNote) {}

  // ================================================================ 下拉项

  public record AdFlashSaleOption(
      @Schema(description = "取值") String code, @Schema(description = "中文说明") String label) {}

  public record AdFlashSalePlatformOption(
      @Schema(description = "平台 id") UUID id,
      @Schema(description = "平台编码") String code,
      @Schema(description = "平台名称") String name) {}

  public record AdFlashSaleOptions(
      @Schema(description = "本地状态选项") List<AdFlashSaleOption> statuses,
      @Schema(description = "平台同步状态选项") List<AdFlashSaleOption> syncStatuses,
      @Schema(description = "投放渠道选项") List<AdFlashSaleOption> channelTypes,
      @Schema(description = "平台下拉项（带 id）") List<AdFlashSalePlatformOption> platforms,
      @Schema(description = "店铺编码（租户内可用的）") List<String> shopCodes,
      @Schema(description = "币种（秒杀里出现过的）") List<String> currencies,
      @Schema(description = "🔴 秒杀价与原价口径说明") String pricingSnapshotNote) {}

  // ================================================================ 秒杀

  /** 秒杀活动的一行。 */
  public record AdFlashSaleRow(
      @Schema(description = "秒杀 id") UUID id,
      @Schema(description = "秒杀编码（租户内唯一）") String saleCode,
      @Schema(description = "秒杀名称") String saleName,
      @Schema(description = "平台 id") UUID platformId,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "listing id（秒杀针对的商品）") UUID listingId,
      @Schema(description = "商品标题") String listingTitle,
      @Schema(description = "🔴 秒杀价（快照）", type = "string") BigDecimal flashPrice,
      @Schema(description = "🔴 原价（快照；没登记 = 不可比，不是 0）", type = "string")
          BigDecimal originalPrice,
      @Schema(description = "币种") String currency,
      @Schema(description = "开始时间") OffsetDateTime startAt,
      @Schema(description = "结束时间") OffsetDateTime endAt,
      @Schema(description = "🔴 当前是否落在秒杀时间窗内") boolean withinScheduleWindow,
      @Schema(description = "本地状态") String status,
      @Schema(description = "🔴 秒杀库存", type = "string") BigDecimal totalStock,
      @Schema(description = "🔴 已领取数量", type = "string") BigDecimal claimedQuantity,
      @Schema(description = "🔴 已核销数量", type = "string") BigDecimal redeemedQuantity,
      @Schema(description = "🔴 剩余库存（不可比时为 null）", type = "string") BigDecimal remainingStock,
      @Schema(description = "🔴 每人限领件数（null = 不限领）", type = "string")
          BigDecimal perUserLimit,
      @Schema(description = "🔴 投放渠道：ON_SITE / OFF_SITE / SOCIAL") String channelType,
      @Schema(description = "🔴 投放位") String placement,
      @Schema(description = "🔴 平台同步状态") String platformSyncStatus,
      @Schema(description = "🔴 本地已改、还没回写平台") boolean pendingPlatformWriteback,
      @Schema(description = "🔴 本地最后一次变更时刻") OffsetDateTime localChangedAt,
      @Schema(description = "备注") String note,
      @Schema(description = "乐观锁版本") long version,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  public record AdFlashSalePage(
      @Schema(description = "当前页数据") List<AdFlashSaleRow> items,
      @Schema(description = "符合筛选条件的总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  /** 秒杀领取记录的一行（含核销信息）。 */
  public record AdFlashSaleClaimRow(
      @Schema(description = "领取记录 id") UUID id,
      @Schema(description = "领取人标识") String recipientKey,
      @Schema(description = "领取件数", type = "string") BigDecimal quantity,
      @Schema(description = "领取时刻") OffsetDateTime claimedAt,
      @Schema(description = "🔴 核销绑定的订单标识（未核销为空）") String orderRef,
      @Schema(description = "核销时刻") OffsetDateTime redeemedAt,
      @Schema(description = "🔴 核销让利额（null = 不可比）", type = "string")
          BigDecimal redeemedAmount,
      @Schema(description = "🔴 补贴成本（null = 不可比）", type = "string") BigDecimal subsidyCost,
      @Schema(description = "状态：CLAIMED / REDEEMED / CANCELLED") String status) {}

  /** 秒杀统计里的按日维度（真聚合）。 */
  public record AdFlashSaleDailyStat(
      @Schema(description = "日期") LocalDate date,
      @Schema(description = "核销数") long redemptionCount,
      @Schema(description = "🔴 补贴成本合计（null = 不可比）", type = "string")
          BigDecimal subsidyCost) {}

  /**
   * 秒杀统计（真聚合）。
   *
   * <p>🔴 {@code remainingStock} 为 null = 库存不可比（不该发生，防御性返回）。
   * {@code redemptionRate} 在没有领取时**不可比（null）**，不是 0。
   */
  public record AdFlashSaleStatistics(
      @Schema(description = "秒杀库存", type = "string") BigDecimal totalStock,
      @Schema(description = "已领取件数") long claimedCount,
      @Schema(description = "已核销件数") long redeemedCount,
      @Schema(description = "🔴 剩余库存", type = "string") BigDecimal remainingStock,
      @Schema(description = "🔴 核销率（没有领取时不可比 = null）", type = "string")
          BigDecimal redemptionRate,
      @Schema(description = "🔴 让利额合计", type = "string") BigDecimal totalRedeemedAmount,
      @Schema(description = "🔴 补贴成本合计", type = "string") BigDecimal totalSubsidyCost,
      @Schema(description = "按日拆分（真聚合）") List<AdFlashSaleDailyStat> dailyTrend,
      @Schema(description = "🔴 毛利维度说明") String marginNote) {}

  public record AdFlashSaleDetail(
      @Schema(description = "秒杀活动") AdFlashSaleRow sale,
      @Schema(description = "🔴 最近领取记录") List<AdFlashSaleClaimRow> recentClaims,
      @Schema(description = "🔴 统计（真聚合）") AdFlashSaleStatistics statistics,
      @Schema(description = "🔴 平台营销回写说明") String syncNote,
      @Schema(description = "🔴 秒杀价与原价口径说明") String pricingSnapshotNote,
      @Schema(description = "说明") String note) {}

  // ================================================================ 请求

  /** 登记一个秒杀活动。 */
  public record SaveFlashSaleRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "秒杀编码（租户内唯一）")
          @NotBlank
          String saleCode,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "秒杀名称") @NotBlank
          String saleName,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "平台 id") @NotNull
          UUID platformId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "店铺编码") @NotBlank
          String shopCode,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "listing id（秒杀商品）")
          @NotNull
          UUID listingId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "🔴 秒杀价（快照）", type = "string")
          @DecimalMin("0")
          BigDecimal flashPrice,
      @Schema(description = "🔴 原价（快照；没登记 = 不可比，不是 0）", type = "string")
          @DecimalMin("0")
          BigDecimal originalPrice,
      @Schema(description = "币种（3 位字母）") @Size(max = 3) String currency,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "开始时间") @NotNull
          OffsetDateTime startAt,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "结束时间（必须晚于开始）")
          @NotNull
          OffsetDateTime endAt,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "🔴 秒杀库存", type = "string")
          @DecimalMin("0")
          BigDecimal totalStock,
      @Schema(description = "🔴 每人限领件数（null = 不限领）", type = "string")
          @DecimalMin(value = "0", inclusive = false)
          BigDecimal perUserLimit,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              description = "🔴 投放渠道：ON_SITE 站内 / OFF_SITE 站外 / SOCIAL 社媒")
          @NotBlank
          String channelType,
      @Schema(description = "🔴 投放位") @Size(max = 200) String placement,
      @Schema(description = "备注") String note) {}

  /** 改本地状态（上线 / 暂停 / 结束 / 停用）。 */
  public record ChangeFlashSaleStatusRequest(
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              description = "目标状态：ACTIVE / PAUSED / ENDED / CANCELLED")
          @NotBlank
          String status,
      @Schema(description = "理由（会写进留痕）") String reason) {}

  /** 领取秒杀（真扣库存 + 限领校验）。 */
  public record ClaimFlashSaleRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "领取人标识") @NotBlank
          String recipientKey,
      @Schema(description = "领取件数", type = "string") @DecimalMin(value = "0", inclusive = false)
          BigDecimal quantity) {}

  /** 核销秒杀（🔴 必须带订单标识）。 */
  public record RedeemFlashSaleRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "🔴 订单标识（核销与订单绑定）")
          @NotBlank
          String orderRef,
      @Schema(description = "领取记录 id（不填则取该收件人最近一条未核销的领取）") UUID claimId) {}
}
