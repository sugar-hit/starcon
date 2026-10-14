package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 8.4.1.1 组装单模型（实物套装组装）。
 *
 * <p>蓝图 08 卷 8.4.1.1 的输入 / 输出逐条对应到这里：
 *
 * <ul>
 *   <li>输入「成品 SKU 与 BOM 版本」→ {@link AssemblyOrderCreateInput#productSkuId()} 与 {@code bomId /
 *       bomCode / bomVersionId / bomVersionNo}；<b>上游来源</b>由 {@code sourceSystem + referenceId}
 *       显式表达（加工单引用键，或仓库直接发起）。<br>
 *   <li>输入「组件领用量」→ {@link AssemblyComponentInput#requiredQuantity()}（BOM 规则 R7 冻结的应领量，本服务**不重算**）。
 *       <br>
 *   <li>输入「组装数量」→ {@link AssemblyOrderCreateInput#assemblyQuantity()}。<br>
 *   <li>输出「组装单 + 组件出库分录 + 成品入库分录」→ {@link AssemblyOrderCreated}（单据）、 {@link
 *       AssemblyOrderIssued#components()}（组件出库）、{@link AssemblyOrderIssued} 的 inbound
 *       分录（成品入库），三者挂在同一个 {@code inventoryTransactionId} 下。
 * </ul>
 *
 * <p><b>数量刻度</b>：列都是 {@code NUMERIC(19,6)}（对齐 catalog 规则 R7 的 6 位小数）， 但**库存余额是 {@code
 * NUMERIC(19,4)}**（V1 已定）。本模块**不允许取整换通过** —— 向下取整等于凭空吞掉材料，向上取整等于凭空多领。所以凡是要真正过账的量， 必须能被 4
 * 位小数精确表示，否则服务层显式拒绝并指出是哪一行。
 *
 * <p><b>虚拟套装进不来</b>：{@code productSkuId} 必须落在本服务 {@code sku_ref} 里。 catalog 3.5.1.1 的虚拟套装（{@code
 * bundle}）**不占 SKU 行**（V25 原文：「bundle 没有任何 sku 外键 —— 虚拟组合不占 SKU 行」），所以它在数据形状上就填不出一个合法的成品 SKU。
 */
public final class AssemblyOrderModels {

  private AssemblyOrderModels() {}

  /** 库存余额刻度：与 V1 的 inventory_balance / stock_bucket numeric(19,4) 对齐。 */
  static final int STOCK_SCALE = 4;

  /** 数量列刻度：与 V103 的 NUMERIC(19,6) 及 catalog 规则 R7 对齐。 */
  static final int QTY_SCALE = 6;

  /** 库存分录来源类型。inventory_transaction.ref_type 上限 32 字符。 */
  static final String REF_TYPE = "ASSEMBLY_ORDER";

  /** 上游来源：6.4.1.1 加工单（trade，按引用键追溯，不建外键）。 */
  static final String SOURCE_PROCESSING_ORDER = "TRADE_PROCESSING_ORDER";

  /** 上游来源：仓库现场直接按「成品 SKU + BOM 版本」发起。 */
  static final String SOURCE_DIRECT = "DIRECT";

  /** 一条组件领用明细（BOM 的一行在组装单上的冻结快照）。 */
  public record AssemblyComponentInput(
      @NotNull UUID skuId,
      @Schema(nullable = true, description = "catalog 3.5.2 bom_component 的逻辑 ID，仅作追溯")
          UUID bomComponentId,
      @Schema(
              description =
                  "按 BOM 规则 R7 冻结的应领量（单位用量 × 组装数量 × (1 + 损耗率/100)，6 位小数 "
                      + "HALF_UP）。本服务原样落库，不重算、不取整")
          @NotNull
          @Positive
          @Digits(integer = 13, fraction = 6)
          BigDecimal requiredQuantity,
      @Schema(nullable = true, description = "单位用量快照（每 1 个成品要多少），可空：只作追溯展示，不参与任何判定")
          @DecimalMax(value = "1000000000000", inclusive = false)
          BigDecimal unitQuantity,
      @Schema(nullable = true, description = "损耗率快照（百分数，5 = 5%），可空") @DecimalMax(value = "100")
          BigDecimal scrapRate,
      @Schema(nullable = true) @Size(max = 64) String role,
      @Schema(nullable = true) @Size(max = 200) String note) {}

  /**
   * 建组装单请求。
   *
   * <p>BOM 与加工单都在别的服务（catalog / trade），所以本请求带的是**引用键 + 冻结的组件明细**， 不是「帮我按 BOM 查一下」—— 那要求 warehouse
   * 跨服务读 catalog 的表，AGENTS.md 明令禁止。
   */
  public record AssemblyOrderCreateInput(
      @Schema(description = "成品 SKU（必须是 catalog 真实 SKU；虚拟套装没有 SKU 行，填不出来）") @NotNull
          UUID productSkuId,
      @Schema(description = "catalog 3.5.2 的 BOM 逻辑 ID（引用键，无外键）") @NotNull UUID bomId,
      @NotNull @Size(max = 64) String bomCode,
      @Schema(description = "catalog 3.5.2 的 BOM 版本逻辑 ID（引用键，无外键）") @NotNull UUID bomVersionId,
      @NotNull @Size(max = 32) String bomVersionNo,
      @Schema(description = "上游来源：TRADE_PROCESSING_ORDER = 来自 6.4.1.1 加工单；DIRECT = 仓库现场直接发起")
          @NotNull
          String sourceSystem,
      @Schema(nullable = true, description = "上游单据引用键；sourceSystem=DIRECT 时必须为空") UUID referenceId,
      @Schema(nullable = true) @Size(max = 64) String referenceNo,
      @NotNull UUID warehouseId,
      @NotNull UUID legalEntityId,
      @Schema(description = "组装数量（成品入库量）。必须能被库存刻度 4 位小数精确表示")
          @NotNull
          @Positive
          @Digits(integer = 13, fraction = 6)
          BigDecimal assemblyQuantity,
      @Schema(description = "组件出库库位；必填（库存的最小事实单位含库位）") @NotNull UUID outboundLocationId,
      @Schema(nullable = true) UUID outboundQualityStatusId,
      @NotNull UUID inboundLocationId,
      @Schema(nullable = true) UUID inboundQualityStatusId,
      @Schema(nullable = true) UUID outboundLotId,
      @Schema(nullable = true) UUID inboundLotId,
      @Schema(nullable = true) @Size(max = 2000) String notes,
      @NotEmpty @Size(max = 50) List<@NotNull @Valid AssemblyComponentInput> components) {}

  /** 建单结果。 */
  public record AssemblyOrderCreated(
      UUID orderId,
      String orderCode,
      String status,
      UUID productSkuId,
      @Digits(integer = 13, fraction = 6) BigDecimal assemblyQuantity,
      @Digits(integer = 13, fraction = 6) BigDecimal requiredComponentQuantity,
      int componentCount,
      String createdAt,
      String note) {}

  /** 执行组装请求（组件出库 + 成品入库在**同一个本地事务**内完成）。 */
  public record AssemblyOrderIssueInput(@Schema(nullable = true) @Size(max = 2000) String notes) {}

  /** 执行结果：一次事务的结果 + 组件出库分录 + 成品入库分录。 */
  public record AssemblyOrderIssued(
      UUID orderId,
      String orderCode,
      UUID inventoryTransactionId,
      String occurredAt,
      UUID productSkuId,
      @Digits(integer = 13, fraction = 6) BigDecimal assemblyQuantity,
      @Digits(integer = 13, fraction = 6) BigDecimal requiredComponentQuantity,
      @Digits(integer = 13, fraction = 6) BigDecimal issuedComponentQuantity,
      @Digits(integer = 13, fraction = 6) BigDecimal inboundProductQuantity,
      List<AssemblyComponentIssued> components,
      List<AssemblyOrderEntry> entries,
      int entryCount,
      String note) {}

  /** 组件出库结果一行（每个组件一条负向分录）。 */
  public record AssemblyComponentIssued(
      UUID skuId,
      String skuCode,
      @Digits(integer = 13, fraction = 6) BigDecimal requiredQuantity,
      @Digits(integer = 13, fraction = 6) BigDecimal issuedQuantity,
      UUID balanceId,
      UUID locationId,
      @Schema(nullable = true) UUID lotId) {}

  /** 取消组装单请求（只有 DRAFT 可取消；取消不动库存）。 */
  public record AssemblyOrderCancelInput(
      @Schema(description = "取消理由（必填：取消一张组装单必须能解释为什么）") @NotNull @Size(min = 1, max = 200)
          String reason) {}

  /** 组装单列表（真分页 + 全量汇总；汇总用标量子查询，不 join 展开）。 */
  public record AssemblyOrderPage(
      List<AssemblyOrderRow> items,
      int page,
      int size,
      long total,
      @Digits(integer = 13, fraction = 6) BigDecimal totalAssemblyQuantity,
      @Digits(integer = 13, fraction = 6) BigDecimal totalRequiredComponentQuantity,
      @Digits(integer = 13, fraction = 6) BigDecimal totalIssuedComponentQuantity,
      long draftCount,
      long completedCount,
      long cancelledCount,
      String sort) {}

  /** 组装单一行（列表口径：不含明细，避免 N+1 与笛卡尔积）。 */
  public record AssemblyOrderRow(
      UUID orderId,
      String orderCode,
      String status,
      UUID productSkuId,
      String productSkuCode,
      UUID bomId,
      String bomCode,
      UUID bomVersionId,
      String bomVersionNo,
      String sourceSystem,
      @Schema(nullable = true) UUID referenceId,
      @Schema(nullable = true) String referenceNo,
      UUID warehouseId,
      @Digits(integer = 13, fraction = 6) BigDecimal assemblyQuantity,
      @Digits(integer = 13, fraction = 6) BigDecimal requiredComponentQuantity,
      @Digits(integer = 13, fraction = 6) BigDecimal issuedComponentQuantity,
      @Digits(integer = 13, fraction = 6) BigDecimal inboundProductQuantity,
      int componentCount,
      @Schema(nullable = true) UUID inventoryTransactionId,
      @Schema(nullable = true) String issuedAt,
      @Schema(nullable = true) String cancelledAt,
      @Schema(nullable = true) String cancelReason,
      String createdAt,
      UUID createdBy,
      String notes) {}

  /** 组装单详情（单据 + 组件快照明细 + 账本分录）。 */
  public record AssemblyOrderDetail(
      AssemblyOrderRow order,
      List<AssemblyComponentRow> components,
      List<AssemblyOrderEntry> entries) {}

  /** 组件明细一行（BOM 的跨服务冻结快照）。 */
  public record AssemblyComponentRow(
      UUID id,
      UUID skuId,
      String skuCode,
      @Schema(nullable = true) UUID bomComponentId,
      @Schema(nullable = true) @Digits(integer = 13, fraction = 6) BigDecimal unitQuantity,
      @Schema(nullable = true) @Digits(integer = 13, fraction = 4) BigDecimal scrapRate,
      @Digits(integer = 13, fraction = 6) BigDecimal requiredQuantity,
      @Digits(integer = 13, fraction = 6) BigDecimal issuedQuantity,
      @Schema(nullable = true) UUID balanceId,
      @Schema(nullable = true) UUID locationId,
      @Schema(nullable = true) UUID lotId,
      String role,
      String note) {}

  /** 一条账本分录（inventory_entry 读出，负数 = 组件出库，正数 = 成品入库）。 */
  public record AssemblyOrderEntry(
      UUID entryId,
      UUID balanceId,
      UUID skuId,
      @Digits(integer = 13, fraction = 4) BigDecimal onHandDelta,
      @Digits(integer = 13, fraction = 4) BigDecimal reservedDelta,
      String direction) {}

  /** 能力声明：把跨服务边界、虚拟套装边界与未交付下游如实报出来。 */
  public record AssemblyCapabilities(
      String refType,
      boolean atomicComponentAndProductMovements,
      boolean supportsDirectInitiation,
      String bomBoundary,
      String virtualKitBoundary,
      List<UpstreamModule> upstreamModules,
      List<DownstreamModule> downstreamModules,
      String invariant,
      String note) {

    public record UpstreamModule(
        String moduleId, String moduleName, String service, boolean available, String usage) {}

    public record DownstreamModule(
        String moduleId, String moduleName, String service, boolean available, String effect) {}
  }
}
