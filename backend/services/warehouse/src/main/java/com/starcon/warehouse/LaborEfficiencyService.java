package com.starcon.warehouse;

import static com.starcon.warehouse.LaborEfficiencyModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 8.5.1.1 作业效率：作业记录写入 + 人效报表 / 排名 / 趋势。
 *
 * <p>蓝图原话：「统计仓库人效：人均日拣货件数/单量、拣货行走距离、打包时效、复核准确率、 差错率，按人按日统计。」输入：「作业任务记录（操作人、时间、类型）；人员配置。」 输出：「人效报表 +
 * 排名 + 趋势。」以及本模块的红线： 「<b>人效数据用于提成核算与排班，需明确口径避免争议</b>（ERP 人效报表是绩效争议高发区）。」
 *
 * <p><b>本模块自己拥有作业记录事实表，并提供真实的写入入口</b> （{@code create / assign / start / complete /
 * exception}）。蓝图的上游「8.3 全部作业记录」 在集成头里整块未交付（V72 只建出库单与出库单行，不建作业记录表；8.3.2/8.3.3 还没排上）， 所以本模块落地 {@code
 * warehouse_work_task} / {@code warehouse_work_event} 并自己写它们。 8.3 各车道落地后把作业事件投递到本服务的端点（{@code
 * sourceModule} 标明来源）， <b>不是</b>直接写表 —— 本车道是这两张表的唯一写入所有者。
 *
 * <p><b>口径是这个模块的交付物，不是它的注释。</b>五个指标的分母、单位、时区逐字写在这里、 写进 V74 的迁移注释、写进 {@code /capabilities} 的 {@code
 * metricBasis}， 也写进每个报表响应的 {@code dayBasis}。理由是蓝图那句「需明确口径避免争议」：
 * 口径只活在代码里，争议发生时没有人能查到；口径进了响应体，绩效面谈时双方看的是同一段字。
 *
 * <p>口径摘要（全文见 {@link #capabilities()}）：
 *
 * <ul>
 *   <li><b>按日</b>：自然日，时区固定 {@code Asia/Shanghai}，聚合键是完成时刻在该时区下的日期。 不跟服务器本地时区走 ——
 *       同一份报表在两台机器上跑出不同的「昨天」不可接受。
 *   <li><b>打包时效</b> = 墙钟时长（completed_at − started_at），<b>不是</b>净作业时长。
 *       净时长需要另记暂停区间，本表没有那组数据，算出来的是假的；墙钟时长可复算 —— 任何人拿同一行都能算出同一个数。分母是<b>打包任务张数</b>，异常终止的任务不计入。
 *   <li><b>复核准确率</b>：分母 = 复核件数（{@code work_type='REVIEW'} 的 piece_count 合计）， 分子 = 复核件数 − 差错件数。分母为 0
 *       返回 {@code null}（不可计算）， <b>不返回 0</b>：0% 会被读成「复核全对」，真实含义是「今天没人复核」。
 *   <li><b>差错率</b>：两个口径都出 —— 按件数 = Σ差错件数 ÷ Σ作业件数， 按单量 = Σ差错单量 ÷ Σ涉及单量。差错件数不可能超过作业件数（DB 级 CHECK）。
 *   <li><b>人均日</b>：分母 = 人数 × <b>作业天数</b>（区间内出现过完成作业的自然日）， 不用「人数 × 区间自然日数」——
 *       那样停工休假日会把人均拉低，而停工不是任何人的效率问题。
 *   <li><b>差错 ≠ 异常</b>：差错（做错了）进差错率；异常（缺货/设备/取消，做不了）进异常。 混在一起会让缺货的人被算成错得最离谱。
 *   <li><b>只统计已完成与异常终止</b>的任务：未开工、作业中的任务没有产出， 算成人效等于奖励「领了任务不干活」。
 * </ul>
 *
 * <p><b>时间绑定纪律</b>：{@code ?::timestamptz} 一律出现在语句最前面的 {@code t} CTE 里， 后面全是普通绑定，cast 永远不在绑定序列末位（与
 * V55 / V59 / V62 同一条纪律）。 写入用 {@link OffsetDateTime} 绑定，不走 {@code Timestamp.valueOf(LocalDateTime)}
 * —— 后者按 JVM 本地时区解释裸本地时间，在东八区会静默偏 −28800s。
 */
@Service
public class LaborEfficiencyService {

  /** 单页最大行数：报表一行 = 一个人一天，人数与天数都有限，但设上限防止参数写错拉爆内存。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 单号日期段用的时区（与服务里的 day 口径同一个时区，单号才与人效报表对得上）。 */
  private static final ZoneId ZONE = ZoneId.of(TIMEZONE);

  /** 趋势区间上限 366 天：超过这个跨度，「趋势」已经没有人看了，聚合还会把连接池占住。 */
  static final int MAX_TREND_DAYS = 366;

  /** numeric(19,6) 口径下的零：避免同一列里混着 scale 不一样的 0。 */
  private static final BigDecimal ZERO_QTY = new BigDecimal("0.000000");

  /** numeric(12,3) 口径下的零（行走距离）。 */
  private static final BigDecimal ZERO_DIST = new BigDecimal("0.000");

  /** 时效秒数保留 3 位小数（毫秒级足够复算，再多是噪声）。 */
  private static final int SECONDS_SCALE = 3;

  /** 比率保留 4 位小数（0.0001%），够读且可复核。 */
  private static final int RATE_SCALE = 4;

  /** 人均日保留 2 位小数：人均看小数点后两位没有意义，但截断前要保留原值去排序。 */
  private static final int PER_CAPITA_SCALE = 2;

  private static final DateTimeFormatter CODE_DAY =
      DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZONE);

  private final DSLContext sql;

  public LaborEfficiencyService(DSLContext sql) {
    this.sql = sql;
  }

  // ============================================================ 「日」与口径常量

  /**
   * 「日」的聚合键：完成时刻在 Asia/Shanghai 下的自然日。
   *
   * <p>刻意写死时区而不是 {@code current_setting('TimeZone')}：报表的可复算性要求 「同一行数据在任何机器上算出同一个日」，而服务器时区是会变的。 这段
   * SQL 与 V74 的四个部分索引建的是同一个表达式。
   */
  private static final String DAY = "(completed_at at time zone '" + TIMEZONE + "')::date";

  /** 带表别名的同一表达式（列表查询用）。 */
  private static final String DAY_T = "(t.completed_at at time zone '" + TIMEZONE + "')::date";

  // ---- 聚合片段（报表 / 排名 / 趋势共用，保证三处口径逐字一致）----

  /** 墙钟时长秒数：缺任一时刻即为 null。打包时效只算它。 */
  private static final String PACKING_SECONDS_SUM =
      "coalesce(sum(extract(epoch from (completed_at-started_at)))"
          + " filter (where work_type='PACKING' and status='COMPLETED' and started_at is not null),0)"
          + "::numeric(19,3)";

  /** 参与打包时效的任务张数：分母。异常终止的（有 completed_at，无 started_at）自然落选。 */
  private static final String PACKING_TIMED_COUNT =
      "count(*) filter (where work_type='PACKING' and status='COMPLETED'"
          + " and started_at is not null)::bigint";

  /** 平均打包时效（秒/任务）：在 SQL 里算，分母为 0 时是 null。 */
  private static final String PACKING_AVG =
      "case when "
          + PACKING_TIMED_COUNT
          + " > 0 then "
          + PACKING_SECONDS_SUM
          + " / "
          + PACKING_TIMED_COUNT
          + " else null end";

  /** 复核准确率（%）：分母是复核件数。分母为 0 时 null，不是 0。 */
  private static final String REVIEW_ACCURACY =
      "case when coalesce(sum(piece_count) filter (where work_type='REVIEW'),0) > 0"
          + " then 100.0 * (coalesce(sum(piece_count) filter (where work_type='REVIEW'),0)"
          + " - coalesce(sum(error_piece_count) filter (where work_type='REVIEW'),0))"
          + " / coalesce(sum(piece_count) filter (where work_type='REVIEW'),0) else null end";

  /** 差错率（按件数 %）：分母是全部作业件数。 */
  private static final String ERROR_RATE_PIECE =
      "case when coalesce(sum(piece_count),0) > 0"
          + " then 100.0 * coalesce(sum(error_piece_count),0) / coalesce(sum(piece_count),0)"
          + " else null end";

  /** 差错率（按单量 %）：分母是全部涉及单量。 */
  private static final String ERROR_RATE_ORDER =
      "case when coalesce(sum(order_count),0) > 0"
          + " then 100.0 * coalesce(sum(error_order_count),0) / coalesce(sum(order_count),0)"
          + " else null end";

  /** 差错件数合计（复核行与差错率都要用）。 */
  private static final String REVIEW_ERROR_PIECES =
      "coalesce(sum(error_piece_count) filter (where work_type='REVIEW'),0)::numeric(19,6)";

  /** 复核件数合计。 */
  private static final String REVIEWED_PIECES =
      "coalesce(sum(piece_count) filter (where work_type='REVIEW'),0)::numeric(19,6)";

  /** 全部作业件数（差错率按件数的分母）。 */
  private static final String ALL_PIECES = "coalesce(sum(piece_count),0)::numeric(19,6)";

  /** 全部涉及单量（差错率按单量的分母）。 */
  private static final String ALL_ORDERS = "coalesce(sum(order_count),0)::bigint";

  /** 拣货行走距离合计。sum 全为 NULL 时 coalesce 成 0（「没记」与「走了 0 米」在库里仍可区分）。 */
  private static final String TRAVEL_DISTANCE =
      "coalesce(sum(travel_distance_m) filter (where work_type='PICKING'),0)::numeric(12,3)";

  /** 拣货件数合计。 */
  private static final String PICKED_PIECES =
      "coalesce(sum(piece_count) filter (where work_type='PICKING'),0)::numeric(19,6)";

  /** 拣货单量合计。 */
  private static final String PICKED_ORDERS =
      "coalesce(sum(order_count) filter (where work_type='PICKING'),0)::bigint";

  /** 人数 = 区间内有过完成作业的**不同操作人**个数（未开工/作业中的人不算进来）。 */
  private static final String HEADCOUNT = "count(distinct operator_user_id)::bigint";

  /** 作业天数 = 区间内出现过完成作业的自然日个数（停工日不进分母）。 */
  private static final String WORK_DAYS = "count(distinct " + DAY + ")::bigint";

  /** 人均日：Σ ÷ (人数 × 作业天数)。分母为 0 时 null。 */
  private static String perCapita(String sum) {
    return "case when "
        + HEADCOUNT
        + "::numeric * "
        + WORK_DAYS
        + " > 0"
        + " then "
        + sum
        + " / ("
        + HEADCOUNT
        + "::numeric * "
        + WORK_DAYS
        + ")"
        + " else null end";
  }

  /** 人均日（个人）：Σ ÷ 自己的作业天数。分母为 0 时 null。 */
  private static String perPersonDay(String sum) {
    return "case when " + WORK_DAYS + " > 0 then " + sum + " / " + WORK_DAYS + " else null end";
  }

  /** 报表 / 排名共用的整段聚合列。 */
  private static final String AGG_COLUMNS =
      "count(*)::bigint as task_count,"
          + HEADCOUNT
          + " as headcount,"
          + WORK_DAYS
          + " as work_days,"
          + PICKED_PIECES
          + " as picked_pieces,"
          + PICKED_ORDERS
          + " as picked_orders,"
          + TRAVEL_DISTANCE
          + " as travel_distance_m,"
          + "count(*) filter (where work_type='PACKING')::bigint as packing_task_count,"
          + PACKING_SECONDS_SUM
          + " as packing_seconds_total,"
          + PACKING_AVG
          + " as avg_packing_seconds,"
          + REVIEWED_PIECES
          + " as reviewed_pieces,"
          + REVIEW_ERROR_PIECES
          + " as review_error_pieces,"
          + REVIEW_ACCURACY
          + " as review_accuracy,"
          + "coalesce(sum(error_piece_count),0)::numeric(19,6) as error_pieces,"
          + "coalesce(sum(error_order_count),0)::bigint as error_orders,"
          + ALL_PIECES
          + " as all_pieces,"
          + ALL_ORDERS
          + " as all_orders,"
          + ERROR_RATE_PIECE
          + " as error_rate_by_piece,"
          + ERROR_RATE_ORDER
          + " as error_rate_by_order";

  // ============================================================ 能力与口径

  /**
   * 作业能力与<b>五个指标的精确口径</b>。
   *
   * <p>前端据此渲染选项与口径说明，而不是把口径硬编码在界面上 —— 口径改了就该只改一处， 而绩效面谈时双方必须看的是同一段字。
   */
  public LaborCapabilities capabilities() {
    List<LaborWorkTypeOption> types = new ArrayList<>();
    for (var t : LaborWorkType.values()) {
      types.add(new LaborWorkTypeOption(t.name(), t.label(), t.description()));
    }
    List<LaborRankingMetricOption> metrics = new ArrayList<>();
    for (var m : LaborRankingMetric.values()) {
      metrics.add(
          new LaborRankingMetricOption(
              m.name(), m.label(), m.description(), m.higherIsBetter(), m.unit()));
    }
    List<LaborExceptionOption> exceptions = new ArrayList<>();
    for (var e : LaborExceptionCode.values()) {
      exceptions.add(new LaborExceptionOption(e.name(), e.label(), e.description()));
    }
    return new LaborCapabilities(
        types,
        metrics,
        exceptions,
        metricBasis(),
        TIMEZONE,
        DAY_BASIS_NOTE,
        PACKING_BASIS_NOTE,
        REVIEW_BASIS_NOTE,
        ERROR_BASIS_NOTE,
        PER_CAPITA_NOTE,
        ERROR_VS_EXCEPTION_NOTE,
        UPSTREAM_NOTE,
        OWNERSHIP_NOTE);
  }

  /** 五个指标的口径定义（无当前值）。报表里带值的版本见 {@link #basis(String, BigDecimal)}。 */
  public List<LaborMetricBasis> metricBasis() {
    List<LaborMetricBasis> out = new ArrayList<>();
    out.add(
        new LaborMetricBasis(
            "PICKED_PIECES",
            "人均日拣货件数",
            "件/人·日",
            "区间内 work_type=PICKING 且已定稿的任务的 piece_count 合计 ÷ (人数 × 作业天数)。"
                + "「日」= 完成时刻在 Asia/Shanghai 下的自然日。",
            "人数 = 区间内有过完成作业的不同操作人个数；作业天数 = 区间内出现过完成作业的自然日天数。",
            null));
    out.add(
        new LaborMetricBasis(
            "PICKED_ORDERS",
            "人均日拣货单量",
            "单/人·日",
            "同上，但分子换成 order_count 合计（拣货任务覆盖的去重后单据张数）。",
            "同「人均日拣货件数」。",
            null));
    out.add(
        new LaborMetricBasis(
            "TRAVEL_DISTANCE",
            "人均日拣货行走距离",
            "米/人·日",
            "区间内拣货任务的 travel_distance_m 合计 ÷ (人数 × 作业天数)。" + "只有拣货任务有值，其他类型恒为 null。",
            "同「人均日拣货件数」。距离越大不代表越差，要与同区间产出对照看。",
            null));
    out.add(
        new LaborMetricBasis(
            "PACKING_SECONDS",
            "打包时效",
            "秒/任务",
            "每张打包任务从开始到完成的墙钟时长（completed_at − started_at）的平均值。",
            "分母是**打包任务张数**，不是件数：只算有 started_at 的打包任务，"
                + "异常终止的任务不计入（中途放弃的时长不代表打包速度）。"
                + "「一件货从 A 走到 B 用多久」需要逐件时间戳，本表没有那组数据，不假装算得出来。",
            null));
    out.add(
        new LaborMetricBasis(
            "REVIEW_ACCURACY",
            "复核准确率",
            "%",
            "(复核件数 − 差错件数) ÷ 复核件数 × 100。差错记在**发现**它的那张任务上，" + "所以复核口径下它就是复核员自己的准确率。",
            "分母是复核件数（work_type=REVIEW 的 piece_count 合计），不是复核单数、不是复核行数。"
                + "分母为 0 时返回 null（不可计算）——不返回 0%，因为 0% 会被读成「复核全对」，"
                + "真实含义是「今天没人复核」。",
            null));
    out.add(
        new LaborMetricBasis(
            "ERROR_RATE_BY_PIECE",
            "差错率（按件数）",
            "%",
            "差错件数合计 ÷ 作业件数合计 × 100，跨三种作业类型统计（拣错、复核漏、打错包装" + "都记在各自那张任务上）。",
            "分母是全部作业件数。差错件数不可能超过作业件数 —— DB 级 CHECK 钉死，" + "超过它意味着口径本身错了，而不是「这个人错得离谱」。",
            null));
    out.add(
        new LaborMetricBasis(
            "ERROR_RATE_BY_ORDER",
            "差错率（按单量）",
            "%",
            "差错单量合计 ÷ 涉及单量合计 × 100。与按件数口径并列给出，" + "因为「大错小错」和「错了几张单」在提成核算里是两笔账。",
            "分母是全部涉及单量。order_count 由上游给出，是**该任务内**去重后的单据张数；" + "跨任务去重需要全量明细，不是这张汇总表的粒度能做的事。",
            null));
    return out;
  }

  // ============================================================ 写入：作业记录

  /**
   * 派工 / 建任务。
   *
   * <p>{@code operatorUserId} 可以为空（「先建任务、待派工」是真实存在的现场情况）， 但未派工的任务不允许开工，所以人效永远不会记在 NULL 头上。
   */
  @Transactional
  public LaborWorkTask create(LaborWorkTaskCreateInput input, UUID actorUserId) {
    var type = workType(input.workType());
    var source = requireText(input.sourceModule(), 32, "必须填来源模块号：报表数字起争议时第一个要回答的是「这批数据从哪来」。");
    var warehouseId = input.warehouseId();
    if (warehouseId == null) {
      throw bad("必须指定作业仓库：人效分母是「人 × 日」，没有仓库就无法按仓切口径。");
    }
    requireWarehouse(warehouseId);
    var note = optional(input.note(), 500);

    var id = UUID.randomUUID();
    // 单号服务派生（WT-<yyyyMMdd>-<8 位 uuid 十六进制>）：人手填的单号会撞，
    // 而这张单据是提成核算的凭据，必须由服务端唯一生成。
    var code =
        "WT-"
            + CODE_DAY.format(OffsetDateTime.now(ZONE))
            + "-"
            + id.toString().replace("-", "").substring(0, 8);

    sql.execute(
        "insert into warehouse_work_task(id,tenant_id,code,work_type,source_module,reference_no,"
            + " warehouse_id,operator_user_id,assigned_at,assigned_by_user_id,"
            + " order_count,line_count,piece_count,note)"
            + " values(?,?,?,?,?,?,?,?,?::timestamptz,?,?,?,?,?)",
        id,
        tenant(),
        code,
        type.name(),
        source,
        input.referenceNo() == null ? "" : input.referenceNo().trim(),
        warehouseId,
        input.operatorUserId(),
        input.operatorUserId() == null ? null : OffsetDateTime.now(ZONE),
        input.operatorUserId() == null ? null : actorUserId,
        orderCount(input.orderCount()),
        lineCount(input.lineCount()),
        pieceCount(input.pieceCount()),
        note);

    event(
        id,
        "CREATED",
        actorUserId,
        "新建"
            + type.label()
            + "任务"
            + (input.operatorUserId() == null ? "（尚未派工）" : "并派工给 " + input.operatorUserId()),
        null);
    if (input.operatorUserId() != null) {
      event(id, "ASSIGNED", actorUserId, "派工给 " + input.operatorUserId(), null);
    }
    return requireTask(id);
  }

  /** 改派：只允许未开工的任务（DB 级触发器也钉死「已开工不许换人」）。 */
  @Transactional
  public LaborWorkTask assign(UUID taskId, LaborWorkTaskAssignInput input, UUID actorUserId) {
    var task = requireTask(taskId);
    if (task.status() != WorkTaskStatus.PENDING) {
      throw conflict(
          "作业任务 "
              + task.code()
              + " 当前是「"
              + task.status().label()
              + "」，只有「待开工」的任务能改派：已开工的任务换人会让人效归属漂移。");
    }
    if (input.operatorUserId() == null) {
      throw bad("必须指定操作人：人效是按人统计的，没有操作人的任务算不出人效。");
    }
    sql.execute(
        "update warehouse_work_task set operator_user_id=?,assigned_at=?::timestamptz,"
            + " assigned_by_user_id=?,note=coalesce(?,note) where tenant_id=? and id=?",
        input.operatorUserId(),
        OffsetDateTime.now(ZONE),
        actorUserId,
        blankToNull(input == null ? null : input.note()),
        tenant(),
        taskId);
    event(
        taskId,
        "ASSIGNED",
        actorUserId,
        (task.operatorUserId() == null ? "首次派工给 " : "改派给 ") + input.operatorUserId(),
        null);
    return requireTask(taskId);
  }

  /**
   * 开工。
   *
   * <p>开始时刻<b>由服务端取 now()</b>，不接受请求参数传入 —— 传时刻等于允许 「把昨天做的活记在今天」，而「日」正是本模块的聚合键，一污染就影响提成核算。
   */
  @Transactional
  public LaborWorkTask start(UUID taskId, LaborWorkTaskStartInput input, UUID actorUserId) {
    var task = requireTask(taskId);
    if (task.status() == WorkTaskStatus.RUNNING) {
      throw conflict("作业任务 " + task.code() + " 已在作业中，重复开工只会让时效被算短。");
    }
    if (task.status() != WorkTaskStatus.PENDING) {
      throw conflict(
          "作业任务 " + task.code() + " 当前是「" + task.status().label() + "」，终态任务不能重新开工：计量已定稿。");
    }
    if (task.operatorUserId() == null) {
      throw conflict("作业任务 " + task.code() + " 还没派工，不能开工：" + "没有操作人的作业算不出人效，记上去只会让���均被稀释。");
    }
    var at = OffsetDateTime.now(ZONE);
    sql.execute(
        "update warehouse_work_task set status='RUNNING',started_at=?::timestamptz,"
            + " note=coalesce(?,note) where tenant_id=? and id=?",
        at,
        blankToNull(input == null ? null : input.note()),
        tenant(),
        taskId);
    event(taskId, "STARTED", actorUserId, "开工" + task.workType().label() + "作业", null);
    return requireTask(taskId);
  }

  /** 完成：计量列在这一刻定稿，之后 V74 触发器冻结。 */
  @Transactional
  public LaborWorkTask complete(UUID taskId, LaborWorkTaskCompleteInput input, UUID actorUserId) {
    var task = requireTask(taskId);
    if (task.status() != WorkTaskStatus.RUNNING) {
      throw conflict("作业任务 " + task.code() + " 当前是「" + task.status().label() + "」，只有「作业中」的任务能完成。");
    }
    var orders = orderCount(input.orderCount());
    var lines = lineCount(input.lineCount());
    var pieces = pieceCount(input.pieceCount());
    var distance = distance(task.workType(), input.travelDistanceM());
    var errorPieces = errorPieceCount(input.errorPieceCount());
    var errorOrders = errorOrderCount(input.errorOrderCount());
    if (errorPieces.compareTo(pieces) > 0) {
      throw bad(
          "差错件数 "
              + errorPieces.stripTrailingZeros().toPlainString()
              + " 不能超过作业件数 "
              + pieces.stripTrailingZeros().toPlainString()
              + "：超过它意味着口径错了，而不是这个人错得离谱。");
    }
    if (errorOrders > orders) {
      throw bad("差错单量 " + errorOrders + " 不能超过涉及单量 " + orders + "。");
    }
    sql.execute(
        "update warehouse_work_task set status='COMPLETED',completed_at=?::timestamptz,"
            + " order_count=?,line_count=?,piece_count=?,travel_distance_m=?,"
            + " error_piece_count=?,error_order_count=?,note=coalesce(?,note)"
            + " where tenant_id=? and id=?",
        OffsetDateTime.now(ZONE),
        orders,
        lines,
        pieces,
        distance,
        errorPieces,
        errorOrders,
        blankToNull(input.note()),
        tenant(),
        taskId);
    event(
        taskId,
        "COMPLETED",
        actorUserId,
        "完成"
            + task.workType().label()
            + "作业：单量 "
            + orders
            + "、行数 "
            + lines
            + "、件数 "
            + pieces.stripTrailingZeros().toPlainString()
            + (distance == null
                ? ""
                : "、行走 " + distance.stripTrailingZeros().toPlainString() + " 米")
            + "、差错 "
            + errorPieces.stripTrailingZeros().toPlainString()
            + " 件",
        null);
    return requireTask(taskId);
  }

  /**
   * 异常上报：缺货 / 设备故障 / 单据取消 / 安全暂停 / 其他。
   *
   * <p>可以从「待开工」直接进异常（货都不在货位，开不了工），也可以从「作业中」进（中途放弃）。 异常<b>不计入</b>打包时效：中途放弃的墙钟时长不代表打包速度。
   */
  @Transactional
  public LaborWorkTask reportException(
      UUID taskId, LaborWorkTaskExceptionInput input, UUID actorUserId) {
    var task = requireTask(taskId);
    if (task.status() == WorkTaskStatus.COMPLETED || task.status() == WorkTaskStatus.EXCEPTION) {
      throw conflict(
          "作业任务 "
              + task.code()
              + " 已定稿（"
              + task.status().label()
              + "），不能重复上报：计量与异常都已落定，纠错请走新的作业单据。");
    }
    var code = exceptionCode(input.exceptionCode());
    var reason = requireText(input.exceptionNote(), 500, "必须写清异常原因：异常复盘时要靠它决定该改排班、该补货还是该修设备。");
    if (reason.length() < 4) {
      throw bad("异常原因至少 4 个字：「缺」「停」这样写等于没写，复盘时没人知道该改什么。");
    }
    var orders = orderCount(input.orderCount());
    var lines = lineCount(input.lineCount());
    var pieces = pieceCount(input.pieceCount());
    var errorPieces = errorPieceCount(input.errorPieceCount());
    var errorOrders = errorOrderCount(input.errorOrderCount());
    if (errorPieces.compareTo(pieces) > 0) {
      throw bad("差错件数不能超过作业件数。");
    }
    if (errorOrders > orders) {
      throw bad("差错单量不能超过涉及单量。");
    }
    sql.execute(
        "update warehouse_work_task set status='EXCEPTION',completed_at=?::timestamptz,"
            + " order_count=?,line_count=?,piece_count=?,error_piece_count=?,error_order_count=?,"
            + " exception_code=?,exception_note=? where tenant_id=? and id=?",
        OffsetDateTime.now(ZONE),
        orders,
        lines,
        pieces,
        errorPieces,
        errorOrders,
        code.name(),
        reason,
        tenant(),
        taskId);
    event(
        taskId,
        "EXCEPTION",
        actorUserId,
        code.label()
            + "："
            + reason
            + "（中断时已作业 "
            + pieces.stripTrailingZeros().toPlainString()
            + " 件）",
        code.name());
    return requireTask(taskId);
  }

  // ============================================================ 读取：作业记录

  /** 作业任务列表：真分页 + 五种筛选 + 真实关键字搜索（单号 / 上游单据号）。 */
  public LaborWorkTaskPage tasks(
      String status,
      String workType,
      UUID warehouseId,
      UUID operatorUserId,
      String keyword,
      int page,
      int size) {
    int limit = pageSize(size);
    int offset = Math.max(0, page) * limit;
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    where.add("t.tenant_id = ?");
    binds.add(tenant());
    if (!blank(status)) {
      where.add("t.status = ?");
      binds.add(status(status).name());
    }
    if (!blank(workType)) {
      where.add("t.work_type = ?");
      binds.add(workType(workType).name());
    }
    if (warehouseId != null) {
      where.add("t.warehouse_id = ?");
      binds.add(warehouseId);
    }
    if (operatorUserId != null) {
      where.add("t.operator_user_id = ?");
      binds.add(operatorUserId);
    }
    if (!blank(keyword)) {
      where.add("(t.code ilike ? or t.reference_no ilike ?)");
      var like = "%" + keyword.trim() + "%";
      binds.add(like);
      binds.add(like);
    }
    var clause = " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from " + TASK_FROM + clause, binds.toArray())
            .get(0, Long.class);
    if (total == 0) {
      return new LaborWorkTaskPage(List.of(), 0, Math.max(0, page), limit);
    }
    // offset 必须绑成 long（与 7.4.2.2 同一处约束：int 绑 OFFSET 会在驱动层报写入失败）
    var rows =
        sql.fetch(
            "select "
                + TASK_COLUMNS
                + " from "
                + TASK_FROM
                + clause
                + " order by t.updated_at desc, t.id desc limit ? offset ?",
            append(binds, (long) limit, (long) offset).toArray());
    return new LaborWorkTaskPage(map(rows), total, Math.max(0, page), limit);
  }

  /** 作业任务详情 = 头部 + 完整事件时间线。 */
  public LaborWorkTaskDetail detail(UUID taskId) {
    var task = requireTask(taskId);
    var timeline =
        sql.fetch(
            "select id,task_id,event_type,actor_user_id,occurred_at,exception_code,detail"
                + " from warehouse_work_event where tenant_id=? and task_id=?"
                + " order by occurred_at,id",
            tenant(),
            taskId);
    return new LaborWorkTaskDetail(task, timelineOf(timeline));
  }

  // ============================================================ 报表（按人按日）

  /**
   * 人效报表：按人按日 + 区间汇总（含人均日）。
   *
   * <p>聚合全部在 SQL 里（{@code sum / count / avg / filter}），Java 侧只做映射与比例展示 —— 在 Java 里全量捞出来内存聚合的做法在「8
   * 个人 × 30 天」时看不出问题， 到「80 人 × 90 天」就会把堆打满，而那时它已经算错了。
   */
  public LaborReport report(
      LocalDate from, LocalDate to, UUID warehouseId, String workType, int page, int size) {
    var range = range(from, to);
    var filters = filters(warehouseId, workType);
    var where = baseWhere(range) + filters;
    var binds = append(baseBinds(range), filterBinds(warehouseId, workType).toArray());

    long total =
        sql.fetchOne(
                "select count(*)::bigint from (select t.operator_user_id,"
                    + DAY_T
                    + " from warehouse_work_task t"
                    + where
                    + " group by 1,2) g",
                binds.toArray())
            .get(0, Long.class);

    var rowBinds = new ArrayList<Object>(binds);
    rowBinds.add((long) pageSize(size));
    rowBinds.add((long) Math.max(0, page) * pageSize(size));
    var rows =
        sql.fetch(
            "select t.operator_user_id,"
                + DAY_T
                + " as work_day,"
                + AGG_COLUMNS
                + " from warehouse_work_task t"
                + where
                + " group by t.operator_user_id,"
                + DAY_T
                + " order by work_day desc, picked_pieces desc, t.operator_user_id"
                + " limit ? offset ?",
            rowBinds.toArray());

    var summaryRow =
        sql.fetchOne(
            "select "
                + AGG_COLUMNS
                + ","
                + perCapita(PICKED_PIECES)
                + " as avg_picked_pieces_per_person_day,"
                + perCapita(PICKED_ORDERS)
                + " as avg_picked_orders_per_person_day from warehouse_work_task t"
                + where,
            binds.toArray());

    var summary =
        new LaborSummary(
            summaryRow.get("headcount", Long.class),
            summaryRow.get("work_days", Long.class),
            qty(summaryRow.get("picked_pieces", BigDecimal.class)),
            summaryRow.get("picked_orders", Long.class),
            plain(
                summaryRow.get("avg_picked_pieces_per_person_day", BigDecimal.class),
                PER_CAPITA_SCALE),
            plain(
                summaryRow.get("avg_picked_orders_per_person_day", BigDecimal.class),
                PER_CAPITA_SCALE),
            dist(summaryRow.get("travel_distance_m", BigDecimal.class)),
            summaryRow.get("packing_task_count", Long.class),
            seconds(summaryRow.get("packing_seconds_total", BigDecimal.class)),
            seconds(summaryRow.get("avg_packing_seconds", BigDecimal.class)),
            qty(summaryRow.get("reviewed_pieces", BigDecimal.class)),
            qty(summaryRow.get("review_error_pieces", BigDecimal.class)),
            rate(summaryRow.get("review_accuracy", BigDecimal.class)),
            qty(summaryRow.get("error_pieces", BigDecimal.class)),
            summaryRow.get("error_orders", Long.class),
            rate(summaryRow.get("error_rate_by_piece", BigDecimal.class)),
            rate(summaryRow.get("error_rate_by_order", BigDecimal.class)));

    var mapped = new ArrayList<LaborEfficiencyRow>();
    for (Record r : rows) {
      mapped.add(
          new LaborEfficiencyRow(
              r.get("operator_user_id", UUID.class),
              r.get("work_day", LocalDate.class),
              r.get("task_count", Long.class),
              qty(r.get("picked_pieces", BigDecimal.class)),
              r.get("picked_orders", Long.class),
              dist(r.get("travel_distance_m", BigDecimal.class)),
              r.get("packing_task_count", Long.class),
              seconds(r.get("packing_seconds_total", BigDecimal.class)),
              seconds(r.get("avg_packing_seconds", BigDecimal.class)),
              qty(r.get("reviewed_pieces", BigDecimal.class)),
              qty(r.get("review_error_pieces", BigDecimal.class)),
              rate(r.get("review_accuracy", BigDecimal.class)),
              qty(r.get("error_pieces", BigDecimal.class)),
              r.get("error_orders", Long.class),
              rate(r.get("error_rate_by_piece", BigDecimal.class)),
              rate(r.get("error_rate_by_order", BigDecimal.class))));
    }
    return new LaborReport(
        range.from(),
        range.to(),
        TIMEZONE,
        DAY_BASIS_NOTE,
        blank(workType) ? null : workType(workType).name(),
        warehouseId == null ? null : warehouseId.toString(),
        summary,
        mapped,
        total,
        Math.max(0, page),
        pageSize(size));
  }

  // ============================================================ 排名

  /**
   * 人效排名：区间内按人合计，按<b>一个</b>指标排序。
   *
   * <p>方向写在 {@link LaborRankingMetric#higherIsBetter()} 里：差错率越低越好，
   * 准确率越高越好。把两个方向相反的指标塞进同一个「排行榜」再让人自己看， 是绩效争议的标准起手式。排序值一律在 SQL 里算<b>未截断</b>的原值， 截断只发生在展示层 ——
   * 否则两个人都显示 100.00 件却一个第 3 一个第 4，看着像 bug。
   */
  public LaborRanking ranking(
      LocalDate from, LocalDate to, UUID warehouseId, String workType, String metric, int size) {
    var metricEnum = rankingMetric(metric);
    var range = range(from, to);
    var where = baseWhere(range) + filters(warehouseId, workType);
    var binds = append(baseBinds(range), filterBinds(warehouseId, workType).toArray());
    var direction = metricEnum.higherIsBetter() ? "desc" : "asc";
    int limit = pageSize(size);
    var rows =
        sql.fetch(
            "select t.operator_user_id,"
                + AGG_COLUMNS
                + ","
                + metricValue(metricEnum)
                + " as metric_value from warehouse_work_task t"
                + where
                + " group by t.operator_user_id"
                + " order by metric_value "
                + direction
                + " nulls last, t.operator_user_id"
                + " limit ?",
            append(binds, (long) limit).toArray());
    long total =
        sql.fetchOne(
                "select count(distinct t.operator_user_id)::bigint from warehouse_work_task t"
                    + where,
                binds.toArray())
            .get(0, Long.class);

    var mapped = new ArrayList<LaborRankingRow>();
    int rank = 0;
    for (Record r : rows) {
      mapped.add(
          new LaborRankingRow(
              ++rank,
              r.get("operator_user_id", UUID.class),
              r.get("task_count", Long.class),
              r.get("work_days", Long.class),
              qty(r.get("picked_pieces", BigDecimal.class)),
              r.get("picked_orders", Long.class),
              dist(r.get("travel_distance_m", BigDecimal.class)),
              r.get("packing_task_count", Long.class),
              seconds(r.get("avg_packing_seconds", BigDecimal.class)),
              qty(r.get("reviewed_pieces", BigDecimal.class)),
              rate(r.get("review_accuracy", BigDecimal.class)),
              qty(r.get("error_pieces", BigDecimal.class)),
              r.get("error_orders", Long.class),
              rate(r.get("error_rate_by_piece", BigDecimal.class)),
              rate(r.get("error_rate_by_order", BigDecimal.class)),
              rankValue(metricEnum, r.get("metric_value", BigDecimal.class))));
    }
    return new LaborRanking(
        range.from(),
        range.to(),
        metricEnum.name(),
        metricEnum.label(),
        metricEnum.higherIsBetter(),
        metricBasisOf(metricEnum),
        mapped,
        total);
  }

  /**
   * 人效趋势：逐日。
   *
   * <p>区间内<b>没有作业的日子也会出现</b>（各数量为 0、率为 null）。折线图跳过空白日会 让「停了两天」看起来像「产量没变」，而那正是排班要看的事实。
   */
  public LaborTrend trend(
      LocalDate from, LocalDate to, UUID operatorUserId, UUID warehouseId, String workType) {
    var range = range(from, to);
    var inner = new ArrayList<String>();
    var innerBinds = new ArrayList<Object>();
    inner.add("t.tenant_id = ?");
    innerBinds.add(tenant());
    inner.add("t.status in ('COMPLETED','EXCEPTION')");
    inner.add(DAY_T + " between ?::date and ?::date");
    innerBinds.add(range.from());
    innerBinds.add(range.to());
    if (warehouseId != null) {
      inner.add("t.warehouse_id = ?");
      innerBinds.add(warehouseId);
    }
    if (!blank(workType)) {
      inner.add("t.work_type = ?");
      innerBinds.add(workType(workType).name());
    }
    if (operatorUserId != null) {
      inner.add("t.operator_user_id = ?");
      innerBinds.add(operatorUserId);
    }

    // generate_series 先出日历，聚合左连接上去：空白日由此而来，而不是被 WHERE 滤掉。
    var binds = new ArrayList<Object>();
    binds.add(range.from());
    binds.add(range.to());
    binds.addAll(innerBinds);
    var rows =
        sql.fetch(
            "select d.day::date as work_day,"
                + " coalesce(a.task_count,0)::bigint as task_count,"
                + " coalesce(a.headcount,0)::bigint as active_operators,"
                + " coalesce(a.picked_pieces,0)::numeric(19,6) as picked_pieces,"
                + " coalesce(a.picked_orders,0)::bigint as picked_orders,"
                + " coalesce(a.travel_distance_m,0)::numeric(12,3) as travel_distance_m,"
                + " coalesce(a.packing_task_count,0)::bigint as packing_task_count,"
                + " a.avg_packing_seconds as avg_packing_seconds,"
                + " coalesce(a.reviewed_pieces,0)::numeric(19,6) as reviewed_pieces,"
                + " a.review_accuracy as review_accuracy,"
                + " coalesce(a.error_pieces,0)::numeric(19,6) as error_pieces,"
                + " coalesce(a.error_orders,0)::bigint as error_orders,"
                + " a.error_rate_by_piece as error_rate_by_piece,"
                + " a.error_rate_by_order as error_rate_by_order"
                + " from generate_series(?::date, ?::date, interval '1 day') as d(day)"
                + " left join (select "
                + DAY_T
                + " as work_day,"
                + AGG_COLUMNS
                + " from warehouse_work_task t where "
                + String.join(" and ", inner)
                + " group by 1) a on a.work_day = d.day::date"
                + " order by work_day",
            binds.toArray());

    var points = new ArrayList<LaborTrendPoint>();
    for (Record r : rows) {
      points.add(
          new LaborTrendPoint(
              r.get("work_day", LocalDate.class),
              r.get("task_count", Long.class),
              r.get("active_operators", Long.class),
              qty(r.get("picked_pieces", BigDecimal.class)),
              r.get("picked_orders", Long.class),
              dist(r.get("travel_distance_m", BigDecimal.class)),
              r.get("packing_task_count", Long.class),
              seconds(r.get("avg_packing_seconds", BigDecimal.class)),
              qty(r.get("reviewed_pieces", BigDecimal.class)),
              rate(r.get("review_accuracy", BigDecimal.class)),
              qty(r.get("error_pieces", BigDecimal.class)),
              r.get("error_orders", Long.class),
              rate(r.get("error_rate_by_piece", BigDecimal.class)),
              rate(r.get("error_rate_by_order", BigDecimal.class))));
    }
    return new LaborTrend(
        range.from(),
        range.to(),
        TIMEZONE,
        operatorUserId,
        operatorUserId == null ? "全员口径：区间内所有人的合计" : "个人口径：只看 " + operatorUserId,
        DAY_BASIS_NOTE,
        points);
  }

  // ============================================================ SQL 片段与工具

  /** 作业任务的查询列（含仓库编码快照 —— 报表上要能读懂是哪个仓）。 */
  private static final String TASK_COLUMNS =
      "t.id,t.code,t.work_type,t.source_module,t.reference_no,t.warehouse_id,w.code as warehouse_code,"
          + " t.operator_user_id,t.assigned_by_user_id,t.assigned_at,t.status,t.started_at,"
          + " t.completed_at,t.order_count,t.line_count,t.piece_count,t.travel_distance_m,"
          + " t.error_piece_count,t.error_order_count,t.exception_code,t.exception_note,"
          + " t.note,t.created_at,t.updated_at,"
          // 墙钟时长与「日」都在 SQL 里算：墙钟是本模块的时效口径（completed − started），
          // 「日」是聚合键，两者都不该在 Java 里另算一遍。
          + " case when t.started_at is not null and t.completed_at is not null"
          + "   then extract(epoch from (t.completed_at-t.started_at))::numeric(19,3) else null end"
          + " as duration_seconds,"
          + DAY_T
          + " as work_day";

  private static final String TASK_FROM =
      "warehouse_work_task t join warehouse w on w.tenant_id=t.tenant_id and w.id=t.warehouse_id";

  /** 只统计已定稿（COMPLETED / EXCEPTION）的任务：未开工、作业中的没有产出。 */
  private String baseWhere(Range range) {
    return " where t.tenant_id = ? and t.status in ('COMPLETED','EXCEPTION') and "
        + DAY_T
        + " between ?::date and ?::date";
  }

  private List<Object> baseBinds(Range range) {
    var binds = new ArrayList<Object>();
    binds.add(tenant());
    binds.add(range.from());
    binds.add(range.to());
    return binds;
  }

  /** 过滤条件片段（报表 / 排名 / 趋势共用）。 */
  private static String filters(UUID warehouseId, String workType) {
    var parts = new ArrayList<String>();
    if (warehouseId != null) parts.add(" and t.warehouse_id = ?");
    if (!blank(workType)) parts.add(" and t.work_type = ?");
    return String.join("", parts);
  }

  private static List<Object> filterBinds(UUID warehouseId, String workType) {
    var binds = new ArrayList<Object>();
    if (warehouseId != null) binds.add(warehouseId);
    if (!blank(workType)) binds.add(workType(workType).name());
    return binds;
  }

  /** 排名按人合计时的排序值表达式：人均日 = 该人合计 ÷ 该人的作业天数。 */
  private static String metricValue(LaborRankingMetric metric) {
    return switch (metric) {
      case PICKED_PIECES -> perPersonDay(PICKED_PIECES);
      case PICKED_ORDERS -> perPersonDay(PICKED_ORDERS);
      case TRAVEL_DISTANCE -> perPersonDay(TRAVEL_DISTANCE);
      case PACKING_SECONDS -> PACKING_AVG;
      case REVIEW_ACCURACY -> REVIEW_ACCURACY;
      case ERROR_RATE_BY_PIECE -> ERROR_RATE_PIECE;
      case ERROR_RATE_BY_ORDER -> ERROR_RATE_ORDER;
    };
  }

  private static BigDecimal rankValue(LaborRankingMetric metric, BigDecimal raw) {
    if (raw == null) return null;
    return switch (metric) {
      case PICKED_PIECES, PICKED_ORDERS, TRAVEL_DISTANCE -> raw.setScale(4, RoundingMode.HALF_UP);
      case PACKING_SECONDS -> raw.setScale(SECONDS_SCALE, RoundingMode.HALF_UP);
      case REVIEW_ACCURACY, ERROR_RATE_BY_PIECE, ERROR_RATE_BY_ORDER ->
          raw.setScale(RATE_SCALE, RoundingMode.HALF_UP);
    };
  }

  /** 排名指标的口径句（定义 + 分母），随排名一起返回：争议时第一眼就能看到分母是什么。 */
  private String metricBasisOf(LaborRankingMetric metric) {
    for (var b : metricBasis()) {
      if (b.metric().equals(metric.name())) {
        return b.definition() + " 分母：" + b.denominator();
      }
    }
    return "";
  }

  /** 区间默认值：最近 14 天（两个自然周），落在「日」口径内。 */
  private record Range(LocalDate from, LocalDate to) {}

  private Range range(LocalDate from, LocalDate to) {
    var end = to == null ? LocalDate.now(ZONE) : to;
    var start = from == null ? end.minusDays(13) : from;
    if (start.isAfter(end)) {
      throw bad("开始日期 " + start + " 不能晚于结束日期 " + end + "。");
    }
    if (start.plusDays(MAX_TREND_DAYS).isBefore(end)) {
      throw bad("区间跨度不能超过 " + MAX_TREND_DAYS + " 天：超过这个跨度就不叫趋势，" + "而是一次会把连接池占住的全表聚合。");
    }
    return new Range(start, end);
  }

  /** 把额外绑定追加到已有绑定序列末尾（cast 永远不在末位，调用方据此排顺序）。 */
  private static List<Object> append(List<Object> binds, Object... more) {
    binds.addAll(List.of(more));
    return binds;
  }

  // ============================================================ 映射与校验

  private List<LaborWorkTask> map(List<? extends Record> rows) {
    var out = new ArrayList<LaborWorkTask>();
    for (Record r : rows) {
      out.add(taskOf(r));
    }
    return out;
  }

  private LaborWorkTask taskOf(Record r) {
    return new LaborWorkTask(
        r.get("id", UUID.class),
        r.get("code", String.class),
        LaborWorkType.valueOf(r.get("work_type", String.class)),
        r.get("source_module", String.class),
        r.get("reference_no", String.class),
        r.get("warehouse_id", UUID.class),
        r.get("warehouse_code", String.class),
        r.get("operator_user_id", UUID.class),
        r.get("assigned_by_user_id", UUID.class),
        r.get("assigned_at", OffsetDateTime.class),
        WorkTaskStatus.valueOf(r.get("status", String.class)),
        r.get("started_at", OffsetDateTime.class),
        r.get("completed_at", OffsetDateTime.class),
        r.get("order_count", Integer.class),
        r.get("line_count", Integer.class),
        qty(r.get("piece_count", BigDecimal.class)),
        r.get("travel_distance_m", BigDecimal.class),
        qty(r.get("error_piece_count", BigDecimal.class)),
        r.get("error_order_count", Integer.class),
        r.get("exception_code", String.class),
        r.get("exception_note", String.class),
        seconds(r.get("duration_seconds", BigDecimal.class)),
        r.get("work_day", LocalDate.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private List<LaborWorkEvent> timelineOf(List<? extends Record> rows) {
    var out = new ArrayList<LaborWorkEvent>();
    for (Record r : rows) {
      out.add(
          new LaborWorkEvent(
              r.get("id", UUID.class),
              r.get("task_id", UUID.class),
              r.get("event_type", String.class),
              r.get("actor_user_id", UUID.class),
              r.get("occurred_at", OffsetDateTime.class),
              r.get("exception_code", String.class),
              r.get("detail", String.class)));
    }
    return out;
  }

  /** 写一条事件（append-only）。事件与状态变化在同一事务里，缺一条时间线就是残的。 */
  private void event(
      UUID taskId, String type, UUID actorUserId, String detail, String exceptionCode) {
    sql.execute(
        "insert into warehouse_work_event(id,tenant_id,task_id,event_type,actor_user_id,occurred_at,"
            + " exception_code,detail) values(?,?,?,?,?,?::timestamptz,?,?)",
        UUID.randomUUID(),
        tenant(),
        taskId,
        type,
        actorUserId,
        OffsetDateTime.now(ZONE),
        exceptionCode,
        detail);
  }

  /** 作业任务的存在性与读取：一次查询取全列（含派生列），跨租户在 RLS 下查不到 → 404。 */
  private LaborWorkTask requireTask(UUID taskId) {
    var r =
        sql.fetchOne(
            "select " + TASK_COLUMNS + " from " + TASK_FROM + " where t.tenant_id = ? and t.id = ?",
            tenant(),
            taskId);
    if (r == null) {
      throw notFound("作业任务", taskId);
    }
    return taskOf(r);
  }

  private void requireWarehouse(UUID warehouseId) {
    if (sql.fetchOne(
            "select id from warehouse where tenant_id = ? and id = ?", tenant(), warehouseId)
        == null) {
      throw notFound("仓库", warehouseId);
    }
  }

  private static LaborWorkType workType(String raw) {
    if (!notBlank(raw)) {
      throw bad("必须选择作业类型：拣货 / 打包 / 复核 —— 五个指标各自对应其中一类。");
    }
    try {
      return LaborWorkType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad(
          "未知的作业类型："
              + raw
              + "。可用值 "
              + java.util.Arrays.stream(LaborWorkType.values()).map(Enum::name).toList());
    }
  }

  private static LaborExceptionCode exceptionCode(String raw) {
    if (!notBlank(raw)) {
      throw bad("必须选择异常类型：缺货 / 设备故障 / 单据取消 / 安全暂停 / 其他。");
    }
    try {
      return LaborExceptionCode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad(
          "未知的异常类型："
              + raw
              + "。可用值 "
              + java.util.Arrays.stream(LaborExceptionCode.values()).map(Enum::name).toList());
    }
  }

  private static WorkTaskStatus status(String raw) {
    try {
      return WorkTaskStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad(
          "未知的任务状态："
              + raw
              + "。可用值 "
              + java.util.Arrays.stream(WorkTaskStatus.values()).map(Enum::name).toList());
    }
  }

  private static LaborRankingMetric rankingMetric(String raw) {
    if (!notBlank(raw)) {
      throw bad(
          "必须选择排名指标：七个指标方向不同（差错率越低越好、准确率越高越好），"
              + "混在一起排就是绩效争议的标准起手式。可用值 "
              + java.util.Arrays.stream(LaborRankingMetric.values()).map(Enum::name).toList());
    }
    try {
      return LaborRankingMetric.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad(
          "未知的排名指标："
              + raw
              + "。可用值 "
              + java.util.Arrays.stream(LaborRankingMetric.values()).map(Enum::name).toList());
    }
  }

  private static int pageSize(int size) {
    if (size <= 0) return 50;
    return Math.min(size, MAX_PAGE_SIZE);
  }

  private static int orderCount(Integer value) {
    if (value == null) return 0;
    if (value < 0) throw bad("涉及单量不能为负数。");
    return value;
  }

  private static int lineCount(Integer value) {
    if (value == null) return 0;
    if (value < 0) throw bad("作业行数不能为负数。");
    return value;
  }

  private static BigDecimal pieceCount(BigDecimal value) {
    if (value == null) return ZERO_QTY;
    if (value.signum() < 0) throw bad("作业件数不能为负数。");
    return value.setScale(6, RoundingMode.HALF_UP);
  }

  private static BigDecimal errorPieceCount(BigDecimal value) {
    if (value == null) return ZERO_QTY;
    if (value.signum() < 0) throw bad("差错件数不能为负数。");
    return value.setScale(6, RoundingMode.HALF_UP);
  }

  private static int errorOrderCount(Integer value) {
    if (value == null) return 0;
    if (value < 0) throw bad("差错单量不能为负数。");
    return value;
  }

  /** 行走距离只有拣货任务有值：其他类型带上会被拒绝，避免把「没走」和「走了 0 米」混成一条数。 */
  private static BigDecimal distance(LaborWorkType type, BigDecimal value) {
    if (value == null) return null;
    if (type != LaborWorkType.PICKING) {
      throw bad("行走距离只属于拣货作业：" + type.label() + "任务带 travelDistanceM 一定是录错了，" + "而它会把人效报表的行走距离算高。");
    }
    if (value.signum() < 0) throw bad("拣货行走距离不能为负数。");
    return value.setScale(3, RoundingMode.HALF_UP);
  }

  private static String requireText(String raw, int max, String message) {
    var v = raw == null ? "" : raw.trim();
    if (v.isEmpty()) throw bad(message);
    if (v.length() > max) throw bad("内容超过 " + max + " 个字，请写结论，不要整段粘贴。");
    return v;
  }

  private static String optional(String raw, int max) {
    var v = raw == null ? "" : raw.trim();
    if (v.length() > max) throw bad("内容超过 " + max + " 个字，请写结论，不要整段粘贴。");
    return v;
  }

  private static boolean notBlank(String raw) {
    return raw != null && !raw.trim().isEmpty();
  }

  private static boolean blank(String raw) {
    return raw == null || raw.trim().isEmpty();
  }

  private static String blankToNull(String raw) {
    return blank(raw) ? null : raw.trim();
  }

  /** 租户来自 RLS 的同一个来源，绝不来自任何请求参数或客户端头（与 7.4.2.1 / 7.4.2.2 同一句）。 */
  private UUID tenant() {
    return sql.fetchOne("select current_setting('app.current_tenant',true)::uuid as t")
        .get("t", UUID.class);
  }

  // ============================================================ 数值规范化

  private static BigDecimal qty(BigDecimal v) {
    return v == null ? ZERO_QTY : v.setScale(6, RoundingMode.HALF_UP);
  }

  private static BigDecimal dist(BigDecimal v) {
    return v == null ? ZERO_DIST : v.setScale(3, RoundingMode.HALF_UP);
  }

  private static BigDecimal seconds(BigDecimal v) {
    return v == null ? null : v.setScale(SECONDS_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * 率：<b>null 原样保留</b>（不可计算），不为 null 时规整到 4 位小数。
   *
   * <p>绝不用 {@code null → 0} 的写法：0% 会被读成「复核全对 / 零差错」， 而分母为 0 的真实含义是「今天没人复核」。这是本模块最容易出事的一处显示错误。
   */
  private static BigDecimal rate(BigDecimal v) {
    return v == null ? null : v.setScale(RATE_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal plain(BigDecimal v, int scale) {
    return v == null ? null : v.setScale(scale, RoundingMode.HALF_UP);
  }

  private static long count(Long v) {
    return v == null ? 0L : v;
  }

  private static ResponseStatusException notFound(String what, UUID id) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, what + "不存在或不属于当前租户：" + id);
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  /** 状态冲突用 409：它和「你填错了」(400) 是两类问题，混在一起前端无法给出不同提示。 */
  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  // ============================================================ 口径文本（逐字对外）

  static final String DAY_BASIS_NOTE =
      "「日」= 任务**完成时刻**在时区 "
          + TIMEZONE
          + " 下的自然日（聚合键 (completed_at at time zone '"
          + TIMEZONE
          + "')::date）。刻意不用服务器本地时区：同一份人效报表在两台机器上跑出"
          + "不同的「昨天」，绩效争议就无从谈起。索引也建在这个表达式上。"
          + "统计范围只含已定稿的任务（已完成 + 异常终止）：未开工、作业中的任务没有产出，"
          + "算成人效等于奖励「领了任务不干活」。";

  static final String PACKING_BASIS_NOTE =
      "打包时效 = 墙钟时长（完成时刻 − 开始时刻），**不是**净作业时长：净时长要剔除等待与中断，"
          + "需要另记暂停区间，本表没有那组数据，算出来的是假的。墙钟时长可复算 —— 任何人拿同一行"
          + "都能算出同一个数，这正是在争议里能被采信的理由。分母是打包**任务张数**，不是件数；"
          + "没有开工时刻的任务（异常终止、以及 8.3 批量补录）不计入，分子分母同时排除，不是算成 0。";

  static final String REVIEW_BASIS_NOTE =
      "复核准确率 = (复核件数 − 差错件数) ÷ 复核件数 × 100。分母是复核件数，不是复核单数、"
          + "也不是复核行数。差错记在**发现**它的那张任务上，所以这个口径下的准确率就是复核员"
          + "自己的准确率。分母为 0 时返回 null（不可计算）而不是 0%：0% 会被读成「复核全对」，"
          + "真实含义是「今天没人复核」。";

  static final String ERROR_BASIS_NOTE =
      "差错率有两个口径，按图索骥地给全：按件数 = Σ差错件数 ÷ Σ作业件数；"
          + "按单量 = Σ差错单量 ÷ Σ涉及单量。分母各自写明，不共用一个「总量」。"
          + "差错件数不可能超过作业件数（DB 级 CHECK 钉死）—— 超过它意味着口径本身错了，"
          + "而不是「这个人错得离谱」。order_count 由上游给出，是**该任务内**去重后的单据张数；"
          + "跨任务去重需要全量明细，不是这张汇总表的粒度能做的事，这里如实说明而不是假装做得到。";

  static final String PER_CAPITA_NOTE =
      "人均日 = 区间合计 ÷ (人数 × 作业天数)。人数 = 区间内有过完成作业的**不同操作人**个数；"
          + "作业天数 = 区间内出现过完成作业的**自然日**天数。刻意不用「人数 × 区间自然日数」"
          + "做分母：那样停工日、休假日会把人均拉低，而停工不是任何一个人的效率问题。"
          + "这条口径的代价也写在这里：区间首尾的不完整日会被当成完整日计入，"
          + "所以它是偏乐观的估算 —— 争议时先承认这一点，比事后被发现好。";

  static final String ERROR_VS_EXCEPTION_NOTE =
      "差错 ≠ 异常。差错（拣错、漏拣、打错包装）是「做错了」，进差错率；"
          + "异常（缺货、设备故障、单据取消、安全暂停）是「做不了」，进异常、**不进差错率**。"
          + "混在一起会让遇上缺货的人被算成错得最离谱 —— 这是排班时最不公平的一种算法，"
          + "所以两者在本模块里是两个字段、两套端点、两个口径。";

  static final String UPSTREAM_NOTE =
      "蓝图的上游是「8.3 全部作业记录」，而 8.3 目前整块未交付（集成头里没有任何出库/拣货/"
          + "复核/打包/发货表）。所以作业记录这张事实表由本模块自己落地并自己写入，"
          + "而不是等上游空转；8.3 各车道落地后把作业事件投递到本服务的端点（sourceModule 标明来源），"
          + "无需改表。补录类事件（没有开工时刻）在打包时效里是 null 而不是 0 秒 —— "
          + "「没有可算的时效」与「时效为零」是两件事。";

  static final String OWNERSHIP_NOTE =
      "warehouse_work_task 与 warehouse_work_event 的唯一写入所有者是本模块（每张事实表只有一个写入者）。"
          + "本模块不读、不建任何 8.3 的拣货/复核/打包表，那是 8.3.2.1 / 8.3.3.1 / 8.3.3.2 各自车道的事。";
}
