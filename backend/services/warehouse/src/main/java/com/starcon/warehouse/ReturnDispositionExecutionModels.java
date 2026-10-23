package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 11.2.3.2 转可售/残次/报废 —— warehouse 侧执行模型。
 *
 * <p>蓝图 11-aftersales-and-returns.md：「按质检结论把退货转入对应状态：可再销售转可售库存、
 * 残次转残次库存（可用于清仓）、报废则核销并计入损耗。」输出：「库存状态变更分录；可售量更新；
 * 报废损耗记录。」
 *
 * <p><b>本服务只负责「执行」这一段</b>：处置决定、审批与报废损耗记录归 trade
 * （{@code V337__trade_return_inventory_disposition.sql}）。库存事实的写入所有权在本服务，
 * 所以改库存这一步必须在这里发生（AGENTS.md「模块边界纪律」禁止跨服务 SQL / FK / 视图）。
 *
 * <p><b>报废为什么是「减库存」而不是「挪到报废 bucket」</b>：质量状态词表
 * {@code inventory_quality_status.role} 只有 SELLABLE / PENDING_INSPECTION / DEFECTIVE 三值，
 * <b>没有 SCRAP</b> —— 报废的货已经核销，物理上不在库位上，往一个「报废 bucket」记数量等于让
 * 这批货永远占着一个库位。
 */
public final class ReturnDispositionExecutionModels {

  private ReturnDispositionExecutionModels() {}

  // ------------------------------------------------------------ 枚举

  /** 处置目标状态（与 trade 侧指令三值一一对应）。 */
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

  // ------------------------------------------------------------ 请求

  /**
   * 执行命令（由 trade 的处置决定单发出）。
   *
   * <p><b>本服务不接受「目标质量状态 id」这类由调用方指定的定位信息</b>：目标质量状态由目标状态
   * <b>在本服务自己的库里</b>按 role 解析（{@code SELLABLE} / {@code DEFECTIVE}），
   * 让调用方指定，就等于允许它在别的租户的库存上乱指。
   *
   * <p><b>目标库位必须显式给</b>：库位类型与质量状态一一对应（权威函数
   * {@code location_allocation_required_type}，V106）—— 可售只能进 STORAGE 位、残次只能进
   * DEFECTIVE 位，而待检货在 QUARANTINE 位上。沿用来源库位会把货落在「既不能发也不能清」的地方。
   * 报废没有目标库位（{@code targetLocationId} 传 null：核销掉的货不在任何库位上）。
   */
  public record ReturnDispositionExecuteRequest(
      @Schema(nullable = false) UUID dispositionId,
      @Schema(nullable = false) String dispositionNo,
      @Schema(nullable = false) UUID instructionId,
      @Schema(nullable = false) UUID inspectionId,
      @Schema(nullable = false) String targetInventoryState,
      @Schema(nullable = false) UUID skuId,
      @Schema(nullable = true) String skuCode,
      @Schema(nullable = true) String skuName,
      @Schema(nullable = true) String batchNo,
      @Schema(nullable = false) BigDecimal qty,
      @Schema(nullable = false) UUID sourceBucketId,
      @Schema(nullable = true) UUID targetLocationId,
      @Schema(nullable = false) OffsetDateTime occurredAt) {}

  // ------------------------------------------------------------ 响应

  /**
   * 执行回执。
   *
   * @param idempotentReplay true = 这张处置单此前已经执行过，本次是幂等重放
   *     （**没有**再动一次库存）；false = 本次真的执行了
   */
  public record ReturnDispositionExecutionResult(
      UUID executionId,
      String executionNo,
      UUID dispositionId,
      String dispositionNo,
      String targetInventoryState,
      UUID skuId,
      String skuCode,
      BigDecimal qty,
      UUID sourceBucketId,
      UUID targetBucketId,
      UUID inventoryTransactionId,
      BigDecimal sellableQuantityBefore,
      BigDecimal sellableQuantityAfter,
      OffsetDateTime occurredAt,
      boolean idempotentReplay,
      String note) {}

  /** 候选来源库位（待检库存行）。 */
  public record ReturnDispositionExecutionPendingBucket(
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
      UUID qualityStatusId,
      String qualityStatusCode,
      UUID skuId,
      String skuCode,
      String skuName,
      BigDecimal onHand,
      BigDecimal reserved,
      BigDecimal available) {}

  /** 候选来源库位分页。 */
  public record PendingInspectionBucketPage(
      List<ReturnDispositionExecutionPendingBucket> rows, long total, int page, int size) {}

  /** 候选目标库位（按目标状态对应的库位类型过滤）。 */
  public record ReturnDispositionExecutionTargetLocation(
      UUID locationId,
      String code,
      String name,
      String type,
      UUID warehouseId,
      String warehouseCode,
      boolean pickable,
      String status) {}

  /**
   * 处置选项：候选来源库位（待检库存行）+ 候选目标库位。
   *
   * <p>两个候选集**必须同时给**：来源库位决定「货现在在哪」，目标库位决定「货要去哪」——
   * 库位类型与目标质量状态是一一对应的（{@code location_allocation_required_type}）。
   */
  public record ReturnDispositionExecutionOptions(
      String targetInventoryState,
      String requiredLocationType,
      List<ReturnDispositionExecutionPendingBucket> pendingBuckets,
      List<ReturnDispositionExecutionTargetLocation> targetLocations) {}

  /** 执行回执行（列表用）。 */
  public record ReturnDispositionExecutionRow(
      UUID id,
      String executionNo,
      UUID dispositionId,
      String dispositionNo,
      UUID instructionId,
      String targetInventoryState,
      UUID skuId,
      String skuCode,
      String skuName,
      String batchNo,
      BigDecimal qty,
      UUID sourceBucketId,
      UUID sourceLocationId,
      UUID targetBucketId,
      UUID inventoryTransactionId,
      BigDecimal sellableQuantityBefore,
      BigDecimal sellableQuantityAfter,
      OffsetDateTime occurredAt,
      UUID executedBy) {}

  /** 执行回执分页。 */
  public record ReturnDispositionExecutionPage(
      List<ReturnDispositionExecutionRow> rows, long total, int page, int size) {}

  // ------------------------------------------------------------ 能力声明

  /** 能力与边界声明。 */
  public record ReturnDispositionExecutionCapabilities(
      List<ReturnDispositionExecutionCapability> blocks,
      List<ReturnDispositionExecutionBoundary> boundaries) {}

  /** 职责句的一块（逐块报可用性）。 */
  public record ReturnDispositionExecutionCapability(
      String block, boolean available, String ownerModule, String note) {}

  /** 边界（刻意不做的事 + 下游归属）。 */
  public record ReturnDispositionExecutionBoundary(
      String boundary, String ownerModule, String reason) {}
}
