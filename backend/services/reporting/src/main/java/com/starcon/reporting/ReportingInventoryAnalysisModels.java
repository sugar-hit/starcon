package com.starcon.reporting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 13.5.1.1 库存分析的**读模型契约**。
 *
 * <p>蓝图原文：「库存分析报表：库存金额结构、周转率、库龄分布、缺货与超储、ABC 分类、呆滞占比，
 * 按仓库/类目/店铺拆解。」输入「库存数据（7）；销售数据；盘点历史。」
 * 输出「库存分析报表 + 图表 + 下钻。」
 *
 * <p>⚠️ **三件事在这份契约里是硬约定**：
 *
 * <ol>
 *   <li><b>「算不出来」与「等于 0」分开</b>。凡是可以「算不出来」的数值一律用 {@code null}
 *       表示，<b>不用 0 冒充</b>：「周转率 0.00」与「这一格没算出来」在报表里长得一模一样，
 *       含义却完全相反 —— 而蓝图那句加粗要消灭的正是前者。</li>
 *   <li><b>周转率按成本口径算</b>。分子是**销售出库成本**，分母是**成本口径的库存金额**，
 *       契约里用 {@code caliber} / {@code caliberNote} 把这一点写在明面上。</li>
 *   <li><b>多币种绝不相加</b>。每一行带自己的币种；跨币种的合计不存在
 *       （{@code totalInventoryValue} 只在**恰好一个币种**时才有值）。</li>
 * </ol>
 */
public final class ReportingInventoryAnalysisModels {

  private ReportingInventoryAnalysisModels() {}

  // ================================================================ 词表

  /** 拆解维度。 */
  public enum Dimension {
    WAREHOUSE("仓库"),
    CATEGORY("类目"),
    SHOP("店铺"),
    SKU("SKU");

    private final String label;

    Dimension(String label) {
      this.label = label;
    }

    /** 中文名（页面上原样显示）。 */
    public String label() {
      return label;
    }
  }

  /** 期间粒度。 */
  public enum Granularity {
    DAY("日"),
    WEEK("周"),
    MONTH("月");

    private final String label;

    Granularity(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * ABC 分类。
   *
   * <p>⚠️ **ABC 的切分依据是「库存金额的累计占比」**（不是销量、不是 SKU 数）：
   * 库存分析要回答的是「哪些 SKU 占了大部分资金」，所以分类必须落在**金额**上。
   */
  public enum AbcClass {
    A("A 类", "金额占比最高的前一批，重点管控"),
    B("B 类", "金额占比居中，正常管控"),
    C("C 类", "金额占比最低的长尾，考虑精简");

    private final String label;
    private final String hint;

    AbcClass(String label, String hint) {
      this.label = label;
      this.hint = hint;
    }

    public String label() {
      return label;
    }

    public String hint() {
      return hint;
    }
  }

  // ================================================================ 库存金额结构

  /**
   * 库存金额结构的一行（按所选维度聚合）。
   *
   * @param inventoryValue 该维度的库存金额（**成本口径**）；null = 成本取不到，**不是 0**
   * @param valueShare 该维度占同币种合计的比重；null = 分母算不出来
   * @param comparable 这一行是否可比（false ⇒ 金额或占比缺一不可）
   */
  public record InventoryStructureRow(
      String dimension,
      String dimensionKey,
      String dimensionLabel,
      String currency,
      @Schema(nullable = true) BigDecimal inventoryValue,
      BigDecimal onHandQuantity,
      BigDecimal availableQuantity,
      long skuCount,
      @Schema(nullable = true) BigDecimal valueShare,
      boolean comparable,
      long zeroStockSkuCount,
      long overstockSkuCount,
      BigDecimal overstockQuantity,
      String note) {}

  /** 某个币种的合计（**绝不跨币种相加**）。 */
  public record InventoryCurrencyTotal(
      String currency,
      @Schema(nullable = true) BigDecimal inventoryValue,
      BigDecimal onHandQuantity,
      BigDecimal availableQuantity,
      long skuCount,
      long costMissingSkuCount,
      BigDecimal costMissingQuantity) {}

  /** 库存金额结构的完整响应。 */
  public record InventoryStructureResponse(
      LocalDate asOf,
      String dimension,
      List<InventoryStructureRow> rows,
      List<InventoryCurrencyTotal> totals,
      /** 合计金额：**仅当本次只有一个币种时**才有值，否则 null（跨币种的合计不存在）。 */
      @Schema(nullable = true) BigDecimal totalInventoryValue,
      /** 合计金额对应的币种（与 {@code totalInventoryValue} 同生共死）。 */
      @Schema(nullable = true) String totalCurrency,
      long costMissingSkuCount,
      BigDecimal costMissingQuantity,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String caliberNote,
      List<String> warnings) {}

  // ================================================================ 周转率

  /**
   * 周转率的一行。
   *
   * @param costOfGoodsSold 销售出库**成本**（成本口径）；null = 这一段算不出来
   * @param openingInventoryValue 期初库存金额（成本口径）；null = 没有期初快照
   * @param closingInventoryValue 期末库存金额（成本口径）；null = 没有期末快照
   * @param averageInventoryValue 平均库存金额 =（期初 + 期末）÷ 2；null = 任一端缺失
   * @param turnoverRate 周转率 = 销售出库成本 ÷ 平均库存金额；null = **分母算不出来**
   * @param turnoverDays 周转天数 = 期间天数 ÷ 周转率；null = 周转率算不出来
   * @param caliber 库存口径：AVERAGE（平均库存）/ CLOSING（只有期末，退而求其次）
   * @param costUncoveredSkuCount **没有维护成本**的 SKU 数：它们的库存与销量**两侧都不在**分子分母里
   *     （两侧口径一致 ⇒ 周转率本身仍然可比），这一列只是让人知道这份周转率覆盖了多大范围
   */
  public record InventoryTurnoverRow(
      String dimension,
      String dimensionKey,
      String dimensionLabel,
      String currency,
      @Schema(nullable = true) BigDecimal costOfGoodsSold,
      @Schema(nullable = true) BigDecimal openingInventoryValue,
      @Schema(nullable = true) BigDecimal closingInventoryValue,
      @Schema(nullable = true) BigDecimal averageInventoryValue,
      @Schema(nullable = true) BigDecimal turnoverRate,
      @Schema(nullable = true) BigDecimal turnoverDays,
      String caliber,
      long costUncoveredSkuCount,
      boolean comparable,
      String note) {}

  /** 周转率趋势上的一个点。 */
  public record InventoryTurnoverPoint(
      LocalDate periodStart,
      LocalDate periodEnd,
      String dimension,
      String dimensionKey,
      String dimensionLabel,
      String currency,
      @Schema(nullable = true) BigDecimal costOfGoodsSold,
      @Schema(nullable = true) BigDecimal openingInventoryValue,
      @Schema(nullable = true) BigDecimal closingInventoryValue,
      @Schema(nullable = true) BigDecimal averageInventoryValue,
      @Schema(nullable = true) BigDecimal turnoverRate,
      @Schema(nullable = true) BigDecimal turnoverDays,
      boolean comparable,
      String note) {}

  /** 周转率的完整响应。 */
  public record InventoryTurnoverResponse(
      LocalDate from,
      LocalDate to,
      String granularity,
      String dimension,
      @Schema(nullable = true) BigDecimal targetTurnover,
      List<InventoryTurnoverRow> rows,
      List<InventoryTurnoverPoint> trend,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String caliberNote,
      List<String> warnings) {}

  // ================================================================ 库龄与呆滞

  /** 库龄分桶的一行。 */
  public record InventoryAgingBucketRow(
      String bucket,
      String bucketLabel,
      @Schema(nullable = true) Integer lowerDays,
      @Schema(nullable = true) Integer upperDays,
      String scopeType,
      String scopeKey,
      String scopeLabel,
      long skuCount,
      long bucketCount,
      BigDecimal onHand,
      BigDecimal reserved,
      BigDecimal available,
      /** 该桶占可用量的比重；null = 分母算不出来 */
      @Schema(nullable = true) BigDecimal availableShare,
      boolean comparable) {}

  /** 呆滞汇总的一行。 */
  public record InventorySlowMovingRow(
      String scopeType,
      String scopeKey,
      String scopeLabel,
      String status,
      String statusLabel,
      long skuCount,
      long bucketCount,
      BigDecimal available,
      /** 呆滞量占可用量的比重；null = 分母算不出来 */
      @Schema(nullable = true) BigDecimal availableShare,
      boolean comparable,
      @Schema(nullable = true) Integer oldestAgingDays) {}

  /** 库龄与呆滞的完整响应。 */
  public record InventoryAgingResponse(
      LocalDate asOf,
      List<InventoryAgingBucketRow> buckets,
      List<InventorySlowMovingRow> slowMoving,
      BigDecimal totalAvailable,
      @Schema(nullable = true) BigDecimal obsoleteAndUpAvailable,
      /** 呆滞占比（呆滞及以上的可用量 ÷ 总可用量）；null = **分母算不出来** */
      @Schema(nullable = true) BigDecimal obsoleteRatio,
      boolean ratioComparable,
      String ratioNote,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String caliberNote,
      List<String> warnings) {}

  // ================================================================ 缺货与超储

  /**
   * 缺货与超储的一行（**一个维度**，不是「一个维度 × 一个币种」）。
   *
   * <p>⚠️ **计数与数量按维度，金额按币种**：缺货 / 超储的 SKU 数与件数是**与币种无关**的事实，
   * 把它按币种拆开会让「这个仓有几个 SKU 断货」这个问题答不出来；
   * 而金额**必须**按币种拆开（**绝不跨币种相加**）。
   * ⇒ 金额放进 {@link #amounts()} 这一层，一层一个币种。
   */
  public record InventoryStockStatusRow(
      String dimension,
      String dimensionKey,
      String dimensionLabel,
      long totalSkuCount,
      long zeroStockSkuCount,
      BigDecimal zeroStockAvailable,
      long overstockSkuCount,
      BigDecimal overstockQuantity,
      /** **未设阈值**的 SKU 数：0 与「没查过」含义不同，必须分开显示。 */
      long thresholdUnsetSkuCount,
      List<InventoryStockStatusAmount> amounts,
      String note) {}

  /**
   * 缺货 / 超储的**金额**（一层一个币种）。
   *
   * @param zeroStockValue 缺货行的金额；null = 该币种下**没有算得出来的金额**（不是 0）
   * @param totalValue 该币种下的库存金额合计；null = 算不出来
   */
  public record InventoryStockStatusAmount(
      String currency,
      @Schema(nullable = true) BigDecimal zeroStockValue,
      @Schema(nullable = true) BigDecimal overstockValue,
      @Schema(nullable = true) BigDecimal totalValue) {}

  /** 缺货与超储的完整响应。 */
  public record InventoryStockStatusResponse(
      LocalDate asOf,
      String dimension,
      List<InventoryStockStatusRow> rows,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String caliberNote,
      List<String> warnings) {}

  // ================================================================ ABC 分类

  /** ABC 分类的一行（一个 SKU × 一个币种）。 */
  public record InventoryAbcRow(
      String skuCode,
      String skuName,
      String currency,
      String categoryCode,
      String warehouseCode,
      String shopCode,
      @Schema(nullable = true) BigDecimal inventoryValue,
      @Schema(nullable = true) BigDecimal valueShare,
      @Schema(nullable = true) BigDecimal cumulativeShare,
      BigDecimal onHandQuantity,
      BigDecimal availableQuantity,
      String abcClass,
      String abcClassLabel,
      boolean comparable,
      String note) {}

  /** ABC 分类的分组汇总。 */
  public record InventoryAbcSummary(
      String currency,
      String abcClass,
      String abcClassLabel,
      long skuCount,
      @Schema(nullable = true) BigDecimal inventoryValue,
      @Schema(nullable = true) BigDecimal valueShare,
      boolean comparable) {}

  /** ABC 分类的完整响应。 */
  public record InventoryAbcResponse(
      LocalDate asOf,
      @Schema(nullable = true) BigDecimal aCumPct,
      @Schema(nullable = true) BigDecimal bCumPct,
      List<InventoryAbcRow> rows,
      List<InventoryAbcSummary> summary,
      long total,
      int page,
      int size,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String caliberNote,
      List<String> warnings) {}

  // ================================================================ 下钻

  /** 下钻的一行（一个 SKU × 一个仓库 × 一个币种）。 */
  public record InventoryDrilldownRow(
      String skuCode,
      String skuName,
      String currency,
      String categoryCode,
      String warehouseCode,
      String warehouseName,
      String shopCode,
      String shopName,
      BigDecimal onHandQuantity,
      BigDecimal reservedQuantity,
      BigDecimal availableQuantity,
      @Schema(nullable = true) BigDecimal unitCost,
      @Schema(nullable = true) BigDecimal inventoryValue,
      BigDecimal soldQuantity,
      @Schema(nullable = true) BigDecimal costOfGoodsSold,
      boolean zeroStock,
      boolean overstock,
      boolean thresholdConfigured,
      String abcClass,
      String abcClassLabel,
      boolean comparable,
      String note) {}

  /** 下钻的完整响应。 */
  public record InventoryDrilldownResponse(
      LocalDate asOf,
      LocalDate from,
      LocalDate to,
      List<InventoryDrilldownRow> rows,
      long total,
      int page,
      int size,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String caliberNote,
      List<String> warnings) {}

  // ================================================================ 盘点历史

  /** 一次盘点单。 */
  public record InventoryStocktakeRow(
      UUID stocktakeId,
      String code,
      String scopeType,
      String scopeLabel,
      String status,
      @Schema(nullable = true) OffsetDateTime frozenAt,
      long lineCount,
      long countedLineCount,
      long varianceLineCount,
      BigDecimal bookTotal,
      /** 已盘行数 ÷ 账面行数；null = 账面行数为 0（**不可比**，不是 0%） */
      @Schema(nullable = true) BigDecimal countedRate,
      /** 差异行数 ÷ 账面行数；null = 账面行数为 0 */
      @Schema(nullable = true) BigDecimal varianceRate,
      boolean comparable) {}

  /** 盘点历史汇总。 */
  public record InventoryStocktakeSummary(
      long sheetCount,
      long lineCount,
      long countedLineCount,
      long varianceLineCount,
      @Schema(nullable = true) BigDecimal countedRate,
      @Schema(nullable = true) BigDecimal varianceRate,
      boolean comparable) {}

  /** 盘点历史的完整响应。 */
  public record InventoryStocktakeResponse(
      List<InventoryStocktakeRow> rows,
      InventoryStocktakeSummary summary,
      long total,
      int page,
      int size,
      @Schema(nullable = true) OffsetDateTime dataUpdatedAt,
      String dataUpdatedNote,
      String caliberNote,
      List<String> warnings) {}

  // ================================================================ 阈值规则

  /** 阈值规则（租户自己的口径）。 */
  public record InventoryAnalysisRule(
      String ruleType,
      @Schema(nullable = true) BigDecimal aCumPct,
      @Schema(nullable = true) BigDecimal bCumPct,
      @Schema(nullable = true) BigDecimal targetTurnover,
      int minHistoryDays,
      boolean enabled,
      @Schema(nullable = true) OffsetDateTime updatedAt) {}

  /** 改阈值的入参（**全部可选**：只改写进来的那几个字段）。 */
  public record InventoryAnalysisRuleInput(
      @Schema(nullable = true) BigDecimal aCumPct,
      @Schema(nullable = true) BigDecimal bCumPct,
      @Schema(nullable = true) BigDecimal targetTurnover,
      @Schema(nullable = true) Integer minHistoryDays,
      @Schema(nullable = true) Boolean enabled) {}

  // ================================================================ 能力声明

  /**
   * 能力声明。
   *
   * @param upstreamConnected 本模块的上游是否真接通
   * @param metrics 本模块覆盖的指标
   * @param dimensions 可用的拆解维度
   * @param upstreamModules 本服务**真的读过**的上游模块
   * @param notDelivered **确实拿不到**的部分（每条写清原因与影响）
   * @param costCaliber 成本口径的逐字说明
   * @param turnoverCaliber 周转率口径的逐字说明
   * @param abcCaliber ABC 分类口径的逐字说明
   */
  public record InventoryAnalysisCapabilities(
      boolean upstreamConnected,
      List<String> metrics,
      List<String> dimensions,
      List<String> upstreamModules,
      List<String> notDelivered,
      String costCaliber,
      String turnoverCaliber,
      String abcCaliber,
      String note) {}

  // ================================================================ 刷新

  /** 一次刷新里某一路上游的结果。 */
  public record InventoryAnalysisRefreshSummary(
      String source,
      int factCount,
      int written,
      String status,
      String note) {}

  /** 一次刷新的完整结果。 */
  public record InventoryAnalysisRefreshResult(
      List<InventoryAnalysisRefreshSummary> sources,
      int totalWritten,
      boolean truncated,
      String note) {}
}
