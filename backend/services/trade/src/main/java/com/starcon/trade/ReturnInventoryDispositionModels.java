package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 11.2.3.2 转可售/残次/报废的传输模型。
 *
 * <p>蓝图 11-aftersales-and-returns.md：「按质检结论把退货转入对应状态：可再销售转可售库存、
 * 残次转残次库存（可用于清仓）、报废则核销并计入损耗。」输入：「质检结论；处置决定；
 * {@code expected_version}。」输出：「库存状态变更分录；可售量更新；报废损耗记录。」
 * 以及红线：「<b>报废是不可逆的</b>，需审批与原因，不能由客服随手处理。」
 *
 * <p><b>命名空间独立于 {@code /trade/return-quality}（11.2.3.1）</b>：本模块的产物是
 * <b>另一种单据</b>（处置决定 + 审批 + 执行回执），不是质检单的另一个视图。
 */
public final class ReturnInventoryDispositionModels {

  private ReturnInventoryDispositionModels() {}

  // ------------------------------------------------------------ 枚举

  /** 处置单状态机。 */
  public enum ReturnInventoryDispositionStatus {
    /** 草稿：处置要素还在录，没有审批、没有动过库存。 */
    DRAFT("草稿"),
    /** 已提交待审。 */
    PENDING_APPROVAL("待审批"),
    /** 已批：可以执行（执行才是动库存的那一步）。 */
    APPROVED("已批待执行"),
    /** 已驳回（终态）。 */
    REJECTED("已驳回"),
    /** 已执行：库存已改、指令已消费、报废损耗已记（终态，不可逆）。 */
    EXECUTED("已执行"),
    /** 执行失败：可原样重试（货还没动过，不必重批）。 */
    EXECUTION_FAILED("执行失败"),
    /** 已撤销（终态）。 */
    CANCELLED("已撤销");

    private final String label;

    ReturnInventoryDispositionStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 目标库存状态（与 11.2.3.1 的质量状态指令三值一一对应）。 */
  public enum TargetInventoryState {
    /** 转可售：待检 → 可售，可售量随之增加（下游 7.1.1.3 可用量）。 */
    SELLABLE("可售"),
    /** 转残次：待检 → 残次，可用于清仓（下游 5.4 调价）。 */
    DEFECTIVE("残次"),
    /** 报废核销：待检 → 核销，货不在库位上（损耗归 12 财务）。 */
    SCRAP("报废");

    private final String label;

    TargetInventoryState(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 报废损耗原因码（受控词表）。 */
  public enum ScrapReasonCode {
    /** 损坏且不可修复。 */
    DAMAGED_UNREPAIRABLE("损坏不可修复"),
    /** 缺少配件（11.2.3.1 判定口径里「缺少配件 ⇒ 只能残次」，这里对应「已无修复价值」）。 */
    MISSING_PARTS("缺少配件"),
    /** 功能检测不通过。 */
    FAILED_FUNCTIONAL_TEST("功能检测不通过"),
    /** 修复成本高于残值。 */
    BEYOND_ECONOMIC_REPAIR("修复不经济"),
    /** 其它（必须写说明）。 */
    OTHER("其它");

    private final String label;

    ScrapReasonCode(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ------------------------------------------------------------ 请求

  /**
   * 建处置决定单（草稿）。
   *
   * <p><b>数量不由调用方自由决定</b>：质检结论的数量是输入（蓝图输入句第一项「质检结论」），
   * 本单必须处置**那一批的全部数量**（处置一半意味着另一半永远没有结论）。
   * 服务层会按指令回填数量，调用方传的数量只作为「与结论一致」的断言。
   */
  public record ReturnInventoryDispositionCreate(
      @Schema(nullable = false) UUID instructionId,
      @Schema(nullable = false) UUID sourceBucketId,
      @Schema(nullable = true) UUID targetLocationId,
      @Schema(nullable = true) BigDecimal qty,
      @Schema(nullable = false) String reasonNote,
      @Schema(nullable = true) String scrapReasonCode) {}

  /** 改草稿要素（只有 DRAFT 可改，必须带 expectedVersion）。 */
  public record ReturnInventoryDispositionUpdate(
      @Schema(nullable = false) Long expectedVersion,
      @Schema(nullable = true) UUID sourceBucketId,
      @Schema(nullable = true) UUID targetLocationId,
      @Schema(nullable = true) BigDecimal qty,
      @Schema(nullable = true) String reasonNote,
      @Schema(nullable = true) String scrapReasonCode) {}

  /** 提交待审。 */
  public record ReturnInventoryDispositionSubmit(
      @Schema(nullable = false) Long expectedVersion, @Schema(nullable = true) String note) {}

  /** 审批（批准 / 驳回）。审批意见必填，且提交人不能审批自己提交的处置单。 */
  public record ReturnInventoryDispositionDecision(
      @Schema(nullable = false) Long expectedVersion,
      @Schema(nullable = false) String decisionNote) {}

  /** 撤销。 */
  public record ReturnInventoryDispositionCancel(
      @Schema(nullable = false) Long expectedVersion,
      @Schema(nullable = false) String cancelReason) {}

  /** 执行（真正动库存的那一步）。 */
  public record ReturnInventoryDispositionExecute(@Schema(nullable = false) Long expectedVersion) {}

  // ------------------------------------------------------------ 响应

  /** 处置单行。 */
  public record ReturnInventoryDispositionRow(
      UUID id,
      String dispositionNo,
      UUID instructionId,
      UUID inspectionId,
      UUID receiptId,
      String targetInventoryState,
      String targetInventoryStateLabel,
      String instructionType,
      UUID skuId,
      String skuCode,
      String skuName,
      String batchNo,
      BigDecimal qty,
      UUID sourceBucketId,
      UUID sourceWarehouseId,
      String sourceWarehouseCode,
      UUID sourceLocationId,
      String sourceLocationCode,
      UUID sourceLotId,
      String sourceLotCode,
      UUID targetLocationId,
      String targetLocationCode,
      String reasonNote,
      String scrapReasonCode,
      String scrapReasonCodeLabel,
      String status,
      String statusLabel,
      long version,
      UUID submittedBy,
      OffsetDateTime submittedAt,
      UUID decidedBy,
      OffsetDateTime decidedAt,
      String decisionNote,
      UUID executedBy,
      OffsetDateTime executedAt,
      UUID executionTransactionId,
      UUID executionBucketId,
      String executionFailureReason,
      UUID createdBy,
      OffsetDateTime createdAt,
      boolean instructionConsumed) {}

  /** 处置单列表（真分页 + 汇总）。 */
  public record ReturnInventoryDispositionPage(
      List<ReturnInventoryDispositionRow> dispositions,
      long total,
      int page,
      int size,
      ReturnInventoryDispositionSummary summary) {}

  /** 跨全表的汇总（不受分页影响）。 */
  public record ReturnInventoryDispositionSummary(
      long totalCount,
      long draftCount,
      long pendingApprovalCount,
      long approvedCount,
      long executedCount,
      long executionFailedCount,
      long scrapExecutedCount,
      BigDecimal executedSellableQty,
      BigDecimal executedDefectiveQty,
      BigDecimal executedScrapQty) {}

  /** 处置单详情：头 + 留痕 + 损耗记录 + 当前可执行动作。 */
  public record ReturnInventoryDispositionDetail(
      ReturnInventoryDispositionRow disposition,
      List<ReturnInventoryDispositionEventRow> events,
      ReturnInventoryDispositionLossRow loss,
      List<ReturnInventoryDispositionAction> actions,
      ReturnInventoryDispositionInstructionRow instruction) {}

  /** 留痕。 */
  public record ReturnInventoryDispositionEventRow(
      UUID id,
      String eventType,
      String fromStatus,
      String toStatus,
      String targetInventoryState,
      BigDecimal qty,
      String reasonNote,
      UUID actorId,
      OffsetDateTime occurredAt) {}

  /**
   * 报废损耗记录。
   *
   * <p><b>财务侧字段恒为 null</b>（12 财务服务尚不存在），并标注归属模块 ——
   * <b>不填 0 冒充「这批报废货没产生任何损耗」</b>（填 0 会让财务以为这批货零损耗）。
   */
  public record ReturnInventoryDispositionLossRow(
      UUID id,
      UUID dispositionId,
      UUID instructionId,
      UUID skuId,
      String skuCode,
      BigDecimal scrapQty,
      BigDecimal originalValue,
      BigDecimal salvageAmount,
      String salvageCurrency,
      BigDecimal proposedLossAmount,
      BigDecimal financeLossAmount,
      String financeCurrency,
      String financeReference,
      OffsetDateTime financeConfirmedAt,
      String financeOwnerModule,
      String lossStatus,
      UUID recordedBy,
      OffsetDateTime recordedAt,
      String note) {}

  /** 当前可执行动作。 */
  public record ReturnInventoryDispositionAction(
      String action,
      String label,
      boolean available,
      String ownerModule,
      String reason) {}

  /** 待处置的质量状态指令（11.2.3.1 产出，本模块消费）。 */
  public record ReturnInventoryDispositionInstructionRow(
      UUID id,
      UUID inspectionId,
      UUID receiptId,
      UUID receiptLineId,
      String instructionType,
      String targetInventoryState,
      String targetInventoryStateLabel,
      UUID skuId,
      String skuCode,
      String skuName,
      String batchNo,
      BigDecimal qty,
      BigDecimal salvageAmount,
      String salvageCurrency,
      BigDecimal originalValue,
      BigDecimal gradeCoefficient,
      String instructionStatus,
      OffsetDateTime issuedAt,
      UUID issuedBy,
      boolean consumed) {}

  /** 待处置指令分页。 */
  public record ReturnInventoryDispositionInstructionPage(
      List<ReturnInventoryDispositionInstructionRow> instructions, long total, int page, int size) {}

  /** 处置选项（候选来源库位 + 候选目标库位，透传 warehouse 的真数据）。 */
  public record ReturnInventoryDispositionOptions(
      String targetInventoryState,
      String requiredLocationType,
      List<ReturnInventoryDispositionPendingBucket> pendingBuckets,
      List<ReturnInventoryDispositionTargetLocation> targetLocations,
      String note) {}

  /** 候选来源库位（待检库存行）。 */
  public record ReturnInventoryDispositionPendingBucket(
      UUID bucketId,
      UUID warehouseId,
      String warehouseCode,
      String warehouseName,
      UUID locationId,
      String locationCode,
      String locationName,
      UUID lotId,
      String lotCode,
      UUID legalEntityId,
      String qualityStatusCode,
      UUID skuId,
      String skuCode,
      String skuName,
      BigDecimal onHand,
      BigDecimal reserved,
      BigDecimal available) {}

  /** 候选目标库位。 */
  public record ReturnInventoryDispositionTargetLocation(
      UUID locationId,
      String code,
      String name,
      String type,
      UUID warehouseId,
      String warehouseCode,
      boolean pickable,
      String status) {}

  // ------------------------------------------------------------ 能力声明

  /** 能力声明。 */
  public record ReturnInventoryDispositionCapabilities(
      List<ReturnInventoryDispositionCapability> blocks,
      List<ReturnInventoryDispositionBoundary> boundaries,
      List<ReturnInventoryDispositionDownstream> downstreams) {}

  /** 职责句的一块。 */
  public record ReturnInventoryDispositionCapability(
      String block, boolean available, String ownerModule, String note) {}

  /** 边界（刻意不做的事）。 */
  public record ReturnInventoryDispositionBoundary(String boundary, String ownerModule, String reason) {}

  /** 下游可用性。 */
  public record ReturnInventoryDispositionDownstream(
      String downstream, boolean delivered, String ownerModule, String note) {}
}
