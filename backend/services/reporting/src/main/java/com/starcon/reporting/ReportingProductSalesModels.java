package com.starcon.reporting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 13.2.2.2 SKU/SPU/Listing 的**出入参模型**。
 *
 * <p>⚠️ **「算不出来」与「等于 0」必须分开**（与 13.1.1.1 / 13.1.1.2 同一纪律）：
 * 分母为 0、事实不存在、指标未登记，三种情况一律返回 {@code null} / {@code NO_DATA}，
 * **不用 0 冒充**。销量 0 与「这一格没算出来」在页面上长得一样，含义却相反。
 *
 * <p>⚠️ **不跨币种相加**：金额按币种分别报。**只有件数与单数**（与货币无关的量）
 * 才给出一个跨币种合计，它的 {@code currency} 是**空串** —— 空串 = 与币种无关，
 * 不是「币种未知」。
 *
 * <p>⚠️ **名字必须带 {@code Product} 前缀**：13.1.1.1 的 {@code ReportingOverviewModels}
 * 与 13.1.1.2 的 {@code ReportingStoreComparisonModels} 里已经有 {@code MetricDefinitionView}
 * 等同名记录。SpringDoc 用**简单类名**当 schema 名，同名时只能加序号消歧
 * （{@code MetricDefinitionView1}）—— 那个序号取决于**发现顺序**，换个服务先启动就换一个名，
 * 生成出来的契约跟着漂。所以这里用一个**不重名**的名字。
 */
public final class ReportingProductSalesModels {

  private ReportingProductSalesModels() {}

  // ==================================================================
  // 指标口径目录
  // ==================================================================

  /**
   * 一个指标的口径定义（口径唯一的展示形态）。
   *
   * @param numeratorDimensionType 分子取自哪一层（空 = 与本行同层）
   * @param denominatorDimensionType 分母取自哪一层（空 = 与本行同层）
   * @param currencyScope SAME 同币种 / ANY 允许跨币种合计（只给与货币无关的量）
   */
  public record ProductMetricDefinitionView(
      String metricCode,
      String displayName,
      String unit,
      String aggregation,
      @Schema(nullable = true) String numeratorMetricCode,
      @Schema(nullable = true) String denominatorMetricCode,
      @Schema(nullable = true) String numeratorDimensionType,
      @Schema(nullable = true) String denominatorDimensionType,
      String currencyScope,
      boolean currencyAware,
      String description,
      String ownerModule) {}

  // ==================================================================
  // 商品销量报表
  // ==================================================================

  /** 一个指标在某个维度、某个币种下的取值。 */
  public record ProductMetricValue(
      String metricCode,
      /** 币种。**空串 = 与币种无关的合计**（件数 / 单数）；不是「币种未知」。 */
      String currency,
      @Schema(nullable = true) BigDecimal value,
      /** 可用性：AVAILABLE / NO_DATA / NOT_COMPARABLE。 */
      String availability,
      /** 不可比或没有数据的原因（availability != AVAILABLE 时必填）。 */
      String availabilityNote) {}

  /** 报表里的一行 = 一个商品维度（SKU / SPU / Listing）。 */
  public record ProductRow(
      String dimensionType,
      String dimensionId,
      String dimensionKey,
      String dimensionName,
      /** 父层（SKU 行的父是 SPU；其余层为空串）。 */
      String parentDimensionType,
      String parentDimensionKey,
      String parentDimensionName,
      /** 变体签名（catalog 的 variant_signature）：同一个 SPU 下区分变体的那串规格。 */
      String variantSignature,
      /** 这个维度关联到的平台（SPU 层 = 它名下 Listing 覆盖的平台；Listing 层 = 它自己的平台）。 */
      List<String> platformCodes,
      /** 这个维度名下的 SKU 数（SPU = 变体数；Listing = 挂载的 SKU 数；SKU = 1）。 */
      int skuCount,
      /** 这个维度关联到的 Listing 数。 */
      int listingCount,
      /** 该维度出现过的币种（金额类指标按币种分别列）。 */
      List<String> currencies,
      /** 该维度的指标取值。 */
      List<ProductMetricValue> metrics,
      /** Listing 层的销量归属状态：ATTRIBUTED 已唯一归属 / SHARED_SKU 同一 SKU 挂在多个 Listing /
       *  NO_LISTING 这个 SKU 没有任何 Listing / NOT_APPLICABLE 非 Listing 层。 */
      String attributionStatus,
      /** 归属状态的说明（为什么是这个状态）。 */
      String attributionNote) {}

  /** 无法唯一归属到 Listing 的销量（SKU 挂在多个 Listing 上，或没有任何 Listing）。 */
  public record UnattributedSummary(
      /** 无法归属的**件数**（跨币种合计：件数与货币无关）。 */
      BigDecimal quantity,
      /** 无法归属的**订单数**（跨币种合计）。 */
      BigDecimal orderCount,
      /** 涉及多少个 SKU。 */
      int skuCount,
      /** 无法归属的金额**按币种分别列出**（绝不跨币种相加）。 */
      List<CurrencyAmount> amounts,
      /** 说明（为什么归不了）。 */
      String note) {}

  /** 一个币种的金额。 */
  public record CurrencyAmount(String currency, BigDecimal amount) {}

  /** 商品销量报表的完整结果。 */
  public record ProductSalesResponse(
      LocalDate from,
      LocalDate to,
      /** 本次报表的维度层级：SKU / SPU / LISTING。 */
      String level,
      /** 输入「是否合并 SPU」：true 时把 SKU 合并到 SPU 层看。 */
      boolean mergeSpu,
      /** 报表行（真分页）。 */
      List<ProductRow> rows,
      /** 符合筛选条件的总条数（不受分页影响）。 */
      long total,
      int page,
      int size,
      /** 本次报表用到的指标口径（页面上原样显示）。 */
      List<ProductMetricDefinitionView> metricDefinitions,
      /** 无法唯一归属到 Listing 的销量。 */
      UnattributedSummary unattributed,
      /** 口径说明（页面上原样显示）。 */
      List<String> caliberNotes,
      /** 本次窗口内出现过的分桶时区（跨时区店铺汇总必须显示所用时区）。 */
      List<String> timezones,
      /** 数据更新时间（最近一次投影落库时刻）—— 卷 13 卷首「数据延迟要显示」。
   *  **空表时是 null**（不是一个长得像时间的假值）。 */
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      /** 数据更新时间的口径说明。 */
      String dataUpdatedNote,
      /** 本次报表整体状态：PROJECTED / NOT_PROJECTED / PARTIAL。 */
      String status,
      /** 状态说明（为什么是这个状态）。 */
      String statusNote,
      /** 空态原因：NO_FACTS / NOT_PROJECTED / null。 */
      @Schema(nullable = true) String emptyReason,
      /** 空态说明。 */
      String emptyNote) {}

  // ==================================================================
  // SPU 汇总
  // ==================================================================

  /**
   * SPU 汇总的一行。
   *
   * <p>🔴 **SPU 口径是跨平台经营的基本单位**（蓝图关系句）：一行 SPU = 一整个商品的整体表现，
   * 底下有多少 SKU、多少个平台、多少个 Listing 都不改变「这个商品卖了多少」这个数。
   */
  public record SpuSummaryRow(
      String spuId,
      String spuKey,
      String spuName,
      /** 名下的 SKU 数（变体数）。 */
      int skuCount,
      /** 名下出现过的平台（跨平台合并看的证据）。 */
      List<String> platformCodes,
      /** 名下出现过的 Listing 数。 */
      int listingCount,
      /** 名下出现过的币种。 */
      List<String> currencies,
      /** 指标取值（金额按币种分别列；件数与单数是跨币种合计）。 */
      List<ProductMetricValue> metrics,
      /** 名下的 SKU 明细（合并前的样子 —— 不合并就看不到「这个 SPU 拆开是什么样」）。 */
      List<SpuVariantSummary> variants) {}

  /** SPU 名下某个 SKU 的汇总。 */
  public record SpuVariantSummary(
      String skuId,
      String skuKey,
      String skuName,
      String variantSignature,
      /** 该 SKU 覆盖的平台（它挂在哪些 Listing 上）。 */
      List<String> platformCodes,
      /** 该 SKU 挂着的 Listing 数（>1 ⇒ 它的销量无法唯一归属到某个 Listing）。 */
      int listingCount,
      List<ProductMetricValue> metrics) {}

  /** SPU 汇总的完整结果。 */
  public record SpuSummaryResponse(
      LocalDate from,
      LocalDate to,
      List<SpuSummaryRow> rows,
      long total,
      int page,
      int size,
      List<ProductMetricDefinitionView> metricDefinitions,
      List<String> caliberNotes,
      List<String> timezones,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String status,
      String statusNote,
      @Schema(nullable = true) String emptyReason,
      String emptyNote) {}

  // ==================================================================
  // 变体销量分布
  // ==================================================================

  /** 变体分布里的一行 = 一个 SKU（一个变体）。 */
  public record VariantRow(
      String skuId,
      String skuKey,
      String skuName,
      String variantSignature,
      /** 变体销量（跨币种合计的件数）。 */
      BigDecimal quantity,
      /** 变体成交金额**按币种分别列出**（绝不跨币种相加）。 */
      List<CurrencyAmount> amounts,
      /** 变体订单数（跨币种合计）。 */
      BigDecimal orderCount,
      /** 该变体在所属 SPU 里的销量占比（百分数）。null = 算不出来（SPU 销量为 0）。 */
      @Schema(nullable = true) BigDecimal share,
      /** 占比算不出来的原因（share 为 null 时必填）。 */
      String shareNote,
      /** 该变体挂着的平台（跨平台合并看的证据）。 */
      List<String> platformCodes,
      /** 该变体挂着的 Listing 数（>1 ⇒ 销量无法唯一归属到某个 Listing）。 */
      int listingCount,
      /** 该变体的指标取值（与报表同源）。 */
      List<ProductMetricValue> metrics) {}

  /** 变体销量分布的完整结果。 */
  public record VariantDistributionResponse(
      LocalDate from,
      LocalDate to,
      /** 所属 SPU。 */
      String spuId,
      String spuKey,
      String spuName,
      /** 该 SPU 的总销量（跨币种合计的件数）—— 占比的分母。 */
      BigDecimal totalQuantity,
      /** 各变体的销量占比之和（正常是 100.0000；不等于 100 时说明有销量归不到任何变体）。 */
      BigDecimal shareTotal,
      List<VariantRow> variants,
      List<ProductMetricDefinitionView> metricDefinitions,
      List<String> caliberNotes,
      List<String> timezones,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String status,
      String statusNote,
      @Schema(nullable = true) String emptyReason,
      String emptyNote) {}

  // ==================================================================
  // 层级钻取
  // ==================================================================

  /** 一条构成明细。 */
  public record ProductDrilldownRow(
      /** 构成类型：ORDER 订单行。 */
      String sourceKind,
      /** 构成标识（订单号 + 行序号）。 */
      String referenceId,
      String referenceLabel,
      /** 构成所在的订单号。 */
      String orderNo,
      /** 🔴 下单时刻（**精确到瞬间**，不是本地时间）。 */
      @Schema(nullable = true) OffsetDateTime occurredAt,
      /** 构成所在的 SKU 码与名称。 */
      String skuCode,
      String skuName,
      /** 该明细的件数贡献。 */
      BigDecimal quantity,
      /** 该明细的金额贡献。 */
      BigDecimal value,
      String currency,
      /** 事实归属的日历日。 */
      LocalDate bucketDate,
      /** 分桶所用时区。 */
      String timezone) {}

  /** 层级钻取的完整结果（**真分页 + 真计数**）。 */
  public record ProductDrilldownResponse(
      LocalDate from,
      LocalDate to,
      String dimensionType,
      String dimensionId,
      String dimensionKey,
      String dimensionName,
      /** 钻取的是哪个指标（空 = 全部指标）。 */
      String metricCode,
      List<ProductDrilldownRow> rows,
      long total,
      int page,
      int size,
      /** 构成明细里出现过的 SKU（从 SKU 层往下钻时有用）。 */
      List<String> skuCodes,
      List<String> caliberNotes,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String status,
      String statusNote,
      @Schema(nullable = true) String emptyReason,
      String emptyNote) {}

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 能力声明。
   *
   * @param upstreamConnected 上游是否真接通（本模块的三个上游全部已交付）
   * @param reportAvailable 商品销量报表是否可用
   * @param dataUpdatedAtVisible 是否常驻显示数据更新时间
   * @param upstreamModules 本服务**真的读过**的上游模块
   * @param notDelivered **确实拿不到**的部分（每条都写清原因与影响）
   * @param rules 口径与判定规则（阈值写清楚，页面上照着显示）
   */
  public record ProductSalesCapabilities(
      boolean upstreamConnected,
      boolean reportAvailable,
      boolean dataUpdatedAtVisible,
      List<String> upstreamModules,
      List<String> notDelivered,
      List<ProductRuleNote> rules) {}

  /** 一条规则 / 口径说明。 */
  public record ProductRuleNote(String ruleCode, String description) {}

  // ==================================================================
  // 刷新
  // ==================================================================

  /** 一路刷新结果。 */
  public record ProductRefreshResult(
      String source,
      /** 拉到的上游行数。 */
      int fetched,
      /** 落库的事实行数。 */
      int projected,
      /** 结论：SUCCEEDED / FAILED / TRUNCATED。 */
      String status,
      /** 说明（失败原因 / 截断原因）。 */
      String note) {}
}
