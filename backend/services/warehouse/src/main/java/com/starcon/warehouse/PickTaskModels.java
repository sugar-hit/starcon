package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 8.3.2.1 拣货任务的传输模型。
 *
 * <p>蓝图 08 卷 8.3.2.1 原文：「<b>生成与执行拣货任务：按库位顺序排序优化路径，记录实际拣货库位/批次/序列号/数量，异常（缺货、破损）上报。</b>
 * 输入：出库单明细；库存分配结果；作业人员或 PDA 扫码。输出：拣货任务与拣货明细（实际库位/批次/序列号）；缺货异常记录。」 关系段：「上游 = 8.3.1.2、7.2.1.2；下游 =
 * 8.3.3.1、7 库存（已拣未发的占用状态）。<b>拣货完不等于已发货</b>： 拣货只是把货从库位移到打包区，库存仍占用未释放。」
 *
 * <p><b>命名空间：全部 {@code PickTask*} 前缀。</b>springdoc 用 record 的<b>简单类名</b>做 {@code
 * components.schemas} 的键，重名会被<b>静默覆盖</b>：别人的字段形状被改掉、 单测全绿、只有跨服务的契约比对才红（8.4.2.1 与 8.5.2.1 车道开工前都逐个
 * grep 过）。
 *
 * <p><b>三个刻意不给默认值的地方：</b>
 *
 * <ul>
 *   <li><b>拣货完 ≠ 已发货。</b>{@link PickTaskStatus} 四态里没有「已发货」，发货是 {@code outbound_order.status}
 *       的事（8.3.4.1）。本模块只把出库单推到 「待复核」，从不碰已发货。
 *   <li><b>未拣 ≠ 没有。</b>逐行 {@code pickedQuantity} 来自实拣记录， {@code lineStatus} 由数量<b>派生</b>（V110 的
 *       BEFORE 触发器改写， 应用层写不出「拣齐了却还是待拣」的状态）。0 与「没量过」不混用： {@code onHandAtSource} 为 0 时 {@code
 *       sourceHasStock=false}。
 *   <li><b>计划 ≠ 实际。</b>计划段（{@link PickTaskLine}，含路径序号与分配库位） 与实拣（{@link
 *       PickTaskPickRow}，含<b>实际</b>库位/批次/序列号）分成两张模型。 分配是建议，现场是事实 —— 现场允许与计划不一致，否则真碰上「计划的库位
 *       已经空了」时，作业员只能被系统逼着报一个缺货。
 * </ul>
 *
 * <p><b>8.5.2.1 那两条建议的落地就在这条链上</b>：{@code nonPickableSource} 与 {@code skuLocationCount} 是与 8.5.2.1
 * 同口径的两个数（前者 = 「高频货在不可拣库位」， 后者 = 「SKU 散在多库位」），排序口径 {@code pathBasis} 随响应返回。
 */
public final class PickTaskModels {

  private PickTaskModels() {}

  /**
   * 拣货任务状态。
   *
   * <p>四态：已生成 / 拣货中 / 已完成 / 已取消。<b>没有「已发货」</b> —— 蓝图关系段 把它点名成两件事，这里在词表层就分开。
   *
   * <p>{@code COMPLETED → IN_PROGRESS} 那条重开边存在，是因为 8.3.1.1 的 {@code outbound_pick_request} 有 {@code
   * UNIQUE(tenant_id,order_id)} （一张出库单只交接一次），而它的状态机里又留着「待复核 → 拣货中」的返工边。
   * 没有重开，复核退回的订单就会永远停在「拣货中」而没有作业入口。
   */
  public enum PickTaskStatus {
    /** 已生成：路径与计划段已经排好，但还没有人接单。 */
    CREATED("已生成", false),
    /** 拣货中：已分配作业人员，可以登记实拣与异常。 */
    IN_PROGRESS("拣货中", false),
    /** 已完成：全部计划段已拣齐（或已按缺货异常登记），提交复核。出库单被推到「待复核」。 */
    COMPLETED("已完成", true),
    /** 已取消：放弃本次拣货（拣不动了）。货仍在原库位，占用不变。 */
    CANCELLED("已取消", true);

    private final String label;
    private final boolean terminal;

    PickTaskStatus(String label, boolean terminal) {
      this.label = label;
      this.terminal = terminal;
    }

    public String label() {
      return label;
    }

    /** 终态。终态任务的拣货明细不可再改（V110 的守卫），要重拣走「重开」并写原因。 */
    public boolean terminal() {
      return terminal;
    }

    /** 能不能登记实拣。只有「拣货中」可以。 */
    public boolean acceptsPicks() {
      return this == IN_PROGRESS;
    }

    /** 能不能上报异常。已完成的任务已交给下游，不能再往里塞新事实。 */
    public boolean acceptsExceptions() {
      return this == CREATED || this == IN_PROGRESS;
    }
  }

  /**
   * 拣货异常种类。
   *
   * <p>蓝图点名了缺货与破损两个；后三个是现场真实存在的「拣不出来」的原因， 每多一类都要有人写清单报出来，所以刻意收窄在五个里。
   */
  public enum PickTaskExceptionKind {
    /** 缺货：计划要 10 件，这个库位只有 7 件。必填 shortageQuantity。 */
    SHORTAGE("缺货"),
    /** 破损：货在，但发不出去（压坏、掉漆、封口破了）。 */
    DAMAGED("破损"),
    /** 序列号缺失：这一件该有序列号，但扫不出来 / 找不到号。 */
    MISSING_SERIAL("序列号缺失"),
    /** 错位：货不在计划的那个库位，且该处不足（要换地方取）。 */
    WRONG_LOCATION("错位"),
    /** 其它：必须写 detail 说清楚。 */
    OTHER("其它");

    private final String label;

    PickTaskExceptionKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 异常处置结论。系统不推，由人写。 */
  public enum PickTaskResolution {
    /** 换货：从别的库位补齐了。 */
    REPLACED("换货"),
    /** 拆单：这件单独走别的单据。 */
    SPLIT("拆单"),
    /** 报损：按损耗处理。 */
    WRITTEN_OFF("报损"),
    /** 忽略：确认不影响发货。 */
    IGNORED("忽略");

    private final String label;

    PickTaskResolution(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 作业留痕的事件类型，与 V110 的 CHECK 逐字一致。 */
  public enum PickTaskEventType {
    GENERATED("生成"),
    ASSIGNED("接单"),
    PICKED("实拣"),
    EXCEPTION_REPORTED("异常上报"),
    REOPENED("重开"),
    COMPLETED("完成"),
    CANCELLED("取消");

    private final String label;

    PickTaskEventType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 拣货明细行的三态。**由数量派生**，应用层写不出第四种组合。 */
  public enum PickTaskLineStatus {
    /** 待拣：一件都没拣。 */
    PENDING("待拣"),
    /** 拣齐：本段实拣 == 计划。 */
    PICKED("拣齐"),
    /** 拣不齐：拣了一部分（> 0 且 < 计划）。 */
    SHORT("拣不齐");

    private final String label;

    PickTaskLineStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ------------------------------------------------------------ 入参

  /**
   * 生成拣货任务入参。
   *
   * <p>{@code outboundOrderId} 是唯一的业务入口：任务从 8.3.1.1 写下的 {@code outbound_pick_request} 接手（V72 §3
   * 明写「8.3.2.1 交付后按 (tenant_id, order_id) 读这张表接手」）。没有交接请求就没有任务 —— 凭空生成一张拣货任务在账上说不通：拣什么？按哪个计划？
   *
   * @param outboundOrderId 出库单 id（本租户）
   * @param stagingLocationId 打包区（发货暂存）库位。<b>可空</b>：不给时按 「本仓唯一一个 ACTIVE 且 type=SHIPPING
   *     的库位」解析；一个都没有就 <b>报错并说清原因</b>，绝不随便挑一个库位 —— 拣完的货没有明确去处 就等于没搬。
   */
  public record PickTaskGenerateInput(
      UUID outboundOrderId,
      @Schema(nullable = true) UUID stagingLocationId,
      @Schema(nullable = true) String note) {}

  /** 接单 / 分配作业人员。PDA 扫码接单走同一条动作。 */
  public record PickTaskAssignInput(UUID assigneeUserId, @Schema(nullable = true) String note) {}

  /**
   * 登记一次实拣。
   *
   * @param lineId 本次拣的是哪一段（{@link PickTaskLine#id()}）
   * @param locationId <b>实际</b>取货库位。允许与计划的 sourceLocationId 不同： 分配是建议，现场是事实。系统只校验它在本仓、且该处真有这件货。
   * @param lotId <b>实际</b>批次，可空（非批次管理品）
   * @param quantity 本次拣走数量（NUMERIC(19,6)，禁浮点）
   * @param serialIds 序列号 id 列表。<b>给了就必须逐个登记</b>，且每一个都要 在本仓、在库、绑定的 SKU 与本段一致、且确实在 {@code locationId}
   *     上 （V110 的 {@code guard_pick_task_serial_fn} 四项都拒）。 数量 > 1 时给序列号列表就是「逐件核对」的开始。
   * @param pickedAt 作业时刻（PDA 时钟），不是 DB 的 now()
   * @param note 现场备注
   */
  public record PickTaskPickInput(
      UUID lineId,
      UUID locationId,
      @Schema(nullable = true) UUID lotId,
      BigDecimal quantity,
      @Schema(nullable = true) List<UUID> serialIds,
      @Schema(nullable = true) String pickedAt,
      @Schema(nullable = true) String note) {}

  /**
   * 上报一条异常。
   *
   * @param kind 缺货 / 破损 / 序列号缺失 / 错位 / 其它
   * @param shortageQuantity 缺货数量。<b>只有 SHORTAGE 必填且必须 &gt; 0</b>； 报一个「缺货」而不写缺几件，仓库只知道要补货不知道要补多少。
   *     其余四类<b>不许</b>填（V110 的 CHECK 直接拒），否则两个数会对不上。
   * @param locationId 异常发生在哪个库位
   * @param detail 一句人读得懂的话（必填）
   * @param reportedAt 上报时刻
   */
  public record PickTaskExceptionInput(
      String kind,
      @Schema(nullable = true) UUID lineId,
      @Schema(nullable = true) BigDecimal shortageQuantity,
      @Schema(nullable = true) UUID locationId,
      String detail,
      @Schema(nullable = true) String reportedAt) {}

  /**
   * 异常处置。
   *
   * <p>处置结论<b>只能写一次</b>（V110 的守卫）—— 异常处置是审计对象， 「当时判定换货、后来又改成忽略」会让缺件责任无解。
   */
  public record PickTaskResolveInput(String resolution, String note) {}

  /** 取消拣货任务。必填原因。 */
  public record PickTaskCancelInput(String reason) {}

  /** 重开（复核返工）。必填原因，且出库单必须已被退回「拣货中」。 */
  public record PickTaskReopenInput(String reason, @Schema(nullable = true) UUID assigneeUserId) {}

  // ------------------------------------------------------------ 输出

  /** 生成任务的结果。 */
  public record PickTaskCreated(
      UUID id,
      String taskCode,
      String status,
      UUID outboundOrderId,
      String outboundOrderCode,
      UUID warehouseId,
      UUID stagingLocationId,
      @Schema(nullable = true) String stagingLocationCode,
      int lineCount,
      BigDecimal plannedQuantity,
      int nonPickableLineCount,
      String pathBasis,
      String message) {}

  /**
   * 列表里的一行。
   *
   * @param plannedQuantity 计划件数合计（触发器维护的唯一写入者）
   * @param pickedQuantity 实拣件数合计
   * @param progress 拣齐进度 0..1。给 0..1 的比例而不是百分比字符串 —— 前端自己乘 100 并处理「还没开始」那一档
   * @param fullyPicked <b>是否每一段都拣齐</b>。这是「能不能提交复核」的判据， 与「拣了多少件」不是一回事（拣了 9/10 件也算有进度，但拣不齐）
   */
  public record PickTaskRow(
      UUID id,
      String taskCode,
      String status,
      @Schema(nullable = true) String statusLabel,
      UUID outboundOrderId,
      String outboundOrderCode,
      UUID warehouseId,
      UUID stagingLocationId,
      @Schema(nullable = true) String stagingLocationCode,
      @Schema(nullable = true) UUID assigneeUserId,
      @Schema(nullable = true) String assigneeName,
      int plannedLineCount,
      BigDecimal plannedQuantity,
      int pickedLineCount,
      BigDecimal pickedQuantity,
      int exceptionCount,
      int nonPickableLineCount,
      @Schema(nullable = true) BigDecimal progress,
      boolean fullyPicked,
      boolean hasUnresolvedException,
      int reopenCount,
      @Schema(nullable = true) OffsetDateTime startedAt,
      @Schema(nullable = true) OffsetDateTime completedAt,
      @Schema(nullable = true) OffsetDateTime createdAt) {}

  /** 分页 + 汇总。 */
  public record PickTaskPage(
      List<PickTaskRow> items, int page, int size, long total, PickTaskSummary summary) {}

  /**
   * 列表汇总。
   *
   * <p>与 {@code LocationUtilizationTotals} 同一纪律：<b>汇总不受筛选影响</b>， 所以筛完只看得到一部分行时，顶部合计仍是全仓真相。
   *
   * @param byStatus 逐状态计数
   * @param nonPickableTaskCount 有「只能从不可拣库位取」行的任务数 —— 这是 8.5.2.1「高频货在不可拣库位」那条建议在作业侧的读数
   * @param dispersionTaskCount 有「SKU 散在多库位」行的任务数 —— 同理，对应「SKU 散在多库位」
   */
  public record PickTaskSummary(
      long totalCount,
      BigDecimal totalPlannedQuantity,
      BigDecimal totalPickedQuantity,
      long nonPickableTaskCount,
      long dispersionTaskCount,
      long openExceptionCount,
      Map<String, Long> byStatus) {}

  /**
   * 拣货明细的一段（路径上的一站）。
   *
   * @param sequenceNo 路径序号。<b>拣货单照它打印，作业员照它走</b> —— 这是「按库位顺序排序优化路径」的落点
   * @param onHandAtSource 分配时该库位该批次的手数快照。<b>按在手而不是可用</b>： 拣货要取的正是已为这张单预占住的那批货（理由写在 V110 该列的注释里）
   * @param sourceHasStock {@code onHandAtSource = 0} 时为 false：这一行<b>没有可分配
   *     的库存</b>（整个仓里一个可拣库位都没有这件货）。这个区别不能省 —— 「量出来是 0」与「没量过」在作业台上是两件事（与 8.5.2.1 的 {@code
   *     capacityMeasured} 同一纪律）
   * @param skuLocationCount 该 SKU 在本仓可拣且 ACTIVE 的库位上散在几处。 {@code > 1} 即 8.5.2.1 的「SKU
   *     散在多库位」，意味着一次拣货要多走
   * @param nonPickableSource 只能退到不可拣库位取货时为 true。 对应 8.5.2.1 的「高频货在不可拣库位」，作业台必须把它顶到最前面提醒
   * @param shortfall 该段还差多少件（= 计划 − 实拣，恒 ≥ 0）
   */
  public record PickTaskLine(
      UUID id,
      int sequenceNo,
      UUID outboundLineId,
      UUID skuId,
      String skuCode,
      String skuName,
      UUID qualityStatusId,
      UUID sourceLocationId,
      String sourceLocationCode,
      @Schema(nullable = true) UUID sourceLotId,
      @Schema(nullable = true) String sourceLotCode,
      String locationPath,
      int locationDepth,
      boolean sourcePickable,
      boolean sourceActive,
      BigDecimal onHandAtSource,
      boolean sourceHasStock,
      int skuLocationCount,
      boolean nonPickableSource,
      BigDecimal plannedQuantity,
      BigDecimal pickedQuantity,
      BigDecimal shortfall,
      PickTaskLineStatus lineStatus,
      @Schema(nullable = true) String lineStatusLabel) {}

  /** 一次实拣。location/lot 是**实际**的，可能与计划不同。 */
  public record PickTaskPickRow(
      UUID id,
      UUID lineId,
      UUID locationId,
      String locationCode,
      @Schema(nullable = true) UUID lotId,
      @Schema(nullable = true) String lotCode,
      BigDecimal quantity,
      int serialCount,
      UUID pickedBy,
      OffsetDateTime pickedAt,
      String note,
      List<PickTaskSerialRow> serials) {}

  /** 实拣的序列号逐个。 */
  public record PickTaskSerialRow(UUID serialId, String serialNumber, OffsetDateTime pickedAt) {}

  /** 一条异常。 */
  public record PickTaskExceptionRow(
      UUID id,
      String kind,
      @Schema(nullable = true) String kindLabel,
      @Schema(nullable = true) UUID lineId,
      @Schema(nullable = true) String skuCode,
      @Schema(nullable = true) BigDecimal shortageQuantity,
      @Schema(nullable = true) UUID locationId,
      @Schema(nullable = true) String locationCode,
      String detail,
      UUID reportedBy,
      OffsetDateTime reportedAt,
      @Schema(nullable = true) String resolution,
      @Schema(nullable = true) UUID resolvedBy,
      @Schema(nullable = true) OffsetDateTime resolvedAt,
      @Schema(nullable = true) String resolveNote,
      boolean resolved) {}

  /** 作业留痕一行。 */
  public record PickTaskEventRow(
      UUID id,
      String eventType,
      @Schema(nullable = true) String eventTypeLabel,
      @Schema(nullable = true) String fromStatus,
      @Schema(nullable = true) String toStatus,
      UUID actorUserId,
      OffsetDateTime occurredAt,
      String detail) {}

  /**
   * 详情 = 单头 + 路径计划段 + 实拣 + 异常 + 时间线。
   *
   * <p>{@code pathReport} 单独一段：路径优化的依据（按库位树层级路径排序、 同 SKU 收敛到尽量少的库位、优先可拣库位）必须能被看见，而不只是 「顺序看起来挺顺」。
   */
  public record PickTaskDetail(
      PickTaskRow task,
      UUID outboundOrderId,
      String outboundOrderCode,
      String outboundOrderStatus,
      @Schema(nullable = true) String outboundOrderStatusLabel,
      List<PickTaskLine> lines,
      List<PickTaskPickRow> picks,
      List<PickTaskExceptionRow> exceptions,
      List<PickTaskEventRow> events,
      PickTaskPathReport pathReport,
      String note) {}

  /**
   * 路径优化报告。
   *
   * @param basis 排序口径原文，随响应返回（不靠文档口口相传）
   * @param distinctLocationCount 要走几个不同库位
   * @param dispersionSkuCount 有几行的 SKU 散在多库位（8.5.2.1 SKU_DISPERSION 的读数）
   * @param nonPickableLineCount 有几行只能退到不可拣库位 （8.5.2.1 HIGH_FREQUENT_NON_PICKABLE 的读数）
   * @param walks 需要走几趟（= 有实拣的库位数）。「拣一次拿齐」的目标就是把 它压到最小
   */
  public record PickTaskPathReport(
      String basis,
      int totalLineCount,
      int distinctLocationCount,
      int nonPickableLineCount,
      int dispersionSkuCount,
      int splitLineCount,
      List<PickTaskPathStop> stops,
      String note) {}

  /** 路径上的一站（同一库位的多段合并成一次停靠）。 */
  public record PickTaskPathStop(
      UUID locationId,
      String locationCode,
      String locationPath,
      int depth,
      boolean pickable,
      int lineCount,
      BigDecimal plannedQuantity,
      int sequenceFrom,
      int sequenceTo) {}

  /** 能力声明。 */
  public record PickTaskCapabilities(
      String refType,
      boolean pathOptimizationLive,
      boolean actualLocationCapture,
      boolean serialCapture,
      boolean exceptionReporting,
      boolean inventoryMovementLive,
      boolean balanceUnchangedByPicking,
      boolean reservationRetained,
      boolean waveAvailable,
      @Schema(nullable = true) String waveOwnerModule,
      boolean recheckAvailable,
      @Schema(nullable = true) String recheckOwnerModule,
      List<PickTaskUpstreamModule> upstreams,
      List<PickTaskDownstreamModule> downstreams,
      List<String> statuses,
      List<String> exceptionKinds,
      List<String> sorts,
      String pathBasis,
      String inventoryNote,
      String note) {}

  /** 上游模块。 */
  public record PickTaskUpstreamModule(
      String id, String name, String service, boolean available, String contract) {}

  /** 下游模块。 */
  public record PickTaskDownstreamModule(
      String id, String name, String service, boolean available, String contract) {}
}
