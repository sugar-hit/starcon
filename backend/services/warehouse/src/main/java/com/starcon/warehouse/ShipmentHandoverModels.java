package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 8.3.4.1 发货交接的传输模型。
 *
 * <p>蓝图 08 卷 8.3.4.1 原文：「<b>发货交接：确认承运商与运单号、登记交接时间与交接人、扣减库存完成出库、
 * 触发物流轨迹与平台回传。</b>输入：包裹与运单号；承运渠道；交接确认。 输出：出库完成分录（预占转实扣）+ 发货事件 → 9 订单状态、10 轨迹、2 平台回写。」 关系段：「上游 =
 * 8.3.3.2；下游 = 7 库存、9 订单、10 物流、2 渠道回写。 <b>实发扣减与预占消费在同一事务</b>，避免预占悬空或重复扣减。」
 *
 * <p><b>命名空间：全部 {@code Handover} / {@code ShipmentHandover} 前缀。</b> springdoc 用 record
 * 的<b>简单类名</b>做 {@code components.schemas} 的键，重名会被 <b>静默覆盖</b>（别人的字段形状被改掉、单测全绿、只有跨服务契约比对才红）。 开工前
 * grep 过 backend 全部 Java 源码，本组前缀下无同名类型；刻意<b>不</b>复用 {@code PackingModels.ParcelRow} / {@code
 * ParcelItemRow}，那两个是 8.3.3.2 的形状。
 *
 * <p><b>五个刻意不给默认值的地方：
 *
 * <ul>
 *   <li><b>承运商与运单号是两个字段，不是一个。</b>承运渠道在 V72 的 {@code outbound_order.carrier_code} 上只是
 *       varchar(64)；运单号在本服务 <b>原本不存在</b>。只有承运商没有号 = 车来了但没人知道运的是哪一单； 只有号没有渠道 = 不知道谁运的。所以 {@code
 *       carrierCode} 与 {@code masterWaybillNo} 分开，且交接确认时两者都必须有。
 *   <li><b>「已交接」不等于「已送达」。</b>{@link HandoverStatus} 三态里没有「已签收」： 轨迹与签收归 10
 *       物流（尚未交付），本模块只认「货交出去了」这一个事实。
 *   <li><b>两个「预占量」是<b>两本账</b>，刻意分成两个字段。</b> {@code deductedQuantity} 是实扣件数；{@code
 *       reservedDeductedQuantity} 是其中 <b>实物预占侧</b>（{@code stock_bucket.reserved}，7.1.1.1）被释放的量；
 *       {@code reservations()} / {@code reservationLineCount} 是<b>承诺侧</b> （{@code
 *       reservation_allocation}，7.2.1.2）被消费的量。 两者不相等是常态甚至常态之外的常态：没有在册预占时 {@code
 *       reservedDeductedQuantity} 仍可能大于 0（实物被圈过但承诺账没建）， 反之承诺账被整笔消费掉时它也只有那笔预占的量。
 *       把它们合成一个数，就答不出「这批货里到底有没有人订走」。
 *   <li><b>「平台回传」不作为本模块的出参出现。</b>2 渠道回写与 10 轨迹是下游动作， 本模块只产出 append-only 流水 + 冻结重量体积；在接口上放一个 {@code
 *       platformCallback} 字段会是承诺一件本服务没做的事。
 *   <li><b>金额一律不出现。</b>交接确认的是货与单据，计价归 10 物流与财务； 本模块的重量与体积<b>不是</b>金额也不参与计价。
 * </ul>
 */
public final class ShipmentHandoverModels {

  private ShipmentHandoverModels() {}

  // ------------------------------------------------------------ 词表

  /**
   * 交接单状态。三态：交接中 / 已交接 / 已作废。
   *
   * <p><b>刻意没有「已取消出库」</b>：货已经交出去了就回不来了，后续的差异处理属 11 售后与 10 物流，不在本模块改写这一单的历史（与 8.3.3.2「打包失败要退回复核
   * 重开一张」不同 —— 打包的货还在仓里，交出去的货不在）。
   */
  public enum HandoverStatus {
    /** 交接中：可以登记承运商与运单号，可以确认交接。**库存尚未动过。** */
    OPEN("交接中", false),
    /** 已交接：出库扣减与预占消费已在同一事务里完成，出库单随之 SHIPPED。 */
    HANDED_OVER("已交接", true),
    /** 已作废：货没交出去，重新走打包或发运流程。库存不动（本来也没扣）。 */
    CANCELLED("已作废", true);

    private final String label;
    private final boolean terminal;

    HandoverStatus(String label, boolean terminal) {
      this.label = label;
      this.terminal = terminal;
    }

    public String label() {
      return label;
    }

    public boolean terminal() {
      return this.terminal;
    }

    /** 还能不能继续登记运单号 / 确认交接。只有「交接中」可以。 */
    public boolean acceptsWork() {
      return this == OPEN;
    }
  }

  /** 交接流水事件类型。 */
  public enum HandoverEventType {
    OPENED("开交接单"),
    PARCEL_ADDED("冻结包裹"),
    ITEM_ADDED("冻结明细"),
    WAYBILL_REGISTERED("登记承运商与运单号"),
    HANDED_OVER("交接确认"),
    CANCELLED("作废交接");

    private final String label;

    HandoverEventType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ------------------------------------------------------------ 入参

  /**
   * 开一张交接单入参。
   *
   * @param packingOrderId 上游 8.3.3.2 的打包单，<b>必须已 PACKED</b>
   * @param carrierCode 承运渠道（可空，稍后登记时补；确认交接时必须有）
   * @param carrierName 承运商名称（可空）
   * @param masterWaybillNo 主运单号（可空，稍后登记时补）
   * @param note 备注
   */
  public record HandoverOpenInput(
      UUID packingOrderId,
      @Schema(nullable = true) String carrierCode,
      @Schema(nullable = true) String carrierName,
      @Schema(nullable = true) String masterWaybillNo,
      @Schema(nullable = true) String note) {}

  /**
   * 登记承运商与运单号入参（蓝图输入「承运渠道；运单号」）。
   *
   * @param parcelWaybillNos 每箱运单号（箱 id → 运单号）。留空 = 全部跟随主运单号； 整车发运的常态就是逐箱无独立号。
   *     <p><b>刻意没有 handoverUserId</b>：蓝图把「登记交接时间与交接人」当成<b>一对</b>事实，
   *     两者都在交接确认那一刻才成立。在这一步先落人而不落时刻，会造出「有交接人、 没有交接时刻」的半截记录 —— V123 的 {@code (handover_user_id IS
   *     NULL) = (handover_at IS NULL)} 会在 DB 层当场拒掉它。
   */
  public record HandoverWaybillInput(
      String carrierCode,
      @Schema(nullable = true) String carrierName,
      String masterWaybillNo,
      @Schema(nullable = true) String receiverName,
      @Schema(nullable = true) String receiverContact,
      @Schema(nullable = true) List<ParcelWaybillEntry> parcelWaybillNos,
      @Schema(nullable = true) String note) {}

  /** 一箱的运单号。 */
  public record ParcelWaybillEntry(UUID parcelId, String parcelWaybillNo) {}

  /**
   * 交接确认入参 —— <b>这一个动作同时完成三件事</b>（蓝图输出「出库完成分录 + 发货事件」）。
   *
   * @param handoverUserId 交接人（可空，不传则用登记运单号时定下的人）
   * @param handoverAt 交接时刻（PDA 时钟），不传取当前时刻
   * @param receiverName 揽收人（承运商侧的人）
   * @param receiverContact 揽收人联系方式
   */
  public record HandoverConfirmInput(
      @Schema(nullable = true) UUID handoverUserId,
      @Schema(nullable = true) String handoverAt,
      @Schema(nullable = true) String receiverName,
      @Schema(nullable = true) String receiverContact,
      @Schema(nullable = true) String note) {}

  /** 作废交接入参。原因必填。 */
  public record HandoverCancelInput(String reason) {}

  // ------------------------------------------------------------ 出参

  /**
   * 能力与边界声明。
   *
   * <p>四段「不由本模块负责 / 尚未接入」的事<b>显式报出来</b>（照 8.3.3.2 的灰块约定）： 空列表会被读成「这件事不存在」，而不是「这段能力还没接入」。
   */
  public record ShipmentHandoverCapabilities(
      List<String> responsibilities,
      List<String> statuses,
      String weightUnit,
      String volumeUnit,
      String transactionBasis,
      String inventoryBasis,
      String carrierBasis,
      String waybillBasis,
      String serialBasis,
      boolean touchesInventory,
      List<HandoverUnavailableLink> unavailable,
      String upstreamModule,
      List<String> downstreamModules) {}

  /** 本模块尚未接入 / 不由本模块负责的相邻能力（照 8.3.1.2 的灰块约定）。 */
  public record HandoverUnavailableLink(
      String capability, String ownerModule, String href, String reason) {}

  /** 交接单列表行 / 详情单头。 */
  public record HandoverRow(
      UUID id,
      String handoverCode,
      UUID packingOrderId,
      String packingCode,
      UUID outboundOrderId,
      String outboundOrderCode,
      String outboundOrderStatus,
      String outboundOrderStatusLabel,
      UUID warehouseId,
      String warehouseCode,
      String carrierCode,
      String carrierName,
      String masterWaybillNo,
      UUID handoverUserId,
      OffsetDateTime handoverAt,
      String receiverName,
      String receiverContact,
      String status,
      String statusLabel,
      Integer parcelCount,
      Integer parcelItemCount,
      String totalQuantity,
      String totalGrossWeightG,
      String totalVolumeCbm,
      String deductedQuantity,
      String reservedDeductedQuantity,
      Integer reservationLineCount,
      UUID inventoryTransactionId,
      OffsetDateTime createdAt,
      OffsetDateTime completedAt,
      OffsetDateTime cancelledAt) {}

  /** 交接单分页页。汇总不受筛选影响 —— 与 8.3.1.1 / 8.3.3.2 同一纪律。 */
  public record HandoverPage(
      List<HandoverRow> items, int page, int size, long total, HandoverSummary summary) {}

  /** 交接作业台汇总。 */
  public record HandoverSummary(
      long totalHandovers,
      long openHandovers,
      long handedOverHandovers,
      long cancelledHandovers,
      long parcels,
      long items,
      String totalQuantity,
      String deductedQuantity,
      String reservedDeductedQuantity,
      String totalGrossWeightG,
      String totalVolumeCbm,
      long reservationLineCount) {}

  /** 交接包裹行（哪几箱进了这次交接 + 每箱运单号 + 冻结实测重量体积）。 */
  public record HandoverParcelRow(
      UUID id,
      UUID parcelId,
      Integer parcelNo,
      String parcelCode,
      String parcelWaybillNo,
      String effectiveWaybillNo,
      String grossWeightG,
      String netContentWeightG,
      String volumeCbm,
      Integer itemCount,
      String totalQuantity,
      boolean deducted) {}

  /** 出库明细行（哪些 SKU、多少件）+ **扣减落点**（从哪条库存行扣的）。 */
  public record HandoverItemRow(
      UUID id,
      UUID parcelId,
      Integer itemNo,
      UUID parcelItemId,
      UUID skuId,
      String skuCode,
      String skuName,
      String quantity,
      UUID serialId,
      String serialNumber,
      String deductedQuantity,
      String reservedDeductedQuantity,
      UUID balanceId,
      UUID bucketId,
      UUID locationId,
      String locationCode,
      UUID lotId,
      UUID qualityStatusId,
      boolean deducted) {}

  /** 预占消费行（这次交接消费了哪几条预占、各多少）。 */
  public record HandoverReservationRow(
      UUID id,
      UUID handoverItemId,
      UUID reservationId,
      String reservationNo,
      UUID allocationId,
      UUID locationId,
      String locationCode,
      UUID lotId,
      String quantity,
      OffsetDateTime occurredAt) {}

  /** 交接流水（时间线）。 */
  public record HandoverEventRow(
      UUID id,
      String eventType,
      String eventTypeLabel,
      String fromStatus,
      String toStatus,
      UUID parcelId,
      UUID actorUserId,
      OffsetDateTime occurredAt,
      String detail) {}

  /** 交接单详情：一次调用返回整屏 = 单头 + 包裹 + 明细 + 预占消费 + 时间线 + 判据 + 边界声明。 */
  public record HandoverDetail(
      HandoverRow order,
      List<HandoverParcelRow> parcels,
      List<HandoverItemRow> items,
      List<HandoverReservationRow> reservations,
      List<HandoverEventRow> timeline,
      List<HandoverReservationOption> reservationCandidates,
      List<HandoverUnavailableLink> unavailable,
      HandoverCompletionReport completion) {}

  /**
   * 可消费的预占候选（COMMITTED 且有剩余量的分配）。
   *
   * <p><b>刻意列出来而不是让前端看不见</b>：交接时要回答「这批货原本被谁预占着」。 空列表有明确含义 —— 这个 SKU 没有任何在册预占，因此本次没有预占可消费，
   * 界面应显示「无预占（未预占出库）」而不是「预占已全部消耗」。
   */
  public record HandoverReservationOption(
      UUID allocationId,
      UUID reservationId,
      String reservationNo,
      UUID skuId,
      String skuCode,
      UUID locationId,
      String locationCode,
      UUID lotId,
      String allocatedQuantity,
      String consumedQuantity,
      String remainingQuantity) {}

  /**
   * 能不能交接确认 —— <b>唯一的判据来源</b>。
   *
   * <p>页面用 {@code handableOver} 决定「确认交接」按钮的可点性，<b>不自己数行、 不自己算可用量</b>：那套判断在前端再算一遍就会与库存账差一位，而差的那一位
   * 就是超卖（与 8.3.3.1 / 8.3.3.2 的 blocking 同一纪律）。
   */
  public record HandoverCompletionReport(
      boolean handableOver,
      List<String> blockingReasons,
      List<String> warnings,
      boolean carrierRegistered,
      boolean waybillRegistered,
      boolean handoverUserSet,
      boolean handoverAtSet,
      int parcelCount,
      int itemCount,
      String totalQuantity,
      String shortQuantity,
      List<String> shortSkus) {}

  /**
   * 交接确认之后的回执：单头读数 + 本次实际动了库存的账。
   *
   * <p>{@code inventoryTransactionId} 与 {@code ledgerEntries} 让「这次交接扣了多少」可反查， 而不是只有一个「成功」。
   */
  public record HandoverConfirmed(
      HandoverRow order,
      UUID inventoryTransactionId,
      List<HandoverLedgerEntry> ledgerEntries,
      int reservationLineCount,
      String reservedDeductedQuantity,
      int serialCount) {}

  /** 一条出库分录（V1 inventory_entry 的形状）。 */
  public record HandoverLedgerEntry(
      UUID entryId,
      UUID balanceId,
      UUID bucketId,
      UUID skuId,
      String skuCode,
      String onHandDelta,
      String reservedDelta,
      String locationCode) {}
}
