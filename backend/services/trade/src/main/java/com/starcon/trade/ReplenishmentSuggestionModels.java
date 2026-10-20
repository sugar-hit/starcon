package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
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
 * 6.2.1.2 补货建议的传输模型。
 *
 * <p>蓝图原话：「计算建议补货量：目标库存覆盖天数 × 日均销量 −（可用 + 在途），并结合 MOQ、
 * 箱规、采购起订额做取整修正，给出建议供应商与到货时间。」输入「销量预测（含近期增长率）；
 * 覆盖天数目标（可按 SKU/分类配置）；MOQ 与箱规（3.6.2）；在途量」。输出「补货建议
 * （建议量、取整说明、预计到货日、预计费用）；支持接受/调整/忽略」。
 *
 * <p><b>命名纪律（硬要求）</b>：springdoc 用 record 的<b>简单类名</b>做 {@code components.schemas} 的键，
 * 重名会被静默覆盖 —— 别人的字段形状被改掉、单测全绿、只有跨服务契约比对才红。本文件所有 record /
 * enum 一律以 {@code Replenishment} 开头。
 *
 * <p><b>五条建模纪律</b>：
 *
 * <ul>
 *   <li><b>「答不出来」与「不需要补」是两个状态</b>：{@link ReplenishmentAdvice#NO_SALES_DATA}
 *       （没有销售速度登记）与 {@link ReplenishmentAdvice#HOLD}（库存已覆盖目标）不折叠成一个 0 ——
 *       折叠掉「没登记数据」会被读成「库存充足」，那正是要防的误判。</li>
 *   <li><b>建议量在基础单位，起订量在采购单位</b>：{@code suggestedQuantity} 是运营填进采购申请的
 *       那个数（基础单位）；{@code suggestedPurchaseQuantity} 是按箱 / 按件订的个数（采购单位）。
 *       两者之间是箱规（{@code boxSpecQuantity}）。</li>
 *   <li><b>取整说明随建议一起返回</b>（{@code roundingNote}）：MOQ / 箱规 / 采购起订额三步各做了什么，
 *       一句话说清 —— 蓝图加粗句「建议必须展示计算过程」。</li>
 *   <li><b>计算过程逐项返回</b>（{@code steps}）：日均销量怎么来的、扣了哪些在途、取整怎么做的。</li>
 *   <li><b>数量全程 BigDecimal</b>，DB 侧 numeric(19,6)；金额 numeric(19,4)，禁浮点。</li>
 * </ul>
 */
public final class ReplenishmentSuggestionModels {

  private ReplenishmentSuggestionModels() {}

  /** 中文名。服务层与前端都拿得到 label，不必各写一遍 switch —— 各写一遍迟早漂移。 */
  public interface Labelled {
    String label();
  }

  // ================================================================ 枚举

  /**
   * 建议类别 —— 一条建议**是什么**。
   *
   * <p>四种取值互不折叠，理由见类注释：
   *
   * <ol>
   *   <li>{@code REPLENISH} —— 真建议：算得出来、有有效报价、净需求量 &gt; 0。可接受 / 调整 / 忽略。</li>
   *   <li>{@code HOLD} —— 算得出来，但库存（含覆盖窗口内可到达的在途）已经覆盖目标库存，不需要补。</li>
   *   <li>{@code NO_SALES_DATA} —— 没有销售速度登记，**答不出**日均销量 ⇒ 目标库存算不出来。</li>
   *   <li>{@code NO_QUOTE} —— 算得出净需求量，但没有有效报价 ⇒ 取不到 MOQ / 交期 / 价格，
   *       给不出可下单的数量（建议量是 null，不是 0，也不是猜一个数）。</li>
   *   <li>{@code NO_BOX_SPEC} —— 有有效报价，但<b>箱规量不到</b>（catalog 没登记该采购单位相对
   *       基础单位的换算比例）⇒ 需求量换算不成可下单的件数。箱规猜成 1 会把订量算少一箱
   *       （1 箱 = 12 件时差 12 倍），所以这里是「答不出来」而不是「按 1 件算」。</li>
   * </ol>
   */
  public enum ReplenishmentAdvice implements Labelled {
    REPLENISH("建议补货"),
    HOLD("无需补货"),
    NO_SALES_DATA("缺日均销量"),
    NO_QUOTE("缺有效报价"),
    NO_BOX_SPEC("缺箱规");

    private final String label;

    ReplenishmentAdvice(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }

    /** 清单排序位次（越小越靠前）。要补的排前面，然后是答不出来的，最后是不用补的。 */
    public int rank() {
      return switch (this) {
        case REPLENISH -> 0;
        case NO_QUOTE -> 1;
        case NO_BOX_SPEC -> 2;
        case NO_SALES_DATA -> 3;
        case HOLD -> 4;
      };
    }
  }

  /** 覆盖天数目标的**作用范围**。取值优先级固定：SKU &gt; CATEGORY &gt; DEFAULT。 */
  public enum ReplenishmentCoverageScope implements Labelled {
    DEFAULT("租户默认"),
    SKU("按 SKU"),
    CATEGORY("按分类");

    private final String label;

    ReplenishmentCoverageScope(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /** 处置状态：待处置 / 已接受 / 已调整 / 已忽略。 */
  public enum ReplenishmentStatus implements Labelled {
    SUGGESTED("待处置"),
    ACCEPTED("已接受"),
    ADJUSTED("已调整"),
    IGNORED("已忽略");

    private final String label;

    ReplenishmentStatus(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /** 处置动作：接受 / 调整 / 忽略。 */
  public enum ReplenishmentDecisionAction implements Labelled {
    ACCEPT("接受"),
    ADJUST("调整"),
    IGNORE("忽略");

    private final String label;

    ReplenishmentDecisionAction(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /** 计算过程的一个步骤。拆解表逐行记下算式里的每一项，让结论可复算。 */
  public enum ReplenishmentStep implements Labelled {
    DAILY_SALES("日均销量"),
    GROWTH_RATE("近期增长率"),
    COVERAGE_DAYS("覆盖天数目标"),
    TARGET_STOCK("目标库存量"),
    AVAILABLE("有效可用量"),
    IN_TRANSIT_TOTAL("在途合计"),
    IN_TRANSIT_ARRIVING("覆盖窗口内可到达在途"),
    RAW_REQUIRED("净需求量"),
    BOX_SPEC("箱规"),
    MOQ("起订量 MOQ"),
    MIN_ORDER_AMOUNT("采购起订额"),
    ROUNDED_QUANTITY("取整后建议量"),
    UNIT_PRICE("适用阶梯单价"),
    ESTIMATED_COST("预计费用"),
    SUPPLIER_LEAD_TIME("供应商交期"),
    PRODUCTION_LEAD_TIME("生产周期"),
    ARRIVAL_DATE("预计到货日");

    private final String label;

    ReplenishmentStep(String label) {
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
   * <p>存在的理由：本模块的输入分属四个服务 / 模块 —— 日均销量在 6.2.1.1 的销售速度登记、
   * 可用量与在途在 warehouse、MOQ / 箱规 / 交期 / 价格在 catalog、头程时效在 10。
   * 哪一个接通了、哪一个没接通，必须由接口自己报出，而不是让调用方从空数组里猜。
   *
   * @param salesVelocityAvailable 6.2.1.1 的销售速度登记是否可用。恒为 true —— 它已在集成头交付
   * @param salesVelocityOwnerModule 销售速度登记的归属模块
   * @param salesVelocityNote 销售速度为什么由 6.2.1.1 维护
   * @param warehouseQuantityAvailable warehouse 7.1.1.3 可用量是否已接入
   * @param warehouseInTransitAvailable warehouse 7.5.1.2 在途是否已接入
   * @param quoteAvailable catalog 3.6.2.1 报价是否已接入
   * @param quoteNote 报价接入方式与「同优先级并列时不替你挑」的说明
   * @param boxSpecAvailable catalog 3.1.2.4 单位换算是否已接入
   * @param boxSpecNote 箱规的来源与「量不到 ≠ 1」的说明
   * @param supplierRatingAvailable 6.1.1.3 供应商评级是否可用。**恒为 false** —— 该模块未交付，
   *     所以本模块**不按评级择优**，改用 MOQ → 交期 → 供应商名 的可解释顺序
   * @param supplierRatingNote 供应商择优为什么不用评级
   * @param firstMileLeadTimeAvailable 10 头程时效是否可用。**恒为 false** —— warehouse 只有逐票 ETA，
   *     不是可用的时效基准（详见 arrivalFormula）
   * @param firstMileLeadTimeNote 头程时效为什么给不出
   * @param purchaseRequestAvailable 6.2.2.1 采购申请是否可用。**恒为 false** —— 下游未交付，
   *     所以本模块只产出建议，不代它建申请
   * @param purchaseRequestNote 采购申请未交付时的说明
   * @param coverageFormula 覆盖天数目标的取法与优先级原文
   * @param requiredFormula 净需求量的算式原文
   * @param roundingFormula 取整修正的三步原文
   * @param arrivalFormula 预计到货日的推导原文
   * @param costFormula 预计费用的算式原文
   * @param note 总体说明
   */
  public record ReplenishmentCapabilities(
      boolean salesVelocityAvailable,
      String salesVelocityOwnerModule,
      String salesVelocityNote,
      boolean warehouseQuantityAvailable,
      boolean warehouseInTransitAvailable,
      boolean quoteAvailable,
      String quoteNote,
      boolean boxSpecAvailable,
      String boxSpecNote,
      boolean supplierRatingAvailable,
      String supplierRatingNote,
      boolean firstMileLeadTimeAvailable,
      String firstMileLeadTimeNote,
      boolean purchaseRequestAvailable,
      String purchaseRequestNote,
      String coverageFormula,
      String requiredFormula,
      String roundingFormula,
      String arrivalFormula,
      String costFormula,
      String note) {}

  // ================================================================ 覆盖天数配置

  /**
   * 登记一条覆盖天数目标。
   *
   * @param scope 作用范围
   * @param skuId SKU（scope=SKU 时必填）
   * @param categoryCode 分类编码（scope=CATEGORY 时必填）
   * @param coverageDays 覆盖天数（&gt; 0）
   * @param effectiveFrom 生效起点（含）
   * @param effectiveTo 生效终点（不含）。null = 当前生效
   * @param note 备注
   */
  public record ReplenishmentCoveragePolicyInput(
      @NotNull ReplenishmentCoverageScope scope,
      @Schema(nullable = true) UUID skuId,
      @Schema(nullable = true) @Size(max = 64) String categoryCode,
      @NotNull @Min(1) @Max(365) Integer coverageDays,
      @NotNull LocalDate effectiveFrom,
      @Schema(nullable = true) LocalDate effectiveTo,
      @Schema(nullable = true) @Size(max = 500) String note) {}

  /** 覆盖天数配置行。 */
  public record ReplenishmentCoveragePolicyRow(
      UUID id,
      String scope,
      String scopeLabel,
      @Schema(nullable = true) UUID skuId,
      @Schema(nullable = true) String categoryCode,
      int coverageDays,
      LocalDate effectiveFrom,
      @Schema(nullable = true) LocalDate effectiveTo,
      String note,
      OffsetDateTime createdAt,
      boolean effective) {}

  /** 覆盖天数配置列表（真分页）。 */
  public record ReplenishmentCoveragePolicyPage(
      List<ReplenishmentCoveragePolicyRow> rows, long total, int page, int size) {}

  // ================================================================ 采购起订额 / 生产周期配置

  /**
   * 登记一条采购起订额 / 生产周期配置。
   *
   * @param supplierId 供应商主体 id（catalog 的 party id）。null = 租户级默认
   * @param supplierRoleId 供应商角色 id。与 supplierId 成对
   * @param minOrderAmount 采购起订额（金额）。null = 不参与取整
   * @param currency 起订额币种。与 minOrderAmount 成对
   * @param productionLeadTimeDays 生产周期（天）。null = 未维护
   * @param effectiveFrom 生效起点（含）
   * @param effectiveTo 生效终点（不含）
   * @param note 备注
   */
  public record ReplenishmentOrderPolicyInput(
      @Schema(nullable = true) UUID supplierId,
      @Schema(nullable = true) UUID supplierRoleId,
      @Schema(nullable = true) @DecimalMin(value = "0", inclusive = false) BigDecimal minOrderAmount,
      @Schema(nullable = true) @Size(min = 3, max = 3) String currency,
      @Schema(nullable = true) @Min(0) @Max(365) Integer productionLeadTimeDays,
      @NotNull LocalDate effectiveFrom,
      @Schema(nullable = true) LocalDate effectiveTo,
      @Schema(nullable = true) @Size(max = 500) String note) {}

  /** 采购起订额 / 生产周期配置行。 */
  public record ReplenishmentOrderPolicyRow(
      UUID id,
      @Schema(nullable = true) UUID supplierId,
      @Schema(nullable = true) UUID supplierRoleId,
      @Schema(nullable = true) String supplierName,
      @Schema(nullable = true) BigDecimal minOrderAmount,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) Integer productionLeadTimeDays,
      LocalDate effectiveFrom,
      @Schema(nullable = true) LocalDate effectiveTo,
      String note,
      OffsetDateTime createdAt,
      boolean effective) {}

  /** 采购起订额 / 生产周期配置列表（真分页）。 */
  public record ReplenishmentOrderPolicyPage(
      List<ReplenishmentOrderPolicyRow> rows, long total, int page, int size) {}

  // ================================================================ 计算

  /**
   * 发起一次补货建议计算。
   *
   * @param asOfDate 评估基准日。null = 取数据库当天。可显式指定以便回溯复算
   * @param defaultCoverageDays 兜底覆盖天数（租户默认）。配置表里的 SKU / 分类配置优先于它
   * @param note 备注
   */
  public record ReplenishmentComputeInput(
      @Schema(nullable = true) LocalDate asOfDate,
      @NotNull @Min(1) @Max(365) Integer defaultCoverageDays,
      @Schema(nullable = true) @Size(max = 500) String note) {}

  /**
   * 一次计算的结果。
   *
   * @param truncated 上游分页或目录查询被上限截断。true 时清单**不完整**，
   *     界面上必须说清楚 —— 一个被悄悄截断的建议清单比一个明确报出截断的清单危险得多
   */
  public record ReplenishmentComputeResult(
      UUID batchId,
      String batchNo,
      LocalDate asOfDate,
      OffsetDateTime computedAt,
      int defaultCoverageDays,
      int itemCount,
      int replenishCount,
      int holdCount,
      int noSalesDataCount,
      int noQuoteCount,
      boolean truncated,
      String truncatedNote,
      String note) {}

  // ================================================================ 批次

  /** 计算批次行。 */
  public record ReplenishmentBatchRow(
      UUID id,
      String batchNo,
      LocalDate asOfDate,
      OffsetDateTime computedAt,
      int defaultCoverageDays,
      int itemCount,
      int replenishCount,
      int holdCount,
      int noSalesDataCount,
      int noQuoteCount,
      int decidedCount,
      boolean truncated,
      String note,
      OffsetDateTime createdAt) {}

  /** 批次列表（真分页）。 */
  public record ReplenishmentBatchPage(List<ReplenishmentBatchRow> rows, long total, int page, int size) {}

  // ================================================================ 建议

  /**
   * 一条补货建议 —— 蓝图输出句的「建议量、取整说明、预计到货日、预计费用」全在这里，
   * 外加把结论复现出来所需的每一个输入。
   */
  public record ReplenishmentSuggestionRow(
      UUID id,
      UUID batchId,
      String batchNo,
      UUID skuId,
      String skuCode,
      String skuName,
      @Schema(nullable = true) BigDecimal dailySalesQuantity,
      @Schema(nullable = true) BigDecimal growthRate,
      @Schema(nullable = true) BigDecimal previousDailySalesQuantity,
      @Schema(nullable = true) LocalDate velocityWindowStart,
      @Schema(nullable = true) LocalDate velocityWindowEnd,
      @Schema(nullable = true) String velocitySourceKind,
      int coverageDays,
      String coverageSource,
      String coverageSourceLabel,
      @Schema(nullable = true) String coverageCategoryCode,
      @Schema(nullable = true) BigDecimal targetStockQuantity,
      BigDecimal availableQuantity,
      BigDecimal inTransitQuantity,
      BigDecimal inTransitArrivingQuantity,
      @Schema(nullable = true) BigDecimal rawRequiredQuantity,
      @Schema(nullable = true) BigDecimal moqQuantity,
      @Schema(nullable = true) String purchaseUnitCode,
      @Schema(nullable = true) BigDecimal boxSpecQuantity,
      @Schema(nullable = true) BigDecimal suggestedPurchaseQuantity,
      @Schema(nullable = true) BigDecimal suggestedQuantity,
      @Schema(nullable = true) BigDecimal roundingAdjustmentQuantity,
      String roundingNote,
      @Schema(nullable = true) BigDecimal minOrderAmount,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) BigDecimal unitPrice,
      @Schema(nullable = true) BigDecimal estimatedCost,
      @Schema(nullable = true) UUID supplierId,
      String supplierName,
      @Schema(nullable = true) UUID supplierRoleId,
      @Schema(nullable = true) Integer supplierPriority,
      String supplierSelectionBasis,
      @Schema(nullable = true) Integer leadTimeDays,
      @Schema(nullable = true) Integer productionLeadTimeDays,
      @Schema(nullable = true) LocalDate expectedArrivalDate,
      String arrivalBasis,
      String advice,
      String adviceLabel,
      String adviceBasis,
      String status,
      String statusLabel,
      @Schema(nullable = true) BigDecimal decidedQuantity,
      @Schema(nullable = true) UUID decisionBy,
      @Schema(nullable = true) OffsetDateTime decisionAt,
      String decisionNote,
      int sortRank) {}

  /** 建议清单分页。 */
  public record ReplenishmentSuggestionPage(
      List<ReplenishmentSuggestionRow> rows,
      long total,
      int page,
      int size,
      @Schema(nullable = true) UUID batchId,
      @Schema(nullable = true) String batchNo,
      @Schema(nullable = true) LocalDate asOfDate,
      int defaultCoverageDays,
      String note) {}

  /** 建议清单查询条件。全部可空，null = 不限。 */
  public record ReplenishmentSuggestionQuery(
      UUID batchId,
      String advice,
      String status,
      String skuCode,
      String keyword,
      Boolean onlyActionable,
      int page,
      int size) {}

  /** 计算过程的一步（逐项拆解）。 */
  public record ReplenishmentStepRow(
      int stepOrder,
      String stepKind,
      String stepKindLabel,
      @Schema(nullable = true) BigDecimal quantity,
      String basis,
      String sourceRef) {}

  /** 处置留痕的一行。 */
  public record ReplenishmentDecisionRow(
      UUID id,
      String action,
      String actionLabel,
      @Schema(nullable = true) BigDecimal quantity,
      String note,
      UUID decidedBy,
      OffsetDateTime decidedAt) {}

  /**
   * 处置一条建议。
   *
   * @param action 处置动作
   * @param quantity 调整后的数量（基础单位）。action=ADJUST 时必填；ACCEPT 时可空（留空即取建议量）
   * @param note 处置说明。**必填** —— 一句「按建议下单」不是留痕
   */
  public record ReplenishmentDecisionInput(
      @NotNull ReplenishmentDecisionAction action,
      @Schema(nullable = true) @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
      @NotBlank @Size(max = 500) String note) {}

  /** 建议详情：结论 + 计算过程 + 处置留痕。 */
  public record ReplenishmentSuggestionDetail(
      ReplenishmentSuggestionRow suggestion,
      List<ReplenishmentStepRow> steps,
      List<ReplenishmentDecisionRow> decisions) {}
}
