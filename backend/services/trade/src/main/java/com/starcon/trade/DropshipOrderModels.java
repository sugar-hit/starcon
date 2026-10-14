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
 * 9.6.1.2 代发单与供应商下单的传输模型。
 *
 * <p>蓝图原话：「买家下单后，自动/手工向 1688 等供应商代发平台下单，传递地址与商品信息，
 * 回填供应商单号与代发成本，计算我方差价毛利。输出：代发单（供应商、单号、发货状态）；
 * 差价成本；差异告警（供应商无货/涨价/失败）。」
 *
 * <p><b>命名纪律（硬要求）</b>：springdoc 用 record 的<b>简单类名</b>做
 * {@code components.schemas} 的键，而本仓已有 {@code WarehouseRow}、
 * {@code AllocationRequest} 等同名 record，同名会互相覆盖。因此本文件所有 record
 * 一律以 {@code DropshipOrder} 开头（与 9.6.1.1 的 {@code Dropship*} 前缀区分，
 * 免得两个模块的类型在契约文件里撞名）。
 *
 * <p><b>四条建模纪律</b>：
 *
 * <ul>
 *   <li><b>差价可以为负，且必须显式报出。</b>{@code spreadAmount} 是后端生成列，
 *       负值原样透传——亏损单是最需要被看见的那一张。</li>
 *   <li><b>「还没回执」与「回执是 0」不是一回事。</b>
 *       {@code actualPurchaseAmount} 为 {@code null} 表示供应商还没回成本，
 *       {@code 0} 表示供应商回的是 0（真实但极端）。</li>
 *   <li><b>四类告警分开。</b>涨价 / 无货 / 失败 / 差价转负是四个不同的事实，
 *       合并成一条「有异常」就没法处置。</li>
 *   <li><b>付款确认带主体与时刻。</b>{@code paymentConfirmedByMembershipId} 不是可选项：
 *       9.1 没有支付状态列，这张单「谁确认已收款」必须可追责。</li>
 * </ul>
 */
public final class DropshipOrderModels {

  private DropshipOrderModels() {}

  // ------------------------------------------------------------ 枚举

  /** 代发单状态机（与 V122 的 CHECK 同一词表）。 */
  public enum DropshipOrderStatus {
    DRAFT("草稿（未确认收款）"),
    READY("已确认收款，可下单"),
    DISPATCHED("已提交供应商"),
    ACCEPTED("供应商已受理"),
    SHIPPED("供应商已发货"),
    FAILED("下单失败"),
    CANCELLED("已取消");

    private final String label;

    DropshipOrderStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 下单方式：AUTO 走 vendor 网关，MANUAL 由运营录入回执。 */
  public enum DropshipDispatchMode {
    AUTO("自动（走供应商接口）"),
    MANUAL("人工录入回执");

    private final String label;

    DropshipDispatchMode(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 差异告警种类（蓝图点名必须显式告警的那一条）。
   *
   * <p>四类**互不合并**：处置动作完全不同 ——
   * 涨价要改售价或换供应商、无货要改供应商、失败要重下或人工处理、
   * 差价转负要立刻止损。
   */
  public enum DropshipAlertKind {
    PRICE_INCREASE("供应商涨价", "WARNING"),
    NO_STOCK("供应商无货", "CRITICAL"),
    ORDER_FAILED("下单失败", "CRITICAL"),
    NEGATIVE_SPREAD("差价转负（亏损）", "CRITICAL");

    private final String label;
    private final String severity;

    DropshipAlertKind(String label, String severity) {
      this.label = label;
      this.severity = severity;
    }

    public String label() {
      return label;
    }

    public String severity() {
      return severity;
    }
  }

  /** 告警处置状态。 */
  public enum DropshipAlertStatus {
    OPEN("未处置"),
    ACKNOWLEDGED("已确认"),
    RESOLVED("已处置");

    private final String label;

    DropshipAlertStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ------------------------------------------------------------ 传输模型

  /** 代发单输入：从一张已提交的销售订单 + 一份 9.6.1.1 配置建单。 */
  public record DropshipOrderInput(
      @NotNull @Schema(description = "9.1 销售订单 ID（同库引用）") UUID salesOrderId,
      @NotNull @Schema(description = "9.6.1.1 代发配置 ID") UUID configId,
      @NotBlank
          @Schema(description = "下单方式：AUTO 走 vendor 接口 / MANUAL 人工录入回执")
          String dispatchMode,
      @NotBlank @Schema(description = "供应商代发 vendor：MANUAL_ENTRY / ALIBABA_1688")
          String vendorCode,
      @Size(max = 400) String note) {}

  /** 收款人工确认（9.1 无支付状态列，见 V122 文件头第二节）。 */
  public record DropshipPaymentConfirmInput(
      @NotNull @Schema(description = "确认时刻") OffsetDateTime confirmedAt,
      @NotNull @Schema(description = "确认人成员 ID") UUID confirmedByMembershipId,
      @NotBlank @Schema(description = "确认依据（这笔款是怎么确认到账的）") String note) {}

  /** 供应商回执：单号与**实际**代发成本（蓝图「回填供应商单号与代发成本」）。 */
  public record DropshipSupplierAckInput(
      @NotBlank @Schema(description = "供应商单号") String supplierOrderNo,
      @NotNull @Schema(description = "供应商实际采购价（回执价，用于涨价判定）")
          BigDecimal actualPurchaseAmount,
      @NotNull @Schema(description = "供应商实际运费") BigDecimal actualSupplierShippingAmount,
      @Size(max = 128) String trackingNo,
      @Size(max = 400) String note) {}

  /** 供应商发货回执（运单号交给下游 9.6.1.3 / 10 物流）。 */
  public record DropshipShipmentInput(
      @NotBlank String supplierTrackingNo,
      @NotNull OffsetDateTime shippedAt) {}

  /** 下单失败原因（下单失败态必填）。 */
  public record DropshipFailureInput(@NotBlank @Size(max = 400) String reason) {}

  /** 告警处置。 */
  public record DropshipAlertAckInput(
      @NotBlank @Schema(description = "处置说明：怎么处理的（改价/换供应商/退款…）")
          String resolutionNote) {}

  /** 供应商代发接口登记（只存 credential_ref，不存任何明文凭证）。 */
  public record DropshipEndpointInput(
      @NotBlank String vendorCode,
      @NotBlank String vendorName,
      @Size(max = 300) String baseUrl,
      @Size(max = 200) String credentialRef,
      @Schema(description = "是否已接线；true 时 baseUrl 必填（DB CHECK 钉死）")
          Boolean wired,
      @Size(max = 400) String note) {}

  // ------------------------------------------------------------ 读模型

  /** 代发单行（传递商品信息给供应商的那一份）。 */
  public record DropshipOrderLineRow(
      UUID id,
      int lineNo,
      UUID skuId,
      String skuRef,
      String skuName,
      String specSnapshot,
      BigDecimal quantity,
      BigDecimal sellUnitPrice,
      BigDecimal purchaseUnitPrice,
      BigDecimal sellAmount,
      BigDecimal purchaseAmount,
      String supplierSkuRef,
      String note) {}

  /** 代发单（蓝图输出第一条：供应商、单号、发货状态 + 差价成本）。 */
  public record DropshipOrderRow(
      UUID id,
      String orderNo,
      UUID salesOrderId,
      String salesOrderNo,
      String customerName,
      String currencyCode,
      UUID configId,
      String configCode,
      String supplierCode,
      String fulfillmentPath,
      String ownershipMode,
      Boolean countsAgainstOwnInventory,
      String status,
      String dispatchMode,
      String vendorCode,
      String supplierOrderNo,
      String supplierTrackingNo,
      @Schema(nullable = true) OffsetDateTime supplierShippedAt,
      String rejectionReason,
      BigDecimal sellAmount,
      BigDecimal purchaseAmount,
      BigDecimal supplierShippingAmount,
      @Schema(nullable = true) BigDecimal actualPurchaseAmount,
      @Schema(nullable = true) BigDecimal actualSupplierShippingAmount,
      @Schema(description = "代发成本 = 采购价 + 供应商运费（生成列）") BigDecimal dropshipCost,
      @Schema(description = "差价 = 售价 −（采购价 + 运费），**可以为负**（生成列）")
          BigDecimal spreadAmount,
      @Schema(description = "差价率（生成列）；售价为 0 时为 null") BigDecimal marginRate,
      @Schema(nullable = true) OffsetDateTime paymentConfirmedAt,
      @Schema(nullable = true) UUID paymentConfirmedByMembershipId,
      String paymentConfirmedSource,
      String paymentConfirmedNote,
      String note,
      @Schema(nullable = true) OffsetDateTime dispatchedAt,
      @Schema(nullable = true) OffsetDateTime acceptedAt,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 差异告警。 */
  public record DropshipAlertRow(
      UUID id,
      UUID orderId,
      String orderNo,
      String alertKind,
      String alertKindLabel,
      String severity,
      @Schema(nullable = true) BigDecimal expectedAmount,
      @Schema(nullable = true) BigDecimal actualAmount,
      String detail,
      String status,
      @Schema(nullable = true) OffsetDateTime acknowledgedAt,
      @Schema(nullable = true) UUID acknowledgedByMembershipId,
      String resolutionNote,
      OffsetDateTime occurredAt) {}

  /** 事件流条目。 */
  public record DropshipOrderEventRow(
      UUID id,
      UUID orderId,
      String eventKind,
      String fromStatus,
      String toStatus,
      String detail,
      @Schema(nullable = true) UUID actorMembershipId,
      OffsetDateTime occurredAt) {}

  /** 供应商代发接口登记。 */
  public record DropshipEndpointRow(
      UUID id,
      String vendorCode,
      String vendorName,
      String baseUrl,
      String credentialRef,
      String status,
      Boolean wired,
      String note,
      OffsetDateTime updatedAt) {}

  // ------------------------------------------------------------ 分页与保存结果

  public record DropshipOrderPage(List<DropshipOrderRow> items, long total, int page, int size) {}

  public record DropshipAlertPage(List<DropshipAlertRow> items, long total, int page, int size) {}

  public record DropshipEventPage(
      List<DropshipOrderEventRow> items, long total, int page, int size) {}

  public record DropshipEndpointPage(
      List<DropshipEndpointRow> items, long total, int page, int size) {}

  public record DropshipOrderSaved(UUID id, String orderNo, boolean created) {}

  public record DropshipEndpointSaved(UUID id, String vendorCode, boolean created) {}

  /**
   * 能力声明：把「哪些输入真有依据」显式报出来。
   *
   * <p>本模块最要紧的一处是 <b>PAYMENT 维度 available=false</b>：
   * 9.1 的 sales_order 没有支付状态列，12 财务的实收流水未交付，
   * 因此「已付款」在本模块是**有主体的人工确认**，不是系统读到的支付事实。
   * 把它报成 true（或画成 0 / 「—」）会让人以为收款状态已经通了。
   */
  public record DropshipOrderCapabilities(
      List<DropshipOrderCapabilityVendor> vendors,
      List<String> statuses,
      List<String> alertKinds,
      List<DropshipOrderCapabilityDimension> dimensions,
      boolean paymentStatusReadable,
      String summary,
      String marginNote) {}

  /** vendor 接线情况。 */
  public record DropshipOrderCapabilityVendor(
      String vendorCode, String displayName, boolean wired, String wiringRequirement) {}

  /** 上游维度可用性。 */
  public record DropshipOrderCapabilityDimension(
      String code,
      String name,
      boolean available,
      String source,
      @Schema(nullable = true) String ownerModule,
      @Schema(nullable = true) String note) {}

  // ------------------------------------------------------------ 查询参数

  public record DropshipOrderQuery(
      String orderNo,
      String salesOrderNo,
      String supplierCode,
      String vendorCode,
      String status,
      Boolean negativeSpreadOnly,
      String keyword,
      int page,
      int size) {}

  public record DropshipAlertQuery(
      UUID orderId,
      String alertKind,
      String status,
      String severity,
      String keyword,
      int page,
      int size) {}

  public record DropshipEventQuery(UUID orderId, int page, int size) {}

  public record DropshipEndpointQuery(String vendorCode, int page, int size) {}
}