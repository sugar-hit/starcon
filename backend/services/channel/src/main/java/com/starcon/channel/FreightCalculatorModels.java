package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 10.1.2.1 运费计算器 的传输模型。
 *
 * <p>蓝图：「输入收货国家、重量、体积、件数、申报价值、是否带电/液体等，输出各可用渠道的
 * 运费与预计时效，供人工决策或规则匹配使用。输入：包裹参数；渠道规则集；可能的附加费。
 * 输出：渠道 × (运费、时效、可承运性) 对照表。」
 *
 * <p>载荷一律用具名 {@code record} 而不是 {@code Map<String,Object>}：Map 的值类型会被
 * springdoc 推成 object，codegen 出来是空对象 {@code {}}（9.6.1.3 已踩过）。
 *
 * <p>🔴 金额与数量一律 {@link BigDecimal}，**没有 double**：运费、各档附加费、保费是钱，
 * 重量 / 体积 / 计费量是数量。AGENTS.md「禁止浮点记账」在这里是硬约束。
 *
 * <p>🔴 {@code costBasis} 在每个响应里都带着，恒为 {@code RULE_ESTIMATE}。蓝图加粗句是
 * 「试算用规则、结算用实际，两者差异进费用差异项 —— 不能拿试算当实际成本」：
 * 所以它不是一句提示语，而是一个贯穿 API / 数据表 / 界面的显式标记。
 */
public final class FreightCalculatorModels {
  private FreightCalculatorModels() {}

  // ================================================================ 能力声明

  public record FreightCapabilityDimension(
      String dimension, String description, boolean available, String source, String ownerModule, String impact) {}

  public record FreightCalculatorCapabilities(
      List<FreightCapabilityDimension> dimensions,
      List<String> chargeModels,
      List<String> bases,
      List<String> roundingModes,
      List<String> rateSources,
      List<String> eligibilityCodes,
      /** 试算 ≠ 实际成本：这条口径写在接口自述里，而不是只写在文档里。 */
      String estimationNote,
      /** 数据来源纪律：规则来自哪几张表。 */
      String dataNote) {}

  // ================================================================ 包裹参数（输入）

  /**
   * 一次试算的包裹参数。
   *
   * <p>{@code pieces} 留空按 1 处理（大多数货是一个包裹）；{@code quotedAt} 留空取当前时刻
   * —— 旺季附加费按它判定，而它会被写进试算单（事后复算「当时加没加旺季费」要靠它）。
   *
   * <p>长宽高要么三个都给，要么都不给：半套尺寸会算出一个看起来正常的体积重。
   */
  public record FreightEstimateRequest(
      /** 只算这些渠道；留空 = 本租户全部适用该目的国的启用渠道。 */
      @Schema(nullable = true) @Size(max = 100) List<UUID> channelIds,
      @NotBlank @Size(min = 2, max = 2) String destCountry,
      @Schema(nullable = true) @Size(max = 64) String shopCode,
      @NotNull @PositiveOrZero BigDecimal weightKg,
      @Schema(nullable = true) @Positive BigDecimal lengthCm,
      @Schema(nullable = true) @Positive BigDecimal widthCm,
      @Schema(nullable = true) @Positive BigDecimal heightCm,
      @Schema(nullable = true) @Positive Integer pieces,
      @Schema(nullable = true) @PositiveOrZero BigDecimal declaredValue,
      @Schema(nullable = true) @Size(min = 3, max = 3) String declaredCurrency,
      @Schema(nullable = true) @Size(max = 50) List<@Size(max = 64) String> categoryCodes,
      boolean containsBattery,
      boolean containsLiquid,
      boolean containsMagnet,
      @Schema(nullable = true) OffsetDateTime quotedAt,
      @Size(max = 2000) String note) {}

  // ================================================================ 对照表（输出）

  /** 对照表的一行：一条渠道 × (运费、时效、可承运性)。 */
  public record FreightEstimateLine(
      UUID channelId,
      String channelCode,
      String channelName,
      String carrierName,
      /** PASS 可承运 · REVIEW 需人工确认 · BLOCK 硬拦截（承运商会拒收）。 */
      String eligibility,
      /** 逐条理由（来自上游 10.1.1.2 的服务端判定，本模块不重写规则）。 */
      List<String> eligibilityReasons,
      /** RATE_CARD 按费率卡 · CHANNEL_FLAT 回退渠道单值 · NO_RULE 规则不全算不出。 */
      String rateSource,
      String basis,
      @Schema(nullable = true) BigDecimal chargeableQty,
      @Schema(nullable = true) BigDecimal volumetricQty,
      @Schema(nullable = true) BigDecimal baseCharge,
      @Schema(nullable = true) BigDecimal fuelFee,
      @Schema(nullable = true) BigDecimal remoteFee,
      @Schema(nullable = true) BigDecimal peakFee,
      @Schema(nullable = true) BigDecimal insuranceFee,
      @Schema(nullable = true) BigDecimal totalCharge,
      String currency,
      @Schema(nullable = true) Integer transitMinDays,
      @Schema(nullable = true) Integer transitMaxDays,
      /** false = 这条渠道算不出钱（{@code rateSource=NO_RULE}），**不是 0 元**。 */
      boolean quoteable,
      /** 算式轨迹：命中哪几档、为什么按这个量、进位与舍入怎么做的。 */
      List<String> trace,
      List<String> notes) {}

  /**
   * 一整张对照表。
   *
   * <p>🔴 {@code mixedCurrency=true} 时 {@code cheapestTotal}/{@code dearestTotal}
   * <b>只在主币种内</b>统计，不同币种的行没有参与比较 —— 把两种币种的金额排成一列
   * 是本模块最容易出的错，所以这里把它做成一个显式标志而不是让使用者自己看出来。
   */
  public record FreightEstimateResult(
      OffsetDateTime quotedAt,
      String destCountry,
      String shopCode,
      BigDecimal weightKg,
      @Schema(nullable = true) BigDecimal volumeCm3,
      Integer pieces,
      int channelCount,
      int quoteableCount,
      long passCount,
      long reviewCount,
      long blockCount,
      String currency,
      boolean mixedCurrency,
      @Schema(nullable = true) BigDecimal cheapestTotal,
      @Schema(nullable = true) BigDecimal dearestTotal,
      /** 恒为 RULE_ESTIMATE —— 试算口径，不是实际结算成本。 */
      String costBasis,
      @Schema(nullable = true) UUID savedQuoteId,
      List<FreightEstimateLine> lines,
      String note) {}

  // ================================================================ 费率卡（规则集）

  /** 一个计分档位。下界恒闭；上界闭开由 {@code toQtyInclusive} 决定；{@code toQty} 空 = 无上限。 */
  public record RateBandItem(
      @Positive int bandNo,
      @NotNull @PositiveOrZero BigDecimal fromQty,
      @Schema(nullable = true) BigDecimal toQty,
      boolean toQtyInclusive,
      @NotNull @PositiveOrZero BigDecimal rateAmount,
      @Size(max = 500) String note) {}

  /** 一张费率卡：档位 / 首重续重 / 舍入口径的容器。 */
  public record RateCardRow(
      UUID id,
      UUID channelId,
      String channelCode,
      String channelName,
      String carrierName,
      /** 渠道侧的计费方式（与 {@code basis} 对应，V140 trigger (a) 保证两者一致）。 */
      String channelBillingMode,
      String currency,
      String status,
      String chargeModel,
      String basis,
      String roundingMode,
      int roundingScale,
      @Schema(nullable = true) BigDecimal volumetricDivisor,
      @Schema(nullable = true) BigDecimal firstQty,
      @Schema(nullable = true) BigDecimal firstAmount,
      @Schema(nullable = true) BigDecimal additionalQty,
      @Schema(nullable = true) BigDecimal additionalAmount,
      List<RateBandItem> bands,
      String note,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 分国家规则：偏远标记 + 试算时效区间。 */
  public record ChannelCountryRule(
      UUID channelId,
      String channelCode,
      String countryCode,
      boolean remoteArea,
      @Schema(nullable = true) Integer transitMinDays,
      @Schema(nullable = true) Integer transitMaxDays,
      String note) {}

  public record RateCardOverview(List<RateCardRow> cards, List<ChannelCountryRule> countryRules, String note) {}

  /**
   * 登记 / 改一张费率卡。
   *
   * <p>{@code bands} 是<b>整体替换</b>语义：少发一档就是静默丢一档（丢掉的那一段货会
   * 算出 0 元运费），所以前端必须提交全集。{@code status=ACTIVE} 时 V140 的触发器会校验
   * 「从 0 起连续覆盖」，不满足直接拒。
   */
  public record SaveRateCardRequest(
      @Schema(nullable = true) UUID cardId,
      @NotNull UUID channelId,
      @NotBlank @Size(max = 16) String status,
      @NotBlank @Size(max = 32) String chargeModel,
      @NotBlank @Size(max = 16) String basis,
      @NotBlank @Size(max = 16) String roundingMode,
      @Min(0) @Max(6) int roundingScale,
      @Schema(nullable = true) BigDecimal volumetricDivisor,
      @Schema(nullable = true) BigDecimal firstQty,
      @Schema(nullable = true) BigDecimal firstAmount,
      @Schema(nullable = true) BigDecimal additionalQty,
      @Schema(nullable = true) BigDecimal additionalAmount,
      @NotNull @Size(max = 50) List<@Valid RateBandItem> bands,
      @Size(max = 2000) String note,
      @PositiveOrZero long version) {}

  /** 登记 / 改一条分国家规则（国家必须在该渠道的适用国家里，否则 DB 层拒）。 */
  public record SaveCountryRuleRequest(
      @NotNull UUID channelId,
      @NotBlank @Size(min = 2, max = 2) String countryCode,
      boolean remoteArea,
      @Schema(nullable = true) @Min(0) Integer transitMinDays,
      @Schema(nullable = true) @Min(0) Integer transitMaxDays,
      @Size(max = 500) String note) {}

  // ================================================================ 试算单

  /** 试算单头（包裹参数回显 + 汇总）。 */
  public record FreightQuoteRow(
      UUID id,
      /** 恒为 RULE_ESTIMATE —— DB 层 CHECK 只允许这一个取值。 */
      String costBasis,
      String destCountry,
      String shopCode,
      BigDecimal weightKg,
      @Schema(nullable = true) BigDecimal lengthCm,
      @Schema(nullable = true) BigDecimal widthCm,
      @Schema(nullable = true) BigDecimal heightCm,
      int pieces,
      @Schema(nullable = true) BigDecimal declaredValue,
      @Schema(nullable = true) String declaredCurrency,
      boolean containsBattery,
      boolean containsLiquid,
      boolean containsMagnet,
      List<String> categoryCodes,
      OffsetDateTime quotedAt,
      String currency,
      boolean mixedCurrency,
      int channelCount,
      int passCount,
      int reviewCount,
      int blockCount,
      @Schema(nullable = true) BigDecimal cheapestTotal,
      @Schema(nullable = true) BigDecimal dearestTotal,
      String note,
      OffsetDateTime createdAt) {}

  /** 试算单的一行（与对照表同构，另存计算轨迹原文）。 */
  public record FreightQuoteLineRow(
      UUID channelId,
      String channelCode,
      String channelName,
      String carrierName,
      String eligibility,
      String eligibilityReason,
      String rateSource,
      String basis,
      @Schema(nullable = true) BigDecimal chargeableQty,
      @Schema(nullable = true) BigDecimal volumetricQty,
      @Schema(nullable = true) BigDecimal baseCharge,
      @Schema(nullable = true) BigDecimal fuelFee,
      @Schema(nullable = true) BigDecimal remoteFee,
      @Schema(nullable = true) BigDecimal peakFee,
      @Schema(nullable = true) BigDecimal insuranceFee,
      @Schema(nullable = true) BigDecimal totalCharge,
      String currency,
      @Schema(nullable = true) Integer transitMinDays,
      @Schema(nullable = true) Integer transitMaxDays,
      boolean quoteable,
      String calcTrace) {}

  public record FreightQuoteDetail(FreightQuoteRow quote, List<FreightQuoteLineRow> lines) {}

  public record FreightQuoteSummary(
      long total, long withBlock, long withReview, long mixedCurrency, long quoteable) {}

  public record FreightQuotePage(
      List<FreightQuoteRow> items, int page, int size, long total, int totalPages, FreightQuoteSummary summary) {}

  /** 保存试算单的请求体：包裹参数 + 「是否落库」。 */
  public record SaveQuoteRequest(@NotNull @Valid FreightEstimateRequest request) {}
}
