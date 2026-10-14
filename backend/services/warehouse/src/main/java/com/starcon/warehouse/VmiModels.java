package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 7.7.2.1 VMI 供应商管理库存的传输模型。
 *
 * <p>蓝图原话：「支持供应商管理库存（VMI）：货权属供应商、我方享有可销售权，按约定补货或由系统触发 供应商备货；记录安全水位与补货信号，售出后与供应商对账结算。」 输入「VMI
 * 协议（SKU、水位、补货倍数、结算价、结算周期）；实时销量与在库数据。」 输出「VMI 水位监控与补货信号（推给供应商或 6.2.1.2 备货）；VMI 结算对账单。」
 *
 * <p><b>本模块只有一条不可让步的口径：可售 ≠ 自有。</b>蓝图把它写成加粗约束： 「VMI 的『可售』不等于『自有』：可售量要计入可售额，货值不能计入自有资产，两者必须分开统计。」 它在
 * V115 里是四道数据库不变量（{@code counts_as_owned_asset} 恒 false、协议行引用的货主 不许是「计入自有资产」的那一类、全表没有第二个金额列、{@code
 * ownedAssetValue} 恒为 {@code null}）。这里只是把它讲给人听的口径 —— 每一个汇总读模型都带 {@link
 * VmiOwnershipSplit}，可售额与自有货值**并排返回、绝不合并**。
 *
 * <p><b>货权复用 7.7.1.1（V67）已交付的 stock_owner</b>，本模块不 ALTER stock_bucket /
 * inventory_balance，也不复制货主权威列：协议行 {@code stockOwnerId} 指向货主档案， 「这批 VMI 货有多少在库」由服务层按 {@code
 * stock_owner_id} 从 stock_bucket 聚合 SELLABLE 质量状态的 available（同服务同库，不是跨服务读）。
 *
 * <p><b>上游缺口一律显式报出而不是猜</b>：13 销量 / 9 订单未交付 → 售出事实自落地 {@code vmi_sale}（以 {@code saleRef} 幂等、标 {@code
 * sourceSystem}）；VMI 货常在供应商 自己的仓、我方账本没有桶 → {@code vmi_stock_snapshot} 租户可维护并**必填来源**； 6 采购 / 12
 * 结算未交付 → 补货信号与对账单只出本服务内单据并留 {@code source_system + reference_id} 定位键。
 *
 * <p><b>记录名统一 {@code Vmi*} 前缀</b>：springdoc 用 record 的简单类名做 {@code components.schemas} 的键，仓库里已经踩过同名
 * record 互相覆盖的坑 （warehouse 的 {@code ScopeOption} 被另一模块同名 record 覆盖，既有字段被静默改名）。
 */
public final class VmiModels {

  private VmiModels() {}

  // ------------------------------------------------------------ 枚举

  /** 协议状态机：DRAFT → ACTIVE ⇄ SUSPENDED → CLOSED，转移由 V115 触发器强制。 */
  public enum VmiAgreementStatus {
    DRAFT("草稿"),
    ACTIVE("生效中"),
    SUSPENDED("已暂停"),
    CLOSED("已关闭");

    private final String label;

    VmiAgreementStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 补货模式（蓝图「按约定补货或由系统触发供应商备货」的两种形态）。
   *
   * <p>{@link #SYSTEM_SIGNAL} 系统按安全水位与补货倍数出信号 → 推给供应商或 6.2.1.2 备货； {@link #VENDOR_OWN}
   * 供应商自行补货，系统只监控水位（不出信号，只报水位状态）。
   */
  public enum VmiReplenishmentMode {
    SYSTEM_SIGNAL("系统出补货信号", "按安全水位与补货倍数算出建议补货量，出信号给供应商或 6 采购备货"),
    VENDOR_OWN("供应商自行补货", "供应商自己决定何时补货，本系统只监控水位并报水位状态，不出信号");

    private final String label;
    private final String description;

    VmiReplenishmentMode(String label, String description) {
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

  /** 信号去向（蓝图输出「推给供应商或 6.2.1.2 备货」的两种去向）。 */
  public enum VmiSignalTarget {
    SUPPLIER("推给供应商", "VMI 的原始形态：信号推给供应商，由供应商自己备货"),
    PROCUREMENT("转 6 采购备货", "信号交给 6.2.1.2 备货流程（本服务只出单据，6 采购尚未交付）");

    private final String label;
    private final String description;

    VmiSignalTarget(String label, String description) {
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
   * 结算周期。按**售出时刻**归期（供应商要的是「这周期我卖出去多少」）。
   *
   * <p>WEEKLY / SEMI_MONTHLY / MONTHLY 由服务层按 UTC 日历算出区间并显式落到对账单的 {@code
   * periodStart/periodEnd}；ON_DEMAND 允许随时手动出一张。
   */
  public enum VmiSettlementCycle {
    WEEKLY("每周", "按自然周（周一至周日）归期"),
    SEMI_MONTHLY("每半月", "按上/下半月归期"),
    MONTHLY("每月", "按自然月归期"),
    ON_DEMAND("按需", "不自动归期，随时手动出对账单");

    private final String label;
    private final String description;

    VmiSettlementCycle(String label, String description) {
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

  /** 补货信号触发原因。三种原因分开而不是笼统的「库存低」： 断货、要跌破水位、余量不足三者的紧急度与处置完全不同。 */
  public enum VmiSignalTriggerReason {
    STOCKOUT("断货", "当前可售量为 0，平台已无货可卖"),
    BELOW_SAFETY_LEVEL("低于安全水位", "当前可售量低于协议约定的安全水位"),
    BELOW_BUFFER("水位余量不足", "高于安全水位但低于「水位 + 一份缓冲」，倍数放大后仍会很快跌破");

    private final String label;
    private final String description;

    VmiSignalTriggerReason(String label, String description) {
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

  /** 信号状态机：OPEN → ACKNOWLEDGED → DISPATCHED → FULFILLED，任一步可 → CANCELLED。 */
  public enum VmiSignalStatus {
    OPEN("待处理"),
    ACKNOWLEDGED("已受理"),
    DISPATCHED("已下发"),
    FULFILLED("已到货"),
    CANCELLED("已作废");

    private final String label;

    VmiSignalStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 对账单状态机：OPEN → CONFIRMED（交 12 结算）/ CANCELLED。 */
  public enum VmiSettlementStatus {
    OPEN("待确认"),
    CONFIRMED("已确认"),
    CANCELLED("已作废");

    private final String label;

    VmiSettlementStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 水位状态：在库可售量与安全水位的关系。 */
  public enum VmiWaterLevelStatus {
    HEALTHY("水位充足", "当前可售量高于安全水位与缓冲线"),
    BELOW_BUFFER("余量不足", "高于安全水位但低于缓冲线"),
    BELOW_SAFETY_LEVEL("低于安全水位", "已跌破协议约定的安全水位，应触发补货"),
    STOCKOUT("断货", "当前可售量为 0");

    private final String label;
    private final String description;

    VmiWaterLevelStatus(String label, String description) {
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
   * 在库量的来源。**每一行水位都带它**，前端照实显示「这行是系统算的还是租户报上来的」。
   *
   * <p>报不出来源的行不允许存在（DB CHECK 钉在词表内），否则财务会把租户报上来的数 当成系统算出来的数。
   */
  public enum VmiStockSource {
    WAREHOUSE_BUCKET("自有仓账本", "从 7.1.1.3 的 stock_bucket 按货主聚合 SELLABLE 可用量，系统算出"),
    TENANT_MAINTAINED("租户维护", "VMI 货物理上在供应商自己的仓、我方账本没有桶，由租户/运营登记"),
    VENDOR_FEED("供应商送数", "由供应商对接文件或接口送来");

    private final String label;
    private final String description;

    VmiStockSource(String label, String description) {
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

  /** 一个可选项（补货模式 / 信号去向 / 结算周期 / 触发原因共用一个形状）。 */
  public record VmiOption(String value, String label, String description) {}

  /**
   * 一环未接入的能力。上游 13 销量、7.1.1.3 可用量、下游 6 采购、12 结算里凡属别的服务 或尚未交付的，都在这里**显式报出**（模块 ID + 归属服务 +
   * 原因），而不是让人从 404 或空数组里猜哪一环没通。
   */
  public record VmiUnavailableCapability(
      String capability, String ownerModule, String ownerService, String reason, String handoff) {}

  /**
   * VMI 能力说明。前端据此渲染选项与提示，而不是把规则硬编码在界面上。
   *
   * @param sellableNote 「可售」口径原文：VMI 量**要**计入可售额
   * @param ownedAssetNote 自有资产口径原文：VMI 货值**不得**计入自有资产
   * @param separationNote 两个口径为什么必须分开统计（蓝图加粗约束原文）
   * @param ownedAssetValueAvailable 恒为 false：本模块不存在「VMI 自有货值」这个数
   * @param salesFactNote 售出事实来源与交接（9 订单 / 13 销量未交付，自落地 + 标来源）
   * @param availableQuantityNote 在库可售量的取数口径与来源标注方式
   * @param signalHandoffNote 补货信号怎么交给供应商 / 6.2.1.2（未交付，只留定位键）
   * @param settlementHandoffNote 对账单怎么交给 12 结算（未交付，只留定位键）
   * @param unavailable 上游/下游未接入环节的显式清单（不会静默返回空）
   */
  public record VmiCapabilities(
      List<VmiOption> replenishmentModes,
      List<VmiOption> signalTargets,
      List<VmiOption> settlementCycles,
      List<VmiOption> triggerReasons,
      List<VmiOption> stockSources,
      List<String> agreementStatuses,
      List<String> signalStatuses,
      List<VmiUnavailableCapability> unavailable,
      String sellableNote,
      String ownedAssetNote,
      String separationNote,
      boolean ownedAssetValueAvailable,
      String salesFactNote,
      String availableQuantityNote,
      String signalHandoffNote,
      String settlementHandoffNote) {}

  // ------------------------------------------------------------ 协议

  /** 建协议。水位、补货倍数、结算价挂在明细行上，所以协议与明细一起提交。 */
  public record VmiAgreementInput(
      UUID supplierPartyId,
      String supplierCode,
      String supplierName,
      UUID legalEntityId,
      String currency,
      String settlementCycle,
      String replenishmentMode,
      @Schema(nullable = true) String signalTarget,
      LocalDate startDate,
      @Schema(nullable = true) LocalDate endDate,
      @Schema(nullable = true) String note,
      List<VmiLineInput> lines) {}

  /**
   * 协议明细行：SKU + 仓库 + 货主 + 安全水位 + 补货倍数 + 结算价。
   *
   * @param stockOwnerId 货主档案（7.7.1.1）。**不允许**是「计入自有资产」的那一类， 数据库会直接拒绝 —— VMI 的货权属供应商
   */
  public record VmiLineInput(
      UUID skuId,
      UUID warehouseId,
      UUID stockOwnerId,
      BigDecimal safetyLevel,
      @Schema(nullable = true) BigDecimal replenishmentMultiple,
      BigDecimal settlementPrice,
      BigDecimal suppliedQuantity,
      BigDecimal onHandQuantity) {}

  /** 加行：给已存在的协议补一个 SKU（水位、倍数、结算价）。 */
  public record VmiAddLineInput(
      UUID skuId,
      UUID warehouseId,
      UUID stockOwnerId,
      BigDecimal safetyLevel,
      @Schema(nullable = true) BigDecimal replenishmentMultiple,
      BigDecimal settlementPrice,
      BigDecimal suppliedQuantity,
      BigDecimal onHandQuantity) {}

  /** 登记在库观测（VMI 货在供应商自己的仓、我方账本没有桶时的唯一落点）。 */
  public record VmiSnapshotInput(
      UUID agreementLineId,
      String stockSource,
      @Schema(nullable = true) String sourceSystem,
      OffsetDateTime observedAt,
      BigDecimal observedQuantity,
      @Schema(nullable = true) BigDecimal coverageDays,
      @Schema(nullable = true) String note) {}

  /** 记售出事实 —— 这是「售出后才与供应商对账结算」的唯一来源。 */
  public record VmiSaleInput(
      BigDecimal soldQuantity,
      String saleRef,
      @Schema(nullable = true) String sourceSystem,
      @Schema(nullable = true) BigDecimal salePrice,
      OffsetDateTime occurredAt,
      @Schema(nullable = true) String note) {}

  /** 出对账单：把区间内已售但还没进任何对账单的销售事实收成一张交给 12。 */
  public record VmiSettlementInput(
      LocalDate periodStart, LocalDate periodEnd, @Schema(nullable = true) String note) {}

  /** 补货信号批量出单（可只针对部分行）。 */
  public record VmiSignalInput(
      @Schema(nullable = true) List<UUID> lineIds, @Schema(nullable = true) String note) {}

  /** 信号登记到货（补货真的到了：这是「供应商按约定补货」落地的那一步）。 */
  public record VmiSignalFulfilInput(
      BigDecimal fulfilledQuantity, @Schema(nullable = true) String note) {}

  /** 状态流转时的一句人读得懂的话（启用/暂停/关闭/确认/作废共用）。 */
  public record VmiNoteInput(@Schema(nullable = true) String note) {}

  // ------------------------------------------------------------ 读模型

  /**
   * 【本模块的核心读模型】可售与自有**分开**的一行口径。
   *
   * <p>蓝图：「可售量要计入可售额，货值不能计入自有资产，两者必须分开统计。」 这个 record 存在的意义就是让「合并成一个库存价值」在类型上就不可能发生 ——
   * 两组数字分属两个字段，页面必须同时拿到两个才能画完一张卡片。
   *
   * @param sellableQuantity 可售量：VMI 货在仓/供应商处的可售数量，**要**计入可售额
   * @param sellableValue 可售额 = 可售量 × 结算价（{@code VmiSettlementCycle} 口径的币种）
   * @param ownedAssetValue 自有货值：**恒为 null**。本模块没有这个口径，给 0 会被读成 「这些货值 0 元」，那是谎报；null 才是「这个数不存在」
   * @param ownedAssetQuantity 自有量：**恒为 0**。VMI 的量一件都不算自有
   * @param countsAsOwnedAsset **恒为 false**（DB CHECK 钉死）
   */
  public record VmiOwnershipSplit(
      BigDecimal sellableQuantity,
      BigDecimal sellableValue,
      BigDecimal ownedAssetQuantity,
      @Schema(nullable = true) BigDecimal ownedAssetValue,
      boolean countsAsOwnedAsset,
      String ownershipNote,
      String separationNote) {}

  /**
   * 一行协议明细 = 水位 + 累计 + 可售/自有分账。
   *
   * @param currentSellableQuantity 当前可售量（按 {@code stockSource} 取数）
   * @param stockSource 这行的在库量是系统算的还是租户报上来的
   * @param waterLevelStatus 与安全水位的关系
   * @param safetyGap 距安全水位的缺口（正数 = 还差多少才够；已达标时为负或 0）
   * @param bufferLine 安全水位 + 一份缓冲（倍数 × 一个「补货单位」的水位参考线）
   * @param projectedQuantity 建议补货量 = ceil(max(0, 安全水位 − 当前可售量) × 补货倍数)
   * @param soldQuantity 累计已售（对账依据）
   * @param unsettleableQuantity 售出过但还没进任何对账单的数量
   */
  public record VmiLine(
      UUID id,
      UUID agreementId,
      UUID skuId,
      String skuCode,
      String skuName,
      UUID warehouseId,
      String warehouseCode,
      UUID stockOwnerId,
      String stockOwnerCode,
      String stockOwnerKind,
      BigDecimal safetyLevel,
      BigDecimal replenishmentMultiple,
      BigDecimal settlementPrice,
      BigDecimal suppliedQuantity,
      BigDecimal soldQuantity,
      BigDecimal withdrawnQuantity,
      BigDecimal onHandQuantity,
      BigDecimal currentSellableQuantity,
      String stockSource,
      String stockSourceLabel,
      @Schema(nullable = true) OffsetDateTime observedAt,
      String waterLevelStatus,
      String waterLevelStatusLabel,
      BigDecimal safetyGap,
      BigDecimal bufferLine,
      BigDecimal projectedQuantity,
      BigDecimal soldQuantity30d,
      @Schema(nullable = true) BigDecimal coverageDays,
      BigDecimal unsettleableQuantity,
      BigDecimal unsettleableAmount,
      @Schema(nullable = true) String openSignalCode,
      VmiOwnershipSplit ownershipSplit) {}

  /** 协议头。合计全部按「可售 / 自有」分开返回。 */
  public record VmiAgreement(
      UUID id,
      String agreementCode,
      UUID supplierPartyId,
      String supplierCode,
      String supplierName,
      UUID legalEntityId,
      String legalEntityCode,
      String currency,
      String settlementCycle,
      String settlementCycleLabel,
      String replenishmentMode,
      String replenishmentModeLabel,
      String signalTarget,
      String signalTargetLabel,
      LocalDate startDate,
      @Schema(nullable = true) LocalDate endDate,
      String status,
      String statusLabel,
      long lineCount,
      BigDecimal totalSafetyLevel,
      BigDecimal totalSupplied,
      BigDecimal totalSold,
      BigDecimal unsettleableQuantity,
      BigDecimal unsettleableAmount,
      BigDecimal settledAmount,
      String note,
      UUID createdByUserId,
      OffsetDateTime createdAt,
      @Schema(nullable = true) OffsetDateTime activatedAt,
      @Schema(nullable = true) OffsetDateTime closedAt) {}

  /** 协议列表（真分页，total 是该筛选下的全量行数）。 */
  public record VmiAgreementPage(List<VmiAgreement> agreements, long total, int page, int size) {}

  /** 协议详情 = 头部 + 全部明细（含水位与分账）+ 审计时间线。 */
  public record VmiAgreementDetail(
      VmiAgreement agreement, List<VmiLine> lines, List<VmiAuditEntry> auditTrail) {}

  /**
   * VMI 水位监控（蓝图输出「VMI 水位监控与补货信号」的前半句）。
   *
   * <p>{@code ownershipSplit} 与 {@code selfOwnedSplit} 是**两张分开的卡片**： 前者是 VMI 货（可售额计入、自有货值恒
   * null），后者是我方自有货（VMI 的量不在里面）。 界面上必须并排展示而不是相加 —— 相加就等于把供应商的货算成我们的资产。
   */
  public record VmiWaterLevelMonitor(
      UUID agreementId,
      String agreementCode,
      String supplierName,
      String currency,
      String replenishmentMode,
      String replenishmentModeLabel,
      String signalTarget,
      String signalTargetLabel,
      String status,
      long lineCount,
      long healthyLineCount,
      long belowBufferLineCount,
      long belowSafetyLineCount,
      long stockoutLineCount,
      long missingStockSourceLineCount,
      BigDecimal totalSafetyLevel,
      BigDecimal totalCurrentSellable,
      BigDecimal totalProjectedQuantity,
      long openSignalCount,
      VmiOwnershipSplit ownershipSplit,
      VmiSelfOwnedSplit selfOwnedSplit,
      List<VmiLine> lines) {}

  /**
   * 我方自有货的对照口径（同一批 SKU 在我方自有货主下的量）。
   *
   * <p>存在的理由是「分开统计」要能被人验证：只报 VMI 的数，读者无从判断我们是不是 把 VMI 的量混进了自有。这里给出同一 SKU×仓 在 SELF 货主下的量， 与 {@link
   * VmiOwnershipSplit} **并排展示、绝不相加**。
   */
  public record VmiSelfOwnedSplit(
      BigDecimal selfOwnedQuantity, long selfOwnedBucketCount, String note) {}

  /** 一条在库观测记录。 */
  public record VmiSnapshot(
      UUID id,
      UUID agreementLineId,
      String skuCode,
      String skuName,
      String stockSource,
      String stockSourceLabel,
      String sourceSystem,
      BigDecimal observedQuantity,
      @Schema(nullable = true) BigDecimal coverageDays,
      OffsetDateTime observedAt,
      String note,
      UUID recordedByUserId,
      OffsetDateTime createdAt) {}

  /** 在库观测列表（真分页）。 */
  public record VmiSnapshotPage(List<VmiSnapshot> snapshots, long total, int page, int size) {}

  /** 一条售出事实。{@code unsettleable} 表示它还没进任何对账单。 */
  public record VmiSale(
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

  /** 售出事实列表（真分页，可按「是否已对账」筛）。 */
  public record VmiSalePage(List<VmiSale> sales, long total, int page, int size) {}

  /**
   * 一条补货信号。
   *
   * @param currentSellableQuantity / safetyLevel / replenishmentMultiple 触发时的快照：
   *     让「为什么补这么多」在三个月后仍可复算
   * @param suggestedQuantity 建议补货量 = ceil(max(0, 水位 − 当前可售) × 倍数)
   */
  public record VmiSignal(
      UUID id,
      String signalCode,
      UUID agreementLineId,
      UUID agreementId,
      String agreementCode,
      String skuCode,
      String skuName,
      String warehouseCode,
      String supplierName,
      String triggerReason,
      String triggerReasonLabel,
      BigDecimal currentSellableQuantity,
      BigDecimal safetyLevel,
      BigDecimal replenishmentMultiple,
      BigDecimal suggestedQuantity,
      String signalTarget,
      String signalTargetLabel,
      String status,
      String statusLabel,
      String sourceSystem,
      @Schema(nullable = true) String referenceId,
      OffsetDateTime createdAt,
      @Schema(nullable = true) OffsetDateTime acknowledgedAt,
      @Schema(nullable = true) OffsetDateTime dispatchedAt,
      @Schema(nullable = true) OffsetDateTime fulfilledAt,
      @Schema(nullable = true) BigDecimal fulfilledQuantity,
      @Schema(nullable = true) OffsetDateTime cancelledAt,
      String note,
      UUID createdByUserId) {}

  /** 出补货信号的结果：真的出了几张、每行为什么出或不出。 */
  public record VmiSignalBatchResult(
      UUID agreementId,
      long createdCount,
      long skippedOpenCount,
      long skippedHealthyCount,
      long skippedVendorOwnCount,
      List<VmiSignal> signals,
      List<String> skippedReasons) {}

  /** 补货信号列表（真分页，可跨协议）。 */
  public record VmiSignalPage(List<VmiSignal> signals, long total, int page, int size) {}

  /** 对账单头。表头应付由 DB 触发器保证 == round(Σ明细金额, 2)。 */
  public record VmiSettlement(
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
      int lineCount,
      BigDecimal totalQuantity,
      BigDecimal totalAmount,
      String sourceSystem,
      @Schema(nullable = true) String referenceId,
      OffsetDateTime triggeredAt,
      @Schema(nullable = true) OffsetDateTime confirmedAt,
      String note,
      UUID createdByUserId,
      OffsetDateTime createdAt) {}

  /** 对账明细：应付额由「数量 × 结算价」生成，全表没有第二个金额来源。 */
  public record VmiSettlementLine(
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

  /** 对账单列表（真分页）。 */
  public record VmiSettlementPage(
      List<VmiSettlement> settlements, long total, int page, int size) {}

  /** 对账单详情 = 头部 + 全部明细（每行都指回触发它的那条销售事实）。 */
  public record VmiSettlementDetail(VmiSettlement settlement, List<VmiSettlementLine> lines) {}

  /** 审计时间线上的一条：谁在什么时候把这份协议怎么样了。 */
  public record VmiAuditEntry(
      UUID id, String eventType, String detail, UUID actorUserId, OffsetDateTime occurredAt) {}

  /**
   * 按协议的结算周期算出的**当前默认对账区间**。
   *
   * <p>它是真算出来的（WEEKLY 取周一至周日、SEMI_MONTHLY 按上下半月、MONTHLY 按自然月）， 不是写死的字符串。ON_DEMAND
   * 没有固定周期，给出今天到明天的一日窗口作为可编辑起点。
   */
  public record VmiSettlementWindow(
      UUID agreementId,
      String settlementCycle,
      String settlementCycleLabel,
      LocalDate periodStart,
      LocalDate periodEnd,
      LocalDate today) {}
}
