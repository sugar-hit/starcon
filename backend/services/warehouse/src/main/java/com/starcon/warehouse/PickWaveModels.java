package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 8.3.2.2 波次与合并拣货的传输模型。
 *
 * <p>蓝图 08 卷 8.3.2.2 原文：「<b>把多张出库单按规则合并成拣货波次，提高拣货效率： 按承运商/库区/订单数合并，支持波次进度跟踪。</b>」
 * 输入「出库单集合；合并规则（承运渠道、库区、时段）。」输出「波次任务 + 各出库单在波次中的位置。」 关系段：「上游 = 8.3.2.1；下游 =
 * 8.3.3.1。<b>合并只影响拣货效率，不改变订单粒度</b>： 波次完成后仍需按订单拆分复核与打包。」
 *
 * <p><b>命名空间：全部 {@code PickWave*} 前缀。</b>springdoc 用 record 的<b>简单类名</b>做 {@code
 * components.schemas} 的键，重名会被静默覆盖（别人的字段形状被改掉、单测全绿、 只有跨服务契约比对才红）。开工前已 grep 六个服务与四个 library：全仓没有任何
 * {@code Wave*} record，所以 {@code PickWave} 前缀还多留了一层余量。
 *
 * <p><b>三个刻意不给默认值的地方</b>：
 *
 * <ul>
 *   <li><b>合并不改订单粒度。</b>{@link PickWaveMemberRow} 一行就是<b>一张出库单的一张 拣货任务</b>，波次从不把它们揉成一条记录。{@code
 *       PickWaveCapabilities .taskOrderGranularityUnchanged} 把这句话也变成了契约里可断言的字段。
 *   <li><b>库区没有默认值。</b>库区是从取货库位沿 V105 库位树上行得到的顶层节点， <b>横跨多个区的拣货任务没有单一库区</b>（{@code
 *       crossZone=true}）， 只能进「库区不限」的规则 —— 绝不被硬塞进某一个区。
 *   <li><b>进度分母为 0 不给百分比。</b>{@code progress} 为 null 表示「计划量还没有」， 显示 0% 会把「没量过」说成「一件没拣」。
 * </ul>
 */
public final class PickWaveModels {

  private PickWaveModels() {}

  /**
   * 波次状态。
   *
   * <p>四态：已排波 / 拣货中 / 已完成 / 已取消。<b>没有「已发货」</b> —— 与 8.3.2.1 同一
   * 纪律：波次结束只表示这一组拣货任务都拣完了，出库单各自进「待复核」（8.3.3.1）， 发货仍是 8.3.4.1 的事。
   */
  public enum PickWaveStatus {
    /** 已排波：合并已完成、成员已定，还没放行。 */
    PLANNED("已排波", false),
    /** 拣货中：已放行、已有负责人，成员在逐张推进。 */
    PICKING("拣货中", false),
    /** 已完成：全部成员拣货任务都已终态。合并到此为止，出库单各自去复核。 */
    COMPLETED("已完成", true),
    /** 已取消：放弃这一波。货仍在原库位、预占不变，成员被放回候选池。 */
    CANCELLED("已取消", true);

    private final String label;
    private final boolean terminal;

    PickWaveStatus(String label, boolean terminal) {
      this.label = label;
      this.terminal = terminal;
    }

    public String label() {
      return label;
    }

    public boolean terminal() {
      return terminal;
    }

    /** 只有「已排波」能放行 / 移出成员。开始走之后就不许再改排班。 */
    public boolean acceptsRelease() {
      return this == PLANNED;
    }

    /** 只有未完态能取消。 */
    public boolean acceptsCancel() {
      return this == PLANNED || this == PICKING;
    }
  }

  /** 波次流水事件类型。append-only（V113 的守卫直接拒改删）。 */
  public enum PickWaveEventType {
    PLANNED("排波"),
    RELEASED("放行"),
    TASK_ATTACHED("并入工单"),
    TASK_DETACHED("移出工单"),
    COMPLETED("完成"),
    CANCELLED("取消");

    private final String label;

    PickWaveEventType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ------------------------------------------------------------ 入参

  /**
   * 新建合并规则。
   *
   * @param ruleCode 规则号，租户内唯一
   * @param carrierMatch 承运渠道。{@code ""} 或不给 = <b>不限</b>（一等公民的选项， 不是「没填」）
   * @param zoneId 库区。不给 = <b>不限</b>
   * @param timeSlotStart 时段起始（仓本地小时，0–23）。不给 = 0
   * @param timeSlotEnd 时段结束（仓本地小时，左闭右开，1–24）。不给 = 24（整天）
   * @param maxOrderCount 订单数上限（蓝图点名的第三个合并维度），必须 &gt; 0
   */
  public record PickWaveRuleInput(
      String ruleCode,
      String ruleName,
      UUID warehouseId,
      @Schema(nullable = true) String carrierMatch,
      @Schema(nullable = true) UUID zoneId,
      @Schema(nullable = true) Integer timeSlotStart,
      @Schema(nullable = true) Integer timeSlotEnd,
      @Schema(nullable = true) Integer maxOrderCount,
      @Schema(nullable = true) Integer priority) {}

  /** 停用合并规则。必填原因：规则一停，一批在等的出库单就换了归并口径。 */
  public record PickWaveRuleRetireInput(String reason) {}

  /**
   * 建波次。
   *
   * <p><b>两种建法，二选一</b>（{@code ruleId} 与 {@code pickTaskIds} 都给时以 {@code ruleId} 为准，并按 {@code
   * carrierCode}/{@code zoneId} 收窄到<b>一个</b> 合并键 —— 波次永远是单键的：同仓、同库区、同承运渠道）。
   *
   * @param ruleId 按规则自动挑候选（可空）
   * @param carrierCode 指定合并键的承运渠道（可空；给了就只取这一组）
   * @param zoneId 指定合并键的库区（可空；给了就只取这一组）
   * @param pickTaskIds 手工指定成员（可空；给了就必须同仓、同承运渠道，且都不在别的 未完波次里）
   * @param plannedPickAt 计划拣货时刻。不给时：按规则从<b>仓本地</b>时段的起始小时 推出今天那一刻；没有规则时取当前时刻 —— 两者都是真算出来的，不是占位值
   */
  public record PickWaveCreateInput(
      UUID warehouseId,
      @Schema(nullable = true) UUID ruleId,
      @Schema(nullable = true) String carrierCode,
      @Schema(nullable = true) UUID zoneId,
      @Schema(nullable = true) List<UUID> pickTaskIds,
      @Schema(nullable = true) String plannedPickAt,
      @Schema(nullable = true) String note) {}

  /** 放行开波。{@code assigneeUserId} 必填：放行之后必须有人对这一波负责。 */
  public record PickWaveReleaseInput(UUID assigneeUserId, @Schema(nullable = true) String note) {}

  /** 完成波次。可空备注。 */
  public record PickWaveCompleteInput(@Schema(nullable = true) String note) {}

  /** 取消波次。必填原因。 */
  public record PickWaveCancelInput(String reason) {}

  // ------------------------------------------------------------ 输出

  /**
   * 合并规则一行。
   *
   * @param waitingOrderCount 当前<b>归这条规则管、但因为已有同键未完波次而进不去</b>的 出库单数。这个数不为 0
   *     就要有人去处理：订单数上限撞顶之后剩下的单会一直等， 界面上不报出来就只剩一个解释不通的空白。
   * @param activeTaskCount 当前归这条规则管的候选拣货任务数
   */
  public record PickWaveRuleRow(
      UUID id,
      String ruleCode,
      String ruleName,
      UUID warehouseId,
      String carrierMatch,
      boolean carrierUnbounded,
      @Schema(nullable = true) UUID zoneId,
      @Schema(nullable = true) String zoneCode,
      boolean zoneUnbounded,
      int timeSlotStart,
      int timeSlotEnd,
      String slotLabel,
      int maxOrderCount,
      int priority,
      String status,
      @Schema(nullable = true) OffsetDateTime retiredAt,
      @Schema(nullable = true) String retireReason,
      int activeTaskCount,
      int waitingOrderCount,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 建波次的结果。 */
  public record PickWaveCreated(
      UUID id,
      String waveCode,
      String status,
      UUID warehouseId,
      String carrierCode,
      @Schema(nullable = true) UUID zoneId,
      @Schema(nullable = true) String zoneCode,
      @Schema(nullable = true) UUID ruleId,
      @Schema(nullable = true) String ruleCode,
      int memberCount,
      int orderCount,
      OffsetDateTime plannedPickAt,
      String mergeBasis,
      int waitingCount,
      String message) {}

  /** 规则清单。 */
  public record PickWaveRuleList(List<PickWaveRuleRow> items, int total, String note) {}

  /**
   * 一张可合并的出库单（= 一张 8.3.2.1 拣货任务）。
   *
   * @param crossZone 该任务的拣货段<b>横跨多个区</b>：它没有单一库区，只能进「库区不限」 的规则。硬塞进某一个区会让「按库区合并」这个口径失真。
   * @param localHour 出库单创建时刻在<b>仓本地</b>的小时（0–23），时段匹配就用它
   * @param matchedRuleId 命中的规则（null = 没有任何 ACTIVE 规则认领它）
   * @param blocked 是否被挡住（已在别的未完波次里）
   * @param blockedReason 挡住的原因原文
   */
  public record PickWaveCandidateRow(
      UUID pickTaskId,
      String taskCode,
      UUID outboundOrderId,
      String outboundOrderCode,
      String carrierCode,
      @Schema(nullable = true) UUID zoneId,
      @Schema(nullable = true) String zoneCode,
      boolean crossZone,
      int plannedLineCount,
      BigDecimal plannedQuantity,
      OffsetDateTime orderCreatedAt,
      int localHour,
      @Schema(nullable = true) UUID matchedRuleId,
      @Schema(nullable = true) String matchedRuleCode,
      @Schema(nullable = true) String matchedRuleName,
      boolean blocked,
      @Schema(nullable = true) String blockedReason) {}

  /**
   * 一个合并键（承运渠道 × 库区）的候选组。
   *
   * <p>波次永远是单键的（V113 的 {@code pick_wave_open_key} 部分唯一索引）， 所以界面按组显示而不是一张混排的清单 —— 一屏里混着四个承运商的订单，
   * 看的人无法判断「现在该开哪一波」。
   */
  public record PickWaveCandidateGroup(
      String carrierCode,
      @Schema(nullable = true) UUID zoneId,
      @Schema(nullable = true) String zoneCode,
      @Schema(nullable = true) UUID ruleId,
      @Schema(nullable = true) String ruleCode,
      @Schema(nullable = true) String ruleName,
      int maxOrderCount,
      int eligibleCount,
      int waitingCount,
      int unruledCount,
      @Schema(nullable = true) UUID openWaveId,
      @Schema(nullable = true) String openWaveCode,
      List<PickWaveCandidateRow> candidates) {}

  /** 候选清单。 */
  public record PickWaveCandidatePage(
      List<PickWaveCandidateGroup> groups,
      int totalEligible,
      int totalWaiting,
      String timezone,
      String note) {}

  /**
   * 波次进度。<b>全部由查询时的一次聚合算出来</b>（{@code pick_wave_task ⋈ pick_task}）， 不是一个存在单头上的数 —— 见 V113 文件头
   * §3：明细的进度住在 8.3.2.1 的表里， 在本表挂一个「工单变了就刷波次」的触发器等于去 ALTER 别人的表。
   *
   * @param progress 0..1；{@code plannedLineCount=0} 时为 null（「没量过」不是「0%」）
   * @param fullyPicked 每一张成员工单都拣齐 —— 与「拣了多少件」不是一回事
   */
  public record PickWaveProgress(
      int orderCount,
      int taskCount,
      int plannedLineCount,
      BigDecimal plannedQuantity,
      int pickedLineCount,
      BigDecimal pickedQuantity,
      int terminalTaskCount,
      @Schema(nullable = true) BigDecimal progress,
      boolean fullyPicked) {}

  /**
   * 列表里的一行。
   *
   * @param waveCode 波次号（服务端派生，不接受调用方自填）
   * @param progress 同 {@link PickWaveProgress#progress()}
   * @param message 状态提示原文（「还有 N 张在拣」之类），随响应返回而不是让前端自己编
   */
  public record PickWaveRow(
      UUID id,
      String waveCode,
      String status,
      String statusLabel,
      UUID warehouseId,
      String carrierCode,
      @Schema(nullable = true) UUID zoneId,
      @Schema(nullable = true) String zoneCode,
      @Schema(nullable = true) UUID ruleId,
      @Schema(nullable = true) String ruleCode,
      @Schema(nullable = true) UUID assigneeUserId,
      OffsetDateTime plannedPickAt,
      @Schema(nullable = true) OffsetDateTime startedAt,
      @Schema(nullable = true) OffsetDateTime completedAt,
      @Schema(nullable = true) OffsetDateTime cancelledAt,
      @Schema(nullable = true) String cancelReason,
      PickWaveProgress progress,
      String mergeBasis,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      String message) {}

  /** 汇总。 */
  public record PickWaveSummary(
      int plannedCount,
      int pickingCount,
      int completedCount,
      int cancelledCount,
      int openOrderCount,
      BigDecimal plannedQuantity,
      BigDecimal pickedQuantity) {}

  /** 分页页。 */
  public record PickWavePage(
      List<PickWaveRow> items, int page, int size, long total, PickWaveSummary summary) {}

  /**
   * 成员一行 = <b>各出库单在波次中的位置</b>（蓝图对「输出」的原话）。
   *
   * @param position 波次内的位置，1 起连续（V113 的 refresh 触发器保证不留洞）
   * @param taskStatus 该出库单<b>自己的</b>拣货任务状态 —— 合并不替它做主
   */
  public record PickWaveMemberRow(
      int position,
      UUID pickTaskId,
      String taskCode,
      String taskStatus,
      String taskStatusLabel,
      UUID outboundOrderId,
      String outboundOrderCode,
      String outboundOrderStatus,
      String carrierCode,
      @Schema(nullable = true) String zoneCode,
      int plannedLineCount,
      BigDecimal plannedQuantity,
      int pickedLineCount,
      BigDecimal pickedQuantity,
      @Schema(nullable = true) BigDecimal taskProgress,
      boolean fullyPicked,
      OffsetDateTime plannedPickAt) {}

  /** 合并报告。 */
  public record PickWaveReport(
      String mergeBasis,
      String dimensionSummary,
      int ruleOrderCap,
      int detachedTaskCount,
      String orderGranularityNote,
      String note) {}

  /** 流水一行。 */
  public record PickWaveEventRow(
      UUID id,
      String eventType,
      String eventLabel,
      @Schema(nullable = true) String fromStatus,
      @Schema(nullable = true) String toStatus,
      UUID actorUserId,
      OffsetDateTime occurredAt,
      String detail) {}

  /** 详情 = 单头 + 成员（按位置） + 流水 + 合并报告。 */
  public record PickWaveDetail(
      PickWaveRow wave,
      List<PickWaveMemberRow> members,
      List<PickWaveEventRow> events,
      PickWaveReport report,
      String note) {}

  /** 上游模块。 */
  public record PickWaveUpstreamModule(
      String id, String name, String service, boolean available, String contract) {}

  /** 下游模块。 */
  public record PickWaveDownstreamModule(
      String id, String name, String service, boolean available, String contract) {}

  /**
   * 能力声明。
   *
   * <p>存在的理由与既有 warehouse 模块一致：把「哪些输入真有依据」「哪条不能填默认值」 显式报出来，而不是让调用方从 400 或空数组里猜。
   */
  public record PickWaveCapabilities(
      String refType,
      boolean carrierMergeLive,
      boolean zoneMergeLive,
      boolean zoneDerivedFromLocationTree,
      boolean timeSlotMergeLive,
      boolean orderCountCapEnforced,
      boolean ruleConfigurable,
      boolean oneOpenWavePerMergeKey,
      boolean oneOpenWavePerTask,
      boolean waveProgressDerived,
      boolean taskOrderGranularityUnchanged,
      boolean inventoryUnaffected,
      @Schema(nullable = true) String inventoryOwnerModule,
      List<PickWaveUpstreamModule> upstreams,
      List<PickWaveDownstreamModule> downstreams,
      List<String> statuses,
      List<String> eventTypes,
      List<String> sorts,
      String mergeBasis,
      String timeSlotBasis,
      String zoneBasis,
      String inventoryNote,
      String note) {}
}
