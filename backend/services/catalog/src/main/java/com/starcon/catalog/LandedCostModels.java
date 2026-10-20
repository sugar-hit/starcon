package com.starcon.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 5.2.1.2 到岸成本：「货到目标仓可售」的总成本 = 采购价 + 国际头程 + 关税 + 清关 + 目的地操作费 + 尾程预备。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 *
 * **职责**：计算"货到目标仓可售"的总成本 = 采购价 + 国际头程 + 关税 + 清关 + 目的地操作费 + 尾程预备，用于海外仓/FBA 定价。
 *
 * **输入**：批次/货件的采购明细；头程费用（10 物流与头程）；税率与清关费参数（16 合规）。
 *
 * **输出**：按 SKU/批次的到岸单位成本；不同目的地的成本对比。
 *
 * **关系**：上游 = 6 采购、10 头程、16 税务参数；下游 = 5.3.1.1。**到岸成本与仓库成本是两个口径**（海外仓 vs 自发货），定价时必须选对，否则自发货商品按海运成本定价会低估。
 *
 * </blockquote>
 *
 * <h2>🔴 本模块唯一不可让步的一条：口径必须写在脸上</h2>
 *
 * <p>蓝图加粗句「到岸成本与仓库成本是两个口径」要求：**到岸成本不含仓储费与尾程派送费**，
 * 它只到「货到目标仓可售」为止。这条在三个地方同时落地：
 *
 * <ol>
 *   <li><b>表结构</b>：{@code landed_cost_statement.cost_basis} 是闭集
 *       （只有 {@code LANDED_TO_TARGET_WAREHOUSE} 一个取值），
 *       {@code excluded_components} 固定写明不算的 STORAGE_FEE 与 LAST_MILE_DELIVERY_FEE。
 *   <li><b>端点响应</b>：{@link LandedCostBasis} 随每一份响应返回，
 *       写明<b>算了哪六项</b>与<b>明确不算哪两项</b>。
 *   <li><b>每一行分量</b>：{@link LandedCostComponentRow} 逐项带
 *       {@code available} / {@code included} / {@code ownerModule} / {@code impact}，
 *       缺口**不显示 0**。
 * </ol>
 *
 * <p><b>🔴 「尾程预备」与「尾程派送费」不是一回事</b>：前者是本模块的第六个分量
 * （定价用的**预留**），后者是已发生的运费，归 10.2 运单 / 10.6.1.1 成本分析。
 * 两者混为一谈，就等于把仓库成本偷偷塞进到岸成本 —— 正是加粗句要防的事。
 *
 * <h2>🔴 六个分量逐项落成可审阅的事实</h2>
 *
 * <p>每个分量都带 {@code sourceModule} 与 {@code sourceDocumentCode}（**来源单号**），
 * 拿不到的标 {@code available=false} + {@code ownerModule} + {@code impact}。
 * <b>用 0 填关税会让到岸成本系统性低估</b>，而页面上看不出任何异常 ——
 * 这是本模块最危险的失败模式，所以它由 DB 层的 CHECK 钉死（{@code amount IS NULL}）。
 */
public final class LandedCostModels {

  private LandedCostModels() {}

  // ================================================================ 词表

  /**
   * 六个到岸成本分量。
   *
   * <p><b>闭集，只增不改</b>：分量会进历史快照（{@code landed_cost_component}），
   * 改语义会让历史到岸成本读起来是错的。
   */
  public enum LandedCostComponent {
    /** 采购价。来源：6 采购的实际单价（本仓改用 catalog 同库的实际到货金额）。 */
    PURCHASE_PRICE("采购价", "FACT", "6 采购（实际到货金额）"),
    /** 国际头程：运费 + 保险费。来源：warehouse 的头程发运单。 */
    INTERNATIONAL_FREIGHT("国际头程", "FACT", "10 头程（warehouse 头程发运单）"),
    /** 关税（+ 增值税/GST，**分列**）。来源：warehouse 的关税估算（税率表由租户维护）。 */
    DUTY("关税", "FACT", "10.5.1.3 关税估算（税率表 16 合规未交付，由租户维护）"),
    /** 清关。**参数**：全仓没有任何清关费事实。 */
    CUSTOMS_CLEARANCE("清关", "PARAMETER", "16 合规（清关费参数，租户维护）"),
    /** 目的地操作费。来源：warehouse 头程发运单上登记的操作费。 */
    DESTINATION_HANDLING("目的地操作费", "FACT", "10 头程（warehouse 头程发运单）"),
    /** 尾程预备。**参数**：定价用的预留，不是尾程派送费实际值。 */
    LAST_MILE_RESERVE("尾程预备", "PARAMETER", "05 定价与价目（本服务参数）");

    private final String label;
    private final String kind;
    private final String source;

    LandedCostComponent(String label, String kind, String source) {
      this.label = label;
      this.kind = kind;
      this.source = source;
    }

    public String label() {
      return label;
    }

    /** FACT = 上游事实；PARAMETER = 租户维护的参数。两者在页面上**分色**显示。 */
    public String kind() {
      return kind;
    }

    public String source() {
      return source;
    }

    public static LandedCostComponent parse(String raw) {
      if (raw == null) return null;
      for (var c : values()) {
        if (c.name().equalsIgnoreCase(raw.trim())) return c;
      }
      return null;
    }
  }

  /**
   * 成本口径。
   *
   * <p>🔴 这是本模块的生死线所在：{@code includedComponents} 与 {@code excludedComponents}
   * 必须与实际计算逐字一致，页面上照着显示，调用方才知道「这个数字里有什么、没什么」。
   */
  public record LandedCostBasis(
      String code,
      String label,
      List<String> includedComponents,
      List<String> excludedComponents,
      String note) {}

  // ================================================================ 能力声明

  /** 能力声明里的一个分量。 */
  public record LandedCostComponentCapability(
      String code,
      String label,
      String source,
      String kind,
      boolean available,
      String ownerModule,
      String impact,
      String basis,
      String note) {}

  /**
   * 能力声明里的一个**未交付 / 取不到**的依赖。
   *
   * <p>与「分量」分开：分量说的是「这一项算不算」，这里说的是「算它要依赖的东西有没有交付」。
   */
  public record LandedCostGap(
      String moduleId, String moduleName, String ownerModule, String impact, String note) {}

  /** 能力声明。页面顶部固定显示它。 */
  public record LandedCostCapabilities(
      LandedCostBasis basis,
      List<LandedCostComponentCapability> components,
      List<LandedCostGap> gaps,
      List<String> allocationMethods,
      String upstreamNote,
      String downstreamNote,
      String sourceNote,
      String actionsNote) {}

  // ================================================================ 列表

  /** 列表查询条件。 */
  public record LandedCostQuery(
      String keyword,
      String destinationCountryCode,
      String currency,
      String status,
      @Schema(nullable = true) OffsetDateTime computedFrom,
      @Schema(nullable = true) OffsetDateTime computedTo,
      int page,
      int size) {}

  /**
   * 列表一行 = 一张到岸成本单（一个批次）。
   *
   * <p>覆盖度（算了几项、缺了哪几项）与合计**同时**出现 ——
   * 一个不说清覆盖范围的到岸成本是不能用来算利润的。
   */
  public record LandedCostStatementRow(
      UUID id,
      String statementCode,
      UUID sourceShipmentId,
      String sourceShipmentCode,
      String destinationCountryCode,
      String destinationName,
      String transportMode,
      String incoterm,
      String riskBearer,
      String currency,
      String costBasis,
      String allocationMethod,
      String status,
      @Schema(nullable = true) BigDecimal totalAmount,
      BigDecimal totalQuantity,
      int totalLineCount,
      int includedComponentCount,
      int missingComponentCount,
      List<String> missingComponents,
      OffsetDateTime computedAt,
      int computeCount) {}

  /** 列表页。 */
  public record LandedCostListPage(
      List<LandedCostStatementRow> items,
      long total,
      int page,
      int size,
      String basisNote,
      String note) {}

  // ================================================================ 明细

  /**
   * 一个分量。
   *
   * @param available 这一项**取得到**吗。false ⇒ {@code amount} 必为 null，
   *     {@code ownerModule} 说归谁管、{@code impact} 说缺了会怎样。
   *     <b>false ≠ 0</b>：用 0 填关税会让到岸成本系统性低估。
   * @param included 这一项**计入合计**吗。available 但 included=false 是
   *     「有事实但按口径不计入」（例如 DAP 下关税由买方承担、采购价币种不一致未折算），
   *     此时 {@code note} 必须写清原因。
   * @param amount 计入本行合计的金额；取不到时为 null
   * @param unitAmount 每基础单位的金额；取不到时为 null
   * @param taxDutyAmount 关税部分（仅 DUTY 分量）；增值税/GST 在 {@code taxOtherAmount}
   */
  public record LandedCostComponentRow(
      String code,
      String label,
      String sourceKind,
      String sourceModule,
      String sourceDocumentCode,
      boolean available,
      boolean included,
      @Schema(nullable = true) BigDecimal amount,
      @Schema(nullable = true) BigDecimal unitAmount,
      @Schema(nullable = true) BigDecimal taxDutyAmount,
      @Schema(nullable = true) BigDecimal taxOtherAmount,
      String taxBearer,
      String ownerModule,
      String impact,
      String note) {}

  /** 一个 SKU 的到岸成本行。 */
  public record LandedCostLineRow(
      UUID id,
      int lineNo,
      UUID skuId,
      String skuCode,
      String skuName,
      String baseUnit,
      BigDecimal quantity,
      BigDecimal baseQuantity,
      @Schema(nullable = true) BigDecimal totalAmount,
      @Schema(nullable = true) BigDecimal unitLandedCost,
      int includedComponentCount,
      int missingComponentCount,
      List<String> missingComponents,
      List<LandedCostComponentRow> components) {}

  /** 到岸成本单明细。 */
  public record LandedCostDetail(
      UUID id,
      String statementCode,
      UUID sourceShipmentId,
      String sourceShipmentCode,
      String destinationCountryCode,
      String destinationName,
      String transportMode,
      String incoterm,
      String riskBearer,
      String currency,
      String costBasis,
      String allocationMethod,
      String status,
      @Schema(nullable = true) BigDecimal totalAmount,
      BigDecimal totalQuantity,
      int totalLineCount,
      int includedComponentCount,
      int missingComponentCount,
      List<String> missingComponents,
      OffsetDateTime computedAt,
      int computeCount,
      LandedCostBasis basis,
      List<LandedCostLineRow> lines,
      String note) {}

  // ================================================================ 目的地对比

  /** 目的地对比的一行 = 一个目的地。 */
  public record LandedCostComparisonRow(
      String destinationCountryCode,
      String destinationName,
      String currency,
      int statementCount,
      int completeStatementCount,
      @Schema(nullable = true) BigDecimal avgUnitLandedCost,
      @Schema(nullable = true) BigDecimal minUnitLandedCost,
      @Schema(nullable = true) BigDecimal maxUnitLandedCost,
      List<String> missingComponents) {}

  /**
   * 目的地对比。
   *
   * <p>🔴 **跨币种绝不相加**：不同目的地的币种不同的行不参与同一个平均值，
   * 页面上必须分开显示。
   */
  public record LandedCostComparison(
      UUID skuId,
      String skuCode,
      String skuName,
      String baseUnit,
      List<LandedCostComparisonRow> rows,
      String note) {}

  // ================================================================ 参数

  /** 租户维护的单件参数（清关费 / 尾程预备）。 */
  public record LandedCostParameterRow(
      UUID id,
      String componentCode,
      String componentLabel,
      String destinationCountryCode,
      String currency,
      BigDecimal unitAmount,
      String note,
      long version,
      OffsetDateTime updatedAt) {}

  /** 参数页。 */
  public record LandedCostParameterPage(
      List<LandedCostParameterRow> items, String basisNote, String note) {}

  /** 保存参数的入参。 */
  public record LandedCostParameterInput(
      @NotBlank
          @Pattern(
              regexp = "CUSTOMS_CLEARANCE|LAST_MILE_RESERVE",
              message = "只有清关费与尾程预备是参数；其余四项是上游事实，不许人工填")
          String componentCode,
      @NotBlank @Pattern(regexp = "[A-Za-z]{2}", message = "目的国必须是两位国家码") String destinationCountryCode,
      @NotBlank @Pattern(regexp = "[A-Za-z]{3}", message = "币种必须是三位 ISO 4217 码") String currency,
      @NotNull(message = "单件金额必填") BigDecimal unitAmount,
      @Size(max = 500) String note,
      long version) {}

  // ================================================================ 计算

  /** 计算到岸成本单的入参：按上游发运单定位（id 或单号，二选一）。 */
  public record LandedCostComputeInput(
      @Schema(nullable = true) UUID sourceShipmentId,
      @Schema(nullable = true) @Size(max = 64) String sourceShipmentCode) {}

  /** 计算结果。 */
  public record LandedCostComputed(
      UUID statementId,
      String statementCode,
      boolean created,
      int computeCount,
      int lineCount,
      @Schema(nullable = true) BigDecimal totalAmount,
      String currency,
      int includedComponentCount,
      int missingComponentCount,
      List<String> missingComponents,
      String note) {}

  /** 分量页（逐项可审阅：每一项都带来源单号）。 */
  public record LandedCostComponentPage(
      List<LandedCostComponentRow> items, long total, int page, int size, String note) {}
}
