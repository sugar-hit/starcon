package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 10.4.1.2 在途跟踪。
 *
 * <p>蓝图 10-logistics-and-freight.md 10.4.1.2 的输入/输出契约：
 *
 * <ul>
 *   <li>输入：货代/船司/清关状态同步；人工更新。
 *   <li>输出：在途看板 + 延误预警 + 到港提醒。
 * </ul>
 *
 * <p>DTO 一律带 {@code Tracking}/{@code TransitTrack} 前缀：四个服务的 schema 被 codegen
 * 拍平进同一命名空间，重名会被**静默覆盖且没有任何警告**（AGENTS.md 契约纪律）。
 * 10.4.1.1 已占用 {@code FirstMile*}，本模块**刻意不叫 {@code FirstMileTracking}**
 * 的同义重复，而是用 {@code Tracking} / {@code TransitTrack} / {@code DelayAlert} /
 * {@code DelayThreshold} 四个前缀，避免与上游的 {@code FirstMile*} 撞名。
 *
 * <p>四条本模块必须自己讲清楚的口径（都随响应返回，不让界面上凭空猜）：
 *
 * <ol>
 *   <li><b>预警按剩余窗口提前分级，不是到港当天才发现晚了</b>：蓝图关系段原文
 *       「头程延误是断货的主要外因，预警必须提前到预计到港前若干天」。
 *       所以分级由「剩余窗口 = 预计到港 − 现在」与阈值表逐级判定，
 *       三档提前量来自 {@code first_mile_delay_threshold} 表，**不是代码里的 if**。
 *   <li><b>外部同步未接入</b>：货代/船司/清关连接器（10.4.2.1）**未交付**，
 *       本模块**没有常驻同步任务**。外部事实靠 {@code sourceSystem} +
 *       {@code referenceId} + 事件流承载，来源不可为空的约束钉在 DB 层。
 *   <li><b>不建第二张在途账</b>：在途余量、已到、未达一律 join 7.5.1.2 的
 *       {@code inventory_transfer} 现算，本模块的表里**没有数量余额列**。
 *   <li><b>只记「目的仓确认收到了」，不写库存</b>：入库落账归 7.5.1.2 的 arrive。
 * </ol>
 */
public final class InTransitTrackingModels {
  private InTransitTrackingModels() {}

  /**
   * 跟踪生命周期状态。与 {@code first_mile_tracking.status} 的 CHECK 逐值对应。
   *
   * <p>刻意与 {@link TrackingStage} 分成**两个维度**：
   * {@code status} 是这条跟踪记录自己的生死（TRACKING → CLOSED / CANCELLED），
   * {@code trackingStage} 是这批货走到哪了。混成一个字段就无法表达
   * 「跟踪已关闭但清关仍在扣关中」这类真实组合。
   */
  public enum TrackingStatus {
    /** 在跟踪中。 */
    TRACKING("在跟踪"),
    /** 已闭环：目的仓确认收货后关闭。终态。 */
    CLOSED("已闭环"),
    /** 已取消：发运单被取消时跟进取消。终态。 */
    CANCELLED("已取消");

    private final String label;

    TrackingStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }

    public static TrackingStatus parse(String raw) {
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw)) {
          return s;
        }
      }
      throw new IllegalArgumentException("未知的跟踪状态：" + raw + "，可选：" + String.join("、", names()));
    }
  }

  /**
   * 跟踪阶段（在途位置状态机）。
   *
   * <p>顺序即推进顺序，DB 层用 {@code stage_rank()} 钉死「不许倒退」：
   * 在途是**推进**过程，倒退会让「这批货到过哪」永远答不出来。
   * {@code EXCEPTION} 是例外分支 —— 它不是推进中的一站，而是
   * 「这一批货出问题了」的标记，DB 里给了最高序号，所以从任何阶段都能进。
   */
  public enum TrackingStage {
    AT_ORIGIN("在起运地", 1),
    IN_TRANSIT("在途", 2),
    ARRIVED_ORIGIN_PORT("已到起运港/起运地", 3),
    IN_CUSTOMS("清关中", 4),
    CUSTOMS_CLEARED("已清关放行", 5),
    INLAND_TO_WAREHOUSE("已放行，运往目的仓", 6),
    RECEIVED("目的仓已收货确认", 7),
    EXCEPTION("节点异常", 8);

    private final String label;
    private final int rank;

    TrackingStage(String label, int rank) {
      this.label = label;
      this.rank = rank;
    }

    public String label() {
      return label;
    }

    /** 推进序号，与 DB 的 {@code stage_rank()} 同口径。 */
    public int rank() {
      return rank;
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }

    public static TrackingStage parse(String raw) {
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw)) {
          return s;
        }
      }
      throw new IllegalArgumentException("未知的跟踪阶段：" + raw + "，可选：" + String.join("、", names()));
    }
  }

  /**
   * 清关状态。
   *
   * <p>{@code HELD}（扣关）与 {@code REJECTED}（退单）必须有原因，DB 层 CHECK 钉死：
   * 这两个状态意味着货被卡住了，没有原因的「扣关」事后没人知道该找谁、该补什么材料。
   */
  public enum CustomsStatus {
    NOT_STARTED("未开始"),
    DECLARED("已申报"),
    UNDER_REVIEW("清关审核中"),
    CLEARED("已放行"),
    HELD("被扣关"),
    REJECTED("退单");

    private final String label;

    CustomsStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }

    public static CustomsStatus parse(String raw) {
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw)) {
          return s;
        }
      }
      throw new IllegalArgumentException("未知的清关状态：" + raw + "，可选：" + String.join("、", names()));
    }
  }

  /**
   * 延误预警档位。
   *
   * <p>三档与 {@link #ON_TRACK} 的关系：{@code ON_TRACK} 不是一档预警而是
   * 「三档都没命中」，所以它只出现在**评估结果**里，不落在
   * {@code first_mile_delay_alert} 表上（表上只有真需要人管的三档）。
   */
  public enum DelayLevel {
    ON_TRACK("正常在途", 0),
    WATCH("临近到港提示", 1),
    WARNING("延误预警", 2),
    CRITICAL("严重延误", 3);

    private final String label;
    private final int severity;

    DelayLevel(String label, int severity) {
      this.label = label;
      this.severity = severity;
    }

    public String label() {
      return label;
    }

    /** 严重度排序，用于「取最严重的一档」与前端排序。 */
    public int severity() {
      return severity;
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /**
   * 事实来源。
   *
   * <p>蓝图输入写「货代/船司/清关状态同步；人工更新」——两种来源形状一样
   * （都是「某时刻、某来源、货到了某个状态」），所以落同一张事件表并用本枚举区分，
   * 而不是造两张形状雷同的表。
   *
   * <p>{@code FORWARDER} / {@code CARRIER} / {@code CUSTOMS_BROKER} 三者对应的
   * **真实连接器均未交付**（10.4.2.1 海外仓连接与货代/船司连接器都还没开工），
   * 当前由人工代录 —— DB 层要求这三者必须带 {@code referenceId}（来源单号），
   * 写不出出处的外部事实一条都不许入库。
   */
  public enum TrackingSource {
    /** 人工登记（actor 必填）。 */
    MANUAL("人工登记"),
    /** 货代（连接器未交付，当前人工代录）。 */
    FORWARDER("货代"),
    /** 船司/航空公司（连接器未交付，当前人工代录）。 */
    CARRIER("船司/航空公司"),
    /** 清关行/报关行（连接器未交付，当前人工代录）。 */
    CUSTOMS_BROKER("清关行/报关行");

    private final String label;

    TrackingSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }

    public static TrackingSource parse(String raw) {
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw)) {
          return s;
        }
      }
      throw new IllegalArgumentException("未知的来源：" + raw + "，可选：" + String.join("、", names()));
    }

    /** 是否外部来源（外部来源必须有单号，人工来源必须有人）。 */
    public boolean external() {
      return this != MANUAL;
    }
  }

  /** 事件类型。与 {@code first_mile_tracking_event.kind} 逐值对应。 */
  public enum TrackingEventKind {
    /** 建立跟踪（人工）。 */
    CREATED("建立跟踪"),
    /** 位置更新。 */
    LOCATION("位置更新"),
    /** 预计到港改期。 */
    ETA_REVISED("预计到港改期"),
    /** 清关状态变化。 */
    CUSTOMS("清关状态"),
    /** 阶段推进。 */
    STAGE("阶段推进"),
    /** 目的仓收货确认。 */
    RECEIPT("目的仓收货确认"),
    /** 预警登记。 */
    ALERT("预警登记"),
    /** 备注。 */
    NOTE("备注");

    private final String label;

    TrackingEventKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  // ================================================================ 能力与查询

  /** 跟查询维度、**未交付依赖逐条声明**、两级状态机与四条口径说明。 */
  @Schema(name = "TransitTrackCapabilities")
  public record TransitTrackCapabilities(
      @Schema(description = "可跟踪的发运单来源（10.4.1.1 已交付）") List<TrackingDimension> sources,
      @Schema(description = "**未交付依赖**逐条声明：外部连接器未接入，不假装有同步任务")
          List<TrackingDimension> dependencies,
      @Schema(description = "跟踪生命周期状态机") List<TrackingTransition> statusTransitions,
      @Schema(description = "跟踪阶段状态机（含推进顺序）") List<TrackingStageTransition> stageTransitions,
      @Schema(description = "清关状态词表") List<TrackingOption> customsStatuses,
      @Schema(description = "事实来源词表") List<TrackingOption> sources2,
      @Schema(description = "预警档位词表") List<TrackingOption> delayLevels,
      @Schema(description = "本模块必须自己讲清楚的口径") List<String> notes) {}

  /** 维度接入情况（可用 / 不可用 + 原因 + 归属模块 + 路由）。 */
  @Schema(name = "TrackingDimension")
  public record TrackingDimension(
      @Schema(description = "维度编码") String code,
      @Schema(description = "中文名") String label,
      @Schema(description = "是否可用") boolean available,
      @Schema(description = "归属模块") String ownerModule,
      @Schema(description = "对应路由") String href,
      @Schema(description = "为什么可用/不可用") String note) {}

  @Schema(name = "TrackingTransition")
  public record TrackingTransition(
      @Schema(description = "当前状态") String from,
      @Schema(description = "可到达状态") String to,
      @Schema(description = "该转换的含义") String label) {}

  @Schema(name = "TrackingStageTransition")
  public record TrackingStageTransition(
      @Schema(description = "当前阶段") String from,
      @Schema(description = "可到达阶段") String to,
      @Schema(description = "该转换的含义") String label) {}

  @Schema(name = "TrackingOption")
  public record TrackingOption(
      @Schema(description = "编码") String code, @Schema(description = "中文名") String label) {}

  /** 在途跟踪查询条件。 */
  public record TrackingQuery(
      List<String> status,
      List<String> stage,
      List<String> customsStatus,
      List<String> delayLevel,
      UUID shipmentId,
      UUID destinationWarehouseId,
      String destinationCountryCode,
      String transportMode,
      /** 只要还在途且已过预计到港的（漏斗里最常用的那一刀）。 */
      Boolean delayedOnly,
      String keyword,
      String sort,
      int page,
      int size) {}

  /** 在途看板行 + 全量汇总（合计不受分页影响）。 */
  @Schema(name = "TransitTrackPage")
  public record TransitTrackPage(
      List<TrackingRow> rows,
      long total,
      @Schema(description = "全量在途数（不受分页影响）") long trackingTotal,
      @Schema(description = "全量 WATCH 档数") long watchCount,
      @Schema(description = "全量 WARNING 档数") long warningCount,
      @Schema(description = "全量 CRITICAL 档数") long criticalCount,
      @Schema(description = "全量已过预计到港仍未到货数") long overdueCount,
      @Schema(description = "全量清关被扣/退单数") long customsHeldCount,
      @Schema(description = "状态词表") List<TrackingOption> statuses,
      @Schema(description = "阶段词表") List<TrackingStageOption> stages,
      @Schema(description = "清关状态词表") List<TrackingOption> customsStatuses,
      @Schema(description = "预警档位词表") List<TrackingOption> delayLevels,
      @Schema(description = "目的仓下拉（含在途批数）") List<TrackingWarehouseOption> warehouses) {}

  @Schema(name = "TrackingWarehouseOption")
  public record TrackingWarehouseOption(
      UUID warehouseId, String warehouseCode, String warehouseName, long trackingCount) {}

  /** 在途看板行。 */
  @Schema(name = "TrackingRow")
  public record TrackingRow(
      UUID id,
      UUID shipmentId,
      @Schema(description = "发运单号（10.4.1.1）") String shipmentCode,
      @Schema(description = "运输方式（10.4.1.1）") String transportMode,
      @Schema(description = "承运人（10.4.1.1）") String carrierName,
      @Schema(description = "订舱号/运单号（10.4.1.1）") String bookingNo,
      @Schema(description = "目的仓 id") UUID destinationWarehouseId,
      @Schema(description = "目的仓名") String destinationWarehouseName,
      @Schema(description = "目的国") String destinationCountryCode,
      String status,
      @Schema(description = "跟踪阶段") String trackingStage,
      @Schema(description = "清关状态") String customsStatus,
      @Schema(description = "当前位置") String currentLocation,
      OffsetDateTime locationOccurredAt,
      OffsetDateTime originalEta,
      OffsetDateTime revisedEta,
      @Schema(description = "当前生效 ETA（改期后取改后值）") OffsetDateTime effectiveEta,
      @Schema(description = "ETA 被改期过几次") int etaRevisedCount,
      @Schema(description = "**剩余窗口（天）**：预计到港 − 现在，负数=已过港未到货") BigDecimal remainingDays,
      @Schema(description = "是否已过预计到港仍未到货") boolean overdue,
      @Schema(description = "本次评估算出的预警档位") String delayLevel,
      @Schema(description = "未结预警条数") long openAlertCount,
      @Schema(description = "在途腿数（7.5.1.2 名下）") long legCount,
      @Schema(description = "在途总数量（7.5.1.2 名下，本模块不持有余额）") BigDecimal inTransitQuantity,
      @Schema(description = "已了结数量（7.5.1.2 名下）") BigDecimal settledQuantity,
      OffsetDateTime receiptConfirmedAt,
      OffsetDateTime updatedAt,
      long version) {}

  @Schema(name = "TrackingStageOption")
  public record TrackingStageOption(String code, String label, int rank) {}

  // ================================================================ 详情

  /** 跟踪详情：头 + 节点事件流 + 在途腿（7.5.1.2 名下）+ 未结预警 + 合法下一步。 */
  @Schema(name = "TransitTrackDetail")
  public record TransitTrackDetail(
      TrackingRow tracking,
      List<TrackingEventRow> events,
      @Schema(description = "在途腿（真在 7.5.1.2 名下，本模块只读）") List<TrackingLegRow> legs,
      List<DelayAlertRow> alerts,
      @Schema(description = "当前状态的合法下一步") List<String> allowedNext,
      @Schema(description = "当前阶段的合法下一步") List<String> allowedNextStages,
      @Schema(description = "当前生效的阈值口径（从阈值表读出，不是代码常量）")
          EffectiveThreshold threshold) {}

  /** 当前生效的阈值：来源行 + 三档提前量 + 剩余窗口。 */
  @Schema(name = "EffectiveThreshold")
  public record EffectiveThreshold(
      UUID thresholdId,
      @Schema(description = "匹配到的阈值行；为默认行时 thresholdId 为空") boolean defaultRow,
      String transportMode,
      String countryCode,
      int watchLeadDays,
      int warningLeadDays,
      int criticalLeadDays,
      @Schema(description = "三档提前量的口径说明（随响应返回，界面不自造解释）") String basis) {}

  /** 节点事件行。 */
  @Schema(name = "TrackingEventRow")
  public record TrackingEventRow(
      UUID id,
      @Schema(description = "事件类型") String kind,
      String locationText,
      String detail,
      String fromStage,
      String toStage,
      OffsetDateTime etaBefore,
      OffsetDateTime etaAfter,
      @Schema(description = "事实来源") String sourceSystem,
      @Schema(description = "来源单号（外部来源必填）") String referenceId,
      OffsetDateTime occurredAt,
      UUID actorId) {}

  /**
   * 在途腿：读 7.5.1.2 的 {@code inventory_transfer}。
   *
   * <p>数量三个口径并排返回（发运 / 已了结 / 未达），
   * 因为「发运 100、到货 60」不并排就会被读成账丢了 40。
   */
  @Schema(name = "TrackingLegRow")
  public record TrackingLegRow(
      UUID legId,
      UUID shipmentLineId,
      int lineNo,
      String skuCode,
      String skuName,
      String legStatus,
      BigDecimal shippedQuantity,
      BigDecimal settledQuantity,
      BigDecimal remainingQuantity,
      OffsetDateTime shippedAt,
      OffsetDateTime expectedArriveAt,
      OffsetDateTime receivedAt,
      String carrierName,
      String trackingNo) {}

  // ================================================================ 预警

  /** 延误预警行。 */
  @Schema(name = "DelayAlertRow")
  public record DelayAlertRow(
      UUID id,
      UUID trackingId,
      String shipmentCode,
      String level,
      UUID thresholdId,
      int leadDays,
      @Schema(description = "剩余窗口（天），负数=已过港未到货") BigDecimal remainingDays,
      String message,
      String status,
      OffsetDateTime raisedAt,
      OffsetDateTime raisedFor,
      OffsetDateTime acknowledgedAt,
      OffsetDateTime closedAt,
      String closeReason) {}

  /** 预警清单 + 全量分档汇总。 */
  @Schema(name = "DelayAlertPage")
  public record DelayAlertPage(
      List<DelayAlertRow> rows,
      long total,
      long openCount,
      long acknowledgedCount,
      long watchCount,
      long warningCount,
      long criticalCount,
      List<TrackingOption> levels) {}

  /** 预警阈值行。 */
  @Schema(name = "DelayThresholdRow")
  public record DelayThresholdRow(
      UUID id,
      String transportMode,
      String countryCode,
      int watchLeadDays,
      int warningLeadDays,
      int criticalLeadDays,
      String notes,
      long version,
      OffsetDateTime updatedAt,
      @Schema(description = "是否当前在途批次实际命中的那一行") boolean inUse) {}

  // ================================================================ 请求体

  /** 建跟踪记录（一张发运单一条）。 */
  @Schema(name = "TrackingCreateRequest")
  public record TrackingCreateRequest(
      @Schema(description = "要跟踪的发运单（必须是**已发出**）") UUID shipmentId,
      @Schema(description = "初始位置") String currentLocation,
      OffsetDateTime locationOccurredAt,
      @Schema(description = "初始清关状态") String customsStatus,
      @Schema(description = "扣关/退单原因（HELD·REJECTED 必填）") String customsHoldReason,
      @Schema(description = "初始阶段（默认 AT_ORIGIN）") String trackingStage,
      String notes) {}

  /** 登记节点事件：位置 / 改期 / 清关 / 阶段推进 / 备注 / 异常，五合一。 */
  @Schema(name = "TrackingEventRequest")
  public record TrackingEventRequest(
      @Schema(description = "事件类型：LOCATION / ETA_REVISED / CUSTOMS / STAGE / NOTE")
          String kind,
      @Schema(description = "事实来源（默认 MANUAL）") String sourceSystem,
      @Schema(description = "来源单号：外部来源必填") String referenceId,
      String locationText,
      @Schema(description = "位置发生时间（LOCATION 必填）") OffsetDateTime locationOccurredAt,
      @Schema(description = "改期后的新预计到港（ETA_REVISED 必填）") OffsetDateTime etaAfter,
      @Schema(description = "改期原因（ETA_REVISED 必填）") String reason,
      @Schema(description = "新的清关状态（CUSTOMS 必填）") String customsStatus,
      @Schema(description = "扣关/退单原因（HELD·REJECTED 必填）") String customsHoldReason,
      @Schema(description = "推进到的阶段（STAGE 必填）") String toStage,
      @Schema(description = "事件正文") String detail,
      OffsetDateTime occurredAt) {}

  /** 目的仓收货确认：只记确认事实，入库落账归 7.5.1.2。 */
  @Schema(name = "TrackingReceiptRequest")
  public record TrackingReceiptRequest(
      @Schema(description = "目的仓收货回单号") String receiptReference,
      OffsetDateTime receivedAt,
      @Schema(description = "备注：短少/破损等差异如实写，不自动抹平") String notes) {}

  /** 关闭跟踪（货已闭环）。 */
  @Schema(name = "TrackingCloseRequest")
  public record TrackingCloseRequest(String reason) {}

  /** 预警确认 / 关闭。 */
  @Schema(name = "DelayAlertAckRequest")
  public record DelayAlertAckRequest(String note) {}

  @Schema(name = "DelayAlertCloseRequest")
  public record DelayAlertCloseRequest(
      @Schema(description = "关闭原因（必填：货到了/改期了/查明了都算）") String reason) {}

  /** 改阈值。 */
  @Schema(name = "DelayThresholdUpdateRequest")
  public record DelayThresholdUpdateRequest(
      Integer watchLeadDays, Integer warningLeadDays, Integer criticalLeadDays, String notes) {}
}