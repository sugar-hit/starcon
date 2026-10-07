package com.starcon.warehouse;

import static com.starcon.warehouse.InventoryLedgerModels.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 7.1.2.1 库存流水台账：append-only 台账的**只读查询面**。
 *
 * <p>本服务**不写任何表**。蓝图要求「流水只追加不修改，错误用反向分录纠正，原记录永久保留」，
 * 这一点在 V1 已经由 DB 层坐实：两张事实表都有 append-only 触发器，
 * 且 warehouse_app 被 REVOKE 了 UPDATE/DELETE。所以本模块连"冲正"入口也不提供 ——
 * 写一条相反符号的分录属于库存业务写入方（7.4.1.1 调整 / 7.5.1.1 调拨 / 收货发货流程）的职责，
 * 台账只负责把发生过的一切如实列出来、筛得动、能下钻、能汇总。
 *
 * <p>与 7.1.1.x 的分层关系：7.1.1.x 读的是 {@code inventory_balance} / {@code stock_bucket} 的
 * **当前值**（随时可被重算的投影），本模块读的是 {@code inventory_transaction} /
 * {@code inventory_entry} 的**历史**（只追加的事实）。7.1.1.2 行上的「查看流水」入口指向本模块，
 * 下游 7.1.2.2 核对与 12 财务成本结转的来源追溯也取自这里。
 *
 * <p>三条实现纪律：
 *
 * <ul>
 *   <li><b>粒度写在代码里而不是只写在文档里</b>：行 = 一笔业务（数量是该笔全部分录之和），
 *       分录在下钻端点。SKU/仓库筛选是"这笔业务碰没碰过它"的存在性过滤，
 *       筛选维度上的量另给 {@code entryOnHandDelta}，两个口径的差是能被解释的。
 *   <li><b>汇总来自全量而不是当前页</b>：合计与分组统计各走一次独立聚合，翻页时数字不变，
 *       否则"翻页时期间净变动变小"会被当成丢数据。
 *   <li><b>数量一律 numeric(19,4) + BigDecimal</b>，沿用既有表口径；时间为 timestamptz，
 *       绑定用 {@code ?::timestamptz} 并以 OffsetDateTime 传值（用 Timestamp 会静默偏 8 小时）。
 * </ul>
 */
@Service
public class InventoryLedgerService {

  /** 单页最大行数：流水是所有业务的历史，必须比余额列表更克制。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 排序键白名单：排序键来自 query string，只允许这些。 */
  private static final List<String> SORTS =
      List.of(
          "OCCURRED_AT",
          "OCCURRED_AT_ASC",
          "KIND",
          "REF_TYPE",
          "ON_HAND_DELTA",
          "ENTRY_COUNT");

  /**
   * 行级 FROM：一笔业务 + 它的全部带符号分录 + 分录所属的余额行。
   *
   * <p>两个 join 都是 {@code left join}：业务单与分录、分录与余额行之间没有任何"保证非空"的
   * 约束（V1 只约束了"分录存在时 delta 至少一个非零"），一条分录都没有的业务单是写入方中途
   * 失败的痕迹，把它藏起来就等于让账平得不明不白，所以留着并在汇总里单独计数。
   */
  private static final String ROW_FROM =
      clause(
          """
          from inventory_transaction t
          left join inventory_entry e on e.tenant_id=t.tenant_id and e.transaction_id=t.id
          left join inventory_balance bal on bal.tenant_id=e.tenant_id and bal.id=e.balance_id
          """);

  /**
   * 分录级 FROM：按分录口径汇总时用。
   *
   * <p>这里 balance / sku_ref / warehouse 用**内连接**：分录的外键本来就锁死了 balance 行
   * （V1 的复合外键），balance 行又锁死了 SKU 与仓库，连不上说明数据已经坏了而不是"可选维度"，
   * 让它炸掉比静默丢一行强。dimJoins 在这里接得上，是因为 SKU / 仓库的编码名本来就要供下钻与关键字筛选用。
   */
  private static final String ENTRY_FROM =
      clause(
          """
          from inventory_entry e
          join inventory_transaction t on t.tenant_id=e.tenant_id and t.id=e.transaction_id
          join inventory_balance bal on bal.tenant_id=e.tenant_id and bal.id=e.balance_id
          """
          + dimJoins(""));

  /** 下钻用 FROM：在分录级基础上补货权法人与质量状态两张维表。 */
  private static final String DRILL_FROM =
      ENTRY_FROM
          + clause(
              """
              join legal_entity_ref le on le.tenant_id=bal.tenant_id and le.id=bal.legal_entity_id
              join inventory_quality_status q on q.tenant_id=bal.tenant_id and q.id=bal.quality_status_id
              """);

  /** 文本块片段的前导空格兜底（与 InventoryBucketService 同一条理由：Java 文本块会丢掉首行换行）。 */
  private static String clause(String fragment) {
    return " " + fragment.stripLeading();
  }

  private final DSLContext sql;

  public InventoryLedgerService(DSLContext sql) {
    this.sql = sql;
  }

  // ------------------------------------------------------------ 台账列表

  /** 台账列表 + 期间净变动汇总。纯读：不写任何表。 */
  public InventoryLedgerPage page(InventoryLedgerQuery query) {
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size() == null ? 50 : query.size()));
    int pageNumber = Math.max(0, query.page() == null ? 0 : query.page());
    checkRange(query);

    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    conditions(query, where, binds);
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    // 总笔数只查业务单表本身：SKU/仓库是 exists 里的存在性过滤，不改变命中哪些业务单。
    // 刻意不套 ROW_FROM —— 那里 left join 了分录，直接 count(*) 会数成"分录条数"，
    // 一笔跨 3 个 SKU 的业务会被算成 3 笔，翻页页数随即算错。
    long total =
        sql.fetchOne("select count(*) from inventory_transaction t" + sqlWhere, binds.toArray())
            .get(0, Long.class);

    var rows =
        sql.fetch(
            "select t.id as transaction_id,t.kind,t.ref_type,t.ref_id,t.occurred_at,t.actor_id,"
                + " t.notes,t.created_at,"
                + " coalesce(sum(e.on_hand_delta),0) as on_hand_delta,"
                + " coalesce(sum(e.reserved_delta),0) as reserved_delta,"
                + " count(e.id) as entry_count,"
                + " count(distinct bal.sku_id) as sku_count,"
                + " count(distinct bal.warehouse_id) as warehouse_count"
                + ROW_FROM
                + sqlWhere
                + // group by 主键即可：inventory_transaction.id 是主键，
                // PostgreSQL 允许按主键分组后直接引用同表其它列（函数依赖），不必把七列都列一遍。
                " group by t.id"
                + order(query)
                + " limit ? offset ?",
            append(binds, size, (long) pageNumber * size).toArray());

    return new InventoryLedgerPage(
        summary(query),
        rows.stream().map(InventoryLedgerService::row).toList(),
        breakdown(query),
        refTypes(),
        scopeOf(query),
        scopeNote(query),
        total,
        pageNumber,
        size);
  }

  // ------------------------------------------------------------ 期间净变动汇总

  /**
   * 期间净变动汇总。**全量**，不受分页影响。
   *
   * <p>两次聚合、两个口径：
   *
   * <ul>
   *   <li>{@code byTransaction}：每笔业务全量分录之和再相加 —— 与行上的数字同源。
   *   <li>{@code byEntry}：命中业务的分录中满足 SKU/仓库筛选的部分 —— 7.1.2.2 拿去核对的数。
   * </ul>
   *
   * <p>没有 SKU/仓库筛选时两者必然相等，这个等式就是账实核对的基线，所以直接算成
   * {@code onHandConserved} / {@code reservedConserved} 摆在响应里。
   */
  private InventoryLedgerSummary summary(InventoryLedgerQuery query) {
    var tWhere = new ArrayList<String>();
    var tBinds = new ArrayList<Object>();
    conditions(query, tWhere, tBinds);
    String tSqlWhere = tWhere.isEmpty() ? "" : " where " + String.join(" and ", tWhere);

    Record byTransaction =
        sql.fetchOne(
            "with filtered as ("
                + " select t.id,t.occurred_at,t.actor_id,"
                + " coalesce(sum(e.on_hand_delta),0) as on_hand_delta,"
                + " coalesce(sum(e.reserved_delta),0) as reserved_delta,"
                + " count(e.id) as entry_count"
                + ROW_FROM
                + tSqlWhere
                + " group by t.id"
                + ") select count(*) as transaction_count,"
                + " coalesce(sum(entry_count),0) as entry_count,"
                + " count(*) filter (where entry_count=0) as empty_count,"
                + " coalesce(sum(on_hand_delta),0) as on_hand_delta,"
                + " coalesce(sum(reserved_delta),0) as reserved_delta,"
                + " count(distinct actor_id) as actor_count,"
                + " min(occurred_at) as first_at,max(occurred_at) as last_at"
                + " from filtered",
            tBinds.toArray());

    var eWhere = new ArrayList<>(tWhere);
    var eBinds = new ArrayList<>(tBinds);
    dimensionConditions(query, "", eWhere, eBinds);
    String eSqlWhere = eWhere.isEmpty() ? "" : " where " + String.join(" and ", eWhere);
    Record byEntry =
        sql.fetchOne(
            "select coalesce(sum(e.on_hand_delta),0) as on_hand,"
                + " coalesce(sum(e.reserved_delta),0) as reserved,"
                + " count(*) as entry_count,"
                + " count(distinct bal.sku_id) as sku_count,"
                + " count(distinct bal.warehouse_id) as warehouse_count"
                + ENTRY_FROM
                + eSqlWhere,
            eBinds.toArray());

    var txOnHand = decimal(byTransaction, "on_hand_delta");
    var txReserved = decimal(byTransaction, "reserved_delta");
    var eOnHand = decimal(byEntry, "on_hand");
    var eReserved = decimal(byEntry, "reserved");

    return new InventoryLedgerSummary(
        byTransaction.get("transaction_count", Long.class),
        byEntry.get("entry_count", Long.class),
        byTransaction.get("empty_count", Long.class),
        txOnHand,
        txReserved,
        eOnHand,
        eReserved,
        txOnHand.compareTo(eOnHand) == 0,
        txReserved.compareTo(eReserved) == 0,
        byEntry.get("sku_count", Long.class),
        byEntry.get("warehouse_count", Long.class),
        byTransaction.get("actor_count", Long.class),
        moment(byTransaction, "first_at"),
        moment(byTransaction, "last_at"));
  }

  /**
   * 取聚合出来的时间值。
   *
   * <p>{@code min/max(occurred_at)} 走纯文本语句时 jOOQ 拿不到列类型，返回的可能是
   * {@link OffsetDateTime}、{@link java.time.Instant} 或 {@link java.sql.Timestamp}。
   * 直接 {@code r.get(field, OffsetDateTime.class)} 会在拿到 Instant 时抛 ClassCastException ——
   * 这里统一收口，并按 JVM 默认时区换算（与 PG 驱动自身的行为一致）。
   */
  private static OffsetDateTime moment(Record r, String field) {
    Object value = r.get(field);
    if (value == null) {
      return null;
    }
    if (value instanceof OffsetDateTime offset) {
      return offset;
    }
    if (value instanceof java.time.Instant instant) {
      return instant.atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime();
    }
    if (value instanceof java.sql.Timestamp timestamp) {
      return timestamp.toInstant().atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime();
    }
    return OffsetDateTime.parse(value.toString());
  }

  /**
   * 按业务类型的分组统计。
   *
   * <p>只返回期间真实发生过的类型：没有发生的类型报 0 笔会让人误判"这个期间查过了、没有这类业务"，
   * 与 7.1.1.1 把「未设阈值」和「未超储」分开是同一条纪律。
   */
  private List<InventoryLedgerBreakdown> breakdown(InventoryLedgerQuery query) {
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    conditions(query, where, binds);
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    return sql.fetch(
            "select t.kind,count(*) as transaction_count,count(e.id) as entry_count,"
                + " coalesce(sum(e.on_hand_delta),0) as on_hand_delta,"
                + " coalesce(sum(e.reserved_delta),0) as reserved_delta"
                + ROW_FROM
                + sqlWhere
                + " group by t.kind order by t.kind",
            binds.toArray())
        .map(
            r ->
                new InventoryLedgerBreakdown(
                    LedgerKind.parse(r.get("kind", String.class)),
                    r.get("transaction_count", Long.class),
                    r.get("entry_count", Long.class),
                    decimal(r, "on_hand_delta"),
                    decimal(r, "reserved_delta")));
  }

  /**
   * 来源单据类型下拉。
   *
   * <p>租户级词表，**不受时间与其它筛选影响**：它是"这个租户开过哪些来源单据类型"的清单，
   * 跟着筛选抖动的下拉会让用户以为某类单据不存在。
   */
  public List<InventoryRefType> refTypes() {
    return sql.fetch(
            "select ref_type,count(*) as transaction_count from inventory_transaction"
                + " group by ref_type order by ref_type")
        .map(
            r ->
                new InventoryRefType(
                    r.get("ref_type", String.class), r.get("transaction_count", Long.class)));
  }

  // ------------------------------------------------------------ 分录下钻

  /**
   * 一笔业务的分录明细。
   *
   * <p>跨租户访问在 RLS 下表现为「查不到」，因此返回 404 而不是 403 —— 403 会泄露「这笔业务存在」。
   */
  public InventoryLedgerDrill drill(UUID transactionId) {
    Record header =
        sql.fetchOne(
            "select t.id as transaction_id,t.kind,t.ref_type,t.ref_id,t.occurred_at,t.actor_id,"
                + " t.notes,t.created_at,0 as on_hand_delta,0 as reserved_delta,"
                + " (select count(*) from inventory_entry x"
                + "   where x.tenant_id=t.tenant_id and x.transaction_id=t.id) as entry_count,"
                + " (select count(distinct xb.sku_id) from inventory_entry x"
                + "   join inventory_balance xb on xb.tenant_id=x.tenant_id and xb.id=x.balance_id"
                + "   where x.tenant_id=t.tenant_id and x.transaction_id=t.id) as sku_count,"
                + " (select count(distinct xb.warehouse_id) from inventory_entry x"
                + "   join inventory_balance xb on xb.tenant_id=x.tenant_id and xb.id=x.balance_id"
                + "   where x.tenant_id=t.tenant_id and x.transaction_id=t.id) as warehouse_count"
                + " from inventory_transaction t where t.id=?",
            transactionId);
    if (header == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "库存流水不存在或无权查看");
    }
    return new InventoryLedgerDrill(row(header), entries(transactionId));
  }

  private List<InventoryEntryLine> entries(UUID transactionId) {
    return sql.fetch(
            "select e.id as entry_id,e.balance_id,e.on_hand_delta,e.reserved_delta,e.created_at,"
                + " bal.sku_id,s.code as sku_code,s.name as sku_name,"
                + " bal.warehouse_id,w.code as warehouse_code,w.name as warehouse_name,"
                + " bal.legal_entity_id,le.code as legal_entity_code,"
                + " bal.quality_status_id,q.code as quality_status_code,q.name as quality_status_name,"
                + " bal.on_hand as balance_on_hand_now,bal.reserved as balance_reserved_now,"
                + " bal.available as balance_available_now"
                + DRILL_FROM
                + " where e.transaction_id=? order by e.created_at,e.id",
            transactionId)
        .map(
            r ->
                new InventoryEntryLine(
                    r.get("entry_id", UUID.class),
                    r.get("balance_id", UUID.class),
                    r.get("sku_id", UUID.class),
                    r.get("sku_code", String.class),
                    r.get("sku_name", String.class),
                    r.get("warehouse_id", UUID.class),
                    r.get("warehouse_code", String.class),
                    r.get("warehouse_name", String.class),
                    r.get("legal_entity_id", UUID.class),
                    r.get("legal_entity_code", String.class),
                    r.get("quality_status_id", UUID.class),
                    r.get("quality_status_code", String.class),
                    r.get("quality_status_name", String.class),
                    decimal(r, "on_hand_delta"),
                    decimal(r, "reserved_delta"),
                    decimal(r, "balance_on_hand_now"),
                    decimal(r, "balance_reserved_now"),
                    decimal(r, "balance_available_now"),
                    r.get("created_at", OffsetDateTime.class)));
  }

  // ------------------------------------------------------------ 条件与排序

  /**
   * 条件拼装。分两层，这是本模块最容易写错的地方：
   *
   * <ul>
   *   <li><b>业务单级</b>（时间范围、业务类型、来源单据类型、数据范围）直接落在 {@code t.} 上。
   *   <li><b>维度级</b>（SKU、仓库、关键字）必须经分录 → 余额行，所以只能用
   *       {@code exists(...)} 表达"这笔业务碰没碰过它"。
   * </ul>
   *
   * <p>维度条件刻意**不进** WHERE 的主查询：把它塞进主查询会让行上的"该笔净变动"被筛成半个数，
   * 一行就不再等于一笔真实发生过的业务了（见类注释的粒度说明）。
   */
  private void conditions(
      InventoryLedgerQuery query, List<String> where, List<Object> binds) {
    // 时间范围排在最前：`?::timestamptz` 后面一定还跟着别的绑定，避免 cast 落在绑定序列末位
    // 时 jOOQ 整体错位一格（V1/V2 与 identity 的写入都遵守同一条纪律）。
    if (query.from() != null) {
      where.add("t.occurred_at >= ?::timestamptz");
      binds.add(query.from());
    }
    if (query.to() != null) {
      where.add("t.occurred_at < ?::timestamptz");
      binds.add(query.to());
    }
    if (query.kinds() != null && !query.kinds().isEmpty()) {
      // 非法码在这里就 400（LedgerKind.parse），不静默忽略。
      var kinds = query.kinds().stream().map(LedgerKind::parse).map(Enum::name).distinct().toList();
      where.add("t.kind in (" + placeholders(kinds.size()) + ")");
      binds.addAll(kinds);
    }
    if (query.refTypes() != null && !query.refTypes().isEmpty()) {
      var refs =
          query.refTypes().stream()
              .map(String::trim)
              .filter(v -> !v.isEmpty())
              .distinct()
              .toList();
      if (!refs.isEmpty()) {
        where.add("t.ref_type in (" + placeholders(refs.size()) + ")");
        binds.addAll(refs);
      }
    }
    // 数据范围 SELF：只看本人操作过的业务。actorId 来自已认证身份，不是客户端参数。
    if (Boolean.TRUE.equals(query.selfOnly())) {
      if (query.actorId() == null) {
        throw new IllegalArgumentException("数据范围限定为本人时必须能解析出操作人");
      }
      where.add("t.actor_id = ?");
      binds.add(query.actorId());
    }
    dimensionConditions(query, "2", where, binds);
  }

  /**
   * 维度条件（SKU / 仓库 / 关键字）。
   *
   * @param prefix 列别名后缀。{@code ""} = 主查询（列前缀 {@code bal}/{@code s}/{@code w}），
   *     {@code "2"} = 相关子查询（{@code bal2}/{@code s2}/{@code w2}）。
   *     同一段条件在列表查询（exists 内）与分录口径汇总里都要用，别名参数化才不用写两遍。
   */
  private void dimensionConditions(
      InventoryLedgerQuery query, String prefix, List<String> where, List<Object> binds) {
    var conditions = new ArrayList<String>();
    if (query.skuId() != null) {
      conditions.add("bal" + prefix + ".sku_id = ?");
      binds.add(query.skuId());
    }
    if (query.warehouseId() != null) {
      conditions.add("bal" + prefix + ".warehouse_id = ?");
      binds.add(query.warehouseId());
    }
    if (query.keyword() != null && !query.keyword().isBlank()) {
      conditions.add(
          "(s" + prefix + ".code ilike ? or s" + prefix + ".name ilike ?"
              + " or w" + prefix + ".code ilike ? or w" + prefix + ".name ilike ?"
              + " or t.notes ilike ?)");
      var k = "%" + query.keyword().trim() + "%";
      for (int i = 0; i < 5; i++) binds.add(k);
    }
    if (conditions.isEmpty()) {
      return;
    }
    if (prefix.isEmpty()) {
      // 分录口径：维度条件直接 AND 进 WHERE（这张 FROM 里 bal/s/w 已经 join 好了）。
      where.add(String.join(" and ", conditions));
      return;
    }
    // 业务单级：维度条件包进相关子查询，表达"这笔业务碰没碰过它"。
    // 子查询里必须自己把 balance 接上（外层的 bal 是主查询的别名，子查询看不见），
    // SKU / 仓库两张参照表又依赖 balance 才能连上。
    where.add(
        "exists (select 1 from inventory_entry e" + prefix
            + " join inventory_balance bal" + prefix
            + " on bal" + prefix + ".tenant_id=e" + prefix + ".tenant_id"
            + " and bal" + prefix + ".id=e" + prefix + ".balance_id"
            + dimJoins(prefix)
            + " where e" + prefix + ".tenant_id=t.tenant_id"
            + " and e" + prefix + ".transaction_id=t.id"
            + " and " + String.join(" and ", conditions) + ")");
  }

  /** 维度条件用到的参照表 join：SKU 与仓库编码/名称都在这里（sku / 仓库仍是逻辑 ID + 引用登记）。 */
  private static String dimJoins(String prefix) {
    return " join sku_ref s" + prefix
        + " on s" + prefix + ".tenant_id=bal" + prefix + ".tenant_id"
        + " and s" + prefix + ".id=bal" + prefix + ".sku_id"
        + " join warehouse w" + prefix
        + " on w" + prefix + ".tenant_id=bal" + prefix + ".tenant_id"
        + " and w" + prefix + ".id=bal" + prefix + ".warehouse_id";
  }

  private static String placeholders(int count) {
    return String.join(",", java.util.Collections.nCopies(count, "?"));
  }

  /**
   * 排序。决胜键恒定追加 {@code t.id}：同一毫秒内多笔业务时翻页不会错位或重复。
   */
  private String order(InventoryLedgerQuery query) {
    String sort = query.sort() == null ? "OCCURRED_AT" : query.sort().toUpperCase(Locale.ROOT);
    if (!SORTS.contains(sort)) {
      throw new IllegalArgumentException("不支持的排序键：" + query.sort() + "，可选：" + String.join("、", SORTS));
    }
    return switch (sort) {
      case "OCCURRED_AT_ASC" -> " order by t.occurred_at,t.id";
      case "KIND" -> " order by t.kind,t.occurred_at desc,t.id desc";
      case "REF_TYPE" -> " order by t.ref_type,t.occurred_at desc,t.id desc";
      // 按聚合列排序：PG 允许 order by 聚合表达式，不必再包一层子查询。
      case "ON_HAND_DELTA" -> " order by coalesce(sum(e.on_hand_delta),0) desc,t.id desc";
      case "ENTRY_COUNT" -> " order by count(e.id) desc,t.id desc";
      default -> " order by t.occurred_at desc,t.id desc";
    };
  }

  // ------------------------------------------------------------ 参数校验

  /**
   * 时间范围校验。
   *
   * <p>from/to 是左闭右开，所以 from == to 表示空区间（合法、返回空）；from &gt; to 一定是填反了，
   * 直接 400 而不是返回空列表。
   */
  private void checkRange(InventoryLedgerQuery query) {
    if (query.from() != null && query.to() != null && query.from().isAfter(query.to())) {
      throw new IllegalArgumentException("开始时间不能晚于结束时间");
    }
  }

  /** 实际生效的数据范围文案。由控制器按角色授权填入 selfOnly，此处只如实回述。 */
  private static String scopeOf(InventoryLedgerQuery query) {
    return Boolean.TRUE.equals(query.selfOnly()) ? "SELF" : "ALL";
  }

  private static String scopeNote(InventoryLedgerQuery query) {
    return Boolean.TRUE.equals(query.selfOnly())
        ? "当前角色的数据范围是「仅本人」，台账只列出你本人操作过的库存业务。"
        : "当前角色的数据范围是本租户全部库存业务。";
  }

  // ------------------------------------------------------------ 行组装

  private static InventoryLedgerRow row(Record r) {
    return new InventoryLedgerRow(
        r.get("transaction_id", UUID.class),
        LedgerKind.parse(r.get("kind", String.class)),
        r.get("ref_type", String.class),
        r.get("ref_id", UUID.class),
        r.get("occurred_at", OffsetDateTime.class),
        r.get("actor_id", UUID.class),
        r.get("notes", String.class),
        decimal(r, "on_hand_delta"),
        decimal(r, "reserved_delta"),
        r.get("entry_count", Long.class),
        r.get("sku_count", Long.class),
        r.get("warehouse_count", Long.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private static BigDecimal decimal(Record r, String field) {
    var v = r.get(field, BigDecimal.class);
    return v == null ? BigDecimal.ZERO : v;
  }

  private List<Object> append(List<Object> binds, Object... more) {
    var all = new ArrayList<>(binds);
    all.addAll(Arrays.asList(more));
    return all;
  }
}