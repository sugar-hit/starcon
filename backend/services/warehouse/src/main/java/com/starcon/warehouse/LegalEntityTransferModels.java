package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 7.5.2.1 跨法人调拨。
 *
 * <p>蓝图 07-inventory.md 7.5.2.1 的输入/输出契约：
 *
 * <ul>
 *   <li>输入：转出与转入法人；转移 SKU 与数量；<b>转移定价</b>。
 *   <li>输出：跨法人调拨单 + <b>内部结算单据</b> + 双方库存变更。
 *   <li>关系：下游 = 12 财务（内部结算/收入确认）、17 权限（双方法人授权）。
 * </ul>
 *
 * <p>DTO 一律带 {@code LegalEntityTransfer} 前缀：四个服务的 schema 被 codegen 拍平进同一命名空间，
 * 重名会被<b>静默覆盖且没有任何警告</b>（AGENTS.md 契约纪律）。
 *
 * <p>数字口径（界面上每一列都对应其中一条，不做二次加工）：
 *
 * <ul>
 *   <li>数量 {@code numeric(19,4)} / 单价 {@code numeric(19,4)} / 行金额生成列
 *       {@code round(quantity*unit_price,4)}。金额是数量的函数，普通列会允许
 *       「改了数量忘了改金额」，而那会直接变成内部结算凭据上的错数。
 *   <li>舍入只发生在落 {@code numeric(19,4)} 这一步（PostgreSQL 四舍五入、远离零），
 *       单价与数量本身不做中间舍入 —— 避免双重舍入。
 *   <li>整单一个币种（ISO 4217 三字母大写）。跨币种换算属 12 财务，本模块不存汇率。
 *   <li>短少 {@code discrepancyQuantity} 与实收 {@code receivedQuantity} 之和不得超过调拨量，
 *       由 DB 触发器兜底。
 * </ul>
 *
 * <p><b>本模块不是普通同仓调拨</b>：货权从转出法人转到转入法人，意味着利润归属变了。
 * 所以这里有 7.5.1.1 没有的三样东西 —— 转移定价、双方确认、内部结算凭据。
 */
public final class LegalEntityTransferModels {
  private LegalEntityTransferModels() {}

  /**
   * 跨法人调拨单状态。与 {@code cross_entity_transfer.status} 的 CHECK 逐值对应。
   *
   * <p>与 7.5.1.1 的最大不同：中间多出<b>两个</b>确认态。转出方与转入方是两个法人，
   * 一个接口同时替两边签字等于没有第二方。
   */
  public enum LegalEntityTransferStatus {
    /** 草稿：只有意图，未定价确认，未动库存。 */
    DRAFT("草稿"),
    /** 待转出方确认。 */
    PENDING_SOURCE_CONFIRM("待转出方确认"),
    /** 待转入方确认（转出方已确认）。 */
    PENDING_TARGET_CONFIRM("待转入方确认"),
    /** 双方已确认，待发运。 */
    CONFIRMED("双方已确认"),
    /** 在途：已发出，货权尚未落到转入法人。 */
    IN_TRANSIT("在途"),
    /** 已交割：转入方实收，货权归转入法人。 */
    COMPLETED("已交割"),
    /** 差异：已结案且存在实收短少。 */
    DISCREPANCY("差异"),
    /** 已作废。 */
    CANCELLED("已作废");

    private final String label;

    LegalEntityTransferStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static LegalEntityTransferStatus parse(String raw) {
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw)) {
          return s;
        }
      }
      throw new IllegalArgumentException(
          "未知的跨法人调拨状态：" + raw + "，可选：" + String.join("、", names()));
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /**
   * 转移定价口径：单价是怎么来的。
   *
   * <p>记录它比只记金额更重要 —— 12 财务做收入确认时要复算「这个价凭什么这么定」，
   * 而「一个说不清来路的金额」复算不出来。
   */
  public enum LegalEntityTransferValuationBasis {
    /** 人工定价。 */
    MANUAL("人工定价"),
    /** 按转出方成本定价。 */
    TRANSFER_COST("转出方成本"),
    /** 按转入方当地成本定价。 */
    LOCAL_COST("转入方当地成本");

    private final String label;

    LegalEntityTransferValuationBasis(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static LegalEntityTransferValuationBasis parse(String raw) {
      for (var b : values()) {
        if (b.name().equalsIgnoreCase(raw)) {
          return b;
        }
      }
      throw new IllegalArgumentException(
          "未知的转移定价口径："
              + raw
              + "，可选："
              + String.join("、", java.util.stream.Stream.of(values()).map(Enum::name).toList()));
    }
  }

  /** 确认方。 */
  public enum LegalEntityTransferSide {
    /** 转出方。 */
    SOURCE("转出方"),
    /** 转入方。 */
    TARGET("转入方");

    private final String label;

    LegalEntityTransferSide(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 列表查询条件。全部对应 SQL 里的真实条件，没有「接了但不生效」的参数。 */
  public record LegalEntityTransferQuery(
      UUID fromLegalEntityId,
      UUID toLegalEntityId,
      UUID fromWarehouseId,
      UUID toWarehouseId,
      UUID skuId,
      List<String> status,
      String keyword,
      String sort,
      Integer page,
      Integer size) {}

  /** 跨法人调拨单列表行。 */
  public record LegalEntityTransferRow(
      UUID id,
      String transferCode,
      UUID fromWarehouseId,
      String fromWarehouseCode,
      String fromWarehouseName,
      UUID toWarehouseId,
      String toWarehouseCode,
      String toWarehouseName,
      UUID fromLegalEntityId,
      String fromLegalEntityCode,
      String fromLegalEntityName,
      UUID toLegalEntityId,
      String toLegalEntityCode,
      String toLegalEntityName,
      String status,
      String valuationBasis,
      String currencyCode,
      BigDecimal totalAmount,
      @Schema(nullable = true) OffsetDateTime expectedShipAt,
      @Schema(nullable = true) OffsetDateTime expectedArriveAt,
      @Schema(nullable = true) OffsetDateTime sourceConfirmedAt,
      @Schema(nullable = true) OffsetDateTime targetConfirmedAt,
      @Schema(nullable = true) OffsetDateTime shippedAt,
      @Schema(nullable = true) OffsetDateTime completedAt,
      long lineCount,
      BigDecimal totalQuantity,
      BigDecimal receivedQuantity,
      BigDecimal discrepancyQuantity,
      BigDecimal inTransitQuantity,
      @Schema(nullable = true) String settlementStatus,
      @Schema(nullable = true) String settlementCode,
      String reason,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 列表页的全量汇总（<b>不受分页影响</b>）。金额汇总是按币种外层聚合的，见服务层。 */
  public record LegalEntityTransferSummary(
      long transferCount,
      long lineCount,
      BigDecimal totalQuantity,
      BigDecimal receivedQuantity,
      BigDecimal discrepancyQuantity,
      BigDecimal inTransitQuantity,
      long draftCount,
      long pendingSourceConfirmCount,
      long pendingTargetConfirmCount,
      long confirmedCount,
      long inTransitCount,
      long completedCount,
      long discrepancyCount,
      long cancelledCount,
      long awaitingBothPartiesCount,
      /** 待对方确认的单数：转出方已确认、转入方还没签，是运营最该盯的一列。 */
      long awaitingTargetCount,
      long awaitingSourceCount,
      /** 已开具凭据但尚未被转入方确认的单数 —— 12 财务的取数入口。 */
      long settlementIssuedCount,
      long settlementAcknowledgedCount,
      @Schema(nullable = true) OffsetDateTime earliestExpectedArriveAt) {}

  /**
   * 列表页。
   *
   * @param summary 全量汇总（不受分页影响）
   * @param currencyAmounts 按币种分组的金额合计：整单单币种，跨单才有必要分组求和
   */
  public record LegalEntityTransferPage(
      LegalEntityTransferSummary summary,
      List<LegalEntityTransferCurrencyAmount> currencyAmounts,
      List<LegalEntityTransferRow> rows,
      List<LegalEntityTransferOption> statusOptions,
      List<LegalEntityTransferOption> valuationOptions,
      List<LegalEntityTransferLegalEntityOption> legalEntityOptions,
      List<LegalEntityTransferWarehouseOption> warehouseOptions,
      long total,
      int page,
      int size) {}

  /** 按币种分组的金额合计。跨币种直接相加是错的，所以按币种分层。 */
  public record LegalEntityTransferCurrencyAmount(String currencyCode, BigDecimal amount, long count) {}

  /** 状态/定价口径下拉项。 */
  public record LegalEntityTransferOption(String value, String label, long transferCount) {}

  public record LegalEntityTransferLegalEntityOption(UUID id, String code, String name) {}

  public record LegalEntityTransferWarehouseOption(UUID id, String code, String name) {}

  /** 明细行 = 一个 SKU × 质量状态 × 数量 × 转移单价。 */
  public record LegalEntityTransferLineRow(
      UUID id,
      UUID skuId,
      String skuCode,
      String skuName,
      UUID qualityStatusId,
      String qualityStatusCode,
      String qualityStatusName,
      BigDecimal quantity,
      BigDecimal unitPrice,
      BigDecimal amount,
      BigDecimal receivedQuantity,
      BigDecimal discrepancyQuantity,
      BigDecimal inTransitQuantity,
      long version) {}

  /** 一次确认（append-only：一个方向只会有一行）。 */
  public record LegalEntityTransferApprovalRow(
      String side,
      UUID approvedBy,
      OffsetDateTime approvedAt,
      String notes) {}

  /** 内部结算凭据行。金额取自单头合计，与明细逐行之和一致（DB 触发器钉死）。 */
  public record LegalEntityTransferSettlementRow(
      UUID id,
      String settlementCode,
      UUID transferId,
      String sourceSystem,
      UUID referenceId,
      UUID fromLegalEntityId,
      String fromLegalEntityCode,
      UUID toLegalEntityId,
      String toLegalEntityCode,
      String currencyCode,
      BigDecimal amount,
      String status,
      OffsetDateTime issuedAt,
      @Schema(nullable = true) OffsetDateTime acknowledgedAt,
      @Schema(nullable = true) UUID acknowledgedBy) {}

  /**
   * 交给 12 财务的取数契约。
   *
   * <p>12 财务按 {@code (sourceSystem, referenceId)} 反查即可拿到一张调拨单唯一一张凭据 ——
   * UNIQUE(source_system,reference_id) 保证重复投递不会重复计提收入。
   *
   * <p><b>刻意不含</b>科目、凭证行、税额与汇率：那些属于 12 自己的权威，
   * 本服务复制一份只会造出两份对不上的账。
   */
  public record LegalEntityTransferSettlementHandoff(
      String sourceSystem,
      UUID referenceId,
      String documentType,
      String documentCode,
      UUID fromLegalEntityId,
      UUID toLegalEntityId,
      String currencyCode,
      BigDecimal amount,
      OffsetDateTime issuedAt,
      String settlementStatus,
      List<LegalEntityTransferSettlementLine> lines,
      String handoffNote) {}

  /** 凭据里的一行，供 12 复算明细。 */
  public record LegalEntityTransferSettlementLine(
      UUID skuId,
      String skuCode,
      BigDecimal quantity,
      BigDecimal unitPrice,
      BigDecimal amount,
      BigDecimal receivedQuantity,
      BigDecimal discrepancyQuantity) {}

  /** 跨法人调拨单详情：头 + 明细 + 双方确认 + 结算凭据。 */
  public record LegalEntityTransferDetail(
      LegalEntityTransferRow transfer,
      List<LegalEntityTransferLineRow> lines,
      List<LegalEntityTransferApprovalRow> approvals,
      @Schema(nullable = true) LegalEntityTransferSettlementRow settlement,
      String pricingNote,
      String ownershipNote,
      String authorizationNote) {}

  /** 本单的库存分录 —— 复用 7.1.2.1 台账，不另写一套流水查询。 */
  public record LegalEntityTransferMovements(
      UUID transferId,
      String transferCode,
      String attribution,
      String attributionNote,
      com.starcon.warehouse.InventoryLedgerModels.InventoryLedgerPage movements) {}

  /** 建单请求。 */
  public record LegalEntityTransferCreateRequest(
      UUID fromWarehouseId,
      UUID toWarehouseId,
      UUID fromLegalEntityId,
      UUID toLegalEntityId,
      String valuationBasis,
      String currencyCode,
      OffsetDateTime expectedShipAt,
      OffsetDateTime expectedArriveAt,
      String reason,
      String notes,
      List<LegalEntityTransferLineRequest> lines) {}

  /** 建单/改单的一条明细。单价是必填的：没有定价就没有内部结算凭据。 */
  public record LegalEntityTransferLineRequest(
      UUID skuId, UUID qualityStatusId, BigDecimal quantity, BigDecimal unitPrice) {}

  /** 改单请求：仅草稿可改，整份替换明细与定价。 */
  public record LegalEntityTransferUpdateRequest(
      Long expectedVersion,
      String valuationBasis,
      String currencyCode,
      OffsetDateTime expectedShipAt,
      OffsetDateTime expectedArriveAt,
      String reason,
      String notes,
      List<LegalEntityTransferLineRequest> lines) {}

  /**
   * 双方确认请求（同一个形状，转出方与转入方各调一次）。
   *
   * <p>方向由端点决定（{@code /source-confirm} 与 {@code /target-confirm}）而不是请求体：
   * 让调用方自己声明「我代表哪一方」等于让它可以自称转入方。
   */
  public record LegalEntityTransferApproveRequest(Long expectedVersion, String notes) {}

  /**
   * 发运请求。
   *
   * <p>跨法人调拨<b>整单发运</b>，不拆批次：转移定价是整单口径，
   * 部分发运会让「一张凭据对应哪几行货」失去唯一答案，而凭据正是 12 财务的取数入口。
   */
  public record LegalEntityTransferShipRequest(Long expectedVersion, OffsetDateTime shippedAt, String notes) {}

  /** 实收请求：一次实收落到转入仓的同一个库位。 */
  public record LegalEntityTransferReceiveRequest(
      Long expectedVersion,
      OffsetDateTime receivedAt,
      UUID locationId,
      UUID lotId,
      String notes,
      List<LegalEntityTransferReceiveLineRequest> lines) {}

  public record LegalEntityTransferReceiveLineRequest(UUID lineId, BigDecimal quantity) {}

  /** 结案请求：把发出过但收不到的部分记为差异。 */
  public record LegalEntityTransferCloseRequest(Long expectedVersion, String reason, String notes) {}

  /** 作废请求：仅在未发运前可用。 */
  public record LegalEntityTransferCancelRequest(Long expectedVersion, String reason) {}

  /** 各写动作后的回读结果（响应是「库里真实存了什么」）。 */
  public record LegalEntityTransferSaved(
      UUID id,
      String transferCode,
      String status,
      long version,
      String valuationBasis,
      String currencyCode,
      BigDecimal totalAmount,
      @Schema(nullable = true) OffsetDateTime sourceConfirmedAt,
      @Schema(nullable = true) OffsetDateTime targetConfirmedAt,
      @Schema(nullable = true) OffsetDateTime shippedAt,
      @Schema(nullable = true) OffsetDateTime completedAt,
      @Schema(nullable = true) String settlementStatus) {}
}
