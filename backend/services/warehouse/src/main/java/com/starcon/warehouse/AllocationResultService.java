package com.starcon.warehouse;

import static com.starcon.warehouse.AllocationResultModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 7.8.2.1 分摊结果查看：把「分摊完了之后到底怎么样了」变成**可核对的事实**。
 *
 * <p>蓝图：「展示某 SKU/仓在各店铺的分摊量、已用、剩余、回写平台的数值，以及回写失败或延迟导致的实际风险敞口。输入：分摊结果；平台回写状态（4.3.2.1）。输出：分摊明细表 + 平台回写一致性核对。」以及关系段的红线：「**平台回写有延迟**，分摊需保留缓冲以覆盖同步窗口，缓冲不足会超卖。」
 *
 * <p><b>六条实现纪律</b>：
 *
 * <ul>
 *   <li><b>不跨服务读表</b>：4.3.2.1 的平台回写状态在 channel 服务。AGENTS.md 禁止跨服务 SQL，
 *       所以回写状态一律走**本地投影表 {@code allocation_writeback_snapshot}**（事件 / 授权 API 推送
 *       或显式录入），每条带来源标识与采集时刻 {@code as_of}。
 *   <li><b>「不知道」是一个状态，不是一个 0</b>：本地没收到回写状态时判 {@code UNKNOWN}，
 *       敞口判不出来时也判 {@code UNKNOWN}。绝不用 0 顶替 —— 0 会让人去补货，
 *       而真相是「我们不知道，该去查同步」。
 *   <li><b>敞口是算出来的，不是提示文案</b>：{@code exposure = max(0, 平台挂着的 − 我方剩余)}，
 *       {@code exposure > buffer} ⇒ 缓冲不足 ⇒ 同步窗口内会超卖。
 *   <li><b>时间绑定</b>：{@code ?::timestamptz} 只出现在语句最前面的 {@code win} CTE 里，
 *       后面全是普通绑定，cast 永远不在绑定序列末位（与 7.8.1.1 / 7.8.1.2 同一条纪律）。
 *   <li><b>数量一律 numeric(19,6) + BigDecimal</b>，比较一律 {@code compareTo} 不用 {@code equals}。
 *   <li><b>三个缓冲不混</b>：本模块的 {@code bufferQuantity} 是 7.2.2.1 的**平台回写缓冲**
 *       （抵御回写同步延迟），与 7.8.1.1 的**分摊缓冲**（{@code runBufferQuantity}）、
 *       7.8.1.2 的**保底池**是三个不同的东西。
 * </ul>
 *
 * <p><b>为什么敞口算式要自己算而不是问 7.2.2.1</b>：7.2.2.1 的池体检是**池级**的
 * （这个池的缓冲够不够），而本模块要回答的是**逐店**的「这家店平台挂着的数，
 * 比我方剩下的多多少、多出来的那部分缓冲能不能兜住」。两个口径形状不同，
 * 但 7.2.2.1 的缓冲比例与超卖容忍是同一份配置 —— 直接读它的表，不复制它的口径。
 */
@Service
public class AllocationResultService {

  /** 单页最大行数，与 7.8.1.1 / 7.8.1.2 同一上限与理由。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 数量列在 DB 侧是 numeric(19,6)，而**件数必须是整件**，所以这里的 scale 是 0。 */
  private static final int QTY_SCALE = 0;

  /** numeric(19,6) 口径下的零。 */
  private static final BigDecimal ZERO = new BigDecimal("0.000000");

  /** 件数口径下的零。 */
  private static final BigDecimal ZERO_QTY = BigDecimal.ZERO;

  /**
   * 分摊结果的 CTE 链。
   *
   * <p>三个时刻绑定刻意排在最前面（{@code win} CTE），后面全是普通绑定 ——
   * {@code ?::timestamptz} 出现在绑定序列末位时 jOOQ/PG 的类型推断会出问题
   * （7.1.1.1 实测踩过）。
   *
   * <p>绑定顺序（**三处查询必须一致**，否则整条语句会错位）：
   *
   * <ol>
   *   <li>{@code at}（求值时刻）
   *   <li>{@code from_at} / {@code to_at}（分摊时刻区间，左闭右开）
   *   <li>{@code runId} ×2
   *   <li>{@code skuId} ×2
   *   <li>{@code method} ×2
   *   <li>{@code referenceId} ×2
   * </ol>
   *
   * <p>区间筛选走 {@code win} CTE 而不是 {@code ?::timestamptz IS NULL}：
   * 后者会把一个 timestamptz cast 放到绑定序列中间，与本仓库的时间绑定纪律冲突。
   */
  private static final String RESULT_CTE =
      "with win as (select ?::timestamptz as at, ?::timestamptz as from_at, ?::timestamptz as to_at), "
          + "runs as ("
          + "  select r.id, r.sku_id, sr.code as sku_code, r.method, r.policy_id, r.allocated_at,"
          + "         r.available_quantity, r.buffer_quantity, r.allocatable_quantity,"
          + "         r.allocated_total, r.unallocated_quantity"
          + "    from allocation_run r left join sku_ref sr"
          + "      on sr.tenant_id = r.tenant_id and sr.id = r.sku_id, win"
          + "   where r.tenant_id = current_setting('app.current_tenant')::uuid"
          + "     and (?::uuid is null or r.id = ?::uuid)"
          + "     and (?::uuid is null or r.sku_id = ?::uuid)"
          + "     and (?::text is null or r.method = ?::text)"
          + "     and (win.from_at is null or r.allocated_at >= win.from_at)"
          + "     and (win.to_at is null or r.allocated_at < win.to_at)"
          + "), lines as ("
          + "  select l.run_id, l.source_system, l.reference_id, l.reference_label,"
          + "         l.shop_operating_mode, l.allocation_order, l.allocated_qty, l.outcome,"
          + "         l.sales_units, l.sales_share"
          + "    from allocation_run_line l"
          + "   where l.tenant_id = current_setting('app.current_tenant')::uuid"
          + "     and (?::uuid is null or l.reference_id = ?::uuid)"
          + "     and exists (select 1 from runs rr where rr.id = l.run_id)"
          + "), obs as ("
          + "  select distinct on (s.run_id, s.reference_id)"
          + "         s.run_id, s.reference_id, s.warehouse_id, s.state, s.attempted_quantity,"
          + "         s.consumed_quantity, s.failure_reason, s.as_of, s.source_kind, s.source_reference"
          + "    from allocation_writeback_snapshot s"
          + "   where s.tenant_id = current_setting('app.current_tenant')::uuid"
          + "     and exists (select 1 from runs rr where rr.id = s.run_id)"
          + "   order by s.run_id, s.reference_id, s.as_of desc, s.id desc"
          + "), plat as ("
          + "  select distinct on (s.run_id, s.reference_id) s.run_id, s.reference_id, s.written_quantity"
          + "    from allocation_writeback_snapshot s"
          + "   where s.tenant_id = current_setting('app.current_tenant')::uuid"
          + "     and s.written_quantity is not null"
          + "     and exists (select 1 from runs rr where rr.id = s.run_id)"
          + "   order by s.run_id, s.reference_id, s.as_of desc, s.id desc"
          + "), cons as ("
          + "  select distinct on (s.run_id, s.reference_id) s.run_id, s.reference_id, s.consumed_quantity"
          + "    from allocation_writeback_snapshot s"
          + "   where s.tenant_id = current_setting('app.current_tenant')::uuid"
          + "     and s.consumed_quantity is not null"
          + "     and exists (select 1 from runs rr where rr.id = s.run_id)"
          + "   order by s.run_id, s.reference_id, s.as_of desc, s.id desc"
          + "), floor as ("
          + "  select f.run_id, f.reference_id, f.status, f.shortfall_qty, f.suppressed, f.suppression_reason"
          + "    from allocation_priority_resolution f"
          + "   where f.tenant_id = current_setting('app.current_tenant')::uuid"
          + "     and exists (select 1 from runs rr where rr.id = f.run_id)"
          + "), base as ("
          + "  select rr.id as run_id, rr.sku_id, rr.sku_code, rr.method, rr.policy_id, rr.allocated_at,"
          + "         rr.available_quantity, rr.buffer_quantity as run_buffer_quantity,"
          + "         rr.allocatable_quantity, rr.allocated_total, rr.unallocated_quantity,"
          + "         l.source_system, l.reference_id, l.reference_label, l.shop_operating_mode,"
          + "         l.allocation_order, l.allocated_qty, l.outcome, l.sales_units, l.sales_share,"
          + "         o.warehouse_id, w.code as warehouse_code, w.name as warehouse_name,"
          + "         o.state as writeback_state, o.attempted_quantity,"
          + "         coalesce(o.consumed_quantity, c.consumed_quantity) as consumed_quantity,"
          + "         o.failure_reason, o.as_of, o.source_kind, o.source_reference,"
          + "         p.written_quantity as platform_quantity,"
          + "         pol.buffer_ratio, pol.oversell_allowed, pol.oversell_tolerance,"
          + "         fl.status as floor_status, fl.shortfall_qty as floor_shortfall_qty,"
          + "         fl.suppressed, fl.suppression_reason"
          + "    from runs rr"
          + "    join lines l on l.run_id = rr.id"
          + "    left join obs o on o.run_id = l.run_id and o.reference_id = l.reference_id"
          + "    left join plat p on p.run_id = l.run_id and p.reference_id = l.reference_id"
          + "    left join cons c on c.run_id = l.run_id and c.reference_id = l.reference_id"
          + "    left join warehouse w on w.tenant_id = current_setting('app.current_tenant')::uuid"
          + "                       and w.id = o.warehouse_id"
          + "    left join floor fl on fl.run_id = l.run_id and fl.reference_id = l.reference_id"
          + "    left join lateral ("
          + "      select sp.buffer_ratio, sp.oversell_allowed, sp.oversell_tolerance"
          + "        from sellable_policy sp"
          + "       where sp.tenant_id = current_setting('app.current_tenant')::uuid"
          + "         and sp.effective_from <= rr.allocated_at"
          + "         and (sp.effective_to is null or sp.effective_to > rr.allocated_at)"
          + "         and ((sp.scope_type = 'SHOP' and sp.reference_id = l.reference_id)"
          + "              or sp.scope_type = 'DEFAULT')"
          + "       order by case when sp.scope_type = 'SHOP' then 0 else 1 end,"
          + "                sp.effective_from desc, sp.id"
          + "       limit 1"
          + "    ) pol on true"
          + "), calc as ("
          + "  select b.*,"
          + "         (b.allocated_qty - b.consumed_quantity) as remaining_quantity,"
          + "         case when b.buffer_ratio is null then null"
          + "              else b.allocated_qty - trunc(b.allocated_qty * (1 - b.buffer_ratio)"
          + "                        + (case when b.oversell_allowed then b.oversell_tolerance else 0 end))"
          + "         end as buffer_quantity,"
          + "         case when b.buffer_ratio is null then null"
          + "              else trunc(b.allocated_qty * (1 - b.buffer_ratio)"
          + "                        + (case when b.oversell_allowed then b.oversell_tolerance else 0 end))"
          + "         end as writable_quantity"
          + "    from base b"
          + "), judged as ("
          + "  select c.*,"
          + "         case when c.platform_quantity is null or c.remaining_quantity is null then null"
          + "              else greatest(c.platform_quantity - c.remaining_quantity, 0) end as exposure_quantity,"
          + "         (c.platform_quantity - c.remaining_quantity) as advertised_delta,"
          + "         case when c.platform_quantity is null or c.remaining_quantity is null then 'UNKNOWN'"
          + "              when greatest(c.platform_quantity - c.remaining_quantity, 0) <= 0 then 'NO_EXPOSURE'"
          + "              when c.buffer_quantity is not null"
          + "               and greatest(c.platform_quantity - c.remaining_quantity, 0) <= c.buffer_quantity"
          + "                then 'WITHIN_BUFFER'"
          + "              else 'BEYOND_BUFFER' end as exposure_state,"
          + "         case when c.platform_quantity is null or c.writable_quantity is null then 'UNKNOWN'"
          + "              when c.platform_quantity > c.writable_quantity then 'OVER_ADVERTISED'"
          + "              when c.platform_quantity < c.writable_quantity then 'UNDER_ADVERTISED'"
          + "              else 'CONSISTENT' end as consistency_state"
          + "    from calc c"
          + ")";

  /**
   * 结果表的外层筛选。
   *
   * <p>{@code scope} 刻意只用**一个**绑定：四种口径是四种不同的病，
   * 用 CASE 一次判定比四个 OR 各带一遍参数更不容易错位。
   *
   * <p>仓库筛选按「回写观测所记录的仓」判定：本地尚未收到回写状态的行**无法归属到仓**，
   * 会被这个条件排除 —— 所以另有一个口径（{@code warehouseUnattributedCount}）
   * 把「被排除的那些行」的数量报出来，不让它们无声消失。
   */
  private static final String SCOPE_FILTER =
      " case ?::text"
          + "   when 'ALL' then true"
          + "   when 'EXPOSURE' then j.exposure_quantity > 0"
          + "   when 'BEYOND_BUFFER' then j.exposure_state = 'BEYOND_BUFFER'"
          + "   when 'INCONSISTENT'"
          + "     then j.consistency_state in ('OVER_ADVERTISED','UNDER_ADVERTISED')"
          + "   when 'UNKNOWN'"
          + "     then j.writeback_state is null or j.platform_quantity is null"
          + "   else false"
          + " end";

  /**
   * 仓库筛选（两个绑定）。
   *
   * <p>按「回写观测所记录的仓」判定：本地尚未收到回写状态的行**无法归属到仓**，
   * 会被这个条件排除 —— 所以另有一个口径（{@code warehouseUnattributedCount}）
   * 把「被排除的那些行」的数量报出来，不让它们无声消失。
   */
  private static final String WAREHOUSE_FILTER = "(?::uuid is null or j.warehouse_id = ?::uuid)";

  /** 外层 WHERE。{@code withWarehouse=false} 用于「仓归属未知」的统计。 */
  private static String where(boolean withWarehouse) {
    return withWarehouse ? WAREHOUSE_FILTER + " and " + SCOPE_FILTER : SCOPE_FILTER;
  }

  private final DSLContext sql;

  public AllocationResultService(DSLContext sql) {
    this.sql = sql;
  }

  // ============================================================ 能力

  /**
   * 分摊结果查看的能力清单。
   *
   * <p>六条说明必须如实讲清楚：跨服务边界、UNKNOWN 与 0 的区别、三个缓冲里用的是哪个、
   * 敞口算式、仓维度的来源、下游交接。前端据此渲染，而不是把规则硬编码在界面上。
   */
  public AllocationResultCapabilities capabilities() {
    var states =
        List.of(
            new ResultCapabilityOption(
                WritebackState.UNKNOWN.name(),
                WritebackState.UNKNOWN.label(),
                false,
                "本地尚未收到该店的回写状态。它与「回写数 = 0」是两件事：前者该去查同步，"
                    + "后者会去补货。页面上必须能把这两种情况区分开。"),
            new ResultCapabilityOption(
                WritebackState.SUCCEEDED.name(),
                WritebackState.SUCCEEDED.label(),
                true,
                "平台已确认持有本次回写的可售件数。"),
            new ResultCapabilityOption(
                WritebackState.FAILED.name(),
                WritebackState.FAILED.label(),
                true,
                "回写失败：平台仍持有旧值，本次想写的新值没上去 —— 延迟敞口的主要来源。"),
            new ResultCapabilityOption(
                WritebackState.PENDING.name(),
                WritebackState.PENDING.label(),
                true,
                "已发出未确认。平台当前挂着的仍是上一次成功回写的值。"));
    var consistency =
        List.of(
            new ResultCapabilityOption(
                ConsistencyState.UNKNOWN.name(),
                ConsistencyState.UNKNOWN.label(),
                false,
                "无法核对：本地没有回写状态，或该店没有配置 7.2.2.1 口径（「应该挂多少」本身没有依据）。"),
            new ResultCapabilityOption(
                ConsistencyState.CONSISTENT.name(),
                ConsistencyState.CONSISTENT.label(),
                true,
                "平台挂着的数正好等于「分摊量 − 缓冲」。"),
            new ResultCapabilityOption(
                ConsistencyState.OVER_ADVERTISED.name(),
                ConsistencyState.OVER_ADVERTISED.label(),
                true,
                "平台挂得比应该挂的多 —— 要么上次回写多写了，要么缓冲被调小了。"),
            new ResultCapabilityOption(
                ConsistencyState.UNDER_ADVERTISED.name(),
                ConsistencyState.UNDER_ADVERTISED.label(),
                true,
                "平台挂得比应该挂的少 —— 不会超卖，但会少卖。"));
    var exposure =
        List.of(
            new ResultCapabilityOption(
                ExposureState.UNKNOWN.name(),
                ExposureState.UNKNOWN.label(),
                false,
                "算不出来：不知道平台挂着多少，或不知道已用多少。这种行不按 0 计入敞口合计。"),
            new ResultCapabilityOption(
                ExposureState.NO_EXPOSURE.name(),
                ExposureState.NO_EXPOSURE.label(),
                true,
                "平台挂着的数没有超过我方剩余 —— 同步窗口内不会因此超卖。"),
            new ResultCapabilityOption(
                ExposureState.WITHIN_BUFFER.name(),
                ExposureState.WITHIN_BUFFER.label(),
                true,
                "有敞口，但缓冲兜得住 —— 敞口件数 ≤ 保留的缓冲件数。"),
            new ResultCapabilityOption(
                ExposureState.BEYOND_BUFFER.name(),
                ExposureState.BEYOND_BUFFER.label(),
                true,
                "敞口超过缓冲 —— 蓝图说的「缓冲不足会超卖」就落在这里："
                    + "同步窗口内平台还能卖出我方已经没有的件数。"));
    var kinds =
        List.of(
            new ResultCapabilityOption(
                WritebackSourceKind.EVENT.name(),
                WritebackSourceKind.EVENT.label(),
                true,
                "上游服务用事件推送回写状态（本服务不主动去查）。"),
            new ResultCapabilityOption(
                WritebackSourceKind.API.name(),
                WritebackSourceKind.API.label(),
                true,
                "上游服务经授权 API 推送回写状态。"),
            new ResultCapabilityOption(
                WritebackSourceKind.MANUAL.name(),
                WritebackSourceKind.MANUAL.label(),
                true,
                "运营显式录入的快照。用于上游还没接上的过渡期。"));
    var scopes =
        List.of(
            new ResultCapabilityOption(
                ResultScope.ALL.name(), ResultScope.ALL.label(), true, "不筛选。"),
            new ResultCapabilityOption(
                ResultScope.EXPOSURE.name(),
                ResultScope.EXPOSURE.label(),
                true,
                "只看已经产生敞口的行（敞口件数 > 0）。"),
            new ResultCapabilityOption(
                ResultScope.BEYOND_BUFFER.name(),
                ResultScope.BEYOND_BUFFER.label(),
                true,
                "只看缓冲兜不住的行 —— 超卖风险已经成立的那几行。"),
            new ResultCapabilityOption(
                ResultScope.INCONSISTENT.name(),
                ResultScope.INCONSISTENT.label(),
                true,
                "只看平台挂的数与「分摊量 − 缓冲」对不上的行。"),
            new ResultCapabilityOption(
                ResultScope.UNKNOWN.name(),
                ResultScope.UNKNOWN.label(),
                true,
                "只看本地没有回写状态的行 —— 该去接同步的那几行。"));
    return new AllocationResultCapabilities(
        states,
        consistency,
        exposure,
        kinds,
        scopes,
        false,
        "平台回写状态（4.3.2.1）在 channel 服务，AGENTS.md 禁止跨服务 SQL / 跨库 FK / 共享视图，"
            + "所以本服务**不读 channel 的表**。回写状态经事件 / 授权 API 推送，"
            + "或由运营显式录入，进入本服务自己的投影表 allocation_writeback_snapshot；"
            + "每条观测都带来源标识（source_kind + source_reference）与采集时刻 as_of。",
        "本地没有收到回写状态时，状态判 UNKNOWN，敞口判 UNKNOWN —— "
            + "**绝不按 0 处理**。0 意味着「平台上一件都卖不出去」，"
            + "而 UNKNOWN 意味着「我们不知道，该去查同步」，两者的处置完全相反。",
        "本模块用的缓冲是 7.2.2.1 的**平台可售数保留缓冲比例**（抵御平台回写同步延迟），"
            + "它与 7.8.1.1 的**分摊缓冲**（同一批货里压根不参与分摊的量）、"
            + "7.8.1.2 的**保底池**（货不够分时先保住高价值店铺的底线）是三个不同的东西。",
        "敞口 = max(0, 平台当前挂着的可售件数 − 我方剩余)；"
            + "我方剩余 = 分摊量 − 已用。缓冲 = 分摊量 − trunc(分摊量 × (1 − 缓冲比例) + 超卖容忍)。"
            + "敞口 > 缓冲 ⇒ 缓冲不足 ⇒ 同步窗口内会超卖。",
        "仓维度取自**回写观测所记录的仓**（那批货是从哪个仓出的）。"
            + "本地尚未收到回写状态的行无法归属到仓，按仓筛选时会被排除，"
            + "被排除的行数由 warehouseUnattributedCount 报出。",
        "下游：04 刊登（回写依据）。本模块已把「应该挂多少」（分摊量 − 缓冲）算出来并逐店列出，"
            + "04 可以直接拿它当回写目标值，并用它核对回写后的一致性。");
  }

  // ============================================================ 分摊明细

  /**
   * 分摊明细表（真分页）。
   *
   * <p>筛选维度覆盖蓝图的「某 SKU/仓在各店铺」：SKU、仓、店铺、分摊轮次，外加
   * 分摊方法、分摊时刻区间与「只看哪一类问题」。
   */
  public AllocationResultPage results(AllocationResultQuery query) {
    AllocationResultQuery q =
        query == null
            ? new AllocationResultQuery(null, null, null, null, null, null, null, null, 0, 50)
            : query;
    int page = Math.max(0, q.page());
    int size = Math.min(Math.max(1, q.size()), MAX_PAGE_SIZE);
    ResultScope scope = scope(q.scope());
    OffsetDateTime at = OffsetDateTime.now();

    Object[] cte = cteBinds(q, at);
    Object[] scoped = scopeBinds(q, scope, true);

    long total =
        count(
            RESULT_CTE + " select count(*) as c from judged j where " + where(true),
            concat(cte, scoped));
    var rows =
        sql.fetch(
            RESULT_CTE
                + " select j.* from judged j where "
                + where(true)
                + " order by j.allocated_at desc, j.run_id desc, j.allocation_order asc limit ? offset ?",
            concatAll(concat(cte, scoped), size, page * size));
    var summary = summary(cte, scoped);

    // 仓归属未知的行数：按仓筛选时被排除的那些。少了这个数，
    // 「筛完只剩 3 行」会让人以为只有 3 家店 —— 而其实是「3 家店有仓归属，
    // 另外 5 家还没回填，归属不到仓」。
    long unattributed =
        q.warehouseId() == null
            ? 0
            : count(
                RESULT_CTE
                    + " select count(*) as c from judged j where "
                    + where(false)
                    + " and j.writeback_state is null",
                concat(cte, scopeBinds(q, scope, false)));

    return new AllocationResultPage(
        rows.map(AllocationResultService::resultRow), total, page, size, summary, unattributed, at);
  }

  /** 平台回写一致性核对的**逐轮**结论。 */
  public List<AllocationReconciliationRun> reconciliationByRun(AllocationResultQuery query) {
    AllocationResultQuery q =
        query == null
            ? new AllocationResultQuery(null, null, null, null, null, null, null, null, 0, 50)
            : query;
    ResultScope scope = scope(q.scope());
    Object[] cte = cteBinds(q, OffsetDateTime.now());
    Object[] scoped = scopeBinds(q, scope, true);
    var rows =
        sql.fetch(
            RESULT_CTE
                + " select j.run_id, j.sku_id, j.sku_code, j.allocated_at,"
                + "        count(*) as shop_count,"
                + "        count(*) filter (where j.writeback_state is null) as unknown_state_count,"
                + "        count(*) filter (where j.consistency_state = 'CONSISTENT') as consistent_count,"
                + "        count(*) filter (where j.consistency_state = 'OVER_ADVERTISED') as over_advertised_count,"
                + "        count(*) filter (where j.consistency_state = 'UNDER_ADVERTISED') as under_advertised_count,"
                + "        count(*) filter (where j.exposure_state = 'BEYOND_BUFFER') as beyond_buffer_count,"
                + "        count(*) filter (where j.exposure_state = 'WITHIN_BUFFER') as within_buffer_count,"
                + "        count(j.exposure_quantity) as exposure_computable_count,"
                + "        coalesce(sum(j.exposure_quantity), 0) as exposure_total"
                + "   from judged j where "
                + where(true)
                + "  group by j.run_id, j.sku_id, j.sku_code, j.allocated_at"
                + "  order by j.allocated_at desc, j.run_id desc",
            concat(cte, scoped));
    return rows.map(AllocationResultService::reconciliationRun);
  }

  /** 平台回写一致性核对的报告：总量口径 + 逐轮结论。 */
  public AllocationReconciliationReport reconciliation(AllocationResultQuery query) {
    AllocationResultQuery q =
        query == null
            ? new AllocationResultQuery(null, null, null, null, null, null, null, null, 0, 50)
            : query;
    ResultScope scope = scope(q.scope());
    Object[] cte = cteBinds(q, OffsetDateTime.now());
    Object[] scoped = scopeBinds(q, scope, true);
    return new AllocationReconciliationReport(
        summary(cte, scoped),
        reconciliationByRun(q),
        "核对口径：平台挂着的可售件数 vs「分摊量 − 缓冲」。"
            + "平台挂多了（OVER_ADVERTISED）会超卖，平台挂少了（UNDER_ADVERTISED）只是少卖；"
            + "UNKNOWN 表示本地没有回写状态或没有 7.2.2.1 口径，不是「没问题」。");
  }

  // ============================================================ 分摊轮次

  /** 分摊轮次列表（真分页）。 */
  public AllocationResultRunPage runs(
      UUID skuId, String method, OffsetDateTime from, OffsetDateTime to, int page, int size) {
    int p = Math.max(0, page);
    int s = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
    String m = method == null || method.isBlank() ? null : method.trim();
    String cte =
        "with win as (select ?::timestamptz as from_at, ?::timestamptz as to_at), "
            + "runs as ("
            + "  select r.id, r.sku_id, sr.code as sku_code, r.method, r.allocated_at,"
            + "         r.available_quantity, r.buffer_quantity, r.allocatable_quantity,"
            + "         r.allocated_total, r.unallocated_quantity,"
            + "         (select count(*) from allocation_run_line l"
            + "           where l.tenant_id = r.tenant_id and l.run_id = r.id) as shop_count,"
            + "         (select count(distinct s.reference_id) from allocation_writeback_snapshot s"
            + "           where s.tenant_id = r.tenant_id and s.run_id = r.id) as observed_shop_count"
            + "    from allocation_run r left join sku_ref sr"
            + "      on sr.tenant_id = r.tenant_id and sr.id = r.sku_id, win"
            + "   where r.tenant_id = current_setting('app.current_tenant')::uuid"
            + "     and (?::uuid is null or r.sku_id = ?::uuid)"
            + "     and (?::text is null or r.method = ?::text)"
            + "     and (win.from_at is null or r.allocated_at >= win.from_at)"
            + "     and (win.to_at is null or r.allocated_at < win.to_at)"
            + ")";
    Object[] head = {from, to, skuId, skuId, m, m};
    long total = count(cte + " select count(*) as c from runs", head);
    var rows =
        sql.fetch(
            cte
                + " select * from runs order by allocated_at desc, id desc limit ? offset ?",
            concatAll(head, s, p * s));
    return new AllocationResultRunPage(rows.map(AllocationResultService::runRow), total, p, s);
  }

  // ============================================================ 回写观测

  /** 回写状态观测列表（真分页）。 */
  public AllocationWritebackPage writebacks(
      UUID runId,
      UUID referenceId,
      UUID warehouseId,
      String state,
      String sourceKind,
      int page,
      int size) {
    int p = Math.max(0, page);
    int s = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
    String st = state == null || state.isBlank() ? null : state.trim().toUpperCase(Locale.ROOT);
    String sk = sourceKind == null || sourceKind.isBlank() ? null : sourceKind.trim().toUpperCase(Locale.ROOT);
    String from =
        " from allocation_writeback_snapshot s"
            + " left join warehouse w on w.tenant_id = s.tenant_id and w.id = s.warehouse_id"
            + " where s.tenant_id = current_setting('app.current_tenant')::uuid"
            + "   and (?::uuid is null or s.run_id = ?::uuid)"
            + "   and (?::uuid is null or s.reference_id = ?::uuid)"
            + "   and (?::uuid is null or s.warehouse_id = ?::uuid)"
            + "   and (?::text is null or s.state = ?::text)"
            + "   and (?::text is null or s.source_kind = ?::text)";
    Object[] bind = {runId, runId, referenceId, referenceId, warehouseId, warehouseId, st, st, sk, sk};
    long total = count("select count(*) as c" + from, bind);
    var rows =
        sql.fetch(
            "select s.id, s.run_id, s.source_system, s.reference_id, s.reference_label,"
                + " s.warehouse_id, w.code as warehouse_code, w.name as warehouse_name,"
                + " s.state, s.attempted_quantity, s.written_quantity, s.consumed_quantity,"
                + " s.failure_reason, s.source_kind, s.source_reference, s.as_of,"
                + " s.recorded_by_membership_id, s.created_at"
                + from
                + " order by s.as_of desc, s.id desc limit ? offset ?",
            concatAll(bind, s, p * s));
    return new AllocationWritebackPage(rows.map(AllocationResultService::writebackRow), total, p, s);
  }

  /**
   * 登记一次平台回写状态的观测。
   *
   * <p>append-only：登记之后不可改、不可删（V189 的 REVOKE + 触发器各挡一次）。
   * 观测必须挂在一次**真实存在**的分摊行上（V189 的复合外键钉住）。
   */
  @Transactional
  public AllocationWritebackSaved record(AllocationWritebackInput input, UUID actorId) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    WritebackState state = writebackState(input.state());
    WritebackSourceKind kind = sourceKind(input.sourceKind());
    BigDecimal attempted = whole(input.attemptedQuantity());
    if (attempted.signum() < 0) {
      throw bad("attemptedQuantity 不能为负，收到 " + attempted);
    }
    BigDecimal written = input.writtenQuantity() == null ? null : whole(input.writtenQuantity());
    if (written != null && written.signum() < 0) {
      throw bad("writtenQuantity 不能为负，收到 " + written);
    }
    BigDecimal consumed = input.consumedQuantity() == null ? null : whole(input.consumedQuantity());
    if (consumed != null && consumed.signum() < 0) {
      throw bad("consumedQuantity 不能为负，收到 " + consumed);
    }
    String reason = input.failureReason() == null ? "" : input.failureReason().trim();
    if (state == WritebackState.SUCCEEDED && written == null) {
      throw bad(
          "state=SUCCEEDED 时必须给出 writtenQuantity（平台确认持有的可售件数）："
              + "成功了却不知道平台挂了多少，这次观测什么也没证明。");
    }
    if (state == WritebackState.FAILED && reason.isEmpty()) {
      throw bad("state=FAILED 时必须写失败原因：没有原因的失败在复盘时与「没发生过」长得一模一样。");
    }
    if (state == WritebackState.PENDING && !reason.isEmpty()) {
      throw bad("state=PENDING（已发出未确认）时不该有失败原因 —— 平台仍持有旧值，没有失败可言。");
    }
    if (input.asOf() == null) {
      throw bad("asOf（采集时刻）必填：观测成立的那一刻与入库那一刻不是一回事。");
    }
    if (input.sourceReference() == null || input.sourceReference().isBlank()) {
      throw bad("sourceReference 必填：没有来源标识，这一行就答不出「这个数是谁在什么时候告诉我们的」。");
    }

    UUID id = UUID.randomUUID();
    // ?::timestamptz 刻意排在绑定序列第一位（时间绑定纪律）。
    sql.execute(
        "with w as (select ?::timestamptz as as_of)"
            + " insert into allocation_writeback_snapshot (id,tenant_id,run_id,source_system,"
            + " reference_id,reference_label,warehouse_id,state,attempted_quantity,written_quantity,"
            + " consumed_quantity,failure_reason,source_kind,source_reference,as_of,"
            + " recorded_by_membership_id)"
            + " select ?,?,?,?,?,?,?,?,?::numeric,?::numeric,?::numeric,?,?,?,w.as_of,? from w",
        input.asOf(),
        id,
        tenant(),
        input.runId(),
        input.sourceSystem().trim(),
        input.referenceId(),
        input.referenceLabel() == null ? "" : input.referenceLabel(),
        input.warehouseId(),
        state.name(),
        attempted,
        written,
        consumed,
        reason,
        kind.name(),
        input.sourceReference().trim(),
        actorId);

    return new AllocationWritebackSaved(
        id,
        input.runId(),
        input.referenceId(),
        state,
        written,
        consumed,
        input.asOf(),
        state == WritebackState.FAILED
            ? "已登记一条失败的回写观测。平台仍持有旧值，延迟敞口由核对页算出来 —— "
                + "注意：失败的观测不会把平台当前值改小。"
            : state == WritebackState.SUCCEEDED
                ? "已登记一次成功回写。核对页会用它算「平台挂着的 vs 我方剩余」与敞口。"
                : "已登记一条未确认的观测。平台当前仍持有上一次成功回写的值。");
  }

  // ============================================================ 汇总

  /** 整个筛选口径下的汇总（不是当前页）。 */
  private AllocationResultSummary summary(Object[] cte, Object[] scoped) {
    var r =
        sql.fetchOne(
            RESULT_CTE
                + " select count(*) as row_count,"
                + "        coalesce(sum(j.allocated_qty), 0) as allocated_total,"
                + "        count(j.consumed_quantity) as consumed_known_count,"
                + "        coalesce(sum(j.consumed_quantity), 0) as consumed_total,"
                + "        count(j.platform_quantity) as platform_known_count,"
                + "        coalesce(sum(j.platform_quantity), 0) as platform_total,"
                + "        count(*) filter (where j.writeback_state is null) as unknown_state_count,"
                + "        count(*) filter (where j.consistency_state = 'CONSISTENT') as consistent_count,"
                + "        count(*) filter (where j.consistency_state = 'OVER_ADVERTISED') as over_advertised_count,"
                + "        count(*) filter (where j.consistency_state = 'UNDER_ADVERTISED') as under_advertised_count,"
                + "        count(*) filter (where j.consistency_state = 'UNKNOWN') as unknown_consistency_count,"
                + "        count(j.exposure_quantity) as exposure_computable_count,"
                + "        coalesce(sum(j.exposure_quantity), 0) as exposure_total,"
                + "        count(*) filter (where j.exposure_state = 'WITHIN_BUFFER') as within_buffer_count,"
                + "        count(*) filter (where j.exposure_state = 'BEYOND_BUFFER') as beyond_buffer_count,"
                + "        count(*) filter (where j.exposure_state = 'NO_EXPOSURE') as no_exposure_count,"
                + "        count(*) filter (where j.exposure_state = 'UNKNOWN') as unknown_exposure_count"
                + "   from judged j where "
                + where(true),
            concat(cte, scoped));
    return new AllocationResultSummary(
        r.get("row_count", Long.class),
        r.get("allocated_total", BigDecimal.class),
        r.get("consumed_known_count", Long.class),
        r.get("consumed_total", BigDecimal.class),
        r.get("platform_known_count", Long.class),
        r.get("platform_total", BigDecimal.class),
        r.get("unknown_state_count", Long.class),
        r.get("consistent_count", Long.class),
        r.get("over_advertised_count", Long.class),
        r.get("under_advertised_count", Long.class),
        r.get("unknown_consistency_count", Long.class),
        r.get("exposure_computable_count", Long.class),
        r.get("exposure_total", BigDecimal.class),
        r.get("within_buffer_count", Long.class),
        r.get("beyond_buffer_count", Long.class),
        r.get("no_exposure_count", Long.class),
        r.get("unknown_exposure_count", Long.class));
  }

  // ============================================================ 映射与工具

  private static AllocationResultRow resultRow(Record r) {
    return new AllocationResultRow(
        r.get("run_id", UUID.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("method", String.class),
        r.get("allocated_at", OffsetDateTime.class),
        r.get("policy_id", UUID.class),
        r.get("available_quantity", BigDecimal.class),
        r.get("run_buffer_quantity", BigDecimal.class),
        r.get("allocatable_quantity", BigDecimal.class),
        r.get("allocated_total", BigDecimal.class),
        r.get("unallocated_quantity", BigDecimal.class),
        r.get("source_system", String.class),
        r.get("reference_id", UUID.class),
        nullToEmpty(r.get("reference_label", String.class)),
        r.get("shop_operating_mode", String.class),
        r.get("allocation_order", Integer.class) == null ? 0 : r.get("allocation_order", Integer.class),
        r.get("allocated_qty", BigDecimal.class),
        r.get("outcome", String.class),
        r.get("sales_units", BigDecimal.class),
        r.get("sales_share", BigDecimal.class),
        r.get("warehouse_id", UUID.class),
        r.get("warehouse_code", String.class),
        r.get("warehouse_name", String.class),
        r.get("writeback_state", String.class) == null
            ? WritebackState.UNKNOWN
            : WritebackState.valueOf(r.get("writeback_state", String.class)),
        r.get("attempted_quantity", BigDecimal.class),
        r.get("platform_quantity", BigDecimal.class),
        r.get("consumed_quantity", BigDecimal.class),
        r.get("remaining_quantity", BigDecimal.class),
        r.get("failure_reason", String.class),
        r.get("as_of", OffsetDateTime.class),
        r.get("source_kind", String.class),
        r.get("source_reference", String.class),
        r.get("buffer_ratio", BigDecimal.class),
        r.get("buffer_ratio", BigDecimal.class) != null,
        r.get("buffer_quantity", BigDecimal.class) == null
            ? null
            : r.get("buffer_quantity", BigDecimal.class),
        r.get("writable_quantity", BigDecimal.class),
        r.get("exposure_quantity", BigDecimal.class),
        r.get("advertised_delta", BigDecimal.class),
        ConsistencyState.valueOf(r.get("consistency_state", String.class)),
        ExposureState.valueOf(r.get("exposure_state", String.class)),
        r.get("floor_status", String.class),
        r.get("floor_shortfall_qty", BigDecimal.class),
        r.get("suppressed", Boolean.class),
        r.get("suppression_reason", String.class));
  }

  private static AllocationReconciliationRun reconciliationRun(Record r) {
    return new AllocationReconciliationRun(
        r.get("run_id", UUID.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("allocated_at", OffsetDateTime.class),
        r.get("shop_count", Long.class),
        r.get("unknown_state_count", Long.class),
        r.get("consistent_count", Long.class),
        r.get("over_advertised_count", Long.class),
        r.get("under_advertised_count", Long.class),
        r.get("beyond_buffer_count", Long.class),
        r.get("within_buffer_count", Long.class),
        r.get("exposure_computable_count", Long.class),
        r.get("exposure_total", BigDecimal.class));
  }

  private static AllocationResultRunRow runRow(Record r) {
    long shopCount = r.get("shop_count", Long.class) == null ? 0 : r.get("shop_count", Long.class);
    long observed =
        r.get("observed_shop_count", Long.class) == null ? 0 : r.get("observed_shop_count", Long.class);
    return new AllocationResultRunRow(
        r.get("id", UUID.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("method", String.class),
        r.get("allocated_at", OffsetDateTime.class),
        r.get("available_quantity", BigDecimal.class),
        r.get("buffer_quantity", BigDecimal.class),
        r.get("allocatable_quantity", BigDecimal.class),
        r.get("allocated_total", BigDecimal.class),
        r.get("unallocated_quantity", BigDecimal.class),
        shopCount,
        observed,
        shopCount - observed);
  }

  private static AllocationWritebackRow writebackRow(Record r) {
    return new AllocationWritebackRow(
        r.get("id", UUID.class),
        r.get("run_id", UUID.class),
        r.get("source_system", String.class),
        r.get("reference_id", UUID.class),
        nullToEmpty(r.get("reference_label", String.class)),
        r.get("warehouse_id", UUID.class),
        r.get("warehouse_code", String.class),
        r.get("warehouse_name", String.class),
        WritebackState.valueOf(r.get("state", String.class)),
        r.get("attempted_quantity", BigDecimal.class),
        r.get("written_quantity", BigDecimal.class),
        r.get("consumed_quantity", BigDecimal.class),
        nullToEmpty(r.get("failure_reason", String.class)),
        WritebackSourceKind.valueOf(r.get("source_kind", String.class)),
        r.get("source_reference", String.class),
        r.get("as_of", OffsetDateTime.class),
        r.get("recorded_by_membership_id", UUID.class),
        r.get("created_at", OffsetDateTime.class));
  }

  /** CTE 部分的绑定：顺序固定，三处查询必须一致。 */
  private static Object[] cteBinds(AllocationResultQuery q, OffsetDateTime at) {
    String method = q.method() == null || q.method().isBlank() ? null : q.method().trim();
    return new Object[] {
      at,
      q.allocatedFrom(),
      q.allocatedTo(),
      q.runId(),
      q.runId(),
      q.skuId(),
      q.skuId(),
      method,
      method,
      q.referenceId(),
      q.referenceId()
    };
  }

  /** 外层筛选的绑定。{@code withWarehouse=false} 用于「仓归属未知」的统计。 */
  private static Object[] scopeBinds(
      AllocationResultQuery q, ResultScope scope, boolean withWarehouse) {
    List<Object> out = new ArrayList<>();
    if (withWarehouse) {
      out.add(q.warehouseId());
      out.add(q.warehouseId());
    }
    out.add(scope.name());
    return out.toArray();
  }

  private static Object[] concat(Object[] head, Object[] tail) {
    Object[] out = new Object[head.length + tail.length];
    System.arraycopy(head, 0, out, 0, head.length);
    System.arraycopy(tail, 0, out, head.length, tail.length);
    return out;
  }

  /** 拼接头部绑定与若干散值（分页参数）。 */
  private static Object[] concatAll(Object[] head, Object... tail) {
    Object[] out = new Object[head.length + tail.length];
    System.arraycopy(head, 0, out, 0, head.length);
    System.arraycopy(tail, 0, out, head.length, tail.length);
    return out;
  }

  /**
   * 数总数。<b>调用方必须传完整的 {@code select count(*) as c …}</b>：
   * 包一层子查询在 PG 里是非法语法，而它报的错是语法错误，不会提示真正的原因。
   */
  private long count(String select, Object[] bind) {
    var r = sql.fetchOne(select, bind);
    return r == null ? 0 : r.get("c", Long.class);
  }

  /** 件数口径：向下取整到整件，不允许小数混进来。 */
  private static BigDecimal whole(BigDecimal value) {
    return (value == null ? BigDecimal.ZERO : value).setScale(QTY_SCALE, RoundingMode.DOWN);
  }

  private static String nullToEmpty(String s) {
    return s == null ? "" : s;
  }

  private static ResultScope scope(String raw) {
    if (raw == null || raw.isBlank()) {
      return ResultScope.ALL;
    }
    try {
      return ResultScope.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad(
          "未知的 scope："
              + raw
              + "（应为 ALL / EXPOSURE / BEYOND_BUFFER / INCONSISTENT / UNKNOWN 之一）");
    }
  }

  private static WritebackState writebackState(String raw) {
    if (raw == null || raw.isBlank()) {
      throw bad("state 必填（SUCCEEDED / FAILED / PENDING）");
    }
    try {
      return WritebackState.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad("未知的 state：" + raw + "（应为 SUCCEEDED / FAILED / PENDING 之一）");
    }
  }

  private static WritebackSourceKind sourceKind(String raw) {
    if (raw == null || raw.isBlank()) {
      throw bad("sourceKind 必填（EVENT / API / MANUAL）");
    }
    try {
      return WritebackSourceKind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad("未知的 sourceKind：" + raw + "（应为 EVENT / API / MANUAL 之一）");
    }
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  /** 租户来自 RLS 的同一个来源，绝不来自任何请求参数或客户端头。 */
  private UUID tenant() {
    return sql.fetchOne("select current_setting('app.current_tenant',true)::uuid as t")
        .get("t", UUID.class);
  }
}
