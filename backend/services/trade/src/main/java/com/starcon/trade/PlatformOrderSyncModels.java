package com.starcon.trade;

import com.starcon.trade.ManualOrderModels.SalesOrderAddressRole;
import com.starcon.trade.ManualOrderModels.SalesOrderCustomerType;
import com.starcon.trade.ManualOrderModels.SalesOrderPaymentMethod;
import com.starcon.trade.ManualOrderModels.SalesOrderSource;
import com.starcon.trade.ManualOrderModels.SalesOrderStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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
 * 9.1.2.1 平台订单同步的传输模型。
 *
 * <p>蓝图原话：「接收并落库平台订单（webhook 实时 + 轮询补拉），做字段解析、状态归一、 金额与税费还原、地址快照，形成统一订单模型」，输入「平台订单事件/接口返回；平台枚举映射（2.1.2.1）； {@code
 * idempotency_key}（平台订单号 + 来源范围）」。
 *
 * <p><b>四条建模纪律</b>：
 *
 * <ul>
 *   <li><b>统一订单模型复用 9.1.2.2 的那一张</b>：本模块**不新建**「平台订单主表」，
 *       平台订单的头/行/地址/费用/客户快照一律写进 V99 的 {@code sales_order} 簇，
 *       {@code order_source} 取平台来源值。因此本文件**直接复用**
 *       {@link SalesOrderSource} / {@link SalesOrderStatus} /
 *       {@link SalesOrderPaymentMethod} / {@link SalesOrderAddressRole} /
 *       {@link SalesOrderCustomerType} —— <b>不另起一套枚举</b>：下游 9.2 审单、
 *       9.3 履约、13 销量读的是同一张 sales_order，词表必须只有一份。
 *   <li><b>来源由服务端定，不由调用方选</b>：{@code orderSource} 由平台码推导
 *       （AMAZON → AMAZON，未知平台 → PLATFORM），<b>不接受请求体传值</b>。
 *       让客户端能选来源等于开了一条「同步通道写手工单」的后门。
 *   <li><b>金额全是 BigDecimal</b>：平台原价 / 优惠 / 税 / 运费 / 应付总额逐项收，
 *       <b>禁浮点</b>。折后价（{@code discountedUnitPrice}）与优惠额二选一即可，
 *       两者都给时以优惠额为准（平台报的是「减了多少」而不是「减完剩多少」）。
 *   <li><b>平台状态是原始值，归一是服务端的事</b>：调用方只报平台怎么说
 *       （{@code platformStatus}），归一后的内部口径由服务端按 2.1.2.1 的映射副本
 *       查出来。<b>调用方不许直接指定归一状态</b> —— 否则订单状态机就有两个入口。
 *   <li><b>来源范围 = 平台，不是平台 + 店铺</b>：共享订单模型
 *       {@code sales_order} 的外部订单号唯一口径是
 *       {@code (租户, order_source, 外部订单号)}（V99），而 {@code order_source}
 *       的词表里只有平台级取值，<b>没有店铺维度</b>。本模块不 ALTER 既有表，
 *       唯一性范围因此必须取平台这一层，否则 platform_order 会接受一张
 *       sales_order 拒收的订单。店铺是追溯维度：同号订单来自另一家店铺时，
 *       本模块**拒收并记 EXTERNAL_ORDER_NO_COLLISION 异常**，不默默判成「重复」。
 * </ul>
 *
 * <p><b>命名</b>：全仓契约被 {@code tools/codegen/catalog-contracts.py} 合并进<b>一个扁平文件</b>，
 * 同名 key 后者覆盖前者且不报冲突。本模块统一用 {@code PlatformOrderSync*} 前缀，
 * 与 9.1.2.2 的 {@code SalesOrder*}、warehouse 的 {@code OutboundOrder*}、crm 的
 * {@code Trade*} 全部错开。
 */
public final class PlatformOrderSyncModels {

  private PlatformOrderSyncModels() {}

  // ------------------------------------------------------------ 枚举

  /**
   * 平台单的生命周期状态（DB {@code platform_order.normalized_status}）。
   *
   * <p><b>与 {@link SalesOrderStatus} 是两个不同的轴</b>：后者是「有没有进审单队列」
   * （单据状态，V99 的 CHECK 只有 DRAFT / SUBMITTED，不可 ALTER），本枚举是平台订单
   * 自己的生命周期（9.3 履约与 9.4 发货回传要看的那一个）。两者的换算规则写在迁移
   * 第六节：CONFIRMED / IN_FULFILLMENT / COMPLETED → 单据状态 SUBMITTED（进审单队列），
   * 其余 → DRAFT。
   */
  public enum PlatformOrderStatus {
    PENDING_CONFIRMATION("待确认"),
    CONFIRMED("已确认"),
    IN_FULFILLMENT("履约中"),
    COMPLETED("已完成"),
    CANCELLED("已取消"),
    ON_HOLD("平台挂起");

    private final String label;

    PlatformOrderStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 平台订单事件类型（DB {@code platform_order_sync_event.event_type}）。 */
  public enum PlatformOrderEventType {
    ORDER_CREATED("订单创建"),
    ORDER_UPDATED("订单更新"),
    ORDER_CANCELLED("订单取消");

    private final String label;

    PlatformOrderEventType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 同步触发方式（DB {@code platform_order_sync_batch.trigger_type}）。 */
  public enum PlatformOrderTrigger {
    WEBHOOK("webhook 实时接入"),
    POLL("轮询补拉");

    private final String label;

    PlatformOrderTrigger(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 批次结论（DB {@code platform_order_sync_batch.status}）。 */
  public enum PlatformOrderBatchStatus {
    RUNNING("进行中"),
    SUCCEEDED("全部落地"),
    PARTIAL("部分落地"),
    FAILED("一条未落地");

    private final String label;

    PlatformOrderBatchStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 事件受理结论（DB {@code platform_order_sync_event.outcome}）。 */
  public enum PlatformOrderEventOutcome {
    RECEIVED("已受理"),
    APPLIED("已落地"),
    DUPLICATE("重复"),
    REJECTED("已拒收");

    private final String label;

    PlatformOrderEventOutcome(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 映射种类（DB {@code platform_order_enum_mapping.enum_kind}）。 */
  public enum PlatformOrderEnumKind {
    ORDER_STATUS("订单状态"),
    PAYMENT_METHOD("支付方式"),
    COMPONENT_TYPE("组件类型");

    private final String label;

    PlatformOrderEnumKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 映射来源（DB {@code platform_order_enum_mapping.mapping_source}）。 */
  public enum PlatformOrderMappingSource {
    TENANT_MAINTAINED("租户自维护"),
    MODULE_2_ENUM_MAPPING("2.1.2.1 平台枚举映射同步");

    private final String label;

    PlatformOrderMappingSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 组件类型（DB {@code platform_order_component.component_type}）。 */
  public enum PlatformOrderComponentType {
    ITEM("单品"),
    BUNDLE("套装"),
    GIFT("赠品"),
    PACKAGING("包装"),
    OTHER("其他");

    private final String label;

    PlatformOrderComponentType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 组件来源（DB {@code platform_order_component.component_source}）。 */
  public enum PlatformOrderComponentSource {
    LINE("订单行本身"),
    COMPONENT("显式组件");

    private final String label;

    PlatformOrderComponentSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 拒收原因码（DB {@code platform_order_sync_exception.error_code}）。
   *
   * <p>机器可判定：前端按它给处置建议，文案另存 message。
   */
  public enum PlatformOrderErrorCode {
    UNMAPPED_PLATFORM_STATUS("平台状态未映射"),
    AMOUNT_MISMATCH("金额还原对不上"),
    INVALID_PAYLOAD("事件字段非法"),
    ORDER_SNAPSHOT_FROZEN("订单已提交不可改写"),
    ORDER_CONTENT_DIVERGED("平台改了订单内容"),
    EXTERNAL_ORDER_NO_COLLISION("外部订单号在本平台已存在"),
    MAPPING_SYNC_FAILED("映射同步失败"),
    PLATFORM_CALL_FAILED("平台接口调用失败");

    private final String label;

    PlatformOrderErrorCode(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ------------------------------------------------------------ 入参

  /** 订单行属性快照（报关、履约各自冻结自己需要的那几个属性）。 */
  public record PlatformOrderSyncAttributeInput(
      @NotBlank @Size(max = 120) String attributeName,
      @Size(max = 300) String attributeValue,
      @Size(max = 32) String attributeUnit,
      @Size(max = 32) String sourceVersion) {}

  /**
   * 订单组件（套装商品的展开件）。
   *
   * <p>{@code componentType} 是<b>平台原始</b>的类型词；服务端按 2.1.2.1 的映射副本
   * 归一（缺映射时归 OTHER —— 组件类型只影响展示归类，猜错不会污染订单状态机）。
   */
  public record PlatformOrderSyncComponentInput(
      @NotBlank @Size(max = 128) String componentCode,
      @Size(max = 200) String componentName,
      @Size(max = 64) String componentType,
      UUID skuId,
      @Size(max = 64) String skuCode,
      @NotNull @Positive @Digits(integer = 13, fraction = 6) BigDecimal quantity,
      @PositiveOrZero @Digits(integer = 15, fraction = 4) BigDecimal unitAmount) {}

  /**
   * 订单行。
   *
   * <p><b>金额还原的三件套</b>：{@code unitPrice} 是平台<b>原价</b>单价，
   * {@code discountedUnitPrice} 是平台<b>折后</b>单价，{@code discountAmount} 是行级优惠额
   * （正数，服务层落库时取负 —— V99 的行折扣恒为负）。三者给其一即可：
   *
   * <ul>
   *   <li>给了 {@code discountedUnitPrice}：行优惠 = 原价小计 − 折后小计（服务端算）；
   *   <li>给了 {@code discountAmount}：直接用它（平台报的是「减了多少」）；
   *   <li>两个都给了：<b>以 discountAmount 为准</b>，并在异常明细里留一条
   *       AMOUNT_MISMATCH 级别的提示？—— 不，两个口径差一分钱以内属正常舍入，
   *       超过一分钱才拒收（见服务层）。</li>
   * </ul>
   *
   * <p><b>{@code lineNo} 只是提示，本模块按输入顺序重排为 1..N</b>：平台自己的行号
   * 跨平台后没有意义，而 {@code sales_order_line} 的行号在单内必须唯一
   * （V99 的 {@code UNIQUE(tenant_id,order_id,line_no)}）—— 直接采信调用方给的行号
   * 会让「第 3 行」指向两行，或者指向一个不存在的行。
   */
  public record PlatformOrderSyncLineInput(
      @Min(1) Integer lineNo,
      @NotNull UUID skuId,
      @NotBlank @Size(max = 64) String skuCode,
      @NotBlank @Size(max = 200) String skuName,
      @Size(max = 300) String specSnapshot,
      @Size(max = 32) String skuMappingVersion,
      @NotNull @Positive @Digits(integer = 13, fraction = 6) BigDecimal quantity,
      @NotNull @PositiveOrZero @Digits(integer = 15, fraction = 4) BigDecimal unitPrice,
      @PositiveOrZero @Digits(integer = 15, fraction = 4) BigDecimal discountedUnitPrice,
      @PositiveOrZero @Digits(integer = 15, fraction = 4) BigDecimal discountAmount,
      @PositiveOrZero @Digits(integer = 15, fraction = 4) BigDecimal taxAmount,
      @Size(max = 500) String note,
      @Valid List<PlatformOrderSyncAttributeInput> attributes,
      @Valid List<PlatformOrderSyncComponentInput> components) {}

  /** 收货 / 开票地址（全量快照，非引用）。 */
  public record PlatformOrderSyncAddressInput(
      @NotBlank @Size(max = 120) String recipientName,
      @Size(max = 64) String recipientPhone,
      @Size(max = 254) String recipientEmail,
      @Size(max = 200) String companyName,
      @NotBlank @Pattern(regexp = "^[A-Z]{2}$", message = "国家码必须是两位大写字母") String countryCode,
      @Size(max = 120) String province,
      @Size(max = 120) String city,
      @Size(max = 120) String district,
      @Size(max = 32) String postcode,
      @NotBlank @Size(max = 300) String addressLine1,
      @Size(max = 300) String addressLine2,
      @Size(max = 64) String taxId,
      @Size(max = 500) String note) {}

  /**
   * 一条平台订单事件（webhook 与轮询补拉<b>共用</b>这一个结构 —— 两者走同一条落库路径）。
   *
   * <p>{@code eventType} 缺省时由入口决定：webhook 必填（平台自己说是创建还是更新），
   * 轮询缺省按 {@code ORDER_UPDATED} 处理（轮询拿回来的是订单**当前**状态）。
   */
  public record PlatformOrderSyncEventInput(
      PlatformOrderEventType eventType,
      @NotBlank @Size(max = 128) String externalOrderNo,
      @NotBlank @Size(max = 128) String platformStatus,
      @PositiveOrZero Long platformEventVersion,
      OffsetDateTime platformPlacedAt,
      OffsetDateTime platformUpdatedAt,
      @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "币种必须是三位大写字母") String currency,
      SalesOrderPaymentMethod paymentMethod,
      @Min(0) @Max(365) Integer paymentTermsDays,
      @NotBlank @Size(max = 64) String buyerId,
      @Size(max = 200) String buyerName,
      SalesOrderCustomerType buyerType,
      @PositiveOrZero @Digits(integer = 15, fraction = 4) BigDecimal discountAmount,
      @PositiveOrZero @Digits(integer = 15, fraction = 4) BigDecimal taxAmount,
      @PositiveOrZero @Digits(integer = 15, fraction = 4) BigDecimal shippingAmount,
      @PositiveOrZero @Digits(integer = 15, fraction = 4) BigDecimal otherAmount,
      @PositiveOrZero @Digits(integer = 15, fraction = 4) BigDecimal platformGrandTotalAmount,
      @NotNull @Valid PlatformOrderSyncAddressInput shippingAddress,
      @Valid PlatformOrderSyncAddressInput billingAddress,
      @NotNull @Valid @Size(min = 1, message = "平台订单至少要有一行商品") List<PlatformOrderSyncLineInput> lines) {}

  /** webhook 实时接入的入参。 */
  public record PlatformOrderSyncWebhookInput(
      @NotBlank @Size(max = 64) String platformCode,
      @NotBlank @Size(max = 64) String shopCode,
      @NotNull @Valid @Size(min = 1, message = "webhook 批次至少要有一个事件") List<PlatformOrderSyncEventInput> events) {}

  /** 轮询补拉的入参：按来源 + 时间窗补拉，orders 是平台接口返回的订单。 */
  public record PlatformOrderSyncPollInput(
      @NotBlank @Size(max = 64) String platformCode,
      @NotBlank @Size(max = 64) String shopCode,
      @NotNull OffsetDateTime windowStart,
      @NotNull OffsetDateTime windowEnd,
      @NotNull @Valid @Size(min = 1, message = "轮询批次至少要有一张订单") List<PlatformOrderSyncEventInput> orders) {}

  /** 登记本模块自己的平台枚举映射副本（租户自维护）。 */
  public record PlatformOrderSyncMappingInput(
      @NotBlank @Size(max = 64) String platformCode,
      @NotNull PlatformOrderEnumKind enumKind,
      @NotBlank @Size(max = 128) String externalValue,
      @NotBlank @Size(max = 64) String internalValue) {}

  /** 从 2.1.2.1（channel 服务）拉取映射副本的入参。 */
  public record PlatformOrderSyncMappingSyncInput(
      @NotBlank @Size(max = 64) String platformCode,
      @NotNull PlatformOrderEnumKind enumKind) {}

  // ------------------------------------------------------------ 查询入参

  /**
   * 同步批次列表的查询条件。
   *
   * <p>{@code page} / {@code size} 是**真分页**（LIMIT/OFFSET + 独立 count(*)），
   * 汇总计数与分页无关（跨全量统计），与 9.1.1.1 订单列表同一纪律。
   */
  public record PlatformOrderSyncBatchQuery(
      String platformCode,
      String shopCode,
      String status,
      String triggerType,
      int page,
      int size) {}

  /** 平台单列表的查询条件。 */
  public record PlatformOrderSyncOrderQuery(
      String platformCode,
      String shopCode,
      String normalizedStatus,
      String keyword,
      int page,
      int size) {}

  /** 异常明细列表的查询条件。 */
  public record PlatformOrderSyncExceptionQuery(
      String platformCode,
      String errorCode,
      String externalOrderNo,
      int page,
      int size) {}

  /** 映射副本列表的查询条件。 */
  public record PlatformOrderSyncMappingQuery(
      String platformCode,
      String enumKind,
      String mappingSource,
      int page,
      int size) {}

  // ------------------------------------------------------------ 出参

  /** 单个事件的受理结果（webhook / 轮询的逐条结果）。 */
  public record PlatformOrderSyncEventResult(
      String externalOrderNo,
      String eventType,
      String outcome,
      String normalizedStatus,
      UUID orderId,
      UUID salesOrderId,
      String orderNo,
      String errorCode,
      String errorMessage) {}

  /** 一次落库（webhook 一批 / 轮询一个时间窗一批）的结果。 */
  public record PlatformOrderSyncResult(
      UUID batchId,
      String platformCode,
      String shopCode,
      String triggerType,
      String status,
      int receivedCount,
      int createdCount,
      int updatedCount,
      int duplicateCount,
      int failedCount,
      String mappingVersion,
      OffsetDateTime startedAt,
      OffsetDateTime finishedAt,
      List<PlatformOrderSyncEventResult> results) {}

  /** 同步批次行（列表页用）。 */
  public record PlatformOrderSyncBatchRow(
      UUID id,
      String platformCode,
      String shopCode,
      String triggerType,
      String status,
      int receivedCount,
      int createdCount,
      int updatedCount,
      int duplicateCount,
      int failedCount,
      String mappingVersion,
      OffsetDateTime windowStartAt,
      OffsetDateTime windowEndAt,
      OffsetDateTime startedAt,
      OffsetDateTime finishedAt,
      OffsetDateTime createdAt) {}

  /** 批次列表：真分页 + 与分页无关的全量汇总。 */
  public record PlatformOrderSyncBatchPage(
      List<PlatformOrderSyncBatchRow> rows,
      int page,
      int size,
      long total,
      long webhookCount,
      long pollCount,
      long failedCount,
      long duplicateCount) {}

  /** inbox 事件行（批次详情 / 订单详情里的逐条受理记录）。 */
  public record PlatformOrderSyncEventRow(
      UUID id,
      String externalOrderNo,
      String eventType,
      String outcome,
      String platformStatus,
      String normalizedStatus,
      String errorCode,
      String errorMessage,
      long platformEventVersion,
      OffsetDateTime platformUpdatedAt,
      OffsetDateTime receivedAt,
      OffsetDateTime processedAt) {}

  /** 异常明细行。 */
  public record PlatformOrderSyncExceptionRow(
      UUID id,
      UUID batchId,
      String platformCode,
      String shopCode,
      String externalOrderNo,
      String errorCode,
      String errorMessage,
      OffsetDateTime createdAt) {}

  /** 异常明细列表：真分页 + 按错误码的汇总。 */
  public record PlatformOrderSyncExceptionPage(
      List<PlatformOrderSyncExceptionRow> rows,
      int page,
      int size,
      long total,
      long unmappedStatusCount,
      long amountMismatchCount,
      long frozenCount) {}

  /** 平台单列表行。 */
  public record PlatformOrderSyncOrderRow(
      UUID id,
      UUID salesOrderId,
      String orderNo,
      String platformCode,
      String shopCode,
      String externalOrderNo,
      String orderSource,
      String documentStatus,
      String platformStatus,
      String normalizedStatus,
      String currency,
      BigDecimal platformOriginalAmount,
      BigDecimal platformDiscountAmount,
      BigDecimal platformTaxAmount,
      BigDecimal platformShippingAmount,
      BigDecimal platformGrandTotalAmount,
      BigDecimal restoredGrandTotalAmount,
      String mappingVersion,
      OffsetDateTime platformPlacedAt,
      OffsetDateTime platformUpdatedAt,
      OffsetDateTime syncedAt) {}

  /** 平台单列表：真分页 + 与分页无关的全量汇总。 */
  public record PlatformOrderSyncOrderPage(
      List<PlatformOrderSyncOrderRow> rows,
      int page,
      int size,
      long total,
      long pendingCount,
      long confirmedCount,
      long inFulfillmentCount,
      long completedCount,
      long cancelledCount,
      long duplicateEventCount) {}

  /** 组件展开行。 */
  public record PlatformOrderSyncComponentRow(
      UUID id,
      int lineNo,
      String componentSource,
      int componentSeq,
      String componentCode,
      String componentName,
      String componentType,
      String externalType,
      String skuCode,
      BigDecimal quantity,
      BigDecimal unitAmount,
      String mappingVersion) {}

  /** 平台单详情：投影 + 组件展开 + 受理记录。 */
  public record PlatformOrderSyncOrderDetail(
      PlatformOrderSyncOrderRow order,
      List<PlatformOrderSyncComponentRow> components,
      List<PlatformOrderSyncEventRow> events) {}

  /** 批次详情：批次 + 逐条受理结果 + 异常明细。 */
  public record PlatformOrderSyncBatchDetail(
      PlatformOrderSyncBatchRow batch,
      List<PlatformOrderSyncEventRow> events,
      List<PlatformOrderSyncExceptionRow> exceptions) {}

  /** 映射副本行。 */
  public record PlatformOrderSyncMappingRow(
      UUID id,
      String platformCode,
      String enumKind,
      String externalValue,
      String internalValue,
      String mappingSource,
      String sourceReference,
      String mappingVersion,
      OffsetDateTime capturedAt,
      OffsetDateTime supersededAt) {}

  /** 映射副本列表（附当前生效版本数）。 */
  public record PlatformOrderSyncMappingPage(
      List<PlatformOrderSyncMappingRow> rows,
      int page,
      int size,
      long total,
      long activeCount,
      List<String> mappingVersions) {}

  /**
   * 从 2.1.2.1（channel 服务）同步映射副本的结果。
   *
   * <p>{@code importedCount} 是本次写入的新行数，{@code supersededCount} 是被本次
   * 同步停用（{@code superseded_at} 落戳）的旧行数 —— 停用是留痕，删除不是。
   */
  public record PlatformOrderSyncMappingSyncResult(
      String platformCode,
      String enumKind,
      String mappingVersion,
      int importedCount,
      int supersededCount,
      long activeCount,
      String source,
      String message) {}

  /**
   * 下游缺口的显式声明。
   *
   * <p><b>刻意带 {@code PlatformOrderSync} 前缀</b>，不叫 {@code DownstreamGap}：全仓契约被
   * codegen 合并进<b>一个扁平文件</b>，同名 key 后者覆盖前者且不报冲突（9.1.2.2 已踩过一次）。
   */
  public record PlatformOrderSyncDownstreamGap(
      String moduleId,
      String name,
      String service,
      boolean available,
      String blueprint,
      String reason) {}

  /** 能力声明：把「哪些输入真有依据」「下游有没有接上」显式报出来。 */
  public record PlatformOrderSyncCapabilities(
      List<SalesOrderSource> sources,
      List<PlatformOrderStatus> statuses,
      List<PlatformOrderEventType> eventTypes,
      List<PlatformOrderTrigger> triggers,
      List<PlatformOrderEnumKind> enumKinds,
      List<PlatformOrderMappingSource> mappingSources,
      List<PlatformOrderErrorCode> errorCodes,
      List<String> transitions,
      boolean enumMappingSyncAvailable,
      String enumMappingNote,
      boolean sharedOrderModel,
      String sharedOrderModelNote,
      String statusNormalizationNote,
      String amountRestorationNote,
      List<PlatformOrderSyncDownstreamGap> downstreamGaps) {}
}
