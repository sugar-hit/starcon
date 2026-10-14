package com.starcon.warehouse;

import static com.starcon.warehouse.PickWaveModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 8.3.2.2 波次与合并拣货：把多张出库单按规则合并成拣货波次，并跟踪波次进度。
 *
 * <p>蓝图 08 卷 8.3.2.2 的四问逐条对应：
 *
 * <ul>
 *   <li><b>职责</b>：按承运商/库区/订单数合并 → {@link #create} + {@link #candidates}；
 *       波次进度跟踪 → {@link #page} / {@link #detail} 里的 {@link PickWaveProgress}。
 *   <li><b>输入</b>：出库单集合 → 读 {@code pick_task}（V110，8.3.2.1 的唯一写入所有者）
 *       与 {@code outbound_order}（V72）；合并规则（承运渠道、库区、时段）→
 *       {@link #createRule} / {@link #rules} 维护的 {@code pick_wave_rule}。
 *   <li><b>输出</b>：波次任务 → {@code pick_wave}；各出库单在波次中的位置 →
 *       {@code pick_wave_task.position}。
 *   <li><b>关系</b>：上游 8.3.2.1 拣货任务；下游 8.3.3.1 复核。
 * </ul>
 *
 * <p><b>本模块不改订单粒度，这是全文件最重要的一条纪律。</b>蓝图关系段原文：
 * 「合并只影响拣货效率，不改变订单粒度：波次完成后仍需按订单拆分复核与打包。」
 * 所以本模块<b>一个字都没改</b> V110 的六张表 —— 实拣、异常、完成拣货全都在 8.3.2.1 那里，
 * 波次只是把若干张工单排成「一起走」。{@link #complete} 也不会替任何一张工单收尾：
 * V113 的 {@code guard_pick_wave_status_fn} 会回表检查成员是否全部终态，
 * 少一条就抛 23514（绕开服务层直连 SQL 也一样）。
 *
 * <p><b>三个合并维度分别落在哪</b>（蓝图「按承运商/库区/订单数合并」）：
 *
 * <ol>
 *   <li><b>承运渠道</b>：{@code outbound_order.carrier_code}，冻结进
 *       {@code pick_wave_task.carrier_code}。出库单的承运渠道在待发货前可以改，
 *       而「这两张单因为同一个承运商凑进一波」是已经发生的排班事实。
 *   <li><b>库区</b>：仓库里没有 {@code zone} 这一列 —— 库区是从取货库位沿 V105
 *       库位树（{@code parent_id}）上行到<b>顶层根节点</b>得到的。V105 的注释把
 *       「父库位为 NULL」的顶层节点直接叫作「区」，本模块沿用同一口径，
 *       所以「按库区合并」与 8.1.2.1 的现场层级认知是同一套。
 *       <b>横跨多个区的拣货任务没有单一库区</b>（{@code crossZone}），
 *       只能进「库区不限」的规则。
 *   <li><b>订单数</b>：{@code pick_wave_rule.max_order_count}，硬上限。撞顶之后剩下的
 *       出库单<b>等这个波次收工</b>才能开下一波（V113 §2：同键只能有一个未完波次），
 *       所以 {@link #candidates} 必须把「在等上一波收工」这个数报出来。
 * </ol>
 *
 * <p><b>时段用仓本地小时而不是 UTC</b>：拣货班次按仓库所在地的作息排，
 * 「下午班」在两个时区的仓库里不是同一批货。时区取 {@code warehouse.timezone}（V1/V69）。
 */
@Service
public class PickWaveService {

  private static final int MAX_PAGE_SIZE = 200;

  /** 排序白名单。与 PickTaskService / WarehouseDetailService 同一纪律。 */
  private static final List<String> SORTS =
      List.of("PLANNED_PICK_AT", "CREATED_AT", "WAVE_CODE", "ORDER_COUNT", "PROGRESS");

  /** 状态词表，与 V113 的 CHECK 逐字一致。 */
  private static final List<String> STATUSES =
      List.of("PLANNED", "PICKING", "COMPLETED", "CANCELLED");

  /** 流水事件词表，与 V113 的 CHECK 逐字一致。 */
  private static final List<String> EVENT_TYPES =
      List.of("PLANNED", "RELEASED", "TASK_ATTACHED", "TASK_DETACHED", "COMPLETED", "CANCELLED");

  /** 幂等锚点：波次只是排班，不进 inventory_transaction。 */
  private static final String REF_TYPE = "PICK_WAVE";

  /** zone_id 为空时在部分唯一索引里用的零值（与 V113 里的表达式一致）。 */
  private static final String NO_ZONE = "00000000-0000-0000-0000-000000000000";

  /**
   * 合并口径原文。随响应返回（{@link PickWaveCapabilities#mergeBasis()} 与每行的
   * {@code mergeBasis}），不只写在注释里 —— 半年后回看时，
   * 「为什么这两张单被凑到一起」只有这一行答得清。
   */
  public static final String MERGE_BASIS =
      "按承运渠道 + 库区两个维度合并，一个波次只对应一个合并键（V113 的部分唯一索引"
          + "pick_wave_open_key 保证同租户同仓同库区同承运渠道同时只有一个未完波次）；"
          + "成员取该键下候选出库单的前 N 张，N = 合并规则的订单数上限；"
          + "库区取拣货段取货库位在库位树（8.1.2.1）上的顶层根节点，"
          + "横跨多个区的任务没有单一库区、只能进「库区不限」的规则；"
          + "成员排序按出库单创建时刻升序，位置由 V113 的触发器重排为 1..n 连续整数。";

  /** 时段口径原文。 */
  public static final String TIME_SLOT_BASIS =
      "时段窗口是仓本地小时区间 [time_slot_start, time_slot_end)，"
          + "出库单的 created_at 按 warehouse.timezone（V1/V69）换算成当地小时后判是否落窗；"
          + "跨午夜的窗口请拆成两条规则（一条 [0,n)、一条 [m,24)）。";

  /** 库区口径原文。 */
  public static final String ZONE_BASIS =
      "库区 = 拣货段取货库位沿 warehouse_location.parent_id 上行到的顶层根节点"
          + "（V105 的库位树把顶层节点称作「区」），与 8.1.2.1 的现场层级认知同一口径；"
          + "一条拣货任务的拣货段横跨多个区时，跨区任务没有单一库区。";

  /**
   * 候选出库单取数的公共 CTE。
   *
   * <p>刻意抽成常量而不是在两处各写一遍：{@link #candidates} 与 {@link #create} 必须
   * 用<b>同一个口径</b>看候选，否则会出现「预览里能合、建波次时却找不到」的错位 ——
   * 那是最难查的一类不一致。
   *
   * <p>三个部分：
   * <ol>
   *   <li>{@code zone_root} —— 上行到每个库位的顶层根节点（V113 §5，V105 的六层上限
   *       换成了六次自连接，原因见那段注释）。</li>
   *   <li>{@code task_zone} —— 把一张拣货任务的<b>所有</b>拣货段归到区上；
   *       {@code zone_count = 1} 才有单一库区，否则为跨区（{@code null}）。</li>
   *   <li>{@code cand} —— 可合并的出库单全集：拣货任务在 CREATED / IN_PROGRESS、
   *       出库单在 PICKING（V72 的状态机：只有「拣货中」的单还在等拣）。
   *       <b>已经在某个未完波次里的任务也保留在结果里</b>（{@code in_wave_id}），
   *       因为「有 N 张在等上一波收工」正是要看板必须报出来的数。</li>
   *   <li>最外层的 {@code left join lateral} —— 取命中的合并规则（按 priority 取第一条），
   *       以及该合并键上是否已有未完波次。</li>
   * </ol>
   *
   * <p>占位符刻意用<b>普通 {@code ?}</b>而不是 {@code ?1}/{@code ?2}：jOOQ 里
   * 编号占位符按出现位置占位，与普通 {@code ?} 混用时计数会错位
   * （实测报「未设定参数值 4 的内容」，而 SQL 里根本没有第 4 个值）。
   * 绑定顺序见 {@link #candidatePool}：租户、仓库、租户、仓库、仓库×3。
   */
  private static final String CANDIDATE_CTE =
      """
      with zone_root as (
          -- 库区 = 取货库位沿 parent 链上行到的顶层根节点（V105 把顶层节点称作「区」）。
          --
          -- **刻意不用 WITH RECURSIVE**，而是六次自连接：V105 的
          -- guard_warehouse_location_tree 已经把树限死在六层（区→巷→排→架→层→格）
          -- 且防环，所以「最多上行五次」是**由数据库保证的上界**，不是猜测。
          -- 代价是这条 SQL 里没有递归 CTE。
          --
          -- coalesce 从最深往上取：**非空的那个就是根**（根的 parent 是 NULL，
          -- 再往上的自连接全是 NULL）。
          select l.id as leaf_id,
                 coalesce(p6.id, p5.id, p4.id, p3.id, p2.id, p1.id, l.id) as zone_id,
                 coalesce(p6.code, p5.code, p4.code, p3.code, p2.code, p1.code, l.code) as zone_code
            from warehouse_location l
            left join warehouse_location p1 on p1.id = l.parent_id
            left join warehouse_location p2 on p2.id = p1.parent_id
            left join warehouse_location p3 on p3.id = p2.parent_id
            left join warehouse_location p4 on p4.id = p3.parent_id
            left join warehouse_location p5 on p5.id = p4.parent_id
            left join warehouse_location p6 on p6.id = p5.parent_id
           where l.tenant_id = ? and l.warehouse_id = ?
      ),
      task_zone as (
          -- 取「唯一那个区」用 (array_agg(..))[1] 而不是 min(..)：**PG 没有 min(uuid)**
          -- （实测报 function min(uuid) does not exist）。count(distinct)=1 已经保证
          -- 数组里只有一个元素，所以取头元素就是那个区。
          select ln.task_id,
                 count(distinct r.zone_id) as zone_count,
                 case when count(distinct r.zone_id) = 1
                      then (array_agg(r.zone_id))[1] end as zone_id,
                 case when count(distinct r.zone_id) = 1
                      then (array_agg(r.zone_code))[1] end as zone_code
            from pick_task_line ln
            join zone_root r on r.leaf_id = ln.source_location_id
           group by ln.task_id
      ),
      cand as (
          select t.tenant_id, t.warehouse_id,
                 t.id as pick_task_id, t.task_code,
                 t.planned_line_count, t.planned_quantity,
                 o.id as outbound_order_id, o.order_code, o.carrier_code,
                 o.status as outbound_status, o.created_at as order_created_at,
                 coalesce(tz.zone_count, 0) as zone_count,
                 tz.zone_id, tz.zone_code,
                 extract(hour from (o.created_at at time zone w.timezone))::int as local_hour,
                 wm.wave_id as in_wave_id, wv.wave_code as in_wave_code
            from pick_task t
            join outbound_order o on o.tenant_id = t.tenant_id and o.id = t.outbound_order_id
            join warehouse w on w.tenant_id = t.tenant_id and w.id = t.warehouse_id
            left join task_zone tz on tz.task_id = t.id
            left join pick_wave_task wm
                   on wm.tenant_id = t.tenant_id and wm.pick_task_id = t.id and not wm.released
            left join pick_wave wv on wv.tenant_id = wm.tenant_id and wv.id = wm.wave_id
           where t.tenant_id = ?
             and t.warehouse_id = ?
             and t.status in ('CREATED','IN_PROGRESS')
             and o.status = 'PICKING'
      )
      """;

  /** 候选取数的最外层：命中规则 + 该合并键上的未完波次。 */
  private static final String CANDIDATE_SELECT =
      """
       -- **刻意把 c.* 展开成逐列列举**：jOOQ 的 PlainSQL 解析器认不出带表限定的
       -- 星号展开，于是 Record 里没有这些列的名字，读的时候报
       -- 「Field "zone_code" is not contained in row type」。逐列写死同时也让
       -- 「这条候选口径到底取了哪些列」在源码里看得见，不靠猜。
       select c.tenant_id, c.warehouse_id, c.pick_task_id, c.task_code,
              c.planned_line_count, c.planned_quantity,
              c.outbound_order_id, c.order_code, c.carrier_code,
              c.outbound_status, c.order_created_at,
              c.zone_count, c.zone_id, c.zone_code, c.local_hour,
              c.in_wave_id, c.in_wave_code,
              m.rule_id as matched_rule_id, m.rule_code, m.rule_name, m.max_order_count,
              (select w2.id from pick_wave w2
                where w2.tenant_id = c.tenant_id
                  and w2.warehouse_id = ?
                  and w2.carrier_code = c.carrier_code
                  and coalesce(w2.zone_id,'00000000-0000-0000-0000-000000000000'::uuid)
                    = coalesce(c.zone_id,'00000000-0000-0000-0000-000000000000'::uuid)
                  and w2.status in ('PLANNED','PICKING')
                limit 1) as open_wave_id,
              (select w3.wave_code from pick_wave w3
                where w3.tenant_id = c.tenant_id
                  and w3.warehouse_id = ?
                  and w3.carrier_code = c.carrier_code
                  and coalesce(w3.zone_id,'00000000-0000-0000-0000-000000000000'::uuid)
                    = coalesce(c.zone_id,'00000000-0000-0000-0000-000000000000'::uuid)
                  and w3.status in ('PLANNED','PICKING')
                limit 1) as open_wave_code
         from cand c
         left join lateral (
              select r.id as rule_id, r.rule_code, r.rule_name, r.max_order_count
                from pick_wave_rule r
               where r.tenant_id = c.tenant_id
                 and r.warehouse_id = ?
                 and r.status = 'ACTIVE'
                 and (r.carrier_match = '' or r.carrier_match = c.carrier_code)
                 and (r.zone_id is null or r.zone_id = c.zone_id)
                 and c.local_hour >= r.time_slot_start
                 and c.local_hour < r.time_slot_end
               order by r.priority, r.rule_code
               limit 1
         ) m on true
        order by c.carrier_code, c.zone_code nulls first, c.order_created_at, c.pick_task_id
      """;

  /**
   * 波次列表取数。
   *
   * <p>{@code agg} 是一次 {@code left join lateral} 聚合：波次的进度<b>不在单头上</b>，
   * 而是每次查询由 {@code pick_wave_task ⋈ pick_task} 现算（V113 §3 说明了为什么
   * 不能做成冗余列）。
   */
  private static final String WAVE_SELECT =
      """
      select w.id, w.wave_code, w.status, w.warehouse_id, w.carrier_code, w.zone_id, w.rule_id,
             w.assignee_user_id, w.planned_pick_at, w.started_at, w.completed_at, w.cancelled_at,
             w.cancel_reason, w.created_at, w.updated_at, w.merge_basis,
             z.code as zone_code, r.rule_code,
             coalesce(agg.task_count, 0) as task_count,
             coalesce(agg.order_count, 0) as order_count,
             coalesce(agg.planned_line_count, 0) as planned_line_count,
             coalesce(agg.planned_quantity, 0) as planned_quantity,
             coalesce(agg.picked_line_count, 0) as picked_line_count,
             coalesce(agg.picked_quantity, 0) as picked_quantity,
             coalesce(agg.terminal_task_count, 0) as terminal_task_count
        from pick_wave w
        left join warehouse_location z on z.tenant_id = w.tenant_id and z.id = w.zone_id
        left join pick_wave_rule r on r.tenant_id = w.tenant_id and r.id = w.rule_id
        left join lateral (
             select count(*) as task_count,
                    count(distinct m.outbound_order_id) as order_count,
                    coalesce(sum(t.planned_line_count), 0) as planned_line_count,
                    coalesce(sum(t.planned_quantity), 0) as planned_quantity,
                    coalesce(sum(t.picked_line_count), 0) as picked_line_count,
                    coalesce(sum(t.picked_quantity), 0) as picked_quantity,
                    count(*) filter (where t.status in ('COMPLETED','CANCELLED')) as terminal_task_count
               from pick_wave_task m
               join pick_task t on t.tenant_id = m.tenant_id and t.id = m.pick_task_id
              where m.tenant_id = w.tenant_id and m.wave_id = w.id
        ) agg on true
      """;

  private final DSLContext sql;

  public PickWaveService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明。
   *
   * <p>把「哪些输入真有依据」「哪条不能填默认值」显式报出来：库区是**推导**的、
   * 时段是**仓本地**的、波次进度是**现算**的、合并不**改订单粒度** ——
   * 这四件事任何一条被误解，界面上的数就会开始骗人。
   */
  public PickWaveCapabilities capabilities() {
    return new PickWaveCapabilities(
        REF_TYPE,
        // carrierMergeLive
        true,
        // zoneMergeLive
        true,
        // zoneDerivedFromLocationTree
        true,
        // timeSlotMergeLive
        true,
        // orderCountCapEnforced
        true,
        // ruleConfigurable
        true,
        // oneOpenWavePerMergeKey
        true,
        // oneOpenWavePerTask
        true,
        // waveProgressDerived
        true,
        // taskOrderGranularityUnchanged
        true,
        // inventoryUnaffected：波次只是排班，不搬货。
        false,
        "8.3.2.1 拣货任务",
        List.of(
            new PickWaveUpstreamModule(
                "8.3.2.1", "拣货任务", "warehouse", true,
                "波次的成员就是它的拣货任务（本表一个字都没改 V110 的六张表）。"
                    + "候选口径：pick_task.status in (CREATED, IN_PROGRESS) 且"
                    + "outbound_order.status = PICKING，且不在任何未完波次里。"),
            new PickWaveUpstreamModule(
                "8.3.1.1", "出库单列表", "warehouse", true,
                "承运渠道取 outbound_order.carrier_code，时段匹配取它的 created_at"
                    + "（换算成仓本地小时）。出库单是履约单据，一个出库单恰好一张拣货任务。"),
            new PickWaveUpstreamModule(
                "8.1.2.1", "库位树", "warehouse", true,
                "库区沿 warehouse_location.parent_id 上行到顶层根节点；"
                    + "V105 的守卫防环且限六层，所以每片叶子恰好命中一个根。")),
        List.of(
            new PickWaveDownstreamModule(
                "8.3.3.1", "复核扫描", "warehouse", false,
                "波次完成只表示「这一组拣货任务都拣完了」。出库单各自进「待复核」，"
                    + "复核与打包仍按订单逐张做 —— 合并只影响拣货效率，不改变订单粒度。"
                    + "复核本身未交付，本模块只交到交接为止。"),
            new PickWaveDownstreamModule(
                "8.3.4.1", "发货", "warehouse", false,
                "本模块<b>不碰</b>发货状态：cancel 一个波次不动库存、不动预占、不改出库单状态，"
                    + "货仍在原库位。发货是 8.3.4.1 写 SHIPPED 的事。")),
        STATUSES,
        EVENT_TYPES,
        SORTS,
        MERGE_BASIS,
        TIME_SLOT_BASIS,
        ZONE_BASIS,
        "波次不搬货、不写 inventory_transaction / inventory_entry、不动预占："
            + "合并只改排班，实拣仍然由 8.3.2.1 在库位间搬移，"
            + "被合并的出库单各自按 8.3.2.1 的口径记账。",
        "合并只影响拣货效率，不改变订单粒度：一个波次 = 多张出库单的拣货任务一起走，"
            + "波次完成后仍需按订单拆分复核与打包（蓝图关系段原话）。");
  }

  // ================================================================ 合并规则

  /**
   * 规则清单（可按仓筛选）。每条带上它当前管着多少候选、多少在等上一波收工。
   *
   * <p>两个计数**复用 {@link #candidates} 的候选口径**（同一个 {@code CANDIDATE_CTE}）：
   * 如果这里另写一份查询，规则台上的「管着 N 张」与候选页上的数就会在某一天开始对不上，
   * 而两个数字看起来都很合理，没人会去核。
   */
  public PickWaveRuleList rules(UUID warehouseId) {
    UUID tenant = currentTenant();
    var rules =
        sql.fetch(
                """
                select r.id, r.rule_code, r.rule_name, r.warehouse_id, r.carrier_match, r.zone_id,
                       z.code as zone_code, r.time_slot_start, r.time_slot_end, r.max_order_count,
                       r.priority, r.status, r.retired_at, r.retire_reason,
                       r.created_at, r.updated_at
                  from pick_wave_rule r
                  left join warehouse_location z on z.tenant_id = r.tenant_id and z.id = r.zone_id
                 where r.tenant_id = ?
                """
                    + (warehouseId == null ? "" : " and r.warehouse_id = ?"),
                warehouseId == null ? new Object[] {tenant} : new Object[] {tenant, warehouseId});

    // 每个仓各取一次候选（候选口径按仓过滤），归到各自规则上。
    var warehouses = new java.util.LinkedHashSet<UUID>();
    for (Record r : rules) warehouses.add(r.get("warehouse_id", UUID.class));

    var activeCount = new java.util.HashMap<UUID, Integer>();
    var keys = new java.util.LinkedHashSet<MergeKey>();
    var poolByRule = new java.util.HashMap<UUID, List<PickWaveCandidateRow>>();
    for (UUID wh : warehouses) {
      for (PickWaveCandidateRow c : candidatePool(wh)) {
        keys.add(new MergeKey(wh, c.carrierCode(), c.zoneId()));
        if (c.matchedRuleId() == null) continue;
        poolByRule.computeIfAbsent(c.matchedRuleId(), k -> new ArrayList<>()).add(c);
        if (!c.blocked()) activeCount.merge(c.matchedRuleId(), 1, Integer::sum);
      }
    }
    // 合并键上是否已有未完波次：每个键查一次，不在候选循环里逐行查。
    var openWaves = new java.util.HashSet<MergeKey>();
    for (MergeKey key : keys) if (openWaveFor(key) != null) openWaves.add(key);

    var items = new ArrayList<PickWaveRuleRow>();
    for (Record r : rules) {
      UUID ruleId = r.get("id", UUID.class);
      UUID wh = r.get("warehouse_id", UUID.class);
      List<PickWaveCandidateRow> pool = poolByRule.getOrDefault(ruleId, List.of());
      int waiting = 0;
      for (PickWaveCandidateRow c : pool) {
        if (c.blocked()) continue;
        if (openWaves.contains(new MergeKey(wh, c.carrierCode(), c.zoneId()))) waiting++;
      }
      items.add(ruleRow(r, waiting, activeCount.getOrDefault(ruleId, 0)));
    }
    return new PickWaveRuleList(items, items.size(),
        "activeTaskCount = 该规则当前认领、且还没进任何波次的出库单数；"
            + "waitingOrderCount = 认领了但因为该合并键上已有未完波次而进不去的出库单数"
            + "（V113 的 pick_wave_open_key：同键同时只能有一个未完波次）。");
  }

  /** 某个仓的全部候选出库单（{@link #candidates} 与规则台共用同一口径）。 */
  private List<PickWaveCandidateRow> candidatePool(UUID warehouseId) {
    UUID tenant = currentTenant();
    // 绑定顺序严格对应 SQL 里的出现顺序：
    // CTE（租户、仓库）→ CTE（租户、仓库）→ 外层三个 warehouse（两个未完波次子查询 +
    // 规则匹配的 lateral）。写成数组而不是 varargs 的"看错了顺序也发现不了"。
    return sql.fetch(
            CANDIDATE_CTE + CANDIDATE_SELECT,
            tenant, warehouseId, tenant, warehouseId, warehouseId, warehouseId, warehouseId)
        .map(r -> candidateRow(r));
  }

  /**
   * 合并键。
   *
   * <p>波次永远是单键的（V113 的 {@code pick_wave_open_key} 部分唯一索引），
   * 所以「同一承运渠道 × 同一库区」这个二元组在本服务里到处要用：候选分组、
   * 建波次前的冲突检查、规则台上的「有几张在等上一波收工」。
   * 抽成 record 而不是拼字符串，是为了让这三个地方<b>不可能</b>用不同的键口径。
   */
  private record MergeKey(UUID warehouseId, String carrierCode, UUID zoneId) {}

  /** 该合并键上的未完波次，没有则 null。 */
  private Record openWaveFor(MergeKey key) {
    return sql.fetchOne(
        "select id, wave_code from pick_wave where tenant_id = ? and warehouse_id = ?"
            + " and carrier_code = ?"
            + " and coalesce(zone_id,?::uuid) = coalesce(?,?::uuid)"
            + " and status in ('PLANNED','PICKING')",
        currentTenant(), key.warehouseId(), key.carrierCode(), NO_ZONE, key.zoneId(), NO_ZONE);
  }

  /** 新建合并规则。 */
  @Transactional
  public PickWaveRuleRow createRule(PickWaveRuleInput input, UUID actorId) {
    UUID tenant = currentTenant();
    if (input.ruleCode() == null || input.ruleCode().isBlank()) {
      throw bad("必须给 ruleCode：规则号是租户内唯一的，没有它就没法在台账上指认这条口径");
    }
    if (input.ruleName() == null || input.ruleName().isBlank()) {
      throw bad("必须给 ruleName：这条规则会决定哪些出库单被凑进同一波，人得看得懂它");
    }
    if (input.warehouseId() == null) throw bad("必须给 warehouseId：波次不能跨仓合并");
    if (warehouseOf(input.warehouseId()) == null) {
      throw notFound("仓库 " + input.warehouseId() + " 不存在");
    }
    int maxOrders = input.maxOrderCount() == null ? 0 : input.maxOrderCount();
    if (maxOrders <= 0) {
      throw bad("必须给 maxOrderCount 且大于 0：订单数上限是蓝图点名的第三个合并维度，"
          + "不给上限的规则会把整个仓的待拣单一次吞进一个波次");
    }
    int slotStart = input.timeSlotStart() == null ? 0 : input.timeSlotStart();
    int slotEnd = input.timeSlotEnd() == null ? 24 : input.timeSlotEnd();
    if (slotStart < 0 || slotEnd > 24 || slotStart >= slotEnd) {
      throw bad("时段窗口非法：仓本地小时必须满足 0 <= timeSlotStart < timeSlotEnd <= 24，"
          + "收到的是 [" + slotStart + "," + slotEnd + ")" + TIME_SLOT_BASIS);
    }
    if (input.zoneId() != null && zoneOf(input.zoneId(), input.warehouseId()) == null) {
      throw bad("库区 " + input.zoneId() + " 不在这个仓库的库位树里");
    }

    UUID id = UUID.randomUUID();
    String carrier = text(input.carrierMatch());
    try {
      sql.execute(
          """
          insert into pick_wave_rule
            (id,tenant_id,rule_code,rule_name,warehouse_id,carrier_match,zone_id,
             time_slot_start,time_slot_end,max_order_count,priority,status,created_by)
          values (?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',?)
          """,
          id, tenant, input.ruleCode().trim(), input.ruleName().trim(), input.warehouseId(),
          carrier, input.zoneId(), slotStart, slotEnd, maxOrders,
          input.priority() == null ? 100 : input.priority(), actorId);
    } catch (org.springframework.dao.DataIntegrityViolationException e) {
      // 刻意捕 Spring 的 DataIntegrityViolationException 而不是 jOOQ 的
      // IntegrityConstraintViolationException：Spring Boot 的 jOOQ 异常翻译器
      // 已经把 PSQLException 换成了前者，捕 jOOQ 那个异常会一个都接不住
      // （实测表现为 duplicate key 原样冒出去变成 500）。
      // 承运渠道 + 库区 + 时段窗口这个三元组在同仓内只能有一条 ACTIVE 规则
      // （pick_wave_rule_active_key）：两条规则认领同一批订单却有不同上限时，
      // 「这批订单归哪个波次」就没有确定答案。
      throw conflict("同一承运渠道 + 库区 + 时段窗口在同一个仓里只能有一条生效规则"
          + "（两条规则认领同一批订单却各有上限时，归属没有确定答案）：" + e.getMessage());
    }
    return rules(input.warehouseId()).items().stream()
        .filter(r -> r.id().equals(id))
        .findFirst()
        .orElseThrow(() -> notFound("规则 " + id + " 刚创建却读不回来"));
  }

  /** 停用合并规则。必填原因。 */
  @Transactional
  public PickWaveRuleRow retireRule(UUID ruleId, PickWaveRuleRetireInput input, UUID actorId) {
    UUID tenant = currentTenant();
    Record rule = requireRule(ruleId);
    if ("INACTIVE".equals(rule.get("status", String.class))) {
      throw conflict("规则 " + rule.get("rule_code", String.class) + " 已经停用了");
    }
    if (input == null || input.reason() == null || input.reason().isBlank()) {
      throw bad("必须写 reason：规则一停，一批正在等的出库单就换了归并口径，"
          + "没有原因的停用等于让昨天和今天的波次用不同标准却没人说得清");
    }
    sql.execute(
        "update pick_wave_rule set status='INACTIVE', retired_at=?::timestamptz,"
            + " retire_reason=?, updated_at=now() where tenant_id=? and id=?",
        OffsetDateTime.now(), input.reason().trim(), tenant, ruleId);
    return rules(rule.get("warehouse_id", UUID.class)).items().stream()
        .filter(r -> r.id().equals(ruleId))
        .findFirst()
        .orElseThrow(() -> notFound("规则 " + ruleId + " 不存在"));
  }

  // ================================================================ 候选

  /**
   * 候选出库单（合并预览）。
   *
   * <p>按<b>合并键</b>（承运渠道 × 库区）分组返回，而不是一张混排的清单：一屏里混着
   * 四个承运商的订单，看的人无法判断「现在该开哪一波」。
   *
   * <p>每组报三个数：{@code eligibleCount}（现在就能并进去）、{@code waitingCount}
   * （得等上一波收工 / 等上限腾出位置）、{@code unruledCount}（没有任何生效规则认领，
   * 只能手工指定成员）。
   */
  public PickWaveCandidatePage candidates(UUID warehouseId) {
    UUID tenant = currentTenant();
    if (warehouseId == null) throw bad("必须给 warehouseId：波次不能跨仓合并");
    String timezone = timezoneOf(warehouseId);
    if (timezone == null) throw notFound("仓库 " + warehouseId + " 不存在或没有时区");

    var rows =
        candidatePool(warehouseId);

    // 分组：合并键 = 承运渠道 × 库区（null 库区归到「跨区/不限」那组）。
    var order = new LinkedHashMap<String, List<PickWaveCandidateRow>>();
    for (var row : rows) {
      String key = row.carrierCode() + "" + (row.zoneId() == null ? "" : row.zoneId());
      order.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
    }

    var groups = new ArrayList<PickWaveCandidateGroup>();
    int totalEligible = 0;
    int totalWaiting = 0;
    for (var entry : order.values()) {
      var group = groupOf(entry, warehouseId);
      groups.add(group);
      totalEligible += group.eligibleCount();
      totalWaiting += group.waitingCount();
    }
    return new PickWaveCandidatePage(
        groups, totalEligible, totalWaiting, timezone,
        "eligibleCount = 现在就能并进一个新波次；waitingCount = 已有同键未完波次"
            + "（V113 的 pick_wave_open_key）或已撞上订单数上限，得等上一波收工；"
            + "unruledCount = 没有生效规则认领，只能手工指定成员。"
            + "时段按仓本地 " + timezone + " 判定（" + TIME_SLOT_BASIS + "）。");
  }

  // ================================================================ 建波次

  /**
   * 建波次。
   *
   * <p>两条路径，终点是同一条合并键：
   *
   * <ul>
   *   <li><b>按规则</b>：在 {@link #candidates} 的分组里挑一组，按 {@code maxOrderCount}
   *       取前 N 张。
   *   <li><b>手工指定</b>：{@code pickTaskIds}。必须同仓、同承运渠道，且都不在别的
   *       未完波次里 —— 波次是单键的，这个键由 V113 的部分唯一索引钉死。
   * </ul>
   *
   * <p>选不中任何一张时<b>直接报错</b>，不建空波次：一个没有成员的波次既不能放行
   * （没人可走）也不能完成（V113 的守卫会拒），留在台账上只是个需要人工解释的怪东西。
   */
  @Transactional
  public PickWaveCreated create(PickWaveCreateInput input, UUID actorId) {
    UUID tenant = currentTenant();
    if (input.warehouseId() == null) throw bad("必须给 warehouseId：波次不能跨仓合并");
    String timezone = timezoneOf(input.warehouseId());
    if (timezone == null) throw notFound("仓库 " + input.warehouseId() + " 不存在或没有时区");

    Record rule = input.ruleId() == null ? null : requireRule(input.ruleId());
    if (rule != null) {
      if (!"ACTIVE".equals(rule.get("status", String.class))) {
        throw conflict("规则 " + rule.get("rule_code", String.class)
            + " 已停用：拿一条停用的规则建波次，等于用今天的口径解释昨天的排班");
      }
      if (!rule.get("warehouse_id", UUID.class).equals(input.warehouseId())) {
        throw bad("规则 " + rule.get("rule_code", String.class) + " 不属于这个仓库");
      }
    }

    // ---- 选出这一波要并进去的出库单
    List<Record> pool =
        new ArrayList<>(
            sql.fetch(
                CANDIDATE_CTE + CANDIDATE_SELECT,
                tenant, input.warehouseId(), tenant, input.warehouseId(),
                input.warehouseId(), input.warehouseId(), input.warehouseId()));
    List<Record> chosen = selectMembers(input, rule, pool);

    String carrier = chosen.get(0).get("carrier_code", String.class);
    var zones = new java.util.LinkedHashSet<UUID>();
    for (var r : chosen) zones.add(r.get("zone_id", UUID.class));
    if (zones.size() > 1) {
      // 手工指定时才会发生：两张单分别在 A 区和 B 区。按库区合并的前提是同一个区，
      // 所以这里不猜、不取第一个，而是要求调用方补上 zoneId 收窄到一个键。
      throw bad("这几张出库单横跨 " + zones.size()
          + " 个库区（" + zoneNames(zones) + "）：波次是单键的，请用 zoneId 收窄到一个合并键");
    }
    UUID zoneId = chosen.get(0).get("zone_id", UUID.class);
    String zoneCode = chosen.get(0).get("zone_code", String.class);

    // ---- 该合并键上已经有未完波次？（V113 的 pick_wave_open_key 是真正的拦截者，
    //      这里先查一次只是为了说人话）
    Record open = openWaveFor(new MergeKey(input.warehouseId(), carrier, zoneId));
    if (open != null) {
      throw conflict("这个合并键上已经有一个未完波次 " + open.get("wave_code", String.class)
          + "：同一仓、同一库区、同一承运渠道同时只能走一个波次"
          + "（两个拣货组会在同一个区互相踩）。请等它收工，或先取消它。");
    }

    // ---- 计划拣货时刻：按规则的仓本地时段起点推今天那一刻；没有规则就取当前时刻
    OffsetDateTime plannedAt =
        input.plannedPickAt() != null && !input.plannedPickAt().isBlank()
            ? instant(input.plannedPickAt(), "计划拣货时刻")
            : rule == null
                ? OffsetDateTime.now()
                : slotStartToday(rule.get("time_slot_start", Integer.class), timezone);

    String basis =
        mergeBasis(
            carrier,
            zoneCode,
            rule,
            chosen.size(),
            pool.size(),
            input.pickTaskIds() != null && !input.pickTaskIds().isEmpty());

    UUID waveId = UUID.randomUUID();
    String waveCode = nextCode("PW");
    sql.execute(
        """
        insert into pick_wave
          (id,tenant_id,wave_code,warehouse_id,carrier_code,zone_id,rule_id,merge_basis,status,
           planned_pick_at,created_by)
        values (?,?,?,?,?,?,?,?,'PLANNED',?::timestamptz,?)
        """,
        waveId, tenant, waveCode, input.warehouseId(), carrier, zoneId,
        input.ruleId(), basis, plannedAt, actorId);

    int position = 1;
    for (Record member : chosen) {
      sql.execute(
          """
          insert into pick_wave_task
            (id,tenant_id,wave_id,pick_task_id,outbound_order_id,position,carrier_code,zone_id,
             planned_pick_at)
          values (?,?,?,?,?,?,?,?,?::timestamptz)
          """,
          UUID.randomUUID(), tenant, waveId, member.get("pick_task_id", UUID.class),
          member.get("outbound_order_id", UUID.class), position, carrier, zoneId, plannedAt);
      position++;
    }

    event(waveId, "PLANNED", null, "PLANNED", actorId,
        "按「" + carrier + (zoneCode == null ? " / 跨区" : " / 库区 " + zoneCode) + "」合并 "
            + chosen.size() + " 张出库单" + (rule == null ? "（手工指定成员）"
                : "（规则 " + rule.get("rule_code", String.class) + "）") + "。");

    int waiting = waitingCount(input.warehouseId(), carrier, zoneId);
    return new PickWaveCreated(
        waveId, waveCode, "PLANNED", input.warehouseId(), carrier, zoneId, zoneCode,
        input.ruleId(), rule == null ? null : rule.get("rule_code", String.class),
        chosen.size(), chosen.size(), plannedAt, basis, waiting,
        "已排波：成员 " + chosen.size() + " 张出库单（每张各自一张拣货任务，订单粒度不变）。"
            + (waiting > 0 ? "还有 " + waiting + " 张在这个合并键上等下一波。" : ""));
  }

  /**
   * 放行开波：PLANNED → PICKING。
   *
   * <p>必须给负责人：放行之后「这一波谁在拣」不能为空，否则 8.5.1.1 的作业效率
   * 无从归因，而且波次可能就这么一直挂着没人动。
   */
  @Transactional
  public PickWaveDetail release(UUID waveId, PickWaveReleaseInput input, UUID actorId) {
    UUID tenant = currentTenant();
    Record wave = requireWave(waveId);
    if (!"PLANNED".equals(wave.get("status", String.class))) {
      throw conflict("波次 " + wave.get("wave_code", String.class) + " 当前状态是 "
          + wave.get("status", String.class) + "，只有「已排波」的波次能放行");
    }
    if (input == null || input.assigneeUserId() == null) {
      throw bad("必须给 assigneeUserId：放行之后这一波要有人负责拣，"
          + "没有人认领的波次会在作业台上一直挂着");
    }
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "update pick_wave set status='PICKING', assignee_user_id=?,"
            + " started_at=coalesce(started_at,?::timestamptz), updated_at=now()"
            + " where tenant_id=? and id=?",
        input.assigneeUserId(), now, tenant, waveId);
    event(waveId, "RELEASED", "PLANNED", "PICKING", actorId,
        "放行给作业人员 " + input.assigneeUserId() + "。"
            + (input.note() == null || input.note().isBlank() ? ""
                : "备注：" + input.note().trim()));
    return detail(waveId);
  }

  /**
   * 完成波次：PICKING → COMPLETED。
   *
   * <p>前提是<b>每一张成员工单都已终态</b>。服务层先查是为了说人话，
   * V113 的 {@code guard_pick_wave_status_fn} 会回表再查一次 —— 绕开服务层直连 SQL
   * 也过不去。这是「合并不改订单粒度」在 DB 层的落点。
   */
  @Transactional
  public PickWaveDetail complete(UUID waveId, PickWaveCompleteInput input, UUID actorId) {
    UUID tenant = currentTenant();
    Record wave = requireWave(waveId);
    if (!"PICKING".equals(wave.get("status", String.class))) {
      throw conflict("波次 " + wave.get("wave_code", String.class) + " 当前状态是 "
          + wave.get("status", String.class) + "，只有「拣货中」的波次能完成");
    }
    int open = openMemberCount(waveId);
    if (open > 0) {
      throw conflict("还有 " + open + " 张拣货任务没到终态，不能完成波次："
          + "合并只影响拣货效率，波次完成不替代任何一张工单的完成。"
          + "请先把每张拣货任务在 8.3.2.1 上完成或取消。");
    }
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "update pick_wave set status='COMPLETED', completed_at=?::timestamptz, updated_at=now()"
            + " where tenant_id=? and id=?",
        now, tenant, waveId);
    event(waveId, "COMPLETED", "PICKING", "COMPLETED", actorId,
        "全部成员工单已终态，波次合并结束。出库单各自按 8.3.3.1 逐单复核与打包。"
            + (input == null || input.note() == null || input.note().isBlank()
                ? "" : "备注：" + input.note().trim()));
    return detail(waveId);
  }

  /** 取消波次：PLANNED / PICKING → CANCELLED。必填原因。<b>不动库存</b>。 */
  @Transactional
  public PickWaveDetail cancel(UUID waveId, PickWaveCancelInput input, UUID actorId) {
    UUID tenant = currentTenant();
    Record wave = requireWave(waveId);
    String status = wave.get("status", String.class);
    if (!PickWaveStatus.valueOf(status).acceptsCancel()) {
      throw conflict("波次 " + wave.get("wave_code", String.class) + " 已经"
          + PickWaveStatus.valueOf(status).label() + "了，取消没有意义");
    }
    if (input == null || input.reason() == null || input.reason().isBlank()) {
      throw bad("必须写 reason：一批订单被排进又排出一波，没有原因的取消等于没留痕");
    }
    int members = memberCount(waveId);
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "update pick_wave set status='CANCELLED', cancelled_at=?::timestamptz, cancel_reason=?,"
            + " updated_at=now() where tenant_id=? and id=?",
        now, input.reason().trim(), tenant, waveId);
    event(waveId, "CANCELLED", status, "CANCELLED", actorId,
        "取消本波，" + members + " 张拣货任务被放回候选池（货仍在原库位、预占不变）。"
            + "原因：" + input.reason().trim());
    return detail(waveId);
  }

  /**
   * 移出一张成员工单（只在「已排波」时允许）。
   *
   * <p>只有<b>一个</b>成员时拒绝：把最后一张移走只会留下一个空波次，
   * 它既不能放行（没人可走）也不能完成（V113 的守卫会拒）。要放弃就取消波次。
   *
   * <p>移出后位置由 V113 的 {@code refresh_pick_wave_positions_fn} 重排为 1..n 连续 ——
   * 界面上与打印单据上不该出现「1、2、4」这种洞。
   */
  @Transactional
  public PickWaveDetail detach(UUID waveId, UUID pickTaskId, UUID actorId) {
    UUID tenant = currentTenant();
    Record wave = requireWave(waveId);
    if (!"PLANNED".equals(wave.get("status", String.class))) {
      throw conflict("波次 " + wave.get("wave_code", String.class) + " 已经放行："
          + "拣货组已经在按位置走了，中途抽走一张会让已发出的拣货单对不上");
    }
    if (memberCount(waveId) <= 1) {
      throw bad("这个波次只剩一个成员：移走它就只剩一个空波次。"
          + "要放弃这一波请用取消（留原因），空波次既不能放行也不能完成");
    }
    Record member =
        sql.fetchOne(
            "select id, outbound_order_id from pick_wave_task"
                + " where tenant_id=? and wave_id=? and pick_task_id=?",
            tenant, waveId, pickTaskId);
    if (member == null) throw notFound("拣货任务 " + pickTaskId + " 不在这个波次里");
    sql.execute("delete from pick_wave_task where tenant_id=? and id=?",
        tenant, member.get("id", UUID.class));
    event(waveId, "TASK_DETACHED", null, null, actorId,
        "移出出库单 " + member.get("outbound_order_id", UUID.class)
            + "：它被放回候选池，位置已重排为连续序号。");
    return detail(waveId);
  }

  // ================================================================ 读

  /** 波次列表：真分页 / 真筛选 / 排序白名单 / 汇总。 */
  public PickWavePage page(
      UUID warehouseId, String status, String carrierCode, UUID zoneId, String keyword, String sort,
      int page, int size) {
    UUID tenant = currentTenant();
    // List.of() 对 null 是敌意的（contains(null) 直接 NPE），而 Controller 上
    // 这些参数都是 required=false —— 漏判就是 500。
    if (status != null && !status.isBlank() && !STATUSES.contains(status)) {
      throw bad("状态只能是 " + STATUSES + " 之一，收到的是 " + status);
    }
    if (sort != null && !sort.isBlank() && !SORTS.contains(sort)) {
      throw bad("排序只能是 " + SORTS + " 之一，收到的是 " + sort);
    }
    int limit = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int offset = Math.max(page, 0) * limit;
    String column =
        switch (sort == null ? "" : sort) {
          case "CREATED_AT" -> "w.created_at";
          case "WAVE_CODE" -> "w.wave_code";
          case "ORDER_COUNT" -> "order_count";
          case "PROGRESS" -> "picked_line_count";
          default -> "w.planned_pick_at";
        };

    var where = new ArrayList<String>();
    var args = new ArrayList<Object>();
    args.add(tenant);
    if (warehouseId != null) {
      where.add("w.warehouse_id = ?");
      args.add(warehouseId);
    }
    if (status != null && !status.isBlank()) {
      where.add("w.status = ?");
      args.add(status);
    }
    if (carrierCode != null && !carrierCode.isBlank()) {
      where.add("w.carrier_code = ?");
      args.add(carrierCode);
    }
    if (zoneId != null) {
      where.add("w.zone_id = ?");
      args.add(zoneId);
    }
    if (keyword != null && !keyword.isBlank()) {
      where.add("(w.wave_code ilike ? or exists (select 1 from pick_wave_task m"
          + " join outbound_order o on o.tenant_id=m.tenant_id and o.id=m.outbound_order_id"
          + " where m.tenant_id=w.tenant_id and m.wave_id=w.id and o.order_code ilike ?))");
      args.add("%" + keyword.trim() + "%");
      args.add("%" + keyword.trim() + "%");
    }
    String clause = where.isEmpty() ? "" : " and " + String.join(" and ", where);

    long total =
        ((Number)
                sql.fetchOne("select count(*) from pick_wave w where w.tenant_id = ?" + clause,
                    args.toArray())
                    .get(0, Number.class))
            .longValue();

    var items =
        sql.fetch(
                WAVE_SELECT + " where w.tenant_id = ?" + clause
                    + " order by " + column + " desc, w.id limit ? offset ?",
                all(args, limit, offset))
            .map(r -> waveRow(r));

    return new PickWavePage(items, Math.max(page, 0), limit, total, summary(warehouseId));
  }

  /** 详情 = 单头 + 成员（按位置） + 流水 + 合并报告。 */
  public PickWaveDetail detail(UUID waveId) {
    UUID tenant = currentTenant();
    // 先查带 join 的完整单头：zone_code / rule_code 只存在于 WAVE_SELECT 的 join 里，
    // 从 requireWave 的 `select *` 上读会报「Field "zone_code" is not contained in
    // row type」（pick_wave 自身没有这一列，它来自 warehouse_location 的 join）。
    Record wave = sql.fetchOne(WAVE_SELECT + " where w.tenant_id = ? and w.id = ?", tenant, waveId);
    if (wave == null) throw notFound("波次 " + waveId + " 不存在");

    var members =
        sql.fetch(
                """
                select m.position, m.pick_task_id, m.carrier_code, m.zone_id, m.planned_pick_at,
                       m.outbound_order_id, z.code as zone_code,
                       t.task_code, t.status as task_status, t.planned_line_count, t.planned_quantity,
                       t.picked_line_count, t.picked_quantity,
                       o.order_code, o.status as outbound_order_status
                  from pick_wave_task m
                  join pick_task t on t.tenant_id = m.tenant_id and t.id = m.pick_task_id
                  join outbound_order o on o.tenant_id = m.tenant_id and o.id = m.outbound_order_id
                  left join warehouse_location z on z.tenant_id = m.tenant_id and z.id = m.zone_id
                 where m.tenant_id = ? and m.wave_id = ?
                 order by m.position
                """,
                tenant, waveId)
            .map(r -> memberRow(r));

    var events =
        sql.fetch(
                "select id, event_type, from_status, to_status, actor_user_id, occurred_at, detail"
                    + " from pick_wave_event where tenant_id=? and wave_id=?"
                    + " order by occurred_at, id",
                tenant, waveId)
            .map(
                r ->
                    new PickWaveEventRow(
                        r.get("id", UUID.class),
                        r.get("event_type", String.class),
                        eventLabel(r.get("event_type", String.class)),
                        r.get("from_status", String.class),
                        r.get("to_status", String.class),
                        r.get("actor_user_id", UUID.class),
                        r.get("occurred_at", OffsetDateTime.class),
                        r.get("detail", String.class)));

    int detached =
        sql.fetchOne(
                "select count(*) from pick_wave_event where tenant_id=? and wave_id=?"
                    + " and event_type='TASK_DETACHED'",
                tenant, waveId)
            .get(0, Number.class)
            .intValue();
    Record rule =
        wave.get("rule_id", UUID.class) == null
            ? null
            : sql.fetchOne("select max_order_count from pick_wave_rule where tenant_id=? and id=?",
                tenant, wave.get("rule_id", UUID.class));

    var report =
        new PickWaveReport(
            wave.get("merge_basis", String.class),
            "承运渠道 " + wave.get("carrier_code", String.class)
                + (wave.get("zone_code", String.class) == null
                    ? " / 跨区或库区不限"
                    : " / 库区 " + wave.get("zone_code", String.class)),
            rule == null ? 0 : rule.get("max_order_count", Integer.class),
            detached,
            "合并只影响拣货效率，不改变订单粒度：成员一行就是一张出库单的一张拣货任务，"
                + "波次完成后仍需按订单拆分复核与打包（8.3.3.1）。",
            "库区由取货库位沿 8.1.2.1 库位树上行得到（" + ZONE_BASIS + "）");

    return new PickWaveDetail(
        waveRow(wave),
        members,
        events,
        report,
        "波次不搬货、不动预占：成员各自的实拣与完成仍由 8.3.2.1 记录。");
  }

  // ================================================================ 内部辅助

  /** 从候选池里挑出这一波的成员，并按合并键收窄。 */
  private List<Record> selectMembers(PickWaveCreateInput input, Record rule, List<Record> pool) {
    // ---- 手工指定成员
    if (rule == null && input.pickTaskIds() != null && !input.pickTaskIds().isEmpty()) {
      var wanted = new java.util.LinkedHashSet<>(input.pickTaskIds());
      var picked = new ArrayList<Record>();
      for (Record r : pool) {
        if (wanted.contains(r.get("pick_task_id", UUID.class))) picked.add(r);
      }
      var missing = new java.util.LinkedHashSet<>(wanted);
      for (Record r : picked) missing.remove(r.get("pick_task_id", UUID.class));
      if (!missing.isEmpty()) {
        throw bad("这些拣货任务不能并进波次：" + missing
            + "。它们要么不在本仓，要么出库单不在「拣货中」，要么已经进了别的未完波次。");
      }
      var carriers = new java.util.LinkedHashSet<String>();
      for (Record r : picked) carriers.add(r.get("carrier_code", String.class));
      if (carriers.size() > 1) {
        throw bad("波次按承运渠道合并，这几张出库单的承运渠道不一致：" + carriers
            + "。请分波次并入。");
      }
      return picked;
    }

    // ---- 按规则挑一个合并键（不给 carrierCode / zoneId 时挑候选最多的那一组）
    String carrier = input.carrierCode() == null ? null : input.carrierCode();
    UUID zone = input.zoneId();

    var scored = new ArrayList<Record>();
    for (Record r : pool) {
      String c = r.get("carrier_code", String.class);
      UUID z = r.get("zone_id", UUID.class);
      // 手工指定了合并键就只取那一组；没指定就只取规则自己认领的那一组。
      if (carrier != null && !carrier.isBlank() && !carrier.equals(c)) continue;
      if (zone != null && !zone.equals(z)) continue;
      if (rule != null) {
        // 只取命中了**这一条**规则的候选。承运渠道 / 库区 / 时段三个条件
        // CANDIDATE_SELECT 的 lateral 已经判过了，这里不再重复一遍 ——
        // （曾经在这里多写了一个「规则限定了承运渠道就跳过」的条件，
        //   结果把**每一条**限定渠道的规则的候选全跳掉了：限定渠道恰恰是
        //   最常见的用法，一开就报「没有认领到任何出库单」，而 lateral 明明匹配上了。）
        if (!rule.get("id", UUID.class).equals(r.get("matched_rule_id", UUID.class))) continue;
      } else if (r.get("matched_rule_id", UUID.class) == null) {
        // 既没给规则也没给手工成员：只取命中了某条生效规则的。
        continue;
      }
      if (r.get("in_wave_id", UUID.class) != null) continue;
      scored.add(r);
    }
    if (scored.isEmpty()) {
      throw bad(rule == null
          ? "这个仓里现在没有可合并的出库单：需要拣货任务处于「已生成/拣货中」、"
              + "出库单处于「拣货中」，且已被某条生效的合并规则认领。"
              + "请先配置合并规则（承运渠道 / 库区 / 时段 / 订单数上限）。"
          : "规则 " + rule.get("rule_code", String.class) + " 现在没有认领到任何出库单："
              + "它的承运渠道「" + text(rule.get("carrier_match", String.class))
              + "」/ 库区「" + (rule.get("zone_id", UUID.class) == null ? "不限"
                  : rule.get("zone_id", UUID.class))
              + "」/ 时段 [" + rule.get("time_slot_start", Integer.class) + ","
              + rule.get("time_slot_end", Integer.class) + ")"
              + " 在这个仓里没有匹配的待拣出库单（或都已经在别的未完波次里）。"
              + "候选池共 " + pool.size() + " 张，样本："
              + pool.stream().limit(3)
                  .map(r -> r.get("order_code", String.class) + "/承运="
                      + r.get("carrier_code", String.class) + "/库区="
                      + r.get("zone_id", UUID.class) + "/时区小时="
                      + r.get("local_hour", Integer.class) + "/命中规则="
                      + r.get("matched_rule_id", UUID.class) + "/在波中="
                      + r.get("in_wave_id", UUID.class))
                  .toList());
    }
    // 合并键候选：按 (carrier, zone) 分组，取成员最多的一组；并列时按编码升序取第一个，
    // 保证同一批输入永远得到同一个答案（不取决于扫描顺序）。
    var byKey = new LinkedHashMap<MergeKey, List<Record>>();
    for (Record r : scored) {
      byKey
          .computeIfAbsent(
              new MergeKey(
                  input.warehouseId(),
                  r.get("carrier_code", String.class),
                  r.get("zone_id", UUID.class)),
              k -> new ArrayList<>())
          .add(r);
    }
    var bestKey =
        byKey.entrySet().stream()
            .min(
                (java.util.Comparator<Map.Entry<MergeKey, List<Record>>>)
                    (x, y) -> {
                      int c = Integer.compare(y.getValue().size(), x.getValue().size());
                      return c != 0 ? c : x.getKey().toString().compareTo(y.getKey().toString());
                    })
            .orElseThrow();
    List<Record> group = bestKey.getValue();

    // 订单数上限（蓝图点名的第三个合并维度）。撞顶后剩下的等下一波 —— V113 §2 说清了
    // 为什么不能同时开两个同键波次。
    int cap =
        rule == null
            ? group.size()
            : Math.max(1, rule.get("max_order_count", Integer.class));
    return group.size() <= cap ? group : new ArrayList<>(group.subList(0, cap));
  }

  /**
   * 该合并键上「认领了但进不去」的出库单数。
   *
   * <p><b>刻意现查一遍候选池</b>，不接着用建波次时那份：建波次时那份是在成员
   * 落库<b>之前</b>取的，{@code in_wave_id} 全是 null —— 用它算出来的「在等 N 张」
   * 会把刚刚并进去的那几张也算成在等，报一个比真实值大的数。板上写着「还有 3 张在等」
   * 而实际只有 1 张，看的人会去催一个已经排好波的单。
   */
  private int waitingCount(UUID warehouseId, String carrier, UUID zoneId) {
    if (openWaveFor(new MergeKey(warehouseId, carrier, zoneId)) == null) return 0;
    int n = 0;
    for (PickWaveCandidateRow c : candidatePool(warehouseId)) {
      if (c.matchedRuleId() == null) continue;
      if (c.blocked()) continue;
      if (!carrier.equals(c.carrierCode())) continue;
      if (zoneId == null ? c.zoneId() != null : !zoneId.equals(c.zoneId())) continue;
      n++;
    }
    return n;
  }

  private PickWaveCandidateGroup groupOf(List<PickWaveCandidateRow> rows, UUID warehouseId) {
    var first = rows.get(0);
    UUID ruleId = null;
    String ruleCode = null;
    String ruleName = null;
    int cap = 0;
    int unruled = 0;
    int blocked = 0;
    for (var r : rows) {
      if (r.matchedRuleId() != null && ruleId == null) {
        ruleId = r.matchedRuleId();
        ruleCode = r.matchedRuleCode();
        ruleName = r.matchedRuleName();
      }
      if (r.matchedRuleId() == null) unruled++;
      if (r.blocked()) blocked++;
    }
    Record open =
        openWaveFor(new MergeKey(warehouseId, first.carrierCode(), first.zoneId()));
    Record rule =
        ruleId == null
            ? null
            : sql.fetchOne(
                "select max_order_count from pick_wave_rule where tenant_id=? and id=?",
                currentTenant(), ruleId);
    cap = rule == null ? rows.size() - unruled : rule.get("max_order_count", Integer.class);

    // open == null → 现在就能并进去的 = 认领了且没进别的波次的；
    // open != null → 这个键上已有未完波次，全部在等（这正是 §2 的代价，必须报出来）。
    int free = rows.size() - unruled - blocked;
    int eligible = open == null ? Math.min(free, Math.max(cap, 0)) : 0;
    int waiting = open == null ? Math.max(free - Math.max(cap, 0), 0) : free;

    return new PickWaveCandidateGroup(
        first.carrierCode(), first.zoneId(), first.zoneCode(), ruleId, ruleCode, ruleName, cap,
        eligible, waiting, unruled,
        open == null ? null : open.get("id", UUID.class),
        open == null ? null : open.get("wave_code", String.class),
        rows);
  }

  private PickWaveCandidateRow candidateRow(Record r) {
    boolean blocked = r.get("in_wave_id", UUID.class) != null;
    String blockedReason =
        blocked
            ? "已在波次 " + r.get("in_wave_code", String.class) + " 里（该波次还没收工）"
            : null;
    UUID pickTaskId = r.get("pick_task_id", UUID.class);
    String taskCode = r.get("task_code", String.class);
    UUID outboundOrderId = r.get("outbound_order_id", UUID.class);
    String orderCode = r.get("order_code", String.class);
    String carrierCode = r.get("carrier_code", String.class);
    UUID zoneId = r.get("zone_id", UUID.class);
    String zoneCode = r.get("zone_code", String.class);
    boolean crossZone = intOf(r, "zone_count") > 1;
    int plannedLineCount = intOf(r, "planned_line_count");
    BigDecimal plannedQuantity = decimal(r, "planned_quantity");
    OffsetDateTime orderCreatedAt = r.get("order_created_at", OffsetDateTime.class);
    int localHour = intOf(r, "local_hour");
    UUID matchedRuleId = r.get("matched_rule_id", UUID.class);
    String matchedRuleCode = r.get("rule_code", String.class);
    String matchedRuleName = r.get("rule_name", String.class);
    return new PickWaveCandidateRow(
        pickTaskId,
        taskCode,
        outboundOrderId,
        orderCode,
        carrierCode,
        zoneId,
        zoneCode,
        crossZone,
        plannedLineCount,
        plannedQuantity,
        orderCreatedAt,
        localHour,
        matchedRuleId,
        matchedRuleCode,
        matchedRuleName,
        blocked,
        blockedReason);
  }

  private PickWaveRuleRow ruleRow(Record r, int waiting, int activeTaskCount) {
    String carrier = r.get("carrier_match", String.class);
    UUID zoneId = r.get("zone_id", UUID.class);
    int start = intOf(r, "time_slot_start");
    int end = intOf(r, "time_slot_end");
    return new PickWaveRuleRow(
        r.get("id", UUID.class),
        r.get("rule_code", String.class),
        r.get("rule_name", String.class),
        r.get("warehouse_id", UUID.class),
        carrier,
        carrier == null || carrier.isBlank(),
        zoneId,
        r.get("zone_code", String.class),
        zoneId == null,
        start,
        end,
        slotLabel(start, end),
        intOf(r, "max_order_count"),
        intOf(r, "priority"),
        r.get("status", String.class),
        r.get("retired_at", OffsetDateTime.class),
        r.get("retire_reason", String.class),
        activeTaskCount,
        waiting,
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private PickWaveMemberRow memberRow(Record r) {
    int plannedLines = intOf(r, "planned_line_count");
    int pickedLines = intOf(r, "picked_line_count");
    return new PickWaveMemberRow(
        intOf(r, "position"),
        r.get("pick_task_id", UUID.class),
        r.get("task_code", String.class),
        r.get("task_status", String.class),
        pickTaskStatusLabel(r.get("task_status", String.class)),
        r.get("outbound_order_id", UUID.class),
        r.get("order_code", String.class),
        r.get("outbound_order_status", String.class),
        r.get("carrier_code", String.class),
        r.get("zone_code", String.class),
        plannedLines,
        decimal(r, "planned_quantity"),
        pickedLines,
        decimal(r, "picked_quantity"),
        ratio(pickedLines, plannedLines),
        pickedLines == plannedLines && plannedLines > 0,
        r.get("planned_pick_at", OffsetDateTime.class));
  }

  private PickWaveRow waveRow(Record r) {
    int plannedLines = intOf(r, "planned_line_count");
    int pickedLines = intOf(r, "picked_line_count");
    int terminal = intOf(r, "terminal_task_count");
    int tasks = intOf(r, "task_count");
    PickWaveProgress progress =
        new PickWaveProgress(
            intOf(r, "order_count"),
            tasks,
            plannedLines,
            decimal(r, "planned_quantity"),
            pickedLines,
            decimal(r, "picked_quantity"),
            terminal,
            ratio(pickedLines, plannedLines),
            tasks > 0 && terminal == tasks);
    String status = r.get("status", String.class);
    return new PickWaveRow(
        r.get("id", UUID.class),
        r.get("wave_code", String.class),
        status,
        PickWaveStatus.valueOf(status).label(),
        r.get("warehouse_id", UUID.class),
        r.get("carrier_code", String.class),
        r.get("zone_id", UUID.class),
        r.get("zone_code", String.class),
        r.get("rule_id", UUID.class),
        r.get("rule_code", String.class),
        r.get("assignee_user_id", UUID.class),
        r.get("planned_pick_at", OffsetDateTime.class),
        r.get("started_at", OffsetDateTime.class),
        r.get("completed_at", OffsetDateTime.class),
        r.get("cancelled_at", OffsetDateTime.class),
        r.get("cancel_reason", String.class),
        progress,
        r.get("merge_basis", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class),
        waveMessage(status, progress));
  }

  /**
   * 汇总。
   *
   * <p>四个状态计数用一条 {@code count(*) filter (...)} 算完；未完波次里的出库单数
   * 与件数则按<b>波次单头 ⋈ 成员 ⋈ 拣货任务</b>聚合 —— 与列表页用的是同一条 join，
   * 所以「汇总里的件数」与「列表里每行相加」一定相等。
   */
  private PickWaveSummary summary(UUID warehouseId) {
    var args = new ArrayList<Object>();
    args.add(currentTenant());
    String filter = "";
    if (warehouseId != null) {
      filter = " and w.warehouse_id = ?";
      args.add(warehouseId);
    }
    Record r =
        sql.fetchOne(
            """
            select count(*) filter (where w.status='PLANNED') as planned_count,
                   count(*) filter (where w.status='PICKING') as picking_count,
                   count(*) filter (where w.status='COMPLETED') as completed_count,
                   count(*) filter (where w.status='CANCELLED') as cancelled_count,
                   coalesce(sum(
                     case when w.status in ('PLANNED','PICKING') then
                       (select count(*) from pick_wave_task m
                         where m.tenant_id = w.tenant_id and m.wave_id = w.id) else 0 end),0)
                     as open_task_count,
                   coalesce(sum(
                     case when w.status in ('PLANNED','PICKING') then
                       (select coalesce(sum(t.planned_quantity),0) from pick_wave_task m
                          join pick_task t on t.tenant_id=m.tenant_id and t.id=m.pick_task_id
                         where m.tenant_id = w.tenant_id and m.wave_id = w.id) else 0 end),0)
                     as open_planned_quantity,
                   coalesce(sum(
                     case when w.status in ('PLANNED','PICKING') then
                       (select coalesce(sum(t.picked_quantity),0) from pick_wave_task m
                          join pick_task t on t.tenant_id=m.tenant_id and t.id=m.pick_task_id
                         where m.tenant_id = w.tenant_id and m.wave_id = w.id) else 0 end),0)
                     as open_picked_quantity
              from pick_wave w
             where w.tenant_id = ?
            """
                + filter,
            args.toArray());
    return new PickWaveSummary(
        intOf(r, "planned_count"),
        intOf(r, "picking_count"),
        intOf(r, "completed_count"),
        intOf(r, "cancelled_count"),
        intOf(r, "open_task_count"),
        r.get("open_planned_quantity", BigDecimal.class),
        r.get("open_picked_quantity", BigDecimal.class));
  }

  private String waveMessage(String status, PickWaveProgress progress) {
    if (status.equals("COMPLETED")) return "已合并完成：出库单各自按 8.3.3.1 逐单复核与打包";
    if (status.equals("CANCELLED")) return "已取消：成员工单已放回候选池，货仍在原库位";
    if (progress.taskCount() == 0) return "没有成员（异常：空波次既不能放行也不能完成）";
    int open = progress.taskCount() - progress.terminalTaskCount();
    if (open == 0) return "全部 " + progress.taskCount() + " 张工单已终态，可以完成波次";
    return "还有 " + open + " 张拣货任务在推进（" + progress.pickedLineCount() + "/"
        + progress.plannedLineCount() + " 段拣齐）";
  }

  private String mergeBasis(
      String carrier, String zoneCode, Record rule, int taken, int poolSize, boolean manual) {
    StringBuilder sb = new StringBuilder();
    sb.append("合并维度：承运渠道 = ").append(carrier);
    sb.append("；库区 = ").append(zoneCode == null ? "跨区或库区不限" : zoneCode).append("。");
    if (manual) {
      sb.append("成员由调用方手工指定，共 ").append(taken).append(" 张出库单");
    } else if (rule != null) {
      sb.append("按规则 ").append(rule.get("rule_code", String.class))
          .append("（时段 ").append(slotLabel(
                  rule.get("time_slot_start", Integer.class),
                  rule.get("time_slot_end", Integer.class)))
          .append(" 仓本地，订单数上限 ")
          .append(rule.get("max_order_count", Integer.class))
          .append("）取前 ").append(taken).append(" 张");
      sb.append("，该键下候选 ").append(poolSize).append(" 张");
    }
    sb.append("。位置按出库单创建时刻升序分配，由 V113 触发器重排为 1..n 连续。");
    sb.append("合并不改变订单粒度：每张成员各自一张拣货任务，完成后按订单复核与打包。");
    return sb.toString();
  }

  private void event(
      UUID waveId, String type, String from, String to, UUID actorId, String detail) {
    sql.execute(
        "insert into pick_wave_event"
            + " (id,tenant_id,wave_id,event_type,from_status,to_status,actor_user_id,occurred_at,detail)"
            + " values(?,?,?,?,?,?,?,?::timestamptz,?)",
        UUID.randomUUID(), currentTenant(), waveId, type, from, to, actorId, OffsetDateTime.now(),
        detail);
  }

  private Record requireWave(UUID waveId) {
    Record wave =
        sql.fetchOne("select * from pick_wave where tenant_id=? and id=?", currentTenant(), waveId);
    if (wave == null) throw notFound("波次 " + waveId + " 不存在");
    return wave;
  }

  private Record requireRule(UUID ruleId) {
    Record rule =
        sql.fetchOne("select * from pick_wave_rule where tenant_id=? and id=?",
            currentTenant(), ruleId);
    if (rule == null) throw notFound("合并规则 " + ruleId + " 不存在");
    return rule;
  }

  private int memberCount(UUID waveId) {
    return sql.fetchOne("select count(*) from pick_wave_task where tenant_id=? and wave_id=?",
            currentTenant(), waveId)
        .get(0, Number.class)
        .intValue();
  }

  private int openMemberCount(UUID waveId) {
    return sql.fetchOne(
            "select count(*) from pick_wave_task m"
                + " join pick_task t on t.tenant_id=m.tenant_id and t.id=m.pick_task_id"
                + " where m.tenant_id=? and m.wave_id=? and t.status not in ('COMPLETED','CANCELLED')",
            currentTenant(), waveId)
        .get(0, Number.class)
        .intValue();
  }

  private String timezoneOf(UUID warehouseId) {
    Record w =
        sql.fetchOne("select timezone from warehouse where tenant_id=? and id=?",
            currentTenant(), warehouseId);
    return w == null ? null : w.get("timezone", String.class);
  }

  private String warehouseOf(UUID warehouseId) {
    Record w =
        sql.fetchOne("select id from warehouse where tenant_id=? and id=?",
            currentTenant(), warehouseId);
    return w == null ? null : w.get("id", UUID.class).toString();
  }

  private String zoneOf(UUID zoneId, UUID warehouseId) {
    Record z =
        sql.fetchOne("select id from warehouse_location where tenant_id=? and id=? and warehouse_id=?",
            currentTenant(), zoneId, warehouseId);
    return z == null ? null : z.get("id", UUID.class).toString();
  }

  private String zoneNames(java.util.Set<UUID> zones) {
    var names = new ArrayList<String>();
    for (UUID z : zones) {
      Record r =
          sql.fetchOne("select code from warehouse_location where tenant_id=? and id=?",
              currentTenant(), z);
      names.add(r == null ? z.toString() : r.get("code", String.class));
    }
    return String.join("、", names);
  }

  /** 计划的拣货时刻：仓本地今天的时段起点。 */
  private OffsetDateTime slotStartToday(Integer slotStart, String timezone) {
    ZoneId zone = ZoneId.of(timezone);
    return LocalDate.now(zone).atStartOfDay(zone).plusHours(slotStart == null ? 0 : slotStart)
        .toOffsetDateTime();
  }

  /** 波次号：PW-&lt;UTC 日期&gt;-&lt;8 位十六进制&gt;，服务端派生，不接受调用方自填。 */
  private String nextCode(String prefix) {
    String date = OffsetDateTime.now(java.time.ZoneOffset.UTC).toLocalDate().toString()
        .replace("-", "");
    return prefix + "-" + date + "-"
        + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
  }

  private static String slotLabel(int start, int end) {
    return String.format("%02d:00-%02d:00（仓本地）", start, end);
  }

  private static String eventLabel(String type) {
    for (PickWaveEventType t : PickWaveEventType.values()) if (t.name().equals(type)) return t.label();
    return type;
  }

  private static String pickTaskStatusLabel(String status) {
    for (PickTaskModels.PickTaskStatus s : PickTaskModels.PickTaskStatus.values()) {
      if (s.name().equals(status)) return s.label();
    }
    return status;
  }

  private static BigDecimal ratio(int numerator, int denominator) {
    if (denominator <= 0) return null;
    return BigDecimal.valueOf(numerator)
        .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
  }

  /**
   * 计数列的读法。
   *
   * <p>刻意不像 BigDecimal 那样把 null 当 0：计数列在 {@code left join lateral} 的
   * 聚合里是真的可能为 null（波次一个成员都没有时 {@code count(*)} 在左连接右侧是 NULL），
   * 直接拆箱会在看板上炸成 500，而「一个成员都没有」本来就是一个要显示出来的状态。
   */
  private static int intOf(Record r, String field) {
    Integer v = r.get(field, Integer.class);
    return v == null ? 0 : v;
  }

  private static BigDecimal decimal(Record r, String field) {
    BigDecimal v = r.get(field, BigDecimal.class);
    return v == null ? BigDecimal.ZERO : v;
  }

  private static String text(String s) {
    return s == null ? "" : s.trim();
  }

  /**
   * 时刻解析：<b>显式带偏移量</b>，不走 {@code Timestamp.valueOf(LocalDateTime)} ——
   * 那条路会把东八区的时间静默偏 −28800 秒（PDA 时钟比 DB 时钟靠前几个小时时，
   * 计划拣货时间会「早到凌晨」，且没有任何报错）。
   */
  private static OffsetDateTime instant(String raw, String what) {
    if (raw == null || raw.isBlank()) return OffsetDateTime.now();
    try {
      return OffsetDateTime.parse(raw.trim());
    } catch (DateTimeParseException e) {
      try {
        return java.time.LocalDateTime.parse(raw.trim())
            .atZone(ZoneId.systemDefault())
            .toOffsetDateTime();
      } catch (DateTimeParseException e2) {
        throw bad(what + "不是合法时刻：" + raw + "（请带偏移量，例如 2026-10-14T09:00:00+08:00）");
      }
    }
  }

  private static Object[] all(List<Object> args, Object... extra) {
    var out = new ArrayList<Object>(args);
    out.addAll(List.of(extra));
    return out.toArray();
  }

  private UUID currentTenant() {
    return UUID.fromString(sql.fetchOne("select current_setting('app.current_tenant', true)")
        .get(0, String.class));
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}