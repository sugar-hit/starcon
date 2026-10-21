package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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
 * 6.1.1.3 供应商评级的传输模型。
 *
 * <p>蓝图 06 卷 06-purchase-and-supply.md 的 6.1.1.3 原文：
 *
 * <blockquote>
 *
 * <p>**职责**：按周期（默认月度）自动统计供应商表现：交期达成率、质量合格率、响应时长、
 * 价格竞争力、售后配合度，综合打分并支持人工调整权重。**输入**：收货事件时间与质检结果；
 * 采购单沟通记录；权重配置。**输出**：评级结果与排名；评级变动历史；影响采购建议的默认供应商优先级。
 * **关系**：上游 = 6.4.2 质检、6.3.2.1 跟单；下游 = 3.6.2 报价优先级、6.2.1.2 补货建议
 * （优先选高分供应商）。**评级必须由客观数据算出**，人工加权分需单独标记，
 * 否则会变成纯主观打分。
 *
 * </blockquote>
 *
 * <p><b>本文件最重要的三条纪律</b>：
 *
 * <ol>
 *   <li><b>客观分与人工调整分��开存</b>（{@code objectiveScore} / {@code manualAdjustment} /
 *       {@code finalScore} 三者并存，且 {@code manuallyAdjusted} 单独标记）。
 *       折叠成一个「总分」之后，三个月后没人能回答「这个 A 是数据算出来的还是谁拍的」。</li>
 *   <li><b>维度不可用 ≠ 维度得 0 分</b>：维度的得分与原始指标一律是<b>可空</b>，
 *       null = 「这个周期没有可算的依据」，0 = 「有依据，而且很差」。
 *       两者在页面上必须长得不一样（与 6.1.1.1 的 M3/M4 同一条纪律）。</li>
 *   <li><b>每个数字都带口径</b>：比率给分子分母、时长给样本数、价格给样本 SKU 数与可比量 ——
 *       一个不说清「是按哪一段、几个样本算的」的数字在供应商评审里没有意义。</li>
 * </ol>
 *
 * <p><b>命名。</b>全仓契约被 codegen 合并进<b>一个扁平文件</b>、同名 key 后者覆盖前者且不报冲突，
 * 所以本文件的每一个类型都带 {@code SupplierRating} 前缀（逐个 grep 比对，零重叠）。
 */
public final class SupplierRatingModels {

  private SupplierRatingModels() {}

  // ================================================================ 枚举

  /** 评级周期类型。蓝图说「按周期（默认月度）」。 */
  public enum SupplierRatingPeriodType {
    MONTHLY("月度"),
    QUARTERLY("季度");

    private final String label;

    SupplierRatingPeriodType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 五个评级维度。
   *
   * <p>枚举而不���自由字符串：维度键会出现在权重配置、结果行、优先级出口三处，
   * 三处各写一份字符串拼错一个字母，界面上就会少一个维度而没人知道。
   */
  public enum SupplierRatingDimension {
    DELIVERY("交期达成率"),
    QUALITY("质量合格率"),
    RESPONSE("响应时长"),
    PRICE("价格竞争力"),
    SERVICE("售后配合度");

    private final String label;

    SupplierRatingDimension(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 评级等级。 */
  public enum SupplierRatingGrade {
    A("A 级"),
    B("B 级"),
    C("C 级"),
    D("D 级");

    private final String label;

    SupplierRatingGrade(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 评级变动的来源。 */
  public enum SupplierRatingChangeType {
    PERIOD_ROLL("跨周期滚动"),
    RECOMPUTE("同周期重算"),
    MANUAL_ADJUSTMENT("人工调整");

    private final String label;

    SupplierRatingChangeType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 能力声明

  /**
   * 能力声明里的一条。
   *
   * @param available 本模块**真的**做到了吗
   * @param ownerModule 这件事的归属模块（{@code available=false} 时说明它归谁）
   * @param impact 没做到的影响是什么
   */
  public record SupplierRatingCapability(
      String name,
      String label,
      boolean available,
      @Schema(nullable = true) String ownerModule,
      @Schema(nullable = true) String impact) {}

  /** 能力声明：五个维度各自有没有依据、上下游接上了没有。 */
  public record SupplierRatingCapabilities(
      List<SupplierRatingCapability> capabilities,
      List<String> dimensions,
      List<String> notes) {}

  // ================================================================ 词表

  /** 词表选项。 */
  public record SupplierRatingOption(String value, String label, String hint) {}

  /** 词表：周期类型 / 维度 / 等级 / 变动来源。 */
  public record SupplierRatingOptions(
      List<SupplierRatingOption> periodTypes,
      List<SupplierRatingOption> dimensions,
      List<SupplierRatingOption> grades,
      List<SupplierRatingOption> changeTypes) {}

  // ================================================================ 权重配置

  /**
   * 权重配置（租户级）。
   *
   * <p>五个权重之和**精确等于 1**（DB 层 CHECK 钉死）。「这个维度不参与」的正确表达是
   * 该维度权重为 0，而不是「权重和不为 1」。
   *
   * @param configured 这行是**真的配置**还是**系统默认口径**
   * @param defaultWeights 租户还没配时返回系统默认权重（界面据此显示「未配置，当前用默认」）
   */
  public record SupplierRatingPolicy(
      SupplierRatingPeriodType periodType,
      BigDecimal deliveryWeight,
      BigDecimal qualityWeight,
      BigDecimal responseWeight,
      BigDecimal priceWeight,
      BigDecimal serviceWeight,
      int responseTargetHours,
      BigDecimal gradeAMin,
      BigDecimal gradeBMin,
      BigDecimal gradeCMin,
      boolean configured,
      BigDecimal defaultDeliveryWeight,
      BigDecimal defaultQualityWeight,
      BigDecimal defaultResponseWeight,
      BigDecimal defaultPriceWeight,
      BigDecimal defaultServiceWeight,
      long version,
      @Schema(nullable = true) String note) {}

  /** 修改权重配置的入参。 */
  public record SupplierRatingPolicyInput(
      @NotNull SupplierRatingPeriodType periodType,
      @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal deliveryWeight,
      @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal qualityWeight,
      @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal responseWeight,
      @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal priceWeight,
      @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal serviceWeight,
      @NotNull @Min(1) Integer responseTargetHours,
      @Schema(nullable = true) @DecimalMin("0.0001") @DecimalMax("1") BigDecimal gradeAMin,
      @Schema(nullable = true) @DecimalMin("0.0001") @DecimalMax("1") BigDecimal gradeBMin,
      @Schema(nullable = true) @DecimalMin("0.0001") @DecimalMax("1") BigDecimal gradeCMin,
      @Schema(nullable = true) @Size(max = 2000) String note) {}

  // ================================================================ 评级结果

  /**
   * 评级结果的一行。
   *
   * <p>三个分数并存：{@code objectiveScore}（纯客观）、{@code manualAdjustment}（人工调整量）、
   * {@code finalScore}（最终分 = 两者之和，DB 层 CHECK 钉死）。
   * 五个维度得分与原始指标一律<b>可空</b>：null = 这个周期没有可算的依据，不是 0 分。
   */
  public record SupplierRatingRow(
      UUID id,
      SupplierRatingPeriodType periodType,
      LocalDate periodStart,
      LocalDate periodEnd,
      UUID supplierRefId,
      String supplierCode,
      String supplierName,
      BigDecimal objectiveScore,
      BigDecimal manualAdjustment,
      BigDecimal finalScore,
      SupplierRatingGrade grade,
      @Schema(nullable = true) Integer rankInPeriod,
      BigDecimal availableWeight,
      String unavailableDimensions,
      String unavailableReason,
      @Schema(nullable = true) BigDecimal deliveryScore,
      @Schema(nullable = true) BigDecimal qualityScore,
      @Schema(nullable = true) BigDecimal responseScore,
      @Schema(nullable = true) BigDecimal priceScore,
      @Schema(nullable = true) BigDecimal serviceScore,
      @Schema(nullable = true) BigDecimal deliveryOnTimeRate,
      int deliveryArrivedCount,
      @Schema(nullable = true) BigDecimal qualityPassRate,
      BigDecimal qualityInspectedQuantity,
      BigDecimal qualityQualifiedQuantity,
      @Schema(nullable = true) BigDecimal responseAvgHours,
      int responseSampleCount,
      @Schema(nullable = true) BigDecimal priceIndex,
      int priceSampleCount,
      BigDecimal priceComparableQuantity,
      @Schema(nullable = true) BigDecimal serviceCooperationRate,
      int serviceClaimCount,
      boolean manuallyAdjusted,
      @Schema(nullable = true) UUID manualAdjustedBy,
      @Schema(nullable = true) OffsetDateTime manualAdjustedAt,
      String manualAdjustmentReason,
      OffsetDateTime computedAt,
      long version) {}

  /** 评级列表的分页结果。 */
  public record SupplierRatingPage(
      List<SupplierRatingRow> items, long total, int page, int size, String note) {}

  /** 评级结果筛选条件。 */
  public record SupplierRatingQuery(
      SupplierRatingPeriodType periodType,
      @Schema(nullable = true) LocalDate periodStart,
      @Schema(nullable = true) UUID supplierRefId,
      @Schema(nullable = true) SupplierRatingGrade grade,
      @Schema(nullable = true) String keyword,
      @Schema(nullable = true) String sort,
      @Schema(nullable = true) Boolean desc,
      int page,
      int size) {}

  // ================================================================ 人工调整

  /**
   * 人工加权分的入参。
   *
   * <p>{@code adjustment} 是**调整量**（−50 ~ +50），不是最终分：填最终分的话，
   * 客观分变化后人工分的含义就漂了。理由必填 —— 没有理由的人工调整等于没留痕。
   */
  public record SupplierRatingAdjustmentInput(
      @NotNull @DecimalMin("-50") @DecimalMax("50") BigDecimal adjustment,
      @NotBlank @Size(max = 1000) String reason) {}

  /** 一次人工调整的留痕（append-only）。 */
  public record SupplierRatingAdjustmentRow(
      UUID id,
      UUID resultId,
      UUID supplierRefId,
      SupplierRatingPeriodType periodType,
      LocalDate periodStart,
      LocalDate periodEnd,
      BigDecimal objectiveScore,
      BigDecimal previousScore,
      BigDecimal adjustedScore,
      BigDecimal adjustment,
      String reason,
      UUID adjustedBy,
      OffsetDateTime adjustedAt) {}

  // ================================================================ 变动历史

  /** 评级变动历史的一行（append-only）。 */
  public record SupplierRatingChangeRow(
      UUID id,
      UUID supplierRefId,
      String supplierCode,
      String supplierName,
      SupplierRatingPeriodType periodType,
      LocalDate periodStart,
      LocalDate periodEnd,
      @Schema(nullable = true) SupplierRatingGrade previousGrade,
      @Schema(nullable = true) BigDecimal previousScore,
      SupplierRatingGrade currentGrade,
      BigDecimal currentScore,
      @Schema(nullable = true) BigDecimal scoreDelta,
      boolean gradeChanged,
      SupplierRatingChangeType changeType,
      String reason,
      UUID operatorId,
      OffsetDateTime occurredAt) {}

  /** 变动历史的分页结果。 */
  public record SupplierRatingChangePage(
      List<SupplierRatingChangeRow> items, long total, int page, int size, String note) {}

  // ================================================================ 概览 / 计算 / 优先级

  /** 评级概览（不受筛选与分页影响）。 */
  public record SupplierRatingOverview(
      SupplierRatingPeriodType periodType,
      LocalDate periodStart,
      LocalDate periodEnd,
      long ratedSupplierCount,
      long gradeACount,
      long gradeBCount,
      long gradeCCount,
      long gradeDCount,
      @Schema(nullable = true) BigDecimal averageScore,
      @Schema(nullable = true) BigDecimal averageObjectiveScore,
      long manuallyAdjustedCount,
      long partialDimensionCount,
      String note) {}

  /**
   * 一次评级计算的结果。
   *
   * <p>{@code skippedSuppliers} 是「这个周期内没有可评的供应商」时的事实，不是错误。
   */
  public record SupplierRatingComputeResult(
      SupplierRatingPeriodType periodType,
      LocalDate periodStart,
      LocalDate periodEnd,
      int computedCount,
      int createdCount,
      int updatedCount,
      int changeRecordCount,
      List<String> unavailableDimensions,
      String note) {}

  /**
   * 默认供应商优先级的一行（供 6.2.1.2 补货建议消费）。
   *
   * <p>刻意只给**排序与依据**，不替下游选供应商：优先级是「谁的客观表现更好」，
   * 补货建议还要看 MOQ / 箱规 / 起订额 / 交期，替下游选完就是越界。
   */
  public record SupplierRatingPriorityRow(
      int rank,
      UUID supplierRefId,
      String supplierCode,
      String supplierName,
      BigDecimal finalScore,
      BigDecimal objectiveScore,
      SupplierRatingGrade grade,
      boolean manuallyAdjusted,
      LocalDate periodStart,
      LocalDate periodEnd,
      @Schema(nullable = true) String unavailableDimensions) {}

  /** 默认供应商优先级的响应。 */
  public record SupplierRatingPriorityList(
      SupplierRatingPeriodType periodType,
      LocalDate periodStart,
      LocalDate periodEnd,
      List<SupplierRatingPriorityRow> items,
      String note) {}

  /** 单个评级详情：结果 + 人工调整留痕 + 变动历史。 */
  public record SupplierRatingDetail(
      SupplierRatingRow result,
      List<SupplierRatingAdjustmentRow> adjustments,
      List<SupplierRatingChangeRow> changes,
      String note) {}

  // ================================================================ 小工具

  /** 等级与维度的闭集词表在枚举里（不另造字符串常量）。 */
}
