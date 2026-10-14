package com.starcon.warehouse;

import static com.starcon.warehouse.LaborEfficiencyModels.*;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 8.5.1.1 作业效率（人效）。
 *
 * <p>蓝图：「统计仓库人效：人均日拣货件数/单量、拣货行走距离、打包时效、复核准确率、差错率， 按人按日统计。」输入：「作业任务记录（操作人、时间、类型）；人员配置。」 输出：「人效报表 +
 * 排名 + 趋势。」以及本模块的红线： 「<b>人效数据用于提成核算与排班，需明确口径避免争议</b>（ERP 人效报表是绩效争议高发区）。」
 *
 * <p>命名空间 {@code /warehouse/labor/...}，权限码与 8.5 其余仓库模块同一套 {@code warehouse.inventory.read} / {@code
 * warehouse.inventory.update}： 07 库存是这些页面的 page_resource，8.5 是它的子页，另起一套权限码会让 已配好角色的租户看不到人效页。端点刻意独立成
 * {@code LaborEfficiencyController} 而不是并进某个既有 controller —— 并行车道各改各的才不会撞。
 *
 * <p>这一组端点分成两半，缺一半都不算交付：
 *
 * <ul>
 *   <li><b>写入半</b>（create / assign / start / complete / exception）：作业记录是真的写进 PostgreSQL
 *       的作业任务表，状态机与「终态计量冻结」由数据库触发器强制。 报表不是从别处算来的二手数据，它读的就是这张自己写的表。
 *   <li><b>读取半</b>（tasks / detail / report / ranking / trend）：人效聚合全部在 SQL 里算 （{@code sum / count /
 *       avg / filter}），不在 Java 里全量捞出来内存聚合。
 * </ul>
 *
 * <p>{@code /capabilities} 把五个指标的<b>精确口径</b>随每个响应一起发出去： 口径只写在服务端注释里，绩效争议时没有人能查到；口径进了响应体，界面能显示给用户，
 * 绩效面谈时双方看的是同一段字。这是蓝图那句「需明确口径避免争议」能落地的最小形式。
 */
@RestController
@RequestMapping("/warehouse")
public class LaborEfficiencyController {

  private final AccessService access;
  private final LaborEfficiencyService labor;

  public LaborEfficiencyController(AccessService access, LaborEfficiencyService labor) {
    this.access = access;
    this.labor = labor;
  }

  /**
   * 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头）， 再在一个事务里绑定 RLS 租户上下文执行查询（与 7.4.2.1 / 7.4.2.2 同一个
   * call）。
   */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  // ------------------------------------------------------------ 能力与口径

  /**
   * 人效能力与五个指标的精确口径（含分母、时区、单位、方向）。
   *
   * <p>前端据此渲染选项与口径说明，而不是把枚举与口径硬编码在界面上 —— 口径改了就该只改一处。
   */
  @GetMapping("/labor/capabilities")
  public LaborCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> labor.capabilities());
  }

  // ------------------------------------------------------------ 作业记录（写入）

  /**
   * 派工 / 建任务。
   *
   * <p>{@code operatorUserId} 可以为空（先建任务、待派工是真实存在的现场情况）， 但未派工的任务不允许开工 —— 所以人效永远不会记在 NULL 头上。
   *
   * <p>{@code sourceModule} 标明这批数据是谁报上来的：8.3 各车道落地后把作业事件投递到 这个端点，报表数字起争议时第一个要回答的问题就是「这批数据从哪来」。
   */
  @PostMapping("/labor/work-tasks")
  public LaborWorkTask create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody LaborWorkTaskCreateInput input) {
    return call(auth, "inventory.update", a -> labor.create(input, a.userId()));
  }

  /** 改派：只允许未开工的任务。已开工换人会让时长与行走距离的归属漂移，绩效争议将无从裁决。 */
  @PostMapping("/labor/work-tasks/{taskId}/assign")
  public LaborWorkTask assign(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID taskId,
      @Valid @RequestBody LaborWorkTaskAssignInput input) {
    return call(auth, "inventory.update", a -> labor.assign(taskId, input, a.userId()));
  }

  /** 开工：待开工 → 作业中。开始时刻由服务端取 now()，不接受请求参数传入 —— 传时刻等于允许「把昨天做的活记在今天」，而「日」正是本模块的聚合键。 */
  @PostMapping("/labor/work-tasks/{taskId}/start")
  public LaborWorkTask start(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID taskId,
      @RequestBody(required = false) LaborWorkTaskStartInput input) {
    return call(
        auth,
        "inventory.update",
        a ->
            labor.start(
                taskId, input == null ? new LaborWorkTaskStartInput(null) : input, a.userId()));
  }

  /**
   * 完成：作业中 → 已完成。计量列在这一刻定稿，之后数据库触发器冻结。
   *
   * <p>{@code travelDistanceM} 只对拣货有效，其他类型带上会被 422； {@code errorPieceCount} 不得超过 {@code
   * pieceCount}。
   */
  @PostMapping("/labor/work-tasks/{taskId}/complete")
  public LaborWorkTask complete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID taskId,
      @Valid @RequestBody LaborWorkTaskCompleteInput input) {
    return call(auth, "inventory.update", a -> labor.complete(taskId, input, a.userId()));
  }

  /** 异常上报：缺货 / 设备故障 / 单据取消 / 安全暂停 / 其他。可从「待开工」直接进（货不在货位）， 也可从「作业中」进（中途放弃）。异常<b>不计入</b>打包时效。 */
  @PostMapping("/labor/work-tasks/{taskId}/exception")
  public LaborWorkTask reportException(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID taskId,
      @Valid @RequestBody LaborWorkTaskExceptionInput input) {
    return call(auth, "inventory.update", a -> labor.reportException(taskId, input, a.userId()));
  }

  // ------------------------------------------------------------ 作业记录（读取）

  /** 作业任务列表：真分页 + 状态/类型/仓库/操作人筛选 + 单号与上游单据号的关键字搜索。 */
  @GetMapping("/labor/work-tasks")
  public LaborWorkTaskPage tasks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String workType,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) UUID operatorUserId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "inventory.read",
        a -> labor.tasks(status, workType, warehouseId, operatorUserId, keyword, page, size));
  }

  /** 作业任务详情 = 头部 + 完整事件时间线（什么时候接的、中间停没停、异常谁报的）。 */
  @GetMapping("/labor/work-tasks/{taskId}")
  public LaborWorkTaskDetail task(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID taskId) {
    return call(auth, "inventory.read", a -> labor.detail(taskId));
  }

  // ------------------------------------------------------------ 人效报表 / 排名 / 趋势

  /**
   * 人效报表：按人按日（蓝图原文「按人按日统计」）+ 区间汇总（含人均日）。
   *
   * <p>{@code from} / {@code to} 是 {@code yyyy-MM-dd}，按 {@code Asia/Shanghai} 解释， 格式不对直接
   * 400，不按服务器本地时区猜。缺省是最近 14 天。
   *
   * <p>响应里的 {@code dayBasis} 是「日」与统计范围的完整定义，前端必须显示出来。
   */
  @GetMapping("/labor/report")
  public LaborReport report(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) String workType,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a -> labor.report(date(from), date(to), warehouseId, workType, page, size));
  }

  /**
   * 人效排名：区间内按人合计，按<b>一个</b>指标排序。
   *
   * <p>{@code metric} 必填且必须显式给出方向（差错率越低越好、准确率越高越好）： 把方向相反的指标塞进同一个「排行榜」再让人自己看，是绩效争议的标准起手式。
   */
  @GetMapping("/labor/ranking")
  public LaborRanking ranking(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) String workType,
      @RequestParam String metric,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "inventory.read",
        a -> labor.ranking(date(from), date(to), warehouseId, workType, metric, size));
  }

  /** 人效趋势：逐日。区间内<b>没有作业的日子也会出现</b>（数量为 0、率为 null）—— 折线图跳过空白日会让「停了两天」看起来像「产量没变」，而那正是排班要看的事实。 */
  @GetMapping("/labor/trend")
  public LaborTrend trend(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) UUID operatorUserId,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) String workType) {
    return call(
        auth,
        "inventory.read",
        a -> labor.trend(date(from), date(to), operatorUserId, warehouseId, workType));
  }

  /**
   * {@code yyyy-MM-dd} 解析。
   *
   * <p>按 {@code Asia/Shanghai} 解释，格式不对直接 400 —— 不按服务器本地时区猜一个「今天」， 那会让同一份报表在不同机器上显示出不同的日期区间。
   */
  private static LocalDate date(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(raw.trim());
    } catch (DateTimeParseException e) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "日期格式必须是 yyyy-MM-dd（按 " + LaborEfficiencyModels.TIMEZONE + " 解释）：" + raw);
    }
  }
}
