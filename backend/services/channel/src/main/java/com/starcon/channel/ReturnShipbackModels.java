package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 10.7.1.2 退运回国的请求 / 响应形状。
 *
 * <p>🔴 本模块最容易做假的三处，这一层就把口径写死：
 *
 * <ol>
 *   <li><b>合单是真的</b>：{@link CreateConsignmentRequest#lines()} 是<b>多条</b>处置单，
 *       一张退运单可以装 N 批货 —— 「凑单 / 合箱」是表结构上的多行，不是一句口号。
 *       反过来一条处置单最多进一个退运单（DB 唯一键）。</li>
 *   <li><b>成本分摊是真的算出来的</b>：{@link ReturnShipbackAllocationRow} 逐行给出
 *       权重、权重合计、比例、分摊金额与尾差调整额；**合计恒等于该笔成本金额**
 *       （提交时由 DEFERRABLE 约束触发器钉死）。比例是 scale 4 的 {@link BigDecimal}，
 *       全程禁浮点。</li>
 *   <li><b>币种口径不混</b>：运输成本一个币种（{@code costCurrency}），
 *       报关金额另一个币种（{@code declaredCurrency}），残值再一个（{@code salvageCurrency}）。
 *       本模块<b>不做任何汇率换算</b>，三者<b>永远不相加</b>。</li>
 * </ol>
 */
public final class ReturnShipbackModels {
  private ReturnShipbackModels() {}

  // ================================================================ 枚举

  /** 退运单状态机。 */
  public enum ShipbackStatus {
    DRAFT("草稿（可加行、可登记报关与成本）"),
    CONFIRMED("已确认（货已凑齐、报关齐备）"),
    DISPATCHED("已发出（已交承运商）"),
    ARRIVED("已到货（已回国、进国内收货仓）"),
    CLOSED("已关闭（账已结清）"),
    CANCELLED("已取消");

    private final String label;

    ShipbackStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static List<String> codes() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /** 运输方式（海运拼箱是本模块的主角）。 */
  public enum TransportMode {
    SEA_CONSOLIDATION("海运拼箱（LCL）"),
    SEA_FULL_CONTAINER("海运整柜（FCL）"),
    AIR("空运"),
    EXPRESS("国际快递"),
    TRUCK("陆运"),
    OTHER("其他");

    private final String label;

    TransportMode(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static List<String> codes() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /** 明细行状态。 */
  public enum ShipbackLineStatus {
    PLANNED("已排入退运单"),
    SHIPPED("已发出"),
    ARRIVED("已到货"),
    RECEIVED("已收货"),
    SCRAPPED("已报废"),
    SHORTAGE("短少");

    private final String label;

    ShipbackLineStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static List<String> codes() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /** 退运成本类型。 */
  public enum ShipbackCostType {
    OCEAN_FREIGHT("海运费"),
    AIR_FREIGHT("空运费"),
    TARIFF("关税"),
    CUSTOMS_FEE("报关费"),
    INLAND_FREIGHT("内陆运费"),
    HANDLING_FEE("操作 / 装卸费"),
    REPACK_FEE("换包装费"),
    OTHER("其他费用");

    private final String label;

    ShipbackCostType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static List<String> codes() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /**
   * 🔴 成本分摊口径。
   *
   * <p>它是<b>数据</b>不是代码常量：海运拼箱按重量摊、空运按件摊、报关货值按价值摊，
   * 都由人显式选。权重合计为 0 时显式回退到 {@link #EQUAL} 并把实际口径写进
   * {@code appliedBasis}，绝不静默换一个口径。
   */
  public enum AllocationBasis {
    WEIGHT("按重量"),
    QUANTITY("按数量（件）"),
    DECLARED_VALUE("按申报货值"),
    EQUAL("平均分摊");

    private final String label;

    AllocationBasis(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static List<String> codes() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /** 报关单类型。 */
  public enum DeclarationType {
    RETURN_IMPORT("退运进口"),
    EXPORT("出口"),
    TRANSIT("过境");

    private final String label;

    DeclarationType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static List<String> codes() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /** 报关资料状态。 */
  public enum DeclarationStatus {
    DRAFT("草稿"),
    SUBMITTED("已提交"),
    CLEARED("已放行"),
    REJECTED("已退单");

    private final String label;

    DeclarationStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static List<String> codes() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /** 报关状态（退运单头，与 10.5.1 的报关单状态对齐的快照）。 */
  public enum ConsignmentCustomsStatus {
    NOT_PREPARED("未备报关"),
    PREPARED("已备报关"),
    SUBMITTED("已报关"),
    CLEARED("已放行"),
    REJECTED("已退单");

    private final String label;

    ConsignmentCustomsStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static List<String> codes() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /** 重新入库 / 报废去向。 */
  public enum EntryType {
    RESTOCK("重新入库（→ 7 库存）"),
    SCRAP("报废（→ 10.7.1.3）");

    private final String label;

    EntryType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static List<String> codes() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /** 留痕事件类型。 */
  public enum ShipbackEventType {
    CONSIGNMENT_CREATED("已建退运单"),
    LINE_ADDED("已加入明细行"),
    CUSTOMS_PREPARED("已登记报关资料"),
    COST_RECORDED("已登记成本并分摊"),
    CONFIRMED("已确认"),
    DISPATCHED("已发出"),
    ARRIVED("已到货收货"),
    ENTRY_ISSUED("已出入库 / 报废分录"),
    CLOSED("已关闭"),
    CANCELLED("已取消");

    private final String label;

    ShipbackEventType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static List<String> codes() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  // ================================================================ 能力声明

  /** 一个能力维度。 */
  public record ShipbackCapabilityDimension(
      String dimension,
      String description,
      boolean available,
      String source,
      @Schema(nullable = true) String ownerModule,
      String impact) {}

  /** 能力声明。 */
  public record ReturnShipbackCapabilities(
      List<ShipbackCapabilityDimension> dimensions,
      List<String> statuses,
      List<String> transportModes,
      List<String> costTypes,
      List<String> allocationBases,
      List<String> declarationTypes,
      List<String> entryTypes,
      String consolidationNote,
      String allocationNote,
      String currencyNote,
      String customsNote,
      String financeNote,
      String inventoryNote,
      String upstreamNote,
      String boundaryNote) {}

  // ================================================================ 概览

  /** 按币种分行的合计（🔴 跨币种不许相加）。 */
  public record ShipbackCurrencyTotal(String currency, BigDecimal amount, long itemCount) {}

  /** 全量概览。 */
  public record ReturnShipbackOverview(
      long consignmentCount,
      long draftCount,
      long confirmedCount,
      long dispatchedCount,
      long arrivedCount,
      long closedCount,
      long cancelledCount,
      long candidateCount,
      BigDecimal candidateQty,
      long lineCount,
      List<ShipbackCurrencyTotal> costTotals,
      List<ShipbackCurrencyTotal> declaredValueTotals,
      long restockEntryCount,
      long scrapEntryCount,
      OffsetDateTime evaluatedAt,
      String note,
      String currencyNote) {}

  // ================================================================ 待退运清单

  /**
   * 一条待退运候选（来自 10.7.1.1 的「退运回国」处置单）。
   *
   * <p>🔴 只有 {@code dispositionMethod = 'RETURN_TO_COUNTRY'} 的处置单会出现在这里；
   * 转销售 / 弃置销毁的处置单**根本不会**出现在候选清单里。
   */
  public record ShipbackCandidateRow(
      UUID dispositionOrderId,
      String dispositionNo,
      UUID decisionId,
      String receiptNo,
      String skuCode,
      String skuName,
      String batchNo,
      String brandCode,
      String platformCode,
      BigDecimal disposedQty,
      BigDecimal salvageAmount,
      String salvageCurrency,
      String dispositionMethod,
      String dispositionMethodLabel,
      String issuedAt,
      String note) {}

  /** 待退运清单页。 */
  public record ShipbackCandidatePage(
      List<ShipbackCandidateRow> rows,
      int page,
      int size,
      long total,
      BigDecimal totalQty,
      OffsetDateTime evaluatedAt,
      String note) {}

  // ================================================================ 退运单

  /** 加入退运单的一条明细（合单的最小单位）。 */
  public record ShipbackLineDraft(
      UUID dispositionOrderId,
      BigDecimal qty,
      @Schema(nullable = true) BigDecimal weightKg,
      @Schema(nullable = true) BigDecimal volumeM3,
      @Schema(nullable = true) String hsCode,
      @Schema(nullable = true) String declaredItemName,
      @Schema(nullable = true) BigDecimal declaredValue,
      @Schema(nullable = true) String declaredCurrency,
      @Schema(nullable = true) BigDecimal salvageAmount,
      @Schema(nullable = true) String salvageCurrency,
      @Schema(nullable = true) String note) {}

  /**
   * 建一张退运单（合单）。
   *
   * <p>🔴 {@code lines} 就是「凑单 / 合箱」的落点：一次可以把多条处置单的货装进同一张
   * 退运单，从而摊薄单票成本。
   */
  public record CreateConsignmentRequest(
      String transportMode,
      String originCountry,
      String destinationCountry,
      String originWarehouseRef,
      String domesticWarehouseRef,
      String costCurrency,
      @Schema(nullable = true) String departurePort,
      @Schema(nullable = true) String arrivalPort,
      @Schema(nullable = true) String carrierRef,
      @Schema(nullable = true) String trackingNo,
      @Schema(nullable = true) OffsetDateTime plannedDepartureAt,
      @Schema(nullable = true) OffsetDateTime plannedArrivalAt,
      @Schema(nullable = true) String consolidationNote,
      @Schema(nullable = true) String note,
      List<ShipbackLineDraft> lines) {}

  /** 往既有退运单里再加入一行（继续凑单）。 */
  public record AddLineRequest(ShipbackLineDraft line) {}

  /** 退运单列表行。 */
  public record ReturnShipbackRow(
      UUID id,
      String shipbackNo,
      String status,
      String statusLabel,
      String transportMode,
      String transportModeLabel,
      String originCountry,
      String destinationCountry,
      String originWarehouseRef,
      String domesticWarehouseRef,
      String carrierRef,
      String trackingNo,
      BigDecimal totalQty,
      BigDecimal totalWeightKg,
      BigDecimal totalVolumeM3,
      String costCurrency,
      String customsStatus,
      String customsStatusLabel,
      String customsDeclarationNo,
      long lineCount,
      BigDecimal totalCost,
      OffsetDateTime plannedDepartureAt,
      OffsetDateTime plannedArrivalAt,
      OffsetDateTime actualDepartureAt,
      OffsetDateTime actualArrivalAt,
      OffsetDateTime createdAt,
      String consolidationNote,
      String note,
      long version) {}

  /** 退运单列表页。 */
  public record ReturnShipbackPage(
      List<ReturnShipbackRow> rows,
      int page,
      int size,
      long total,
      BigDecimal totalCost,
      OffsetDateTime evaluatedAt,
      String note) {}

  /** 明细行。 */
  public record ReturnShipbackLineRow(
      UUID id,
      UUID consignmentId,
      UUID dispositionOrderId,
      UUID decisionId,
      String dispositionNo,
      String receiptNo,
      String skuCode,
      String skuName,
      String batchNo,
      String brandCode,
      String platformCode,
      BigDecimal qty,
      BigDecimal weightKg,
      BigDecimal volumeM3,
      String hsCode,
      String declaredItemName,
      BigDecimal declaredValue,
      String declaredCurrency,
      BigDecimal salvageAmount,
      String salvageCurrency,
      BigDecimal allocatedCost,
      String status,
      String statusLabel,
      String note) {}

  /** 报关资料（本服务自持快照）。 */
  public record ReturnShipbackDeclarationRow(
      UUID id,
      UUID consignmentId,
      UUID lineId,
      String sourceModule,
      String sourceReference,
      String declarationNo,
      String declarationType,
      String declarationTypeLabel,
      String hsCode,
      String declaredItemName,
      BigDecimal declaredQty,
      BigDecimal declaredValue,
      String declaredCurrency,
      BigDecimal tariffRate,
      BigDecimal tariffAmount,
      BigDecimal taxAmount,
      BigDecimal totalAmount,
      String status,
      String statusLabel,
      OffsetDateTime submittedAt,
      OffsetDateTime clearedAt,
      String note) {}

  /** 登记报关资料。 */
  public record RecordShipbackDeclarationRequest(
      @Schema(nullable = true) UUID lineId,
      String declarationType,
      String hsCode,
      String declaredItemName,
      BigDecimal declaredQty,
      BigDecimal declaredValue,
      String declaredCurrency,
      BigDecimal tariffRate,
      @Schema(nullable = true) BigDecimal taxAmount,
      @Schema(nullable = true) String sourceReference,
      @Schema(nullable = true) String declarationNo,
      @Schema(nullable = true) String status,
      @Schema(nullable = true) OffsetDateTime submittedAt,
      @Schema(nullable = true) OffsetDateTime clearedAt,
      @Schema(nullable = true) String note) {}

  /** 成本行。 */
  public record ReturnShipbackCostRow(
      UUID id,
      UUID consignmentId,
      String costType,
      String costTypeLabel,
      BigDecimal amount,
      String currency,
      String allocationBasis,
      String allocationBasisLabel,
      String vendorRef,
      OffsetDateTime incurredAt,
      BigDecimal allocatedTotal,
      String note) {}

  /** 登记一笔退运成本（**记完即分摊**）。 */
  public record RecordShipbackCostRequest(
      String costType,
      BigDecimal amount,
      String currency,
      String allocationBasis,
      @Schema(nullable = true) String vendorRef,
      @Schema(nullable = true) OffsetDateTime incurredAt,
      @Schema(nullable = true) String note) {}

  /**
   * 🔴 成本分摊明细。
   *
   * <p>{@code allocationRatio} 是 scale 4 的比例（HALF_UP 钉死）；
   * {@code residualAmount} 是**尾差调整额**（把「总额 − 逐笔四舍五入之和」补到某一行上），
   * 于是 {@code SUM(allocatedAmount) 恒等于该笔成本金额}。
   */
  public record ReturnShipbackAllocationRow(
      UUID id,
      UUID consignmentId,
      UUID lineId,
      UUID costId,
      String requestedBasis,
      String requestedBasisLabel,
      String appliedBasis,
      String appliedBasisLabel,
      BigDecimal basisValue,
      BigDecimal basisTotal,
      BigDecimal allocationRatio,
      BigDecimal allocatedAmount,
      String currency,
      BigDecimal residualAmount,
      String skuCode,
      String skuName) {}

  /** 分摊结果（记一笔成本后的返回）。 */
  public record ShipbackAllocationResult(
      UUID costId,
      String costType,
      BigDecimal amount,
      String currency,
      String requestedBasis,
      String appliedBasis,
      boolean basisFallback,
      BigDecimal totalAllocated,
      List<ReturnShipbackAllocationRow> allocations,
      String note) {}

  /** 回国收货记录。 */
  public record ReturnShipbackArrivalRow(
      UUID id,
      UUID consignmentId,
      String domesticWarehouseRef,
      OffsetDateTime arrivedAt,
      BigDecimal receivedQty,
      BigDecimal damagedQty,
      BigDecimal shortageQty,
      UUID receivedBy,
      String note) {}

  /** 登记回国收货。 */
  public record RecordShipbackArrivalRequest(
      String domesticWarehouseRef,
      OffsetDateTime arrivedAt,
      BigDecimal receivedQty,
      @Schema(nullable = true) BigDecimal damagedQty,
      @Schema(nullable = true) BigDecimal shortageQty,
      @Schema(nullable = true) String note) {}

  /** 重新入库 / 报废分录（→ 7 库存 / 10.7.1.3，**只出不消费**）。 */
  public record ReturnShipbackEntryRow(
      UUID id,
      UUID consignmentId,
      UUID lineId,
      String entryType,
      String entryTypeLabel,
      BigDecimal qty,
      String skuCode,
      String skuName,
      String batchNo,
      String domesticWarehouseRef,
      String entryNo,
      String postingStatus,
      String consumerModule,
      OffsetDateTime issuedAt,
      UUID issuedBy,
      String note) {}

  /** 登记重新入库 / 报废分录。 */
  public record RecordShipbackEntryRequest(
      UUID lineId,
      String entryType,
      BigDecimal qty,
      @Schema(nullable = true) String domesticWarehouseRef,
      @Schema(nullable = true) String note) {}

  /** 推进退运单状态（确认 / 发出 / 关闭 / 取消）。 */
  public record AdvanceShipbackRequest(
      @Schema(nullable = true) OffsetDateTime occurredAt,
      @Schema(nullable = true) String trackingNo,
      @Schema(nullable = true) String cancelReason,
      @Schema(nullable = true) Long expectedVersion) {}

  /** 退运单详情。 */
  public record ReturnShipbackDetail(
      ReturnShipbackRow head,
      List<ReturnShipbackLineRow> lines,
      List<ReturnShipbackDeclarationRow> declarations,
      List<ReturnShipbackCostRow> costs,
      List<ReturnShipbackAllocationRow> allocations,
      List<ReturnShipbackArrivalRow> arrivals,
      List<ReturnShipbackEntryRow> entries,
      List<ReturnShipbackEventRow> events,
      List<String> nextActions,
      String note) {}

  // ================================================================ 留痕

  /** 留痕事件行。 */
  public record ReturnShipbackEventRow(
      UUID id,
      String eventType,
      String eventTypeLabel,
      UUID consignmentId,
      UUID lineId,
      UUID costId,
      UUID declarationId,
      UUID entryId,
      UUID actorId,
      OffsetDateTime occurredAt,
      String detail) {}

  /** 留痕集合页。 */
  public record ReturnShipbackEventPage(
      List<ReturnShipbackEventRow> rows,
      int page,
      int size,
      long total,
      OffsetDateTime evaluatedAt,
      String note) {}
}
