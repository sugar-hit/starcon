package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 14.4.1.1 促销列表 的传输模型。
 *
 * <p>蓝图：「管理平台促销活动：促销类型、适用商品、折扣幅度、起止时间、预算上限、参与店铺，
 * 同步状态与效果。输入：促销定义；平台促销接口；适用商品选择。输出：促销记录 + 平台同步状态。
 * 关系：上游 = 5 定价（促销价计算）；下游 = 平台促销 API、12 财务（费用）。
 * <b>促销的毛利必须实时测算</b>——折扣叠加后亏损是常态，需在下单前拦截或预警。」
 *
 * <p>🔴 这个模型层钉死本模块最容易做丢的四件事：
 *
 * <ol>
 *   <li><b>毛利 / 利润维度没有上游，就是没有</b>。上游 5 定价在 <b>catalog</b> 服务
 *       （跨服务不可读），且 catalog <b>没有促销价测算端点</b> ⇒ 本模型**没有 margin /
 *       profit 字段**，也不提供「毛利 0」这种占位。详情里带一句
 *       {@code marginNote} 说清「当前拿不到成本，毛利未测算」。
 *   <li><b>「本地已建」不等于「平台侧已建」</b>。{@code platformSyncStatus} +
 *       {@code pendingPlatformWriteback} + {@code localChangedAt} 三件一起表达
 *       「本地已建、还没回写平台」——平台促销 API 未交付，这三列不是装饰。
 *   <li><b>折后价与毛利是两回事</b>。{@code netPrice} 是**本地可算**的
 *       （基准价 × (1 − 折扣率)），所以它有值；「是否亏损」需要成本，{@code margin}
 *       不存在。空表 / 假零会被渲染成「毛利是 0」，那是假事实。
 *   <li><b>拦截与预警都要留痕</b>。{@link AdPromotionCheckRow} 逐条列出
 *       规则、结论、观测值与阈值；被拦下的那几条照样有记录。
 * </ol>
 *
 * <p>🔴 金额与数量一律 {@link BigDecimal}，**没有 double**：折扣率 / 预算上限 /
 * 基准价 / 折后价都是钱或比率，它们直接决定「折后卖出去还剩多少」。
 */
public final class AdPromotionModels {
  private AdPromotionModels() {}

  // ================================================================ 能力声明

  public record AdPromotionCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  /**
   * 能力声明：逐维度说清「哪些能算、哪些拿不到」。
   *
   * <p>🔴 四个不可用维度分别是：<b>毛利 / 利润</b>（上游 5 定价，跨服务不可读且无端点）、
   * <b>平台效果</b>（无平台促销同步源）、<b>平台回写</b>（平台促销 API 未交付）、
   * <b>促销费用</b>（下游 12 财务不在本仓）。
   */
  public record AdPromotionCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false")
          List<AdPromotionCapabilityDimension> dimensions,
      @Schema(description = "促销类型取值") List<String> promotionTypes,
      @Schema(description = "本地状态取值") List<String> statuses,
      @Schema(description = "平台同步状态取值") List<String> syncStatuses,
      @Schema(description = "拦截/预警规则取值") List<String> ruleCodes,
      @Schema(description = "结论严重级别取值") List<String> severities,
      @Schema(description = "🔴 折后价口径说明") String netPriceNote,
      @Schema(description = "🔴 毛利维度当前不可用的说明") String marginNote,
      @Schema(description = "🔴 平台效果维度当前不可用的说明") String effectNote,
      @Schema(description = "🔴 促销费用维度当前不可用的说明") String financeNote,
      @Schema(description = "🔴 平台同步状态的口径说明") String syncNote,
      @Schema(description = "🔴 促销叠加的口径说明") String stackingNote,
      @Schema(description = "上游与边界说明") String upstreamNote,
      @Schema(description = "下游与边界说明") String boundaryNote) {}

  // ================================================================ 下拉项

  public record AdPromotionOption(
      @Schema(description = "取值") String code, @Schema(description = "中文说明") String label) {}

  /** 平台下拉项（**带 id** —— 登记促销要的是平台 id，不只是编码）。 */
  public record AdPromotionPlatformOption(
      @Schema(description = "平台 id") UUID id,
      @Schema(description = "平台编码") String code,
      @Schema(description = "平台名称") String name) {}

  public record AdPromotionOptions(
      @Schema(description = "促销类型选项") List<AdPromotionOption> promotionTypes,
      @Schema(description = "本地状态选项") List<AdPromotionOption> statuses,
      @Schema(description = "平台同步状态选项") List<AdPromotionOption> syncStatuses,
      @Schema(description = "拦截/预警规则选项") List<AdPromotionOption> ruleCodes,
      @Schema(description = "严重级别选项") List<AdPromotionOption> severities,
      @Schema(description = "平台下拉项（带 id）") List<AdPromotionPlatformOption> platforms,
      @Schema(description = "平台编码（租户内可用的）") List<String> platformCodes,
      @Schema(description = "店铺编码（租户内可用的）") List<String> shopCodes,
      @Schema(description = "币种（促销里出现过的）") List<String> currencies,
      @Schema(description = "🔴 折后价口径说明") String netPriceNote,
      @Schema(description = "🔴 毛利维度说明") String marginNote) {}

  // ================================================================ 促销

  /** 促销列表的一行。 */
  public record AdPromotionRow(
      @Schema(description = "促销 id") UUID id,
      @Schema(description = "促销编码（租户内唯一）") String promotionCode,
      @Schema(description = "促销名称") String promotionName,
      @Schema(description = "促销类型") String promotionType,
      @Schema(description = "平台 id") UUID platformId,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "参与店铺编码") String shopCode,
      @Schema(description = "🔴 折扣率（0 < r <= 1；百分比促销才有）", type = "string")
          BigDecimal discountRate,
      @Schema(description = "🔴 固定减免额（固定额促销才有）", type = "string")
          BigDecimal discountAmount,
      @Schema(description = "🔴 预算上限（null = 不限，不是 0）", type = "string") BigDecimal budgetCap,
      @Schema(description = "币种") String currency,
      @Schema(description = "开始时间") OffsetDateTime startAt,
      @Schema(description = "结束时间") OffsetDateTime endAt,
      @Schema(description = "🔴 当前是否落在促销时间窗内") boolean withinScheduleWindow,
      @Schema(description = "本地状态") String status,
      @Schema(description = "🔴 平台同步状态：LOCAL_ONLY / PENDING_WRITEBACK / SYNCED / FAILED")
          String platformSyncStatus,
      @Schema(description = "🔴 本地已改、还没回写平台") boolean pendingPlatformWriteback,
      @Schema(description = "🔴 本地最后一次变更时刻") OffsetDateTime localChangedAt,
      @Schema(description = "🔴 最近一次拦截 / 预警检查的时刻（没检查过为空）")
          OffsetDateTime lastCheckedAt,
      @Schema(description = "平台侧促销 id（未回写为空串）") String platformPromotionId,
      @Schema(description = "适用商品条数") int productCount,
      @Schema(description = "最近一次检查的拦截条数") int blockedCount,
      @Schema(description = "最近一次检查的预警条数") int warningCount,
      @Schema(description = "备注") String note,
      @Schema(description = "乐观锁版本") long version,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  public record AdPromotionPage(
      @Schema(description = "当前页数据") List<AdPromotionRow> items,
      @Schema(description = "符合筛选条件的总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  /**
   * 促销适用商品的一行。
   *
   * <p>🔴 {@code netPrice} 是**本地可算**的折后价（基准价 × (1 − 折扣率)）；
   * {@code basePrice} 为空时 {@code netPrice} 也是空（**不可比**，不是 0）。
   * {@code stackedNetPrice} 是叠加所有生效促销后的折后价。
   */
  public record AdPromotionProductRow(
      @Schema(description = "关联 id") UUID id,
      @Schema(description = "listing id") UUID listingId,
      @Schema(description = "平台商品 id") String externalItemId,
      @Schema(description = "商品标题") String title,
      @Schema(description = "商品状态") String listingStatus,
      @Schema(description = "🔴 基准价（促销定义登记的原价；null = 没登记）", type = "string")
          BigDecimal basePrice,
      @Schema(description = "币种") String currency,
      @Schema(description = "🔴 本促销下的折后价（null = 不可比）", type = "string")
          BigDecimal netPrice,
      @Schema(description = "🔴 生效折扣幅度：百分比促销 = 折扣率；固定额促销 = 减免额 / 基准价"
              + "（null = 不可比）", type = "string")
          BigDecimal discountDepth,
      @Schema(description = "🔴 覆盖同一商品的其它生效促销数") int stackedPromotionCount,
      @Schema(description = "🔴 叠加后的折后价（null = 不可比）", type = "string")
          BigDecimal stackedNetPrice,
      @Schema(description = "备注") String note,
      @Schema(description = "创建时刻") OffsetDateTime createdAt) {}

  /** 一次拦截 / 预警的结论。 */
  public record AdPromotionCheckRow(
      @Schema(description = "记录 id") UUID id,
      @Schema(description = "listing id（促销级检查为空）") UUID listingId,
      @Schema(description = "规则：DISCOUNT_DEPTH / PRICE_FLOOR / PROMOTION_STACKING / BUDGET_CAP")
          String ruleCode,
      @Schema(description = "结论：BLOCKED 拦截 / WARNING 预警") String severity,
      @Schema(description = "结论说明") String message,
      @Schema(description = "观测值（null = 该规则这次没有可比数值）", type = "string")
          BigDecimal observedValue,
      @Schema(description = "阈值（null = 该规则没设阈值）", type = "string")
          BigDecimal thresholdValue,
      @Schema(description = "检查时刻") OffsetDateTime checkedAt) {}

  /** 促销的拦截 / 预警规则（租户级配置）。 */
  public record AdPromotionGuardrailRow(
      @Schema(description = "规则 id") UUID id,
      @Schema(description = "规则编码（租户内唯一）") String guardrailCode,
      @Schema(description = "规则名称") String guardrailName,
      @Schema(description = "折扣幅度上限（null = 不设阈值）", type = "string")
          BigDecimal maxDiscountRate,
      @Schema(description = "折后价下限（null = 不设阈值）", type = "string") BigDecimal minNetPrice,
      @Schema(description = "促销叠加是否拦截（false = 只预警）") boolean blockOnStacking,
      @Schema(description = "状态：ACTIVE / PAUSED") String status,
      @Schema(description = "备注") String note,
      @Schema(description = "乐观锁版本") long version,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  public record AdPromotionGuardrailPage(
      @Schema(description = "当前页数据") List<AdPromotionGuardrailRow> items,
      @Schema(description = "符合筛选条件的总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  /**
   * 促销详情。
   *
   * <p>🔴 响应里 {@code marginNote} 必须在场：毛利维度不可用这件事要让界面看得见，
   * 而不是让界面显示一个假的 0。
   */
  public record AdPromotionDetail(
      @Schema(description = "促销") AdPromotionRow promotion,
      @Schema(description = "🔴 适用商品（含折后价与叠加）") List<AdPromotionProductRow> products,
      @Schema(description = "🔴 拦截 / 预警记录（最近一次检查）") List<AdPromotionCheckRow> checks,
      @Schema(description = "当前生效的护栏规则（没有则为空）") AdPromotionGuardrailRow guardrail,
      @Schema(description = "拦截条数") int blockedCount,
      @Schema(description = "预警条数") int warningCount,
      @Schema(description = "🔴 毛利维度说明（当前拿不到成本）") String marginNote,
      @Schema(description = "🔴 平台同步说明") String syncNote,
      @Schema(description = "说明") String note) {}

  // ================================================================ 请求

  /** 促销适用商品（蓝图的「适用商品选择」）。 */
  public record SavePromotionProduct(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "listing id") @NotNull
          UUID listingId,
      @Schema(description = "🔴 基准价（促销前原价；null = 不可比，不是 0）", type = "string")
          @DecimalMin("0")
          BigDecimal basePrice,
      @Schema(description = "币种（与促销一致；留空取促销的币种）") @Size(max = 3) String currency,
      @Schema(description = "备注") String note) {}

  /** 登记一条促销（蓝图的「促销定义」+「适用商品选择」）。 */
  public record SavePromotionRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "促销编码（租户内唯一）")
          @NotBlank
          String promotionCode,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "促销名称") @NotBlank
          String promotionName,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              description = "促销类型：PERCENT_OFF / FIXED_AMOUNT_OFF / BOGO / BUNDLE / FREE_SHIPPING")
          @NotBlank
          String promotionType,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "平台 id") @NotNull
          UUID platformId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "参与店铺编码") @NotBlank
          String shopCode,
      @Schema(description = "折扣率（0 < r <= 1；PERCENT_OFF 必填）", type = "string")
          @DecimalMin(value = "0", inclusive = false)
          @DecimalMax("1")
          BigDecimal discountRate,
      @Schema(description = "固定减免额（FIXED_AMOUNT_OFF 必填）", type = "string")
          @DecimalMin("0")
          BigDecimal discountAmount,
      @Schema(description = "预算上限（null = 不限）", type = "string") @DecimalMin("0")
          BigDecimal budgetCap,
      @Schema(description = "币种（3 位字母）") @Size(max = 3) String currency,
      @Schema(description = "开始时间") OffsetDateTime startAt,
      @Schema(description = "结束时间（必须晚于开始）") OffsetDateTime endAt,
      @Schema(description = "适用商品") @Valid List<SavePromotionProduct> products,
      @Schema(description = "备注") String note) {}

  /** 改本地状态（激活 / 暂停 / 结束 / 取消）。 */
  public record ChangePromotionStatusRequest(
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              description = "目标状态：SCHEDULED / ACTIVE / PAUSED / ENDED / CANCELLED")
          @NotBlank
          String status,
      @Schema(description = "理由（会写进留痕）") String reason) {}

  /** 登记 / 修改护栏规则（拦截与预警的阈值）。 */
  public record SaveGuardrailRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "规则编码（租户内唯一）")
          @NotBlank
          String guardrailCode,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "规则名称") @NotBlank
          String guardrailName,
      @Schema(description = "折扣幅度上限（折扣率 > 上限 ⇒ 拦截）", type = "string")
          @DecimalMin(value = "0", inclusive = false)
          @DecimalMax("1")
          BigDecimal maxDiscountRate,
      @Schema(description = "折后价下限（折后价 < 下限 ⇒ 拦截）", type = "string") @DecimalMin("0")
          BigDecimal minNetPrice,
      @Schema(description = "促销叠加是否拦截（false = 只预警）") Boolean blockOnStacking,
      @Schema(description = "状态：ACTIVE / PAUSED") String status,
      @Schema(description = "备注") String note) {}
}
