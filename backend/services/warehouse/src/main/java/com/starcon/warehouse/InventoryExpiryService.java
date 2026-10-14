package com.starcon.warehouse;

import static com.starcon.warehouse.InventoryExpiryModels.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 7.3.1.2 效期与先进先出。
 *
 * <p>蓝图：「对有保质期的商品，按 FEFO（先到期先出）或 FIFO 分配出库批次，预警临期与过期库存」， 输入「批次失效日期；出库分配策略（FEFO/FIFO/指定批次）；预警提前天数」，
 * 输出「推荐出库批次；临期/过期预警清单」。
 *
 * <p>五条实现纪律：
 *
 * <ul>
 *   <li><b>分配真从 {@code stock_bucket} 排序算出来</b>。批次只在 {@code stock_bucket} 这一层是精确的 （{@code
 *       inventory_balance} / {@code inventory_entry} 都没有 {@code lot_id}， 与 7.3.1.1 的 {@code
 *       attribution=BALANCE_GRANULAR} 是同一事实）， 所以推荐的每一行都对应真实的 bucket，不是按批次号估算的。
 *   <li><b>FEFO 与 FIFO 是两种不同的排序依据</b>，各自有测试钉住： FEFO 按 {@code expires_on} 升序，FIFO 按 {@code
 *       received_on}（业务日期）升序、 缺失时退回 {@code stock_bucket.created_at}。 <b>刻意不用 {@code
 *       last_movement_at} 排序</b>：它是「最近一次变动的业务时间」， 每动一次就前移，拿它排先进先出等于按"最近被碰过的先后"出库，完全不是先进先出。
 *   <li><b>过期库存在任何策略下都不参与出库</b>，并且它的数量被单独报进 {@link FefoAllocationIneligible#expiredAvailable} ——
 *       分配不足时界面能回答「为什么不够」。
 *   <li><b>提前天数是每 SKU 的配置</b>（{@code inventory_expiry_policy.lead_days}）， 不是全局常量：食品与日化的临期窗口差一个数量级。
 *   <li><b>策略开关开着才生效</b>。蓝图说「第一期是否启用可延后，但维度已保留」， 这句话落到代码上就是 {@code enabled}：关着时出库退回按库位顺序取可用量， 并在响应里
 *       {@code policyEnabled=false} 如实报出，不假装已经排过 FEFO。
 * </ul>
 *
 * <p>租户来自已认证成员关系（控制器传入），不接受任何客户端租户参数；RLS 在数据库层强制隔离。
 */
@Service
public class InventoryExpiryService {

  /** 单页最大行数。批次是主数据，量级远小于流水。 */
  static final int MAX_PAGE_SIZE = 200;

  /**
   * 没有配置策略时的提前天数。
   *
   * <p>刻意是一个**有名字的默认值而不是魔法数字**：30 天是食品行业最常见的临期口径， 而它是「没人配时的兜底」—— 响应里的 {@code leadDaysSource} 会如实标成
   * DEFAULT， 界面上能看出这个 30 不是谁调出来的。
   */
  public static final int DEFAULT_LEAD_DAYS = 30;

  /**
   * 「本次请求没有覆盖提前天数」的哨兵值。
   *
   * <p>必须是**合法天数区间之外**的值：任何落在 0~3650 的数都可能是用户真的填的。 之所以不传 null，是因为 SQL 侧的优先级要写成 {@code
   * coalesce(nullif(?,-1), p.lead_days, ?)} —— 见 {@link #alerts} 里那段说明。
   */
  private static final int NO_OVERRIDE = -1;

  /** 提前天数的合法区间。上限与 DB 的 CHECK 一致，避免只在应用层拒绝而直接落库时被打回。 */
  static final int MAX_LEAD_DAYS = 3650;

  /** 效期预警的默认关注范围：已过期 + 临期。 */
  private static final List<String> DEFAULT_STATUSES = List.of("EXPIRED", "EXPIRING");

  private static final List<String> STRATEGIES =
      Arrays.stream(ExpiryAllocationStrategy.values()).map(Enum::name).toList();

  private static final List<String> STATUSES =
      Arrays.stream(ExpiryStatus.values()).map(Enum::name).toList();

  /** 效期预警排序键白名单。 */
  private static final List<String> ALERT_SORTS =
      List.of("EXPIRY", "ON_HAND", "LOT_CODE", "RECEIVED_ON", "UPDATED_AT", "SKU");

  /** 策略列表排序键白名单。 */
  private static final List<String> POLICY_SORTS =
      List.of("SKU", "STRATEGY", "LEAD_DAYS", "ENABLED", "UPDATED_AT");

  private final DSLContext sql;

  public InventoryExpiryService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 策略列表

  /** 策略列表 + 全量汇总。 */
  public ExpiryPolicyPage policies(ExpiryPolicyQuery query) {
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size() == null ? 50 : query.size()));
    int pageNumber = Math.max(0, query.page() == null ? 0 : query.page());

    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    policyConditions(query, where, binds);
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    long total =
        sql.fetchOne("select count(*)" + policyFrom("") + sqlWhere, binds.toArray())
            .get(0, Long.class);

    var rows =
        sql.fetch(
            POLICY_SELECT + policyFrom("") + sqlWhere + policyOrder(query) + " limit ? offset ?",
            append(binds, size, (long) pageNumber * size).toArray());

    return new ExpiryPolicyPage(
        policySummary(),
        rows.stream().map(InventoryExpiryService::policyRow).toList(),
        strategyOptions(),
        total,
        pageNumber,
        size);
  }

  /**
   * 策略列表行投影。
   *
   * <p>{@code expiringLotCount} / {@code expiredLotCount} 用该策略自己的 {@code lead_days} 算， 所以「这个 SKU 配的
   * 60 天窗口下有多少临期批次」在列表里就能直接看到 —— 配 7 天和配 90 天的两个 SKU，同一批货的临期数完全不同，这正是阈值可配置的意义。
   *
   * <p>批次数一律 {@code count(distinct l.id)}：同一批次横跨多个库位 / 多个仓时， {@code count(*)} 数的是 bucket
   * 行数，会让「有多少个临期批次」变成「有多少个库位有临期货」。
   */
  private static final String POLICY_SELECT =
      """
      select p.id as policy_id,p.sku_id,s.code as sku_code,s.name as sku_name,p.strategy,p.enabled,\
       p.lead_days,p.specified_lot_id,sl.lot_code as specified_lot_code,p.notes,p.updated_at,\
       coalesce(agg.on_hand,0) as on_hand,coalesce(agg.lot_count,0) as lot_count,\
       coalesce(agg.expiring_lot_count,0) as expiring_lot_count,\
       coalesce(agg.expired_lot_count,0) as expired_lot_count\
      """;

  private static String policyFrom(String a) {
    return " from inventory_expiry_policy p"
        + a
        + " join sku_ref s on s.tenant_id=p"
        + a
        + ".tenant_id and s.id=p"
        + a
        + ".sku_id"
        + " left join inventory_lot sl on sl.tenant_id=p"
        + a
        + ".tenant_id and sl.id=p"
        + a
        + ".specified_lot_id"
        + " left join lateral (select sum(b.on_hand) as on_hand,count(distinct l.id) as lot_count,"
        + " count(distinct l.id) filter (where l.expires_on is not null and l.expires_on<current_date)"
        + "   as expired_lot_count,"
        + " count(distinct l.id) filter (where l.expires_on is not null and l.expires_on>=current_date"
        + "   and l.expires_on<=current_date+p"
        + a
        + ".lead_days) as expiring_lot_count"
        + " from stock_bucket b"
        + " join inventory_lot l on l.tenant_id=b.tenant_id and l.id=b.lot_id"
        + " where b.tenant_id=p"
        + a
        + ".tenant_id and b.sku_id=p"
        + a
        + ".sku_id and b.on_hand>0) agg"
        + a
        + " on true";
  }

  private void policyConditions(ExpiryPolicyQuery query, List<String> where, List<Object> binds) {
    if (query.skuId() != null) {
      where.add("p.sku_id=?");
      binds.add(query.skuId());
    }
    if (query.enabled() != null) {
      where.add("p.enabled=?");
      binds.add(query.enabled());
    }
    addEnumIn(where, binds, "p.strategy", query.strategy(), STRATEGIES, "出库分配策略");
    if (query.keyword() != null && !query.keyword().isBlank()) {
      where.add("(s.code ilike ? or s.name ilike ? or sl.lot_code ilike ?)");
      var k = "%" + query.keyword().trim() + "%";
      for (int i = 0; i < 3; i++) binds.add(k);
    }
  }

  private String policyOrder(ExpiryPolicyQuery query) {
    String sort = query.sort() == null ? "SKU" : query.sort().toUpperCase(Locale.ROOT);
    if (!POLICY_SORTS.contains(sort)) {
      throw new IllegalArgumentException(
          "不支持的排序键：" + query.sort() + "，可选：" + String.join("、", POLICY_SORTS));
    }
    return switch (sort) {
      case "STRATEGY" -> " order by p.strategy,s.code,p.id";
      case "LEAD_DAYS" -> " order by p.lead_days desc,s.code,p.id";
      case "ENABLED" -> " order by p.enabled desc,s.code,p.id";
      case "UPDATED_AT" -> " order by p.updated_at desc,s.code,p.id";
      default -> " order by s.code,p.id";
    };
  }

  /**
   * 策略汇总。**全量**，不受筛选与分页影响。
   *
   * <p>{@code skuWithExpiryLotCount} 数的是「本租户有带失效日期批次的 SKU 数」， 它回答的是「效期管理这件事目前覆盖了多少个品」—— 与已配置的策略数对比，
   * 就能一眼看出还有哪些效期品没纳入策略。
   */
  private ExpiryPolicySummary policySummary() {
    Record main =
        sql.fetchOne(
            "select count(*) as policy_count,count(distinct p.sku_id) as sku_count,"
                + " count(*) filter (where p.enabled) as enabled_count,"
                + " count(*) filter (where p.strategy='FEFO') as fefo_count,"
                + " count(*) filter (where p.strategy='FIFO') as fifo_count,"
                + " count(*) filter (where p.strategy='SPECIFIED_LOT') as specified_lot_count,"
                + " count(*) filter (where not p.enabled) as disabled_count"
                + " from inventory_expiry_policy p");
    Record scope =
        sql.fetchOne(
            "select count(distinct l.sku_id) as sku_with_expiry from inventory_lot l"
                + " where l.expires_on is not null");
    return new ExpiryPolicySummary(
        main.get("policy_count", Long.class),
        main.get("sku_count", Long.class),
        main.get("enabled_count", Long.class),
        main.get("fefo_count", Long.class),
        main.get("fifo_count", Long.class),
        main.get("specified_lot_count", Long.class),
        main.get("disabled_count", Long.class),
        scope.get("sku_with_expiry", Long.class));
  }

  /** 策略下拉词表：**租户级，不随筛选与时间变化**（与 7.3.1.1 的下拉同一纪律）。 */
  private List<ExpiryPolicyOption> strategyOptions() {
    return sql.fetch(
            "select strategy as value,case strategy when 'FEFO' then '先到期先出'"
                + " when 'FIFO' then '先进先出' else '指定批次' end as label,"
                + " count(*) as policy_count from inventory_expiry_policy"
                + " group by strategy order by strategy")
        .map(
            r ->
                new ExpiryPolicyOption(
                    r.get("value", String.class),
                    r.get("label", String.class),
                    r.get("policy_count", Long.class)));
  }

  // ================================================================ 单个 SKU 的生效策略

  /**
   * 某个 SKU 的**生效策略**：配了就用配的，没配就用兜底默认值并如实标成 {@code configured=false}。
   *
   * <p>与 7.2.2.1 的 {@code /effective} 同一纪律：未配置必须**如实报出**， 而不是静默按 0 或某个隐含值处理 —— 界面上「配成 30
   * 天」与「没人配」必须分得开。
   *
   * <p>跨租户的 SKU 查不到 → 404，不泄露存在性。
   */
  public ExpiryPolicy policy(UUID skuId) {
    Record r =
        sql.fetchOne(
            "select s.id as sku_id,s.code as sku_code,s.name as sku_name,p.id as policy_id,"
                + " p.strategy,p.enabled,p.lead_days,p.specified_lot_id,"
                + " sl.lot_code as specified_lot_code,p.notes,p.updated_at,"
                + " (select min(l.expires_on) from inventory_lot l"
                + "  where l.tenant_id=s.tenant_id and l.sku_id=s.id) as nearest_expiry"
                + " from sku_ref s"
                + " left join inventory_expiry_policy p"
                + " on p.tenant_id=s.tenant_id and p.sku_id=s.id"
                + " left join inventory_lot sl on sl.tenant_id=s.tenant_id and sl.id=p.specified_lot_id"
                + " where s.id=?",
            skuId);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "SKU 不存在或无权查看");
    }
    return toPolicy(r);
  }

  private static ExpiryPolicy toPolicy(Record r) {
    boolean configured = r.get("policy_id", UUID.class) != null;
    var strategy =
        configured
            ? ExpiryAllocationStrategy.parse(r.get("strategy", String.class))
            : ExpiryAllocationStrategy.FEFO;
    return new ExpiryPolicy(
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        strategy.name(),
        strategy.label(),
        configured && Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
        configured,
        configured ? intOrNull(r, "lead_days") : DEFAULT_LEAD_DAYS,
        r.get("specified_lot_id", UUID.class),
        r.get("specified_lot_code", String.class),
        r.get("nearest_expiry", LocalDate.class),
        r.get("notes", String.class),
        configured ? "POLICY" : "DEFAULT",
        r.get("updated_at", OffsetDateTime.class));
  }

  // ================================================================ 建立/修改策略

  /**
   * 建立或修改某 SKU 的出库分配策略（按 SKU 幂等 upsert）。
   *
   * <p>用 {@code INSERT ... ON CONFLICT (tenant_id,sku_id) DO UPDATE} 而不是「先查再决定」： 前者由 DB
   * 的唯一索引在并发下裁决，后者的两次调用可能都走 INSERT 分支而撞 23505。
   *
   * <p>{@code updated_at} 显式推进：{@code inventory_expiry_policy} 上没有自动更新触发器
   * （本迁移刻意不加：配置行的最后修改时间由应用层显式写，与 inventory_lot 同一口径）。
   *
   * <p>指定批次的归属由 DB 触发器兜底（{@code guard_expiry_policy_lot_sku}）， 这里先做一次前置校验只为给出可读错误 —— 可读报错比 PG
   * 的约束名有用。
   *
   * @param tenantId 来自已认证成员关系（控制器传入），**不是**客户端参数。 RLS 的 WITH CHECK 会再用它复核一次，两道都不靠客户端。
   */
  public ExpiryPolicySaved upsertPolicy(UUID tenantId, ExpiryPolicyUpsertRequest request) {
    UUID skuId = request.skuId();
    if (sql.fetchOne("select s.id from sku_ref s where s.id=?", skuId) == null) {
      throw new IllegalArgumentException("SKU 不存在或无权访问：" + skuId);
    }
    var strategy =
        request.strategy() == null || request.strategy().isBlank()
            ? ExpiryAllocationStrategy.FEFO
            : ExpiryAllocationStrategy.parse(request.strategy());

    Integer leadDays = request.leadDays() == null ? DEFAULT_LEAD_DAYS : request.leadDays();
    if (leadDays < 0 || leadDays > MAX_LEAD_DAYS) {
      throw new IllegalArgumentException(
          "预警提前天数必须在 0 ~ " + MAX_LEAD_DAYS + " 之间（当前 " + leadDays + "）");
    }

    // 指定批次策略必须有指定批次；其它策略允许留一个（无害冗余，DB CHECK 也这么判）。
    UUID specifiedLotId = request.specifiedLotId();
    if (strategy == ExpiryAllocationStrategy.SPECIFIED_LOT && specifiedLotId == null) {
      throw new IllegalArgumentException("指定批次策略必须给出 specifiedLotId（要从哪一批出）");
    }
    if (specifiedLotId != null) {
      requireLotOfSku(skuId, specifiedLotId);
    }

    var notes = request.notes() == null ? "" : request.notes().trim();
    if (notes.length() > 500) {
      throw new IllegalArgumentException("策略备注长度不能超过 500 个字符");
    }

    try {
      sql.execute(
          "insert into inventory_expiry_policy(id,tenant_id,sku_id,strategy,enabled,lead_days,"
              + "specified_lot_id,notes) values(?,?,?,?,?,?,?,?)"
              + " on conflict (tenant_id,sku_id) do update set strategy=excluded.strategy,"
              + " enabled=excluded.enabled,lead_days=excluded.lead_days,"
              + " specified_lot_id=excluded.specified_lot_id,notes=excluded.notes,updated_at=now()",
          UUID.randomUUID(),
          tenantId,
          skuId,
          strategy.name(),
          request.enabled() != null && request.enabled(),
          leadDays,
          specifiedLotId,
          notes);
    } catch (org.springframework.dao.DataAccessException e) {
      throw translatePolicyConflict(e);
    }
    return policySaved(skuId);
  }

  /** 批次必须属于该 SKU。前置校验只为给出可读错误，DB 触发器是最终防线。 */
  private void requireLotOfSku(UUID skuId, UUID lotId) {
    Record lot = sql.fetchOne("select l.sku_id from inventory_lot l where l.id=?", lotId);
    if (lot == null) {
      throw new IllegalArgumentException("指定批次不存在或无权访问：" + lotId);
    }
    UUID owner = lot.get("sku_id", UUID.class);
    if (!owner.equals(skuId)) {
      throw new IllegalArgumentException("指定批次不属于该 SKU（批次 " + lotId + " 归属的 SKU 是 " + owner + "）");
    }
  }

  /** 唯一冲突翻译成 409：不把 PG 的约束名原样抛给用户。 */
  private static RuntimeException translatePolicyConflict(
      org.springframework.dao.DataAccessException e) {
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      if (cause instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState())) {
        return new ResponseStatusException(
            HttpStatus.CONFLICT, "该 SKU 已存在分配策略，请改用修改（同一 SKU 只允许一条）");
      }
    }
    return e;
  }

  private ExpiryPolicySaved policySaved(UUID skuId) {
    Record r =
        sql.fetchOne(
            "select p.id as policy_id,p.sku_id,p.strategy,p.enabled,p.lead_days,"
                + " p.specified_lot_id,p.notes,p.created_at,p.updated_at"
                + " from inventory_expiry_policy p where p.sku_id=?",
            skuId);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "策略不存在或无权查看");
    }
    return new ExpiryPolicySaved(
        r.get("policy_id", UUID.class),
        r.get("sku_id", UUID.class),
        r.get("strategy", String.class),
        Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
        intOrNull(r, "lead_days"),
        r.get("specified_lot_id", UUID.class),
        r.get("notes", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  // ================================================================ 分配推荐

  /**
   * 出库分配推荐：**真从 {@code stock_bucket} 按策略排序算出来**。
   *
   * <p>算法（与 7.1.1.2 明细同一纪律：数量逻辑必须可审阅，不靠隐式 ORM）：
   *
   * <ol>
   *   <li>取出该 SKU 在该仓全部 {@code available > 0} 的 bucket，按策略的 ORDER BY 排好；
   *   <li>逐行分类：非可售 / 不可拣 / 过期 / 无批次 / 无效期（FEFO 下）→ 进 {@code ineligible}； 其余为可分配候选；
   *   <li>按顺序逐个 bucket 取 {@code min(剩余需求, 该 bucket 可用量)}，取完即止。
   * </ol>
   *
   * <p>分到批次的量因此是**批次精确**的（stock_bucket 是批次粒度事实层）， 不像 7.3.1.1 的流水那样只能到 balance 口径。
   *
   * <p><b>过期 / 临期判定全部在 SQL 侧用 {@code current_date} 完成</b>（与 7.3.1.1 同一口径）： 用 {@code
   * LocalDate.now()} 会在跨时区部署时与建档兜底差一天，而 FEFO 的整个排序建立在这上面。
   *
   * <p>本方法**只读不写**：它出的是推荐，扣减由 8 拣货的业务单据负责， 避免在没有真实出库单的情况下凭空减少库存。
   */
  public FefoAllocationPlan recommend(FefoAllocationRequest request) {
    UUID skuId = request.skuId();
    UUID warehouseId = request.warehouseId();
    if (request.quantity() == null) {
      throw new IllegalArgumentException("出库数量不能为空");
    }
    if (request.quantity().signum() <= 0) {
      throw new IllegalArgumentException(
          "出库数量必须大于 0（当前 " + request.quantity().toPlainString() + "）");
    }

    Record sku =
        sql.fetchOne(
            "select s.id,s.code as sku_code,s.name as sku_name,w.code as warehouse_code"
                + " from sku_ref s join warehouse w on w.tenant_id=s.tenant_id and w.id=?"
                + " where s.id=?",
            warehouseId,
            skuId);
    if (sku == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "SKU 或仓库不存在或无权查看");
    }

    // 生效策略：请求显式指定 > SKU 策略（且开关打开）> 兜底默认（不做效期排序）
    var notes = new ArrayList<String>();
    ExpiryAllocationStrategy strategy;
    String strategySource;
    var stored = policy(skuId);
    boolean policyEnabled = stored.enabled();

    if (request.strategy() != null && !request.strategy().isBlank()) {
      strategy = ExpiryAllocationStrategy.parse(request.strategy());
      strategySource = "REQUEST";
      if (!policyEnabled) {
        notes.add("本次请求显式指定了策略，覆盖该 SKU 当前「未启用」的配置状态。");
      }
    } else if (policyEnabled) {
      strategy = ExpiryAllocationStrategy.parse(stored.strategy());
      strategySource = "POLICY";
    } else {
      strategy = null;
      strategySource = stored.configured() ? "POLICY" : "DEFAULT";
      notes.add(
          stored.configured()
              ? "该 SKU 的效期分配策略未启用（enabled=false）：出库退回按库位顺序取可用量，不做效期排序。"
              : "该 SKU 没有配置效期分配策略：出库退回按库位顺序取可用量，不做效期排序。");
    }

    UUID specifiedLotId =
        request.specifiedLotId() != null ? request.specifiedLotId() : stored.specifiedLotId();
    if (strategy == ExpiryAllocationStrategy.SPECIFIED_LOT) {
      if (specifiedLotId == null) {
        throw new IllegalArgumentException("指定批次策略必须给出 specifiedLotId（要从哪一批出）");
      }
      requireLotOfSku(skuId, specifiedLotId);
    }

    // ---------------------------------------------------------------- 取候选 bucket
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    where.add("b.sku_id=?");
    binds.add(skuId);
    where.add("b.warehouse_id=?");
    binds.add(warehouseId);
    where.add("b.available>0");
    if (strategy == ExpiryAllocationStrategy.SPECIFIED_LOT) {
      where.add("l.id=?");
      binds.add(specifiedLotId);
    }

    var candidates =
        sql.fetch(
            CANDIDATE_SELECT
                + " from stock_bucket b"
                + " join warehouse_location loc on loc.tenant_id=b.tenant_id and loc.id=b.location_id"
                + " join inventory_quality_status q on q.tenant_id=b.tenant_id and q.id=b.quality_status_id"
                + " join warehouse w on w.tenant_id=b.tenant_id and w.id=b.warehouse_id"
                + " left join inventory_lot l on l.tenant_id=b.tenant_id and l.id=b.lot_id"
                + " where "
                + String.join(" and ", where)
                + orderBy(strategy),
            binds.toArray());

    // ---------------------------------------------------------------- 分类 + 走量
    var expired = BigDecimal.ZERO;
    var noExpiry = BigDecimal.ZERO;
    var nonSellable = BigDecimal.ZERO;
    var nonPickable = BigDecimal.ZERO;
    var noLot = BigDecimal.ZERO;
    var skuAvailable = BigDecimal.ZERO;

    // 按分配顺序累加：LinkedHashMap 的插入序就是「该批次第几个被取用」。
    Map<UUID, List<Pick>> lotBuckets = new LinkedHashMap<>();
    List<Pick> ungrouped = new ArrayList<>();
    var remaining = request.quantity();

    for (Record r : candidates) {
      var available = decimal(r, "available");
      skuAvailable = skuAvailable.add(available);

      boolean sellable = "SELLABLE".equals(r.get("quality_role", String.class));
      boolean pickable = Boolean.TRUE.equals(r.get("pickable", Boolean.class));
      boolean expiredLot = Boolean.TRUE.equals(r.get("is_expired", Boolean.class));
      boolean noExpiryLot = r.get("expires_on", LocalDate.class) == null;
      UUID lotId = r.get("lot_id", UUID.class);

      // 分类顺序即「先判与效期无关的硬约束」：非可售/不可拣与效期无关，先排除掉，
      // 否则会把待检货算进「过期量」，让处置方向整个跑偏。
      if (!sellable) {
        nonSellable = nonSellable.add(available);
        continue;
      }
      if (!pickable) {
        nonPickable = nonPickable.add(available);
        continue;
      }
      if (expiredLot) {
        expired = expired.add(available);
        continue;
      }
      // 策略关着时不要求批次；启用了 FEFO/FIFO 时，没有批次的货无法参与排序与追溯。
      if (strategy != null && lotId == null) {
        noLot = noLot.add(available);
        continue;
      }
      // FEFO 按失效日期排序：**没有失效日期的批次不参与排序**。
      // 它不是「最晚到期」，而是「不适用」—— 混进排序会让「先到期先出」这个承诺失去意义。
      if (strategy == ExpiryAllocationStrategy.FEFO && noExpiryLot) {
        noExpiry = noExpiry.add(available);
        continue;
      }
      if (remaining.signum() <= 0) {
        continue;
      }
      var take = available.min(remaining);
      var pick = new Pick(r, take);
      if (lotId == null) {
        ungrouped.add(pick);
      } else {
        lotBuckets.computeIfAbsent(lotId, k -> new ArrayList<>()).add(pick);
      }
      remaining = remaining.subtract(take);
    }

    var allocated = request.quantity().subtract(remaining);
    var planLines = new ArrayList<FefoAllocationLine>();
    LocalDate earliest = null;
    for (var picks : lotBuckets.values()) {
      var line = allocationLine(picks.get(0), picks, strategy);
      planLines.add(line);
      if (line.expiresOn() != null && (earliest == null || line.expiresOn().isBefore(earliest))) {
        earliest = line.expiresOn();
      }
    }
    if (!ungrouped.isEmpty()) {
      // 策略关着时可能取到无批次货：单列一行，批号留空而不是编一个。
      planLines.add(
          new FefoAllocationLine(
              null,
              null,
              null,
              null,
              null,
              null,
              "库位顺序",
              null,
              sumTake(ungrouped),
              ungrouped.size(),
              bucketLines(ungrouped)));
    }

    if (remaining.signum() > 0) {
      notes.add(
          "可分配量不足，缺口 " + remaining.toPlainString() + "：缺口来源见 ineligible 各分项（过期库存需先走 6.4.2 临期品处理）。");
    }
    if (strategy == ExpiryAllocationStrategy.FEFO && noExpiry.signum() > 0) {
      notes.add("该 SKU 有 " + noExpiry.toPlainString() + " 无失效日期的可售量未参与 FEFO 排序。");
    }
    if (strategy == ExpiryAllocationStrategy.SPECIFIED_LOT && planLines.isEmpty()) {
      notes.add("指定批次在指定仓没有可售可拣的可用量。");
    }

    return new FefoAllocationPlan(
        sku.get("id", UUID.class),
        sku.get("sku_code", String.class),
        sku.get("sku_name", String.class),
        warehouseId,
        sku.get("warehouse_code", String.class),
        strategy == null ? "NONE" : strategy.name(),
        strategy == null ? "未启用策略（按库位顺序）" : strategy.label(),
        policyEnabled,
        strategySource,
        request.quantity(),
        allocated,
        remaining,
        skuAvailable,
        earliest,
        planLines.size(),
        planLines,
        new FefoAllocationIneligible(expired, noExpiry, nonSellable, nonPickable, noLot),
        notes);
  }

  /**
   * 候选 bucket 投影。
   *
   * <p>刻意把 {@code available} 一起取出来而不是自己算 {@code on_hand - reserved}： {@code available} 是 V2
   * 的生成列，值只有一个来源。
   *
   * <p>{@code is_expired} 与 {@code days_to_expiry} 都在 SQL 侧算（基准是数据库当天）： 日期判定必须与建档兜底、7.3.1.1
   * 的过期判定同源，否则同一条数据会出现两个答案。
   */
  private static final String CANDIDATE_SELECT =
      """
      select b.id as bucket_id,b.lot_id,l.lot_code,l.produced_on,l.expires_on,l.received_on,\
       b.warehouse_id,w.code as warehouse_code,b.location_id,loc.code as location_code,\
       loc.name as location_name,loc.pickable,b.quality_status_id,\
       q.code as quality_status_code,q.role as quality_role,\
       b.on_hand,b.reserved,b.available,b.created_at,\
       (l.expires_on is not null and l.expires_on<current_date) as is_expired,\
       case when l.expires_on is null then null else (l.expires_on-current_date) end\
         as days_to_expiry\
      """;

  /**
   * 策略对应的 ORDER BY —— **FEFO 与 FIFO 的差别全在这几行里**。
   *
   * <ul>
   *   <li>FEFO：{@code expires_on} 升序（先到期先出）。失效日期为空的排最后（随后被 ineligible 收走）。
   *   <li>FIFO：{@code coalesce(received_on, created_at::date)} 升序（先进先出）， 同日入库再按 bucket
   *       建行时间细分。**刻意不用 {@code last_movement_at}**： 它记录「最近一次变动的业务时间」，每动一次就前移，拿它排序等于按「最近被碰过的先后」出库。
   *   <li>SPECIFIED_LOT：只有一个批次，排序退化为按库位取（同一批次跨库位时的取用顺序）。
   *   <li>策略关着（null）：纯库位顺序，不做任何效期排序。
   * </ul>
   *
   * <p>决胜键恒定追加 {@code b.id}：同键多行时顺序确定，重复请求得到同一份推荐。
   */
  private static String orderBy(ExpiryAllocationStrategy strategy) {
    if (strategy == null) {
      return " order by w.code,loc.code,b.id";
    }
    return switch (strategy) {
      case FEFO -> " order by l.expires_on nulls last,l.lot_code,b.id";
      case FIFO ->
          " order by coalesce(l.received_on,b.created_at::date) nulls last,b.created_at,l.lot_code,b.id";
      case SPECIFIED_LOT -> " order by w.code,loc.code,b.id";
    };
  }

  /**
   * 一次取用：来源 bucket 行 + 从它取的量。
   *
   * <p>刻意用一个显式的小 record 而不是往 jOOQ 的 {@code Record} 上加列 —— {@code with(String, Object)} 这类重载不存在（只有
   * {@code with(Field<T>, T)}）， 而 {@code Record} 一旦被 {@code with(Field,...)} 改写就成了一份副本，混着用反而更难读。
   */
  private record Pick(Record row, BigDecimal take) {}

  private static BigDecimal sumTake(List<Pick> picks) {
    var sum = BigDecimal.ZERO;
    for (var p : picks) sum = sum.add(p.take());
    return sum;
  }

  /**
   * 把一次取用折成一行推荐。
   *
   * <p>{@code sortBasis} / {@code sortBasisValue} 报的是**本次实际用的排序键**， 由策略决定而不是由「这个批次有没有失效日期」决定：FIFO
   * 下同样是有失效日期的批次， 排的却是入库时间，标成「失效日期」会让人以为系统排错了。
   */
  private static FefoAllocationLine allocationLine(
      Pick head, List<Pick> picks, ExpiryAllocationStrategy strategy) {
    Record r = head.row();
    var expiresOn = r.get("expires_on", LocalDate.class);
    var receivedOn = r.get("received_on", LocalDate.class);
    String basis;
    String basisValue;
    if (strategy == ExpiryAllocationStrategy.FEFO) {
      basis = "失效日期";
      basisValue = expiresOn == null ? null : expiresOn.toString();
    } else if (strategy == ExpiryAllocationStrategy.FIFO) {
      basis = "入库时间";
      // FIFO 兜底口径：没有收货日期就用 bucket 建行日期，与 orderBy 的 coalesce 同源。
      basisValue =
          receivedOn != null
              ? receivedOn.toString()
              : r.get("created_at", OffsetDateTime.class).toLocalDate().toString();
    } else if (strategy == ExpiryAllocationStrategy.SPECIFIED_LOT) {
      basis = "指定批次";
      basisValue = r.get("lot_code", String.class);
    } else {
      basis = "库位顺序";
      basisValue = null;
    }
    return new FefoAllocationLine(
        r.get("lot_id", UUID.class),
        r.get("lot_code", String.class),
        r.get("produced_on", LocalDate.class),
        expiresOn,
        receivedOn,
        intOrNull(r, "days_to_expiry"),
        basis,
        basisValue,
        sumTake(picks),
        picks.size(),
        bucketLines(picks));
  }

  private static List<FefoAllocationBucketLine> bucketLines(List<Pick> picks) {
    var out = new ArrayList<FefoAllocationBucketLine>();
    for (var p : picks) {
      Record r = p.row();
      out.add(
          new FefoAllocationBucketLine(
              r.get("bucket_id", UUID.class),
              r.get("location_id", UUID.class),
              r.get("location_code", String.class),
              r.get("location_name", String.class),
              r.get("warehouse_code", String.class),
              r.get("quality_status_code", String.class),
              p.take(),
              decimal(r, "available")));
    }
    return out;
  }

  // ================================================================ 效期预警

  /**
   * 效期预警清单（临期 / 过期 / 非效期品）。
   *
   * <p>提前天数是**逐 SKU 取的**：{@code coalesce(本次覆盖值, 该 SKU 策略的 lead_days, 默认 30)}。 所以同一个租户里不同 SKU
   * 的临期口径可以不同，而 {@code leadDaysSource} 会如实标明这一行用的是哪一个。
   *
   * <p><b>汇总不受 expiryStatus 档位筛选影响</b>（其余筛选生效）：默认只看临期/过期两档时， 若汇总也跟着收窄，{@code healthyLotCount} 就会恒为
   * 0 —— 一个恒为 0 的字段等于没有这个字段。
   */
  public ExpiryAlertPage alerts(ExpiryAlertQuery query) {
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size() == null ? 50 : query.size()));
    int pageNumber = Math.max(0, query.page() == null ? 0 : query.page());

    Integer override = query.leadDays();
    if (override != null && (override < 0 || override > MAX_LEAD_DAYS)) {
      throw new IllegalArgumentException(
          "预警提前天数必须在 0 ~ " + MAX_LEAD_DAYS + " 之间（当前 " + override + "）");
    }

    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    alertConditions(query, where, binds);
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    // 提前天数的生效顺序是「本次覆盖 > 该 SKU 策略 > 默认值」，写成
    // coalesce(nullif(?,?), p.lead_days, ?) —— 覆盖值**永远不为 null**，
    // 直接 coalesce 会让它恒取第一个非空参数，于是 p.lead_days 变成永远走不到的死代码；
    // 而这个 bug 的表现只是「配了 7 天却按 30 天算」，不看返回值根本发现不了。
    // nullif 两个参数 + coalesce 两个默认值，所以这里有**三个**占位符，
    // 三个值全部从下面这同一个数组派生，不手数（手数正是「错位一格」的来源）。
    Object[] leadBind = {override == null ? NO_OVERRIDE : override, NO_OVERRIDE, DEFAULT_LEAD_DAYS};
    String cte = alertCte(sqlWhere);

    var statuses = statusesOf(query);
    String statusWhere =
        statuses.isEmpty() ? "" : " where expiry_status in (" + placeholders(statuses.size()) + ")";

    long total =
        sql.fetchOne(
                cte + " select count(*) from scoped" + statusWhere,
                concat(leadBind, binds.toArray(), statuses.toArray()))
            .get(0, Long.class);

    var rows =
        sql.fetch(
            cte
                + " select lot_id,sku_id,sku_code,sku_name,lot_code,produced_on,expires_on,received_on,"
                + " expiry_status,days_to_expiry,effective_lead_days,has_policy,"
                + " lot_status,origin,on_hand,reserved,available,warehouse_count,notes,updated_at"
                + " from scoped"
                + statusWhere
                + alertOrder(query)
                + " limit ? offset ?",
            concat(
                leadBind,
                binds.toArray(),
                statuses.toArray(),
                new Object[] {size, (long) pageNumber * size}));

    String leadNote =
        override != null
            ? "本次统一按 " + override + " 天窗口计算（临时覆盖，未改动各 SKU 的策略）。"
            : "逐 SKU 按各自策略的提前天数计算；没有配置策略的 SKU 用默认值 " + DEFAULT_LEAD_DAYS + " 天。";

    return new ExpiryAlertPage(
        alertSummary(cte, leadBind, binds.toArray()),
        rows.stream().map(InventoryExpiryService::alertRow).toList(),
        statusOptions(),
        override,
        leadNote,
        total,
        pageNumber,
        size);
  }

  /**
   * 效期预警的两级 CTE。
   *
   * <p><b>为什么分两级</b>：{@code expiry_status} 要用同层的 {@code days_to_expiry} 与 {@code
   * effective_lead_days}，而 PG 的 SELECT 列表里引用不到刚定义的别名。 把「算天数与阈值」放进 {@code base}，把「按它们算档位」放进 {@code
   * scoped}， 绑定值就只在 {@code base} 出现一次 —— 重复写一遍 {@code coalesce(...)} 就得多绑一次，
   * 提前天数一改就极易错位一格，而错位的表现是「筛选结果莫名其妙少一批」。
   *
   * <p>返回的是完整的 {@code WITH} 子句，**必须拼在每条语句最前面** —— {@code select count(*) with x as ...} 不是合法
   * SQL，而这种拼接顺序错误的表现是 驱动报「bad SQL grammar」，看起来像 SQL 写错了，根因却只是顺序。
   *
   * <p>两级的天数表达式与档位阈值必须同源：天数算出 N、档位用另一个阈值判， 就会出现「天数是临期但档位是正常」这种自相矛盾的行。
   */
  private static String alertCte(String sqlWhere) {
    return " with base as (select l.id as lot_id,l.sku_id,s.code as sku_code,s.name as sku_name,"
        + " l.lot_code,l.produced_on,l.expires_on,l.received_on,l.status as lot_status,"
        + " l.origin,l.notes,l.updated_at,"
        + " coalesce(agg.on_hand,0) as on_hand,coalesce(agg.reserved,0) as reserved,"
        + " coalesce(agg.available,0) as available,coalesce(agg.warehouse_count,0) as warehouse_count,"
        + " coalesce(nullif(?,?),p.lead_days,?) as effective_lead_days,(p.id is not null) as has_policy,"
        + " case when l.expires_on is null then null else (l.expires_on-current_date) end"
        + "   as days_to_expiry"
        + " from inventory_lot l"
        + " join sku_ref s on s.tenant_id=l.tenant_id and s.id=l.sku_id"
        + " left join lateral (select sum(b.on_hand) as on_hand,sum(b.reserved) as reserved,"
        + "   sum(b.available) as available,count(distinct b.warehouse_id) as warehouse_count"
        + "   from stock_bucket b where b.tenant_id=l.tenant_id and b.lot_id=l.id) agg on true"
        + " left join inventory_expiry_policy p on p.tenant_id=l.tenant_id and p.sku_id=l.sku_id"
        + sqlWhere
        + "), scoped as (select base.*,"
        + " case when expires_on is null then 'NO_EXPIRY'"
        + " when days_to_expiry<0 then 'EXPIRED'"
        + " when days_to_expiry<=effective_lead_days then 'EXPIRING'"
        + " else 'HEALTHY' end as expiry_status from base)";
  }

  /**
   * 预警清单的 WHERE 条件。
   *
   * <p>{@code expiryStatus} 档位筛选**不在这里**（它作用在外层，汇总不受它影响）。
   *
   * <p>{@code inStock} 默认 true：零库存批次发不出货，占着预警位是噪声。 显式传 {@code false} 时不看在手条件（此时清单退化成「本租户所有批次」）。
   */
  private void alertConditions(ExpiryAlertQuery query, List<String> where, List<Object> binds) {
    if (query.skuId() != null) {
      where.add("l.sku_id=?");
      binds.add(query.skuId());
    }
    if (query.warehouseId() != null) {
      where.add(
          "exists (select 1 from stock_bucket wb where wb.tenant_id=l.tenant_id"
              + " and wb.lot_id=l.id and wb.warehouse_id=? and wb.on_hand>0)");
      binds.add(query.warehouseId());
    }
    if (Boolean.FALSE.equals(query.inStock())) {
      return; // 显式要求不过滤在手，此时不再追加关键字条件之外的库存约束
    }
    where.add(
        "exists (select 1 from stock_bucket sb where sb.tenant_id=l.tenant_id"
            + " and sb.lot_id=l.id and sb.on_hand>0)");
    if (query.keyword() != null && !query.keyword().isBlank()) {
      where.add("(l.lot_code ilike ? or s.code ilike ? or s.name ilike ?)");
      var k = "%" + query.keyword().trim() + "%";
      for (int i = 0; i < 3; i++) binds.add(k);
    }
  }

  /**
   * 档位筛选：不传只看「需要处理」的两档。
   *
   * <p>预警清单的用途是「找出待办的货」，把 HEALTHY 也塞进来会让清单长到没法用 —— 一个几千行的清单里没有一行是需要动手的。
   */
  private static List<String> statusesOf(ExpiryAlertQuery query) {
    if (query.expiryStatus() == null || query.expiryStatus().isEmpty()) {
      return DEFAULT_STATUSES;
    }
    var codes = new ArrayList<String>();
    for (var raw : query.expiryStatus()) {
      if (raw == null || raw.isBlank()) continue;
      var code = raw.trim().toUpperCase(Locale.ROOT);
      if (!STATUSES.contains(code)) {
        throw new IllegalArgumentException("未知的效期档位：" + raw + "，可选：" + String.join("、", STATUSES));
      }
      if (!codes.contains(code)) codes.add(code);
    }
    return codes;
  }

  private String alertOrder(ExpiryAlertQuery query) {
    String sort = query.sort() == null ? "EXPIRY" : query.sort().toUpperCase(Locale.ROOT);
    if (!ALERT_SORTS.contains(sort)) {
      throw new IllegalArgumentException(
          "不支持的排序键：" + query.sort() + "，可选：" + String.join("、", ALERT_SORTS));
    }
    return switch (sort) {
        // 紧急度优先：剩余天数升序 —— 已过期的（负数）排在最前，临期的紧随其后。
      case "EXPIRY" -> " order by days_to_expiry nulls last,lot_code,lot_id";
      case "ON_HAND" -> " order by on_hand desc,days_to_expiry nulls last,lot_id";
      case "RECEIVED_ON" -> " order by received_on desc nulls last,lot_id";
      case "UPDATED_AT" -> " order by updated_at desc,lot_id";
      default -> " order by sku_code,lot_code,lot_id";
    };
  }

  /**
   * 效期预警汇总。
   *
   * <p>数量与在手量都**按档位拆开**报：过期走 6.4.2 临期品处理、临期走促销或调拨， 处置路径完全不同，合成一个数就没法派工。
   *
   * <p>{@code skuCount} 单独查一次而不按档位加总：一个同时有临期与过期批次的 SKU 在两个档位里各数一次，加起来会把它算成两个 SKU。
   */
  private ExpiryAlertSummary alertSummary(String cte, Object[] leadBind, Object[] whereBinds) {
    var byStatus =
        sql
            .fetch(
                cte
                    + " select expiry_status,count(*) as lot_count,sum(on_hand) as on_hand,"
                    + " min(expires_on) as min_exp,max(expires_on) as max_exp"
                    + " from scoped"
                    + " group by expiry_status",
                concat(leadBind, whereBinds))
            .stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    r -> r.get("expiry_status", String.class),
                    r -> r,
                    (a, b) -> a,
                    LinkedHashMap::new));

    Record overall =
        sql.fetchOne(
            cte
                + " select count(distinct sku_id) as sku_count,"
                + " count(distinct effective_lead_days) as lead_days_count,"
                + " min(expires_on) as min_exp,max(expires_on) as max_exp"
                + " from scoped"
                + " where expires_on is not null",
            concat(leadBind, whereBinds));

    long lotCount = 0, expired = 0, expiring = 0, healthy = 0, noExpiry = 0;
    var onHand = BigDecimal.ZERO;
    var expiredOnHand = BigDecimal.ZERO;
    var expiringOnHand = BigDecimal.ZERO;
    LocalDate earliest = null;
    for (var entry : byStatus.entrySet()) {
      Record r = entry.getValue();
      long n = r.get("lot_count", Long.class);
      var qty = decimal(r, "on_hand");
      lotCount += n;
      onHand = onHand.add(qty);
      var minExp = r.get("min_exp", LocalDate.class);
      if (minExp != null && (earliest == null || minExp.isBefore(earliest))) earliest = minExp;
      switch (entry.getKey()) {
        case "EXPIRED" -> {
          expired = n;
          expiredOnHand = qty;
        }
        case "EXPIRING" -> {
          expiring = n;
          expiringOnHand = qty;
        }
        case "HEALTHY" -> healthy = n;
        default -> noExpiry = n;
      }
    }
    return new ExpiryAlertSummary(
        lotCount,
        overall.get("sku_count", Long.class),
        expired,
        expiring,
        healthy,
        noExpiry,
        onHand,
        expiredOnHand,
        expiringOnHand,
        overall.get("lead_days_count", Long.class),
        earliest,
        overall.get("max_exp", LocalDate.class));
  }

  /**
   * 效期档位下拉项（带命中批次数）。
   *
   * <p>用**兜底阈值**而不是各 SKU 的策略值来数：下拉是「这个租户的效期盘子有多大」， 一个随 SKU 配置跳动的计数在筛选器里只会让人以为数错了。
   */
  private List<ExpiryStatusOption> statusOptions() {
    // 刻意**不 join 策略表**，恒用兜底阈值：一个随各 SKU 配置跳动的下拉计数，
    // 在筛选器里只会让人以为数错了。下拉要回答的是「这个租户的效期盘子有多大」。
    String cte =
        " with scoped as (select case when l.expires_on is null then 'NO_EXPIRY'"
            + " when l.expires_on<current_date then 'EXPIRED'"
            + " when l.expires_on-current_date<=? then 'EXPIRING'"
            + " else 'HEALTHY' end as expiry_status from inventory_lot l)";
    return sql.fetch(
            cte
                + " select expiry_status as value,case expiry_status when 'EXPIRED' then '已过期'"
                + " when 'EXPIRING' then '临期' when 'HEALTHY' then '正常' else '非效期品' end as label,"
                + " count(*) as lot_count from scoped group by expiry_status order by expiry_status",
            new Object[] {DEFAULT_LEAD_DAYS})
        .map(
            r ->
                new ExpiryStatusOption(
                    r.get("value", String.class),
                    r.get("label", String.class),
                    r.get("lot_count", Long.class)));
  }

  // ================================================================ 下拉参照数据

  /** 仓库清单。分配推荐限定单仓，所以给仓库下拉。 */
  public List<ExpiryWarehouseOption> warehouses() {
    return sql.fetch("select w.id,w.code,w.name from warehouse w order by w.code").stream()
        .map(
            r ->
                new ExpiryWarehouseOption(
                    r.get("id", UUID.class),
                    r.get("code", String.class),
                    r.get("name", String.class)))
        .toList();
  }

  /** 某 SKU 的批次下拉（指定批次策略用）。按失效日期升序：先到期的排前面。 */
  public List<ExpiryLotOption> lots(UUID skuId) {
    return sql
        .fetch(
            "select l.id,l.lot_code,l.expires_on from inventory_lot l where l.sku_id=?"
                + " order by l.expires_on nulls last,l.lot_code,l.id",
            skuId)
        .stream()
        .map(
            r ->
                new ExpiryLotOption(
                    r.get("id", UUID.class),
                    r.get("lot_code", String.class),
                    r.get("expires_on", LocalDate.class)))
        .toList();
  }

  // ================================================================ 条件与工具

  /** 多选枚举条件：非法码当场 400，不静默忽略（与 7.3.1.1 同一纪律）。 */
  private void addEnumIn(
      List<String> where,
      List<Object> binds,
      String column,
      List<String> values,
      List<String> allowed,
      String label) {
    if (values == null || values.isEmpty()) {
      return;
    }
    var codes = new ArrayList<String>();
    for (var raw : values) {
      if (raw == null || raw.isBlank()) {
        continue;
      }
      var code = raw.trim().toUpperCase(Locale.ROOT);
      if (!allowed.contains(code)) {
        throw new IllegalArgumentException(
            "未知的" + label + "：" + raw + "，可选：" + String.join("、", allowed));
      }
      if (!codes.contains(code)) {
        codes.add(code);
      }
    }
    if (!codes.isEmpty()) {
      where.add(column + " in (" + placeholders(codes.size()) + ")");
      binds.addAll(codes);
    }
  }

  private static ExpiryPolicyRow policyRow(Record r) {
    var strategy = ExpiryAllocationStrategy.parse(r.get("strategy", String.class));
    return new ExpiryPolicyRow(
        r.get("policy_id", UUID.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        strategy.name(),
        strategy.label(),
        Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
        intOrNull(r, "lead_days"),
        r.get("specified_lot_id", UUID.class),
        r.get("specified_lot_code", String.class),
        decimal(r, "on_hand"),
        r.get("lot_count", Long.class),
        r.get("expiring_lot_count", Long.class),
        r.get("expired_lot_count", Long.class),
        r.get("notes", String.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private static ExpiryAlertRow alertRow(Record r) {
    var status = ExpiryStatus.valueOf(r.get("expiry_status", String.class));
    boolean hasPolicy = Boolean.TRUE.equals(r.get("has_policy", Boolean.class));
    return new ExpiryAlertRow(
        r.get("lot_id", UUID.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("lot_code", String.class),
        r.get("produced_on", LocalDate.class),
        r.get("expires_on", LocalDate.class),
        r.get("received_on", LocalDate.class),
        status.name(),
        status.label(),
        intOrNull(r, "days_to_expiry"),
        intOrNull(r, "effective_lead_days"),
        hasPolicy ? "POLICY" : "DEFAULT",
        r.get("lot_status", String.class),
        r.get("origin", String.class),
        decimal(r, "on_hand"),
        decimal(r, "reserved"),
        decimal(r, "available"),
        r.get("warehouse_count", Long.class),
        r.get("notes", String.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private static BigDecimal decimal(Record r, String field) {
    var v = r.get(field, BigDecimal.class);
    return v == null ? BigDecimal.ZERO : v;
  }

  /**
   * 取整数列。
   *
   * <p>{@code expires_on - current_date} 与 {@code count(*)} 在 PG 里都是 integer， 但**纯文本语句里 jOOQ
   * 拿不到列类型**，直接 {@code r.get(field, Integer.class)} 会因类型不匹配而返回 null —— 于是每一行都退化成「没有效期 / 天数是空」，
   * 而这恰恰是最该显示出来的那两列。这里收口成不受驱动返回类型影响的读法。
   */
  private static Integer intOrNull(Record r, String field) {
    var value = r.get(field);
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number.intValue();
    }
    return Integer.valueOf(value.toString());
  }

  private static String placeholders(int count) {
    return String.join(",", java.util.Collections.nCopies(count, "?"));
  }

  /** 绑定序列拼接。**所有绑定都必须经过这里**，避免在别处手拼数组导致错位一格。 */
  private static Object[] concat(Object[]... parts) {
    return Arrays.stream(parts).flatMap(Arrays::stream).toArray();
  }

  private static List<Object> append(List<Object> binds, Object... more) {
    var all = new ArrayList<>(binds);
    all.addAll(Arrays.asList(more));
    return all;
  }
}
