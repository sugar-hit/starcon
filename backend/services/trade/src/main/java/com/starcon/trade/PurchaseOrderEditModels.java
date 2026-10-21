package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 6.3.1.2 采购单创建与编辑的传输模型。
 *
 * <p>蓝图 06 卷 6.3.1.2 原文：「创建/编辑采购单：选择供应商、添加 SKU 行（带出报价与库存快照）、
 * 填写数量/采购单位/单价/币种、约定交期与发货地址、备注与附件。支持草稿与提交。」
 * 输入「表单数据；{@code expected_version}；SKU 与供应商报价快照」，输出「采购单（草稿/已提交）；
 * 金额与税额计算结果」。
 *
 * <p><b>本文件最重要的纪律：拿不到的口径必须是 null，不是 0、不是假枚举。</b>
 *
 * <ul>
 *   <li><b>汇率</b>：提交那一刻冻结；取不到就是 null（{@code fxStatus=UNAVAILABLE}），
 *       <b>绝不</b>按 1:1 填一个数进去 —— 假汇率会让「跨币种合计」变成一个看起来能对账、
 *       其实对不上的数。</li>
 *   <li><b>税额</b>：税率的权威在 12 财务，而 finance 服务当前不存在 ⇒
 *       {@code taxRate} / {@code taxAmount} 恒为 null，{@code taxStatus=NOT_AVAILABLE}。
 *       本模块**不造税率表**。</li>
 *   <li><b>库存快照</b>：取不到就是 null（{@code stockStatus=UNAVAILABLE}），
 *       <b>不</b>填 0 冒充「一件都没有」。</li>
 *   <li><b>单位换算（箱规）</b>：取不到就是 null（{@code conversionStatus=UNKNOWN}），
 *       <b>不</b>猜成 1 —— 猜成 1 会让订量差一个数量级。</li>
 * </ul>
 *
 * <p><b>命名。</b>全仓契约按服务分文件导出，但同名类型仍会在桶文件里撞车，
 * 所以本文件的每一个类型都带 {@code PurchaseOrderEdit} 前缀（已逐个 grep 比对，零重叠）。
 */
public final class PurchaseOrderEditModels {

  private PurchaseOrderEditModels() {}

  // ================================================================ 枚举

  /**
   * 单价来源。
   *
   * <p>与 {@code purchase_order_line.price_source} 的枚举**逐字同构**
   * （REQUEST_ESTIMATE / QUOTE / MANUAL）—— 本模块只写 QUOTE 与 MANUAL，
   * REQUEST_ESTIMATE 归 6.2.2.1 的转单建单路径（6.3.1.1）。
   *
   * <p>来源不同，对账时追法不同：QUOTE 有报价单可查，MANUAL 只有采购员自己说的那个数。
   */
  public enum PurchaseOrderEditPriceSource {
    QUOTE("报价"),
    MANUAL("手工填写");

    private final String label;

    PurchaseOrderEditPriceSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 报价快照的候选（带出报价用）。
   *
   * @param selectionState 与 catalog 同口径：SELECTED / AMBIGUOUS / LOWER_PRIORITY
   * @param unitPrice 命中的阶梯单价（数量落在哪一档就取哪一档；低于最低档为 null）
   * @param minQuantity 命中档的起订量
   */
  public record PurchaseOrderEditQuoteCandidate(
      UUID quoteId,
      UUID supplierId,
      String supplierName,
      String currency,
      String purchaseUnitCode,
      BigDecimal moqQuantity,
      @Schema(nullable = true) Integer leadTimeDays,
      @Schema(nullable = true) Integer priority,
      @Schema(nullable = true) BigDecimal unitPrice,
      @Schema(nullable = true) BigDecimal minQuantity,
      @Schema(nullable = true) String selectionState) {}

  // ================================================================ 入参

  /**
   * 采购单的一行（表单里的一行）。
   *
   * @param lineNo 行号（可空 = 服务端按提交顺序编号）
   * @param quantity 数量（NUMERIC(19,6)，基础单位；禁浮点）
   * @param unitCode 采购单位（如「箱」）；**单位换算快照在提交那一刻才冻结**
   * @param unitPrice 单价（与 currency 同币种）
   * @param priceSource 单价来源：QUOTE（带出报价）/ MANUAL（手工填）
   * @param quoteId priceSource=QUOTE 时必填：这条价来自哪条报价
   */
  public record PurchaseOrderEditLineInput(
      @Schema(nullable = true) Integer lineNo,
      @Schema(nullable = true) UUID skuId,
      String skuCode,
      String skuName,
      // ⚠️ 数量与单价在契约里是**可空**的：表单要能表达「这一格还没填」，
      //    而 null 与 0 的含义完全不同（0 会被读成「免费 / 一件都不要」）。
      //    服务层会**拒掉**空数量与空单价（"第 N 行的数量必须大于 0"），不会拿 null 当 0 用。
      @Schema(nullable = true) BigDecimal quantity,
      String unitCode,
      @Schema(nullable = true) BigDecimal unitPrice,
      String priceSource,
      @Schema(nullable = true) UUID quoteId,
      String note) {}

  /**
   * 发货地址（表单里的一份）。
   *
   * <p>地址是**表单内容**：本仓的地址台账（3.6.3.1）在 catalog 独立库，而 warehouse 的仓档案里
   * 没有街道地址字段 ⇒ 本模块不引用任何外部地址源，地址由采购员填，提交时冻结。
   */
  public record PurchaseOrderEditAddressInput(
      String recipientName,
      String recipientPhone,
      String countryCode,
      String province,
      String city,
      String district,
      String detail,
      String postalCode,
      String note) {}

  /**
   * 附件（表单里的一条）。
   *
   * @param mediaId catalog 媒体库的素材 id（逻辑引用，不建跨库外键）
   */
  public record PurchaseOrderEditAttachmentInput(
      UUID mediaId, String attachmentType, String note) {}

  /**
   * 采购单表单（头 + 行 + 地址 + 附件）。
   *
   * <p><b>整体替换语义</b>：PUT 提交的是**这一版的完整表单**，
   * 服务端据此把采购单改成这一版（增行、改行、删行都在这一份里表达）。
   * 少传一行 = 删掉那一行 —— 少传不是「保持原样」。
   */
  public record PurchaseOrderEditOrderInput(
      UUID supplierRefId,
      String supplierCode,
      String supplierName,
      @Schema(nullable = true) UUID legalEntityId,
      String legalEntityCode,
      String legalEntityName,
      String currency,
      UUID targetWarehouseId,
      String targetWarehouseCode,
      String targetWarehouseName,
      LocalDate promisedArrivalDate,
      String note,
      List<PurchaseOrderEditLineInput> lines,
      @Schema(nullable = true) PurchaseOrderEditAddressInput shipAddress,
      List<PurchaseOrderEditAttachmentInput> attachments) {}

  /**
   * 新建采购单（草稿）。
   *
   * @param order 表单内容
   */
  public record PurchaseOrderEditCreateRequest(PurchaseOrderEditOrderInput order) {}

  /**
   * 编辑保存。
   *
   * @param expectedVersion 乐观锁（6.3.1.1 的 purchase_order.version；不匹配返 409）
   * @param order 表单内容（**整体替换**）
   */
  public record PurchaseOrderEditUpdateRequest(
      Long expectedVersion, PurchaseOrderEditOrderInput order) {}

  /**
   * 提交（草稿 → 待审批）。
   *
   * @param expectedVersion 乐观锁
   * @param note 提交说明（进留痕）
   */
  public record PurchaseOrderEditSubmitRequest(Long expectedVersion, String note) {}

  /**
   * 报价 / 库存 / 单位换算预览（带出报价与库存快照）。
   *
   * @param skuId 必填
   * @param supplierRefId 必填：**一张采购单只对一个供应商**，报价要在供应商内部比优先级
   * @param quantity 数量（用来定位阶梯价）
   * @param at 取价日（业务日历口径）
   */
  public record PurchaseOrderEditQuotePreviewRequest(
      UUID skuId,
      UUID supplierRefId,
      @Schema(nullable = true) BigDecimal quantity,
      @Schema(nullable = true) LocalDate at) {}

  /**
   * 由 6.2.2.2 的采购单草稿建单。
   *
   * @param draftIds 6.2.2.2 {@code purchase_demand_merge_draft} 的 id 列表
   */
  public record PurchaseOrderEditFromDraftRequest(List<UUID> draftIds) {}

  // ================================================================ 出参

  /**
   * 采购单的一行（读出）。
   *
   * @param priceSource 单价来源
   * @param quoteId 这一行带的报价 id（priceSource=QUOTE 时有值）
   * @param snapshot 提交时冻结的快照（**未提交时为 null** —— 还没冻结就没有）
   */
  public record PurchaseOrderEditLine(
      int lineNo,
      @Schema(nullable = true) UUID skuId,
      String skuCode,
      String skuName,
      BigDecimal quantity,
      String unitCode,
      BigDecimal unitPrice,
      BigDecimal lineAmount,
      String priceSource,
      @Schema(nullable = true) UUID quoteId,
      @Schema(nullable = true) PurchaseOrderEditLineSnapshot snapshot) {}

  /**
   * 行级冻结快照。
   *
   * @param quantityPerBase 1 个采购单位 = 多少基础单位（**取不到为 null**，不猜成 1）
   * @param baseQuantity 换算后的基础单位数量
   * @param conversionStatus CATALOG_UNIT_CONVERSION / MANUAL / UNKNOWN
   * @param stockAvailableQty / stockSellableQty 08 仓储的库存快照（取不到为 null）
   * @param taxRate / taxAmount 恒为 null（税率的权威在 12 财务）
   */
  public record PurchaseOrderEditLineSnapshot(
      String baseUnitCode,
      String purchaseUnitCode,
      @Schema(nullable = true) BigDecimal quantityPerBase,
      @Schema(nullable = true) BigDecimal baseQuantity,
      String conversionStatus,
      @Schema(nullable = true) UUID quoteId,
      @Schema(nullable = true) String quoteSupplierName,
      @Schema(nullable = true) String quoteCurrency,
      @Schema(nullable = true) BigDecimal quoteUnitPrice,
      @Schema(nullable = true) BigDecimal quoteMoqQuantity,
      @Schema(nullable = true) Integer quoteLeadTimeDays,
      @Schema(nullable = true) LocalDate quoteValidFrom,
      @Schema(nullable = true) LocalDate quoteValidTo,
      @Schema(nullable = true) BigDecimal stockAvailableQty,
      @Schema(nullable = true) BigDecimal stockSellableQty,
      @Schema(nullable = true) OffsetDateTime stockSnapshotAt,
      String stockStatus,
      @Schema(nullable = true) BigDecimal taxRate,
      @Schema(nullable = true) BigDecimal taxAmount,
      String taxStatus,
      OffsetDateTime snapshotAt) {}

  /**
   * 发货地址（读出）。
   */
  public record PurchaseOrderEditAddress(
      String recipientName,
      String recipientPhone,
      String countryCode,
      String province,
      String city,
      String district,
      String detail,
      String postalCode,
      String note) {}

  /**
   * 附件（读出）。
   */
  public record PurchaseOrderEditAttachment(
      UUID id,
      UUID mediaId,
      String mediaName,
      String mediaType,
      String mimeType,
      @Schema(nullable = true) BigDecimal byteSize,
      String storageKey,
      String originalFilename,
      String attachmentType,
      String note) {}

  /**
   * 金额结果（蓝图输出句：「金额与税额计算结果」）。
   *
   * @param totalAmount 头金额（**由 DB 从行表重算**，调用方传不进来）
   * @param totalQuantity 数量合计（基础单位）
   * @param taxRate / taxAmount 恒为 null（12 财务才有税率的权威）
   * @param functionalAmount 本位币折算金额（**无汇率快照时为 null**，不凑）
   */
  public record PurchaseOrderEditAmounts(
      BigDecimal totalAmount,
      String currency,
      int lineCount,
      BigDecimal totalQuantity,
      @Schema(nullable = true) BigDecimal taxRate,
      @Schema(nullable = true) BigDecimal taxAmount,
      String taxStatus,
      String taxNote,
      @Schema(nullable = true) String functionalCurrency,
      @Schema(nullable = true) BigDecimal fxRateToFunctional,
      @Schema(nullable = true) LocalDate fxRateEffectiveDate,
      String fxStatus,
      @Schema(nullable = true) BigDecimal functionalAmount,
      String note) {}

  /**
   * 采购单编辑表单（读出）。
   *
   * @param sourceType MANUAL（手工建单）/ PURCHASE_REQUEST_CONVERSION（来自采购申请）
   * @param sourceDraftNo 来自 6.2.2.2 合并草稿时的草稿号（其它来源为 null）
   * @param version 乐观锁版本号（编辑时要原样带回）
   * @param editable 现在能不能改（只有草稿能改）
   * @param editableReason 不能改时的原因（提交后要改得走变更单）
   */
  public record PurchaseOrderEditForm(
      UUID id,
      String orderNo,
      String status,
      String statusLabel,
      String sourceType,
      @Schema(nullable = true) String sourceDraftNo,
      @Schema(nullable = true) String sourceRequestNo,
      UUID supplierRefId,
      String supplierCode,
      String supplierName,
      @Schema(nullable = true) UUID legalEntityId,
      String legalEntityCode,
      String legalEntityName,
      String currency,
      UUID targetWarehouseId,
      String targetWarehouseCode,
      String targetWarehouseName,
      LocalDate promisedArrivalDate,
      String note,
      long version,
      boolean editable,
      String editableReason,
      PurchaseOrderEditAmounts amounts,
      List<PurchaseOrderEditLine> lines,
      @Schema(nullable = true) PurchaseOrderEditAddress shipAddress,
      List<PurchaseOrderEditAttachment> attachments,
      @Schema(nullable = true) OffsetDateTime createdAt,
      @Schema(nullable = true) OffsetDateTime updatedAt) {}

  /**
   * 报价 / 库存 / 单位换算预览（带出报价与库存快照）。
   *
   * @param candidates 该 SKU + 该供应商的全部有效报价候选（**并列时如实列出来**）
   * @param selected 唯一可采用的那条（**歧义时为 null** —— 不替用户挑）
   * @param ambiguous 是否歧义（同一优先级多条有效报价）
   */
  public record PurchaseOrderEditQuotePreview(
      UUID skuId,
      String skuCode,
      String skuName,
      String baseUnit,
      String purchaseUnitCode,
      @Schema(nullable = true) BigDecimal quantityPerBase,
      @Schema(nullable = true) BigDecimal baseQuantity,
      String conversionStatus,
      List<PurchaseOrderEditQuoteCandidate> candidates,
      @Schema(nullable = true) PurchaseOrderEditQuoteCandidate selected,
      boolean ambiguous,
      @Schema(nullable = true) String ambiguityProblem,
      @Schema(nullable = true) BigDecimal stockAvailableQty,
      @Schema(nullable = true) BigDecimal stockSellableQty,
      String stockStatus,
      String stockNote,
      String note) {}

  /**
   * 建单参照（供应商 / 目标仓 / 法人 / 币种）。
   *
   * <p>四样都来自**真实数据**：供应商来自 catalog（party）、目标仓与法人来自 warehouse、
   * 币种来自 catalog 的法人本位币 + 报价币种。**没有一个是硬编码词表。**
   */
  public record PurchaseOrderEditOptions(
      List<PurchaseOrderEditSupplierOption> suppliers,
      List<PurchaseOrderEditWarehouseOption> warehouses,
      List<PurchaseOrderEditLegalEntityOption> legalEntities,
      List<PurchaseOrderEditCurrencyOption> currencies,
      String note) {}

  /** 供应商下拉项（catalog 的 SUPPLIER 角色）。 */
  public record PurchaseOrderEditSupplierOption(
      UUID roleId, String partyCode, String partyName, String partyStatus) {}

  /** 目标仓下拉项（warehouse）。 */
  public record PurchaseOrderEditWarehouseOption(
      UUID id, String code, String name, String countryCode, String status) {}

  /** 法人下拉项（warehouse；本位币来自 catalog 的法人档案）。 */
  public record PurchaseOrderEditLegalEntityOption(
      UUID id,
      String code,
      String name,
      String status,
      @Schema(nullable = true) String baseCurrency) {}

  /** 币种下拉项（来自真实数据：报价币种 + 法人本位币）。 */
  public record PurchaseOrderEditCurrencyOption(String currency, String source, long orderCount) {}

  /**
   * 由 6.2.2.2 草稿建单的结果。
   *
   * @param created 建成的采购单（每张合并草稿一张单：一个草稿一个供应商 ⇒ 不能跨供应商合单）
   * @param rejected 被拒的草稿与原因（**不静默丢弃**：丢掉的草稿等于少订的货）
   */
  public record PurchaseOrderEditFromDraftResult(
      List<PurchaseOrderEditCreatedOrder> created,
      List<PurchaseOrderEditRejectedDraft> rejected) {}

  /** 建成的采购单。 */
  public record PurchaseOrderEditCreatedOrder(
      UUID orderId,
      String orderNo,
      UUID sourceDraftId,
      String sourceDraftNo,
      int lineCount,
      BigDecimal totalAmount,
      String currency) {}

  /** 被拒的合并草稿与原因码。 */
  public record PurchaseOrderEditRejectedDraft(UUID draftId, String reasonCode, String reason) {}

  /**
   * 能力声明里的一条。
   *
   * @param code 维度/能力码
   * @param available 本模块**真的**能不能做
   * @param ownerModule 归属模块（谁交付之后它才会变成真数据）
   * @param reason 为什么不能 / 前提是什么
   */
  public record PurchaseOrderEditCapability(
      String code, String label, boolean available, String ownerModule, String reason) {}

  /**
   * 能力声明。
   *
   * @param capabilities 逐条列出本模块的能力与边界
   */
  public record PurchaseOrderEditCapabilities(
      List<PurchaseOrderEditCapability> capabilities, String note) {}
}
