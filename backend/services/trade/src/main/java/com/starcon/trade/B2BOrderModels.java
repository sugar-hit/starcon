package com.starcon.trade;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 9.7.1.1 批发订单与账期的传输模型。
 *
 * <p>蓝图原话：「支持 B2B/批发订单：客户分级价、起订量、阶梯价、账期结算（不即时收款）、
 * 开票与对账；与零售订单在订单类型、收款方式、成本口径上区分」，
 * 输出「批发订单 + 应收（按账期，12 应收）；开票需求」。
 *
 * <p><b>本文件的每一个字段都对应 V120 迁移里的一个真实列</b>，枚举值域与 DB 的 CHECK
 * 逐条对齐 —— 只改 Java 不改迁移，DB 会拒绝写入，而报错对调用方毫无意义。
 *
 * <p><b>四条建模纪律</b>：
 *
 * <ul>
 *   <li><b>与零售区分是三个字段，不是一个标记</b>：{@link B2BOrderType}（订单类型）、
 *       {@link B2BPaymentMethod}（收款方式）、{@link B2BCostBasis}（成本口径）三者都写在自己的
 *       {@code b2b_order} 表上，与零售的 {@code sales_order} 互不覆盖。「区分」因此是结构性的。
 *   <li><b>收款方式决定要不要产生应收</b>：{@link B2BPaymentMethod#ORDER_SETTLEMENT} 下单结算时
 *       款项已收讫，<b>不产生应收</b>；{@link B2BPaymentMethod#ON_RECEIPT} 与
 *       {@link B2BPaymentMethod#CREDIT_TERM} 在<b>发货确认时</b>产生应收，到期日按同一算法算。
 *       「账期结算不即时收款」因此是数据层的 CHECK。
 *   <li><b>单价不接受调用方传值</b>：批发单价由<b>阶梯价落档</b>算出，
 *       {@link B2BOrderLineInput} 只有 {@code skuId} 与 {@code quantity}。
 *       允许客户端传单价等于允许绕过阶梯价与起订量两条硬规则。
 *   <li><b>客户分级与账期取自上游投影</b>：{@link B2BCustomerTermsInput} 是 3.6.1.2 的
 *       trade 侧只读投影的写入形状，订单创建时读取它决定账期天数，
 *       <b>不跨库回查 catalog</b>（AGENTS.md 模块边界纪律）。
 * </ul>
 *
 * <p>命名统一用 {@code B2B*} 前缀：全仓契约是 {@code tools/codegen/catalog-contracts.py} 把
 * <b>所有服务</b>的 spec 合并进同一个扁平文件，同名 key 后者覆盖前者且不报冲突，
 * 故刻意避开 {@code Trade*}（crm 贸易合规域）与已占用的 {@code SalesOrder*} / {@code ProcessingOrder*}。
 */
public final class B2BOrderModels {

  private B2BOrderModels() {}

  // ------------------------------------------------------------ 枚举

  /** 订单类型（DB {@code b2b_order.order_type} 的 CHECK 值域）。 */
  public enum B2BOrderType {
    /** 批发零售型：按客户分级价走量，最常见的一种。 */
    B2B_WHOLESALE,
    /** 分销型：面向经销商，走 9.7.1.2 的授权与返点口径。 */
    B2B_DISTRIBUTION,
    /** 项目型：一次性项目采购，对账口径与常规批发不同。 */
    B2B_PROJECT
  }

  /**
   * 收款方式（DB {@code b2b_order.payment_method} 的 CHECK 值域）。
   *
   * <p>蓝图硬要求：「账期结算不即时收款 —— 收款方式字段要能表达
   * 「下单结算 / 确认收货后结算 / 账期」」。三个值一字对应那句话里的三个词。
   */
  public enum B2BPaymentMethod {
    /** 下单结算：下单时款项已收讫，<b>不产生应收</b>。 */
    ORDER_SETTLEMENT,
    /** 确认收货后结算：发货确认时产生应收，到期日 = 发货确认时刻。 */
    ON_RECEIPT,
    /** 账期：发货确认时产生应收，到期日 = 发货确认时刻 + 账期天数。 */
    CREDIT_TERM
  }

  /** 成本口径（DB {@code b2b_order.cost_basis} 的 CHECK 值域）。 */
  public enum B2BCostBasis {
    /** 标准成本：按财务预设的标准成本结转。 */
    STANDARD,
    /** 移动加权：按当前移动加权平均成本结转。 */
    MOVING_WEIGHTED,
    /** 批次成本：按发货批次的实际成本结转。 */
    LOT
  }

  /** 订单状态（DB {@code b2b_order.status} 的 CHECK 值域，单向状态机）。 */
  public enum B2BStatus {
    /** 草稿：行、落档可改。 */
    DRAFT,
    /** 已确认：明细与阶梯落档冻结，进入发货。 */
    CONFIRMED,
    /** 部分发货：已发一部分，剩余待发。 */
    PARTIALLY_SHIPPED,
    /** 全部发货 —— 本模块的终态。 */
    SHIPPED
  }

  /** 客户分级（与 catalog V30 {@code party_customer_profile.customer_level} 同词表）。 */
  public enum B2BCustomerLevel {
    STRATEGIC,
    KEY,
    STANDARD,
    PROSPECT,
    RISK
  }

  /** 应收状态（DB {@code b2b_receivable.status}）。 */
  public enum B2BReceivableStatus {
    OPEN,
    SETTLED,
    CANCELLED
  }

  /**
   * 开票需求状态（DB {@code b2b_receivable.invoice_state}）。
   *
   * <p>蓝图输出里的「开票需求」。本服务只把需求标记出来，
   * <b>不写 12 财务的表</b>：12.6.2 发票交付后由它读这张表。
   */
  public enum B2BInvoiceState {
    NOT_REQUESTED,
    REQUESTED,
    INVOICED
  }

  /** 订单事件类型（DB {@code b2b_order_event.event_type}）。 */
  public enum B2BEventType {
    CREATED,
    UPDATED,
    CONFIRMED,
    SHIPPED,
    INVOICE_REQUESTED
  }

  // ------------------------------------------------------------ 上游投影写入

  /**
   * 3.6.1.2 客户档案的 trade 侧只读投影（幂等 upsert，键 = customerPartyId）。
   *
   * <p>{@code sourceVersion} 单调递增：乱序到达的旧事件不得覆盖新值（DB 触发器再拦一次）。
   */
  public record B2BCustomerTermsInput(
      @NotNull UUID customerPartyId,
      @Size(max = 32) String customerCode,
      @Size(max = 160) String customerName,
      @NotNull B2BCustomerLevel customerLevel,
      @Min(0) @Max(365) Integer paymentTermsDays,
      @Pattern(regexp = "^[A-Z]{3}$", message = "默认币种必须是 ISO 4217 三位码")
          String defaultCurrency,
      @DecimalMin(value = "0", inclusive = false) @Digits(integer = 15, fraction = 4)
          BigDecimal creditLimit,
      @Min(0) Long sourceVersion) {}

  /** 05 价目阶梯价的 trade 侧投影写入（价目表头：SKU × 客户分级）。 */
  public record B2BPriceBookInput(
      @NotNull UUID skuId,
      @Size(max = 64) String skuCode,
      @Size(max = 200) String skuName,
      @NotNull B2BCustomerLevel customerLevel,
      @NotNull @Pattern(regexp = "^[A-Z]{3}$", message = "币种必须是 ISO 4217 三位码") String currency,
      /** 起订量。0 = 不限起订量（拼箱 / 试样）。 */
      @NotNull @DecimalMin("0") @Digits(integer = 13, fraction = 6) BigDecimal moqQuantity,
      @Min(0) Long sourceVersion) {}

  /** 阶梯价的一个档位（下界含）。区间是 {@code (上一档下界, 本档下界]}。 */
  public record B2BPriceTierInput(
      @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 13, fraction = 6)
          BigDecimal minQuantity,
      @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 15, fraction = 4)
          BigDecimal unitPrice) {}

  /** 阶梯价投影的写入请求：表头（SKU × 分级 + 起订量）+ 全部档位（整体替换）。 */
  public record B2BPriceBookRequest(
      @NotNull @Valid B2BPriceBookInput book,
      @NotEmpty @Size(max = 50) List<@Valid B2BPriceTierInput> tiers) {}

  // ------------------------------------------------------------ 订单写入

  /** 创建批发订单。行只给 SKU 与数量：单价、落档、起订量全部由系统算。 */
  public record B2BOrderCreateInput(
      @NotBlank @Size(max = 48) String orderNo,
      @NotNull B2BOrderType orderType,
      @NotNull UUID customerPartyId,
      @NotNull @Pattern(regexp = "^[A-Z]{3}$", message = "币种必须是 ISO 4217 三位码") String currency,
      @NotNull B2BPaymentMethod paymentMethod,
      @Min(0) @Max(365) Integer paymentTermsDays,
      @NotNull B2BCostBasis costBasis,
      @NotEmpty @Size(max = 200) List<@Valid B2BOrderLineInput> lines,
      @Size(max = 500) String note) {}

  /**
   * 订单行输入。
   *
   * <p><b>刻意没有 unitPrice 字段</b>：批发单价是阶梯价落档的结果。
   * 允许客户端传单价等于允许绕过起订量与阶梯价两条硬规则 ——
   * 那正是本模块要防的事。
   */
  public record B2BOrderLineInput(
      @NotNull UUID skuId,
      @Size(max = 64) String skuCode,
      @Size(max = 200) String skuName,
      @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 13, fraction = 6)
          BigDecimal quantity,
      @Size(max = 500) String note) {}

  /** 更新草稿（整体替换行）。确认后由 DB 触发器冻结。 */
  public record B2BOrderUpdateInput(
      @NotEmpty @Size(max = 200) List<@Valid B2BOrderLineInput> lines,
      @Size(max = 500) String note) {}

  /** 发货确认。这是应收的<b>唯一</b>产生入口。 */
  public record B2BShipmentInput(
      @NotBlank @Size(max = 48) String shipmentNo,
      /** 发货时刻：账期从它起算。不传则取当前时刻。 */
      OffsetDateTime shippedAt,
      @Size(max = 64) String carrierRef,
      @NotEmpty @Size(max = 200) List<@Valid B2BShipmentLineInput> lines,
      @Size(max = 500) String note) {}

  /** 发货明细行：引用订单行 + 本次发货数量。 */
  public record B2BShipmentLineInput(
      @NotNull UUID orderLineId,
      @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 13, fraction = 6)
          BigDecimal quantity) {}

  // ------------------------------------------------------------ 读模型

  /** 客户投影行。 */
  public record B2BCustomerTermsRow(
      UUID id,
      UUID customerPartyId,
      String customerCode,
      String customerName,
      B2BCustomerLevel customerLevel,
      int paymentTermsDays,
      String defaultCurrency,
      BigDecimal creditLimit,
      long sourceVersion,
      OffsetDateTime syncedAt) {}

  /** 阶梯价试算结果：某一数量在本 SKU / 客户分级下命中的档位。 */
  public record B2BPriceQuoteRow(
      UUID skuId,
      String skuCode,
      B2BCustomerLevel customerLevel,
      BigDecimal moqQuantity,
      BigDecimal quantity,
      /** 命中的档位下界（含）。数量恰好等于它时落在这一档。 */
      BigDecimal appliedTierMinQuantity,
      BigDecimal unitPrice,
      BigDecimal lineAmount,
      /** 命中的是否为该阶梯的最低档（界面用来提示「再买 N 件进下一档」）。 */
      boolean lowestTier,
      BigDecimal nextTierMinQuantity,
      BigDecimal nextTierUnitPrice) {}

  /** 价目表头 + 阶梯的读模型。 */
  public record B2BPriceBookRow(
      UUID id,
      UUID skuId,
      String skuCode,
      String skuName,
      B2BCustomerLevel customerLevel,
      String currency,
      BigDecimal moqQuantity,
      long sourceVersion,
      OffsetDateTime syncedAt,
      List<B2BPriceTierRow> tiers) {}

  /** 单个阶梯档位。 */
  public record B2BPriceTierRow(
      UUID id, BigDecimal minQuantity, BigDecimal unitPrice, OffsetDateTime createdAt) {}

  /** 订单列表行。 */
  public record B2BOrderRow(
      UUID id,
      String orderNo,
      B2BOrderType orderType,
      UUID customerPartyId,
      String customerName,
      B2BCustomerLevel customerLevel,
      String currency,
      B2BPaymentMethod paymentMethod,
      int paymentTermsDays,
      B2BCostBasis costBasis,
      B2BStatus status,
      BigDecimal totalAmount,
      BigDecimal receivableAmount,
      /** 订单总额 − 累计已确认应收。未发货的部分就是它。 */
      BigDecimal unshippedAmount,
      int lineCount,
      OffsetDateTime confirmedAt,
      OffsetDateTime createdAt,
      int version) {}

  /** 订单行读模型，带落档说明。 */
  public record B2BOrderLineRow(
      UUID id,
      int lineNo,
      UUID skuId,
      String skuCode,
      String skuName,
      BigDecimal quantity,
      BigDecimal shippedQuantity,
      BigDecimal moqQuantity,
      UUID appliedTierId,
      BigDecimal appliedTierMinQuantity,
      BigDecimal unitPrice,
      BigDecimal lineAmount,
      String note) {}

  /** 发货单读模型。 */
  public record B2BShipmentRow(
      UUID id,
      String shipmentNo,
      OffsetDateTime shippedAt,
      String carrierRef,
      String note,
      /** 本次发货确认的应收 id；下单结算方式下为 null（款项已收，不产生应收）。 */
      UUID receivableId,
      List<B2BShipmentLineRow> lines) {}

  /** 发货明细行。 */
  public record B2BShipmentLineRow(
      UUID id, UUID orderLineId, String skuCode, BigDecimal quantity, BigDecimal unitPrice,
      BigDecimal lineAmount) {}

  /** 应收单读模型。 */
  public record B2BReceivableRow(
      UUID id,
      String receivableNo,
      UUID orderId,
      String orderNo,
      UUID shipmentId,
      UUID customerPartyId,
      String customerName,
      String currency,
      B2BPaymentMethod paymentMethod,
      int paymentTermsDays,
      BigDecimal amount,
      OffsetDateTime confirmedAt,
      OffsetDateTime dueDate,
      B2BReceivableStatus status,
      B2BInvoiceState invoiceState,
      OffsetDateTime invoiceRequestedAt,
      List<B2BReceivableLineRow> lines) {}

  /** 应收明细行。 */
  public record B2BReceivableLineRow(
      UUID id, UUID orderLineId, String skuCode, BigDecimal quantity, BigDecimal unitPrice,
      BigDecimal lineAmount) {}

  /** 订单事件流行。 */
  public record B2BEventRow(
      UUID id,
      B2BEventType eventType,
      String fromStatus,
      String toStatus,
      String reason,
      UUID actorId,
      OffsetDateTime occurredAt) {}

  /** 订单详情：一次调用返回整屏。 */
  public record B2BOrderDetail(
      B2BOrderRow order,
      B2BCustomerTermsRow terms,
      List<B2BOrderLineRow> lines,
      List<B2BShipmentRow> shipments,
      List<B2BReceivableRow> receivables,
      List<B2BEventRow> events) {}

  /** 客户投影列表：真分页。 */
  public record B2BCustomerTermsPage(
      List<B2BCustomerTermsRow> items, int page, int size, long total) {}

  /** 阶梯价列表：真分页，每项自带全部档位。 */
  public record B2BPriceBookPage(
      List<B2BPriceBookRow> items, int page, int size, long total) {}

  /** 订单列表：真分页 + 与分页无关的全量汇总。 */
  public record B2BOrderPage(
      List<B2BOrderRow> items,
      int page,
      int size,
      long total,
      long draft,
      long confirmed,
      long partiallyShipped,
      long shipped,
      /** 账期应收未结总额（到期日已过仍 OPEN 的）。 */
      BigDecimal overdueAmount,
      /** 全部未结应收总额（OPEN）。 */
      BigDecimal openReceivableAmount) {}

  /** 应收列表：真分页 + 汇总。 */
  public record B2BReceivablePage(
      List<B2BReceivableRow> items,
      int page,
      int size,
      long total,
      BigDecimal openAmount,
      BigDecimal overdueAmount,
      /** 待开票张数（已提出开票需求、尚未开票）。 */
      long invoicePending) {}

  /**
   * 阶梯价试算返回：每一行命中的档位与「再买多少进下一档」。
   *
   * <p>刻意叫 {@code B2BQuoteResponse} 而不是 {@code QuoteResponse}：全仓契约是所有服务的
   * spec 合并后的产物，一个过于通用的名字迟早会与别的域撞上。
   */
  public record B2BQuoteResponse(List<B2BPriceQuoteRow> items) {}

  /** 阶梯价试算请求（一次可试多个 SKU × 多个数量）。 */
  public record B2BPriceQuoteRequest(
      @NotEmpty @Size(max = 50) List<@Valid B2BPriceQuoteLineInput> lines,
      @NotNull B2BCustomerLevel customerLevel) {}

  /** 试算行：SKU 与数量。 */
  public record B2BPriceQuoteLineInput(
      @NotNull UUID skuId,
      @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 13, fraction = 6)
          BigDecimal quantity) {}

  /** 写操作返回值。 */
  public record B2BSaved(
      UUID id,
      String reference,
      B2BStatus status,
      BigDecimal totalAmount,
      BigDecimal receivableAmount,
      String message) {}

  /** 上游 / 下游缺口声明（capabilities 用，形状与已交付模块刻意错开以免契约同名覆盖）。 */
  public record B2BDownstreamGap(
      String moduleId,
      String moduleName,
      String service,
      boolean available,
      String ownerModule,
      String effect) {}

  /** 本模块能力与上下游可用性。 */
  public record B2BCapabilities(
      List<B2BOrderType> orderTypes,
      List<B2BPaymentMethod> paymentMethods,
      List<B2BCostBasis> costBases,
      List<B2BStatus> statuses,
      List<B2BCustomerLevel> customerLevels,
      List<B2BInvoiceState> invoiceStates,
      List<String> rules,
      boolean upstreamCustomerProfileAvailable,
      String upstreamCustomerProfileNote,
      boolean upstreamPriceBookAvailable,
      String upstreamPriceBookNote,
      boolean downstreamReceivableAvailable,
      String downstreamReceivableNote,
      boolean downstreamFulfillmentAvailable,
      String downstreamFulfillmentNote,
      List<B2BDownstreamGap> gaps) {}
}
