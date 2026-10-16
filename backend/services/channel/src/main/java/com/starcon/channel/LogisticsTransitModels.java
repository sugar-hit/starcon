package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
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
 * 10.1.1.3 时效与限制 的传输模型。
 *
 * <p>蓝图：「维护渠道时效（分国家分重量段）与限制（尺寸上限、重量上限、禁限品类、
 * 电池/液体/危险品标记要求），并在选品与刊登阶段提示」，输出「时效估算；限制命中提示」。
 *
 * <p>载荷一律用具名 {@code record} 而不是 {@code Map<String,Object>}：Map 的值类型会被
 * springdoc 推成 object，OpenAPI codegen 出来是空对象 {@code {}}（9.6.1.3 已踩过）。
 *
 * <p>🔴 重量与尺寸一律 {@link BigDecimal}，**没有 double**：重量段边界、尺寸上限、
 * 重量上限都是数量。AGENTS.md「禁止浮点记账」在这里是硬约束。
 *
 * <p>🔴 本模块最要紧的一件事是 {@link LimitHit}：**限制命中提示在服务端算**。
 * 前端拿到的只是结论与逐条理由，**不允许前端自己拿上限 / 时效段自己比对**。
 * 蓝图的加粗句是「渠道限制（禁限品类、电池液体、磁力）要硬拦截，否则会被承运商
 * 拒收产生额外费用」—— 一个能被绕过、也能被漏掉的判断不算硬拦截。
 */
public final class LogisticsTransitModels {
  private LogisticsTransitModels() {}

  // ================================================================ 能力声明

  /**
   * 一个能力维度。
   *
   * <p>{@code available=false} 时必须同时给出 {@code ownerModule}（缺口归属模块）与
   * {@code impact}（缺了会怎样）。**不用空列表或 0 冒充「这一项没有数据」**。
   */
  public record TransitCapabilityDimension(
      String dimension,
      String description,
      boolean available,
      String source,
      String ownerModule,
      String impact) {}

  public record LogisticsTransitCapabilities(
      List<TransitCapabilityDimension> dimensions,
      List<String> serviceLevels,
      List<String> flagKinds,
      List<String> requirementModes,
      List<String> readinessCodes,
      List<String> statusCodes,
      String boundaryNote,
      String limitationNote) {}

  // ================================================================ 时效规则

  /**
   * 时效规则的一行：某渠道发往某国家、货重落在这个重量段、按这个服务等级的时效承诺。
   *
   * <p>渠道编码 / 名称是**同库读 10.1.1.2 渠道配置**得到的展示快照，不可编辑 ——
   * 渠道身份归上游，本模块的写请求一个渠道身份字段都不接受。
   *
   * <p>{@code effectiveNow} 是按**读取时刻**算出来的：生效区间含空的是常驻，
   * 其余只有落在区间内才算当前生效。
   */
  public record TransitRuleRow(
      UUID id,
      // === 上游 10.1.1.2 只读引用 ===
      UUID channelId,
      String channelCode,
      String channelName,
      // === 本模块主权 ===
      String countryCode,
      String serviceLevel,
      BigDecimal weightMinKg,
      /** 半开区间 [min, max) 的上限；null = 无上限（最后一段）。 */
      @Schema(nullable = true) BigDecimal weightMaxKg,
      int transitDaysMin,
      int transitDaysMax,
      /** 截单时间（当地墙钟，HH:mm）。 */
      String cutoffTime,
      @Schema(nullable = true) OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      String status,
      String readiness,
      String readinessMessage,
      boolean effectiveNow,
      String note,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 列表分页信封；{@code summary} 不受 limit/offset 影响。 */
  public record TransitRulePage(
      List<TransitRuleRow> items,
      int page,
      int size,
      long total,
      int totalPages,
      TransitRuleSummary summary) {}

  /** 筛选结果上的真聚合（独立查询，不随 limit/offset 变）。 */
  public record TransitRuleSummary(
      long total,
      long ready,
      long noTransit,
      long noBand,
      long disabled,
      long effectiveNow,
      long countries,
      long channels) {}

  // ================================================================ 尺寸 / 重量上限

  /**
   * 尺寸 / 重量上限的一行。
   *
   * <p>{@code countryCode} 为 null = 渠道级（对所有国家生效）；非 null = 该国更严的
   * 一条。判定取**最严**的一条，所以国家级比渠道级更宽的数据在 DB 层就写不进来。
   */
  public record SizeLimitRow(
      UUID id,
      UUID channelId,
      String channelCode,
      String channelName,
      @Schema(nullable = true) String countryCode,
      @Schema(nullable = true) BigDecimal maxLengthCm,
      @Schema(nullable = true) BigDecimal maxWidthCm,
      @Schema(nullable = true) BigDecimal maxHeightCm,
      @Schema(nullable = true) BigDecimal maxGirthCm,
      @Schema(nullable = true) BigDecimal maxWeightKg,
      /** 渠道级 / 国家级，供列表一眼区分。 */
      String scopeLabel,
      String note,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  public record SizeLimitPage(
      List<SizeLimitRow> items,
      int page,
      int size,
      long total,
      int totalPages,
      long channelLevel,
      long countryLevel) {}

  // ================================================================ 标记要求

  /**
   * 电池 / 液体 / 危险品 / 磁力 标记要求的一行。
   *
   * <p>它与 10.1.1.2 的「渠道限制」回答两个不同的问题：渠道限制回答「能不能发」，
   * 本表回答「能发的话要贴什么」。
   */
  public record MarkingRequirementRow(
      UUID id,
      UUID channelId,
      String channelCode,
      String channelName,
      String flagKind,
      String requirementMode,
      String markingCode,
      String carrierReason,
      /** 上游 10.1.1.2 对同一个 flag 的判定（可能为空 = 上游没登记限制）。 */
      @Schema(nullable = true) String upstreamRestrictionMode,
      /** 结论与上游是否一致；false = 需要有人去把它改掉（触发器会挡，兜底是读时可见）。 */
      boolean consistentWithUpstream,
      String note,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  public record MarkingRequirementPage(
      List<MarkingRequirementRow> items,
      int page,
      int size,
      long total,
      int totalPages,
      long labelRequired,
      long blocked,
      long inconsistent) {}

  // ================================================================ 概览

  public record LogisticsTransitCount(String code, String label, long count) {}

  public record LogisticsTransitOverview(
      TransitRuleSummary transitSummary,
      List<LogisticsTransitCount> byServiceLevel,
      List<LogisticsTransitCount> byFlagKind,
      long sizeLimitTotal,
      long markingTotal,
      String note) {}

  // ================================================================ 写入口

  /**
   * 登记 / 改一条时效规则。
   *
   * <p>{@code transitRuleId} 为空 = 新建（此时 {@code channelId} 必填）；非空 = 改，
   * 此时 {@code version} 是乐观锁。渠道身份不可改（渠道归 10.1.1.2）。
   */
  public record SaveTransitRuleRequest(
      @Schema(nullable = true) UUID transitRuleId,
      @Schema(nullable = true) UUID channelId,
      @NotBlank @Size(min = 2, max = 2) String countryCode,
      @NotBlank @Size(max = 16) String serviceLevel,
      @NotNull @DecimalMin("0") BigDecimal weightMinKg,
      /** 半开区间上限；留空 = 无上限（最后一段，如「>45kg」）。 */
      @Schema(nullable = true) @DecimalMin("0") BigDecimal weightMaxKg,
      /** 时效天数至少 1（服务层给出可读理由，DB 层 CHECK + 触发器兜底）。 */
      @PositiveOrZero int transitDaysMin,
      @PositiveOrZero int transitDaysMax,
      @NotBlank @Size(max = 8) String cutoffTime,
      @Schema(nullable = true) OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      @NotBlank @Size(max = 16) String status,
      @Size(max = 2000) String note,
      @PositiveOrZero long version) {}

  /** 登记 / 改一条尺寸 / 重量上限。 */
  public record SaveSizeLimitRequest(
      @Schema(nullable = true) UUID sizeLimitId,
      @Schema(nullable = true) UUID channelId,
      /** 留空 = 渠道级（对所有国家生效）。 */
      @Schema(nullable = true) @Size(min = 2, max = 2) String countryCode,
      @Schema(nullable = true) @Positive BigDecimal maxLengthCm,
      @Schema(nullable = true) @Positive BigDecimal maxWidthCm,
      @Schema(nullable = true) @Positive BigDecimal maxHeightCm,
      @Schema(nullable = true) @Positive BigDecimal maxGirthCm,
      @Schema(nullable = true) @Positive BigDecimal maxWeightKg,
      @Size(max = 2000) String note,
      @PositiveOrZero long version) {}

  /** 登记 / 改一条标记要求。 */
  public record SaveMarkingRequirementRequest(
      @Schema(nullable = true) UUID markingId,
      @Schema(nullable = true) UUID channelId,
      @NotBlank @Size(max = 32) String flagKind,
      @NotBlank @Size(max = 32) String requirementMode,
      @Size(max = 64) String markingCode,
      @Size(max = 500) String carrierReason,
      @Size(max = 2000) String note,
      @PositiveOrZero long version) {}

  // ================================================================ 时效估算

  /**
   * 一次时效估算请求（分国家 + 重量 → 命中重量段 → 预计到达）。
   *
   * <p>这正是下游 5.3.1.1 测算（时效影响转化）与 10.2.2.1 面单模板要的那个问题：
   * 「这个包裹走这条渠道几天到」。
   */
  public record TransitEstimateRequest(
      /** 要估算哪些渠道。留空 = 估算本租户全部**适用该国家**的启用中渠道。 */
      @Schema(nullable = true) @Size(max = 100) List<UUID> channelIds,
      @NotBlank @Size(min = 2, max = 2) String countryCode,
      @NotNull @Positive BigDecimal weightKg,
      /** 留空 = 该国家所有服务等级都估一遍。 */
      @Schema(nullable = true) @Size(max = 16) String serviceLevel,
      /** 发货时刻。留空 = 取服务端当前时刻（UTC）。 */
      @Schema(nullable = true) OffsetDateTime shipAt) {}

  /** 一条渠道的时效估算结论。 */
  public record TransitEstimateItem(
      UUID channelId,
      String channelCode,
      String channelName,
      String countryCode,
      String serviceLevel,
      BigDecimal queriedWeightKg,
      String matchedBand,
      int transitDaysMin,
      int transitDaysMax,
      String cutoffTime,
      OffsetDateTime earliestArrival,
      OffsetDateTime latestArrival,
      String effectiveWindowNote,
      String matchedRuleNote) {}

  public record TransitEstimateResult(
      OffsetDateTime shipAt,
      List<TransitEstimateItem> items,
      long matched,
      long unmatched,
      String note) {}

  // ================================================================ 限制命中提示

  /**
   * 一次限制命中提示请求（**选品与刊登阶段**问的那个问题）。
   *
   * <p>{@code categoryCodes} / {@code containsBattery} 等就是上游 3.7.2 SKU 安全属性
   * 的输入形状：<b>入参</b>，不是跨服务查询。channel 服务不读 catalog 的库。
   */
  public record LimitPrecheckRequest(
      @Schema(nullable = true) @Size(max = 100) List<UUID> channelIds,
      @Schema(nullable = true) @Size(min = 2, max = 2) String countryCode,
      /** SKU 品类编码（3.7.2），与禁限品类比对的就是它。 */
      @NotNull @Size(max = 50) List<@Size(max = 64) String> categoryCodes,
      boolean containsBattery,
      boolean containsLiquid,
      boolean containsMagnet,
      /** 危险品：V136 的四种 restriction_kind 里没有这一类，由本模块的标记要求覆盖。 */
      boolean containsDangerousGoods,
      @Schema(nullable = true) @Positive BigDecimal weightKg,
      @Schema(nullable = true) @Positive BigDecimal lengthCm,
      @Schema(nullable = true) @Positive BigDecimal widthCm,
      @Schema(nullable = true) @Positive BigDecimal heightCm,
      @Schema(nullable = true) OffsetDateTime shipAt) {}

  /**
   * 一条命中记录。
   *
   * <p>刻意<b>结构化</b>而不是拼成一句话字符串：界面上要能按「尺寸超了 / 品类被禁 /
   * 必须贴标识」分组展示，并按 {@code hitKind} 决定图标与颜色。
   */
  public record LimitHit(
      /** RESTRICTED_CATEGORY / BATTERY / LIQUID / MAGNET / DANGEROUS_GOODS /
       * DIMENSION / WEIGHT / TRANSIT_MISSING / CHANNEL_NOT_READY */
      String hitKind,
      /** BLOCK 硬拦截 · REVIEW 需人工确认（不拦，但不允许自动放行）。 */
      String severity,
      /** 被命中的那个对象（品类编码 / 维度名 / flag）。 */
      String subject,
      String message,
      String carrierReason) {}

  /** 一条渠道的判定结论。 */
  public record LimitPrecheckVerdict(
      UUID channelId,
      String channelCode,
      String channelName,
      String countryCode,
      /** PASS 可承运 · REVIEW 需人工确认 · BLOCK 硬拦截（承运商会拒收） */
      String verdict,
      List<LimitHit> hits,
      @Schema(nullable = true) Integer transitDaysMin,
      @Schema(nullable = true) Integer transitDaysMax,
      String note) {}

  public record LimitPrecheckResult(
      OffsetDateTime evaluatedAt,
      List<LimitPrecheckVerdict> verdicts,
      long pass,
      long review,
      long block,
      String note) {}

  // ================================================================ 判定口径

  /**
   * 限制判定的<b>单一实现</b>（precheck 里汇总上游渠道限制、本模块尺寸重量上限与
   * 标记要求时都调它）。
   *
   * <p>判定结果只有三档，含义互不重叠：
   *
   * <ol>
   *   <li>{@code BLOCK} —— 命中 {@code BLOCK} 级限制 / 超尺寸或重量上限。
   *       承运商会拒收并产生额外费用。
   *   <li>{@code REVIEW} —— 命中 {@code MANUAL_REVIEW} 级限制、必须贴标识而货件没贴，
   *       或该重量段没有时效承诺。不拦，但<b>不允许自动放行</b>。
   *   <li>{@code PASS} —— 没有命中任何限制。
   * </ol>
   *
   * <p>三档而不是「是/否」两档，是因为把「需要人看一眼」混进「可以自动放行」，
   * 就等于把拒收风险交给了运气。
   */
  public final class LimitVerdict {
    private LimitVerdict() {}

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
    public static String severityForRequirement(String requirementMode) {
      return switch (requirementMode == null ? "" : requirementMode) {
        case "BLOCK" -> BLOCK;
        case "MANUAL_REVIEW" -> REVIEW;
        // 「可以走但必须贴标识」≠ 可以自动放行：漏打标识会在清关出问题
        case "LABEL_REQUIRED" -> REVIEW;
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
