package com.starcon.warehouse;

import static com.starcon.warehouse.AllocationPolicyModels.*;
import static com.starcon.warehouse.AllocationPriorityModels.*;

import com.starcon.warehouse.AllocationPolicyModels.AllocationMethod;
import com.starcon.warehouse.AllocationPolicyModels.AllocationPolicyMember;
import com.starcon.warehouse.AllocationPolicyModels.AllocationPolicyRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * 7.8.1.2 优先级与保底：把「货不够分的时候先保住谁」变成可配置、可解释、可复盘的一件事。
 *
 * <p>蓝图：「在分摊冲突时按优先级裁决，并为高价值店铺设置保底可用量与安全垫，防止低优先级店铺长期断货影响其 店铺绩效与平台考核。输出：分摊结果（含被压低店铺的提示）；保底生效记录。」
 *
 * <p><b>五条实现纪律</b>：
 *
 * <ul>
 *   <li><b>算法只有一处</b>：{@link #compute} 是纯函数（不碰 DB、不碰租户上下文）， 试算与落库跑的是**同一个**它。真正的纯函数也让单测不必拉
 *       Testcontainers。
 *   <li><b>保底先划池、余量再分</b>：上界静态、可复现，见 {@link AllocationPriorityModels} 的算式块。
 *   <li><b>区间 + 不可变</b>：保底内容写定后不可改（V76 触发器），改保底 = 收口 + 新开。
 *   <li><b>时间绑定</b>：{@code ?::timestamptz} 只出现在语句最前面的 {@code win} CTE 里， 后面全是普通绑定，cast 永远不在绑定序列末位（与
 *       7.8.1.1、7.1.2.2 同一条纪律）。
 *   <li><b>数量一律 numeric(19,6) + BigDecimal</b>，比较一律 {@code compareTo} 不用 {@code equals}。
 * </ul>
 *
 * <p><b>为什么复用 7.8.1.1 的 {@code effective()} 而不是自己解析生效档</b>：分档解析有三层约定 （SKU &gt; SHOP &gt;
 * DEFAULT、左闭右开、区间不重叠），那套约定已经在 {@code AllocationPolicyService} 里被 V68 的触发器钉住了。再写一份解析器，
 * 两份迟早在某次换档时给出不同答案，而「当时到底听谁的」这个问题没有第二个答案。 所以这里直接调它，成员与销量则自己读（读不构成重复实现，写才是）。
 *
 * <p><b>为什么余量分配要自己算一遍</b>：本模块的余量分配跑在 {@code residual = allocatable − 保底池}
 * 上、且只在**未被保底足额满足**的店铺之间归一化份额 —— 那是 7.8.1.1 没有的输入形态，它的 {@code allocate()} 无参可表达。
 * 代价是两处算式形状相同、必须同步演进；本轮按「只碰自己新增的文件」的纪律没有去改 {@code
 * AllocationPolicyService}，统一成一个共享计算器是后续该做的事，已写进会话日志。
 */
@Service
public class AllocationPriorityService {

  /** 单页最大行数，与 7.8.1.1 同一上限与理由。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 单次分摊最多配多少条保底。成员上限是 200（7.8.1.1 的 MAX_MEMBERS），保底不可能超过成员数。 */
  public static final int MAX_FLOORS = 200;

  /** 数量列在 DB 侧是 numeric(19,6)，而**件数必须���整件**，所以这里的 scale 是 0。 */
  private static final int QTY_SCALE = 0;

  /** numeric(19,6) 口径下的零，避免同一列里混着 scale 不一样的 0。 */
  private static final BigDecimal ZERO = new BigDecimal("0.000000");

  /** 件数口径下的零。 */
  private static final BigDecimal ZERO_QTY = BigDecimal.ZERO;

  private static final BigDecimal ONE = BigDecimal.ONE;

  /** 生效时刻绑定。两个 {@code ?::timestamptz} 刻意放在整条语句最前面。 */
  private static final String WIN = "with win as (select ?::timestamptz as at)";

  /** 保底分档的固定 SELECT 列。 */
  private static final String FLOOR_COLUMNS =
      "f.id,f.policy_id,f.source_system,f.reference_id,f.reference_label,f.floor_mode,"
          + "f.floor_ratio,f.floor_quantity,f.safety_quantity,f.effective_from,f.effective_to,"
          + "f.note,f.created_by_membership_id,f.created_at";

  /** 生效判定谓词 —— 「生效」在本模块只有这一个定义。 */
  private static final String FLOOR_LIVE_AT =
      "(f.effective_from <= win.at and (f.effective_to is null or f.effective_to > win.at))";

  /**
   * 保底写入的列与每列的绑定 cast，<b>由同一个数组生成 SQL</b>。
   *
   * <p>与 7.8.1.1 同一道防线：列顺序与 cast 顺序各写一遍时，错开一位就会让整条语句绑定错位， 而 PG 报的是「invalid input syntax for type
   * numeric」—— 没有一条指向真正的原因。
   */
  private static final String[] FLOOR_INSERT_COLUMNS = {
    "id",
    "tenant_id",
    "policy_id",
    "source_system",
    "reference_id",
    "reference_label",
    "floor_mode",
    "floor_ratio",
    "floor_quantity",
    "safety_quantity",
    "effective_from",
    "note",
    "created_by_membership_id"
  };

  private static final String[] FLOOR_INSERT_CASTS = {
    "", "", "", "", "", "", "", "::numeric", "::numeric", "::numeric", "::timestamptz", "", ""
  };

  private static String floorInsert() {
    if (FLOOR_INSERT_CASTS.length != FLOOR_INSERT_COLUMNS.length) {
      throw new IllegalStateException(
          "保底写入的列与 cast 数量不一致：" + FLOOR_INSERT_COLUMNS.length + " vs " + FLOOR_INSERT_CASTS.length);
    }
    StringJoiner columns = new StringJoiner(",");
    StringJoiner values = new StringJoiner(",");
    for (int i = 0; i < FLOOR_INSERT_COLUMNS.length; i++) {
      columns.add(FLOOR_INSERT_COLUMNS[i]);
      values.add("?" + FLOOR_INSERT_CASTS[i]);
    }
    return "insert into allocation_floor (" + columns + ") values (" + values + ")";
  }

  private final DSLContext sql;
  private final AllocationPolicyService policies;

  public AllocationPriorityService(DSLContext sql, AllocationPolicyService policies) {
    this.sql = sql;
    this.policies = policies;
  }

  // ============================================================ 能力

  /**
   * 保底能力清单。
   *
   * <p>三条必须如实讲清楚的说明：绩效依据为什么不可用、可用量为什么必须由调用方传入、 保底池与另两个「缓冲」的区别。前端据此渲染提示，而不是把规则硬编码在界面上。
   */
  public PriorityCapabilities capabilities() {
    var modes =
        List.of(
            new FloorCapabilityOption(
                "QUANTITY",
                FloorMode.QUANTITY.label(),
                true,
                "按件数保底。高价值店铺的真实诉求通常是「无论卖多少，至少给 N 件」—— " + "平台考核看的是断货天数而不是占比。"),
            new FloorCapabilityOption(
                "RATIO", FloorMode.RATIO.label(), true, "按可分配量的比例保底，适合「任何时候至少给它两成」这种与货量无关的承诺。"));
    var suppress =
        List.of(
            new FloorCapabilityOption(
                "RESERVED_FOR_FLOOR",
                SuppressionReason.RESERVED_FOR_FLOOR.label(),
                true,
                "这台机器上原本没有你的事，是别家店的保底把货占走了 —— " + "处置是调保底或调优先级，与「本来就轮不到它」完全不同。"),
            new FloorCapabilityOption(
                "POOL_EXHAUSTED", SuppressionReason.POOL_EXHAUSTED.label(), true, "货被排在它前面的店铺分完了。"),
            new FloorCapabilityOption(
                "MEMBER_DISABLED",
                SuppressionReason.MEMBER_DISABLED.label(),
                true,
                "该成员已被停用，不参与分摊。"),
            new FloorCapabilityOption(
                "NO_SALES",
                SuppressionReason.NO_SALES.label(),
                true,
                "登记销量为 0，比例分配下必然分不到货（如实如此，不是 bug）。"));
    return new PriorityCapabilities(
        modes,
        suppress,
        AllocationPolicyService.SHOP_OPERATING_MODES,
        false,
        "店铺绩效（17 绩效域）未交付，仓库服务里既没有表也没有授权 API。"
            + "因此保底的依据只有两样真实存在的东西：人定的保底数量/比例与人定的安全垫。"
            + "换句话说——保底是人为承诺的底线，不是系统按绩效算出来的下限。",
        true,
        "本端点要求传入 availableQuantity：7.2.2.1 的池容量口径（合格可售可用量 + 含在途时发往该仓的在途）"
            + "是 7.8.1.1 服务里的私有实现，本模块复制一份只会得到一个迟早与它漂移的副本。"
            + "请传入 7.2.2.1 已经算出的那个数。",
        "本模块有三个互不相同��「留一点」：7.8.1.1 的分摊缓冲（同一批货里压根不参与分摊的量）、"
            + "7.2.2.1 的平台回写缓冲（可回写给平台的件数留多少余量，抵御回写延迟）、"
            + "以及本模块的保底池（货不够分时先保住高价值店铺的底线）。"
            + "三者串联：可分配量 = 可用量 − 分摊缓冲；保底池 + 余量池 = 可分配量。",
        "下游：7.8.2.1 分摊结果查看与 7.8.2.2 缺货与饥饿预警。"
            + "本模块已把「保底没兑现」（status < FULFILLED）与「被压低」（suppressed=true）"
            + "逐店留痕并建了索引，那两个模块可以直接在上面做聚合，不需要重新算一遍分摊。");
  }

  // ============================================================ 保底分档

  /** 保底列表（真分页）。{@code effectiveOnly=true} 只看此刻仍在生效的档。 */
  public AllocationFloorPage page(FloorQuery query) {
    FloorQuery q = query == null ? new FloorQuery(null, null, false, null, 0, 50) : query;
    int page = Math.max(0, q.page());
    int size = Math.min(Math.max(1, q.size()), MAX_PAGE_SIZE);
    String keyword = q.keyword() == null || q.keyword().isBlank() ? null : q.keyword().trim();

    String from =
        " from allocation_floor f, win"
            + " where f.tenant_id = current_setting('app.current_tenant')::uuid"
            + " and (?::uuid is null or f.policy_id = ?::uuid)"
            + " and (?::uuid is null or f.reference_id = ?::uuid)"
            + " and (not ? or "
            + FLOOR_LIVE_AT
            + ")"
            + " and (?::text is null or lower(f.reference_label) like lower('%' || ?::text || '%'))";
    Object[] bind = {
      OffsetDateTime.now(),
      q.policyId(),
      q.policyId(),
      q.referenceId(),
      q.referenceId(),
      q.effectiveOnly(),
      keyword,
      keyword
    };
    // total 与列表**分别**成句：不带 ORDER BY 的 count 与带 ORDER BY 的列表共用一段 SQL 时，
    // `select count(*) from (… order by …) t` 在 PG 里是非法语法，报的却是语法错误而不是意图。
    long total = count(WIN + " select count(*) as c" + from, bind);
    var rows =
        sql.fetch(
            WIN
                + " select "
                + FLOOR_COLUMNS
                + from
                + " order by f.created_at desc, f.id limit ? offset ?",
            bindings(bind, size, page * size));
    return new AllocationFloorPage(
        rows.map(AllocationPriorityService::floorRow), total, page, size);
  }

  /** 保底详情。跨租户在 RLS 下查不到 → 404，不泄露存在性。 */
  public AllocationFloorRow detail(UUID id) {
    var r = sql.fetchOne("select " + FLOOR_COLUMNS + " from allocation_floor f where f.id = ?", id);
    if (r == null) {
      throw notFound(id);
    }
    return floorRow(r);
  }

  /**
   * 新建一档保底（{@code supersedeId} 为 null）或换版（收口旧档 + 开新档）。
   *
   * <p>店铺必须是该策略的**启用成员** —— 由 V76 的 {@code allocation_floor_member} 触发器在 DB 层挡住，
   * 不靠这里自觉。给不在名册里的店铺配保底，货一多就会以保底的名义凭空抢走别人的货， 而界面上查不到是谁拿走的。
   */
  @Transactional
  public AllocationFloorSaved save(UUID supersedeId, AllocationFloorInput input, UUID actorId) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    FloorMode mode = floorMode(input.floorMode());
    BigDecimal ratio = input.floorRatio() == null ? ZERO : scale(input.floorRatio(), 6);
    BigDecimal quantity = input.floorQuantity() == null ? ZERO_QTY : whole(input.floorQuantity());
    BigDecimal safety = input.safetyQuantity() == null ? ZERO_QTY : whole(input.safetyQuantity());

    if (mode == FloorMode.RATIO && quantity.signum() > 0) {
      throw bad(
          "floorMode=RATIO 时不能同时给 floorQuantity：「到底保底保多少」这个问题"
              + "没有唯一答案。请只给 floorRatio，或把口径改成 QUANTITY。");
    }
    if (mode == FloorMode.QUANTITY && ratio.signum() > 0) {
      throw bad("floorMode=QUANTITY 时不能同时给 floorRatio：「到底保底保多少」这个问题" + "没有唯一答案。两个口径只能选一个。");
    }
    if (ratio.signum() < 0 || ratio.compareTo(ONE) > 0) {
      throw bad("floorRatio 必须在 0 ~ 1 之间，收到 " + ratio);
    }
    if (quantity.signum() < 0) {
      throw bad("floorQuantity 不能为负，收到 " + quantity);
    }
    if (safety.signum() < 0) {
      throw bad("safetyQuantity 不能为负，收到 " + safety);
    }
    // 「以为自己配了保底」的形态：两个口径都是 0。算法里它与「没配」完全等价，
    // 而界面上看起来配了 —— 这是最坏的一种错配，在写入这一刻就该拒。
    if (ratio.signum() == 0 && quantity.signum() == 0 && safety.signum() == 0) {
      throw bad("保底数量、比例与安全垫不能同时为 0：这样的保底在算法里与「没配」完全等价，" + "但界面上看起来配了。要取消保底请走作废（retire），不要留一档空的。");
    }

    AllocationFloorRow superseded = null;
    if (supersedeId != null) {
      superseded = detail(supersedeId);
    }
    if (superseded != null && !superseded.policyId().equals(input.policyId())) {
      throw bad(
          "换版不能跨策略：原保底挂在策略 "
              + superseded.policyId()
              + " 上，请求指向 "
              + input.policyId()
              + "。两家店的保底承诺属于两批不同的货。");
    }

    OffsetDateTime from =
        input.effectiveFrom() == null ? OffsetDateTime.now() : input.effectiveFrom();
    OffsetDateTime retireAt = null;
    if (superseded != null) {
      retireAt =
          from.isAfter(superseded.effectiveFrom())
              ? from
              : superseded.effectiveFrom().plusSeconds(1);
      sql.execute(
          "update allocation_floor set effective_to = ?::timestamptz where id = ?",
          retireAt,
          superseded.id());
    }

    UUID id = UUID.randomUUID();
    sql.execute(
        floorInsert(),
        id,
        tenant(),
        input.policyId(),
        input.sourceSystem(),
        input.referenceId(),
        input.referenceLabel() == null ? "" : input.referenceLabel(),
        mode.name(),
        ratio,
        quantity,
        safety,
        from,
        input.note() == null ? "" : input.note(),
        actorId);

    return new AllocationFloorSaved(
        id,
        input.policyId(),
        input.referenceId(),
        mode,
        quantity,
        ratio,
        safety,
        from,
        retireAt,
        superseded == null ? null : superseded.id(),
        superseded == null
            ? "已新建一档保底。注意：保底是策略不是事实，它只在货真的不够分时才会起作用。"
            : "已收口旧档并开出新档。历史那一档原样保留 —— 复盘「当时承诺过什么」全靠它。");
  }

  /**
   * 作废一档保底（收口它的生效区间）。
   *
   * <p>用 POST 而不是 DELETE：作废是审计事件，档位行必须永久保留（V76 的 REVOKE DELETE 挡在权限层）。
   */
  @Transactional
  public AllocationFloorSaved retire(UUID id, OffsetDateTime at) {
    var row = detail(id);
    if (row.effectiveTo() != null) {
      throw bad("这一档保底已经被收口（生效至 " + row.effectiveTo() + "），不允许再次作废或复活");
    }
    // 不传时刻时用**当下**收口，而不是 effective_from + 1s。
    // 那样写的话，刚建好就作废的保底会在往后一秒里仍然生效：
    // 界面上显示「已作废」，裁决却还在给它发货，而这种不一致只在一秒的窗口里出现，
    // 事后根本查不出来。+1s 只在「传入时刻早于生效起点」时才用得上（那时它必须晚于起点）。
    OffsetDateTime now = at == null ? OffsetDateTime.now() : at;
    OffsetDateTime closeAt =
        now.isAfter(row.effectiveFrom()) ? now : row.effectiveFrom().plusSeconds(1);
    sql.execute(
        "update allocation_floor set effective_to = ?::timestamptz where id = ?", closeAt, id);
    return new AllocationFloorSaved(
        id,
        row.policyId(),
        row.referenceId(),
        row.floorMode(),
        row.floorQuantity(),
        row.floorRatio(),
        row.safetyQuantity(),
        row.effectiveFrom(),
        closeAt,
        null,
        "保底已作废（生效至 " + closeAt + "）。它之后不再参与分摊裁决，但这一行永远保留。");
  }

  // ============================================================ 带保底的分摊

  /**
   * 分摊**试算**。一行都不写。
   *
   * <p>与 7.8.1.1 的 {@code /calculation} 同一口径：试算看到的数与落库后的数**逐字相同**， 因为它们跑的是同一个 {@link #compute}。
   */
  public List<PriorityAllocationResult> preview(PriorityAllocationRequest request, UUID actorId) {
    return allocate(request, actorId, false);
  }

  /**
   * 分摊并落库。
   *
   * <p>与 {@link #preview} 分成两个端点而不是一个 {@code persist} 布尔：「看一眼」和 「写一笔事实」的风险等级不同、权限也不同。
   */
  @Transactional
  public List<PriorityAllocationResult> runOnce(PriorityAllocationRequest request, UUID actorId) {
    return allocate(request, actorId, true);
  }

  private List<PriorityAllocationResult> allocate(
      PriorityAllocationRequest request, UUID actorId, boolean persist) {
    if (request == null) {
      throw bad("请求体不能为空");
    }
    BigDecimal available = whole(request.availableQuantity());
    if (available.signum() < 0) {
      throw bad("availableQuantity 不能为负，收到 " + available);
    }
    OffsetDateTime at = request.at() == null ? OffsetDateTime.now() : request.at();

    AllocationPolicyRow policy =
        request.policyId() == null
            ? resolveDefault(request.skuId(), at)
            : loadPolicy(request.policyId());

    LocalDate windowEnd = at.toLocalDate();
    LocalDate windowStart = windowEnd.minusDays(policy.salesWindowDays());

    // 该策略此刻仍在生效的保底，一次读完。V76 的唯一部分索引保证每个店至多一条。
    List<AllocationFloorRow> floors = liveFloors(policy.id(), at);
    if (floors.size() > MAX_FLOORS) {
      throw bad("本策略此刻生效的保底有 " + floors.size() + " 条，超过上限 " + MAX_FLOORS);
    }

    var roster = roster(policy.id());
    if (roster.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "策略 " + policy.id() + " 没有成员店铺：没有名册就没有分摊，保底也无从裁决。");
    }
    Map<UUID, BigDecimal> sales = salesIndex(request.skuId(), windowStart, windowEnd);
    String skuCode = skuCode(request.skuId());

    Outcome outcome =
        compute(policy, roster, sales, grants(floors), available, windowStart, windowEnd);

    PriorityAllocationResult result =
        new PriorityAllocationResult(
            policy.id(),
            policyLabel(policy),
            policy.method().name(),
            null,
            request.skuId(),
            skuCode,
            outcome.lines(),
            outcome.available(),
            outcome.buffer(),
            outcome.allocatable(),
            outcome.floorPool(),
            outcome.residualPool(),
            outcome.allocated(),
            outcome.unallocated(),
            outcome.baseAllocated(),
            roster.size(),
            floors.size(),
            outcome.suppressed(),
            outcome.shortfall(),
            windowStart,
            windowEnd,
            outcome.note(persist));

    if (!persist) {
      return List.of(result);
    }
    return List.of(persistRun(result, outcome, policy, actorId));
  }

  // ------------------------------------------------------------ 落库

  /**
   * 写一次分摊的头 + 逐店行 + 逐店保底生效记录（一个事务）。
   *
   * <p>头与行写进 <b>7.8.1.1 的 {@code allocation_run} / {@code allocation_run_line}</b>，
   * 不是另建一套：本模块不发明第二种「分摊」概念 —— 两个 run id 拼起来的记录迟早对不上， 而 7.8.2.1 要读的正是那一张表。
   */
  private PriorityAllocationResult persistRun(
      PriorityAllocationResult result, Outcome outcome, AllocationPolicyRow policy, UUID actorId) {
    UUID runId = UUID.randomUUID();
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
        result.skuId(),
        result.lines().isEmpty() ? OffsetDateTime.now() : outcome.allocatedAt(),
        result.salesWindowStart(),
        result.salesWindowEnd(),
        result.availableQuantity(),
        result.bufferQuantity(),
        result.allocatableQuantity(),
        result.allocatedTotal(),
        result.unallocatedQuantity(),
        outcome.note(true),
        actorId);

    int order = 0;
    for (PriorityAllocationLine line : outcome.lines()) {
      order++;
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
          outcome.salesUnits().getOrDefault(line.referenceId(), ZERO),
          outcome.salesShare().get(line.referenceId()),
          line.allocatedQty(),
          // 7.8.1.1 的 outcome 词表里没有「被保底挤下去」这一项，所以复用最接近的两项：
          // 拿不到货一律 DEMAND_EXHAUSTED（本模块的精细原因在 resolution 表里逐店留痕）。
          line.allocatedQty().signum() > 0 ? "OK" : "DEMAND_EXHAUSTED");
    }

    for (PriorityAllocationLine line : outcome.lines()) {
      sql.execute(
          "insert into allocation_priority_resolution (tenant_id,run_id,reference_id,source_system,"
              + "reference_label,floor_order,floor_mode,floor_ratio,floor_quantity,safety_quantity,"
              + "requested_qty,granted_qty,shortfall_qty,status,shortfall_reason,residual_qty,"
              + "base_allocated_qty,allocated_qty,suppressed,suppression_reason) "
              + "values (?,?,?,?,?,?,?,?::numeric,?::numeric,?::numeric,?::numeric,?::numeric,"
              + "?::numeric,?,?,?::numeric,?::numeric,?::numeric,?,?)",
          tenant(),
          runId,
          line.referenceId(),
          line.sourceSystem(),
          line.referenceLabel(),
          line.floorOrder(),
          line.floorMode().name(),
          line.floorRatio(),
          line.floorQuantity(),
          line.safetyQuantity(),
          line.floorRequested(),
          line.floorGranted(),
          line.floorShortfall(),
          line.floorStatus().name(),
          line.shortfallReason() == null ? null : line.shortfallReason().name(),
          line.residualQty(),
          line.baseAllocatedQty(),
          line.allocatedQty(),
          line.suppressed(),
          line.suppressionReason() == null ? null : line.suppressionReason().name());
    }

    return new PriorityAllocationResult(
        result.policyId(),
        result.policyLabel(),
        result.method(),
        runId,
        result.skuId(),
        result.skuCode(),
        result.lines(),
        result.availableQuantity(),
        result.bufferQuantity(),
        result.allocatableQuantity(),
        result.floorPool(),
        result.residualPool(),
        result.allocatedTotal(),
        result.unallocatedQuantity(),
        result.baseAllocatedTotal(),
        result.memberCount(),
        result.floorMemberCount(),
        result.suppressedMemberCount(),
        result.shortfallMemberCount(),
        result.salesWindowStart(),
        result.salesWindowEnd(),
        outcome.note(true));
  }

  // ------------------------------------------------------------ 保底生效记录

  /**
   * 保底生效记录列表（真分页）。
   *
   * <p>7.8.2.2 饥饿预警会直接用它做聚合：{@code shortfallOnly} 只看保底没兑现的， {@code suppressedOnly}
   * 只看被别家保底挤下去的。两者是不同的病， 蓝图那句「『没货卖』和『没分到货』是两种问题」在这里先分成两个查询参数。
   */
  public ResolutionPage resolutions(
      UUID runId,
      UUID referenceId,
      boolean shortfallOnly,
      boolean suppressedOnly,
      int page,
      int size) {
    int p = Math.max(0, page);
    int s = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
    String from =
        " from allocation_priority_resolution r"
            + " where r.tenant_id = current_setting('app.current_tenant')::uuid"
            + " and (?::uuid is null or r.run_id = ?::uuid)"
            + " and (?::uuid is null or r.reference_id = ?::uuid)"
            + " and (not ? or r.shortfall_qty > 0)"
            + " and (not ? or r.suppressed)";
    Object[] bind = {runId, runId, referenceId, referenceId, shortfallOnly, suppressedOnly};
    long total = count("select count(*) as c" + from, bind);
    var rows =
        sql.fetch(
            "select r.*"
                + from
                + " order by r.created_at desc, r.run_id desc, r.floor_order limit ? offset ?",
            bindings(bind, s, p * s));
    return new ResolutionPage(rows.map(AllocationPriorityService::resolutionRow), total, p, s);
  }

  // ============================================================ 核心算法（纯函数）

  /** 保底请求：把一档保底读成算法需要的三个数。 */
  record Grant(
      UUID referenceId, FloorMode mode, BigDecimal quantity, BigDecimal ratio, BigDecimal safety) {}

  /** 一次带保底分摊的中间结果。 */
  record Outcome(
      List<PriorityAllocationLine> lines,
      BigDecimal available,
      BigDecimal buffer,
      BigDecimal allocatable,
      BigDecimal floorPool,
      BigDecimal residualPool,
      BigDecimal allocated,
      BigDecimal unallocated,
      BigDecimal baseAllocated,
      OffsetDateTime allocatedAt,
      Map<UUID, BigDecimal> salesUnits,
      Map<UUID, BigDecimal> salesShare,
      long suppressed,
      long shortfall) {

    String note(boolean persisted) {
      StringBuilder sb = new StringBuilder();
      sb.append("保底池 ")
          .append(plain(floorPool))
          .append(" 件 + 余量池 ")
          .append(plain(residualPool))
          .append(" 件 = 可分配量 ")
          .append(plain(allocatable))
          .append(" 件。");
      if (suppressed > 0) {
        sb.append("有 ").append(suppressed).append(" 家店被压低（拿到的比「没有保底时」少），明细见下方被压低提示列；");
      }
      if (shortfall > 0) {
        sb.append("有 ").append(shortfall).append(" 家店的保底**没有足额兑现**，差额与原因逐店列出；");
      }
      if (suppressed == 0 && shortfall == 0) {
        sb.append("本次没有任何店铺被压低、也没有保底欠账；");
      }
      sb.append("注意：保底是策略不是事实 —— 它只在货真的不够分时才会起作用，货够分时每家店拿多少由余量分配决定。");
      sb.append(persisted ? "本次已落库。" : "本次为试算：一行都没有写，配保底时先用它看结果，确认后再落库。");
      return sb.toString();
    }
  }

  /**
   * 算一次带保底的分摊。<b>纯函数</b>：不碰 DB、不碰租户上下文、不读系统时间。
   *
   * <p>单测直接调它，不必拉 Testcontainers；试算与落库也跑的是同一个它。 算式见 {@link AllocationPriorityModels} 的类注释。
   *
   * @param floors 该策略此刻生效的保底（算法内部会按仲裁次序重排，顺序由它自己定）
   */
  static Outcome compute(
      AllocationPolicyRow policy,
      List<AllocationPolicyMember> roster,
      Map<UUID, BigDecimal> sales,
      List<Grant> floors,
      BigDecimal availableIn,
      LocalDate windowStart,
      LocalDate windowEnd) {

    BigDecimal available = whole(availableIn);

    // ① 缓冲口径是 7.8.1.1 的，本模块只算一次、不重新解释「留多少」。
    BigDecimal buffer =
        policy.bufferMode()
                == com.starcon.warehouse.AllocationPolicyModels.AllocationBufferMode.RATIO
            ? whole(
                available.multiply(nz(policy.bufferRatio())).setScale(QTY_SCALE, RoundingMode.DOWN))
            : whole(nz(policy.bufferQuantity()).min(available));
    if (buffer.compareTo(available) > 0) {
      buffer = available;
    }
    BigDecimal allocatable = available.subtract(buffer);

    // 仲裁次序：类型名次（只有 SHOP_TYPE_PRIORITY 才用）+ 成员 priority + 店铺 id 升序。
    // 最后一级 tie-break 用 id 是为了**可复现**：同一份配置跑两次必须得到同样的结果。
    Map<String, Integer> typeRanks = typeRanks(policy.shopTypeOrder());
    boolean byType = policy.method() == AllocationMethod.SHOP_TYPE_PRIORITY;
    List<AllocationPolicyMember> ordered = new ArrayList<>(roster);
    ordered.sort(
        Comparator.comparingInt(
                (AllocationPolicyMember m) ->
                    byType ? typeRanks.getOrDefault(m.shopOperatingMode(), Integer.MAX_VALUE) : 0)
            .thenComparingInt(AllocationPolicyMember::priority)
            .thenComparing(m -> m.referenceId().toString()));

    Map<UUID, Grant> grantByShop = new LinkedHashMap<>();
    for (Grant g : floors) {
      if (g != null && g.referenceId() != null) {
        grantByShop.put(g.referenceId(), g);
      }
    }

    // 基线：同一批货在「完全没有保底」时按 7.8.1.1 的算式算出来的数。
    // 它是「被压低提示」的唯一依据 —— 没有它，界面上只能显示一个绝对分摊额，
    // 而答不出「这个数是被谁挤下去的」。
    Map<UUID, BigDecimal> base =
        distribute(policy, ordered, sales, allocatable, allEnabled(ordered));
    BigDecimal baseTotal = base.values().stream().reduce(ZERO_QTY, BigDecimal::add);

    // ② 保底池上界：逐店请求之和，再夹到可分配量。夹在这里而不是最后，
    // 是为了让「保底池 = 余量池之上」这条等式在任何输入下都成立。
    Map<UUID, BigDecimal> requested = new LinkedHashMap<>();
    Map<UUID, BigDecimal> granted = new LinkedHashMap<>();
    Map<UUID, FloorShortfallReason> shortfallReasons = new LinkedHashMap<>();

    BigDecimal demand = ZERO_QTY;
    for (AllocationPolicyMember m : ordered) {
      Grant g = grantByShop.get(m.referenceId());
      if (g == null) {
        continue;
      }
      BigDecimal want = requestFor(g, allocatable);
      if (!m.enabled()) {
        // 成员被停用：请求照记（承诺存在过），但一分不给，原因写明。
        requested.put(m.referenceId(), want);
        granted.put(m.referenceId(), ZERO_QTY);
        shortfallReasons.put(m.referenceId(), FloorShortfallReason.MEMBER_DISABLED);
        demand = demand.add(want);
        continue;
      }
      requested.put(m.referenceId(), want);
      demand = demand.add(want);
    }
    BigDecimal floorPool = demand.min(allocatable);

    // ③④ 保底裁决：按次序逐个从保底池里拿。
    for (AllocationPolicyMember m : ordered) {
      if (!requested.containsKey(m.referenceId())) {
        continue;
      }
      BigDecimal want = requested.get(m.referenceId());
      if (shortfallReasons.containsKey(m.referenceId())) {
        granted.put(m.referenceId(), ZERO_QTY);
        continue;
      }
      BigDecimal got = want.min(floorPool);
      granted.put(m.referenceId(), got);
      floorPool = floorPool.subtract(got);
      if (got.compareTo(want) < 0) {
        shortfallReasons.put(m.referenceId(), FloorShortfallReason.POOL_EXHAUSTED);
      }
    }
    // Σ实际发放 = Σgranted（直接加，不要从「上界」反推 —— 反推式读错一位就会变成负数，
    // 而负的余量池意味着超卖）。
    BigDecimal actuallyGranted = granted.values().stream().reduce(ZERO_QTY, BigDecimal::add);
    BigDecimal residualPool = allocatable.subtract(actuallyGranted);
    if (residualPool.signum() < 0) {
      throw new IllegalStateException(
          "保底发放合计 " + actuallyGranted + " 超过可分配量 " + allocatable + "。这是算法 bug，不是输入问题 —— 余量池不能是负数。");
    }

    // ⑤ 余量分配：只让**未被保底足额满足**的店铺参与。
    // 已经足额的那家如果还能从余量池再拿一份，保底就变成了「起步价」而不是「底线」，
    // 而蓝图要的恰恰是底线。
    Set<UUID> participants = new LinkedHashSet<>();
    for (AllocationPolicyMember m : ordered) {
      if (!m.enabled()) {
        continue;
      }
      if (!granted.containsKey(m.referenceId())
          || granted.get(m.referenceId()).compareTo(requested.get(m.referenceId())) < 0) {
        participants.add(m.referenceId());
      }
    }
    Map<UUID, BigDecimal> residual =
        residualPool.signum() > 0 && !participants.isEmpty()
            ? distribute(policy, ordered, sales, residualPool, participants)
            : Map.of();

    // ⑥ 汇总逐店行。输出顺序 = 仲裁次序，「谁先拿到」一眼可见。
    List<PriorityAllocationLine> lines = new ArrayList<>(ordered.size());
    BigDecimal allocatedTotal = ZERO_QTY;
    long suppressedCount = 0;
    long shortfallCount = 0;
    Map<UUID, BigDecimal> salesShare = shareAmong(ordered, sales, participants);
    // 本次是否真的有货以「保底」的名义发出去过。判据是 Σgranted > 0：
    // 它决定了「被压低」能不能归因到保底机制，而不是笼统的「货被别人分完了」。
    boolean floorTookStock = actuallyGranted.signum() > 0;

    for (AllocationPolicyMember m : ordered) {
      Grant g = grantByShop.get(m.referenceId());
      boolean hasFloor = requested.containsKey(m.referenceId());
      BigDecimal req = hasFloor ? requested.get(m.referenceId()) : ZERO_QTY;
      BigDecimal got = hasFloor ? granted.get(m.referenceId()) : ZERO_QTY;
      BigDecimal res = residual.getOrDefault(m.referenceId(), ZERO_QTY);
      BigDecimal total = got.add(res);
      BigDecimal baseQty = base.getOrDefault(m.referenceId(), ZERO_QTY);
      BigDecimal gap = req.subtract(got);

      FloorShortfallReason shortReason =
          gap.signum() > 0
              ? shortfallReasons.getOrDefault(m.referenceId(), FloorShortfallReason.POOL_EXHAUSTED)
              : null;
      FloorStatus status =
          gap.signum() == 0
              ? FloorStatus.FULFILLED
              : (got.signum() == 0 ? FloorStatus.UNFULFILLED : FloorStatus.PARTIAL);

      boolean suppressed = total.compareTo(baseQty) < 0;
      SuppressionReason suppressReason = null;
      if (suppressed) {
        // 判定顺序即「处置建议」的顺序：先说清是谁动的手，再说是被谁分满的。
        if (!m.enabled()) {
          suppressReason = SuppressionReason.MEMBER_DISABLED;
        } else if (hasFloor && gap.signum() > 0) {
          // 自己的保底都没兑现 —— 是保底机制本身让它没拿到够的。
          suppressReason = SuppressionReason.RESERVED_FOR_FLOOR;
        } else if (floorTookStock) {
          // 本次确实有货以保底的名义发出去过，而这家店拿到的比无保底基线少：
          // 那批货里有它的一份，被挪去补别家的底线了。
          // 「被别家店的保底挤下去」与「本来就轮不到它」是两种病，处置完全不同，
          // 所以这个判断必须是显式的一条，而不是 else 兜底。
          suppressReason = SuppressionReason.RESERVED_FOR_FLOOR;
        } else if (policy.method().needsSales()
            && sales.getOrDefault(m.referenceId(), ZERO_QTY).signum() == 0) {
          suppressReason = SuppressionReason.NO_SALES;
        } else {
          suppressReason = SuppressionReason.POOL_EXHAUSTED;
        }
        suppressedCount++;
      }
      if (gap.signum() > 0) {
        shortfallCount++;
      }
      allocatedTotal = allocatedTotal.add(total);

      lines.add(
          new PriorityAllocationLine(
              m.sourceSystem(),
              m.referenceId(),
              m.referenceLabel(),
              m.shopOperatingMode(),
              floorOrderOf(ordered, m),
              g == null ? FloorMode.QUANTITY : g.mode(),
              g == null ? ZERO_QTY : g.quantity(),
              g == null ? ZERO : g.ratio(),
              g == null ? ZERO_QTY : g.safety(),
              req,
              got,
              gap,
              status,
              shortReason,
              res,
              baseQty,
              total,
              suppressed,
              suppressReason));
    }

    BigDecimal unallocated = allocatable.subtract(allocatedTotal);
    if (unallocated.signum() < 0) {
      // 走到这里说明算法错了，绝不能让它以负数的样子提交成功（超卖）。
      throw new IllegalStateException(
          "保底分配后总账不平：Σ各店分摊 " + allocatedTotal + " 超过可分配量 " + allocatable + "。这是算法 bug，不是输入问题。");
    }

    return new Outcome(
        List.copyOf(lines),
        available,
        buffer,
        allocatable,
        allocatable.subtract(residualPool),
        residualPool,
        allocatedTotal,
        unallocated,
        baseTotal,
        OffsetDateTime.now(),
        sales,
        salesShare,
        suppressedCount,
        shortfallCount);
  }

  /**
   * 按 7.8.1.1 的同一套算式在 {@code pool} 上分配，只给 {@code participants} 里的店铺。
   *
   * <p>比例分配在**参与店铺之间**重新归一化份额：被保底足额满足的店铺已经拿够了， 它的销量仍然算在分母里的话，余量就会被稀释到不该稀释的地方。
   * 先到先得与按店铺类型优先级则是次序问题，按原次序跳过不参与的店铺。
   */
  private static Map<UUID, BigDecimal> distribute(
      AllocationPolicyRow policy,
      List<AllocationPolicyMember> ordered,
      Map<UUID, BigDecimal> sales,
      BigDecimal pool,
      Set<UUID> participants) {
    Map<UUID, BigDecimal> out = new LinkedHashMap<>();
    if (pool.signum() <= 0 || participants.isEmpty()) {
      return out;
    }

    if (policy.method() == AllocationMethod.PROPORTIONAL_SALES) {
      BigDecimal totalSales = ZERO_QTY;
      for (UUID shop : participants) {
        totalSales = totalSales.add(nz(sales.get(shop)));
      }
      if (totalSales.signum() <= 0) {
        // 销量全为 0 时如实给 0，而不是均分 —— 均分出来的数没有任何依据，
        // 而界面上的一个数字总会被人当成依据。
        return out;
      }
      // 逐个向下取整 + 余数按小数部分从大到小补发（与 7.8.1.1 同一套）
      record Share(UUID shop, BigDecimal exact, BigDecimal frac, int seq) {}
      List<Share> shares = new ArrayList<>();
      int seq = 0;
      for (UUID shop : participants) {
        BigDecimal exact =
            pool.multiply(nz(sales.get(shop))).divide(totalSales, 12, RoundingMode.DOWN);
        BigDecimal floor = exact.setScale(QTY_SCALE, RoundingMode.DOWN);
        shares.add(new Share(shop, floor, exact.subtract(floor), seq++));
        out.put(shop, floor);
      }
      BigDecimal handed = shares.stream().map(Share::exact).reduce(ZERO_QTY, BigDecimal::add);
      BigDecimal remainder = pool.subtract(handed);
      List<Share> byFrac = new ArrayList<>(shares);
      byFrac.sort(
          Comparator.comparing(Share::frac).reversed().thenComparing(s -> s.shop().toString()));
      int i = 0;
      while (remainder.signum() > 0 && i < byFrac.size()) {
        UUID shop = byFrac.get(i % byFrac.size()).shop();
        out.compute(shop, (k, v) -> v.add(BigDecimal.ONE));
        remainder = remainder.subtract(BigDecimal.ONE);
        i++;
      }
      return out;
    }

    // 先到先得 / 按店铺类型优先级：逐个分，封顶后看剩下多少。
    BigDecimal remaining = pool;
    for (AllocationPolicyMember m : ordered) {
      if (!participants.contains(m.referenceId())) {
        continue;
      }
      BigDecimal cap =
          m.shareLimit() == null
              ? remaining
              : whole(pool.multiply(m.shareLimit()).setScale(QTY_SCALE, RoundingMode.DOWN));
      BigDecimal take = cap.min(remaining);
      out.put(m.referenceId(), take);
      remaining = remaining.subtract(take);
      if (remaining.signum() <= 0) {
        break;
      }
    }
    return out;
  }

  /**
   * 保底请求 = 保底 + 安全垫。
   *
   * <p><b>刻意不夹到可分配量</b>：一夹，「要 6 件、货只有 0 件」就会变成 「要 0 件、给了 0 件、欠账 0」—— 承诺明明存在过，记录里却看不出它没兑现， 而那正是
   * {@code shortfall_reason} 这几列存在的全部理由。 超发由别处保证：保底池上界是 {@code min(allocatable, Σ请求)}，逐店发放时又只从池里拿。
   */
  private static BigDecimal requestFor(Grant g, BigDecimal allocatable) {
    BigDecimal floor =
        g.mode() == FloorMode.RATIO
            ? whole(allocatable.multiply(nz(g.ratio())).setScale(QTY_SCALE, RoundingMode.DOWN))
            : nz(g.quantity());
    BigDecimal want = floor.add(nz(g.safety()));
    return want.signum() < 0 ? ZERO_QTY : want;
  }

  /**
   * 一店在仲裁次序里的名次（1 = 最先保住）。
   *
   * <p><b>每个成员都有名次，包括没配保底的那几家</b>：名次本身是「它当时排在第几」的凭据， 对没配保底的店来说它回答的是「它<b>本该</b>排第几却没货保」——
   * 这正是复盘被压低时最想知道的一句话。留 0 会让这两种情形长得一模一样。
   */
  private static int floorOrderOf(List<AllocationPolicyMember> ordered, AllocationPolicyMember m) {
    for (int i = 0; i < ordered.size(); i++) {
      if (ordered.get(i).referenceId().equals(m.referenceId())) {
        return i + 1;
      }
    }
    return 0;
  }

  private static Set<UUID> allEnabled(List<AllocationPolicyMember> ordered) {
    Set<UUID> out = new LinkedHashSet<>();
    for (AllocationPolicyMember m : ordered) {
      if (m.enabled()) {
        out.add(m.referenceId());
      }
    }
    return out;
  }

  /** 参与店铺之间的销量份额（用于 allocation_run_line 的 sales_share 列）。销量合计 0 时为 null。 */
  private static Map<UUID, BigDecimal> shareAmong(
      List<AllocationPolicyMember> ordered, Map<UUID, BigDecimal> sales, Set<UUID> participants) {
    BigDecimal total = ZERO_QTY;
    for (UUID shop : participants) {
      total = total.add(nz(sales.get(shop)));
    }
    if (total.signum() <= 0) {
      return Map.of();
    }
    Map<UUID, BigDecimal> out = new LinkedHashMap<>();
    for (UUID shop : participants) {
      out.put(shop, scale(nz(sales.get(shop)).divide(total, 6, RoundingMode.HALF_UP), 6));
    }
    return out;
  }

  /** 店铺类型优先级顺序 → 名次。未列出的类型排最后（而不是排最前）。 */
  private static Map<String, Integer> typeRanks(String order) {
    Map<String, Integer> ranks = new LinkedHashMap<>();
    String raw =
        order == null || order.isBlank() ? AllocationPolicyService.DEFAULT_SHOP_TYPE_ORDER : order;
    String[] parts = raw.split(",");
    for (int i = 0; i < parts.length; i++) {
      ranks.put(parts[i].trim().toUpperCase(Locale.ROOT), i);
    }
    return ranks;
  }

  // ============================================================ 取数

  /** 显式指定策略时的按 id 取档。跨租户 → 404。 */
  private AllocationPolicyRow loadPolicy(UUID policyId) {
    var r =
        sql.fetchOne(
            "select p.id,p.scope_type,p.source_system,p.reference_id,p.reference_label,p.method,"
                + "p.buffer_mode,p.buffer_ratio,p.buffer_quantity,p.shop_type_order,p.sales_window_days,"
                + "p.effective_from,p.effective_to,p.note,p.created_by_membership_id,p.created_at"
                + " from allocation_policy p where p.id = ?",
            policyId);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分摊策略不存在或不属于当前租户：" + policyId);
    }
    return policyOf(r);
  }

  /** 未指定策略时按 7.8.1.1 的分档解析（SKU &gt; SHOP &gt; DEFAULT）。不自己写第二份解析器。 */
  private AllocationPolicyRow resolveDefault(UUID skuId, OffsetDateTime at) {
    var resolved = policies.effective(null, null, skuId, at);
    if (resolved.policy() == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND,
          "没有解析到任何生效的分摊策略：保底是分摊之上的裁决，没有分摊就没有保底可言。" + "请先在 7.8.1.1 建一档策略（含成员），再回来配保底。");
    }
    return resolved.policy();
  }

  private List<AllocationPolicyMember> roster(UUID policyId) {
    return sql.fetch(
            "select m.source_system,m.reference_id,m.reference_label,m.shop_operating_mode,"
                + "m.priority,m.share_limit,m.enabled from allocation_policy_member m"
                + " where m.policy_id = ?",
            policyId)
        .map(
            r ->
                new AllocationPolicyMember(
                    policyId,
                    r.get("source_system", String.class),
                    r.get("reference_id", UUID.class),
                    nullToEmpty(r.get("reference_label", String.class)),
                    r.get("shop_operating_mode", String.class),
                    r.get("priority", Integer.class),
                    r.get("share_limit", BigDecimal.class),
                    Boolean.TRUE.equals(r.get("enabled", Boolean.class))));
  }

  /** 该策略此刻仍在生效的保底。 */
  private List<AllocationFloorRow> liveFloors(UUID policyId, OffsetDateTime at) {
    var rows =
        sql.fetch(
            WIN
                + " select "
                + FLOOR_COLUMNS
                + " from allocation_floor f, win"
                + " where f.policy_id = ? and "
                + FLOOR_LIVE_AT
                + " order by f.effective_from desc, f.id",
            at,
            policyId);
    return rows.map(AllocationPriorityService::floorRow);
  }

  private List<Grant> grants(List<AllocationFloorRow> floors) {
    List<Grant> out = new ArrayList<>(floors.size());
    for (AllocationFloorRow f : floors) {
      out.add(
          new Grant(
              f.referenceId(),
              f.floorMode(),
              f.floorQuantity(),
              f.floorRatio(),
              f.safetyQuantity()));
    }
    return out;
  }

  /** 窗口内按店铺汇总的登记销量（7.8.1.1 的 allocation_shop_sales 是本服务唯一的销量真相来源）。 */
  private Map<UUID, BigDecimal> salesIndex(UUID skuId, LocalDate from, LocalDate to) {
    Map<UUID, BigDecimal> out = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select reference_id, coalesce(sum(units_sold),0) as units from allocation_shop_sales"
                + " where sku_id = ? and period_end >= ? and period_start <= ? group by reference_id",
            skuId,
            from,
            to)) {
      out.put(r.get("reference_id", UUID.class), decimal(r, "units"));
    }
    return out;
  }

  private String skuCode(UUID skuId) {
    var r = sql.fetchOne("select code from sku_ref where id = ?", skuId);
    return r == null ? null : nullToEmpty(r.get("code", String.class));
  }

  private String policyLabel(AllocationPolicyRow policy) {
    String label =
        switch (policy.scopeType()) {
          case DEFAULT -> "全租户兜底档";
          case SHOP -> "按店铺档 " + policy.referenceLabel();
          case SKU -> "按 SKU 档 " + policy.referenceLabel();
        };
    return label + "（" + policy.method().label() + "）";
  }

  // ============================================================ 映射与工具

  private static AllocationFloorRow floorRow(Record r) {
    return new AllocationFloorRow(
        r.get("id", UUID.class),
        r.get("policy_id", UUID.class),
        r.get("source_system", String.class),
        r.get("reference_id", UUID.class),
        nullToEmpty(r.get("reference_label", String.class)),
        FloorMode.valueOf(r.get("floor_mode", String.class)),
        decimal(r, "floor_ratio"),
        whole(decimal(r, "floor_quantity")),
        whole(decimal(r, "safety_quantity")),
        r.get("effective_from", OffsetDateTime.class),
        r.get("effective_to", OffsetDateTime.class),
        nullToEmpty(r.get("note", String.class)),
        r.get("created_by_membership_id", UUID.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private static ResolutionRow resolutionRow(Record r) {
    return new ResolutionRow(
        r.get("run_id", UUID.class),
        r.get("reference_id", UUID.class),
        nullToEmpty(r.get("reference_label", String.class)),
        r.get("floor_order", Integer.class),
        FloorMode.valueOf(r.get("floor_mode", String.class)),
        whole(decimal(r, "floor_quantity")),
        decimal(r, "floor_ratio"),
        whole(decimal(r, "safety_quantity")),
        whole(decimal(r, "requested_qty")),
        whole(decimal(r, "granted_qty")),
        whole(decimal(r, "shortfall_qty")),
        FloorStatus.valueOf(r.get("status", String.class)),
        r.get("shortfall_reason", String.class) == null
            ? null
            : FloorShortfallReason.valueOf(r.get("shortfall_reason", String.class)),
        whole(decimal(r, "residual_qty")),
        whole(decimal(r, "base_allocated_qty")),
        whole(decimal(r, "allocated_qty")),
        Boolean.TRUE.equals(r.get("suppressed", Boolean.class)),
        r.get("suppression_reason", String.class) == null
            ? null
            : SuppressionReason.valueOf(r.get("suppression_reason", String.class)),
        r.get("created_at", OffsetDateTime.class));
  }

  private static AllocationPolicyRow policyOf(Record r) {
    return new AllocationPolicyRow(
        r.get("id", UUID.class),
        com.starcon.warehouse.AllocationPolicyModels.AllocationScopeType.valueOf(
            r.get("scope_type", String.class)),
        r.get("reference_id", UUID.class),
        nullToEmpty(r.get("reference_label", String.class)),
        AllocationMethod.valueOf(r.get("method", String.class)),
        com.starcon.warehouse.AllocationPolicyModels.AllocationBufferMode.valueOf(
            r.get("buffer_mode", String.class)),
        decimal(r, "buffer_ratio"),
        decimal(r, "buffer_quantity"),
        nullToEmpty(r.get("shop_type_order", String.class)),
        r.get("sales_window_days", Integer.class),
        r.get("effective_from", OffsetDateTime.class),
        r.get("effective_to", OffsetDateTime.class),
        nullToEmpty(r.get("note", String.class)),
        r.get("created_by_membership_id", UUID.class),
        r.get("created_at", OffsetDateTime.class));
  }

  /**
   * 数总数。<b>调用方必须传完整的 {@code select count(*) as c …}</b>： 包一层子查询（{@code from (select … order by …)
   * t}）在 PG 里是非法语法， 而它报的错是语法错误，不会提示「你把排序带进计数里了」。
   */
  private long count(String select, Object[] bind) {
    var r = sql.fetchOne(select, bind);
    return r == null ? 0 : r.get("c", Long.class);
  }

  private static Object[] bindings(Object[] head, Object... tail) {
    Object[] out = new Object[head.length + tail.length];
    System.arraycopy(head, 0, out, 0, head.length);
    System.arraycopy(tail, 0, out, head.length, tail.length);
    return out;
  }

  private static BigDecimal decimal(Record r, String column) {
    BigDecimal v = r.get(column, BigDecimal.class);
    return v == null ? ZERO : v;
  }

  /** 件数口径：向下取整到整件，不允许小数混进来。 */
  private static BigDecimal whole(BigDecimal value) {
    return nz(value).setScale(QTY_SCALE, RoundingMode.DOWN);
  }

  private static BigDecimal scale(BigDecimal value, int exp) {
    return nz(value).setScale(exp, RoundingMode.HALF_UP);
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static String plain(BigDecimal value) {
    return nz(value).stripTrailingZeros().toPlainString();
  }

  private static String nullToEmpty(String s) {
    return s == null ? "" : s;
  }

  private static FloorMode floorMode(String raw) {
    if (raw == null || raw.isBlank()) {
      throw bad("floorMode 必填（QUANTITY 或 RATIO）");
    }
    try {
      return FloorMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad("未知的 floorMode：" + raw + "（应为 QUANTITY 按件数 / RATIO 按比例）");
    }
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException notFound(UUID id) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "保底分档不存在或不属于当前租户：" + id);
  }

  /** 租户来自 RLS 的同一个来源，绝不来自任何请求参数或客户端头。 */
  private UUID tenant() {
    return sql.fetchOne("select current_setting('app.current_tenant',true)::uuid as t")
        .get("t", UUID.class);
  }
}
