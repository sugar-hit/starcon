package com.starcon.finance;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 12.4.1.1 计价方法配置的传输模型。
 *
 * <p>蓝图 12-finance.md 原文：
 *
 * <blockquote>
 *
 * 职责：配置成本计算方法（先进先出/移动加权平均/批次成本/指定成本法），按法人或品类设置，确定生效时间。 输入：计价方法选择；生效时间；适用范围。 输出：生效的成本方法配置。 关系：上游 = 用户会计政策决定；下游 = 12.4.1.2、13 利润。<b>系统提供选项与试算，不替用户决定政策</b>；切换方法需重算并留版本对比。
 *
 * </blockquote>
 *
 * <p><b>命名。</b>统一用 {@code FinanceCostValuation*} 前缀，与既有服务的类型名零重叠 —— 契约被 codegen
 * 合并进一个扁平文件，同名后者覆盖前者且不报冲突（见 tools/codegen/catalog-contracts.py 的跨服务重名检测）。
 *
 * <p><b>金额一律 BigDecimal + NUMERIC</b>：本文件里没有一个 {@code double}。金额 {@code
 * numeric(19,4)}，数量 {@code numeric(19,6)}。
 *
 * <p><b>系统提供选项与试算，不替用户决定政策。</b>这句话在本模块的落地方式是：
 *
 * <ul>
 *   <li>四个计价方法是<b>选项</b>（{@link FinanceCostValuationMethodOption}：值 + 名称 + 口径说明），
 *       服务层只负责把它们摆出来，<b>不预置任何一行配置</b>；
 *   <li>落库的每一行配置都是<b>租户自己选</b>的，且带<b>生效时间</b>与<b>版本链</b>；
 *   <li><b>试算</b>把四种方法在同一批真实成本层上的结果<b>并排给出</b>，
 *       但<b>不执行重算</b>（重算属 12.4.1.2）、<b>不产出任何成本事实</b>。
 * </ul>
 */
public final class CostValuationModels {

  private CostValuationModels() {}

  /** 金额小数位（与同服务 12.4.2.1 费用归集一致）。 */
  public static final int MONEY_SCALE = 4;

  /** 数量小数位（AGENTS.md：数量 NUMERIC(19,6)）。 */
  public static final int QUANTITY_SCALE = 6;

  // ================================================================ 枚举

  /**
   * 计价方法。
   *
   * <p>蓝图原文点名四个：先进先出 / 移动加权平均 / 批次成本 / 指定成本法 —— 本枚举就是这句话的<b>逐项</b>落地。
   *
   * <p><b>枚举只增不改</b>：计价方法会进历史快照（{@code finance_cost_valuation_method.valuation_method}），
   * 改语义会让历史成本读起来是错的。
   */
  public enum ValuationMethod {
    /** 先进先出：按到货先后消耗成本层。 */
    FIFO(
        "先进先出",
        "按成本层的到货时间升序消耗：先买的货先结转成本。价格随批次波动时，"
            + "结转成本接近最早那批的进价。"),
    /** 移动加权平均：全部有效成本层按数量加权平均。 */
    MOVING_AVERAGE(
        "移动加权平均",
        "把当前全部有效成本层按数量加权平均，得到一个单位成本："
            + "总成本 ÷ 总数量。价格波动被摊平。"),
    /** 批次成本：按指定批次结转。 */
    BATCH(
        "批次成本",
        "按出库所属的那一个批次结转成本：单位成本取该批次的进价。"
            + "试算时必须指定批次，否则算不出来（响应里如实写明「未指定批次」）。"),
    /** 指定成本法：按人工指定的单位成本结转。 */
    SPECIFIED(
        "指定成本法",
        "不按实际进价，按人工维护的指定单价结转。指定单价由本模块按 SKU + 币种维护，"
            + "未维护的 SKU 算不出结果（响应里如实写明「未维护指定成本」）。");

    private final String label;
    private final String basis;

    ValuationMethod(String label, String basis) {
      this.label = label;
      this.basis = basis;
    }

    public String label() {
      return label;
    }

    public String basis() {
      return basis;
    }

    /** 解析方法取值；非法取值返回 {@code null}（由服务层给一句能直接展示的话）。 */
    public static ValuationMethod parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var m : values()) {
        if (m.name().equalsIgnoreCase(raw.trim())) return m;
      }
      return null;
    }
  }

  /** 适用范围：按法人 / 按品类（蓝图「按法人或品类设置」）。 */
  public enum ValuationScopeType {
    /** 按法人：法人来自 identity 的 17 法人档案（跨服务逻辑引用）。 */
    LEGAL_ENTITY("按法人"),
    /** 按品类：品类来自 catalog 的品类树（跨服务逻辑引用）。 */
    CATEGORY("按品类");

    private final String label;

    ValuationScopeType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static ValuationScopeType parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var t : values()) {
        if (t.name().equalsIgnoreCase(raw.trim())) return t;
      }
      return null;
    }
  }

  /** 配置状态。 */
  public enum ValuationStatus {
    /** 生效中（同一范围同时只有一个）。 */
    ACTIVE("生效中"),
    /** 已被新版本取代（历史，保留供版本对比）。 */
    SUPERSEDED("已被取代"),
    /** 已作废（终态，不生效）。 */
    CANCELLED("已作废");

    private final String label;

    ValuationStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 终态：不可再改。 */
    public boolean terminal() {
      return this == CANCELLED;
    }
  }

  /** 重算标记（重算本身属 12.4.1.2，本模块只标记）。 */
  public enum RecalculationStatus {
    /** 不需要重算（首次配置，或方法未变）。 */
    NOT_REQUIRED("无需重算"),
    /** 方法被切换过，需要 12.4.1.2 重算。 */
    PENDING("待重算（12.4.1.2）");

    private final String label;

    RecalculationStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 能力声明

  /** 能力条目：每一维说清「有没有、依据是什么、缺了会怎样」。 */
  public record FinanceCostValuationCapability(
      String module,
      String name,
      boolean available,
      String basis,
      String ownerModule,
      String detail) {}

  /** 下游缺口：谁没交付、归谁。 */
  public record FinanceCostValuationDownstreamGap(
      String module,
      String name,
      String ownerService,
      boolean available,
      String group,
      String detail) {}

  /** 12.4.1.1 的能力声明。 */
  public record FinanceCostValuationCapabilities(
      List<FinanceCostValuationCapability> capabilities,
      List<FinanceCostValuationDownstreamGap> downstreamGaps) {}

  // ================================================================ 选项

  /**
   * 一个计价方法选项（值 + 名称 + 口径说明）。
   *
   * <p>🔴 这是「系统提供选项」的那一半：系统把四个方法摆出来，**不替用户选**。
   */
  public record FinanceCostValuationMethodOption(String value, String label, String basis) {}

  /** 筛选项下拉（全部来自**真实数据**或本模块自己的词表，不是写死的清单）。 */
  public record FinanceCostValuationOptions(
      List<FinanceCostValuationMethodOption> methods,
      List<String> scopeTypes,
      List<String> statuses,
      List<String> currencies,
      List<String> recalculationStatuses) {}

  /**
   * 一个可选范围（法人或品类）。
   *
   * @param available 上游读到了吗（false ⇒ 界面显示「读不到」，**不是**「没有法人 / 没有品类」）
   */
  public record FinanceCostValuationScopeOption(
      String scopeType,
      UUID refId,
      String code,
      String name,
      String status,
      boolean available,
      String note) {}

  /** 范围选项（法人 + 品类）。上游读不到时 available=false 并写明原因。 */
  public record FinanceCostValuationScopeOptions(
      List<FinanceCostValuationScopeOption> legalEntities,
      List<FinanceCostValuationScopeOption> categories,
      String legalEntityNote,
      String categoryNote) {}

  // ================================================================ 方法配置

  /** 一条计价方法配置（版本链上的一版）。 */
  public record FinanceCostValuationMethodRow(
      UUID id,
      String scopeType,
      UUID scopeRefId,
      String scopeCode,
      String scopeName,
      ValuationMethod valuationMethod,
      String valuationMethodLabel,
      /** 该方法的口径说明（随行返回，避免半年后没人说得清口径）。 */
      String methodBasis,
      String currency,
      OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      String status,
      int version,
      @Schema(nullable = true) UUID supersedesId,
      String recalculationStatus,
      String recalculationOwnerModule,
      String note,
      @Schema(nullable = true) UUID createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * 登记 / 切换计价方法（按「范围」upsert：同范围已有生效版本 ⇒ 追加新版本）。
   *
   * <p>⚠️ {@code effectiveFrom} 是<b>必填</b>：没有生效时间的计价方法等于没有 —— 上线之后改配置，
   * 会把切换前算出来的成本变成另一个口径，而那笔成本没人能解释。
   */
  public record FinanceCostValuationMethodInput(
      String scopeType,
      UUID scopeRefId,
      String scopeCode,
      String scopeName,
      String valuationMethod,
      String currency,
      OffsetDateTime effectiveFrom,
      String note) {}

  /** 方法配置列表页（真分页 + 真筛选）。 */
  public record FinanceCostValuationMethodPage(
      List<FinanceCostValuationMethodRow> items, int page, int size, long total, long activeCount) {}

  /** 版本对比的一行差异。 */
  public record FinanceCostValuationVersionDiff(
      String field, String fieldLabel, String fromValue, String toValue, boolean changed) {}

  /**
   * 版本对比（蓝图「切换方法需重算并<strong>留版本对比</strong>」）。
   *
   * <p>⚠️ 对比的是<b>配置本身</b>（方法 / 币种 / 生效区间 / 备注），
   * **不是**重算结果 —— 重算属 12.4.1.2，本模块只负责把「换之前是什么、换之后是什么」摆清楚。
   */
  public record FinanceCostValuationVersionComparison(
      UUID scopeRefId,
      String scopeType,
      String scopeCode,
      String scopeName,
      FinanceCostValuationMethodRow from,
      FinanceCostValuationMethodRow to,
      List<FinanceCostValuationVersionDiff> differences,
      boolean methodChanged,
      /** 重算提醒（换方法 ⇒ 需要 12.4.1.2 重算）。 */
      String recalculationNote) {}

  // ================================================================ 指定成本

  /** 指定成本法下的指定单价（一行 = 一个 SKU + 一个币种的一版）。 */
  public record FinanceCostValuationSpecifiedCostRow(
      UUID id,
      UUID skuId,
      String skuCode,
      String currency,
      BigDecimal unitCost,
      OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      String status,
      int version,
      String note,
      OffsetDateTime createdAt) {}

  /** 维护指定单价。 */
  public record FinanceCostValuationSpecifiedCostInput(
      UUID skuId,
      String skuCode,
      String currency,
      BigDecimal unitCost,
      OffsetDateTime effectiveFrom,
      String note) {}

  /** 指定单价列表页。 */
  public record FinanceCostValuationSpecifiedCostPage(
      List<FinanceCostValuationSpecifiedCostRow> items, int page, int size, long total) {}

  // ================================================================ 试算

  /**
   * 一个方法在本次试算里的结果。
   *
   * <p>🔴 {@code comparable=false} 时 {@code unitCost} 与 {@code costAmount} <b>一律为空</b>，
   * {@code gapNote} 写明「为什么算不出来」—— 绝不显示 0 冒充「成本是 0」。
   */
  public record FinanceCostValuationTrialResultRow(
      String valuationMethod,
      String valuationMethodLabel,
      String methodBasis,
      String currency,
      BigDecimal coveredQuantity,
      BigDecimal shortfallQuantity,
      @Schema(nullable = true) BigDecimal unitCost,
      @Schema(nullable = true) BigDecimal costAmount,
      /**
       * 与**当前生效方法**的成本差额（正 = 这个方法更贵）。
       *
       * <p>库存不足导致覆盖不全时**不给差额** —— 拿覆盖了一半的成本和覆盖完整的成本相减，
       * 那个差额是假的。
       */
      @Schema(nullable = true) BigDecimal differenceToEffectiveMethod,
      boolean comparable,
      String gapNote,
      String basisNote) {}

  /** 一次试算（表头 + 四个方法的结果 + 与当前生效方法的差额）。 */
  public record FinanceCostValuationTrialRow(
      UUID id,
      String scopeType,
      UUID scopeRefId,
      String scopeCode,
      String scopeName,
      UUID skuId,
      String skuCode,
      String currency,
      BigDecimal issueQuantity,
      BigDecimal availableQuantity,
      int layerCount,
      /** 成本层来源服务（catalog 的 5.2.1.1 成本构成）。 */
      String sourceService,
      String sourceNote,
      String basisNote,
      /** 当前生效的计价方法（试算时刻）。 */
      @Schema(nullable = true) String effectiveMethod,
      /** 当前生效方法在该批数据上的成本金额（供对比）。 */
      @Schema(nullable = true) BigDecimal effectiveMethodCostAmount,
      List<FinanceCostValuationTrialResultRow> results,
      OffsetDateTime createdAt) {}

  /**
   * 发起一次试算。
   *
   * @param batchRef 批次成本法试算时指定的批次（留空 ⇒ 批次法算不出来，如实写明）
   * @param effectiveAt 试算口径时刻（默认现在）：决定「当时生效的是哪一版配置」
   */
  public record FinanceCostValuationTrialRequest(
      String scopeType,
      UUID scopeRefId,
      UUID skuId,
      String skuCode,
      String currency,
      BigDecimal issueQuantity,
      @Schema(nullable = true) String batchRef,
      @Schema(nullable = true) OffsetDateTime effectiveAt) {}

  /** 试算列表页。 */
  public record FinanceCostValuationTrialPage(
      List<FinanceCostValuationTrialRow> items, int page, int size, long total) {}
}
