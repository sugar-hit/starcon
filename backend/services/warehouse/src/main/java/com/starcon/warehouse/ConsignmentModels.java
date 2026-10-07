package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 7.7.1.2 寄售与代销的传输模型。
 *
 * <p>蓝图原话：「管理寄售与代销业务：寄售协议（供应商货、我方销，结算按实际售出）、寄售可用量、
 * 销售触发结算数量、未售退货与期末清理。支持『寄售库存不计入我方采购入库成本，仅在售出时结转』。」
 * 输入：「寄售协议（供应商、SKU、寄售量、结算价、结算周期、期末处理约定）；销售事实。」
 * 输出：「寄售库存视图（寄售量/已售/剩余）；结算触发单（→ 12 应付）；期末退回或转入采购的单据。」
 *
 * <p><b>本模块只有一条不可让步的财务语义</b>：寄售未售出不产生采购成本与应付。
 * 它在 V71 里是四道数据库不变量（剩余量守恒、应付额由「已售 × 结算价」生成、
 * 一条销售事实最多进一张结算单、未售退回不产生应付），这里只是把它讲给人听的口径。
 *
 * <p><b>货主建模是自落地的，不寄生在 {@code stock_bucket} 上</b>：兄弟模块 7.7.1.1
 * 「货主与所有权」尚未交付，{@code stock_bucket} 目前没有 {@code stock_owner} 列。
 * 本模块的寄售事实自成一体（{@code consignment_agreement_line} 自带
 * {@code stockOwnerType} 与恒为 false 的 {@code countsAsOwnedAsset}），
 * 因此与 7.7.1.1 将来给 bucket 加列的工作**不存在 DDL 冲突**。
 * 07-inventory.md:24 的硬约束「寄售货主是供应商，我方只记持有位置与可销售权，
 * 不记为自有资产」由一个 {@code CHECK(counts_as_owned_asset = false)} 强制。
 *
 * <p><b>销售事实为什么在本服务自落地</b>：9 订单尚未交付，而「销售触发结算数量」是本模块的
 * 职责，不能因为上游没交付就停摆。所以本模块自落地 {@code consignment_sale} 记事实，
 * 交接键是 {@code saleRef} + {@code sourceSystem}，9 交付后由它推 —— 不建跨服务 FK、
 * 不查对方的表（AGENTS.md 同一纪律）。9 订单、12 应付、10 物流、6 采购均未交付，
 * 本模块一律只出单据 + 留定位键，不替它们写数据。
 */
public final class ConsignmentModels {

  private ConsignmentModels() {}

  // ------------------------------------------------------------ 枚举

  /** 经营模式。寄售与代销货主都在供应商侧，区别在于**谁承担买断成本**。 */
  public enum ConsignmentMode {
    CONSIGNMENT("寄售", "供应商货、我方销，按实际售出结算，未售出不产生应付"),
    AGENCY("代销", "我方买断、供应商代销，期末处理与结算口径与寄售不同");

    private final String label;
    private final String description;

    ConsignmentMode(String label, String description) {
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

  /** 协议状态机：DRAFT → ACTIVE ⇄ SUSPENDED → CLOSED，转移由 V71 触发器强制。 */
  public enum ConsignmentAgreementStatus {
    DRAFT("草稿"),
    ACTIVE("生效中"),
    SUSPENDED("已暂停"),
    CLOSED("已关闭");

    private final String label;

    ConsignmentAgreementStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 结算周期。按**售出时刻**归期（蓝图「结算按实际售出」）。
   *
   * <p>WEEKLY / SEMI_MONTHLY / MONTHLY 由服务层按 UTC 日历算出区间并显式落到结算单的
   * {@code periodStart/periodEnd}；ON_DEMAND 允许随时手动出一张按需触发单。
   */
  public enum ConsignmentSettlementCycle {
    WEEKLY("每周", "按自然周（周一至周日）归期"),
    SEMI_MONTHLY("每半月", "按上/下半月归期"),
    MONTHLY("每月", "按自然月归期"),
    ON_DEMAND("按需", "不自动归期，随时手动出触发单");

    private final String label;
    private final String description;

    ConsignmentSettlementCycle(String label, String description) {
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
   * 期末处理约定（蓝图「期末处理约定」与输出「期末退回或转入采购的单据」）。
   *
   * <p>两者财务后果相反，所以它们不是同一件事的两种叫法：
   *
   * <ul>
   *   <li>{@link #RETURN_TO_SUPPLIER} 退回供应商 —— 货没卖掉，**不产生任何应付**。
   *   <li>{@link #CONVERT_TO_PURCHASE} 转入我方采购 —— 期末我方买断未售货，
   *       这才产生应付，是「寄售未售出不产生应付」的**唯一**例外（且必须协议里写明）。
   * </ul>
   */
  public enum ConsignmentEndTreatment {
    RETURN_TO_SUPPLIER("退回供应商", "期末未售货退回供应商，不产生应付"),
    CONVERT_TO_PURCHASE("转入采购", "期末未售货由我方买断，转入采购并产生应付（协议约定的唯一例外）");

    private final String label;
    private final String description;

    ConsignmentEndTreatment(String label, String description) {
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

  /** 结算单状态机：OPEN → CONFIRMED（交 12 应付）/ CANCELLED。 */
  public enum ConsignmentSettlementStatus {
    OPEN("待确认"),
    CONFIRMED("已确认"),
    CANCELLED("已作废");

    private final String label;

    ConsignmentSettlementStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 退货/清理单状态机：DRAFT → EXECUTED（已执行）/ CANCELLED。 */
  public enum ConsignmentReturnStatus {
    DRAFT("草稿"),
    EXECUTED("已执行"),
    CANCELLED("已作废");

    private final String label;

    ConsignmentReturnStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 退货/清理单的性质：周期内退未售货，或期末整体清理。 */
  public enum ConsignmentReturnKind {
    UNSOLD_RETURN("未售退货", "周期内退掉未售出部分"),
    PERIOD_END_CLEANUP("期末清理", "协议期末对未售货整体处理");

    private final String label;
    private final String description;

    ConsignmentReturnKind(String label, String description) {
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

  /** 一个可选项（经营模式 / 结算周期 / 期末处理 / 退货性质共用一个形状）。 */
  public record ConsignmentOption(
      String value, String label, String description, boolean createsPayable) {}

  /**
   * 寄售与代销能力说明。前端据此渲染选项与提示，而不是把规则硬编码在界面上。
   *
   * @param payableBasis 应付口径原文：<b>只有「已售数量 × 结算价」会产生应付</b>
   * @param costBasisAvailable 采购入库成本口径是否可算。**恒为 false**：
   *     寄售按设计就不进我方采购入库成本，因此本模块没有任何采购成本列可算
   * @param costBasisNote 如实说明为什么算不出采购成本（不是「还没做」，而是「按设计就不该有」）
   * @param ownershipNote 货主口径原文：货主恒为供应商，永不计入我方自有资产
   * @param saleFactNote 销售事实的来源与交接方式（9 订单未交付）
   * @param payableHandoffNote 结算触发单怎么交给 12 应付（12 未交付，只留定位键）
   * @param returnHandoffNote 期末退回怎么交给 10 物流（10 未交付，只留定位键）
   */
  public record ConsignmentCapabilities(
      List<ConsignmentOption> modes,
      List<ConsignmentOption> settlementCycles,
      List<ConsignmentOption> endTreatments,
      List<ConsignmentOption> returnKinds,
      List<String> agreementStatuses,
      List<String> returnDispositions,
      String payableBasis,
      boolean costBasisAvailable,
      String costBasisNote,
      String ownershipNote,
      String saleFactNote,
      String payableHandoffNote,
      String returnHandoffNote) {}

  // ------------------------------------------------------------ 协议

  /** 建协议。寄售量与结算价挂在明细行上，所以「协议」与「协议带哪些 SKU」一起提交。 */
  public record ConsignmentAgreementInput(
      String mode,
      UUID supplierPartyId,
      String supplierCode,
      String supplierName,
      UUID legalEntityId,
      String currency,
      String settlementCycle,
      String endTreatment,
      LocalDate startDate,
      @Schema(nullable = true) LocalDate endDate,
      @Schema(nullable = true) String note,
      List<ConsignmentLineInput> lines) {}

  /**
   * 协议明细行（寄售量 + 结算价）。
   *
   * <p>{@code consignedQuantity} 是供应商交给我方代销的**上限**，它不是成本也不是负债；
   * {@code settlementPrice} 是**售出时**付给供应商的单价，应付额只由它 × 已售数生成。
   */
  public record ConsignmentLineInput(
      UUID skuId, UUID warehouseId, BigDecimal consignedQuantity, BigDecimal settlementPrice) {}

  /** 加行：给已存在的协议补一个 SKU（寄售量与结算价）。 */
  public record ConsignmentAddLineInput(
      UUID skuId, UUID warehouseId, BigDecimal consignedQuantity, BigDecimal settlementPrice) {}

  /** 记销售事实：售出数量 + 售出时刻 + 幂等引用。 */
  public record ConsignmentSaleInput(
      BigDecimal soldQuantity,
      String saleRef,
      @Schema(nullable = true) String sourceSystem,
      @Schema(nullable = true) BigDecimal salePrice,
      OffsetDateTime occurredAt,
      @Schema(nullable = true) String note) {}

  /** 出结算触发单：把区间内已售事实收成一张交给 12 的应付单。 */
  public record ConsignmentSettlementInput(
      LocalDate periodStart,
      LocalDate periodEnd,
      @Schema(nullable = true) String note) {}

  /** 退货/清理单明细（未售数量）。 */
  public record ConsignmentReturnLineInput(UUID agreementLineId, BigDecimal quantity) {}

  /** 未售退货与期末清理。{@code disposition} 决定这张单到底会不会产生应付。 */
  public record ConsignmentReturnInput(
      String kind,
      String disposition,
      List<ConsignmentReturnLineInput> lines,
      @Schema(nullable = true) String note) {}

  /** 状态流转时的一句人读得懂的话（启用/暂停/关闭/确认/作废共用）。 */
  public record ConsignmentNoteInput(@Schema(nullable = true) String note) {}

  // ------------------------------------------------------------ 读模型

  /**
   * 一行寄售明细 = 寄售量 / 已售 / 退回 / 转采购 / 剩余。这是蓝图输出里的
   * 「寄售库存视图（寄售量/已售/剩余）」。
   *
   * @param remainingQuantity 剩余 = 寄售 − 已售 − 已退回 − 已转采购（DB 生成列，恒等式成立）
   * @param countsAsOwnedAsset 恒为 false：寄售货不是自有资产（DB CHECK 钉死）
   * @param salesInPeriod 落在当前结算区间内的已售数量，用于「可结算量」的判断
   * @param settledQuantity 已被结算单收走的数量
   * @param unsettleableQuantity 售出过但还没进任何结算单的数量
   */
  public record ConsignmentLine(
      UUID id,
      UUID agreementId,
      UUID skuId,
      String skuCode,
      String skuName,
      UUID warehouseId,
      String warehouseCode,
      BigDecimal consignedQuantity,
      BigDecimal settlementPrice,
      BigDecimal soldQuantity,
      BigDecimal returnedQuantity,
      BigDecimal convertedQuantity,
      BigDecimal remainingQuantity,
      String stockOwnerType,
      boolean countsAsOwnedAsset,
      BigDecimal salesInPeriod,
      BigDecimal settledQuantity,
      BigDecimal unsettleableQuantity) {}

  /**
   * 协议头。
   *
   * @param totalConsigned 寄售量合计（**不是**成本、不是应付）
   * @param totalSold 已售合计
   * @param totalRemaining 剩余合计
   * @param settledAmount 已确认应付合计（已售 × 结算价）
   * @param unsettleableAmount 已售但还没进结算单的应付预估
   */
  public record ConsignmentAgreement(
      UUID id,
      String agreementCode,
      String mode,
      String modeLabel,
      UUID supplierPartyId,
      String supplierCode,
      String supplierName,
      UUID legalEntityId,
      String legalEntityCode,
      String currency,
      String settlementCycle,
      String settlementCycleLabel,
      String endTreatment,
      String endTreatmentLabel,
      LocalDate startDate,
      @Schema(nullable = true) LocalDate endDate,
      String status,
      String statusLabel,
      long lineCount,
      BigDecimal totalConsigned,
      BigDecimal totalSold,
      BigDecimal totalReturned,
      BigDecimal totalConverted,
      BigDecimal totalRemaining,
      BigDecimal settledAmount,
      BigDecimal unsettleableAmount,
      String note,
      UUID createdByUserId,
      OffsetDateTime createdAt,
      @Schema(nullable = true) OffsetDateTime activatedAt,
      @Schema(nullable = true) OffsetDateTime closedAt) {}

  /** 协议列表（真分页，total 是该筛选下的全量行数）。 */
  public record ConsignmentAgreementPage(
      List<ConsignmentAgreement> agreements, long total, int page, int size) {}

  /** 协议详情 = 头部 + 全部明细 + 审计时间线。 */
  public record ConsignmentAgreementDetail(
      ConsignmentAgreement agreement,
      List<ConsignmentLine> lines,
      List<ConsignmentAuditEntry> auditTrail) {}

  /**
   * 寄售库存视图：蓝图的「寄售量/已售/剩余」，并如实标注它**不是**自有库存。
   *
   * @param ownedAssetValue 恒为 null：本模块没有库存成本列，寄售按设计也不该有自有货值。
   *     报一个 0 会被读成「这些货值 0 元」，那是谎报；null 才是「这个口径不存在」
   */
  public record ConsignmentStockView(
      UUID agreementId,
      String agreementCode,
      String mode,
      String currency,
      String settlementCycle,
      String endTreatment,
      String status,
      BigDecimal totalConsigned,
      BigDecimal totalSold,
      BigDecimal totalReturned,
      BigDecimal totalConverted,
      BigDecimal totalRemaining,
      BigDecimal unsettledQuantity,
      BigDecimal unsettledAmount,
      long lineCount,
      long zeroRemainingLineCount,
      String stockOwnerType,
      boolean countsAsOwnedAsset,
      @Schema(nullable = true) BigDecimal ownedAssetValue,
      String ownershipNote,
      String payableBasis) {}

  /** 一条销售事实。{@code unsettleable} 表示它还没进任何结算单。 */
  public record ConsignmentSale(
      UUID id,
      UUID agreementLineId,
      String skuCode,
      String skuName,
      BigDecimal soldQuantity,
      BigDecimal salePrice,
      String currency,
      String saleRef,
      String sourceSystem,
      OffsetDateTime occurredAt,
      boolean unsettleable,
      @Schema(nullable = true) UUID settlementId,
      String settlementCode,
      UUID recordedByUserId,
      OffsetDateTime createdAt) {}

  /** 销售事实列表（真分页，可按「是否已结算」筛）。 */
  public record ConsignmentSalePage(List<ConsignmentSale> sales, long total, int page, int size) {}

  /** 结算单头。合计由 DB 触发器保证 == Σ 明细。 */
  public record ConsignmentSettlement(
      UUID id,
      String settlementCode,
      UUID agreementId,
      String agreementCode,
      String supplierName,
      LocalDate periodStart,
      LocalDate periodEnd,
      String currency,
      String status,
      String statusLabel,
      long lineCount,
      BigDecimal totalQuantity,
      BigDecimal totalAmount,
      String sourceSystem,
      @Schema(nullable = true) String referenceId,
      OffsetDateTime triggeredAt,
      @Schema(nullable = true) OffsetDateTime confirmedAt,
      String note,
      UUID createdByUserId,
      OffsetDateTime createdAt) {}

  /** 结算单明细：应付额由「数量 × 结算价」生成，全表没有第二个金额来源。 */
  public record ConsignmentSettlementLine(
      UUID id,
      UUID agreementLineId,
      String skuCode,
      String skuName,
      UUID saleId,
      String saleRef,
      BigDecimal quantity,
      BigDecimal unitPrice,
      BigDecimal amount,
      OffsetDateTime soldAt) {}

  /** 结算单列表（真分页）。 */
  public record ConsignmentSettlementPage(
      List<ConsignmentSettlement> settlements, long total, int page, int size) {}

  /** 结算单详情 = 头部 + 全部明细（每行都指回触发它的那条销售事实）。 */
  public record ConsignmentSettlementDetail(
      ConsignmentSettlement settlement, List<ConsignmentSettlementLine> lines) {}

  /** 退货/清理单头。{@code totalPayableAmount} 只有转采购才非零（DB CHECK 钉死）。 */
  public record ConsignmentReturn(
      UUID id,
      String returnCode,
      UUID agreementId,
      String agreementCode,
      String supplierName,
      String kind,
      String kindLabel,
      String disposition,
      String dispositionLabel,
      String status,
      String statusLabel,
      long lineCount,
      BigDecimal totalQuantity,
      BigDecimal totalPayableAmount,
      String sourceSystem,
      @Schema(nullable = true) String referenceId,
      @Schema(nullable = true) OffsetDateTime executedAt,
      String note,
      UUID createdByUserId,
      OffsetDateTime createdAt) {}

  /** 退货/清理单明细。 */
  public record ConsignmentReturnLine(
      UUID id,
      UUID agreementLineId,
      String skuCode,
      String skuName,
      BigDecimal quantity,
      BigDecimal settlementPrice,
      BigDecimal payableAmount) {}

  /** 退货/清理单列表（真分页）。 */
  public record ConsignmentReturnPage(List<ConsignmentReturn> returns, long total, int page, int size) {}

  /** 退货/清理单详情 = 头部 + 全部明细。 */
  public record ConsignmentReturnDetail(ConsignmentReturn returnOrder, List<ConsignmentReturnLine> lines) {}

  /** 审计时间线上的一条：谁在什么时候把这份协议怎么样了。 */
  public record ConsignmentAuditEntry(
      UUID id, String eventType, String detail, UUID actorUserId, OffsetDateTime occurredAt) {}

  /**
   * 按协议的结算周期算出的**当前默认结算区间**。
   *
   * <p>它是真算出来的（WEEKLY 取周一至周日、SEMI_MONTHLY 按上下半月、MONTHLY 按自然月），
   * 不是写死的字符串。ON_DEMAND 没有固定周期，给出今天到明天的一日窗口作为可编辑起点。
   *
   * <p>出单时允许显式改期（补结算、跨期合并都要用得到），所以这里只是默认值，不是闸门。
   */
  public record SettlementWindow(
      UUID agreementId,
      String settlementCycle,
      String settlementCycleLabel,
      LocalDate periodStart,
      LocalDate periodEnd,
      LocalDate today) {}
}
