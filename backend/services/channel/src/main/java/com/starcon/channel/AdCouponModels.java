package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 14.4.1.2 优惠券与秒杀 的传输模型。
 *
 * <p>蓝图：「管理优惠券（折扣码、满减、买赠）与秒杀活动：生成码、设置库存与限领、
 * 投放渠道（站内/站外/社媒），跟踪领取与核销。输入：券规则；投放计划；核销数据同步。
 * 输出：券记录 + 核销统计。关系：上游 = 5 定价；下游 = 平台营销接口、15 客户（定向发放）。
 * <b>券的核销要与订单绑定</b>，否则无法评估活动真实效果与补贴成本。」
 *
 * <p>🔴 这个模型层钉死本模块最容易做丢的五件事：
 *
 * <ol>
 *   <li><b>门槛与面额是快照，不是实时价</b>。上游 5 定价在 <b>catalog</b> 服务
 *       （跨服务不可读）⇒ {@code thresholdAmount} / {@code discountValue} /
 *       {@code discountRate} 都是**建券时登记的输入**（创建当时的定价口径），
 *       模型层**没有**任何「实时价格」字段。</li>
 *   <li><b>「本地已建」不等于「平台侧已建」</b>。平台营销接口未交付 ⇒
 *       {@code platformSyncStatus} 恒为 {@code LOCAL_ONLY} +
 *       {@code pendingPlatformWriteback} 恒为 true，详情里带一句
 *       {@code syncNote} 说清「平台侧还没有这张券」。</li>
 *   <li><b>核销必须带订单标识</b>。{@code orderRef} 在请求与响应里都是
 *       **NOT NULL** 语义（服务端 400 拒绝空串）—— 没订单标识的核销写不进来。</li>
 *   <li><b>补贴成本不可测算时是 null，不是 0</b>。买赠券（BOGO）的成本价在
 *       05 定价（跨服务不可读）⇒ {@code subsidyCost} 为 null，统计里单列
 *       {@code unmeasurableRedemptions} 笔数。写 0 会被渲染成「补贴成本是 0」，
 *       那是假事实。</li>
 *   <li><b>限领的判据是「这个人在这张券上累计领了几张」</b>。
 *       {@code perUserLimit} 为 null = 不限领（**不是 0**）。</li>
 * </ol>
 *
 * <p>🔴 金额与数量一律 {@link BigDecimal}，**没有 double**：面额、门槛、封顶、
 * 补贴成本、库存、限领张数都是钱或数量，浮点记账是禁区（AGENTS.md）。
 */
public final class AdCouponModels {
  private AdCouponModels() {}

  // ================================================================ 能力声明

  public record AdCouponCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  /**
   * 能力声明：逐维度说清「哪些能算、哪些拿不到」。
   *
   * <p>🔴 三个不可用维度分别是：<b>平台营销回写</b>（平台营销接口未交付）、
   * <b>客户主数据</b>（下游 15 客户未交付 ⇒ 定向发放只记收件人标识）、
   * <b>买赠补贴成本</b>（成本价在 05 定价，跨服务不可读）。
   */
  public record AdCouponCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false")
          List<AdCouponCapabilityDimension> dimensions,
      @Schema(description = "券类型取值") List<String> couponTypes,
      @Schema(description = "本地状态取值") List<String> statuses,
      @Schema(description = "平台同步状态取值") List<String> syncStatuses,
      @Schema(description = "领取方式取值") List<String> claimKinds,
      @Schema(description = "投放渠道取值") List<String> channelTypes,
      @Schema(description = "🔴 门槛与面额的口径说明") String pricingSnapshotNote,
      @Schema(description = "🔴 平台营销回写当前不可用的说明") String syncNote,
      @Schema(description = "🔴 定向发放当前不可用的说明") String recipientNote,
      @Schema(description = "🔴 补贴成本不可测算的说明") String subsidyNote,
      @Schema(description = "🔴 核销与订单绑定的说明") String orderBindingNote,
      @Schema(description = "上游与边界说明") String upstreamNote,
      @Schema(description = "下游与边界说明") String boundaryNote) {}

  // ================================================================ 下拉项

  public record AdCouponOption(
      @Schema(description = "取值") String code, @Schema(description = "中文说明") String label) {}

  /** 平台下拉项（**带 id** —— 登记券要的是平台 id，不只是编码）。 */
  public record AdCouponPlatformOption(
      @Schema(description = "平台 id") UUID id,
      @Schema(description = "平台编码") String code,
      @Schema(description = "平台名称") String name) {}

  public record AdCouponOptions(
      @Schema(description = "券类型选项") List<AdCouponOption> couponTypes,
      @Schema(description = "本地状态选项") List<AdCouponOption> statuses,
      @Schema(description = "平台同步状态选项") List<AdCouponOption> syncStatuses,
      @Schema(description = "领取方式选项") List<AdCouponOption> claimKinds,
      @Schema(description = "投放渠道选项") List<AdCouponOption> channelTypes,
      @Schema(description = "平台下拉项（带 id）") List<AdCouponPlatformOption> platforms,
      @Schema(description = "平台编码（租户内可用的）") List<String> platformCodes,
      @Schema(description = "店铺编码（租户内可用的）") List<String> shopCodes,
      @Schema(description = "币种（券里出现过的）") List<String> currencies,
      @Schema(description = "🔴 门槛与面额口径说明") String pricingSnapshotNote) {}

  // ================================================================ 券

  /** 优惠券的一行。 */
  public record AdCouponRow(
      @Schema(description = "券 id") UUID id,
      @Schema(description = "券编码（租户内唯一）") String couponCode,
      @Schema(description = "券名称") String couponName,
      @Schema(description = "券类型：DISCOUNT_CODE / FULL_REDUCTION / BOGO") String couponType,
      @Schema(description = "平台 id") UUID platformId,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "挂着的促销 id（不挂促销为空）") UUID promotionId,
      @Schema(description = "🔴 折扣率（快照；折扣码才有）", type = "string") BigDecimal discountRate,
      @Schema(description = "🔴 面额 / 减免额（快照）", type = "string") BigDecimal discountValue,
      @Schema(description = "🔴 满减门槛（快照）", type = "string") BigDecimal thresholdAmount,
      @Schema(description = "🔴 单笔让利封顶（null = 不封顶）", type = "string")
          BigDecimal maxDiscountAmount,
      @Schema(description = "买赠：买 N", type = "string") BigDecimal buyQuantity,
      @Schema(description = "买赠：送 M", type = "string") BigDecimal giftQuantity,
      @Schema(description = "币种") String currency,
      @Schema(description = "生效时间") OffsetDateTime startAt,
      @Schema(description = "失效时间") OffsetDateTime endAt,
      @Schema(description = "🔴 当前是否落在生效时间窗内") boolean withinScheduleWindow,
      @Schema(description = "本地状态") String status,
      @Schema(description = "🔴 库存（可生成的券码总数）", type = "string") BigDecimal totalStock,
      @Schema(description = "🔴 已生成券码数", type = "string") BigDecimal generatedCount,
      @Schema(description = "🔴 已领取数", type = "string") BigDecimal claimedCount,
      @Schema(description = "🔴 已核销数", type = "string") BigDecimal redeemedCount,
      @Schema(description = "🔴 此刻可领的剩余额度（已生成但未被领取的券码数）", type = "string")
          BigDecimal claimableCount,
      @Schema(description = "🔴 每人限领张数（null = 不限领）", type = "string")
          BigDecimal perUserLimit,
      @Schema(description = "🔴 平台同步状态：LOCAL_ONLY / PENDING_WRITEBACK / SYNCED / FAILED")
          String platformSyncStatus,
      @Schema(description = "🔴 本地已改、还没回写平台") boolean pendingPlatformWriteback,
      @Schema(description = "🔴 本地最后一次变更时刻") OffsetDateTime localChangedAt,
      @Schema(description = "投放渠道条数") int channelCount,
      @Schema(description = "备注") String note,
      @Schema(description = "乐观锁版本") long version,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  public record AdCouponPage(
      @Schema(description = "当前页数据") List<AdCouponRow> items,
      @Schema(description = "符合筛选条件的总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  /** 券码的一行。 */
  public record AdCouponCodeRow(
      @Schema(description = "券码 id") UUID id,
      @Schema(description = "券码") String code,
      @Schema(description = "券码状态：GENERATED / CLAIMED / USED / EXPIRED / VOID") String status,
      @Schema(description = "领取人标识") String claimantKey,
      @Schema(description = "领取时刻") OffsetDateTime claimedAt,
      @Schema(description = "核销时刻") OffsetDateTime usedAt,
      @Schema(description = "核销绑定的订单标识") String orderRef,
      @Schema(description = "创建时刻") OffsetDateTime createdAt) {}

  public record AdCouponCodePage(
      @Schema(description = "当前页数据") List<AdCouponCodeRow> items,
      @Schema(description = "符合筛选条件的总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  /** 投放渠道（投放计划）的一行。 */
  public record AdCouponChannelRow(
      @Schema(description = "投放 id") UUID id,
      @Schema(description = "🔴 渠道类型：ON_SITE 站内 / OFF_SITE 站外 / SOCIAL 社媒")
          String channelType,
      @Schema(description = "投放位") String placement,
      @Schema(description = "投放计划开始时间") OffsetDateTime planStartAt,
      @Schema(description = "投放计划结束时间") OffsetDateTime planEndAt,
      @Schema(description = "计划触达量（null = 未登记）", type = "string") BigDecimal plannedReach,
      @Schema(description = "🔴 实际领取数（真聚合）") long actualClaimCount,
      @Schema(description = "🔴 实际核销数（真聚合）") long actualRedemptionCount,
      @Schema(description = "投放状态") String status,
      @Schema(description = "备注") String note,
      @Schema(description = "创建时刻") OffsetDateTime createdAt) {}

  /** 领取记录的一行（含定向发放）。 */
  public record AdCouponClaimRow(
      @Schema(description = "领取记录 id") UUID id,
      @Schema(description = "领取方式：PULL / PUSH / CHANNEL") String claimKind,
      @Schema(description = "领取人 / 收件人标识") String recipientKey,
      @Schema(description = "领取渠道：ON_SITE / OFF_SITE / SOCIAL / DIRECT") String channelType,
      @Schema(description = "券码") String code,
      @Schema(description = "领取张数", type = "string") BigDecimal quantity,
      @Schema(description = "领取时刻") OffsetDateTime claimedAt) {}

  /** 核销记录的一行。 */
  public record AdCouponRedemptionRow(
      @Schema(description = "核销记录 id") UUID id,
      @Schema(description = "🔴 订单标识（核销与订单绑定）") String orderRef,
      @Schema(description = "券码") String code,
      @Schema(description = "订单金额", type = "string") BigDecimal orderAmount,
      @Schema(description = "🔴 本次核销让利额", type = "string") BigDecimal redeemedAmount,
      @Schema(description = "🔴 补贴成本（null = 本地不可测算，如买赠）", type = "string")
          BigDecimal subsidyCost,
      @Schema(description = "币种") String currency,
      @Schema(description = "核销时刻") OffsetDateTime redeemedAt) {}

  /** 核销统计里的渠道维度（真聚合）。 */
  public record AdCouponChannelStat(
      @Schema(description = "渠道类型") String channelType,
      @Schema(description = "投放位（定向发放无投放位）") String placement,
      @Schema(description = "领取数") long claimCount,
      @Schema(description = "核销数") long redemptionCount,
      @Schema(description = "🔴 补贴成本合计（null = 不可测算）", type = "string")
          BigDecimal subsidyCost) {}

  /** 核销统计里的按日维度（真聚合）。 */
  public record AdCouponDailyStat(
      @Schema(description = "日期") LocalDate date,
      @Schema(description = "核销数") long redemptionCount,
      @Schema(description = "🔴 补贴成本合计（null = 不可测算）", type = "string")
          BigDecimal subsidyCost) {}

  /**
   * 核销统计（真聚合）。
   *
   * <p>🔴 {@code subsidyCost} 为 null = **一笔都不可测算**（全是买赠这类没有成本口径的券）；
   * {@code unmeasurableRedemptions} 单独列出不可测算的**笔数**，
   * 这样界面不会把「没算出来」显示成「成本是 0」。
   */
  public record AdCouponStatistics(
      @Schema(description = "已生成券码数") long generatedCount,
      @Schema(description = "已领取数") long claimedCount,
      @Schema(description = "已核销数") long redeemedCount,
      @Schema(description = "🔴 核销率（核销数 / 领取数；没有领取时不可比 = null）", type = "string")
          BigDecimal redemptionRate,
      @Schema(description = "🔴 补贴成本合计（null = 一笔都不可测算）", type = "string")
          BigDecimal totalSubsidyCost,
      @Schema(description = "🔴 让利额合计", type = "string") BigDecimal totalRedeemedAmount,
      @Schema(description = "🔴 补贴成本不可测算的核销笔数") long unmeasurableRedemptions,
      @Schema(description = "按渠道拆分（真聚合）") List<AdCouponChannelStat> channelBreakdown,
      @Schema(description = "按日拆分（真聚合）") List<AdCouponDailyStat> dailyTrend,
      @Schema(description = "🔴 补贴成本口径说明") String subsidyNote) {}

  /**
   * 券详情。
   *
   * <p>🔴 响应里 {@code syncNote} 必须在场：平台营销接口未交付这件事要让界面看得见。
   */
  public record AdCouponDetail(
      @Schema(description = "券") AdCouponRow coupon,
      @Schema(description = "🔴 投放渠道与投放计划") List<AdCouponChannelRow> channels,
      @Schema(description = "🔴 最近领取记录") List<AdCouponClaimRow> recentClaims,
      @Schema(description = "🔴 最近核销记录") List<AdCouponRedemptionRow> recentRedemptions,
      @Schema(description = "🔴 核销统计（真聚合）") AdCouponStatistics statistics,
      @Schema(description = "🔴 平台营销回写说明") String syncNote,
      @Schema(description = "🔴 门槛与面额口径说明") String pricingSnapshotNote,
      @Schema(description = "说明") String note) {}

  // ================================================================ 请求

  /** 登记一张券（蓝图的「券规则」）。 */
  public record SaveCouponRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "券编码（租户内唯一）")
          @NotBlank
          String couponCode,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "券名称") @NotBlank
          String couponName,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              description = "券类型：DISCOUNT_CODE 折扣码 / FULL_REDUCTION 满减 / BOGO 买赠")
          @NotBlank
          String couponType,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "平台 id") @NotNull
          UUID platformId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "店铺编码") @NotBlank
          String shopCode,
      @Schema(description = "挂着的促销 id（不挂促销留空）") UUID promotionId,
      @Schema(description = "折扣率（0 < r <= 1；折扣码二选一）", type = "string")
          @DecimalMin(value = "0", inclusive = false)
          @DecimalMax("1")
          BigDecimal discountRate,
      @Schema(description = "面额 / 减免额（折扣码与满减用）", type = "string") @DecimalMin("0")
          BigDecimal discountValue,
      @Schema(description = "满减门槛（满减必填）", type = "string") @DecimalMin("0")
          BigDecimal thresholdAmount,
      @Schema(description = "单笔让利封顶（null = 不封顶）", type = "string") @DecimalMin("0")
          BigDecimal maxDiscountAmount,
      @Schema(description = "买赠：买 N", type = "string") @DecimalMin(value = "0", inclusive = false)
          BigDecimal buyQuantity,
      @Schema(description = "买赠：送 M", type = "string") @DecimalMin(value = "0", inclusive = false)
          BigDecimal giftQuantity,
      @Schema(description = "币种（3 位字母）") @Size(max = 3) String currency,
      @Schema(description = "生效时间") OffsetDateTime startAt,
      @Schema(description = "失效时间（必须晚于生效时间）") OffsetDateTime endAt,
      @Schema(description = "🔴 库存（可生成的券码总数）", type = "string") @DecimalMin("0")
          BigDecimal totalStock,
      @Schema(description = "🔴 每人限领张数（null = 不限领）", type = "string")
          @DecimalMin(value = "0", inclusive = false)
          BigDecimal perUserLimit,
      @Schema(description = "🔴 券码前缀") @Size(max = 16) String codePrefix,
      @Schema(description = "🔴 券码随机段长度（4-32）") @Min(4) @Max(32) Integer codeLength,
      @Schema(description = "🔴 券码随机字符集") @Size(max = 64) String codeCharset,
      @Schema(description = "备注") String note) {}

  /** 改本地状态（上线 / 暂停 / 结束 / 停用）。 */
  public record ChangeCouponStatusRequest(
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              description = "目标状态：ACTIVE / PAUSED / ENDED / CANCELLED")
          @NotBlank
          String status,
      @Schema(description = "理由（会写进留痕）") String reason) {}

  /** 批量生成券码（蓝图的「生成码」）。 */
  public record GenerateCouponCodesRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "生成张数（1-1000）")
          @Min(1)
          @Max(1000)
          int count,
      @Schema(description = "券码前缀（留空用券上的默认前缀）") @Size(max = 16) String codePrefix,
      @Schema(description = "随机段长度（留空用券上的默认长度）") @Min(4) @Max(32) Integer codeLength,
      @Schema(description = "随机字符集（留空用券上的默认字符集）") @Size(max = 64) String codeCharset) {}

  /** 领取 / 定向发放一张券。 */
  public record ClaimCouponRequest(
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              description = "领取方式：PULL 用户主动领取 / PUSH 定向发放 / CHANNEL 渠道投放领取")
          @NotBlank
          String claimKind,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "领取人 / 收件人标识")
          @NotBlank
          String recipientKey,
      @Schema(description = "领取渠道：ON_SITE / OFF_SITE / SOCIAL / DIRECT") String channelType,
      @Schema(description = "投放渠道 id（渠道投放领取用）") UUID channelId,
      @Schema(description = "领取张数", type = "string") @DecimalMin(value = "0", inclusive = false)
          BigDecimal quantity) {}

  /** 核销一张券（🔴 必须带订单标识）。 */
  public record RedeemCouponRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "🔴 订单标识（核销与订单绑定）")
          @NotBlank
          String orderRef,
      @Schema(description = "券码（不填则取该领取人已领未核销的码）") String code,
      @Schema(description = "订单金额", type = "string") @DecimalMin("0") BigDecimal orderAmount) {}

  /** 登记一条投放渠道与投放计划。 */
  public record SaveCouponChannelRequest(
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              description = "渠道类型：ON_SITE 站内 / OFF_SITE 站外 / SOCIAL 社媒")
          @NotBlank
          String channelType,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "投放位") @NotBlank
          String placement,
      @Schema(description = "投放计划开始时间") OffsetDateTime planStartAt,
      @Schema(description = "投放计划结束时间") OffsetDateTime planEndAt,
      @Schema(description = "计划触达量（null = 未登记）", type = "string") @DecimalMin("0")
          BigDecimal plannedReach,
      @Schema(description = "投放状态：PLANNED / RUNNING / PAUSED / FINISHED / CANCELLED")
          String status,
      @Schema(description = "备注") String note) {}
}
