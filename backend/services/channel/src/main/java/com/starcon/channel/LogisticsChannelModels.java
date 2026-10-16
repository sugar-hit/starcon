package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 10.1.1.2 渠道配置 的传输模型。
 *
 * <p>蓝图：「配置具体物流渠道：所属承运商、渠道代码、适用店铺/国家/品类、计费方式
 * （按重/按件/按体积）、附加费（燃油、偏远、旺季）、申报限制、保价规则」，
 * 输出「渠道配置；供运费试算与自动匹配使用」。
 *
 * <p>载荷一律用具名 {@code record} 而不是 {@code Map<String,Object>}：Map 的值类型会被
 * springdoc 推成 object，OpenAPI codegen 出来是空对象 {@code {}}（9.6.1.3 已踩过）。
 *
 * <p>🔴 金额与数量一律 {@link BigDecimal}，**没有 double**：基础单价、最低收费、附加费、
 * 申报价值、保价上限、保费都是钱；重量与体积折算系数是数量。AGENTS.md
 * 「禁止浮点记账」在这里是硬约束。
 *
 * <p>🔴 本模块最要紧的一件事是 {@link RestrictionVerdict}：**渠道限制由服务端判定**，
 * 前端拿到的只是判定结果，**不允许前端自己拿限制字段去比对**。蓝图的加粗句是
 * 「渠道限制（禁限品类、电池液体、磁力）要硬拦截，否则会被承运商拒收产生额外费用」——
 * 一个能被绕过、也能被漏掉的判断不算硬拦截。
 */
public final class LogisticsChannelModels {
  private LogisticsChannelModels() {}

  // ================================================================ 能力声明

  /**
   * 一个能力维度。
   *
   * <p>{@code available=false} 时必须同时给出 {@code ownerModule}（缺口归属模块）与
   * {@code impact}（缺了会怎样）。**不用空列表或 0 冒充「这一项没有数据」**。
   */
  public record ChannelCapabilityDimension(
      String dimension,
      String description,
      boolean available,
      String source,
      String ownerModule,
      String impact) {}

  public record LogisticsChannelCapabilities(
      List<ChannelCapabilityDimension> dimensions,
      List<String> billingModes,
      List<String> surchargeKinds,
      List<String> restrictionKinds,
      List<String> restrictionModes,
      List<String> statusCodes,
      List<String> readinessCodes,
      List<String> scopeKinds,
      String boundaryNote,
      String restrictionNote) {}

  // ================================================================ 渠道配置

  /** 适用维度里的一项（每值一行，不是逗号串）。 */
  public record ChannelScopeItem(String scopeKind, String scopeValue) {}

  /** 附加费里的一项。旺季费带生效区间，其余两类不带。 */
  public record ChannelSurchargeItem(
      String surchargeKind,
      String chargeMode,
      BigDecimal amount,
      @Schema(nullable = true) OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      String note) {}

  /** 渠道限制里的一项（禁限品类 / 电池 / 液体 / 磁力）。 */
  public record ChannelRestrictionItem(
      String restrictionKind,
      String subject,
      String restrictionMode,
      String carrierReason) {}

  /**
   * 渠道配置的一行。
   *
   * <p>承运商名称（{@code carrierName} / {@code carrierCode}）是**同库读 10.1.1.1 的承运商
   * 档案**得到的展示快照，**不可编辑** —— 改承运商身份要回 10.1.1.1 去做，本模块
   * 的写请求一个承运商字段都不接受。
   *
   * <p>{@code activeSurcharges} 是按**读取时刻**算出来的当前生效附加费：旺季费只有
   * 落在生效区间内才在这里，其余时候它只在 {@code surcharges} 全集里。
   */
  public record LogisticsChannelRow(
      UUID id,
      // === 上游 10.1.1.1 只读引用 ===
      UUID carrierProfileId,
      String carrierCode,
      String carrierName,
      String carrierAvailability,
      // === 本模块主权 ===
      String channelCode,
      String channelName,
      String status,
      String billingMode,
      String billingCurrency,
      BigDecimal baseRate,
      BigDecimal chargeDivisor,
      BigDecimal minCharge,
      BigDecimal declarationMinValue,
      BigDecimal declarationMaxValue,
      @Schema(nullable = true) String declarationCurrency,
      String insuranceMode,
      BigDecimal insuranceRatePct,
      BigDecimal insuranceMinFee,
      @Schema(nullable = true) BigDecimal insuranceMaxValue,
      List<ChannelScopeItem> scopes,
      List<ChannelSurchargeItem> surcharges,
      List<ChannelSurchargeItem> activeSurcharges,
      List<ChannelRestrictionItem> restrictions,
      // === 判定 ===
      String readiness,
      String readinessMessage,
      /** 是否被当前读取时刻的旺季区间命中（界面上要能一眼看出旺季）。 */
      boolean peakSeasonActive,
      String note,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 列表分页信封；{@code summary} 不受 limit/offset 影响。 */
  public record LogisticsChannelPage(
      List<LogisticsChannelRow> items,
      int page,
      int size,
      long total,
      int totalPages,
      LogisticsChannelSummary summary) {}

  /** 筛选结果上的真聚合（独立查询，不随 limit/offset 变）。 */
  public record LogisticsChannelSummary(
      long total,
      long ready,
      long noBaseRate,
      long noScope,
      long suspended,
      long draft,
      long withRestrictions,
      long peakSeasonActive) {}

  /** 概览：全量口径的计费方式 / 限制分布，不受列表筛选与分页影响。 */
  public record LogisticsChannelCount(String code, String label, long count) {}

  public record LogisticsChannelOverview(
      LogisticsChannelSummary summary,
      List<LogisticsChannelCount> byBillingMode,
      List<LogisticsChannelCount> byRestrictionKind,
      String note) {}

  // ================================================================ 写入口

  /**
   * 登记 / 改一个渠道配置。
   *
   * <p>整体替换语义：适用维度、附加费、限制三项都按提交的整份替换。少发一条就是
   * 静默丢失（尤其是限制 —— 少发一条意味着一个本该被拦的品类放行了），
   * 所以前端必须提交全集。
   *
   * <p>{@code channelId} 为空 = 新建（此时 {@code carrierProfileId} 必填）；
   * 非空 = 改配置，此时渠道代码与所属承运商都不可改（渠道代码由 DB 层 trigger b
   * 钉死，所属承运商由服务层比对），{@code version} 是乐观锁。
   */
  public record SaveChannelRequest(
      @Schema(nullable = true) UUID channelId,
      @Schema(nullable = true) UUID carrierProfileId,
      @NotBlank @Size(max = 64) String channelCode,
      @NotBlank @Size(max = 200) String channelName,
      @NotBlank @Size(max = 16) String status,
      @NotBlank @Size(max = 16) String billingMode,
      @NotBlank @Size(max = 3) String billingCurrency,
      @NotNull @PositiveOrZero BigDecimal baseRate,
      @Schema(nullable = true) @PositiveOrZero BigDecimal chargeDivisor,
      @Schema(nullable = true) @PositiveOrZero BigDecimal minCharge,
      @Schema(nullable = true) @PositiveOrZero BigDecimal declarationMinValue,
      @Schema(nullable = true) @PositiveOrZero BigDecimal declarationMaxValue,
      @Schema(nullable = true) @Size(max = 3) String declarationCurrency,
      @NotBlank @Size(max = 16) String insuranceMode,
      @Schema(nullable = true) @DecimalMin("0") @DecimalMax("100") BigDecimal insuranceRatePct,
      @Schema(nullable = true) @PositiveOrZero BigDecimal insuranceMinFee,
      @Schema(nullable = true) @PositiveOrZero BigDecimal insuranceMaxValue,
      @NotNull @Size(max = 200) List<ChannelScopeItem> scopes,
      @NotNull @Size(max = 3) List<ChannelSurchargeItem> surcharges,
      @NotNull @Size(max = 200) List<ChannelRestrictionItem> restrictions,
      @Size(max = 2000) String note,
      @PositiveOrZero long version) {}

  // ================================================================ 渠道限制判定

  /**
   * 一次可承运性判定请求。
   *
   * <p>这就是下游（10.1.2.1 运费试算 / 10.1.2.2 渠道比价 / 9.3.2.1 分仓 /
   * 10.2.1.2 运单申请）在**下单前**问的那个问题：这个包裹能不能走这条渠道。
   *
   * <p>刻意做成**一次请求带多件候选渠道 + 货件属性**，而不是「先查限制再自己判断」：
   * 后者就是那个「只存一个字段然后前端自己判断」的假活 —— 判断规则会散落到
   * 每个调用方，而总有一个调用方会漏掉「磁力」这一条。
   */
  public record ChannelEligibilityRequest(
      /** 要判定哪些渠道。留空 = 判定本租户全部启用中的渠道。 */
      @Schema(nullable = true) @Size(max = 100) List<UUID> channelIds,
      /** 目的国（两位大写）。留空 = 不按国家判（渠道没限定该国时不拦）。 */
      @Schema(nullable = true) @Size(min = 2, max = 2) String countryCode,
      /** 适用店铺编码。留空 = 不按店铺判。 */
      @Schema(nullable = true) @Size(max = 64) String shopCode,
      /** 货件涉及的品类编码 —— 与禁限品类比对的就是它。 */
      @NotNull @Size(max = 50) List<@Size(max = 64) String> categoryCodes,
      boolean containsBattery,
      boolean containsLiquid,
      boolean containsMagnet,
      /** 申报价值。留空 = 不按申报限制判。 */
      @Schema(nullable = true) @PositiveOrZero BigDecimal declaredValue,
      /** 保价金额。留空 = 不按保价上限判。 */
      @Schema(nullable = true) @PositiveOrZero BigDecimal insuredValue) {}

  /** 一条渠道的判定结论。 */
  public record ChannelEligibilityVerdict(
      UUID channelId,
      String channelCode,
      String channelName,
      String carrierName,
      /** PASS 可承运 · REVIEW 需人工确认 · BLOCK 硬拦截（承运商会拒收） */
      String verdict,
      /** 硬拦截的理由原文（承运商侧理由 + 命中哪条限制），逐条给出，不合并成一句话。 */
      List<String> blockingReasons,
      /** 需人工确认的理由（不拦截，但不允许自动放行）。 */
      List<String> reviewReasons) {}

  public record ChannelEligibilityResult(
      List<ChannelEligibilityVerdict> verdicts,
      long pass,
      long review,
      long block,
      String note) {}

  // ================================================================ 判定口径

  /**
   * 渠道限制判定的<b>单一实现</b>（写入前校验与判定端点都调它）。
   *
   * <p>判定结果只有三档，含义互不重叠：
   *
   * <ol>
   *   <li>{@code BLOCK} —— 命中 {@code BLOCK} 级限制，或超保价上限。承运商会拒收。
   *   <li>{@code REVIEW} —— 命中 {@code MANUAL_REVIEW} 级限制，或渠道没启用/没配齐。
   *       不拦，但<b>不允许自动放行</b>。
   *   <li>{@code PASS} —— 没有命中任何限制，且渠道可用。
   * </ol>
   *
   * <p>三档而不是「是/否」两档，是因为把「需要人看一眼」混进「可以自动放行」，
   * 就等于把拒收风险交给了运气。
   */
  public final class RestrictionVerdict {
    private RestrictionVerdict() {}

    public static final String PASS = "PASS";
    public static final String REVIEW = "REVIEW";
    public static final String BLOCK = "BLOCK";

    /** 三档里更严重的一档（BLOCK > REVIEW > PASS）。 */
    public static String worse(String left, String right) {
      return rank(left) >= rank(right) ? left : right;
    }

    private static int rank(String verdict) {
      return switch (verdict) {
        case BLOCK -> 2;
        case REVIEW -> 1;
        default -> 0;
      };
    }

    /** 判定门槛从轻到重；用于在多条限制命中时取最严的一档。 */
    public static String verdictForMode(String restrictionMode) {
      return switch (restrictionMode == null ? "" : restrictionMode) {
        case "BLOCK" -> BLOCK;
        case "MANUAL_REVIEW" -> REVIEW;
        default -> PASS;
      };
    }

    /** 给界面看的一句话解释；与三档一一对应，不猜。 */
    public static String message(String verdict) {
      return switch (verdict) {
        case BLOCK -> "硬拦截：承运商会拒收，产生额外费用";
        case REVIEW -> "需人工确认：不会自动放行";
        default -> "可承运";
      };
    }
  }
}
