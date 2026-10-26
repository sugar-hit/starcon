package com.starcon.finance;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 12.4.1.3 成本差异分析的传输模型。
 *
 * <p>蓝图 12-finance.md 原文：
 *
 * <blockquote>
 *
 * 职责：分析成本差异：实际成本 vs 预算/标准成本、采购价波动、头程成本波动、损耗差异、汇率影响，定位利润波动主因。 输入：成本计算结果；预算或历史基线；价格与汇率历史。 输出：差异分析报表 + 主因归因 + 异常提示。 关系：上游 = 12.4.1.2；下游 = 13 分析、6 采购（价格谈判依据）。<b>利润下滑时先看成本差异</b>，比看销售额更能定位问题。
 *
 * </blockquote>
 *
 * <p><b>命名。</b>统一用 {@code FinanceCostVariance*} 前缀，与既有服务的类型名零重叠 —— 契约被 codegen
 * 合并进一个扁平文件，同名后者覆盖前者且不报冲突（见 tools/codegen/catalog-contracts.py 的跨服务重名检测）。
 *
 * <p><b>金额一律 BigDecimal + NUMERIC</b>：本文件里没有一个 {@code double}。金额 {@code
 * numeric(19,4)}，数量 {@code numeric(19,6)}。
 *
 * <p><b>🔴 不可计算 ≠ 0</b>：可比性（{@code comparability}）是**独立于金额的一个维度** ——
 * 「无基线，不可比」时 {@code amount} 一律为空（不是 0），理由写在 {@code comparableReason} 里。
 * 差额表里出现一个 0，读者会以为「差异确实是 0」；而真相是「这一格没算出来」。
 *
 * <p><b>🔴 多币种绝不相加</b>：报表按币种分组（{@code currencyGroups}），
 * 每组里的金额只在**同一币种内**相加。
 */
public final class CostVarianceModels {

  private CostVarianceModels() {}

  /** 金额小数位（与同服务 12.4.1.1 / 12.4.1.2 / 12.4.2.1 一致）。 */
  public static final int MONEY_SCALE = 4;

  /** 数量小数位（AGENTS.md：数量 NUMERIC(19,6)）。 */
  public static final int QUANTITY_SCALE = 6;

  // ================================================================ 枚举

  /**
   * 驱动因素：蓝图点名的五类差异。
   *
   * <p><b>枚举只增不改</b>：驱动因素会进历史报表行（{@code finance_cost_variance_item.driver}），
   * 改语义会让历史差异分析读起来是错的。
   *
   * <p>🔴 <b>两类关系不可混</b>：
   *
   * <ul>
   *   <li>{@link #STANDARD_COST} 是**总差异**（实际成本 − 标准成本）；
   *   <li>{@link #FX} 是总差异的**构成部分**（汇率变动带来的那一截）；
   *   <li>{@link #PURCHASE_PRICE} / {@link #FIRST_MILE} / {@link #SHRINKAGE} 是
   *       **独立观察到的驱动因素** —— 它们解释「单位成本为什么动了」，各自有自己的口径与来源。
   * </ul>
   *
   * ⇒ 五类**不可相加**（除了 FX 与 STANDARD_COST 的构成关系），
   *   报表只给**贡献排名**与**主因**，不给「五类之和」。
   */
  public enum VarianceDriver {
    /** 实际成本 vs 预算 / 标准成本（**总差异**）。 */
    STANDARD_COST("实际成本 vs 预算/标准成本"),
    /** 采购价波动（trade 6 采购，授权只读端口）。 */
    PURCHASE_PRICE("采购价波动"),
    /** 头程成本波动（warehouse 10.4.1.1 头程发运单，授权只读端口；**单据级**）。 */
    FIRST_MILE("头程成本波动"),
    /** 损耗差异（warehouse 7.1.2.1 调整流水，授权只读端口）。 */
    SHRINKAGE("损耗差异"),
    /** 汇率影响（catalog 5.2.2.1 汇率事实，授权只读端口；总差异的**构成部分**）。 */
    FX("汇率影响");

    private final String label;

    VarianceDriver(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static VarianceDriver parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var d : values()) {
        if (d.name().equalsIgnoreCase(raw.trim())) return d;
      }
      return null;
    }
  }

  /**
   * 可比性：这一格**能不能算**。
   *
   * <p>🔴 这是本模块最重要的一个枚举：**「算不出来」与「差异是 0」是两回事**。
   * 基线缺失、币种不一致、上游不可得 —— 这三种情况下金额一律为空，
   * 由 {@code comparableReason} 写明原因。
   */
  public enum Comparability {
    /** 可比：数字是真的。 */
    COMPARABLE("可比"),
    /** 无基线：该 SKU 该期间没有标准成本 / 预算 ⇒ **差异不可计算**。 */
    NO_BASELINE("无基线，不可比"),
    /** 币种不一致：基线币种与实际成本币种不同 ⇒ 绝不跨币种相减。 */
    CURRENCY_MISMATCH("币种不一致，不可比"),
    /** 上游不可得：采购价 / 头程 / 损耗 / 汇率的事实读不到。 */
    UPSTREAM_UNAVAILABLE("上游不可得，不可计算"),
    /** 本类不适用（例如该币种就是报表币种 ⇒ 汇率影响恒为 0，无需计算）。 */
    NOT_APPLICABLE("不适用");

    private final String label;

    Comparability(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static Comparability parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var c : values()) {
        if (c.name().equalsIgnoreCase(raw.trim())) return c;
      }
      return null;
    }
  }

  /** 基线类型：标准成本 / 预算。 */
  public enum BaselineType {
    /** 标准成本。 */
    STANDARD_COST("标准成本"),
    /** 预算。 */
    BUDGET("预算");

    private final String label;

    BaselineType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static BaselineType parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var t : values()) {
        if (t.name().equalsIgnoreCase(raw.trim())) return t;
      }
      return null;
    }
  }

  /** 基线维度：按 SKU / 按类目。 */
  public enum BaselineDimension {
    /** 按 SKU。 */
    SKU("按 SKU"),
    /** 按类目。 */
    CATEGORY("按类目");

    private final String label;

    BaselineDimension(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static BaselineDimension parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var d : values()) {
        if (d.name().equalsIgnoreCase(raw.trim())) return d;
      }
      return null;
    }
  }

  /** 基线版本状态。 */
  public enum BaselineStatus {
    /** 当前这版。 */
    ACTIVE("生效中"),
    /** 已被新版本接替。 */
    SUPERSEDED("已被接替"),
    /** 撤下（不接替）。 */
    ARCHIVED("已撤下");

    private final String label;

    BaselineStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static BaselineStatus parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw.trim())) return s;
      }
      return null;
    }
  }

  /** 告警严重度。 */
  public enum AlertSeverity {
    /** 提示。 */
    INFO("提示"),
    /** 警告：这一格不可比 / 不可计算。 */
    WARNING("警告"),
    /** 严重：数据缺口影响结论。 */
    CRITICAL("严重");

    private final String label;

    AlertSeverity(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static AlertSeverity parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw.trim())) return s;
      }
      return null;
    }
  }

  // ================================================================ 能力声明

  /** 能力条目：每一维说清「有没有、依据是什么、缺了会怎样」。 */
  public record FinanceCostVarianceCapability(
      String module, String name, boolean available, String basis, String ownerModule, String detail) {}

  /** 下游缺口：谁没交付、归谁。 */
  public record FinanceCostVarianceDownstreamGap(
      String module, String name, String ownerService, boolean available, String group, String detail) {}

  /** 12.4.1.3 的能力声明。 */
  public record FinanceCostVarianceCapabilities(
      List<FinanceCostVarianceCapability> capabilities,
      List<FinanceCostVarianceDownstreamGap> downstreamGaps) {}

  // ================================================================ 选项

  /** 筛选项下拉（全部来自真实数据与真实枚举，不含编造的取值）。 */
  public record FinanceCostVarianceOptions(
      List<String> baselineTypes,
      List<String> dimensions,
      List<String> currencies,
      List<String> drivers,
      List<String> comparabilities,
      List<String> severities) {}

  // ================================================================ 基线

  /**
   * 一版标准成本 / 预算基线。
   *
   * <p><b>🔴 这是「历史即证据」的一行</b>：版本一旦写定，基线值 / 维度 / 币种 / 版本号 /
   * 生效起点都不可改写（DB 触发器钉死）；改基线 = 追加新版本。
   */
  public record FinanceCostVarianceBaselineRow(
      UUID id,
      String baselineType,
      String dimension,
      @Schema(nullable = true) UUID dimensionRefId,
      String dimensionCode,
      String dimensionName,
      String currency,
      BigDecimal unitCost,
      OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      String status,
      int version,
      @Schema(nullable = true) UUID supersedesId,
      String note,
      @Schema(nullable = true) UUID createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 基线列表页（真分页 + 真筛选）。 */
  public record FinanceCostVarianceBaselinePage(
      List<FinanceCostVarianceBaselineRow> items, int page, int size, long total) {}

  /**
   * 登记 / 切换一版基线（= **追加新版本**，不是就地改）。
   *
   * @param baselineType 基线类型（STANDARD_COST / BUDGET）
   * @param dimension 维度（SKU / CATEGORY）
   * @param dimensionRefId 跨服务逻辑引用（catalog 的 SKU / 品类 id）
   * @param currency 基线币种（**绝不跨币种相加**）
   * @param unitCost 标准 / 预算单位成本（必须 &gt; 0）
   * @param effectiveFrom 生效起点（**必须晚于上一版**，否则历史差异分析会被追溯改写）
   */
  public record FinanceCostVarianceBaselineRequest(
      String baselineType,
      String dimension,
      @Schema(nullable = true) UUID dimensionRefId,
      @Schema(nullable = true) String dimensionCode,
      @Schema(nullable = true) String dimensionName,
      String currency,
      BigDecimal unitCost,
      OffsetDateTime effectiveFrom,
      @Schema(nullable = true) String note) {}

  /**
   * 版本对比（切换前 / 切换后）。
   *
   * <p>与 12.4.1.1 的版本对比同一纪律：逐字段给出差异，让「切换基线会改哪些历史结论」看得见。
   */
  public record FinanceCostVarianceBaselineComparison(
      UUID fromId,
      int fromVersion,
      BigDecimal fromUnitCost,
      OffsetDateTime fromEffectiveFrom,
      @Schema(nullable = true) OffsetDateTime fromEffectiveTo,
      String fromStatus,
      UUID toId,
      int toVersion,
      BigDecimal toUnitCost,
      OffsetDateTime toEffectiveFrom,
      @Schema(nullable = true) OffsetDateTime toEffectiveTo,
      String toStatus,
      BigDecimal unitCostDifference,
      String currency,
      String dimension,
      @Schema(nullable = true) UUID dimensionRefId,
      List<String> changedFields,
      String note) {}

  // ================================================================ 报表

  /**
   * 一次差异分析（表头）。
   *
   * <p><b>🔴 计价方法取的是「批次行上的快照」</b>（{@code valuationMethod} /
   * {@code valuationMethodVersion}），**不是**「当前生效版本」—— 拿当前版本去套历史批次
   * 会把口径算错。
   */
  public record FinanceCostVarianceReportRow(
      UUID id,
      String scopeType,
      UUID scopeRefId,
      String scopeCode,
      String scopeName,
      OffsetDateTime periodFrom,
      OffsetDateTime periodTo,
      String reportingCurrency,
      String status,
      /** 基数来源：12.4.1.2 的批次 id（逗号分隔）。 */
      String costRunIds,
      String valuationMethod,
      int valuationMethodVersion,
      String basisNote,
      String sourceNote,
      int runCount,
      int skuCount,
      int itemCount,
      int comparableSkuCount,
      int noBaselineSkuCount,
      int alertCount,
      /** 出现过的币种（逗号分隔）—— 分组呈现的依据。 */
      String currencies,
      @Schema(nullable = true) UUID createdBy,
      OffsetDateTime createdAt) {}

  /** 报表列表页（真分页 + 真筛选）。 */
  public record FinanceCostVarianceReportPage(
      List<FinanceCostVarianceReportRow> items, int page, int size, long total) {}

  /**
   * 报表明细（一个 SKU 在一个币种下的这一类差异）。
   *
   * <p>🔴 {@code comparability} 与 {@code amount} 是**两个维度**：不可比时 {@code amount} 为空，
   * 理由在 {@code comparableReason} 里。
   */
  public record FinanceCostVarianceItemRow(
      UUID id,
      UUID reportId,
      UUID skuId,
      String skuCode,
      String currency,
      String driver,
      String driverLabel,
      @Schema(nullable = true) BigDecimal amount,
      @Schema(nullable = true) BigDecimal quantity,
      @Schema(nullable = true) BigDecimal actualQuantity,
      @Schema(nullable = true) BigDecimal actualCostAmount,
      @Schema(nullable = true) BigDecimal standardCostAmount,
      @Schema(nullable = true) BigDecimal baselineUnitCost,
      @Schema(nullable = true) UUID baselineId,
      @Schema(nullable = true) Integer baselineVersion,
      String comparability,
      String comparabilityLabel,
      String comparableReason,
      String note) {}

  /** 明细列表页（真分页 + 真筛选）。 */
  public record FinanceCostVarianceItemPage(
      List<FinanceCostVarianceItemRow> items, int page, int size, long total) {}

  /**
   * 按币种分组的驱动因素汇总 + **主因归因**。
   *
   * @param rank 该币种内按 |金额| 的贡献排名（1 = 最大贡献）；不可计算为 null
   * @param isPrimary 主因标记（rank = 1 且可计算）
   * @param contributionShare 贡献占比（0~1）
   */
  public record FinanceCostVarianceDriverRow(
      UUID id,
      UUID reportId,
      String currency,
      String driver,
      String driverLabel,
      @Schema(nullable = true) BigDecimal amount,
      @Schema(nullable = true) BigDecimal quantity,
      @Schema(nullable = true) BigDecimal actualCostAmount,
      @Schema(nullable = true) BigDecimal standardCostAmount,
      @Schema(nullable = true) BigDecimal actualQuantity,
      @Schema(nullable = true) BigDecimal standardQuantity,
      String comparability,
      String comparabilityLabel,
      String comparableReason,
      @Schema(nullable = true) Integer rank,
      boolean isPrimary,
      @Schema(nullable = true) BigDecimal contributionShare,
      String basis) {}

  /** 驱动因素列表页（真分页 + 真筛选）。 */
  public record FinanceCostVarianceDriverPage(
      List<FinanceCostVarianceDriverRow> items, int page, int size, long total) {}

  /**
   * 一个币种的差异小结（按币种分组呈现 —— **绝不跨币种相加**）。
   *
   * @param primaryDriver 主因（该币种内贡献最大的那一类；不可计算时为空）
   */
  public record FinanceCostVarianceCurrencyGroup(
      String currency,
      @Schema(nullable = true) BigDecimal actualCostAmount,
      @Schema(nullable = true) BigDecimal standardCostAmount,
      @Schema(nullable = true) BigDecimal totalVarianceAmount,
      @Schema(nullable = true) BigDecimal fxImpactAmount,
      @Schema(nullable = true) BigDecimal actualQuantity,
      int comparableSkuCount,
      int noBaselineSkuCount,
      int skuCount,
      @Schema(nullable = true) String primaryDriver,
      @Schema(nullable = true) String primaryDriverLabel,
      @Schema(nullable = true) BigDecimal primaryDriverAmount,
      List<FinanceCostVarianceDriverRow> drivers,
      List<FinanceCostVarianceAlertRow> alerts,
      String note) {}

  // ================================================================ 异常提示

  /** 一条异常提示。 */
  public record FinanceCostVarianceAlertRow(
      UUID id,
      UUID reportId,
      String code,
      String severity,
      String severityLabel,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) UUID skuId,
      String skuCode,
      @Schema(nullable = true) BigDecimal amount,
      String message,
      OffsetDateTime createdAt) {}

  /** 异常提示列表页（真分页 + 真筛选）。 */
  public record FinanceCostVarianceAlertPage(
      List<FinanceCostVarianceAlertRow> items, int page, int size, long total) {}

  // ================================================================ 报表全文

  /**
   * 一张差异分析报表的全文：表头 + 按币种分组 + 每组的驱动因素与告警。
   *
   * <p>🔴 {@code currencyGroups} 是**按币种分组**的：不同币种之间**没有任何合计数** ——
   * 报表里刻意不提供「全部差异合计」这一格（加不出来，也不该加）。
   */
  public record FinanceCostVarianceReportDetail(
      FinanceCostVarianceReportRow report,
      List<FinanceCostVarianceCurrencyGroup> currencyGroups,
      int itemCount,
      int alertCount,
      String note) {}

  /**
   * 执行一次差异分析。
   *
   * @param scopeType 计价范围类型（LEGAL_ENTITY / CATEGORY）
   * @param scopeRefId 计价范围（跨服务逻辑引用）
   * @param periodFrom 期间起（含）
   * @param periodTo 期间止（不含）
   * @param reportingCurrency 报表币种（汇率影响折算的目标币种）
   * @param baselineType 用哪一档基线（STANDARD_COST / BUDGET）
   * @param dimension 基线维度（SKU / CATEGORY）：按哪个维度找标准成本
   */
  public record FinanceCostVarianceReportRequest(
      String scopeType,
      UUID scopeRefId,
      @Schema(nullable = true) String scopeCode,
      @Schema(nullable = true) String scopeName,
      OffsetDateTime periodFrom,
      OffsetDateTime periodTo,
      String reportingCurrency,
      String baselineType,
      @Schema(nullable = true) String dimension,
      @Schema(nullable = true) String note) {}
}
