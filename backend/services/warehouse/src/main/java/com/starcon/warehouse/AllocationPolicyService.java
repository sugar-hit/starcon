package com.starcon.warehouse;

import static com.starcon.warehouse.AllocationPolicyModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 7.8.1.1 分配策略配置：分档生效、成员名册、销量登记，以及<b>一套真的</b>分摊算法。
 *
 * <p>蓝图红线：「多平台共享库存时必须有一份统一的分摊算法，否则会出现『某个店铺一直抢不到货』或
 * 『所有店铺都以为有货』的超卖。」本类就是那句话的落点 —— 算式全部在 {@link #compute} 一处，
 * 别的端点只负责取数与落库，不重复实现第二套算法。
 *
 * <p><b>四条实现纪律</b>：
 *
 * <ul>
 *   <li><b>区间 + 不可变</b>：策略内容写定后不可改（V68 触发器），改策略 = 收口旧档 + 开新档。
 *       历史因此天然完整，不需要第二张「变更历史」表（与 17.5.1.1、7.2.2.1 同一套约定）。
 *   <li><b>守恒在 DB 层</b>：{@code available = buffer + allocatable} 与
 *       {@code allocatable = allocatedTotal + unallocated} 都是 allocation_run 的 CHECK，
 *       {@code Σ行 == allocatedTotal} 由延迟约束触发器保证。应用层算错会被 DB 当场拒绝。
 *   <li><b>时间绑定</b>：{@code ?::timestamptz} 集中在最前面的 {@code win} CTE 里，
 *       后面全是普通绑定，cast 永远不在绑定序列末位（与 7.1.2.2 同一条纪律）。
 *   <li><b>数量一律 numeric(19,6) + BigDecimal</b>，比较一律 {@code compareTo} 不用 {@code equals}。
 * </ul>
 *
 * <p><b>可用量为什么可以由调用方直接给</b>：分摊的输入是「这一批货有多少」，而这批货属于哪些仓
 * 是 7.2.2.1 口径（共享池）的结论。调用方手里往往已经有那个数（04 回写、07 的拣货任务），
 * 让它再传一遍比重算一遍更不容易错。传 null 时本服务按 7.2.2.1 的池容量口径现算
 * （合格可售可用量 + 含在途时发往该仓的在途），但那条路只在它真的能算准时才用。
 */
@Service
public class AllocationPolicyService {

  /** 单页最大行数：策略是配置不是流水，量级很小，但仍设上限防止一次拉爆 JVM。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 一次最多算多少个 SKU（与 InventoryAvailabilityService / 7.2.2.1 同一套上限与理由）。 */
  public static final int MAX_SKUS = 500;

  /** 单档最多配多少个成员店铺。店铺是有限的组织实体，设上限只为挡住误操作。 */
  public static final int MAX_MEMBERS = 200;

  /** 数量列在 DB 侧是 numeric(19,6)，而**分摊额必须是整件**，所以这里的 scale 是 0。 */
  private static final int QTY_SCALE = 0;

  /** numeric(19,6) 口径下的零，避免同一列里混着 scale 不一样的 0。 */
  private static final BigDecimal ZERO = new BigDecimal("0.000000");

  /** 分摊额口径下的零（整件）。 */
  private static final BigDecimal ZERO_QTY = BigDecimal.ZERO;

  private static final BigDecimal ONE = BigDecimal.ONE;

  /** 店铺类型的默认优先级顺序（V68 的 shop_type_order 默认值，两处必须一致）。 */
  static final String DEFAULT_SHOP_TYPE_ORDER =
      "SELF_FULFILLED,SEMI_CONSIGNMENT,FULL_CONSIGNMENT,OVERSEAS_WAREHOUSE,LOCAL";

  /** 店铺类型词表，取值与 channel 的 shop.operating_mode 完全一致（2.3.2.1）。 */
  static final List<String> SHOP_OPERATING_MODES =
      List.of("SELF_FULFILLED", "FULL_CONSIGNMENT", "SEMI_CONSIGNMENT", "OVERSEAS_WAREHOUSE", "LOCAL");

  /** IN 列表占位符，每个都带 {@code ::uuid}：jOOQ 按 varchar 送 UUID，单元素列表 PG 反推不出类型。 */
  private static String placeholders(int n) {
    return "(" + String.join(",", java.util.Collections.nCopies(n, "?::uuid")) + ")";
  }

  /** 生效时刻绑定。两个 {@code ?::timestamptz} 刻意放在整条语句最前面，理由见类注释。 */
  private static final String WIN = "with win as (select ?::timestamptz as at)";

  /** 分档的固定 SELECT 列。 */
  private static final String COLUMNS =
      "p.id,p.scope_type,p.source_system,p.reference_id,p.reference_label,p.method,"
          + "p.buffer_mode,p.buffer_ratio,p.buffer_quantity,p.shop_type_order,p.sales_window_days,"
          + "p.effective_from,p.effective_to,p.note,p.created_by_membership_id,p.created_at";

  /** 生效判定谓词 —— 「生效」在本模块只有这一个定义。 */
  private static final String LIVE_AT =
      "(p.effective_from <= win.at and (p.effective_to is null or p.effective_to > win.at))";

  /**
   * 分档写入的列与每列的绑定 cast，<b>由同一个数组生成 SQL</b>。
   *
   * <p>刻意不手写两串 {@code "?,?,?,?::numeric,..."}：列顺序与 cast 顺序各写一遍时，
   * 少一个或错开一位就会让整条语句绑定错位，而 PG 报的是
   * 「invalid input syntax for type numeric」或「未设定参数值 N」—— 没有一条指向真正的原因。
   */
  private static final String[] POLICY_COLUMNS = {
    "id", "tenant_id", "scope_type", "source_system", "reference_id", "reference_label",
    "method", "buffer_mode", "buffer_ratio", "buffer_quantity", "shop_type_order",
    "sales_window_days", "effective_from", "note", "created_by_membership_id"
  };

  /** 与 {@link #POLICY_COLUMNS} 一一对应：空串 = 普通绑定，其余是 PG 侧的 cast。 */
  private static final String[] POLICY_CASTS = {
    "", "", "", "", "", "", "", "", "::numeric", "::numeric", "",
    "", "::timestamptz", "", ""
  };

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
    return "insert into allocation_policy (" + columns + ") values (" + values + ")";
  }

  private final DSLContext sql;

  public AllocationPolicyService(DSLContext sql) {
    this.sql = sql;
  }

  // ============================================================ 能力

  /**
   * 分摊能力清单：三种方法、三个范围档、五种店铺类型，以及三条必须如实讲清楚的说明。
   *
   * <p>前端据此渲染选项与提示，而不是把规则硬编码在界面上 —— 规则改了就该只改一处。
   */
  public AllocationCapabilities capabilities() {
    List<AllocationMethodOption> methods = new ArrayList<>();
    for (AllocationMethod m : AllocationMethod.values()) {
      methods.add(
          new AllocationMethodOption(
              m.name(),
              m.label(),
              true,
              m.needsSales(),
              switch (m) {
                case PROPORTIONAL_SALES ->
                    "按各店登记销量占比分摊。销量为 0 的店铺分不到货 —— 这是方法的定义，"
                        + "不是缺陷；余数按份额小数部分从大到小逐件补发，保证 Σ分摊 ≤ 可分配量。";
                case FIXED_PRIORITY ->
                    "按固定优先级先到先得。**必须给每个成员设份额上限**：不设上限时排第一的店铺"
                        + "会拿走全部，低优先级店铺永远拿不到货 —— 界面把「不设上限」标成风险而不是默认值。";
                case SHOP_TYPE_PRIORITY ->
                    "按店铺类型（自营/托管）优先级，类型顺序在策略里配置而不是写死在代码里；"
                        + "同类型内再按成员优先级。货权是谁的货谁优先，是经营决策，代码替租户做不了。";
              }));
    }
    List<AllocationScopeOption> scopes = new ArrayList<>();
    for (AllocationScopeType s : AllocationScopeType.values()) {
      scopes.add(
          new AllocationScopeOption(
              s.name(),
              s.label(),
              true,
              switch (s) {
                case DEFAULT -> "兜底档。全租户一条，没有它就没有统一分摊算法（蓝图红线）。";
                case SHOP -> "按店铺覆盖。店铺在 channel 服务，这里只存逻辑 ID 与可读名快照，不建外键。";
                case SKU -> "按 SKU 覆盖。SKU 在 catalog 服务，同上。同一批货里某个爆款 SKU 的分摊"
                    + "方式与其余 SKU 不同是常态，所以 SKU 档优先于店铺档。";
              }));
    }
    return new AllocationCapabilities(
        methods,
        scopes,
        SHOP_OPERATING_MODES,
        DEFAULT_SHOP_TYPE_ORDER,
        false,
        "销量数据**无法自动派生**：库存流水（inventory_transaction）没有店铺维度，一个仓发出去的货"
            + "拆不到「哪个店卖掉的」；而订单域（05 卷）尚未交付，没有可查的订单事实。"
            + "所以各店历史销量由 POST /warehouse/inventory/allocation-sales **登记**进来，"
            + "它是本服务唯一的销量真相来源。订单域交付后应由其回写，本模块不臆造销量。",
        "本模块的「留出不分配缓冲」与 7.2.2.1 的 buffer_ratio 是**两个不同的东西**："
            + "7.2.2.1 留的是平台回写同步延迟的余量（单店口径），本模块留的是多店分摊时压根不参与分摊的量。"
            + "可分配量 = 可用量 − 本模块缓冲。7.2.2.1 的缓冲在它上游、各店自己的回写口径里，"
            + "本模块不重复扣一次 —— 重复扣会让同一批货被两块缓冲各留一次，余量凭空少一截。",
        "分摊结果已落库（allocation_run / allocation_run_line，append-only），下游 7.8.2.1 分摊结果查看"
            + "读的就是这两张表；7.8.1.2 优先级与保底、7.8.2.2 缺货与饥饿预警尚未交付，"
            + "本模块不代它们实现，只在结果里如实标出每个店铺「为什么没拿到货」。");
  }

  // ============================================================ 分档列表与详情

  /** 分摊策略分档列表（真分页，total 与分页无关）。 */
  public AllocationPolicyPage page(AllocationPolicyQuery query) {
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size() <= 0 ? 50 : query.size()));
    int pageNo = Math.max(0, query.page());
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    conditions(query, where, binds);
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    long total =
        sql.fetchOne("select count(*) from allocation_policy p" + sqlWhere, binds.toArray())
            .get(0, Long.class);

    var rows =
        sql.fetch(
            "select " + COLUMNS + " from allocation_policy p" + sqlWhere + order(query)
                + " limit ? offset ?",
            append(binds, size, (long) pageNo * size).toArray());

    return new AllocationPolicyPage(
        rows.stream().map(AllocationPolicyService::row).toList(), total, pageNo, size);
  }

  private void conditions(AllocationPolicyQuery query, List<String> where, List<Object> binds) {
    if (notBlank(query.scopeType())) {
      where.add("p.scope_type = ?");
      binds.add(query.scopeType().trim().toUpperCase(Locale.ROOT));
    }
    if (query.referenceId() != null) {
      where.add("p.reference_id = ?");
      binds.add(query.referenceId());
    }
    if (notBlank(query.method())) {
      where.add("p.method = ?");
      binds.add(query.method().trim().toUpperCase(Locale.ROOT));
    }
    if (query.effectiveOnly()) {
      // 「此刻仍在生效」由服务端用 now() 判定，不接受调用方传时刻：
      // 否则「看未来某一刻谁生效」会变成一个能试错的入口，而它在这里没有业务含义。
      where.add("(p.effective_from <= now() and (p.effective_to is null or p.effective_to > now()))");
    }
    if (notBlank(query.keyword())) {
      where.add("(p.reference_label ilike ? or p.note ilike ?)");
      String k = "%" + query.keyword().trim() + "%";
      binds.add(k);
      binds.add(k);
    }
  }

  private String order(AllocationPolicyQuery query) {
    // 决胜键恒定追加 p.id：同排序值时翻页不会错位或重复。
    return query.effectiveOnly()
        ? " order by p.scope_type, p.method, p.effective_from desc, p.id"
        : " order by p.effective_from desc, p.id";
  }

  /** 策略详情 = 分档 + 它的成员名册。跨租户在 RLS 下查不到 → 404。 */
  public AllocationPolicyDetail detail(UUID id) {
    var r = sql.fetchOne("select " + COLUMNS + " from allocation_policy p where p.id = ?", id);
    if (r == null) {
      throw notFound(id);
    }
    return new AllocationPolicyDetail(row(r), members(id));
  }

  private List<AllocationPolicyMember> members(UUID policyId) {
    return sql.fetch(
            "select m.policy_id,m.source_system,m.reference_id,m.reference_label,m.shop_operating_mode,"
                + "m.priority,m.share_limit,m.enabled from allocation_policy_member m"
                + " where m.policy_id = ? order by m.priority, m.reference_id",
            policyId)
        .map(
            r ->
                new AllocationPolicyMember(
                    r.get("policy_id", UUID.class),
                    r.get("source_system", String.class),
                    r.get("reference_id", UUID.class),
                    nullToEmpty(r.get("reference_label", String.class)),
                    r.get("shop_operating_mode", String.class),
                    r.get("priority", Integer.class),
                    decimalOrNull(r, "share_limit"),
                    Boolean.TRUE.equals(r.get("enabled", Boolean.class))));
  }

  // ============================================================ 写入策略

  /**
   * 新建一档分摊策略。{@code supersedeId} 非空时先收口那一档的区间再开新档（改策略的标准动作）。
   *
   * <p>跨服务边界：店铺在 channel 服务、SKU 在 catalog 服务，本服务不建外键也不查它们的表，
   * 只存 {@code source_system + reference_id} 与写入时的可读名快照（V68 文件头）。
   *
   * <p>逐店声明 {@code shopOperatingMode} 不是形式：「按店铺类型优先级」真的按它排序。
   */
  @Transactional
  public AllocationPolicySaved save(UUID supersedeId, AllocationPolicyInput input, UUID actorId) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    AllocationScopeType scope = scope(input.scopeType());
    AllocationMethod method = method(input.method());
    AllocationBufferMode bufferMode = bufferMode(input.bufferMode());

    String sourceSystem = input.sourceSystem() == null ? "" : input.sourceSystem().trim();
    UUID referenceId = input.referenceId();
    if (scope == AllocationScopeType.DEFAULT) {
      sourceSystem = "";
      referenceId = null;
    } else if (referenceId == null || sourceSystem.isEmpty()) {
      throw bad(
          scope.label()
              + "档必须同时给 sourceSystem 与 referenceId：它们是跨服务定位键"
              + "（店铺在 channel、SKU 在 catalog，本服务不建外键也不查它们的表），"
              + "少一个就会出现一档永远命中不了的策略");
    }

    BigDecimal bufferRatio =
        input.bufferRatio() == null ? ZERO : input.bufferRatio().setScale(6, RoundingMode.HALF_UP);
    BigDecimal bufferQuantity =
        input.bufferQuantity() == null ? ZERO : input.bufferQuantity().setScale(6, RoundingMode.HALF_UP);
    if (bufferRatio.signum() < 0 || bufferRatio.compareTo(ONE) > 0) {
      throw bad("缓冲比例必须在 0 与 1 之间，收到 " + bufferRatio.toPlainString()
          + "：缓冲是防超卖的最后一道防线，越界等于没有防线");
    }
    if (bufferQuantity.signum() < 0) {
      throw bad("缓冲件数不能为负数，收到 " + bufferQuantity.toPlainString());
    }
    // 两列互斥由服务先挡一道：同时给比例与件数，「到底留多少」这个问题就没有唯一答案。
    if (bufferMode == AllocationBufferMode.RATIO && bufferQuantity.signum() != 0) {
      throw bad("按比例留缓冲时 bufferQuantity 必须是 0（留多少由比例决定），"
          + "两者都填等于「到底留多少」没有唯一答案");
    }
    if (bufferMode == AllocationBufferMode.QUANTITY && bufferRatio.signum() != 0) {
      throw bad("按件数留缓冲时 bufferRatio 必须是 0（留多少由件数决定），"
          + "两者都填等于「到底留多少」没有唯一答案");
    }

    String shopTypeOrder = shopTypeOrder(input.shopTypeOrder());
    int windowDays = input.salesWindowDays() == null ? 30 : input.salesWindowDays();
    if (windowDays <= 0 || windowDays > 365) {
      throw bad("销量回看天数必须在 1 与 365 之间，收到 " + windowDays
          + "：回看超过一年没有业务含义，而一天以下取不到能反映趋势的量");
    }

    List<AllocationPolicyInput.AllocationPolicyMemberInput> members = input.members();
    if (members == null || members.isEmpty()) {
      throw bad("至少要配一个参与分摊的店铺：没有成员的分摊档解析不出「分给谁」，"
          + "它会静默地给不出任何结果");
    }
    if (members.size() > MAX_MEMBERS) {
      throw bad("一档最多配 " + MAX_MEMBERS + " 个成员店铺，收到 " + members.size());
    }

    OffsetDateTime effectiveFrom =
        input.effectiveFrom() == null ? OffsetDateTime.now() : input.effectiveFrom();

    // 换版：先把该槽位仍在生效的那一档收口，再开新档。顺序不能反 ——
    // 先插新档会被区间重叠触发器当场拒绝（那是正确的失败，但表达不出「顺带收口」这个意图）。
    UUID superseded = null;
    if (supersedeId != null) {
      var target = sql.fetchOne("select " + COLUMNS + " from allocation_policy p where p.id = ?", supersedeId);
      if (target == null) {
        throw notFound(supersedeId);
      }
      if (target.get("effective_to", OffsetDateTime.class) != null) {
        throw bad("这一档的区间已被收口（生效至 " + target.get("effective_to", OffsetDateTime.class)
            + "），不能作为换版对象：要换的是当前正在生效的那一档");
      }
      if (!sameSlot(target, scope, referenceId)) {
        throw bad("换版必须落在同一个范围槽位内（原档 "
            + AllocationScopeType.valueOf(target.get("scope_type", String.class)).label()
            + "，新档 " + scope.label() + "）：跨槽位改档会让原档被收口后无处继承，"
            + "等于悄悄作废了一条分摊策略");
      }
      if (!effectiveFrom.isAfter(target.get("effective_from", OffsetDateTime.class))) {
        throw bad("新档的生效起点 " + effectiveFrom + " 必须晚于原档起点 "
            + target.get("effective_from", OffsetDateTime.class) + "：区间不前进就等于原地改策略");
      }
      sql.execute(
          "update allocation_policy set effective_to = ?::timestamptz where id = ?",
          effectiveFrom, supersedeId);
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
        method.name(),
        bufferMode.name(),
        bufferRatio,
        bufferQuantity,
        shopTypeOrder,
        windowDays,
        effectiveFrom,
        input.note() == null ? "" : input.note().trim(),
        actorId);

    // 成员写在策略之后：V68 的 guard_allocation_member_policy_open 只允许往**未收口**的档上挂，
    // 而换版时旧档此刻已被收口 —— 所以成员必须挂到刚开的新档上。
    var normalized = normalizeMembers(members, method);
    for (AllocationPolicyInput.AllocationPolicyMemberInput m : normalized) {
      sql.execute(
          "insert into allocation_policy_member (tenant_id,policy_id,source_system,reference_id,"
              + "reference_label,shop_operating_mode,priority,share_limit,enabled) "
              + "values (?,?,?,?,?,?,?,?,?)",
          tenant(),
          id,
          m.sourceSystem().trim(),
          m.referenceId(),
          m.referenceLabel() == null ? "" : m.referenceLabel().trim(),
          m.shopOperatingMode().trim().toUpperCase(Locale.ROOT),
          m.priority() == null ? 100 : m.priority(),
          m.shareLimit(),
          m.enabled() == null || m.enabled());
    }

    return new AllocationPolicySaved(
        id,
        method,
        effectiveFrom,
        null,
        superseded,
        normalized.size(),
        superseded == null
            ? "已新建" + scope.label() + "分摊策略：" + method.label() + "，缓冲 "
                + bufferText(bufferMode, bufferRatio, bufferQuantity) + "，参与店铺 " + normalized.size()
                + " 个。该档按区间生效，历史各档原样保留。"
            : "已换版：原档的区间收到 " + effectiveFrom + " 为止，新档自 " + effectiveFrom + " 起生效。"
                + "两档并存，历史那一档仍是「当时真正生效的那套分摊算法」的唯一凭据。");
  }

  /**
   * 作废一档（收口它的生效区间）。
   *
   * <p>刻意**不**提供 DELETE：作废是审计事件，档位行必须永久保留（V68 已 REVOKE DELETE
   * + 不可变触发器双重锁死）。要回看当时的算法时查得到，才叫可追溯。
   */
  @Transactional
  public AllocationPolicySaved retire(UUID id, OffsetDateTime at) {
    var r = sql.fetchOne("select " + COLUMNS + " from allocation_policy p where p.id = ?", id);
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
    sql.execute("update allocation_policy set effective_to = ?::timestamptz where id = ?", moment, id);
    return new AllocationPolicySaved(
        id,
        AllocationMethod.valueOf(r.get("method", String.class)),
        from,
        moment,
        null,
        members(id).size(),
        "已作废：本档自 " + moment + " 起不再生效。行本身保留，需要回看当时的算法时查得到。"
            + "若此刻没有别的在效档，解析会如实报「未配置」而不是静默按平均分配处理 —— "
            + "没有兜底档就没有统一分摊算法。");
  }

  // ============================================================ 销量登记

  /**
   * 登记一条店铺历史销量。
   *
   * <p>它是本服务**唯一的销量真相来源**（V68 文件头）：库存流水没有店铺维度，
   * 订单域未交付。所以这里是显式的登记写入，而不是假装从某处算出来。
   */
  @Transactional
  public AllocationShopSalesSaved recordSales(AllocationShopSalesInput input, UUID actorId) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    if (input.sourceSystem() == null || input.sourceSystem().isBlank()) {
      throw bad("sourceSystem 不能为空：它是跨服务定位键，"
          + "店铺在 channel、SKU 在 catalog，本服务不建外键也不查它们的表");
    }
    if (input.periodEnd().isBefore(input.periodStart())) {
      throw bad("销量区间倒置：" + input.periodStart() + " ~ " + input.periodEnd()
          + "，结束日早于开始日");
    }
    if (input.unitsSold() == null || input.unitsSold().signum() < 0) {
      throw bad("销量不能为负数，收到 " + input.unitsSold());
    }
    UUID id = UUID.randomUUID();
    BigDecimal units = input.unitsSold().setScale(6, RoundingMode.HALF_UP);
    // 同一店铺同一 SKU 同一区间只登记一次：重复登记会把这段时间的销量算两遍，
    // 比例分配随之偏掉，而界面上看不出任何异常。
    sql.execute(
        "insert into allocation_shop_sales (id,tenant_id,source_system,reference_id,reference_label,"
            + "sku_id,period_start,period_end,units_sold,recorded_by_membership_id) "
            + "values (?,?,?,?,?,?,?,?,?::numeric,?)",
        id,
        tenant(),
        input.sourceSystem().trim(),
        input.referenceId(),
        input.referenceLabel() == null ? "" : input.referenceLabel().trim(),
        input.skuId(),
        input.periodStart(),
        input.periodEnd(),
        units,
        actorId);
    return new AllocationShopSalesSaved(
        id,
        input.referenceId(),
        input.skuId(),
        input.periodStart(),
        input.periodEnd(),
        units,
        "已登记 " + units.toPlainString() + " 件（" + input.periodStart() + " ~ " + input.periodEnd()
            + "）。比例分配按策略配置的窗口回看这些登记；同一店铺同一 SKU 同一区间不允许重复登记，"
            + "否则这段时间会被算两遍而界面上看不出异常。");
  }

  /** 某 SKU 近 N 天的销量名册（按店铺聚合，窗口内无登记的店铺不在列）。 */
  public List<SaleRow> sales(UUID skuId, LocalDate from, LocalDate to) {
    if (skuId == null) {
      throw bad("请给出 skuId：销量按 SKU × 店铺聚合，空 SKU 没有可回答的问题");
    }
    if (from == null || to == null || to.isBefore(from)) {
      throw bad("销量回看区间非法：" + from + " ~ " + to + "（结束日不得早于开始日）");
    }
    return sql.fetch(
            "select s.reference_id, s.reference_label, sum(s.units_sold) as units,"
                + " count(*) as records from allocation_shop_sales s"
                + " where s.sku_id = ? and s.period_end >= ? and s.period_start <= ?"
                + " group by s.reference_id, s.reference_label"
                + " order by units desc, s.reference_id",
            skuId, from, to)
        .map(
            r ->
                new SaleRow(
                    r.get("reference_id", UUID.class),
                    nullToEmpty(r.get("reference_label", String.class)),
                    decimal(r, "units"),
                    r.get("records", Long.class)));
  }

  // ============================================================ 生效值解析

  /**
   * 解析某一时刻真正生效的策略。
   *
   * <p>优先级 SKU &gt; SHOP &gt; DEFAULT（与 17.5.1.1、7.2.2.1 同一套分档约定），命中即止，
   * 这样「回退到兜底档」是一个**如实报出来的状态**（{@code usedFallback}）而不是猜测。
   *
   * <p>三个分支各写一条 SQL，而不是一条 {@code (? is null or ...)}：后者既让 PG 反推不出
   * 参数类型，又要把 cast 放到末位绑定 —— 正是 7.1.2.2 踩过的那条纪律。
   */
  public AllocationPolicyEffective effective(String scopeType, UUID referenceId, UUID skuId, OffsetDateTime at) {
    OffsetDateTime moment = at == null ? OffsetDateTime.now() : at;
    AllocationScopeType requested =
        notBlank(scopeType) ? scope(scopeType) : AllocationScopeType.SKU;

    // SKU 档最具体：一个爆款 SKU 的分摊方式与其余 SKU 不同是常态。
    if (requested == AllocationScopeType.SKU && skuId != null) {
      var skuHit = liveRow(moment, AllocationScopeType.SKU.name(), skuId);
      if (skuHit != null) {
        return new AllocationPolicyEffective(
            skuHit, AllocationScopeType.SKU, false, moment, directNote(AllocationScopeType.SKU, skuHit));
      }
    }
    if (referenceId != null) {
      var shopHit = liveRow(moment, AllocationScopeType.SHOP.name(), referenceId);
      if (shopHit != null) {
        return new AllocationPolicyEffective(
            shopHit, AllocationScopeType.SHOP, false, moment, directNote(AllocationScopeType.SHOP, shopHit));
      }
    }
    var fallback = liveRow(moment, AllocationScopeType.DEFAULT.name(), null);
    if (fallback != null) {
      // 调用方点名要兜底档时，命中兜底档**不算**回退 —— 回退的含义是
      // 「你要的那一档没有，我换了算法」，而不是「你就要的这一档正好命中了」。
      boolean asked = requested == AllocationScopeType.DEFAULT;
      return new AllocationPolicyEffective(
          fallback,
          AllocationScopeType.DEFAULT,
          !asked,
          moment,
          asked
              ? "直接命中全租户兜底档「" + fallback.referenceLabel() + "」。"
              : "未命中 " + requested.label() + "档"
                  + (requested == AllocationScopeType.SKU ? "（skuId=" + skuId + "）" : "（referenceId=" + referenceId + "）")
                  + "，已回退到全租户兜底档。回退在这里是如实报出的状态而不是猜测："
                  + "界面与任何下游都必须知道自己用的是兜底算法。");
    }
    return new AllocationPolicyEffective(
        null,
        AllocationScopeType.DEFAULT,
        false,
        moment,
        "没有找到生效的分摊策略（已依次查过 SKU 档、店铺档与全租户兜底档）。"
            + "这是必须显式处理的空状态：多平台共享库存没有统一分摊算法，"
            + "此时各店各自判断有没有货，结果就是『所有店铺都以为有货』的超卖。");
  }

  private String directNote(AllocationScopeType type, AllocationPolicyRow hit) {
    return "命中 " + type.label() + "档「" + hit.referenceLabel() + "」：" + hit.method().label()
        + "，缓冲 " + bufferText(hit.bufferMode(), hit.bufferRatio(), hit.bufferQuantity())
        + "，参与店铺 " + members(hit.id()).size() + " 个。";
  }

  /** 某一时刻某一档的在效行；没有则 null。 */
  private AllocationPolicyRow liveRow(OffsetDateTime at, String scopeType, UUID referenceId) {
    boolean fallback = AllocationScopeType.DEFAULT.name().equals(scopeType);
    var statement =
        WIN + " select " + COLUMNS + " from allocation_policy p, win where " + (fallback
                ? "p.scope_type = 'DEFAULT' and "
                : "p.scope_type = ? and p.reference_id = ? and ")
            + LIVE_AT
            + " order by p.effective_from desc, p.id limit 1";
    var r =
        sql.fetchOne(statement, fallback ? new Object[] {at} : new Object[] {at, scopeType, referenceId});
    return r == null ? null : row(r);
  }

  // ============================================================ 分摊

  /**
   * 发起一次分摊（试算或落库）。
   *
   * <p>{@code persist=false} 是<b>试算</b>：算完就走，一行都不写，让运营在配策略时先看结果。
   * {@code persist=true} 落库并写出 {@code allocation_run} + {@code allocation_run_line} ——
   * 下游 7.8.2.1 分摊结果查看读的就是这两张表，而超卖复盘要回答
   * 「当时到底按哪一档、当时的销量是多少算出来的」，现算只能算出**现在**的结果。
   *
   * <p>可用量：调用方给了就用它（它往往已经按 7.2.2.1 的共享池口径算好了）；
   * 没给则由本服务按同一口径现算（合格可售可用量 + 含在途时发往该仓的在途）。
   */
  @Transactional
  public List<AllocationResult> allocate(AllocationRequest request, UUID actorId) {
    if (request == null) {
      throw bad("请求体不能为空");
    }
    List<UUID> skuIds = normalize(request.skuIds());
    if (skuIds.isEmpty()) {
      throw bad("请至少传一个 SKU：分摊是「这批货分给谁」，空集合没有可回答的问题");
    }
    if (skuIds.size() > MAX_SKUS) {
      throw bad("一次最多算 " + MAX_SKUS + " 个 SKU，收到 " + skuIds.size());
    }
    OffsetDateTime at = request.at() == null ? OffsetDateTime.now() : request.at();
    boolean persist = Boolean.TRUE.equals(request.persist());

    var resolved = effective(request.scopeType(), request.referenceId(), skuIds.get(0), at);
    if (resolved.policy() == null) {
      // 空状态如实返回：调用方据此拒绝下单，而不是让各店各自判断有没有货。
      return List.of(emptyResult(resolved, skuIds, at));
    }
    AllocationPolicyRow policy = resolved.policy();
    List<AllocationPolicyMember> roster = members(policy.id());
    if (roster.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND,
          "策略 " + policy.id() + " 没有成员店铺：成员全被删掉时它分不出任何结果，"
              + "而这正是蓝图说的「某个店铺一直抢不到货」。请开一档带成员的新策略。");
    }

    LocalDate windowEnd = at.toLocalDate();
    LocalDate windowStart = windowEnd.minusDays(policy.salesWindowDays());

    // 各店的登记销量一次查完，供全部 SKU 复用（每个 SKU 一轮查询会放大成 N 次往返）。
    Map<UUID, Map<UUID, BigDecimal>> salesBySku = new LinkedHashMap<>();
    for (UUID skuId : skuIds) {
      salesBySku.put(skuId, salesIndex(skuId, windowStart, windowEnd));
    }
    Map<UUID, String> skuCodes = skuCodes(skuIds);

    List<AllocationResult> out = new ArrayList<>(skuIds.size());
    for (UUID skuId : skuIds) {
      BigDecimal available =
          request.availableQuantity() != null
              ? whole(request.availableQuantity())
              : poolCapacity(skuId, at);
      out.add(
          persist
              ? runOnce(policy, resolved, skuId, skuCodes.get(skuId), roster, salesBySku.get(skuId),
                  windowStart, windowEnd, available, at, actorId)
              : preview(policy, resolved, skuId, skuCodes.get(skuId), roster, salesBySku.get(skuId),
                  windowStart, windowEnd, available, at));
    }
    return out;
  }

  /**
   * 算一次但不落库。
   *
   * <p>守恒式在这里同样成立（available = buffer + allocatable，allocatable = allocated +
   * unallocated），所以试算看到的数与落库后的数**逐字相同** —— 两套算法才是最怕的。
   */
  private AllocationResult preview(
      AllocationPolicyRow policy,
      AllocationPolicyEffective resolved,
      UUID skuId,
      String skuCode,
      List<AllocationPolicyMember> roster,
      Map<UUID, BigDecimal> sales,
      LocalDate windowStart,
      LocalDate windowEnd,
      BigDecimal available,
      OffsetDateTime at) {
    Outcome outcome = compute(policy, roster, sales, available);
    return new AllocationResult(
        resolved,
        null,
        skuId,
        skuCode,
        outcome.lines(),
        outcome.available(),
        outcome.buffer(),
        outcome.allocatable(),
        outcome.allocated(),
        outcome.unallocated(),
        windowStart,
        windowEnd,
        outcome.totalSales(),
        outcome.totalShare(),
        roster.size(),
        outcome.starved(),
        outcome.note() + " 本次为**试算**：一行都没有写，配策略时先用它看结果，确认后再落库。");
  }

  /** 算一次并落库（头 + 逐店行，一个事务）。DB 的 CHECK 与延迟触发器会核对守恒。 */
  private AllocationResult runOnce(
      AllocationPolicyRow policy,
      AllocationPolicyEffective resolved,
      UUID skuId,
      String skuCode,
      List<AllocationPolicyMember> roster,
      Map<UUID, BigDecimal> sales,
      LocalDate windowStart,
      LocalDate windowEnd,
      BigDecimal available,
      OffsetDateTime at,
      UUID actorId) {
    Outcome outcome = compute(policy, roster, sales, available);
    UUID runId = UUID.randomUUID();
    // 头一次写全：allocated_total 与 unallocated_quantity 一起落，
    // 守恒式由行级 CHECK 当场核对（Σ行 == allocated_total 由延迟触发器在提交时核对）。
    sql.execute(
        "insert into allocation_run (id,tenant_id,policy_id,method,sku_id,allocated_at,"
            + "sales_window_start,sales_window_end,available_quantity,buffer_quantity,"
            + "allocatable_quantity,allocated_total,unallocated_quantity,note,"
            + "created_by_membership_id) values (?,?,?,?,?,?::timestamptz,?,?,?::numeric,"
            + "?::numeric,?::numeric,?::numeric,?::numeric,?,?)",
        runId,
        tenant(),
        policy.id(),
        policy.method().name(),
        skuId,
        at,
        windowStart,
        windowEnd,
        outcome.available(),
        outcome.buffer(),
        outcome.allocatable(),
        outcome.allocated(),
        outcome.unallocated(),
        outcome.note(),
        actorId);

    int order = 0;
    for (AllocationLine line : outcome.lines()) {
      order++;
      // sales_share 恒定绑定一个占位符（非比例分配时绑 null）：按「值是否为 null」
      // 拼 SQL 会让同一段代码有两种语句形状，而少一个逗号就是一条语法错误 ——
      // 这正是分摊批次可列出并下钻那个用例踩到的坑。
      sql.execute(
          "insert into allocation_run_line (tenant_id,run_id,source_system,reference_id,"
              + "reference_label,shop_operating_mode,allocation_order,sales_units,sales_share,"
              + "allocated_qty,outcome) values (?,?,?,?,?,?,?,?::numeric,?::numeric,"
              + "?::numeric,?)",
          tenant(),
          runId,
          line.sourceSystem(),
          line.referenceId(),
          line.referenceLabel(),
          line.shopOperatingMode(),
          order,
          line.salesUnits(),
          line.salesShare(),
          line.allocatedQty(),
          line.outcome().name());
    }

    return new AllocationResult(
        resolved,
        new AllocationRunRef(runId, at, true),
        skuId,
        skuCode,
        outcome.lines(),
        outcome.available(),
        outcome.buffer(),
        outcome.allocatable(),
        outcome.allocated(),
        outcome.unallocated(),
        windowStart,
        windowEnd,
        outcome.totalSales(),
        outcome.totalShare(),
        roster.size(),
        outcome.starved(),
        outcome.note() + " 已落库为分摊批次 " + runId + "（append-only，改不了也删不掉）："
            + "下游 7.8.2.1 分摊结果查看读的就是它，超卖复盘要回答「当时按哪一档、当时销量多少」"
            + "也靠它。");
  }

  // ============================================================ 算法

  /** 一次计算的全部中间量。守恒式在这一个对象里成立，别处不再重复推导。 */
  private record Outcome(
      List<AllocationLine> lines,
      BigDecimal available,
      BigDecimal buffer,
      BigDecimal allocatable,
      BigDecimal allocated,
      BigDecimal unallocated,
      BigDecimal totalSales,
      BigDecimal totalShare,
      long starved,
      String note) {}

  /**
   * <b>整个模块唯一的一套分摊算法</b>。三种方法都在这里算，别处不重复实现。
   *
   * <p>算式（与 {@link AllocationPolicyModels} 类注释一一对应）：
   *
   * <pre>
   *   buffer      = RATIO: trunc(available × ratio) | QUANTITY: min(quantity, available)
   *   allocatable = available − buffer
   *   PROPORTIONAL_SALES: trunc(allocatable × share_i) 逐个向下取整，
   *                      余数按「份额小数部分从大到小」逐件补发（同小数按店铺 id 升序）
   *   FIXED_PRIORITY / SHOP_TYPE_PRIORITY: 按次序取 min(trunc(allocatable × shareLimit_i), 剩余)
   * </pre>
   *
   * <p>Σ分摊 <b>永远 ≤</b> allocatable：比例分配靠「逐个向下取整 + 余数按小数部分补发」保证，
   * 先到先得靠「每一步都取 min(上限, 剩余)」保证。差额显式落在 {@code unallocated}
   * 而不是被四舍五入吃掉 —— 那正是蓝图红线里「总账对不上」的来源。
   */
  private Outcome compute(
      AllocationPolicyRow policy,
      List<AllocationPolicyMember> roster,
      Map<UUID, BigDecimal> sales,
      BigDecimal available) {
    BigDecimal buffer =
        policy.bufferMode() == AllocationBufferMode.RATIO
            ? available.multiply(policy.bufferRatio()).setScale(QTY_SCALE, RoundingMode.DOWN)
            : policy.bufferQuantity().min(available);
    buffer = whole(buffer);
    BigDecimal allocatable = available.subtract(buffer);

    // 成员拆成「参与」与「停用」两拨。停用的**仍然出现在结果里**（份额恒 0、原因写明
    // DISABLED）—— 把它从结果里删掉，界面上就会「凭空少一个店」，而用户真正要问的是
    // 「为什么这个店这次没有份」。
    List<AllocationPolicyMember> active = new ArrayList<>();
    List<AllocationPolicyMember> disabled = new ArrayList<>();
    for (AllocationPolicyMember m : roster) {
      (m.enabled() ? active : disabled).add(m);
    }

    BigDecimal totalSales = ZERO;
    for (AllocationPolicyMember m : active) {
      totalSales = totalSales.add(sales.getOrDefault(m.referenceId(), ZERO));
    }
    boolean salesUsable = totalSales.signum() > 0;

    // 落库用的份额：6 位小数（DB 列 numeric(9,6)）。
    // 算份额时保留 9 位再落到 6 位 —— 份额是比值，少留几位会在大额上差出好几件。
    Map<UUID, BigDecimal> shares = new LinkedHashMap<>();
    if (policy.method() == AllocationMethod.PROPORTIONAL_SALES && salesUsable) {
      for (AllocationPolicyMember m : active) {
        shares.put(
            m.referenceId(),
            sales.getOrDefault(m.referenceId(), ZERO)
                .divide(totalSales, 9, RoundingMode.HALF_UP)
                .setScale(6, RoundingMode.HALF_UP));
      }
    }

    // 两种方法在这里分道：比例分配是「先各自向下取整、再统一补余数」，
    // 先到先得是「按次序逐个取 min(上限, 剩余)」。两者的余数来源不同，混在一起写必然算错。
    List<AllocationLine> lines = new ArrayList<>(roster.size());
    BigDecimal allocatedTotal;
    BigDecimal unallocated;
    if (policy.method() == AllocationMethod.PROPORTIONAL_SALES) {
      Proportional split = splitProportionally(allocatable, ordered(active, policy), shares, sales);
      lines.addAll(split.lines());
      allocatedTotal = split.allocated();
      unallocated = split.unallocated();
    } else {
      FirstCome split = splitFirstCome(allocatable, ordered(active, policy), sales, shares);
      lines.addAll(split.lines());
      allocatedTotal = split.allocated();
      unallocated = split.unallocated();
    }

    int order = lines.size();
    for (AllocationPolicyMember m : disabled) {
      order++;
      lines.add(new AllocationLine(
          m.sourceSystem(), m.referenceId(), m.referenceLabel(), m.shopOperatingMode(),
          order, sales.getOrDefault(m.referenceId(), ZERO), shares.get(m.referenceId()),
          ZERO_QTY, AllocationOutcome.DISABLED));
    }

    long starved = lines.stream().filter(l -> l.allocatedQty().signum() == 0).count();
    BigDecimal totalShare = null;
    if (policy.method() == AllocationMethod.PROPORTIONAL_SALES && !shares.isEmpty()) {
      BigDecimal sum = ZERO;
      for (BigDecimal s : shares.values()) {
        sum = sum.add(s);
      }
      totalShare = sum;
    }

    return new Outcome(
        lines,
        available,
        buffer,
        allocatable,
        allocatedTotal,
        unallocated,
        totalSales,
        totalShare,
        starved,
        noteFor(policy, available, buffer, allocatable, allocatedTotal, unallocated,
            totalSales, salesUsable, starved, roster.size()));
  }

  /**
   * 参与成员的全序。
   *
   * <p>比例分配不靠次序决定结果（结果只由份额决定），但**展示次序仍然要稳定** ——
   * 否则两次同样输入的分摊，界面上行序会跳动，用户会以为算法不确定。
   */
  private static List<AllocationPolicyMember> ordered(
      List<AllocationPolicyMember> active, AllocationPolicyRow policy) {
    List<AllocationPolicyMember> list = new ArrayList<>(active);
    if (policy.method() == AllocationMethod.SHOP_TYPE_PRIORITY) {
      Map<String, Integer> ranks = typeRanks(policy.shopTypeOrder());
      list.sort(
          Comparator.comparingInt((AllocationPolicyMember m) ->
                  ranks.getOrDefault(m.shopOperatingMode(), Integer.MAX_VALUE))
              .thenComparingInt(AllocationPolicyMember::priority)
              .thenComparing(AllocationPolicyMember::referenceId));
    } else if (policy.method() == AllocationMethod.FIXED_PRIORITY) {
      list.sort(
          Comparator.comparingInt(AllocationPolicyMember::priority)
              .thenComparing(AllocationPolicyMember::referenceId));
    } else {
      list.sort(Comparator.comparing(AllocationPolicyMember::referenceId));
    }
    return list;
  }

  /** 一次比例分配的产物。 */
  private record Proportional(List<AllocationLine> lines, BigDecimal allocated, BigDecimal unallocated) {}

  /** 一次先到先得分摊的产物。 */
  private record FirstCome(List<AllocationLine> lines, BigDecimal allocated, BigDecimal unallocated) {}

  /**
   * <b>按销量占比比例分配</b>：先各自向下取整，再把余数按小数部分从大到小补发。
   *
   * <p>两步法的理由：直接对每个份额四舍五入，会出现 Σ分摊 &gt; 可分配量（超卖），
   * 也会出现 Σ分摊 &lt; 可分配量却无人认领（凭空丢货）。先取整再补余数则保证
   *
   * <pre>
   *   Σ分摊 ≤ 可分配量   恒成立（余数最多就是成员数，而每步都从 remaining 里扣）
   *   Σ分摊 = 可分配量 − 余数，而余数 &lt; 成员数   （所以「刚好分完」是常态，
   *                                                「分不满」只发生在可分配量 &lt; 成员数时）
   * </pre>
   *
   * <p>补发判据是「被向下取整吃掉的小数部分」，小数部分大 = 欠得最多。
   * 同小数按店铺 id 升序 —— 没有这个决胜键，两次同样输入可能补给不同的人，
   * 分摊结果就不可复现，超卖复盘也无从重算。
   */
  private Proportional splitProportionally(
      BigDecimal allocatable,
      List<AllocationPolicyMember> ordered,
      Map<UUID, BigDecimal> shares,
      Map<UUID, BigDecimal> sales) {
    List<AllocationLine> lines = new ArrayList<>(ordered.size());
    if (shares.isEmpty()) {
      // 全体销量为 0：比例分配没有分母。此时**显式报零**而不是均分 ——
      // 均分会让零销量店铺凭空拿到货，那不是「按销量占比分配」。
      int index = 1;
      for (AllocationPolicyMember m : ordered) {
        lines.add(new AllocationLine(
            m.sourceSystem(), m.referenceId(), m.referenceLabel(), m.shopOperatingMode(),
            index++, sales.getOrDefault(m.referenceId(), ZERO), null, ZERO_QTY,
            AllocationOutcome.NO_SALES));
      }
      return new Proportional(lines, ZERO_QTY, allocatable);
    }

    // 第一步：逐个向下取整，并记下被吃掉的小数部分。
    Map<UUID, BigDecimal> floorByShop = new LinkedHashMap<>();
    Map<UUID, BigDecimal> fractionByShop = new LinkedHashMap<>();
    BigDecimal allocated = ZERO_QTY;
    for (AllocationPolicyMember m : ordered) {
      BigDecimal share = shares.getOrDefault(m.referenceId(), ZERO);
      BigDecimal exact = allocatable.multiply(share);
      BigDecimal floor = whole(exact);
      floorByShop.put(m.referenceId(), floor);
      fractionByShop.put(m.referenceId(), exact.subtract(floor));
      allocated = allocated.add(floor);
    }

    // 第二步：余数按小数部分从大到小补发（同小数按 id 升序），逐件扣。
    BigDecimal remainder = allocatable.subtract(allocated);
    BigDecimal distributed = ZERO_QTY;
    List<AllocationPolicyMember> byFraction = new ArrayList<>(ordered);
    byFraction.sort(
        Comparator.comparing((AllocationPolicyMember m) -> fractionByShop.getOrDefault(m.referenceId(), ZERO))
            .reversed()
            .thenComparing(AllocationPolicyMember::referenceId));
    for (AllocationPolicyMember m : byFraction) {
      if (remainder.signum() <= 0) {
        break;
      }
      floorByShop.put(m.referenceId(), floorByShop.get(m.referenceId()).add(BigDecimal.ONE));
      remainder = remainder.subtract(BigDecimal.ONE);
      distributed = distributed.add(BigDecimal.ONE);
    }
    // 补发的件必须同时计入分摊合计：漏了这一步就会出现「头里的 Σ 与行的 Σ 不相等」，
    // 而那正是 V68 的守恒触发器要挡的错账。
    allocated = allocated.add(distributed);

    int index = 1;
    for (AllocationPolicyMember m : ordered) {
      BigDecimal take = floorByShop.get(m.referenceId());
      AllocationOutcome outcome;
      if (take.signum() > 0) {
        outcome = AllocationOutcome.OK;
      } else {
        // 分到 0 件的原因要能区分：是这个店销量为 0，还是货本来就不够分。
        outcome = sales.getOrDefault(m.referenceId(), ZERO).signum() == 0
            ? AllocationOutcome.NO_SALES
            : AllocationOutcome.DEMAND_EXHAUSTED;
      }
      lines.add(new AllocationLine(
          m.sourceSystem(), m.referenceId(), m.referenceLabel(), m.shopOperatingMode(),
          index++, sales.getOrDefault(m.referenceId(), ZERO),
          shares.get(m.referenceId()), take, outcome));
    }
    return new Proportional(lines, allocated, remainder);
  }

  /**
   * <b>先到先得</b>（固定优先级与店铺类型优先级共用这一段）。
   *
   * <p>每一步都取 {@code min(份额上限, 剩余)}，所以 Σ分摊 ≤ 可分配量 恒成立。
   * 份额上限为空 = 不设上限 = 拿走全部剩余 —— 这在业务上几乎总是错的，
   * 所以它必须是一个界面上能被看见的显式取值，而不是一个「忘了填」的默认值。
   *
   * <p>两种方法的差别只在**次序**：店铺类型优先级先按 {@code shopTypeOrder} 里的名次，
   * 同类型内再按成员优先级（次序由 {@link #ordered} 决定，算法本身不再关心）。
   */
  private FirstCome splitFirstCome(
      BigDecimal allocatable,
      List<AllocationPolicyMember> ordered,
      Map<UUID, BigDecimal> sales,
      Map<UUID, BigDecimal> shares) {
    List<AllocationLine> lines = new ArrayList<>(ordered.size());
    BigDecimal remaining = allocatable;
    BigDecimal allocated = ZERO_QTY;
    int index = 1;
    for (AllocationPolicyMember m : ordered) {
      BigDecimal units = sales.getOrDefault(m.referenceId(), ZERO);
      BigDecimal take;
      AllocationOutcome outcome;
      if (m.shareLimit() == null) {
        take = remaining;
        outcome = take.signum() > 0 ? AllocationOutcome.OK : AllocationOutcome.DEMAND_EXHAUSTED;
      } else {
        BigDecimal cap = whole(allocatable.multiply(m.shareLimit()));
        take = cap.min(remaining);
        if (take.signum() == 0) {
          outcome = AllocationOutcome.DEMAND_EXHAUSTED;
        } else if (take.compareTo(cap) == 0 && remaining.subtract(take).signum() > 0) {
          // 触到自己的上限而货还有剩：封顶生效，如实标出来，
          // 否则界面上会以为「它只要就要得到这么多」。
          outcome = AllocationOutcome.CAP_REACHED;
        } else {
          outcome = AllocationOutcome.OK;
        }
      }
      take = take.min(remaining);
      remaining = remaining.subtract(take);
      allocated = allocated.add(take);
      lines.add(new AllocationLine(
          m.sourceSystem(), m.referenceId(), m.referenceLabel(), m.shopOperatingMode(),
          index++, units, shares.get(m.referenceId()), take, outcome));
    }
    return new FirstCome(lines, allocated, remaining);
  }


  /** 比例分配的说明：把守恒式与「为什么这么分」写在返回里，界面上不必再解释一遍。 */
  private String noteFor(
      AllocationPolicyRow policy,
      BigDecimal available,
      BigDecimal buffer,
      BigDecimal allocatable,
      BigDecimal allocated,
      BigDecimal unallocated,
      BigDecimal totalSales,
      boolean salesUsable,
      long starved,
      int rosterSize) {
    String head =
        policy.method().label() + "：可用 " + available.toPlainString() + " 件，缓冲留 "
            + buffer.toPlainString() + " 件，可分配 " + allocatable.toPlainString() + " 件；"
            + "逐店分摊合计 " + allocated.toPlainString() + " 件，未分配 "
            + unallocated.toPlainString() + " 件。";
    String tail =
        switch (policy.method()) {
          case PROPORTIONAL_SALES -> salesUsable
              ? "按各店登记销量合计 " + totalSales.toPlainString() + " 件的占比分摊，"
                  + "逐个向下取整、余数按小数部分从大到小补发，Σ分摊 ≤ 可分配量恒成立。"
              : "**窗口内没有任何销量登记**（合计 0 件）：比例分配没有分母，结果逐店为 0。"
                  + "如实返回 0 而不是均分 —— 均分会让零销量店铺凭空拿到货，"
                  + "那不是「按销量占比分配」。请先登记销量或改用先到先得类方法。";
          case FIXED_PRIORITY ->
              "按成员优先级从高到低先到先得，每个成员最多拿可分配量 × 其份额上限。"
                  + "未设份额上限的成员会拿走全部剩余 —— 配置时务必逐个设上限。";
          case SHOP_TYPE_PRIORITY ->
              "先按店铺类型顺序（" + policy.shopTypeOrder() + "）排队，同类型内再按成员优先级。"
                  + "类型顺序是策略上的配置而不是写死的常量：货权是谁的货谁优先，是经营决策。";
        };
    String starve =
        starved > 0
            ? rosterSize + " 个成员里有 " + starved + " 个这次分到 0 件，每行都标了原因"
                + "（触顶 / 被前面的分完 / 销量为 0 / 已停用）。"
                + "这正是蓝图说的「某个店铺一直抢不到货」—— 原因可查，才谈得上修。"
            : "全部成员都分到了货。";
    return head + tail + starve;
  }

  /** 店铺类型名次表。找不到的类型排最后（不静默丢掉成员）。 */
  private static Map<String, Integer> typeRanks(String order) {
    Map<String, Integer> ranks = new LinkedHashMap<>();
    if (order == null) {
      return ranks;
    }
    String[] parts = order.split(",");
    for (int i = 0; i < parts.length; i++) {
      ranks.put(parts[i].trim().toUpperCase(Locale.ROOT), i);
    }
    return ranks;
  }

  // ============================================================ 分摊结果

  /** 分摊批次列表（真分页；行数与未分到货的行数由明细现算）。 */
  public AllocationRunPage runs(UUID skuId, String method, int page, int size) {
    int pageSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size <= 0 ? 20 : size));
    int pageNo = Math.max(0, page);
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    if (skuId != null) {
      where.add("r.sku_id = ?");
      binds.add(skuId);
    }
    if (notBlank(method)) {
      where.add("r.method = ?");
      binds.add(method.trim().toUpperCase(Locale.ROOT));
    }
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    long total =
        sql.fetchOne("select count(*) from allocation_run r" + sqlWhere, binds.toArray())
            .get(0, Long.class);

    var bindsPage = new ArrayList<>(binds);
    bindsPage.add(pageSize);
    bindsPage.add((long) pageNo * pageSize);
    var rows =
        sql.fetch(
            "select r.id,r.policy_id,p.reference_label as policy_label,r.method,r.sku_id,"
                + "k.code as sku_code,r.allocated_at,r.available_quantity,r.buffer_quantity,"
                + "r.allocatable_quantity,r.allocated_total,r.unallocated_quantity,"
                + "(select count(*) from allocation_run_line l where l.run_id = r.id) as line_count,"
                + "(select count(*) from allocation_run_line l where l.run_id = r.id"
                + "   and l.allocated_qty = 0) as starved_count"
                + " from allocation_run r"
                + " join allocation_policy p on p.tenant_id = r.tenant_id and p.id = r.policy_id"
                + " left join sku_ref k on k.tenant_id = r.tenant_id and k.id = r.sku_id"
                + sqlWhere
                + " order by r.allocated_at desc, r.id limit ? offset ?",
            bindsPage.toArray());

    return new AllocationRunPage(
        rows.stream().map(AllocationPolicyService::runSummary).toList(), total, pageNo, pageSize);
  }

  /** 分摊批次详情 = 摘要 + 逐店明细。跨租户在 RLS 下查不到 → 404。 */
  public AllocationRunDetail run(UUID runId) {
    var r =
        sql.fetchOne(
            "select r.id,r.policy_id,p.reference_label as policy_label,r.method,r.sku_id,"
                + "k.code as sku_code,r.allocated_at,r.available_quantity,r.buffer_quantity,"
                + "r.allocatable_quantity,r.allocated_total,r.unallocated_quantity,"
                + "(select count(*) from allocation_run_line l where l.run_id = r.id) as line_count,"
                + "(select count(*) from allocation_run_line l where l.run_id = r.id"
                + "   and l.allocated_qty = 0) as starved_count"
                + " from allocation_run r"
                + " join allocation_policy p on p.tenant_id = r.tenant_id and p.id = r.policy_id"
                + " left join sku_ref k on k.tenant_id = r.tenant_id and k.id = r.sku_id"
                + " where r.id = ?",
            runId);
    if (r == null) {
      throw notFound(runId);
    }
    var lines =
        sql.fetch(
                "select l.source_system,l.reference_id,l.reference_label,l.shop_operating_mode,"
                    + "l.allocation_order,l.sales_units,l.sales_share,l.allocated_qty,l.outcome"
                    + " from allocation_run_line l where l.run_id = ? order by l.allocation_order",
                runId)
            .map(
                l ->
                    new AllocationLine(
                        l.get("source_system", String.class),
                        l.get("reference_id", UUID.class),
                        nullToEmpty(l.get("reference_label", String.class)),
                        l.get("shop_operating_mode", String.class),
                        l.get("allocation_order", Integer.class),
                        decimal(l, "sales_units"),
                        decimalOrNull(l, "sales_share"),
                        decimal(l, "allocated_qty"),
                        AllocationOutcome.valueOf(l.get("outcome", String.class))));
    return new AllocationRunDetail(runSummary(r), lines);
  }

  private static AllocationRunSummary runSummary(Record r) {
    return new AllocationRunSummary(
        r.get("id", UUID.class),
        r.get("policy_id", UUID.class),
        nullToEmpty(r.get("policy_label", String.class)),
        AllocationMethod.valueOf(r.get("method", String.class)),
        r.get("sku_id", UUID.class),
        nullToEmpty(r.get("sku_code", String.class)),
        r.get("allocated_at", OffsetDateTime.class),
        decimal(r, "available_quantity"),
        decimal(r, "buffer_quantity"),
        decimal(r, "allocatable_quantity"),
        decimal(r, "allocated_total"),
        decimal(r, "unallocated_quantity"),
        r.get("line_count", Long.class),
        r.get("starved_count", Long.class));
  }

  // ============================================================ 取数

  /** 窗口内各店的登记销量，按店铺 id 索引。窗口内无登记的店铺不出现（取数时按 0 处理）。 */
  private Map<UUID, BigDecimal> salesIndex(UUID skuId, LocalDate from, LocalDate to) {
    Map<UUID, BigDecimal> out = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select s.reference_id, sum(s.units_sold) as units from allocation_shop_sales s"
                + " where s.sku_id = ? and s.period_end >= ? and s.period_start <= ?"
                + " group by s.reference_id",
            skuId, from, to)) {
      out.put(r.get("reference_id", UUID.class), decimal(r, "units"));
    }
    return out;
  }

  /** SKU 编码快照。取不到就留空 —— 编码属 catalog，本服务不联查（跨服务边界）。 */
  private Map<UUID, String> skuCodes(List<UUID> skuIds) {
    Map<UUID, String> out = new LinkedHashMap<>();
    for (Record r :
        sql.fetch("select id, code from sku_ref where id in " + placeholders(skuIds.size()), skuIds.toArray())) {
      out.put(r.get("id", UUID.class), nullToEmpty(r.get("code", String.class)));
    }
    for (UUID id : skuIds) {
      out.putIfAbsent(id, "");
    }
    return out;
  }

  /**
   * 按 7.2.2.1 的池容量口径现算某 SKU 的可用量。
   *
   * <p>口径与 {@code SellablePolicyService.calculate} 完全一致：只计
   * {@code inventory_quality_status.role='SELLABLE'} 的余额（待检、残次被**排除**而不是扣减）。
   *
   * <p>刻意**不**在这里叠加 7.2.2.1 的 buffer_ratio：那是各店回写平台时的口径，由
   * {@code SellablePolicyService} 自己算。本模块再扣一次会让同一批货被两块缓冲各留一次，
   * 余量凭空少一截 —— 那是重复计算，不是更安全。
   */
  private BigDecimal poolCapacity(UUID skuId, OffsetDateTime at) {
    var r =
        sql.fetchOne(
            "select coalesce(sum(b.available), 0) as available from inventory_balance b"
                + " where b.sku_id = ?"
                + "   and exists (select 1 from inventory_quality_status q"
                + "     where q.tenant_id = b.tenant_id and q.id = b.quality_status_id"
                + "     and q.role = 'SELLABLE')",
            skuId);
    BigDecimal available = r == null ? ZERO : decimal(r, "available");
    // 在途：发往本租户任一仓、尚未收货的量。是否计入由 7.2.2.1 的口径决定，
    // 这里按「含在途」的上限取，因为调用方没给可用量时通常就是要一个上限。
    var transit =
        sql.fetchOne(
            "select coalesce(sum(t.quantity), 0) as in_transit from inventory_transfer t"
                + " where t.sku_id = ? and t.status = 'IN_TRANSIT'",
            skuId);
    BigDecimal sum = available.add(transit == null ? ZERO : decimal(transit, "in_transit"));
    return whole(sum.setScale(QTY_SCALE, RoundingMode.DOWN));
  }

  private AllocationResult emptyResult(
      AllocationPolicyEffective resolved, List<UUID> skuIds, OffsetDateTime at) {
    return new AllocationResult(
        resolved,
        null,
        skuIds.get(0),
        nullToEmpty(skuCodes(skuIds).get(skuIds.get(0))),
        List.of(),
        ZERO,
        ZERO,
        ZERO,
        ZERO_QTY,
        ZERO_QTY,
        at.toLocalDate().minusDays(30),
        at.toLocalDate(),
        ZERO,
        null,
        0,
        0,
        resolved.note() + " 本次没有算出任何分摊：没有统一算法就没有分摊额，"
            + "调用方应当拒绝据此下单，而不是让各店各自判断有没有货。");
  }

  // ============================================================ 内部工具

  private static AllocationPolicyRow row(Record r) {
    return new AllocationPolicyRow(
        r.get("id", UUID.class),
        AllocationScopeType.valueOf(r.get("scope_type", String.class)),
        r.get("reference_id", UUID.class),
        nullToEmpty(r.get("reference_label", String.class)),
        AllocationMethod.valueOf(r.get("method", String.class)),
        AllocationBufferMode.valueOf(r.get("buffer_mode", String.class)),
        decimal(r, "buffer_ratio"),
        decimal(r, "buffer_quantity"),
        r.get("shop_type_order", String.class),
        r.get("sales_window_days", Integer.class),
        r.get("effective_from", OffsetDateTime.class),
        r.get("effective_to", OffsetDateTime.class),
        nullToEmpty(r.get("note", String.class)),
        r.get("created_by_membership_id", UUID.class),
        r.get("created_at", OffsetDateTime.class));
  }

  /**
   * 校验并规范化店铺类型顺序。
   *
   * <p>必须是五种经营模式的**排列**：顺序是经营决策所以允许配置，
   * 但漏一种、重复一种、写成任意字符串都会让「按店铺类型优先级」解析不出唯一排序 ——
   * V68 的 CHECK 与 {@code valid_shop_type_order} 会在 DB 层再挡一次。
   */
  private static String shopTypeOrder(String raw) {
    String value = raw == null || raw.isBlank() ? DEFAULT_SHOP_TYPE_ORDER : raw.trim();
    List<String> parts =
        java.util.Arrays.stream(value.split(",")).map(String::trim)
            .map(s -> s.toUpperCase(Locale.ROOT)).toList();
    if (parts.size() != SHOP_OPERATING_MODES.size()) {
      throw bad("店铺类型顺序必须恰好列出 " + SHOP_OPERATING_MODES.size() + " 种店铺类型各一次，收到 "
          + parts.size() + " 段（" + value + "）。允许配置顺序是因为「自营优先还是托管优先」是经营决策，"
          + "但漏一种或重复一种都会让「按店铺类型优先级」排不出唯一顺序。");
    }
    for (String part : parts) {
      if (!SHOP_OPERATING_MODES.contains(part)) {
        throw bad("未知的店铺类型：" + part + "（取值：" + SHOP_OPERATING_MODES + "）。"
            + "类型必须与 channel 的 shop.operating_mode（2.3.2.1）一致，"
            + "本服务不联查它的表，类型对不上就等于排错队列。");
      }
    }
    if (new java.util.HashSet<>(parts).size() != parts.size()) {
      throw bad("店铺类型顺序里有重复项（" + value + "）：重复会让同一类型的名次有两个答案");
    }
    return String.join(",", parts);
  }

  /** 成员规范化：逐个校验类型与份额上限，并拒绝同一个店铺出现两次。 */
  private List<AllocationPolicyInput.AllocationPolicyMemberInput> normalizeMembers(
      List<AllocationPolicyInput.AllocationPolicyMemberInput> members, AllocationMethod method) {
    List<AllocationPolicyInput.AllocationPolicyMemberInput> out = new ArrayList<>(members.size());
    var seen = new java.util.LinkedHashSet<UUID>();
    for (AllocationPolicyInput.AllocationPolicyMemberInput m : members) {
      if (m == null || m.referenceId() == null) {
        throw bad("成员清单里有空行：每个店铺都要指明 referenceId 与 shopOperatingMode");
      }
      if (m.sourceSystem() == null || m.sourceSystem().isBlank()) {
        throw bad("成员 " + m.referenceId() + " 缺 sourceSystem："
            + "它是跨服务定位键，店铺在 channel 服务，本服务不建外键也不查它的表");
      }
      if (!seen.add(m.referenceId())) {
        throw bad("店铺 " + m.referenceId() + " 在成员清单里出现了两次："
            + "同一个店出现两次会让「它一共分到几成」有两套答案");
      }
      String mode =
          m.shopOperatingMode() == null ? "" : m.shopOperatingMode().trim().toUpperCase(Locale.ROOT);
      if (!SHOP_OPERATING_MODES.contains(mode)) {
        throw bad("成员 " + m.referenceId() + " 的店铺类型「" + m.shopOperatingMode() + "」不在词表里（"
            + SHOP_OPERATING_MODES + "）：「按店铺类型优先级」真的按它排序，类型对不上就等于排错队列");
      }
      if (m.shareLimit() != null
          && (m.shareLimit().signum() <= 0 || m.shareLimit().compareTo(ONE) > 0)) {
        throw bad("成员 " + m.referenceId() + " 的份额上限必须在 0 与 1 之间（不含 0），收到 "
            + m.shareLimit().toPlainString() + "：上限为 0 等于把它排除出分摊，"
                + "那应该用停用而不是给一个永远为 0 的上限");
      }
      if (method == AllocationMethod.SHOP_TYPE_PRIORITY && m.priority() == null) {
        // 不是错误：同类型内次序相同时用 reference_id 兜底保证全序。
        // 但让调用方知道默认是 100 而不是「没填就随机」。
        out.add(new AllocationPolicyInput.AllocationPolicyMemberInput(
            m.sourceSystem(), m.referenceId(), m.referenceLabel(), mode, 100, m.shareLimit(), m.enabled()));
        continue;
      }
      out.add(new AllocationPolicyInput.AllocationPolicyMemberInput(
          m.sourceSystem(), m.referenceId(), m.referenceLabel(), mode,
          m.priority() == null ? 100 : m.priority(), m.shareLimit(), m.enabled()));
    }
    return out;
  }

  private static boolean sameSlot(Record target, AllocationScopeType scope, UUID referenceId) {
    return target.get("scope_type", String.class).equals(scope.name())
        && Objects.equals(target.get("reference_id", UUID.class), referenceId);
  }

  private static AllocationScopeType scope(String value) {
    try {
      return AllocationScopeType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad("未知的生效范围：" + value + "（取值："
          + java.util.Arrays.toString(AllocationScopeType.values()) + "）");
    }
  }

  private static AllocationMethod method(String value) {
    try {
      return AllocationMethod.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad("未知的分摊方法：" + value + "（取值："
          + java.util.Arrays.toString(AllocationMethod.values()) + "）");
    }
  }

  private static AllocationBufferMode bufferMode(String value) {
    try {
      return AllocationBufferMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad("未知的缓冲口径：" + value + "（取值："
          + java.util.Arrays.toString(AllocationBufferMode.values()) + "）");
    }
  }

  private static String bufferText(AllocationBufferMode mode, BigDecimal ratio, BigDecimal quantity) {
    return mode == AllocationBufferMode.RATIO
        ? percent(ratio)
        : quantity.toPlainString() + " 件";
  }

  private static String percent(BigDecimal ratio) {
    return ratio.multiply(new BigDecimal("100")).stripTrailingZeros().toPlainString() + "%";
  }

  /** 分摊额必须是整件（V68 的 trunc CHECK 在 DB 层再挡一次）。 */
  private static BigDecimal whole(BigDecimal value) {
    return value.setScale(QTY_SCALE, RoundingMode.DOWN);
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

  private static BigDecimal decimalOrNull(Record r, String field) {
    return r.get(field, BigDecimal.class);
  }

  private static List<Object> append(List<Object> base, Object... tail) {
    List<Object> all = new ArrayList<>(base);
    java.util.Collections.addAll(all, tail);
    return all;
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

  /** 租户来自 RLS 的同一个来源，绝不来自任何请求参数或客户端头。 */
  private UUID tenant() {
    return sql.fetchOne("select current_setting('app.current_tenant',true)::uuid as t").get("t", UUID.class);
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException notFound(UUID id) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "分摊策略或分摊批次不存在或不属于当前租户：" + id);
  }
}