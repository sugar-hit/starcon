package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 6.4.1.1 加工单的**仓库侧消费**模型（组件出库 + 成品入库）。
 *
 * <p>蓝图 06 卷 6.4.1.1 关系段的加粗句是本模型的全部理由：
 * 「加工是『消耗组件产生成品』的库存动作，**必须在 warehouse 本地事务完成**，
 * 不能由 trade 直接改库存。」
 *
 * <p>所以加工单的**单据**在 trade（V97），而**库存动作**在这里：
 * 一次 {@code POST /warehouse/processing-orders} 就是一个本地事务，
 * 组件出库与成品入库要么都成、要么都回滚 —— 不允许出现
 * 「组件扣了成品没进」或反之。
 *
 * <p>数量口径：需求数量来自 trade 冻结的 BOM 规则 R7 结果
 * （单位用量 × 产量 × (1 + 损耗率/100)，6 位小数 HALF_UP），
 * 本服务**原样使用**，不重算、不取整 —— 取整是 3.5.2.2 与 06 采购建议的职责。
 * 但**库存余额的刻度是 numeric(19,4)**（V1 已定），
 * 所以入出库前会按 {@link #STOCK_SCALE} 对齐，避免 6 位小数被静默截断。
 */
public final class ProcessingOrderModels {

  private ProcessingOrderModels() {}

  /** 库存余额刻度：与 V1 的 inventory_balance / stock_bucket numeric(19,4) 对齐。 */
  static final int STOCK_SCALE = 4;

  /** 需求数量刻度：与 trade V97 的 numeric(19,6) 及 catalog 规则 R7 对齐。 */
  static final int QTY_SCALE = 6;

  /** 库存分录来源类型。inventory_transaction.ref_type 上限 32 字符。 */
  static final String REF_TYPE = "PROCESSING_ORDER";

  /** 一条组件领用明细。 */
  public record ComponentIssue(
      @NotNull UUID skuId,
      @Schema(description = "本次实发数量（不得超过加工单冻结的需求量）")
          @NotNull
          @Positive
          @Digits(integer = 13, fraction = 6)
          BigDecimal quantity,
      @Schema(nullable = true) @Size(max = 200) String note) {}

  /**
   * 加工单入库执行请求（**一个本地事务**）。
   *
   * <p>组件出库与成品入库在**同一个事务**里完成：任何一个环节失败（组件不足、
   * 库位不存在、质量状态非法）都会整体回滚，不留「扣了料没入货」的半截状态。
   */
  public record ProcessingOrderIssueInput(
      @Schema(
              description = "加工单 ID（trade V97 的逻辑 ID；跨服务不建外键，只作幂等锚点与追溯引用）")
          @NotNull
          UUID processingOrderId,
      @Schema(nullable = true) @Size(max = 64) String orderNo,
      @NotNull UUID warehouseId,
      @NotNull UUID legalEntityId,
      @Schema(nullable = true, description = "成品入库到哪个质量状态；不传则取本租户唯一启用的可售状态")
          UUID inboundQualityStatusId,
      @Schema(description = "成品 SKU（catalog 逻辑 ID，跨服务不建外键）") @NotNull UUID outputSkuId,
      @Schema(description = "本次入库的成品数量") @NotNull @Positive @Digits(integer = 13, fraction = 6)
          BigDecimal completedQuantity,
      @NotEmpty @Size(max = 50) List<@NotNull ComponentIssue> componentIssues,
      @Schema(nullable = true) UUID outboundLocationId,
      @Schema(nullable = true) UUID outboundQualityStatusId,
      @Schema(nullable = true) UUID inboundLocationId,
      @Schema(nullable = true) UUID outboundLotId,
      @Schema(nullable = true) UUID inboundLotId,
      @Schema(nullable = true) @Size(max = 2000) String notes) {}

  /** 执行结果：一次事务的结果 + 生成的分录凭据。 */
  public record ProcessingOrderIssueSaved(
      UUID processingOrderId,
      @Schema(nullable = true) String orderNo,
      UUID inventoryTransactionId,
      String occurredAt,
      UUID outputSkuId,
      @Digits(integer = 13, fraction = 6) BigDecimal completedQuantity,
      List<ComponentIssued> components,
      int entryCount,
      String note) {}

  /** 组件出库结果一行（每个组件一条负向分录）。 */
  public record ComponentIssued(
      UUID skuId,
      @Digits(integer = 13, fraction = 6) BigDecimal quantity,
      UUID balanceId,
      UUID locationId,
      @Schema(description = "按库存刻度（4 位小数）实际出库的量") @Digits(integer = 13, fraction = 4)
          BigDecimal postedQuantity) {}

  /** 加工单在仓库的执行记录查询（读口径：同一 ref_type 下有多少次执行）。 */
  public record ProcessingStockPage(
      List<ProcessingStockRow> items,
      int page,
      int size,
      long total,
      @Digits(integer = 13, fraction = 4) BigDecimal totalCompletedQuantity,
      @Digits(integer = 13, fraction = 4) BigDecimal totalIssuedQuantity) {}

  /** 一次执行的分录快照（inventory_transaction + inventory_entry 读出）。 */
  public record ProcessingStockRow(
      UUID inventoryTransactionId,
      UUID processingOrderId,
      @Schema(nullable = true) String orderNo,
      String occurredAt,
      UUID outputSkuId,
      @Digits(integer = 13, fraction = 4) BigDecimal completedQuantity,
      @Digits(integer = 13, fraction = 4) BigDecimal issuedQuantity,
      int componentCount,
      int entryCount,
      String notes,
      UUID actorId,
      List<ProcessingStockEntry> entries) {}

  /** 一条分录。 */
  public record ProcessingStockEntry(
      UUID entryId,
      UUID balanceId,
      UUID skuId,
      @Digits(integer = 13, fraction = 4) BigDecimal onHandDelta,
      @Digits(integer = 13, fraction = 4) BigDecimal reservedDelta) {}

  /** 能力声明：把「库存动作归谁」与未交付下游显式报出来。 */
  public record ProcessingStockCapabilities(
      String refType,
      boolean atomicWithComponentAndProductMovements,
      boolean supportsPartialComponentIssue,
      String upstreamNote,
      List<DownstreamGap> downstreamGaps,
      String note) {

    public record DownstreamGap(
        String moduleId, String moduleName, String service, boolean available, String effect) {}
  }

  /** 库存动作归属说明。 */
  public record StockMovementBoundary(
      String owner, String localTransaction, String tradeResponsibility, String note) {}
}
