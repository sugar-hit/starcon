package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 10.7.1.1 退货接收与处置决策的请求 / 响应形状。
 *
 * <p>🔴 本模块最容易做假的三个地方，这一层就把它们的口径写死：
 *
 * <ol>
 *   <li><b>处置方式没有默认值</b>：{@link DecideDispositionRequest#dispositionMethod()} 与
 *       {@link ReturnDispositionDecisionRow#dispositionMethod()} 都是<b>必填</b>，
 *       三值白名单由 DB 列级 CHECK 钉死。少给一个值 ⇒ 请求被拒，
 *       **不会**悄悄落成「转销售」。</li>
 *   <li><b>成本合计不落表</b>：{@link ReturnDispositionOrderRow#totalCostAmount()} 是
 *       <b>查询时现算</b>的 SUM，不是某一列。跨币种不许相加 ——
 *       {@link ReturnDispositionOverview#costTotals()} 按币种分行给出。</li>
 *   <li><b>残值与成本是两个币种</b>：{@code costCurrency} 与 {@code salvageCurrency} 分开。
 *       本模块<b>不做任何汇率换算</b>，跨币种的净额归 12 财务（服务当前不存在）。</li>
 * </ol>
 */
public final class ReturnDispositionModels {
  private ReturnDispositionModels() {}

  // ================================================================ 枚举

  /** 处置去向（蓝图「转销售 / 退运回国 / 弃置销毁」三值）。 */
  public enum DispositionMethod {
    RESALE("转销售（可再售）"),
    RETURN_TO_COUNTRY("退运回国"),
    DISCARD("弃置销毁");

    private final String label;

    DispositionMethod(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 静态白名单，供能力声明与前端下拉使用。 */
    public static List<String> codes() {
      return List.of("RESALE", "RETURN_TO_COUNTRY", "DISCARD");
    }
  }

  /** 接收仓类型（蓝图「本地仓 / 海外仓 / 平台仓」三值）。 */
  public enum WarehouseType {
    LOCAL("本地仓"),
    OVERSEAS("海外仓"),
    PLATFORM("平台仓");

    private final String label;

    WarehouseType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static List<String> codes() {
      return List.of("LOCAL", "OVERSEAS", "PLATFORM");
    }
  }

  /** 再销售限制的作用对象。 */
  public enum RestrictionSubjectType {
    BRAND("品牌方要求"),
    PLATFORM("平台政策"),
    SKU("单品限制");

    private final String label;

    RestrictionSubjectType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static List<String> codes() {
      return List.of("BRAND", "PLATFORM", "SKU");
    }
  }

  /** 限制依据。 */
  public enum RestrictionBasis {
    BRAND_REQUIREMENT("品牌方要求"),
    PLATFORM_POLICY("平台政策"),
    REGULATION("法规不允许"),
    CONTRACT("合同条款");

    private final String label;

    RestrictionBasis(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static List<String> codes() {
      return List.of("BRAND_REQUIREMENT", "PLATFORM_POLICY", "REGULATION", "CONTRACT");
    }
  }

  /** 处置成本类型（蓝图点名的三类 + 兜底）。 */
  public enum CostType {
    RETURN_FREIGHT("退货运费"),
    DESTROY_FEE("销毁费"),
    PLATFORM_HANDLING_FEE("平台处理费"),
    OTHER("其他费用");

    private final String label;

    CostType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static List<String> codes() {
      return List.of("RETURN_FREIGHT", "DESTROY_FEE", "PLATFORM_HANDLING_FEE", "OTHER");
    }
  }

  /** 决策状态机。 */
  public enum DecisionStatus {
    RECORDED("已记录，待下发处置单"),
    CONSUMED("已下发处置单"),
    SUPERSEDED("已被后来的决策取代");

    private final String label;

    DecisionStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 处置单状态机。 */
  public enum OrderStatus {
    ISSUED("已下发"),
    CANCELLED("已撤销");

    private final String label;

    OrderStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 库存状态变更指令类型（→ 7 库存）。 */
  public enum InstructionType {
    MOVE_TO_SELLABLE("转可售"),
    HOLD_FOR_REPATRIATION("待退运回国"),
    HOLD_FOR_DISPOSAL("待弃置销毁");

    private final String label;

    InstructionType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 指令的目标去向状态。 */
  public enum TargetInventoryState {
    SELLABLE("可售"),
    RETURN_PENDING("待退运"),
    DISPOSAL_PENDING("待销毁");

    private final String label;

    TargetInventoryState(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 留痕事件类型。 */
  public enum EventType {
    RECEIVED("已接收登记"),
    DECIDED("已记录处置决策"),
    ORDER_ISSUED("已下发处置单"),
    COST_RECORDED("已登记处置成本"),
    INSTRUCTION_ISSUED("已产出库存状态变更指令"),
    ORDER_CANCELLED("已撤销处置单"),
    RESTRICTION_REGISTERED("已登记再销售限制"),
    RESTRICTION_REVOKED("已撤销再销售限制");

    private final String label;

    EventType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 能力声明

  /** 一个能力维度。 */
  public record CapabilityDimension(
      String dimension,
      String description,
      boolean available,
      String source,
      @Schema(nullable = true) String ownerModule,
      String impact) {}

  /** 能力声明。 */
  public record ReturnDispositionCapabilities(
      List<CapabilityDimension> dimensions,
      List<String> dispositionMethods,
      List<String> warehouseTypes,
      List<String> costTypes,
      List<String> restrictionSubjectTypes,
      List<String> restrictionBases,
      String decisionNote,
      String currencyNote,
      String financeNote,
      String inventoryNote,
      String upstreamNote,
      String boundaryNote) {}

  // ================================================================ 概览

  /** 按币种分行的成本合计（🔴 跨币种不许相加）。 */
  public record CurrencyTotal(String currency, BigDecimal amount, long itemCount) {}

  /** 全量概览。 */
  public record ReturnDispositionOverview(
      long registrationCount,
      long pendingDecisionCount,
      long issuedOrderCount,
      long cancelledOrderCount,
      long resaleCount,
      long repatriationCount,
      long discardCount,
      long restrictedRegistrationCount,
      List<CurrencyTotal> costTotals,
      List<CurrencyTotal> salvageTotals,
      OffsetDateTime evaluatedAt,
      String note,
      String currencyNote) {}

  // ================================================================ 接收登记

  /**
   * 登记一次接收。
   *
   * <p>🔴 受理范围只到「货到了哪个仓、由谁登记」，
   * <b>不是</b> 11.2.2.1 的入库流程：入库分录与待检分录是 trade 的事实。
   */
  public record RegisterReceivingRequest(
      UUID receiptId,
      UUID receiptLineId,
      String warehouseType,
      String warehouseRef,
      @Schema(nullable = true) String warehouseCountry,
      BigDecimal receivedQty,
      OffsetDateTime receivedAt,
      @Schema(nullable = true) String brandCode,
      @Schema(nullable = true) String platformCode,
      @Schema(nullable = true) String note) {}

  /** 接收登记列表行。 */
  public record ReturnReceivingRow(
      UUID id,
      String registrationNo,
      UUID receiptId,
      UUID receiptLineId,
      String receiptNo,
      String skuCode,
      String skuName,
      String batchNo,
      String brandCode,
      String platformCode,
      String warehouseType,
      String warehouseTypeLabel,
      String warehouseRef,
      String warehouseCountry,
      BigDecimal receivedQty,
      OffsetDateTime receivedAt,
      UUID registeredBy,
      String note,
      String dispositionState,
      @Schema(nullable = true) String dispositionMethod,
      @Schema(nullable = true) String dispositionMethodLabel,
      @Schema(nullable = true) UUID decisionId,
      @Schema(nullable = true) UUID orderId,
      long activeRestrictionCount,
      long version) {}

  /** 接收登记列表页。 */
  public record ReturnReceivingPage(
      List<ReturnReceivingRow> rows,
      int page,
      int size,
      long total,
      OffsetDateTime evaluatedAt,
      String note,
      String dispositionNote) {}

  /** 接收登记详情：登记行 + 它的全部决策 + 留痕。 */
  public record ReturnReceivingDetail(
      ReturnReceivingRow head,
      List<ReturnDispositionDecisionRow> decisions,
      List<ReturnDispositionEventRow> events) {}

  // ================================================================ 再销售限制

  /** 登记一条再销售限制。 */
  public record SaveRestrictionRequest(
      String subjectType,
      String subjectRef,
      String restrictionBasis,
      String reason,
      OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo) {}

  /** 撤销一条再销售限制。 */
  public record RevokeRestrictionRequest(String revokeReason) {}

  /** 再销售限制行。 */
  public record ReturnResaleRestrictionRow(
      UUID id,
      String subjectType,
      String subjectTypeLabel,
      String subjectRef,
      String restrictionBasis,
      String restrictionBasisLabel,
      String reason,
      OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      String status,
      @Schema(nullable = true) OffsetDateTime revokedAt,
      String revokeReason,
      long version) {}

  /** 再销售限制列表页。 */
  public record ReturnResaleRestrictionPage(
      List<ReturnResaleRestrictionRow> rows,
      int page,
      int size,
      long total,
      OffsetDateTime evaluatedAt,
      String note) {}

  // ================================================================ 可行性判定

  /** 一种去向的可行性结论。 */
  public record ReturnMethodEligibility(
      String method,
      String methodLabel,
      boolean allowed,
      List<String> blockingReasons,
      String note) {}

  /**
   * 可行性判定（<b>只读，无副作用</b>）。
   *
   * <p>🔴 它**不替人做决定**：它只回答「这批货按上游质检结论与本租户的政策，
   * 哪些去向被允许、哪些被谁挡住」。处置方式仍然必须由人显式给出。
   */
  public record ReturnDispositionEligibility(
      UUID registrationId,
      String skuCode,
      String skuName,
      BigDecimal receivedQty,
      @Schema(nullable = true) String qualityVerdict,
      @Schema(nullable = true) String qualityVerdictLabel,
      @Schema(nullable = true) OffsetDateTime qualityVerdictAt,
      @Schema(nullable = true) String qualityNote,
      boolean resaleRestricted,
      String resaleRestrictionNote,
      List<ReturnMethodEligibility> methods,
      OffsetDateTime evaluatedAt,
      String upstreamNote) {}

  // ================================================================ 处置决策

  /**
   * 记录一条处置决策。
   *
   * <p>🔴 {@code dispositionMethod} <b>必填且无默认值</b>；{@code decisionBasis} 必填。
   * 少了任何一个，服务层与 DB 都会拒。
   */
  public record DecideDispositionRequest(
      UUID registrationId,
      String dispositionMethod,
      BigDecimal decidedQty,
      String decisionBasis,
      @Schema(nullable = true) OffsetDateTime decidedAt) {}

  /** 处置决策行。 */
  public record ReturnDispositionDecisionRow(
      UUID id,
      String decisionNo,
      UUID registrationId,
      UUID receiptId,
      UUID receiptLineId,
      String receiptNo,
      String skuCode,
      String skuName,
      String batchNo,
      String brandCode,
      String platformCode,
      String dispositionMethod,
      String dispositionMethodLabel,
      BigDecimal decidedQty,
      String decisionBasis,
      UUID decidedBy,
      OffsetDateTime decidedAt,
      @Schema(nullable = true) String qualityVerdict,
      @Schema(nullable = true) String qualityVerdictLabel,
      @Schema(nullable = true) OffsetDateTime qualityVerdictAt,
      @Schema(nullable = true) BigDecimal qualitySalvageEstimate,
      @Schema(nullable = true) String qualitySalvageCurrency,
      boolean resaleRestricted,
      String resaleRestrictionNote,
      String status,
      String statusLabel,
      @Schema(nullable = true) UUID consumedByOrderId,
      long version) {}

  /** 处置决策列表页。 */
  public record ReturnDispositionDecisionPage(
      List<ReturnDispositionDecisionRow> rows,
      int page,
      int size,
      long total,
      OffsetDateTime evaluatedAt,
      String note,
      String decisionNote) {}

  /** 处置决策详情。 */
  public record ReturnDispositionDecisionDetail(
      ReturnDispositionDecisionRow head, List<ReturnDispositionEventRow> events) {}

  // ================================================================ 处置单

  /** 一条待记的成本（随处置单一起提交）。 */
  public record ReturnCostDraft(
      String costType,
      BigDecimal amount,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) String vendorRef,
      @Schema(nullable = true) OffsetDateTime incurredAt,
      @Schema(nullable = true) String note) {}

  /**
   * 下发一张处置单。
   *
   * <p>🔴 处置方式<b>不</b>在这里给 —— 它只能来自被引用的那条决策。
   * 处置单与决策的「去向相同 / 数量相同」由 DB 触发器钉死。
   */
  public record IssueDispositionOrderRequest(
      UUID decisionId,
      String costCurrency,
      String salvageCurrency,
      BigDecimal salvageAmount,
      @Schema(nullable = true) OffsetDateTime issuedAt,
      @Schema(nullable = true) String note,
      @Schema(nullable = true) List<ReturnCostDraft> costs) {}

  /** 追加一条处置成本。 */
  public record RecordCostRequest(
      String costType,
      BigDecimal amount,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) String vendorRef,
      @Schema(nullable = true) OffsetDateTime incurredAt,
      @Schema(nullable = true) String note) {}

  /**
   * 撤销一张处置单。
   *
   * <p>🔴 撤销**不撤回**已经产出的库存状态变更指令：指令是「当时下达过什么」的
   * 事实，消费方 7.4.1.1 未交付，此刻没有任何库存被改动。
   */
  public record CancelDispositionOrderRequest(String cancelReason, Long expectedVersion) {}

  /** 处置成本明细行。 */
  public record ReturnDispositionCostRow(
      UUID id,
      UUID orderId,
      String costType,
      String costTypeLabel,
      BigDecimal amount,
      String currency,
      String vendorRef,
      OffsetDateTime incurredAt,
      String note) {}

  /** 库存状态变更指令（→ 7 库存，只出不消费）。 */
  public record ReturnInventoryInstructionRow(
      UUID id,
      UUID orderId,
      String dispositionMethod,
      String instructionType,
      String instructionTypeLabel,
      String targetInventoryState,
      String targetInventoryStateLabel,
      String skuCode,
      String skuName,
      String batchNo,
      BigDecimal qty,
      String instructionStatus,
      String consumerModule,
      OffsetDateTime issuedAt,
      UUID issuedBy) {}

  /** 处置单行。 */
  public record ReturnDispositionOrderRow(
      UUID id,
      String dispositionNo,
      UUID decisionId,
      UUID registrationId,
      UUID receiptId,
      UUID receiptLineId,
      String receiptNo,
      String skuCode,
      String skuName,
      String batchNo,
      String brandCode,
      String platformCode,
      String dispositionMethod,
      String dispositionMethodLabel,
      BigDecimal disposedQty,
      String costCurrency,
      String salvageCurrency,
      BigDecimal salvageAmount,
      BigDecimal totalCostAmount,
      int costItemCount,
      String status,
      String statusLabel,
      UUID issuedBy,
      OffsetDateTime issuedAt,
      String note,
      long version) {}

  /** 处置单详情。 */
  public record ReturnDispositionOrderDetail(
      ReturnDispositionOrderRow head,
      List<ReturnDispositionCostRow> costs,
      @Schema(nullable = true) ReturnInventoryInstructionRow instruction,
      List<ReturnDispositionEventRow> events) {}

  /** 处置单列表页。 */
  public record ReturnDispositionOrderPage(
      List<ReturnDispositionOrderRow> rows,
      int page,
      int size,
      long total,
      OffsetDateTime evaluatedAt,
      String note,
      String currencyNote) {}

  // ================================================================ 留痕

  /** 留痕行。 */
  public record ReturnDispositionEventRow(
      UUID id,
      String eventType,
      String eventTypeLabel,
      @Schema(nullable = true) UUID registrationId,
      @Schema(nullable = true) UUID decisionId,
      @Schema(nullable = true) UUID orderId,
      UUID actorId,
      OffsetDateTime occurredAt,
      String detail) {}

  /** 留痕列表页。 */
  public record ReturnDispositionEventPage(
      List<ReturnDispositionEventRow> rows, int page, int size, long total) {}
}
