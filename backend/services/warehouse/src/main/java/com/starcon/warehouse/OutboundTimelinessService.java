package com.starcon.warehouse;

import static com.starcon.warehouse.OutboundTimelinessModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 8.5.3.1 出库时效：订单时刻投影写入 + 四环节分布 + 瓶颈识别 + 超时明细。
 *
 * <p>蓝图：「<b>统计出库各环节时效：订单到出库时长、拣货时长、打包时长、交接时长，识别超时环节与瓶颈。</b>」输出：「<b>时效分布 + 瓶颈环节 + 超时明细</b>。」以及红线：「<b>时效数据直接对应平台迟发率指标</b>，超时会导致绩效违规与搜索降权。」
 *
 * <p><b>三处最容易被做错、且做错了会误导人的地方，都在本文件里显式处理：</b>
 *
 * <ul>
 *   <li><b>四环节各走一张独立的标量子查询，不 join 展开。</b>拣货（V110 pick_task）对出库单是 UNIQUE，打包（V121 packing_order）经 recount_session 关联、交接（V123 shipment_handover）也 UNIQUE —— 三张表分别 join 到 outbound_order 上会把同一张出库单放大成多行，而 {@code count(*)} 放大的分母会让「平均时长」凭空变小。这与 8.1.1.1 / 8.1.2.1 / 8.5.2.1 同一条纪律：每份聚合各走独立查询。
 *   <li><b>终点未发生 = null，不是 0。</b>还没发货的单，「订单到出库时长」是<b>不可计算</b>（没有终点），不是 0 分钟。夹成 0 会让「这单还没发」显示成「这单超快」， 直接误导迟发率判断。这些单数单独进 {@code missingSampleCounts}。
 *   <li><b>负时长 = null，不夹成 0。</b>交接时刻早于建单时刻是上游数据错误；夹成 0 会把「时间戳错了」显示成「这一单超快」。
 * </ul>
 *
 * <p><b>时长是分钟 + BigDecimal，全程禁浮点。</b>SQL 里 {@code extract(epoch from (b-a))/60} 先转 {@code ::numeric(19,6)} 再落 Java；分位数用 {@code percentile_cont}（分布统计的标准做法），立刻 cast 回 numeric —— 浮点只允许出现在 PG 内部的插值里，不进入存储与传输。
 *
 * <p><b>时间绑定纪律</b>：{@code ?::timestamptz} 一律出现在语句最前面的 {@code t} CTE 里（与 V55 / V59 / V62 / V74 同一条纪律），后面全是普通绑定。写入用 {@link OffsetDateTime}，不走 {@code Timestamp.valueOf(LocalDateTime)} —— 后者按 JVM 本地时区解释裸本地时间，在东八区会静默偏 −28800s。
 *
 * <p><b>租户谓词每条查询都写。</b>RLS（{@code app.current_tenant}）是第一道防线，显式 {@code tenant_id=} 是第二道。
 */
@Service
public class OutboundTimelinessService {

  /** 单页最大行数：报表一行 = 一张出库单，设上限防止参数写错拉爆内存。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 区间上限 366 天：超过这个跨度，「时效分布」已经没有人看了，聚合还会把连接池占住。 */
  static final int MAX_RANGE_DAYS = 366;

  /** 分钟保留 6 位小数（numeric(19,6) 口径）：1.5 分钟与 1 分 30 秒不能被截成同一个数。 */
  private static final int MINUTE_SCALE = 6;

  /** 分位数同样保留 6 位；线性插值结果落库前 cast 回 numeric。 */
  private static final BigDecimal ZERO_MINUTE = new BigDecimal("0.000000");

  /** 比率保留 4 位小数（0.0001%），够读且可复核。 */
  private static final int RATE_SCALE = 4;

  private static final ZoneId ZONE = ZoneId.of(TIMEZONE);

  /**
   * 内置兜底阈值（分钟）—— <b>只在租户与仓库都还没配置时生效</b>。
   *
   * <p>刻意不写死：它们对外出现在 {@code /capabilities} 的 {@code builtinThresholds} 里， 且每个被解析出的阈值都带 {@code source=BUILTIN}。看到 40 分钟这个数时， 必须能回答「这是谁定的」—— 否则调完阈值没人知道有没有生效。
   */
  private static final Map<String, BigDecimal> BUILTIN_THRESHOLDS =
      Map.of(
          "ORDER_TO_OUTBOUND", new BigDecimal("1440.000000"), // 24 小时：平台截单时间的粗口径
          "PICKING", new BigDecimal("240.000000"), // 4 小时
          "PACKING", new BigDecimal("120.000000"), // 2 小时
          "HANDOVER", new BigDecimal("120.000000")); // 2 小时

  private static final String STAGE_BASIS_NOTE =
      "四个环节按蓝图逐字取：订单到出库时长（order_placed_at → outbound_order.shipped_at）、"
          + "拣货时长（pick_task.started_at → completed_at）、打包时长（packing_order.started_at → completed_at）、"
          + "交接时长（shipment_handover.created_at → handover_at）。三个内部环节的时间戳取自 8.3 各车道"
          + "（V110 / V121 / V123），本模块只读不投影；订单下单时刻属 trade 服务，只能由上游投递到本服务"
          + "（V148 表① outbound_order_time_snapshot），本车道是该表唯一写入所有者。";

  private static final String WALL_CLOCK_BASIS_NOTE =
      "时长是**墙钟时长**（终点 − 起点），不是净作业时长。净时长需要剔除等待/中断，"
          + "那要求另记暂停区间，系统里没有那组数据，算出来的是假的。墙钟时长可复算：两个时间戳都在库里，"
          + "任何人拿同一行都能算出同一个数。单位是分钟（numeric(19,6) + BigDecimal，全程禁浮点）。";

  private static final String MISSING_SAMPLE_BASIS_NOTE =
      "终点尚未发生的单子，该环节时长是**null（不可计算），不是 0**：还没发货的单没有终点，"
          + "夹成 0 会让「这单还没发」显示成「这单超快」。这些单数单独报在 missingSampleCounts 里，"
          + "不混进分布的分母 —— 「今天时效很好」有可能只是「货还没发出去」。";

  private static final String BOTTLENECK_BASIS_NOTE =
      "瓶颈只在**内部三环节**（拣货/打包/交接）里选，判定依据是「该环节的超时率最高，"
          + "同率比平均时长」。订单到出库是总时长，恒大于其任一环节，把它算进排名会让"
          + "「瓶颈永远是订单到出库」这条结论恒为零 —— 总时长单列在分布里，不参与排名。"
          + "候选为空（区间内没有任何内部环节有样本）时瓶颈为 null，不给一个占位环节。";

  private static final String OVERDUE_BASIS_NOTE =
      "超时判定用**生效阈值**（仓库覆盖 > 租户默认 > 内置兜底，见 thresholdSource），不是内置兜底。"
          + "明细定位到「具体出库单 + 具体环节」，因为本模块的数直接对应平台迟发率指标，"
          + "绩效申诉时需要的是「哪一单、哪一环、超了多少分钟」，不是一句「平均超时」。";

  private static final String ORDER_TIMESTAMP_SOURCE_NOTE =
      "订单下单时刻属 9 订单（trade 服务），本服务**读不到**（禁止跨服务 SQL / 跨库 FK / 共享视图）。"
          + "本服务维护自己的投影 outbound_order_time_snapshot，由上游投递到 "
          + "POST /warehouse/outbound-timeliness/order-snapshots（幂等 upsert）。"
          + "未投递时「订单到出库时长」为 null 并计入缺失样本数 —— 本模块不代填 now()、"
          + "也不用出库单创建时刻顶替：编一个看起来能用的时效比没有时效危险得多。";

  private static final String UPSTREAM_NOTE =
      "上游 = 9 订单（下单时刻，经授权端点投递）、8.3 出库作业（V72 出库单 / V110 拣货 / V121 打包 / V123 交接）；"
          + "下游 = 1 工作台履约健康。9 订单的服务侧时效分析是 9.5.1.1（trade 服务自己的模块），"
          + "与本模块正交：它算订单维度的承诺与迟发，本模块算仓库内部四个作业环节的用时。";

  private static final String OWNERSHIP_NOTE =
      "本车道是 outbound_order_time_snapshot 与 outbound_timeliness_threshold 的唯一写入所有者。"
          + "「每张事实表只有一个写入所有者」在这里是可执行的边界：8.3 各车道写自己的作业表，"
          + "本模块只读它们；trade 投递时刻而不直接写本服务数据库。";

  private final DSLContext sql;

  public OutboundTimelinessService(DSLContext sql) {
    this.sql = sql;
  }

  // ============================================================ 能力与口径

  /** 四环节能力 + 生效阈值 + 全部口径说明。 */
  public OutboundTimelinessCapabilities capabilities(UUID warehouseId) {
    List<OutboundStageOption> stages = new ArrayList<>();
    for (var s : OutboundTimelinessStage.values()) {
      stages.add(new OutboundStageOption(s.name(), s.label(), s.description(), s.isInternal()));
    }
    if (warehouseId != null) {
      requireWarehouse(warehouseId);
    }
    return new OutboundTimelinessCapabilities(
        stages,
        builtinThresholds(),
        thresholds(warehouseId),
        warehouseId,
        TIMEZONE,
        STAGE_BASIS_NOTE,
        WALL_CLOCK_BASIS_NOTE,
        MISSING_SAMPLE_BASIS_NOTE,
        BOTTLENECK_BASIS_NOTE,
        OVERDUE_BASIS_NOTE,
        ORDER_TIMESTAMP_SOURCE_NOTE,
        UPSTREAM_NOTE,
        OWNERSHIP_NOTE);
  }

  /** 内置兜底阈值（对外声明，让人知道「没人配过时按什么算」）。 */
  public List<OutboundThresholdView> builtinThresholds() {
    List<OutboundThresholdView> out = new ArrayList<>();
    for (var s : OutboundTimelinessStage.values()) {
      out.add(
          new OutboundThresholdView(
              s.name(),
              s.label(),
              BUILTIN_THRESHOLDS.get(s.name()),
              ThresholdSource.BUILTIN.name(),
              ThresholdSource.BUILTIN.label(),
              null,
              "尚未配置时的兜底值；配置后本行会被仓库覆盖或租户默认取代。"));
    }
    return out;
  }

  /**
   * 生效阈值解析：仓库覆盖 &gt; 租户默认 &gt; 内置兜底。
   *
   * <p>两个优先级用<b>一条</b> SQL 按显式优先级排序取第一行，而不是分两次查询再在 Java 里覆盖 —— 后者读起来像「先取仓库再取租户」， 很容易在改动时退化成「后取的把先取的盖掉」。
   */
  public List<OutboundThresholdView> thresholds(UUID warehouseId) {
    var rows =
        sql.fetch(
            "select stage_code,threshold_minutes,source,source_label,enabled,note from ("
                + "select t.stage_code,t.threshold_minutes,"
                + "'WAREHOUSE' as source,'仓库覆盖' as source_label,"
                + "t.enabled,t.note,1 as prio"
                + " from outbound_timeliness_threshold t"
                + " where t.tenant_id = ? and t.warehouse_id = ? and t.enabled"
                + " union all"
                + " select t.stage_code,t.threshold_minutes,"
                + "'TENANT' as source,'租户默认' as source_label,"
                + "t.enabled,t.note,2 as prio"
                + " from outbound_timeliness_threshold t"
                + " where t.tenant_id = ? and t.warehouse_id is null and t.enabled"
                + ") e join ("
                + " select unnest(array['ORDER_TO_OUTBOUND','PICKING','PACKING','HANDOVER']) as sc"
                + ") s on s.sc = e.stage_code"
                + " order by e.stage_code, e.prio",
            tenant(),
            warehouseId,
            tenant());

    Map<String, Record> byStage = new LinkedHashMap<>();
    for (Record r : rows) {
      byStage.putIfAbsent(r.get("stage_code", String.class), r);
    }
    List<OutboundThresholdView> out = new ArrayList<>();
    for (var s : OutboundTimelinessStage.values()) {
      var r = byStage.get(s.name());
      if (r == null) {
        out.add(
            new OutboundThresholdView(
                s.name(),
                s.label(),
                BUILTIN_THRESHOLDS.get(s.name()),
                ThresholdSource.BUILTIN.name(),
                ThresholdSource.BUILTIN.label(),
                null,
                "尚未配置时的兜底值；配置后本行会被仓库覆盖或租户默认取代。"));
      } else {
        out.add(
            new OutboundThresholdView(
                s.name(),
                s.label(),
                minutes(r.get("threshold_minutes", BigDecimal.class)),
                r.get("source", String.class),
                r.get("source_label", String.class),
                r.get("enabled", Boolean.class),
                r.get("note", String.class)));
      }
    }
    return out;
  }

  // ============================================================ 写入：订单时刻投影

  /**
   * 投递订单下单时刻（幂等 upsert）。
   *
   * <p>幂等语义：同一 (租户, 出库单) 重复投递<b>覆盖</b>，不产生第二行。trade 的订单补录/改期是真实运营动作，需要能改； 但这里只留「当前可信的下单时刻」， 不是事件流水 —— 真要历史应去 trade 查订单事件日志。
   */
  @Transactional
  public OutboundTimelinessRow recordOrderTime(OutboundOrderTimeInput input, UUID actorUserId) {
    var outboundOrderId = input.outboundOrderId();
    if (outboundOrderId == null) {
      throw bad("必须指定出库单：时效逐出库单计算，分仓/分批会让一张订单有多张出库单。");
    }
    if (input.orderPlacedAt() == null) {
      throw bad(
          "必须提供订单下单时刻（orderPlacedAt）：它是「订单到出库时长」的起点，"
              + "本服务读不到 trade 的订单表，只能由上游投递；代填会把迟发率算成假的。");
    }
    var source = sourceType(input.sourceType());
    var module = requireText(input.sourceModule(), 32, "必须填来源模块号：数字起争议时第一个要回答的是「这个时刻是谁报上来的」。");

    // 出库单必须存在且属于本租户：跨租户在 RLS 下查不到 → 404，不给「猜 id」的侧信道。
    var order =
        sql.fetchOne(
            "select source_type,source_external_ref,order_code from outbound_order"
                + " where tenant_id = ? and id = ?",
            tenant(),
            outboundOrderId);
    if (order == null) {
      throw notFound("出库单", outboundOrderId);
    }
    // 来源类型必须与出库单一致：同一个「订单时刻」被贴到另一类单上会算出一条不存在的时效。
    if (!order.get("source_type", String.class).equals(source.name())) {
      throw bad(
          "来源类型与出库单不一致：出库单是 "
              + order.get("source_type", String.class)
              + "，你报的是 "
              + source.name()
              + "。");
    }

    sql.execute(
        "insert into outbound_order_time_snapshot"
            + "(id,tenant_id,outbound_order_id,source_type,order_placed_at,source_module,"
            + " reported_by_user_id,created_at,updated_at)"
            + " values(?,?,?,?,?::timestamptz,?,?,now(),now())"
            + " on conflict (tenant_id,outbound_order_id) do update set"
            + " order_placed_at=excluded.order_placed_at,"
            + " source_module=excluded.source_module,"
            + " reported_by_user_id=excluded.reported_by_user_id,"
            + " updated_at=now()",
        UUID.randomUUID(),
        tenant(),
        outboundOrderId,
        source.name(),
        input.orderPlacedAt(),
        module,
        actorUserId);

    var views = thresholds(null);
    return rowOf(requireRow(outboundOrderId), views);
  }

  /** 配置阈值（幂等 upsert；warehouseId 为空 = 租户默认档）。 */
  @Transactional
  public OutboundThresholdView configureThreshold(
      OutboundThresholdInput input, UUID actorUserId) {
    var stage = stage(input.stageCode());
    if (input.thresholdMinutes() == null) {
      throw bad("必须给出阈值分钟数：阈值是本模块判定超时与瓶颈的唯一依据，不能留空。");
    }
    if (input.thresholdMinutes().signum() < 0) {
      throw bad("阈值不能为负数：负数没有任何业务含义。");
    }
    var value = input.thresholdMinutes().setScale(MINUTE_SCALE, RoundingMode.HALF_UP);
    var note = optional(input.note(), 300);
    var enabled = input.enabled() == null || input.enabled();
    var warehouseId = input.warehouseId();
    if (warehouseId != null) {
      requireWarehouse(warehouseId);
    }
    sql.execute(
        "insert into outbound_timeliness_threshold"
            + "(id,tenant_id,warehouse_id,stage_code,threshold_minutes,enabled,note,"
            + " created_by_user_id,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,now(),now())"
            + " on conflict (tenant_id,warehouse_id,stage_code) do update set"
            + " threshold_minutes=excluded.threshold_minutes,"
            + " enabled=excluded.enabled,"
            + " note=excluded.note,"
            + " created_by_user_id=excluded.created_by_user_id,"
            + " updated_at=now()",
        UUID.randomUUID(),
        tenant(),
        warehouseId,
        stage.name(),
        value,
        enabled,
        note,
        actorUserId);

    for (var v : thresholds(warehouseId)) {
      if (v.stageCode().equals(stage.name())) {
        return v;
      }
    }
    // 启用=false 时上面解析不到这一档（查询只取 enabled），如实报停用而不是编一个生效值。
    return new OutboundThresholdView(
        stage.name(),
        stage.label(),
        value,
        warehouseId == null ? ThresholdSource.TENANT.name() : ThresholdSource.WAREHOUSE.name(),
        warehouseId == null ? ThresholdSource.TENANT.label() : ThresholdSource.WAREHOUSE.label(),
        enabled,
        enabled ? null : "本档已停用：不参与生效阈值解析，报表将回落到下一优先级或内置兜底。");
  }

  // ============================================================ 报表

  /**
   * 出库时效报表：分布 + 瓶颈 + 超时明细。
   *
   * <p>窗口按已发货出库单（{@code outbound_order.shipped_at}）落在 {@code [from, to]} 取 —— 以<b>发货时刻</b>而非建单时刻为聚合键：一张 3 天前建、今天才发的单，迟发与否是今天的事。
   */
  public OutboundTimelinessReport report(
      LocalDate from, LocalDate to, UUID warehouseId, int page, int size) {
    var range = range(from, to);
    // 仓库不存在与不属于本租户都 404：两者可观测结果必须相同，否则「拿别的租户的
    // warehouseId 试一下」就成了探测别的租户有没有仓库的侧信道。
    // 放在最前面，也顺带挡住「用不存在的仓库 id 换到一份空报表」这种读法。
    if (warehouseId != null) {
      requireWarehouse(warehouseId);
    }
    var views = thresholds(warehouseId);

    var shippedIds = shippedOrderIds(range, warehouseId);
    var rows = new ArrayList<OutboundTimelinessRow>();
    // 完整的投影语句必须是「select + 列清单 + from 子句」，三段缺一不可：
    // 只传 ROW_FROM 的话 SQL 根本没有 SELECT 子句，查询在运行期才炸。
    var rowStatement = "select " + ROW_COLUMNS + ROW_FROM;
    for (var id : shippedIds) {
      // 租户谓词打在 o 上（ROW_FROM 里 o 就是 outbound_order）—— 别名写错一个字母
      // 整份报表就查不出来，而它是这个模块唯一的读路径。
      var r = sql.fetchOne(rowStatement + " where o.tenant_id = ? and o.id = ?", tenant(), id);
      if (r != null) {
        rows.add(rowOf(r, views));
      }
    }

    var distribution = distribution(rows, views, shippedOrderCount(range, warehouseId));
    var overdueDetails = overdueDetails(rows);
    var bottleneck = bottleneck(distribution);
    var missing = missingCounts(rows, shippedOrderCount(range, warehouseId));

    long overdueOrders = rows.stream().filter(r -> r.overdueStageCount() > 0).count();
    BigDecimal overdueOrderRate =
        rows.isEmpty() ? null : rate(percent(overdueOrders, rows.size()));

    int ps = pageSize(size);
    var sortedOverdue =
        overdueDetails.stream()
            .sorted(
                Comparator.comparing(
                        (OutboundOverdueDetail d) ->
                            d.excessMinutes() == null ? BigDecimal.ZERO : d.excessMinutes())
                    .reversed()
                    .thenComparing(OutboundOverdueDetail::occurredAt, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(OutboundOverdueDetail::outboundOrderId))
            .toList();
    var pageItems = sortedOverdue.stream().skip((long) Math.max(0, page) * ps).limit(ps).toList();

    return new OutboundTimelinessReport(
        range.from(),
        range.to(),
        TIMEZONE,
        "聚合键 = 已发货出库单的 shipped_at 在 " + TIMEZONE + " 下的自然日；"
            + "刻意不跟服务器本地时区走 —— 同一份报表在两台机器上跑出不同的「昨天」不可接受。",
        warehouseId == null ? null : warehouseId.toString(),
        warehouseId == null ? null : warehouseLabel(warehouseId),
        rows.size(),
        overdueOrders,
        overdueOrderRate,
        views,
        distribution,
        bottleneck,
        new OutboundOverduePage(pageItems, sortedOverdue.size(), Math.max(0, page), ps),
        rows,
        rows.size(),
        Math.max(0, page),
        ps,
        missing);
  }

  // ============================================================ SQL

  /** 「日」的聚合键：发货时刻在 Asia/Shanghai 下的自然日。写死时区，理由见 dayBasis。 */
  private static final String DAY = "(o.shipped_at at time zone '" + TIMEZONE + "')::date";

  /**
   * 逐单时长 SQL —— <b>四张标量子查询，绝不 join 展开</b>。
   *
   * <p>三个内部环节各来自一张独立的 8.3 表。join 展开会把同一张出库单放大成多行， 放大的分母会让平均时长凭空变小（8.1.1.1 / 8.1.2.1 / 8.5.2.1 反复出现过的同一个事故形状）。
   *
   * <p>{@code nullif(...)} + {@code case} 处理两件事：终点未发生（终点为 null）与**负时长**（终点早于起点）。 两者都返回 null 而不是 0。
   */
  private static final String STAGE_EXPR =
      """
      case when %1$s is null or %2$s is null then null
           when %2$s < %1$s then null
           else round((extract(epoch from (%2$s - %1$s))/60.0)::numeric, 6)
      end""";

  private static String minutes(String startExpr, String endExpr) {
    return STAGE_EXPR.formatted(startExpr, endExpr);
  }

  private static final String PICK_START = "(select p.started_at from pick_task p"
      + " where p.tenant_id = o.tenant_id and p.outbound_order_id = o.id"
      + " and p.status = 'COMPLETED' limit 1)";

  private static final String PICK_END = "(select p.completed_at from pick_task p"
      + " where p.tenant_id = o.tenant_id and p.outbound_order_id = o.id"
      + " and p.status = 'COMPLETED' limit 1)";

  private static final String PACK_START = "(select pk.started_at from packing_order pk"
      + " where pk.tenant_id = o.tenant_id and pk.outbound_order_id = o.id"
      + " and pk.status = 'PACKED' limit 1)";

  private static final String PACK_END = "(select pk.completed_at from packing_order pk"
      + " where pk.tenant_id = o.tenant_id and pk.outbound_order_id = o.id"
      + " and pk.status = 'PACKED' limit 1)";

  private static final String HANDOVER_START = "(select h.created_at from shipment_handover h"
      + " where h.tenant_id = o.tenant_id and h.outbound_order_id = o.id"
      + " and h.status = 'HANDED_OVER' limit 1)";

  private static final String HANDOVER_END = "(select h.handover_at from shipment_handover h"
      + " where h.tenant_id = o.tenant_id and h.outbound_order_id = o.id"
      + " and h.status = 'HANDED_OVER' limit 1)";

  private static final String ORDER_PLACED =
      "(select s.order_placed_at from outbound_order_time_snapshot s"
          + " where s.tenant_id = o.tenant_id and s.outbound_order_id = o.id)";

  /** 逐单投影 SELECT 片段（四环节时长列 + 各环节终点时刻）。 */
  private static final String ROW_COLUMNS =
      "o.id as outbound_order_id,o.order_code,o.source_type,o.source_external_ref,"
          + "o.warehouse_id,w.code as warehouse_code,o.shipped_at,"
          + ORDER_PLACED
          + " as order_placed_at,"
          + minutes(ORDER_PLACED, "o.shipped_at")
          + " as order_to_outbound_minutes,"
          + minutes(PICK_START, PICK_END)
          + " as picking_minutes,"
          + minutes(PACK_START, PACK_END)
          + " as packing_minutes,"
          + minutes(HANDOVER_START, HANDOVER_END)
          + " as handover_minutes,"
          + PICK_END
          + " as pick_completed_at,"
          + PACK_END
          + " as pack_completed_at,"
          + HANDOVER_END
          + " as handover_at";

  private static final String ROW_FROM =
      " from outbound_order o join warehouse w on w.tenant_id = o.tenant_id and w.id = o.warehouse_id";

  // ============================================================ 组装

  private OutboundTimelinessRow rowOf(Record r, List<OutboundThresholdView> views) {
    var thresholdByStage = new LinkedHashMap<String, BigDecimal>();
    for (var v : views) {
      thresholdByStage.put(v.stageCode(), v.thresholdMinutes());
    }
    var overdue = new ArrayList<String>();
    for (var s : OutboundTimelinessStage.values()) {
      var actual = minutesOf(r, s);
      if (actual == null) {
        continue;
      }
      var threshold = thresholdByStage.get(s.name());
      if (threshold != null && actual.compareTo(threshold) > 0) {
        overdue.add(s.name());
      }
    }
    return new OutboundTimelinessRow(
        r.get("outbound_order_id", UUID.class),
        r.get("order_code", String.class),
        r.get("source_type", String.class),
        r.get("source_external_ref", String.class),
        r.get("warehouse_id", UUID.class),
        r.get("warehouse_code", String.class),
        minutes(r.get("order_to_outbound_minutes", BigDecimal.class)),
        minutes(r.get("picking_minutes", BigDecimal.class)),
        minutes(r.get("packing_minutes", BigDecimal.class)),
        minutes(r.get("handover_minutes", BigDecimal.class)),
        overdue,
        overdue.size(),
        r.get("shipped_at", OffsetDateTime.class),
        r.get("order_placed_at", OffsetDateTime.class),
        r.get("pick_completed_at", OffsetDateTime.class),
        r.get("pack_completed_at", OffsetDateTime.class),
        r.get("handover_at", OffsetDateTime.class));
  }

  private static BigDecimal minutesOf(Record r, OutboundTimelinessStage stage) {
    return switch (stage) {
      case ORDER_TO_OUTBOUND -> r.get("order_to_outbound_minutes", BigDecimal.class);
      case PICKING -> r.get("picking_minutes", BigDecimal.class);
      case PACKING -> r.get("packing_minutes", BigDecimal.class);
      case HANDOVER -> r.get("handover_minutes", BigDecimal.class);
    };
  }

  /** 已组装的 {@link OutboundTimelinessRow} 上的同一取值口径（分布/明细/缺失计数共用）。 */
  private static BigDecimal minutesOf(OutboundTimelinessRow row, OutboundTimelinessStage stage) {
    return switch (stage) {
      case ORDER_TO_OUTBOUND -> row.orderToOutboundMinutes();
      case PICKING -> row.pickingMinutes();
      case PACKING -> row.packingMinutes();
      case HANDOVER -> row.handoverMinutes();
    };
  }

  /** 单条出库单（用于投影写入后回显）。 */
  private Record requireRow(UUID outboundOrderId) {
    var r =
        sql.fetchOne(
            "select " + ROW_COLUMNS + ROW_FROM + " where o.tenant_id = ? and o.id = ?",
            tenant(),
            outboundOrderId);
    if (r == null) {
      throw notFound("出库单", outboundOrderId);
    }
    return r;
  }

  /** 区间内已发货的出库单 id（聚合键 = shipped_at）。 */
  private List<UUID> shippedOrderIds(Range range, UUID warehouseId) {
    var binds = new ArrayList<Object>();
    binds.add(tenant());
    binds.add(range.start());
    binds.add(range.end());
    var warehouseClause = "";
    if (warehouseId != null) {
      warehouseClause = " and o.warehouse_id = ?";
      binds.add(warehouseId);
    }
    return sql.fetch(
        "select o.id from outbound_order o where o.tenant_id = ?"
            + " and o.shipped_at is not null and o.shipped_at >= ?::timestamptz"
            + " and o.shipped_at < (?::timestamptz + interval '1 day')"
            + warehouseClause
            + " order by o.shipped_at desc, o.id",
        binds.toArray()).getValues("id", UUID.class);
  }

  /** 区间内出库单总数（含未发货的）—— 缺失样本数的分母。 */
  private long shippedOrderCount(Range range, UUID warehouseId) {
    var binds = new ArrayList<Object>();
    binds.add(tenant());
    binds.add(range.start());
    binds.add(range.end());
    var warehouseClause = "";
    if (warehouseId != null) {
      warehouseClause = " and o.warehouse_id = ?";
      binds.add(warehouseId);
    }
    var r =
        sql.fetchOne(
            "select count(*)::bigint from outbound_order o where o.tenant_id = ?"
                + " and o.shipped_at is not null and o.shipped_at >= ?::timestamptz"
                + " and o.shipped_at < (?::timestamptz + interval '1 day')"
                + warehouseClause,
            binds.toArray());
    return r == null ? 0L : r.get(0, Long.class);
  }

  /** 四环节分布。样本已在 Java 侧取回（单页量级），分位数就地算，无需下推。 */
  private List<OutboundStageDistribution> distribution(
      List<OutboundTimelinessRow> rows, List<OutboundThresholdView> views, long orderCount) {
    var thresholdByStage = new LinkedHashMap<String, BigDecimal>();
    var sourceByStage = new LinkedHashMap<String, String>();
    for (var v : views) {
      thresholdByStage.put(v.stageCode(), v.thresholdMinutes());
      sourceByStage.put(v.stageCode(), v.source());
    }
    List<OutboundStageDistribution> out = new ArrayList<>();
    for (var stage : OutboundTimelinessStage.values()) {
      var samples = new ArrayList<BigDecimal>();
      for (var row : rows) {
        var v = minutesOf(row, stage);
        if (v != null) {
          samples.add(v);
        }
      }
      long missing = Math.max(0, orderCount - samples.size());
      BigDecimal threshold = thresholdByStage.get(stage.name());
      var sorted = samples.stream().sorted().toList();
      var overdue =
          sorted.stream().filter(v -> threshold != null && v.compareTo(threshold) > 0).count();

      out.add(
          new OutboundStageDistribution(
              stage.name(),
              stage.label(),
              "分钟",
              sorted.size(),
              missing,
              coverage(orderCount, sorted.size()),
              sorted.isEmpty() ? null : mean(sorted),
              sorted.isEmpty() ? null : percentile(sorted, 0.50),
              sorted.isEmpty() ? null : percentile(sorted, 0.90),
              sorted.isEmpty() ? null : sorted.get(sorted.size() - 1),
              threshold,
              sourceByStage.get(stage.name()),
              overdue,
              sorted.isEmpty() ? null : rate(percent(overdue, sorted.size())),
              buckets(sorted)));
    }
    return out;
  }

  /**
   * 时效分位桶：按**内置参考线**分档（不是阈值 —— 阈值按仓库/租户不同，桶要能跨仓比较）。
   *
   * <p>桶边界取仓库现场常见的量级：30 分钟内 / 2 小时内 / 8 小时内 / 24 小时内 / 超过 24 小时。
   */
  private static final List<BigDecimal> BUCKET_EDGES =
      List.of(
          new BigDecimal("30.000000"),
          new BigDecimal("120.000000"),
          new BigDecimal("480.000000"),
          new BigDecimal("1440.000000"));

  private static final String[] BUCKET_LABELS = {
    "30 分钟内", "2 小时内", "8 小时内", "24 小时内", "超过 24 小时"
  };

  private List<OutboundDistributionBucket> buckets(List<BigDecimal> sorted) {
    long[] counts = new long[BUCKET_LABELS.length];
    for (var v : sorted) {
      int idx = BUCKET_EDGES.size();
      for (int i = 0; i < BUCKET_EDGES.size(); i++) {
        if (v.compareTo(BUCKET_EDGES.get(i)) <= 0) {
          idx = i;
          break;
        }
      }
      counts[idx]++;
    }
    List<OutboundDistributionBucket> out = new ArrayList<>();
    for (int i = 0; i < BUCKET_LABELS.length; i++) {
      out.add(
          new OutboundDistributionBucket(
              BUCKET_LABELS[i], counts[i], rate(percent(counts[i], sorted.size()))));
    }
    return out;
  }

  /**
   * 瓶颈：只在内部三环节里选，先比超时率、再比平均时长。
   *
   * <p>候选为空时返回 null —— 不给一个占位环节。候选数为 1 时也返回它， 附带「只有一个环节有样本」的事实说明。
   */
  private OutboundBottleneck bottleneck(List<OutboundStageDistribution> distribution) {
    var candidates =
        distribution.stream()
            .filter(d -> OutboundTimelinessStage.valueOf(d.stageCode()).isInternal())
            .filter(d -> d.sampledCount() > 0)
            .toList();
    if (candidates.isEmpty()) {
      return new OutboundBottleneck(
          null,
          null,
          null,
          ZERO_MINUTE,
          ThresholdSource.BUILTIN.name(),
          null,
          0,
          0,
          "区间内没有任何内部环节有已完成的样本，无法判定瓶颈；"
              + "这不等于「没有瓶颈」，只说明这段时间还没有可测量的环节数据。");
    }
    var best =
        candidates.stream()
            .max(
                Comparator.<OutboundStageDistribution>comparingLong(d -> d.overdueCount())
                    .thenComparing(
                        d -> d.overdueRate() == null ? BigDecimal.ZERO : d.overdueRate())
                    .thenComparing(d -> d.avgMinutes() == null ? BigDecimal.ZERO : d.avgMinutes()))
            .orElseThrow();
    var stage = OutboundTimelinessStage.valueOf(best.stageCode());
    var basis =
        candidates.size() == 1
            ? "区间内只有「" + stage.label() + "」有已完成样本，瓶颈判定因此没有可比对象；"
                + "它只是唯一一个可测量的环节，不代表其它环节表现正常。"
            : "在内部三环节里，"
                + stage.label()
                + "的超时率最高（"
                + (best.overdueRate() == null ? "—" : best.overdueRate() + "%")
                + "，阈值 "
                + best.thresholdMinutes()
                + " 分钟，来源 "
                + ThresholdSource.valueOf(best.thresholdSource()).label()
                + "）；同率时比平均时长。总时长（订单到出库）不参与排名，理由见 bottleneckBasisNote。";
    return new OutboundBottleneck(
        best.stageCode(),
        best.stageLabel(),
        best.avgMinutes(),
        best.thresholdMinutes(),
        best.thresholdSource(),
        best.overdueRate(),
        best.overdueCount(),
        best.sampledCount(),
        basis);
  }

  /** 超时明细：逐单 × 逐环节定位。 */
  private List<OutboundOverdueDetail> overdueDetails(List<OutboundTimelinessRow> rows) {
    List<OutboundOverdueDetail> out = new ArrayList<>();
    for (var row : rows) {
      var views = thresholds(row.warehouseId());
      var thresholdByStage = new LinkedHashMap<String, BigDecimal>();
      var sourceByStage = new LinkedHashMap<String, String>();
      for (var v : views) {
        thresholdByStage.put(v.stageCode(), v.thresholdMinutes());
        sourceByStage.put(v.stageCode(), v.source());
      }
      for (var stage : OutboundTimelinessStage.values()) {
        var actual = minutesOf(row, stage);
        if (actual == null) {
          continue;
        }
        var threshold = thresholdByStage.get(stage.name());
        if (threshold == null || actual.compareTo(threshold) <= 0) {
          continue;
        }
        out.add(
            new OutboundOverdueDetail(
                row.outboundOrderId(),
                row.orderCode(),
                stage.name(),
                stage.label(),
                row.warehouseId(),
                row.warehouseCode(),
                actual,
                threshold,
                sourceByStage.get(stage.name()),
                actual.subtract(threshold),
                occurredAt(row, stage)));
      }
    }
    return out;
  }

  /** 超时发生时刻：各环节取它自己的终点，而不是一律报发货时刻。 */
  private static OffsetDateTime occurredAt(OutboundTimelinessRow row, OutboundTimelinessStage stage) {
    return switch (stage) {
      case ORDER_TO_OUTBOUND -> row.shippedAt();
      case PICKING -> row.pickCompletedAt();
      case PACKING -> row.packCompletedAt();
      case HANDOVER -> row.handoverAt();
    };
  }

  /** 各环节「终点未发生」的单数 —— 不混进分布分母，但要单独报。 */
  private Map<String, Long> missingCounts(List<OutboundTimelinessRow> rows, long orderCount) {
    Map<String, Long> out = new LinkedHashMap<>();
    for (var stage : OutboundTimelinessStage.values()) {
      long sampled = rows.stream().filter(r -> minutesOf(r, stage) != null).count();
      out.put(stage.name(), Math.max(0, orderCount - sampled));
    }
    return out;
  }

  // ============================================================ 统计工具

  private static BigDecimal mean(List<BigDecimal> sorted) {
    var sum = BigDecimal.ZERO;
    for (var v : sorted) {
      sum = sum.add(v);
    }
    return sum.divide(BigDecimal.valueOf(sorted.size()), MINUTE_SCALE, RoundingMode.HALF_UP);
  }

  /** 线性插值分位数（与 PG percentile_cont 同口径）。已在 Java 侧算，浮点不落库。 */
  private static BigDecimal percentile(List<BigDecimal> sorted, double q) {
    if (sorted.isEmpty()) {
      return null;
    }
    if (sorted.size() == 1) {
      return sorted.get(0).setScale(MINUTE_SCALE, RoundingMode.HALF_UP);
    }
    double pos = q * (sorted.size() - 1);
    int lo = (int) Math.floor(pos);
    int hi = (int) Math.ceil(pos);
    if (lo == hi) {
      return sorted.get(lo).setScale(MINUTE_SCALE, RoundingMode.HALF_UP);
    }
    var lower = sorted.get(lo);
    var upper = sorted.get(hi);
    var weight = BigDecimal.valueOf(pos - lo);
    return lower.add(upper.subtract(lower).multiply(weight)).setScale(MINUTE_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal coverage(long total, long sampled) {
    if (total <= 0) {
      return null;
    }
    return rate(percent(sampled, total));
  }

  private static BigDecimal percent(long part, long total) {
    if (total <= 0) {
      return null;
    }
    return BigDecimal.valueOf(part)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(total), RATE_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal rate(BigDecimal v) {
    return v == null ? null : v.setScale(RATE_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal minutes(BigDecimal v) {
    return v == null ? null : v.setScale(MINUTE_SCALE, RoundingMode.HALF_UP);
  }

  // ============================================================ 校验与工具

  private record Range(LocalDate from, LocalDate to, OffsetDateTime start, OffsetDateTime end) {}

  private Range range(LocalDate from, LocalDate to) {
    var f = from == null ? LocalDate.now(ZONE).minusDays(29) : from;
    var t = to == null ? LocalDate.now(ZONE) : to;
    if (t.isBefore(f)) {
      throw bad("结束日期不能早于开始日期。");
    }
    if (f.plusDays(MAX_RANGE_DAYS).isBefore(t)) {
      throw bad("统计区间不能超过 " + MAX_RANGE_DAYS + " 天：更长的跨度已经没有人看了。");
    }
    // 日窗口的边界在业务时区构造，再转成同一瞬间的时刻值绑定 ——
    // 不走 Timestamp.valueOf(LocalDateTime)（那条路在东八区会静默偏 −28800s）。
    var start = f.atStartOfDay(ZONE).toOffsetDateTime();
    var end = t.atStartOfDay(ZONE).toOffsetDateTime();
    return new Range(f, t, start, end);
  }

  private static OutboundTimelinessStage stage(String raw) {
    if (raw == null || raw.trim().isEmpty()) {
      throw bad("必须指定环节：四个环节各自独立配阈值，一次只配一个。");
    }
    try {
      return OutboundTimelinessStage.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad(
          "未知的环节："
              + raw
              + "。可用值 "
              + java.util.Arrays.stream(OutboundTimelinessStage.values())
                  .map(Enum::name)
                  .toList());
    }
  }

  private static OutboundSourceType sourceType(String raw) {
    if (raw == null || raw.trim().isEmpty()) {
      throw bad("必须指定来源类型：出库单是哪一类单驱动的（与 outbound_order.source_type 同一枚举）。");
    }
    try {
      return OutboundSourceType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad(
          "未知的来源类型："
              + raw
              + "。可用值 "
              + java.util.Arrays.stream(OutboundSourceType.values()).map(Enum::name).toList());
    }
  }

  /** 出库来源类型（与 V72 outbound_order.source_type 同一枚举，不另立一套）。 */
  private enum OutboundSourceType {
    SALES,
    PURCHASE_RETURN,
    TRANSFER,
    PRODUCTION
  }

  private void requireWarehouse(UUID warehouseId) {
    if (sql.fetchOne(
            "select id from warehouse where tenant_id = ? and id = ?", tenant(), warehouseId)
        == null) {
      throw notFound("仓库", warehouseId);
    }
  }

  private String warehouseLabel(UUID warehouseId) {
    var r =
        sql.fetchOne(
            "select code,name from warehouse where tenant_id = ? and id = ?",
            tenant(),
            warehouseId);
    return r == null ? null : r.get("code", String.class) + " " + r.get("name", String.class);
  }

  private static int pageSize(int size) {
    if (size <= 0) return 20;
    return Math.min(size, MAX_PAGE_SIZE);
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

  /** 租户来自 RLS 的同一个来源，绝不来自任何请求参数或客户端头。 */
  private UUID tenant() {
    return sql.fetchOne("select current_setting('app.current_tenant',true)::uuid as t")
        .get("t", UUID.class);
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException notFound(String what, UUID id) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, what + " " + id + " 不存在");
  }
}
