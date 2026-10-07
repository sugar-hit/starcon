package com.starcon.warehouse;

import static com.starcon.warehouse.SellablePolicyModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 7.2.2.1 可售量口径：渠道/店铺维度的库存口径配置、生效值解析、共享池超卖体检与可售量计算。
 *
 * <p>蓝图把它定位成「平台库存回写缓冲是防超卖的最后一道防线」，所以本服务真正的产出不是那几行
 * 配置，而是 {@link PoolSafetyVerdict} —— 04 回写与 7.1.1.3 把可售数写给平台之前必须先过的那道体检。
 *
 * <p><b>三条实现纪律</b>：
 *
 * <ul>
 *   <li><b>区间 + 不可变</b>：口径内容写定后不可改（V56 触发器），改口径 = 收口旧档 + 开新档。
 *       历史因此天然完整，不需要第二张「变更历史」表 —— 已关区间的那些行就是历史
 *       （与 17.5.1.1 的 {@code effective_from} 同一套约定）。
 *   <li><b>时间绑定</b>：{@code ?::timestamptz} 集中在最前面的 {@code win} CTE 里，
 *       后面全是普通绑定，cast 永远不在绑定序列末位（与 7.1.2.2 同一条纪律）。
 *   <li><b>数量一律 numeric(19,4) + BigDecimal</b>，比较一律 {@code compareTo} 不用 {@code equals}
 *       （后者把 0 与 0.0000 判成不等）。
 * </ul>
 *
 * <p><b>池容量为什么聚合 {@code inventory_balance} 而不是 {@code stock_bucket}</b>：V1 把 balance
 * 定为「权威可用量」，V2 用约束保证「存在 bucket 时 Σbucket == balance」。而「还没拆到库位」的
 * balance 行（7.1.1.2 里的未分解）是合法状态，只聚合 bucket 会把它们漏成 0 —— 那是少卖不是超卖，
 * 但仍然是错的数。按 balance 聚合是全集，两者在有 bucket 的地方数值完全相同。
 */
@Service
public class SellablePolicyService {

  /** 单页最大行数：口径是配置不是流水，量级很小，但仍设上限防止一次拉爆 JVM。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 一次最多算多少个 SKU（与 InventoryAvailabilityService 同一套上限与理由）。 */
  public static final int MAX_SKUS = 500;

  /** 差异为 0 的量在 numeric(19,4) 口径下的零，避免同一列里混着 scale 不一样的 0。 */
  private static final BigDecimal ZERO = new BigDecimal("0.0000");

  private static final BigDecimal ONE = BigDecimal.ONE;

  /** 排序权重：越危险的排越前，名册打开第一眼就是最该改的那个。 */
  private static int rank(PoolSafetyVerdict v) {
    return switch (v) {
      case OVERSOLD -> 0;
      case NO_HEADROOM -> 1;
      case TOLERATED -> 2;
      case GUARDED -> 3;
    };
  }

  /** IN 列表占位符，每个都带 {@code ::uuid}：jOOQ 按 varchar 送 UUID，单元素列表 PG 反推不出类型。 */
  private static String placeholders(int n) {
    return "(" + String.join(",", java.util.Collections.nCopies(n, "?::uuid")) + ")";
  }

  /** 生效时刻绑定。两个 {@code ?::timestamptz} 刻意放在整条语句最前面，理由见类注释。 */
  private static final String WIN = "with win as (select ?::timestamptz as at)";

  /** 分档的固定 SELECT 列。 */
  private static final String COLUMNS =
      "p.id,p.scope_type,p.source_system,p.reference_id,p.reference_label,"
          + "p.pool_mode,p.pool_key,p.oversell_allowed,p.oversell_tolerance,"
          + "p.platform_stock_scope,p.buffer_ratio,p.include_in_transit,"
          + "p.effective_from,p.effective_to,p.note,p.created_by_membership_id,p.created_at";

  /** 生效判定谓词 —— 「生效」在本模块只有这一个定义。 */
  private static final String LIVE_AT =
      "(p.effective_from <= win.at and (p.effective_to is null or p.effective_to > win.at))";

  /**
   * 分档写入的列与每列的绑定 cast，**由同一个数组生成 SQL**。
   *
   * <p>刻意不手写两串 {@code "?,?,?,?::numeric,..."}：列顺序与 cast 顺序各写一遍时，
   * 少一个或错开一位就会让整条语句绑定错位，而 PG 报的是
   * 「invalid input syntax for type numeric」「cannot cast type boolean to numeric」
   * 或「未设定参数值 N」—— 没有一条指向真正的原因（列顺序），极难一眼看出。
   * 由数组生成后，cast 与列天然一一对应，长度不一致在拼 SQL 时就炸。
   */
  private static final String[] POLICY_COLUMNS = {
    "id", "tenant_id", "scope_type", "source_system", "reference_id", "reference_label",
    "pool_mode", "pool_key", "oversell_allowed", "oversell_tolerance", "platform_stock_scope",
    "buffer_ratio", "include_in_transit", "effective_from", "note", "created_by_membership_id"
  };

  /** 与 {@link #POLICY_COLUMNS} 一一对应：空串 = 普通绑定，其余是 PG 侧的 cast。 */
  private static final String[] POLICY_CASTS = {
    "", "", "", "", "", "", "", "", "", "::numeric", "", "::numeric", "", "::timestamptz", "", ""
  };

  /** {@code timestamptz} 的 cast 落在倒数第三列而非末位（与 7.1.2.2 同一条纪律）。 */
  private static String policyInsert() {
    if (POLICY_CASTS.length != POLICY_COLUMNS.length) {
      throw new IllegalStateException(
          "分档写入的列与 cast 数量不一致：" + POLICY_COLUMNS.length + " vs " + POLICY_CASTS.length);
    }
    StringJoiner columns = new StringJoiner(",");
    StringJoiner values = new StringJoiner(",");
    for (int i = 0; i < POLICY_COLUMNS.length; i++) {
      columns.add(POLICY_COLUMNS[i]);
      values.add("?" + POLICY_CASTS[i]);
    }
    return "insert into sellable_policy (" + columns + ") values (" + values + ")";
  }

  private final DSLContext sql;

  public SellablePolicyService(DSLContext sql) {
    this.sql = sql;
  }

  // ============================================================ 分档列表与详情

  /** 口径分档列表（真分页，total 与分页无关）。 */
  public SellablePolicyPage page(SellablePolicyQuery query) {
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size() <= 0 ? 50 : query.size()));
    int pageNo = Math.max(0, query.page());
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    conditions(query, where, binds);
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    long total =
        sql.fetchOne("select count(*) from sellable_policy p" + sqlWhere, binds.toArray()).get(0, Long.class);

    var rows =
        sql.fetch(
            "select " + COLUMNS + " from sellable_policy p" + sqlWhere + order(query) + " limit ? offset ?",
            append(binds, size, (long) pageNo * size).toArray());

    return new SellablePolicyPage(
        rows.stream().map(SellablePolicyService::row).toList(), total, pageNo, size);
  }

  private void conditions(SellablePolicyQuery query, List<String> where, List<Object> binds) {
    if (notBlank(query.scopeType())) {
      where.add("p.scope_type = ?");
      binds.add(query.scopeType().trim().toUpperCase(Locale.ROOT));
    }
    if (query.referenceId() != null) {
      where.add("p.reference_id = ?");
      binds.add(query.referenceId());
    }
    if (notBlank(query.poolMode())) {
      where.add("p.pool_mode = ?");
      binds.add(query.poolMode().trim().toUpperCase(Locale.ROOT));
    }
    if (query.effectiveOnly()) {
      // 「此刻仍在生效」由服务端用 now() 判定，不接受调用方传时刻：
      // 否则「看未来某一刻谁生效」会变成一个能试错的入口，而它在这里没有业务含义。
      where.add("(p.effective_from <= now() and (p.effective_to is null or p.effective_to > now()))");
    }
    if (notBlank(query.keyword())) {
      where.add("(p.reference_label ilike ? or p.pool_key ilike ? or p.note ilike ?)");
      String k = "%" + query.keyword().trim() + "%";
      binds.add(k);
      binds.add(k);
      binds.add(k);
    }
  }

  private String order(SellablePolicyQuery query) {
    // 决胜键恒定追加 p.id：同排序值时翻页不会错位或重复。
    return query.effectiveOnly()
        ? " order by p.scope_type, p.pool_key, p.effective_from desc, p.id"
        : " order by p.effective_from desc, p.id";
  }

  /** 口径详情 = 分档 + 它吃哪几个仓（池容量的构成单元）。跨租户在 RLS 下查不到 → 404。 */
  public SellablePolicyDetail detail(UUID id) {
    var r = sql.fetchOne("select " + COLUMNS + " from sellable_policy p where p.id = ?", id);
    if (r == null) {
      throw notFound(id);
    }
    return new SellablePolicyDetail(row(r), warehouses(id));
  }

  private List<SellablePolicyWarehouseLink> warehouses(UUID policyId) {
    return sql.fetch(
            "select lw.policy_id,lw.warehouse_id,w.code as warehouse_code,w.name as warehouse_name,"
                + "lw.warehouse_kind from sellable_policy_warehouse lw"
                + " join warehouse w on w.tenant_id = lw.tenant_id and w.id = lw.warehouse_id"
                + " where lw.policy_id = ? order by w.code",
            policyId)
        .map(
            r ->
                new SellablePolicyWarehouseLink(
                    r.get("policy_id", UUID.class),
                    r.get("warehouse_id", UUID.class),
                    r.get("warehouse_code", String.class),
                    r.get("warehouse_name", String.class),
                    WarehouseKind.valueOf(r.get("warehouse_kind", String.class))));
  }

  // ============================================================ 写入

  /**
   * 新建一档口径。{@code supersedeId} 非空时先收口那一档的区间再开新档（改口径的标准动作）。
   *
   * <p>跨服务边界：店铺/渠道在 channel 服务，本服务不建外键也不查它的表，只存
   * {@code source_system + reference_id} 与写入时的可读名快照（V56 文件头）。
   *
   * <p>逐仓声明 {@code warehouse_kind} 是「平台可售量按自有仓还是含 FBA」可计算的前提：
   * 不声明就无法在「仅自有仓」口径下把 FBA 量从池容量里排除出去。
   */
  @Transactional
  public SellablePolicySaved save(UUID supersedeId, SellablePolicyInput input, UUID actorId) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    PolicyScopeType scope = scope(input.scopeType());
    PoolMode poolMode = poolMode(input.poolMode());
    PlatformStockScope stockScope = stockScope(input.platformStockScope());

    String sourceSystem = input.sourceSystem() == null ? "" : input.sourceSystem().trim();
    UUID referenceId = input.referenceId();
    if (scope == PolicyScopeType.DEFAULT) {
      sourceSystem = "";
      referenceId = null;
    } else if (referenceId == null || sourceSystem.isEmpty()) {
      throw bad(
          scope.label()
              + "档必须同时给 sourceSystem 与 referenceId：它们是跨服务定位键"
              + "（店铺/渠道在 channel 服务，本服务不建外键也不查它的表），"
              + "少一个就会出现一档永远命中不了的口径");
    }
    if (poolMode == PoolMode.SHARED && scope == PolicyScopeType.DEFAULT) {
      throw bad("全租户兜底档不能设为共享池：兜底档没有「与谁共享」这个对象，"
          + "设了也解析不出成员集合，缓冲比例会算到一个空池子上");
    }

    BigDecimal bufferRatio = input.bufferRatio() == null ? ZERO : input.bufferRatio();
    if (bufferRatio.signum() < 0 || bufferRatio.compareTo(ONE) > 0) {
      throw bad("缓冲比例必须在 0 与 1 之间，收到 " + bufferRatio.toPlainString()
          + "：缓冲是防超卖的最后一道防线，越界等于没有防线");
    }
    BigDecimal tolerance = input.oversellTolerance() == null ? ZERO : input.oversellTolerance();
    if (tolerance.signum() < 0) {
      throw bad("超卖容忍不能为负数，收到 " + tolerance.toPlainString());
    }
    if (!input.oversellAllowed() && tolerance.signum() != 0) {
      throw bad("不允许超卖时超卖容忍必须是 0：留着额度却关掉开关，"
          + "解析出来的是「明明不许超卖却带着额度」的矛盾档");
    }

    String poolKey = resolvePoolKey(poolMode, input.poolKey(), referenceId);
    OffsetDateTime effectiveFrom =
        input.effectiveFrom() == null ? OffsetDateTime.now() : input.effectiveFrom();

    Map<UUID, WarehouseKind> wanted = bindings(input.warehouses());
    verifyWarehouses(wanted.keySet());

    // 换版：先把该槽位仍在生效的那一档收口，再开新档。顺序不能反 ——
    // 先插新档会被区间重叠触发器当场拒绝（那是正确的失败，但表达不出「顺带收口」这个意图）。
    UUID superseded = null;
    if (supersedeId != null) {
      var target = sql.fetchOne("select " + COLUMNS + " from sellable_policy p where p.id = ?", supersedeId);
      if (target == null) {
        throw notFound(supersedeId);
      }
      if (target.get("effective_to", OffsetDateTime.class) != null) {
        throw bad("这一档的区间已被收口（生效至 " + target.get("effective_to", OffsetDateTime.class)
            + "），不能作为换版对象：要换的是当前正在生效的那一档");
      }
      if (!sameSlot(target, scope, referenceId)) {
        throw bad("换版必须落在同一个范围槽位内（原档 "
            + PolicyScopeType.valueOf(target.get("scope_type", String.class)).label()
            + "，新档 " + scope.label() + "）：跨槽位改档会让原档被收口后无处继承，"
            + "等于悄悄作废了一个口径");
      }
      if (!effectiveFrom.isAfter(target.get("effective_from", OffsetDateTime.class))) {
        throw bad("新档的生效起点 " + effectiveFrom + " 必须晚于原档起点 "
            + target.get("effective_from", OffsetDateTime.class) + "：区间不前进就等于原地改口径");
      }
      sql.execute("update sellable_policy set effective_to = ?::timestamptz where id = ?", effectiveFrom, supersedeId);
      superseded = supersedeId;
    }

    UUID id = UUID.randomUUID();
    sql.execute(
        policyInsert(),
        id,
        tenant(),
        scope.name(),
        sourceSystem,
        referenceId,
        input.referenceLabel() == null ? "" : input.referenceLabel().trim(),
        poolMode.name(),
        poolKey,
        input.oversellAllowed(),
        tolerance,
        stockScope.name(),
        bufferRatio,
        input.includeInTransit(),
        effectiveFrom,
        input.note() == null ? "" : input.note().trim(),
        actorId);

    for (Map.Entry<UUID, WarehouseKind> entry : wanted.entrySet()) {
      sql.execute(
          "insert into sellable_policy_warehouse (tenant_id,policy_id,warehouse_id,warehouse_kind) "
              + "values (?,?,?,?)",
          tenant(),
          id,
          entry.getKey(),
          entry.getValue().name());
    }

    return new SellablePolicySaved(
        id,
        poolKey,
        effectiveFrom,
        null,
        superseded,
        superseded == null
            ? "已新建" + scope.label() + "口径：缓冲 " + percent(bufferRatio) + "，平台口径 "
                + stockScope.label() + "，超卖容忍 " + tolerance.toPlainString() + " 件。"
                + "该档按区间生效，历史各档原样保留。"
            : "已换版：原档的区间收到 " + effectiveFrom + " 为止，新档自 " + effectiveFrom + " 起生效。"
                + "两档并存，历史那一档仍是「当时真正生效的防线」的唯一凭据。");
  }

  /**
   * 作废一档（收口它的生效区间）。
   *
   * <p>刻意**不**提供 DELETE：作废是审计事件，档位行必须永久保留（V56 已 REVOKE DELETE
   * + 不可变触发器双重锁死）。要回看当时口径时查得到，才叫可追溯。
   */
  @Transactional
  public SellablePolicySaved retire(UUID id, OffsetDateTime at) {
    var r = sql.fetchOne("select " + COLUMNS + " from sellable_policy p where p.id = ?", id);
    if (r == null) {
      throw notFound(id);
    }
    var existingTo = r.get("effective_to", OffsetDateTime.class);
    if (existingTo != null) {
      throw bad("这一档已被作废过（生效至 " + existingTo + "），重复作废不会产生新的收口时刻");
    }
    var from = r.get("effective_from", OffsetDateTime.class);
    OffsetDateTime moment = at == null ? OffsetDateTime.now() : at;
    if (!moment.isAfter(from)) {
      throw bad("作废时刻 " + moment + " 必须晚于该档的生效起点 " + from + "：否则这一档从未真正生效过");
    }
    sql.execute("update sellable_policy set effective_to = ?::timestamptz where id = ?", moment, id);
    return new SellablePolicySaved(
        id,
        r.get("pool_key", String.class),
        from,
        moment,
        null,
        "已作废：本档自 " + moment + " 起不再生效。行本身保留，需要回看当时口径时查得到。"
            + "若此刻没有别的在效档，解析会如实报「未配置」而不是静默按 0 处理。");
  }

  // ============================================================ 生效值解析

  /**
   * 解析某一时刻真正生效的口径。
   *
   * <p>优先级 SHOP &gt; CHANNEL &gt; DEFAULT（与 17.5.1.1 同一套分档约定），命中即止，
   * 这样「回退到兜底档」是一个**如实报出来的状态**（{@code usedFallback}）而不是猜测。
   *
   * <p>三个分支各写一条 SQL，而不是一条 {@code (? is null or ...)}：后者既让 PG 反推不出
   * 参数类型，又要把 cast 放到末位绑定 —— 正是 7.1.2.2 踩过的那条纪律。
   */
  public SellablePolicyEffective effective(String scopeType, UUID referenceId, OffsetDateTime at) {
    OffsetDateTime moment = at == null ? OffsetDateTime.now() : at;
    PolicyScopeType requested = notBlank(scopeType) ? scope(scopeType) : PolicyScopeType.SHOP;

    if (requested != PolicyScopeType.DEFAULT && referenceId != null) {
      var hit = liveRow(moment, requested.name(), referenceId);
      if (hit != null) {
        return new SellablePolicyEffective(hit, requested, false, moment, directNote(requested, hit));
      }
    }
    var fallback = liveRow(moment, PolicyScopeType.DEFAULT.name(), null);
    if (fallback != null) {
      return new SellablePolicyEffective(
          fallback,
          PolicyScopeType.DEFAULT,
          true,
          moment,
          "未命中 " + requested.label() + "档（referenceId=" + referenceId
              + "），已回退到全租户兜底档。回退在这里是如实报出的状态而不是猜测："
              + "界面与 04 回写都必须知道自己用的是兜底口径。");
    }
    return new SellablePolicyEffective(
        null,
        PolicyScopeType.DEFAULT,
        false,
        moment,
        "没有找到生效的口径（已依次查过 " + requested.label() + "档与全租户兜底档）。"
            + "这是必须显式处理的空状态：平台可售数没有依据，04 回写应当拒绝，"
            + "而不是回写 0（立刻断货）或回写满（超卖）。");
  }

  private String directNote(PolicyScopeType type, SellablePolicyRow hit) {
    return "命中 " + type.label() + "档「" + hit.referenceLabel() + "」：缓冲 "
        + percent(hit.bufferRatio()) + "，平台口径 " + hit.platformStockScope().label()
        + "，超卖容忍 " + hit.oversellTolerance().toPlainString() + " 件，池 " + hit.poolKey()
        + "（" + hit.poolMode().label() + "）。";
  }

  /** 某一时刻某一档的在效行；没有则 null。 */
  private SellablePolicyRow liveRow(OffsetDateTime at, String scopeType, UUID referenceId) {
    boolean fallback = PolicyScopeType.DEFAULT.name().equals(scopeType);
    var statement =
        WIN + " select " + COLUMNS + " from sellable_policy p, win where " + (fallback
                ? "p.scope_type = 'DEFAULT' and "
                : "p.scope_type = ? and p.reference_id = ? and ")
            + LIVE_AT
            + " order by p.effective_from desc, p.id limit 1";
    var r =
        sql.fetchOne(statement, fallback ? new Object[] {at} : new Object[] {at, scopeType, referenceId});
    return r == null ? null : row(r);
  }

  // ============================================================ 共享池体检

  /**
   * 共享池名册（配置体检，不需要库存数据）。
   *
   * <p>池余量的**比例**只取决于各成员的缓冲比例，与当前有多少货无关 —— 所以「缓冲够不够」
   * 是配置问题，改缓冲就能改答案，不必先有库存数据。这样名册在刚配完、还没接库存时就能给结论；
   * 逐 SKU 的真实余量由 {@link #calculate} 给。
   */
  public List<SellablePoolSummary> pools(OffsetDateTime at) {
    OffsetDateTime moment = at == null ? OffsetDateTime.now() : at;
    Map<String, List<SellablePoolMember>> byPool = new LinkedHashMap<>();
    for (SellablePoolMember m : liveMembers(moment, null)) {
      byPool.computeIfAbsent(m.poolKey(), k -> new ArrayList<>()).add(m);
    }
    List<SellablePoolSummary> out = new ArrayList<>(byPool.size());
    for (Map.Entry<String, List<SellablePoolMember>> entry : byPool.entrySet()) {
      List<SellablePoolMember> list = entry.getValue();
      out.add(summarize(
          entry.getKey(), list, modeOf(list), distinctWarehouseCount(list), ZERO, noteFor(entry.getKey(), list)));
    }
    // 最危险的排最前：打开名册第一眼就该看到最该改的那个。
    out.sort(Comparator.comparingInt((SellablePoolSummary s) -> rank(s.verdict())).thenComparing(SellablePoolSummary::poolKey));
    return out;
  }

  /** 单个池的体检明细：成员清单 + 仓构成 + 被平台口径排除掉的 FBA 量。 */
  public SellablePoolDetail pool(String poolKey, OffsetDateTime at) {
    OffsetDateTime moment = at == null ? OffsetDateTime.now() : at;
    List<SellablePoolMember> list = new ArrayList<>(liveMembers(moment, poolKey));
    if (list.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "没有生效中的口径属于池 " + poolKey + "（池名对不上，或该池各档都已收口）");
    }
    BigDecimal excluded = excludedPlatformQuantity(list);
    String note = noteFor(poolKey, list)
        + (excluded.signum() > 0
            ? "另有 " + excluded.toPlainString() + " 件 FBA 量被平台口径排除在池容量之外"
                + "（口径设为仅自有仓时，FBA 是平台观测值不是自有账本，不参与可用量推导）。"
            : "本池没有被排除的 FBA 量。");
    SellablePoolSummary summary =
        summarize(poolKey, list, modeOf(list), distinctWarehouseCount(list), ZERO, note);
    return new SellablePoolDetail(summary, list, excluded, note);
  }

  /**
   * 池安全判定 —— 本模块真正的产出。
   *
   * <p>{@code allocationRatio} = Σ 成员的 (1 − 缓冲比例)，即「各成员的回写额加起来占池容量的
   * 几成」，完全由配置决定：
   *
   * <ul>
   *   <li>独占池只有一个成员，比值恒为 {@code 1 − 缓冲比例}，余量就是缓冲比例本身。
   *   <li>共享池 N 个成员各留 r 时比值 = N − Σr。<b>比值 ≥ 1 就是没有余量</b>：
   *       两个店铺各留 50% 缓冲时恰好等于 1，任何同步延迟都会击穿 —— 这正是蓝图说的
   *       「多店铺共享池时，缓冲不足会在同步延迟期间超卖」。
   * </ul>
   *
   * <p>有库存数据时（{@code capacity > 0}）按绝对量判，没数据时按比例判：前者能区分
   * 「有意超卖」与「配置错了」，后者只能给配置层面的结论。
   */
  private SellablePoolSummary summarize(
      String poolKey,
      List<SellablePoolMember> list,
      PoolMode mode,
      long warehouseCount,
      BigDecimal capacity,
      String note) {
    BigDecimal bufferSum = ZERO;
    BigDecimal toleranceSum = ZERO;
    for (SellablePoolMember m : list) {
      bufferSum = bufferSum.add(m.bufferRatio());
      toleranceSum = toleranceSum.add(allowance(m));
    }
    BigDecimal allocationRatio = BigDecimal.valueOf(list.size()).subtract(bufferSum);
    // 分配额按与 calculate() 完全相同的口径算（逐成员、向下取整），否则名册报出的
    // committedListing 会与逐 SKU 拆解里的 platformListing 对不上，两个数让人以为口径有两套。
    BigDecimal committed = ZERO;
    for (SellablePoolMember m : list) {
      committed = committed.add(
          capacity.multiply(ONE.subtract(m.bufferRatio())).add(allowance(m))
              .setScale(0, RoundingMode.FLOOR));
    }
    BigDecimal headroom = capacity.subtract(committed);

    PoolSafetyVerdict verdict;
    if (capacity.signum() == 0) {
      // 没有库存数据：按比例判。零库存不等于零风险，所以不能返回 GUARDED。
      verdict = allocationRatio.compareTo(ONE) > 0
          ? PoolSafetyVerdict.OVERSOLD
          : allocationRatio.compareTo(ONE) == 0 ? PoolSafetyVerdict.NO_HEADROOM : PoolSafetyVerdict.GUARDED;
    } else if (headroom.signum() > 0) {
      verdict = PoolSafetyVerdict.GUARDED;
    } else if (headroom.signum() == 0) {
      verdict = PoolSafetyVerdict.NO_HEADROOM;
    } else {
      // 负余量：落在已配置的超卖容忍额度之内是有意为之，超出去才是配置错了。
      verdict = headroom.abs().compareTo(toleranceSum) <= 0
          ? PoolSafetyVerdict.TOLERATED
          : PoolSafetyVerdict.OVERSOLD;
    }
    return new SellablePoolSummary(
        poolKey, mode, list.size(), warehouseCount, capacity, bufferSum, allocationRatio,
        committed, toleranceSum, headroom, verdict, note);
  }

  private String noteFor(String poolKey, List<SellablePoolMember> list) {
    if (list.size() > 1) {
      return "池 " + poolKey + " 有 " + list.size() + " 个口径共用同一个池：它们扣的是同一批货，"
          + "各成员的回写额必须合起来小于池容量。当前缓冲比例之和 " + percent(sumBuffer(list))
          + "，成员数 " + list.size() + " —— 低于 " + (list.size() - 1)
          + " 就会有成员分不到货，高于或等于则没有余量可用。";
    }
    return "池 " + poolKey + " 只有 1 个口径在用（独占池）：它的缓冲比例就是留给同步延迟的全部余量。";
  }

  private static BigDecimal sumBuffer(List<SellablePoolMember> list) {
    BigDecimal sum = ZERO;
    for (SellablePoolMember m : list) {
      sum = sum.add(m.bufferRatio());
    }
    return sum;
  }

  /** 成员允许的超卖额度；开关关着时恒为 0（V56 的 CHECK 也保证库里那一列是 0）。 */
  private static BigDecimal allowance(SellablePoolMember m) {
    return m.oversellAllowed() ? m.oversellTolerance() : ZERO;
  }

  // ============================================================ 可售量计算

  /**
   * 算出某一口径下每个 SKU 的平台可售数与池余量 —— 7.1.1.3 与 04 回写消费的就是这个。
   *
   * <p>口径逐项参与计算，不是挂着好看：
   *
   * <ul>
   *   <li>合格量只含 {@code inventory_quality_status.role='SELLABLE'} 的余额：待检、残次、未上架
   *       被**排除**而不是扣减（蓝图 7.1.1.3 原文）。
   *   <li>{@code includeInTransit} 为真时加上发往该仓的在途（{@code to_warehouse_id}）——
   *       抵扣的是「会到这仓来」的货，不是从这仓发出的货。
   *   <li>{@code platformStockScope='OWN_WAREHOUSE'} 时 FBA 仓不计入池容量，与 7.1.3.1 的
   *       「FBA 库存是平台仓观测值，不是自有账本」同一条边界。
   *   <li>池容量按**去重后的仓集合**聚合：同一个仓被两个共享成员引用只算一次，
   *       否则共享池容量会被重复计入而虚高一倍。
   * </ul>
   */
  public SellableQuantityResult calculate(SellableQuantityRequest request) {
    if (request == null) {
      throw bad("请求体不能为空");
    }
    List<UUID> skuIds = normalize(request.skuIds());
    if (skuIds.isEmpty()) {
      throw bad("请至少传一个 SKU：可售量按 SKU 集合批量算，空集合没有可回答的问题");
    }
    if (skuIds.size() > MAX_SKUS) {
      throw bad("一次最多算 " + MAX_SKUS + " 个 SKU，收到 " + skuIds.size());
    }
    OffsetDateTime at = request.at() == null ? OffsetDateTime.now() : request.at();
    SellablePolicyEffective resolved = effective(request.scopeType(), request.referenceId(), at);
    if (resolved.policy() == null) {
      // 空状态如实返回：rows 为空、verdict 为 null，调用方据此拒绝回写而不是回写 0。
      return new SellableQuantityResult(resolved, null, List.of(), skuIds.size(), null, null, null, resolved.note());
    }
    SellablePolicyRow policy = resolved.policy();

    List<SellablePoolMember> members = new ArrayList<>(liveMembers(at, policy.poolKey()));
    if (members.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "池 " + policy.poolKey() + " 没有生效中的成员口径，无法计算池容量");
    }

    Map<UUID, Capacity> capacities = capacities(at, members, skuIds, request.warehouseId());
    BigDecimal poolCapacity = ZERO;
    List<SellableQuantityRow> rows = new ArrayList<>(skuIds.size());
    for (UUID skuId : skuIds) {
      Capacity cap = capacities.getOrDefault(skuId, Capacity.EMPTY);
      BigDecimal bufferReserved = cap.capacity().multiply(policy.bufferRatio());
      BigDecimal allowance = allowance(policy);
      // 回写平台的件数必须是整数：0.8 件无法下单。向下取整而不是四舍五入 ——
      // 四舍五入会把 0.5 件变成 1 件，凭空多卖半件。
      BigDecimal listing =
          cap.capacity().subtract(bufferReserved).add(allowance).setScale(0, RoundingMode.FLOOR);
      rows.add(new SellableQuantityRow(
          skuId, cap.skuCode(), cap.warehouseCount(), cap.available(), cap.inTransit(), cap.capacity(),
          policy.bufferRatio(), bufferReserved, allowance, listing));
      poolCapacity = poolCapacity.add(cap.capacity());
    }

    // 池余量要把**所有成员**的回写额加起来才判断得了 —— 只算被问的那一个，
    // 就会漏掉「另一个店铺把池子占满了」这种真正会超卖的情况。summarize() 内部就是
    // 按全部成员逐条算的，所以这里不再重复一遍口径。
    SellablePoolSummary pool = summarize(
        policy.poolKey(), members, policy.poolMode(), distinctWarehouseCount(members), poolCapacity,
        noteFor(policy.poolKey(), members) + " " + resolved.note());

    return new SellableQuantityResult(
        resolved,
        pool,
        rows,
        skuIds.size(),
        poolCapacity,
        rows.stream().map(SellableQuantityRow::platformListing).reduce(ZERO, BigDecimal::add),
        pool.verdict(),
        resolved.note()
            + " 合格量只含质量状态为可售的库存；待检、残次、未上架被排除而不是扣减。"
            + "池 " + policy.poolKey() + " 本次余量 " + pool.headroom().toPlainString()
            + "，判定「" + pool.verdict().label() + "」。");
  }

  private static BigDecimal allowance(SellablePolicyRow p) {
    return p.oversellAllowed() ? p.oversellTolerance() : ZERO;
  }

  /** 逐 SKU 的池容量构成。 */
  private record Capacity(
      BigDecimal available, BigDecimal inTransit, BigDecimal capacity, long warehouseCount, String skuCode) {
    static final Capacity EMPTY = new Capacity(ZERO, ZERO, ZERO, 0, "");
  }

  /**
   * 取每个 SKU 的池容量：一次查询把三件事算完 —— 去重后的贡献仓集合、合格可用量、在途增量。
   *
   * <p>在途用独立的第二个 CTE 而不是 join 进来：两者粒度不同，join 会让在途按 sku×warehouse
   * 相乘（与 {@code InventoryAvailabilityService} 同一理由）。
   */
  private Map<UUID, Capacity> capacities(
      OffsetDateTime at, List<SellablePoolMember> members, List<UUID> skuIds, UUID warehouseId) {
    String in = placeholders(skuIds.size());
    // 池里的成员按定义共用同一个 poolKey（liveMembers 就是按它查出来的），取第一个即可。
    String poolKey = members.get(0).poolKey();

    var binds = new ArrayList<Object>();
    binds.add(at);
    binds.add(poolKey);
    binds.add(tenant());
    if (warehouseId != null) {
      binds.add(warehouseId);
    }
    binds.add(tenant());
    binds.addAll(skuIds);
    binds.add(tenant());
    binds.addAll(skuIds);
    binds.add(tenant());
    binds.addAll(skuIds);

    var rows =
        sql.fetch(
            WIN
                + ", members as (select p.id,p.buffer_ratio,p.oversell_allowed,p.oversell_tolerance,"
                + " p.platform_stock_scope,p.include_in_transit from sellable_policy p, win"
                + "   where p.pool_key = ? and " + LIVE_AT + "),"
                + " contrib as (select lw.warehouse_id, bool_or(m.include_in_transit) as include_in_transit"
                + "   from sellable_policy_warehouse lw"
                + "   join members m on m.id = lw.policy_id"
                + "   where lw.tenant_id = ?"
                + "     and (m.platform_stock_scope = 'OWN_PLUS_FBA' or lw.warehouse_kind = 'OWN')"
                + (warehouseId == null ? "" : "     and lw.warehouse_id = ?")
                + "   group by lw.warehouse_id),"
                + " qual as (select b.sku_id, sum(b.available) as available from inventory_balance b"
                + "   join contrib c on c.warehouse_id = b.warehouse_id"
                + "   where b.tenant_id = ? and b.sku_id in " + in
                + "     and exists (select 1 from inventory_quality_status q"
                + "       where q.tenant_id = b.tenant_id and q.id = b.quality_status_id and q.role = 'SELLABLE')"
                + "   group by b.sku_id),"
                + " transit as (select t.sku_id, sum(t.quantity) as in_transit from inventory_transfer t"
                + "   join contrib c on c.warehouse_id = t.to_warehouse_id and c.include_in_transit"
                + "   where t.tenant_id = ? and t.status = 'IN_TRANSIT' and t.sku_id in " + in
                + "   group by t.sku_id),"
                + " skus as (select id, code from sku_ref where tenant_id = ? and id in " + in + ")"
                + " select skus.id as sku_id, skus.code,"
                + " coalesce(qual.available, 0) as available,"
                + " coalesce(transit.in_transit, 0) as in_transit,"
                + " (select count(*) from contrib) as warehouse_count"
                + " from skus"
                + " left join qual on qual.sku_id = skus.id"
                + " left join transit on transit.sku_id = skus.id"
                + " order by skus.code",
            binds.toArray());

    Map<UUID, Capacity> out = new LinkedHashMap<>();
    for (Record r : rows) {
      BigDecimal available = decimal(r, "available");
      BigDecimal transit = decimal(r, "in_transit");
      out.put(
          r.get("sku_id", UUID.class),
          new Capacity(available, transit, available.add(transit),
              r.get("warehouse_count", Long.class), r.get("code", String.class)));
    }
    return out;
  }

  // ============================================================ 内部工具

  /** 某一时刻生效中的池成员。{@code poolKey} 为 null 时取本租户全部在效档。 */
  private List<SellablePoolMember> liveMembers(OffsetDateTime at, String poolKey) {
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    binds.add(at);
    if (poolKey != null) {
      where.add("p.pool_key = ?");
      binds.add(poolKey);
    }
    where.add(LIVE_AT);

    var rows =
        sql.fetch(
            WIN + " select " + COLUMNS + ","
                + " (select count(*) from sellable_policy_warehouse lw where lw.policy_id = p.id)"
                + " as warehouse_count"
                + " from sellable_policy p, win where " + String.join(" and ", where)
                + " order by p.pool_key, p.scope_type, p.id",
            binds.toArray());
    List<SellablePoolMember> out = new ArrayList<>(rows.size());
    for (Record r : rows) {
      out.add(
          new SellablePoolMember(
              r.get("id", UUID.class),
              r.get("pool_key", String.class),
              PolicyScopeType.valueOf(r.get("scope_type", String.class)),
              r.get("reference_id", UUID.class),
              nullToEmpty(r.get("reference_label", String.class)),
              decimal(r, "buffer_ratio"),
              Boolean.TRUE.equals(r.get("oversell_allowed", Boolean.class)),
              decimal(r, "oversell_tolerance"),
              PlatformStockScope.valueOf(r.get("platform_stock_scope", String.class)),
              Boolean.TRUE.equals(r.get("include_in_transit", Boolean.class)),
              r.get("warehouse_count", Long.class)));
    }
    return out;
  }

  private static SellablePolicyRow row(Record r) {
    return new SellablePolicyRow(
        r.get("id", UUID.class),
        PolicyScopeType.valueOf(r.get("scope_type", String.class)),
        r.get("reference_id", UUID.class),
        nullToEmpty(r.get("reference_label", String.class)),
        PoolMode.valueOf(r.get("pool_mode", String.class)),
        r.get("pool_key", String.class),
        Boolean.TRUE.equals(r.get("oversell_allowed", Boolean.class)),
        decimal(r, "oversell_tolerance"),
        PlatformStockScope.valueOf(r.get("platform_stock_scope", String.class)),
        decimal(r, "buffer_ratio"),
        Boolean.TRUE.equals(r.get("include_in_transit", Boolean.class)),
        r.get("effective_from", OffsetDateTime.class),
        r.get("effective_to", OffsetDateTime.class),
        nullToEmpty(r.get("note", String.class)),
        r.get("created_by_membership_id", UUID.class),
        r.get("created_at", OffsetDateTime.class));
  }

  /**
   * 池内被「仅自有仓」口径排除掉的 FBA 量。
   *
   * <p>排除而不是静默丢弃：用户得知道有一批货按自己的配置没被计入，否则会以为 FBA 已经被算进去了。
   */
  private BigDecimal excludedPlatformQuantity(List<SellablePoolMember> members) {
    List<Object> ids = members.stream().map(SellablePoolMember::policyId).map(o -> (Object) o).toList();
    if (ids.isEmpty()) {
      return ZERO;
    }
    var r =
        sql.fetchOne(
            "select coalesce(sum(b.available), 0) as excluded from inventory_balance b"
                + " join sellable_policy_warehouse lw on lw.tenant_id = b.tenant_id"
                + "   and lw.warehouse_id = b.warehouse_id and lw.policy_id in " + placeholders(ids.size())
                + " join sellable_policy p on p.tenant_id = b.tenant_id and p.id = lw.policy_id"
                + " where lw.warehouse_kind = 'PLATFORM_FBA'"
                + "   and p.platform_stock_scope = 'OWN_WAREHOUSE'"
                + "   and exists (select 1 from inventory_quality_status q"
                + "     where q.tenant_id = b.tenant_id and q.id = b.quality_status_id and q.role = 'SELLABLE')",
            ids.toArray());
    return decimal(r, "excluded");
  }

  private long distinctWarehouseCount(List<SellablePoolMember> members) {
    if (members.isEmpty()) {
      return 0;
    }
    List<Object> ids = members.stream().map(SellablePoolMember::policyId).map(o -> (Object) o).toList();
    var r =
        sql.fetchOne(
            "select count(distinct lw.warehouse_id) as c from sellable_policy_warehouse lw"
                + " where lw.policy_id in " + placeholders(ids.size()),
            ids.toArray());
    return r == null ? 0 : r.get(0, Long.class);
  }

  /** 池模式：独占池的键由服务按 EXCL-{referenceId} 派生，所以池里只有一个成员就是独占池。 */
  private static PoolMode modeOf(List<SellablePoolMember> list) {
    return list.size() > 1 ? PoolMode.SHARED : PoolMode.EXCLUSIVE;
  }

  private static Map<UUID, WarehouseKind> bindings(List<SellablePolicyInput.SellablePolicyWarehouseBinding> input) {
    Map<UUID, WarehouseKind> wanted = new LinkedHashMap<>();
    if (input == null) {
      return wanted;
    }
    for (SellablePolicyInput.SellablePolicyWarehouseBinding binding : input) {
      if (binding == null || binding.warehouseId() == null) {
        throw bad("挂仓清单里有空行：每个仓都要指明 warehouseId 与 warehouseKind");
      }
      if (wanted.put(binding.warehouseId(), warehouseKind(binding.warehouseKind())) != null) {
        throw bad("仓 " + binding.warehouseId() + " 在挂仓清单里出现了两次");
      }
    }
    return wanted;
  }

  private void verifyWarehouses(Set<UUID> ids) {
    if (ids.isEmpty()) {
      return;
    }
    Set<UUID> found = new LinkedHashSet<>();
    for (Record r : sql.fetch("select id from warehouse where id in " + placeholders(ids.size()), ids.toArray())) {
      found.add(r.get(0, UUID.class));
    }
    Set<UUID> missing = new LinkedHashSet<>(ids);
    missing.removeAll(found);
    if (!missing.isEmpty()) {
      throw bad("这些仓在本租户不存在：" + missing
          + "。挂不到口径上的仓会让池容量凭空少算，而少算平台可售数看起来更安全，"
          + "实际是丢单与断货");
    }
  }

  /** 去重但保留请求顺序：结果里每一行都要能对上调用方提交的那一行的位置。 */
  private static List<UUID> normalize(List<UUID> ids) {
    List<UUID> out = new ArrayList<>();
    if (ids == null) {
      return out;
    }
    for (UUID id : ids) {
      if (id != null && !out.contains(id)) {
        out.add(id);
      }
    }
    return out;
  }

  private static String resolvePoolKey(PoolMode mode, String raw, UUID referenceId) {
    if (mode == PoolMode.EXCLUSIVE) {
      // 独占池键由服务派生，人填什么都忽略：让人自己填才可能把独占池写成共享池。
      return "EXCL-" + (referenceId == null ? "" : referenceId.toString());
    }
    String key = raw == null ? "" : raw.trim();
    if (key.isEmpty()) {
      throw bad("共享池必须取池名：几个口径用同一个池名才叫共享，没有池名就解析不出成员集合");
    }
    if (key.startsWith("EXCL-")) {
      throw bad("共享池名不能以 EXCL- 开头：那是服务给独占池派生的命名空间，"
          + "占用它会让「按名字找池子」在两种含义之间产生歧义");
    }
    return key;
  }

  private static boolean sameSlot(Record target, PolicyScopeType scope, UUID referenceId) {
    return target.get("scope_type", String.class).equals(scope.name())
        && Objects.equals(target.get("reference_id", UUID.class), referenceId);
  }

  private static PolicyScopeType scope(String value) {
    try {
      return PolicyScopeType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad("未知的生效范围：" + value + "（取值："
          + java.util.Arrays.toString(PolicyScopeType.values()) + "）");
    }
  }

  private static PoolMode poolMode(String value) {
    try {
      return PoolMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad("未知的共享池策略：" + value + "（取值："
          + java.util.Arrays.toString(PoolMode.values()) + "）");
    }
  }

  private static PlatformStockScope stockScope(String value) {
    try {
      return PlatformStockScope.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad("未知的平台可售量口径：" + value + "（取值："
          + java.util.Arrays.toString(PlatformStockScope.values()) + "）");
    }
  }

  private static WarehouseKind warehouseKind(String value) {
    try {
      return WarehouseKind.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad("未知的仓库归属：" + value + "（取值："
          + java.util.Arrays.toString(WarehouseKind.values()) + "）");
    }
  }

  private static String percent(BigDecimal ratio) {
    return ratio.multiply(new BigDecimal("100")).stripTrailingZeros().toPlainString() + "%";
  }

  private static boolean notBlank(String s) {
    return s != null && !s.isBlank();
  }

  private static String nullToEmpty(String s) {
    return s == null ? "" : s;
  }

  private static BigDecimal decimal(Record r, String field) {
    BigDecimal v = r.get(field, BigDecimal.class);
    return v == null ? ZERO : v;
  }

  private static List<Object> append(List<Object> base, Object... tail) {
    List<Object> all = new ArrayList<>(base);
    java.util.Collections.addAll(all, tail);
    return all;
  }

  /** 租户来自 RLS 的同一个来源，绝不来自任何请求参数或客户端头。 */
  private UUID tenant() {
    return sql.fetchOne("select current_setting('app.current_tenant',true)::uuid as t").get("t", UUID.class);
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException notFound(UUID id) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "口径不存在或不属于当前租户：" + id);
  }
}
