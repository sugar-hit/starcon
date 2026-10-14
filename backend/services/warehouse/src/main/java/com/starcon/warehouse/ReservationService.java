package com.starcon.warehouse;

import static com.starcon.warehouse.ReservationModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

/**
 * 7.2.1.1 预占需求列表。
 *
 * <p>蓝图：「展示所有预占需求（来自订单履约、加工领料、其他占用），含需求来源、数量、
 * 状态（held/committed/consumed/released/expired）、剩余量、超时时长。」输出「预占需求列表 + 状态汇总」。
 *
 * <p><b>服务归属偏离说明</b>：蓝图关系句写「预占需求由 trade 持久化协调」，本实现不采纳。 对集成头全库实查确认 inventory_reservation
 * 表在任何服务里都不存在，V1 文件头早已把该表 预留给 warehouse 并注明「reservation 属 7.2.1.x，本期不建空表占位」。详见 V102 文件头第零节。
 *
 * <p>五条实现纪律：
 *
 * <ol>
 *   <li><b>状态汇总用标量子查询聚合，不 join 展开</b>。五个状态各一个 {@code count(*) filter (where status=…)}，合计口径与列表逐行同源；
 *       若把分配明细 join 进来再 group by，五态计数会被分配行数放大 —— 这正是 7.2.1.2 交付后最容易踩的坑，所以本期就把口径钉死。
 *   <li><b>合计不受分页与筛选影响</b>。它回答「我名下总共有多少预占」，拿筛选后的数当合计 会让「切了个筛选条件怎么总量变了」看起来像数据丢了。
 *   <li><b>超时长从真实写入时刻算</b>。用库里的 held_at / expires_at 算，不用 {@code now() -
 *       created_at}：两者在本表里可能不同（跨服务调用方显式传 held_at）， 而 created_at 是「本服务什么时候收到的」而不是「业务什么时候提的需求」。
 *   <li><b>超时为 null 而不是 0</b>。终态行与未过期行的 overtimeSeconds 恒 null —— 给 0 会把「没超时」与「超时 0
 *       秒」混成同一个数，前端无法区分。
 *   <li><b>COMMITTED 不参与自动过期</b>（蓝图 7.2.1.3 红线：「承诺分配后不再自动 TTL 释放，
 *       否则已承诺发货的货会被误释放导致超卖」）。所以超时筛选与超时计数只认 HELD。
 * </ol>
 *
 * <p>本模块<b>登记 + 列表只读</b>：分配明细由 7.2.1.2 承担（本列表的 allocated_quantity 由那张表的分配行回写），释放与过期仍属 7.2.1.3
 * 未交付，所以**终态行在本库仍然不可达** —— 这件事由 {@code capabilities()} 如实报出，不在列表上装成已经跑通。
 *
 * <p>租户来自已认证成员关系（控制器传入），不接受任何客户端租户参数；RLS 在数据库层强制隔离。
 */
@Service
public class ReservationService {

  /** 单页最大行数。与 WarehouseFacilityService / InventoryAgingService 同口径。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 排序键白名单：排序键来自 query string，只允许这六个。 */
  static final List<String> SORTS =
      List.of("REQUESTED_AT", "EXPIRES_AT", "STATUS", "QUANTITY", "REMAINING", "RESERVATION_NO");

  /** 超时筛选只看 HELD：承诺分配后不再自动过期（蓝图 7.2.1.3 红线）。 */
  /**
   * 超时谓词，带表别名。
   *
   * <p>必须带别名：{@code legal_entity_ref} 也有一个 {@code status} 列， 行查询 join 了它之后裸写 {@code status=}
   * 会得到「column reference is ambiguous」。 而歧义错误只在带 join 的那条查询上出现、汇总那条不出现 ——
   * 只按汇总调试会以为谓词没问题，直到列表接口上线才炸。
   *
   * <p>{@code alias} 传 null 表示不限定表（汇总查询里没有 join）。
   */
  private static String overdue(String alias) {
    String p = alias == null ? "" : alias + ".";
    return p + "status='HELD' and " + p + "expires_at is not null and " + p + "expires_at < now()";
  }

  /** 无别名的超时谓词，只用于没有 join 的汇总查询。 */
  private static final String OVERDUE_PREDICATE = overdue(null);

  private static final String TENANT = "current_setting('app.current_tenant', true)::uuid";

  private static final String NOTE =
      "预占需求表按 V1 文件头预留的 warehouse 归属落在本服务（蓝图关系句写 trade，"
          + "但该表在任何服务里都不存在，且 trade 侧无预占表可查）。合计不受分页与筛选影响；"
          + "剩余量 = 需求量 − 已消费量，不含已分配未出库的部分。";

  private static final String OWNER_NOTE =
      "预占需求、分配明细、释放与过期三张表同属 warehouse（V102 文件头第零节），"
          + "不跨服务读写 trade 的表；上游单据用 source_system + reference_id 定位键登记。";

  private final DSLContext sql;
  private final Clock clock;

  public ReservationService(DSLContext sql) {
    this.sql = sql;
    // 超时长按库里的时间戳算，但「现在」用系统时钟取，与 WarehouseFacilityService 同理：
    // 固定时钟会让测试变成只测一个假想的时刻。
    this.clock = Clock.systemDefaultZone();
  }

  // ================================================================ 列表

  /** 预占需求列表。筛选（状态 / 来源类型 / 来源系统 / 时间 / 超时 / 法人 / SKU）+ 关键字 + 排序 + 真分页。 */
  public ReservationPage list(ReservationQuery q) {
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, q.size() == null ? 20 : q.size()));
    int page = Math.max(0, q.page() == null ? 0 : q.page());

    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    filters(q, where, binds);
    String sqlWhere = " where " + String.join(" and ", where);

    long total =
        sql.fetchOne("select count(*)" + FROM_CLAUSE + sqlWhere, binds.toArray())
            .get(0, Long.class);

    List<ReservationRow> rows =
        sql.fetch(
                selectRows() + sqlWhere + orderBy(q.sort()) + " limit ? offset ?",
                bindsToArray(binds, size, page * size))
            .map(r -> row(r));

    // detailUnavailable 从 true 翻成 false：7.2.1.2 分配明细已交付，明细入口现在真的点得进去。
    // 这个翻旗动作由 7.2.1.2 那条车道按本方法原注释的约定来做。
    return new ReservationPage(summary(), rows, total, page, size, false, NOTE);
  }

  /**
   * 行查询。
   *
   * <p>sku_ref 与 legal_entity_ref 各用**一次左连接**取 code/name：两张表的主键都是 {@code
   * UNIQUE(tenant_id,id)}，所以连接不会放大行数（与「按 warehouse_id 聚合余额」 那种一行对多行的表不同，那类必须用标量子查询）。连接条件带上
   * tenant_id 两侧相等， 不靠 RLS 单独兜住跨租户误连。
   */
  private static String selectRows() {
    return """
        select r.id, r.reservation_no, r.source_system, r.source_type,
               r.source_ref_id, r.source_ref_code,
               r.legal_entity_id, r.sku_id,
               r.requested_quantity, r.allocated_quantity, r.consumed_quantity, r.remaining,
               r.status, r.requested_at, r.held_at, r.committed_at, r.settled_at,
               r.expires_at, r.note,
               le.code as le_code, le.name as le_name,
               sk.code as sku_code, sk.name as sku_name,
               -- 超时长：从库里的真实 expires_at 算到「现在」。只在 HELD 且已过期时非 null。
               case when %s then
                 cast(extract(epoch from (now() - r.expires_at)) as bigint)
               else null end as overdue_seconds
        """
            .formatted(overdue("r"))
        + FROM_CLAUSE;
  }

  /**
   * FROM + 两个左连接，**行查询与计数查询共用同一段**。
   *
   * <p>共用不是洁癖：关键字筛选要匹配 {@code sk.code / sk.name}（用户在 SKU 列上看到的那两列）， 所以 where 子句会引用 {@code
   * sk}。若计数查询只写 {@code from inventory_reservation r}， 带关键字的请求会在 count 那一步报「bad SQL grammar」——
   * 而且只在用户真的敲了关键字时才炸，不敲关键字的用例全绿，很难在联调前发现。
   *
   * <p>sku_ref 与 legal_entity_ref 各用**一次左连接**取 code/name：两张表的主键都是 {@code
   * UNIQUE(tenant_id,id)}，所以连接不会放大行数（与「按 warehouse_id 聚合余额」 那种一行对多行的表不同，那类必须用标量子查询）。连接条件带上
   * tenant_id 两侧相等， 不靠 RLS 单独兜住跨租户误连。
   */
  private static final String FROM_CLAUSE =
      """
          from inventory_reservation r
          left join legal_entity_ref le on le.tenant_id=r.tenant_id and le.id=r.legal_entity_id
          left join sku_ref sk on sk.tenant_id=r.tenant_id and sk.id=r.sku_id
      """;

  private static void filters(ReservationQuery q, List<String> where, List<Object> binds) {
    where.add("r.tenant_id = " + TENANT);

    if (q.statuses() != null && !q.statuses().isEmpty()) {
      List<String> names = new ArrayList<>();
      for (String raw : q.statuses()) {
        if (raw == null || raw.isBlank()) continue;
        String name = raw.toUpperCase(Locale.ROOT).trim();
        // 词表外的状态在 SQL 绑定**之前**就拒：把拼错的 status 绑进去会得到一个
        // 「合法但永远为空」的结果集，界面上看起来像「这个状态真的一条都没有」。
        if (!statusNames().contains(name)) throw new IllegalArgumentException("不支持的预占状态：" + raw);
        names.add(name);
      }
      if (!names.isEmpty()) {
        String placeholders =
            names.stream().map(n -> "?").collect(java.util.stream.Collectors.joining(","));
        where.add("r.status in (" + placeholders + ")");
        binds.addAll(names);
      }
    }
    if (q.sourceType() != null && !q.sourceType().isBlank()) {
      where.add("r.source_type = ?");
      binds.add(sourceType(q.sourceType()).name());
    }
    if (q.sourceSystem() != null && !q.sourceSystem().isBlank()) {
      where.add("r.source_system = ?");
      binds.add(sourceSystem(q.sourceSystem()).name());
    }
    // 时间范围左闭右开 [from, to)。右开让「按天筛」可以把次日 00:00 当上界而不用减一秒。
    if (q.requestedFrom() != null) {
      where.add("r.requested_at >= ?::timestamptz");
      binds.add(q.requestedFrom());
    }
    if (q.requestedTo() != null) {
      where.add("r.requested_at < ?::timestamptz");
      binds.add(q.requestedTo());
    }
    if (Boolean.TRUE.equals(q.overdueOnly())) {
      where.add(overdue("r"));
    }
    if (q.legalEntityId() != null) {
      where.add("r.legal_entity_id = ?");
      binds.add(q.legalEntityId());
    }
    if (q.skuId() != null) {
      where.add("r.sku_id = ?");
      binds.add(q.skuId());
    }
    if (q.keyword() != null && !q.keyword().isBlank()) {
      // 关键字覆盖「人能看到的四个地方」：本单号、上游单号、SKU 编码/名称、备注。
      // 少匹配一个，用户就得记住「预占单号在本单号列、订单号在来源列」才能找到一行。
      where.add(
          "(r.reservation_no ilike ? or r.source_ref_code ilike ?"
              + " or sk.code ilike ? or sk.name ilike ? or r.note ilike ?)");
      String like = "%" + q.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
  }

  private static Object[] bindsToArray(List<Object> binds, Object... tail) {
    Object[] all = new Object[binds.size() + tail.length];
    for (int i = 0; i < binds.size(); i++) all[i] = binds.get(i);
    for (int i = 0; i < tail.length; i++) all[binds.size() + i] = tail[i];
    return all;
  }

  private String orderBy(String sort) {
    String key = sort == null || sort.isBlank() ? "REQUESTED_AT" : sort.toUpperCase(Locale.ROOT);
    if (!SORTS.contains(key)) throw new IllegalArgumentException("不支持的排序键：" + sort);
    // 一律以 reservation_no 收尾：排序键相同时必须有确定次序，
    // 否则同一页两次请求可能给出不同顺序（requested_at 同毫秒时确实会）。
    return switch (key) {
      case "EXPIRES_AT" -> " order by r.expires_at asc nulls last, r.reservation_no";
      case "STATUS" -> " order by r.status, r.reservation_no";
      case "QUANTITY" -> " order by r.requested_quantity desc, r.reservation_no";
      case "REMAINING" -> " order by r.remaining desc, r.reservation_no";
      case "RESERVATION_NO" -> " order by r.reservation_no";
      default -> " order by r.requested_at desc, r.reservation_no";
    };
  }

  // ================================================================ 状态汇总

  /**
   * 全量状态汇总。**不受分页与筛选影响** —— 它回答「我名下总共有多少预占、欠多少货」。
   *
   * <p>五个状态各一个 {@code count(*) filter}，**没有 join 任何明细表**： 7.2.1.2 交付后 reservation_allocation
   * 会按预占展开成多行， 届时若改成 join + group by，五个计数会被分配行数放大而与列表逐行求和不一致。 现在就把口径钉成「只数预占行」，两条车道不必再改这个方法。
   */
  private ReservationSummary summary() {
    var r =
        sql.fetchOne(
            """
            select count(*) as total_count,
                   count(*) filter (where status='HELD')       as held_count,
                   count(*) filter (where status='COMMITTED') as committed_count,
                   count(*) filter (where status='CONSUMED')   as consumed_count,
                   count(*) filter (where status='RELEASED')   as released_count,
                   count(*) filter (where status='EXPIRED')    as expired_count,
                   coalesce(sum(requested_quantity),0) as requested_total,
                   coalesce(sum(consumed_quantity),0)  as consumed_total,
                   coalesce(sum(remaining),0)         as remaining_total,
                   count(*) filter (where %s)         as overdue_count,
                   coalesce(sum(remaining) filter (where %s),0) as overdue_total
              from inventory_reservation
             where tenant_id = %s
            """
                .formatted(OVERDUE_PREDICATE, OVERDUE_PREDICATE, TENANT));

    Map<String, Long> counts = new LinkedHashMap<>();
    counts.put("HELD", r.get("held_count", Long.class));
    counts.put("COMMITTED", r.get("committed_count", Long.class));
    counts.put("CONSUMED", r.get("consumed_count", Long.class));
    counts.put("RELEASED", r.get("released_count", Long.class));
    counts.put("EXPIRED", r.get("expired_count", Long.class));

    return new ReservationSummary(
        r.get("total_count", Long.class),
        counts,
        r.get("requested_total", BigDecimal.class),
        r.get("consumed_total", BigDecimal.class),
        r.get("remaining_total", BigDecimal.class),
        r.get("overdue_count", Long.class),
        r.get("overdue_total", BigDecimal.class));
  }

  // ================================================================ 行映射

  private ReservationRow row(org.jooq.Record r) {
    String statusName = r.get("status", String.class);
    ReservationStatus status = ReservationStatus.valueOf(statusName);
    SourceSystem system = SourceSystem.valueOf(r.get("source_system", String.class));
    SourceType type = SourceType.valueOf(r.get("source_type", String.class));
    Long overdue = r.get("overdue_seconds", Long.class);

    return new ReservationRow(
        r.get("id", UUID.class),
        r.get("reservation_no", String.class),
        system.name(),
        system.label(),
        type.name(),
        type.label(),
        r.get("source_ref_id", UUID.class),
        r.get("source_ref_code", String.class),
        r.get("legal_entity_id", UUID.class),
        r.get("le_code", String.class),
        r.get("le_name", String.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("requested_quantity", BigDecimal.class),
        r.get("allocated_quantity", BigDecimal.class),
        r.get("consumed_quantity", BigDecimal.class),
        r.get("remaining", BigDecimal.class),
        status.name(),
        status.label(),
        terminal(status),
        overdue,
        r.get("requested_at", OffsetDateTime.class),
        r.get("held_at", OffsetDateTime.class),
        r.get("committed_at", OffsetDateTime.class),
        r.get("settled_at", OffsetDateTime.class),
        r.get("expires_at", OffsetDateTime.class),
        r.get("note", String.class));
  }

  // ================================================================ 登记

  /**
   * 登记一条预占需求。
   *
   * <p><b>只允许建成 HELD</b>：V102 的 guard_reservation_insert 在 DB 层拒绝任何 「插一行终态」的写法。这里再查一次 source_system
   * / source_type 的**组合合法性** —— 两个 CHECK 只保证各自在词表内，不保证「手工登记的销售订单预占」这种自相矛盾的行不出现。
   *
   * <p>单号由本服务发（RSV-日期-序号），<b>不采信调用方传入的单号</b>： 采信的话，两个不同上游各发一次 RSV-000001 就会撞
   * UNIQUE(tenant_id,reservation_no)， 而撞车时调用方拿到的是一个指不到具体行的 409。上游的定位键是 {@code source_system +
   * reference_id}，那才是跨服务去重该用的键。
   */
  public ReservationCreated create(UUID tenantId, ReservationCreateInput input) {
    SourceSystem system = sourceSystem(input.sourceSystem());
    SourceType type = sourceType(input.sourceType());
    if (!validCombination(system, type)) {
      throw new IllegalArgumentException(
          "来源与类型不匹配：" + system.label() + " 不能登记 " + type.label() + " 类型的预占");
    }
    if (input.requestedQuantity() == null
        || input.requestedQuantity().compareTo(BigDecimal.ZERO) <= 0) {
      throw new IllegalArgumentException("需求数量必须为正");
    }

    // 提前 1 毫秒截断：PG 的 timestamptz 是微秒精度，Java 是纳秒，
    // 不截断的话写回读出的值与传入值不等，测试「断言写回的值」会差在末位。
    OffsetDateTime heldAt =
        input.heldAt() == null
            ? OffsetDateTime.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
            : input.heldAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    OffsetDateTime expiresAt =
        input.expiresAt() == null
            ? null
            : input.expiresAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);

    UUID id = UUID.randomUUID();
    long seq = sql.fetchOne("select nextval('inventory_reservation_no_seq')").get(0, Long.class);
    String no =
        "RSV-"
            + OffsetDateTime.now(clock).toLocalDate().toString().replace("-", "")
            + "-"
            + String.format("%06d", seq);

    sql.execute(
        "insert into inventory_reservation(id,tenant_id,reservation_no,source_system,source_type,"
            + "source_ref_id,source_ref_code,legal_entity_id,sku_id,requested_quantity,"
            + "held_at,requested_at,expires_at,note)"
            + " values(?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?::timestamptz,?)",
        id,
        tenantId,
        no,
        system.name(),
        type.name(),
        input.referenceId(),
        input.referenceCode(),
        input.legalEntityId(),
        input.skuId(),
        input.requestedQuantity().setScale(6, RoundingMode.HALF_UP),
        heldAt,
        heldAt,
        expiresAt,
        input.note());

    return new ReservationCreated(
        id, no, ReservationStatus.HELD.name(), ReservationStatus.HELD.label());
  }

  // ================================================================ 能力与下拉项

  /**
   * 预占链路各环节的接入情况。
   *
   * <p>分配明细（7.2.1.2）已交付；释放/过期（7.2.1.3）仍未交付，这里逐条报出归哪个模块、缺什么 —— 前端据此禁用入口，而不是显示一个永远为 0
   * 的「已分配量」让人以为分配已经跑通。
   */
  public List<ReservationCapability> capabilities() {
    // 注意：本端点**不提供**自动过期动作。蓝图 7.2.1.3 明确「承诺分配后不再自动 TTL 释放」，
    // 属高风险不可逆动作，必须由 7.2.1.3 按显式规则交付，不能夹在列表模块里顺带实现。
    return List.of(
        new ReservationCapability(
            "DEMAND_LIST", "预占需求列表", true, "7.2.1.1", "已接入 inventory_reservation"),
        new ReservationCapability(
            "DEMAND_CREATE", "登记预占需求", true, "7.2.1.1", "已接入：只允许建成 HELD，终态由状态迁移产生"),
        new ReservationCapability(
            "ALLOCATION_DETAIL",
            "分配明细",
            true,
            "7.2.1.2",
            "已接入 reservation_allocation：分配到具体 bucket（仓 / 货位 / 批次），"
                + "迁移流水 append-only；入口 /warehouse/inventory/reservations/{id}/allocations"),
        new ReservationCapability(
            "RELEASE", "释放与过期", false, "7.2.1.3", "7.2.1.3 释放与过期尚未交付，没有释放入口也没有批量过期清理，终态行需要由该模块写入"));
  }

  /** 筛选与录入下拉项。 */
  public ReservationOptions options() {
    List<StatusOption> statuses = new ArrayList<>();
    for (ReservationStatus s : ReservationStatus.values()) {
      statuses.add(new StatusOption(s.name(), s.label(), terminal(s)));
    }
    List<SourceOption> systems = new ArrayList<>();
    for (SourceSystem s : SourceSystem.values()) systems.add(new SourceOption(s.name(), s.label()));
    List<SourceOption> types = new ArrayList<>();
    for (SourceType t : SourceType.values()) types.add(new SourceOption(t.name(), t.label()));

    return new ReservationOptions(
        statuses,
        systems,
        types,
        List.of(
            new StatusOption("REQUESTED_AT", "按提出时间", false),
            new StatusOption("EXPIRES_AT", "按过期时间", false),
            new StatusOption("STATUS", "按状态", false),
            new StatusOption("QUANTITY", "按需求量", false),
            new StatusOption("REMAINING", "按剩余量", false),
            new StatusOption("RESERVATION_NO", "按预占单号", false)),
        "时间一律带时区偏移：库里是 timestamptz，界面按浏览器本地时区显示，" + "服务端不做二次时区转换（转换两次就会把偏移量算错 8 小时）",
        OWNER_NOTE);
  }

  // ================================================================ 词表与判定

  private static List<String> statusNames() {
    List<String> names = new ArrayList<>();
    for (ReservationStatus s : ReservationStatus.values()) names.add(s.name());
    return names;
  }

  private static boolean terminal(ReservationStatus s) {
    return s == ReservationStatus.CONSUMED
        || s == ReservationStatus.RELEASED
        || s == ReservationStatus.EXPIRED;
  }

  private static SourceSystem sourceSystem(String raw) {
    try {
      return SourceSystem.valueOf(raw.toUpperCase(Locale.ROOT).trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("不支持的来源系统：" + raw);
    }
  }

  private static SourceType sourceType(String raw) {
    try {
      return SourceType.valueOf(raw.toUpperCase(Locale.ROOT).trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("不支持的来源类型：" + raw);
    }
  }

  /**
   * 来源系统 × 来源类型的合法组合。
   *
   * <p>两个 CHECK 只保证各自在词表内，不保证组合自洽。逐条说清为什么这么定：
   *
   * <ul>
   *   <li>{@code SALES_ORDER}（销售订单预占）只能来自 {@code TRADE} —— 09 订单在 trade。
   *   <li>{@code PROCESSING_ORDER}（加工领料预占）只能来自 {@code PROCESSING} —— 6.4.1.1 加工单在 trade
   *       但归属加工链，运营手工登记一条「加工领料预占」应当显式声明它来自加工链。
   *   <li>{@code OTHER_HOLD}（其他占用）只能来自 {@code MANUAL} —— 没有上游单据。
   * </ul>
   *
   * <p>反过来 {@code PROCESSING} 系统也不许登记 SALES_ORDER：那会让 6.4.1.2 按 「加工来源」去找 BOM 关联时读到一张销售订单预占，去 trade
   * 查时找不到而卡住。
   */
  private static boolean validCombination(SourceSystem system, SourceType type) {
    return switch (type) {
      case SALES_ORDER -> system == SourceSystem.TRADE;
      case PROCESSING_ORDER -> system == SourceSystem.PROCESSING;
      case OTHER_HOLD -> system == SourceSystem.MANUAL;
    };
  }
}
