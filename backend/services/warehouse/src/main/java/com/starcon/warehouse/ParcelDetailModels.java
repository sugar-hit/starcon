package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 10.2.2.1 包裹详情的传输模型。
 *
 * <p>蓝图原话：「单包裹视图：包裹内 SKU 与数量（parcel_item）、订单行对应关系、重量体积、
 * 面单、轨迹、交运信息。」输入：「parcel_id；关联订单行与轨迹。」输出：「包裹详情 + 操作入口。」 以及被点名的那条关系：「下游 = 10.3.1.1、12 财务（运费成本归集）。
 * **parcel_item 与订单行的对应关系是成本归集到行的基础**，缺了就只能算到订单级。」
 *
 * <p><b>本模块不拥有 parcel / parcel_item</b>，它是 8.3.3.2（warehouse，V121）的只读视图：
 * 一个字节都不改写那些表。本模块<b>只</b>新增一张 {@code parcel_item_sales_order_line}（V125），补上蓝图点名缺的那一环 ——
 * 明细项到<b>销售订单行</b>的显式关联（带归集分母与匹配口径）。
 *
 * <p><b>为什么「订单行」有两个含义，必须分清</b>：{@code parcel_item.outbound_line_id} 指向的是<b>出库单行</b>（warehouse
 * 自己的计划快照，发货链路上的真实关联）； 而 12 财务要摊运费的那一行是 <b>trade 服务的销售订单行</b>。只有把后者建起来，
 * 运费才能落到行；否则永远只能回答「这一单运费多少」。V125 建的正是这一环。
 *
 * <p><b>三段数据本服务真读得到、三段不读</b>：SKU 与数量、重量体积、订单行对应关系读本服务 的表（真实）；面单读 8.3.4.2 已交付的
 * print_job（真实）；<b>轨迹与交运信息在本服务不存在</b>， 由 {@link ParcelDetailUnavailable} 如实标 {@code
 * available=false} 并给出归属模块 —— 刻意不返回空列表冒充「这一票没有轨迹」。
 */
public final class ParcelDetailModels {

  private ParcelDetailModels() {}

  // ------------------------------------------------------------ 不可得能力的统一形状

  /**
   * 一段<b>当前不可得</b>的数据：是什么、归谁、为什么拿不到、去哪看。
   *
   * <p>与 8.3.1.2 的 {@code OutboundUnavailableLink} 同一套约定。刻意<b>不</b>用空列表或 0 表达「上游没交付」——
   * 那在界面上与「确实没有记录」长得一样，人会以为这票没有轨迹， 而不是「这段能力还没接入」。空值是事实，缺能力不是事实。
   */
  public record ParcelDetailUnavailable(
      String label,
      boolean available,
      /** 不可得时才有值：这段数据归哪个模块。 */
      @Schema(nullable = true) String ownerModule,
      @Schema(nullable = true) String href,
      String reason) {}

  // ------------------------------------------------------------ 包裹头

  /**
   * 包裹头：重量体积（实测口径）+ 包材 + 面单打印引用 + 所属出库单。
   *
   * @param volumeDivisorNote <b>刻意不返回体积重</b>：除数（6000/5000/…）是承运商渠道口径， 归 10
   *     物流。在本服务先乘一个除数就是替渠道定价（V121 §1.5 同一条理由）。这里只给实测 尺寸与实测体积，由下游按自己的除数去算。
   * @param measured 三个实测值是否齐全。半量状态由 V121 的 CHECK 拒绝，这里是如实回报。
   */
  public record ParcelDetailHeader(
      UUID parcelId,
      String parcelCode,
      int parcelNo,
      UUID packingOrderId,
      String packingOrderCode,
      String packingOrderStatus,
      UUID outboundOrderId,
      String outboundOrderCode,
      String outboundSourceType,
      String outboundSourceLabel,
      @Schema(nullable = true) UUID warehouseId,
      @Schema(nullable = true) String warehouseCode,
      @Schema(nullable = true) String warehouseName,
      @Schema(nullable = true) UUID materialId,
      @Schema(nullable = true) String materialCode,
      @Schema(nullable = true) String materialName,
      @Schema(nullable = true) UUID fillerMaterialId,
      @Schema(nullable = true) String fillerMaterialName,
      /** 实测外形三件（厘米）。未量 = null，<b>不是 0</b>（0 会被读成「量出来是 0 厘米」）。 */
      @Schema(nullable = true) BigDecimal outerLengthCm,
      @Schema(nullable = true) BigDecimal outerWidthCm,
      @Schema(nullable = true) BigDecimal outerHeightCm,
      @Schema(nullable = true) BigDecimal measuredGrossWeightG,
      BigDecimal tareWeightG,
      @Schema(nullable = true) BigDecimal netContentWeightG,
      @Schema(nullable = true) BigDecimal volumeCbm,
      boolean measured,
      @Schema(nullable = true) OffsetDateTime measuredAt,
      @Schema(nullable = true) UUID measuredBy,
      int itemCount,
      BigDecimal totalQuantity,
      @Schema(nullable = true) OffsetDateTime createdAt,
      String volumeDivisorNote) {}

  // ------------------------------------------------------------ 明细项

  /**
   * 包裹内的一条明细项：装了哪件货、几件、指回哪条复核基线行与哪条出库单行。
   *
   * @param salesOrderLines 该明细项关联到的销售订单行。**可能为空数组**（非 SALES 来源出库单 在 trade 侧没有销售订单行），此时 {@link
   *     ParcelDetailItem#orderLineNote} 说清原因。 空数组与「这段能力没接入」是两件事，靠本字段区分。
   */
  public record ParcelDetailItem(
      UUID parcelItemId,
      int itemNo,
      UUID skuId,
      String skuCode,
      String skuName,
      BigDecimal quantity,
      @Schema(nullable = true) UUID serialId,
      @Schema(nullable = true) String serialNumber,
      UUID recountLineId,
      UUID outboundLineId,
      int outboundLineNo,
      String declarationSource,
      String hsCode,
      String declaredItemName,
      String declaredOriginCountryCode,
      List<ParcelItemSalesOrderLine> salesOrderLines,
      String orderLineNote) {}

  /**
   * 包裹明细项 → 销售订单行的关联（V125 新表）。
   *
   * @param allocatedQuantity <b>归集分母</b>：本箱分给这一行的件数，恒等于该明细项的件数 （V125 的守卫钉死）。12 财务按它 / 重量 /
   *     体积三个口径各自加权。
   * @param matchBasis 匹配口径。{@code SKU_UNIQUE_ALIGNED} 是强关联（两边都按 (order, sku) 唯一键对齐），{@code
   *     SKU_NAME_FALLBACK} 是弱关联（uuid 对不上但编码一致）， 财务可据此决定要不要追加人工复核。
   * @param salesLineQuantity 订单侧<b>应发</b>件数快照。与 allocatedQuantity 不同是正常的
   *     （一行跨箱分装），两者用途不同，前者应发、后者本箱实装。
   */
  public record ParcelItemSalesOrderLine(
      UUID linkId,
      UUID salesOrderId,
      UUID salesOrderLineId,
      String salesOrderNo,
      int salesOrderLineNo,
      UUID skuId,
      String skuCode,
      BigDecimal allocatedQuantity,
      BigDecimal salesLineQuantity,
      String matchBasis,
      String matchBasisLabel,
      boolean strongMatch,
      @Schema(nullable = true) OffsetDateTime createdAt) {}

  // ------------------------------------------------------------ 面单

  /**
   * 面单（8.3.4.2 已交付的 print_job / print_target，同服务真实表）。
   *
   * @param available 是否已记过面单打印。没打印过 available=false 且其余字段为 null —— 那是事实（这一箱还没印），不是能力缺失。
   */
  public record ParcelDetailLabel(
      boolean available,
      @Schema(nullable = true) UUID printJobId,
      @Schema(nullable = true) String jobNo,
      @Schema(nullable = true) String jobStatus,
      @Schema(nullable = true) String jobStatusLabel,
      @Schema(nullable = true) Integer copies,
      @Schema(nullable = true) String targetType,
      @Schema(nullable = true) String externalRef,
      @Schema(nullable = true) String waybillNo,
      @Schema(nullable = true) String carrierCode,
      @Schema(nullable = true) String shipToName,
      @Schema(nullable = true) String shipToAddress,
      @Schema(nullable = true) String platformCode,
      @Schema(nullable = true) Integer targetPrintCount,
      @Schema(nullable = true) OffsetDateTime firstPrintedAt,
      @Schema(nullable = true) OffsetDateTime lastPrintedAt,
      int printCount,
      @Schema(nullable = true) OffsetDateTime labelPrintedAt,
      String note) {}

  // ------------------------------------------------------------ 成本归集

  /**
   * 成本归集基线（下游 12 财务）。
   *
   * <p>蓝图那句「parcel_item 与订单行的对应关系是成本归集到行的基础，缺了就只能算到订单级」 的落点。{@code allocatableToLine} 为 false
   * 时，运费只能归到<b>订单级</b>，并且必须 如实告知原因（哪几行没关联上），而不是默认按订单摊。
   *
   * @param matchedQuantity 已关联到销售订单行的件数合计；unmatchedQuantity 未关联上的件数合计。 两者之和恒等于 totalQuantity ——
   *     这个恒等式由服务层读回后断言。
   */
  public record ParcelCostAllocation(
      boolean allocatableToLine,
      BigDecimal totalQuantity,
      BigDecimal matchedQuantity,
      BigDecimal unmatchedQuantity,
      int itemCount,
      int linkedItemCount,
      int unlinkedItemCount,
      int distinctSalesOrderCount,
      int distinctSalesOrderLineCount,
      String fallbackLevel,
      @Schema(nullable = true) String fallbackReason,
      List<ParcelUnmatchedItem> unmatchedItems,
      String note) {}

  /** 未关联上销售订单行的一条明细项 + 原因（归集到订单级时要让人能追到具体是哪些货）。 */
  public record ParcelUnmatchedItem(
      UUID parcelItemId,
      int itemNo,
      String skuCode,
      String skuName,
      BigDecimal quantity,
      String reason,
      @Schema(nullable = true) String ownerModule) {}

  // ------------------------------------------------------------ 详情

  /**
   * 包裹详情（一次调用返回整屏）。
   *
   * <p>蓝图输出：「包裹详情 + 操作入口。」{@code actions} 是操作入口， {@code unavailable} 是三段本服务真读不到的数据逐段说明。
   */
  public record ParcelDetail(
      ParcelDetailHeader header,
      List<ParcelDetailItem> items,
      ParcelDetailLabel label,
      ParcelCostAllocation costAllocation,
      List<ParcelDetailAction> actions,
      List<ParcelDetailUnavailable> unavailable,
      List<String> notes) {}

  /**
   * 详情页操作入口。
   *
   * <p>本模块是<b>只读视图</b>，所以它自己不提供任何写操作入口 —— 入口指向真正做这件事的 作业模块（8.3.3.2 打包作业页 / 8.3.4.1 发货 / 10.3.1.1
   * 轨迹），详情页只给跳转与前置条件。
   */
  public record ParcelDetailAction(
      String action,
      String label,
      @Schema(nullable = true) String href,
      String ownerModule,
      String precondition,
      String note) {}

  // ------------------------------------------------------------ 能力声明

  /**
   * 详情页能力声明：逐段可用性 + 归集口径 + 四条必须写在界面上的说明。
   *
   * <p>前端据此渲染「未接入」的灰块与提示，而不是把六段都画成空的详情表。
   */
  public record ParcelDetailCapabilities(
      List<ParcelDetailUnavailable> sections,
      ParcelCostAllocationPolicy costAllocationPolicy,
      List<String> upstreamModules,
      List<String> downstreamModules,
      String note) {}

  /** 成本归集口径：能归到行还是只能归到订单级，以及那条基础是什么。 */
  public record ParcelCostAllocationPolicy(
      String basis,
      boolean lineLevelSupported,
      @Schema(nullable = true) String ownerModule,
      String reason) {}
}
