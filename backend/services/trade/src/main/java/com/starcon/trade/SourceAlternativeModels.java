package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 6.1.2.2 同款货源推荐的模型。
 *
 * <p><b>命名纪律（硬要求）</b>：springdoc 用 record 的<b>简单类名</b>做 {@code components.schemas} 的键，
 * 重名会被静默覆盖 —— 别人的字段形状被改掉、单测全绿、只有跨服务契约比对才红。本文件所有 record /
 * enum 一律带 {@code SourceAlternative} 前缀，不占用任何通用名。
 *
 * <p><b>本文件最重要的两个数</b>：
 *
 * <ul>
 *   <li>{@code confidence} —— **同款置信度**：这条候选**是不是同一个货**。
 *       确证（人工关联过的 SKU）= 1.0000；历史采购过的供应商 = 0.5000~0.7500；
 *       只有标题与规格一致的疑似同款 = 0.3000 / 0.4000。
 *       便宜的错货，置信度一样低 —— 便宜不是同款的证据。</li>
 *   <li>{@code score} —— **推荐排序分**：按租户级权重（价格 / 评级 / 历史 / 交期）合成的 0~1 分。
 *       它回答「先看哪一条」，<b>不</b>回答「是不是同款」。两个数不许混成一个。</li>
 * </ul>
 */
public final class SourceAlternativeModels {

  private SourceAlternativeModels() {}

  /** 公共接口。 */
  public interface Labelled {
    String label();
  }

  /** 推荐理由：缺货还是质量不达标（闭集）。 */
  public enum SourceAlternativeReason implements Labelled {
    STOCKOUT("缺货"),
    QUALITY("质量不达标");

    private final String label;

    SourceAlternativeReason(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /**
   * 判定类型：这条候选凭什么进清单（闭集，强度从高到低）。
   *
   * <p>🔴 刻意**没有** IMAGE_SIMILARITY 之类的取值：本仓没有视觉相似度服务，
   * 凭空加一个取值等于凭空加一个会把采购指向错误商品的结论。
   */
  public enum SourceAlternativeMatchType implements Labelled {
    SKU_LINK("已确认同款（人工关联）"),
    PURCHASE_HISTORY("历史采购过该货的供应商"),
    TITLE_SPEC_MATCH("疑似同款（标题与规格一致）");

    private final String label;

    SourceAlternativeMatchType(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /** 批次状态。 */
  public enum SourceAlternativeRunStatus implements Labelled {
    OPEN("待处置"),
    REQUESTED("已生成采购申请"),
    CLOSED("已关闭");

    private final String label;

    SourceAlternativeRunStatus(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /** 判定类型。 */
  public enum SourceAlternativeDecisionType implements Labelled {
    CONFIRMED("人工确认是同款"),
    REJECTED("否决"),
    REQUESTED("已生成采购申请");

    private final String label;

    SourceAlternativeDecisionType(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  // ================================================================ 能力 / 词表 / 概览

  /** 能力声明里的一条（上下游可用性）。 */
  public record SourceAlternativeCapability(
      String moduleId,
      String name,
      String ownerService,
      boolean available,
      String impact) {}

  /** 能力与上下游可用性。 */
  public record SourceAlternativeCapabilities(
      List<SourceAlternativeCapability> capabilities,
      List<String> matchTypes,
      List<String> notes) {}

  /** 词表选项。 */
  public record SourceAlternativeOption(String value, String label, String hint) {}

  /** 词表。 */
  public record SourceAlternativeOptions(
      List<SourceAlternativeOption> reasons,
      List<SourceAlternativeOption> matchTypes,
      List<SourceAlternativeOption> statuses,
      List<SourceAlternativeOption> decisionTypes,
      List<String> notes) {}

  /** 概览（不受筛选与分页影响）。 */
  public record SourceAlternativeOverview(
      long runCount,
      long openRunCount,
      long candidateCount,
      long confirmedCandidateCount,
      long weakCandidateCount,
      long requestCount,
      OffsetDateTime lastRunAt,
      String note) {}

  // ================================================================ 目标仓与法人

  /** 目标仓下拉项（warehouse 跨服务只读，快照）。 */
  public record SourceAlternativeWarehouseOption(
      UUID id, String code, String name, String status) {}

  /** 法人下拉项（warehouse 跨服务只读，快照）。 */
  public record SourceAlternativeLegalEntityOption(
      UUID id, String code, String name, String status) {}

  /**
   * 目标仓与法人（生成采购申请时必填）。
   *
   * @param available 仓库服务接线了没有。**false 时列表为空是如实的**，
   *     页面据此显示「仓库服务未接线」，而不是显示一个空下拉框 ——
   *     空下拉框会被读成「这个租户一个仓都没有」。
   */
  public record SourceAlternativeTargetOptions(
      List<SourceAlternativeWarehouseOption> warehouses,
      List<SourceAlternativeLegalEntityOption> legalEntities,
      boolean available,
      String unavailableNote) {}

  // ================================================================ 规则与权重

  /**
   * 租户级规则与排序权重。
   *
   * @param configured 库里是否已经有这条策略（false = 用的是内置默认值）
   */
  public record SourceAlternativePolicy(
      boolean configured,
      BigDecimal weakMatchMinConfidence,
      BigDecimal confirmedMatchMinConfidence,
      boolean requireManualConfirmation,
      int maxCandidates,
      BigDecimal priceWeight,
      BigDecimal ratingWeight,
      BigDecimal historyWeight,
      BigDecimal leadTimeWeight,
      long version,
      OffsetDateTime updatedAt) {}

  /** 修改规则与权重。权重和必须精确等于 1（服务层 + DB CHECK 两道闸）。 */
  public record SourceAlternativePolicyInput(
      @NotNull @DecimalMin(value = "0", inclusive = true) @DecimalMax(value = "1", inclusive = true)
          BigDecimal weakMatchMinConfidence,
      @NotNull @DecimalMin(value = "0", inclusive = true) @DecimalMax(value = "1", inclusive = true)
          BigDecimal confirmedMatchMinConfidence,
      @NotNull Boolean requireManualConfirmation,
      @NotNull @Min(1) @Max(500) Integer maxCandidates,
      @NotNull @DecimalMin(value = "0", inclusive = true) @DecimalMax(value = "1", inclusive = true)
          BigDecimal priceWeight,
      @NotNull @DecimalMin(value = "0", inclusive = true) @DecimalMax(value = "1", inclusive = true)
          BigDecimal ratingWeight,
      @NotNull @DecimalMin(value = "0", inclusive = true) @DecimalMax(value = "1", inclusive = true)
          BigDecimal historyWeight,
      @NotNull @DecimalMin(value = "0", inclusive = true) @DecimalMax(value = "1", inclusive = true)
          BigDecimal leadTimeWeight) {}

  // ================================================================ 推荐批次

  /**
   * 一条候选货源。
   *
   * @param confidence 同款置信度 0~1（**只衡量是不是同款**）
   * @param score 推荐排序分 0~1（按租户级权重合成，**不是置信度**）
   */
  public record SourceAlternativeCandidateRow(
      UUID id,
      UUID runId,
      UUID itemId,
      UUID stallId,
      String stallName,
      String platform,
      String platformItemId,
      UUID supplierId,
      String supplierCode,
      String supplierName,
      String title,
      String spec,
      String imageUrl,
      BigDecimal priceMin,
      BigDecimal priceMax,
      String currency,
      BigDecimal moq,
      OffsetDateTime collectedAt,
      boolean onSale,
      String matchType,
      String matchTypeLabel,
      BigDecimal confidence,
      String matchBasis,
      boolean requiresManualConfirmation,
      int historyOrderCount,
      BigDecimal historyQuantity,
      BigDecimal leadTimeDays,
      BigDecimal ratingObjectiveScore,
      BigDecimal ratingFinalScore,
      String ratingGrade,
      Boolean ratingManuallyAdjusted,
      LocalDate ratingPeriodStart,
      LocalDate ratingPeriodEnd,
      Integer priceRank,
      boolean lowestPrice,
      BigDecimal score,
      int sortRank,
      String decision,
      String decisionLabel,
      String purchaseRequestNo) {}

  /** 判定事件。 */
  public record SourceAlternativeDecisionRow(
      UUID id,
      UUID candidateId,
      String decisionType,
      String decisionTypeLabel,
      String reason,
      UUID purchaseRequestId,
      String purchaseRequestNo,
      UUID decidedBy,
      OffsetDateTime decidedAt) {}

  /**
   * 一次推荐的批次头。
   *
   * @param referenceImageUrl 目标 SKU 的参考图（**只给人看**，系统不拿它做相似度判定）
   */
  public record SourceAlternativeRunRow(
      UUID id,
      String runNo,
      UUID skuId,
      String skuCode,
      String skuName,
      String reason,
      String reasonLabel,
      BigDecimal requiredQuantity,
      String referenceImageUrl,
      String status,
      String statusLabel,
      int candidateCount,
      int confirmedCount,
      UUID targetWarehouseId,
      String targetWarehouseCode,
      String targetWarehouseName,
      UUID legalEntityId,
      String legalEntityCode,
      String legalEntityName,
      String note,
      UUID createdBy,
      OffsetDateTime createdAt) {}

  /** 推荐批次列表（真分页）。 */
  public record SourceAlternativeRunPage(
      List<SourceAlternativeRunRow> items, long total, int page, int size) {}

  /** 批次详情：批次头 + 候选 + 判定留痕 + 价格对比。 */
  public record SourceAlternativeRunDetail(
      SourceAlternativeRunRow run,
      List<SourceAlternativeCandidateRow> candidates,
      List<SourceAlternativeDecisionRow> decisions,
      List<SourceAlternativePriceComparison> priceComparison,
      String note) {}

  /**
   * 一个币种下的价格对比。
   *
   * @param lowestPrice 该币种下的最低价下限（区间里的下限，不是「精确价」）
   */
  public record SourceAlternativePriceComparison(
      String currency, BigDecimal lowestPrice, BigDecimal highestPrice, int candidateCount) {}

  // ================================================================ 入参

  /** 建一次推荐。 */
  public record SourceAlternativeCreateRunInput(
      @NotNull UUID skuId,
      @NotBlank @Pattern(regexp = "STOCKOUT|QUALITY") String reason,
      @Schema(nullable = true)
          @DecimalMin(value = "0", inclusive = false)
          BigDecimal requiredQuantity,
      @Schema(nullable = true) @Size(max = 1000) String note) {}

  /** 人工判定：确认或否决。 */
  public record SourceAlternativeDecisionInput(
      @NotBlank @Pattern(regexp = "CONFIRMED|REJECTED") String decisionType,
      @Schema(nullable = true) @Size(max = 1000) String reason) {}

  /**
   * 一键生成采购申请。
   *
   * @param quantity 申请数量。**必须 ≥ 该货源的起订量**（起订量是真实约束，
   *     低于它的采购单到外部平台会被拒）
   */
  public record SourceAlternativePurchaseRequestInput(
      @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
      @NotNull UUID targetWarehouseId,
      @NotNull UUID legalEntityId,
      @Schema(nullable = true) @Size(max = 1000) String note) {}

  /** 一键生成采购申请的结果。 */
  public record SourceAlternativePurchaseRequestResult(
      UUID purchaseRequestId,
      String purchaseRequestNo,
      String purchaseRequestStatus,
      UUID candidateId,
      String runNo,
      String note) {}
}
