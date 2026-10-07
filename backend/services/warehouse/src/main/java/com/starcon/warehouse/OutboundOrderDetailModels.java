package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 8.3.1.2 出库单详情的传输模型。
 *
 * <p>蓝图原话：「单出库单作业视图：明细行与已拣/已发量、关联销售订单与买家地址、预占引用、
 * 包裹列表、作业时间线。」输入：「outbound_order_id；关联对象。」输出：「详情 + 作业操作入口。」
 * 以及本模块被点名的那条关系：「**详情页串联整条出库链路**，作业人员不需要在多个页面跳。」
 *
 * <p><b>本模块不拥有任何事实表</b>，是纯读投影：它读 8.3.1.1 的四张表（单头 / 明细计划快照 /
 * 拣货交接请求 / 作业留痕），把一屏之内该看到的东西聚到一起。七段内容里四段真实、三段
 * 不可得，不可得的一律 {@code available=false} 并给出 {@code ownerModule} 与原因。
 *
 * <p><b>已拣量为什么是 null 而不是 0</b>：8.3.1.1 刻意没建 picking_task（那张表归 8.3.2.1），
 * 它只做到 {@code outbound_pick_request} 交接为止 —— 那是**请求量快照**。填 0 会让作业员
 * 以为「一件都没拣」，那是假数字，比不显示更危险。所以逐行 {@code pickedQuantity} 恒为
 * {@code null}，由 {@link OutboundQuantityAvailability} 说清原因与归属模块。
 *
 * <p><b>已发量为什么可以是真数</b>：{@code status = SHIPPED} 是终态且 DB 层冻结（V72 §4.2），
 * 而明细行是不可改的计划快照（V72 §4.4）—— 在这张单上「已发量 = 计划件数」是成立的。
 * 前提是 {@code status = SHIPPED}，其余状态一律 {@code null}：不按拣货进度推算发货进度。
 */
public final class OutboundOrderDetailModels {

  private OutboundOrderDetailModels() {}

  // ------------------------------------------------------------ 不可得能力的统一形状

  /**
   * 一段**当前不可得**的数据：是什么、归谁、为什么现在拿不到、去哪看。
   *
   * <p>与 8.2.1.1 的 {@code ReceiptSourceCapability} 同一套约定（available + ownerModule +
   * href + note），刻意**不**用「返回空列表」表达「上游没交付」—— 空列表在界面上与
   * 「确实没有记录」长得一模一样，作业员会以为这单没预占、没包裹，而不是「还没接入」。
   */
  public record OutboundUnavailableLink(
      String label,
      boolean available,
      @Schema(nullable = true) String ownerModule,
      @Schema(nullable = true) String href,
      String reason) {}

  /**
   * 数量口径的可用性：已拣量 / 已发量各自能不能给，给不了归谁。
   *
   * <p>{@code value} 为 {@code null} 时界面必须显示成「待 8.3.2.1 交付」而不是 0。
   */
  public record OutboundQuantityAvailability(
      String quantity,
      String label,
      @Schema(nullable = true) BigDecimal value,
      boolean available,
      /** 不可得时才有值：已交付的量归本服务自己，归属模块为空。 */
      @Schema(nullable = true) String ownerModule,
      String reason) {}

  // ------------------------------------------------------------ 明细与时间线

  /**
   * 明细行 = 计划快照（8.3.1.1 的 outbound_order_line）。
   *
   * <p>{@code pickedQuantity} / {@code shippedQuantity} 逐行返回，但语义不同，见类注释。
   */
  public record OutboundDetailLine(
      int lineNo,
      UUID skuId,
      String skuCode,
      String skuName,
      /** 计划出库件数（NUMERIC(19,6) + BigDecimal，全程禁浮点）。 */
      BigDecimal plannedQuantity,
      /** 实拣量：8.3.2.1 未交付前恒为 null（**不是 0**）。 */
      @Schema(nullable = true) BigDecimal pickedQuantity,
      /** 已发量：仅 status=SHIPPED 时有值，其余为 null。 */
      @Schema(nullable = true) BigDecimal shippedQuantity) {}

  /** 作业时间线的一行（outbound_order_event，append-only，V72 §5.5 钉死不可改删）。 */
  public record OutboundDetailEvent(
      UUID id,
      String eventType,
      String eventTypeLabel,
      @Schema(nullable = true) String fromStatus,
      @Schema(nullable = true) String fromStatusLabel,
      @Schema(nullable = true) String toStatus,
      @Schema(nullable = true) String toStatusLabel,
      UUID actorUserId,
      OffsetDateTime occurredAt,
      String detail) {}

  /** 拣货交接请求视图（8.3.2.1 的契约位，与 8.3.1.1 的 OutboundPickRequestView 同形状）。 */
  public record OutboundDetailPickRequest(
      UUID id,
      int lineCount,
      BigDecimal totalPieceCount,
      String note,
      UUID requestedByUserId,
      OffsetDateTime requestedAt) {}

  /**
   * 当前状态的合法下一步（作业操作入口）。
   *
   * <p>直接来自 {@link OutboundOrderStatus#transitionsFrom()} —— 与 8.3.1.1 共用**同一个**
   * 转换矩阵，不另写一份。界面上只给这些按钮；DB 触发器在库层再挡一次。
   */
  public record OutboundDetailAction(
      String action,
      String toStatus,
      String toStatusLabel,
      boolean reasonRequired,
      boolean requiresCarrier,
      String note,
      /** 目标模块：推进动作由下游模块在自己的作业界面触发，详情页给出跳转入口。 */
      String ownerModule) {}

  // ------------------------------------------------------------ 详情主体

  /**
   * 出库单详情（GET /warehouse/inventory/outbound-orders/{id}）。
   *
   * <p>字段顺序与作业员的阅读顺序一致：单头 → 明细 → 数量口径 → 交接 → 时间线 → 不可得能力 → 操作入口。
   */
  public record OutboundOrderDetail(
      // ---- 单头（8.3.1.1 的形状，逐字复用列表行的口径）
      UUID id,
      String orderCode,
      String sourceType,
      String sourceTypeLabel,
      @Schema(nullable = true) UUID sourceTransferOrderId,
      @Schema(nullable = true) String sourceTransferCode,
      @Schema(nullable = true) String sourceExternalRef,
      UUID warehouseId,
      String warehouseCode,
      String warehouseName,
      String carrierCode,
      String status,
      String statusLabel,
      boolean terminal,
      @Schema(nullable = true) OffsetDateTime shippedAt,
      @Schema(nullable = true) OffsetDateTime cancelledAt,
      @Schema(nullable = true) String cancelReason,
      UUID createdByUserId,
      OffsetDateTime createdAt,
      // ---- 明细与合计
      int skuLineCount,
      /** 件数 = 计划出库件数合计（现算，不存列，见 V72 第五节）。 */
      BigDecimal totalPieceCount,
      List<OutboundDetailLine> lines,
      // ---- 数量口径：已拣 / 已发，各自独立说明能不能给
      List<OutboundQuantityAvailability> quantities,
      // ---- 拣货交接请求（8.3.2.1 的契约位；未交接时为 null）
      boolean pickRequested,
      @Schema(nullable = true) OutboundDetailPickRequest pickRequest,
      // ---- 作业时间线（append-only 全量）
      List<OutboundDetailEvent> timeline,
      int timelineEventCount,
      // ---- 四段不可得能力，逐段如实标注
      OutboundUnavailableLink linkedOrder,
      OutboundUnavailableLink shipToAddress,
      OutboundUnavailableLink reservationRefs,
      List<OutboundUnavailableLink> packages,
      // ---- 作业操作入口（合法下一步，来自同一个转换矩阵）
      List<OutboundDetailAction> actions) {}

  // ------------------------------------------------------------ 能力声明

  /**
   * 详情页能力声明：六段内容的可用性、两个数量的口径、以及四条必须写在界面上的说明。
   *
   * <p>与 8.3.1.1 的 {@code /capabilities} 分开：本端点报的是**详情页**的形状与边界
   * （含下游模块归属），两者并排读才不会把「列表能做什么」当成「详情能做什么」。
   */
  public record OutboundOrderDetailCapabilities(
      List<OutboundUnavailableLink> sections,
      List<OutboundQuantityAvailability> quantityPolicies,
      List<String> actionOwnerModules,
      String pickedQuantityNote,
      String shippedQuantityNote,
      String linkedOrderNote,
      String reservationNote,
      String packageNote) {}
}