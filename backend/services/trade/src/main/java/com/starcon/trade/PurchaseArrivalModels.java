package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 6.3.2.2 到货与逾期的传输模型。
 *
 * <p>蓝图 06-purchase-and-supply.md 原文：
 *
 * <blockquote>
 * 职责：跟踪采购单的到货状态：已发货（在途）、部分到货、全部到货、逾期未到、取消。
 * 逾期自动预警并给出处置建议（催货/改供应商/调库存/延期售卖）。
 * 输入：8 仓储的收货事件；跟单记录；物流在途状态（10）。
 * 输出：到货状态 + 逾期预警 + 处置建议。
 * 关系：上游 = 8 仓储作业、10 物流；下游 = 6.2.1.1 断货风险、1 工作台待办。
 * <b>部分到货必须立即更新已收数量并触发应付部分确认</b>，否则采购与财务三方数据对不上。
 * </blockquote>
 *
 * <p><b>本文件最重要的四件事</b>：
 *
 * <ol>
 *   <li><b>状态机写在枚举里</b>。{@link PurchaseArrivalStatus#canTransitionTo} 是<b>唯一</b>的
 *       合法流转口径，Java 侧与 DB 守卫①（V235 第三节）逐条对齐 ——
 *       两处写同一张表，是因为「服务端说合法、DB 说非法」时排查成本最高。</li>
 *   <li><b>「可收 / 已收 / 未收」三件套并排出现在每一行</b>。
 *       只给一个「已收数量」的话，界面上「还差多少」要靠减法自己算 ——
 *       而减法在两处页面上各写一次就会算出两个数。</li>
 *   <li><b>逾期判定带口径与判定时刻</b>。{@link PurchaseArrivalRow#overdueDays} 与
 *       {@link PurchaseArrivalPage#evaluatedAt} 必须一起看：
 *       逾期天数是「按哪个时刻算的」，不给出这个时刻，清单上的数字就没有意义。</li>
 *   <li><b>处置建议带依据</b>。{@link PurchaseArrivalDispositionRow#basisNote} 是人话，
 *       {@link PurchaseArrivalDispositionRow#basisFacts} 是同一句话里的数字（jsonb）——
 *       界面不必去解析中文就能把「逾期 12 天 / 缺口 40 件」摆出来。</li>
 * </ol>
 *
 * <p><b>命名。</b>统一用 {@code PurchaseArrival*} 前缀，与 {@code SupplierClaim*} /
 * {@code ProcessingOrder*} 零重叠 —— 契约被 codegen 合并进一个扁平文件、同名后者覆盖前者且不报冲突。
 */
public final class PurchaseArrivalModels {

  private PurchaseArrivalModels() {}

  // ================================================================ 枚举

  /**
   * 到货状态机。
   *
   * <p>蓝图职责句的五个状态（已发货在途 / 部分到货 / 全部到货 / 逾期未到 / 取消）
   * 加上一个**待发货** —— 没有它，「供应商还没发货、但承诺日还没到」这一档无处安放，
   * 而这一档恰恰是绝大多数采购单在正常期的样子。
   *
   * <p>⚠️ <b>逾期未到是一个状态，不是一个标记</b>：它有明确的进入路径
   * （待发货 / 部分到货 + 承诺日已过）与退出路径（货到了 / 取消了），
   * 所以它是可留痕、可解释的，而不是一个每次读都要重算的布尔。
   *
   * <p>⚠️ <b>在途不判逾期</b>：{@link #IN_TRANSIT} 只能流向部分到货 / 全部到货 / 取消，
   * <b>不能</b>流向 {@link #OVERDUE}。理由写在 DB 守卫① 里：还在路上，判逾期会误伤。
   */
  public enum PurchaseArrivalStatus {
    PENDING_SHIPMENT("待发货"),
    IN_TRANSIT("已发货（在途）"),
    PARTIALLY_RECEIVED("部分到货"),
    FULLY_RECEIVED("全部到货"),
    OVERDUE("逾期未到"),
    CANCELLED("取消");

    private final String label;

    PurchaseArrivalStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 终态：不可再流转、不可再改。 */
    public boolean terminal() {
      return this == FULLY_RECEIVED || this == CANCELLED;
    }

    /**
     * 合法流转判定（<b>唯一</b>口径，与 V235 守卫① 逐条对齐）。
     *
     * <p>刻意写成显式的白名单而不是「排除终态」：白名单里没有的组合一律拒绝，
     * 新增状态时不会因为「忘了排除」而悄悄放行。
     */
    public boolean canTransitionTo(PurchaseArrivalStatus next) {
      if (next == null || next == this) {
        return next == this;
      }
      return switch (this) {
        case PENDING_SHIPMENT ->
            next == IN_TRANSIT
                || next == PARTIALLY_RECEIVED
                || next == FULLY_RECEIVED
                || next == OVERDUE
                || next == CANCELLED;
        case IN_TRANSIT ->
            // ⚠️ **在途不判逾期**：OVERDUE 不在白名单里。
            next == PARTIALLY_RECEIVED || next == FULLY_RECEIVED || next == CANCELLED;
        case PARTIALLY_RECEIVED ->
            next == PARTIALLY_RECEIVED
                || next == FULLY_RECEIVED
                || next == OVERDUE
                || next == CANCELLED;
        case OVERDUE ->
            // 逾期之后供应商才发货是常事 ⇒ 允许回到在途。
            next == IN_TRANSIT
                || next == PARTIALLY_RECEIVED
                || next == FULLY_RECEIVED
                || next == CANCELLED;
        case FULLY_RECEIVED, CANCELLED -> false;
      };
    }

    /** 非法跃迁时给界面看的理由。 */
    public String transitionBlockedReason(PurchaseArrivalStatus next) {
      if (next == null) {
        return "必须给出目标状态";
      }
      if (next == this) {
        return null;
      }
      if (terminal()) {
        return "「" + label + "」已终结（终态），不可再流转";
      }
      if (this == IN_TRANSIT && next == OVERDUE) {
        return "已发货在途的跟踪单不判逾期（还在路上，判逾期会误伤）";
      }
      return "不允许从「" + label + "」流转到「" + next.label + "」";
    }
  }

  /** 到货流水的一次登记类型（<b>由 DB 定</b>，调用方传不进来）。 */
  public enum PurchaseArrivalType {
    PARTIAL("部分到货"),
    FULL("全部到货");

    private final String label;

    PurchaseArrivalType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 应付部分确认的状态。
   *
   * <p>⚠️ <b>登记时恒为 {@link #PENDING_FINANCE_CONFIRM}、生效金额恒为 0</b> ——
   * 下游 12 财务（finance 服务）**当前不存在**，本模块只落 trade 侧自有事实记录。
   * 绝不允许返回一个「已触发财务确认」的假回执。
   */
  public enum PayableConfirmationStatus {
    PENDING_FINANCE_CONFIRM("待财务确认"),
    CONFIRMED("财务已确认"),
    REJECTED("财务已驳回");

    private final String label;

    PayableConfirmationStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 处置建议的动作类型（蓝图职责句的四种）。 */
  public enum DispositionActionType {
    URGE_SUPPLIER("催货"),
    SWITCH_SUPPLIER("改供应商"),
    ADJUST_STOCK("调库存"),
    DELAY_SALE("延期售卖");

    private final String label;

    DispositionActionType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 处置建议的严重度。 */
  public enum DispositionSeverity {
    INFO("提示"),
    WARN("警告"),
    CRITICAL("严重");

    private final String label;

    DispositionSeverity(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 处置建议的处理状态。 */
  public enum DispositionStatus {
    OPEN("待处理"),
    ADOPTED("已采纳"),
    DISMISSED("已忽略");

    private final String label;

    DispositionStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 预警类型。
   *
   * <p>⚠️ 刻意把「逾期」与「在途滞留」分成两类：<b>在途不判逾期</b>是硬规则，
   * 但一票货在途滞留 20 天显然需要有人管 —— 把它混进逾期里会让「逾期天数」这个数字失真，
   * 把它单列一类则两个口径各自干净。
   */
  public enum ArrivalAlertType {
    OVERDUE("逾期未到"),
    IN_TRANSIT_STALLED("在途滞留");

    private final String label;

    ArrivalAlertType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 查询

  /** 列表查询。筛选维度：状态 / 供应商 / 是否逾期 / 关键字 / 承诺日区间 / 排序。 */
  public record PurchaseArrivalQuery(
      Integer page,
      Integer size,
      String status,
      UUID supplierRefId,
      Boolean overdueOnly,
      Boolean inTransitOnly,
      String keyword,
      LocalDate promisedBefore,
      String sort) {}

  // ================================================================ 行

  /**
   * 到货跟踪行。
   *
   * @param status <b>生效</b>状态（与 {@code persistedStatus} 一致：落库的即生效的，
   *     因为逾期落库在读取路径上已幂等刷新）
   * @param overdueDays 逾期天数；<b>在途恒为 0</b>（在途不判逾期）
   * @param daysUntilPromise 距承诺到货日还有几天；负数表示已过承诺日
   */
  @Schema(name = "PurchaseArrivalRow")
  public record PurchaseArrivalRow(
      UUID id,
      String trackingNo,
      String purchaseOrderNo,
      String sourceDocNo,
      String sourceDocType,
      UUID supplierRefId,
      String supplierCode,
      String supplierName,
      BigDecimal receivableQty,
      BigDecimal receivedQty,
      BigDecimal outstandingQty,
      @Schema(description = "已收比例（0~1，界面自己乘 100 显示百分比）") BigDecimal receivedRatio,
      BigDecimal unitPrice,
      String currency,
      LocalDate promisedArrivalDate,
      String promiseSource,
      String status,
      String statusLabel,
      @Schema(description = "是否逾期（在途恒为 false）") boolean overdue,
      @Schema(description = "逾期天数（在途恒为 0）") int overdueDays,
      @Schema(description = "距承诺到货日的天数，负数=已过承诺日") int daysUntilPromise,
      @Schema(description = "在途滞留天数（来自 10.4.1.2 快照，未同步为 0）") int inTransitDwellDays,
      String inTransitShipmentCode,
      String inTransitWaybillNo,
      OffsetDateTime inTransitEta,
      OffsetDateTime inTransitSyncedAt,
      @Schema(description = "到货次数") long arrivalCount,
      BigDecimal pendingPayableAmount,
      BigDecimal confirmedPayableAmount,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      long version) {}

  /** 列表页汇总（跨全表算，不随分页变小）。 */
  @Schema(name = "PurchaseArrivalSummary")
  public record PurchaseArrivalSummary(
      long total,
      long pendingShipment,
      long inTransit,
      long partiallyReceived,
      long fullyReceived,
      long overdue,
      long cancelled,
      @Schema(description = "逾期单缺口合计") BigDecimal overdueOutstandingQty,
      @Schema(description = "待财务确认的应付金额合计") BigDecimal pendingPayableAmount,
      @Schema(description = "财务已确认的应付金额合计") BigDecimal confirmedPayableAmount) {}

  /** 列表页。 */
  @Schema(name = "PurchaseArrivalPage")
  public record PurchaseArrivalPage(
      List<PurchaseArrivalRow> rows,
      int page,
      int size,
      long total,
      PurchaseArrivalSummary summary,
      @Schema(description = "本次逾期判定的时刻（DB 时钟），逾期天数按它算") OffsetDateTime evaluatedAt,
      @Schema(description = "逾期判定口径（界面不自造解释）") String overdueRuleNote,
      @Schema(description = "数量口径说明") String quantityNote,
      @Schema(description = "排序白名单说明") String sortNote) {}

  // ================================================================ 详情

  /** 详情：头 + 到货流水 + 应付部分确认 + 处置建议 + 留痕 + 可执行动作。 */
  @Schema(name = "PurchaseArrivalDetail")
  public record PurchaseArrivalDetail(
      PurchaseArrivalRow head,
      List<PurchaseArrivalReceiptRow> receipts,
      List<PurchaseArrivalPayableRow> payableConfirmations,
      List<PurchaseArrivalDispositionRow> dispositions,
      List<PurchaseArrivalEventRow> events,
      List<PurchaseArrivalAction> allowedActions,
      @Schema(description = "本次逾期判定的时刻（DB 时钟）") OffsetDateTime evaluatedAt,
      @Schema(description = "逾期判定口径") String overdueRuleNote,
      @Schema(description = "处置建议的推导规则说明") String dispositionRuleNote,
      @Schema(description = "上游可用性（8 仓储 / 10 物流 / 6.3.1.x 采购单 / 6.3.2.1 跟单 / 12 财务）")
          String upstreamNote) {}

  /** 一条到货流水。 */
  @Schema(name = "PurchaseArrivalReceiptRow")
  public record PurchaseArrivalReceiptRow(
      UUID id,
      int arrivalSeq,
      String arrivalType,
      String arrivalTypeLabel,
      BigDecimal arrivedQty,
      OffsetDateTime arrivedAt,
      OffsetDateTime recordedAt,
      String warehouseReceiptCode,
      BigDecimal warehouseReceiptReceivedQty,
      OffsetDateTime warehouseReceiptSyncedAt,
      String waybillNo,
      String note,
      UUID recordedBy) {}

  /** 一笔应付部分确认。 */
  @Schema(name = "PurchaseArrivalPayableRow")
  public record PurchaseArrivalPayableRow(
      UUID id,
      UUID receiptId,
      String confirmationNo,
      String confirmationStatus,
      String confirmationStatusLabel,
      BigDecimal confirmationAmount,
      String currency,
      BigDecimal arrivedQty,
      BigDecimal unitPrice,
      String basisNote,
      @Schema(description = "已生效金额：财务确认之前恒为 0") BigDecimal effectiveAmount,
      String financeReference,
      UUID confirmedBy,
      OffsetDateTime confirmedAt,
      UUID rejectedBy,
      OffsetDateTime rejectedAt,
      String rejectReason,
      OffsetDateTime createdAt) {}

  /** 一条处置建议。 */
  @Schema(name = "PurchaseArrivalDispositionRow")
  public record PurchaseArrivalDispositionRow(
      UUID id,
      String actionType,
      String actionTypeLabel,
      String severity,
      String severityLabel,
      String basisNote,
      @Schema(description = "依据里的数字（jsonb 原文）") String basisFacts,
      String dispositionStatus,
      String dispositionStatusLabel,
      String resolveNote,
      UUID resolvedBy,
      OffsetDateTime resolvedAt,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 一条留痕。 */
  @Schema(name = "PurchaseArrivalEventRow")
  public record PurchaseArrivalEventRow(
      UUID id,
      String eventType,
      String eventTypeLabel,
      String fromStatus,
      String toStatus,
      BigDecimal amount,
      BigDecimal quantity,
      String reasonNote,
      UUID occurredBy,
      OffsetDateTime occurredAt) {}

  /** 一个可执行动作（前端据此渲染按钮，未交付的动作**不出现**而不是给一个点不动的按钮）。 */
  @Schema(name = "PurchaseArrivalAction")
  public record PurchaseArrivalAction(
      String action,
      String label,
      boolean enabled,
      String method,
      String path,
      boolean needsBody,
      String requirement,
      @Schema(nullable = true) String blockedReason) {}

  // ================================================================ 逾期看板

  /** 逾期预警看板的一行。 */
  @Schema(name = "PurchaseArrivalAlertRow")
  public record PurchaseArrivalAlertRow(
      UUID id,
      String trackingNo,
      String purchaseOrderNo,
      String supplierCode,
      String supplierName,
      String alertType,
      String alertTypeLabel,
      String status,
      String statusLabel,
      int overdueDays,
      int inTransitDwellDays,
      LocalDate promisedArrivalDate,
      BigDecimal receivableQty,
      BigDecimal receivedQty,
      BigDecimal outstandingQty,
      @Schema(description = "缺口比例（0~1）") BigDecimal gapRatio,
      List<PurchaseArrivalDispositionRow> dispositions) {}

  /** 逾期预警看板汇总。 */
  @Schema(name = "PurchaseArrivalAlertSummary")
  public record PurchaseArrivalAlertSummary(
      long total,
      long overdueCount,
      long inTransitStalledCount,
      long criticalCount,
      long warnCount,
      BigDecimal overdueOutstandingQty,
      @Schema(description = "最大逾期天数") int maxOverdueDays) {}

  /** 逾期预警看板。 */
  @Schema(name = "PurchaseArrivalAlertBoard")
  public record PurchaseArrivalAlertBoard(
      List<PurchaseArrivalAlertRow> rows,
      int page,
      int size,
      long total,
      PurchaseArrivalAlertSummary summary,
      OffsetDateTime evaluatedAt,
      @Schema(description = "逾期与在途滞留两个口径的说明") String ruleNote,
      @Schema(description = "处置建议的推导规则说明") String dispositionRuleNote) {}

  // ================================================================ 请求

  /**
   * 建跟踪单。
   *
   * <p>⚠️ <b>请求体里没有「已收数量」</b>：已收数量永远由 DB 从到货流水重算（V235 守卫①），
   * 传进来也传不进来。
   */
  @Schema(name = "PurchaseArrivalCreate")
  public record PurchaseArrivalCreate(
      String purchaseOrderNo,
      @Schema(nullable = true) UUID purchaseOrderRefId,
      String sourceDocNo,
      String sourceDocType,
      UUID supplierRefId,
      String supplierCode,
      String supplierName,
      @Schema(description = "可收数量（NUMERIC(19,6)）") BigDecimal receivableQty,
      @Schema(description = "单价快照（应付部分确认的依据）") BigDecimal unitPrice,
      String currency,
      @Schema(description = "承诺到货日（跟单 / 采购单带来的输入事实）") LocalDate promisedArrivalDate,
      String promiseSource) {}

  /**
   * 标记已发货（在途）。
   *
   * <p>{@code shipmentCode} 是**头程发运单号**（10.4.1.1 逻辑引用）——
   * 本模块用它去 10.4.1.2 在途跟踪里找对应的在途记录并冻结快照。
   */
  @Schema(name = "PurchaseArrivalShip")
  public record PurchaseArrivalShip(
      Long expectedVersion,
      String shipmentCode,
      String waybillNo,
      OffsetDateTime shippedAt,
      @Schema(nullable = true, description = "新的承诺到货日（跟单改了交期就填这里）")
          LocalDate promisedArrivalDate,
      String promiseSource) {}

  /**
   * 登记到货（部分 / 全部）。
   *
   * <p>⚠️ <b>请求体里没有「到货类型」</b>：本次是部分还是全部由 DB 按缺口判定（V235 守卫②）。
   * 也没有「应付确认金额」：金额由 DB 按单价快照 × 本次到货数量算（V235 守卫③ / ④）。
   */
  @Schema(name = "PurchaseArrivalArrival")
  public record PurchaseArrivalArrival(
      Long expectedVersion,
      @Schema(description = "本次到货数量") BigDecimal arrivedQty,
      OffsetDateTime arrivedAt,
      @Schema(nullable = true, description = "8.2.1.1 收货单号（可选；给了就去 warehouse 授权核对）")
          String warehouseReceiptCode,
      String waybillNo,
      String note) {}

  /** 取消跟踪单。必须写理由。 */
  @Schema(name = "PurchaseArrivalCancel")
  public record PurchaseArrivalCancel(Long expectedVersion, String reason) {}

  /** 财务确认应付部分确认。需要**独立**权限（登记的人不该顺手把财务确认掉）。 */
  @Schema(name = "PurchaseArrivalPayableConfirm")
  public record PurchaseArrivalPayableConfirm(
      @Schema(description = "财务单据号（应付单号）") String financeReference) {}

  /** 财务驳回应付部分确认。必须写理由。 */
  @Schema(name = "PurchaseArrivalPayableReject")
  public record PurchaseArrivalPayableReject(String reason) {}

  /** 处置建议的处理（采纳 / 忽略）。必须写理由。 */
  @Schema(name = "PurchaseArrivalDispositionResolve")
  public record PurchaseArrivalDispositionResolve(
      @Schema(description = "ADOPTED 或 DISMISSED") String dispositionStatus, String note) {}

  // ================================================================ 能力声明

  /** 能力声明。 */
  @Schema(name = "PurchaseArrivalCapabilities")
  public record PurchaseArrivalCapabilities(
      List<String> dataSources,
      List<PurchaseArrivalCapabilitySection> sections,
      String stateMachineNote,
      String overdueRuleNote,
      String quantityRuleNote,
      String dispositionRuleNote,
      String payableRuleNote,
      String upstreamBoundaryNote) {}

  /** 能力声明的一段。 */
  @Schema(name = "PurchaseArrivalCapabilitySection")
  public record PurchaseArrivalCapabilitySection(
      String key,
      String label,
      boolean available,
      @Schema(nullable = true, description = "未交付时这个能力归属哪个模块号") String owner,
      @Schema(nullable = true, description = "不可用的原因（available=false 时非空）")
          String blockedReason,
      String detail,
      @Schema(nullable = true) String evidence) {}
}
