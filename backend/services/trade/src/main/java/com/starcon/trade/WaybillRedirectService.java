package com.starcon.trade;

import static com.starcon.trade.WaybillRedirectModels.*;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * 10.2.1.3 改址与取消。
 *
 * <p>本模块<b>不拥有运单号</b>：运单申请与出号时刻是 10.2.1.2 的
 * {@code logistics_waybill_request}（trade V128），本模块对它只做<b>只读</b>查询 ——
 * 不 ALTER、不建跨模块 FK、不写任何一列。改址 / 取消**只对已出号（ISSUED）的运单**有意义；
 * 还没出号的作废仍走 10.2.1.2 的 cancel。
 *
 * <p>本模块拥有三件事（V135）：
 *
 * <ul>
 *   <li>{@code logistics_carrier_redirect_rule} —— 渠道<b>规则与时限</b>（蓝图输入句第二项），
 *       租户可维护的表，不是代码常量（各承运商的改址窗口本来就不一样）。
 *   <li>{@code logistics_waybill_redirect} / {@code logistics_waybill_cancel} ——
 *       改址与取消单据，附<b>判定基准</b>（出号时刻 + 截止时刻）与<b>明确结论</b>。
 *   <li>{@code logistics_buyer_notification_task} —— 买家通知任务（蓝图输出句第三项），
 *       状态恒为 QUEUED、ownerModule=15：本模块<b>不自己发通知</b>。
 * </ul>
 *
 * <p><b>本模块最要紧的一条</b>（蓝图加粗句「超时的改址请求要明确告知能否成功而不是让客服猜」）：
 * 判定被拆成两个<b>彼此独立</b>的维度 ——
 *
 * <ol>
 *   <li>{@code verdict}（时间 / 规则维度）：WITHIN_WINDOW / DEADLINE_PASSED /
 *       CHANNEL_UNSUPPORTED / RULE_UNREGISTERED；</li>
 *   <li>{@code canSucceed}（结论维度）：能不能成，以及<b>为什么</b>。</li>
 * </ol>
 *
 * 「在时限内但渠道没接通」与「已超时限」必须能被界面<b>分开显示</b>：
 * 前者接通了还能成，后者再也不会成 —— 客服对买家说的话完全不同。
 *
 * <p>⚠️ <b>时限判定不吃 now()</b>：基准是
 * {@code 出号时刻 issued_at + 规则登记的 deadline_hours}，两者都是<b>显式传入或落库的数据</b>，
 * 而发起时刻 {@code requestedAt} 由调用方显式给定（补录历史诉求时必须给真实发起时刻）。
 * 所以同一条单据无论何时重算，结论都不变 —— 测试因此可以钉死时刻而不是靠 now() 碰运气。
 */
@Service
public class WaybillRedirectService {

  private static final int MAX_PAGE_SIZE = 200;

  private final DSLContext sql;
  private final CarrierRedirectAdapterRegistry adapters;

  public WaybillRedirectService(DSLContext sql, CarrierRedirectAdapterRegistry adapters) {
    this.sql = sql;
    this.adapters = adapters;
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>四处「拿不到却必须说」：承运商侧改址 / 取消接口（10.1 未交付）、
   * 承运商侧轨迹（10.3 未交付，决定「这一票是否已揽收、还能不能改」）、
   * 买家通知的实际送达（15 未交付，本模块只落任务）、平台通知回写（2.5.1.1 未交付）。
   * 四处一律 {@code available=false} 并点名 ownerModule 与影响。
   */
  public WaybillRedirectCapabilities capabilities() {
    return new WaybillRedirectCapabilities(
        List.of(
            new RedirectCapabilityDimension(
                "REDIRECT_DEADLINE_VERDICT",
                "改址 / 取消的时限判定（能否成功 + 为什么）",
                true,
                "出号时刻来自同服务 10.2.1.2 的 logistics_waybill_request（trade V128，**库内只读**）；"
                    + "时限来自本模块的 logistics_carrier_redirect_rule（**租户可维护的表**）。"
                    + "截止时刻 = 出号时刻 + 规则小时数，**落库成可查询的事实**并由 DB 触发器重算核对",
                null,
                "判定基准是**数据**不是代码里的 if (hoursSinceIssue < 24)："
                    + "各承运商窗口不同，规则改了历史单据的判据依然可复算"),
            new RedirectCapabilityDimension(
                "CARRIER_REDIRECT_CALL",
                "承运商侧改址 / 取消的**实际调用**",
                false,
                "10.1 物流渠道全部未交付（台账 10.1.1.1/10.1.1.2/10.1.1.3/10.1.2.1/10.1.2.2 全未开发），"
                    + "集成头内没有任何承运商连接配置",
                "10.1.1.1 承运商列表 / 10.1.1.2 渠道配置（连接与凭据归 channel 与 secret manager）",
                "本模块定义 CarrierRedirectPort 统一接口并按 adapterKey 索引，"
                    + "**不凭空受理一次改址**：没有 vendor 实现时该单置 outcome=NOT_ATTEMPTED "
                    + "并写明「渠道未接通」。**「已受理」只可能来自承运商真实回话**"),
            new RedirectCapabilityDimension(
                "CARRIER_PICKUP_STATE",
                "承运商侧是否已揽收（决定这一票还能不能改 / 撤）",
                false,
                "10.3 物流跟踪未交付，集成头内没有任何承运商轨迹数据源",
                "10.3.1.1 物流轨迹 / 10.3.2.2 揽收与签收确认",
                "本模块只能按**登记的时限**判定，**不知道这一票是否已在派送车上**。"
                    + "时限内判成「可改」也可能被承运商以「已揽收」拒收 —— "
                    + "那会如实落在 outcome=REJECTED，而不是被提前猜成成功"),
            new RedirectCapabilityDimension(
                "ORDER_ADDRESS_LINKAGE",
                "联动订单地址变更（9.1.1.3）",
                true,
                "同服务 9.1.1.3 的 order_change_request（trade V119，**库内只读**，"
                    + "本模块**不写它任何一列**）：改址单携带 order_change_request_id 即为联动",
                null,
                "联动 = **建立引用并校验**（存在 / change_kind=ADDRESS_MODIFY / 同订单），"
                    + "**不是**另起一套订单变更单据 —— 订单侧单据的写入所有者永远是 9.1.1.3"),
            new RedirectCapabilityDimension(
                "BUYER_NOTIFICATION",
                "买家通知任务（15）",
                true,
                "本模块把通知落成 logistics_buyer_notification_task（**真实待发任务**），"
                    + "status 恒为 QUEUED、ownerModule=15",
                null,
                "本模块**只建任务不发送**：跨服务禁止直连对方库 / 跨库 FK / 视图（AGENTS.md）。"
                    + "「已送达」本模块**不产出** —— 15 消费后由它自己管发出状态。"
                    + "有结论（受理 / 拒收 / 超时 / 不支持）才建任务；还在等回话的不建"),
            new RedirectCapabilityDimension(
                "PLATFORM_NOTIFY_WRITEBACK",
                "改址 / 取消结果回写平台",
                false,
                "2.5.1.1 平台回写未交付，本模块**不自己发平台通知**",
                "2.5.1.1 平台回写 / 9.1.1.3 的 writeback 任务（V119）",
                "订单侧的写回任务归 9.1.1.3；本模块只产生**运单侧**单据与通知任务，"
                    + "不越界代写平台的回写单"),
            new RedirectCapabilityDimension(
                "REDIRECT_FEE",
                "渠道改址费 / 撤单费",
                false,
                "10.1 物流渠道未交付，没有任何渠道费率配置；本模块也不落金额列",
                "10.1.2.1 运费计算器 / 10.1.2.2 渠道比价",
                "本模块**不编一个改址费**：将来渠道收费时必须用 BigDecimal + NUMERIC(19,4)，"
                    + "禁浮点（AGENTS.md）。现在界面上是「无费率口径」，不是 0")),
        List.of("WITHIN_WINDOW", "DEADLINE_PASSED", "CHANNEL_UNSUPPORTED", "RULE_UNREGISTERED"),
        List.of("ACCEPTED", "REJECTED", "CARRIER_UNAVAILABLE", "ADAPTER_MISSING"),
        List.of("REDIRECT_ACCEPTED", "REDIRECT_REJECTED", "REDIRECT_EXPIRED",
            "REDIRECT_UNSUPPORTED", "CANCEL_ACCEPTED", "CANCEL_REJECTED", "CANCEL_EXPIRED",
            "CANCEL_UNSUPPORTED"),
        "verdict 判的是**时间与规则**（在不在时限内、渠道支持不支持、规则登没登记），"
            + "canSucceed 判的是**能不能成** —— 两者刻意独立：「在时限内但渠道没接通」"
            + "是 verdict=WITHIN_WINDOW 且 canSucceed=false，而话术是「接通后仍可成功」；"
            + "「已超时限」是 verdict=DEADLINE_PASSED 且 canSucceed=false，"
            + "话术是「这一票**再也不会**成功」",
        "截止时刻 = 出号时刻 + 该承运商登记的时限小时数，**逐单落库**并由 DB 触发器"
            + "按规则重算核对；判定基准是发起时刻 requestedAt（可显式补录），"
            + "**不吃 now()**，所以同一张单据的结论任何时候重算都不变",
        "本模块**只对已出号（ISSUED）的运单**生效：未出号的作废走 10.2.1.2；"
            + "本模块只读 V128，不 ALTER 不写不建跨模块 FK，跨车道 DDL 回归面 = 0");
  }

  // ================================================================ 渠道规则

  /** 规则清单：真分页不需要（每承运商一条，量级是承运商数），但总数要给准。 */
  public CarrierRedirectRulePage rules(String carrierCode) {
    String where = "";
    Object[] params = new Object[0];
    if (carrierCode != null && !carrierCode.isBlank()) {
      where = " where carrier_code = ?";
      params = new Object[] {carrierCode.trim().toUpperCase(Locale.ROOT)};
    }
    List<CarrierRedirectRuleRow> items = new ArrayList<>();
    sql.fetch(
            "select r.id, r.carrier_code, c.carrier_channel, r.redirect_supported,"
                + " r.redirect_deadline_hours, r.cancel_supported, r.cancel_deadline_hours,"
                + " r.unsupported_reason, r.remark, r.registered_by_membership_id, r.registered_at"
                + " from logistics_carrier_redirect_rule r"
                + " left join logistics_carrier c on c.tenant_id = r.tenant_id"
                + "   and c.code = r.carrier_code" + where
                + " order by r.carrier_code asc",
            params)
        .forEach(r -> items.add(ruleRow(r)));
    return new CarrierRedirectRulePage(items, items.size());
  }

  /**
   * 登记 / 更新一个承运商的改址与取消规则（**upsert**：一个承运商一条）。
   *
   * <p>承运商必须**已登记在 10.2.1.2 的 logistics_carrier 里**（服务层查得到才写，
   * 找不到就 404 而不是 500）—— 否则会留下一条挂在不存在的承运商上的规则，
   * 而「按承运商分组」会永远取不到它。
   */
  @Transactional
  public CarrierRedirectRuleRow upsertRule(CarrierRedirectRuleInput input, UUID membershipId) {
    String carrierCode = input.carrierCode().trim().toUpperCase(Locale.ROOT);
    boolean redirectSupported = Boolean.TRUE.equals(input.redirectSupported());
    boolean cancelSupported = Boolean.TRUE.equals(input.cancelSupported());

    // 「支持就必须有时限、不支持就不能有时限」在服务层也先说一遍，
    // 给出可读的中文错误；DB 层的 CHECK 是最后一道（绕过服务层写 SQL 同样被拒）。
    if (redirectSupported && input.redirectDeadlineHours() == null)
      throw badRequest("承运商 " + carrierCode + " 声明支持改址，就必须登记改址时限小时数"
          + "（否则等于「改址可以无限期成功」，现实里没有这样的承运商）");
    if (!redirectSupported && input.redirectDeadlineHours() != null)
      throw badRequest("承运商 " + carrierCode + " 声明不支持改址，就不该给出改址时限");
    if (cancelSupported && input.cancelDeadlineHours() == null)
      throw badRequest("承运商 " + carrierCode + " 声明支持取消，就必须登记取消时限小时数");
    if (!cancelSupported && input.cancelDeadlineHours() != null)
      throw badRequest("承运商 " + carrierCode + " 声明不支持取消，就不该给出取消时限");
    if (!redirectSupported && !cancelSupported
        && (input.unsupportedReason() == null || input.unsupportedReason().isBlank()))
      throw badRequest("承运商 " + carrierCode + " 改址与取消都不支持时，必须写明原因"
          + "（否则界面上只有一片灰，没人知道该找谁）");

    var carrier = sql.fetchOne(
        "select code from logistics_carrier where code = ?", carrierCode);
    if (carrier == null)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND,
          "承运商 " + carrierCode + " 尚未在 10.2.1.2 的运单申请里登记，"
              + "请先到「运单申请 · 承运商」登记后再配置改址规则");

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    sql.execute(
        "insert into logistics_carrier_redirect_rule"
            + " (id, tenant_id, carrier_code, redirect_supported, redirect_deadline_hours,"
            + "  cancel_supported, cancel_deadline_hours, unsupported_reason, remark,"
            + "  registered_by_membership_id, registered_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?::timestamptz)"
            + " on conflict (tenant_id, carrier_code) do update set"
            + "  redirect_supported = excluded.redirect_supported,"
            + "  redirect_deadline_hours = excluded.redirect_deadline_hours,"
            + "  cancel_supported = excluded.cancel_supported,"
            + "  cancel_deadline_hours = excluded.cancel_deadline_hours,"
            + "  unsupported_reason = excluded.unsupported_reason,"
            + "  remark = excluded.remark,"
            + "  registered_by_membership_id = excluded.registered_by_membership_id,"
            + "  registered_at = excluded.registered_at",
        UUID.randomUUID(), currentTenant(), carrierCode, redirectSupported,
        redirectSupported ? input.redirectDeadlineHours() : null,
        cancelSupported, cancelSupported ? input.cancelDeadlineHours() : null,
        nz(input.unsupportedReason()), nz(input.remark()), membershipId, now);
    // withNano(0) 之后按 nanosecond=0 补一位，避免带小数秒的 OffsetDateTime 写进去。
    // 上面的 withNano(0) 已经处理，这里只是显式注释这一点。

    return ruleRow(sql.fetchOne(
        "select r.id, r.carrier_code, c.carrier_channel, r.redirect_supported,"
            + " r.redirect_deadline_hours, r.cancel_supported, r.cancel_deadline_hours,"
            + " r.unsupported_reason, r.remark, r.registered_by_membership_id, r.registered_at"
            + " from logistics_carrier_redirect_rule r"
            + " left join logistics_carrier c on c.tenant_id = r.tenant_id"
            + "   and c.code = r.carrier_code"
            + " where r.carrier_code = ?",
        carrierCode));
  }

  // ================================================================ 改址

  /**
   * 改址清单：真分页 + 真筛选 + 排序白名单，<b>汇总独立于分页</b>。
   *
   * <p>本模块最高频的读：客服打开一票运单先看「还能不能改」。
   * 汇总里 {@code deadlinePassed} 与 {@code canSucceedCount} 分开给 ——
   * 两者相加不是总数（还有「规则未登记」这一类），混起来会让运营以为
   * 「剩下的都能改」。
   */
  public WaybillRedirectPage redirects(
      String carrierCode, String verdict, String outcome, String salesOrderNo, String keyword,
      OffsetDateTime dateFrom, OffsetDateTime dateTo, String sort, int page, int size) {
    int limit = pageSize(size);
    var f = redirectFilters(carrierCode, verdict, outcome, salesOrderNo, keyword, dateFrom, dateTo,
        true);
    String where = f.clause.isEmpty() ? "" : " where " + f.clause;

    // ⚠️ 必须是 `f.params.toArray()`，不能直接传 `f.params`：varargs 会把整个 List
    // 当成**一个**绑定值（而不是拆成 N 个占位符），jOOQ 随即抛
    // 「Error while writing value at bind index」—— 端点上表现为 409，
    // 而 409 在这里是「数据冲突」，会把一个纯 SQL 调用错误说成业务冲突。
    long total =
        countOf("select count(*) from logistics_waybill_redirect r" + where, f.params.toArray());
    List<WaybillRedirectRow> items = new ArrayList<>();
    if (total > 0) {
      sql.fetch(
              "select r.id, r.redirect_no, r.request_id, r.request_no, r.waybill_no, r.carrier_code,"
                  + " r.issued_at_snapshot, r.deadline_at, r.recipient_name, r.recipient_country,"
                  + " r.address_line, r.city, r.postal_code, r.reason, r.verdict, r.can_succeed,"
                  + " r.can_succeed_reason, r.outcome, r.carrier_ref, r.outcome_reason,"
                  + " r.dispatched_at, r.order_change_request_id, r.sales_order_no,"
                  + " r.requested_at, r.updated_at, w.carrier_channel, n.id as notification_id,"
                  + " n.notification_no, oc.status as order_change_status"
                  + " from logistics_waybill_redirect r"
                  + " join logistics_waybill_request w on w.tenant_id = r.tenant_id"
                  + "   and w.id = r.request_id"
                  + " left join logistics_buyer_notification_task n"
                  + "   on n.tenant_id = r.tenant_id and n.redirect_id = r.id"
                  + " left join order_change_request oc"
                  + "   on oc.tenant_id = r.tenant_id and oc.id = r.order_change_request_id"
                  + where + " order by " + redirectOrderBy(sort)
                  + " limit ? offset ?",
              concat(f.params, limit, (long) Math.max(page, 0) * limit))
          .forEach(x -> items.add(redirectRow(x)));
    }

    // 汇总：一条**不带 LIMIT/OFFSET** 的独立查询，永远覆盖筛选后的全量。
    // 跟着分页走的汇总是列表页最常见的假活。
    Record s = sql.fetchOne(
        "select count(*) as total,"
            + " count(*) filter (where verdict = 'WITHIN_WINDOW') as within_window,"
            + " count(*) filter (where verdict = 'DEADLINE_PASSED') as deadline_passed,"
            + " count(*) filter (where verdict = 'CHANNEL_UNSUPPORTED') as unsupported,"
            + " count(*) filter (where verdict = 'RULE_UNREGISTERED') as unregistered,"
            + " count(*) filter (where can_succeed) as can_succeed,"
            + " count(*) filter (where outcome = 'ACCEPTED') as accepted,"
            + " count(*) filter (where outcome = 'REJECTED') as rejected,"
            + " count(*) filter (where outcome = 'NOT_ATTEMPTED') as not_attempted,"
            + " count(*) filter (where exists (select 1 from logistics_buyer_notification_task n"
            + "    where n.tenant_id = r.tenant_id and n.redirect_id = r.id)) as notified"
            + " from logistics_waybill_redirect r" + where,
        f.params.toArray());

    return new WaybillRedirectPage(
        items, total,
        longOf(s.get("within_window", Long.class)),
        longOf(s.get("deadline_passed", Long.class)),
        longOf(s.get("unsupported", Long.class)),
        longOf(s.get("unregistered", Long.class)),
        longOf(s.get("can_succeed", Long.class)),
        longOf(s.get("accepted", Long.class)),
        longOf(s.get("rejected", Long.class)),
        longOf(s.get("not_attempted", Long.class)),
        longOf(s.get("notified", Long.class)),
        List.of(
            "汇总覆盖**筛选后的全量**，与分页无关（分页只切 items）",
            "deadlinePassed（已超时限）**再也不会成功**，不是重试一下就能过 —— "
                + "这是蓝图加粗句点名要给客服的准话",
            "canSucceed 与 verdict **不是同一维度**：在时限内但渠道没接通的票，"
                + "verdict=WITHIN_WINDOW 而 canSucceed=false（接通后仍可成功）",
            "unregistered（未登记规则）与 unsupported（渠道不支持）是两回事："
                + "前者是**系统不知道**，后者是**明确不支持**",
            "pendingNotificationCount 只统计**已建任务**的行；还在等承运商回话的"
                + "（NOT_ATTEMPTED 且未接通）刻意**不建**通知任务，"
                + "因为那时还没有结论可通知买家"));
  }

  /** 单条改址详情：单据 + 承运商侧口径 + 联动的订单变更 + 通知任务 + 可执行动作。 */
  public WaybillRedirectDetail redirect(String id) {
    UUID redirectId = parseUuid(id, "改址单不存在：" + id);
    Record r = redirectRecord(redirectId);
    if (r == null) throw notFound("改址单不存在：" + id);
    WaybillRedirectRow row = redirectRow(r);
    return new WaybillRedirectDetail(row, carrierState(row.carrierCode()), nextActions(row),
        List.of(
            "运单号与出号时刻由 10.2.1.2 拥有，本模块只读",
            "改址单一旦写下**判定即不可改写**（DB 触发器钉死）：要重新判定请发起一条新的改址单",
            "「已受理」只可能来自承运商真实回话；渠道未接通时本模块**不受理**",
            "承运商侧是否已揽收**没有数据源**（10.3 未交付），"
                + "所以时限内判成「可改」仍可能被承运商以「已揽收」拒收"));
  }

  /**
   * 发起一次改址。
   *
   * <p>核心判定链（<b>每一步的结论都落库，不藏在中间态</b>）：
   *
   * <ol>
   *   <li>运单必须存在且<b>已出号</b>（否则指向 10.2.1.2 的作废）；</li>
   *   <li>取该承运商的规则：没登记 → {@code RULE_UNREGISTERED}；</li>
   *   <li>规则不支持改址 → {@code CHANNEL_UNSUPPORTED}；</li>
   *   <li>算出截止时刻 = 出号时刻 + 规则小时数，比较发起时刻 → 在窗口内 / 已超时；</li>
   *   <li>在窗口内才去问适配器通不通：通则派发，未通则不派发（<b>不凭空受理</b>）。</li>
   * </ol>
   *
   * <p>{@code requestedAt} 显式传入时用它作为发起时刻与判定基准（补录历史诉求的真实时刻）。
   * 传了则用服务端当前时刻。⚠️ 判定的<b>基准</b>永远是 issued_at（落库数据），
   * now() 只在调用方没给 requestedAt 时用于「此刻发起」这一个语义。
   */
  @Transactional
  public WaybillRedirectRow redirect(String id, WaybillRedirectInput input, UUID membershipId) {
    UUID requestId = parseUuid(id, "运单不存在：" + id);
    Waybill w = requireIssuedWaybill(requestId);
    OffsetDateTime requestedAt =
        input.requestedAt() == null
            ? OffsetDateTime.now(ZoneOffset.UTC).withNano(0)
            : input.requestedAt().withNano(0);

    // 订单联动校验：必须是 9.1.1.3 的 ADDRESS_MODIFY 单，且与本运单同订单。
    // 联动 = 引用别人的单据，所以**校验**归本模块，**写入**归 9.1.1.3。
    if (input.orderChangeRequestId() != null)
      requireLinkedOrderChange(input.orderChangeRequestId(), w.salesOrderNo());

    CarrierRule rule = carrierRule(w.carrierCode());
    DeadlineVerdict verdict;
    OffsetDateTime deadline = null;
    if (rule == null) {
      verdict = DeadlineVerdict.RULE_UNREGISTERED;
    } else if (!rule.redirectSupported()) {
      verdict = DeadlineVerdict.CHANNEL_UNSUPPORTED;
    } else {
      deadline = w.issuedAt().plusHours(rule.redirectDeadlineHours());
      verdict = requestedAt.isAfter(deadline)
          ? DeadlineVerdict.DEADLINE_PASSED
          : DeadlineVerdict.WITHIN_WINDOW;
    }

    // canSucceed 与 verdict **独立**：在时限内但渠道没接通，仍是「现在不能成，
    // 但接通后仍可成」；已超时则是「再也不会成」。
    boolean canSucceed;
    String reason;
    if (verdict == DeadlineVerdict.WITHIN_WINDOW) {
      var resolution = adapters.resolve(w.carrierCode(), adapterKeyOf(w.carrierCode()));
      if (resolution instanceof CarrierRedirectPort.Resolution.Unavailable u) {
        canSucceed = false;
        reason = "在改址时限内（截止 " + deadline + "），但**渠道未接通**，"
            + "现在发不出去；接通后仍可成功。原因：" + u.reason();
      } else {
        canSucceed = true;
        reason = "在改址时限内（截止 " + deadline + "）且渠道已接通，可以提交改址。"
            + "⚠️ 承运商是否受理仍以它真实回话为准（是否已揽收本模块无法预知）";
      }
    } else {
      canSucceed = false;
      reason =
          switch (verdict) {
            case DEADLINE_PASSED -> "已超过该承运商的改址时限（截止 " + deadline
                + "）：这一票**再也不会**改址成功，不是重试一下就能过。"
                + "请改用「取消 + 重新发货」或联系买家确认其它方案";
            case CHANNEL_UNSUPPORTED -> "该承运商不支持改址（"
                + rule.unsupportedReason() + "）：改址**一定**不会成功";
            case RULE_UNREGISTERED -> "尚未登记该承运商的改址规则，本模块**不知道**它支持不支持，"
                + "因此不受理改址（不猜）。请先登记规则";
            default -> "无法判定";
          };
    }

    String redirectNo = nextNo("waybill_redirect_no_seq", "RAC");
    var carrier = carrierMaster(w.carrierCode());
    UUID id2 = UUID.randomUUID();
    sql.execute(
        "insert into logistics_waybill_redirect"
            + " (id, tenant_id, redirect_no, request_id, request_no, waybill_no, carrier_code,"
            + "  issued_at_snapshot, deadline_at, recipient_name, recipient_country, address_line,"
            + "  city, postal_code, reason, verdict, can_succeed, can_succeed_reason, outcome,"
            + "  order_change_request_id, sales_order_no, requested_by_membership_id,"
            + "  requested_at, updated_at)"
            + " values (?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,?,?,?,?,?,?,?,?,'NOT_ATTEMPTED',?,?,?,"
            + " ?::timestamptz,?::timestamptz)",
        id2, currentTenant(), redirectNo, w.id(), w.requestNo(), w.waybillNo(), w.carrierCode(),
        w.issuedAt(), deadline, input.recipientName(), input.recipientCountry(),
        nz(input.addressLine()), nz(input.city()), nz(input.postalCode()), input.reason(),
        verdict.name(), canSucceed, reason, input.orderChangeRequestId(), w.salesOrderNo(),
        membershipId, requestedAt, requestedAt);

    // 只有 verdict=WITHIN_WINDOW 且渠道已接通才真正派发。
    // 判定为「不能成」的票**绝不**去调承运商 —— 那既浪费配额，也会在承运商侧
    // 留下一条永远不会被处理的请求。
    if (verdict == DeadlineVerdict.WITHIN_WINDOW && canSucceed) {
      dispatchRedirect(id2, w, input, carrier, redirectNo);
    }

    // 有结论才通知买家。⚠️ 判定依据必须是**落库后的那一行**，不能是本方法里的局部变量
    // canSucceed —— 派发已经把承运商的真实回话写回库里了：承运商拒收时局部变量仍是
    // true，照它建任务会给买家发一条「改址已受理」，而货根本没改（最贵的一种错）。
    maybeNotifyRedirect(id2, redirectRecord(id2));

    return redirectRow(redirectRecord(id2));
  }

  /** 真正派发一次改址。承运商真实回话才写 ACCEPTED / REJECTED，绝不编。 */
  private void dispatchRedirect(UUID redirectId, Waybill w, WaybillRedirectInput input,
      Carrier carrier, String redirectNo) {
    var resolution = adapters.resolve(w.carrierCode(), carrier == null ? "" : carrier.adapterKey());
    if (resolution instanceof CarrierRedirectPort.Resolution.Unavailable u) {
      // 兜底：判定阶段说「已接通」，真派发时又不通（如灰度关掉了）。
      // 如实记 outcome=NOT_ATTEMPTED 并写明，不假装成功。
      sql.execute(
          "update logistics_waybill_redirect set can_succeed = false,"
              + " can_succeed_reason = ? , updated_at = now() at time zone 'UTC' where id = ?",
          "渠道在派发时变为未接通：" + u.reason(), redirectId);
      return;
    }
    var port = ((CarrierRedirectPort.Resolution.Connected) resolution).port();
    CarrierRedirectPort.RedirectOutcome outcome;
    try {
      outcome = port.redirect(new CarrierRedirectPort.RedirectCommand(
          redirectId, redirectNo, w.waybillNo(), w.carrierCode(),
          carrier == null ? "" : carrier.carrierChannel(),
          input.recipientName(), input.recipientCountry(), nz(input.addressLine()),
          nz(input.city()), nz(input.postalCode())));
    } catch (RuntimeException e) {
      // 承运商侧异常**如实落库**为不可达，不吞掉、不假装受理。
      outcome = CarrierRedirectPort.RedirectOutcome.unavailable(
          e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
    }
    persistRedirectOutcome(redirectId, outcome);
  }

  private void persistRedirectOutcome(UUID redirectId,
      CarrierRedirectPort.RedirectOutcome outcome) {
    // 只承认「承运商真实回了 ACCEPTED」这一种成功；其余一律按未受理 / 被拒处理。
    // ⚠️ 这里比对的是**端口**枚举（CarrierOutcome，含两个瞬时态），
    // 落库的列只有三种值 —— 不可达 / 未接通都落成 NOT_ATTEMPTED，原因进 outcome_reason。
    boolean accepted = outcome.outcome() == CarrierOutcome.ACCEPTED;
    sql.execute(
        "update logistics_waybill_redirect set outcome = ?,"
            + " can_succeed = ?, carrier_ref = ?, outcome_reason = ?,"
            + " can_succeed_reason = ?,"
            + " dispatched_at = ?::timestamptz, updated_at = ?::timestamptz"
            + " where id = ?",
        accepted ? "ACCEPTED"
            : (outcome.outcome() == CarrierOutcome.REJECTED
                ? "REJECTED" : "NOT_ATTEMPTED"),
        accepted,
        outcome.carrierRef(),
        nz(outcome.reason()),
        accepted ? "承运商已受理改址（回执 " + outcome.carrierRef() + "）"
            : "承运商**未受理**改址：" + nz(outcome.reason()),
        OffsetDateTime.now(ZoneOffset.UTC).withNano(0),
        OffsetDateTime.now(ZoneOffset.UTC).withNano(0),
        redirectId);
  }

  /**
   * 有结论才建买家通知任务（蓝图输出句第三项；15 未交付，本模块只建任务不发送）。
   *
   * <p>入参是**落库后的那一行**而不是判定阶段的局部变量：派发可能已经把承运商的
   * 真实回话（ACCEPTED / REJECTED）写回库里，通知类型必须跟着真实结果走。
   * 按局部变量建任务会在承运商拒收时给买家发一条「改址已受理」。
   */
  private void maybeNotifyRedirect(UUID redirectId, Record stored) {
    NotificationKind kind =
        notificationKind(
            DeadlineVerdict.valueOf(stored.get("verdict", String.class)),
            StoredOutcome.valueOf(stored.get("outcome", String.class)),
            true);
    if (kind == null) return; // 还没有结论 → 不通知
    createNotification(redirectId, null, kind);
  }

  // ================================================================ 取消

  /** 取消清单：与改址同一形状。 */
  public WaybillCancelPage cancels(
      String carrierCode, String verdict, String outcome, String salesOrderNo, String keyword,
      OffsetDateTime dateFrom, OffsetDateTime dateTo, String sort, int page, int size) {
    int limit = pageSize(size);
    // 取消单**没有** recipient_name：关键词只搜单号 / 运单号 / 申请单号（见 redirectFilters）。
    var f = redirectFilters(carrierCode, verdict, outcome, salesOrderNo, keyword, dateFrom, dateTo,
        false);
    String where = f.clause.isEmpty() ? "" : " where " + f.clause;

    long total =
        countOf("select count(*) from logistics_waybill_cancel r" + where, f.params.toArray());
    List<WaybillCancelRow> items = new ArrayList<>();
    if (total > 0) {
      sql.fetch(
              "select r.id, r.cancel_no, r.request_id, r.request_no, r.waybill_no, r.carrier_code,"
                  + " r.issued_at_snapshot, r.deadline_at, r.reason, r.verdict, r.can_succeed,"
                  + " r.can_succeed_reason, r.outcome, r.carrier_ref, r.outcome_reason,"
                  + " r.dispatched_at, r.order_cancellation_request_id, r.sales_order_no,"
                  + " r.requested_at, r.updated_at, w.carrier_channel, n.id as notification_id,"
                  + " n.notification_no, oc.status as order_cancellation_status"
                  + " from logistics_waybill_cancel r"
                  + " join logistics_waybill_request w on w.tenant_id = r.tenant_id"
                  + "   and w.id = r.request_id"
                  + " left join logistics_buyer_notification_task n"
                  + "   on n.tenant_id = r.tenant_id and n.cancel_id = r.id"
                  + " left join order_cancellation_request oc"
                  + "   on oc.tenant_id = r.tenant_id and oc.id = r.order_cancellation_request_id"
                  + where + " order by " + redirectOrderBy(sort)
                  + " limit ? offset ?",
              concat(f.params, limit, (long) Math.max(page, 0) * limit))
          .forEach(x -> items.add(cancelRow(x)));
    }

    Record s = sql.fetchOne(
        "select count(*) as total,"
            + " count(*) filter (where verdict = 'WITHIN_WINDOW') as within_window,"
            + " count(*) filter (where verdict = 'DEADLINE_PASSED') as deadline_passed,"
            + " count(*) filter (where verdict = 'CHANNEL_UNSUPPORTED') as unsupported,"
            + " count(*) filter (where verdict = 'RULE_UNREGISTERED') as unregistered,"
            + " count(*) filter (where can_succeed) as can_succeed,"
            + " count(*) filter (where outcome = 'ACCEPTED') as accepted,"
            + " count(*) filter (where outcome = 'REJECTED') as rejected,"
            + " count(*) filter (where outcome = 'NOT_ATTEMPTED') as not_attempted,"
            + " count(*) filter (where exists (select 1 from logistics_buyer_notification_task n"
            + "    where n.tenant_id = r.tenant_id and n.cancel_id = r.id)) as notified"
            + " from logistics_waybill_cancel r" + where,
        f.params.toArray());

    return new WaybillCancelPage(
        items, total,
        longOf(s.get("within_window", Long.class)),
        longOf(s.get("deadline_passed", Long.class)),
        longOf(s.get("unsupported", Long.class)),
        longOf(s.get("unregistered", Long.class)),
        longOf(s.get("can_succeed", Long.class)),
        longOf(s.get("accepted", Long.class)),
        longOf(s.get("rejected", Long.class)),
        longOf(s.get("not_attempted", Long.class)),
        longOf(s.get("notified", Long.class)),
        List.of(
            "取消只对**已出号（ISSUED）**的运单生效：未出号的作废走 10.2.1.2 的 cancel",
            "本表与 10.2.1.2 的作废是**两件事**：没号时没有承运商侧事实要撤销，有号时必须去承运商侧撤",
            "汇总覆盖筛选后的全量，与分页无关",
            "deadlinePassed 再也不会成功；canSucceed 与 verdict 是两个维度"));
  }

  public WaybillCancelDetail cancel(String id) {
    UUID cancelId = parseUuid(id, "取消单不存在：" + id);
    Record r = cancelRecord(cancelId);
    if (r == null) throw notFound("取消单不存在：" + id);
    WaybillCancelRow row = cancelRow(r);
    return new WaybillCancelDetail(row, carrierState(row.carrierCode()), cancelNextActions(row),
        List.of(
            "运单号与出号时刻由 10.2.1.2 拥有，本模块只读",
            "取消单一旦写下**判定即不可改写**（DB 触发器钉死）",
            "「已受理」只可能来自承运商真实回话；渠道未接通时本模块**不受理**"));
  }

  /** 发起一次已出号运单的取消（与改址同一套判定链，时限读规则的 cancel 小时数）。 */
  @Transactional
  public WaybillCancelRow cancel(String id, WaybillCancelInput input, UUID membershipId) {
    UUID requestId = parseUuid(id, "运单不存在：" + id);
    Waybill w = requireIssuedWaybill(requestId);
    OffsetDateTime requestedAt =
        input.requestedAt() == null
            ? OffsetDateTime.now(ZoneOffset.UTC).withNano(0)
            : input.requestedAt().withNano(0);

    if (input.orderCancellationRequestId() != null)
      requireLinkedOrderCancellation(input.orderCancellationRequestId(), w.salesOrderNo());

    CarrierRule rule = carrierRule(w.carrierCode());
    DeadlineVerdict verdict;
    OffsetDateTime deadline = null;
    if (rule == null) {
      verdict = DeadlineVerdict.RULE_UNREGISTERED;
    } else if (!rule.cancelSupported()) {
      verdict = DeadlineVerdict.CHANNEL_UNSUPPORTED;
    } else {
      deadline = w.issuedAt().plusHours(rule.cancelDeadlineHours());
      verdict = requestedAt.isAfter(deadline)
          ? DeadlineVerdict.DEADLINE_PASSED
          : DeadlineVerdict.WITHIN_WINDOW;
    }

    boolean canSucceed;
    String reason;
    if (verdict == DeadlineVerdict.WITHIN_WINDOW) {
      var resolution = adapters.resolve(w.carrierCode(), adapterKeyOf(w.carrierCode()));
      if (resolution instanceof CarrierRedirectPort.Resolution.Unavailable u) {
        canSucceed = false;
        reason = "在取消时限内（截止 " + deadline + "），但**渠道未接通**，"
            + "现在发不出去；接通后仍可成功。原因：" + u.reason();
      } else {
        canSucceed = true;
        reason = "在取消时限内（截止 " + deadline + "）且渠道已接通，可以提交取消。"
            + "⚠️ 承运商是否受理仍以它真实回话为准";
      }
    } else {
      canSucceed = false;
      reason =
          switch (verdict) {
            case DEADLINE_PASSED -> "已超过该承运商的取消时限（截止 " + deadline
                + "）：这一票**再也不会**取消成功。请改用「拒收 / 退回」等承运商侧其它路径";
            case CHANNEL_UNSUPPORTED -> "该承运商不支持取消已出号运单（"
                + rule.unsupportedReason() + "）：取消**一定**不会成功";
            case RULE_UNREGISTERED -> "尚未登记该承运商的取消规则，本模块**不知道**它支持不支持，"
                + "因此不受理（不猜）。请先登记规则";
            default -> "无法判定";
          };
    }

    String cancelNo = nextNo("waybill_cancel_no_seq", "WAC");
    var carrier = carrierMaster(w.carrierCode());
    UUID cancelId = UUID.randomUUID();
    sql.execute(
        "insert into logistics_waybill_cancel"
            + " (id, tenant_id, cancel_no, request_id, request_no, waybill_no, carrier_code,"
            + "  issued_at_snapshot, deadline_at, reason, verdict, can_succeed, can_succeed_reason,"
            + "  outcome, order_cancellation_request_id, sales_order_no,"
            + "  requested_by_membership_id, requested_at, updated_at)"
            + " values (?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,?,?,?,'NOT_ATTEMPTED',?,?,?,"
            + " ?::timestamptz,?::timestamptz)",
        cancelId, currentTenant(), cancelNo, w.id(), w.requestNo(), w.waybillNo(), w.carrierCode(),
        w.issuedAt(), deadline, input.reason(), verdict.name(), canSucceed, reason,
        input.orderCancellationRequestId(), w.salesOrderNo(), membershipId, requestedAt,
        requestedAt);

    if (verdict == DeadlineVerdict.WITHIN_WINDOW && canSucceed) {
      dispatchCancel(cancelId, w, input, carrier, cancelNo);
    }

    // 与改址同一解法：按**落库后**那一行判通知类型，别信判定阶段的局部变量。
    maybeNotifyCancel(cancelId, cancelRecord(cancelId));

    return cancelRow(cancelRecord(cancelId));
  }

  private void dispatchCancel(UUID cancelId, Waybill w, WaybillCancelInput input,
      Carrier carrier, String cancelNo) {
    var resolution = adapters.resolve(w.carrierCode(), carrier == null ? "" : carrier.adapterKey());
    if (resolution instanceof CarrierRedirectPort.Resolution.Unavailable u) {
      sql.execute(
          "update logistics_waybill_cancel set can_succeed = false,"
              + " can_succeed_reason = ?, updated_at = now() at time zone 'UTC' where id = ?",
          "渠道在派发时变为未接通：" + u.reason(), cancelId);
      return;
    }
    var port = ((CarrierRedirectPort.Resolution.Connected) resolution).port();
    CarrierRedirectPort.CancelOutcome outcome;
    try {
      outcome = port.cancel(new CarrierRedirectPort.CancelCommand(
          cancelId, cancelNo, w.waybillNo(), w.carrierCode(),
          carrier == null ? "" : carrier.carrierChannel(), input.reason()));
    } catch (RuntimeException e) {
      outcome = CarrierRedirectPort.CancelOutcome.unavailable(
          e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
    }
    // 与改址同一解法：比对的是**端口**枚举，落库列只有三种值。
    boolean accepted = outcome.outcome() == CarrierOutcome.ACCEPTED;
    sql.execute(
        "update logistics_waybill_cancel set outcome = ?,"
            + " can_succeed = ?, carrier_ref = ?, outcome_reason = ?,"
            + " can_succeed_reason = ?,"
            + " dispatched_at = ?::timestamptz, updated_at = ?::timestamptz where id = ?",
        accepted ? "ACCEPTED"
            : (outcome.outcome() == CarrierOutcome.REJECTED
                ? "REJECTED" : "NOT_ATTEMPTED"),
        accepted, outcome.carrierRef(), nz(outcome.reason()),
        accepted ? "承运商已受理取消（回执 " + outcome.carrierRef() + "）"
            : "承运商**未受理**取消：" + nz(outcome.reason()),
        OffsetDateTime.now(ZoneOffset.UTC).withNano(0),
        OffsetDateTime.now(ZoneOffset.UTC).withNano(0), cancelId);
  }

  private void maybeNotifyCancel(UUID cancelId, Record stored) {
    NotificationKind kind =
        notificationKind(
            DeadlineVerdict.valueOf(stored.get("verdict", String.class)),
            StoredOutcome.valueOf(stored.get("outcome", String.class)),
            false);
    if (kind == null) return;
    createNotification(null, cancelId, kind);
  }

  // ================================================================ 买家通知任务

  /** 买家通知任务清单（15 接走发送；本模块只读不改）。 */
  public BuyerNotificationTaskPage notificationTasks(String notificationKind, String salesOrderNo,
      int page, int size) {
    int limit = pageSize(size);
    var clauses = new ArrayList<String>();
    var params = new ArrayList<Object>();
    if (notificationKind != null && !notificationKind.isBlank()) {
      clauses.add("n.notification_kind = ?");
      params.add(notificationKind.trim());
    }
    if (salesOrderNo != null && !salesOrderNo.isBlank()) {
      clauses.add("n.sales_order_no = ?");
      params.add(salesOrderNo.trim());
    }
    String where = clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses);

    long total = countOf("select count(*) from logistics_buyer_notification_task n" + where,
        params.toArray());
    List<BuyerNotificationTaskRow> items = new ArrayList<>();
    if (total > 0) {
      sql.fetch(
              "select n.id, n.notification_no, n.redirect_id, n.cancel_id, n.sales_order_no,"
                  + " n.waybill_no, n.carrier_code, n.notification_kind, n.subject_line,"
                  + " n.body_summary, n.status, n.owner_module, n.created_at"
                  + " from logistics_buyer_notification_task n" + where
                  + " order by n.created_at desc, n.notification_no desc limit ? offset ?",
              concat(params, limit, (long) Math.max(page, 0) * limit))
          .forEach(x -> items.add(new BuyerNotificationTaskRow(
              x.get("id", UUID.class), x.get("notification_no", String.class),
              x.get("redirect_id", UUID.class), x.get("cancel_id", UUID.class),
              x.get("sales_order_no", String.class), x.get("waybill_no", String.class),
              x.get("carrier_code", String.class),
              NotificationKind.valueOf(x.get("notification_kind", String.class)),
              NotificationKind.valueOf(x.get("notification_kind", String.class)).displayName,
              x.get("subject_line", String.class), x.get("body_summary", String.class),
              x.get("status", String.class), x.get("owner_module", String.class),
              x.get("created_at", OffsetDateTime.class))));
    }

    // 汇总走**独立聚合**：status / owner_module 现在恒为常量（DB CHECK 钉死），
    // 但那是**今天**的约束，不是这页的语义。用真聚合而不是把 total 抄三遍 ——
    // 一旦 15 接走后状态机变宽，写死的三个 total 会立刻变成假活。
    Record agg = sql.fetchOne(
        "select count(*) as total,"
            + " count(*) filter (where status = 'QUEUED') as queued,"
            + " count(*) filter (where owner_module = '15') as owned_by_15"
            + " from logistics_buyer_notification_task n" + where,
        params.toArray());
    long grandTotal = longOf(agg.get("total", Object.class));
    long queuedCount = longOf(agg.get("queued", Object.class));
    long ownedBy15 = longOf(agg.get("owned_by_15", Object.class));

    return new BuyerNotificationTaskPage(
        items, total, queuedCount, ownedBy15,
        List.of(
            "本模块**只建任务不发送**：15 客户与客服在 crm 服务，跨服务不得直连对方库 / 建跨库 FK（AGENTS.md）",
            "status 恒为 QUEUED、ownerModule=15 —— 「已送达」本模块**不产出**，"
                + "发出后由 15 自己管状态",
            "任务**只追加不修改不删除**（DB 触发器钉死）：已发出去的通知无法收回",
            "有结论（受理 / 拒收 / 超时 / 不支持）才建任务；"
                + "还在等承运商回话的不建 —— 那时还没有结论可通知买家",
            "汇总覆盖筛选后的全量，与分页无关"));
  }

  /** 建一条买家通知任务。收件对象与措辞要点来自来源单据（触发器会核对一致性）。 */
  private void createNotification(UUID redirectId, UUID cancelId, NotificationKind kind) {
    String sourceNo;
    String waybillNo;
    String carrierCode;
    String salesOrderNo;
    if (redirectId != null) {
      Record r = sql.fetchOne(
          "select redirect_no, waybill_no, carrier_code, coalesce(sales_order_no,'') as so"
              + " from logistics_waybill_redirect where id = ?", redirectId);
      if (r == null) return;
      sourceNo = r.get("redirect_no", String.class);
      waybillNo = r.get("waybill_no", String.class);
      carrierCode = r.get("carrier_code", String.class);
      salesOrderNo = r.get("so", String.class);
    } else {
      Record r = sql.fetchOne(
          "select cancel_no, waybill_no, carrier_code, coalesce(sales_order_no,'') as so"
              + " from logistics_waybill_cancel where id = ?", cancelId);
      if (r == null) return;
      sourceNo = r.get("cancel_no", String.class);
      waybillNo = r.get("waybill_no", String.class);
      carrierCode = r.get("carrier_code", String.class);
      salesOrderNo = r.get("so", String.class);
    }

    String subject = kind.displayName + "（运单 " + waybillNo + "）";
    String body = "单据 " + sourceNo + "：运单号 " + waybillNo + "，承运商 " + carrierCode
        + "。请以本条结论答复买家，不要另行推测。";
    sql.execute(
        "insert into logistics_buyer_notification_task"
            + " (id, tenant_id, notification_no, redirect_id, cancel_id, sales_order_no,"
            + "  waybill_no, carrier_code, notification_kind, subject_line, body_summary,"
            + "  status, owner_module, created_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,'QUEUED','15',?::timestamptz)"
            + " on conflict do nothing",
        UUID.randomUUID(), currentTenant(), nextNo("buyer_notification_no_seq", "BNT"), redirectId,
        cancelId, salesOrderNo, waybillNo, carrierCode, kind.name(), subject, body,
        OffsetDateTime.now(ZoneOffset.UTC).withNano(0));
  }

  // ================================================================ 预判（批量判定「能不能改」）

  /**
   * 批量预判：一票问清一批运单「现在能不能改 / 能不能撤」。
   *
   * <p>这是蓝图加粗句最直接的落地形态：客服手上有一堆买家在问改址的票，
   * 一次性问清「哪些还能改、哪些已经没戏」，而不是逐票点开试。
   *
   * <p>预判**不落库**（纯读 + 现算），所以可以反复调而不会产生一堆单据。
   * 基准同样是落库数据（出号时刻 + 规则），传 {@code asOf} 时用它当判定时刻；
   * 不传则用服务端当前时刻。
   */
  public WaybillRedirectPrecheckResult precheck(WaybillRedirectPrecheckInput input,
      OffsetDateTime asOf) {
    OffsetDateTime at = (asOf == null ? OffsetDateTime.now(ZoneOffset.UTC).withNano(0) : asOf)
        .withNano(0);
    List<WaybillRedirectPrecheckEntry> entries = new ArrayList<>();
    int issued = 0, notIssued = 0, can = 0, cannot = 0;
    for (String raw : input.requestIds()) {
      UUID requestId;
      try {
        requestId = UUID.fromString(raw);
      } catch (IllegalArgumentException e) {
        throw badRequest("运单 id 不是合法 UUID：" + raw);
      }
      Record r = sql.fetchOne(
          "select id, request_no, waybill_no, carrier_code, status, issued_at"
              + " from logistics_waybill_request where id = ?", requestId);
      if (r == null) throw notFound("运单不存在：" + raw);
      String status = r.get("status", String.class);
      boolean isIssued = "ISSUED".equals(status);
      String carrierCode = r.get("carrier_code", String.class);
      if (isIssued) issued++; else notIssued++;

      DeadlineVerdict verdict;
      boolean canSucceed = false;
      String reason;
      OffsetDateTime deadline = null;
      Integer redirectHours = null, cancelHours = null;
      if (!isIssued) {
        verdict = DeadlineVerdict.RULE_UNREGISTERED;
        reason = "这一票还没出号（状态 " + status + "），**不走改址**：未出号的作废请走 10.2.1.2";
      } else {
        CarrierRule rule = carrierRule(carrierCode);
        if (rule == null) {
          verdict = DeadlineVerdict.RULE_UNREGISTERED;
          reason = "未登记该承运商的规则，本模块**不知道**能不能改";
        } else {
          redirectHours = rule.redirectSupported() ? rule.redirectDeadlineHours() : null;
          cancelHours = rule.cancelSupported() ? rule.cancelDeadlineHours() : null;
          if (!rule.redirectSupported()) {
            verdict = DeadlineVerdict.CHANNEL_UNSUPPORTED;
            reason = "该承运商不支持改址（" + rule.unsupportedReason() + "）";
          } else {
            OffsetDateTime issuedAt = r.get("issued_at", OffsetDateTime.class);
            deadline = issuedAt.plusHours(rule.redirectDeadlineHours());
            if (at.isAfter(deadline)) {
              verdict = DeadlineVerdict.DEADLINE_PASSED;
              reason = "已超过改址时限（截止 " + deadline + "）：**再也不会**成功";
            } else {
              verdict = DeadlineVerdict.WITHIN_WINDOW;
              var resolution = adapters.resolve(carrierCode, adapterKeyOf(carrierCode));
              boolean connected =
                  resolution instanceof CarrierRedirectPort.Resolution.Connected;
              canSucceed = connected;
              reason = connected
                  ? "在时限内且渠道已接通"
                  : "在时限内（截止 " + deadline + "）但**渠道未接通**，接通后仍可成功";
            }
          }
        }
      }
      if (canSucceed) can++; else cannot++;
      Long minutesToDeadline =
          deadline == null ? null
              : Duration.between(at, deadline).toMinutes();
      boolean adapterConnected =
          isIssued && adapters.resolve(carrierCode, adapterKeyOf(carrierCode))
              instanceof CarrierRedirectPort.Resolution.Connected;
      entries.add(new WaybillRedirectPrecheckEntry(
          requestId, r.get("request_no", String.class), r.get("waybill_no", String.class),
          carrierCode, isIssued,
          isIssued ? "已出号，可走改址" : "未出号（" + status + "），请走 10.2.1.2 作废",
          verdict, verdict.displayName, canSucceed, reason, deadline, minutesToDeadline,
          adapterConnected, redirectHours, cancelHours));
    }
    return new WaybillRedirectPrecheckResult(
        entries.size(), issued, notIssued, can, cannot, entries,
        List.of(
            "预判**不落库**（纯读 + 现算），可反复调用而不产生单据",
            "判定基准 = 出号时刻 + 该承运商登记的改址时限，**逐票可复算**",
            "canSucceed=false 分两类话术：「已超时限 → 再也不会成功」与"
                + "「在时限内但渠道未接通 → 接通后仍可成功」，界面必须分开显示",
            "未出号的运单**不走改址**：作废请走 10.2.1.2"));
  }

  // ================================================================ 内部：判定与读模型

  private record Waybill(UUID id, String requestNo, String waybillNo, String carrierCode,
      String salesOrderNo, OffsetDateTime issuedAt) {}

  private record Carrier(String carrierChannel, String adapterKey) {}

  private record CarrierRule(boolean redirectSupported, Integer redirectDeadlineHours,
      boolean cancelSupported, Integer cancelDeadlineHours, String unsupportedReason) {}

  private static final String WAYBILL_COLUMNS =
      "request_no, waybill_no, carrier_code, sales_order_no, issued_at, status";

  /**
   * 取一张**已出号**的运单（改址 / 取消的唯一合法对象）。
   *
   * <p>不是已出号时**明确指向 10.2.1.2 的作废**而不是笼统报错 ——
   * 「未出号请去那边作废」是客服立刻能执行的下一步，
   * 而「运单状态不允许」只会让人再点一次然后放弃。
   */
  private Waybill requireIssuedWaybill(UUID requestId) {
    Record r = sql.fetchOne(
        "select " + WAYBILL_COLUMNS + " from logistics_waybill_request where id = ?", requestId);
    if (r == null) throw notFound("运单不存在：" + requestId);
    String status = r.get("status", String.class);
    if (!"ISSUED".equals(status))
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "运单 " + r.get("request_no", String.class) + " 当前状态为 " + status
              + "，只有**已出号（ISSUED）**的运单才走改址 / 取消；"
              + "还没出号的请到 10.2.1.2「运单申请」里作废");
    return new Waybill(requestId, r.get("request_no", String.class),
        r.get("waybill_no", String.class), r.get("carrier_code", String.class),
        r.get("sales_order_no", String.class), r.get("issued_at", OffsetDateTime.class));
  }

  private CarrierRule carrierRule(String carrierCode) {
    Record r = sql.fetchOne(
        "select redirect_supported, redirect_deadline_hours, cancel_supported,"
            + " cancel_deadline_hours, unsupported_reason"
            + " from logistics_carrier_redirect_rule where carrier_code = ?",
        carrierCode);
    if (r == null) return null;
    return new CarrierRule(
        Boolean.TRUE.equals(r.get("redirect_supported", Boolean.class)),
        r.get("redirect_deadline_hours", Integer.class),
        Boolean.TRUE.equals(r.get("cancel_supported", Boolean.class)),
        r.get("cancel_deadline_hours", Integer.class),
        r.get("unsupported_reason", String.class));
  }

  private Carrier carrierMaster(String carrierCode) {
    Record r = sql.fetchOne(
        "select carrier_channel, adapter_key from logistics_carrier where code = ?", carrierCode);
    return r == null ? null
        : new Carrier(r.get("carrier_channel", String.class), r.get("adapter_key", String.class));
  }

  private String adapterKeyOf(String carrierCode) {
    Carrier c = carrierMaster(carrierCode);
    return c == null ? "" : nz(c.adapterKey());
  }

  /**
   * 校验联动的订单地址变更单（9.1.1.3）。
   *
   * <p>三项都要核：存在、{@code change_kind='ADDRESS_MODIFY'}、与本运单<b>同订单</b>。
   * 少任何一项都会把一次运单改址挂到不相干的订单单据上，
   * 而订单侧与运单侧从此各说各话。
   */
  private void requireLinkedOrderChange(UUID orderChangeId, String salesOrderNo) {
    Record r = sql.fetchOne(
        "select order_no, change_kind, status from order_change_request where id = ?",
        orderChangeId);
    if (r == null)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND,
          "联动的订单地址变更单不存在：" + orderChangeId
              + "（该表由 9.1.1.3 拥有，本模块只引用不写入）");
    if (!"ADDRESS_MODIFY".equals(r.get("change_kind", String.class)))
      throw badRequest("订单变更单 " + orderChangeId + " 的类型是 "
          + r.get("change_kind", String.class) + "，不是 ADDRESS_MODIFY，不能作为改址联动的依据");
    if (!r.get("order_no", String.class).equals(salesOrderNo))
      throw badRequest("订单变更单 " + orderChangeId + " 属于订单 "
          + r.get("order_no", String.class) + "，与本运单的订单 " + salesOrderNo
          + " 不是同一张单，不能联动");
  }

  private void requireLinkedOrderCancellation(UUID orderCancelId, String salesOrderNo) {
    Record r = sql.fetchOne(
        "select order_no, status from order_cancellation_request where id = ?", orderCancelId);
    if (r == null)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND,
          "联动的订单取消单不存在：" + orderCancelId
              + "（该表由 9.1.1.3 拥有，本模块只引用不写入）");
    if (!r.get("order_no", String.class).equals(salesOrderNo))
      throw badRequest("订单取消单 " + orderCancelId + " 属于订单 "
          + r.get("order_no", String.class) + "，与本运单的订单 " + salesOrderNo
          + " 不是同一张单，不能联动");
  }

  /** 承运商侧口径：规则登记与适配器接通**分两列**（两种处境不能画成同一个绿）。 */
  private WaybillRedirectCarrierState carrierState(String carrierCode) {
    CarrierRule rule = carrierRule(carrierCode);
    Carrier master = carrierMaster(carrierCode);
    String adapterKey = master == null ? "" : nz(master.adapterKey());
    boolean connected =
        adapters.resolve(carrierCode, adapterKey) instanceof CarrierRedirectPort.Resolution.Connected;
    String unavailableReason =
        connected ? ""
            : (rule == null
                ? "未登记该承运商的改址 / 取消规则（先登记规则才知道支持不支持）"
                : "10.1 物流渠道未交付，该承运商的改址 / 取消通道未接通"
                    + "（ownerModule=10.1.1.1 / 10.1.1.2）");
    return new WaybillRedirectCarrierState(
        carrierCode, master != null,
        rule != null && rule.redirectSupported(),
        rule != null && rule.cancelSupported(),
        rule == null ? null : rule.redirectDeadlineHours(),
        rule == null ? null : rule.cancelDeadlineHours(),
        connected, connected, unavailableReason,
        "「规则登记」与「适配器接通」是两件事：登记说支持、但适配器没接通时，"
            + "这一票是「规则允许但现在发不出去」，接通了仍可成功");
  }

  /**
   * 由**落库的判定 + 渠道结果**推出通知类型；{@code null} = 还没有结论，不该通知。
   *
   * <p>⚠️ 取的是 {@code outcome}（真实渠道回话）而不是判定阶段的 canSucceed：承运商
   * 拒收时 canSucceed 还是 true，照它建任务会给买家发「改址已受理」。
   *
   * <p>另外两处刻意的「不通知」：
   *
   * <ul>
   *   <li>NOT_ATTEMPTED + WITHIN_WINDOW：渠道没接通，**还没有结论**。提前告诉买家
   *       「正在处理」会让人以为已经改好了。
   *   <li>RULE_UNREGISTERED：那是**系统不知道**，不是给买家的结论 —— 该做的是让运营
   *       去登记规则，而不是拿「我们也不确定」去打扰买家。
   * </ul>
   */
  private NotificationKind notificationKind(
      DeadlineVerdict verdict, StoredOutcome outcome, boolean redirect) {
    if (outcome == StoredOutcome.ACCEPTED)
      return redirect ? NotificationKind.REDIRECT_ACCEPTED : NotificationKind.CANCEL_ACCEPTED;
    if (outcome == StoredOutcome.REJECTED)
      return redirect ? NotificationKind.REDIRECT_REJECTED : NotificationKind.CANCEL_REJECTED;
    // 没有渠道结果时，只有「已经判死」的两类才是结论；其余都还没到能通知的时候。
    return switch (verdict) {
      case DEADLINE_PASSED -> redirect ? NotificationKind.REDIRECT_EXPIRED
          : NotificationKind.CANCEL_EXPIRED;
      case CHANNEL_UNSUPPORTED -> redirect ? NotificationKind.REDIRECT_UNSUPPORTED
          : NotificationKind.CANCEL_UNSUPPORTED;
      // WITHIN_WINDOW + 没有渠道结果 = 还在等承运商回话，还没有结论。
      // RULE_UNREGISTERED = 系统不知道，不是给买家的结论。
      default -> null;
    };
  }

  private List<String> nextActions(WaybillRedirectRow row) {
    List<String> actions = new ArrayList<>();
    if (row.verdict().hopeless())
      actions.add("**无法改址**：请改用「取消 + 重新发货」或与买家确认其它方案");
    if (!row.verdict().hopeless() && !row.canSucceed())
      actions.add("在时限内但**渠道未接通**：先到 10.1 渠道配置接通该承运商，再重新发起改址");
    if (row.outcome() == StoredOutcome.ACCEPTED)
      actions.add("已受理：以承运商回执为准，可联系买家确认改址是否已生效");
    if (row.outcome() == StoredOutcome.REJECTED)
      actions.add("被承运商拒收：按 outcome_reason 处理（常见是票已揽收）");
    if (row.outcome() == StoredOutcome.NOT_ATTEMPTED)
      actions.add("**未派发**：渠道未接通，本模块不会凭空受理");
    if (row.orderChangeRequestId() == null && row.verdict() != DeadlineVerdict.RULE_UNREGISTERED)
      actions.add("可携带 9.1.1.3 的 ADDRESS_MODIFY 单据 id 建立订单侧联动");
    return actions;
  }

  private List<String> cancelNextActions(WaybillCancelRow row) {
    List<String> actions = new ArrayList<>();
    if (row.verdict().hopeless())
      actions.add("**无法取消**：请改走承运商侧其它退回路径");
    if (!row.verdict().hopeless() && !row.canSucceed())
      actions.add("在时限内但**渠道未接通**：先接通该承运商再重新发起取消");
    if (row.outcome() == StoredOutcome.ACCEPTED)
      actions.add("已受理：以承运商回执为准");
    if (row.outcome() == StoredOutcome.REJECTED)
      actions.add("被承运商拒收：按 outcome_reason 处理");
    if (row.outcome() == StoredOutcome.NOT_ATTEMPTED)
      actions.add("**未派发**：渠道未接通，本模块不会凭空受理");
    return actions;
  }

  private Record redirectRecord(UUID id) {
    return sql.fetchOne(
        "select r.id, r.redirect_no, r.request_id, r.request_no, r.waybill_no, r.carrier_code,"
            + " r.issued_at_snapshot, r.deadline_at, r.recipient_name, r.recipient_country,"
            + " r.address_line, r.city, r.postal_code, r.reason, r.verdict, r.can_succeed,"
            + " r.can_succeed_reason, r.outcome, r.carrier_ref, r.outcome_reason, r.dispatched_at,"
            + " r.order_change_request_id, coalesce(r.sales_order_no,'') as sales_order_no,"
            + " r.requested_at, r.updated_at, w.carrier_channel, n.id as notification_id,"
            + " n.notification_no, oc.status as order_change_status"
            + " from logistics_waybill_redirect r"
            + " join logistics_waybill_request w on w.tenant_id = r.tenant_id and w.id = r.request_id"
            + " left join logistics_buyer_notification_task n"
            + "   on n.tenant_id = r.tenant_id and n.redirect_id = r.id"
            + " left join order_change_request oc"
            + "   on oc.tenant_id = r.tenant_id and oc.id = r.order_change_request_id"
            + " where r.id = ?",
        id);
  }

  private Record cancelRecord(UUID id) {
    return sql.fetchOne(
        "select r.id, r.cancel_no, r.request_id, r.request_no, r.waybill_no, r.carrier_code,"
            + " r.issued_at_snapshot, r.deadline_at, r.reason, r.verdict, r.can_succeed,"
            + " r.can_succeed_reason, r.outcome, r.carrier_ref, r.outcome_reason, r.dispatched_at,"
            + " r.order_cancellation_request_id,"
            + " coalesce(r.sales_order_no,'') as sales_order_no, r.requested_at, r.updated_at,"
            + " w.carrier_channel, n.id as notification_id, n.notification_no,"
            + " oc.status as order_cancellation_status"
            + " from logistics_waybill_cancel r"
            + " join logistics_waybill_request w on w.tenant_id = r.tenant_id and w.id = r.request_id"
            + " left join logistics_buyer_notification_task n"
            + "   on n.tenant_id = r.tenant_id and n.cancel_id = r.id"
            + " left join order_cancellation_request oc"
            + "   on oc.tenant_id = r.tenant_id and oc.id = r.order_cancellation_request_id"
            + " where r.id = ?",
        id);
  }

  private WaybillRedirectRow redirectRow(Record r) {
    DeadlineVerdict verdict = DeadlineVerdict.valueOf(r.get("verdict", String.class));
    OffsetDateTime requestedAt = r.get("requested_at", OffsetDateTime.class);
    OffsetDateTime deadline = r.get("deadline_at", OffsetDateTime.class);
    UUID notificationId = r.get("notification_id", UUID.class);
    UUID orderChangeId = r.get("order_change_request_id", UUID.class);
    return new WaybillRedirectRow(
        r.get("id", UUID.class), r.get("redirect_no", String.class),
        r.get("request_id", UUID.class), r.get("request_no", String.class),
        r.get("waybill_no", String.class), r.get("carrier_code", String.class),
        r.get("carrier_channel", String.class), r.get("issued_at_snapshot", OffsetDateTime.class),
        deadline, minutesBetween(requestedAt, deadline),
        r.get("recipient_name", String.class), r.get("recipient_country", String.class),
        r.get("address_line", String.class), r.get("city", String.class),
        r.get("postal_code", String.class), r.get("reason", String.class),
        verdict, verdict.displayName,
        Boolean.TRUE.equals(r.get("can_succeed", Boolean.class)),
        r.get("can_succeed_reason", String.class),
        StoredOutcome.valueOf(r.get("outcome", String.class)),
        StoredOutcome.valueOf(r.get("outcome", String.class)).displayName,
        r.get("carrier_ref", String.class), r.get("outcome_reason", String.class),
        r.get("dispatched_at", OffsetDateTime.class), orderChangeId,
        r.get("sales_order_no", String.class), r.get("order_change_status", String.class),
        orderChangeId == null
            ? "未联动订单地址变更单（本模块只引用 9.1.1.3 的单据，不代写）"
            : "已联动订单地址变更单（状态 " + r.get("order_change_status", String.class)
                + "，写入权归 9.1.1.3）",
        notificationId, r.get("notification_no", String.class),
        requestedAt, r.get("updated_at", OffsetDateTime.class),
        nextActionLine(verdict, r));
  }

  private WaybillCancelRow cancelRow(Record r) {
    DeadlineVerdict verdict = DeadlineVerdict.valueOf(r.get("verdict", String.class));
    OffsetDateTime requestedAt = r.get("requested_at", OffsetDateTime.class);
    OffsetDateTime deadline = r.get("deadline_at", OffsetDateTime.class);
    UUID notificationId = r.get("notification_id", UUID.class);
    UUID orderCancelId = r.get("order_cancellation_request_id", UUID.class);
    return new WaybillCancelRow(
        r.get("id", UUID.class), r.get("cancel_no", String.class),
        r.get("request_id", UUID.class), r.get("request_no", String.class),
        r.get("waybill_no", String.class), r.get("carrier_code", String.class),
        r.get("carrier_channel", String.class), r.get("issued_at_snapshot", OffsetDateTime.class),
        deadline, minutesBetween(requestedAt, deadline),
        r.get("reason", String.class), verdict, verdict.displayName,
        Boolean.TRUE.equals(r.get("can_succeed", Boolean.class)),
        r.get("can_succeed_reason", String.class),
        StoredOutcome.valueOf(r.get("outcome", String.class)),
        StoredOutcome.valueOf(r.get("outcome", String.class)).displayName,
        r.get("carrier_ref", String.class), r.get("outcome_reason", String.class),
        r.get("dispatched_at", OffsetDateTime.class), orderCancelId,
        r.get("sales_order_no", String.class),
        r.get("order_cancellation_status", String.class),
        orderCancelId == null
            ? "未联动订单取消单（本模块只引用 9.1.1.3 的单据，不代写）"
            : "已联动订单取消单（状态 " + r.get("order_cancellation_status", String.class)
                + "，写入权归 9.1.1.3）",
        notificationId, r.get("notification_no", String.class),
        requestedAt, r.get("updated_at", OffsetDateTime.class),
        nextActionLine(verdict, r));
  }

  /** 「下一步该做什么」一行摘要（详情里还会给更完整的 nextActions 列表）。 */
  private String nextActionLine(DeadlineVerdict verdict, Record r) {
    if (verdict == DeadlineVerdict.DEADLINE_PASSED)
      return "**再也不会成功**：请改用「取消 + 重新发货」或与买家确认其它方案";
    if (verdict == DeadlineVerdict.CHANNEL_UNSUPPORTED) return "该渠道不支持此操作，**一定**不会成功";
    if (verdict == DeadlineVerdict.RULE_UNREGISTERED)
      return "先到「渠道规则」登记该承运商，才知道支持不支持";
    // 在时限内：先说**有没有派出去**，再说能不能成。
    // 顺序反了会把「渠道没接通、压根没派发」显示成「再等等就能成」——
    // 客服会一直等一个永远不会到来的受理。
    var outcome = StoredOutcome.valueOf(r.get("outcome", String.class));
    if (outcome == StoredOutcome.NOT_ATTEMPTED
        && !Boolean.TRUE.equals(r.get("can_succeed", Boolean.class)))
      return "**未派发**：渠道未接通，本模块不会凭空受理；接通后可重新发起";
    return switch (outcome) {
      case ACCEPTED -> "已受理：以承运商回执为准";
      case REJECTED -> "被承运商拒收：按 outcome_reason 处理";
      default -> Boolean.TRUE.equals(r.get("can_succeed", Boolean.class))
          ? "在时限内且渠道已接通，可提交"
          : "在时限内但**渠道未接通**：接通后可重新发起";
    };
  }

  private CarrierRedirectRuleRow ruleRow(Record r) {
    return new CarrierRedirectRuleRow(
        r.get("id", UUID.class), r.get("carrier_code", String.class),
        r.get("carrier_channel", String.class),
        Boolean.TRUE.equals(r.get("redirect_supported", Boolean.class)),
        r.get("redirect_deadline_hours", Integer.class),
        Boolean.TRUE.equals(r.get("cancel_supported", Boolean.class)),
        r.get("cancel_deadline_hours", Integer.class),
        r.get("unsupported_reason", String.class), r.get("remark", String.class),
        r.get("registered_by_membership_id", UUID.class),
        r.get("registered_at", OffsetDateTime.class));
  }

  // ================================================================ 筛选与工具

  private record Filters(String clause, List<Object> params) {}

  /**
   * 筛选条件（改址 / 取消共用的那几项）。
   *
   * <p>⚠️ {@code keyword} 的列集合由调用方传入：改址单有 {@code recipient_name}
   * （新收件人），**取消单没有这一列** —— 两张表共用一个过滤方法时把列写死，
   * 取消清单一搜关键词就会撞 {@code column "recipient_name" does not exist}。
   * 客服恰好是在取消清单上按收件人搜的时候才会踩到，所以在这里显式区分。
   */
  private Filters redirectFilters(String carrierCode, String verdict, String outcome,
      String salesOrderNo, String keyword, OffsetDateTime dateFrom, OffsetDateTime dateTo,
      boolean withRecipientColumn) {
    var clauses = new ArrayList<String>();
    var params = new ArrayList<Object>();
    if (carrierCode != null && !carrierCode.isBlank()) {
      clauses.add("r.carrier_code = ?");
      params.add(carrierCode.trim().toUpperCase(Locale.ROOT));
    }
    if (verdict != null && !verdict.isBlank()) {
      clauses.add("r.verdict = ?");
      params.add(verdict.trim());
    }
    if (outcome != null && !outcome.isBlank()) {
      clauses.add("r.outcome = ?");
      params.add(outcome.trim());
    }
    if (salesOrderNo != null && !salesOrderNo.isBlank()) {
      clauses.add("r.sales_order_no = ?");
      params.add(salesOrderNo.trim());
    }
    if (keyword != null && !keyword.isBlank()) {
      // 关键词覆盖：单号 / 运单号 / 申请单号（+ 改址单的收件人）—— 客服手上通常只有其中一个。
      // ⚠️ 两张表的**单号列名不同**（redirect_no / cancel_no），且取消单没有 recipient_name。
      // 把两组列写死成一个跨表通用条件，会让取消清单一搜关键词就撞
      // 「column "redirect_no" does not exist」—— 错得毫无线索，因为改址清单是好的。
      String like = "%" + keyword.trim() + "%";
      clauses.add(withRecipientColumn
          ? "(r.redirect_no ilike ? or r.waybill_no ilike ? or r.request_no ilike ?"
              + " or r.recipient_name ilike ?)"
          : "(r.cancel_no ilike ? or r.waybill_no ilike ? or r.request_no ilike ?)");
      params.add(like); params.add(like); params.add(like);
      if (withRecipientColumn) params.add(like);
    }
    if (dateFrom != null) {
      clauses.add("r.requested_at >= ?::timestamptz");
      params.add(dateFrom);
    }
    if (dateTo != null) {
      clauses.add("r.requested_at < ?::timestamptz");
      params.add(dateTo);
    }
    return new Filters(clauses.isEmpty() ? "" : String.join(" and ", clauses), params);
  }

  /** 排序白名单：表名/列名一律来自上面的常量，不接受调用方传入的排序片段。 */
  private String redirectOrderBy(String sort) {
    if (sort == null) return "r.requested_at desc, r.redirect_no desc";
    return switch (sort) {
      case "REQUESTED_AT_ASC" -> "r.requested_at asc, r.redirect_no asc";
      case "CARRIER_CODE_ASC" -> "r.carrier_code asc, r.requested_at desc";
      case "DEADLINE_AT_ASC" -> "r.deadline_at asc nulls last, r.requested_at desc";
      case "VERDICT_ASC" -> "r.verdict asc, r.requested_at desc";
      default -> "r.requested_at desc, r.redirect_no desc";
    };
  }

  private String nextNo(String sequence, String prefix) {
    // 单号由 DB 发（并发提交不撞号）：RAC / WAC / BNT + UTC 日期 + 6 位序号。
    return sql.fetchOne(
            "select '" + prefix + "-' || to_char(now() at time zone 'UTC', 'YYYYMMDD') || '-'"
                + " || lpad(nextval('" + sequence + "')::text, 6, '0') as no")
        .get("no", String.class);
  }

  /**
   * 当前 RLS 租户。
   *
   * <p>与兄弟服务（{@code WaybillListingService.currentTenant}）同一形状：
   * 租户只能来自 backend/libraries/tenancy 在事务内设的 {@code app.current_tenant}，
   * 客户端的 {@code X-Tenant-Id} 头进不来也改不动它。四张表的 {@code WITH CHECK}
   * 会再钉一道 —— 写错租户的行在 INSERT 阶段就被拒。
   */
  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid as t")
        .get("t", UUID.class);
  }

  /** 「还剩多少分钟到截止时刻」。null = 没有时限（规则未登记 / 不支持），**不编一个数**。 */
  private static Long minutesBetween(OffsetDateTime from, OffsetDateTime to) {
    return to == null ? null : Duration.between(from, to).toMinutes();
  }

  private int pageSize(int size) {
    if (size <= 0) return 20;
    return Math.min(size, MAX_PAGE_SIZE);
  }

  private long countOf(String statement, Object... params) {
    return longOf(sql.fetchOne(statement, params).get(0, Long.class));
  }

  /**
   * 把筛选参数与分页参数拼成 jOOQ 的<b>绑定值数组</b>。
   *
   * <p>⚠️ 返回 {@code Object[]} 而不是 {@code List}：jOOQ 的重载是
   * {@code fetch(String, Object...)}，传一个 {@code List} 进去会被当成**单个**
   * 绑定值（不是展开成 N 个），于是报
   * {@code Error while writing value at bind index: 1}。返回数组才能真正展开。
   */
  private static Object[] concat(List<Object> params, Object... more) {
    var out = new ArrayList<Object>(params);
    out.addAll(java.util.Arrays.asList(more));
    return out.toArray();
  }

  private static long longOf(Object o) {
    return o instanceof Number n ? n.longValue() : 0L;
  }

  private static String nz(String s) {
    return s == null ? "" : s;
  }  private static UUID parseUuid(String id, String notFoundMessage) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, notFoundMessage);
    }
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
