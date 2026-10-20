package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 10.6.1.1 成本分析 的传输模型。
 *
 * <p>蓝图：「分析物流成本：单位重量/单均运费、头程分摊后到岸成本占比、各渠道成本对比、
 * 超成本订单识别、头程费用分摊明细。输入：实际运费与头程费用；包裹与发运记录。
 * 输出：成本报表 + 分摊明细 + 异常订单清单。」并加粗
 * 「<b>物流成本常被低估</b>：真实成本 = 尾程 + 头程分摊 + 包材 + 关税 + 仓储费，
 * 报表必须全口径」。
 *
 * <p>载荷一律用具名 {@code record} 而不是 {@code Map<String,Object>}：Map 的值类型会被
 * springdoc 推成 object，OpenAPI codegen 出来是空对象 {@code {}}（9.6.1.3 已踩过）。
 *
 * <p>🔴 <b>null 与 0 在本模块里是两种东西</b>，全模型都遵守：
 *
 * <ul>
 *   <li>{@code null} = <b>算不出来</b>（分母是 0 / 这一位没有数据源）。界面上显示
 *       「暂无数据」+ 中性配色。
 *   <li>{@code 0} = <b>真的就是这个数</b>（这票货的运费确实是 0）。
 * </ul>
 *
 * <p>把「算不出来」画成 0，是本模块最容易犯、也最贵的错：成本报表上的 0 会被当成
 * 「这项成本不要钱」，而真相是「没人记这笔账」—— 于是利润被高估，且没人查得出来。
 *
 * <p>🔴 <b>跨币种金额逐币种展示，绝不相加</b>：所有金额都带币种，
 * 合计只在<b>同一币种内</b>成立（与 10.2.1.1 运单运费汇总同口径）。
 */
public final class LogisticsCostAnalysisModels {
  private LogisticsCostAnalysisModels() {}

  // ================================================================ 枚举

  /**
   * 成本分量。**闭集五位**，与蓝图加粗句里的五个加数逐字对应。
   *
   * <p>做成枚举而不是自由字符串：成本分量的清单是**口径**的一部分，
   * 少一位就是「报表漏算了一块成本」，而多一位则是「凭空多算了一块」。
   */
  enum CostComponent {
    LAST_MILE_FREIGHT("尾程运费"),
    FIRST_MILE_ALLOCATION("头程分摊"),
    PACKAGING("包材"),
    DUTY_AND_TAX("关税与增值税"),
    STORAGE_FEE("仓储费");

    public final String displayName;

    CostComponent(String displayName) {
      this.displayName = displayName;
    }
  }

  /**
   * 成本分量的**来源类别**。
   *
   * <p>🔴 事实（{@code FACT}）与参数（{@code PARAMETER}）在界面上**必须分色**：
   * 把「租户自己填的每票包材成本」报成「事实」，比不报包材这一位更危险 ——
   * 前者会让下游（5 定价 / 13 利润）以为这个数有账单支撑。
   */
  enum CostSourceKind {
    FACT("事实"),
    PARAMETER("参数");

    public final String displayName;

    CostSourceKind(String displayName) {
      this.displayName = displayName;
    }
  }

  /** 头程分摊基准。与 V213 的 {@code logistics_cost_allocation_rule.allocation_basis} 同词表。 */
  enum CostAllocationBasis {
    BY_QUANTITY("按发出数量"),
    BY_WEIGHT("按毛重"),
    BY_DECLARED_VALUE("按申报价值"),
    EQUAL("均摊");

    public final String displayName;

    CostAllocationBasis(String displayName) {
      this.displayName = displayName;
    }
  }

  /** 分摊单状态。 */
  enum CostAllocationStatus {
    COMPUTED("已计算"),
    LOCKED("已结账"),
    SUPERSEDED("已被新规则版本取代");

    public final String displayName;

    CostAllocationStatus(String displayName) {
      this.displayName = displayName;
    }
  }

  // ================================================================ 能力声明

  /**
   * 一个能力维度。
   *
   * @param available 现在能不能从**已交付的上游**真的取到这个能力
   * @param ownerModule 不可得时点名的归属模块
   * @param unavailableReason 不可得的**具体原因**（不是一句「暂无数据」）
   */
  public record CostCapabilityDimension(
      String dimension,
      String description,
      boolean available,
      String source,
      String ownerModule,
      String unavailableReason,
      String impact) {}

  /**
   * 一个成本分量的可得性与口径。
   *
   * <p>这是「全口径」在界面上最直接的落点：<b>五个分量都在</b>，
   * 可得的说清来源与口径，不可得的说清归属模块与影响 ——
   * 不可得的那一位<b>不显示 0</b>，显示「暂无数据」。
   */
  public record CostComponentCapability(
      String component,
      String displayName,
      boolean available,
      String sourceKind,
      String source,
      String basis,
      String ownerModule,
      String unavailableReason,
      String impact) {}

  /**
   * 能力声明。
   *
   * @param basisNote 成本口径总述：分摊用哪种规则、成本按什么时点结
   * @param financeNote 12 财务未交付的如实说明
   */
  public record CostCapabilities(
      List<CostComponentCapability> components,
      List<CostCapabilityDimension> dimensions,
      String basisNote,
      String financeNote,
      List<String> notes) {}

  // ================================================================ 成本参数

  /**
   * 一条成本参数（租户维护的**参数**口径）。
   *
   * <p>目前只有包材走参数口径：{@code amountPerWaybill} 是「每票包材成本」，
   * 报表里包材金额 = 本参数 × 本窗该币种的运单票数。
   */
  public record LogisticsCostParameterRow(
      UUID id,
      String component,
      String currency,
      BigDecimal amountPerWaybill,
      String unit,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 成本参数清单（真分页 + 全量汇总）。 */
  public record LogisticsCostParameterPage(
      List<LogisticsCostParameterRow> items,
      int page,
      int size,
      long totalElements,
      long totalPages,
      List<String> notes) {}

  /**
   * 维护一条成本参数。
   *
   * @param component 目前只接受 PACKAGING
   * @param amountPerWaybill 每票包材成本（金额非负）
   */
  public record LogisticsCostParameterRequest(
      @NotBlank @Size(max = 24) String component,
      @NotBlank @Size(max = 3) String currency,
      @NotNull @DecimalMin("0.0") BigDecimal amountPerWaybill,
      @Size(max = 500) String note) {}

  // ================================================================ 报表

  /** 逐币种的金额合计。<b>跨币种绝不相加</b>。 */
  public record CostCurrencyTotal(String currency, BigDecimal amount, long count) {}

  /**
   * 一个成本分量在本次报表里的取数结果。
   *
   * @param byCurrency 逐币种的**实付/已定案**金额 —— 只有这一列进合计
   * @param estimatedByCurrency 逐币种的**估算**金额 —— <b>不进合计</b>。
   *     估算不是真实成本：把它加进合计等于报出一个「看起来全口径、其实掺了预估值」的数。
   *     真实成本被低估要治，但**不能靠把估值当实付来治**。
   * @param available=false 时两个列表恒为空（不是 0 列表）
   */
  public record CostComponentAmount(
      String component,
      String displayName,
      boolean available,
      String sourceKind,
      String source,
      String basis,
      String ownerModule,
      String unavailableReason,
      String impact,
      List<CostCurrencyTotal> byCurrency,
      List<CostCurrencyTotal> estimatedByCurrency,
      int currencyCount) {}

  /**
   * 逐币种的一行合计。
   *
   * <p>某一格为 {@code null} = **这个分量在这个币种上没有数**（不是 0）。
   * {@code subtotal} 只把<b>非 null</b>的那几项加起来，并列出
   * {@code includedComponents} / {@code missingComponents} ——
   * 合计永远带着「我算了谁、没算谁」一起出现。
   *
   * @param packaging 包材（**参数**口径：每票包材成本 × 本窗票数）
   * @param lastMileShare 尾程占本币种合计的比重；{@code subtotal} 为 0 时是 {@code null}
   * @param firstMileShare 头程分摊占本币种合计的比重（= 到岸成本里头程那一块的占比）
   * @param dutyAndTaxShare 关税占本币种合计的比重
   * @param packagingShare 包材占本币种合计的比重
   */
  public record CostCurrencyRow(
      String currency,
      @Schema(nullable = true) BigDecimal lastMileFreight,
      @Schema(nullable = true) BigDecimal firstMileAllocation,
      @Schema(nullable = true) BigDecimal dutyAndTax,
      @Schema(nullable = true) BigDecimal packaging,
      @Schema(nullable = true) BigDecimal subtotal,
      @Schema(nullable = true) BigDecimal lastMileShare,
      @Schema(nullable = true) BigDecimal firstMileShare,
      @Schema(nullable = true) BigDecimal dutyAndTaxShare,
      @Schema(nullable = true) BigDecimal packagingShare,
      List<String> includedComponents,
      List<String> missingComponents) {}

  /**
   * 单位成本指标（逐币种）。
   *
   * @param totalFreightAmount 该币种**已登记**的运费合计；一票都没登记时是 {@code null}
   *     （**不是 0** —— 「没人登记」与「登记为 0」是两件事）
   * @param perOrderFreight 单均运费；分母（已登记运费的票数）为 0 时是 {@code null}
   * @param perKgFreight 单位重量运费；分母（这些票的总毛重）为 0 时是 {@code null}
   */
  public record CostUnitMetric(
      String currency,
      @Schema(nullable = true) BigDecimal totalFreightAmount,
      long registeredWaybills,
      long notRegisteredWaybills,
      @Schema(nullable = true) BigDecimal totalWeightKg,
      @Schema(nullable = true) BigDecimal perOrderFreight,
      @Schema(nullable = true) BigDecimal perKgFreight) {}

  /**
   * 覆盖说明。
   *
   * @param complete 五个分量是否都取到了数
   * @param missingComponents 没有取到数的分量（<b>不等于 0</b>）
   */
  public record CostCoverage(
      boolean complete, List<String> missingComponents, String note, List<String> notes) {}

  /**
   * 成本总览（成本报表）。
   *
   * @param scannedWaybills 本次整窗扫描到的尾程运单票数
   * @param truncated 是否撞到扫描上限而没扫完（撞到了就不能把统计说成全量）
   */
  public record CostOverview(
      OffsetDateTime computedAt,
      OffsetDateTime windowFrom,
      OffsetDateTime windowTo,
      List<CostComponentAmount> components,
      List<CostCurrencyRow> currencyRows,
      List<CostUnitMetric> unitMetrics,
      CostCoverage coverage,
      long scannedWaybills,
      boolean truncated,
      List<String> notes) {}

  // ================================================================ 渠道对比

  /**
   * 一个渠道的成本指标（逐币种）。
   *
   * @param perOrderFreight 单均运费；分母为 0 时是 {@code null}
   * @param perKgFreight 单位重量运费；分母为 0 时是 {@code null}
   */
  public record CostChannelCurrencyMetric(
      String currency,
      BigDecimal amount,
      long waybills,
      @Schema(nullable = true) BigDecimal totalWeightKg,
      @Schema(nullable = true) BigDecimal perOrderFreight,
      @Schema(nullable = true) BigDecimal perKgFreight) {}

  /** 一个承运渠道的成本对比行。 */
  public record CostChannelRow(
      String carrierCode,
      String carrierChannel,
      String carrierChannelLabel,
      long waybills,
      long freightRegistered,
      long freightNotRegistered,
      List<CostChannelCurrencyMetric> byCurrency) {}

  /** 渠道对比的全量汇总（**不受分页影响**）。 */
  public record CostChannelSummary(
      long carrierCount, long waybills, long freightRegistered, long freightNotRegistered) {}

  /** 渠道成本对比。 */
  public record CostChannelComparison(
      OffsetDateTime computedAt,
      OffsetDateTime windowFrom,
      OffsetDateTime windowTo,
      List<CostChannelRow> rows,
      CostChannelSummary summary,
      long scannedWaybills,
      boolean truncated,
      List<String> notes) {}

  // ================================================================ 超成本订单

  /**
   * 一个币种的超成本阈值。
   *
   * @param averageUnitFreight 该币种的平均单位重量运费；<b>没有可评级的票时是 null</b>
   * @param thresholdUnitFreight 超成本线；平均值为 null 时也是 null
   */
  public record CostCurrencyThreshold(
      String currency,
      @Schema(nullable = true) BigDecimal averageUnitFreight,
      @Schema(nullable = true) BigDecimal thresholdUnitFreight,
      long evaluatedWaybills,
      long overCostWaybills) {}

  /** 一张超成本运单。 */
  public record CostOverCostOrderRow(
      UUID id,
      String waybillNo,
      String salesOrderNo,
      String outboundOrderNo,
      String carrierCode,
      String carrierChannel,
      String destinationCountry,
      String currency,
      BigDecimal freightAmount,
      @Schema(nullable = true) BigDecimal grossWeightKg,
      @Schema(nullable = true) BigDecimal unitFreight,
      @Schema(nullable = true) BigDecimal averageUnitFreight,
      @Schema(nullable = true) BigDecimal thresholdUnitFreight,
      @Schema(nullable = true) BigDecimal excessAmount,
      String excessReason,
      OffsetDateTime createdAt) {}

  /** 超成本订单清单（真分页）。 */
  public record CostOverCostOrderPage(
      OffsetDateTime computedAt,
      OffsetDateTime windowFrom,
      OffsetDateTime windowTo,
      BigDecimal thresholdMultiplier,
      List<CostCurrencyThreshold> thresholds,
      List<CostOverCostOrderRow> items,
      int page,
      int size,
      long totalElements,
      long totalPages,
      List<String> notes) {}

  // ================================================================ 分摊规则

  /** 一条分摊规则版本。 */
  public record CostAllocationRuleRow(
      UUID id,
      String ruleCode,
      String ruleName,
      int version,
      String allocationBasis,
      String allocationBasisLabel,
      String status,
      String statusLabel,
      OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      String note,
      long allocationCount,
      OffsetDateTime createdAt) {}

  /** 分摊规则版本列表。 */
  public record CostAllocationRulePage(
      List<CostAllocationRuleRow> items, int page, int size, long totalElements, long totalPages) {}

  /**
   * 新建一个规则版本。<b>只新建版本，不改既有版本</b>（蓝图 5.2.1.3）。
   *
   * @param activate 是否让这个版本成为现行版本（true 时同 rule_code 的旧现行版本转为 RETIRED，
   *     **旧版本行仍留档**，历史分摊单继续指向它当时用的那个版本）
   */
  public record CostAllocationRuleRequest(
      @NotBlank @Size(max = 32) String ruleCode,
      @NotBlank @Size(max = 120) String ruleName,
      @NotNull CostAllocationBasis allocationBasis,
      @NotNull OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      @Size(max = 500) String note,
      boolean activate) {}

  // ================================================================ 分摊单

  /** 一张分摊单。 */
  public record CostAllocationRow(
      UUID id,
      String allocationCode,
      UUID ruleId,
      String ruleCode,
      int ruleVersion,
      String allocationBasis,
      String allocationBasisLabel,
      UUID sourceShipmentId,
      String sourceShipmentCode,
      String sourceTransportMode,
      String sourceCarrierName,
      String destinationCountryCode,
      String currency,
      BigDecimal sourceTotalAmount,
      BigDecimal allocatedTotalAmount,
      BigDecimal basisTotal,
      int lineCount,
      String status,
      String statusLabel,
      OffsetDateTime computedAt,
      OffsetDateTime costAsOf,
      @Schema(nullable = true) OffsetDateTime lockedAt,
      @Schema(nullable = true) OffsetDateTime supersededAt,
      @Schema(nullable = true) Integer supersededByRuleVersion,
      String note,
      OffsetDateTime createdAt) {}

  /** 一行分摊明细。 */
  public record CostAllocationLineRow(
      UUID id,
      int lineNo,
      @Schema(nullable = true) UUID skuId,
      String skuCode,
      String skuName,
      String hsCode,
      BigDecimal basisValue,
      String basisUnit,
      @Schema(nullable = true) BigDecimal ratio,
      BigDecimal allocatedAmount,
      String currency) {}

  /** 分摊单详情：头 + 明细。 */
  public record CostAllocationDetail(CostAllocationRow allocation, List<CostAllocationLineRow> lines) {}

  /** 分摊单列表（真分页 + 全量汇总）。 */
  public record CostAllocationPage(
      List<CostAllocationRow> items,
      int page,
      int size,
      long totalElements,
      long totalPages,
      long computedCount,
      long lockedCount,
      long supersededCount,
      List<CostCurrencyTotal> allocatedByCurrency,
      List<String> notes) {}

  /**
   * 对一张头程发运单做分摊。
   *
   * @param ruleId 用哪一版规则（**指定版本**，不是「当前规则」）
   * @param sourceShipmentId warehouse 的头程发运单 id
   * @param costAsOf 结账时点：这批货的成本算到哪一刻的头上
   */
  public record CostAllocationRequest(
      @NotNull UUID ruleId,
      @NotNull UUID sourceShipmentId,
      @NotNull OffsetDateTime costAsOf,
      @Size(max = 500) String note) {}

  /** 结账：锁定这张分摊单。 */
  public record CostAllocationLockRequest(
      @Schema(nullable = true) OffsetDateTime costAsOf, @Size(max = 500) String note) {}
}
