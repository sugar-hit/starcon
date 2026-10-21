package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 7.7.2.2 平台托管与全托管备货的传输模型。
 *
 * <p>蓝图原话：「处理平台托管（全托管）下的"备货"语义：发给平台的货进入平台仓但**所有权与结算规则随模式变化**，需与自有仓库存分账；支持备货单、平台仓收发对账、按件/按日结算口径标记。」 输入「店铺经营模式（2.3.2.1）；备货单与发运数据；平台仓库存与结算回执。」 输出「托管备货台账（备货量/平台在库/平台已售/结算未结）；与自有库存的对账结果；结算口径标记供 12 使用。」
 *
 * <p><b>本模块只有一条不可让步的口径：备货 ≠ 自有库存。</b>蓝图把它写成加粗句：「全托管下"备货"不等于"自有库存"，平台仓在库数是平台回执的观测值，不得并入自有库存余额」。它在 V244 里是三道数据库不变量（{@code countsAsOwnedAsset} 恒 false；{@code inventory_entry} 上 {@code guard_consignment_stock_entry_not_added} 挡住正数入库与预占；明细表里没有第二个数量来源），这里只是把它讲给人听的口径 —— 每个汇总读模型都把**托管口径**与**自有库存口径**并排返回，**绝不合并成一个「合计库存」**。
 *
 * <p><b>四个口径分得清，不许混成一个数字</b>：备货量（我方发出）、平台在库（平台回执入库 − 出库）、平台已售（平台出库回执）、结算未结（已售但尚未按平台口径结算）。本模块里它们是四个独立字段，读模型逐个返回；「备货量」与「平台在库」之差是**在途未确认**，不是差异损失。
 *
 * <p><b>经营模式（2.3.2.1）只读</b>：属 channel 服务，本模块经只读端口读 {@code GET /channel/shops/{id}}，把当时取值快照到备货单上；**不建影子表**，要查店铺现在的模式仍然读 channel。快照是「建单当时的事实」，不是第二个权威。
 *
 * <p><b>结算口径（按件 / 按日）是本模块的输出之一</b>，且两种口径下「结算未结」的算法**真的不同**：按件看件数差，按日看哪几天的销还没结。蓝图那句「结算按平台规则（可能按件或按日）而非我方发货确认」因此是可执行的 —— 发货只能推出备货量，推不出已售多少件。
 *
 * <p><b>记录名统一 {@code PlatformConsignment*} 前缀</b>：springdoc 用 record 的简单类名做 {@code components.schemas} 的键，仓库里已经踩过同名 record 互相覆盖的坑（warehouse 的 {@code ScopeOption}、11.2.3.1 与 9.x 的 {@code QualityEventRow}）。7.7.1.2 寄售模块已占用 {@code Consignment*} 前缀，本模块必须避开。
 */
public final class ConsignmentStockModels {

  private ConsignmentStockModels() {}

  // ------------------------------------------------------------ 枚举

  /**
   * 经营模式（2.3.2.1，channel 拥有）。本模块**只读**它，不建影子表；这里只声明取值域与语义。
   *
   * <p>取值与 channel 的 {@code shop.operating_mode} 逐字一致（channel V1 的 CHECK 约束）。
   */
  public enum PlatformConsignmentOperatingMode {
    SELF_FULFILLED("自发货", "货由我方发货直送买家，不存在平台仓备货"),
    FULL_CONSIGNMENT("全托管", "货权随货交给平台，按平台规则结算"),
    SEMI_CONSIGNMENT("半托管", "部分货托管、部分自发货"),
    OVERSEAS_WAREHOUSE("海外仓", "货在海外仓，备货语义属 10.4.2.2 预报与 7.5 调拨"),
    LOCAL("本地仓", "货在本地仓，备货语义属 10.4.2.2 预报与 7.5 调拨");

    private final String label;
    private final String description;

    PlatformConsignmentOperatingMode(String label, String description) {
      this.label = label;
      this.description = description;
    }

    public String label() {
      return label;
    }

    public String description() {
      return description;
    }

    /** 该经营模式下是否允许建立平台托管备货单。 */
    public boolean supportsConsignmentStock() {
      return this == FULL_CONSIGNMENT || this == SEMI_CONSIGNMENT;
    }
  }

  /**
   * 结算口径（按件 / 按日）。蓝图：「按件/按日结算口径标记」「结算按平台规则（可能按件或按日）而非我方发货确认」。
   *
   * <p>两种口径下「结算未结」的算法不同：按件 = 已售件数 − 已结算件数；按日 = 落在**未**结算日期区间内的售出件数。
   */
  public enum PlatformConsignmentSettlementBasis {
    PER_ITEM("按件结算", "平台按售出件数结算：结算未结 = 平台已售件数 − 已结算件数"),
    PER_DAY("按日结算", "平台按日结算：结算未结 = 落在尚未结算的日期区间内的售出件数");

    private final String label;
    private final String description;

    PlatformConsignmentSettlementBasis(String label, String description) {
      this.label = label;
      this.description = description;
    }

    public String label() {
      return label;
    }

    public String description() {
      return description;
    }
  }

  /** 备货单状态机：DRAFT → SHIPPED → RECEIVED → CLOSED；前三个状态可 → CANCELLED。终态不可复活。 */
  public enum PlatformConsignmentOrderStatus {
    DRAFT("草稿"),
    SHIPPED("已发出"),
    RECEIVED("平台已确认收货"),
    CLOSED("已关闭"),
    CANCELLED("已作废");

    private final String label;

    PlatformConsignmentOrderStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 平台仓回执类型。
   *
   * <p>INBOUND = 平台确认收到我方发出的货；OUTBOUND = 平台从平台仓发出（= 平台已售）。
   */
  public enum PlatformConsignmentReceiptType {
    INBOUND("平台入库", "平台确认收到我方发出的备货"),
    OUTBOUND("平台出库", "平台从平台仓发出，对应平台已售");

    private final String label;
    private final String description;

    PlatformConsignmentReceiptType(String label, String description) {
      this.label = label;
      this.description = description;
    }

    public String label() {
      return label;
    }

    public String description() {
      return description;
    }
  }

  /**
   * 对账差异口径。**每一种都是真算出来的**，不是留个空壳字段。
   *
   * <p>差异量 = 平台入库回执累计 − 备货量（我方发出的量）：正数 = 平台多收，负数 = 在途未确认。
   */
  public enum PlatformConsignmentDifferenceBasis {
    MATCHED("完全对平", "平台入库回执累计与备货量一致，无差异"),
    IN_TRANSIT("在途未确认", "平台还没确认收到这批货，差额是在途量，不是损失"),
    OVER_RECEIVED("平台多收", "平台入库回执累计大于我方发出的备货量，要查是不是串货或串单"),
    NEGATIVE_ON_HAND("平台出库超过入库", "平台出库回执累计大于入库回执累计，平台回执自相矛盾");

    private final String label;
    private final String description;

    PlatformConsignmentDifferenceBasis(String label, String description) {
      this.label = label;
      this.description = description;
    }

    public String label() {
      return label;
    }

    public String description() {
      return description;
    }
  }

  /** 结算状态（**推导**出来的，不是单据状态）。 */
  public enum PlatformConsignmentSettlementState {
    NOT_STARTED("未发生", "平台还没有回执确认售出"),
    PENDING("有未结", "平台已售但还有未按平台口径结算的量"),
    SETTLED("已结清", "平台已售的量全部已按平台口径结算");

    private final String label;
    private final String description;

    PlatformConsignmentSettlementState(String label, String description) {
      this.label = label;
      this.description = description;
    }

    public String label() {
      return label;
    }

    public String description() {
      return description;
    }
  }

  /** 分账校验结论：本模块**自有库存账本**里有没有混进平台托管的量。 */
  public enum PlatformConsignmentSegregationStatus {
    SEPARATED("已分账", "自有库存账本里没有任何平台托管入库分录：平台在库数没有并进自有库存余额"),
    VIOLATION("分账被破坏", "自有库存账本里出现了平台托管的正数入库分录：平台在库数被并进了自有库存余额");

    private final String label;
    private final String description;

    PlatformConsignmentSegregationStatus(String label, String description) {
      this.label = label;
      this.description = description;
    }

    public String label() {
      return label;
    }

    public String description() {
      return description;
    }
  }

  // ------------------------------------------------------------ 能力与选项

  /** 一个可选项（经营模式 / 结算口径 / 回执类型 / 差异口径共用一个形状）。 */
  public record PlatformConsignmentOption(String value, String label, String description) {}

  /**
   * 一环未接入的能力。上游 10.4.2.2 的能力边界、下游 12 结算 / 13 利润里凡属别的服务 或尚未交付的，都在这里**显式报出**（模块 ID + 归属服务 + 原因 + 交接方式），而不是让人从 404 或空数组里猜哪一环没通。
   */
  public record PlatformConsignmentUnavailableCapability(
      String capability, String ownerModule, String ownerService, String reason, String handoff) {}

  /**
   * 平台托管备货能力说明。前端据此渲染选项与提示，而不是把规则硬编码在界面上。
   *
   * @param measureNote 四个口径各自的定义（备货量 / 平台在库 / 平台已售 / 结算未结）
   * @param separationNote 「备货 ≠ 自有库存」的原文与本模块怎么落地
   * @param settlementNote 结算按平台口径（按件 / 按日）而非我方发货确认
   * @param operatingModeNote 经营模式来自 2.3.2.1、只读、不建影子表
   * @param forecastNote 10.4.2.2 备货预报在 channel，本模块经只读端口取数
   * @param segregationCheckNote 分账校验查的是什么、查不到会怎样
   */
  public record PlatformConsignmentCapabilities(
      List<PlatformConsignmentOption> operatingModes,
      List<PlatformConsignmentOption> settlementBases,
      List<PlatformConsignmentOption> receiptTypes,
      List<PlatformConsignmentOption> differenceBases,
      List<PlatformConsignmentOption> orderStatuses,
      List<String> supportedOperatingModes,
      List<PlatformConsignmentUnavailableCapability> unavailable,
      String measureNote,
      String separationNote,
      String settlementNote,
      String operatingModeNote,
      String forecastNote,
      String segregationCheckNote) {}

  // ------------------------------------------------------------ 输入

  /**
   * 建备货单。
   *
   * @param shopId 店铺（2.3.2.1 经营模式属 channel）。服务层经只读端口核对经营模式后才允许建单
   * @param forecastId 10.4.2.2 备货预报单（可选）。给了就按预报明细预填备货行
   * @param settlementBasis 结算口径（按件 / 按日）。离开草稿态后冻结
   */
  public record PlatformConsignmentOrderInput(
      UUID shopId,
      String platformWarehouseCode,
      String platformWarehouseName,
      UUID legalEntityId,
      String currency,
      String settlementBasis,
      @Schema(nullable = true) UUID forecastId,
      @Schema(nullable = true) String note,
      List<PlatformConsignmentLineInput> lines) {}

  /** 备货明细行：SKU + 备货量。 */
  public record PlatformConsignmentLineInput(
      UUID skuId, BigDecimal stockedQuantity, @Schema(nullable = true) String unit,
      @Schema(nullable = true) String note) {}

  /** 加行：给已存在的备货单补一个 SKU。 */
  public record PlatformConsignmentAddLineInput(
      UUID skuId, BigDecimal stockedQuantity, @Schema(nullable = true) String unit,
      @Schema(nullable = true) String note) {}

  /** 登记平台仓收发回执。 */
  public record PlatformConsignmentReceiptInput(
      UUID orderLineId,
      String receiptType,
      String platformReceiptRef,
      BigDecimal quantity,
      OffsetDateTime occurredAt,
      @Schema(nullable = true) String sourceSystem,
      @Schema(nullable = true) String note) {}

  /** 登记平台结算回执（口径随回执走：平台合同规则不是我方自选）。 */
  public record PlatformConsignmentSettlementReceiptInput(
      UUID orderLineId,
      String settlementBasis,
      LocalDate periodStart,
      LocalDate periodEnd,
      BigDecimal settledQuantity,
      @Schema(nullable = true) BigDecimal settledAmount,
      String currency,
      String platformRef,
      OffsetDateTime receivedAt,
      @Schema(nullable = true) String sourceSystem,
      @Schema(nullable = true) String note) {}

  /** 状态流转时的一句人读得懂的话（发货 / 确认收货 / 关闭 / 作废共用）。 */
  public record PlatformConsignmentNoteInput(@Schema(nullable = true) String note) {}

  // ------------------------------------------------------------ 读模型

  /**
   * 【本模块的核心读模型】一行备货明细的**四个口径**。
   *
   * <p>蓝图输出「托管备货台账（备货量/平台在库/平台已售/结算未结）」。这四个数**分属四个字段**，不是同一个「库存量」换着名字用：备货量是我方发出的、平台在库与平台已售来自平台回执、结算未结来自平台结算口径。
   *
   * @param stockedQuantity 备货量：我方发给平台的量
   * @param platformReceivedQuantity 平台入库回执累计
   * @param platformSoldQuantity 平台出库回执累计（= 平台已售）
   * @param platformOnHandQuantity 平台在库 = 平台入库 − 平台出库
   * @param inTransitQuantity 在途未确认 = 备货量 − 平台入库累计（不足则为 0）
   * @param settledQuantity 已结算量（按本单结算口径算出来的）
   * @param unsettledQuantity 结算未结（按件 / 按日两套算法，结论不同）
   * @param ownInventoryOnHandQuantity 同 SKU 在**自有库存**里的在手量（不含任何托管量）
   * @param ownInventoryReservedQuantity 同 SKU 在自有库存里的预占量
   * @param countsAsOwnedAsset **恒为 false**：托管备货不是自有资产（DB CHECK 钉死）
   */
  public record PlatformConsignmentLedgerRow(
      UUID orderLineId,
      UUID skuId,
      String skuCode,
      String skuName,
      BigDecimal stockedQuantity,
      BigDecimal platformReceivedQuantity,
      BigDecimal platformSoldQuantity,
      BigDecimal platformOnHandQuantity,
      BigDecimal inTransitQuantity,
      BigDecimal settledQuantity,
      BigDecimal unsettledQuantity,
      BigDecimal ownInventoryOnHandQuantity,
      BigDecimal ownInventoryReservedQuantity,
      boolean countsAsOwnedAsset,
      String settlementBasis,
      String settlementBasisLabel,
      String settlementState,
      String settlementStateLabel) {}

  /** 托管备货台账（表头合计 + 逐行四口径）。 */
  public record PlatformConsignmentLedger(
      UUID orderId,
      String orderCode,
      String shopCode,
      String shopName,
      String operatingMode,
      String operatingModeLabel,
      String settlementBasis,
      String settlementBasisLabel,
      BigDecimal totalStockedQuantity,
      BigDecimal totalPlatformReceivedQuantity,
      BigDecimal totalPlatformSoldQuantity,
      BigDecimal totalPlatformOnHandQuantity,
      BigDecimal totalInTransitQuantity,
      BigDecimal totalSettledQuantity,
      BigDecimal totalUnsettledQuantity,
      BigDecimal totalOwnInventoryOnHandQuantity,
      List<PlatformConsignmentLedgerRow> rows,
      String measureNote,
      String separationNote) {}

  /**
   * 与自有库存的**对账结果**（蓝图输出「与自有库存的对账结果」）。
   *
   * @param ownInventoryOnHandQuantity 同 SKU 在自有库存里的在手量 —— **不含**托管量
   * @param segregationLeakQuantity 自有库存账本里与平台托管相关的**正数入库**累计量。恒为 0；不为 0 就是分账被破坏
   * @param segregationStatus 分账校验结论
   */
  public record PlatformConsignmentReconciliation(
      UUID orderId,
      String orderCode,
      String shopCode,
      String operatingMode,
      String operatingModeLabel,
      String settlementBasis,
      String settlementBasisLabel,
      BigDecimal totalStockedQuantity,
      BigDecimal totalPlatformReceivedQuantity,
      BigDecimal totalPlatformSoldQuantity,
      BigDecimal totalPlatformOnHandQuantity,
      BigDecimal totalInTransitQuantity,
      BigDecimal totalUnsettledQuantity,
      BigDecimal ownInventoryOnHandQuantity,
      BigDecimal ownInventoryReservedQuantity,
      BigDecimal segregationLeakQuantity,
      String segregationStatus,
      String segregationStatusLabel,
      List<PlatformConsignmentReconciliationRow> rows,
      String differenceNote,
      String segregationNote) {}

  /**
   * 一行对账结果：差异有量、有口径。
   *
   * @param differenceQuantity 差异量 = 平台入库回执累计 − 备货量（有符号：正数 = 平台多收，负数 = 在途未确认）
   * @param differenceBasis 差异口径（**真算出来的**，不是空壳字段）
   */
  public record PlatformConsignmentReconciliationRow(
      UUID orderLineId,
      UUID skuId,
      String skuCode,
      String skuName,
      BigDecimal stockedQuantity,
      BigDecimal platformReceivedQuantity,
      BigDecimal platformSoldQuantity,
      BigDecimal platformOnHandQuantity,
      BigDecimal inTransitQuantity,
      BigDecimal settledQuantity,
      BigDecimal unsettledQuantity,
      BigDecimal ownInventoryOnHandQuantity,
      BigDecimal ownInventoryReservedQuantity,
      BigDecimal differenceQuantity,
      String differenceBasis,
      String differenceBasisLabel,
      String differenceNote) {}

  /** 备货单头。合计全部由明细聚合得出，不另存列（避免与明细漂移）。 */
  public record PlatformConsignmentOrder(
      UUID id,
      String orderCode,
      UUID shopId,
      String shopCode,
      String shopName,
      String operatingMode,
      String operatingModeLabel,
      String platformWarehouseCode,
      String platformWarehouseName,
      UUID legalEntityId,
      String currency,
      String settlementBasis,
      String settlementBasisLabel,
      String status,
      String statusLabel,
      @Schema(nullable = true) UUID forecastId,
      String forecastNo,
      long lineCount,
      BigDecimal totalStockedQuantity,
      BigDecimal totalPlatformReceivedQuantity,
      BigDecimal totalPlatformSoldQuantity,
      BigDecimal totalPlatformOnHandQuantity,
      BigDecimal totalUnsettledQuantity,
      String settlementState,
      String settlementStateLabel,
      String note,
      UUID createdByUserId,
      OffsetDateTime createdAt,
      @Schema(nullable = true) OffsetDateTime shippedAt,
      @Schema(nullable = true) OffsetDateTime platformReceivedAt,
      @Schema(nullable = true) OffsetDateTime closedAt) {}

  /** 备货单列表（真分页，total 是该筛选下的全量行数）。 */
  public record PlatformConsignmentOrderPage(
      List<PlatformConsignmentOrder> orders, long total, int page, int size) {}

  /** 备货单详情 = 头部 + 明细 + 台账 + 对账 + 审计时间线。 */
  public record PlatformConsignmentOrderDetail(
      PlatformConsignmentOrder order,
      List<PlatformConsignmentOrderLine> lines,
      PlatformConsignmentLedger ledger,
      PlatformConsignmentReconciliation reconciliation,
      List<PlatformConsignmentAuditEntry> auditTrail) {}

  /** 备货单明细行（含逐行四口径）。 */
  public record PlatformConsignmentOrderLine(
      UUID id,
      UUID orderId,
      UUID skuId,
      String skuCode,
      String skuName,
      BigDecimal stockedQuantity,
      String unit,
      boolean countsAsOwnedAsset,
      BigDecimal platformReceivedQuantity,
      BigDecimal platformSoldQuantity,
      BigDecimal platformOnHandQuantity,
      BigDecimal inTransitQuantity,
      BigDecimal settledQuantity,
      BigDecimal unsettledQuantity,
      String settlementState,
      String settlementStateLabel,
      String note) {}

  /** 平台仓收发回执。 */
  public record PlatformConsignmentReceipt(
      UUID id,
      UUID orderId,
      UUID orderLineId,
      String skuCode,
      String receiptType,
      String receiptTypeLabel,
      String platformReceiptRef,
      BigDecimal quantity,
      OffsetDateTime occurredAt,
      String sourceSystem,
      String note,
      OffsetDateTime createdAt) {}

  /** 平台仓回执列表（真分页）。 */
  public record PlatformConsignmentReceiptPage(
      List<PlatformConsignmentReceipt> receipts, long total, int page, int size) {}

  /** 平台结算回执。 */
  public record PlatformConsignmentSettlementReceipt(
      UUID id,
      UUID orderId,
      UUID orderLineId,
      String skuCode,
      String settlementBasis,
      String settlementBasisLabel,
      LocalDate periodStart,
      LocalDate periodEnd,
      BigDecimal settledQuantity,
      @Schema(nullable = true) BigDecimal settledAmount,
      String currency,
      String platformRef,
      OffsetDateTime receivedAt,
      String sourceSystem,
      String note,
      OffsetDateTime createdAt) {}

  /** 平台结算回执列表（真分页）。 */
  public record PlatformConsignmentSettlementReceiptPage(
      List<PlatformConsignmentSettlementReceipt> receipts, long total, int page, int size) {}

  /**
   * 结算口径标记（供 12.3.1 结算使用）。
   *
   * @param basis 口径（按件 / 按日）
   * @param basisLabel 口径中文名
   * @param basisRule 该口径下「结算未结」**怎么算**（原文）
   * @param unsettledQuantity 未结量（按本口径算出来的）
   * @param unsettledKnown 上游/回执是否足以算出未结量。false 时 {@code unsettledQuantity} 为 {@code null}（**不是 0**）
   * @param settledPeriods 已结算的日期区间（按日口径下逐段列出）
   */
  public record PlatformConsignmentSettlementBasisView(
      UUID orderId,
      String orderCode,
      String basis,
      String basisLabel,
      String basisRule,
      BigDecimal platformSoldQuantity,
      BigDecimal settledQuantity,
      @Schema(nullable = true) BigDecimal unsettledQuantity,
      boolean unsettledKnown,
      String settlementState,
      String settlementStateLabel,
      List<PlatformConsignmentSettledPeriod> settledPeriods,
      String note) {}

  /** 一个已结算的日期区间（按日口径）。 */
  public record PlatformConsignmentSettledPeriod(
      LocalDate periodStart, LocalDate periodEnd, BigDecimal settledQuantity, String basis) {}

  /** 审计时间线条目。 */
  public record PlatformConsignmentAuditEntry(
      UUID id,
      String eventType,
      String detail,
      UUID actorUserId,
      OffsetDateTime occurredAt) {}

  /**
   * 店铺经营模式读出口（2.3.2.1，只读）。
   *
   * @param known 上游是否给出了这家店铺的经营模式。false 时 {@code operatingMode} 为 {@code null}（**不是 0、也不是猜一个**）
   * @param note 上游状态说明（读不到 / 没有这条记录 / 读到了）
   */
  public record PlatformConsignmentShopMode(
      UUID shopId,
      String shopCode,
      String shopName,
      @Schema(nullable = true) String operatingMode,
      @Schema(nullable = true) String operatingModeLabel,
      boolean known,
      boolean supportsConsignmentStock,
      String note) {}

  /** 备货预报预填预览（10.4.2.2，只读）。 */
  public record PlatformConsignmentForecastPreview(
      UUID forecastId,
      String forecastNo,
      boolean known,
      String status,
      String expectedArrivalAt,
      BigDecimal totalQuantity,
      List<PlatformConsignmentForecastLine> lines,
      String note) {}

  /** 预报明细的一行（SKU 编码来自 channel，SKU 标识来自本服务的 sku_ref 登记）。 */
  public record PlatformConsignmentForecastLine(
      String skuCode,
      BigDecimal quantity,
      String unit,
      @Schema(nullable = true) UUID skuId,
      @Schema(nullable = true) String skuName,
      boolean mapped) {}
}
