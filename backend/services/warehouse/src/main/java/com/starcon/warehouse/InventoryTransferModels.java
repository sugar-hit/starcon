package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 7.5.1.1 调拨单。
 *
 * <p>蓝图 07-inventory.md 7.5.1.1 的输入/输出契约：
 *
 * <ul>
 *   <li>输入：调拨请求（源仓、目标仓、SKU、数量）；{@code expected_version}。
 *   <li>输出：调拨单（草稿/待发/在途/部分收/已收/差异）； 库存分录（发出转入途、实收转入目标仓）。
 * </ul>
 *
 * <p>DTO 一律带 {@code TransferOrder}/{@code Transfer} 前缀：四个服务的 schema 被 codegen
 * 拍平进同一命名空间，重名会被**静默覆盖且没有任何警告**（AGENTS.md 契约纪律）。
 *
 * <p>数字口径（界面上每一列都对应其中一条，不做二次加工）：
 *
 * <ul>
 *   <li>{@code requested} 请求调拨量 · {@code shipped} 累计发出 · {@code received} 累计实收 · {@code
 *       discrepancy} 累计确认差异 · {@code inTransit} 仍在途。
 *   <li>守恒式：{@code shipped = received + discrepancy + inTransit}， 由迁移 V61 的 {@code
 *       guard_transfer_line_conservation} 在 DB 级钉死。
 *   <li>{@code unshipped = requested − shipped}：还没发出去的部分。它**不是**差异 ——
 *       货从没离开源仓，不存在短少。把它算进差异会让「少发了」被误读成「路上丢了」。
 * </ul>
 */
public final class InventoryTransferModels {
  private InventoryTransferModels() {}

  /** 调拨单状态词表。与 {@code transfer_order.status} 的 CHECK 逐值对应。 */
  public enum TransferOrderStatus {
    /** 草稿：只有意图，未动库存。 */
    DRAFT("草稿"),
    /** 待发：已确认，尚未发出。 */
    PENDING_SHIPMENT("待发"),
    /** 在途：已发出，全部明细均未实收。 */
    IN_TRANSIT("在途"),
    /** 部分收：有实收但还有明细没收完。 */
    PARTIALLY_RECEIVED("部分收"),
    /** 已收：全部发出量已实收结清。 */
    RECEIVED("已收"),
    /** 差异：已结案且存在实收短少。 */
    DISCREPANCY("差异");

    private final String label;

    TransferOrderStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static TransferOrderStatus parse(String raw) {
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw)) {
          return s;
        }
      }
      throw new IllegalArgumentException("未知的调拨单状态：" + raw + "，可选：" + String.join("、", names()));
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /** 差异原因词表。SHORTAGE 事件必填其一。 */
  public enum TransferDiscrepancyReason {
    LOST("运输丢失"),
    DAMAGED("破损"),
    REJECTED("目的仓拒收"),
    SHORT_SHIPPED("发运即短少");

    private final String label;

    TransferDiscrepancyReason(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static TransferDiscrepancyReason parse(String raw) {
      for (var r : values()) {
        if (r.name().equalsIgnoreCase(raw)) {
          return r;
        }
      }
      throw new IllegalArgumentException(
          "未知的差异原因："
              + raw
              + "，可选："
              + String.join("、", java.util.Arrays.stream(values()).map(Enum::name).toList()));
    }
  }

  /** 列表查询条件。全部对应 SQL 里的真实条件，没有「接了但不生效」的参数。 */
  public record TransferOrderQuery(
      UUID fromWarehouseId,
      UUID toWarehouseId,
      UUID skuId,
      List<String> status,
      String keyword,
      String sort,
      Integer page,
      Integer size) {}

  /** 调拨单列表行。 */
  public record TransferOrderRow(
      UUID id,
      String orderCode,
      UUID fromWarehouseId,
      String fromWarehouseCode,
      String fromWarehouseName,
      UUID toWarehouseId,
      String toWarehouseCode,
      String toWarehouseName,
      UUID legalEntityId,
      String legalEntityName,
      String status,
      OffsetDateTime expectedShipAt,
      @Schema(nullable = true) OffsetDateTime expectedArriveAt,
      @Schema(nullable = true) OffsetDateTime confirmedAt,
      @Schema(nullable = true) OffsetDateTime shippedAt,
      @Schema(nullable = true) OffsetDateTime completedAt,
      BigDecimal requestedQuantity,
      BigDecimal shippedQuantity,
      BigDecimal receivedQuantity,
      BigDecimal discrepancyQuantity,
      BigDecimal inTransitQuantity,
      long lineCount,
      String reason,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 列表页的全量汇总（**不受分页影响**）。 */
  public record TransferOrderSummary(
      long orderCount,
      long lineCount,
      BigDecimal requestedQuantity,
      BigDecimal shippedQuantity,
      BigDecimal receivedQuantity,
      BigDecimal discrepancyQuantity,
      BigDecimal inTransitQuantity,
      long draftCount,
      long pendingShipmentCount,
      long inTransitCount,
      long partiallyReceivedCount,
      long receivedCount,
      long discrepancyOrderCount,
      @Schema(nullable = true) OffsetDateTime earliestExpectedArriveAt) {}

  /**
   * 列表页。
   *
   * <p>{@code summary} 是**全量**汇总（不受分页影响）；下拉词表随响应一起给， 界面不用再单独拉一次。
   */
  public record TransferOrderPage(
      TransferOrderSummary summary,
      List<TransferOrderRow> rows,
      List<TransferOption> statusOptions,
      List<TransferWarehouseOption> warehouseOptions,
      long total,
      int page,
      int size) {}

  /** 状态/仓库下拉项。 */
  public record TransferOption(String value, String label, long orderCount) {}

  public record TransferWarehouseOption(UUID id, String code, String name) {}

  /** 明细行 = 一个 SKU × 质量状态。 */
  public record TransferOrderLineRow(
      UUID id,
      UUID skuId,
      String skuCode,
      String skuName,
      UUID qualityStatusId,
      String qualityStatusCode,
      String qualityStatusName,
      BigDecimal requestedQuantity,
      BigDecimal shippedQuantity,
      BigDecimal receivedQuantity,
      BigDecimal discrepancyQuantity,
      BigDecimal inTransitQuantity,
      BigDecimal unshippedQuantity,
      long version) {}

  /** 一次发出动作及其覆盖的明细行。 */
  public record TransferShipmentRow(
      UUID id,
      OffsetDateTime shippedAt,
      UUID actorId,
      String notes,
      List<TransferShipmentLineRow> lines) {}

  public record TransferShipmentLineRow(UUID lineId, String skuCode, BigDecimal quantity) {}

  /**
   * 一次收货（或一次差异登记）及其覆盖的明细行。
   *
   * <p>{@code kind=RECEIVED} 是实收；{@code kind=SHORTAGE} 是结案时的差异登记， 二者按发生顺序混排在同一条时间线上 ——
   * 一趟调拨的完整经过只能这样读出来。
   */
  public record TransferReceiptRow(
      UUID id,
      String kind,
      OffsetDateTime receivedAt,
      @Schema(nullable = true) UUID locationId,
      @Schema(nullable = true) String locationCode,
      UUID actorId,
      String reason,
      String notes,
      List<TransferReceiptLineRow> lines) {}

  public record TransferReceiptLineRow(UUID lineId, String skuCode, BigDecimal quantity) {}

  /** 调拨单详情：头 + 明细 + 发出记录 + 收货/差异记录。 */
  public record TransferOrderDetail(
      TransferOrderRow order,
      List<TransferOrderLineRow> lines,
      List<TransferShipmentRow> shipments,
      List<TransferReceiptRow> receipts,
      String conservationNote) {}

  /**
   * 本单的库存分录（复用 7.1.2.1 台账的响应）。
   *
   * <p>{@code attribution} 与口径说明必须随响应返回：在途那部分数量**不在分录里** （货权已在发出时一次性离开发货仓），不说明的话「发出 100、实收 40、分录只有
   * -100+40」 会被读成「60 件的账去哪了」。
   */
  public record TransferMovements(
      UUID orderId,
      String orderCode,
      String attribution,
      String attributionNote,
      com.starcon.warehouse.InventoryLedgerModels.InventoryLedgerPage movements) {}

  /** 建单请求。 */
  public record TransferOrderCreateRequest(
      UUID fromWarehouseId,
      UUID toWarehouseId,
      UUID legalEntityId,
      OffsetDateTime expectedShipAt,
      OffsetDateTime expectedArriveAt,
      String reason,
      String notes,
      List<TransferOrderLineRequest> lines) {}

  /** 建单/改单的一条明细。 */
  public record TransferOrderLineRequest(UUID skuId, UUID qualityStatusId, BigDecimal quantity) {}

  /** 改单请求：{@code expectedVersion} 是乐观锁，null 视为非法（不静默放行）。 */
  public record TransferOrderUpdateRequest(
      Long expectedVersion,
      OffsetDateTime expectedShipAt,
      OffsetDateTime expectedArriveAt,
      String reason,
      String notes,
      List<TransferOrderLineRequest> lines) {}

  /** 确认（待发）等无明细的状态动作。 */
  public record TransferOrderActionRequest(Long expectedVersion, String notes) {}

  /** 发出请求：可只发部分行、部分量（分批发运）。 */
  public record TransferShipRequest(
      Long expectedVersion,
      OffsetDateTime shippedAt,
      String notes,
      List<TransferShipLineRequest> lines) {}

  public record TransferShipLineRequest(UUID lineId, BigDecimal quantity) {}

  /**
   * 实收请求：一次收货落到同一个库位。
   *
   * <p>{@code lotId} 可选：蓝图 7.5.1.1 的调拨契约是 SKU × 数量（不按批次拆分）， 批次级拆分属 7.3.1.x
   * 的演进范围。给了批次就落到该批次上，不给则落在无批次库存位置。
   */
  public record TransferReceiveRequest(
      Long expectedVersion,
      OffsetDateTime receivedAt,
      UUID locationId,
      UUID lotId,
      String notes,
      List<TransferReceiveLineRequest> lines) {}

  public record TransferReceiveLineRequest(UUID lineId, BigDecimal quantity) {}

  /** 结案请求：把已发出但未实收的部分记为差异，在途归零。 */
  public record TransferCloseRequest(Long expectedVersion, String reason, String notes) {}

  /** 建单/改单/发货/收货/结案后的回读结果（响应是「库里真实存了什么」）。 */
  public record TransferOrderSaved(
      UUID id,
      String orderCode,
      String status,
      long version,
      BigDecimal requestedQuantity,
      BigDecimal shippedQuantity,
      BigDecimal receivedQuantity,
      BigDecimal discrepancyQuantity,
      BigDecimal inTransitQuantity,
      OffsetDateTime confirmedAt,
      @Schema(nullable = true) OffsetDateTime shippedAt,
      @Schema(nullable = true) OffsetDateTime completedAt) {}
}
