package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 8.2.2.2 待检处理传输模型（待检区库存 → 质检 → 质量状态分流）。
 *
 * <p>蓝图 08 卷 8.2.2.2 的四问逐条对应到这里：
 *
 * <ul>
 *   <li>职责「管理待检区库存：<b>发起质检任务（6 质检单）</b>、质检通过后转入可售、质检不通过转残次/报废/退货」→
 *       {@link PendingInspectionTaskCreateInput}（建单）+ {@link PendingInspectionRequestInspectionInput}（发起质检）+
 *       {@link PendingInspectionRecordResultInput}（质检结果）+ {@link PendingInspectionDisposeInput}（处置决定）。<br>
 *   <li>输入「待检库存清单；质检结果；处置决定」→ {@link PendingInspectionStockPage}（清单）+
 *       {@link PendingInspectionDisposeLineInput}（处置决定）。<br>
 *   <li>输出「<b>质量状态变更分录</b>；可售量更新」→ {@link PendingInspectionEntryRow}（分录）+ {@link
 *       PendingInspectionTaskDetail}（可售量前后对照）。<br>
 *   <li>关系「上游 = 8.2.1.1、6.4.2.1；下游 = 7.1.1.3 可用量、6.4.2.2」→ {@link PendingInspectionCapabilities} 的上下游表。
 * </ul>
 *
 * <p><b>蓝图加粗句的落点</b>：「<b>待检品不可售</b>是硬规则，质检通过前不能被销售预占。」 这句话在本模块有三处具体形态：
 *
 * <ol>
 *   <li><b>待检清单只列 role=PENDING_INSPECTION 的库存</b>，可售量口径（7.1.1.3）本来就不把它算进可售基数；<br>
 *   <li><b>质检结果没录入之前不允许处置</b>：{@link PendingInspectionDisposeInput} 的去向里，转可售的量受「可售量」上限约束，
 *       而可售量来自质检结论（合格量 + 让步接收量），不合格品转可售在服务层与数据库层各挡一道；<br>
 *   <li><b>处置必须真的动库存</b>：转可售是一条「待检余额 -Q / 可售余额 +Q」的成对分录，
 *       数据库守卫（V252）回读账本分录核对 P1/P2/P3/P4 —— 只改单据状态不改库存的「处置」绕不过。
 * </ol>
 *
 * <p><b>命名空间纪律</b>：本模块全部 record 带 {@code PendingInspection} 前缀。springdoc 用 record 的 <b>简单类名</b>做
 * OpenAPI {@code components.schemas} 的键，重名会被<b>静默覆盖</b> —— 仓库里已经有真实事故（{@code ScopeOption}、
 * {@code LocationType} 撞名），表现为「单测全绿但别的模块契约形状被改」。
 */
public final class PendingInspectionModels {

  private PendingInspectionModels() {}

  // ================================================================ 能力声明

  /**
   * 能力声明：把「哪些输入真有依据」「哪条硬约束」「上下游可用性」显式报出来。
   *
   * <p>存在的理由与既有 warehouse 模块一致：让调用方从一次 GET 就能知道「发起质检走哪条通道」
   * 「不合格品能不能转可售」「6.4.2.2 不良品处理交付后谁接管」，而不是从 400 或空数组里猜。
   */
  public record PendingInspectionCapabilities(
      String refType,
      /* 一张待检处理单 = 一条 inventory_transaction + N 条分录 */
      boolean atomicQualityMoves,
      /* 待检品不可售：质检通过前不进可售基数，也不许被预占 */
      boolean pendingIsNotSellable,
      /* 处置必须把这一批待检货全部处置掉，不允许「处置一半」 */
      boolean disposeMustCoverWholeLot,
      String upstreamBoundary,
      String inspectionBoundary,
      String dispositionBoundary,
      List<PendingInspectionUpstreamModule> upstreamModules,
      List<PendingInspectionDownstreamModule> downstreamModules,
      List<PendingInspectionHardRule> hardRules,
      String note) {

    public record PendingInspectionUpstreamModule(
        String moduleId, String moduleName, String service, boolean available, String usage) {}

    public record PendingInspectionDownstreamModule(
        String moduleId, String moduleName, String service, boolean available, String effect) {}

    /** 硬规则逐条列出：每条都是能被数据库裁决的不变量，不是提醒。 */
    public record PendingInspectionHardRule(String code, String rule, String enforcedBy) {}
  }

  // ================================================================ 待检库存清单

  /** 待检库存清单的一行 = 一个 bucket（库位 × 批次 × 质量状态）。 */
  public record PendingInspectionStockRow(
      UUID bucketId,
      UUID balanceId,
      UUID warehouseId,
      String warehouseCode,
      String warehouseName,
      UUID locationId,
      String locationCode,
      String locationName,
      String locationType,
      boolean locationPickable,
      UUID skuId,
      String skuCode,
      String skuName,
      UUID lotId,
      @Schema(nullable = true) String lotCode,
      UUID qualityStatusId,
      String qualityStatusCode,
      String qualityStatusName,
      BigDecimal onHand,
      BigDecimal reserved,
      BigDecimal available,
      @Schema(nullable = true) OffsetDateTime lastMovementAt,
      /* 该库位上是否已经压着待检处理单：有则不可重复建单 */
      boolean hasOpenTask) {}

  /** 待检库存清单（真分页 + 真筛选 + 汇总）。 */
  public record PendingInspectionStockPage(
      List<PendingInspectionStockRow> rows,
      long total,
      int page,
      int size,
      BigDecimal totalOnHand,
      BigDecimal totalAvailable,
      long bucketCount,
      long locationCount,
      long skuCount,
      @Schema(nullable = true) String note) {}

  // ================================================================ 建单

  /**
   * 建待检处理单。
   *
   * <p>入参刻意只有「哪一批待检货」+「它是哪次实收的哪一行」+「本次检多少」：
   * 质量状态不是入参 —— 它从库存行读出来（role 必须是 PENDING_INSPECTION），
   * 让调用方自己声明「这批是待检的」等于允许它把可售品重新标成待检。
   */
  public record PendingInspectionTaskCreateInput(
      @Schema(description = "待检库存行（stock_bucket.id）：这批待检货此刻所在的库位 × 批次")
          @NotNull
          UUID sourceBucketId,
      @Schema(description = "上游 8.2.1.1 收货明细行（goods_receipt_line.id）：这批货是哪次实收的哪一行")
          @NotNull
          UUID receiptLineId,
      @Schema(description = "本次待检的批量（不得超过库存行在手量与收货明细行实收量）")
          @NotNull
          @DecimalMin(value = "0", inclusive = false)
          BigDecimal quantity,
      @Schema(nullable = true) @Size(max = 2000) String notes) {}

  // ================================================================ 任务

  /** 待检处理单列表行。 */
  public record PendingInspectionTaskRow(
      UUID id,
      String taskCode,
      String status,
      String statusLabel,
      UUID warehouseId,
      String warehouseCode,
      UUID skuId,
      String skuCode,
      String skuName,
      UUID receiptId,
      String receiptCode,
      UUID receiptLineId,
      BigDecimal quantity,
      @Schema(nullable = true) String inspectionOrderCode,
      @Schema(nullable = true) String resultType,
      @Schema(nullable = true) String resultTypeLabel,
      @Schema(nullable = true) BigDecimal qualifiedQuantity,
      @Schema(nullable = true) BigDecimal unqualifiedQuantity,
      @Schema(nullable = true) BigDecimal concessionQuantity,
      @Schema(nullable = true) BigDecimal sellableQuantity,
      @Schema(nullable = true) BigDecimal nonSellableQuantity,
      String dispositionSummary,
      @Schema(nullable = true) String dispositionSummaryLabel,
      @Schema(nullable = true) OffsetDateTime createdAt,
      @Schema(nullable = true) OffsetDateTime disposedAt,
      long version) {}

  /** 待检处理单列表（真分页 + 真筛选 + 汇总）。 */
  public record PendingInspectionTaskPage(
      List<PendingInspectionTaskRow> rows,
      long total,
      int page,
      int size,
      BigDecimal totalQuantity,
      BigDecimal totalSellableQuantity,
      BigDecimal totalNonSellableQuantity,
      long pendingCount,
      long inspectionRequestedCount,
      long resultRecordedCount,
      long disposedCount,
      long cancelledCount,
      @Schema(nullable = true) String note) {}

  /** 处置明细（一行 = 一个去向）。 */
  public record PendingInspectionDispositionRow(
      UUID id,
      String dispositionType,
      String dispositionTypeLabel,
      BigDecimal quantity,
      @Schema(nullable = true) UUID targetQualityStatusId,
      @Schema(nullable = true) String targetQualityStatusCode,
      @Schema(nullable = true) String targetQualityStatusName,
      @Schema(nullable = true) UUID targetLocationId,
      @Schema(nullable = true) String targetLocationCode,
      @Schema(nullable = true) String targetLocationName,
      @Schema(nullable = true) String targetLocationType,
      @Schema(nullable = true) UUID targetBalanceId,
      @Schema(nullable = true) UUID targetBucketId,
      @Schema(nullable = true) String note,
      OffsetDateTime createdAt) {}

  /** 事件台账的一行（append-only）。 */
  public record PendingInspectionEventRow(
      UUID id,
      String eventType,
      String eventTypeLabel,
      OffsetDateTime occurredAt,
      UUID actorId,
      String note) {}

  /** 库存分录的一行（带符号；负向 = 从待检转出，正向 = 转入可售 / 残次）。 */
  public record PendingInspectionEntryRow(
      UUID id,
      UUID balanceId,
      UUID qualityStatusId,
      String qualityStatusCode,
      String qualityStatusName,
      String qualityRole,
      BigDecimal onHandDelta,
      BigDecimal reservedDelta,
      OffsetDateTime createdAt) {}

  /** 质检单快照（本服务只留 trade 的逻辑引用与可读单号）。 */
  public record PendingInspectionOrderSnapshot(
      @Schema(nullable = true) UUID inspectionOrderId,
      @Schema(nullable = true) String inspectionOrderCode,
      @Schema(nullable = true) String inspectionTemplateCode,
      @Schema(nullable = true) OffsetDateTime requestedAt,
      @Schema(nullable = true) String requestedBy) {}

  /**
   * 待检处理单详情。
   *
   * <p>{@code sellableBefore} / {@code sellableAfter} 是本模块「可售量更新」这条输出的直接读数：
   * 处置前后各算一次（同一口径：role=SELLABLE ∧ 库位可拣启用），处置完的差值就是这次质检放行的货。
   */
  public record PendingInspectionTaskDetail(
      PendingInspectionTaskRow task,
      UUID sourceBucketId,
      UUID sourceLocationId,
      String sourceLocationCode,
      String sourceLocationName,
      String sourceLocationType,
      UUID sourceBalanceId,
      UUID pendingQualityStatusId,
      String pendingQualityStatusCode,
      String pendingQualityStatusName,
      UUID lotId,
      @Schema(nullable = true) String lotCode,
      BigDecimal sourceOnHand,
      PendingInspectionOrderSnapshot inspection,
      List<PendingInspectionDispositionRow> dispositions,
      List<PendingInspectionEventRow> events,
      List<PendingInspectionEntryRow> entries,
      BigDecimal netOnHandDelta,
      BigDecimal sellableBefore,
      BigDecimal sellableAfter,
      @Schema(nullable = true) String note) {}

  // ================================================================ 动作入参

  /**
   * 发起质检任务（6.4.2.1 质检单，trade 服务）。
   *
   * <p>质检模板必填：trade 侧的质检单按模板算抽样规则与检验项，
   * 没有模板就没有「检什么」这件事 —— 本服务不替它猜一个默认模板。
   */
  public record PendingInspectionRequestInspectionInput(
      @Schema(description = "6.4.2.1 质检模板（trade 服务，quality_inspection_template.id）") @NotNull
          UUID templateId,
      @Schema(nullable = true) @Size(max = 2000) String notes) {}

  /**
   * 录入质检结果。
   *
   * <p><b>没有数量字段</b>：质检结论的唯一来源是 trade 的 6.4.2.1 质检单，
   * 本服务在录入时**回读**那张单的三量（合格 / 不合格 / 让步接收）并冻结成本地快照。
   * 让操作员在这里再敲一遍数字，等于让同一个结论在两个服务各有一份 —— 迟早对不上。
   */
  public record PendingInspectionRecordResultInput(
      @Schema(nullable = true) @Size(max = 2000) String notes) {}

  /** 处置明细的一行（一个去向）。 */
  public record PendingInspectionDisposeLineInput(
      @Schema(
              description =
                  "去向：SELLABLE 转可售 / DEFECTIVE 转残次 / SCRAP 报废（离仓） / RETURN 退货（离仓）")
          @NotNull
          String dispositionType,
      @Schema(description = "该去向的数量") @NotNull @DecimalMin(value = "0", inclusive = false)
          BigDecimal quantity,
      @Schema(
              nullable = true,
              description =
                  "目标质量状态（inventory_quality_status.id）。SELLABLE / DEFECTIVE 行必填，"
                      + "且角色必须与去向一致；SCRAP / RETURN 行必须为空")
          UUID targetQualityStatusId,
      @Schema(
              nullable = true,
              description =
                  "目标库位（warehouse_location.id）。SELLABLE / DEFECTIVE 行必填，"
                      + "库位类型必须与质量角色匹配（可售→存储 / 残次→残次）；SCRAP / RETURN 行必须为空")
          UUID targetLocationId,
      @Schema(nullable = true) @Size(max = 2000) String note) {}

  /**
   * 处置决定。
   *
   * <p>「一批待检货必须被全部处置掉」：各行数量之和必须等于任务批量，
   * 服务层与数据库守卫各挡一道 —— 处置一半意味着另一半的结论永远没人给。
   */
  public record PendingInspectionDisposeInput(
      @Schema(description = "乐观锁版本号（单头 version），与当前不一致一律 409") @NotNull Long
          expectedVersion,
      @Schema(description = "处置明细。至少一行，各行数量之和必须等于任务批量") @NotEmpty @Valid
          List<PendingInspectionDisposeLineInput> lines,
      @Schema(nullable = true) @Size(max = 2000) String notes) {}

  /** 取消待检处理。理由必填。 */
  public record PendingInspectionCancelInput(
      @Schema(nullable = true) @Size(max = 200) String reason) {}

  // ================================================================ 下拉与参照

  /** 仓库参照。 */
  public record PendingInspectionWarehouseRef(
      UUID id, String code, String name, String countryCode, String timezone) {}

  /** 质量状态参照。 */
  public record PendingInspectionQualityRef(
      UUID id, String code, String name, String role, boolean enabled) {}

  /** 库位参照。 */
  public record PendingInspectionLocationRef(
      UUID id,
      UUID warehouseId,
      String warehouseCode,
      String code,
      String name,
      String type,
      boolean pickable,
      String status) {}

  /** 质检模板参照（来自 trade 6.4.2.1，只读）。 */
  public record PendingInspectionTemplateRef(
      UUID id, String code, String name, String inspectionType, String inspectionTypeLabel) {}

  /** 建单与处置需要的全部下拉。 */
  public record PendingInspectionOptions(
      List<PendingInspectionWarehouseRef> warehouses,
      List<PendingInspectionQualityRef> qualityStatuses,
      List<PendingInspectionLocationRef> locations,
      List<PendingInspectionTemplateRef> inspectionTemplates,
      boolean inspectionTemplatesAvailable,
      @Schema(nullable = true) String inspectionTemplatesNote,
      @Schema(nullable = true) String note) {}
}
