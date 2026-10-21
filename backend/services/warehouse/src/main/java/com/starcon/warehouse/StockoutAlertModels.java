package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 7.8.2.2 缺货与饥饿预警的传输模型。
 *
 * <p>蓝图原话：「识别因分摊/优先级导致的店铺"饥饿"（长期分不到货、影响绩效），以及真正的全网缺货，区分两者给出不同处置建议。输入：分摊结果；店铺销售与绩效；总可用量。输出：饥饿店铺清单 + 缺货清单 + 建议（调优先级/调保留/补货）。」以及关系段的红线：「**"没货卖"和"没分到货"是两种问题**，处置完全不同，不能混在一个缺货告警里。」
 *
 * <p><b>命名空间：全部 {@code Stockout*} / {@code Starvation*} 前缀。</b>springdoc 用 record 的
 * <b>简单类名</b>做 {@code components.schemas} 的键，重名会被<b>静默覆盖</b> —— 别人的字段形状被改掉、
 * 单测全绿、只有跨服务的契约比对才红（7.8.2.1 已经踩过一次：{@code AllocationRunPage} 与
 * 7.8.1.1 的同名 DTO 撞车，把别人的契约换掉了）。所以本模块一个裸名都不复用。
 *
 * <p><b>两套模型、两套枚举、两个方向</b>：{@link StarvationAlertRow} 与
 * {@link StockoutAlertRow} 连字段名都不共用（除了生命周期那几列）。这不是啰嗦，
 * 是「混在一个缺货告警里」这件事在类型层面就做不到。
 *
 * <p><b>「不知道」是一个状态，不是一个 0</b>。三处「不知道」都有各自的显式取值：
 *
 * <ul>
 *   <li>{@link StockoutState#NO_LEDGER_RECORD}：账本里查不到这个 SKU 的库存记录
 *       （{@code availableQuantity = null}）—— 0 意味着「一件都没有」，而查不到记录意味着「要去查」。
 *   <li>{@link StockoutState#UNKNOWN_DEMAND} / {@link StarvationState#UNKNOWN_DEMAND}：
 *       没有销量登记（{@code demandUnits = null}）—— 0 需求会被读成「不用补货」。
 *   <li>{@link DemandState#UNKNOWN}：需求状态本身就是一个可筛选的维度。
 * </ul>
 *
 * <p><b>建议是结构，不是文案</b>：{@link AlertRecommendation} 带着目标对象
 * （{@code target} / {@code targetLabel}）、定位键（{@code policyId} / {@code referenceId} /
 * {@code warehouseId}）、当前值、建议值与理由。运营拿到的是「把 7.8.1.2 的这个保底从 0 调到 30 件」，
 * 而不是「建议多给点货」。
 */
public final class StockoutAlertModels {

  private StockoutAlertModels() {}

  // ------------------------------------------------------------ 枚举

  /** 店铺饥饿判定。 */
  public enum StarvationState {
    STARVED("长期分不到货"),
    UNDER_SUPPLIED("供给不足（还没到长期为零）"),
    UNKNOWN_DEMAND("不知道：没有销量登记"),
    HEALTHY("供给正常");

    private final String label;

    StarvationState(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 缺货判定。仓级断货与全网缺货是**两个取值**，因为处置不同。 */
  public enum StockoutState {
    NO_LEDGER_RECORD("查不到库存记录（不是 0 件）"),
    NETWORK_STOCKED_OUT("全网缺货"),
    WAREHOUSE_STOCKED_OUT("该仓断货（货在别的仓）"),
    AT_RISK("覆盖不足"),
    UNKNOWN_DEMAND("不知道：没有销量登记"),
    HEALTHY("供给正常");

    private final String label;

    StockoutState(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 严重度。 */
  public enum Severity {
    CRITICAL("严重"),
    WARNING("警告"),
    INFO("提示");

    private final String label;

    Severity(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 需求状态。UNKNOWN 是显式取值，不是「需求为 0」。 */
  public enum DemandState {
    KNOWN("已登记"),
    UNKNOWN("未登记");

    private final String label;

    DemandState(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 饥饿的根因：它到底是被什么挤下去的。 */
  public enum StarvationRootCause {
    FLOOR("配了保底却没保住"),
    PRIORITY("优先级排在后面"),
    SHARE_LIMIT("触到份额上限"),
    NO_SALES("没有销量登记"),
    UNKNOWN("判不出来");

    private final String label;

    StarvationRootCause(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 建议的种类。每一种都指向**具体的上游对象**。 */
  public enum RecommendationKind {
    RAISE_FLOOR("调保底（7.8.1.2 的 allocation_floor）"),
    RAISE_PRIORITY("调优先级（7.8.1.1 的 allocation_policy_member）"),
    REGISTER_SALES("登记销量（7.8.1.1 的 allocation_shop_sales）"),
    REPLENISH("补货（6.2.1.2，尚未交付）"),
    NONE("本模块给不出可执行建议");

    private final String label;

    RecommendationKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 处置生命周期。 */
  public enum LifecycleState {
    OPEN("待处理"),
    ACKNOWLEDGED("已认领"),
    RESOLVED("已处置");

    private final String label;

    LifecycleState(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 清单的筛选口径。 */
  public enum StarvationScope {
    ALL("全部"),
    PROBLEM("只看有问题的（饥饿 + 供给不足）"),
    STARVED("只看长期分不到货的"),
    UNDER_SUPPLIED("只看供给不足的"),
    UNKNOWN("只看判不出来的（没有销量登记）"),
    OPEN("只看还没处置的");

    private final String label;

    StarvationScope(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 清单的筛选口径。 */
  public enum StockoutScope {
    ALL("全部"),
    PROBLEM("只看有问题的"),
    STOCKED_OUT("只看缺货的（全网 / 仓级）"),
    AT_RISK("只看覆盖不足的"),
    UNKNOWN("只看判不出来的"),
    OPEN("只看还没处置的");

    private final String label;

    StockoutScope(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ------------------------------------------------------------ 建议

  /**
   * 一条建议 —— **结构，不是文案**。
   *
   * @param kind 建议的种类
   * @param target 目标对象的表 / 模块标识（{@code allocation_floor} /
   *     {@code allocation_policy_member} / {@code allocation_shop_sales} / {@code 6.2.1.2}）
   * @param targetLabel 目标对象的人话说明
   * @param policyId 目标分摊策略 id（调保底 / 调优先级时有）
   * @param referenceId 目标店铺 id（调保底 / 调优先级 / 登记销量时有）
   * @param warehouseId 目标仓 id（补货时有）
   * @param currentValue 目标对象**当前**的值。<b>null = 不知道 / 不适用</b>
   * @param suggestedValue 建议调成的值
   * @param reason 为什么是这个值 —— 把算式摊开，运营可以自己改
   */
  public record AlertRecommendation(
      RecommendationKind kind,
      String target,
      String targetLabel,
      @Schema(nullable = true) UUID policyId,
      @Schema(nullable = true) UUID referenceId,
      @Schema(nullable = true) UUID warehouseId,
      @Schema(nullable = true) BigDecimal currentValue,
      @Schema(nullable = true) BigDecimal suggestedValue,
      String reason) {}

  // ------------------------------------------------------------ 评估请求

  /**
   * 一次评估的参数。
   *
   * <p>「长期」是相对的，所以观察窗口按**分摊轮次**算而不是按天算：
   * 一个 SKU 一天分三次还是一个月分三次，「连续三轮没分到」的分量完全不同。
   *
   * @param windowRounds 观察窗口内的分摊轮次数（每个 SKU 各取最近 N 轮）。1~50，默认 5
   * @param coverageThreshold 覆盖率阈值：累计分摊量 ÷ 需求量低于它算供给不足。0~1，默认 0.5
   * @param zeroRoundRatio 零分配轮次占比阈值：窗口内分到 0 件的轮次占比高于它算「长期」。0~1，默认 0.5
   * @param demandWindowDays 需求窗口天数（当 SKU 没有分摊轮次可参照时用）。1~365，默认 30
   * @param skuId 只评估某个 SKU（可空 = 全部）
   * @param warehouseId 只评估某个仓（可空 = 全部）
   * @param referenceId 只评估某个店铺（可空 = 全部）
   */
  public record StockoutEvaluationRequest(
      @Schema(nullable = true) Integer windowRounds,
      @Schema(nullable = true) BigDecimal coverageThreshold,
      @Schema(nullable = true) BigDecimal zeroRoundRatio,
      @Schema(nullable = true) Integer demandWindowDays,
      @Schema(nullable = true) UUID skuId,
      @Schema(nullable = true) UUID warehouseId,
      @Schema(nullable = true) UUID referenceId) {}

  /**
   * 一次评估的结果。
   *
   * @param scannedStockoutCount 缺货侧扫描到的 SKU×仓 组合数
   * @param scannedStarvationCount 饥饿侧扫描到的 SKU×店铺 组合数
   * @param stockoutProblemCount 其中命中缺货的行数
   * @param starvationProblemCount 其中命中饥饿的行数
   * @param excludedByStockoutCount <b>因为全网缺货而未列入饥饿清单</b>的店铺数。
   *     它们不是「没饿」，是根因是缺货（处置是补货）；这个数让它们不无声消失
   * @param unknownDemandCount 两边都没有销量登记、判不出来的行数
   */
  public record StockoutEvaluationResult(
      OffsetDateTime evaluatedAt,
      int windowRounds,
      int scannedStockoutCount,
      int scannedStarvationCount,
      int stockoutProblemCount,
      int starvationProblemCount,
      int excludedByStockoutCount,
      int unknownDemandCount,
      String note) {}

  // ------------------------------------------------------------ 饥饿清单

  /** 饥饿清单的查询条件。 */
  public record StarvationAlertQuery(
      @Schema(nullable = true) UUID skuId,
      @Schema(nullable = true) UUID referenceId,
      @Schema(nullable = true) UUID policyId,
      @Schema(nullable = true) String shopOperatingMode,
      @Schema(nullable = true) String scope,
      @Schema(nullable = true) String severity,
      @Schema(nullable = true) String lifecycle,
      int page,
      int size) {}

  /**
   * 一行店铺饥饿。
   *
   * @param demandUnits 观察窗口内登记的销量。<b>null = 没有销量登记 = 不知道</b>
   * @param coverageRatio 累计分摊量 ÷ 需求量。需求未知时为 null
   * @param maxRunAvailableQuantity 窗口内各轮「总可用量」的最大值。
   *     它 &gt; 0 是「有货可分」的证据 —— 它恒为 0 的话那就不是饥饿而是缺货
   * @param floorCommittedQuantity 当前生效保底档的承诺件数（建议的起点）
   */
  public record StarvationAlertRow(
      UUID id,
      UUID skuId,
      @Schema(nullable = true) String skuCode,
      String sourceSystem,
      UUID referenceId,
      String referenceLabel,
      String shopOperatingMode,
      UUID policyId,
      OffsetDateTime windowStart,
      OffsetDateTime windowEnd,
      int observedRunCount,
      int zeroRoundCount,
      BigDecimal allocatedTotal,
      @Schema(nullable = true) BigDecimal maxRunAvailableQuantity,
      @Schema(nullable = true) BigDecimal minRunAvailableQuantity,
      @Schema(nullable = true) Integer lastAllocationOrder,
      String lastOutcome,
      @Schema(nullable = true) UUID lastRunId,
      @Schema(nullable = true) BigDecimal demandUnits,
      boolean demandRegistered,
      @Schema(nullable = true) LocalDate demandWindowStart,
      @Schema(nullable = true) LocalDate demandWindowEnd,
      @Schema(nullable = true) BigDecimal coverageRatio,
      @Schema(nullable = true) Integer memberPriority,
      @Schema(nullable = true) BigDecimal memberShareLimit,
      @Schema(nullable = true) Boolean memberEnabled,
      @Schema(nullable = true) Integer floorOrder,
      @Schema(nullable = true) String floorStatus,
      @Schema(nullable = true) BigDecimal floorShortfallQuantity,
      @Schema(nullable = true) Boolean floorSuppressed,
      @Schema(nullable = true) String floorSuppressionReason,
      @Schema(nullable = true) String floorCommittedMode,
      @Schema(nullable = true) BigDecimal floorCommittedRatio,
      @Schema(nullable = true) BigDecimal floorCommittedQuantity,
      @Schema(nullable = true) BigDecimal floorSafetyQuantity,
      StarvationState alertState,
      Severity severity,
      DemandState demandState,
      StarvationRootCause rootCause,
      String note,
      AlertRecommendation recommendation,
      LifecycleState lifecycleState,
      @Schema(nullable = true) UUID acknowledgedByMembershipId,
      @Schema(nullable = true) OffsetDateTime acknowledgedAt,
      @Schema(nullable = true) OffsetDateTime resolvedAt,
      @Schema(nullable = true) String resolutionNote,
      int recurrenceCount,
      OffsetDateTime detectedAt) {}

  /** 饥饿清单的汇总。 */
  public record StarvationAlertSummary(
      long rowCount,
      long starvedCount,
      long underSuppliedCount,
      long unknownDemandCount,
      long healthyCount,
      long criticalCount,
      long warningCount,
      long openCount,
      long acknowledgedCount,
      long resolvedCount,
      long floorShortfallCount,
      BigDecimal allocatedTotal,
      BigDecimal demandTotal,
      long demandKnownCount,
      @Schema(nullable = true) OffsetDateTime lastDetectedAt) {}

  /** 饥饿清单（真分页，total 不受分页影响）。 */
  public record StarvationAlertPage(
      List<StarvationAlertRow> rows,
      long total,
      int page,
      int size,
      StarvationAlertSummary summary) {}

  // ------------------------------------------------------------ 缺货清单

  /** 缺货清单的查询条件。 */
  public record StockoutAlertQuery(
      @Schema(nullable = true) UUID skuId,
      @Schema(nullable = true) UUID warehouseId,
      @Schema(nullable = true) String scope,
      @Schema(nullable = true) String severity,
      @Schema(nullable = true) String lifecycle,
      int page,
      int size) {}

  /**
   * 一行缺货。
   *
   * @param availableQuantity 该仓的账本可用量（= on_hand − reserved）。<b>null = 查不到记录</b>
   * @param networkAvailableQuantity 该 SKU 全网合计的账本可用量。
   *     它 ≤ 0 才是蓝图说的「真正的全网缺货」
   * @param runAvailableQuantity 最近一轮分摊时的总可用量。与现在的账本可用量交叉核对
   * @param daysOfCover 可售天数 = 账本可用量 ÷ 日均需求。日均为 0 时为 null
   */
  public record StockoutAlertRow(
      UUID id,
      UUID skuId,
      @Schema(nullable = true) String skuCode,
      @Schema(nullable = true) UUID warehouseId,
      @Schema(nullable = true) String warehouseCode,
      @Schema(nullable = true) String warehouseName,
      @Schema(nullable = true) BigDecimal onHandQuantity,
      @Schema(nullable = true) BigDecimal reservedQuantity,
      @Schema(nullable = true) BigDecimal availableQuantity,
      @Schema(nullable = true) BigDecimal networkOnHandQuantity,
      @Schema(nullable = true) BigDecimal networkReservedQuantity,
      @Schema(nullable = true) BigDecimal networkAvailableQuantity,
      @Schema(nullable = true) UUID runId,
      @Schema(nullable = true) OffsetDateTime runAt,
      @Schema(nullable = true) String runMethod,
      @Schema(nullable = true) BigDecimal runAvailableQuantity,
      @Schema(nullable = true) BigDecimal runAllocatableQuantity,
      @Schema(nullable = true) BigDecimal runUnallocatedQuantity,
      @Schema(nullable = true) BigDecimal runAllocatedTotal,
      @Schema(nullable = true) BigDecimal demandUnits,
      boolean demandRegistered,
      @Schema(nullable = true) LocalDate demandWindowStart,
      @Schema(nullable = true) LocalDate demandWindowEnd,
      int demandShopCount,
      @Schema(nullable = true) BigDecimal coverageRatio,
      @Schema(nullable = true) BigDecimal daysOfCover,
      StockoutState alertState,
      Severity severity,
      DemandState demandState,
      String note,
      AlertRecommendation recommendation,
      LifecycleState lifecycleState,
      @Schema(nullable = true) UUID acknowledgedByMembershipId,
      @Schema(nullable = true) OffsetDateTime acknowledgedAt,
      @Schema(nullable = true) OffsetDateTime resolvedAt,
      @Schema(nullable = true) String resolutionNote,
      int recurrenceCount,
      OffsetDateTime detectedAt) {}

  /** 缺货清单的汇总。 */
  public record StockoutAlertSummary(
      long rowCount,
      long networkStockedOutCount,
      long warehouseStockedOutCount,
      long atRiskCount,
      long noLedgerRecordCount,
      long unknownDemandCount,
      long healthyCount,
      long criticalCount,
      long warningCount,
      long openCount,
      long acknowledgedCount,
      long resolvedCount,
      BigDecimal availableTotal,
      long availableKnownCount,
      BigDecimal demandTotal,
      long demandKnownCount,
      long networkStockedOutSkuCount,
      @Schema(nullable = true) OffsetDateTime lastDetectedAt) {}

  /** 缺货清单（真分页，total 不受分页影响）。 */
  public record StockoutAlertPage(
      List<StockoutAlertRow> rows,
      long total,
      int page,
      int size,
      StockoutAlertSummary summary) {}

  // ------------------------------------------------------------ 处置

  /**
   * 处置一条预警的请求体。
   *
   * @param note 处置说明：做了什么。**必填** —— 没有处置说明的记录在复盘时与「没发生过」一模一样
   */
  public record StockoutAlertResolutionInput(
      @NotBlank @Size(max = 500) String note) {}

  /** 认领 / 处置的结果。 */
  public record StockoutAlertActionResult(
      UUID alertId,
      String alertKind,
      LifecycleState lifecycleState,
      OffsetDateTime at,
      String message) {}

  // ------------------------------------------------------------ 能力

  /**
   * 一项能力声明。<b>{@code available=false} 时必须同时给出 {@code ownerModule}</b> ——
   * 与 4.1.1.1 / 4.3.1.2 的既有写法一致（ListingClaimModels / ContentWritebackModels）：
   * 「这一环没通」必须是可读字段，而不是让人从 404 去猜。
   */
  public record StockoutAlertCapabilityDimension(
      String dimension,
      String description,
      boolean available,
      String source,
      String ownerModule,
      String impact) {}

  /**
   * 能力清单。
   *
   * @param dimensions 逐维度能力声明
   * @param performanceAvailable 店铺绩效（17 绩效域）是否可用。恒为 false
   * @param performanceOwnerModule 绩效的归属模块
   * @param replenishmentAvailable 补货（6.2.1.2，06 采购域）是否可用。恒为 false
   * @param replenishmentOwnerModule 补货的归属模块
   * @param workbenchAlertAvailable 工作台预警（1 工作台）是否可用。恒为 false
   * @param workbenchAlertOwnerModule 工作台的归属模块
   */
  public record StockoutAlertCapabilities(
      List<StockoutAlertCapabilityDimension> dimensions,
      List<String> starvationStates,
      List<String> stockoutStates,
      List<String> severities,
      List<String> recommendationKinds,
      List<String> lifecycleStates,
      boolean performanceAvailable,
      String performanceOwnerModule,
      boolean replenishmentAvailable,
      String replenishmentOwnerModule,
      boolean workbenchAlertAvailable,
      String workbenchAlertOwnerModule,
      String separationNote,
      String unknownNote,
      String availableNote,
      String upstreamNote,
      String downstreamNote) {}
}
