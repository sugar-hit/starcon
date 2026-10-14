package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 9.1.1.3 订单变更与取消的传输形状。
 *
 * <p><b>三个实体的划分与蓝图输出句一一对应</b>：
 *
 * <ul>
 *   <li><b>变更请求</b>（{@link OrderChangeCancelChangeRequest}）：地址修改与数量调整。
 *       蓝图说「数量调整（<b>需平台允许</b>）」，所以它带平台规则命中结果。</li>
 *   <li><b>取消请求</b>（{@link OrderChangeCancelCancellation}）：取消未发货数量，
 *       状态机五态，终态数量只在责任方确认后落。</li>
 *   <li><b>两项下游任务</b>：仓库停止作业与释放占用（7.2.1.3）、平台回写（2.5.1.1）。</li>
 * </ul>
 *
 * <p><b>数量一律 {@code numeric(19,6)} + {@link BigDecimal}，金额一律 {@code numeric(19,4)}</b>，
 * 禁浮点（AGENTS.md）。时间一律 {@link OffsetDateTime} 对应 {@code timestamptz}，
 * 服务层用 {@code ?::timestamptz} 绑定。
 */
public final class OrderChangeCancelModels {

  private OrderChangeCancelModels() {}

  // ================================================================ 枚举

  /**
   * 变更类型。
   *
   * <p>数量调整刻意<b>不</b>合成一个带符号的 {@code QUANTITY_ADJUST} + 正负数量：
   * 增与减在平台侧是两种能力（多数平台允许加量，几乎都不允许改已下单行的量），
   * 合并后规则表就没法按方向配，而配不出方向就等于退回「代码里的 if」。
   *
   * <p>{@code CANCEL} 是取消用的第四个值：取消在平台上同样是「能不能改」的一种，
   * 与数量减少不是同一回事（平台往往允许取消整单却不允许改行数量），
   * 所以它必须是规则表里独立的一行。
   */
  public enum OrderChangeKind {
    ADDRESS_MODIFY,
    QUANTITY_INCREASE,
    QUANTITY_DECREASE,
    CANCEL
  }

  /**
   * 变更 / 取消的共用状态机（服务层 {@code OrderChangeCancelService.TRANSITIONS}
   * 与测试逐状态比对，不另写一份矩阵）。
   *
   * <p><b>{@code PENDING_VERIFICATION} 不是终态</b>：等待平台或仓库核验后仍可转
   * {@code CONFIRMED}。蓝图要求的状态（「取消申请 / 处理中 / 已确认 / 拒绝 / 待核验」）
   * 五个值一一对应，缺一个就答不出「等待核验的申请现在算什么」。
   */
  public enum OrderChangeStatus {
    REQUESTED,
    PROCESSING,
    CONFIRMED,
    REJECTED,
    PENDING_VERIFICATION
  }

  /** 取消范围：整单取消与按行取消。 */
  public enum OrderCancelScope {
    FULL_ORDER,
    PARTIAL_LINES
  }

  /** 仓库任务阶段。蓝图要求「先停止作业<b>并</b>释放占用」，所以是两个阶段而不是一个。 */
  public enum OrderHoldTaskStage {
    STOP_WORK,
    RELEASE_OCCUPANCY
  }

  /** 仓库任务状态。两个阶段都到 COMPLETED 才允许取消单确认（V119 守卫 9.2）。 */
  public enum OrderHoldTaskStatus {
    PENDING,
    ACKNOWLEDGED,
    COMPLETED,
    FAILED,
    REJECTED
  }

  /** 平台回写任务状态。 */
  public enum OrderWritebackStatus {
    PENDING,
    SUBMITTED,
    CONFIRMED,
    REJECTED,
    PENDING_VERIFICATION
  }

  /** 履约投影来源：如实标注这批数是谁写进来的（9.3.3.1 未交付，故含人工维护来源）。 */
  public enum OrderProgressSource {
    MODULE_9_3_3_1,
    EVENT_8_OUTBOUND,
    TENANT_MAINTAINED
  }

  /** 地址可改字段。枚举化而不是自由文本：自由文本会让拼错的字段名静默落库。 */
  public enum OrderAddressFieldCode {
    RECIPIENT_NAME,
    RECIPIENT_PHONE,
    RECIPIENT_EMAIL,
    COMPANY_NAME,
    PROVINCE,
    CITY,
    DISTRICT,
    POSTCODE,
    TAX_ID,
    NOTE
  }

  /** 地址角色。与 V99 的 order_address_snapshot.address_role 同一套取值。 */
  public enum OrderAddressRole {
    SHIPPING,
    BILLING
  }

  // ================================================================ 输入

  /** 建变更请求。 */
  public record OrderChangeCancelChangeInput(
      @NotNull UUID orderId,
      @NotNull OrderChangeKind changeKind,
      @Size(max = 500) String reason,
      /*
       * 平台代码与店铺代码是**显式入参**，不是从订单上读出来的。
       *
       * sales_order 簇（V99）没有平台列：order_source 只有 MANUAL / PLATFORM，
       * 而 9.1.2.1 平台订单同步未开发，全服务没有任何一处写过平台代码
       * （9.1.1.1 的 platformStatus 就因此恒为 null）。
       * 所以「这张单属于哪个平台」目前只能由调用方给出；
       * 手工单（order_source=MANUAL）传空即可，服务层会跳过平台规则与平台回写。
       * 9.1.2.1 落地后这两项由订单自己带，届时入参可弃用 —— 表结构不需要改。
       */
      @Size(max = 32) String platformCode,
      @Size(max = 64) String shopCode,
      @Valid List<OrderChangeCancelChangeLineInput> lines,
      @Valid List<OrderChangeCancelAddressFieldInput> addressFields) {}

  /** 变更的数量调整明细。 */
  public record OrderChangeCancelChangeLineInput(
      @NotNull UUID orderLineId,
      @NotNull @DecimalMin("0") @Digits(integer = 13, fraction = 6) BigDecimal requestedQuantity) {}

  /** 变更的地址字段前后值。 */
  public record OrderChangeCancelAddressFieldInput(
      @NotNull OrderAddressRole addressRole,
      @NotNull OrderAddressFieldCode fieldCode,
      @Size(max = 300) String beforeValue,
      @Size(max = 300) String afterValue) {}

  /** 建取消请求。 */
  public record OrderChangeCancelCancellationInput(
      @NotNull UUID orderId,
      @NotNull OrderCancelScope cancelScope,
      @NotBlank @Size(max = 500) String reason,
      @Size(max = 32) String platformCode,
      @Size(max = 64) String shopCode,
      /*
       * 仓库清单：取消要向哪些仓下达停止作业与释放占用。
       * 没有它就不知道该找哪个仓释放 —— 空清单会让「必须先释放」这条规则
       * 在没有仓的订单上退化成「什么都不用释放就能确认」。
       */
      @NotEmpty @Size(max = 50) List<String> warehouseRefs,
      @NotEmpty @Valid List<OrderChangeCancelCancellationLineInput> lines) {}

  /** 取消的逐行数量。 */
  public record OrderChangeCancelCancellationLineInput(
      @NotNull UUID orderLineId,
      @NotNull @DecimalMin("0") @Digits(integer = 13, fraction = 6) BigDecimal cancelQuantity,
      /*
       * 预占引用：跨服务只存对方的业务键，不猜对方主键 uuid
       * （与 V101 给 warehouse 的 fulfillment-reference 同一纪律）。
       */
      @Size(max = 64) String reservationRef) {}

  /** 变更 / 取消的状态推进。 */
  public record OrderChangeCancelStatusInput(
      @NotNull OrderChangeStatus toStatus, @Size(max = 500) String reason) {}

  /** 仓库任务回执。 */
  public record OrderChangeCancelHoldTaskInput(
      @NotNull OrderHoldTaskStatus status,
      @DecimalMin("0") @Digits(integer = 13, fraction = 6) BigDecimal completedQuantity,
      @Size(max = 500) String resultNote,
      @Size(max = 500) String failureReason) {}

  /** 平台回写任务结果。 */
  public record OrderChangeCancelWritebackInput(
      @NotNull OrderWritebackStatus status, @Size(max = 500) String lastError) {}

  /** 平台可变更规则的维护。 */
  public record OrderChangeCancelRuleInput(
      @NotBlank @Size(max = 32) String platformCode,
      @Size(max = 64) String shopCode,
      @NotNull OrderChangeKind changeKind,
      Boolean allowed,
      @DecimalMin("0") @Digits(integer = 7, fraction = 2) BigDecimal deadlineHours,
      @DecimalMin("0") @Digits(integer = 13, fraction = 6) BigDecimal maxDecreaseQty,
      Boolean requiresPlatformAck,
      OffsetDateTime effectiveFrom,
      OffsetDateTime effectiveTo,
      @Size(max = 400) String notes,
      Long version) {}

  /** 履约投影录入。 */
  public record OrderChangeCancelProgressInput(
      @NotNull UUID orderId,
      @NotNull UUID orderLineId,
      @NotNull @DecimalMin("0") @Digits(integer = 13, fraction = 6) BigDecimal reservedQuantity,
      @NotNull @DecimalMin("0") @Digits(integer = 13, fraction = 6) BigDecimal pickingQuantity,
      @NotNull @DecimalMin("0") @Digits(integer = 13, fraction = 6) BigDecimal shippedQuantity,
      OffsetDateTime shippedAt,
      @NotNull OrderProgressSource progressSource,
      @NotNull OffsetDateTime observedAt) {}

  // ================================================================ 输出

  /** 平台可变更规则的一行。 */
  public record OrderChangeCancelRuleRow(
      UUID id,
      String platformCode,
      String shopCode,
      OrderChangeKind changeKind,
      boolean allowed,
      BigDecimal deadlineHours,
      BigDecimal maxDecreaseQty,
      boolean requiresPlatformAck,
      OffsetDateTime effectiveFrom,
      OffsetDateTime effectiveTo,
      String notes,
      long version,
      boolean active) {}

  /** 规则集合查询。{@code active} 为 false 的规则在生效窗口外，不参与判定。 */
  public record OrderChangeCancelRuleQuery(
      String platformCode,
      String shopCode,
      OrderChangeKind changeKind,
      Boolean allowed,
      int page,
      int size) {}

  public record OrderChangeCancelRulePage(
      List<OrderChangeCancelRuleRow> items, long total, int page, int size) {}

  /** 履约投影的一行（一张订单行的三个数量口径）。 */
  public record OrderChangeCancelProgressRow(
      UUID orderLineId,
      String skuCode,
      BigDecimal orderedQuantity,
      BigDecimal reservedQuantity,
      BigDecimal pickingQuantity,
      BigDecimal shippedQuantity,
      OffsetDateTime shippedAt,
      OrderProgressSource progressSource,
      OffsetDateTime observedAt,
      /*
       * 投影是否已接入。false = 本服务还没有这一行的任何发货记录，
       * 界面上「未发货数量」必须显示「未接入」而不是 0 ——
       * 显示 0 等于宣称「一件都没发」，那是本仓库 8.3.1.2 / 9.1.1.2
       * 已经两次明令禁止的造假。
       */
      boolean projected) {}

  /** 变更请求的一行。 */
  public record OrderChangeCancelChangeRow(
      UUID id,
      UUID orderId,
      String orderNo,
      OrderChangeKind changeKind,
      OrderChangeStatus status,
      String reason,
      String currency,
      BigDecimal refundAmount,
      UUID platformRuleId,
      boolean platformAllowed,
      OffsetDateTime requestedAt,
      UUID requestedByMembershipId,
      OffsetDateTime decidedAt,
      String rejectionReason,
      long version,
      OffsetDateTime createdAt) {}

  /** 变更请求的完整视图（明细 + 地址字段 + 回写任务 + 时间线）。 */
  public record OrderChangeCancelChangeDetail(
      OrderChangeCancelChangeRow header,
      List<OrderChangeCancelChangeLineRow> lines,
      List<OrderChangeCancelAddressFieldRow> addressFields,
      @Schema(nullable = true) List<OrderChangeCancelWritebackRow> writebackTasks,
      List<OrderChangeCancelEventRow> timeline,
      String consumptionNote) {}

  public record OrderChangeCancelChangeLineRow(
      UUID id,
      UUID orderLineId,
      String skuCode,
      BigDecimal originalQuantity,
      BigDecimal requestedQuantity,
      BigDecimal deltaQuantity,
      BigDecimal unitPrice,
      BigDecimal lineAmount) {}

  public record OrderChangeCancelAddressFieldRow(
      OrderAddressRole addressRole,
      OrderAddressFieldCode fieldCode,
      String beforeValue,
      String afterValue) {}

  /** 取消请求的一行。 */
  public record OrderChangeCancelCancellationRow(
      UUID id,
      UUID orderId,
      String orderNo,
      OrderCancelScope cancelScope,
      OrderChangeStatus status,
      String reason,
      String currency,
      BigDecimal refundAmount,
      UUID platformRuleId,
      boolean platformAllowed,
      boolean shippedQuantityVerified,
      OffsetDateTime requestedAt,
      UUID requestedByMembershipId,
      OffsetDateTime decidedAt,
      String rejectionReason,
      long version,
      OffsetDateTime createdAt) {}

  /** 取消请求的完整视图：逐行数量 + 仓库任务 + 平台回写 + 时间线。 */
  public record OrderChangeCancelCancellationDetail(
      OrderChangeCancelCancellationRow header,
      List<OrderChangeCancelCancellationLineRow> lines,
      List<OrderChangeCancelHoldTaskRow> warehouseTasks,
      @Schema(nullable = true) List<OrderChangeCancelWritebackRow> writebackTasks,
      List<OrderChangeCancelProgressRow> progress,
      List<OrderChangeCancelEventRow> timeline,
      /** 真正的确认闸门（仓库回执、平台回写）。为空才允许确认。 */
      List<OrderChangeCancelBlocker> blockers,
      /** 数据完整性提示（不阻止确认，但界面必须显示，不能当 0）。 */
      List<OrderChangeCancelBlocker> warnings,
      String consumptionNote) {}

  /**
   * 取消逐行明细。
   *
   * @param cancelledQuantity <b>责任方确认前恒为 0</b>（V119 守卫 9.1）。
   *     界面拿它与 {@code cancelQuantity} 之差就是「已确认掉的量」；
   *     显示成申请量会在仓库一个货都没放的时候告诉运营「已取消 3 件」。
   * @param shipBlocking 是否因已发货被拒（可取消量为 0）。
   */
  public record OrderChangeCancelCancellationLineRow(
      UUID id,
      UUID orderLineId,
      String skuCode,
      BigDecimal orderedQuantity,
      BigDecimal shippedQuantityAtRequest,
      BigDecimal cancelQuantity,
      BigDecimal cancelledQuantity,
      BigDecimal cancelableQuantity,
      BigDecimal unitPrice,
      BigDecimal refundAmount,
      boolean shipBlocking,
      String note) {}

  /** 仓库停止作业 / 释放占用任务。 */
  public record OrderChangeCancelHoldTaskRow(
      UUID id,
      UUID cancellationRequestId,
      String warehouseRef,
      String reservationRef,
      OrderHoldTaskStage taskStage,
      OrderHoldTaskStatus status,
      String targetApi,
      String ownerModule,
      BigDecimal targetQuantity,
      BigDecimal completedQuantity,
      String resultNote,
      String failureReason,
      OffsetDateTime acknowledgedAt,
      OffsetDateTime completedAt) {}

  /** 平台回写任务。 */
  public record OrderChangeCancelWritebackRow(
      UUID id,
      UUID changeRequestId,
      UUID cancellationRequestId,
      String platformCode,
      String shopCode,
      String platformOrderRef,
      OrderChangeKind changeKind,
      OrderWritebackStatus status,
      String ownerModule,
      int attemptCount,
      String lastError,
      OffsetDateTime submittedAt,
      OffsetDateTime settledAt) {}

  /** 事件时间线的一行。 */
  public record OrderChangeCancelEventRow(
      UUID id,
      String eventType,
      String fromStatus,
      String toStatus,
      String detail,
      UUID actorMembershipId,
      OffsetDateTime occurredAt) {}

  /**
   * 「现在还差什么才能确认」的一条阻塞项。
   *
   * <p>存在的理由：取消单的确认有两个责任方前置条件（仓库释放完成、平台接受）。
   * 界面上只显示一个 {@code status=REQUESTED} 的话，操作人看不出**卡在哪**；
   * 让服务端逐条报出阻塞项，比让人去翻两个模块的表快得多。
   */
  public record OrderChangeCancelBlocker(
      String code, String ownerModule, String description, Map<String, Object> detail) {}

  /** 变更 / 取消请求的集合查询。 */
  public record OrderChangeCancelQuery(
      UUID orderId, String orderNo, OrderChangeStatus status, String changeKind, int page, int size) {}

  public record OrderChangeCancelPage<T>(List<T> items, long total, int page, int size) {}

  /**
   * 能力声明：本模块哪些能力有数据、哪些还没接上。
   *
   * <p>与 9.1.1.1 的 {@code /list/capabilities}（列表页筛选边界）、
   * 9.1.1.2 的 {@code /detail/capabilities}（详情页分区边界）三者并排读，
   * 才不会把「本模块能改什么」当成「订单详情里有什么」。
   */
  public record OrderChangeCancelCapabilities(
      List<String> availableCapabilities,
      List<OrderChangeCancelBlocker> unavailableCapabilities,
      List<String> statusTransitions,
      String warehouseReleaseApi,
      String aftersalesOwnerModule,
      String tenantIsolationNote,
      String snapshotNote) {}
}
