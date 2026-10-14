package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 8.2.2.1 上架任务传输模型（收货暂存位 → 正式库位）。
 *
 * <p>蓝图 08 卷 8.2.2.1 的四问逐条对应到这里：
 *
 * <ul>
 *   <li>职责「把已收货物从收货暂存位上架到正式库位，生成上架任务派发给作业人员（支持 PDA/扫码）」
 *       → {@link PutawayTaskCreateInput}（单据）+ {@link PutawayTaskAssignInput}（派工）
 *       + {@link PutawayTaskScanInput}（PDA/扫码）。<br>
 *   <li>输入「待上架清单；<b>库位推荐或指定库位</b>；作业人员分配」
 *       → {@link PutawayTaskLineInput#targetLocationId()}（指定库位，可空 {@code allocationId}
 *       表示这次是照 8.1.2.2 已选定的决策建的任务）。<br>
 *   <li>输出「上架任务（待执行/执行中/完成）；<b>库存 bucket 位置变更</b>」
 *       → {@link PutawayTaskRow}（三态）+ {@link PutawayTaskScanned}（一次扫码的成对反向分录）。<br>
 *   <li>关系「上游 = 8.2.1.1、8.1.2.2；下游 = 8.3.2.1 拣货」→ {@link PutawayCapabilities} 的上下游表。
 * </ul>
 *
 * <p><b>蓝图加粗句的落点</b>：「<b>上架只改位置不改数量</b>，数量在收货时已确定。」
 * 这句话决定了三件事，逐件在这里有对应物：
 *
 * <ol>
 *   <li><b>数量不是本模块的输入自由变量</b>：{@link PutawayTaskLineInput} 里<b>没有</b>数量字段。
 *       应上架量由上游收货明细行冻结而来（{@code plannedQuantity} 是派生量），
 *       调用方只能「指定把哪一行搬到哪里」，不能「说搬多少」。<br>
 *   <li><b>状态只有三态</b>（待执行 / 执行中 / 完成），没有「部分入库」这种收货才有的形态 ——
 *       上架做一半叫「执行中」，不叫「部分完成」。<br>
 *   <li><b>扫码是位置变更事件</b>：{@link PutawayTaskScanRow} 一行对应**一对**分录
 *       （{@code inventoryEntryOutId} 源位 -Q / {@code inventoryEntryInId} 目标位 +Q），
 *       两者指向同一条 balance，净额恒为 0。
 * </ol>
 *
 * <p><b>命名空间纪律</b>：本模块全部 record 带 {@code Putaway} 前缀。springdoc 用 record 的
 * <b>简单类名</b>做 OpenAPI {@code components.schemas} 的键，重名会被<b>静默覆盖</b> ——
 * 仓库里已经有两次真实事故（{@code ScopeOption}、{@code LocationType} 撞名），
 * 表现为「单测全绿但别的模块契约形状被改」。同服务另有 8.4.2.1 的 {@code Disassembly*} 裸名，
 * 本模块刻意一个都不复用。
 */
public final class PutawayTaskModels {

  private PutawayTaskModels() {}

  // ================================================================ 能力声明

  /**
   * 能力声明：把「哪些输入真有依据」「哪条硬约束」「上下游可用性」显式报出来。
   *
   * <p>存在的理由与既有 warehouse 模块一致：让调用方从一次 GET 就能知道
   * 「能不能指定库位」「改不改数量」「8.3.2.1 拣货能不能直接消费我的结果」，
   * 而不是从 400 或空数组里猜。
   */
  public record PutawayCapabilities(
      String refType,
      /* 一次上架 = 一条 inventory_transaction + 每次扫码一对反向分录 */
      boolean atomicLocationMoves,
      /* 本模块不产生也不接受数量：应上架量由收货明细冻结 */
      boolean quantityIsNotAnInput,
      String upstreamBoundary,
      String allocationBoundary,
      String quantityBoundary,
      List<PutawayUpstreamModule> upstreamModules,
      List<PutawayDownstreamModule> downstreamModules,
      String invariant,
      String note) {

    public record PutawayUpstreamModule(
        String moduleId, String moduleName, String service, boolean available, String usage) {}

    public record PutawayDownstreamModule(
        String moduleId, String moduleName, String service, boolean available, String effect) {}
  }

  // ================================================================ 建单

  /**
   * 建上架任务。
   *
   * <p><b>刻意没有「数量」字段</b>（见类注释第 1 点）：要上架哪一行、要搬到哪，
   * 由调用方指明；搬多少由那张收货明细行的实收量决定。
   */
  public record PutawayTaskCreateInput(
      @Schema(description = "上游 8.2.1.1 收货单（同一服务，真外键；上架的货必须来自一张真实收货单）")
          @NotNull
          UUID receiptId,
      @Schema(
              nullable = true,
              description = "8.1.2.2 已选定/已释放的上架决策（location_allocation.id）。"
                  + "可空 = 人工直接指派库位 —— 蓝图原文是「库位推荐**或**指定库位」，两种都是合法形态")
          UUID allocationId,
      @Schema(description = "待上架明细。每一行 = 一条收货明细行 + 一个目标库位") @NotEmpty @Valid
          List<PutawayTaskLineInput> lines,
      @Schema(nullable = true) @Size(max = 2000) String notes) {}

  /**
   * 上架任务的一行。
   *
   * <p><b>没有数量字段</b>：应上架量取自收货明细行的实收量，在建任务时冻结成
   * {@code plannedQuantity}。收货方事后改单不会让在场的作业单凭空变形。
   */
  public record PutawayTaskLineInput(
      @Schema(description = "上游收货明细行（goods_receipt_line.id）：这批货是哪次实收的哪一行") @NotNull
          UUID receiptLineId,
      @Schema(
              description =
                  "目标库位（warehouse_location.id）。质量状态决定它的**类型**："
                      + "SELLABLE→STORAGE / PENDING_INSPECTION→QUARANTINE / DEFECTIVE→DEFECTIVE，"
                      + "这是 8.1.2.2 的硬规则，不是排序偏好")
          @NotNull
          UUID targetLocationId,
      @Schema(
              nullable = true,
              description = "批次。非批次管理品留空；留空会按「无批次」定位源位库存行")
          UUID lotId,
      @Schema(
              nullable = true,
              description = "这批货要占的容量（体积单位）。**可空 = 没量过**，"
                  + "不是 0 ——「没量过」与「不占地方」是两件事，折成 0 会让没量过的批次凭空通过容量校验")
          @Digits(integer = 13, fraction = 6)
          BigDecimal requiredCapacity,
      @Schema(nullable = true, description = "这批货的重量。同 requiredCapacity：可空 = 没量过")
          @Digits(integer = 13, fraction = 6)
          BigDecimal requiredWeight,
      @Schema(nullable = true) @Size(max = 500) String note) {}

  /** 建单结果：任务已生成（PENDING 待执行），<b>货还没动</b>。 */
  public record PutawayTaskCreated(
      UUID taskId,
      String taskCode,
      String status,
      long detailLineCount,
      BigDecimal plannedQuantity,
      String receiptCode,
      OffsetDateTime createdAt,
      String note) {}

  // ================================================================ 派工 / 执行

  /** 派工：把任务指给一位作业人员（蓝图输入第三项「作业人员分配」）。 */
  public record PutawayTaskAssignInput(
      @NotNull UUID assigneeId, @Schema(nullable = true) @Size(max = 500) String note) {}

  /** 开始作业：PENDING → IN_PROGRESS。记录是谁开始搬的（人效核算 8.5.1.1 的事实来源之一）。 */
  public record PutawayTaskStartInput(@Schema(nullable = true) @Size(max = 500) String note) {}

  /**
   * 一次 PDA 扫码上架。
   *
   * <p>{@code quantity} 是<b>本次</b>搬的量而不是「最终要搬的量」：PDA 作业一张单可能扫三次，
   * 每次搬一部分，累计到 {@code plannedQuantity} 才允许完成任务。
   */
  public record PutawayTaskScanInput(
      @NotNull UUID lineId,
      @Schema(description = "本次搬走的量。累计不得超过该行的应上架量") @NotNull @Positive
          @Digits(integer = 13, fraction = 6)
          BigDecimal quantity,
      @Schema(
              nullable = true,
              description = "扫码读到的码（库位条码 / SKU 条码 / 单号）。"
                  + "同一张任务单内重复的码会被幂等索引挡下 —— PDA 网络抖动重发是现场常态，"
                  + "没有这道，同一批货会被搬两遍而「净额仍为 0」抓不到")
          @Size(max = 128)
          String scanReference,
      @Schema(description = "设备类型：PDA（蓝图点名）/ MOBILE / DESKTOP（后台补录）") @NotNull String
          deviceType,
      @Schema(
              nullable = true,
              description = "**业务时间**：作业员扫的那一下，不是提交入库的时刻。"
                  + "事后补录必须能填真实时刻，否则「这批货几点上的架」永远答不出来")
          OffsetDateTime scannedAt,
      @Schema(nullable = true) @Size(max = 500) String note) {}

  /**
   * 一次扫码的结果：<b>一对</b>反向分录。
   *
   * <p>{@code onHandDeltaOut} + {@code onHandDeltaIn} 的代数和恒为 0 ——
   * 这就是蓝图「上架只改位置不改数量」在单条扫码上的读数。
   */
  public record PutawayTaskScanned(
      UUID taskId,
      String taskCode,
      UUID lineId,
      UUID skuId,
      String skuCode,
      UUID sourceLocationId,
      UUID targetLocationId,
      BigDecimal scannedQuantity,
      BigDecimal linePlannedQuantity,
      BigDecimal linePutawayQuantity,
      boolean lineCompleted,
      UUID inventoryTransactionId,
      UUID inventoryEntryOutId,
      UUID inventoryEntryInId,
      BigDecimal onHandDeltaOut,
      BigDecimal onHandDeltaIn,
      OffsetDateTime scannedAt,
      String taskStatus,
      String taskStatusName,
      String note) {}

  /** 完成上架：IN_PROGRESS → COMPLETED。三条不变量在数据库层核对。 */
  public record PutawayTaskCompleteInput(@Schema(nullable = true) @Size(max = 500) String note) {}

  /** 取消任务（**不动库存**：货还在收货暂存位，从没搬走）。 */
  public record PutawayTaskCancelInput(
      @Schema(description = "取消原因必填：现场复盘的第一问就是「为什么撤这张单」") @NotNull @Size(min = 1, max = 200)
          String reason) {}

  // ================================================================ 读

  /** 任务行（列表与详情的单头）。 */
  public record PutawayTaskRow(
      UUID taskId,
      String taskCode,
      String status,
      String statusName,
      UUID receiptId,
      String receiptCode,
      UUID warehouseId,
      UUID legalEntityId,
      @Schema(nullable = true) UUID allocationId,
      @Schema(nullable = true) UUID assigneeId,
      @Schema(nullable = true) String assignedAt,
      @Schema(nullable = true) String startedAt,
      @Schema(nullable = true) String completedAt,
      @Schema(nullable = true) String cancelledAt,
      String cancelReason,
      long detailLineCount,
      BigDecimal plannedQuantity,
      BigDecimal putawayQuantity,
      long scanCount,
      @Schema(nullable = true) UUID inventoryTransactionId,
      String createdAt,
      UUID createdBy,
      String notes) {}

  /** 任务明细行。源位/目标位/两种数量都在这里，「只改位置不改数量」因此一眼可核。 */
  public record PutawayTaskLineRow(
      UUID lineId,
      UUID receiptLineId,
      UUID skuId,
      String skuCode,
      UUID qualityStatusId,
      String qualityCode,
      String qualityRole,
      UUID sourceLocationId,
      String sourceLocationCode,
      @Schema(nullable = true) UUID sourceBucketId,
      UUID targetLocationId,
      String targetLocationCode,
      String targetLocationType,
      @Schema(nullable = true) UUID targetBucketId,
      @Schema(nullable = true) UUID lotId,
      BigDecimal plannedQuantity,
      BigDecimal putawayQuantity,
      BigDecimal remainingQuantity,
      boolean completed,
      @Schema(nullable = true) BigDecimal requiredCapacity,
      @Schema(nullable = true) BigDecimal requiredWeight,
      boolean capacityEvaluated,
      boolean weightEvaluated,
      @Schema(nullable = true) UUID balanceId,
      @Schema(nullable = true) String completedAt,
      String note) {}

  /** PDA 扫码事件（append-only 台账）。 */
  public record PutawayTaskScanRow(
      UUID scanId,
      UUID lineId,
      BigDecimal scannedQuantity,
      UUID sourceLocationId,
      UUID targetLocationId,
      @Schema(nullable = true) String scanReference,
      String deviceType,
      String scannedAt,
      UUID scannerId,
      @Schema(nullable = true) UUID inventoryEntryOutId,
      @Schema(nullable = true) UUID inventoryEntryInId,
      String note) {}

  /** 账本分录（成对出现，净额恒 0）。 */
  public record PutawayTaskEntry(
      UUID entryId,
      UUID balanceId,
      BigDecimal onHandDelta,
      BigDecimal reservedDelta,
      String direction,
      @Schema(nullable = true) String locationCode) {}

  /** 目标库位分布：这张列表里多少货正在往哪些库位搬（库位利用率 8.5.2.1 关心的那个切面）。 */
  public record PutawayTargetStat(
      UUID locationId,
      String locationCode,
      String locationType,
      long lineCount,
      BigDecimal plannedQuantity,
      BigDecimal putawayQuantity) {}

  /** 列表汇总。三个合计列与三态计数都来自**标量子查询**，不受分页影响。 */
  public record PutawayTaskSummary(
      long total,
      BigDecimal plannedQuantity,
      BigDecimal putawayQuantity,
      long pendingCount,
      long inProgressCount,
      long completedCount,
      long cancelledCount,
      long unassignedCount) {}

  /** 列表：真分页 + 真筛选 + 排序白名单 + 汇总 + 目标库位分布。 */
  public record PutawayTaskPage(
      List<PutawayTaskRow> items,
      long total,
      int page,
      int size,
      PutawayTaskSummary summary,
      List<PutawayTargetStat> targetStats) {}

  /**
   * 详情：单头 + 明细（源位/目标位/两量）+ 扫码台账 + 账本分录。
   *
   * <p><b>{@code netOnHandDelta} 在任何时刻都必须是 0</b>，包括任务还没做完的时候：
   * 一次扫码写的就是一对反向分录，成对的两条加起来恒为 0。
   * 所以它<b>不是</b>「做完没做完」的判据 —— 那是 P2/P3 与 {@code putawayQuantity} 的事。
   * 它暴露的是另一种错：有人往这条上架交易里塞了一条不成对的分录。
   * 非 0 时服务层直接抛错而不是把它渲染成一个「看起来正常的数」。
   */
  public record PutawayTaskDetail(
      PutawayTaskRow task,
      List<PutawayTaskLineRow> lines,
      List<PutawayTaskScanRow> scans,
      List<PutawayTaskEntry> entries,
      BigDecimal netOnHandDelta,
      String note) {}
}