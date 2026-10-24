package com.starcon.finance;

import com.starcon.finance.CostValuationModels.FinanceCostValuationMethodOption;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 12.4.1.2 成本计算执行的传输模型。
 *
 * <p>蓝图 12-finance.md 原文：
 *
 * <blockquote>
 *
 * 职责：执行成本计算：对销售出库、发货、平台仓转出计算应结转成本，生成成本层与成本分录；支持按期间批量重算与差异对比。 输入：库存流水（7.1.2.1）；计价方法；成本层数据；计算期间。 输出：成本计算结果（成本层 + 结转金额）+ 差异对比。 关系：上游 = 7 库存流水、12.4.1.1；下游 = 13 利润、12.5.2 凭证。<b>数量账在 warehouse、价值账在 finance</b>——这是两个服务的职责划分，重算不能写库存。
 *
 * </blockquote>
 *
 * <p><b>命名。</b>统一用 {@code FinanceCostCalculation*} 前缀，与既有服务的类型名零重叠 —— 契约被 codegen
 * 合并进一个扁平文件，同名后者覆盖前者且不报冲突（见 tools/codegen/catalog-contracts.py 的跨服务重名检测）。
 *
 * <p><b>金额一律 BigDecimal + NUMERIC</b>：本文件里没有一个 {@code double}。金额 {@code
 * numeric(19,4)}，数量 {@code numeric(19,6)}。
 *
 * <p><b>🔴 算不出来 ≠ 0</b>：覆盖不全时 {@code unitCost} 一律为空（半截成本不能当成完整单价），
 * {@code shortfallQuantity} 与 {@code gapNote} 如实给出缺了多少、为什么缺。
 *
 * <p><b>🔴 数量账在 warehouse、价值账在 finance</b>：本模块**只读**库存流水，
 * 一条库存流水都不改 —— 成本计算写的是**价值**（应结转多少成本），
 * 不是**数量**（仓库里还剩多少）。
 */
public final class CostCalculationModels {

  private CostCalculationModels() {}

  /** 金额小数位（与同服务 12.4.1.1 / 12.4.2.1 一致）。 */
  public static final int MONEY_SCALE = 4;

  /** 数量小数位（AGENTS.md：数量 NUMERIC(19,6)）。 */
  public static final int QUANTITY_SCALE = 6;

  // ================================================================ 枚举

  /**
   * 业务类型：蓝图点名的三类出库。
   *
   * <p>与 warehouse 7.1.2.1 的 {@code inventory_transaction.kind} 对应关系：
   *
   * <ul>
   *   <li>{@code SHIPMENT}（出库）⇒ 发货或销售出库，按来源单据类型再分；
   *   <li>{@code TRANSFER_OUT}（调拨出）⇒ 平台仓转出。
   * </ul>
   *
   * <p><b>枚举只增不改</b>：业务类型会进历史分录（{@code finance_cost_calculation_entry.business_type}），
   * 改语义会让历史成本读起来是错的。
   */
  public enum CalculationBusinessType {
    /** 销售出库：kind=SHIPMENT 且来源单据不是发货交接单。 */
    SALES_OUTBOUND("销售出库"),
    /** 发货：kind=SHIPMENT 且来源单据是发货交接单（SHIPMENT_HANDOVER）。 */
    SHIPMENT("发货"),
    /** 平台仓转出：kind=TRANSFER_OUT（调拨出）。 */
    PLATFORM_TRANSFER_OUT("平台仓转出");

    private final String label;

    CalculationBusinessType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static CalculationBusinessType parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var t : values()) {
        if (t.name().equalsIgnoreCase(raw.trim())) return t;
      }
      return null;
    }
  }

  /** 批次状态。 */
  public enum CalculationRunStatus {
    /** 算到一半（正常情况下不会留在这个状态对外可见）。 */
    PENDING("计算中"),
    /** 算完了：成本层与结转金额都已落库。 */
    COMPLETED("已完成"),
    /** 算失败了（上游读不到等）：批次留痕，但不产生任何成本事实。 */
    FAILED("已失败");

    private final String label;

    CalculationRunStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static CalculationRunStatus parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw.trim())) return s;
      }
      return null;
    }
  }

  /** 批次类型：为什么会有这一次计算。 */
  public enum CalculationReason {
    /** 首次计算。 */
    INITIAL("首次计算"),
    /** 计价方法切换后的重算。 */
    METHOD_SWITCH("方法切换重算"),
    /** 人工发起重算。 */
    MANUAL("人工重算");

    private final String label;

    CalculationReason(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static CalculationReason parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var r : values()) {
        if (r.name().equalsIgnoreCase(raw.trim())) return r;
      }
      return null;
    }
  }

  /** 分录状态：这一笔出库的成本算全了没有。 */
  public enum CalculationEntryStatus {
    /** 完整：出库量被成本层完全覆盖。 */
    CALCULATED("已结转"),
    /** 有缺口：库存不足或上游层不够，只结转了覆盖部分。 */
    INCOMPLETE("有缺口");

    private final String label;

    CalculationEntryStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static CalculationEntryStatus parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw.trim())) return s;
      }
      return null;
    }
  }

  // ================================================================ 能力声明

  /** 能力条目：每一维说清「有没有、依据是什么、缺了会怎样」。 */
  public record FinanceCostCalculationCapability(
      String module, String name, boolean available, String basis, String ownerModule, String detail) {}

  /** 下游缺口：谁没交付、归谁。 */
  public record FinanceCostCalculationDownstreamGap(
      String module, String name, String ownerService, boolean available, String group, String detail) {}

  /** 12.4.1.2 的能力声明。 */
  public record FinanceCostCalculationCapabilities(
      List<FinanceCostCalculationCapability> capabilities,
      List<FinanceCostCalculationDownstreamGap> downstreamGaps) {}

  // ================================================================ 选项

  /**
   * 筛选项下拉。
   *
   * <p>计价方法选项**复用** 12.4.1.1 的 {@link FinanceCostValuationMethodOption}
   * （同一个契约类型，不另起一个同名近似的）—— 方法只有四个、口径说明也只有一份。
   */
  public record FinanceCostCalculationOptions(
      List<FinanceCostValuationMethodOption> methods,
      List<String> businessTypes,
      List<String> statuses,
      List<String> reasons,
      List<String> currencies) {}

  // ================================================================ 批次

  /**
   * 一次成本计算（表头）。
   *
   * <p><b>🔴 这是「历史即证据」的一行</b>：批次一旦完成，方法 / 期间 / 口径 / 汇总
   * 都不可改写（DB 触发器钉死）；重算请另起批次。
   */
  public record FinanceCostCalculationRunRow(
      UUID id,
      String scopeType,
      UUID scopeRefId,
      String scopeCode,
      String scopeName,
      String valuationMethod,
      String valuationMethodLabel,
      /** 当时生效的那一版配置（12.4.1.1 的版本链）；该范围在期间起点没有配置时为空。 */
      @Schema(nullable = true) Integer valuationMethodVersion,
      @Schema(nullable = true) UUID valuationMethodConfigId,
      String currency,
      OffsetDateTime periodFrom,
      OffsetDateTime periodTo,
      String recalculationReason,
      String status,
      /** 数量账的来源服务（warehouse 7.1.2.1）。 */
      String flowSourceService,
      /** 成本层的来源服务（catalog 5.2.1.1）。 */
      String layerSourceService,
      String basisNote,
      String sourceNote,
      int skuCount,
      int layerCount,
      int entryCount,
      /** 覆盖不全的分录条数（> 0 ⇒ 这批成本不完整，必须能被看见）。 */
      int incompleteEntryCount,
      /** 期间出库总量（Σ 出库数量）。 */
      BigDecimal totalQuantity,
      /** 已结转成本的数量（Σ 覆盖数量）—— 与出库总量的差就是缺口。 */
      BigDecimal totalCoveredQuantity,
      /** 缺口总量（Σ 缺口数量）。 */
      BigDecimal totalShortfallQuantity,
      BigDecimal totalCostAmount,
      @Schema(nullable = true) UUID supersedesRunId,
      @Schema(nullable = true) UUID supersededByRunId,
      @Schema(nullable = true) UUID createdBy,
      OffsetDateTime createdAt,
      @Schema(nullable = true) OffsetDateTime completedAt) {}

  /** 批次列表页（真分页 + 真筛选）。 */
  public record FinanceCostCalculationRunPage(
      List<FinanceCostCalculationRunRow> items, int page, int size, long total, long completedCount) {}

  /**
   * 批次明细：表头 + 该批次的**全部**成本层与成本分录。
   *
   * <p>⚠️ 明细里**不塞**「已重算」之类的结论 —— 重算事实由批次本身（另一条批次 + 重算链）表达。
   */
  public record FinanceCostCalculationRunDetail(
      FinanceCostCalculationRunRow run,
      List<FinanceCostCalculationLayerRow> layers,
      List<FinanceCostCalculationEntryRow> entries) {}

  /**
   * 发起一次成本计算。
   *
   * @param scopeType 计价范围类型（LEGAL_ENTITY / CATEGORY）
   * @param valuationMethod 计价方法（**必填**：没有方法的成本计算不是计算）
   * @param currency 计价币种（**必填**：绝不跨币种相加）
   * @param periodFrom 期间起（含）
   * @param periodTo 期间止（不含）
   * @param batchRef 批次成本法（BATCH）要用的批次；留空 ⇒ 批次法算不出结果，如实报出
   */
  public record FinanceCostCalculationRequest(
      String scopeType,
      UUID scopeRefId,
      String scopeCode,
      String scopeName,
      String valuationMethod,
      String currency,
      OffsetDateTime periodFrom,
      OffsetDateTime periodTo,
      @Schema(nullable = true) String batchRef,
      @Schema(nullable = true) String reason,
      @Schema(nullable = true) String note) {}

  // ================================================================ 成本层

  /**
   * 一层被消耗的成本。
   *
   * @param layerQuantity 该层**可消耗**的数量（窗口内的真实到货记录）
   * @param consumedQuantity 本批次**实际消耗**的数量
   */
  public record FinanceCostCalculationLayerRow(
      UUID id,
      UUID runId,
      UUID skuId,
      String skuCode,
      int layerIndex,
      String lotRef,
      @Schema(nullable = true) LocalDate receivedDate,
      BigDecimal layerQuantity,
      BigDecimal layerAmount,
      BigDecimal unitCost,
      BigDecimal consumedQuantity,
      BigDecimal consumedAmount,
      String currency,
      String layerSourceService) {}

  /** 成本层列表页。 */
  public record FinanceCostCalculationLayerPage(
      List<FinanceCostCalculationLayerRow> items, int page, int size, long total) {}

  // ================================================================ 成本分录

  /**
   * 一笔出库的一个 SKU 应结转多少成本。
   *
   * <p>🔴 {@code status='INCOMPLETE'} 时 {@code unitCost} <b>一律为空</b>，
   * {@code shortfallQuantity} 写明缺了多少，{@code gapNote} 写明为什么缺 ——
   * 绝不显示 0 冒充「成本是 0」，也不拿半截成本当成完整单价。
   */
  public record FinanceCostCalculationEntryRow(
      UUID id,
      UUID runId,
      /** 上游库存流水（warehouse 7.1.2.1）—— 跨服务逻辑引用。 */
      UUID flowTransactionId,
      String flowRefType,
      @Schema(nullable = true) UUID flowRefId,
      String flowKind,
      OffsetDateTime occurredAt,
      String businessType,
      String businessTypeLabel,
      UUID skuId,
      String skuCode,
      BigDecimal issueQuantity,
      BigDecimal coveredQuantity,
      BigDecimal shortfallQuantity,
      @Schema(nullable = true) BigDecimal unitCost,
      BigDecimal costAmount,
      String currency,
      int layerCount,
      String status,
      String gapNote) {}

  /** 成本分录列表页（真分页 + 真筛选 + 汇总）。 */
  public record FinanceCostCalculationEntryPage(
      List<FinanceCostCalculationEntryRow> items,
      int page,
      int size,
      long total,
      BigDecimal totalQuantity,
      BigDecimal totalCostAmount,
      long incompleteCount) {}

  // ================================================================ 差异对比

  /**
   * 两次批次之间**一个 SKU** 的差异。
   *
   * @param onlyInFrom 只在旧批次里出现（重算后这个 SKU 没有出库了）
   * @param onlyInTo 只在新批次里出现
   */
  public record FinanceCostCalculationVarianceRow(
      UUID skuId,
      String skuCode,
      BigDecimal fromQuantity,
      BigDecimal fromCostAmount,
      int fromEntryCount,
      BigDecimal toQuantity,
      BigDecimal toCostAmount,
      int toEntryCount,
      BigDecimal quantityDifference,
      BigDecimal costAmountDifference,
      boolean onlyInFrom,
      boolean onlyInTo) {}

  /**
   * 差异对比（蓝图「支持按期间批量重算与<strong>差异对比</strong>」）。
   *
   * <p>⚠️ 这是**两次计算批次之间**的对比（通常是切换计价方法后重算的同期间），
   * <b>不是</b> 12.4.1.3 的成本差异分析报表 —— 那个模块未交付。
   */
  public record FinanceCostCalculationVarianceComparison(
      UUID fromRunId,
      UUID toRunId,
      String fromValuationMethod,
      String fromValuationMethodLabel,
      int fromValuationMethodVersion,
      String toValuationMethod,
      String toValuationMethodLabel,
      int toValuationMethodVersion,
      OffsetDateTime periodFrom,
      OffsetDateTime periodTo,
      BigDecimal fromTotalQuantity,
      BigDecimal toTotalQuantity,
      BigDecimal fromTotalCostAmount,
      BigDecimal toTotalCostAmount,
      /** 正数 = 新批次更贵；负数 = 更便宜。 */
      BigDecimal totalCostAmountDifference,
      int comparedSkuCount,
      int onlyInFromCount,
      int onlyInToCount,
      List<FinanceCostCalculationVarianceRow> rows,
      String note) {}
}
