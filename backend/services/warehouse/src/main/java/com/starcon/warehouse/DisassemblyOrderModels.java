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
 * 8.4.2.1 拆解单模型（成品拆解回组件）。
 *
 * <p>蓝图 08 卷 8.4.2.1 的输入 / 输出逐条对应到这里：
 *
 * <ul>
 *   <li>输入「成品 SKU 与数量」→ {@link DisassemblyOrderCreateInput#productSkuId()} 与
 *       {@code disassemblyQuantity}；<b>上游来源</b>由 {@code sourceSystem + referenceId} 显式表达
 *       （8.4.1.1 组装单引用键 / 11 售后退件引用键 / 仓库现场直接发起）。<br>
 *   <li>输入「拆解去向（可用/待检/残次）」→ {@link DisassemblyComponentInput#destinationQualityStatusId()}
 *       与 {@link DisassemblyComponentInput#destinationLocationId()}，<b>逐行必填、没有默认值</b>。<br>
 *   <li>输出「拆解单 + 反向库存分录」→ {@link DisassemblyOrderCreated}（单据）、
 *       {@link DisassemblyOrderExecuted}（成品出库 + 组件入库分录），挂同一个
 *       {@code inventoryTransactionId}。</li>
 * </ul>
 *
 * <p><b>蓝图加粗句的落点</b>：「拆解结果的组件质量状态由接收方决定，<b>不能默认全部回可售</b>。」
 * 这条在本文件里表现为 {@code destinationQualityStatusId} <b>不带任何「不填就替你选」的兜底</b>，
 * 且刻意<b>不</b>放在单头上做整单默认 —— 同一张单里「面料回可售、螺丝回残次」是常态，
 * 单头一个默认值会把这个常态压平。
 *
 * <p><b>命名空间纪律</b>：本模块全部 record 带 {@code Disassembly} 前缀。Spring 的 springdoc 用
 * record 的<b>简单类名</b>做 OpenAPI {@code components.schemas} 的键，重名会被<b>静默覆盖</b> ——
 * 上一批车道就因为声明了与既有 {@code DutyTaxModels.ScopeOption} 同名的 record，
 * 悄悄改掉了别的模块的契约形状，而全量单测全绿。所以这里的每个类型名都带模块前缀，
 * 也刻意不叫 {@code UpstreamModule} / {@code DownstreamModule}（8.4.1.1 已占用那两个名字，
 * 嵌套 record 在 schema 里同样按简单名平铺）。
 *
 * <p><b>数量刻度</b>：列都是 {@code NUMERIC(19,6)}，但<b>库存余额是 {@code NUMERIC(19,4)}</b>。
 * 本模块<b>不允许取整换通过</b> —— 向下取整等于凭空吞掉组件，向上取整等于凭空多产组件，
 * 两种都恰好能让不变量「看起来成立」而物料对不上。所以凡是要真正过账的量必须能被 4 位小数
 * 精确表示，否则服务层显式拒绝并指出是哪一行。
 */
public final class DisassemblyOrderModels {

  private DisassemblyOrderModels() {}

  /** 库存余额刻度：与 V1 的 inventory_balance / stock_bucket numeric(19,4) 对齐。 */
  static final int STOCK_SCALE = 4;

  /** 数量列刻度：与 V108 的 NUMERIC(19,6) 对齐。 */
  static final int QTY_SCALE = 6;

  /** 库存分录来源类型。inventory_transaction.ref_type 上限 32 字符。 */
  static final String REF_TYPE = "DISASSEMBLY_ORDER";

  /** 上游来源：8.4.1.1 组装单（同服务，逻辑引用键，不建外键 —— 外键表达不了「上游必须已完成」）。 */
  static final String SOURCE_ASSEMBLY_ORDER = "ASSEMBLY_ORDER";

  /** 上游来源：11 售后退件单（跨服务，逻辑引用键，仅作追溯）。 */
  static final String SOURCE_AFTERSALES_RETURN = "AFTERSALES_RETURN";

  /** 上游来源：仓库现场直接发起（样品回收等没有上游单据的场景）。 */
  static final String SOURCE_DIRECT = "DIRECT";

  /**
   * 一条组件回收明细（BOM 的一行在拆解单上的反向冻结快照 + 这一行的入库去向）。
   *
   * <p>刻意与 8.4.1.1 的 {@code AssemblyComponentInput} <b>分开声明</b>：两者形状不同
   * （这里是 {@code recoveryRate} + {@code destination*}，那边是 {@code scrapRate} + 必填的成品入库），
   * 共用一个名字只会在 OpenAPI 里互相覆盖。
   */
  public record DisassemblyComponentInput(
      @NotNull UUID skuId,
      @Schema(nullable = true, description = "catalog 3.5.2 bom_component 的逻辑 ID，仅作追溯")
          UUID bomComponentId,
      @Schema(
              description =
                  "按「单位用量 × 拆解数量 × 回收率/100」冻结的应回量（6 位小数 HALF_UP）。"
                      + "本服务原样落库，不重算、不取整")
          @NotNull
          @Positive
          @Digits(integer = 13, fraction = 6)
          BigDecimal expectedQuantity,
      @Schema(
              nullable = true,
              description = "单位用量快照（每 1 个成品能拆出多少），可空：只作追溯展示，不参与任何判定")
          @DecimalMax(value = "1000000000000", inclusive = false)
          BigDecimal unitQuantity,
      @Schema(
              nullable = true,
              description =
                  "回收率快照（百分数，95 = 95%）。退件拆解与样品回收的实际损耗，默认 100（无损）")
          @DecimalMax(value = "100")
          BigDecimal recoveryRate,
      @Schema(
              description =
                  "组件入库去向的质量状态。**必填、没有默认值** —— 蓝图点名「拆解结果的组件质量状态"
                      + "由接收方决定，不能默认全部回可售」。取值落在 inventory_quality_status.role："
                      + "SELLABLE(可用) / PENDING_INSPECTION(待检) / DEFECTIVE(残次)")
          @NotNull
          UUID destinationQualityStatusId,
      @Schema(description = "组件入库库位（逐行：良品区 / 待检区 / 残次区可以分开）") @NotNull
          UUID destinationLocationId,
      @Schema(nullable = true) UUID destinationLotId,
      @Schema(nullable = true) @Size(max = 64) String role,
      @Schema(nullable = true) @Size(max = 200) String note) {}

  /**
   * 建拆解单请求。
   *
   * <p>BOM 在 catalog、售后退件单在 11 售后，都不是本服务的表。所以本请求带的是
   * <b>引用键 + 冻结的组件明细</b>，不是「帮我按 BOM 反推一下」—— 那要求 warehouse
   * 跨服务读 catalog 的表，AGENTS.md 明令禁止。
   */
  public record DisassemblyOrderCreateInput(
      @Schema(description = "被拆的成品 SKU（必须是本服务 sku_ref 里登记过的真实 SKU）")
          @NotNull
          UUID productSkuId,
      @Schema(description = "catalog 3.5.2 的 BOM 逻辑 ID（引用键，无外键）") @NotNull UUID bomId,
      @NotNull @Size(max = 64) String bomCode,
      @Schema(description = "catalog 3.5.2 的 BOM 版本逻辑 ID（引用键，无外键）") @NotNull
          UUID bomVersionId,
      @NotNull @Size(max = 32) String bomVersionNo,
      @Schema(
              description =
                  "上游来源：ASSEMBLY_ORDER = 来自 8.4.1.1 组装单；AFTERSALES_RETURN = 来自 11 售后退件；"
                      + "DIRECT = 仓库现场直接发起（样品回收等）")
          @NotNull
          String sourceSystem,
      @Schema(nullable = true, description = "上游单据引用键；sourceSystem=DIRECT 时必须为空")
          UUID referenceId,
      @Schema(nullable = true) @Size(max = 64) String referenceNo,
      @NotNull UUID warehouseId,
      @NotNull UUID legalEntityId,
      @Schema(description = "拆解数量（成品出库量）。必须能被库存刻度 4 位小数精确表示")
          @NotNull
          @Positive
          @Digits(integer = 13, fraction = 6)
          BigDecimal disassemblyQuantity,
      @Schema(description = "成品出库库位；必填（库存的最小事实单位含库位）") @NotNull
          UUID outboundLocationId,
      @Schema(nullable = true) UUID outboundQualityStatusId,
      @Schema(nullable = true) UUID outboundLotId,
      @Schema(nullable = true) @Size(max = 2000) String notes,
      @NotEmpty @Size(max = 50) List<@NotNull @Valid DisassemblyComponentInput> components) {}

  /** 建单结果。 */
  public record DisassemblyOrderCreated(
      UUID orderId,
      String orderCode,
      String status,
      UUID productSkuId,
      @Digits(integer = 13, fraction = 6) BigDecimal disassemblyQuantity,
      @Digits(integer = 13, fraction = 6) BigDecimal expectedComponentQuantity,
      int componentCount,
      String createdAt,
      String note) {}

  /** 执行拆解请求（成品出库 + 组件入库在**同一个本地事务**内完成）。 */
  public record DisassemblyOrderExecuteInput(
      @Schema(nullable = true) @Size(max = 2000) String notes) {}

  /** 执行结果：一个事务的结果 + 成品出库分录 + 组件入库分录。 */
  public record DisassemblyOrderExecuted(
      UUID orderId,
      String orderCode,
      UUID inventoryTransactionId,
      String occurredAt,
      UUID productSkuId,
      @Digits(integer = 13, fraction = 6) BigDecimal disassemblyQuantity,
      @Digits(integer = 13, fraction = 6) BigDecimal outboundProductQuantity,
      @Digits(integer = 13, fraction = 6) BigDecimal expectedComponentQuantity,
      @Digits(integer = 13, fraction = 6) BigDecimal recoveredComponentQuantity,
      List<DisassemblyComponentRecovered> components,
      List<DisassemblyOrderEntry> entries,
      int entryCount,
      String note) {}

  /** 一条组件回收结果（每个组件一行正向分录，含它的去向）。 */
  public record DisassemblyComponentRecovered(
      UUID skuId,
      String skuCode,
      @Digits(integer = 13, fraction = 6) BigDecimal expectedQuantity,
      @Digits(integer = 13, fraction = 6) BigDecimal recoveredQuantity,
      UUID destinationQualityStatusId,
      String destinationQualityCode,
      String destinationQualityRole,
      UUID destinationLocationId,
      @Schema(nullable = true) UUID destinationLotId,
      UUID balanceId) {}

  /** 取消拆解单请求（只有 DRAFT 可取消；取消不动库存）。 */
  public record DisassemblyOrderCancelInput(
      @Schema(description = "取消理由（必填：取消一张拆解单必须能解释为什么）")
          @NotNull
          @Size(min = 1, max = 200)
          String reason) {}

  /**
   * 一档去向的分布（列表页的汇总口径）。
   *
   * <p>单独一条查询算出来，<b>不 join 进分页查询</b>：join 进来会让「单头一行 × N 条组件」
   * 相乘，于是合计被放大 N 倍 —— 而这类页面最常见的 bug 恰恰是数字对不上且静态检查全绿。
   *
   * <p>它的存在理由是蓝图加粗句：拆解去向必须**看得见**。看不到去向分布，
   * 「不能默认全部回可售」就只是一句写在文档里的话。
   */
  public record DisassemblyDestinationStat(
      String qualityStatusId,
      String qualityCode,
      String qualityRole,
      long lineCount,
      @Digits(integer = 13, fraction = 6) BigDecimal expectedQuantity,
      @Digits(integer = 13, fraction = 6) BigDecimal recoveredQuantity) {}

  /** 拆解单列表（真分页 + 全量汇总 + 去向分布；汇总用标量子查询，不 join 展开）。 */
  public record DisassemblyOrderPage(
      List<DisassemblyOrderRow> items,
      int page,
      int size,
      long total,
      @Digits(integer = 13, fraction = 6) BigDecimal totalDisassemblyQuantity,
      @Digits(integer = 13, fraction = 6) BigDecimal totalExpectedComponentQuantity,
      @Digits(integer = 13, fraction = 6) BigDecimal totalRecoveredComponentQuantity,
      long draftCount,
      long completedCount,
      long cancelledCount,
      List<DisassemblyDestinationStat> destinations,
      String sort) {}

  /** 拆解单一行（列表口径：不含明细，避免 N+1 与笛卡尔积）。 */
  public record DisassemblyOrderRow(
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
      @Digits(integer = 13, fraction = 6) BigDecimal disassemblyQuantity,
      @Digits(integer = 13, fraction = 6) BigDecimal expectedComponentQuantity,
      @Digits(integer = 13, fraction = 6) BigDecimal recoveredComponentQuantity,
      @Digits(integer = 13, fraction = 6) BigDecimal outboundProductQuantity,
      int componentCount,
      @Schema(nullable = true) UUID inventoryTransactionId,
      @Schema(nullable = true) String executedAt,
      @Schema(nullable = true) String cancelledAt,
      @Schema(nullable = true) String cancelReason,
      String createdAt,
      UUID createdBy,
      String notes) {}

  /** 拆解单详情（单据 + 组件反向快照明细 + 账本分录）。 */
  public record DisassemblyOrderDetail(
      DisassemblyOrderRow order,
      List<DisassemblyComponentRow> components,
      List<DisassemblyOrderEntry> entries) {}

  /** 组件明细一行（BOM 的跨服务反向冻结快照 + 逐行去向）。 */
  public record DisassemblyComponentRow(
      UUID id,
      UUID skuId,
      String skuCode,
      @Schema(nullable = true) UUID bomComponentId,
      @Schema(nullable = true) @Digits(integer = 13, fraction = 6) BigDecimal unitQuantity,
      @Schema(nullable = true) @Digits(integer = 13, fraction = 4) BigDecimal recoveryRate,
      @Digits(integer = 13, fraction = 6) BigDecimal expectedQuantity,
      @Digits(integer = 13, fraction = 6) BigDecimal recoveredQuantity,
      UUID destinationQualityStatusId,
      String destinationQualityCode,
      String destinationQualityRole,
      UUID destinationLocationId,
      @Schema(nullable = true) UUID destinationLotId,
      @Schema(nullable = true) UUID balanceId,
      String role,
      String note) {}

  /** 一条账本分录（inventory_entry 读出，负数 = 成品出库，正数 = 组件入库）。 */
  public record DisassemblyOrderEntry(
      UUID entryId,
      UUID balanceId,
      UUID skuId,
      @Digits(integer = 13, fraction = 4) BigDecimal onHandDelta,
      @Digits(integer = 13, fraction = 4) BigDecimal reservedDelta,
      String direction) {}

  /**
   * 能力声明：把跨服务边界、去向必填这条硬约束与未交付下游如实报出来。
   *
   * <p>嵌套的上下游类型刻意叫 {@code DisassemblyUpstreamModule} / {@code DisassemblyDownstreamModule}：
   * 嵌套 record 在 OpenAPI schema 里同样按<b>简单类名平铺</b>，而
   * {@code UpstreamModule} / {@code DownstreamModule} 已被 8.4.1.1 占用。
   */
  public record DisassemblyCapabilities(
      String refType,
      boolean atomicProductAndComponentMovements,
      boolean supportsDirectInitiation,
      String bomBoundary,
      String upstreamBoundary,
      String qualityDestinationRule,
      List<DisassemblyUpstreamModule> upstreamModules,
      List<DisassemblyDownstreamModule> downstreamModules,
      String invariant,
      String note) {

    public record DisassemblyUpstreamModule(
        String moduleId, String moduleName, String service, boolean available, String usage) {}

    public record DisassemblyDownstreamModule(
        String moduleId, String moduleName, String service, boolean available, String effect) {}
  }
}