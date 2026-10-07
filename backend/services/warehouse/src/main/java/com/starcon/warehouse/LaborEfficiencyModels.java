package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 8.5.1.1 作业效率（人效）的传输模型。
 *
 * <p>蓝图原话：「统计仓库人效：人均日拣货件数/单量、拣货行走距离、打包时效、复核准确率、
 * 差错率，按人按日统计。」输入：「作业任务记录（操作人、时间、类型）；人员配置。」
 * 输出：「人效报表 + 排名 + 趋势。」上游 = 8.3 全部作业记录；下游 = 17 绩效。
 * 以及本模块被点名的红线：
 * 「<b>人效数据用于提成核算与排班，需明确口径避免争议</b>（ERP 人效报表是绩效争议高发区）。」
 *
 * <p><b>本模块自己拥有「作业记录」这张事实表</b>（{@code warehouse_work_task} /
 * {@code warehouse_work_event}），理由与交接方式见 V74 迁移注释：要害是
 *
 * <ul>
 *   <li>蓝图的上游「8.3 全部作业记录」在集成头里整块未交付（33 张表里没有任何出库/拣货/
 *       复核/打包/发货表），所以人效的事实由本模块落地，并配真实的写入端点，
 *       而不是等上游；
 *   <li>本表记的是**人效粒度**（谁、什么时候、做了多少、走了多远、错了几处），
 *       8.3 记的是**作业单据粒度**（要拣哪些货）。两者正交，粒度不同，因此不是同一张表；
 *   <li>本车道是这两张表的<b>唯一写入所有者</b>。8.3 各车道落地后把作业事件投递到本服务的
 *       端点（{@code sourceModule} 标明来源），而不是直接写表 ——
 *       「每张事实表只有一个写入所有者」在这里是可执行的边界，不是口号。
 * </ul>
 *
 * <p><b>五个指标的口径逐字写在这里、写在 SQL 注释里、也写进每个报表响应的
 * {@code metricBasis} 字段</b>。理由是蓝图那句「需明确口径避免争议」：
 * 口径只活在代码注释里，争议时没有人能查；口径进了响应体，前端能把它显示给用户，
 * 绩效面谈时双方看的是同一段字。
 */
public final class LaborEfficiencyModels {

  private LaborEfficiencyModels() {}

  /** 人效统计的时区口径。固定 Asia/Shanghai，不跟服务器本地时区走。 */
  public static final String TIMEZONE = "Asia/Shanghai";

  // ------------------------------------------------------------ 枚举

  /**
   * 作业类型：只三种，因为蓝图 8.5.1.1 的五个指标只覆盖这三种。
   *
   * <p>发货（8.3.4）另有面单与打印次数口径，不在本模块；枚举里不留没人会写的值 ——
   * 预留值会让「{@code SHIPPING} 的人效在哪」变成永远查不出答案的幽灵分支。
   */
  public enum LaborWorkType {
    PICKING("拣货", "拣货件数/单量/行走距离三项指标都来自这一类"),
    PACKING("打包", "打包时效来自这一类；异常终止的任务不计入时效"),
    REVIEW("复核", "复核准确率来自这一类；差错率按件数口径记在发现差错的那张任务上");

    private final String label;
    private final String description;

    LaborWorkType(String label, String description) {
      this.label = label;
      this.description = description;
    }

    public String label() {
      return label;
    }

    public String description() {
      return description;
    }
  }

  /**
   * 作业任务状态机：PENDING → RUNNING → COMPLETED / EXCEPTION。
   *
   * <p>{@link #COMPLETED} 与 {@link #EXCEPTION} 都是<b>定稿</b>：进入之后计量列不可再改
   * （V74 触发器钉死），纠错只能靠另报异常留痕，不能擦掉重写。
   */
  public enum WorkTaskStatus {
    PENDING("待开工"),
    RUNNING("作业中"),
    COMPLETED("已完成"),
    EXCEPTION("异常终止");

    private final String label;

    WorkTaskStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 异常类型。
   *
   * <p>与<b>差错</b>严格分开：差错是「做错了」（拣错、漏拣、打错），进差错率；
   * 异常是「做不完 / 做不了」，进异常而不进差错率。把两者混在一起，
   * 遇上缺货的人会被算成错得最离谱 —— 这是排班时最不公平的一种算法。
   */
  public enum LaborExceptionCode {
    MISSING_STOCK("缺货", "货不在货位或账面有实物无，人做不下去"),
    EQUIPMENT_FAILURE("设备故障", "手持机、打印、打包设备不可用"),
    ORDER_CANCELLED("单据取消", "作业途中单据被取消或被改派给别人"),
    SAFETY_HOLD("安全暂停", "安全事件或现场条件不具备，暂停作业"),
    OTHER("其他", "以上都不是：写清楚原因，复盘时才知道该改什么");

    private final String label;
    private final String description;

    LaborExceptionCode(String label, String description) {
      this.label = label;
      this.description = description;
    }

    public String label() {
      return label;
    }

    public String description() {
      return description;
    }
  }

  /**
   * 排名的可选指标与<b>方向</b>。
   *
   * <p>{@code higherIsBetter} 不是装饰：差错率越低越好，把两个方向不同的指标塞进同一个
   * 「排行榜」再让人自己看，是绩效争议的标准起手式。方向写在枚举里，SQL 的 ORDER BY
   * 由它决定，前端也据它决定箭头朝上还是朝下。
   *
   * <p>精度：件数/单量/距离按 2 位小数，时效按秒（2 位），三个率按百分比（4 位小数）。
   * 排序一律在 SQL 里按<b>未截断</b>的值做，取整只发生在展示层 —— 截断后再排序会让
   * 排名与数字对不上（两个人都显示 100.00 件，一个第 3 一个第 4，看着像 bug）。
   */
  public enum LaborRankingMetric {
    PICKED_PIECES("人均日拣货件数", true, "日均拣货件数越高越好", "件/人·日"),
    PICKED_ORDERS("人均日拣货单量", true, "日均拣货单量越高越好", "单/人·日"),
    TRAVEL_DISTANCE("人均日拣货行走距离", false, "同样产出走得更少更好", "米/人·日"),
    PACKING_SECONDS("平均打包时效", false, "墙钟时长越短越好", "秒/任务"),
    REVIEW_ACCURACY("复核准确率", true, "复核准确率越高越好", "%"),
    ERROR_RATE_BY_PIECE("差错率（按件数）", false, "越低越好", "%"),
    ERROR_RATE_BY_ORDER("差错率（按单量）", false, "越低越好", "%");

    private final String label;
    private final boolean higherIsBetter;
    private final String description;
    private final String unit;

    LaborRankingMetric(String label, boolean higherIsBetter, String description, String unit) {
      this.label = label;
      this.higherIsBetter = higherIsBetter;
      this.description = description;
      this.unit = unit;
    }

    public String label() {
      return label;
    }

    public boolean higherIsBetter() {
      return higherIsBetter;
    }

    public String description() {
      return description;
    }

    public String unit() {
      return unit;
    }
  }

  // ------------------------------------------------------------ 输入

  /**
   * 派工（创建作业任务）。
   *
   * <p>{@code operatorUserId} 可以为空 —— 「先建任务、待派工」是真实存在的现场情况。
   * 但未派工的任务<b>不允许开工</b>（服务层 409），所以人效永远不会记在 NULL 头上。
   *
   * <p>{@code sourceModule} 标明这批数据是谁报上来的（8.3 各车道投递时填自己的模块号，
   * 本服务自发起的填 {@code 8.5.1.1}）。报表数字起争议时，第一个要回答的问题是
   * 「这批数据从哪来」，所以它不是可选字段。
   */
  public record LaborWorkTaskCreateInput(
      String workType,
      UUID warehouseId,
      String sourceModule,
      String referenceNo,
      @Schema(nullable = true) Integer orderCount,
      @Schema(nullable = true) Integer lineCount,
      @Schema(nullable = true) BigDecimal pieceCount,
      @Schema(nullable = true) UUID operatorUserId,
      @Schema(nullable = true) String note) {}

  /** 改派：只允许未开工的任务（DB 级触发器也钉死「已开工不许换人」）。 */
  public record LaborWorkTaskAssignInput(UUID operatorUserId, String note) {}

  /**
   * 开工。只带一句备注 —— <b>开始时刻由服务端取 now()</b>。
   *
   * <p>不接受请求参数传入时刻：调用方传时刻等于允许「把昨天做的活记在今天」，
   * 而「日」正是本模块的聚合键，一污染就影响提成核算。
   */
  public record LaborWorkTaskStartInput(@Schema(nullable = true) String note) {}

  /**
   * 完成：计量列在这一刻定稿。
   *
   * <p>{@code travelDistanceM} 只对拣货任务有意义，其他类型带上会被 422 拒绝；
   * {@code errorPieceCount} 不得超过 {@code pieceCount}（DB 级 CHECK 再挡一次）。
   */
  public record LaborWorkTaskCompleteInput(
      @Schema(nullable = true) Integer orderCount,
      @Schema(nullable = true) Integer lineCount,
      @Schema(nullable = true) BigDecimal pieceCount,
      @Schema(nullable = true) BigDecimal travelDistanceM,
      @Schema(nullable = true) BigDecimal errorPieceCount,
      @Schema(nullable = true) Integer errorOrderCount,
      @Schema(nullable = true) String note) {}

  /** 异常上报：缺货/设备/取消/安全暂停/其他。异常码必填，原因必填（复盘时要知道该改什么）。 */
  public record LaborWorkTaskExceptionInput(
      String exceptionCode,
      String exceptionNote,
      @Schema(nullable = true) Integer orderCount,
      @Schema(nullable = true) Integer lineCount,
      @Schema(nullable = true) BigDecimal pieceCount,
      @Schema(nullable = true) BigDecimal errorPieceCount,
      @Schema(nullable = true) Integer errorOrderCount) {}

  // ------------------------------------------------------------ 作业任务

  /**
   * 一张作业任务 = 一次人效事实。
   *
   * @param durationSeconds 墙钟时长 = completedAt − startedAt；缺任一时刻则为 null
   *     （异常终止的任务有 completedAt 但可能没有 startedAt，此时同样是 null，
   *     而它本来就不进打包时效）
   * @param workDay Asia/Shanghai 自然日，取自 completedAt —— 「一天算到什么时候」
   *     以完成时刻为准，不是开工或派工时刻
   * @param travelDistanceM 拣货行走距离；非拣货任务恒为 null（「没记」与「走了 0 米」是两件事）
   */
  public record LaborWorkTask(
      UUID id,
      String code,
      LaborWorkType workType,
      String sourceModule,
      String referenceNo,
      UUID warehouseId,
      String warehouseCode,
      @Schema(nullable = true) UUID operatorUserId,
      @Schema(nullable = true) UUID assignedByUserId,
      @Schema(nullable = true) OffsetDateTime assignedAt,
      WorkTaskStatus status,
      @Schema(nullable = true) OffsetDateTime startedAt,
      @Schema(nullable = true) OffsetDateTime completedAt,
      int orderCount,
      int lineCount,
      BigDecimal pieceCount,
      @Schema(nullable = true) BigDecimal travelDistanceM,
      BigDecimal errorPieceCount,
      int errorOrderCount,
      @Schema(nullable = true) String exceptionCode,
      String exceptionNote,
      @Schema(nullable = true) BigDecimal durationSeconds,
      @Schema(nullable = true) LocalDate workDay,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 作业任务列表（真分页；total 是该筛选下的全量行数）。 */
  public record LaborWorkTaskPage(List<LaborWorkTask> tasks, long total, int page, int size) {}

  /** 作业事件（append-only 时间线的一格）。 */
  public record LaborWorkEvent(
      UUID id,
      UUID taskId,
      String eventType,
      UUID actorUserId,
      OffsetDateTime occurredAt,
      @Schema(nullable = true) String exceptionCode,
      String detail) {}

  /** 作业任务详情 = 头部 + 完整事件时间线（「什么时候接的、中间停没停、异常谁报的」）。 */
  public record LaborWorkTaskDetail(LaborWorkTask task, List<LaborWorkEvent> timeline) {}

  // ------------------------------------------------------------ 口径

  /** 作业类型选项（前端据此渲染，不把枚举硬编码在界面上）。 */
  public record LaborWorkTypeOption(String value, String label, String description) {}

  /** 排名指标选项，含方向。 */
  public record LaborRankingMetricOption(
      String value, String label, String description, boolean higherIsBetter, String unit) {}

  /** 异常类型选项。 */
  public record LaborExceptionOption(String value, String label, String description) {}

  /**
   * 一个指标的口径定义。
   *
   * @param metric 指标代号
   * @param denominator 分母口径的**逐字**说明 —— 争议九成出在分母，不在分子
   * @param definition 计算式的逐字说明
   */
  public record LaborMetricBasis(
      String metric,
      String label,
      String unit,
      String definition,
      String denominator,
      @Schema(nullable = true) BigDecimal value) {}

  /**
   * 能力与口径。
   *
   * <p>前端把 {@code metricBasis} 渲染成人效页面上的口径说明，而不是把口径写死在界面上：
   * 口径改了就该只改一处，而争议时两边必须看的是同一段字。
   */
  public record LaborCapabilities(
      List<LaborWorkTypeOption> workTypes,
      List<LaborRankingMetricOption> rankingMetrics,
      List<LaborExceptionOption> exceptionCodes,
      List<LaborMetricBasis> metricBasis,
      String timezone,
      String dayBasisNote,
      String packingEfficiencyBasisNote,
      String reviewAccuracyBasisNote,
      String errorBasisNote,
      String perCapitaBasisNote,
      String errorVsExceptionNote,
      String upstreamNote,
      String ownershipNote) {}

  // ------------------------------------------------------------ 报表 / 排名 / 趋势

  /**
   * 人效报表的一行 = 一个操作人的一个自然日（蓝图：「按人按日统计」）。
   *
   * @param avgPackingSeconds 平均打包时效（墙钟时长）；分母是**打包任务张数**，
   *     且只算有开工时刻的任务；没有可算的任务时为 null
   * @param reviewAccuracy 复核准确率；分母为 0 时为 null（不可计算，不是 0%）
   */
  public record LaborEfficiencyRow(
      UUID operatorUserId,
      LocalDate workDay,
      long taskCount,
      BigDecimal pickedPieces,
      long pickedOrders,
      BigDecimal travelDistanceM,
      long packingTaskCount,
      BigDecimal packingSecondsTotal,
      BigDecimal avgPackingSeconds,
      BigDecimal reviewedPieces,
      BigDecimal reviewErrorPieces,
      BigDecimal reviewAccuracy,
      BigDecimal errorPieces,
      long errorOrders,
      BigDecimal errorRateByPiece,
      BigDecimal errorRateByOrder) {}

  /** 区间汇总。「人均」相关字段的全部分母口径见 {@link LaborCapabilities#perCapitaBasisNote()}。 */
  public record LaborSummary(
      long headcount,
      long workDays,
      BigDecimal pickedPieces,
      long pickedOrders,
      @Schema(nullable = true) BigDecimal avgPickedPiecesPerPersonDay,
      @Schema(nullable = true) BigDecimal avgPickedOrdersPerPersonDay,
      BigDecimal travelDistanceM,
      long packingTaskCount,
      BigDecimal packingSecondsTotal,
      @Schema(nullable = true) BigDecimal avgPackingSeconds,
      BigDecimal reviewedPieces,
      BigDecimal reviewErrorPieces,
      @Schema(nullable = true) BigDecimal reviewAccuracy,
      BigDecimal errorPieces,
      long errorOrders,
      BigDecimal errorRateByPiece,
      BigDecimal errorRateByOrder) {}

  /** 人效报表（按人按日分页 + 区间汇总）。 */
  public record LaborReport(
      LocalDate from,
      LocalDate to,
      String timezone,
      String dayBasis,
      @Schema(nullable = true) String workTypeFilter,
      @Schema(nullable = true) String warehouseFilter,
      LaborSummary summary,
      List<LaborEfficiencyRow> rows,
      long total,
      int page,
      int size) {}

  /** 排名的一行。{@code metricValue} 是**被排的那个指标**的原始值（未截断）。 */
  public record LaborRankingRow(
      int rank,
      UUID operatorUserId,
      long taskCount,
      long workDays,
      BigDecimal pickedPieces,
      long pickedOrders,
      BigDecimal travelDistanceM,
      long packingTaskCount,
      @Schema(nullable = true) BigDecimal avgPackingSeconds,
      BigDecimal reviewedPieces,
      @Schema(nullable = true) BigDecimal reviewAccuracy,
      BigDecimal errorPieces,
      long errorOrders,
      BigDecimal errorRateByPiece,
      BigDecimal errorRateByOrder,
      @Schema(nullable = true) BigDecimal metricValue) {}

  /** 人效排名（按区间合计排序，同一个人跨多天合并成一行）。 */
  public record LaborRanking(
      LocalDate from,
      LocalDate to,
      String metric,
      String metricLabel,
      boolean higherIsBetter,
      String metricBasis,
      List<LaborRankingRow> rows,
      long total) {}

  /** 趋势的一点 = 一个自然日。区间内**没有作业的日子也会出现**（各指标为 0、率为 null）。 */
  public record LaborTrendPoint(
      LocalDate workDay,
      long taskCount,
      long activeOperators,
      BigDecimal pickedPieces,
      long pickedOrders,
      BigDecimal travelDistanceM,
      long packingTaskCount,
      @Schema(nullable = true) BigDecimal avgPackingSeconds,
      BigDecimal reviewedPieces,
      @Schema(nullable = true) BigDecimal reviewAccuracy,
      BigDecimal errorPieces,
      long errorOrders,
      BigDecimal errorRateByPiece,
      BigDecimal errorRateByOrder) {}

  /** 人效趋势。{@code scopeNote} 说明这是全员口径还是某个人的口径。 */
  public record LaborTrend(
      LocalDate from,
      LocalDate to,
      String timezone,
      @Schema(nullable = true) UUID operatorUserId,
      String scopeNote,
      String dayBasisNote,
      List<LaborTrendPoint> points) {}
}