package com.starcon.trade;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 9.1.2.2 手工订单的传输模型。
 *
 * <p>蓝图原话：「支持非平台来源订单（独立站线下、B2B、代发、样机单）的录入，含客户选择、
 * 商品行、收货地址、费用与支付方式」，输出「手工销售订单（来源 = MANUAL）+ 后续同样进入
 * 审单履约流程」。
 *
 * <p><b>本文件的每一个字段都对应 V99 迁移里的一个真实列</b>。上一版本车道留下的 Java 与迁移
 * 是两套互不相干的形状（列名、枚举值域、连表都不同），已被整体替换成以迁移为准的实现。
 *
 * <p><b>四条建模纪律</b>：
 *
 * <ul>
 *   <li><b>来源是字段不是表</b>：{@link SalesOrderSource} 是 {@code sales_order.order_source}
 *       这一列的枚举值。本模块只写 {@link SalesOrderSource#MANUAL}；9.1.2.1 平台订单同步（D5）
 *       写平台来源到<b>同一张表</b>，下游读法完全一致 —— 这正是蓝图「手工单与平台单共用同一条
 *       履约链路，只有来源标记不同，避免两套流程」的实现形式。值域刻意与 DB 的 CHECK <b>逐条对齐</b>。
 *   <li><b>行是快照不是引用</b>：{@link SalesOrderLineInput} 要求 SKU 的编码、名称、规格快照与
 *       映射版本<b>由调用方一并提交</b>，服务层原样冻结进 {@code sales_order_line}。
 *       商品事后改参数、改名、改映射，历史订单行<b>一个字节都不变</b>。
 *   <li><b>地址是快照不是引用</b>：{@link SalesOrderAddressInput} 收全量地址字段，
 *       服务层冻结进 {@code order_address_snapshot}。本表<b>没有</b>指向 3.6.3.1 地址的列 ——
 *       跨服务本就不建 FK，客户档案里改名或删地址都改不动已成立的订单地址。
 *   <li><b>价格由表单给，不引用未交付的价目服务</b>：05 价目（D6）未交付，因此
 *       {@link SalesOrderLineInput#unitPrice} 是必填的表单字段。本文件<b>不</b>引用任何
 *       价目服务，也<b>不</b>内置任何默认单价 —— 单价是表数据不是代码常量。
 * </ul>
 *
 * <p>命名与 {@code packages/api-contracts} 里已有的类型刻意错开：全仓契约是
 * {@code tools/codegen/catalog-contracts.py} 把<b>所有服务</b>的 spec 合并进同一个扁平文件，
 * 同名 key 后者覆盖前者且不报冲突。故本模块统一用 {@code SalesOrder*} 前缀，
 * <b>不用</b> {@code Trade*} 前缀（那 40 多个 {@code TradeSet*} / {@code TradeRule*} /
 * {@code TradeDocument*} 是 crm 贸易合规域的既有类型），也避开已占用的
 * {@code OutboundOrder*}（warehouse）与 {@code ProcessingOrder*}（trade V97）。
 */
public final class ManualOrderModels {

  private ManualOrderModels() {}

  // ------------------------------------------------------------ 枚举
  // 四个枚举的值域与 V99 的 CHECK 约束逐条对齐。加值必须走「加值迁移」（R6），
  // 不能只改这里 —— 只改 Java 会让 DB 拒绝写入，而报错信息对调用方毫无意义。

  /**
   * 订单来源（DB {@code sales_order.order_source} 的 CHECK 值域）。
   *
   * <p><b>本模块只写 {@link #MANUAL}</b>，且<b>不接受调用方传值</b>：让客户端能选来源等于开
   * 一条「手工录入通道写平台单」的后门，而外部订单号的唯一约束是按来源维度的，来源被伪造就会
   * 撞掉平台单的唯一性。其它值留给 9.1.2.1 平台订单同步（D5）与各平台适配器，届时同样由服务端写死。
   */
  public enum SalesOrderSource {
    MANUAL("手工订单"),
    PLATFORM("平台订单"),
    AMAZON("Amazon"),
    SHOPIFY("Shopify"),
    EBAY("eBay"),
    TIKTOK_SHOP("TikTok Shop"),
    ALIBABA_1688("1688");

    private final String label;

    SalesOrderSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 手工单子类（DB {@code manual_kind}）。蓝图职责句点名的四种非平台来源。
   *
   * <p>DB CHECK 强制「来源 = MANUAL ⟺ manual_kind 非空」：只写 MANUAL 不写子类时，
   * B2B / 代发 / 样机单的下游处理完全不同，来源标记不够用。
   */
  public enum SalesOrderManualKind {
    OFFLINE_STORE("独立站线下"),
    B2B("B2B 批发"),
    DROPSHIP("代发"),
    SAMPLE("样机单");

    private final String label;

    SalesOrderManualKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 订单自身生命周期（DB {@code sales_order.status}）。
   *
   * <p>值域<b>只有 DRAFT 与 SUBMITTED</b>，与 V99 的 CHECK 一致：9.2 审单未交付，
   * 审单结论（通过 / 驳回）由它用「加值」的方式扩展，本模块不预留。
   *
   * <p>没有「取消」：取消需要人写明原因并留痕，属于 9.2 的驳回动作；在审单还没交付时
   * 先造一个 CANCELLED，9.2 落地时会与它的驳回语义打架（同一个终态两个来源）。
   * 草稿的处置方式是「不提交」—— 它从未进过审单队列，不需要作废。
   */
  public enum SalesOrderStatus {
    DRAFT("草稿"),
    SUBMITTED("已提交待审");

    private final String label;

    SalesOrderStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 支付方式（服务层受控词表，与 V99 CHECK 逐条对齐）。
   *
   * <p>手工单<b>不接支付网关</b>：这里记的是与客户的<b>约定</b>（预付 / 货到付 / 电汇 /
   * 账期 / 货代代收），不是实收流水。实收由 finance 域另行记账，本表不越界。
   */
  public enum SalesOrderPaymentMethod {
    PREPAID("预付"),
    COD("货到付款"),
    BANK_TRANSFER("银行转账"),
    CREDIT_TERM("账期"),
    FREIGHT_FORWARDER("货代代收"),
    OTHER("其他");

    private final String label;

    SalesOrderPaymentMethod(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 地址角色（DB {@code order_address_snapshot.address_role}）。 */
  public enum SalesOrderAddressRole {
    SHIPPING("收货地址"),
    BILLING("开票地址");

    private final String label;

    SalesOrderAddressRole(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 费用类型（DB {@code order_charge.charge_type}）。
   *
   * <p>正负号规则由 DB CHECK 钉死：{@code DISCOUNT} 必须是负数，其余必须是正数。
   * 这样「减钱」不会在某一套实现里变成「加钱」而数据上看不出来。
   */
  public enum SalesOrderChargeType {
    SHIPPING("运费"),
    DISCOUNT("折扣"),
    TAX("税费"),
    PACKAGING("包装费"),
    OTHER("其他费用");

    private final String label;

    SalesOrderChargeType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 费用层级（DB {@code order_charge.charge_kind}）。
   *
   * <p><b>不是行级分摊明细</b>，见迁移第 6 节的说明：真正的行级分摊需要另开
   * {@code order_charge_allocation} 子表，拿这一列当分摊记号会让「这一行买了多少钱」有两个答案。
   */
  public enum SalesOrderChargeLevel {
    ORDER("整单费用"),
    LINE("行级费用");

    private final String label;

    SalesOrderChargeLevel(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 客户类型（DB {@code sales_order_party_snapshot.customer_type}）。 */
  public enum SalesOrderCustomerType {
    ORGANIZATION("企业"),
    INDIVIDUAL("个人");

    private final String label;

    SalesOrderCustomerType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 状态事件类型（DB {@code sales_order_status_history.event_type}）。 */
  public enum SalesOrderEventType {
    CREATED("创建"),
    SUBMITTED("提交待审");

    private final String label;

    SalesOrderEventType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ------------------------------------------------------------ 入参

  /** 订单行的单个属性快照（报关、履约各自冻结自己需要的那几个属性）。 */
  public record SalesOrderAttributeInput(
      @NotBlank @Size(max = 120) String attributeName,
      @Size(max = 300) String attributeValue,
      @Size(max = 32) String attributeUnit,
      @Size(max = 32) String sourceVersion) {}

  /**
   * 商品行录入。
   *
   * <p>{@code skuCode} / {@code skuName} / {@code specSnapshot} / {@code skuMappingVersion}
   * <b>四项都是快照，由表单一并提交</b>：服务层不读 catalog，也不回查 SKU 当前参数 ——
   * 回查就会让「后来改了参数的商品」改写历史订单行，那正是快照纪律禁止的。
   *
   * <p>{@code discountAmount} <b>必须 ≤ 0</b>（DB CHECK 钉死），{@code taxAmount} <b>必须 ≥ 0</b>。
   */
  public record SalesOrderLineInput(
      @NotNull UUID skuId,
      @NotBlank @Size(max = 64) String skuCode,
      @NotBlank @Size(max = 200) String skuName,
      @Size(max = 300) String specSnapshot,
      @Size(max = 32) String skuMappingVersion,
      @NotNull @Positive @Digits(integer = 13, fraction = 6) BigDecimal quantity,
      @NotNull @PositiveOrZero @Digits(integer = 15, fraction = 4) BigDecimal unitPrice,
      @DecimalMax(value = "0.0", message = "行折扣必须是负数或零：正的折扣会让「减钱」变成「加钱」")
          @Digits(integer = 15, fraction = 4)
          BigDecimal discountAmount,
      @PositiveOrZero @Digits(integer = 15, fraction = 4) BigDecimal taxAmount,
      @Size(max = 500) String note,
      @Valid List<SalesOrderAttributeInput> attributes) {}

  /** 收货 / 开票地址录入（全量快照，非引用）。 */
  public record SalesOrderAddressInput(
      @NotNull SalesOrderAddressRole addressRole,
      @NotBlank @Size(max = 120) String recipientName,
      @Size(max = 64) String recipientPhone,
      @Size(max = 254) String recipientEmail,
      @Size(max = 200) String companyName,
      @NotBlank @Pattern(regexp = "^[A-Z]{2}$", message = "国家码必须是两位大写字母")
          String countryCode,
      @Size(max = 120) String province,
      @Size(max = 120) String city,
      @Size(max = 120) String district,
      @Size(max = 32) String postcode,
      @NotBlank @Size(max = 300) String addressLine1,
      @Size(max = 300) String addressLine2,
      @Size(max = 64) String taxId,
      @Size(max = 500) String note) {}

  /**
   * 头级费用录入。金额正负由 {@code chargeType} 决定：DISCOUNT 必负、其余必正（DB CHECK）。
   */
  public record SalesOrderChargeInput(
      @NotNull SalesOrderChargeType chargeType,
      @Size(max = 120) String label,
      @NotNull @Digits(integer = 15, fraction = 4) BigDecimal amount,
      @NotNull SalesOrderChargeLevel chargeLevel,
      @Size(max = 500) String note) {}

  /**
   * 客户快照录入。
   *
   * <p>刻意<b>不</b>只收一个 customerPartyId 再回查 catalog：回查等于让「客户改名 / 改级别 /
   * 改默认币种」改写历史订单。这里收全量快照字段，与 {@code sales_order_party_snapshot} 的列逐条对应。
   */
  public record SalesOrderCustomerInput(
      @NotNull UUID customerPartyId,
      @NotBlank @Size(max = 64) String customerCode,
      @NotBlank @Size(max = 200) String customerName,
      @NotNull SalesOrderCustomerType customerType,
      @Pattern(regexp = "^([A-Z]{3})?$", message = "默认币种必须是三位大写字母或留空")
          String customerDefaultCurrency) {}

  /**
   * 手工订单创建入参。
   *
   * <p>{@code orderSource} <b>刻意不接受调用方传入</b>（见 {@link SalesOrderSource} 的说明）。
   */
  public record SalesOrderCreateInput(
      @NotBlank @Size(max = 64) String orderNo,
      @Size(max = 128) String externalOrderNo,
      @NotNull SalesOrderManualKind manualKind,
      @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "币种必须是三位大写字母") String currency,
      @NotNull SalesOrderPaymentMethod paymentMethod,
      @Min(0) @Max(365) Integer paymentTermsDays,
      @Size(max = 500) String note,
      @NotNull @Valid SalesOrderCustomerInput customer,
      @NotNull @Valid @Size(min = 1, message = "手工订单至少要有一行商品") List<SalesOrderLineInput> lines,
      @Valid List<SalesOrderAddressInput> addresses,
      @Valid List<SalesOrderChargeInput> charges) {}

  /** 草稿更新入参：整体替换客户 / 行 / 地址 / 费用。单号与外部单号建后不可改（V99 触发器冻结）。 */
  public record SalesOrderUpdateInput(
      @NotNull SalesOrderManualKind manualKind,
      @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
      @NotNull SalesOrderPaymentMethod paymentMethod,
      @Min(0) @Max(365) Integer paymentTermsDays,
      @Size(max = 500) String note,
      @NotNull @Valid SalesOrderCustomerInput customer,
      @NotNull @Valid @Size(min = 1) List<SalesOrderLineInput> lines,
      @Valid List<SalesOrderAddressInput> addresses,
      @Valid List<SalesOrderChargeInput> charges) {}

  /** 列表查询。 */
  public record SalesOrderQuery(
      String manualKind,
      String status,
      UUID customerPartyId,
      String paymentMethod,
      String keyword,
      int page,
      int size) {}

  // ------------------------------------------------------------ 出参

  public record SalesOrderAttributeRow(
      UUID id,
      String attributeName,
      String attributeValue,
      String attributeUnit,
      String sourceVersion) {}

  public record SalesOrderLineRow(
      UUID id,
      int lineNo,
      UUID skuId,
      String skuCode,
      String skuName,
      String specSnapshot,
      String skuMappingVersion,
      BigDecimal quantity,
      BigDecimal unitPrice,
      BigDecimal discountAmount,
      BigDecimal taxAmount,
      BigDecimal lineAmount,
      String note,
      List<SalesOrderAttributeRow> attributes,
      OffsetDateTime createdAt) {}

  public record SalesOrderAddressRow(
      UUID id,
      SalesOrderAddressRole addressRole,
      String recipientName,
      String recipientPhone,
      String recipientEmail,
      String companyName,
      String countryCode,
      String province,
      String city,
      String district,
      String postcode,
      String addressLine1,
      String addressLine2,
      String taxId,
      String note,
      OffsetDateTime createdAt) {}

  public record SalesOrderChargeRow(
      UUID id,
      SalesOrderChargeType chargeType,
      String label,
      BigDecimal amount,
      SalesOrderChargeLevel chargeLevel,
      String note,
      OffsetDateTime createdAt) {}

  public record SalesOrderCustomerRow(
      UUID customerPartyId,
      String customerCode,
      String customerName,
      SalesOrderCustomerType customerType,
      String customerDefaultCurrency) {}

  public record SalesOrderEventRow(
      UUID id,
      SalesOrderEventType eventType,
      String fromStatus,
      String toStatus,
      String reason,
      UUID actorId,
      OffsetDateTime occurredAt) {}

  /** 列表行（列表页不返回明细，省一次大查询）。 */
  public record SalesOrderRow(
      UUID id,
      String orderNo,
      SalesOrderSource orderSource,
      SalesOrderManualKind manualKind,
      String externalOrderNo,
      String currency,
      SalesOrderStatus status,
      SalesOrderPaymentMethod paymentMethod,
      int paymentTermsDays,
      String customerPartyId,
      String customerName,
      int lineCount,
      BigDecimal grandTotalAmount,
      OffsetDateTime submittedAt,
      OffsetDateTime createdAt,
      long version) {}

  /** 列表页：真分页 + 与分页无关的全量汇总。 */
  public record SalesOrderPage(
      List<SalesOrderRow> rows,
      int page,
      int size,
      long total,
      long draftCount,
      long submittedCount,
      BigDecimal grandTotalAmountAll) {}

  /** 创建 / 更新后的返回体。 */
  public record SalesOrderSaved(
      UUID id, String orderNo, SalesOrderStatus status, BigDecimal grandTotalAmount, String message) {}

  /** 详情：一次调用返回整屏（头 + 客户 + 行 + 属性 + 地址 + 费用 + 事件流）。 */
  public record SalesOrderDetail(
      SalesOrderRow order,
      SalesOrderCustomerRow customer,
      List<SalesOrderLineRow> lines,
      List<SalesOrderAddressRow> addresses,
      List<SalesOrderChargeRow> charges,
      List<SalesOrderEventRow> events) {}

  /** 下游缺口的显式声明（照 9.3.2.2 / 6.4.1.1 既有约定）。 */
  public record DownstreamGap(
      String moduleId, String name, String service, boolean available, String blueprint, String reason) {}

  /**
   * 能力声明：把「哪些输入真有依据」「下游有没有接上」显式报出来，
   * 而不是让调用方从 404 或空数组里去猜哪一环没通。
   */
  public record SalesOrderCapabilities(
      List<SalesOrderSource> sources,
      List<SalesOrderManualKind> manualKinds,
      List<SalesOrderStatus> statuses,
      List<SalesOrderPaymentMethod> paymentMethods,
      List<SalesOrderAddressRole> addressRoles,
      List<SalesOrderChargeType> chargeTypes,
      List<String> transitions,
      boolean priceSourceAvailable,
      String priceSourceNote,
      boolean downstreamReviewAvailable,
      String sharedFulfillmentNote,
      List<DownstreamGap> downstreamGaps) {}
}
