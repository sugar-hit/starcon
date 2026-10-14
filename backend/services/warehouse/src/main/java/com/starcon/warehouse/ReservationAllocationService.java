package com.starcon.warehouse;

import static com.starcon.warehouse.ReservationAllocationModels.*;
import static com.starcon.warehouse.ReservationModels.ReservationCapability;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 7.2.1.2 预占分配明细。
 *
 * <p>蓝图：「展示一个预占需求实际分配到了哪些 bucket（哪个仓、哪个货位、哪个批次），以及每个分配的
 * held→终态迁移记录。」输出「分配明细行 + 状态迁移时间线」。
 *
 * <p><b>为什么分配到具体 bucket 是前提</b>（蓝图关系句原文）：只记总量的话，
 * 「这 20 件到底是从哪几个批次出的」「仓储成本摊到哪个 SKU 上」这两个问题都答不出来。
 * 所以本表的每一行都必须指着一个（仓, 货位, 批次）三元组，没有「分配到某个抽象池子」的写法。
 *
 * <p>七条实现纪律：
 *
 * <ol>
 *   <li><b>汇总不受分页与筛选影响</b>。它回答「这笔预占一共圈了多少」，拿筛选后的数当汇总会让
 *       「切了个筛选怎么总量变了」看起来像数据丢了。
 *   <li><b>bucket 数与分配笔数分开报</b>：同一个 bucket 分两次圈只算一个 bucket。
 *       只报笔数的话，拆单圈货会把「圈了几个地方」虚高一倍。
 *   <li><b>分录的时间线按发生时刻正序</b>，且同刻并列时以 id 收尾 —— 否则同一页两次请求
 *       顺序可能不同（同一毫秒内确实会发生）。
 *   <li><b>时间一律带偏移量</b>：库里是 timestamptz，绑定时写 {@code ?::timestamptz}。
 *       用 {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800s。
 *   <li><b>写操作必须显式带上发生时刻</b>（{@code allocatedAt} / {@code occurredAt}）。
 *       跨服务调用方与本服务之间有时钟差，用服务端当前时间会把「什么时候发生的」换成
 *       「本服务什么时候知道的」，而时间线正是批次追溯的凭据。
 *   <li><b>分配状态变，预占状态跟着变</b>：分配全部承诺 → 预占进 COMMITTED；
 *       分配全部消耗且覆盖需求量 → 预占进 CONSUMED。不同步的话预占会一直停在 HELD，
 *       而蓝图 7.2.1.3 的超时清理会把已承诺发货的货误释放，直接造成超卖。
 *   <li><b>释放与过期不是本模块的按钮</b>：属 7.2.1.3，本模块既不提供入口，
 *       也不在能力声明里假装已接入。
 * </ol>
 *
 * <p>租户来自已认证成员关系（控制器传入），不接受任何客户端租户参数；RLS 在数据库层强制隔离，
 * 服务层的 {@code tenant_id} 谓词是第二道防线。
 */
@Service
public class ReservationAllocationService {

  /** 单页最大行数。与 ReservationService / WarehouseFacilityService 同口径。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 排序键白名单：排序键来自 query string，只允许这五个。 */
  static final List<String> SORTS =
      List.of("ALLOCATED_AT", "STATUS", "QUANTITY", "REMAINING", "BUCKET");

  private static final String TENANT = "current_setting('app.current_tenant', true)::uuid";

  private static final String NOTE =
      "分配明细 = 这笔预占圈到的每一个（仓 / 货位 / 批次）三元组；未分配量 = 需求量 − 已分配量，"
          + "与「剩余量（还欠多少没出库）」是两个问题。承诺与消耗由本模块记录，"
          + "释放与过期属 7.2.1.3，本页不提供释放入口。";

  private static final String TIMELINE_NOTE =
      "迁移流水是 append-only 审计凭据，DB 层拒绝 UPDATE / DELETE；分配级与预占级迁移按发生时刻混排，"
          + "因为「分配在 10:03 承诺、预占在 10:03 也承诺」拆成两段就看不出因果。";

  private static final String OWNER_NOTE =
      "预占需求、分配明细、迁移流水三张表同属 warehouse（V102 / V107 文件头第零节），"
          + "不跨服务读写 trade 的表；bucket 的仓 / 货位 / 批次只登记本服务引用登记表里的 id 与快照。";

  private static final String TIMEZONE_NOTE =
      "时间一律带时区偏移：库里是 timestamptz，界面按浏览器本地时区显示，"
          + "服务端不做二次时区转换（转换两次就会把偏移量算错 8 小时）";

  private final DSLContext sql;
  private final Clock clock;

  public ReservationAllocationService(DSLContext sql) {
    this.sql = sql;
    // 发生时刻优先用调用方给的；「现在」用系统时钟取，与 ReservationService 同理：
    // 固定时钟会让测试变成只测一个假想的时刻。
    this.clock = Clock.systemDefaultZone();
  }

  // ================================================================ 明细行

  /**
   * 分配明细行 + 预占抬头 + 汇总。
   *
   * @param tenantId 已认证成员解析出的租户；不接受客户端头
   * @param reservationId 蓝图写明的唯一输入
   */
  public ReservationAllocationPage allocations(
      UUID tenantId, UUID reservationId, ReservationAllocationQuery q) {
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, q.size() == null ? 20 : q.size()));
    int page = Math.max(0, q.page() == null ? 0 : q.page());

    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    filters(reservationId, q, where, binds);
    String sqlWhere = " where " + String.join(" and ", where);

    long total =
        sql.fetchOne("select count(*)" + FROM_CLAUSE + sqlWhere, binds.toArray()).get(0, Long.class);

    List<ReservationAllocationRow> rows =
        sql.fetch(
                selectRows() + sqlWhere + orderBy(q.sort()) + " limit ? offset ?",
                bindsToArray(binds, size, page * size))
            .map(this::row);

    return new ReservationAllocationPage(
        header(tenantId, reservationId),
        summary(tenantId, reservationId),
        rows,
        total,
        timelineTotal(tenantId, reservationId),
        page,
        size,
        NOTE);
  }

  /**
   * 行查询与计数查询**共用同一段 FROM**。
   *
   * <p>共用不是洁癖：关键字筛选要匹配仓编码 / 货位编码 / 批次号（用户在明细列上看到的那三列），
   * 所以 where 会引用 {@code w} / {@code l} / {@code lt}。若计数查询只写
   * {@code from reservation_allocation}，带关键字的请求会在 count 那一步报 bad SQL grammar ——
   * 而且只在用户真的敲了关键字时才炸，不敲关键字的用例全绿。
   */
  private static String selectRows() {
    return """
        select a.id, a.reservation_id, a.status,
               a.allocated_quantity, a.consumed_quantity, a.released_quantity, a.remaining,
               a.allocated_at, a.committed_at, a.settled_at, a.note,
               a.warehouse_id, w.code as warehouse_code, w.name as warehouse_name,
               a.location_id, l.code as location_code, l.name as location_name,
               a.lot_id, lt.lot_code,
               to_char(lt.expires_on,'YYYY-MM-DD') as lot_expires_on,
               -- 该分配名下的迁移条数：让页面不跳时间线也知道这条分配经历过几次迁移。
               (select count(*) from reservation_entry e
                 where e.tenant_id=a.tenant_id and e.allocation_id=a.id) as transition_count
        """
        + FROM_CLAUSE;
  }

  private static final String FROM_CLAUSE =
      """
          from reservation_allocation a
          join warehouse w on w.tenant_id=a.tenant_id and w.id=a.warehouse_id
          left join warehouse_location l on l.tenant_id=a.tenant_id and l.id=a.location_id
          left join inventory_lot lt on lt.tenant_id=a.tenant_id and lt.id=a.lot_id
      """;

  /**
   * 过滤条件拼装。
   *
   * <p>预占 id 作为**第一个绑定值**固定在 binds[0]，其余条件追加其后，
   * 这样 count / rows / 分页 limit 的绑定顺序始终一致 —— Java 没有具名绑定，
   * 一旦顺序错位就是「筛 A 却返回 B」这种最难查的 bug。
   */
  private void filters(
      UUID reservationId, ReservationAllocationQuery q, List<String> where, List<Object> binds) {
    where.add("a.tenant_id = " + TENANT);
    where.add("a.reservation_id = ?");
    binds.add(reservationId);

    if (q.statuses() != null && !q.statuses().isEmpty()) {
      List<String> names = new ArrayList<>();
      for (String raw : q.statuses()) {
        if (raw == null || raw.isBlank()) continue;
        String name = raw.toUpperCase(Locale.ROOT).trim();
        // 词表外的状态在 SQL 绑定**之前**就拒：绑进去只会得到一个「合法但永远为空」的结果集，
        // 界面上看起来像「这个状态真的一条都没有」。
        if (!statusNames().contains(name)) throw new IllegalArgumentException("不支持的分配状态：" + raw);
        names.add(name);
      }
      if (!names.isEmpty()) {
        where.add("a.status in (" + names.stream().map(n -> "?").collect(Collectors.joining(",")) + ")");
        binds.addAll(names);
      }
    }
    if (q.warehouseId() != null) {
      where.add("a.warehouse_id = ?");
      binds.add(q.warehouseId());
    }
    if (q.locationId() != null) {
      where.add("a.location_id = ?");
      binds.add(q.locationId());
    }
    if (q.lotId() != null) {
      where.add("a.lot_id = ?");
      binds.add(q.lotId());
    }
    if (q.keyword() != null && !q.keyword().isBlank()) {
      // 关键字覆盖「人能看到的五处」：仓编码 / 仓名 / 货位编码 / 货位名 / 批次号。
      // 少匹配一个，用户就得记住「批次在批次列、货位在货位列」才能找到一行。
      where.add(
          "(w.code ilike ? or w.name ilike ? or l.code ilike ? or l.name ilike ? or lt.lot_code ilike ?"
              + " or a.note ilike ?)");
      String like = "%" + q.keyword().trim() + "%";
      for (int i = 0; i < 6; i++) binds.add(like);
    }
  }

  private static Object[] bindsToArray(List<Object> binds, Object... tail) {
    Object[] all = new Object[binds.size() + tail.length];
    for (int i = 0; i < binds.size(); i++) all[i] = binds.get(i);
    for (int i = 0; i < tail.length; i++) all[binds.size() + i] = tail[i];
    return all;
  }

  private String orderBy(String sort) {
    String key = sort == null || sort.isBlank() ? "ALLOCATED_AT" : sort.toUpperCase(Locale.ROOT);
    if (!SORTS.contains(key)) throw new IllegalArgumentException("不支持的排序键：" + sort);
    // 一律以 a.id 收尾：排序键相同时必须有确定次序，否则同一页两次请求可能给出不同顺序。
    return switch (key) {
      case "STATUS" -> " order by a.status, a.id";
      case "QUANTITY" -> " order by a.allocated_quantity desc, a.id";
      case "REMAINING" -> " order by a.remaining desc, a.id";
      // bucket 排序用可读的编码而不是 uuid：同一仓内按 uuid 排出来的顺序对用户毫无意义。
      case "BUCKET" ->
          " order by w.code, coalesce(l.code,''), coalesce(lt.lot_code,''), a.id";
      default -> " order by a.allocated_at desc, a.id";
    };
  }

  // ================================================================ 汇总与抬头

  /**
   * 分配汇总。**不受分页与筛选影响**。
   *
   * <p>{@code bucket_count} 用「仓 | 货位 | 批次」的字符串三元组去重而不是 ROW 去重：
   * ROW 比较里 NULL 与 NULL 的相等性在不同写法下容易读错，而这里货位与批次本来就可空，
   * 写成字符串拼接后「同一个仓 + 都没有货位」的两行必然算同一个 bucket，没有解释空间。
   *
   * <p>allocated / consumed / released 是**全部**分配之和（含终态），
   * 因为「当初圈了多少、出了多少、还了多少」是三个历史事实，不会因为某一行进了终态就消失。
   */
  private ReservationAllocationSummary summary(UUID tenantId, UUID reservationId) {
    var r =
        sql.fetchOne(
            """
            select count(*) as total_count,
                   count(*) filter (where status='HELD')       as held_count,
                   count(*) filter (where status='COMMITTED') as committed_count,
                   count(*) filter (where status='CONSUMED')   as consumed_count,
                   count(*) filter (where status='RELEASED')   as released_count,
                   count(*) filter (where status='EXPIRED')    as expired_count,
                   coalesce(sum(allocated_quantity),0) as allocated_total,
                   coalesce(sum(consumed_quantity),0)  as consumed_total,
                   coalesce(sum(released_quantity),0)  as released_total,
                   coalesce(sum(remaining),0)         as remaining_total,
                   count(distinct warehouse_id::text||'|'
                                    ||coalesce(location_id::text,'')||'|'
                                    ||coalesce(lot_id::text,'')) as bucket_count
              from reservation_allocation
             where tenant_id = %s and reservation_id = ?
            """.formatted(TENANT),
            reservationId);

    Map<String, Long> counts = new LinkedHashMap<>();
    counts.put("HELD", r.get("held_count", Long.class));
    counts.put("COMMITTED", r.get("committed_count", Long.class));
    counts.put("CONSUMED", r.get("consumed_count", Long.class));
    counts.put("RELEASED", r.get("released_count", Long.class));
    counts.put("EXPIRED", r.get("expired_count", Long.class));

    BigDecimal requested = requestedQuantity(tenantId, reservationId);
    BigDecimal allocated = r.get("allocated_total", BigDecimal.class);
    // 未分配量按「非终态分配的圈定量」算：已释放 / 已过期的分配不再欠货。
    // 口径与 §二 一致 —— 父表 allocated_quantity 就是这么汇总的，两处必须同源，
    // 否则抬头的已分配与汇总的已分配会给出两个数。
    BigDecimal occupying =
        sql.fetchOne(
                """
                select coalesce(sum(allocated_quantity),0) from reservation_allocation
                 where tenant_id = %s and reservation_id = ?
                   and status not in ('RELEASED','EXPIRED')
                """.formatted(TENANT),
                reservationId)
            .get(0, BigDecimal.class);

    return new ReservationAllocationSummary(
        r.get("total_count", Long.class),
        counts,
        allocated,
        r.get("consumed_total", BigDecimal.class),
        r.get("released_total", BigDecimal.class),
        r.get("remaining_total", BigDecimal.class),
        requested.subtract(occupying),
        r.get("bucket_count", Long.class));
  }

  private BigDecimal requestedQuantity(UUID tenantId, UUID reservationId) {
    var one =
        sql.fetchOne(
            "select requested_quantity from inventory_reservation"
                + " where tenant_id = " + TENANT + " and id = ?",
            reservationId);
    if (one == null) throw notFound(reservationId);
    return one.get(0, BigDecimal.class);
  }

  /** 预占抬头。字段与 7.2.1.1 的 {@code ReservationRow} 同源，两页显示的数必须一致。 */
  private ReservationAllocationHeader header(UUID tenantId, UUID reservationId) {
    var r =
        sql.fetchOne(
            """
            select r.reservation_no, r.source_system, r.source_type,
                   r.source_ref_id, r.source_ref_code,
                   r.sku_id, r.legal_entity_id,
                   r.requested_quantity, r.allocated_quantity, r.consumed_quantity, r.remaining,
                   r.status, r.held_at, r.committed_at, r.settled_at, r.expires_at, r.note,
                   le.code as le_code, le.name as le_name,
                   sk.code as sku_code, sk.name as sku_name
              from inventory_reservation r
              left join legal_entity_ref le on le.tenant_id=r.tenant_id and le.id=r.legal_entity_id
              left join sku_ref sk on sk.tenant_id=r.tenant_id and sk.id=r.sku_id
             where r.tenant_id = %s and r.id = ?
            """.formatted(TENANT),
            reservationId);
    if (r == null) throw notFound(reservationId);

    var status = ReservationModels.ReservationStatus.valueOf(r.get("status", String.class));
    return new ReservationAllocationHeader(
        reservationId,
        r.get("reservation_no", String.class),
        r.get("source_system", String.class),
        ReservationModels.SourceSystem.valueOf(r.get("source_system", String.class)).label(),
        r.get("source_type", String.class),
        ReservationModels.SourceType.valueOf(r.get("source_type", String.class)).label(),
        r.get("source_ref_id", UUID.class),
        r.get("source_ref_code", String.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("legal_entity_id", UUID.class),
        r.get("le_code", String.class),
        r.get("requested_quantity", BigDecimal.class),
        r.get("allocated_quantity", BigDecimal.class),
        r.get("consumed_quantity", BigDecimal.class),
        r.get("remaining", BigDecimal.class),
        status.name(),
        status.label(),
        status == ReservationModels.ReservationStatus.CONSUMED
            || status == ReservationModels.ReservationStatus.RELEASED
            || status == ReservationModels.ReservationStatus.EXPIRED,
        r.get("held_at", OffsetDateTime.class),
        r.get("committed_at", OffsetDateTime.class),
        r.get("settled_at", OffsetDateTime.class),
        r.get("expires_at", OffsetDateTime.class),
        r.get("note", String.class));
  }

  // ================================================================ 时间线

  /** 迁移时间线。按发生时刻正序；同刻并列以 id 收尾，保证两次请求顺序一致。 */
  public ReservationAllocationTimeline timeline(
      UUID tenantId, UUID reservationId, ReservationAllocationTimelineQuery q) {
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, q.size() == null ? 50 : q.size()));
    int page = Math.max(0, q.page() == null ? 0 : q.page());

    // 先确认这条预占在当前租户下**看得见**，再查分录。
    // 不查这一步的话，别人拿着一个不属于自己的预占 id 会得到一个「空时间线」而不是 404 ——
    // 那是拿「查不到」冒充「不存在」，也让人以为「这笔预占从来没发生过迁移」。
    requestedQuantity(tenantId, reservationId);

    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    where.add("e.tenant_id = " + TENANT);
    where.add("e.reservation_id = ?");
    binds.add(reservationId);
    if (q.allocationId() != null) {
      where.add("e.allocation_id = ?");
      binds.add(q.allocationId());
    }
    if (q.transition() != null && !q.transition().isBlank()) {
      where.add("e.transition = ?");
      binds.add(transition(q.transition()).name());
    }
    String sqlWhere = " where " + String.join(" and ", where);

    long total =
        sql.fetchOne("select count(*) from reservation_entry e" + sqlWhere, binds.toArray())
            .get(0, Long.class);

    List<ReservationAllocationEntryRow> entries =
        sql.fetch(
                """
                select e.id, e.reservation_id, e.allocation_id, e.transition,
                       e.from_status, e.to_status, e.quantity, e.occurred_at, e.actor_id, e.reason,
                       w.code as warehouse_code, l.code as location_code, lt.lot_code
                  from reservation_entry e
                  left join reservation_allocation a
                         on a.tenant_id=e.tenant_id and a.id=e.allocation_id
                  left join warehouse w on w.tenant_id=e.tenant_id and w.id=a.warehouse_id
                  left join warehouse_location l on l.tenant_id=e.tenant_id and l.id=a.location_id
                  left join inventory_lot lt on lt.tenant_id=e.tenant_id and lt.id=a.lot_id
                """
                    + sqlWhere
                    + " order by e.occurred_at, e.id limit ? offset ?",
                bindsToArray(binds, size, page * size))
            .map(
                e ->
                    new ReservationAllocationEntryRow(
                        e.get("id", UUID.class),
                        e.get("reservation_id", UUID.class),
                        e.get("allocation_id", UUID.class),
                        e.get("allocation_id", UUID.class) == null ? "RESERVATION" : "ALLOCATION",
                        e.get("transition", String.class),
                        transitionLabel(e.get("transition", String.class)),
                        e.get("from_status", String.class),
                        e.get("to_status", String.class),
                        ReservationAllocationStatus.valueOf(e.get("to_status", String.class)).label(),
                        e.get("quantity", BigDecimal.class),
                        e.get("occurred_at", OffsetDateTime.class),
                        e.get("actor_id", UUID.class),
                        e.get("reason", String.class),
                        e.get("warehouse_code", String.class),
                        e.get("location_code", String.class),
                        e.get("lot_code", String.class)));

    return new ReservationAllocationTimeline(
        reservationId, entries, total, page, size, TIMELINE_NOTE);
  }

  private long timelineTotal(UUID tenantId, UUID reservationId) {
    return sql.fetchOne(
            "select count(*) from reservation_entry where tenant_id = " + TENANT
                + " and reservation_id = ?",
            reservationId)
        .get(0, Long.class);
  }

  // ================================================================ 下拉项

  /**
   * 下拉项。
   *
   * <p><b>仓 / 货位 / 批次的候选值只取这条预占实际出现过的</b>，不是全库：明细页的筛选器问的是
   * 「这笔预占圈过哪些地方」，把全库几千个批次倒给下拉框既慢又没意义。
   *
   * <p>{@code transitions} 刻意**不含**释放与过期：那是 7.2.1.3 的动作。
   * 在这里列出它等于给前端一个可以点、但点了什么也不发生的按钮。
   */
  public ReservationAllocationOptions options(UUID tenantId, UUID reservationId) {
    // 顺带确认预占存在：不存在时下拉项返回空会让页面显示成「这笔预占没有圈任何地方」。
    requestedQuantity(tenantId, reservationId);

    List<ReservationAllocationOption> statuses = new ArrayList<>();
    for (ReservationAllocationStatus s : ReservationAllocationStatus.values()) {
      statuses.add(
          new ReservationAllocationOption(
              s.name(), s.label(), s.terminal(), s.terminal() ? "终态，不可再迁出" : "仍在占额"));
    }
    List<ReservationAllocationOption> transitions = new ArrayList<>();
    for (ReservationAllocationTransition t : ReservationAllocationTransition.values()) {
      transitions.add(new ReservationAllocationOption(t.name(), t.label(), false, null));
    }

    return new ReservationAllocationOptions(
        statuses,
        transitions,
        bucketOptions(tenantId, reservationId, "a.warehouse_id", "w.code", "w.name"),
        bucketOptions(tenantId, reservationId, "a.location_id", "l.code", "l.name"),
        bucketOptions(tenantId, reservationId, "a.lot_id", "lt.lot_code", null),
        List.of(
            new ReservationAllocationOption("ALLOCATED_AT", "按圈定时间", false, null),
            new ReservationAllocationOption("STATUS", "按状态", false, null),
            new ReservationAllocationOption("QUANTITY", "按分配量", false, null),
            new ReservationAllocationOption("REMAINING", "按剩余量", false, null),
            new ReservationAllocationOption("BUCKET", "按 bucket", false, null)),
        targets(tenantId, reservationId),
        TIMEZONE_NOTE,
        OWNER_NOTE);
  }

  /**
   * 登记分配时的可选目标：租户在用的仓 / 货位，以及这笔预占那个 SKU 名下的在用批次。
   *
   * <p>与 {@link #bucketOptions} 那组候选值用途不同，必须分开：筛选器只关心「这笔预占圈过哪些」，
   * 而登记时恰恰需要**还没被圈过**的那些 —— 新建的预占一条分配都没有，
   * 拿筛选候选集去填登记表就是死结。
   *
   * <p>只给 {@code ACTIVE}：把已归档的仓、已过期的批次倒进选择框，只会让人选到一个
   * 提交后必然被拒的组合。
   *
   * <p>批次按<b>父预占的 SKU</b>过滤而不是全库：把别的商品的批次列出来让人选，
   * 十有八九会选错，而错的那个会被 DB 的守卫挡下、只留下一句「SKU 不是同一个商品」。
   * 在这里就按 SKU 筛掉，比让用户踩一次报错再学一次便宜得多。
   */
  private ReservationAllocationTargets targets(UUID tenantId, UUID reservationId) {
    var warehouseRows =
        sql.fetch(
                "select id, code, name from warehouse where tenant_id = " + TENANT
                    + " and status='ACTIVE' order by code",
                new Object[0])
            .map(
                r ->
                    new ReservationAllocationTarget(
                        r.get("id", UUID.class), null, r.get("code", String.class),
                        r.get("name", String.class)));

    var locationRows =
        sql.fetch(
                "select l.id, l.warehouse_id, l.code, l.name from warehouse_location l"
                    + " where l.tenant_id = " + TENANT
                    + " and l.status='ACTIVE'"
                    + " and exists (select 1 from warehouse w where w.tenant_id=l.tenant_id"
                    + "   and w.id=l.warehouse_id and w.status='ACTIVE')"
                    + " order by l.code",
                new Object[0])
            .map(
                r ->
                    new ReservationAllocationTarget(
                        r.get("id", UUID.class), r.get("warehouse_id", UUID.class),
                        r.get("code", String.class), r.get("name", String.class)));

    var lotRows =
        sql.fetch(
                "select id, lot_code from inventory_lot where tenant_id = " + TENANT
                    + " and status='ACTIVE' and sku_id = (select sku_id from inventory_reservation"
                    + "   where tenant_id = " + TENANT + " and id = ?)"
                    + " order by lot_code",
                new Object[] {reservationId})
            .map(
                r ->
                    new ReservationAllocationTarget(
                        r.get("id", UUID.class), null, r.get("lot_code", String.class), null));

    return new ReservationAllocationTargets(warehouseRows, locationRows, lotRows);
  }

  /**
   * 某个 bucket 维度上的候选项。
   *
   * <p>货位与批次可空，所以用 {@code having id is not null} 把「这一维没填」的组滤掉 ——
   * 直接 inner join 会把整行丢掉，数量口径就与明细对不上了。
   *
   * @param nameExpr 该维度的名称列；批次没有名称列（{@code inventory_lot} 只有 lot_code），
   *     传 {@code null} 表示这一维没有名称可读。刻意做成「可空的列表达式」而不是在 SQL 里
   *     写 {@code null as name}：那样 {@code group by} 里就得跟着写 {@code null}，
   *     而按常量分组是 PostgreSQL 直接拒绝的语法
   */
  private List<ReservationAllocationBucketOption> bucketOptions(
      UUID tenantId, UUID reservationId, String idExpr, String codeExpr, String nameExpr) {
    String groupBy = nameExpr == null ? idExpr + ", " + codeExpr : idExpr + ", " + codeExpr + ", " + nameExpr;
    return sql.fetch(
            """
            select %s as id, %s as code, %s as name,
                   sum(a.allocated_quantity) as quantity, count(*) as allocation_count
              from reservation_allocation a
              left join warehouse w on w.tenant_id=a.tenant_id and w.id=a.warehouse_id
              left join warehouse_location l on l.tenant_id=a.tenant_id and l.id=a.location_id
              left join inventory_lot lt on lt.tenant_id=a.tenant_id and lt.id=a.lot_id
             where a.tenant_id = %s and a.reservation_id = ?
             group by %s
            having %s is not null
             order by %s
            """
                .formatted(
                    idExpr, codeExpr, nameExpr == null ? "null" : nameExpr, TENANT, groupBy, idExpr, codeExpr),
            reservationId)
        .map(
            r ->
                new ReservationAllocationBucketOption(
                    r.get("id", UUID.class),
                    r.get("code", String.class),
                    r.get("name", String.class),
                    r.get("quantity", BigDecimal.class),
                    r.get("allocation_count", Long.class)));
  }

  /**
   * 链路各环节的接入情况。复用 7.2.1.1 的 {@code ReservationCapability}，
   * 不另造一份形状相同的类型（另造会让同一条链路的能力声明有两种表示）。
   *
   * <p><b>2026-10-14 更新</b>：{@code ALLOCATION_RELEASE} 由 {@code available=false} 翻成
   * {@code true}。这是 7.2.1.3 落地后的回填 —— 7.2.1.2 交付时释放与过期尚未交付，
   * 所以这里显式报了「未接入」并写明归属模块；现在 7.2.1.3 已交付
   * （手动释放 / 批量过期清理 / 过期时刻补写 / append-only 凭据），声明必须跟着变，
   * 否则前端会继续禁用一个已经能用的入口。
   */
  public List<ReservationCapability> capabilities() {
    return List.of(
        new ReservationCapability(
            "ALLOCATION_DETAIL", "分配明细", true, "7.2.1.2", "已接入 reservation_allocation"),
        new ReservationCapability(
            "ALLOCATION_REGISTER",
            "登记分配",
            true,
            "7.2.1.2",
            "已接入：建行即 HELD，终态只能由状态迁移产生；跨行守恒由 DB 触发器兜底"),
        new ReservationCapability(
            "ALLOCATION_COMMIT",
            "承诺分配",
            true,
            "7.2.1.2",
            "已接入：HELD → COMMITTED；全部分配承诺时父预占同步进 COMMITTED"),
        new ReservationCapability(
            "ALLOCATION_CONSUME",
            "消耗出库",
            true,
            "7.2.1.2",
            "已接入：COMMITTED → CONSUMED，支持部分出库；覆盖需求量时父预占同步进 CONSUMED"),
        new ReservationCapability(
            "ALLOCATION_RELEASE",
            "释放与过期",
            true,
            "7.2.1.3",
            "已接入（7.2.1.3）：写入 RELEASED / EXPIRED 分配与对应分录，"
                + "入口 POST /warehouse/inventory/reservations/{id}/release（手动）与 "
                + "POST /warehouse/inventory/reservations/expire-overdue（批量）；"
                + "COMMITTED 不参与自动过期，可用量的口径仍归 7.1.1.3"));
  }

  // ================================================================ 写入：登记分配

  /**
   * 登记分配：把这笔预占圈到一个 bucket 上。
   *
   * <p>三重校验的分工：
   *
   * <ul>
   *   <li>预占存在且未终结 → 404 / 409（父表已终结时再加分配等于往历史里补货）
   *   <li>仓 / 货位 / 批次存在且自洽 → 404 / 400（**先在 Java 里判**，因为复合外键冲突会被
   *       ApiErrorHandler 翻成一句笼统的 409，用户看不出是「货位不属于这个仓」）
   *   <li>跨行守恒（所有分配之和 ≤ 需求量）→ 交给 DB 触发器：Java 与 DB 之间存在裸 SQL 缝隙，
   *       而超卖是不可逆事故，必须在 DB 层兜住
   * </ul>
   *
   * <p>写入顺序固定为「先插分配、再插分录」：V107 的分录守卫要求该分配此刻的状态恰等于分录的
   * to_status，所以分录只能在分配行落库之后写。两者在同一事务里（调用方的
   * {@code AccessService.run} 已开事务），不会出现「有分配没有流水」的中间态。
   */
  public ReservationAllocationCreated allocate(
      UUID tenantId, UUID reservationId, ReservationAllocationCreateInput input) {
    if (input.allocatedQuantity() == null
        || input.allocatedQuantity().compareTo(BigDecimal.ZERO) <= 0) {
      throw new IllegalArgumentException("分配数量必须为正");
    }
    var reservation = lockOpenReservation(reservationId);
    requireWarehouse(input.warehouseId());
    requireLocationInWarehouse(input.warehouseId(), input.locationId());
    requireLotOfSku(input.lotId(), reservation.get("sku_id", UUID.class));

    OffsetDateTime allocatedAt =
        input.allocatedAt() == null ? now() : input.allocatedAt().truncatedTo(ChronoUnit.MILLIS);
    BigDecimal quantity = input.allocatedQuantity().setScale(6, RoundingMode.HALF_UP);
    UUID allocationId = UUID.randomUUID();

    sql.execute(
        "insert into reservation_allocation(id,tenant_id,reservation_id,warehouse_id,location_id,"
            + "lot_id,allocated_quantity,allocated_at,note)"
            + " values(?,?,?,?,?,?,?,?::timestamptz,?)",
        allocationId,
        tenantId,
        reservationId,
        input.warehouseId(),
        input.locationId(),
        input.lotId(),
        quantity,
        allocatedAt,
        input.note());

    UUID entryId = appendEntry(
        tenantId,
        reservationId,
        allocationId,
        "ALLOCATED",
        null,
        "HELD",
        quantity,
        allocatedAt,
        "登记分配");

    var after = parentRollup(reservationId);
    return new ReservationAllocationCreated(
        allocationId,
        reservationId,
        ReservationAllocationStatus.HELD.name(),
        ReservationAllocationStatus.HELD.label(),
        after.allocated(),
        after.requested().subtract(after.allocated()),
        entryId);
  }

  // ================================================================ 写入：状态迁移

  /**
   * 承诺分配：HELD → COMMITTED。
   *
   * <p>承诺时把 {@code expires_at} **保留**而不是清空：expires_at 是业务事实
   * （「这笔预占原本计划在 T 过期」），清空它会让事后对账失去依据。
   * 「承诺后不再自动 TTL 释放」由状态本身保证（V102 的超时谓词只看 HELD），不需要靠清空字段。
   */
  public ReservationAllocationTransitioned commit(
      UUID tenantId,
      UUID reservationId,
      UUID allocationId,
      ReservationAllocationTransitionInput input) {
    var allocation = lockAllocation(reservationId, allocationId);
    var from = ReservationAllocationStatus.valueOf(allocation.get("status", String.class));
    if (from != ReservationAllocationStatus.HELD) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "分配当前是 " + from.label() + "，只有预占中（HELD）的分配可以承诺");
    }
    var reservation = lockOpenReservation(reservationId);
    OffsetDateTime occurredAt =
        input == null || input.occurredAt() == null ? now() : input.occurredAt().truncatedTo(ChronoUnit.MILLIS);
    BigDecimal quantity = allocation.get("allocated_quantity", BigDecimal.class);

    sql.execute(
        "update reservation_allocation set status='COMMITTED', committed_at=?::timestamptz"
            + " where id=? and tenant_id=? and status='HELD'",
        occurredAt,
        allocationId,
        tenantId);

    UUID entryId =
        appendEntry(
            tenantId,
            reservationId,
            allocationId,
            "COMMITTED",
            "HELD",
            "COMMITTED",
            quantity,
            occurredAt,
            input == null ? null : input.reason());

    promoteReservationToCommitted(reservationId, occurredAt);

    var after = parentRollup(reservationId);
    return new ReservationAllocationTransitioned(
        allocationId,
        reservationId,
        from.name(),
        ReservationAllocationStatus.COMMITTED.name(),
        ReservationAllocationStatus.COMMITTED.name(),
        ReservationAllocationStatus.COMMITTED.label(),
        after.status(),
        ReservationModels.ReservationStatus.valueOf(after.status()).label(),
        after.consumed(),
        after.remaining(),
        entryId);
  }

  /**
   * 消耗出库：COMMITTED → CONSUMED，支持部分出库。
   *
   * <p>数量不传时取「该分配的剩余量」（一次性全出）；传了必须为正且不超过剩余量。
   * 部分出库是常态：一张订单拆单出库时，同一个 bucket 上的货分几次出，
   * 把它写成「必须一次性全出」会逼调用方去算剩余量，而算错的后果是静默的账实不符。
   *
   * <p>刻意**不允许** HELD → CONSUMED：没承诺就出库意味着货没圈住就走了，
   * 那正是超卖的定义。合法迁移矩阵由 V107 的分录守卫在 DB 层再钉一遍。
   */
  public ReservationAllocationTransitioned consume(
      UUID tenantId,
      UUID reservationId,
      UUID allocationId,
      ReservationAllocationTransitionInput input) {
    var allocation = lockAllocation(reservationId, allocationId);
    var from = ReservationAllocationStatus.valueOf(allocation.get("status", String.class));
    if (from != ReservationAllocationStatus.COMMITTED) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "分配当前是 " + from.label() + "，只有已承诺（COMMITTED）的分配可以消耗");
    }
    var reservation = lockOpenReservation(reservationId);
    OffsetDateTime occurredAt =
        input == null || input.occurredAt() == null ? now() : input.occurredAt().truncatedTo(ChronoUnit.MILLIS);

    BigDecimal remaining = allocation.get("remaining", BigDecimal.class);
    BigDecimal quantity =
        input == null || input.quantity() == null
            ? remaining
            : input.quantity().setScale(6, RoundingMode.HALF_UP);
    if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
      throw new IllegalArgumentException("消耗数量必须为正");
    }
    if (quantity.compareTo(remaining) > 0) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "消耗数量 " + quantity + " 超过该分配的剩余量 " + remaining);
    }

    sql.execute(
        "update reservation_allocation set status='CONSUMED',"
            + " consumed_quantity=consumed_quantity+?::numeric, settled_at=?::timestamptz"
            + " where id=? and tenant_id=? and status='COMMITTED'",
        quantity,
        occurredAt,
        allocationId,
        tenantId);

    UUID entryId =
        appendEntry(
            tenantId,
            reservationId,
            allocationId,
            "CONSUMED",
            "COMMITTED",
            "CONSUMED",
            quantity,
            occurredAt,
            input == null ? null : input.reason());

    settleReservationIfFullyConsumed(reservationId, occurredAt);

    var after = parentRollup(reservationId);
    return new ReservationAllocationTransitioned(
        allocationId,
        reservationId,
        from.name(),
        ReservationAllocationStatus.CONSUMED.name(),
        ReservationAllocationStatus.CONSUMED.name(),
        ReservationAllocationStatus.CONSUMED.label(),
        after.status(),
        ReservationModels.ReservationStatus.valueOf(after.status()).label(),
        after.consumed(),
        after.remaining(),
        entryId);
  }

  // ================================================================ 内部：锁与判定

  /**
   * 锁定预占并确认它没进终态。
   *
   * <p>{@code for update}：并发两次「承诺同一个分配」时，两个事务都读到 HELD 会双双通过
   * Java 层的 if 判断，然后由 DB 的 {@code where status='HELD'} 让后一个更新 0 行 ——
   * 不看影响行数的话，调用方会拿到一个「成功但什么都没发生」的假回执。
   */
  private org.jooq.Record lockOpenReservation(UUID reservationId) {
    var one =
        sql.fetchOne(
            "select id,status,requested_quantity,sku_id,allocated_quantity,consumed_quantity,remaining"
                + " from inventory_reservation where tenant_id = " + TENANT + " and id=? for update",
            reservationId);
    if (one == null) throw notFound(reservationId);
    var status = ReservationModels.ReservationStatus.valueOf(one.get("status", String.class));
    if (status == ReservationModels.ReservationStatus.CONSUMED
        || status == ReservationModels.ReservationStatus.RELEASED
        || status == ReservationModels.ReservationStatus.EXPIRED) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "预占 " + one.get("id", UUID.class) + " 已终结于 " + status.label() + "，不能再分配");
    }
    return one;
  }

  /** 锁定分配行，并确认它属于这条预占。跨预占操作在这里就被拒，不会漏到 SQL 条件里。 */
  private org.jooq.Record lockAllocation(UUID reservationId, UUID allocationId) {
    var one =
        sql.fetchOne(
            "select id,status,allocated_quantity,consumed_quantity,remaining"
                + " from reservation_allocation where tenant_id = " + TENANT
                + " and reservation_id=? and id=? for update",
            reservationId,
            allocationId);
    if (one == null) throw notFoundAllocation(allocationId);
    return one;
  }

  /**
   * 全部分配承诺时把预占推进到 COMMITTED。
   *
   * <p>这一步不做的话，预占会一直停在 HELD，而蓝图 7.2.1.3 的超时清理会把已承诺发货的货
   * 误释放 —— 那是蓝图自己点名的超卖场景。所以「分配状态变，预占状态跟着变」不是洁癖，是防线。
   *
   * <p>判定用「非终态分配全部为 COMMITTED 且至少一条」：部分承诺时预占仍留 HELD，
   * 因为它确实还有没承诺的部分，继续参与超时是对的。
   */
  private void promoteReservationToCommitted(UUID reservationId, OffsetDateTime at) {
    var open =
        sql.fetchOne(
            """
            select count(*) as open_count,
                   count(*) filter (where status='COMMITTED') as committed_count
              from reservation_allocation
             where tenant_id = %s and reservation_id = ? and status not in ('RELEASED','EXPIRED')
            """.formatted(TENANT),
            reservationId);
    long openCount = open.get("open_count", Long.class);
    if (openCount == 0 || open.get("committed_count", Long.class) != openCount) return;

    sql.execute(
        "update inventory_reservation set status='COMMITTED', committed_at=?::timestamptz"
            + " where tenant_id=" + TENANT + " and id=? and status='HELD'",
        at,
        reservationId);
  }

  /**
   * 分配全部消耗且覆盖需求量时把预占推进到 CONSUMED，并把预占的已消费量对齐到分配之和。
   *
   * <p>{@code consumed_quantity} 按分配之和**重算**而不是累加：累加在两次并发消耗、
   * 或一次请求重试时会翻倍，而重算是幂等的。这个列由本模块维护（allocated 由 V107 的触发器维护），
   * {@code released_quantity} 归 7.2.1.3，本模块**不碰** —— 碰了就会在对方写完释放量后
   * 被下一次分配变更覆盖回去。
   */
  private void settleReservationIfFullyConsumed(UUID reservationId, OffsetDateTime at) {
    var r =
        sql.fetchOne(
            """
            select count(*) as total_count,
                   count(*) filter (where status<>'CONSUMED') as not_consumed,
                   coalesce(sum(consumed_quantity),0) as consumed_total,
                   (select requested_quantity from inventory_reservation
                     where tenant_id = %s and id = ?) as requested
              from reservation_allocation
             where tenant_id = %s and reservation_id = ?
            """.formatted(TENANT, TENANT),
            reservationId,
            reservationId);
    BigDecimal consumed = r.get("consumed_total", BigDecimal.class);
    BigDecimal requested = r.get("requested", BigDecimal.class);
    boolean fully =
        r.get("total_count", Long.class) > 0
            && r.get("not_consumed", Long.class) == 0
            && consumed.compareTo(requested) >= 0;

    if (fully) {
      sql.execute(
          "update inventory_reservation set consumed_quantity=?::numeric,"
              + " status='CONSUMED', settled_at=?::timestamptz,"
              + " committed_at=coalesce(committed_at,?::timestamptz)"
              + " where tenant_id=" + TENANT + " and id=? and status<>'CONSUMED'",
          consumed,
          at,
          at,
          reservationId);
    } else {
      // 部分消耗：只对齐已消费量，不动状态。
      sql.execute(
          "update inventory_reservation set consumed_quantity=?::numeric"
              + " where tenant_id=" + TENANT + " and id=?",
          consumed,
          reservationId);
    }
  }

  /** 追加一条迁移分录。append-only，写完不可改。 */
  private UUID appendEntry(
      UUID tenantId,
      UUID reservationId,
      UUID allocationId,
      String transition,
      String fromStatus,
      String toStatus,
      BigDecimal quantity,
      OffsetDateTime occurredAt,
      String reason) {
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into reservation_entry(id,tenant_id,reservation_id,allocation_id,transition,"
            + "from_status,to_status,quantity,occurred_at,reason)"
            + " values(?,?,?,?,?,?,?,?,?::timestamptz,?)",
        id,
        tenantId,
        reservationId,
        allocationId,
        transition,
        fromStatus,
        toStatus,
        quantity,
        occurredAt,
        reason);
    return id;
  }

  /** 父预占当前的三个量。读的是库里的真值，不是本次操作前的内存副本。 */
  private ParentRollup parentRollup(UUID reservationId) {
    var one =
        sql.fetchOne(
            "select requested_quantity, allocated_quantity, consumed_quantity, remaining, status"
                + " from inventory_reservation where tenant_id = " + TENANT + " and id = ?",
            reservationId);
    if (one == null) throw notFound(reservationId);
    return new ParentRollup(
        one.get("requested_quantity", BigDecimal.class),
        one.get("allocated_quantity", BigDecimal.class),
        one.get("consumed_quantity", BigDecimal.class),
        one.get("remaining", BigDecimal.class),
        one.get("status", String.class));
  }

  private record ParentRollup(
      BigDecimal requested, BigDecimal allocated, BigDecimal consumed, BigDecimal remaining,
      String status) {}

  // ================================================================ 内部：引用登记校验

  private void requireWarehouse(UUID warehouseId) {
    if (sql.fetchOne("select 1 from warehouse where tenant_id = " + TENANT + " and id = ?", warehouseId)
        == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "仓库 " + warehouseId + " 不存在或无权使用");
    }
  }

  /**
   * 货位必须属于所声明的仓。
   *
   * <p>先在 Java 里判：V107 的复合外键当然也会拒「A 仓 + B 仓的货位」，但那是 23503 / 23514，
   * 经 {@code ApiErrorHandler} 翻成一句「库存数据与既有记录冲突」，用户完全看不出是货位挂错了仓。
   */
  private void requireLocationInWarehouse(UUID warehouseId, UUID locationId) {
    if (locationId == null) return;
    if (sql.fetchOne(
            "select 1 from warehouse_location where tenant_id = " + TENANT
                + " and warehouse_id=? and id=?",
            warehouseId,
            locationId)
        == null) {
      throw new IllegalArgumentException(
          "货位 " + locationId + " 不属于仓库 " + warehouseId + "：货位与仓库必须成对登记");
    }
  }

  /**
   * 批次必须属于预占的那个 SKU。
   *
   * <p>V107 的更新守卫也逐行比对，这里先判是为了给出能指到 SKU 的错误文案
   * （「批次属于 SKU-A，这笔预占要的是 SKU-B」），而不是一句「批次 SKU 与预占 SKU 不是同一个商品」。
   */
  private void requireLotOfSku(UUID lotId, UUID skuId) {
    if (lotId == null) return;
    var one = sql.fetchOne("select sku_id from inventory_lot where tenant_id = " + TENANT + " and id = ?", lotId);
    if (one == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "批次 " + lotId + " 不存在或无权使用");
    }
    if (!one.get("sku_id", UUID.class).equals(skuId)) {
      throw new IllegalArgumentException("批次 " + lotId + " 的 SKU 与这笔预占的 SKU 不是同一个商品");
    }
  }

  // ================================================================ 行映射与词表

  private ReservationAllocationRow row(org.jooq.Record r) {
    var status = ReservationAllocationStatus.valueOf(r.get("status", String.class));
    return new ReservationAllocationRow(
        r.get("id", UUID.class),
        r.get("reservation_id", UUID.class),
        status.name(),
        status.label(),
        status.terminal(),
        status.occupying(),
        r.get("warehouse_id", UUID.class),
        r.get("warehouse_code", String.class),
        r.get("warehouse_name", String.class),
        r.get("location_id", UUID.class),
        r.get("location_code", String.class),
        r.get("location_name", String.class),
        r.get("lot_id", UUID.class),
        r.get("lot_code", String.class),
        r.get("lot_expires_on", String.class),
        r.get("allocated_quantity", BigDecimal.class),
        r.get("consumed_quantity", BigDecimal.class),
        r.get("released_quantity", BigDecimal.class),
        r.get("remaining", BigDecimal.class),
        r.get("allocated_at", OffsetDateTime.class),
        r.get("committed_at", OffsetDateTime.class),
        r.get("settled_at", OffsetDateTime.class),
        r.get("note", String.class),
        r.get("transition_count", Long.class));
  }

  private static List<String> statusNames() {
    List<String> names = new ArrayList<>();
    for (ReservationAllocationStatus s : ReservationAllocationStatus.values()) names.add(s.name());
    return names;
  }

  /** 时间线上的迁移类型。词表外的类型必须被拒，而不是静默返回空集。 */
  private static ReservationAllocationTransition transition(String raw) {
    try {
      return ReservationAllocationTransition.valueOf(raw.toUpperCase(Locale.ROOT).trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("不支持的迁移类型：" + raw);
    }
  }

  private static String transitionLabel(String code) {
    return switch (code) {
      case "ALLOCATED" -> ReservationAllocationTransition.ALLOCATE.label();
      case "COMMITTED" -> ReservationAllocationTransition.COMMIT.label();
      case "CONSUMED" -> ReservationAllocationTransition.CONSUME.label();
      case "RELEASED" -> "已释放";
      case "EXPIRED" -> "已过期";
      default -> code;
    };
  }

  /**
   * 租户越权时统一 404 而不是 403。
   *
   * <p>给 403 等于确认了「这条预占存在、只是不归你管」，那是信息泄露：
   * 猜 id 就能试出别人的预占是否存在。404 同时覆盖「不存在」与「不归你」两种情况。
   */
  private static ResponseStatusException notFound(UUID reservationId) {
    return new ResponseStatusException(
        HttpStatus.NOT_FOUND, "预占 " + reservationId + " 不存在或无权查看");
  }

  private static ResponseStatusException notFoundAllocation(UUID allocationId) {
    return new ResponseStatusException(
        HttpStatus.NOT_FOUND, "分配 " + allocationId + " 不存在或无权操作");
  }

  private OffsetDateTime now() {
    // 提前 1 毫秒截断：PG 的 timestamptz 是微秒精度，Java 是纳秒，
    // 不截断的话写回读出的值与传入值不等，测试「断言写回的值」会差在末位。
    return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
  }
}
