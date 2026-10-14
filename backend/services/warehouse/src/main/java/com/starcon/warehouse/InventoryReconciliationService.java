package com.starcon.warehouse;

import static com.starcon.warehouse.InventoryReconciliationModels.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 7.1.2.2 账实核对：把「按流水累计的余额」和「当前余额快照」对上，对不上就指出**哪一段**对不上。
 *
 * <p>两个来源都是**现算**的：累计值是 Σ {@code inventory_entry}（append-only 事实表）， 快照是 {@code
 * inventory_balance}（可重算的投影）。刻意不建「累计值表」—— 那会引入第二个真相来源， 核对时反而无法判断该信谁（V51 台账迁移已经按同一条理由拒绝过汇总表）。落库的只有
 * 「这次核对的结论」，它是审计凭据而不是账（V54 已 REVOKE 改删）。
 *
 * <p><b>口径（全模块唯一一套）</b>：期初 + 区间内 + 区间后 = 应当等于快照。 期初这一档不能省：快照只有「现在」这一个值，拿区间内的流水去比它会凭空差出期初量。
 * <b>差异判定覆盖全部历史</b>，不受区间影响 —— 否则换个区间重核一次结论就变了， 那不是核对，那是筛流水。
 *
 * <p><b>可疑定位是本模块真正的产出</b>。返回一个差额数字等于什么都没说，运营要的是 「先去看哪几笔」。四类可疑各自有可计算的信号，且都落到具体的余额行或业务单上：
 *
 * <ul>
 *   <li>漏记：快照非零，但核对区间里一条能解释它的流水都没有（{@code period_entry_count=0}）。
 *   <li>迟到补记：分录入库时刻比声称的发生时刻晚超过一天（{@code created_at - occurred_at}）。
 *   <li>重复记：同一余额行上，同一操作人、同一类型、同一数量、彼此相隔一分钟内的两笔不同业务。 V1 的 UNIQUE(tenant_id,ref_type,ref_id)
 *       挡住同一来源单据重投，挡不住「同一批货被两张不同的单据各记一次」。
 *   <li>并发丢失：同一余额行在**写入时刻**上撞在一起。丢失更新必须有并发才可能发生， 单写入者的漂移归漏记 —— 两者不混着报。
 * </ul>
 *
 * <p>后三类只在**存在差异的余额行**上扫描。不一致的账才需要解释；对本来平了的行做同样的扫描 只会产出噪音，把真正要看的信号淹掉。
 *
 * <p>三条实现纪律：
 *
 * <ul>
 *   <li><b>时间绑定</b>：{@code ?::timestamptz} 集中在最前面的 {@code win} CTE 里， 后面所有 {@code ? } 都是普通绑定。这样
 *       cast 永远不在绑定序列末位 —— 末位 cast 会让 jOOQ 整体错位一格（AGENTS.md 与 7.1.2.1 的同一条纪律）。两个边界都有值时 opening /
 *       period / after 三个桶**恰好**把全部历史分完，{@code expected == total} 恒成立。
 *   <li><b>写入逐行 execute</b>，不用 {@code DSLContext.batch(String,...)}：本仓库统一这么写， 原因与 {@code
 *       ImportJobService} 相同 —— 纯文本语句上带 {@code ?::cast} 的 batch 绑定不可靠。
 *   <li><b>数量一律 numeric(19,4) + BigDecimal</b>，沿用既有表口径；比较一律 {@code compareTo}， 不用 {@code
 *       equals}（后者把 0 与 0.0000 判成不等）。
 * </ul>
 */
@Service
public class InventoryReconciliationService {

  /** 单页最大行数：核对结果是审计凭据，比业务列表更克制。 */
  static final int MAX_PAGE_SIZE = 200;

  /**
   * 并发窗口（秒）：同一余额行在这个间隔内的两次写入视为「撞在一起」。
   *
   * <p>一分钟是经验值：批量收货本来就会在一分钟内连续写同一行，但那些是顺序写入、各自带分录、 不会让账对不上；真正要报的是「撞车**且**账对不上」这一组合，见类注释。
   */
  static final long CONCURRENCY_GAP_SECONDS = 60;

  /** 迟到补记阈值（秒）：入库时刻比声称的发生时刻晚过一天，就不是「当天补记」而是「跨期补记」。 */
  static final long BACKFILL_LATENESS_SECONDS = 86400L;

  /** 区间缺省天数：核最近 30 天。缺省只影响「区间内/区间后」怎么拆与可疑去哪个时间段找。 */
  static final int DEFAULT_WINDOW_DAYS = 30;

  /** 差异为 0 的账实在 numeric(19,4) 口径下的零，避免同一列里混着 scale 不一样的 0。 */
  private static final BigDecimal ZERO = new BigDecimal("0.0000");

  /**
   * 核对区间绑定。
   *
   * <p>两个 {@code ?::timestamptz} 刻意放在整条语句最前面，理由见类注释。
   */
  private static final String WIN =
      "with win as (select ?::timestamptz as from_at, ?::timestamptz as to_at),";

  /**
   * 核对范围 + 四组定位维度。
   *
   * <p>四个维表用**内连接**：balance 的外键本来就锁死了这四行，连不上说明数据已经坏了而不是 「可选维度」，让它炸掉比静默丢一行强（与 7.1.2.1 的 ENTRY_FROM
   * 同一条理由）。 维度编码在这一层 join 好，差异明细与可疑流水都直接从它取，不必各自再 join 一遍。
   */
  private static final String SCOPED_HEAD =
      clause(
          """
          scoped as (
          select bal.tenant_id, bal.id as balance_id, bal.sku_id, s.code as sku_code,
                 bal.warehouse_id, w.code as warehouse_code,
                 bal.legal_entity_id, le.code as legal_entity_code,
                 bal.quality_status_id, q.code as quality_status_code,
                 bal.on_hand as snapshot_on_hand, bal.reserved as snapshot_reserved,
                 bal.version
          from inventory_balance bal
          join sku_ref s on s.tenant_id = bal.tenant_id and s.id = bal.sku_id
          join warehouse w on w.tenant_id = bal.tenant_id and w.id = bal.warehouse_id
          join legal_entity_ref le on le.tenant_id = bal.tenant_id and le.id = bal.legal_entity_id
          join inventory_quality_status q on q.tenant_id = bal.tenant_id and q.id = bal.quality_status_id
          """);

  private static final String SCOPED_TAIL = ")";

  /**
   * 每条余额行的分录累计。
   *
   * <p>三个时间桶（区间前 / 区间内 / 区间后）各自用 {@code sum(...) filter (where ...)} 表达， 而不是三条独立查询后在 Java
   * 里加：过滤与聚合在同一次扫描里完成， 也不会出现「两次查询之间又来了一笔流水」导致加法对不上的经典竞态。
   */
  private static final String PER_BALANCE =
      clause(
          """
          per_balance as (
            select e.balance_id,
              coalesce(sum(e.on_hand_delta) filter (where t.occurred_at < win.from_at), 0::numeric(19,4)) as opening_on_hand,
              coalesce(sum(e.on_hand_delta) filter (where t.occurred_at >= win.from_at and t.occurred_at < win.to_at), 0::numeric(19,4)) as period_on_hand,
              coalesce(sum(e.on_hand_delta) filter (where t.occurred_at >= win.to_at), 0::numeric(19,4)) as after_window_on_hand,
              coalesce(sum(e.on_hand_delta), 0::numeric(19,4)) as total_on_hand,
              coalesce(sum(e.reserved_delta) filter (where t.occurred_at < win.from_at), 0::numeric(19,4)) as opening_reserved,
              coalesce(sum(e.reserved_delta) filter (where t.occurred_at >= win.from_at and t.occurred_at < win.to_at), 0::numeric(19,4)) as period_reserved,
              coalesce(sum(e.reserved_delta) filter (where t.occurred_at >= win.to_at), 0::numeric(19,4)) as after_window_reserved,
              coalesce(sum(e.reserved_delta), 0::numeric(19,4)) as total_reserved,
              count(e.id) filter (where t.occurred_at >= win.from_at and t.occurred_at < win.to_at) as period_entry_count,
              count(e.id) as total_entry_count,
              count(distinct e.transaction_id) filter (where t.occurred_at >= win.from_at and t.occurred_at < win.to_at) as period_transaction_count,
              count(distinct e.transaction_id) as transaction_count,
              min(t.occurred_at) as first_at, max(t.occurred_at) as last_at,
              (array_agg(t.id order by t.occurred_at desc, t.id desc))[1] as last_transaction_id
            from inventory_entry e
            join inventory_transaction t on t.tenant_id = e.tenant_id and t.id = e.transaction_id
            join scoped sc on sc.balance_id = e.balance_id
            -- cross join win：CTE 名必须出现在 FROM 里才能被引用。三个时间桶的边界条件写在
            -- 聚合的 filter 里，win 不进 FROM 就是 "missing FROM-clause entry for table win"。
            -- win 只有一行，cross join 不改基数。
            cross join win
            group by e.balance_id
          )
          """);

  /**
   * 对不上的余额行 —— 差异明细与可疑扫描的唯一来源。
   *
   * <p>判定覆盖全部历史（{@code total} vs 快照），不受核对区间影响，理由见类注释。
   */
  private static final String DISCREPANT =
      clause(
          """
          discrepant as (
            select sc.tenant_id, sc.balance_id, sc.sku_id, sc.sku_code,
                   sc.warehouse_id, sc.warehouse_code, sc.legal_entity_id, sc.legal_entity_code,
                   sc.quality_status_id, sc.quality_status_code, sc.version,
                   sc.snapshot_on_hand, sc.snapshot_reserved,
                   coalesce(pb.total_on_hand, 0::numeric(19,4)) as total_on_hand,
                   coalesce(pb.total_reserved, 0::numeric(19,4)) as total_reserved,
                   coalesce(pb.total_on_hand, 0::numeric(19,4)) - sc.snapshot_on_hand as on_hand_diff,
                   coalesce(pb.total_reserved, 0::numeric(19,4)) - sc.snapshot_reserved as reserved_diff,
                   coalesce(pb.period_on_hand, 0::numeric(19,4)) as period_on_hand,
                   coalesce(pb.period_reserved, 0::numeric(19,4)) as period_reserved,
                   coalesce(pb.period_entry_count, 0) as period_entry_count,
                   coalesce(pb.total_entry_count, 0) as entry_count,
                   coalesce(pb.transaction_count, 0) as transaction_count,
                   pb.first_at, pb.last_at, pb.last_transaction_id
            from scoped sc
            left join per_balance pb on pb.balance_id = sc.balance_id
            where coalesce(pb.total_on_hand, 0::numeric(19,4)) <> sc.snapshot_on_hand
               or coalesce(pb.total_reserved, 0::numeric(19,4)) <> sc.snapshot_reserved
          )
          """);

  /**
   * 落在差异行上、且落在核对区间内的分录。
   *
   * <p>两个 {@code lag} 窗口分别按「重复签名」（余额行 + 类型 + 数量 + 操作人）与 「同一余额行」分区取前一笔，一次扫描把迟到补记、重复记、并发丢失三个信号需要的
   * 全部信息读出来，分类留给 Java。三个信号可以同时命中同一笔（一次重复录入恰好又是迟到补记）， 那就都报 —— 多一条线索比少一条强。
   */
  private static final String HITS =
      clause(
          """
          hits as (
            select e.balance_id, e.on_hand_delta, e.reserved_delta,
                   t.id as transaction_id, t.kind, t.ref_type, t.ref_id,
                   t.occurred_at, t.created_at, t.actor_id,
                   lag(t.id) over sig as sig_prev_id,
                   lag(t.occurred_at) over sig as sig_prev_at,
                   lag(t.id) over hit as hit_prev_id,
                   lag(t.created_at) over hit as hit_prev_at
            from inventory_entry e
            join inventory_transaction t on t.tenant_id = e.tenant_id and t.id = e.transaction_id
            join discrepant d on d.balance_id = e.balance_id
            -- cross join win：同 per_balance，CTE 名必须出现在 FROM 里才能被引用（win 只有一行）
            cross join win
            where t.occurred_at >= win.from_at and t.occurred_at < win.to_at
            window sig as (partition by e.balance_id, t.kind, e.on_hand_delta, e.reserved_delta, t.actor_id
                           order by t.occurred_at, t.id),
                   hit as (partition by e.balance_id order by t.created_at, t.id)
          )
          """);

  /** 文本块片段的前导空格兜底（与 InventoryLedgerService 同一理由：Java 文本块会丢掉首行换行）。 */
  private static String clause(String fragment) {
    return " " + fragment.stripLeading();
  }

  /**
   * 用逗号连接 with 子句里的 CTE 片段。
   *
   * <p>刻意不让调用方手写逗号：少一个逗号的报错是 {@code syntax error at or near per_balance}， 指向的是后面那个 CTE
   * 而不是缺逗号的地方，排查一次要花掉好几分钟。
   */
  private static String ctes(String... fragments) {
    return String.join(",", fragments);
  }

  private final DSLContext sql;

  public InventoryReconciliationService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 执行核对

  /**
   * 执行一次核对并落库。
   *
   * <p><b>一次事务</b>：run 与它的 findings 同生共死。半截的核对记录比没有更糟 —— 有人会拿它当「核过了」的依据。
   */
  @Transactional
  public ReconciliationResult run(ReconciliationRequest request, UUID actorId) {
    var window = window(request);
    var scope = scope(request);
    var scopeLabel = scopeLabel(scope, request);

    // binds 的追加顺序必须与 SQL 文本里 ? 的出现顺序一致：win 的两个 cast 在最前，
    // 其后才是范围条件（scoped CTE 紧跟 win）。
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    scopeConditions(request, where, binds);
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    var scoped = SCOPED_HEAD + sqlWhere + SCOPED_TAIL;

    var prefix = new ArrayList<Object>();
    prefix.add(window.from());
    prefix.add(window.to());
    prefix.addAll(binds);

    var totals = totals(prefix, scoped);
    var discrepancies = discrepancies(prefix, scoped);
    // 后三类可疑只在有差异的行上扫：一致的账不需要解释，对平了的行做同样的扫描只会产出噪音。
    var suspects = suspects(prefix, scoped, discrepancies);

    var status =
        discrepancies.isEmpty() ? ReconciliationStatus.CONSISTENT : ReconciliationStatus.DISCREPANT;
    var runId = UUID.randomUUID();
    var run =
        header(
            runId,
            scope,
            scopeKey(scope, request),
            scopeLabel,
            window,
            status,
            totals,
            discrepancies.size(),
            suspects,
            actorId);
    insertRun(run);
    storeFindings(runId, discrepancies, suspects);

    return new ReconciliationResult(run, discrepancies, suspects, windows(suspects));
  }

  /** 核对区间：左闭右开，缺省近 30 天。两个边界都落库，保证这次核对事后可精确复现。 */
  private Window window(ReconciliationRequest request) {
    var toAt = (request.to() == null ? now() : request.to()).truncatedTo(ChronoUnit.SECONDS);
    var fromAt =
        (request.from() == null ? toAt.minusDays(DEFAULT_WINDOW_DAYS) : request.from())
            .truncatedTo(ChronoUnit.SECONDS);
    if (!fromAt.isBefore(toAt)) {
      throw new IllegalArgumentException("核对区间需为左闭右开且起点早于终点，当前为 " + fromAt + " → " + toAt);
    }
    return new Window(fromAt, toAt);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
  }

  private record Window(OffsetDateTime from, OffsetDateTime to) {}

  // ------------------------------------------------------------ 范围

  /** 范围三选一。传两个会让「这次核了什么」无法一句话说清，scope_key 也存不下两个 id。 */
  private ReconciliationScopeType scope(ReconciliationRequest request) {
    int count =
        (request.warehouseId() == null ? 0 : 1)
            + (request.skuId() == null ? 0 : 1)
            + (request.legalEntityId() == null ? 0 : 1);
    if (count > 1) {
      throw new IllegalArgumentException("核对范围只能指定一个维度：仓库、SKU、货权法人三选一");
    }
    if (request.warehouseId() != null) return ReconciliationScopeType.WAREHOUSE;
    if (request.skuId() != null) return ReconciliationScopeType.SKU;
    if (request.legalEntityId() != null) return ReconciliationScopeType.LEGAL_ENTITY;
    return ReconciliationScopeType.ALL;
  }

  private static UUID scopeKey(ReconciliationScopeType scope, ReconciliationRequest request) {
    return switch (scope) {
      case ALL -> null;
      case WAREHOUSE -> request.warehouseId();
      case SKU -> request.skuId();
      case LEGAL_ENTITY -> request.legalEntityId();
    };
  }

  /** 范围条件只作用在 balance 行上：SKU / 仓库 / 法人三个维度都是 balance 的列。 */
  private void scopeConditions(
      ReconciliationRequest request, List<String> where, List<Object> binds) {
    if (request.warehouseId() != null) {
      where.add("bal.warehouse_id = ?");
      binds.add(request.warehouseId());
    }
    if (request.skuId() != null) {
      where.add("bal.sku_id = ?");
      binds.add(request.skuId());
    }
    if (request.legalEntityId() != null) {
      where.add("bal.legal_entity_id = ?");
      binds.add(request.legalEntityId());
    }
  }

  /**
   * 范围的中文名。
   *
   * <p>固化下来是因为维表会被改名/停用：三个月前那次核对的范围仍然要读得懂。 查不到就是范围本身不存在（RLS 下也包含「不属于本租户」），直接 400 而不是悄悄核全量 ——
   * 悄悄扩大范围会让用户以为核过的是他选的那部分。
   */
  private String scopeLabel(ReconciliationScopeType scope, ReconciliationRequest request) {
    if (scope == ReconciliationScopeType.ALL) {
      return "全部余额行";
    }
    var id = scopeKey(scope, request);
    var code =
        switch (scope) {
          case WAREHOUSE -> refCode("select code from warehouse where id=?", id);
          case SKU -> refCode("select code from sku_ref where id=?", id);
          case LEGAL_ENTITY -> refCode("select code from legal_entity_ref where id=?", id);
          case ALL -> null;
        };
    return scope.label() + " " + code;
  }

  private String refCode(String sqlText, UUID id) {
    var code = sql.fetchOne(sqlText, id);
    if (code == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "指定的核对范围在当前租户下不存在或无权查看");
    }
    return String.valueOf(code.get(0, String.class));
  }

  // ------------------------------------------------------------ 三条查询

  /** 核对总量。覆盖范围内**全部**余额行，与差异行数无关。 */
  private Totals totals(List<Object> prefix, String scoped) {
    // CTE 必须在主查询之前声明：WIN → scoped → per_balance → 主查询。
    Record r =
        sql.fetchOne(
            WIN
                + ctes(scoped, PER_BALANCE)
                + clause(
                    """
                    select count(*) as balance_count,
                      coalesce(sum(pb.period_entry_count), 0) as period_entry_count,
                      coalesce(sum(pb.period_transaction_count), 0) as period_transaction_count,
                      coalesce(sum(pb.opening_on_hand), 0::numeric(19,4)) as opening_on_hand,
                      coalesce(sum(pb.period_on_hand), 0::numeric(19,4)) as period_on_hand,
                      coalesce(sum(pb.after_window_on_hand), 0::numeric(19,4)) as after_window_on_hand,
                      coalesce(sum(pb.total_on_hand), 0::numeric(19,4)) as expected_on_hand,
                      coalesce(sum(sc.snapshot_on_hand), 0::numeric(19,4)) as snapshot_on_hand,
                      coalesce(sum(pb.opening_reserved), 0::numeric(19,4)) as opening_reserved,
                      coalesce(sum(pb.period_reserved), 0::numeric(19,4)) as period_reserved,
                      coalesce(sum(pb.after_window_reserved), 0::numeric(19,4)) as after_window_reserved,
                      coalesce(sum(pb.total_reserved), 0::numeric(19,4)) as expected_reserved,
                      coalesce(sum(sc.snapshot_reserved), 0::numeric(19,4)) as snapshot_reserved
                    from scoped sc
                    left join per_balance pb on pb.balance_id = sc.balance_id
                    """),
            prefix.toArray());
    return new Totals(
        r.get("balance_count", Long.class),
        r.get("period_entry_count", Long.class),
        r.get("period_transaction_count", Long.class),
        decimal(r, "opening_on_hand"),
        decimal(r, "period_on_hand"),
        decimal(r, "after_window_on_hand"),
        decimal(r, "expected_on_hand"),
        decimal(r, "snapshot_on_hand"),
        decimal(r, "opening_reserved"),
        decimal(r, "period_reserved"),
        decimal(r, "after_window_reserved"),
        decimal(r, "expected_reserved"),
        decimal(r, "snapshot_reserved"));
  }

  private record Totals(
      long balanceCount,
      long periodEntryCount,
      long periodTransactionCount,
      BigDecimal openingOnHand,
      BigDecimal periodOnHand,
      BigDecimal afterWindowOnHand,
      BigDecimal expectedOnHand,
      BigDecimal snapshotOnHand,
      BigDecimal openingReserved,
      BigDecimal periodReserved,
      BigDecimal afterWindowReserved,
      BigDecimal expectedReserved,
      BigDecimal snapshotReserved) {}

  /**
   * 差异明细。
   *
   * <p>排序按差异绝对值倒序：先看错得最多的。返回**全部**差异行（这里不翻页）， 因为它们要一起落库成这次核对的结论；翻页发生在回查时（{@link #findings}），
   * 对已经落库的结果做真分页。
   */
  private List<ReconciliationFinding> discrepancies(List<Object> prefix, String scoped) {
    return sql.fetch(
            WIN
                + ctes(scoped, PER_BALANCE, DISCREPANT)
                + " select * from discrepant"
                + " order by greatest(abs(on_hand_diff), abs(reserved_diff)) desc, balance_id",
            prefix.toArray())
        .map(InventoryReconciliationService::discrepancy);
  }

  /**
   * 可疑流水定位。
   *
   * <p>漏记走一条（它看的是「区间内没有分录」的余额行，根本不需要 join 分录）， 后三类共用 {@link #HITS} 一次扫描。三类合计为本次核对的全部可疑线索。
   */
  private List<ReconciliationFinding> suspects(
      List<Object> prefix, String scoped, List<ReconciliationFinding> discrepancies) {
    if (discrepancies.isEmpty()) {
      return List.of();
    }
    var found = new ArrayList<ReconciliationFinding>();
    found.addAll(missing(prefix, scoped));
    found.addAll(hits(prefix, scoped));
    return found;
  }

  /** 漏记：快照非零，但核对区间内一条分录都没有 —— 没有任何流水能解释这个余额。 */
  private List<ReconciliationFinding> missing(List<Object> prefix, String scoped) {
    return sql.fetch(
            WIN
                + ctes(scoped, PER_BALANCE, DISCREPANT)
                + " select * from discrepant where period_entry_count = 0 and snapshot_on_hand <> 0"
                + " order by abs(on_hand_diff) desc, balance_id",
            prefix.toArray())
        .map(
            r -> {
              var at = moment(r, "last_at");
              var entryCount = r.get("entry_count", Long.class);
              return new ReconciliationFinding(
                  UUID.randomUUID(),
                  ReconciliationFindingKind.SUSPECT,
                  ReconciliationSuspectCategory.MISSING,
                  ReconciliationSeverity.WARN,
                  "快照在手为 "
                      + decimal(r, "snapshot_on_hand").toPlainString()
                      + "，但核对区间内没有任何分录能解释它（全历史累计也只有 "
                      + entryCount
                      + " 笔）。货是从哪来的、或者本该记的出库没记，要靠人工查。",
                  r.get("balance_id", UUID.class),
                  r.get("sku_id", UUID.class),
                  r.get("sku_code", String.class),
                  r.get("warehouse_id", UUID.class),
                  r.get("warehouse_code", String.class),
                  r.get("legal_entity_id", UUID.class),
                  r.get("legal_entity_code", String.class),
                  r.get("quality_status_id", UUID.class),
                  r.get("quality_status_code", String.class),
                  null,
                  "",
                  null,
                  "",
                  null,
                  null,
                  null,
                  null,
                  at,
                  null,
                  decimal(r, "total_on_hand"),
                  decimal(r, "total_reserved"),
                  decimal(r, "snapshot_on_hand"),
                  decimal(r, "snapshot_reserved"),
                  decimal(r, "on_hand_diff"),
                  decimal(r, "reserved_diff"),
                  entryCount,
                  r.get("transaction_count", Long.class),
                  at,
                  at);
            });
  }

  /** 迟到补记 / 重复记 / 并发丢失：一次扫描，三个信号。 */
  private List<ReconciliationFinding> hits(List<Object> prefix, String scoped) {
    var rows =
        sql.fetch(
            WIN
                + ctes(scoped, PER_BALANCE, DISCREPANT, HITS)
                + " select h.transaction_id, h.kind, h.ref_type, h.ref_id, h.occurred_at, h.created_at,"
                + " h.actor_id, h.on_hand_delta, h.reserved_delta,"
                + " h.sig_prev_id, extract(epoch from (h.occurred_at - h.sig_prev_at)) as sig_gap_seconds,"
                + " h.hit_prev_id, extract(epoch from (h.created_at - h.hit_prev_at)) as hit_gap_seconds,"
                + " extract(epoch from (h.created_at - h.occurred_at)) as lateness_seconds,"
                + " date_trunc('day', h.occurred_at) as bucket_start,"
                + " d.balance_id, d.sku_id, d.sku_code, d.warehouse_id, d.warehouse_code,"
                + " d.legal_entity_id, d.legal_entity_code, d.quality_status_id, d.quality_status_code,"
                + " d.snapshot_on_hand, d.snapshot_reserved, d.on_hand_diff, d.reserved_diff,"
                + " d.entry_count, d.transaction_count"
                + " from hits h"
                + " join discrepant d on d.balance_id = h.balance_id"
                + " order by h.occurred_at, h.transaction_id",
            prefix.toArray());

    var found = new ArrayList<ReconciliationFinding>();
    for (Record r : rows) {
      var base = baseSuspect(r);
      var occurredAt = moment(r, "occurred_at");
      var createdAt = moment(r, "created_at");
      var bucket = moment(r, "bucket_start");
      var lateness = seconds(r, "lateness_seconds");
      var sigPrev = r.get("sig_prev_id", UUID.class);
      var hitPrev = r.get("hit_prev_id", UUID.class);
      var sigGap = seconds(r, "sig_gap_seconds");
      var hitGap = seconds(r, "hit_gap_seconds");
      var self = r.get("transaction_id", UUID.class);

      if (lateness != null && lateness > BACKFILL_LATENESS_SECONDS) {
        found.add(
            signal(
                base,
                ReconciliationSuspectCategory.BACKFILL,
                ReconciliationSeverity.WARN,
                "这笔流水声称发生在 "
                    + occurredAt
                    + "，却到 "
                    + createdAt
                    + " 才补录，迟了 "
                    + humanSeconds(lateness)
                    + "。跨期补记会让补记之前那几天的账处于无人认领的状态。",
                self,
                null,
                null,
                lateness,
                occurredAt,
                createdAt,
                bucket));
      }
      if (sigPrev != null && sigGap != null && sigGap <= CONCURRENCY_GAP_SECONDS) {
        found.add(
            signal(
                base,
                ReconciliationSuspectCategory.DUPLICATE,
                ReconciliationSeverity.HIGH,
                "同一余额行上，另一笔流水（"
                    + sigPrev
                    + "）与它同类型、同数量、同操作人，相隔仅 "
                    + humanSeconds(sigGap)
                    + "。V1 的 UNIQUE(tenant_id,ref_type,ref_id) 挡得住同一来源单据重投，"
                    + "挡不住同一批货被两张不同的单据各记一次。",
                self,
                sigPrev,
                sigGap,
                null,
                occurredAt,
                createdAt,
                bucket));
      }
      if (hitPrev != null && hitGap != null && hitGap <= CONCURRENCY_GAP_SECONDS) {
        found.add(
            signal(
                base,
                ReconciliationSuspectCategory.LOST_UPDATE,
                ReconciliationSeverity.HIGH,
                "同一余额行在写入时刻上撞在一起：另一笔流水（"
                    + hitPrev
                    + "）在 "
                    + humanSeconds(hitGap)
                    + " 内写过同一条余额行。并发写入下的丢失更新只能长这个样子，"
                    + "要查这两笔之间有没有乐观锁版本被覆盖。",
                self,
                hitPrev,
                hitGap,
                null,
                occurredAt,
                createdAt,
                bucket));
      }
    }
    return found;
  }

  /** 可疑条目的共同部分：定位维度 + 数量背景。 */
  private static ReconciliationFinding baseSuspect(Record r) {
    return new ReconciliationFinding(
        UUID.randomUUID(),
        ReconciliationFindingKind.SUSPECT,
        null,
        ReconciliationSeverity.INFO,
        "",
        r.get("balance_id", UUID.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("warehouse_id", UUID.class),
        r.get("warehouse_code", String.class),
        r.get("legal_entity_id", UUID.class),
        r.get("legal_entity_code", String.class),
        r.get("quality_status_id", UUID.class),
        r.get("quality_status_code", String.class),
        r.get("transaction_id", UUID.class),
        r.get("ref_type", String.class),
        r.get("ref_id", UUID.class),
        r.get("kind", String.class),
        null,
        null,
        null,
        r.get("actor_id", UUID.class),
        r.get("occurred_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class),
        decimal(r, "on_hand_delta"),
        decimal(r, "reserved_delta"),
        decimal(r, "snapshot_on_hand"),
        decimal(r, "snapshot_reserved"),
        decimal(r, "on_hand_diff"),
        decimal(r, "reserved_diff"),
        r.get("entry_count", Long.class),
        r.get("transaction_count", Long.class),
        null,
        null);
  }

  private static ReconciliationFinding signal(
      ReconciliationFinding base,
      ReconciliationSuspectCategory category,
      ReconciliationSeverity severity,
      String reason,
      UUID transactionId,
      UUID relatedTransactionId,
      Long gapSeconds,
      Long latenessSeconds,
      OffsetDateTime occurredAt,
      OffsetDateTime createdAt,
      OffsetDateTime bucket) {
    return new ReconciliationFinding(
        UUID.randomUUID(),
        base.kind(),
        category,
        severity,
        reason,
        base.balanceId(),
        base.skuId(),
        base.skuCode(),
        base.warehouseId(),
        base.warehouseCode(),
        base.legalEntityId(),
        base.legalEntityCode(),
        base.qualityStatusId(),
        base.qualityStatusCode(),
        transactionId,
        base.refType(),
        base.refId(),
        base.txKind(),
        relatedTransactionId,
        gapSeconds,
        latenessSeconds,
        base.actorId(),
        occurredAt,
        createdAt,
        base.onHandSum(),
        base.reservedSum(),
        base.snapshotOnHand(),
        base.snapshotReserved(),
        base.onHandDiff(),
        base.reservedDiff(),
        base.entryCount(),
        base.transactionCount(),
        bucket,
        bucket == null ? null : bucket.plusDays(1));
  }

  private static ReconciliationFinding discrepancy(Record r) {
    var first = moment(r, "first_at");
    var last = moment(r, "last_at");
    var onHandDiff = decimal(r, "on_hand_diff");
    var reservedDiff = decimal(r, "reserved_diff");
    return new ReconciliationFinding(
        UUID.randomUUID(),
        ReconciliationFindingKind.DISCREPANCY,
        null,
        onHandDiff.abs().compareTo(reservedDiff.abs()) >= 0
            ? ReconciliationSeverity.HIGH
            : ReconciliationSeverity.WARN,
        "这条余额行按流水累计在手 "
            + decimal(r, "total_on_hand").toPlainString()
            + "，快照在手 "
            + decimal(r, "snapshot_on_hand").toPlainString()
            + "，差 "
            + onHandDiff.toPlainString()
            + "；预占累计 "
            + decimal(r, "total_reserved").toPlainString()
            + "，快照 "
            + decimal(r, "snapshot_reserved").toPlainString()
            + "，差 "
            + reservedDiff.toPlainString()
            + "。全历史共 "
            + r.get("entry_count", Long.class)
            + " 笔分录 / "
            + r.get("transaction_count", Long.class)
            + " 笔业务，其中核对区间内 "
            + r.get("period_entry_count", Long.class)
            + " 笔。",
        r.get("balance_id", UUID.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("warehouse_id", UUID.class),
        r.get("warehouse_code", String.class),
        r.get("legal_entity_id", UUID.class),
        r.get("legal_entity_code", String.class),
        r.get("quality_status_id", UUID.class),
        r.get("quality_status_code", String.class),
        r.get("last_transaction_id", UUID.class),
        "",
        null,
        "",
        null,
        null,
        null,
        null,
        last,
        null,
        decimal(r, "total_on_hand"),
        decimal(r, "total_reserved"),
        decimal(r, "snapshot_on_hand"),
        decimal(r, "snapshot_reserved"),
        onHandDiff,
        reservedDiff,
        r.get("entry_count", Long.class),
        r.get("transaction_count", Long.class),
        first,
        last);
  }

  /**
   * 可疑区间排名：把可疑发现按时间桶聚起来，回答「是哪**一段**流水可疑」。
   *
   * <p>在 Java 里聚合而不是再发一条 SQL：发现集合本来就在内存里，回数据库一趟只为排序不划算， 而且这样「桶」与「发现」用的是同一份对象，不会出现两处口径漂移。
   * 差异明细**不参与**这个聚合：它的时间跨度是「整条余额行的全部流水」， 与可疑笔按天分桶不是一个尺度，混进来会算出一个没人能解释的数字。
   */
  private List<ReconciliationSuspectWindow> windows(List<ReconciliationFinding> suspects) {
    var buckets = new java.util.TreeMap<OffsetDateTime, ReconciliationSuspectWindow>();
    for (var s : suspects) {
      if (s.windowStart() == null) {
        continue;
      }
      buckets.merge(
          s.windowStart(),
          new ReconciliationSuspectWindow(
              s.windowStart(), s.windowEnd(), 1, 0, s.onHandSum(), s.severity()),
          InventoryReconciliationService::merge);
    }
    var out = new ArrayList<>(buckets.values());
    out.sort(
        Comparator.comparingInt((ReconciliationSuspectWindow w) -> rank(w.severity()))
            .reversed()
            .thenComparing(
                Comparator.comparingLong(ReconciliationSuspectWindow::suspectCount).reversed())
            .thenComparing(
                Comparator.comparing((ReconciliationSuspectWindow w) -> w.onHandDelta().abs())
                    .reversed())
            .thenComparing(ReconciliationSuspectWindow::windowStart));
    return out;
  }

  private static ReconciliationSuspectWindow merge(
      ReconciliationSuspectWindow a, ReconciliationSuspectWindow b) {
    return new ReconciliationSuspectWindow(
        a.windowStart(),
        a.windowEnd(),
        a.suspectCount() + b.suspectCount(),
        a.discrepancyCount() + b.discrepancyCount(),
        a.onHandDelta().add(b.onHandDelta()),
        rank(a.severity()) >= rank(b.severity()) ? a.severity() : b.severity());
  }

  private static int rank(ReconciliationSeverity severity) {
    return switch (severity) {
      case HIGH -> 2;
      case WARN -> 1;
      case INFO -> 0;
    };
  }

  // ------------------------------------------------------------ 结论与落库

  private ReconciliationRun header(
      UUID runId,
      ReconciliationScopeType scope,
      UUID scopeKey,
      String scopeLabel,
      Window window,
      ReconciliationStatus status,
      Totals totals,
      int discrepancyCount,
      List<ReconciliationFinding> suspects,
      UUID actorId) {
    var onHandDiff = totals.expectedOnHand().subtract(totals.snapshotOnHand());
    var reservedDiff = totals.expectedReserved().subtract(totals.snapshotReserved());
    var moments =
        suspects.stream().map(ReconciliationFinding::occurredAt).filter(Objects::nonNull).toList();
    return new ReconciliationRun(
        runId,
        scope,
        scopeKey,
        scopeLabel,
        window.from(),
        window.to(),
        status,
        totals.balanceCount(),
        totals.periodEntryCount(),
        totals.periodTransactionCount(),
        totals.openingOnHand(),
        totals.periodOnHand(),
        totals.afterWindowOnHand(),
        totals.expectedOnHand(),
        totals.snapshotOnHand(),
        onHandDiff,
        totals.openingReserved(),
        totals.periodReserved(),
        totals.afterWindowReserved(),
        totals.expectedReserved(),
        totals.snapshotReserved(),
        reservedDiff,
        discrepancyCount,
        suspects.size(),
        moments.stream().min(Comparator.naturalOrder()).orElse(null),
        moments.stream().max(Comparator.naturalOrder()).orElse(null),
        verdict(
            scopeLabel,
            window,
            status,
            totals,
            discrepancyCount,
            suspects,
            onHandDiff,
            reservedDiff),
        actorId,
        now());
  }

  /** 结论全文。差异为零也要写清「核了哪个区间、多少笔分录」，否则这份记录无法复现。 */
  private String verdict(
      String scopeLabel,
      Window window,
      ReconciliationStatus status,
      Totals totals,
      int discrepancyCount,
      List<ReconciliationFinding> suspects,
      BigDecimal onHandDiff,
      BigDecimal reservedDiff) {
    var sb = new StringBuilder();
    sb.append("范围 ")
        .append(scopeLabel)
        .append("，区间 ")
        .append(window.from())
        .append(" 起（不含）")
        .append(window.to())
        .append("，共核对了 ")
        .append(totals.balanceCount())
        .append(" 条余额行、区间内 ")
        .append(totals.periodEntryCount())
        .append(" 笔分录 / ")
        .append(totals.periodTransactionCount())
        .append(" 笔业务。");
    if (status == ReconciliationStatus.CONSISTENT) {
      sb.append("按流水累计与当前余额快照完全一致（在手差 ")
          .append(onHandDiff.toPlainString())
          .append("，预占差 ")
          .append(reservedDiff.toPlainString())
          .append("）。");
      return sb.toString();
    }
    sb.append("其中 ")
        .append(discrepancyCount)
        .append(" 条余额行对不上：在手差 ")
        .append(onHandDiff.toPlainString())
        .append("，预占差 ")
        .append(reservedDiff.toPlainString())
        .append("；定位到 ")
        .append(suspects.size())
        .append(" 条可疑线索。");
    if (totals.afterWindowOnHand().compareTo(ZERO) != 0
        || totals.afterWindowReserved().compareTo(ZERO) != 0) {
      sb.append("注意：核对区间结束后仍有流水（在手 ")
          .append(totals.afterWindowOnHand().toPlainString())
          .append("、预占 ")
          .append(totals.afterWindowReserved().toPlainString())
          .append("），说明存在错期录入或事后补记，先查这部分。");
    }
    return sb.toString();
  }

  private void insertRun(ReconciliationRun run) {
    // 占位符按列位置生成，不手数：手写的问号个数一旦与列数对不上，PostgreSQL 报的是
    // 「bind message supplies N parameters」，排查成本远高于这几行。
    //
    // 两处纪律都在这里：
    //  1. 五个 timestamptz 列必须显式 cast —— 纯文本语句里 jOOQ 无从推断 OffsetDateTime，
    //     不 cast 会被驱动当 varchar 送过去，报 "column window_from is of type timestamp with
    //     time zone but expression is of type character varying"。
    //  2. created_at 刻意**不**排在列清单最后（列顺序是自由的）。cast 落在绑定序列末位会让
    //     jOOQ 整体错位一格，末位留给普通列 triggered_by 才安全。
    String values =
        placeholders(5)
            + ",?::timestamptz,?::timestamptz,"
            + placeholders(18)
            + ",?::timestamptz,?::timestamptz,?::timestamptz,"
            + placeholders(2);
    sql.execute(
        "insert into inventory_reconciliation_run(id,tenant_id,scope_type,scope_key,scope_label,"
            + " window_from,window_to,status,checked_balance_count,checked_entry_count,"
            + " checked_transaction_count,opening_on_hand,period_on_hand,after_window_on_hand,"
            + " expected_on_hand,snapshot_on_hand,on_hand_diff,opening_reserved,period_reserved,"
            + " after_window_reserved,expected_reserved,snapshot_reserved,reserved_diff,"
            + " discrepancy_count,suspect_count,first_suspect_at,last_suspect_at,created_at,"
            + " verdict_note,triggered_by) values("
            + values
            + ")",
        run.id(),
        currentTenant(),
        run.scopeType().name(),
        run.scopeKey(),
        run.scopeLabel(),
        run.windowFrom(),
        run.windowTo(),
        run.status().name(),
        run.checkedBalanceCount(),
        run.checkedEntryCount(),
        run.checkedTransactionCount(),
        run.openingOnHand(),
        run.periodOnHand(),
        run.afterWindowOnHand(),
        run.expectedOnHand(),
        run.snapshotOnHand(),
        run.onHandDiff(),
        run.openingReserved(),
        run.periodReserved(),
        run.afterWindowReserved(),
        run.expectedReserved(),
        run.snapshotReserved(),
        run.reservedDiff(),
        run.discrepancyCount(),
        run.suspectCount(),
        run.firstSuspectAt(),
        run.lastSuspectAt(),
        run.createdAt(),
        run.verdictNote(),
        run.triggeredBy());
  }

  /**
   * 落库本次核对的全部发现。
   *
   * <p>逐行 execute：与本仓库其它写入一致（见 {@code ImportJobService}）—— 纯文本语句上带 {@code ?::cast} 的 {@code
   * DSLContext.batch(String,...)} 绑定不可靠。
   */
  private void storeFindings(
      UUID runId, List<ReconciliationFinding> discrepancies, List<ReconciliationFinding> suspects) {
    var all = new ArrayList<ReconciliationFinding>(discrepancies);
    all.addAll(suspects);
    if (all.isEmpty()) {
      return;
    }
    var tenant = currentTenant();
    // 四个 timestamptz 列（occurred_at / entry_created_at / window_start / window_end）显式 cast，
    // 理由同 insertRun；window_end 的 cast 后面还跟着 reason 这一列，不落在绑定序列末位。
    String values =
        placeholders(20)
            + ",?::timestamptz,?::timestamptz,"
            + placeholders(11)
            + ",?::timestamptz,?::timestamptz,?";
    String insert =
        "insert into inventory_reconciliation_finding(id,tenant_id,run_id,kind,category,severity,"
            + " balance_id,sku_id,sku_code,warehouse_id,warehouse_code,legal_entity_id,legal_entity_code,"
            + " quality_status_id,quality_status_code,transaction_id,ref_type,ref_id,tx_kind,actor_id,"
            + " occurred_at,entry_created_at,related_transaction_id,gap_seconds,lateness_seconds,"
            + " on_hand_sum,reserved_sum,snapshot_on_hand,snapshot_reserved,on_hand_diff,reserved_diff,"
            + " entry_count,transaction_count,window_start,window_end,reason) values("
            + values
            + ")";
    for (var f : all) {
      sql.execute(
          insert,
          f.id(),
          tenant,
          runId,
          f.kind().name(),
          f.category() == null ? null : f.category().name(),
          f.severity().name(),
          f.balanceId(),
          f.skuId(),
          f.skuCode(),
          f.warehouseId(),
          f.warehouseCode(),
          f.legalEntityId(),
          f.legalEntityCode(),
          f.qualityStatusId(),
          f.qualityStatusCode(),
          f.transactionId(),
          f.refType(),
          f.refId(),
          f.txKind(),
          f.actorId(),
          f.occurredAt(),
          f.entryCreatedAt(),
          f.relatedTransactionId(),
          f.gapSeconds(),
          f.latenessSeconds(),
          f.onHandSum(),
          f.reservedSum(),
          f.snapshotOnHand(),
          f.snapshotReserved(),
          f.onHandDiff(),
          f.reservedDiff(),
          f.entryCount(),
          f.transactionCount(),
          f.windowStart(),
          f.windowEnd(),
          f.reason());
    }
  }

  /** 生成 n 个 {@code ?::} 占位符的 values 片段。 */
  private static String placeholders(int n) {
    return String.join(",", java.util.Collections.nCopies(n, "?"));
  }

  /**
   * 当前租户。
   *
   * <p>从 RLS 策略的同一个来源取（{@code app.current_tenant}），而不是从任何请求参数取。 写入的 tenant_id 与 RLS
   * 注入的租户**必须是同一个**，否则 WITH CHECK 会直接拒写 —— 这比在应用层自查可靠。
   */
  private UUID currentTenant() {
    var row = sql.fetchOne("select current_setting('app.current_tenant',true)::uuid as t");
    var tenant = row == null ? null : row.get("t", UUID.class);
    if (tenant == null) {
      throw new IllegalStateException("缺少租户上下文：请在 AccessService.run 绑定的事务内调用");
    }
    return tenant;
  }

  // ================================================================ 回查

  /** 核对批次列表。真分页；每批次的差异明细/可疑流水进详情端点按需拉，不在这里内联。 */
  public ReconciliationRunPage runs(Integer page, Integer size, String status) {
    int size0 = Math.min(MAX_PAGE_SIZE, Math.max(1, size == null ? 20 : size));
    int page0 = Math.max(0, page == null ? 0 : page);
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    if (status != null && !status.isBlank()) {
      where.add("status = ?");
      binds.add(parseStatus(status).name());
    }
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    long total =
        sql.fetchOne(
                "select count(*) from inventory_reconciliation_run" + sqlWhere, binds.toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
            "select * from inventory_reconciliation_run"
                + sqlWhere
                + " order by created_at desc, id desc limit ? offset ?",
            append(binds, size0, (long) page0 * size0).toArray());
    return new ReconciliationRunPage(
        rows.map(InventoryReconciliationService::runRow), total, page0, size0);
  }

  /** 某次核对的结论。跨租户访问在 RLS 下表现为「查不到」，返回 404 而不是 403。 */
  public ReconciliationRun runOf(UUID runId) {
    Record r = sql.fetchOne("select * from inventory_reconciliation_run where id=?", runId);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "核对记录不存在或无权查看");
    }
    return runRow(r);
  }

  /** 某次核对的发现分页。kind 决定翻的是差异明细还是可疑流水，合计为全量。 */
  public ReconciliationFindingPage findings(UUID runId, String kind, Integer page, Integer size) {
    runOf(runId);
    int size0 = Math.min(MAX_PAGE_SIZE, Math.max(1, size == null ? 50 : size));
    int page0 = Math.max(0, page == null ? 0 : page);
    var kind0 = parseKind(kind);
    var args = new ArrayList<Object>();
    args.add(runId);
    args.add(kind0.name());
    long total =
        sql.fetchOne(
                "select count(*) from inventory_reconciliation_finding where run_id=? and kind=?",
                args.toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
            "select * from inventory_reconciliation_finding where run_id=? and kind=?"
                + " order by severity desc, window_start desc nulls last, id limit ? offset ?",
            append(args, size0, (long) page0 * size0).toArray());
    return new ReconciliationFindingPage(
        rows.map(InventoryReconciliationService::findingRow), total, page0, size0);
  }

  /**
   * 某次核对的可疑区间排名。
   *
   * <p>刻意**不**走分页：区间排名的全部分母就是可疑条目总数，翻页会算出一个 「只统计了前 N 条」的可疑区间，那就是个没人能用的数字。可疑条目数受本次核对区间约束，
   * 一次全量读入是可控的。
   */
  public List<ReconciliationSuspectWindow> suspectWindows(UUID runId) {
    runOf(runId);
    var suspects =
        sql.fetch(
                "select * from inventory_reconciliation_finding"
                    + " where run_id=? and kind='SUSPECT' order by window_start nulls last, id",
                runId)
            .map(InventoryReconciliationService::findingRow);
    return windows(suspects);
  }

  // ------------------------------------------------------------ 行映射与工具

  private static ReconciliationRun runRow(Record r) {
    return new ReconciliationRun(
        r.get("id", UUID.class),
        ReconciliationScopeType.valueOf(r.get("scope_type", String.class)),
        r.get("scope_key", UUID.class),
        r.get("scope_label", String.class),
        moment(r, "window_from"),
        moment(r, "window_to"),
        ReconciliationStatus.valueOf(r.get("status", String.class)),
        r.get("checked_balance_count", Long.class),
        r.get("checked_entry_count", Long.class),
        r.get("checked_transaction_count", Long.class),
        decimal(r, "opening_on_hand"),
        decimal(r, "period_on_hand"),
        decimal(r, "after_window_on_hand"),
        decimal(r, "expected_on_hand"),
        decimal(r, "snapshot_on_hand"),
        decimal(r, "on_hand_diff"),
        decimal(r, "opening_reserved"),
        decimal(r, "period_reserved"),
        decimal(r, "after_window_reserved"),
        decimal(r, "expected_reserved"),
        decimal(r, "snapshot_reserved"),
        decimal(r, "reserved_diff"),
        r.get("discrepancy_count", Long.class),
        r.get("suspect_count", Long.class),
        moment(r, "first_suspect_at"),
        moment(r, "last_suspect_at"),
        r.get("verdict_note", String.class),
        r.get("triggered_by", UUID.class),
        moment(r, "created_at"));
  }

  private static ReconciliationFinding findingRow(Record r) {
    return new ReconciliationFinding(
        r.get("id", UUID.class),
        ReconciliationFindingKind.valueOf(r.get("kind", String.class)),
        r.get("category") == null
            ? null
            : ReconciliationSuspectCategory.valueOf(r.get("category", String.class)),
        ReconciliationSeverity.valueOf(r.get("severity", String.class)),
        r.get("reason", String.class),
        r.get("balance_id", UUID.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("warehouse_id", UUID.class),
        r.get("warehouse_code", String.class),
        r.get("legal_entity_id", UUID.class),
        r.get("legal_entity_code", String.class),
        r.get("quality_status_id", UUID.class),
        r.get("quality_status_code", String.class),
        r.get("transaction_id", UUID.class),
        r.get("ref_type", String.class),
        r.get("ref_id", UUID.class),
        r.get("tx_kind", String.class),
        r.get("related_transaction_id", UUID.class),
        r.get("gap_seconds", Long.class),
        r.get("lateness_seconds", Long.class),
        r.get("actor_id", UUID.class),
        moment(r, "occurred_at"),
        moment(r, "entry_created_at"),
        decimal(r, "on_hand_sum"),
        decimal(r, "reserved_sum"),
        decimal(r, "snapshot_on_hand"),
        decimal(r, "snapshot_reserved"),
        decimal(r, "on_hand_diff"),
        decimal(r, "reserved_diff"),
        r.get("entry_count", Long.class),
        r.get("transaction_count", Long.class),
        moment(r, "window_start"),
        moment(r, "window_end"));
  }

  /** 未知码直接 400：筛一个不存在的结论返回空列表，用户会以为「这段时间没有差异」。 */
  private static ReconciliationStatus parseStatus(String value) {
    for (var s : ReconciliationStatus.values()) {
      if (s.name().equalsIgnoreCase(value.trim())) return s;
    }
    throw new IllegalArgumentException("未知的核对结论：" + value + "，可选：CONSISTENT、DISCREPANT");
  }

  private static ReconciliationFindingKind parseKind(String value) {
    if (value == null || value.isBlank()) {
      return ReconciliationFindingKind.SUSPECT;
    }
    for (var k : ReconciliationFindingKind.values()) {
      if (k.name().equalsIgnoreCase(value.trim())) return k;
    }
    throw new IllegalArgumentException("未知的发现类型：" + value + "，可选：DISCREPANCY、SUSPECT");
  }

  /**
   * 取 numeric 列。
   *
   * <p>{@code null} 收成 0 而不是留 null：数量为「没有」时页面要显示 0.0000， 显示空白会被读成「没查到」。零统一用 {@link #ZERO}，避免同一列里混着
   * scale 不一样的 0。
   */
  private static BigDecimal decimal(Record r, String field) {
    var v = r.get(field, BigDecimal.class);
    return v == null ? ZERO : v;
  }

  /**
   * 取时间值。
   *
   * <p>与 7.1.2.1 同一处理：纯文本语句里 jOOQ 拿不到列类型，返回的可能是 {@link OffsetDateTime}、{@link java.time.Instant} 或
   * {@link java.sql.Timestamp}， 直接 {@code get(field, OffsetDateTime.class)} 会在拿到 Instant 时抛
   * ClassCastException。
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

  /** {@code extract(epoch from ...)} 在 PG 里返回 numeric，jOOQ 可能给 BigDecimal / Double / Long。 */
  private static Long seconds(Record r, String field) {
    Object value = r.get(field);
    return value == null ? null : ((Number) value).longValue();
  }

  private static String humanSeconds(Long seconds) {
    if (seconds == null) return "0 秒";
    if (seconds < 60) return seconds + " 秒";
    if (seconds < 3600) return (seconds / 60) + " 分钟";
    if (seconds < 86400) return (seconds / 3600) + " 小时";
    return (seconds / 86400) + " 天";
  }

  private static List<Object> append(List<Object> binds, Object... more) {
    var out = new ArrayList<>(binds);
    out.addAll(Arrays.asList(more));
    return out;
  }
}
