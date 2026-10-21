package com.starcon.crm;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 15.2.2.2 索评规则的传输模型。
 *
 * <p>蓝图 15 卷 15.2.2.2 原文：
 *
 * <blockquote>
 *
 * 职责：配置索评策略：按店铺、按物流方式（自发货/平台仓）设置不同延后时间，避开节假日，支持 A/B 测试不同话术。 输入：规则配置；话术模板。 输出：生效策略 + 效果对比。 关系：上游 = 15.2.2.1；下游 = 15.1.1.2 发送。
 *
 * </blockquote>
 *
 * <p><b>建模纪律</b>：
 *
 * <ul>
 *   <li><b>规则是「配置」，不是「快照」</b>：15.2.2.1 的索评请求自带一份规则快照，
 *       本模块的规则是**可编辑的策略**（改了之后**新**的索评请求按新策略走，
 *       已经落库的分配记录仍按当时的快照算 —— 快照列建后不可改）。
 *   <li><b>话术分模板与变体</b>：模板是身份（叫什么、什么语言），
 *       变体是内容（A / B 两臂 + 权重）。A/B 测试的权重挂在变体上。
 *   <li><b>数量与时间一律 BigDecimal / OffsetDateTime</b>：延后小时数与权重用
 *       {@code numeric(19,6)}，时刻用带时区偏移量的 ISO-8601 字符串
 *       （用 {@code Timestamp.valueOf(LocalDateTime)} 会在东八区静默偏 −28800 秒）。
 *   <li><b>节假日是日历概念</b>：日期用 {@code YYYY-MM-DD}，不带时刻也不带时区；
 *       时区换算只发生在**排期时刻**上（规则上的 {@code timezone}）。
 * </ul>
 */
public final class ReviewRuleModels {

  private ReviewRuleModels() {}

  // ------------------------------------------------------------ 输入

  /**
   * 索评规则配置。
   *
   * <p>{@code shopId} 为空 = 适用于全部店铺；{@code logisticsMode} 为 {@code ANY} = 不限物流方式。
   * 命中优先级由服务层按「范围越具体越优先 → 同范围 priority 升序」判定，
   * DB 层另有唯一索引保证同一范围同一优先级不会有两条 ACTIVE 规则。
   */
  public record CrmReviewRuleConfigInput(
      @NotBlank @Size(max = 200) String ruleName,
      @Schema(nullable = true) UUID shopId,
      @NotBlank String logisticsMode,
      @NotNull
          @DecimalMin(value = "0.000001")
          @DecimalMax(value = "8760")
          @Schema(description = "时点：签收后延后多少小时才发索评")
          BigDecimal delayHours,
      @Schema(nullable = true) UUID templateId,
      Boolean abTestEnabled,
      Boolean avoidHolidays,
      @Size(max = 64) String holidayRegion,
      @Min(0) @Max(30) Integer holidayShiftDays,
      @Size(max = 64) String timezone,
      @Min(1) @Max(1000) Integer priority,
      @NotBlank String status,
      @Schema(nullable = true) String effectiveFrom,
      @Schema(nullable = true) String effectiveTo,
      @Size(max = 1000) String note) {}

  /** 话术变体（A / B 两臂之一）。 */
  public record CrmReviewTemplateVariantInput(
      @NotBlank String variantCode,
      @Size(max = 300) String subject,
      @Size(max = 4000) String body,
      @NotNull
          @DecimalMin(value = "0.000001")
          @DecimalMax(value = "100")
          @Schema(description = "权重：百分比，分桶时按累计权重落到 0..9999 的桶里")
          BigDecimal weight,
      @NotBlank String status) {}

  /** 话术模板（含变体）。变体至少一个 —— 没有变体的模板发不出任何话术。 */
  public record CrmReviewTemplateInput(
      @NotBlank @Size(max = 200) String templateName,
      @NotBlank @Size(max = 16) String locale,
      @NotBlank String status,
      @NotEmpty @Valid List<CrmReviewTemplateVariantInput> variants,
      @Size(max = 1000) String note) {}

  /**
   * 节假日。
   *
   * <p>{@code holidayDate} / {@code holidayEndDate} 都是 {@code YYYY-MM-DD}；
   * 后者为空 = 单日，非空 = 连休（春节等）。{@code region} 为空 = 全部区域。
   */
  public record CrmReviewHolidayInput(
      @NotBlank
          @Schema(description = "节假日开始日期，例如 2026-10-01")
          String holidayDate,
      @Schema(nullable = true) String holidayEndDate,
      @NotBlank @Size(max = 200) String holidayName,
      @Size(max = 64) String region,
      @NotBlank String kind,
      @Size(max = 1000) String note) {}

  /**
   * 把当前生效策略应用到一条索评请求上。
   *
   * <p>只给「索评请求」与「物流方式」：规则由服务层**按生效策略解析**得出，
   * 运营不指定规则 —— 指定规则就绕过了「生效策略」这件事本身。
   */
  public record CrmReviewAssignmentInput(
      @NotNull UUID reviewRequestId,
      @NotBlank String logisticsMode,
      @Size(max = 1000) String note) {}

  // ------------------------------------------------------------ 输出

  /** 能力维度：每一维都要说清「有没有、依据是什么、缺了会怎样」。 */
  public record CrmReviewRuleCapabilityDimension(
      String dimension,
      String description,
      boolean available,
      String source,
      String ownerModule,
      String impact) {}

  /** 能力声明：前端在渲染前就知道哪些动作存在，而不是靠试。 */
  public record CrmReviewRuleCapabilities(
      List<CrmReviewRuleCapabilityDimension> dimensions,
      List<String> ruleStatuses,
      List<String> logisticsModes,
      List<String> templateStatuses,
      List<String> variantCodes,
      List<String> holidayKinds,
      List<String> requestStatuses,
      String boundaryNote,
      String highlight) {}

  /** 一条索评规则。 */
  public record CrmReviewRuleRow(
      UUID id,
      String ruleNo,
      String ruleName,
      @Schema(nullable = true) UUID shopId,
      String shopName,
      String logisticsMode,
      BigDecimal delayHours,
      @Schema(nullable = true) UUID templateId,
      String templateName,
      boolean abTestEnabled,
      boolean avoidHolidays,
      String holidayRegion,
      Integer holidayShiftDays,
      String timezone,
      Integer priority,
      String status,
      @Schema(nullable = true) String effectiveFrom,
      @Schema(nullable = true) String effectiveTo,
      String note,
      String createdAt,
      String updatedAt,
      Long assignmentCount) {}

  /** 一个话术变体。 */
  public record CrmReviewVariantRow(
      UUID id,
      UUID templateId,
      String variantCode,
      String subject,
      String body,
      BigDecimal weight,
      String status,
      String createdAt,
      String updatedAt) {}

  /** 一个话术模板（含它的变体）。 */
  public record CrmReviewTemplateRow(
      UUID id,
      String templateNo,
      String templateName,
      String locale,
      String status,
      String note,
      String createdAt,
      String updatedAt,
      List<CrmReviewVariantRow> variants) {}

  /** 一个节假日。 */
  public record CrmReviewHolidayRow(
      UUID id,
      String holidayDate,
      @Schema(nullable = true) String holidayEndDate,
      String holidayName,
      String region,
      String kind,
      String note,
      String createdAt,
      String updatedAt) {}

  /** 一次「生效策略」的落地（= 效果对比的归属事实）。 */
  public record CrmReviewAssignmentRow(
      UUID id,
      UUID ruleId,
      String ruleNo,
      String ruleName,
      @Schema(nullable = true) UUID templateId,
      String templateName,
      @Schema(nullable = true) UUID variantId,
      String variantCode,
      UUID reviewRequestId,
      String requestNo,
      @Schema(nullable = true) UUID shopId,
      String shopName,
      String logisticsMode,
      String buyerRef,
      String salesOrderNo,
      @Schema(nullable = true) String deliveredAt,
      BigDecimal delayHours,
      String scheduledAt,
      Integer shiftedDays,
      String holidayName,
      String requestStatus,
      String note,
      String createdAt,
      String updatedAt) {}

  /**
   * 生效策略（蓝图输出句的第一项）。
   *
   * <p>🔴 为什么不放一个 {@code variant: CrmReviewVariantRow | null} 字段：
   * SpringDoc 对**嵌套 record** 的 {@code @Schema(nullable = true)} 不生效
   * （生成的 OpenAPI 里它就是一个裸 {@code $ref}），codegen 出来的 TS 类型会变成
   * 非空 —— 类型在骗人，运行时却是 null。所以这里改成
   * 「变体清单 + 本次分到的变体 id」两段：清单永远是数组（不会空），
   * 选中的那个用 {@code pickedVariantId} 指（可空，UUID 走的是基础类型路径，nullable 有效）。
   */
  public record CrmReviewEffectivePolicy(
      @Schema(nullable = true) UUID ruleId,
      String ruleNo,
      String ruleName,
      @Schema(nullable = true) UUID shopId,
      String shopName,
      String logisticsMode,
      BigDecimal delayHours,
      @Schema(nullable = true) UUID templateId,
      String templateName,
      boolean abTestEnabled,
      List<CrmReviewVariantRow> variants,
      @Schema(nullable = true) UUID pickedVariantId,
      String baseScheduledAt,
      String scheduledAt,
      Integer shiftedDays,
      String holidayName,
      boolean holidayAvoided,
      String matchedScope,
      String resolvedAt,
      String note) {}

  /** 效果对比的一行（按话术变体）。 */
  public record CrmReviewEffectVariantRow(
      @Schema(nullable = true) UUID variantId,
      String variantCode,
      @Schema(nullable = true) UUID templateId,
      String templateName,
      String subject,
      BigDecimal weight,
      long assigned,
      long sent,
      long recovered,
      @Schema(nullable = true) BigDecimal recoveryRate,
      @Schema(nullable = true) BigDecimal averageRating,
      String note) {}

  /** 效果对比的一行（按规则）。 */
  public record CrmReviewEffectRuleRow(
      UUID ruleId,
      String ruleNo,
      String ruleName,
      long assigned,
      long sent,
      long recovered,
      @Schema(nullable = true) BigDecimal recoveryRate,
      @Schema(nullable = true) BigDecimal averageRating) {}

  /** 效果对比（蓝图输出句的第二项）。 */
  public record CrmReviewEffectComparison(
      Integer scopeDays,
      String scopeFrom,
      String scopeTo,
      long totalAssignments,
      List<CrmReviewEffectVariantRow> variants,
      List<CrmReviewEffectRuleRow> byRule,
      String note) {}

  /** 店铺目录的一行（来自上游 channel 的真实清单）。 */
  public record CrmReviewRuleShopView(
      UUID id,
      String code,
      String name,
      String platformCode,
      String platformName,
      String operatingMode,
      String status) {}

  // ------------------------------------------------------------ 分页

  /** 规则列表分页。 */
  public record CrmReviewRulePage(
      List<CrmReviewRuleRow> items, int page, int size, long totalElements, int totalPages) {}

  /** 话术模板列表分页。 */
  public record CrmReviewTemplatePage(
      List<CrmReviewTemplateRow> items, int page, int size, long totalElements, int totalPages) {}

  /** 节假日列表分页。 */
  public record CrmReviewHolidayPage(
      List<CrmReviewHolidayRow> items, int page, int size, long totalElements, int totalPages) {}

  /** 落地记录列表分页。 */
  public record CrmReviewAssignmentPage(
      List<CrmReviewAssignmentRow> items, int page, int size, long totalElements, int totalPages) {}

  /**
   * 可被应用策略的索评请求（15.2.2.1 的一条任务）。
   *
   * <p>本模块**只读**它 —— 写入所有者仍然只有 15.2.2.1 一个。
   */
  public record CrmReviewRuleCandidate(
      UUID requestId,
      String requestNo,
      @Schema(nullable = true) UUID shopId,
      String shopName,
      String buyerRef,
      String salesOrderNo,
      String platformCode,
      @Schema(nullable = true) String deliveredAt,
      String status,
      String rejectReason) {}

  /** 可被应用策略的索评请求分页。 */
  public record CrmReviewRuleCandidatePage(
      List<CrmReviewRuleCandidate> items, int page, int size, long totalElements, int totalPages) {}
}
