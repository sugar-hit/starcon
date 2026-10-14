package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 9.6.1.1 代发模式配置的传输模型。
 *
 * <p>蓝图原话：「配置店铺/SKU 是否走代发（一件代发），以及代发供应商、结算方式 （下单/确认收货后结算、差价抽成）、可售校验规则；区分『自营发货』与『供应商直发』
 * 两种履约路径。纯代理代发不占用自有仓库存；其他直发模式的货权与结算规则须单独确认。」 输出是「代发模式生效配置」与「代发可售标记（决定是否可售、是否可刊登）」—— 后者是硬输出，所以
 * {@link DropshipEvaluationRow} 里 {@code sellable} 与 {@code listable} **分成两列**，且都带原因码与原因说明。
 *
 * <p><b>命名纪律（硬要求，不是风格偏好）</b>：springdoc 用 record 的 <b>简单类名</b>做 {@code components.schemas} 的键，而本仓已有
 * {@code WarehouseRow}、 {@code AllocationRequest} 等同名 record，同名会互相覆盖。因此本文件所有 record 与 enum 一律以
 * {@code Dropship} 开头。
 *
 * <p><b>三条建模纪律</b>：
 *
 * <ul>
 *   <li><b>物流与货权是两个字段，不是一个。</b>{@link DropshipFulfillmentPath}（谁发货）与 {@link
 *       DropshipOwnershipMode}（货权在谁）互不推导；「不占自有仓库存」是两者 <b>同时</b>成立才有的结论，由 DB 生成列 {@code
 *       counts_against_own_inventory} / {@code pure_agency_exempt} 钉死，见 V118 文件头第一节。
 *   <li><b>差价抽成比例一律 BigDecimal</b>，前端拿到的也是 number，不在前端算抽佣。
 *   <li><b>「没有观测」与「观测为 0」不是一回事</b>：{@code supplierAvailableQty} 为 {@code null} 表示没人报过供应商库存，{@code
 *       0} 表示供应商确实没货。
 * </ul>
 */
public final class DropshipConfigModels {

  private DropshipConfigModels() {}

  // ------------------------------------------------------------ 枚举

  /** 适用范围：按店铺或按 SKU 生效（DB CHECK 二选一）。 */
  public enum DropshipScopeKind {
    SHOP("按店铺"),
    SKU("按 SKU");

    private final String label;

    DropshipScopeKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 履约路径（**物流**维度：谁发货、货经过谁的手）。 */
  public enum DropshipFulfillmentPath {
    SELF_FULFILLED("自营发货"),
    SUPPLIER_DIRECT("供应商直发");

    private final String label;

    DropshipFulfillmentPath(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 货权模式（**货权**维度：结算前货算谁的）。
   *
   * <p>与 {@link DropshipFulfillmentPath} 刻意分开：蓝图加粗句「不经自有仓不代表不承担货权」。 {@code SUPPLIER_DIRECT +
   * OUR_ACCOUNT} 就是「供应商替我发货、但货权在我方」（VMI 型）， 它<b>必须</b>占用我方库存口径，而且需要单独确认。
   */
  public enum DropshipOwnershipMode {
    PURE_AGENCY("纯代理（货权归供应商，不记我方库存）"),
    OUR_ACCOUNT("我方货权（须单独确认）");

    private final String label;

    DropshipOwnershipMode(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 结算周期。 */
  public enum DropshipSettlementTiming {
    ON_ORDER("下单结算"),
    ON_RECEIPT("确认收货后结算");

    private final String label;

    DropshipSettlementTiming(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 结算方式。 */
  public enum DropshipSettlementBasis {
    FLAT_COST("固定供货价"),
    MARGIN_SHARE("差价抽成");

    private final String label;

    DropshipSettlementBasis(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 可售校验方式。蓝图的「可售校验（供应商库存是否足够）」落在这里。 */
  public enum DropshipAvailabilityCheckMode {
    SUPPLIER_STOCK("按供应商库存校验"),
    CONFIRM_ON_ORDER("下单时向供应商确认"),
    SKIP("不校验");

    private final String label;

    DropshipAvailabilityCheckMode(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 刊登策略：决定「不可售」之后刊登要不要下架。 */
  public enum DropshipListingPolicy {
    ALWAYS_LIST("始终刊登"),
    PAUSE_WHEN_UNSELLABLE("不可售即下架"),
    PAUSE_WHEN_NEGATIVE_MARGIN("差价为负即下架"),
    MANUAL("人工决定");

    private final String label;

    DropshipListingPolicy(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 配置状态。CLOSED 是终态。 */
  public enum DropshipConfigStatus {
    DRAFT("草稿"),
    ACTIVE("生效中"),
    SUSPENDED("已暂停"),
    CLOSED("已关闭");

    private final String label;

    DropshipConfigStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 可售判定的原因码。SELLABLE 之外的每一种都要能说清「缺什么」。 */
  public enum DropshipSellReasonCode {
    SELLABLE("可售可刊登"),
    CONFIG_NOT_ACTIVE("配置未生效"),
    CONFIG_NOT_EFFECTIVE("不在有效期内"),
    NO_SUPPLIER_STOCK_RECORD("无供应商库存记录"),
    SUPPLIER_STOCK_INSUFFICIENT("供应商库存不足"),
    NEGATIVE_MARGIN("差价为负"),
    LISTING_PAUSED("刊登策略已暂停"),
    QUOTE_EXPIRED("供应商报价已过期"),
    SHOP_INACTIVE("店铺已停用"),
    LINE_INACTIVE("明细行已停用");

    private final String label;

    DropshipSellReasonCode(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 店铺投影来源（2.3.1 channel 服务）。 */
  public enum DropshipShopSource {
    MODULE_2_3_1("2.3.1 店铺（channel）"),
    TENANT_MAINTAINED("租户手工登记");

    private final String label;

    DropshipShopSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 供应商报价投影来源（3.6.2 catalog 服务）。 */
  public enum DropshipSupplierSource {
    MODULE_3_6_2("3.6.2 供应商报价（catalog）"),
    TENANT_MAINTAINED("租户手工登记");

    private final String label;

    DropshipSupplierSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 供应商库存观测来源。 */
  public enum DropshipStockSource {
    MODULE_7_1_1_3("7.1.1.3 可用量（warehouse）"),
    MODULE_3_6_2("3.6.2 供应商报价（catalog）"),
    TENANT_MAINTAINED("租户手工登记");

    private final String label;

    DropshipStockSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ------------------------------------------------------------ 能力声明

  /** 本模块的能力与上游可用性。存在的理由见 {@code DropshipConfigService#capabilities()}。 */
  public record DropshipCapabilities(
      List<DropshipCapabilityDimension> dimensions,
      List<DropshipScopeKind> scopeKinds,
      List<DropshipFulfillmentPath> fulfillmentPaths,
      List<DropshipOwnershipMode> ownershipModes,
      List<DropshipSettlementTiming> settlementTimings,
      List<DropshipSettlementBasis> settlementBases,
      List<DropshipAvailabilityCheckMode> availabilityCheckModes,
      List<DropshipListingPolicy> listingPolicies,
      List<DropshipConfigStatus> configStatuses,
      List<DropshipSellReasonCode> sellReasonCodes,
      boolean crossServiceReadAvailable,
      boolean reservesOwnInventory,
      String upstreamNote,
      String ownershipNote,
      String note) {

    /**
     * 一个输入维度是否可用。
     *
     * @param source 该维度的上游归属（服务 + 模块），便于调用方知道去哪补
     */
    public record DropshipCapabilityDimension(
        String code,
        String label,
        boolean available,
        String source,
        @Schema(nullable = true) String unavailableReason) {}
  }

  // ------------------------------------------------------------ 店铺投影（2.3.1）

  public record DropshipShopRefRow(
      UUID id,
      String shopCode,
      String shopName,
      String platformCode,
      String regionCode,
      boolean active,
      String sourceSystem,
      OffsetDateTime syncedAt,
      OffsetDateTime updatedAt) {}

  public record DropshipShopRefInput(
      @NotBlank @Size(max = 64) String shopCode,
      @NotBlank @Size(max = 200) String shopName,
      @NotBlank @Size(max = 32) String platformCode,
      @Size(max = 32) String regionCode,
      Boolean active,
      @NotBlank String sourceSystem,
      @NotNull OffsetDateTime syncedAt) {}

  public record DropshipShopRefSaved(UUID id, String shopCode, boolean created) {}

  public record DropshipShopRefQuery(
      String shopCode,
      String platformCode,
      Boolean activeOnly,
      String keyword,
      int page,
      int size) {}

  public record DropshipShopRefPage(
      List<DropshipShopRefRow> items, long total, int page, int size) {}

  // ------------------------------------------------------------ 供应商报价投影（3.6.2）

  public record DropshipSupplierRefRow(
      UUID id,
      String supplierCode,
      String supplierName,
      String currencyCode,
      @Schema(nullable = true) java.time.LocalDate quoteValidFrom,
      @Schema(nullable = true) java.time.LocalDate quoteValidTo,
      String quoteSource,
      @Schema(nullable = true) BigDecimal leadTimeDays,
      boolean active,
      OffsetDateTime syncedAt,
      OffsetDateTime updatedAt) {}

  public record DropshipSupplierRefInput(
      @NotBlank @Size(max = 64) String supplierCode,
      @NotBlank @Size(max = 200) String supplierName,
      @NotBlank @Size(max = 3) String currencyCode,
      @Schema(nullable = true) java.time.LocalDate quoteValidFrom,
      @Schema(nullable = true) java.time.LocalDate quoteValidTo,
      @NotBlank String quoteSource,
      @Schema(nullable = true) BigDecimal leadTimeDays,
      Boolean active,
      @NotNull OffsetDateTime syncedAt) {}

  public record DropshipSupplierRefSaved(UUID id, String supplierCode, boolean created) {}

  public record DropshipSupplierRefQuery(
      String supplierCode,
      String quoteSource,
      Boolean activeOnly,
      Boolean quoteValidOnly,
      String keyword,
      int page,
      int size) {}

  public record DropshipSupplierRefPage(
      List<DropshipSupplierRefRow> items, long total, int page, int size) {}

  // ------------------------------------------------------------ 代发配置

  /**
   * 配置行读模型。
   *
   * <p>{@code countsAgainstOwnInventory} 与 {@code pureAgencyExempt} 是 **DB 生成列**， 服务层只读不算 —— 见 V118
   * 文件头第一节「物流路径 ≠ 货权」。
   */
  public record DropshipConfigRow(
      UUID id,
      String configCode,
      String configName,
      String scopeKind,
      String shopCode,
      String skuRef,
      String fulfillmentPath,
      String supplierCode,
      String ownershipMode,
      boolean countsAgainstOwnInventory,
      boolean pureAgencyExempt,
      String settlementTiming,
      String settlementBasis,
      @Schema(nullable = true) BigDecimal marginShareRate,
      String currencyCode,
      BigDecimal supplierCost,
      BigDecimal referenceSellPrice,
      BigDecimal grossSpread,
      BigDecimal expectedMarginAmount,
      BigDecimal expectedSupplierAmount,
      String availabilityCheckMode,
      @Schema(nullable = true) BigDecimal minSupplierStockBuffer,
      String listingPolicy,
      int priority,
      String status,
      @Schema(nullable = true) OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      @Schema(nullable = true) OffsetDateTime ownershipConfirmedAt,
      @Schema(nullable = true) String ownershipConfirmedNote,
      String ownershipReviewedWith,
      String note,
      int version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * 配置录入。
   *
   * <p>{@code ownershipConfirmedAt} / {@code ownershipConfirmedByMembershipId} / {@code
   * ownershipConfirmedNote} 三者在 {@code ownershipMode != PURE_AGENCY} 时 **必填**（DB CHECK +
   * 触发器两层强制）；{@code PURE_AGENCY} 时必须留空 —— 纯代理不需要「单独确认」，硬塞一个确认时间会让人误以为它也被复核过。
   */
  public record DropshipConfigInput(
      @NotBlank @Size(max = 32) String configCode,
      @NotBlank @Size(max = 200) String configName,
      @NotBlank String scopeKind,
      @Size(max = 64) String shopCode,
      @Size(max = 64) String skuRef,
      @NotBlank String fulfillmentPath,
      @Size(max = 64) String supplierCode,
      @NotBlank String ownershipMode,
      @Schema(nullable = true) OffsetDateTime ownershipConfirmedAt,
      @Schema(nullable = true) UUID ownershipConfirmedByMembershipId,
      @Schema(nullable = true) @Size(max = 2000) String ownershipConfirmedNote,
      @Size(max = 64) String ownershipReviewedWith,
      @NotBlank String settlementTiming,
      @NotBlank String settlementBasis,
      @Schema(nullable = true) BigDecimal marginShareRate,
      @NotBlank @Size(max = 3) String currencyCode,
      @NotNull BigDecimal supplierCost,
      @NotNull BigDecimal referenceSellPrice,
      @NotBlank String availabilityCheckMode,
      @Schema(nullable = true) BigDecimal minSupplierStockBuffer,
      @NotBlank String listingPolicy,
      Integer priority,
      @Schema(nullable = true) OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      @Size(max = 2000) String note) {}

  public record DropshipConfigSaved(UUID id, String configCode, boolean created) {}

  public record DropshipConfigQuery(
      String configCode,
      String scopeKind,
      String shopCode,
      String skuRef,
      String fulfillmentPath,
      String ownershipMode,
      String settlementBasis,
      String status,
      Boolean effectiveOnly,
      Boolean countsAgainstOwnInventory,
      String keyword,
      int page,
      int size) {}

  public record DropshipConfigPage(List<DropshipConfigRow> items, long total, int page, int size) {}

  /** 状态流转：仅允许 DRAFT → ACTIVE → SUSPENDED ↔ ACTIVE → CLOSED。 */
  public record DropshipConfigStatusInput(
      @NotBlank String status, @Schema(nullable = true) @Size(max = 2000) String note) {}

  // ------------------------------------------------------------ 配置明细行（SKU）

  public record DropshipConfigLineRow(
      UUID id,
      UUID configId,
      String skuRef,
      String skuName,
      @Schema(nullable = true) BigDecimal supplierCostOverride,
      @Schema(nullable = true) BigDecimal referenceSellPriceOverride,
      boolean active,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  public record DropshipConfigLineInput(
      @NotBlank @Size(max = 64) String skuRef,
      @Size(max = 200) String skuName,
      @Schema(nullable = true) BigDecimal supplierCostOverride,
      @Schema(nullable = true) BigDecimal referenceSellPriceOverride,
      Boolean active,
      @Size(max = 2000) String note) {}

  public record DropshipConfigLineSaved(UUID id, UUID configId, String skuRef, boolean created) {}

  // ------------------------------------------------------------ 供应商库存观测

  public record DropshipSupplierStockRow(
      UUID id,
      String supplierCode,
      String skuRef,
      BigDecimal availableQty,
      String stockSource,
      OffsetDateTime observedAt,
      OffsetDateTime updatedAt) {}

  public record DropshipSupplierStockInput(
      @NotBlank @Size(max = 64) String supplierCode,
      @NotBlank @Size(max = 64) String skuRef,
      @NotNull BigDecimal availableQty,
      @NotBlank String stockSource,
      @NotNull OffsetDateTime observedAt) {}

  public record DropshipSupplierStockSaved(
      UUID id, String supplierCode, String skuRef, boolean created) {}

  public record DropshipSupplierStockQuery(
      String supplierCode, String skuRef, String stockSource, String keyword, int page, int size) {}

  public record DropshipSupplierStockPage(
      List<DropshipSupplierStockRow> items, long total, int page, int size) {}

  // ------------------------------------------------------------ 可售标记（输出之二）

  /**
   * 代发可售标记。
   *
   * <p>{@code sellable} 与 {@code listable} 分开：不可售不必然要下架 —— {@code listingPolicy=ALWAYS_LIST}
   * 时就是「卖不了但先挂着」。合成一列会让页面 分不清「没货了该补货」与「该下架换供应商」这两件不同的事。
   */
  public record DropshipEvaluationRow(
      UUID id,
      UUID configId,
      @Schema(nullable = true) UUID configLineId,
      String skuRef,
      String shopCode,
      String supplierCode,
      BigDecimal requestedQty,
      @Schema(nullable = true) BigDecimal requiredQty,
      @Schema(nullable = true) BigDecimal supplierAvailableQty,
      boolean sellable,
      boolean listable,
      String reasonCode,
      String reasonDetail,
      String settlementBasis,
      @Schema(nullable = true) BigDecimal expectedMarginAmount,
      boolean countsAgainstOwnInventory,
      OffsetDateTime evaluatedAt) {}

  public record DropshipEvaluationRequest(
      @NotNull UUID configId,
      @NotBlank @Size(max = 64) String skuRef,
      @NotNull BigDecimal requestedQty) {}

  public record DropshipEvaluationSaved(
      UUID id, boolean sellable, boolean listable, String reasonCode) {}

  public record DropshipEvaluationQuery(
      UUID configId,
      String skuRef,
      String shopCode,
      String supplierCode,
      Boolean sellable,
      Boolean listable,
      String reasonCode,
      int page,
      int size) {}

  public record DropshipEvaluationPage(
      List<DropshipEvaluationRow> items, long total, int page, int size) {}

  // ------------------------------------------------------------ 事件流

  public record DropshipEventRow(
      UUID id,
      @Schema(nullable = true) UUID configId,
      String eventKind,
      String fromStatus,
      String toStatus,
      String detail,
      @Schema(nullable = true) UUID actorMembershipId,
      OffsetDateTime occurredAt) {}

  public record DropshipEventPage(List<DropshipEventRow> items, long total, int page, int size) {}
}
