package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 9.6.1.3 代发履约跟踪与异常的传输模型。
 *
 * <p>蓝图原话：「跟踪代发订单的供应商发货与妥投状态，同步供应商运单号回传平台， 处理代发异常（延迟、丢件、供应商跑单），并把『我方』与『供应商』两段物流在订单上
 * 分开呈现。输出：代发履约状态；异常清单 + 处置（催供应商/改自营发货/退款）。」
 *
 * <p><b>命名纪律（硬要求）</b>：springdoc 用 record 的<b>简单类名</b>做 {@code components.schemas} 的键，本仓已有 {@code
 * WarehouseRow}、 {@code DropshipOrderRow} 等。本文件所有 record 一律以 {@code DropshipTracking} 或 {@code
 * DropshipException} / {@code DropshipDisposition} / {@code DropshipTask} 开头，且**不复用** 9.6.1.2 的
 * {@code Dropship*} 类型名 —— 契约文件里撞名会让两个模块的 schema 互相覆盖。
 *
 * <p><b>四条建模纪律</b>：
 *
 * <ul>
 *   <li><b>两段物流是两个字段组，不是一个。</b>传输模型里 {@code supplierLegStatus / supplierTrackingNo /
 *       supplierCarrierCode / …} 与 {@code ownLegStatus / ownTrackingNo / ownCarrierCode / …}
 *       永远成对出现但 <b>各自独立</b>，界面上也必须分开画（见 workspace）。合成一列在「改自营发货」 那一刻就会把「供应商发过货」这个凭据覆盖掉。
 *   <li><b>异常带判定证据。</b>{@code ruleCode / ruleThresholdHours / ruleEvidence} 随异常一起返回，而不是只给一个「延迟」标签
 *       —— 没有判据的异常无法复核。
 *   <li><b>未回传就是未回传。</b>{@code callbackStatus} 停在 PENDING/QUEUED 时，
 *       界面必须显示「待回传（平台）」，不允许出现「已回传」这种假结论。
 *   <li><b>下游未交付时，任务是真的任务。</b>退款与回传落在 {@link DropshipTaskRow} 里（有状态机、有载荷、有幂等键），而不是在前端画成「已提交退款」。
 * </ul>
 */
public final class DropshipTrackingModels {

  private DropshipTrackingModels() {}

  // ------------------------------------------------------------ 枚举

  /** 生效段：纯代发时是 SUPPLIER，「改自营发货」后翻成 OWN。 */
  public enum DropshipTrackingLeg {
    SUPPLIER("供应商段（供应商 → 买家）"),
    OWN("我方段（我方 → 买家）");

    private final String label;

    DropshipTrackingLeg(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 供应商段状态机（与 V126 的 CHECK 同一词表）。
   *
   * <p>注意它与 {@link DropshipOwnLegStatus} <b>不是同一个词表</b>： 供应商段有「已发货 / 丢件 / 废弃」，我方段没有 —— 把两段压成一个枚举正是
   * 「合成一个运单号」的代码版本。
   */
  public enum DropshipSupplierLegStatus {
    NOT_SHIPMENT("未发货"),
    SHIPPED("供应商已发货"),
    IN_TRANSIT("在途"),
    DELIVERED("已妥投"),
    LOST("丢件"),
    ABANDONED("已废弃（改自营发货后）");

    private final String label;

    DropshipSupplierLegStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 我方段状态机。与供应商段<b>刻意不同</b>：以「交运」而非「发货」为起点。 */
  public enum DropshipOwnLegStatus {
    NOT_HANDED_OVER("我方未交运"),
    HANDED_OVER("我方已交运"),
    IN_TRANSIT("在途"),
    DELIVERED("已妥投"),
    RETURNED("已退回");

    private final String label;

    DropshipOwnLegStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 三类履约异常（蓝图列举的「延迟、丢件、供应商跑单」）。
   *
   * <p>三者的处置动作完全不同：延迟要催货或切换履约，丢件要查件或补发， 跑单要退款 —— 所以枚举里各自带一个默认处置建议。
   */
  public enum DropshipExceptionKind {
    DELAYED_SHIPMENT("延迟发货", "WARNING", "PAST_PROMISED_SHIP_NO_SHIPMENT", "超时未发货", "催供应商 / 改自营发货"),
    PARCEL_LOST(
        "丢件（轨迹停滞）", "WARNING", "NO_TRACK_EVENT_BEYOND_THRESHOLD", "轨迹长时间无更新", "查件 / 补发 / 退款"),
    SUPPLIER_RUNNING_AWAY(
        "供应商跑单（无运单号）", "CRITICAL", "SHIPPED_WITHOUT_TRACKING_NO", "供应商报发货后无运单", "退款 / 改自营发货");

    private final String label;
    private final String defaultSeverity;
    private final String ruleCode;
    private final String ruleSummary;
    private final String suggestedActions;

    DropshipExceptionKind(
        String label,
        String defaultSeverity,
        String ruleCode,
        String ruleSummary,
        String suggestedActions) {
      this.label = label;
      this.defaultSeverity = defaultSeverity;
      this.ruleCode = ruleCode;
      this.ruleSummary = ruleSummary;
      this.suggestedActions = suggestedActions;
    }

    public String label() {
      return label;
    }

    public String defaultSeverity() {
      return defaultSeverity;
    }

    public String ruleCode() {
      return ruleCode;
    }

    public String ruleSummary() {
      return ruleSummary;
    }

    public String suggestedActions() {
      return suggestedActions;
    }
  }

  /** 异常状态机。DISMISSED = 判为误报（必须写明理由）。 */
  public enum DropshipExceptionStatus {
    OPEN("未处置"),
    ACKNOWLEDGED("已确认"),
    DISPOSED("已处置"),
    DISMISSED("判为误报");

    private final String label;

    DropshipExceptionStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 处置动作（蓝图输出原文：催供应商 / 改自营发货 / 退款）。 */
  public enum DropshipDispositionAction {
    URGE_SUPPLIER("催供应商", "SUPPLIER"),
    SWITCH_TO_SELF("改自营发货", "WAREHOUSE"),
    REQUEST_REFUND("发起退款", "AFTER_SALE");

    private final String label;
    private final String targetSystem;

    DropshipDispositionAction(String label, String targetSystem) {
      this.label = label;
      this.targetSystem = targetSystem;
    }

    public String label() {
      return label;
    }

    public String targetSystem() {
      return targetSystem;
    }
  }

  /** 处置状态机（PENDING → SENT → ACKNOWLEDGED → COMPLETED，PENDING → CANCELLED）。 */
  public enum DropshipDispositionStatus {
    PENDING("待发出"),
    SENT("已发出"),
    ACKNOWLEDGED("已受理"),
    COMPLETED("已办结"),
    CANCELLED("已撤销");

    private final String label;

    DropshipDispositionStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 任务种类 —— 与「这一跳要递给谁」严格一一对应（DB CHECK 钉死）。 */
  public enum DropshipTaskKind {
    PLATFORM_CALLBACK("运单号回传平台", "CHANNEL", "2.5.1.1（channel）"),
    SUPPLIER_URGE("催供应商", "SUPPLIER", "供应商"),
    SELF_FULFILL_SWITCH("改自营发货", "WAREHOUSE", "我方仓库"),
    REFUND_REQUEST("退款待办", "AFTER_SALE", "11 售后");

    private final String label;
    private final String targetSystem;
    private final String targetOwner;

    DropshipTaskKind(String label, String targetSystem, String targetOwner) {
      this.label = label;
      this.targetSystem = targetSystem;
      this.targetOwner = targetOwner;
    }

    public String label() {
      return label;
    }

    public String targetSystem() {
      return targetSystem;
    }

    public String targetOwner() {
      return targetOwner;
    }
  }

  /** 任务状态机。ACKNOWLEDGED 只由下游接手后写回。 */
  public enum DropshipTaskStatus {
    OPEN("待领取"),
    CLAIMED("已领取"),
    SENT("已提交下游"),
    ACKNOWLEDGED("下游已受理"),
    FAILED("失败待重试"),
    CANCELLED("已取消");

    private final String label;

    DropshipTaskStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 供应商运单号回传平台的状态（2.5.1.1 未交付）。 */
  public enum DropshipCallbackStatus {
    NOT_REQUIRED("无需回传"),
    PENDING("待回传"),
    QUEUED("已排队待下发"),
    SENT("已提交平台"),
    ACKNOWLEDGED("平台已受理"),
    FAILED("回传失败");

    private final String label;

    DropshipCallbackStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ------------------------------------------------------------ 输入

  /** 建立跟踪：一张代发单一条跟踪，承诺发货时限必填（延迟判定的基准）。 */
  public record DropshipTrackingInput(
      @NotNull @Schema(description = "9.6.1.2 代发单 ID（同库引用）") UUID orderId,
      @NotNull @Schema(description = "供应商承诺发货时限；延迟判定以它为基准") OffsetDateTime supplierPromisedShipAt,
      @Size(max = 400) String note) {}

  /** 录入/更新供应商段（发货、轨迹、妥投）。运单号与承运商分列，不与我方段共用。 */
  public record DropshipSupplierLegInput(
      @NotBlank
          @Schema(description = "供应商段状态：NOT_SHIPMENT/SHIPPED/IN_TRANSIT/DELIVERED/LOST/ABANDONED")
          String supplierLegStatus,
      @Size(max = 64) String supplierCarrierCode,
      @Size(max = 128) String supplierTrackingNo,
      @Schema(nullable = true) OffsetDateTime supplierShippedAt,
      @Schema(nullable = true, description = "轨迹最后更新时刻（由观测方给）；用写入时刻冒充会让停滞检测失效")
          OffsetDateTime supplierLastEventAt,
      @Schema(nullable = true) OffsetDateTime supplierDeliveredAt,
      @Size(max = 400) String supplierExceptionNote) {}

  /** 录入/更新我方段（交运、轨迹、妥投）。切换履约模式后这一段才开始推进。 */
  public record DropshipOwnLegInput(
      @NotBlank
          @Schema(description = "我方段状态：NOT_HANDED_OVER/HANDED_OVER/IN_TRANSIT/DELIVERED/RETURNED")
          String ownLegStatus,
      @Size(max = 64) String ownCarrierCode,
      @Size(max = 128) String ownTrackingNo,
      @Schema(nullable = true) OffsetDateTime ownHandedOverAt,
      @Schema(nullable = true) OffsetDateTime ownLastEventAt,
      @Schema(nullable = true) OffsetDateTime ownDeliveredAt,
      @Size(max = 400) String ownDeliveryNote) {}

  /** 检出策略（租户级单例）：阈值是经营口径，不写死在代码里。 */
  public record DropshipTrackingPolicyInput(
      @Min(0) @Max(720) @Schema(description = "承诺时限过了多少小时才算延迟（宽限）") Integer delayToleranceHours,
      @Min(1) @Max(2160) @Schema(description = "延迟严重度分界，超过则 CRITICAL") Integer criticalDelayHours,
      @Min(1) @Max(2160) @Schema(description = "轨迹多少小时无更新算丢件前兆") Integer stallThresholdHours,
      @Min(1) @Max(4320) @Schema(description = "停滞严重度分界，超过则 CRITICAL") Integer criticalStallHours,
      @Schema(description = "供应商报发货是否必须给运单号；false 时停用跑单规则") Boolean requireTrackingNoOnShipped,
      @Size(max = 400) String note) {}

  /** 处置：三类动作共用一个入口（动作与目标系统一一对应，不给自由文本）。 */
  public record DropshipDisposeInput(
      @NotBlank @Schema(description = "URGE_SUPPLIER / SWITCH_TO_SELF / REQUEST_REFUND")
          String actionKind,
      @Schema(description = "退款金额（numeric，不走浮点）；仅 REQUEST_REFUND 必填") BigDecimal refundAmount,
      @Schema(description = "退款币种；仅 REQUEST_REFUND 必填") String refundCurrencyCode,
      @NotBlank @Size(max = 400) String note) {}

  /** 处置状态流转。 */
  public record DropshipDispositionStatusInput(
      @NotBlank @Schema(description = "SENT / ACKNOWLEDGED / COMPLETED / CANCELLED")
          String toStatus,
      @Size(max = 400) String outcome) {}

  /** 判为误报。理由必填 —— 没有理由的误报会让下一次同类异常也被一并放过。 */
  public record DropshipDismissInput(
      @NotBlank @Size(max = 400) @Schema(description = "为什么这不是异常（必填）") String reason) {}

  /** 下游回执任务。 */
  public record DropshipTaskResultInput(
      @Schema(description = "true=受理成功，false=失败待重试（需给 error）") Boolean success,
      @Size(max = 400) String error) {}

  // ------------------------------------------------------------ 读模型

  /** 跟踪头（两段物流并列呈现，**不合成**）。 */
  public record DropshipTrackingRow(
      UUID id,
      String trackingNo,
      UUID orderId,
      String orderNo,
      UUID salesOrderId,
      String salesOrderNo,
      String supplierCode,
      String activeLeg,
      // --- 供应商段 ---
      String supplierLegStatus,
      String supplierCarrierCode,
      String supplierTrackingNo,
      @Schema(nullable = true) OffsetDateTime supplierShippedAt,
      @Schema(nullable = true) OffsetDateTime supplierLastEventAt,
      @Schema(nullable = true) OffsetDateTime supplierDeliveredAt,
      OffsetDateTime supplierPromisedShipAt,
      String supplierExceptionNote,
      // --- 我方段 ---
      String ownLegStatus,
      String ownCarrierCode,
      String ownTrackingNo,
      @Schema(nullable = true) OffsetDateTime ownHandedOverAt,
      @Schema(nullable = true) OffsetDateTime ownLastEventAt,
      @Schema(nullable = true) OffsetDateTime ownDeliveredAt,
      String ownDeliveryNote,
      // --- 切换与催单 ---
      @Schema(nullable = true) OffsetDateTime switchedToOwnAt,
      @Schema(nullable = true) UUID switchedToOwnByMembershipId,
      String switchReason,
      Integer urgeCount,
      @Schema(nullable = true) OffsetDateTime lastUrgedAt,
      // --- 回传平台 ---
      String callbackStatus,
      @Schema(nullable = true) OffsetDateTime callbackSentAt,
      Integer callbackAttempts,
      String callbackLastError,
      String note,
      Integer version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 履约异常（带判定证据，可复核）。 */
  public record DropshipTrackingExceptionRow(
      UUID id,
      String exceptionNo,
      UUID trackingId,
      UUID orderId,
      String orderNo,
      String salesOrderNo,
      String supplierCode,
      String exceptionKind,
      String exceptionKindLabel,
      String severity,
      String ruleCode,
      String ruleSummary,
      Integer ruleThresholdHours,
      String ruleEvidence,
      String status,
      @Schema(nullable = true) OffsetDateTime acknowledgedAt,
      @Schema(nullable = true) UUID acknowledgedByMembershipId,
      String resolutionNote,
      @Schema(nullable = true) OffsetDateTime resolvedAt,
      @Schema(nullable = true) UUID resolvedByMembershipId,
      OffsetDateTime detectedAt) {}

  /**
   * 任务载荷：跨服务那一跳真正需要的字段。
   *
   * <p>刻意是<b>具名 record</b>而不是 {@code Map<String,Object>}：Map 的值类型会被 springdoc 推成 {@code
   * object}，codegen 出来的 TS 就是个空对象 {@code {}}， 前端既拿不到运单号也过不了类型检查。这里逐个列出来，下游要什么一目了然，
   * 而且**刻意不复制整张代发单过去** —— 下游只需要这几项。
   *
   * <p>金额一律用字符串承载（refundAmount）：走 BigDecimal 的精确十进制文本， 不让 JSON 里的浮点把它变成 31.567799999999999。
   */
  public record DropshipTaskPayload(
      @Schema(nullable = true) String orderNo,
      @Schema(nullable = true) String salesOrderNo,
      @Schema(nullable = true) String supplierCode,
      @Schema(nullable = true) String supplierTrackingNo,
      @Schema(nullable = true) String supplierCarrierCode,
      @Schema(nullable = true) String supplierShippedAt,
      @Schema(nullable = true) String refundAmount,
      @Schema(nullable = true) String refundCurrencyCode,
      @Schema(nullable = true) String urgedAt,
      @Schema(nullable = true) String switchedAt,
      @Schema(nullable = true) String note) {}

  /** 处置记录。 */
  public record DropshipDispositionRow(
      UUID id,
      String dispositionNo,
      UUID exceptionId,
      UUID trackingId,
      String orderNo,
      String actionKind,
      String actionLabel,
      String status,
      @Schema(nullable = true) BigDecimal refundAmount,
      @Schema(nullable = true) String refundCurrencyCode,
      String targetSystem,
      String note,
      UUID createdByMembershipId,
      @Schema(nullable = true) OffsetDateTime sentAt,
      @Schema(nullable = true) OffsetDateTime acknowledgedAt,
      @Schema(nullable = true) OffsetDateTime completedAt,
      @Schema(nullable = true) OffsetDateTime cancelledAt,
      String outcome,
      OffsetDateTime createdAt) {}

  /** 待回传 / 待处置任务（跨服务那一跳的权威待办）。 */
  public record DropshipTaskRow(
      UUID id,
      String taskNo,
      String taskKind,
      String taskKindLabel,
      String taskStatus,
      UUID trackingId,
      UUID orderId,
      String orderNo,
      String salesOrderNo,
      @Schema(nullable = true) UUID dispositionId,
      @Schema(nullable = true) UUID exceptionId,
      String targetSystem,
      String targetOwner,
      @Schema(description = "跨服务动作真正需要的载荷（运单号/金额等），不复制整张代发单") DropshipTaskPayload payload,
      String idempotencyKey,
      Integer attemptCount,
      @Schema(nullable = true) OffsetDateTime lastAttemptAt,
      String lastError,
      @Schema(nullable = true) UUID claimedByMembershipId,
      @Schema(nullable = true) OffsetDateTime claimedAt,
      @Schema(nullable = true) OffsetDateTime finishedAt,
      OffsetDateTime createdAt) {}

  /** 事件流条目（两段状态都记，切换那一瞬才看得出供应商段去哪了）。 */
  public record DropshipTrackingEventRow(
      UUID id,
      UUID trackingId,
      String eventKind,
      String fromActiveLeg,
      String toActiveLeg,
      String fromSupplierLegStatus,
      String toSupplierLegStatus,
      String fromOwnLegStatus,
      String toOwnLegStatus,
      String detail,
      @Schema(nullable = true) UUID actorMembershipId,
      OffsetDateTime occurredAt) {}

  /** 检出策略。 */
  public record DropshipTrackingPolicyRow(
      @Schema(nullable = true) UUID id,
      boolean policyRegistered,
      Integer delayToleranceHours,
      Integer criticalDelayHours,
      Integer stallThresholdHours,
      Integer criticalStallHours,
      Boolean requireTrackingNoOnShipped,
      String note,
      @Schema(nullable = true) OffsetDateTime updatedAt) {}

  // ------------------------------------------------------------ 分页与保存结果

  public record DropshipTrackingPage(
      List<DropshipTrackingRow> items, long total, int page, int size) {}

  public record DropshipTrackingExceptionPage(
      List<DropshipTrackingExceptionRow> items, long total, int page, int size) {}

  public record DropshipTaskPage(List<DropshipTaskRow> items, long total, int page, int size) {}

  public record DropshipTrackingEventPage(
      List<DropshipTrackingEventRow> items, long total, int page, int size) {}

  public record DropshipTrackingSaved(UUID id, String trackingNo, boolean created) {}

  public record DropshipDispositionSaved(UUID id, String dispositionNo, String status) {}

  public record DropshipTaskSaved(UUID id, String taskNo, String taskStatus) {}

  /** 检出一轮的结果：扫了多少张、检出几条（未结案去重后）。 */
  public record DropshipTrackingEvaluateResult(
      long scanned, long detected, List<String> detectedKinds) {}

  /** 跟踪详情：跟踪头 + 异常 + 处置 + 任务 + 事件流。跨租户在 RLS 下查不到 → 404。 */
  public record DropshipTrackingDetail(
      DropshipTrackingRow tracking,
      List<DropshipTrackingExceptionRow> exceptions,
      List<DropshipDispositionRow> dispositions,
      List<DropshipTaskRow> tasks,
      List<DropshipTrackingEventRow> events) {}

  // ------------------------------------------------------------ 能力声明

  /**
   * 能力声明：把「哪些输入真有依据、哪些下游还没交付」显式报出来。
   *
   * <p>本模块最要紧的两处都是 available=false 且点名 ownerModule： <b>平台回传</b>（2.5.1.1 未交付）与 <b>售后退款</b>（11 未交付）。
   * 它们在本模块里的真实形态是**任务**，不是已完成的动作。
   */
  public record DropshipTrackingCapabilities(
      List<String> supplierLegStatuses,
      List<String> ownLegStatuses,
      List<String> exceptionKinds,
      List<String> dispositionActions,
      List<String> taskKinds,
      List<DropshipTrackingCapabilityRule> rules,
      List<DropshipTrackingCapabilityDimension> dimensions,
      boolean platformCallbackReady,
      boolean refundReady,
      String summary,
      String twoLegNote) {}

  /** 一条检出规则的自述（前端把「异常是怎么来的」原样显示，不让用户猜）。 */
  public record DropshipTrackingCapabilityRule(
      String exceptionKind,
      String label,
      String ruleCode,
      String ruleSummary,
      String suggestedActions,
      String defaultSeverity) {}

  /** 上游 / 下游维度可用性。 */
  public record DropshipTrackingCapabilityDimension(
      String code,
      String name,
      boolean available,
      String source,
      @Schema(nullable = true) String ownerModule,
      @Schema(nullable = true) String note) {}

  // ------------------------------------------------------------ 查询参数

  public record DropshipTrackingQuery(
      String orderNo,
      String salesOrderNo,
      String supplierCode,
      String activeLeg,
      String supplierLegStatus,
      String ownLegStatus,
      String exceptionOnly,
      String keyword,
      int page,
      int size) {}

  public record DropshipTrackingExceptionQuery(
      UUID orderId,
      String exceptionKind,
      String status,
      String severity,
      String keyword,
      int page,
      int size) {}

  public record DropshipTaskQuery(
      String taskKind,
      String taskStatus,
      String targetSystem,
      String keyword,
      int page,
      int size) {}

  public record DropshipTrackingEventQuery(UUID trackingId, int page, int size) {}
}
