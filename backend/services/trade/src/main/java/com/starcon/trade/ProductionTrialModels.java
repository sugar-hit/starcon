package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 6.6.2.1 试产单的传输模型。
 *
 * <p>蓝图原话：「管理试产：小批量试产（数量、BOM 版本、组件需求、产线/加工厂、质检标准、试产成本与良率），
 * 试产结果记录并对比量产目标，输出转量产建议。」输入「试产申请（BOM 版本、目标数量）；质检方案；成本数据」。
 * 输出「试产单 + 组件领用需求（→ 7 库存）；良率与成本结果；转量产建议」。
 *
 * <p><b>命名纪律（硬要求）</b>：springdoc 用 record 的<b>简单类名</b>做 {@code components.schemas} 的键，
 * 重名会被静默覆盖 —— 别人的字段形状被改掉、单测全绿、只有跨服务契约比对才红。本文件所有 record /
 * enum 一律以 {@code ProductionTrial} 开头。
 *
 * <p><b>九条建模纪律</b>：
 *
 * <ul>
 *   <li><b>良率是比例，不是百分数</b>：{@code yieldRate} 取值 0..1（0.95 = 95%），
 *       DB 侧 {@code numeric(19,6)}，**由数据库触发器按 合格 ÷ 产出 推导** ——
 *       客户端报上来的良率一律被覆盖。「良率是算出来的，不是报出来的」。</li>
 *   <li><b>金额全程 BigDecimal</b>，DB 侧 numeric(19,4)；数量 numeric(19,6)；**禁浮点**。</li>
 *   <li><b>一张试产单一个币种</b>：不同币种的数绝不相加（DB 层有触发器钉死）。
 *       「试产成本 vs 量产目标成本」<b>只在同一币种内可比</b>，跨币种按币种分组，
 *       <b>不做固定汇率折算</b>。</li>
 *   <li><b>「取不到」与「确实是 0」不折叠</b>：量产目标成本取不到时是 {@code null}
 *       （**不是 0**），此时成本对比标 {@code comparable=false} 并说明原因。</li>
 *   <li><b>组件领用需求是「需求」，不是库存</b>：{@code requiredQuantity} 是需求量，
 *       <b>没有任何库存数量被增减</b>。真实领用由 warehouse 侧 8.4.1 组装单承接。</li>
 *   <li><b>转量产建议是「建议事实」</b>：建议结论 + 建议文本 + <b>生成条件</b> +
 *       生成时的实测快照。6.6.2.2 未开发 ⇒ <b>本模块不建 SKU</b>。</li>
 *   <li><b>时间一律 timestamptz</b>：用 {@link OffsetDateTime} 传输，不传本地时刻。</li>
 *   <li><b>状态是字符串 + 中文名成对返回</b>（{@code status} / {@code statusLabel}），
 *       前端不必自己再写一遍映射 —— 各写一遍迟早漂移。</li>
 *   <li><b>状态只给后端说的合法去向</b>：下拉里的目标状态来自 {@code allowedTargets}，
 *       页面不自己维护一份状态机。</li>
 * </ul>
 */
public final class ProductionTrialModels {

  private ProductionTrialModels() {}

  /** 中文名。服务层与前端都拿得到 label，不必各写一遍 switch —— 各写一遍迟早漂移。 */
  public interface Labelled {
    String label();
  }

  // ================================================================ 枚举

  /**
   * 试产单状态 —— 一次试产活动的生命周期。
   *
   * <p>🔴 状态机在服务层（{@code ProductionTrialRules.allowedTargets}）与
   * V264 迁移的守卫触发器（数据库层）<b>两处</b>都钉死。
   * {@link #CLOSED} / {@link #CANCELLED} 是<b>终态</b>：没有出边，不提供「恢复」。
   */
  public enum ProductionTrialStatus implements Labelled {
    REQUESTED("已申请"),
    SCHEDULED("已排产"),
    IN_PROGRESS("试产中"),
    COMPLETED("已完成"),
    CLOSED("已关闭"),
    CANCELLED("已取消");

    private final String label;

    ProductionTrialStatus(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /**
   * 组件领用需求状态。
   *
   * <p>🔴 {@link #DRAFT} → {@link #SUBMITTED} → {@link #RELEASED} 的含义是
   * 「本模块把需求交出去了」，<b>不是</b>「库存被扣了」——
   * 真实领用由 warehouse 侧 8.4.1 组装单承接。
   */
  public enum ProductionTrialRequirementStatus implements Labelled {
    DRAFT("草稿"),
    SUBMITTED("已提交领用需求"),
    RELEASED("已由加工单承接"),
    CANCELLED("已取消");

    private final String label;

    ProductionTrialRequirementStatus(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /** 质检标准项的判定结果。 */
  public enum ProductionTrialJudgment implements Labelled {
    PENDING("待判定"),
    PASS("合格"),
    FAIL("不合格");

    private final String label;

    ProductionTrialJudgment(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /**
   * 试产成本类型（封闭五值，<b>无「其它」</b>）。
   *
   * <p>🔴 「其它」桶会让「这个钱花在哪」永远答不出来 —— 费用归集最怕的就是「其它」。
   */
  public enum ProductionTrialCostType implements Labelled {
    MATERIAL("物料费"),
    PROCESSING("加工费"),
    MOLD("模具费"),
    INSPECTION("检品费"),
    OVERHEAD("制造费用");

    private final String label;

    ProductionTrialCostType(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /**
   * 转量产建议结论。
   *
   * <p>🔴 只有两个值：<b>建议</b>转量产 / <b>暂不建议</b>转量产。
   * 「建议」本身是建议 —— 真正去建 SKU 是 6.6.2.2 的事（未开发）。
   */
  public enum ProductionTrialMassProductionDecision implements Labelled {
    GO("建议转量产"),
    NO_GO("暂不建议转量产");

    private final String label;

    ProductionTrialMassProductionDecision(String label) {
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
   * <p>存在的理由：BOM 版本（3.5.2.1）在 catalog <b>已交付</b> ⇒ 可用；
   * 打样单（6.6.1.2）在同一个 trade 库 <b>已交付</b> ⇒ 可用；
   * 而<b>真实库存扣减</b>（8.4.1 组装单）、<b>转量产建档</b>（6.6.2.2）、
   * <b>汇率</b>（5.2.2.1）三件事<b>做不到</b> —— 哪一个接通了必须由接口自己报出。
   */
  public record ProductionTrialCapabilities(
      boolean bomVersionAvailable,
      String bomVersionSource,
      String bomVersionNote,
      boolean componentRequirementAvailable,
      String componentRequirementSource,
      String componentRequirementNote,
      boolean inventoryDeductionAvailable,
      String inventoryDeductionOwnerModule,
      String inventoryDeductionNote,
      boolean assemblyOrderHandoffAvailable,
      String assemblyOrderHandoffOwnerModule,
      String assemblyOrderHandoffNote,
      boolean massProductionHandoffAvailable,
      String massProductionHandoffOwnerModule,
      String massProductionHandoffNote,
      boolean sampleOrderReferenceAvailable,
      String sampleOrderReferenceSource,
      String sampleOrderReferenceNote,
      boolean exchangeRateAvailable,
      String exchangeRateOwnerModule,
      String exchangeRateNote,
      String boundaryNote) {}

  // ================================================================ 下拉项

  /** 建单参照项。 */
  public record ProductionTrialOptions(
      List<ProductionTrialStatusOption> statuses,
      List<ProductionTrialRequirementStatusOption> requirementStatuses,
      List<ProductionTrialJudgmentOption> judgments,
      List<ProductionTrialCostTypeOption> costTypes,
      List<ProductionTrialMassProductionDecisionOption> decisions) {}

  /** 一个试产单状态选项。 */
  public record ProductionTrialStatusOption(
      String value, String label, boolean terminal, List<String> allowedTargets) {}

  /** 一个组件领用需求状态选项。 */
  public record ProductionTrialRequirementStatusOption(String value, String label) {}

  /** 一个质检判定选项。 */
  public record ProductionTrialJudgmentOption(String value, String label) {}

  /** 一个成本类型选项。 */
  public record ProductionTrialCostTypeOption(String value, String label) {}

  /** 一个转量产建议结论选项。 */
  public record ProductionTrialMassProductionDecisionOption(String value, String label) {}

  // ================================================================ 列表

  /** 试产单清单的一行（一次试产活动）。 */
  public record ProductionTrialOrderRow(
      UUID id,
      String trialNo,
      UUID bomVersionId,
      String bomVersionNo,
      String bomCode,
      String outputSkuCode,
      String outputSkuName,
      @Schema(nullable = true) UUID developmentSampleOrderId,
      String developmentSampleOrderNo,
      BigDecimal targetQuantity,
      String productionLineCode,
      String productionLineName,
      String factoryCode,
      String factoryName,
      String qualityStandardCode,
      String qualityStandardNote,
      String status,
      String statusLabel,
      String currency,
      UUID requesterId,
      String requesterName,
      OffsetDateTime requestedAt,
      @Schema(nullable = true) OffsetDateTime plannedStartAt,
      @Schema(nullable = true) OffsetDateTime plannedEndAt,
      int requirementCount,
      int pendingRequirementCount,
      int inspectionItemCount,
      int pendingInspectionItemCount,
      boolean hasResult,
      @Schema(nullable = true) BigDecimal yieldRate,
      @Schema(nullable = true) BigDecimal trialCostTotal,
      @Schema(nullable = true) BigDecimal massTargetCost,
      boolean hasRecommendation,
      @Schema(nullable = true) String recommendationDecision,
      @Schema(nullable = true) String recommendationDecisionLabel,
      String purposeNote,
      String note,
      UUID createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 试产单清单页（真分页）。 */
  public record ProductionTrialOrderPage(
      List<ProductionTrialOrderRow> rows, long total, int page, int size) {}

  /** 按状态分组的概览。 */
  public record ProductionTrialSummary(
      long totalTrials,
      long totalRequirements,
      long pendingRequirements,
      long totalInspectionItems,
      long pendingInspectionItems,
      long completedTrials,
      long recommendationCount,
      List<ProductionTrialStatusCount> statuses,
      List<ProductionTrialCostCurrencyTotal> costByCurrency,
      List<ProductionTrialCostComparisonRow> costComparison,
      List<ProductionTrialOrderRow> recentTrials) {}

  /** 一个试产单状态的计数。 */
  public record ProductionTrialStatusCount(String status, String label, long count) {}

  /**
   * 按币种的试产成本合计。
   *
   * <p>🔴 **不同币种的数绝不相加**（汇率查询 5.2.2.1 未交付）⇒ 概览给的是<b>分组</b>而不是一个总数。
   */
  public record ProductionTrialCostCurrencyTotal(
      String currency, BigDecimal trialCostTotal, long trialCount) {}

  /**
   * 「试产成本 vs 量产目标成本」的对比行（**按币种分组**）。
   *
   * <p>🔴 {@code comparable=false} 表示<b>这个币种拿不到量产目标成本</b>，
   * 此时 {@code massTargetCost} 为 {@code null}（<b>不是 0</b>）——
   * 跨币种不做固定汇率折算，宁可说「不可比」。
   */
  public record ProductionTrialCostComparisonRow(
      String currency,
      @Schema(nullable = true) BigDecimal trialCostTotal,
      @Schema(nullable = true) BigDecimal massTargetCost,
      @Schema(nullable = true) BigDecimal varianceAmount,
      @Schema(nullable = true) BigDecimal varianceRate,
      boolean comparable,
      String note) {}

  // ================================================================ 详情

  /** 试产单详情：试产单 + 组件领用需求 + 质检标准 + 结果 + 成本 + 转量产建议 + 迁移历史。 */
  public record ProductionTrialOrderDetail(
      ProductionTrialOrderRow order,
      List<ProductionTrialComponentRequirementRow> componentRequirements,
      List<ProductionTrialInspectionItemRow> inspectionItems,
      @Schema(nullable = true) ProductionTrialResultRow result,
      @Schema(nullable = true) ProductionTrialCostView cost,
      @Schema(nullable = true) ProductionTrialMassProductionRecommendationRow recommendation,
      List<ProductionTrialTransitionRow> transitions,
      List<String> allowedTargets) {}

  /** 组件领用需求的一行（🔴 需求，不是库存）。 */
  public record ProductionTrialComponentRequirementRow(
      UUID id,
      UUID trialOrderId,
      int lineNo,
      UUID skuId,
      String skuCode,
      String skuName,
      String specSnapshot,
      BigDecimal unitQuantity,
      BigDecimal scrapRate,
      BigDecimal requiredQuantity,
      String unit,
      String requirementStatus,
      String requirementStatusLabel,
      @Schema(nullable = true) UUID assemblyOrderRefId,
      String assemblyOrderNo,
      boolean inventoryDeducted,
      String inventoryOwnerModule,
      String note,
      UUID createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 质检标准项的一行（含判定结果）。 */
  public record ProductionTrialInspectionItemRow(
      UUID id,
      UUID trialOrderId,
      String itemCode,
      String itemName,
      String standardValue,
      String inspectionMethod,
      String unit,
      boolean criticalItem,
      int sortOrder,
      String judgment,
      String judgmentLabel,
      String measuredValue,
      @Schema(nullable = true) OffsetDateTime judgedAt,
      @Schema(nullable = true) UUID judgedBy,
      String note,
      UUID createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 试产结果的一行（良率由数据库推导）。 */
  public record ProductionTrialResultRow(
      UUID id,
      UUID trialOrderId,
      BigDecimal outputQuantity,
      BigDecimal qualifiedQuantity,
      BigDecimal defectiveQuantity,
      BigDecimal yieldRate,
      String conclusion,
      OffsetDateTime recordedAt,
      UUID recordedBy,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 试产成本视图：成本头（一个币种）+ 明细行。 */
  public record ProductionTrialCostView(
      @Schema(nullable = true) UUID costId,
      UUID trialOrderId,
      String currency,
      BigDecimal totalAmount,
      @Schema(nullable = true) BigDecimal massTargetCost,
      boolean massTargetAvailable,
      String massTargetNote,
      List<ProductionTrialCostLineRow> lines) {}

  /** 成本明细行的一行。 */
  public record ProductionTrialCostLineRow(
      UUID id,
      UUID costId,
      String costType,
      String costTypeLabel,
      BigDecimal amount,
      String currency,
      String description,
      OffsetDateTime incurredAt,
      UUID createdBy,
      OffsetDateTime createdAt) {}

  /** 转量产建议的一行。 */
  public record ProductionTrialMassProductionRecommendationRow(
      UUID id,
      UUID trialOrderId,
      String decision,
      String decisionLabel,
      String rationale,
      String conditionNote,
      BigDecimal minYieldThreshold,
      @Schema(nullable = true) BigDecimal maxCostVarianceRate,
      BigDecimal observedYieldRate,
      @Schema(nullable = true) BigDecimal observedCostVarianceRate,
      @Schema(nullable = true) BigDecimal observedTrialCost,
      @Schema(nullable = true) BigDecimal observedMassTargetCost,
      String observedCurrency,
      UUID createdBy,
      OffsetDateTime createdAt) {}

  /** 试产单状态迁移事件的一行。 */
  public record ProductionTrialTransitionRow(
      UUID id,
      UUID trialOrderId,
      String fromStatus,
      String fromStatusLabel,
      String toStatus,
      String toStatusLabel,
      String reason,
      UUID actorId,
      String actorName,
      OffsetDateTime occurredAt,
      @Schema(nullable = true) OffsetDateTime appliedAt) {}

  /** catalog 的 BOM 版本候选（跨服务只读读回来的形状）。 */
  public record ProductionTrialBomVersionCandidateRow(
      UUID id,
      String versionNo,
      String name,
      String bomCode,
      String outputSkuCode,
      String outputSkuName,
      String status,
      String statusLabel,
      long componentCount,
      boolean effective) {}

  // ================================================================ 输入

  /** 试产单输入（建单）。 */
  public record ProductionTrialCreateInput(
      @NotBlank @Size(max = 64) String trialNo,
      /** BOM 版本（catalog 3.5.2.1 的 id，**逻辑引用**）。 */
      @NotNull UUID bomVersionId,
      /** 目标数量（蓝图输入「试产申请（BOM 版本、目标数量）」）。 */
      @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal targetQuantity,
      @Size(max = 64) String productionLineCode,
      @Size(max = 200) String productionLineName,
      @Size(max = 64) String factoryCode,
      @Size(max = 200) String factoryName,
      @Size(max = 64) String qualityStandardCode,
      @Size(max = 500) String qualityStandardNote,
      /** 币种：一张单一个币种。 */
      @NotBlank String currency,
      /** 量产目标成本（**同一币种**；取不到就传 null，**不传 0**）。 */
      @Schema(nullable = true) @DecimalMin(value = "0") BigDecimal massTargetCost,
      @Schema(nullable = true) UUID requesterId,
      @Schema(nullable = true) String requesterName,
      @NotNull OffsetDateTime requestedAt,
      @Schema(nullable = true) OffsetDateTime plannedStartAt,
      @Schema(nullable = true) OffsetDateTime plannedEndAt,
      /** 上游 6.6.1.2 打样单（**逻辑引用 + 快照**，零外键）。 */
      @Schema(nullable = true) UUID developmentSampleOrderId,
      @Schema(nullable = true) @Size(max = 64) String developmentSampleOrderNo,
      @Size(max = 1000) String purposeNote,
      @Size(max = 1000) String note,
      /** 质检方案（逐项登记）。 */
      List<ProductionTrialInspectionItemInput> inspectionItems) {}

  /** 试产单改单输入（整体替换语义）。 */
  public record ProductionTrialUpdateInput(
      @Size(max = 64) String productionLineCode,
      @Size(max = 200) String productionLineName,
      @Size(max = 64) String factoryCode,
      @Size(max = 200) String factoryName,
      @Size(max = 64) String qualityStandardCode,
      @Size(max = 500) String qualityStandardNote,
      @Schema(nullable = true) OffsetDateTime plannedStartAt,
      @Schema(nullable = true) OffsetDateTime plannedEndAt,
      @Size(max = 1000) String purposeNote,
      @Size(max = 1000) String note) {}

  /** 推进状态输入。 */
  public record ProductionTrialTransitionInput(
      @NotNull ProductionTrialStatus toStatus, @NotBlank @Size(max = 500) String reason) {}

  /** 质检标准项输入。 */
  public record ProductionTrialInspectionItemInput(
      @NotBlank @Size(max = 64) String itemCode,
      @NotBlank @Size(max = 200) String itemName,
      @Size(max = 200) String standardValue,
      @Size(max = 200) String inspectionMethod,
      @Size(max = 16) String unit,
      boolean criticalItem,
      Integer sortOrder,
      @Size(max = 1000) String note) {}

  /** 质检判定输入（**判定不可撤销成「待判定」**）。 */
  public record ProductionTrialInspectionJudgmentInput(
      @NotNull ProductionTrialJudgment judgment,
      @Size(max = 200) String measuredValue,
      @Size(max = 1000) String note) {}

  /** 试产结果输入（**良率不收**：由数据库按 合格 ÷ 产出 推导）。 */
  public record ProductionTrialResultInput(
      @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal outputQuantity,
      @NotNull @DecimalMin(value = "0") BigDecimal qualifiedQuantity,
      @NotBlank @Size(max = 1000) String conclusion,
      @NotNull OffsetDateTime recordedAt,
      @Size(max = 1000) String note) {}

  /** 成本明细行输入。 */
  public record ProductionTrialCostLineInput(
      @NotNull ProductionTrialCostType costType,
      @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount,
      @NotBlank @Size(max = 500) String description,
      @NotNull OffsetDateTime incurredAt) {}

  /** 记录「已由加工单承接」的输入（8.4.1 组装单，**逻辑引用 + 快照**）。 */
  public record ProductionTrialRequirementReleaseInput(
      @NotNull UUID assemblyOrderRefId,
      @NotBlank @Size(max = 64) String assemblyOrderNo,
      @Size(max = 1000) String note) {}

  /** 转量产建议输入（**生成条件必填**：这次结论是在什么门槛下得出的）。 */
  public record ProductionTrialMassProductionRecommendationInput(
      @NotNull ProductionTrialMassProductionDecision decision,
      @NotBlank @Size(max = 2000) String rationale,
      @NotBlank @Size(max = 2000) String conditionNote,
      /** 良率门槛（**比例**，0.95 = 95%）。 */
      @NotNull @DecimalMin(value = "0") BigDecimal minYieldThreshold,
      /** 成本偏差上限（**比例**，0.10 = 10%）；null = 本次不设这道门槛。 */
      @Schema(nullable = true) @DecimalMin(value = "0") BigDecimal maxCostVarianceRate) {}
}
