package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 8.2.1.1 收货单。
 *
 * <p>蓝图 08-warehouse-operations.md 8.2.1.1 的输入/输出契约：
 *
 * <ul>
 *   <li>输入：来源单据（6 采购单 / 11 退货授权 / 7.5 调拨单）；实收明细（SKU、数量、批次、
 *       序列号、效期）；差异原因。
 *   <li>输出：收货单（草稿/已收/部分收/已关闭）+ 库存入库分录（转入待检或可售 bucket）。
 * </ul>
 *
 * <p>DTO 一律带 {@code GoodsReceipt}/{@code Receipt} 前缀：四个服务的 schema 被 codegen
 * 拍平进同一命名空间，重名会被**静默覆盖且没有任何警告**（AGENTS.md 契约纪律）。
 *
 * <p>数字口径（界面上每一列都对应其中一条，不做二次加工）：
 *
 * <ul>
 *   <li>{@code expected} 预期 · {@code received} 累计实收 · {@code rejected} 累计拒收
 *       —— 蓝图原文「**预期与实收分开记录**」。三行永不相减覆盖，差额现场可读。
 *   <li>{@code outstanding = expected − received − rejected}：还差多少没到齐。
 *       它**不是**差异 —— 货还在路上；关单时才会被如实记成 SHORT_RECEIPT。
 *   <li>拒收量**不写分录、不进库存**：它从没进过 inventory_balance，
 *       所以 8.2.1.2 接手的是索赔与改判，而不是「把它从库存里删掉」。
 * </ul>
 */
public final class GoodsReceiptModels {
  private GoodsReceiptModels() {}

  /**
   * 收货单状态词表。与 {@code goods_receipt.status} 的 CHECK 逐值对应。
   *
   * <p>合法转换只有这一张表（{@link #ALLOWED_TRANSITIONS} 是唯一真相，服务层与前端共用）：
   * {@code DRAFT → PARTIALLY_RECEIVED / RECEIVED / CLOSED}、
   * {@code PARTIALLY_RECEIVED → PARTIALLY_RECEIVED / RECEIVED / CLOSED}、
   * {@code RECEIVED → CLOSED}、{@code CLOSED} 为终态。
   *
   * <p>{@code DRAFT → CLOSED} 是刻意允许的：货一件没到、整单拒收时，
   * 必须有一个合法动作把它结掉，否则这张单会永远挂在待办里。
   * {@code CLOSED} 之后一律拒绝 —— 已关闭的单再收货等于账外入库。
   */
  public enum ReceiptStatus {
    /** 草稿：只有意图，未动库存。 */
    DRAFT("草稿"),
    /** 部分收：已有实收，但仍有明细未收齐。 */
    PARTIALLY_RECEIVED("部分收"),
    /** 已收：全部明细的实收量已达到预期量（非终态，仍可关闭）。 */
    RECEIVED("已收"),
    /** 已关闭：终态。剩余未到齐的部分已如实记为短少。 */
    CLOSED("已关闭");

    private final String label;

    ReceiptStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static ReceiptStatus parse(String raw) {
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw)) {
          return s;
        }
      }
      throw new IllegalArgumentException(
          "未知的收货单状态：" + raw + "，可选：" + String.join("、", names()));
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }

    /** 唯一真相：合法转换表。任何不在表内的跳转都必须被拒绝。 */
    public static List<ReceiptStatus> allowedNext(ReceiptStatus current) {
      return switch (current) {
        case DRAFT -> List.of(PARTIALLY_RECEIVED, RECEIVED, CLOSED);
        case PARTIALLY_RECEIVED -> List.of(PARTIALLY_RECEIVED, RECEIVED, CLOSED);
        case RECEIVED -> List.of(CLOSED);
        case CLOSED -> List.of();
      };
    }
  }

  /**
   * 来源类型词表。
   *
   * <p>四类里只有 {@code TRANSFER} 的上游（7.5.1.1 调拨单）已交付。
   * 其余三类在 {@link GoodsReceiptCapabilities} 里显式 {@code available=false} 并给出原因，
   * **不建占位表、不返回空集冒充成功**。
   */
  public enum ReceiptSourceType {
    /** 6 采购单。上游 6.3 未交付。 */
    PURCHASE("采购单", "6.3.2.2"),
    /** 11 退货授权单。上游 11.2.1.1 未交付。 */
    RETURN_AUTH("退货授权单", "11.2.1.1"),
    /** 7.5.1.1 调拨单。已交付，可真实验证来源单据存在。 */
    TRANSFER("调拨单", "7.5.1.1"),
    /** 平台仓回货。上游 10.x 未交付。 */
    PLATFORM_WAREHOUSE("平台仓回货", "10.x");

    private final String label;
    private final String ownerModule;

    ReceiptSourceType(String label, String ownerModule) {
      this.label = label;
      this.ownerModule = ownerModule;
    }

    public String label() {
      return label;
    }

    /** 该来源维度的事实所有者模块号。 */
    public String ownerModule() {
      return ownerModule;
    }

    public static ReceiptSourceType parse(String raw) {
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw)) {
          return s;
        }
      }
      throw new IllegalArgumentException(
          "未知的收货来源类型："
              + raw
              + "，可选："
              + String.join("、", java.util.Arrays.stream(values()).map(Enum::name).toList()));
    }
  }

  /**
   * 差异类型词表。与 {@code goods_receipt_discrepancy.discrepancy_type} 的 CHECK 逐值对应。
   *
   * <p>本模块只**记录**这些差异（蓝图 8.2.1.1）；处理决定、索赔、拍照留证归 8.2.1.2。
   */
  public enum DiscrepancyType {
    /** 超收：收得比预期多。必须留痕（DB 触发器 guard_receipt_line_overage 钉死）。 */
    OVER_RECEIPT("超收"),
    /** 少收：收得比预期少。关单时把剩余未到齐如实记成它。 */
    SHORT_RECEIPT("少收"),
    /** 拒收：现场明确不要的部分。**不入库**，不写分录。 */
    REJECTED("拒收"),
    /** 破损。 */
    DAMAGED("破损"),
    /** 型号不符。 */
    WRONG_ITEM("型号不符"),
    /** 过期。 */
    EXPIRED("过期"),
    /** 错发。 */
    MISDELIVERED("错发");

    private final String label;

    DiscrepancyType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static DiscrepancyType parse(String raw) {
      for (var t : values()) {
        if (t.name().equalsIgnoreCase(raw)) {
          return t;
        }
      }
      throw new IllegalArgumentException(
          "未知的差异类型："
              + raw
              + "，可选："
              + String.join("、", java.util.Arrays.stream(values()).map(Enum::name).toList()));
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /**
   * 来源维度接入情况。
   *
   * <p>沿用仓库既有约定（7.1.1.2 {@code DimensionCapability}、7.4.2.2 capabilities）：
   * {@code available=false} 时 {@code href} 仍然给出 —— 它是**路由契约**而不是死链，
   * 指明这个来源落在哪个模块、上游交付后直接可用；{@code note} 说明当前为什么不可用，
   * 避免界面上出现点下去没反应或 404 的按钮。
   */
  public record ReceiptSourceCapability(
      String value,
      String label,
      boolean available,
      String ownerModule,
      String href,
      String note) {}

  /** 状态机的合法转换，前端据此只给合法的按钮。 */
  public record ReceiptTransition(String from, String to, boolean allowed, String note) {}

  /**
   * 收货能力：来源维度接入情况、四态状态机、七类差异，以及两条必须如实讲清楚的说明。
   *
   * <p>前端据此渲染来源下拉、动作按钮与提示，而不是把规则硬编码在界面上 —— 规则改了就该只改一处。
   */
  public record GoodsReceiptCapabilities(
      List<ReceiptSourceCapability> sources,
      List<ReceiptTransition> transitions,
      List<ReceiptOption> statusOptions,
      List<DiscrepancyOption> discrepancyTypes,
      boolean discrepancyWorkflowAvailable,
      String discrepancyOwnerModule,
      String discrepancyNote,
      String writebackNote,
      String conservationNote) {}

  public record ReceiptOption(String value, String label, long receiptCount) {}

  public record DiscrepancyOption(String value, String label, boolean recordedHere) {}

  /** 列表查询条件。全部对应 SQL 里的真实条件，没有「接了但不生效」的参数。 */
  public record GoodsReceiptQuery(
      UUID warehouseId,
      List<String> status,
      List<String> sourceType,
      String keyword,
      String sort,
      Integer page,
      Integer size) {}

  /** 收货单列表行。 */
  public record GoodsReceiptRow(
      UUID id,
      String receiptCode,
      String sourceType,
      String sourceTypeLabel,
      @Schema(nullable = true) UUID sourceRefId,
      String sourceRefCode,
      UUID warehouseId,
      String warehouseCode,
      String warehouseName,
      UUID legalEntityId,
      String legalEntityName,
      String status,
      String statusLabel,
      String carrier,
      String waybillNo,
      BigDecimal expectedQuantity,
      BigDecimal receivedQuantity,
      BigDecimal rejectedQuantity,
      BigDecimal outstandingQuantity,
      long lineCount,
      long eventCount,
      long discrepancyCount,
      @Schema(nullable = true) OffsetDateTime closedAt,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 列表页的全量汇总（**不受分页影响**）。 */
  public record GoodsReceiptSummary(
      long receiptCount,
      long lineCount,
      BigDecimal expectedQuantity,
      BigDecimal receivedQuantity,
      BigDecimal rejectedQuantity,
      BigDecimal outstandingQuantity,
      long draftCount,
      long partiallyReceivedCount,
      long receivedCount,
      long closedCount,
      long discrepancyCount,
      long openReceiptCount,
      @Schema(nullable = true) OffsetDateTime earliestCreatedAt) {}

  /** 列表页。{@code summary} 是全量汇总，下拉词表随响应一起给。 */
  public record GoodsReceiptPage(
      GoodsReceiptSummary summary,
      List<GoodsReceiptRow> rows,
      List<ReceiptOption> statusOptions,
      List<ReceiptSourceCapability> sourceOptions,
      List<ReceiptWarehouseOption> warehouseOptions,
      long total,
      int page,
      int size) {}

  public record ReceiptWarehouseOption(UUID id, String code, String name) {}

  /** 明细行 = 一个 SKU × 落库质量状态。 */
  public record GoodsReceiptLineRow(
      UUID id,
      UUID skuId,
      String skuCode,
      String skuName,
      UUID qualityStatusId,
      String qualityStatusCode,
      String qualityStatusName,
      BigDecimal expectedQuantity,
      BigDecimal receivedQuantity,
      BigDecimal rejectedQuantity,
      BigDecimal outstandingQuantity,
      long eventCount,
      long serialCount,
      long version) {}

  /**
   * 一次实收事件及其覆盖的明细行。
   *
   * <p>逐笔留痕：只记「总共收了 60」就答不出「这 60 是哪几笔到的」，
   * 而货损追责与批次效期核对的起点都是那几笔到达时间。
   */
  public record GoodsReceiptEventRow(
      UUID id,
      OffsetDateTime receivedAt,
      UUID locationId,
      String locationCode,
      String locationName,
      String waybillNo,
      UUID actorId,
      String notes,
      List<GoodsReceiptEventLineRow> lines) {}

  /** 一次实收里的一个 SKU 行：批次、效期、实收与拒收量、逐号登记的序列号。 */
  public record GoodsReceiptEventLineRow(
      UUID id,
      UUID lineId,
      String skuCode,
      String skuName,
      BigDecimal receivedQuantity,
      BigDecimal rejectedQuantity,
      @Schema(nullable = true) UUID lotId,
      @Schema(nullable = true) String lotCode,
      @Schema(nullable = true) LocalDate expiryDate,
      List<String> serialNumbers) {}

  /** 差异事实记录。只记录事实；处理流程归 8.2.1.2。 */
  public record GoodsReceiptDiscrepancyRow(
      UUID id,
      @Schema(nullable = true) UUID lineId,
      String skuCode,
      String discrepancyType,
      String discrepancyTypeLabel,
      BigDecimal quantity,
      String reasonCode,
      String reasonNote,
      String evidenceNote,
      OffsetDateTime recordedAt,
      UUID actorId) {}

  /** 收货单详情：头 + 明细 + 实收事件 + 差异记录。 */
  public record GoodsReceiptDetail(
      GoodsReceiptRow receipt,
      List<GoodsReceiptLineRow> lines,
      List<GoodsReceiptEventRow> events,
      List<GoodsReceiptDiscrepancyRow> discrepancies,
      List<ReceiptTransition> transitions,
      String conservationNote) {}

  /**
   * 本单的库存分录（复用 7.1.2.1 台账的响应）。
   *
   * <p>{@code attribution} 与口径说明必须随响应返回：拒收量**不在分录里**
   * （它从没进过库存），不说明的话「预期 100、实收 60、拒收 10、分录只有 +60」
   * 会被读成「30 件的账去哪了」。
   */
  public record GoodsReceiptMovements(
      UUID receiptId,
      String receiptCode,
      String attribution,
      String attributionNote,
      com.starcon.warehouse.InventoryLedgerModels.InventoryLedgerPage movements) {}

  /** 建单请求。 */
  public record GoodsReceiptCreateRequest(
      String sourceType,
      @Schema(nullable = true) UUID sourceRefId,
      String sourceRefCode,
      UUID warehouseId,
      UUID legalEntityId,
      String carrier,
      String waybillNo,
      String evidenceNote,
      List<GoodsReceiptLineRequest> lines) {}

  /** 建单/改单的一条明细：SKU × 落库质量状态 × 预期量。 */
  public record GoodsReceiptLineRequest(
      UUID skuId, UUID qualityStatusId, BigDecimal expectedQuantity) {}

  /** 改单请求：{@code expectedVersion} 是乐观锁，null 视为非法（不静默放行）。 */
  public record GoodsReceiptUpdateRequest(
      Long expectedVersion,
      String sourceRefCode,
      String carrier,
      String waybillNo,
      String evidenceNote,
      List<GoodsReceiptLineRequest> lines) {}

  /**
   * 实收请求：一次收货落到同一个库位。
   *
   * <p>{@code serialNumbers} 在行内给出：条数必须等于该行的实收量（或与 {@code
   * declaredSerialCount} 一致），否则报「不允许大概齐」。
   */
  public record GoodsReceiptReceiveRequest(
      Long expectedVersion,
      @Schema(nullable = true) OffsetDateTime receivedAt,
      UUID locationId,
      String waybillNo,
      String notes,
      List<GoodsReceiptReceiveLineRequest> lines,
      List<ReceiptDiscrepancyRequest> discrepancies) {}

  /** 实收请求的一行。批次与效期是**逐次实收**的属性，不是收货单行的属性。 */
  public record GoodsReceiptReceiveLineRequest(
      UUID lineId,
      BigDecimal receivedQuantity,
      BigDecimal rejectedQuantity,
      @Schema(nullable = true) UUID lotId,
      @Schema(nullable = true) LocalDate expiryDate,
      List<String> serialNumbers) {}

  /**
   * 差异登记请求。
   *
   * <p>{@code discrepancyType} 为 {@code null} 时由服务层按数量自动判定：
   * 超预期 → OVER_RECEIPT、全量拒收 → REJECTED，否则报出「必须明确差异类型」，
   * **不猜** —— 猜错类型的差异记录会直接误导 8.2.1.2 的索赔判断。
   */
  public record ReceiptDiscrepancyRequest(
      @Schema(nullable = true) UUID lineId,
      @Schema(nullable = true) String discrepancyType,
      BigDecimal quantity,
      String reasonCode,
      String reasonNote,
      String evidenceNote) {}

  /** 关单请求：把剩余未到齐如实记为少收，然后结案。 */
  public record GoodsReceiptCloseRequest(
      Long expectedVersion, String reason, String notes) {}

  /** 建单/改单/实收/关单后的回读结果（响应是「库里真实存了什么」）。 */
  public record GoodsReceiptSaved(
      UUID id,
      String receiptCode,
      String status,
      String statusLabel,
      long version,
      BigDecimal expectedQuantity,
      BigDecimal receivedQuantity,
      BigDecimal rejectedQuantity,
      BigDecimal outstandingQuantity,
      @Schema(nullable = true) OffsetDateTime closedAt) {}
}