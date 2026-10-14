package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 8.3.3.2 打包与称重的传输模型。
 *
 * <p>蓝图 08 卷 8.3.3.2 原文：「<b>打包作业：选择包材、填充物、打印面单、称重与体积测量、记录包装尺寸（用于体积重与平台申报）。</b>
 * 输入：复核结果；包材选择；称重数据；申报信息（3.7.2 物理属性）。
 * 输出：包裹记录（parcel + parcel_item）+ 实测重量体积。」
 * 关系段：「上游 = 8.3.3.1；下游 = 8.3.4.1 发货、10 物流（取重算费）。
 * <b>包裹必须含明细项</b>（哪些 SKU、多少件），只有重量和运单号无法追溯与对账。」
 *
 * <p><b>命名空间：全部 {@code Packing} / {@code Parcel} / {@code Material} 前缀。</b>
 * springdoc 用 record 的<b>简单类名</b>做 {@code components.schemas} 的键，重名会被
 * <b>静默覆盖</b>（别人的字段形状被改掉、单测全绿、只有跨服务契约比对才红）。
 * 开工前 grep 过 backend 全部 Java 源码，三个前缀下无同名类型。
 *
 * <p><b>五个刻意不给默认值的地方：</b>
 *
 * <ul>
 *   <li><b>打包完成 ≠ 已发货。</b>{@link PackingStatus} 四态里没有「已发货」：
 *       打包完成只是把包裹交给 8.3.4.1（运单号与承运渠道是下游的事），
 *       与 8.3.3.1「复核通过 ≠ 已发货」同一纪律。</li>
 *   <li><b>没量 ≠ 量出来是 0。</b>{@code outerLengthCm} / {@code measuredGrossWeightG}
 *       / {@code volumeCbm} 未测时返回 <b>null</b>，不返回 0 —— 0 会被读成
 *       「这个箱子重 0 克」，而真实含义是「还没上秤」。</li>
 *   <li><b>净重与体积是派生量，界面不自己算。</b>{@code netContentWeightG} 与
 *       {@code volumeCbm} 由 DB 触发器从毛重与三边算出（V121），前端展示即可，
 *       在页面再乘一遍会与账上的数差一位。</li>
 *   <li><b>体积重不在本模块算。</b>{@code divisor} 一律不出现：除数（6000/5000/…）
 *       是承运商渠道口径，归 10 物流。这里给的是实测三边与实测体积，
 *       10 物流按自己的除数去算 —— 在此先乘一个除数就是替渠道定价。</li>
 *   <li><b>「高价值」与金额在本模块一律不出现。</b>出库单上没有金额列（V72 §1），
 *       申报货值归 9/10/报关侧，本模块的重量与体积<b>不是</b>金额也不参与计价。</li>
 * </ul>
 */
public final class PackingModels {

  private PackingModels() {}

  // ------------------------------------------------------------ 词表

  /**
   * 打包单状态。四态：打包中 / 已打包 / 打包失败 / 已取消。
   *
   * <p><b>没有「已发货」与「已交接」</b> —— 那是 8.3.4.1 的事（与 8.3.3.1
   * 「复核通过 ≠ 已发货」同一纪律）。
   */
  public enum PackingStatus {
    /** 打包中：可以建包裹、装明细、称重、量尺寸、记面单打印。 */
    OPEN("打包中", false),
    /** 已打包：每个包裹都有明细、都称过重量量过尺寸，且每行基线的实装件数恰好等于应发。 */
    PACKED("已打包", true),
    /** 打包失败：打包时发现缺件/破损。出库单退回 PENDING_RECHECK 重新复核。 */
    FAILED("打包失败", true),
    /** 已取消：放弃这次打包（退回打包区流程重来）。库存不动。 */
    CANCELLED("已取消", true);

    private final String label;
    private final boolean terminal;

    PackingStatus(String label, boolean terminal) {
      this.label = label;
      this.terminal = terminal;
    }

    public String label() {
      return label;
    }

    public boolean terminal() {
      return this.terminal;
    }

    /** 能不能继续装箱。只有「打包中」可以。 */
    public boolean acceptsWork() {
      return this == OPEN;
    }
  }

  /** 物料角色。蓝图「选择包材、填充物」是两个东西。 */
  public enum MaterialRole {
    /** 包材：算进毛重与外形尺寸（净重 = 毛重 − 包材自重）。 */
    PACKAGING("包材"),
    /** 填充物：气泡柱/泡沫/纸团，只影响防护，不进净重口径、不构成外形。 */
    CUSHIONING("填充物");

    private final String label;

    MaterialRole(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 外形种类（现场按形状挑，不是自由文本）。 */
  public enum MaterialType {
    CARTON("纸箱"),
    ENVELOPE("快递袋"),
    BAG("编织袋"),
    BOX("盒子"),
    BUBBLE("气泡柱"),
    PAPER("纸团/缓冲纸"),
    FOAM("泡沫"),
    OTHER("其他");

    private final String label;

    MaterialType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 打包流水事件类型。 */
  public enum PackingEventType {
    OPENED("开打包单"),
    PACKAGE_ADDED("建包裹"),
    ITEM_ADDED("装明细"),
    MEASURED("称重与量尺寸"),
    LABEL_PRINTED("记面单打印"),
    PACKED("打包完成"),
    FAILED("打包失败"),
    CANCELLED("取消打包");

    private final String label;

    PackingEventType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 明细项的申报信息来源。
   *
   * <p>蓝图输入写「申报信息（<b>3.7.2 物理属性</b>）」，但 3.7.2.1 的 SKU 物理档案属
   * <b>catalog</b>（V6 sku_detail 的 length_mm/width_mm/height_mm/net_weight_g，
   * V28 安全属性），本服务跨服务读表被模块边界纪律禁止，且该模块尚未交付读取端口。
   * 所以本服务只快照<b>自己真读得到</b>的 customs_declaration_line（V85，同服务），
   * 取不到就如实记 NOT_FOUND —— <b>绝不猜一个 HS 编码填上去</b>。
   */
  public enum DeclarationSource {
    /** 取自本服务的报关单明细（V85 customs_declaration_line）。 */
    CUSTOMS_DECLARATION_LINE("取自报关单明细"),
    /** 本租户没有这个 SKU 的报关明细：如实留空，不编。 */
    NOT_FOUND("本服务无报关明细（3.7.2 物理属性属 catalog，未接入）");

    private final String label;

    DeclarationSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ------------------------------------------------------------ 入参

  /**
   * 建包材 / 填充物入参。
   *
   * @param materialCode 编码（现场按名字挑，按编码对账，两列都必填非空）
   * @param outerLengthCm 外形长（厘米）—— 包裹实测三边不许超出它（留 5mm 装配公差）
   * @param tareWeightG 包材自重（克），净重 = 实测毛重 − 它；填充物必须为 0
   * @param maxGrossWeightG 承重上限（克），0 = 未设上限（不是「承重无限」）
   */
  public record PackingMaterialInput(
      String materialCode,
      String name,
      String materialRole,
      String materialType,
      @Schema(nullable = true) UUID warehouseId,
      String outerLengthCm,
      String outerWidthCm,
      String outerHeightCm,
      @Schema(nullable = true) String tareWeightG,
      @Schema(nullable = true) String maxGrossWeightG) {}

  /** 停用包材入参。原因必填（包材口径的历史必须可追）。 */
  public record PackingMaterialRetireInput(String reason) {}

  /**
   * 开一张打包单入参。
   *
   * @param recountSessionId 上游 8.3.3.1 的复核单，<b>必须已 PASSED</b>
   * @param packerUserId 打包人（可空，稍后完成时补；完成时必须有）
   */
  public record PackingOpenInput(
      UUID recountSessionId, @Schema(nullable = true) UUID packerUserId,
      @Schema(nullable = true) String note) {}

  /**
   * 建包裹入参（选择包材 + 填充物）。
   *
   * @param materialId 包材（角色必须是 PACKAGING）
   * @param fillerMaterialId 填充物（角色必须是 CUSHIONING，可空）
   * @param parcelNo 箱序号；不传则自动接在当前最大箱号之后
   */
  public record ParcelAddInput(
      UUID materialId,
      @Schema(nullable = true) UUID fillerMaterialId,
      @Schema(nullable = true) Integer parcelNo,
      @Schema(nullable = true) String note) {}

  /**
   * 装一件明细入参。
   *
   * @param recountLineId 来源复核基线行 —— <b>必填</b>：没有来源行的明细就是凭空装进去的东西
   * @param quantity 件数；逐件核对的基线行必须一行一号（带 serialId，件数恒为 1）
   * @param serialId 序列号件的单件序列号（可空）
   */
  public record ParcelItemInput(
      UUID recountLineId,
      String quantity,
      @Schema(nullable = true) UUID serialId,
      @Schema(nullable = true) String note) {}

  /**
   * 称重与量尺寸入参（实测重量体积 —— 本模块的核心产出）。
   *
   * @param measuredGrossWeightG 连箱带货的实测毛重（克）
   * @param outerLengthCm / outerWidthCm / outerHeightCm 装好之后量的三边（厘米）
   * @param measuredAt 上秤时刻（PDA 时钟），不传取当前时刻
   */
  public record ParcelMeasureInput(
      String measuredGrossWeightG,
      String outerLengthCm,
      String outerWidthCm,
      String outerHeightCm,
      @Schema(nullable = true) String measuredAt,
      @Schema(nullable = true) String note) {}

  /**
   * 记面单打印入参。
   *
   * <p>只<b>引用</b> 8.3.4.2 已交付的 print_job，不在本模块渲染 ZPL：
   * 面单内容与打印次数的权威是 V73 的 print_job / label_print_log。
   */
  public record ParcelLabelInput(
      UUID labelPrintJobId,
      @Schema(nullable = true) String printedAt,
      @Schema(nullable = true) String note) {}

  /** 打包完成入参。{@code packerUserId} 不传则用开单时的人。 */
  public record PackingCompleteInput(
      @Schema(nullable = true) UUID packerUserId, @Schema(nullable = true) String note) {}

  /** 打包失败入参。原因必填（缺件？破损？）。 */
  public record PackingFailInput(String reason) {}

  /** 取消打包入参。原因必填。 */
  public record PackingCancelInput(String reason) {}

  // ------------------------------------------------------------ 出参

  /**
   * 能力与边界声明。
   *
   * <p>刻意把三段「拿不到 / 不该由本模块算」的事<b>显式报出来</b>：
   * 体积重除数（归 10 物流）、3.7.2 SKU 物理属性（归 catalog，未接入）、
   * 运单号与承运渠道（归 8.3.4.1）。空的包裹列表会被读成「这单没有包裹」，
   * 而不是「这段能力还没接入」—— 与 8.3.1.2 已建立的约定一致。
   */
  public record PackingCapabilities(
      List<String> responsibilities,
      List<String> statuses,
      List<String> materialRoles,
      List<String> materialTypes,
      List<String> declarationSources,
      String weightUnit,
      String dimensionUnit,
      String volumeUnit,
      String weightBasis,
      String dimensionBasis,
      String volumetricWeightBasis,
      String declaredInformationBasis,
      boolean touchesInventory,
      String measureGuardBasis,
      List<UnavailableLink> unavailable,
      String upstreamModule,
      List<String> downstreamModules) {}

  /** 本模块尚未接入 / 不由本模块负责的相邻能力（照 8.3.1.2 的灰块约定）。 */
  public record UnavailableLink(String capability, String ownerModule, String href, String reason) {}

  /** 包材列表行。 */
  public record PackingMaterialRow(
      UUID id,
      String materialCode,
      String name,
      String materialRole,
      String materialRoleLabel,
      String materialType,
      String materialTypeLabel,
      UUID warehouseId,
      String outerLengthCm,
      String outerWidthCm,
      String outerHeightCm,
      String tareWeightG,
      String maxGrossWeightG,
      String status,
      OffsetDateTime createdAt,
      OffsetDateTime retiredAt) {}

  /** 包材分页页。 */
  public record PackingMaterialPage(
      List<PackingMaterialRow> items, int page, int size, long total) {}

  /** 包材详情：包材本身 + 被用过多少次（现算，不是存的列）。 */
  public record PackingMaterialDetail(PackingMaterialRow material, long parcelCount, long fillerCount) {}

  /** 开单 / 完成 / 失败 / 取消之后返回的单头读数。 */
  public record PackingCreated(
      UUID id,
      String packingCode,
      UUID recountSessionId,
      String recountSessionCode,
      UUID outboundOrderId,
      String outboundOrderCode,
      UUID warehouseId,
      String status,
      String statusLabel,
      Integer baselineLineCount,
      String baselineQuantity,
      Integer packedLineCount,
      String packedQuantity,
      Integer parcelCount,
      Integer parcelItemCount,
      Integer measuredParcelCount,
      String totalGrossWeightG,
      String totalTareWeightG,
      String totalVolumeCbm,
      String packingBasis,
      OffsetDateTime startedAt,
      OffsetDateTime completedAt) {}

  /** 打包单列表行。 */
  public record PackingRow(
      UUID id,
      String packingCode,
      UUID recountSessionId,
      String recountSessionCode,
      UUID outboundOrderId,
      String outboundOrderCode,
      String outboundOrderStatus,
      String outboundOrderStatusLabel,
      UUID warehouseId,
      String warehouseCode,
      String status,
      String statusLabel,
      Integer baselineLineCount,
      String baselineQuantity,
      Integer packedLineCount,
      String packedQuantity,
      Integer parcelCount,
      Integer parcelItemCount,
      Integer measuredParcelCount,
      String totalGrossWeightG,
      String totalVolumeCbm,
      UUID packerUserId,
      String packingBasis,
      OffsetDateTime startedAt,
      OffsetDateTime completedAt) {}

  /** 打包单分页页。汇总不受筛选影响 —— 与 8.3.2.1 / 8.3.2.2 / 8.3.3.1 同一纪律。 */
  public record PackingPage(List<PackingRow> items, int page, int size, long total, PackingSummary summary) {}

  /** 打包作业台汇总。 */
  public record PackingSummary(
      long totalOrders,
      long openOrders,
      long packedOrders,
      long failedOrders,
      long cancelledOrders,
      long parcels,
      long unmeasuredParcels,
      String baselineQuantity,
      String packedQuantity,
      String totalGrossWeightG,
      String totalVolumeCbm) {}

  /**
   * 复核基线行 + 该行<b>已装</b>的件数（现算的差，不存列）。
   *
   * <p>{@code packedQuantity} 与 {@code expectedQuantity} 分开展示：
   * 「复核核过的还没全装进去」正是本模块最要紧的一条现场事实。
   */
  public record PackingBaselineRow(
      UUID id,
      Integer lineNo,
      UUID skuId,
      String skuCode,
      String skuName,
      String expectedQuantity,
      String packedQuantity,
      String remainingQuantity,
      boolean requiresSerial,
      boolean fullyPacked,
      Integer parcelCount) {}

  /** 包裹行（含实测重量体积与包材/填充物读数）。 */
  public record ParcelRow(
      UUID id,
      UUID packingOrderId,
      Integer parcelNo,
      String parcelCode,
      UUID materialId,
      String materialCode,
      String materialName,
      String materialRoleLabel,
      UUID fillerMaterialId,
      String fillerMaterialCode,
      String fillerMaterialName,
      String outerLengthCm,
      String outerWidthCm,
      String outerHeightCm,
      String measuredGrossWeightG,
      String tareWeightG,
      String netContentWeightG,
      String volumeCbm,
      boolean measured,
      OffsetDateTime measuredAt,
      UUID labelPrintJobId,
      Integer labelPrintCount,
      OffsetDateTime labelPrintedAt,
      Integer itemCount,
      String totalQuantity,
      OffsetDateTime createdAt) {}

  /** 包裹明细项（哪些 SKU、多少件 —— 蓝图点名要的那张表）。 */
  public record ParcelItemRow(
      UUID id,
      UUID parcelId,
      Integer itemNo,
      UUID recountLineId,
      UUID outboundLineId,
      UUID skuId,
      String skuCode,
      String skuName,
      String quantity,
      UUID serialId,
      String serialNumber,
      String declarationSource,
      String declarationSourceLabel,
      String hsCode,
      String declaredItemName,
      String declaredOriginCountryCode,
      OffsetDateTime createdAt) {}

  /** 打包流水（时间线）。 */
  public record PackingEventRow(
      UUID id,
      String eventType,
      String eventTypeLabel,
      String fromStatus,
      String toStatus,
      UUID parcelId,
      UUID actorUserId,
      OffsetDateTime occurredAt,
      String detail) {}

  /**
   * 打包单详情：一次调用返回整屏 = 单头 + 包裹 + 明细 + 基线对齐 + 时间线 + 边界声明。
   */
  public record PackingDetail(
      PackingRow order,
      List<ParcelRow> parcels,
      List<ParcelItemRow> items,
      List<PackingBaselineRow> baseline,
      List<PackingEventRow> timeline,
      List<UnavailableLink> unavailable,
      PackingCompletionReport completion) {}

  /**
   * 能不能打包完成 —— <b>唯一的判据来源</b>。
   *
   * <p>页面用 {@code blocking} 决定「完成」按钮的可点性，<b>不自己数行、不自己算重量</b>：
   * 那套数在前端再算一遍就会与账上的数差一位（与 8.3.3.1 的 blocking 同一纪律）。
   */
  public record PackingCompletionReport(
      boolean packable,
      List<String> blockingReasons,
      List<String> warnings,
      int parcelCount,
      int unmeasuredParcelCount,
      int emptyParcelCount,
      int baselineLineCount,
      String baselineQuantity,
      String packedQuantity,
      String shortfallQuantity) {}
}