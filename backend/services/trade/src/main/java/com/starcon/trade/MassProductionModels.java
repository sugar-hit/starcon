package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 6.6.2.2 转量产与建档的传输模型。
 *
 * <p>蓝图原话：「试产通过后转正式量产：确定量产 BOM、批量采购/生产计划、目标成本、
 * 正式 SKU 建档（含编码/条码/属性/报关属性），并把量产参数带入采购与价格模型。」
 * 输入「试产结果；量产决策；BOM 定版」。输出「量产计划；正式 SKU（3.1.2.1）；量产 BOM 版本；采购需求（6.2.1.2）」。
 *
 * <p><b>命名纪律（硬要求）</b>：springdoc 用 record 的<b>简单类名</b>做 {@code components.schemas} 的键，
 * 重名会被静默覆盖 —— 别人的字段形状被改掉、单测全绿、只有跨服务契约比对才红。本文件所有 record /
 * enum 一律以 {@code MassProduction} 开头。
 *
 * <p><b>十条建模纪律</b>：
 *
 * <ul>
 *   <li><b>金额全程 BigDecimal</b>，DB 侧 numeric(19,4)；数量 numeric(19,6)；<b>禁浮点</b>。</li>
 *   <li><b>一个量产计划一个币种</b>：跨币种<b>绝不相加</b>（5.2.2.1 汇率查询未交付 ⇒ 不做固定汇率折算）。</li>
 *   <li><b>「取不到」与「确实是 0」不折叠</b>：目标成本取不到时是 {@code null}（<b>不是 0</b>），
 *       此时成本对比标 {@code comparable=false} 并说明原因。</li>
 *   <li><b>正式 SKU 不在本服务建</b>：写入所有权在 catalog（3.1.2.1）——
 *       本服务只落<b>建档请求事实</b>（目标系统 + 目标键 + 载荷 + 幂等键 + 状态机），
 *       SKU id 是<b>从 catalog 读回来的</b>，不是本服务编的。</li>
 *   <li><b>建档请求的「结果未知」是一种独立状态</b>（{@code UNKNOWN}）——
 *       catalog 的建档端点不接受幂等键，盲目重试可能建出重复 SKU。</li>
 *   <li><b>采购需求只做「重算触发」</b>：6.2.1.2 的起订额与交期是人工维护的策略，
 *       本模块<b>不顶掉</b>它 ⇒ {@code massProductionInputApplied=false} 如实标出。</li>
 *   <li><b>价格模型不 mock</b>：5.3.1.1 未交付 ⇒ 如实标 {@code available=false} + 缺口形态。</li>
 *   <li><b>时间一律 timestamptz</b>：用 {@link OffsetDateTime} 传输，不传本地时刻。</li>
 *   <li><b>状态是字符串 + 中文名成对返回</b>（{@code status} / {@code statusLabel}），
 *       前端不必自己再写一遍映射。</li>
 *   <li><b>状态只给后端说的合法去向</b>：下拉里的目标状态来自服务层规则，页面不自己维护状态机。</li>
 * </ul>
 */
public final class MassProductionModels {

  private MassProductionModels() {}

  /** 中文名。服务层与前端都拿得到 label，不必各写一遍 switch —— 各写一遍迟早漂移。 */
  public interface Labelled {
    String label();
  }

  // ================================================================ 枚举

  /**
   * 量产计划状态 —— 一次量产决策的生命周期。
   *
   * <p>🔴 状态机在服务层（{@code MassProductionRules.allowedTargets}）与
   * V268 迁移的守卫触发器（数据库层）<b>两处</b>都钉死。
   * {@link #CLOSED} / {@link #CANCELLED} 是<b>终态</b>：没有出边，不提供「恢复」。
   */
  public enum MassProductionPlanStatus implements Labelled {
    DRAFT("已建计划"),
    DECIDED("已决策"),
    BOM_FROZEN("BOM已定版"),
    SKU_FILED("SKU已建档"),
    RELEASED("已下达"),
    CLOSED("已关闭"),
    CANCELLED("已取消");

    private final String label;

    MassProductionPlanStatus(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /** 量产决策结论。 */
  public enum MassProductionDecision implements Labelled {
    GO("转量产"),
    HOLD("暂缓量产");

    private final String label;

    MassProductionDecision(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /**
   * 量产决策的来源。
   *
   * <p>🔴 {@link #MANUAL_OVERRIDE} 不是「随便覆盖」：上游转量产建议是 {@code NO_GO} 而本计划决策是
   * {@code GO} 时，<b>必须</b>写覆盖理由，否则就是「没证据地放行一次量产」。
   */
  public enum MassProductionDecisionSource implements Labelled {
    TRIAL_RECOMMENDATION("跟随上游转量产建议"),
    MANUAL_OVERRIDE("人工覆盖上游建议");

    private final String label;

    MassProductionDecisionSource(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /**
   * SKU 建档请求状态。
   *
   * <p>🔴 {@link #UNKNOWN}（结果未知）是<b>必须单独存在</b>的一种状态：
   * catalog 的建档端点不接受幂等键，超时 / 断连时无法判断它到底建没建 ——
   * 此时盲目重试可能建出<b>两个</b> SKU，所以必须先人工核对。
   */
  public enum MassProductionFilingStatus implements Labelled {
    PENDING("待提交"),
    SUBMITTED("已提交"),
    CONFIRMED("已建成"),
    PARTIAL("部分完成"),
    FAILED("失败"),
    UNKNOWN("结果未知");

    private final String label;

    MassProductionFilingStatus(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /** 建档属性行的覆写状态（与 catalog 的三种取值逐字对齐）。 */
  public enum MassProductionAttributeState implements Labelled {
    SET("设置"),
    CLEAR("清空"),
    INHERIT("继承");

    private final String label;

    MassProductionAttributeState(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>存在的理由：<b>正式 SKU 建档</b>（catalog 的 {@code POST /catalog/products/{id}/skus}）有入口 ⇒ 可用；
   * <b>采购需求</b>（6.2.1.2，同库）只能<b>重算触发</b> ⇒ 可用但参数进不去；
   * <b>价格模型</b>（5.3.1.1，未开发）与<b>生产计划下达</b>（8.4.1 组装单，warehouse 独立库）<b>做不到</b> ——
   * 哪一个接通了必须由接口自己报出。
   */
  public record MassProductionCapabilities(
      boolean bomVersionAvailable,
      String bomVersionOwner,
      String bomVersionNote,
      boolean skuFilingAvailable,
      String skuFilingOwner,
      String skuFilingNote,
      boolean purchaseDemandAvailable,
      String purchaseDemandOwner,
      String purchaseDemandNote,
      boolean pricingAvailable,
      String pricingOwner,
      String pricingNote,
      boolean assemblyOrderAvailable,
      String assemblyOrderOwner,
      String assemblyOrderNote,
      String boundary) {}

  // ================================================================ 下拉项

  public record MassProductionStatusOption(
      String status, String label, boolean terminal, List<String> allowedTargets) {}

  public record MassProductionDecisionOption(String decision, String label) {}

  public record MassProductionDecisionSourceOption(String source, String label) {}

  public record MassProductionFilingStatusOption(String status, String label) {}

  public record MassProductionAttributeStateOption(String state, String label) {}

  /** 全部下拉项。 */
  public record MassProductionOptions(
      List<MassProductionStatusOption> statuses,
      List<MassProductionDecisionOption> decisions,
      List<MassProductionDecisionSourceOption> decisionSources,
      List<MassProductionFilingStatusOption> filingStatuses,
      List<MassProductionAttributeStateOption> attributeStates) {}

  // ================================================================ 概览

  public record MassProductionStatusCount(String status, String label, long count) {}

  /** 目标成本按币种分组（🔴 跨币种绝不相加）。 */
  public record MassProductionTargetCostCurrencyTotal(
      String currency, BigDecimal totalTargetCost, long count) {}

  public record MassProductionSummary(
      long totalPlans,
      long decidedPlans,
      long frozenBomPlans,
      long filedSkuPlans,
      long releasedPlans,
      long pendingFilings,
      long failedFilings,
      List<MassProductionStatusCount> statuses,
      List<MassProductionTargetCostCurrencyTotal> targetCostByCurrency,
      List<MassProductionPlanRow> recent) {}

  // ================================================================ 量产计划

  public record MassProductionPlanRow(
      UUID id,
      String planNo,
      UUID trialOrderId,
      String trialNo,
      String trialRecommendationDecision,
      UUID bomVersionId,
      String bomVersionNo,
      String bomCode,
      String outputSkuCode,
      String outputSkuName,
      BigDecimal planQuantity,
      @Schema(nullable = true) BigDecimal targetCost,
      String currency,
      String productionLineCode,
      String productionLineName,
      String factoryCode,
      String factoryName,
      String status,
      String statusLabel,
      String decision,
      @Schema(nullable = true) String decisionLabel,
      String decisionSource,
      @Schema(nullable = true) String decisionSourceLabel,
      String decisionRationale,
      String overrideReason,
      @Schema(nullable = true) UUID decidedBy,
      @Schema(nullable = true) OffsetDateTime decidedAt,
      int bomLineCount,
      String filingStatus,
      @Schema(nullable = true) String filingStatusLabel,
      @Schema(nullable = true) UUID filedSkuId,
      @Schema(nullable = true) String filedSkuCode,
      @Schema(nullable = true) OffsetDateTime plannedStartAt,
      @Schema(nullable = true) OffsetDateTime plannedEndAt,
      String note,
      UUID createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  public record MassProductionPlanPage(
      List<MassProductionPlanRow> rows, int page, int size, long total, int totalPages) {}

  /** 量产 BOM 组件行快照（定版那一刻从 catalog 原样搬过来的）。 */
  public record MassProductionBomLineRow(
      UUID id,
      UUID planId,
      int lineNo,
      UUID skuId,
      String skuCode,
      String skuName,
      BigDecimal unitQuantity,
      BigDecimal scrapRate,
      BigDecimal requiredQuantity,
      String unit,
      OffsetDateTime createdAt) {}

  /** SKU 建档请求行。 */
  public record MassProductionSkuFilingRow(
      UUID id,
      String filingNo,
      UUID planId,
      String targetSystem,
      String targetEndpoint,
      UUID targetSpuId,
      String targetSpuCode,
      String targetSpuName,
      String skuCode,
      String skuName,
      String barcode,
      String baseUnit,
      String skuStatus,
      String idempotencyKey,
      String status,
      String statusLabel,
      @Schema(nullable = true) UUID targetSkuId,
      String targetSkuCode,
      String customsHsCode,
      String customsOriginCountryCode,
      String customsDeclaredName,
      @Schema(nullable = true) BigDecimal customsDeclaredValueReference,
      String customsStatutoryUnitCode,
      boolean customsPreferentialOriginEligible,
      String customsPreferenceAgreement,
      String customsNote,
      String errorMessage,
      @Schema(nullable = true) OffsetDateTime submittedAt,
      @Schema(nullable = true) OffsetDateTime confirmedAt,
      List<MassProductionSkuFilingAttributeRow> attributes,
      List<MassProductionCustomsElementRow> customsElements,
      UUID createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  public record MassProductionSkuFilingAttributeRow(
      UUID id,
      UUID filingId,
      int lineNo,
      UUID attributeId,
      String attributeCode,
      String attributeName,
      String valueState,
      String valueStateLabel,
      List<String> values) {}

  public record MassProductionCustomsElementRow(
      UUID id,
      UUID filingId,
      int ordinal,
      String elementCode,
      String elementName,
      String elementValue,
      boolean required) {}

  /** 下游交接留痕。 */
  public record MassProductionHandoffRow(
      UUID id,
      UUID planId,
      String targetModule,
      String targetEndpoint,
      String ownerModule,
      boolean available,
      boolean massProductionInputApplied,
      String status,
      String resultRef,
      String resultSummary,
      String errorMessage,
      String impact,
      OffsetDateTime attemptedAt) {}

  /** 计划详情：计划 + BOM 快照 + 建档请求 + 交接留痕 + 迁移历史。 */
  public record MassProductionPlanDetail(
      MassProductionPlanRow plan,
      List<MassProductionBomLineRow> bomLines,
      @Schema(nullable = true) MassProductionSkuFilingRow skuFiling,
      List<MassProductionHandoffRow> handoffs,
      List<MassProductionPlanTransitionRow> transitions,
      List<String> allowedTargets,
      MassProductionCostComparisonRow costComparison) {}

  public record MassProductionPlanTransitionRow(
      UUID id,
      UUID planId,
      String fromStatus,
      String fromStatusLabel,
      String toStatus,
      String toStatusLabel,
      String reason,
      UUID actorId,
      OffsetDateTime occurredAt) {}

  /**
   * 量产目标成本与试产实测成本的对比。
   *
   * <p>🔴 {@code comparable=false} 时，{@code targetCost} / {@code varianceAmount} / {@code varianceRate}
   * 都是 {@code null}（<b>不是 0</b>），并由 {@code reason} 说明为什么不可比。
   */
  public record MassProductionCostComparisonRow(
      String currency,
      @Schema(nullable = true) BigDecimal trialCost,
      @Schema(nullable = true) BigDecimal targetCost,
      @Schema(nullable = true) BigDecimal varianceAmount,
      @Schema(nullable = true) BigDecimal varianceRate,
      boolean comparable,
      String reason) {}

  // ================================================================ 上游候选

  /** 可转量产的试产单候选（来自 6.6.2.1，同库只读）。 */
  public record MassProductionTrialCandidateRow(
      UUID trialOrderId,
      String trialNo,
      String trialStatus,
      String trialStatusLabel,
      String recommendationDecision,
      String recommendationDecisionLabel,
      @Schema(nullable = true) BigDecimal trialCost,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) BigDecimal massTargetCost,
      UUID bomVersionId,
      String bomVersionNo,
      String bomCode,
      String outputSkuCode,
      String outputSkuName) {}

  /** catalog 的 SPU 候选（SKU 建档的「目标键」）。 */
  public record MassProductionCatalogSpuRow(
      UUID id, String code, String name, String brand, String category, String status) {}

  /** catalog 的商品属性候选（建档载荷里「属性」那一段的取值范围）。 */
  public record MassProductionCatalogAttributeRow(
      UUID id,
      String code,
      String name,
      String dataType,
      String valueMode,
      boolean multiple,
      boolean required,
      boolean variantAxis,
      String unit,
      String groupName,
      List<String> options,
      List<String> defaultValues) {}

  // ================================================================ 入参

  /** 建量产计划。 */
  public record MassProductionCreateInput(
      @NotBlank @Size(max = 64) String planNo,
      @NotNull UUID trialOrderId,
      @NotNull UUID bomVersionId,
      @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal planQuantity,
      @Schema(nullable = true) @DecimalMin(value = "0", inclusive = false) BigDecimal targetCost,
      @NotBlank String currency,
      @Size(max = 64) String productionLineCode,
      @Size(max = 200) String productionLineName,
      @Size(max = 64) String factoryCode,
      @Size(max = 200) String factoryName,
      @Schema(nullable = true) OffsetDateTime plannedStartAt,
      @Schema(nullable = true) OffsetDateTime plannedEndAt,
      @Size(max = 2000) String note) {}

  /** 改量产计划（🔴 试产单与 BOM 版本不可改：改了它们，已定版的快照就全对不上了）。 */
  public record MassProductionUpdateInput(
      @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal planQuantity,
      @Schema(nullable = true) @DecimalMin(value = "0", inclusive = false) BigDecimal targetCost,
      @NotBlank String currency,
      @Size(max = 64) String productionLineCode,
      @Size(max = 200) String productionLineName,
      @Size(max = 64) String factoryCode,
      @Size(max = 200) String factoryName,
      @Schema(nullable = true) OffsetDateTime plannedStartAt,
      @Schema(nullable = true) OffsetDateTime plannedEndAt,
      @Size(max = 2000) String note) {}

  /**
   * 推进量产计划状态。
   *
   * @param targetStatus 目标状态（只能沿状态机上存在的边走）
   * @param decision 决策结论（迁到「已决策」时必填）
   * @param decisionSource 决策来源（迁到「已决策」时必填）
   * @param decisionRationale 决策理由（迁到「已决策」时必填）
   * @param overrideReason 覆盖上游建议的理由（上游建议是 NO_GO 而本计划决策是 GO 时必填）
   * @param reason 状态迁移理由
   */
  public record MassProductionTransitionInput(
      @NotBlank String targetStatus,
      @Schema(nullable = true) String decision,
      @Schema(nullable = true) String decisionSource,
      @Schema(nullable = true) @Size(max = 2000) String decisionRationale,
      @Schema(nullable = true) @Size(max = 2000) String overrideReason,
      @Schema(nullable = true) @Size(max = 2000) String reason) {}

  /** 建 SKU 建档请求。 */
  public record MassProductionSkuFilingInput(
      @NotNull UUID targetSpuId,
      @NotBlank @Size(max = 64) String skuCode,
      @NotBlank @Size(max = 200) String skuName,
      @NotBlank @Size(max = 128) String barcode,
      @NotBlank @Size(max = 32) String baseUnit,
      @NotBlank String skuStatus,
      @Size(max = 16) String customsHsCode,
      @Size(max = 2) String customsOriginCountryCode,
      @Size(max = 200) String customsDeclaredName,
      @Schema(nullable = true) @DecimalMin(value = "0") BigDecimal customsDeclaredValueReference,
      @Size(max = 8) String customsStatutoryUnitCode,
      boolean customsPreferentialOriginEligible,
      @Size(max = 32) String customsPreferenceAgreement,
      @Size(max = 1000) String customsNote,
      @NotNull @Size(max = 200) List<@NotNull @Valid MassProductionSkuFilingAttributeInput> attributes,
      @NotNull @Size(max = 200) List<@NotNull @Valid MassProductionCustomsElementInput> customsElements) {}

  /** 建档载荷里的一条属性行。 */
  public record MassProductionSkuFilingAttributeInput(
      @NotNull UUID attributeId,
      @Size(max = 64) String attributeCode,
      @Size(max = 200) String attributeName,
      @NotBlank String valueState,
      @NotNull @Size(max = 50) List<@NotNull @Size(max = 2000) String> values) {}

  /** 建档载荷里的一条报关申报要素。 */
  public record MassProductionCustomsElementInput(
      @NotNull @jakarta.validation.constraints.Positive Integer ordinal,
      @NotBlank @Size(max = 32) String elementCode,
      @NotBlank @Size(max = 200) String elementName,
      @Size(max = 2000) String elementValue,
      boolean required) {}
}
